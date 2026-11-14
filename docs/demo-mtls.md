# Trình diễn mTLS trong ba phút

**Mai Thành Đức · Lõi VPN Java**

Trên Linux hoặc Git Bash ở Windows, chuẩn bị JDK 17 trở lên và mở terminal ở thư mục gốc repo. Kiểm tra ba lệnh `java -version`, `javac -version` và `keytool -help` trước buổi trình bày.

```bash
bash scripts/run-demo.sh
```

Script tạo chứng chỉ mới cho lần chạy, biên dịch dự án rồi mở hai tiến trình Java kết nối qua loopback. Dòng đầu in thư mục chứa log. Khóa riêng và mật khẩu chỉ dùng cho lần demo đó.

| Thời điểm | Thao tác và điều cần giải thích |
| --- | --- |
| 0:00–0:45 | Vẽ đường đi của một gói: Client → độ dài 2 byte + gói IPv4 → TLS 1.3 → Server. Hai byte đầu giúp Server đọc đúng số byte của một gói trong luồng TLS. |
| 0:45–1:45 | Chỉ dòng `TLS TLSv1.3` và tên bộ mã. Ở phiên `trusted`, hai đầu dùng chứng chỉ riêng. Dòng `DONE packets=3` cho biết Server đã phản hồi ba gói. Mỗi dòng `PASS packet=` cho biết Client đã so sánh toàn bộ gói nhận về với gói gửi đi. |
| 1:45–2:30 | Ở phiên `missing-cert`, Client vẫn tin đúng Server nhưng không gửi chứng chỉ của mình. Chỉ lỗi bắt tay trong log Server và giải thích vì sao không có dòng `ECHO` ở phiên này. Dòng `PASS demo: missing-cert` xác nhận script quan sát được kết quả từ chối mong đợi. |
| 2:30–3:00 | Nêu phạm vi thử nghiệm: hai tiến trình trao đổi gói IPv4 mẫu. Để trình diễn ping hoặc web qua IP ảo, nhóm ghép `PacketDevice` với TUN trên Linux. |

**Khi trình bày lại:** mở `trusted-client.log` để xem bộ mã và thời gian khứ hồi, rồi đối chiếu `trusted-server.log` với `missing-cert-server.log`. Hai dòng `READY port=...` có thể hiện hai cổng khác nhau vì hệ điều hành cấp cổng cho từng phiên.

**Câu hỏi bảo vệ:** Nếu Client tin đúng chứng chỉ Server thì tại sao phiên thứ hai vẫn thất bại? Trong mTLS, Server cũng yêu cầu bằng chứng về danh tính Client. Client thiếu khóa và chứng chỉ để hoàn tất phần xác thực đó.
