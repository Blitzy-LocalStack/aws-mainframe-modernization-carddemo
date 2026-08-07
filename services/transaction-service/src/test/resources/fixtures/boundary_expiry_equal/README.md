# boundary_expiry_equal -- a transaction dated exactly on the account expiration date

## Purpose of this document

This document is the Explainability carrier for the four files in this directory.
Three of them -- `dailytran.txt`, `transact.txt` and `tcatbal.txt` -- are
fixed-width positional records in which every byte position is a field. They have
no comment construct of any kind, so the whole documentation obligation for the
directory is discharged here: what the scenario is, which business rule it
exercises, what a consumer should expect of it, what each load-bearing byte holds,
where those bytes came from, and -- for every choice a reader could reasonably
have expected to go the other way -- why it went this way. Rule 1
(Explainability), the single user-specified rule for this migration, is what makes
that last part mandatory rather than courteous; its full text is available through
the `review_rules` tool, and the specification anchors it at sections 0.8 and
0.8.1, with the artifacts it forces into scope listed at section 0.2.1.6.

Two documents govern this one and it is subordinate to both. The folder index at
[`../README.md`](../README.md) holds the reasoning that is identical across all
ten scenarios and is cited here by section number rather than repeated. The
byte-encoding contract -- record widths, one-based field positions, the
zoned-decimal sign-overpunch table, the implied decimal, and the line-ending and
trailing-newline rules -- lives in `tests/fixtures/README.md`, is cited by section
number, and is never restated here, so that no second copy of an encoding rule is
free to drift from the first. Paths outside this directory are written as
repository-root-relative code spans rather than as relative links, because a
citation that has to climb seven directory levels is the kind that stops resolving
without anyone noticing. Everything under `app/**` and `tests/**` is
reference-only: read, never edited.

---

## 1. Scenario intent

A transaction whose origination date is **exactly equal** to the account's
expiration date, and which must post.

That is the whole condition. This is the positive half of the expiration
boundary: the account expires on a date, a record arrives bearing that same date,
and the correct outcome is a post rather than a reject. Master section 6.1 names
this scenario for precisely that purpose, and `tests/README.md` lines 572-573
state the same rule in prose -- a transaction dated equal to the account
expiration date must post, one day past must reject.

---

## 2. The business rule it exercises

The gate is in `app/cbl/CBTRN02C.cbl`, inside paragraph `1500-B-LOOKUP-ACCT`
whose header is at line 393. Lines 414-420 read:

```cobol
                IF ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS (1:10)
                  CONTINUE
                ELSE
                  MOVE 103 TO WS-VALIDATION-FAIL-REASON
                  MOVE 'TRANSACTION RECEIVED AFTER ACCT EXPIRATION'
                    TO WS-VALIDATION-FAIL-REASON-DESC
                END-IF
```

Two properties of line 414 carry this scenario.

**The comparison is `>=`, so it is inclusive.** An equal date satisfies it, control
takes the `CONTINUE` arm at line 415, and the `ELSE` arm is not entered: reason
**103** is never assigned at line 417 and its description --
`TRANSACTION RECEIVED AFTER ACCT EXPIRATION`, the verbatim text at line 418 and
in the reject table of `tests/README.md` at line 566 -- is never moved. The branch
this scenario proves is the one it does **not** take.

**`DALYTRAN-ORIG-TS (1:10)` is a reference-modified character range, not a date
value.** The modifier takes the first ten bytes of the 26-character originating
timestamp and compares them against `ACCT-EXPIRAION-DATE`, which
`app/cpy/CVACT01Y.cpy` declares at line 11 as `PIC X(10)`. Those ten bytes are
therefore load-bearing input rather than decoration, which is why master section
6.3 requires an input originating timestamp to be deterministic, author-supplied,
a literal, and never masked. Section 6 of the folder index carries the same
requirement for this folder.

The gate is reached only after three earlier outcomes have been avoided, and each
one has to hold for this scenario to be about the expiration date at all:

| Not taken | Where | Why it is not taken here |
|---|---|---|
| reason 100, `INVALID CARD NUMBER FOUND` | lines 385-387, in `1500-A-LOOKUP-XREF` at line 380 | the card in this record is the one the seed cross-reference resolves to account `00000000007` |
| reason 101, `ACCOUNT RECORD NOT FOUND` | lines 397-399 | the account the cross-reference names exists |
| reason 102, `OVERLIMIT TRANSACTION` | lines 407-412, over the balance computed at lines 403-405 | the amount leaves the computed balance within the credit limit -- see section 6.2, where this turns out to be load-bearing rather than incidental |

---

## 3. Expected outcome -- a post, not a reject

Line 211 tests the validation reason after `1500-VALIDATE-TRAN` has run at line
210. A reason of zero performs `2000-POST-TRANSACTION` at line 212, whose paragraph
header is line 424; any other reason performs the reject paragraph at line 215.
This record takes the first route, so:

- **No reject row is produced.** Reason 103 is not assigned, and nothing is
  written that would become a row of `ledger.transaction_rejects` -- the table
  created at line 542 of `V1__ledger.sql`, whose three columns the folder index
  describes in its section 2.3.
- **The category-balance row is updated, not created.** Lines 495-499 fork on the
  flag that line 473 initialises and lines 474-479 clear or set: a key-matching
  row takes `2700-B-UPDATE-TCATBAL-REC` at line 526, which adds the amount at line
  527 and `REWRITE`s at line 528. With this directory's bytes that is
  **100.00 + 504.77 = 604.77**, exact in fixed point at both ends -- the operand
  and the result are `NUMERIC(11,2)` in `ledger.transaction_category_balances`,
  declared at line 726 of `V1__ledger.sql`.
- **The account is adjusted.** Lines 545-551 add the amount to `ACCT-CURR-BAL`
  and then, because `DALYTRAN-AMT >= 0` at line 548, add it to
  `ACCT-CURR-CYC-CREDIT` at line 549 rather than to `ACCT-CURR-CYC-DEBIT` at line
  551. This is stated as prose deliberately: no account image is shipped here (see
  section 6.8), so the numbers come from the seed account row rather than from a
  file in this directory. That row carries `ACCT-CURR-BAL` +193.00 and both cycle
  fields at +0.00, so the effect is a balance of +697.77 and a cycle credit of
  +504.77.
- **One posted transaction is written.** Paragraph
  `2900-WRITE-TRANSACTION-FILE` at line 562 writes the record at line 564. That
  written record is what `transact.txt` represents: a row already posted, loaded
  into `ledger.transactions` ahead of an assertion rather than presented to a
  program as a feed.

This directory has no expected-output counterpart, and that absence is by design
rather than by omission: folder section 4.1 records that the reference tree's
fixture-to-expected-output mirroring is not imported here, so assertions on these
bytes live in test code. Nothing in this tree is graded, tolerated or aggregated;
folder section 9 states that boundary and it is not restated here.

---

## 4. Fixture bytes and governance

### 4.1 Files, widths, record counts, line endings

Every number in this table was measured on this branch rather than recalled, and
every one of them is re-measurable with the commands in folder section 10.

| File | Record | Copybook | Bytes per record | Records | Line ending | Bytes on disk |
|---|---|---|---|---|---|---|
| `dailytran.txt` | `DALYTRAN-RECORD` | `app/cpy/CVTRA06Y.cpy` | 350 | 1 | LF | 351 |
| `transact.txt` | `TRAN-RECORD` | `app/cpy/CVTRA05Y.cpy` | 350 | 1 | LF | 351 |
| `tcatbal.txt` | `TRAN-CAT-BAL-RECORD` | `app/cpy/CVTRA01Y.cpy` | 50 | 1 | LF | 51 |

Each on-disk figure is the record plus one trailing line feed, and the
carriage-return count across all three files is zero. Master sections 3.2 and 3.3
are the authority for both rules, and folder section 3.3 -- Decision C -- carries
the folder-level consequence, including why the CRLF that the `tcatbal` seed ships
is not carried across.

### 4.2 The load-bearing values, named

