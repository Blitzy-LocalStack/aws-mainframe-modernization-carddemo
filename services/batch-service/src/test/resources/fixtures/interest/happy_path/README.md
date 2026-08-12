# Interest fixtures, `happy_path`: the DIRECT `DISCGRP` rate hit (`12.50` interest, one account rewrite, no fee)

```text
# =============================================================================
# services/batch-service/src/test/resources/fixtures/interest/happy_path/README.md
# -----------------------------------------------------------------------------
# Purpose:
#       The sole Explainability carrier for this scenario folder. The four
#       sibling files here are static fixed-width byte images: they can hold no
#       docstring, no header line and no comment of any kind, because every byte
#       position in a record is data. Every "what" and every "why" for all four
#       therefore lives in this one file.
#
# Source of truth:
#       The COBOL baseline. The record layouts come from app/cpy/, the behaviour
#       from app/cbl/CBACT04C.cbl, the dataset and parameter contracts from
#       app/jcl/INTCALC.jcl, the raw byte values from app/data/ASCII/, and the
#       expected outputs from tests/golden/interest/happy_path/. Every figure in
#       this file was measured from those files rather than carried over from
#       prose, and every line number cited was read before it was written down.
#
# Authority:
#       The tree-level byte-encoding contract at ../../README.md governs record
#       widths, offsets, sign encoding, padding bytes, line endings and the
#       business-rule semantics. This file cites that contract BY SECTION and
#       neither restates nor contradicts it, per master section 1.3. It records
#       only what is specific to this scenario.
#
# Scope:
#       app/**, tests/**, scripts/** and samples/** are REFERENCE-ONLY and are
#       never modified. These fixtures are DERIVED from them. Nothing here edits
#       a baseline file and nothing here regenerates a golden.
# =============================================================================
```

> **Byte-encoding contract -- read this FIRST:** `../../README.md` is the authoritative,
> byte-level contract for **every** fixture in this tree. It owns the record-length ledger
> and key geometry (master section 5.1), the zoned-decimal sign-overpunch encoding (master
> section 3.3), the implied-decimal rule (master section 3.7), the measured per-record
> `FILLER` padding bytes (master section 6.1), the job-dependent `TRAN-DESC` padding split
> (master section 6.3), the line-ending and trailing-newline rules (master sections 3.8 and
> 3.9), the card-cross-reference width ruling (master section 3.10), the business-date token
> contract (master section 8.2) and the per-domain timestamp handling (master section 8.1).
> **This scenario README duplicates none of that.** There is no overpunch table here, no full
> copybook layout and no padding-byte table. Where a raw token such as `0000010000{` appears
> below, it appears only so a reader can locate this scenario's number in the raw bytes --
> it is a locator, not a restatement of the encoding rules.

---

## Why this README exists, and what enforces it

### The mandate

**No row of the migration plan asks for a README at this path.** AAP section 0.2.1.2 asks for
*"fixture records derived from the copybook layouts"* -- records, not documentation. This file
exists because of **user-specified Rule 1 (Explainability)**, whose validation gate at its line
43 fails a contribution missing **either** the documentation **or** the decision rationale. The
tree-level mandate is stated at master section 10, which makes the artifact *required* rather
than customary for every scenario directory.

### No mechanical gate reaches this folder

This matters because it is what makes this file load-bearing rather than decorative. **Two
independent legs establish that no automated documentation check can ever read this
directory:**

| Leg | Evidence | Consequence |
|---|---|---|
| 1 | `config/checkstyle/checkstyle.xml` line 209 sets `fileExtensions` to `java` on the `Checker` itself | a `.txt` or `.md` file here is outside the audit set before any suppression is consulted |
| 2 | `config/checkstyle/suppressions.xml` line 188 suppresses `[\\/]src[\\/]test[\\/]resources[\\/]fixtures[\\/]` | even a `.java` file generated into this tree is exempt from the Javadoc checks |

Either leg alone would be sufficient; both hold. **Rule 1 nonetheless binds this file in full**
-- it is simply enforced by review rather than by a linter, which makes the completeness of this
document the only guarantee there is. That is the reason the four elements below are discharged
explicitly and labelled, rather than left to be inferred from prose.

Note also what leg 2 does **not** say: the suppression covers `src/test/resources/fixtures/`
only and must never be widened toward the per-service Java tests, which are exactly where the
business rules transcribed from the COBOL are asserted. The boundary is recorded in the
suppression's own comment and nothing here broadens it.

### Scope boundary

Rule 1 governs **newly authored** artifacts. `app/**`, `tests/**` and `scripts/**` are
REFERENCE-ONLY, so **no retro-documentation of the COBOL baseline is required or permitted**
here. Baseline behaviour is cited by path and line and is never edited, and the only framing
used for a difference is *the baseline does X; the migrated Java implements Y; the divergence
is documented*.

### How reasoning is labelled here

Every non-obvious decision below carries its reasoning under one of Rule 1's category names,
written plain, plural, unparenthesised and with the colon retained:

```text
Alternatives Considered:
Assumptions:
Trade-offs:
```

Assumptions: `docs/CODE_DOCUMENTATION_STANDARD.md` lines 232-255 fix that as the **one**
permitted written form and state that emphasis markup around a label is wrong in Markdown as
well as in code, because a reviewer auditing this repository against Rule 1's validation gate
finds every rationale by literal string search across seven languages and no linter parses
prose in a Markdown file, a Dockerfile, a `.tf` file or a SQL migration. Rule 1 typesets its
four category names in bold at its lines 31-34; that bold is the rules document's own
typography and not part of a label's written form, as that standard records at its line 227.
Master section 1.4 states the same constraint for this tree, so one literal search finds every
rationale in this file, in the master and in the sibling READMEs in a single pass. The singular
and parenthesised variants that appear in the reference-only house tree are deliberately not
imitated, and the two forms are never mixed inside one file.

Rule 1's fourth category -- the one its line 32 scopes to *replacing existing code* -- is
factually unavailable in this folder. These four byte images are net-new and replace nothing:
the COBOL baseline they derive from is untouched, and the house fixture tree they parallel
remains reference-only and continues to run unchanged. Differences from either are recorded
under `Alternatives Considered:` or `Assumptions:` instead.

### How Rule 1's four docstring elements are discharged

A folder of byte images has no function signature, so the rule's four required elements are
mapped onto the fixture set and each is given its own section, so that a reviewer can see the
mapping rather than reconstruct it:

| Rule 1 element | Section | Mapped onto |
|---|---|---|
| **Purpose** | 1 | the scenario's intent and the exact branch under test |
| **Parameters** | 4 | one row per byte image: file, copybook, record length, organization, key geometry, record and byte counts, and the operative values each carries |
| **Return values** | 3 | the expected outputs, with the arithmetic shown rather than merely asserted |
| **Exceptions or errors** | 5 | the abend path the domain contains, and the authoring failures this folder can produce |

The numbered sections appear in the order master section 10 mandates for a scenario README,
which is why the **Return values** element precedes the **Parameters** element here rather than
following it.

---

## 1. Scenario intent (Rule 1: Purpose)

Deterministic, fixed-width **input** fixtures that drive `app/cbl/CBACT04C.cbl` down the
**DIRECT** disclosure-group interest path. Each account's `ACCT-GROUP-ID` is present in
`DISCGRP`, so the keyed read succeeds with VSAM file **status `00`** and the program **never
enters the status-23 fallback**. The scenario is the reference money-in / money-out interest
case: a `1000.00` category balance at a `15.00` rate yields **`12.50`** of interest per
category row, and the account that is followed by a control break has its record **rewritten**
-- the one observable account rewrite in the domain.

Stated plainly, so that no reader has to infer it from the bytes:

- This is **not** the status-23 `'DEFAULT'` fallback. That branch belongs to `../default_fallback`.
- This is **not** the zero-rate case, in which no interest transaction is written at all. That
  contrast is documented inside `../zero_balance`, which itself drives a zero **balance** and
  therefore still writes a transaction.
- This scenario **does** exercise the account-keyed alternate cross-reference path, which is
  the access path the interest job needs and the posting job does not (section 2.3).

These four files are the **data-in** side of parity verification. The corresponding data-out
side is `tests/golden/interest/happy_path/`, which is reference-only: it is read as the
expectation and is never regenerated from this tree.

---

## 2. The rule exercised, cited by program and line

All citations are to `app/cbl/CBACT04C.cbl` and `app/jcl/INTCALC.jcl` unless stated otherwise.
Both are REFERENCE-ONLY.

### 2.1 The DIRECT disclosure-group read: file status `00`

The rate lookup key is composed field by field, once per category-balance row:

| Line | Statement | Effect in this fixture |
|---|---|---|
| `:210` | `MOVE ACCT-GROUP-ID TO FD-DIS-ACCT-GROUP-ID` | `A000000000`, because the account row carries it at `[112:122]` |
| `:211` | `MOVE TRANCAT-CD TO FD-DIS-TRAN-CAT-CD` | `0001`, from the category-balance key |
| `:212` | `MOVE TRANCAT-TYPE-CD TO FD-DIS-TRAN-TYPE-CD` | `01`, from the category-balance key |
| `:213` | `PERFORM 1200-GET-INTEREST-RATE` | reads `DISCGRP` on the composed key |

`1200-GET-INTEREST-RATE` begins at `:415`. Its `READ` at `:416-420` carries an `INVALID KEY`
clause, and `:422` accepts file status `'00'  OR '23'` as success. **The composed key
`A000000000` + `01` + `0001` is exactly the single row this scenario's `discgrp.txt` carries**,
so the read returns status `00`, the `IF DISCGRP-STATUS = '23'` test at `:436` is false, and the
`'DEFAULT'` re-read at `:437-438` never runs.

`:214` then gates the whole calculation on `IF DIS-INT-RATE NOT = 0`. The rate here is `15.00`,
so the gate is satisfied and both `1300-COMPUTE-INTEREST` (`:215`) and `1400-COMPUTE-FEES`
(`:216`) are performed.

Assumptions: the gate at `:214` tests the **rate**, not the balance. That distinction is what
separates this scenario from a zero-rate scenario, in which no record is written at all, and it
is why a zero **balance** would still produce a transaction. Master section 7.2.2 is the
authority for all three outcomes; section 2.4 below names which sibling carries which.

### 2.2 The two-account control break is load-bearing

`1050-UPDATE-ACCOUNT` (`:350`) is the only paragraph that writes an account record back, and it
runs **only on an account-id change**:

- `:194` `IF TRANCAT-ACCT-ID NOT= WS-LAST-ACCT-NUM` detects the change.
- `:195` `IF WS-FIRST-TIME NOT = 'Y'` suppresses the very first entry, so the first account is
  not flushed before it has accumulated anything.
- `:196` `PERFORM 1050-UPDATE-ACCOUNT` is the flush itself.

Because `:188` `PERFORM UNTIL END-OF-FILE = 'Y'` tests its condition **before** the body, the
loop exits on the same iteration that sets the end-of-file flag, so the trailing
`ELSE` / `PERFORM 1050-UPDATE-ACCOUNT` at `:219-220` is never reached. **The last -- or only --
account is therefore never written back.** The baseline behaves that way; the migrated Java
flushes the final account; the divergence is documented in
`docs/architecture/cobol-to-service-traceability.md`, and nothing in `app/**` changes. Master
section 7.3 is the tree-level statement of the same finding.

That single property is why this folder ships **two** accounts rather than one:

| Account | Role | What it makes observable |
|---|---|---|
| `00000000001` | **non-final** | the control break to account 2 fires `1050-UPDATE-ACCOUNT`, so the `REWRITE` at `:356` genuinely executes and this account's balance changes |
| `00000000002` | **final** | no further control break follows it, so its record is not written back and the omission is visible in the golden as an unchanged balance |

Alternatives Considered: a single-account fixture, which is the smaller and more obvious shape.
Rejected because it can observe **neither** behaviour: with one account there is no control
break at all, so `1050-UPDATE-ACCOUNT` never runs, and the expectation degenerates to "no
account record changed" -- which is indistinguishable from a job that computed nothing. A third
account was also considered and rejected: it only shifts which account is "final" and buys no
additional coverage, while adding a third cross-reference chain, a third category-balance row
and a third golden record to keep in step.

Assumptions: `TCATBAL` is declared `ORGANIZATION IS INDEXED` with `ACCESS MODE IS SEQUENTIAL`
at `:28-32`, so its rows are consumed in **key order** regardless of the physical line order of
a flat fixture. `00000000001` is therefore deterministically the non-final account, and a reader
does not need to reason about the on-disk sort to predict which record is rewritten. Master
section 3.12 states the rule for the tree; the rows here are already authored in key order.

**The state change at the break is three things, not one.** All three run inside
`1050-UPDATE-ACCOUNT` before the `REWRITE` at `:356`:

| Line | Statement | Effect |
|---|---|---|
| `:352` | `ADD WS-TOTAL-INT  TO ACCT-CURR-BAL` | the accumulated interest is added to the balance |
| `:353` | `MOVE 0 TO ACCT-CURR-CYC-CREDIT` | the cycle credit is reset |
| `:354` | `MOVE 0 TO ACCT-CURR-CYC-DEBIT` | the cycle debit is reset |

The two resets are a **billing-cycle reset side effect** of the same paragraph, and they are
easy to miss when reading only the balance arithmetic. Assumptions: in this fixture both cycle
fields are already `0.00` on input, so the resets write the value they already held and are
**observationally inert here**. This folder therefore does not demonstrate the reset, and no
expectation drawn from it may be read as evidence that the reset works; a scenario intending to
demonstrate it would have to carry non-zero cycle values and say so.

`WS-TOTAL-INT` is the accumulator the break flushes. It is zeroed at `:200` on each account
change and accumulated at `:467` by `ADD WS-MONTHLY-INT TO WS-TOTAL-INT`, once per category
row. With exactly one category row per account in this fixture, the accumulated total for
account `00000000001` is a single `12.50`.

### 2.3 The alternate cross-reference path is what this file's `cardxref.txt` is for

The interest job reads `CARD-XREF` **by account id**, through the alternate key -- not through
the primary card-number key. The evidence is in three places:

| Source | Line | Statement |
|---|---|---|
| `app/jcl/INTCALC.jcl` | `:29-30` | `//XREFFILE` mounts the base cluster `AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS` |
| `app/jcl/INTCALC.jcl` | `:31-32` | `//XREFFIL1` mounts the alternate-index path `AWS.M2.CARDDEMO.CARDXREF.VSAM.AIX.PATH` |
| `app/cbl/CBACT04C.cbl` | `:38` | `ALTERNATE RECORD KEY IS FD-XREF-ACCT-ID`, alongside the primary `FD-XREF-CARD-NUM` at `:37` |
| `app/cbl/CBACT04C.cbl` | `:204-205` | `MOVE TRANCAT-ACCT-ID TO FD-XREF-ACCT-ID` then `PERFORM 1110-GET-XREF-DATA` |

So the lookup argument is the category balance's account id, resolved against `XREF-ACCT-ID` at
zero-based offset **25**. The card number the generated transaction carries is the *result* of
that read -- `:495` `MOVE XREF-CARD-NUM TO TRAN-CARD-NUM` -- which is why the two cards in the
golden appear in account order rather than in card order.

**Contrast, because the two domains differ here:** transaction posting reaches the same file
through the **primary card-number key only**, at offset 0. A fixture that satisfies one access
path does not automatically satisfy the other. Master section 4.3 is the tree-level ruling.

**The migrated counterpart of this access path** is the non-unique secondary index
`idx_card_xref_account_id`, declared at
`services/batch-service/src/test/resources/db/testharness/test-harness-schemas-and-foreign-tables.sql`
line 301. The lineage is therefore explicit end to end: the VSAM alternate index, the COBOL
alternate key at `:38`, and the PostgreSQL index that replaces both.

Assumptions: the alternate index permits duplicate keys. `app/jcl/XREFFILE.jcl:72-78` defines it
with `KEYS(11,25)` at `:74` and **`NONUNIQUEKEY`** at `:75`, so the account-keyed path may
legitimately return more than one row -- master section 4.3 records the same property. **This
two-row fixture happens to carry unique account ids, so it cannot itself demonstrate the
duplicate case**, and no expectation here may be read as evidence about it. Recorded so the
absence never reads as a claim. The same `DEFINE` also declares `RECORDSIZE(50,50)` at `:77`,
which independently corroborates the 50-byte width section 4.4 authors.

### 2.4 This scenario is one of three distinct outcomes

Master section 7.2.2 fixes three mutually exclusive disclosure-group outcomes. This folder
carries the first; the other two are reached by path, and their internal bytes are their own
READMEs' business:

| Outcome | Mechanism | Where it lives |
|---|---|---|
| **Direct hit**, file status `00` | composed key found; its rate is used | **this folder** |
| **`'DEFAULT'` fallback**, file status `23` | `:436-437` moves the literal `'DEFAULT'` into the group id and `:438` re-reads through `1200-A-GET-DEFAULT-INT-RATE` (`:443-460`) | `../default_fallback` |
| **Zero rate** | `:214` `IF DIS-INT-RATE NOT = 0` is false, so **no interest transaction is written at all** and the fee paragraph is skipped | documented as a contrast inside `../zero_balance`, whose own driver is a zero **balance** and which therefore still writes a transaction |

Those two paths are written as plain code spans rather than as links because neither sibling
README exists at the time this file is authored, and `docs/CODE_DOCUMENTATION_STANDARD.md`
lines 932-933 require a path to a document that does not exist yet to be a plain code span so
that no reader follows a reference to nothing.

---

## 3. Expected outcome (Rule 1: Return values)

Every figure in this section was byte-verified against the committed goldens in
`tests/golden/interest/happy_path/`. The arithmetic is shown rather than asserted, so a reader
can check the result without running anything.

### 3.1 The headline numbers, with the arithmetic

**Interest per category row.** `:464-465` computes:

```text
COMPUTE WS-MONTHLY-INT
 = ( TRAN-CAT-BAL * DIS-INT-RATE) / 1200
```

Substituting this scenario's inputs, in exact fixed point at every step:

```text
1000.00 x 15.00      = 15000.0000
15000.0000 / 1200    =    12.5000
at scale 2           =       12.50
```

`WS-MONTHLY-INT` is declared `PIC S9(09)V99` at `:168`, so scale 2 is the declared target rather
than a rounding choice. **`12.50` is asserted exactly, with no tolerance.** Money is exact fixed
point at every hop under master section 5.5, which also fixes what is excluded from the money
path; that contract is cited rather than restated here.

**Account balances.** One row per account, both carried in `acctdat.expected`:

| Account | Input `ACCT-CURR-BAL` | Expected | Encoded | Why |
|---|---|---|---|---|
| `00000000001` | `194.00` | **`206.50`** | `00000002065{` | `194.00 + 12.50` -- the control break to account 2 fired, so the `REWRITE` at `:356` executed |
| `00000000002` | `158.00` | **`158.00`** | `00000001580{` | unchanged -- no control break follows it, so `1050-UPDATE-ACCOUNT` never runs for it (section 2.2) |

`ACCT-CURR-BAL` is the **only** field of account `00000000001` that changes. Both cycle fields
remain `00000000000{` in the golden, exactly as section 2.2 predicts, because the resets at
`:353-354` write a zero over a zero.

