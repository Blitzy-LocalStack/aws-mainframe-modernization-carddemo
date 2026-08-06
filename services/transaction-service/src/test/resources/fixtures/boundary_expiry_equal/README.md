# boundary_expiry_equal -- a transaction dated exactly on the expiration date

Scenario README for `fixtures/boundary_expiry_equal/`. The reasoning that is
identical across all ten scenarios lives in the folder index at
[`../README.md`](../README.md) and is cited here by section number rather than
copied; the byte-encoding contract lives in
[`tests/fixtures/README.md`](../../../../../../../tests/fixtures/README.md) and is
cited by section number and never restated.

---

## 1. Intent

This is the **pass** member of the one-day pair described in folder section 1.1.
It exists to prove that the expiration comparison is inclusive: a transaction
dated exactly on the account's expiration date posts rather than rejecting.

Its counterpart is `reject_103_expired`, and the two `dailytran.txt` files differ
in **exactly one byte** -- position 288, the tenth and last byte of the date,
measured.

---

## 2. The rule it exercises

The expiration gate in
[`app/cbl/CBTRN02C.cbl`](../../../../../../../app/cbl/CBTRN02C.cbl), on its pass
arm:

```text
IF ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS (1:10)
   CONTINUE
ELSE
   MOVE 103 TO WS-VALIDATION-FAIL-REASON
   ...
END-IF
```

That is line 414, with `CONTINUE` on line 415. The field name is the baseline
spelling `ACCT-EXPIRAION-DATE` and is quoted exactly as declared, because the
declared name is what the program compiles against; folder section 1.1 records the
same.

The expiration date comes from seed row 7 of
[`app/data/ASCII/acctdata.txt`](../../../../../../../app/data/ASCII/acctdata.txt),
positions 59-68, which is `2024-12-13`. This record's originating timestamp
therefore begins `2024-12-13`, so the comparison is `2024-12-13 >= 2024-12-13` and
takes the pass arm.

Note the shape of the right-hand side. `DALYTRAN-ORIG-TS (1:10)` is a
reference-modified **character** range, not a date value, compared against a
10-byte alphanumeric field. The comparison is therefore a character comparison,
and it agrees with a date comparison only because the form is ISO-ordered --
year, then month, then day, each zero-padded.

---

## 3. Expected outcome

- The record posts. No row is written to `ledger.transaction_rejects`, and in
  particular no row carrying reason code 103.
- The reject count stays at zero, so line 229 is false and the return code is left
  at zero rather than moved to 4 on line 230.
- The credit-limit gate on line 407 also passes: the amount is unchanged from the
  template at +504.77 against a credit limit of +2065.00. That matters more than it
  first appears -- see section 5.

---

## 4. Fixture bytes and governance

### 4.1 Files, widths, record counts, line endings

| File | Table it loads | Records | Bytes per record | Bytes on disk | Line ending |
|---|---|---|---|---|---|
| `dailytran.txt` | `ledger.daily_transactions` | 1 | 350 | 351 | LF, one trailing newline |
| `transact.txt` | `ledger.transactions` | 1 | 350 | 351 | LF, one trailing newline |
| `tcatbal.txt` | `ledger.transaction_category_balances` | 1 | 50 | 51 | LF, one trailing newline |

Folder section 3.3 is the authority for LF being the only line ending here,
including in `tcatbal.txt`, whose seed ships CRLF.

### 4.2 Provenance attestation

Folder section 7 mandates the three items below.

1. **Synthetic and seed-derived.** `dailytran.txt` is record 1 of
   [`app/data/ASCII/dailytran.txt`](../../../../../../../app/data/ASCII/dailytran.txt)
   with one field reshaped, per item 3. `transact.txt` has **no seed row at all** --
   master section 4.2 item 7 records that `app/data/ASCII/` holds exactly nine files
   and no `transact.txt`, because the posted-transaction master is an *output* of
   `CBTRN02C` (line 562, writing at line 564) rather than an input -- so its bytes are
   authored from the [`app/cpy/CVTRA05Y.cpy`](../../../../../../../app/cpy/CVTRA05Y.cpy)
   layout and are synthetic in the strict sense. Its first 304 bytes are nonetheless
   taken from this scenario's own `dailytran.txt`, for the reason given in item 3.
   `tcatbal.txt` is byte-identical to its `happy_path` counterpart, whose provenance
   is recorded in [`../happy_path/README.md`](../happy_path/README.md) section 4.2.
