# CBTRN02C posting -- happy path (in-limit, non-expired, posts via 2700-B-UPDATE)

**Purpose.** This document is the scenario record for `fixtures/happy_path/`. It
states what the three fixed-width record files beside it represent, which
business rule they exercise, what a consumer should expect of them, and why each
non-obvious byte in them is the byte it is.

**Why it is mandatory rather than courteous.** The three files here --
`dailytran.txt`, `transact.txt` and `tcatbal.txt` -- are fixed-width positional
records, and a fixed-width record has no comment construct at all. A single added
byte of prose would change the record width, break the check that a file's line
count equals its record count, and shift every field after the insertion point.
Those files therefore carry no explanation of their own, and this document
discharges the whole of their Explainability obligation. Two authorities require
it: the project's Rule 1, Explainability, whose full text is available through
`review_rules` and whose scope is set out in specification section 0.8; and
master section 9.1, which words the per-scenario obligation as MUST and records
in its own note why it was strengthened back from "should".

**Sources of truth, and what this document deliberately does not contain.** Three
documents govern these bytes and none of them is restated here:

- the byte-encoding contract -- record widths, field positions, the
  zoned-decimal sign-overpunch table, line endings and the trailing-newline rule
  -- is [`tests/fixtures/README.md`](../../../../../../../tests/fixtures/README.md),
  cited below by section number only;
- the folder-wide conventions shared by all ten scenarios -- the inventory, the
  discriminator pairs, and the three folder-level decisions -- are
  [`../README.md`](../README.md), cited below by section number only;
- the rule these fixtures encode is
  [`app/cbl/CBTRN02C.cbl`](../../../../../../../app/cbl/CBTRN02C.cbl), and the
  physical contract the rows load into is
  [`V1__ledger.sql`](../../../../main/resources/db/migration/V1__ledger.sql).

Assumptions: citing those documents by section instead of copying from them is
what keeps one contract in one place. A reproduced overpunch table or offset
table becomes a second source of truth the moment the first one is corrected, and
a reader who finds two copies has no way to tell which is current.

Assumptions: no mechanical gate reads this folder. As `../README.md` section 9
records, the Java, TypeScript and Python documentation gates cover none of these
file types, so nothing will report this document if it is incomplete. It is
complete by construction rather than by enforcement, which is why each section
below names the specific consequence of the choice it records instead of asserting
that the choice was sound.

---

## 1. Scenario intent

One well-formed daily transaction clears both validation gates and posts. It
takes the update-existing arm of the category-balance fork, it touches nothing
belonging to another bounded context, and it produces exactly one posted
transaction row and no reject.

This is also the byte-shape template the rest of the folder is cut from. The
sibling scenarios are built by changing a single field of this record: two move
only the amount, to the credit limit exactly and to one cent past it; two move
only the transaction date, to the expiration date exactly and to one day past it;
and one empties a sibling file rather than editing the record at all.
`../README.md` section 1 carries that inventory and section 1.1 carries the two
discriminator pairs with their measured single-byte differences.

Assumptions: that template role is why the values in this scenario are not
arbitrary and should not be adjusted for convenience. Every delta in the folder
is measured against this record, so a field changed here silently changes what
nine other scenarios are a delta from -- and the pairs that isolate one field
would no longer isolate anything.

---

## 2. Rule exercised -- `CBTRN02C` `1500-VALIDATE-TRAN` (L370)

`1500-VALIDATE-TRAN` at L370 runs the card-number lookup and then, only if that
lookup left the failure reason at zero, the account lookup. This scenario is the
path on which every one of the four documented reject reasons declines to fire.

| Reject | Would be set at | Verbatim message text | Why it does not fire here |
|---|---|---|---|
| 100 | L385-L387 | `INVALID CARD NUMBER FOUND` | the card number resolves to a cross-reference row |
| 101 | L397-L399 | `ACCOUNT RECORD NOT FOUND` | the resolved account exists |
| 102 | L410-L411 | `OVERLIMIT TRANSACTION` | the inclusive limit gate at L407 takes its pass arm |
| 103 | L417-L418 | `TRANSACTION RECEIVED AFTER ACCT EXPIRATION` | the inclusive expiry gate at L414 takes its pass arm |

