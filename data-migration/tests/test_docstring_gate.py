"""Require a docstring on every declaration, including the ones ruff cannot see.

Purpose
-------
Close the one measurable gap between what Rule 1 "Explainability" requires of this
package and what ``ruff``'s pydocstyle (D) family mechanically enforces. Rule 1 requires a
docstring on every module, class, function and constructor with no carve-out for
visibility; ``data-migration/pyproject.toml`` selects the D family, and this module is the
second half of that gate rather than a duplicate of it. The two halves are needed because
D100, D101, D102, D103, D104 and D106 are *public*-declaration checks, so a declaration is
invisible to them when its own name carries a SINGLE leading underscore, when any
enclosing class is privately named, or when it is declared inside a function body at any
depth and any visibility. A module follows the same single-underscore convention, which
makes ``__init__.py`` public and ``_helper.py`` private -- the opposite of how the two read.
Every clause above was measured against the pinned ruff, not inferred from its rule table.
This module walks the same trees with ``ast`` and asserts the presence Rule 1 asks for on
all of them.

Alternatives Considered:
    Four alternatives were evaluated. (1) A ``per-file-ignores`` entry or a different
    ``convention`` in ``pyproject.toml`` cannot help, because the behaviour is upstream
    visibility semantics rather than a suppression this repository could withdraw --
    there is no setting that turns D103 into a private-function check. (2) Invoking
    ``pydocstyle`` directly was rejected because it is the same implementation of the same
    semantics, so it inherits the same blind spot while adding a pinned dependency. (3) A
    standalone lint script wired into ``.github/workflows/services-ci.yml`` as a new step
    was rejected because the published gate already runs this suite fail-closed with no
    tolerance, so a test needs no workflow change and cannot be forgotten in one. (4)
    Renaming the package's private helpers so that ruff would see them was rejected
    outright: it enlarges a published API surface to satisfy a linter, which is the wrong
    artifact to change.

Assumptions:
    The subject is the source under version control, so both trees are located relative to
    this file rather than through the imported package. That matters here specifically:
    this package deliberately runs its suite against the INSTALLED distribution and sets no
    ``pythonpath``, and ``tests/`` is not packaged at all, so an installed
    ``carddemo_migration.__file__`` would reach neither this suite nor the source tree a
    reviewer edits. Reading from disk is also what lets the gate cover itself.

Trade-offs:
    This gate checks PRESENCE only, and that is the whole of its remit. Ruff's content
    rules -- D400, D403, D205 and the rest -- were measured to apply to every docstring it
    finds regardless of visibility or nesting, so shape is already covered everywhere and
    duplicating it here would create two authorities for one question. Presence was the
    only half that went unchecked. The "why" requirement in Rule 1 remains a review
    judgement no walker can make: a docstring that says nothing satisfies this gate and
    still fails review.

Refactoring Rationale:
    The walker is self-tested by ``test_the_walker_reaches_every_nesting_and_visibility``
    rather than trusted, because this gate's only failure mode that matters is the quiet
    one. A walker that stopped descending into function bodies would report nothing
    missing and pass, silently reducing this module's coverage to exactly what ruff already
    does while continuing to look like a gate. Asserting that the walk finds a known matrix
    of shapes turns that regression into a failure.
"""

from __future__ import annotations

import ast
from pathlib import Path
from typing import NamedTuple

import pytest

# Assumptions: both roots resolve from this file, so the gate behaves identically whether
#   it is invoked from the repository root, from `data-migration/`, or by an editor.
_DATA_MIGRATION_ROOT = Path(__file__).resolve().parents[1]
_PACKAGE_ROOT = _DATA_MIGRATION_ROOT / "src" / "carddemo_migration"
_SUITE_ROOT = _DATA_MIGRATION_ROOT / "tests"

# Assumptions: only these two trees are in scope. The repository's own `tests/` tree is the
#   COBOL parity oracle and is reference-only, so it is deliberately not reachable from
#   here; naming the two roots explicitly rather than globbing upward is what keeps it out.
_ROOTS = (_PACKAGE_ROOT, _SUITE_ROOT)