**Transaction count.** **Two** interest transactions, one per `TCATBAL` category row. The reason
is structural rather than incidental: `:468` `PERFORM 1300-B-WRITE-TX` sits **inside**
`1300-COMPUTE-INTEREST` (`:462`), which `:215` performs once per category-balance row. Two rows
in, two records out.

**No fee transaction.** `1400-COMPUTE-FEES` (`:518-520`) is an **empty stub**: its body is the
single comment `* To be implemented`, followed by `EXIT.` at `:520`. It is performed at `:216`
and produces nothing. **The expectation therefore asserts the ABSENCE of a fee record**, and
`transact.expected` contains exactly two records and no third. **No fee output is fabricated for
this scenario**, and the migrated Java carries the paragraph across as an explicitly documented
no-op extension point rather than inventing behaviour for it.

**Return code.** `return_code.expected` is **`0`**. There is no reject stream in this domain and
nothing in this scenario raises the graded warn tier that the posting domain uses.

### 3.2 The generated `TRAN-RECORD`, byte by byte

`CVTRA05Y`, 350 bytes, written by `:500` `WRITE FD-TRANFILE-REC FROM TRAN-RECORD`. Zero-based
byte ranges; every value below is confirmed against `transact.expected` record 0:

| Field | Range | Value |
|---|---|---|
| `TRAN-ID` | `[0:16]` | `2024-01-15000001` |
| `TRAN-TYPE-CD` | `[16:18]` | `01` -- set by `:482` |
| `TRAN-CAT-CD` | `[18:22]` | **`0005`** -- set by `:483`, see below |
| `TRAN-SOURCE` | `[22:32]` | `System` plus 4 spaces -- set by `:484` into an `X(10)` field |
| `TRAN-DESC` | `[32:132]` | `Int. for a/c 00000000001`, 24 characters, then **exactly 76 bytes of `0x00`** |
| `TRAN-AMT` | `[132:143]` | `0000000125{` -- the `12.50` of section 3.1 |
| `TRAN-MERCHANT-ID` | `[143:152]` | `000000000` -- `:491` moves `0` into a `9(09)` field |
| `TRAN-MERCHANT-NAME` | `[152:202]` | 50 spaces -- `:492` |
| `TRAN-MERCHANT-CITY` | `[202:252]` | 50 spaces -- `:493` |
| `TRAN-MERCHANT-ZIP` | `[252:262]` | 10 spaces -- `:494` |
| `TRAN-CARD-NUM` | `[262:278]` | `9680294154603697` -- `:495`, the alternate-key read's result |
| `TRAN-ORIG-TS` | `[278:304]` | volatile, **masked** -- 26 spaces in the golden |
| `TRAN-PROC-TS` | `[304:330]` | volatile, **masked** -- 26 spaces in the golden |
| `FILLER` | `[330:350]` | **20 bytes of `0x00`** |

**Record 1 differs from record 0 in exactly three fields:** `TRAN-ID` is `2024-01-15000002`, the
`TRAN-DESC` head is `Int. for a/c 00000000002`, and `TRAN-CARD-NUM` is `0923877193247330`.
Everything else, including the amount, is byte-identical -- both accounts carry the same
`1000.00` balance at the same `15.00` rate.

**`TRAN-CAT-CD` is `0005`, not `0001`.** `:483` moves the **literal** `'05'` into a field declared
`PIC 9(04)`, so the generated record stores `0005` **regardless of the input category**, which
is `0001` in this fixture's `tcatbal.txt`. **Do not reconcile the output category to the input
category** -- they are not the same value and were never meant to be. Master section 7.2.4
records the same finding, and master section 5.2 records that the field is unsigned, so `0005`
carries plain digits with no overpunch.

**`TRAN-DESC` is NUL-padded in this domain, and that is the single most confusing byte claim in
this folder.** `:485-489` uses `STRING 'Int. for a/c ' , ACCT-ID DELIMITED BY SIZE INTO
TRAN-DESC`, which writes only the 24 characters it was given and **leaves the tail of the
receiving field untouched**, over storage that is low values -- hence exactly 76 bytes of `0x00`
at `[56:132]`. Contrast `:492-494`, where an explicit `MOVE SPACES` gives the merchant name,
city and zip **space** padding in the same record. Master section 6.3 owns the job-dependent
split and is the authority for it.

Assumptions: space-padding an interest `TRAN-DESC` shifts nothing and leaves the description
perfectly readable, so the record is the right length and every field reads correctly to a human
-- and the comparison still fails, on 76 bytes that carry no data. That is why the padding byte
is stated here as an expectation in its own right rather than left to follow from the field's
`PICTURE`.

The same hazard exists on the **input** side of this folder, in the opposite direction: the
`FILLER` bytes of `discgrp.txt` and `tcatbal.txt` are ASCII `'0'`, while those of `acctdata.txt`
and `cardxref.txt` are spaces. Those are measured properties of the corpus, not consequences of
the `PICTURE` clauses, and master section 6.1 is the measured table -- cited here rather than
reproduced, per master section 1.3.

**Both timestamps are volatile and are necessarily equal to each other.** `:496` performs
`Z-GET-DB2-FORMAT-TIMESTAMP` **once**, and `:497` and `:498` then move that one result into
`TRAN-ORIG-TS` and `TRAN-PROC-TS` respectively. So the two fields cannot differ, and both must
be normalised before comparison; the committed goldens carry 26 spaces in each, 52 contiguous
spaces in total.

Per master section 8.1, a 26-blank timestamp means two different things depending on where it
appears, and the meaning cannot be recovered from the bytes. **Stated explicitly for this
scenario: the blank timestamps shown above appear in a GOLDEN and are the product of
normalisation, not input data.** None of this folder's four input files carries a transaction
record at all, so the other reading does not arise here.

The Java side expresses this domain's masking with a dedicated layout rather than the ordinary
one: `INTTRAN` is declared at
`services/common-lib/src/main/java/com/carddemo/common/codec/CopybookLayout.java` lines
1843-1844 as `TRAN_LAYOUT.withFieldFlags("INTTRAN", "TRAN-ORIG-TS", true, false)` and registered
as a derived layout at line 2155. **An expectation for this scenario compares under `INTTRAN`,
not under `TRAN`**, because `TRAN` masks only the processing timestamp and deliberately preserves
a deterministic originating timestamp -- which is correct for posting and wrong here. That file
owns the definition; it is cited, not redefined.

### 3.3 The business-date token: this scenario is the ISO exemplar

`1300-B-WRITE-TX` builds the transaction id at `:476-480`:

```text
STRING PARM-DATE,
       WS-TRANID-SUFFIX
  DELIMITED BY SIZE
  INTO TRAN-ID
END-STRING.
```

`PARM-DATE` is declared `PIC X(10)` at `:178` and `WS-TRANID-SUFFIX` is `PIC 9(06) VALUE 0` at
`:173`, so `10 + 6 = 16` fills `TRAN-ID PIC X(16)` exactly. `:474` `ADD 1 TO WS-TRANID-SUFFIX`
increments the counter **before** it is used, which is why the first generated id always ends
`000001`. **No formatting whatsoever is applied to the ten-character prefix** -- it is copied
through as bytes.

**This scenario supplies the ISO token `2024-01-15`**, matching the committed golden byte for
byte and producing `2024-01-15000001` and `2024-01-15000002`.

Alternatives Considered: supplying the compact token `2022071800` that `app/jcl/INTCALC.jcl:22`
passes as `PARM='2022071800'`, which would make this scenario mirror the baseline job card
exactly. Rejected for this folder for two reasons. First, the committed scenario golden
`transact.expected` carries the ISO prefix, and changing the token would invalidate 20 of the
32 bytes this folder asserts across its two `TRAN-ID` fields while proving nothing new about the
rate path the scenario exists to exercise. Second, and more importantly, **the token is an
opaque ten-character passthrough**, so the domain has to exercise both shapes for the
passthrough to be genuinely tested rather than assumed: `../zero_balance` carries the compact
form, this folder carries the ISO form, and neither is privileged. Master section 8.2 is the
tree-level ruling and records the same division of labour.

Assumptions: `services/batch-service/README.md` line 506 describes the compact token as
`yyyyMMdd` followed by the literal `00` and "not an ISO date". That is accurate as a description
of what the baseline job card happens to pass, and **wrong if read as a required format for the
migrated Java** -- which is the reading to guard against. **That stronger reading is not followed
and is not propagated here.** It would invalidate this folder's ISO-prefixed golden outright, and
master section 8.2 rules that where the two statements conflict the goldens and the migrated
business-date type govern. The ten-character prefix is treated throughout as an **input
parameter of the scenario**, never as a derived value.

### 3.4 Why `12.50` is encoded, and not `12.59`

`:464-465` carries **no `ROUNDED` phrase**, so the baseline truncates. AAP Rule T4 requires the
migrated Java to **multiply at full precision first and only then divide**, with an explicit
scale and rounding mode.

**The canonical `1000.00` at `15.00` vector does not prove that ordering rule**, and this file
does not claim it does. `12.5000` divides exactly, so multiply-first and divide-first both land
on `12.50`, and truncation and half-up rounding also agree -- the vector is neutral on **both**
axes. It is the right vector for checking that this scenario's arithmetic closes and the wrong
vector for justifying the ordering.

A **discriminating** vector, for a reader who wants to see the ordering actually bite: a balance
of `0.43` at a rate of `15.00`.

```text
multiply-first:  0.43 x 15.00 = 6.4500 ; 6.4500 / 1200 = 0.005375 -> 0.01 at scale 2
divide-first:    15.00 / 1200 = 0.0125 -> 0.01 at scale 2 ; 0.43 x 0.01 = 0.0043 -> 0.00
```

One cent, on a single category balance, compounding once per category per account. Master
section 7.2.1 records the same discriminating shape. **No fixture in this folder uses that
vector**, so nothing here may be read as evidence about the ordering; it is documented so that a
reader does not mistake the canonical vector for a proof of it.

Trade-offs: `tests/fixtures/interest/happy_path/README.md` section 7 records a reproducible
end-to-end GnuCOBOL 3.2.0 run over this same two-account shape whose emitted amount and
account-1 balance both differ from the analytic figures, while confirming the **structural**
behaviour section 2.2 states -- that account 1's record changes and account 2's does not.
Consult that section for its detail rather than a paraphrase; because `app/cbl/CBACT04C.cbl` is
immutable REFERENCE material, the observation is **documented, not altered**. This folder
nonetheless encodes the analytic `12.50` and `206.50`, matching the committed goldens, and **they
are not adjusted toward the observed figure**. The compromise accepted is that one number in this
domain is analytic rather than captured, in exchange for an expectation that states the
documented business rule instead of reproducing a defect. Note additionally that the run in
question used `PARM-DATE = 2022071800` while this scenario uses `2024-01-15` -- a **different
business-date parameter**, per section 3.3 -- so the two sets of figures are not two
measurements of one run and must not be reconciled.

---

## 4. Fixture bytes and governance, per file (Rule 1: Parameters)

This section is the analogue of the rule's "name, type, and description for each parameter",
mapped onto file, layout and value. Every count below was measured from the committed bytes.

### 4.1 The four byte images

| File | Copybook | RECLN | Organization / access | Key geometry, zero-based | Records | Bytes |
|---|---|---:|---|---|---:|---:|
| `tcatbal.txt` | `app/cpy/CVTRA01Y.cpy` | 50 | INDEXED, read SEQUENTIAL (`:28-32`) | `TRAN-CAT-KEY` at offset 0, length 17 | 2 | 102 |
| `acctdata.txt` | `app/cpy/CVACT01Y.cpy` | 300 | INDEXED, read RANDOM (`:41-45`) | `ACCT-ID` at offset 0, length 11 | 2 | 602 |
| `discgrp.txt` | `app/cpy/CVTRA02Y.cpy` | 50 | INDEXED, read RANDOM (`:47-51`) | `DIS-GROUP-KEY` at offset 0, length 16 | 1 | 51 |
| `cardxref.txt` | `app/cpy/CVACT03Y.cpy` | **50** | INDEXED, read RANDOM (`:34-39`) | primary `XREF-CARD-NUM` at offset 0, length 16, **plus the ALTERNATE `XREF-ACCT-ID` at offset 25, length 11, duplicates permitted** | 2 | 102 |

The byte counts are the record count times `RECLN + 1`: the `+ 1` is the single line feed that
terminates each record. **All four files are LF-only, carry no carriage return anywhere, and end
in exactly one trailing newline** -- measured as `CR 0` in each, with a single terminator after
the last record and no second blank line. Master sections 3.8 and 3.9 own those rules; this
folder conforms to them without exception, and the one file that needed a change to do so is
recorded in section 4.4.

The `ASSIGN` names the baseline binds -- `TCATBALF`, `ACCTFILE`, `DISCGRP` and `XREFFILE` /
`XREFFIL1` -- appear at `app/jcl/INTCALC.jcl:27-36`, and each file here is named for the
lowercase dataset base that matches its binding, per master section 2. The job's fifth DD,
`TRANSACT` at `:37-39`, is the sequential **output** and has no fixture: it is what the golden
asserts.

