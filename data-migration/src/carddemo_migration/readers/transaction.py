"""Streaming reader for the CardDemo transaction master, which ships no extract in either encoding.

Purpose
-------
Turn a transaction master extract into decoded records the Aurora loaders and the verification
passes can consume, one record at a time. TWO ENCODINGS ARE SUPPORTED -- the line-oriented ASCII
text form and the fixed-length EBCDIC dataset form -- and NEITHER IS SHIPPED: unlike its eleven
siblings this record has no committed extract in ``app/data``, because the transaction master is
written by the posting pipeline rather than loaded from an initial image. Both entry points are
published anyway, and both yield the same decoded shape, because the extracts this reader is
actually pointed at arrive in both forms: a committed test fixture arrives as text and a staged
backup or combined generation arrives as a fixed-length byte image. A caller can therefore compare
one form against the other without adapting to a second contract.

Refactoring Rationale: this paragraph used to open with "in both shipped encodings" and describe
"either of the two forms the baseline ships", which contradicted the section further down that
correctly records that no extract ships at all. The distinction the prose was missing is between a
SUPPORTED FORMAT and a SHIPPED INPUT: this reader supports two formats and is shipped zero inputs.
The corpus status is now stated once, here, and the sections below refer to it rather than restating
it, because two statements of one fact are how the contradiction arose.

This module follows the contract ``carddemo_migration.readers.account`` established and differs
from it only in which record descriptor it names. Where the reasoning behind a step is identical
to that module's it is referenced rather than restated, because two copies of one justification
drift apart and then one of them is wrong; where this record differs, the difference is recorded
here at the point it matters.

Parameters
----------
None
    A module takes no argument. Every published callable states its own parameters at its own
    definition, and this module reads no argument, option or environment variable while being
    imported.

Returns
-------
None
    Importing binds names only: :data:`LOADED_FIELDS` and :data:`DROPPED_FIELD_NAMES` are derived
    from the record descriptor, and nothing else is computed. No dataset is opened, no database
    connection is made, no environment variable is read and no network is reached at import time,
    so importing this module is safe on a bare checkout with no credential configured.

Raises
------
LayoutError
    At import, from ``carddemo_migration.copybook.layouts``, which this module imports for its
    record descriptor: that module validates every declared layout's geometry and both disclosure
    allowlists as it loads, and refuses to import if any of them disagree. The failure belongs to
    that module and is neither caught nor re-worded here. At call time, from the two masked
    renderings, if ``CARDDEMO_MASK_HMAC_KEY`` is set to material this package refuses -- anything
    that is not canonical base64 of at least thirty-two distinct-valued bytes -- because a
    guessable key returns the redaction tag to the confirmable digest it replaced. Leaving the
    variable unset is supported and is not an error.

What this module reads
---------------------
The record is ``TRAN-RECORD`` as declared in ``app/cpy/CVTRA05Y.cpy``, and its byte geometry is
resolved exclusively through ``TRAN_LAYOUT`` in ``carddemo_migration.copybook.layouts``.
The absence of a committed extract, stated in the Purpose above, is a property of the baseline
rather than a gap here: ``app/data`` holds the DAILY transactions this master consumes and not the
master it writes. The corpora this reader is exercised against are therefore the committed fixtures
under ``tests/fixtures/export`` and, at run time, the backup and combined generations the batch
chain stages to object storage.

An absent source is a NORMAL state, not a failure -- and it is the ONLY normal one
----------------------------------------------------------------------------------
This is the one reader whose input may legitimately not exist, and it is the property that
distinguishes it from its eleven siblings. Because no extract ships, the two file-taking entry
points treat a GENUINELY ABSENT path exactly as they treat an EMPTY one: they yield zero records and
raise nothing. :data:`HAS_COMMITTED_SEED_DATASET` states the fact.

Nothing else is treated as absence. A path that EXISTS and is not a readable regular file -- a
directory, a dangling symbolic link, a named pipe, a device, or a name whose parent denies traversal
-- is a caller mistake and is reported as one rather than loaded as zero rows. Both entry points get
that behaviour from a single hardened open, performed by ``carddemo_migration.readers.source``, that
decides presence and reads the bytes through the SAME descriptor; :func:`seed_dataset_is_present`
answers the same question for a caller that wants to ask before it starts, and delegates to the same
implementation so the two cannot disagree.

Refactoring Rationale: presence used to be decided by ``Path.is_file()`` in a check performed BEFORE
the open, and that shape had two independent faults. ``is_file()`` is false for a directory, a
dangling link, an unreadable parent and a device, so each of those produced a successful
zero-record load; and because the check and the open were separate operations, a name that passed
the check could be replaced before the open. Both are closed by making the open the only place
presence is decided.
The fault mattered more here than it would in any sibling: this is precisely the record whose
verification baseline is NULL rather than a count, so a silent zero-row load had no downstream check
left to catch it.

Assumptions: ``data-migration/sql/verify/row_counts.sql`` already encodes the same fact from the
other end. Its baseline for ``ledger.transactions`` is ``NULL`` rather than zero, on the stated
grounds that the table is filled by the posting job, so "a correct fresh load cannot read as a
failure". A reader that raised on the missing input would contradict the verification pass that
is meant to check it, and the contradiction would surface as a failed migration rather than as
the disagreement it actually is.

Assumptions: nothing is SYNTHESISED to stand in for the absent extract. The baseline's own
primer record is available -- ``app/jcl/TRANFILE.jcl`` copies it at L67-L74 -- and emitting it,
or any zero-filled placeholder, is rejected in :func:`seed_dataset_is_present`. It would insert
a row the source does not contain, which the row-count and money-total passes would then
correctly report as a parity failure.

The two-timestamp asymmetry
---------------------------
``TRAN-ORIG-TS`` at offset 278 and ``TRAN-PROC-TS`` at offset 304 are the same width and the
same regime, and they must NOT be treated alike. The originating stamp is deterministic business
data copied from the input transaction; the processing stamp is written from the run clock by the
posting program and is the only value in this record that differs between two runs over identical
data. ``data-migration/README.md`` section 10.1 fixes that as the contract for a checksum, which
must exclude the second and must not exclude the first.

Which stamp is which is read from the DESCRIPTOR's ``normalize_ts`` mark and is never re-derived
from a field name written here. The mark is surfaced as
:data:`NORMALIZED_TIMESTAMP_FIELD_NAMES`, its complement as :data:`DETERMINISTIC_FIELD_NAMES`,
and :func:`is_normalized_timestamp_field` answers for a single field, so a verification pass
partitions the record by reading the mark rather than by carrying a list of its own.

A published ``TRAN-PROC-TS`` is also VALIDATED rather than trusted: an unwritten stamp is
accepted, and a stamp that is neither unwritten nor well formed is refused. See
:func:`_require_timestamp_shape` for the rule and for why refusing beats blanking.

Assumptions: an INTEREST transaction record decodes identically through this reader.
``INTTRAN`` is registered separately in the layouts module to carry DERIVED provenance
and its own alternate key, and it declares the same copybook, the same 350-byte width
and the same field geometry as this record -- the two differ only in provenance and in
which timestamps a golden comparison normalises, neither of which affects decoding. A
thirteenth reader for it would therefore have been a second statement of one layout.

This record also carries the ``TRANSACT.VSAM.AIX`` batch access path: the processing
timestamp at offset 304 is the alternate key ``app/jcl/TRANIDX.jcl`` builds, and the
target replaces it with a non-unique secondary index on the same column. The descriptor
carries that alternate key, so a caller reads it from there.

Decoded shape
-------------
A decoded record is a ``dict`` keyed by the field name exactly as the copybook spells it, in
declaration order, which is the record's byte order. A character field maps to its characters at
full declared width, untrimmed. An unsigned display field maps to its characters too,
once they are proven to be the digits its picture clause requires, so the leading zeroes
in the four-digit category code and the nine-digit merchant identifier survive.
The one signed display field maps to an exact
:class:`decimal.Decimal` at the scale its picture clause declares.
The trailing pad is absent; see :data:`DROPPED_FIELD_NAMES`.

That shape is deliberately the one ``carddemo_migration.copybook.ebcdic_codec.decode_record``
publishes, minus the pad, which is what makes the two encodings comparable rather than merely
similar.

Transaction exposure control
----------------------------
EIGHT of this record's thirteen data fields are marked sensitive by the descriptor, and the
descriptor is the only place that decides it. They are ``TRAN-ID``, ``TRAN-DESC``, ``TRAN-AMT``,
``TRAN-MERCHANT-ID``, ``TRAN-MERCHANT-NAME``, ``TRAN-MERCHANT-CITY``, ``TRAN-MERCHANT-ZIP`` and
``TRAN-CARD-NUM``. The remaining FIVE -- ``TRAN-TYPE-CD``, ``TRAN-CAT-CD``, ``TRAN-SOURCE``,
``TRAN-ORIG-TS`` and ``TRAN-PROC-TS`` -- are disclosable, and the fourteenth field is the trailing
pad, which is dropped rather than published. The split is identical to the daily-transaction twin's,
which is what one would expect of two records declared from the same field geometry.

All thirteen data fields are **emitted** in the decoded mapping, because the ledger table stores all
thirteen and a loader that silently withheld a column would load a wrong row. The eight are
**redacted** in every diagnostic this module produces, with the account number revealing at most its
trailing four digits. Diagnostics never echo record content of any kind, sensitive or otherwise, so
the redaction is a second line rather than the only one.

Refactoring Rationale: this section used to claim TWO sensitive fields -- the account number and the
amount -- and went on to list the identifier, the description and the merchant identity as staying
verbatim on the grounds that "none of them designates a cardholder". Against the descriptor this
module actually reads, all six of those are marked sensitive, so both the count and the conclusion
drawn from it were wrong. A masked transaction diff is consequently far less readable than the old
prose promised: it shows the type, category and source codes and the two stamps, and nothing else.
The prose is corrected rather than the policy, because a merchant name and city together with an
amount identify a cardholder's purchase perfectly well even without the number, which is exactly the
reassembly the broad marking prevents. Assumptions: the effective set is pinned by
``tests/test_corpus_disclosure.py``, which requires every field of every record to be either
admitted by name to a disclosure allowlist or marked sensitive, so the descriptor cannot drift away
from this paragraph again without breaking a test.

Design decisions (WHY)
----------------------
Assumptions:
    **Every offset, length, storage regime and record length is imported, never declared.**
    This module states no byte position of its own, and in particular states no key width: the
    16-byte key is read from the descriptor's own ``key_offset`` and ``key_length``. That is
    the Python analogue of compiling every COBOL program against a single ``cobc -I app/cpy``
    include path. The consequence of breaking it is specific and undetectable -- a re-declared
    offset lets this reader and a sibling reading the same bytes drift apart, and a record read
    one byte out of alignment still decodes to plausible characters, so nothing raises and no
    test fails.
Trade-offs:
    **Records are streamed, never materialised.** Both entry points are generators, so memory is
    constant in the record count. The accepted cost is a single forward pass; what it buys is
    that this reader behaves identically on the committed seed and on a production extract many
    orders larger, so the one proven against the seed is the one that runs.
Assumptions:
    **Money is exact fixed point and there is no ``float`` in this module.** An IEEE-754 binary
    value cannot represent ten cents exactly, so a total accumulated in one drifts from the total
    the baseline computed -- silently, and by an amount that grows with the row count. Every
    signed display field therefore leaves this module as a :class:`decimal.Decimal` at its
    declared scale.
Trade-offs:
    **Standard library plus ``carddemo_migration.copybook``, and nothing else.** No database
    driver, no AWS SDK and no character-set package is imported here, so this reader imports and
    runs on a bare checkout with no credential configured. Choosing a dataset, opening a
    connection and writing rows belong to a loader.
"""

