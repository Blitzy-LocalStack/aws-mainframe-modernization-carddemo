# `disclosure_group/happy_path` -- the DIRECT rate hit (`CBACT04C` resolves `15.00` on the composed key, and the `DEFAULT` fallback never runs)

One deterministic, fixed-width input record that drives the rate lookup of
[`app/cbl/CBACT04C.cbl`](../../../../../../../../app/cbl/CBACT04C.cbl) down its
**DIRECT** path: the account's own disclosure-group row is present, so the keyed
read succeeds with VSAM file **status `00`** and the status-23 `DEFAULT` fallback
is never entered.

> **Byte-encoding contract -- read this FIRST:**
> [`../../README.md`](../../README.md) is the authoritative, tree-scope,
> byte-level derivation contract for **every** fixture beneath it: the 0-based
> offset base, the five record layouts with a copybook line per field, the
> exact-length and single-trailing-newline rules, the LF-only ruling, the full
> overpunch tables with their four worked vectors, **both** `FILLER` fill regimes,
> the acceptance arithmetic, the canonical rationale labels, the per-scenario
> README obligation, the do-not-create list and the validation gates. **This
> scenario README does not restate that contract** -- no overpunch table and no
> full copybook layout of any other record type is duplicated here. It records
> only what *this* scenario does and *why*, and it exists because a fixed-width
> record cannot carry a comment: every byte position is meaningful, so an
> explanatory character would be **data**, and a row whose length is not exactly
> 50 is rejected outright.

---

## 1. Scenario intent

**The branch taken, not the number produced, is what separates this scenario from
its sibling.** On the pair used here, type `01` category `0001`, the resolved rate
is `15.00` whether the direct read answered or the `DEFAULT` fallback did, because
the two groups carry byte-identical rates on that pair. The number alone therefore
discriminates nothing, and only the *path* differs -- which is the whole reason the
two scenarios exist as a pair.

The two prove their opposite claims by opposite means, and it is worth seeing both
at once. [`../default_fallback`](../default_fallback/README.md) holds **both**
groups in one file (17 `A000000000` rows and 17 `'DEFAULT   '` rows), so absence is
unavailable to it as evidence and it must discriminate on the seed's single
differing pair; section 7.2 records which pair that is and why. **This scenario
proves its claim structurally instead:** the file contains one group only, so no
`'DEFAULT   '` row exists that could have answered, and the account's group id is
a key the file does contain, so the read resolves on the first attempt at file
status `00`.

The observable this scenario pins down is consequently an **absence**: the
`DEFAULT` substitution and the re-read do not happen at all. Section 2 cites the
two statements that do not execute, because naming them is a sharper statement of
the scenario than naming the value that is returned.

---

## 2. The exact business rule it exercises

The rate lookup of `CBACT04C`, cited statement by statement. Every line number
below was read from the file it names.

