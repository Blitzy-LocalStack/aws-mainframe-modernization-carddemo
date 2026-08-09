"""Hold the README's closed roster of test modules to the directory it describes.

Purpose
-------
``data-migration/README.md`` publishes a closed inventory of this suite's modules -- "All N
modules are delivered", followed by a list. That figure and that list live in a different file
from the thing they count, so the change that falsifies them never touches them, and they went
stale in exactly that way: the README claimed sixteen modules, listed seventeen, and omitted
seven that existed, while the paragraph beneath the list asserted that both had been
"re-measured ... rather than incremented". A claim of having re-measured is worse than no claim
when it is wrong, because it stops the next reader checking. This module reads the roster and
counts the directory, so the next such change fails a test instead of publishing a false
document.

Alternatives Considered
-----------------------
Three alternatives were evaluated.

(1) **Generating the roster from the directory at build time**, as ``terraform-docs`` does for
the infrastructure READMEs. Rejected because there is no generator in this package's toolchain
to hang it on, and introducing one to maintain nine lines of Markdown would be a larger
standing cost than the check below.

(2) **Parsing the counts and names out of the README's prose**, with no delimiters. Rejected on
measurement: this README links several test modules outside the roster -- in the disclosure
policy section, in the masking-key section and in the runbook cross-references -- so a parser
loose enough to find the roster would also collect those and would fail on a correct document.
The fenced region is what makes the check's verdict trustworthy in both directions.

(3) **Adding the check to** ``test_docstring_gate.py``, which already reads files rather than
importing them. Rejected because that module's charter is the Rule 1 docstring gate and its
whole value is that a failure there means one thing; a roster failure arriving under that name
would be read as a documentation-gate failure and investigated in the wrong file.

Assumptions
-----------
``conftest.py`` is counted as a module of the suite because the README lists it as one. It is
not a test module, which is why the README states the total and the test-module count
separately, and why this module asserts both rather than one.
"""

from __future__ import annotations

import re
from pathlib import Path

# Assumptions: the roots are resolved from this file rather than from the working directory, so
#   the checks hold under `pytest data-migration/tests` from the repository root and under
#   `pytest` from inside the package, which are both documented invocations.
_DATA_MIGRATION_ROOT = Path(__file__).resolve().parents[1]
_SUITE_ROOT = _DATA_MIGRATION_ROOT / "tests"
_README = _DATA_MIGRATION_ROOT / "README.md"

# Assumptions: the fence is an HTML comment pair, so it is invisible in a rendered README while
#   being unambiguous to a parser. A Markdown heading would have been visible and would have
#   made the region's extent depend on the next heading, which is exactly the fragility the
#   explicit end marker removes.
_ROSTER_BEGIN = "<!-- carddemo:test-module-roster:begin -->"
_ROSTER_END = "<!-- carddemo:test-module-roster:end -->"

# Assumptions: a roster entry is a Markdown link whose label is the file name in backticks.
#   Matching the LABEL rather than the target is deliberate: a label is what a reader sees, and
#   a mismatched pair is caught separately by the target check below.
_ENTRY = re.compile(r"\[`(?P<name>[A-Za-z0-9_]+\.py)`\]\((?P<target>[^)]+)\)")

# Assumptions: the sentence carrying the two counts is matched as a whole rather than by
#   scanning for digits, so a number appearing elsewhere in the surrounding prose cannot be
#   read as one of them.
_COUNTS = re.compile(
    r"All \*\*(?P<total>\d+)\*\* modules are delivered — \*\*(?P<tests>\d+)\*\* test modules"
)


def _readme_text() -> str:
    """Return the README's contents.

    Returns
    -------
    str
        The whole file, decoded as UTF-8.

    Raises
    ------
    AssertionError
        If the README is absent, which fails rather than skips: an absent README is exactly
        the case in which its claims would otherwise go unchecked.
    """
    assert _README.is_file(), f"{_README} is missing, so its roster cannot be checked"
    return _README.read_text(encoding="utf-8")


