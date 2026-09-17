"""Command-line entry point for OriginOS Toolkit."""

from __future__ import annotations

import argparse
import json
import sys
from datetime import date
from pathlib import Path
from typing import List, Optional, Sequence

from . import __version__
from .adb import AdbError, AdbRunner, RecordingRunner, find_adb
from .catalog import Catalog, CatalogError, Tweak, lint, load_catalog
from .engine import Engine, EngineError, TweakResult
from .export import render_script
from .journal import Journal
from .ops import probe_for, runs_without_shizuku, shell_calls, tweak_requirement
from .profiles import UnknownReference, resolve_ids, summarise
from .render import bullet, heading, marker, paint, risk_badge, table, wrap

EXIT_OK = 0
EXIT_ERROR = 1
EXIT_USAGE = 2

SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

DEVICE_PROPS = (
    ("Model", "ro.product.model"),
    ("Brand", "ro.product.brand"),
    ("Android", "ro.build.version.release"),
    ("SDK", "ro.build.version.sdk"),
    ("OriginOS", "ro.vivo.os.version"),
    ("OriginOS build", "ro.vivo.os.build.display.id"),
    ("FuntouchOS", "ro.vivo.product.version"),
    ("Codename", "ro.product.device"),
)


# ---------------------------------------------------------------------------
# parser
# ---------------------------------------------------------------------------


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="originos-toolkit",
        description=(
            "No-root tweak engine for Vivo / iQOO OriginOS devices. "
            "Talks to a device over ADB, or prints commands with --dry-run."
        ),
        epilog="Docs: https://github.com/cameleonnbss/originos-toolkit",
    )
    parser.add_argument("--version", action="version", version=f"originos-toolkit {__version__}")

    # Sub-parsers re-declare the global options so they can be written on either
    # side of the command. SUPPRESS keeps an unset sub-parser option from
    # clobbering a value the user already gave before the command name.
    common = argparse.ArgumentParser(add_help=False)
    common.add_argument("--catalog", default=argparse.SUPPRESS, help="path to the catalog directory")
    common.add_argument("--serial", default=argparse.SUPPRESS, help="adb device serial")
    common.add_argument("--adb", default=argparse.SUPPRESS, help="path to the adb executable")
    common.add_argument("--journal", default=argparse.SUPPRESS, help="path to the revert journal")

    parser.add_argument("--catalog", help="path to the catalog directory")
    parser.add_argument("--serial", help="adb device serial")
    parser.add_argument("--adb", help="path to the adb executable")
    parser.add_argument("--journal", help="path to the revert journal")

    sub = parser.add_subparsers(dest="command", metavar="<command>")

    sub.add_parser("devices", parents=[common], help="list connected devices")
    sub.add_parser("doctor", parents=[common], help="check adb, device and Shizuku readiness")
    sub.add_parser("info", parents=[common], help="print device and OriginOS properties")

    catalog_cmd = sub.add_parser("catalog", parents=[common], help="list every tweak")
    catalog_cmd.add_argument("--category", help="only this category id")
    catalog_cmd.add_argument("--risk", choices=["low", "medium", "high"], help="only this risk level")
    catalog_cmd.add_argument(
        "--no-shizuku",
        action="store_true",
        help="only tweaks that work without Shizuku or root",
    )
    catalog_cmd.add_argument("--json", action="store_true", help="machine-readable output")

    show = sub.add_parser("show", parents=[common], help="explain one tweak in detail")
    show.add_argument("tweak")
    show.add_argument("--commands", action="store_true", help="print the exact adb commands")

    apply_cmd = sub.add_parser("apply", parents=[common], help="apply tweaks or profiles")
    apply_cmd.add_argument("ids", nargs="+", help="tweak ids and/or profile ids")
    apply_cmd.add_argument("--dry-run", action="store_true", help="print commands, touch nothing")
    apply_cmd.add_argument("--yes", action="store_true", help="skip the confirmation prompt")
    apply_cmd.add_argument("--force", action="store_true", help="re-apply something already applied")
    apply_cmd.add_argument(
        "--allow-experimental",
        action="store_true",
        help="permit tweaks in the experimental category",
    )

    revert = sub.add_parser("revert", parents=[common], help="undo tweaks from the journal")
    revert.add_argument("ids", nargs="*", help="tweak ids to revert")
    revert.add_argument("--all", action="store_true", help="revert everything in the journal")
    revert.add_argument("--dry-run", action="store_true", help="print commands, touch nothing")
    revert.add_argument("--yes", action="store_true", help="skip the confirmation prompt")

    sub.add_parser("status", parents=[common], help="show which tweaks look applied")
    sub.add_parser("profiles", parents=[common], help="list the bundled profiles")

    debloat = sub.add_parser("debloat", parents=[common], help="inspect and disable OEM packages")
    debloat.add_argument("--installed", action="store_true", help="only packages present on the device")
    debloat.add_argument("--targets", nargs="*", help="packages to disable instead of the catalog set")
    debloat.add_argument("--dry-run", action="store_true", help="print commands only")
    debloat.add_argument("--yes", action="store_true", help="skip the confirmation prompt")
    debloat.add_argument("--enable", action="store_true", help="re-enable instead of disabling")

    awesome = sub.add_parser("awesome", parents=[common], help="browse the curated no-root app list")
    awesome.add_argument("--section", help="only this section id")
    awesome.add_argument("--root-only", action="store_true", help="only entries that require root")
    awesome.add_argument("--json", action="store_true", help="machine-readable output")

    export = sub.add_parser("export", parents=[common], help="write an ADB script for tweaks")
    export.add_argument("ids", nargs="+")
    export.add_argument("-o", "--output", help="file to write (default: stdout)")
    export.add_argument("--format", choices=["sh", "ps1"], default="sh")

    lint_cmd = sub.add_parser("lint", parents=[common], help="validate the catalog")
    lint_cmd.add_argument("--json", action="store_true")

    return parser


