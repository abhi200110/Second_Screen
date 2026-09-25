# Miracast / Wi-Fi Display (WFD) Protocol Decisions Log

This document records every protocol-specific design and implementation decision for building an Android Wi-Fi Display (WFD / Miracast) Sink that connects with Windows `Win + K` (Cast/Project).

All protocol fields, RTSP messages, byte layouts, port assignments, and capabilities documented herein are directly derived from:
1. **Wi-Fi Display Technical Specification v1.1.0 (Wi-Fi Alliance)**
2. **Microsoft Open Specifications**:
   - `[MS-WFDPE]`: Wi-Fi Display Protocol Extension
   - `[MS-MICE]`: Miracast over Infrastructure Connection Establishment Protocol
3. **Android Open Source Project (AOSP)**:
   - `media/libstagefright/wifi-display/source/WifiDisplaySource.cpp`
   - `media/libstagefright/wifi-display/VideoFormats.cpp` and `VideoFormats.h`
   - `frameworks/base/wifi/java/android/net/wifi/p2p/WifiP2pWfdInfo.java`
   - `com/android/server/wifi/p2p/WifiP2pServiceImpl.java`
4. **Mature Open-Source Implementations**:
   - `albfan/miraclecast` (`src/ctl/ctl-sink.c`, `res/sinkctl.protocol-extension.example`)
   - `5ingwings/MirrorCast-SinkApp` (`WiFiDirectMgr.java`, `RtspClient.java`, `RTPServer.java`)

---

## Stage 1: P2P Device Discovery & WFD Information Element (IE) Advertisement

### Protocol Behavior Being Implemented
To be discovered by Windows when the user presses `Win + K`, the Android device must participate in Wi-Fi Direct (P2P) discovery and advertise the Wi-Fi Display Information Element (WFD IE) within 802.11 Beacon and Probe Response frames.

Without a valid WFD IE declaring the device as a **Primary Sink** and indicating that a session is available, Windows `Win + K` filters out the device and will not display it in the Cast list.

#### 1. WFD Information Element Structure (OUI `50-6F-9A:0A`)
The 802.11 vendor-specific Information Element contains:
* Element ID: `0xDD` (Vendor Specific)
* Length: Length of IE body
* OUI: `50-6F-9A` (Wi-Fi Alliance)
* OUI Type: `0x0A` (Wi-Fi Display)
* Subelements:
  * **Subelement ID `0x00`**: WFD Device Information Subelement (Length = 6 bytes)
    * **WFD Device Information Bitmap** (2 octets, Big Endian):
      * Bits 0–1: Device Type (`00` = WFD Source, `01` = Primary Sink, `10` = Secondary Sink, `11` = Dual-role Source/Sink). We set `01` (Primary Sink).
      * Bit 2: Coupled Sink Support (`0` = not supported).
      * Bit 3: CP (Content Protection / HDCP 2.0/2.1) (`0` = not supported, `1` = supported).
      * Bit 4: Time Synchronization (`0` = not supported).
      * Bit 5: WFD Service Discovery (`0` = not supported).
      * Bits 6–7: Preferred Connectivity (`00` = P2P default).
      * Bit 8: WFD Session Availability (`1` = available for session establishment, `0` = unavailable).
      * Bit 9: WSD (`0`).
      * Bits 10–15: Reserved (`0`).
      * **Combined Bitmap Value**: `0x0111` (or `0x0101` when CP is disabled).
    * **Session Management Control Port** (2 octets, Big Endian):
      * TCP port on which the Sink listens for RTSP connections.
      * Standard Default Port: `7236` (`0x1C44` in hexadecimal).
    * **Maximum Device Throughput** (2 octets, Big Endian):
      * Maximum throughput in Mbps. E.g., `50` Mbps (`0x0032` in hexadecimal).

### Source / Reference
* Wi-Fi Display Technical Specification v1.1.0, Section 5.1.1 (WFD Device Information Subelement, Table 5.2).
* AOSP `WifiP2pWfdInfo.java`:
  - `DEVICE_TYPE_PRIMARY_SINK = 1`
  - `setWfdEnabled(true)`, `setDeviceType(1)`, `setSessionAvailable(true)`, `setControlPort(7236)`, `setMaxThroughput(50)`.
