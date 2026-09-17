# Setting up without root

There are three ways to run this toolkit, from the least to the most involved. Read
[Option 0](#option-0--no-shizuku-no-computer-nothing-to-install) first: if the tweak you want
is on that list, you are done in about fifteen seconds.

| Route | Setup | Covers |
| --- | --- | --- |
| **Option 0** — no Shizuku | One special-access switch | 8 of 39 tweaks, including forcing the max refresh rate |
| **Option A** — Shizuku, on the phone | Wireless debugging, once per reboot | All 39 tweaks |
| **Option B** — adb from a computer | USB cable, a few commands | All 39 tweaks, scriptable |

---

## Option 0 — no Shizuku, no computer, nothing to install

Some settings live in the `system` namespace, and Android lets an ordinary app write that
namespace itself. No shell, no Shizuku, no root, no background process: just one switch.

### 1. Grant *modify system settings*

Open OriginOS Toolkit → **Home → Access** → **Grant modify system settings**, and turn it on.
The same screen is reachable by hand: `Settings → Apps → OriginOS Toolkit → Special access →
Modify system settings`.

### 2. Apply a tweak from that list

In the app, open **Tweaks** and flip on **Works without Shizuku**. The CLI shows the same set:

```console
$ originos-toolkit catalog --no-shizuku
```

| Tweak | What it changes |
| --- | --- |
| `force-max-refresh-rate` | `peak_refresh_rate` / `min_refresh_rate` — the 144 Hz trick |
| `refresh-rate-overlay` | Keeps the live refresh-rate readout in sync |
| `lock-rotation-portrait` | Rotation lock |
| `font-scale-compact` | Font scale |
| `screen-timeout-30s` | Screen-off timeout |
| `disable-adaptive-brightness` | Adaptive brightness |
| `disable-touch-sounds` | Touch and lock sounds |
| `disable-haptics` | System haptic feedback |

### What it does *not* cover

`WRITE_SETTINGS` reaches the `system` namespace and nothing else. `Settings.Secure` and
`Settings.Global` — debloat, phone config, display density, most of the battery and privacy
section — need the `shell` user, so for those continue with Option A or B below.

Every one of these tweaks is journalled and reversible exactly like the rest.

---

## Option A — no computer at all (Android 11 and newer)

The rest of the tweaks go through Shizuku. Shizuku starts a privileged shell **once** — using
Android's own debugging facilities — and then lends that identity to apps you approve. The
identity it lends is the `shell` user (uid 2000), exactly what you get from `adb shell`. No
root, no Magisk, no unlocked bootloader, no warranty concerns.

You have to do this once per reboot of your phone, unless you use the [automation
trick](#keeping-shizuku-alive) at the bottom.

Everything below happens on the phone.

### 1. Unlock Developer options

`Settings → About phone → Software information` → tap **Build number** seven times.
OriginOS will ask for your PIN. (`Settings → System → About phone` on some builds.)

### 2. Turn on Wireless debugging

`Settings → System → Developer options → Wireless debugging` → toggle on.

You must be connected to Wi-Fi. OriginOS keeps this switch independent from "USB
debugging" — you only need wireless.

### 3. Install and start Shizuku

Install [Shizuku](https://github.com/RikkaApps/Shizuku/releases) (available on Google Play
too), open it, and tap **Start** under *Start via Wireless debugging*. Shizuku opens
Android's pairing dialog for you:

1. In the pairing dialog, tap **Pair device with pairing code**.
2. Android shows a 6-digit code and a port.
3. Type the code into Shizuku and confirm.

Shizuku should now say *Shizuku is running*.

> **OriginOS detail:** if the pairing dialog keeps disappearing, disable
> *Developer options → Disable permission monitoring* first, then retry. On some OriginOS 5
> builds you also need to keep the Wireless debugging screen open while pairing.

### 4. Grant the permission to OriginOS Toolkit

Open OriginOS Toolkit. The **Home** tab shows a red *Shizuku* card. Tap **Grant access**, then
allow it in the dialog Shizuku shows. The card turns into a green *Ready* message.

If Shizuku is running but the app still cannot write, check
`Settings → Apps → OriginOS Toolkit → Permissions` and make sure nothing was auto-revoked —
OriginOS's permission manager is more aggressive than AOSP's.

---

## Option B — one-time ADB from a computer

Use this if Wireless debugging is blocked, or if pairing refuses to work.

1. Enable `Developer options → USB debugging` and plug the phone into a computer with
   [Platform Tools](https://developer.android.com/tools/releases/platform-tools) installed.
2. Accept the *Allow USB debugging* prompt on the phone.
3. Start Shizuku from the computer:

   ```console
   $ adb shell sh /storage/emulated/0/Android/data/moe.shizuku.privileged.api/start.sh
   ```

   The path is created by Shizuku; run the command once and Shizuku remembers how it was
   started.
4. Unplug. Shizuku keeps running until the phone reboots.

You can also do the whole thing over Wi-Fi ADB:

```console
$ adb tcpip 5555
$ adb connect <phone-ip>:5555
$ adb shell sh /storage/emulated/0/Android/data/moe.shizuku.privileged.api/start.sh
```

---

## What the toolkit needs besides Shizuku

| Feature | Extra grant | Where |
| --- | --- | --- |
| Everything in **Tweaks**, **Debloat**, profiles | Shizuku | App dialog |
| **FPS overlay** | Display over other apps | `Settings → Apps → Special access → Display over other apps` |
| **Per-app refresh rate** | Usage access | `Settings → Privacy → Special access → Usage access` |

Both of the extra grants are only needed for the feature that names them. The app says so
instead of failing silently: tapping a switch without the grant opens the right system
screen.

---

## Keeping Shizuku alive

Shizuku stops when the phone reboots, and OriginOS's memory manager is happy to kill it
mid-session. Two things help a lot:

1. **Exempt Shizuku from battery optimisation.** `Settings → Battery → Background power
   consumption management → Shizuku → Allow` (the exact wording moves between OriginOS
   versions). Without this, Shizuku is often killed within minutes on OriginOS 4 and 5.
2. **Lock Shizuku in Recents.** Open Recents, swipe Shizuku down slightly (or tap its
   menu), and choose the padlock/"lock" action OriginOS shows. That prevents the automatic
   clear-memory sweep from taking it out.

Do the same for **OriginOS Toolkit** if you use the per-app refresh watcher: it is a
foreground service with a notification, but OriginOS still tries to be clever.

If Shizuku dies while the watcher is running, the watcher's notification changes to
*Shizuku unavailable* and it stops writing rates rather than flapping values it cannot read.

---

## Troubleshooting

| Symptom | Cause and fix |
| --- | --- |
| Shizuku says *running* but the app says *not authorised* | The grant is per-app and per-boot. Tap **Grant access** again. |
| `Grant access` does nothing | Shizuku is not running yet — the app cannot request a permission from a dead binder. Start Shizuku first. |
| Everything worked before a reboot | Expected. Restart Shizuku (Option A or B), then re-grant. |
| Pairing code dialog closes immediately | Keep the Wireless debugging screen in the foreground while pairing; disable *Disable permission monitoring* if present. |
| Tweaks apply but nothing changes | Some settings need a screen off/on cycle, or the app you were looking at must be restarted. The FPS overlay and `refresh-rate-overlay` are the ground truth — turn one on. |
| "adbd cannot run as root" from ADB tools | Irrelevant here: this toolkit never asks for root. Ignore it. |
| The UI looks cramped after a density tweak | **Settings → Panic reset** restores factory density and window size, then reverts everything else. |

---

## Uninstalling

Nothing this toolkit does survives uninstalling it:

1. **Settings → Revert journal → Revert everything** (or `originos-toolkit revert --all`).
2. Re-enable any packages you disabled: **Debloat → tap each disabled package**, or
   `originos-toolkit debloat --enable`.
3. Uninstall the app. Shizuku can be stopped or uninstalled too.

If the app is already gone and you want the system back to default:

```console
$ originos-toolkit revert --all      # if the journal is still on the phone, use the app
$ adb shell settings delete system peak_refresh_rate
$ adb shell settings delete system min_refresh_rate
$ adb shell wm density reset
$ adb shell wm size reset
```

Those four commands undo the only changes that are noticeable if you forget to revert.
