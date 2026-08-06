# reject_109_rewrite_invalid_key -- a failure raised after validation has passed

Scenario README for `fixtures/reject_109_rewrite_invalid_key/`. The reasoning that is
identical across all ten scenarios lives in the folder index at
[`../README.md`](../README.md) and is cited here by section number rather than copied;
the byte-encoding contract lives in
[`tests/fixtures/README.md`](../../../../../../../tests/fixtures/README.md) and is
cited by section number and never restated.

---

## 1. Intent

This scenario exists to prove that a reject can be raised **after** validation has
already succeeded. Reasons 100 through 103 are validation outcomes; 109 is a write
outcome, reached only once validation has returned a reason of zero and posting has
begun.

Folder section 1.2 records that this scenario has no counterpart in the reference tree
at `tests/fixtures/posting/`, which covers 100, 101, 102 and 103 and stops. Its fixture
is authored from the record layout rather than adapted from an existing one.

Its `dailytran.txt` is byte-identical to `happy_path/dailytran.txt`, measured. That is
deliberate and is the subject of section 5, not an oversight.

---

## 2. The rule it exercises

The account rewrite in
[`app/cbl/CBTRN02C.cbl`](../../../../../../../app/cbl/CBTRN02C.cbl), reached through the
posting path.

| Step | Line | What happens |
|---|---|---|
| validation succeeded | 211 | `IF WS-VALIDATION-FAIL-REASON = 0` is true |
| posting begins | 212 | `PERFORM 2000-POST-TRANSACTION` |
| the category balance is updated first | 440 | `PERFORM 2700-UPDATE-TCATBAL` |
| the account is updated second | 441 | `PERFORM 2800-UPDATE-ACCOUNT-REC` |
| the paragraph | 545 | `2800-UPDATE-ACCOUNT-REC.` |
| in-memory balances move | 547-552 | the amount is added to `ACCT-CURR-BAL`, then to the cycle credit or the cycle debit depending on its sign |
| the rewrite | 554 | `REWRITE FD-ACCTFILE-REC FROM ACCOUNT-RECORD` |
| the failure arm | 555-558 | `INVALID KEY` sets reason 109 on 556 and the message `ACCOUNT RECORD NOT FOUND` on 557 |

The ordering is the whole content of the scenario. Line 211 is the gate: 109 is
unreachable unless every one of 100, 101, 102 and 103 was **not** raised. That is why
this record's card number, amount and date are all the template's passing values.

---

## 3. Expected outcome

- One reject, carrying reason code **109** and the message `ACCOUNT RECORD NOT FOUND`
  byte for byte as declared on line 557. The message is 24 characters and carries no
  trailing punctuation.
- An assertion **must** include the code. Folder section 1.2 records the collision: this
  message is byte-identical to the one reason 101 writes on line 398, so a
  description-only assertion cannot tell this scenario from `reject_101_acct_missing` and
  will pass against the wrong one.
- The category balance has **already** been updated when the failure is raised, because
  line 440 runs before line 441. An assertion that expects the whole posting attempt to
  leave no trace is asserting something the baseline does not do.

Assumptions: that last point is a property of statement order in the baseline and is
recorded so that a test is written against what the program does rather than against what
a transactional reading of it would suggest. It also means the two sides of the migration
differ observably here, and an assertion must pick one deliberately: the baseline leaves
the category-balance update in place because a file rewrite is not undone, whereas the
target runs the whole posting unit of work as one database transaction, which specification
section 0.4.1.3 sets out and defends against the alternatives. A target-side assertion for
this scenario therefore expects the category balance **unchanged**, and a baseline-side
comparison expects it updated. Stating both is the point; assuming either silently is what
would make the assertion wrong.

---

## 4. Fixture bytes and governance

### 4.1 Files, widths, record counts, line endings

| File | Table it loads | Records | Bytes per record | Bytes on disk | Line ending |
|---|---|---|---|---|---|
| `dailytran.txt` | `ledger.daily_transactions` | 1 | 350 | 351 | LF, one trailing newline |
| `transact.txt` | `ledger.transactions` | 1 | 350 | 351 | LF, one trailing newline |
| `tcatbal.txt` | `ledger.transaction_category_balances` | 1 | 50 | 51 | LF, one trailing newline |

The category-balance row is present rather than absent, which matters here: with a row
on the composed key the read on line 474 succeeds, so line 440 takes the update arm on
line 498 and completes, and the failure that follows on line 441 is unambiguously the
account rewrite rather than anything earlier.

### 4.2 Provenance attestation

Folder section 7 mandates the three items below.