from __future__ import annotations

import pathlib
from collections.abc import Iterable, Iterator, Mapping
from decimal import Decimal
from typing import Final

# WHY : Assumptions: these imports are absolute and rooted at the distribution package rather
#   than relative, and the record descriptor named below is the ONLY statement of this record's
#   geometry anywhere in the package. A relative import is how the single-sourcing guarantee gets
#   broken quietly: a module moved between `readers/` and `loaders/` keeps importing successfully
#   but against a different sibling, and this project's ruff configuration bans relative imports
#   outright for that reason.
# WHY : Assumptions: the descriptor is selected by NAME and never by record length, and for this
#   record that is a correctness requirement rather than a preference. `CVTRA05Y` and `CVTRA06Y`
#   are byte-for-byte identical in geometry -- same widths, same offsets, the same 350-byte record
#   length -- and differ only in the prefix on every field name, so `TRAN_LAYOUT` and
#   `DALYTRAN_LAYOUT` are indistinguishable by width. A length-based lookup would return whichever
#   of the two was registered first, decode every field to the right characters under the wrong
#   names, and give a loader a row it would insert into the wrong table with no width check left
#   to catch it. The layouts module additionally registers `TRNX_LAYOUT`, `REJECT_LAYOUT` and
#   `INTTRAN_LAYOUT` over related geometry; this module reads `TRAN_LAYOUT` alone.
# WHY : Assumptions: two offsets in this descriptor are load-bearing beyond the record itself, and
#   three independent sources agree on both. Summing the copybook field widths puts
#   `TRAN-CARD-NUM` at zero-based 262 and `TRAN-PROC-TS` at 304; `app/jcl/TRANREPT.jcl` L41-L42
#   declares them as ONE-based DFSORT positions 263 and 305; and `app/jcl/TRANFILE.jcl` L84 builds
#   the `TRANSACT.VSAM.AIX` alternate index with a zero-based `KEYS(26 304)`. Those are the two
#   columns the target replaces with `idx_transactions_card_num` and `idx_transactions_proc_ts`,
#   so an off-by-one here would break the report's sort and every by-card lookup while still
#   decoding to plausible digits.
# WHY : Assumptions: the shared timestamp authority is imported as a MODULE and its members
#   reached through it, where every other import here names the members it wants. That is
#   deliberate: `is_unwritten`, `is_admitted` and `canonical` are generic verbs, and unqualified
#   they would read as though this reader owned the rule. Qualified, every use site says which
#   module decides what a timestamp is -- the whole point of one module rather than three copies.
from carddemo_migration.copybook import timestamp
from carddemo_migration.copybook.ebcdic_codec import decode_record, iter_ebcdic_records
from carddemo_migration.copybook.layouts import (
    TRAN_LAYOUT,
    FieldSpec,
    Kind,
    LayoutError,
    RecordLengthError,
    iter_ascii_text_records,
    mask_field,
    mask_record,
)
from carddemo_migration.copybook.zoned import decode_zoned_field
from carddemo_migration.readers.source import (
    data_region_width,
    iter_seed_lines,
    open_regular_binary,
    require_exact_record_width,
)

# WHY : Trade-offs: the shared presence predicate is imported under an ALIAS because this module
#   publishes a predicate of the same name -- the one this record's callers and its own docstring
#   refer to -- and a plain import would shadow it. Renaming the published name instead would break
#   a documented public surface for a private convenience, so the private borrowing is what gets the
#   new name.
from carddemo_migration.readers.source import (
    seed_dataset_is_present as source_dataset_is_present,
)

__all__ = [
    "DETERMINISTIC_FIELD_NAMES",
    "DROPPED_FIELD_NAMES",
    "HAS_COMMITTED_SEED_DATASET",
    "LOADED_FIELDS",
    "NORMALIZED_TIMESTAMP_FIELD_NAMES",
    "TRAN_LAYOUT",
    "DecodedTransaction",
    "decode_ascii_transaction",
    "decode_ebcdic_transaction",
    "is_normalized_timestamp_field",
    "iter_ascii_transactions",
    "iter_ebcdic_transactions",
    "read_ascii_transactions",
    "read_ebcdic_transactions",
    "record_key",
    "render_masked_transaction_field",
    "render_masked_transaction_record",
    "seed_dataset_is_present",
]

# WHY : Assumptions: the decoded value type is a union of exactly two members because this
#   record declares three storage regimes and two of them -- character and unsigned
#   display -- yield characters while the third yields an exact decimal. The union
#   deliberately excludes `bytes`, which the record decoder can return for a
#   mixed-regime area, because this record declares no such area; admitting it here
#   would oblige every caller to narrow a case the layout makes unreachable.
DecodedTransaction = dict[str, str | Decimal]

# WHY : Assumptions: the trailing pad is identified by NAME and not by position, and the
#   convention was measured rather than assumed: across all fourteen registered layouts every
#   record declares exactly one pad, named either `FILLER` or, where the copybook qualified it,
#   with that word as its final hyphenated component -- which is exactly this record's case for
#   the security file and is why a positional rule would have had to special-case it. Testing the
#   name keeps this module free of any byte position of its own.
_PAD_FIELD_NAME: Final[str] = "FILLER"
_PAD_NAME_SUFFIX: Final[str] = f"-{_PAD_FIELD_NAME}"