| Line | Statement | Role in this scenario |
|---|---|---|
| L210 | `MOVE ACCT-GROUP-ID TO FD-DIS-ACCT-GROUP-ID` | builds bytes 0 to 9 of the read key |
| L211 | `MOVE TRANCAT-CD TO FD-DIS-TRAN-CAT-CD` | builds bytes 12 to 15 |
| L212 | `MOVE TRANCAT-TYPE-CD TO FD-DIS-TRAN-TYPE-CD` | builds bytes 10 and 11 |
| L213 | `PERFORM 1200-GET-INTEREST-RATE` | enters the lookup |
| L415 | `1200-GET-INTEREST-RATE.` | the paragraph header |
| L416-L420 | `READ DISCGRP-FILE INTO DIS-GROUP-RECORD ... INVALID KEY ... END-READ` | the keyed read this fixture answers |
| L422 | `IF DISCGRP-STATUS = '00' OR '23'` | only those two statuses are acceptable; **`00` here** |
| L434 | `PERFORM 9999-ABEND-PROGRAM` | the path any other status takes; not taken |
| **L436** | `IF DISCGRP-STATUS = '23'` | **FALSE on a direct hit** |
| **L437** | `MOVE 'DEFAULT' TO FD-DIS-ACCT-GROUP-ID` | **does not execute** |
| **L438** | `PERFORM 1200-A-GET-DEFAULT-INT-RATE` | **does not execute** |
| L443 | `1200-A-GET-DEFAULT-INT-RATE.` | the fallback paragraph, never entered |
| L214 | `IF DIS-INT-RATE NOT = 0` | gates the computation; **true**, because the resolved rate is `15.00` |
| L215-L217 | `PERFORM 1300-COMPUTE-INTEREST` / `PERFORM 1400-COMPUTE-FEES` / `END-IF` | both run under that gate |
| L462 | `1300-COMPUTE-INTEREST.` | the consuming paragraph |
| L464-L465 | `COMPUTE WS-MONTHLY-INT = ( TRAN-CAT-BAL * DIS-INT-RATE) / 1200` | the formula that consumes the rate; divisor `1200` is 12 months x 100 |

The key is composed in copybook order, `group + type + category`, regardless of
L211 preceding L212 -- the `MOVE` order does not reorder the record's bytes.

These are **observed properties of the baseline**, and the baseline behaviour here
is simply the specification this fixture encodes. One consequence is recorded so
that a reader does not mistake this scenario for the one that exercises it: when
the fallback *is* taken and the `'DEFAULT   '` rows are absent, L455 reports the
failure and L458 reaches `9999-ABEND-PROGRAM` at L628, which issues a Language
Environment abend through `CALL 'CEE3ABD'` with `ABCODE` **999** at L631 and L632
([`../../README.md`](../../README.md) section 6.3 records that chain in full).
**This scenario exercises none of that machinery.**

---

## 3. Expected outcome

The outcome has two halves, and both are asserted.

**The resolved value.** `DIS-INT-RATE` decodes from the six bytes `00150{` to
exactly **`15.00`** at scale 2. Fed to the formula at L464-L465 with
`TRAN-CAT-BAL = 1000.00` over divisor `1200`, the monthly interest is exactly
**`12.50`**, asserted **exactly, with no floating-point tolerance**.
`tests/fixtures/README.md` section 6.2 (L570 to L580) gives the identical worked
example and the identical no-tolerance instruction, stating the formula at L573,
the divisor's derivation at L576 and the `00150{` encoding at L577.

**The resolved path.** DIRECT -- group `A000000000`, type `01`, category `0001`,
file status `00` -- and **not** the `DEFAULT` fallback. The branch that is *not*
taken is part of the expected outcome here, not a side note: a consumer that
asserted only `12.50` would pass identically if the fallback had produced it.

Assumptions: the consuming arithmetic multiplies at full precision and only then
divides, which is AAP Rule T4 (Arithmetic order is preserved). The consequence is
measurable on this exact vector rather than a matter of style. Multiply first:
`(1000.00 x 15.00) / 1200` is `12.5000`, which is **`12.50`** at scale 2. Divide
first, rounding the quotient to the money scale of 2 before multiplying:
`(1000.00 / 1200)` is `0.83`, and `0.83 x 15.00` is `12.4500`, which is
**`12.45`** -- a **five-cent** divergence on a single category balance. That is
why a fixture supplies the exact rate bytes and never a pre-computed product, and
why the order is stated here rather than left to the consumer to choose.

---

## 4. Fixture bytes and governance

### 4.1 Record width, record count, line ending and determinism

| File | Copybook | `RECLN` | Organization | Key (offset/len) | Line ending | Records |
|---|---|---:|---|---|---|---:|
| `discgrp.txt` | `CVTRA02Y` | 50 | INDEXED | `DIS-GROUP-KEY` off 0 len 16 (group 10 + type 2 + cat 4) | LF | 1 |

