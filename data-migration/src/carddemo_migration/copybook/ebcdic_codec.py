"""Character decoder for the CardDemo mainframe extracts, applied one field at a time.

Purpose
-------
This module is the ONE AND ONLY place in the repository where EBCDIC is turned into
characters. It opens a dataset READ-ONLY IN BINARY MODE, slices it strictly by the
record length its layout declares, and then decodes EACH FIXED-WIDTH FIELD SEPARATELY
through the cp037 code page. It never decodes a whole record, it never looks for a line
terminator, and it never hands a packed or binary span to a character decoder at all.

It also FAILS CLOSED on the code page itself. A page reaches the decoder only if it is named
in :data:`SUPPORTED_CODE_PAGES` -- a curated set of four audited pages -- and only after it has
PROVED, on its first use in the process, that it behaves as a single-byte Latin EBCDIC page and
answers the characters this package's own contracts read; every decode then additionally proves
the span itself returned one character per byte. That is an input-validation control rather than
tidiness: a registered-but-wrong single-byte page such as ``latin-1`` decodes a field to the SAME
width and changes only its content, so an amount comes back plausible and wrong rather than
refused, which is precisely the class of failure the per-field rule below exists to prevent.

Everything else the package needs from these bytes is somebody else's job, and the
boundaries are drawn deliberately. Byte geometry -- offsets, lengths, storage regimes,
record boundaries -- belongs to ``carddemo_migration.copybook.layouts``, which this
module imports and never restates. Trailing-sign overpunch interpretation belongs to
``carddemo_migration.copybook.zoned``. ``COMP-3`` and ``COMP`` interpretation belongs to
``carddemo_migration.copybook.packed``. CHOOSING which dataset to read -- and what to do
with the values afterwards -- belongs to a reader or a loader; this module never selects
one, never writes one and never deletes one.

OPENING a dataset a caller has already chosen is, by contrast, deliberately THIS module's
job, and the distinction is worth stating because the two read alike. :func:`iter_ebcdic_records`
accepts a path, and when it is given one it stats the file, validates the size against the
declared record length, opens it in ``"rb"`` read-only, streams whole records and closes it.
That is not a boundary leak but the reason the boundary is drawn here: the mode string is
the single most consequential character in this package, and a caller permitted to open the
file itself is a caller permitted to open it in text mode, which applies an encoding and
universal-newline translation to a corpus containing 4153 low values and five 0x0A bytes
that are payload. Owning the open is what makes "binary, read-only" a property of the code
rather than a rule in a document. Byte SOURCES that are already bytes -- an in-memory image,
an iterable of records -- are accepted just as readily, so a caller that has its own reason
to hold the bytes never has to route a file through here to be understood.

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

Which code pages this module admits, and how it proves one
----------------------------------------------------------
Strict error handling refuses a byte the page leaves undefined, and that is necessary and
nowhere near sufficient: it does not establish that the configured page is the RIGHT one.
The measurement which settles the point is ``latin-1``. It decodes all 256 byte values, maps
them to 256 distinct characters and re-encodes them byte for byte, so no structural test
rejects it -- and it places the digits at 0x30 and the letters at 0x41, where EBCDIC places
neither. A field decoded through it keeps its declared width, so every later offset still
looks valid and only the content is wrong, which is the same plausible-wrong-value outcome
that decoding per record produces.

Four things are therefore required of a page before one byte of a dataset is decoded through
it, checked in this order:

1. It must be REGISTERED with the interpreter. For any page past cp037 that means the
   optional distribution described below is installed.
2. Its CANONICAL CODEC NAME must be in :data:`SUPPORTED_CODE_PAGES`. The name is taken from
   the resolved codec rather than from the caller's spelling, so an alias resolves to the
   page it names and is admitted, while a page nobody has vetted is refused however it is
   spelled.
3. It must pass the STRUCTURAL PROOF, once per page per process: all 256 byte values decode
   without substitution, the decode consumes every one of them, it yields exactly 256
   characters -- one per byte, which is what makes a field's character count equal its byte
   count -- those 256 characters are distinct, and encoding them returns the original 256
   bytes exactly. Distinctness is what makes the exact round trip achievable at all.
4. It must pass the KNOWN-ANSWER ANCHORS, which are precisely the characters this package's
   own contracts depend on: the display digits at 0xF0 to 0xF9, the two sign-overpunch zones
   at 0xC0 to 0xC9 and 0xD0 to 0xD9 that ``zoned`` is written against, the letter zone at
   0xE2 to 0xE9, the blank at 0x40 that pads a short value, and the low value at 0x00. This
   is the step ``latin-1`` fails.

Every field decode then repeats the per-field half of that proof on the span itself: full
consumption, one character per byte, and an exact byte-for-byte re-encode. A codec whose
decode and encode tables disagree is caught on the first field that exposes it instead of
quietly producing a value.

The admitted pages, and the measured reason each one is admitted::

    cp037    the page the shipped extracts are in, and the default. Standard library.
    cp1140   cp037 with the euro sign, differing from it at EXACTLY ONE byte value, 0x9F.
    cp500    the international page, differing from cp037 at seven byte values, every one
             of them a punctuation or symbol character.
    cp1047   Latin-1 / Open Systems, the page a z/OS UNIX file carries. It differs from
             cp037 at eight byte values, including the 0x15 and 0x25 pair, which it SWAPS
             rather than merges -- which is exactly why it stays bijective. Registered only
             by the optional distribution.

What is refused is recorded too, so the list above is not read as an oversight. The
euro-updated national family cp1141 through cp1149 maps BOTH 0x15 and 0x25 to one
character, so its 256 byte values decode to 255 distinct characters and no exact
byte-for-byte round trip exists for it at all; most of that family also places national
characters in the overpunch zone, where ``zoned`` expects ``{ABCDEFGHI`` and ``}JKLMNOPQR``.
cp273 and cp1026 do round-trip, and do the same thing to the overpunch zone. cp424 cannot
decode all 256 byte values at all. An extract in one of those pages is refused at the
configuration that selects it, with the page named, rather than turned into values that only
a money-total comparison would ever question.

What this module deliberately does not do
-----------------------------------------
* It does not normalise a timestamp. The ``normalize_ts`` flag MARKS which fields a
  golden-master comparison may blank; producing that rendering is the comparison's job.
* It does not mask anything. The ``sensitive`` flag MARKS a field whose bytes must stay
  out of diagnostics. Masking, encryption and account-number truncation belong to the
  domain projection. What this module does with the flag is govern disclosure in a failure
  message, and it does so under a policy that does not depend on the flag being complete:
  NO FAILURE RENDERS A FIELD'S SPAN, by any route. A failure discloses at most the ONE byte
  at the offset it names, for a field that is not marked sensitive, and for a field that is
  marked sensitive it discloses no content whatsoever.
* It does not read an environment variable, configure logging, or do any work at import
  time beyond one guarded, optional codec registration and deriving the closed set of code
  pages a field may be decoded through from it.
* It does not decode a field through an arbitrary codec. ``code_page`` selects from a
  CLOSED FAMILY -- cp037 plus whatever single-byte EBCDIC pages the optional distribution
  registered -- and a name outside it is refused at the configuration boundary rather than
  attempted. Two counts are then checked on every decode, so even an admitted page that
  failed to map one byte to one character position is caught instead of trusted. The reason
  both guards exist is that a multibyte or stateful codec breaks the one property this whole
  module rests on: that a field's byte width and its character width are the same number,
  and therefore that every offset after it is still correct.
* It does not write. Every dataset it touches under ``app/`` is INPUT: opened read-only
  in binary mode, never rewritten, never re-encoded, never converted in place.
* It does not decode a MIXED-REGIME AREA as anything. One field in the corpus is a
  container whose interior a different layout describes -- ``EXPORT-RECORD-DATA``, 460
  bytes redefined five ways -- and it is declared :attr:`layouts.Kind.OPAQUE` so that it
  leaves here as raw bytes. :func:`decode_export_record` reads its interior by taking the
  discriminator from the envelope first and only then decoding against the branch that
  discriminator selects.
* It does not accept an arbitrary code page. :data:`SUPPORTED_CODE_PAGES` is a closed set
  of EBCDIC single-byte pages, and a decode additionally has to consume every byte of the
  field and produce exactly one character per byte. A page outside the set does not fail
  loudly of its own accord: ``latin-1`` decodes every byte of this corpus and returns
  characters of the right width that are wrong in almost every position.

No value from any dataset is reproduced anywhere in this module -- not in a docstring,
not in a comment, not in a doctest. In particular the eight-byte password the baseline
stores in clear is a byte range and nothing more.
"""

from __future__ import annotations

import codecs
import os
import pathlib
import stat
from collections.abc import Iterable, Iterator
from decimal import Decimal
from types import ModuleType
from typing import Final

from carddemo_migration.copybook.layouts import (
    EXPORT_HEADER_LAYOUT,
    EXPORT_PAYLOAD_OFFSET,
    FieldSpec,
    Kind,
    RecordLengthError,
    RecordSpec,
    count_fixed_length_records,
    export_branch,
    iter_fixed_length_records,
)
from carddemo_migration.copybook.packed import decode_binary_field, decode_packed_field
from carddemo_migration.copybook.zoned import decode_zoned

# WHY : Alternatives Considered: the obvious spelling is a plain module-level
#   ``import ebcdic``, and it is refused. ``data-migration/requirements.txt`` pins
#   ``ebcdic==2.0.1`` and states at that pin what it is for: it registers the WIDER EBCDIC
#   code-page family around cp037 -- roughly 35 pages including cp1047 and the euro-updated
#   cp1141 through cp1149 -- which CPython does not ship. It also states what the pin is NOT
#   for, and that is the decisive part: cp037 ITSELF IS A STANDARD-LIBRARY CODEC. Measured
#   with the distribution absent from the interpreter, ``codecs.lookup("cp037")`` resolves and
#   returns the codec, and all thirteen datasets decode. So an unconditional import would buy
#   nothing for today's corpus while costing the guarantee the package publicly makes.
# WHY : Trade-offs: the guard accepts one ``try``/``except`` in exchange for
#   bare-checkout importability. ``carddemo_migration/__init__.py`` publishes that
#   ``carddemo_migration.copybook`` is standard library only and imports on a bare checkout
#   with no database driver and no AWS SDK present, and ``tests/helpers/record_codec.py``
#   states the same discipline in its own module docstring, restricting itself to the standard
#   library "so that even a bare checkout, before any test dependency is installed, can import
#   and use it". An unconditional import would make ``import carddemo_migration.copybook``
#   fail outright on such a checkout -- for a package that is not needed to decode any dataset
#   the repository actually ships. The pin therefore stays, is used the moment it is present,
#   and costs nothing when it is not: the guard buys importability and loses no capability.
# WHY : Assumptions: the module object is BOUND to a name below rather than imported
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

