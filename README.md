# Second Screen (`SecondScreen`)
### Windows `Win + K` → Android Wireless Secondary Display (Miracast / MS-MICE)

An ultra-low-latency Wi-Fi Display (WFD / Miracast) Sink receiver designed for Android tablets (optimized for **OnePlus Pad 2** Snapdragon 8 Gen 3 with 3K 7:5 EDID) that turns the tablet into a high-performance wireless secondary monitor for Windows 10/11 PCs (`Win + K` / Cast / Extend Display).

---

## Current Status: Milestones 0 – 6 Completed (Production Ready)

The complete end-to-end screen mirroring pipeline is fully implemented, verified, and running on physical hardware:

1. **Dual Connection Modes**:
   - **Mode 1: Wi-Fi Router Mode (`[MS-MICE]` - Miracast over Infrastructure)**:
     - Discovered natively by Windows over LAN/mDNS (`_display._tcp.local` and `_miracast._tcp.local` on port 7250).
     - Keeps tablet Wi-Fi internet active with zero WPS pairing prompts.
   - **Mode 2: Direct P2P Mode (Wi-Fi Direct)**:
     - Pure offline peer-to-peer 802.11 connection without any router.
     - Autonomous Group Owner (AGO) and WFD Sink beacon management via privileged shell helper (`UID 2000`).
2. **RTSP State Machine Engine (`rtsp/RtspServer.kt`)**:
   - Full duplex RTSP state machine handling M1 through M7 handshake, M16 keep-alive probes, and TEARDOWN.
   - Clean differentiation between M6 SETUP and M7 PLAY responses to guarantee immediate stream start.
3. **MPEG-TS Zero-Copy Demuxer (`media/TsDemuxer.kt`)**:
   - Parses 188-byte MPEG-TS packets over RTP PT=33 at high throughput.
   - Dynamically locks onto Video Elementary Stream PID and extracts Annex-B H.264 NAL units.
4. **Hardware Low-Latency Decoder (`media/VideoDecoder.kt`)**:
   - Leverages Snapdragon 8 Gen 3 hardware decoder (`c2.qti.avc.decoder`).
   - Configures `MediaFormat.KEY_LOW_LATENCY = 1` for minimal input lag.
   - Renders frames directly to Android `SurfaceView`.
5. **Resolution Control & Custom EDID Synthesis (`media/ResolutionPreference.kt`)**:
   - **3K 60fps Native (3000x2120 @ 7:5)**: Synthesizes a valid 128-byte EDID 1.4 block (`wfd_display_edid: 0001 <hex_edid>`) with DTD for 3000x2120 @ 60Hz and H.264 Level 5.2.
   - **1080p 60fps (Full HD - 1920x1080)**: Native CEA Index 8 (`0x40`), CEA mask `00000180`. Forces Windows to stream at full 1080p60 without falling back to 1024x768.
   - **1200p 60fps (16:10 WUXGA - 1920x1200)**: Native VESA Index 29 (`0xe9`), VESA mask `30000000`. Matches the tablet's 7:5 panel with reduced black borders.
   - **720p 60fps (HD Low Latency - 1280x720)**: Optimal for weak Wi-Fi or fast gaming.
   - **Auto / Multi-Resolution**: Exposes all standard modes to Windows Display Settings.
6. **Dynamic Display Scaling Modes (`DisplayScaleMode`)**:
   - **Fit (Letterbox)**: Preserves exact aspect ratio without distortion.
   - **Fill (Zero Bars)**: Crops slight horizontal borders to fill the tablet's 7:5 (3000x2120) panel edge-to-edge.
   - **Stretch**: Fullscreen stretch.
7. **Fullscreen Player UI (`MainActivity.kt`)**:
   - Auto-transitions from diagnostics to fullscreen player upon stream start.
   - Tap-to-toggle floating overlay with live bitrate (Mbps), packet counts, active resolution pill (e.g. `1920x1088 (16:9)`, `2736x1824 (3:2)`), scale mode switcher, and in-stream resolution selector.

