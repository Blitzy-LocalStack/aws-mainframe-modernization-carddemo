"""Decode fixed-width CardDemo extracts into records, behind one package boundary.

Purpose
-------
``carddemo_migration.readers`` sits between the byte geometry of
:mod:`carddemo_migration.copybook` and the target-datastore loaders of
:mod:`carddemo_migration.loaders`. One module per record layout turns an extract file into decoded
records and publishes the privacy-safe renderings a diagnostic may emit for the record it owns.

This module is the subpackage entry point. It publishes the complete inventory of readers, the
mapping from a record layout to the module that owns it, one dispatch registry per corpus with the
resolver that selects between them, and attribute access to every one of those modules -- the
twelve readers bound eagerly at import, the two support modules resolved on first access.

The contract every reader is held to
------------------------------------
**One reader per record layout, at the documented record length.** No reader declares a layout, an
offset, a length or a ``USAGE`` width of its own: each imports its geometry from
:mod:`carddemo_migration.copybook.layouts` and its value decoders from
:mod:`carddemo_migration.copybook.zoned`, :mod:`carddemo_migration.copybook.packed` and
:mod:`carddemo_migration.copybook.ebcdic_codec`. A reader therefore differs from its siblings only
in which record descriptor it names. Each publishes the same six decode entry points -- ``decode_``,
``iter_`` and ``read_`` for both the ASCII seed form and the EBCDIC dataset form -- plus a key
accessor and two masked renderings, except where a record's own corpus makes an entry point
meaningless. Every ``iter_`` and ``read_`` entry point is a GENERATOR, so nothing here accumulates a
dataset in memory and a reader may be pointed at an extract far larger than the shipped seed
without any caller changing shape.

Parameters
----------
None
    A module takes no argument. Every published callable documents its own parameters at its own
    definition.

Returns
-------
None
    Importing binds names only: it opens no dataset, reads no environment variable and reaches no
    network. It DOES import the twelve reader modules, eagerly, which is what lets
    :data:`DATASET_READERS` hold the reader functions themselves and what turns a broken reader
    into an immediate ``ImportError`` from this package; the two support modules, ``factory`` and
    ``source``, are the ones :func:`__getattr__` resolves on first access. The cost and the two
    mistakes the earlier all-lazy shape made are argued under ``Trade-offs`` below.

    Refactoring Rationale: this contract used to read "imports NO reader module ... resolved one at
    a time, on first access", which described the withdrawn shape and contradicted both the
    ``Trade-offs`` section below it and the import block in the body. The top of a package entry
    point is where a reader settles what importing costs, so a stale answer there is the one worth
    correcting first.

Raises
------
LayoutError
    Raised at import by :mod:`carddemo_migration.copybook.layouts`, which this module imports for
    the layout names in :data:`DATASET_READERS`, if a declared layout is internally inconsistent or
    if either disclosure allowlist disagrees with the descriptors. Also raised by
    :func:`dataset_reader` for an unknown corpus, a layout no reader owns, or a layout that
    publishes no entry point for the requested corpus.
ValueError
    Raised at import by this module's closing inventory check if :data:`DATASET_READERS` and the
    layouts declared by ``layouts`` disagree about which records have a reader.
AttributeError
    Raised by attribute access for a name this package does not publish.
KeyError
    Raised by :func:`reader_module` for a layout no reader owns, with a message enumerating the
    layouts that do have one.

Dispatching by record and by corpus
-----------------------------------
:data:`DATASET_READERS` holds the twelve whole-extract EBCDIC entry points and
:data:`ASCII_DATASET_READERS` the ten whole-extract character entry points, both keyed by layout
name; :data:`CHARACTER_PATH_ABSENT_RECORDS` is the derived difference between them, and
:func:`dataset_reader` selects one callable from a layout name and a corpus name.

Assumptions: a caller that needs decoded records reaches them through :func:`dataset_reader` rather
than by building a reader from a layout with :mod:`carddemo_migration.readers.factory`. The two are
NOT interchangeable and the difference is a security boundary, not a convenience: a factory-built
reader carries the geometry and the suppression contract and none of the per-record policy the
twelve modules add on top -- ``card``'s ``ProtectedValue`` wrapper on the card verification value,
``usrsec``'s A/U user-type domain, the processing-timestamp validation in ``dalytran`` and
``transaction``, and ``transaction``'s absent-source semantics.

Assumptions: the layout NAME is the registry key, because ``data-migration/README.md`` records that
``--dataset`` is the record-layout identifier ``list-datasets`` reports, so a layout name is what
every caller already holds -- including the batch chain's per-dataset staging step. A file-name key
would need two entries per record, since one record ships as both ``app/data/ASCII/cardxref.txt``
and ``app/data/EBCDIC/AWS.M2.CARDDEMO.CARDXREF.PS``.

Four ways the twelve are NOT uniform
------------------------------------
Everything else about the twelve is interchangeable, which makes these four the facts a caller has
to know. Each is stated at its own reader as well; they are collected here because a caller who
reads only this boundary would otherwise assert that ``transaction`` returns rows, that ``usrsec``
has an ASCII path, or that ``xref`` refuses a 36-byte line -- each of which describes a working
reader as broken.

1. **``xref`` right-pads a short row.** ``app/data/ASCII/cardxref.txt`` carries 36 data bytes per
   line where ``CVACT03Y`` declares 50, the 14-byte trailing ``FILLER`` being omitted from the
   shipped seed. A short row is right-padded with spaces before any field is extracted; an
   over-long row is still refused, because padding on the right cannot move a field that exists
   whereas truncating an over-long row would move every field after the cut.
2. **``transaction`` has no seed dataset.** No ``TRANSACT`` extract ships in either encoding, so an
   absent or zero-row input is a NORMAL state for this one reader. Its
   ``HAS_COMMITTED_SEED_DATASET`` flag says so in code.
3. **``usrsec`` and ``export_record`` are EBCDIC-only**, for two different reasons. ``usrsec``
   could have a character path and is denied one: it ships in no ASCII form, and a transcode would
   be another copy of a record holding a plaintext password. ``export_record`` cannot have one:
   three of its regimes are not characters in any code page.
4. **``discgrp`` ships fifty-ONE records, not fifty.** The extra row is the mandatory ``DEFAULT``
   group the interest calculation falls back to.

Standard library only
---------------------
Importing this package pulls in the Python standard library and
:mod:`carddemo_migration.copybook` and nothing else -- no ``psycopg``, no ``boto3``, no
``botocore``, no ``ebcdic`` distribution and no sibling ``loaders``, ``verify``, ``config`` or
``credentials`` module. The guarantee is about THIS package and the copybook layer it stands on,
which is what makes the codecs exercisable against known-answer vectors on a bare checkout. A codec
defect is the one class of defect here that is silent -- it produces plausible numbers that are
wrong -- so reproducing one in isolation is load-bearing rather than tidy. Elsewhere in the
distribution ``config`` constructs AWS clients and ``credentials`` imports the database driver,
both inside functions.

Two meanings of "EBCDIC"
------------------------
The word names two distinct things, and conflating them produces a value of the right width and the
wrong content, which every downstream length check then passes. In
:mod:`carddemo_migration.copybook.zoned` it names a SIGN CONVENTION, the zoned-decimal overpunch,
whose characters are themselves ASCII-printable. In
:mod:`carddemo_migration.copybook.ebcdic_codec` it names a CHARACTER ENCODING, the cp037 code page.
The ASCII seeds carry overpunched signs with no cp037 anywhere; the ``.PS`` datasets carry both at
once. A reader's ``*_ascii_*`` and ``*_ebcdic_*`` entry points are named for the CORPUS they read,
so both trios go through the overpunch tables and only the second goes through cp037.

Relationship to the baseline
----------------------------
``app/**`` is reference-only input, and that includes ``app/data/**``. Every module below opens
those extracts read-only and never modifies, re-encodes, normalises or rewrites one in place: they
are the specification these decoders are held to, so a reader that adjusted its own input would
destroy the evidence it is checked against. ``tests/**``, ``scripts/**`` and ``samples/**`` are
reference-only on the same terms.

Assumptions: every import in this file and in the fourteen modules below is absolute and written in
full from ``carddemo_migration``, including this package's own children. ``TID252`` bans the
relative spelling, and the absolute form of a self-import resolves because importing a submodule
binds it on the partially initialised parent before the ``from`` clause is satisfied.

Trade-offs: the twelve readers are imported EAGERLY at package import and the two support modules
are not. Eager import binds :data:`DATASET_READERS` to callables rather than to module-name
strings, so a caller holding a record name gets something it can call, and it surfaces a syntax or
import error in any reader at package import rather than at the first access of that one reader.
The cost is measured: the twelve readers are 712 kibibytes of source, and nothing heavier hides
behind them -- each is standard-library-plus-``copybook``.

Assumptions: this file has content for two independent reasons. Packaging -- an implicit namespace
package is discovered by setuptools only by fallback, so a later ``packages`` or ``exclude`` entry
could drop this directory from the built wheel while every test in the checkout still passed, and
the failure would arrive as ``ModuleNotFoundError`` inside a container. And documentation -- a
package entry point is the first artifact kind the project's explainability rule names, and ruff's
``D104`` enforces it under an ``ignore`` list that is deliberately empty.
"""