def _is_padding_field(field: FieldSpec) -> bool:
    """Report whether a field is the record's trailing pad rather than data.

    Purpose
    -------
    Decide, from the field's declared name alone, whether it exists only to fill the record out
    to its fixed length. This is the single place that judgement is made, so the projection and
    the record of what was dropped cannot disagree about it.

    Parameters
    ----------
    field : FieldSpec
        The field descriptor under test. Only its ``name`` is consulted.

    Returns
    -------
    bool
        ``True`` when the field is a pad and must be dropped from a decoded record; ``False``
        for every field that carries data.

    Raises
    ------
    None
    """
    return field.name == _PAD_FIELD_NAME or field.name.endswith(_PAD_NAME_SUFFIX)


# WHY : Assumptions: the pad is DROPPED from every decoded record because its 20 trailing
#   bytes pad the record out to its fixed 350-byte length and carry no data. The EBCDIC
#   record decoder deliberately returns it, stating that dropping it is a projection decision
#   belonging to the reader that maps a record onto a table, so this is that decision and this is
#   where it is taken. Both names below are published rather than kept private so the drop is a
#   fact a caller and a verification pass can assert, instead of a silent omission that would
#   make a decoded record a partial description of the bytes it came from.
LOADED_FIELDS: Final[tuple[FieldSpec, ...]] = tuple(
    field for field in TRAN_LAYOUT.fields if not _is_padding_field(field)
)
DROPPED_FIELD_NAMES: Final[frozenset[str]] = frozenset(
    field.name for field in TRAN_LAYOUT.fields if _is_padding_field(field)
)


# WHY : Assumptions: the boundary between a value and the trailing pad is DERIVED from
#   the published field tuple and is never written here as a number. Bytes at or beyond it
#   are the pad a text conversion may legitimately have dropped, so supplying them by
#   padding restores what was discarded and changes no published value; bytes BEFORE it
#   belong to a field this reader publishes, so supplying those would not restore anything
#   -- it would invent a value the source never carried and hand a loader a row to key on.
_DATA_REGION_WIDTH: Final[int] = data_region_width(LOADED_FIELDS)

# WHY : Assumptions: WHICH stamp is non-deterministic is read off the DESCRIPTOR's
#   `normalize_ts` mark and is never re-derived from a field name written here. The asymmetry is
#   load-bearing rather than incidental: `TRAN-ORIG-TS` is copied from the input transaction and
#   does not vary between runs, whereas `TRAN-PROC-TS` is stamped from the run clock by the posting
#   program. A checksum or a golden comparison must leave the second out of its span and must NOT
#   leave the first out, and `data-migration/README.md` section 10.1 fixes that as the contract:
#   the pass reads the mark rather than carrying its own list of offsets. Deriving the same fact
#   twice is how the two come to disagree, and the disagreement would be silent -- a digest that
#   included the wall-clock stamp differs between two loads of identical data, which reads as a
#   data defect rather than as the measurement defect it is.
# WHY : Trade-offs: both sets are PUBLISHED rather than kept private, and the complement is
#   published as an ORDERED tuple rather than as a set. A digest is computed over an explicit
#   ordered sequence of field names precisely because a mapping's iteration order is a property of
#   how it was built, so handing a caller a set would oblige the caller to choose an order -- and
#   two callers choosing differently would compute two incomparable digests of identical data. The
#   order here is the descriptor's declaration order, which is the record's byte order. Together
#   the two names partition the published fields exactly, so a caller can assert the split rather
#   than trust it.
NORMALIZED_TIMESTAMP_FIELD_NAMES: Final[frozenset[str]] = frozenset(
    field.name for field in LOADED_FIELDS if field.normalize_ts
)
DETERMINISTIC_FIELD_NAMES: Final[tuple[str, ...]] = tuple(
    field.name for field in LOADED_FIELDS if not field.normalize_ts
)

# WHY : Assumptions: this record ships NO extract of its own, in either encoding, and the fact is
#   published rather than left in prose so a caller can branch on it instead of hard-coding the
#   same conclusion. Measured: `app/data/ASCII/` holds nine seeds and none of them is a transaction
#   master, and `app/data/EBCDIC/` holds thirteen datasets and none of them is either.
#   `app/jcl/TRANFILE.jcl` defines the cluster at L49-L54 and then primes it at L67-L74 by copying
#   `AWS.M2.CARDDEMO.DALYTRAN.PS.INIT`, which is a single 350-byte record, so even the baseline job
#   loads no master data here -- the content is written later by the posting and backup pipeline.
#   `data-migration/README.md` marks this record separately from the ten that do ship an extract
#   for exactly this reason.
HAS_COMMITTED_SEED_DATASET: Final[bool] = False


def is_normalized_timestamp_field(field_name: str) -> bool:
    """Report whether one named field of this record is the wall-clock stamp.

    Purpose
    -------
    Answer, for a single field, the question a checksum or a golden comparison has to ask before
    it includes that field in a compared span: is this value written from the run clock, and
    therefore expected to differ between two runs over the same data?

    Parameters
    ----------
    field_name : str
        The field name exactly as the copybook spells it.

    Returns
    -------
    bool
        ``True`` when the descriptor marks the field as a run-clock stamp a parity comparison may
        blank; ``False`` for every field carrying deterministic data, the originating stamp
        included.

    Raises
    ------
    LayoutError
        If no field of that name is declared by this record. Raised by the descriptor's own
        lookup.
    """
    # WHY : Assumptions: the flag is read off the descriptor for the NAMED field rather than
    #   tested against the published set, so an unknown or misspelled name RAISES here instead of
    #   answering `False`. `False` is the answer that means "this field is deterministic", so a
    #   typo would otherwise place a wall-clock stamp inside a checksummed span, and the digest
    #   would then differ between two loads of identical data with nothing naming the cause.
    return TRAN_LAYOUT.field(field_name).normalize_ts


def seed_dataset_is_present(path: pathlib.Path) -> bool:
    """Report whether a named transaction source exists to be read.

    Purpose
    -------
    Decide, in one named place, whether there is anything at ``path`` to decode. It is the guard
    both file-taking entry points apply, so this record's defining property -- that its input may
    legitimately not exist -- is an explicit decision rather than an accident of how an iterator
    behaves when handed a missing file.

    Assumptions: an absent source is a NORMAL state for this record and for no sibling. No extract
    ships in either encoding, so there is nothing under ``app/data`` for a caller to point at and
    no default path to fall back on; the content is produced by the posting and backup pipeline.
    ``data-migration/sql/verify/row_counts.sql`` encodes the same fact as a ``NULL`` baseline
    rather than a count of zero, on the stated grounds that a correct fresh load must not read as
    a failure. Raising here would make this reader contradict the verification pass written to
    check it.

    Alternatives Considered: standing in for the absent extract rather than reporting it absent.
    Two stand-ins were available and both are rejected. The baseline's own primer record is
    committed and could be emitted, and a zero-filled record of the declared width could be
    synthesised. Either would put a row into the target that the source does not contain, and the
    row-count and money-total passes would then correctly report a parity failure -- so the cost of
    the convenience is a false alarm in the checks that exist to catch real ones.

    Alternatives Considered: reporting absence rather than raising and letting the caller catch.
    Catching would work, but it makes every caller responsible for knowing which of the twelve
    readers has an optional input, and a caller that guarded the wrong exception type would turn a
    normal state into a stopped load. A predicate states the fact once where the fact is known.

    Assumptions: this predicate is INFORMATIONAL for a caller that wants to know before it starts
    reading. It is NOT the guard the two file-taking entry points apply, and that separation is
    deliberate -- see the Refactoring Rationale below. Both entry points decide presence inside the
    single hardened open that also reads the file, so no window exists in which the path could
    change kind between a look and a read.

    Parameters
    ----------
    path : pathlib.Path
        The exact source to test. Nothing is opened for reading, no byte is decoded and no
        directory is searched: the caller names the file.

    Returns
    -------
    bool
        ``True`` when a regular file exists at ``path``; ``False`` ONLY when nothing exists there,
        in which case a subsequent read yields no records and reports no error. Every other
        outcome raises: absence is the only state this predicate is allowed to report as
        ``False``.

    Raises
    ------
    IsADirectoryError
        If a directory occupies the path. A directory is not an absent dataset, and reporting
        it as one loads zero rows from a path an operator believes they supplied.
    OSError
        If the path exists but cannot be inspected -- a traversal denial on a parent directory or a
        broken mount, for instance -- because in that state the answer to "is the dataset there?"
        is not known, and answering "absent" would report a fault as a normal state.
    LayoutError
        If something exists at ``path`` and is not a regular file, or if ``path`` is a symbolic
        link whose target does not resolve. Both are caller mistakes rather than the normal empty
        state this record is allowed to be in.
    """
    # WHY : Refactoring Rationale: this was `path.is_file()`, and that one call collapsed four
    #   distinct outcomes into the single answer "absent". is_file() is False for a DIRECTORY, for a
    #   DANGLING SYMBOLIC LINK, for a path whose parent DENIES TRAVERSAL and for a DEVICE or PIPE --
    #   so each of those made this reader complete successfully having loaded nothing. That is the
    #   worst available outcome for this particular record, because it is the one record whose
    #   verification baseline is deliberately NULL rather than a count: `sql/verify/row_counts.sql`
    #   accepts zero rows for `ledger.transactions` on the stated grounds that the posting job fills
    #   it, so a silent zero-row load has no downstream check left to catch it. The old rationale
    #   argued a directory "is a caller mistake, and reporting no records for it is the same answer
    #   this function gives for the case the mistake resembles" -- which is precisely the conflation
    #   that made the mistake invisible.
    # WHY : Trade-offs: the implementation is DELEGATED to the shared source module rather than
    #   restated here, so the missing-versus-invalid split has exactly one implementation for all
    #   twelve readers and this module cannot drift away from the two entry points that now decide
    #   presence through the same module's hardened opens. The cost is one indirection; what it buys
    #   is that "absent" means the same thing on the predicate, on the character path and on the
    #   byte path.
    # WHY : Assumptions: a ZERO-BYTE file is still reported PRESENT and is deliberately not filtered
    #   out. Both iterators already yield nothing for an empty source -- the text one produces no
    #   line and the fixed-length one divides zero bytes into zero records -- so the empty case
    #   needs no special handling and gets none. Adding one would put a second, redundant statement
    #   of "no records" in this module, which is how the two would come to disagree about, for
    #   instance, a file holding a single stray separator.
    return source_dataset_is_present(path)