class Declaration(NamedTuple):
    """One module, class or function found by the walk, with its ruff visibility.

    Attributes
    ----------
    path : Path
        The file the declaration was read from, used to report a failure by location.
    kind : str
        One of ``"module"``, ``"class"`` or ``"function"``.
    qualified_name : str
        Dotted path from the module to the declaration, so a nested closure is
        distinguishable from a same-named sibling elsewhere in the file.
    lineno : int
        One-based line of the ``def``, ``class`` or -- for a module -- of its first line.
    documented : bool
        Whether a non-blank docstring is present.
    visible_to_ruff : bool
        Whether ruff's D presence checks would require the docstring. ``False`` marks a
        declaration this gate is the only enforcement for.
    documents_return : bool
        Whether a function's docstring addresses its return -- a ``Returns`` or a
        ``Yields`` section. Always ``True`` for a module or a class, which return nothing,
        so a caller filters on ``kind`` before reading it.
    """

    path: Path
    kind: str
    qualified_name: str
    lineno: int
    documented: bool
    visible_to_ruff: bool
    documents_return: bool


def _is_privately_named(name: str) -> bool:
    """Report whether pydocstyle would treat this name as private.

    Parameters
    ----------
    name : str
        The declared name of a class or function.

    Returns
    -------
    bool
        ``True`` for a single leading underscore. A dunder is excluded because
        pydocstyle checks those under D105 and D107 rather than skipping them.
    """
    return name.startswith("_") and not (name.startswith("__") and name.endswith("__"))


def _has_docstring(node: ast.AST) -> bool:
    """Report whether a node carries a docstring with at least one non-blank character.

    Parameters
    ----------
    node : ast.AST
        A module, class or function node.

    Returns
    -------
    bool
        ``True`` when a docstring is present and not merely whitespace.

    Raises
    ------
    TypeError
        Propagated from :func:`ast.get_docstring` if handed a node kind that cannot
        carry a docstring, which would mean the walk itself had gone wrong.
    """
    # Assumptions: an all-whitespace docstring is treated as absent. Ruff's D419 catches
    #   the empty case for declarations it can see; for the ones it cannot, accepting `""`
    #   here would let a placeholder satisfy the gate, which is the outcome it exists to
    #   prevent.
    text = ast.get_docstring(node)
    return bool(text and text.strip())


def _documents_return(node: ast.AST) -> bool:
    """Report whether a docstring addresses what the declaration returns.

    Parameters
    ----------
    node : ast.AST
        A module, class or function node.

    Returns
    -------
    bool
        ``True`` when the docstring names a ``Returns`` or a ``Yields`` section, or when
        there is no docstring at all. The no-docstring case answers ``True`` so that one
        missing docstring is reported once, by the presence assertion that owns it, rather
        than a second time here.

    Raises
    ------
    TypeError
        Propagated from :func:`ast.get_docstring` if handed a node kind that cannot carry
        a docstring, which would mean the walk itself had gone wrong.
    """
    text = ast.get_docstring(node)
    if not (text and text.strip()):
        return True
    return "Returns" in text or "Yields" in text