def _roster_region(text: str) -> str:
    """Return the fenced roster region of the README.

    Parameters
    ----------
    text : str
        The README's contents.

    Returns
    -------
    str
        The text between the two fence markers, exclusive of both.

    Raises
    ------
    AssertionError
        If either marker is absent or they appear in the wrong order. Removing a marker is how
        a failing assertion here would be silenced, so its absence is a failure rather than a
        skip.
    """
    begin = text.find(_ROSTER_BEGIN)
    end = text.find(_ROSTER_END)
    assert begin != -1, f"{_README} carries no {_ROSTER_BEGIN} marker"
    assert end != -1, f"{_README} carries no {_ROSTER_END} marker"
    assert begin < end, f"{_README} carries the roster markers in the wrong order"
    return text[begin + len(_ROSTER_BEGIN) : end]


def _declared_modules() -> list[tuple[str, str]]:
    """Return the roster's entries as name and link-target pairs.

    Returns
    -------
    list[tuple[str, str]]
        One pair per entry, in the order the README lists them.
    """
    return [
        (match.group("name"), match.group("target"))
        for match in _ENTRY.finditer(_roster_region(_readme_text()))
    ]


def _present_modules() -> set[str]:
    """Return the file names of every Python module in the suite directory.

    Returns
    -------
    set[str]
        File names, not paths, so they compare directly against the roster's labels.
    """
    return {path.name for path in _SUITE_ROOT.glob("*.py")}


def test_the_readme_roster_is_exactly_the_suite_directory() -> None:
    """Assert the README's module roster equals the modules on disk.

    Returns
    -------
    None
        Nothing; a roster that omits a module or names an absent one is reported as an
        assertion failure naming the difference in both directions.
    """
    declared = {name for name, _ in _declared_modules()}
    present = _present_modules()

    # WHY (Assumptions): equality is asserted rather than containment, and both directions
    #   matter for different reasons. A module present and unlisted is the failure that occurred
    #   -- seven of them -- and it understates the suite; a module listed and absent is a dead
    #   link that tells a reader to open a file that is not there.
    assert declared == present, (
        "the README's test-module roster and data-migration/tests disagree: missing from the"
        f" README={sorted(present - declared)}; named by the README but absent"
        f"={sorted(declared - present)}"
    )


def test_every_roster_entry_links_to_the_file_it_names() -> None:
    """Assert each roster entry's link target resolves to the module its label names.

    Returns
    -------
    None
        Nothing; a label and target that disagree, or a target that does not resolve, is
        reported as an assertion failure.
    """
    for name, target in _declared_modules():
        resolved = (_DATA_MIGRATION_ROOT / target).resolve()
        # WHY (Assumptions): the label and the target are checked against each other as well as
        #   against the filesystem. A pair whose target exists but names a DIFFERENT module
        #   reads correctly and navigates wrongly, which no existence check alone would catch.
        assert resolved.name == name, (
            f"the roster entry labelled {name} links to {target}, which names a different module"
        )
        assert resolved.is_file(), f"the roster entry {name} links to {target}, which is absent"


def test_the_stated_module_counts_match_the_directory() -> None:
    """Assert the README's two stated counts match the suite directory.

    Returns
    -------
    None
        Nothing; a stated count that disagrees with the directory is reported as an assertion
        failure.
    """
    stated = _COUNTS.search(_readme_text())
    # WHY (Assumptions): the sentence's absence is a failure rather than a skip. Rewording it is
    #   the way this check would be silenced without anybody removing it, so the check reports
    #   the rewording instead of quietly passing.
    assert stated is not None, (
        f"{_README} no longer carries the sentence stating the module counts in the form"
        ' "All **N** modules are delivered — **M** test modules"'
    )

    present = _present_modules()
    test_modules = {name for name in present if name.startswith("test_")}

    assert int(stated.group("total")) == len(present), (
        f"the README states {stated.group('total')} modules; the directory holds {len(present)}"
    )
    assert int(stated.group("tests")) == len(test_modules), (
        f"the README states {stated.group('tests')} test modules; the directory holds"
        f" {len(test_modules)}"
    )
