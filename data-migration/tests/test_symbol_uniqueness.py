"""Refuse a module that binds one top-level name twice, because the first binding vanishes.

Purpose
-------
Close the measured gap between what this package's lint configuration can see and the one
defect it demonstrably could not. ``copybook/layouts.py`` declared ``_MASK_HMAC_KEY_MIN_BYTES``
and ``_mask_hmac_key`` TWICE, byte-identically, about 128 lines apart. Python evaluates both in
order, so the second declaration silently replaced the first and every caller ran the later
copy. Nothing failed: the module imported, the suite passed, and the two copies agreed -- which
is exactly what makes the shape dangerous. Edit one copy and the module still imports, the suite
still passes, and the edit simply has no effect, with no diagnostic for the author to read.

``data-migration/pyproject.toml`` selects the pyflakes ``F`` family, so F811 "Redefinition of
unused name" is live in this package. It reports NEITHER half of that defect. Measured against
the pinned ruff 0.16.0 rather than inferred from its rule table:

- a PUBLIC module-level ``def`` or ``class`` redefined IS reported;
- a SINGLE-UNDERSCORE-named ``def`` or ``class`` redefined is SILENT, because the name matches
  ruff's default ``lint.dummy-variable-rgx``; withdrawing that pattern makes both cases report,
  which is how the cause was confirmed rather than guessed;
- a dunder ``def`` such as ``__getattr__`` IS reported, because a trailing-underscore name does
  not match that pattern -- the two conventions are close enough to be mistaken for each other;
- a module-level assignment or annotated assignment re-declared is SILENT whatever its name, and
  stays silent with that pattern withdrawn, because rebinding a module-level variable is
  ordinary Python and outside a redefinition check's remit;
- a duplicate ``import`` IS reported.

The defect therefore sat in two blind spots at once: the constant by KIND, the function by
VISIBILITY. This module is the enforcement for both. F811 also stops reporting a public
definition once the earlier binding is read BETWEEN the two declarations -- measured -- so this
gate is the only unconditional one even for the shapes F811 can see.

Alternatives Considered:
    (1) Narrowing ``lint.dummy-variable-rgx`` in ``pyproject.toml`` so F811 sees privately named
    definitions. Measured: it does make the private ``def`` and ``class`` cases report, and it
    adds no new finding over either tree today, so it is cheap -- but it still cannot see the
    annotated constant, which was half of this defect, and it repurposes a setting whose
    documented meaning is "names that are intentionally unused" to steer an unrelated check.
    Rejected as half a fix bought with a semantic mismatch. (2) A standalone lint script wired
    into a workflow was rejected for the reason ``test_docstring_gate`` records: this suite
    already runs fail-closed, so a test cannot be forgotten in a workflow edit. (3) Leaving the
    shape to review was rejected on the evidence -- the duplicate reached the branch and was
    found only by a reviewer reading a 5,800-line module, so the check belongs at the edit.

Assumptions:
    Only DIRECT children of the module node are counted. That is what keeps a conditional
    alternative -- ``try``/``except ImportError``, ``if TYPE_CHECKING``, an ``if``/``else`` pair
    -- out of scope by construction, since at most one branch binds at run time and neither
    shadows the other. Both trees were checked for the remaining module-level rebinding forms:
    neither contains a module-level ``del`` or ``global``, nor a PEP 695 ``type`` alias, so no
    further statement kind needs handling for the sources this gate actually reads.

Trade-offs:
    The gate compares NAMES, not bodies. Two differently named copies of one block are
    duplication it cannot see, and one name bound once in each of two modules is ordinary and is
    not reported. Its remit is the single failure mode where one module binds one name twice and
    the earlier binding disappears without a diagnostic. Class bodies are deliberately not
    descended into: a class body sanctions a repeated name through a ``@property`` and
    ``@x.setter`` pair, so covering it would need a second exemption for a defect shape neither
    tree has produced, whereas module scope is where this one was measured. ``@overload`` is the
    one module-level repetition Python itself sanctions, and is exempted by decorator name.
    File discovery is spelled out here rather than shared with ``test_docstring_gate``: the only
    place two test modules could share it is ``conftest.py``, which every module in this tree
    imports, so widening one gate's four-line expression to suite scope would trade a small
    repetition for a large blast radius.

Refactoring Rationale:
    The walk is self-tested against a synthetic matrix rather than trusted, for the same reason
    the docstring gate self-tests its own walker: this gate's only dangerous failure is the quiet
    one. A walk that skipped assignments, or that stopped at a module's first duplicate, would
    report nothing, keep passing, and cover half of what its name claims.
"""

