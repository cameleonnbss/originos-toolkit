# The catalog

Three JSON files, and the single source of truth for the whole project.

| File | Contents |
| --- | --- |
| `tweaks.json` | Categories and the 39 tweaks: what each one does, the exact ops it runs, how it is undone |
| `profiles.json` | One-click bundles of tweak ids |
| `awesome.json` | The curated no-root tool index shown on the app's Discover screen |

**These files are not copied anywhere.** The Android build adds this directory as an asset
directory (`assets.srcDirs` in `app/build.gradle.kts`) so `assets/tweaks.json` *is*
`catalog/tweaks.json`, and the CLI reads the same files from disk. There is exactly one copy
of every definition, which is why the app and the CLI can never disagree.

## Editing

```console
$ python -m originos_toolkit.cli lint      # validate (from cli/, or with PYTHONPATH=cli)
$ python scripts/generate_docs.py          # regenerate docs/TWEAKS.md and docs/AWESOME-ORIGINOS.md
$ cd cli && python -m unittest discover -s tests -t .
```

CI runs all three and fails if the documentation is stale, so `catalog/` and `docs/` cannot
drift apart.

## Schema

A tweak:

```jsonc
{
  "id": "kebab-case-and-unique",          // referenced by profiles, the journal and the docs
  "name": "Shown in the list",
  "category": "display",                  // must exist in "categories"
  "risk": "low",                          // low | medium | high
  "requires": "shizuku",                  // shizuku | adb | either
  "verified": true,                       // false ⇒ grouped in the opt-in Experimental UI
  "reversible": false,                    // optional; declare a one-shot action honestly
  "originOs": ["originos4", "originos5"], // compatibility, shown in the app and docs
  "summary": "One sentence.",
  "details": "Everything a careful user needs to know.",
  "actions": [
    { "op": "settings_put", "ns": "system", "key": "peak_refresh_rate", "value": "144.0" }
  ],
  "revert": [ /* optional, only when the inverse cannot be derived */ ],
  "verify": [ /* optional extra reads, for `status` */ ]
}
```

Ops, the auto-inverse rules and the probe semantics are documented in
[`docs/ARCHITECTURE.md`](../docs/ARCHITECTURE.md). The rules a contribution has to satisfy are
in [`CONTRIBUTING.md`](../CONTRIBUTING.md).

## Why JSON

So that adding a tweak is a data change rather than a code change, and so that the *same*
declaration can be executed by two independent implementations and validated by a third
(`lint`) before anyone's phone is involved. A tweak that cannot be undone does not compile
into a build: it fails `lint`.