from __future__ import annotations

# Trade-offs: the TWELVE readers are imported here, eagerly, and the two support modules are
#   not -- the module docstring argues the cost and the two mistakes the earlier deferral made. The
#   imports are written absolutely, from `carddemo_migration.readers`, including these imports of
#   this package's own children: `TID252` bans the relative spelling, and the absolute form of a
#   self-import resolves correctly because importing a submodule binds it on the partially
#   initialised parent before the `from` clause is satisfied.
# Assumptions: `importlib` is still imported, because `factory` and `source` remain resolved
#   on first access and `reader_module` answers for a record whose reader is already bound. It is
#   standard library, so it costs nothing an interpreter has not already paid.
# Assumptions: `Mapping` and `Callable` are imported for annotation only and stay behind the
#   type-checking guard, because `from __future__ import annotations` defers every annotation to a
#   string. Taking them at run time would import `collections.abc` for names nothing evaluates.
import importlib
from types import MappingProxyType, ModuleType
from typing import TYPE_CHECKING, Final

from carddemo_migration.copybook.layouts import (
    EXPORT_HEADER_LAYOUT,
    LayoutError,
    base_master_names,
)
from carddemo_migration.readers import (
    account,
    card,
    customer,
    dalytran,
    discgrp,
    export_record,
    tcatbal,
    trancatg,
    transaction,
    trantype,
    usrsec,
    xref,
)