def _walk(
    node: ast.AST,
    path: Path,
    prefix: str,
    *,
    inside_function: bool,
    under_private: bool,
) -> list[Declaration]:
    """Collect every class and function beneath a node, descending without limit.

    Parameters
    ----------
    node : ast.AST
        The node whose children are examined; a module on the outermost call.
    path : Path
        The file being read, carried through so a failure names a location.
    prefix : str
        Dotted qualified name of ``node``, prepended to each child's name.
    inside_function : bool
        Whether ``node`` is inside a function body. Once true it stays true, because
        pydocstyle requires no docstring anywhere below a function.
    under_private : bool
        Whether any enclosing class is privately named. Once true it stays true, because
        pydocstyle treats a public member of a private class as private too.

    Returns
    -------
    list[Declaration]
        Every class and function found, in source order, depth-first. Each function entry
        also carries whether its docstring addresses its return.
    """
    found: list[Declaration] = []
    for child in ast.iter_child_nodes(node):
        if isinstance(child, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
            is_class = isinstance(child, ast.ClassDef)
            private_here = under_private or _is_privately_named(child.name)
            found.append(
                Declaration(
                    path=path,
                    kind="class" if is_class else "function",
                    qualified_name=f"{prefix}.{child.name}",
                    lineno=child.lineno,
                    documented=_has_docstring(child),
                    visible_to_ruff=not (inside_function or private_here),
                    documents_return=is_class or _documents_return(child),
                )
            )
            found.extend(
                _walk(
                    child,
                    path,
                    f"{prefix}.{child.name}",
                    inside_function=inside_function or not is_class,
                    under_private=private_here,
                )
            )
        else:
            # Assumptions: recursion continues through non-declaration nodes rather than
            #   stopping, because a `def` can be nested inside an `if`, a `try`, a `with`
            #   or a loop at module level. Descending only into declarations would miss
            #   those and quietly shrink the gate.
            found.extend(
                _walk(
                    child,
                    path,
                    prefix,
                    inside_function=inside_function,
                    under_private=under_private,
                )
            )
    return found


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
        If the root does not exist or holds no Python file. Both cases would make the
        gate pass by measuring nothing, so they are failures rather than empty results.
    """
    assert root.is_dir(), f"documentation gate root is missing: {root}"
    sources = sorted(p for p in root.rglob("*.py") if "__pycache__" not in p.parts)
    assert sources, f"documentation gate root holds no Python source: {root}"
    return sources


def _declarations(root: Path) -> list[Declaration]:
    """Collect every module, class and function declaration under a root.

    Parameters
    ----------
    root : Path
        Directory to search recursively.

    Returns
    -------
    list[Declaration]
        One entry per module plus one per class and function at any depth.

    Raises
    ------
    SyntaxError
        Propagated from :func:`ast.parse` when a source file does not parse, which is a
        real defect and must not be reported as a documentation result.
    """
    collected: list[Declaration] = []
    for source in _python_sources(root):
        tree = ast.parse(source.read_text(encoding="utf-8"), filename=str(source))
        collected.append(
            Declaration(
                path=source,
                kind="module",
                qualified_name=source.stem,
                lineno=1,
                documented=_has_docstring(tree),
                # Assumptions: the module rule follows the SAME single-underscore
                #   convention as classes and functions, so `__init__.py` is public and
                #   raises D104 while `_helper.py` is private and raises nothing.
                #   Measured against the pinned ruff, because the two-underscore case is
                #   the one an author is most likely to guess wrongly: it looks more
                #   private than `_helper.py`, and is in fact the only module name
                #   pydocstyle has a dedicated public-package rule for.
                visible_to_ruff=not _is_privately_named(source.stem),
                # Assumptions: a module returns nothing, so the return element does not
                #   apply to it and the field is set true rather than left to be filtered
                #   by every reader. The return assertion filters on `kind` regardless, so
                #   the two agree.
                documents_return=True,
            )
        )
        collected.extend(
            _walk(
                tree,
                source,
                source.stem,
                inside_function=False,
                under_private=False,
            )
        )
    return collected


def _format_undocumented(declarations: list[Declaration]) -> str:
    """Render undocumented declarations as one reviewable line each.

    Parameters
    ----------
    declarations : list[Declaration]
        The declarations that carry no docstring.

    Returns
    -------
    str
        A newline-joined report giving path, line, kind and qualified name, plus whether
        ruff would also have caught it. The last field tells a reader at a glance whether
        the failure is a plain lint miss or one only this gate reports.
    """
    return "\n".join(
        f"    {d.path}:{d.lineno} {d.kind} {d.qualified_name}"
        f" (ruff would {'also' if d.visible_to_ruff else 'NOT'} report it)"
        for d in declarations
    )


@pytest.mark.parametrize(
    "root",
    _ROOTS,
    ids=["package", "suite"],
)
def test_every_declaration_carries_a_docstring(root: Path) -> None:
    """Assert Rule 1 docstring presence on every declaration in a tree.

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
        Naming every undocumented module, class or function found, one per line.
    """
    # Trade-offs: both trees are asserted by one parametrised test rather than two bespoke
    #   ones so that neither can be dropped without the parametrisation visibly shrinking.
    #   The cost is a less specific test name; the id makes the failing tree explicit.
    undocumented = [d for d in _declarations(root) if not d.documented]
    assert not undocumented, (
        f"{len(undocumented)} declaration(s) under {root} carry no docstring, which "
        "Rule 1 requires of every module, class, function and constructor:\n"
        f"{_format_undocumented(undocumented)}"
    )


def _functions_missing_a_return_contract(root: Path) -> list[Declaration]:
    """Collect functions whose docstring documents neither a return nor a yield.

    Parameters
    ----------
    root : Path
        Directory to search recursively.

    Returns
    -------
    list[Declaration]
        One entry per function or method whose docstring carries no ``Returns`` and no
        ``Yields`` section. A declaration with no docstring at all is excluded, because
        :func:`test_every_declaration_carries_a_docstring` already owns that failure and
        reporting it twice would make one defect look like two.

    Raises
    ------
    SyntaxError
        Propagated from :func:`ast.parse` when a source file does not parse.
    """
    # Assumptions: the section name is matched as a substring rather than as a parsed
    #   numpydoc heading, and that is the honest limit of this check. It decides SHAPE --
    #   that the author addressed the return at all -- and decides nothing about whether
    #   the description is true, which stays a review obligation exactly as the module
    #   docstring above records for the "why" requirement.
    # Assumptions: `Yields` counts, because a generator's return contract IS its yield
    #   contract; numpydoc names the section `Yields` for that case, and
    #   `carddemo_migration.loaders.aurora._cursor_of` is one. Requiring `Returns` alone
    #   would have reported a correctly documented generator and taught a reader that this
    #   gate's findings are noise.
    return [
        declaration
        for declaration in _declarations(root)
        if declaration.kind == "function"
        and declaration.documented
        and not declaration.documents_return
    ]


def test_every_package_function_documents_its_return() -> None:
    """Assert Rule 1's return-value element on every function in the package source.

    Returns
    -------
    None
        The test exists for its assertions.

    Raises
    ------
    AssertionError
        Naming every package function whose docstring addresses neither a return nor a
        yield, one per line.
    """
    # Refactoring Rationale: this assertion was added because presence alone let a real
    #   gap through. Three docstrings in `carddemo_migration.loaders.aurora` --
    #   `_Connection.commit`, `_Connection.rollback` and `TableTarget.__post_init__` --
    #   documented their purpose and, in one case, their exception, and said nothing at all
    #   about what they return, while their own sibling `_Connection.cursor` documented its
    #   return. Ruff's D family does not look for the section and the presence walk above
    #   was satisfied by any non-blank docstring, so nothing in the build could see the
    #   omission and it reached review instead. Rule 1 names "Return values: Type and
    #   description of what is returned" as its third docstring element, so the element was
    #   unenforced for this language until this test existed.
    # Trade-offs: the scope is the PACKAGE tree only, and the suite tree is deliberately
    #   excluded rather than silently included. Measured at the time this test was added:
    #   487 of 487 functions under `src/carddemo_migration` document a return or a yield,
    #   and 355 of 609 under `tests/` do not -- pytest cases and fixtures that return
    #   nothing and say so in prose rather than in a numpydoc section. Extending the
    #   assertion there would demand a 355-function mechanical sweep of test docstrings as
    #   the price of enforcing the element on the code that ships, so the element is
    #   enforced where the shipping code is and remains a review obligation for the suite.
    #   That limit is stated here, in the gate, rather than left for a reader to infer from
    #   a passing run -- a gate whose reach is wider in a reader's mind than in its code is
    #   the failure this whole module exists to prevent.
    undocumented = _functions_missing_a_return_contract(_PACKAGE_ROOT)
    assert not undocumented, (
        f"{len(undocumented)} function(s) under {_PACKAGE_ROOT} document neither a "
        "'Returns' nor a 'Yields' section, which Rule 1 requires as its third docstring "
        f"element:\n{_format_undocumented(undocumented)}"
    )


def test_the_return_contract_check_reaches_a_known_omission() -> None:
    """Assert the return-contract walk reports a docstring that omits its return.

    Returns
    -------
    None
        The test exists for its assertions.

    Raises
    ------
    AssertionError
        If the walk fails to report a function whose docstring has no return section, or
        reports one whose docstring has either section.
    """
    # Assumptions: the probe is parsed in memory rather than written to disk, because this
    #   check reads an AST and needs no file. The three shapes cover the whole decision:
    #   a `Returns` section passes, a `Yields` section passes, and a docstring carrying
    #   only Purpose and Raises is reported. Without this test a walk narrowed to report
    #   nothing would pass `test_every_package_function_documents_its_return` while
    #   enforcing nothing, which is the same quiet regression the presence walk is
    #   self-tested against above.
    probe = ast.parse(
        '"""Module probe."""\n'
        "\n"
        "def returns_documented():\n"
        '    """Do a thing.\n'
        "\n"
        "    Returns\n"
        "    -------\n"
        "    None\n"
        "        Nothing.\n"
        '    """\n'
        "\n"
        "\n"
        "def yields_documented():\n"
        '    """Do a thing.\n'
        "\n"
        "    Yields\n"
        "    ------\n"
        "    int\n"
        "        A number.\n"
        '    """\n'
        "    yield 1\n"
        "\n"
        "\n"
        "def return_undocumented():\n"
        '    """Do a thing.\n'
        "\n"
        "    Raises\n"
        "    ------\n"
        "    ValueError\n"
        "        Always.\n"
        '    """\n'
        "    raise ValueError\n"
    )
    walked = _walk(
        probe,
        Path("probe.py"),
        "probe",
        inside_function=False,
        under_private=False,
    )
    reported = {
        declaration.qualified_name
        for declaration in walked
        if declaration.kind == "function" and not declaration.documents_return
    }

    assert reported == {"probe.return_undocumented"}, (
        "the return-contract walk must report exactly the function whose docstring omits "
        f"its return section, and it reported {sorted(reported)}"
    )