* MiracleCast `wpa_supplicant` subelements formatting (`p2p_set wfd_subelems`).

### Android API Involved
* `android.net.wifi.p2p.WifiP2pWfdInfo`:
  - Introduced in public API in API 30 (Android 11) with getters/setters (`setDeviceType`, `setWfdEnabled`, `setControlPort`, `setMaxThroughput`).
* `android.net.wifi.p2p.WifiP2pManager.setWfdInfo(Channel c, WifiP2pWfdInfo wfdInfo, ActionListener listener)`:
  - Requires permission: `android.permission.CONFIGURE_WIFI_DISPLAY`.

### Documented or Experimental
* **Documented**: `WifiP2pWfdInfo` class is documented in API 30+.
* **Restricted / Experimental in Userspace**:
  - `setWfdInfo` requires `android.permission.CONFIGURE_WIFI_DISPLAY`.
  - In AOSP `frameworks/base/core/res/AndroidManifest.xml`, `CONFIGURE_WIFI_DISPLAY` has `protectionLevel="signature"`.
  - Normal installed third-party apps cannot hold this signature permission without platform keys or root.
  - **Crucial Finding**: In `packages/Shell/AndroidManifest.xml`, `CONFIGURE_WIFI_DISPLAY` **is explicitly granted to the Android Shell (`com.android.shell`, UID 2000)** for CTS testing!
  - Therefore, an agent running via `adb shell` (`app_process`), Shizuku, or a rooted system app possesses `CONFIGURE_WIFI_DISPLAY` and can call `setWfdInfo()` without `SecurityException`.
  - For standard non-root apps, legacy fallback reflection (`clsWifiP2pManager.getMethod("setWFDInfo", ...)`) can be attempted, but will throw on Android 8.0+ stock ROMs unless invoked via ADB/privileged daemon.

### Expected Windows Behavior
* Windows broadcasts 802.11 Probe Requests scanning for Wi-Fi Direct displays.
* When Android responds with the WFD IE indicating Primary Sink and Port 7236, Windows adds the device to the `Win + K` list with a display monitor icon.

### How the Behavior Will Be Tested
1. Launch WFD Sink advertisement on Android (via Shell/ADB helper or privileged process).
2. On Windows, press `Win + K`.
3. Verify that the Android device name appears in the Windows Cast list within 3–5 seconds.
4. Verify via Wireshark or `netsh trace` that Windows received the 802.11 Probe Response with WFD IE Subelement 0 (`0x00 0x0006 0x0111 0x1C44 0x0032`).

---

## Stage 2: P2P Group Formation & Network Layer Setup

### Protocol Behavior Being Implemented
When the user clicks the Android Sink in the Windows `Win + K` menu, Windows initiates Wi-Fi Direct connection establishment:
1. **P2P Negotiation**:
   - Option A: Autonomous Group Owner (AGO) mode. Android pre-creates the P2P group (`createGroup`), acting as Group Owner.
   - Option B: P2P GO Negotiation. Windows or Android becomes Group Owner with Intent values (0–15).
   - In practice, **Autonomous Group Owner (AGO)** on Android is far more reliable because Android controls DHCP IP assignment.
2. **WPS (Wi-Fi Protected Setup)**:
   - Push Button Configuration (PBC) mode is standard so no PIN entry is required on Windows.
3. **DHCP & IP Assignment**:
   - Group Owner (Android) assigns itself `192.168.49.1`.
   - Windows connects as P2P Client and is leased `192.168.49.x` via Android's internal `dnsmasq` / P2P DHCP service.

### Source / Reference
* Wi-Fi Peer-to-Peer (P2P) Technical Specification v1.7.
* Android `WifiP2pManager` documentation:
  - `createGroup(Channel channel, ActionListener listener)`
  - Broadcast `WIFI_P2P_CONNECTION_CHANGED_ACTION`
  - `requestConnectionInfo(Channel channel, ConnectionInfoListener listener)`
