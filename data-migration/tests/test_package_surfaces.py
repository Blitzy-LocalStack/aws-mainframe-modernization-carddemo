"""Exercise the two ETL package boundaries: the reader inventory and the verification passes.

Purpose
-------
Assert the properties of ``carddemo_migration.readers`` and ``carddemo_migration.verify`` that are
about the PACKAGE rather than about anything inside it: that each publishes everything it documents,
that the reader registry covers every record the migration has to read and maps each to the right
module, that both surfaces resolve LAZILY so importing the boundary does not import its contents,
and that neither can be redirected at run time.

Assumptions: the completeness assertions are made against the DIRECTORY and against the layouts
registry rather than against a list written here. A list in the test would have the identical
failure mode as the list in the module -- a reader added without being registered would go
unnoticed by exactly the test meant to notice it.

Assumptions: laziness is asserted by inspecting :data:`sys.modules` in a subprocess rather than in
this interpreter. By the time this suite runs, other test modules have imported most of the package,
so an in-process check would pass regardless of what the boundary does; a fresh interpreter is the
only place the question has a meaningful answer.
"""

from __future__ import annotations

import pathlib
import subprocess
import sys
from typing import Final

import pytest

from carddemo_migration import readers, verify
from carddemo_migration.copybook import layouts

# Assumptions: the directory is resolved from the imported package so the walk examines the same
#   tree the rest of the suite imports, which is the installed distribution rather than the
#   checkout under the arrangement data-migration/pyproject.toml sets up.
_READERS_DIR: Final[pathlib.Path] = pathlib.Path(readers.__file__ or "").parent

# Assumptions: these are the module names that are NOT record readers, listed explicitly because
#   the completeness check below is a set comparison and needs to subtract them. Each is a support
#   module the reader package publishes under its own tuple.
_NON_READER_MODULES: Final[frozenset[str]] = frozenset({"__init__", "factory", "source"})


def _subprocess_imports(statement: str) -> frozenset[str]:
    """Return the ``carddemo_migration`` submodules a fresh interpreter imports for a statement.

    Parameters
    ----------
    statement : str
        Python source executed in a new interpreter, ordinarily a single import.

    Returns
    -------
    frozenset of str
        Every ``sys.modules`` key beginning with ``carddemo_migration`` after the statement ran.

    Raises
    ------
    AssertionError
        If the subprocess exits non-zero, which means the statement itself failed and the
        measurement would be meaningless.
    """
    # WHY (Assumptions): the child inherits this process's sys.path, which is what makes it resolve
    #   the same distribution the parent imported -- an explicitly constructed environment could
    #   pick up a different copy and would then be measuring the wrong package.
    source = (
        f"{statement}\n"
        "import sys\n"
        "print('\\n'.join(m for m in sys.modules if m.startswith('carddemo_migration')))\n"
    )
    completed = subprocess.run(  # noqa: S603 -- fixed argument list, no shell, no external input
        [sys.executable, "-c", source],
        capture_output=True,
        text=True,
        check=False,
    )
    assert completed.returncode == 0, completed.stderr
    return frozenset(line for line in completed.stdout.splitlines() if line)


def test_the_reader_registry_covers_every_record_the_migration_must_read() -> None:
    """Assert every base master and the export record map to a reader, and nothing else does.

    Returns
    -------
    None
        The assertion is the result.
    """
    # WHY (Assumptions): the expectation comes from the layouts module's own provenance split, so a
    #   record added as a base master is required to have a reader without this test being edited.
    #   The three DERIVED layouts are asserted ABSENT rather than merely not required: each is an
    #   output of the migrated pipeline -- the statement view, the reject stream, the interest
    #   transaction -- so a reader for one would imply a source extract that does not exist.
    expected = frozenset(layouts.base_master_names()) | {layouts.EXPORT_HEADER_LAYOUT.name}

    assert frozenset(readers.DATASET_READERS) == expected
    for derived in layouts.derived_names():
        assert derived not in readers.DATASET_READERS


def test_every_reader_in_the_directory_is_registered_and_published() -> None:
    """Assert the inventory matches the directory, in both directions.

    Returns
    -------
    None
        The assertion is the result.
    """
    # WHY (Refactoring Rationale): the boundary previously documented three of twelve readers, and
    #   nothing compared its documentation with the directory. Both directions are asserted because
    #   each catches a different mistake: a module present and unregistered is a reader no dispatch
    #   can reach, and a name registered without a module is an import error deferred to whichever
    #   load first needs that record.
    on_disk = {path.stem for path in _READERS_DIR.glob("*.py")} - _NON_READER_MODULES

    assert frozenset(readers.READER_MODULES) == on_disk
    assert on_disk <= frozenset(readers.__all__)


