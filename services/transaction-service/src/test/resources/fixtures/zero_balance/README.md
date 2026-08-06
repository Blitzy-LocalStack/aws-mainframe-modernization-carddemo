# zero_balance -- the create arm of the category-balance fork

Scenario README for `fixtures/zero_balance/`. The reasoning that is identical
across all ten scenarios lives in the folder index at
[`../README.md`](../README.md) and is cited here by section number rather than
copied; the byte-encoding contract lives in
[`tests/fixtures/README.md`](../../../../../../../tests/fixtures/README.md) and is
cited by section number and never restated.

---

## 1. Intent

This scenario exists to reach the **create** arm of the transaction-category-balance
fork, which is only reachable when no category-balance row exists for the composed
key. It does that through a `tcatbal.txt` that is present and **zero bytes long**.

Read the scenario name carefully, because it is the one name in this folder that
invites the wrong reading. It does not mean "a row whose balance is zero". It means
"no accumulated balance exists yet for this key", and the fixture expresses that as
an absent row rather than as a zero-valued one. Section 5 records why the
distinction is not cosmetic.

Its counterpart is `happy_path`: the two `dailytran.txt` files are byte-identical,
measured, and the two scenarios differ **only** in `tcatbal.txt` -- one row against
no rows. That is what makes the fork the single variable.

---

## 2. The rule it exercises

The category-balance fork in
[`app/cbl/CBTRN02C.cbl`](../../../../../../../app/cbl/CBTRN02C.cbl), inside
`2700-UPDATE-TCATBAL` whose header is line 467.

| Step | Line | What happens |
|---|---|---|
| the key is composed | 469-471 | `XREF-ACCT-ID`, then `DALYTRAN-TYPE-CD`, then `DALYTRAN-CAT-CD` |
| the flag is preset to not-create | 473 | `MOVE 'N' TO WS-CREATE-TRANCAT-REC` |
| the row is read | 474 | `READ TCATBAL-FILE INTO TRAN-CAT-BAL-RECORD` |
| the absent row is detected | 475-478 | the `INVALID KEY` arm sets `'Y'`, after a `DISPLAY` on 476-477 that names the key and the word `Creating` |
| an absent row is explicitly not an error | 481 | `IF TCATBALF-STATUS = '00' OR '23'` accepts both the found and not-found statuses before deciding to abend on 484 |
| the fork | 495-499 | `IF WS-CREATE-TRANCAT-REC = 'Y'` performs `2700-A-CREATE-TCATBAL-REC` on 496, otherwise `2700-B-UPDATE-TCATBAL-REC` on 498 |
| the create paragraph | 503 | `2700-A-CREATE-TCATBAL-REC.`, which initialises the record on 504 and moves the account identifier on 505 |
| the update paragraph, not taken here | 526-528 | `2700-B-UPDATE-TCATBAL-REC.` adds the amount to the existing balance and rewrites |

The key this record composes is account `00000000007` -- reached from the card
number through row 7 of
[`app/data/ASCII/cardxref.txt`](../../../../../../../app/data/ASCII/cardxref.txt)
-- with transaction type `01` at positions 17-18 and category `0001` at positions
19-22 of the daily record. With no row on that key, the read takes `INVALID KEY`
and the create arm runs.

Line 481 is the load-bearing detail of the whole scenario. Status `23` is the
not-found status, and the program lists it beside `00` as an accepted outcome
precisely so that an absent row is a branch rather than a failure. A test asserting
this scenario is asserting that branch, not an error path.

---

## 3. Expected outcome

- The record posts. No row is written to `ledger.transaction_rejects`; the return
  code is left at zero, because line 229 is false.
- Exactly one category-balance row is **created**, on the key
  (`00000000007`, `01`, `0001`), carrying the transaction amount +504.77 as its
  opening balance.
- The update arm on line 498 is not taken. An assertion that only checks the
  resulting balance cannot tell the two arms apart -- both leave +504.77 when the
  prior balance was zero -- so an assertion for this scenario must distinguish
  creation from update, by row count before and after or by the row's absence
  beforehand.

