<div align="center">

# OriginOS Toolkit

**No-root tweaks, per-app refresh rate, debloat and a curated no-root tool index —
for Vivo / iQOO phones running OriginOS (FuntouchOS works too).**

[![Android](https://github.com/cameleonnbss/originos-toolkit/actions/workflows/android.yml/badge.svg)](https://github.com/cameleonnbss/originos-toolkit/actions/workflows/android.yml)
[![CLI & catalog](https://github.com/cameleonnbss/originos-toolkit/actions/workflows/cli.yml/badge.svg)](https://github.com/cameleonnbss/originos-toolkit/actions/workflows/cli.yml)
[![Release](https://img.shields.io/github/v/release/cameleonnbss/originos-toolkit?include_prereleases&sort=semver)](https://github.com/cameleonnbss/originos-toolkit/releases)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
![No root](https://img.shields.io/badge/root-not%20required-brightgreen)
![No telemetry](https://img.shields.io/badge/telemetry-none-brightgreen)
![Platform](https://img.shields.io/badge/Android-10%2B%20(API%2029%2B)-informational)

[Features](#features) · [Quick start](#quick-start) · [No-root setup](#no-root-setup-in-5-minutes) ·
[The revert guarantee](#the-revert-guarantee) · [CLI](#command-line) · [Catalog format](#catalog-format) ·
[Build](#build-from-source) · [FAQ](docs/FAQ.md) · [Awesome list](docs/AWESOME-ORIGINOS.md) ·
[The look](docs/ORIGINOS-LOOK.md)

</div>

---

## What this is

OriginOS hides a lot of behaviour behind a whitelist. The panel does 144 Hz, but your
game is clamped to 60. Wi-Fi keeps scanning for location in the background. The Vivo
browser and its push notifications run whether you asked for them or not. The settings
that control all of this exist — they are just not exposed, and the usual way to reach
them is root.

**OriginOS Toolkit reaches them without root.** Every change goes through
[Shizuku](https://github.com/RikkaApps/Shizuku), which runs commands as the `shell` user —
the same identity `adb shell` gives you. That is *less* privileged than root: it cannot
read `/data/data`, cannot mount filesystems, and cannot touch verified boot.

Three things make it more than a wrapper around `settings put`:

1. **Nothing is written before its previous value is read and stored.** Reverting restores
   the exact state — including "this key did not exist before".
2. **The catalog is data, not code.** 39 tweaks, 6 profiles and 35 curated tools live in
   [`catalog/*.json`](catalog/), shared verbatim by the Android app and the CLI, and linted
   in CI. A tweak without a documented inverse fails the build.
3. **There is no network permission.** The app cannot phone home, because it cannot reach
   the network at all.

> **You do not need a computer after setup.** Shizuku can be started from Android's own
> wireless debugging — no ADB, no cable, no root.

---

## Features

### Per-app refresh rate — the headline feature
OriginOS decides for you which apps may run above 60/90 Hz. This watcher decides instead:
pick a rate per app (or a default for everything), and it pins `peak_refresh_rate` /
`min_refresh_rate` while that app is in the foreground, restoring your original values the
moment you leave it. Rates are read from the panel itself, so 120/144/165 Hz all work on
whichever device you have.

### 39 reversible tweaks across 8 categories
| Category | What is in it |
| --- | --- |
| **Display & refresh rate** | Force max refresh rate, refresh-rate overlay, dark mode, animation speed, rotation lock, font scale, density |
| **Performance & gaming** | GPU rendering, disable window blurs, larger cached-process pool |
| **Battery & power** | Stop Wi-Fi/BT background scanning, disable mobile-data-always-on, App Standby, saver threshold, NFC |
| **Privacy & network** | Ad-tracking opt-out, private DNS with ad blocking, lock-screen notification privacy |
| **Debloat** | Vivo Store/Browser, Vivo analytics, Game Center, Google extras — reversible, per user |
| **Navigation, audio & haptics** | Gesture navigation, extra-wide back gesture, immersive-mode prompts, click sounds, haptics |
| **System & developer** | Developer options, wireless-debugging flag, keep-awake while charging, cache trimming |
| **Experimental** | Fixed performance mode, forced Doze, SurfaceFlinger sampling — flagged, opt-in, unverified |

Full command-level reference: **[docs/TWEAKS.md](docs/TWEAKS.md)** (generated from the catalog).

### One-click profiles
`daily-balanced` · `gaming-max` · `battery-max` · `privacy-hardened` · `clean-slate` ·
`smooth-navigation`. No profile bundles a high-risk tweak, and every one can be previewed
command-by-command before it runs.

### Debloat that cannot brick anything
`pm disable-user --user 0`, never `pm uninstall`: the APK stays on the read-only partition,
the data stays put, and re-enabling restores everything. The app shows what is installed,
what is disabled, and what each package does.

### A curated *no-root* index, built in
The **Discover** screen ships the [Awesome OriginOS](docs/AWESOME-ORIGINOS.md) list — 35
tools, each labelled `NO-ROOT`, `ADB`, `SHIZUKU` or `ROOT`, so nobody installs a rooted
Magisk module by accident.

### Home-screen components, cut like the system's own

Two *atomic components* — OriginOS's word for a home-screen widget — ship with the app:

- a **2×2 refresh-rate tile** that shows the panel maximum and pins or releases
  `force-max-refresh-rate` with one tap, and
- a **4×2 status tile** with the model, the Android version, the density, the panel rate, the
  number of applied tweaks and the access level the app is running with.

The refresh tile is a second door into the same engine, not a shortcut around it: the change
is read, journalled and only then written, exactly as in the app, so a tap on the home screen
reverts from **Settings → Revert journal** like anything else. Both are `RemoteViews` — no
Glance, no extra dependency, no new permission — and they read only what an unprivileged app
can read, so a widget never makes Shizuku wake up just to draw a label.

They are shaped the way the system shapes its own components, which is measured rather than
copied by eye: see **[docs/ORIGINOS-LOOK.md](docs/ORIGINOS-LOOK.md)** for the plates, the pixel
measurements, and the one part of the look a widget cannot have (a `RemoteViews` panel carries
a shape drawable, never a path, so its corner is circular where the app's is G2).

### Works without Shizuku

8 of the 39 tweaks never need the `shell` user. They write the `system` settings namespace,
which an ordinary app may write as soon as you grant *modify system settings* — one switch in
Android Settings, nothing else to install:
*Force maximum refresh rate*, *Refresh-rate overlay*, *Lock rotation*, *Font scale*,
*Screen timeout*, *Adaptive brightness*, *Touch and lock sounds* and *Haptic feedback*.

They apply and revert like any other tweak, they are journalled the same way, and the
**Tweaks** screen has a *Works without Shizuku* filter that shows exactly this set.
Everything else — debloat, phone config, display density — needs the shell user, either
through [Shizuku](https://github.com/RikkaApps/Shizuku) on the phone or through the generated
adb script from a computer.

### An honest FPS overlay
A floating readout that does not lie about what it measures. The number comes from
`SurfaceFlinger`'s own present timestamps for the app in front — the real frame rate, plus a
**1% low** — not from counting vsync callbacks and calling the result "fps". When the shell
user is not available it shows the panel refresh rate and says so, instead of inventing a
frame rate it cannot see.

### A revert journal, and a panic button
Everything applied is listed in **Settings → Revert journal** with the exact inverse that was
captured before the change. **Panic reset** restores factory display density and window size
first, then replays the whole journal backwards — reachable even if a bad density made the
UI awkward.

---

## Quick start

### The app

1. Grab the APK from [Releases](https://github.com/cameleonnbss/originos-toolkit/releases)
   (`originos-toolkit-*-debug.apk` installs directly).
2. On **Home → Access**, tap **Grant modify system settings**. That is the whole setup for the
   8 Shizuku-free tweaks, including forcing the maximum refresh rate.
3. Optional, for everything else: install and start [Shizuku](https://github.com/RikkaApps/Shizuku),
   then grant this app the Shizuku permission.
4. Open the **Tweaks** tab, hit **Commands** on anything you are unsure about, then **Apply**.

The **Home** tab tells you exactly what it detected: model, OriginOS build, current density
and the refresh rate in use right now.

### The command line

```console
$ pip install -e cli/          # or run it with PYTHONPATH=cli python -m originos_toolkit
$ originos-toolkit doctor      # checks adb, device, Shizuku, shell access
$ originos-toolkit catalog --no-shizuku   # what works without Shizuku: 8 of 39
$ originos-toolkit apply force-max-refresh-rate --dry-run
$ originos-toolkit apply gaming-max
$ originos-toolkit revert --all
```

No Shizuku and no phone? Everything still works as a generator:

```console
$ originos-toolkit export gaming-max --format sh -o apply-gaming.sh
$ originos-toolkit show force-max-refresh-rate --commands
```

---

## No-root setup in 5 minutes

The short version — the full walkthrough with screenshots of the Android menus is in
**[docs/NO-ROOT-SETUP.md](docs/NO-ROOT-SETUP.md)**.

**Option A — no computer at all (Android 11+)**

1. Settings → About phone → tap *Build number* 7 times to unlock Developer options.
2. Developer options → **Wireless debugging** → on.
3. Install Shizuku, open it, choose **Start via Wireless debugging**, and follow its pairing
   prompt (it opens the pairing dialog for you).
4. Back in OriginOS Toolkit, tap **Grant access**.

**Option B — one-time ADB from a computer**

```console
$ adb shell sh /storage/emulated/0/Android/data/moe.shizuku.privileged.api/start.sh
```

Either way, Shizuku has to be restarted after a reboot; the app tells you when it is not
running instead of failing silently.

---

## The revert guarantee

This is the part worth reading before you trust the app with your phone.

```
1. read     settings get system peak_refresh_rate     →  "60.0"
2. journal  {"tweakId": "force-max-refresh-rate",
             "inverse": [{"op":"settings_put", ... "value":"60.0"}]}      ← written to disk
3. write    settings put system peak_refresh_rate 144.0                 ← only now
```

Because the inverse is persisted **before** the first write, a revert is exact even if:

- the phone reboots between applying and reverting;
- the app is killed, updated or reinstalled;
- **the catalog later changes what the tweak does** — the journal wins, not the catalog.

And when undoing would be wrong, the engine refuses. If a package was already disabled
before you touched it, reverting the debloat bundle leaves it disabled rather than
"helpfully" re-enabling something you had deliberately turned off. The same goes for
overlays, and for settings keys that did not exist before — those are deleted again rather
than set to a made-up default.

Both engines (Python and Kotlin) implement this identically, and both are tested for it:

```console
$ cd cli && python -m unittest discover -s tests -t .    # 128 tests
$ gradle testDebugUnitTest                               # the same invariants in Kotlin
```

---

## Safety model

| Guarantee | How it is enforced |
| --- | --- |
| No root, ever | Only Shizuku's `shell` identity is used; there is no `su` call anywhere in the tree |
| No over-broad settings access | `WRITE_SETTINGS` reaches the `system` namespace only. `Settings.Secure` and `Settings.Global` — the ones that matter for device state — remain out of reach and go through Shizuku instead. `WRITE_SECURE_SETTINGS` is not requested at all |
| No lying numbers | The FPS overlay reads SurfaceFlinger present timestamps, and falls back to reporting the refresh rate by name when it cannot see frames |
| No silent privilege growth | CI fails if a new `uses-permission` appears that is not listed in [docs/PERMISSIONS.md](docs/PERMISSIONS.md) |
| No telemetry | The manifest has no `INTERNET` permission. None. |
| No fake tweaks | Tweaks that are not verified on real hardware are marked `UNVERIFIED` and kept in an opt-in category |
| No data loss | Debloat disables packages; it never uninstalls them |
| No permanent modification | Nothing writes to `/system`, `/vendor` or the boot image |

What it cannot protect you from, and what is explicitly out of scope, is documented in
[SECURITY.md](SECURITY.md).

---

## Command line

The CLI is the reference implementation of the engine: no dependencies beyond Python 3.9+
and an `adb` binary.

| Command | What it does |
| --- | --- |
| `doctor` | Checks adb, the device, Shizuku and shell access |
| `info` | Model, OriginOS build, density, resolution, refresh-rate settings |
| `catalog` | Lists every tweak (`--category`, `--risk`, `--json`, `--no-shizuku`) |
| `show <id>` | Full description; `--commands` prints the exact ADB lines |
| `profiles` | Lists the six bundles |
| `apply <id…>` | Applies tweaks and/or profiles (`--dry-run`, `--yes`, `--force`, `--allow-experimental`) |
| `revert <id…>` | Restores from the journal (`--all`, `--dry-run`) |
| `status` | Shows which tweaks look applied on the device |
| `debloat` | Inspects and disables/re-enables OEM packages |
| `awesome` | Browses the curated list (`--section`, `--json`, `--root-only`) |
| `export <id…>` | Writes a standalone `sh`/`ps1` ADB script |
| `lint` | Validates the catalog (what CI runs) |

Details and environment variables: [`cli/README.md`](cli/README.md).

---

## Catalog format

A tweak is data. This is the whole of one:

```json
{
  "id": "force-max-refresh-rate",
  "name": "Force maximum refresh rate (120 / 144 / 165 Hz)",
  "category": "display",
  "risk": "low",
  "requires": "shizuku",
  "verified": true,
  "originOs": ["originos4", "originos5", "originos6"],
  "summary": "Pins Android's peak and minimum refresh rate to the panel maximum…",
  "details": "OriginOS ships a per-app high-refresh whitelist…",
  "actions": [
    { "op": "settings_put", "ns": "system", "key": "peak_refresh_rate", "value": "144.0" },
    { "op": "settings_put", "ns": "system", "key": "min_refresh_rate", "value": "144.0" }
  ]
}
```

There is no `revert` field because the engine derives one: it reads the previous value of
every key first and rebuilds the inverse from that. Ops that *cannot* be inverted
(`shell`, `cmd`) must either declare an explicit `revert` array or set
`"reversible": false` — and `originos-toolkit lint` rejects the catalog otherwise.

Adding a tweak means editing JSON, running `lint`, and regenerating the docs:

```console
$ python -m originos_toolkit.cli lint          # from cli/, or PYTHONPATH=cli
$ python scripts/generate_docs.py
$ cd cli && python -m unittest discover -s tests -t .
```

The documented op set, the auto-inverse rules and the risk levels are described in
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

---

## Repository layout

```
catalog/            tweaks.json · profiles.json · awesome.json   ← single source of truth
app/                Android app (Kotlin, Jetpack Compose, Shizuku)
  core/             models, catalog parser, op layer, engine, journal
  service/          per-app refresh watcher, FPS overlay
  ui/               Compose screens and view model
  src/test/         JVM tests mirroring the Python suite
cli/                Python CLI (stdlib only) + 128 unit tests
docs/               TWEAKS.md · AWESOME-ORIGINOS.md · NO-ROOT-SETUP.md · ARCHITECTURE.md ·
                    ORIGINOS-LOOK.md · …
scripts/            generate_docs.py — keeps the docs honest; design-probe.html — the
                    corner measurements behind docs/ORIGINOS-LOOK.md
.github/workflows/  Android build/test/lint/release · catalog + CLI tests
```

The catalog is wired into the APK through Gradle's `assets.srcDirs`, so the app and the CLI
read the same bytes. There is exactly one copy of every definition.

---

## Build from source

```console
$ git clone https://github.com/cameleonnbss/originos-toolkit
$ cd originos-toolkit
$ gradle assembleDebug          # or open the folder in Android Studio
$ adb install app/build/outputs/apk/debug/app-debug.apk
```

Requirements: JDK 17, Android SDK 35. There is deliberately no committed Gradle wrapper
jar — CI provisions Gradle itself, and if you want a wrapper locally:

```console
$ gradle wrapper --gradle-version 8.9
```

> **Windows:** build from a path that is pure ASCII and free of spaces. Gradle passes its test
> worker classpath through an `@argfile`, and a non-ASCII character anywhere in that path (an
> accented user name, for instance) makes every test worker die with
> `ClassNotFoundException: GradleWorkerMain`. See [CONTRIBUTING.md](CONTRIBUTING.md#troubleshooting-the-local-build).

Run everything CI runs:

```console
$ python scripts/generate_docs.py --check
$ cd cli && python -m unittest discover -s tests -t . -v
$ cd .. && gradle testDebugUnitTest lint assembleDebug
```

---

## Credits

This project exists because of work other people published first. The
[Awesome OriginOS](docs/AWESOME-ORIGINOS.md) list gives each of them a proper entry; the
ones that shaped the design are:

- **[RikkaApps/Shizuku](https://github.com/RikkaApps/Shizuku)** — the entire no-root ecosystem
  rests on it.
- **[ewfawfasdf/VivoIQOO144FPSUnlocker](https://github.com/ewfawfasdf/VivoIQOO144FPSUnlocker)**
  — the proof that the 144 Hz whitelist is reachable without root.
- **[Astreas-Core/otweak](https://github.com/Astreas-Core/otweak)** — a clean example of
  layering ADB commands behind a Kotlin UI.
- **[0x192/universal-android-debloater](https://github.com/0x192/universal-android-debloater)**
  and **[MuntashirAkon/AppManager](https://github.com/MuntashirAkon/AppManager)** — the
  benchmark for safe, reversible package management.
- **[timschneeb/awesome-shizuku](https://github.com/timschneeb/awesome-shizuku)** — the map of
  what Shizuku can do.

---

## Contributing

Bug reports with a `logcat` and your OriginOS build are the most valuable thing you can
send. Tweaks must arrive with a documented inverse and a targeted device to test on — see
[CONTRIBUTING.md](CONTRIBUTING.md) and the [Code of Conduct](CODE_OF_CONDUCT.md).

## License

[MIT](LICENSE). Not affiliated with Vivo, iQOO, OriginOS or FuntouchOS.

**Tweaks change device behaviour.** Everything here is reversible and nothing requires root,
but you are responsible for your own phone: read the `details` on a tweak, use
`--dry-run`/`Commands` first, and start with a profile rather than the whole catalog.