# WHY : Refactoring Rationale: the timestamp shape rule that stood here -- two pad-character
#   constants, a uniformity helper, a per-position character helper and two frozen sets naming the
#   admitted separators and the admitted digits -- is gone, and `copybook.timestamp` now answers
#   both questions for every caller in the package. The rule as written admitted the UNION of four
#   separator characters at EACH of six positions and validated no calendar and no clock at all, so
#   three spellings the baseline never writes satisfied it: a value separated
#   `2022.07-18:10.30 00-123456`, an impossible date `2022-13-45 10:30:00.123456`, and an
#   out-of-range clock `2022-07-18 99:99:99.123456`. The last two then failed much later, inside the
#   database, as a cast error naming a column rather than a record. And the same forty lines existed
#   in THREE readers, so a correction had to be made three times or the three would disagree about
#   what a timestamp is while all three continued to look right.
# WHY : Alternatives Considered: tightening the per-position rule in place -- a separator set per
#   position plus explicit range checks on month, day, hour, minute and second. Rejected because it
#   re-implements a calendar: it still has to know that April has thirty days and that 2100 is not a
#   leap year, and either line can be wrong with no test noticing until a particular date arrives.
#   The shared module parses through the standard library's own calendar and requires the parsed
#   instant to FORMAT BACK to the original characters, which is the same test with none of that
#   surface and which additionally rejects a short fraction that `%f` alone accepts.


def _require_timestamp_shape(value: str, field: FieldSpec) -> str:
    """Require a run-clock stamp to be either unwritten or a well-formed timestamp.

    Purpose
    -------
    Validate the one field of this record whose value a parity comparison is allowed to blank,
    BEFORE it is published, so that a corrupt stamp is reported rather than carried into a load
    and then blanked out of the very comparison that would have caught it.

    Assumptions: an unwritten stamp is a LEGITIMATE value and not a decode failure. COBOL leaves a
    stamp it has not written in the state the record was initialised to, and this record is the one
    the posting run writes, so a row staged before that run carries the blank span its input
    carried. Treating either uniform form as corrupt would refuse records the baseline considers
    valid.

    Trade-offs: a stamp that is NEITHER unwritten NOR well formed is refused rather than accepted
    or quietly blanked. Blanking by position would paper over exactly two faults a financial
    pipeline must surface: a record whose bytes are corrupt, and a reader whose offsets have moved
    -- and this stamp sits at offset 304, which is the alternate-index key, so a moved offset here
    still yields plausible characters while breaking the access path the report depends on. The
    cost accepted is that a genuinely new timestamp dialect fails loudly here instead of passing
    through, which is the cheaper of the two failures because it names the field.

    Trade-offs: a span holding a MIXTURE of the two unwritten forms falls through to the same
    refusal, deliberately. Only a uniform span denotes a stamp nobody has written; a mixture is one
    partly written or partly overwritten, and no writer in this corpus produces one. The package's
    timestamp authority refuses that case for the same reason, so accepting it here would make the
    reader and that codec disagree about what an unwritten stamp is.

    Parameters
    ----------
    value : str
        The decoded characters of the stamp, at the field's full declared width.
    field : FieldSpec
        The descriptor for the stamp, supplying its declared width for the check and its geometry
        for the diagnostic. Nothing about the field is taken from anywhere else.

    Returns
    -------
    str
        ``value`` unchanged. Nothing is normalised, blanked or reformatted here: the mark on the
        descriptor says which field a COMPARISON may blank, and producing that rendering belongs to
        the comparison, because a reader that blanked on the way through would leave the parity
        check unable to show what actually differed.

    Raises
    ------
    LayoutError
        If the value is not the field's declared width, or is neither uniformly unwritten nor a
        well-formed timestamp in either dialect.
    """
    if len(value) == field.length:
        # WHY : Assumptions: the DECLARED width is still checked here, against the
        #   descriptor, rather than delegated with the rest. The shared authority checks the
        #   26 characters its two admitted forms occupy; this reader is the only place that
        #   knows the width the LAYOUT declares for this field, and the two agreeing is a
        #   property to verify rather than one to assume -- a descriptor edited to a
        #   different width would otherwise pass silently on every populated stamp.
        if timestamp.is_unwritten(value) or timestamp.is_admitted(value):
            return value

    # WHY : Trade-offs: the refusal names the field's GEOMETRY and the declared width it failed
    #   against, and quotes NO part of the value -- not the offending character and not its
    #   position. `LayoutError` is chosen over the two nearer alternatives for reasons that are not
    #   stylistic: a record-length error would misdescribe a record of exactly the right width whose
    #   CONTENT is wrong, and the display-numeric codec's error belongs to a numeric regime this
    #   character field does not declare, so borrowing it would send a reader looking in the wrong
    #   codec. It is a `ValueError` either way, so a caller guarding that base type still catches
    #   it.
    raise LayoutError(
        f"field {field.describe()} of record {TRAN_LAYOUT.name} holds neither a well-formed"
        f" {field.length}-character timestamp nor a uniformly unwritten span, so either the stamp"
        " is corrupt or the field offsets have moved; it is refused rather than accepted or"
        " blanked, because blanking it would remove the evidence from the comparison that would"
        " otherwise have reported it"
    )


def _field_containing(offset: int) -> FieldSpec | None:
    """Find the declared field whose byte span covers a record offset.

    Purpose
    -------
    Let a failure report WHICH field a bad byte falls in, using the layout's own spans, so a
    diagnostic can be specific about location without quoting any record content.

    Parameters
    ----------
    offset : int
        A zero-based offset into the record.

    Returns
    -------
    FieldSpec | None
        The descriptor whose half-open span contains ``offset``, or ``None`` when the offset lies
        beyond the declared record; the fields cover the record contiguously, so ``None`` means
        the offset itself is out of range.

    Raises
    ------
    None
    """
    for field in TRAN_LAYOUT.fields:
        if field.start <= offset < field.end:
            return field
    return None


