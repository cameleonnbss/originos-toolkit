"""Loading and linting of the shared tweak catalog.

The same JSON files power the CLI and the Android app, so this module is
deliberately strict: :func:`lint` is run by CI on every pull request and a new
tweak that is not reversible, or that references an unknown op, fails the build.
"""

from __future__ import annotations

import json
import os
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Sequence

#: Ops understood by the engine. Anything else is a catalog bug.
KNOWN_OPS = frozenset(
    {
        "settings_get",
        "settings_put",
        "settings_delete",
        "device_config_get",
        "device_config_put",
        "device_config_delete",
        "pm_disable",
        "pm_enable",
        "pm_list",
        "overlay_enable",
        "overlay_disable",
        "overlay_list",
        "cmd",
        "svc",
        "shell",
        "wm_density_delta",
        "wm_density_set",
        "wm_density_reset",
    }
)

#: Ops whose inverse can be derived automatically from a snapshot or a rule.
AUTO_INVERTIBLE = frozenset(
    {
        "settings_put",
        "settings_delete",
        "device_config_put",
        "device_config_delete",
        "pm_disable",
        "pm_enable",
        "overlay_enable",
        "overlay_disable",
        "svc",
        "wm_density_delta",
    }
)

RISK_LEVELS = ("low", "medium", "high")

#: Access a tweak declares it needs, weakest first.
#:
#: * ``settings`` — runs in-process with the "modify system settings" special
#:   access alone. No Shizuku, no root, no computer.
#: * ``adb`` — needs the shell user, reachable from a computer.
#: * ``shizuku`` — needs the shell user, obtained on the phone via Shizuku.
#: * ``either`` — the shell user, by whichever route you happen to have.
REQUIREMENTS = ("settings", "adb", "shizuku", "either")

CATALOG_FILENAMES = ("tweaks.json", "profiles.json", "awesome.json")


class CatalogError(RuntimeError):
    """Raised when a catalog file is missing or malformed beyond repair."""


@dataclass
class Action:
    """One atomic step inside a tweak."""

    op: str
    raw: Dict[str, Any] = field(default_factory=dict)

    def get(self, key: str, default: Any = None) -> Any:
        return self.raw.get(key, default)

    @property
    def target(self) -> str:
        """A stable identity for the thing this action mutates."""

        if self.op.startswith("settings_"):
            return f"settings/{self.get('ns')}/{self.get('key')}"
        if self.op.startswith("device_config_"):
            return f"device_config/{self.get('ns')}/{self.get('key')}"
        if self.op in ("pm_disable", "pm_enable"):
            return f"package/{self.get('pkg')}"
        if self.op.startswith("overlay_"):
            return f"overlay/{self.get('pkg')}"
        if self.op == "svc":
            return f"svc/{self.get('service')}"
        if self.op.startswith("wm_density"):
            return "display/density"
        if self.op == "cmd":
            return "cmd/" + " ".join(str(a) for a in self.get("args", []))
        if self.op == "shell":
            return "shell/" + str(self.get("command", ""))
        return self.op

    def to_json(self) -> Dict[str, Any]:
        return {"op": self.op, **self.raw}

    @classmethod
    def from_json(cls, data: Dict[str, Any]) -> "Action":
        payload = dict(data)
        op = payload.pop("op", None)
        if not isinstance(op, str):
            raise CatalogError(f"action is missing an 'op' field: {data!r}")
        return cls(op=op, raw=payload)


@dataclass
class Tweak:
    id: str
    name: str
    category: str
    summary: str
    actions: List[Action]
    revert: Optional[List[Action]] = None
    details: str = ""
    risk: str = "low"
    requires: str = "shizuku"
    verified: bool = True
    reversible: Optional[bool] = None
    origin_os: List[str] = field(default_factory=list)
    verify: List[Action] = field(default_factory=list)

    @property
    def is_reversible(self) -> bool:
        if self.reversible is not None:
            return self.reversible
        if self.revert is not None:
            return True
        return all(a.op in AUTO_INVERTIBLE for a in self.actions)

    @property
    def needs_snapshot(self) -> bool:
        return any(a.op in ("settings_put", "settings_delete", "device_config_put", "device_config_delete", "wm_density_delta") for a in self.actions)

    @property
    def uses_dynamic_value(self) -> bool:
        return any(a.op == "wm_density_delta" for a in self.actions)

    @classmethod
    def from_json(cls, data: Dict[str, Any]) -> "Tweak":
        try:
            tweak_id = data["id"]
            name = data["name"]
            category = data["category"]
            actions = [Action.from_json(a) for a in data["actions"]]
        except KeyError as exc:
            raise CatalogError(f"tweak is missing required field {exc}: {data.get('id', data)!r}") from None

        return cls(
            id=tweak_id,
            name=name,
            category=category,
            summary=data.get("summary", ""),
            details=data.get("details", ""),
            actions=actions,
            revert=[Action.from_json(a) for a in data["revert"]] if data.get("revert") else None,
            risk=data.get("risk", "low"),
            requires=data.get("requires", "shizuku"),
            verified=bool(data.get("verified", True)),
            reversible=data.get("reversible"),
            origin_os=list(data.get("originOs", [])),
            verify=[Action.from_json(a) for a in data.get("verify", [])],
        )


