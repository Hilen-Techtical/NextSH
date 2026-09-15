# NextSH

[![Version](https://img.shields.io/badge/version-1.0.0-8B1A2F)](https://github.com/Hilen-Techtical/NextSH/releases)
[![License: MIT](https://img.shields.io/badge/license-MIT-C9A84C)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android%20%7C%20Windows%20%7C%20Linux-blue)](https://github.com/Hilen-Techtical/NextSH/releases)

**Native SSH client** : multi-session tabs, encrypted vault, SSH tunneling, and a modern dark UI.

Available on **Android** and **Desktop** (Windows, Linux). **iOS/macOS** planned.

Built by [Techtical](https://www.techtical.fr).

> Report issues at https://github.com/Hilen-Techtical/NextSH/issues.

---

## Download

| Platform | Format | File |
|---|---|---|
| Android | APK | [NextSH-1.0.0.apk](https://github.com/Hilen-Techtical/NextSH/releases/download/android-v1.0.0/NextSH-1.0.0.apk) |
| Windows | MSI installer | [NextSH-1.0.0.msi](https://github.com/Hilen-Techtical/NextSH/releases/download/desktop-v1.0.0/NextSH-1.0.0.msi) |
| Windows | EXE installer | [NextSH-1.0.0-setup.exe](https://github.com/Hilen-Techtical/NextSH/releases/download/desktop-v1.0.0/NextSH-1.0.0-setup.exe) |
| Linux | AppImage | [NextSH-1.0.0.AppImage](https://github.com/Hilen-Techtical/NextSH/releases/download/desktop-v1.0.0/NextSH-1.0.0.AppImage) |
| Linux | .deb | [NextSH_1.0.0-1_amd64.deb](https://github.com/Hilen-Techtical/NextSH/releases/download/desktop-v1.0.0/NextSH_1.0.0-1_amd64.deb) |


---

https://github.com/user-attachments/assets/fd71ba77-e841-4817-b552-afb88129bfc1

## Features

### SSH Sessions
- Multi-session with tabs and quick host picker
- Password, SSH key (Ed25519, RSA-4096, ECDSA), certificate, and biometric key authentication
- Auto-reconnect and configurable keep-alive
- **Split-screen**: two panes side-by-side or stacked (Terminal+Terminal or Terminal+SFTP)

### Terminal
- Full terminal emulator (forked from Termux on Android, jediterm-core with a native Compose Canvas renderer on Desktop)
- ANSI/256 colors, Unicode support
- **9 preset color themes** per host: Techtical Dark, Solarized Dark, Dracula, Nord, Monokai, Gruvbox Dark, Solarized Light, Techtical Light, High Contrast
- **Custom themes**: user-defined palettes (background, foreground, cursor, selection + 16 ANSI colors) with an HSV color picker, synced across devices
- Customizable font size and scrollback history
- Copy/paste via native clipboard with auto-clear

### Vault
- Credentials encrypted via Android Keystore (Android) or Windows Credential Manager (Desktop) + AES-256-GCM
- **Mandatory unlock** on every app launch (biometric / PIN / passphrase)
- SSH key generation in-app (Ed25519, RSA-4096, ECDSA 256/384/521)
- Biometric SSH keys: EC P-256 in hardware Keystore (TEE/StrongBox), per-use BiometricPrompt
- Encrypted backup export/import (`.nextsh` format, AES-256-GCM + PBKDF2).
  The backup covers hosts, tunnels, SSH keys with their private keys and
  passphrases, passwords and certificates. It does not include snippets,
  custom terminal themes, settings, known-host fingerprints, the LAN sync
  enrolment (by design) or biometric keys (hardware-bound by design)

### SSH Tunneling
- Local port forwarding (`-L`), remote port forwarding (`-R`), dynamic SOCKS5 proxy (`-D`)
- Named tunnel profiles with CRUD management
- Real-time state tracking (Starting, Active, Reconnecting, Error, Stopped)
- Auto-reconnect on network change with exponential backoff
- Application-level liveness watchdog (Desktop): `keepalive@openssh.com` every 30 s

### SFTP File Browser
- Full directory navigation with interactive breadcrumb path
- Complete CRUD: create directory, delete (recursive with symlink safety), rename, chmod
- File preview: text files (line numbers, JetBrains Mono) + images (pinch-to-zoom)
- Background transfers: progress notification, SAF for upload/download
- Sort by name/size/date, hidden files toggle

### LAN Sync (Solo mode)
- QR code enrollment between Android and Desktop (ECDH X25519 + HKDF-SHA256)
- TLS-pinned transport: self-signed cert fingerprint exchanged at enrollment
- Per-request HMAC-SHA256 + AES-256-GCM payload + ±5 min anti-replay
- CRDT engine with vector clocks, conflict resolution UI
- Transport gate: WiFi, VPN, or Ethernet only, cellular blocked

### FIDO2 / Passkeys
- Hardware key support (CTAP2 via YubiKit USB+NFC) on Android
- Passkey mode (Credential Manager) on Android
- FIDO2 Desktop on Windows (webauthn.dll / Windows Hello) and on Linux (YubiKit, CTAP2 over HID FIDO with PC/SC fallback)
- On Linux the HID transport needs read / write access to the key's `/dev/hidraw*` node : recent systemd distributions grant it to the logged-in user, otherwise install your distribution's FIDO udev rules (`libu2f-udev` on Debian / Ubuntu)

---

## Build from source

### Prerequisites
- JDK 17 or 21 (full JDK with `jpackage.exe` required for Desktop packaging, Eclipse Temurin or BellSoft Liberica recommended)
- Android Studio Iguana or newer (for Android build)
- ImageMagick (only if regenerating icon assets)

### Android
```bash
./gradlew assembleRelease       # APK in app/build/outputs/apk/release/
./gradlew :app:test             # 412 unit tests
./gradlew :app:lint
```

### Desktop (Windows)
```bash
./gradlew :desktop:packageMsiWithResources    # MSI installer
./gradlew :desktop:packageExeWithResources    # EXE installer (self-extracting, wraps the same MSI)
./gradlew :desktop:renameWindowsArtifacts     # canonical names (depends on both tasks above)
```
> Use the `*WithResources` tasks, not Compose Desktop's own `packageMsi` /
> `packageExe`: the latter overwrite jpackage's `--resource-dir`, so the
> installer they produce has neither the NextSH wizard branding nor the
> close-the-running-app-on-upgrade actions from
> `desktop/installer/windows/jpackage/main.wxs`.

### Desktop (Linux - Ubuntu 22.04+ / Debian / WSL2)
```bash
# Prereqs: full JDK 21 (with jpackage), the Android SDK (the shared KMP
# module configures AGP even for the Desktop build), and fakeroot (for the .deb).
./gradlew :desktop:fixDebDesktopEntry         # .deb (Debian/Ubuntu), incl. taskbar-icon fix
./gradlew :desktop:packageRealAppImage        # single-file .AppImage (x86_64 / aarch64)
./gradlew :desktop:renameLinuxArtifacts       # canonical names → release-artifacts/
```
> `packageAppImage` alone only emits a jpackage app-image *directory*;
> `packageRealAppImage` wraps it into a distributable `.AppImage`.

---

## Architecture

NextSH is a Kotlin Multiplatform project:
- **`app/`** : Android (Jetpack Compose, Hilt, Room)
- **`desktop/`** : Compose Multiplatform Desktop (SQLDelight, jediterm-core)
- **`shared/`** : KMP shared domain (models, crypto, CRDT sync)
- **`terminal/`** : Termux fork (terminal-emulator + terminal-view, Android only)

Clean Architecture + MVVM with unidirectional data flow. SSH errors never
leak exceptions to the UI, all results flow through the `SshResult<T>`
sealed class.

---

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Kotlin 2.0.21 |
| Android UI | Jetpack Compose (BOM 2024.05.00) |
| Desktop UI | Compose Multiplatform 1.7.3 |
| SSH | SSHJ 0.38.0 + BouncyCastle 1.78.1 + i2p EdDSA 0.3.0 |
| Vault Android | Android Keystore + EncryptedSharedPreferences |
| Vault Desktop | PBKDF2-HMAC-SHA256 200k + AES-256-GCM, Windows Credential Manager (JNA) |
| Database Android | Room 2.6.1 |
| Database Desktop | SQLDelight 2.0.2 |
| Terminal Android | Termux fork (terminal-emulator + terminal-view) |
| Terminal Desktop | jediterm-core 3.40 (emulator) + custom Compose Canvas renderer |
| LAN Sync server | Ktor Netty 3.0.0 (HTTPS, port 47731) |
| LAN Sync client | Ktor 3.0.0 (client-okhttp) |
| FIDO2 | YubiKit (CTAP2 USB+NFC) + Credential Manager (Android), webauthn.dll (Windows) |

---

## Security

- **Mandatory vault unlock** on every app launch (biometric or device credential / passphrase)
- Hardware-backed master key (Android Keystore TEE/StrongBox, Windows Credential Manager)
- Zero credentials in plain memory beyond immediate use
- `FLAG_SECURE` on sensitive screens (no screenshots)
- Timber log filtering in release (passwords, keys, tokens redacted)
- Host key fingerprint verification (TOFU + strict MITM rejection)
- Biometric keys invalidated on new enrollment
- Clipboard auto-clear after configurable timeout

LAN sync security:
- ECDH X25519 ephemeral handshake at enrollment
- TLS self-signed cert pinned by fingerprint
- HMAC-SHA256 + AES-256-GCM per request, ±5 min anti-replay
- HKDF-derived inner key for credential payloads (defense-in-depth)

Found a vulnerability? Please report it **privately**, see
[SECURITY.md](SECURITY.md). Do not open a public issue for security reports.

---

## Roadmap

### Android
- [x] SSH tunneling (local/remote/SOCKS5) with foreground service
- [x] Integrated secure browser for tunneled web services
- [x] FIDO2 SSH challenge-response (hardware key CTAP2 + passkey mode)
- [x] SFTP file browser (full CRUD, multi-select, preview, background transfers)
- [x] Snippet manager with terminal integration
- [x] Split-screen terminal
- [x] Per-host terminal color themes (9 presets) + user-defined custom themes (HSV color picker, CRDT-synced)
- [x] Internationalization (French + English)
- [x] Host import : native `nextsh-hosts` JSON, Termius (JSON), KeePassXC (CSV / XML)
- [ ] Tablet layout

### Desktop & Sync
- [x] Compose Multiplatform Desktop app : Windows
- [x] LAN sync : Solo mode (QR enrollment, ECDH + TLS-pinned)
- [x] Conflict resolution UI
- [x] Multi-session tabs, live theme picker, SFTP browser, split-screen
- [x] Windows Credential Manager, end-to-end credential sync
- [x] First-launch onboarding (3-step stepper)
- [x] FIDO2 Desktop : Windows (webauthn.dll / Windows Hello)
- [x] In-app browser for LOCAL_FORWARD tunnels (KCEF, Chromium Embedded)
- [x] FIDO2 Desktop : Linux (YubiKit Java driver)
- [ ] Linux Desktop secret backend (libsecret : fallback file backend already works)

### Team mode (server, separate repository)
- [ ] Self-hosted server
- [ ] Shared vault management with granular permissions
- [ ] LDAP / OpenLDAP / AD + SSO (OIDC, SAML)
- [ ] Web admin UI

### iOS / macOS
- [ ] iOS app
- [ ] macOS app

---

## Changelog

Release history is documented in [CHANGELOG.md](CHANGELOG.md).

---

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Pull requests welcome on the open
core (Android + Desktop + shared). The Team server module is proprietary
and lives in a separate private repository.

---

## License

NextSH is released under the **MIT License** : see [LICENSE](LICENSE) and
[NOTICE.md](NOTICE.md) for third-party attributions.

The Termux-derived terminal emulator (`terminal/terminal-emulator/` and
`terminal/terminal-view/`) remains under its original Apache License 2.0.

The future **Team server** (server-side LDAP/SSO/PostgreSQL multi-tenant
deployment) is distributed under a commercial license in a separate
repository.

---

## Author

**Thibaud Maneval** - [Techtical](https://www.techtical.fr)
