# boundary_exact_limit -- an amount landing exactly on the credit limit

Scenario README for `fixtures/boundary_exact_limit/`, and the only file in this folder
able to hold prose. The three record files beside it are fixed-width positional data
with no comment construct, so the reasoning behind their bytes has nowhere else to
live: this document carries it for all three.

Reasoning shared by all ten scenarios lives in the folder index at
[`../README.md`](../README.md), and the byte-encoding contract lives in the master at
[`tests/fixtures/README.md`](../../../../../../../tests/fixtures/README.md). Both are
cited below by section number and neither is restated, because a second copy of a
convention is a second thing able to drift out of agreement with the first. Sections 1
to 4 supply the four items master section 9.1 requires of every scenario README;
sections 5 to 7 carry the per-decision rationale; section 8 records what reads these
bytes.

Three record files sit beside this one, and each has a distinct job:

- **`dailytran.txt`** -- the unposted input record, one 350-byte
  `DALYTRAN-RECORD`. It is the record the posting rule reads, and its amount field
  is the byte this whole scenario turns on. It depends on the account it resolves
  to through its card number, which is not a file here (section 5), and it loads
  into `ledger.daily_transactions`.
- **`transact.txt`** -- the posted image of that same transaction, one 350-byte
  `TRAN-RECORD`. It is what the run is expected to produce, so it makes the
  outcome at section 3 checkable rather than merely asserted. It depends on
  `dailytran.txt` for its first 304 positions (section 6.1) and on the
  `NOT NULL` processing timestamp its table declares (section 6.2), and it loads
  into `ledger.transactions`.
- **`tcatbal.txt`** -- the prior category balance, one 50-byte
  `TRAN-CAT-BAL-RECORD`. It decides which arm of the create-versus-update branch
  runs and supplies the balance the post adds to. It depends on the account, type
  and category key agreeing with the input record (section 7.2), and it loads into
  `ledger.transaction_category_balances`.

---

## 1. Intent

This is the **pass** member of the one-cent pair described at folder section 1.1.
Its whole reason for existing is to prove that the credit-limit comparison is
**inclusive**: an amount landing exactly on the limit posts rather than rejecting.
The scenario is therefore about a single comparison operator, and every byte in the
folder is arranged so that operator is the only thing the outcome can be
attributed to.

### 1.1 The one-cent pair, byte for byte

The counterpart is the sibling [`reject_102_overlimit`](../reject_102_overlimit/README.md),
and the pair is the mechanism rather than a coincidence of naming. Folder section
1.1 reports the agreement as a measurement, and it reproduces on this branch: the two
`dailytran.txt` records are **350 bytes each and differ at exactly one 1-based
position, 143** -- the amount field's low-order byte. All 349 remaining bytes are
identical.

| Scenario | `DALYTRAN-AMT` bytes | Decoded | Trial balance | `limit >= trial balance?` | Result |
|---|---|---|---|---|---|
| `boundary_exact_limit` (this folder) | `0000020650{` | +2065.00 | 2065.00 | TRUE | POST |
| `reject_102_overlimit` (sibling) | `0000020650A` | +2065.01 | 2065.01 | FALSE | reject 102 |

Assumptions: the column names the predicate in the direction the reference evaluates
it. `app/cbl/CBTRN02C.cbl` L403 to L405 forms the trial balance and L407 then tests
`IF ACCT-CREDIT-LIMIT >= WS-TEMP-BAL`, so the operand on the left of `>=` is the limit
and the operand on the right is the trial balance -- which is why a trial balance
landing exactly on the limit is TRUE and posts, and why one cent above it is FALSE and
reaches the reject at L410. An earlier revision of this heading read `` `>=` limit? ``, which
reverses the operands: read in that direction a trial balance of 2065.00 is not
greater than or equal to *more* than itself, so the TRUE in row one contradicted the
label above it and the one comparison this whole folder exists to pin down was
described backwards.

The single byte is legible only through the overpunch: `{` is the positive
low-order digit **0**, making the digit string `00000206500`, and `A` is the
positive low-order digit **1**, making it `00000206501`. With the two implied
decimal places the difference is exactly **one cent**. The overpunch table itself
lives at master section 3.4 and the implied decimal at master section 3.5; neither
is reproduced here, because a decoding table copied into ten scenario documents is
a table that has to agree with itself in ten places.

