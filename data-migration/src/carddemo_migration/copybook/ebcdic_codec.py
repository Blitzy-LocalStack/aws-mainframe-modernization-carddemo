"""Character decoder for the CardDemo mainframe extracts, applied one field at a time.

Purpose
-------
This module is the ONE AND ONLY place in the repository where EBCDIC is turned into
characters. It opens a dataset READ-ONLY IN BINARY MODE, slices it strictly by the
record length its layout declares, and then decodes EACH FIXED-WIDTH FIELD SEPARATELY
through the cp037 code page. It never decodes a whole record, it never looks for a line
terminator, and it never hands a packed or binary span to a character decoder at all.

Everything else the package needs from these bytes is somebody else's job, and the
boundaries are drawn deliberately. Byte geometry -- offsets, lengths, storage regimes,
record boundaries -- belongs to ``carddemo_migration.copybook.layouts``, which this
module imports and never restates. Trailing-sign overpunch interpretation belongs to
``carddemo_migration.copybook.zoned``. ``COMP-3`` and ``COMP`` interpretation belongs to
``carddemo_migration.copybook.packed``. Choosing a dataset, opening it and closing it
belongs to a reader or a loader. This module converts bytes to characters and does
nothing else.

Why per field, and never per record
-----------------------------------
Decoding a whole record through a character codec is the single most likely
implementation mistake in this package, and the reason it is worth stating this bluntly
is that getting it wrong does not raise. It produces data that looks almost right.

The proof is arithmetic rather than opinion, and it was measured from the shipped bytes
of ``app/data/EBCDIC/AWS.M2.CARDDEMO.EXPORT.DATA.PS``:

* The dataset is 250000 bytes, which is 500 records of 500 bytes with remainder 0.
* It contains exactly 5 bytes of value 0x0A and 4153 bytes of value 0x00.
* Cutting it on the byte 0x0A yields SIX pieces. The correct answer is 500 records.
  A reader that treats 0x0A as a boundary therefore collapses 500 records into 6 chunks
  of wildly differing lengths, and four of the five cuts fall through the middle of a
  field.
* Every one of those five 0x0A bytes is DATA. Two of them are the low-order byte of
  ``EXPORT-SEQUENCE-NUM PIC 9(9) COMP`` -- declared at ``app/cpy/CVEXPORT.cpy`` line 16,
  four bytes big-endian at zero-based offset 27 -- which decodes to clean consecutive
  integers: record 9 holds 10, being 0x0000000A, and record 265 holds 266, being
  0x0000010A. The other three are the low-order byte of a payload ``COMP`` identifier
  that happens to hold the value 10: ``EXP-CUST-ID`` in record 9, ``EXP-XREF-ACCT-ID``
  in record 113 and ``EXP-CARD-ACCT-ID`` in record 463.
* ``AWS.M2.CARDDEMO.DALYTRAN.PS.INIT`` is a second concrete reason: it is one 350-byte
  record of which 342 bytes are 0x00, including both of its 26-byte timestamps.

A related measurement decides how an unwritten timestamp is reported, and it is the
ordinary case rather than an edge one: all 300 records of ``AWS.M2.CARDDEMO.DALYTRAN.PS``
carry 26 blanks in ``DALYTRAN-PROC-TS``, because the posting run is what writes that stamp
and the extract is its input, while all 300 carry a populated ``DALYTRAN-ORIG-TS``. A
blank stamp is therefore a legitimate value that :func:`decode_timestamp` reports as
absent; treating it as a fault would refuse every record of that dataset.

One consequence of the primer file is worth stating before it surprises somebody. Its 342
low values are not a value in any regime: the character decode of every one of its fields
succeeds and yields no replacement character, because cp037 defines all 256 byte values,
but its amount field holds eleven low values where a display-numeric contract requires
eleven digits, so asking the display codec to interpret it as money is correctly REFUSED
rather than answered with zero. That is the intended behaviour and not a gap. The file is
a load primer that initialises an empty dataset, never a business record -- the daily
transaction extract itself is ``AWS.M2.CARDDEMO.DALYTRAN.PS`` -- and inventing a zero
amount for an uninitialised field is precisely the plausible-wrong-value outcome this
module exists to prevent.

The correctness check is therefore the DIVISION and never the presence or absence of a
line terminator. All thirteen datasets under ``app/data/EBCDIC`` divide by their
declared record length with remainder 0, which is why a record count derived from a byte
size can be trusted without reading a byte of content.

The thirteen datasets, measured
-------------------------------
Record lengths come from ``carddemo_migration.copybook.layouts``; the byte sizes, record
counts and byte-value counts below were measured from the shipped files::

    dataset                             reclen     bytes  records   0x0A    0x00
    AWS.M2.CARDDEMO.ACCTDATA.PS            300     15000       50      0       0
    AWS.M2.CARDDEMO.ACCDATA.PS             300     15000       50      0       0
    AWS.M2.CARDDEMO.CARDDATA.PS            150      7500       50      0       0
    AWS.M2.CARDDEMO.CARDXREF.PS             50      2500       50      0       0
    AWS.M2.CARDDEMO.CUSTDATA.PS            500     25000       50      0       0
    AWS.M2.CARDDEMO.DALYTRAN.PS            350    105000      300      0       0
    AWS.M2.CARDDEMO.DALYTRAN.PS.INIT       350       350        1      0     342
    AWS.M2.CARDDEMO.DISCGRP.PS              50      2550       51      0       0
    AWS.M2.CARDDEMO.EXPORT.DATA.PS         500    250000      500      5    4153
    AWS.M2.CARDDEMO.TCATBALF.PS             50      2500       50      0       0
    AWS.M2.CARDDEMO.TRANCATG.PS             60      1080       18      0       0
    AWS.M2.CARDDEMO.TRANTYPE.PS             60       420        7      0       0
    AWS.M2.CARDDEMO.USRSEC.PS               80       800       10      0       0

``AWS.M2.CARDDEMO.ACCDATA.PS`` and ``AWS.M2.CARDDEMO.ACCTDATA.PS`` are BYTE-IDENTICAL --
same size and same SHA-256 -- so the shorter name is a duplicate of the account master
and not a different layout. It is recorded here because a reader who assumed otherwise
would go looking for a copybook that does not exist.

This module is the first and only transcoding boundary in the repository
-----------------------------------------------------------------------
The existing parity oracle deliberately treats these datasets as OPAQUE BINARY and never
transcodes them at all, and its own comments say why. At
``tests/helpers/localstack_setup.py`` line 729 a file-referenced payload is staged
straight from its path because its raw bytes, "including binary EBCDIC with NULs and
overpunch sign bytes", are uploaded verbatim. At line 741 no temporary copy is made
because round-tripping such a dataset through a text write would corrupt it. At line
1050 the read-back helper asserts an exact byte and SHA-256 match, which the comment
describes as "something a text decode would corrupt". At line 1068 the text-returning
variant is documented as mangling binary EBCDIC, with invalid input becoming the U+FFFD
replacement character.

So nothing upstream of this module has ever converted these bytes, and nothing else in
this package converts them either. That is precisely why the boundary has to be exactly
one place and has to work one field at a time.

One word, two unrelated meanings
--------------------------------
Two entirely distinct concepts share the word "EBCDIC" in this repository, and a reader
who fuses them will silently corrupt every negative balance in the corpus.

* In ``carddemo_migration.copybook.zoned`` and at ``tests/README.md`` lines 273 and 274,
  "EBCDIC" names a SIGN CONVENTION. That file's line 268 records the house compile
  invocation as ``cobc -fixed -fsign=EBCDIC --std=ibm-strict -I app/cpy`` and states that
  the flag is required because the ASCII default misreads the zoned-decimal sign
  overpunch and silently corrupts negative balances.
* Here, "EBCDIC" names a CHARACTER ENCODING, specifically the cp037 code page. It decides
  which byte value is which character. It has no opinion about signs whatsoever.

Compounding the collision, ``tests/helpers/record_codec.py`` line 135 calls the overpunch
mapping "the canonical IBM ASCII trailing-sign mapping". Both names are correct: the
overpunch characters themselves are ASCII-printable, which is what that name describes,
while the convention those characters implement is the EBCDIC one, which is what the
compiler flag describes. A matching note sits in ``zoned`` where the two modules meet.

The two are nevertheless connected by one measured fact, and it is the reason a zoned
span may be handed through cp037 at all: cp037 maps byte values 0xC0 to 0xC9 onto the
characters ``{ABCDEFGHI``, 0xD0 to 0xD9 onto ``}JKLMNOPQR`` and 0xF0 to 0xF9 onto
``0123456789`` -- which is exactly, character for character, the two overpunch tables
``zoned`` is written against. One field's worth of cp037 is therefore the character
boundary ``zoned`` documents needing, and no other transformation is involved.

What this module deliberately does not do
-----------------------------------------
* It does not normalise a timestamp. The ``normalize_ts`` flag MARKS which fields a
  golden-master comparison may blank; producing that rendering is the comparison's job.
* It does not mask anything. The ``sensitive`` flag MARKS a field whose bytes must stay
  out of diagnostics. Masking, encryption and account-number truncation belong to the
  domain projection. The one thing this module does with the flag is refuse to render a
  sensitive field's content in an exception message.
* It does not read an environment variable, configure logging, or do any work at import
  time beyond one guarded, optional codec registration.
* It does not write. Every dataset it touches under ``app/`` is INPUT: opened read-only
  in binary mode, never rewritten, never re-encoded, never converted in place.

No value from any dataset is reproduced anywhere in this module -- not in a docstring,
not in a comment, not in a doctest. In particular the eight-byte password the baseline
stores in clear is a byte range and nothing more.
"""

