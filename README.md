# Lõi VPN Java

Phần phụ trách của **Mai Thành Đức**. Mô-đun thiết lập đường hầm TLS, kiểm tra danh tính máy chủ, phân khung gói IPv4 và chuyển gói hai chiều. Client và Server dùng chung mô-đun này. Mã dùng JDK 17 trở lên và chỉ cần thư viện chuẩn Java.

## Kiểm thử lõi

Chạy trong thư mục dự án bằng Bash trên Linux hoặc Git Bash trên Windows. Cần có `java`, `javac` và `keytool` trên PATH.

```bash
bash scripts/smoke-test.sh
```

Script tạo chứng chỉ riêng cho mỗi lần chạy và dựng kết nối TCP qua loopback. Hai thiết bị giả lập trong bộ nhớ thay cho TUN. Kiểm thử đối chiếu từng byte của các gói truyền theo hai chiều, kiểm tra phiên tự đóng khi một đầu kết thúc, thử chứng chỉ không đáng tin cậy và sai danh tính, rồi kiểm tra khung quá lớn, bị cắt hoặc có header IP sai. Thư mục `build/` chứa các tệp sinh ra trong quá trình kiểm thử.

## Thỏa thuận giữa Client và Server

| Mục | Giá trị |
| --- | --- |
| Kênh truyền | TCP, TLS 1.3, ALPN `java-vpn/1` |
| Định dạng khung bên trong TLS | `[độ dài: 2 byte, unsigned, big-endian][gói IPv4: đúng số byte đã khai báo]` |
| Kích thước gói IPv4 | 20–1400 byte. Dùng `TlsTunnel.MTU` để cấu hình TUN. |
| Kiểm tra đầu nhận | Độ dài nằm trong giới hạn, version là IPv4, IHL hợp lệ và trường Total Length bằng độ dài khung |
| Chấm dứt phiên | EOF ở ranh giới khung kết thúc phiên. EOF giữa khung hoặc khung sai gây lỗi và đóng phiên qua `TunnelBridge`. |

TLS cung cấp luồng byte nên một lần đọc có thể lấy một phần gói hoặc dữ liệu thuộc nhiều gói. Trường độ dài giúp đọc đúng một gói mỗi lần. TLS xác thực máy chủ và bảo vệ dữ liệu trên đường truyền. Cơ chế kiểm tra tên của JSSE dùng thuật toán nhận diện đầu cuối `HTTPS` để đối chiếu tên DNS hoặc IP với chứng chỉ, dù đường hầm không chạy HTTP. [Tài liệu Java về SSLParameters](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/javax/net/ssl/SSLParameters.html)

## Tích hợp với TUN

Mỗi đầu triển khai `vpn.core.PacketDevice` cho thiết bị Linux TUN của mình. Cấu hình `IFF_TUN | IFF_NO_PI` để đọc trực tiếp gói IP, không kèm header PI 4 byte của TUN. Đặt MTU hai đầu là 1400. Với demo IPv4, chỉ đưa gói IPv4 vào mô-đun. Tách địa chỉ mạng thật dùng để nối TLS khỏi địa chỉ IP ảo trong VPN.

- `readPacket()` đọc đúng một gói, trả mảng byte đúng độ dài thực tế hoặc `null` khi thiết bị kết thúc.
- `writePacket(packet)` ghi cả gói trong một thao tác ghi vào TUN.
- Adapter cho phép đọc và ghi đồng thời. `close()` cần đánh thức thao tác đang chờ, kể cả khi TUN không có gói. Hai bạn phụ trách TUN nên kiểm tra yêu cầu này trên Linux, chẳng hạn dùng cơ chế poll có đường báo dừng trong lớp native.
- `TunnelBridge.run(tunnel, device)` chặn đến khi phiên kết thúc, sau đó đóng cả tunnel và device. Tạo device mới cho phiên kế tiếp nếu bổ sung kết nối lại.

Phía Client, truyền địa chỉ mạng thật của máy chủ. Chứng chỉ phải chứa SAN khớp địa chỉ này.

```java
var context = TlsEndpoints.clientContext(Path.of("certs/trusted.p12"), password);
try (var tunnel = TlsEndpoints.connect(context, serverHost, 8443)) {
    System.out.println(tunnel.protocol() + " " + tunnel.cipherSuite());
    TunnelBridge.run(tunnel, clientTun);
}
```

Phía Server, đặt địa chỉ lắng nghe là IP mạng lab của máy chủ.

```java
var context = TlsEndpoints.serverContext(Path.of("certs/server.p12"), password);
try (var listener = TlsEndpoints.listen(context, new InetSocketAddress(labIp, 8443));
     var tunnel = listener.accept()) {
    TunnelBridge.run(tunnel, serverTun);
}
```

