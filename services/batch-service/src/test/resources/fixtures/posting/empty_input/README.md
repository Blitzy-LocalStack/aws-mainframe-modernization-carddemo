# `posting/empty_input` -- an empty feed posts nothing and exits clean

> **Purpose.** Pin what the transaction-posting job does when its driving feed holds no
> records at all: it must open every dataset it needs, find no work, write no transaction and
> no reject, leave every other dataset untouched, report both of its counters as zero, and
> finish in the clean return-code tier. The scenario exists to separate **"no work" from
> "failure"** -- two outcomes a batch step conflates easily, and which are indistinguishable
> from the outside if the only thing checked is whether output appeared.
>
> **Source of truth.** `app/cbl/CBTRN02C.cbl` for the behaviour and `app/jcl/POSTTRAN.jcl`
> for the dataset contract, both reference-only and both cited below by line;
> `app/cpy/CVTRA06Y.cpy`, `app/cpy/CVACT03Y.cpy`, `app/cpy/CVACT01Y.cpy` and
> `app/cpy/CVTRA01Y.cpy` for the record layouts; the seed datasets under `app/data/ASCII/`
> for the bytes; and the tree-level [master contract](../../README.md) for every encoding
> rule, which this document cites by section rather than restating (master section 1.3).
>
> **Label form.** Rationales below are tagged `Alternatives Considered:`, `Assumptions:` and
> `Trade-offs:` -- plain, plural, colon retained, no emphasis markup. That is the one
> permitted written form fixed by `docs/CODE_DOCUMENTATION_STANDARD.md` and required of this
> tree by master section 1.4, and this whole file is pure ASCII for the reason that section
> gives: the labels are found by literal search before they are read by a person.

**This README is the mandatory Explainability carrier for the four record files beside it.**
Master section 1.2 records why one is needed: a fixed-width record file cannot carry a
comment of any kind, not even a header line, because every byte position is meaningful and a
comment occupying its own line is a physical row of the wrong length. `dailytran.txt` is the
extreme case in this folder -- it is zero bytes, so a single added byte would stop it being
an empty dataset at all -- and the other three are byte images in which a comment character
would occupy a contract byte and break the record length. The whole folder's Rule 1
obligation therefore lands here, and master section 10 makes the artifact mandatory rather
than courteous.

The section order is the one master section 10 mandates -- intent, then the rule cited by line,
then the outcome, then the bytes and their governance, then the provenance attestation -- so the
expected outcome in section 3 precedes the file inventory in section 5. Sections 4 and 6 to 8 sit
where the subject they cover belongs: the errors beside the outcome they qualify, and the
determinism, target-side and boundary statements after the bytes they refer to.

---

## 1. Purpose -- the condition this scenario pins

The scenario is one **genuinely zero-byte** daily-transaction feed alongside **three fully
populated** datasets: the card cross-reference, the account master and the
transaction-category balance. That shape is not a local invention; it is the `empty_input`
semantic fixed by master section 3.11, and the byte geometry below matches the reference
shape that section measures.

Four things must hold at once, and each is a way a batch step has actually gone wrong:

- the job **exits clean** rather than reporting a soft-warn or a failure;
- it **writes no reject**, so an empty feed never looks like a rejected feed;
- it **touches no balance**, so no account or category row moves by a cent;
- it **does not abend** on the open of an empty dataset, which is the failure mode a step
  acquires when it treats "nothing to read" as "cannot read".

It also exercises the **counter-summary path with zero counts**. Both counter lines are
emitted unconditionally at `app/cbl/CBTRN02C.cbl:227-228`, whatever the feed contained, so
this is the one scenario in which an off-by-one in the read loop surfaces as a non-zero count
on empty input -- a defect that every populated scenario hides, because there a count that is
one too high still looks plausible.

