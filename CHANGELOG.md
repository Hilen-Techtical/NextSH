# Changelog

All notable changes to NextSH are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
NextSH ships two products from one codebase, versioned independently:
**Android** (`android-vX.Y.Z`) and **Desktop** (`desktop-vX.Y.Z`). Entries are
grouped by release date; bullets are tagged `[All]`, `[Android]` or
`[Desktop]` to indicate the platform(s) they apply to.

## [android-v1.0.0] / [desktop-v1.0.0] - 2026-09-15

Initial public release.

### Added
- [All] **SSH sessions** : multi-session tabs with a quick host picker,
  password / SSH key (Ed25519, RSA-4096, ECDSA) / certificate /
  biometric-key authentication, host-key fingerprint verification (TOFU
  with strict mismatch rejection), auto-reconnect and configurable
  keep-alive.
  [All] Split-screen: two panes side-by-side or stacked
  (Terminal+Terminal or Terminal+SFTP).
- [All] **FIDO2 / passkeys** : hardware-key CTAP2 (YubiKit USB+NFC) and
  passkey mode (Credential Manager) on Android; `webauthn.dll` / Windows
  Hello on Desktop.
- [Android] **Terminal emulator** (forked from Termux) : ANSI/256 colors,
  Unicode support, 9 preset color themes led by Techtical Dark and Techtical
  Light (the light theme keeps gold and burgundy on the same ANSI slots as
  the dark one), extra-keys bar, clipboard
  copy/paste with auto-clear.
- [Desktop] **Terminal renderer** : Compose Canvas renderer (`jediterm-core`
  as the emulator) with native xterm mouse reporting (modes 1000/1002/1003,
  X10 + SGR) and pane cycling shortcuts.
- [Desktop] **Broadcast typing to several terminals at once** : split a tab
  into two or more connected sessions, press Ctrl+Shift+B, and every
  keystroke typed in the active pane is relayed to all of them, the way
  `synchronize-panes` works in tmux but across distinct SSH hosts. Target
  panes wear a "Broadcast" badge, and the mode switches itself off as soon as
  fewer than two panes remain connected. Run the same command on a fleet of
  servers, watch every answer side by side.
- [All] **Custom terminal themes** : user-defined palettes (background,
  foreground, cursor, selection + 16 ANSI colors) with an HSV color picker,
  assignable per host, live re-render on open sessions, synced across
  devices via the CRDT engine (metadata only).
- [All] **Encrypted vault** : Android Keystore (TEE/StrongBox) +
  `EncryptedSharedPreferences` on Android; PBKDF2-HMAC-SHA256 (200k) +
  AES-256-GCM with a Windows Credential Manager–backed master key on
  Desktop. Mandatory unlock on every launch (biometric / PIN / passphrase),
  in-app SSH key generation, biometric SSH keys invalidated on new
  enrollment.
- [Desktop] **Vault recovery phrase** : DEK-envelope scheme unlockable by
  PIN or a 12-word BIP39 recovery phrase.
- [All] **SSH tunneling** : local (`-L`), remote (`-R`) and dynamic SOCKS5
  (`-D`) port forwarding with named tunnel profiles, real-time state
  tracking, and auto-reconnect on network change with exponential backoff.
  Application-level liveness watchdog (`keepalive@openssh.com`, 30 s tick).
  [Android] Integrated secure in-app browser for tunneled web services.
  [Desktop] In-app browser (KCEF) for `LOCAL_FORWARD` tunnels.
- [All] **SFTP file browser** : full directory navigation, complete CRUD
  (create, delete, rename, chmod), text and image preview, background
  transfers with progress.
- [All] **Snippet manager** with terminal integration.
- [All] **Host import** : a documented native `nextsh-hosts` JSON format,
  plus Termius (JSON) and KeePassXC (CSV and KeePass 2 XML) importers, with
  a selection preview that never displays secrets and flags secret-bearing
  entries.
- [All] **Encrypted backup** export/import (`.nextsh` format : AES-256-GCM +
  PBKDF2, passphrase required) covering hosts, tunnels, SSH keys with their
  private keys and passphrases, passwords and certificates. Snippets, custom
  terminal themes, settings and known-host fingerprints are not part of it,
  nor are the LAN sync enrolment and biometric keys, both by design.
- [All] **LAN sync (Solo mode)** : QR code enrollment between Android and
  Desktop (ECDH X25519 + HKDF-SHA256), TLS-pinned transport (self-signed
  cert fingerprint exchanged at enrollment), per-request HMAC-SHA256 +
  AES-256-GCM payload with ±5 min anti-replay, CRDT engine with conflict
  resolution UI, transport gate restricted to WiFi / VPN / Ethernet.
  Automatic rediscovery when the Desktop's IP changes, via an
  HMAC-authenticated UDP broadcast probe.
- [All] Internationalization (French + English).
- [Android] Targets Android 16 (API 36) with 16 KB page-size compliance and
  typed foreground services (`specialUse` for tunnels, `dataSync` for SFTP
  transfers), as Google Play requires; edge-to-edge layout, with rotation on
  the terminal, the SFTP browser and the tunnel browser.
- [Desktop] Windows packaging (MSI / EXE installer) and Linux packaging (single-file AppImage / `.deb`), both
  carrying the store metadata software centers read (AppStream, icon,
  developer, licence, website).
- [All] MIT license, `NOTICE.md` third-party attributions, and SPDX headers
  across `app/` / `shared/` / `desktop/`.

### Security
- [All] Credentials and private keys are encrypted at rest and unlocked
  only via an explicit vault unlock on every launch; secrets are kept in
  memory only for immediate use (`String` → `CharArray`, wiped after use)
  and never written to logs in release builds.
- [All] Host keys are verified on first use (TOFU) with strict rejection on
  mismatch.
- [Android] `FLAG_SECURE` on sensitive screens, clipboard auto-clear after a
  configurable timeout, `allowBackup=false`.
- [All] LAN sync's device-local shared secret stays on-device by design :
  never exported to peers, never replicated through credential sync, and
  never included in an encrypted vault backup.
- [Desktop] The LAN discovery responder pre-filters and rate-limits
  unauthenticated packets before touching the vault, never answers a
  replayed nonce, and advertises nothing to scanners; a device's last known
  address is persisted only after a fully authenticated sync, never from an
  unauthenticated discovery response.
- [All] Host importers avoid logging raw parser exception messages, which
  could otherwise quote file content, including secrets.

[android-v1.0.0]: https://github.com/Hilen-Techtical/NextSH/releases/tag/android-v1.0.0
[desktop-v1.0.0]: https://github.com/Hilen-Techtical/NextSH/releases/tag/desktop-v1.0.0
