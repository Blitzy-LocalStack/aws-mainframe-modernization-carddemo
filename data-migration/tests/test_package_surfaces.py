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
from types import ModuleType
from typing import Final

import pytest

from carddemo_migration import loaders, readers, verify
from carddemo_migration.copybook import layouts

# Assumptions: the directory is resolved from the imported package so the walk examines the same
#   tree the rest of the suite imports, which is the installed distribution rather than the
#   checkout under the arrangement data-migration/pyproject.toml sets up.
_READERS_DIR: Final[pathlib.Path] = pathlib.Path(readers.__file__ or "").parent

# Assumptions: resolved the same way as the reader directory above, and for the same reason -- the
#   inventory assertion below has to walk the tree the suite imports rather than a checkout path.
_LOADERS_DIR: Final[pathlib.Path] = pathlib.Path(loaders.__file__ or "").parent

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
    # Assumptions: the child inherits this process's sys.path, which is what makes it resolve
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
    # Assumptions: the expectation comes from the layouts module's own provenance split, so a
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
    # Refactoring Rationale: the boundary previously documented three of twelve readers, and
    #   nothing compared its documentation with the directory. Both directions are asserted because
    #   each catches a different mistake: a module present and unregistered is a reader no dispatch
    #   can reach, and a name registered without a module is an import error deferred to whichever
    #   load first needs that record.
    on_disk = {path.stem for path in _READERS_DIR.glob("*.py")} - _NON_READER_MODULES

    assert frozenset(readers.READER_MODULES) == on_disk
    assert on_disk <= frozenset(readers.__all__)


def _subprocess_service_clients(statement: str) -> frozenset[str]:
    """Report which third-party service clients a statement pulls into a fresh interpreter.

    Purpose
    -------
    Answer the question :func:`_subprocess_imports` cannot: that helper prints only
    ``carddemo_migration`` modules, so a set intersection against a driver or SDK name taken from
    it is empty whatever the statement imported. This one looks for the three client
    distributions by name.

    Parameters
    ----------
    statement : str
        Python source executed in the child before its module table is read.

    Returns
    -------
    frozenset of str
        The subset of ``psycopg``, ``boto3`` and ``botocore`` present in the child's module table.

    Raises
    ------
    AssertionError
        If the subprocess exits non-zero, which means the statement itself failed and the
        measurement would be meaningless.
    """
    # Assumptions: the check runs in a CHILD interpreter because this test module imports the
    #   loader package, so the driver may already be in the parent's `sys.modules` and an in-process
    #   assertion would pass or fail according to collection order.
    source = (
        f"{statement}\n"
        "import sys\n"
        "print('\\n'.join(m for m in ('psycopg', 'boto3', 'botocore') if m in sys.modules))\n"
    )
    completed = subprocess.run(  # noqa: S603 -- fixed argument list, no shell, no external input
        [sys.executable, "-c", source],
        capture_output=True,
        text=True,
        check=False,
    )
    assert completed.returncode == 0, completed.stderr
    return frozenset(line for line in completed.stdout.splitlines() if line)


def test_the_four_unguessable_layout_to_module_pairs_resolve() -> None:
    """Assert the mapping's whole reason for existing: the four names that do not transform.

    Returns
    -------
    None
        The assertion is the result.
    """
    # Assumptions: these four are the reason a mapping exists at all rather than a naming
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
    # Assumptions: the assertion is that the resolved module DECLARES the layout it was
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
    # Assumptions: mutability here would be a global, silent change to which decoder a dataset
    #   is read with -- the mapping is module state shared by every consumer in the process, so one
    #   caller's convenience would redirect every other caller's load.
    with pytest.raises(TypeError):
        readers.DATASET_READERS["ACCOUNT"] = "card"  # type: ignore[index]


