"""Shared fixtures for the ``carddemo_migration`` ETL test suite.

Purpose
-------
Provide the one folder-wide fixture module every test under ``data-migration/tests``
builds on, covering the four things those tests need and nothing else:

* read-only access to the two committed record corpora -- the scenario fixtures under
  ``tests/fixtures`` and the seed datasets under ``app/data`` -- in both a text form and a
  raw-byte form;
* a scratch workspace that lives outside the repository checkout;
* a synthetic ``CSUSR01Y`` record builder, so the password-drop can be asserted without a
  real credential existing anywhere in this repository;
* in-process doubles for the Aurora and object-store clients, so no test needs a database,
  an emulator, a live endpoint, a network route or a credential.

There is exactly ONE ``conftest.py`` under ``data-migration/``. Collection settings,
``testpaths``, the ruff rule selection and the coverage source all live in
``data-migration/pyproject.toml``; none of them is restated, contradicted or duplicated
here, and this module registers NO pytest hook -- it declares fixtures only, so adding it
cannot change how the modules already in this folder are collected or reported.

Import discipline
-----------------
Every import is absolute and rooted at ``carddemo_migration``. There is no relative import,
and this module never edits the interpreter's module search path -- by insertion, by
appending, by changing the working directory, or through any import-machinery hook.

Assumptions: what makes those absolute imports resolve is the ``src`` layout plus the
packaging and pytest configuration in ``data-migration/pyproject.toml`` -- the distribution
is installed and imported from ``site-packages``, which is why that file deliberately sets
no ``pythonpath``. Its own rationale block records the consequence: an entry there would
shadow the installed copy, so a subpackage missing from the wheel would pass every test
here and fail for the first time in the container. An imperative search-path edit written
into this module would be worse still, because it would apply to every run including the
authoritative one and could not be turned off -- which is the reason that file states no
module and no conftest in this package manipulates the search path. So if an import here
fails, the fix is to install the distribution (``pip install ./data-migration``) or to
export ``PYTHONPATH=data-migration/src`` in a developer's own environment, never to patch
the path from inside this file.

Assumptions: nothing from ``tests.*`` is imported. The COBOL parity oracle's helpers under
``tests/helpers`` are reference material for a human author, not runtime dependencies of
this suite; its ``tests/fixtures`` files are read here strictly as DATA.

Corpus discipline
-----------------
Both corpora are opened for READING ONLY, through ``Path.read_bytes`` and
``Path.read_text``. No path under ``tests/**`` or ``app/**`` is ever opened for write,
append or truncation, nothing is copied into this folder, and no dataset is normalised in
place. Every record width comes from
:mod:`carddemo_migration.copybook.layouts`; none is spelled out here.

Return-code discipline
----------------------
Assumptions: ``pytest`` is a BINARY gate in this folder -- zero on success, non-zero on
failure. The graded condition-code rubric used by the COBOL parity oracle under ``tests/**``
(0 pass / 2 usage / 4 warn / 8 fail / 16 fatal, aggregating the worst code seen, in which a
warn-level 4 is that suite's documented green state because two baseline programs carry an
unfixable defect in immutable source) belongs to that oracle alone. It is deliberately not
imported, mirrored or approximated here, and no fixture below tolerates a failure, because
a graded rubric would make a genuine codec or loader defect indistinguishable from that
known baseline one.
"""

from __future__ import annotations

import re
import shutil
from collections.abc import Iterator, Mapping, Sequence
from pathlib import Path
from types import MappingProxyType
from typing import Any, Final

import pytest

from carddemo_migration.config import (
    REDACTED,
    REQUIRED_SSL_MODE,
    SCHEMA_ROLES,
    AuroraConnectionSettings,
    ConfigurationError,
    DatasetStagingSettings,
)
from carddemo_migration.copybook.layouts import (
    EXPORT_HEADER_LAYOUT,
    SECUSER_LAYOUT,
    Kind,
    RecordSpec,
    count_fixed_length_records,
    iter_ascii_text_records,
    iter_fixed_length_records,
    layout,
    reclen_of,
)

__all__ = [
    "EBCDIC_DATASET_PREFIX",
    "FORBIDDEN_LOADER_STATEMENTS",
    "SYNTHETIC_PASSWORD_FILL",
    "FakeAuroraConnection",
    "FakeAuroraCopy",
    "FakeAuroraCursor",
    "FakeAuroraDatabase",
    "FakeClientContractError",
    "FakeObjectStore",
    "FakeObjectStorePaginator",
    "FixtureCorpus",
    "SecUserRecordBuilder",
    "SeedCorpus",
    "aurora_settings",
    "fake_aurora",
    "fake_object_store",
    "fixture_corpus",
    "repo_root",
    "secuser_builder",
    "seed_corpus",
    "staging_settings",
    "workspace",
]

# WHY (Assumptions): these three directories are the landmarks this suite actually reads --
# the COBOL programs the record layouts were transcribed from, the scenario corpus, and the
# installed package's own source tree. Their joint presence is the cheap identity check that
# distinguishes the CardDemo repository from any other directory this file could be copied
# into, and it is the same shape the reference suite's own root validation uses.
_REPO_MARKERS: Final[tuple[str, ...]] = (
    "app/cbl",
    "tests/fixtures",
    "data-migration/src/carddemo_migration",
)

# WHY (Assumptions): resolved from this file's own on-disk position, which the repository
# layout fixes at ``<repo>/data-migration/tests/conftest.py`` -- so the repository root is
# its second parent. Alternatives Considered: honouring a ``CARDDEMO_REPO_ROOT`` environment
# override as the reference conftest does. Rejected because the reference accepts one only in
# order to reject any value that diverges from this same anchor; reading no override at all
# reaches the identical outcome with no surface to spoof, and this suite has no runner script
# that needs to supply one.
_REPO_ROOT: Final[Path] = Path(__file__).resolve().parents[2]

#: Prefix every dataset under ``app/data/EBCDIC`` carries on disk, measured from the
#: thirteen committed files. Exposed so a test can spell either the bare dataset name or the
#: full file name and reach the same object.
EBCDIC_DATASET_PREFIX: Final[str] = "AWS.M2.CARDDEMO."

# WHY (Assumptions): a fixture file name is NOT always the seed name for the same record.
# The measured corpus uses ``trandata.txt`` in the export scenario and ``acctfile.txt``,
# ``custfile.txt``, ``trnxfile.txt`` and ``xreffile.txt`` in the statement scenarios, where
# ``app/data/ASCII`` spells the same four records ``acctdata.txt``, ``custdata.txt``,
# ``dailytran.txt`` and ``cardxref.txt``. Mapping every spelling onto one registry record
# name is what lets a width come from ``reclen_of`` rather than from a literal at the call
# site, so a fixture and its seed can never be read at two different widths.
# Each pairing below is the one the scenario's own README declares: the export README names
# ``CVTRA05Y`` / ``TRAN`` for ``trandata.txt``, and the statement README names
# ``COSTM01.CPY`` / ``TRNX-RECORD`` for ``trnxfile.txt`` -- two 350-byte records that differ
# only in field naming and key width, which is exactly the confusion this table removes.
_FIXTURE_RECORD_NAMES: Final[Mapping[str, str]] = MappingProxyType(
    {
        "acctdata.txt": "ACCOUNT",
        "acctfile.txt": "ACCOUNT",
        "carddata.txt": "CARD",
        "cardxref.txt": "XREF",
        "custdata.txt": "CUSTOMER",
        "custfile.txt": "CUSTOMER",
        "dailytran.txt": "DALYTRAN",
        "discgrp.txt": "DISGROUP",
        "tcatbal.txt": "TCATBAL",
        "trandata.txt": "TRAN",
        "trnxfile.txt": "TRNX",
        "xreffile.txt": "XREF",
    }
)

# WHY (Assumptions): the nine committed ASCII seeds map onto registry records by file name,
# and two of those pairings are load-bearing rather than obvious. ``cardxref.txt`` is
# thirty-six characters wide on disk while ``XREF`` declares fifty, so it is read through
# ``iter_ascii_text_records``, which pads a short line to the declared width -- reading it at
# its observed width instead would put every later field at the wrong offset. And three of
# the nine carry ragged line endings (``tcatbal.txt`` CRLF on 49 of 50 rows, ``trantype.txt``
# on 6 of 7, ``trancatg.txt`` on all 18), which is why no terminator handling is written here
# at all: the layouts iterator strips at most one CR and one LF, in that order, in one place.
_ASCII_SEED_RECORD_NAMES: Final[Mapping[str, str]] = MappingProxyType(
    {
        "acctdata.txt": "ACCOUNT",
        "carddata.txt": "CARD",
        "cardxref.txt": "XREF",
        "custdata.txt": "CUSTOMER",
        "dailytran.txt": "DALYTRAN",
        "discgrp.txt": "DISGROUP",
        "tcatbal.txt": "TCATBAL",
        "trancatg.txt": "TRANCAT",
        "trantype.txt": "TRANTYPE",
    }
)

# WHY (Assumptions): every EBCDIC dataset's byte count divides its record length exactly,
# which is the property that makes a record count derivable without decoding a byte --
# 800 = 10 x 80 for the security file, 15000 = 50 x 300 for the account master, 105000 =
# 300 x 350 for the daily transactions, and so on for all of them. ``CARDXREF.PS`` is the one
# to note: it is 2500 bytes at the full fifty-byte width, unlike its thirty-six-byte ASCII
# counterpart, so the same record name yields a different physical shape in each tree.
_EBCDIC_SEED_RECORD_NAMES: Final[Mapping[str, str]] = MappingProxyType(
    {
        "AWS.M2.CARDDEMO.ACCDATA.PS": "ACCOUNT",
        "AWS.M2.CARDDEMO.ACCTDATA.PS": "ACCOUNT",
        "AWS.M2.CARDDEMO.CARDDATA.PS": "CARD",
        "AWS.M2.CARDDEMO.CARDXREF.PS": "XREF",
        "AWS.M2.CARDDEMO.CUSTDATA.PS": "CUSTOMER",
        "AWS.M2.CARDDEMO.DALYTRAN.PS": "DALYTRAN",
        "AWS.M2.CARDDEMO.DALYTRAN.PS.INIT": "DALYTRAN",
        "AWS.M2.CARDDEMO.DISCGRP.PS": "DISGROUP",
        "AWS.M2.CARDDEMO.TCATBALF.PS": "TCATBAL",
        "AWS.M2.CARDDEMO.TRANCATG.PS": "TRANCAT",
        "AWS.M2.CARDDEMO.TRANTYPE.PS": "TRANTYPE",
        "AWS.M2.CARDDEMO.USRSEC.PS": "SECUSER",
    }
)

# WHY (Assumptions): the export extract is the one EBCDIC dataset with no entry in the
# record registry, because a 500-byte export record is a fixed header followed by a branch
# chosen per record type rather than one field list. Its physical width still has a
# single source -- ``EXPORT_HEADER_LAYOUT.reclen`` -- so it is resolved from the layouts
# module like every other width and never written as a literal. 250000 bytes divide into
# exactly 500 such records.
_EXPORT_EBCDIC_DATASET: Final[str] = "AWS.M2.CARDDEMO.EXPORT.DATA.PS"

# WHY (Assumptions): both spellings of the export extract are precomputed, so the accessors
# recognise it through the same bare-name convenience every other EBCDIC dataset gets rather
# than being the one name that must be written in full.
_EXPORT_DATASET_SPELLINGS: Final[frozenset[str]] = frozenset(
    {_EXPORT_EBCDIC_DATASET, _EXPORT_EBCDIC_DATASET.removeprefix(EBCDIC_DATASET_PREFIX)}
)

#: The value written into the eight-byte ``SEC-USR-PWD`` slot by
#: :class:`SecUserRecordBuilder`. Unmistakably a placeholder, matching no credential
#: anywhere; see that class for why a placeholder is the only admissible content.
SYNTHETIC_PASSWORD_FILL: Final[str] = "XXXXXXXX"

# WHY (Assumptions): the ceiling the object store places on one multi-object delete request.
# It is stated here as the SERVICE's limit rather than read from the staging loader, whose own
# copy is private to that module: a double that imported the value under test could not
# discover a loader that batched against the wrong number, because both sides would be wrong
# together.
_MAX_DELETE_BATCH: Final[int] = 1000