Alternatives Considered: emptying all four files rather than only the feed. Rejected because
a pass under that shape would also be consistent with a job that never successfully opened
anything, so the scenario could not distinguish "empty driver, healthy reference data" from
"nothing provisioned at all" -- and those two need different fixes. Keeping the cross-
reference, the account and the category balance intact proves the job opened all six datasets
successfully, found no work in the one that drives its loop, and left every other dataset
exactly as it found it. The unchanged-reference-data assertion in section 3 is only available
because those three files have content; against three empty files it would assert nothing.

---

## 2. The business rule, cited by program and line

`app/cbl/CBTRN02C.cbl` reaches end-of-file on its **first** read and skips its entire loop
body. The walk below was checked line by line against the file.

| Step | Line | What happens on an empty feed |
|---|---|---|
| Open all six datasets | `:195-200` | `DALYTRAN` INPUT `:238`, `TRANFILE` OUTPUT `:256`, `XREFFILE` INPUT `:275`, `DALYREJS` OUTPUT `:293`, `ACCTFILE` I-O `:311`, `TCATBALF` I-O `:329`. Every open succeeds |
| Enter the read loop | `:202` | `PERFORM UNTIL END-OF-FILE = 'Y'`, with `END-OF-FILE` still at its declared `'N'` (`:146`), so the loop is entered once |
| Read the feed | `:204` | performs `1000-DALYTRAN-GET-NEXT` (`:345-369`); the `READ` at `:346` returns file status `'10'` |
| Map the status | `:351-352` | `'10'` moves **16** into `APPL-RESULT`, and `88 APPL-EOF VALUE 16` at `:144` is what gives that number its meaning |
| Set the loop flag | `:360-361` | `APPL-EOF` is true, so `MOVE 'Y' TO END-OF-FILE` |
| Skip the whole body | `:205` | the inner `IF END-OF-FILE = 'N'` is now false, so **nothing between `:206` and `:216` executes** |
| -- no count | `:206` | `ADD 1 TO WS-TRANSACTION-COUNT` never runs, so the processed count stays 0 |
| -- no validation | `:210` | `1500-VALIDATE-TRAN` (`:370-378`) is never performed, so no reject reason can be set |
| -- no reject | `:214-215` | `ADD 1 TO WS-REJECT-COUNT` and `2500-WRITE-REJECT-REC` never run |
| Leave the loop | `:219` | `END-PERFORM`, with the flag now `'Y'` |
| Close all six datasets | `:221-226` | `:584`, `:602`, `:621`, `:639`, `:657`, `:676` |
| Report the counters | `:227-228` | both counter lines are emitted, each carrying zero |
| Grade the run | `:229-230` | `IF WS-REJECT-COUNT > 0` is false, so `MOVE 4 TO RETURN-CODE` is skipped and the code stays 0 |

The end-of-file handling, reproduced faithfully from `app/cbl/CBTRN02C.cbl:345-361`:

```cobol
 1000-DALYTRAN-GET-NEXT.
     READ DALYTRAN-FILE INTO DALYTRAN-RECORD.
     IF  DALYTRAN-STATUS = '00'
         MOVE 0 TO APPL-RESULT
     ELSE
         IF  DALYTRAN-STATUS = '10'
             MOVE 16 TO APPL-RESULT
         ELSE
             MOVE 12 TO APPL-RESULT
         END-IF
     END-IF
     IF  APPL-AOK
         CONTINUE
     ELSE
         IF  APPL-EOF
             MOVE 'Y' TO END-OF-FILE
```

Three statuses and only three: `'00'` continues, `'10'` ends the loop, and anything else
takes the error path at `:363-366`. An empty sequential dataset produces the second, which is
why this scenario never reaches the third.

---

## 3. Returns -- the expected outcome

Every row below is an **assertion**, not an observation, and the last one is the real payload
of the scenario.