* MirrorCast-SinkApp `WiFiDirectMgr.java:200-257`.

### Android API Involved
* `WifiP2pManager.createGroup(mChannel, mActionListener)`
* `BroadcastReceiver` receiving `WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION`
* `WifiP2pManager.requestConnectionInfo(mChannel, mConnectionInfoListener)`
* `WifiP2pInfo`: `groupFormed`, `isGroupOwner`, `groupOwnerAddress`.

### Documented or Experimental
* **Documented**: All `WifiP2pManager` group creation and connection APIs are fully documented public Android APIs (API 14+).

### Expected Windows Behavior
* Windows shows status "Connecting to <device>...".
* Windows Wi-Fi adapter connects to the Android Direct-XX SSID, performs WPS PBC handshake, and receives an IP address in the `192.168.49.0/24` subnet.

### How the Behavior Will Be Tested
1. Trigger connection by clicking the Sink in Windows `Win + K`.
2. On Android, observe `WIFI_P2P_CONNECTION_CHANGED_ACTION` broadcast with `networkInfo.isConnected() == true`.
3. Retrieve `WifiP2pInfo`: verify `groupFormed == true`.
4. Test ICMP ping from Windows to Android (`ping 192.168.49.1`) and verify round-trip network connectivity.

---

## Stage 3: RTSP Control Connection Establishment (TCP)

### Protocol Behavior Being Implemented
The Wi-Fi Display specification mandates that the WFD Sink acts as a TCP server listening on the Session Management Control Port advertised in the WFD IE (Port 7236).

Within seconds after the P2P connection and DHCP lease are completed, the WFD Source (Windows) connects via TCP to the Sink's IP address on Port 7236.

### Source / Reference
* Wi-Fi Display Technical Specification v1.1.0, Section 4.5: RTSP Session Establishment.
* AOSP `WifiDisplaySource.cpp:246` (Source connects to Sink and immediately dispatches M1).
* MiracleCast `src/ctl/ctl-sink.c:40-85`.

### Android API Involved
* `java.net.ServerSocket(7236)`
* Non-blocking I/O or background worker thread accepting incoming TCP socket connections.

### Documented or Experimental
* **Documented**: Standard Java TCP networking (`java.net.ServerSocket`).

### Expected Windows Behavior
* Windows opens a TCP connection to `<Android-IP>:7236`.
* Windows completes the TCP 3-way handshake (SYN, SYN/ACK, ACK).

### How the Behavior Will Be Tested
1. Start `ServerSocket` on port 7236 on Android before or immediately upon P2P connection.
2. Trigger Windows `Win + K` connection.
3. Assert that `serverSocket.accept()` returns a connected `Socket` and log the client IP address (Windows IP).

---

## Stage 4: RTSP M1 & M2 Exchange (OPTIONS Handshake)

### Protocol Behavior Being Implemented
As soon as the TCP connection is established, the RTSP handshake begins:

#### 1. M1 Request (Windows Source -> Android Sink)
Windows queries the Sink's supported RTSP methods:
```http
OPTIONS * RTSP/1.0
CSeq: 1
Require: org.wfa.wfd1.0
```

#### 2. M1 Response (Android Sink -> Windows Source)
The Sink responds with the required Wi-Fi Display public methods:
```http
RTSP/1.0 200 OK
CSeq: 1
Public: org.wfa.wfd1.0, GET_PARAMETER, SET_PARAMETER
```

#### 3. M2 Request (Android Sink -> Windows Source)
Immediately after or simultaneously, the Sink sends M2 over the SAME TCP connection to query the Source's supported methods:
```http
OPTIONS * RTSP/1.0
CSeq: 1
Require: org.wfa.wfd1.0
```

#### 4. M2 Response (Windows Source -> Android Sink)
Windows confirms supported methods:
```http
RTSP/1.0 200 OK
CSeq: 1
Public: org.wfa.wfd1.0, SETUP, TEARDOWN, PLAY, PAUSE, GET_PARAMETER, SET_PARAMETER
```

