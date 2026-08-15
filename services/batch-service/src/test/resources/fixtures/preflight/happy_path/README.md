# `preflight/happy_path` -- the card resolves, the account is found, and neither diagnostic fires

> **Purpose.** Pin the fully matched path of the pre-posting preflight pass: one daily transaction
> whose card **is** present in the cross-reference and whose resolved account **is** present in the
> account master. Both indexed reads succeed, so the program reports both successes and reaches
> neither of its two diagnostic branches. It is the reference case the two unmatched siblings deviate
> from, and the only one of the three in which nothing is reported.
>
> **Source of truth.** `app/cbl/CBTRN01C.cbl` for the behaviour, cited below by line;
> `app/cpy/CVTRA06Y.cpy`, `app/cpy/CVACT03Y.cpy` and `app/cpy/CVACT01Y.cpy` for the record layouts;
> the seed datasets under `app/data/ASCII/` for the bytes; and the tree-level
> [master contract](../../README.md) for every encoding rule, which this document cites by section
> and never restates (master section 1.3). **There is no JCL driver to cite**: master section 4.1
> records that no member of `app/jcl` executes this program at all, which is why the reference
> behaviour is reachable only through a test.
>
> **Label form.** Rationales below are tagged `Alternatives Considered:`, `Assumptions:` and
> `Trade-offs:` -- plain, plural, colon retained, no emphasis markup, per
> `docs/CODE_DOCUMENTATION_STANDARD.md` and master section 1.4. That spelling is not cosmetic:
> `config/rule1/rule1_gate.py` is a build-failing gate whose canonical label set covers `.md` and
> rejects parenthesised, singular, colon-dropped and emphasis-wrapped variants, so an emphasised
> label here would fail the build as well as escape the literal search the audit depends on. This
> whole file is pure ASCII for the reason master section 1.4 gives.

**This README is the mandatory Explainability carrier for the three record files beside it.** Master
section 1.2 records why: a fixed-width record file cannot carry a comment of any kind, because every
byte position is meaningful and a comment on its own line is a physical row of the wrong length.
Master section 10 makes the artifact mandatory rather than courteous, and the section order below is
the one it fixes.

---

## 1. Purpose -- the condition this scenario pins

One daily transaction on card `4859452612877065`, a cross-reference row holding **that** card and
resolving it to account `00000000007`, and an account master holding **that** account. Both lookups
succeed.

Three things hold at once:

- the cross-reference lookup **succeeds**, so the card-unresolvable branch is not taken;
- the account lookup **succeeds**, so the account-missing branch is not taken;
- **nothing is written and nothing is changed** -- every one of the program's six datasets is opened
  `INPUT`, and the program contains no `WRITE`, `REWRITE` or `DELETE` statement of any kind.

Master section 4.1 fixes the domain at three scenarios, and this is the one that establishes what a
clean record looks like so that the other two have something to differ from.

Alternatives Considered: omitting a matched-path scenario altogether, on the grounds that the two
unmatched scenarios carry the interesting behaviour. Rejected because an implementation that reported
a diagnostic for **every** record -- one that had inverted a status test, say -- would satisfy both
unmatched scenarios and fail only this one. Without it the suite cannot distinguish "reports the right
records" from "reports every record".

Assumptions: the domain directory is spelled **`preflight/`** here while the reference-only house tree
spells the same three scenarios **`prepost/`** (`tests/fixtures/prepost/**`), and neither name is
wrong. Master section 4.2 records the correspondence: the two names denote the same `CBTRN01C`
behaviour, this tree matches the job name a reader finds in the module's own sources and in the batch
state machine, and the house tree is reference-only in any case. The consequence is a rule for
maintainers rather than a fact about bytes -- **neither name is to be "corrected" to the other**, and a
reader looking for the house analogue of this folder must look under `prepost/happy_path`, not under a
`preflight` directory that does not exist there.

---

## 2. The business rule, cited by program and line

### 2.1 The two lookups, and the guard between them

`app/cbl/CBTRN01C.cbl:164-186` is the main loop. For this record:

| Step | Line | What happens |
|---|---|---|
| Read the feed | `:166`, `:202-225` | `1000-DALYTRAN-GET-NEXT`; the `READ` at `:203` returns `'00'`, so `:204-205` set `APPL-RESULT` to 0 and the terminating flag stays `'N'` |
| Display the record | `:167-169` | the inner guard is true, so `:168` DISPLAYs the whole `DALYTRAN-RECORD` |
| Compose the card key | `:170-171` | the read status is cleared and `DALYTRAN-CARD-NUM` is moved into `XREF-CARD-NUM` |
| **Cross-reference lookup** | `:172`, `:227-239` | `2000-LOOKUP-XREF` moves the key in at `:228` and reads `KEY IS FD-XREF-CARD-NUM` (`:229-230`). The card **hits**, so the `NOT INVALID KEY` arm at `:234` runs and `WS-XREF-READ-STATUS` stays 0 |
| Guard | `:173` | `IF WS-XREF-READ-STATUS = 0` is **true**, so the account read is performed rather than the skip message |
| Compose the account key | `:174-175` | the account status is cleared and `XREF-ACCT-ID` is moved into `ACCT-ID` |
| **Account lookup** | `:176`, `:241-250` | `3000-READ-ACCOUNT` moves the key in at `:242` and reads `KEY IS FD-ACCT-ID` (`:243-244`). The account **hits**, so the `NOT INVALID KEY` arm at `:248` runs and `WS-ACCT-READ-STATUS` stays 0 |
| No diagnostic | `:177-179` | `IF WS-ACCT-READ-STATUS NOT = 0` is **false**, so the line at `:178` does not run |
| Leave the loop | `:186` | the next iteration reaches end of file; see section 2.2 |
| Close and finish | `:188-197` | six closes, the end banner at `:195`, `GOBACK` at `:197` |

The four DISPLAY literals the matched path produces are reproduced **exactly** as the source writes
them, spacing included:

| Line | Literal, verbatim | Operand appended |
|---|---|---|
| `:235` | `'SUCCESSFUL READ OF XREF'` | -- |
| `:236` | `'CARD NUMBER: '` | `XREF-CARD-NUM` |
| `:237` | `'ACCOUNT ID : '` | `XREF-ACCT-ID` |
| `:238` | `'CUSTOMER ID: '` | `XREF-CUST-ID` |
| `:249` | `'SUCCESSFUL READ OF ACCOUNT FILE'` | -- |

`:237` carries a **space before its colon** where `:236` and `:238` do not. That asymmetry is in the
baseline literal and is reproduced rather than tidied: `app/**` is reference-only, and an expectation
that normalised the spacing would stop matching the output the reference actually emits.

**The `SUCCESSFUL READ OF ...` lines are confirmation output, not error output.** Both sit on
`NOT INVALID KEY` arms. They are the markers an expectation asserts **present**; the diagnostics this
scenario must not produce are at `:232`, `:246`, `:178` and `:181-183`.

**This lookup uses the cross-reference PRIMARY key** -- `XREF-CARD-NUM`, offset 0, length 16 -- exactly
as posting does. Master section 4.3 fixes the two access paths and records that the account-keyed
alternate path at offset 25, whose migrated target is the secondary index
`idx_card_xref_account_id`, belongs to the **interest** domain. Nothing in this folder reads through
it, so the account identifier at offset 25 is here a **resolved value**, never a search key.

### 2.2 The loop performs the lookup twice for one record

The inner `IF END-OF-DAILY-TRANS-FILE = 'N'` opened at `:167` closes at **`:169`**, so it guards
**only** the record display at `:168`. The lookup block at **`:170-184` sits outside it**, inside the
outer `IF` at `:165`. On the second iteration the read at `:166` reaches end of file and sets the
terminating flag, the display is skipped -- and the lookup block runs **again**, against the card
number still sitting in `DALYTRAN-RECORD` from the first pass.

So for one input record the program performs **two** cross-reference reads and **two** account reads,
and emits each success marker **twice**.

Assumptions: this is a control-flow characteristic of the immutable baseline, and it is the divergence
the migrated `PreflightDailyTransactionsJob` carries -- the target inspects each record it read once
and inspects no record it did not read. It has no effect on data, because every dataset is opened
`INPUT`, so the repeated pass changes nothing and produces no output beyond the duplicated console
lines. The consequence is a hard rule for every expectation over this folder: **assert marker presence
and absence, never a repetition count.** A count assertion would encode the stale re-lookup as a
requirement and would then fail against the migrated pass, which is correct precisely because it does
not repeat itself.

### 2.3 Two duplicate data-names a reader will meet in this program

Both are visible in the `FILE SECTION` and both look like defects on first reading. Neither is one, and
they are recorded separately because they are two independent collisions in two different pairs of
record descriptions.

Assumptions: **`FD-CUST-DATA` is declared twice.** At `:69` it is the filler tail of the DALYTRAN
record description, `PIC X(334)`; at `:74` it is the filler tail of the CUSTOMER record description,
`PIC X(491)`. The two are unrelated fields that happen to share a name, and they collide only in a flat
symbol table -- inside their own `FD` each is unambiguous, which is why the program compiles. The
consequence for anyone decoding these fixtures is to **scope every layout to its copybook** rather than
to a name: the 350-byte feed record is `CVTRA06Y` and nothing else, and a decoder keyed on a bare
field name rather than on a record layout would bind the wrong width and shift every field after it.

