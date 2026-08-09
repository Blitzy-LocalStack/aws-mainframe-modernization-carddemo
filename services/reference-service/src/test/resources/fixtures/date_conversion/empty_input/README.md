# date_conversion / empty_input - a zero-byte 1000-byte-record request dataset

## 1. Intent

The **quiet queue**: a request dataset that is **present and holds no record at
all**. It pins the one input state in which the reference program's request loop
terminates without ever assembling a reply.

**This document is the sole carrier of the scenario's meaning.** Its sibling
`date-request.txt` is exactly **0 bytes** - no width to inspect, no field to read
off, nothing it can convey on its own. Without this README a reviewer opening the
directory could not tell a **deliberate empty scenario** from a **truncated commit or
a dropped file**, and those two have opposite correct responses: leave it alone, or
restore it. That distinction is settled here, from this file alone.

That is also why user-specified **Rule 1 (Explainability)** binds harder here than
anywhere else in the domain. Rule 1 L15 requires a docstring on every module entry
point; a scenario directory is the module-equivalent entry point and this README is
its docstring. L27 requires the explanation to sit adjacent to what it explains,
which is why the carrier lives here rather than upstream.

> **The byte contract is not restated here.** The 0-based offset declaration, the
> padding and overpunch tables, the line-ending and trailing-newline rules and the
> full five-layout catalogue live in the tree-scope contract
> [`../../README.md`](../../README.md). This file **references** that contract and
> never restates or contradicts it. What it states is scoped to this scenario: its
> own record geometry, its own byte counts, and the reasoning behind them.

---

## 2. The exact business rule it exercises

### 2.1 "Empty" means a zero-byte dataset and nothing else

`tests/fixtures/README.md` L144 to L151 records this as **verified enforcement, not
guidance**: the loaders **reject** any physical row whose length is not exactly the
record length; they do **not** pad short rows, **not** truncate long ones, and
**not** silently drop blank lines. At L147 to L148 the only input treated as empty is
a **genuinely zero-byte dataset** - zero bytes, no records. L184 gives the converse:
an empty line is a zero-length record that will fail fixed-width parsing. The
tree-scope contract inherits the ruling at its sec 5.2 and restates it as a
derivation decision at sec 6.5; `com.carddemo.common.codec.FixedWidthCodec` enforces
it in Java through `RecordLengthException`.

Assumptions: the ruling rests on the financial-integrity stance at
`tests/fixtures/README.md` L150 to L151 - a malformed record must never be silently
coerced into a well-formed-looking one. What an author feels is that miscounting a
width fails hard at load instead of producing a record that loads cleanly and decodes
into shifted fields.

House sec 7 at L658 to L659 requires each scenario to say **which** kind of empty it
is, because a zero-byte file and a file containing no lines are both loosely "empty"
and the codec must be told which. **This scenario is the 0-byte kind.**

### 2.2 The acceptance arithmetic at zero rows

Tree-scope sec 5.7 fixes every record file's size as `bytes = rows x RECLN + rows`.
Here `RECLN` is 1000, so `bytes = rows x 1000 + rows`; at **rows = 0** that is
**0 bytes**. Measured: `wc -c` is **0**, `wc -l` is **0**, `od -c` prints only the end
offset. There is **no line ending because there is no line** - the
one-trailing-newline rule has nothing to terminate.

### 2.3 The three non-forms, and what each would have tested instead

| Non-form | What it actually is | What it would test instead |
|---|---|---|
| a single `\n` | a **1-byte** file holding one zero-length record | the loader's **rejection** path, not its empty path |
| a blank line | a **zero-length record** (house L184) | fixed-width **parse failure** |
| 1000 spaces | **one** length-valid record the loader accepts | a populated single-record scenario - ONE record, not ZERO |

The 1000-space form is the sharpest of the three: it passes the length check exactly,
so nothing rejects it, and it would be moved into the record and answered. That is
the populated path with a blank function code, not the absence of a request.