### Source / Reference
* Wi-Fi Display Technical Specification v1.1.0, Section 4.5.1 (M1) & Section 4.5.2 (M2).
* AOSP `WifiDisplaySource.cpp:544-565` (`sendM1`) & lines `1107-1120` (`onReceiveM2`).
* MiracleCast `ctl-sink.c:40-85` (`sink_handle_options`).

### Android API Involved
* Socket InputStream and OutputStream (`BufferedReader` / `BufferedWriter` with `\r\n` line delimiters and UTF-8 encoding).

### Documented or Experimental
* **Documented**: Standard RTSP (RFC 2326) with WFD 1.0 extensions.

### Expected Windows Behavior
* Windows sends M1 with `CSeq: 1`.
* Upon receiving Sink's 200 OK with `Public: org.wfa.wfd1.0, GET_PARAMETER, SET_PARAMETER` and Sink's M2 `OPTIONS *`, Windows replies to M2 and dispatches M3 (`GET_PARAMETER`).

### How the Behavior Will Be Tested
1. Parse incoming M1 request: verify method is `OPTIONS`, URI is `*`, `Require: org.wfa.wfd1.0` is present.
2. Send M1 response with matching `CSeq` and `Public` header.
3. Send M2 request with `CSeq: 1`.
4. Read Windows response to M2: assert status code `200 OK`.

---

## Stage 5: RTSP M3 Exchange (Capability Negotiation)

### Protocol Behavior Being Implemented
Windows queries the Sink's video, audio, and transport capabilities using RTSP `GET_PARAMETER`.

#### 1. M3 Request (Windows Source -> Android Sink)
Windows sends:
```http
GET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0
CSeq: 2
Content-Type: text/parameters
Content-Length: <length>

wfd_video_formats
wfd_audio_codecs
wfd_client_rtp_ports
wfd_uibc_capability
wfd_content_protection
microsoft_diagnostics_capability
microsoft_format_change_capability
microsoft_rtcp_capability
```

#### 2. M3 Response (Android Sink -> Windows Source)
The Sink responds with its exact capabilities:
```http
RTSP/1.0 200 OK
CSeq: 2
Content-Type: text/parameters
Content-Length: <length>

wfd_video_formats: 00 00 02 02 00000040 00000000 00000000 00 0000 0000 00 none none
wfd_audio_codecs: AAC 00000001 00, LPCM 00000002 00
wfd_client_rtp_ports: RTP/AVP/UDP;unicast <rtp_port> 0 mode=play
wfd_uibc_capability: none
wfd_content_protection: none
```

#### Breakdown of `wfd_video_formats` Bytes (from AOSP `VideoFormats.cpp`):
* Byte 0 (`00`): Native Resolution (`native_index << 3 | native_type`).
  - `00` = CEA Index 0 (640x480 p60) or `40` = CEA Index 8 (1920x1080 p60), `30` = CEA Index 6 (1280x720 p60).
* Byte 1 (`00`): Preferred Display Mode Supported (`00` = not supported, `01` = supported).
* Byte 2 (`02`): H.264 Profile (`01` = Constrained Baseline Profile, `02` = Constrained High Profile).
* Byte 3 (`02`): H.264 Level (`01` = Level 3.1, `02` = Level 3.2, `04` = Level 4.0, `08` = Level 4.1, `10` = Level 4.2).
* Bytes 4–7 (`00000040`): 4-byte CEA Resolution Bitmask:
  - Bit 0: 640x480 p60 (`0x00000001`)
  - Bit 5: 1280x720 p30 (`0x00000020`)
  - Bit 6: 1280x720 p60 (`0x00000040`)
  - Bit 7: 1920x1080 p30 (`0x00000080`)
  - Bit 8: 1920x1080 p60 (`0x00000100`)
* Bytes 8–11 (`00000000`): 4-byte VESA Resolution Bitmask.
* Bytes 12–15 (`00000000`): 4-byte Handheld (HH) Resolution Bitmask.
* Byte 16 (`00`): Latency (0 = none/unspecified).
* Bytes 17–18 (`0000`): Min-slice-size.
* Bytes 19–20 (`0000`): Slice-enc-params.
* Byte 21 (`00`): Frame-rate-control-support.
* Max-Hres: `none`
* Max-Vres: `none`