### 4.2 Operative values

The values that decide this scenario's outcome, quoted as the raw encoded tokens so a reader can
find them in the bytes:

| Field | File and slice | Encoded | Decodes to |
|---|---|---|---|
| `TRAN-CAT-BAL`, both rows | `tcatbal.txt` `[17:28]` | `0000010000{` | `1000.00` |
| `TRAN-CAT-KEY`, row 0 / row 1 | `tcatbal.txt` `[0:17]` | `00000000001010001` / `00000000002010001` | account, then type `01`, then category `0001` |
| `DIS-INT-RATE` | `discgrp.txt` `[16:22]` | `00150{` | `15.00` |
| `DIS-GROUP-KEY` | `discgrp.txt` `[0:16]` | `A000000000010001` | group `A000000000`, type `01`, category `0001` |
| `ACCT-CURR-BAL`, row 0 / row 1 | `acctdata.txt` `[12:24]` | `00000001940{` / `00000001580{` | `194.00` / `158.00` |
| `ACCT-GROUP-ID`, both rows | `acctdata.txt` `[112:122]` | `A000000000` | the group id that makes the read a direct hit |
| `ACCT-CURR-CYC-CREDIT` / `-DEBIT`, both rows | `acctdata.txt` `[78:90]` / `[90:102]` | `00000000000{` | `0.00`, which is why the resets at `:353-354` are inert here |
| `XREF-ACCT-ID`, row 0 / row 1 | `cardxref.txt` `[25:36]` | `00000000001` / `00000000002` | the alternate-key lookup argument |

The cross-reference chains, which must resolve inside this scenario's own files:

```text
card 9680294154603697 -> customer 000000001 -> account 00000000001
card 0923877193247330 -> customer 000000002 -> account 00000000002
```

Both accounts named by the cross-reference exist in this folder's `acctdata.txt`, and both
accounts named by the category-balance keys exist in both `acctdata.txt` and `cardxref.txt`, so
**this scenario is self-contained**: no key is deliberately omitted and no read is expected to
miss. That is the property that distinguishes it from `../default_fallback`, where the
disclosure-group read is meant to miss.

The remaining account fields are carried through unchanged from the seed and are listed in
section 6 so that the provenance claim is precise about what was and was not touched.

### 4.3 The two reshaped business-rule fields

Exactly **two** fields in this folder differ from their seed values. Both are business-rule
fields and both are load-bearing for the scenario.

#### `ACCT-GROUP-ID`: ten spaces in the seed, `A000000000` here

Measured on `app/data/ASCII/acctdata.txt`, for **both** rows this folder uses:

| Field | Slice | Raw seed value |
|---|---|---|
| `ACCT-ADDR-ZIP` | `[102:112]` | `A000000000` |
| `ACCT-GROUP-ID` | `[112:122]` | **ten spaces** |

Alternatives Considered: copying the seed account rows verbatim, which is the default derivation
and would keep this file byte-identical to its provenance. Rejected because it produces the wrong
scenario outright: a blank group id composes a `DISCGRP` key that misses, file status **23** fires,
and `:436-437` moves the literal `'DEFAULT'` into the key so that `:438` re-reads through
`1200-A-GET-DEFAULT-INT-RATE`. **That is `../default_fallback`, not this folder.** Setting
`ACCT-GROUP-ID` to `A000000000` is therefore not a convenience -- it is the single input that
selects the direct-hit branch this scenario exists to exercise, and without it every other byte
here would be describing a different code path. Master section 7.2.3 records the same
measurement and the same conclusion.

Assumptions: **`ACCT-ADDR-ZIP` and `ACCT-GROUP-ID` are different fields that happen to share one
literal in this reshaped fixture** -- in the rows above both read `A000000000`, ten bytes apart at
`[102:112]` and `[112:122]`. That coincidence is what makes the confusion easy and its consequence
silent: writing the group id into the zip slice leaves the record exactly 300 bytes long, raises
no length error, fails no load, leaves the group id blank, and produces a run that takes the
fallback and still yields a plausible interest figure. **There is no symptom at all until the
golden diff.** The two offsets are stated here as distinct values for exactly that reason; master
section 5.2 is the authority for the offsets themselves.

#### `TRAN-CAT-BAL`: `0.00` in the seed, `1000.00` here

Measured on `app/data/ASCII/tcatbal.txt`: the rows for accounts `00000000001` and `00000000002`
at type `01` and category `0001` both carry `0000000000{`, which is **`0.00`**. This folder
carries `0000010000{`, which is **`1000.00`**.

Alternatives Considered: keeping the seed's zero balance, which would leave `tcatbal.txt`
byte-identical to its provenance apart from the line ending. Rejected because `(0.00 x 15.00) /
1200` is `0.00`, and a zero result makes the scenario's central assertion unobservable: a
transaction record **is** still written, because the gate at `:214` tests the rate rather than the
balance, but the account rewrite adds `0.00` to `194.00` and leaves `194.00` behind. The expected
balance would then be identical whether `1050-UPDATE-ACCOUNT` ran or not, so the one observable
rewrite in the domain would stop being observable. `1000.00` at `15.00` instead yields a clean,
hand-checkable `12.50` and a visible `194.00` to `206.50` transition. The zero-balance case is
owned by `../zero_balance`, which uses the seed balance, so the two scenarios differ in exactly
one input and each proves something the other cannot.

### 4.4 The two shape-only derivations

Neither of these changes a value. They are recorded separately from section 4.3 so that the
provenance claim stays precise: a reader diffing a row against its seed row must be able to tell
a reshaped field from a normalised shape.

#### `tcatbal.txt` is LF-normalised from a CRLF seed

Measured: `app/data/ASCII/tcatbal.txt` is 2599 bytes with **49 carriage returns and 50 line
feeds** -- 49 CRLF-terminated rows and a final bare LF. The `acctdata`, `discgrp` and `cardxref`
seeds are already LF-only. **`tcatbal.txt` is therefore the only file in this folder that needed
a line-ending change at all.**

Trade-offs: the loader treats one physical line as exactly one fixed-length record, so a stray
`\r` is absorbed as a data byte and pushes the row to **51 bytes against a declared `RECLN` of
50**, which either fails the load outright or corrupts the trailing `FILLER`. Normalising to LF
removes that hazard and makes all four files in this folder byte-uniform on their terminators.
The compromise accepted is that this file is not byte-identical to its seed rows: the divergence
is **one byte per row, in the line terminator only**, and **every content byte within the 50-byte
record is unchanged apart from the balance recorded in section 4.3**. Master section 3.8 fixes LF
as the default and requires an intentionally preserved CRLF to be declared; nothing is preserved
here, so the default applies and this note is the record of it.