| Assertion | Value | Authority |
|---|---|---|
| Return-code tier | **0**, the clean tier | `app/cbl/CBTRN02C.cbl:229-230` moves 4 only when the reject count exceeds zero, and here it is zero |
| Transactions processed, as reported | **0** | the counter line at `:227`, never incremented because `:206` does not run |
| Transactions rejected, as reported | **0** | the counter line at `:228`, never incremented because `:214` does not run |
| Posted transaction records | **0** | `2900-WRITE-TRANSACTION-FILE` at `:562` is reached only from `2000-POST-TRANSACTION` (`:424`), which requires a record |
| Reject records | **0** | `2500-WRITE-REJECT-REC` at `:446` is never performed |
| Category-balance rows created or updated | **0** | the create-versus-update branch at `:495-499` selects between `2700-A-CREATE-TCATBAL-REC` (`:503`) and `2700-B-UPDATE-TCATBAL-REC` (`:526`), and neither is reached |
| `cardxref.txt` after the run | **unchanged, byte for byte** | opened INPUT at `:275`; the job has no statement that writes it |
| `acctdata.txt` after the run | **unchanged, byte for byte** | opened I-O at `:311`, but `2800-UPDATE-ACCOUNT-REC` at `:545` is reached only from the posting path |
| `tcatbal.txt` after the run | **unchanged, byte for byte** | opened I-O at `:329`, same reasoning |

The reference-only oracle corroborates all of it independently: in
`tests/golden/posting/empty_input/` the return code is `0`, `tranfile.expected` and
`dalyrejs.expected` are both **0 bytes**, and `acctdat.expected` and `tcatbal.expected` are
**byte-identical** to this folder's `acctdata.txt` and `tcatbal.txt` -- 301 and 51 bytes,
compared byte for byte. There is no cross-reference expectation there at all, which is itself
the oracle's way of recording that this job never writes that dataset.

**Zero reject records is not the same as no reject dataset.** `app/jcl/POSTTRAN.jcl:34-38`
allocates `DALYREJS(+1)` with `DISP=(NEW,CATLG,DELETE)` and `LRECL=430` **unconditionally**,
and the job opens it OUTPUT at `:293` before it knows whether anything will be rejected, so a
clean run still produces the dataset -- empty. The migrated job does the same: `stageRejectStream`
at `services/batch-service/src/main/java/com/carddemo/batch/job/PostTransactionsJob.java:751-767`
allocates a new generation and stages it even when the reject count is zero, which is why the
oracle carries a zero-byte `dalyrejs.expected` rather than no file. An assertion phrased as
"no reject dataset exists" would fail against correct behaviour.

Assumptions: the expected code is 0 and **not** a distinct "nothing to do" code, because
`app/cbl/CBTRN02C.cbl:229-230` grades on **one** input -- whether the reject count exceeds
zero -- and has no other assignment to the return code anywhere in the program. The migrated
`dto/BatchReturnCode` models exactly three tiers, 0, 4 and 8, and deliberately models no
fourth, so "no work" and "clean work" share tier 0 by design rather than by omission. A
scenario expecting a separate code for an empty feed would be asserting against a tier the
contract does not define.

Trade-offs: this scenario's central assertion is **negative**. It asserts that nothing was
written and that two services were never invoked, rather than byte-diffing a produced output
against a golden record. A negative assertion is weaker evidence than a byte comparison,
because it passes for a job that did the right thing and equally for a job that did nothing at
all for the wrong reason. The compromise is accepted because there is no output to diff -- the
correct behaviour is the absence of output -- and it is compensated by asserting that the
three populated files are unchanged byte for byte, which a job that failed part way through
its unit of work would not satisfy.

---

## 4. Exceptions and errors -- what must not happen, and why it cannot

**No reject is written.** `1500-VALIDATE-TRAN` (`:370-378`) is never performed, so no
validation can fail and no reason code can be set. Master section 7.1 fixes the persisted
reject domain as exactly `{100, 101, 102, 103}`; none of the four is reachable here, because
each is set inside `1500-A-LOOKUP-XREF` or `1500-B-LOOKUP-ACCT` and neither paragraph is
entered.

**No abend occurs, and that is provable rather than hopeful.** `9999-ABEND-PROGRAM` is
performed from eighteen sites. Thirteen of them lie on this scenario's control path and each
one is guarded by an I/O status:

| Reachable abend site | Paragraph | Fires only when |
|---|---|---|
| `:250`, `:268`, `:287`, `:305`, `:323`, `:341` | the six open paragraphs | an `OPEN` returns a status other than `'00'` |
| `:363-366` | `1000-DALYTRAN-GET-NEXT` | the `READ` status is neither `'00'` nor `'10'` |
| `:596`, `:614`, `:633`, `:651`, `:669`, `:688` | the six close paragraphs | a `CLOSE` returns a status other than `'00'` |

The remaining five -- `:463` in `2500-WRITE-REJECT-REC`, `:492`, `:523` and `:541` in the
three category-balance paragraphs, and `:577` in `2900-WRITE-TRANSACTION-FILE` -- sit inside
paragraphs that run only after a record has been read, so they are unreachable in this
scenario for the same reason the counters stay at zero. A run whose opens and closes all
return `'00'` and whose single read returns `'10'` therefore reaches none of the thirteen.

**Opening an empty sequential dataset is a normal condition, not an error.** `OPEN INPUT` on
a zero-byte sequential file succeeds with status `'00'`; emptiness is discovered on the
first `READ`, as status `'10'`, and `:351-352` maps that to the `APPL-EOF` condition declared
at `:144`. Only a third status -- neither `'00'` nor `'10'` -- is treated as an error. There
is no code path in which an empty input is itself a diagnostic.

---

## 5. Parameters -- the files in this folder and their byte geometry

### 5.1 Inventory

| File | Copybook | RECLN | DDNAME / `ASSIGN` | Organization | Key |
|---|---|---:|---|---|---|
| `dailytran.txt` | `app/cpy/CVTRA06Y.cpy` | **0 bytes -- no record** | `DALYTRAN` | SEQUENTIAL | -- |
| `cardxref.txt` | `app/cpy/CVACT03Y.cpy` | 50 | `XREFFILE` | INDEXED | `XREF-CARD-NUM`, offset 0, length 16 |
| `acctdata.txt` | `app/cpy/CVACT01Y.cpy` | 300 | `ACCTFILE` | INDEXED | `ACCT-ID`, offset 0, length 11 |
| `tcatbal.txt` | `app/cpy/CVTRA01Y.cpy` | 50 | `TCATBALF` | INDEXED | `TRAN-CAT-KEY`, offset 0, length 17 |
| `README.md` | -- | -- | -- | -- | this file |

The `ASSIGN` names and organizations are the ones declared at `app/cbl/CBTRN02C.cbl:29-61`;
the record lengths are the summed widths of master section 5.1, never a `RECLN` banner. The
feed's declared length is 350 even though this scenario supplies no record of it, which is why
the row above states the width and the emptiness separately.

The posting job reads the cross-reference **by card number**, at offset 0 -- master section
4.3, since `app/jcl/POSTTRAN.jcl:32-33` mounts the base cluster only and never the
account-keyed alternate path. The single cross-reference row here is therefore addressed by
its card key, and its account id is payload rather than a second key.

### 5.2 On-disk sizes, line endings, and the zero-byte feed

| File | Size | Records | Bytes per record | `CR` bytes | Trailing newline |
|---|---:|---:|---:|---:|---|
| `dailytran.txt` | **0** | 0 | -- | 0 | none, and none is possible |
| `cardxref.txt` | **51** | 1 | 50 | 0 | exactly one `LF` |
| `acctdata.txt` | **301** | 1 | 300 | 0 | exactly one `LF` |
| `tcatbal.txt` | **51** | 1 | 50 | 0 | exactly one `LF` |

Each populated file is one record plus a single trailing `LF`, per master sections 3.8 and
3.9, and the whole folder contains **zero `CR` bytes**. These four sizes are the reference
shape master section 3.11 measures for an `empty_input` scenario.

