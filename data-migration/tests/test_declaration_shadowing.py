"""Refuse a module-level name that is declared twice, because the second one wins silently.

Purpose
-------
Assert that no module in this package or in this suite declares the same module-level name
more than once. A repeated declaration is not a syntax error and not a lint finding: Python
evaluates both and the LAST one wins, so the module works, imports cleanly, and answers to
the definition a reader is least likely to be looking at. That makes it the rare defect with
no symptom at all until the two copies stop agreeing, at which point the surviving behaviour
is whichever copy happens to sit lower in the file.

Refactoring Rationale: this module exists because exactly that happened to a security
control. ``layouts._MASK_HMAC_KEY_MIN_BYTES`` and ``layouts._mask_hmac_key`` -- the floor
the redaction key must clear and the resolver that enforces it -- were each declared twice,
in two blocks that were byte-identical at the time and 127 lines long. Two consequences made
it worth a permanent gate rather than a one-line correction. The surviving definition was the
LOWER one, which carried no comment, so the provenance recorded for this control -- its
registration as ``D-ETL-MASK-KEY-STRENGTH`` and the operator-facing half of the rule in
``docs/runbooks/deploy.md`` -- was attached to the copy Python discarded. And a future edit
correcting the floor, or tightening the refusal, would have been applied to one copy with no
observable effect and no failing test, which is indistinguishable from the edit working.

Alternatives Considered:
    Four alternatives were evaluated. (1) Relying on ``ruff``: the pinned configuration
    selects the D, E, W, F, I and TID252 families with nothing ignored, and none of them
    reports this. F811 is the closest rule and it is scoped to a redefinition of an unused
    name, so a redeclaration of a name that IS used -- which every case worth catching is --
    passes. Measured against the pinned ruff on the real duplicate, not inferred from the
    rule table. (2) Asserting only the two masking names, which is where the defect was
    found. Rejected because the hazard is a property of module-level declaration and not of
    masking, and a check that names its subjects has to be extended by whoever adds the next
    one -- which is the same manual step that failed here. (3) Comparing the two blocks for
    equality and permitting an exact duplicate as harmless. Rejected outright: an exact
    duplicate is the state this defect was FOUND in and the state it is least harmful in, so
    permitting it means the gate only fires once the copies have already diverged. (4)
    Extending ``tests/test_docstring_gate.py``, which already walks these two trees -- and
    ``config/rule1`` besides -- with ``ast``.
    Rejected because that module is named and documented for docstring presence, and a file
    whose name understates what it enforces is the failure this checkpoint is about.

Assumptions:
    Only names bound by a declaration at the TOP level of a module body are compared, and a
    binding made inside an ``if``, ``try`` or function body is deliberately out of scope. The
    exclusion is not a simplification: rebinding a name under ``if TYPE_CHECKING`` or in an
    ``except ImportError`` fallback is a legitimate idiom whose whole purpose is to supply one
    name by two routes, and a gate that reported it would be silenced rather than obeyed.
    Restricting the walk to ``ast.Module.body`` expresses that by construction, so there is no
    exemption list to keep current.

Assumptions:
    Both roots resolve from this file rather than through the imported package, for the reason
    the sibling docstring gate records: this suite deliberately runs against the INSTALLED
    distribution and sets no import path, so ``carddemo_migration.__file__`` would reach
    neither this suite nor the source tree a reviewer edits. The subject is the source under
    version control, and reading it from disk is also what lets the gate cover itself.

Trade-offs:
    A decorated function declared once per overload signature would be reported, since
    ``typing.overload`` legitimately repeats a name. That costs nothing today -- neither tree
    contains a single ``@overload``, measured rather than assumed -- and if one is ever
    introduced the gate should be taught the decorator rather than deleted. Recorded here so
    the first person to hit it knows which of the two it is.
"""

from __future__ import annotations

import ast
from pathlib import Path

import pytest

# Assumptions: both roots resolve from this file, so the gate behaves identically whether it
#   is invoked from the repository root, from `data-migration/`, or by an editor.
_DATA_MIGRATION_ROOT = Path(__file__).resolve().parents[1]
_PACKAGE_ROOT = _DATA_MIGRATION_ROOT / "src" / "carddemo_migration"
_SUITE_ROOT = _DATA_MIGRATION_ROOT / "tests"

