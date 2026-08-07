# reject_102_overlimit -- one cent past the credit limit

Scenario README for `fixtures/reject_102_overlimit/`, and the only file in this
folder able to hold prose. The three record files beside it are fixed-width
positional data with no comment construct, and a single added byte would break
both the declared record width and the `wc -l` equals record-count invariant, so
the reasoning behind their bytes has nowhere else to live. This document carries
it for all four files, which is what makes the folder conforming rather than
merely populated.

Reasoning shared by all ten scenarios lives in the folder index at
[`../README.md`](../README.md), and the byte-encoding contract lives in the
master at
[`tests/fixtures/README.md`](../../../../../../../tests/fixtures/README.md).
Both are cited below by section number and neither is restated: a second copy of
a convention is a second thing free to drift out of agreement with the first, and
a reader who found the two disagreeing would have no way to tell which one the
loader implements. In particular the sign-overpunch table and the implied-decimal
rule are cited and never reproduced.

Sections 1 to 4 supply the four items master section 9.1 requires of every
scenario README. Sections 5 to 8 carry the per-decision rationale for the bytes.
Section 9 records what reads them. Section 10 records how this document
discharges Rule 1.

Three record files sit beside this one, and each has a distinct job:

- **`dailytran.txt`** -- the unposted feed record, one 350-byte
  `DALYTRAN-RECORD`. It is the record the posting rule reads, and its amount
  field is the byte this whole scenario turns on. It depends on the account it
  resolves to through its card number, which is deliberately not a file here
  (section 4.2), and it loads into `ledger.daily_transactions`.
- **`transact.txt`** -- one 350-byte `TRAN-RECORD` already on the ledger before
  the run. Because this scenario rejects, it is emphatically not the posted image
  of the feed record beside it, and section 6 sets out what its bytes are and what
  they do and do not let a test assert. It loads into `ledger.transactions`.
- **`tcatbal.txt`** -- the prior category balance, one 50-byte
  `TRAN-CAT-BAL-RECORD`. It is the row that must survive the run unchanged, and
  its non-zero value is what makes an accidental mutation visible (section 7). It
  loads into `ledger.transaction_category_balances`.

---

## 1. Intent

This is the **reject** member of the one-cent pair described at folder section
1.1. Its whole reason for existing is to prove the **exclusive side of an
inclusive comparison**: an amount one cent beyond the credit limit rejects with
reason **102** and the message `OVERLIMIT TRANSACTION`.

The sibling [`boundary_exact_limit`](../boundary_exact_limit/README.md) proves
the other side, that an amount landing exactly on the limit posts. Neither folder
pins the comparison operator on its own. A single fixture that rejects is
consistent with `>=` and with `>` alike; a single fixture that posts is likewise
consistent with both. Only the pair, straddling the limit by the smallest step
the field can express, distinguishes them. That is why this scenario is described
throughout as one half of a mechanism rather than as a test of its own.

### 1.1 The one-cent pair, byte for byte

Folder section 1.1 reports the pair's agreement as a measurement, and it
reproduces here: the two `dailytran.txt` records are **350 bytes each and differ
at exactly one 1-based position, 143**. All 349 remaining bytes are identical.
The differing position is the amount field's low-order byte.

| Folder | `DALYTRAN-AMT` bytes at 133-143 | Decodes to | Trial balance | `limit >= trial balance?` | Result |
|---|---|---|---|---|---|
| `boundary_exact_limit` (sibling) | `0000020650{` | +2065.00 | 2065.00 | TRUE | POST |
| `reject_102_overlimit` (this folder) | `0000020650A` | +2065.01 | 2065.01 | FALSE | reject 102 |

The column heading names the predicate in the direction the reference evaluates
it: [`app/cbl/CBTRN02C.cbl`](../../../../../../../app/cbl/CBTRN02C.cbl) line 407
reads `IF ACCT-CREDIT-LIMIT >= WS-TEMP-BAL`, so the limit is the left operand and
the trial balance is the right one. Read in the other direction the TRUE and
FALSE in the table invert, and the one comparison this pair exists to pin down
would be described backwards.

The single byte is legible only through the overpunch. The ten leading characters
`0000020650` are identical in both records. The eleventh carries both the
low-order digit and the sign: `{` is the positive low-order digit **0**, giving
the digit string `00000206500`, and `A` is the positive low-order digit **1**,
giving `00000206501`. With the two implied decimal places those are +2065.00 and
+2065.01, a difference of exactly one cent. The overpunch table itself is master
section 3.4 and the implied decimal is master section 3.5; neither appears here,
because a decoding table copied into ten scenario documents is a table obliged to
agree with itself in ten places.

Assumptions: the pair's one-byte agreement is a property this fixture depends on
rather than a coincidence worth noting, and it was measured on this branch rather
than intended -- `cmp` between the two `dailytran.txt` files reports a single
differing position, 143, and nothing else. Any further divergence destroys the
discriminator outright. If the transaction type code, the category, the source,
the card number, the originating timestamp or the trailing `FILLER` also differed
between the two records, a failure in either scenario would no longer be
attributable to the comparison operator, because any of those fields could have
caused it. The pair would still contain one posting record and one rejecting
record and would still look like a boundary test, while proving nothing about the
boundary. A reader can re-run the comparison; a reader cannot re-run an
intention, which is why the agreement is recorded as a measurement with its
position.