Both surviving gates are inclusive on the pass side. The limit gate at L407 reads
`IF ACCT-CREDIT-LIMIT >= WS-TEMP-BAL`, where the comparand is the cycle figure
computed at L403. The expiry gate at L414 reads
`IF ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS (1:10)`, comparing only the first ten
characters of the originating timestamp.

Assumptions: `ACCT-EXPIRAION-DATE` is quoted with the spelling the copybook
declares, and is not adjusted here or anywhere downstream in this document. The
declared name is the contract the baseline compiles against, so the spelling is
the identifier rather than a blemish on it; renaming it in prose would break the
one thing a reader does with these documents, which is search for a field name to
get from a fixture to the copybook that defines it.

Assumptions: the two gates are two sequential and unguarded `IF` blocks --
L407-L413 and then L414-L420 -- inside the same account-found branch, with
nothing between them that would skip the second. They are therefore not
alternatives: if both conditions failed, the reason moved at L417 would overwrite
the one moved at L410 and only 103 would be reported. This scenario passes both,
so neither fires and the distinction has no effect on its outcome. It is recorded
because reading the pair as an either/or is the natural misreading, and a sibling
scenario that fails one gate depends on knowing which of the two it has actually
isolated.

**The category-balance fork.** Because `tcatbal.txt` carries a row on the key the
paragraph composes, `2700-UPDATE-TCATBAL` reaches the update arm at L526,
`2700-B-UPDATE-TCATBAL-REC`, which at L527-L528 adds the transaction amount to
the balance it read and then rewrites the record. It does not reach the create
arm at L503, `2700-A-CREATE-TCATBAL-REC`. The create arm is the complement, and
`../README.md` section 1 records which scenario covers it.

**The account update.** L547-L551 is behaviour of the posting program rather than
of anything in this folder: L547 adds the amount to the account's current
balance, and because the amount is not negative the test at L548 sends it to the
cycle credit accumulator at L549 rather than the cycle debit accumulator at L551.
No fixture here represents the account record, for the reason given in section 5.

**The write.** L562, `2900-WRITE-TRANSACTION-FILE`, writes the posted record --
once.

---

## 3. Why the category balance is seeded non-zero

Assumptions: the fork described above selects on whether a row already exists on
the composed key, so the presence of a key-matching row in `tcatbal.txt` is what
forces the update arm at L526 instead of the create arm at L503. Presence alone
settles which arm runs.

Trade-offs: the starting balance is nevertheless non-zero, at +100.00, and that
is a separate choice from mere presence. It makes the arm observable in the
result rather than only in the control flow: the update arm produces 604.77,
which is a different number from the 504.77 a create-from-zero would produce, so
a consuming test that asserts on the resulting balance cannot pass while silently
taking the wrong arm. A row present with a zero balance would have satisfied the
fork and left the two arms indistinguishable by their output, which is precisely
the failure this byte exists to prevent. The cost accepted is that this is the
only scenario in the folder carrying a non-zero balance, so this field is one
place where the folder's records deliberately do not agree with one another.

---

## 4. Input fixtures

| File | Copybook | Record width | Records | Line ending |
|---|---|---|---|---|
| `dailytran.txt` | `CVTRA06Y` | 350 | 1 | LF, single trailing newline |
| `transact.txt` | `CVTRA05Y` | 350 | 1 | LF, single trailing newline |
| `tcatbal.txt` | `CVTRA01Y` | 50 | 1 | LF, single trailing newline |

The salient values, as decimals and identifiers rather than as bytes:

- `dailytran.txt` -- `DALYTRAN-ID` `0000000000683580`, type code `01`, category
  code `0001`, `DALYTRAN-AMT` +504.77, `DALYTRAN-CARD-NUM` `4859452612877065`,
  `DALYTRAN-ORIG-TS` `2022-06-10 19:27:53.000000`, and `DALYTRAN-PROC-TS` blank
  as 26 spaces.
- `transact.txt` -- identical through bytes 1-304, with `TRAN-PROC-TS`
  `2022-07-18 00:00:00.000000`.
- `tcatbal.txt` -- key `00000000007` / `01` / `0001`, and `TRAN-CAT-BAL` +100.00.

How those decimals are encoded as bytes is master sections 3.1, 3.4 and 3.5:
fixed width with no delimiters, the sign carried as an overpunch on the low-order
character, and the decimal point implied rather than stored. `../README.md`
section 4.1 records which rule lives where. None of it is repeated here.