# WHY (Assumptions): a data loader connects as a ``carddemo_<context>`` login role, and V0
# grants that role no DDL and no ownership -- schemas and roles are created by
# ``data-migration/sql/V0__schemas_and_roles.sql`` and indexes by each service's Flyway
# migration under a separate ``_migrator`` role. Any statement below would therefore either
# fail against the real cluster or, worse, succeed in a test that had been handed a
# privileged connection and so prove nothing about the privilege the loader actually runs
# with. Recording them is what turns that negative contract into something a test can assert.
# WHY (Trade-offs): ``DELETE`` is matched only in its ``DELETE FROM`` statement form. The bare
# keyword also appears inside ``ON DELETE RESTRICT``, which is legitimate DDL text a
# diagnostic query could quote, so matching the bare word would report a violation that is
# not one. The accepted cost is that a contrived spelling could evade the pattern; a false
# positive here is worse, because it would train a reader to disbelieve the report.
FORBIDDEN_LOADER_STATEMENTS: Final[Mapping[str, re.Pattern[str]]] = MappingProxyType(
    {
        "CREATE SCHEMA": re.compile(r"\bCREATE\s+SCHEMA\b", re.IGNORECASE),
        "CREATE ROLE": re.compile(r"\bCREATE\s+(?:ROLE|USER)\b", re.IGNORECASE),
        "GRANT": re.compile(r"\bGRANT\b", re.IGNORECASE),
        "REVOKE": re.compile(r"\bREVOKE\b", re.IGNORECASE),
        "DELETE": re.compile(r"\bDELETE\s+FROM\b", re.IGNORECASE),
        "TRUNCATE": re.compile(r"\bTRUNCATE\b", re.IGNORECASE),
        "CREATE INDEX": re.compile(r"\bCREATE\s+(?:UNIQUE\s+)?INDEX\b", re.IGNORECASE),
    }
)


class FakeClientContractError(AssertionError):
    """Error raised when a test double is used outside the contract it stands in for.

    Purpose
    -------
    Report a misuse of one of the doubles below -- a float reaching a money column, a copy
    row of the wrong arity, a query whose result nobody arranged -- as a failed assertion
    rather than as an ordinary exception.

    Assumptions: deriving from :class:`AssertionError` is deliberate. These conditions are
    not runtime errors a production caller should handle; they are gate failures, and pytest
    presents an ``AssertionError`` as the test's own failure with the offending value in the
    report. A bespoke ``RuntimeError`` would read as a defect in the double instead of as the
    contract breach it actually is.
    """


def _validate_repo_root() -> Path:
    """Resolve the repository root from this file's position and prove its shape.

    Purpose
    -------
    Anchor the suite to the repository that physically contains this module, and refuse to
    run against a tree that does not look like CardDemo -- so a misplaced copy of this file
    fails immediately and by name, rather than reporting every corpus lookup as a missing
    file.

    Parameters
    ----------
    None
        The anchor is derived from :data:`_REPO_ROOT`.

    Returns
    -------
    pathlib.Path
        The resolved, absolute, symlink-free repository root.

    Raises
    ------
    Failed
        Via :func:`pytest.fail`, if any directory in :data:`_REPO_MARKERS` is absent.
    """
    # WHY (Alternatives Considered): returning the anchor unchecked. Rejected because the
    # failure it produces is uninformative and arrives late -- every corpus accessor would
    # report its own missing file, and a reader would have to infer from a dozen such
    # messages that the root itself was wrong. Failing here names the missing landmark once.
    for marker in _REPO_MARKERS:
        if not (_REPO_ROOT / marker).is_dir():
            pytest.fail(
                f"repository root {_REPO_ROOT} is missing the expected directory {marker!r}, "
                "so this is not the CardDemo tree this suite reads its corpora from; "
                "refusing to run",
                pytrace=False,
            )
    return _REPO_ROOT


def _contained_under(child: Path, parent: Path, *, label: str) -> Path:
    """Resolve a path and require it to live inside a parent directory.

    Purpose
    -------
    Keep every corpus lookup inside the directory it names, so a scenario or dataset
    argument carrying ``..`` or an absolute path cannot reach a file outside the corpus.

    Parameters
    ----------
    child : pathlib.Path
        The candidate path, normally built by joining caller-supplied segments onto
        ``parent``.
    parent : pathlib.Path
        The directory the child must resolve under.
    label : str
        Short name of the lookup being validated, used only to make the failure actionable.

    Returns
    -------
    pathlib.Path
        The resolved, contained path.

    Raises
    ------
    Failed
        Via :func:`pytest.fail`, if the resolved child escapes ``parent``.
    """
    # WHY (Assumptions): the comparison is made on RESOLVED paths, so ``..`` segments and
    # symlinks are collapsed BEFORE containment is tested. A string-prefix test on the raw
    # value would be satisfied by ``<corpus>/../../etc``, which names a real escape; asking
    # ``is_relative_to`` about the resolved target reasons about the file that would actually
    # be opened.
    resolved = child.resolve()
    if resolved != parent and not resolved.is_relative_to(parent):
        pytest.fail(
            f"{label} resolves to {resolved}, which is outside {parent}; the corpora are read "
            "in place and a lookup may not leave the directory it names",
            pytrace=False,
        )
    return resolved


def _require_corpus_segment(segment: str, *, label: str) -> str:
    """Reject a corpus path segment that is empty, absolute or parent-relative.

    Purpose
    -------
    Catch a malformed lookup at the argument rather than at the filesystem, so the message
    names the argument that was wrong. A scenario directory may legitimately contain a
    forward slash (``posting/happy_path``), so only the traversal forms are refused.

    Parameters
    ----------
    segment : str
        The caller-supplied scenario directory or dataset file name.
    label : str
        Short name of the argument, quoted in the failure message.

    Returns
    -------
    str
        The unchanged segment.

    Raises
    ------
    Failed
        Via :func:`pytest.fail`, if the segment is not text, is blank, begins with a
        separator, or contains a ``..`` component.
    """
    if not isinstance(segment, str) or not segment.strip():
        pytest.fail(f"{label} must be a non-blank string, but was {segment!r}", pytrace=False)
    if segment.startswith(("/", "\\")):
        pytest.fail(
            f"{label}={segment!r} is absolute; corpus lookups are relative to the corpus root",
            pytrace=False,
        )
    if ".." in Path(segment).parts:
        pytest.fail(
            f"{label}={segment!r} contains a parent reference; the corpora are read in place",
            pytrace=False,
        )
    return segment


def _record_name_for(dataset: str, table: Mapping[str, str], *, corpus: str) -> str:
    """Look up the registry record name a dataset file holds.

    Purpose
    -------
    Turn a file name into the registry key its width is resolved through, and fail with the
    admissible spellings listed when the name is unknown -- which is the common mistake,
    because the fixture corpus and the seed tree spell four of the same records differently.

    Parameters
    ----------
    dataset : str
        The dataset file name, with no directory part.
    table : Mapping[str, str]
        The file-name-to-record-name mapping for the corpus being read.
    corpus : str
        Short name of that corpus, quoted in the failure message.

    Returns
    -------
    str
        The registry record name, resolvable through
        :func:`carddemo_migration.copybook.layouts.layout`.

    Raises
    ------
    Failed
        Via :func:`pytest.fail`, if the file name has no mapping.
    """
    name = table.get(dataset)
    if name is None:
        pytest.fail(
            f"{dataset!r} is not a known {corpus} dataset; the mapped names are "
            f"{', '.join(sorted(table))}",
            pytrace=False,
        )
    return name


class FixtureCorpus:
    """Read-only accessor over the scenario record corpus under ``tests/fixtures``.

    Purpose
    -------
    Resolve one dataset inside one scenario directory and hand back its contents in
    whichever of the three forms a test needs: verbatim bytes, whole text, or whole records
    padded to the width the copybook declares.

    Alternatives Considered: copying a handful of vectors into ``data-migration/tests`` and
    reading those instead. Rejected on what each choice proves. The committed corpus is the
    input the COBOL programs are run against and the same bytes the Java shared kernel's
    codec tests use, so decoding it here is what demonstrates that the Python codecs agree
    with the COBOL and with the Java -- a vector invented in this folder would only
    demonstrate that the Python agrees with itself. Copying would also duplicate a
    reference corpus the migration is forbidden to modify or re-pin, and a duplicate is a
    second thing to keep in step with a file nobody may edit.

    Assumptions: a zero-byte fixture is VALID input, not an error. Seven of the seventy-eight
    committed fixtures are genuinely empty -- the posting empty-input daily transactions, the
    zero-balance category balances, all four provisioning empty-input masters and the
    statement empty-input transaction file -- and each one models a real batch condition: a
    dataset that exists so the program opens it successfully and reads no rows. The reference
    codec states the same rule from the other side, noting that an empty dataset is
    represented by the caller iterating zero rows and so never reaches record validation at
    all. :meth:`records` therefore returns an empty tuple for those seven and never raises.

    Assumptions: every path is opened for READING only, through ``Path.read_bytes`` and
    ``Path.read_text``. Nothing here opens a corpus path for write, append or truncation, and
    no dataset is rewritten, re-encoded or normalised in place.
    """

    def __init__(self, root: Path) -> None:
        """Bind the accessor to a corpus root directory.

        Parameters
        ----------
        root : pathlib.Path
            The ``tests/fixtures`` directory, already resolved.

        Returns
        -------
        None
            Stores the root for later lookups.

        Raises
        ------
        None
            The root's existence is proven by the repository-root validation that produced
            it.
        """
        self.root = root

    def scenarios(self) -> tuple[str, ...]:
        """Return every leaf scenario directory, as corpus-relative posix paths.

        Purpose
        -------
        Let a test parametrise over the whole corpus without naming its scenarios, so a
        scenario added to the corpus is picked up rather than silently skipped.

        Parameters
        ----------
        None
            Walks :attr:`root`.

        Returns
        -------
        tuple[str, ...]
            Sorted ``<domain>/<scenario>`` paths of the directories that hold at least one
            ``.txt`` dataset. Twenty at the time of writing.

        Raises
        ------
        None
            An absent directory yields an empty tuple rather than an error; the
            repository-root validation has already proven the corpus root exists.
        """
        # WHY (Assumptions): the corpus is DISCOVERED rather than enumerated from a constant.
        # A hard-coded list of twenty would have to be edited in lockstep with a reference
        # tree this migration may not modify, and the failure mode of forgetting is the quiet
        # one -- a new scenario would simply never be parametrised over, and a suite that
        # silently tests less than it appears to is the outcome worth the most to avoid.
        found = {path.parent.relative_to(self.root).as_posix() for path in self.root.rglob("*.txt")}
        return tuple(sorted(found))

    def path(self, scenario: str, dataset: str) -> Path:
        """Return the resolved path of one dataset inside one scenario.

        Parameters
        ----------
        scenario : str
            The ``<domain>/<scenario>`` directory, for example ``posting/happy_path``.
        dataset : str
            The dataset file name, for example ``dailytran.txt``.

        Returns
        -------
        pathlib.Path
            The resolved path, proven to exist and to lie under :attr:`root`.

        Raises
        ------
        Failed
            Via :func:`pytest.fail`, if either argument is malformed, if the resolved path
            escapes the corpus root, or if the file does not exist.
        """
        _require_corpus_segment(scenario, label="scenario")
        _require_corpus_segment(dataset, label="dataset")
        candidate = _contained_under(
            self.root / scenario / dataset,
            self.root,
            label=f"fixture {scenario}/{dataset}",
        )
        if not candidate.is_file():
            pytest.fail(
                f"fixture {scenario}/{dataset} does not exist at {candidate}; the corpus is "
                "read in place and this suite never creates one",
                pytrace=False,
            )
        return candidate

    def raw_bytes(self, scenario: str, dataset: str) -> bytes:
        """Return one fixture's bytes exactly as committed.

        Purpose
        -------
        Give a decoder the untouched image, so nothing between the file and the assertion
        can alter a byte.

        Parameters
        ----------
        scenario : str
            The ``<domain>/<scenario>`` directory.
        dataset : str
            The dataset file name.

        Returns
        -------
        bytes
            The file's contents verbatim, empty for the seven zero-byte fixtures.

        Raises
        ------
        Failed
            Via :func:`pytest.fail`, propagated from :meth:`path`.
        """
        # WHY (Assumptions): a raw-byte form exists alongside the text form because a text
        # decode is not neutral over this corpus. The seed and extract datasets carry bytes
        # that no text codec preserves -- ``AWS.M2.CARDDEMO.EXPORT.DATA.PS`` holds five stray
        # 0x0A and eleven stray 0x0D bytes INSIDE its packed-decimal payload, where they are
        # data and not line boundaries, and the zoned sign overpunch rides the final digit
        # byte of every signed money field. Routing either through a decoder turns the byte
        # into a replacement character and loses the sign on negative balances silently, so
        # the byte path is the one every packed, binary or EBCDIC assertion must use.
        return self.path(scenario, dataset).read_bytes()

    def text(self, scenario: str, dataset: str) -> str:
        """Return one fixture decoded as ASCII text.

        Parameters
        ----------
        scenario : str
            The ``<domain>/<scenario>`` directory.
        dataset : str
            The dataset file name.

        Returns
        -------
        str
            The whole file as text, empty for the seven zero-byte fixtures.

        Raises
        ------
        Failed
            Via :func:`pytest.fail`, propagated from :meth:`path`.
        UnicodeDecodeError
            If the fixture holds a byte outside ASCII, which would mean the corpus had
            changed shape.
        """
        # WHY (Alternatives Considered): the codec is named explicitly, and ``ascii`` is chosen
        # over both ``utf-8`` and the platform default. The default was rejected because it
        # varies by host and locale, so the same fixture could decode differently in a container
        # than on a workstation. ``utf-8`` was rejected because it is the more PERMISSIVE of the
        # two here: it would silently accept a multi-byte sequence and hand back a string whose
        # character count no longer equals its byte count, which is exactly the property every
        # fixed-width offset in this corpus depends on. ``ascii`` raises instead, so a corpus
        # that stopped being seven-bit fails at the read rather than at an offset far downstream.
        return self.path(scenario, dataset).read_text(encoding="ascii")

    def records(self, scenario: str, dataset: str) -> tuple[str, ...]:
        """Return one fixture as whole records padded to the declared width.

        Purpose
        -------
        Hand a test the record sequence a reader would see, with the width taken from the
        copybook rather than from the file.

        Parameters
        ----------
        scenario : str
            The ``<domain>/<scenario>`` directory.
        dataset : str
            The dataset file name.

        Returns
        -------
        tuple[str, ...]
            One string per record, each exactly :meth:`reclen` characters. Empty for the
            seven zero-byte fixtures.

        Raises
        ------
        Failed
            Via :func:`pytest.fail`, propagated from :meth:`path` or :meth:`reclen`.
        LayoutError
            Propagated from the layouts iterator if a line is not character data or holds an
            interior separator.
        RecordLengthError
            Propagated from the layouts iterator if a line is longer than the declared width,
            which means the field offsets have moved and is refused rather than truncated.
        """
        # WHY (Alternatives Considered): splitting the text and stripping terminators here.
        # Rejected because ``iter_ascii_text_records`` already owns that rule for the whole
        # package -- it removes at most one trailing CR and one LF, in that order, refuses an
        # interior separator, and right-pads a short line to the declared width. Restating it
        # would create a second implementation of a rule the ragged corpus makes delicate,
        # and the two could then disagree about the same file: the ASCII seed tree mixes CRLF
        # and bare-LF rows within a single dataset, so a reader that stripped greedily would
        # shorten a legitimately space-padded final field.
        return tuple(iter_ascii_text_records(self.text(scenario, dataset), self.reclen(dataset)))

    def record_spec(self, dataset: str) -> RecordSpec:
        """Return the layout descriptor for the record a fixture file holds.

        Parameters
        ----------
        dataset : str
            The dataset file name.

        Returns
        -------
        RecordSpec
            The registry descriptor, carrying the field list, record length and key geometry.

        Raises
        ------
        Failed
            Via :func:`pytest.fail`, if the file name has no mapped record name.
        """
        return layout(_record_name_for(dataset, _FIXTURE_RECORD_NAMES, corpus="fixture"))

    def reclen(self, dataset: str) -> int:
        """Return the declared record width for a fixture file, in characters.

        Parameters
        ----------
        dataset : str
            The dataset file name.

        Returns
        -------
        int
            The width the copybook declares, resolved through the layouts registry.

        Raises
        ------
        Failed
            Via :func:`pytest.fail`, if the file name has no mapped record name.
        """
        return reclen_of(_record_name_for(dataset, _FIXTURE_RECORD_NAMES, corpus="fixture"))


