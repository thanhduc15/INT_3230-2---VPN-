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

## Phía Server

Script đặt vpn0 của Server là 10.8.0.1/30. Lệnh Server bên trong namespace có dạng sau.

```bash
ip netns exec "$server" java -cp build/classes vpn.demo.TunVpn   server 192.0.2.1 8443 vpn0 "$certs/server.p12" "$certs/trust-client.p12"
```

Server in READY khi đã mở listener, rồi chờ một Client. Tham số true của TlsEndpoints.listen yêu cầu chứng chỉ Client. Chỉ sau bắt tay thành công, TunnelBridge mới chuyển gói qua TLS và TUN. Chương trình hiện phục vụ một phiên điểm-điểm.

Dịch vụ nội bộ dùng lệnh dưới đây. Trang mẫu chứa mã riêng cho từng lần chạy, để script đối chiếu đúng nội dung nhận về.

```bash
ip netns exec "$server" python3 -m http.server 8000   --bind 10.8.0.1 --directory "$run/www"
```

## Thứ tự demo trực tiếp trong 4 phút

| Thời gian | Thao tác | Lời dẫn |
| --- | --- | --- |
| 0:00–0:45 | Mở slide 12 | “Hai đầu chạy trên cùng Ubuntu với hai bảng định tuyến riêng. Đường hầm dùng IP wire0, còn dịch vụ nội bộ dùng IP vpn0.” |
| 0:45–1:30 | Chạy lab và chỉ dòng route | “Đích 10.8.0.1 đi qua dev vpn0. Linux đưa gói cho helper và Java, Java chuyển qua mTLS.” |
| 1:30–2:00 | Chỉ thống kê ping và PASS HTTP | “Ba gói ping có phản hồi. Script còn so nội dung HTTP với mã riêng của lần chạy.” |
| 2:00–2:30 | Chỉ PASS sau ngắt | “Script dừng VPN Client, kiểm tra HTTP Server vẫn sống, rồi tải lại trang. Lần tải này thất bại.” |
| 2:30–4:00 | Mở hai PCAP trong Wireshark | “Bản bắt vpn0 thấy gói IP và trang HTTP. Bản bắt wire0 thấy kết nối TLS tới 8443.” |

## Xem và lưu kết quả Wireshark

Dòng đầu của lab in đường dẫn dạng build/tun.XXXXXX. Mở inner-ip.pcap và outer-tls.pcap trong thư mục đó. Nếu file do sudo tạo bị hạn chế quyền đọc, sao chép riêng hai PCAP sang thư mục người dùng rồi đổi chủ sở hữu các bản sao bằng chown.

1. Mở inner-ip.pcap, nhập display filter `icmp`. Xem ba Echo Request và ba Echo Reply giữa 10.8.0.2 và 10.8.0.1.
2. Đổi filter thành `tcp.port == 8000`. Chọn gói TCP, dùng Follow → TCP Stream để xem yêu cầu HTTP và phần trả lời chứa VPN-LAB-. Nếu cần giải mã giao thức, dùng Analyze → Decode As → HTTP cho cổng 8000.
3. Mở outer-tls.pcap, dùng filter `tcp.port == 8443`. Nếu Wireshark chưa nhận diện TLS, dùng Analyze → Decode As → TLS cho cổng 8443.
4. Xem Client Hello, Server Hello và các bản ghi TLS. Có thể thêm `&& tls.app_data` vào bộ lọc để xem dữ liệu được bảo vệ.
5. Chụp màn hình danh sách ICMP, TCP Stream HTTP bên trong và danh sách TLS bên ngoài. Giữ tên PCAP và bộ lọc trong ảnh để người nghe biết đang quan sát lớp nào.

Số bản ghi TLS có thể khác số gói IP vì TCP/TLS có ranh giới dữ liệu riêng. PCAP bên trong ghi nhận nội dung ở đầu cuối VPN. Việc tìm thấy nội dung rõ tại TUN là kết quả của việc quan sát gói trước hoặc sau lớp bảo vệ.

## Bằng chứng đã lưu

Lần thực nghiệm mới chạy thành công trên Ubuntu của GitHub Actions tại commit 661bd56488b3ea1540f6e9aaa3a901f480aeb969.

| Kiểm tra | Kết quả lần chạy |
| --- | --- |
| Route Client | 10.8.0.1 dev vpn0 src 10.8.0.2 |
| Ping | Gửi 3, nhận 3, mất gói 0% |
| HTTP qua VPN | PASS |
| HTTP sau ngắt VPN | PASS cho tình huống mất truy cập |
| inner-ip.pcap | 16 gói tổng cộng, gồm 6 gói ICMP và HTTP cổng 8000 |
| outer-tls.pcap | 46 gói Ethernet, TCP cổng 8443, không thấy chuỗi nội dung VPN-LAB- |
| Bộ mã trong log | TLSv1.3 TLS_AES_256_GCM_SHA384 |

Các con số PCAP thuộc đúng lần chạy này, các lần khác có thể có số gói khác. Kiểm tra nội dung PCAP được thực hiện bằng bộ đọc file pcap. Ảnh Wireshark có thể được lấy theo hướng dẫn trên khi trình chiếu.

[Log trên GitHub](https://github.com/thanhduc15/INT_3230-2---VPN-/actions/runs/44498966856/job/135473363772)

[Bản lưu PCAP và log trong repo](evidence/2026-11-19/)

Workflow lưu artifact vpn-demo-evidence trong 14 ngày. Thư mục evidence giữ bản sao phục vụ trình bày lâu dài. Nếu chạy lại trên GitHub, vào Actions → Linux TUN integration → Run workflow khi workflow đã có ở nhánh mặc định, chọn nhánh rồi chạy. Mỗi lần thay mã thuộc phạm vi workflow trên nhánh hiện tại hoặc main cũng tự kích hoạt kiểm thử.

Khi script chủ động ngắt Client, log Server có thể kết thúc bằng SocketException/Connection reset. Đối chiếu thời điểm ngắt và dòng PASS sau đó để giải thích kết quả.

## Tổng duyệt và phạm vi

- Mở bộ 15 slide và giữ terminal, Wireshark ở cửa sổ riêng.
- Thử trước lệnh Windows cho tình huống chứng chỉ và lệnh Ubuntu cho TUN.
- Nếu trình chiếu từ Windows chưa có Ubuntu, dùng PCAP và log đã lưu để giải thích kết quả thực nghiệm. Ghi rõ thời điểm và môi trường chạy.
- Nếu cần video dự phòng, quay màn hình một lần chạy trên Ubuntu cùng lúc giải thích route, ping và HTTP. PCAP/log đã lưu có thể dùng để quay phần phân tích Wireshark.
- Điền tên thành viên theo yêu cầu nhóm khi chốt slide bìa. Bản hiện tại chỉ ghi Mai Thành Đức theo thông tin đã có.

Phạm vi đã kiểm chứng là IPv4, một phiên điểm-điểm, hai namespace trên cùng Ubuntu và dịch vụ nằm ngay tại đầu Server. Java xử lý lõi đường hầm, Python hỗ trợ TUN và HTTP mẫu. Khả năng nhiều Client, IPv6, định tuyến tới LAN phía sau Server, NAT Internet và tự kết nối lại cần được phát triển riêng.
