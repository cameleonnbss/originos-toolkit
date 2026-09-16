"""Profile (bundle) resolution."""

from __future__ import annotations

from typing import Iterable, List, Sequence, Set

from .catalog import Catalog, Profile, Tweak


class UnknownReference(KeyError):
    """Raised when a requested tweak or profile id does not exist."""

    def __init__(self, reference: str, kind: str = "tweak") -> None:
        super().__init__(reference)
        self.reference = reference
        self.kind = kind

    def __str__(self) -> str:  # pragma: no cover - trivial
        return f"unknown {self.kind} id: {self.reference!r}"


def is_profile(catalog: Catalog, reference: str) -> bool:
    return any(p.id == reference for p in catalog.profiles)


def resolve_ids(catalog: Catalog, references: Sequence[str]) -> List[Tweak]:
    """Expand a mix of profile ids and tweak ids into tweaks, order preserved.

    Ids are de-duplicated: applying ``gaming-max animation-speed-fast`` must not
    apply the animation tweak twice.
    """

    resolved: List[Tweak] = []
    seen: Set[str] = set()

    for reference in references:
        if is_profile(catalog, reference):
            members: Iterable[str] = catalog.profile(reference).tweaks
        else:
            members = [reference]

        for tweak_id in members:
            if tweak_id in seen:
                continue
            tweak = catalog.try_tweak(tweak_id)
            if tweak is None:
                raise UnknownReference(tweak_id)
            seen.add(tweak_id)
            resolved.append(tweak)

    return resolved


def summarise(profile: Profile, catalog: Catalog) -> str:
    """One-line description used by the CLI and the app's profile cards."""

    risks = []
    for tweak_id in profile.tweaks:
        tweak = catalog.try_tweak(tweak_id)
        if tweak is not None:
            risks.append(tweak.risk)

    parts = []
    medium = risks.count("medium")
    high = risks.count("high")
    if medium:
        parts.append(f"{medium} medium-risk")
    if high:
        parts.append(f"{high} high-risk")

    summary = f"{len(profile.tweaks)} tweaks"
    return " · ".join([summary, *parts])