Assumptions: **`FD-ACCT-DATA` is declared twice as well**, and it is a genuinely different pair from
the one above. At `:89` it is the filler tail of the ACCOUNT record description, `PIC X(289)`; at `:94`
it is the filler tail of the TRANSACT record description, `PIC X(334)`. The same reasoning applies and
the same consequence follows, with one addition specific to this pair: the name says `ACCT` in both
places while only the first describes an account, so a reader searching for the account layout finds
two hits and must take the one inside `FD ACCOUNT-FILE`. The 300-byte account fixture in this folder is
`CVACT01Y`, and the `:94` occurrence has nothing to do with it.

---

## 3. Returns -- the expected observable outcome

**What must appear.**

| Marker | Line | Times, for one input record |
|---|---|---|
| `START OF EXECUTION OF PROGRAM CBTRN01C` | `:156` | once |
| the whole `DALYTRAN-RECORD`, displayed | `:168` | once -- it is inside the guard that closes at `:169` |
| `SUCCESSFUL READ OF XREF` | `:235` | twice, per section 2.2 |
| `CARD NUMBER: 4859452612877065` | `:236` | twice |
| `ACCOUNT ID : 00000000007` | `:237` | twice |
| `CUSTOMER ID: 000000007` | `:238` | twice |
| `SUCCESSFUL READ OF ACCOUNT FILE` | `:249` | twice |
| `END OF EXECUTION OF PROGRAM CBTRN01C` | `:195` | once |

**What must not appear.**

| Absent marker | Line | Why it cannot occur |
|---|---|---|
| `INVALID CARD NUMBER FOR XREF` | `:232` | the read at `:229` takes `NOT INVALID KEY`, so the `INVALID KEY` arm at `:231` is not entered |
| `CARD NUMBER ... COULD NOT BE VERIFIED. SKIPPING TRANSACTION ID-...` | `:181-183` | the `ELSE` at `:180` is reached only when `WS-XREF-READ-STATUS` is not 0 |
| `INVALID ACCOUNT NUMBER FOUND` | `:246` | the read at `:243` takes `NOT INVALID KEY` |
| `ACCOUNT 00000000007 NOT FOUND` | `:178` | the test at `:177` is false, because `WS-ACCT-READ-STATUS` stayed 0 |

The run then completes cleanly: six closes performed from `:188-193`, the end banner at `:195`, and
`GOBACK` at `:197`.

### 3.1 There is no exit-status value to assert, because the program declares none

**`CBTRN01C` declares no `RETURN-CODE`, no reject stream and no counters.** That is a measured
property, not an inference: searching the whole program for `RETURN-CODE`, for the `DALYREJS` reject
dataset, for any reject wording and for any counter data-name returns **zero** hits, and searching for
`WRITE`, `REWRITE` and `DELETE` returns zero hits as well. The program's entire output is console
diagnostics.

Its **only** failure exit is `Z-ABEND-PROGRAM` at `:469-473`, which DISPLAYs `'ABENDING PROGRAM'`
(`:470`), sets the timing operand (`:471`), moves `999` into the abend code (`:472`) and calls
`'CEE3ABD'` with both (`:473`). Every site that performs it is guarded on a file status, so it is
reachable only from an `OPEN`, `READ` or `CLOSE` error -- never from a lookup outcome. Section 4
enumerates the sites.

**The consequence is that the migrated `PreflightDailyTransactionsJob` can never emit exit-status tier
4: it either completes cleanly or fails hard.** There is no soft-reject tier for it to report, because
there is nothing in the program that grades a record. Master section 7.1.6 records that the graded
warn tier belongs to `CBTRN02C` and to the COBOL parity suite alone; it does not apply to this program,
and an expectation over this folder that asserted a numeric exit status would be asserting a value the
reference never assigns.

Assumptions: this domain has **no golden corpus in either tree**, and none is asserted here. Because
the output is console diagnostics rather than records, the expectation is stated as marker presence and
absence above rather than as a byte comparison against an `*.expected` file. The consequence is that
the only things this folder can be held to are its bytes and the relationships between them, which is
what section 5.2 checks and what makes those checks worth running.

---

## 4. Exceptions and errors -- what must not happen, and why it cannot

**Neither diagnostic branch may be taken.** Both are listed in section 3 with the line that guards
them. The failure mode worth naming is the **inverted status test**: an implementation testing
`WS-XREF-READ-STATUS NOT = 0` where the baseline tests `= 0` at `:173` would skip the account read on a
matched card and emit the skip message -- which is exactly what the `unmatched_card` sibling expects.
The two scenarios together are therefore what distinguish a correct guard from an inverted one, and
neither does it alone.

**No abend occurs.** `CBTRN01C` uses `Z-ABEND-PROGRAM` and `Z-DISPLAY-IO-STATUS` rather than the
numbered paragraphs `CBTRN02C` uses, and every site is status-guarded:

| Site | Guarded by | Fires only when |
|---|---|---|
| `:266`, `:285`, `:303`, `:321`, `:339`, `:357` | the six open paragraphs beginning at `:252`, `:271`, `:289`, `:307`, `:325`, `:343` | an `OPEN` returns a status other than `'00'` |
| `:222` | `1000-DALYTRAN-GET-NEXT` | the feed `READ` status is neither `'00'` nor `'10'` |
| `:375`, `:393`, `:411`, `:429`, `:447`, `:465` | the six close paragraphs beginning at `:361`, `:379`, `:397`, `:415`, `:433`, `:451` | a `CLOSE` returns a status other than `'00'` |

A run whose opens and closes all return `'00'`, and whose feed read returns `'00'` and then `'10'`,
reaches none of them. **An `INVALID KEY` on either lookup is not an error** -- `2000-LOOKUP-XREF` and
`3000-READ-ACCOUNT` contain no abend site at all; each sets a status field and DISPLAYs, which is the
whole point of a preflight pass.

**A missing cross-reference or account row must not fail the batch chain.** That is the two siblings'
expectation, and it is stated here too because the distinction is easy to lose: preflight **reports**,
posting **rejects**. The same two conditions become reject reasons 100 and 101 in `CBTRN02C`, which
does write a reject stream and does grade the run; here they are console diagnostics and nothing else,
for the reason section 3.1 gives.

Trade-offs: `CBTRN01C` opens **six** datasets but reads only three, and this folder ships only the
three it reads. The customer master is opened `INPUT` at `:273`, the card master at `:309` and the
transaction master at `:345` -- performed from `:158`, `:160` and `:162` respectively -- yet the
program's only three `READ` statements are the feed at `:203`, the cross-reference at `:229` and the
account at `:243`. Those three masters therefore need only be **openable**; not one byte of their
content is ever examined. Shipping three further committed byte images to satisfy them was
**rejected**: each would be a fixture no assertion could reference, it would triple the provenance
surface section 9 has to attest for no gain, and a reader would reasonably infer the program reads
them. What the paired test supplies instead is empty-but-valid objects with the correct key geometry --
`account.customers`, `card.cards` and `ledger.transactions`, declared by the sibling harness and left
unseeded -- so the openable-but-unread contract is honoured with no committed bytes at all. The cost
accepted is that this folder does not mirror the program's dataset count, which is why the mismatch is
named here rather than left to look like an omission.

---

## 5. Parameters -- the files in this folder and their byte geometry

### 5.1 Inventory

| File | Copybook / record | RECLN | Bytes on disk | Records | Line ending | Organization / key |
|---|---|---:|---:|---:|---|---|
| `dailytran.txt` | `CVTRA06Y` `DALYTRAN-RECORD` | 350 | **351** | 1 | one trailing `LF` | SEQUENTIAL, no key |
| `cardxref.txt` | `CVACT03Y` `CARD-XREF-RECORD` | 50 | **51** | 1 | one trailing `LF` | INDEXED, `XREF-CARD-NUM` offset 0 length 16 (**PRIMARY**) |
| `acctdata.txt` | `CVACT01Y` `ACCOUNT-RECORD` | 300 | **301** | 1 | one trailing `LF` | INDEXED, `ACCT-ID` offset 0 length 11 |

`wc -l` returns **1** for each file, which equals its record count, and the folder contains **zero
`CR` bytes and zero NUL bytes**. Each file is therefore one record plus a single terminating newline,
per master sections 3.8 and 3.9. The `ASSIGN` names these bind -- `DALYTRAN`, `XREFFILE`, `ACCTFILE` --
are declared at `app/cbl/CBTRN01C.cbl:29-58`; the record lengths are the summed field widths of master
section 5.1, never a `RECLN` banner. **The zero-based offset ledgers are master section 5.1 and 5.2 and
are not reproduced here** (master section 1.3).

**The resolution chain, end to end.** Card `4859452612877065` at `dailytran.txt` offset 262 equals the
cross-reference primary key at `cardxref.txt` offset 0, whose row carries customer `000000007` at
offset 16 and account `00000000007` at offset 25, and that account equals the account master key at
`acctdata.txt` offset 0. Both links were measured on the committed bytes rather than assumed.

Assumptions: those two equalities **are** the scenario. The bytes are otherwise unremarkable seed rows,
and a fixture whose card silently stopped matching would still load, still decode and still satisfy
every geometry check -- it would simply exercise the `unmatched_card` path while claiming to be the
matched one. The consequence is that the two chain links deserve an explicit authoring-time assertion,
which is what the check below adds to the geometry check, and it matters more here than in the posting
domain because section 3.1 establishes there is no exit status and no reject stream to make such a
drift loud at run time.

### 5.2 Verifying the bytes and the chain

