# Remote Control - Android

Native Android phone client for the same remote-control system as
`remote_control_phone` (web), `relay-server` (signalling relay) and
`remote_control_laptop` (Python agent that injects input and streams frames).

The phone is a **remote**, not the target: it authenticates to the relay with
its own Ed25519 key pair, sends terminal commands and input events, and renders
the laptop screen.

## Requirements

- JDK 17
- Android SDK with platform 36 (`compileSdk`/`targetSdk` 36, `minSdk` 26)
- Point the build at your SDK with a `local.properties` containing
  `sdk.dir=/path/to/Android/Sdk`

## Build, test, lint

```bash
./gradlew :app:assembleDebug          # APK at app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest      # JVM unit tests (identity, protocol, relay handshake, geometry)
./gradlew :app:lintDebug              # Android lint
./gradlew :app:installDebug           # to a connected device/emulator
```

## Configuration

Defaults live in `app/build.gradle.kts` and match `remote_control_phone/config.js`:

| Setting  | Default                                     |
| -------- | ------------------------------------------- |
| Relay    | `wss://remote-control-lmxu.vercel.app/`      |
| Pair token | `rc.pairToken (see gitignored env files)`      |
| Passcode | `laser`                                      |

Override without touching the source:

```properties
# gradle.properties or ~/.gradle/gradle.properties
rc.serverUrl=wss://your-relay.example/
rc.pairToken=your-pair-token
rc.passcode=your-passcode
```

Everything can also be changed at runtime from **Settings**. For release
signing, create a git-ignored `keystore.properties` next to the build file:

```properties
storeFile=/abs/path/release.jks
storePassword=...
keyAlias=remote-control
keyPassword=...
```

## Authentication and pairing

Identical handshake to the web client and laptop agent
(`docs/ARCHITECTURE.md`):

1. `register` - `device_id`, `public_key` (raw 32-byte Ed25519 point, hex) and
   `pair_token` while the relay does not know the key.
2. `challenge` - the relay replies with a fresh `nonce`.
3. `auth` - Ed25519 signature (hex) over `"<device_id>:<nonce>"`.
4. `registered` - the phone is now `READY`.

The relay binds `device_id` to `SHA-256(public_key)`, so the keypair is the
identity: it is generated once and persisted in `SharedPreferences`
(`rc:phone:private_key`, `rc:phone:public_key`, `rc:phone:paired`), matching the
web client. Pair once with the token; later connections present the same key
without a token. Only one phone may hold the slot; a `4001` close means another
client took over.

Screenshots arrive as raw binary WebSocket frames (JPEG). All other traffic is
JSON (`command`, `signal`, `screen.request`, `pointer.move`,
`pointer.button`, `pointer.scroll`, `keyboard.key`).

## Biometric lock

The app requires approval before anything else happens:

- On launch and on returning from Settings/Home, `BiometricPrompt` is shown with
  `BIOMETRIC_WEAK or BIOMETRIC_STRONG`.
- "Use passcode" falls back to the configured passcode.
- If biometrics are unavailable or unenrolled, the passcode is the only way in.
- The relay connection is opened **only after** the gate is approved, and is
  dropped when the app is backgrounded while *Lock on background* is on.
- Back from Home, or the lock button, re-locks immediately.

Both toggles (*Require biometrics*, *Lock on background*) live in Settings.

## Transport security

- `usesCleartextTraffic="false"` plus `res/xml/network_security_config.xml`: the
  shipped `wss://` relay is the only default; cleartext is permitted solely for
  `localhost`, `127.0.0.1` and `10.0.2.2` (device/emulator development). Running
  a plain `ws://` relay on your LAN means adding its host to that file.
- The device identity is never backed up or transferred
  (`res/xml/data_extraction_rules.xml`, `res/xml/backup_rules.xml`): copying the
  Ed25519 private key to a second device would mint a duplicate identity for the
  same pair.
- The passcode is stored in app-private `SharedPreferences`, mirroring
  `localStorage` in the web client. Treat a rooted device as compromised.

## Layout

```
app/src/main/java/com/remotecontrol/
├── MainActivity.kt        Compose host, lock state, background locking
├── RemoteControlApp.kt    Application: owns the RemoteSession
├── config/                AppConfig (defaults + URL handling), SettingsStore
├── core/                  hex helpers
├── data/                  DeviceIdentity, Protocol, RelayClient, RemoteSession
├── input/                 KeyMap: text/combo -> evdev key names
└── ui/                    lock/, home/, terminal/, remote/, settings/, theme/
```

## Tests

`app/src/test` covers the parts that must stay wire-compatible:

| Test | What it pins down |
| ---- | ----------------- |
| `data/DeviceIdentityTest` | Ed25519 generation, persistence across instances, `device_id == SHA-256(raw key)`, and that signatures verify the way `relay-server/index.js` verifies them |
| `data/RelayHandshakeTest` | `register → challenge → auth → registered` against a MockWebServer relay that re-implements the real checks; pair token sent only while unpaired; binary frames delivered |
| `data/ProtocolTest` | Exact JSON keys for every outbound message and the typed parsing of inbound ones |
| `config/AppConfigTest` | URL/host normalisation and the shipped defaults |
| `input/KeyMapTest` | Key names and character → `(evdev key, shift)` mapping |
| `ui/remote/ScreenGeometryTest` | `object-fit: contain` rectangles, corner/centre mapping, letterbox rejection |

## Input mapping

Pointer coordinates are normalized to the laptop screen: the frame keeps its
aspect ratio (`object-fit: contain`) and only touches inside the displayed
rectangle are mapped. Letterbox touches are ignored rather than clamped, as
required by `docs/io-mapping-PRD.md`. Key names follow the laptop agent's
`evdev_keycodes.py` table (`KEY_A`, `ESC`, `LEFTCTRL`, aliases included).