# ---------------------------------------------------------------------------
# shared plumbing
# ---------------------------------------------------------------------------


def _load_catalog(args) -> Catalog:
    return load_catalog(getattr(args, "catalog", None))


def _make_runner(args, dry_run: bool):
    if dry_run:
        return RecordingRunner()
    if not find_adb(getattr(args, "adb", None)):
        raise AdbError(
            "adb was not found, so the toolkit cannot talk to a device.\n"
            "  · install Android Platform Tools and add it to PATH, or\n"
            "  · pass --adb /path/to/adb, or\n"
            "  · use --dry-run to print the commands instead."
        )
    return AdbRunner(getattr(args, "adb", None), getattr(args, "serial", None))


def _make_engine(args, catalog: Catalog, dry_run: bool, allow_experimental: bool = False) -> Engine:
    journal = Journal(Path(args.journal).expanduser() if getattr(args, "journal", None) else None)
    return Engine(
        catalog=catalog,
        runner=_make_runner(args, dry_run),
        journal=journal,
        dry_run=dry_run,
        allow_experimental=allow_experimental,
    )


def _confirm(question: str, assume_yes: bool) -> bool:
    if assume_yes:
        return True
    if not sys.stdin.isatty():
        print(
            paint("refusing to continue: ", "red")
            + "not a terminal. Re-run with --yes (or --dry-run to look first)."
        )
        return False
    answer = input(f"{question} [y/N] ").strip().lower()
    return answer in ("y", "yes")


def _print_result(result: TweakResult) -> None:
    head = f"{result.name} ({result.tweak_id})"
    if result.dry_run:
        head += " — DRY RUN"
    print("\n" + paint(head, "bold"))

    for step in result.steps:
        status = paint("ok  ", "green") if step.ok else paint("FAIL", "red")
        print(f"  {status} {step.label}")
        print(paint(f"        {step.call.render()}", "dim"))
        if step.output and step.output != "(dry run)":
            snippet = step.output.strip().splitlines()
            for line in snippet[:6]:
                print(paint(f"        → {line}", "dim"))

    for note in result.skipped:
        print("  " + paint("skip ", "yellow") + note)
    for error in result.errors:
        print("  " + paint("error ", "red") + error)

    if result.errors:
        print(paint("  finished with errors — nothing was reverted automatically.", "yellow"))
    elif not result.dry_run:
        print(paint("  done. `originos-toolkit revert " + result.tweak_id + "` undoes it.", "dim"))


