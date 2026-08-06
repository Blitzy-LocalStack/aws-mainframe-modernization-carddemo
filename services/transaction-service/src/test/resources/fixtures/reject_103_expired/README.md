# reject_103_expired -- one day past the account expiration date

Scenario README for `fixtures/reject_103_expired/`. The reasoning that is
identical across all ten scenarios lives in the folder index at
[`../README.md`](../README.md) and is cited here by section number rather than
copied; the byte-encoding contract lives in
[`tests/fixtures/README.md`](../../../../../../../tests/fixtures/README.md) and is
cited by section number and never restated.

---

## 1. Intent

This is the **reject** member of the one-day pair described in folder section 1.1.
It exists to prove that the expiration comparison rejects as soon as the
transaction date passes the account's expiration date, by the smallest step the
field can express: one day.

Its counterpart is `boundary_expiry_equal`, and the two `dailytran.txt` files
differ in **exactly one byte** -- position 288, measured. All 349 other bytes are
identical.

---

## 2. The rule it exercises

The expiration gate in
[`app/cbl/CBTRN02C.cbl`](../../../../../../../app/cbl/CBTRN02C.cbl), on its
**reject** arm:

```text
IF ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS (1:10)
   CONTINUE
ELSE
   MOVE 103 TO WS-VALIDATION-FAIL-REASON
   MOVE 'TRANSACTION RECEIVED AFTER ACCT EXPIRATION'
     TO WS-VALIDATION-FAIL-REASON-DESC
END-IF
```

That is lines 414-419. The field name is the baseline spelling
`ACCT-EXPIRAION-DATE`, quoted exactly as declared.

The expiration date on seed row 7 of
[`app/data/ASCII/acctdata.txt`](../../../../../../../app/data/ASCII/acctdata.txt),
positions 59-68, is `2024-12-13`. This record's originating timestamp begins
`2024-12-14`, so the comparison `2024-12-13 >= 2024-12-14` is false, control takes
the `ELSE` on line 416, reason code 103 is set on line 417 and the message on line
418.

The reason then being non-zero, line 211 takes its `ELSE` on line 213, the reject
count is incremented on line 214 and `2500-WRITE-REJECT-REC` runs on line 215.
Posting on line 212 never runs.

---

## 3. Expected outcome

- One reject, carrying reason code **103** and the message `TRANSACTION RECEIVED
  AFTER ACCT EXPIRATION` byte for byte as declared on line 418. The message is 42
  characters, the longest of the reject messages in this program, and it carries no
  trailing punctuation.
- No posted transaction, no account-balance change and no category-balance change:
  lines 440-442 sit inside `2000-POST-TRANSACTION`, which is not reached.
- The reject count becomes 1, so line 229 is true and the return code is moved to
  **4** on line 230 -- a soft warn, because a correctly written business reject is
  the program working as specified.

---

## 4. Fixture bytes and governance

### 4.1 Files, widths, record counts, line endings

| File | Table it loads | Records | Bytes per record | Bytes on disk | Line ending |
|---|---|---|---|---|---|
| `dailytran.txt` | `ledger.daily_transactions` | 1 | 350 | 351 | LF, one trailing newline |
| `transact.txt` | `ledger.transactions` | 1 | 350 | 351 | LF, one trailing newline |
| `tcatbal.txt` | `ledger.transaction_category_balances` | 1 | 50 | 51 | LF, one trailing newline |

Folder section 2.3 is the authority for why a reject scenario ships no fourth
file: the reject is an output of posting, not an input to it.

### 4.2 Provenance attestation

Folder section 7 mandates the three items below.

1. **Synthetic and seed-derived.** `dailytran.txt` is record 1 of
   [`app/data/ASCII/dailytran.txt`](../../../../../../../app/data/ASCII/dailytran.txt)
   with one field reshaped, per item 3. `transact.txt` and `tcatbal.txt` are
   byte-identical to their `happy_path` counterparts, whose provenance is recorded
   in [`../happy_path/README.md`](../happy_path/README.md) section 4.2.
2. **No real person and no real account.** Every account number, card number,
   name and address byte here comes from the published CardDemo demonstration
   seed. They are demonstration values, not credentials, and they identify no real
   person and no real account.
3. **Which bytes were reshaped away from the seed value.** One field in
   `dailytran.txt`: the date inside the originating timestamp at positions 279-304,
   moved from the seed's `2022-06-10` to `2024-12-14`. Measured against the seed
   that is four bytes -- positions 282, 284, 285 and 288; measured against
   `boundary_expiry_equal/dailytran.txt` it is one byte, position 288. The
   time-of-day tail ` 19:27:53.000000` at positions 289-304 is the seed's,
   unchanged.

---

## 5. Two decisions about this record

Assumptions: only the first ten bytes of the originating timestamp participate in
the comparison, so the time-of-day tail is irrelevant to the outcome and is carried
across unchanged rather than adjusted. This is worth stating because the natural
reading of "one day past" is that the whole timestamp moved forward by 24 hours; it
did not, and it must not, because folder section 1.1 requires the pair to agree
byte for byte except in the single field that moves. A record whose tail also moved
would still reject, but the pair would no longer isolate the date.

Trade-offs: the amount stays inside the credit limit, at the template's +504.77.
Lines 407-413 and 414-421 are two **independent** `IF` statements executed in
sequence rather than two arms of one choice, so a record that was both over limit
and expired would have 102 assigned on line 410 and then overwritten by 103 on line
417. Only one reject code ever survives, and which one depends on statement order.
Keeping the amount inside the limit is what makes the 103 in this scenario
attributable to the date rather than to the order of two statements.

Alternatives Considered: expressing the same boundary by moving the account's
expiration date one day earlier instead of moving the transaction date one day
later was rejected on ownership grounds. The expiration date lives in the account
record, and folder section 3.1 records that this folder ships no account image
because `ledger` is the only schema this module owns.
