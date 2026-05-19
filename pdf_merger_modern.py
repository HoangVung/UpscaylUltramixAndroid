import sys
import os
from pathlib import Path

from pypdf import PdfReader, PdfWriter

from PySide6.QtCore import Qt, QThread, Signal
from PySide6.QtGui import QDesktopServices
from PySide6.QtCore import QUrl
from PySide6.QtWidgets import (
    QApplication,
    QMainWindow,
    QWidget,
    QListWidget,
    QListWidgetItem,
    QPushButton,
    QLabel,
    QFileDialog,
    QMessageBox,
    QVBoxLayout,
    QHBoxLayout,
    QProgressBar,
    QFrame,
)


class MergeWorker(QThread):
    progress = Signal(int)
    finished = Signal(str, int, int)
    error = Signal(str)

    def __init__(self, pdf_files, output_file):
        super().__init__()
        self.pdf_files = pdf_files
        self.output_file = output_file

    def run(self):
        try:
            writer = PdfWriter()

            total_files = len(self.pdf_files)
            total_pages = 0

            for index, pdf_path in enumerate(self.pdf_files, start=1):
                reader = PdfReader(str(pdf_path))

                if reader.is_encrypted:
                    try:
                        reader.decrypt("")
                    except Exception:
                        self.error.emit(f"Không thể mở file PDF bị khóa:\n{pdf_path.name}")
                        return

                for page in reader.pages:
                    writer.add_page(page)
                    total_pages += 1

                percent = int(index / total_files * 100)
                self.progress.emit(percent)

            with open(self.output_file, "wb") as f:
                writer.write(f)

            self.finished.emit(self.output_file, total_files, total_pages)

        except Exception as e:
            self.error.emit(str(e))


class DropListWidget(QListWidget):
    files_dropped = Signal(list)

    def __init__(self):
        super().__init__()
        self.setAcceptDrops(True)
        self.setSelectionMode(QListWidget.SingleSelection)

    def dragEnterEvent(self, event):
        if event.mimeData().hasUrls():
            event.acceptProposedAction()
        else:
            event.ignore()

    def dragMoveEvent(self, event):
        if event.mimeData().hasUrls():
            event.acceptProposedAction()
        else:
            event.ignore()

    def dropEvent(self, event):
        paths = []

        for url in event.mimeData().urls():
            path = Path(url.toLocalFile())
            if path.suffix.lower() == ".pdf":
                paths.append(path)

        if paths:
            self.files_dropped.emit(paths)


