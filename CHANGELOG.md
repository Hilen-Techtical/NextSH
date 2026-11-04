# Changelog

All notable changes to NextSH are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
NextSH ships two products from one codebase, versioned independently:
**Android** (`android-vX.Y.Z`) and **Desktop** (`desktop-vX.Y.Z`). Entries are
grouped by release date; bullets are tagged `[All]`, `[Android]` or
`[Desktop]` to indicate the platform(s) they apply to.

## [android-v1.0.1] / [desktop-v1.0.1] - 2026-11-04

Maintenance release for both products.

### Changed
- [Desktop] NextSH now reopens on the screen where it was left, instead of
  always on the primary one, and a maximized window moved to another screen
  keeps a sensible restored size there.
- [Desktop] The window no longer shows a pixelated logo in the Windows title
  bar.

### Fixed
- [Android] The vault can be created and unlocked on Android 10, where the
  unlock screen previously offered no button.
- [Android] Ed25519 key generation now works in the published app too, not
  only in development builds.
- [Android] Saving a host no longer crashes when the vault authentication has
  expired: the fingerprint or device code prompt is offered on the form,
  which keeps what you typed.
- [Android] Connecting with an encrypted SSH key now uses its stored
  passphrase, and a cancelled connection no longer shows an error.
- [Android] A tunnel with an out-of-range port no longer crashes the app: the
  form explains the allowed range, and a tunnel received by sync or import
  with a bad port shows an error instead.
- [Android] The built-in browser now asks for the user name and password of
  services protected by HTTP authentication, instead of only showing a 401
  page.
- [Android] The black band above the keyboard in the host, tunnel and key
  import forms is gone.
- [Android] Deleting a host or an SSH key now also removes its stored
  password, passphrase and certificate. Switching a host from key to password
  authentication no longer risks sharing storage with the key, and a host
  whose key has disappeared shows an error asking you to choose another one.
- [Desktop] The built-in browser no longer crashes the application when
  NextSH is started normally. It now uses its own browser profile and keeps
  no cookies, history or cache on disk.
- [Desktop] Editing a host or a tunnel no longer resets the settings absent
  from the form (favorite, keep-alive, auto-reconnect, FIDO2 mode), which
  could make a renamed FIDO2 host unusable on Android after a sync.
- [Desktop] Tunnels are more reliable: a tunnel can no longer start twice or
  keep its local port, a stop requested during startup is honored, tunnels
  that were reconnecting resume when the network returns, and a stopped local
  tunnel no longer keeps a processor core busy.
- [Android] Stopping a local tunnel no longer leaves a thread using a full
  processor core until the app is closed.
- [Desktop] The window disappears cleanly when closing and no longer flashes
  white at startup.
- [Desktop] Text keeps the right size when the window moves to a screen with
  a different scaling, and a restored window gets back its floating size
  instead of the full screen one.
- [Desktop] Copying from a full-screen remote program such as Claude Code now
  reaches the clipboard.
- [Desktop] SSH connections with a FIDO2 security key now work: OpenSSH
  servers rejected the signature format, and on Windows the signature was also
  computed on the wrong data.
- [All] Importing hosts is safer: an import no longer leaves a host without
  its secrets, a key shared by several Termius hosts is imported once, and an
  imported host finds its key and passphrase when connecting.
- [All] Sync is more careful with your data: on Android it no longer deletes
  the tunnels of synced hosts, on Desktop it keeps their creation date and last
  connection, and adding or removing a favorite now reaches the other device.
- [All] A deletion received by sync while the vault is locked is retried at
  the next sync, instead of being undone on the other devices.
- [All] SFTP folder deletion no longer fails halfway when the folder contains
  a subfolder, and file names containing characters such as `+`, `%` or `\`
  now point to the right file or folder.

### Security
- [Android] The name of an uploaded file can no longer place it outside the
  chosen SFTP folder.
- [Desktop] The name of a new SFTP folder is checked before use, so it can no
  longer be created outside the displayed folder.
- [Android] Host passwords are masked by default in the host form, with a
  button to reveal them, and every secret field (key passphrases, backup
  passphrases, HTTP password, FIDO2 PIN) uses a keyboard that does not learn
  or autocorrect what you type.
- [Android] Opening the vault after its authentication window has expired now
  asks for authentication instead of failing.

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

[android-v1.0.1]: https://github.com/Hilen-Techtical/NextSH/releases/tag/android-v1.0.1
[desktop-v1.0.1]: https://github.com/Hilen-Techtical/NextSH/releases/tag/desktop-v1.0.1
[android-v1.0.0]: https://github.com/Hilen-Techtical/NextSH/releases/tag/android-v1.0.0
[desktop-v1.0.0]: https://github.com/Hilen-Techtical/NextSH/releases/tag/desktop-v1.0.0