```bash
# WHAT: assert the byte geometry of all three files, and additionally assert the two relations this
#       scenario turns on -- that the feed's card matches the cross-reference key, and that the
#       account it resolves to matches the account master's key. Run from this directory.
# WHY : Assumptions: geometry alone cannot detect the failure that matters here. A row whose card no
#       longer matches is still 350 bytes and still decodes, so it passes every width and line-ending
#       check while quietly turning this scenario into its own sibling. Checking both links at
#       authoring time is what separates a fixture defect from a program defect before either is
#       attributed to the other.
python3 - <<'PY'
EXPECTED = {"dailytran.txt": (351, 350), "cardxref.txt": (51, 50),
            "acctdata.txt": (301, 300)}
for name, (size, width) in EXPECTED.items():
    raw = open(name, "rb").read()
    rows = [r for r in raw.split(b"\n") if r]
    widths = sorted({len(r) for r in rows})
    ok = (len(raw) == size and raw.count(b"\r") == 0
          and raw.count(b"\x00") == 0 and widths == [width])
    print(name, "size", len(raw), "expected", size, "| rows", len(rows), widths,
          "| CR", raw.count(b"\r"), "| NUL", raw.count(b"\x00"),
          "| OK" if ok else "| MISMATCH")
feed = open("dailytran.txt", "rb").read()
xref = open("cardxref.txt", "rb").read()
acct = open("acctdata.txt", "rb").read()
print("link 1: feed card", feed[262:278].decode("ascii"),
      "vs xref key", xref[0:16].decode("ascii"),
      "| OK the card resolves" if feed[262:278] == xref[0:16]
      else "| MISMATCH the card would not be verified")
print("link 2: resolved account", xref[25:36].decode("ascii"),
      "vs account master key", acct[0:11].decode("ascii"),
      "| OK the account is found" if xref[25:36] == acct[0:11]
      else "| MISMATCH the account would be reported missing")
PY
```

### 5.3 Field values, as observed

Offsets are zero-based; money is signed zoned with the sign folded into the last byte and no byte for
the decimal point, per master sections 3.3 and 3.7. **This program reads no monetary field at all** --
the decoded amounts below are recorded for completeness, not as expectations.

`dailytran.txt`, one `DALYTRAN-RECORD`:

| Field | Offset | Width | Bytes | Decoded |
|---|---:|---:|---|---|
| `DALYTRAN-ID` | 0 | 16 | `0000000000683580` | the identifier the skip message at `:183` would carry -- not reached here |
| `DALYTRAN-TYPE-CD` | 16 | 2 | `01` | unread |
| `DALYTRAN-CAT-CD` | 18 | 4 | `0001` | unread; unsigned, plain digits |
| `DALYTRAN-SOURCE` | 22 | 10 | `POS TERM` + 2 spaces | unread |
| `DALYTRAN-DESC` | 32 | 100 | `Purchase at Abshire-Lowe` + 76 spaces | unread |
| `DALYTRAN-AMT` | 132 | 11 | `0000005047G` | `+504.77` -- unread |
| `DALYTRAN-MERCHANT-ID` | 143 | 9 | `800000000` | unread |
| `DALYTRAN-MERCHANT-NAME` | 152 | 50 | `Abshire-Lowe` + 38 spaces | unread |
| `DALYTRAN-MERCHANT-CITY` | 202 | 50 | `North Enoshaven` + 35 spaces | unread |
| `DALYTRAN-MERCHANT-ZIP` | 252 | 10 | `72112` + 5 spaces | unread |
| `DALYTRAN-CARD-NUM` | 262 | 16 | **`4859452612877065`** | the key `:171` moves and `:229` **finds** |
| `DALYTRAN-ORIG-TS` | 278 | 26 | `2022-06-10 19:27:53.000000` | author-supplied input; unread |
| `DALYTRAN-PROC-TS` | 304 | 26 | 26 spaces | an un-posted timestamp value; see section 6 |
| `FILLER` | 330 | 20 | 20 spaces | input-DALYTRAN padding |

Only two fields of this record are read by the program: the card number at `:171`, and the identifier
at `:183` on the branch this scenario does not take. Everything else is displayed as part of the whole
record at `:168` and otherwise ignored.

Assumptions: those four offsets -- 262, 278, 304 and 330 -- are **not** peculiar to the daily feed.
`CVTRA06Y` `DALYTRAN-RECORD` is field-for-field identical to `CVTRA05Y` `TRAN-RECORD` across copybook
lines 5-18: same order, same `PICTURE` on every one of the fourteen entries, and only the
`DALYTRAN-` prefix in place of `TRAN-`. That identity is *why* the card number lands at 262 and the two
timestamps at 278 and 304 in both records, and the consequence is a naming discipline rather than a
decoding one: the two layouts are the same 350 bytes, so master section 2 makes the **file name** the
only thing that tells a 350-byte feed record from a 350-byte posted one. A file named `dailytran.txt`
must never hold a posted row, and nothing may rely on width to tell them apart. Note that identical
*layout* does not mean interchangeable *bytes*: master section 6.1 measures the input feed record's
trailing `FILLER` as SPACE and the posted record's as the low value, because that padding is a property
of which program last wrote the row rather than of the copybook both share.

