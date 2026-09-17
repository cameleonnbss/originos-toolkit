#!/usr/bin/env python3
"""Check every link this project publishes, so a dead one fails CI.

The curated index in `catalog/awesome.json` points at 35 third-party projects
and the documentation links to a few more. Those links rot: projects get
renamed, archived or deleted. A curated list whose links are half dead is worse
than no list at all, so this runs on every pull request.

    python scripts/check_links.py            # check everything
    python scripts/check_links.py --offline  # only parse, no network

GitHub slugs are checked through the API (a renamed repository answers with a
redirect and reports its canonical name, which we flag as "renamed, update the
link"). Everything else is checked with a plain HEAD request.

Exit codes: 0 when every link resolves, 1 when at least one is dead.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.request
from pathlib import Path
from typing import Dict, List, Optional, Tuple

REPO_ROOT = Path(__file__).resolve().parents[1]

#: Files whose links are published and therefore checked.
DOC_FILES = (
    "README.md",
    "CONTRIBUTING.md",
    "SECURITY.md",
    "cli/README.md",
    "catalog/README.md",
    "docs/ARCHITECTURE.md",
    "docs/DEVICE-SUPPORT.md",
    "docs/FAQ.md",
    "docs/NO-ROOT-SETUP.md",
    "docs/PERMISSIONS.md",
)

#: Domains we do not probe: badge renderers and spec sites that rate-limit.
SKIP_HOSTS = ("img.shields.io", "keepachangelog.com", "semver.org", "github.com/features")

GITHUB_REPO = re.compile(r"https?://github\.com/([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+)")
URL_RE = re.compile(r"https?://[^\s\)\]\"'<>`]+")
#: Fenced code blocks hold schema examples like "owner/repo", not real links.
CODE_FENCE = re.compile(r"```.*?```", re.S)
REQUEST_TIMEOUT = 15


def _api_token() -> Optional[str]:
    """A token if we can find one: CI exports one, a laptop has `gh auth token`."""

    import subprocess

    for name in ("GH_TOKEN", "GITHUB_TOKEN"):
        value = os.environ.get(name)
        if value:
            return value
    try:
        result = subprocess.run(
            ["gh", "auth", "token"], capture_output=True, text=True, timeout=10
        )
    except Exception:
        return None
    return result.stdout.strip() or None


def _get(url: str, token: Optional[str] = None) -> Tuple[Optional[int], str]:
    """Returns (status, body). Status is None when the request never landed."""

    headers = {"User-Agent": "originos-toolkit-link-check", "Accept": "application/vnd.github+json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    request = urllib.request.Request(url, headers=headers, method="GET")
    try:
        with urllib.request.urlopen(request, timeout=REQUEST_TIMEOUT) as response:
            return response.status, response.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as error:
        return error.code, ""
    except Exception as error:  # DNS failure, TLS problem, timeout: not a verdict.
        return None, str(error)


def github_slugs(text: str) -> List[str]:
    return sorted({match.group(1).removesuffix(".git") for match in GITHUB_REPO.finditer(text)})


def collect() -> Dict[str, List[str]]:
    """url -> where it is published."""

    found: Dict[str, List[str]] = {}
    for path in [REPO_ROOT / "catalog" / "awesome.json"]:
        if not path.is_file():
            continue
        doc = json.loads(path.read_text(encoding="utf-8"))
        for section in doc.get("sections", []):
            for entry in section.get("entries", []):
                found.setdefault(entry["url"], []).append(f"{path.name}:{entry['name']}")

    for name in DOC_FILES:
        path = REPO_ROOT / name
        if not path.is_file():
            continue
        prose = CODE_FENCE.sub("", path.read_text(encoding="utf-8"))
        for url in URL_RE.findall(prose):
            found.setdefault(url.rstrip(".,"), []).append(name)

    return found


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--offline", action="store_true", help="parse only, no network calls")
    args = parser.parse_args()

    found = collect()
    token = _api_token()
    dead: List[str] = []
    renamed: List[Tuple[str, str]] = []
    unverified: List[Tuple[str, str]] = []
    checked_repos: Dict[str, Optional[str]] = {}

    for url in sorted(found):
        where = ", ".join(sorted(set(found[url])))
        if any(host in url for host in SKIP_HOSTS):
            print(f"[skip] {url}")
            continue
        if args.offline:
            print(f"[parse] {url}  ({where})")
            continue

        match = GITHUB_REPO.match(url)
        if match:
            slug = match.group(1).removesuffix(".git")
            if slug not in checked_repos:
                status, body = _get(f"https://api.github.com/repos/{slug}", token)
                canonical = None
                if status == 200:
                    try:
                        canonical = json.loads(body)["full_name"]
                    except Exception:
                        canonical = None
                checked_repos[slug] = canonical if status == 200 else ("" if status == 404 else None)
            result = checked_repos[slug]
            if result is None:
                unverified.append((url, "GitHub API did not answer (rate limit?)"))
                print(f"[????] {url}  ({where})")
            elif result == "":
                dead.append(url)
                print(f"[DEAD] {url}  ({where}) — 404")
            elif result.lower() != slug.lower():
                renamed.append((url, result))
                print(f"[MOVE] {url}  ({where}) — now {result}")
            else:
                print(f"[ ok ] {url}")
            continue

        status, _ = _get(url)
        if status is None:
            unverified.append((url, "request failed without an HTTP status"))
            print(f"[????] {url}  ({where})")
        elif status >= 400:
            dead.append(url)
            print(f"[DEAD] {url}  ({where}) — HTTP {status}")
        else:
            print(f"[ ok ] {url}  ({where})")

    if args.offline:
        print(f"\n{len(found)} unique links parsed")
        return 0

    print()
    for url, canonical in renamed:
        print(f"update this link to https://github.com/{canonical}\n  was {url}")
    for url, reason in unverified:
        print(f"could not verify {url}: {reason}")

    if dead or renamed:
        print(
            f"\n{len(dead)} dead link(s), {len(renamed)} renamed repo(s) — "
            "fix them in catalog/awesome.json, then run python scripts/generate_docs.py",
            file=sys.stderr,
        )
        return 1

    if unverified:
        # Never claim a clean run we did not have: an unauthenticated run hits
        # GitHub's 60-requests-per-hour ceiling well before the list is done.
        print(
            f"\n{len(unverified)} link(s) could not be verified, so this is an "
            "incomplete check, not a pass. Set GH_TOKEN to raise the API limit.",
            file=sys.stderr,
        )
        return 0

    print(f"all {len(found)} links resolve")
    return 0


if __name__ == "__main__":
    sys.exit(main())