Alternatives Considered: writing `dailytran.txt` as a single `LF` rather than as a zero-byte
file. Rejected because a lone newline is a **one-byte record**, not an empty dataset. Master
section 1.2 records that the loaders reject any physical row whose length is not exactly the
record length, do not pad a short row, and do not silently drop a blank line, and that the
only input treated as empty is a genuinely zero-byte dataset. A more permissive reader would
be worse rather than better: it would admit the newline as a malformed one-byte daily
transaction, which then fails validation and produces a **reject**, inverting the expected
return-code tier from 0 to 4 and failing in a shape that reads like a reader defect rather
than a fixture defect.

```bash
# WHAT: assert this scenario's whole byte geometry at once -- that the feed is exactly zero
#       bytes, and that each remaining file is one record of its declared width plus a
#       single trailing LF, with no CR anywhere. Run from this directory.
# WHY : Assumptions: the loaders enforce the record length exactly and treat only a
#       zero-byte file as empty (master sections 1.2 and 3.11), so the two mistakes worth
#       catching before a load are a feed that is one byte long rather than empty and a
#       populated record carrying a stray CR that pushes it one byte over its width.
#       Printing the CR count beside the size catches both in one pass; checking the size
#       alone reports the second as merely "52" and sends the reader looking at fields.
python3 - <<'PY'
EXPECTED = {"dailytran.txt": (0, None), "cardxref.txt": (51, 50),
            "acctdata.txt": (301, 300), "tcatbal.txt": (51, 50)}
for name, (size, width) in EXPECTED.items():
    raw = open(name, "rb").read()
    rows = [r for r in raw.split(b"\n") if r]
    widths = sorted({len(r) for r in rows})
    carriage_returns = raw.count(b"\r")
    print(name, "size", len(raw), "expected", size,
          "| rows", len(rows), widths, "expected width", width,
          "| CR", carriage_returns,
          "| OK" if len(raw) == size and carriage_returns == 0
          and widths == ([width] if width else []) else "| MISMATCH")
PY
```

### 5.3 Field values, as observed

Offsets are zero-based. Money is stored as a signed zoned field whose sign is folded into the
last byte and whose decimal point occupies no byte at all, per master sections 3.3 and 3.7;
the decoded column below is what those bytes mean, not a second encoding.

`cardxref.txt`, one `CARD-XREF-RECORD`:

| Field | Offset | Width | Bytes |
|---|---:|---:|---|
| `XREF-CARD-NUM` | 0 | 16 | `4859452612877065` |
| `XREF-CUST-ID` | 16 | 9 | `000000007` |
| `XREF-ACCT-ID` | 25 | 11 | `00000000007` |
| `FILLER` | 36 | 14 | 14 spaces |

`acctdata.txt`, one `ACCOUNT-RECORD`:

| Field | Offset | Width | Bytes | Decoded |
|---|---:|---:|---|---|
| `ACCT-ID` | 0 | 11 | `00000000007` | account 7 |
| `ACCT-ACTIVE-STATUS` | 11 | 1 | `Y` | active |
| `ACCT-CURR-BAL` | 12 | 12 | `00000001930{` | `+193.00` |
| `ACCT-CREDIT-LIMIT` | 24 | 12 | `00000020650{` | `+2065.00` |
| `ACCT-CASH-CREDIT-LIMIT` | 36 | 12 | `00000002640{` | `+264.00` |
| `ACCT-OPEN-DATE` | 48 | 10 | `2012-10-12` | -- |
| `ACCT-EXPIRAION-DATE` | 58 | 10 | `2024-12-13` | -- |
| `ACCT-REISSUE-DATE` | 68 | 10 | `2024-12-13` | -- |
| `ACCT-CURR-CYC-CREDIT` | 78 | 12 | `00000000000{` | `+0.00` |
| `ACCT-CURR-CYC-DEBIT` | 90 | 12 | `00000000000{` | `+0.00` |
| `ACCT-ADDR-ZIP` | 102 | 10 | `A000000000` | -- |
| `ACCT-GROUP-ID` | 112 | 10 | 10 spaces | blank |
| `FILLER` | 122 | 178 | 178 spaces | -- |

