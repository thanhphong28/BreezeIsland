@echo off
chcp 65001 >nul
title Cài đặt BreezeIsland cho Samsung Galaxy Z Flip 5 (One UI 9)
echo ============================================================================
echo        BREEZE ISLAND - TRÌNH CÀI ĐẶT TỐI ƯU CHO GALAXY Z FLIP 5 (ONE UI 9)
echo   (Không dùng Quyền Trợ Năng / Accessibility - Dùng App Ngân Hàng 100%%)
echo ============================================================================
echo.

set ADB_EXE=adb
if exist "%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" (
    set ADB_EXE="%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
)

echo [1/4] Đang kiểm tra kết nối điện thoại Galaxy Z Flip 5...
%ADB_EXE% devices
echo.

echo [2/4] Đang cài đặt BreezeIsland-ZFlip5.apk...
%ADB_EXE% install -r "BreezeIsland-ZFlip5.apk"
echo.

echo [3/4] Đang cấp quyền Hệ thống giúp BreezeIsland nằm đè lên Status Bar One UI 9...
echo       (Hoàn toàn KHÔNG bật Accessibility Service nên App Ngân hàng chạy 100%%)
%ADB_EXE% shell pm grant com.breeze.island android.permission.WRITE_SECURE_SETTINGS
%ADB_EXE% shell appops set com.breeze.island SYSTEM_ALERT_WINDOW allow
%ADB_EXE% shell appops set com.breeze.island WRITE_SETTINGS allow
%ADB_EXE% shell cmd notification allow_listener com.breeze.island/com.arnav.island.events.notification.IslandNotificationListener
echo.

echo [4/4] Đang ẩn chip thông báo thừa trên Status Bar One UI 9 để không đè lên Island...
%ADB_EXE% shell settings put system simple_status_bar 0
%ADB_EXE% shell settings put system status_bar_show_notification_icon 0
echo.

echo ============================================================================
echo  HOÀN TẤT! Hãy mở app BreezeIsland trên điện thoại Z Flip 5 của bạn.
echo  MẸO NHỎ TRÊN ONE UI 9:
echo  Vào Cài đặt máy -^> Màn hình khóa và AOD -^> Now bar -^> Thông báo trực tiếp
echo  và tắt mục "Trình phát media" để chip [▷ Bài hát] của Samsung không hiện trùng!
echo ============================================================================
pause
