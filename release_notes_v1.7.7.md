# BreezeIsland v1.7.7 Release Notes

### Cải tiến & Tối ưu hóa toàn diện Breeze Sound Island theo yêu cầu:

1. **Hiển thị mượt mà & Sửa lỗi mức âm lượng không nhảy**:
   - Khởi tạo và đồng bộ hóa lò xo `volumeSpring`: Khi thanh xuất hiện lần đầu, mức âm lượng được cố định chính xác ngay lập tức (không bị nhảy từ vị trí cũ). Khi bấm phím Volume Up/Down liên tiếp, thanh màu trắng bên trong lướt lên / lướt xuống êm ái như chất lỏng (Liquid Spring leveling).
   - Tối ưu hóa `slideSpring`: Chuyển động trượt ra từ mép màn hình đầm chắc, mượt mà chuẩn trải nghiệm Liquid Glass.

2. **Chế độ Que Mỏng (Stick Mode) & Quy tắc Phóng to (Expand to Capsule)**:
   - Mặc định khi bấm phím âm lượng: Chỉ mở thanh que mỏng (Stick Mode) siêu hẹp và tinh tế (`32dp` notch, thanh trượt `10dp` nằm ở giữa).
   - **Chỉ phóng to thành Capsule khi**:
     - Bấm liên tiếp trên 3 lần 1 nút, hoặc
     - Nhấn GIỮ phím âm lượng tăng hoặc giảm, hoặc
     - Dùng tay chạm giữ vào thanh âm lượng khoảng ~150ms rồi vuốt cảm ứng.
   - Sau 1.2s không tương tác tiếp, thanh tự động thu nhỏ êm ái lại thành que mỏng trước khi ẩn.

3. **Chỉ giữ 2 chế độ: Âm lượng & Chuông (Xóa bỏ hoàn toàn Work Mode & DND)**:
   - Đã loại bỏ hoàn toàn chế độ Làm việc (Work Mode) và Không làm phiền (DND) theo đúng mong muốn của bạn.
   - Giao diện giờ đây chỉ tập trung 100% vào 2 chức năng: **Âm lượng (Volume)** và **Chuông (Ringer)**.

4. **Tách bạch hoàn toàn thao tác Chạm (Tap) và Chỉnh Âm lượng (Drag)**:
   - **Nhấp 1 lần vào thanh âm lượng**: Ngay lập tức mở chuyển sang phần Chuông, **TUYỆT ĐỐI không làm thay đổi âm lượng**.
   - **Chỉnh âm lượng**: Phải chạm giữ ~150ms rồi vuốt lên/xuống thì âm lượng mới thay đổi theo ngón tay (có rung phản hồi từng nấc).
   - **Nhấp vào phần Chuông**: Tự động chuyển đổi chế độ tiếp theo:
     - Chuông (Ring - icon Trắng sáng) ↔ Rung (Vibrate - icon Vàng cam có sóng rung) ↔ Tắt tiếng (Mute/Silent - icon Đỏ gạch chéo).
     - Bỏ toàn bộ chữ text rườm rà, icon có hiệu ứng nảy bật (Bouncy Pop Spring) cực kỳ sống động và sang trọng.
   - **Vuốt ngang vào trong**: Chuyển đổi qua lại giữa Âm lượng và Chuông. Vuốt ra ngoài mép: Thu gọn thanh ngay lập tức.