# ---------------------------------------------------------------------------
# commands
# ---------------------------------------------------------------------------


def cmd_devices(args) -> int:
    try:
        runner = _make_runner(args, dry_run=False)
    except AdbError as exc:
        print(paint(str(exc), "red"))
        return EXIT_ERROR
    serials = runner.devices()
    if not serials:
        print("no devices in 'device' state. Enable USB debugging, or pair over wireless ADB.")
        return EXIT_ERROR
    print(heading("Connected devices"))
    for serial in serials:
        print(bullet(serial))
    return EXIT_OK


def cmd_doctor(args) -> int:
    print(heading("OriginOS Toolkit — doctor"))
    checks: List[tuple] = []

    adb_path = find_adb(getattr(args, "adb", None))
    checks.append(("adb executable", bool(adb_path), adb_path or "not found"))

    if not adb_path:
        print(table(["check", "state", "detail"], [(n, "FAIL", d) for n, ok, d in checks]))
        print(
            "\n"
            + bullet("Install Android Platform Tools: https://developer.android.com/tools/releases/platform-tools")
            + "\n"
            + bullet("Then re-run: originos-toolkit doctor")
        )
        return EXIT_ERROR

    runner = AdbRunner(adb_path, getattr(args, "serial", None))
    serials = runner.devices()
    checks.append(("device attached", bool(serials), ", ".join(serials) or "none"))

    if serials:
        package_list = runner.raw_shell("pm list packages")
        checks.append(
            (
                "Shizuku installed (needed for the app)",
                SHIZUKU_PACKAGE in package_list.stdout,
                SHIZUKU_PACKAGE,
            )
        )
        wifi = runner.shell(["settings", "get", "global", "adb_wifi_enabled"])
        checks.append(
            (
                "wireless debugging flag",
                wifi.output == "1",
                "enabled" if wifi.output == "1" else "off (no-root setup uses it)",
            )
        )
        shell_uid = runner.shell(["id"])
        checks.append(("shell access", shell_uid.ok, shell_uid.output or "no output"))

    try:
        catalog = _load_catalog(args)
    except CatalogError:
        catalog = None
    if catalog is not None:
        shell_free = [t for t in catalog.tweaks if runs_without_shizuku(t)]
        checks.append(
            (
                "tweaks needing no Shizuku",
                True,
                f"{len(shell_free)} of {len(catalog.tweaks)} — grant 'modify system settings' in the app",
            )
        )

    print(table(["check", "state", "detail"], [(n, "ok" if ok else "FAIL", d) for n, ok, d in checks]))

    if not serials:
        print("\n" + bullet("No device: run `originos-toolkit devices` after plugging in / pairing."))
    else:
        print("\n" + bullet("Ready. Try `originos-toolkit catalog` then `apply <id> --dry-run`."))
    return EXIT_OK if all(ok for _, ok, _ in checks) else EXIT_ERROR


def cmd_info(args) -> int:
    runner = _make_runner(args, dry_run=False)
    props = []
    for label, prop in DEVICE_PROPS:
        result = runner.shell(["getprop", prop])
        value = result.output or "—"
        props.append((label, value))

    extra = [
        ("Density", runner.shell(["wm", "density"]).output or "—"),
        ("Resolution", runner.shell(["wm", "size"]).output or "—"),
        ("Peak refresh rate", runner.shell(["settings", "get", "system", "peak_refresh_rate"]).output or "—"),
        ("Min refresh rate", runner.shell(["settings", "get", "system", "min_refresh_rate"]).output or "—"),
    ]

    print(heading("Device"))
    print(table(["property", "value"], props))
    print(heading("Display"))
    print(table(["property", "value"], extra))

    origin_os = any(label == "OriginOS" and value != "—" for label, value in props)
    print(
        "\n"
        + (
            bullet("OriginOS detected — the OriginOS-specific tweaks apply to this device.")
            if origin_os
            else bullet(
                "No `ro.vivo.os.version` property: this does not look like an OriginOS device. "
                "Most tweaks are generic Android and still work."
            )
        )
    )
    return EXIT_OK