@dataclass
class Category:
    id: str
    name: str
    blurb: str = ""
    icon: str = ""


@dataclass
class Profile:
    id: str
    name: str
    blurb: str
    tweaks: List[str]
    icon: str = ""


@dataclass
class AwesomeEntry:
    name: str
    repo: str
    url: str
    access: str
    description: str
    tags: List[str] = field(default_factory=list)


@dataclass
class AwesomeSection:
    id: str
    name: str
    blurb: str = ""
    entries: List[AwesomeEntry] = field(default_factory=list)


@dataclass
class Catalog:
    catalog_version: int
    categories: List[Category]
    tweaks: List[Tweak]
    profiles: List[Profile] = field(default_factory=list)
    awesome: List[AwesomeSection] = field(default_factory=list)
    updated_at: str = ""
    root: Optional[Path] = None

    # -- lookups ----------------------------------------------------------

    def tweak(self, tweak_id: str) -> Tweak:
        for item in self.tweaks:
            if item.id == tweak_id:
                return item
        raise KeyError(tweak_id)

    def try_tweak(self, tweak_id: str) -> Optional[Tweak]:
        try:
            return self.tweak(tweak_id)
        except KeyError:
            return None

    def by_category(self, category: str) -> List[Tweak]:
        return [t for t in self.tweaks if t.category == category]

    def profile(self, profile_id: str) -> Profile:
        for item in self.profiles:
            if item.id == profile_id:
                return item
        raise KeyError(profile_id)

    def category_name(self, category_id: str) -> str:
        for item in self.categories:
            if item.id == category_id:
                return item.name
        return category_id

    def expand(self, ids: Sequence[str]) -> List[Tweak]:
        """Resolve profile ids and tweak ids into a de-duplicated tweak list."""

        from .profiles import resolve_ids

        return resolve_ids(self, ids)


# -- discovery -----------------------------------------------------------


def _read_json(path: Path) -> Dict[str, Any]:
    try:
        with path.open("r", encoding="utf-8") as handle:
            return json.load(handle)
    except FileNotFoundError as exc:
        raise CatalogError(f"catalog file not found: {path}") from exc
    except json.JSONDecodeError as exc:
        raise CatalogError(f"{path} is not valid JSON: {exc}") from exc


def _candidate_roots(start: Optional[Path] = None) -> Iterable[Path]:
    env = os.environ.get("ORIGINOS_TOOLKIT_CATALOG")
    if env:
        yield Path(env)

    package_dir = Path(__file__).resolve().parent
    for parent in [package_dir, *package_dir.parents]:
        yield parent / "catalog"
        yield parent / "data" / "catalog"

    here = (start or Path.cwd()).resolve()
    for parent in [here, *here.parents]:
        yield parent / "catalog"


def discover_catalog(explicit: Optional[str] = None) -> Path:
    """Return the directory that holds the catalog JSON files."""

    if explicit:
        path = Path(explicit).expanduser()
        if not path.exists():
            raise CatalogError(f"--catalog path does not exist: {path}")
        return path if path.is_dir() else path.parent

    seen = set()
    for candidate in _candidate_roots():
        key = str(candidate)
        if key in seen:
            continue
        seen.add(key)
        if (candidate / "tweaks.json").is_file():
            return candidate

    raise CatalogError(
        "could not find the catalog. Pass --catalog /path/to/catalog or set "
        "ORIGINOS_TOOLKIT_CATALOG."
    )


