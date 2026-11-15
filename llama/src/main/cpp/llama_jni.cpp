// JNI bridge between com.jbsoft.jobq.llama.LlamaNative and llama.cpp.
//
// Text crosses the boundary as UTF-8 byte arrays rather than jstring: JNI strings are
// "modified UTF-8", which mangles characters outside the BMP, and a token piece can end
// in the middle of a multi-byte character.

#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <cmath>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"

#define LOG_TAG "jobq-llama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct Session {
	llama_model * model = nullptr;
	llama_context * ctx = nullptr;
	std::atomic<bool> cancel{false};
};

void throw_java(JNIEnv * env, const char * cls_name, const std::string & msg) {
	jclass cls = env->FindClass(cls_name);
	if (cls != nullptr) {
		env->ThrowNew(cls, msg.c_str());
	}
}

void throw_llama(JNIEnv * env, const std::string & msg) {
	throw_java(env, "com/jbsoft/jobq/llama/LlamaException", msg);
}

std::string to_string(JNIEnv * env, jbyteArray bytes) {
	if (bytes == nullptr) {
		return {};
	}
	const jsize len = env->GetArrayLength(bytes);
	std::string out(static_cast<size_t>(len), '\0');
	if (len > 0) {
		env->GetByteArrayRegion(bytes, 0, len, reinterpret_cast<jbyte *>(out.data()));
	}
	return out;
}

jbyteArray to_bytes(JNIEnv * env, const std::string & s) {
	jbyteArray out = env->NewByteArray(static_cast<jsize>(s.size()));
	if (out != nullptr && !s.empty()) {
		env->SetByteArrayRegion(out, 0, static_cast<jsize>(s.size()), reinterpret_cast<const jbyte *>(s.data()));
	}
	return out;
}

void init_backend() {
	static std::once_flag once;
	std::call_once(once, [] {
		llama_log_set([](enum ggml_log_level level, const char * text, void *) {
			// Only these two: GGML_LOG_LEVEL_CONT (progress dots) sorts above ERROR.
			if (level == GGML_LOG_LEVEL_WARN || level == GGML_LOG_LEVEL_ERROR) {
				__android_log_print(level == GGML_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR : ANDROID_LOG_WARN, LOG_TAG, "%s", text);
			}
		}, nullptr);
		llama_backend_init();
	});
}

// Renders the conversation with the model's own chat template, falling back to ChatML
// when the template is missing or not one llama.cpp recognises.
bool apply_template(const llama_model * model, const std::string & system, const std::string & user, std::string & out) {
	std::vector<llama_chat_message> messages;
	if (!system.empty()) {
		messages.push_back({"system", system.c_str()});
	}
	messages.push_back({"user", user.c_str()});

	const char * model_tmpl = llama_model_chat_template(model, nullptr);
	for (const char * tmpl : {model_tmpl, static_cast<const char *>(nullptr)}) {
		std::vector<char> buf(2 * (system.size() + user.size()) + 1024);
		int32_t n = llama_chat_apply_template(tmpl, messages.data(), messages.size(), true, buf.data(), static_cast<int32_t>(buf.size()));
		if (n > static_cast<int32_t>(buf.size())) {
			buf.resize(static_cast<size_t>(n));
			n = llama_chat_apply_template(tmpl, messages.data(), messages.size(), true, buf.data(), static_cast<int32_t>(buf.size()));
		}
		if (n >= 0) {
			out.assign(buf.data(), static_cast<size_t>(n));
			return true;
		}
		if (tmpl == nullptr) {
			break;
		}
	}
	return false;
}

