# happy_path -- the byte-shape template that clears both boundary gates

Scenario README for `fixtures/happy_path/`. The reasoning that is identical
across all ten scenarios lives in the folder index at
[`../README.md`](../README.md) and is cited here by section number rather than
copied; the byte-encoding contract -- record widths, position tables, the
zoned-decimal sign-overpunch table, line endings -- lives in
[`tests/fixtures/README.md`](../../../../../../../tests/fixtures/README.md) and
is cited by section number and never restated.

---

## 1. Intent

This is the template every other scenario is cut from. It holds one well-formed
daily-transaction record that passes both validation gates, so that each of the
other nine scenarios can be expressed as an edit to a single field of this file
and a byte-level diff between any two of them isolates exactly the field that
moved.

That role is the reason this scenario proves no boundary of its own. Its amount
sits far below the credit limit and its date far before the expiration date, so
it exercises the pass arm of both comparisons without being near either edge.

Assumptions: a template that already sat on a boundary could not serve as the
common ancestor of the two discriminator pairs in folder section 1.1, because the
pass member of each pair would then be the template itself and the pair would
lose the neutral third point that shows the field, and only the field, is what
moves the outcome.

---

## 2. The rule it exercises

Both gates in
[`app/cbl/CBTRN02C.cbl`](../../../../../../../app/cbl/CBTRN02C.cbl), taken on
their pass arms, plus the update arm of the category-balance fork.

| What is exercised | Where | Why this record takes that arm |
|---|---|---|
| credit-limit gate, pass arm | line 407 `IF ACCT-CREDIT-LIMIT >= WS-TEMP-BAL`, `CONTINUE` at line 408 | the amount is +504.77 against a credit limit of +2065.00 |
| expiration gate, pass arm | line 414 `IF ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS (1:10)`, `CONTINUE` at line 415 | the originating date is 2022-06-10 against an expiration date of 2024-12-13 |
| the posting branch | line 211 `IF WS-VALIDATION-FAIL-REASON = 0` reaching line 212 `PERFORM 2000-POST-TRANSACTION` | both gates left the reason at the zero set on line 208 |
| category-balance fork, update arm | the read at line 474 succeeds, so the flag stays at the `'N'` set on line 473 and the fork on line 495 performs `2700-B-UPDATE-TCATBAL-REC` on line 498, whose header is line 526 | `tcatbal.txt` carries a row on the exact key the paragraph composes at lines 469-471 |

The copybook field name on line 414 is the baseline spelling
`ACCT-EXPIRAION-DATE` and is quoted exactly as declared, because the declared
name is what the program compiles against.

The account values the two gates read are seed row 7 of
[`app/data/ASCII/acctdata.txt`](../../../../../../../app/data/ASCII/acctdata.txt),
which is the account that this record's card number resolves to through row 7 of
[`app/data/ASCII/cardxref.txt`](../../../../../../../app/data/ASCII/cardxref.txt):
credit limit `00000020650{` at positions 25-36, expiration date `2024-12-13` at
positions 59-68.

---

## 3. Expected outcome

- The record posts. No row is written to `ledger.transaction_rejects`.
- The existing category-balance row is updated in place rather than created; the
  create arm on line 496 is not taken.
- The reject count stays at zero, so the `IF WS-REJECT-COUNT > 0` test on line
  229 is false and the return code is left at zero rather than moved to 4 on line
  230.

---

## 4. Fixture bytes and governance

### 4.1 Files, widths, record counts, line endings

| File | Table it loads | Records | Bytes per record | Bytes on disk | Line ending |
|---|---|---|---|---|---|
| `dailytran.txt` | `ledger.daily_transactions` | 1 | 350 | 351 | LF, one trailing newline |
| `transact.txt` | `ledger.transactions` | 1 | 350 | 351 | LF, one trailing newline |
| `tcatbal.txt` | `ledger.transaction_category_balances` | 1 | 50 | 51 | LF, one trailing newline |

The one-byte difference between each record width and its size on disk is the
single trailing newline that master section 3.3 requires, which is what makes a
line count equal a record count. Folder section 3.3 is the authority for LF being
the only line ending here, including in `tcatbal.txt`.

The four tables these files load into are created by
[`V1__ledger.sql`](../../../../main/resources/db/migration/V1__ledger.sql). The
three files are three independent table images and not a before-and-after pair of
one transaction; folder section 3.1 is the authority for that, and it is why
`dailytran.txt` and `transact.txt` may carry the same transaction identifier
without asserting anything about posting order.

### 4.2 Provenance attestation

Folder section 7 mandates the three items below.

