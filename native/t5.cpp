#include <jni.h>
#include "llama.h"
#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
std::mutex t5_mutex;
std::atomic<uint64_t> cancel_epoch{0};
llama_model * t5_model = nullptr;
std::string t5_path;
void quiet_log(enum ggml_log_level, const char *, void *) {}
struct Request {
    uint64_t epoch;
    bool cancelled() const { return cancel_epoch.load() != epoch; }
    void require_active() const { if (cancelled()) throw std::runtime_error("Context check cancelled"); }
};
bool abort_check(void * request) { return static_cast<Request *>(request)->cancelled(); }
bool load_progress(float, void * request) { return !abort_check(request); }

// JNI's modified UTF-8 loses supplementary characters. Use standard UTF-8 bytes.
std::string utf8(JNIEnv * env, jstring text) {
    if (!text) throw std::runtime_error("Missing context check input");
    jclass cls = env->FindClass("java/lang/String");
    jmethodID get_bytes = env->GetMethodID(cls, "getBytes", "(Ljava/lang/String;)[B");
    jstring encoding = env->NewStringUTF("UTF-8");
    auto bytes = static_cast<jbyteArray>(env->CallObjectMethod(text, get_bytes, encoding));
    if (!bytes || env->ExceptionCheck()) throw std::runtime_error("Cannot encode context check input");
    std::string result(env->GetArrayLength(bytes), '\0');
    env->GetByteArrayRegion(bytes, 0, result.size(), reinterpret_cast<jbyte *>(result.data()));
    env->DeleteLocalRef(bytes); env->DeleteLocalRef(encoding); env->DeleteLocalRef(cls);
    return result;
}
jstring java_string(JNIEnv * env, const std::string & text) {
    jclass cls = env->FindClass("java/lang/String");
    jmethodID ctor = env->GetMethodID(cls, "<init>", "([BLjava/lang/String;)V");
    jbyteArray bytes = env->NewByteArray(text.size());
    if (!bytes) throw std::runtime_error("Cannot allocate context check output");
    env->SetByteArrayRegion(bytes, 0, text.size(), reinterpret_cast<const jbyte *>(text.data()));
    jstring encoding = env->NewStringUTF("UTF-8");
    auto result = static_cast<jstring>(env->NewObject(cls, ctor, bytes, encoding));
    env->DeleteLocalRef(bytes); env->DeleteLocalRef(encoding); env->DeleteLocalRef(cls);
    return result;
}
void clear_model() {
    if (t5_model) llama_model_free(t5_model);
    t5_model = nullptr; t5_path.clear();
}
void set_batch(llama_batch_ext * batch, const llama_token * tokens, int count, int start, bool logits) {
    llama_batch_ext_clear(batch);
    for (int i = 0; i < count; ++i) {
        int idx = llama_batch_ext_add_token(batch, 0, tokens[i]);
        llama_pos position = start + i;
        llama_batch_ext_set_pos(batch, idx, &position);
        if (logits && i == count - 1) llama_batch_ext_set_output_logits(batch, idx, true);
    }
}
}