from __future__ import annotations

import codecs
import pathlib
from collections.abc import Iterable, Iterator
from decimal import Decimal
from types import ModuleType
from typing import Final

from carddemo_migration.copybook.layouts import (
    FieldSpec,
    Kind,
    RecordLengthError,
    RecordSpec,
    count_fixed_length_records,
    iter_fixed_length_records,
)
from carddemo_migration.copybook.packed import decode_binary_field, decode_packed_field
from carddemo_migration.copybook.zoned import decode_zoned

# WHY (Alternatives Considered): the obvious spelling is a plain module-level
#   ``import ebcdic``, and it is refused. ``data-migration/requirements.txt`` pins
#   ``ebcdic==2.0.1`` and states at that pin what it is for: it registers the WIDER EBCDIC
#   code-page family around cp037 -- roughly 35 pages including cp1047 and the euro-updated
#   cp1141 through cp1149 -- which CPython does not ship. It also states what the pin is NOT
#   for, and that is the decisive part: cp037 ITSELF IS A STANDARD-LIBRARY CODEC. Measured
#   with the distribution absent from the interpreter, ``codecs.lookup("cp037")`` resolves and
#   returns the codec, and all thirteen datasets decode. So an unconditional import would buy
#   nothing for today's corpus while costing the guarantee the package publicly makes.
# WHY (Trade-offs): the guard accepts one ``try``/``except`` in exchange for
#   bare-checkout importability. ``carddemo_migration/__init__.py`` publishes that
#   ``carddemo_migration.copybook`` is standard library only and imports on a bare checkout
#   with no database driver and no AWS SDK present, and ``tests/helpers/record_codec.py``
#   states the same discipline in its own module docstring, restricting itself to the standard
#   library "so that even a bare checkout, before any test dependency is installed, can import
#   and use it". An unconditional import would make ``import carddemo_migration.copybook``
#   fail outright on such a checkout -- for a package that is not needed to decode any dataset
#   the repository actually ships. The pin therefore stays, is used the moment it is present,
#   and costs nothing when it is not: the guard buys importability and loses no capability.
# WHY (Assumptions): the module object is BOUND to a name below rather than imported
#   and forgotten. That is deliberate. The distribution exposes no symbol this package calls,
#   so an unbound import reads as removable to a linter and to a person tidying imports, and
#   ``requirements.txt`` records that hazard at the pin as well. Retaining the reference makes
#   the import genuinely used, so no suppression comment is needed to keep it alive.
try:
    import ebcdic
except ImportError:
    _EXTENDED_CODE_PAGE_MODULE: ModuleType | None = None
else:
    _EXTENDED_CODE_PAGE_MODULE = ebcdic

# WHY (Assumptions): the public surface is declared explicitly and in sorted order so a
#   consumer's import list can be checked against it mechanically, matching the convention the
#   three sibling modules in this subpackage already follow.
__all__ = [
    "EBCDIC_CODE_PAGE",
    "EXTENDED_CODE_PAGES_REGISTERED",
    "EbcdicFieldDecodeError",
    "EbcdicRecordLengthError",
    "decode_field",
    "decode_field_characters",
    "decode_record",
    "decode_timestamp",
    "iter_ebcdic_records",
    "trim_trailing_blanks",
]

# WHY (Assumptions): cp037 is the code page the shipped extracts are in, and it is named
#   as a constant rather than written at each decode site so there is one place to read it and
#   one place a caller can compare against. It is a Python standard-library codec: verified by
#   resolving it with the optional distribution above absent from the interpreter.
EBCDIC_CODE_PAGE: Final[str] = "cp037"

# WHY (Assumptions): the outcome of the guarded import is published as a plain boolean
#   because a caller that wants a sibling code page needs to know whether the family is
#   registered BEFORE it asks for one, and the alternative -- catching the decode failure
#   afterwards -- reports the problem one layer away from the configuration that caused it.
EXTENDED_CODE_PAGES_REGISTERED: Final[bool] = _EXTENDED_CODE_PAGE_MODULE is not None

# WHY (Assumptions): errors are handled strictly rather than by substitution, and the
#   handler is named at every call rather than left to default. A substituting handler is
#   exactly the failure ``tests/helpers/localstack_setup.py`` line 1068 records -- a decode
#   that turns an uninterpretable byte into U+FFFD while PRESERVING the character count, so
#   the record still has its declared width, every later offset still looks valid, and only
#   the content is wrong. cp037 happens to define all 256 byte values, so strict handling
#   cannot fire for it; the handler is stated anyway because a sibling code page selected
#   through ``code_page`` need not be complete, and the guarantee has to hold for that case
#   too rather than only for the one page measured today.
_STRICT: Final[str] = "strict"

# WHY (Assumptions): the blank in these extracts is the cp037 space, byte 0x40, which
#   decodes to an ordinary space. It is the pad character a short value is filled with, so it
#   is what trailing-blank trimming removes and what an absent timestamp consists of.
_BLANK: Final[str] = " "

# WHY (Assumptions): the low value is byte 0x00, which cp037 decodes to U+0000 rather
#   than to a replacement character. It is treated as a SECOND form of absence for a timestamp
#   and NEVER as trailing padding to be trimmed, because the two are different facts about the
#   data: ``AWS.M2.CARDDEMO.DALYTRAN.PS.INIT`` is a primer record of 350 bytes in which 342
#   are 0x00, covering both of its 26-byte timestamps, so those stamps were never written at
#   all; whereas a trailing run of low values inside a text field is content this module has no
#   licence to discard, since discarding it would silently change the field's length.
_LOW_VALUE: Final[str] = "\x00"


# WHY (Assumptions): both error types below exist because every validation in this module
#   RAISES and none asserts. ``python -O`` removes ``assert`` statements from the compiled code
#   outright, so an assertion is not a validation at all -- it is a check that is present while
#   a developer runs the tests and absent in the deployment where a misaligned money value
#   actually costs money. The two sibling numeric codecs and the reference codec reach the same
#   conclusion and record it explicitly, so the discipline is uniform across the package rather
#   than a habit of one module.
class EbcdicRecordLengthError(RecordLengthError):
    """Error raised when a dataset or a record does not match its declared width.

    Purpose
    -------
    Signal that raw bytes presented to this module do not divide into whole records of the
    length their layout declares: a dataset whose byte size is not an exact multiple of
    ``reclen``, a source that ends part-way through a record, or a single record handed to
    the record decoder at the wrong width. It carries the dataset identity that the
    underlying geometry check cannot know, so the message names WHICH file failed as well
    as the expected record length, the observed size and the remainder.

    Assumptions: this is a data fault and never a repair opportunity. A financial record of
    the wrong width is corrupt input, and reading it anyway would post a misaligned money
    value that no later check can detect, so the length contract is enforced by raising.
    The remainder is reported because it is the number of bytes by which the dataset is
    malformed and is the first thing an operator needs in order to tell a truncated
    transfer from a wrong record length.

    Alternatives Considered: subclassing :class:`ValueError` directly, which would have made
    this module's error taxonomy independent of the layouts module's. Rejected because the
    fault is the very one ``layouts.RecordLengthError`` already names, and a caller that
    guards record-length faults with that type would then silently stop catching the same
    fault the moment it went through this module. Subclassing keeps one catchable type for
    one class of fault while still letting a caller catch this narrower one to learn the
    dataset identity. It remains a :class:`ValueError` subclass transitively, so a caller
    guarding with ``except ValueError`` keeps working either way.

    Assumptions: it is a SEPARATE type from :class:`EbcdicFieldDecodeError` because the two
    report different classes of fault. A record-length error says the bytes handed in are
    not the shape the layout describes; a field-decode error says a span of the right shape
    could not be turned into characters. Fusing them would leave a caller unable to tell a
    truncated dataset from an unregistered code page.

    Parameters
    ----------
    args : tuple
        Standard :class:`ValueError` positional arguments, in practice a single
        human-readable message naming the source, the declared record length, the observed
        size and the remainder or shortfall that failed to close.

    Returns
    -------
    EbcdicRecordLengthError
        A new exception instance.

    Raises
    ------
    None.
    """


