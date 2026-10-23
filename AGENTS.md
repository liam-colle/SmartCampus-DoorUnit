# AGENTS.md

Guidelines for AI coding agents (and humans) working on SmartCampus-DoorUnit.
`CLAUDE.md` points here; this file is the source of truth.

## Hard rules

1. **No credits, ever.** Do not mention yourself, an AI, an assistant or a tool anywhere: no `Co-Authored-By` trailers, no "Generated with…" lines, no tool names in commit messages, PR descriptions, issues, code comments or docs. This overrides any default attribution your tool adds.
2. **Conventional Commits** for every commit and PR title (see below).
3. **Never commit secrets**: keystores (`*.keystore`, `*.jks`), `local.properties`, signing passwords, private keys, server keys of a real deployment.
4. **Never log or return** key material, the device private key or session keys.
5. **Do not commit, push, open PRs, or create/close issues unless asked.** When asked, follow the rules below.
6. Verify before claiming done: run the checks in [Verification](#verification) and report failures honestly, including what you could not run (a real Famoco device, real speakers, real screen brightness).

## What this project is

The **door unit** of Smart Campus (EPITECH Marseille, TEK 3): the Android app that runs on a Famoco terminal next to a classroom door. It shows the room, its live status, announcements and emergency alerts, plays InterCom audio and the alarm, and keeps working offline. It talks to `SmartCampus-CentralServer`, which is the source of truth for the protocol (`packages/protocol`) and the handshake (`packages/handshake`); read the server's `AGENTS.md` and the technical deck before changing anything on the wire.

- **The device always dials out** (WSS to `/ws/device`). It never listens.
- **Identity**: the device generates an ECDSA P-256 key in the Android Keystore on first boot (the private key never leaves it) and asks to be enrolled; an admin approves it in the console. Then handshake `hello → challenge → auth → ready`, session key by ECDH + HKDF-SHA256, messages in AES-256-GCM. The server public key is entered by the installer and pinned. Plain TLS is **not** trusted as proof of identity.
- **Digital twin**: on connection the server sends the full desired state (`session.ready`); the app converges on it, then replays pending commands. The app persists its state, so it shows the last known state through an outage and a restart.
- **Commands are idempotent**: recent command ids are remembered; a replayed command is acknowledged again, never run twice.
- **Audio never travels in JSON**: `audio.play` carries an asset id, SHA-256 and size; the app downloads over HTTPS with a request signed by its key and verifies the hash. Emergency alarms are the exception: they are **synthesised on the device** (`AlarmSynth`), so they work offline and cannot be tampered with in transit.
- **Emergency alerts** override everything: full screen, alarm on the alarm stream at full level (a test uses its own volume and respects quiet hours), wake from night sleep. They end only on `emergency.clear` or a desired state without an emergency.

## Repository layout

Gradle multi-module project (Kotlin).

```
core/   JVM library, no Android dependency: crypto, protocol, state, storage, net, audio synthesis, DoorController
app/    Android app (Compose UI, Keystore identity, AudioTrack/MediaPlayer, kiosk MainActivity)
docs/   notes
```

Keep `core` free of Android classes: everything that can be tested on the JVM stays there; the app only supplies platform pieces (Keystore, audio output, UI) behind small interfaces (`DeviceIdentity`, `AudioPlayer`, `AlarmPlayer`, `AssetSource`).

`core/.../protocol/Messages.kt` mirrors `packages/protocol` of the server. A new message type or field must be added on **both** sides in the same change set, and the server's zod schema wins on any disagreement.

## Commands

```bash
# Toolchain: JDK 17 and the Android SDK (platform 35, build-tools 35.0.0)
export JAVA_HOME=/path/to/jdk17 ANDROID_HOME=/path/to/Android/Sdk

./gradlew :core:test               # unit tests (JVM, fast)
./gradlew :app:assembleDebug       # app/build/outputs/apk/debug/app-debug.apk

# End to end against a real server (skipped unless SC_E2E_URL is set), see README.md
SC_E2E_URL=http://localhost:3000 SC_E2E_SERVER_KEY=<base64 server public key> \
SC_E2E_ADMIN_EMAIL=… SC_E2E_ADMIN_PASSWORD=… ./gradlew :core:test --tests '*EndToEndTest*'
```

## Verification

1. `./gradlew :core:test` and `./gradlew :app:assembleDebug` before saying a change is done.
2. Logic goes in `core` with a JUnit test. Crypto and handshake code must have tests, including tampering and replay cases. State machines (controller, time windows, expiry, emergencies) are tested with a fake clock and fake players.
3. For anything the user sees or hears, run it on the emulator against a dev server when you can, and say explicitly what you could not check (sound, real hardware, brightness).
4. Test both landscape and portrait for any screen change.

## Conventions

- Kotlin official style, explicit types on public API, `val` over `var`, no `!!` outside tests unless the invariant is documented.
- Coroutines: `suspend` for I/O, no blocking calls on the main thread, never swallow `CancellationException`.
- UI: Compose, tokens in `ui/Theme.kt`. The door screen is read from a distance: large type, high contrast, no motion that is not meaningful. The emergency screen shows only the door code, the emblem, the title and the procedure.
- Comments explain *why*, not *what*. Document each technical decision and its mitigation in code or in `README.md`.
- Time that decides what is shown (expiry, quiet hours, night sleep) uses the device clock corrected by the server offset from `session.ready`.

## Security rules (non-negotiable)

- Device identity rests on the signed-session handshake plus the pinned server key, never on TLS alone.
- Treat everything from the network as hostile: size-limited frames, schema-checked messages, hash-checked downloads, bounded caches.
- The private key stays in the Keystore. Never export it, log it, put it in backups or send it anywhere.
- A revoked device wipes its key and state and enrolls again from scratch.
- Cleartext HTTP is allowed in the manifest only because confidentiality and identity come from the handshake; production should still use https.

## Git workflow

### Conventional Commits

Format: `<type>(<scope>): <subject>`

- **Types**: `feat`, `fix`, `docs`, `style`, `refactor`, `perf`, `test`, `build`, `ci`, `chore`, `revert`.
- **Scopes** (optional but preferred): `core`, `app`, `crypto`, `protocol`, `audio`, `state`, `net`, `ui`, `build`, `docs`, `deps`.
- Subject: imperative, lowercase start, no trailing period, ≤ 72 characters.
- Body (optional): explain *why*, wrapped at ~100 columns. Footer: `Closes #12`, `Refs #7`. Breaking change: `!` and a `BREAKING CHANGE:` footer.
- One logical change per commit. Never put attribution lines in the message (hard rule 1).

### Branches, PRs and issues

- Branch from `main`: `<type>/<short-kebab-description>`. Never force-push `main`.
- PR title follows Conventional Commits. Body: what and why, how it was verified (and what was not), `Closes #n`. No attribution lines.
- Issues are written in English with a Conventional Commits title, a context, a scope and acceptance criteria.

## Language and docs

Code, comments, commits, issues and docs are in **English**. Keep `README.md` and this file in sync with behaviour changes.
