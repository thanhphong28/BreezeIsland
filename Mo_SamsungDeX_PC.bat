@echo off
setlocal EnableExtensions EnableDelayedExpansion
chcp 65001 >nul
title BreezeIsland — Mở Samsung DeX trên Laptop (Galaxy Z Flip 5)

set "DEX_PORT=8765"
set "DEX_URL=http://127.0.0.1:%DEX_PORT%"
set "WATCH_MODE=0"
if /I "%~1"=="--watch" set "WATCH_MODE=1"

:: Tìm đường dẫn adb.exe tự động
set "ADB_EXE="
if exist "C:\Android\Sdk\platform-tools\adb.exe" set "ADB_EXE=C:\Android\Sdk\platform-tools\adb.exe"
if not defined ADB_EXE if exist "%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" set "ADB_EXE=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
if not defined ADB_EXE if defined ANDROID_HOME if exist "%ANDROID_HOME%\platform-tools\adb.exe" set "ADB_EXE=%ANDROID_HOME%\platform-tools\adb.exe"
if not defined ADB_EXE (
    for %%I in (adb.exe) do if not "%%~$PATH:I"=="" set "ADB_EXE=%%~$PATH:I"
)

if not defined ADB_EXE (
    echo ============================================================================
    echo  KHÔNG TÌM THẤY ADB (Android Platform-Tools)!
    echo  Hãy cài Android Platform-Tools vào C:\Android\Sdk\platform-tools\
    echo  hoặc thêm adb.exe vào biến môi trường PATH.
    echo ============================================================================
    pause
    exit /b 1
)

:MAIN_LOOP
cls
echo ============================================================================
echo        BREEZE ISLAND — SAMSUNG DeX FOR PC (GALAXY Z FLIP 5)
echo   Cắm cáp USB-C to C / USB-C to A vào Laptop là tự mở Samsung DeX!
echo ============================================================================
echo.

echo [1/4] Đang chờ kết nối Galaxy Z Flip 5 qua dây USB...
echo       (Nếu chưa cắm cáp, hãy cắm vào ngay — mở khóa điện thoại ^& bật Gỡ lỗi USB)
"%ADB_EXE%" wait-for-device >nul 2>&1
if errorlevel 1 (
    echo [LỖI] Không thể kết nối với ADB. Hãy kiểm tra lại dây cáp USB!
    pause
    exit /b 1
)

echo       -^> Đã nhận Galaxy Z Flip 5 qua USB!
echo.

echo [2/4] Đang thiết lập đường truyền tốc độ cao qua dây USB (port %DEX_PORT%)...
"%ADB_EXE%" forward tcp:%DEX_PORT% tcp:%DEX_PORT% >nul 2>&1
if errorlevel 1 (
    echo [LỖI] Không thể chuyển tiếp cổng USB tcp:%DEX_PORT%.
    echo       Hãy kiểm tra thông báo "Cho phép Gỡ lỗi USB" trên màn hình điện thoại!
    pause
    exit /b 1
)
echo       -^> Đường truyền USB đã sẵn sàng (%DEX_URL%).
echo.

echo [3/4] Đang kiểm tra Samsung DeX trên điện thoại...
call :CHECK_DEX_LIVE
if "!DEX_LIVE!"=="1" goto :OPEN_BROWSER

echo       -^> Samsung DeX chưa bật. Đang tự mở sẵn màn hình Samsung DeX trên Z Flip 5...
"%ADB_EXE%" shell am start -n com.breeze.island/com.arnav.island.settings.MainActivity --es destination local_dex >nul 2>&1
echo       -^> Hãy gạt nút [Bật Samsung DeX] trên điện thoại của bạn!
echo          (Đang tự động chờ Samsung DeX khởi động...)

set /a WAIT_SEC=0
:WAIT_DEX_LOOP
timeout /t 1 /nobreak >nul
call :CHECK_DEX_LIVE
if "!DEX_LIVE!"=="1" goto :OPEN_BROWSER
set /a WAIT_SEC+=1
if !WAIT_SEC! GEQ 90 (
    echo.
    echo [CẢNH BÁO] Quá 90 giây chưa thấy Samsung DeX phản hồi trên cổng %DEX_PORT%.
    echo Hãy kiểm tra app BreezeIsland trên điện thoại đã cập nhật lên v2.7.0+ và đã bật DeX chưa.
    pause
    exit /b 1
)
goto :WAIT_DEX_LOOP

:OPEN_BROWSER
echo.
echo [4/4] Samsung DeX đã sẵn sàng! Đang mở cửa sổ Samsung DeX trên Laptop...
echo       - Chuột trái/phải, kéo thả cửa sổ, cuộn chuột ^& bàn phím hoạt động trực tiếp.
echo       - Nhấn F11 hoặc bấm nút Fullscreen ở thanh trên cùng để phóng toàn màn hình.
echo.

set "BROWSER_EXE="
if exist "%ProgramFiles%\Google\Chrome\Application\chrome.exe" set "BROWSER_EXE=%ProgramFiles%\Google\Chrome\Application\chrome.exe"
if not defined BROWSER_EXE if exist "%ProgramFiles(x86)%\Google\Chrome\Application\chrome.exe" set "BROWSER_EXE=%ProgramFiles(x86)%\Google\Chrome\Application\chrome.exe"
if not defined BROWSER_EXE if exist "%ProgramFiles(x86)%\Microsoft\Edge\Application\msedge.exe" set "BROWSER_EXE=%ProgramFiles(x86)%\Microsoft\Edge\Application\msedge.exe"
if not defined BROWSER_EXE if exist "%ProgramFiles%\Microsoft\Edge\Application\msedge.exe" set "BROWSER_EXE=%ProgramFiles%\Microsoft\Edge\Application\msedge.exe"

if defined BROWSER_EXE (
    start "" "!BROWSER_EXE!" --app=%DEX_URL% --start-maximized
) else (
    start "" "%DEX_URL%"
)

if "%WATCH_MODE%"=="1" (
    echo [CHẾ ĐỘ TỰ ĐỘNG] Đang chờ bạn rút cáp rồi cắm lại lần sau...
    "%ADB_EXE%" wait-for-disconnect >nul 2>&1
    timeout /t 2 /nobreak >nul
    goto :MAIN_LOOP
)

timeout /t 3 /nobreak >nul
exit /b 0

:CHECK_DEX_LIVE
set "DEX_LIVE=0"
curl.exe -fsS --connect-timeout 1 --max-time 2 "%DEX_URL%/" >nul 2>&1
if not errorlevel 1 set "DEX_LIVE=1"
exit /b 0