**Acceptance arithmetic.** [`../../README.md`](../../README.md) section 5.7 fixes
the size of any record file as `bytes = rows x RECLN + rows`. For this file that
closes as `1 x 50 + 1 = 51`, and the file measures **51 bytes**. The same formula
closes at **867** for a 17-row single-group subset and at **2601** for the full
51-row seed, which is the corroboration that the arithmetic is right rather than
coincidental.

**Line endings.** LF (`\n`) only, exactly one trailing newline, no blank lines,
and zero carriage-return bytes. Because there is one trailing newline per record,
`wc -l` equals the record count.

**No line-ending normalisation decision arises in this domain, and none is
recorded because none was made.** The seed
[`app/data/ASCII/discgrp.txt`](../../../../../../../../app/data/ASCII/discgrp.txt)
already ships LF-only -- measured 0 CR, 51 LF, 2601 bytes -- and
`tests/fixtures/README.md` section 3.2 lists `discgrp` among the LF seeds at
L162. Of the nine ASCII seeds, six are LF-only and three carry carriage returns
(`tcatbal` 49, `trancatg` 18, `trantype` 6), so a scenario deriving from one of
those three does face the choice and owes a rationale for it. This one does not,
and inventing one here would document a decision that was never taken.

**Determinism.** The record contains no timestamp, no random identifier and no
environment-derived value; `CVTRA02Y` declares no timestamp field at all. Every
byte is literal, so the file is reproducible and byte-stable across runs, which is
the "fixed content only" principle of `tests/fixtures/README.md` section 7
(L644 to L646).

### 4.2 Field layout, with the offset base declared

**Offsets below are 0-based: the first byte of the record is at offset 0.**
[`../../README.md`](../../README.md) section 3.1 declares that base once for the
whole tree and section 6.8 records the choice. The base is repeated here because
it cannot be inferred and because getting it wrong is silent.

| Copybook line | Field | `PICTURE` | Offset | Length | Value in this fixture |
|---|---|---|---:|---:|---|
| L6 | `DIS-ACCT-GROUP-ID` (level 10, in `DIS-GROUP-KEY`) | `X(10)` | 0 | 10 | `A000000000` |
| L7 | `DIS-TRAN-TYPE-CD` (level 10, in `DIS-GROUP-KEY`) | `X(02)` | 10 | 2 | `01` |
| L8 | `DIS-TRAN-CAT-CD` (level 10, in `DIS-GROUP-KEY`) | `9(04)` | 12 | 4 | `0001`, plain digits, no overpunch |
| L9 | `DIS-INT-RATE` | `S9(04)V99` | 16 | 6 | `00150{`, decoding to `15.00` |
| L10 | `FILLER` | `X(28)` | 22 | 28 | twenty-eight ASCII `'0'` (0x30) |

Byte-count check: 10 + 2 + 4 + 6 + 28 = **50**, and the row measures 50
characters. All three key fields sit at level 10 inside `DIS-GROUP-KEY`
(`CVTRA02Y` L5), so the read key is the record's first 16 bytes.

**A second, independent source states the same geometry**, which is why it is
cited rather than left resting on the copybook alone:
[`app/jcl/DISCGRP.jcl`](../../../../../../../../app/jcl/DISCGRP.jcl) L40 defines
the cluster with `KEYS(16 0)` -- a 16-byte key at offset 0 -- and L41 with
`RECORDSIZE(50 50)`, a fixed 50-byte record. The copybook banner at `CVTRA02Y` L2
declares `RECLN = 50` independently of both.

### 4.3 Three encoding traps, each of which produces a plausible wrong answer

These are called out because none of them fails loudly. A record that falls into
any of them is the right length with the right line ending and the wrong value.

