# PDF Merger App

Ứng dụng nhỏ dùng để ghép nhiều file PDF thành một file PDF duy nhất.

## Chức năng

- Chọn nhiều file PDF.
- Kéo thả file PDF vào giao diện.
- Sắp xếp thứ tự ghép.
- Xóa file khỏi danh sách.
- Hiển thị tiến trình ghép.
- Xuất ra một file PDF duy nhất.

## Công nghệ sử dụng

- Python
- PySide6
- pypdf
- PyInstaller

## Cài đặt thư viện

```bash
pip install pyside6 pypdf
```

## Chạy chương trình

```bash
python pdf_merger_modern.py
```

## Đóng gói thành file EXE trên Windows

Cài PyInstaller:

```bash
pip install pyinstaller
```

Build ứng dụng:

```bash
python -m PyInstaller --onefile --windowed --name "PDF Merger" --collect-all pypdf --collect-all PySide6 pdf_merger_modern.py
```

Hoặc chạy file:

```text
build_windows.bat
```

File kết quả sẽ nằm trong thư mục `dist/`.

## Ghi chú

Không nên đưa thư mục `build/`, `dist/` và file `.spec` lên GitHub vì đây là các file sinh ra khi đóng gói.

Nếu file `.exe` báo thiếu thư viện `pypdf`, hãy build lại bằng lệnh có `--collect-all pypdf`.