1. **Synthetic and seed-derived.** `dailytran.txt` is byte-identical to record 1
   of
   [`app/data/ASCII/dailytran.txt`](../../../../../../../app/data/ASCII/dailytran.txt),
   measured rather than asserted. `transact.txt` carries those same 350 bytes
   with only the processing timestamp filled, per item 3 below. `tcatbal.txt`
   derives from record 1 of
   [`app/data/ASCII/tcatbal.txt`](../../../../../../../app/data/ASCII/tcatbal.txt),
   seed key `00000000001` / `01` / `0001`.
2. **No real person and no real account.** Every account number, card number,
   customer name, merchant name and address byte here comes from the published
   CardDemo demonstration seed. They are demonstration values, not credentials,
   and they identify no real person and no real account.
3. **Which bytes were reshaped away from the seed value.** In `dailytran.txt`,
   none -- the record is the seed row unchanged. In `transact.txt`, the
   processing timestamp at positions 305-330 only: the seed carries 26 spaces
   there and this file carries the literal `2022-07-18 00:00:00.000000`. In
   `tcatbal.txt`, two fields: the account identifier at positions 1-11 moved from
   the seed's `00000000001` to `00000000007` so the row sits on the key this
   scenario's card resolves to, and the balance at positions 18-28 moved from
   `0000000000{` to `0000001000{`, which is +100.00.

Assumptions: the balance field is `TRAN-CAT-BAL PIC S9(09)V99` at positions 18-28,
so its 11 bytes `0000001000{` decode to exactly **+100.00** and to nothing else. The
derivation is mechanical under the two master rules: section 3.4 reads the trailing
`{` as a **positive-zero overpunch supplying the low-order digit as well as the
sign**, making the digit string `0000001000` + `0` = `00000010000`; section 3.5's
implied `V99` then takes the last two of those eleven digits as cents, splitting them
into `000000100` and `00`. The distinction is recorded because the field is easy to
misread by one decimal place: taking the leading ten characters as the whole value and
treating `{` as a bare sign understates the row tenfold, and `+10.00` would in fact be
the different byte string `0000000100{`.

Two independent measurements confirm that reading rather than restating it. The decode
is asserted executably by `categoryBalanceRecordRoundTripsByteIdentically` in
[`FixedWidthCodecTest`](../../../../../../common-lib/src/test/java/com/carddemo/common/codec/FixedWidthCodecTest.java),
which builds this exact 50-byte image and expects `new BigDecimal("100.00")`; and
[`tcatbal.expected`](../../../../../../../tests/golden/posting/happy_path/tcatbal.expected)
carries `0000006047G` in the same field, which decodes to 604.77 and equals this
balance plus the +504.77 `DALYTRAN-AMT` of the sibling `dailytran.txt` -- a +10.00
reading would instead require 514.77 there and does not match the byte. WHY this is
written down at all: 100.00 and 10.00 differ by a single character at position 24, the
balance is the only field distinguishing the two arms of the category-balance fork, and
a decode stated once in prose is the value every later reader trusts, so the derivation
is recorded beside the bytes instead of being left to be re-derived.

Assumptions: `transact.txt` needs a filled processing timestamp because
`ledger.transactions.proc_ts` is declared `NOT NULL` while
`ledger.daily_transactions.proc_ts` is nullable -- the asymmetry folder section
3.2 exists to make assertable. Folder section 4.2 item 7 records that
`app/data/ASCII/` holds no `transact.txt` at all, so those bytes are authored
from the `CVTRA05Y` layout rather than copied from a seed row; deriving them from
the daily record is sound because folder section 2 measures the two 350-byte
layouts as field-for-field identical in picture and order.

Assumptions: that layout identity licenses the same offsets in both records, but
what licenses copying the *values* is the program.
[`app/cbl/CBTRN02C.cbl`](../../../../../../../app/cbl/CBTRN02C.cbl) opens
`2000-POST-TRANSACTION` at line 424, and lines 425-436 are twelve consecutive
`MOVE DALYTRAN-x TO TRAN-x` statements -- identifier, type code, category code,
source, description, amount, merchant identifier, merchant name, merchant city,
merchant zip, card number, originating timestamp -- with nothing reformatted,
rounded or recomputed on the way across. Positions 1-304 here are therefore a
projection of that paragraph rather than a resemblance to a neighbouring file,
which is what makes byte equality with `dailytran.txt` over exactly that range
the correct assertion to write. Of the fourteen fields the program derives only
one: line 437 performs `Z-GET-DB2-FORMAT-TIMESTAMP` and line 438 moves its
result into `TRAN-PROC-TS`, which is why that single field is the only one this
file cannot inherit.

