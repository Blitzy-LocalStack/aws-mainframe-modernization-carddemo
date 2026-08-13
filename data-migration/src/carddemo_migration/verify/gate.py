"""Run the three verification passes as one indivisible gate, in order, stopping at the first fail.

Purpose
-------
Make the combined verification the package documents an object a caller can invoke, rather than a
claim about a command. The three passes -- row counts, record checksums, money-total parity -- are
each blind to the defect the next one catches, so the migration plan requires all three and treats
a load verified by counts alone as unverified. This module is what enforces that mechanically: it
refuses to produce a verdict at all over fewer than three passes, runs them in the fixed order 1,
2, 3, stops at the first failure, and reduces the run to one binary answer.

It orchestrates and judges; it does not verify. Every measurement is taken by
:mod:`carddemo_migration.verify.row_counts`, :mod:`carddemo_migration.verify.checksum` and
:mod:`carddemo_migration.verify.money_parity`, which this module reaches only through the callables
its caller supplies. That is what lets the gate's own rules -- coverage, order, short-circuit,
binary verdict -- be driven with no cluster, no extract and no connection.

Parameters
----------
None
    A module takes no argument. Every published callable documents its own parameters at its own
    definition.

Returns
-------
None
    Importing binds names only. This module opens no connection, constructs no client, reads no
    environment variable and imports neither third-party client, at module scope or otherwise: it
    never imports the three passes at all.

Raises
------
VerificationGateError
    Raised when the steps handed to the gate are not the three declared passes in their declared
    order, so a partial or reordered gate cannot report a verdict.

Design decisions (WHY)
----------------------
Assumptions:
    **The gate refuses a verdict over fewer than three passes, which is why the mandate is
    mechanical rather than procedural.** A caller cannot ask for counts only: omitting a pass, or
    supplying one twice, or supplying them out of order raises before any pass runs. The
    alternative -- accepting whatever steps arrive and reporting a verdict over them -- was
    refused because it would make "verified" mean different things on different runs, and the one
    signal an operator needs from a verification is whether the load can be trusted.
Alternatives Considered:
    **The passes are supplied as callables rather than run from inside this module.** Building the
    three passes here would give the gate a source-resolution contract, a connection factory and a
    dataset vocabulary, and every one of the gate's own rules would then need a cluster and an
    extract to test. Injecting them keeps this module pure -- the short-circuit, the coverage
    refusal and the binary verdict are all driven from doubles -- and keeps each pass's own
    arguments documented where that pass owns them.
Alternatives Considered:
    **A failing pass short-circuits rather than the gate running all three and collecting every
    failure.** Collecting everything was considered and refused for a specific reason rather than
    for cost: pass 1 failing means the wrong NUMBER of rows arrived, at which point pass 2's
    per-record comparison and pass 3's totals are guaranteed to differ as well, and reporting
    three failures for one cause makes an operator triage noise. The order the passes run in is
    what makes each failure interpretable, and stopping is what keeps the report about the cause.
Trade-offs:
    **A pass that RAISES is not caught here.** The gate distinguishes "this pass reached a verdict
    and the verdict is no" from "this pass could not reach a verdict", and only the first is a
    gate outcome. A refusal -- an unreadable extract, a writable session, a result set that
    breaks its column contract -- propagates to the caller, whose own error mapping decides the
    exit status. Swallowing it into a failed outcome would report a data disagreement where the
    truth is that nothing was compared.
Trade-offs:
    **The rendering carries no timestamp, no duration and no field value**, matching both database
    passes, so two gate runs over unchanged data diff to nothing. The accepted cost is that a
    report cannot answer how long a pass took; that belongs to the orchestrator's own execution
    history, and a clock reading here would make every report differ from every other one.
"""

from __future__ import annotations

# WHY : Trade-offs: the standard library only, and NONE of the three pass modules. This module is
#   deliberately importable without pulling in a pass -- pass 1 imports the loader and every reader
#   at its own module scope -- because the gate's rules are about order and coverage, not about any
#   measurement. `Protocol` is what expresses the one shape a pass result must have without this
#   module naming the three concrete result classes and importing them to do it.
from dataclasses import dataclass
from typing import TYPE_CHECKING, Final, Protocol, runtime_checkable

if TYPE_CHECKING:  # pragma: no cover - imported for annotations only
    from collections.abc import Callable, Mapping

# WHY : Alternatives Considered: the surface is DECLARED rather than left to whatever names happen
#   to be bound, matching the three sibling passes, and the constants come first with the remaining
#   names in sorted order so all four modules in this subpackage read the same way.
__all__ = [
    "GATE_PASSES",
    "CombinedPassResult",
    "MigrationVerification",
    "PassOutcome",
    "PassResult",
    "VerificationGateError",
    "run_verification_gate",
]