Positions are one-based and inclusive. The authoritative position tables are
master sections 5.1, 5.5 and 5.6 and are not reproduced here; what follows is only
this scenario's values in those positions, so that a reader can check a byte
without decoding the whole record.

In `dailytran.txt`:

| Positions | Field | Value | Why it holds this value |
|---|---|---|---|
| 133-143 | `DALYTRAN-AMT` | `0000005047G` | +504.77 in zoned decimal with sign overpunch, inside the credit limit on purpose (section 6.2) |
| 263-278 | `DALYTRAN-CARD-NUM` | `4859452612877065` | the seed card the cross-reference resolves, so reason 100 cannot fire |
| 279-288 | date inside `DALYTRAN-ORIG-TS` | `2024-12-13` | equal to the seed account's expiration date, which is the property being proved (sections 2 and 6.1) |
| 289-304 | time-of-day tail of the same field | ` 19:27:53.000000` | the seed's own tail, carried across because only the first ten bytes take part in the comparison (section 6.1) |
| 305-330 | `DALYTRAN-PROC-TS` | 26 spaces | an unposted feed record, and the input to the nullable column (section 6.4) |
| 331-350 | `FILLER` | 20 spaces | the program never moves this range (section 6.4) |

In `transact.txt`: positions 1-304 are byte-for-byte identical to positions 1-304
of this directory's `dailytran.txt`, measured. Positions 305-330 carry
`2024-12-13 00:00:00.000000` and `FILLER` at 331-350 carries 20 spaces. Sections
6.4 and 6.5 account for both.

In `tcatbal.txt`: the record key at 1-17 is the account `00000000007`, the type
code `01` and the category `0001`, composed in that order by lines 469-471 of
`app/cbl/CBTRN02C.cbl`; `TRAN-CAT-BAL` at 18-28 is `0000001000{`, which is
+100.00; and `FILLER` at 29-50 is 22 ASCII zeros. Sections 6.6 and 6.7 account for
the last two.

Both 26-character timestamps are well-formed `YYYY-MM-DD HH:MM:SS.mmmmmm`
literals. That shape is not an invention of this directory:
`app/cpy/CSDAT01Y.cpy` declares the `WS-TIMESTAMP` group at line 42 with thirteen
members across lines 43-55 summing to exactly 26 bytes, the space separator being
the `FILLER` at line 48 and the dot the `FILLER` at line 54.

### 4.3 Provenance attestation

Master section 10.3 requires three statements, and folder section 7 restates the
obligation for every scenario in this tree because every non-empty record file
here carries a primary account number.

**1. The identity-shaped bytes are synthetic and seed-derived.** The primary
account number `4859452612877065`, the account identifier `00000000007`, the
merchant identifier `800000000`, and the merchant name, city and postal code all
come from the published CardDemo demonstration seed. `dailytran.txt` derives from
record 1 of `app/data/ASCII/dailytran.txt`. `tcatbal.txt` derives from the
account-7 row of `app/data/ASCII/tcatbal.txt`, which is line 7 of that file. The
account values quoted as prose in section 3 come from the account-7 row of
`app/data/ASCII/acctdata.txt`, where the expiration date sits at positions 59-68
and reads `2024-12-13`, the credit limit reads `00000020650{` for +2065.00, and
both cycle fields read `00000000000{` for +0.00. Master sections 10.1 and 10.2
carry the attestation itself and the derivation of record; neither is reproduced
here.

**2. They represent no real person and no real account.** These are demonstration
values published with the reference application. They are not credentials, they
identify no real cardholder, and they name no real account.

**3. Which business-rule fields were reshaped away from the seed value.** This
differs per file, so each is stated separately.

- `dailytran.txt` -- **exactly one** field reshaped: the date inside
  `DALYTRAN-ORIG-TS`, moved from the seed's `2022-06-10` to `2024-12-13`. Measured
  against the seed that is four bytes, at positions 282, 284, 285 and 288. The
  amount, the card number, the merchant fields, the description, the identifiers,
  the type and category codes and the time-of-day tail are all seed-verbatim.
