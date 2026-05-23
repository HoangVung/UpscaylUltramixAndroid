# Qualcomm XLSR Upscaler

[Tiếng Việt](#tiếng-việt) | [English](#english)

---

## Tiếng Việt

**Qualcomm XLSR Upscaler** là ứng dụng Android tối giản để upscale ảnh bằng mô hình Qualcomm XLSR chạy qua TensorFlow Lite. App hiện ưu tiên chạy bằng NNAPI/NPU, có fallback sang GPU hoặc CPU nếu thiết bị không hỗ trợ runtime mong muốn.

Repo hiện tại: `HoangVung/QualcommXlsrUpscaler`.

### Chức năng hiện có

- Giao diện Android Kotlin.
- Chọn ảnh từ máy bằng Android Photo/File Picker.
- Chọn thư mục lưu kết quả bằng Android Storage Access Framework.
- Ghi nhớ thư mục lưu đã chọn.
- Chọn tỷ lệ upscale: 2x, 3x, 4x.
- Chọn runtime xử lý: NNAPI/NPU, GPU hoặc CPU.
- Xử lý ảnh theo tile để giảm áp lực bộ nhớ.
- Hiển thị tiến trình xử lý tile và thời gian đã chạy.
- Hỗ trợ hủy tác vụ đang xử lý.
- Xuất ảnh PNG vào thư mục đã chọn hoặc thư mục app mặc định.

### Pipeline hiện tại

Code hiện tại dùng TensorFlow Lite:

```text
app/src/main/java/com/vung/upscaylultramix/XlsrUpscaleEngine.kt
app/src/main/java/com/vung/upscaylultramix/TfliteModelRunner.kt
app/src/main/java/com/vung/upscaylultramix/RuntimeDelegateFactory.kt
app/src/main/java/com/vung/upscaylultramix/TileProcessor.kt
```

Pipeline chính:

1. Người dùng chọn ảnh.
2. App kiểm tra giới hạn kích thước ảnh đầu vào.
3. Copy model TFLite từ `assets/models` vào cache.
4. Tạo TensorFlow Lite Interpreter theo runtime ưu tiên.
5. Chia ảnh thành tile theo kích thước input của model.
6. Chạy inference từng tile.
7. Ghép tile output thành ảnh kết quả.
8. Nếu chọn 2x hoặc 4x nhưng không có model native tương ứng, app dùng model 3x rồi resize xuống/lên.
9. Lưu ảnh PNG.

### Model cần có

Thư mục model:

```text
app/src/main/assets/models/
```

Model bắt buộc cho pipeline mặc định:

```text
xlsr_3x_w8a8.tflite
```

Model tùy chọn:

```text
xlsr_3x_float.tflite
xlsr_2x.tflite
xlsr_4x.tflite
```

Nếu không có `xlsr_2x.tflite`, chế độ 2x sẽ dùng pipeline `XLSR 3x + resize down`.
Nếu không có `xlsr_4x.tflite`, chế độ 4x sẽ dùng pipeline `XLSR 3x + resize up`.

### Cấu hình build

- Android Gradle Plugin: `8.7.3`.
- Gradle Wrapper: `8.10.2`.
- Kotlin Android Plugin: `2.0.21`.
- `compileSdk 35`.
- `minSdk 26`.
- `targetSdk 35`.
- Java/Kotlin target: JVM 17.
- ABI hiện giới hạn ở `arm64-v8a`.
- NDK cấu hình: `28.2.13676358`.

### Mở bằng Android Studio

1. Mở Android Studio.
2. Chọn **File > Open**.
3. Chọn thư mục `QualcommXlsrUpscaler`.
4. Chờ Gradle Sync hoàn tất.
5. Kiểm tra `local.properties` đã trỏ đúng Android SDK.
6. Đảm bảo model TFLite nằm trong `app/src/main/assets/models/`.
7. Build hoặc chạy app trên thiết bị Android ARM64.

### Cấu trúc chính

```text
app/src/main/AndroidManifest.xml
app/src/main/java/com/vung/upscaylultramix/MainActivity.kt
app/src/main/java/com/vung/upscaylultramix/XlsrUpscaleEngine.kt
app/src/main/java/com/vung/upscaylultramix/TfliteModelRunner.kt
app/src/main/java/com/vung/upscaylultramix/RuntimeDelegateFactory.kt
app/src/main/java/com/vung/upscaylultramix/TileProcessor.kt
app/src/main/res/layout/activity_main.xml
app/src/main/assets/models/
```

### Ghi chú về native/NCNN

Repo có thể còn một số file native/CMake từ hướng thử nghiệm NCNN cũ. Tuy nhiên pipeline chính đang được app gọi hiện tại là TensorFlow Lite/XLSR, không phải NCNN/RealESRGAN.

### Việc cần làm tiếp theo

- Thêm chế độ `Text Enhance` để làm nét chữ/tài liệu.
- Thêm lựa chọn mode trong UI: Standard Upscale, Text Enhance, Text Upscale.
- Tối ưu giới hạn kích thước ảnh theo RAM thực tế của thiết bị.
- Dọn các file native/NCNN không còn dùng nếu quyết định giữ hướng TFLite.
- Hoặc tích hợp lại native/NCNN thật sự nếu muốn quay về pipeline Ultramix/RealESRGAN.

---

## English

**Qualcomm XLSR Upscaler** is a minimal Android image upscaler powered by Qualcomm XLSR models through TensorFlow Lite. The app prefers NNAPI/NPU acceleration and falls back to GPU or CPU when the selected runtime is unavailable.

Current repository: `HoangVung/QualcommXlsrUpscaler`.

### Current features

- Kotlin Android UI.
- Select an image from the device.
- Choose an output folder with Android Storage Access Framework.
- Remember the selected output folder.
- Select upscale ratio: 2x, 3x, or 4x.
- Select runtime: NNAPI/NPU, GPU, or CPU.
- Tile-based processing to reduce memory pressure.
- Tile progress and elapsed-time display.
- Cancel running upscale jobs.
- Export PNG results to the selected folder or the app default folder.

### Current pipeline

The current implementation uses TensorFlow Lite:

```text
app/src/main/java/com/vung/upscaylultramix/XlsrUpscaleEngine.kt
app/src/main/java/com/vung/upscaylultramix/TfliteModelRunner.kt
app/src/main/java/com/vung/upscaylultramix/RuntimeDelegateFactory.kt
app/src/main/java/com/vung/upscaylultramix/TileProcessor.kt
```

Main pipeline:

1. The user selects an image.
2. The app checks the input image size limit.
3. The TFLite model is copied from `assets/models` to cache.
4. A TensorFlow Lite Interpreter is created using the preferred runtime.
5. The image is split into tiles based on the model input size.
6. Inference is run tile by tile.
7. Output tiles are stitched into the final image.
8. If 2x or 4x is selected but no native model is available, the app uses the 3x model and resizes down/up.
9. The final PNG is saved.

### Required models

Model directory:

```text
app/src/main/assets/models/
```

Required model for the default pipeline:

```text
xlsr_3x_w8a8.tflite
```

Optional models:

```text
xlsr_3x_float.tflite
xlsr_2x.tflite
xlsr_4x.tflite
```

If `xlsr_2x.tflite` is missing, 2x mode uses `XLSR 3x + resize down`.
If `xlsr_4x.tflite` is missing, 4x mode uses `XLSR 3x + resize up`.

### Build configuration

- Android Gradle Plugin: `8.7.3`.
- Gradle Wrapper: `8.10.2`.
- Kotlin Android Plugin: `2.0.21`.
- `compileSdk 35`.
- `minSdk 26`.
- `targetSdk 35`.
- Java/Kotlin target: JVM 17.
- Current ABI filter: `arm64-v8a`.
- Configured NDK version: `28.2.13676358`.

### Open in Android Studio

1. Open Android Studio.
2. Select **File > Open**.
3. Choose the `QualcommXlsrUpscaler` folder.
4. Wait for Gradle Sync to finish.
5. Check that `local.properties` points to the correct Android SDK.
6. Make sure the TFLite model exists in `app/src/main/assets/models/`.
7. Build or run the app on an ARM64 Android device.

### Main structure

```text
app/src/main/AndroidManifest.xml
app/src/main/java/com/vung/upscaylultramix/MainActivity.kt
app/src/main/java/com/vung/upscaylultramix/XlsrUpscaleEngine.kt
app/src/main/java/com/vung/upscaylultramix/TfliteModelRunner.kt
app/src/main/java/com/vung/upscaylultramix/RuntimeDelegateFactory.kt
app/src/main/java/com/vung/upscaylultramix/TileProcessor.kt
app/src/main/res/layout/activity_main.xml
app/src/main/assets/models/
```

### Note about native/NCNN

The repository may still contain native/CMake files from an older NCNN experiment. The app's current main pipeline is TensorFlow Lite/XLSR, not NCNN/RealESRGAN.

### Next steps

- Add `Text Enhance` mode for documents and text-heavy images.
- Add mode selection in the UI: Standard Upscale, Text Enhance, Text Upscale.
- Tune the image-size limit based on actual device RAM.
- Remove unused native/NCNN files if the project keeps the TFLite direction.
- Or wire the native/NCNN pipeline back into the app if the project should return to Ultramix/RealESRGAN.
