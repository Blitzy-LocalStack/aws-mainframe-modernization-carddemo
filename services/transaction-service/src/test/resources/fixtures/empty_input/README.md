# empty_input -- a present-but-empty primary input

Scenario README for `fixtures/empty_input/`. It carries the documentation obligation for
this whole directory, because the three files beside it are fixed-width positional
records with no comment construct of any kind -- master section 3.1 establishes that the
reading layer rejects any row whose length is not exactly the record length, so a comment
line placed in one of them would be read as a malformed record -- and one of them has no
bytes at all. Sections 1 through 4 below are the four items master section 9.1 requires
of a scenario README, in its order: intent, the rule exercised, the expected outcome, and
the fixture bytes and governance.

Two documents own material this file deliberately does not repeat, and both are cited by
section number only. The encoding contract -- record width, field position, sign carriage,
implied decimal, line ending -- belongs to
[`tests/fixtures/README.md`](../../../../../../../tests/fixtures/README.md), referred to
throughout as the master. Everything true of all ten scenarios rather than of this one
belongs to the folder index at [`../README.md`](../README.md). Copying either here would
give this folder a private duplicate of a rule it does not own, and a reader who later
found the two versions in conflict would have no way to know which one the loader obeys.

---

## 1. Intent

The primary input is **present and carries no records**. `CBTRN02C` drives one
iteration of its read loop per daily-transaction record, so with no record there is
nothing to validate, nothing to post, nothing to write and nothing to reject. The
property this scenario pins is that **a zero-record run is a clean, successful no-op
rather than a failure**: the file opens, the first read reports end of data, and the
run finishes having changed nothing.

Folder section 1 lists this scenario's discriminating property as the file's
emptiness rather than as anything a record contains. It is the only scenario in the
folder whose primary input holds no record. The two table images beside it are
populated deliberately, which section 5.4 explains.

> **An empty input is not a missing input.**
>
> The folder name reads either way to a newcomer, which is exactly why leaving this
> implicit would be a defect. **A missing input file is an I/O error: the open itself
> fails. An empty input file is a valid run with nothing to do: the open succeeds and
> the first read returns end of file.** The two paths are separate in the source and
> can be told apart line by line.
>
> - **Missing.** `0000-DALYTRAN-OPEN.` at line 236 issues its open at line 238. A
>   status other than `'00'` takes the else arm opening at line 241, whose line 242
>   sets a result that is neither the all-clear condition nor the end-of-data
>   condition, so line 247 reports the failure and line 250 performs the abend
>   paragraph whose header is line 707. Not one loop iteration happens, and the run
>   does not complete normally.
> - **Empty.** That same open succeeds at line 239. The first read at line 346 then
>   returns status `'10'`, which line 351 maps to the end-of-data condition declared
>   at line 144, so line 361 sets the loop sentinel declared at line 146 and the loop
>   at line 202 ends having processed nothing. The run completes normally.
>
> This scenario is the second of those two, and only the second, which is the shape
> master section 7 prescribes for an `empty_input` scenario: what gets tested is a
> successful open followed by no data rather than an open that never succeeds. Section
> 4.1 answers in bytes which kind of empty it uses.

---

## 2. The rule it exercises

The rule is `CBTRN02C`'s no-work path: the read loop terminates on its first read, so
validation, posting, the reject write and the category-balance fork are all unreached.
Every line below was confirmed in
[`app/cbl/CBTRN02C.cbl`](../../../../../../../app/cbl/CBTRN02C.cbl), which is read as
specification and never modified.

| Anchor | Line | Role in this scenario |
|---|---|---|
| `PERFORM UNTIL END-OF-FILE = 'Y'` | 202 | opens the read loop; one iteration per daily-transaction record |
| `1000-DALYTRAN-GET-NEXT.` | 345 | the read paragraph; against a zero-byte input it reports end of data on the very first read |
| `1500-VALIDATE-TRAN.` | 370 | validation -- **never reached**, so nothing is validated |
| `2000-POST-TRANSACTION.` | 424 | posting -- **never reached**, so nothing is posted |
| `2500-WRITE-REJECT-REC.` | 446 | the reject write -- **never reached**, so no reject row is produced |
| `2700-A-CREATE-TCATBAL-REC.` | 503 | the category-balance create arm -- **never reached** |
| `2700-B-UPDATE-TCATBAL-REC.` | 526 | the category-balance update arm -- **never reached**, which is why the seeded balance is untouched |
| `2900-WRITE-TRANSACTION-FILE.` | 562 | the posted-record write -- **never reached** |
| `END-PERFORM.` | 219 | closes the read loop |
| `IF WS-REJECT-COUNT > 0` | 229 | the trailing reject gate; the counter never leaves the zero it is initialised to at line 186, so this test never fires |