Trade-offs: the consequence accepted here is that the discriminating byte is
invisible in a terminal. Both `{` and `A` are single printable characters and both
records are the same length, so reviewing the pair by eye is unreliable by
construction rather than by carelessness. The byte-length and carriage-return
sweeps at folder section 10 are the check that works. This brittleness is not an
unfortunate side effect of the design -- it is the design. A difference large
enough to see is a difference large enough to reject under either operator, and
the moment the pair is legible at a glance it has stopped testing anything.

---

## 2. The rule it exercises

`CBTRN02C` reject 102, on its reject arm: the `>=` credit-limit boundary inside
paragraph `1500-B-LOOKUP-ACCT` of
[`app/cbl/CBTRN02C.cbl`](../../../../../../../app/cbl/CBTRN02C.cbl). Master
section 6.1 is the authority for the rule as specification; the citations below
locate it in the source and were each verified by reading that file.

### 2.1 The gate ordering that makes reason 102 reachable at all

Reason 102 is not the first thing the program can conclude, and the ordering is
part of the scenario rather than background. `1500-VALIDATE-TRAN` at line 370
performs the cross-reference lookup first, then gates the account lookup on the
reason still being zero at lines 372 and 373. The cross-reference lookup is
`1500-A-LOOKUP-XREF` at line 380, which sets reason 100 on its `INVALID KEY`
branch. The account read is `1500-B-LOOKUP-ACCT` at line 393, whose `READ` is at
line 395 and which sets reason 101 on its own `INVALID KEY` branch.

The credit-limit arithmetic and both boundary tests sit on the `NOT INVALID KEY`
leg of that read, which begins at line 400 and closes with the `END-READ` at line
421. So reason 102 is reachable only after the card has resolved through the
cross-reference **and** the account it names has been read successfully.

Assumptions: that ordering is why this record carries the resolvable seed card
`4859452612877065` rather than a value chosen to look distinctive. The alternative
-- an unresolvable card, as `reject_100_card_missing` uses deliberately -- would
set reason 100 at the earlier gate and never reach line 407 at all, so the folder
would be named for one reason while encoding its predecessor. The card is
resolvable because the seed cross-reference maps it to account `00000000007`,
which folder section 1.3 records as measured, and that mapping is state a consuming
test establishes rather than bytes this folder ships.

### 2.2 The comparison, and the arithmetic plugged in

Lines 403 to 405 form the trial balance across three continuation lines:

```text
COMPUTE WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT
                    - ACCT-CURR-CYC-DEBIT
                    + DALYTRAN-AMT
```

Line 407 then tests it, and lines 408 to 413 are the two arms:

```text
IF ACCT-CREDIT-LIMIT >= WS-TEMP-BAL
  CONTINUE
ELSE
  MOVE 102 TO WS-VALIDATION-FAIL-REASON
  MOVE 'OVERLIMIT TRANSACTION'
    TO WS-VALIDATION-FAIL-REASON-DESC
END-IF
```

The comparison at line 407 is **inclusive**. The pass arm is the bare `CONTINUE`
at line 408; the `ELSE` is at line 409, the reason code is moved at line 410, the
message literal `OVERLIMIT TRANSACTION` at line 411, its move target at line 412,
and the `END-IF` at line 413.

Master section 6.1 records at its lines 560 to 566 the fixture-tuning assumption
this scenario rests on: in the seeds `ACCT-CURR-CYC-CREDIT` and
`ACCT-CURR-CYC-DEBIT` are both zero, so the trial balance reduces to the
transaction amount alone, and the boundary fixtures therefore set the amount
relative to the chosen account's credit limit -- equal to it for
`boundary_exact_limit`, and the limit plus `0.01` here. Section 4.2 records the
two cycle totals as measured for this scenario's account. Plugged in:

```text
WS-TEMP-BAL = 0.00 - 0.00 + 2065.01 = 2065.01
2065.00 >= 2065.01   ->   FALSE
```

Control therefore takes the `ELSE` at line 409 and reason 102 is set at line 410.
One cent lower and the same expression yields `2065.00 >= 2065.00`, which is TRUE
on an inclusive comparison, and the sibling posts.

Two independent statements of the same rule corroborate this outside the source.
[`tests/README.md`](../../../../../../../tests/README.md) carries the reason-102
row of its reject-reason table at line 565, giving the message text verbatim, and
states the boundary in prose at its lines 570 and 571: a balance exactly at the
credit limit must post, and one cent over must reject with reason 102.

### 2.3 Reason 103 is evaluated next, and it passes

This is the part of the flow most easily written down wrongly, so it is stated
precisely. The `END-IF` at line 413 closes the 102 test, and line 414
**immediately evaluates** the expiration gate on that same `NOT INVALID KEY` leg,
inside the same `END-READ` at line 421:

```text
IF ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS (1:10)
  CONTINUE
ELSE
  MOVE 103 TO WS-VALIDATION-FAIL-REASON
  MOVE 'TRANSACTION RECEIVED AFTER ACCT EXPIRATION'
    TO WS-VALIDATION-FAIL-REASON-DESC
END-IF
```

Both tests move into the **same** `WS-VALIDATION-FAIL-REASON` field, declared at
line 181. The two are sequential, not exclusive: a reason 103 raised at line 417
would land in the field a reason 102 had already been written to and would
**overwrite** it, and the record would then be rejected under 103 with 102 gone
without trace. The field name is quoted with the baseline spelling
`ACCT-EXPIRAION-DATE`, because the declared name is what the program compiles
against.