# WHY : Assumptions: the three passes are named in the order the plan RUNS them -- count, then
#   checksum, then money total -- because that order is load-bearing: a count mismatch explains a
#   checksum mismatch, so running the cheap pass first is what makes the expensive one's failure
#   interpretable. Alphabetical order would put the money pass first and lose that.
# WHY : Trade-offs: these names are spelled here rather than imported from
#   `carddemo_migration.verify.PASS_MODULES`, and the duplication is deliberate. That package
#   resolves this module lazily, so importing the parent's constant from inside it would tie a
#   child's import to its parent's initialisation for one tuple of three strings. The two are held
#   equal by a test instead, which is a mechanical check rather than an agreement.
GATE_PASSES: Final[tuple[str, ...]] = ("row_counts", "checksum", "money_parity")

# WHY : Assumptions: the verdict words are constants and are spelled exactly as the two database
#   passes spell them in their own renderings, so an operator reading a gate report and a pass
#   report in the same run reads one vocabulary rather than two.
_PASSED: Final[str] = "PASSED"
_FAILED: Final[str] = "FAILED"
_NOT_RUN: Final[str] = "NOT RUN"


class VerificationGateError(RuntimeError):
    """Raised when the gate is handed something other than the three declared passes, in order.

    Purpose
    -------
    Refuse a partial, duplicated or reordered gate before any pass runs, so a run that could only
    have produced a partial verdict produces none at all.

    Parameters
    ----------
    None
        Inherits the base exception's own arguments.

    Returns
    -------
    None
        Exception classes are raised, not returned. The inapplicability is stated rather than left
        silent, so a reader can tell a class with no return from a docstring that forgot one.

    Raises
    ------
    None
        Declaring an exception class raises nothing.
    """


@runtime_checkable
class PassResult(Protocol):
    """The one shape a pass result must have to be judged by the gate.

    Purpose
    -------
    Name the two members the gate reads -- a binary verdict and a deterministic rendering -- so the
    gate never imports the three concrete result classes. :class:`~row_counts.RowCountReport`,
    :class:`~checksum.ChecksumComparison` and :class:`~money_parity.MoneyTotalReport` all satisfy
    it already, which is what makes this a description of the existing contract rather than a new
    one imposed on the passes.

    Parameters
    ----------
    None
        A protocol is implemented, not constructed.

    Returns
    -------
    None
        Protocol classes describe a shape; they are not called.

    Raises
    ------
    None
        Declaring a protocol raises nothing.
    """

    @property
    def verified(self) -> bool:
        """Report whether this pass's own measurements agreed.

        Parameters
        ----------
        None
            Reads the result.

        Returns
        -------
        bool
            True when the pass judged its data and found no difference, False otherwise.

        Raises
        ------
        None
            Reading a computed verdict cannot fail.
        """
        ...  # pragma: no cover - protocol declaration

    def render(self) -> str:
        """Render this pass's result as deterministic, privacy-safe text.

        Parameters
        ----------
        None
            Reads the result.

        Returns
        -------
        str
            A multi-line rendering carrying no timestamp and no field value, so two runs over
            unchanged data are byte-identical.

        Raises
        ------
        None
            Rendering cannot fail.
        """
        ...  # pragma: no cover - protocol declaration


@dataclass(frozen=True)
class CombinedPassResult:
    """Fold the several per-dataset results of ONE pass into that pass's single result.

    Purpose
    -------
    Let a pass that runs per dataset -- the checksum pass compares one dataset at a time -- present
    the whole migration as one result the gate can judge. Without this, the caller would have to
    reduce those parts itself and the "nothing judged is not verified" rule would live in the
    caller rather than beside the gate that depends on it.

    Parameters
    ----------
    label : str
        What the parts are of, rendered as the aggregate's heading. A dataset-independent name such
        as ``"record checksums"``, never a value.
    parts : tuple[PassResult, ...]
        The per-dataset results, in the order they were produced. Rendering preserves that order,
        because the gate's output must be diffable between runs.

    Returns
    -------
    None
        Construction validates nothing: emptiness is not an error to raise, it is a state the
        verdict below reports as unverified.

    Raises
    ------
    None
        Holding already-validated results cannot fail.
    """

    label: str
    parts: tuple[PassResult, ...]

    @property
    def verified(self) -> bool:
        """Report whether every part agreed, and whether there was any part at all.

        Parameters
        ----------
        None
            Reads :attr:`parts`.

        Returns
        -------
        bool
            True when at least one part was judged and every part verified. An EMPTY aggregate is
            NOT verified.

        Raises
        ------
        None
            Reducing validated results to one verdict cannot fail.
        """
        # WHY : Assumptions: an empty aggregate is unverified, matching `RowCountReport.verified`
        #   and `MoneyTotalReport.verified`, because a run that judged nothing has proved nothing.
        #   The failure this guards against is real and silent: a dataset vocabulary that resolved
        #   to no datasets, or a loop whose iterable was already consumed, would otherwise report a
        #   clean pass over zero comparisons and the gate would certify a load nothing had read.
        return bool(self.parts) and all(part.verified for part in self.parts)

    def render(self) -> str:
        """Render every part in order beneath one heading.

        Parameters
        ----------
        None
            Reads :attr:`label` and :attr:`parts`.

        Returns
        -------
        str
            The heading, the part count and each part's own rendering, in order. Carries no
            timestamp and no field value, because each part's rendering carries neither.

        Raises
        ------
        None
            Rendering already-validated results cannot fail.
        """
        # WHY : Assumptions: the part count is stated even when it is zero, because "0 result(s)"
        #   is the only visible evidence of the empty aggregate the verdict above refuses. A
        #   heading alone would render an empty aggregate and a missing one identically.
        lines = [f"{self.label}: {len(self.parts)} result(s)"]
        lines.extend(part.render() for part in self.parts)
        return "\n".join(lines)