#### Breakdown of `wfd_audio_codecs`:
* `AAC 00000001 00`: AAC 2-channel 48kHz (bit 0 = 1).
* `LPCM 00000002 00`: LPCM 2-channel 48kHz (bit 1 = 1).

#### Breakdown of `wfd_client_rtp_ports`:
* Syntax: `RTP/AVP/UDP;unicast <rtp_port> 0 mode=play`
* Notice: The second port (`port1`) **MUST be 0** according to AOSP `WifiDisplaySource.cpp:800` (`port1 != 0` is flagged as malformed!).

### Source / Reference
* Wi-Fi Display Technical Specification v1.1.0, Section 5.1.5 (Table 5.11).
* AOSP `VideoFormats.cpp:95-285` (CEA, VESA, HH resolution tables and format spec generation).
* AOSP `WifiDisplaySource.cpp:567-595` (`sendM3`) and lines `767-900` (`onReceiveM3Response`).
* Microsoft `[MS-WFDPE]` Section 3.1.5.1 (GET_PARAMETER M3 request details).

### Android API Involved
* `android.media.MediaCodecList` to inspect device hardware decoder capabilities (validating H.264 High/Baseline profile support).

### Documented or Experimental
* **Documented**: Standard WFD 1.1 parameters. Unknown Microsoft parameters (`microsoft_diagnostics_capability`) can be omitted or answered with `none` according to `[MS-WFDPE]`.

### Expected Windows Behavior
* Windows parses the advertised video formats, selects an agreeable resolution (typically 1920x1080 or 1280x720) and codec, and sends M4 `SET_PARAMETER`.

### How the Behavior Will Be Tested
1. Verify parser extracts requested parameter keys from M3 body.
2. Verify Sink outputs valid M3 200 OK string.
3. Test against Windows `Win + K` and assert that Windows does not terminate the connection and sends M4.

---

## Stage 6: RTSP M4 & M5 Exchange (Session Setup & Trigger)

### Protocol Behavior Being Implemented

#### 1. M4 Request (Windows Source -> Android Sink)
Windows selects the session parameters:
```http
SET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0
CSeq: 3
Content-Type: text/parameters
Content-Length: <length>

wfd_video_formats: 00 00 02 02 00000040 00000000 00000000 00 0000 0000 00 none none
wfd_audio_codecs: AAC 00000001 00
wfd_presentation_URL: rtsp://<windows-ip>/wfd1.0/streamid=0 none
wfd_client_rtp_ports: RTP/AVP/UDP;unicast <rtp_port> 0 mode=play
```

#### 2. M4 Response (Android Sink -> Windows Source)
Sink confirms receipt:
```http
RTSP/1.0 200 OK
CSeq: 3
```

#### 3. M5 Request (Windows Source -> Android Sink)
Windows triggers the Sink to initiate the RTSP media setup:
```http
SET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0
CSeq: 4
Content-Type: text/parameters
Content-Length: 27

wfd_trigger_method: SETUP
```

#### 4. M5 Response (Android Sink -> Windows Source)
Sink acknowledges trigger:
```http
RTSP/1.0 200 OK
CSeq: 4
```

### Source / Reference
* Wi-Fi Display Technical Specification v1.1.0, Section 4.5.4 (M4) & Section 4.5.5 (M5).
* AOSP `WifiDisplaySource.cpp:597-651` (`sendM4`) & lines `653-697` (`sendTrigger`).
* Microsoft `[MS-WFDPE]` Section 3.1.5.2.

### Android API Involved
* String parameter extraction (storing `wfd_presentation_URL` and selected video resolution for `MediaCodec`).

### Documented or Experimental
* **Documented**: WFD 1.1 standard handshake.

### Expected Windows Behavior
* Windows sends M4, receives 200 OK.
* Windows sends M5 with `wfd_trigger_method: SETUP`, receives 200 OK, and awaits Sink's M6 `SETUP` request.

### How the Behavior Will Be Tested
1. Parse M4 body: extract `wfd_presentation_URL`.
2. Send 200 OK for M4.
3. Parse M5 body: verify `wfd_trigger_method: SETUP`.
4. Send 200 OK for M5, then immediately trigger M6.

