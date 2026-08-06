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
   with one field reshaped, per item 3; the seed key is dailytran record 1.
   `transact.txt` has **no seed row at all** -- folder section 4.2 item 7 records
   that `app/data/ASCII/` holds exactly nine files and no `transact.txt`, because
   the posted-transaction master is an *output* of `CBTRN02C` (line 562, writing at
   line 564) rather than an input -- so its bytes are authored from the
   [`app/cpy/CVTRA05Y.cpy`](../../../../../../../app/cpy/CVTRA05Y.cpy) layout and
   are synthetic in the strict sense. Its first 304 bytes are nonetheless taken from
   this scenario's own `dailytran.txt`, including the reshaped amount, for the reason
   given in item 3. `tcatbal.txt` is byte-identical to **record 7** of
   [`app/data/ASCII/tcatbal.txt`](../../../../../../../app/data/ASCII/tcatbal.txt)
   with that record's CRLF normalised to LF per folder section 3.3, and with no
   field reshaped at all -- record 7 being the seed row whose `TRANCAT-ACCT-ID` is
   `00000000007`, the account this scenario's card resolves to in section 2. Both
   facts were measured: stripping the carriage return from seed record 7 yields this
   file byte for byte, and the balance field of all fifty seed rows reads
   `0000000000{`, so `+0.00` is the seed's own value here rather than a value chosen
   for this scenario.

   Assumptions: neither of these two files is the `happy_path` template any longer,
   and the distinction matters because an earlier state of this folder described each
   of them that way. `transact.txt` diverged when the reshaped amount was carried
   into the posted image (item 3), and `tcatbal.txt` diverged when it was re-derived
   from seed record 7 so that its key matches the account this scenario's card
   resolves to. Both divergences are measured against `happy_path` rather than
   asserted: the two files differ from their counterparts there, so a reader who
   copies either from `happy_path` reintroduces a defect that section 6 exists to
   prevent.
2. **No real person and no real account.** Every account number, card number,
   name and address byte here comes from the published CardDemo demonstration
   seed. They are demonstration values, not credentials, and they identify no real
   person and no real account.
3. **Which bytes were reshaped away from the seed value.** One field in
   `dailytran.txt`: the amount at positions 133-143, moved from the seed's
   `0000005047G` (+504.77) to `0000020650{` (+2065.00). Six bytes change,
   positions 138 through 143, measured. Nothing else in the record moves. That same
   amount carries into `transact.txt`, which therefore differs from the
   `happy_path` template in exactly those six positions and nowhere else, also
   measured. Positions 1-304 of `transact.txt` are byte-for-byte identical to
   positions 1-304 of this scenario's `dailytran.txt`, which is what lines 425-436 of
   [`app/cbl/CBTRN02C.cbl`](../../../../../../../app/cbl/CBTRN02C.cbl) produce:
   twelve one-to-one `MOVE` statements from `DALYTRAN-*` to `TRAN-*`, the sixth of
   them `MOVE DALYTRAN-AMT TO TRAN-AMT` at line 430, covering exactly those 304
   bytes with nothing reformatted, rounded or recomputed on the way across. The
   processing timestamp at 305-330 is the one field the program does not copy --
   lines 437-438 supply it separately, and line 437's
   `Z-GET-DB2-FORMAT-TIMESTAMP` is a clock read, which is why a fixture pins a
   literal rather than reproducing a runtime value -- and it holds
   `2022-07-18 00:00:00.000000`. That span is **filled** here while this scenario's
   `dailytran.txt` leaves it as 26 spaces, because
   [`V1__ledger.sql`](../../../../main/resources/db/migration/V1__ledger.sql)
   declares `transactions.proc_ts` `NOT NULL` against a nullable
   `daily_transactions.proc_ts`; that asymmetry is folder section 3.2, and folder
   section 6 fixes this one literal for every `transact.txt` in the tree so that ten
   scenarios do not each invent a stamp. The literal's own provenance is
   [`app/jcl/INTCALC.jcl`](../../../../../../../app/jcl/INTCALC.jcl) line 22,
   `PARM='2022071800'`, the only business-date injection in the baseline batch
   chain; that derivation and the two rejected alternatives for this span belong to
   [`../happy_path/README.md`](../happy_path/README.md) section 4.2 and are not
   restated here. Positions 331-350 are the 20-space `FILLER`, matching
   `dailytran.txt`. **`tcatbal.txt` has no reshaped bytes at all**, per item 1 -- it
   is seed record 7 verbatim, so the answer this item owes for that file is "none"
   rather than a list, and section 6 records why the seed value is the right one to
   keep.