def _require_single_byte_record(record: str, number: int) -> str:
    """Require a record whose character count provably equals its byte count.

    Purpose
    -------
    Close the one width failure the shared text-record iterator cannot see. That iterator
    enforces the declared length in CHARACTERS, which is the right check for text; this one
    additionally proves every character occupies a single byte, so the character contract also
    holds at the byte level the field offsets are expressed in.

    Parameters
    ----------
    record : str
        One whole record, already cut to the declared length by the shared iterator.
    number : int
        The one-based record number within the source, reported so a rejection names the row
        that failed.

    Returns
    -------
    str
        ``record`` unchanged, once every character is proven to be single-byte.

    Raises
    ------
    RecordLengthError
        If any character is outside the single-byte range. The record's declared width would
        then differ from its width in bytes.
    """
    # WHY : Assumptions: a multi-byte character satisfies a CHARACTER-count check while occupying
    #   more than one byte, so it passes the shared iterator's declared-length test and then
    #   desynchronises every offset after it -- and because a record read one byte out of
    #   alignment still decodes to plausible characters, nothing later would raise. This check,
    #   and every other validation in this module, is enforced by an explicit `raise` and never by
    #   an `assert`: running the interpreter with `-O` strips assert statements outright, so an
    #   assertion is a validation that disappears in exactly the deployment where a misaligned
    #   record costs something.
    if record.isascii():
        return record

    offset = next(index for index, char in enumerate(record) if not char.isascii())
    field = _field_containing(offset)

    # WHY : Trade-offs: the message names the record number, the zero-based offset and the
    #   containing field's geometry, and it quotes NO part of the record -- not the offending
    #   character and not its code point. That shows exactly WHERE the record failed while
    #   emitting none of its content, so the diagnostic is safe to log wherever its consumer
    #   sends it. The reference codec does echo the offending character; the stricter form is
    #   adopted here because this record holds a primary account number
    #   at offset 262 and an amount at 132, and a reader cannot know which fields of a
    #   future record are sensitive, so no content is echoed at all.
    location = "beyond the declared record" if field is None else f"in field {field.describe()}"
    raise RecordLengthError(
        f"record {number} of {TRAN_LAYOUT.name} holds a character outside the single-byte"
        f" range at zero-based offset {offset}, {location}; a multi-byte character satisfies"
        f" the {TRAN_LAYOUT.reclen}-character width check while occupying more bytes, which"
        " moves every field offset after it, so the record is rejected rather than decoded"
    )


def _decode_text_field_value(record: str, field: FieldSpec) -> str | Decimal:
    """Decode one field of a character record, routing it by its declared storage regime.

    Purpose
    -------
    Produce one field's final value from a record that is already characters. Each regime this
    record declares is named explicitly and routed to the codec that owns it, and anything else
    is refused.

    Parameters
    ----------
    record : str
        One whole record at its declared character width.
    field : FieldSpec
        The descriptor supplying the offset, the width and the regime. Nothing about the field's
        position is taken from anywhere else.

    Returns
    -------
    str | Decimal
        The field's characters at full declared width for a character field, and the same for
        an unsigned display field once its digits are proven; an exact
        :class:`decimal.Decimal` at the field's declared scale for the signed display
        field.

    Raises
    ------
    LayoutError
        If the field declares a storage regime that cannot occur in a character record, which is
        any computational or mixed-regime area, or if a run-clock stamp holds neither an unwritten
        span nor a well-formed timestamp.
    ZonedSpanWidthError
        If an unsigned display field's declared span is not available, or the descriptor's display
        geometry is internally inconsistent. Raised by the display codec.
    ZonedDecimalError
        If a display span violates its contract: a non-digit in the body of either display regime,
        or a low-order byte that is not a valid sign overpunch in a signed one. Raised by the
        display codec.
    """
    if field.kind is Kind.TEXT:
        characters = record[field.start : field.end]
        # WHY : Assumptions: the stamp check is driven by the descriptor's `normalize_ts` mark, so
        #   the ORIGINATING stamp -- which the descriptor does not mark -- passes through as
        #   ordinary business data while the RUN-CLOCK one is validated. Naming a field here
        #   instead would restate a fact the descriptor already carries, and the two would then be
        #   free to disagree with the verification pass, which reads the same mark.
        if field.normalize_ts:
            return _require_timestamp_shape(characters, field)
        return characters

    if field.kind is Kind.UINT:
        # WHY : Trade-offs: the decimal this returns is DISCARDED and the characters are returned
        #   instead, which costs one parse whose result is thrown away. What it buys is the content
        #   check the picture clause states -- an unsigned display field holding a letter is
        #   refused rather than passed through -- while keeping the value a digit string of
        #   declared width. An integer would drop a leading zero, and a leading zero is
        #   significant in every identifier this corpus declares as `PIC 9`.
        # WHY : Assumptions: this check runs on BOTH paths of this reader. The EBCDIC path gets it
        #   as a by-product of having to decode a code page at all; performing it here keeps the
        #   two entry points enforcing ONE contract, so the same defective record cannot decode
        #   differently depending on which corpus it arrived in.
        decode_zoned_field(record, field)
        return record[field.start : field.end]

    if field.kind is Kind.ZONED:
        # WHY : Alternatives Considered: the FIELD-oriented entry point is called rather than the
        #   span-oriented one, which would have meant slicing here and passing the digit counts and
        #   the sign flag as three separate arguments. The field-oriented form derives all of them
        #   from this descriptor and slices by it too, so this module restates no part of the
        #   geometry. Passing the descriptor also lets that codec name the field in its own
        #   diagnostics and honour its sensitivity flag.
        # WHY : Assumptions: the result is an exact decimal at the scale the picture clause
        #   declares and is never converted to a binary floating-point type. A binary float cannot
        #   represent ten cents exactly, so a total accumulated in one drifts from the total the
        #   baseline computed, by an amount that grows with the row count.
        return decode_zoned_field(record, field)

    # WHY : Assumptions: every regime this record declares is named explicitly above and anything
    #   else raises, rather than the last branch doubling as a default. A computational or
    #   mixed-regime area cannot be read from a character record at all: its bytes are packed
    #   nibbles, machine words or a differently-described overlay, and a character decode of them
    #   succeeds, keeps the declared width and yields plausible text, so the damage would be
    #   invisible. Raising here also means a regime added to this record later cannot fall
    #   silently into whichever branch happens to be last.
    raise LayoutError(
        f"field {field.describe()} of record {TRAN_LAYOUT.name} declares a storage regime"
        " that cannot be decoded from a character record; only the character and display regimes"
        " can, and a computational or mixed-regime area must be read from the byte image through"
        " the EBCDIC record decoder"
    )


def _project_decoded_fields(
    values: Mapping[str, str | Decimal | bytes],
) -> DecodedTransaction:
    """Drop the trailing pad from a fully decoded record.

    Purpose
    -------
    Apply the projection that separates the bytes on disk from the columns a row carries, so both
    encodings converge on the same published shape, and fail closed on a value the published
    shape cannot hold.

    Parameters
    ----------
    values : Mapping[str, str | Decimal | bytes]
        Every declared field of one record, keyed by field name, including the pad. The parameter
        admits the record decoder's own wider value union.

    Returns
    -------
    DecodedTransaction
        The same mapping without any padding field, in
        declaration order.

    Raises
    ------
    LayoutError
        If a retained field decoded to raw bytes, which means the descriptor has acquired a
        computational or mixed-regime area this reader publishes no representation for.
    """
    # WHY : Trade-offs: an unexpected `bytes` value is REJECTED rather than filtered out. The
    #   filter would be justified by this record declaring no computational area today, and that
    #   is true -- but the moment a descriptor edit or a decoder change made it false, a filter
    #   would silently OMIT a real field and hand a loader a plausible partial row whose missing
    #   column inserts as NULL with nothing reporting it. Failing closed converts an invisible
    #   data-integrity fault into a named one.
    # WHY : Trade-offs: the pad is dropped BEFORE the type is judged, so a pad that decoded to
    #   bytes cannot raise. The pad is not published, so its regime is not this reader's contract
    #   to enforce, and raising over a value nobody receives would turn a harmless descriptor
    #   detail into a load failure.
    projected: DecodedTransaction = {}
    for name, value in values.items():
        if name in DROPPED_FIELD_NAMES:
            continue
        if isinstance(value, bytes):
            # WHY : Trade-offs: the rejection names the field's GEOMETRY through the descriptor
            #   and never renders the bytes, not even as a length-bounded excerpt, because an
            #   unexpected computational span could sit anywhere in this record including over a
            #   field the disclosure policy protects. Naming the field is enough to locate the
            #   defect, which is in the descriptor rather than in the data.
            raise LayoutError(
                f"field {TRAN_LAYOUT.field(name).describe()} of record"
                f" {TRAN_LAYOUT.name} decoded to raw bytes rather than a published value, so"
                " it declares a computational or mixed-regime area; such an area must be decoded"
                " by the codec that owns its regime rather than dropped from the row"
            )
        projected[name] = value
    return projected