That last point is the reason this scenario is not redundant with `happy_path`,
and it is why the two ship byte-identical daily records.

---

## 4. Fixture bytes and governance

### 4.1 Files, widths, record counts, line endings

| File | Table it loads | Records | Bytes per record | Bytes on disk | Line ending |
|---|---|---|---|---|---|
| `dailytran.txt` | `ledger.daily_transactions` | 1 | 350 | 351 | LF, one trailing newline |
| `transact.txt` | `ledger.transactions` | 1 | 350 | 351 | LF, one trailing newline |
| `tcatbal.txt` | `ledger.transaction_category_balances` | **0** | not applicable | **0** | not applicable |

The empty file is the only deviation from the folder's uniform shape and it is
deliberate. It carries no records, so it carries no trailing newline either: master
section 3.3 makes a line count equal a record count, and a record count of zero is a
byte count of zero. A one-byte file holding a lone newline would be one empty
record, not none.

### 4.2 Provenance attestation

Folder section 7 mandates the three items below.

1. **Synthetic and seed-derived.** `dailytran.txt` is byte-identical to record 1 of
   [`app/data/ASCII/dailytran.txt`](../../../../../../../app/data/ASCII/dailytran.txt),
   measured. `transact.txt` is byte-identical to its `happy_path` counterpart, whose
   provenance is recorded in [`../happy_path/README.md`](../happy_path/README.md)
   section 4.2. `tcatbal.txt` derives from no seed row at all, by construction.
2. **No real person and no real account.** Every account number, card number, name
   and address byte here comes from the published CardDemo demonstration seed. They
   are demonstration values, not credentials, and they identify no real person and no
   real account.
3. **Which bytes were reshaped away from the seed value.** In `dailytran.txt`, none
   -- the record is the seed row unchanged. In `transact.txt`, the processing
   timestamp at positions 305-330 only, as in `happy_path`. In `tcatbal.txt`, the
   whole of the seed row is **absent** rather than reshaped: the seed's record 1 was
   not copied and no substitute was authored.

---

## 5. Why an empty file rather than a zero-valued row

Alternatives Considered: shipping a row whose balance is `0000000000{` -- positive
zero -- looks like the obvious reading of the scenario name and was rejected because
it takes the **opposite** branch. A present row makes the read on line 474 succeed,
so the flag stays at the `'N'` set on line 473 and the fork on line 495 performs the
update arm on line 498. The scenario would then duplicate `happy_path` and the create
arm on line 496 would be covered by nothing in this folder.

The trap is closer than it looks: the seed's own `tcatbal.txt` record 1 carries
exactly `0000000000{` in its balance field, so copying the seed row verbatim -- the
default move everywhere else in this folder -- is precisely what would break this
scenario. Folder section 4.2 records that `{` and `0` are different bytes and that
the field is signed; neither fact rescues a present row from taking the wrong arm.

Alternatives Considered, second option: deleting the file rather than emptying it.
Rejected on two grounds. Folder section 1 promises three record files in every
scenario, so an absent file would make the folder index false, which is the class of
defect this whole tree of documents exists to close. And an absent file and an empty
file are different inputs to a loader: one is a missing-resource failure, the other
is an immediate end of data. Only the second is the property under test.

Trade-offs: a zero-byte file is easy to mistake for an authoring accident -- it looks
exactly like a file someone forgot to fill in, and no tool will flag it. That risk is
accepted because the alternatives are worse, and it is mitigated the only way it can
be: this README is the record that the emptiness is intentional, and the byte-length
sweep in folder section 10 reports `0` for this file as the expected result rather
than as a finding.

Assumptions: whichever test consumes this scenario loads all three files with the
same loader, including the empty one, rather than skipping a zero-byte file as
nothing to do. A loader that skipped it would leave whatever the previous test wrote
in place, and the create arm would silently become the update arm -- a false pass
rather than a failure.