Alternatives Considered: an amount comfortably under the limit would also post, and
a pair built from "well under" and "well over" values would still show one posting
and one rejecting. It was rejected because it distinguishes nothing -- master
section 6.1 makes exactly this point, that such a pair passes even when the
operator is wrong. An implementation using `>` instead of `>=` produces identical
results on every input except one: the value sitting precisely on the limit. That
value is the whole of this scenario.

Trade-offs: the consequence is that the discriminating byte is invisible in a
terminal. `{` and `A` are both single printable characters and both records are the
same length, so reviewing the pair by eye is unreliable by construction. The
byte-length and carriage-return sweeps at folder section 10 are the check that
works. This cost is accepted because the alternative -- a difference large enough
to see -- is the very thing that stops the pair proving inclusivity.

---

## 2. The rule it exercises

`CBTRN02C` reject 102, on its pass arm: the `>=` credit-limit boundary in
paragraph `1500-B-LOOKUP-ACCT` of
[`app/cbl/CBTRN02C.cbl`](../../../../../../../app/cbl/CBTRN02C.cbl).

The paragraph is reached along a chain worth naming, because the reject reason it
sets is what gates the post. The main loop performs `1500-VALIDATE-TRAN` at line
210, and posting happens only when the reason is still zero afterwards -- lines
211 and 212 make that gate explicit. `1500-VALIDATE-TRAN` at line 370 performs the
cross-reference lookup first, then reaches `1500-B-LOOKUP-ACCT` at line 393, which
takes the account key from `XREF-ACCT-ID` at line 394 rather than from the input
record.

Lines 403 to 405 compute the value being compared:

```text
COMPUTE WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT
                    - ACCT-CURR-CYC-DEBIT
                    + DALYTRAN-AMT
```

Line 407 is the gate, `IF ACCT-CREDIT-LIMIT >= WS-TEMP-BAL`, and line 408 is its
`CONTINUE`. Equality therefore takes the pass arm. Reason 102 is set only on the
`ELSE`, lines 409 through 412, where line 410 moves the reason and line 411 moves
the message `OVERLIMIT TRANSACTION`. **In this scenario that arm is not taken**, so
that message is named here to identify the branch being avoided rather than to
describe an output.

The same rule is stated in prose at
[`tests/README.md`](../../../../../../../tests/README.md) lines 570 and 571 -- a
balance exactly at the credit limit must post, and one cent over must reject with
reason 102 -- and the message text is tabulated against reason 102 at its line 565.

The arithmetic reduces to a single term, and that is deliberate. The card number in
this record resolves through the cross-reference to account `00000000007`, which is
**row 7** of
[`app/data/ASCII/acctdata.txt`](../../../../../../../app/data/ASCII/acctdata.txt)
and the only row in that seed carrying that account. Decoded, its two cycle totals
are both `00000000000{`, which is +0.00, and its credit limit at positions 25-36 is
`00000020650{`, which is **+2065.00**. With both cycle terms at zero, `WS-TEMP-BAL`
reduces to `DALYTRAN-AMT` exactly, so line 407 compares the limit directly against
the amount in this folder:

```text
WS-TEMP-BAL = 0.00 - 0.00 + 2065.00 = 2065.00
2065.00 >= 2065.00  ->  TRUE  ->  CONTINUE  ->  reason stays 0  ->  POST
```

That account image is **not a file in this folder** -- section 5 gives the ownership
reason -- so the limit is cited as a fact of the seed row the card resolves to. The
reference scenario at
[`tests/fixtures/posting/boundary_exact_limit/acctdata.txt`](../../../../../../../tests/fixtures/posting/boundary_exact_limit/acctdata.txt)
carries that same seed row unchanged, measured byte for byte, which is why the two
trees agree about what the boundary value is.

Assumptions: the inclusive `>=` at line 407 is the external contract these bytes
depend on, and it is the one assumption whose failure inverts the scenario rather
than merely weakening it. An implementation comparing with `>` turns this fixture
from a passing post into a reject 102 while every byte in the folder stays valid,
and the failure surfaces as an unexplained rejection rather than as an operator
defect. The operator was read from line 407 directly before being written down.