def cmd_catalog(args) -> int:
    catalog = _load_catalog(args)
    tweaks = list(catalog.tweaks)
    if args.category:
        tweaks = [t for t in tweaks if t.category == args.category]
    if args.risk:
        tweaks = [t for t in tweaks if t.risk == args.risk]
    if getattr(args, "no_shizuku", False):
        tweaks = [t for t in tweaks if runs_without_shizuku(t)]

    if args.json:
        print(
            json.dumps(
                [
                    {
                        "id": t.id,
                        "name": t.name,
                        "category": t.category,
                        "risk": t.risk,
                        "requires": t.requires,
                        "withoutShizuku": runs_without_shizuku(t),
                        "verified": t.verified,
                        "reversible": t.is_reversible,
                        "summary": t.summary,
                    }
                    for t in tweaks
                ],
                indent=2,
                ensure_ascii=False,
            )
        )
        return EXIT_OK

    shell_free = [t for t in catalog.tweaks if runs_without_shizuku(t)]
    print(heading(f"Catalog — {len(tweaks)} tweaks (v{catalog.catalog_version})"))
    rows = [
        (
            t.id,
            t.name[:44],
            risk_badge(t.risk),
            t.requires,
            "no-shizuku" if runs_without_shizuku(t) else "shell uid 2000",
            "yes" if t.verified else "no",
        )
        for t in tweaks
    ]
    print(table(["id", "name", "risk", "via", "needs", "verified"], rows))
    print(
        "\n"
        + bullet(f"{len(shell_free)} of {len(catalog.tweaks)} tweaks need no Shizuku: "
                 f"`originos-toolkit catalog --no-shizuku`")
        + "\n"
        + bullet("`originos-toolkit show <id>` for the full story")
        + "\n"
        + bullet("`originos-toolkit apply <id> --dry-run` to see the commands")
    )
    return EXIT_OK


def cmd_show(args) -> int:
    catalog = _load_catalog(args)
    tweak = catalog.try_tweak(args.tweak)
    if tweak is None:
        print(paint(f"unknown tweak: {args.tweak}", "red"))
        return EXIT_ERROR

    print(heading(tweak.name))
    print(f"  id       {tweak.id}")
    print(f"  category {catalog.category_name(tweak.category)}")
    print(f"  risk     {risk_badge(tweak.risk)}")
    print(f"  via      {tweak.requires}")
    print(f"  access   {_describe_access(tweak)}")
    print(f"  verified {'yes' if tweak.verified else 'no — community-submitted'}")
    print(f"  revert   {'yes' if tweak.is_reversible else 'no (one-shot action)'}")
    print(f"  OriginOS {', '.join(tweak.origin_os) or '—'}")
    print("\n" + wrap(tweak.summary, indent="  "))
    if tweak.details:
        print("\n" + wrap(tweak.details, indent="  "))

    print(heading("Operations"))
    for action in tweak.actions:
        print(bullet(f"{action.op}: {action.target}"))
    if tweak.revert:
        print(bullet("explicit revert defined", indent=2))

    if args.commands:
        print(heading("Commands"))
        for action in tweak.actions:
            call = probe_for(action)
            if call is not None:
                print(paint(f"  $ settings read: {call.render()}", "dim"))
            for call in shell_calls(action, previous="460"):
                print(f"  $ adb shell {call.render()}")
    return EXIT_OK


def _describe_access(tweak: Tweak) -> str:
    """Plain-language answer to "do I need Shizuku for this?"""

    if runs_without_shizuku(tweak):
        return "no Shizuku needed — the app writes it with the modify-system-settings grant"
    if tweak_requirement(tweak) == "shell":
        return "needs the shell user (Shizuku on the phone, or adb from a computer)"
    return "read-only"