What keeps 102 the surviving reason here is that the expiration gate **passes**.
This record's `DALYTRAN-ORIG-TS` carries the date `2022-06-10` in its leading ten
bytes, and the account's expiration date is `2024-12-13`, so line 414 is true and
takes the `CONTINUE` at line 415. Nothing is skipped and nothing is short-circuited
around; the gate runs and its pass arm is taken. The oracle counterpart records
the same relationship at its lines 47 to 54.

Assumptions: the account's expiration date of `2024-12-13` is an external
contract this fixture depends on and does not own. It lives in
[`app/data/ASCII/acctdata.txt`](../../../../../../../app/data/ASCII/acctdata.txt)
on the row for account `00000000007`, measured, and section 4.2 records why that
file is not staged here. The originating date of `2022-06-10` is therefore
load-bearing rather than cosmetic: it is the seed's own value, kept deliberately
well inside the account's validity window so that the expiration gate cannot
reach its `ELSE`. Moving it past `2024-12-13` would silently convert this folder
into a second copy of `reject_103_expired`, and the failure would present as the
wrong reason code rather than as a broken fixture. Should that account's
expiration date ever move earlier than `2022-06-10`, this scenario stops
expressing reason 102 and the date in this record is what has to move in
response.

---

## 3. Expected outcome

Stated against the target shapes, because those are the shapes an assertion in
this module reads. All column and constraint citations below are to
[`V1__ledger.sql`](../../../../main/resources/db/migration/V1__ledger.sql) and
were verified by reading that file.

### 3.1 The reject row

Exactly **one** row in `ledger.transaction_rejects`, created at line 579, carrying:

| Column | Declared at | Expected value |
|---|---|---|
| `reason_code SMALLINT NOT NULL` | line 697 | `102` |
| `reason_desc VARCHAR(76) NOT NULL` | line 719 | `OVERLIMIT TRANSACTION` |
| `raw_record CHAR(350) NOT NULL` | line 670 | the `dailytran.txt` record's 350 bytes, undecomposed |

The message is the literal at line 411 of the program, character for character:
one word `OVERLIMIT`, no hyphen, no trailing period, 21 characters. It is a
user-visible string, so it is carried across verbatim rather than tidied, and an
assertion on it is an assertion on those exact bytes.

`raw_record` holds the feed record whole and undecomposed, which includes the
**26 spaces at positions 305 to 330** that the feed record carries in its
processing-timestamp field. A reader expecting that column to hold a parsed or
trimmed image will find neither; the value is the input bytes.

Assumptions: the reason code is constrained only as a range, not as an enumerated
list. `ck_transaction_rejects_reason_code` at lines 757 and 758 checks
`reason_code BETWEEN 0 AND 9999`, which follows the four-digit
`WS-VALIDATION-FAIL-REASON` picture at line 181 of the program rather than the set
of codes the program actually raises. So the schema admits 102 by width and does
not itself assert that 102 is the right answer -- the assertion has to name the
code. This matters because the sibling `reject_101_acct_missing` and
`reject_109_rewrite_invalid_key` share a message text between them, as folder
section 1.2 records, so `reason_desc` alone cannot always identify a scenario.
Naming the code is the habit that keeps every reject assertion in this folder
unambiguous, including this one, where the message happens to be unique.

### 3.2 What must not change

The three statements below are as much of the outcome as the reject row is, and
each is checkable against a file this folder ships.

- **No row is added to `ledger.transactions`.** In the program, line 211 reaches
  `2000-POST-TRANSACTION` at line 212 only when the reason is still zero; a
  non-zero reason takes the `ELSE` at line 213, increments the reject count at
  line 214 and performs `2500-WRITE-REJECT-REC` at line 215. Everything that
  writes -- the category-balance update, the account update and the transaction
  write at lines 440 to 442 -- sits inside the paragraph that is not reached. The
  row `transact.txt` supplies is therefore still the only row, and still carries
  the amount it started with. Section 6 records exactly which assertion that
  permits and which it does not.
- **The `ledger.transaction_category_balances` row is unchanged at +100.00.** It
  is the row `tcatbal.txt` supplies, keyed on the composite primary key at lines
  883 and 884. Section 7 records why the starting value is non-zero.
- **No account or customer row is touched**, and neither is staged here in any
  case. Section 4.2 records why.

### 3.3 The 430-byte lineage of the reject row

The three target columns are a decomposition of one baseline record, and the
lineage is worth stating because the 350 in `CHAR(350)` is not an arbitrary width.

`2500-WRITE-REJECT-REC` begins at line 446. It moves the whole daily record into
the reject record's data area at line 447, moves the trailer at line 448, and
writes the record at line 451. The record it writes is declared at lines 176 to
178 as `REJECT-TRAN-DATA PIC X(350)` followed by `VALIDATION-TRAILER PIC X(80)`,
totalling **430** bytes; lines 180 to 182 decompose that trailer into
`WS-VALIDATION-FAIL-REASON PIC 9(04)` and
`WS-VALIDATION-FAIL-REASON-DESC PIC X(76)`.
[`app/jcl/POSTTRAN.jcl`](../../../../../../../app/jcl/POSTTRAN.jcl) confirms the
total independently at line 36 with `LRECL=430`.