The causal chain is why none of them runs. The loop at line 202 tests its sentinel before
each iteration and the body re-tests it at line 205 after the read. On a zero-record input
the read at line 346 sets that sentinel immediately, so the second test fails on the first
pass and the body's remaining work -- the counter at line 206, the validation call at line
210 and the mutually exclusive post and reject calls at lines 212 and 215 -- is skipped
entirely. The reject counter stays at the zero it starts from and the gate at line 229
never fires.

Two consequences of that chain are the whole content of this scenario: because
`2500-WRITE-REJECT-REC.` is unreached no reject row exists to inspect, and because
both arms of the category-balance fork are unreached the seeded balance keeps the
exact value the fixture loaded.

---

## 3. Expected outcome

Stated as rows, against the four tables that
[`V1__ledger.sql`](../../../../main/resources/db/migration/V1__ledger.sql) creates.

| Table | Expected state |
|---|---|
| `ledger.daily_transactions` | **zero rows** from `dailytran.txt` -- there is no record in it to load |
| `ledger.transactions` | **unchanged.** The one pre-existing row loaded from `transact.txt` is still present, still keyed `0000000000683580`, still carrying the amount **+504.77** and the same originating and processing timestamps. No new row is inserted |
| `ledger.transaction_rejects` | **zero rows.** No reject row is produced |
| `ledger.transaction_category_balances` | **unchanged.** The row keyed `00000000007` / `01` / `0001` still carries a balance of **+100.00** |

The run **completes successfully**. This is a no-op, not a failure and not an error:
nothing was there to process, so nothing was processed, and that is the correct
result rather than a tolerated one.

The `transactions` and `transaction_category_balances` expectations are deliberately
phrased as *unchanged* rather than as *empty*. Section 5.4 records why that is the
stronger claim.

---

## 4. Fixture bytes and governance

### 4.1 Files, widths, record counts, line endings

| File | Table it loads | Records | Bytes per record | Bytes on disk | Line ending |
|---|---|---|---|---|---|
| `dailytran.txt` | `ledger.daily_transactions` | **0** | not applicable -- no records | **0** | **none at all** |
| `transact.txt` | `ledger.transactions` | 1 | 350 (`CVTRA05Y`) | 351 | LF, one trailing newline |
| `tcatbal.txt` | `ledger.transaction_category_balances` | 1 | 50 (`CVTRA01Y`) | 51 | LF, one trailing newline |

Each populated file measures one byte more on disk than its record is wide, and that
byte is the terminating newline master section 3.3 calls for -- the reason a line
count of these files can stand in for a record count. LF is the only ending used
here, `tcatbal.txt` included; folder section 3.3 is the authority for that and
section 5.3 records what it costs.

`dailytran.txt` has no width, no count and no line ending, and those three blanks are
the point of the scenario rather than an omission. It is also the one file in this
folder that could not document itself even if the format allowed it: any byte
whatsoever, including a lone newline or a comment marker, would make it non-empty and
destroy the property under test. That is the narrowest case of the general reason this
README exists.

#### `dailytran.txt` is a genuine zero-length file

Both kinds are loosely called empty and the reading layer must be told which one it
faces. This one is unambiguous:

> `dailytran.txt` is a **genuine zero-length file -- 0 bytes, 0 records, `wc -l` of
> 0, no line ending, and not a single byte of any kind**. It is **not** a file
> containing one blank line.

That distinction decides which code path the fixture exercises, so it is not
cosmetic. Master section 3.3 establishes that a blank line is a zero-length record,
and master section 3.1 establishes that the reading layer rejects any row whose
length is not exactly the record length -- it does not pad a short row, does not
truncate a long one, and does not silently drop a blank line. A one-blank-line file
would therefore be a **malformed** 1-byte input that exercises the length-validation
failure path, which is a different test with a different expected outcome. Master
section 3.1 is equally explicit on the other side: the only input treated as empty is
a genuinely zero-byte dataset.

The choice follows measured precedent rather than being invented here:
`tests/fixtures/posting/empty_input/dailytran.txt` and
`tests/fixtures/posting/zero_balance/tcatbal.txt` are both 0 bytes, measured directly.

#### `tcatbal.txt`

The 50 bytes decompose by `CVTRA01Y` as follows. Master section 5.6 owns the position
table and is not reproduced.

