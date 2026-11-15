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
| 2:30–3:00 | Nêu phạm vi thử nghiệm: hai tiến trình trao đổi gói IPv4 mẫu. Chuyển sang [demo TUN trên Ubuntu](demo-tun-linux.md) để quan sát ping và web qua IP ảo. |

**Khi trình bày lại:** mở `trusted-client.log` để xem bộ mã và thời gian khứ hồi, rồi đối chiếu `trusted-server.log` với `missing-cert-server.log`. Hai dòng `READY port=...` có thể hiện hai cổng khác nhau vì hệ điều hành cấp cổng cho từng phiên.

**Câu hỏi bảo vệ:** Nếu Client tin đúng chứng chỉ Server thì tại sao phiên thứ hai vẫn thất bại? Trong mTLS, Server cũng yêu cầu bằng chứng về danh tính Client. Client thiếu khóa và chứng chỉ để hoàn tất phần xác thực đó.

## Quan sát hai phiên trong Wireshark

1. Trước khi chạy demo, mở Wireshark và bắt gói trên giao diện loopback. Trên Linux chọn `lo`. Trên Windows có Npcap, chọn giao diện bắt lưu lượng loopback. Cả Client và Server dùng địa chỉ `127.0.0.1`.
2. Chạy `bash scripts/run-demo.sh`. Khi thấy cả hai dòng `PASS demo`, dừng bắt gói. Terminal in một dòng `Wireshark (trusted)` và một dòng `Wireshark (missing-cert)`. Sao chép biểu thức bắt đầu từ `ip.addr` của từng dòng vào ô **display filter** phía trên danh sách gói. Cổng được cấp khi Server bắt đầu lắng nghe nên hãy dùng giá trị của chính lần chạy vừa bắt.
3. Chọn phiên `trusted`. Nếu cột Protocol chỉ hiện TCP, chọn một gói trong phiên, mở **Analyze → Decode As…**, chọn cổng TCP của Server và giao thức TLS. Xem Client Hello rồi Server Hello. Trong Server Hello, mở **Supported Versions** để xem TLS 1.3. Đối chiếu bộ mã với dòng `TLS TLSv1.3` trong `trusted-client.log`. [Hướng dẫn Decode As của Wireshark](https://www.wireshark.org/docs/wsug_html_chunked/ChCustProtocolDissectionSection.html)
4. Với bộ lọc của phiên `trusted`, thêm `&& tls.app_data` để xem các bản ghi được mã hóa. Client kiểm tra dữ liệu **sau khi giải mã** và in ba dòng `PASS packet=`. Số bản ghi trên mạng có thể khác ba vì TLS và TCP chia dữ liệu theo ranh giới riêng. [Các trường lọc TLS trong Wireshark](https://www.wireshark.org/docs/dfref/t/tls.html)
5. Dán lại biểu thức cho phiên `missing-cert`. Nếu cần, thực hiện Decode As cho cổng này. So sánh thời điểm kết nối kết thúc với lỗi `Empty client certificate chain` trong `missing-cert-server.log`. Log Server cho biết nguyên nhân từ chối; phần bắt tay sau Server Hello của TLS 1.3 đã được mã hóa.

**Minh họa trên slide.** Dùng ảnh Server Hello ở phiên thành công kèm dòng log xác nhận ba gói, rồi dùng dòng lỗi từ phiên thiếu chứng chỉ. Ghi rõ đây là lưu lượng loopback của demo gói mẫu. Khi nhóm ghép TUN và chạy hai máy, chọn giao diện mạng nối hai máy để quan sát đường hầm bên ngoài.