Assumptions: both cycle totals being +0.00 on the resolved account is what permits
the amount to be set equal to the limit and land precisely on it. Master section
6.1 records the same fixture-tuning assumption for the posting boundary scenarios
generally -- the amount is set relative to the chosen account's credit limit
because the cycle totals are zero in the seeds -- and that section also requires a
scenario using non-zero cycle values to write out its intended arithmetic. This
scenario uses the zero-valued seed row, so the arithmetic above is the whole of it.

The second gate does not contaminate the result. Line 414,
`IF ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS (1:10)`, compares the account
expiration `2024-12-13` against the first ten bytes of this record's originating
timestamp, `2022-06-10`, so it passes and reason 103 is never reached. The field
name is quoted with the baseline spelling because that is the name the program
compiles against. Assumptions: this gate has to pass for the scenario to mean
anything -- a record failing it would reject regardless of the credit-limit
comparison, and the pass outcome asserted at section 3 would be unreachable while
the folder still appeared to be exercising the limit.

---

## 3. Expected outcome

**POST.** The validation reason stays `0`, reason 102 does not fire, and no reject
record is produced -- nothing is written to `ledger.transaction_rejects`, and in
particular no row carrying reason code 102.

Three effects follow, and master section 6.1 lists them as the effects of a
successful post generally:

- **The posted transaction exists.** One row for transaction id
  `0000000000683580` carrying an amount of +2065.00, written by
  `2900-WRITE-TRANSACTION-FILE` at line 562 through the `WRITE` at line 564. In the
  target that row lands in `ledger.transactions`, whose `amount` column is declared
  `NUMERIC(11,2)` -- the exact-scale counterpart of the copybook's
  `PIC S9(09)V99`. The value is carried as `BigDecimal` at scale 2 in Java and as a
  string on the wire, so no consumer can widen it into a binary approximation and
  lose the cent this scenario turns on.
- **The category balance moves through the update arm.** The row keyed
  `00000000007` / `01` / `0001` goes from `0.00` to `2065.00`. The dispatch at lines
  495 through 499 takes its `ELSE` into `2700-B-UPDATE-TCATBAL-REC` at line 526,
  whose line 527 is `ADD DALYTRAN-AMT TO TRAN-CAT-BAL`; the create arm at line 503
  is not taken. Section 7.2 explains which bytes decide that.
- **The account balances move.** `2800-UPDATE-ACCOUNT-REC` at line 545 adds the
  amount to the current balance at line 547, taking it from `193.00` to `2258.00`;
  line 548 tests the sign, and because the amount is non-negative line 549 adds it
  to the cycle credit, taking that from `0.00` to `2065.00`. The cycle debit is
  untouched, since line 551 is reached only for a negative amount.

That last effect is a fact of the scenario's semantics rather than a row in this
folder. The account record lives in the `account` schema, owned by another module,
so the figures above describe what the posting rule does to it and not something
this folder asserts by shipping bytes. Section 5 gives the reason the image is
absent.

Determinism needs no comparator here, and none is claimed. It comes from the
literals: the originating timestamp is a literal, the processing timestamp on the input
record is blank, and the processing timestamp on the posted record is pinned. A
rerun over these bytes reproduces the same values because there is nothing in them
for a clock to change. Section 6 records where each of those three choices comes
from.

---

## 4. Fixture bytes and governance

### 4.1 Files, widths, record counts, line endings

| File | Copybook | Table it loads | RECLN | Records | Line ending | Bytes on disk |
|---|---|---|---:|---:|---|---:|
| `dailytran.txt` | `CVTRA06Y` | `ledger.daily_transactions` | 350 | 1 | LF | 351 |
| `transact.txt` | `CVTRA05Y` | `ledger.transactions` | 350 | 1 | LF | 351 |
| `tcatbal.txt` | `CVTRA01Y` | `ledger.transaction_category_balances` | 50 | 1 | LF | 51 |

Three files, not five: the two absent record images are accounted for at section 5,
and folder section 3.1 is the folder-wide decision. The layouts themselves are at
master sections 5.1, 5.5 and 5.6. The one extra byte on disk in each row is the
single trailing newline master section 3.3 requires -- every figure in the table was
measured on this branch rather than derived from the layout, so a width other than
350, 350 and 50 is a corrupt record and not a formatting preference.

### 4.2 The byte positions a reader needs

Positional, no delimiters, per master section 3.1, so a position is the only way to
name a field. In both 350-byte records:

| Field | 1-based positions | Value here |
|---|---|---|
| `DALYTRAN-ID` / `TRAN-ID` | 1-16 | `0000000000683580` |
| type code, category code | 17-18, 19-22 | `01`, `0001` |
| `DALYTRAN-AMT` / `TRAN-AMT` | 133-143 | `0000020650{` = +2065.00 |
| `CARD-NUM` | 263-278 | `************7065` -- masked to its last four digits; read positions 263-278 of `dailytran.txt` for the 16 bytes themselves, and section 4.3 item 2 for the digest |
| `ORIG-TS` | 279-304 | `2022-06-10 19:27:53.000000` |
| `PROC-TS` | 305-330 | 26 spaces in `dailytran.txt`; the pinned literal in `transact.txt` |
| `FILLER` | 331-350 | 20 spaces in both |

In the 50-byte record, `TRAN-CAT-KEY` occupies 1-17 and reads
`00000000007010001`, the balance occupies 18-28 and reads `0000000000{`, and
`FILLER` occupies 29-50. Folder section 2.1 corroborates the three positions that
carry the most weight from a second, independent source, and folder section 2.2
covers the 26-character timestamp form.

### 4.3 Provenance attestation

Master section 10.3 requires three statements of any scenario carrying primary
account number or identity-shaped bytes. This one does, so all three follow.

1. **Synthetic and seed-derived.** Every identity byte here comes unchanged from
   the published CardDemo sample datasets under
   [`app/data/ASCII/`](../../../../../../../app/data/ASCII/). `dailytran.txt` is
   **record 1** of
   [`app/data/ASCII/dailytran.txt`](../../../../../../../app/data/ASCII/dailytran.txt),
   which supplies the transaction id `0000000000683580`, the primary account number
   at positions 263-278 and the four merchant identity fields at positions 144-262,
   together with the source, description and originating timestamp. Those fields are
   cited by position rather than reproduced, because the seed record and the
   [`app/cpy/CVTRA06Y.cpy`](../../../../../../../app/cpy/CVTRA06Y.cpy) layout are
   already the authority for their bytes and a second copy in prose is one more place
   an identity-shaped value has to be redacted from. `tcatbal.txt` is **row 7** of
   [`app/data/ASCII/tcatbal.txt`](../../../../../../../app/data/ASCII/tcatbal.txt),
   the seed row whose account, type and category key is `00000000007` / `01` /
   `0001`, and it is byte-identical to that row once its carriage return is dropped
   -- measured, not asserted. `transact.txt` has **no seed row to derive from**, for
   the reason at section 6.3, so its bytes come from the
   [`app/cpy/CVTRA05Y.cpy`](../../../../../../../app/cpy/CVTRA05Y.cpy) layout with
   its first 304 positions taken from this scenario's own `dailytran.txt`.
2. **No real person and no real account.** The account number, the primary account
   number and the merchant identity bytes identify no real person and no real
   account, and none of them is a credential. The fixture files carry those bytes
   unmasked because a fixed-width record image is the artifact under test and a
   masked image would not load. This document masks the primary account number and
   cites the merchant fields by position instead, so that provenance can be attested
   without a second, un-redactable copy of identity-shaped bytes outside the record
   the test actually reads.
3. **Which bytes were reshaped away from the seed value.** Exactly one field, in
   two files. `DALYTRAN-AMT` at positions 133-143 moves from the seed's
   `0000005047G`, which is +504.77, to `0000020650{`, which is +2065.00 -- so it is
   set to the credit limit of the account the card resolves to, per section 2. Six
   bytes change, positions 138 through 143, measured. The same six positions carry
   into `transact.txt` for the reason at section 6.1. `transact.txt` additionally
   pins `PROC-TS` at 305-330 to a literal, per section 6.2. **`tcatbal.txt` has no
   reshaped bytes at all** -- its `+0.00` balance is the seed's own value, as
   section 7.1 establishes by measurement, so the answer this item owes for that
   file is "none" rather than a list. Every identity byte in all three files is
   carried unchanged.

---

## 5. Why the boundary is expressed in the amount rather than in the limit