extern "C" JNIEXPORT void JNICALL Java_dev_localwriter_ContextGrammarEngine_cancel(JNIEnv *, jclass) {
    cancel_epoch.fetch_add(1);
}
extern "C" JNIEXPORT jlong JNICALL Java_dev_localwriter_ContextGrammarEngine_epoch(JNIEnv *, jclass) {
    return cancel_epoch.load();
}
extern "C" JNIEXPORT void JNICALL Java_dev_localwriter_ContextGrammarEngine_release(JNIEnv *, jclass) {
    cancel_epoch.fetch_add(1);
    std::lock_guard<std::mutex> guard(t5_mutex);
    clear_model();
}
extern "C" JNIEXPORT jstring JNICALL Java_dev_localwriter_ContextGrammarEngine_generate(
        JNIEnv * env, jclass, jstring path, jstring text, jlong epoch) {
    const Request request{static_cast<uint64_t>(epoch)};
    std::lock_guard<std::mutex> guard(t5_mutex);
    try {
        request.require_active();
        llama_log_set(quiet_log, nullptr);
        const std::string file = utf8(env, path);
        const std::string input = "gec: " + utf8(env, text);
        // A conservative byte limit bounds allocation before tokenization. The
        // actual model limit is checked in token space, without truncation.
        if (input.size() > 16384) throw std::runtime_error("Sentence exceeds context check limit");
        if (!t5_model || t5_path != file) {
            clear_model();
            llama_backend_init();
            auto options = llama_model_default_params();
            options.n_gpu_layers = 0; options.load_mode = LLAMA_LOAD_MODE_MMAP;
            options.progress_callback = load_progress;
            options.progress_callback_user_data = const_cast<Request *>(&request);
            t5_model = llama_model_load_from_file(file.c_str(), options);
            request.require_active();
            if (!t5_model) throw std::runtime_error("Cannot load bundled context model");
            if (!llama_model_has_encoder(t5_model) || !llama_model_has_decoder(t5_model)) {
                clear_model(); throw std::runtime_error("Context model is not encoder-decoder");
            }
            t5_path = file;
        }
        const auto vocab = llama_model_get_vocab(t5_model);
        int count = -llama_tokenize(vocab, input.data(), input.size(), nullptr, 0, true, false);
        if (count <= 0 || count > 512) throw std::runtime_error("Sentence exceeds context model tokens");
        std::vector<llama_token> tokens(count);
        int written = llama_tokenize(vocab, input.data(), input.size(), tokens.data(), count, true, false);
        if (written != count) throw std::runtime_error("Cannot tokenize context check input");
        const llama_token eos = llama_vocab_eos(vocab);
        if (eos == LLAMA_TOKEN_NULL) throw std::runtime_error("Context model has no end token");
        if (tokens.back() != eos) tokens.push_back(eos);
        if (tokens.size() > 512) throw std::runtime_error("Sentence exceeds context model tokens");
        const llama_token start = llama_model_decoder_start_token(t5_model);
        if (start == LLAMA_TOKEN_NULL || start != llama_vocab_pad(vocab))
            throw std::runtime_error("Context model has incorrect decoder start token");
        auto options = llama_context_default_params();
        options.n_ctx = 512; options.n_batch = 512; options.n_ubatch = 512;
        options.n_threads = 2; options.n_threads_batch = 2; options.no_perf = true;
        options.abort_callback = abort_check;
        options.abort_callback_data = const_cast<Request *>(&request);
        std::unique_ptr<llama_context, decltype(&llama_free)> ctx(llama_init_from_model(t5_model, options), llama_free);
        if (!ctx) throw std::runtime_error("Cannot allocate context check state");
        std::unique_ptr<llama_batch_ext, decltype(&llama_batch_ext_free)> batch(llama_batch_ext_init(ctx.get()), llama_batch_ext_free);
        if (!batch) throw std::runtime_error("Cannot allocate context check batch");
        set_batch(batch.get(), tokens.data(), tokens.size(), 0, false);
        request.require_active();
        if (llama_process(ctx.get(), LLAMA_PROCESS_TYPE_ENCODE, batch.get()))
            throw std::runtime_error("Context encoder failed");
        set_batch(batch.get(), &start, 1, 0, true);
        std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> sampler(
            llama_sampler_chain_init(llama_sampler_chain_default_params()), llama_sampler_free);
        if (!sampler) throw std::runtime_error("Cannot allocate context check sampler");
        llama_sampler_chain_add(sampler.get(), llama_sampler_init_greedy());
        std::string output;
        bool ended = false;
        for (int position = 0; position < 512; ++position) {
            request.require_active();
            if (llama_process(ctx.get(), LLAMA_PROCESS_TYPE_DECODE, batch.get()))
                throw std::runtime_error("Context decoder failed");
            request.require_active();
            const llama_token token = llama_sampler_sample(sampler.get(), ctx.get(), -1);
            if (llama_vocab_is_eog(vocab, token)) { ended = true; break; }
            llama_sampler_accept(sampler.get(), token);
            char buffer[256];
            int length = llama_token_to_piece(vocab, token, buffer, sizeof(buffer), 0, false);
            if (length < 0) {
                std::vector<char> large(-length);
                length = llama_token_to_piece(vocab, token, large.data(), large.size(), 0, false);
                if (length < 0) throw std::runtime_error("Cannot decode context check output");
                output.append(large.data(), length);
            } else output.append(buffer, length);
            if (output.size() > 16384) throw std::runtime_error("Context output exceeds limit");
            set_batch(batch.get(), &token, 1, position + 1, true);
        }
        request.require_active();
        // A clipped generation must never suggest deleting the sentence tail.
        if (!ended || output.empty()) throw std::runtime_error("Context check returned incomplete output");
        return java_string(env, output);
    } catch (const std::exception & error) {
        if (request.cancelled()) clear_model();
        if (!env->ExceptionCheck()) {
            const char * kind = request.cancelled() ? "java/util/concurrent/CancellationException" : "java/lang/IllegalStateException";
            env->ThrowNew(env->FindClass(kind), error.what());
        }
        return nullptr;
    }
}