---

## 3. Fixture inventory

| File | Record it holds zero of | Record width | Records | Bytes | Line ending |
|---|---|---:|---:|---:|---|
| `date-request.txt` | `REQUEST-MSG-COPY` | 1000 | **0** | **0** | none - there is no line |

The directory holds **exactly two files** - that record file and this README - and no
subdirectory.

Alternatives Considered: a `.gitkeep` to mark the directory intentional. Rejected
because the zero-byte `date-request.txt` **is** the payload, not a placeholder for
one; house `tests/fixtures/provisioning/empty_input/` is four 0-byte record files
plus a README and nothing else. A second empty file adds a name no consumer resolves
and invites a later reader to delete the wrong one.

Alternatives Considered: omitting the file entirely. Rejected because "present but
empty" and "absent dataset" are **different conditions** and only the former is this
scenario. An absent file makes a consumer fail to resolve a dataset -
`ReferenceFixtureTest` raises rather than skips on a missing name - whereas a present
zero-byte file lets it open the dataset and read zero records, which is the condition
under test.

---

## 4. The record layout this scenario contains zero instances of

"Zero records of *what*" is part of the meaning, so the geometry is recorded even
though no record is present. **Offsets are 0-based**, matching tree-scope sec 3.1.
All three fields are declared inline in the `WORKING-STORAGE` of
`app/app-vsam-mq/cbl/CODATE01.cbl` (524 lines, REFERENCE-ONLY), where **L109**
declares `01 REQUEST-MSG-COPY.`:

| Line | Field | `PICTURE` and `VALUE` | Offset | Length | Fill |
|---|---|---|---:|---:|---|
| L110 | `WS-FUNC` | `X(04)` `VALUE SPACES` | 0 | 4 | right-pad **space** |
| L111 | `WS-KEY` | `9(11)` `VALUE ZEROES` | 4 | 11 | left-pad ASCII **`'0'`** |
| L112 | `WS-FILLER` | `X(985)` `VALUE SPACES` | 15 | 985 | **space**, per `VALUE SPACES` |

Byte-count check: 4 + 11 + 985 = **1000**, which is also the width of the raw inbound
buffer `01 REQUEST-MESSAGE PIC X(1000)` at **L106**. Under AAP Rule T1 (Copybook is
normative) the `FILLER` drop is recorded per record: **985 bytes** dropped in the
target projection.

**The 1000-byte regime is new to this repository.** The house record-length
enumeration at `tests/fixtures/README.md` L140 to L142 lists 350, 300, 150, 50, 500
and 80 and does **not** include 1000, so no pre-existing house fixture exercises a
record of this width. Stated rather than left to imply coverage that does not exist.

There is **no money field anywhere in this record**, so AAP Rule T3 (money never
leaves fixed point) does not bite here. Hazard 4 below does.

### 4.1 Four parsing hazards, each confirmed by direct inspection

1. **Non-monotonic level numbering.** L109 is level `01` and its subordinates jump
   straight to level **10** with no intervening `05` - harmless to the layout, fatal
   to a parser assuming levels advance by a fixed step.
2. **Identical legacy sequence numbers.** All four of L109 to L112 carry `011700` in
   columns 1 to 6: four declarations sharing one number, which collapses under any
   tooling keyed on that area.
3. **Populated by a group `MOVE`, not field by field.** **L322** is
   `MOVE REQUEST-MESSAGE TO REQUEST-MSG-COPY`, with numeric re-initialisation at
   **L294** by `INITIALIZE REQUEST-MSG-COPY REPLACING NUMERIC BY ZEROES`. That is why
   a populated fixture here is one contiguous 1000-byte row, not a delimited
   structure.
4. **`WS-KEY` is UNSIGNED** - plain ASCII digits, **no sign overpunch**. Overpunch
   decoding belongs to `S9` fields and must never be applied to a `9(n)` field:
   doing so reinterprets the final digit as a signed nibble and changes the value
   that decodes out.

