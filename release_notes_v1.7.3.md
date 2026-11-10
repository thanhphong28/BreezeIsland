### 🚀 Bản cập nhật v1.7.3: Tích hợp AIDL Stub & HiddenApiBypass triệt tiêu 100% thanh âm lượng Samsung One UI

> [!IMPORTANT]
> **Khắc phục lỗi thanh âm lượng Samsung vẫn hiện ở bản v1.7.2:**
> Ở phiên bản trước, cơ chế bảo vệ Hidden API của Android 14/15 và thiếu lớp Stub AIDL biên dịch thực tế khiến lệnh ngắt kết nối SystemUI bị hệ điều hành chặn lại trong nền.
> **Bản v1.7.3 đã tích hợp đầy đủ:**
> 1. Thư viện **`HiddenApiBypass`** mở khóa toàn bộ API ẩn cho ứng dụng mà không cần root.
> 2. Biên dịch trực tiếp file **`IVolumeController.aidl`** thành lớp Stub chuẩn của hệ thống Android.
> 3. Tự động cấp quyền Shizuku trực tiếp qua popup 1 chạm trên màn hình.
> 4. Thông báo Toast trực tiếp trên màn hình báo trạng thái: *"Đã ẩn thành công thanh âm lượng mặc định Samsung!"*.

---

### 📦 Tải về bản cài đặt:
- **`BreezeIsland.apk`** (Phiên bản cài đặt duy nhất cho tất cả thiết bị Samsung / Android 10 - 15)