- `tcatbal.txt` -- **no** business-rule field is reshaped away from the seed
  *record shape*: the key and the 22-zero `FILLER` are the seed account-7 row's,
  and the line ending is normalised from the seed's CRLF to LF under folder
  section 3.3. The one field whose value is not the seed's is the balance, which
  carries the template value rather than the seed's; section 6.7 records that
  choice and the alternative it displaced. A line-ending normalisation is exactly
  that and nothing more -- it is not a repair, and the seed is not modified by it.
- `transact.txt` -- **no seed exists to reshape.** `app/data/ASCII/` holds exactly
  nine files and no `transact.txt`, because in the baseline the posted-transaction
  master is an **output** of `CBTRN02C` -- written at line 564 -- and never an
  input. Item 7 of the table in folder section 4.2 records the same measurement.
  These bytes are therefore authored from the `CVTRA05Y` layout, with positions
  1-304 taken from this scenario's own `dailytran.txt` and the processing timestamp
  supplied separately, which is what section 6.5 accounts for.

---

## 5. The one-day discriminator pair

**This directory is one half of a pair, and the pairing is the point of it.** Its
partner is the sibling scenario `reject_103_expired`, which carries `2024-12-14`
in the same ten bytes -- exactly one day later. Folder section 1.1 is the
authority for the pairing rule: a pair must agree byte for byte except in the
single field that moves, because a pair that differs in two fields no longer
isolates which one caused the outcome.

**The single differing day is the discriminator.** This side proves that `>=`
admits equality and the record posts; the partner proves that one day past assigns
reason 103. Neither proves it alone. A test that only checked a date well before
expiry and a date well after it would pass even if the operator on line 414 were
`>` instead of `>=`, and that operator is the whole content of the rule.

The agreement is a measurement, not an intention. Compared byte by byte on this
branch, `dailytran.txt` and `reject_103_expired/dailytran.txt` differ at **exactly
one byte, one-based position 288** -- a `3` here against a `4` there, the last
digit of the day. Positions 1-278 and 289-350 are equal. The claim is scoped to
`dailytran.txt` on purpose, because that is the file whose bytes line 414 reads;
folder section 1.1 scopes it the same way.

The consequence is worth stating plainly, because it is what makes the date in
this directory an authored value rather than an inherited one: any stray difference
anywhere else in the 350 bytes would destroy the proof. It would leave a reader
unable to say whether the outcome turned on the day or on the other difference, and
it would do so silently, since both records would still look like well-formed
transactions.

---

## 6. Why the bytes are what they are

Eleven choices in this directory could reasonably have gone the other way, and
each one is recorded below with the category of reasoning that justifies it. The
labels are written in the one permitted form -- plural, unparenthesised, colon
retained, no emphasis markup -- which `docs/CODE_DOCUMENTATION_STANDARD.md` sets
out at its lines 203-234 and folder section 8 restates: the labels are searched
literally before they are read, so a second spelling of a category makes an audit
silently partial.

### 6.1 The date sits exactly on the expiration date rather than comfortably before it

Trade-offs: a date well before expiry would also post, and it would make a more
forgiving fixture -- one that survived a change to the seed account's expiration
value. It is declined because it would prove nothing about the edge. The `>` and
`>=` readings of line 414 agree on every input except an equal date, so an equal
date is the only value that separates them, and separating them is the entire
purpose of this scenario. The cost accepted is a coupling: this fixture is tied to
one specific seed account value, and a change to that row would falsify it. The
coupling is recorded here and the value is cited to its seed row in section 4.3, so
it is checkable rather than hidden.

Assumptions: the ten date bytes moved and the sixteen-byte time-of-day tail behind
them did not. Only the first ten bytes reach the comparison, so the tail at 289-304
is irrelevant to the outcome and is carried across from the seed unchanged. This is
worth stating because the natural reading of a date boundary is that the whole
timestamp shifted, and a fixture whose tail had also moved would still post while
adding a second difference against both the template and the partner -- which is
exactly what folder section 1.1 forbids, since a pair that differs in two fields no
longer attributes the outcome to either.

### 6.2 The amount stays inside the credit limit even though this scenario is about a date

