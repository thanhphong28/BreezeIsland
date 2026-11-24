@echo off
setlocal EnableExtensions EnableDelayedExpansion
title BreezeIsland - Mo Samsung DeX tren Laptop (Galaxy Z Flip 5)

set "DEX_PORT=8765"
set "DEX_URL=http://127.0.0.1:%DEX_PORT%"
set "WATCH_MODE=0"
if /I "%~1"=="--watch" set "WATCH_MODE=1"

set "ADB_EXE="
if exist "C:\Android\Sdk\platform-tools\adb.exe" set "ADB_EXE=C:\Android\Sdk\platform-tools\adb.exe"
if not defined ADB_EXE if exist "%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" set "ADB_EXE=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
if not defined ADB_EXE if defined ANDROID_HOME if exist "%ANDROID_HOME%\platform-tools\adb.exe" set "ADB_EXE=%ANDROID_HOME%\platform-tools\adb.exe"
if not defined ADB_EXE (
    for %%I in (adb.exe) do if not "%%~$PATH:I"=="" set "ADB_EXE=%%~$PATH:I"
)

if not defined ADB_EXE (
    echo ============================================================================
    echo  KHONG TIM THAY ADB ^(Android Platform-Tools^)!
    echo  Hay cai Android Platform-Tools vao C:\Android\Sdk\platform-tools\
    echo  hoac them adb.exe vao bien moi truong PATH.
    echo ============================================================================
    pause
    exit /b 1
)

:MAIN_LOOP
cls
echo ============================================================================
echo         BREEZE ISLAND - SAMSUNG DeX FOR PC ^(GALAXY Z FLIP 5^)
echo    Cam cap USB-C to C / USB-C to A vao Laptop la tu mo Samsung DeX!
echo ============================================================================
echo.

echo [1/4] Dang cho ket noi Galaxy Z Flip 5 qua day USB...
echo       ^(Neu chua cam cap, hay cam vao ngay - mo khoa DT va bat Go loi USB^)
"%ADB_EXE%" wait-for-device >nul 2>&1
if errorlevel 1 (
    echo [LOI] Khong the ket noi ADB. Hay kiem tra lai day cap USB!
    pause
    exit /b 1
)

echo       -^> Da nhan Galaxy Z Flip 5 qua USB!
echo.

echo [2/4] Dang thiet lap duong truyen USB toc do cao ^(port %DEX_PORT%^)...
"%ADB_EXE%" forward tcp:%DEX_PORT% tcp:%DEX_PORT% >nul 2>&1
if errorlevel 1 (
    echo [LOI] Khong the chuyen tiep cong USB tcp:%DEX_PORT%.
    echo       Hay bam "Cho phep Go loi USB" tren man hinh dien thoai!
    pause
    exit /b 1
)
echo       -^> Duong truyen USB da san sang ^(%DEX_URL%^).
echo.

echo [3/4] Dang kiem tra Samsung DeX tren dien thoai...
call :CHECK_DEX_LIVE
if "!DEX_LIVE!"=="1" goto OPEN_BROWSER

echo       -^> Samsung DeX chua bat. Dang tu mo man hinh Samsung DeX tren Z Flip 5...
"%ADB_EXE%" shell am start -n com.breeze.island/com.arnav.island.settings.MainActivity --es destination local_dex >nul 2>&1
echo       -^> Hay gat nut [Bat Samsung DeX] tren dien thoai cua ban!
echo          ^(Dang tu dong cho Samsung DeX khoi dong...^)

set /a WAIT_SEC=0
:WAIT_DEX_LOOP
timeout /t 1 /nobreak >nul
call :CHECK_DEX_LIVE
if "!DEX_LIVE!"=="1" goto OPEN_BROWSER
set /a WAIT_SEC+=1
if !WAIT_SEC! GEQ 90 (
    echo.
    echo [CANH BAO] Qua 90 giay chua thay Samsung DeX phan hoi tren cong %DEX_PORT%.
    echo Hay kiem tra BreezeIsland tren DT da cap nhat len v2.7.0+ va da bat DeX chua.
    pause
    exit /b 1
)
goto WAIT_DEX_LOOP

:OPEN_BROWSER
echo.
echo [4/4] Samsung DeX da san sang! Dang mo cua so Samsung DeX tren Laptop...
echo       - Chuot trai/phai, keo tha cua so, cuon chuot va ban phim hoat dong truc tiep.
echo       - Nhan F11 hoac bam nut Fullscreen o thanh tren cung de phong toan man hinh.
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
    echo [CHE DO TU DONG] Dang cho ban rut cap roi cam lai lan sau...
    "%ADB_EXE%" wait-for-disconnect >nul 2>&1
    timeout /t 2 /nobreak >nul
    goto MAIN_LOOP
)

timeout /t 3 /nobreak >nul
exit /b 0

:CHECK_DEX_LIVE
set "DEX_LIVE=0"
curl.exe -fsS --connect-timeout 1 --max-time 2 "%DEX_URL%/" >nul 2>&1
if not errorlevel 1 set "DEX_LIVE=1"
exit /b 0