from __future__ import annotations

import ast
import re
from collections import defaultdict
from pathlib import Path
from typing import NamedTuple

import pytest

# Assumptions: both roots resolve from this file, so the gate behaves identically whether it is
#   invoked from the repository root, from `data-migration/`, or by an editor. This mirrors the
#   anchoring `test_docstring_gate` uses, and for the same reason: this package runs its suite
#   against the INSTALLED distribution, so an imported `carddemo_migration.__file__` could point
#   at a different checkout than the source a reviewer is editing.
_DATA_MIGRATION_ROOT = Path(__file__).resolve().parents[1]
_PACKAGE_ROOT = _DATA_MIGRATION_ROOT / "src" / "carddemo_migration"
_SUITE_ROOT = _DATA_MIGRATION_ROOT / "tests"

# Assumptions: only these two trees are in scope. The repository's own `tests/` tree is the COBOL
#   parity oracle and is reference-only, so naming both roots explicitly rather than globbing
#   upward is what keeps this gate off it.
_ROOTS = (_PACKAGE_ROOT, _SUITE_ROOT)

# WHY : Assumptions: this is ruff's EFFECTIVE `lint.dummy-variable-rgx` in this package, read
#   from `ruff check --show-settings` rather than transcribed from documentation. The project
#   sets no value, so the tool default applies, and the default matches every single-underscore
#   name -- which is why F811 stays silent on a redefined `_helper` while reporting a redefined
#   `helper`. It is reproduced here only to CLASSIFY a binding for the redundancy test below;
#   nothing in the gate itself depends on it, so a future ruff default change cannot weaken the
#   check, only make one assertion's explanation stale.
_RUFF_DUMMY_NAME = re.compile(r"^(_+|(_+[a-zA-Z0-9_]*[a-zA-Z0-9]+?))$")

# WHY : Assumptions: `typing.overload` is the one module-level repetition of a name that Python
#   itself sanctions -- the stubs exist to be replaced by the implementation that follows them.
#   Both spellings are accepted because either import form is legitimate, and matching on the
#   unparsed decorator text covers `@overload` and `@typing.overload` without resolving imports.
_OVERLOAD_DECORATORS = frozenset({"overload", "typing.overload"})

# WHY : Assumptions: the two kinds are distinguished because F811's blindness differs between
#   them -- a definition is invisible only when privately named, an assignment is invisible
#   always -- and the redundancy test asserts both kinds are present in the trees.
_DEFINITION = "definition"
_ASSIGNMENT = "assignment"


class Binding(NamedTuple):
    """One module-level name binding found by the walk.

    Attributes
    ----------
    path : Path
        The file the binding was read from, so a failure reports a location.
    name : str
        The bound name.
    kind : str
        ``"definition"`` for a ``def``, ``async def`` or ``class``; ``"assignment"`` for a
        plain or annotated module-level assignment.
    lineno : int
        One-based line of the statement that binds the name.
    visible_to_f811 : bool
        Whether ruff's F811 can see a redefinition of this shape AT ALL. ``False`` marks a
        binding this gate is the only enforcement for. It is an upper bound rather than a
        prediction: F811 additionally goes silent when the earlier binding is read between the
        two declarations, so a ``True`` here does not promise ruff would report the duplicate.
    """

    path: Path
    name: str
    kind: str
    lineno: int
    visible_to_f811: bool


def _matches_ruff_dummy_name(name: str) -> bool:
    """Report whether ruff would treat this name as an intentionally unused ("dummy") name.

    Parameters
    ----------
    name : str
        The bound name.

    Returns
    -------
    bool
        ``True`` when the name matches ruff's effective ``dummy-variable-rgx``, which is what
        suppresses F811 for a privately named definition.
    """
    return _RUFF_DUMMY_NAME.match(name) is not None


def _is_overloaded(node: ast.FunctionDef | ast.AsyncFunctionDef | ast.ClassDef) -> bool:
    """Report whether a definition is a ``typing.overload`` stub.

    Parameters
    ----------
    node : ast.FunctionDef | ast.AsyncFunctionDef | ast.ClassDef
        A module-level definition node.

    Returns
    -------
    bool
        ``True`` when any decorator unparses to ``overload`` or ``typing.overload``.
    """
    return any(ast.unparse(decorator) in _OVERLOAD_DECORATORS for decorator in node.decorator_list)


