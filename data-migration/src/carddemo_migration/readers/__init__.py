"""Decode fixed-width CardDemo extracts into records, behind one package boundary.

Purpose
-------
``carddemo_migration.readers`` is the layer between the byte geometry of
:mod:`carddemo_migration.copybook` and the target-datastore loaders of
:mod:`carddemo_migration.loaders`. One module per record layout turns an extract file into
decoded records, and each module additionally publishes the privacy-safe renderings a
diagnostic may emit for the record it owns.

This module is the subpackage entry point. It publishes the complete inventory of readers, the
mapping from a record layout to the module that owns it, and lazy access to every one of those
modules by attribute.

Parameters
----------
None
    A module takes no argument. Every published callable documents its own parameters at its own
    definition.

Returns
-------
None
    Importing binds names only. It imports NO reader module, opens no dataset, reads no
    environment variable and reaches no network; the twelve readers are resolved one at a time,
    on first access, by :func:`__getattr__` below.

Raises
------
LayoutError
    Raised at import by :mod:`carddemo_migration.copybook.layouts`, which this module imports for
    the layout names in :data:`DATASET_READERS`, if a declared layout is internally inconsistent
    or if either disclosure allowlist disagrees with the descriptors. That failure belongs to that
    module; nothing here re-raises or re-words it.
AttributeError
    Raised by attribute access for a name this package does not publish, and by
    :func:`reader_module` -- as :class:`KeyError` -- for a layout no reader owns.

The twelve record readers
-------------------------
Each publishes the same six decode entry points -- ``decode_``, ``iter_`` and ``read_`` for both
the ASCII seed form and the EBCDIC dataset form -- plus a key accessor and two masked renderings,
except where a record's own corpus makes an entry point meaningless, which is noted.

``account``
    The 300-byte account master of ``app/cpy/CVACT01Y.cpy``. Five ``PIC S9(10)V99`` money fields,
    which are the five ``NUMERIC(12,2)`` columns the money-total verification pass aggregates.
``card``
    The 150-byte card master of ``app/cpy/CVACT02Y.cpy``, whose primary account number and card
    verification value are the most closely held values in the migration. Carries the ``CARDAIX``
    alternate key.
``customer``
    The 500-byte customer master of ``app/cpy/CVCUS01Y.cpy``: the widest record in the corpus and
    the one holding name, address, national identifier, government-issued identifier and date of
    birth. Its ``CUST-FICO-CREDIT-SCORE`` is the corpus's one bounded score and projects to an
    ``int``.
``xref``
    The 50-byte card cross-reference of ``app/cpy/CVACT03Y.cpy``, which is also the ``CXACAIX``
    alternate access path. Its shipped ASCII seed is the one that relies on the trailing-pad
    tolerance: 36 data characters against a declared 50.
``transaction``
    The 350-byte posted transaction of ``app/cpy/CVTRA05Y.cpy``. The ONE record whose input may
    legitimately be absent -- no extract ships in either encoding, because the content is produced
    by the posting and backup pipeline -- so its file-taking entry points yield nothing for a
    missing source and refuse a path that exists but cannot be read.
``dalytran``
    The 350-byte daily transaction of ``app/cpy/CVTRA06Y.cpy``, the pre-posting feed, whose
    processing timestamp is blank on every shipped record.
``tcatbal``
    The 50-byte transaction-category balance of ``app/cpy/CVTRA01Y.cpy``, keyed by account,
    transaction type and transaction category.
``discgrp``
    The 50-byte disclosure group of ``app/cpy/CVTRA02Y.cpy``. Fifty-ONE records, not fifty: the
    extra row is the mandatory ``DEFAULT`` group the interest calculation falls back to.
``trantype``
    The 60-byte transaction type of ``app/cpy/CVTRA03Y.cpy``; seven records.
``trancatg``
    The 60-byte transaction category of ``app/cpy/CVTRA04Y.cpy``; eighteen records, each keyed by
    a type code and a category code, with a foreign key to the type.
``usrsec``
    The 80-byte security user of ``app/cpy/CSUSR01Y.cpy``. EBCDIC ONLY -- it ships in no ASCII
    form and publishes no ASCII entry point -- and the one record whose baseline holds a plaintext
    password, which this reader never decodes and the target never carries.
``export_record``
    The 500-byte export record of ``app/cpy/CVEXPORT.cpy``: a fixed envelope carrying one of five
    payload shapes, selected at run time by the envelope's own discriminator. EBCDIC only, because
    three of its regimes are not characters in any code page.

The two support modules
-----------------------
``factory``
    Builds a reader from a layout descriptor, so nine of the twelve are one layout constant plus a
    name rather than a thousand hand-written lines.
``source``
    The one hardened flat-file open and the two record-width guards every reader shares.

WHY (Refactoring Rationale)
---------------------------
This file used to document three readers, re-export nothing and declare no ``__all__``, and it said
so as a deliberate choice: it existed to make the directory a regular package rather than an
implicit namespace one, and the packaging argument for that is recorded below and still holds. What
the choice cost became clear once all twelve readers had landed. A caller holding a dataset name
had no way to reach the reader that owns it except by knowing the module name -- and the mapping
from a record to its module is not guessable, because four of the twelve are spelled differently
from the layout they read (``TRAN`` is read by ``transaction``, ``DISGROUP`` by ``discgrp``,
``TRANCAT`` by ``trancatg``, ``SECUSER`` by ``usrsec``). So every consumer either hard-coded that
mapping or guessed at it, which is one mapping in several places, and the package boundary
documented a third of its own contents.

WHY (Alternatives Considered)
-----------------------------
Eager re-export, which is what the sibling ``copybook`` package does: import every submodule at the
top and curate ``__all__``. Rejected here and only here, because the numbers differ by an order of
magnitude. ``copybook`` has four modules; ``readers`` has fourteen, together roughly seven hundred
kilobytes of source, and a command-line invocation that loads ONE dataset would import and compile
all of them. The eager form would also make this package's import cost grow with every record
added, for a caller that named one.

Also considered: publishing the reader modules themselves in a mapping, rather than their names.
Rejected because building that mapping requires importing all twelve at import time, which is
precisely the cost the lazy form exists to avoid -- a registry of names is inert, and
:func:`reader_module` turns a name into a module at the moment a caller actually wants one.

WHY (Trade-offs)
----------------
Lazy attribute resolution means a typo in a submodule -- a syntax error, a bad import -- surfaces
on first ACCESS rather than at package import, so a defect in one reader no longer stops a load
that never touches it. That is the point, and it is also the cost: it moves the failure later.
Two things keep the cost bounded. ``data-migration/tests/test_readers.py`` imports every reader
by name, so a broken module fails the suite rather than only a production run; and
:data:`READER_MODULES` is asserted against the directory's actual contents, so a reader that is
present and absent from this inventory fails too.

WHY (Assumptions)
-----------------
This file's ORIGINAL reason for existing is unchanged and is restated because it is easy to
mistake for boilerplate. This directory had no ``__init__.py``, which made
``carddemo_migration.readers`` an implicit NAMESPACE package while its siblings ``copybook`` and
``loaders`` were regular packages -- measured rather than assumed: before this file existed,
``importlib.import_module("carddemo_migration.readers").__file__`` was ``None`` where both siblings
reported a real path. ``pyproject.toml`` records the reasoning at the site where the same
correction was made for ``copybook``: setuptools discovery finds a regular package by its
``__init__.py``, so a namespace child is included in a built artifact only incidentally. That file
deliberately puts NO source directory on pytest's import path so the import the suite exercises is
the INSTALLED distribution, on the stated grounds that "a subpackage left out of the built artifact
passes every test and fails only when the container starts" -- and a namespace subpackage weakens
that guarantee for the one layer that owns every record layout.
"""