`cardxref.txt`, one `CARD-XREF-RECORD` -- all three identifiers are DISPLAYed at `:236-238`:

| Field | Offset | Width | Bytes |
|---|---:|---:|---|
| `XREF-CARD-NUM` | 0 | 16 | `4859452612877065` |
| `XREF-CUST-ID` | 16 | 9 | `000000007` |
| `XREF-ACCT-ID` | 25 | 11 | `00000000007` |
| `FILLER` | 36 | 14 | 14 spaces |

`acctdata.txt`, one `ACCOUNT-RECORD` for account `00000000007`: status `Y`, `ACCT-CURR-BAL`
`00000001930{` = `+193.00`, `ACCT-CREDIT-LIMIT` `00000020650{` = `+2065.00`,
`ACCT-CASH-CREDIT-LIMIT` `00000002640{` = `+264.00`, open `2012-10-12`, `ACCT-EXPIRAION-DATE`
`2024-12-13`, reissue `2024-12-13`, both cycle fields `+0.00`, `ACCT-ADDR-ZIP` `A000000000`,
`ACCT-GROUP-ID` ten spaces at offset 112, `FILLER` 178 spaces at offset 122.

**Not one field of this record is read.** `3000-READ-ACCOUNT` reads the row by key and DISPLAYs a
fixed marker at `:249`; it inspects nothing it read. The row's only job is to **exist** under key
`00000000007`, so every value in it is inert -- including the blank `ACCT-GROUP-ID`, which is the
interest domain's disclosure-group key (master section 7.2.3). It is ten blanks in the seed row and is
carried verbatim; **it is not to be filled in here**, because preflight never reads a disclosure group
and a populated value would imply a lookup this program does not perform.

`ACCT-EXPIRAION-DATE` is spelled exactly as `app/cpy/CVACT01Y.cpy` spells it, preserved verbatim
because this is copybook-side naming; master section 9.3 confines the three spelling corrections to
target column names.

Assumptions: **every `FILLER` slice in this folder is padded with SPACE, `0x20`, and never with NUL.**
That covers the DALYTRAN `FILLER X(20)` at offset 330, the ACCOUNT `FILLER X(178)` at offset 122 and
the widened CARD-XREF `FILLER X(14)` at offset 36; the account slice was checked byte by byte and its
distinct-value set is exactly `{0x20}`. What governs this is master section 6.1's **measured** table,
not a rule derived from the `PICTURE` clause -- and master section 3.2 and 6.2 record that where the
general rule and a measured byte disagree, the measured byte wins. The consequence is that the padding
byte is a property of the corpus to be looked up rather than reasoned out: two record types in this
tree pad with ASCII `'0'` instead, and neither appears in this domain, so master section 6.2's
exceptions do not arise here and must not be applied by analogy.

### 5.4 Departures from a tree rule, named

| Departure | Rule | Reason |
|---|---|---|
| `cardxref.txt` is 50 bytes where its seed row is 36 | master section 3.10 | The copybook sums to 50; the shipped seed row omits the trailing `FILLER X(14)`. Authored at full copybook width with that `FILLER` space-padded |
| The folder holds three record files where the program opens six datasets | master section 2 | The other three are opened and never read (section 4), so a fixture for them would assert nothing |
| The domain is named `preflight` where the house tree names it `prepost` | master section 4.2 | Recorded in section 1; the two denote the same program and neither is wrong |

Assumptions: the card-xref widening is a **width** decision and nothing more. `cardxref.txt` is
authored at the full 50-byte copybook width; the shipped seed row measures 36 bytes only because
`FILLER X(14)` at offset 36 is absent from it, and the fixture is that row plus fourteen spaces.
Master section 3.10 admits a deliberate 36-byte card-xref as a legitimate variant, and **this scenario
does not use one** -- stated explicitly because the ruling permits both and silence would leave a
reader unable to tell a considered choice from an accident. The consequence is that the three
identifier fields sit at the offsets the copybook declares, so a reader who measured 36 bytes in the
seed and inferred a 36-byte fixture would mis-key the file; and had the 36-byte form been chosen, that
choice would have had to be named here just as plainly.

There is **no** line-ending normalization to declare in this folder: neither seed source ships `CRLF`,
so the `CR`-stripping the posting scenarios perform on their category-balance row has no counterpart
here. **No business-rule field is reshaped either** -- section 9 attests every row.

---

## 6. Determinism -- and what the blank timestamp means here

