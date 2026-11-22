<div align="center">

# MeshWalkie

**Offline push-to-talk voice for Android, over a Bluetooth LE relay mesh.**
No internet. No cellular. No Wi-Fi. No server.

![Android 11+](https://img.shields.io/badge/Android-11%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-2.3-7F52FF?logo=kotlin&logoColor=white)
![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?logo=jetpackcompose&logoColor=white)
![Bluetooth LE](https://img.shields.io/badge/Bluetooth-LE-0082FC?logo=bluetooth&logoColor=white)
![Status](https://img.shields.io/badge/status-v0.2.0%20initial%20release-orange)

</div>

> [!NOTE]
> MeshWalkie is in **early development**. The code is feature-complete for its milestone but has **not yet been validated on multiple physical devices**. Expect rough edges, and please read [Development Status](#development-status).

---

## About

MeshWalkie turns nearby Android phones into a walkie-talkie network. Phones discover each other over Bluetooth LE, connect automatically, and **relay each other's packets**, so two people who are out of direct Bluetooth range can still talk through a phone in between.

- **Offline by design:** nothing leaves the phones. There is no account, server or cloud.
- **Push-to-talk:** hold the button to talk, release to listen.
- **Encrypted channels:** voice is protected with AES-256-GCM using a key derived from a shared room code.
- **Self-healing:** dropped links are detected and re-established automatically.

> [!IMPORTANT]
> MeshWalkie uses an **application-level BLE relay mesh**. It is *not* the Bluetooth SIG Mesh standard.

---

## Features

### Mesh networking
- Automatic BLE peer discovery and connection. Every device is both a GATT server and a GATT client.
- Application-level **flood relaying** with a hop limit (TTL 4) and duplicate-packet suppression.
- Relays never echo a packet back to the device it came from or to its origin (best effort).
- Large packets are fragmented to fit each peer's negotiated BLE MTU.

### Push-to-talk and talker arbitration
- **Channel-busy detection:** if someone is already talking, your press is refused with a haptic buzz and the speaker's name.
- **Collision resolution:** if two people start at the same moment, every device independently picks the same winner. No server or shared clock is needed.

| Rule | Behavior |
|------|----------|
| 1 | A talker who has sent about one second of audio beats a newcomer |
| 2 | Otherwise the higher random session ID wins (fair over time) |

- **Active speaker display:** see who is transmitting.
- **Transmit limit:** a 60-second cap stops a stuck microphone.
- **Lost-end recovery:** a talker who goes silent for 2 seconds is dropped automatically.
- Haptic feedback for transmit start and for denied or interrupted presses.

### Audio
- 8 kHz mono voice, IMA ADPCM compressed, in 40 ms frames.
- Jitter buffer with a 120 ms target, reordering and packet-loss concealment.
- Foreground service with a persistent notification.

### Security
- **AES-256-GCM** authenticated encryption.
- Key derived from the shared room code with **PBKDF2-HMAC-SHA256** (120,000 iterations).
- A short room tag in the packet header lets devices ignore other rooms without decrypting.
- Room codes are stored on the device.

### Reliability and self-healing
- **Acknowledged write pacing:** each BLE write waits for Android's completion callback.
- **Bounded, prioritised send queues:** control packets (start, end, announce) go first. When a link is congested, the oldest voice is dropped instead of building up delay.
- **Dead-link detection:** a peer with no successful activity for 20 s is torn down and reconnected.
- **Stuck-connection watchdog:** connections that never finish setup are abandoned and retried with exponential backoff.
- **Scan retry** after a BLE scan failure.
- **Bluetooth off/on recovery:** the mesh pauses when Bluetooth is turned off and resumes automatically when it returns.

### Diagnostics and visualisation
- **Radio** screen for channel, callsign, room code and push-to-talk.
- **Topology** screen showing nearby nodes and hop counts.
- **Diagnostics** screen: direct peers, RSSI, MTU, reconnect attempts, packets sent, received and relayed, duplicates dropped, authentication failures, congestion drops, link resets, voice loss and jitter-buffer statistics.

---

## Tech Stack

| Component | Configuration |
|-----------|---------------|
| Language | Kotlin `2.3` |
| UI | Jetpack Compose with Material 3 |
| Transport | Android Bluetooth LE (GATT server + client, advertising, scanning) |
| Crypto | `javax.crypto` AES-256-GCM, PBKDF2-HMAC-SHA256 |
| Audio | `AudioRecord` / `AudioTrack`, IMA ADPCM |
| Concurrency | Kotlin Coroutines |
| Min SDK | `30` (Android 11) |
| Target / Compile SDK | `36` |
| JVM target | `17` |

---

## Getting Started

### Prerequisites
- Android Studio (current stable)
- JDK 17
- **At least two** physical Android 11+ devices with Bluetooth LE. A third device is needed to test relaying. Emulators cannot test BLE mesh behavior.

### Clone the repository
```bash
git clone https://github.com/KeiranGamingTV/MeshWalkie.git
cd MeshWalkie
```

### Open and build
1. Open the project in Android Studio.
2. Let Gradle sync.
3. Connect a device and press **Run**, or build from the command line:

```bash
./gradlew assembleDebug
```

```bat
gradlew.bat assembleDebug
```

### Output location
```
app/build/outputs/apk/debug/app-debug.apk
```

---

## Usage

1. Install MeshWalkie on every device and grant the requested permissions (Bluetooth, microphone, notifications).
2. On every device, choose the **same channel** and enter the **same room code**.
3. Tap **Start Mesh**. Devices find each other automatically.
4. Hold the push-to-talk button to speak. Release to listen.
5. For relaying, place a third device between two devices that are out of direct range of each other.

> [!TIP]
> If devices don't find each other, confirm Bluetooth is on, all permissions were granted, and the room code and channel match exactly. The Diagnostics screen shows direct peers and any authentication failures.

---

## Project Structure

The source lives in `app/src/main/java/com/keiranhaas/bluetoothwalkie/`.

```
bluetoothwalkie/
├── MainActivity.kt          # Entry point and permissions
├── MeshService.kt           # Foreground service, push-to-talk lifecycle
├── MeshRuntime.kt           # UI state shared with the service
├── audio/
│   ├── WalkieAudioEngine.kt # Capture, jitter buffer, playback, PLC
│   └── ImaAdpcm.kt          # ADPCM codec
├── mesh/
│   ├── BleMeshTransport.kt  # BLE scan/advertise/GATT, link health
│   ├── PeerSendQueue.kt     # Bounded priority send queue
│   ├── FragmentCodec.kt     # MTU fragmentation and reassembly
│   ├── MeshEngine.kt        # Relaying, announcements, talker flow
│   ├── MeshProtocol.kt      # Packet and envelope formats
│   ├── RoomCrypto.kt        # Key derivation and AES-GCM
│   ├── TalkerArbiter.kt     # Decentralised floor control
│   └── MeshDiagnostics.kt   # Live statistics
├── prefs/                   # Settings and room-code storage
└── ui/                      # Radio, Topology and Diagnostics screens
```

---

## Android Permissions

| Permission | Purpose | Notes |
|------------|---------|-------|
| `BLUETOOTH_SCAN` | Find nearby MeshWalkie devices | Android 12+ |
| `BLUETOOTH_ADVERTISE` | Let nearby devices find this one | Android 12+ |
| `BLUETOOTH_CONNECT` | Connect to peers | Android 12+ |
| `BLUETOOTH`, `BLUETOOTH_ADMIN` | Legacy Bluetooth access | Android 11 and below |
| `ACCESS_FINE_LOCATION` | Required by Android for BLE scanning on older versions | Used only for scanning |
| `RECORD_AUDIO` | Capture your voice while transmitting | |
| `FOREGROUND_SERVICE` + `_CONNECTED_DEVICE` + `_MICROPHONE` | Keep the mesh and microphone alive | |
| `POST_NOTIFICATIONS` | Show the persistent mesh notification | Android 13+ |

MeshWalkie requests no internet permission.

---

## Development Status

v0.2.0 is the **initial release** and targets the "Communication Reliability" milestone. The features above are implemented, but the project is not stability-guaranteed. Areas still being worked on:

- Validation on real devices: two-device, three-device relay, range loss and recovery
- Smarter relay pruning and per-peer link-quality scoring
- Audio quality: adaptive jitter buffer, noise suppression, AGC, voice-activity detection
- Security hardening: peer authentication, replay protection review, key rotation, QR-code invitations
- Background and battery optimisation
- Channel and peer management, and theming

See the [release notes](../../releases) for details.

> [!WARNING]
> BLE throughput and behavior vary widely between phone models. Voice quality over several relay hops is **not yet characterised**.

---

## Contributing

Contributions, ideas and testing reports are welcome!

1. Fork the repository.
2. Create a feature branch.
3. Make your changes and build them.
4. Test on at least two physical devices if you touch the mesh or audio code.
5. Open a pull request describing what changed and why.

---

## Bug Reports

Please include:

- Device models and Android versions of **every** phone involved
- How many devices were in the mesh and how they were arranged
- Steps to reproduce
- What you expected and what happened
- The Diagnostics screen values (peers, link resets, congestion drops, packet loss)
- Logcat output if possible

---

## Acknowledgements

- [Android Bluetooth LE APIs](https://developer.android.com/develop/connectivity/bluetooth/ble/ble-overview)
- [Jetpack Compose](https://developer.android.com/jetpack/compose) and Material 3
- [Kotlin Coroutines](https://github.com/Kotlin/kotlinx.coroutines)
- IMA/DVI ADPCM

---

<div align="center">

**MeshWalkie**
Talk to the people around you. No signal required.

</div>