Assumptions: `TRAN-CAT-BAL` decodes to +100.00 and to nothing else, and the value
has to be decoded from the bytes rather than taken from this sentence. The field
is eleven characters for a nine-digit-plus-two-decimal picture, and its trailing
overpunch is the positive-zero form, which supplies a low-order digit as well as
a sign; the eleven digits that result are then split by the implied two decimal
places. Reading the leading ten characters as the whole number and treating the
overpunch as a bare sign understates the row by a factor of ten. The distinction
is written down because +100.00 and +10.00 differ by one character in one
position, both look plausible, and the arithmetic in section 7 is the only thing
that would expose the error later.

Assumptions: `tcatbal.txt` pads its trailing `FILLER` with 22 ASCII zeros where
the other two files pad theirs with spaces. That is not an inconsistency
introduced here -- master section 5.6 records the convention as a property of the
seed, and a fixture is required to preserve it. The inconsistency between the
three files is inherited, and normalising it would make this record differ from
every other record of its layout in the repository.

Assumptions: money is exact fixed point at every hop, and the bytes are the
storage end of that contract. Downstream the same value is `NUMERIC(11,2)` in
PostgreSQL and a scale-2 `BigDecimal` in Java. Binary floating point and JSON
numbers are excluded from the money path entirely, because a value that reaches
either one has already lost the exactness these eleven bytes exist to carry.

---

## 5. Why three files and not five

Alternatives Considered: mirroring the reference-tree scenario file for file was
evaluated and rejected. `tests/fixtures/posting/happy_path` carries five record
files, adding `acctdata.txt` and `cardxref.txt`, and it needs them because the
program it drives opens the account and cross-reference files directly. That
program, `CBTRN02C`, is batch-service's. This service migrates the online
programs `COTRN00C`, `COTRN01C`, `COTRN02C` and `COBIL00C`, and it owns the
`ledger` schema alone: accounts, customers and the card cross-reference belong to
the `account` schema and cards belong to `card`. Carrying either of those two
files here would put another context's table image inside this context's test
tree, which is the coupling the schema-per-service boundary exists to prevent, and
it would leave two services holding an authoritative copy of one table's shape.
`../README.md` section 3.1 states the same decision at folder level.

Assumptions: that exclusion has a consequence worth stating rather than leaving a
reader to notice. The two account-side values the gates in section 2 compare
against -- the credit limit read at L407 and the expiration date read at L414 --
are not represented by any fixture in this folder and cannot be. They are
properties of the scenario narrative, taken from the account row the reference
tree uses, and they are what makes "in-limit and non-expired" true of this record.
A reader looking for them here should conclude they are out of scope by design,
not that a file is missing.

---

## 6. Why `dailytran.txt` and `transact.txt` are separate

Assumptions: `V1__ledger.sql` declares `ledger.transactions.proc_ts` as
`NOT NULL` while `ledger.daily_transactions.proc_ts` is nullable. That asymmetry
is the entire reason there are two files rather than one: each is the image of a
different table, and the two tables genuinely disagree about whether this one
column may be empty. `../README.md` section 3.2 records the decision at folder
level.

Assumptions: bytes 1-304 of `transact.txt` mirror `dailytran.txt` field for
field, and the licence for that is the program rather than a resemblance between
two neighbouring files. L425-L436 is a run of twelve consecutive
`MOVE DALYTRAN-x TO TRAN-x` statements -- identifier, type code, category code,
source, description, amount, merchant identifier, merchant name, merchant city,
merchant zip, card number and originating timestamp -- with nothing reformatted,
rounded or recomputed in transit. The single field not copied is the processing
timestamp, which L437-L438 derives instead. Byte equality over exactly bytes
1-304 is therefore the correct assertion to make about this pair, and it is exact
rather than approximate.