if TYPE_CHECKING:  # pragma: no cover - imported for annotations only
    import pathlib
    from collections.abc import Callable, Iterator, Mapping

# Alternatives Considered: the surface is DECLARED rather than left to whatever names happen
#   to be bound, and the reasoning is in the module docstring above: a surface that changes shape
#   whenever a module gains a helper is not a contract, and `cli.py` names this exact file as a
#   dependency. The list is also the input `__getattr__` resolves against, so the declaration and
#   the resolver check each other rather than agreeing by coincidence.
# Assumptions: the three constants come first and the fourteen module names follow in sorted
#   order, matching how the sibling `copybook` package declares its own surface, so both entry
#   points in this distribution read the same way. `reader_module` sorts among them because it is a
#   published callable rather than a constant.
# Refactoring Rationale: `reader_module` was absent from this list while being public, used
#   by `cli.py` and `verify/row_counts.py`, documented in the module docstring above, and added to
#   `__dir__` by hand. A surface declared in two places disagreed in exactly the way that matters:
#   `from carddemo_migration.readers import *` did not bring in the one callable a dispatching
#   caller needs, and `__dir__` had to compensate for the omission rather than derive from the
#   declaration.
__all__ = [
    "ASCII_DATASET_READERS",
    "CHARACTER_PATH_ABSENT_RECORDS",
    "DATASET_READERS",
    "READER_MODULES",
    "SUPPORT_MODULES",
    "account",
    "card",
    "customer",
    "dalytran",
    "dataset_reader",
    "discgrp",
    "export_record",
    "factory",
    "reader_module",
    "ships_committed_extract",
    "source",
    "tcatbal",
    "trancatg",
    "transaction",
    "trantype",
    "usrsec",
    "xref",
]

