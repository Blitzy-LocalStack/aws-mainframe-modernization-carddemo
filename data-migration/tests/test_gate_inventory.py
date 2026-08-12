"""Hold the documentation gate's own measured inventory to the tree it describes.

Purpose
-------
``data-migration/pyproject.toml`` is the file that CONFIGURES the Rule 1 documentation gate, and
its header block publishes a measured inventory of what that gate covers: how many source modules
exist, how many test modules, how many files ruff lints, and the name of every one. Those figures
live in a different file from the thing they count, so the change that falsifies them never touches
them -- and they went stale in exactly that way four times running, each correction claiming to
have re-measured. A claim of having re-measured is worse than no claim when it is wrong, because it
stops the next reader checking.

This module reads the delimited inventory region and compares every figure and every name against
the directory. The next module added or removed without re-measuring fails a test instead of
publishing a false inventory in the file a contributor consults to learn what already exists.

Alternatives Considered
-----------------------
Three alternatives were evaluated.

(1) **Generating the inventory into ``pyproject.toml`` from the directory.** Rejected for the same
reason the sibling README roster check rejects it: there is no generator in this package's toolchain
to hang it on, and TOML comments are not a generated-documentation target the way an infrastructure
README is. A check that fails loudly is the cheaper mechanism, and it also preserves the prose
rationale interleaved with the figures, which a generator would flatten.

(2) **Parsing the figures out of the surrounding prose, with no delimiters.** Rejected on
measurement: the same header block names earlier, deliberately WRONG figures in its Refactoring
Rationale -- "it said TEN modules", "then TWELVE", "THIRTY-THREE source modules ... after
``copybook/timestamp.py`` ... had landed" -- because recording the history is what stops a fifth
recurrence. A parser loose enough to find the live figures in prose would also collect those, and
would then fail on a correct document. The delimited region is what makes this check's verdict
trustworthy in both directions.

(3) **Asserting the passing-test total as well.** Rejected, and the pyproject block says so at the
figure: a total that fails whenever a test is added gets re-measured mechanically rather than read,
which is precisely the reflex that let the file counts go stale four times. The module inventory is
asserted instead, because it changes only when a file does -- and a file appearing or disappearing
is the event the stale figures always followed.

Assumptions
-----------
The governed-file count is derived from the directory rather than by invoking ruff. Running
``ruff check --show-files`` from a test would make this module depend on the linter being installed
and on its exit status, which is the gate's job rather than this check's; the equivalence is stated
where the figure is, and it is exact -- ruff lints every ``.py`` this package ships plus
``pyproject.toml`` itself, and its default exclusions already drop ``build/`` and ``__pycache__``.
"""

from __future__ import annotations

import re
from pathlib import Path
from typing import Final

# Assumptions: the roots are resolved from THIS file rather than from a working directory, so the
#   check reads the same checkout it lives in however pytest was invoked -- from the repository
#   root, from `data-migration/`, or through an installed distribution with the suite on the path.
_DATA_MIGRATION_ROOT: Final[Path] = Path(__file__).resolve().parents[1]
_SUITE_ROOT: Final[Path] = _DATA_MIGRATION_ROOT / "tests"
_SOURCE_ROOT: Final[Path] = _DATA_MIGRATION_ROOT / "src" / "carddemo_migration"
_PYPROJECT: Final[Path] = _DATA_MIGRATION_ROOT / "pyproject.toml"

# Assumptions: the delimiters are TOML comments, so they are inert to every tool that reads this
#   file as configuration and visible to the one tool -- this test -- that reads it as text. The
#   spelling matches the sibling README roster's convention (`carddemo:<name>:begin`) so a reader
#   who has met one recognises the other.
_INVENTORY_BEGIN: Final[str] = "# carddemo:gate-inventory:begin"
_INVENTORY_END: Final[str] = "# carddemo:gate-inventory:end"

# Assumptions: each figure is one `name = integer` line inside the region, which is the smallest
#   form that is unambiguous to parse and still readable in place as part of the header's prose.
_FIGURE: Final[re.Pattern[str]] = re.compile(r"^#\s+(?P<name>[a-z-]+)\s*=\s*(?P<value>\d+)\s*$")

# Assumptions: the four figures are named here so a region that LOST one fails rather than being
#   compared against whatever it still contains. A missing figure is the same defect as a wrong
#   one: the block would no longer publish the fact it claims to.
_REQUIRED_FIGURES: Final[frozenset[str]] = frozenset(
    {"source-modules", "test-modules", "suite-files", "governed-files"}
)