So `raw_record CHAR(350)` is the data area, `reason_code SMALLINT` is the
four-digit reason and `reason_desc VARCHAR(76)` is the description, and the widths
line up field for field with the record the baseline writes. Folder section 2.3 is
the authority for that mapping and for why a reject scenario ships no fourth
input file: a reject is an **output** of posting rather than an input to it, so the
expected code and message are stated here in prose and the input set is the same
three files every other scenario carries.

For baseline context only, and not as a property of this folder: the program moves
4 into `RETURN-CODE` at line 230 when the reject count is above zero at line 229,
and `tests/README.md` records at its lines 558 and 559 that each reject reason
writes the reject stream and sets that code. This folder supplies inputs and a
documented expectation. It makes no claim about any expected-output tree, and
folder section 4.1 records that this tree deliberately has no such counterpart.

---

## 4. Fixture bytes and governance

### 4.1 Files, widths, record counts, line endings

| File | Copybook | Record width | Records | Line ending | Bytes on disk |
|---|---|---:|---:|---|---:|
| `dailytran.txt` | [`CVTRA06Y.cpy`](../../../../../../../app/cpy/CVTRA06Y.cpy) lines 4-18 | 350 | 1 | LF | 351 |
| `transact.txt` | [`CVTRA05Y.cpy`](../../../../../../../app/cpy/CVTRA05Y.cpy) lines 4-18 | 350 | 1 | LF | 351 |
| `tcatbal.txt` | [`CVTRA01Y.cpy`](../../../../../../../app/cpy/CVTRA01Y.cpy) lines 4-10 | 50 | 1 | LF | 51 |

Every number above was measured on this branch. Each file ends with **exactly one
trailing newline** after its single record, which is why the on-disk size is the
record width plus one and why `wc -l` equals the record count -- the cheap
integrity check master section 3.3 exists to provide. **Carriage returns are absent
from all three**, measured as a count of zero across the folder.

Assumptions: the LF ending on `tcatbal.txt` is a deliberate normalisation and not
an inherited property, which matters because the seed row it derives from is
CRLF-terminated. Master section 3.2 names `tcatbal` as one of three seeds that ship
CRLF and requires a scenario that *intentionally preserves* CRLF to say so in its
own README; this scenario does not preserve it, so the note here records the
opposite choice rather than an exemption. Master section 5.6 repeats the warning at
its lines 432 to 435, and folder section 3.3 is the authority for the resolution
across the whole folder. The consequence of the alternative is concrete: the loader
treats one physical line as one fixed-length record, so a stray carriage return is
absorbed into the trailing `FILLER` and pushes a 50-byte record to 51 bytes on the
line, landing in the field at positions 29 to 50 and corrupting it for a reason
unrelated to anything under test.

The authoritative position tables are master sections 5.1, 5.5 and 5.6, and the
sign-overpunch table is master section 3.4. None of them is reproduced here. The
only positions named anywhere in this document are the three the reasoning cannot
be written without: the amount at 133-143, the originating timestamp at 279-304 and
the processing timestamp at 305-330, all of which folder section 2.1 lists with two
independent corroborations.

### 4.2 The account context, which is deliberately not a file here

The gate at line 407 reads three fields this folder does not ship. They are
recorded here in prose so that the arithmetic in section 2.2 can be checked, and
they were measured on the row for account `00000000007` in
[`app/data/ASCII/acctdata.txt`](../../../../../../../app/data/ASCII/acctdata.txt):

- `ACCT-CREDIT-LIMIT` reads `00000020650{`, decoding to **2065.00**.
- `ACCT-CURR-CYC-CREDIT` and `ACCT-CURR-CYC-DEBIT` both read `00000000000{`,
  decoding to **0.00** each, which is what reduces the trial balance to the amount.
- `ACCT-EXPIRAION-DATE` reads `2024-12-13`, which is what section 2.3 depends on.

Assumptions: the credit limit and the transaction amount are **different widths**,
and conflating them is the specific error this paragraph exists to prevent.
`ACCT-CREDIT-LIMIT` is declared `PIC S9(10)V99` at line 8 of
[`CVACT01Y.cpy`](../../../../../../../app/cpy/CVACT01Y.cpy) and occupies **12
bytes**, which is why its overpunched form `00000020650{` is twelve characters
long. `DALYTRAN-AMT` is declared `PIC S9(09)V99` at line 10 of `CVTRA06Y.cpy` and
occupies **11 bytes**, which is why `0000020650A` is eleven. The two values are
compared and they decode to the same scale, so a reader carrying one field's width
across to the other will produce a value that looks plausible and is wrong by a
factor of ten. The working field the comparison actually uses,
`WS-TEMP-BAL PIC S9(09)V99` at line 187 of the program, shares the narrower
11-byte width of the transaction amount rather than the wider account width.

Alternatives Considered: staging `acctdata.txt` and `cardxref.txt` in this folder,
which is exactly what the oracle counterpart does. It was rejected on ownership
rather than on convenience. The account master and the card cross-reference belong
to the `account` schema, owned by account-service, and this module owns the `ledger`
schema only; a fixture tree that shipped another module's record images would make
this folder the second place those layouts are defined and free to drift from the
first. The credit limit and the two cycle totals that the line 407 gate reads live
in `account.accounts`, so the precondition they express is state a consuming test
establishes rather than bytes this folder loads. Folder section 3.1 is the
authority for that decision across the whole folder. The cost is that this
scenario's outcome cannot be derived from its own three files alone, which is why
the three values above are recorded in prose with the seed row they came from.

