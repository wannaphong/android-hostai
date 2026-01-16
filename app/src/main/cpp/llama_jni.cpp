#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include "llama.h"

#define LOG_TAG "LlamaJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

struct LlamaContext {
    llama_model *model;
    llama_context *ctx;
    llama_sampler *sampler;
    std::string model_path;
    bool is_loaded;
    
    LlamaContext() : model(nullptr), ctx(nullptr), sampler(nullptr), is_loaded(false) {}
};

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_wannaphong_hostai_LlamaModel_nativeInit(JNIEnv *env, jobject thiz) {
    LOGI("Initializing LlamaContext");
    
    // Initialize llama backend once
    llama_backend_init();
    
    LlamaContext *ctx = new LlamaContext();
    ctx->is_loaded = false;
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT jboolean JNICALL
Java_com_wannaphong_hostai_LlamaModel_nativeLoadModel(
        JNIEnv *env, jobject thiz, jlong context_ptr, jstring model_path) {
    
    LlamaContext *llamaCtx = reinterpret_cast<LlamaContext *>(context_ptr);
    if (!llamaCtx) {
        LOGE("Invalid context pointer");
        return JNI_FALSE;
    }
    
    const char *path_str = env->GetStringUTFChars(model_path, nullptr);
    llamaCtx->model_path = std::string(path_str);
    env->ReleaseStringUTFChars(model_path, path_str);
    
    LOGI("Loading model from: %s", llamaCtx->model_path.c_str());
    
    // Load model
    llama_model_params model_params = llama_model_default_params();
    llamaCtx->model = llama_load_model_from_file(llamaCtx->model_path.c_str(), model_params);
    
    if (!llamaCtx->model) {
        LOGE("Failed to load model");
        return JNI_FALSE;
    }
    
    // Create context
    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = 2048;
    ctx_params.n_batch = 512;
    ctx_params.n_threads = 4;
    
    llamaCtx->ctx = llama_new_context_with_model(llamaCtx->model, ctx_params);
    
    if (!llamaCtx->ctx) {
        LOGE("Failed to create context");
        llama_model_free(llamaCtx->model);
        llamaCtx->model = nullptr;
        return JNI_FALSE;
    }
    
    // Create sampler
    llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    llamaCtx->sampler = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(llamaCtx->sampler, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(llamaCtx->sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    
    llamaCtx->is_loaded = true;
    LOGI("Model loaded successfully");
    return JNI_TRUE;
}

JNIEXPORT jstring JNICALL
Java_com_wannaphong_hostai_LlamaModel_nativeGenerate(
        JNIEnv *env, jobject thiz, jlong context_ptr, 
        jstring prompt, jint max_tokens, jfloat temperature) {
    
    LlamaContext *llamaCtx = reinterpret_cast<LlamaContext *>(context_ptr);
    if (!llamaCtx || !llamaCtx->is_loaded) {
        LOGE("Context not initialized or model not loaded");
        return env->NewStringUTF("Error: Model not loaded");
    }
    
    const char *prompt_str = env->GetStringUTFChars(prompt, nullptr);
    std::string prompt_string(prompt_str);
    env->ReleaseStringUTFChars(prompt, prompt_str);
    
    LOGI("Generating response for prompt: %s", prompt_string.c_str());
    LOGI("Max tokens: %d, Temperature: %.2f", max_tokens, temperature);
    
    // Update sampler temperature if needed (recreating is simple and safe for this use case)
    llama_sampler_free(llamaCtx->sampler);
    llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    llamaCtx->sampler = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(llamaCtx->sampler, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(llamaCtx->sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    
    // Get vocab from model
    const llama_vocab *vocab = llama_model_get_vocab(llamaCtx->model);
    
    // Tokenize the prompt - allocate buffer with reasonable size
    std::vector<llama_token> tokens;
    // Pre-allocate based on typical token-to-char ratio (1 token per 3-4 chars is common)
    tokens.resize(prompt_string.length() + 256);
    
    int n_tokens_prompt = llama_tokenize(
        vocab,
        prompt_string.c_str(),
        prompt_string.length(),
        tokens.data(),
        tokens.size(),
        true,  // add_special
        false  // parse_special
    );
    
    if (n_tokens_prompt < 0) {
        LOGE("Failed to tokenize prompt: buffer too small");
        // Retry with larger buffer
        tokens.resize(-n_tokens_prompt);
        n_tokens_prompt = llama_tokenize(
            vocab,
            prompt_string.c_str(),
            prompt_string.length(),
            tokens.data(),
            tokens.size(),
            true,
            false
        );
        if (n_tokens_prompt < 0) {
            LOGE("Failed to tokenize prompt after resize");
            return env->NewStringUTF("Error: Failed to tokenize prompt");
        }
    }
    
    tokens.resize(n_tokens_prompt);
    LOGI("Tokenized prompt into %d tokens", n_tokens_prompt);
    
    // Clear KV cache
    llama_kv_cache_clear(llamaCtx->ctx);
    
    // Evaluate the prompt
    llama_batch batch = llama_batch_get_one(tokens.data(), n_tokens_prompt);
    if (llama_decode(llamaCtx->ctx, batch) != 0) {
        LOGE("Failed to evaluate prompt");
        return env->NewStringUTF("Error: Failed to evaluate prompt");
    }
    
    // Generate tokens
    std::string result;
    int n_gen = 0;
    for (int i = 0; i < max_tokens; i++) {
        llama_token new_token = llama_sampler_sample(llamaCtx->sampler, llamaCtx->ctx, -1);
        
        // Check for end of generation
        if (llama_vocab_is_eog(vocab, new_token)) {
            LOGI("End of generation at token %d", i);
            break;
        }
        
        // Decode token to text with larger buffer for safety
        std::vector<char> buf(512);
        int n = llama_token_to_piece(vocab, new_token, buf.data(), buf.size(), 0, false);
        if (n > 0 && n < static_cast<int>(buf.size())) {
            result.append(buf.data(), n);
        } else if (n >= static_cast<int>(buf.size())) {
            // Buffer was too small, retry with exact size
            buf.resize(n + 1);
            n = llama_token_to_piece(vocab, new_token, buf.data(), buf.size(), 0, false);
            if (n > 0) {
                result.append(buf.data(), n);
            }
        }
        
        // Feed the new token back for next prediction
        batch = llama_batch_get_one(&new_token, 1);
        if (llama_decode(llamaCtx->ctx, batch) != 0) {
            LOGE("Failed to decode token at position %d", i);
            break;
        }
        
        n_gen++;
    }
    
    LOGI("Generated %d tokens", n_gen);
    
    return env->NewStringUTF(result.c_str());
}

JNIEXPORT jboolean JNICALL
Java_com_wannaphong_hostai_LlamaModel_nativeIsLoaded(JNIEnv *env, jobject thiz, jlong context_ptr) {
    LlamaContext *ctx = reinterpret_cast<LlamaContext *>(context_ptr);
    if (!ctx) {
        return JNI_FALSE;
    }
    return ctx->is_loaded ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_wannaphong_hostai_LlamaModel_nativeUnload(JNIEnv *env, jobject thiz, jlong context_ptr) {
    LlamaContext *llamaCtx = reinterpret_cast<LlamaContext *>(context_ptr);
    if (!llamaCtx) {
        return;
    }
    
    LOGI("Unloading model");
    
    if (llamaCtx->sampler) {
        llama_sampler_free(llamaCtx->sampler);
        llamaCtx->sampler = nullptr;
    }
    
    if (llamaCtx->ctx) {
        llama_free(llamaCtx->ctx);
        llamaCtx->ctx = nullptr;
    }
    
    if (llamaCtx->model) {
        llama_model_free(llamaCtx->model);
        llamaCtx->model = nullptr;
    }
    
    llamaCtx->is_loaded = false;
}

JNIEXPORT void JNICALL
Java_com_wannaphong_hostai_LlamaModel_nativeFree(JNIEnv *env, jobject thiz, jlong context_ptr) {
    LlamaContext *llamaCtx = reinterpret_cast<LlamaContext *>(context_ptr);
    if (!llamaCtx) {
        return;
    }
    
    LOGI("Freeing LlamaContext");
    
    // Make sure everything is cleaned up
    if (llamaCtx->sampler) {
        llama_sampler_free(llamaCtx->sampler);
    }
    if (llamaCtx->ctx) {
        llama_free(llamaCtx->ctx);
    }
    if (llamaCtx->model) {
        llama_model_free(llamaCtx->model);
    }
    
    delete llamaCtx;
}

} // extern "C"