Assumptions: lines 407-413 and 414-420 are two **independent** `IF` statements
executed in sequence rather than two arms of one choice, and both move into the
same field, `WS-VALIDATION-FAIL-REASON`. The last assignment that fires is
therefore the one that survives, so a record that was both over limit and expired
would take 102 at line 410 and have it overwritten by 103 at line 417.

Trade-offs: the amount is held at +504.77, inside the seed account's credit limit
of +2065.00. The balance the limit gate compares is computed at lines 403-405 from
the two cycle fields plus the amount, and both cycle fields are +0.00 in the seed
account row, so that computed balance equals the amount and the gate at line 407
passes. Were the amount over the limit, 102 would be assigned -- and because this
scenario's date gate passes and so never overwrites it, the record would reject for
the credit limit while the directory name claimed an expiration boundary. The
amount is load-bearing rather than decoration, and moving it is the easiest way to
invalidate this fixture without touching the field it is named for.

### 6.3 An inclusive character comparison stands in for a date comparison

Assumptions: line 414 compares `ACCT-EXPIRAION-DATE`, a `PIC X(10)` field, against
`DALYTRAN-ORIG-TS (1:10)`, a reference-modified ten-byte character range. Both
sides are characters, so the operator is a character comparison and not date
arithmetic. It agrees with a calendar comparison only because the form is
zero-padded ISO order -- year, then month, then day -- which makes lexicographic
order and calendar order the same order. Two consequences follow, and both matter
beyond this directory. First, it is why a static byte fixture can exercise a date
rule with no date arithmetic anywhere in the fixture: `2024-12-13` compared against
`2024-12-13` is a byte comparison a reader can verify by eye. Second, it is why the
migrated target can store the value as a real date-typed column --
`ledger.transactions.orig_ts TIMESTAMP(6)` at line 231 of `V1__ledger.sql` --
rather than preserving it as a string to keep the comparison behaving: the ordering
survives the conversion instead of depending on the representation. Rewriting the
date in any other order, `12/13/2024` for instance, would still look like a date to
a reader while inverting the comparison for most inputs.

### 6.4 The processing timestamp is blank in one file and filled in the other

Assumptions: the two files load two columns with **different** nullability.
`V1__ledger.sql` declares `ledger.daily_transactions.proc_ts TIMESTAMP(6)` --
nullable -- at line 463, and `ledger.transactions.proc_ts TIMESTAMP(6) NOT NULL` at
line 247. A feed record has not been posted, so its processing timestamp is 26
spaces exactly as the seed carries it, which is also what master section 6.3
requires of an input fixture; a posted record has been posted, so its processing
timestamp holds a value. The closing `FILLER` at 331-350 is 20 spaces in both files
for a related reason: lines 425-436 move exactly twelve fields from `DALYTRAN-*` to
`TRAN-*` and lines 437-438 supply the processing timestamp, so that range is never
moved at all.

Alternatives Considered: one shared 350-byte record file would have served both
tables, since the two layouts are identical in picture and order -- folder section
3.2 measures that identity and records the decision. It is rejected because a
single file cannot be blank and non-blank at once, so one of the two nullability
assertions would become inexpressible at any price. Two files cost roughly 330
duplicated bytes per scenario and the diligence of editing both; that is the
cheaper side of the trade.

### 6.5 The posted record's processing timestamp is the boundary date's last second

