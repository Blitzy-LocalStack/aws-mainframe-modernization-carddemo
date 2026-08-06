# reject_102_overlimit -- one cent past the credit limit

Scenario README for `fixtures/reject_102_overlimit/`. The reasoning that is
identical across all ten scenarios lives in the folder index at
[`../README.md`](../README.md) and is cited here by section number rather than
copied; the byte-encoding contract lives in
[`tests/fixtures/README.md`](../../../../../../../tests/fixtures/README.md) and is
cited by section number and never restated.

---

## 1. Intent

This is the **reject** member of the one-cent pair described in folder section
1.1. It exists to prove that the credit-limit comparison rejects as soon as the
amount exceeds the limit, by the smallest representable step in a scale-2 money
field: one cent.

Its counterpart is `boundary_exact_limit`, and the two `dailytran.txt` files
differ in **exactly one byte** -- position 143, measured. Everything else in the
two records, all 349 remaining bytes, is identical.

---

## 2. The rule it exercises

The credit-limit gate in
[`app/cbl/CBTRN02C.cbl`](../../../../../../../app/cbl/CBTRN02C.cbl), on its
**reject** arm.

Lines 403-405 compute `WS-TEMP-BAL` from the two cycle totals plus the
transaction amount; because seed account row 7 carries both cycle totals at +0.00
(positions 79-90 and 91-102 of
[`app/data/ASCII/acctdata.txt`](../../../../../../../app/data/ASCII/acctdata.txt),
both `00000000000{`), the value reduces to the amount in this file. Line 407 then
tests:

```text
IF ACCT-CREDIT-LIMIT >= WS-TEMP-BAL
   CONTINUE
ELSE
   MOVE 102 TO WS-VALIDATION-FAIL-REASON
   MOVE 'OVERLIMIT TRANSACTION'
     TO WS-VALIDATION-FAIL-REASON-DESC
END-IF
```

The credit limit is +2065.00 and this record's amount is **+2065.01**, so the
comparison is false, control takes the `ELSE` on line 409, reason code 102 is set
on line 410 and the message `OVERLIMIT TRANSACTION` on line 411.

Because the reason is then non-zero, line 211 takes its `ELSE` on line 213, the
reject count is incremented on line 214 and `2500-WRITE-REJECT-REC` runs on line
215. Posting on line 212 never runs.

---

## 3. Expected outcome

- One reject, carrying reason code **102** and the message `OVERLIMIT
  TRANSACTION` byte for byte as declared on line 411. The message is 21
  characters and carries no trailing punctuation.
- No posted transaction, no account-balance change and no category-balance change,
  because everything on lines 440-442 sits inside `2000-POST-TRANSACTION`, which
  is not reached.
- The reject count becomes 1, so line 229 is true and the return code is moved to
  **4** on line 230 -- a soft warn rather than a failure, because a correctly
  written business reject is the program working as specified.

The reject shape in the target is the three columns folder section 2.3 describes,
`raw_record CHAR(350)`, `reason_code SMALLINT` and `reason_desc VARCHAR(76)`, so
an assertion here reads the code and the description from
`ledger.transaction_rejects` and the untouched 350 input bytes from `raw_record`.

---

## 4. Fixture bytes and governance

### 4.1 Files, widths, record counts, line endings

| File | Table it loads | Records | Bytes per record | Bytes on disk | Line ending |
|---|---|---|---|---|---|
| `dailytran.txt` | `ledger.daily_transactions` | 1 | 350 | 351 | LF, one trailing newline |
| `transact.txt` | `ledger.transactions` | 1 | 350 | 351 | LF, one trailing newline |
| `tcatbal.txt` | `ledger.transaction_category_balances` | 1 | 50 | 51 | LF, one trailing newline |

Folder section 2.3 is the authority for why a reject scenario ships no fourth
file: a reject is an output of posting rather than an input to it, so the expected
code and message are stated in prose above and the input set is the same three
files as every other scenario.

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
   `dailytran.txt`: the amount at positions 133-143, moved from the seed's
   `0000005047G` (+504.77) to `0000020650A` (+2065.01). Measured against the seed
   that is six bytes, positions 138-143; measured against
   `boundary_exact_limit/dailytran.txt` it is one byte, position 143.

---

## 5. Why the moving byte is the last one

Assumptions: the sign of a zoned-decimal field is carried in its **low-order**
digit, so the byte that distinguishes this file from its pass counterpart is the
last of the eleven and not the first. `boundary_exact_limit` ends `...0650{` and
this file ends `...0650A`; under the overpunch table in master section 3.4 the
`{` is the positive digit 0 and the `A` is the positive digit 1, which is the one
cent. An author expecting a leading minus sign or a decimal point will find
neither -- master section 3.5 records that the implied decimal occupies no byte.

Trade-offs: that one-byte difference is invisible in a terminal, because `{` and
`A` are both single printable characters and the two lines are the same length.
Reviewing this pair by eye is unreliable by construction; the byte-length and
carriage-return sweeps in folder section 10 are the check. This is stated because
the cost of the pair being silently broken is high -- the two scenarios would
both post, both assertions would need to change, and the inclusive boundary would
stop being covered at all.

Alternatives Considered: making the step larger -- one dollar rather than one cent
-- was rejected. The comparison on line 407 is on an exact fixed-point value, and
folder section 4.2 records that money in these masters is zoned decimal rather
than floating point, so the smallest representable step is exactly what proves
the comparison is not tolerant. A larger step would pass equally well against an
implementation that rounded to dollars, which is precisely the defect the scale-2
contract exists to prevent.