class PDFMergerWindow(QMainWindow):
    def __init__(self):
        super().__init__()

        self.setWindowTitle("PDF Merger - Ghép file PDF")
        self.resize(820, 560)
        self.setMinimumSize(760, 520)

        self.pdf_files = []
        self.worker = None

        self.init_ui()

    def init_ui(self):
        main_widget = QWidget()
        self.setCentralWidget(main_widget)

        main_layout = QVBoxLayout()
        main_layout.setSpacing(12)
        main_layout.setContentsMargins(18, 18, 18, 18)
        main_widget.setLayout(main_layout)

        title = QLabel("CHƯƠNG TRÌNH GHÉP FILE PDF")
        title.setAlignment(Qt.AlignCenter)
        title.setObjectName("TitleLabel")
        main_layout.addWidget(title)

        subtitle = QLabel("Kéo thả file PDF vào danh sách hoặc bấm “Thêm PDF”. Sau đó sắp xếp thứ tự và bấm “Ghép PDF”.")
        subtitle.setAlignment(Qt.AlignCenter)
        subtitle.setObjectName("SubtitleLabel")
        main_layout.addWidget(subtitle)

        line = QFrame()
        line.setFrameShape(QFrame.HLine)
        line.setFrameShadow(QFrame.Sunken)
        main_layout.addWidget(line)

        content_layout = QHBoxLayout()
        main_layout.addLayout(content_layout, stretch=1)

        self.list_widget = DropListWidget()
        self.list_widget.setObjectName("PDFList")
        self.list_widget.files_dropped.connect(self.add_pdf_paths)
        content_layout.addWidget(self.list_widget, stretch=1)

        side_layout = QVBoxLayout()
        content_layout.addLayout(side_layout)

        self.btn_add = QPushButton("Thêm PDF")
        self.btn_remove = QPushButton("Xóa file")
        self.btn_up = QPushButton("Đưa lên")
        self.btn_down = QPushButton("Đưa xuống")
        self.btn_clear = QPushButton("Xóa tất cả")

        side_buttons = [
            self.btn_add,
            self.btn_remove,
            self.btn_up,
            self.btn_down,
            self.btn_clear,
        ]

        for btn in side_buttons:
            btn.setMinimumWidth(130)
            btn.setMinimumHeight(36)
            side_layout.addWidget(btn)

        side_layout.addStretch()

        self.btn_merge = QPushButton("GHÉP PDF")
        self.btn_merge.setObjectName("MergeButton")
        self.btn_merge.setMinimumHeight(48)
        side_layout.addWidget(self.btn_merge)

        self.progress_bar = QProgressBar()
        self.progress_bar.setValue(0)
        main_layout.addWidget(self.progress_bar)

        self.status_label = QLabel("Chưa chọn file PDF nào.")
        self.status_label.setObjectName("StatusLabel")
        main_layout.addWidget(self.status_label)

        self.btn_add.clicked.connect(self.add_files)
        self.btn_remove.clicked.connect(self.remove_selected)
        self.btn_up.clicked.connect(self.move_up)
        self.btn_down.clicked.connect(self.move_down)
        self.btn_clear.clicked.connect(self.clear_all)
        self.btn_merge.clicked.connect(self.merge_pdfs)

        self.apply_style()

    def apply_style(self):
        self.setStyleSheet("""
            QMainWindow {
                background-color: #f4f6f8;
            }

            QLabel#TitleLabel {
                font-size: 22px;
                font-weight: bold;
                color: #1f2937;
            }

            QLabel#SubtitleLabel {
                font-size: 13px;
                color: #4b5563;
            }

            QLabel#StatusLabel {
                font-size: 12px;
                color: #374151;
            }

            QListWidget#PDFList {
                background-color: white;
                border: 1px solid #d1d5db;
                border-radius: 8px;
                padding: 8px;
                font-size: 13px;
            }

            QListWidget#PDFList::item {
                padding: 8px;
                border-bottom: 1px solid #e5e7eb;
            }

            QListWidget#PDFList::item:selected {
                background-color: #dbeafe;
                color: #111827;
            }

            QPushButton {
                background-color: white;
                border: 1px solid #cbd5e1;
                border-radius: 7px;
                padding: 8px 12px;
                font-size: 13px;
            }

            QPushButton:hover {
                background-color: #f1f5f9;
            }

            QPushButton:pressed {
                background-color: #e2e8f0;
            }

            QPushButton#MergeButton {
                background-color: #2563eb;
                color: white;
                font-weight: bold;
                border: none;
                font-size: 15px;
            }

            QPushButton#MergeButton:hover {
                background-color: #1d4ed8;
            }

            QPushButton#MergeButton:pressed {
                background-color: #1e40af;
            }

            QProgressBar {
                border: 1px solid #d1d5db;
                border-radius: 7px;
                text-align: center;
                height: 20px;
                background-color: white;
            }

            QProgressBar::chunk {
                background-color: #2563eb;
                border-radius: 7px;
            }
        """)

    def add_files(self):
        files, _ = QFileDialog.getOpenFileNames(
            self,
            "Chọn file PDF",
            "",
            "PDF files (*.pdf)"
        )

        paths = [Path(file) for file in files]
        self.add_pdf_paths(paths)

    def add_pdf_paths(self, paths):
        added = 0

        for path in paths:
            path = Path(path)

            if path.suffix.lower() != ".pdf":
                continue

            if path not in self.pdf_files:
                self.pdf_files.append(path)
                added += 1

        if added > 0:
            self.refresh_list()

    def refresh_list(self):
        self.list_widget.clear()

        for index, path in enumerate(self.pdf_files, start=1):
            item = QListWidgetItem(f"{index}. {path.name}")
            item.setToolTip(str(path))
            self.list_widget.addItem(item)

        self.status_label.setText(f"Đã chọn {len(self.pdf_files)} file PDF.")
        self.progress_bar.setValue(0)

    def remove_selected(self):
        row = self.list_widget.currentRow()

        if row < 0:
            QMessageBox.warning(self, "Thông báo", "Vui lòng chọn file cần xóa.")
            return

        del self.pdf_files[row]
        self.refresh_list()

    def move_up(self):
        row = self.list_widget.currentRow()

        if row <= 0:
            return

        self.pdf_files[row], self.pdf_files[row - 1] = self.pdf_files[row - 1], self.pdf_files[row]
        self.refresh_list()
        self.list_widget.setCurrentRow(row - 1)

    def move_down(self):
        row = self.list_widget.currentRow()

        if row < 0 or row >= len(self.pdf_files) - 1:
            return

        self.pdf_files[row], self.pdf_files[row + 1] = self.pdf_files[row + 1], self.pdf_files[row]
        self.refresh_list()
        self.list_widget.setCurrentRow(row + 1)

    def clear_all(self):
        if not self.pdf_files:
            return

        confirm = QMessageBox.question(
            self,
            "Xác nhận",
            "Bạn có chắc muốn xóa toàn bộ danh sách PDF không?"
        )

        if confirm == QMessageBox.Yes:
            self.pdf_files.clear()
            self.refresh_list()
            self.status_label.setText("Đã xóa toàn bộ danh sách.")

    def set_buttons_enabled(self, enabled):
        self.btn_add.setEnabled(enabled)
        self.btn_remove.setEnabled(enabled)
        self.btn_up.setEnabled(enabled)
        self.btn_down.setEnabled(enabled)
        self.btn_clear.setEnabled(enabled)
        self.btn_merge.setEnabled(enabled)

    def merge_pdfs(self):
        if not self.pdf_files:
            QMessageBox.warning(self, "Thiếu file", "Vui lòng chọn ít nhất một file PDF.")
            return

        output_file, _ = QFileDialog.getSaveFileName(
            self,
            "Lưu file PDF sau khi ghép",
            "merged.pdf",
            "PDF files (*.pdf)"
        )

        if not output_file:
            return

        if not output_file.lower().endswith(".pdf"):
            output_file += ".pdf"

        self.progress_bar.setValue(0)
        self.status_label.setText("Đang ghép PDF...")
        self.set_buttons_enabled(False)

        self.worker = MergeWorker(self.pdf_files.copy(), output_file)
        self.worker.progress.connect(self.progress_bar.setValue)
        self.worker.finished.connect(self.on_merge_finished)
        self.worker.error.connect(self.on_merge_error)
        self.worker.start()

    def on_merge_finished(self, output_file, total_files, total_pages):
        self.set_buttons_enabled(True)
        self.progress_bar.setValue(100)

        self.status_label.setText(
            f"Hoàn tất: đã ghép {total_files} file, tổng {total_pages} trang."
        )

        open_file = QMessageBox.question(
            self,
            "Hoàn tất",
            f"Đã ghép PDF thành công.\n\n"
            f"File xuất ra:\n{output_file}\n\n"
            f"Tổng số file: {total_files}\n"
            f"Tổng số trang: {total_pages}\n\n"
            f"Bạn có muốn mở file kết quả không?"
        )

        if open_file == QMessageBox.Yes:
            QDesktopServices.openUrl(QUrl.fromLocalFile(output_file))

    def on_merge_error(self, message):
        self.set_buttons_enabled(True)
        self.status_label.setText("Có lỗi xảy ra khi ghép PDF.")

        QMessageBox.critical(
            self,
            "Lỗi",
            f"Có lỗi xảy ra khi ghép PDF:\n\n{message}"
        )


def main():
    app = QApplication(sys.argv)
    window = PDFMergerWindow()
    window.show()
    sys.exit(app.exec())


if __name__ == "__main__":
    main()