**Trap 1 -- `FILLER` is twenty-eight ASCII `'0'` (0x30), not spaces.** Measured:
the 28 bytes at offset 22 have exactly one distinct value across all 51 seed rows,
and a hexdump confirms `0x30`. The trap is that the generic house padding rule
(`tests/fixtures/README.md` section 3.1, L152 to L156) right-pads a text `X` field
with **spaces**, and this `FILLER` is `PIC X(28)` -- so applying the generic rule
yields twenty-eight spaces where the seed has twenty-eight zeros. That is a
different 50 bytes, and no length check and no line-ending check can catch it. The
governing precedent is `tests/fixtures/README.md` section 5.6 (L432 to L435), the
seed-`'0'` convention preserved *"for a clean byte-diff against the seed-shaped
golden"*. This tree carries **two opposite `FILLER` fill regimes**, both tabled in
[`../../README.md`](../../README.md) section 5.3; this domain is firmly the
ASCII-`'0'` one, and `FILLER` content is copied byte for byte rather than
synthesised.

**Trap 2 -- the trailing `{` is an overpunched low-order digit, not a bare sign
marker and not a brace character.** A zoned-decimal field carries no `+`, no `-`
and no literal decimal point: `V` occupies no byte, so the implied point is a
property of the `PICTURE` and never reaches the record. Positive digits map to
`{ABCDEFGHI` and negative digits to `}JKLMNOPQR`, **position in the string is the
digit value**, and the overpunch replaces the **low-order digit only**. The worked
vector for this fixture: `15.00` becomes the digit string `001500`, whose leading
`00150` stays as written and whose low-order `0` folds together with the positive
sign into `{`, giving **`00150{`**. Only three raw rate tokens exist anywhere in
the seed -- `00000{`, `00150{` and `00250{`, for `0.00`, `15.00` and `25.00` -- so
a fourth token in a `discgrp` fixture did not come from the seed. AAP Rule T3
(Money never leaves fixed point) forbids writing this field as a float-looking
literal: the text `15.00` is five characters plus a period, so the field would be
the wrong width and every later byte would shift. The converse matters equally --
`DIS-TRAN-CAT-CD` is unsigned `9(04)` and must **never** be overpunch-decoded --
and both oracles record that split machine-readably:
`tests/helpers/record_codec.py` L1126 declares the category with kind `uint` while
only L1127's `DIS-INT-RATE` is `zoned`, and `CopybookLayout.java` draws the same
line at L1691 and L1692 with `uint(...)` and `signedZoned(...)`. The complete
tables live in [`../../README.md`](../../README.md) section 5.4 and are
deliberately not copied here.

**Trap 3 -- the same record is tabled in this repository under both offset
bases.** Section 4.2 above is 0-based; `tests/fixtures/README.md` section 5.7
(L437 to L450) tables **this same record 1-based** as `1-10`, `11-12`, `13-16`,
`17-22`, `23-50`. Both are correct for the same 50 bytes and the conversion is
always `zeroBased = oneBased - 1`. A reader who consults the house table while
assuming this tree's base is off by one byte on **every** field.

### 4.4 Field values, derivation and provenance

**Values, in one line:** group `A000000000`, type `01`, category `0001`,
`DIS-INT-RATE` = **`15.00`** (encoded `00150{`), `FILLER` twenty-eight ASCII
`'0'`.

**Derivation.** The row is copied byte for byte out of
[`app/data/ASCII/discgrp.txt`](../../../../../../../../app/data/ASCII/discgrp.txt),
which is REFERENCE-only and is never edited. **Zero rows are authored**, and no
padding was synthesised, because the seed rows are already exactly 50 characters.

**The row is unreshaped.** Nothing about it is changed in derivation. The house
interest fixture reshapes `ACCT-GROUP-ID` from the seed's blank value to
`A000000000` to force a direct hit, but it does that in its **account** records, a
different record type; the disclosure-group row itself is verbatim in both places.
Measured confirmation: this file is byte-identical to
`tests/fixtures/interest/happy_path/discgrp.txt`.