Alternatives Considered: the processing timestamp is the literal
`2022-07-18 00:00:00.000000`, chosen over two candidates that were both rejected.
The first was leaving it blank, as the reference tree's expected output for this
layout does. Those 26 spaces are a comparator masking artifact rather than a
value, and blank bytes cannot load into a `NOT NULL` column at all, so copying
them would produce a file that loads nothing. The second was reusing the
originating timestamp verbatim. It loads, but it makes the two columns
indistinguishable: a test that read `orig_ts` where it meant `proc_ts` would pass,
and the index on the processing timestamp would be exercised as though it ordered
by originating time. The literal chosen sits more than a month after the
originating timestamp, so neither column can stand in for the other. Its date is
traceable rather than invented: `2022-07-18` is the baseline's only business-date
injection, at `app/jcl/INTCALC.jcl` L22, `PARM='2022071800'`; `app/jcl/POSTTRAN.jcl`
injects no date at all, so posting supplies none to borrow.

Assumptions: master section 6.3 requires an input fixture to leave the processing
timestamp blank, and this file does not contradict that requirement -- it falls
outside its scope. Section 6.3 governs input fixtures to the compiled COBOL
programs, where the processing timestamp is the field the program itself writes
and the comparator then masks; that is exactly why the reference fixture tree
contains no `transact.txt` at all, the transaction file being an output there
rather than an input. This file is a different class of artifact: an
already-posted row loaded into PostgreSQL, where the column is data on the way in
rather than a result on the way out. The determinism principle behind section 6.3
is honoured in full, because the value is a fixed literal and is never read from a
clock, so two loads of this file are byte-identical.

Trade-offs: the trailing `FILLER` of `transact.txt` is 20 spaces rather than the
20 low-value bytes a compiled program leaves in that range. Those low values are
an artifact of an uninitialised record area rather than a value anyone wrote, they
are not printable, and this tree requires 7-bit ASCII so that its records survive
being diffed, grepped and read in a terminal. The compromise accepted is that
this file differs from a program-written record in every one of those 20
positions; nothing downstream can observe it, because `V1__ledger.sql` maps this
`FILLER` to no column and it exists only to reach the declared width of 350.

---

## 7. Expected outcome

- Exactly **one** posted transaction row, with an amount of +504.77.
- **Zero** rejects. None of the four reason codes in section 2 is set.
- The category balance moves from 100.00 to 604.77, by
  `100.00 + 504.77 = 604.77`.

That arithmetic is written out rather than described because it is the whole
observable difference between the two arms of the category-balance fork: the
update arm reaches 604.77 and a create arm starting from nothing would reach
504.77. Every figure above is an exact fixed-point decimal.

Assumptions: no return code and no tolerance band is stated for this scenario,
and none should be added. The gate that consumes these fixtures is Maven and
JUnit, and it is binary -- a test passes or it fails. A graded return code, and in
particular a warn level that counts as success, belongs to the COBOL suite's
condition-code rubric, which governs a different runner over a different tree and
carries no meaning in this one. Importing that vocabulary here would offer a
consumer a middle outcome that the runner cannot express.

---

## 8. Line endings and normalisation

Refactoring Rationale: the seed row `tcatbal.txt` derives from ends CRLF, and this
file stores the same record LF-only. The measurement behind that is in the seed
itself: across its 50 records it carries 49 carriage returns and 50 line feeds, so
its lines occupy both 50 and 51 bytes and only the last one ends without a
carriage return. Carrying the carriage return across would not be a cosmetic
difference here, because nothing delimits a fixed-width field: the stray byte is
absorbed into the trailing `FILLER` and pushes the record one byte past its
declared width of 50, which is a corrupt record rather than a formatting
preference. Master section 3.2 makes LF the preferred default and asks that a
deliberate CRLF retention be documented; there is no retention to document here,
so the normalisation is documented in its place.

Assumptions: this is a change of line ending and not a change of content, because
no byte inside the 50-byte record moves. That distinction is what lets section 9
list the normalisation among the differences from the seed row without counting it
as a reshaped field: the record still decodes field for field against `CVTRA01Y`,
so a reader comparing this file with its seed row sees one changed value and one
changed terminator rather than two changed values.

Each file ends with exactly one trailing newline, per master section 3.3, which is
what makes a file's line count equal its record count and turns a width check into
a one-line measurement. `../README.md` section 3.3 states the LF-only rule for the
folder as a whole and section 10 carries the commands that verify it, so neither
is reproduced here.

---

## 9. Provenance and data governance

This folder carries a primary account number in two of its three files, so the
attestation mandated by master section 10.3 applies to it. Its three required
items follow.

**1. The account-number and identity bytes are synthetic and seed-derived.**

