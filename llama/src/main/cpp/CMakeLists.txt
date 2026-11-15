cmake_minimum_required(VERSION 3.22.1)

project(jobq_llama LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# llama.cpp is fetched at configure time, pinned to a release tag and verified by hash.
# To upgrade: change the tag, then update the hash (shasum -a 256 <tarball>) and re-check
# llama_jni.cpp against include/llama.h, since the C API is not stable between tags.
set(LLAMA_CPP_TAG "b12415")
set(LLAMA_CPP_SHA256 "ece68c5ee1fb15780c8bdf19a21631568fc01e61d81db7208dc177e8dcf4f02b")

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_CCACHE OFF CACHE BOOL "" FORCE)

if(ANDROID_ABI STREQUAL "arm64-v8a")
	# Dot-product and fp16 instructions roughly double quantised inference speed. Every
	# phone with enough RAM to hold a model has them (Cortex-A75/A55 and newer, 2018+).
	set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
endif()

include(FetchContent)
FetchContent_Declare(
	llama_cpp
	URL "https://github.com/ggml-org/llama.cpp/archive/refs/tags/${LLAMA_CPP_TAG}.tar.gz"
	URL_HASH "SHA256=${LLAMA_CPP_SHA256}"
)
FetchContent_MakeAvailable(llama_cpp)

add_library(jobq_llama SHARED llama_jni.cpp)
target_link_libraries(jobq_llama PRIVATE llama android log)
