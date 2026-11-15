package com.jbsoft.jobq.domain.ai

/**
 * The shape of a JSON value the model must produce. [toGbnf] turns it into a llama.cpp
 * grammar, so sampling can only yield compact JSON of exactly this shape: keys in the
 * declared order, no extra keys, no prose around it. That makes small models reliable at
 * structured output.
 */
sealed interface JsonShape {
	data object Text : JsonShape
	data object Integer : JsonShape
	/** An integer from 0 to 100. */
	data object Percent : JsonShape
	data object Bool : JsonShape
	data class OneOf(val values: List<String>) : JsonShape
	data class Nullable(val inner: JsonShape) : JsonShape
	data class ListOf(val item: JsonShape, val maxItems: Int) : JsonShape
	data class Obj(val fields: List<Pair<String, JsonShape>>) : JsonShape {
		constructor(vararg fields: Pair<String, JsonShape>) : this(fields.toList())
	}
}

fun JsonShape.toGbnf(): String {
	val rules = LinkedHashMap<String, String>()
	var counter = 0

	fun literal(text: String) = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

	fun ref(shape: JsonShape): String = when (shape) {
		JsonShape.Text -> "string"
		JsonShape.Integer -> "integer"
		JsonShape.Percent -> "percent"
		JsonShape.Bool -> "boolean"
		is JsonShape.OneOf -> "(" + shape.values.joinToString(" | ") { literal("\"$it\"") } + ")"
		is JsonShape.Nullable -> "(" + ref(shape.inner) + " | \"null\")"
		is JsonShape.ListOf -> {
			require(shape.maxItems >= 1) { "maxItems must be at least 1" }
			val item = ref(shape.item)
			val name = "list${counter++}"
			val more = if (shape.maxItems > 1) " (\",\" ws $item){0,${shape.maxItems - 1}}" else ""
			rules[name] = "\"[\" ($item$more)? \"]\""
			name
		}
		is JsonShape.Obj -> {
			val name = "obj${counter++}"
			val body = shape.fields.joinToString(" \",\" ws ") { (key, value) ->
				literal("\"$key\":") + " ws " + ref(value)
			}
			rules[name] = "\"{\" $body \"}\""
			name
		}
	}

	val root = ref(this)
	return buildString {
		appendLine("root ::= $root")
		rules.forEach { (name, body) -> appendLine("$name ::= $body") }
		appendLine("""string ::= "\"" ([^"\\\x7F\x00-\x1F] | "\\" (["\\bfnrt/] | "u" [0-9a-fA-F]{4}))* "\""""")
		appendLine("""integer ::= "-"? ("0" | [1-9] [0-9]{0,8})""")
		appendLine("""percent ::= "100" | [1-9] [0-9] | [0-9]""")
		appendLine("""boolean ::= "true" | "false"""")
		// Compact JSON only: at most one space, and only after ":" and ",". Models like to
		// pretty-print, and if a grammar allows a newline but not the indentation they want
		// after "[", the likeliest token left is "]" — every list silently comes back empty.
		appendLine("""ws ::= " "?""")
	}
}