# WHY : Assumptions: the public surface is declared explicitly and in sorted order so a
#   consumer's import list can be checked against it mechanically, matching the convention the
#   three sibling modules in this subpackage already follow.
__all__ = [
    "EBCDIC_CODE_PAGE",
    "EXTENDED_CODE_PAGES_REGISTERED",
    "SUPPORTED_CODE_PAGES",
    "EbcdicFieldDecodeError",
    "EbcdicRecordLengthError",
    "decode_export_record",
    "decode_field",
    "decode_field_characters",
    "decode_record",
    "decode_timestamp",
    "iter_ebcdic_records",
    "trim_trailing_blanks",
]

# WHY : Assumptions: cp037 is the code page the shipped extracts are in, and it is named
#   as a constant rather than written at each decode site so there is one place to read it and
#   one place a caller can compare against. It is a Python standard-library codec: verified by
#   resolving it with the optional distribution above absent from the interpreter.
EBCDIC_CODE_PAGE: Final[str] = "cp037"

# WHY : Assumptions: the outcome of the guarded import is published as a plain boolean
#   because a caller that wants a sibling code page needs to know whether the family is
#   registered BEFORE it asks for one, and the alternative -- catching the decode failure
#   afterwards -- reports the problem one layer away from the configuration that caused it.
EXTENDED_CODE_PAGES_REGISTERED: Final[bool] = _EXTENDED_CODE_PAGE_MODULE is not None

# WHY : Assumptions: the set of pages is an ALLOW-LIST of canonical codec names, because
#   ``codecs.lookup`` resolves 117 codec names on this interpreter, plus roughly thirty more when
#   the optional distribution is present, and a WRONG one does not raise. The
#   measurement that makes the hazard concrete is ``latin-1``: measured on all 256 byte values it
#   decodes without substitution, yields 256 DISTINCT characters and re-encodes to the original
#   bytes exactly, so every structural test passes -- and it puts the digits at 0x30 and the
#   letters at 0x41, where EBCDIC puts neither. A card number decoded through it is still sixteen
#   characters, so the record keeps its declared width and every later offset still looks valid.
#   Membership is therefore checked BEFORE any structural work, and it is checked against the
#   name the registry resolves rather than the caller's spelling, so an alias such as IBM037
#   resolves to cp037 and is admitted while an unvetted page is refused however it is spelled.
# WHY : Trade-offs: four pages are admitted and the wider registered family is not, which
#   costs a caller holding an extract in a national page an explicit decision and buys a decode
#   nobody has to audit afterwards. Each admission is measured against cp037: cp1140 differs at
#   EXACTLY ONE byte value (0x9F, the euro sign); cp500 differs at seven, every one a punctuation
#   or symbol character; cp1047 differs at eight, including the 0x15 and 0x25 pair, which it SWAPS
#   rather than merges and which is exactly why it stays bijective. Not one of those differences
#   touches the digit zone, either overpunch zone, the letter zone, the blank or the low value, so
#   every anchor below holds for all four.
# WHY : Alternatives Considered: admitting the euro-updated national family cp1141 through
#   cp1149 as well, since the optional distribution registers it. Refused on measurement: those
#   pages map BOTH 0x15 and 0x25 to one character, so 256 byte values decode to 255 distinct
#   characters and no exact byte-for-byte round trip exists for them at all, which removes the one
#   check that can prove a decode reversible. Most of that family also places national characters
#   in the overpunch zone that ``zoned`` reads. cp273 and cp1026 do round-trip and do the same
#   thing to the overpunch zone; cp424 cannot decode all 256 byte values at all. Refusing at the
#   configuration that selects the page names the problem where it can be acted on.
SUPPORTED_CODE_PAGES: Final[frozenset[str]] = frozenset({"cp037", "cp500", "cp1047", "cp1140"})

# WHY : Assumptions: errors are handled strictly rather than by substitution, and the
#   handler is named at every call rather than left to default. A substituting handler is
#   exactly the failure ``tests/helpers/localstack_setup.py`` line 1068 records -- a decode
#   that turns an uninterpretable byte into U+FFFD while PRESERVING the character count, so
#   the record still has its declared width, every later offset still looks valid, and only
#   the content is wrong. Strict handling is what makes the page proof in
#   :func:`_vet_code_page` fail closed -- a candidate that leaves a byte value undefined is
#   rejected there rather than substituted here -- and it is named on the encode direction too,
#   where it is what turns an unrepresentable character into a refusal instead of a lost byte.
_STRICT: Final[str] = "strict"

# WHY : Assumptions: the blank in these extracts is the cp037 space, byte 0x40, which
#   decodes to an ordinary space. It is the pad character a short value is filled with, so it
#   is what trailing-blank trimming removes and what an absent timestamp consists of.
_BLANK: Final[str] = " "

# WHY : Assumptions: the low value is byte 0x00, which cp037 decodes to U+0000 rather
#   than to a replacement character. It is treated as a SECOND form of absence for a timestamp
#   and NEVER as trailing padding to be trimmed, because the two are different facts about the
#   data: ``AWS.M2.CARDDEMO.DALYTRAN.PS.INIT`` is a primer record of 350 bytes in which 342
#   are 0x00, covering both of its 26-byte timestamps, so those stamps were never written at
#   all; whereas a trailing run of low values inside a text field is content this module has no
#   licence to discard, since discarding it would silently change the field's length.
_LOW_VALUE: Final[str] = "\x00"

# WHY : Assumptions: the two sentinels are collected into one set for the mixed-span test in
#   ``decode_timestamp`` and for nothing else. They are NOT combined anywhere a span is stripped
#   or trimmed, because the two facts they denote are different -- see the note on ``_LOW_VALUE``
#   directly above -- and an earlier revision's single combined strip is precisely how a
#   partly-written stamp came to be reported as absent instead of refused.
_ABSENT_SENTINELS: Final[frozenset[str]] = frozenset({_BLANK, _LOW_VALUE})
# WHY : Assumptions: the two export envelope field names are named ONCE here rather than
#   written as literals inside :func:`decode_export_record`, because both are looked up in a
#   mapping and a mistyped key would raise KeyError with nothing to say which of the two
#   lookups failed or why. The names are the copybook's own, at ``app/cpy/CVEXPORT.cpy``
#   lines 10 and 19, and the layouts module's own import-time self-check already proves the
#   envelope declares both -- so if either constant were wrong the failure would be a lookup
#   here rather than a wrong decode, which is the failure mode worth having.
_EXPORT_REC_TYPE_FIELD: Final[str] = "EXPORT-REC-TYPE"
_EXPORT_PAYLOAD_FIELD: Final[str] = "EXPORT-RECORD-DATA"

# WHY : Assumptions: the probe is EVERY byte value rather than a sample, because the
#   property being proved is a property of the whole page: a page that is complete for 255 values
#   and undefined for one is exactly the page that decodes a whole dataset and then refuses a
#   single field, and a page that maps two byte values onto one character cannot round-trip the
#   one it loses. Both are cheap to establish exhaustively -- 256 bytes, once per page per process
#   -- and neither is establishable at all from a sample.
_ALL_BYTE_VALUES: Final[bytes] = bytes(range(256))

# WHY : Assumptions: each anchor is a KNOWN-ANSWER vector for a character some contract in
#   this package depends on, which is what makes the set principled rather than arbitrary. The
#   digit zone is what every PIC 9 field decodes to. The two overpunch zones are, character for
#   character, ``zoned``'s positive and negative tables, so a page that moved them would make a
#   sign unreadable. The letter zone is where a character field's ordinary content sits. The blank
#   is what :func:`trim_trailing_blanks` removes and what :func:`decode_timestamp` reads as an
#   absent stamp, and the low value is that function's second absence form -- both are taken from
#   the module constants above rather than restated, so an anchor cannot drift from the contract
#   it protects.
# WHY : Trade-offs: the anchors are what the structural proof cannot supply, and the two
#   halves are kept separate for that reason. Structure alone admits ``latin-1``; the anchors
#   refuse it on its first probe. The cost is six extra decodes per page per process.
_PAGE_ANCHORS: Final[tuple[tuple[bytes, str, str], ...]] = (
    (bytes(range(0xF0, 0xFA)), "0123456789", "the display digits every PIC 9 field decodes to"),
    (
        bytes(range(0xC0, 0xCA)),
        "{ABCDEFGHI",
        "the positive sign-overpunch zone the display codec is written against",
    ),
    (
        bytes(range(0xD0, 0xDA)),
        "}JKLMNOPQR",
        "the negative sign-overpunch zone the display codec is written against",
    ),
    (bytes(range(0xE2, 0xEA)), "STUVWXYZ", "the letter zone a character field's content sits in"),
    (b"\x40", _BLANK, "the blank that pads a short value and forms an absent timestamp"),
    (b"\x00", _LOW_VALUE, "the low value that forms the second absent-timestamp case"),
)

# WHY : Trade-offs: the outcome of the page proof is CACHED by canonical page name, so the
#   proof runs once per page per process rather than once per field. Decoding the whole shipped
#   corpus takes 10672 field decodes, and repeating a 256-byte decode, a 256-byte encode and six
#   anchor probes on every one of them would be pure waste for a result that
#   cannot change: a registered codec's tables are fixed for the life of the process. The cost is
#   one module-level dictionary, and the entry is only ever written after the proof has passed, so
#   a cache hit is a proof that already succeeded. Two threads racing the same first use both
#   perform the proof and both store the same value, which is why no lock is needed.
_VETTED_CODECS: Final[dict[str, codecs.CodecInfo]] = {}


# WHY : Assumptions: this module's own default page is checked against the set it publishes, AT
#   IMPORT, and a mismatch raises rather than waiting for a decode to discover it. Were cp037 ever
#   to fail the probe on some interpreter, every decode in this package would be wrong, and the
#   honest place to fail is where the package is loaded rather than part-way through a load that has
#   already written rows. The same assertion is what ties the set above to this module's own
#   default: an edit that dropped cp037 from the curated set -- or emptied it -- would otherwise
#   turn every decode into a refusal whose message named the wrong cause.
if EBCDIC_CODE_PAGE not in SUPPORTED_CODE_PAGES:
    raise RuntimeError(
        f"the default code page {EBCDIC_CODE_PAGE!r} did not pass this module's single-byte EBCDIC"
        " verification on this interpreter, so no field decode in this package can be trusted"
    )


# WHY : Assumptions: both error types below exist because every validation in this module
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
    field's declared end, the requested code page is not registered with the interpreter, is
    not one of the vetted single-byte EBCDIC pages, or fails the proof that it behaves as one,
    the span holds byte values that page leaves undefined, the decode does not consume the
    span or does not return one character per byte or does not re-encode to the span, or the
    field declares a storage regime this module has no rule for. It is the single decode fault
    type, so a caller guards one name rather than seven.

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


