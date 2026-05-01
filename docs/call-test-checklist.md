# Test Checklist – Cuộc gọi 1001 / 1002 / 3001

> Chạy từng nhóm theo thứ tự. Mỗi case ghi rõ kết quả: ✅ Pass / ❌ Fail / ⚠️ Partial.
> Dùng lệnh log nhanh: `adb logcat -s SipAccountMgr GsmSipBridgeService -v time`

---

## 0. Điều kiện tiên quyết

| # | Hạng mục | Kỳ vọng |
|---|----------|---------|
| 0-1 | FreePBX hoạt động, extension 1001/1002/3001 đã tạo | Asterisk CLI `sip show peers` → 3 entry |
| 0-2 | App đã cấp đủ quyền Runtime | `READ_PHONE_STATE`, `RECORD_AUDIO`, `CALL_PHONE`, `ANSWER_PHONE_CALLS` |
| 0-3 | App đã lưu cấu hình và bấm "Save & Start Dual SIP" | Notification bar: "SIM1 (1001) Registered" và "SIM2 (1002) Registered" |
| 0-4 | Log xác nhận đăng ký OK | `[SipAccountMgr] Registration state [slot=0]: Ok` và `slot=1: Ok` |

---

## 1. GSM → SIP (SIM nhận cuộc gọi GSM, bridge sang 1001/1002)

### Hướng dẫn thực hiện
1. Gọi điện từ số ngoài vào **SIM 1** của điện thoại Android đang chạy app.
2. Quan sát log và trạng thái thông báo.

| # | Bước kiểm tra | Log kỳ vọng | Kết quả |
|---|---------------|-------------|---------|
| 1-1 | GsmCallReceiver bắt trạng thái RINGING | `Incoming call from <số> on SIM slot 0` | |
| 1-2 | Service nhận ACTION_INCOMING_CALL | `Incoming call from <số> on SIM slot 0` trong `GsmSipBridgeService` | |
| 1-3 | Chờ đủ ring rồi auto-answer GSM | `GSM auto-answered` | |
| 1-4 | AudioManager vào MODE_IN_COMMUNICATION | `[AUDIO] prepareAudio: mode=IN_COMMUNICATION speaker=false` | |
| 1-5 | Bridge gọi vào extension 1001 | `Bridging SIM slot 0 to extension 1001` | |
| 1-6 | SIP call kết nối | `[RTP] CONNECTED slot=0` | |
| 1-7 | Codec đã negotiate | `[RTP] Connected slot=0 audioCodec=PCMU/8000` (hoặc PCMA) | |
| 1-8 | Streams chạy, RTP stats OK | `[RTP] STREAMS_RUNNING slot=0 iceState=... lostRcv=0.0%` | |
| 1-9 | Người gọi nghe được tiếng | Kiểm tra thực tế 2 chiều | |
| 1-10 | Kết thúc cuộc gọi | `[RTP] CALL_END slot=0` → `Ready - Waiting...` | |

**Lặp lại 1-1 đến 1-10 cho SIM 2** (thay slot=0 → slot=1, extension 1001 → 1002).

---

## 2. SIP → GSM (3001 gọi ra ngoài qua SIM)

### Hướng dẫn thực hiện
1. Từ softphone app đăng nhập extension **3001**, gọi đến số điện thoại di động thực.
2. Quan sát log.

| # | Bước kiểm tra | Log kỳ vọng | Kết quả |
|---|---------------|-------------|---------|
| 2-1 | SipAccountManager nhận INVITE từ Asterisk | `[RTP] INCOMING slot=? from=sip:3001@... to=<số_gsm>` | |
| 2-2 | Codec được negotiate | `[RTP] INVITE slot=? audioCodec=PCMU/8000` | |
| 2-3 | onIncomingCall dispatch đúng slot | `Incoming SIP from Asterisk: slot=0 number=<số_gsm>` | |
| 2-4 | App trả lời SIP call và gọi GSM ra | `GSM outbound call initiated: <số_gsm>` | |
| 2-5 | AudioManager chuẩn bị | `[AUDIO] prepareAudio:` với micMute=false, volume>0 | |
| 2-6 | Người dùng softphone nghe được nhạc chờ GSM | Kiểm tra thực tế | |
| 2-7 | Khi người nhận GSM bắt máy → 2 chiều OK | Kiểm tra thực tế | |
| 2-8 | Kết thúc → cả SIP và GSM đều drop | `[RTP] CALL_END` → `Ready - Waiting...` | |

---

## 3. Gọi đồng thời 2 SIM (Dual SIM stress test)

| # | Bước kiểm tra | Kết quả |
|---|---------------|---------|
| 3-1 | Gọi GSM vào SIM1, đồng thời gọi GSM vào SIM2 | Cả 2 cuộc gọi độc lập bridge song song |
| 3-2 | Log cho thấy slot=0 và slot=1 hoạt động độc lập | Không lẫn lộn slot |
| 3-3 | Kết thúc SIM1 không ảnh hưởng SIM2 | SIM2 vẫn còn kết nối |

---

## 4. Kiểm tra âm thanh (nếu kết nối OK nhưng không nghe tiếng)

Chạy lệnh này khi đang trong cuộc gọi:

```
adb logcat -s SipAccountMgr GsmSipBridgeService -v time | findstr RTP
```

| Triệu chứng log | Nguyên nhân có thể | Hành động |
|-----------------|-------------------|-----------|
| `audioCodec=null` | SDP không negotiate được codec | Kiểm tra codec cho phép trên FreePBX (PCMU, PCMA, G722) |
| `lostRcv > 5%` | Mạng kém, jitter cao | Thử kết nối Wi-Fi ổn định hơn |
| `iceState=Failed` | NAT/firewall chặn UDP RTP | Mở port UDP 10000-20000 hoặc cấu hình STUN |
| `micMute=true` | Microphone bị tắt hệ thống | Kiểm tra quyền RECORD_AUDIO đã được cấp runtime |
| `volume=0/15` | Âm lượng voice call = 0 | Người dùng tăng volume trong lúc gọi |
| `localAddr=0.0.0.0` | App không bind được cổng RTP | Kiểm tra INTERNET permission, thử đổi port range trong linphonerc |

---

## 5. Kiểm tra sau reload / mạng thay đổi

| # | Bước | Kỳ vọng |
|---|------|---------|
| 5-1 | Tắt Wi-Fi, bật lại | App tự re-register trong vòng ~5s, log `Network available - scheduling SIP re-register` |
| 5-2 | Bấm "Save & Start" lại (reload config) | Log `Reloaded accounts:` → đăng ký lại sạch, không duplicate event |
| 5-3 | Gọi ngay sau reload | Registration state Ok → cuộc gọi bridge thành công |
