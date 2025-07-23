@echo off
title Drowsiness Detection System
echo Starting Drowsiness Detection System...

:: Clear any conflicting Python paths
set PYTHONPATH=
set PYTHONHOME=

:: Set correct environment
set PYTHONHOME=%~dp0python
set PATH=%~dp0python;%~dp0python\Scripts;%PATH%

:: Install core packages
python -m pip install --no-index --find-links="%~dp0Lib\site-packages" numpy==2.2.4
python -m pip install --no-index --find-links="%~dp0Lib\site-packages" opencv_python==4.11.0.86
python -m pip install --no-index --find-links="%~dp0Lib\site-packages" onnxruntime==1.21.0

:: Install additional required packages
python -m pip install --no-index --find-links="%~dp0Lib\site-packages" requests==2.31.0
python -m pip install --no-index --find-links="%~dp0Lib\site-packages" pushbullet.py==0.12.0
python -m pip install --no-index --find-links="%~dp0Lib\site-packages" python-telegram-bot==20.4

:: Install telebot if needed (uncomment if using Option 2)
:: python -m pip install --no-index --find-links="%~dp0Lib\site-packages" pyTelegramBotAPI

:: Verify all packages
python -c "from telegram import Bot; import cv2, numpy, onnxruntime, requests, pushbullet; print('All packages loaded successfully')" || (
    echo ERROR: Failed to import required packages
    pause
    exit /b 1
)

:: Run main script
python "%~dp0drowsiness_detection.py"

pause