def test_importing_the_reader_package_imports_every_reader_and_no_service_client() -> None:
    """Assert the boundary is eager over the twelve readers and still free of service clients.

    Returns
    -------
    None
        The assertion is the result.
    """
    # Refactoring Rationale: this test asserted the OPPOSITE property -- that importing the
    #   package imported no reader -- and it was withdrawn with the contract it described. The
    #   package's plan states that the dispatch mapping's values are callable, which requires the
    #   twelve readers to be imported; the cost argument the old test rested on counted this file
    #   and the two support modules every reader imports regardless, and the deferral it protected
    #   hid a broken reader until something first touched it.
    imported = _subprocess_imports("import carddemo_migration.readers")
    reader_imports = {name for name in imported if name.startswith("carddemo_migration.readers.")}

    assert {f"carddemo_migration.readers.{name}" for name in readers.READER_MODULES} <= (
        reader_imports
    )
    assert "carddemo_migration.copybook.layouts" in imported
    # Assumptions: the property that made the deferral affordable is the one asserted here
    #   instead, and it is the one that actually matters: eager import of the twelve pulls in NO
    #   database driver and NO AWS SDK, so the codec layer still imports and runs on a bare checkout
    #   with no credential configured. That is the guarantee the reader package's docstring makes,
    #   and it is now checked against an eager import rather than being true by the accident of
    #   importing nothing. It is measured by its own probe: `_subprocess_imports` prints only
    #   `carddemo_migration` names, so intersecting its result with a client name would be empty
    #   however the child had been written.
    assert _subprocess_service_clients("import carddemo_migration.readers") == frozenset()


def test_the_two_support_modules_are_the_only_lazily_resolved_names() -> None:
    """Assert the surface resolves the two support modules by hook and the readers by binding.

    Returns
    -------
    None
        The assertion is the result.
    """
    # Assumptions: the twelve readers are asserted to be reachable WITHOUT the hook, which is
    #   what "imported eagerly" means operationally: the name is already an attribute of the
    #   package, so ordinary lookup finds it. The two support modules are asserted reachable at all,
    #   which is what the hook is for -- `factory` is not imported by any reader, so nothing else
    #   would bind it.
    for name in readers.READER_MODULES:
        assert name in vars(readers), f"{name} is not bound, so the barrel is not eager"
    assert readers.factory.__name__.endswith(".factory")
    assert readers.source.__name__.endswith(".source")
    for name in readers.SUPPORT_MODULES:
        assert name in vars(readers)

    # Assumptions: the DIRECTION the default surface moves in is asserted, in a child
    #   interpreter where the starting state is known. An earlier comment in the reader package
    #   described the unoverridden surface as SHRINKING as modules import, which is the opposite of
    #   what the import system does -- importing a submodule BINDS it on the parent package, so the
    #   namespace only ever gains names. A reader trusting the old wording would have gone looking
    #   for the mechanism that removes one.
    probe = (
        "import carddemo_migration.readers as r\n"
        "before = set(vars(r))\n"
        "r.factory\n"
        "after = set(vars(r))\n"
        "print(f'{before <= after}|{sorted(after - before)}')\n"
    )
    completed = subprocess.run(  # noqa: S603 -- fixed argument list, no shell, no external input
        [sys.executable, "-c", probe],
        capture_output=True,
        text=True,
        check=False,
    )
    assert completed.returncode == 0, completed.stderr
    grew, gained = completed.stdout.strip().split("|", 1)
    assert grew == "True", "the package namespace lost a name when a module was resolved"
    assert "factory" in gained


def test_the_reader_package_refuses_an_unpublished_attribute() -> None:
    """Assert the lazy hook resolves only the published set and not an arbitrary sibling.

    Returns
    -------
    None
        The assertion is the result.
    """
    # Assumptions: a permissive hook forwarding every name to importlib would make any module
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
    # Refactoring Rationale: this package's ``__all__`` was an EMPTY list while its docstring
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
    # Assumptions: one entry point and one result type per pass is the published criterion, so
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
    # Assumptions: the lazy hook checks the module names BEFORE the curated entry points, so
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
    # Assumptions: the concrete cost being avoided is named rather than left general -- the
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


@pytest.mark.parametrize("module", [readers, verify, loaders], ids=["readers", "verify", "loaders"])
def test_dir_reports_the_documented_surface(module: object) -> None:
    """Assert ``dir()`` is exactly ``__all__`` rather than whatever a session has touched.

    Parameters
    ----------
    module : object
        One of the three packages under test.

    Returns
    -------
    None
        The assertion is the result.
    """
    # Assumptions: dir() is overridden in all three packages because a lazily resolved name is
    #   absent from the namespace until it is touched, so the default implementation reports a
    #   surface that GROWS as a session proceeds -- importing a submodule binds it on the parent, so
    #   the default answer only ever gains names. This asserts the override is present and complete,
    #   which is what makes interactive completion agree with the documentation.
    # Refactoring Rationale: the comparison is EQUALITY where it used to be a subset. A subset
    #   assertion accepted a hook that added a name of its own, which is exactly what the reader
    #   package's hook did: it appended ``reader_module`` by hand because that name was missing from
    #   the declaration, so the surface was stated in two places and they disagreed.
    published = frozenset(module.__all__)  # type: ignore[attr-defined]

    assert published == frozenset(dir(module))


