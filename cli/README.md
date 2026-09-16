# originos-toolkit (CLI)

The reference implementation of the toolkit's tweak engine. It reads the same
[`catalog/`](../catalog) JSON files as the Android app, talks to your phone over
ADB, and journals every change so anything can be undone exactly.

```console
$ originos-toolkit catalog
$ originos-toolkit show force-max-refresh-rate
$ originos-toolkit apply gaming-max --dry-run
$ originos-toolkit apply gaming-max
$ originos-toolkit revert --all
```

## Install

```console
# from the repository
pip install -e cli/

# or run it straight from a checkout, with no install at all
cd cli && python -m originos_toolkit --help
# ...or from the repository root, without changing directory
PYTHONPATH=cli python -m originos_toolkit --help
```

The package uses relative imports, so it is always invoked as a module
(`python -m originos_toolkit`), never as a file path.

The CLI has **no dependencies** beyond the standard library and an `adb` binary.

## Commands

| Command | What it does |
| --- | --- |
| `doctor` | Checks adb, the attached device, Shizuku and shell access. Start here. |
| `devices` | Lists devices in `device` state. |
| `info` | Prints model, OriginOS build, density, resolution and refresh-rate settings. |
| `catalog` | Lists every tweak, with `--category`, `--risk` and `--json` filters. |
| `show <id>` | Full description; `--commands` prints the exact ADB commands. |
| `profiles` | The bundled bundles: `daily-balanced`, `gaming-max`, `battery-max`, `privacy-hardened`, `clean-slate`, `smooth-navigation`. |
| `apply <id…>` | Applies tweaks and/or profiles. `--dry-run` prints commands, `--yes` skips the prompt, `--allow-experimental` unlocks the experimental category. |
| `revert <id…>` | Restores from the journal. `--all` undoes everything, `--dry-run` previews. |
| `status` | Shows which tweaks currently look applied. |
| `debloat` | Inspects Vivo/Google packages and disables or re-enables them. |
| `awesome` | Browses the curated no-root app list (`--section`, `--json`, `--root-only`). |
| `export <id…>` | Writes a standalone ADB script (`--format sh|ps1`, `-o file`). |
| `lint` | Validates the catalog. CI runs this on every pull request. |

## Try it without a phone

```console
$ originos-toolkit apply force-max-refresh-rate --dry-run
$ originos-toolkit export gaming-max --format sh -o apply-gaming.sh
```

`--dry-run` never touches a device; the exported script is exactly what the
engine would have run.

## How reversal works

1. Before anything is applied, the engine reads the previous value of every key
   it is about to change (`settings get`, `device_config get`, `pm list packages -d`,
   `cmd overlay list`, `wm density`).
2. The derived inverse is written to the journal
   (`~/.local/state/originos-toolkit/journal.json`, `%APPDATA%` on Windows)
   **before** the first write reaches the device.
3. `revert` replays the journal, so restores are exact — including keys that did
   not exist before, and packages that were already disabled.

Override the journal location with `--journal <path>` or `ORIGINOS_TOOLKIT_JOURNAL`.

## Environment variables

| Variable | Purpose |
| --- | --- |
| `ORIGINOS_TOOLKIT_ADB` | Full path to `adb`. |
| `ORIGINOS_TOOLKIT_CATALOG` | Directory holding `tweaks.json`. |
| `ORIGINOS_TOOLKIT_JOURNAL` | Journal file location. |
| `NO_COLOR` | Disable colour output. |

## Tests

```console
python -m unittest discover -s cli/tests -t cli -v
```