**Sources.** Layout: `app/cpy/CVTRA02Y.cpy` L4 to L10. Business rule:
`app/cbl/CBACT04C.cbl`. Dataset definition and load: `app/jcl/DISCGRP.jcl` L40 and
L41 for the cluster geometry, L54 to L62 for the `REPRO` load of the flat file
into the KSDS.

**No PAN and no identity data, stated positively.** `DIS-ACCT-GROUP-ID` is a
**disclosure-group classifier** -- the seed's only three values are
`A000000000`, `'DEFAULT   '` and `'ZEROAPR   '` -- and **not** an account number.
This folder carries no primary account number, no account identifier, no customer
identifier and no identity-shaped field of any kind. `tests/fixtures/README.md`
section 10.3 (L818 to L831) scopes its synthetic-data attestation to *"Every
scenario README whose folder contains PAN or identity data"*, and
[`../../README.md`](../../README.md) section 8.6 applies that scoping across this
tree and records `disclosure_group` as **not required**. The attestation therefore
does not attach here. That is recorded rather than left silent, because silence is
indistinguishable from oversight.

**No secrets.** The file contains no credential, no key, no token, no endpoint and
no resource identifier of any kind. It is 51 bytes of reference classification and
one interest rate.

---

## 5. Failure modes these bytes can produce

Assumptions: the modes below are the ones a consumer of *these* bytes can hit,
which is user-specified Rule 1's exceptions element applied to a fixture rather
than to a function. That element rests on Rule 1 L21, *"Exceptions or errors: any
that may be raised (where applicable)"*, and **not** on the Validation Gate at
L43, which enumerates purpose, parameters and return values only. The gate is
conjunctive in a different direction: it fails review for missing **either** the
documented content **or** the decision rationale, which is why sections 4 and 7
are both mandatory here.

- **A one-byte miscount shifts every subsequent field and corrupts the record
  silently.** Insert or drop a single character in the group id and the type code
  is read from the category's bytes, the rate span is read from the `FILLER`, and
  the decode still yields values that look like values. Section 4.2 states the
  width and every offset for exactly this reason.
- **A blank line is a zero-length record, not an absence.** An editor that appends
  a second newline turns this into a two-record file whose second record is 0
  bytes long, which fails fixed-width parsing rather than being ignored.
- **Only a genuinely zero-byte file counts as empty.** That is the sibling
  [`../empty_input`](../empty_input/README.md)'s contract and it is 0 bytes on
  disk. A file "containing no data" but holding one newline is a one-byte file
  with one zero-length record.

**The loaders are fail-closed, which is what makes the first mode survivable.**
Any row whose length is not exactly 50 is rejected outright: there is no padding,
no truncation and no silent dropping of blank lines
(`tests/fixtures/README.md` section 3.1, L140 to L151). The stated reason at L151
is a financial-integrity stance -- a malformed monetary record must never be
silently coerced into a well-formed-looking one. The Java side agrees:
`FixedWidthCodec` validates the record length **before any field is read** and
raises `RecordLengthException` naming the layout, the expected record length and
the actual length received. The tree-scope catalogue of failure modes is
[`../../README.md`](../../README.md) section 12 and is not duplicated here.

---

## 6. Consumer

Availability status, checked against the branch rather than assumed.

- **[present] `ReferenceFixtureContractTest`** (Surefire). L136 registers
  `disclosure_group/happy_path/discgrp.txt` at the `DISCGRP` record length with
  **one** expected record. L325 to L342,
  `theDirectHitFixtureIsOneRecordOnTheComposedKey()`, asserts
  `DIS-ACCT-GROUP-ID` = `A000000000`, `DIS-TRAN-TYPE-CD` = `01`,
  `DIS-TRAN-CAT-CD` = `1`, and that `DIS-INT-RATE` **is a `BigDecimal`** equal to
  `15.00`. It asserts the decoded type beside the value deliberately: a codec that
  read the rate span as text would still produce something plausible-looking.
