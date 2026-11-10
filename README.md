<p align="center">
  <img src="app/src/main/res/drawable-nodpi/ic_breeze_logo.png" width="120" alt="BreezeIsland Logo">
</p>

<h1 align="center">BreezeIsland</h1>

<p align="center">
  A Dynamic Island–style live activity system for Android, <b>built for the Samsung Galaxy Z Flip 5 (One UI 9)</b> and Samsung One UI phones.<br>
  <b>Zero Root · Zero Accessibility Service (100% Banking & VNeID Compatible) · Built-in OTA Self-Update · Bilingual (Tiếng Việt & English)</b>
</p>

<p align="center">
  <a href="https://github.com/thanhphong28/BreezeIsland/releases/latest">
    <img src="https://img.shields.io/github/v/release/thanhphong28/BreezeIsland?style=for-the-badge&label=Download%20Latest%20APK&color=FF2A4B" alt="Download Latest APK">
  </a>
</p>

---

## ⬇ Download & Install (Tải và Cài đặt)

### [👉 Download Latest APK (`BreezeIsland.apk`)](https://github.com/thanhphong28/BreezeIsland/releases/latest/download/BreezeIsland.apk)

Direct APK links from the [**Releases**](https://github.com/thanhphong28/BreezeIsland/releases) page:
- **Latest Release Page**: [`https://github.com/thanhphong28/BreezeIsland/releases/latest`](https://github.com/thanhphong28/BreezeIsland/releases/latest)
- **Direct APK Download**: [`https://github.com/thanhphong28/BreezeIsland/releases/latest/download/BreezeIsland.apk`](https://github.com/thanhphong28/BreezeIsland/releases/latest/download/BreezeIsland.apk)

### First-Time Setup on Samsung Galaxy Z Flip 5 (One UI 9)
1. Download `BreezeIsland.apk` on your phone and install it (you only need to install it manually **once**!).
2. Open **BreezeIsland**. You can switch the app language between **Tiếng Việt** and **English** at the top right or in **Settings → System → Language**.
3. Follow the guided onboarding steps:
   - Grant **Display over other apps** (*Hiển thị trên các ứng dụng khác*).
   - Grant **Notification access** (*Quyền truy cập thông báo*). *(On Android 13+, if greyed out, go to **Settings → Apps → BreezeIsland → ⋮ → Allow restricted settings** first).*
   - Grant **Modify system settings** (*Sửa đổi cài đặt hệ thống*) so BreezeIsland automatically hides redundant One UI 9 status bar notification icons (`simple_status_bar = 0`).
   - In **Settings → Lock screen & AOD → Now bar → Live notifications**, turn off **Media player** so One UI 9's duplicate `[▷ Song]` chip never overlaps BreezeIsland.
4. **Updating in the future (`Check for updates` / `Kiểm tra cập nhật`)**:
   - Whenever a new version is pushed to this repository, open **BreezeIsland → Settings → Check for updates (Kiểm tra cập nhật)** (or **About → Software update**).
   - Tap **Download & Install** to update directly inside the app in one tap while keeping all your settings!

---

## ✨ Key Highlights (Điểm nổi bật)

- **Built for the Samsung Galaxy Z Flip 5 (`SM-F731*` · One UI 9)**:
  - Pre-calibrated camera profile (`1080 × 2640`, `120Hz`, centered punch-hole cutout) with 1-px physical calibration and **Island Studio** live blueprint resizing.
- **Sits Cleanly on Top of One UI 9 Status Bar**:
  - Automatically suppresses duplicate One UI status bar notification icons via `WRITE_SETTINGS` (no PC required) and clears status bar ongoing chips, clock, and icons via `WRITE_SECURE_SETTINGS` when expanded.
  - Auto-expands into a sleek top card on the first track of a media session and automatically snoozes duplicate One UI heads-up banners.
- **100% Banking App Compatible (No Accessibility Service, No Root)**:
  - Uses `SYSTEM_ALERT_WINDOW` with `IMPORTANT_FOR_ACCESSIBILITY_NO` and `ACCESSIBILITY_LIVE_REGION_NONE` so banking, financial, and government apps never flag the overlay.
- **In-App OTA Updater (`Kiểm tra cập nhật`)**:
  - Connects directly to `github.com/thanhphong28/BreezeIsland/releases/latest` to check, download, and install updates right from Settings.
- **Full Bilingual UI (`Tiếng Việt` & `English`)**:
  - Switch between Vietnamese and English anytime in Settings; the entire app translates instantaneously.
- **Closed-Form Physical Spring Engine**:
  - Frame-rate independent damped springs (60Hz / 120Hz), interrupted transitions preserve velocity, metaball liquid split, squash & stretch, waveform-to-ring morph, and 6 themes (*Classic Black, Glass, Minimal, Samsung One UI, RGB Gaming, Custom*).

---

## 📱 100% On-Phone Permission Setup (Zero PC Required)

Everything in **BreezeIsland** is designed to be granted directly on your Galaxy Z Flip 5 without a computer:
1. Open **BreezeIsland → Đồng bộ One UI 9 (Blend with One UI)**.
2. Tap **Cấp quyền đè lên trên thanh trạng thái (1 chạm trên ĐT)** (*Grant top-most header control*) to grant `Modify system settings` (`WRITE_SETTINGS`) and/or 1-tap on-phone `Shizuku` (`WRITE_SECURE_SETTINGS`) directly on your phone screen.
3. BreezeIsland automatically places the Compact & Split Island inside the clean header corridor (budding the Split droplet to the left of the camera, away from One UI's right-side Work Profile / Mute / Wi-Fi cluster) and uses focus-synchronized `WindowInsetsController.hide(statusBars())` + `FLAG_ALT_FOCUSABLE_IM` to cover the entire header when expanded.

---

## 🏗 Build & Release Environment

### Local Build
```powershell
$env:JAVA_HOME = "C:\Program Files\Android\openjdk\jdk-21.0.8"
.\gradlew.bat testDebugUnitTest assembleDebug assembleRelease
```
- Output APK: `app/build/outputs/apk/release/app-release.apk` (signed with `app/breeze-release.jks`, applicationId `com.breeze.island`).

### Publishing a New Update to Your Phone via GitHub Releases
1. Bump `versionCode` and `versionName` (e.g. `1.3.0`) in `app/build.gradle.kts`.
2. Commit and push a tag (e.g. `git tag v1.3.0 && git push origin main --tags`), or run `gh release create v1.3.0 BreezeIsland.apk`.
3. On your Galaxy Z Flip 5, open **BreezeIsland → Kiểm tra cập nhật (Check for updates)** and tap **Tải & Cập nhật ngay**!

---

## 🔌 Local API for Your Own Apps

Copy [`client/IslandClient.kt`](client/IslandClient.kt) into your app and add to your `AndroidManifest.xml`:

```xml
<uses-permission android:name="com.breeze.island.permission.POST_ISLAND_ACTIVITY" />
<queries><package android:name="com.breeze.island" /></queries>
```

```kotlin
IslandClient.show(
    context,
    id = "build",
    title = "Building…",
    subtitle = "assembleRelease",
    icon = "DOWNLOAD",
    progress = 0.4f,
)
```

---

## 📄 License

Proprietary & custom-built for **Nguyễn Thanh Phong (`thanhphong28`)** — **BreezeIsland** for Samsung Galaxy Z Flip 5 (One UI 9).