# Assumptions: the mapping is keyed by the LAYOUT name -- the identifier
#   `carddemo_migration.copybook.layouts` spells, that `list-datasets` prints and that `cli.py`
#   names `--dataset` -- so this registry speaks the vocabulary every caller already holds rather
#   than an internal one. The full argument, including why a dataset FILE name was rejected, is in
#   the module docstring above.
# Assumptions: this registry and the set `--dataset` accepts OVERLAP on the eleven base
#   masters and each carries one deliberate extra, which is worth stating because the near-match
#   invites the assumption that they are the same set. `EXPORT` is here and not there because
#   `layouts` registers the export envelope separately rather than in the resolvable registry, so a
#   caller reaches that reader through this mapping rather than through `--dataset`. The three
#   derived layouts are there and not here because each is an OUTPUT of the migrated pipeline, so
#   `--dataset` can name one while no reader owns it -- which is precisely the case `reader_module`
#   raises for rather than answering with nothing.
# Refactoring Rationale: this mapping exists because four of the twelve pairs are not
#   guessable by transformation. `TRAN` is read by `transaction`, `DISGROUP` by `discgrp`,
#   `TRANCAT` by `trancatg` and `SECUSER` by `usrsec` -- the module names follow the shipped
#   dataset spellings while the layout names follow the copybook record names, and neither is
#   derivable from the other. A caller deriving the module name from the layout name gets eight of
#   twelve right, which is the worst possible outcome: it works in development against the records
#   somebody tested and fails on the four nobody did.
# Alternatives Considered: module NAME strings as the values, which would let the mapping be
#   built without importing anything. Rejected because it leaves the mapping unable to dispatch: a
#   caller holding a record name would receive a string and need a second call to obtain something
#   it could invoke, so a "dispatch mapping" would name a lookup table that dispatches nothing.
#   Every value here is a CALLABLE -- the reader's whole-extract EBCDIC entry point.
# Assumptions: the EBCDIC entry point is the one chosen, and it is chosen because it is the
#   only entry point ALL TWELVE readers publish. `usrsec` ships in no ASCII form -- transcoding it
#   would be another copy of a plaintext password -- and three of `export_record`'s five payload
#   regimes are not characters in any code page, so neither has an ASCII path to name here. A
#   mapping missing two of twelve entries would not be a dispatch table, and a mapping whose value
#   was a PAIR of entry points would push the corpus choice onto every caller when the binary `.PS`
#   extracts are what the cutover actually reads. Each reader's ASCII entry point stays reachable at
#   its own module, which is the spelling the scenario tests already use.
# Trade-offs: the values are bound FUNCTIONS rather than the modules that hold them, so a
#   caller can invoke a value directly without knowing the entry point's name -- which differs per
#   record: `read_ebcdic_accounts`, `read_ebcdic_card_xrefs`, `read_ebcdic_security_users`. The
#   modules stay published under their own names for everything else a caller needs from one, and
#   `reader_module` still answers "which module owns this record" for the two consumers that ask.
# Assumptions: no record length, offset or field width appears in this mapping. Those belong
#   to `carddemo_migration.copybook.layouts`, which every reader resolves them from, and a second
#   copy here is exactly the drift single-sourcing exists to prevent -- the copy would keep
#   answering plausibly after the original was corrected. The lengths are documented in the table
#   above, which is prose a reader consults rather than a value this module branches on.
# Assumptions: MappingProxyType makes it read-only at run time, so a caller cannot redirect a
#   record to a different reader by mutating a shared dict -- which would be a silent, global change
#   to which decoder a dataset is read with.
DATASET_READERS: Final[Mapping[str, Callable[[pathlib.Path], Iterator[Mapping[str, object]]]]] = (
    MappingProxyType(
        {
            "ACCOUNT": account.read_ebcdic_accounts,
            "CARD": card.read_ebcdic_cards,
            "CUSTOMER": customer.read_ebcdic_customers,
            "XREF": xref.read_ebcdic_card_xrefs,
            "DALYTRAN": dalytran.read_ebcdic_daily_transactions,
            "TRAN": transaction.read_ebcdic_transactions,
            "DISGROUP": discgrp.read_ebcdic_disclosure_groups,
            "TCATBAL": tcatbal.read_ebcdic_category_balances,
            "SECUSER": usrsec.read_ebcdic_security_users,
            "TRANCAT": trancatg.read_ebcdic_transaction_categories,
            "TRANTYPE": trantype.read_ebcdic_transaction_types,
            EXPORT_HEADER_LAYOUT.name: export_record.read_ebcdic_export_records,
        }
    )
)