Assumptions: the amount is the one field this scenario turns on, so carrying it into
the posted image is a correctness requirement rather than a tidiness one. Leaving
`transact.txt` on the template's +504.77 would describe a posted row that the gate at
line 407 never produced from this input, and the pass arm this folder exists to
demonstrate would be asserted against a record 1560.23 lighter than the one that
drove it. The pass scenarios `happy_path`, `boundary_expiry_equal` and `zero_balance`
each relate their two 350-byte records the same way -- differing only inside 305-330,
measured -- so this file follows the folder's established shape rather than
introducing one. Trade-offs: the consequence is that the amount now lives in two
files per scenario and an author moving one must move the other, which folder section
3.2 already accepts as the standing cost of Decision B.

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

---

## 6. The two decisions carried by `tcatbal.txt`

This file is fifty bytes of positional data with no comment construct, so master
section 9.1 makes this README the carrier for its reasoning. Two of its choices
are ones a reasonable author could have made differently, and both are recorded
here rather than left to be inferred from the bytes. The third choice a reader
will ask about -- LF line endings, when the `tcatbal` seed is one of the three
master section 3.2 names as shipping CRLF -- is a folder-wide decision and is
recorded once at folder section 3.3, which states the carriage-return mechanism
and the measurement behind it. It is deliberately not restated here.

### 6.1 Why the balance is `+0.00` rather than the template's `+100.00`

The balance field at positions 18-28 reads `0000000000{`, which is `+0.00` under
master section 3.4. The `happy_path` template reads `0000001000{` at the same
positions, which is `+100.00`, so the two files differ at exactly one byte,
position 24 -- measured, and the only position at which they differ.

Assumptions: `+0.00` is what makes this scenario's arithmetic have a single
term. Section 2 establishes that both of the account's cycle totals are zero, so
the trial balance computed at lines 403-405 of
[`app/cbl/CBTRN02C.cbl`](../../../../../../../app/cbl/CBTRN02C.cbl) reduces to
`DALYTRAN-AMT` alone and the comparison on line 407 is directly against this
scenario's `+2065.00`. Holding the category balance at zero as well means the
figure this scenario posts into that row is provably `0.00 + 2065.00`, with no
second contributing term a reader has to carry. The category balance is not an
input to the credit-limit gate, so a non-zero value here would not change the
pass/reject outcome -- which is precisely why leaving it unexplained would be a
trap: a later author could move it believing it to be inert and would silently
change the posted total this scenario asserts.

Alternatives Considered: carrying the template's `+100.00`, so that every
populated scenario in the folder shares one prior balance and a consumer
asserting balance arithmetic has one value to remember rather than two. That was
rejected on provenance grounds. Section 4.2 item 1 records that all fifty rows of
the seed carry `+0.00` and that this file is seed record 7 verbatim, so `+100.00`
is the reshaped value and `+0.00` is the unreshaped one; the reference tree agrees,
carrying `+0.00` at
[`tests/fixtures/posting/boundary_exact_limit/tcatbal.txt`](../../../../../../../tests/fixtures/posting/boundary_exact_limit/tcatbal.txt).
Trade-offs: the folder therefore holds two different category balances rather than
one, and that uniformity is given up on purpose -- agreeing with the seed and with
the reference tree is the stronger property, because that tree is the oracle this
migration is verified against, whereas a shared constant is only a convenience.

### 6.2 Why a key-matching row is present at all

The first three fields form `TRAN-CAT-KEY` at positions 1-17, reading
`00000000007` then `01` then `0001`. Assumptions: the second and third of those are
the type code and category code the sibling `dailytran.txt` record carries at
positions 17-18 and 19-22, which read `01` and `0001`, measured. The first is the
account the program composes the key from, which does not appear in the input
record at all -- it arrives as `XREF-ACCT-ID` from the cross-reference read, and
section 2 traces that chain from this record's card number to account
`00000000007`. All three therefore agree and the composed key **resolves**, which
is what sends the dispatch at lines 495-499 of the same program down its `ELSE`
arm into `2700-B-UPDATE-TCATBAL-REC` at line 526, rather than into
`2700-A-CREATE-TCATBAL-REC` at line 503.

Alternatives Considered: shipping no `tcatbal.txt`, or shipping an empty one. Both
were rejected because either would resolve to no row and would silently move this
scenario onto the create arm -- still posting, still passing, but exercising the
credit-limit boundary in a different configuration than the one this README
claims. Folder section 1 assigns the create arm to `zero_balance`, whose empty
`tcatbal.txt` is its discriminating property, so expressing it here as well would
duplicate that coverage while quietly removing the update-arm coverage this
scenario contributes. The consequence worth stating plainly: this row is not
scenery. Deleting it or drifting any of its seventeen key bytes changes which
branch runs.
