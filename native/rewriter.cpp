#include <jni.h>
#include "llama.h"
#include <algorithm>
#include <atomic>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>

static std::mutex mutex;
static std::atomic<bool> cancelled{false};
static bool should_abort(void *) { return cancelled.load(); }
extern "C" JNIEXPORT void JNICALL Java_dev_localwriter_LocalRewriter_cancel(JNIEnv *, jclass) { cancelled.store(true); }
static llama_model * model = nullptr;
static std::string model_path;
static void quiet_log(enum ggml_log_level, const char *, void *) {}
static std::string utf8(JNIEnv *env, jstring text) {
    jclass cls=env->FindClass("java/lang/String");
    jmethodID method=env->GetMethodID(cls,"getBytes","(Ljava/lang/String;)[B");
    jstring encoding=env->NewStringUTF("UTF-8");
    auto bytes=static_cast<jbyteArray>(env->CallObjectMethod(text,method,encoding));
    if(!bytes)throw std::runtime_error("Cannot encode input");
    std::string result(env->GetArrayLength(bytes),'\0');
    env->GetByteArrayRegion(bytes,0,result.size(),reinterpret_cast<jbyte*>(result.data()));
    env->DeleteLocalRef(bytes);env->DeleteLocalRef(encoding);env->DeleteLocalRef(cls);
    return result;
}
static jstring java_string(JNIEnv *env,const std::string& text) {
    jclass cls=env->FindClass("java/lang/String");
    jmethodID ctor=env->GetMethodID(cls,"<init>","([BLjava/lang/String;)V");
    jbyteArray bytes=env->NewByteArray(text.size());
    env->SetByteArrayRegion(bytes,0,text.size(),reinterpret_cast<const jbyte*>(text.data()));
    jstring encoding=env->NewStringUTF("UTF-8");
    auto result=static_cast<jstring>(env->NewObject(cls,ctor,bytes,encoding));
    env->DeleteLocalRef(bytes);env->DeleteLocalRef(encoding);env->DeleteLocalRef(cls);
    return result;
}
extern "C" JNIEXPORT jstring JNICALL Java_dev_localwriter_LocalRewriter_generate(JNIEnv *env,jclass,jstring path,jstring input,jint max_tokens) {
    std::lock_guard<std::mutex> lock(mutex);
    cancelled.store(false);
    try {
        const std::string file=utf8(env,path), prompt=utf8(env,input);
        llama_log_set(quiet_log,nullptr);
        if(!model||model_path!=file) {
            if(model)llama_model_free(model);
            llama_backend_init();
            auto params=llama_model_default_params();params.n_gpu_layers=0;
            model=llama_model_load_from_file(file.c_str(),params);model_path=file;
            if(!model)throw std::runtime_error("Cannot load bundled rewrite model");
        }
        auto vocab=llama_model_get_vocab(model);
        int count=-llama_tokenize(vocab,prompt.data(),prompt.size(),nullptr,0,true,true);
        const int budget=std::clamp(static_cast<int>(max_tokens),1,512);
        if(count<=0||count+budget>4096)throw std::runtime_error("Selected text exceeds local rewrite context");
        std::vector<llama_token> tokens(count);
        if(llama_tokenize(vocab,prompt.data(),prompt.size(),tokens.data(),count,true,true)<0)throw std::runtime_error("Cannot tokenize rewrite input");
        auto params=llama_context_default_params();params.n_ctx=count+budget;params.n_batch=128;params.n_ubatch=128;params.n_threads=2;params.n_threads_batch=2;params.no_perf=true;params.abort_callback=should_abort;
        std::unique_ptr<llama_context,decltype(&llama_free)> ctx(llama_init_from_model(model,params),llama_free);
        if(!ctx)throw std::runtime_error("Cannot allocate local rewrite context");
        std::unique_ptr<llama_batch_ext,decltype(&llama_batch_ext_free)> batch(llama_batch_ext_init(ctx.get()),llama_batch_ext_free);
        if(!batch)throw std::runtime_error("Cannot allocate rewrite batch");
        for(int start=0;start<count;start+=128) {
            llama_batch_ext_clear(batch.get());int n=std::min(128,count-start);
            for(int j=0;j<n;j++){int idx=llama_batch_ext_add_token(batch.get(),0,tokens[start+j]);llama_pos pos=start+j;llama_batch_ext_set_pos(batch.get(),idx,&pos);}
            if(start+n==count)llama_batch_ext_set_output_logits(batch.get(),n-1,true);
            if(llama_process(ctx.get(),LLAMA_PROCESS_TYPE_DECODE,batch.get()))throw std::runtime_error("Rewrite prompt evaluation failed");
        }
        std::unique_ptr<llama_sampler,decltype(&llama_sampler_free)> sampler(llama_sampler_chain_init(llama_sampler_chain_default_params()),llama_sampler_free);
        llama_sampler_chain_add(sampler.get(),llama_sampler_init_greedy());
        std::string output;
        for(int i=0;i<budget;i++) {
            if(cancelled.load())throw std::runtime_error("Generation cancelled");
            llama_token token=llama_sampler_sample(sampler.get(),ctx.get(),-1);
            if(llama_vocab_is_eog(vocab,token))break;
            llama_sampler_accept(sampler.get(),token);
            char piece[256];int n=llama_token_to_piece(vocab,token,piece,sizeof(piece),0,false);
            if(n<0){std::vector<char> large(-n);n=llama_token_to_piece(vocab,token,large.data(),large.size(),0,false);if(n<0)throw std::runtime_error("Cannot decode rewrite output");output.append(large.data(),n);}else output.append(piece,n);
            llama_batch_ext_clear(batch.get());int idx=llama_batch_ext_add_token(batch.get(),0,token);llama_pos pos=count+i;llama_batch_ext_set_pos(batch.get(),idx,&pos);llama_batch_ext_set_output_logits(batch.get(),idx,true);
            if(i+1<budget&&llama_process(ctx.get(),LLAMA_PROCESS_TYPE_DECODE,batch.get()))throw std::runtime_error("Rewrite generation failed");
        }
        if(output.empty())throw std::runtime_error("Local rewrite returned no text");
        return java_string(env,output);
    }catch(const std::exception& e){env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),e.what());return nullptr;}
}
