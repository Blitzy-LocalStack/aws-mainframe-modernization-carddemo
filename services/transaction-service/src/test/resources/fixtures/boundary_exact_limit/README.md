# boundary_exact_limit -- an amount landing exactly on the credit limit

Scenario README for `fixtures/boundary_exact_limit/`. The reasoning that is
identical across all ten scenarios lives in the folder index at
[`../README.md`](../README.md) and is cited here by section number rather than
copied; the byte-encoding contract lives in
[`tests/fixtures/README.md`](../../../../../../../tests/fixtures/README.md) and is
cited by section number and never restated.

---

## 1. Intent

This is the **pass** member of the one-cent pair described in folder section 1.1.
It exists to prove that the credit-limit comparison is inclusive: an amount that
lands exactly on the limit posts rather than rejecting.

Its counterpart is `reject_102_overlimit`, and the two files differ in **exactly
one byte** -- position 143, measured. That single-byte separation is the whole
value of the pair: no other field can be blamed for the difference in outcome.

---

## 2. The rule it exercises

The credit-limit gate in
[`app/cbl/CBTRN02C.cbl`](../../../../../../../app/cbl/CBTRN02C.cbl), on its pass
arm, and the arithmetic that feeds it.

Lines 403-405 compute the value being compared:

```text
COMPUTE WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT
                    - ACCT-CURR-CYC-DEBIT
                    + DALYTRAN-AMT
```

Line 407 then tests `IF ACCT-CREDIT-LIMIT >= WS-TEMP-BAL` and takes `CONTINUE` on
line 408 when the limit is greater than or equal, so equality passes.

The reason the fixture amount can be set to the limit directly is measurable
rather than assumed. The account this record's card resolves to is
`00000000007`, seed row 7 of
[`app/data/ASCII/acctdata.txt`](../../../../../../../app/data/ASCII/acctdata.txt),
and that row carries both cycle totals at zero: `ACCT-CURR-CYC-CREDIT` at
positions 79-90 is `00000000000{` and `ACCT-CURR-CYC-DEBIT` at positions 91-102
is `00000000000{`, both being +0.00 under master section 3.4. With both terms
zero, `WS-TEMP-BAL` reduces to `DALYTRAN-AMT` exactly, so the comparison on line
407 is directly against the amount in this file.

The credit limit on that same row is `00000020650{` at positions 25-36, which is
**+2065.00**. This fixture's amount is therefore `0000020650{` -- the same value
in the 11-byte field of the transaction layout.

---

## 3. Expected outcome

- The record posts. No row is written to `ledger.transaction_rejects`, and in
  particular no row carrying reason code 102.
- The reject count stays at zero, so line 229 is false and the return code is
  left at zero rather than moved to 4 on line 230.
- The expiration gate on line 414 also passes: the originating date is unchanged
  from the template at 2022-06-10, well inside the 2024-12-13 expiration date.
  That is deliberate -- a record that failed the second gate would still reject,
  and the pass outcome asserted here would be unreachable.

---

## 4. Fixture bytes and governance

### 4.1 Files, widths, record counts, line endings

| File | Table it loads | Records | Bytes per record | Bytes on disk | Line ending |
|---|---|---|---|---|---|
| `dailytran.txt` | `ledger.daily_transactions` | 1 | 350 | 351 | LF, one trailing newline |
| `transact.txt` | `ledger.transactions` | 1 | 350 | 351 | LF, one trailing newline |
| `tcatbal.txt` | `ledger.transaction_category_balances` | 1 | 50 | 51 | LF, one trailing newline |

The extra byte on disk in each row is the single trailing newline master section
3.3 requires. Folder section 3.3 is the authority for LF being the only line
ending here.

### 4.2 Provenance attestation

Folder section 7 mandates the three items below.

1. **Synthetic and seed-derived.** `dailytran.txt` is record 1 of
   [`app/data/ASCII/dailytran.txt`](../../../../../../../app/data/ASCII/dailytran.txt)
   with one field reshaped, per item 3. `transact.txt` and `tcatbal.txt` are
   byte-identical to their `happy_path` counterparts, whose provenance is recorded
   in [`../happy_path/README.md`](../happy_path/README.md) section 4.2; the seed
   keys are dailytran record 1 and tcatbal record 1.
2. **No real person and no real account.** Every account number, card number,
   name and address byte here comes from the published CardDemo demonstration
   seed. They are demonstration values, not credentials, and they identify no real
   person and no real account.
3. **Which bytes were reshaped away from the seed value.** One field in
   `dailytran.txt`: the amount at positions 133-143, moved from the seed's
   `0000005047G` (+504.77) to `0000020650{` (+2065.00). Six bytes change,
   positions 138 through 143, measured. Nothing else in the record moves. The
   reshaping in the other two files is the same as `happy_path` and is not
   repeated here.

Assumptions: the trailing `{` is not a brace and not decoration -- it is the
positive low-order digit zero of the zoned-decimal overpunch table in master
section 3.4, which is the single home of that table and the reason it is not
reproduced here. Writing the amount as `00000206500` would drop the sign
representation entirely and stop the field being a signed value.

---

## 5. Why the boundary is expressed in the amount rather than in the limit

Alternatives Considered: the same boundary could be expressed the other way
round -- leave the amount at the template's +504.77 and lower the credit limit to
match it. That was rejected on ownership grounds. The credit limit lives in the
account record, and folder section 3.1 records that this folder ships no account
image at all, because `ledger` is the only schema this module owns and the account
record belongs to the `account` schema owned by another module. Moving the limit
would require placing another bounded context's record inside this module's test
data, and would leave two modules free to disagree about the same bytes.

Trade-offs: the consequence is that the value `0000020650{` is hard-wired to seed
account row 7 by way of the card number in this record. If a future fixture points
the card number at a different account, this amount silently stops being the
boundary and the scenario keeps passing while proving nothing. The derivation is
therefore written out in section 2 above -- card number to cross-reference row 7,
to account `00000000007`, to positions 25-36 of seed row 7 -- so that the next
author can re-verify it in three steps instead of re-deriving it.

Assumptions: whichever test consumes this scenario seeds the account tier with
account `00000000007` carrying that same credit limit and both cycle totals at
zero. If it seeds a different limit, the record posts or rejects for a reason that
has nothing to do with the bytes in this folder, and the failure will look like a
fixture defect when it is a seeding difference.