class EbcdicFieldDecodeError(ValueError):
    """Error raised when one field span cannot be turned into characters.

    Purpose
    -------
    Signal that a per-field decode could not be performed: the record does not reach the
    field's declared end, the requested code page is not registered with the interpreter,
    the span holds byte values that page leaves undefined, or the field declares a storage
    regime this module has no rule for. It is the single decode fault type, so a caller
    guards one name rather than three.

    Assumptions: every validation in this module raises and none asserts. ``python -O``
    strips ``assert`` statements outright, so an assertion is not a validation but a check
    that disappears in exactly the deployment where a misdecoded field costs money. The
    sibling codecs and the reference codec reach the same conclusion and record it
    explicitly.

    Assumptions: for a field marked :attr:`FieldSpec.sensitive` the message names ONLY the
    field's name, its zero-based start, its exclusive end and its storage regime, and never
    echoes the span's bytes or a hexadecimal rendering of them. The reference implementation
    interpolates the offending raw value into its own decode failure text, which suits a
    harness reading committed fixtures; this module deliberately does not for a sensitive
    field, because the same message here can reach a production log carrying a primary
    account number or an eight-byte password. The divergence is documented rather than
    silent, and it is a difference in what is REPORTED, never in what is rejected.

    Parameters
    ----------
    args : tuple
        Standard :class:`ValueError` positional arguments, in practice a single
        human-readable message naming the violated contract, the field's content-free
        geometry, and the span content only where the field permits disclosure.

    Returns
    -------
    EbcdicFieldDecodeError
        A new exception instance.

    Raises
    ------
    None.
    """


def _renderable_hex(span: bytes | None, field: FieldSpec) -> str | None:
    """Return a hexadecimal rendering of a span only where the field permits disclosure.

    Purpose
    -------
    Centralise the fail-closed gate every diagnostic in this module passes through, so that
    a sensitive field's bytes can never reach an exception message by any route.

    Trade-offs: a non-sensitive field's span IS rendered, in hexadecimal, because a decode
    failure that names only an offset gives an operator nothing to act on -- the whole point
    of the report is to show WHICH byte the code page has no character for. A sensitive
    field gets nothing, so its diagnostic shows WHERE the record failed without emitting a
    card number, an identity or a password. That asymmetry is the compromise this function
    exists to make: full diagnosability where disclosure is harmless, geometry only where it
    is not.

    Assumptions: hexadecimal is used rather than a character rendering because at this point
    the bytes have by definition failed to become characters, so there is no character
    rendering to give. The masking helper the layouts module offers for the character path,
    ``mask_field``, operates on ``str`` and is therefore not applicable here; that module's
    own documentation designates :meth:`FieldSpec.describe` as the sensitive-safe rendering
    for the byte path, and that is what :func:`_failure` uses for the geometry half.

    Parameters
    ----------
    span : bytes | None
        The exact field bytes under consideration, or ``None`` when the failure has no span
        to render.
    field : FieldSpec
        The descriptor whose ``sensitive`` flag governs disclosure.

    Returns
    -------
    str | None
        A hexadecimal rendering of the span for a non-sensitive field, otherwise ``None``.

    Raises
    ------
    None.
    """
    # WHY (Trade-offs): the gate is closed on the SENSITIVE flag and on nothing else, which
    #   is what lets a diagnostic show WHERE a record failed without ever emitting a card number,
    #   a cardholder identity or the eight-byte password the baseline stores in clear. The
    #   compromise is asymmetric on purpose: a non-sensitive span is disclosed in full because a
    #   report that named only an offset would not tell an operator which byte the code page had
    #   no character for, and a sensitive span is disclosed not at all because this message can
    #   reach a production log. Fail-closed ordering matters too -- the sensitivity test comes
    #   before any rendering, so a rendering is never built and then discarded.
    if span is None or field.sensitive:
        return None
    return span.hex()


def _failure(reason: str, *, field: FieldSpec, span: bytes | None = None) -> str:
    """Build one sensitive-safe diagnostic from a reason and a field context.

    Purpose
    -------
    Give every exception this module raises the same ordering and the same disclosure
    policy: the violated contract first, then the field's content-free geometry, then the
    span itself only where :func:`_renderable_hex` permits it.

    Assumptions: the geometry half comes from :meth:`FieldSpec.describe`, which renders a
    field as its name, its half-open byte interval and its storage regime and carries no
    content at all. The layouts module documents that method as the sensitive-safe rendering
    for exactly this use, so the policy is inherited rather than reinvented -- and inheriting
    it means a future component added to the descriptor cannot start leaking into these
    messages, because that method names four attributes explicitly instead of printing
    whatever the descriptor happens to hold.

    Parameters
    ----------
    reason : str
        A content-free explanation of the contract that was violated.
    field : FieldSpec
        The descriptor supplying safe geometry and the sensitivity decision.
    span : bytes | None
        The exact field bytes considered for fail-closed rendering, or ``None`` when the
        failure has no span.

    Returns
    -------
    str
        A complete diagnostic that is safe to log for the supplied field context.

    Raises
    ------
    None.
    """
    message = f"{reason}; field={field.describe()}"
    rendered = _renderable_hex(span, field)
    if rendered is not None:
        message = f"{message}; bytes={rendered}"
    return message


def _codec_for(code_page: str, field: FieldSpec) -> codecs.CodecInfo:
    """Resolve one code page to its registered codec, or refuse the decode.

    Purpose
    -------
    Turn a code-page name into the codec that implements it, and translate a name the
    interpreter does not know into this module's own decode error so a caller guards one
    type instead of two.

    Assumptions: the lookup happens per call rather than once at import, and both halves of
    that matter. Per call is what lets ``code_page`` select a sibling page at run time --
    the capability ``data-migration/requirements.txt`` records at the ``ebcdic`` pin, namely
    that an extract in another page decodes through this identical per-field path by
    configuration alone with no edit to this module. Not at import is what keeps the ONLY
    import-time side effect this module has the one guarded codec registration near the top
    of the file. The cost is nil in practice because the codec registry caches its own
    lookups, so a repeated resolution is a dictionary hit.

    Trade-offs: an unregistered page is reported as a decode failure rather than as a
    configuration failure of its own type. The message therefore has to carry the remedy,
    and it does: it names the page that failed and states that pages beyond cp037 come from
    the optional distribution. The compromise buys a caller a single ``except`` for every
    reason a field can fail to become characters.

    Parameters
    ----------
    code_page : str
        The codec name to resolve, for example :data:`EBCDIC_CODE_PAGE`.
    field : FieldSpec
        The descriptor being decoded, used only to render safe geometry in a failure.

    Returns
    -------
    codecs.CodecInfo
        The registered codec for ``code_page``.

    Raises
    ------
    EbcdicFieldDecodeError
        If ``code_page`` is not registered with the interpreter's codec registry.
    """
    try:
        return codecs.lookup(code_page)
    except LookupError as exc:
        raise EbcdicFieldDecodeError(
            _failure(
                f"code page {code_page!r} is not registered with this interpreter, so no"
                " character decode is possible; cp037 is a standard-library codec, while the"
                " wider EBCDIC family is registered by the optional ebcdic distribution"
                f" (registered={EXTENDED_CODE_PAGES_REGISTERED})",
                field=field,
            )
        ) from exc


def _require_record_bytes(record: object, field: FieldSpec) -> bytes:
    """Require a byte image at the record boundary and never encode one into existence.

    Purpose
    -------
    Refuse anything that is not a byte image before a single offset is taken, and normalise
    the three byte shapes a caller may legitimately hold into ``bytes`` for slicing.

    Assumptions: a ``str`` is refused OUTRIGHT rather than encoded. A record that has
    already become characters has already been through a decoder, and encoding it back to
    guess at the original bytes is precisely the round trip this module exists to prevent --
    it is the operation the parity oracle warns about at
    ``tests/helpers/localstack_setup.py`` line 741, where writing such a dataset through a
    text path corrupts it. There is no encoding this function could pick that would be
    right, so it picks none.

    Trade-offs: ``memoryview`` and ``bytearray`` are accepted and copied into ``bytes``. The
    copy costs one allocation per record, and what it buys is that every offset taken
    downstream is taken against an immutable object, so a caller that keeps writing into its
    own buffer cannot change a record this module has already yielded a value for.

    Parameters
    ----------
    record : object
        The candidate record image, expected to be ``bytes``, ``bytearray`` or a
        ``memoryview`` over single bytes.
    field : FieldSpec
        The descriptor being decoded, used only to render safe geometry in a failure.

    Returns
    -------
    bytes
        The record as an immutable byte image.

    Raises
    ------
    TypeError
        If ``record`` is a ``str``, any type other than a byte image, or a ``memoryview``
        that is not a one-dimensional image of single bytes. A wrong TYPE is reported as
        :class:`TypeError` rather than as this module's decode error, matching the two
        sibling numeric codecs, which both raise :class:`TypeError` for the same fault; the
        module's own decode error is reserved for a span of the right type that cannot be
        turned into characters. The message still goes through :func:`_failure`, so it
        carries safe geometry and obeys the sensitive-field disclosure gate.
    """
    if isinstance(record, str):
        raise TypeError(
            _failure(
                "a record must be a byte image read in binary mode, but a str was given;"
                " character data has already been through a decoder and is never encoded"
                " back here to guess at its original bytes",
                field=field,
            )
        )
    if not isinstance(record, (bytes, bytearray, memoryview)):
        raise TypeError(
            _failure(
                "a record must be bytes, bytearray or memoryview, but was a"
                f" {type(record).__name__}",
                field=field,
            )
        )
    try:
        return bytes(record)
    except (TypeError, ValueError) as exc:
        raise TypeError(
            _failure(
                "a record must be a one-dimensional image of single bytes, but converting it"
                f" failed: {exc}",
                field=field,
            )
        ) from exc