Alternatives Considered: the same boundary could be expressed from the other side --
leave the amount at the seed's +504.77 and lower the credit limit to match it. That
was rejected on ownership grounds. The credit limit lives in the account record, and
the `ACCTFILE` record described by
[`app/cpy/CVACT01Y.cpy`](../../../../../../../app/cpy/CVACT01Y.cpy) maps to
`account.accounts` while the `XREFFILE` record described by
[`app/cpy/CVACT03Y.cpy`](../../../../../../../app/cpy/CVACT03Y.cpy) maps to
`account.card_xref` -- both owned by account-service, whereas this module owns only
the `ledger` schema. Moving the limit would place another bounded context's records
inside this module's test data and leave two deployables free to disagree about the
same bytes, which is the failure that schema ownership exists to prevent. Adjusting
a field this folder legitimately owns is the smaller change.

This is worth stating plainly rather than leaving to inference, because the
reference scenario at `tests/fixtures/posting/boundary_exact_limit/` **does** ship
both `acctdata.txt` and `cardxref.txt`, and a reader diffing the two folders side by
side would otherwise conclude that two files are missing here. Neither is missing;
both are absent by decision, which is why the governance table at section 4.1
carries three rows rather than five. The posting program itself belongs to
batch-service, which is also why this folder documents the rule it satisfies
rather than owning the code that applies it.

Trade-offs: the consequence is that `0000020650{` is hard-wired to seed account row
7 by way of the card number at positions 263-278. Point that card number at a
different account and the amount silently stops being the boundary -- the record
still posts, the scenario still passes, and it proves nothing. That is why section 2
writes the derivation out in full, from card number through the cross-reference to
account `00000000007` and on to positions 25-36 of seed row 7: another author can
re-verify the chain in three steps instead of re-deriving it, and a reviewer can
tell in one reading whether the amount and the account still agree.

Assumptions: whichever test drives this scenario end to end seeds the account tier
with account `00000000007` carrying that same credit limit and both cycle totals at
zero. Seeding a different limit makes the record post or reject for a reason having
nothing to do with the bytes in this folder, and the failure presents as a fixture
defect when it is a seeding difference.

---

## 6. Why `transact.txt` is a second file, and what determines its bytes

Folder section 3.2 is the folder-wide decision that a scenario ships an unposted
record and a posted record as two files rather than one. Three of this file's
properties are scenario-level choices and are recorded here.

### 6.1 Positions 1-304 mirror `dailytran.txt`, including the moved amount

This is exact rather than stylistic. Lines 425 through 436 of `CBTRN02C` are
**twelve** literal one-to-one `MOVE DALYTRAN-* TO TRAN-*` statements -- id, type
code, category code, source, description, amount at line 430, merchant id, merchant
name, merchant city, merchant postal code, card number and originating timestamp --
covering exactly those 304 positions with nothing reformatted, rounded or recomputed
in transit. Positions 1-304 of the two files are byte-identical, measured, and the
only span in which they differ is 305-330.

Assumptions: carrying the amount across is a correctness requirement and not a tidiness
one, which is worth naming because the seed amount is what a template would leave in
place. A `transact.txt` reading +504.77 would describe a posted row 1560.23 lighter than
the record that actually drove the gate at line 407, so the pass this folder exists to
demonstrate would be asserted against a row the run never produced -- and the assertion
would succeed, because nothing in the file itself is malformed. The two `transact.txt` files here and in `happy_path` differ at
exactly positions 138-143, measured, which is the same six bytes section 4.3 records
for the daily record.

Trade-offs: the amount consequently lives in two files per scenario, and an author
moving one has to move the other. Folder section 3.2 already accepts that as the
standing cost of keeping the two records separate; it is repeated here only as the
concrete thing that breaks -- the two files disagreeing about the value the gate
compared.

### 6.2 `PROC-TS` is pinned to `2022-07-18 00:00:00.000000`

Assumptions: two independent facts make a literal necessary here.
[`V1__ledger.sql`](../../../../main/resources/db/migration/V1__ledger.sql) declares
`ledger.transactions.proc_ts` as `NOT NULL` while
`ledger.daily_transactions.proc_ts` is nullable, so the posted record has to carry a
value where the unposted one cannot. And the program originates that field from a
clock: line 437 performs `Z-GET-DB2-FORMAT-TIMESTAMP` and line 438 moves the result
into `TRAN-PROC-TS`. A clock read is not reproducible, so a fixture pins a literal
rather than reproducing a runtime value. Master section 6.3 draws the same
distinction from the other direction -- the originating timestamp on an input record
is author-supplied and load-bearing and must be a literal, while the processing
timestamp is the only runtime-varying range and must be blank on input.