---

## Stage 7: RTSP M6 (SETUP) & M7 (PLAY) Exchange

### Protocol Behavior Being Implemented

#### 1. M6 Request (Android Sink -> Windows Source)
Triggered by M5, the Sink requests RTSP transport setup:
```http
SETUP rtsp://<windows-ip>/wfd1.0/streamid=0 RTSP/1.0
CSeq: 2
Transport: RTP/AVP/UDP;unicast;client_port=<rtp_port>
```

#### 2. M6 Response (Windows Source -> Android Sink)
Windows confirms and assigns the Session ID and server ports:
```http
RTSP/1.0 200 OK
CSeq: 2
Session: <session_id>;timeout=60
Transport: RTP/AVP/UDP;unicast;client_port=<rtp_port>;server_port=<server_rtp_port>-<server_rtcp_port>
```

#### 3. M7 Request (Android Sink -> Windows Source)
Sink requests media playback:
```http
PLAY rtsp://<windows-ip>/wfd1.0/streamid=0 RTSP/1.0
CSeq: 3
Session: <session_id>
```

#### 4. M7 Response (Windows Source -> Android Sink)
Windows starts playback:
```http
RTSP/1.0 200 OK
CSeq: 3
Session: <session_id>
Range: npt=now-
```

### Source / Reference
* Wi-Fi Display Technical Specification v1.1.0, Section 4.5.6 (M6) & Section 4.5.7 (M7).
* AOSP `WifiDisplaySource.cpp:1125-1310` (`onSetupRequest`) and lines `1312-1355` (`onPlayRequest`).
* MirrorCast-SinkApp `RtspClient.java:393-415` (`sendRequestM6`, `sendRequestPlay`).

### Android API Involved
* Java Socket I/O.
* Allocation of local UDP port for RTP via `java.net.DatagramSocket`.

### Documented or Experimental
* **Documented**: Standard RTSP SETUP/PLAY flow.

### Expected Windows Behavior
* Windows returns 200 OK with `Session` ID for SETUP.
* Windows returns 200 OK with `Range: npt=now-` for PLAY.
* Immediately upon sending the M7 200 OK response, Windows begins transmitting RTP media packets to Android's `<rtp_port>`.

### How the Behavior Will Be Tested
1. Send M6 with client RTP port. Verify Windows replies 200 OK and provides `Session` ID.
2. Send M7 with Session ID. Verify Windows replies 200 OK.
3. Open UDP `DatagramSocket` on `<rtp_port>` and verify receipt of incoming data packets.

---

## Stage 8: Media Transport & Demuxing (RTP / MPEG-TS -> H.264 Video Decoder)

### Protocol Behavior Being Implemented
Windows streams the desktop screen as an MPEG-2 Transport Stream inside RTP packets over UDP.

#### 1. RTP Packet Format (RFC 3550)
* Header: 12 bytes
  - Byte 0: `0x80` (Version 2, No padding, No extensions, 0 CSRC)
  - Byte 1: Payload Type `33` (`0x21` or `0xA1` if marker bit is set) = MP2T (MPEG-2 Transport Stream)
  - Bytes 2–3: Sequence Number (16-bit, incremental)
  - Bytes 4–7: Timestamp (32-bit, 90 kHz clock rate)
  - Bytes 8–11: SSRC (Synchronization Source ID)

#### 2. MPEG-2 Transport Stream Format (ISO/IEC 13818-1)
* Each RTP payload contains exactly 7 TS packets of 188 bytes each = 1,316 bytes (Total UDP payload = 1,328 bytes).
* Each TS packet starts with sync byte `0x47`.
* TS Header (4 bytes):
  - Sync byte: `0x47`
  - Transport Error Indicator (1 bit)
  - Payload Unit Start Indicator (PUSI, 1 bit)
  - Transport Priority (1 bit)
  - PID (Packet ID, 13 bits):
    - `0x0000`: PAT (Program Association Table) -> points to PMT PID
    - PMT PID: Program Map Table -> defines Video Stream Type (`0x1B` for H.264) and Audio Stream Type (`0x0F` for AAC)
