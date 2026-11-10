# BreezeIsland v1.7.6 Release Notes

### Sửa triệt để 2 lỗi cốt lõi trên Samsung One UI (Galaxy Z Flip5):
1. **Khắc phục lỗi "bật lại thanh âm lượng không thấy thanh âm lượng"**:
   - Sửa lỗi hình học Bezier Path trong `EdgeBezelShape.kt`: Giới hạn đường cong `maxCurve = tabWidth * 0.45f` để đường bo góc và góc lượn hõm không bao giờ tự cắt hoặc lộn ngược vào trong, loại bỏ hoàn toàn hiện tượng canvas Skia bị cắt cụt pixel về 0.
   - Chuẩn hóa kích thước que mỏng (Stick Mode): Đặt `tabWidth = 32dp`, `sliderW = 10dp` nằm cân đối chính giữa với khoảng đệm `11dp`. Thanh que hiển thị sắc nét, thanh lịch và không bao giờ bị lỗi đồ họa.
   - Kết nối trực tiếp Binder Callback từ `AudioService`: `VolumeSuppressor.onVolumeChangedCallback` được kết nối tức thì vào `SoundIslandController`, nhận diện sự kiện phím âm lượng vật lý với độ trễ 0ms ngay cả khi mức âm lượng đang ở Min (0%) hoặc Max (100%).

2. **Khắc phục lỗi "tắt đi cũng không thấy thanh âm lượng gốc của Samsung"**:
   - Khôi phục an toàn qua SystemUI Watchdog: Sử dụng lệnh chuẩn xác `am crash com.android.systemui; am force-stop com.android.systemui` qua Shizuku để Android tự động tái khởi động `com.android.systemui` trong 0.3s, tự động đăng ký lại `VolumeDialog` mặc định của Samsung.
   - Tự động hoàn trả khi tắt Breeze Sound Island: Khi người dùng gạt tắt công tắc Breeze Sound Island, hệ thống tự động kiểm tra và hoàn trả lại thanh âm lượng mặc định của Samsung nếu trước đó đang bật chế độ ẩn.
   - Bổ sung nút bấm trực tiếp: *"Khôi phục thanh âm lượng gốc Samsung ngay lập tức"* ngay trong màn hình cài đặt để người dùng có thể chủ động khôi phục bất cứ lúc nào.
   - Nhắc nhở an toàn: Nếu tắt Shizuku, chỉ cần khởi động lại máy là thanh Samsung sẽ tự có lại 100% vì cơ chế can thiệp hoàn toàn chạy trên RAM, không sửa file hệ thống.
