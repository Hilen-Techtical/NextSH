# NOTICE

NextSH is licensed under the MIT License (see [LICENSE](LICENSE)).

This software bundles or links to the third-party libraries listed below.
Each entry gives the version, the upstream license, and (where applicable)
the option exercised when the upstream is dual-licensed. All third-party
licenses are compatible with the MIT distribution of NextSH.

---

## Forked code

The terminal emulator embedded in the Android client is a fork of
[Termux](https://github.com/termux/termux-app):

- **`terminal/terminal-emulator/`** : terminal emulation engine (Java)
- **`terminal/terminal-view/`** : Android terminal view widget (Java)

  License: **Apache License 2.0** : the original copyright notices are
  preserved inside `terminal/` source files. See
  https://www.apache.org/licenses/LICENSE-2.0.

---

## Runtime dependencies

### SSH and cryptography

| Library | Version | License |
|---|---|---|
| SSHJ | 0.38.0 | Apache License 2.0 |
| Bouncy Castle (`bcprov-jdk18on`, `bcpkix-jdk18on`) | 1.78.1 | Bouncy Castle License (MIT-style, see https://www.bouncycastle.org/licence.html) |
| net.i2p.crypto.eddsa | 0.3.0 | CC0-1.0 (public domain) |

### UI and platform

| Library | Version | License |
|---|---|---|
| Compose Multiplatform | 1.7.3 | Apache License 2.0 |
| Compose BOM (Android) | 2024.05.00 | Apache License 2.0 |
| Compose Material Icons Extended | (Compose BOM 2024.05.00) | Apache License 2.0 |
| Compose Icons Lucide | 1.1.0 | ISC License |
| AndroidX Core / Lifecycle / Activity / Navigation | (latest stable) | Apache License 2.0 |
| AndroidX Biometric | 1.2.0-alpha05 | Apache License 2.0 |
| AndroidX Security Crypto | 1.1.0-alpha06 | Apache License 2.0 |
| AndroidX Credentials | 1.3.0 | Apache License 2.0 |
| AndroidX Room | 2.6.1 | Apache License 2.0 |
| AndroidX DataStore | 1.1.1 | Apache License 2.0 |
| AndroidX WorkManager | 2.9.1 | Apache License 2.0 |
| AndroidX CameraX | 1.3.4 | Apache License 2.0 |
| Hilt | 2.51.1 | Apache License 2.0 |

### Storage and serialization

| Library | Version | License |
|---|---|---|
| SQLDelight | 2.0.2 | Apache License 2.0 |
| kotlinx-serialization | 1.7.3 | Apache License 2.0 |
| kotlinx-coroutines | 1.9.0 | Apache License 2.0 |
| kotlinx-datetime | 0.6.0 | Apache License 2.0 |

### Networking

| Library | Version | License |
|---|---|---|
| Ktor (server + client) | 3.0.0 | Apache License 2.0 |
| OkHttp | 4.12.0 | Apache License 2.0 |

### Desktop terminal and rendering

| Library | Version | License |
|---|---|---|
| JediTerm (`jediterm-core`) | 3.40 | Apache License 2.0 |
| SLF4J (`slf4j-nop`) | 2.0.13 | MIT License |

### QR codes

| Library | Version | License |
|---|---|---|
| ZXing (`core`, `javase`) | 3.5.3 | Apache License 2.0 |

### Hardware security keys (FIDO2)

| Library | Version | License |
|---|---|---|
| YubiKit Android (`android`, `fido`) | 2.4.0 | Apache License 2.0 |
| YubiKit Desktop (`desktop`, `core`, `fido`) | 2.8.1 | Apache License 2.0 |

### Logging

| Library | Version | License |
|---|---|---|
| Timber | 5.0.1 | Apache License 2.0 |

---

## Dual-licensed libraries option exercised

- **JNA** 5.14.0 - Apache License 2.0 OR LGPL 2.1+
  → NextSH exercises the **Apache License 2.0** option.

- **JNA Platform** 5.14.0 - Apache License 2.0 OR LGPL 2.1+
  → NextSH exercises the **Apache License 2.0** option. Used on Desktop
  for Windows Credential Manager bindings (`Advapi32.CredReadW/CredWriteW`)
  and Windows WebAuthn API native bindings (`webauthn.dll`).

---

## Bundled data files

| File | Source | License |
|---|---|---|
| `desktop/.../core/vault/bip39-english.txt` | Official BIP39 English wordlist from the [bitcoin/bips](https://github.com/bitcoin/bips/blob/master/bip-0039/english.txt) repository | Public domain (CC0-1.0) |

The 2048-word list is used to generate and validate the Desktop vault
recovery phrase (see `RecoveryMnemonic`).

---

## Embedded native components

- **KCEF** 2025.03.23 - Kotlin wrapper for the Chromium Embedded Framework
  (CEF), provided by Datlag. Used on Desktop for the in-app browser
  overlay rendering tunneled web services.
  - Wrapper: **Apache License 2.0** (see https://github.com/DatL4g/KCEF)
  - Underlying CEF binaries are pulled at runtime from
    [JetBrainsRuntime](https://github.com/JetBrains/JetBrainsRuntime)
    (BSD 3-Clause for CEF + various Chromium upstream licenses).
    NextSH does not redistribute the CEF binaries; they are downloaded
    on first launch by KCEF.

---

## Third-party services

- **Google ML Kit barcode-scanning** 17.3.0 (Android only, used to scan
  the LAN sync enrollment QR code from Desktop). Subject to the Google
  ML Kit Terms of Service:
  https://developers.google.com/ml-kit/terms

  Note: ML Kit on-device models are bundled with the NextSH Android
  release; users do not need to enable any external service.

---

## Test-only dependencies

These dependencies are not shipped in the release artifacts; they are
listed for completeness.

| Library | Version | License |
|---|---|---|
| JUnit Jupiter (`api`, `engine`) | 5.10.2 | Eclipse Public License 2.0 |
| MockK | 1.13.11 | Apache License 2.0 |
| OkHttp MockWebServer | 4.12.0 | Apache License 2.0 |
| Ktor Server Test Host | 3.0.0 | Apache License 2.0 |
| AndroidX Test Ext JUnit | 1.1.5 | Apache License 2.0 |
| Espresso Core | 3.5.1 | Apache License 2.0 |
| kotlinx-coroutines-test | 1.9.0 | Apache License 2.0 |

---

## Trademarks

- "NextSH" is a project name used by Techtical
  ([techtical.fr](https://www.techtical.fr)).
- Other product names mentioned in this document are trademarks of their
  respective owners.

---

If you believe a library is missing from this list or its license is
misattributed, please open an issue on the public repository.