### 4.3 Provenance attestation

Master section 10.3 mandates the three items below at its lines 818 to 828, and
folder section 7 restates the obligation for this folder. Master sections 10.1 and
10.2 carry the attestation language and the derivation-of-record reasoning in full
and are not restated here.

1. **Synthetic and seed-derived.** Every identity-shaped byte in this folder is a
   published CardDemo demonstration value. The card number `4859452612877065`, the
   account identifier `00000000007`, the transaction identifier
   `0000000000683580`, the merchant name, city and ZIP all arrive from
   [`app/data/ASCII/dailytran.txt`](../../../../../../../app/data/ASCII/dailytran.txt)
   record 1, measured, whose key is `0000000000683580`. `tcatbal.txt` derives from
   [`app/data/ASCII/tcatbal.txt`](../../../../../../../app/data/ASCII/tcatbal.txt)
   record 1, the row for account `00000000007`. `transact.txt` has no seed row of
   its own: item 7 of the table at folder section 4.2 establishes that
   `app/data/ASCII/` holds no `transact.txt` at all, so its bytes are authored
   from the `CVTRA05Y` layout and its identity bytes are the same seed record 1
   values, measured.
2. **No real person and no real account.** These are demonstration values, not
   credentials. They identify no real person and no real account, and no value in
   this folder is a secret, a credential or an endpoint.
3. **Which business-rule fields were reshaped away from the seed value.** Three,
   named explicitly so the provenance of the changed bytes is as explicit as the
   provenance of the unchanged ones:
   - `DALYTRAN-AMT` in `dailytran.txt`, at positions 133-143: the seed carries
     `0000005047G` (+504.77) and this file carries `0000020650A` (+2065.01).
     Measured against the seed the change spans six bytes, positions 138 to 143;
     measured against `boundary_exact_limit/dailytran.txt` it is the single byte at
     143. No other byte of this record differs from seed record 1.
   - `TRAN-CAT-BAL` in `tcatbal.txt`, at positions 18-28: the seed row carries
     `0000000000{` (+0.00) and this file carries `0000001000{` (+100.00). Section 7
     records why.
   - `TRAN-PROC-TS` in `transact.txt`, at positions 305-330: the seed daily record
     carries 26 spaces there and this file carries the fixed literal
     `2022-07-18 00:00:00.000000`. Section 6.2 records why the two files treat that
     span differently.

   None of the three carries identity. The account identifier, the type code and
   the category code in `tcatbal.txt` compose the seed row's own key and are
   unchanged from it.

---

## 5. Why the moving byte is the last one, and why the margin is one cent

Assumptions: the sign of a zoned-decimal field is carried in its **low-order**
digit, so the byte that separates this fixture from its pass counterpart is the
eleventh of the amount field and not the first. An author expecting a leading
minus sign, a decimal point, or a change somewhere in the leading digits will find
none of those: master section 3.5 records that the implied decimal occupies no byte
at all, and master section 3.4 records that the sign and the low-order digit share
one character. That is the whole reason a one-cent change is a change to the final
byte rather than to the field's tail digits.

Alternatives Considered: representing this money as a binary floating-point value
somewhere along the path, which is the single most likely way this scenario is
defeated without anything appearing to go wrong. A one-cent difference at a
magnitude above two thousand is not exactly representable in IEEE-754 binary64: the
two values become the nearest representable neighbours of 2065.00 and 2065.01
rather than those numbers, and a comparison, a sum or a round trip through such a
type can make them compare equal, or can yield a value on the other side of the
limit than the byte says. The failure is silent -- no exception, no truncation
warning, just a gate that answers TRUE where the bytes require FALSE. That is
precisely the defect this pair is built to catch, and a floating-point pipeline
would report the pair as passing while the boundary went unverified.

The discipline chosen instead is exact fixed point at every hop, and this folder is
the sharpest argument for it in the module: `NUMERIC(11,2)` in the `ledger` schema,
which
[`V1__ledger.sql`](../../../../main/resources/db/migration/V1__ledger.sql)
declares for the feed amount at line 449 and for the posted amount at line 198;
`BigDecimal` at scale 2 with `HALF_UP` rounding in Java; and a JSON **string** on
the wire, so that no client parses the value into a double on the way in. Every
money figure in this document is written as a decimal string with two decimal
places for the same reason.

Trade-offs: choosing the smallest representable step rather than a comfortable
margin. A larger amount -- say a hundred dollars beyond the limit -- would still
reject, so a test built on it would go green while establishing nothing about
whether the comparison is inclusive or exclusive; an implementation using `>` where
the reference uses `>=` produces identical results on every input except the one
sitting exactly on the limit, and that single value is what the pair straddles. One
cent is the smallest step a scale-2 field can express, so it is the only margin that
separates the two operators. The cost is a fixture unusually brittle to a single
byte, and that cost is accepted because the brittleness is the sensitivity: a
fixture that cannot be broken by one byte cannot detect a one-byte-wide rule
either.

---

## 6. What determines the bytes of `transact.txt`

`transact.txt` loads `ledger.transactions`, the **posted**-transaction table
created at line 117 of the migration, and section 3.2 states this scenario's
expected outcome as no posted transaction. Those two facts together decide what the
row may contain, and they are the reason this file needs its own section.

