# Manual QA Checklist — Samsung Galaxy Z Flip 5 (One UI 9)

Run through on the physical device after installing `BreezeIsland-ZFlip5.apk`.

## 1. First Launch & Bilingual Setup
- [ ] Onboarding launches with **Tiếng Việt / English** language toggle at top right.
- [ ] Switching between **Tiếng Việt** and **English** translates all onboarding and settings screens immediately.
- [ ] Granting **Display over other apps**, **Notification access**, **Modify system settings**, and **Unrestricted battery** updates the status indicators live.

## 2. One UI 9 Status Bar & Overlap Prevention
- [ ] With **Modify system settings** granted, redundant status bar notification icons are hidden automatically (`simple_status_bar = 0`).
- [ ] Starting music playback auto-expands the BreezeIsland media card cleanly over the top area.
- [ ] Incoming notifications snooze duplicate One UI heads-up pop-ups while BreezeIsland displays the banner.
- [ ] Banking apps and VNeID open without any Accessibility Service warning.

## 3. In-App OTA Update (`Kiểm tra cập nhật`)
- [ ] **Settings → System → Check for updates (Kiểm tra cập nhật)** and **About → Software update** connect to `https://github.com/thanhphong28/BreezeIsland/releases/latest`.
- [ ] Shows current version status (`v1.2.0`) and allows downloading & installing the APK directly in-app.