@dataclass(frozen=True)
class PassOutcome:
    """What one pass of the gate produced.

    Parameters
    ----------
    pass_name : str
        The pass's name, one of :data:`GATE_PASSES`.
    position : int
        The pass's one-based position in the gate, so a rendering states which of the three this
        was without the reader counting lines.
    result : PassResult
        The pass's own result, kept whole rather than reduced to a boolean so a caller can read the
        detail behind the verdict without re-running the pass.

    Returns
    -------
    None
        Construction validates nothing; :func:`run_verification_gate` builds these from a pass it
        has already run.

    Raises
    ------
    None
        Holding an already-produced result cannot fail.
    """

    pass_name: str
    position: int
    result: PassResult

    @property
    def verified(self) -> bool:
        """Report this pass's own verdict.

        Parameters
        ----------
        None
            Reads :attr:`result`.

        Returns
        -------
        bool
            The result's verdict, unmodified. The gate never softens a pass's answer.

        Raises
        ------
        None
            Reading a computed verdict cannot fail.
        """
        return self.result.verified

    def render(self) -> str:
        """Render this outcome as a heading line followed by the pass's own report.

        Parameters
        ----------
        None
            Reads the outcome.

        Returns
        -------
        str
            The heading -- position, pass name and verdict -- and the result's own rendering.

        Raises
        ------
        None
            Rendering cannot fail.
        """
        verdict = _PASSED if self.verified else _FAILED
        heading = f"pass {self.position}/{len(GATE_PASSES)} {self.pass_name}: {verdict}"
        return f"{heading}\n{self.result.render()}"


