#include <jni.h>
#include <string>
#include <android/log.h>
#include "realesrgan.h"

#define LOG_TAG "UltramixJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C"
JNIEXPORT jint JNICALL
Java_com_vung_upscaylultramix_MainActivity_upscaleNative(
        JNIEnv *env,
        jobject,
        jstring inputPath,
        jstring outputPath,
        jstring modelParamPath,
        jstring modelBinPath
) {
    const char *input = env->GetStringUTFChars(inputPath, nullptr);
    const char *output = env->GetStringUTFChars(outputPath, nullptr);
    const char *param = env->GetStringUTFChars(modelParamPath, nullptr);
    const char *bin = env->GetStringUTFChars(modelBinPath, nullptr);

    LOGI("Processing: %s -> %s", input, output);

    RealESRGAN* engine = new RealESRGAN();

    int load_ret = engine->load(param, bin);
    int result = -1;

    if (load_ret == 0) {
        result = engine->process(input, output);
    } else {
        LOGE("Failed to load model");
        result = -101;
    }

    delete engine;

    env->ReleaseStringUTFChars(inputPath, input);
    env->ReleaseStringUTFChars(outputPath, output);
    env->ReleaseStringUTFChars(modelParamPath, param);
    env->ReleaseStringUTFChars(modelBinPath, bin);

    return result;
}
