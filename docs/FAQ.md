# FAQ

### Does this need root?

No. It never calls `su` and never writes to a system partition. It sends commands through
[Shizuku](https://github.com/RikkaApps/Shizuku), which runs them as the `shell` user — the
same identity `adb shell` gives you. That is less privileged than root: shell cannot read
`/data/data`, cannot mount filesystems, cannot touch verified boot.

### Will it void my warranty or trip SafetyNet / Play Integrity?

No. Nothing here unlocks the bootloader, patches the boot image or modifies `/system`. The
changes are ordinary settings, package states and display metrics — the same things the
Settings app changes. Your device still reports as unmodified.

### Is this the same as the Vivo/iQOO 144 FPS unlockers?

Same problem, different approach. Those apps force the refresh rate globally; this one gives
you a **per-app** choice with automatic restore, and wraps the whole thing in a catalog where
every entry declares how it is undone. The 144 Hz unlock is one tweak of 39. Credit where it
is due: [VivoIQOO144FPSUnlocker](https://github.com/ewfawfasdf/VivoIQOO144FPSUnlocker) showed
the community that the whitelist is reachable without root.

### Why does a tweak stay "applied" after I restart the phone?

Because it is a persisted setting — that is the point. The refresh rate, animation scales and
disabled packages survive reboots. The *Shizuku session* does not, which is why the app shows
a red Shizuku card after every reboot: re-grant access and the tweaks are still in place.

### It says "already applied" and refuses to run again. Is that a bug?

It is deliberate. Re-applying would snapshot the *already modified* value and quietly turn a
future revert into a no-op. Use **Re-apply** (or `--force`) when you really mean it, accepting
that the journal is rewritten from the current state.

### Can I see what a tweak will do before it runs?

Yes, twice over:

- **Commands** in the app (or `originos-toolkit apply <id> --dry-run`) prints every command
  without touching the device.
- `originos-toolkit export <id> --format sh` writes a standalone script you can read, keep,
  or run yourself from a computer.

`--dry-run` also generates no journal entries, because nothing happened.

### What happens if I apply a bad density?

**Settings → Panic reset** puts density and window size back to factory values first, then
replays the journal in reverse. It is intentionally the first thing on that screen. From a
computer: `adb shell wm density reset && adb shell wm size reset`.

### Are there tweaks you refuse to ship?

Yes:

- Anything requiring root, Magisk or a kernel patch — that is a different project.
- `pm uninstall --user 0`. Disabling is reversible; uninstalling loses app data.
- Tweaks whose only evidence is "someone said it feels faster on my phone".
- Anything without a documented inverse that is not explicitly declared one-shot.
- Any tweak that changes the device's security posture (locking, SELinux, verified boot).

### Why are three tweaks marked UNVERIFIED?

Because nobody has confirmed them on real hardware yet. `fixed-performance-mode` is a real
AOSP command that most OEM kernels ignore; `doze-force-idle` and the SurfaceFlinger sample
behave differently across OriginOS builds. They sit in an opt-in **Experimental** category
rather than hiding among the verified ones. If you test one, say so in an issue and it gets
promoted.

### Does the app send anything anywhere?

There is no `INTERNET` permission in the manifest, so it physically cannot. No analytics, no
crash reporting, no update check. Licences and links open in your browser instead.

### Does it work on FuntouchOS or on a non-Vivo phone?

Yes. Detection is based on `ro.vivo.os.version`; when that is missing the app says so and
originally-OriginOS notes are ignored. Every tweak is plain Android — `settings`,
`device_config`, RRO overlays, package states — so it works on any phone running Android 10+
with Shizuku available. The per-app refresh watcher will pin rates on any panel that supports
them.

### Why is there no Play Store release?

Several reasons, all boring and all about honesty: `QUERY_ALL_PACKAGES` plus overlay plus
usage-access together are a policy minefield; the app's entire purpose is to change system
settings, which Play discourages; and a sideloaded APK published as a GitHub Release can
explain itself in a README instead of a 4000-character description. The release APK is
signed, open, and reproducible from this repository.

### How do I contribute a tweak?

Add it to `catalog/tweaks.json`, make sure it has an inverse (or declare
`"reversible": false` with a reason in `details`), then run:

```console
$ python -m originos_toolkit.cli lint
$ python scripts/generate_docs.py
$ cd cli && python -m unittest discover -s tests -t .
```

CI runs the same three commands, plus a Kotlin test suite that asserts the same safety
properties. See [CONTRIBUTING.md](../CONTRIBUTING.md).