| Field | Positions | Value in this fixture |
|---|---|---|
| `TRAN-CAT-KEY` | 1-17 | account `00000000007`, type `01`, category `0001` |
| `TRAN-CAT-BAL` | 18-28 | `0000001000{` |
| `FILLER` | 29-50 | 22 ASCII zero characters, **not spaces** |

The `FILLER` convention is master section 5.6's, and it is followed here for fidelity
to the seed row those bytes come from: the seed writes that range as zeros, and a
fixture that blanked it would differ from its own source in 22 of 50 bytes for a
reason unrelated to anything under test.

The balance decodes to **+100.00** under master sections 3.4 and 3.5, which own the
overpunch table and the implied decimal. It is worth stating because the field is easy to
misread by one decimal place: the trailing `{` supplies the low-order digit as well as the
sign, so +100.00 and not +10.00, which would be the different byte string `0000000100{`.

#### `transact.txt`

Positions 1-304 mirror the daily-transaction layout field for field. That mirroring is
a property of the programs rather than a convenience of this fixture: lines 425 to 436
of `CBTRN02C` move twelve fields one at a time out of the daily record and into the
posted one, name for name, and folder section 2 carries the measurement that the two
350-byte layouts agree in picture and in order throughout. Only the last two fields
distinguish a posted image from a feed record:

| Field | Positions | Value in this fixture |
|---|---|---|
| `TRAN-PROC-TS` | 305-330 | the 26-character literal `2022-07-18 00:00:00.000000` |
| `FILLER` | 331-350 | 20 spaces |

The processing timestamp is a fixed literal, and deliberately so. Master section 7
requires a fixture to carry no value that varies between runs, and in the baseline
this field is a clock read -- line 437 of `CBTRN02C` calls the timestamp routine and
line 438 moves its result into this field. A literal is the only form of that field a
repeatable assertion can be written against, and being a literal rather than a clock
read is precisely what keeps it deterministic.

One distinction avoids a false conflict with folder section 6: its requirement that
positions 305-330 be blank governs the **daily-feed** record, where the seed itself
carries 26 blanks, whereas the posted image here is the other side of the nullability pair
of section 5.4 and must carry a value. The originating timestamp at 279-304 is
load-bearing input in both files and literal in both.

`transact.txt` has **no ASCII seed to copy from**: `app/data/ASCII/` holds nine files and
none is named `transact.txt`, because in the baseline the posted-transaction dataset is an
**output** and never an input. Section 4.2 attests its bytes accordingly.

#### What this folder does not contain

There is no `acctdata.txt`, no `cardxref.txt` and no fifth file of any kind. Those two
records belong to the `account` and `card` schemas rather than to `ledger`, and
`CBTRN02C` itself is batch-service's program, so this folder holds the three record images
the `ledger` schema owns and nothing else. The reference tree makes the other choice for
its own reasons, so the difference is recorded rather than left to look like an oversight.

### 4.2 Provenance attestation

Folder section 7 mandates the three items below, and master section 10.3 is their
source. Both populated files carry identity-shaped data, so the attestation applies.

1. **Synthetic and seed-derived.** Positions 1-304 of `transact.txt`, including the
   sixteen-digit card number at positions 263-278, are taken **verbatim from record 1
   of**
   [`app/data/ASCII/dailytran.txt`](../../../../../../../app/data/ASCII/dailytran.txt);
   the two ranges were compared byte by byte and agree exactly. That number is written
   here as `************7065`, with
   `sha256=ebb257b3c85c7780eccdac5fac0798728353a140ae81c21b7d9875a8dca84a40` as the
   verifiable form: run the digest over positions 263-278 of either file and it
   matches, so the provenance claim stays checkable without a full primary-account-
   number-shaped literal sitting in prose. Trade-offs: a masked value cannot be read
   straight out of this document and compared by eye, which is the point -- the digest
   is the comparison instrument, and it is exact where an eye is not. The fixture bytes
   themselves are unchanged, because positions 263-278 ARE the byte contract section
   5.4 states and a fixture whose bytes were masked would no longer be the seed record
   it attests to.

   The account
   identifier `00000000007` at positions 1-11 of `tcatbal.txt` comes from the seed row
   keyed `00000000007010001`, which is **record 7 of**
   [`app/data/ASCII/tcatbal.txt`](../../../../../../../app/data/ASCII/tcatbal.txt) --
   a row that already carries that key, so no identifier was moved onto it.
   `dailytran.txt` is empty and therefore carries **no data at all**: no card number,
   no identifier, and nothing to attest beyond its emptiness.
2. **No real person and no real account.** Every account number, card number,
   customer name, merchant name and address byte in the two populated files comes from
   the published CardDemo demonstration seeds, which ship as fabricated demonstration
   data. They are demonstration values, not credentials, and they identify no real
   person and no real account.
