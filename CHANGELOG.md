# Changelog

All notable changes to NextSH are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
NextSH ships two products from one codebase, versioned independently:
**Android** (`android-vX.Y.Z`) and **Desktop** (`desktop-vX.Y.Z`). Entries are
grouped by release date; bullets are tagged `[All]`, `[Android]` or
`[Desktop]` to indicate the platform(s) they apply to.

## [android-v1.1.0] / [desktop-v1.1.0] - 2026-11-19

Feature release for both products: backup format 2, automatic app lock and
background terminal sessions on Android, much more reliable tunnels, a safer
SFTP browser, and a large security and translation pass.

### Added
- [All] The `.nextsh` backup (version 2) now also carries snippets, custom
  terminal themes, certificates, known hosts and the main display settings.
  Older versions refuse a version 2 file with a clear message instead of
  restoring it badly. Security settings and the sync secret are never saved.
- [All] After an import, a summary dialog tells what was restored, what was
  skipped and why, and whether the restore was only partial. An export now
  warns about items that could not be restored later.
- [All] The SFTP browser has a "+" button with New folder and New file, and
  sending or downloading onto an existing name asks "Replace / Cancel"
  instead of overwriting without notice.
- [All] The SFTP browser shows the hidden leftovers of an interrupted
  replacement ("Upload interrupted" badge) and offers to delete them after
  listing their exact names. Nothing is ever deleted on its own.
- [All] Creating or renaming in the SFTP browser now says why a name is
  refused, under the field, instead of silently changing it.
- [All] A tunnel stopped because it was deleted on another device is announced
  by a notification.
- [All] Snippet and theme editors refuse invisible characters and oversized
  values, with the reason under the field, so what you save can always be
  restored from a backup.
- [Android] The app locks itself: at startup, after a delay spent away
  (immediately, 1, 5, 15 or 30 minutes) or with "Lock now". The lock screen
  covers everything, and the vault stays open under it, so background
  reconnections keep working.
- [Android] Terminal sessions keep running when you leave the terminal screen
  or the app. A "Resume" banner and a badge in the host list bring them back,
  and a notification offers "Close all".
- [Android] A biometric SSH key can now be chosen in the host form, with one
  fingerprint prompt per connection.
- [Android] A tunnel that needs you (fingerprint or security key) before it can
  reconnect posts an "Authentication required" notification, and tapping it
  restarts the tunnels that were waiting.
- [Android] A tunnel card in error can be dismissed.
- [Android] "Erase vault" asks for confirmation in two steps, shows its
  progress, resumes after the app was killed halfway and returns to the first
  launch screen.
- [Android] The terminal draws in JetBrains Mono, like the Desktop app, and
  its scrollback follows the setting (1,000 to 20,000 lines, 5,000 by default).
- [Android] Deleting an SSH key used by hosts names those hosts and counts the
  tunnels concerned before asking for confirmation.
- [Android] Opening a file in the SFTP browser keeps the connection through the
  file picker, and a document that exists only online (such as a Google Docs
  file) is refused with a message asking you to export it.
- [Android] Messages use the app's own message bar everywhere, read aloud by
  TalkBack.
- [Desktop] The built-in browser asks for the user name and password of
  services protected by HTTP authentication.
- [Desktop] After `exit`, a terminal stays readable under a "Session ended"
  banner with Reconnect and Close, instead of looking connected while frozen.
  The connection closes once no SFTP browser or transfer still uses it.

### Changed
- [Android] Tunnel reconnection is shown in amber with its reason (network
  lost, session closed, no answer from the server) and the card turns red only
  when the app gives up. No attempt is wasted while the device is offline.
- [Desktop] The reason of a tunnel reconnection stays visible on the card (full
  text on hover). While offline the tunnel waits for the network, and a change
  of network adapters wakes it up.
- [Android] The global Keep-alive setting is gone: each host's own Keep-alive
  field (0 to disable, otherwise 15 to 60 seconds) now applies to all its
  sessions, so an idle terminal is no longer silently dropped by a router.
- [Android] A host key that no longer matches is never retried automatically,
  and its confirmation prompt now expires instead of waiting forever.
- [Android] The Passkey mode is no longer offered for new FIDO2 hosts, and a
  FIDO2 host with no mode connects with its security key.
- [All] Dates older than 7 days are shown as an absolute date, in the language
  of the displayed texts.
- [All] The sync conflict screen shows the favorite and the other synchronized
  fields, in both languages, and "Keep local" really settles the conflict
  instead of letting it come back.
- [All] Text that was still hardcoded now follows the app language: on Android
  the host list, tunnel screens, sync screens, SFTP browser, snippets, known
  hosts and settings, and on Desktop the SFTP browser, vault messages, FIDO2
  errors and host key dialogs.
- [All] French texts use the formal "vous" consistently, and a few messages
  that suggested an action that does not exist now name one that does.
- [Android] Error messages of connections, enrollment and the vault no longer
  quote the raw text of an exception.
- [Android] A launcher shortcut acts immediately on an already unlocked app and
  reuses the open terminal.

### Fixed
- [All] Deleting a host also deletes its tunnels and stops the running ones.
  Editing a host or a tunnel is atomic, keeps its dates and no longer brings
  back an item deleted elsewhere.
- [All] A restore now updates existing items instead of replacing them, and
  sync ignores a tunnel whose host is unknown instead of failing.
- [All] A refused client connection or a slow channel opening no longer stops a
  local tunnel while the SSH connection is alive.
- [All] A lost connection while checking a name in the SFTP browser now says
  "connection lost".
- [All] An SFTP upload that fails or is cancelled no longer leaves a partial
  file under a new name, and an in-place write reads the first block of the
  source before emptying the target, so an unreadable source no longer leaves
  an empty file. A source shorter than announced is reported as a failure.