`ACCT-EXPIRAION-DATE` is spelled exactly as the baseline spells it at `app/cpy/CVACT01Y.cpy`
line 11. The misspelling is **preserved verbatim** here, because this is copybook-side naming
and the fixture describes the record as the baseline declares it; master section 9.3 records
that the three spelling corrections apply to target column names only.

`tcatbal.txt`, one `TRAN-CAT-BAL-RECORD`:

| Field | Offset | Width | Bytes | Decoded |
|---|---:|---:|---|---|
| `TRANCAT-ACCT-ID` | 0 | 11 | `00000000007` | account 7 |
| `TRANCAT-TYPE-CD` | 11 | 2 | `01` | -- |
| `TRANCAT-CD` | 13 | 4 | `0001` | -- |
| `TRAN-CAT-BAL` | 17 | 11 | `0000000000{` | `+0.00` |
| `FILLER` | 28 | 22 | 22 ASCII `'0'` | -- |

The three records form one coherent chain: card `4859452612877065` resolves to customer
`000000007` and account `00000000007`, that account exists in `acctdata.txt`, and the category
key `00000000007 / 01 / 0001` names the same account. Nothing in this scenario reads any of
it, but the chain is closed anyway, so the fixture stays valid if the scenario is ever
extended with a record.

**`FILLER` padding differs between these files by measurement, not by inconsistency.**
`cardxref.txt` and `acctdata.txt` pad with `0x20` SPACE while `tcatbal.txt` pads with ASCII
`'0'`, `0x30`. Both are the bytes master section 6.1 measures for those records, and master
section 6.2 records that the category-balance row is one of the two that contradict the
general padding rule of section 3.2, with the measured byte winning. Applying the general rule
to `tcatbal.txt` here would produce a row differing from both its seed and the oracle in 22
bytes that carry no data at all.

### 5.4 The two encoding normalizations relative to the seed lines

The three populated records are **not** byte-identical to the seed lines they come from, and
the difference is exactly two normalizations, both of them width and line-ending conformance:

| File | Normalization | Effect |
|---|---|---|
| `cardxref.txt` | 14 trailing spaces added | the seed row is the 36-byte trimmed form; the copybook form is 50 |
| `tcatbal.txt` | the `CR` stripped | the seed row ships `CRLF`; the fixture is `LF`-terminated |

Assumptions: both normalizations rest on rulings this tree already made, and stating them is
the difference between an accurate claim and a wrong one. The card-xref case is master section
3.10's 36-versus-50 ruling: `app/cpy/CVACT03Y.cpy` sums to 50 bytes but the shipped seed
measures 36 because the trailing `FILLER X(14)` is simply absent from the seed rows, and the
ruling is to author at the full copybook width with that `FILLER` space-padded. The
category-balance case is master sections 3.8 and 3.9 together with the loader's exact-length
enforcement of section 1.2: the seed is mixed `CRLF`, and a stray `0x0D` absorbed into the
22-byte `'0'` `FILLER` would push the record to 51 bytes and fail the load with every field
value correct. An unqualified claim that these rows are "verbatim from the seed" would be
inaccurate on precisely these bytes, which is why they are named rather than glossed.

---

## 6. Determinism -- present, and empty

**No timestamp is emitted anywhere in this scenario, so there is nothing to normalise and
nothing to assert.** The reasoning is short but it is not an omission:

- No feed record exists, so `app/cbl/CBTRN02C.cbl:436` never copies a `DALYTRAN-ORIG-TS` and
  `:437-438` never reads the clock into a `TRAN-PROC-TS`.
- No transaction record is written, so no output carries either 26-byte field.
- None of the three populated record layouts has a timestamp field at all. `ACCOUNT` carries
  three `X(10)` dates and no timestamp, and `CARD-XREF` and `TRAN-CAT-BAL` carry neither.

Master section 8.1's posting-domain rules -- assert the originating stamp, mask the processing
stamp -- therefore do not engage here, and its requirement that a scenario state which of the
two meanings a blank timestamp carries does not arise, because no blank timestamp appears in
any file in this folder. Everything else about determinism holds by construction: every byte
here is literal, there is no wall-clock value, no random identifier and no environment-derived
string, and the run is reproducible by master section 8.

