// JNI bridge between Kotlin (com.example.voiceagent.engine.CactusNative) and the
// Cactus 4 C ABI declared in cactus.h. One process-global engine instance.

#include <jni.h>

#include <android/log.h>

#include <cstdint>
#include <cstring>
#include <fstream>
#include <string>
#include <vector>

#include "cactus.h"

namespace {

constexpr const char* kLogTag = "CactusJNI";
constexpr int kCompleteBufferSize = 1 << 20;   // 1 MiB of JSON
constexpr int kTranscribeBufferSize = 1 << 19; // 512 KiB of JSON

std::string LastError() {
    const char* message = cactus_last_error();
    return message == nullptr ? std::string() : std::string(message);
}

void ThrowRuntime(JNIEnv* env, const std::string& message) {
    jclass exception_class = env->FindClass("java/lang/RuntimeException");
    if (exception_class != nullptr) {
        env->ThrowNew(exception_class, message.c_str());
    }
}

jbyteArray ToByteArray(JNIEnv* env, const char* data, size_t length) {
    if (length > static_cast<size_t>(INT32_MAX)) {
        ThrowRuntime(env, "Cactus response is too large");
        return nullptr;
    }
    jbyteArray result = env->NewByteArray(static_cast<jsize>(length));
    if (result == nullptr) {
        return nullptr;
    }
    env->SetByteArrayRegion(
        result, 0, static_cast<jsize>(length), reinterpret_cast<const jbyte*>(data));
    return result;
}

jbyteArray NullTerminatedResult(JNIEnv* env, const std::vector<char>& buffer) {
    const size_t length = strnlen(buffer.data(), buffer.size());
    return ToByteArray(env, buffer.data(), length);
}

// The engine writes error details into the output buffer for some calls and
// only exposes a generic message through cactus_last_error() for others.
std::string ErrorDetail(const std::vector<char>& buffer, const char* fallback) {
    if (!buffer.empty() && buffer[0] != '\0') {
        return std::string(buffer.data());
    }
    const std::string detail = LastError();
    return detail.empty() ? std::string(fallback) : detail;
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_example_voiceagent_engine_CactusNative_nativeLoad(
    JNIEnv* env, jobject /*thiz*/, jstring path) {
    if (path == nullptr) {
        ThrowRuntime(env, "Missing model path");
        return -1;
    }
    const char* utf8_path = env->GetStringUTFChars(path, nullptr);
    if (utf8_path == nullptr) {
        return -1;
    }

    std::ifstream input(utf8_path, std::ios::binary | std::ios::ate);
    env->ReleaseStringUTFChars(path, utf8_path);
    if (!input) {
        ThrowRuntime(env, "Could not open model file");
        return -1;
    }

    const std::streamsize size = input.tellg();
    if (size <= 0) {
        ThrowRuntime(env, "Model file is empty");
        return -1;
    }
    input.seekg(0, std::ios::beg);

    std::vector<unsigned char> data(static_cast<size_t>(size));
    if (!input.read(reinterpret_cast<char*>(data.data()), size)) {
        ThrowRuntime(env, "Could not read model file");
        return -1;
    }

    const int result =
        cactus_load(data.data(), static_cast<unsigned long long>(data.size()));
    if (result < 0) {
        __android_log_print(
            ANDROID_LOG_ERROR, kLogTag, "cactus_load failed: %s", LastError().c_str());
    }
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_voiceagent_engine_CactusNative_nativeModels(
    JNIEnv* /*env*/, jobject /*thiz*/) {
    return cactus_models();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_voiceagent_engine_CactusNative_nativeInit(
    JNIEnv* env, jobject /*thiz*/, jstring system_prompt, jstring tools_json) {
    const char* system_chars =
        system_prompt == nullptr ? nullptr : env->GetStringUTFChars(system_prompt, nullptr);
    const char* tools_chars =
        tools_json == nullptr ? nullptr : env->GetStringUTFChars(tools_json, nullptr);

    const int result = cactus_init(system_chars, tools_chars, nullptr);

    if (system_prompt != nullptr && system_chars != nullptr) {
        env->ReleaseStringUTFChars(system_prompt, system_chars);
    }
    if (tools_json != nullptr && tools_chars != nullptr) {
        env->ReleaseStringUTFChars(tools_json, tools_chars);
    }
    return result;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_example_voiceagent_engine_CactusNative_nativeComplete(
    JNIEnv* env, jobject /*thiz*/, jbyteArray input, jint max_new_tokens) {
    if (input == nullptr) {
        ThrowRuntime(env, "Missing input text");
        return nullptr;
    }

    const jsize input_length = env->GetArrayLength(input);
    std::string text(static_cast<size_t>(input_length), '\0');
    if (input_length > 0) {
        env->GetByteArrayRegion(
            input, 0, input_length, reinterpret_cast<jbyte*>(text.data()));
    }

    std::vector<char> output(kCompleteBufferSize, '\0');
    const int result = cactus_complete(
        text.c_str(), nullptr, 0, max_new_tokens, output.data(),
        static_cast<int>(output.size()));
    if (result < 0) {
        ThrowRuntime(env, ErrorDetail(output, "cactus_complete failed"));
        return nullptr;
    }
    return NullTerminatedResult(env, output);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_example_voiceagent_engine_CactusNative_nativeTranscribe(
    JNIEnv* env, jobject /*thiz*/, jfloatArray pcm, jstring language) {
    if (pcm == nullptr) {
        ThrowRuntime(env, "Missing audio");
        return nullptr;
    }

    const jsize sample_count = env->GetArrayLength(pcm);
    std::vector<float> samples(static_cast<size_t>(sample_count));
    if (sample_count > 0) {
        env->GetFloatArrayRegion(pcm, 0, sample_count, samples.data());
    }

    const char* language_chars =
        language == nullptr ? nullptr : env->GetStringUTFChars(language, nullptr);
    std::vector<char> output(kTranscribeBufferSize, '\0');
    const int result = cactus_transcribe(
        samples.data(), sample_count, language_chars, nullptr, 0, output.data(),
        static_cast<int>(output.size()));
    if (language != nullptr && language_chars != nullptr) {
        env->ReleaseStringUTFChars(language, language_chars);
    }

    if (result < 0) {
        ThrowRuntime(env, ErrorDetail(output, "cactus_transcribe failed"));
        return nullptr;
    }
    return NullTerminatedResult(env, output);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_voiceagent_engine_CactusNative_nativeReset(
    JNIEnv* /*env*/, jobject /*thiz*/) {
    cactus_reset();
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_voiceagent_engine_CactusNative_nativeLastError(
    JNIEnv* env, jobject /*thiz*/) {
    const std::string detail = LastError();
    return env->NewStringUTF(detail.c_str());
}