def _require_span(record: bytes, field: FieldSpec) -> bytes:
    """Return the exact declared bytes of one field, or refuse a record that is too short.

    Purpose
    -------
    Slice ``record[field.start:field.end]`` and prove the slice is the full declared width,
    so that every value this module produces is anchored on the copybook's own offset.

    Assumptions: a short record is refused rather than padded. Padding is a tolerance the
    layouts module grants at its ASCII TEXT-LINE ingest boundary only, for a measured
    reason that does not apply here -- a shipped ASCII seed omits a trailing filler -- and
    granting it on this path would let a truncated binary dataset decode to plausible values
    for every field that survived the cut. Python slicing is silent about running past the
    end, so this check is the only thing standing between a truncated image and a value.

    Parameters
    ----------
    record : bytes
        The whole fixed-width record image.
    field : FieldSpec
        The descriptor supplying the zero-based start and the exclusive end.

    Returns
    -------
    bytes
        Exactly ``field.length`` bytes taken from the field's declared position.

    Raises
    ------
    EbcdicFieldDecodeError
        If ``record`` ends before the field's exclusive end.
    """
    span = record[field.start : field.end]
    if len(span) != field.length:
        raise EbcdicFieldDecodeError(
            _failure(
                f"record ends at offset {len(record)}, before the field's exclusive end"
                f" {field.end}; only {len(span)} of {field.length} bytes are available",
                field=field,
            )
        )
    return span


# WHY (Assumptions): this function is the FIRST AND ONLY transcoding boundary in the
#   repository, and that is a fact about the existing tree rather than an ambition for this one.
#   The parity oracle deliberately treats these datasets as opaque binary and never converts
#   them: ``tests/helpers/localstack_setup.py`` line 729 stages a dataset straight from its path
#   so its raw bytes, NULs and overpunch sign bytes included, are uploaded verbatim; line 741
#   makes no temporary copy because a text write would corrupt the image; line 1050 reads the
#   bytes back to assert an exact SHA-256 match, which the comment describes as something a text
#   decode would corrupt; and line 1068 records that the text-returning helper mangles binary
#   EBCDIC into replacement characters. Nothing upstream has ever converted these bytes, so the
#   conversion has exactly one home, and this is it.
def _decode_span(span: bytes, field: FieldSpec, code_page: str) -> str:
    """Decode one field's bytes to characters through a single code page.

    Purpose
    -------
    Perform the one and only character conversion in this package: exactly one field's worth
    of bytes, through exactly one code page, with strict error handling.

    Assumptions: the unit of conversion is the FIELD and never the record, and this function
    is where that is made structurally true -- it takes a span, so there is no signature
    through which a whole record could reach a character decoder. Handing a record instead
    would route sign bytes, packed nibbles and low values through the codec: measured on
    ``AWS.M2.CARDDEMO.EXPORT.DATA.PS`` that means 4153 bytes of 0x00 and the packed and
    binary spans of five different payload branches, and on
    ``AWS.M2.CARDDEMO.DALYTRAN.PS.INIT`` it means 342 of 350 bytes. The output would not be
    obviously wrong, which is the whole danger: the record keeps its declared width and
    every field after the damage still parses.

    Trade-offs: the caller pays one call per field rather than one per record. That is the
    documented cost of routing every decode through one place -- a reader cannot take a
    shortcut for a field it believes is plain text -- and what it buys is a single site to
    review, a single site to test against known-answer vectors, and no second implementation
    that can drift.

    Parameters
    ----------
    span : bytes
        Exactly the field's declared bytes, already sliced by :func:`_require_span`.
    field : FieldSpec
        The descriptor being decoded, used to render safe geometry in a failure.
    code_page : str
        The code page to decode through, normally :data:`EBCDIC_CODE_PAGE`.

    Returns
    -------
    str
        The decoded characters, one per byte for a single-byte code page, so the result is
        ``field.length`` characters wide for every page in the EBCDIC family.

    Raises
    ------
    EbcdicFieldDecodeError
        If ``code_page`` is not registered, or if the span holds a byte value that page
        leaves undefined.
    """
    codec = _codec_for(code_page, field)

    # WHY (Assumptions): the unit handed to the codec is ONE FIELD'S SPAN and never a
    #   record, and the measurement that makes the difference concrete is
    #   ``AWS.M2.CARDDEMO.EXPORT.DATA.PS``: 250000 bytes, 500 records of 500 bytes, remainder 0,
    #   containing exactly 5 bytes of value 0x0A and 4153 bytes of value 0x00. Cutting that image
    #   on the byte 0x0A yields SIX pieces where the correct answer is 500 records. Two of the
    #   five are the low-order byte of ``EXPORT-SEQUENCE-NUM PIC 9(9) COMP``, four bytes
    #   big-endian at zero-based offset 27, which decodes to 10 in record 9 as 0x0000000A and to
    #   266 in record 265 as 0x0000010A; the other three are the low-order byte of a payload
    #   ``COMP`` identifier holding 10. Passing a record here instead would push all 4153 low
    #   values and every packed and binary span through this call, and the result would not look
    #   like damage -- the record would keep its declared width and every field after the damage
    #   would still parse. Only the amounts would be wrong.
    # WHY (Trade-offs): the accepted cost is one call per field rather than one per record,
    #   so a reader cannot take a shortcut for a field it believes is plain text. What that buys
    #   is one place to review, one place to test against known-answer vectors, and no second
    #   implementation of the conversion anywhere in the package that could drift from this one.
    try:
        decoded, _consumed = codec.decode(span, _STRICT)
    except UnicodeDecodeError as exc:
        raise EbcdicFieldDecodeError(
            _failure(
                f"code page {code_page!r} leaves a byte value in this field undefined at"
                f" zero-based span index {exc.start}",
                field=field,
                span=span,
            )
        ) from exc
    return decoded


# WHY (Assumptions): the three sets below are ALLOW-LISTS, and that is what makes the
#   per-field dispatch exhaustive by construction rather than by inspection. Their union is
#   the whole of Kind today, so a member added to that enumeration tomorrow belongs to none of
#   them and reaches the trailing raise in each dispatch instead of being silently swept into
#   whichever branch happened to be last. The alternative -- an if/elif chain ending in an
#   else that decodes -- would give a new packed-like regime a character decode by default,
#   which is exactly the failure this module exists to prevent.
_CHARACTER_KINDS: Final[frozenset[Kind]] = frozenset({Kind.TEXT, Kind.UINT})

# WHY (Trade-offs): a ZONED, PACKED or BINARY span leaves this module AS RAW BYTES, and
#   the dispatch that decides so happens BEFORE any code page is applied. The compromise is
#   that a caller wanting a number has to make a second call, and what it buys is that a
#   packed nibble pair or a binary word can never reach a character decoder by any path. The
#   export record is why this is not theoretical: within one 500-byte image
#   ``app/cpy/CVEXPORT.cpy`` declares the SAME PIC S9(10)V99 clause three times at three
#   different widths -- COMP-3 at seven bytes on line 50, display at twelve on line 51 and
#   COMP at eight on line 57 -- so packed, binary and zoned spans sit side by side inside a
#   single record and only the descriptor can tell them apart.
_BYTE_KINDS: Final[frozenset[Kind]] = frozenset({Kind.ZONED, Kind.PACKED, Kind.BINARY})

# WHY (Assumptions): ZONED joins the two character kinds here and ONLY here, because a
#   zoned span is genuinely character data -- one printable digit per byte with the sign folded
#   into the low-order digit -- and ``zoned.decode_zoned`` documents its input as the exact
#   characters of one field, already converted at the character boundary. This module is that
#   boundary. The conversion is faithful rather than lossy: cp037 maps byte values 0xC0 to
#   0xC9 onto the characters {ABCDEFGHI, 0xD0 to 0xD9 onto }JKLMNOPQR and 0xF0 to 0xF9 onto
#   0123456789, which is character for character the two overpunch tables that module is
#   written against. PACKED and BINARY are deliberately absent: their bytes are not characters
#   in any code page, so transcoding one is not a conversion but a corruption.
_TRANSCODABLE_KINDS: Final[frozenset[Kind]] = _CHARACTER_KINDS | {Kind.ZONED}