class SeedCorpus:
    """Read-only accessor over the committed seed datasets under ``app/data``.

    Purpose
    -------
    Reach the nine line-oriented ASCII seeds and the thirteen fixed-length EBCDIC extracts
    that the ETL exists to load, each in the form its encoding admits: the ASCII tree as text
    or as padded records, the EBCDIC tree as bytes or as byte records sliced strictly by
    length.

    Assumptions: the two trees hold the SAME records in two different physical shapes, and
    conflating them is the mistake this class is arranged to prevent. The ASCII tree is
    newline-delimited text whose ``cardxref.txt`` is thirty-six characters wide against a
    fifty-byte copybook, so it is read through the padding text iterator. The EBCDIC tree has
    NO line terminators at all, so it is read through the strict fixed-length iterator, and
    every one of its datasets divides its record length exactly.

    Assumptions: no EBCDIC dataset is ever decoded here. Choosing a code page is the
    responsibility of the package's own EBCDIC codec, which holds an allow-list for it; this
    accessor hands over bytes so a test decodes field by field, which is the only order that
    keeps a sign byte, a packed nibble pair and an embedded low value intact.

    Assumptions: as with the fixture corpus, every path is opened for READING only. Nothing
    under ``app/data`` is rewritten, re-encoded or normalised, in place or otherwise.
    """

    def __init__(self, ascii_root: Path, ebcdic_root: Path) -> None:
        """Bind the accessor to the two seed directories.

        Parameters
        ----------
        ascii_root : pathlib.Path
            The ``app/data/ASCII`` directory, already resolved.
        ebcdic_root : pathlib.Path
            The ``app/data/EBCDIC`` directory, already resolved.

        Returns
        -------
        None
            Stores both roots for later lookups.

        Raises
        ------
        None
            Neither directory is probed here; a missing dataset is reported by the accessor
            that looks for it.
        """
        self.ascii_root = ascii_root
        self.ebcdic_root = ebcdic_root

    def ascii_datasets(self) -> tuple[str, ...]:
        """Return the ASCII seed file names this accessor can resolve a width for.

        Parameters
        ----------
        None
            Reads the mapping table.

        Returns
        -------
        tuple[str, ...]
            The nine sorted file names under ``app/data/ASCII``.

        Raises
        ------
        None
            The table is a module constant.
        """
        return tuple(sorted(_ASCII_SEED_RECORD_NAMES))

    def ebcdic_datasets(self) -> tuple[str, ...]:
        """Return the EBCDIC dataset file names this accessor can resolve a width for.

        Parameters
        ----------
        None
            Reads the mapping table and the export dataset name.

        Returns
        -------
        tuple[str, ...]
            The thirteen sorted file names under ``app/data/EBCDIC``, including the export
            extract, whose width comes from the export header layout rather than the registry.

        Raises
        ------
        None
            Both sources are module constants.
        """
        return tuple(sorted({*_EBCDIC_SEED_RECORD_NAMES, _EXPORT_EBCDIC_DATASET}))

    def ascii_path(self, dataset: str) -> Path:
        """Return the resolved path of one ASCII seed dataset.

        Parameters
        ----------
        dataset : str
            The seed file name, for example ``acctdata.txt``.

        Returns
        -------
        pathlib.Path
            The resolved path, proven to exist and to lie under ``app/data/ASCII``.

        Raises
        ------
        Failed
            Via :func:`pytest.fail`, if the name is malformed, escapes the seed root, or names
            no committed file.
        """
        return self._resolve(self.ascii_root, dataset, corpus="ASCII seed")

    def ebcdic_path(self, dataset: str) -> Path:
        """Return the resolved path of one EBCDIC seed dataset.

        Purpose
        -------
        Accept either the full committed file name or the bare dataset name, since the two
        differ only by the prefix every one of the thirteen files carries.

        Parameters
        ----------
        dataset : str
            The dataset name, spelled either ``AWS.M2.CARDDEMO.USRSEC.PS`` or ``USRSEC.PS``.

        Returns
        -------
        pathlib.Path
            The resolved path, proven to exist and to lie under ``app/data/EBCDIC``.

        Raises
        ------
        Failed
            Via :func:`pytest.fail`, if the name is malformed, escapes the seed root, or names
            no committed file under either spelling.
        """
        # WHY (Trade-offs): the bare spelling is accepted as a convenience, and the prefix it
        # prepends is a measured property of the thirteen committed files rather than a
        # convention invented here. The full name is tried first so an exact file name always
        # wins, which keeps the fallback from ever changing which file an unambiguous
        # argument reaches. The accepted cost is two spellings for one dataset; the benefit is
        # that a test reads ``USRSEC.PS`` instead of a twenty-five-character prefix.
        _require_corpus_segment(dataset, label="EBCDIC dataset")
        direct = self.ebcdic_root / dataset
        if not direct.is_file():
            dataset = f"{EBCDIC_DATASET_PREFIX}{dataset}"
        return self._resolve(self.ebcdic_root, dataset, corpus="EBCDIC seed")

    def ascii_raw_bytes(self, dataset: str) -> bytes:
        """Return one ASCII seed dataset's bytes exactly as committed.

        Purpose
        -------
        Expose the byte form of the ASCII tree too, because three of its nine datasets carry
        ragged line endings -- CRLF on 49 of 50 rows in the category balances, on 6 of 7 in
        the transaction types, on all 18 in the transaction categories -- and a test asserting
        that mixture has to see the terminators rather than a normalised reading of them.

        Parameters
        ----------
        dataset : str
            The seed file name.

        Returns
        -------
        bytes
            The file's contents verbatim, terminators included.

        Raises
        ------
        Failed
            Via :func:`pytest.fail`, propagated from :meth:`ascii_path`.
        """
        return self.ascii_path(dataset).read_bytes()

    def ascii_text(self, dataset: str) -> str:
        """Return one ASCII seed dataset decoded as ASCII text.

        Parameters
        ----------
        dataset : str
            The seed file name.

        Returns
        -------
        str
            The whole file as text, terminators included.

        Raises
        ------
        Failed
            Via :func:`pytest.fail`, propagated from :meth:`ascii_path`.
        UnicodeDecodeError
            If the file holds a byte outside ASCII, which would mean the seed tree had changed
            shape.
        """
        # WHY (Assumptions): the codec is ``ascii`` for the reason recorded on
        # :meth:`FixtureCorpus.text` -- character count must equal byte count for every
        # fixed-width offset to hold, and a permissive codec would break that silently. It is
        # named here too rather than factored out, because a shared reader would have to take the
        # encoding as an argument and the point is that neither corpus may choose a different one.
        return self.ascii_path(dataset).read_text(encoding="ascii")

    def ascii_records(self, dataset: str) -> tuple[str, ...]:
        """Return one ASCII seed dataset as whole records padded to the declared width.

        Parameters
        ----------
        dataset : str
            The seed file name.

        Returns
        -------
        tuple[str, ...]
            One string per row, each exactly the copybook's declared width -- fifty for the
            cross-reference, whose rows are thirty-six characters on disk and are padded up.

        Raises
        ------
        Failed
            Via :func:`pytest.fail`, propagated from :meth:`ascii_path` or :meth:`reclen`.
        LayoutError
            Propagated from the layouts iterator for a non-text or run-together line.
        RecordLengthError
            Propagated from the layouts iterator for an over-long line.
        """
        return tuple(iter_ascii_text_records(self.ascii_text(dataset), self.reclen(dataset)))

    def ebcdic_raw_bytes(self, dataset: str) -> bytes:
        """Return one EBCDIC dataset's bytes exactly as committed.

        Purpose
        -------
        Be the only way this suite reads the EBCDIC tree, because these are the datasets a
        text path cannot survive.

        Parameters
        ----------
        dataset : str
            The dataset name, in either spelling.

        Returns
        -------
        bytes
            The file's contents verbatim.

        Raises
        ------
        Failed
            Via :func:`pytest.fail`, propagated from :meth:`ebcdic_path`.
        """
        # WHY (Assumptions): these thirteen files hold no line terminator at all, so there is
        # nothing for a text reader to split on and the record boundary is the declared length
        # alone. The reference emulator helper reaches the same conclusion from the other
        # direction, uploading such a dataset straight from its path because round-tripping it
        # through a UTF-8 write would corrupt it. The export extract makes the point concrete:
        # its five 0x0A bytes sit inside packed fields, so a newline-aware reader would report
        # six pieces of wildly differing length instead of five hundred equal records.
        return self.ebcdic_path(dataset).read_bytes()

    def ebcdic_records(self, dataset: str) -> tuple[bytes, ...]:
        """Return one EBCDIC dataset sliced into whole records by declared length.

        Parameters
        ----------
        dataset : str
            The dataset name, in either spelling.

        Returns
        -------
        tuple[bytes, ...]
            One bytes object per record, each exactly :meth:`reclen` bytes and still encoded.

        Raises
        ------
        Failed
            Via :func:`pytest.fail`, propagated from :meth:`ebcdic_path` or :meth:`reclen`.
        RecordLengthError
            Propagated from the layouts iterator if the dataset's size is not an exact
            multiple of the record length, which would mean the file had been truncated or
            re-encoded.
        """
        return tuple(
            iter_fixed_length_records(self.ebcdic_raw_bytes(dataset), self.reclen(dataset))
        )

    def ebcdic_record_count(self, dataset: str) -> int:
        """Return how many whole records one EBCDIC dataset holds, without reading content.

        Purpose
        -------
        Derive a row count from the file size and, in doing so, assert the exact-division
        property that makes the count meaningful -- which is the correctness check for a
        fixed-length dataset, since no terminator exists to count instead.

        Parameters
        ----------
        dataset : str
            The dataset name, in either spelling.

        Returns
        -------
        int
            The record count, for example ten for the eight-hundred-byte security file at
            eighty bytes per record.

        Raises
        ------
        Failed
            Via :func:`pytest.fail`, propagated from :meth:`ebcdic_path` or :meth:`reclen`.
        RecordLengthError
            Propagated if the size is not an exact multiple of the record length.
        """
        size = self.ebcdic_path(dataset).stat().st_size
        return count_fixed_length_records(size, self.reclen(dataset))

    def record_spec(self, dataset: str) -> RecordSpec:
        """Return the layout descriptor for the record a seed dataset holds.

        Purpose
        -------
        Resolve a descriptor from either tree's spelling of a dataset name, so a test
        comparing the ASCII and EBCDIC forms of one record reads one descriptor.

        Parameters
        ----------
        dataset : str
            A seed file name from either tree, in either EBCDIC spelling.

        Returns
        -------
        RecordSpec
            The registry descriptor, or the export header descriptor for the export extract.

        Raises
        ------
        Failed
            Via :func:`pytest.fail`, if the name belongs to neither tree.
        """
        # WHY (Assumptions): the export extract is answered before the registry is consulted,
        # because it has no registry entry by design -- a 500-byte export record is a fixed
        # header followed by one of five branches chosen by record type, so the header
        # descriptor is what carries its physical width. Its branch layouts are selected
        # elsewhere, by record type, and are not a property of the file's geometry.
        if dataset in _EXPORT_DATASET_SPELLINGS:
            return EXPORT_HEADER_LAYOUT
        return layout(self._seed_record_name(dataset))

    def reclen(self, dataset: str) -> int:
        """Return the declared record width for a seed dataset, in bytes or characters.

        Parameters
        ----------
        dataset : str
            A seed file name from either tree, in either EBCDIC spelling.

        Returns
        -------
        int
            The width the copybook declares, resolved through the layouts registry or, for
            the export extract, through the export header descriptor.

        Raises
        ------
        Failed
            Via :func:`pytest.fail`, if the name belongs to neither tree.
        """
        return self.record_spec(dataset).reclen

    def _seed_record_name(self, dataset: str) -> str:
        """Return the registry record name a seed dataset holds, from either tree.

        Purpose
        -------
        Search the two mapping tables in one place so a caller never has to say which tree a
        dataset came from, and so an unknown name is reported once with every admissible
        spelling listed.

        Parameters
        ----------
        dataset : str
            A seed file name from either tree, in either EBCDIC spelling.

        Returns
        -------
        str
            The registry record name, resolvable through
            :func:`carddemo_migration.copybook.layouts.layout`.

        Raises
        ------
        Failed
            Via :func:`pytest.fail`, if the name appears in neither table under either
            spelling.
        """
        # WHY (Assumptions): the bare EBCDIC spelling is tried only AFTER both tables have
        # been consulted with the name exactly as given. Prepending the prefix first would let
        # a bare name shadow a real ASCII file name, and the two trees share four spellings.
        candidates = (dataset, f"{EBCDIC_DATASET_PREFIX}{dataset}")
        for table in (_ASCII_SEED_RECORD_NAMES, _EBCDIC_SEED_RECORD_NAMES):
            for candidate in candidates:
                name = table.get(candidate)
                if name is not None:
                    return name
        known = sorted({*_ASCII_SEED_RECORD_NAMES, *self.ebcdic_datasets()})
        pytest.fail(
            f"{dataset!r} is not a known seed dataset; the mapped names are {', '.join(known)}",
            pytrace=False,
        )

    def _resolve(self, root: Path, dataset: str, *, corpus: str) -> Path:
        """Resolve one seed dataset under a root and prove it exists inside it.

        Parameters
        ----------
        root : pathlib.Path
            The seed directory to resolve within.
        dataset : str
            The dataset file name.
        corpus : str
            Short name of the tree, quoted in the failure message.

        Returns
        -------
        pathlib.Path
            The resolved, contained, existing path.

        Raises
        ------
        Failed
            Via :func:`pytest.fail`, if the name is malformed, escapes ``root``, or names no
            committed file.
        """
        _require_corpus_segment(dataset, label=f"{corpus} dataset")
        candidate = _contained_under(root / dataset, root, label=f"{corpus} {dataset}")
        if not candidate.is_file():
            pytest.fail(
                f"{corpus} dataset {dataset!r} does not exist at {candidate}; the seed tree is "
                "read in place and this suite never creates one",
                pytrace=False,
            )
        return candidate


