@echo off
chcp 65001 >nul
echo ============================================================================
echo   BREEZE ISLAND - CAP QUYEN DE LEN TREN THANH TRANG THAI (KHONG TRỢ NĂNG)
echo   Hoan toan tuong thich 100%% voi tat ca App Ngan Hang & VNeID
echo ============================================================================
echo.
set ADB="%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
if not exist %ADB% set ADB=adb

%ADB% devices
echo.
echo Dang cap quyen WRITE_SECURE_SETTINGS cho BreezeIsland (com.breeze.island)...
%ADB% shell pm grant com.breeze.island android.permission.WRITE_SECURE_SETTINGS
%ADB% shell settings put system simple_status_bar 0
%ADB% shell settings put system status_bar_show_notification_icon 0
echo.
echo [HOAN TAT] Quyen da duoc luu vinh vien tren may!
echo Ban co the tat Go loi USB (USB Debugging) va rut cap, quyen van giu nguyen.
pause