def test_the_gate_covers_declarations_ruff_cannot_see() -> None:
    """Assert this gate is not a duplicate of the ruff D family.

    Returns
    -------
    None
        The test exists for its assertions.

    Raises
    ------
    AssertionError
        If no declaration in either tree is invisible to ruff's presence checks, which
        would mean this module had become redundant and its claim in
        ``pyproject.toml`` had become false.
    """
    # Refactoring Rationale: without this assertion the module could be deleted as
    #   "already covered by ruff" and every test here would keep passing right up to the
    #   deletion. Stating the overlap as a measurement makes the redundancy question
    #   answerable from the suite rather than from a reading of ruff's rule table.
    declarations = [d for root in _ROOTS for d in _declarations(root)]
    invisible = [d for d in declarations if not d.visible_to_ruff]
    assert invisible, (
        "no declaration is invisible to ruff's D presence checks, so this gate would add "
        "nothing; verify the claim in data-migration/pyproject.toml before removing it"
    )
    assert all(d.documented for d in invisible), (
        f"{sum(1 for d in invisible if not d.documented)} declaration(s) that ruff cannot "
        "see carry no docstring:\n"
        f"{_format_undocumented([d for d in invisible if not d.documented])}"
    )


def test_the_walker_reaches_every_nesting_and_visibility(tmp_path: Path) -> None:
    """Assert the walk finds each declaration shape and classifies its ruff visibility.

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
        If a shape is missed, or if its visibility classification disagrees with the
        behaviour measured against the pinned ruff.
    """
    # Assumptions: the expected visibility of each shape below was MEASURED against the
    #   pinned ruff by checking an equivalent module with `--select D`, not inferred from
    #   documentation. The three that ruff reports are the public method, the public class
    #   nested in a class, and that nested class's method; every other shape here is
    #   silent, which is precisely the set this gate exists to carry.
    # Assumptions: the three module names below are part of the matrix, not scaffolding.
    #   `__init__.py` is PUBLIC to pydocstyle and raises D104, while `_helper.py` is
    #   private and raises nothing -- the two-underscore case reads as more private than
    #   the one-underscore case and behaves as the opposite, so it is asserted rather than
    #   reasoned about.
    (tmp_path / "__init__.py").write_text('"""Package docstring."""\n', encoding="utf-8")
    (tmp_path / "_helper.py").write_text('"""Private module docstring."""\n', encoding="utf-8")

    source = tmp_path / "shapes.py"
    source.write_text(
        '"""Synthetic module exercising every declaration shape."""\n'
        "\n"
        "\n"
        "def _private_function() -> None:\n"
        '    """Doc."""\n'
        "\n"
        "\n"
        "class _PrivateClass:\n"
        '    """Doc."""\n'
        "\n"
        "    def public_method_of_private_class(self) -> None:\n"
        '        """Doc."""\n'
        "\n"
        "\n"
        "class PublicClass:\n"
        '    """Doc."""\n'
        "\n"
        "    def __init__(self) -> None:\n"
        '        """Doc."""\n'
        "\n"
        "    def _private_method(self) -> None:\n"
        '        """Doc."""\n'
        "\n"
        "    def public_method(self) -> None:\n"
        '        """Doc."""\n'
        "\n"
        "    class NestedPublicClass:\n"
        '        """Doc."""\n'
        "\n"
        "        def public_method(self) -> None:\n"
        '            """Doc."""\n'
        "\n"
        "\n"
        "def public_outer() -> None:\n"
        '    """Doc."""\n'
        "\n"
        "    def nested_public_function() -> None:\n"
        '        """Doc."""\n'
        "\n"
        "    class NestedInFunction:\n"
        '        """Doc."""\n'
        "\n"
        "        def public_method(self) -> None:\n"
        '            """Doc."""\n'
        "\n"
        "\n"
        "if True:\n"
        "\n"
        "    def guarded_function() -> None:\n"
        '        """Doc."""\n',
        encoding="utf-8",
    )

    visibility = {d.qualified_name: d.visible_to_ruff for d in _declarations(tmp_path)}

    expected = {
        "__init__": True,
        "_helper": False,
        "shapes": True,
        "shapes._private_function": False,
        "shapes._PrivateClass": False,
        "shapes._PrivateClass.public_method_of_private_class": False,
        "shapes.PublicClass": True,
        "shapes.PublicClass.__init__": True,
        "shapes.PublicClass._private_method": False,
        "shapes.PublicClass.public_method": True,
        "shapes.PublicClass.NestedPublicClass": True,
        "shapes.PublicClass.NestedPublicClass.public_method": True,
        "shapes.public_outer": True,
        "shapes.public_outer.nested_public_function": False,
        "shapes.public_outer.NestedInFunction": False,
        "shapes.public_outer.NestedInFunction.public_method": False,
        # Assumptions: a `def` under a module-level `if` is an ordinary public declaration.
        #   It is included because the walk has to recurse through statement nodes to find
        #   it, so its absence would prove the recursion had been narrowed to declarations.
        "shapes.guarded_function": True,
    }

    assert visibility == expected, (
        "the declaration walk no longer finds every shape, or classifies one differently "
        "from the measured ruff behaviour; missing="
        f"{sorted(set(expected) - set(visibility))} unexpected="
        f"{sorted(set(visibility) - set(expected))}"
    )
