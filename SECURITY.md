# Security Policy

## Supported versions

| Version | Supported |
| ------- | --------- |
| 1.x     | ✅        |

## Design guarantees

OriginOS Toolkit is a **no-root** project. It never:

- escalates to UID 0, patches `init`, or touches the boot image;
- writes to `/system`, `/vendor`, or any read-only partition;
- ships an exploit, a privilege-escalation payload, or a bundled su binary;
- phones home. There is **no analytics, no telemetry and no account**.

Every privileged action goes through one of two documented, user-consented channels:

1. **Shizuku** — the user installs Shizuku, starts it over wireless debugging or ADB, and
   explicitly grants this app the `Shizuku` permission. Commands then run as the `shell`
   user (UID 2000), exactly like `adb shell`.
2. **ADB from a computer** — the user runs the generated commands over USB/Wi-Fi ADB.
   The `shell` UID is *less* privileged than root: it cannot read `/data/data`, cannot
   mount filesystems, and cannot modify verified boot state.

Every tweak in [`catalog/tweaks.json`](catalog/tweaks.json) declares an explicit inverse
operation, so anything applied can be reverted from inside the app (`Settings → Journal →
Revert all`).

## Reporting a vulnerability

Open a private security advisory:
<https://github.com/cameleonnbss/originos-toolkit/security/advisories/new>

Please include the app version, your device/OriginOS build, and a `logcat` excerpt. Expect
an acknowledgement within 7 days.

Please **do not** open a public issue for:
- anything that could be weaponised against other users' devices;
- a catalog entry that a normal user could turn into a bootloop (report it, we pull it);
- a Shizuku permission bypass.

## Threat model (what we do not defend against)

- A malicious **Shizuku** install or a hostile ADB host on your network.
- Another app on your device holding the same `WRITE_SECURE_SETTINGS` grant via Shizuku.
- A user pasting arbitrary `shell` commands from the internet. The `shell` op exists for
  power users; it is opt-in behind *Enable experimental tweaks*, and the app shows the exact
  command string before running it.

## Hard rules for contributors

- No pull request may add a root exploit, a Magisk module, or a payload download.
- No tweak may be added without a documented inverse operation.
- No new network permission. The app's manifest is locked to a minimal permission set and
  CI fails the build if that set grows (`.github/workflows/android.yml` step *manifest guard*).