### 4.2 Both fill regimes, one byte apart inside one record

Recorded although no record is present, because the next author to add a populated
row here would otherwise rediscover it:

- **`WS-FUNC`** is right-space-padded at bytes **0 to 3**, per `VALUE SPACES` at L110.
- **`WS-KEY`** is `'0'`-left-padded at bytes **4 to 14** - unsigned numeric, per
  `VALUE ZEROES` at L111 and reinforced by the numeric re-initialisation at L294.
- **`WS-FILLER`** is SPACE-filled at bytes **15 to 999**, per `VALUE SPACES` at L112.
- The numeric and `FILLER` regimes therefore **coexist one byte apart in a single
  record**, across the boundary between byte 14 and byte 15.

Alternatives Considered: the ASCII `'0'` `FILLER` fill used by the three seed-derived
reference records in this tree, whose `FILLER` measures ASCII `'0'` (0x30) uniformly
across every seed row. Rejected because this record's `FILLER` is declared
`VALUE SPACES` at L112, so that regime would put **985 zeros where this record has
985 spaces**: a different 1000 bytes, a different record, and a difference no length
check would catch.

---

## 5. Expected outcome

**Zero records decoded, no reply produced, no error raised.**

### 5.1 Why no input is an ordinary terminal condition, not an error

The program primes its loop with one get at **L163**
(`PERFORM 3000-GET-REQUEST`, the paragraph declared at L283), iterates at **L164** to
**L165** (`PERFORM 4000-MAIN-PROCESS UNTIL NO-MORE-MSGS`) and terminates at **L167**.
With no request available the get returns the no-message-available reason code, so the
test at **L326** succeeds and **L327** sets `NO-MORE-MSGS`, declared
`88 NO-MORE-MSGS VALUE 'Y'` at **L14**. The loop body never executes even once: the
group `MOVE` at L322 never runs and `4000-PROCESS-REQUEST-REPLY` at **L339** is never
entered, so no clock is read and no reply is assembled.

Draining until the queue reports no message available is the loop's **normal** exit,
and the receive is bounded by a five-second wait at **L286**
(`MOVE 5000 TO MQGMO-WAITINTERVAL`), preserved in the target as a five-second
listener poll timeout. A zero-record input is a normal quiet interval, which is why
this scenario asserts an absence rather than an error.

### 5.2 The consumers, and what they assert

Both are present in this module and resolve this file from the **test classpath** as
`fixtures/date_conversion/empty_input/date-request.txt`, not by filesystem path:

- `ReferenceFixtureContractTest` enrols this path in its empty-fixture set and
  asserts the file is **exactly zero bytes**; its populated counterpart asserts
  `rows x (width + 1)` bytes, the sec 5.7 arithmetic of sec 2.2.
- `ReferenceFixtureTest` asserts an empty fixture **is zero bytes and decodes to no
  record**, and that a zero-length image raises
  `FixedWidthCodec.RecordLengthException` - a **length** failure, not an empty field
  map. It obtains geometry from a locally-built
  `CopybookLayout.RecordSpec("REQUEST-MSG-COPY", 1000, 11, 4)` whose fields sit at
  **offsets 0 / 4 / 15 with widths 4 / 11 / 985**, matching sec 4 exactly.

The queue-side consumer of this record shape,
`DateConversionMessageListener.onDateConversionRequest(...)`, decodes the fixed
1000-byte buffer through a `RecordSpec` built the same way, and its
`convert(DateConversionRequest)` is **shared** with the synchronous
`DateConversionController`, so the HTTP and queue transports produce identical
replies. **The bytes are the contract**: where a consumer's expectation and these
bytes disagree, the bytes are read rather than edited.

### 5.3 No field of the request drives the reply