def _require_validated_timestamps(values: DecodedTransaction) -> DecodedTransaction:
    """Apply the run-clock stamp policy to an already projected record.

    Purpose
    -------
    Give the byte path the same stamp validation the character path applies field by field, so a
    corrupt processing timestamp is refused whichever corpus the record arrived in.

    Parameters
    ----------
    values : DecodedTransaction
        One projected record, keyed by field name, as :func:`_project_decoded_fields` returns it.
        It is a freshly built mapping, so the marked field is rewritten in place rather than the
        whole record copied.

    Returns
    -------
    DecodedTransaction
        The same mapping, with every run-clock stamp proven to be either unwritten or well formed.
        No value is altered: the policy validates and returns, it does not normalise.

    Raises
    ------
    LayoutError
        If a marked stamp decoded to a non-character value, or holds neither an unwritten span nor
        a well-formed timestamp.
    """
    # WHY : Alternatives Considered: this is a separate pass over the MARKED fields rather than a
    #   branch inside the projection loop, and it iterates the published mark rather than every
    #   field. The projection has one job -- drop the pad and refuse an unpublishable value -- and a
    #   second condition inside it would run on all thirteen fields to reach the one that needs it.
    #   Both paths still call ONE policy function, which is what makes it impossible for the two
    #   encodings to enforce different rules; splitting the POLICY rather than the call site is the
    #   drift this arrangement exists to avoid.
    for name in NORMALIZED_TIMESTAMP_FIELD_NAMES:
        field = TRAN_LAYOUT.field(name)
        value = values[name]

        # WHY : Trade-offs: a marked field that did not decode to characters is REFUSED rather
        #   than skipped. A skip would be justified by this record declaring its stamps as
        #   character data today, which it does -- but the moment a descriptor edit made that
        #   false, a skip would silently stop validating the one field a comparison is allowed to
        #   blank, and an unvalidated stamp is exactly what the blanking would then hide. The
        #   package's timestamp codec refuses a non-character stamp for the same reason.
        if not isinstance(value, str):
            raise LayoutError(
                f"field {field.describe()} of record {TRAN_LAYOUT.name} is marked as a"
                " run-clock stamp but decoded to a value that is not characters, so its"
                " descriptor no longer declares the character regime a timestamp is written in"
            )
        values[name] = _require_timestamp_shape(value, field)
    return values


def record_key(record: str) -> str:
    """Return the primary key of one character record, sliced by the descriptor.

    Purpose
    -------
    Expose the key a loader upserts on and a verification pass groups by, taken from the
    descriptor's own key offset and length rather than from a width written here.

    Parameters
    ----------
    record : str
        One whole record at exactly the declared character width.

    Returns
    -------
    str
        The key characters at their full declared width, untrimmed, so a significant leading
        zero survives.

    Raises
    ------
    RecordLengthError
        If the record is not exactly the declared width. Raised by the shared width guard: a short
        record would yield a short key that collides with a sibling row, and an over-long one means
        the source was cut on the wrong boundary, so neither is accepted.
    """
    # WHY : Assumptions: the key is sliced by `key_offset` and `key_length` from the descriptor,
    #   never by a literal. Writing the width here would be a second statement of it, and a key
    #   sliced one character short still looks like a key -- it collides with a sibling record
    #   instead of raising, which a loader would resolve as an upsert onto the wrong row.
    # WHY : Refactoring Rationale: the width test is DELEGATED to the shared guard and is
    #   now EXACT. This function used to accept any record at least the declared width,
    #   which its own docstring and every decoder in this module contradict, and the
    #   over-long case is the more dangerous of the two: the key sliced from it comes from
    #   the right offsets of the WRONG record -- two rows concatenated, most plausibly --
    #   so it looks entirely well formed and a loader upserts on it. Delegating also means
    #   the eight flat readers cannot drift apart on a test they all have to make.
    require_exact_record_width(record, TRAN_LAYOUT)
    start = TRAN_LAYOUT.key_offset
    return record[start : start + TRAN_LAYOUT.key_length]


def decode_ascii_transaction(
    record: str,
    *,
    number: int = 1,
) -> DecodedTransaction:
    """Decode one transaction record from the ASCII seed form.

    Purpose
    -------
    Turn a single full-width character record into the published decoded shape: every data field
    keyed by its copybook name, with the trailing pad dropped.

    Parameters
    ----------
    record : str
        One whole record at exactly the declared record width, with no line terminator. Records
        at the declared width are what the shared text-record iterator yields.
    number : int
        The one-based record number within the source, used only so a rejection names the row
        that failed. Defaults to 1 for a caller decoding a record in isolation.

    Returns
    -------
    DecodedTransaction
        One entry per data field, keyed by the field name exactly as the copybook spells it, in
        declaration order. The pad is absent.

    Raises
    ------
    RecordLengthError
        If the record is not the declared width, or holds a character outside the single-byte
        range.
    LayoutError
        If a declared field's storage regime cannot be decoded from a character record, or a
        run-clock stamp holds neither an unwritten span nor a well-formed timestamp.
    ZonedSpanWidthError
        If an unsigned display field's declared span is not available, or the descriptor's display
        geometry is internally inconsistent. Raised by the display codec.
    ZonedDecimalError
        If a display span violates its contract: a non-digit in the body of either display regime,
        or a low-order byte that is not a valid sign overpunch in a signed one. Raised by the
        display codec.
    """
    # WHY : Trade-offs: a record of the WRONG width is rejected here rather than padded or cut to
    #   fit. Truncation would silently discard real data and padding would invent it, and either
    #   way the row would still decode to well-formed characters, so a wrong-width record would
    #   load under a misread key with nothing reporting it. The accepted cost is that a genuinely
    #   malformed source stops the load instead of loading partially.
    if len(record) != TRAN_LAYOUT.reclen:
        raise RecordLengthError(
            f"record {number} of {TRAN_LAYOUT.name} is {len(record)} characters against a"
            f" declared width of {TRAN_LAYOUT.reclen}; a record of the wrong width means the"
            " field offsets have moved, so it is rejected rather than padded or truncated"
        )

    checked = _require_single_byte_record(record, number)

    # WHY : Assumptions: the fields are walked in the descriptor's declaration order, which is the
    #   record's byte order, so the resulting mapping iterates the record left to right. The pad
    #   is excluded by iterating the published field tuple rather than by decoding every field and
    #   filtering afterwards, which also avoids decoding 20 bytes of pad on every record.
    return {field.name: _decode_text_field_value(checked, field) for field in LOADED_FIELDS}


def iter_ascii_transactions(
    source: str | Iterable[object],
) -> Iterator[DecodedTransaction]:
    """Decode the ASCII seed form of the transaction master, one record at a time.

    Purpose
    -------
    Stream decoded records from character data the caller already holds or is already iterating,
    delegating every record boundary decision to the shared text-record iterator.

    Parameters
    ----------
    source : str | Iterable[object]
        The seed data as either the whole text, or an iterable whose every element is one LINE,
        with or without its terminator. An open text stream is such an iterable. A byte object is
        refused, because no character encoding is guessed here.

    Returns
    -------
    Iterator[DecodedTransaction]
        Each record in order, in the published decoded shape. A source with no records yields
        nothing at all.

    Raises
    ------
    LayoutError
        If the source is a byte object, is neither text nor iterable, or produces an element that
        is not a line, if a field declares a regime a character record cannot hold, or if a
        run-clock stamp holds neither an unwritten span nor a well-formed timestamp.
    RecordLengthError
        If a line is longer than the declared record width, or a record holds a character outside
        the single-byte range.
    ZonedSpanWidthError
        If an unsigned display field's declared span is not available, or the descriptor's display
        geometry is internally inconsistent. Raised by the display codec.
    ZonedDecimalError
        If a display span violates its contract: a non-digit in the body of either display regime,
        or a low-order byte that is not a valid sign overpunch in a signed one. Raised by the
        display codec.
    """
    # WHY : Assumptions: the record cut is DELEGATED and not written again here. That iterator
    #   owns one statement of the text-mode contract for the whole package: it splits on the
    #   separator, removes at most one trailing carriage return and separator per row so trailing
    #   blanks stay data, drops the single phantom empty piece a text ending in a separator
    #   produces, right-pads a short line, and REJECTS an over-long one. A second implementation
    #   here is exactly the drift this dependency edge exists to prevent, and it would be
    #   invisible: two readers stripping terminators slightly differently both return well-formed
    #   records.
    # WHY : Trade-offs: that iterator's tolerance for a SHORT line -- right-padding it
    #   with blanks -- is inherited deliberately. Padding on the right cannot move a field
    #   that is present, and the characters added are precisely the pad a text form
    #   omitted; the committed fixtures for this record are already full width, so the
    #   tolerance does not engage.
    # WHY : Refactoring Rationale: the record cut is bounded by `_DATA_REGION_WIDTH`, and the bound
    #   closes a data-integrity defect rather than tightening a nicety. The shared iterator
    #   right-pads a short line -- which is what lets a seed whose trailing pad the conversion
    #   dropped be read at all -- and it padded a line of ANY length, so a line that stopped
    #   part-way through a field this reader PUBLISHES was completed with manufactured blanks and
    #   returned as a well-formed record. Nothing raised: the invented characters are
    #   indistinguishable from real ones. The bound is the end of the last published field, derived
    #   from `LOADED_FIELDS` rather than written here, and it is compared against the SOURCE line
    #   before any padding, which is the only place the comparison is exact.
    # WHY : Assumptions: the descriptor is passed for DIAGNOSTICS only, so a refusal can name the
    #   record and the field the line stopped inside. It cannot change which lines are accepted.
    records = iter_ascii_text_records(
        source, TRAN_LAYOUT.reclen, min_data_width=_DATA_REGION_WIDTH, layout=TRAN_LAYOUT
    )

    for number, record in enumerate(records, start=1):
        yield decode_ascii_transaction(record, number=number)