---

## Live Hardware Verification Metrics (Physical OnePlus Pad 2)

* **1080p60 Test**:
  - Locked onto `1920x1088 @ 60 FPS`.
  - Qualcomm feedback: `inputFps=60, outputFps=58, renderFps=57, discardFps=0`.
* **3K High-Resolution Test**:
  - Locked onto `2736x1824 @ 3K`.
  - Over **5,700 frames** rendered smoothly at ~42–44 FPS with **`discardFps = 0`** (zero dropped frames).
* **Keep-Alive (M16)**:
  - 100% of Windows M16 probes answered with empty `200 OK` (CSeq: 1–50+), zero session disconnects.

---

## Project Structure

```text
Pad2WirelessDisplay/
├── app/
│   ├── build.gradle.kts
│   ├── src/main/
│   │   ├── AndroidManifest.xml
│   │   └── java/com/example/pad2display/
│   │       ├── MainActivity.kt                 # Jetpack Compose UI (Diagnostics & Fullscreen Player)
│   │       ├── diagnostic/                     # Hardware & Subsystem Diagnostics
│   │       │   ├── DeviceInfo.kt               # Snapdragon 8 Gen 3 & Display metrics
│   │       │   ├── WifiDiagnostics.kt          # Wi-Fi link speed & interface probing
│   │       │   ├── P2pDiagnostics.kt           # Wi-Fi Direct channel & WFD inspection
│   │       │   └── DiagnosticExporter.kt       # JSON/Text diagnostic export
│   │       ├── media/                          # Media Pipeline & Hardware Decoding
│   │       │   ├── ResolutionPreference.kt     # EDID synthesis, resolution presets, scaling modes
│   │       │   ├── RtpReceiver.kt              # UDP 19000 socket receiver & bitrate meter
│   │       │   ├── TsDemuxer.kt                # MPEG-TS parser & Annex-B NAL unit reassembly
│   │       │   └── VideoDecoder.kt             # MediaCodec c2.qti.avc.decoder low-latency renderer
│   │       ├── mice/                           # Mode 1: MS-MICE (Miracast over Infrastructure)
│   │       │   ├── MiceDiscoveryService.kt     # NsdManager mDNS advertiser (_display._tcp, _miracast._tcp)
│   │       │   └── MiceServer.kt               # TCP 7250 listener & SOURCE_READY parser
│   │       ├── rtsp/                           # RTSP Protocol Engine
│   │       │   └── RtspServer.kt               # Stage 3–9 RTSP state machine & M6/M7 dispatcher
│   │       └── shell/
│   │           └── WfdShellHelper.kt           # Privileged UID 2000 WFD Sink beacon helper
├── docs/
│   └── PROTOCOL_DECISIONS.md                   # Complete protocol rationale & decision log
├── research/                                   # Downloaded AOSP and open-source references
├── GEMINI.md                                   # Active development log & milestone checkpoint
└── README.md
```

---

## How to Resume & Run

### Step 1: Launch WFD Shell Helper (Privileged Beacon)
Ensure ADB is connected to the tablet (`adb devices` shows `4a0a11c1`):
```powershell
adb -s 4a0a11c1 shell 'CLASSPATH=$(pm path com.example.pad2display.debug | cut -d: -f2) app_process / com.example.pad2display.shell.WfdShellHelper sink'
```

### Step 2: Launch App on Tablet
```powershell
adb -s 4a0a11c1 shell "am start -n com.example.pad2display.debug/com.example.pad2display.MainActivity"
```

### Step 3: Connect from Windows
1. Press <kbd>Win</kbd> + <kbd>K</kbd> on Windows.
2. Select **OnePlus Pad 2** in the Cast flyout.
3. The tablet will immediately enter fullscreen and display your Windows screen.
4. Press <kbd>Win</kbd> + <kbd>P</kbd> → **Extend** to use the tablet as an independent secondary desktop.
5. In **Windows Settings → Display**, click the tablet to adjust resolution and scaling.