Alternatives Considered: letting each scenario choose its own stamp was rejected,
because ten scenarios inventing ten stamps leaves nothing comparable across them and
turns a shared field into a per-folder detail. This literal is byte-identical to the
one in [`../happy_path/transact.txt`](../happy_path/transact.txt), measured, so the
folder carries one processing timestamp rather than ten. The value is not chosen at
random either: its provenance is
[`app/jcl/INTCALC.jcl`](../../../../../../../app/jcl/INTCALC.jcl) line 22,
`PARM='2022071800'`, which is the only injected business date anywhere in
`app/jcl` -- the three other `PARM=` occurrences in that tree carry an empty string,
a resource-definition option string and a commented-out address, none of them a
date. Using the one date the baseline batch chain actually injects keeps the
fixture's stamp traceable to the baseline instead of to an author's preference.

### 6.3 There is no seed row for this file, and it exists anyway

Alternatives Considered: deriving the file from a seed is impossible, because
`app/data/ASCII/` holds exactly nine files and none of them is `transact.txt`. The
posted-transaction master is an **output** of the posting program -- written at line
562 through line 564 -- and never an input, so the seed set has no reason to carry
one. Omitting the file was the other option and was rejected: `ledger.transactions`
is a table this module owns, and section 3 names a posted row with a specific id and
amount, so a scenario asserting that outcome without shipping the row's image leaves
the claim unverifiable against anything. Authoring the bytes from the `CVTRA05Y`
layout, with the 304 mirrored positions supplying every field the program copies, is
what makes the posted side of the scenario checkable.

---

## 7. The decisions carried by `tcatbal.txt`

Fifty bytes of positional data with no comment construct, so master section 9.1
makes this document the carrier for its reasoning. Three of its choices are ones a
reasonable author could have made differently.

### 7.1 The balance is `+0.00`, not the template's `+100.00`

The balance at positions 18-28 reads `0000000000{`, which is +0.00. The
`happy_path` file reads `0000001000{` at the same positions, which is +100.00, so
the two differ at exactly one byte, position 24 -- measured.

Assumptions: zero is what leaves this scenario's arithmetic with a single term.
Section 2 establishes that the account's cycle totals are both zero, so the trial
balance reduces to the amount; holding the category balance at zero as well makes
the figure this scenario posts into that row provably `0.00 + 2065.00 = 2065.00`,
with no second contributing term for a reader to carry. The category balance is not
an input to the credit-limit gate at line 407, and that is precisely why leaving the
choice unexplained would be a trap: another reader could move it believing it inert
and silently change the total section 3 asserts, while the pass or reject outcome
stayed the same and no test noticed.

Alternatives Considered: carrying +100.00 so that every populated scenario in the
folder shares one prior balance, giving a consumer one value to remember rather than
two. Rejected on provenance. All fifty rows of
[`app/data/ASCII/tcatbal.txt`](../../../../../../../app/data/ASCII/tcatbal.txt)
carry `0000000000{` -- measured, one distinct balance value across the whole seed --
so +0.00 is the unreshaped value and +100.00 is the reshaped one, and the reference
tree agrees by carrying +0.00 at
[`tests/fixtures/posting/boundary_exact_limit/tcatbal.txt`](../../../../../../../tests/fixtures/posting/boundary_exact_limit/tcatbal.txt).

Trade-offs: the folder therefore holds two different category balances rather than
one, and that uniformity is given up deliberately. Agreeing with the seed and with
the reference tree is the stronger property, because that tree is the oracle this
migration is verified against, whereas a shared constant is only a convenience for
whoever writes the assertion.

### 7.2 A key-matching row is present at all

The first three fields form `TRAN-CAT-KEY` at positions 1-17, reading
`00000000007` then `01` then `0001`. Assumptions: the second and third are the type
code and category code the sibling `dailytran.txt` record carries at positions 17-18
and 19-22, which read `01` and `0001` -- measured. The first is the account, which
does not appear in the input record at all: it arrives as `XREF-ACCT-ID` from the
cross-reference read at line 394, and section 2 traces that chain from this record's
card number to account `00000000007`. All three agree, so the composed key
**resolves**, which is what sends the dispatch at lines 495 through 499 down its
`ELSE` arm into `2700-B-UPDATE-TCATBAL-REC` at line 526 rather than into
`2700-A-CREATE-TCATBAL-REC` at line 503.