class SecUserRecordBuilder:
    """Builder for synthetic ``CSUSR01Y`` security records carrying no credential.

    Purpose
    -------
    Assemble one eighty-byte ``SEC-USER-DATA`` record from named parts, so a reader test can
    prove that the security reader DROPS the password field without a real credential
    existing anywhere in this repository.

    Assumptions: the eight-byte slot at offset 48 always receives
    :data:`SYNTHETIC_PASSWORD_FILL`, and that is a correctness requirement rather than
    caution. The baseline carries all ten users' passwords inline as literal text in the
    in-stream data of ``app/jcl/DUSRSECJ.jcl``, and the committed
    ``AWS.M2.CARDDEMO.USRSEC.PS`` is those same ten records encoded -- so a "realistic"
    fixture here would mean copying real credential material into new source. The target
    ``auth.users`` table deliberately has NO password column at all, which is the documented
    security correction this migration makes rather than ports. What a test asserts is
    therefore the ABSENCE of the field after reading, never a value, so the slot's content is
    irrelevant to the assertion and a placeholder is strictly better than a plausible value.
    Nothing in this class ever puts that slot into a message, and a caller may not override
    it.

    Assumptions: every offset and width comes from
    :data:`carddemo_migration.copybook.layouts.SECUSER_LAYOUT`, looked up by field name at
    build time. Writing the six offsets as literals would let this builder and the descriptor
    drift apart, and a record built at stale offsets still decodes to plausible text -- the
    failure would surface as a reader defect rather than as the fixture defect it was.
    """

    def __init__(self, spec: RecordSpec = SECUSER_LAYOUT) -> None:
        """Bind the builder to a record descriptor and prove it is space-paddable.

        Parameters
        ----------
        spec : RecordSpec
            The security-record descriptor. Defaults to the registry's ``SECUSER`` layout,
            which is the only descriptor this builder is written against; the parameter exists
            so a test can pass a deliberately wrong descriptor and assert the rejection.

        Returns
        -------
        None
            Stores the descriptor for later builds.

        Raises
        ------
        FakeClientContractError
            If the descriptor declares any field that is not character storage.
        """
        # WHY (Assumptions): every SECUSER field is TEXT, and this builder pads with spaces on
        # exactly that basis. A space is the correct pad for character storage and is WRONG
        # for every other kind -- a zoned field padded with spaces has no sign overpunch and a
        # packed field padded with spaces is not valid packed decimal, yet both would still be
        # eighty bytes and would look like a record. Asserting the kind here is what stops this
        # builder being reused for a numeric layout it cannot correctly pad. A frozen
        # descriptor is safe as a default argument because it cannot be mutated between calls.
        non_text = tuple(field.name for field in spec.fields if field.kind is not Kind.TEXT)
        if non_text:
            raise FakeClientContractError(
                f"{spec.name} declares non-character fields {non_text}, which this builder "
                "cannot pad with spaces; it is written for the all-TEXT security record"
            )
        self.spec = spec

    def build(
        self,
        *,
        user_id: str = "SYNTH001",
        first_name: str = "SYNTHETIC",
        last_name: str = "TESTUSER",
        user_type: str = "U",
        filler: str = "",
    ) -> str:
        """Build one synthetic eighty-character security record.

        Parameters
        ----------
        user_id : str
            Value for ``SEC-USR-ID``, at most eight characters.
        first_name : str
            Value for ``SEC-USR-FNAME``, at most twenty characters.
        last_name : str
            Value for ``SEC-USR-LNAME``, at most twenty characters.
        user_type : str
            Value for ``SEC-USR-TYPE``, exactly ``"A"`` for an administrator or ``"U"`` for an
            ordinary user.
        filler : str
            Value for the named trailing pad ``SEC-USR-FILLER``, at most twenty-three
            characters. Empty by default, which yields the all-space pad the seed records
            carry.

        Returns
        -------
        str
            The assembled record, exactly ``spec.reclen`` characters, with every part
            left-justified and space-padded to its declared width and with the password slot
            holding :data:`SYNTHETIC_PASSWORD_FILL`.

        Raises
        ------
        FakeClientContractError
            If any part is longer than its declared width, if ``user_type`` is not ``"A"`` or
            ``"U"``, or if the assembled record is not exactly the declared record length.
        """
        # WHY (Assumptions): the type domain is checked here because it is a documented
        # property of the copybook field rather than of its width -- the descriptor can only
        # prove the field is one character wide, and any single character would satisfy that.
        # An out-of-domain value would load into a target column whose check constraint admits
        # only these two, so rejecting it at the fixture is what keeps a reader test from
        # asserting behaviour the target schema would refuse.
        if user_type not in {"A", "U"}:
            raise FakeClientContractError(f"SEC-USR-TYPE must be 'A' or 'U', but was {user_type!r}")
        parts = {
            "SEC-USR-ID": user_id,
            "SEC-USR-FNAME": first_name,
            "SEC-USR-LNAME": last_name,
            "SEC-USR-PWD": SYNTHETIC_PASSWORD_FILL,
            "SEC-USR-TYPE": user_type,
            "SEC-USR-FILLER": filler,
        }
        chunks: list[str] = []
        for field in self.spec.fields:
            value = parts[field.name]
            if len(value) > field.length:
                # WHY (Assumptions): the offending VALUE is quoted for every field except the
                # password slot, whose content is never echoed anywhere. The slot is
                # unreachable here in practice because its filler is a constant of the
                # declared width, but the guard is written so that no future edit can make
                # this the line that prints it.
                shown = "<withheld>" if field.name == "SEC-USR-PWD" else repr(value)
                raise FakeClientContractError(
                    f"{field.name} is {len(value)} characters, which exceeds its declared "
                    f"width of {field.length}: {shown}"
                )
            chunks.append(value.ljust(field.length))
        record = "".join(chunks)
        if len(record) != self.spec.reclen:
            raise FakeClientContractError(
                f"assembled {self.spec.name} record is {len(record)} characters, but the "
                f"descriptor declares {self.spec.reclen}"
            )
        return record

    def build_bytes(self, *, encoding: str = "cp037", **parts: str) -> bytes:
        """Build one synthetic security record and encode it for a fixed-length reader.

        Purpose
        -------
        Produce the byte form a reader of the committed EBCDIC security file consumes, so a
        test can exercise the same path the real dataset takes.

        Parameters
        ----------
        encoding : str
            Codec name the record is encoded with. Defaults to ``"cp037"``, the code page the
            committed EBCDIC datasets are written in.
        **parts : str
            Any keyword :meth:`build` accepts, forwarded unchanged.

        Returns
        -------
        bytes
            The encoded record, exactly ``spec.reclen`` bytes for any single-byte code page.

        Raises
        ------
        FakeClientContractError
            Propagated from :meth:`build` for an over-long part or an out-of-domain type.
        LookupError
            If ``encoding`` names no installed codec.
        UnicodeEncodeError
            If a part holds a character the code page cannot represent.
        """
        # WHY (Alternatives Considered): encoding through the package's own EBCDIC codec.
        # Rejected because that module owns a code-page allow-list and its own validation,
        # which is the subject of other tests rather than a dependency of this builder -- a
        # fixture that produced its bytes through the code under test could not be used to
        # test that code. ``cp037`` is a standard-library codec, so this needs no third-party
        # import and stays usable on a bare checkout.
        return self.build(**parts).encode(encoding)


def _reject_float(value: object, *, context: str) -> object:
    """Refuse a binary floating-point value anywhere a money value can travel.

    Purpose
    -------
    Make the no-float rule a mechanical check at the boundary the doubles below stand in for,
    so a loader that let a ``float`` reach a ``NUMERIC(p,2)`` column fails in a test instead
    of rounding a balance in production.

    Parameters
    ----------
    value : object
        The value about to be recorded as a bound parameter or a copied field.
    context : str
        Short description of where the value appeared, quoted in the failure message.

    Returns
    -------
    object
        The unchanged value, so this can be used inline while recording.

    Raises
    ------
    FakeClientContractError
        If the value is a ``float``.
    """
    # WHY (Assumptions): the check is on ``float`` specifically, and ``Decimal``, ``int`` and
    # ``str`` all pass. A binary float cannot represent ten cents exactly, so a money total
    # routed through one is wrong by an amount that grows with the number of rows and is
    # invisible in any single value. ``bool`` and ``int`` are exact and are therefore
    # deliberately not refused; ``complex`` cannot reach a numeric column at all and would be
    # rejected by the driver rather than silently accepted, which is the difference that makes
    # ``float`` the one type worth guarding here.
    if isinstance(value, float):
        raise FakeClientContractError(
            f"{context} received the float {value!r}; money is exact fixed point end to end, "
            "so a Decimal is required and a float is refused"
        )
    return value