from __future__ import annotations

import importlib
from types import MappingProxyType, ModuleType
from typing import TYPE_CHECKING, Final

from carddemo_migration.copybook.layouts import EXPORT_HEADER_LAYOUT, base_master_names

if TYPE_CHECKING:  # pragma: no cover - imported for annotations only
    from collections.abc import Mapping

__all__ = [
    "DATASET_READERS",
    "READER_MODULES",
    "SUPPORT_MODULES",
    "account",
    "card",
    "customer",
    "dalytran",
    "discgrp",
    "export_record",
    "factory",
    "source",
    "tcatbal",
    "trancatg",
    "transaction",
    "trantype",
    "usrsec",
    "xref",
]

# WHY : Assumptions: the mapping is keyed by the LAYOUT name -- the same key
#   `carddemo_migration.copybook.layouts.layout` takes -- and not by a dataset file name, because a
#   layout name is the one identifier that is stable across both corpora. The same record ships as
#   `app/data/ASCII/cardxref.txt` and `app/data/EBCDIC/AWS.M2.CARDDEMO.CARDXREF.PS`, so a file-name
#   key would need two entries per record and would answer differently depending on which corpus a
#   caller happened to name.
# WHY : Refactoring Rationale: this mapping exists because four of the twelve pairs are not
#   guessable by transformation. `TRAN` is read by `transaction`, `DISGROUP` by `discgrp`,
#   `TRANCAT` by `trancatg` and `SECUSER` by `usrsec` -- the module names follow the shipped
#   dataset spellings while the layout names follow the copybook record names, and neither is
#   derivable from the other. A caller deriving the module name from the layout name gets eight of
#   twelve right, which is the worst possible outcome: it works in development against the records
#   somebody tested and fails on the four nobody did.
# WHY : Trade-offs: the values are module NAMES rather than imported modules, so this constant is
#   inert and building it imports nothing. `reader_module` resolves a name to a module when a
#   caller wants one, which is what keeps a single-dataset load from importing all fourteen
#   modules in this package.
# WHY : Assumptions: MappingProxyType makes it read-only at run time, so a caller cannot redirect a
#   record to a different reader by mutating a shared dict -- which would be a silent, global change
#   to which decoder a dataset is read with.
DATASET_READERS: Final[Mapping[str, str]] = MappingProxyType(
    {
        "ACCOUNT": "account",
        "CARD": "card",
        "CUSTOMER": "customer",
        "XREF": "xref",
        "DALYTRAN": "dalytran",
        "TRAN": "transaction",
        "DISGROUP": "discgrp",
        "TCATBAL": "tcatbal",
        "SECUSER": "usrsec",
        "TRANCAT": "trancatg",
        "TRANTYPE": "trantype",
        EXPORT_HEADER_LAYOUT.name: "export_record",
    }
)