Alternatives Considered: shipping no `tcatbal.txt`, or shipping an empty one. Both
were rejected because either resolves to no row and silently moves this scenario onto
the create arm -- still posting, still passing, but exercising the credit-limit
boundary in a different configuration than this document describes. Folder section 1
assigns the create arm to `zero_balance`, whose empty file is its discriminating
property, so expressing it here as well would duplicate that coverage while quietly
removing the update-arm coverage this scenario contributes. Stated plainly: this row
is not scenery. Deleting it, or drifting any of its seventeen key bytes, changes
which branch runs.

### 7.3 `FILLER` is 22 ASCII zeros, and the line ending is LF

Assumptions: the trailing `FILLER` at positions 29-50 is twenty-two ASCII zeros
rather than the spaces every other `FILLER` in this folder uses. That is the seed's
own convention for this record, established by master section 5.6, and this file is
byte-identical to seed row 7 -- so substituting spaces would depart from the seed
purely for cosmetic symmetry with the two 350-byte records, and would make the
"no reshaped bytes" statement at section 4.3 item 3 untrue.

Assumptions: this is also the one file in the folder whose seed ships CRLF, which
master section 3.2 names among the three such seeds; the other two records here
derive from LF seeds. A retained carriage return matters specifically because of the
`FILLER` above it -- the stray byte is absorbed into that trailing field and pushes
the record one byte past `RECLN 50`, corrupting the last field rather than announcing
itself. This file carries no carriage return, measured, and dropping the carriage
return from seed row 7 reproduces it exactly. The folder-wide decision to normalise
is folder section 3.3, which holds the mechanism and the measurement behind it, and
is cited rather than restated here.

---

## 8. What consumes this scenario

Assumptions: this is written as a consumer contract rather than as a roster of the
classes presently in the module. A roster answers "what reads this today", which a
reader can settle with one search and which is wrong the moment a test is added; the
contract answers "what must a reader supply to use these bytes", which does not change
unless the bytes do.

- **The byte contract is machine-checked.**
  [`TransactionFixtureContractTest`](../../../java/com/carddemo/transaction/fixtures/TransactionFixtureContractTest.java)
  resolves each file as a classpath resource under the fixture prefix and asserts, for
  this scenario specifically, that all three files are present at their declared widths,
  that the amount field forms the one-cent pair with its sibling, and that the category
  balance is the zero its reference row declares. That is what makes the byte facts in
  sections 1.1, 4.1 and 7.1 checkable by a build rather than by a reading, so a drift in
  position 143, in a record width or in the balance field fails the module instead of
  quietly invalidating this document.
- **The row expectations are for a fixture-loading integration test** over
  `ledger.transactions`, `ledger.daily_transactions` and
  `ledger.transaction_category_balances`, which loads the three record images as a
  starting state and asserts the posted row, the balance movement and the absence of a
  reject row. Assumptions: it runs against a real PostgreSQL instance through
  Testcontainers. The engine matters rather than being incidental -- the non-unique index
  on the processing timestamp and the key-ordered read paths these records feed are
  properties of the real engine, so a test passing against an in-memory substitute would
  prove nothing about the behaviour being claimed. Where these records feed a list path,
  that path pages by **key** and not by ordinal position, so record ordering is part of
  the fixture's meaning.
- **The inclusive comparison is asserted at service level**, pairing this scenario
  against `reject_102_overlimit` so the two amounts separate `>=` from `>`. Assumptions:
  that consumer needs the account tier seeded as section 5 describes, because the limit
  it compares against is not in this folder.

Two siblings are load-bearing for anything that consumes these bytes:
[`V1__ledger.sql`](../../../../main/resources/db/migration/V1__ledger.sql), which creates
the three tables above with the nullability asymmetry section 6.2 depends on, and
[`../../application-test.yml`](../../application-test.yml), which pins schema resolution
and the migration location. That profile sets no clock property: determinism for a
26-character timestamp is supplied as a fixed clock passed to the shared formatter.

One limitation belongs here rather than in a reader's assumptions. The documentation gate
configures itself for `.java` files only, so **no linter inspects this document or the
three records beside it**. Rule 1 compliance in this folder rests on authoring discipline
and human review, which is where the rule's own validation gate places it.