# Assumptions: the CHARACTER counterpart of the mapping above is declared so that a dispatching
#   caller can pick a CORPUS without also picking a weaker decoder. A caller able to reach only the
#   EBCDIC entry point through this boundary can honour an `--encoding ascii` request only by
#   building a GENERIC reader from the layout, which bypasses every policy the owning readers
#   enforce: `card`'s card-verification value arrives as bare text instead of a `ProtectedValue`,
#   `usrsec`'s A/U user-type domain goes unchecked, `dalytran` and `transaction` skip their
#   processing-timestamp validation, and `transaction`'s absent-source semantics become an ordinary
#   open failure.
# Alternatives Considered: deriving each character entry point from its EBCDIC sibling's
#   `__name__` -- replacing the `ebcdic` segment with `ascii` -- was evaluated and rejected. It
#   would work for all ten today and it is the same class of mistake this package already refuses
#   for module names: a derivation that is right until one reader is spelled differently, and whose
#   failure is an `AttributeError` from inside a dispatch rather than a missing registry entry.
#   Declaring the ten costs ten lines and cannot be right for nine records and wrong for one.
# Assumptions: this mapping holds TEN entries against the twelve above, and the two absences
#   are deliberate and unlike each other. `SECUSER` COULD have a character path and is denied one,
#   because every transcode of that record is another copy of a plaintext password --
#   `factory.CHARACTER_PATH_WITHHELD_RECORDS` states the same refusal for the generic reader, so
#   the path is closed at both doors. `EXPORT` could not have one at all: three of its five payload
#   regimes are not characters in any code page. The two are kept distinguishable rather than
#   merged into one "no ASCII" set because the first is a policy that could be revisited and the
#   second is a property of the bytes that cannot.
ASCII_DATASET_READERS: Final[
    Mapping[str, Callable[[pathlib.Path], Iterator[Mapping[str, object]]]]
] = MappingProxyType(
    {
        "ACCOUNT": account.read_ascii_accounts,
        "CARD": card.read_ascii_cards,
        "CUSTOMER": customer.read_ascii_customers,
        "XREF": xref.read_ascii_card_xrefs,
        "DALYTRAN": dalytran.read_ascii_daily_transactions,
        "TRAN": transaction.read_ascii_transactions,
        "DISGROUP": discgrp.read_ascii_disclosure_groups,
        "TCATBAL": tcatbal.read_ascii_category_balances,
        "TRANCAT": trancatg.read_ascii_transaction_categories,
        "TRANTYPE": trantype.read_ascii_transaction_types,
    }
)

# Assumptions: the set is DERIVED from the two registries rather than written out, so a
#   reader that gains or loses a character entry point changes this set by construction. Writing
#   `{"SECUSER", "EXPORT"}` here would be a fourth statement of one fact -- alongside the two
#   registries and `factory.CHARACTER_PATH_WITHHELD_RECORDS` -- and the copy would keep reading
#   plausibly after the original changed.
CHARACTER_PATH_ABSENT_RECORDS: Final[frozenset[str]] = frozenset(DATASET_READERS) - frozenset(
    ASCII_DATASET_READERS
)

# Assumptions: the inventory is derived from the mapping above rather than written a second
#   time, so a reader added to one is present in the other by construction. Sorting it makes the
#   published order independent of the mapping's declaration order, which is the corpus order and
#   is the wrong order for a name lookup.
# Assumptions: each module name is read from its entry point's own `__module__` and reduced to
#   the last dotted segment, which is what keeps this inventory derived from a mapping that holds
#   functions rather than names. Reading `__module__` also makes the derivation self-correcting: an
#   entry pointing at a function from the wrong module reports that module here, and
#   `test_package_surfaces.py` compares this tuple against the directory's contents in both
#   directions, so the mistake surfaces as a mismatch rather than as a reader nobody dispatches to.
READER_MODULES: Final[tuple[str, ...]] = tuple(
    sorted({reader.__module__.rsplit(".", 1)[-1] for reader in DATASET_READERS.values()})
)