Measured across all 524 lines, `WS-FUNC`, `WS-KEY` and `WS-FILLER` are referenced
**only** at their declarations at L110 to L112, plus the numeric re-initialisation at
L294 and the group `MOVE` at L322 that *fills* them. **No individual field is ever
read** - no `IF`, no `EVALUATE`, no `MOVE` takes a value out of one. The reply is
built purely from `EXEC CICS ASKTIME` at **L343 to L345** and `FORMATTIME` at **L347
to L353**, then STRINGed at **L355 to L360** from two verbatim 14-character prefixes,
`'SYSTEM DATE : '` and `'SYSTEM TIME : '`, around a `MM-DD-YYYY` date and an
`HH:MM:SS` time. Those two values are declared `PIC X(10)` and `PIC X(8)`, so the
reply carries 14 + 10 + 14 + 8 = **46 characters of content**. This scenario asserts
that **none of it is produced** - a claim about the loop, not about the fields.

Assumptions: the domain carries **two coexisting date masks and they must never be
merged.** The conversion path emits the United States ordering **`MM-DD-YYYY`** beside
`HH:MM:SS`, as the `MMDDYYYY` target at L349 and the separator at L350 establish,
while `com.carddemo.common.validation.DateEditValidator` standardises separately on
the ten-character ISO form **`YYYY-MM-DD`**. Two contracts, each correct for what it
describes.

---

## 6. Assumptions, alternatives and trade-offs

The labels used throughout are the plural, unemphasised forms Rule 1 names at L31, L33
and L34, which is also this tree's settled convention (tree-scope sec 2.2). Rule 1's
**fourth** rationale category is deliberately absent, and its absence is recorded here
so it does not read as an oversight: L32 scopes that category to **replacing existing
code**, and everything in this directory is authored beside an untouched baseline and
replaces nothing, so claiming it would be a false statement.

Assumptions: the choices here are properties of the **fixture**, not of the rule it
exercises, and each is one a later author could reverse with no length check failing.
Rule 1's gate at **L43** is conjunctive - code missing **either** the documentation or
the decision rationale fails review - so satisfying one does not excuse the other.
L29 words the rationale duty as "should"; **L43** hardens it into the audited gate,
and L43 is the sentence this file is written against.

- Alternatives Considered: representing empty as a blank line, a single newline, or a
  1000-space record. All three rejected; sec 2.3 names what each would have tested
  instead. The 0-byte form is **established house practice, not a novel choice**:
  seven house fixture files are genuinely 0 bytes, and five record files in this tree
  are, of which tree-scope sec 5.2 names this one the oldest and the one whose
  convention the other four follow.
- Alternatives Considered: the unhyphenated house basename form - all twelve distinct
  house fixture basenames are unhyphenated (`acctdata.txt`, `dailytran.txt`,
  `trnxfile.txt` and so on). The hyphenated `date-request.txt` is kept because the
  name is **identical across all four `date_conversion` scenario directories**, so a
  consumer resolves a scenario's fixture from the **scenario name alone** and no
  per-scenario filename table has to be maintained.
- Trade-offs: this file states its own record geometry in sec 4 while deferring the
  general byte contract upward. Restating the whole contract would leave the tree two
  copies that drift independently, and a drifted contract is worse than a single
  referenced one. Stating **this** record's three fields is not the same trade:
  tree-scope sec 8.4 requires a scenario README to carry the offset, `PICTURE`, fill
  character and declaring line **per field** as its Rule 1 L19 element, and sec 5.8
  rules that such a table is a derived index citing a declaring line rather than a
  competing declaration. Where table and program disagree, the program wins.

---

## 7. Derivation and determinism

Assumptions: **no seed dataset exists for this record.** No file in `app/data/ASCII/`
corresponds to `REQUEST-MSG-COPY` - that directory holds nine files whose widest
record is 500 bytes - and the record has **no copybook of its own**: it is declared
inline in the `WORKING-STORAGE` of `CODATE01.cbl` and of the sibling
`app/app-vsam-mq/cbl/COACCT01.cbl`, and nowhere else. Nothing here was copied from a
dataset or derived from one.