### 6.1 It is prior ledger state, not the posted image of the record beside it

Measured on this branch, this file is **byte-identical to
`happy_path/transact.txt`**. It carries the transaction identifier
`0000000000683580`, transaction type `01`, source `POS TERM`, and an amount of
`0000005047G` at positions 133-143, decoding to **+504.77**.

The value that matters is that amount. It is the **template amount**, the one
`happy_path` posts, and it is **not** this scenario's amount of +2065.01. So this
row is not the posted image of the feed record sitting beside it, and it does not
assert that the rejected transaction posted.

Alternatives Considered: mirroring, which is what the pass counterpart does. Folder
section 6 requires every `transact.txt` in this tree to carry a filled processing
timestamp, and `boundary_exact_limit` additionally mirrors its own feed record's
first 304 positions **including the moved amount**, so that its posted row is the
outcome its own section 3 claims. Copying that convention here would be wrong
rather than merely redundant: nothing posts in this scenario, so a mirrored row
carrying +2065.01 would put the rejected transaction on the posted ledger as
starting state and would assert the exact opposite of the outcome above. The
amount is therefore left at the template value, and that difference from the
mirroring convention is the point of it.

Assumptions: this row nevertheless shares the feed record's **identifier**, and
that is a constraint on how the outcome can be asserted rather than a detail. The
migration declares `transaction_id CHAR(16)` at line 127 and makes it the primary
key through `pk_transactions` at line 275, so a pre-loaded row keyed
`0000000000683580` is indistinguishable by key from one an insert would have added.
Two consequences follow, and a test author needs both:

- **A row-count assertion alone is not sufficient.** An implementation that posted
  the rejected transaction through an upsert or a merge would update this row in
  place rather than add one, leaving the count at exactly one. The count would
  agree with the expected outcome while a post had in fact occurred.
- **The available assertion is on the surviving row's value.** After the run the
  single row must still carry **+504.77**, and must never carry +2065.01. That
  distinguishes an untouched ledger from an overwritten one, which the count cannot.
  An assertion that the rejected identifier is absent is **not** available here,
  because the identifier is present as starting state.

Trade-offs: the alternative shape is a divergent prior-state row carrying a
different identifier, which four of the ten scenarios in this folder do use --
section 4.3 of the sibling
[`reject_101_acct_missing`](../reject_101_acct_missing/README.md) enumerates them
as `boundary_expiry_equal`, `reject_101_acct_missing`, `reject_100_card_missing`
and `reject_109_rewrite_invalid_key`, and this folder is correctly absent from that
list. A concrete candidate exists in the seed: record
154 of `app/data/ASCII/dailytran.txt`, key `0000000500885895`, type `03`, source
`OPERATOR`, amount `0000005798Q` decoding to -579.88, on the same card
`4859452612877065`. Adopting it would buy the identifier-absence assertion and
would exercise the negative sign overpunch as a bonus, at the cost of this file no
longer being byte-identical to the template and of an author meeting one more
difference when comparing the ten with `cmp`. The bytes as they stand keep the
byte-identity, and this section is where the resulting narrower assertion surface is
accounted for so that no test is written against an assertion this fixture cannot
support.

### 6.2 The two processing timestamps are treated differently

`dailytran.txt` carries **26 spaces** at positions 305-330; `transact.txt` carries
the fixed literal `2022-07-18 00:00:00.000000` in the same span. The asymmetry is a
schema constraint rather than a house preference, and folder section 3.2 is the
authority for it.

Assumptions: the two target columns differ in nullability, and the difference
follows the data rather than a convention. The migration declares
`ledger.daily_transactions.proc_ts TIMESTAMP(6)` -- nullable -- at line 500, and
`ledger.transactions.proc_ts TIMESTAMP(6) NOT NULL` at line 265. The feed record
genuinely arrives with that field blank, because the baseline assigns it during
posting: `2000-POST-TRANSACTION` performs `Z-GET-DB2-FORMAT-TIMESTAMP` at line 437
and moves the result into `TRAN-PROC-TS` at line 438. So 26 spaces are the correct
bytes on the input side and would be rejected at insert on the posted side, which
is why one file blanks the span and the other fills it.

Assumptions: the filled value is a **literal** rather than a run-varying reading,
and that is what keeps an assertion on a 26-character timestamp reproducible. The
instant is pinned in test code, not in configuration: `FIXED_CLOCK` at lines 259
and 260 of
[`TransactionRepositoryIT`](../../../java/com/carddemo/transaction/repository/TransactionRepositoryIT.java)
is `Clock.fixed(Instant.parse("2022-07-18T00:00:00Z"), ZoneOffset.UTC)`. The
sibling profile [`../../application-test.yml`](../../application-test.yml) is the wrong
place to look for it and says so itself: its exclusion list at lines 68 to 72 names
any clock or current-time property as deliberately absent, and records that
determinism is injected through `Clock.fixed` because `common-lib`'s
`TimestampFormatter` takes the clock as a collaborator rather than reading the
ambient one. The interior shape of the 26 characters -- a space at character 11 and
a dot at character 20 -- is folder section 2.2, which corroborates it from two
sources.

---

## 7. What determines the bytes of `tcatbal.txt`

The record reads `000000000070100010000001000{0000000000000000000000`: account
`00000000007`, transaction type `01`, category `0001`, a balance of
`0000001000{`, and 22 trailing `FILLER` bytes.

