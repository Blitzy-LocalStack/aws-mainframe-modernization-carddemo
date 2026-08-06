# transaction-service test fixtures -- the LEDGER fixed-width record tree

This folder holds the fixed-width record images that the transaction-service
tests load into the `ledger` schema. Ten scenario subfolders sit beside this
file, one per behaviour under test. The purpose of this document is narrow and
specific: it holds the reasoning that is **the same for all ten scenarios**, so
that no scenario has to repeat it and no reader has to reconstruct it from ten
partial copies.

Availability, stated up front and using the `[present]`/`[planned]` convention of
section 9.3, because the rest of this document reasons about a record shape rather
than about a file count and a reader must not have to infer which files exist.
Every count below was measured on this branch rather than recalled, and the
section that can independently re-measure it is named beside it:

- All ten scenario subfolders are **[present]**, and each carries all three
  record files -- `dailytran.txt`, `transact.txt` and `tcatbal.txt` -- for
  **thirty record files** in total. Section 3.1 settles WHICH record types belong
  in this folder, and the set it fixes is now the set every scenario holds.
- Exactly two of those thirty files are **deliberately zero bytes**:
  `empty_input/dailytran.txt` and `zero_balance/tcatbal.txt`. For both, the
  emptiness IS the discriminating property, so a present-but-empty file is the
  authored artifact and not a missing one. The remaining twenty-eight each carry
  exactly one record -- 350 bytes for `dailytran.txt` and `transact.txt`, 50
  bytes for `tcatbal.txt` -- and section 3.3 carries the measured line-ending
  result for all thirty.
- All ten per-scenario `README.md` files are **[present]**, and each carries the
  byte-level attestation section 7 owes. Section 8 is the template they were
  authored from and remains the checklist any eleventh scenario is held to. This
  document is not a substitute for them: it holds only what is uniform across all
  ten.

Refactoring Rationale: this block previously reported a partial tree -- seven
folders, ten record files and no scenario README -- and that report was accurate
when it was written. It is re-derived here because the corpus was completed
afterwards, and a stale availability marker is worse than no marker at all: it
tells a reader that an artifact sitting in front of them does not exist, and it
quietly retires the obligation the marker was carrying. The one consequence that
mattered most is now discharged rather than annotated -- the one-cent
discriminator pair named in section 1.1, `boundary_exact_limit` and
`reject_102_overlimit`, is present in full, so the credit-limit boundary that
separates return code 0 from the soft-warn return code 4 has a fixture on both
sides of it.

> **Purpose and sources of truth.** Two documents govern the bytes in this tree
> and this file is subordinate to both. The **byte-encoding contract** -- record
> widths, one-based field positions, the zoned-decimal sign-overpunch table, the
> line-ending and trailing-newline rules -- lives in
> [`tests/fixtures/README.md`](../../../../../../tests/fixtures/README.md), and
> is cited here **by section number and never restated or contradicted**. The
> **documentation convention** lives in
> [`docs/CODE_DOCUMENTATION_STANDARD.md`](../../../../../../docs/CODE_DOCUMENTATION_STANDARD.md).
> The behavioural specification is the COBOL under `app/**`, which is cited by
> path and line. Everything under `app/**` and `tests/**` is reference-only: it
> is read, never edited.
>
> **Why this document exists (Rule 1, Explainability).** Rule 1 is the single
> user-specified rule for this migration; its full text is available through the
> `review_rules` tool, and the specification anchors it at sections 0.8 / 0.8.1
> with the artifacts it forces into scope listed at section 0.2.1.6. Rule 1 asks
> that a justification sit **adjacent** to the thing it explains, and separately
> forbids **restating what an artifact already shows**. A decision that is
> uniform across every scenario has no single scenario to sit adjacent to, and
> copying it into each sibling README would be exactly the restatement the rule
> forbids. The folder is therefore the only location that satisfies both clauses
> at once, which is what makes this file rule-mandated rather than decorative.
> Rule 1's validation gate is conjunctive -- the documentation and the reasoning
> are both required -- so a folder-wide decision recorded nowhere would fail
> review even though every scenario README were present.

