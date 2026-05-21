#include <jni.h>
#include <string>
#include <atomic>
#include <android/log.h>
#include "realesrgan.h"

#define LOG_TAG "UltramixJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static std::atomic_bool g_cancel_requested(false);

static bool report_progress(
        JNIEnv* env,
        jobject callbackTarget,
        jmethodID progressMethod,
        int done,
        int total,
        const char* phase,
        long long elapsedMs,
        bool usingVulkan
) {
    if (g_cancel_requested.load()) return false;
    if (!callbackTarget || !progressMethod) return true;

    jstring phaseString = env->NewStringUTF(phase);
    env->CallVoidMethod(
        callbackTarget,
        progressMethod,
        (jint)done,
        (jint)total,
        phaseString,
        (jlong)elapsedMs,
        (jboolean)usingVulkan
    );
    env->DeleteLocalRef(phaseString);

    if (env->ExceptionCheck()) {
        LOGE("Progress callback threw an exception");
        env->ExceptionClear();
        return false;
    }
    return !g_cancel_requested.load();
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_vung_upscaylultramix_MainActivity_upscaleNative(
        JNIEnv *env,
        jobject thiz,
        jstring inputPath,
        jstring outputPath,
        jstring modelParamPath,
        jstring modelBinPath,
        jobject callbackTarget
) {
    g_cancel_requested.store(false);

    jclass callbackClass = env->GetObjectClass(callbackTarget ? callbackTarget : thiz);
    jmethodID progressMethod = env->GetMethodID(
        callbackClass,
        "onNativeProgress",
        "(IILjava/lang/String;JZ)V"
    );
    if (!progressMethod) {
        LOGE("Failed to find onNativeProgress callback");
        env->ExceptionClear();
    }

    const char *input = env->GetStringUTFChars(inputPath, nullptr);
    const char *output = env->GetStringUTFChars(outputPath, nullptr);
    const char *param = env->GetStringUTFChars(modelParamPath, nullptr);
    const char *bin = env->GetStringUTFChars(modelBinPath, nullptr);

    LOGI("Processing: %s -> %s", input, output);

    RealESRGAN* engine = new RealESRGAN();
    report_progress(env, callbackTarget ? callbackTarget : thiz, progressMethod, 0, 0,
                    engine->using_vulkan() ? "Đang tải mô hình AI (Vulkan)" : "Đang tải mô hình AI (CPU)",
                    0, engine->using_vulkan());

    int load_ret = engine->load(param, bin);
    int result = -1;

    if (load_ret == 0) {
        result = engine->process(
            input,
            output,
            [env, callbackTarget, thiz, progressMethod](int done, int total, const char* phase, long long elapsedMs, bool usingVulkan) {
                return report_progress(env, callbackTarget ? callbackTarget : thiz, progressMethod, done, total, phase, elapsedMs, usingVulkan);
            },
            g_cancel_requested
        );
    } else {
        LOGE("Failed to load model");
        result = -101;
    }

    delete engine;

    env->ReleaseStringUTFChars(inputPath, input);
    env->ReleaseStringUTFChars(outputPath, output);
    env->ReleaseStringUTFChars(modelParamPath, param);
    env->ReleaseStringUTFChars(modelBinPath, bin);

    LOGI("upscaleNative result code: %d", result);
    return result;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_vung_upscaylultramix_MainActivity_cancelNative(
        JNIEnv *,
        jobject
) {
    g_cancel_requested.store(true);
    LOGI("Cancel requested");
}