3. **Which business-rule fields were reshaped away from the seed value.** Two, and
   only two.
   - `transact.txt` positions 305-330, `TRAN-PROC-TS`: the seed's daily-transaction
     record carries **26 blanks** there, and this fixture carries the literal
     `2022-07-18 00:00:00.000000`. The reason is the column contract of section 5.4
     together with the determinism requirement recorded in section 4.1.
   - `tcatbal.txt` positions 18-28, `TRAN-CAT-BAL`: seed record 7 carries
     `0000000000{`, which is +0.00, and this fixture carries `0000001000{`, which is
     +100.00. It matches the sibling `happy_path` scenario byte for byte so that
     "unchanged" is an observable claim rather than one indistinguishable from "never
     loaded" -- again section 5.4.
   - `dailytran.txt` holds no record at all, so its seed row is **absent** rather than
     reshaped. Absence is the scenario itself and not a reshaping, which is why it is
     recorded here as a third item and not counted among the two fields above.

No identity byte was altered in either file. Only the two business-rule fields above were
reshaped, which is the discipline master section 10.2 describes.

---

## 5. The decisions behind this scenario

### 5.1 Why the file is zero bytes rather than blank or absent

Alternatives Considered: three forms of "empty" were available, and they exercise
three different code paths, so the choice among them decides what the scenario tests.

| Option | What it actually exercises | Verdict |
|---|---|---|
| A true 0-byte file | The open succeeds and the first read returns end of data -- the clean no-work path this scenario exists to prove | **Chosen** |
| A file containing one blank line | One zero-length row presented to a fixed-width reader, which fails length validation -- the parser's error path. The file is also 1 byte rather than 0 | Rejected |
| Omitting the file entirely | A missing dataset, so the open itself fails -- the I/O error path of section 1, which is a different scenario | Rejected |

The second and third options are not weaker versions of the first; each lands
somewhere else entirely, and each would leave the no-work path untested while
appearing to cover it. Omitting the file would additionally contradict folder section
3.1, which commits every scenario here to the same three record images, so the folder
index would become false.

Trade-offs: a file of zero bytes is indistinguishable, on sight, from one somebody meant
to fill in and forgot, and no tool will flag the difference because there is no malformed
byte to find. The risk is accepted because both alternatives are worse, and it is mitigated
the only way it can be -- here, and in folder section 3.3, which counts the folder's
zero-length files as an expected result rather than a finding. Two files in this folder are
deliberately empty, this one and `zero_balance/tcatbal.txt`, for unrelated reasons.

### 5.2 What a zero-record run is expected to produce

Assumptions: a zero-record run is expected to **succeed**, and to produce **no reject
row at all**. Every reject reason the posting flow can raise depends on having a
record in hand to judge, so **reasons 100, 101, 102, 103 and 109 are all irrelevant
to this scenario** -- not merely unlikely, and not one of them a candidate for the
expected outcome. There is no record to reject. Reasons 100 through 103 are
validation outcomes raised from `1500-VALIDATE-TRAN.` at line 370, and 109 is a write
outcome raised from inside `2000-POST-TRANSACTION.` at line 424 and reachable only
once validation has already passed -- folder section 1.2 is the authority for that
ordering. Section 2 records that neither of those two paragraphs is entered here, so
no reason of either kind can arise.

A caution for whoever reads this next: **do not add a reject expectation to this
scenario.** An expected reject row here would directly contradict the property under
test, and the contradiction would be easy to miss, because a reject row is the normal
expectation in five of this folder's ten scenarios. A scenario that needs a reject
asserted already exists for each reason; folder section 1 lists them.

### 5.3 Why `tcatbal.txt` is LF where its seed is CRLF

Trade-offs: the seed
[`app/data/ASCII/tcatbal.txt`](../../../../../../../app/data/ASCII/tcatbal.txt) ships
with CRLF record terminators -- master sections 3.2 and 8 both name it among the
three seeds that do -- and this fixture is written LF-only, so it does not reproduce
its source byte for byte in that one respect.

The consequence of the alternative settles it. One physical line is read as one record of
fixed length, so a carriage return is not stepped over -- it lands inside the trailing
`FILLER` at positions 29-50 and carries the record to 51 bytes where 50 are declared, which
master section 3.1 rejects on length rather than loading. What is given up is byte-for-byte
fidelity to the seed's line endings; what is bought is a record that parses at its declared
width.

### 5.4 Why `transact.txt` carries a record at all

The primary input is empty, but the posted-transaction image beside it holds one
record. That reads as a contradiction until two separate reasons are named, and they
pull in the same direction.