# WHY : Assumptions: the inventory is derived from the mapping above rather than written a second
#   time, so a reader added to one is present in the other by construction. Sorting it makes the
#   published order independent of the mapping's declaration order, which is the corpus order and
#   is the wrong order for a name lookup.
READER_MODULES: Final[tuple[str, ...]] = tuple(sorted(set(DATASET_READERS.values())))

# WHY : Assumptions: the two support modules are published in their own tuple rather than folded
#   into READER_MODULES, because they are not readers and a caller iterating readers must not be
#   handed them. `factory` builds a reader from a layout; `source` holds the hardened open and the
#   width guards. Both are importable through the same lazy attribute path, because a test that
#   exercises the shared guards needs to reach them by name.
SUPPORT_MODULES: Final[tuple[str, ...]] = ("factory", "source")

_LAZY_MODULES: Final[frozenset[str]] = frozenset(READER_MODULES) | frozenset(SUPPORT_MODULES)


def reader_module(layout_name: str) -> ModuleType:
    """Import and return the reader module that owns one record layout.

    Purpose
    -------
    Turn a layout name into the module that decodes it, so a caller holding a record identity does
    not have to hold the mapping to a module name as well.

    Parameters
    ----------
    layout_name : str
        A layout name as :mod:`carddemo_migration.copybook.layouts` spells it -- ``ACCOUNT``,
        ``SECUSER``, ``EXPORT`` and so on. The comparison is exact and case-sensitive, matching
        that module's own registry lookup.

    Returns
    -------
    ModuleType
        The imported reader module. The import happens on the first call for a given name and is
        cached by the interpreter thereafter, so repeated calls cost a dictionary lookup.

    Raises
    ------
    KeyError
        If no reader owns that layout. The message lists the layouts that do have one, because the
        three derived layouts -- the statement view, the reject stream and the interest
        transaction -- are deliberately readerless: each is an OUTPUT of the migrated pipeline
        rather than an extract to be read, so there is no source file to point a reader at.
    """
    # WHY : Trade-offs: an unknown layout raises rather than returning None, so a caller cannot
    #   accidentally treat "no reader" as "no records" -- which is the same conflation that made an
    #   unreadable path look like an absent dataset elsewhere in this package.
    try:
        module_name = DATASET_READERS[layout_name]
    except KeyError:
        raise KeyError(
            f"no reader owns layout {layout_name!r}; the layouts with a reader are"
            f" {sorted(DATASET_READERS)}. The derived layouts are readerless on purpose: each is"
            " produced by the migrated pipeline rather than shipped as an extract"
        ) from None
    return importlib.import_module(f"{__name__}.{module_name}")