def _renderable_byte(span: bytes | None, index: int | None, field: FieldSpec) -> str | None:
    """Return the one byte a failure names, only where the field permits disclosure.

    Purpose
    -------
    Centralise the fail-closed gate every diagnostic in this module passes through, and bound
    what any of them can disclose to a SINGLE byte at a named offset, so that neither a
    sensitive field's bytes nor any field's span can reach an exception message by any route.

    Trade-offs: the unit of disclosure is one byte rather than the whole span, and that is the
    substantive compromise. A decode failure that named only an offset would give an operator
    nothing to act on, because the whole point of the report is to show WHICH byte value the
    code page has no character for; a report of the whole span would disclose the field's
    entire content, which for a sixteen-byte account number or a nine-digit identifier is the
    value itself. One byte at a stated offset answers the first question and cannot reconstruct
    the second, and it holds that property for EVERY field rather than only for the fields a
    descriptor happens to mark -- which matters because whether a given identifier or amount
    carries the flag is a policy decision taken in the layouts module, and a disclosure rule
    that depended on it would be only as complete as that policy.

    Trade-offs: a field marked :attr:`FieldSpec.sensitive` is disclosed NOTHING, not even the
    one byte. Both tiers are kept because they answer different risks: the bound protects a
    field nobody marked, and the flag protects the fields somebody did -- a primary account
    number, a cardholder identity, the eight-byte password the baseline stores in clear -- for
    which even a single byte narrows a guess.

    Assumptions: this gate is only ever as wide as the classification the layouts module
    carries, so "non-sensitive" has to MEAN non-sensitive for every corpus this runs over.
    The two authorization segments are the case where that stopped being true: they used to
    mark only the primary account number, while
    ``services/common-lib/src/main/java/com/carddemo/common/codec/CsvAuthCodec.java``
    classifies the same record content far more widely over the authorization wire -- card
    expiry, transaction amount, approved amount, merchant identity, name, city and postal
    code, and the transaction identity, all alongside the card number. The layouts module now
    resolves those two segments through an explicit DISCLOSABLE allowlist
    (``_close_authorization_disclosure``), so an authorization field is withheld unless it is
    named as a closed-domain code, a date, a counter, an account key or the trailing pad --
    and a field added to either segment later is withheld by default rather than rendered.
    The policy therefore lives with the descriptors, where the packed and zoned codecs read
    it too, and this function stays a single unconditional read of one flag. The policy is a
    target-only addition and is registered as ``D-AUTH-DIAGNOSTIC-DISCLOSURE`` in
    ``docs/architecture/cobol-to-service-traceability.md``.

    Assumptions: hexadecimal is used rather than a character rendering because at this point
    the byte has by definition failed to become a character, so there is no character rendering
    to give. The masking helper the layouts module offers for the character path,
    ``mask_field``, operates on ``str`` and is therefore not applicable here; that module's
    own documentation designates :meth:`FieldSpec.describe` as the sensitive-safe rendering
    for the byte path, and that is what :func:`_failure` uses for the geometry half.

    Parameters
    ----------
    span : bytes | None
        The exact field bytes under consideration, or ``None`` when the failure has no span.
    index : int | None
        The zero-based offset WITHIN ``span`` of the single byte the failure names, or ``None``
        when the failure names no particular byte.
    field : FieldSpec
        The descriptor whose ``sensitive`` flag governs disclosure.

    Returns
    -------
    str | None
        Two lower-case hexadecimal digits for the byte at ``index`` of a non-sensitive field,
        otherwise ``None``.

    Raises
    ------
    None.
    """
    # WHY : Trade-offs: the gate closes on THREE conditions and in this order, so a rendering
    #   is never built and then discarded: no span, no named byte, or a sensitive field. Only a
    #   failure that can point at one byte discloses one, which means a length, consumption or
    #   width failure -- none of which has an offending byte -- discloses nothing at all.
    # WHY : Assumptions: reading ONE flag is safe only because the flag is now set by policy
    #   rather than per declaration for the records where the exposure is worst. The two
    #   authorization segments resolve their sensitivity through the layouts module's disclosable
    #   allowlist, so their default is withheld and their classification matches CsvAuthCodec's
    #   SENSITIVE_FIELD_NAMES field for field. Re-deciding that here would put one policy in two
    #   places and leave the packed and zoned codecs reading the older one.
    if span is None or index is None or field.sensitive:
        return None
    return f"{span[index]:02x}"


def _failure(
    reason: str,
    *,
    field: FieldSpec,
    span: bytes | None = None,
    index: int | None = None,
) -> str:
    """Build one sensitive-safe diagnostic from a reason and a field context.

    Purpose
    -------
    Give every exception this module raises the same ordering and the same disclosure
    policy: the violated contract first, then the field's content-free geometry, then the
    single byte the failure names, and only where :func:`_renderable_byte` permits it.

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
    index : int | None
        The zero-based offset within ``span`` of the single byte the failure names, or ``None``
        when the failure names no particular byte.

    Returns
    -------
    str
        A complete diagnostic that is safe to log for the supplied field context.

    Raises
    ------
    None.
    """
    message = f"{reason}; field={field.describe()}"
    rendered = _renderable_byte(span, index, field)
    if rendered is not None:
        message = f"{message}; byte[{index}]=0x{rendered}"
    return message


def _vet_code_page(codec: codecs.CodecInfo, field: FieldSpec) -> None:
    """Prove one resolved codec is a total, bijective, single-byte EBCDIC page.

    Purpose
    -------
    Establish, before any dataset byte is decoded through it, that a codec on the allow-list
    behaves the way this module's offsets and this package's numeric contracts require: it
    defines every byte value, consumes every byte, produces exactly one character per byte,
    produces 256 DISTINCT characters, re-encodes them to the original bytes exactly, and maps
    the EBCDIC digit, overpunch, letter, blank and low-value positions to the characters those
    contracts read.

    Assumptions: the structural half and the known-answer half are BOTH required because
    neither implies the other, and the measurement that proves it is ``latin-1``: it is total,
    it is bijective and it round-trips exactly, so the structural half admits it, while every
    anchor rejects it because it places the digits at 0x30 and the letters at 0x41. Conversely
    a page could name the right characters and still lose one of two byte values that share
    one, which is what the euro-updated national family does at 0x15 and 0x25 -- so the
    structural half is not implied by the anchors either.

    Assumptions: the name membership test is NOT repeated here. This function proves how a
    codec behaves; :func:`_codec_for` decides which codec is allowed to be asked, and it
    performs that test first. Keeping the two apart means the proof can be read as a statement
    about behaviour alone.

    Trade-offs: a failure is reported as :class:`EbcdicFieldDecodeError` rather than as a
    configuration error of its own type, matching the treatment of an unregistered page. The
    compromise keeps a caller's guard to one type for every reason a field can fail to become
    characters, and it costs the message the burden of naming the remedy, which it carries.

    Parameters
    ----------
    codec : codecs.CodecInfo
        The resolved codec to prove, already established to be on :data:`SUPPORTED_CODE_PAGES`.
    field : FieldSpec
        The descriptor being decoded, used only to render safe geometry in a failure. No byte
        of the field is examined here; the probes are code-page constants.

    Returns
    -------
    None
        Nothing. The function either returns having proved the page or raises.

    Raises
    ------
    EbcdicFieldDecodeError
        If the page leaves any byte value undefined, does not consume every probe byte, does
        not produce exactly one character per byte, maps two byte values onto one character,
        does not re-encode its own output to the original bytes, or answers any anchor with
        characters other than the ones this package's contracts read.
    """
    try:
        decoded, consumed = codec.decode(_ALL_BYTE_VALUES, _STRICT)
    except UnicodeDecodeError as exc:
        raise EbcdicFieldDecodeError(
            _failure(
                f"code page {codec.name!r} leaves byte value 0x{_ALL_BYTE_VALUES[exc.start]:02x}"
                " undefined, so it cannot decode a fixed-width record in which every byte"
                " position must yield exactly one character",
                field=field,
            )
        ) from exc
    if consumed != len(_ALL_BYTE_VALUES):
        raise EbcdicFieldDecodeError(
            _failure(
                f"code page {codec.name!r} consumed {consumed} of {len(_ALL_BYTE_VALUES)} probe"
                " bytes, so it is stateful or multi-byte; a fixed-width field decode requires a"
                " page that consumes every byte it is given",
                field=field,
            )
        )

    # WHY : Assumptions: one character per byte is the property every offset in this package
    #   rests on, so it is proved rather than assumed. A page producing fewer characters than bytes
    #   would make a field's decoded width disagree with its declared width while the record still
    #   divided correctly, and a page producing more would do the same in the other direction --
    #   both give a value of the wrong length that no length check downstream of the field is
    #   positioned to notice.
    if len(decoded) != len(_ALL_BYTE_VALUES):
        raise EbcdicFieldDecodeError(
            _failure(
                f"code page {codec.name!r} decoded {len(_ALL_BYTE_VALUES)} probe bytes into"
                f" {len(decoded)} characters, so it is not a single-byte page; this module"
                " requires exactly one character per byte",
                field=field,
            )
        )

    # WHY : Assumptions: distinctness is required because it is what makes the exact round
    #   trip below achievable at all. A page mapping two byte values onto one character can only
    #   encode that character back to one of them, so the other is unrecoverable -- and the
    #   euro-updated national family does exactly that at 0x15 and 0x25, decoding 256 byte values
    #   into 255 distinct characters. A decode that cannot be reversed cannot be verified, and an
    #   unverifiable decode of a financial record is the outcome this module exists to refuse.
    distinct = len(set(decoded))
    if distinct != len(_ALL_BYTE_VALUES):
        raise EbcdicFieldDecodeError(
            _failure(
                f"code page {codec.name!r} maps {len(_ALL_BYTE_VALUES)} byte values onto"
                f" {distinct} distinct characters, so at least two byte values share one"
                " character and no exact byte-for-byte round trip exists for this page",
                field=field,
            )
        )

    # WHY : Assumptions: the page is proved REVERSIBLE by re-encoding its own output, which
    #   is the only check that exercises the encode table at all. A codec whose two tables
    #   disagree -- a real possibility for any page registered from outside the standard library --
    #   would decode a dataset into characters that no longer stand for the bytes they came from,
    #   and nothing about the decode direction alone can detect that.
    try:
        reencoded, encoded_characters = codec.encode(decoded, _STRICT)
    except UnicodeEncodeError as exc:
        raise EbcdicFieldDecodeError(
            _failure(
                f"code page {codec.name!r} cannot re-encode the character it decodes from byte"
                f" value 0x{_ALL_BYTE_VALUES[exc.start]:02x}, so its decode and encode tables"
                " disagree and a decode through it is not reversible",
                field=field,
            )
        ) from exc
    if encoded_characters != len(decoded) or reencoded != _ALL_BYTE_VALUES:
        difference = _first_difference(_ALL_BYTE_VALUES, reencoded)
        raise EbcdicFieldDecodeError(
            _failure(
                f"code page {codec.name!r} does not round-trip its own output: re-encoding the"
                f" decoded probe first differs at probe offset {difference} of"
                f" {len(_ALL_BYTE_VALUES)}, so a decode through this page is not reversible and"
                " cannot be verified",
                field=field,
            )
        )

    # WHY : Assumptions: the anchors are checked LAST because they are the cheapest to state
    #   and the most specific to fail on: by this point the page is known total, so a probe cannot
    #   raise, and every mismatch is therefore a statement that this page puts a character this
    #   package depends on somewhere else. The contract each anchor protects travels with it into
    #   the message, so the failure says which downstream rule the page would have broken rather
    #   than only which bytes disagreed.
    for probe, expected, contract in _PAGE_ANCHORS:
        observed, _consumed = codec.decode(probe, _STRICT)
        if observed != expected:
            raise EbcdicFieldDecodeError(
                _failure(
                    f"code page {codec.name!r} is registered and structurally complete but is not"
                    f" an EBCDIC page: the {len(probe)} byte values from 0x{probe[0]:02x} must"
                    f" carry {contract}, and this page decodes them to other characters",
                    field=field,
                )
            )


