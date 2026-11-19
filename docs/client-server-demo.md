# VPN Client, Server và demo chung

Bài trình bày dùng [bộ 15 slide](slides/VPN-Nhom-Hoan-Chinh.pptx). Nội dung bám ba phần phân công ban đầu. Phần lõi đã có đóng khung IPv4 và mTLS. Client/Server cùng dùng lớp TunVpn, chọn vai trò bằng đối số đầu tiên.

## Đối chiếu sản phẩm theo phân công

| Phần | Mã và tài liệu bàn giao | Kiểm chứng |
| --- | --- | --- |
| Client và TUN | TunVpn chế độ client, LinuxTunDevice, helper tun-io.py | Đọc gói từ vpn0, chuyển qua TLS và ghi gói phản hồi về TUN |
| IP ảo và định tuyến | test-linux-tun.sh tạo namespace, vpn0, địa chỉ và MTU | Route tới 10.8.0.1 chọn vpn0 |
| Server | TunVpn chế độ server và TlsEndpoints | Chờ mTLS trên 192.0.2.1:8443 và yêu cầu chứng chỉ Client |
| Web nội bộ | Python HTTP Server trong namespace Server | Client tải đúng trang tại 10.8.0.1:8000 |
| Kiểm thử và Wireshark | Tình huống bật/ngắt VPN, PCAP lớp trong và lớp ngoài | Ping 3/3, HTTP thành công khi kết nối, thất bại sau ngắt |
| Slide | Trang 2–5 về nguyên lý, 10–11 về Client/Server, 12–15 về thực nghiệm | Có lời thuyết trình trong speaker notes |

## Chuẩn bị môi trường

Phần mTLS gói mẫu chạy trên Windows theo [hướng dẫn hiện có](demo-mtls.md).

Phần TUN chạy trên Ubuntu có quyền sudo, JDK 17+, Python 3, iproute2, iputils-ping, curl và tcpdump. Trên máy Ubuntu mới, có thể cài các công cụ bằng lệnh sau.

```bash
sudo apt-get update
sudo apt-get install -y openjdk-17-jdk python3 iproute2 iputils-ping curl tcpdump
```

Từ thư mục repo, kiểm tra thiết bị TUN rồi chạy cả lab và bắt gói.

```bash
test -c /dev/net/tun
sudo env VPN_CAPTURE=1 PATH="$PATH" bash scripts/test-linux-tun.sh
```

Lab tạo hai network namespace trên cùng máy Ubuntu. Mỗi namespace có bảng định tuyến riêng. Script dựng Server trước, sau đó Client, thực hiện kiểm tra và tự dọn tiến trình cùng namespace khi thoát.

## Phía Client

Luồng cấu hình trong test-linux-tun.sh gồm tạo vpn0, đặt IP 10.8.0.2/30 và MTU 1400. Khi thêm IP /30, Linux tạo route kết nối trực tiếp tới 10.8.0.0/30 qua vpn0. Gói tới Server 10.8.0.1 vì thế vào TUN. Địa chỉ 192.0.2.1 dùng để kết nối TLS thuộc mạng wire0 riêng.

Lệnh Java thực tế bên trong namespace Client có dạng dưới đây. Các biến namespace và certs do script tạo cho lần chạy, đồng thời truyền VPN_STORE_PASSWORD qua môi trường.

```bash
ip netns exec "$client" java -cp build/classes vpn.demo.TunVpn   client 192.0.2.1 8443 vpn0 "$certs/client.p12" "$certs/trust-server.p12"
```

Trong TunVpn, mutualContext nạp danh tính và kho tin cậy. LinuxTunDevice khởi động helper Python để mở TUN. TlsEndpoints.connect kiểm tra chứng chỉ Server khớp địa chỉ 192.0.2.1. TunnelBridge bắt đầu hai chiều truyền. Gói nhận về được ghi vào TUN để Linux chuyển tới ứng dụng Client.