Assumptions: this section is deliberately present and deliberately empty. Timestamp handling
is the single most error-prone part of comparing this domain's output, so a reader arriving
here needs to be able to tell that the subject was considered and found not to apply. Omitting
the section entirely would leave exactly the same page for a scenario that had forgotten it,
and a reader cannot distinguish "not applicable" from "overlooked" by absence.

---

## 7. Target-side contracts this scenario agrees with

The migrated job under test is `job/PostTransactionsJob`. On an empty feed its keyset walk
receives an empty first page and **terminates immediately**, and it still produces a summary:
the two counter lines are reported with zeros and the pass returns the clean tier, decided by
whether any record was rejected rather than by how many, which is the same test
`app/cbl/CBTRN02C.cbl:229` makes.

Three target-side contracts constrain what this scenario may expect:

- **`dto/BatchRunSummary` enforces the warn-tier biconditional.** For the posting job the
  soft-warn tier holds **exactly when** the rejected count exceeds zero, and its constructor
  checks the equivalence in both directions, so a summary claiming warn with nothing rejected
  and a summary claiming clean with records rejected are both refused. It also rejects a
  negative value in any of its four counters. A zero-reject run must therefore report tier 0
  with every counter zero and none negative -- which is precisely this scenario's expectation,
  and the constructor would refuse any other combination of the two.
- **`dto/BatchReturnCode` supplies tiers 0, 4 and 8** and deliberately models no separate "no
  work" tier, for the reason recorded in section 3.
- **`service/PostingValidationService` and `service/CategoryBalanceService` are never
  invoked**, and the **non-invocation is itself the assertion**. Both are reached only from
  the per-record path, so a run that touched either one on an empty feed would have processed
  a record that does not exist. Asserting zero interactions on both is the target-side
  equivalent of the COBOL never performing `1500-VALIDATE-TRAN` or `2700-UPDATE-TCATBAL`.

The database objects these rows load into are the ones the sibling harness declares in
[`test-harness-schemas-and-foreign-tables.sql`](../../../db/testharness/test-harness-schemas-and-foreign-tables.sql)
-- `ledger.daily_transactions` for the feed, `account.card_xref`, `account.accounts` and
`ledger.transaction_category_balances` for the three populated files, with
`ledger.transactions` and `ledger.transaction_rejects` as the two outputs that must stay
empty. A scenario owns only its own rows and never seeds another service's schema, per master
section 11.3.

**One distinction to keep straight.** The graded return-code rubric -- 0, 4, 8 -- belongs to
the COBOL parity suite alone, as master section 7.1.6 records. Maven, Surefire, Failsafe and
JUnit are binary pass or fail. The tier-0 expectation in this document is the **job's** return
code, asserted like any other fixture value; it is not a build status, and a Java build that
reports anything other than success is a failure.

---

## 8. Boundaries

### 8.1 No golden file lives in this folder

There is **no `*.expected` file here and none is to be added.** This folder holds inputs. The
expected-output oracle is the reference-only `tests/golden/posting/**` tree, which is read as
the authority and never written, and no golden is ever regenerated. `app/**`, `tests/**` and
`scripts/**` are reference-only in their entirety: these fixtures are an **additive mirror**
of the house tree's shape, never a move of it, and the house suite continues to run unchanged
on its own files.

### 8.2 Oracle constants that are not expectations for this scenario

Master section 7.1.7 lists the constants `tests/e2e/test_posting_cycle.py` fixes for the
**full 300-record seed cycle**: `_EXPECTED_DAILY = 300`, `_EXPECTED_POSTED = 262`,
`_EXPECTED_REJECTED = 38`, `_EXPECTED_CONSERVATION = Decimal("77954.70")`, and
`_EXPECTED_TCAT_INIT_KEYS = 50` becoming `_EXPECTED_TCAT_FINAL_KEYS = 100`. **None of them is
an expectation for this scenario**, and none may be used as one: this is a sample of one
record per reference dataset and none at all in the feed, so every count above is zero here.
Nothing in this folder contradicts those constants, because the same programs and layouts
produce both -- they simply describe a different input.