def _codec_for(code_page: str, field: FieldSpec) -> codecs.CodecInfo:
    """Resolve one code page to a vetted registered codec, or refuse the decode.

    Purpose
    -------
    Turn a code-page name into the codec that implements it, refuse any page this module has
    not vetted, prove a newly seen page once, and translate every one of those refusals into
    this module's own decode error so a caller guards one type instead of four.

    Assumptions: the three gates run in the order registration, then ALLOW-LIST MEMBERSHIP,
    then the behavioural proof, and the order is deliberate. Membership is a string test
    against :data:`SUPPORTED_CODE_PAGES` and costs nothing, so it precedes the 256-byte proof
    and keeps an unvetted page from consuming any work at all. It is tested against the
    CANONICAL name the registry resolves rather than the caller's spelling, so an alias is
    admitted as the page it names while an unvetted page is refused however it is spelled --
    the registry normalises names, and a policy keyed on the caller's spelling would be
    bypassable by writing the same page differently.

    Assumptions: the lookup happens per call rather than once at import, and both halves of
    that matter. Per call is what lets ``code_page`` select a vetted sibling page at run time
    -- the capability ``data-migration/requirements.txt`` records at the ``ebcdic`` pin, namely
    that an extract in another vetted page decodes through this identical per-field path by
    configuration alone with no edit to this module. Not at import is what keeps the ONLY
    import-time side effect this module has the one guarded codec registration near the top
    of the file. The cost is nil in practice because the codec registry caches its own
    lookups and this function caches the proof, so a repeated resolution is two dictionary
    hits.

    Trade-offs: an unregistered, unvetted or failing page is reported as a decode failure
    rather than as a configuration failure of its own type. The message therefore has to carry
    the remedy, and it does: it names the page that failed, names the pages that are admitted,
    and states that anything past cp037 comes from the optional distribution. The compromise
    buys a caller a single ``except`` for every reason a field can fail to become characters.

    Parameters
    ----------
    code_page : str
        The codec name to resolve, for example :data:`EBCDIC_CODE_PAGE`.
    field : FieldSpec
        The descriptor being decoded, used only to render safe geometry in a failure.

    Returns
    -------
    codecs.CodecInfo
        The registered, allow-listed and proved codec for ``code_page``.

    Raises
    ------
    EbcdicFieldDecodeError
        If ``code_page`` is not registered with the interpreter's codec registry, if the codec
        it resolves to is not named in :data:`SUPPORTED_CODE_PAGES`, or if that codec fails the
        proof :func:`_vet_code_page` performs.
    """
    try:
        codec = codecs.lookup(code_page)
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

    # WHY : Trade-offs: the cache is consulted BEFORE the membership test rather than after,
    #   which reads redundant and is not: an entry exists only if that page already passed both
    #   the membership test and the full proof, so a hit is a decision already taken. What the
    #   ordering buys is that the steady state -- every field of every record after the first --
    #   costs one dictionary lookup and nothing else.
    vetted = _VETTED_CODECS.get(codec.name)
    if vetted is not None:
        return vetted

    if codec.name not in SUPPORTED_CODE_PAGES:
        raise EbcdicFieldDecodeError(
            _failure(
                f"code page {code_page!r} resolves to the codec {codec.name!r}, which is not one"
                " of the single-byte EBCDIC pages this module has vetted"
                f" ({', '.join(sorted(SUPPORTED_CODE_PAGES))}); a page outside that set is"
                " refused rather than trusted, because a wrong page decodes a field to the right"
                " WIDTH and the wrong content, which leaves every later offset looking valid",
                field=field,
            )
        )

    _vet_code_page(codec, field)
    _VETTED_CODECS[codec.name] = codec
    return codec


def _first_difference(left: bytes, right: bytes) -> int:
    """Return the zero-based offset at which two byte images first differ.

    Purpose
    -------
    Give a round-trip failure one exact offset to name, so the report points at the byte that
    disagreed rather than at the whole image.

    Assumptions: when one image is a PREFIX of the other, every byte they share is equal and
    the first difference is the length of the shorter one, which is the offset the longer image
    continues at. Returning that instead of signalling "no difference" keeps the caller's
    message honest for images of unequal length, which is the case a length check alone would
    already have caught but which this function must still answer sensibly.

    Parameters
    ----------
    left : bytes
        The first image, in practice the original span.
    right : bytes
        The second image, in practice the result of re-encoding the decoded characters.

    Returns
    -------
    int
        The offset of the first differing byte, or the length of the shorter image when one is
        a prefix of the other. For two identical images that is the common length, and no
        caller reaches this function with identical images.

    Raises
    ------
    None.
    """
    shared = min(len(left), len(right))
    for offset in range(shared):
        if left[offset] != right[offset]:
            return offset
    return shared


def _describe_wrong_view(view: memoryview) -> str:
    """Describe why one memoryview cannot be sliced by byte offset, without its content.

    Purpose
    -------
    Produce the content-free half of the two refusals below, so that both report a
    non-byte view in the same words and a caller reading either message learns the same
    remedy.

    Refactoring Rationale: this check is new, and it closes a gap between what the two
    boundary functions DOCUMENTED and what they enforced. Both stated that a
    ``memoryview`` had to be a one-dimensional image of single bytes and both then called
    ``bytes(...)``, which silently accepts a wider or multi-dimensional view: measured,
    ``memoryview(record).cast("H")`` -- one dimension, two-byte items -- reached the
    character decoder, the packed decoder and the record decoder without any of the three
    refusing it. The guard already existed one module away, at
    ``layouts._require_byte_chunk``, so the rule was settled and only its application was
    missing here.

    Assumptions: the two properties tested are exactly the two that make a byte offset
    meaningful. A record boundary, a field start and a field end are all counted in BYTES,
    so a view whose items are wider than one byte counts them in the wrong unit, and a
    view of more than one dimension has no single linear order for them at all. What makes
    this worth refusing rather than converting is that the conversion succeeds: a wider
    view flattens to the host's byte order, so a caller that assembled its buffer from
    integers gets one answer on a little-endian machine and a different one on a
    big-endian machine, and neither raises.

    Parameters
    ----------
    view : memoryview
        The view that failed the guard, read only for its shape metadata.

    Returns
    -------
    str
        A content-free sentence naming the observed dimension count and item size and the
        cast that fixes them.

    Raises
    ------
    None.
    """
    return (
        "a record must be a ONE-DIMENSIONAL image of SINGLE bytes, but was given a memoryview"
        f" of {view.ndim} dimensions and {view.itemsize}-byte items; cast it with .cast('B')"
        " first, because every offset in a layout is counted in bytes and a wider view"
        " flattens to the host's own byte order rather than raising"
    )


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

    # WHY : Assumptions: the shape of a memoryview is checked BEFORE conversion, because
    #   the conversion is what hides the problem. ``bytes()`` over a two-byte-item view
    #   succeeds and yields the buffer's bytes in the host's order, so a wrong view produces a
    #   plausible record rather than an error, and every offset taken from it afterwards is
    #   counted in the wrong unit. The rule enforced here is the one both this function's
    #   Raises clause and ``layouts._require_byte_chunk`` already state.
    if isinstance(record, memoryview) and (record.ndim != 1 or record.itemsize != 1):
        raise TypeError(_failure(_describe_wrong_view(record), field=field))

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