def _apply_ids(args, ids: Sequence[str], dry_run: bool) -> int:
    catalog = _load_catalog(args)
    try:
        tweaks = resolve_ids(catalog, ids)
    except UnknownReference as exc:
        print(paint(str(exc), "red"))
        print("Run `originos-toolkit catalog` for the list of ids.")
        return EXIT_ERROR

    experimental = [t for t in tweaks if t.category == "experimental"]
    allow_experimental = getattr(args, "allow_experimental", False)

    print(heading(f"{len(tweaks)} tweak(s) to {'preview' if dry_run else 'apply'}"))
    for tweak in tweaks:
        flag = " (experimental)" if tweak.category == "experimental" else ""
        print(bullet(f"{risk_badge(tweak.risk)} {tweak.name}{flag}"))

    if experimental and not allow_experimental and not dry_run:
        print("\n" + paint("blocked: ", "red") + "experimental tweaks need --allow-experimental")
        return EXIT_ERROR

    high_risk = [t for t in tweaks if t.risk == "high"]
    if high_risk and not dry_run:
        print("\n" + paint("high risk: ", "yellow") + ", ".join(t.id for t in high_risk))

    if not dry_run and not _confirm("Apply these changes to the device?", getattr(args, "yes", False)):
        return EXIT_ERROR

    try:
        engine = _make_engine(args, catalog, dry_run, allow_experimental)
    except AdbError as exc:
        print(paint(str(exc), "red"))
        return EXIT_ERROR

    failures = 0
    for tweak in tweaks:
        try:
            result = engine.apply(tweak, force=getattr(args, "force", False))
        except EngineError as exc:
            print(paint(f"  aborted {tweak.id}: {exc}", "red"))
            failures += 1
            continue
        _print_result(result)
        failures += 1 if result.errors else 0

    if not dry_run:
        print(
            "\n"
            + bullet(f"journal: {engine.journal.path}")
            + "\n"
            + bullet("undo everything with `originos-toolkit revert --all`")
        )
    return EXIT_ERROR if failures else EXIT_OK


def cmd_apply(args) -> int:
    return _apply_ids(args, args.ids, args.dry_run)


def cmd_revert(args) -> int:
    catalog = _load_catalog(args)
    try:
        engine = _make_engine(args, catalog, args.dry_run)
    except AdbError as exc:
        print(paint(str(exc), "red"))
        return EXIT_ERROR

    if args.all:
        if not engine.journal.entries:
            print("the journal is empty — nothing to revert.")
            return EXIT_OK
        print(heading(f"Reverting {len(engine.journal.entries)} tweak(s)"))
        if not args.dry_run and not _confirm("Undo everything in the journal?", args.yes):
            return EXIT_ERROR
        failures = 0
        for result in engine.revert_all():
            _print_result(result)
            failures += 1 if result.errors else 0
        return EXIT_ERROR if failures else EXIT_OK

    if not args.ids:
        print(paint("nothing to revert: pass tweak ids or --all", "yellow"))
        return EXIT_USAGE

    failures = 0
    for tweak_id in args.ids:
        tweak = catalog.try_tweak(tweak_id)
        try:
            result = engine.revert(tweak, tweak_id)
        except EngineError as exc:
            print(paint(f"  {exc}", "red"))
            failures += 1
            continue
        _print_result(result)
        failures += 1 if result.errors else 0
    return EXIT_ERROR if failures else EXIT_OK


def cmd_status(args) -> int:
    catalog = _load_catalog(args)
    try:
        engine = _make_engine(args, catalog, dry_run=False)
    except AdbError as exc:
        print(paint(str(exc), "red"))
        return EXIT_ERROR

    rows = []
    for tweak, applied in engine.status():
        rows.append((marker(applied), tweak.id, tweak.name[:44], risk_badge(tweak.risk)))

    print(heading("Tweak status"))
    print(table(["", "id", "name", "risk"], rows))
    print("\n" + bullet("? = the tweak has no readable state (a one-shot action)"))
    return EXIT_OK