That changes what "correct bytes" means. With no seed to compare against, correctness
rests entirely on the **three field widths, the two fill characters and the 1000-byte
total** in sec 4 - and, for this scenario, on the total byte count being **exactly
zero**.

The fixture carries **no timestamp, no clock value and no environment-derived
value**; it carries no byte at all. Every run therefore reads the identical input and
the scenario is byte-deterministic by construction. The reply the program would build
from `ASKTIME` is a clock read, a further reason this scenario asserts that no reply
is produced rather than asserting a reply's content.

### 7.1 Failure modes these bytes can produce

Rule 1 L21 asks for exceptions; a fixture's are the ones its consumers raise.

- **A one-byte miscount shifts every subsequent field.** The symptom is a
  plausible-looking wrong value rather than an exception, which is why sec 3 states
  the width and sec 4 states every offset.
- **An editor appending a final newline on save** turns this zero-byte file into a
  1-byte file holding one zero-length record; it then fails fixed-width parsing
  instead of exercising the empty path, and the change is invisible in a diff viewer.
- **A blank line is a zero-length record, not an empty file.** Only a genuinely
  zero-byte file is empty here.
- **A rewrite with CRLF endings** would add a byte per record and shift every offset
  after the first. This file is LF-only with no carriage return; measured, it has no
  line at all.

---

## 8. Data governance and synthetic provenance

The house attestation obligation at `tests/fixtures/README.md` sec 10.3 - heading at
L818, items at L824 to L828 - applies to the `date_conversion` domain because
`WS-KEY PIC 9(11)` is identity-shaped. Tree-scope sec 8.6 scopes it to exactly one of
this tree's five domains, this one, and prescribes that this scenario discharge it by
recording **positively** that a zero-byte file holds no identity data. An empty file
is **not** an exemption: house L830 to L831 records that this colocated attestation is
what makes the fixture tree audit-defensible for financial-enterprise review, and an
absent attestation is indistinguishable from an overlooked one.

1. **Synthetic, constructed rather than copied.** The geometry in sec 4 is
   **constructed from the layout** declared at `CODATE01.cbl` L109 to L112, not copied
   from any dataset - none exists for this record (sec 7).
2. **No real person and no real account.** The strongest available form of that
   statement holds here: the file contains **no bytes, and therefore no identifier of
   any kind** - no key, no name, no account number, no card number. Nothing in it to
   attest to and nothing in it to expose.
3. **No field reshaped away from a source value.** There is no source value to
   reshape: no seed exists for this record, and this file holds no record.

Assumptions: `WS-KEY` is described **only** as an 11-digit ACCTDAT-key-shaped value,
claiming no more than the program shows. The one file literal `CODATE01` declares is
`LIT-ACCTFILENAME PIC X(8) VALUE 'ACCTDAT '` at **L115 and L116**, and the ACCTDAT key
`ACCT-ID` is `PIC 9(11)` at `app/cpy/CVACT01Y.cpy` **L5**, so the widths match
exactly. But that literal is **declared and never used**: measured across all 524
lines, `CODATE01` contains **zero** `EXEC CICS READ` verbs and the literal appears at
exactly one line, its own declaration. The keyed read that actually consumes such a
key lives in the sibling `app/app-vsam-mq/cbl/COACCT01.cbl` at **L393 to L398** and is
attributed there, never to `CODATE01`.

No illustrative key value appears anywhere in this directory, including in this
document's prose. Inventing a realistic-looking 11-digit value to demonstrate the
field would place an account-shaped string in the repository for no assertion's
benefit, which the migration's no-secrets constraint forbids.

---

*This README is the mandatory Explainability carrier for the static fixture in this
directory, required independently by user-specified Rule 1 and by the house mandate at
`tests/fixtures/README.md` sec 9.1, which the tree-scope contract restates for this
tree at its sec 8.4.*