def read_ascii_transactions(path: pathlib.Path) -> Iterator[DecodedTransaction]:
    """Stream the transaction master from an ASCII seed file at an explicit path.

    Purpose
    -------
    Open one named seed file, stream its records through the shared text-record contract, and
    close it when the caller stops reading.

    Parameters
    ----------
    path : pathlib.Path
        The exact source file to read. The caller names the file; this function never searches a
        directory for it. The file need not exist: see the return description.

    Returns
    -------
    Iterator[DecodedTransaction]
        Each record in order, in the published decoded shape. A GENUINELY ABSENT path and a
        zero-byte file both yield nothing, and neither is an error -- this record ships no extract,
        so having nothing to read is a normal state rather than a fault. Nothing else is treated as
        absence: a path that exists and cannot be read as a seed file is reported, not skipped.

    Raises
    ------
    OSError
        If the path exists but cannot be inspected, opened or read -- a traversal or read
        permission denial, for instance. Only genuine non-existence is reported as no records.
    LayoutError
        If the path exists and is not a regular file, if it is a symbolic link whose target does
        not resolve, if the file produces an element that is not a line, if a field declares a
        regime a character record cannot hold, or if a run-clock stamp holds neither an unwritten
        span nor a well-formed timestamp.
    RecordLengthError
        If a line is longer than the declared record width plus its terminators, if a line stops
        inside the record's data region, or if a record holds a character outside the single-byte
        range.
    ZonedSpanWidthError
        If an unsigned display field's declared span is not available, or the descriptor's display
        geometry is internally inconsistent. Raised by the display codec.
    ZonedDecimalError
        If a display span violates its contract: a non-digit in the body of either display regime,
        or a low-order byte that is not a valid sign overpunch in a signed one. Raised by the
        display codec.
    """
    # WHY : Assumptions: the caller supplies an EXPLICIT file and this function never globs a
    #   directory to find one. The seed directories make that concrete: they hold a zero-byte
    #   placeholder and, in the EBCDIC tree, names differing by a single character, so a pattern
    #   match would either sweep the placeholder in or load a dataset twice -- and a doubled image
    #   still divides by the record length with remainder zero, so nothing downstream would catch
    #   it and every money total would come out doubled.
    # WHY : Refactoring Rationale: absence and the open are now ONE operation, where they used to be
    #   a predicate followed by `path.open`. Two faults came out of that pairing and both are closed
    #   here. The predicate answered "absent" for a directory, a dangling link, an unreadable parent
    #   and a device, so each of those loaded zero rows and reported success; and between the check
    #   and the open the name could be replaced, so a path that passed as a regular file could be
    #   read as something else. `iter_seed_lines` opens once with O_NONBLOCK, validates the KIND of
    #   the object it actually opened through that descriptor, and reports a genuinely missing name
    #   as no lines because `optional` is set -- which is the one outcome this record is allowed to
    #   have. Nothing else is silent.
    # WHY : Assumptions: `optional=True` is set on this path and on the byte path and on neither of
    #   the eleven siblings. It is the reason this reader differs from them: each of those has a
    #   committed extract to point at, so a missing file there is a fault, while for this record it
    #   is the expected state before the posting pipeline has produced any output. The flag is
    #   passed here rather than left to the caller so both encodings answer identically; see
    #   `seed_dataset_is_present` for why a stand-in record is not emitted instead.
    # WHY : Trade-offs: the code page, the terminator policy and the read bound all move WITH the
    #   open, so this module no longer names any of the three. That is the point -- nine modules
    #   each naming them is nine chances to disagree, and a disagreement would be invisible, because
    #   a reader that validated a file slightly differently from its siblings still returns
    #   well-formed records for every ordinary input. The cost is one more module to read to see how
    #   a file is opened; what it buys is that a line longer than the record can hold is refused
    #   after a few hundred characters instead of being allocated in full. `readers.source` records
    #   the full reasoning for all three decisions in one place.
    yield from iter_ascii_transactions(iter_seed_lines(path, TRAN_LAYOUT.reclen, optional=True))


def decode_ebcdic_transaction(
    record: bytes | bytearray | memoryview,
) -> DecodedTransaction:
    """Decode one transaction record from the EBCDIC dataset form.

    Purpose
    -------
    Turn a single fixed-length record image into the published decoded shape, delegating the
    per-field character conversion and the numeric decode to the EBCDIC record codec.

    Parameters
    ----------
    record : bytes | bytearray | memoryview
        One whole record image of exactly the declared record length, read in binary mode. A
        ``str`` is refused by the codec, because character data has already lost the byte values
        a sign overpunch depends on.

    Returns
    -------
    DecodedTransaction
        One entry per data field, keyed by the field name exactly as the copybook spells it, in
        declaration order, with the pad dropped. The shape is identical to the ASCII path's,
        which is what makes the two corpora comparable.

    Raises
    ------
    TypeError
        If the record is a ``str`` or is not a one-dimensional image of single bytes.
    EbcdicRecordLengthError
        If the record is not exactly the declared record length. This is a record-length error,
        so a caller guarding either encoding may catch the shared base type.
    EbcdicFieldDecodeError
        If a span does not decode to exactly one character per byte and re-encode to those same
        bytes.
    LayoutError
        If a published field decoded to raw bytes, which means the descriptor has acquired a
        computational or mixed-regime area, or if a run-clock stamp holds neither an unwritten span
        nor a well-formed timestamp.
    ZonedSpanWidthError
        If an unsigned display field's declared span is not available, or the descriptor's display
        geometry is internally inconsistent. Raised by the display codec.
    ZonedDecimalError
        If a display span violates its contract: a non-digit in the body of either display regime,
        or a low-order byte that is not a valid sign overpunch in a signed one. Raised by the
        display codec.
    """
    # WHY : Assumptions: the conversion is DELEGATED per field and never performed on the record
    #   as a whole. That codec decodes each declared span on its own, dispatching on the
    #   descriptor's regime BEFORE any character set is applied, so no span of packed nibbles or
    #   low-value padding is ever handed to a character decoder. Decoding a whole record through a
    #   code page is the single most likely mistake on this path and the most damaging: it
    #   succeeds, preserves the declared width, and yields a record that looks almost right.
    # WHY : Assumptions: the stamp policy is applied on this path too, through the same function
    #   the character path calls. The record codec deliberately returns a stamp exactly as decoded
    #   rather than validating its shape, so without this step the byte path would publish a
    #   corrupt processing timestamp that the character path refuses -- and the two corpora would
    #   then disagree about the validity of one record while agreeing on its every other field.
    decoded = _project_decoded_fields(decode_record(record, TRAN_LAYOUT))
    return _require_validated_timestamps(decoded)