- [Android] The app no longer crashes when background sync runs while the vault
  has not been opened yet.
- [Android] A tunnel stopped during its startup stays stopped, a failed start
  gives a precise reason, and a cancelled start no longer leaves a card on
  "Starting" forever.
- [Android] Tunnels use one SSH connection per host, and a session lasts as
  long as its last user: closing a tab no longer cuts a tunnel or a transfer
  that shares the connection.
- [Android] Tapping a host opens a single SSH connection, and a shell that does
  not open closes its session.
- [Android] A tunnel that drops again right after being restored is no longer
  reconnected endlessly, and both automatic reconnection options are now
  honored.
- [Android] A tunnel that never ran is no longer reported as deleted elsewhere,
  and a deleted tunnel no longer keeps running in the background.
- [Android] FIDO2 security keys are more reliable: the prompt no longer cancels
  itself while the key asks for its PIN, alphanumeric PINs work, NFC and USB
  problems are explained, a silent server no longer blocks the connection, and
  cancelling really cancels.
- [Android] A FIDO2 enrollment with a wrong or missing PIN says so plainly, and
  security keys no longer appear in the SSH key list.
- [Android] Rotating the phone no longer loses open dialogs, sheets and forms
  on the SFTP, snippet, key, import and settings screens, and SFTP dialogs
  scroll in landscape.
- [Android] A host import no longer needs two Back presses.
- [Android] The Vault screen counts passwords without decrypting the vault and
  locks the app again when it finds the vault authentication expired.
- [Android] A vault key invalidated by a screen lock change is reported with
  the real cause.
- [Android] Exporting a backup asks for the passphrase before the location and
  no longer leaves an empty file.
- [Android] SFTP file dates and sizes follow the language of the displayed
  texts, not the system one.
- [Desktop] A split pane reconnects to its own server, and SFTP browsers and
  snippet filters in a split follow the host of their pane.
- [Desktop] Closing a terminal tab or pane no longer cuts a transfer in
  progress, and a resize sent to a closed shell no longer drops the whole
  connection and its SFTP.
- [Desktop] A SOCKS5 tunnel whose listener fails now reconnects instead of
  staying "Active" without listening, and a tunnel no longer stays stuck on
  "Starting" after an error.
- [Desktop] "Erase vault" now closes sync, tunnels, transfers and sessions
  first, removes leftover temporary files, can be retried after a failure and
  leaves the app on the vault creation screen.
- [Desktop] Downloads go through a temporary file, so a file you agreed to
  replace stays intact if the transfer fails, and its permissions are kept.
- [Desktop] The SFTP browser keeps its hidden files option, sort order and
  current folder when you switch tabs, and an error that arrives during a
  loading is no longer lost.
- [Desktop] Host and tunnel editors show a clear error instead of creating a
  duplicate host, and the host editor keeps the typed password until saving.
- [Desktop] Local database links between hosts and tunnels are really enforced.
- [Desktop] The first session after creating the vault also starts the sync
  server, and an abandoned host import erases its secrets.
- [Desktop] An apostrophe in the purge dialog no longer shows a backslash.

### Security
- [All] SSH keys are proven, not trusted: the public key is now derived from the
  private key and checked by a signature, so the key you copy to a server is
  always the right one. A key file whose public part does not match is refused
  at import, and a backup with a wrong public key gets it corrected.
  Keys that would take too long to open or use an unsupported encryption are
  refused with a message explaining how to save them again, and key files are
  read with a 64 KiB limit.
- [All] A backup cannot replace one of your keys: SSH keys are only added on
  restore, and a booby-trapped file is validated, truncated at fixed limits or
  refused before it can use too much memory or time.
- [All] A backup never brings the first fingerprint of a host already present on
  the device, so a crafted file cannot silently redirect it to another server.
  The first connection checks it again.
- [All] Invisible formatting characters (such as right-to-left overrides) are
  refused in SFTP names, so an `.exe` can no longer pass for a `.pdf`. They are
  replaced when sending or downloading and shown as a marker in lists.
- [All] An SFTP replacement writes a hidden temporary file first and only
  follows a symbolic link that belongs to you and points straight to its
  target, so a link planted by someone else in a shared folder cannot redirect
  it.
- [All] Pasting in the terminal is sanitized: escape and control codes are
  removed and line breaks become returns, so copied text cannot run commands
  by itself.
- [All] Logs no longer contain host names, addresses, paths, labels, device
  identifiers or exception texts, and masked values also hide invisible and
  control characters.
- [All] Sync errors no longer quote decrypted content, and a received key is
  normalized before it is kept.
- [All] A user name with a control character is refused in the host editor.
- [Desktop] A split pane no longer sends its password to the server of the first
  pane when it reconnects.
- [Desktop] The sync server checks a push before reading it (headers, time,
  known device, 32 MiB limit), accepts at most two at once and answers 503 when
  overloaded instead of making a healthy device enroll again.
- [Desktop] Copying an SFTP path that contains a hidden character is refused,
  and a file sent from the computer no longer keeps a backslash in its name.
- [Android] The vault is erased without decrypting its secrets, and the
  biometric keys are deleted with it. Restore and sync refuse a foreign
  Keystore alias.
- [Android] Launcher shortcuts carry a token specific to the installation, so
  another app cannot trigger them.
- [Android] The trust button for an unknown server key disarms itself when the
  app leaves the foreground, and a known-hosts entry that cannot be stored (a
  host address with a space or an invisible character) is refused.
- [Android] The FIDO2 PIN is no longer kept as text, is announced as a password
  by TalkBack, and the exported USB entry point no longer crashes on a
  malformed request.

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