# Assumptions: only these two trees are in scope, named explicitly rather than reached by
#   globbing upward. The repository's own `tests/` tree is the COBOL parity oracle and is
#   reference-only, and naming the roots is what keeps it out of a recursive walk.
_ROOTS = (_PACKAGE_ROOT, _SUITE_ROOT)

# Assumptions: a floor rather than an exact figure, because the gate must not pass by
#   measuring nothing and must not need editing every time a module is added. The measured
#   count across both trees is far above this at the time of writing, so the floor catches a
#   walk that silently stopped finding declarations without objecting to ordinary growth.
_MINIMUM_BINDINGS = 500


def _python_sources(root: Path) -> list[Path]:
    """List the Python files under a root, in a stable order.

    Parameters
    ----------
    root : Path
        Directory to search recursively.

    Returns
    -------
    list[Path]
        Every ``.py`` file, excluding compiled-cache directories, sorted by path.

    Raises
    ------
    AssertionError
        If the root does not exist or holds no Python file. Both cases would make the gate
        pass by measuring nothing, so they are failures rather than empty results.
    """
    assert root.is_dir(), f"shadowing gate root is missing: {root}"
    sources = sorted(p for p in root.rglob("*.py") if "__pycache__" not in p.parts)
    assert sources, f"shadowing gate root holds no Python source: {root}"
    return sources


def _module_level_bindings(source: str) -> dict[str, list[int]]:
    """Map each name declared at a module's top level to the lines that declare it.

    Parameters
    ----------
    source : str
        The full text of one Python module.

    Returns
    -------
    dict[str, list[int]]
        One entry per distinct name, whose value lists the one-based line number of every
        top-level declaration of it, in file order. A name declared once yields a
        single-element list.

    Raises
    ------
    SyntaxError
        If the text does not parse, which is a defect in the file rather than in the gate and
        is therefore propagated rather than reported as a duplicate.
    """
    bindings: dict[str, list[int]] = {}

    # WHY : Assumptions: the walk is over `tree.body` alone and never recursive. That is the
    #       mechanism by which a conditional rebinding stays out of scope, as the module
    #       docstring argues, and it also means an inner function shadowing an outer name --
    #       ordinary lexical scoping, not a defect -- can never be reported.
    for node in ast.parse(source).body:
        if isinstance(node, ast.FunctionDef | ast.AsyncFunctionDef | ast.ClassDef):
            declared = [node.name]
        elif isinstance(node, ast.Assign):
            # WHY : Assumptions: only `Name` targets are collected, so `a = b = value` counts
            #       both names while a tuple unpacking or an attribute or subscript target
            #       contributes none. An attribute assignment does not declare a module-level
            #       name at all, and a tuple target's elements are bound as a group whose
            #       repetition is a different question from a redeclared definition.
            declared = [t.id for t in node.targets if isinstance(t, ast.Name)]
        elif isinstance(node, ast.AnnAssign) and isinstance(node.target, ast.Name):
            declared = [node.target.id]
        else:
            declared = []

        for name in declared:
            bindings.setdefault(name, []).append(node.lineno)

    return bindings


def _duplicated(source: str) -> dict[str, list[int]]:
    """Reduce one module's bindings to only the names it declares more than once.

    Parameters
    ----------
    source : str
        The full text of one Python module.

    Returns
    -------
    dict[str, list[int]]
        One entry per repeated name, whose value lists every declaring line. Empty when the
        module declares each of its top-level names exactly once.
    """
    return {name: lines for name, lines in _module_level_bindings(source).items() if len(lines) > 1}


def _format_duplicates(path: Path, duplicated: dict[str, list[int]]) -> str:
    """Render one file's repeated declarations as a reviewable report.

    Parameters
    ----------
    path : Path
        The file the names were found in.
    duplicated : dict[str, list[int]]
        Repeated names mapped to their declaring lines, as returned by :func:`_duplicated`.

    Returns
    -------
    str
        One line per repeated name naming the file, the name, the surviving line and the
        shadowed ones. The surviving line is called out because it is the one Python keeps and
        the one a reader is least likely to have been editing.
    """
    return "\n".join(
        f"{path}: '{name}' is declared {len(lines)} times, at lines"
        f" {', '.join(str(line) for line in lines)}; line {lines[-1]} wins and the rest are"
        " dead code that no test can reach"
        for name, lines in sorted(duplicated.items())
    )


