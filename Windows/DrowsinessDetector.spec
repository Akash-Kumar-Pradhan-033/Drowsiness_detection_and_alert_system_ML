# -*- mode: python ; coding: utf-8 -*-


a = Analysis(
    ['E:\\Windows\\drowsiness_detection.py'],
    pathex=['E:\\Windows\\python', 'E:\\Windows\\Lib\\site-packages'],
    binaries=[],
    datas=[('drowsiness_model.onnx', '.'), ('python312._pth', '.'), ('Lib/site-packages/cv2', 'cv2'), ('Lib/site-packages/numpy', 'numpy'), ('Lib/site-packages/onnxruntime', 'onnxruntime')],
    hiddenimports=['cv2', 'numpy', 'onnxruntime', 'requests', 'pushbullet', 'telegram'],
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=[],
    noarchive=False,
    optimize=0,
)
pyz = PYZ(a.pure)

exe = EXE(
    pyz,
    a.scripts,
    a.binaries,
    a.datas,
    [],
    name='DrowsinessDetector',
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=True,
    upx_exclude=[],
    runtime_tmpdir=None,
    console=False,
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
)