Alternatives Considered: `transact.txt` carries `2024-12-13 23:59:59.000000` in
positions 305-330, and the originating timestamp at positions 274-304 carries
`2024-12-13 19:27:53.000000`, so the processing stamp falls **after** the instant it
processes. Refactoring Rationale: an earlier revision of this file carried
`2024-12-13 00:00:00.000000` here, which is midnight at the head of the same day and is
therefore roughly seven and a half hours **before** the originating instant on that day.
That is the very incoherence the rest of this subsection argues against, one paragraph
below, so the file contradicted its own reasoning: a row cannot be processed before it
originated, and the processing timestamp is a real access path rather than decoration.
The last second of the boundary date is chosen instead of the following midnight because
the business date is the single axis this directory turns on -- moving the stamp into
`2024-12-14` would move a second value and stop the directory being single-axis -- and it
is chosen over reusing the originating instant exactly because equality would make the
two stamps indistinguishable and hide which one an assertion is reading. It remains an
injected literal with six zero microseconds and is never a clock reading. The alternative was to carry the `happy_path` template's
`2022-07-18 00:00:00.000000` across verbatim, which would keep that literal uniform
across scenario directories and leave this file byte-identical to the template; the
partner directory `reject_103_expired` does exactly that, measured. It is declined
here because it would place the posting run roughly two and a half years before the
transaction it posts originated, and that incoherence is not cosmetic: the
processing timestamp is a real access path in the target, indexed non-uniquely by
`idx_transactions_proc_ts` at lines 293-294 of `V1__ledger.sql`. That index is the
counterpart of the alternate index declared `KEYS(26 304)` at line 27 of
`app/jcl/TRANIDX.jcl` -- 26 bytes at a zero-based displacement of 304, which is
one-based positions 305-330, precisely this field -- with `NONUNIQUEKEY` at line 28
for the same reason the target index is non-unique. A row whose processing stamp
preceded its own originating stamp would sort nonsensically against its own
business date on that path.

What the template establishes is the **form** rather than the instant: an injected
literal with six zero microseconds, never a clock reading. Folder
section 6 quotes the template's literal when it states that requirement -- this
field must be filled and must be an injected literal rather than a clock reading --
and the same section delegates the provenance of each scenario's own literal to that
scenario's README, which is the obligation this subsection discharges. The form is
therefore honoured exactly, and the date within it is the one this directory turns
on. That discipline is the baseline's own -- `app/jcl/INTCALC.jcl` line 22 passes a
business date as `PARM='2022071800'` rather than letting the program read a clock.
Taking the date from this scenario's own boundary value is
also what keeps the directory single-axis: `2024-12-13` is the one value that
moves, and both timestamps here follow from it. Measured, this file differs from the template inside those two
windows and nowhere else: in the originating timestamp at the four date bytes, and in the
processing timestamp at the four date bytes together with the six time bytes that carry
the last second of the day rather than midnight. Because the partner keeps the template literal, the two
directories' posted images differ in both windows, which is why the pairing claim
in section 5 is scoped to `dailytran.txt`: that is the record line 414 reads.

### 6.6 The 50-byte record's FILLER is 22 ASCII zeros and not spaces

Assumptions: `app/cpy/CVTRA01Y.cpy` declares that range at line 10 as
`FILLER PIC X(22)`, so the picture alone permits either form and cannot settle it.
What settles it is the seed's own convention, which master section 5.6 records.
Blanking those bytes would change 22 of 50 bytes -- nearly half the record -- so any
byte-level comparison against a seed-shaped record would fail for a reason entirely
unrelated to the behaviour under test, and the failure would point at the
comparison rather than at the cause.

### 6.7 The category-balance row is present, and carries the template balance

Assumptions: **presence selects the arm, not value.** Line 473 sets the create flag
to `N`; the keyed read at line 474 leaves it at `N` when a row matches the composed
key, and lines 475-478 set it to `Y` on `INVALID KEY`; lines 495-499 then fork on
it. A key-matching row therefore drives the update path
`2700-B-UPDATE-TCATBAL-REC` at line 526, while an **absent** row -- not a row
holding zero -- drives the create path `2700-A-CREATE-TCATBAL-REC` at line 503.
Line 481 accepts status `00` or `23`, which is what makes the not-found case an
ordinary outcome rather than an error. The sibling scenario `zero_balance` is the
one that exercises the create path, and it does so with a `tcatbal.txt` of exactly
zero bytes, measured -- so a zero balance here would not select that path and would
only look as though it did.