def cmd_profiles(args) -> int:
    catalog = _load_catalog(args)
    print(heading("Profiles"))
    rows = [
        (p.id, p.name[:30], summarise(p, catalog))
        for p in catalog.profiles
    ]
    print(table(["id", "name", "contents"], rows))
    for profile in catalog.profiles:
        print("\n" + paint(profile.name, "bold"))
        print(wrap(profile.blurb, indent="  "))
        print(wrap(", ".join(profile.tweaks), indent="  ", width=84))
    print("\n" + bullet("apply one with: originos-toolkit apply <profile-id>"))
    return EXIT_OK


def cmd_debloat(args) -> int:
    catalog = _load_catalog(args)

    if args.targets is not None:
        targets = list(args.targets)
    else:
        targets = []
        for tweak in catalog.by_category("debloat"):
            for action in tweak.actions:
                if action.op == "pm_disable":
                    targets.append(action.get("pkg"))

    if not targets:
        print(paint("no packages to act on", "yellow"))
        return EXIT_USAGE

    try:
        runner = _make_runner(args, args.dry_run)
    except AdbError as exc:
        print(paint(str(exc), "red"))
        return EXIT_ERROR

    installed_result = runner.raw_shell("pm list packages")
    disabled_result = runner.raw_shell("pm list packages -d")
    installed = {
        line.split(":", 1)[1].strip()
        for line in installed_result.stdout.splitlines()
        if line.startswith("package:")
    }
    disabled = {
        line.split(":", 1)[1].strip()
        for line in disabled_result.stdout.splitlines()
        if line.startswith("package:")
    }

    if args.installed and installed:
        targets = [p for p in targets if p in installed]

    rows = []
    for package in sorted(set(targets)):
        if installed and package not in installed:
            state = "absent"
        elif package in disabled:
            state = "disabled"
        else:
            state = "enabled"
        rows.append((package, state))

    print(heading(f"Packages ({len(rows)})"))
    print(table(["package", "state"], rows))

    action = "enable" if args.enable else "disable"
    actionable = [p for p, state in rows if state != "absent"] if installed else [p for p, _ in rows]
    if not actionable:
        print(paint("\nnothing actionable (no device data, or all packages absent).", "yellow"))
        return EXIT_OK

    print(
        "\n"
        + bullet(f"about to {action}: {', '.join(actionable)}")
        + "\n"
        + bullet("pm disable-user is per-user and always reversible with `--enable`")
    )

    if args.dry_run:
        print("\n" + paint("dry run — commands:", "bold"))
        verb = ["pm", "enable"] if args.enable else ["pm", "disable-user", "--user", "0"]
        for package in actionable:
            print(f"  $ adb shell {' '.join([*verb, package])}")
        return EXIT_OK

    if not _confirm(f"{action} these {len(actionable)} package(s)?", args.yes):
        return EXIT_ERROR

    failures = 0
    for package in actionable:
        if args.enable:
            result = runner.shell(["pm", "enable", "--user", "0", package])
        else:
            result = runner.shell(["pm", "disable-user", "--user", "0", package])
        tag = paint("ok  ", "green") if result.ok else paint("FAIL", "red")
        print(f"  {tag} {package} {paint(result.output, 'dim')}")
        failures += 0 if result.ok else 1

    print("\n" + bullet("restore with: originos-toolkit debloat --enable"))
    return EXIT_ERROR if failures else EXIT_OK


def cmd_awesome(args) -> int:
    catalog = _load_catalog(args)
    sections = catalog.awesome
    if args.section:
        sections = [s for s in sections if s.id == args.section]
        if not sections:
            print(paint(f"unknown section: {args.section}", "red"))
            return EXIT_ERROR

    if args.json:
        payload = [
            {
                "section": s.id,
                "name": s.name,
                "entries": [
                    {
                        "name": e.name,
                        "repo": e.repo,
                        "url": e.url,
                        "access": e.access,
                        "description": e.description,
                        "tags": e.tags,
                    }
                    for e in s.entries
                    if not args.root_only or e.access == "root"
                ],
            }
            for s in sections
        ]
        print(json.dumps(payload, indent=2, ensure_ascii=False))
        return EXIT_OK

    total = 0
    for section in sections:
        entries = [e for e in section.entries if not args.root_only or e.access == "root"]
        if not entries:
            continue
        total += len(entries)
        print(heading(section.name))
        if section.blurb:
            print(wrap(section.blurb, indent="  ") + "\n")
        for entry in entries:
            badge = paint(f"({entry.access})", "cyan")
            print(f"  {paint(entry.name, 'bold')} {badge}")
            print(paint(f"    {entry.url}", "dim"))
            print(wrap(entry.description, indent="    "))

    print("\n" + bullet(f"{total} entries. Full list: docs/AWESOME-ORIGINOS.md"))
    return EXIT_OK