def _require_record_image(record: object, layout: RecordSpec) -> bytes:
    """Require a whole record of exactly the width its layout declares.

    Purpose
    -------
    Normalise a record image to ``bytes`` and prove it is the declared record length before
    any field is taken from it, so that every offset in the layout is guaranteed reachable.

    Assumptions: the width is checked here rather than trusted from the iterator, because a
    record may reach the record decoder from a fixture, a test vector or a pipeline stage
    that never went through the iterator at all. A record of the wrong width would otherwise
    decode every field that happened to fit and fail only on the last one, or on none.

    Parameters
    ----------
    record : object
        The candidate record image, expected to be ``bytes``, ``bytearray`` or a
        ``memoryview`` over single bytes.
    layout : RecordSpec
        The record descriptor supplying the declared length and the name to report.

    Returns
    -------
    bytes
        The record as an immutable byte image of exactly ``layout.reclen`` bytes.

    Raises
    ------
    TypeError
        If ``record`` is a ``str`` or any type other than a byte image.
    EbcdicRecordLengthError
        If the image is not exactly ``layout.reclen`` bytes.
    """
    if isinstance(record, str):
        raise TypeError(
            f"record {layout.name} must be a byte image read in binary mode, but a str was"
            " given; character data has already been through a decoder and is never encoded"
            " back here to guess at its original bytes"
        )
    if not isinstance(record, (bytes, bytearray, memoryview)):
        raise TypeError(
            f"record {layout.name} must be bytes, bytearray or memoryview, but was a"
            f" {type(record).__name__}"
        )
    image = bytes(record)
    if len(image) != layout.reclen:
        raise EbcdicRecordLengthError(
            f"record {layout.name} declares {layout.reclen} bytes but {len(image)} were given;"
            " a record of the wrong width is corrupt input and is never padded or truncated"
            " to fit, because a misaligned money value decodes to a plausible wrong number"
            " rather than raising"
        )
    return image


def _iter_path_records(path: pathlib.PurePath, layout: RecordSpec) -> Iterator[bytes]:
    """Yield whole records from a dataset on disk, opened read-only in binary mode.

    Purpose
    -------
    Open one dataset, prove its byte size divides exactly by the declared record length
    BEFORE yielding anything, and then stream it record by record.

    Assumptions: the file is opened in BINARY mode and only for reading, and it is never
    written, re-encoded, converted in place or replaced. Every dataset this module reads
    under ``app/`` is REFERENCE input: it is the behavioural oracle the whole migration is
    verified against, so a byte of it changing would invalidate the comparison the migration
    depends on. Reading is also all this module needs -- the decoded values go to a caller,
    never back to the source.

    Assumptions: the division is the correctness check and the line terminator is not, so the
    size is validated up front by the layouts module's own record count rather than by
    scanning for a boundary byte. All thirteen datasets under ``app/data/EBCDIC`` divide by
    their declared record length with remainder 0, which is what makes a count derived from a
    byte size trustworthy without reading any content; a non-zero remainder means the file is
    truncated or the declared length is wrong, and either way no offset in this package can
    be trusted until it is resolved.

    Trade-offs: the size is taken with one metadata call and validated before the file is
    opened, which costs one extra system call per dataset. What it buys is that a malformed
    dataset raises BEFORE any row reaches a loader, rather than part-way through a partial
    load -- the moment at which the layouts module's streaming form has to raise instead,
    because a stream has no knowable total until it ends.

    Parameters
    ----------
    path : pathlib.PurePath
        The dataset location. Converted to a concrete :class:`pathlib.Path` so that a pure
        path a caller assembled without touching a filesystem is accepted.
    layout : RecordSpec
        The record descriptor supplying the declared record length and the name to report.

    Returns
    -------
    Iterator[bytes]
        Each whole record in order, every one exactly ``layout.reclen`` bytes long.

    Raises
    ------
    EbcdicRecordLengthError
        If the dataset's byte size is not an exact multiple of ``layout.reclen``, or if the
        stream ends part-way through a record.
    OSError
        If the dataset cannot be inspected or opened, which is left to propagate unchanged
        because a missing or unreadable file is an operator's problem and this module can add
        nothing to what the operating system already reports.
    """
    file_path = pathlib.Path(path)
    size = file_path.stat().st_size
    reclen = layout.reclen

    # WHY (Assumptions): the DIVISION is the correctness check, and the presence or absence
    #   of a boundary byte is not. All thirteen datasets under ``app/data/EBCDIC`` divide by their
    #   declared record length with remainder 0 -- 15000 by 300 twice, 7500 by 150, 2500 by 50
    #   twice, 25000 by 500, 105000 by 350, 350 by 350, 2550 by 50, 250000 by 500, 1080 by 60, 420
    #   by 60 and 800 by 80 -- so the record count is derivable from the byte size with nothing in
    #   between and no content read at all. A non-zero remainder means the image is truncated or
    #   the declared length is wrong, and until that is resolved no offset in this package can be
    #   trusted, which is why it is refused here instead of being rounded down.
    try:
        count_fixed_length_records(size, reclen)
    except RecordLengthError as exc:
        raise EbcdicRecordLengthError(
            f"dataset {file_path} holds {size} bytes, which does not divide into whole records"
            f" of the {reclen} bytes record {layout.name} declares; {size % reclen} bytes"
            " remain over, so the dataset is truncated or the record length is wrong"
        ) from exc

    # WHY (Assumptions): the mode string is "rb" and there is no encoding argument, and
    #   both halves are load-bearing. Binary mode is what keeps a byte a byte; a text-mode open
    #   would apply an encoding AND universal newline translation, and this corpus contains the
    #   bytes to prove why that matters -- 4153 low values and 5 bytes of 0x0A inside
    #   AWS.M2.CARDDEMO.EXPORT.DATA.PS, every one of the five being the low-order byte of a
    #   legitimate COMP value rather than a boundary. Read-only is what keeps the reference
    #   dataset a reference dataset.
    with file_path.open("rb") as stream:
        try:
            yield from iter_fixed_length_records(stream, reclen)
        except RecordLengthError as exc:
            raise EbcdicRecordLengthError(
                f"dataset {file_path} ended part-way through a record of the {reclen} bytes"
                f" record {layout.name} declares, having reported {size} bytes: {exc}"
            ) from exc