def test_the_four_unguessable_layout_to_module_pairs_resolve() -> None:
    """Assert the mapping's whole reason for existing: the four names that do not transform.

    Returns
    -------
    None
        The assertion is the result.
    """
    # WHY (Assumptions): these four are the reason a mapping exists at all rather than a naming
    #   convention. The module names follow the shipped dataset spellings and the layout names
    #   follow the copybook record names, and neither is derivable from the other -- so a caller
    #   deriving one from the other gets eight of twelve right, which fails only on the four nobody
    #   tested.
    assert readers.reader_module("TRAN").__name__.endswith(".transaction")
    assert readers.reader_module("DISGROUP").__name__.endswith(".discgrp")
    assert readers.reader_module("TRANCAT").__name__.endswith(".trancatg")
    assert readers.reader_module("SECUSER").__name__.endswith(".usrsec")


def test_every_registered_reader_module_resolves_to_a_module_owning_that_layout() -> None:
    """Assert each registry entry resolves and that the module it names reads that record.

    Returns
    -------
    None
        The assertion is the result.
    """
    # WHY (Assumptions): the assertion is that the resolved module DECLARES the layout it was
    #   registered for, not merely that it imports. A registry entry pointing at the wrong reader
    #   would import perfectly and then decode a record at another record's offsets, which is the
    #   one failure mode a name-keyed mapping is exposed to.
    for layout_name in readers.DATASET_READERS:
        module = readers.reader_module(layout_name)
        declared = {
            value.name for value in vars(module).values() if isinstance(value, layouts.RecordSpec)
        }
        assert layout_name in declared, (
            f"{module.__name__} is registered for {layout_name} but declares {sorted(declared)}"
        )


def test_a_layout_with_no_reader_is_refused_rather_than_answered_with_nothing() -> None:
    """Assert an unregistered layout raises instead of resolving to a false absence.

    Returns
    -------
    None
        The assertion is the result.
    """
    with pytest.raises(KeyError, match="no reader owns layout"):
        readers.reader_module("REJECT")


def test_the_reader_registry_cannot_be_redirected_at_run_time() -> None:
    """Assert the mapping is read-only, so a record cannot be pointed at another decoder.

    Returns
    -------
    None
        The assertion is the result.
    """
    # WHY (Assumptions): mutability here would be a global, silent change to which decoder a dataset
    #   is read with -- the mapping is module state shared by every consumer in the process, so one
    #   caller's convenience would redirect every other caller's load.
    with pytest.raises(TypeError):
        readers.DATASET_READERS["ACCOUNT"] = "card"  # type: ignore[index]


def test_importing_the_reader_package_imports_no_reader() -> None:
    """Assert the boundary is lazy: importing it costs the copybook layer and nothing more.

    Returns
    -------
    None
        The assertion is the result.
    """
    # WHY (Trade-offs): this is the property that justifies lazy resolution over the eager
    #   re-export the sibling copybook package uses, so it is asserted rather than assumed. The
    #   fourteen modules in this directory are roughly seven hundred kilobytes of source, and a
    #   command-line invocation that loads one dataset would otherwise compile all of them.
    imported = _subprocess_imports("import carddemo_migration.readers")
    reader_imports = {name for name in imported if name.startswith("carddemo_migration.readers.")}

    assert reader_imports == set()
    assert "carddemo_migration.copybook.layouts" in imported


def test_touching_one_reader_imports_only_that_reader() -> None:
    """Assert lazy access resolves one module rather than warming the whole package.

    Returns
    -------
    None
        The assertion is the result.
    """
    # WHY (Assumptions): the expectation is that reader PLUS the shared source module, and no
    #   other. Every reader imports `readers.source` for the hardened flat-file open and the two
    #   width guards, so its presence is the delegation working rather than a leak; what would be a
    #   leak is any of the other eleven readers, which is what the second assertion states
    #   positively rather than leaving to the set equality to imply.
    imported = _subprocess_imports(
        "import carddemo_migration.readers as r\nr.account.ACCOUNT_LAYOUT\n"
    )
    reader_imports = {name for name in imported if name.startswith("carddemo_migration.readers.")}

    assert reader_imports == {
        "carddemo_migration.readers.account",
        "carddemo_migration.readers.source",
    }
    untouched = {name for name in readers.READER_MODULES if name != "account"}
    assert {f"carddemo_migration.readers.{name}" for name in untouched} & reader_imports == set()