def load_catalog(path: Optional[str] = None) -> Catalog:
    """Load and parse the catalog. Raises :class:`CatalogError` on bad input."""

    root = discover_catalog(path)
    tweaks_doc = _read_json(root / "tweaks.json")

    categories = [
        Category(
            id=c["id"],
            name=c["name"],
            blurb=c.get("blurb", ""),
            icon=c.get("icon", ""),
        )
        for c in tweaks_doc.get("categories", [])
    ]
    tweaks = [Tweak.from_json(t) for t in tweaks_doc.get("tweaks", [])]

    profiles: List[Profile] = []
    profiles_path = root / "profiles.json"
    if profiles_path.is_file():
        profiles_doc = _read_json(profiles_path)
        profiles = [
            Profile(
                id=p["id"],
                name=p["name"],
                blurb=p.get("blurb", ""),
                icon=p.get("icon", ""),
                tweaks=list(p.get("tweaks", [])),
            )
            for p in profiles_doc.get("profiles", [])
        ]

    awesome: List[AwesomeSection] = []
    awesome_path = root / "awesome.json"
    if awesome_path.is_file():
        awesome_doc = _read_json(awesome_path)
        for section in awesome_doc.get("sections", []):
            awesome.append(
                AwesomeSection(
                    id=section["id"],
                    name=section["name"],
                    blurb=section.get("blurb", ""),
                    entries=[
                        AwesomeEntry(
                            name=e["name"],
                            repo=e.get("repo", ""),
                            url=e["url"],
                            access=e.get("access", "no-root"),
                            description=e.get("description", ""),
                            tags=list(e.get("tags", [])),
                        )
                        for e in section.get("entries", [])
                    ],
                )
            )

    return Catalog(
        catalog_version=int(tweaks_doc.get("catalogVersion", 1)),
        updated_at=tweaks_doc.get("updatedAt", ""),
        categories=categories,
        tweaks=tweaks,
        profiles=profiles,
        awesome=awesome,
        root=root,
    )


# -- lint ----------------------------------------------------------------


def lint(catalog: Catalog) -> List[str]:
    """Return a list of human-readable problems. Empty list means healthy."""

    problems: List[str] = []
    seen_ids: Dict[str, int] = {}
    category_ids = {c.id for c in catalog.categories}

    for tweak in catalog.tweaks:
        seen_ids[tweak.id] = seen_ids.get(tweak.id, 0) + 1

        if tweak.category not in category_ids:
            problems.append(f"{tweak.id}: unknown category {tweak.category!r}")

        if tweak.risk not in RISK_LEVELS:
            problems.append(f"{tweak.id}: risk must be one of {RISK_LEVELS}, got {tweak.risk!r}")

        if tweak.requires not in REQUIREMENTS:
            problems.append(f"{tweak.id}: requires must be one of {REQUIREMENTS}, got {tweak.requires!r}")

        # The declared requirement is checked against what the actions can
        # actually do, so the catalog cannot drift into claiming Shizuku is
        # needed for something a plain app can do. Imported here rather than at
        # module level because ops.py imports this module.
        from .ops import action_requirement, requirement_of, settings_targets

        implied = requirement_of([*tweak.actions, *(tweak.revert or [])])
        if tweak.requires == "settings" and implied == "shell":
            needs_shell = [
                a.target
                for a in [*tweak.actions, *(tweak.revert or [])]
                if action_requirement(a) == "shell"
            ]
            problems.append(
                f"{tweak.id}: declares requires 'settings' but {needs_shell} need the shell user"
            )
        elif tweak.requires != "settings" and implied == "settings":
            targets = settings_targets([*tweak.actions, *(tweak.revert or [])])
            problems.append(
                f"{tweak.id}: declares requires {tweak.requires!r} but only touches {targets}, "
                "which works without Shizuku — declare requires 'settings'"
            )

        for action in [*tweak.actions, *(tweak.verify or [])]:
            if action.op not in KNOWN_OPS:
                problems.append(f"{tweak.id}: unknown op {action.op!r}")

        explicitly_one_shot = tweak.reversible is False
        derivable = tweak.revert is not None or all(a.op in AUTO_INVERTIBLE for a in tweak.actions)
        if not derivable and not explicitly_one_shot:
            problems.append(
                f"{tweak.id}: nothing can undo it — add a 'revert' list, or set "
                '"reversible": false if that is deliberate'
            )

        if not tweak.summary:
            problems.append(f"{tweak.id}: missing summary")

        if not tweak.origin_os:
            problems.append(f"{tweak.id}: missing originOs compatibility list")

    for tweak_id, count in seen_ids.items():
        if count > 1:
            problems.append(f"duplicate tweak id: {tweak_id} (x{count})")

    profile_ids = {p.id for p in catalog.profiles}
    for profile in catalog.profiles:
        if not profile.tweaks:
            problems.append(f"profile {profile.id}: has no tweaks")
        for referenced in profile.tweaks:
            if referenced not in seen_ids:
                problems.append(f"profile {profile.id}: references unknown tweak {referenced!r}")

    for section in catalog.awesome:
        for entry in section.entries:
            if not entry.url.startswith("https://"):
                problems.append(f"awesome {section.id}/{entry.name}: url must be https")
            if entry.access not in ("no-root", "adb", "shizuku", "root"):
                problems.append(f"awesome {section.id}/{entry.name}: unknown access {entry.access!r}")

    if profile_ids and len(profile_ids) != len(catalog.profiles):
        problems.append("duplicate profile id")

    return problems