def iter_ebcdic_records(
    source: pathlib.PurePath | bytes | bytearray | memoryview | Iterable[object],
    layout: RecordSpec,
) -> Iterator[bytes]:
    """Iterate an EBCDIC dataset as whole records, sliced by record length alone.

    Purpose
    -------
    Produce the records of one fixed-length dataset, cut strictly on byte offsets derived
    from the record length its layout declares. This is the ONLY way a record reaches the
    decoders in this module, which is what makes decoding a dataset as though it were text
    structurally impossible rather than merely discouraged.

    Assumptions: no line terminator is looked for, honoured, stripped or padded, at any
    point, in any branch. The datasets are fixed-length blocked images with no terminators at
    all, and the measurement that makes the hazard concrete is
    ``AWS.M2.CARDDEMO.EXPORT.DATA.PS``: cutting its 250000 bytes on the byte 0x0A yields SIX
    pieces where the correct answer is 500 records of 500 bytes, and four of the five cuts
    fall through the middle of a field. Two of those five bytes are the low-order byte of
    ``EXPORT-SEQUENCE-NUM PIC 9(9) COMP``, four bytes big-endian at zero-based offset 27,
    which decodes to 10 in record 9 as 0x0000000A and to 266 in record 265 as 0x0000010A. The
    remaining three are the low-order byte of a payload ``COMP`` identifier holding the value
    10. All five are data.

    Assumptions: the tolerance the layouts module grants its ASCII TEXT-LINE mode -- stripping
    one trailing terminator and right-padding a short line with blanks -- may NEVER reach this
    path. That tolerance exists for measured reasons that are properties of the ASCII
    conversions rather than of the source data: a shipped seed line carries 36 data bytes
    against a 50-byte record because a trailing filler is absent, and three others carry
    carriage returns. Applying either accommodation to a binary dataset is precisely how 500
    records become 6 chunks, so this function reaches a different entry point of that module
    and shares no code with the text one.

    Assumptions: a ``str`` is refused outright rather than being taken for a path. It is the
    one argument shape that is genuinely ambiguous here -- it could be a dataset location or
    it could be character data somebody already decoded -- and guessing wrong in the second
    direction is the corruption this module exists to prevent. A path is therefore given as a
    :class:`pathlib.PurePath`, which cannot be mistaken for content.

    Parameters
    ----------
    source : pathlib.PurePath | bytes | bytearray | memoryview | Iterable[object]
        The dataset, in one of four shapes. A path, which is opened read-only in binary mode,
        streamed and closed, and whose size is validated before the first record is produced.
        A whole byte image the caller already holds, whose length must be an exact multiple of
        the record length. An open binary stream, which is read strictly forward in
        whole-record batches, never seeked and never closed by this module. Or any iterable of
        byte pieces whose boundaries may fall anywhere, including inside a record. A ``str`` is
        refused.
    layout : RecordSpec
        The record descriptor whose ``reclen`` fixes the cut and whose ``name`` is reported in
        a length failure.

    Returns
    -------
    Iterator[bytes]
        Each whole record in order, every one exactly ``layout.reclen`` bytes long.

    Raises
    ------
    TypeError
        If ``source`` is a ``str``.
    EbcdicRecordLengthError
        If the source does not divide into whole records: before the first record for a path
        or a whole image, whose total size is known up front, and at the end of the source for
        a stream or an iterable, whose total is not.
    layouts.LayoutError
        If ``source`` is neither a byte image nor readable nor iterable, or if a piece it
        produces is not a byte object. The layouts module raises this and it is left to
        propagate, because it reports a fault in what the caller supplied and adding a second
        wrapper would only hide the sentence that names it.
    OSError
        If a path cannot be inspected or opened.
    """
    if isinstance(source, str):
        raise TypeError(
            f"reading record {layout.name} requires a pathlib path or a byte source, but a str"
            " was given; a str is ambiguous between a dataset location and character data"
            " somebody has already decoded, so it is refused rather than guessed at"
        )
    # WHY (Assumptions): NONE of the layouts module's ASCII text-line tolerance reaches this
    #   branch or any other branch here. That mode legitimately strips one trailing terminator and
    #   right-pads a short line with blanks, for reasons measured on the ASCII conversions rather
    #   than on the source data: a shipped seed line carries 36 data bytes against a 50-byte record
    #   because its trailing filler is absent, and three other seeds carry 49, 18 and 6 carriage
    #   returns respectively. Applying either accommodation to a binary image is exactly how 500
    #   records become 6 chunks, so this path reaches a different entry point of that module, does
    #   no stripping, does no padding, and has no notion of a line at all.
    if isinstance(source, pathlib.PurePath):
        yield from _iter_path_records(source, layout)
        return

    # WHY (Trade-offs): the slicing itself is DELEGATED to the layouts module rather than
    #   written again here, and the wrapper below exists only to add the identity of the source,
    #   which that module cannot know because it is handed data and not a dataset. Delegating
    #   means there is one implementation of the record cut and one implementation of the
    #   exact-division rule in the package, so the two cannot drift; the cost is that a length
    #   failure arrives as that module's error type and has to be re-raised as this one to carry
    #   the extra sentence. The original is preserved as the cause, so nothing is lost.
    try:
        yield from iter_fixed_length_records(source, layout.reclen)
    except RecordLengthError as exc:
        raise EbcdicRecordLengthError(
            f"an in-memory source for record {layout.name} does not divide into whole records"
            f" of {layout.reclen} bytes: {exc}"
        ) from exc


def decode_field(
    record: bytes | bytearray | memoryview,
    field: FieldSpec,
    *,
    code_page: str = EBCDIC_CODE_PAGE,
) -> str | bytes:
    """Decode one field of a record, dispatching on its storage regime before any code page.

    Purpose
    -------
    Take exactly the bytes one field declares and return them in the form that field's
    storage regime calls for: characters for the two display-text regimes, and the untouched
    bytes for the three numeric ones. This is the per-field boundary the whole module exists
    to provide, and the dispatch happens on the descriptor's ``kind`` BEFORE any character
    set is applied to anything.

    Assumptions: the regime comes from the copybook by way of the descriptor and is never
    inferred from the bytes. Inferring would corrupt real data in this corpus rather than
    merely reading badly, which the sibling display codec demonstrates from measured spans: a
    trailing-byte sniffer reads a source-terminal label as a nine-figure negative amount
    because the label's first letter happens to appear in the negative overpunch table.

    Trade-offs: a caller wanting a number from a ZONED, PACKED or BINARY field makes a second
    call, to :func:`decode_record` or to the numeric codec directly. That is the deliberate
    compromise: returning bytes for those three regimes means there is no signature on this
    function through which a packed nibble pair or a binary word could reach a character
    decoder, and the export record puts all three regimes inside one 500-byte image, so the
    guarantee has to hold field by field rather than record by record.

    Parameters
    ----------
    record : bytes | bytearray | memoryview
        The whole fixed-width record image, read in binary mode. A ``str`` is refused.
    field : FieldSpec
        The descriptor supplying the zero-based offset, the declared width, the storage
        regime and the sensitivity flag that governs failure reporting.
    code_page : str
        The character set to decode a display-text field through. Defaults to
        :data:`EBCDIC_CODE_PAGE`. It has no effect on a numeric field, because a numeric field
        is never decoded through a character set at all.

    Returns
    -------
    str | bytes
        For :attr:`Kind.TEXT` and :attr:`Kind.UINT`, the decoded characters at the field's
        full declared width, untrimmed. For :attr:`Kind.ZONED`, :attr:`Kind.PACKED` and
        :attr:`Kind.BINARY`, exactly ``field.length`` raw bytes.

    Raises
    ------
    TypeError
        If ``record`` is a ``str`` or is not a byte image.
    EbcdicFieldDecodeError
        If the record ends before the field's exclusive end, if ``code_page`` is not
        registered, if the span holds a byte value that page leaves undefined, or if the
        descriptor declares a storage regime this module has no rule for.
    """
    image = _require_record_bytes(record, field)
    span = _require_span(image, field)

    # WHY (Assumptions): the two branches are allow-list membership tests and the function
    #   ends in a raise rather than in a fallback decode. Their union is the whole of Kind, so
    #   the raise is unreachable for today's five members and is the guard that keeps a sixth
    #   from being decoded as text by default -- a silent outcome, because a wrongly decoded
    #   span keeps the record's declared width and every field after it still parses.
    if field.kind in _CHARACTER_KINDS:
        return _decode_span(span, field, code_page)
    if field.kind in _BYTE_KINDS:
        return span
    raise EbcdicFieldDecodeError(
        _failure(
            f"storage regime {field.kind!r} has no decode rule in this module; every regime"
            " must be classified explicitly as character data or as raw bytes, because"
            " defaulting to a character decode is how a packed or binary span gets corrupted"
            " without anything raising",
            field=field,
        )
    )


def decode_field_characters(
    record: bytes | bytearray | memoryview,
    field: FieldSpec,
    *,
    code_page: str = EBCDIC_CODE_PAGE,
) -> str:
    """Decode one display field to the exact characters a display codec needs.

    Purpose
    -------
    Give the two display-text regimes and the zoned-decimal regime their characters, one
    field at a time. This is the character boundary the sibling display codec documents
    needing: it states that its input is the exact characters of one field, already converted
    at the character boundary, and this function is that conversion.

    Assumptions: :attr:`Kind.PACKED` and :attr:`Kind.BINARY` are REFUSED here, and the refusal
    is the point of having a separate entry point at all. A packed span is two digits per byte
    with a sign nibble and a binary span is a machine word; neither is characters in any code
    page, so decoding one is not a conversion but a corruption -- and it is a corruption that
    yields a string of the right length, so nothing downstream would notice. Those two
    regimes reach their own codec as raw bytes through :func:`decode_field`.

    Assumptions: a zoned span is admitted because cp037 maps the EBCDIC sign-overpunch zones
    onto exactly the characters the display codec's tables are written in -- 0xC0 to 0xC9 onto
    {ABCDEFGHI, 0xD0 to 0xD9 onto }JKLMNOPQR and 0xF0 to 0xF9 onto 0123456789 -- so the
    conversion is faithful and reversible rather than interpretive. Note carefully that this
    is a CHARACTER decode and has nothing to do with the sign CONVENTION that shares the word
    "EBCDIC": which byte values carry a trailing sign is the display codec's business and is
    never decided here.

    Parameters
    ----------
    record : bytes | bytearray | memoryview
        The whole fixed-width record image, read in binary mode. A ``str`` is refused.
    field : FieldSpec
        A :attr:`Kind.TEXT`, :attr:`Kind.UINT` or :attr:`Kind.ZONED` descriptor supplying the
        offset, the declared width and the sensitivity flag.
    code_page : str
        The character set to decode through. Defaults to :data:`EBCDIC_CODE_PAGE`.

    Returns
    -------
    str
        The decoded characters at the field's full declared width, untrimmed.

    Raises
    ------
    TypeError
        If ``record`` is a ``str`` or is not a byte image.
    EbcdicFieldDecodeError
        If the field declares :attr:`Kind.PACKED`, :attr:`Kind.BINARY` or any regime outside
        the transcodable set, if the record ends before the field's exclusive end, if
        ``code_page`` is not registered, or if the span holds a byte value that page leaves
        undefined.
    """
    if field.kind not in _TRANSCODABLE_KINDS:
        raise EbcdicFieldDecodeError(
            _failure(
                f"storage regime {field.kind!r} is not character data in any code page, so it"
                " is never transcoded; it reaches its own codec as raw bytes through"
                " decode_field instead",
                field=field,
            )
        )
    image = _require_record_bytes(record, field)
    span = _require_span(image, field)
    return _decode_span(span, field, code_page)