Assumptions: the date in that literal is traceable rather than invented.
[`app/jcl/INTCALC.jcl`](../../../../../../../app/jcl/INTCALC.jcl) line 22 reads
`EXEC PGM=CBACT04C,PARM='2022071800'`, and it is the only business-date
injection in the baseline batch chain --
[`app/jcl/POSTTRAN.jcl`](../../../../../../../app/jcl/POSTTRAN.jcl) invokes
`CBTRN02C` at its line 23 with no `PARM` at all, so posting supplies no date to
borrow. A zero time of day is the honest widening of a date parameter to a
`TIMESTAMP(6)`, and `.000000` follows the seed, where every originating
timestamp carries zero microseconds. The interior shape is the space-and-colon
form folder section 2.2 fixes from `CSDAT01Y`, not the dash-and-dot form
`CBTRN02C` spells for its own working field at lines 160-174; that one is DB2's
character representation of a timestamp, which PostgreSQL does not parse into
`TIMESTAMP(6)`.

Alternatives Considered: two other values for positions 305-330 were evaluated
and both rejected. The first was 26 spaces copied from
[`tranfile.expected`](../../../../../../../tests/golden/posting/happy_path/tranfile.expected),
which carries this same `CVTRA05Y` shape and does hold 26 blanks there --
measured, not assumed. Those blanks are a comparator artifact rather than a
value: master section 6.3 states the rule as masking only the runtime processing
timestamp in place, preserving every other byte, so that field is pinned blank on
input and masked on output while the originating timestamp is left alone.
Reusing them here loads nothing, because the `NOT NULL` above refuses that column
empty. It would also misread what that file is -- a comparison baseline for a
batch program, and nothing in this module is compared against it.
The second candidate was reusing the originating timestamp verbatim. It loads,
but it makes the two columns indistinguishable: a test that read `orig_ts` where
it meant `proc_ts` would pass, and the non-unique `idx_transactions_proc_ts`
ordering would be exercised as though it were originating order. The literal
chosen sits more than a month after the originating timestamp, so the two values
are independently observable and neither can stand in for the other.

Trade-offs: positions 331-350 hold 20 spaces, matching `dailytran.txt`. The same
`tranfile.expected` holds 20 `0x00` bytes there instead -- also measured -- and
that is a record-area artifact rather than a value, because a compiled program
never writes into this `FILLER` and leaves those bytes at the low values its
record area was initialised to. Spaces are chosen at the cost of differing from a
program-written record in every one of those 20 positions, and they buy a file
that is wholly 7-bit ASCII and so can be diffed, grepped and read in a terminal;
a run of 20 low values is invisible in precisely the tools a reviewer uses, and
folder section 10 gates this tree on printable ASCII for that reason. Nothing
downstream can observe the difference either way:
[`V1__ledger.sql`](../../../../main/resources/db/migration/V1__ledger.sql) maps
this `FILLER` to no column and records that omission at its lines 109-113, so
these bytes exist only to reach the declared record length of 350.

Trade-offs: the balance moved to +100.00 rather than being left at the seed's
+0.00 so that this scenario's category row is unmistakably present and non-zero,
which keeps it distinguishable at a glance from the deliberately absent row in
`zero_balance`. A reader comparing the two scenarios sees a populated row against
an empty file rather than two rows that differ only in a sign-overpunch byte. The
non-zero choice also earns its keep in the arithmetic: because
`2700-B-UPDATE-TCATBAL-REC` adds the transaction amount to the balance it read,
a non-zero starting value makes the update result 604.77 -- provably distinct
from the 504.77 a create-from-zero would produce -- so a consuming test cannot
pass while silently taking the create arm. A zero starting balance would have
left the two arms indistinguishable by result, which is the failure this byte
exists to prevent.

---

## 5. Why this scenario ships an unmodified seed record

Alternatives Considered: minting a fresh template -- a round amount, a simple
card number, a recent date -- was evaluated and rejected. A minted record would
carry no provenance, so the attestation in section 4.2 would have to assert its
synthetic origin without any file to point at, and every value in it would be
unverifiable by construction. Copying the seed row makes the whole of item 1
above a measurement.

Refactoring Rationale: the seed row also happens to carry three of the five facts
folder section 4.2 forbids normalising, so using it verbatim preserves them for
free rather than requiring them to be reinstated deliberately: the signed amount
`0000005047G`, whose trailing `G` is the positive low-order digit 7 under master
section 3.4 and makes the value +504.77; the 26-space processing timestamp; and
the exact 26-character originating timestamp shape with a space at character 11
and a dot at character 20.

Trade-offs: because this file is the ancestor of the other nine, an edit here
propagates to every diff in the folder. The two discriminator pairs in folder
section 1.1 would still hold -- each pair is internally consistent -- but the
measured byte offsets quoted in the other nine READMEs are stated against this
file and would need re-measuring. That cost is accepted in exchange for a single
template; the alternative, nine independently authored records, would let the
pairs drift apart silently.
