cmake_minimum_required(VERSION 3.22.1)

project("ultramix_jni")

# ĐƯỜNG DẪN TỚI NCNN SDK (Bạn cần tải ncnn-android-vulkan và giải nén vào đây)
set(ncnn_DIR "${CMAKE_CURRENT_SOURCE_DIR}/ncnn-sdk/${ANDROID_ABI}/lib/cmake/ncnn")
find_package(ncnn REQUIRED)

add_library(
    ultramix_jni
    SHARED
    upscaler_jni.cpp
    realesrgan.cpp
)

target_include_directories(
    ultramix_jni
    PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/ncnn-sdk/${ANDROID_ABI}/include"
)

find_library(log-lib log)
find_library(android-lib android)
find_library(jnigraphics-lib jnigraphics)

target_link_libraries(
    ultramix_jni
    ncnn
    ${log-lib}
    ${android-lib}
    ${jnigraphics-lib}
)