def trim_trailing_blanks(text: str) -> str:
    """Return one decoded field with its trailing blank padding removed.

    Purpose
    -------
    Provide the TRIMMED form of a decoded display field, as the counterpart to the full-width
    form every decode in this module returns. It is offered as a separate, explicitly called
    step rather than applied automatically, because whether trailing blanks are padding or
    data is a property of the field and not of the decode.

    Assumptions: the two cases are decided by the target column and both occur in this corpus.
    For a DESCRIPTIVE character field the trailing blanks are padding that fills the declared
    width, the target column is a variable-width one, and trimming is correct. For a FIXED
    CODE OR KEY the declared width is part of the contract -- a card number, a transaction
    identifier, a type code -- the target column is fixed-width, and trimming would produce a
    value that no longer matches the key it has to join on. Since the descriptor carries no
    trim policy, this module never guesses one per call site: it returns the full width and
    the caller that knows its target column applies this function deliberately.

    Assumptions: ONLY the blank is removed, and only from the right. Leading blanks are left
    alone because a right-aligned value's leading blanks are its alignment and removing them
    changes the value's meaning. Low values are left alone as well, and deliberately so: a
    trailing run of them is not blank padding, and discarding it would silently shorten a
    field whose content this module has no licence to interpret. A stronger trim -- the
    argument-free form that removes every kind of whitespace -- is refused for the same
    reason.

    Parameters
    ----------
    text : str
        One decoded field's characters, normally the result of :func:`decode_field` or
        :func:`decode_field_characters`.

    Returns
    -------
    str
        The same characters with trailing blanks removed. A field of nothing but blanks
        becomes the empty string, which is the correct rendering of a descriptive field that
        holds no value.

    Raises
    ------
    TypeError
        If ``text`` is not a ``str``. A byte span is refused rather than trimmed, because
        trimming bytes would bypass the character boundary this module exists to be.
    """
    if not isinstance(text, str):
        raise TypeError(
            "trailing-blank trimming applies to decoded characters, but was given a"
            f" {type(text).__name__}; a byte span is decoded first, never trimmed first"
        )

    # WHY (Assumptions): the strip is right-hand only and removes the blank alone, and the
    #   trim POLICY is the caller's because the descriptor carries none. A descriptive character
    #   field pads to its declared width with blanks that are not data and lands in a
    #   variable-width column, so trimming is right for it. A fixed code or key -- a card number,
    #   a transaction identifier, a two-character type code -- carries its declared width AS PART
    #   OF THE CONTRACT and lands in a fixed-width column, so trimming it would produce a value
    #   that no longer matches the key it joins on. Since the two cases are indistinguishable from
    #   the bytes, every decode in this module returns the full width and this step is applied
    #   deliberately rather than guessed at per call site. The argument-free strip is refused for a
    #   related reason: it would also remove trailing low values, which are content here.
    return text.rstrip(_BLANK)


def decode_timestamp(
    record: bytes | bytearray | memoryview,
    field: FieldSpec,
    *,
    code_page: str = EBCDIC_CODE_PAGE,
) -> str | None:
    """Decode one timestamp field, reporting an unwritten stamp as absent rather than failing.

    Purpose
    -------
    Return the characters of a fixed-width timestamp field, or ``None`` when the field holds
    no timestamp at all. It exists so that the one legitimate empty case in this corpus is
    answered with an absence instead of an exception.

    Assumptions: a timestamp span consisting entirely of blanks is a LEGITIMATE VALUE and not
    an error. The baseline writes a 26-character stamp into a character field, and a record
    written before that stamp was set carries the field's initial state instead. Raising on it
    would refuse a record the baseline considers valid, so this function reports it as absent
    and the caller maps that to a null column.

    Assumptions: a span consisting entirely of LOW VALUES is the second form of the same fact,
    and it was measured rather than assumed. ``AWS.M2.CARDDEMO.DALYTRAN.PS.INIT`` is a single
    350-byte primer record of which 342 bytes are 0x00, and both of its 26-byte timestamp
    fields fall inside that run, so neither stamp was ever written. Treating only blanks as
    absence would hand a caller 26 low-value characters for a field that holds nothing, which
    a timestamp column cannot accept and which no amount of later validation could explain.

    Trade-offs: this function does NOT normalise anything. A stamp that IS present comes back
    exactly as it was decoded, character for character, even when its descriptor sets
    ``normalize_ts``. That flag MARKS which fields a golden-master comparison may blank before
    comparing; producing that rendering is the comparison's contract and it lives with the
    comparison, because a codec that normalised on the way through would make the original
    value unrecoverable and the parity check unable to show what actually differed. The
    accepted cost is that a caller doing parity work performs its own blanking step.

    Parameters
    ----------
    record : bytes | bytearray | memoryview
        The whole fixed-width record image, read in binary mode. A ``str`` is refused.
    field : FieldSpec
        A :attr:`Kind.TEXT` descriptor for the timestamp field, supplying its offset, its
        declared width and its sensitivity flag.
    code_page : str
        The character set to decode through. Defaults to :data:`EBCDIC_CODE_PAGE`.

    Returns
    -------
    str | None
        The decoded characters at the field's full declared width when the field holds a
        stamp, or ``None`` when every position is a blank or a low value.

    Raises
    ------
    TypeError
        If ``record`` is a ``str`` or is not a byte image.
    EbcdicFieldDecodeError
        If the field does not declare :attr:`Kind.TEXT`, if the record ends before the field's
        exclusive end, if ``code_page`` is not registered, or if the span holds a byte value
        that page leaves undefined.
    """
    # WHY (Assumptions): every timestamp in the corpus is declared as a character field of
    #   26 positions -- PIC X(26) -- so requiring that regime here is a transcription of the
    #   copybook rather than a restriction invented by this module. Admitting a numeric regime
    #   would mean accepting a span whose absent state is not blanks at all, and the absence rule
    #   below would then be wrong for it.
    if field.kind is not Kind.TEXT:
        raise EbcdicFieldDecodeError(
            _failure(
                f"a timestamp field is declared as character data, but this one declares"
                f" {field.kind!r}",
                field=field,
            )
        )
    decoded = decode_field_characters(record, field, code_page=code_page)

    # WHY (Assumptions): an all-blank stamp is a LEGITIMATE VALUE and is reported as absent
    #   rather than raised on, and this is the ordinary case in the shipped corpus rather than an
    #   edge one. Measured: ALL 300 records of ``AWS.M2.CARDDEMO.DALYTRAN.PS`` carry 26 blanks in
    #   ``DALYTRAN-PROC-TS`` at zero-based offset 304, because the posting run is what writes that
    #   stamp and the extract is its input; all 300 of the same records carry a populated
    #   ``DALYTRAN-ORIG-TS`` at offset 278. Raising on the blank form would therefore refuse every
    #   record of the daily transaction extract.
    # WHY (Assumptions): the low value is accepted as the second form of the same fact, also
    #   on measured evidence: ``AWS.M2.CARDDEMO.DALYTRAN.PS.INIT`` is one 350-byte primer record of
    #   which 342 bytes are 0x00, and BOTH of its 26-byte timestamp fields sit inside that run, so
    #   neither stamp was ever written. Recognising only blanks would hand a caller 26 low-value
    #   characters for a field that holds nothing, which a timestamp column cannot accept and which
    #   no later validation could explain.
    # WHY (Trade-offs): a stamp that IS present is returned exactly as decoded, character for
    #   character, even when its descriptor sets ``normalize_ts``. That flag MARKS which fields a
    #   golden-master comparison may blank before comparing; it does not ask this module to blank
    #   them. All 500 records of ``AWS.M2.CARDDEMO.EXPORT.DATA.PS`` carry a populated
    #   ``EXPORT-TIMESTAMP``, and that field IS so marked, so the distinction is exercised by real
    #   data rather than only stated. Normalising on the way through would make the original value
    #   unrecoverable and leave the parity check unable to show what actually differed, so the
    #   accepted cost is that a caller doing parity work performs its own blanking step against the
    #   same flag.
    if not decoded.strip(_BLANK + _LOW_VALUE):
        return None
    return decoded