# WHY : Assumptions: this function is the FIRST AND ONLY transcoding boundary in the
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

    Assumptions: the decode is PROVED on every span rather than trusted from the page proof
    :func:`_codec_for` performed, and the three checks below are exactly the per-field half of
    that proof: the codec consumed every byte, it produced one character per byte, and
    re-encoding those characters returns the span unchanged. The page proof establishes the
    tables are sound for all 256 values; these establish that this call actually behaved that
    way, which is what turns "the page is capable of a faithful decode" into "this field's
    value stands for these bytes".

    Trade-offs: proving each span costs one extra encode per field, roughly doubling the
    codec work in a decode whose cost is dominated by everything else -- for the whole shipped
    corpus that is 10672 extra calls into a C-level table lookup, against the database round
    trips the loaded rows then cost. What it buys is that a value
    which does not stand for its bytes cannot leave this function, and the alternative on offer
    was to trust the page proof and discover a table disagreement as a money-total difference
    at the end of a load.

    Parameters
    ----------
    span : bytes
        Exactly the field's declared bytes, already sliced by :func:`_require_span`.
    field : FieldSpec
        The descriptor being decoded, used to render safe geometry in a failure.
    code_page : str
        The code page to decode through, normally :data:`EBCDIC_CODE_PAGE`. It is resolved and
        proved by :func:`_codec_for`, so an unvetted page never reaches the codec.

    Returns
    -------
    str
        The decoded characters, exactly one per byte, so the result is ``field.length``
        characters wide. That width is proved on this span rather than assumed.

    Raises
    ------
    EbcdicFieldDecodeError
        If ``code_page`` is not registered, is not one of the vetted pages named in
        :data:`SUPPORTED_CODE_PAGES`, or fails the page proof; if the span holds a byte value
        that page leaves undefined; or if this decode does not consume every byte of the span,
        does not yield exactly one character per byte, or does not re-encode to the span
        byte for byte.
    """
    codec = _codec_for(code_page, field)

    # WHY : Assumptions: the unit handed to the codec is ONE FIELD'S SPAN and never a
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
    # WHY : Trade-offs: the accepted cost is one call per field rather than one per record,
    #   so a reader cannot take a shortcut for a field it believes is plain text. What that buys
    #   is one place to review, one place to test against known-answer vectors, and no second
    #   implementation of the conversion anywhere in the package that could drift from this one.
    try:
        decoded, consumed = codec.decode(span, _STRICT)
    except UnicodeDecodeError as exc:
        raise EbcdicFieldDecodeError(
            _failure(
                f"code page {code_page!r} leaves a byte value in this field undefined at"
                f" zero-based span index {exc.start}",
                field=field,
                span=span,
                index=exc.start,
            )
        ) from exc

    # WHY : Assumptions: the consumed count is CHECKED rather than discarded, which is the
    #   whole reason it is bound to a name. A decode that stopped early would return the
    #   characters of a PREFIX of the field while the record kept its declared width, so the value
    #   would be short and every later offset would still look valid -- the same shape of silent
    #   failure as a wrong page. For a vetted single-byte page this cannot fire; it is checked
    #   because the guarantee has to rest on the observation and not on the expectation.
    if consumed != len(span):
        raise EbcdicFieldDecodeError(
            _failure(
                f"code page {code_page!r} consumed {consumed} of the {len(span)} bytes this field"
                " declares, so part of the field was never decoded",
                field=field,
            )
        )

    # WHY : Assumptions: the character count is required to EQUAL the byte count, because
    #   that equality is what every offset in this package rests on and what makes the returned
    #   value the field's declared width. A page yielding a different count would produce a value
    #   of the wrong length from a span of the right length, which no check downstream of the
    #   field is positioned to see.
    if len(decoded) != len(span):
        raise EbcdicFieldDecodeError(
            _failure(
                f"code page {code_page!r} decoded the {len(span)} bytes this field declares into"
                f" {len(decoded)} characters, so it is not behaving as a single-byte page",
                field=field,
            )
        )

    # WHY : Assumptions: the span is proved RECOVERABLE from the characters just produced,
    #   and that is the check strict error handling cannot make: strict refuses a byte the page
    #   leaves undefined, whereas this refuses a byte the page defines WRONGLY -- a decode and
    #   encode table that disagree, which is a real possibility for any codec registered from
    #   outside the standard library. The offending index is derived from the same span it reports,
    #   and for a page proved one character per byte a character index and a byte index are the
    #   same number, which is why the encode failure below can name a span offset at all.
    try:
        reencoded, encoded_characters = codec.encode(decoded, _STRICT)
    except UnicodeEncodeError as exc:
        raise EbcdicFieldDecodeError(
            _failure(
                f"code page {code_page!r} cannot re-encode the character it decoded from"
                f" zero-based span index {exc.start}, so its decode and encode tables disagree"
                " and this field's value does not stand for its bytes",
                field=field,
                span=span,
                index=exc.start,
            )
        ) from exc
    if encoded_characters != len(decoded) or reencoded != span:
        difference = _first_difference(span, reencoded)
        raise EbcdicFieldDecodeError(
            _failure(
                f"code page {code_page!r} does not round-trip this field: re-encoding the decoded"
                f" characters first differs from the span at zero-based index {difference}, so the"
                " decoded value does not stand for the bytes it came from",
                field=field,
                span=span,
                index=difference,
            )
        )
    return decoded


# WHY : Assumptions: the four sets below are ALLOW-LISTS, and that is what makes the
#   per-field dispatch exhaustive by construction rather than by inspection. The union of the
#   plain-text, unsigned-display and raw-byte sets is the whole of Kind today, so a member
#   added to that enumeration tomorrow belongs to none of them and reaches the trailing raise
#   in each dispatch instead of being silently swept into whichever branch happened to be
#   last. The alternative -- an if/elif chain ending in an else that decodes -- would give a
#   new packed-like regime a character decode by default, which is exactly the failure this
#   module exists to prevent.
_PLAIN_TEXT_KINDS: Final[frozenset[Kind]] = frozenset({Kind.TEXT})

# WHY : Refactoring Rationale: the unsigned display regime has its OWN set, and it was
#   previously grouped with plain text under one "character kinds" name. Grouping them meant a
#   ``PIC 9(n)`` field was decoded and returned with no check that what came back was digits,
#   so a nine-position national identifier holding cp037 letters came back as the literal
#   letters -- measured -- while the Java parity anchor refuses the same span. Plain text has
#   no content contract to check; an unsigned display field has one, and it is the same
#   contract ``zoned`` enforces. Separating the two sets is what lets the dispatch apply that
#   check to exactly the fields that have it.
_UNSIGNED_DISPLAY_KINDS: Final[frozenset[Kind]] = frozenset({Kind.UINT})

# WHY : Trade-offs: a ZONED, PACKED, BINARY or OPAQUE span leaves this module AS RAW
#   BYTES, and the dispatch that decides so happens BEFORE any code page is applied. The
#   compromise is that a caller wanting a number has to make a second call, and what it buys
#   is that a packed nibble pair or a binary word can never reach a character decoder by any
#   path. The export record is why this is not theoretical: within one 500-byte image
#   ``app/cpy/CVEXPORT.cpy`` declares the SAME PIC S9(10)V99 clause three times at three
#   different widths -- COMP-3 at seven bytes on line 50, display at twelve on line 51 and
#   COMP at eight on line 57 -- so packed, binary and zoned spans sit side by side inside a
#   single record and only the descriptor can tell them apart.
# WHY : Refactoring Rationale: OPAQUE belongs to this set and its membership is the fix
#   for a real corruption rather than a completeness tidy-up. ``EXPORT-RECORD-DATA`` was
#   declared text, so the generic record decoder pushed the whole 460-byte payload area --
#   which the five branch overlays fill with a primary account number, a card verification
#   value, a national identifier, three COMP-3 amounts and seven COMP identifiers -- through
#   cp037 and returned it as a 460-character string. Classifying the regime as raw bytes is
#   what makes that impossible by construction; :func:`decode_export_record` is how the
#   interior is read instead.
_BYTE_KINDS: Final[frozenset[Kind]] = frozenset({Kind.ZONED, Kind.PACKED, Kind.BINARY, Kind.OPAQUE})

# WHY : Assumptions: ZONED joins the two display kinds here and ONLY here, because a
#   zoned span is genuinely character data -- one printable digit per byte with the sign folded
#   into the low-order digit -- and ``zoned.decode_zoned`` documents its input as the exact
#   characters of one field, already converted at the character boundary. This module is that
#   boundary. The conversion is faithful rather than lossy: cp037 maps byte values 0xC0 to
#   0xC9 onto the characters {ABCDEFGHI, 0xD0 to 0xD9 onto }JKLMNOPQR and 0xF0 to 0xF9 onto
#   0123456789, which is character for character the two overpunch tables that module is
#   written against. PACKED, BINARY and OPAQUE are deliberately absent: their bytes are not
#   characters in any code page, so transcoding one is not a conversion but a corruption.
_TRANSCODABLE_KINDS: Final[frozenset[Kind]] = (
    _PLAIN_TEXT_KINDS | _UNSIGNED_DISPLAY_KINDS | {Kind.ZONED}
)


def _decode_unsigned_display(span: bytes, field: FieldSpec, code_page: str) -> str:
    """Decode one unsigned display field and prove its characters really are digits.

    Purpose
    -------
    Give the ``PIC 9(n)`` regime the content check its picture clause states, and return
    the field as the characters the rest of this module returns for it, so that one regime
    has one representation everywhere.

    Refactoring Rationale: this function is new. The unsigned display regime was
    previously decoded through the same branch as plain text and returned unexamined,
    which meant a nine-position identifier holding cp037 letters came back as those
    letters. Measured: a ``EXP-CUST-SSN`` span filled with the cp037 encoding of
    ``ABCDEFGHI`` returned the string ``ABCDEFGHI`` from :func:`decode_field`. The Java
    parity anchor refuses the same span, so the two implementations disagreed about a
    malformed identifier -- and the disagreement was in the direction that keeps bad data
    moving rather than the direction that stops it.

    Assumptions: the check is delegated to ``zoned.decode_zoned`` rather than written here
    as a digit scan. That module owns the display-numeric contract for both display
    regimes, it already reports the exact zero-based index of the first offending
    character, and its message goes through the same content-free field rendering this
    module uses. A digit scan written here would be a second implementation of one rule,
    and the two could then disagree about a character such as a full-width digit that
    ``str.isdigit`` accepts and the contract does not.

    Assumptions: the geometry triple handed to that codec is ``(field.length, 0, False)``,
    which is the derivation ``layouts`` documents on :attr:`Kind.UINT` and which
    ``zoned._field_geometry`` applies for the same regime: an unsigned display field
    records its digit count ONCE, as its length, and deliberately leaves both digit-count
    attributes at zero so the two cannot disagree. Passing ``signed=True`` here would ask
    the codec to fold the low-order digit as an overpunch, which turns a digit into a
    letter and would refuse every valid identifier in the corpus.

    Trade-offs: the decimal the codec returns is DISCARDED and the characters are returned
    instead. That costs one parse whose result is thrown away, and it buys a single
    representation for the regime: :func:`decode_field`, :func:`decode_field_characters`
    and :func:`decode_record` all yield the same characters for a ``PIC 9(n)`` field, and
    the published contracts keep identifiers as digit strings of declared width rather
    than as integers, because a leading zero is significant and an integer would drop it.

    Parameters
    ----------
    span : bytes
        Exactly the field's declared bytes, already sliced and width-checked.
    field : FieldSpec
        The :attr:`Kind.UINT` descriptor being decoded.
    code_page : str
        The code page to decode through, normally :data:`EBCDIC_CODE_PAGE`.

    Returns
    -------
    str
        The decoded characters at the field's full declared width, proven to satisfy the
        unsigned display contract.

    Raises
    ------
    EbcdicFieldDecodeError
        If ``code_page`` is not supported or not registered, if the span holds a byte value
        that page leaves undefined, or if the page does not decode one character per byte.
    zoned.ZonedDecimalError
        If the decoded characters are not the digits the picture clause requires. Raised by
        the display codec and left to propagate, because its own message already names the
        field through the same content-free rendering used here and adding a wrapper would
        only hide the sentence that identifies the offending position.
    """
    characters = _decode_span(span, field, code_page)
    decode_zoned(characters, field.length, 0, False, field=field)
    return characters


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
        If ``record`` is a ``str``, any type other than a byte image, or a ``memoryview``
        that is not a one-dimensional image of single bytes.
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

    # WHY : Assumptions: the same shape guard the per-field boundary applies is applied
    #   here too, and it is applied at BOTH boundaries rather than at one shared choke point
    #   because the two are reached independently -- a caller may decode one field of a record
    #   without ever calling the record decoder. Checking one and trusting the other would
    #   leave whichever path was not taken accepting a view whose offsets mean nothing.
    if isinstance(record, memoryview) and (record.ndim != 1 or record.itemsize != 1):
        raise TypeError(f"record {layout.name}: {_describe_wrong_view(record)}")

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

    Assumptions: this function is why the module docstring separates dataset SELECTION from
    dataset OPENING. Selection is a caller's decision and this function never makes it -- it
    receives one path and reaches for nothing else, no directory listing, no pattern match, no
    default location. What it does own is the open, and owning it is the point: ``"rb"`` with no
    encoding argument is the single most consequential detail in this package, and a caller
    permitted to hand in its own file object would be a caller permitted to hand in a text-mode
    one. The stat, the divisibility check and the close are all consequences of owning the open,
    not additional responsibilities taken on beside it.

    Assumptions: the division is the correctness check and the line terminator is not, so the
    size is validated up front by the layouts module's own record count rather than by
    scanning for a boundary byte. All thirteen datasets under ``app/data/EBCDIC`` divide by
    their declared record length with remainder 0, which is what makes a count derived from a
    byte size trustworthy without reading any content; a non-zero remainder means the file is
    truncated or the declared length is wrong, and either way no offset in this package can
    be trusted until it is resolved.

    Refactoring Rationale: the size used to be taken from the PATH with a separate
    ``stat`` call and the file opened afterwards, and that pairing had three measured
    defects. It resolved the path twice, so the object whose size was validated and the
    object actually read were only the same object if nothing replaced the name in between
    -- a window a caller cannot close and this function can. ``stat`` reports zero bytes for
    a special file, and zero divides by every record length with no remainder, so the
    geometry check passed for things that are not datasets at all: ``/dev/zero`` yielded
    200 001 records of low values before the probe gave up. And a named pipe BLOCKED forever
    inside the open, so a caller could not even be told what was wrong. All three are closed
    by opening ONCE, non-blocking, and validating that one descriptor.

    Assumptions: the descriptor is obtained with ``O_NONBLOCK`` set, and that single flag is
    what turns the pipe hang into a refusal. Opening a first-in-first-out file for reading
    normally waits for a writer, and the wait is unbounded; with the flag the open returns
    at once, ``fstat`` names the object as a pipe and the refusal below fires. The flag is
    then CLEARED for the regular file that survives the check, so the reads that follow are
    ordinary blocking reads and a short read cannot be mistaken for end of file. It is
    looked up with a default of zero so that a platform without it still compiles and simply
    keeps the previous blocking behaviour for pipes.

    Assumptions: the file must be a REGULAR file, and that is checked on the open descriptor
    rather than on the path for the same reason the size is. Every dataset this module reads
    is a fixed-length blocked image whose whole record count is derived from its byte size,
    which is a property only a regular file has; a pipe, a socket, a device or a directory
    has no such size, so there is nothing for the division to check and no bound on how much
    it would yield. A symbolic link TO a regular file is accepted, because resolving one is
    ordinary and the descriptor -- not the link -- is what is validated.

    Trade-offs: the size is taken and validated before the first record is yielded, which
    costs one extra system call per dataset. What it buys is that a malformed dataset raises
    BEFORE any row reaches a loader, rather than part-way through a partial load -- the
    moment at which the layouts module's streaming form has to raise instead, because a
    stream has no knowable total until it ends.

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
        If the opened dataset is not a regular file, if its byte size is not an exact
        multiple of ``layout.reclen``, if the stream ends part-way through a record, or if
        the file grew past the record count its size implied.
    OSError
        If the dataset cannot be opened or inspected, which is left to propagate unchanged
        because a missing or unreadable file is an operator's problem and this module can add
        nothing to what the operating system already reports.
    """
    file_path = pathlib.Path(path)
    reclen = layout.reclen

    # WHY : Assumptions: the flags are read-only and non-blocking, and both are
    #   load-bearing. Read-only is what keeps the reference dataset a reference dataset.
    #   Non-blocking is what makes a named pipe answerable at all: without it the open waits
    #   for a writer with no bound, and a caller that passed one by mistake never receives an
    #   error to act on. The close-on-exec flag is requested where the platform offers it so a
    #   descriptor over a dataset is not inherited by an unrelated child process.
    # WHY : Trade-offs: the flags are looked up with ``getattr`` and a default of zero
    #   rather than named directly. That costs two attribute lookups per dataset and buys a
    #   module that still imports and still works on a platform that defines neither, where the
    #   only loss is that a pipe reverts to blocking -- a narrower behaviour than a NameError at
    #   import time on a module the whole package depends on.
    open_flags = os.O_RDONLY | getattr(os, "O_NONBLOCK", 0) | getattr(os, "O_CLOEXEC", 0)
    descriptor = os.open(file_path, open_flags)

    # WHY : Assumptions: the regular-file test happens while the descriptor is still bare,
    #   and the descriptor is closed by hand if it fails, because wrapping it in a file object
    #   first would mean a refusal had to unwind through that object's own close path. The test
    #   comes before the geometry test because the geometry test cannot detect the problem:
    #   ``st_size`` is 0 for a pipe and for a character device, and 0 divides by every record
    #   length with remainder 0, so the division reported a well-formed EMPTY dataset for an
    #   endless one. Ordering the two this way means a non-dataset is named as a non-dataset
    #   instead of passing as an empty one.
    try:
        status = os.fstat(descriptor)
        if not stat.S_ISREG(status.st_mode):
            raise EbcdicRecordLengthError(
                f"dataset {file_path} is not a regular file, so it has no byte size a whole"
                f" record count of the {reclen} bytes record {layout.name} declares could be"
                " derived from; a pipe or a device reports zero bytes, which divides by every"
                " record length with no remainder and would read as a well-formed empty"
                " dataset while yielding without bound"
            )

        # WHY : Assumptions: the non-blocking flag is cleared now that the object is known
        #   to be a regular file. It was set only to keep the open from waiting on a pipe, and
        #   leaving it set would let a read return short for a reason that is not end of file --
        #   which the record engine would see as a truncated dataset.
        os.set_blocking(descriptor, True)
    except BaseException:
        os.close(descriptor)
        raise

    # WHY : Assumptions: the mode string is "rb" and there is no encoding argument, and
    #   both halves are load-bearing. Binary mode is what keeps a byte a byte; a text-mode open
    #   would apply an encoding AND universal newline translation, and this corpus contains the
    #   bytes to prove why that matters -- 4153 low values and 5 bytes of 0x0A inside
    #   AWS.M2.CARDDEMO.EXPORT.DATA.PS, every one of the five being the low-order byte of a
    #   legitimate COMP value rather than a boundary.
    # WHY : Assumptions: the file object ADOPTS the validated descriptor rather than
    #   opening the path again, with ``closefd`` true so the context manager still owns closing
    #   it. That is the whole point of the rewrite: the bytes read below provably come from the
    #   object whose kind and size were just checked, which no second resolution of a name can
    #   promise.
    with open(descriptor, "rb", closefd=True) as stream:
        size = status.st_size

        # WHY : Assumptions: the DIVISION is the correctness check, and the presence or
        #   absence of a boundary byte is not. All thirteen datasets under ``app/data/EBCDIC``
        #   divide by their declared record length with remainder 0 -- 15000 by 300 twice, 7500 by
        #   150, 2500 by 50 twice, 25000 by 500, 105000 by 350, 350 by 350, 2550 by 50, 250000 by
        #   500, 1080 by 60, 420 by 60 and 800 by 80 -- so the record count is derivable from the
        #   byte size with nothing in between and no content read at all. A non-zero remainder
        #   means the image is truncated or the declared length is wrong, and until that is
        #   resolved no offset in this package can be trusted, which is why it is refused here
        #   instead of being rounded down.
        # WHY : Assumptions: the size comes from ``fstat`` on the OPEN descriptor and never
        #   from a second look at the name. That is what makes the count a statement about the
        #   bytes this loop is about to read rather than about whatever the name pointed at a
        #   moment ago.
        try:
            expected_records = count_fixed_length_records(size, reclen)
        except RecordLengthError as exc:
            raise EbcdicRecordLengthError(
                f"dataset {file_path} holds {size} bytes, which does not divide into whole"
                f" records of the {reclen} bytes record {layout.name} declares;"
                f" {size % reclen} bytes remain over, so the dataset is truncated or the"
                " record length is wrong"
            ) from exc

        # WHY : Trade-offs: the iterator is advanced by an explicit ``next`` inside a
        #   narrow ``try`` rather than consumed by a ``for`` wrapped in one, and the shape is
        #   deliberate. This module's length error SUBCLASSES the layouts module's, so a bound
        #   violation raised inside a broader ``try`` would be caught by the very handler meant
        #   for a truncated read and re-reported as "ended part-way through a record" -- a
        #   correct refusal wearing the wrong explanation. Narrowing the guarded region to the
        #   advance alone keeps each failure reported as itself. The cost is four extra lines.
        produced = 0
        records = iter_fixed_length_records(stream, reclen)
        while True:
            try:
                record = next(records)
            except StopIteration:
                break
            except RecordLengthError as exc:
                raise EbcdicRecordLengthError(
                    f"dataset {file_path} ended part-way through a record of the {reclen} bytes"
                    f" record {layout.name} declares, having reported {size} bytes: {exc}"
                ) from exc

            produced += 1

            # WHY : Assumptions: the count validated up front is also ENFORCED as an upper
            #   bound while streaming, so the promise made before the first record is kept for
            #   the last one. A regular file can grow while it is being read, and without this
            #   bound a file being appended to would yield past the count the caller was given
            #   -- which for a loader sizing a batch from that count is a silent overrun rather
            #   than an error. Refusing at the boundary keeps the iteration bounded by a number
            #   taken from the descriptor itself.
            if produced > expected_records:
                raise EbcdicRecordLengthError(
                    f"dataset {file_path} yielded more than the {expected_records} whole"
                    f" records its {size} bytes imply for record {layout.name}; the file grew"
                    " while it was being read, so the count this iteration promised no longer"
                    " describes it"
                )
            yield record


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
    # WHY : Assumptions: NONE of the layouts module's ASCII text-line tolerance reaches this
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

    # WHY : Trade-offs: the slicing itself is DELEGATED to the layouts module rather than
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
        :data:`EBCDIC_CODE_PAGE`, and must name one of the vetted pages in
        :data:`SUPPORTED_CODE_PAGES`. It has no effect on a numeric field, because a numeric
        field is never decoded through a character set at all.

    Returns
    -------
    str | bytes
        For :attr:`Kind.TEXT`, the decoded characters at the field's full declared width,
        untrimmed. For :attr:`Kind.UINT`, the same characters after they have been proved
        to satisfy the unsigned display contract. For :attr:`Kind.ZONED`,
        :attr:`Kind.PACKED`, :attr:`Kind.BINARY` and :attr:`Kind.OPAQUE`, exactly
        ``field.length`` raw bytes.

    Raises
    ------
    TypeError
        If ``record`` is a ``str``, is not a byte image, or is a ``memoryview`` that is not
        a one-dimensional image of single bytes.
    EbcdicFieldDecodeError
        If the record ends before the field's exclusive end, if ``code_page`` is not
        supported or not registered, if the span holds a byte value that page leaves
        undefined, if the page does not decode one character per byte, or if the descriptor
        declares a storage regime this module has no rule for.
    zoned.ZonedDecimalError
        If a :attr:`Kind.UINT` span decodes to characters that are not the digits its
        picture clause requires.
    """
    image = _require_record_bytes(record, field)
    span = _require_span(image, field)

    # WHY : Assumptions: the three branches are allow-list membership tests and the
    #   function ends in a raise rather than in a fallback decode. Their union is the whole of
    #   Kind, so the raise is unreachable for today's six members and is the guard that keeps a
    #   seventh from being decoded as text by default -- a silent outcome, because a wrongly
    #   decoded span keeps the record's declared width and every field after it still parses.
    if field.kind in _PLAIN_TEXT_KINDS:
        return _decode_span(span, field, code_page)
    if field.kind in _UNSIGNED_DISPLAY_KINDS:
        return _decode_unsigned_display(span, field, code_page)
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

    Assumptions: :attr:`Kind.OPAQUE` is refused here for the same reason and with more at
    stake, because such an area is not one regime but several at once. The corpus has exactly
    one -- ``EXPORT-RECORD-DATA``, whose 460 bytes the five branch overlays fill with a
    primary account number, a card verification value, a national identifier, three
    ``COMP-3`` amounts and seven ``COMP`` identifiers -- and it WAS reaching this path,
    because the area was declared as the character field its picture clause literally
    describes. Its interior is read by :func:`decode_export_record`, which takes the
    discriminator from the envelope and decodes the payload against the branch it selects.

    Refactoring Rationale: the unsigned display regime is admitted here WITHOUT its digit
    check, which :func:`decode_field` applies and this function deliberately does not. The
    asymmetry is intentional: this is the character boundary, and its one consumer is the
    display codec, which performs that exact check on the characters it is handed. Applying
    it here as well would run one contract twice and would make this function refuse a span
    on behalf of a caller that was about to refuse it anyway, with the second refusal
    reported from the wrong place.

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
        The character set to decode through. Defaults to :data:`EBCDIC_CODE_PAGE`, and must
        name one of the vetted pages in :data:`SUPPORTED_CODE_PAGES`.

    Returns
    -------
    str
        The decoded characters at the field's full declared width, untrimmed.

    Raises
    ------
    TypeError
        If ``record`` is a ``str``, is not a byte image, or is a ``memoryview`` that is not a
        one-dimensional image of single bytes.
    EbcdicFieldDecodeError
        If the field declares :attr:`Kind.PACKED`, :attr:`Kind.BINARY`, :attr:`Kind.OPAQUE`
        or any other regime outside the transcodable set, if the record ends before the
        field's exclusive end, if ``code_page`` is not registered, is not one of the vetted
        pages named in
        :data:`SUPPORTED_CODE_PAGES` or fails the page proof, or if the span does not decode
        to exactly one character per byte and re-encode to the span byte for byte.
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

    # WHY : Assumptions: the strip is right-hand only and removes the blank alone, and the
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

    Assumptions: those two forms are the ONLY absences, and each is UNIFORM. A span holding
    nothing but a mixture of the two pad bytes is a partly-written field rather than an
    unwritten one, and it is REFUSED rather than reported as absent -- no writer in the corpus
    produces such a span, so admitting it would convert an undetected corruption into a null
    column. A span that holds real characters alongside a run of either pad byte is content and
    is returned unchanged, exactly as a populated stamp is.

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
        The character set to decode through. Defaults to :data:`EBCDIC_CODE_PAGE`, and must
        name one of the vetted pages in :data:`SUPPORTED_CODE_PAGES`.

    Returns
    -------
    str | None
        The decoded characters at the field's full declared width when the field holds a
        stamp, or ``None`` when EVERY position is a blank or EVERY position is a low value.

    Raises
    ------
    TypeError
        If ``record`` is a ``str``, is not a byte image, or is a ``memoryview`` that is not a
        one-dimensional image of single bytes.
    EbcdicFieldDecodeError
        If the field does not declare :attr:`Kind.TEXT`, if the record ends before the field's
        exclusive end, if ``code_page`` is not registered, is not one of the vetted pages named
        in :data:`SUPPORTED_CODE_PAGES` or fails the page proof, or if the span does not decode
        to exactly one character per byte and re-encode to the span byte for byte, or if the
        span holds nothing but a MIXTURE of the two pad bytes, which is a partly-written stamp
        rather than an absent one.
    """
    # WHY : Assumptions: every timestamp in the corpus is declared as a character field of
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

    # WHY : Assumptions: an all-blank stamp is a LEGITIMATE VALUE and is reported as absent
    #   rather than raised on, and this is the ordinary case in the shipped corpus rather than an
    #   edge one. Measured: ALL 300 records of ``AWS.M2.CARDDEMO.DALYTRAN.PS`` carry 26 blanks in
    #   ``DALYTRAN-PROC-TS`` at zero-based offset 304, because the posting run is what writes that
    #   stamp and the extract is its input; all 300 of the same records carry a populated
    #   ``DALYTRAN-ORIG-TS`` at offset 278. Raising on the blank form would therefore refuse every
    #   record of the daily transaction extract.
    # WHY : Assumptions: the low value is accepted as the second form of the same fact, also
    #   on measured evidence: ``AWS.M2.CARDDEMO.DALYTRAN.PS.INIT`` is one 350-byte primer record of
    #   which 342 bytes are 0x00, and BOTH of its 26-byte timestamp fields sit inside that run, so
    #   neither stamp was ever written. Recognising only blanks would hand a caller 26 low-value
    #   characters for a field that holds nothing, which a timestamp column cannot accept and which
    #   no later validation could explain.
    # WHY : Trade-offs: a stamp that IS present is returned exactly as decoded, character for
    #   character, even when its descriptor sets ``normalize_ts``. That flag MARKS which fields a
    #   golden-master comparison may blank before comparing; it does not ask this module to blank
    #   them. All 500 records of ``AWS.M2.CARDDEMO.EXPORT.DATA.PS`` carry a populated
    #   ``EXPORT-TIMESTAMP``, and that field IS so marked, so the distinction is exercised by real
    #   data rather than only stated. Normalising on the way through would make the original value
    #   unrecoverable and leave the parity check unable to show what actually differed, so the
    #   accepted cost is that a caller doing parity work performs its own blanking step against the
    #   same flag.
    if all(character == _BLANK for character in decoded):
        return None
    if all(character == _LOW_VALUE for character in decoded):
        return None

    # WHY : Assumptions: a span that holds NOTHING BUT a MIXTURE of the two sentinels is
    #   REFUSED rather than reported as absent, and the distinction is the whole point of the
    #   two uniform tests above. Only two forms are documented as legitimate and each is uniform:
    #   26 blanks, which all 300 records of ``AWS.M2.CARDDEMO.DALYTRAN.PS`` carry in
    #   ``DALYTRAN-PROC-TS``, and 26 low values, which the primer record
    #   ``AWS.M2.CARDDEMO.DALYTRAN.PS.INIT`` carries because 342 of its 350 bytes were never
    #   written. A span holding some of each is neither: it is a field that was partly written or
    #   partly overwritten, and no writer in the corpus produces one. An earlier revision tested
    #   the span by stripping BOTH sentinels at once, which cannot tell the three cases apart --
    #   it reported every mixture as absent, so a stamp corrupted into thirteen blanks followed by
    #   thirteen low values became a NULL column and the corruption left no trace at all.
    # WHY : Trade-offs: the accepted cost is that a dataset which legitimately mixed the two
    #   pad bytes in one timestamp would now be refused where it used to load. That trade is taken
    #   deliberately: refusing costs one diagnostic naming the field and the counts, whereas
    #   loading costs a silent NULL that no later validation could distinguish from a stamp the
    #   posting run had simply not written yet. The diagnostic reports the two COUNTS and never
    #   the span, so a field marked sensitive discloses nothing through this path either.
    if all(character in _ABSENT_SENTINELS for character in decoded):
        blanks = decoded.count(_BLANK)
        raise EbcdicFieldDecodeError(
            _failure(
                "timestamp field holds a mixture of the two pad bytes -- "
                f"{blanks} blank and {len(decoded) - blanks} low-value positions -- and only"
                " an entirely blank or entirely low-value span denotes a stamp that was never"
                " written, so this field was partly written and cannot be reported as absent",
                field=field,
            )
        )
    return decoded


