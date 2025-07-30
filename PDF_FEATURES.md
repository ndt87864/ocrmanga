# Hướng dẫn sử dụng tính năng PDF

## Tính năng mới được thêm vào

### 1. Xuất phòng thành PDF
- **Mô tả**: Xuất tất cả ảnh trong một phòng cùng với các bản dịch được ghi đè lên thành một file PDF duy nhất
- **Cách sử dụng**: 
  1. Vào màn hình Gallery (Thư viện)
  2. Tìm phòng muốn xuất
  3. Nhấn nút "PDF" trên card của phòng
  4. File PDF sẽ được lưu vào thư mục `Android/data/com.example.ocrmanga/files/PDFs/`
- **Định dạng file**: `TênPhòng_YYYYMMDD_HHMMSS.pdf`
- **Chất lượng**: Giữ nguyên độ phân giải ảnh gốc với bản dịch được ghi đè chất lượng cao

### 2. Import file PDF
- **Mô tả**: Chọn file PDF từ thiết bị, ứng dụng sẽ tự động trích xuất từng trang thành ảnh riêng biệt
- **Cách sử dụng**:
  1. Vào màn hình Gallery (Thư viện)  
  2. Nhấn nút "+" (Tạo mới)
  3. Chọn "Chọn file PDF"
  4. Chọn file PDF từ trình duyệt file
  5. Ứng dụng sẽ trích xuất các trang và chuyển sang màn hình Viewer
- **Chất lượng**: Trang PDF được render ở 150 DPI (chất lượng cao)
- **Hỗ trợ**: Tất cả định dạng PDF tiêu chuẩn

### 3. Xử lý PDF như ảnh thông thường  
- Sau khi import PDF, các trang được xử lý như ảnh bình thường
- Có thể thực hiện OCR, dịch thuật, chỉnh sửa
- Có thể lưu thành phòng như với ảnh thường

## Cải tiến kỹ thuật

### Thư viện được sử dụng
- **iText7**: Tạo file PDF với chất lượng cao
- **PDFBox-Android**: Đọc và render PDF trên Android
- **Android PDF APIs**: Xử lý tài liệu PDF native

### Xử lý lỗi
- Kiểm tra tính hợp lệ của file PDF
- Xử lý gracefully khi file PDF bị lỗi
- Hiển thị thông báo lỗi rõ ràng cho người dùng
- Tự động dọn dẹp tài nguyên

### Tối ưu hóa
- Render PDF ở DPI phù hợp (150 DPI)
- Nén ảnh JPEG chất lượng 85% để tiết kiệm dung lượng  
- Tự động chia nhỏ text dài thành nhiều dòng
- Xử lý đa luồng (background threads)

## Quyền cần thiết
- `READ_EXTERNAL_STORAGE`: Đọc file PDF từ thiết bị
- `WRITE_EXTERNAL_STORAGE`: Lưu file PDF xuất ra (Android ≤ 9)
- File Provider được cấu hình để chia sẻ file PDF an toàn

## Hạn chế hiện tại
- PDF có mã hóa/bảo mật có thể không import được
- PDF với font đặc biệt có thể render không chính xác  
- Kích thước file PDF lớn có thể làm chậm quá trình xử lý

## Lưu ý khi sử dụng
- Đảm bảo đủ dung lượng trống trước khi xuất PDF
- File PDF xuất sẽ có kích thước lớn hơn ảnh gốc do chứa cả ảnh và text overlay
- Quá trình import PDF có thể mất vài giây với file lớn