Các đoạn tích hợp dùng `java.nio.file.Path`, `java.net.InetSocketAddress` và các lớp trong `vpn.core`. Biến `password` có kiểu `char[]`. Với cách kết nối mặc định, Client dùng truststore và Server giữ keystore chứa khóa riêng. Chế độ mTLS bên dưới bổ sung keystore riêng cho Client. Adapter phải được đóng nếu kết nối thất bại trước khi bàn giao thiết bị cho bridge.

## Tạo chứng chỉ cho demo trên hai máy

Ví dụ VPN Server có IP mạng thật `192.168.56.10`. Thay giá trị SAN bằng IP thật của Server trong lab, rồi dùng chính IP đó khi gọi `connect`. `keytool` sẽ hỏi mật khẩu cho mỗi kho.

```bash
mkdir -p certs
keytool -genkeypair -alias vpn-server -keyalg EC -groupname secp256r1 \
  -dname "CN=VPN Lab" -ext "SAN=ip:192.168.56.10" -validity 30 \
  -storetype PKCS12 -keystore certs/server.p12
keytool -exportcert -alias vpn-server -keystore certs/server.p12 \
  -file certs/server.cer
keytool -importcert -alias vpn-server -file certs/server.cer \
  -storetype PKCS12 -keystore certs/trusted.p12
```

Chép `trusted.p12` sang Client qua kênh của nhóm sau khi xác nhận chứng chỉ đúng. Server giữ `server.p12`. Cấu hình này xác thực máy chủ, còn máy khách chưa có danh tính được server xác thực. Dùng mạng lab riêng cho bản demo.

## Phần trình bày của Đức

- **Kiến trúc:** TUN phía Client → phân khung IPv4 → TLS/TCP → giải khung → TUN phía Server. Chiều về chạy đồng thời.
- **Bảo mật:** Trình bày vai trò của keystore, truststore và SAN. Dùng log để chỉ ra TLS 1.3 và bộ mã được thương lượng trong phiên.
- **Demo sau khi ghép TUN:** Truy cập dịch vụ web qua IP ảo của máy chủ. So sánh gói trên TUN với lưu lượng cổng 8443 trên mạng thật. Đổi sang truststore chứa chứng chỉ khác và chạy lại để minh họa kết nối bị từ chối.

## Chạy demo đường hầm ở hai tiến trình

Demo này tạo các gói IPv4 mẫu, gửi qua TLS và nhận lại nguyên gói do Server phản hồi. Kết quả cho biết đường hầm hoạt động và các byte được giữ nguyên. Demo ping và truy cập web qua IP ảo nằm ở bước ghép TUN trên Linux.

Chạy các lệnh từ thư mục gốc repo. Chuẩn bị JDK 17 trở lên, Bash và hai kho chứng chỉ theo mục tạo chứng chỉ ở trên. Với demo cùng máy, dùng SAN là `ip:127.0.0.1` khi tạo chứng chỉ. Khi chạy trên hai máy, SAN chứa IP mạng thật của Server và Client kết nối bằng IP đó.

Biên dịch các lớp chính.

```bash
bash scripts/build.sh
```

Mở terminal thứ nhất, đặt mật khẩu keystore và khởi động Server. Thay giá trị mật khẩu minh họa bằng mật khẩu đã dùng khi tạo kho. Server in `READY port=8443` sau khi lắng nghe thành công.

```bash
export VPN_STORE_PASSWORD='mat-khau-keystore'
java -cp build/classes vpn.demo.TunnelDemo server 127.0.0.1 8443 certs/server.p12
```

Mở terminal thứ hai, đặt mật khẩu truststore và chạy Client.

```bash
export VPN_STORE_PASSWORD='mat-khau-truststore'
java -cp build/classes vpn.demo.TunnelDemo client 127.0.0.1 8443 certs/trusted.p12
```

Trong PowerShell, dùng `$env:VPN_STORE_PASSWORD = 'mat-khau-cua-kho'` để đặt biến môi trường, rồi chạy cùng lệnh `java`. Chạy bước build bằng Git Bash.

Client in phiên bản TLS, bộ mã và ba dòng `PASS packet=...`. Server in ba dòng `ECHO`, sau đó `DONE packets=3` và kết thúc. Mỗi lần thử lại cần khởi động lại Server. Với hai máy, thay `127.0.0.1` trong cả hai lệnh bằng IP thật của Server.

Client chờ tối đa 5 giây cho mỗi thao tác đọc socket. Server chờ tối đa 10 giây khi đọc sau bắt tay TLS. Nếu hết thời gian chờ, demo đóng phiên và trả mã lỗi 1. Khi một phần khung đã được đọc, chương trình gọi thư viện cần đóng phiên sau lỗi timeout để tránh lệch ranh giới khung.