def _assigned_names(target: ast.expr) -> list[str]:
    """List the names one assignment target binds, flattening tuple and list targets.

    Parameters
    ----------
    target : ast.expr
        A single element of ``ast.Assign.targets`` or an ``ast.AnnAssign.target``.

    Returns
    -------
    list[str]
        Every plain name bound by the target, in source order. Attribute and subscript targets
        bind no new module-level name and yield nothing.
    """
    # Assumptions: unpacking nests, so `a, (b, c) = ...` and `head, *tail = ...` both have to be
    #   walked rather than read one level deep. Missing a name here would be a false NEGATIVE --
    #   a duplicate this gate fails to report -- which is the failure mode worth spending the
    #   recursion on.
    if isinstance(target, ast.Name):
        return [target.id]
    if isinstance(target, ast.Starred):
        return _assigned_names(target.value)
    if isinstance(target, (ast.Tuple, ast.List)):
        return [name for element in target.elts for name in _assigned_names(element)]
    return []


def _module_bindings(source: Path) -> list[Binding]:
    """Collect every top-level name binding in one module.

    Parameters
    ----------
    source : Path
        The Python file to read.

    Returns
    -------
    list[Binding]
        One entry per module-level definition or assignment, in source order. ``@overload``
        stubs and their implementation are omitted, because repeating that name is sanctioned.

    Raises
    ------
    SyntaxError
        Propagated from :func:`ast.parse` when the file does not parse. That is a real defect
        and must not be reported as a duplicate-symbol result.
    """
    tree = ast.parse(source.read_text(encoding="utf-8"), filename=str(source))
    overloaded = {
        statement.name
        for statement in tree.body
        if isinstance(statement, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef))
        and _is_overloaded(statement)
    }

    found: list[Binding] = []
    for statement in tree.body:
        if isinstance(statement, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
            names = [statement.name]
            kind = _DEFINITION
        elif isinstance(statement, ast.AnnAssign):
            names = _assigned_names(statement.target)
            kind = _ASSIGNMENT
        elif isinstance(statement, ast.Assign):
            names = [name for target in statement.targets for name in _assigned_names(target)]
            kind = _ASSIGNMENT
        else:
            # Assumptions: every other statement kind is skipped rather than recursed into.
            #   `import` is excluded because F811 already reports a duplicate import, measured;
            #   `if`, `try` and `with` are excluded because a binding inside one is a
            #   conditional alternative, not a shadowing redeclaration.
            continue

        for name in names:
            if name in overloaded:
                continue
            found.append(
                Binding(
                    path=source,
                    name=name,
                    kind=kind,
                    lineno=statement.lineno,
                    # Assumptions: an assignment is invisible to F811 unconditionally, so its
                    #   visibility is not a function of the name at all. Measured: a duplicated
                    #   `PUBLIC_X: int` is silent even with the dummy-name pattern withdrawn.
                    visible_to_f811=(kind == _DEFINITION and not _matches_ruff_dummy_name(name)),
                )
            )
    return found


def _bindings(root: Path) -> list[Binding]:
    """Collect every top-level name binding under a root.

    Parameters
    ----------
    root : Path
        Directory to search recursively.

    Returns
    -------
    list[Binding]
        Every module-level binding in every Python file under the root, file order then source
        order.

    Raises
    ------
    AssertionError
        If the root is missing or holds no Python file. Both would make this gate pass by
        measuring nothing, so each is a failure rather than an empty result.
    """
    assert root.is_dir(), f"symbol-uniqueness root is missing: {root}"
    sources = sorted(path for path in root.rglob("*.py") if "__pycache__" not in path.parts)
    assert sources, f"symbol-uniqueness root holds no Python source: {root}"
    return [binding for source in sources for binding in _module_bindings(source)]


def _duplicates(bindings: list[Binding]) -> dict[tuple[Path, str], list[Binding]]:
    """Group the bindings that a single module declares more than once.

    Parameters
    ----------
    bindings : list[Binding]
        Bindings collected from one or more modules.

    Returns
    -------
    dict[tuple[Path, str], list[Binding]]
        Keyed by file and name, holding every occurrence, for the names bound at least twice
        within one file. Grouping by file is what keeps a name legitimately bound once in each
        of two modules out of the result.
    """
    grouped: dict[tuple[Path, str], list[Binding]] = defaultdict(list)
    for binding in bindings:
        grouped[(binding.path, binding.name)].append(binding)
    return {key: occurrences for key, occurrences in grouped.items() if len(occurrences) > 1}


def _format_duplicates(duplicates: dict[tuple[Path, str], list[Binding]]) -> str:
    """Render duplicate bindings as one reviewable line each.

    Parameters
    ----------
    duplicates : dict[tuple[Path, str], list[Binding]]
        The grouping returned by :func:`_duplicates`.

    Returns
    -------
    str
        A newline-joined report naming the file, the name, the kind, every line it is bound on,
        and whether ruff could have seen it. The last field tells a reader at a glance whether
        the duplicate escaped a lint run or was invisible to it.
    """
    lines = []
    for (path, name), occurrences in sorted(duplicates.items(), key=lambda item: str(item[0])):
        first = occurrences[0]
        linenos = ", ".join(str(occurrence.lineno) for occurrence in occurrences)
        lines.append(
            f"    {path}: {first.kind} {name} bound on lines {linenos}"
            f" (ruff F811 {'can' if first.visible_to_f811 else 'CANNOT'} see this shape)"
        )
    return "\n".join(lines)


@pytest.mark.parametrize("root", _ROOTS, ids=["package", "suite"])
def test_no_module_binds_one_top_level_name_twice(root: Path) -> None:
    """Assert every module in a tree binds each of its top-level names exactly once.

    Parameters
    ----------
    root : Path
        The package source tree or this suite's own tree, supplied by parametrisation.

    Returns
    -------
    None
        The test exists for its assertions.

    Raises
    ------
    AssertionError
        Naming every duplicated module-level binding found, one per line.
    """
    # Trade-offs: both trees are asserted by one parametrised test rather than two bespoke ones
    #   so neither can be dropped without the parametrisation visibly shrinking. This suite is
    #   in scope as well as the package: a copied block landing in a module that already
    #   declares the same test function name would leave pytest running only the later one, and
    #   a silently unrun test is the outcome this package's pytest configuration is written to
    #   prevent everywhere else.
    duplicates = _duplicates(_bindings(root))
    assert not duplicates, (
        f"{len(duplicates)} top-level name(s) under {root} are bound more than once in a single "
        "module; the earlier binding is silently discarded at import time:\n"
        f"{_format_duplicates(duplicates)}"
    )


def test_the_gate_covers_redefinitions_ruff_cannot_see() -> None:
    """Assert this gate is not a restatement of ruff's F811.

    Returns
    -------
    None
        The test exists for its assertions.

    Raises
    ------
    AssertionError
        If every binding in both trees is a shape F811 can see, which would mean this module
        had become redundant and its explanation of the ruff blind spots had become false.
    """
    # Refactoring Rationale: without this assertion the module could be deleted as "already
    #   covered by the F rules" and every test here would keep passing right up to the deletion.
    #   Stating the overlap as a measurement makes the redundancy question answerable from the
    #   suite rather than from a reading of ruff's rule table.
    bindings = [binding for root in _ROOTS for binding in _bindings(root)]
    invisible = [binding for binding in bindings if not binding.visible_to_f811]
    assert invisible, (
        "every top-level binding in both trees is a shape ruff's F811 can see, so this gate "
        "would add nothing; re-measure the blind spots described in this module's docstring "
        "before removing it"
    )
    # Assumptions: BOTH blind spots have to be represented, not just one. The defect this gate
    #   was written for was one of each -- a privately named definition and an annotated
    #   constant -- so a tree that had lost either kind would make this module's claim to cover
    #   two blind spots only half true.
    invisible_kinds = {binding.kind for binding in invisible}
    assert invisible_kinds == {_DEFINITION, _ASSIGNMENT}, (
        "the bindings ruff cannot see no longer include both a definition and an assignment, "
        f"only {sorted(invisible_kinds)}; this module's docstring describes two blind spots"
    )


def test_the_walk_finds_each_binding_shape_and_skips_the_sanctioned_ones(tmp_path: Path) -> None:
    """Assert the walk reports each duplicated shape and stays silent on the legitimate ones.

    Parameters
    ----------
    tmp_path : Path
        Pytest-supplied directory holding a synthetic module that exercises the matrix.

    Returns
    -------
    None
        The test exists for its assertions.

    Raises
    ------
    AssertionError
        If a duplicated shape goes unreported, if a sanctioned repetition is reported, or if a
        binding's F811 visibility disagrees with the behaviour measured against the pinned ruff.
    """
    # Refactoring Rationale: the first two duplicated shapes below are the defect this gate was
    #   written for, reproduced in miniature -- a privately named annotated constant and a
    #   privately named function, each declared twice. They are the regression anchor: if the
    #   walk stops seeing either, the gate has lost the case that motivated it.
    source = tmp_path / "shapes.py"
    source.write_text(
        '"""Synthetic module exercising every module-level binding shape."""\n'
        "\n"
        "from typing import Final, overload\n"
        "\n"
        "_DUPLICATE_CONSTANT: Final[int] = 1\n"
        "_DUPLICATE_CONSTANT: Final[int] = 1\n"
        "\n"
        "_UNIQUE_CONSTANT: Final[int] = 2\n"
        "\n"
        "PAIRED_LEFT, PAIRED_RIGHT = 1, 2\n"
        "PAIRED_LEFT = 3\n"
        "\n"
        "_STARRED_HEAD, *_STARRED_TAIL = (1, 2, 3)\n"
        "_STARRED_HEAD, *_STARRED_TAIL = (4, 5, 6)\n"
        "\n"
        "_CONDITIONAL = 1\n"
        "if _UNIQUE_CONSTANT:\n"
        "    _CONDITIONAL = 2\n"
        "\n"
        "try:\n"
        "    _ALTERNATIVE = 1\n"
        "except ValueError:\n"
        "    _ALTERNATIVE = 2\n"
        "\n"
        "\n"
        "def _duplicate_private_function() -> None:\n"
        '    """Doc."""\n'
        "\n"
        "\n"
        "def _duplicate_private_function() -> None:\n"
        '    """Doc."""\n'
        "\n"
        "\n"
        "class DuplicatePublicClass:\n"
        '    """Doc."""\n'
        "\n"
        "\n"
        "class DuplicatePublicClass:\n"
        '    """Doc."""\n'
        "\n"
        "\n"
        "@overload\n"
        "def widened(value: int) -> int: ...\n"
        "\n"
        "\n"
        "@overload\n"
        "def widened(value: str) -> str: ...\n"
        "\n"
        "\n"
        "def widened(value: int | str) -> int | str:\n"
        '    """Doc."""\n'
        "    return value\n",
        encoding="utf-8",
    )

    bindings = _bindings(tmp_path)
    duplicated = {name for _, name in _duplicates(bindings)}

    assert duplicated == {
        "_DUPLICATE_CONSTANT",
        "PAIRED_LEFT",
        "_STARRED_HEAD",
        "_STARRED_TAIL",
        "_duplicate_private_function",
        "DuplicatePublicClass",
    }, f"the walk no longer reports exactly the duplicated shapes; it reported {sorted(duplicated)}"

    # Assumptions: each name below is a SANCTIONED repetition or a single binding, and each is
    #   listed for a distinct reason: `widened` is repeated by `@overload`, `_CONDITIONAL` and
    #   `_ALTERNATIVE` are rebound inside a nested statement rather than at module level, and
    #   `PAIRED_RIGHT` proves tuple flattening does not over-report the half that is bound once.
    for sanctioned in (
        "widened",
        "_CONDITIONAL",
        "_ALTERNATIVE",
        "_UNIQUE_CONSTANT",
        "PAIRED_RIGHT",
    ):
        assert sanctioned not in duplicated, (
            f"{sanctioned!r} is a legitimate single or sanctioned binding and must not be "
            "reported as a duplicate"
        )

    visibility = {binding.name: binding.visible_to_f811 for binding in bindings}
    assert visibility["DuplicatePublicClass"] is True, (
        "a redefined PUBLIC class is a shape F811 reports, measured against the pinned ruff"
    )
    assert visibility["_duplicate_private_function"] is False, (
        "a redefined privately named function is silent under F811 because the name matches "
        "ruff's dummy-variable pattern, measured against the pinned ruff"
    )
    assert visibility["_DUPLICATE_CONSTANT"] is False, (
        "a re-declared module-level constant is outside F811's remit whatever its name, "
        "measured against the pinned ruff"
    )