### 7.1 The balance is +100.00, and the low-order byte is why

`0000001000{` decodes to **+100.00**. The ten leading characters supply the digits
`0000001000` and the trailing `{` is a positive-zero overpunch supplying the
low-order digit **0**, making the digit string `00000010000`, which with two implied
decimal places is 100.00.

That final byte is the whole difference between this value and a plausible
misreading of it. Dropping the overpunched digit and reading only the ten leading
characters gives 10.00, which is wrong by a factor of ten and looks entirely
reasonable. The value is +100.00. The digit the overpunch contributes is the last
one, not the first, for the reason section 5 records.

Assumptions: the composite key is the account plus the type plus the category, and
it must agree with the feed record's type `01` and category `0001` or the scenario
changes character. In the baseline, a matching row takes the update branch of
`2700-UPDATE-TCATBAL` and a missing one takes the create branch; folder section 1.3
cites those two branch points. A key that drifted would put this scenario on the
other branch from the one its outcome describes. The target expresses the same key
as the composite primary key at lines 883 and 884 of the migration.

### 7.2 The starting balance is non-zero here and zero in the pass counterpart

Measured, `boundary_exact_limit/tcatbal.txt` differs from this file at **exactly one
byte, position 24**: a `0` there against the `1` here, which is the difference
between +0.00 and +100.00. This file is byte-identical to
`reject_101_acct_missing/tcatbal.txt`, also measured.

Assumptions: a posting scenario and a reject scenario want opposite starting values,
and the two neighbouring fixtures reflect that rather than disagreeing by accident.
In a scenario that posts, a starting balance of +0.00 leaves the resulting
arithmetic with a single contributing term, so the posted result is provably the
transaction amount and nothing else -- which is why the pass counterpart holds zero,
and its own section 7.1 records that reasoning. In a scenario that rejects, nothing
is added, so the value's job is the opposite: it has to make an accidental mutation
**visible**. A row starting at +0.00 that was wrongly incremented and then wrongly
decremented, or that was written and rolled back to its default, can land back on
zero and look untouched, because `balance NUMERIC(11,2) NOT NULL DEFAULT 0` at line
869 of the migration makes zero the value a newly created row already has. A row
starting at +100.00 cannot be confused with a defaulted one, so "unchanged at
+100.00" is a claim that distinguishes an untouched row from a recreated one.

### 7.3 The `FILLER` is 22 ASCII zeros, not spaces

Assumptions: this is the seed's own convention for this layout and not an oversight,
and it is worth flagging because **every other record in this folder pads with
spaces** -- the 20-byte `FILLER` at positions 331-350 of both 350-byte records is
blank, and this 22-byte span is not. Master section 5.6 states the convention at its
lines 432 to 435 and gives the reason: those 22 bytes are nearly half of a 50-byte
record, so blanking them would make any byte-level comparison against a seed-shaped
row fail for a reason unrelated to the behaviour under test. Preserving them also
means an edit to the balance that gained or lost a byte pushes into a run of zeros
and shows up as a `FILLER` difference rather than as a silent shift of the field
boundary.

---

## 8. The oracle this folder mirrors, and how the two file sets differ

`tests/fixtures/posting/reject_102_overlimit/` is the counterpart of this folder in
the reference tree. It is read as a structure and semantics reference and is
**never** modified, moved or re-pinned: `app/**`, `tests/**`, `scripts/**` and
`samples/**` are reference-only, and the reference suite is the parity oracle the
migration is verified against, so an edit there would change the thing the work is
measured by.

Assumptions: the two file sets deliberately differ, and the difference is
predictable from schema ownership rather than arbitrary. The oracle ships
`README.md`, `dailytran.txt`, `tcatbal.txt`, `acctdata.txt` and `cardxref.txt`; this
folder ships `README.md`, `dailytran.txt`, `tcatbal.txt` and `transact.txt`. So the
oracle carries an account image and a cross-reference image that this folder does
not, for the reason section 4.2 gives, and this folder carries a `transact.txt` for
which the oracle has no equivalent at all -- folder section 6 explains that
asymmetry, the short form being that the oracle's fixtures are inputs presented to a
compiled program whose reject stream is an output, whereas this folder's rows are
loaded into a database ahead of a test. The two trees agree on the business rule and
on the discriminating bytes; they are not expected to agree on the file list.

Trade-offs: three habits visible in the oracle document are not the standard here,
and folder section 8 records them at its lines 750 to 766 so that reading the oracle
for shape does not import a defect. The oracle writes its category labels in the
singular; this document writes the plural forms the documentation standard defines.
The oracle carries a bracketed identifier tag in one of its headings that
corresponds to no requirement register in this project; no such tag appears here.
And the oracle cites specification section 0.10.1 as the anchor for the
Explainability rule, which is the attachments section; section 10 below cites the
rule by name instead.

---

## 9. What consumes this scenario

Annotated `[present]` or `[planned]` per master section 9.3, whose purpose is to
stop a document describing a future artifact as though it already existed. Paths
that do not yet exist appear as plain code spans rather than links, so that no link
in this tree resolves to nothing.