class FakeAuroraCopy:
    """Recorder standing in for a ``COPY ... FROM STDIN`` bulk-ingest stream.

    Purpose
    -------
    Accept the rows or bytes a loader streams into a copy operation and record them on the
    owning database, so a test can assert what was ingested without a server.

    Assumptions: the driver's copy object is used as a context manager and accepts either
    whole rows through ``write_row`` or pre-encoded bytes through ``write``. Both are offered
    because they are not interchangeable at the boundary this stands in for: a row is a
    sequence of Python values the driver adapts, whereas a written chunk is already in the
    wire format and bypasses adaptation entirely.
    """

    def __init__(self, database: FakeAuroraDatabase, statement: str) -> None:
        """Bind a copy stream to its database and statement.

        Parameters
        ----------
        database : FakeAuroraDatabase
            The database the rows are recorded on.
        statement : str
            The ``COPY`` statement the stream was opened with.

        Returns
        -------
        None
            Stores both for the duration of the stream.

        Raises
        ------
        None
            Nothing is validated until a row is written.
        """
        self.database = database
        self.statement = statement
        self.closed = False

    def write_row(self, row: Sequence[object]) -> None:
        """Record one row streamed into the copy operation.

        Parameters
        ----------
        row : Sequence[object]
            The field values, in the column order the ``COPY`` statement declares.

        Returns
        -------
        None
            The row is appended to the database's copied-row log.

        Raises
        ------
        FakeClientContractError
            If the stream has been closed, if the row is a bare string rather than a sequence
            of fields, or if any field is a ``float``.
        """
        if self.closed:
            raise FakeClientContractError(
                f"a row was written after the copy stream for {self.statement!r} had closed"
            )
        # WHY (Assumptions): a ``str`` is refused even though it is a Sequence. Passing one
        # would make each CHARACTER a field, so a ten-character key would be recorded as ten
        # columns -- an arity error the driver reports but which a permissive double would
        # accept and then attribute to the loader's column list.
        if isinstance(row, (str, bytes, bytearray)):
            raise FakeClientContractError(
                f"a copy row must be a sequence of field values, but was {type(row).__name__}; "
                "pass write() a pre-encoded chunk instead"
            )
        values = tuple(_reject_float(value, context="a copy row field") for value in row)
        self.database.copied_rows.append((self.statement, values))

    def write(self, data: bytes | str) -> None:
        """Record one pre-encoded chunk streamed into the copy operation.

        Parameters
        ----------
        data : bytes | str
            The chunk exactly as the loader produced it.

        Returns
        -------
        None
            The chunk is appended to the database's copied-chunk log, verbatim.

        Raises
        ------
        FakeClientContractError
            If the stream has been closed.
        """
        if self.closed:
            raise FakeClientContractError(
                f"a chunk was written after the copy stream for {self.statement!r} had closed"
            )
        self.database.copied_chunks.append((self.statement, data))

    def __enter__(self) -> FakeAuroraCopy:
        """Enter the copy stream's context manager.

        Parameters
        ----------
        None
            Operates on this stream.

        Returns
        -------
        FakeAuroraCopy
            This stream, so rows can be written inside the block.

        Raises
        ------
        None
            Entering cannot fail.
        """
        return self

    def __exit__(self, *exc_info: object) -> None:
        """Leave the copy stream's context manager, closing it.

        Parameters
        ----------
        *exc_info : object
            The exception type, value and traceback pytest's runtime supplies, unused.

        Returns
        -------
        None
            Returning ``None`` lets any exception in the block propagate, which is the
            behaviour a rollback path depends on.

        Raises
        ------
        None
            Closing cannot fail.
        """
        self.closed = True


class FakeAuroraCursor:
    """Recorder standing in for a database cursor over :class:`FakeAuroraConnection`.

    Purpose
    -------
    Execute statements against a recording database, return rows a test arranged in advance,
    and open copy streams -- covering the whole cursor surface a loader or verification pass
    uses.

    Assumptions: the surface mirrored here is the one the package already declares twice for
    the real driver -- ``execute`` with optional bound parameters, ``fetchall``, and use as a
    context manager -- extended with ``fetchone``, ``rowcount`` and ``copy``, which a
    row-count verification pass and a bulk load respectively need.
    """

    def __init__(self, connection: FakeAuroraConnection) -> None:
        """Bind a cursor to its connection.

        Parameters
        ----------
        connection : FakeAuroraConnection
            The connection this cursor executes through.

        Returns
        -------
        None
            Stores the connection and starts with no result set.

        Raises
        ------
        None
            Nothing is validated until a statement is executed.
        """
        self.connection = connection
        self.rows: list[tuple[object, ...]] = []
        self.rowcount = -1
        self.closed = False

    def execute(self, statement: str, params: Sequence[object] | None = None) -> FakeAuroraCursor:
        """Execute one statement, recording it and loading any arranged result set.

        Parameters
        ----------
        statement : str
            The SQL text, recorded verbatim so a test can assert on it.
        params : Sequence[object] | None
            Bound parameters, or ``None`` for a statement with none.

        Returns
        -------
        FakeAuroraCursor
            This cursor, matching the driver's chaining behaviour.

        Raises
        ------
        FakeClientContractError
            If the cursor has been closed, or if any bound parameter is a ``float``.
        """
        if self.closed:
            raise FakeClientContractError(
                f"a statement was executed on a closed cursor: {statement!r}"
            )
        bound = (
            None
            if params is None
            else tuple(_reject_float(value, context="a bound parameter") for value in params)
        )
        self.connection.database.record_statement(statement, bound)
        self.rows = list(self.connection.database.rows_for(statement))
        self.rowcount = len(self.rows)
        return self

    def fetchall(self) -> list[tuple[object, ...]]:
        """Return every row of the current result set.

        Parameters
        ----------
        None
            Reads the result set the last :meth:`execute` loaded.

        Returns
        -------
        list[tuple[object, ...]]
            The arranged rows, or an empty list when nothing was arranged for the statement.

        Raises
        ------
        None
            An unarranged statement yields no rows rather than an error, because a query
            returning nothing is a legitimate outcome a verification pass has to handle.
        """
        return list(self.rows)

    def fetchone(self) -> tuple[object, ...] | None:
        """Return the first row of the current result set, or ``None`` when there is none.

        Parameters
        ----------
        None
            Reads the result set the last :meth:`execute` loaded.

        Returns
        -------
        tuple[object, ...] | None
            The first arranged row, or ``None`` for an empty result.

        Raises
        ------
        None
            An empty result is reported as ``None``, exactly as the driver does.
        """
        return self.rows[0] if self.rows else None

    def copy(self, statement: str) -> FakeAuroraCopy:
        """Open a copy stream for one ``COPY`` statement.

        Parameters
        ----------
        statement : str
            The ``COPY ... FROM STDIN`` text, recorded so a test can assert the target table
            and column list.

        Returns
        -------
        FakeAuroraCopy
            A stream to be used as a context manager.

        Raises
        ------
        FakeClientContractError
            If the cursor has been closed.
        """
        if self.closed:
            raise FakeClientContractError(f"a copy was opened on a closed cursor: {statement!r}")
        self.connection.database.record_statement(statement, None)
        self.connection.database.copy_statements.append(statement)
        return FakeAuroraCopy(self.connection.database, statement)

    def __enter__(self) -> FakeAuroraCursor:
        """Enter the cursor's context manager.

        Parameters
        ----------
        None
            Operates on this cursor.

        Returns
        -------
        FakeAuroraCursor
            This cursor, so statements can be executed inside the block.

        Raises
        ------
        None
            Entering cannot fail.
        """
        return self

    def __exit__(self, *exc_info: object) -> None:
        """Leave the cursor's context manager, closing it.

        Parameters
        ----------
        *exc_info : object
            The exception type, value and traceback, unused.

        Returns
        -------
        None
            Returning ``None`` lets any exception in the block propagate.

        Raises
        ------
        None
            Closing cannot fail.
        """
        self.closed = True


class FakeAuroraTransaction:
    """Recorder standing in for an explicit transaction block on a connection.

    Purpose
    -------
    Mark a unit of work as committed when its block leaves cleanly and rolled back when it
    leaves by exception, so a test can assert that a multi-table load was one atomic unit
    rather than several.

    Assumptions: leaving by exception must ROLL BACK and must let the exception propagate.
    That pairing is the target of the baseline's explicit rollback-then-abandon path, and a
    double that swallowed the exception would let a loader appear to succeed while its unit of
    work had been abandoned.
    """

    def __init__(self, connection: FakeAuroraConnection) -> None:
        """Bind a transaction block to its connection.

        Parameters
        ----------
        connection : FakeAuroraConnection
            The connection whose commit and rollback counters this block advances.

        Returns
        -------
        None
            Stores the connection.

        Raises
        ------
        None
            Nothing is validated on construction.
        """
        self.connection = connection

    def __enter__(self) -> FakeAuroraTransaction:
        """Enter the transaction block and record that it opened.

        Parameters
        ----------
        None
            Operates on this block.

        Returns
        -------
        FakeAuroraTransaction
            This block.

        Raises
        ------
        None
            Entering cannot fail.
        """
        self.connection.database.transactions_opened += 1
        return self

    def __exit__(self, *exc_info: object) -> None:
        """Leave the transaction block, committing on success and rolling back on error.

        Parameters
        ----------
        *exc_info : object
            The exception type, value and traceback; the first element decides the outcome.

        Returns
        -------
        None
            Returning ``None`` lets any exception propagate, so a failed unit of work stays
            failed.

        Raises
        ------
        None
            Recording the outcome cannot fail.
        """
        if exc_info and exc_info[0] is not None:
            self.connection.rollback()
        else:
            self.connection.commit()


class FakeAuroraConnection:
    """Recorder standing in for one open connection to the Aurora cluster.

    Purpose
    -------
    Hand out cursors and transaction blocks, and count commits, rollbacks and closes, so a
    test can assert both what was executed and the unit of work it was executed in.

    Assumptions: an instance is only ever produced by :meth:`FakeAuroraDatabase.connect`,
    which validates the connection parameters first. Constructing one directly bypasses that
    validation, which is why the connection keeps a reference to the database rather than
    owning the recording itself.
    """

    def __init__(self, database: FakeAuroraDatabase, params: Mapping[str, object]) -> None:
        """Bind a connection to its database and remember its masked parameters.

        Parameters
        ----------
        database : FakeAuroraDatabase
            The database every statement is recorded on.
        params : Mapping[str, object]
            The connection parameters, already masked by
            :meth:`FakeAuroraDatabase.masked_params`.

        Returns
        -------
        None
            Stores both and opens with no commits or rollbacks recorded.

        Raises
        ------
        None
            Validation has already happened in :meth:`FakeAuroraDatabase.connect`.
        """
        self.database = database
        self.params = params
        self.commits = 0
        self.rollbacks = 0
        self.closed = False

    def cursor(self) -> FakeAuroraCursor:
        """Return a new cursor over this connection.

        Parameters
        ----------
        None
            Operates on this connection.

        Returns
        -------
        FakeAuroraCursor
            A cursor to be used directly or as a context manager.

        Raises
        ------
        FakeClientContractError
            If the connection has been closed.
        """
        if self.closed:
            raise FakeClientContractError("a cursor was opened on a closed connection")
        cursor = FakeAuroraCursor(self)
        self.database.cursors.append(cursor)
        return cursor

    def transaction(self) -> FakeAuroraTransaction:
        """Return an explicit transaction block over this connection.

        Parameters
        ----------
        None
            Operates on this connection.

        Returns
        -------
        FakeAuroraTransaction
            A block to be used as a context manager, committing on clean exit and rolling back
            on exception.

        Raises
        ------
        FakeClientContractError
            If the connection has been closed.
        """
        if self.closed:
            raise FakeClientContractError("a transaction was opened on a closed connection")
        return FakeAuroraTransaction(self)

    def commit(self) -> None:
        """Record a commit of the current unit of work.

        Parameters
        ----------
        None
            Operates on this connection.

        Returns
        -------
        None
            Advances this connection's and the database's commit counters.

        Raises
        ------
        FakeClientContractError
            If the connection has been closed.
        """
        if self.closed:
            raise FakeClientContractError("a commit was issued on a closed connection")
        self.commits += 1
        self.database.commits += 1

    def rollback(self) -> None:
        """Record a rollback of the current unit of work.

        Parameters
        ----------
        None
            Operates on this connection.

        Returns
        -------
        None
            Advances this connection's and the database's rollback counters.

        Raises
        ------
        FakeClientContractError
            If the connection has been closed.
        """
        if self.closed:
            raise FakeClientContractError("a rollback was issued on a closed connection")
        self.rollbacks += 1
        self.database.rollbacks += 1

    def close(self) -> None:
        """Close the connection, making every further operation an error.

        Parameters
        ----------
        None
            Operates on this connection.

        Returns
        -------
        None
            Marks the connection closed; closing twice is accepted, as the driver accepts it.

        Raises
        ------
        None
            Closing cannot fail.
        """
        self.closed = True

    def __enter__(self) -> FakeAuroraConnection:
        """Enter the connection's context manager.

        Parameters
        ----------
        None
            Operates on this connection.

        Returns
        -------
        FakeAuroraConnection
            This connection.

        Raises
        ------
        None
            Entering cannot fail.
        """
        return self

    def __exit__(self, *exc_info: object) -> None:
        """Leave the connection's context manager, committing or rolling back accordingly.

        Parameters
        ----------
        *exc_info : object
            The exception type, value and traceback; the first element decides the outcome.

        Returns
        -------
        None
            Returning ``None`` lets any exception propagate.

        Raises
        ------
        None
            Recording the outcome cannot fail.
        """
        # WHY (Assumptions): the driver's connection context manager ends the TRANSACTION and
        # leaves the connection open, so this commits or rolls back without closing. A double
        # that closed here would make a loader reusing one connection across two units of work
        # fail in the double only, which is the least useful kind of difference.
        if exc_info and exc_info[0] is not None:
            self.rollback()
        else:
            self.commit()