def _inventory_figures() -> dict[str, int]:
    """Read the delimited inventory region and return each published figure.

    Purpose
    -------
    Extract the measured figures from the gate's own header so they can be compared against the
    directory, without the surrounding prose -- including the deliberately wrong historical
    figures it records -- being mistaken for a live claim.

    Returns
    -------
    dict of str to int
        Each figure name mapped to the integer published for it.

    Raises
    ------
    AssertionError
        If either delimiter is missing, if they appear out of order, if the region contains a line
        that is not a figure, or if a required figure is absent.
    """
    text = _PYPROJECT.read_text(encoding="utf-8")
    assert text.count(_INVENTORY_BEGIN) == 1, f"{_INVENTORY_BEGIN} must appear exactly once"
    assert text.count(_INVENTORY_END) == 1, f"{_INVENTORY_END} must appear exactly once"
    begin = text.index(_INVENTORY_BEGIN) + len(_INVENTORY_BEGIN)
    end = text.index(_INVENTORY_END)
    assert begin < end, "the inventory delimiters are out of order"

    figures: dict[str, int] = {}
    for line in text[begin:end].splitlines():
        if not line.strip():
            continue
        match = _FIGURE.match(line)
        # WHY : Assumptions: an unparsed line inside the region is a FAILURE rather than something
        #   to skip. Skipping would let prose drift into the region and be silently ignored, which
        #   is how a figure ends up stated twice -- once inside the region and once beside it -- and
        #   only one of them checked.
        assert match is not None, (
            f"the inventory region holds a line that is not a figure: {line!r}"
        )
        figures[match["name"]] = int(match["value"])

    missing = _REQUIRED_FIGURES - set(figures)
    assert not missing, f"the inventory region no longer publishes {sorted(missing)}"
    unexpected = set(figures) - _REQUIRED_FIGURES
    assert not unexpected, (
        f"the inventory region publishes an unchecked figure {sorted(unexpected)}"
    )
    return figures


def _source_modules() -> frozenset[str]:
    """List the package's Python modules, as paths relative to the package root.

    Returns
    -------
    frozenset of str
        Every ``.py`` under ``src/carddemo_migration``, in POSIX spelling.

    Raises
    ------
    None
    """
    return frozenset(
        path.relative_to(_SOURCE_ROOT).as_posix()
        for path in _SOURCE_ROOT.rglob("*.py")
        if "__pycache__" not in path.parts
    )


def _test_modules() -> frozenset[str]:
    """List the suite's test module stems.

    Returns
    -------
    frozenset of str
        The stem of every ``test_*.py`` directly under the suite root.

    Raises
    ------
    None
    """
    return frozenset(path.stem for path in _SUITE_ROOT.glob("test_*.py"))


def test_the_published_source_module_count_is_the_directory_s() -> None:
    """Assert the gate's source-module figure equals the package's own module count.

    Returns
    -------
    None
        The assertion is the result.

    Raises
    ------
    AssertionError
        If the published figure and the directory disagree.
    """
    figures = _inventory_figures()
    modules = _source_modules()

    assert figures["source-modules"] == len(modules), (
        f"the gate publishes {figures['source-modules']} source modules and the package holds"
        f" {len(modules)}"
    )


def test_every_source_module_is_named_in_the_gate_s_inventory() -> None:
    """Assert each of the package's modules is named in the header, and nothing else is.

    Returns
    -------
    None
        The assertion is the result.

    Raises
    ------
    AssertionError
        If a module exists that the header does not name.
    """
    # WHY : Assumptions: the NAMES are checked as well as the count, because the two fail
    #   differently. A count alone passes a header that renamed a module, and a header naming a
    #   module the tree no longer holds is what invites a contributor to look for something absent.
    #   The header spells each module as a bare file name in backticks, so the comparison is over
    #   file names rather than over the relative paths the count is taken from -- three of them
    #   (`__init__.py` most obviously) repeat across packages, which is why the count cannot be
    #   derived from this set.
    text = _PYPROJECT.read_text(encoding="utf-8")
    for module in sorted(_source_modules()):
        name = module.rsplit("/", 1)[-1]
        assert f"`{name}`" in text, f"{module} is not named in the gate's inventory"


def test_the_published_test_module_inventory_is_the_suite_directory() -> None:
    """Assert the gate's test-module figure and roster equal the suite's own contents.

    Returns
    -------
    None
        The assertion is the result.

    Raises
    ------
    AssertionError
        If the published figure, the published names, and the directory do not all agree.
    """
    figures = _inventory_figures()
    modules = _test_modules()
    text = _PYPROJECT.read_text(encoding="utf-8")

    assert figures["test-modules"] == len(modules), (
        f"the gate publishes {figures['test-modules']} test modules and the suite holds"
        f" {len(modules)}"
    )
    # WHY : Assumptions: `suite-files` is asserted to be exactly one more than the test modules,
    #   which is the shape the header states -- the test modules plus `conftest.py`. Deriving it
    #   independently would let a second non-test module appear in the suite and be counted without
    #   anyone noticing it had, and the suite's shared doubles all live in `conftest.py` by design.
    assert figures["suite-files"] == figures["test-modules"] + 1
    assert (_SUITE_ROOT / "conftest.py").is_file()
    assert len(list(_SUITE_ROOT.glob("*.py"))) == figures["suite-files"]
    for module in sorted(modules):
        assert f"`{module}`" in text, f"{module} is not named in the gate's inventory"


def test_the_published_governed_file_count_is_what_the_gate_lints() -> None:
    """Assert the governed-file figure is every shipped Python file plus the configuration itself.

    Returns
    -------
    None
        The assertion is the result.

    Raises
    ------
    AssertionError
        If the published figure does not equal the derived one.
    """
    # WHY : Assumptions: the derivation walks `src` and `tests` rather than the whole
    #   `data-migration` directory, because the directory also holds a `build/` tree when a wheel
    #   has been built locally and ruff's default exclusions drop it. Naming the two roots makes the
    #   derived figure independent of whether anything has been built, which a `rglob` over the
    #   parent would not be.
    python_files = len(_source_modules()) + len(list(_SUITE_ROOT.glob("*.py")))
    figures = _inventory_figures()

    assert figures["governed-files"] == python_files + 1, (
        f"the gate publishes {figures['governed-files']} governed files; the tree holds"
        f" {python_files} Python files plus pyproject.toml"
    )