# Assumptions: the two support modules are published in their own tuple rather than folded
#   into READER_MODULES, because they are not readers and a caller iterating readers must not be
#   handed them. `factory` builds a reader from a layout; `source` holds the hardened open and the
#   width guards. Both are importable through the same lazy attribute path, because a test that
#   exercises the shared guards needs to reach them by name.
SUPPORT_MODULES: Final[tuple[str, ...]] = ("factory", "source")

# Refactoring Rationale: only the two SUPPORT modules are resolved lazily now. The twelve
#   readers are imported at the top of this file, so they are already bound as attributes of this
#   package and ordinary attribute lookup finds them without `__getattr__` being called at all --
#   which is why they are removed from this set rather than left in it harmlessly: a set claiming to
#   list what is resolved lazily and naming twelve names that never reach the resolver would be a
#   third description of the import policy, disagreeing with the other two.
_LAZY_MODULES: Final[frozenset[str]] = frozenset(SUPPORT_MODULES)


def reader_module(layout_name: str) -> ModuleType:
    """Import and return the reader module that owns one record layout.

    Purpose
    -------
    Turn a layout name into the module that decodes it, so a caller holding a record identity does
    not have to hold the mapping to a module name as well. This is the one callable that resolves
    an entry in :data:`DATASET_READERS`, and it is the surface ``cli.py``'s per-dataset
    subcommands reach the twelve readers through.

    Parameters
    ----------
    layout_name : str
        A layout name as :mod:`carddemo_migration.copybook.layouts` spells it -- ``ACCOUNT``,
        ``SECUSER``, ``EXPORT`` and so on. The comparison is exact and case-sensitive, matching
        that module's own registry lookup.

    Returns
    -------
    ModuleType
        The reader module. It is already imported -- this package imports the twelve at import --
        so the call is a registry lookup plus a ``sys.modules`` lookup.

    Raises
    ------
    KeyError
        If no reader owns that layout. The message lists the layouts that do have one, because the
        three derived layouts -- the statement view, the reject stream and the interest
        transaction -- are deliberately readerless: each is an OUTPUT of the migrated pipeline
        rather than an extract to be read, so there is no source file to point a reader at.
    """
    # Trade-offs: an unknown layout raises rather than returning None, so a caller cannot
    #   accidentally treat "no reader" as "no records" -- which is the same conflation that made an
    #   unreadable path look like an absent dataset elsewhere in this package.
    # Alternatives Considered: a package-specific exception class deriving from KeyError was
    #   evaluated so that the failure would carry a name of its own. It was rejected because the
    #   failure IS a mapping miss and reads as one, because `cli.py` already catches KeyError from
    #   this call to answer "this layout has no reader" with an empty suppression set, and because
    #   what a maintainer needs at that moment is the closed set of names -- which the message
    #   carries. What is deliberately NOT done is letting the bare subscript error escape: that one
    #   reports only the missing key and would leave the caller to discover the valid set by
    #   reading this file.
    try:
        reader = DATASET_READERS[layout_name]
    except KeyError:
        raise KeyError(
            f"no reader owns layout {layout_name!r}; the layouts with a reader are"
            f" {sorted(DATASET_READERS)}. The derived layouts are readerless on purpose: each is"
            " produced by the migrated pipeline rather than shipped as an extract"
        ) from None
    # Assumptions: the module is resolved from the registered CALLABLE's own `__module__`
    #   rather than from a name string, so this function and the registry cannot disagree about
    #   which module owns a record -- they read the same object. `import_module` on an
    #   already-imported name is a `sys.modules` lookup, which is what makes reaching the module
    #   this way no more expensive than holding a second mapping to it.
    return importlib.import_module(reader.__module__)