* PES (Packetized Elementary Stream):
  - Strips TS headers and reassembles PES packets.
  - Extracts H.264 Annex B Byte Stream (NAL units starting with `00 00 00 01`).
  - Feeds SPS (`NAL type 7`), PPS (`NAL type 8`), and slice data to Android `MediaCodec`.

### Source / Reference
* RFC 3550 (RTP: A Transport Protocol for Real-Time Applications).
* RFC 2250 (RTP Payload Format for MPEG1/MPEG2 Video).
* ISO/IEC 13818-1 (MPEG-2 Systems / Transport Stream).
* Wi-Fi Display Technical Specification v1.1.0, Section 4.6 (Media Transport).
* AOSP `frameworks/av/media/libstagefright/wifi-display/source/TSPackets.cpp`.

### Android API Involved
* `java.net.DatagramSocket`
* `android.media.MediaCodec` configured for `video/avc` in asynchronous or surface rendering mode (`configure(format, surface, null, 0)`).
* `android.view.SurfaceView` / `android.view.TextureView`.
* `android.media.AudioTrack` / `MediaCodec` for AAC (`audio/mp4a-latm`).

### Documented or Experimental
* **Documented**: Fully documented Android `MediaCodec` and `Surface` APIs.

### Expected Windows Behavior
* Windows Desktop Duplication API encodes screen frames with hardware H.264 encoder (Intel QuickSync, NVIDIA NVENC, or AMD AMF), multiplexes into MPEG-TS, and streams continuously over UDP.

### How the Behavior Will Be Tested
1. Log incoming UDP packet sizes (confirming 1,328-byte packets).
2. Validate RTP header: `payloadType == 33`.
3. Validate MPEG-TS sync byte: check that every 188 bytes starts with `0x47`.
4. Parse PAT and PMT, extract H.264 NAL units.
5. Render video frames onto Android `SurfaceView` and verify display of Windows desktop.

---

## Stage 9: RTSP Keep-Alive (M16) & Session Teardown

### Protocol Behavior Being Implemented

#### 1. Keep-Alive (M16)
Every 5 to 30 seconds, Windows or the Sink sends:
```http
GET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0
CSeq: <cseq>
Session: <session_id>
Content-Length: 0
```
Sink must reply:
```http
RTSP/1.0 200 OK
CSeq: <cseq>
```
If no keep-alive is received within the timeout window (60s), the session times out.

#### 2. Session Teardown
When the user clicks "Disconnect" in Windows or stops casting:
Windows sends:
```http
TEARDOWN rtsp://<windows-ip>/wfd1.0/streamid=0 RTSP/1.0
CSeq: <cseq>
Session: <session_id>
```
Or:
```http
SET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0
CSeq: <cseq>
Content-Type: text/parameters
Content-Length: 29

wfd_trigger_method: TEARDOWN
```
Sink responds with 200 OK, shuts down RTP receiver, releases `MediaCodec`, and unbinds sockets.

### Source / Reference
* Wi-Fi Display Technical Specification v1.1.0, Section 4.5.8 (Keep-Alive) & Section 4.5.9 (Teardown).
* AOSP `WifiDisplaySource.cpp:699-721` (`sendM16`) and lines `1100-1105` (`updateLiveness`).

### Android API Involved
* Socket I/O + `MediaCodec.stop()`, `MediaCodec.release()`, `DatagramSocket.close()`, `ServerSocket.close()`.

### Documented or Experimental
* **Documented**: Standard WFD session management.

### Expected Windows Behavior
* Windows sends periodic `GET_PARAMETER` keep-alives. On disconnect, Windows sends `TEARDOWN` and tears down the Wi-Fi Direct connection.

### How the Behavior Will Be Tested
1. Keep the connection running for >2 minutes to observe and answer multiple M16 keep-alives.
2. Disconnect from Windows (`Win + K` -> Disconnect); verify Sink cleanly receives TEARDOWN and releases all resources.

---

## Stage 10: Extended Protocol Decisions & Hardware Media Pipeline

