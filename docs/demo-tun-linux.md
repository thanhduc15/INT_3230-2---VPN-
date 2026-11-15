# Ping và HTTP qua VPN trên Ubuntu

Từ thư mục gốc của repo, chạy trên máy ảo Ubuntu với Bash, JDK 17+, Python 3, iproute2, iputils-ping, curl và thiết bị `/dev/net/tun`. Lệnh cần quyền quản trị để tạo hai network namespace. Các địa chỉ và route được cấu hình trong hai namespace tạm.

```bash
sudo env PATH="$PATH" bash scripts/test-linux-tun.sh
```

| Thành phần | Client | Server |
| --- | --- | --- |
| Địa chỉ truyền TLS trên `wire0` | 192.0.2.2 | 192.0.2.1, cổng 8443 |
| Địa chỉ IPv4 trên `vpn0` | 10.8.0.2 | 10.8.0.1 |
| HTTP nội bộ | curl | 10.8.0.1:8000 |

**Đường đi của gói ping.** Kernel của Client chọn route qua `vpn0`. Helper Python mở TUN bằng `TUNSETIFF`, nhận gói IPv4 rồi chuyển sang Java qua pipe. Java thực hiện đóng khung, xác thực mTLS và truyền gói qua TCP. Ở Server, Java giải khung rồi helper ghi gói vào TUN để kernel xử lý. Hai IP mạng ngoài thuộc một cặp veth; mỗi namespace có bảng định tuyến riêng.

**Kiểm chứng khi bảo vệ.** Xem kết quả `ip route get` để xác nhận đích đi qua `vpn0`. Xem thống kê ping và hai dòng PASS của HTTP. Lần tải đầu kiểm tra đúng nội dung của web Server. Lần sau ngắt tiến trình VPN Client trong khi HTTP Server tiếp tục chạy. Script báo lỗi nếu trang vẫn truy cập được.

**Chẩn đoán.** Đường dẫn log được in ở đầu lần chạy. Xem `server.log`, `client.log` và `http.log` trong thư mục đó. Nếu máy báo `Operation not permitted` khi tạo namespace hoặc thiếu `/dev/net/tun`, kiểm tra quyền và hỗ trợ TUN trên máy Linux. Script dọn tiến trình và namespace khi thoát. Demo dùng IPv4 với MTU 1400; helper dùng ABI TUN của Linux x86-64 hoặc arm64. [Tài liệu TUN/TAP của Linux](https://docs.kernel.org/networking/tuntap.html)