class FakeAuroraDatabase:
    """In-process double for the Aurora PostgreSQL client the loaders connect through.

    Purpose
    -------
    Stand in for the whole database boundary: acquire a connection from connection
    PARAMETERS, hand out cursors and transaction blocks, accept ``COPY``-style bulk ingest,
    answer queries with a result set the test arranged, and record every one of those
    interactions so the test can assert on them.

    Alternatives Considered: connecting to a real PostgreSQL instance, or reaching an emulator
    the way the reference AWS helper does. Rejected on two independent grounds. Hermeticity
    first: a live endpoint or a real credential would make this suite unrunnable wherever one
    is absent, and no credential may exist in this repository at all -- so a test that needed
    one could only ever be skipped, and a suite whose verification tests silently skip is
    indistinguishable from one that passed. Second, the reference helper reaches AWS by
    launching an ``awslocal`` SUBPROCESS against a running emulator, which is a reasonable
    design for validating the emulated dataset-staging path and the wrong one here: it makes
    every test depend on an external process being up. An in-process double needs no emulator,
    no daemon and no socket, and it can additionally observe things a real server cannot be
    asked about -- such as which statements were NEVER issued.

    Assumptions: a connection is acquired from PARAMETERS and never from an already-open
    connection, because that is the shape
    :meth:`carddemo_migration.config.AuroraConnectionSettings.as_connection_params` produces
    -- the configuration module returns parameters and opens nothing, deliberately, so that
    importing it pulls in no driver.

    Assumptions: the recorded parameters are MASKED. The mapping a real connect call receives
    holds the credential in clear, and these recordings end up in assertion output whenever a
    test fails, so the password value is replaced with
    :data:`carddemo_migration.config.REDACTED` exactly as that module's own ``__repr__`` does.
    A test can therefore still assert that a credential WAS supplied -- the key is present and
    equal to the redaction marker -- without any mechanism existing here that could print it.
    """

    def __init__(self) -> None:
        """Create an empty database double with nothing recorded and nothing arranged.

        Parameters
        ----------
        None
            Every log starts empty and every result set is arranged by the test.

        Returns
        -------
        None
            Initialises the recording state.

        Raises
        ------
        None
            Construction cannot fail.
        """
        self.connections: list[FakeAuroraConnection] = []
        self.connection_params: list[Mapping[str, object]] = []
        self.cursors: list[FakeAuroraCursor] = []
        self.statements: list[tuple[str, tuple[object, ...] | None]] = []
        self.copy_statements: list[str] = []
        self.copied_rows: list[tuple[str, tuple[object, ...]]] = []
        self.copied_chunks: list[tuple[str, bytes | str]] = []
        self.commits = 0
        self.rollbacks = 0
        self.transactions_opened = 0
        self._arranged_rows: list[tuple[str, tuple[tuple[object, ...], ...]]] = []

    def connect(self, **params: object) -> FakeAuroraConnection:
        """Acquire a connection from connection parameters, validating the TLS keywords.

        Purpose
        -------
        Be the seam a loader's connect call substitutes at, and refuse any parameter set that
        would not have verified the server's certificate.

        Parameters
        ----------
        **params : object
            The connection keywords, as produced by
            :meth:`carddemo_migration.config.AuroraConnectionSettings.as_connection_params`:
            ``host``, ``port``, ``dbname``, ``user``, ``password``, ``sslmode`` and
            ``sslrootcert``.

        Returns
        -------
        FakeAuroraConnection
            A new recording connection, also appended to :attr:`connections`.

        Raises
        ------
        ConfigurationError
            If ``dbname`` is absent, if ``sslmode`` is absent or is not
            :data:`carddemo_migration.config.REQUIRED_SSL_MODE`, or if ``sslrootcert`` is
            absent.
        """
        # WHY (Assumptions): the TLS keywords are checked here because a loader is expected to
        # build its parameters by CALLING ``as_connection_params``, which emits them
        # unconditionally, and hand-rolling the dict is the way that guarantee gets lost. The
        # bootstrap SQL refuses an unencrypted session, so such a loader would fail against the
        # real cluster with a connection error that named neither the missing keyword nor the
        # call it should have made. ``dbname`` is checked alongside them for the reason that
        # module records: the attribute is spelled ``database`` and the keyword is ``dbname``,
        # so passing the fields through by name is a mistake that only a connect call catches.
        if "dbname" not in params:
            raise ConfigurationError(
                "connection parameters name no 'dbname'; the settings attribute is spelled "
                "'database' and only as_connection_params translates it"
            )
        if params.get("sslmode") != REQUIRED_SSL_MODE:
            raise ConfigurationError(
                f"connection parameters must request sslmode={REQUIRED_SSL_MODE!r}, but named "
                f"{params.get('sslmode')!r}; the cluster refuses an unverified session"
            )
        if not params.get("sslrootcert"):
            raise ConfigurationError(
                "connection parameters name no 'sslrootcert', so no trust anchor would verify "
                "the server certificate"
            )
        masked = self.masked_params(params)
        self.connection_params.append(masked)
        connection = FakeAuroraConnection(self, masked)
        self.connections.append(connection)
        return connection

    def masked_params(self, params: Mapping[str, object]) -> Mapping[str, object]:
        """Return connection parameters with the credential replaced by the redaction marker.

        Parameters
        ----------
        params : Mapping[str, object]
            The connection keywords as supplied, credential included.

        Returns
        -------
        Mapping[str, object]
            A read-only copy in which a non-empty ``password`` reads
            :data:`carddemo_migration.config.REDACTED`. Every other key is unchanged, and an
            absent or empty password is left as it was so the difference stays observable.

        Raises
        ------
        None
            Masking cannot fail.
        """
        # WHY (Trade-offs): an empty or absent password is deliberately NOT replaced with the
        # marker. Doing so would make "a credential was supplied" and "no credential was
        # supplied" render identically, and the second is a real defect a test should be able to
        # catch. The accepted cost is that the mapping distinguishes the two states, which is
        # the whole point; the credential's VALUE remains unreachable either way.
        masked = dict(params)
        if masked.get("password"):
            masked["password"] = REDACTED
        return MappingProxyType(masked)

    def arrange_rows(self, statement_fragment: str, rows: Sequence[Sequence[object]]) -> None:
        """Arrange the result set a later query containing a text fragment will return.

        Purpose
        -------
        Let a verification test supply the rows its query is meant to find, keyed by a
        distinctive fragment of the statement rather than by the whole SQL text -- which a
        loader is free to reformat without changing meaning.

        Parameters
        ----------
        statement_fragment : str
            Text that identifies the statement, matched case-insensitively as a substring.
        rows : Sequence[Sequence[object]]
            The rows to return, each a sequence of column values.

        Returns
        -------
        None
            Records the arrangement; the most recently arranged matching fragment wins.

        Raises
        ------
        FakeClientContractError
            If the fragment is blank, or if any column value is a ``float``.
        """
        if not statement_fragment.strip():
            raise FakeClientContractError("a result-set fragment must be non-blank")
        # WHY (Assumptions): arranged rows are float-checked as well as executed parameters,
        # because a verification pass compares what the database returned against what the file
        # held. A float on the arranged side would make an exact-money assertion pass against a
        # value the real NUMERIC column could never produce, which is the one direction of this
        # check that a loader-side guard cannot cover.
        checked = tuple(
            tuple(_reject_float(value, context="an arranged result row") for value in row)
            for row in rows
        )
        self._arranged_rows.append((statement_fragment.casefold(), checked))

    def rows_for(self, statement: str) -> tuple[tuple[object, ...], ...]:
        """Return the rows arranged for a statement, or none when nothing matches.

        Parameters
        ----------
        statement : str
            The SQL text being executed.

        Returns
        -------
        tuple[tuple[object, ...], ...]
            The rows of the most recently arranged fragment contained in the statement, or an
            empty tuple when no arrangement matches.

        Raises
        ------
        None
            An unmatched statement yields no rows, because a query that legitimately finds
            nothing must be expressible.
        """
        folded = statement.casefold()
        for fragment, rows in reversed(self._arranged_rows):
            if fragment in folded:
                return rows
        return ()

    def record_statement(self, statement: str, params: tuple[object, ...] | None) -> None:
        """Record one executed statement and its bound parameters.

        Parameters
        ----------
        statement : str
            The SQL text, kept verbatim.
        params : tuple[object, ...] | None
            The bound parameters, or ``None`` for a statement with none.

        Returns
        -------
        None
            Appends to :attr:`statements`.

        Raises
        ------
        None
            Recording cannot fail; parameter validation happened at the cursor.
        """
        self.statements.append((statement, params))

    def executed_sql(self) -> tuple[str, ...]:
        """Return every executed statement's text, in execution order.

        Parameters
        ----------
        None
            Reads :attr:`statements`.

        Returns
        -------
        tuple[str, ...]
            The SQL texts, including the ``COPY`` statements that opened a stream.

        Raises
        ------
        None
            Reading the log cannot fail.
        """
        return tuple(statement for statement, _ in self.statements)

    def forbidden_statements(self) -> tuple[tuple[str, str], ...]:
        """Return every executed statement that breaches the loader privilege contract.

        Purpose
        -------
        Make the NEGATIVE half of the contract observable. A loader connects as a
        ``carddemo_<context>`` login role that holds no DDL, no ownership and no delete
        privilege, so schema and role creation, grants, revocations, deletes, truncations and
        index creation are all statements it must never issue.

        Parameters
        ----------
        None
            Reads :attr:`statements` and :data:`FORBIDDEN_LOADER_STATEMENTS`.

        Returns
        -------
        tuple[tuple[str, str], ...]
            One ``(label, statement)`` pair per breach, in execution order, where the label is
            the key from :data:`FORBIDDEN_LOADER_STATEMENTS`. Empty when the contract holds.

        Raises
        ------
        None
            Reporting a breach is not itself an error; see the rationale below.
        """
        # WHY (Trade-offs): a breach is REPORTED rather than raised at execution time. Raising
        # would abort the loader from inside the double, and the traceback would point at this
        # module rather than at the statement's author; a test asserting that this tuple is
        # empty fails with the label and the offending SQL in the message, which is where a
        # reader can act on it. The accepted cost is that the loader runs to completion in the
        # test even after a breach, which is harmless because nothing here reaches a database.
        found: list[tuple[str, str]] = []
        for statement, _ in self.statements:
            for label, pattern in FORBIDDEN_LOADER_STATEMENTS.items():
                if pattern.search(statement):
                    found.append((label, statement))
        return tuple(found)