@pytest.mark.parametrize("root", _ROOTS, ids=["package", "suite"])
def test_no_module_level_name_is_declared_twice(root: Path) -> None:
    """Assert every module in one tree declares each of its top-level names once.

    Parameters
    ----------
    root : Path
        The package source tree or this suite's own tree, supplied by parametrisation.

    Returns
    -------
    None
        The assertion is the result.

    Raises
    ------
    AssertionError
        If any module declares a top-level name more than once. The message names every
        occurrence and which one survives, because the correction is to delete the right copy
        and that depends on which one carries the reasoning.
    """
    reports = [
        _format_duplicates(path, duplicated)
        for path in _python_sources(root)
        if (duplicated := _duplicated(path.read_text(encoding="utf-8")))
    ]

    assert not reports, (
        "a module-level name is declared more than once, so the lower declaration silently"
        " replaces the upper one and any edit to the upper one has no effect:\n"
        + "\n".join(reports)
    )


def test_the_gate_sees_enough_declarations_to_be_meaningful() -> None:
    """Assert the walk reaches a plausible number of declarations, not zero.

    Returns
    -------
    None
        The assertion is the result.

    Raises
    ------
    AssertionError
        If the two trees together yield fewer top-level names than the floor. A walk that
        stopped finding declarations would report no duplicates and pass, which is the one
        way this gate could fail silently, so the count is asserted rather than assumed.
    """
    counted = sum(
        len(_module_level_bindings(path.read_text(encoding="utf-8")))
        for root in _ROOTS
        for path in _python_sources(root)
    )

    assert counted >= _MINIMUM_BINDINGS, (
        f"the shadowing gate found only {counted} module-level names across {_PACKAGE_ROOT}"
        f" and {_SUITE_ROOT}, below the floor of {_MINIMUM_BINDINGS}; a walk that finds"
        " nothing reports no duplicates and passes"
    )


def test_the_gate_catches_the_defect_it_was_written_for(tmp_path: Path) -> None:
    """Assert the collector reports a duplicate of the exact shape that was found in the tree.

    Parameters
    ----------
    tmp_path : Path
        Per-test temporary directory supplied by pytest, used so the specimen is never a file
        the gate itself walks.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the collector misses the repeated constant or the repeated function, or if it
        reports a name that appears once. A gate asserted only against a clean tree cannot
        distinguish working from vacuous, so the positive case is exercised here.
    """
    # WHY : Assumptions: the specimen reproduces the real defect's shape rather than a minimal
    #       one -- an annotated constant and a function repeated together, with an unrelated
    #       single declaration between them and an inner name reusing an outer one. The
    #       intervening declaration is what proves the collector does not simply compare
    #       adjacent nodes, and the inner reuse is what proves it does not descend into a
    #       function body and report ordinary shadowing.
    specimen = tmp_path / "specimen.py"
    specimen.write_text(
        "from typing import Final\n"
        "\n"
        "_FLOOR: Final[int] = 32\n"
        "\n"
        "\n"
        "def _resolve() -> int:\n"
        '    """Return the floor."""\n'
        "    return _FLOOR\n"
        "\n"
        "\n"
        "_UNRELATED: Final[str] = 'x'\n"
        "\n"
        "\n"
        "_FLOOR: Final[int] = 32\n"
        "\n"
        "\n"
        "def _resolve() -> int:\n"
        '    """Return the floor."""\n'
        "    _UNRELATED = 1\n"
        "    return _UNRELATED\n",
        encoding="utf-8",
    )

    duplicated = _duplicated(specimen.read_text(encoding="utf-8"))

    assert sorted(duplicated) == ["_FLOOR", "_resolve"]
    assert duplicated["_FLOOR"] == [3, 14]
    assert duplicated["_resolve"] == [6, 17]
    assert "_UNRELATED" not in duplicated
    assert "line 14 wins" in _format_duplicates(specimen, duplicated)