## Kiểm thử bổ sung

Lệnh `bash scripts/smoke-test.sh` chạy cả `CoreSmoke` và `PacketFramesTest`. Các tình huống mới gồm đọc từ peer im lặng với timeout, đọc gói từ luồng chỉ trả một byte mỗi lần, nhiều khung nối tiếp, header IPv4 có options, giới hạn MTU, độ dài và header sai. Bài kiểm tra bên gửi xác nhận gói sai bị từ chối trước khi ghi byte nào vào luồng.

Để kiểm tra chứng chỉ thủ công, dùng truststore chứa chứng chỉ khác rồi chạy lại Client. Với chứng chỉ chỉ có SAN `ip:127.0.0.1`, kết nối bằng `localhost` sẽ thử tình huống sai danh tính. Client phải báo lỗi và kết thúc với mã 1. Quan sát cổng TCP 8443 trên Wireshark để xem phiên TLS khi trình diễn.

## Xác thực hai chiều bằng chứng chỉ

Khi bật mTLS, Server yêu cầu Client trình chứng chỉ và kiểm tra chứng chỉ đó bằng kho tin cậy của Server. Client tiếp tục kiểm tra chứng chỉ và SAN của Server như trước.

| Đầu kết nối | Kho khóa riêng | Kho chứng chỉ tin cậy |
| --- | --- | --- |
| Server | `server.p12` | `clients.p12` chứa chứng chỉ Client được phép kết nối |
| Client | `client.p12` | `trusted.p12` chứa chứng chỉ Server |

Trong lab này, mỗi truststore nhập trực tiếp chứng chỉ tự ký đã được nhóm xác nhận. Các file chứa khóa riêng được giữ ở máy sở hữu. Chỉ xuất file `.cer` để nhập vào truststore của đầu còn lại.

Tạo danh tính Client trên máy Client, sau đó chuyển file `client.cer` sang Server.

```bash
keytool -genkeypair -alias vpn-client -keyalg EC -groupname secp256r1 \
  -dname "CN=VPN Client Duc" -ext EKU=clientAuth -validity 30 \
  -storetype PKCS12 -keystore certs/client.p12
keytool -exportcert -alias vpn-client -keystore certs/client.p12 \
  -file certs/client.cer
```

Trên Server, nhập chứng chỉ Client đã xác nhận vào kho tin cậy.

```bash
keytool -importcert -alias vpn-client -file certs/client.cer \
  -storetype PKCS12 -keystore certs/clients.p12
```

Với demo dòng lệnh, đặt cùng mật khẩu cho hai kho được dùng ở mỗi tiến trình. Thư viện Java cho phép truyền mật khẩu riêng cho từng kho qua `mutualContext`. Mỗi terminal đặt `VPN_STORE_PASSWORD` theo hướng dẫn ở trên.

Chạy Server với tham số cuối là kho tin cậy dành cho Client.

```bash
java -cp build/classes vpn.demo.TunnelDemo server 127.0.0.1 8443 certs/server.p12 certs/clients.p12
```

Chạy Client với tham số cuối là kho khóa riêng của Client.

```bash
java -cp build/classes vpn.demo.TunnelDemo client 127.0.0.1 8443 certs/trusted.p12 certs/client.p12
```

Các ví dụ cùng máy yêu cầu chứng chỉ Server có SAN `ip:127.0.0.1`. Hai đầu dùng chứng chỉ hợp lệ sẽ in ba gói phản hồi như demo trước. Để thử từ chối Client, khởi động lại Server rồi chạy lệnh Client với bốn tham số để bỏ danh tính Client, hoặc đưa một keystore Client chưa được Server tin cậy vào tham số cuối. Demo báo lỗi và trả mã 1. Mỗi lần thử lại cần khởi động lại Server vì demo xử lý một phiên.

Khi ghép TUN, tạo context cho mỗi đầu bằng cùng API sau.

```java
var context = TlsEndpoints.mutualContext(
        keyStorePath, keyPassword, trustStorePath, trustPassword);
```

Server gọi `TlsEndpoints.listen(context, bindAddress, true)` để bắt buộc chứng chỉ Client. Client gọi `TlsEndpoints.connect(context, serverHost, port)` như trước. Việc cấu hình context và yêu cầu chứng chỉ Client là hai bước riêng, cần thực hiện cả hai ở Server.

Bộ kiểm thử `bash scripts/smoke-test.sh` bổ sung `MutualTlsTest` với ba kết quả gồm từ chối Client thiếu chứng chỉ, từ chối Client có chứng chỉ ngoài kho tin cậy, và truyền gói thành công với Client được tin cậy sau hai lần bắt tay thất bại.