### 1. RTSP M6 SETUP vs M7 PLAY Response Differentiator
* **Issue Encountered**: In RFC 2326, both `SETUP 200 OK` and `PLAY 200 OK` responses include the `Session: <session-id>` header. A naive RTSP response parser checking `if (sessionLine != null)` misidentifies the M7 PLAY response as another M6 SETUP response, causing an infinite loop where the Sink repeatedly sends M7 PLAY requests (`CSeq 1 -> 2 -> 3...`) and never transitions to `isStreaming = true`.
* **Resolution**: Per RFC 2326 Section 10.4 and 10.5, only `SETUP 200 OK` contains the `Transport: RTP/AVP/UDP;...` header. The parser checks:
  ```kotlin
  val isSetupResponse = sessionLine != null && transportLine != null && currentSessionId.isEmpty()
  ```
  Once `currentSessionId` is set and M7 PLAY is dispatched, any subsequent 200 OK containing `Session:` without `Transport:` is cleanly recognized as the M7 PLAY acknowledgment, immediately transitioning to active streaming.

### 2. WFD Native Resolution Byte Encoding & 1024x768 Fallback Prevention
* **Specification Rule**: Per Wi-Fi Display Specification v1.1.0 and AOSP `VideoFormats.cpp:244-250`, Byte 0 of `wfd_video_formats` encodes:
  $$\text{Byte 0} = (\text{nativeIndex} \ll 3) \mid \text{nativeType}$$
  where `nativeType` is `0` (CEA), `1` (VESA), or `2` (HH).
* **Root Cause for 1024x768 Fallback**: When Byte 0 is left as `0x00`, the Sink indicates native resolution is CEA Index 0 (640x480). When Windows parses this alongside a non-zero VESA mask containing bit 3 (1024x768 @ 60fps), Windows prioritizes 1024x768 over 1080p.
* **Resolution**: Setting Byte 0 to `0x40` ($8 \ll 3 \mid 0$) announces CEA Index 8 (1920x1080 @ 60fps) as native, with VESA modes zeroed out. This forces Windows to output full 1080p60.

### 3. Native 3K (3000x2120 @ 7:5) EDID Synthesis (`wfd_display_edid`)
* **Specification Rule**: Per Wi-Fi Display Specification Section 5.1.5 and `[MS-WFDPE]`, custom resolutions not present in the standard 1920x1080 CEA or 1920x1200 VESA tables are communicated via a 128-byte EDID 1.4 block:
  `wfd_display_edid: 0001 <256_hex_characters>`
* **Implementation Details**:
  - Manufacturer ID: `OPL` (`0x3E18`)
  - Product Name: `"OnePlus Pad 2"`
  - Detailed Timing Descriptor 1 (DTD 1): `3000x2120 @ 60Hz` with pixel clock 408.58 MHz ($3160 \times 2155 \times 60$).
  - Fallback Standard Timings: `1920x1200 @ 60Hz` (16:10) and `1920x1080 @ 60Hz` (16:9).
  - Verified Checksum: $\sum_{i=0}^{127} \text{byte}_i \equiv 0 \pmod{256}$.
  - Combined with H.264 High Profile Level 5.2 (`02 07`) and `microsoft_video_formats: 0000001fffff` per `[MS-WFDPE]`.

### 4. Zero-Copy MPEG-TS Demuxer & Low-Latency Snapdragon MediaCodec
* **Architecture**:
  - `RtpReceiver`: UDP socket with 8 MB buffer receiving 1,328-byte RTP datagrams (PT=33). Strips the 12-byte RTP header.
  - `TsDemuxer`: Parses 188-byte MPEG-TS packets starting with sync byte `0x47`. Identifies video PID via PES start codes (`00 00 01 E0..EF`), reassembles PES packets, and emits Annex-B Access Units.
  - `VideoDecoder`: Configures `c2.qti.avc.decoder` with `MediaFormat.KEY_LOW_LATENCY = 1` for immediate presentation on `SurfaceView`.
  - Verified in live testing: 5,700+ frames decoded at 2736x1824 with `discardFps = 0`.

