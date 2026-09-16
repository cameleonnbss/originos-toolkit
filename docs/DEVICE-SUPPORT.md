# Device support

## Requirements

| Requirement | Minimum | Why |
| --- | --- | --- |
| Android | 10 (API 29) | `UsageStatsManager` activity events, `AppOpsManager.unsafeCheckOpNoThrow`, adaptive icons |
| Architecture | any | Pure Kotlin, no native code |
| Shizuku | 13.x | Binder API version 23 |
| Root | **not required** | — |

## Tested targets

The catalog labels each tweak with the OriginOS versions it is expected to work on
(`originOs` field), and `verified: true` means it has been confirmed on hardware rather than
inferred from the Android source.

| ROM | Status |
| --- | --- |
| OriginOS 6 (Android 16) | Expected to work as `originos6`; the generic Android tweaks are the same as 5 |
| OriginOS 5 (Android 15) | Primary target — 36 of 39 tweaks are marked verified against this generation |
| OriginOS 4 (Android 14) | Supported |
| OriginOS 3 (Android 13) | Supported for most tweaks; `disable-window-blurs` and `max-cached-processes` need Android 13+ |
| OriginOS 2 (Android 12) | Supported for the generic tweaks; some display entries are 13+ |
| FuntouchOS 13–15 | Best effort — the same settings namespace, a different skin |
| Other Android 10+ ROMs | The generic tweaks are plain AOSP surfaces; the OriginOS-specific notes do not apply |

iQOO devices are Vivo devices: they report the same `ro.vivo.*` properties and are covered by
the same `originosN` labels.

## What is device-dependent

These do not have a single right answer, which is why they are handled dynamically or marked
experimental:

| Tweak | Why it varies |
| --- | --- |
| `force-max-refresh-rate` | The panel maximum differs (120 / 144 / 165 Hz). Set the value your panel actually reports — the dashboard and `docs/TWEAKS.md` list every supported rate. |
| `density-compact` | Uses a **relative** −10 % delta computed from your current density, never a hard-coded number. |
| `fixed-performance-mode` | A real AOSP command, but many OEM kernels ignore it entirely. Marked **UNVERIFIED**. |
| `doze-force-idle` | Works everywhere; the visible effect depends on the OEM power stack. Marked **UNVERIFIED**. |
| `record-fps-sample` | `dumpsys SurfaceFlinger --latency` output format differs between builds, and some builds restrict it without root. Marked **UNVERIFIED**. |
| `debloat-*` | Package names are stable across Vivo builds, but a missing package is skipped rather than failing the bundle. |

## Reporting a result

A one-line issue is genuinely useful. The most helpful report includes:

1. Device model and `ro.vivo.os.version` (the app shows both on **Home**).
2. The tweak id, and what you expected versus what happened.
3. The command output — the app shows it in the result dialog, and a `Commands` preview is
   the fastest way to see what was attempted.

Reports that confirm a tweak on a new ROM get the entry promoted to `verified: true` and the
OriginOS version added to its list. Reports that find a broken tweak get it demoted,
annotated, or removed — see the [issue template](../.github/ISSUE_TEMPLATE/bug_report.yml).

## Devices that are known to need extra steps

- **OriginOS 4/5 with aggressive background management** — exempt Shizuku (and this app, if
  you use the per-app watcher) from battery optimisation, or Shizuku gets killed mid-session.
  See [NO-ROOT-SETUP.md](NO-ROOT-SETUP.md#keeping-shizuku-alive).
- **Builds with "Disable permission monitoring"** in Developer options — that switch can make
  the wireless-debugging pairing dialog misbehave. Turn it off while pairing.
- **Multi-user or work-profile devices** — every op is scoped to `--user 0`. The toolkit
  intentionally does not touch other users' packages.
