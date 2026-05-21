# Upscayl Ultramix Android

Ứng dụng Android tối giản để upscale ảnh bằng model `ultramix-balanced-4x`.

## Mục tiêu

Repo này chỉ dành cho app Android Upscayl Ultramix. Các file của project khác như PDF Merger/PySide6/PyInstaller đã được loại bỏ để tránh trộn repo.

## Chức năng hiện có

- Giao diện Android Kotlin.
- Chọn ảnh từ máy.
- Chọn thư mục lưu kết quả bằng Android Storage Access Framework.
- Copy model từ `assets/models` vào bộ nhớ app.
- JNI bridge để gọi native engine.
- CMake build native library.
- Native RealESRGAN/NCNN pipeline có tile processing, progress callback, cancel và fallback CPU/GPU.

## Model cần có

Copy 2 file model sau vào thư mục:

```text
app/src/main/assets/models/
```

Tên file cần đúng:

```text
ultramix-balanced-4x.param
ultramix-balanced-4x.bin
```

Có thể lấy từ repo Upscayl desktop của bạn, ví dụ:

```text
D:\GitHub\upscayl\resources\models\ultramix-balanced-4x.param
D:\GitHub\upscayl\resources\models\ultramix-balanced-4x.bin
```

## Native dependencies

Project dùng NCNN Android Vulkan. CMake đang trỏ tới:

```text
app/src/main/cpp/ncnn-sdk/${ANDROID_ABI}/lib/cmake/ncnn
```

Cần giải nén NCNN Android SDK tương ứng vào:

```text
app/src/main/cpp/ncnn-sdk/arm64-v8a/
```

## Mở bằng Android Studio

1. Mở Android Studio.
2. Chọn **File > Open**.
3. Chọn thư mục `UpscaylUltramixAndroid`.
4. Sync Gradle.
5. Kiểm tra `local.properties` đã trỏ đúng Android SDK.
6. Build APK.

## Cấu trúc chính

```text
app/src/main/java/com/vung/upscaylultramix/MainActivity.kt
app/src/main/cpp/CMakeLists.txt
app/src/main/cpp/upscaler_jni.cpp
app/src/main/cpp/realesrgan.cpp
app/src/main/cpp/realesrgan.h
app/src/main/res/layout/activity_main.xml
```

## Ghi chú build

- Project đang dùng `compileSdk 35`, `minSdk 26`, `targetSdk 35`.
- Native ABI hiện giới hạn ở `arm64-v8a`.
- NDK đang cấu hình `28.2.13676358`.
- Java/Kotlin target đang là JVM 21.

## Việc cần làm tiếp theo

- Thêm chế độ `Text Enhance` để làm nét chữ/tài liệu.
- Thêm lựa chọn mode trong UI: Standard Upscale, Text Enhance, Text Upscale.
- Tối ưu giới hạn kích thước ảnh theo RAM thực tế của thiết bị.