def test_the_loader_inventory_is_the_directory_contents_in_one_stated_order() -> None:
    """Assert ``LOADER_MODULES`` is the directory's own modules, in sorted order.

    Returns
    -------
    None
        The assertion is the result.
    """
    # Refactoring Rationale: the tuple was documented as "dependency order" while being
    #   neither dependency order -- ``protected_columns`` before the ``aurora`` that imports it --
    #   nor workflow order, which would stage before loading. It is an INVENTORY, and the order is
    #   now sorted so that no reader can derive a false conclusion from the arrangement. This test
    #   is what holds it to both halves of that: the membership and the stated order.
    on_disk = {path.stem for path in _LOADERS_DIR.glob("*.py")} - {"__init__"}

    assert frozenset(loaders.LOADER_MODULES) == on_disk
    assert list(loaders.LOADER_MODULES) == sorted(loaders.LOADER_MODULES)
    for name in loaders.LOADER_MODULES:
        assert name in loaders.__all__


def test_a_resolved_loader_entry_point_is_bound_into_the_package_namespace() -> None:
    """Assert a lazily resolved class or function is cached, so it resolves at most once.

    Returns
    -------
    None
        The assertion is the result.
    """
    # Assumptions: the probe runs in a CHILD interpreter because this assertion is about the
    #   state of the package BEFORE the name is first touched, and by the time this suite runs other
    #   modules have already touched most of the surface in this process.
    # Refactoring Rationale: the resolver used to return the class or function without binding
    #   it, while its comment claimed each resolution happened at most once. That claim held for the
    #   MODULE branch -- ``import_module`` binds a submodule on its parent as a side effect -- and
    #   not for the entry-point branch, so every access to ``loaders.load_records`` re-entered the
    #   hook and re-ran the import and the attribute lookup.
    probe = (
        "import carddemo_migration.loaders as loaders\n"
        "before = 'load_records' in vars(loaders)\n"
        "first = loaders.load_records\n"
        "after = 'load_records' in vars(loaders)\n"
        "same = vars(loaders).get('load_records') is first is loaders.load_records\n"
        "print(f'{before}|{after}|{same}')\n"
    )
    completed = subprocess.run(  # noqa: S603 -- fixed argument list, no shell, no external input
        [sys.executable, "-c", probe],
        capture_output=True,
        text=True,
        check=False,
    )

    assert completed.returncode == 0, completed.stderr
    assert completed.stdout.strip() == "False|True|True", (
        f"a resolved entry point is not cached in the package namespace: {completed.stdout.strip()}"
    )


def test_the_loader_package_declares_names_but_no_record_geometry() -> None:
    """Assert the loader package declares table and family names and no record geometry.

    Returns
    -------
    None
        The assertion is the result.
    """
    # Refactoring Rationale: the boundary docstring claimed no DATASET NAME appeared in this
    #   package, which was false and unnecessarily so -- the load targets are keyed by record name
    #   and name their tables, and the generation families name their path segments, because
    #   declaring those mappings is what this package is FOR. The guarantee that is both true and
    #   worth having is about geometry, and this test holds the package to the narrowed form.
    aurora_module = loaders.aurora
    staging = loaders.s3_stage

    # The names ARE here, which is the half the old claim denied.
    assert {target.table for target in aurora_module.TARGETS.values()}
    assert set(staging.family_names())

    # The geometry is NOT: neither loader declares a record specification or a field of its own.
    for module in (aurora_module, staging):
        declared = [
            name
            for name, value in vars(module).items()
            if isinstance(value, layouts.RecordSpec | layouts.FieldSpec)
        ]
        assert declared == [], f"{module.__name__} declares its own geometry: {declared}"
    # Assumptions: the positive half is asserted too -- the bulk loader IMPORTS the layout
    #   registry -- so this cannot pass for a loader that declares no geometry because it uses none
    #   and hard-codes its offsets as bare integers instead.
    assert layouts.__name__ in {
        module.__name__ for module in vars(aurora_module).values() if isinstance(module, ModuleType)
    }