1. **Synthetic and seed-derived.** `dailytran.txt` is byte-identical to record 1 of
   [`app/data/ASCII/dailytran.txt`](../../../../../../../app/data/ASCII/dailytran.txt),
   measured. `transact.txt` carries the identity bytes of **line 114** of that same
   file -- `TRAN-ID 0000000380632461`, amount `0000004283C`, merchant
   `Beahan, Little and Sanford` -- on the card the primary input also uses, measured;
   folder section 4.2 item 7 is the authority for there being no seeded
   posted-transaction master to copy from, so the record is authored from the
   `CVTRA05Y` layout over those bytes. `tcatbal.txt` is byte-identical to its
   `happy_path` counterpart, whose provenance is recorded in
   [`../happy_path/README.md`](../happy_path/README.md) section 4.2.

   Assumptions: `transact.txt` deliberately does **not** reuse the identifier the
   primary input carries, and the reason is a schema fact rather than a preference.
   [`V1__ledger.sql`](../../../../main/resources/db/migration/V1__ledger.sql) declares
   `CONSTRAINT pk_transactions PRIMARY KEY (transaction_id)`, so seeding
   `ledger.transactions` with the identifier the feed is about to post would make the
   posting insert raise a unique violation -- a different failure from the account
   rewrite on line 554 that this scenario exists to exercise, and one that would mask
   it. It would also leave the scenario's central assertion unfalsifiable: a row
   bearing that identifier would already be present before the run, so "the failed
   attempt added no row for this transaction" could not fail. Holding a **different**
   identifier on the **same** card keeps that assertion falsifiable while leaving the
   table populated, so it is a genuine no-new-row check rather than an empty-table
   check, and `idx_transactions_card_num` has data on the browse path.

   Alternatives Considered: giving `transact.txt` the same bytes as
   `happy_path/transact.txt`, which would have made the folder's three record files
   uniform and saved a reader one comparison. Rejected for the two consequences
   above -- uniformity here costs the scenario the property it exists to prove.
2. **No real person and no real account.** Every account number, card number, customer
   name, merchant name and address byte here comes from the published CardDemo
   demonstration seed. They are demonstration values, not credentials, and they identify
   no real person and no real account.
3. **Which bytes were reshaped away from the seed value.** In `dailytran.txt`, none --
   the record is the seed row unchanged, which is the point of the scenario. In
   `transact.txt`, the processing timestamp at positions 305-330 only: measured against
   line 114 of the seed, the two records differ nowhere else. In `tcatbal.txt`, the
   account identifier at positions 1-11 and the balance at positions 18-28, as in
   `happy_path`.

   Assumptions: that one field has to move because the two tables disagree on
   nullability. `ledger.transactions.proc_ts` is `NOT NULL` while
   `ledger.daily_transactions.proc_ts` is nullable -- folder section 3.2 records that
   asymmetry as the reason these are two files rather than one -- so the 26 blanks the
   seed carries at 305-330, correct for a feed record that has not been posted yet,
   cannot stand in a table whose rows exist only once processing has happened. The
   baseline mints the value at run time, performing `Z-GET-DB2-FORMAT-TIMESTAMP` on
   line 437 and moving the result on line 438. A fixture cannot read a clock and stay
   reproducible, so the literal `2022-07-18 00:00:00.000000` is used: it is the
   business date the baseline injects as a job parameter at
   [`app/jcl/INTCALC.jcl`](../../../../../../../app/jcl/INTCALC.jcl) line 22,
   `PARM='2022071800'`, and it is the literal `happy_path/transact.txt` already
   carries, so the folder stays internally consistent. Master section 6.3 pins
   `PROC-TS` blank on **input** fixtures and this file is seeded prior state rather
   than input, so the two are consistent in principle -- both insist a fixture hold a
   fixed literal rather than a wall-clock read.

---

## 5. Why the primary input carries no discriminator

Assumptions: the discriminating precondition is **not expressible in this folder**. What
fails on line 554 is a rewrite of the account record, and the account record belongs to
the `account` schema owned by another module -- folder section 3.1 is the authority for
this folder shipping no account image. The precondition is that the account is readable
during validation on line 395 and not writable on line 554, and it is established by the
consuming test's own account state, not by any byte here.

Alternatives Considered: giving the record a distinguishing byte so that the folder alone
determined the outcome was evaluated and rejected. Any byte that changed the outcome would
have to change one of the four fields the validation gates read -- the card number, the
amount, or the date -- and changing any of them moves the record onto a **validation**
reject path, at which point line 211 is false, line 212 never runs, and the post-validation
path this scenario exists to cover stops being exercised at all. The absence of a
discriminator is not a gap in the fixture; it is a consequence of what the scenario tests.

Trade-offs: two scenarios in this folder therefore hold byte-identical primary inputs --
this one and `happy_path` -- and a diff between the two directories shows no difference in
`dailytran.txt` at all. To a reader skimming the tree that looks like an accidental
duplicate. This README and folder section 1.2 are the only things that distinguish them,
so both must stay accurate; if either is deleted or allowed to drift, the scenario reads
as a copy-paste error and will be "cleaned up" by someone acting in good faith.

Refactoring Rationale: the scenario was kept in the folder rather than dropped for being
unexpressible in bytes, because the reject **contract** it asserts is one this module owns
outright -- the three columns of `ledger.transaction_rejects` described in folder section
2.3 -- and because 109 is the only reject code in the program that shares its message text
with another. Dropping it would leave that collision uncovered, and the collision is
precisely the kind of thing a description-only assertion elsewhere would hide.