Alternatives Considered: reproducing the seed account-7 row's `0000000000{`
(+0.00) unmodified was the alternative, and for that one field it would give the
cleanest possible provenance. It is declined, and each reason below is a
measurement rather than a preference. Holding the
file byte-identical to the template's `0000001000{` (+100.00) keeps this directory
differing from the template only in the field that moves, which is the property
folder section 1.1 requires, and it keeps the partner's row identical to this one so
the pair stays single-axis on this file as well as on `dailytran.txt`. It also keeps
one prior balance across every populated scenario rather than nine, so a consumer
asserting balance arithmetic reasons from a single value. The balance is immaterial
to the gate this scenario turns on, because line 414 reads the account record and
never this row, so the uniform value costs the scenario nothing. Both facts are
asserted by a present consumer: `TransactionFixtureContractTest` asserts the
+100.00 prior balance at its lines 253-254 and the byte-identity with the template
at its lines 273-274, so a silent divergence here fails that test rather than
surviving in prose.

### 6.8 No account image and no cross-reference image is shipped

Refactoring Rationale: the reference counterpart at
`tests/fixtures/posting/boundary_expiry_equal` ships five record files, adding the
account master and the card cross-reference, because `CBTRN02C` opens both during
validation. This directory ships three. `CBTRN02C` is batch-service's program;
transaction-service migrates four online programs and owns exactly one schema,
`ledger`. The account, customer and cross-reference records belong to the `account`
schema and the card record to `card`, both owned by other modules, so an account
image here would place another bounded context's records inside this module's test
data and leave two modules free to disagree about the same bytes. Folder section 3.1
is the authority for that decision and its reasoning is not restated here.

Alternatives Considered: mirroring the reference tree's five-file shape was the
alternative, and it is what a reader comparing the two trees would expect to find.
It is declined on the ownership grounds above. The cost is that the expiration date
this scenario turns on is prose in section 3 rather than a byte in this directory.
That is accepted because the value is cited to its seed row, where anyone can
re-measure it, and because a duplicated account image would be the more expensive
error: no later edit repairs a record that was placed in the wrong module.

### 6.9 The field name is quoted in the baseline's own spelling

Assumptions: `ACCT-EXPIRAION-DATE` is quoted exactly as declared, at line 11 of
`app/cpy/CVACT01Y.cpy` and at line 414 of `app/cbl/CBTRN02C.cbl`. The declared name
is what the program compiles against and what a reader searches for when moving
between this directory and the copybooks, so a citation that regularised the
spelling would stop matching the source it cites. Master section 5.2 records the
preserved spelling and notes that the card copybook carries the same form. Nothing
under `app/**` is edited. The migrated target names the column `expiration_date`,
and that renaming is recorded in the migration's data-model and schema-mapping
document rather than being settled here.

### 6.10 Money stays exact fixed point end to end

Assumptions: money is exact at every hop, in both directions. In these bytes it is
zoned decimal with sign overpunch, whose table is master section 3.4, with the
decimal implied and no separator stored, which is master section 3.5. In the target
it is `NUMERIC(11,2)`: `ledger.transactions.amount` at line 180 of
`V1__ledger.sql`, `ledger.daily_transactions.amount` at line 412 and
`ledger.transaction_category_balances.balance` at line 726. No binary
floating-point type and no JSON number appears anywhere on that path.
`0000005047G` is +504.77 and must decode to that value and no other; a value routed
through a double carries no guarantee of comparing equal to itself after one round
trip, which is why the arithmetic in section 3 is stated to the cent.

### 6.11 Consumers are annotated rather than assumed

Assumptions: an availability marker is a claim about the state of the branch, and
it goes stale silently -- it can end up telling a reader that an artifact in front
of them does not exist, which quietly retires the obligation the marker was
carrying. Section 7 therefore annotates every consumer with `[present]` or
`[planned]` per master section 9.3, and each marker there is a measurement with its
evidence beside it rather than an expectation.

---

## 7. Availability of consumers

Annotated per master section 9.3, and measured on this branch.

- `services/transaction-service/src/test/java/com/carddemo/transaction/fixtures/TransactionFixtureContractTest.java`
  -- **[present]**, and it reads these bytes. Its lines 104-119 assert that this
  scenario carries all three files as whole records of the declared widths with no
  carriage return; its lines 153-168 assert the one-day pair, that this directory's
  originating timestamp begins `2024-12-13`, that the partner's begins `2024-12-14`,
  and that the two time-of-day tails are equal at ` 19:27:53.000000`; its lines
  195-204 assert the seed card; its lines 236-256 assert the composed key and the
  +100.00 prior balance; and its lines 273-275 assert byte-identity of `tcatbal.txt`
  with the template's. A boundary value that moved by one day, or a balance that
  drifted, fails that test instead of passing quietly.