class FakeObjectStorePaginator:
    """Paginator standing in for the object store's paginated list operations.

    Purpose
    -------
    Serve ``list_objects_v2`` and ``list_object_versions`` pages DERIVED from what the store
    actually holds, so a generation staged through :class:`FakeObjectStore` is immediately
    discoverable by a listing -- which is what lets a test exercise "find the highest existing
    generation prefix" end to end.

    Alternatives Considered: pre-arranging fixed pages per operation and prefix, as the
    sibling staging test does for its own narrow assertions. Rejected for a shared double
    because arranged pages and stored objects can disagree: a retention test would then be
    asserting against the page list it wrote itself rather than against the effect of the
    writes and deletes under test, and a cleanup that deleted the wrong prefix would still
    report the arranged listing.
    """

    def __init__(self, store: FakeObjectStore, operation_name: str) -> None:
        """Bind a paginator to its store and list operation.

        Parameters
        ----------
        store : FakeObjectStore
            The store whose contents the pages are derived from.
        operation_name : str
            Either ``"list_objects_v2"`` or ``"list_object_versions"``.

        Returns
        -------
        None
            Stores both for the duration of the paginator.

        Raises
        ------
        FakeClientContractError
            If the operation is neither of the two the staging loader paginates.
        """
        if operation_name not in {"list_objects_v2", "list_object_versions"}:
            raise FakeClientContractError(
                f"no paginator is offered for {operation_name!r}; the staging loader paginates "
                "list_objects_v2 and list_object_versions only"
            )
        self.store = store
        self.operation_name = operation_name

    def paginate(self, **kwargs: Any) -> list[dict[str, Any]]:
        """Return the response pages for one list call.

        Parameters
        ----------
        **kwargs : Any
            The list arguments: ``Bucket`` and optionally ``Prefix`` and ``Delimiter``.

        Returns
        -------
        list[dict[str, Any]]
            One or more page mappings. A ``list_objects_v2`` page carries ``CommonPrefixes``
            and ``Contents``; a ``list_object_versions`` page carries ``Versions`` and
            ``DeleteMarkers``. Always at least one page, empty when nothing matches, matching
            the service's behaviour of returning a page with no collections rather than none.

        Raises
        ------
        FakeClientContractError
            If ``Bucket`` is absent.
        """
        bucket = kwargs.get("Bucket")
        if not bucket:
            raise FakeClientContractError(f"a {self.operation_name} call named no Bucket")
        prefix = str(kwargs.get("Prefix", ""))
        self.store.list_calls.append((self.operation_name, str(bucket), prefix))
        if self.operation_name == "list_objects_v2":
            return self._object_pages(str(bucket), prefix, kwargs.get("Delimiter"))
        return self._version_pages(str(bucket), prefix)

    def _object_pages(self, bucket: str, prefix: str, delimiter: object) -> list[dict[str, Any]]:
        """Build the ``list_objects_v2`` pages for one bucket and prefix.

        Parameters
        ----------
        bucket : str
            The bucket being listed.
        prefix : str
            The key prefix being listed under.
        delimiter : object
            The delimiter, or ``None`` for a flat listing.

        Returns
        -------
        list[dict[str, Any]]
            Pages carrying ``CommonPrefixes`` for the immediate child folders and ``Contents``
            for the keys that lie directly beneath the prefix.

        Raises
        ------
        None
            An unmatched prefix yields one empty page.
        """
        # WHY (Assumptions): the delimiter collapses everything below the first separator into
        # ONE common prefix, and that rollup is the whole mechanism generation discovery relies
        # on -- the staging loader lists the family prefix to learn the ``dt=`` folders, then
        # lists each of those to learn the ``gen=`` folders. A double that returned raw keys
        # instead would make the loader see no folders at all and quietly find no generation to
        # scratch, so the retention contract would appear to hold on an empty result.
        common: set[str] = set()
        contents: list[dict[str, Any]] = []
        for key in self.store.keys(bucket):
            if not key.startswith(prefix):
                continue
            remainder = key[len(prefix) :]
            if delimiter and str(delimiter) in remainder:
                head = remainder.split(str(delimiter), 1)[0]
                common.add(f"{prefix}{head}{delimiter}")
            else:
                contents.append({"Key": key, "Size": len(self.store.body_of(key, bucket=bucket))})
        prefix_items = [{"Prefix": value} for value in sorted(common)]
        return self._paged({"CommonPrefixes": prefix_items, "Contents": contents})

    def _version_pages(self, bucket: str, prefix: str) -> list[dict[str, Any]]:
        """Build the ``list_object_versions`` pages for one bucket and prefix.

        Parameters
        ----------
        bucket : str
            The bucket being listed.
        prefix : str
            The key prefix being listed under.

        Returns
        -------
        list[dict[str, Any]]
            Pages carrying ``Versions`` for every stored version and ``DeleteMarkers`` for
            every marker, each item a ``Key`` and ``VersionId`` pair.

        Raises
        ------
        None
            An unmatched prefix yields one empty page.
        """
        # WHY (Assumptions): delete markers are reported alongside versions because the bucket
        # this stands in for is VERSIONED, and permanent cleanup has to name both. A marker left
        # behind keeps the key present in a version listing forever, so a double that omitted
        # markers would report a prefix as fully scratched while the real bucket still held it.
        versions = [
            {"Key": key, "VersionId": version_id}
            for key, version_id in self.store.stored_versions(bucket)
            if key.startswith(prefix)
        ]
        markers = [
            {"Key": key, "VersionId": version_id}
            for key, version_id in self.store.delete_markers(bucket)
            if key.startswith(prefix)
        ]
        return self._paged({"Versions": versions, "DeleteMarkers": markers})

    def _paged(self, collections: Mapping[str, list[dict[str, Any]]]) -> list[dict[str, Any]]:
        """Split response collections into pages of the store's configured page size.

        Parameters
        ----------
        collections : Mapping[str, list[dict[str, Any]]]
            The response key names mapped to their full item lists.

        Returns
        -------
        list[dict[str, Any]]
            Pages, each carrying at most ``store.page_size`` items per collection and omitting
            a collection that has no items left. Never empty: an all-empty result yields a
            single page with no collections.

        Raises
        ------
        None
            Paging cannot fail.
        """
        # WHY (Assumptions): the result is split across SEVERAL pages even when it would fit in
        # one, because a caller that reads only the first page is a real and silent defect --
        # it would scratch only the oldest few generations and report success. Serving one page
        # would make that defect invisible in every test. The size is small and configurable so
        # a test can force a multi-page walk with two staged generations.
        remaining = {name: list(items) for name, items in collections.items() if items}
        pages: list[dict[str, Any]] = []
        while remaining:
            page: dict[str, Any] = {}
            for name in sorted(remaining):
                page[name] = remaining[name][: self.store.page_size]
                del remaining[name][: self.store.page_size]
            remaining = {name: items for name, items in remaining.items() if items}
            pages.append(page)
        return pages or [{}]


class FakeObjectStore:
    """In-process double for the versioned object store dataset generations are staged into.

    Purpose
    -------
    Stand in for the whole staging boundary: accept a put of VERBATIM bytes, serve paginated
    prefix listings derived from what is actually held, and permanently delete explicitly
    named versions -- while recording every key, body and call so a test can assert the
    ``<domain>/<dataset>/dt=YYYY-MM-DD/gen=NNNN/`` convention with the generation zero-padded
    to four digits.

    Alternatives Considered: pointing a real client at an emulator. Rejected for the same
    reasons as the database double, and one more that is specific to this seam: the staging
    loader takes its client as an ARGUMENT and the configuration module builds one from the
    environment with no literal endpoint anywhere, so substituting an in-process object at that
    seam needs nothing mocked, patched or redirected. Reaching an emulator through a subprocess
    shim -- the reference helper's approach, correct for validating that emulated path -- would
    add a running daemon to the prerequisites of every test here in exchange for nothing this
    double cannot observe.

    Assumptions: bodies are stored and returned as the exact bytes handed in. A staged
    generation may be a packed-decimal or EBCDIC image, so any normalisation -- a text decode, a
    line-ending fix, a strip -- would corrupt it, and the corruption would be invisible in a
    byte count.

    Assumptions: the store is VERSIONED, so a second put of one key adds a version rather than
    replacing it, and a delete that names no version identifier writes a delete marker instead
    of removing anything. Both behaviours matter to what is being tested: distinct ``gen=``
    prefixes are distinct KEYS rather than versions of one key, so generation retention cannot
    be delegated to a lifecycle rule and has to delete every version and marker explicitly.

    Assumptions: no dataset family is enumerated here, and that is deliberate rather than an
    omission. There are TEN generation families -- six defined in ``app/jcl/DEFGDGB.jcl``, three
    in ``app/jcl/DEFGDGD.jcl`` and one in ``app/jcl/DALYREJS.jcl``, every one at a
    five-generation scratch limit -- and which of them exist is a property of the batch pipeline
    and its Terraform retention input, not of this double. Encoding a list here would give that
    fact a second home and let a double built for six silently constrain a pipeline that has
    ten; the configuration module's prefix builder refuses to enumerate them for the same
    reason.
    """

    def __init__(self, *, page_size: int = 2) -> None:
        """Create an empty versioned store with nothing held and nothing recorded.

        Parameters
        ----------
        page_size : int
            Maximum number of items each list page carries. Small by default so a listing
            spans several pages, which is what exposes a caller that reads only the first.

        Returns
        -------
        None
            Initialises the object state and the call logs.

        Raises
        ------
        FakeClientContractError
            If ``page_size`` is not a positive integer.
        """
        if isinstance(page_size, bool) or not isinstance(page_size, int) or page_size < 1:
            raise FakeClientContractError(
                f"page_size must be a positive integer, not {page_size!r}"
            )
        self.page_size = page_size
        self.put_calls: list[Mapping[str, Any]] = []
        self.delete_calls: list[Mapping[str, Any]] = []
        self.list_calls: list[tuple[str, str, str]] = []
        self._objects: dict[tuple[str, str], list[tuple[str, bytes]]] = {}
        self._markers: dict[tuple[str, str], list[str]] = {}
        self._delete_errors: list[dict[str, str]] = []
        self._next_version = 0

    def put_object(self, **kwargs: Any) -> dict[str, str]:
        """Store one object version, keeping its body byte for byte.

        Parameters
        ----------
        **kwargs : Any
            The put arguments: ``Bucket``, ``Key``, ``Body`` and any others the caller passes,
            all of which are recorded.

        Returns
        -------
        dict[str, str]
            A minimal success response carrying the new ``VersionId`` and an ``ETag``.

        Raises
        ------
        FakeClientContractError
            If ``Bucket`` or ``Key`` is absent, or if ``Body`` is not ``bytes``.
        """
        bucket = kwargs.get("Bucket")
        key = kwargs.get("Key")
        body = kwargs.get("Body")
        if not bucket or not key:
            raise FakeClientContractError("a put_object call named no Bucket or no Key")
        # WHY (Assumptions): a ``str`` body is refused rather than encoded on the caller's
        # behalf. Encoding it here would pick a code page this double has no business choosing,
        # and for an EBCDIC or packed generation any choice is wrong -- the staging loader already
        # requires bytes for exactly this reason, so accepting text would let a defect through
        # the double that the real path rejects.
        if not isinstance(body, bytes):
            raise FakeClientContractError(
                f"a staged body must be bytes, but {key!r} was given "
                f"{type(body).__name__}; a text body has no code page this double may choose"
            )
        self.put_calls.append(MappingProxyType(dict(kwargs)))
        self._next_version += 1
        version_id = f"v{self._next_version:04d}"
        self._objects.setdefault((str(bucket), str(key)), []).append((version_id, body))
        return {"VersionId": version_id, "ETag": f'"{version_id}"'}

    def delete_objects(self, **kwargs: Any) -> dict[str, list[dict[str, str]]]:
        """Delete the named object versions and report any arranged per-object errors.

        Parameters
        ----------
        **kwargs : Any
            The delete arguments: ``Bucket`` and ``Delete``, where ``Delete["Objects"]`` is a
            list of ``Key`` and optional ``VersionId`` mappings.

        Returns
        -------
        dict[str, list[dict[str, str]]]
            A response carrying ``Errors``, which holds whatever
            :meth:`fail_next_delete_with` arranged and is otherwise empty.

        Raises
        ------
        FakeClientContractError
            If ``Bucket`` is absent, if ``Delete`` names no ``Objects``, or if more objects are
            named than the service accepts in one call.
        """
        bucket = kwargs.get("Bucket")
        delete = kwargs.get("Delete") or {}
        objects = delete.get("Objects")
        if not bucket:
            raise FakeClientContractError("a delete_objects call named no Bucket")
        if not objects:
            raise FakeClientContractError("a delete_objects call named no Delete['Objects']")
        # WHY (Assumptions): the batch ceiling is asserted because the service enforces it and
        # the staging loader batches against it. A double that accepted an over-long batch would
        # let a loader whose chunking was off by one pass here and fail on the first dataset
        # large enough to exceed it, which is the run least likely to be observed closely.
        if len(objects) > _MAX_DELETE_BATCH:
            raise FakeClientContractError(
                f"a delete_objects call named {len(objects)} objects, more than the "
                f"{_MAX_DELETE_BATCH} the service accepts in one request"
            )
        self.delete_calls.append(MappingProxyType(dict(kwargs)))
        for item in objects:
            self._delete_one(str(bucket), str(item.get("Key", "")), item.get("VersionId"))
        errors = list(self._delete_errors)
        self._delete_errors.clear()
        return {"Errors": errors}

    def get_paginator(self, operation_name: str) -> FakeObjectStorePaginator:
        """Return a paginator bound to one list operation.

        Parameters
        ----------
        operation_name : str
            Either ``"list_objects_v2"`` or ``"list_object_versions"``.

        Returns
        -------
        FakeObjectStorePaginator
            A paginator whose pages are derived from what this store holds.

        Raises
        ------
        FakeClientContractError
            If the operation is not one the staging loader paginates.
        """
        return FakeObjectStorePaginator(self, operation_name)

    def fail_next_delete_with(self, code: str, key: str = "") -> None:
        """Arrange a per-object error for the next delete call.

        Purpose
        -------
        Let a test exercise the partial-failure path, which the staging loader must treat as a
        hard failure -- a cleanup that deleted some versions and reported success would leave a
        generation half-scratched.

        Parameters
        ----------
        code : str
            The service error code to report, for example ``"AccessDenied"``.
        key : str
            The key the error is attributed to. Empty by default, since the loader reports the
            codes rather than the keys.

        Returns
        -------
        None
            The arrangement is consumed by the next :meth:`delete_objects` call.

        Raises
        ------
        FakeClientContractError
            If the code is blank.
        """
        if not code.strip():
            raise FakeClientContractError("an arranged delete error must name a non-blank code")
        self._delete_errors.append({"Key": key, "Code": code})

    def keys(self, bucket: str | None = None) -> tuple[str, ...]:
        """Return every key that currently holds at least one version.

        Parameters
        ----------
        bucket : str | None
            Restrict the result to one bucket, or ``None`` to span every bucket held.

        Returns
        -------
        tuple[str, ...]
            The sorted keys. A key whose every version has been deleted is absent, even when a
            delete marker for it remains.

        Raises
        ------
        None
            An unknown bucket yields an empty tuple.
        """
        return tuple(
            sorted(
                key
                for (held_bucket, key), versions in self._objects.items()
                if versions and (bucket is None or held_bucket == bucket)
            )
        )

    def body_of(self, key: str, *, bucket: str | None = None) -> bytes:
        """Return the newest stored body for one key, byte for byte.

        Parameters
        ----------
        key : str
            The object key.
        bucket : str | None
            The bucket to look in, or ``None`` to accept the only bucket holding the key.

        Returns
        -------
        bytes
            The most recently put body, exactly as it was handed in.

        Raises
        ------
        FakeClientContractError
            If the key holds no version in the bucket, or if ``bucket`` is ``None`` and more
            than one bucket holds it.
        """
        matches = [
            (held_bucket, versions)
            for (held_bucket, held_key), versions in self._objects.items()
            if held_key == key and versions and (bucket is None or held_bucket == bucket)
        ]
        if not matches:
            raise FakeClientContractError(f"no stored object holds the key {key!r}")
        if len(matches) > 1:
            raise FakeClientContractError(
                f"the key {key!r} is held in {len(matches)} buckets; name one explicitly"
            )
        return matches[0][1][-1][1]

    def stored_versions(self, bucket: str) -> tuple[tuple[str, str], ...]:
        """Return every live key and version identifier held in one bucket.

        Parameters
        ----------
        bucket : str
            The bucket to enumerate.

        Returns
        -------
        tuple[tuple[str, str], ...]
            Sorted ``(key, version_id)`` pairs, newest version last within each key.

        Raises
        ------
        None
            An unknown bucket yields an empty tuple.
        """
        return tuple(
            sorted(
                (key, version_id)
                for (held_bucket, key), versions in self._objects.items()
                if held_bucket == bucket
                for version_id, _ in versions
            )
        )

    def delete_markers(self, bucket: str) -> tuple[tuple[str, str], ...]:
        """Return every delete marker held in one bucket.

        Parameters
        ----------
        bucket : str
            The bucket to enumerate.

        Returns
        -------
        tuple[tuple[str, str], ...]
            Sorted ``(key, version_id)`` pairs, one per marker.

        Raises
        ------
        None
            An unknown bucket yields an empty tuple.
        """
        return tuple(
            sorted(
                (key, version_id)
                for (held_bucket, key), markers in self._markers.items()
                if held_bucket == bucket
                for version_id in markers
            )
        )

    def _delete_one(self, bucket: str, key: str, version_id: object) -> None:
        """Remove one named version, or write a delete marker when none was named.

        Parameters
        ----------
        bucket : str
            The bucket the object is held in.
        key : str
            The object key.
        version_id : object
            The version identifier to remove, or ``None`` to write a delete marker instead.

        Returns
        -------
        None
            Mutates the store in place. Removing a version that is not held is accepted
            silently, as the service accepts it.

        Raises
        ------
        None
            A key or version that is not held is not an error; the service reports such a
            delete as successful.
        """
        # WHY (Assumptions): a delete with NO version identifier writes a marker rather than
        # removing anything, because that is what a versioned bucket does, and reproducing it is
        # the point. A loader that omitted the identifier would believe it had scratched a
        # generation while every version remained recoverable; because this double keeps the
        # versions and adds a marker, the following version listing still reports them and the
        # test fails where the real bucket would have silently retained them.
        if version_id is None:
            marker_id = f"marker{len(self._markers) + 1:04d}"
            self._markers.setdefault((bucket, key), []).append(marker_id)
            return
        held = self._objects.get((bucket, key))
        if held is not None:
            self._objects[bucket, key] = [entry for entry in held if entry[0] != str(version_id)]
        markers = self._markers.get((bucket, key))
        if markers is not None and str(version_id) in markers:
            markers.remove(str(version_id))