The two loader-geometry constants **do** agree with this folder, and the agreement is worth
noting because it is a cross-check rather than a coincidence: `_ACCT = (300, 11)` matches
`acctdata.txt`'s 300-byte record and 11-byte key, and `_TCAT = (50, 17)` matches
`tcatbal.txt`'s 50-byte record and 17-byte composite key, exactly as section 5.1 states them.

---

## 9. Data governance and synthetic provenance

**The data in this folder is synthetic and seed-derived.** The three populated records are
copied from the published AWS CardDemo sample seed datasets `app/data/ASCII/cardxref.txt`,
`app/data/ASCII/acctdata.txt` and `app/data/ASCII/tcatbal.txt`, with card
`4859452612877065` and account `00000000007` as the traceable keys. The account and
category-balance rows come from row 7 of their seeds; the cross-reference row comes from row
21 of its seed, which is where account `00000000007` appears in a file ordered by card number
rather than by account.

**It represents no real person and no real account.** Those seeds ship with the upstream
open-source project as fabricated demonstration data, and master section 11.1 carries the
tree-level attestation this scenario inherits.

**No business-rule field is reshaped away from its seed value in any of the three populated
records.** They carry the seed field values exactly as they stand -- balance, both credit
limits, all three dates, both current-cycle amounts, the ZIP, the blank group id, the category
key and the category balance are all the seed's own. This was verified by byte comparison:
`acctdata.txt`'s 300-byte record is byte-identical to row 7 of `app/data/ASCII/acctdata.txt`,
and `tcatbal.txt`'s 50-byte record is byte-identical to row 7 of `app/data/ASCII/tcatbal.txt`
once the `CR` is removed. The two normalizations named in section 5.4 are width and
line-ending conformance and are **not** business-rule field changes, so the two statements do
not conflict: no field value was altered, and two files differ from their seed lines only in
padding and in a line terminator.

**The only reshaping in this scenario is `dailytran.txt`, emptied to zero bytes, and the
emptying is deliberate rather than a missing file.** It is the scenario's entire subject. A
reviewer finding a zero-length file in a fixture folder is right to suspect an authoring
omission, so it is recorded here, in section 1, in section 5.1 and in section 5.2 that the
file is meant to be exactly zero bytes and that its emptiness is the condition under test.

Alternatives Considered: minting fresh reserved-range test primary account numbers with a
documented check-digit generator, rather than reusing the published synthetic seed rows. The
seeds were chosen for two reasons a generator cannot supply. First, a checksum-valid card
number is indistinguishable from a live number **by inspection**, so provenance has to be
attested rather than assumed, and a seed row can be pointed back to a published file and row
whereas a generated value's origin is a generator run nobody kept. Second, the seeds keep the
card, cross-reference, account and category chain internally consistent, because the programs
under test already validate against seed-shaped records. The cost accepted is that these
identity values are not unique to this tree -- the same card number appears in the house
fixture tree and in the seeds themselves -- and uniqueness was never the goal; traceability
was.

Master section 11.3 governs the rest and is not restated here: no secret, credential,
connection string or endpoint appears in any file in this folder, money never leaves fixed
point, and nothing here modifies the COBOL baseline or the parity oracle.

---

*This README is the mandatory Explainability carrier for the four record files in this
directory, required by master section 10 and by user-specified Rule 1. Exactly one gate reads
it: `config/rule1/rule1_gate.py` decides the **form** of the rationale labels above,
repository-wide and including Markdown, which is why they are written plain rather than
emphasised. Nothing else does -- `config/checkstyle/checkstyle.xml` limits its audit set to
`java`, so no linter reads a byte of the prose. Whether each rationale names a real
consequence, and whether every number and line citation here is true, are review obligations
that no lexical gate can decide, which makes the discipline more important here rather than
less.*
