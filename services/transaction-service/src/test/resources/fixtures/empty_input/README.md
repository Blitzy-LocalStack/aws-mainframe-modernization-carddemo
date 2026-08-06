# empty_input -- a present-but-empty primary input

Scenario README for `fixtures/empty_input/`. The reasoning that is identical across
all ten scenarios lives in the folder index at [`../README.md`](../README.md) and is
cited here by section number rather than copied; the byte-encoding contract lives in
[`tests/fixtures/README.md`](../../../../../../../tests/fixtures/README.md) and is
cited by section number and never restated.

---

## 1. Intent

This scenario exists to prove that an empty daily feed is a normal outcome and not
an error: zero records are processed, nothing is rejected, and the run still ends
cleanly. It expresses that through a `dailytran.txt` that is present and **zero bytes
long**.

It is the only scenario in the folder whose primary input carries no record. The two
master images beside it are populated, which is deliberate and is the subject of
section 5.

---

## 2. The rule it exercises

The read loop in
[`app/cbl/CBTRN02C.cbl`](../../../../../../../app/cbl/CBTRN02C.cbl), lines 202-219,
and the return-code decision that follows it on lines 229-231.

```text
PERFORM UNTIL END-OF-FILE = 'Y'
    IF  END-OF-FILE = 'N'
        PERFORM 1000-DALYTRAN-GET-NEXT
        IF  END-OF-FILE = 'N'
          ADD 1 TO WS-TRANSACTION-COUNT
          ...
```

The outer test on line 202 and the guard on line 203 both pass on the first
iteration, so `1000-DALYTRAN-GET-NEXT` -- whose header is line 345 -- runs once. With
no record to return it sets the end-of-file flag, so the **inner** guard on line 205
is false and line 206 never increments the transaction count. Control returns to line
202, which is now true, and the loop ends having counted nothing.

Three consequences follow, and each is part of what this scenario asserts:

- validation on line 210 never runs, so no reject can be produced on line 215;
- the two totals displayed on lines 227-228 are both zero;
- `IF WS-REJECT-COUNT > 0` on line 229 is false, so line 230 does not run and the
  return code stays at zero.

The inner guard on line 205 is the load-bearing detail. Without it the program would
count a phantom record on an empty file. Its presence is what makes an empty feed
produce a count of zero rather than a count of one, and that is the specific property
this fixture pins.

---

## 3. Expected outcome

- Zero records processed and zero rejected. `ledger.daily_transactions` receives no
  rows from this scenario.
- No row is written to `ledger.transaction_rejects`.
- The return code is **zero**, not 4. An empty input is not a soft warn: line 230 is
  reached only by a non-zero reject count, and there is nothing here to reject.
- The two master images load normally, so a list or view assertion still has rows to
  read. See section 5.

---

## 4. Fixture bytes and governance

### 4.1 Files, widths, record counts, line endings

| File | Table it loads | Records | Bytes per record | Bytes on disk | Line ending |
|---|---|---|---|---|---|
| `dailytran.txt` | `ledger.daily_transactions` | **0** | not applicable | **0** | not applicable |
| `transact.txt` | `ledger.transactions` | 1 | 350 | 351 | LF, one trailing newline |
| `tcatbal.txt` | `ledger.transaction_category_balances` | 1 | 50 | 51 | LF, one trailing newline |

The empty file carries no records, so it carries no trailing newline either: master
section 3.3 makes a line count equal a record count, and a record count of zero is a
byte count of zero. A one-byte file holding a lone newline would be one empty record
of the wrong width, not none.

### 4.2 Provenance attestation

Folder section 7 mandates the three items below, and it mandates them for this
scenario too even though its primary input is empty, because the two files beside it
each carry a primary account number.

1. **Synthetic and seed-derived.** `transact.txt` carries the 350 bytes of record 1
   of
   [`app/data/ASCII/dailytran.txt`](../../../../../../../app/data/ASCII/dailytran.txt)
   with only the processing timestamp filled; deriving a posted-transaction image from
   a daily record is sound because folder section 2 measures the two 350-byte layouts
   as field-for-field identical in picture and order, and folder section 4.2 item 7
   records that `app/data/ASCII/` holds no `transact.txt` to copy. `tcatbal.txt`
   derives from record 1 of
   [`app/data/ASCII/tcatbal.txt`](../../../../../../../app/data/ASCII/tcatbal.txt),
   seed key `00000000001` / `01` / `0001`. `dailytran.txt` derives from no seed row, by
   construction.
2. **No real person and no real account.** Every account number, card number,
   customer name, merchant name and address byte in the two populated files comes from
   the published CardDemo demonstration seed. They are demonstration values, not
   credentials, and they identify no real person and no real account.
3. **Which bytes were reshaped away from the seed value.** In `transact.txt`, the
   processing timestamp at positions 305-330: the seed carries 26 spaces and this file
   carries the literal `2022-07-18 00:00:00.000000`. In `tcatbal.txt`, two fields --
   the account identifier at positions 1-11 from `00000000001` to `00000000007`, and
   the balance at positions 18-28 from `0000000000{` to `0000001000{`, which is +10.00.
   In `dailytran.txt`, the seed row is **absent** rather than reshaped.

---

## 5. Three decisions about this scenario

Alternatives Considered: deleting `dailytran.txt` rather than emptying it. Rejected
on two grounds. An absent file and an empty file are different inputs -- the first is
a missing-resource failure and would exercise the program's file-open abend path
rather than its loop, and the second is an immediate end of data. Only the second is
the property this scenario names. Separately, folder section 1 promises three record
files in every scenario, so deleting one would make the folder index false, which is
the class of defect this tree of documents exists to close.

Assumptions: the two master images are deliberately **not** emptied alongside the
primary input. Emptying all three would make this scenario an empty-database test
rather than an empty-feed test, and it would remove the only inputs that exercise the
`NOT NULL` side of the nullability pair folder section 3.2 is built around --
`ledger.transactions.proc_ts` is declared not-null and `ledger.daily_transactions.proc_ts`
nullable, and with no posted row present there is nothing to assert the not-null side
against. The scenario that empties the category balance instead is `zero_balance`,
and keeping the two separate is what lets each isolate one property.

Trade-offs: a zero-byte file looks exactly like a file someone forgot to author, and
no tool will flag it. That risk is accepted because the alternatives are worse, and it
is mitigated the only way it can be: this README records that the emptiness is
intentional, and the byte-length sweep in folder section 10 reports `0` for this file
as the expected result rather than as a finding. This folder now contains two
deliberately empty files -- this one and `zero_balance/tcatbal.txt` -- and they are
empty for two unrelated reasons, so neither README may be read as covering the other.