// Feeds `tokens` to the model starting at position `pos`, requesting logits for the last one.
bool decode(llama_context * ctx, llama_batch & batch, int32_t capacity, const llama_token * tokens, int32_t n, int32_t pos, const std::atomic<bool> & cancel) {
	for (int32_t done = 0; done < n;) {
		if (cancel.load()) {
			return false;
		}
		const int32_t chunk = std::min(capacity, n - done);
		batch.n_tokens = chunk;
		for (int32_t i = 0; i < chunk; ++i) {
			batch.token[i] = tokens[done + i];
			batch.pos[i] = pos + done + i;
			batch.n_seq_id[i] = 1;
			batch.seq_id[i][0] = 0;
			batch.logits[i] = 0;
		}
		done += chunk;
		if (done == n) {
			batch.logits[chunk - 1] = 1;
		}
		if (llama_decode(ctx, batch) != 0) {
			return false;
		}
	}
	return true;
}

// Samples the next token. With a grammar, the cheap path samples first and only checks that
// one token against the grammar; the full vocabulary is filtered only when it is rejected.
llama_token sample(llama_context * ctx, const llama_vocab * vocab, llama_sampler * chain, llama_sampler * grammar) {
	llama_token token = llama_sampler_sample(chain, ctx, -1);
	if (grammar == nullptr) {
		return token;
	}

	llama_token_data single = {token, 1.0f, 0.0f};
	llama_token_data_array single_arr = {&single, 1, -1, false};
	llama_sampler_apply(grammar, &single_arr);
	if (!std::isinf(single_arr.data[0].logit)) {
		llama_sampler_accept(grammar, token);
		return token;
	}

	const float * logits = llama_get_logits_ith(ctx, -1);
	const int32_t n_vocab = llama_vocab_n_tokens(vocab);
	std::vector<llama_token_data> candidates;
	candidates.reserve(static_cast<size_t>(n_vocab));
	for (llama_token id = 0; id < n_vocab; ++id) {
		candidates.push_back({id, logits[id], 0.0f});
	}
	llama_token_data_array arr = {candidates.data(), candidates.size(), -1, false};
	llama_sampler_apply(grammar, &arr);
	llama_sampler_apply(chain, &arr);
	token = arr.selected >= 0 ? arr.data[arr.selected].id : arr.data[0].id;
	llama_sampler_accept(grammar, token);
	return token;
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_jbsoft_jobq_llama_LlamaNative_load(JNIEnv * env, jobject, jbyteArray jpath, jint n_ctx, jint n_threads) {
	init_backend();
	const std::string path = to_string(env, jpath);

	llama_model_params mparams = llama_model_default_params();
	mparams.n_gpu_layers = 0;
	llama_model * model = llama_model_load_from_file(path.c_str(), mparams);
	if (model == nullptr) {
		throw_llama(env, "Could not load model file");
		return 0;
	}

	llama_context_params cparams = llama_context_default_params();
	cparams.n_ctx = static_cast<uint32_t>(n_ctx);
	cparams.n_batch = 512;
	cparams.n_threads = n_threads;
	cparams.n_threads_batch = n_threads;
	llama_context * ctx = llama_init_from_model(model, cparams);
	if (ctx == nullptr) {
		llama_model_free(model);
		throw_llama(env, "Could not create inference context (not enough memory?)");
		return 0;
	}

	auto * session = new Session();
	session->model = model;
	session->ctx = ctx;
	LOGI("model loaded, n_ctx=%u threads=%d", llama_n_ctx(ctx), n_threads);
	return reinterpret_cast<jlong>(session);
}

JNIEXPORT void JNICALL
Java_com_jbsoft_jobq_llama_LlamaNative_free(JNIEnv *, jobject, jlong handle) {
	auto * session = reinterpret_cast<Session *>(handle);
	if (session == nullptr) {
		return;
	}
	llama_free(session->ctx);
	llama_model_free(session->model);
	delete session;
}

JNIEXPORT void JNICALL
Java_com_jbsoft_jobq_llama_LlamaNative_cancel(JNIEnv *, jobject, jlong handle) {
	auto * session = reinterpret_cast<Session *>(handle);
	if (session != nullptr) {
		session->cancel.store(true);
	}
}

// Returns the generated text as UTF-8 bytes, or null when cancelled.
JNIEXPORT jbyteArray JNICALL
Java_com_jbsoft_jobq_llama_LlamaNative_generate(
		JNIEnv * env, jobject, jlong handle, jbyteArray jsystem, jbyteArray juser,
		jint max_tokens, jfloat temperature, jbyteArray jgrammar) {
	auto * session = reinterpret_cast<Session *>(handle);
	if (session == nullptr) {
		throw_llama(env, "Model is closed");
		return nullptr;
	}
	session->cancel.store(false);

	llama_context * ctx = session->ctx;
	const llama_vocab * vocab = llama_model_get_vocab(session->model);
	const std::string system = to_string(env, jsystem);
	const std::string user = to_string(env, juser);
	const std::string grammar_str = to_string(env, jgrammar);

	std::string prompt;
	if (!apply_template(session->model, system, user, prompt)) {
		throw_llama(env, "Could not apply the chat template");
		return nullptr;
	}

	const int32_t n_prompt = -llama_tokenize(vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()), nullptr, 0, true, true);
	std::vector<llama_token> tokens(static_cast<size_t>(std::max(n_prompt, 0)));
	if (n_prompt <= 0 || llama_tokenize(vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()), tokens.data(), n_prompt, true, true) < 0) {
		throw_llama(env, "Could not tokenize the prompt");
		return nullptr;
	}

	const int32_t n_ctx = static_cast<int32_t>(llama_n_ctx(ctx));
	const int32_t min_output = 64;
	if (n_prompt + min_output > n_ctx) {
		throw_java(env, "com/jbsoft/jobq/llama/PromptTooLongException",
				"Prompt is " + std::to_string(n_prompt) + " tokens, context is " + std::to_string(n_ctx));
		return nullptr;
	}
	const int32_t n_predict = std::min(static_cast<int32_t>(max_tokens), n_ctx - n_prompt);

	llama_sampler * grammar = nullptr;
	if (!grammar_str.empty()) {
		grammar = llama_sampler_init_grammar(vocab, grammar_str.c_str(), "root");
		if (grammar == nullptr) {
			throw_llama(env, "Invalid grammar");
			return nullptr;
		}
	}
	llama_sampler * chain = llama_sampler_chain_init(llama_sampler_chain_default_params());
	if (temperature <= 0.0f) {
		llama_sampler_chain_add(chain, llama_sampler_init_greedy());
	} else {
		llama_sampler_chain_add(chain, llama_sampler_init_top_k(40));
		llama_sampler_chain_add(chain, llama_sampler_init_top_p(0.9f, 1));
		llama_sampler_chain_add(chain, llama_sampler_init_temp(temperature));
		llama_sampler_chain_add(chain, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
	}

	const int32_t capacity = static_cast<int32_t>(llama_n_batch(ctx));
	llama_batch batch = llama_batch_init(capacity, 0, 1);
	llama_memory_clear(llama_get_memory(ctx), true);

	std::string output;
	bool failed = false;
	if (!decode(ctx, batch, capacity, tokens.data(), n_prompt, 0, session->cancel)) {
		failed = !session->cancel.load();
	} else {
		int32_t pos = n_prompt;
		for (int32_t i = 0; i < n_predict && !session->cancel.load(); ++i) {
			llama_token token = sample(ctx, vocab, chain, grammar);
			if (llama_vocab_is_eog(vocab, token)) {
				break;
			}
			char buf[256];
			const int32_t n = llama_token_to_piece(vocab, token, buf, sizeof(buf), 0, false);
			if (n > 0) {
				output.append(buf, static_cast<size_t>(n));
			}
			if (!decode(ctx, batch, capacity, &token, 1, pos, session->cancel)) {
				failed = !session->cancel.load();
				break;
			}
			++pos;
		}
	}

	llama_batch_free(batch);
	llama_sampler_free(chain);
	if (grammar != nullptr) {
		llama_sampler_free(grammar);
	}

	if (failed) {
		LOGE("llama_decode failed");
		throw_llama(env, "Inference failed");
		return nullptr;
	}
	if (session->cancel.load()) {
		return nullptr;
	}
	return to_bytes(env, output);
}

} // extern "C"