def __getattr__(name: str) -> ModuleType:
    """Resolve a published submodule by attribute, importing it on first access.

    Purpose
    -------
    Let ``readers.account`` work after ``import carddemo_migration.readers`` alone, without this
    package importing all fourteen of its modules to make that true.

    Parameters
    ----------
    name : str
        The attribute being looked up. Only the twelve reader modules and the two support modules
        resolve; anything else is reported as missing.

    Returns
    -------
    ModuleType
        The imported submodule.

    Raises
    ------
    AttributeError
        If ``name`` is not a module this package publishes. The message names the package rather
        than only the attribute, because a mistyped reader name is otherwise reported identically
        to a mistyped attribute on a reader.
    """
    # WHY : Assumptions: only names in the published set are resolved, so this hook cannot be used
    #   to import an arbitrary sibling module by attribute. A permissive hook that forwarded every
    #   name to importlib would turn a typo into an ImportError from deep inside the import
    #   machinery, and would make any module that happened to sit in this directory reachable as
    #   part of the public surface.
    # WHY : Assumptions: Python calls this only when ordinary attribute lookup has already failed,
    #   so a module already imported -- by this hook or by an explicit `from ... import` -- is found
    #   in the package's namespace and never reaches here. That is what makes the import happen at
    #   most once per module without any cache written here.
    if name in _LAZY_MODULES:
        return importlib.import_module(f"{__name__}.{name}")
    raise AttributeError(f"module {__name__!r} has no attribute {name!r}")


def __dir__() -> list[str]:
    """Return the published surface, so interactive completion matches the documented one.

    Returns
    -------
    list[str]
        The sorted contents of :data:`__all__` together with :func:`reader_module`.

    Raises
    ------
    None
    """
    # WHY : Assumptions: dir() is overridden because the lazy modules are absent from this
    #   package's namespace until they are touched, so the default implementation would list a
    #   different -- and shrinking -- surface depending on what a session had already imported.
    return sorted([*__all__, "reader_module"])


# WHY : Assumptions: the inventory is proven against the layouts module at IMPORT rather than only
#   in the test suite, and the check is one comparison over names that are already in memory. Every
#   base master must have a reader, because each ships an extract that has to be loaded; the export
#   record is added separately because it is registered under its own layout rather than as a base
#   master. A base master left without a reader is a dataset the migration cannot read at all, and
#   discovering that at import is strictly better than discovering it when the load reaches that
#   dataset.
# WHY : Trade-offs: the failure is a ValueError raised from the package's own import, which is
#   blunt -- it stops every reader, not only the missing one. That is the correct bluntness here:
#   an incomplete reader inventory means the migration is incomplete, and a partial load is the
#   outcome this refuses to let happen quietly.
_EXPECTED_LAYOUTS: Final[frozenset[str]] = frozenset(base_master_names()) | {
    EXPORT_HEADER_LAYOUT.name
}
if frozenset(DATASET_READERS) != _EXPECTED_LAYOUTS:
    raise ValueError(
        "every base master and the export record must have a reader, but the reader registry and"
        f" the declared layouts disagree: registered without a layout"
        f" {sorted(frozenset(DATASET_READERS) - _EXPECTED_LAYOUTS)}, declared without a reader"
        f" {sorted(_EXPECTED_LAYOUTS - frozenset(DATASET_READERS))}"
    )