- [`TransactionFixtureContractTest`](../../../java/com/carddemo/transaction/fixtures/TransactionFixtureContractTest.java)
  -- **[present]**, and it reads these bytes. It resolves fixtures from the
  classpath root `fixtures/` declared at its line 32, and its
  `theOverLimitBoundaryIsAOneCentPair` case at lines 128 to 144 decodes
  `DALYTRAN-AMT` from both members of the pair and asserts +2065.00, +2065.01 and a
  difference of 0.01 -- asserting the difference, so that moving both files by the
  same amount fails rather than passing two independent checks. Its
  `theCategoryBalanceRowsCarryTheComposedKey` case at lines 238 to 276 asserts this
  folder's composed key and its +100.00 balance, and its
  `everyScenarioCarriesItsThreeFiles` case asserts the record widths and the absence
  of carriage returns.
- [`TransactionRejectRepositoryIT`](../../../java/com/carddemo/transaction/repository/TransactionRejectRepositoryIT.java)
  -- **[present]**. It resolves a scenario's `dailytran.txt` from this tree at its
  line 1488 and exercises the reject-row columns of section 3.1 against a real
  PostgreSQL instance through Testcontainers.
- A test asserting **this scenario's reject outcome end to end** -- **[planned]**.
  Nothing today drives the reason-102 path from these bytes and asserts
  `reason_code` 102, `reason_desc` `OVERLIMIT TRANSACTION`, the 350-byte
  `raw_record`, and the two untouched-row claims of section 3.2. That is the gap this
  folder exists to be filled against, and section 6.1 constrains how the
  posted-ledger half of it may be written. The module's naming shape for such a class
  is `*ControllerTest` for the web layer, `*ServiceTest` for a unit-level rule and
  `*RepositoryIT` for a Testcontainers-backed integration test, and it would run
  under the `test` Spring profile supplied by
  [`../../application-test.yml`](../../application-test.yml), which pins schema resolution
  to `ledger` for the connection at its line 131 and for the migration tool at its
  lines 165 and 172, and points the migration tool at this module's own migration at
  its line 157.
- [`V1__ledger.sql`](../../../../main/resources/db/migration/V1__ledger.sql) --
  **[present]**, the source of every column and constraint cited in section 3.
- [`tests/fixtures/README.md`](../../../../../../../tests/fixtures/README.md) --
  **[present]**, the authoritative byte contract.

Refactoring Rationale: this section previously would have read `[planned]` in full,
and the folder index still describes the test tree as holding no consumer of these
fixtures. That description no longer matches the branch: the tree now holds a
fixture-loading contract test that asserts this very pair's decoded values, so
recording it as absent would understate the coverage and, worse, would leave a
future author re-deriving a decode that is already asserted in code. The annotation
is therefore stated per artifact and as a measurement, with the genuinely missing
piece named narrowly as the reject-outcome assertion rather than as the whole
consumer.

---

## 10. How this document satisfies Rule 1

Rule 1, Explainability, requires a docstring stating purpose, parameters, return
values and error conditions, and requires every non-obvious decision to carry a
rationale from one of four named categories. Its full text is available through
`review_rules`; the specification anchors are sections 0.8 and 0.8.1 for the rule
itself and 0.2.1.6 for the files it forces into scope. A fixed-width data file has
no docstring construct, so the four fields are mapped onto this folder as follows.

| Rule 1 field | Where it is discharged |
|---|---|
| Purpose | Section 1, the scenario intent, and section 1.1, the pair that gives it meaning |
| Parameters | Section 4.1, each staged record with its width, count and line ending; section 4.2, the account context that is not a file; sections 6 and 7, the field semantics of each record |
| Return values | Section 3.1, the expected reject-row column values, and section 3.2, the rows that must be unchanged |
| Exceptions and error conditions | Section 2.1, the gate ordering that determines which reason code is reachable, and section 2.3, the 102 and 103 relationship in which a later reason would overwrite an earlier one |

The rationale labels used here are `Alternatives Considered:`,
`Refactoring Rationale:`, `Assumptions:` and `Trade-offs:`, written plural,
unparenthesised, with the colon retained and with no emphasis markup, which is the
one form
[`docs/CODE_DOCUMENTATION_STANDARD.md`](../../../../../../../docs/CODE_DOCUMENTATION_STANDARD.md)
permits at its lines 205 to 226. Its reasoning at lines 228 to 234 is that the label
is read by a literal search before it is read by a person, so a second accepted
spelling makes an audit silently partial. Folder section 8 repeats the constraint
for this tree.

This document is written in 7-bit ASCII throughout, using `--` for a dash, `->` for
an arrow, `>=` for an inequality and the word `section` rather than a section
symbol, per folder section 8. A codepoint that renders like an ASCII character but
is not one defeats a literal search for a hyphenated COBOL field name such as
`ACCT-EXPIRAION-DATE` or `TRAN-PROC-TS`, which is the main way anyone navigates
between these fixtures and the copybooks.

Refactoring Rationale: every line number cited above was re-derived by reading the
file it cites rather than inherited from a neighbouring document, and that is
load-bearing here rather than merely diligent. Several citations into
`V1__ledger.sql`, `application-test.yml` and the test tree that appear in
neighbouring documents no longer resolve to the lines they name -- those files have
grown since, and a line number is the one kind of reference that goes wrong silently
while continuing to look precise. Carrying such a number across would have produced
a document that reads as verified and is not. An invented or stale citation is worse
than no citation, because it costs the next reader the time to discover it is wrong
and, being unverifiable, is itself the vague rationale the rule forbids. Folder
section 8 makes re-reading before writing the closing requirement of this tree for
exactly that reason.