**`DALYTRAN-PROC-TS` is 26 spaces at offset 304, and in this input file those spaces are a genuine
value: the record has not been posted yet.** They are not a missing field, not a placeholder and not
the residue of a normalisation step.

Assumptions: master section 8.1 requires that meaning to be stated every time a blank timestamp is
shown, because the byte pattern a masking step produces is **identical** to the pattern an un-posted
record carries, and nothing in the bytes distinguishes them. The consequence is a target-side contract
this folder depends on: `ledger.daily_transactions.proc_ts` is declared **nullable** by the sibling
harness while `ledger.transactions.proc_ts` is `NOT NULL`, and that asymmetry exists precisely so an
un-posted feed row can be represented without inventing a timestamp for it. A reader who took these 26
bytes for a missing field would conclude the column ought to be `NOT NULL` and would break the feed
table. The companion field `DALYTRAN-ORIG-TS` at offset 278 is deterministic author-supplied input --
the ISO-shaped `2022-06-10 19:27:53.000000` -- so the record carries one populated timestamp and one
deliberately blank one, and the difference between them is the record's posting state.

**Neither timestamp is read, written or normalised anywhere in this domain.** `CBTRN01C` writes no
record at all, so master section 8.1's posting and interest rules -- assert the originating stamp, mask
the processing stamp -- do not engage. The field appears in this document only because the feed record
carries it and master section 8.1 requires its meaning stated wherever it is shown.

The one non-deterministic-looking property of this domain is the **doubled** console output of section
2.2, and it is deterministic too: exactly two lookups for one record on every run, not a race.
Everything else holds by construction -- every byte here is literal, there is no clock value, no random
identifier and no environment-derived string, and each test provisions and tears down its own
workspace, per master section 8.

---

## 7. Target-side contracts this scenario agrees with

The migrated pass is `job/PreflightDailyTransactionsJob`, the job bean
`preflight-daily-transactions`, and state 3 of the nightly `carddemo-daily-batch` machine. Four of its
contracts constrain what this folder may expect:

- **The banners are verbatim.** `START OF EXECUTION OF PROGRAM CBTRN01C` and
  `END OF EXECUTION OF PROGRAM CBTRN01C`, from `:156` and `:195`.
- **There is no graded tier to report.** An unresolvable record is a diagnostic, not a rejection, which
  is the target-side expression of section 3.1: the reference assigns no `RETURN-CODE`, keeps no reject
  stream and maintains no counter, so the pass completes cleanly or fails hard and has no third
  outcome.
- **The pass writes no row and mutates no row**, because every dataset is read-only. This scenario's
  negative expectation is therefore checkable as a row count as well as an absence of diagnostics.
- **One inspection per record read.** The target implements the clean loop rather than the baseline's
  repeated final pass (section 2.2), so the doubled markers are a baseline characteristic the target
  does not reproduce and that no expectation asserts a count for.

The rows land in the objects the sibling harness declares in
[`test-harness-schemas-and-foreign-tables.sql`](../../../db/testharness/test-harness-schemas-and-foreign-tables.sql)
under the `test` profile of `application-test.yml`: `ledger.daily_transactions` for the feed,
`account.card_xref` (`card_num CHAR(16)` primary key) and `account.accounts` (`acct_id BIGINT` primary
key) for the two lookups. Decoding on the Java side is done by `DailyTransactionMapper`,
`CardXrefRecordMapper` and `AccountRecordMapper` over the `common-lib` codecs `CopybookLayout`,
`FixedWidthCodec` and `ZonedDecimalCodec`, which are the only sanctioned decoders for these bytes. A
scenario owns only its own rows and never seeds another service's schema, per master section 11.3.

---

## 8. Boundaries

### 8.1 No golden file lives in this folder

There is **no `*.expected` file here and none is to be added.** This folder holds inputs. Unlike the
posting and interest domains, this one has no golden corpus at all in either tree -- the program
produces console diagnostics rather than records, so its expectation is stated as marker presence and
absence in section 3. `tests/**` remains reference-only and no golden is ever regenerated.

### 8.2 The oracle constants do not apply here

Master section 7.1.7's constants -- 300 daily records, 262 posted, 38 rejected, a conservation total,
50 category keys becoming 100 -- are `CBTRN02C` figures for the full seed cycle. **None applies to this
domain**: this program posts nothing, rejects nothing and touches no category balance. The only figures
that transfer are the record geometries, and they agree: the 350-byte feed record and the 300-byte
account record are the same layouts.

### 8.3 The sibling folders

[`../unmatched_card`](../unmatched_card/README.md) breaks the **first** link -- no cross-reference row
carries its card -- and [`../unmatched_account`](../unmatched_account/README.md) breaks the **second**
-- its cross-reference resolves correctly but the resolved account is not held. Read the three
together: this folder shows what a clean record looks like, and the two siblings show the two ways the
chain can fail and which diagnostic each produces.

