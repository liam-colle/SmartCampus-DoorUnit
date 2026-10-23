# Smart Campus Door Unit

Android app for the door terminals of Smart Campus (Famoco, Android 8 and later). A terminal is linked to a
room and shows its name, its status (free, occupied, closed, works, or a custom one), announcements with their
priority, and a clock. It is managed from the console of
[SmartCampus-CentralServer](https://github.com/liam-colle/SmartCampus-CentralServer).

```
core/   Kotlin library, no Android dependency: handshake, encrypted channel, protocol, door state,
        controller, enrollment, signed asset download. Unit tested and tested against a real server.
app/    Android app: Keystore identity, Compose screens, kiosk mode, audio playback.
```

## Build

Needs JDK 17 and the Android SDK (platform 35, build tools 35).

```bash
export JAVA_HOME=/path/to/jdk17 ANDROID_HOME=/path/to/Android/Sdk
./gradlew :core:test              # unit tests
./gradlew :app:assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
```

## First boot

1. The installer enters the **server address** and the **server public key** (base64 SPKI, printed by the server
   at `secrets/server-key.pub`). The terminal only trusts a server that proves it owns that key; TLS alone is not
   trusted as proof of identity.
2. The terminal creates its own P-256 key in the Android Keystore (the private key can sign but never be read)
   and sends a connection request with the public key. The screen shows the key **fingerprint**.
3. An admin or operator compares the fingerprint in the console (Devices, connection requests) and approves.
4. The terminal connects, receives its full desired state and shows the room. From then on it boots straight
   into its last known state, network or not.

A revoked terminal wipes its key and state and asks again from step 1.

## What it does

- **Room screen**: layouts standard, compact and split (split stacks the panels on a portrait screen), dark or light theme, accent colour, and the elements the
  console chose to show (room name, building, floor, capacity, clock, announcements).
- **Status**: label, colour and icon come with each status, so a status created after installation just works.
- **Announcements**: info, important, urgent, each with its own style. They hide by themselves at their expiry
  time, using the server clock offset, so a wrong terminal clock does not keep one on screen.
- **Offline mode**: the last state is written to disk after every change; the screen shows it with an
  "offline" badge while the link retries with a 1 to 60 s jittered backoff.
- **Night sleep**: inside the configured window the screen goes black and the backlight to its minimum.
  The terminal stays connected, so an announcement can still wake it.
- **InterCom**: `audio.play` carries an asset id, SHA-256 and size. The terminal downloads the file with a request
  signed by its key, checks the hash, caches it and plays it at the volume set for this device. During quiet hours
  it acknowledges the broadcast without playing it.
- **Delivery**: every command is acknowledged once; the ids of the last 200 are remembered on disk, so a replay
  after a reconnect or a restart never runs twice.

## Decisions

- **Native Kotlin and Compose** instead of the React Native app in the first design: a kiosk needs a small runtime
  and direct access to the Android Keystore. The protocol is the same, so nothing changes on the server.
- **`core` has no Android dependency**, so the cryptography, protocol and controller run in plain JUnit tests and in
  an end to end test, without an emulator.
- **Cleartext HTTP is allowed** in the manifest because identity and confidentiality come from the handshake, not
  from TLS. Production deployments should still use https.
- **Night sleep dims instead of switching the panel off**: turning the screen off needs device admin rights. A
  Famoco device owner setup can replace this with a real screen off.
- **Audio commands expire after five minutes** on the server, so a terminal that comes back late never plays stale audio.

## Tests

`./gradlew :core:test` runs the unit tests (RFC 5869 HKDF vector, channel tampering, replay and reflection,
handshake refusal, state, expiry, time windows, controller behaviour including quiet hours and restarts).

The end to end test talks to a real server and is skipped unless `SC_E2E_URL` is set. It enrolls a terminal,
approves it through the admin API, completes the handshake, follows display, announcement and audio commands,
downloads a sound with a signed request, and checks that revocation stops the link:

```bash
SC_E2E_URL=http://localhost:3000 \
SC_E2E_SERVER_KEY=<base64 of secrets/server-key.pub> \
SC_E2E_ADMIN_EMAIL=admin@smartcampus.local SC_E2E_ADMIN_PASSWORD=changeme123 \
./gradlew :core:test --tests '*EndToEndTest*'
```

## Run on an emulator

Install the emulator and a system image (`sdkmanager "emulator" "system-images;android-34;google_apis;x86_64"`),
create an AVD (`avdmanager create avd -n door-test -k "system-images;android-34;google_apis;x86_64" -d pixel_tablet`),
boot it (it needs KVM, and `-no-window` works for a headless machine) and `adb install` the debug APK. The emulator
reaches the host at `10.0.2.2`, so the server address is `http://10.0.2.2:3000`.

Checked this way against a local central server: provisioning and enrollment (the fingerprint on screen matches the
console), live status changes, split layout with the light theme, urgent announcement, audio download and playback
at the configured volume, offline badge and a cold restart without network, automatic reconnection (about 6 s),
night sleep (black screen, the device reports `screen: sleep`) and revocation (key and state wiped, back to setup).

## Not covered yet

Not tried on a real Famoco terminal. Typing the server key by hand is tedious on a device: a QR code or a
managed configuration would be better. No boot receiver: the app is meant to be set as the home app of the terminal.