@pytest.fixture(scope="session")
def repo_root() -> Path:
    """Return the validated CardDemo repository root.

    Purpose
    -------
    Provide the single anchor the two corpus fixtures are derived from, resolved from this
    file's own on-disk position and proven to be the CardDemo tree before it is handed over.

    Parameters
    ----------
    None
        The anchor comes from :data:`_REPO_ROOT`.

    Returns
    -------
    pathlib.Path
        The resolved, absolute, symlink-free repository root, guaranteed to contain every
        directory in :data:`_REPO_MARKERS`.

    Raises
    ------
    Failed
        Via :func:`pytest.fail`, from :func:`_validate_repo_root`, if a marker directory is
        absent.
    """
    # WHY (Assumptions): session scope is correct because the answer is a constant of the
    # checkout -- the validation reads three directory entries and cannot change during a run,
    # so repeating it per test would buy nothing. All validation lives in the helper rather
    # than in this body so a test can exercise the containment contract directly.
    return _validate_repo_root()


@pytest.fixture(scope="session")
def fixture_corpus(repo_root: Path) -> FixtureCorpus:
    """Return a read-only accessor over the scenario corpus under ``tests/fixtures``.

    Parameters
    ----------
    repo_root : pathlib.Path
        The validated repository root, supplying the corpus's parent directory.

    Returns
    -------
    FixtureCorpus
        An accessor bound to ``<repo_root>/tests/fixtures``, offering the text, record and
        raw-byte forms of any dataset in any scenario. It opens files for reading only and
        never writes, copies or normalises one.

    Raises
    ------
    Failed
        Via :func:`pytest.fail`, propagated from the ``repo_root`` fixture.
    """
    # WHY (Assumptions): the corpus is read IN PLACE from the reference tree, at its committed
    # path, rather than being copied into a temporary directory first. Reading in place is what
    # makes an accidental write impossible to hide -- there is no staging copy whose divergence
    # from the original could go unnoticed -- and it is why every accessor uses a read-only open.
    return FixtureCorpus(repo_root / "tests" / "fixtures")


@pytest.fixture(scope="session")
def seed_corpus(repo_root: Path) -> SeedCorpus:
    """Return a read-only accessor over the committed seed datasets under ``app/data``.

    Parameters
    ----------
    repo_root : pathlib.Path
        The validated repository root, supplying both seed directories' parent.

    Returns
    -------
    SeedCorpus
        An accessor bound to ``<repo_root>/app/data/ASCII`` and ``<repo_root>/app/data/EBCDIC``,
        offering the text and padded-record forms of the ASCII tree and the byte and
        byte-record forms of the EBCDIC tree.

    Raises
    ------
    Failed
        Via :func:`pytest.fail`, propagated from the ``repo_root`` fixture.
    """
    data_root = repo_root / "app" / "data"
    return SeedCorpus(data_root / "ASCII", data_root / "EBCDIC")


@pytest.fixture
def workspace(tmp_path: Path) -> Iterator[Path]:
    """Yield a scratch directory outside the repository, removed when the test ends.

    Purpose
    -------
    Give a test somewhere to write -- a staged extract, a decoded output, a generated manifest
    -- without any of it landing in the checkout.

    Parameters
    ----------
    tmp_path : pathlib.Path
        Pytest's per-test temporary directory, which already lives outside the repository tree.

    Yields
    ------
    pathlib.Path
        An existing, empty ``etl`` directory beneath ``tmp_path``, writable by the test and
        guaranteed not to be inside the repository. The tree is removed on teardown whatever
        the test's outcome.

    Raises
    ------
    OSError
        Propagated from directory creation if the temporary root cannot be written, which would
        mean the run had no usable scratch space at all.
    """
    # WHY (Assumptions): the workspace is built on ``tmp_path`` precisely because that directory
    # is already OUTSIDE the checkout. Nothing this suite writes may land under ``app/**``,
    # ``tests/**`` or ``data-migration/**``: the first two are reference trees that must stay
    # byte-identical for the COBOL parity oracle to keep serving as the oracle, and a stray file
    # under the third would be an untracked artifact that a later commit could pick up. A
    # subdirectory rather than ``tmp_path`` itself, so a test can tell what it created from what
    # pytest did.
    root = tmp_path / "etl"
    root.mkdir()
    yield root
    # WHY (Trade-offs): the tree is removed explicitly even though pytest retires its temporary
    # directories on its own, and errors are ignored while doing so. Pytest keeps the last few
    # runs' directories by design, so a staged dataset image can outlive the test that wrote it
    # by several runs; removing it here bounds that. Ignoring errors is deliberate, because a
    # teardown that raised would replace a test's real result with a cleanup failure, and the
    # accepted cost is that an undeletable file is left for pytest's own retirement to collect.
    shutil.rmtree(root, ignore_errors=True)


@pytest.fixture(scope="session")
def secuser_builder() -> SecUserRecordBuilder:
    """Return a builder for synthetic ``CSUSR01Y`` security records.

    Parameters
    ----------
    None
        The builder resolves its geometry from the registry's ``SECUSER`` descriptor.

    Returns
    -------
    SecUserRecordBuilder
        A builder whose ``build`` and ``build_bytes`` methods assemble one eighty-byte record
        with the password slot holding :data:`SYNTHETIC_PASSWORD_FILL`. Session-scoped because
        the builder is stateless.

    Raises
    ------
    FakeClientContractError
        Propagated from the builder if the descriptor declares a non-character field.
    """
    return SecUserRecordBuilder()


@pytest.fixture
def fake_aurora() -> FakeAuroraDatabase:
    """Return a fresh in-process double for the Aurora client.

    Parameters
    ----------
    None
        The double starts with nothing recorded and no result set arranged.

    Returns
    -------
    FakeAuroraDatabase
        A recording database double. Its ``connect`` accepts connection parameters, its
        connections yield cursors and transaction blocks, and every statement, copied row,
        commit and rollback is recorded. Function-scoped, so no test can observe another's
        statements.

    Raises
    ------
    None
        Construction records nothing and validates nothing.
    """
    # WHY (Assumptions): function scope is a correctness requirement rather than a default. The
    # double's whole value is the statement log, and a session-scoped instance would let one
    # test's statements satisfy another's assertion that a forbidden one was never issued --
    # turning the privilege contract into a check on execution order.
    return FakeAuroraDatabase()


@pytest.fixture
def fake_object_store() -> FakeObjectStore:
    """Return a fresh in-process double for the versioned dataset object store.

    Parameters
    ----------
    None
        The store starts empty, with the default small page size so listings span several pages.

    Returns
    -------
    FakeObjectStore
        A recording store double satisfying the staging client surface -- ``put_object``,
        ``delete_objects`` and ``get_paginator`` -- with every key, verbatim body and call
        recorded. Function-scoped for the same reason as the database double.

    Raises
    ------
    None
        Construction validates only its own default page size, which is valid.
    """
    return FakeObjectStore()


@pytest.fixture
def staging_settings() -> DatasetStagingSettings:
    """Return dataset-staging settings built from clearly synthetic, non-secret values.

    Parameters
    ----------
    None
        The values are literals chosen to be recognisably synthetic.

    Returns
    -------
    DatasetStagingSettings
        Validated settings naming a synthetic bucket and the ``test`` environment, whose
        ``generation_prefix`` produces the real
        ``<domain>/<dataset>/dt=YYYY-MM-DD/gen=NNNN/`` convention with the generation
        zero-padded to four digits.

    Raises
    ------
    ConfigurationError
        Propagated from the settings class if either value were blank, which these are not.
    """
    # WHY (Assumptions): neither field is a credential -- a bucket name and an environment name
    # are exactly what an operator needs to read in a staging log line -- which is why this class
    # keeps its generated repr where the connection settings mask theirs. The values are still
    # written to be unmistakably synthetic so no reader can mistake them for a deployed bucket,
    # and nothing here resolves a real one: the resolver that would read Parameter Store is never
    # called, so this suite needs no region, no credential and no network.
    return DatasetStagingSettings(bucket="carddemo-datasets-synthetic", environment="test")


@pytest.fixture
def aurora_settings() -> AuroraConnectionSettings:
    """Return Aurora connection settings built from clearly synthetic, non-secret values.

    Purpose
    -------
    Supply a valid settings object a loader test can pass through
    ``as_connection_params`` into :meth:`FakeAuroraDatabase.connect`, so the parameter
    translation and the TLS keywords are exercised rather than hand-written.

    Parameters
    ----------
    None
        The values are literals; nothing is resolved from the environment.

    Returns
    -------
    AuroraConnectionSettings
        Validated settings for the ``auth`` schema's login role against an unresolvable host,
        carrying a placeholder credential. Its ``__repr__`` masks that credential, and
        ``as_connection_params`` emits ``dbname``, ``sslmode`` and ``sslrootcert``.

    Raises
    ------
    ConfigurationError
        Propagated from the settings class if any value were blank or the port out of range,
        which none is.
    """
    # WHY (Assumptions): the host is in the reserved ``.invalid`` top-level domain and the trust
    # anchor names a path that does not exist, so this object cannot reach anything even if a
    # future test handed it to a real driver by mistake -- name resolution fails before a socket
    # is opened. The settings class performs no filesystem check on the anchor, which is what
    # makes a non-existent path admissible here and keeps the fixture hermetic.
    # WHY (Assumptions): the role name is taken from the eight-entry schema-to-role map rather
    # than written out, so it stays the role V0 actually creates for the ``auth`` context.
    return AuroraConnectionSettings(
        host="aurora.carddemo.invalid",
        port=5432,
        database="carddemo",
        user=SCHEMA_ROLES["auth"],
        password=SYNTHETIC_PASSWORD_FILL,
        ssl_root_cert="/nonexistent/synthetic-test-anchor.pem",
    )
