# Upscayl Ultramix Android

App Android tối giản để upscale ảnh bằng preset `ultramix-balanced-4x`.

## Trạng thái hiện tại

Bộ project này đã có:

- Giao diện Android Kotlin
- Chọn ảnh từ máy
- Copy model từ assets
- JNI bridge
- CMake
- Chỗ gọi native engine

Phần còn thiếu là nhúng engine thật:

- `realesrgan-ncnn-vulkan`
- `ncnn`
- Vulkan runtime
- code đọc/ghi ảnh native

Hiện tại hàm JNI trả mã `100`, nghĩa là project build được nhưng chưa upscale thật.

## Copy model

Copy 2 file này từ repo Upscayl desktop của bạn:

```text
D:\GitHub\upscayl\resources\models\ultramix-balanced-4x.param
D:\GitHub\upscayl\resources\models\ultramix-balanced-4x.bin
```

vào:

```text
app/src/main/assets/models/
```

## Mở bằng Android Studio

1. File > Open
2. Chọn thư mục `UpscaylUltramixAndroid`
3. Sync Gradle
4. Build APK

## Bước tiếp theo để upscale thật

Cần thêm source `realesrgan-ncnn-vulkan` bản Android vào `app/src/main/cpp`, rồi thay nội dung `upscaler_jni.cpp` bằng lời gọi engine thật.