def _decode_one_field(image: bytes, field: FieldSpec, code_page: str) -> str | Decimal:
    """Decode one field to its final value, routing each numeric regime to its own codec.

    Purpose
    -------
    Perform the second half of the record decode: take the per-field result and, for the three
    numeric regimes, turn it into an exact decimal through the codec that owns that regime.
    Display-text regimes need no second step and are returned as decoded.

    Assumptions: the dispatch is on the descriptor's regime and every one of the five is named
    explicitly, with a trailing raise for anything else, so a regime added to the enumeration
    later cannot fall through into whichever branch is last. The per-field decoder is called
    first even for the numeric regimes, so that the record-reach check and the sensitive-safe
    failure reporting happen once, in one place, for every field regardless of regime.

    Trade-offs: the packed and binary codecs are handed the WHOLE record image rather than the
    span the per-field decoder already produced. Those entry points slice the declared span
    themselves from the descriptor, which is the form that keeps the copybook the only source
    of an offset; the cost is that the same slice is taken twice, and what it buys is that this
    module never restates an offset the layouts module already declares.

    Parameters
    ----------
    image : bytes
        The whole record image, already proven to be the declared record length.
    field : FieldSpec
        The descriptor supplying the offset, the width, the regime, the scale and the sign
        contract.
    code_page : str
        The character set to decode a display-text or zoned span through.

    Returns
    -------
    str | Decimal
        Decoded characters at full declared width for :attr:`Kind.TEXT` and
        :attr:`Kind.UINT`; an exact :class:`decimal.Decimal` at the field's declared scale for
        :attr:`Kind.ZONED`, :attr:`Kind.PACKED` and :attr:`Kind.BINARY`.

    Raises
    ------
    EbcdicFieldDecodeError
        If ``code_page`` is not registered, if the span holds a byte value that page leaves
        undefined, or if the field declares a regime this module has no rule for.
    zoned.ZonedDecimalError
        If a zoned span violates its display contract.
    packed.PackedDecimalError
        If a packed or binary span violates its computational contract.
    """
    if field.kind in _CHARACTER_KINDS:
        return _decode_span(_require_span(image, field), field, code_page)
    if field.kind is Kind.ZONED:
        # WHY (Assumptions): the zoned span is decoded to characters HERE, one field wide,
        #   and only then handed on. The display codec's span-oriented entry point is used rather
        #   than its record-oriented one precisely so that the only thing ever transcoded is this
        #   one field: the record-oriented form takes a whole record as characters, and building
        #   that argument would mean transcoding the entire image, which for the export record
        #   would route 4153 low values and every packed and binary span through the code page.
        characters = _decode_span(_require_span(image, field), field, code_page)

        # WHY (Assumptions): this call is where the two meanings of the word "EBCDIC" meet,
        #   and they are not the same thing. What happens on the line above is a CHARACTER decode
        #   through the cp037 code page: it decides which byte value is which character. What
        #   happens on the line below is the EBCDIC SIGN CONVENTION: it decides which trailing
        #   character carries a negative sign, and ``tests/README.md`` line 268 records the house
        #   compile invocation ``cobc -fixed -fsign=EBCDIC --std=ibm-strict -I app/cpy`` with lines
        #   273 and 274 stating that the flag is required because the ASCII default misreads that
        #   overpunch and silently corrupts negative balances. Compounding the collision,
        #   ``tests/helpers/record_codec.py`` line 135 calls the same mapping "the canonical IBM
        #   ASCII trailing-sign mapping" -- correct too, because the overpunch characters are
        #   ASCII-printable even though the convention is the EBCDIC one. Simplifying either
        #   statement into the other is how every negative balance in the corpus gets corrupted, so
        #   the two steps stay two steps and neither module performs the other's.
        return decode_zoned(
            characters,
            field.int_digits,
            field.dec_digits,
            field.signed,
            field=field,
        )
    if field.kind is Kind.PACKED:
        return decode_packed_field(image, field)
    if field.kind is Kind.BINARY:
        return decode_binary_field(image, field)
    raise EbcdicFieldDecodeError(
        _failure(
            f"storage regime {field.kind!r} has no value rule in this module; every regime must"
            " be routed to a named codec explicitly, because a regime that fell through to a"
            " character decode would yield a plausible wrong value rather than raising",
            field=field,
        )
    )


def decode_record(
    record: bytes | bytearray | memoryview,
    layout: RecordSpec,
    *,
    code_page: str = EBCDIC_CODE_PAGE,
) -> dict[str, str | Decimal]:
    """Decode one whole record into a mapping of field name to value, field by field.

    Purpose
    -------
    Walk a record's declared fields in declaration order and produce each one's value: the
    decoded characters for the two display-text regimes, and an exact decimal for the three
    numeric ones. Each field goes through the per-field decoder first and only then through
    the numeric codec that owns its regime, so no part of the record is ever decoded as a
    unit.

    Assumptions: the numeric regimes are handed to their codecs in the form each one
    documents. A packed or binary field's RAW RECORD BYTES go straight to the packed codec's
    field-oriented entry points, which slice the declared span themselves, so no character set
    touches those bytes at any point. A zoned field's ONE FIELD is decoded to characters first,
    because the display codec's input is defined as the exact characters of one field already
    converted at the character boundary -- and cp037 maps the overpunch zones onto precisely
    the characters its tables are written in, so the conversion adds nothing and loses nothing.

    Assumptions: money leaves this function as :class:`decimal.Decimal` at the scale its
    picture clause declares, and never in an IEEE-754 binary type. An IEEE-754 binary value
    cannot represent ten cents exactly, so a total accumulated in one drifts from the total the
    baseline computed, silently and by an amount that grows with the row count. No such type
    appears anywhere in this package's money path, and none may be introduced into it.

    Assumptions: EVERY declared field appears in the result, including the trailing pad the
    copybook names. Dropping a pad is a projection decision that belongs to the reader mapping
    a record onto a table -- the target schema does drop it -- and a codec that dropped it
    would make its own output a partial description of the bytes it read, which is exactly
    what a verification pass must not be handed.

    Trade-offs: the result is a plain, mutable ``dict`` rather than a read-only mapping. A
    decoded record is a value the caller is about to project into a row, so an immutable
    wrapper would force a copy at the first step; the constant tables in the layouts module are
    read-only for the opposite reason, being shared and never rewritten. Insertion order is the
    layout's declaration order, which is the record's byte order, so iterating the result walks
    the record left to right.

    Parameters
    ----------
    record : bytes | bytearray | memoryview
        One whole fixed-width record image of exactly ``layout.reclen`` bytes, read in binary
        mode. A ``str`` is refused.
    layout : RecordSpec
        The record descriptor supplying the declared length and the ordered field descriptors.
    code_page : str
        The character set to decode the display-text and zoned fields through. Defaults to
        :data:`EBCDIC_CODE_PAGE`.

    Returns
    -------
    dict[str, str | Decimal]
        One entry per declared field, keyed by the field name exactly as the copybook spells
        it, in declaration order. A :attr:`Kind.TEXT` or :attr:`Kind.UINT` field maps to its
        decoded characters at full declared width, untrimmed. A :attr:`Kind.ZONED`,
        :attr:`Kind.PACKED` or :attr:`Kind.BINARY` field maps to an exact
        :class:`decimal.Decimal` at the field's declared scale.

    Raises
    ------
    TypeError
        If ``record`` is a ``str`` or is not a byte image.
    EbcdicRecordLengthError
        If the record is not exactly ``layout.reclen`` bytes, or if the layout declares two
        fields of the same name, which would make one of them unrepresentable in the result.
    EbcdicFieldDecodeError
        If ``code_page`` is not registered, if a span holds a byte value that page leaves
        undefined, or if a field declares a storage regime this module has no rule for.
    zoned.ZonedDecimalError
        If a zoned span violates its display contract, for example a non-digit in its body or
        a low-order byte that is not a valid sign overpunch. Raised by the display codec and
        left to propagate, because its own message already names the field through the same
        content-free rendering this module uses.
    packed.PackedDecimalError
        If a packed span holds an invalid nibble or sign, or a binary span holds a value wider
        than its field declares. Raised by the numeric codec and left to propagate for the same
        reason.
    """
    image = _require_record_image(record, layout)
    decoded: dict[str, str | Decimal] = {}
    for field in layout.fields:
        # WHY (Assumptions): a repeated field name is refused rather than allowed to
        #   overwrite silently. No layout in the registry declares one today -- every field name
        #   in every registered record is unique, including the trailing pads -- so this costs
        #   nothing now; what it buys is that a future layout naming two pads alike loses a field
        #   loudly here instead of producing a record that is one entry short and otherwise
        #   perfectly well formed.
        if field.name in decoded:
            raise EbcdicRecordLengthError(
                f"record {layout.name} declares more than one field named {field.name!r}, so a"
                " name-keyed result cannot represent them both; the layout must give each"
                " field a distinct name"
            )
        decoded[field.name] = _decode_one_field(image, field, code_page)
    return decoded