#### `cardxref.txt` is padded from the seed's 36 bytes to the copybook's 50

Measured: `app/data/ASCII/cardxref.txt` is 1850 bytes across 50 rows, so its rows are **36 bytes**
wide -- the trailing `FILLER X(14)` declared at `app/cpy/CVACT03Y.cpy:8` is simply absent from the
seed. This folder authors the full **50-byte** record with those 14 bytes supplied as spaces.

Trade-offs: the loader reads one physical line as one fixed-length record of `RECLN` bytes, so a
36-byte line is 14 bytes short of the declared length -- it either fails the load or leaves the
record truncated, and any consumer addressing `[36:50]` reads past the end of the row. Authoring
the copybook width instead means one width holds for the loader and for the migrated codec alike,
and the alternative would make record length depend on which file a row came from. The
compromise accepted is that a reader diffing these rows against their seed rows sees 14 extra
bytes and has to consult this note. **This is a width completion, not a value reshape:** all
three data fields -- card number, customer id and account id -- are byte-for-byte seed. Master
section 3.10 is the tree-level ruling, and `app/jcl/XREFFILE.jcl:77` `RECORDSIZE(50,50)`
corroborates the 50-byte width from a second, unrelated source.

Assumptions: the seed lists the account-`00000000002` row **before** the account-`00000000001`
row, whereas this folder lists `...001` first. That reordering is immaterial and no expectation
depends on it: `XREF-FILE` is declared `ACCESS MODE IS RANDOM` at `:36`, so the file is only ever
read by key and never traversed in physical order. Noted only because a reader comparing this
file to the seed side by side will otherwise wonder whether the order carries meaning. The
`tcatbal.txt` rows, by contrast, **are** authored in key order, for the reason section 2.2 gives.

---

## 5. Exceptions or errors (Rule 1: Exceptions or errors)

Two classes matter here: one abend path that the domain genuinely contains, and three authoring
failures that this folder's byte-level expectations exist to prevent.

### 5.1 The abend path: a missing `'DEFAULT'` disclosure-group row

**This scenario never reaches it** -- the direct hit at `:415-435` succeeds with file status `00`
and the retry is never performed. It is documented so that a reader understands where the risk
boundary lies, because the two disclosure-group paragraphs are **not** symmetric:

| Paragraph | Line | `INVALID KEY` clause | Statuses accepted | Behaviour on anything else |
|---|---:|---|---|---|
| `1200-GET-INTEREST-RATE` | `:415` | **yes**, at `:417-419`, with two `DISPLAY`s | `'00'  OR '23'` (`:422`) | `:431-434` display, then `9999-ABEND-PROGRAM` |
| `1200-A-GET-DEFAULT-INT-RATE` | `:443` | **none** -- the `READ` at `:444` is bare | `'00'` only (`:446`) | `:455` displays `ERROR READING DEFAULT DISCLOSURE GROUP`, then `:458` performs `9999-ABEND-PROGRAM` |

**That asymmetry is the whole reason a blank group id is survivable but a missing `'DEFAULT'` row
is not.** The first paragraph treats a missed key as an expected outcome and routes it to the
fallback; the second treats anything but success as fatal. A scenario whose group id misses
therefore depends on a `'DEFAULT'` row existing, and the migrated harness seeds that baseline
independently of any scenario. This folder has no such dependency, because its key hits directly.

### 5.2 A stray carriage return overruns `RECLN`

A `\r` left in a row is not a terminator to the loader -- it is a data byte. It is absorbed into
the trailing `FILLER` and pushes the record **one byte past its declared length**, corrupting the
last field and shifting nothing else, so the failure surfaces as a wrong-length row rather than
as a readable error about the field that was destroyed. Section 4.4 records that `tcatbal.txt` is
the one file here derived from a CRLF seed, and the measured `CR 0` in all four files is the
evidence that none survived.

### 5.3 Wrong `FILLER` bytes fail the comparison on a field whose value is correct

Three distinct forms of this, all of which leave a record that reads correctly to a human:

- **Spaces where ASCII `'0'` is required.** Applying the general text-padding rule to
  `discgrp.txt` or `tcatbal.txt` produces a row differing from both the seed and the golden in 28
  and 22 bytes respectively, with the key, the rate and the balance all correct.
- **ASCII `'0'` where spaces are required**, in `acctdata.txt` or in the `cardxref.txt` tail that
  section 4.4 materialises.
- **Space-padding an interest `TRAN-DESC`.** This changes 76 bytes from `0x00` to `0x20` and
  shifts nothing, so the description is right, the record length is right, and the diff points at
  the one field a reviewer is least likely to suspect (section 3.2).

In every case the failing bytes carry no data at all. Master section 6.1 holds the measured
padding byte per record and master section 6.3 the job-dependent `TRAN-DESC` split; both are
cited rather than reproduced, so there is one place to change if a measurement is ever revised.

### 5.4 A group id written into the wrong slot converts the scenario silently

Writing `A000000000` into `[102:112]` instead of `[112:122]` leaves the record exactly 300 bytes
long, raises no error, and leaves `ACCT-GROUP-ID` blank -- which turns this folder into the
`'DEFAULT'`-fallback case while every filename, every other byte and every comment still say
`happy_path`. The run completes, produces an interest figure, and asserts the wrong one of the
three outcomes of master section 7.2.2. This is the failure mode section 4.3 exists to prevent,
and it is the reason both offsets are written out there as distinct values.

---

## 6. Synthetic-provenance attestation

Mandatory for this folder under master section 10, item 5. Master section 11.1 holds the
tree-level attestation and master section 11.3 the prohibitions that govern every byte here; both
are cited rather than restated, per master section 1.3.

### 6.1 REFERENCE-only sources these four files derive from

| Role | Path |
|---|---|
| Seed datasets | `app/data/ASCII/tcatbal.txt`, `app/data/ASCII/acctdata.txt`, `app/data/ASCII/discgrp.txt`, `app/data/ASCII/cardxref.txt` |
| Record layouts | `app/cpy/CVTRA01Y.cpy`, `app/cpy/CVACT01Y.cpy`, `app/cpy/CVTRA02Y.cpy`, `app/cpy/CVACT03Y.cpy` |
| Layout of the generated output | `app/cpy/CVTRA05Y.cpy` |
| Business-rule source | `app/cbl/CBACT04C.cbl` |
| Dataset and parameter driver | `app/jcl/INTCALC.jcl` |
| Expected outputs | `tests/golden/interest/happy_path/` |