---

## 9. Data governance and synthetic provenance

**The three record files in this folder are synthetic and seed-derived.** Every one is a published AWS
CardDemo sample seed row under `app/data/ASCII/`, selected rather than invented:

| File | Seed file | Seed row | Relationship to the seed row |
|---|---|---:|---|
| `dailytran.txt` | `app/data/ASCII/dailytran.txt` | **1** | **byte-for-byte identical** |
| `cardxref.txt` | `app/data/ASCII/cardxref.txt` | **21** | identical on `[0:36]`; fourteen spaces appended to reach the copybook's 50 bytes |
| `acctdata.txt` | `app/data/ASCII/acctdata.txt` | **7** | **byte-for-byte identical** |

The cross-reference row is row **21** of its seed rather than row 7 because that file is ordered by
card number, and account `00000000007` appears in it under card `4859452612877065`.

**These bytes represent no real person and no real account.** The seeds ship with the upstream
open-source project as fabricated demonstration data, and master section 11.1 carries the tree-level
attestation this scenario inherits. Identity and primary-account-number bytes -- the card number, the
customer identifier and the account identifier named throughout this document -- are taken unchanged
from the seed.

**Every business-rule field reshaped away from its seed value: none.** Not one. The matched path needs
no reshaping, because the seeds are already internally consistent: card `4859452612877065` resolves to
account `00000000007` in the seed cross-reference, and the seed account master holds that account. This
scenario is the seeds' own default state, which is the strongest provenance position a fixture in this
tree can occupy. **The only byte-level difference from any seed row anywhere in this folder is the
card-xref 36-to-50 `FILLER` widening** named in section 5.4, and width conformance is not a
business-rule field change, so it does not qualify the statement above. `dailytran.txt` and
`acctdata.txt` are verbatim.

**Every key deliberately omitted: none.** The chain resolves completely -- the card is present in the
cross-reference and the resolved account is present in the account master -- which is the entire point
of this scenario. Stated explicitly because omission is the technique the two siblings use and its
absence here is a decision rather than an oversight: `unmatched_card` withholds the cross-reference row
and `unmatched_account` withholds the account row, and each says so in its own README.

Trade-offs: this folder's three files are byte-identical to their equivalents in the reference-only
`tests/fixtures/prepost/happy_path/`, and that duplication is deliberate. Master section 4.2 records
that the house tree is reference-only and that this module's tests must read fixtures from this tree, so
the alternative would be for a Java test to reach across into `tests/**` -- coupling it to a path the
COBOL suite owns and could reorganise, and blurring which tree a failure belongs to. The cost accepted
is duplicated bytes; what is bought is one owner per file.

Master section 11.3 governs the rest and is not restated: no secret, credential, connection string or
endpoint appears in any file here, money never leaves fixed point, and nothing here modifies the COBOL
baseline or the parity oracle.

---

## 10. What drives this corpus, and what reads it

`dailytran.txt` in this folder **is a driven job input**, not a passive mirror. Master section 1.5
records it, and `PreflightDailyTransactionsJobTest` is the consumer: its matched-path case seeds the
feed from these committed bytes and seeds a cross-reference row resolving the card to an account that
exists, then asserts that neither diagnostic branch is reached. The card number it seeds is read from
this file rather than restated, so the chain link of section 5.1 is load-bearing at run time and not
only at review time.

`BatchFixtureContractTest` reads the folder as well, enumerating this scenario among all sixteen in the
tree and holding every file here to its declared geometry, its line endings, a successful decode under
its declared layout, and the discriminating relationships this scenario turns on. Between the two, an
edit to any of these three files is detected -- by the contract test for all three, and by the job test
for the feed record specifically.

Assumptions: the distinction between the three domains in this tree is deliberate and is stated rather
than left to inference, because master section 1.5 is explicit that most of the tree is mirrored and
only part of it is driven. `preflight/**` and `posting/**` are driven, the latter by
`PostTransactionsJobParityIT` against `tests/golden/posting/<scenario>`; `interest/**` is a mirror,
because `CalculateInterestJobTest` resolves its inputs from the reference tree under the repository
root instead. The consequence for a maintainer is that editing a byte in this folder changes what a run
asserts, so it is not a documentation-only change and cannot be reviewed as one.

---

*This README is the mandatory Explainability carrier for the three record files in this directory,
required by master section 10 and by user-specified Rule 1. `config/rule1/rule1_gate.py` decides the
written form of the rationale labels above, repository-wide and including Markdown, which is why they
are plain rather than emphasised. `config/checkstyle/checkstyle.xml` limits its audit set to `java`, so
no linter reads this prose. Whether each rationale names a real consequence, and whether every number
and line citation is true, are review obligations no lexical gate can decide.*