Assumptions: the physical contract requires it.
[`V1__ledger.sql`](../../../../main/resources/db/migration/V1__ledger.sql) declares
`ledger.transactions.proc_ts` as `NOT NULL` at line 245, while
`ledger.daily_transactions.proc_ts` is nullable at line 397, and the migration
records its own reasoning for the pair at line 379. That asymmetry is why
`transact.txt` carries a real 26-character processing timestamp at positions 305-330
where a daily-transaction fixture carries 26 blanks, and it is why the two files exist
separately at all rather than one image serving both -- folder section 3.2 is the
authority. An empty posted-transaction image would leave the not-null side of that
pair with no input to exercise it.

Trade-offs: keeping the row also makes the assertion stronger, at the cost of a
scenario that looks less uniformly empty than its name suggests. With a pre-existing
posted row present, the claim in section 3 becomes *the pre-existing ledger rows are
unchanged* rather than merely *the table is empty*. That is a far better statement
about a no-op, because "empty" is indistinguishable from "the fixture never loaded" --
a fixture that failed to load and a correct no-op produce the same empty table, and
only a populated starting state tells them apart. The same reasoning applies to the
category-balance row and its +100.00 balance: an untouched non-zero value is
observable evidence that neither arm of the fork at lines 503 and 526 ran, where a
zero or absent row would prove nothing.

---

## 6. What consumes these fixtures

Assumptions: this is written as a contract rather than as a roster of the classes
presently in the module. A roster answers "what reads this today", which a reader can
settle with one search and which is wrong the moment a test is added; the contract answers
"what must a reader supply to use these rows", which does not change unless the rows do.

- **The consumer contract.** These images are read in two capacities.
  `com.carddemo.transaction.fixtures.TransactionFixtureContractTest` asserts the byte
  contract this document states -- record widths, record counts, the zero-byte primary
  input and the trailing newline -- so no claim here can drift without a test failing. The
  row expectations of section 3 are for a fixture-loading integration test against a real
  engine in `com.carddemo.transaction.repository`, which loads the images as a starting
  state. That is the whole intended consumption: the images are not request bodies, not
  golden masters and not inputs to any unit test. A test that needs a transaction shape
  rather than a table state constructs it in code instead.
- [`../../application-test.yml`](../../application-test.yml) -- the sibling profile.
  It pins schema resolution and the migration location for the module's tests. It
  carries no connection coordinates by design, and none are supplied here either --
  and it carries no clock either. Assumptions: the pinned instant that lets an
  assertion on a 26-character timestamp be repeatable is
  `TransactionRepositoryIT.FIXED_CLOCK`, declared at that class's lines 259 and 260
  as `Clock.fixed(Instant.parse("2022-07-18T00:00:00Z"), ZoneOffset.UTC)`. This
  bullet previously attributed the pin to the profile, which is the one file that
  positively documents its absence: "any clock or current-time property" is item 8
  of that profile's own exclusion list, and the rationale there names `Clock.fixed`
  as the mechanism precisely because `common-lib`'s `TimestampFormatter` takes the
  clock as a collaborator rather than reading the ambient one. Attributing an
  injected-determinism control to configuration is worse than leaving it uncited: a
  maintainer chasing a non-reproducible timestamp would have edited a profile that
  has no such key and concluded the mechanism was broken.
- [`V1__ledger.sql`](../../../../main/resources/db/migration/V1__ledger.sql) -- the
  migration that creates the four `ledger` tables section 3 names. These images are
  loaded into the schema it produces, never into a hand-built one.
- A real PostgreSQL instance supplied through Testcontainers. Folder section 5 gives the
  reason no in-memory stand-in is used: the index behaviour and key-ordered reads these
  rows exercise belong to the engine itself, so a green result against a substitute would
  say nothing about the behaviour being claimed. Where these rows feed a list path, that
  path advances by key rather than by ordinal position, which is why the order of records
  within a fixture is itself meaningful.
- [`tests/fixtures/README.md`](../../../../../../../tests/fixtures/README.md) -- the
  authoritative byte contract this document cites throughout and never restates.
- [`../README.md`](../README.md) -- the folder index that owns every convention cited
  here as "folder section N".

This scenario changes nothing under `app/`, `tests/`, `scripts/` or `samples/`. Those
trees are read here as the specification and as the source of the seed bytes, and
master section 8 states the same restriction for the seeds themselves. The framing
folder section 4.2 sets out is kept as well: the baseline programs and seeds are
described as they stand, the target is described by the behaviour it implements, and
where the two differ the difference is recorded as a difference -- not as something
mended.