def _decode_one_field(image: bytes, field: FieldSpec, code_page: str) -> str | Decimal | bytes:
    """Decode one field to its final value, routing each numeric regime to its own codec.

    Purpose
    -------
    Perform the second half of the record decode: take the field's declared span and, for the
    three numeric regimes, turn it into an exact decimal through the codec that owns that
    regime. Plain text needs no second step, an unsigned display field needs only its content
    check, and an opaque area is returned as the bytes it is.

    Assumptions: the dispatch is on the descriptor's regime and every one of the six is named
    explicitly, with a trailing raise for anything else, so a regime added to the enumeration
    later cannot fall through into whichever branch is last.

    Refactoring Rationale: this docstring previously said the per-field DECODER was called
    first for every regime so that one reach check and one disclosure policy served them all.
    That was never what the code did, and the difference matters to a reader following the
    packed path: the two computational branches call the packed codec's FIELD-oriented entry
    points, which take the whole record image and slice the declared span themselves, so the
    per-field decoder is not on that path at all and the reach check for those two regimes is
    the packed codec's own. What actually is shared for every regime is :func:`_require_span`,
    which is called by the three branches that need characters, and the packed codec's
    equivalent for the two that do not.

    Trade-offs: the packed and binary codecs are consequently handed the WHOLE record image
    rather than a span. Those entry points locate the field from the descriptor, which is the
    form that keeps the copybook the only source of an offset; the cost is that the slice is
    taken inside that module instead of here, and what it buys is that this module never
    restates an offset the layouts module already declares.

    Parameters
    ----------
    image : bytes
        The whole record image, already proven to be the declared record length.
    field : FieldSpec
        The descriptor supplying the offset, the width, the regime, the scale and the sign
        contract.
    code_page : str
        The character set to decode a display-text or zoned span through, which must name one
        of the vetted pages in :data:`SUPPORTED_CODE_PAGES`.

    Returns
    -------
    str | Decimal | bytes
        Decoded characters at full declared width for :attr:`Kind.TEXT`, and the same for
        :attr:`Kind.UINT` once its digits are proven; an exact :class:`decimal.Decimal` at the
        field's declared scale for :attr:`Kind.ZONED`, :attr:`Kind.PACKED` and
        :attr:`Kind.BINARY`; and the untouched declared bytes for :attr:`Kind.OPAQUE`.

    Raises
    ------
    EbcdicFieldDecodeError
        If ``code_page`` is not registered, is not one of the vetted pages named in
        :data:`SUPPORTED_CODE_PAGES` or fails the page proof, if the span does not decode to
        exactly one character per byte and re-encode to the span byte for byte, or if the field
        declares a regime this module has no rule for.
    zoned.ZonedDecimalError
        If a zoned span violates its display contract, or if an unsigned display span is not
        the digits its picture clause requires.
    packed.PackedDecimalError
        If a packed or binary span violates its computational contract.
    """
    if field.kind in _PLAIN_TEXT_KINDS:
        return _decode_span(_require_span(image, field), field, code_page)
    if field.kind in _UNSIGNED_DISPLAY_KINDS:
        return _decode_unsigned_display(_require_span(image, field), field, code_page)

    # WHY : Assumptions: an opaque area is returned as its RAW BYTES and no code page is
    #   applied to it, which is the whole reason the regime exists. ``EXPORT-RECORD-DATA`` is the
    #   one such area in the corpus: its 460 bytes are redefined five ways and the overlays
    #   between them hold a primary account number, a card verification value, a national
    #   identifier, three COMP-3 amounts and seven COMP identifiers, so a character decode of
    #   the area yields 460 plausible characters and destroys every computational field in it
    #   without raising. Its interior is read by :func:`decode_export_record`, which selects the
    #   describing layout from the record-type discriminator before decoding anything.
    if field.kind is Kind.OPAQUE:
        return _require_span(image, field)
    if field.kind is Kind.ZONED:
        # WHY : Assumptions: the zoned span is decoded to characters HERE, one field wide,
        #   and only then handed on. The display codec's span-oriented entry point is used rather
        #   than its record-oriented one precisely so that the only thing ever transcoded is this
        #   one field: the record-oriented form takes a whole record as characters, and building
        #   that argument would mean transcoding the entire image, which for the export record
        #   would route 4153 low values and every packed and binary span through the code page.
        characters = _decode_span(_require_span(image, field), field, code_page)

        # WHY : Assumptions: this call is where the two meanings of the word "EBCDIC" meet,
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
) -> dict[str, str | Decimal | bytes]:
    """Decode one whole record into a mapping of field name to value, field by field.

    Purpose
    -------
    Walk a record's declared fields in declaration order and produce each one's value: the
    decoded characters for the two display-text regimes, an exact decimal for the three
    numeric ones, and the untouched bytes for an opaque area. Each field is located by its own
    declared offset and handed to the codec that owns its regime, so no part of the record is
    ever decoded as a unit.

    Assumptions: the numeric regimes are handed to their codecs in the form each one
    documents. A packed or binary field's RAW RECORD BYTES go straight to the packed codec's
    field-oriented entry points, which slice the declared span themselves, so no character set
    touches those bytes at any point. A zoned field's ONE FIELD is decoded to characters first,
    because the display codec's input is defined as the exact characters of one field already
    converted at the character boundary -- and cp037 maps the overpunch zones onto precisely
    the characters its tables are written in, so the conversion adds nothing and loses nothing.

    Assumptions: an OPAQUE area is returned as bytes and is never decoded here, because its
    interior is described by a layout this record does not name. The corpus has exactly one:
    ``EXPORT-RECORD-DATA``, whose 460 bytes the five branch overlays fill with a primary
    account number, a card verification value, a national identifier, three ``COMP-3`` amounts
    and seven ``COMP`` identifiers. Reading that interior is a SECOND, discriminator-driven
    step and :func:`decode_export_record` is the entry point for it.

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
        :data:`EBCDIC_CODE_PAGE`, and must name one of the vetted pages in
        :data:`SUPPORTED_CODE_PAGES`.

    Returns
    -------
    dict[str, str | Decimal | bytes]
        One entry per declared field, keyed by the field name exactly as the copybook spells
        it, in declaration order. A :attr:`Kind.TEXT` field maps to its decoded characters at
        full declared width, untrimmed, and a :attr:`Kind.UINT` field to the same characters
        once they are proven to be the digits its picture clause requires. A
        :attr:`Kind.ZONED`, :attr:`Kind.PACKED` or :attr:`Kind.BINARY` field maps to an exact
        :class:`decimal.Decimal` at the field's declared scale. A :attr:`Kind.OPAQUE` area maps
        to exactly its declared bytes, undecoded.

    Raises
    ------
    TypeError
        If ``record`` is a ``str``, is not a byte image, or is a ``memoryview`` that is not a
        one-dimensional image of single bytes.
    EbcdicRecordLengthError
        If the record is not exactly ``layout.reclen`` bytes, or if the layout declares two
        fields of the same name, which would make one of them unrepresentable in the result.
    EbcdicFieldDecodeError
        If ``code_page`` is not registered, is not one of the vetted pages named in
        :data:`SUPPORTED_CODE_PAGES` or fails the page proof, if a span does not decode to
        exactly one character per byte and re-encode to the span byte for byte, or if a field
        declares a storage regime this module has no rule for.
    zoned.ZonedDecimalError
        If a zoned span violates its display contract, for example a non-digit in its body or
        a low-order byte that is not a valid sign overpunch, or if an unsigned display span is
        not the digits its picture clause requires. Raised by the display codec and left to
        propagate, because its own message already names the field through the same
        content-free rendering this module uses.
    packed.PackedDecimalError
        If a packed span holds an invalid nibble or sign, or a binary span holds a value wider
        than its field declares. Raised by the numeric codec and left to propagate for the same
        reason.
    """
    image = _require_record_image(record, layout)
    decoded: dict[str, str | Decimal | bytes] = {}
    for field in layout.fields:
        # WHY : Assumptions: a repeated field name is refused rather than allowed to
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