def cmd_export(args) -> int:
    catalog = _load_catalog(args)
    try:
        tweaks = resolve_ids(catalog, args.ids)
    except UnknownReference as exc:
        print(paint(str(exc), "red"))
        return EXIT_ERROR

    name = Path(args.output).name if args.output else f"originos-toolkit.{args.format}"
    script = render_script(
        tweaks,
        serial=getattr(args, "serial", None),
        fmt=args.format,
        version=str(catalog.catalog_version),
        date=date.today().isoformat(),
        name=name,
    )
    if args.output:
        # write_bytes, not write_text: we need the newlines we chose in the
        # renderer to survive verbatim (CRLF for .ps1, LF for .sh) on every
        # platform, and Path.write_text only learned `newline=` in Python 3.10.
        Path(args.output).write_bytes(script.encode("utf-8"))
        print(f"wrote {args.output} ({len(script.splitlines())} lines, {len(tweaks)} tweaks)")
    else:
        print(script)
    return EXIT_OK


def cmd_lint(args) -> int:
    catalog = _load_catalog(args)
    problems = lint(catalog)
    if args.json:
        print(json.dumps({"ok": not problems, "problems": problems}, indent=2))
        return EXIT_OK if not problems else EXIT_ERROR

    print(heading("Catalog lint"))
    print(
        bullet(f"{len(catalog.tweaks)} tweaks, {len(catalog.categories)} categories, "
               f"{len(catalog.profiles)} profiles, {len(catalog.awesome)} awesome sections")
    )
    if not problems:
        print("\n" + paint("  no problems found.", "green"))
        return EXIT_OK
    print()
    for problem in problems:
        print("  " + paint("✗ ", "red") + problem)
    return EXIT_ERROR


COMMANDS = {
    "devices": cmd_devices,
    "doctor": cmd_doctor,
    "info": cmd_info,
    "catalog": cmd_catalog,
    "show": cmd_show,
    "apply": cmd_apply,
    "revert": cmd_revert,
    "status": cmd_status,
    "profiles": cmd_profiles,
    "debloat": cmd_debloat,
    "awesome": cmd_awesome,
    "export": cmd_export,
    "lint": cmd_lint,
}


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = build_parser()
    args = parser.parse_args(list(argv) if argv is not None else None)

    if not getattr(args, "command", None):
        parser.print_help()
        return EXIT_USAGE

    handler = COMMANDS[args.command]
    try:
        return handler(args)
    except CatalogError as exc:
        print(paint(f"catalog error: {exc}", "red"))
        return EXIT_ERROR
    except AdbError as exc:
        print(paint(str(exc), "red"))
        return EXIT_ERROR
    except BrokenPipeError:  # pragma: no cover - piping into head
        return EXIT_OK
    except KeyboardInterrupt:  # pragma: no cover - interactive
        print("\ninterrupted")
        return EXIT_ERROR
# Two entry points, deliberately:
#
#   python -m originos_toolkit       [args]   <- __main__.py
#   python -m originos_toolkit.cli   [args]   <- this block
#
# Running the file by path (`python cli/originos_toolkit/cli.py`) is what breaks
# the relative imports, and only that is unsupported. The `-m` form imports this
# module inside its package, so every import above resolves normally.
#
# This block is load-bearing: without it `python -m originos_toolkit.cli lint`
# exits 0 and prints nothing, so every script that trusted it — the Makefile
# targets, the CI smoke test — was silently checking nothing at all.
if __name__ == "__main__":
    sys.exit(main())