def iter_ebcdic_transactions(
    source: pathlib.PurePath | bytes | bytearray | memoryview | Iterable[object],
) -> Iterator[DecodedTransaction]:
    """Decode the EBCDIC dataset form of the transaction master, one record at a time.

    Purpose
    -------
    Stream decoded records from a fixed-length dataset, cut strictly on the record length the
    layout declares.

    Parameters
    ----------
    source : pathlib.PurePath | bytes | bytearray | memoryview | Iterable[object]
        The dataset, in one of four shapes: a path, which the codec opens read-only in binary
        mode, streams and closes; a whole byte image the caller already holds; an open binary
        stream, read strictly forward and neither seeked nor closed; or an iterable of byte pieces
        whose boundaries may fall anywhere. A ``str`` is refused.

    Returns
    -------
    Iterator[DecodedTransaction]
        Each record in order, in the published decoded shape. A dataset with no bytes yields
        nothing at all.

    Raises
    ------
    TypeError
        If the source is a ``str``, which is ambiguous between a dataset location and character
        data somebody has already decoded.
    EbcdicRecordLengthError
        If the dataset does not divide into whole records of the declared length.
    EbcdicFieldDecodeError
        If a span does not decode to exactly one character per byte.
    LayoutError
        If the source is neither a byte image nor readable nor iterable, produces a piece that is
        not a byte object, a published field decoded to raw bytes, or a run-clock stamp holds
        neither an unwritten span nor a well-formed timestamp.
    ZonedSpanWidthError
        If an unsigned display field's declared span is not available, or the descriptor's display
        geometry is internally inconsistent. Raised by the display codec.
    ZonedDecimalError
        If a display span violates its contract: a non-digit in the body of either display regime,
        or a low-order byte that is not a valid sign overpunch in a signed one. Raised by the
        display codec.
    """
    # WHY : Assumptions: the dataset is cut on the declared record length ALONE, and no line
    #   terminator is looked for, honoured, stripped or padded on this path. A fixed-length
    #   blocked dataset carries no terminators, so a byte that happens to equal a newline is field
    #   data -- a low-order digit, a packed nibble pair or a pad byte -- and splitting on it would
    #   produce pieces of wildly differing lengths, most cut through the middle of a field. The
    #   correctness test for this dataset is that its size divides by the declared record length
    #   with no remainder, which the delegated iterator checks before it yields the first record.
    # WHY : Assumptions: the text form's tolerances must never reach here. Right-padding a short
    #   piece or stripping a trailing byte would turn a genuine length failure into a plausible
    #   record, which is why this path reaches a different entry point of the layouts module and
    #   shares no code with the text one.
    for record in iter_ebcdic_records(source, TRAN_LAYOUT):
        yield decode_ebcdic_transaction(record)


def read_ebcdic_transactions(path: pathlib.Path) -> Iterator[DecodedTransaction]:
    """Stream the transaction master from an EBCDIC dataset file at an explicit path.

    Purpose
    -------
    Read one named fixed-length dataset and stream its decoded records, with the size validated
    against the declared record length before the first record is produced.

    Parameters
    ----------
    path : pathlib.Path
        The exact dataset file to read. The caller names the file; this function never searches a
        directory for it. The file need not exist: see the return description.

    Returns
    -------
    Iterator[DecodedTransaction]
        Each record in order, in the published decoded shape. A GENUINELY ABSENT path and a
        zero-byte file both yield nothing, and neither is an error -- this record ships no extract
        in either encoding, so having nothing to read is a normal state rather than a fault.
        Nothing else is treated as absence: a path that exists and cannot be read as a dataset is
        reported, not skipped.

    Raises
    ------
    OSError
        If the path exists but cannot be inspected or opened -- a traversal or read permission
        denial, for instance. Only genuine non-existence is reported as no records.
    EbcdicRecordLengthError
        If the file size does not divide into whole records of the declared length. The size is
        taken from the descriptor this function opens, so the failure arrives before the first
        record rather than at the end of the stream.
    EbcdicFieldDecodeError
        If a span does not decode to exactly one character per byte.
    LayoutError
        If the path exists and is not a regular file, if it is a symbolic link whose target does
        not resolve, if a published field decoded to raw bytes, or if a run-clock stamp holds
        neither an unwritten span nor a well-formed timestamp.
    ZonedSpanWidthError
        If an unsigned display field's declared span is not available, or the descriptor's display
        geometry is internally inconsistent. Raised by the display codec.
    ZonedDecimalError
        If a display span violates its contract: a non-digit in the body of either display regime,
        or a low-order byte that is not a valid sign overpunch in a signed one. Raised by the
        display codec.
    """
    # WHY : Refactoring Rationale: this used to be a presence predicate followed by handing the PATH
    #   to the codec, and the pairing had the same two faults the character path had: the predicate
    #   reported a directory, a dangling link, an unreadable parent and a device as "absent", and
    #   the name could change between the look and the codec's own open. The open now happens ONCE,
    #   here, through the shared hardened opener, and the codec is handed the resulting STREAM. Both
    #   faults close together, because the kind of the object is validated on the very descriptor
    #   the bytes are then read from.
    # WHY : Alternatives Considered: keeping the path form and catching FileNotFoundError around the
    #   codec call. Rejected: that guard cannot distinguish the absence of THIS dataset from a
    #   FileNotFoundError raised for any other reason once iteration has begun, and this module's
    #   own predicate documents why making a caller guard an exception type is the wrong shape for a
    #   normal state. Adding an `optional` flag to the codec was also considered and rejected -- it
    #   would widen a shared API used by eleven readers that have no optional input, to serve one
    #   that does.
    # WHY : Trade-offs: opening here means this function owns the close, which the `with` block
    #   below provides, including when a caller abandons the generator part way through. What it
    #   buys is `optional` semantics the codec's path branch does not offer, and the up-front
    #   divisibility check is NOT lost in the trade: `records_of` runs it against the size of the
    #   descriptor just opened, so a truncated dataset still fails before the first record reaches a
    #   loader rather than at the end of the stream, which is where the codec's stream branch would
    #   have to raise.
    # WHY : Assumptions: the same `optional=True` the character path passes is passed here, so the
    #   two encodings agree that a genuinely missing source yields no records and that anything else
    #   is reported. This record legitimately has no committed dataset in EITHER encoding, so
    #   leaving the accommodation on one path only would make the answer depend on which entry point
    #   a caller happened to choose.
    handle = open_regular_binary(path, records_of=TRAN_LAYOUT.reclen, optional=True)
    if handle is None:
        return

    with handle:
        yield from iter_ebcdic_transactions(handle)


def render_masked_transaction_record(record: str) -> str:
    """Render one character record with every sensitive field redacted.

    Purpose
    -------
    Give an operator a privacy-safe, byte-aligned rendering of a whole record, so a failed
    comparison can show a record's shape and which field differs without emitting content whose
    sensitivity the reader cannot judge.

    Parameters
    ----------
    record : str
        One whole record at exactly the declared character width.

    Returns
    -------
    str
        A rendering of exactly the declared width, with each sensitive field replaced by a
        same-width redaction and every other field left verbatim, so offsets can still be counted
        across it.

    Raises
    ------
    RecordLengthError
        If the record is not the declared width. Raised by the shared masking helper, whose width
        check is the reason this rendering can be relied on to stay byte-aligned.
    LayoutError
        If a field slice comes out the wrong width, which the descriptor's own geometry
        validation makes unreachable.
    """
    # WHY : Assumptions: the redaction is delegated to the shared helper rather than applied here,
    #   so marking a field sensitive in the layout remains the ONLY change ever needed to redact
    #   it in this rendering. The amount is redacted to a keyed tag rather than
    #   blanked, so two transactions differing only in amount still render differently and
    #   a reconciliation can locate the row that moved.
    return mask_record(record, TRAN_LAYOUT)


def render_masked_transaction_field(record: str, field_name: str) -> str:
    """Render one named field of a character record with redaction applied.

    Purpose
    -------
    Produce a privacy-safe rendering of a single field, for a diagnostic that needs to show one
    field rather than a whole record.

    Parameters
    ----------
    record : str
        One whole record at exactly the declared character width.
    field_name : str
        The field name exactly as the copybook spells it, including any baseline misspelling,
        since the descriptor preserves the copybook's own spelling.

    Returns
    -------
    str
        A rendering of exactly that field's declared width: the characters verbatim when the field
        is not sensitive, otherwise a same-width redaction.

    Raises
    ------
    LayoutError
        If no field of that name is declared, or the sliced span is not the field's declared
        width, which happens when the record is short.
    """
    # WHY : Assumptions: the field is resolved through the layout by name and then sliced by its
    #   own declared span, so this module still states no offset of its own and an unknown name
    #   fails loudly here rather than silently rendering the wrong bytes. Resolution is scoped to
    #   THIS record's descriptor rather than to a package-wide field table, which is what keeps a
    #   name declared over different children in a sibling copybook from resolving to the wrong
    #   geometry.
    field = TRAN_LAYOUT.field(field_name)
    return mask_field(field, record[field.start : field.end])