> **Note -- the inventory is measured, not projected.** The set described in
> [section 1](#1-scope-and-inventory) is both the target contract and the current
> state of the tree: **10 scenario subfolders, 30 record files and 10 scenario
> `README.md` files, all present.** Two obligations that used to be conditional on
> that state are therefore discharged rather than outstanding: Decision A's "three
> record files per scenario" is both the shape each scenario is authored to and the
> shape each one holds, and [section 8](#8-authoring-a-scenario-readme) describes
> ten documents that exist rather than ten that do not. The commands in
> [section 10](#10-verification-commands) glob `*/*.txt`, so they cover all 30
> record files, and the byte sweep there covers this document together with all ten
> scenario READMEs.
>
> **Why the annotation is here (Assumption made explicit).** This file also uses
> the **[present]**/**[planned]** convention of master section 9.3 in
> [section 5](#5-what-consumes-these-fixtures), where the artifacts it names are
> still genuinely mixed, and for the reason master section 9.3 states: the marker
> keeps a document from describing a future artifact as if it already existed.
> Applying it to section 5 and not to this file's own inventory would leave the
> convention half-applied, which is why the inventory carries it as well even now
> that every marker in it reads **[present]**.

---

## 1. Scope and inventory

Ten scenarios, three record files each. The set is deliberately small and every
member earns its place by proving one discriminating property. The final column of the table records WHERE
that property lives, because for three scenarios it is deliberately not in the
`dailytran.txt` bytes -- see section 1.3, which exists so that a reader comparing
two identical files does not take one of them for a copy-paste error.

| Scenario | Discriminating property it exists to prove | Expected outcome | Property carried by |
|---|---|---|---|
| `happy_path` | the byte-shape template: one well-formed record that clears both boundary gates | posts, no reject | the record |
| `boundary_exact_limit` | an amount landing exactly on the credit limit | posts | the record |
| `boundary_expiry_equal` | a transaction date exactly equal to the expiration date | posts | the record |
| `zero_balance` | the create arm of the category-balance fork, via a truly empty `tcatbal.txt` | posts, category row created | a sibling file |
| `empty_input` | a present-but-empty primary input, via a truly empty `dailytran.txt` | no records processed, no reject | the file's emptiness |
| `reject_100_card_missing` | the card number resolves to no cross-reference row | reject 100, `INVALID CARD NUMBER FOUND` | the record |
| `reject_101_acct_missing` | the resolved account does not exist | reject 101, `ACCOUNT RECORD NOT FOUND` | a precondition |
| `reject_102_overlimit` | one cent past the same limit | reject 102, `OVERLIMIT TRANSACTION` | the record |
| `reject_103_expired` | one day past the same date | reject 103, `TRANSACTION RECEIVED AFTER ACCT EXPIRATION` | the record |
| `reject_109_rewrite_invalid_key` | a failure raised after validation has already passed | reject 109, `ACCOUNT RECORD NOT FOUND` | a precondition |

### 1.1 The two discriminator pairs

The set is built around two pairs, and the pairing is the point of it. Each pair
must agree **byte for byte except in the single field that moves**, because a
pair that differs in two fields no longer isolates which one caused the outcome.

**Both pairs are present, and both agreements were measured rather than assumed.**
Each pair was compared byte by byte on this branch: the one-cent pair differs at
**exactly one byte, position 143**, and the one-day pair at **exactly one byte,
position 288**. Each measurement is reported below beside the rule the pair
straddles. Assumptions: this property holds when a pair is authored together and
stops holding silently when one member is later edited alone, which is why it is
recorded as a measurement with its positions rather than as an intention -- a
reader can re-run the comparison, and a reader cannot re-run an intention.

- **One cent.** `boundary_exact_limit` and `reject_102_overlimit` differ only in
  the amount at positions 133-143. The comparison in
  [`app/cbl/CBTRN02C.cbl`](../../../../../../app/cbl/CBTRN02C.cbl) line 407 is
  `IF ACCT-CREDIT-LIMIT >= WS-TEMP-BAL`, so landing exactly on the limit takes
  the pass arm and one cent beyond takes the reject arm. Both members are present
  and the agreement holds as stated, measured on this branch: the amount field at
  positions 133-143 reads `0000020650{` in the first and `0000020650A` in the
  second, so the two records differ **from each other at exactly one byte,
  position 143** -- the sign-overpunch low-order digit, where `{` carries a
  positive zero and `A` a positive one under master section 3.4. The pair
  therefore moves the amount by one cent and moves nothing else, which is the
  property that makes the outcome attributable to the limit comparison alone.
- **One day.** `boundary_expiry_equal` and `reject_103_expired` differ only in
  the date carried in the first ten bytes of the originating timestamp at
  positions 279-304. Line 414 of the same program is
  `IF ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS (1:10)`, so an equal date passes
  and one day beyond rejects. Both members of this pair are present and the
  agreement holds as stated, measured on this branch: each differs from
  `happy_path/dailytran.txt` only inside the ten date bytes at 279-288, carrying
  `2024-12-13` and `2024-12-14` against the template's `2022-06-10`, and the two
  differ **from each other at exactly one byte**, position 288.

Both comparisons are inclusive on the pass side. The copybook field name in that
second comparison is the baseline spelling `ACCT-EXPIRAION-DATE` and is quoted
exactly as declared, because the declared name is what the program compiles
against.

### 1.2 Reason 109 is new coverage with nothing to mirror

`reject_109_rewrite_invalid_key` has no counterpart in the reference tree. This
is stated plainly rather than dressed up: `tests/fixtures/posting/` holds
scenarios for 100, 101, 102 and 103 only, and a search for `109` across
`tests/README.md` and `tests/fixtures/README.md` returns nothing -- the reject
table in `tests/README.md` lines 561-566 lists four reasons and stops. The
fixture for this scenario is therefore authored from the record layout rather
than adapted from an existing one.

Reason 109 is raised at
[`app/cbl/CBTRN02C.cbl`](../../../../../../app/cbl/CBTRN02C.cbl) line 556, on
the `INVALID KEY` branch of the account rewrite at line 554. That rewrite lives
in paragraph `2800-UPDATE-ACCOUNT-REC`, whose header is at line 545, and the
paragraph is performed at line 441 from inside `2000-POST-TRANSACTION` -- which
line 211 reaches only when validation has already returned a reason of zero.
That ordering is the whole content of the scenario: 100 through 103 are
validation outcomes, whereas 109 is a write outcome reached after validation
succeeded.

> A collision worth knowing before writing an assertion.
>
> Assumptions: the message text for 109 at line 557 is byte-identical to the
> message text for 101 at line 398 -- both are `ACCOUNT RECORD NOT FOUND`, 24
> characters, with no distinguishing whitespace. A test that asserts only on
> `reason_desc` therefore cannot tell 101 from 109 and will pass against the wrong
> scenario; only `reason_code` separates them. Any assertion covering either
> scenario must include the code.

### 1.3 Three `dailytran.txt` files are byte-identical to `happy_path`, deliberately

This is recorded because it looks like a defect and is not. Measured with `cmp`
against `happy_path/dailytran.txt`, the ten scenarios relate to the template as
follows -- positions are one-based:

| Scenario | Differs from `happy_path` at | Field that moves |
|---|---|---|
| `boundary_exact_limit` | 138-143 | the amount, to `0000020650{` |
| `reject_102_overlimit` | 138-143 | the amount, to `0000020650A` |
| `boundary_expiry_equal` | 282, 284, 285, 288 | the originating date, to `2024-12-13` |
| `reject_103_expired` | 282, 284, 285, 288 | the originating date, to `2024-12-14` |
| `reject_100_card_missing` | 263-278 | the card number, to `9999999999999999` |
| `reject_101_acct_missing` | nowhere | none |
| `reject_109_rewrite_invalid_key` | nowhere | none |
| `zero_balance` | nowhere | none |
| `empty_input` | the file is empty | none |

Assumptions: the three scenarios that differ nowhere are discriminated by
something other than a byte of the input record -- for two of them a precondition
the TEST establishes over data this folder deliberately does not own, and for the
third a sibling file that this folder does ship.

- `reject_100_card_missing` is the one "not found" scenario that DOES move a
  byte: it carries `9999999999999999` in the card field, fifteen of the sixteen
  bytes changing from the template -- the sixteenth is position 266, which
  already held a `9`. The value is unresolvable by construction rather than by
  omission, and that is a measurement: `app/data/ASCII/cardxref.txt` holds fifty
  rows and none begins with it. The reference tree reaches the same reason from
  the other direction -- its `reject_100_card_missing/dailytran.txt` is
  byte-identical to that tree's `happy_path/dailytran.txt` and its own
  `cardxref.txt` supplies a single row for a different card, so the record's card
  resolves to nothing. That construction is unavailable here because Decision A
  keeps the cross-reference out of this folder: with no `cardxref.txt` of its own,
  the scenario has to express the absence inside the one file it does ship.
- `reject_101_acct_missing` is the complement, and it moves no byte at all. Reason
  101 requires the cross-reference read to have **succeeded** and the account it
  names to be absent, so the card here must be a resolvable one -- and the
  template's `4859452612877065` is precisely that, the card the seed
  cross-reference maps to account `00000000007` at row 21. The absent-account half
  is a state the consuming test establishes, per Decision A. This is also how the
  reference tree builds the same scenario: its `reject_101_acct_missing` carries
  the happy card and separates itself by shipping an `acctdata.txt` that holds a
  different account. Moving the card here would be actively wrong rather than
  merely unnecessary -- see the note below.
- `reject_109_rewrite_invalid_key` reaches its reason on the account rewrite,
  after validation has already returned zero, so by construction nothing in the
  input record can express it; section 1.2 sets out the ordering.
- `zero_balance` is discriminated by its empty `tcatbal.txt` sibling, not by the
  input record.

> **A card substitution in `reject_101_acct_missing` is specifically wrong, and
> the reason is worth recording where the next author will meet it.** The card
> `0927987108636232` reads like the natural choice for this scenario, because it
> is a published seed value that resolves -- row 4 of
> [`app/data/ASCII/cardxref.txt`](../../../../../../app/data/ASCII/cardxref.txt)
> maps it to customer `000000020` and account `00000000020`, measured -- and
> because it carries the leading zero that item 1 of the table in section 4.2
> forbids normalising. It was carried here and has been withdrawn. Assumptions: it
> resolves only if the consuming test seeds that particular cross-reference row.
> The reference corpus's resolvable-card scenarios ship a single row mapping
> `4859452612877065`, and `0927987108636232` appears there only as the decoy row
> of `reject_100_card_missing`, never as a daily record's card. Under a
> cross-reference tier of that minimal shape the card resolves to nothing, and
> because [`app/cbl/CBTRN02C.cbl`](../../../../../../app/cbl/CBTRN02C.cbl) checks
> reason 100 at lines 385-387 **before** reason 101 at lines 397-399, the record
> would express reason 100 -- so the scenario would be named for one reason and
> encode its predecessor. Byte identity with `happy_path` removes the dependency
> entirely: the card is the one every resolvable-card scenario maps, leaving the
> account-tier omission as the single condition the test has to establish.
> Trade-offs: byte identity costs this scenario any visible marker of its own, so
> its identity rests on its folder name and on the account state a test supplies,
> and it costs the folder the leading-zero round-trip that the withdrawn card
> happened to carry. Both are accepted. The first is preferable to a fixture whose
> bytes contradict its name, and the second is answered by the closing paragraph
> of section 4.2: a leading-zero round-trip deserves a fixture authored and named
> for that purpose, not one smuggled into a scenario whose discriminator is
> something else.

Trade-offs: the alternative was to give each of those three a distinct,
invented input record so that every fixture differed from every other. It is
rejected because an invented card number or amount would assert a discriminator
the program does not read for that reason code, and a reader would then be unable
to tell which field the scenario actually turns on. A byte-identical file plus a
stated precondition is checkable; a plausible-looking difference that nothing
consumes is not.

---

## 2. Record layouts, by reference

| File | Record | Copybook | Bytes per record |
|---|---|---|---|
| `dailytran.txt` | `DALYTRAN-RECORD` | [`app/cpy/CVTRA06Y.cpy`](../../../../../../app/cpy/CVTRA06Y.cpy) lines 4-18 | 350 |
| `transact.txt` | `TRAN-RECORD` | [`app/cpy/CVTRA05Y.cpy`](../../../../../../app/cpy/CVTRA05Y.cpy) lines 4-18 | 350 |
| `tcatbal.txt` | `TRAN-CAT-BAL-RECORD` | [`app/cpy/CVTRA01Y.cpy`](../../../../../../app/cpy/CVTRA01Y.cpy) lines 4-10 | 50 |

The authoritative position tables are master sections 5.1, 5.5 and 5.6 and are
not reproduced here.

**The two 350-byte layouts are field-for-field identical in picture and in
order, differing only by field-name prefix.** `DALYTRAN-ID` through
`DALYTRAN-PROC-TS` and the closing `FILLER X(20)` occupy lines 5-18 of
`CVTRA06Y.cpy`; `TRAN-ID` through `TRAN-PROC-TS` and the same closing `FILLER`
occupy lines 5-18 of `CVTRA05Y.cpy`, with matching pictures on every
corresponding line. Master section 5.5 states the same identity. Never author a
different layout for the daily feed on the assumption that a pre-posting record
must differ from a posted one -- it does not.

### 2.1 The three positions that matter, with two independent corroborations

Positions are one-based and inclusive.

| Field | Positions | Picture |
|---|---|---|
| amount | 133-143 | `S9(09)V99`, 11 bytes |
| card number | 263-278 | `X(16)` |
| processing timestamp | 305-330 | `X(26)` |

A reader should not have to trust this document for a byte position, so two
sources outside the copybook agree with it:

1. [`app/jcl/TRANREPT.jcl`](../../../../../../app/jcl/TRANREPT.jcl) lines 41-42
   declare `TRAN-CARD-NUM,263,16,ZD` and `TRAN-PROC-DT,305,10,CH` in one-based
   positions, matching the card number exactly and placing the report's date
   window on the first ten bytes of the processing timestamp.
2. [`app/jcl/TRANIDX.jcl`](../../../../../../app/jcl/TRANIDX.jcl) line 27
   declares `KEYS(26 304)` -- a length of 26 at a zero-based displacement of
   304, which is one-based position 305 -- with `NONUNIQUEKEY` at line 28,
   `UPGRADE` at line 29 and `RECORDSIZE(350,350)` at line 30. The two numbers
   inside `KEYS` are **space-separated in the source and are quoted that way on
   purpose**; rewriting them as a comma-separated pair would misquote the file.

The target counterpart of that alternate index is `idx_transactions_proc_ts` in
[`V1__ledger.sql`](../../../main/resources/db/migration/V1__ledger.sql) line
291, and it is non-unique for the same reason `NONUNIQUEKEY` is declared: many
transactions share a processing timestamp. That migration's own note at line 283
records that a plain `CREATE INDEX` is already non-unique in PostgreSQL, so no
modifier is needed to express it. The companion index on the card number is at
line 269.

### 2.2 The 26-character timestamp

Both timestamp fields are `X(26)`, and the exact interior shape comes from
[`app/cpy/CSDAT01Y.cpy`](../../../../../../app/cpy/CSDAT01Y.cpy): the
`WS-TIMESTAMP` group is declared at line 42 and its thirteen members span lines
43-55, summing to exactly 26 bytes. Two of those members fix the punctuation --
the separator at line 48 carries a **space** and the one at line 54 carries a
**dot** -- so the form is `YYYY-MM-DD HH:MM:SS.mmmmmm`, with a space at
character 11 and a dot at character 20.

[`app/cbl/COBIL00C.cbl`](../../../../../../app/cbl/COBIL00C.cbl) corroborates
this independently at lines 264-266: it fills characters 1-10 with the date and
characters 12-19 with the time, stepping over character 11 entirely, and then
forces the microsecond group to zeros -- leaving characters 11 and 20 to the two
`FILLER` values above. The seed agrees as well; the originating timestamp of the
second `dailytran` record reads `2022-06-10 19:27:53.000000`, which is 26
characters with a space at 11 and a dot at 20.

### 2.3 The reject contract, and why it adds no fourth file

The reject stream is the widest record in the posting flow, and its shape is
declared in
[`app/cbl/CBTRN02C.cbl`](../../../../../../app/cbl/CBTRN02C.cbl) lines 176-178:
a 350-byte data area plus an 80-byte trailer, totalling **430** bytes. Lines
181-182 decompose that trailer into a four-digit reason and a 76-byte
description.
[`app/jcl/POSTTRAN.jcl`](../../../../../../app/jcl/POSTTRAN.jcl) line 36
confirms the total independently with `LRECL=430`.

The target keeps that shape as three columns and leaves the data area
undecomposed: `raw_record CHAR(350)`, `reason_code SMALLINT` and
`reason_desc VARCHAR(76)`, at
[`V1__ledger.sql`](../../../main/resources/db/migration/V1__ledger.sql) lines
471, 485 and 497. A reject is an **output** of posting rather than an input to
it, so no scenario in this folder ships a reject fixture; each reject scenario
states its expected code and message in prose instead, and keeps the same three
input files as every other scenario.

---

## 3. The three folder-level decisions

Each decision below has an obvious competing alternative that a reader would
reasonably expect to have been taken. Each therefore names that alternative and
what concretely goes wrong under it, tagged with the category names from Rule 1
in the one written form the documentation standard permits.

### 3.1 Decision A -- three record layouts per scenario, not five

Every scenario here ships `dailytran.txt`, `transact.txt` and `tcatbal.txt` --
the set the availability block at the head of this file measures, and the set all
ten scenarios hold. The reference posting scenarios ship a different set: they add the account master and
the card cross-reference, because `CBTRN02C` opens those two files during
validation. **This folder ships neither of them**, and that is a choice rather
than an omission.

Assumptions: "three, not five" is a statement about the file set a scenario may
contain, and it remains that statement now that every folder holds all three. What
this decision fixes is the CEILING and the composition: a scenario in this folder
never acquires a fourth or fifth record file, whatever else it gains. The
distinction matters because the two failures are not symmetrical -- a folder short
of `tcatbal.txt` is incomplete and can be completed, whereas a folder holding
`acctdata.txt` would have taken another bounded context's records, which no later
addition repairs. Only the first of those two was ever true here, and it no longer
is.

Alternatives Considered: mirroring the reference tree's five-file shape was
evaluated and rejected on ownership grounds. `CBTRN02C` is batch-service's
program; transaction-service migrates the four online programs `COTRN00C`,
`COTRN01C`, `COTRN02C` and `COBIL00C`, and owns exactly one schema. The four
tables in it are `ledger.transactions`, `ledger.daily_transactions`,
`ledger.transaction_rejects` and `ledger.transaction_category_balances`, created
at [`V1__ledger.sql`](../../../main/resources/db/migration/V1__ledger.sql) lines
116, 319, 445 and 548. The account, customer and cross-reference records belong
to the `account` schema and the card record to the `card` schema, both owned by
other modules. Placing an account or cross-reference image in this module's test
resources would put another bounded context's records inside this one's test
data, which is the precise ownership split that bounded contexts exist to
prevent, and it would leave two modules free to disagree about the same bytes.

Assumptions: the scenario names borrowed from the reference tree are a **naming
convention only**, not a claim that this module re-runs `CBTRN02C` validation.
What the reject scenarios exercise here is the one reject contract this module
does own -- the three columns of `ledger.transaction_rejects` described in
section 2.3. A scenario therefore documents its expected reason code and its
verbatim message, and gains no extra file for doing so.

### 3.2 Decision B -- `dailytran.txt` and `transact.txt` are two files, not one

Section 2 establishes that the two 350-byte layouts are identical in picture and
order. A single shared record file would therefore look entirely sufficient, and
would save 350 bytes per scenario. It is still two files.

Assumptions: the two target columns carry **different nullability**, and the
asymmetry is load-bearing rather than incidental.
[`V1__ledger.sql`](../../../main/resources/db/migration/V1__ledger.sql) declares
`ledger.transactions.proc_ts TIMESTAMP(6) NOT NULL` at line 245 and
`ledger.daily_transactions.proc_ts TIMESTAMP(6)` -- nullable -- at line 397, and
records its own reasoning for the pair at line 379. The asymmetry follows the
data: the pre-posting feed genuinely arrives with that field blank, which is
measurable in the seed and is item 2 of the table in section 4.2. Two files are
what let one test assert that the nullable side accepts a blank and another
assert that the not-null side rejects one; from a single file neither assertion
can be written without the other scenario contradicting it.

Trade-offs: roughly 330 of 350 bytes are duplicated between the two files in
every scenario, and an author who edits one must consider the other. That
duplication is accepted because each file then exercises exactly one column
constraint. Collapsing them would make the nullability assertion inexpressible
at any price, whereas the duplication costs only diligence.

### 3.3 Decision C -- every file here is LF-only, including `tcatbal.txt`

Every record file in every scenario here uses LF line endings and ends with a
single trailing newline. That is measured, not asserted: across all **30** record
files the carriage-return count is **zero**, and each of the 28 non-empty files
ends with exactly one line feed -- the two zero-byte files named in the
availability block carry no bytes at all to terminate. Master sections 3.2 and 3.3
are the authority for both rules and state why; this section records only the
folder-level consequence.

Assumptions: the loader treats each physical line as exactly one fixed-length
record, so a stray carriage return is absorbed into the trailing field or the
`FILLER` and pushes the record one byte past its declared length. The concrete
measurement is available in the seed itself: `app/data/ASCII/tcatbal.txt` totals
2599 bytes for 50 records of 50 logical bytes, carrying 49 carriage returns, so
each CRLF-terminated line **measures 51 bytes on disk for a 50-byte record**.
That extra byte is what a fixed-width reader would fold into the 22-byte
`FILLER` at positions 29-50.

Refactoring Rationale: `tcatbal` is one of the three seeds master section 3.2
names as shipping CRLF, so carrying the seed's line endings across verbatim is
the thing that would be wrong here rather than the thing that would be faithful.
The reference tree already resolves it the same way, and this was measured rather
than assumed: across all 36 record files under `tests/fixtures/posting/**` --
`tcatbal.txt` included -- the carriage-return count is zero. This folder follows
that established resolution. Should a future scenario ever need to preserve CRLF
deliberately, master section 3.2 requires that choice to be documented
explicitly in that scenario's own README, and this index says so here so that the
requirement is discoverable from the folder rather than only from the master.

---

## 4. Byte conventions, by reference

### 4.1 Where each rule actually lives

Cited by section number, deliberately not reproduced. Restating an encoding rule
here would create a second copy free to drift from the first, and a reader who
found the two disagreeing would have no way to tell which one the loader
implements.

| Master section | What it governs |
|---|---|
| 3.1 | fixed width and positional fields, with no delimiters |
| 3.2 | line endings, and the three seeds that ship CRLF |
| 3.3 | exactly one trailing newline, so a line count equals the record count |
| 3.4 | the zoned-decimal sign-overpunch table and its worked vectors |
| 3.5 | the implied decimal: `V` occupies no byte and no literal `.` is stored |
| 5.1, 5.5 | the two 350-byte layouts and their position tables |
| 5.6 | the 50-byte layout, and the seed's `FILLER` convention |
| 6.3 | the determinism asymmetry between the two timestamps |
| 9.1 | the mandated per-scenario README and its four required items |
| 9.3 | the `[present]` and `[planned]` availability convention |
| 10.1-10.3 | the synthetic-provenance attestation |

**The overpunch table is not reproduced anywhere in this folder.** Master section
3.4 is its single home. Two worked vectors appear in the table below only because
the sentences that need them would otherwise be unreadable, and both are quoted
from that section's own examples.

Two conventions differ from the reference tree by design, and are recorded so
neither reads as an oversight:

- **No domain level.** The reference layout is
  `tests/fixtures/<domain>/<scenario>/`; this one is `fixtures/<scenario>/` with
  nothing in between. A single service module has exactly one domain, so the
  extra level would carry no information and would only lengthen every path. The
  peer tree at `services/reference-service/src/test/resources/fixtures/` uses the
  same flat shape.
- **No expected-output mirror.** Master section 2.1 binds each reference fixture
  directory to a matching directory under `tests/golden/`. That rule is not
  imported here and this folder has no such counterpart: the four programs this
  module migrates are all online programs, which `tests/README.md` documents as
  not runnable end to end without a CICS runtime, so the reference tree's
  expected-output comparisons cover batch flows and not these. Assertions here
  live in test code, not in a parallel tree of expected files.

### 4.2 Seed facts that must survive into a fixture

Every fact below was measured on the branch, not recalled. Each one looks like
something a careful author would tidy, and each one is load-bearing: tidying it
destroys the property the fixture exists to prove. The permitted framing applies
throughout -- the baseline is what it is, the target implements a defined
behaviour, and any divergence is documented; nothing in `app/**` is edited.

| Fact as measured | Where to verify it | What breaks if it is normalised |
|---|---|---|
| A **synthetic CardDemo demonstration card number copied from the published seed, not a credential and not a real person's account number**, carrying a leading zero: `0927987108636232` | `app/data/ASCII/dailytran.txt` record 2, positions 263-278 | it is the evidence that the card number must round-trip as a digits-only string in a `CHAR(16)` column. A numeric column or a numeric Java type silently drops the leading digit and the value compares unequal to itself after one round trip |
| The processing timestamp arrives as **26 spaces** | same record, positions 305-330 | it is the seed evidence for the nullability split in Decision B: `daily_transactions.proc_ts` nullable against `transactions.proc_ts NOT NULL`. Filling it in erases the only input that exercises the nullable side |
| A signed amount written `0000009190}` | same record, positions 133-143 | the trailing `}` is a negative low-order digit under master section 3.4, so the value is negative. Money in these masters is zoned decimal, not packed. Rewriting the sign as a leading `-` lengthens the field past 11 bytes and inverts nothing about the sign the reader expects |
| A `{` used as a **positive-zero** low-order digit: `0000000000{` | `app/data/ASCII/tcatbal.txt` record 1, positions 18-28 | `{` and `0` are different bytes, which a byte dump of that field shows directly. Replacing `{` with `0` discards the sign entirely, and the field stops being a signed value |
| The 50-byte record's `FILLER` is **22 ASCII zeros, not spaces** | same record, positions 29-50 | master section 5.6 states the convention. Blanking those bytes changes 22 of 50 bytes -- nearly half the record -- and any byte-level comparison against a seed-shaped record fails for a reason unrelated to the behaviour under test |
| The seed file is `dailytran.txt` while the dataset it loads is named `DALYTRAN` | `app/data/ASCII/dailytran.txt` against [`app/jcl/POSTTRAN.jcl`](../../../../../../app/jcl/POSTTRAN.jcl) line 31 | the two spellings genuinely differ in the baseline. **Normalise neither.** Fixture file names follow the seed file name, so the name in this folder is `dailytran.txt` |
| There is **no `transact.txt` in `app/data/ASCII/`** -- that directory holds exactly nine files | `app/data/ASCII/` | the posted-transaction master is not seeded in ASCII form, so every `transact.txt` in this folder is authored from the `CVTRA05Y` layout rather than copied from a seed row. Each scenario README must therefore attest those bytes as synthetic in its own words, per section 7 |

Assumptions: the first row states a property of the **seed** and an obligation on
the **column**, and it is not a claim that a fixture in this folder currently
carries that card. Measured on this branch, no `dailytran.txt` here carries it:
nine of the ten carry `4859452612877065`, which is also a published seed value and
has no leading zero, and the tenth -- `reject_100_card_missing` -- carries the
deliberately unresolvable `9999999999999999`. Section 1.3 records why the one
scenario that formerly carried the leading-zero card must not. The obligation the row establishes is therefore
outstanding on the schema side, where `V1__ledger.sql` declares the card column
`CHAR(16)`, and a fixture whose purpose is to prove the leading zero survives a
round trip has to be authored with that card **and named for that purpose**,
rather than smuggled into a scenario whose discriminator is something else
entirely. Trade-offs: keeping the row here rather than deleting it costs this
paragraph, and buys a reader the seed evidence for a rule the folder does not yet
exercise; deleting it would leave the `CHAR(16)` choice looking arbitrary.

---

## 5. What consumes these fixtures

This section uses the `[present]` and `[planned]` convention of master section
9.3. Its purpose is to keep a document from describing a future artifact as
though it already exists, which is the failure this annotation prevents.

- `services/transaction-service/src/test/java/**` -- **[present]**, but **no
  consumer of these fixtures is present yet**. Measured on this branch the tree
  holds four test classes --
  [`TransactionApiContractTest`](../../java/com/carddemo/transaction/dto/TransactionApiContractTest.java),
  [`TransactionAddRequestTest`](../../java/com/carddemo/transaction/dto/TransactionAddRequestTest.java),
  [`FixedWidthMappingTest`](../../java/com/carddemo/transaction/domain/FixedWidthMappingTest.java)
  and
  [`TransactionRepositoryIT`](../../java/com/carddemo/transaction/repository/TransactionRepositoryIT.java)
  -- beside five documentation-only `package-info.java` declarations, and **not one
  of them reads a byte from this folder**. The contract test binds the published
  OpenAPI document to the request and response records; the mapping test asserts
  the fixed-width offsets against the entity; and the repository integration test
  builds every row it needs in code, through `save(...)` calls on literal values,
  rather than loading a record image. So every record file here is still loaded by
  nothing.

  Refactoring Rationale: this bullet has been re-derived twice, and the second
  re-derivation is the reason it is now phrased around the consumer rather than
  around the class count. It first read `[planned]` with no test class at all; it
  then named the single contract test; the tree now holds four classes including
  the `*RepositoryIT` this section used to describe as the thing that would read
  these bytes once authored. The fact a fixture author actually needs -- whether
  anything reads them -- survived all three states unchanged, so it is stated
  first and the inventory is stated as a measurement behind it. Adding another test
  class no longer falsifies the sentence; making one of them load a record file is
  the single change that must update it.
- [`../application-test.yml`](../application-test.yml) -- **[present]** sibling.
  Three of the things it supplies are what make an assertion on these bytes
  reproducible at all: it pins schema resolution to `ledger` for both the
  connection and the migration tool (its `search_path` setting at line 285 and
  its schema settings at lines 340 and 356), it points the migration tool at this
  module's own single migration (line 327, with the reasoning at line 318), and it
  pins the clock to one instant (line 180). Without that last one, no assertion on
  a 26-character timestamp can be reproducible, because the value would change
  between runs.
- [`../../../main/resources/db/migration/V1__ledger.sql`](../../../main/resources/db/migration/V1__ledger.sql)
  -- **[present]**. It creates the four `ledger` tables these fixtures load into,
  along with `idx_transactions_card_num` and the non-unique
  `idx_transactions_proc_ts`.
- [`tests/fixtures/README.md`](../../../../../../tests/fixtures/README.md) --
  **[present]**, the authoritative byte contract of section 4.1.
- Integration testing against a real PostgreSQL instance through Testcontainers
  -- **[present]**: `TransactionRepositoryIT` runs against `postgres:17-alpine`
  and applies this module's own migration. A **fixture-loading** integration test
  is the part that remains **[planned]**. Assumptions: the non-unique index on the
  processing timestamp and the key-ordered read paths these records feed are
  properties of the real engine; an in-memory substitute cannot exercise either,
  so a test that passed against one would prove nothing about the behaviour being
  claimed. Where these records feed a list or browse path, that path pages by
  **key** and not by ordinal position: a page envelope carries `items`,
  `firstKey`, `lastKey` and `hasNext`, with `hasNext` discovered by reading one
  row beyond the page size. Fixture record ordering therefore matters, and a
  scenario that needs a particular page boundary must place its records in key
  order.

---

## 6. Determinism

Master section 6.3 is the authority and distinguishes the two timestamps
precisely; it is not restated here. One consequence governs authoring in this
folder and is worth stating in the folder that the authors work in:

- The **originating timestamp at positions 279-304 is load-bearing and must be a
  literal**. Its first ten bytes are the date the expiration comparison consumes
  at [`app/cbl/CBTRN02C.cbl`](../../../../../../app/cbl/CBTRN02C.cbl) line 414,
  which is exactly what the `boundary_expiry_equal` and `reject_103_expired` pair
  moves by one day. It is not a runtime value and must never be blanked.
- The **processing timestamp at positions 305-330 is the only runtime-varying
  range**, and in every `dailytran.txt` here it **must be blank** -- 26 spaces, as
  the seed carries it. In every `transact.txt` it must instead be **filled**, with
  the fixed literal `2022-07-18 00:00:00.000000`. That is not an exception to
  master section 6.3 so much as the other side of it: the master's blank-bytes
  clause governs an input to a compiled program, where the processing timestamp is
  the field the program **writes**, which is precisely why no `transact.txt`
  exists in the oracle fixture tree at all. These `transact.txt` files are a
  different class of artifact -- an already-posted row loaded into PostgreSQL
  ahead of a test rather than a feed presented to `CBTRN02C` -- and
  `transactions.proc_ts` is `NOT NULL` where `daily_transactions.proc_ts` is not,
  which is Decision B in section 3.2. The determinism the master is protecting is
  honoured by the value being a fixed literal and never a clock reading; each
  scenario README records the provenance of that literal at its own point of use.
- Any date a program **consumes** is injected as a parameter rather than read
  from the wall clock, so that a rerun produces identical output. The pinned clock
  named in section 5 is the target-side counterpart of that discipline.

---

## 7. Provenance and data governance

Every non-empty record file in this folder carries a primary account number, so
the attestation mandated by master sections 10.1 through 10.3 applies to **every
scenario** without exception. Those sections carry the attestation
itself and the reasoning for deriving from the published seeds rather than
minting fresh values; neither is restated here.

> **Satisfied, and measured rather than assumed.** All ten scenario `README.md`
> files exist -- see the availability block at the head of this file -- and each
> carries a provenance attestation section covering the three required items, so
> the obligation stated here is **met for every scenario**. The instance that was
> sharpest while the gap was open is now the clearest evidence that it is closed:
> item 7 of the table in section 4.2 establishes that no `transact.txt` is seeded
> in `app/data/ASCII/`, so `empty_input/transact.txt` is authored from the
> `CVTRA05Y` layout, and
> [`empty_input/README.md`](empty_input/README.md) section 4.2 accounts for those
> bytes explicitly -- naming the daily record they derive from, the field it
> reshaped, and the layout measurement that makes the derivation sound.
> Assumptions: this paragraph is kept, rather than deleted once the gap closed,
> because the gap can reopen. An eleventh scenario arrives with record bytes before
> it arrives with a README, and a mandate written in the present tense reads as
> satisfied at exactly that moment. Stating the state as measured leaves the next
> author a claim to falsify instead of a sentence to trust.

What this index adds is the folder-level obligation. Each scenario README must
record, briefly and in its own words, the three things master section 10.3
requires:

1. that the account-number and identity bytes are synthetic and seed-derived,
   citing the seed file and, where it helps, the seed row key;
2. that they represent no real person and no real account;
3. which business-rule fields were reshaped away from the seed value, so that the
   provenance of the **changed** bytes is explicit too.

The third item carries most of the weight in this folder, because the two
discriminator pairs in section 1.1 exist precisely by reshaping one business-rule
field, and because `transact.txt` has no seed row at all -- item 7 of the table in
section 4.2 -- so its bytes are authored from the layout and must be attested as
such.

---

## 8. Authoring a scenario README

One place to look, so that ten scenario documents come out consistent -- and they
do: all ten exist, and each was authored against the requirements below. This
section is therefore both the record of what they were held to and the checklist
any eleventh scenario is held to; section 7 records that the governance obligation
it carries is met for all ten. Assumptions: an author arriving to add a scenario
reads this list, and an author arriving to review one reads it as the acceptance
criteria for the ten already here. Keeping one list for both uses is what stops
the two from drifting into two lists that disagree.

**The four required items** are master section 9.1's, and every scenario README
must carry all four: the scenario intent; the exact business rule it exercises,
named with its program and paragraph or its reason code; the expected outcome; and
the fixture bytes and governance, meaning the per-file record width, record count
and line ending plus the attestation of section 7.

**Tag every rationale with one of exactly four labels**, written in the one form
the documentation standard permits at its lines 209-226 -- plural,
unparenthesised, colon retained, and with no emphasis markup, a constraint that
standard states applies in Markdown as well as in code:

```text
Alternatives Considered:
Refactoring Rationale:
Assumptions:
Trade-offs:
```

Assumptions: these labels are read by a literal search before they are read by a
person, so a second accepted spelling of the same category makes an audit
silently partial -- the rationale reads as documented to a human and as absent to
the search. That is the reasoning the documentation standard gives at its lines
228-234, and it is why the singular forms are not treated as abbreviations.

**Write in 7-bit ASCII.** Use `--` for a dash, `->` for an arrow, `>=` for an
inequality, `[ok]` for a check mark, and the word `section` rather than a section
symbol. Assumptions: a codepoint that renders like an ASCII character but is not
one silently defeats a literal search for a hyphenated COBOL field name such as
`ACCT-EXPIRAION-DATE` or `TRAN-PROC-TS`, which is the main way anyone navigates
between these fixtures and the copybooks. This is not a hypothetical concern in
this repository: `tests/README.md` renders 106 of its hyphens as the
non-breaking-hyphen codepoint rather than the ASCII hyphen-minus, so a search for
`cross-reference` or `Over-limit` in that file finds nothing. Verify with the byte
sweep in section 10 rather than by eye, because the two forms are visually
identical.

**Annotate availability** with `[present]` or `[planned]` per master section 9.3
whenever referring to an artifact this module has not authored yet, and write the
path of a not-yet-existing file as a plain code span rather than a link, so that
no link in this tree resolves to nothing.

**Three habits in the reference trees are not the standard here.** They are
recorded because they are visible in the documents a scenario author will read
for shape, and copying them would introduce a defect:

- **Singular category labels.** `tests/fixtures/posting/happy_path/README.md`
  writes them singular at its lines 64, 133 and 147. The plural form is the one
  the rule and the documentation standard define, and no form may be mixed inside
  one document.
- **A fabricated identifier tag.** `tests/fixtures/posting/reject_102_overlimit/README.md`
  line 110 carries a bracketed tag in a heading that corresponds to no
  requirement register in this project. Invent no such tag.
- **A mis-citation of the Explainability anchor.** Both the master at its line 711
  and the scenario exemplar at its line 9 cite specification section 0.10.1 for
  Rule 1. That section is the attachments section and records that none were
  provided. The correct anchors are sections 0.8 and 0.8.1 for the rule itself and
  0.2.1.6 for the files it forces into scope; citing the rule by name and pointing
  at `review_rules` is equally acceptable.

And one habit that is the standard: **verify a line number by reading the file
before writing it down**. An invented citation is worse than no citation, because
it costs the next reader the time to discover it is wrong and, being unverifiable,
is itself the vague rationale the rule forbids.

---

## 9. What the mechanical gates do and do not cover here

This is stated plainly, because overclaiming it would itself breach the rule this
document exists to satisfy.

[`config/checkstyle/checkstyle.xml`](../../../../../../config/checkstyle/checkstyle.xml)
configures its checker with `fileExtensions` set to `java`, at line 185.
**Every `.txt` and `.md` file in this folder is therefore outside the audit set
and is mechanically unchecked** -- including this document.

[`config/checkstyle/suppressions.xml`](../../../../../../config/checkstyle/suppressions.xml)
does carry an entry matching this path, at line 250. That entry is **defensive
rather than load-bearing**, and it says so itself: its own justification at lines
227-234 records that the extension filter already places these files outside the
audit set, that the entry covers only the narrow case of a `.java` file
co-located with this directory, and that it must not be described as though it
were load-bearing. Lines 235-248 add the boundary that matters most about it --
it must never be widened to cover `src/test/java/`, where the transcribed
business rules are actually asserted.

The consequence is worth naming rather than leaving implied. A suppression
**exempts** a path; it authors no content and licenses no omission. Rule 1
compliance in this folder rests on authoring discipline and human review, which
is exactly where the rule's own validation gate places it. The Maven and JUnit
gates that do apply to this module are pass-or-fail: there is no graded tolerance
here and none is to be introduced, and the reference suite's condition-code
rubric belongs to that suite and its own workflow, not to this tree.

---

## 10. Verification commands

Run from the repository root. Each block states what it does and why it is done
that way, per the house convention for fenced command blocks.

```bash
# WHAT: Report the character length of every record in this folder, then the line
#       count of each file.
# WHY : Assumptions: the loader reads one physical line as one fixed-length
#       record, so a width other than 350 for dailytran/transact or 50 for
#       tcatbal is a corrupt record rather than a formatting preference. A width
#       of 51 on a tcatbal line is the specific signature of a stray carriage
#       return, which is the failure Decision C exists to prevent. A line count
#       that differs from the record count means the trailing-newline rule of
#       master section 3.3 was broken.
for f in services/transaction-service/src/test/resources/fixtures/*/*.txt; do
  awk -v F="$f" '{printf "%s rec %d: len=%d\n", F, NR, length($0)}' "$f"
done
wc -l services/transaction-service/src/test/resources/fixtures/*/*.txt
```

```bash
# WHAT: Count carriage returns across every record file in this folder.
# WHY : Assumptions: Decision C makes LF the only line ending here, so the only
#       acceptable total is zero. This is the same measurement that established
#       the precedent -- the equivalent count across the 36 files under
#       tests/fixtures/posting/ is zero -- so a non-zero result here is a
#       deviation from the reference tree and not merely from a local preference.
for f in services/transaction-service/src/test/resources/fixtures/*/*.txt; do
  printf 'CR=%s %s\n' "$(tr -cd '\r' < "$f" | wc -c)" "$f"
done
```

```bash
# WHAT: Print the four seed values that section 4.2 forbids normalising: the
#       leading-zero card number, the blank processing timestamp, the negative
#       amount, and the positive-zero balance with its 22-byte FILLER.
# WHY : Assumptions: these are byte-level facts, and three of the four are
#       invisible in a terminal -- 26 spaces, a sign carried in a letter-shaped
#       byte, and a run of ASCII zeros all look like nothing or like something
#       else. Piping the two ambiguous ranges through a byte dump is what makes
#       them checkable, and is why this block does not simply cut and print.
sed -n '2p' app/data/ASCII/dailytran.txt | cut -c263-278
sed -n '2p' app/data/ASCII/dailytran.txt | cut -c305-330 | od -c | head -3
sed -n '2p' app/data/ASCII/dailytran.txt | cut -c133-143
sed -n '1p' app/data/ASCII/tcatbal.txt | tr -d '\r' | cut -c18-28 | od -c | head -2
sed -n '1p' app/data/ASCII/tcatbal.txt | tr -d '\r' | cut -c29-50 | od -c | head -2
```

```bash
# WHAT: Show that a 50-byte tcatbal record occupies 51 bytes on disk in the seed.
# WHY : Assumptions: this is the concrete measurement behind Decision C. The
#       three numbers reconcile as 50 records x 50 logical bytes, plus 49
#       carriage returns, plus 50 line feeds, which is the reported total; the
#       final line ends without a carriage return. Reporting the total alone
#       would not show where the extra bytes come from.
printf 'bytes=%s lines=%s CR=%s\n' \
  "$(stat -c%s app/data/ASCII/tcatbal.txt)" \
  "$(wc -l < app/data/ASCII/tcatbal.txt)" \
  "$(tr -cd '\r' < app/data/ASCII/tcatbal.txt | wc -c)"
```

```bash
# WHAT: Assert that this document and every scenario README contain no byte
#       outside 7-bit ASCII.
# WHY : Assumptions: the visual check is unreliable by construction, because the
#       codepoints that matter are the ones that look like ASCII. The second
#       command reports the offending line and character rather than only a
#       match, so a failure is actionable instead of merely known.
grep -rnP '[^\x00-\x7F]' services/transaction-service/src/test/resources/fixtures/
python3 - <<'PY'
import pathlib
root = pathlib.Path('services/transaction-service/src/test/resources/fixtures')
bad = [(str(p), i, ch)
       for p in sorted(root.rglob('*.md'))
       for i, line in enumerate(p.read_text(encoding='utf-8').splitlines(), 1)
       for ch in line if ord(ch) > 127]
print('NON-ASCII:', len(bad))
print(bad[:20])
PY
```

```bash
# WHAT: Build the module through the phase the documentation gate is bound to,
#       then confirm the reference trees are untouched.
# WHY : Assumptions: this folder contributes no Java, so the correct outcome is
#       that it adds no violation and does not perturb the build -- section 9
#       explains why the gate cannot see these files at all. The second command
#       is the standing check that app/** and tests/** remain reference-only; any
#       path from either tree in its output is a defect regardless of intent.
mvn -f services/transaction-service/pom.xml validate
git status --porcelain
```
