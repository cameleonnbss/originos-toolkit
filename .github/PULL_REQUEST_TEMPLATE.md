# What and why

<!-- One paragraph on the problem. The diff shows the *what*; explain the *why*. -->

## Type

- [ ] New tweak(s) in `catalog/tweaks.json`
- [ ] New curated entry in `catalog/awesome.json`
- [ ] New profile
- [ ] Engine change (apply / revert / probe / journal)
- [ ] Android app
- [ ] CLI
- [ ] Documentation
- [ ] CI or build

## The safety checklist

<!-- These are the properties this project exists for. Please tick honestly. -->

- [ ] Every new tweak **can be undone** — either the engine derives the inverse from the
      previous value, or I wrote an explicit `revert` array, or I set
      `"reversible": false` with the reason in `details`.
- [ ] `make lint` passes (it rejects an undeclared one-shot tweak).
- [ ] No root, no Magisk, no LSPosed, no kernel patch, no write to `/system`.
- [ ] Nothing uninstalls a package (`pm disable-user` yes, `pm uninstall` no).
- [ ] If I changed engine behaviour, I changed **both** implementations (Python and Kotlin)
      and both test suites.
- [ ] `make docs` was run if the catalog changed (CI fails on stale docs).
- [ ] No new Android permission — or, if there is one, it is in `docs/PERMISSIONS.md` and in
      the allowlist in `.github/workflows/android.yml`.

## Testing

<!-- What did you run, and on what device? -->

- [ ] `make verify` (lint + docs freshness + Python tests)
- [ ] `gradle testDebugUnitTest` (if Kotlin changed)
- [ ] Tested on hardware: device / ROM / Android version →

```
model:
ro.vivo.os.version:
toolkit version:
```

## Notes for the reviewer

<!-- Anything you are unsure about, any tweak you want demoted to `verified: false`, anything
you deliberately did not do. Flagging uncertainty here is welcome, not a weakness. -->