def dataset_reader(
    layout_name: str, encoding: str
) -> Callable[[pathlib.Path], Iterator[Mapping[str, object]]]:
    """Return the owning reader's whole-extract entry point for one layout and one corpus.

    Purpose
    -------
    Give a dispatching caller the ONE callable that reads a named record from a named corpus with
    every validation and value type that record's own reader enforces -- so a caller choosing a
    corpus cannot end up choosing a weaker decoder as well.

    Parameters
    ----------
    layout_name : str
        A layout name as :mod:`carddemo_migration.copybook.layouts` spells it -- ``ACCOUNT``,
        ``SECUSER``, ``EXPORT`` and so on. The comparison is exact and case-sensitive.
    encoding : str
        ``"ebcdic"`` for the ``.PS`` dataset form or ``"ascii"`` for the seed text form. Any other
        spelling is refused rather than defaulted, because defaulting would silently read a binary
        extract as text or the reverse.

    Returns
    -------
    Callable[[pathlib.Path], Iterator[Mapping[str, object]]]
        The owning reader's ``read_ebcdic_*`` or ``read_ascii_*`` generator, taking the extract
        path and yielding one decoded record at a time.

    Raises
    ------
    LayoutError
        If the encoding is not one of the two corpora, if no reader owns the layout, or if the
        layout has no entry point for that corpus. The message names the closed set in each case.
    """
    # Alternatives Considered: this raises `LayoutError` where the sibling `reader_module`
    #   raises `KeyError` for the same missing layout, and the difference is deliberate rather than
    #   an inconsistency. `reader_module` answers an INTERNAL question -- which module owns this
    #   record -- and `cli.py` already catches its `KeyError` to mean "no reader, so no suppression
    #   set". This function answers a question that came straight from a command argument, and its
    #   failure has to reach the operator as a classified non-zero exit rather than a traceback:
    #   `LayoutError` is in `cli.py`'s `_STEP_ERRORS`, and it is the type the owning readers already
    #   raise when a corpus is refused, so a caller needs one `except` clause rather than two.
    registries = {"ebcdic": DATASET_READERS, "ascii": ASCII_DATASET_READERS}
    registry = registries.get(encoding)
    if registry is None:
        raise LayoutError(
            f"unknown extract encoding {encoding!r}; the corpora this package reads are"
            f" {sorted(registries)}"
        )
    reader = registry.get(layout_name)
    if reader is not None:
        return reader
    # Assumptions: the two refusals are reported SEPARATELY, because they send a maintainer to
    #   different places. "No reader owns this layout" means the name is one of the derived layouts
    #   the migrated pipeline produces, so there is no extract to read at all; "no character entry
    #   point" means the record is real and ships in one corpus only, which is a fact about that
    #   record's own contract. One shared message would send half of each audience to the wrong
    #   file.
    if layout_name not in DATASET_READERS:
        raise LayoutError(
            f"no reader owns layout {layout_name!r}; the layouts with a reader are"
            f" {sorted(DATASET_READERS)}. The derived layouts are readerless on purpose: each is"
            " produced by the migrated pipeline rather than shipped as an extract"
        )
    raise LayoutError(
        f"layout {layout_name!r} publishes no character entry point, so it cannot be read from the"
        f" ASCII seed form; the records without one are"
        f" {sorted(CHARACTER_PATH_ABSENT_RECORDS)}. Read it from its EBCDIC extract instead"
    )


