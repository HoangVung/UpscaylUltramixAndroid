@echo off
cd /d "%~dp0"

echo Dang cai / cap nhat thu vien can thiet...
python -m pip install --upgrade pip
python -m pip install --upgrade pypdf pyside6 pyinstaller

echo.
echo Dang xoa ban build cu...
rmdir /s /q build 2>nul
rmdir /s /q dist 2>nul
del /q "PDF Merger.spec" 2>nul

echo.
echo Dang dong goi chuong trinh...
python -m PyInstaller ^
  --onefile ^
  --windowed ^
  --name "PDF Merger" ^
  --hidden-import=pypdf ^
  --hidden-import=pypdf._reader ^
  --hidden-import=pypdf._writer ^
  pdf_merger_modern.py

echo.
echo Hoan tat.
echo File EXE nam trong thu muc dist
pause