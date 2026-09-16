"""The tweak engine: probe → journal → apply, and the reverse on demand."""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Dict, List, Optional, Sequence, Tuple

from .adb import CommandResult
from .catalog import Action, Catalog, Tweak
from .journal import Journal
from .ops import ShellCall, interpret_probe, inverse_action, probe_for, shell_calls


@dataclass
class StepResult:
    """Outcome of a single shell command."""

    label: str
    call: ShellCall
    result: Optional[CommandResult] = None

    @property
    def ok(self) -> bool:
        return self.result is None or self.result.ok

    @property
    def output(self) -> str:
        if self.result is None:
            return "(dry run)"
        return self.result.output


@dataclass
class TweakResult:
    """Aggregate outcome for one tweak."""

    tweak_id: str
    name: str
    steps: List[StepResult] = field(default_factory=list)
    skipped: List[str] = field(default_factory=list)
    errors: List[str] = field(default_factory=list)
    reverted: bool = False
    dry_run: bool = False

    @property
    def ok(self) -> bool:
        return not self.errors

    @property
    def carried_out(self) -> int:
        return len([s for s in self.steps if s.ok])


class EngineError(RuntimeError):
    """Raised for conditions the caller must decide about (aborted apply)."""


class Engine:
    """Applies and reverts tweaks against a device, journalling every change.

    ``runner`` only has to expose ``shell(argv)`` / ``raw_shell(str)``, which
    makes the whole class drivable by ``RecordingRunner`` in tests and
    ``--dry-run``.
    """

    def __init__(
        self,
        catalog: Catalog,
        runner,
        journal: Optional[Journal] = None,
        dry_run: bool = False,
        allow_experimental: bool = False,
        verbose: bool = False,
    ) -> None:
        self.catalog = catalog
        self.runner = runner
        self.journal = journal if journal is not None else Journal()
        self.dry_run = dry_run
        self.allow_experimental = allow_experimental
        self.verbose = verbose

    # -- helpers ----------------------------------------------------------

    def _exec(self, call: ShellCall) -> CommandResult:
        if call.raw is not None:
            return self.runner.raw_shell(call.raw)
        return self.runner.shell(list(call.argv))

    def _check_allowed(self, tweak: Tweak) -> None:
        if tweak.category == "experimental" and not self.allow_experimental:
            raise EngineError(
                f"{tweak.id} is experimental. Re-run with --allow-experimental once you "
                "have read its notes."
            )

    # -- probes -----------------------------------------------------------

    def probe(self, tweak: Tweak) -> Dict[str, Optional[str]]:
        """Read the previous state of everything the tweak will mutate."""

        states: Dict[str, Optional[str]] = {}
        for action in tweak.actions:
            call = probe_for(action)
            if call is None:
                continue
            if self.dry_run:
                # A dry run must not touch the device at all, and the exact
                # previous value is irrelevant for printing commands — except
                # for delta ops, which we resolve against a sane default.
                states[action.target] = "460" if action.op == "wm_density_delta" else None
                continue
            result = self._exec(call)
            if not result.ok and not result.stdout:
                raise EngineError(
                    f"could not read the previous state for {action.target}: {result.output}"
                )
            states[action.target] = interpret_probe(action, result.stdout)
        return states

    def inverse_for(self, tweak: Tweak, states: Dict[str, Optional[str]]) -> List[Action]:
        """Explicit reverts win; otherwise derive one inverse per action."""

        if tweak.revert:
            return list(tweak.revert)

        inverses: List[Action] = []
        for action in tweak.actions:
            candidate = inverse_action(action, states.get(action.target))
            if candidate is not None:
                inverses.append(candidate)
        return inverses

    # -- planning ---------------------------------------------------------

    def plan(self, tweak: Tweak) -> Tuple[Dict[str, Optional[str]], List[StepResult]]:
        """Return the states and the commands that *would* run."""

        states = self.probe(tweak)
        steps: List[StepResult] = []
        for action in tweak.actions:
            calls = shell_calls(action, previous=states.get(action.target))
            for call in calls:
                if call.is_empty():
                    continue
                steps.append(StepResult(label=action.target, call=call))
        return states, steps

    # -- apply / revert ---------------------------------------------------

    def apply(self, tweak: Tweak, force: bool = False) -> TweakResult:
        self._check_allowed(tweak)
        outcome = TweakResult(tweak_id=tweak.id, name=tweak.name, dry_run=self.dry_run)

        if tweak.id in self.journal.applied_ids and not force and not self.dry_run:
            # Applying again would re-snapshot the *already modified* state, which
            # would silently turn a revert into a no-op. Refuse instead.
            outcome.skipped.append(f"{tweak.id} is already applied (use --force to re-apply)")
            return outcome

        states = self.probe(tweak)
        inverses = self.inverse_for(tweak, states)

        if not self.dry_run:
            if inverses or tweak.revert:
                self.journal.record(tweak.id, tweak.name, inverses)
            elif tweak.is_reversible:
                outcome.errors.append(
                    f"refusing to apply {tweak.id}: no inverse could be derived"
                )
                return outcome

        for action in tweak.actions:
            calls = shell_calls(action, previous=states.get(action.target))
            if not calls:
                outcome.skipped.append(f"{action.op} ({action.target}) could not be resolved")
                continue
            for call in calls:
                if self.dry_run:
                    outcome.steps.append(StepResult(label=action.target, call=call))
                    continue
                result = self._exec(call)
                step = StepResult(label=action.target, call=call, result=result)
                outcome.steps.append(step)
                if not result.ok:
                    outcome.errors.append(f"{call.render()} -> {result.output}")

        return outcome

    def revert(self, tweak: Optional[Tweak], tweak_id: str) -> TweakResult:
        """Undo a tweak using the journal, falling back to the catalog."""

        entry = self.journal.get(tweak_id)
        inverses: List[Action]
        name = tweak.name if tweak is not None else tweak_id

        if entry is not None:
            inverses = entry.inverse
        elif tweak is not None and tweak.revert:
            inverses = list(tweak.revert)
        else:
            raise EngineError(
                f"nothing to revert for {tweak_id}: it is not in the journal and the "
                "catalog defines no explicit revert"
            )

        outcome = TweakResult(tweak_id=tweak_id, name=name, reverted=True, dry_run=self.dry_run)
        for action in inverses:
            for call in shell_calls(action, previous=None):
                if call.is_empty():
                    continue
                if self.dry_run:
                    outcome.steps.append(StepResult(label=action.target, call=call))
                    continue
                result = self._exec(call)
                step = StepResult(label=action.target, call=call, result=result)
                outcome.steps.append(step)
                if not result.ok:
                    outcome.errors.append(f"{call.render()} -> {result.output}")

        if not self.dry_run and entry is not None and outcome.ok:
            self.journal.forget(tweak_id)
        return outcome

    def revert_all(self) -> List[TweakResult]:
        """Undo everything in the journal, most recent first."""

        results: List[TweakResult] = []
        for entry in list(reversed(self.journal.entries)):
            results.append(self.revert(self.catalog.try_tweak(entry.tweak_id), entry.tweak_id))
        return results

    # -- status -----------------------------------------------------------

    def status(self) -> List[Tuple[Tweak, Optional[bool]]]:
        """For each tweak, whether it looks applied (``None`` = cannot tell)."""

        rows: List[Tuple[Tweak, Optional[bool]]] = []
        for tweak in self.catalog.tweaks:
            rows.append((tweak, self.is_applied(tweak)))
        return rows

    def is_applied(self, tweak: Tweak) -> Optional[bool]:
        verdict: Optional[bool] = None
        for action in tweak.actions:
            call = probe_for(action)
            if call is None:
                continue
            result = self._exec(call)
            state = interpret_probe(action, result.stdout)
            expected = _expected_state(action, state)
            if expected is None:
                continue
            if verdict is None:
                verdict = True
            verdict = verdict and (state == expected)
        return verdict


def _expected_state(action: Action, current: Optional[str]) -> Optional[str]:
    """What ``current`` should look like once the tweak is applied."""

    if action.op == "settings_put":
        return str(action.get("value"))
    if action.op == "settings_delete":
        return ""
    if action.op == "device_config_put":
        return str(action.get("value"))
    if action.op == "pm_disable":
        return "disabled"
    if action.op == "pm_enable":
        return "enabled"
    if action.op == "overlay_enable":
        return "enabled"
    if action.op == "overlay_disable":
        return "disabled"
    return None