- A test that loads these record images **into** the `ledger` schema --
  **[planned]**. `TransactionRepositoryIT` is present and runs against a real
  PostgreSQL container with this module's own migration applied, but it builds every
  row it needs in code rather than reading a record image, so nothing loads these
  files into a database. The properties these records are shaped for -- the
  non-unique index on the processing timestamp and the key-ordered read paths --
  are properties of the real engine, so such a test belongs on a container and not
  on an in-memory substitute.
- [`../../application-test.yml`](../../application-test.yml) -- **[present]**, one
  directory above `fixtures/` rather than beside this file. Both halves of this
  reference were previously written as `../application-test.yml`, which resolves to
  `fixtures/application-test.yml` and matches nothing, so the link did not open; the
  word "sibling" was wrong for the same reason, since the profile is a sibling of the
  `fixtures/` directory and not of this README. Every other scenario README in this
  directory family already cites it with two levels, so the corrected form is the one
  the family uses rather than a new convention. It
  pins schema resolution to `ledger` for the connection at its line 283 and for the
  migration tool at its line 338, and points the migration tool at this module's own
  migration at its line 325. It deliberately sets no clock property, and its lines
  163-179 record why: the timestamp formatter takes a clock as a collaborator, so a
  consumer asserting on a 26-character timestamp injects a fixed clock of its own
  rather than inheriting one from configuration.
- `services/transaction-service/src/main/resources/db/migration/V1__ledger.sql` --
  **[present]**. It creates `ledger.transactions` at line 118,
  `ledger.daily_transactions` at line 321, `ledger.transaction_rejects` at line 542
  and `ledger.transaction_category_balances` at line 694, together with the two
  indexes cited above.
- `tests/fixtures/README.md` -- **[present]**, the authoritative byte contract.
- `tests/fixtures/posting/boundary_expiry_equal/` -- **[present]** as the reference
  counterpart, and reference-only. It is read for its record shape and never
  edited, moved or re-pinned.

---

## 8. Where the rest of the contract lives

Cited by section number, so that no encoding rule exists here in a second copy free
to drift from the first.

- `tests/fixtures/README.md` -- sections 3.1 through 3.5 for fixed width, line
  endings, the trailing newline, the sign-overpunch table and the implied decimal;
  sections 5.1, 5.5 and 5.6 for the three record layouts; section 5.2 for the
  preserved field-name spelling; section 6.1 for the validation rule this scenario
  is named in; section 6.3 for the asymmetry between the two timestamps; section 9.1
  for the four items this document owes; section 9.3 for the availability
  convention; and sections 10.1 through 10.3 for the provenance attestation.
- [`../README.md`](../README.md) -- everything uniform across the ten scenarios: the
  pairing rule in section 1.1, the positions and the reject contract in sections 2.1
  through 2.3, the three folder-level decisions in sections 3.1 through 3.3, the
  seed facts in section 4.2, the consumers in section 5, the determinism rules in
  section 6, the governance obligation in section 7, the authoring checklist in
  section 8, the reach of the mechanical gates in section 9, and the re-measurement
  commands in section 10.
- `app/cbl/CBTRN02C.cbl` -- the behavioural specification, cited above by line.
- `docs/CODE_DOCUMENTATION_STANDARD.md` -- the documentation convention, including
  the one permitted written form of the four labels used throughout section 6.

One property of this directory is worth stating rather than leaving implied. Folder
section 9 records that the Checkstyle configuration audits `java` files only, so
every `.txt` and `.md` file here -- this document included -- sits outside the audit
set, and that the suppression entry matching this path is defensive rather than
load-bearing. No linter reads this document. Rule 1 compliance here therefore rests
on authoring discipline and review, which is where the rule's own validation gate
places it, and which raises the bar for this document rather than lowering it.