def decode_export_record(
    record: bytes | bytearray | memoryview,
    *,
    code_page: str = EBCDIC_CODE_PAGE,
) -> tuple[dict[str, str | Decimal | bytes], dict[str, str | Decimal | bytes]]:
    """Decode one export record in the two steps its layout genuinely has.

    Purpose
    -------
    Read the 500-byte export envelope's own six fields, take the record-type discriminator
    those fields carry, ask the layouts module which of the five overlays that type selects,
    and decode the 460-byte payload against THAT layout. It returns both halves so a caller
    holds the envelope it was routed by and the branch it was routed to.

    Refactoring Rationale: this function is new, and it exists because the alternative was a
    silent corruption. ``app/cpy/CVEXPORT.cpy`` declares the payload area ``PIC X(460)`` at
    its line 19 and then redefines it five ways, so the literal reading -- character data --
    was what the generic record decoder applied: the whole area went through cp037 and came
    back as a 460-character string. Nothing raised, the record kept its declared width, and
    the primary account number at line 92, the card verification value at line 96, the
    national identifier at line 36, the three ``COMP-3`` amounts at lines 41, 50 and 52 and
    the seven ``COMP`` identifiers had all crossed a transcoding boundary they must never
    enter. Declaring the area :attr:`Kind.OPAQUE` closes that path; this function is the one
    that opens the correct one.

    Assumptions: the discriminator is read from the ENVELOPE and never guessed from the
    payload. ``layouts.export_branch`` refuses a type it does not know rather than defaulting
    to a branch, and that refusal is load-bearing: all five branches are 460 bytes, so the
    wrong branch decodes without raising and returns a full set of well-formed, meaningless
    values -- a card number read out of a customer's address, for instance.

    Assumptions: the payload is sliced with the layouts module's published payload offset
    and the branch's own declared record length, and never with a literal. Every branch field
    is declared in coordinates RELATIVE to the payload, so mixing the two coordinate systems
    shifts every branch field by exactly the header's width; taking the offset from the
    module that declares it is what makes that mistake unavailable here.

    Trade-offs: both halves are returned rather than one merged mapping. Merging would be
    convenient and was declined for two reasons: the envelope's own opaque entry would then
    sit beside the decoded interior it stands for, which invites a reader to treat the raw
    bytes as a second, disagreeing description of the same 460 bytes; and two branch layouts
    could in principle declare a field name the envelope also declares, at which point a
    merged mapping would silently lose one of them. The cost is that a caller unpacks a pair.

    Parameters
    ----------
    record : bytes | bytearray | memoryview
        One whole 500-byte export record image, read in binary mode. A ``str`` is refused.
    code_page : str
        The character set to decode the display-text and zoned fields of both halves through.
        Defaults to :data:`EBCDIC_CODE_PAGE`.

    Returns
    -------
    tuple[dict[str, str | Decimal | bytes], dict[str, str | Decimal | bytes]]
        The envelope's decoded fields first -- in which ``EXPORT-RECORD-DATA`` is still the
        undecoded 460 bytes -- and the payload's decoded fields second, keyed and typed
        exactly as :func:`decode_record` describes.

    Raises
    ------
    TypeError
        If ``record`` is a ``str``, is not a byte image, or is a ``memoryview`` that is not a
        one-dimensional image of single bytes.
    EbcdicRecordLengthError
        If the record is not exactly 500 bytes, or if the sliced payload is not the branch's
        declared length, which would mean the published payload constants and the branch
        layouts disagree.
    EbcdicFieldDecodeError
        If ``code_page`` is not supported or not registered, if a span holds a byte value that
        page leaves undefined, or if the page does not decode one character per byte.
    layouts.LayoutError
        If the record-type discriminator is not one of the five the export program writes.
        Raised by ``layouts.export_branch`` and left to propagate, because its message already
        lists the five so a caller can see what the byte should have been.
    zoned.ZonedDecimalError
        If a display span in either half violates its contract.
    packed.PackedDecimalError
        If a computational span in either half violates its contract.
    """
    envelope = decode_record(record, EXPORT_HEADER_LAYOUT, code_page=code_page)

    # WHY : Assumptions: the discriminator is taken from the DECODED envelope rather than
    #   sliced out of the image again. The envelope decode has already applied the code page to
    #   that one byte through the same per-field path as every other field, so reading it here
    #   costs nothing and cannot disagree with what the envelope reports; slicing it separately
    #   would be a second, independent statement of where the discriminator lives.
    record_type = envelope[_EXPORT_REC_TYPE_FIELD]
    if not isinstance(record_type, str):
        raise EbcdicFieldDecodeError(
            f"record {EXPORT_HEADER_LAYOUT.name} field {_EXPORT_REC_TYPE_FIELD} decoded to"
            f" {type(record_type).__name__} rather than characters, so it cannot select a"
            " payload branch; the envelope layout must declare it as character data"
        )
    branch = export_branch(record_type)

    # WHY : Assumptions: the payload comes from the OPAQUE entry the envelope already
    #   produced, so the bytes decoded against the branch are provably the same bytes the
    #   envelope reported and no second slice of the image is taken. The published payload
    #   offset is still named in the length message below because that constant is what a
    #   caller slicing for itself would use, and naming it is what makes a disagreement between
    #   the constants and the branch layouts readable rather than mysterious.
    payload = envelope[_EXPORT_PAYLOAD_FIELD]
    if not isinstance(payload, bytes):
        raise EbcdicFieldDecodeError(
            f"record {EXPORT_HEADER_LAYOUT.name} field {_EXPORT_PAYLOAD_FIELD} decoded to"
            f" {type(payload).__name__} rather than raw bytes, so it has already been through"
            " a character decoder; the envelope layout must declare the payload area as an"
            f" {Kind.OPAQUE.name} field"
        )
    if len(payload) != branch.reclen:
        raise EbcdicRecordLengthError(
            f"the export payload at zero-based offset {EXPORT_PAYLOAD_OFFSET} is"
            f" {len(payload)} bytes but branch {branch.name} declares {branch.reclen}; the"
            " published payload constants and the branch layouts must agree, because a caller"
            " slicing with the constants would otherwise decode the wrong bytes"
        )

    return envelope, decode_record(payload, branch, code_page=code_page)