- `dailytran.txt` is byte-identical to line 1 of `app/data/ASCII/dailytran.txt`,
  established by direct comparison of the two records rather than by assumption.
- `tcatbal.txt` derives from the `00000000007` / `01` / `0001` row of
  `app/data/ASCII/tcatbal.txt`, which is the row whose key this scenario's card
  resolves to.
- `transact.txt` has no seed row to derive from. `app/data/ASCII/` holds exactly
  nine files and no `transact.txt`, so this record is authored from the `CVTRA05Y`
  layout with its identity bytes taken from `dailytran.txt` -- seed-derived at one
  remove rather than directly. `../README.md` section 4.2 records the same absence
  as a folder-level fact. It is a fact about the seed set, not an omission in it.

**2. They represent no real person and no real account.** Every account number,
card number, name, merchant and address byte in this folder originates in the
upstream AWS CardDemo sample datasets, which are published as fabricated
demonstration data. None of it is a credential and none of it identifies a real
person or a real account.

**3. The business-rule fields reshaped away from their seed values.**

- `tcatbal.txt` `TRAN-CAT-BAL` is reshaped from the seed's 0.00 to 100.00, for the
  reason in section 3. The key bytes and the `FILLER` bytes are unchanged, so the
  balance is the only field in that record that differs from its seed row.
- `tcatbal.txt` is stored LF-only although its seed row ships CRLF. That is the
  line-ending normalisation of section 8 and not a content change.
- `transact.txt` `TRAN-PROC-TS` is populated rather than blank, for the reasons in
  section 6. Every other byte through 304 matches `dailytran.txt`.
- `dailytran.txt` carries no reshaped bytes at all.

The seed files cited above are reference-only. They are read to establish
provenance and are never edited.

Alternatives Considered: the account and card numbers here are seed-derived rather
than freshly minted, which is master section 10.2's reasoning applied to this
scenario. Generating new numbers from a range reserved for test data was weighed
against reusing the published synthetic values, and the seeds win on two counts.
The card-to-account chain stays internally consistent, because the seed rows
already agree with one another about which card resolves to which account, whereas
a minted number would have to be threaded through every related record by hand.
And these values inherit an attestation that already exists upstream, instead of
requiring a second generator that would itself need to be documented and attested
before anything derived from it could be trusted.

No credential, connection string, endpoint or account identifier appears in this
folder. A consuming test obtains its database coordinates from Testcontainers at
run time.

---

## 10. Companion artifacts

Two different kinds of consumer are worth separating here, because only one of
them exists and conflating them would misdescribe what these files are currently
proving.

A contract-level consumer does exist in this module. A fixture contract test reads
all three files in this folder and holds them to the byte values this document
states -- the composed category-balance key and its 100.00 balance, the blank
processing timestamp on the daily record against the populated one on the posted
record, and the shared identifier and originating timestamp across the two. That
is why the figures in sections 4, 6 and 7 are load-bearing rather than
descriptive: a value edited here without editing the record, or the reverse, is a
test failure rather than a documentation drift. `../README.md` section 5 is the
folder-level register of what consumes these fixtures and is the place that
bookkeeping is kept current.

Assumptions: a behavioural consumer is a separate matter and is **[planned]**. No
test in this service executes the validation and posting rule of section 2 against
these bytes, because the program that implements that rule belongs to another
service; what exists today pins the fixture bytes, not the outcome the scenario
describes. The distinction is recorded under the availability convention of master
section 9.3 so that a reader does not take the passing contract test as evidence
that the posting behaviour in section 7 has been demonstrated -- it has not, and
the expected outcome stated there remains a specification for a consumer rather
than a result already observed.

The complement to this scenario is the one that covers the create arm at L503,
reached when no row matches the composed key. `../README.md` section 1 is the
authority for which scenario that is and for the folder inventory as a whole; it
is not restated here, so that the inventory cannot disagree with itself across ten
documents.

Assumptions: there is no golden mirror for this folder and none is promised.
Master section 2.1 requires a byte-identically-named mirror under `tests/golden/**`
for the fixtures the COBOL suite consumes, and that requirement is addressed to
that tree; this folder is outside it. Every program this service migrates is an
online program with no golden-master pipeline behind it, so nothing here is
compared against an expected-output file, and a reader should not look for one or
add one on the assumption that it was overlooked.