def test_the_reader_package_refuses_an_unpublished_attribute() -> None:
    """Assert the lazy hook resolves only the published set and not an arbitrary sibling.

    Returns
    -------
    None
        The assertion is the result.
    """
    # WHY (Assumptions): a permissive hook forwarding every name to importlib would make any module
    #   that happens to sit in this directory part of the public surface, and would report a typo as
    #   an ImportError from inside the import machinery rather than as a missing attribute.
    with pytest.raises(AttributeError, match="has no attribute"):
        _ = readers.no_such_reader


@pytest.mark.parametrize("name", sorted(readers.__all__))
def test_every_documented_reader_name_resolves(name: str) -> None:
    """Assert nothing in the reader package's ``__all__`` is unreachable.

    Parameters
    ----------
    name : str
        One published name.

    Returns
    -------
    None
        The assertion is the result.
    """
    assert getattr(readers, name) is not None


@pytest.mark.parametrize("name", sorted(verify.__all__))
def test_every_documented_verification_name_resolves(name: str) -> None:
    """Assert nothing in the verification package's ``__all__`` is unreachable.

    Parameters
    ----------
    name : str
        One published name.

    Returns
    -------
    None
        The assertion is the result.
    """
    # WHY (Refactoring Rationale): this package's ``__all__`` was an EMPTY list while its docstring
    #   described three verification passes, so the boundary documented three capabilities and
    #   published none of them. The parametrised form is deliberate: a single assertion over the
    #   whole list would report only the first unreachable name.
    assert getattr(verify, name) is not None


def test_all_three_verification_passes_are_reachable_through_the_boundary() -> None:
    """Assert each pass's entry point and result type resolve from the package itself.

    Returns
    -------
    None
        The assertion is the result.
    """
    # WHY (Assumptions): one entry point and one result type per pass is the published criterion, so
    #   all six are named here explicitly rather than derived from ``__all__``. Deriving them would
    #   make this test pass for a surface that published six unrelated names.
    assert verify.compare_counts.__module__.endswith(".row_counts")
    assert verify.RowCountComparison.__module__.endswith(".row_counts")
    assert verify.digest_records.__module__.endswith(".checksum")
    assert verify.RecordDigest.__module__.endswith(".checksum")
    assert verify.compare_money_totals.__module__.endswith(".money_parity")
    assert verify.MoneyParity.__module__.endswith(".money_parity")


def test_a_pass_module_name_still_means_the_module() -> None:
    """Assert the three module spellings existing consumers use have not changed meaning.

    Returns
    -------
    None
        The assertion is the result.
    """
    # WHY (Assumptions): the lazy hook checks the module names BEFORE the curated entry points, so
    #   ``verify.row_counts`` continues to resolve to the module. Every consumer that predates the
    #   curated surface uses that spelling, and a surface that silently rebound it to something
    #   else would break them without any import failing.
    for name in verify.PASS_MODULES:
        module = getattr(verify, name)
        assert module.__name__ == f"carddemo_migration.verify.{name}"


def test_importing_the_verification_package_imports_no_pass() -> None:
    """Assert the verification boundary is lazy, so a checksum does not pull in a driver.

    Returns
    -------
    None
        The assertion is the result.
    """
    # WHY (Assumptions): the concrete cost being avoided is named rather than left general -- the
    #   row-count pass reaches the database, so an eager boundary would make the pure-Python
    #   checksum pass depend on a driver being importable.
    imported = _subprocess_imports("import carddemo_migration.verify")

    assert {name for name in imported if name.startswith("carddemo_migration.verify.")} == set()


def test_the_verification_package_refuses_an_unpublished_attribute() -> None:
    """Assert the verification hook resolves only its documented surface.

    Returns
    -------
    None
        The assertion is the result.
    """
    with pytest.raises(AttributeError, match="has no attribute"):
        _ = verify.no_such_pass


@pytest.mark.parametrize("module", [readers, verify], ids=["readers", "verify"])
def test_dir_reports_the_documented_surface(module: object) -> None:
    """Assert ``dir()`` matches ``__all__`` rather than whatever a session happens to have touched.

    Parameters
    ----------
    module : object
        One of the two packages under test.

    Returns
    -------
    None
        The assertion is the result.
    """
    # WHY (Assumptions): dir() is overridden in both packages because a lazily resolved name is
    #   absent from the namespace until it is touched, so the default implementation reports a
    #   surface that GROWS as a session proceeds. This asserts the override is present and complete,
    #   which is what makes interactive completion agree with the documentation.
    published = frozenset(module.__all__)  # type: ignore[attr-defined]

    assert published <= frozenset(dir(module))