- **[present] `ReferenceFixtureTest`** (Surefire). L408 registers the same file at
  50 bytes and 1 row. L220 to L235,
  `theHappyPathRateFixtureIsOneDirectHitRow()`, asserts one row, the three key
  fields, the rate comparing equal to `15.00`, **and its scale of 2** -- because a
  rate that decoded to the right digits at the wrong scale would satisfy a text
  comparison and change every amount derived from it.
- **[planned] a `*ServiceTest` or `*RepositoryIT` that consumes the resolved rate
  across the L464-L465 formula and asserts the derived `12.50`.** No test of those
  kinds is present in
  `services/reference-service/src/test/java/com/carddemo/reference` yet;
  [`../../README.md`](../../README.md) section 9.1 records the same status. What
  such a consumer would assert is written out in section 3, so the fixture's
  return value is documented independently of whether a reader can find a test
  that reads it.

**Byte consumers, in `common-lib`.** `CopybookLayout` supplies the geometry,
registering `DISGROUP` at record length **50**, key length **16** and key offset
**0** with the five fields of section 4.2 (L1688 to L1693); `ZonedDecimalCodec`
owns the `DIS-INT-RATE` overpunch decode; `FixedWidthCodec` composes them and
enforces the length check of section 5. A consumer asks the registry for an offset
and never hard-codes one copied out of a table
([`../../README.md`](../../README.md) section 5.8).

**A second, independent decoder can cross-check these bytes.**
`tests/helpers/record_codec.py` declares `DISGROUP_LAYOUT` at L1118 with the same
record length 50, key length 16 and key offset 0, and the same five leaf fields at
offsets 0, 10, 12, 16 and 22 (L1124 to L1128), registered in `LAYOUTS` at L1352 --
so a `discgrp` fixture can be verified against the Python oracle as well as the
Java one. That is **not** true of `trantype` or `trancatg`, which the Python oracle
does not register at all ([`../../README.md`](../../README.md) section 11.2), and
it is why `disclosure_group` is the one domain here with two independent decoders.

**This fixture is not a database seed, and nothing here should be read as
implying it is.** `services/reference-service/src/test/resources/application-test.yml`
sets `spring.flyway.schemas: reference` with `create-schemas: true`, so
`V1__reference.sql` and `V2__seed_reference.sql` both run against a bare
container and **all 51 `disclosure_groups` rows are already present** before any
test body executes -- including the 17 rows keyed `'DEFAULT   '` that
`CBACT04C`'s fallback needs. These 51 bytes are **decode, round-trip and
business-rule vectors**; they load nothing.

Consistency targets, cited rather than authored: `V1__reference.sql` gives
`disclosure_groups` the composite primary key
`(acct_group_id CHAR(10), tran_type_cd CHAR(2), tran_cat_cd CHAR(4))` with
`interest_rate NUMERIC(6,2)`, and `V2__seed_reference.sql` seeds 17 rows per group
id for all three ids. Its first tuple is `('A000000000', '01', '0001', 15.00)` --
the same key and the same rate this fixture encodes, which is the cheapest
available check that the byte form and the relational form agree.

**Sibling scenarios, for orientation only.**
[`../default_fallback`](../default_fallback/README.md) is the status-23 contrast at
34 rows and 1734 bytes, carrying both groups at once;
[`../empty_input`](../empty_input/README.md) is the zero-byte case. **This scenario
reads nothing from either.** And the contract runs
one way ([`../../README.md`](../../README.md) section 9.2): **the bytes are the
contract**, so a fixture that is wrong does not fail -- it produces a green test
that proves nothing, which is why section 4 states every byte fact explicitly
rather than leaving it to be read off the file.

---

## 7. Why these bytes and not others