def __getattr__(name: str) -> ModuleType:
    """Resolve a published submodule by attribute, importing it on first access.

    Purpose
    -------
    Let ``readers.factory`` and ``readers.source`` work after ``import carddemo_migration.readers``
    alone, without this package importing either to make that true. The twelve readers do not reach
    here: they are imported at the top of this file, so ordinary attribute lookup finds them.

    Parameters
    ----------
    name : str
        The attribute being looked up. Only the two support-module names resolve; anything else is
        reported as missing, including a reader name misspelled in a way ordinary lookup missed.

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
    # Assumptions: only names in the published set are resolved, so this hook cannot be used
    #   to import an arbitrary sibling module by attribute. A permissive hook that forwarded every
    #   name to importlib would turn a typo into an ImportError from deep inside the import
    #   machinery, and would make any module that happened to sit in this directory reachable as
    #   part of the public surface.
    # Assumptions: Python calls this only when ordinary attribute lookup has already failed,
    #   so a module already imported -- by this hook or by an explicit `from ... import` -- is found
    #   in the package's namespace and never reaches here. `import_module` binds the submodule as an
    #   attribute of this package as a side effect, so the resolution happens at most once per
    #   module with no cache written in this file: the binding IS the cache. That side effect is
    #   what a lazily returned CLASS or FUNCTION would not have, which is why the sibling `loaders`
    #   entry point -- whose surface is classes and functions -- writes its resolutions into its own
    #   namespace explicitly and this hook does not need to.
    if name in _LAZY_MODULES:
        return importlib.import_module(f"{__name__}.{name}")
    raise AttributeError(f"module {__name__!r} has no attribute {name!r}")


def __dir__() -> list[str]:
    """Return the published surface, so interactive completion matches the documented one.

    Purpose
    -------
    Report this package's contract to ``dir()``, ``help()`` and a shell's completion, which would
    otherwise see only the subset of modules the current session happens to have touched.

    Parameters
    ----------
    None
        Python calls this hook with no argument.

    Returns
    -------
    list[str]
        The sorted contents of :data:`__all__`, which now includes :func:`reader_module`.
    """
    # Assumptions: this derives from `__all__` ALONE and adds nothing by hand, so the published
    #   surface is stated in one place and this hook cannot drift from it.
    # Assumptions: dir() is overridden because the two support modules are absent from this
    #   package's namespace until something imports them, so the default implementation would report
    #   a surface that GROWS as a session proceeds -- importing a submodule binds it on the parent
    #   package, so the default answer only ever gains names, never loses them.
    return sorted(__all__)


# Assumptions: the inventory is proven against the layouts module at IMPORT rather than only
#   in the test suite, and the check is one comparison over names that are already in memory. Every
#   base master must have a reader, because each ships an extract that has to be loaded; the export
#   record is added separately because it is registered under its own layout rather than as a base
#   master. A base master left without a reader is a dataset the migration cannot read at all, and
#   discovering that at import is strictly better than discovering it when the load reaches that
#   dataset.
# Trade-offs: the failure is a ValueError raised from the package's own import, which is
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


def ships_committed_extract(layout_name: str) -> bool:
    """Report whether a record layout has a committed seed extract that can be read.

    Purpose
    -------
    Publish the one authority for "does an extract for this record exist in the repository", so that
    every caller which has to decide whether to READ one branches on the owning reader's own
    declaration rather than on a list of layout names it maintains itself.

    Parameters
    ----------
    layout_name : str
        A record layout name owned by one of the readers.

    Returns
    -------
    bool
        True when the layout's reader does not declare itself unseeded.

    Raises
    ------
    KeyError
        Propagated from :func:`reader_module` if no reader owns the layout, with a message
        enumerating the layouts that do.
    """
    # Refactoring Rationale: this was a private helper inside
    #   `carddemo_migration.verify.money_parity`, and it is published here because a second caller
    #   appeared that must not disagree with the first. The combined verification gate must decide
    #   which registered datasets it may read an extract for, and the money pass must decide which
    #   columns it requires a source total for. Two copies of that rule is how the gate comes
    #   to offer an extract the pass refuses -- or, worse, to read a file that is not a full record.
    # Assumptions: the default is TRUE -- a reader that says nothing ships an extract -- and
    #   only a reader declaring the flag False is unseeded. `readers/transaction.py` is the one that
    #   declares it: no `app/data/ASCII/transact.txt` and no TRANSACT extract under
    #   `app/data/EBCDIC` exist, and `app/jcl/TRANFILE.jcl` primes that cluster from a single
    #   350-byte initializer record whose unpopulated fields do not decode as a whole transaction.
    #   Defaulting to False would make every other layout look unseeded, and an unseeded column is a
    #   PASSING not-comparable line -- so the wrong default would turn a forgotten extract into a
    #   clean bill of health.
    return bool(getattr(reader_module(layout_name), "HAS_COMMITTED_SEED_DATASET", True))