2. **No real person and no real account.** Every account number, card number,
   name and address byte here comes from the published CardDemo demonstration
   seed. They are demonstration values, not credentials, and they identify no real
   person and no real account.
3. **Which bytes were reshaped away from the seed value.** One field in
   `dailytran.txt`: the date inside the originating timestamp at positions 279-304,
   moved from the seed's `2022-06-10` to `2024-12-13`. Four bytes change --
   positions 282, 284, 285 and 288, measured. The time-of-day tail
   ` 19:27:53.000000` at positions 289-304 is carried across from the seed
   unchanged. In `transact.txt` the same date carries into **two** fields, so eight
   bytes differ from the `happy_path` template: positions 282, 284, 285 and 288 in
   the originating timestamp, and 308, 310, 311 and 314 in the processing timestamp
   -- all measured, and all inside those two windows and nowhere else. Positions
   1-304 of `transact.txt` are byte-for-byte identical to positions 1-304 of this
   scenario's `dailytran.txt`, which is what lines 425-436 of
   [`app/cbl/CBTRN02C.cbl`](../../../../../../../app/cbl/CBTRN02C.cbl) produce: twelve
   one-to-one `MOVE` statements from `DALYTRAN-*` to `TRAN-*` covering exactly those
   304 bytes. The processing timestamp at 305-330 is the one field the program does
   not copy -- lines 437-438 supply it separately -- and section 5 records the value
   chosen for it.

---

## 5. Four decisions about this record

Assumptions: the date must stay ISO-ordered. Because line 414 compares characters
rather than dates, as section 2 sets out, rewriting the date in any other order --
`12/13/2024`, say -- would not merely reformat the fixture, it would invert the
comparison for most inputs while still looking like a date to a reader. The
ISO order is what makes the character compare agree with the date compare, and it
is also why folder section 6 calls the originating timestamp load-bearing and
requires it to be a literal.

Refactoring Rationale: only the first ten bytes participate in the comparison, so
the time-of-day tail was left at the seed's ` 19:27:53.000000` rather than being
normalised to midnight. Changing it would add a second difference between this
file and the template without adding a property, and folder section 1.1 requires a
pair to agree byte for byte except in the single field that moves. Leaving it also
keeps the 26-character shape master section 6.3 and folder section 2.2 describe --
a space at character 11 and a dot at character 20 -- visible in the fixture rather
than only in the copybook.

Trade-offs: the amount is deliberately left inside the credit limit even though
this scenario is about dates. Lines 407-413 and 414-421 are two **independent**
`IF` statements executed in sequence, not two arms of one choice, so a record that
failed both would have reason 102 assigned on line 410 and then overwritten by 103
on line 417 -- the last assignment winning. A fixture that moved both fields would
therefore still produce exactly one reject code, and which one it produced would
depend on statement order rather than on anything the scenario meant to assert. One
field moves; the other stays.

Alternatives Considered: the processing timestamp in `transact.txt` at positions
305-330 is `2024-12-13 00:00:00.000000`. The alternative was to carry the
`happy_path` template's `2022-07-18 00:00:00.000000` across verbatim, which would
have kept that literal uniform across every scenario folder and left this file
byte-identical to the template. It was rejected because it would place the posting
run roughly two and a half years *before* the transaction it posts originated, and
that incoherence is not cosmetic here:
[`V1__ledger.sql`](../../../../main/resources/db/migration/V1__ledger.sql) declares the
non-unique `idx_transactions_proc_ts` over this column at lines 291-292 as a real
keyset access path -- the replacement for the alternate index whose key
`KEYS(26 304)` is declared at line 27 of
[`app/jcl/TRANIDX.jcl`](../../../../../../../app/jcl/TRANIDX.jcl), and whose 26 bytes
at offset 304 are precisely this field -- so a row whose processing stamp preceded
its own originating stamp would order nonsensically against its own business date.
Uniformity of an unrelated literal is worth less than a coherent posted row on an
indexed column. What the template actually establishes is the *form*, not the year:
an injected literal at midnight with six zero microseconds, never a clock read. That
form is preserved, and taking the date from the scenario's own boundary value keeps
the folder single-axis -- `2024-12-13` is the one value that moves, and both
timestamps follow from it.

Assumptions: this field cannot simply be left blank the way `dailytran.txt` leaves
it. `V1__ledger.sql` declares `ledger.transactions.proc_ts TIMESTAMP(6) NOT NULL` at
line 245 against `ledger.daily_transactions.proc_ts` nullable at line 397, and
folder section 3.2 records that asymmetry as the whole reason the two files exist
separately rather than being collapsed into one shared record.