Every one of those is **read and cited, never modified**. Nothing in this folder writes to
`app/**`, `tests/**`, `scripts/**` or `samples/**`, and no golden is regenerated from here.

### 6.2 Synthetic-data statement

**No real cardholder, account or personal data appears in this folder.** Both primary account
numbers -- `9680294154603697` and `0923877193247330` -- together with the account ids
`00000000001` and `00000000002` and the customer ids `000000001` and `000000002`, are **synthetic
test data taken byte for byte from the published AWS CardDemo sample seed datasets** under
`app/data/ASCII/`. Those seeds ship with the upstream open-source project as fabricated
demonstration data and **describe no real person and no real account**. Where such a value happens
to satisfy a check digit, it does so because the upstream synthetic seed made it so, not because
it was matched to any issuer.

No secret, credential, connection string, endpoint or hostname appears in any file in this folder,
in any form. These are record data and nothing else.

### 6.3 Every reshaped business-rule field, named

**Exactly two fields in this folder differ in value from their seed bytes. The pair below is
complete; there is no third.**

| Field | File | Seed value | Value here | Why, in full |
|---|---|---|---|---|
| **`ACCT-GROUP-ID`** at `[112:122]`, both rows | `acctdata.txt` | **ten spaces** | `A000000000` | section 4.3 -- it is the one input that selects the direct-hit branch |
| **`TRAN-CAT-BAL`** at `[17:28]`, both rows | `tcatbal.txt` | `0000000000{`, `0.00` | `0000010000{`, `1000.00` | section 4.3 -- a zero product would leave the one observable account rewrite unobservable |

### 6.4 What is deliberately **not** reshaped

A loose claim here would be inaccurate, so the carried-through values are enumerated. All of the
following are **byte-for-byte seed**:

- **The account balances.** `194.00` and `158.00`, encoded `00000001940{` and `00000001580{`.
  **These were not reshaped**, and the `206.50` of section 3.1 is an *expected output*, never an
  input.
- **The credit limits** `00000020200{` and `00000061300{`, and the **cash credit limits**
  `00000010200{` and `00000054480{`.
- **All three dates on both rows** -- open, `ACCT-EXPIRAION-DATE` and reissue: `2014-11-20`,
  `2025-05-20`, `2025-05-20` on row 0, and `2013-06-19`, `2024-08-11`, `2024-08-11` on row 1. The
  baseline field spelling is quoted as it stands, per master section 9.3, which scopes the three
  name corrections to migrated **column** names and to nothing else.
- **Both cycle fields on both rows**, `00000000000{`. Their being zero is what makes the resets at
  `:353-354` inert here, per section 2.2.
- **`ACCT-ACTIVE-STATUS`**, `Y` on both rows, and **`ACCT-ADDR-ZIP`**, `A000000000` on both rows --
  the field section 4.3 warns must not be confused with the group id.
- **Every card number, account id and customer id**, in both `cardxref.txt` and `acctdata.txt`.
- **`discgrp.txt`'s single row is byte-for-byte seed row 0, with no reshape at all** -- verified by
  direct comparison of all 50 bytes. Its `15.00` is the seed's own rate.
- **`cardxref.txt`'s three data fields are byte-for-byte seed.** Only the declared-but-absent
  `FILLER X(14)` is materialised.

### 6.5 The two shape-only derivations

Neither changes a value, and both are argued at length in section 4.4:

| File | Derivation | Content bytes affected |
|---|---|---|
| `tcatbal.txt` | **LF-normalised** from its CRLF seed | none -- the change is confined to the line terminator |
| `cardxref.txt` | **padded from 36 to 50 bytes**, the trailing 14-byte `FILLER` supplied as spaces | none -- the three data fields are untouched |


---

## 7. Re-measuring every claim in this file

Nothing above needs to be taken on trust. Both blocks are read-only and touch no baseline file.

```bash
# WHAT: print each fixture's byte count, carriage-return count, row count and the
#       set of distinct physical row widths.
# WHY : Assumptions: the loader treats one physical line as exactly one
#       fixed-length record and rejects any other width outright, so a single
#       stray carriage return or a second trailing newline is a load failure
#       rather than bad data (section 5.2). A one-element width set is the proof
#       that a whole file is uniform; a two-element set means one row was
#       authored by a different rule, which is the fastest way to catch that
#       before it reaches a comparison. Expected here: 102, 602, 51 and 102
#       bytes, CR 0 in every file, and widths [50] / [300] / [50] / [50].
cd services/batch-service/src/test/resources/fixtures/interest/happy_path
python3 - <<'PY'
import glob
for f in sorted(glob.glob('*.txt')):
    b = open(f, 'rb').read()
    rows = b.split(b'\n')[:-1]
    print(f, 'bytes', len(b), 'CR', b.count(b'\r'), 'rows', len(rows),
          'widths', sorted({len(r) for r in rows}),
          'one trailing newline', b.endswith(b'\n') and not b.endswith(b'\n\n'))
PY
```

```bash
# WHAT: re-derive the interest figure and the rewritten balance in exact
#       fixed point, and re-check that the canonical vector is neutral on the
#       multiply-before-divide ordering.
# WHY : Assumptions: the arithmetic is asserted exactly, with no tolerance, so the
#       expectation is only as good as the claim that 12.5000 divides exactly.
#       Printing the neutrality result beside the figure is what stops a reader
#       citing this scenario as evidence for the ordering rule of section 3.4,
#       which it cannot support -- the discriminating vector recorded there is the
#       one that separates the two orders.
python3 - <<'PY'
from decimal import Decimal, ROUND_HALF_UP

def scale2(value):
    return value.quantize(Decimal('0.01'), rounding=ROUND_HALF_UP)

balance, rate = Decimal('1000.00'), Decimal('15.00')
interest = scale2(balance * rate / 1200)
print('product          ', balance * rate)
print('interest         ', interest, '-> expect 12.50')
print('account 1 balance', scale2(Decimal('194.00') + interest), '-> expect 206.50')
print('ordering neutral ', scale2(balance / 1200 * rate) == interest, '-> expect True')
PY
```

<sub>Apache-2.0. This folder is additive and test-only. The COBOL baseline under `app/**` and the
parity oracle under `tests/**` and `scripts/**` are reference material and are never modified. See
`../../README.md` for the tree-level byte-encoding contract and
`docs/CODE_DOCUMENTATION_STANDARD.md` for the documentation convention this file is written
against.</sub>