User-specified Rule 1 L40 makes it a violation to leave a non-obvious choice
undocumented when a reasonable alternative exists, and L41 requires each rationale
to be specific rather than a preference. Three such choices were made here, and
each names its rejected alternative together with the measured consequence of
taking it.

The label canon has **four** members (Rule 1 L31 to L34) and three are usable in
this tree: L32 scopes `Refactoring Rationale` by its own definition to replacing
existing code, and nothing in this additive tree replaces anything, so using it
would state something false. Four in the canon, three usable -- a reconciliation,
not a shortening, and [`../../README.md`](../../README.md) section 2.2 reaches the
same reading. The labels below are plural, ASCII and unemphasised, as that section
requires.

### 7.1 One row rather than seventeen or fifty-one

Alternatives Considered: the full 51-row seed (2601 bytes) and a 17-row
single-group subset carrying every `A000000000` pair (867 bytes) were both
reachable, and either would pass. One row was chosen because the read at L416 is
keyed on the exact 16-byte composite `A000000000` + `01` + `0001`, so no other row
participates in the lookup and every additional row would be inert bytes that no
assertion depends on. The house precedent is measured, not asserted:
`tests/fixtures/interest/happy_path/discgrp.txt` is 51 bytes and 1 row carrying
this same row, and `tests/fixtures/interest/zero_balance/discgrp.txt` is
byte-identical to it, so the one-row direct-hit file is used twice there.
[`../../README.md`](../../README.md) section 5.7 states the ruling outright -- a
single-row or single-group subset of a 51-row seed is an established house pattern
and not a shortcut.

Trade-offs: a one-row file cannot demonstrate a lookup *choosing* between
candidates, so on its own it cannot show that the key discriminated. That
compromise is accepted because a direct hit is proved by the row being found at
status `00` together with the L436 branch not running, and because the sibling
`../default_fallback` carries 34 rows precisely for the claim that does need two
candidate groups present at once.

### 7.2 The `'DEFAULT   '` rows are deliberately absent

Assumptions: a single-row `A000000000` file necessarily omits the 17 rows keyed
`'DEFAULT   '`, and that is **correct for this scenario rather than an
oversight**. The account's own group resolves at file status `00`, so L436 is
false, the fallback paragraph at L443 is never entered, and nothing on the
executed path is left unresolvable. `tests/fixtures/README.md` section 7 (L642 to
L661) makes exactly this carve-out at L647 to L652 -- a scenario is self-contained
*except* where it deliberately omits a key to trigger a reject or a fallback --
and requires the omission to be labelled deliberate, which is what this paragraph
does.

Stated the other way round so that a reader does not have to infer it: **the
absence of the `'DEFAULT   '` rows here is not asserting an abend.** A fixture
that omitted them while its executed path *did* reach L438 would be asserting one,
because L455 would report the failure and L458 would reach `9999-ABEND-PROGRAM`.
[`../../README.md`](../../README.md) section 6.3 records that chain and requires
such a fixture's README to say so. This one never reaches L438.

Alternatives Considered: a file carrying **both** `A000000000` and the
`'DEFAULT   '` rows, described as a direct hit. Rejected on a measured property
rather than on taste. Across the 17 (type, category) pairs the two groups differ
on **exactly one** -- type `07` category `0001`, where `A000000000` carries
`00150{` (`15.00`) and `'DEFAULT   '` carries `00000{` (`0.00`) -- and the other
**sixteen** pairs are byte-identical. On any other pair, including this scenario's
type `01` category `0001`, the rate assertion would pass identically whether the
direct read or the fallback resolved it: vacuous coverage, which is worse than none
because it looks like coverage. [`../../README.md`](../../README.md) section 6.3
rules that a co-present design **must** exercise type `07` category `0001`, and
`../default_fallback` is exactly that design -- 34 rows carrying both groups -- so
it is bound by that rule and discriminates there. **The co-present design is not
adopted here**: this fixture holds one group and one row, which is what makes the
absence of a `'DEFAULT   '` row available as evidence in the first place.

