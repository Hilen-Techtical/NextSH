# Security Policy

NextSH is a security-sensitive application: it stores SSH credentials, private
keys, and certificates, and it establishes SSH connections and tunnels on the
user's behalf. We take vulnerability reports seriously and appreciate
responsible disclosure.

> **Note:** Response times below are best-effort targets, not guarantees.

## Supported Versions

Only the latest published release of each platform receives security fixes.
Older releases are not patched, please update before reporting.

| Product | Supported |
|---|---|
| Android latest `android-vX.Y.Z` | ✅ |
| Desktop latest `desktop-vX.Y.Z` | ✅ |
| Any older release | ❌ |

## Reporting a Vulnerability

**Please do not open a public GitHub issue for security vulnerabilities.**

Report privately through one of:

1. **GitHub Private Vulnerability Reporting** (preferred) : on the
   [`Hilen-Techtical/NextSH`](https://github.com/Hilen-Techtical/NextSH)
   repository: **Security → Report a vulnerability**. This opens a private
   advisory visible only to maintainers.
2. **Techtical** : via [techtical.fr](https://www.techtical.fr) if you cannot use
   GitHub's private reporting.

Please include:

- Affected platform and version (`android-vX.Y.Z` / `desktop-vX.Y.Z`).
- A description of the issue and its security impact.
- Reproduction steps or a proof of concept.
- Any suggested remediation, if you have one.

**Never include real credentials, private keys, or production host details** in
a report : redact or use disposable test material.

## What to Expect

- **Acknowledgement:** within ~5 business days.
- **Triage & assessment:** we will confirm the issue, assess severity, and keep
  you informed of progress.
- **Coordinated disclosure:** we will agree on a disclosure timeline with you
  and credit you in the advisory and release notes unless you prefer to remain
  anonymous.

## Scope

**In scope** : the open-core published in this repository:

- `app/` (Android), `desktop/` (Compose Desktop), `shared/` (KMP domain/crypto).
- The vault (key storage, encryption, backup format), SSH connection and
  authentication handling, SSH tunneling, the LAN Sync transport (enrollment,
  pinning, payload encryption), and FIDO2 flows.

**Out of scope:**

- The proprietary **Team server** (LDAP/SSO/PostgreSQL multi-tenant deployment),
  which lives in a separate private repository.
- Issues that require a fully compromised host OS, a rooted/jailbroken device,
  or physical access to an unlocked device.
- The bundled Termux-derived terminal emulator's upstream behavior
  (`terminal/terminal-emulator`, `terminal/terminal-view`), please report those
  to the relevant upstream where applicable.
- Vulnerabilities in third-party dependencies that are already publicly known
  and have an upstream fix pending (we track and bump dependencies separately).

## Security Model (summary)

- Credentials and private keys are encrypted at rest: Android Keystore
  (TEE/StrongBox) on Android; PBKDF2-HMAC-SHA256 (200k) + AES-256-GCM with an
  OS-backed master key (Windows Credential Manager, file fallback elsewhere) on
  Desktop.
- The vault requires an explicit unlock on every launch.
- Secrets are kept in memory only for immediate use and wiped afterward; logs
  are filtered to exclude passwords, keys, and tokens in release builds.
- Host keys are verified on first use (TOFU) with strict rejection on mismatch.
- LAN Sync uses an ECDH-derived, TLS-pinned, HMAC-authenticated, AES-256-GCM
  encrypted transport with anti-replay timestamps.

Thank you for helping keep NextSH and its users safe.
