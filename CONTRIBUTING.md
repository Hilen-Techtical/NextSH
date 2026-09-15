# Contributing to NextSH

Thanks for considering a contribution! NextSH is an open-source SSH client
under active development. This guide covers how to build the project
locally, our coding conventions, and the contribution workflow.

## Scope

Contributions are welcome on the **open-core** components:

- `app/` : Android client
- `desktop/` : Compose Multiplatform Desktop client
- `shared/` : Kotlin Multiplatform shared domain
- `terminal/` : Termux fork (Apache 2.0 preserve upstream copyright)

The **Team server** (LDAP/SSO/PostgreSQL multi-tenant deployment) is
proprietary and lives in a separate private repository. External pull
requests for that module will not be accepted.

## Prerequisites

- **JDK 17 or 21** (full JDK with `jpackage.exe`, e.g. Eclipse Temurin 21
  LTS or BellSoft Liberica 21). Android Studio's bundled JBR works for
  Android compilation but **does not** include `jpackage`, so Desktop
  packaging requires a separate JDK.
- **Android Studio Iguana** (2023.2.1) or newer for the Android module.
- **Gradle 8.7+** (use the bundled `gradlew`).
- **ImageMagick** : only required if you regenerate the icon assets from
  the source logo (kept in the internal design repository; not needed for
  a normal build). Install via `winget install ImageMagick.ImageMagick`
  on Windows.

Set `JAVA_HOME` before running Gradle. On Windows:

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.5.11-hotspot"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
```

## Build

### Android

```bash
./gradlew assembleDebug         # debug APK in app/build/outputs/apk/debug/
./gradlew assembleRelease       # release APK
./gradlew :app:test             # 412 unit tests
./gradlew :app:lint
./gradlew installDebug          # install on connected device
```

### Desktop (Windows)

```bash
./gradlew :desktop:packageMsiWithResources    # MSI installer
./gradlew :desktop:packageExeWithResources    # EXE installer (self-extracting, wraps the same MSI)
./gradlew :desktop:renameWindowsArtifacts     # canonical filenames (depends on both tasks above)
./gradlew :desktop:packagePortableWindows     # portable ZIP with embedded JRE
```

> Do **not** use Compose Desktop's own `packageMsi` / `packageExe`: they
> overwrite jpackage's `--resource-dir`, so the resulting installer ships
> without the NextSH wizard branding and without the
> close-the-running-app-on-upgrade actions defined in
> `desktop/installer/windows/jpackage/main.wxs`.

### Desktop (Linux - Ubuntu 22.04+ / Debian / WSL2)

```bash
./gradlew :desktop:fixDebDesktopEntry         # .deb (Debian/Ubuntu), incl. taskbar-icon fix
./gradlew :desktop:packageRealAppImage        # single-file .AppImage
./gradlew :desktop:renameLinuxArtifacts       # canonical names → release-artifacts/
```

### Tests

```bash
./gradlew :app:test                # Android unit tests (412)
./gradlew :shared:desktopTest      # KMP shared (CRDT, ECDH, codecs)
./gradlew :desktop:desktopTest     # Desktop (LAN sync server, vault, tunnels)
```

## Continuous integration

Every pull request is gated by GitHub Actions (`.github/workflows/ci.yml`):

- the committed Gradle wrapper is integrity-checked
  (`gradle/actions/wrapper-validation`),
- `:app` is assembled, unit-tested, and linted (debug variant),
- `:shared` and `:desktop` JVM tests run (`desktopTest`, under a virtual display).

CI is **debug-only**, no signing keys are used or needed. Release artifacts
(signed APK, MSI/EXE, AppImage/deb) are built and signed internally. Please make
sure these checks pass locally before opening a PR.

## Workflow

1. **Branch from `main`**.
   Format: `feature/xxx`, `fix/xxx`, `sec/xxx`, `chore/xxx`. Never commit
   directly to `main`.
2. **Conventional Commits**.
   - `feat(scope): description`
   - `fix(scope): description`
   - `sec(scope): description` for security fixes
   - `chore(scope): description` for maintenance/refactor/docs
   - `test(scope): description`
3. **Atomic commits**. One logical change per commit.
4. **Pull request** to `main` with a clear description. The maintainer
   will review and merge.

## Coding conventions

- Classes: `PascalCase`. Functions: `camelCase`. Constants:
  `SCREAMING_SNAKE_CASE`.
- Coroutines: `viewModelScope` for UI; `supervisorScope` for SSH
  (isolation of errors).
- SSH errors: sealed class `SshResult<T>` never let exceptions reach
  the UI.
- `:shared` module: **no Android SDK dependencies**. Code shared between
  Android and Desktop JVM should live in the `jvmCommon` source set.

## Security guidelines

- **Zero credentials in source code.** Use environment variables or vault
  storage.
- **Zero sensitive data in logs.** Passwords, tokens, private keys must
  never appear in Timber output (release `ReleaseTree` filters these).
- Use `CharArray` instead of `String` for credentials, and wipe with
  `Arrays.fill(buffer, '\0')` after use.
- Validate and sanitize all input at trust boundaries (UI, SSH responses,
  sync payloads).
- New dependencies must be justified, up-to-date, and have an
  MIT-compatible license. Update `NOTICE.md` when adding/removing a
  dependency.

## License

By contributing, you agree that your contributions will be licensed under
the **MIT License** (see [LICENSE](LICENSE)). The Termux-derived terminal
emulator retains its Apache License 2.0.