### 7.3 Pair `01|0001` at rate `15.00`

Alternatives Considered: two other direct hits were reachable. A `25.00` hit on
pair `01|0002`, `01|0003` or `01|0004`, all three encoded `00250{`; and a
zero-rate hit through group `'ZEROAPR   '` (seven characters plus three spaces),
which is uniformly `00000{` across all 17 of its rows. `15.00` wins for a happy
path on a mechanism rather than a preference: **L214 gates L215 on
`IF DIS-INT-RATE NOT = 0`**, so a resolved rate of zero skips
`1300-COMPUTE-INTEREST` entirely and a `ZEROAPR` vector could assert only an
**absence** -- that no interest was computed -- never a specific amount. `15.00`
drives the full path through the `COMPUTE` at L464-L465 and yields the specific
number `12.50`. The `25.00` alternative drives that same path and is a legitimate
second choice; `15.00` is preferred only because the house worked example at
`tests/fixtures/README.md` L578 to L579 is stated at that rate, so a reader
checking this fixture against the house document compares like with like. The
zero-rate option would still have made a legitimate but *different* claim -- that
a **resolved** rate of zero is distinguishable from an **unresolved** lookup --
which is why it was evaluated rather than dismissed, and
[`../../README.md`](../../README.md) section 6.3 already names `'ZEROAPR   '` as
the natural zero-rate control group for a scenario that wants to make it.

---

## 8. Hazards a later edit of these bytes would create

Each row pairs an edit with the symptom it actually produces, because the
dangerous ones are the quiet ones.

| Edit | Symptom |
|---|---|
| A second row appended | Both consumers declare a record count of 1 (`ReferenceFixtureContractTest` L136, `ReferenceFixtureTest` L408), so both fail. **Loud** -- the good case |
| The rate token changed | The derived amount changes silently. `12.50` is written into section 3 so the intended value has a home outside an assertion |
| `FILLER` space-filled instead of copied | Correct length, correct line ending, wrong 50 bytes. **Silent** until an unrelated-looking comparison fails |
| The `'DEFAULT   '` rows added | This scenario's assertion keeps passing on type `01` category `0001` while no longer proving the direct read resolved it. **Vacuous** -- section 7.2 |
| Rewritten with CRLF | The row becomes 51 characters and is rejected at load. **Loud** |
| A trailing blank line appended | A zero-length record, rejected at load. **Loud** |
| An offset restated in test code instead of obtained from `CopybookLayout` | Two declarations of one geometry that drift independently. Forbidden by [`../../README.md`](../../README.md) section 5.8 |

Nothing under `app/**`, `tests/**`, `scripts/**` or `samples/**` is edited to
accommodate any of the above. Those trees are REFERENCE-only: the seed is an input
to derivation, and a fixture is created by copying rows out of it.

---

*This README is the mandatory Explainability carrier for the single static
`discgrp.txt` in this folder, required independently by user-specified Rule 1's
Validation Gate (L43) and by the `MUST` at `tests/fixtures/README.md` section 9.1
(L695 to L719), which words the obligation as "a mandatory Explainability carrier,
not a suggestion". Because a fixed-width record cannot hold a comment, the WHYs
live here: the DIRECT-versus-fallback distinction that makes the L436 branch's
absence the observable (sections 1 and 2), the exact `12.50` with the five-cent
consequence of reversing the arithmetic order (section 3), the 0-based offsets with
their 1-based house counterpart and the three silent encoding traps (section 4),
the fail-closed length contract (section 5), the honest present-and-planned
consumer status with the explicit statement that these bytes seed no table
(section 6), and the three labelled justifications -- one row, the deliberate
`'DEFAULT   '` omission with the co-present design rejected on the `07|0001`
measurement, and pair `01|0001` at `15.00` chosen because a zero rate would skip
the computation the scenario exists to exercise (section 7).*