@dataclass(frozen=True)
class MigrationVerification:
    """The one binary verdict the combined gate produces, and the outcomes behind it.

    Parameters
    ----------
    outcomes : tuple[PassOutcome, ...]
        The passes that RAN, in the order they ran. Shorter than :data:`GATE_PASSES` exactly when a
        pass failed and the gate stopped, which is what :attr:`not_run` reports.

    Returns
    -------
    None
        Construction validates nothing; :func:`run_verification_gate` is what builds these.

    Raises
    ------
    None
        Holding already-produced outcomes cannot fail.
    """

    outcomes: tuple[PassOutcome, ...]

    @property
    def verified(self) -> bool:
        """Report whether the whole migration verified.

        Parameters
        ----------
        None
            Reads :attr:`outcomes`.

        Returns
        -------
        bool
            True only when all three passes ran and every one of them verified. A run that stopped
            early is NOT verified, and neither is an empty one.

        Raises
        ------
        None
            Reducing outcomes to one verdict cannot fail.
        """
        # WHY : Assumptions: the count is checked as well as the verdicts, so a gate that stopped
        #   after two passing passes cannot report success. Checking only `all(...)` would make a
        #   short-circuited run indistinguishable from a complete one -- and `all(())` is True, so
        #   a gate that ran nothing at all would certify the load.
        return len(self.outcomes) == len(GATE_PASSES) and all(
            outcome.verified for outcome in self.outcomes
        )

    @property
    def failure(self) -> PassOutcome | None:
        """Return the outcome that stopped the gate.

        Parameters
        ----------
        None
            Reads :attr:`outcomes`.

        Returns
        -------
        PassOutcome | None
            The first outcome that did not verify, or ``None`` when every pass that ran verified.

        Raises
        ------
        None
            Filtering already-produced outcomes cannot fail.
        """
        for outcome in self.outcomes:
            if not outcome.verified:
                return outcome
        return None

    @property
    def not_run(self) -> tuple[str, ...]:
        """Return the passes the gate never reached.

        Parameters
        ----------
        None
            Reads :attr:`outcomes`.

        Returns
        -------
        tuple[str, ...]
            The declared pass names after the last one that ran, in declared order. Empty when the
            gate ran to completion.

        Raises
        ------
        None
            Slicing a declared tuple cannot fail.
        """
        return GATE_PASSES[len(self.outcomes) :]

    def render(self) -> str:
        """Render the whole gate: every pass that ran, every pass that did not, and the verdict.

        Parameters
        ----------
        None
            Reads :attr:`outcomes`.

        Returns
        -------
        str
            A deterministic multi-line rendering: each outcome's own report in order, one line per
            pass that never ran, and a final verdict line. Carries no timestamp, no duration and no
            field value.

        Raises
        ------
        None
            Rendering already-produced outcomes cannot fail.
        """
        lines = [f"migration verification: {len(GATE_PASSES)} mandatory pass(es)"]
        lines.extend(outcome.render() for outcome in self.outcomes)
        # WHY : Assumptions: the passes that never ran are listed EXPLICITLY rather than left out
        #   of the report. A gate that stopped after pass 1 would otherwise render exactly like a
        #   gate configured with one pass, and the reader could not tell "two passes are unproven"
        #   from "two passes were not required" -- which is the distinction the whole mandate rests
        #   on. The reason is stated on the line so the report explains itself.
        for offset, pass_name in enumerate(self.not_run, start=len(self.outcomes) + 1):
            lines.append(
                f"pass {offset}/{len(GATE_PASSES)} {pass_name}: {_NOT_RUN}"
                " (a preceding pass failed, so this evidence is missing)"
            )
        verdict = _PASSED if self.verified else _FAILED
        lines.append(
            f"migration verification {verdict}:"
            f" {len(self.outcomes)} of {len(GATE_PASSES)} pass(es) run,"
            f" {sum(1 for outcome in self.outcomes if outcome.verified)} verified"
        )
        return "\n".join(lines)


def run_verification_gate(
    steps: Mapping[str, Callable[[], PassResult]],
) -> MigrationVerification:
    """Run the three mandatory passes in declared order, stopping at the first that fails.

    Purpose
    -------
    Be the combined verification the package contracts: the single call that makes "verified" mean
    all three passes agreed, and that cannot be asked for less.

    Parameters
    ----------
    steps : Mapping[str, Callable[[], PassResult]]
        Exactly the three names in :data:`GATE_PASSES`, in that order, each mapped to a
        zero-argument callable that runs that pass and returns its result. Nothing is passed to the
        callables: each caller closes over the connection, source and dataset vocabulary its own
        pass needs, which is what keeps those contracts out of this module.

    Returns
    -------
    MigrationVerification
        The outcomes of the passes that ran and the single binary verdict over them. A failure is
        REPORTED here rather than raised, so one call names the pass that failed and renders its
        detail.

    Raises
    ------
    VerificationGateError
        If ``steps`` is not exactly the three declared passes in declared order. Raised BEFORE any
        pass runs, so a misconfigured gate performs no measurement.
    """
    # WHY : Assumptions: the keys are compared as an ORDERED tuple, not as a set. A mapping
    #   preserves insertion order in every Python this distribution supports, so comparing the
    #   tuple catches a reordered gate as well as an incomplete one -- and order is load-bearing
    #   here, because running the money pass before the count pass would report a money difference
    #   whose cause a count pass would have named in one line.
    supplied = tuple(steps)
    if supplied != GATE_PASSES:
        # WHY : Trade-offs: the message names the passes rather than echoing the caller's keys
        #   verbatim beyond what is needed to see the difference. Both sides are pass NAMES, so
        #   nothing sensitive is disclosed, and naming the expectation is what makes a reordered
        #   gate self-diagnosing.
        raise VerificationGateError(
            "the verification gate runs exactly the passes"
            f" {', '.join(GATE_PASSES)} in that order;"
            f" received {', '.join(supplied) if supplied else '(none)'}"
        )
    outcomes: list[PassOutcome] = []
    for position, pass_name in enumerate(GATE_PASSES, start=1):
        outcome = PassOutcome(pass_name=pass_name, position=position, result=steps[pass_name]())
        outcomes.append(outcome)
        # WHY : Assumptions: the loop stops at the first failing pass rather than collecting all
        #   three failures. The passes are ordered so that an earlier failure EXPLAINS a later one
        #   -- the wrong number of rows guarantees differing digests and differing totals -- so
        #   continuing would report one cause three times and cost a full re-read of every extract
        #   to do it.
        if not outcome.verified:
            break
    return MigrationVerification(outcomes=tuple(outcomes))
