```text
# =============================================================================
# services/batch-service/src/test/resources/fixtures/README.md
# -----------------------------------------------------------------------------
# Purpose:
#       The MASTER byte-encoding contract for the batch-service integration-test
#       fixture tree. This file authors no record bytes. It fixes, at byte
#       granularity, the record widths, field offsets, sign encoding, padding
#       bytes, line endings, business-rule expectations and determinism rules
#       that every fixture beneath this directory must satisfy. A reader holding
#       this document and the copybooks it cites can author any fixture in this
#       tree byte for byte, and can decide whether an existing one is correct.
#
# Source of truth:
#       The COBOL baseline. Record layouts come from the copybooks under
#       app/cpy/, behaviour from the programs under app/cbl/, dataset and
#       parameter contracts from the jobs under app/jcl/, and the measured byte
#       values from the seed datasets under app/data/ASCII/ and from the golden
#       masters under tests/golden/. Every number in this file was re-derived
#       from those sources rather than carried over from prose; section 5.1
#       records why summing declared field widths is the only admissible way to
#       obtain a record length here.
#
# Authority:
#       The widths, offsets, overpunch encoding and padding bytes below are
#       CONTRACTS, not guidance. Each <domain>/<scenario>/README.md in this tree
#       cites this document BY SECTION and must neither restate nor contradict
#       it (section 1.3). Where a general rule and a measured byte disagree, the
#       measured byte wins and section 6 is the arbiter.
#
# Scope:
#       app/**, tests/**, scripts/** and samples/** are REFERENCE-ONLY and are
#       never modified. Fixtures are DERIVED from them. Nothing in this tree
#       edits a baseline file, and nothing in this tree regenerates a golden.
# =============================================================================
```

---

## 1. Purpose and scope

### 1.1 What this tree is

This directory holds **fixed-width, positional record files** that stand in for the
VSAM datasets and sequential inputs that `batch-service` was migrated from. They are
the *data-in* side of parity verification, and they serve it in **two distinct ways**
that section 1.5 separates file by file. In the first, a test loads a scenario's records,
exercises the migrated Spring Batch job, and compares the result against the expectation
that the scenario's own `README.md` states. In the second, the records are a **mirror**
of a corpus the parity run reads elsewhere, held to their declared geometry and to the
discriminating values their scenario README states, so that an edit here is still
detected even though no job in this module consumes them.

Assumptions: the distinction is stated because reading the first sentence as universal is
the specific mistake it invites. Of the **51** record files in this tree, **five** are
opened as job input by a test in this module; the rest are mirrors. Presenting all of them
as driven would make every scenario README's expected-outcome section read as an assertion
some test performs, and for twelve of the sixteen scenarios it is instead a statement of
what the reference produces and what the migrated rule is asserted to produce elsewhere.

Every record file in this tree is **derived**: it is a copy or a subset of an ASCII seed
dataset under `app/data/ASCII/`, reshaped in non-identity business-rule fields for the
scenario at hand. The seeds are inputs to derivation and are never edited in place.
Section 9 fixes the derivation discipline and section 11 the provenance attestation that
follows from it.

The COBOL programs these fixtures drive are the specification, not a starting point. The
migrated Java encodes their behaviour; it does not redefine it. Where the Java's
behaviour departs from the baseline's, the departure is a **documented divergence** with
a stated reason, recorded in `docs/architecture/cobol-to-service-traceability.md` -- never
a silent correction, and never an edit to `app/**`.

### 1.2 Why this contract is a README and not a set of comments

A fixed-width record file **cannot carry a comment of any kind, not even a header line**.
Every byte position in a record is meaningful, so a `#` would be *data*; and a comment
occupying its own line is a physical row of the wrong length.

That is not a style preference, it is enforced. `tests/helpers/record_codec.py` and
`tests/helpers/vsam_loader.py` **reject** any physical row whose length is not exactly
the record length. They do not pad a short row, do not truncate a long one, and do not
silently drop a blank line. The **only** input treated as empty is a genuinely zero-byte
dataset (section 3.11). An author who miscounts a width therefore gets a hard failure at
load rather than a plausible-looking corrupt record -- a deliberate financial-integrity
stance, because a malformed monetary record must never be quietly coerced into a
well-formed-looking one.

A README is consequently the **only** carrier available for the reasoning behind these
bytes, and this document is that carrier for the whole tree.

Assumptions: nothing in this directory is machine-scanned for documentation.
`config/checkstyle/checkstyle.xml` sets `fileExtensions` to `java` at `Checker` level
(line 209), so a `.md` or `.txt` file here is outside the audit set before any
suppression is consulted; and the fixtures entry in
`config/checkstyle/suppressions.xml` (lines 188-189) is defensive belt-and-braces for the
narrow case of a `.java` file generated into this directory, not a load-bearing exemption.
**user-specified Rule 1 (Explainability) binds this file in full regardless** -- it is
simply enforced by review rather than by a linter, which makes the discipline more
important here, not less. That suppression covers
`src/test/resources/fixtures/` and **must never be widened to `src/test/java/`**: the
per-service tests are exactly where the business rules transcribed from the COBOL are
asserted, and `src/main/java/**` is never a candidate for suppression at all. The
boundary is recorded in the suppression's own comment at lines 178-186 and nothing in
this document may broaden it.

### 1.3 The two-tier rule -- what belongs here and what belongs in a scenario

This tree carries documentation at exactly two tiers, and the split is a hard constraint
rather than a convention:

| Tier | File | Holds |
|---|---|---|
| Tree | **this document** | The authoritative contract: widths, offsets, encoding, padding bytes, line endings, determinism, business-rule semantics, governance |
| Scenario | `<domain>/<scenario>/README.md` | Only what is specific to that scenario: its intent, the rule it exercises, its expected outcome, its per-file byte geometry and provenance |

A scenario README **cites this document by section** -- "widths per section 5.1", "padding
per section 6.1" -- and **must not restate or contradict it**.

Alternatives Considered: letting each scenario README repeat the ledger it depends on, so
that a reader never has to open a second file. Rejected because sixteen copies of a
ten-row table are sixteen things to keep in step, and the first one to fall behind
becomes a second source of truth that reads exactly as authoritative as this one. The
cost accepted instead is one indirection per lookup. The house master contract states the
same constraint in its own opening block, so a reader moving between the two trees meets
one rule rather than two.

### 1.4 How reasoning is labelled in this document

Every non-obvious assertion below carries its reasoning under one of Rule 1's category
names, written **plain, plural and unemphasised** with the colon retained:

```text
Alternatives Considered:
Assumptions:
Trade-offs:
```

Assumptions: `docs/CODE_DOCUMENTATION_STANDARD.md` fixes the one permitted written form of
these labels and names the emphasised spelling explicitly wrong in Markdown as well as in
code, because a reviewer auditing this repository against Rule 1's validation gate finds
every rationale by literal string search across seven languages, and no linter parses
prose in a Markdown file, a Dockerfile, a `.tf` file or a SQL migration. An emphasised
label is a rationale that search does not return. The rules document typesets the four
names in bold; that bold is its own typography and not part of the label. Every rationale
in this file is therefore written as `Assumptions: ` and not as an emphasised variant, which
also matches the ninety-odd sibling READMEs and the SQL, Terraform and Java rationales in
this same module, so one search finds them all in a single pass.

Rule 1's fourth category name -- the one its line 32 scopes to *replacing existing code* --
is **factually unavailable in this file**. This document is net-new and replaces nothing:
the COBOL baseline it derives from is untouched, and the house fixture tree it parallels
is a separate, reference-only tree that continues to run unchanged. Where a decision here
differs from the house tree's or from the baseline's behaviour, the difference is recorded
under `Alternatives Considered:` or `Assumptions:` instead.

Validation gate, per Rule 1 line 43: a contribution to this tree missing **either** the
documentation **or** the decision rationale fails review. Line 43 is conjunctive and
states the obligation as a requirement; line 29's softer phrasing is not the operative
wording and is not cited here.

### 1.5 Which scenarios drive a job in this module, and which are mirrors

Measured from the consuming classes rather than asserted:

| Scenario | Files this module opens as job input | Consumer |
|---|---|---|
| `preflight/happy_path` | `dailytran.txt` | `PreflightDailyTransactionsJobTest`, which seeds the feed from these bytes and seeds a cross-reference row resolving the card to an account that exists |
| `preflight/unmatched_account` | `dailytran.txt` | the same class, seeding the card to an account it deliberately does not create |
| `preflight/unmatched_card` | `dailytran.txt`, `acctdata.txt` | the same class, seeding **no** cross-reference row, and seeding the account through the production record mapper |
| `export/happy_path` | `acctdata.txt` | `ExportJobTest`, for the account phase of the export record |
| every other scenario | **none** | -- |

The two domains that are **not** driven from this tree are driven elsewhere, and the
elsewhere is specific:

- **`interest/*`** -- `CalculateInterestJobTest` resolves its fixtures under the repository
  root as `tests/fixtures/interest/<scenario>/<file>` and asserts each is a regular file, so
  it reads the **reference** tree and never this one. The three scenario directories here
  mirror it.
- **`posting/*`** -- the rule is asserted by `PostingValidationServiceTest` and
  `CategoryBalanceServiceTest` against values declared inside those classes, which cite
  `tests/fixtures/posting/...` in their documentation. The driven nine-scenario corpus with
  its own contract test lives in the sibling module at
  `services/transaction-service/src/test/resources/fixtures/`.

**Every file in this tree nonetheless has an executable consumer**, and it is
`services/batch-service/src/test/java/com/carddemo/batch/fixtures/BatchFixtureContractTest.java`.
That class asserts the scenario census as a closed set in both directions, the presence of the
README section 10 mandates for each, whole-record geometry against the layout each file name maps
to, LF-only line endings, the one-trailing-newline rule and its two exemptions, a successful decode
of every record under its declared layout -- which is what proves the sign-overpunch rule of section
3.3 across the corpus without restating the overpunch alphabet -- and the discriminating value or
relationship each scenario turns on.

Assumptions: the contract test deliberately does **not** re-run the jobs over these bytes.
Duplicating the two driven domains' runs would assert those jobs twice while leaving the
mirrored files exactly as unread as before, so what it asserts instead is the corpus: its
census, its geometry, its governance rules and the values the scenario documents state.

---

## 2. Directory organization and file naming

The layout is a two-level `domain / scenario` partition with one flat record file per
input record type, plus the mandated scenario README:

```text
services/batch-service/src/test/resources/fixtures/
  README.md                       <- this document (the tree-level contract)
  <domain>/<scenario>/README.md   <- the scenario-level contract (section 10)
  <domain>/<scenario>/<dataset>.txt
```

Record files are named for the **lowercase dataset base** plus `.txt`, matching the seed
they derive from and the `ASSIGN` name the baseline binds:

| File | Record type | Copybook | Width |
|---|---|---:|---:|
| `acctdata.txt` | ACCOUNT | `app/cpy/CVACT01Y.cpy` | 300 |
| `carddata.txt` | CARD | `app/cpy/CVACT02Y.cpy` | 150 |
| `cardxref.txt` | CARD-XREF | `app/cpy/CVACT03Y.cpy` | 50 |
| `custdata.txt` | CUSTOMER | `app/cpy/CVCUS01Y.cpy` | 500 |
| `dailytran.txt` | DALYTRAN | `app/cpy/CVTRA06Y.cpy` | 350 |
| `tcatbal.txt` | TRAN-CAT-BAL | `app/cpy/CVTRA01Y.cpy` | 50 |
| `discgrp.txt` | DIS-GROUP | `app/cpy/CVTRA02Y.cpy` | 50 |
| `trandata.txt` | TRAN | `app/cpy/CVTRA05Y.cpy` | 350 |

Assumptions: the file name is the only handle a test has on a record's layout, because the
bytes themselves carry no type marker. Naming a 350-byte daily-transaction file
`dailytran.txt` and a 350-byte posted-transaction file `trandata.txt` is what keeps the
two apart -- they are the same width and differ only in which copybook prefixes their
field names (section 5.1). A scenario that needs a second file of one type must extend the
base name rather than reuse a sibling's, and must say so in its own README.

This tree deliberately holds **no `.gitignore`** and no generated artifact. Every byte
here is authored and reviewed.

---

## 3. Encoding rules

This is the most error-prone area of the tree. Read it in full before authoring a byte.

### 3.1 Fixed width, positional fields, no delimiters

A field's meaning is determined **only** by its byte position within the record. There are
no commas, tabs or separators. Every physical row is **exactly** the record length for its
type, per the ledger in section 5.1. A row one byte short or long shifts every subsequent
field and corrupts the record -- see section 1.2 for why that surfaces as a hard load
failure rather than as bad data.

### 3.2 Padding -- the general rule, and the one that overrides it

The general three-way rule follows from the `PICTURE` clause:

| Field class | Pads | With |
|---|---|---|
| Text, `PIC X(n)` | on the **right** | space, `0x20` |
| Unsigned numeric, `PIC 9(n)` | on the **left** | ASCII zero, `0x30` |
| Signed numeric, `PIC S9(n)V99` | on the **left** | ASCII zero, `0x30`, with the sign carried in the **last** byte as an overpunch (section 3.3) |

**The measured `FILLER` table in section 6.1 OVERRIDES this general rule wherever the two
differ, and two records in this tree are such cases.** Do not apply the general rule to a
`FILLER` without checking section 6.1 first.

### 3.3 Zoned-decimal sign overpunch

A signed numeric field contains no `+`, no `-` and no literal decimal point. The sign is
folded into the **last byte** together with that byte's digit:

| Digit | 0 | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 |
|---|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|
| **Positive** | `{` | `A` | `B` | `C` | `D` | `E` | `F` | `G` | `H` | `I` |
| **Negative** | `}` | `J` | `K` | `L` | `M` | `N` | `O` | `P` | `Q` | `R` |

Read as two strings, in which **the position IS the digit value**:

- positive `{ABCDEFGHI` -- `{` is +0, `A` is +1, ... `I` is +9
- negative `}JKLMNOPQR` -- `}` is -0, `J` is -1, ... `R` is -9

Assumptions: this is the **EBCDIC** sign convention, and it is required rather than
optional. `tests/README.md` section 5.2 records that the baseline is compiled with
`-fsign=EBCDIC` and that the default ASCII convention misreads the overpunch and
**silently corrupts negative balances** -- silently being the operative word, because the
run still produces plausible numbers. The characters above are ASCII-printable; it is the
*convention* assigning them digit-and-sign meanings that is the EBCDIC one, and both
statements are true about different things.

The Java side decodes and encodes these fields with
**`ZonedDecimalCodec` from `common-lib`**, in its EBCDIC mode. It owns the two tables as
`POSITIVE_OVERPUNCH = "{ABCDEFGHI"` and `NEGATIVE_OVERPUNCH = "}JKLMNOPQR"`
(`services/common-lib/src/main/java/com/carddemo/common/codec/ZonedDecimalCodec.java`
lines 222 and 224). The two Python codecs hold the same tables under their own names,
`_POS_OVERPUNCH` / `_NEG_OVERPUNCH`, at `tests/helpers/record_codec.py` lines 137-138 and
`data-migration/src/carddemo_migration/copybook/zoned.py` lines 239-240.

Assumptions: per AAP Rule T2, one former `COPY` becomes exactly one import, and shared
codecs come only from `com.carddemo.common.*`. A test in this module must therefore never
re-declare an overpunch table locally. Two tables that agree today are two tables that can
disagree after one edit, and the disagreement would appear as a wrong cent rather than as
a compile error.

### 3.4 Worked vectors

Each vector below is present in a seed or a golden in this repository, so an author can
self-check against a real byte string rather than against an example:

| `PICTURE` | Value | Encoding | Where it appears |
|---|---|---|---|
| `S9(10)V99` | `+194.00` | `00000001940{` | `ACCT-CURR-BAL` of account `00000000001`, `app/data/ASCII/acctdata.txt` row 1, offsets 12-23 |
| `S9(09)V99` | `+504.77` | `0000005047G` | `DALYTRAN-AMT` of the first record of `app/data/ASCII/dailytran.txt`, offsets 132-142 |
| `S9(09)V99` | `-919.00` | `0000009190}` | a negative `DALYTRAN-AMT` in the same seed |
| `S9(04)V99` | `15.00` | `00150{` | `DIS-INT-RATE` of `app/data/ASCII/discgrp.txt` row 0, offsets 16-21 |
| `S9(09)V99` | `12.50` | `0000000125{` | `TRAN-AMT` of `tests/golden/interest/happy_path/transact.expected` row 0, offsets 132-142 |

Read the second vector carefully. `S9(09)V99` is **eleven** digit positions; `504.77` fills
them as `00000050477`; the final `7` takes a positive overpunch, giving `G`. So
**`0000005047G` is `504.77`.** It is **not** `+50.47` -- the `G` is the low-order signed
*digit*, not a sign character appended to a ten-digit body. Reading it the other way scales
every amount in the record by a hundred and leaves the field the right width, so nothing
downstream complains.

The fifth vector is the canonical interest result: `(1000.00 x 15.00) / 1200 = 12.50`, and
section 7.2 states precisely what that vector does and does not prove.

### 3.5 The signed-versus-unsigned false-positive class

`PIC 9(n)` is **unsigned** and carries plain ASCII digits with **no** overpunch. Several
unsigned fields in the seeds nonetheless end in a letter, because that letter is simply a
data character:

```text
3580010001P   2252010001P   1861010001P   2564010001P   4260030001O
```

Every one of those *looks* overpunched and none is. **Never sniff the last byte to decide
whether a field is signed -- consult the field's `PICTURE` in the section 5 layout tables.**
Sniffing turns a `P` in an unsigned identifier into `-7`, which produces a negative
identifier that is still the declared width and still all-legal characters.

### 3.6 The negative-zero carve-out

A `}`-overpunched zero decodes to zero and re-encodes as `{`, because the target types have
no negative zero to preserve. This is **the one documented non-byte-identical round trip**
in the zoned regime; the packed analogue is a `0xD`-signed zero re-encoding as `0xC`. A
fixture that deliberately carries `}` in a zero field must say so in its own README, because
a re-encode comparison will differ in that single byte and the difference is expected.

### 3.7 Implied decimal

`V` marks an implied decimal point that occupies **no byte**. `V99` means the last two
digits of the field are cents. **There is never a literal `.` in a stored money field.** In
`S9(09)V99` the eleven stored digits `00000050477` are `504.77` because the scale is
declared, not because any character marks it.

### 3.8 Line endings

Prefer **LF (`\n`) throughout**, and normalise a CRLF seed on the way in.

Three seeds ship CRLF, with this measured geometry:

| Seed | Size | Rows | CRLF terminators | Final terminator |
|---|---:|---:|---:|---|
| `app/data/ASCII/tcatbal.txt` | 2599 bytes | 50 | 49 | a bare LF |
| `app/data/ASCII/trancatg.txt` | 1116 bytes | 18 | 18 | CRLF |
| `app/data/ASCII/trantype.txt` | 433 bytes | 7 | 6 | a bare LF |

Assumptions: the loader treats each physical line as exactly one fixed-length record, so a
stray `\r` is absorbed as a data byte and pushes the row one over the record length. Note
that `tcatbal.txt` is *mixed* -- 49 CRLF rows and a final LF row -- which is why the count is
49 and not 50, and why a fixture derived from it must be normalised rather than assumed
uniform. A scenario that **intentionally** preserves CRLF must state that in its own
README; silence means LF.

### 3.9 Trailing newline

End every record file with **exactly one** trailing newline after the last record, so that
`wc -l` equals the record count and CI has a cheap integrity check. Add no blank line
between records and no second trailing blank line: an empty line is a zero-length record
that fails fixed-width parsing.

### 3.10 Measured seed geometry, and the card-xref ruling

Every figure below was measured from the committed bytes, not read from a header:

| Seed | Size | Rows | Bytes per record | Copybook width |
|---|---:|---:|---:|---:|
| `acctdata.txt` | 15050 | 50 | 300 | 300 |
| `carddata.txt` | 7550 | 50 | 150 | 150 |
| `custdata.txt` | 25050 | 50 | 500 | 500 |
| `dailytran.txt` | 105300 | **300** | 350 | 350 |
| `tcatbal.txt` | 2599 | 50 | 50 | 50 |
| `discgrp.txt` | 2601 | 51 | 50 | 50 |
| `trantype.txt` | 433 | 7 | 60 | 60 |
| `trancatg.txt` | 1116 | 18 | 60 | 60 |
| `cardxref.txt` | 1850 | 50 | **36** | **50** |

The 300-record daily-transaction geometry is the origin of the oracle's `_EXPECTED_DAILY`
constant (section 7.1).

**The card-xref 36-versus-50 ruling.** `app/cpy/CVACT03Y.cpy` sums to 50 bytes, but the
shipped seed measures 36 bytes per record (1850 / 50) because the trailing `FILLER X(14)`
is simply absent from the seed rows. **Author card-xref fixtures at the full 50-byte width
with that `FILLER` space-padded.** The house fixtures measure 51 bytes per line -- 50 plus
the LF -- with fourteen spaces in the tail, and that is the shape to reproduce. A scenario
that deliberately wants the 36-byte seed shape must say so explicitly in its own README.

Trade-offs: the 50-byte form is chosen over the seed's own 36-byte form even though the
seed is the provenance source. The compromise accepted is that a card-xref fixture is not
byte-identical to its seed row, so a reader diffing the two sees fourteen extra bytes and
must consult this section. What is bought is that one width -- the copybook's -- holds for
both the loader and the Java codec, and the alternative would make record length depend on
which file a row came from.

### 3.11 The `empty_input` semantic

`empty_input` means the **primary** input is a **genuinely zero-byte** file, and the other
files in that scenario stay fully populated. The house measurement is the reference shape:
`tests/fixtures/posting/empty_input/dailytran.txt` is **0 bytes** while its `acctdata.txt`
is 301 bytes, and its `cardxref.txt` and `tcatbal.txt` are 51 bytes each.

Assumptions: a zero-byte file and a file "containing no lines" are not interchangeable
here. Section 1.2 records that the only input treated as empty is a genuinely zero-byte
dataset, so a file holding a single newline is a zero-length record and a load failure, not
an empty input. The masters stay populated because the scenario is testing "the job opens a
valid but empty feed and posts nothing", not "the job cannot find its reference data".

### 3.12 Indexed inputs must be pre-sorted by key

`TCATBAL` is an indexed file in the baseline, so its rows are consumed in **key order**
regardless of a flat fixture's physical line order. Author indexed fixtures already sorted
by their key.

Trade-offs: out-of-key-order rows would often produce the same outcome, so this rule buys
readability rather than correctness in the common case. What it prevents is a reader having
to simulate the loader to work out which record the program sees first -- which matters
directly in section 7.3, where *which* account is the final one determines the expected
output.

---

## 4. Domains, jobs under test and scenarios

### 4.1 The four domains

| Domain | Baseline program | Baseline driver | Input record types | Scenarios |
|---|---|---|---|---|
| `posting/` | `CBTRN02C` | `app/jcl/POSTTRAN.jcl` | DALYTRAN, CARD-XREF, ACCOUNT, TRAN-CAT-BAL | `happy_path`, `boundary_exact_limit`, `boundary_expiry_equal`, `empty_input`, `reject_100_card_missing`, `reject_101_acct_missing`, `reject_102_overlimit`, `reject_103_expired`, `zero_balance` -- **9** |
| `interest/` | `CBACT04C` | `app/jcl/INTCALC.jcl` | TRAN-CAT-BAL, ACCOUNT, DIS-GROUP, CARD-XREF | `happy_path`, `default_fallback`, `zero_balance` -- **3** |
| `preflight/` | `CBTRN01C` | **none in the baseline** | ACCOUNT, CARD-XREF, DALYTRAN | `happy_path`, `unmatched_account`, `unmatched_card` -- **3** |
| `export/` | `CBEXPORT` / `CBIMPORT` | -- | ACCOUNT, CARD, CUSTOMER, CARD-XREF, TRAN | `happy_path` -- **1** |

Sixteen scenarios, therefore sixteen scenario READMEs (section 10).

`CBTRN01C` **has no JCL driver anywhere in the baseline** -- nothing under `app/jcl/`
executes it, and only an integration test drives it. It is migrated regardless, because the
AAP assigns it to this service as the pre-posting preflight step and the batch chain
invokes it as a state of its own.

### 4.2 Two naming facts, and two deliberate absences

Assumptions: the `preflight` / `prepost` naming correspondence is real and neither name is
wrong. The house tree calls this domain **`prepost`** (`tests/fixtures/prepost/**`), while
the AAP and this module name the job **preflight-daily-transactions**. This tree uses
**`preflight/`** to match the job name a reader will find in the module's own sources and
in the batch state machine. Recorded here so that nobody "corrects" one tree to the other:
the two directory names denote the same `CBTRN01C` behaviour, and the house tree is
reference-only in any case.

Assumptions: `statement` and `provisioning` are **deliberately absent** from this tree even
though the house tree has both. Their programs are not this service's: the AAP assigns
`CBSTM03A` and `CBSTM03B` to **reporting-service**, and `CBACT01C`, `CBACT02C`, `CBACT03C`
and `CBCUS01C` to **account-service** and **card-service**. A fixture for a program this
module does not own would be a fixture no test here can drive, and would put the same
record layout under two owners. Recorded so their absence never reads as an oversight.

### 4.3 Two cross-reference access paths need two fixture shapes

The two jobs read `CARD-XREF` through **different keys**, and a fixture that satisfies one
does not automatically satisfy the other:

- **Posting reads by card number.** `app/jcl/POSTTRAN.jcl` lines 32-33 mount the base
  cluster only. The key is `XREF-CARD-NUM`, `PIC X(16)` at zero-based offset **0**.
- **Interest reads by account id.** `app/jcl/INTCALC.jcl` lines 29-30 mount the base
  cluster **and** lines 31-32 mount a second DD, `XREFFIL1`, pointing at the
  alternate-index PATH. `app/cbl/CBACT04C.cbl` line 38 declares
  `ALTERNATE RECORD KEY IS FD-XREF-ACCT-ID`, and the alternate is defined `WITH DUPLICATES`.
  The key is `XREF-ACCT-ID`, `PIC 9(11)` at zero-based offset **25**.

A posting fixture must therefore make every daily transaction's card resolvable at offset 0,
and an interest fixture must make every category balance's account resolvable at offset 25.
The migrated target of the alternate path is the secondary index
`idx_card_xref_account_id` on `account.card_xref(account_id)`, declared by the sibling harness
at `services/batch-service/src/test/resources/db/testharness/test-harness-schemas-and-foreign-tables.sql`
line 301.

Assumptions: `WITH DUPLICATES` means the account-keyed path may legitimately return more
than one row, so an interest fixture is free to map two cards to one account. A fixture that
relies on the alternate path returning exactly one row is relying on its own data shape, not
on the access path, and must say so in its own README.

---

## 5. Record-length ledger and layout reference

### 5.1 The ledger, and why lengths are summed rather than read

**Never derive a record length by parsing a `RECLN` banner comment.** The banners are
inconsistent across the baseline, in four distinct styles:

| Style | Example | Copybooks |
|---|---|---|
| `RECLN = n` | `RECLN = 350` | `CVTRA01Y`, `CVTRA02Y`, `CVTRA05Y`, `CVTRA06Y` (each at line 2) |
| `RECLN n`, no equals sign | `RECLN 300` | `CVACT01Y`, `CVACT02Y`, `CVACT03Y`, `CVCUS01Y` (each at line 2) |
| prose | `Total Record Length: 500 bytes` | `CVEXPORT` (line 5) |
| **no banner at all** | -- | `CSUSR01Y`, whose header is the Apache copyright block |

Assumptions: a parser written against any one of those styles reports nothing for the other
three, and reporting nothing is worse than failing, because a missing length is easy to
default. Summing the declared field widths is the only method that works on all four and
is the only admissible method here. Every length below was obtained that way.

| Record | Copybook | Composition | Length |
|---|---|---|---:|
| `TRAN-CAT-BAL-RECORD` | `app/cpy/CVTRA01Y.cpy` | `TRAN-CAT-KEY` = acct `9(11)` + type `X(02)` + cat `9(04)` (**17 bytes at offset 0**) + `TRAN-CAT-BAL S9(09)V99` (11) + `FILLER X(22)` | **50** |
| `DIS-GROUP-RECORD` | `app/cpy/CVTRA02Y.cpy` | `DIS-GROUP-KEY` = group `X(10)` + type `X(02)` + cat `9(04)` (**16 bytes at offset 0**) + `DIS-INT-RATE S9(04)V99` (6) + `FILLER X(28)` | **50** |
| `CARD-XREF-RECORD` | `app/cpy/CVACT03Y.cpy` | card `X(16)` at 0 + cust `9(09)` at 16 + acct `9(11)` at **25** + `FILLER X(14)` at **36** | **50** |
| `ACCOUNT-RECORD` | `app/cpy/CVACT01Y.cpy` | see section 5.2; `ACCT-ID 9(11)` at 0, `ACCT-ADDR-ZIP X(10)` at **102**, `ACCT-GROUP-ID X(10)` at **112**, `FILLER X(178)` at **122**; carries the baseline spelling **`ACCT-EXPIRAION-DATE`** at line 11 | **300** |
| `CARD-RECORD` | `app/cpy/CVACT02Y.cpy` | carries the baseline spelling **`CARD-EXPIRAION-DATE`** at line 9; `FILLER X(59)` | **150** |
| `CUSTOMER-RECORD` | `app/cpy/CVCUS01Y.cpy` | 332 declared bytes + `FILLER X(168)` at line 23 | **500** |
| `TRAN-RECORD` | `app/cpy/CVTRA05Y.cpy` | see the field table in section 5.2 | **350** |
| `DALYTRAN-RECORD` | `app/cpy/CVTRA06Y.cpy` | **field-for-field identical to `CVTRA05Y`, at the same line numbers**; only the `DALYTRAN-` name prefix differs | **350** |
| `EXPORT-RECORD` | `app/cpy/CVEXPORT.cpy` | a **40-byte shared prefix** + `EXPORT-RECORD-DATA X(460)`; the only base copybook using `COMP` / `COMP-3` | **500** |
| `REJECT-RECORD` | `app/cbl/CBTRN02C.cbl` lines 176-178 | `REJECT-TRAN-DATA X(350)` + `VALIDATION-TRAILER X(80)`, the trailer being `WS-VALIDATION-FAIL-REASON PIC 9(04)` + `WS-VALIDATION-FAIL-REASON-DESC PIC X(76)` (lines 180-182) | **430** |

The arithmetic, written out so that an author can check a length without re-adding the table:

| Record | Sum | Length |
|---|---|---:|
| `TRAN-RECORD` / `DALYTRAN-RECORD` | `16 + 2 + 4 + 10 + 100 + 11 + 9 + 50 + 50 + 10 + 16 + 26 + 26 + 20` | 350 |
| `TRAN-CAT-BAL-RECORD` | `11 + 2 + 4 + 11 + 22` | 50 |
| `DIS-GROUP-RECORD` | `10 + 2 + 4 + 6 + 28` | 50 |
| `CARD-XREF-RECORD` | `16 + 9 + 11 + 14` | 50 |
| `CUSTOMER-RECORD` | `332 + 168` | 500 |
| `EXPORT-RECORD` | `40 + 460` | 500 |
| `REJECT-RECORD` | `350 + 4 + 76` | 430 |

`ACCOUNT-RECORD` and `CARD-RECORD` have too many fields to line up usefully here; their
per-field breakdowns are in section 5.2 and in the copybooks themselves.

The `CVTRA05Y` / `CVTRA06Y` identity means a 350-byte row is ambiguous on its bytes alone;
section 2 records how the file name disambiguates it.

### 5.2 `CVTRA05Y` / `CVTRA06Y` field table, zero-based offsets

The widths sum to exactly 350: `16+2+4+10+100+11+9+50+50+10+16+26+26+20`.

| Field | `PICTURE` | Bytes | Zero-based offset |
|---|---|---:|---:|
| `TRAN-ID` | `X(16)` | 16 | 0 |
| `TRAN-TYPE-CD` | `X(02)` | 2 | 16 |
| `TRAN-CAT-CD` | **`9(04)`** | 4 | **18** |
| `TRAN-SOURCE` | `X(10)` | 10 | 22 |
| `TRAN-DESC` | `X(100)` | 100 | **32** |
| `TRAN-AMT` | `S9(09)V99` | 11 | **132** |
| `TRAN-MERCHANT-ID` | `9(09)` | 9 | 143 |
| `TRAN-MERCHANT-NAME` | `X(50)` | 50 | 152 |
| `TRAN-MERCHANT-CITY` | `X(50)` | 50 | 202 |
| `TRAN-MERCHANT-ZIP` | `X(10)` | 10 | 252 |
| `TRAN-CARD-NUM` | `X(16)` | 16 | **262** |
| `TRAN-ORIG-TS` | `X(26)` | 26 | **278** |
| `TRAN-PROC-TS` | `X(26)` | 26 | **304** |
| `FILLER` | `X(20)` | 20 | **330** |

**`TRAN-CAT-CD` is `PIC 9(04)` -- unsigned.** A generated interest transaction's category is
therefore stored as the plain digits `0005` with **no overpunch** (section 7.2). Applying the
signed rule to it would put an `{` in the last byte and turn `0005` into `000` plus a sign.

The `ACCOUNT-RECORD` offsets that fixtures actually reshape are: `ACCT-ID` 0, active status
11, `ACCT-CURR-BAL` 12, `ACCT-CREDIT-LIMIT` 24, cash credit limit 36, open date 48,
`ACCT-EXPIRAION-DATE` 58, reissue date 68, `ACCT-CURR-CYC-CREDIT` 78,
`ACCT-CURR-CYC-DEBIT` 90, `ACCT-ADDR-ZIP` **102**, `ACCT-GROUP-ID` **112**, `FILLER` 122.
Section 7.2 explains why the last two are the easiest pair in this tree to confuse.

### 5.3 The two-source offset cross-check

Two unrelated sources agree on the same two offsets, which is what removes doubt from every
offset-dependent decision in this tree:

- **Summing the copybook** puts `TRAN-CARD-NUM` at zero-based **262** and `TRAN-PROC-TS` at
  zero-based **304** (section 5.2).
- **`app/jcl/TRANREPT.jcl` lines 41-42** independently declare the DFSORT symbols
  `TRAN-CARD-NUM,263,16,ZD` and `TRAN-PROC-DT,305,10,CH`, in **one-based** positions.
- Together they confirm the alternate-index key `KEYS(26 304)` built by
  `app/jcl/TRANIDX.jcl` line 27 -- length 26 at offset 304, which is exactly
  `TRAN-PROC-TS`.

**The conversion is always `zeroBased = oneBased - 1`.** Stating it explicitly is worth the
line: a sign error here shifts every field by one byte, and the result is a record of the
right length full of values that each look almost plausible. The same cross-check is
recorded on the Java side at
`services/common-lib/src/main/java/com/carddemo/common/codec/CopybookLayout.java` line 1672.

### 5.4 Why the reject record is 430 bytes, proved six ways

| # | Source | Evidence |
|---|---|---|
| 1 | `app/cbl/CBTRN02C.cbl` lines 83-84 | the FD declares `FD-REJECT-RECORD PIC X(350)` + `FD-VALIDATION-TRAILER PIC X(80)` |
| 2 | `app/cbl/CBTRN02C.cbl` lines 176-182 | working storage declares the same 350 + 80 split, and decomposes the trailer into `9(04)` + `X(76)` |
| 3 | `app/cbl/CBTRN02C.cbl` lines 447-448 | the two `MOVE`s that fill it write the 350-byte image, then the 80-byte trailer |
| 4 | `app/jcl/POSTTRAN.jcl` line 36 | the `DALYREJS` DD declares `DCB=(RECFM=F,LRECL=430,BLKSIZE=0)` |
| 5 | `tests/e2e/test_posting_cycle.py` line 108 | the parity oracle pins `_REJ_RECLEN = 430` |
| 6 | the committed reject goldens | each measures **431 bytes** -- 430 plus the single trailing LF of section 3.9 |

`350 + 4 + 76 = 430`. Six independent sources is worth recording because the trailer is the
one structure in this tree that is declared in a program rather than in a copybook, so there
is no `RECLN` banner to consult even inconsistently.

### 5.5 Money is one uniform contract

**`TRAN-AMT`, `TRAN-CAT-BAL`, `WS-MONTHLY-INT` and `WS-TOTAL-INT` are all `S9(09)V99`.**
One signed eleven-byte zoned field, one scale, end to end. `ACCT-CURR-BAL` and the credit
limits are the wider `S9(10)V99`, twelve bytes, same scale.

Money is exact fixed point at every hop: `NUMERIC(p,2)` in SQL, `BigDecimal` at scale 2 with
an explicit rounding mode in Java, `Decimal` in Python, and a JSON **string** on the wire.
**`float`, `double` and JSON numbers are forbidden in the money path**, and the prohibition
is asserted by an ArchUnit rule rather than left to review.

Assumptions: a JSON number is parsed into an IEEE-754 double by most clients, which destroys
exactness at the boundary a user actually reads. The string is not fastidiousness -- it is
the only representation that survives an arbitrary client, and it is why a fixture's money
bytes and the API's money bytes carry the same digits.

### 5.6 `CVEXPORT`: physical width comes from `USAGE`, never from `PICTURE`

The 40-byte prefix is `EXPORT-REC-TYPE X(1)` + `EXPORT-TIMESTAMP X(26)` +
`EXPORT-SEQUENCE-NUM 9(9) COMP` (4 bytes) + `EXPORT-BRANCH-ID X(4)` +
`EXPORT-REGION-CODE X(5)`, and `40 + 460 = 500`. `EXPORT-TIMESTAMP-R` is a `REDEFINES` and
adds nothing (section 9.2).

The width rules that make the branches close:

| `USAGE` | Physical width |
|---|---|
| display, the default | one byte per digit: `S9(09)V99` is **11**, `S9(10)V99` is **12** |
| `COMP-3`, packed | `ceil((digits + 1) / 2)`: `S9(09)V99` is **6**, `S9(10)V99` is **7**, `9(03)` is **2** |
| `COMP`, binary | **2** bytes up to 4 digits, **4** for 5 to 9, **8** for 10 to 18 |

**All five `REDEFINES` branches of the 460-byte data area close at exactly 460** -- customer,
account, transaction, card cross-reference and card -- but only if `PIC S9(10)V99 COMP-3` is
**seven** bytes.

Assumptions: the account branch is the arithmetic proof. It carries two `S9(10)V99 COMP-3`
fields, so at seven bytes each it sums to exactly 460, and at six bytes each it sums to
**458**. **Any statement that `S9(10)V99 COMP-3` is six bytes is defective and must not be
propagated**, and the two-byte shortfall is the specific consequence. The same copybook
demonstrates both widths side by side: its transaction branch holds `S9(09)V99 COMP-3` at
six bytes while its account branch holds `S9(10)V99 COMP-3` at seven -- the same `PICTURE`
family, a different digit count, a different physical width.

---

## 6. `FILLER` padding bytes -- measured, not derived

**There is no single padding rule for `FILLER` in this tree.** The byte a `FILLER` carries
depends on the record and, for output records, on the job that wrote it. The table below was
obtained by taking the byte-set of the `FILLER` slice of every row of the cited file, so each
row is a measurement rather than an inference.

### 6.1 The table

| Record | `FILLER` | Measured byte | Evidence |
|---|---|---|---|
| ACCOUNT `CVACT01Y` | `X(178)` at 122 | **`0x20` SPACE** | `app/data/ASCII/acctdata.txt`, slice `[122:300]`, byte-set `{32}` across all 50 rows |
| DALYTRAN `CVTRA06Y`, **input** | `X(20)` at 330 | **`0x20` SPACE** | `app/data/ASCII/dailytran.txt`, slice `[330:350]`, byte-set `{32}` across all 300 rows |
| TRAN `CVTRA05Y`, **output** | `X(20)` at 330 | **`0x00` NUL** | slice `[330:350]`, byte-set `{0}` in the posting golden and in all three interest scenario goldens -- because **no `MOVE` in either program ever touches `TRAN-RECORD`'s `FILLER`**, so it keeps the low-values state of the record area |
| TCATBAL `CVTRA01Y`, **input and rewritten output** | `X(22)` at 28 | **`0x30` ASCII `'0'`** | `app/data/ASCII/tcatbal.txt`, slice `[28:50]`, byte-set `{48}` across all 50 rows; and **eight of the nine** posting goldens' `tcatbal.expected`, same slice, same byte-set |
| TCATBAL `CVTRA01Y`, **created output** | `X(22)` at 28 | **`0x00` NUL** | `tests/golden/posting/zero_balance/tcatbal.expected`, slice `[28:50]`, byte-set `{0}` -- the ONE tree whose input holds no row for the key, so the row is created rather than rewritten (section 6.2.1) |
| DISCGRP `CVTRA02Y` | `X(28)` at 22 | **`0x30` ASCII `'0'`** | `app/data/ASCII/discgrp.txt`, slice `[22:50]`, byte-set `{48}` across all 51 rows; and the house interest fixture `discgrp.txt`, identical |
| CARD-XREF `CVACT03Y` | `X(14)` at 36 | **`0x20` SPACE** | the house 51-byte fixture line = 50 + LF, slice `[36:50]`, byte-set `{32}` (the seed omits this `FILLER` entirely -- section 3.10) |

### 6.2.1 TCATBAL's `FILLER` depends on the ARM that wrote the row, not on the record

**The two TCATBAL rows of the table above are one record type with two padding bytes, and which
one appears is decided by the create-versus-update branch of section 7.1.5.** This was measured
across all nine committed posting trees rather than inferred:

| Trees | Arm that wrote the row | `tcatbal.expected` slice `[28:50]` |
|---|---|---|
| `happy_path`, `empty_input`, `boundary_exact_limit`, `boundary_expiry_equal`, and all four `reject_10x_*` -- **eight** | `2700-B-UPDATE`, `app/cbl/CBTRN02C.cbl:526-542`, or no write at all | byte-set `{48}` -- the **seed row's own** padding, carried through untouched |
| `zero_balance` -- **one** | `2700-A-CREATE`, `app/cbl/CBTRN02C.cbl:503-524` | byte-set `{0}` |

The mechanism is the same one the table's TRAN row already records: a **created** record is built
in a record area no `MOVE` reaches beyond the fields it sets, so the pad keeps the low-values
state of that area, whereas a **rewritten** record starts from the row that was read and keeps
whatever that row carried. `zero_balance` is the only posting scenario that reaches the create
arm, because it is the only one whose `tcatbal.txt` is a **zero-byte file** (section 3.11), and
that is exactly why the scenario exists.

The consequence, stated specifically: **an author who applies the `{48}` row of the table to a
newly created TCATBAL expectation differs from the golden in 22 bytes, with the key and the
balance both correct.** It is the same failure class section 6.2 describes, arriving from the
opposite direction -- the general rule is not the hazard here, the record-level rule is, because
it is right for eight trees out of nine.

Assumptions: this is stated as a property of the WRITING ARM rather than of the record, so a
scenario README must say which arm produced the category row it expects, exactly as section 6.3
requires a `TRAN-DESC` expectation to name its writing job. `PostTransactionsJobTest` derives the
byte from the arm for this reason -- it asks whether the scenario's own input holds a row under the
key its expectation carries, and requires `0x30` when it does and `0x00` when it does not -- so
the two cannot be conflated by a reader of that case either.

### 6.2 The two rows that contradict the general rule

**TCATBAL's and DISCGRP's `FILLER`s are ASCII zeros, which contradicts the general "text `X`
pads right with spaces" rule of section 3.2. The measured bytes win.** (For TCATBAL that holds
wherever the row is read or rewritten; a row the posting job CREATES pads with the low value
instead, which section 6.2.1 measures and explains.)

The consequence, stated specifically rather than as a caution: an author who applies the
general rule to `tcatbal.txt` produces a row that differs from **both** the seed and the
golden in **22 bytes**, and in `discgrp.txt` in **28 bytes**. In each case every value the
record actually carries -- the key, the balance, the rate -- is correct, and the comparison
still fails, on padding that carries no data at all. That is the hardest class of failure to
diagnose from a diff, because the eye goes to the fields.

Assumptions: section 6.1 is the authority for fixture authoring, and it overrides section
3.2 wherever the two differ. Note that
`services/common-lib/src/main/java/com/carddemo/common/codec/FixedWidthCodec.java` restores
dropped `FILLER` **as blanks** on encode. That is the right behaviour for a codec, which has
no record-specific padding knowledge to draw on and must choose one filler; it is **not** the
rule for authoring a fixture. The two are consistent only for the three records whose
measured byte is a space, and for TCATBAL, DISCGRP and output TRAN they are not.

Alternatives Considered: deriving the padding from the `PICTURE` uniformly and treating the
seeds' zeros and NULs as noise to be normalised away. Rejected because the goldens carry the
same bytes as the seeds -- `0x30` in the TCATBAL golden, `0x00` in every TRAN golden -- so the
"noise" is what the programs actually produce, and normalising it would mean either editing
`tests/golden/**`, which is reference-only, or accepting a permanent diff on every record.

The DISCGRP row is a finding this contract adds. It is marked as measured from the seed
**and** from the house interest fixture, so a reader knows it was verified in two places
rather than assumed to follow from the TCATBAL row beside it.

An author can re-measure any row of the table directly:

```bash
# WHAT: print the distinct byte values occupying a record's FILLER slice, across
#       every row of a fixed-width file.
# WHY : Assumptions: the padding byte is a measured property of the corpus, not a
#       consequence of the PICTURE clause -- section 6.2 records two records where
#       the two disagree. A single-element byte-set is the proof that a whole file
#       is uniform; a two-element set means a row was authored by a different rule
#       and is the fastest way to catch it before it reaches a comparison.
python3 - <<'PY'
FILE, WIDTH, START, END = "app/data/ASCII/tcatbal.txt", 50, 28, 50
raw = open(FILE, "rb").read().replace(b"\r\n", b"\n").split(b"\n")
rows = [r for r in raw if len(r) == WIDTH]
print(FILE, "rows:", len(rows), "FILLER byte-set:", sorted({b for r in rows for b in r[START:END]}))
PY
```

### 6.3 `TRAN-DESC` padding is job-dependent, and the mechanism is proven

`TRAN-DESC` is `X(100)` at offset 32 in a 350-byte transaction record, and it is padded
**differently by the two jobs that write one**:

| Written by | Bytes 32 to 131 | Mechanism |
|---|---|---|
| **Interest**, `CBACT04C` | `Int. for a/c 00000000001` -- 24 characters -- then **exactly 76 x `0x00`** | lines 485-489 use `STRING 'Int. for a/c ' ACCT-ID DELIMITED BY SIZE INTO TRAN-DESC`. `STRING` writes only the 24 characters it was given and **leaves the tail of the receiving field untouched**, over storage that is low values |
| **Posting**, `CBTRN02C` | the description, then **spaces** | line 429 is a plain `MOVE DALYTRAN-DESC TO TRAN-DESC` of an input field that is already space-padded, and `MOVE` pads an alphanumeric receiver with spaces |

`TRAN-MERCHANT-NAME`, `TRAN-MERCHANT-CITY` and `TRAN-MERCHANT-ZIP` are **space-padded in
both jobs** -- the interest path sets them explicitly with `MOVE SPACES` at
`app/cbl/CBACT04C.cbl` lines 492-494, and the posting path moves already-padded input. The
reject trailer's description is space-padded for the same reason: it receives a `MOVE` of an
alphanumeric literal (section 7.1).

The consequence, stated specifically: **space-padding an interest `TRAN-DESC` shifts nothing
and changes 76 bytes from `0x00` to `0x20`, so the comparison fails on a field whose value is
correct.** The description reads correctly, the record is the right length, every other field
matches, and the diff points at the one field a reviewer is least likely to suspect.

Assumptions: the padding of `TRAN-DESC` is a property of the writing job, not of the record
type, so "a 350-byte TRAN record" is not sufficient information to author its byte 32 to 131.
A scenario README must state which job wrote the record it expects. This is the reason the
two domains cannot share a single expectation template even though they share a copybook.

---

## 7. Business-rule semantics each scenario must encode

These rules are transcribed from the baseline, not restated from prose. Every line citation
below was checked against the file it names.

### 7.1 Posting -- `CBTRN02C`, paragraph `1500-VALIDATE-TRAN`

#### 7.1.1 The four reject reasons

| Reason | Condition | Message text, verbatim | Reason `MOVE` | Message `MOVE` |
|---:|---|---|---|---|
| **100** | the card number is not found in the cross-reference | `INVALID CARD NUMBER FOUND` | `:385` | `:386` |
| **101** | the account record is not found | `ACCOUNT RECORD NOT FOUND` | `:397` | `:398` |
| **102** | the transaction would exceed the credit limit | `OVERLIMIT TRANSACTION` | `:410` | `:411` |
| **103** | the transaction is dated after account expiration | `TRANSACTION RECEIVED AFTER ACCT EXPIRATION` | `:417` | `:418` |

Both columns are given because a citation may reasonably point at either the code that sets
the reason or the literal that carries the text, and a reader following one number should not
land in the wrong construct. Reason 100 sits in `1500-A-LOOKUP-XREF` (`:380-392`); 101, 102
and 103 sit in `1500-B-LOOKUP-ACCT` (`:393-422`).

A fifth code, **109**, is written at `app/cbl/CBTRN02C.cbl:556` -- inside the
`INVALID KEY` branch of the account `REWRITE` in `2800-UPDATE-ACCOUNT-REC`, which runs only
on the *posting* path, after validation has already passed and where no reject record is
written. It is a **dead write** that never reaches the reject stream. **The persisted domain
is exactly {100, 101, 102, 103}.** No scenario may expect a `0109`.

#### 7.1.2 Reject codes are four characters on the wire and a `SMALLINT` in storage

`WS-VALIDATION-FAIL-REASON` is `PIC 9(04)` (`app/cbl/CBTRN02C.cbl:181`), so the trailer
carries **`0100`, `0101`, `0102`, `0103`** -- zero-padded to four characters, **not** `100`.
This is byte-proven: in each committed reject golden, the 80-byte trailer at offsets 350 to
429 reads the four-character code followed by the message space-padded to 76, and the
`reject_102_overlimit` golden reads `0102` then `OVERLIMIT TRANSACTION`.

**The stored column is different.** The migrated table holds the *integer* `102`, because
`reason_code` is declared `SMALLINT`. The sibling harness at
`services/batch-service/src/test/resources/db/testharness/test-harness-schemas-and-foreign-tables.sql`
lines 492-494 declares the reject contract by composition -- `raw_record CHAR(350)`,
`reason_code SMALLINT`, `reason_desc VARCHAR(76)` -- and that file is the authority for it.
The schema is deliberately not re-derived here.

Assumptions: the four-character form and the integer form are two representations of one
value, and an expectation must name which one it is asserting. A test comparing a
`SMALLINT` column against the string `0102` fails, and a byte comparison against `102`
fails, and both failures look like a wrong reason code rather than a wrong representation.

#### 7.1.3 Validation order, short-circuit, and the two inclusive boundaries

`1500-VALIDATE-TRAN` (`:370-378`) performs the cross-reference lookup, then performs the
account lookup **only if** the reason is still zero. **Reason 100 therefore prevents the
account lookup entirely** -- a `reject_100_card_missing` fixture may legitimately omit the
account, and its README should say that the omission is untested rather than satisfied.

Inside the account lookup's `NOT INVALID KEY` branch:

- `:403-405` computes `WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT`.
- `:407` tests `IF ACCT-CREDIT-LIMIT >= WS-TEMP-BAL`. **The over-limit boundary is
  inclusive**: a transaction landing *exactly* on the credit limit **posts**; one cent over
  **rejects** with 102.
- `:414` tests `IF ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS (1:10)`, a reference modifier
  taking the **first ten characters** of the 26-character originating timestamp. **The
  expiration boundary is inclusive**: a transaction dated *equal to* the expiration date
  **posts**; one day past **rejects** with 103.

Assumptions: in the seeds both cycle fields are `0`, so `WS-TEMP-BAL` equals `DALYTRAN-AMT`
and a boundary fixture can be reasoned about from the amount alone. A fixture that uses
non-zero cycle values changes that identity and **must show its intended arithmetic in its
own README**, because the reader can no longer read the expected outcome off the amount.

#### 7.1.4 Reason 102 is overwritten by 103 -- last writer wins

`:407-413` and `:414-420` are **two sequential, unguarded `IF` blocks** inside the same
`NOT INVALID KEY`. There is **no reason-code guard between them**. A transaction that is
both over limit **and** past expiration therefore ends with `WS-VALIDATION-FAIL-REASON`
holding **103**, and the trailer reads **`0103`** with the expiration message.

This is the least obvious expectation in the domain. A scenario authoring such a case
**must state it explicitly** in its own README, naming the overwrite and the two lines, so a
reader does not read the fixture's over-limit amount and conclude the expectation is wrong.
Conversely, a scenario intending to isolate 102 must keep its transaction date **on or
before** the expiration date, or its expectation silently becomes 103.

#### 7.1.5 The reject payload, the unit of work, and the category-balance branch

- **Reject payload.** `:447` moves the **entire 350-byte `DALYTRAN-RECORD` verbatim** into
  the reject image; `:448` appends the 80-byte trailer. The rejected input is preserved
  byte for byte, which is why the oracle can read the card number out of the reject at
  offsets 262 to 277 (section 5.2).
- **Unit of work.** `2000-POST-TRANSACTION` performs, in order, `:440` the category balance,
  `:441` the account, `:442` the transaction write, and the record loop at `:200-226` performs
  that paragraph **once per record**. In the migrated job those three writes are **one commit
  per record** -- opened by a `TransactionTemplate` over the step's transaction manager, with the
  tasklet itself declared `PROPAGATION_NOT_SUPPORTED` so the pass body holds no transaction.
  **No saga, no two-phase commit, no compensating reversal.** A partial-posting state within one
  record does not exist in the baseline, so no fixture may expect one; a run that committed some
  records and then failed **is** baseline behaviour, so no fixture may expect the earlier records
  to be undone either.
- **Category balance, create versus update** (`:495-542`). Both paths are **additive**. The
  create path (`:503-524`) `INITIALIZE`s the record, sets the key, `ADD`s the amount and
  `WRITE`s; the update path (`:526-542`) `ADD`s the amount and `REWRITE`s. The only
  differences are the `INITIALIZE` and the write-versus-rewrite. In SQL this is
  `INSERT ... ON CONFLICT DO UPDATE SET balance = balance + :amt` with the insert value
  `:amt`. **The key's account id comes from the cross-reference read at `:469-471`, not from
  the daily transaction record.** Both partitions must be non-empty across the domain and
  separately tested, because they are the two halves of one branch.

#### 7.1.6 The warn tier belongs to the COBOL suite alone

`:229-230` sets `RETURN-CODE` to **4** when the reject count is greater than zero. All four
`reject_10x_*` scenarios therefore carry an expected return code of **4**, and every clean
scenario carries **0**.

Assumptions: that graded rubric is the mainframe condition-code convention and it belongs
**exclusively** to the COBOL parity suite under `tests/**`. Maven, Surefire, Failsafe and
JUnit are **binary** -- a build either passes or fails, and there is no intermediate tier for
them to express. A return code of 4 is a **fixture expectation value** in this tree, asserted
like any other; it must never be wired into a Java build gate, and a Java build that reports
anything other than success is a failure.

#### 7.1.7 Oracle constants a fixture may not contradict

From `tests/e2e/test_posting_cycle.py`, which is reference-only:

| Constant | Value | Line |
|---|---|---:|
| `_EXPECTED_DAILY` | 300 | 123 |
| `_EXPECTED_POSTED` | 262 | 124 |
| `_EXPECTED_REJECTED` | 38, all reason `0102` for that seed | 125 |
| `_EXPECTED_CONSERVATION` | `Decimal("77954.70")` | 126 |
| `_EXPECTED_TCAT_INIT_KEYS` -> `_EXPECTED_TCAT_FINAL_KEYS` | 50 -> 100, being 50 updated plus 50 created | 127-128 |
| `_ACCT`, `_TCAT` loader geometry | `(300, 11)` and `(50, 17)` | 114-115 |
| `_REJ_RECLEN` | 430 | 108 |
| reject slices | card `[262:278]`, code `[350:354]`, message `[354:430]` | 109-111 |

`262 + 38 = 300` closes, and the 50-to-100 split means the create and update partitions are
each exactly 50 for the full seed.

Assumptions: **scenario fixtures in this tree are small samples, not the 300-record seed** --
a scenario isolating one reject reason holds a handful of rows. Nothing authored here may
contradict the constants above, because the same programs and the same layouts produce both.
A scenario claiming, for instance, a reject reason other than 102 for an unmodified seed row
would be asserting against the oracle rather than alongside it.

### 7.2 Interest -- `CBACT04C`

#### 7.2.1 The formula, and what the canonical vector actually proves

`app/cbl/CBACT04C.cbl:464-465`:

```text
COMPUTE WS-MONTHLY-INT
 = ( TRAN-CAT-BAL * DIS-INT-RATE) / 1200
```

There is **no `ROUNDED` phrase**, so the baseline truncates. **AAP Rule T4** requires the
migrated Java to **multiply at full precision first and only then divide**, with an explicit
scale and rounding mode.

**The canonical vector does not prove that ordering rule.** `(1000.00 x 15.00) / 1200` is
`12.5000` **exactly**, so multiply-first and divide-first both yield `12.50`, and truncation
and half-up rounding also agree. The vector is neutral on **both** axes. It is the right
vector for checking that a fixture's arithmetic closes; it is the wrong vector for
justifying the ordering, and this document does not claim otherwise.

Alternatives Considered: dividing the rate by 1200 first and multiplying the quotient into
the balance, which reads more naturally in Java and needs one fewer intermediate scale.
Rejected on a **discriminating** vector: a balance of `0.43` at a rate of `15.00` gives
`0.0064...` -- which multiply-first carries at full precision and then rounds to **`0.01`**,
while dividing to cents first gives `0.01` as the rate factor's rounded form or `0.00` as the
product's, landing on **`0.00`**. That is a one-cent divergence on a single category balance,
compounding once per category per account, on inputs the canonical vector cannot distinguish.
A fixture intended to exercise the ordering must use a vector of that shape and say so.

#### 7.2.2 Three distinct disclosure-group outcomes

The `DISCGRP` read key is `ACCT-GROUP-ID` + `TRAN-TYPE` + `TRAN-CAT`. There are **three**
outcomes and a scenario must name which one it exercises:

1. **Direct hit.** The composed key is found; its rate is used.
2. **`DEFAULT` fallback.** The read returns `INVALID KEY` -- VSAM status **23** -- and
   `app/cbl/CBACT04C.cbl:436-439` moves the literal `'DEFAULT'` into the group id and
   re-reads through `1200-A-GET-DEFAULT-INT-RATE` (`:443-460`). Note that the retry treats
   anything other than status `00` as fatal, so a missing `DEFAULT` row is an abend rather
   than a zero rate.
3. **Zero rate.** `:214` gates the whole calculation on `IF DIS-INT-RATE NOT = 0`. When the
   rate is zero, **no interest transaction is written at all** and the fee paragraph is
   skipped. This is a **third** outcome, distinct from both of the above.

Assumptions: outcome 3 is not the same thing as a zero *balance*. The `zero_balance` scenario
still writes a transaction -- its `TRAN-AMT` is `0000000000{`, which is `0.00` -- because the
rate is non-zero and only the product is zero. A scenario expecting *no record at all* must
be driving a zero **rate**, and a scenario expecting a `0.00` record must be driving a zero
**balance**. The two are one byte apart in the fixture and a whole record apart in the output.

The migrated `DEFAULT` rows are seeded by the sibling harness, which inserts exactly
**seventeen** `'DEFAULT   '` rows -- space-padded to `CHAR(10)`, three trailing blanks -- at
lines 394-410, idempotently via `ON CONFLICT ... DO NOTHING` at line 411. **Scenario group
rows are additive on top of those**: a scenario adds the group rows its own key needs and does
not re-seed or alter the baseline seventeen.

#### 7.2.3 `ACCT-GROUP-ID` is blank in the raw seed -- the fixture author must reshape it

Measured from `app/data/ASCII/acctdata.txt` row 1:

| Field | Zero-based slice | One-based columns | Raw seed value |
|---|---|---|---|
| `ACCT-ADDR-ZIP` | `[102:112]` | 103-112 | `A000000000` |
| `ACCT-GROUP-ID` | `[112:122]` | 113-122 | **ten spaces** |

A fixture that copies a seed account row verbatim therefore gets a **blank** group id: the
composed key misses, status 23 fires, and the `DEFAULT` fallback runs. **That is the
`default_fallback` scenario, not `happy_path`.** Verified in both directions in the house
tree: `tests/fixtures/interest/happy_path/acctdata.txt` sets `ACCT-GROUP-ID` to `A000000000`
deliberately, while `tests/fixtures/interest/default_fallback/acctdata.txt` leaves it as ten
spaces -- and the corresponding goldens carry the same distinction.

**Do not conflate `ACCT-ADDR-ZIP` with `ACCT-GROUP-ID`.** In the reshaped happy-path fixture
**both** read `A000000000`, ten bytes apart.

Assumptions: that coincidence is what makes the confusion easy and its consequence silent.
Writing the group id into the zip slice leaves the record the right length, leaves the group
id blank, and produces a run that takes the fallback and still yields a plausible interest
figure -- so a `happy_path` fixture would pass a loose expectation while exercising the wrong
one of the three outcomes in section 7.2.2.

#### 7.2.4 The generated interest transaction

- `:483` moves the literal `'05'` into `TRAN-CAT-CD`, which is `PIC 9(04)`, so the generated
  record stores **`0005`** -- **regardless of the input category**, which is `0001` in the
  seeds and in the house fixtures. **Do not reconcile it to the input.**
- `:482` sets `TRAN-TYPE-CD` to `'01'` and `:484` sets `TRAN-SOURCE` to `'System'`, which
  occupies `X(10)` as `System` plus four spaces.
- `1400-COMPUTE-FEES` (`:518-520`) is an **empty stub**: its body is the single comment
  `* To be implemented`, followed by `EXIT`. **No fee is produced.** Tests assert the
  **absence** of a fee record. **Do not fabricate fee output** for any scenario.

### 7.3 The two-account interest design is load-bearing -- mirror it, never simplify it

`app/cbl/CBACT04C.cbl:188-206` reads category balances and performs `1050-UPDATE-ACCOUNT`
**only when the account id changes**. Because `PERFORM UNTIL END-OF-FILE = 'Y'` tests its
condition **before** the body, the trailing `ELSE PERFORM 1050-UPDATE-ACCOUNT` at `:220` is
**unreachable**: the loop exits on the same iteration that sets the end-of-file flag. The
consequence is that the **last (or only) account is never written back**.

The baseline behaves that way; the migrated Java flushes the final account; the divergence is
documented in `docs/architecture/cobol-to-service-traceability.md`. Nothing in `app/**`
changes.

**A single-account interest fixture cannot observe either behaviour.** Ship **two**:

| Account | Role | What it demonstrates |
|---|---|---|
| `00000000001` | **non-final** | the control break to account 2 fires `1050-UPDATE-ACCOUNT`, so this account's record **is** rewritten -- the only observable rewrite in the domain |
| `00000000002` | **final** | no further control break occurs, so this account's record is **not** rewritten -- the omission, made visible |

Dropping the second account removes the only observable rewrite; adding a third only shifts
which account is "final" and buys nothing. Because `TCATBAL` is indexed, rows are consumed in
key order regardless of physical line order (section 3.12), so `...001` is deterministically
the non-final account.

Established values to reuse, so that every interest scenario composes with the goldens:

| Item | Value |
|---|---|
| `ACCT-GROUP-ID`, both accounts | `A000000000` (reshaped -- section 7.2.3) |
| category balances | one per account, `1000.00`, encoded `0000010000{` |
| `DIS-INT-RATE` | `15.00`, encoded `00150{` |
| cross-reference chain | card `9680294154603697` -> customer `000000001` -> account `00000000001`; card `0923877193247330` -> customer `000000002` -> account `00000000002` |
| opening account balances | `194.00` (encoded `00000001940{`) and `158.00` (encoded `00000001580{`) |

The expectation, byte-verified from the committed goldens:

| Account | Expected `ACCT-CURR-BAL` | Encoding | Why |
|---|---|---|---|
| `00000000001` | **`206.50`** | `00000002065{` | `194.00 + 12.50` -- the rewrite fired |
| `00000000002` | **`158.00`** | `00000001580{` | unchanged -- no control break followed it |

**Encode `12.50` and `206.50`. Do not "correct" them toward `12.59`.**

Trade-offs: `tests/fixtures/interest/happy_path/README.md` section 7 records that an
end-to-end run of the compiled two-account program reproducibly produced a `TRAN-AMT` of
`+12.59` and an account-1 balance of `-181.52`, while confirming the *structural* behaviour
above -- which account is rewritten and which is not. Because the COBOL is immutable
reference material, that observation is **documented, not altered**; consult that README for
its detail rather than a paraphrase here. The committed goldens nonetheless encode the
analytic `12.50` and `206.50`, and that is what the integration expectation asserts. The
compromise accepted is that one figure in this domain is analytic rather than captured, in
exchange for an expectation that states the documented business rule instead of a
reproduction of a defect. Note also that the divergence run used
`PARM-DATE = 2022071800` while the committed scenario goldens use `2024-01-15` -- a
**different business-date parameter** (section 8.2) -- which is a further reason the two
figures are not two measurements of the same run and must not be reconciled.

---

## 8. Determinism and isolation

A fixture contains no value that varies between runs: no wall-clock timestamp, no random
identifier, no environment-derived string. Everything is literal and reproducible. Each
scenario is self-contained -- every key cross-referenced inside it resolves inside its own
files -- **except** where a key is *deliberately* omitted to trigger a reject or a fallback,
which the scenario's README must name. Each test provisions a fresh workspace, loads its
fixtures, runs the job and tears the workspace down, so there is no shared mutable state and
scenarios run independently and in parallel.

### 8.1 Timestamp handling is domain-dependent

The two jobs treat the two 26-byte timestamp fields differently, and masking the wrong one
either throws away a real assertion or compares a clock reading.

| Domain | `TRAN-ORIG-TS` at 278 | `TRAN-PROC-TS` at 304 | Evidence |
|---|---|---|---|
| **Posting** | **copied from the input record** -- deterministic, so **assert it** | from the wall clock -- **mask it** | `app/cbl/CBTRN02C.cbl:436` moves `DALYTRAN-ORIG-TS` straight across; `:437-438` performs the clock read and moves it into the processing stamp |
| **Interest** | from the wall clock -- **mask it** | from the wall clock -- **mask it** | `app/cbl/CBACT04C.cbl:496` performs `Z-GET-DB2-FORMAT-TIMESTAMP` **once** and `:497-498` move that one value into **both** fields |

So for interest the two fields are **necessarily equal to each other** as well as volatile --
confirmed in the goldens, where both appear as 26 spaces each, 52 contiguous spaces in total.
The parity oracle retains and asserts `TRAN-ORIG-TS` for posting.

**The generated form differs from the copied form.** `Z-GET-DB2-FORMAT-TIMESTAMP`
(`app/cbl/CBACT04C.cbl:613-626`) builds the **DB2 dotted** shape
`YYYY-MM-DD-HH.MM.SS.mm0000` -- a **dash** between the date and the time rather than a space
or a `T`, and **dots** inside the time, assembled from the separator moves at `:623-624` and
the literal `'0000'` at `:622`. The posting path, by contrast, copies an ISO-shaped
`YYYY-MM-DD HH:MM:SS.ffffff` straight out of the input; the daily-transaction seed's first
record carries `2022-06-10 19:27:53.000000`. Both are 26 characters and they are not
interchangeable.

The Java side expresses exactly this distinction with **two layouts** in
`services/common-lib/src/main/java/com/carddemo/common/codec/CopybookLayout.java`: `TRAN`
normalises only the processing timestamp and deliberately preserves a deterministic
originating timestamp, while **`INTTRAN` is a purpose-built derivation of `TRAN` with the
originating timestamp additionally flagged for normalisation** -- declared at lines 1843-1844
as `TRAN_LAYOUT.withFieldFlags("INTTRAN", "TRAN-ORIG-TS", true, false)` and registered as a
derived layout at line 2155. A scenario expectation names the layout it compares under.

**A 26-blank timestamp is a legitimate value, not an error -- and it means two different
things.** In the daily-transaction seed those 26 spaces are **genuine input data**: the record
has not been processed yet, which is why the migrated `daily_transactions.proc_ts` column is
**nullable** in the sibling harness (line 474) while `transactions.proc_ts` is `NOT NULL`
(line 450). In a golden, the same 26 spaces are the **product of normalisation**. Same bytes,
two different reasons.

Assumptions: because the byte pattern is identical, the meaning cannot be recovered from the
bytes and must be stated. **Every scenario README must say which of the two applies, every
time** it shows a blank timestamp. A reader who assumes "normalised" for an input file
concludes the fixture is missing data; a reader who assumes "real data" for a golden
concludes the comparison is not masking.

### 8.2 The business-date token is an opaque passthrough -- both formats must be exercised

**Any expectation that hard-codes one business-date format is wrong.** The evidence:

- **Two committed golden forms, both exactly 16 characters.** One generated transaction id
  begins `2022071800` then `000001` -- the **compact** form, matching
  `app/jcl/INTCALC.jcl:22` `PARM='2022071800'`. The other begins `2024-01-15` then `000001` --
  the **ISO** form. Measured: all three interest **scenario** goldens use ISO; both interest
  **end-to-end** goldens use compact.
- **Those records are otherwise byte-identical** -- same type `01`, same category `0005`, same
  source `System`, same description shape, same card number -- differing **only** in the
  ten-character prefix, in two different layouts. **Forcing either one provably breaks the
  other.**
- **The baseline applies no formatting whatsoever.** `app/cbl/CBACT04C.cbl:473-480` merely
  `STRING`s `PARM-DATE`, a `PIC X(10)`, alongside a `PIC 9(06)` counter
  `DELIMITED BY SIZE INTO TRAN-ID`, a `PIC X(16)` -- `10 + 6 = 16`, filled exactly. Line
  `:474` increments the counter **before** it is used, so the **first** generated id always
  ends `000001`.
- **The migrated type holds the same contract.** `dto/BusinessDate.java` specifies an opaque
  ten-character token, preserved character for character, never normalised, with no default
  instance and no clock-reading factory. Both `application-test.yml` (its numbered note 6)
  and the module's `src/main/resources/application.yml` decline to configure any canonical
  date format for it, on this same evidence.

Therefore: **the interest scenarios must supply both an ISO token and a compact token across
the domain**, so that the passthrough is genuinely exercised rather than assumed, and every
scenario README treats the ten-character prefix as an **input parameter** of the scenario
rather than as a derived value.

Assumptions: `services/batch-service/README.md` line 506 frames the compact token as
`yyyyMMdd` followed by the literal `00` and "not an ISO date". That is accurate as a
description of what `app/jcl/INTCALC.jcl` happens to pass, and **wrong if read as a required
format for the migrated Java** -- which is the reading to guard against. That stronger reading
must not be followed or propagated here: it contradicts the opaque-token contract the DTO
implements, and it would invalidate the three ISO-prefixed scenario goldens outright. Where
the two statements conflict, the goldens and the DTO govern.

---

## 9. Derivation from seeds, and authoring hazards

### 9.1 Derivation discipline

| Seed | Record type | Line ending |
|---|---|---|
| `app/data/ASCII/acctdata.txt` | ACCOUNT, 300 | LF |
| `app/data/ASCII/carddata.txt` | CARD, 150 | LF |
| `app/data/ASCII/cardxref.txt` | CARD-XREF, 36-byte seed / 50-byte copybook | LF |
| `app/data/ASCII/custdata.txt` | CUSTOMER, 500 | LF |
| `app/data/ASCII/dailytran.txt` | DALYTRAN, 350 | LF |
| `app/data/ASCII/discgrp.txt` | DIS-GROUP, 50 | LF |
| `app/data/ASCII/tcatbal.txt` | TRAN-CAT-BAL, 50 | **CRLF** (section 3.8) |

The card-to-customer-to-account chain is established through `cardxref`:
`card [0:16]` -> `customer [16:25]` -> `account [25:36]`. When building a scenario, pick a
coherent chain -- a card whose `XREF-ACCT-ID` names an account that exists in the scenario's
own `acctdata.txt`, **or** is deliberately absent, which is exactly what
`reject_101_acct_missing` is for.

**Seeds are inputs to derivation only and are NEVER modified.** Copy the rows a scenario needs
into that scenario's own file and reshape **there**. Nothing in this tree writes to `app/**`,
`tests/**`, `scripts/**` or `samples/**`, and nothing in this tree regenerates a golden.

### 9.2 Parser and authoring hazards

Any copybook or JCL reader used while authoring a fixture must handle all of the following.
Each is a real property of the baseline, not a defect to be worked around.

**Ignore columns 73 to 80.** COBOL's identification area and JCL's sequence field carry
characters that are not part of the statement.

Assumptions: the baseline genuinely uses that area, so a reader cannot treat it as always
blank. `app/jcl/DEFGDGD.jcl:1` carries the sequence field `JOB05067`, and
`app/cbl/CBTRN02C.cbl:161-167` carries stray single letters in the identification area of a
`.cbl` source. A reader that takes the whole line sees `JOB05067` as part of a `JOB`
statement's operands and those single letters as data-division tokens -- so the failure is a
misparse of a line that is perfectly valid COBOL and JCL, not a syntax error it can report.

**Treat `REDEFINES` as an overlay that does not advance the offset.** `CVEXPORT` is the
worked example: `EXPORT-TIMESTAMP-R` redefines `EXPORT-TIMESTAMP` and contributes nothing to
the record length, and the five branches of `EXPORT-RECORD-DATA` each redefine the *same* 460
bytes (section 5.6).

Assumptions: a redefinition is an alternative reading of bytes that already exist, never
additional bytes. Counting the five `EXPORT-RECORD-DATA` branches as fields turns a 500-byte
record into a 2340-byte one, and counting `EXPORT-TIMESTAMP-R` shifts every field after it by
26 -- two different wrong answers from the same omission.

**Distinguish the two kinds of `FILLER`.** A `FILLER` without a `VALUE` is padding and its
byte comes from section 6.1. A `FILLER` carrying a `VALUE` is content and its bytes are that
value.

Assumptions: the name `FILLER` marks only that the field is unnamed, not that it is
meaningless. Treating a valued `FILLER` as padding replaces real content with a padding byte
of the right width, so the record still loads and one field silently loses its value.

Assumptions: **duplicate data-names collide in a flat symbol table, and they are not bugs.**
The baseline legitimately reuses names across file-description entries in one program:

| Name | Occurrences | Widths |
|---|---|---|
| `FD-ACCT-DATA` | `app/cbl/CBACT04C.cbl:87` and `:92` | `X(289)` and `X(334)` |
| `FD-ACCT-DATA` | `app/cbl/CBTRN01C.cbl:89` and `:94` | `X(289)` and `X(334)` |
| `FD-CUST-DATA` | `app/cbl/CBTRN01C.cbl:69` and `:74` | `X(334)` and `X(491)` |
| `FD-TRANS-ID` versus `FD-TRAN-ID` | distinct names, one character apart | -- |
| `TRAN-CAT-KEY` | `app/cpy/CVTRA01Y.cpy:5` and `app/cpy/CVTRA04Y.cpy:5` | **17 bytes** (acct + type + cat) versus **6 bytes** (type + cat) |

A layout registry must therefore be **scoped per copybook and per file-description entry**,
never flattened into one global name-to-width map. A flat map resolves `TRAN-CAT-KEY` to
whichever definition it loaded last, and an 11-byte error at offset 0 shifts every field of a
50-byte record.

**Derive every record length by summing field widths**, never by parsing a `RECLN` banner --
section 5.1 records the four inconsistent banner styles that make summing the only reliable
method.

**Do not "correct" a preserved baseline literal.** `app/jcl/TRANBKP.jcl` lines 35 and 49 carry
the spelling `TRANSACATION` in comments. Where a literal is preserved, it is preserved as it
stands.

### 9.3 The three misspelling corrections apply to target column names only

Per **AAP Rule T1**, three baseline field names are corrected in the **migrated column
names**, and in nothing else:

| Baseline field | Target column |
|---|---|
| `ACCT-EXPIRAION-DATE` (`app/cpy/CVACT01Y.cpy:11`) | `expiration_date` |
| `CARD-EXPIRAION-DATE` (`app/cpy/CVACT02Y.cpy:9`) | `expiration_date` |
| `PA-MERCHANT-CATAGORY-CODE` | `merchant_category_code` |

**No other field is renamed**, and the corrections never apply to a COBOL citation: a
reference to the validation at `app/cbl/CBTRN02C.cbl:414` names `ACCT-EXPIRAION-DATE`, because
that is what the line says. A scenario README citing a baseline line must quote the baseline
spelling.

---

## 10. The per-scenario README mandate

**Every scenario directory in this tree MUST carry a `README.md`.** The wording is *must*, not
*should*: a scenario directory without one is non-conforming, because section 1.2 establishes
that its record files can carry no explanation of their own and the directory would then hold
no reasoning at all.

Assumptions: no row of the migration plan asks for a README at these paths. AAP section
0.2.1.2 asks for *"fixture records derived from the copybook layouts"* -- records, not
documentation. **These READMEs exist because of user-specified Rule 1 (Explainability)**,
whose validation gate at line 43 fails a contribution missing either the documentation or the
decision rationale. The house fixture tree reached the same conclusion independently and
mandates the same artifact in its own explainability section, so the obligation is not an
invention of this tree.

Each scenario `README.md` must state, in this order:

1. **Scenario intent** -- the condition the scenario represents, in one or two sentences.
2. **The exact business rule it exercises, cited by program and line.** For example:
   "`CBTRN02C` reject 102, the inclusive `>=` credit-limit boundary at `:407`", or
   "`CBACT04C` `DEFAULT`-group fallback on VSAM status 23 at `:436-439`". A citation, not a
   paraphrase -- the line number is what lets a reviewer check the claim.
3. **The expected outcome** -- post versus a specific reject code **and** its verbatim
   message, or the exact computed interest value **with its arithmetic shown**, plus the
   expected return code (section 7.1.6).
4. **Fixture bytes and governance, per file** -- record width, record count, line ending, and
   provenance. Where a scenario departs from a rule in this document -- a deliberate 36-byte
   card-xref (section 3.10), a deliberately preserved CRLF (section 3.8), a deliberate
   negative zero (section 3.6), non-zero cycle values (section 7.1.3), a case where 102 is
   overwritten by 103 (section 7.1.4) -- it must name the departure and its reason.
5. **The synthetic-provenance attestation** of section 11 -- mandatory for every scenario
   carrying card-number or identity-shaped data, which is **all posting scenarios, all
   preflight scenarios, the export scenario, and the interest cross-reference files**. It must
   state that the bytes are synthetic and seed-derived, citing the seed file and where useful
   the seed row key; that they represent **no real person or account**; and **every
   business-rule field reshaped away from its seed value** -- `ACCT-GROUP-ID` being the
   canonical example (section 7.2.3).

Item 5's last clause is the one most easily skipped and the one that matters most for audit: a
reader comparing a fixture row against its seed row byte for byte will otherwise conclude the
row was invented when only a business-rule field was reshaped.

A scenario README cites this document **by section number** and restates none of it
(section 1.3). The section numbers above are stable and are the ones to cite.

**All sixteen scenario READMEs now exist**, and the mandate is machine-checked rather than
stated: `BatchFixtureContractTest` asserts the presence of a `README.md` in each of the
sixteen directories, one parameterised case per scenario, and asserts the scenario census as
a closed set in both directions so that a scenario added without a contract document fails
rather than escaping notice. Section 1.5 records the class and what else it holds.

Refactoring Rationale: twelve of the sixteen were missing while this section stated the
obligation as *must*, which is exactly the failure mode an unchecked prose obligation
produces -- and the reason the presence check is now an assertion. The convention that a path
to a scenario README which does not exist yet is written as a **plain code span** rather than
a link, per `docs/CODE_DOCUMENTATION_STANDARD.md`, remains in force for any future scenario
authored before its document; it no longer applies to any scenario in this tree, so the
sibling references in the existing READMEs are links.

Refactoring Rationale: the mandate above is now **satisfied for all sixteen scenarios**, and
the two paragraphs that follow were written while it was not. Twelve of the sixteen READMEs
were authored after this contract, in one pass: `interest/zero_balance`, the eight `posting/`
scenarios other than `empty_input`, and all three `preflight/` scenarios. The preceding
paragraph's code-span convention therefore no longer applies to any path in this tree --
every scenario README exists and may be linked -- and it is left standing because it still
governs a scenario added in future, which would carry the same gap until its README is
written. Assumptions: the count is measured rather than tallied. Sixteen scenario
directories each hold a `README.md`, which is the same population section 4.1 counts, so
that sentence and this one check each other.

Alternatives Considered: those twelve scenario READMEs were drafted THREE times, independently,
and the two shorter drafts are superseded rather than merged in -- with one exception recorded
below. The first shorter draft mirrored the five mandated items above one-for-one, at roughly 185
lines each; the second ran 123 to 161 lines. The one in the tree carries the
same five in the same relative order -- intent, then the rule cited by program and line, then
the outcome, then the bytes and their governance, then the provenance attestation -- and adds
five sections the mandate does not ask for: what must NOT happen and why it cannot, what the
blank timestamp means for determinism, which target-side contracts the scenario agrees with,
where the scenario's boundaries lie, and what drives the corpus and what reads it.

⚠️ Assumptions: that last section is the ONE thing carried out of a superseded draft rather than
discarded with it, because the surviving text had no equivalent and a reader cannot otherwise tell
whether editing these bytes changes what a run asserts. It is carried CORRECTED, not copied: the
draft stated uniformly that no test in this module opens the folders, and that is now true of
`preflight/**` and `interest/**` only. `PostTransactionsJobParityIT` declares `/fixtures/posting/`
as its seed root and compares against `tests/golden/posting/<scenario>`, so the posting family is a
driven input and each of its READMEs says so, while the other two say the opposite and each states
the other case so the split cannot read as an oversight. The supersession was decided by MEASUREMENT rather
than by length: every code span the shorter draft cited appears in the surviving one, in that
spelling or a finer one -- its `:229-230` grade citation inside a `:227-230` report-and-grade
row, its `:562` write citation inside the `:442` performing site that reaches it, and its seed
row key inside a per-file seed-row table that also states the one-byte difference and its
offset. Keeping both was rejected because two contracts for one directory is the condition
section 1.3 exists to prevent: a reader would have to decide which one governs.

Two checks are worth running before proposing a fixture. In the first, `FIXTURE` and `WIDTH`
are parameters to substitute. Assumptions: the illustrative path is `posting/happy_path`,
which **does** now hold the files shown -- it did not when this contract was written, and the
statement that it would not has been corrected rather than left to read as a caveat about a
file a reader can see.

```bash
# WHAT: assert that every physical row of a fixture file is exactly the declared
#       record length, and that the file ends in exactly one newline.
# WHY : Assumptions: the loaders reject a wrong-length row outright rather than
#       padding or truncating it (section 1.2), so this check turns a load-time
#       failure into an authoring-time one. Counting rows separately from bytes is
#       what catches the two most common mistakes at once -- a stray CR absorbed
#       into the last field, and a second trailing blank line that parses as a
#       zero-length record.
FIXTURE=services/batch-service/src/test/resources/fixtures/posting/happy_path/dailytran.txt
WIDTH=350
awk -v w="$WIDTH" 'length($0)!=w{printf "line %d is %d bytes, expected %d\n", NR, length($0), w; bad=1}
     END{ if(!bad) printf "OK: %d rows, all %d bytes\n", NR, w }' "$FIXTURE"
tail -c 2 "$FIXTURE" | od -c | head -1
```

```bash
# WHAT: confirm this contract document is pure ASCII.
# WHY : Assumptions: tests/README.md carries the non-breaking hyphen U+2011 in its
#       prose, including inside a category label, and it is the only file in the
#       repository that does. Label text copied from it looks identical on screen
#       and does not match a literal ASCII search, which is precisely the audit
#       mechanism section 1.4 depends on. A non-empty result here means a label or
#       a heading was copied rather than typed.
grep -nP '[^\x00-\x7F]' services/batch-service/src/test/resources/fixtures/README.md \
  && echo "FAIL: non-ASCII byte found" || echo "OK: pure ASCII"
```

---

## 11. Data governance and synthetic-provenance attestation

### 11.1 Attestation

**No real cardholder, account or personal data appears anywhere in this fixture tree.** Every
card number, account id, customer id, and identity-shaped value -- name, address, national
identifier, government-issued identifier, date of birth, telephone number, credit score -- is
**synthetic test data derived from the published AWS CardDemo sample seed datasets** under
`app/data/ASCII/`. Those seeds ship with the upstream open-source AWS CardDemo project as
**fabricated demonstration data**; they describe **no real person and no real account**.

Identity and primary-account-number bytes are taken **unchanged** from the seed. Only
**non-identity business-rule fields** are reshaped: monetary amounts, dates, group ids and
balances. That split is what keeps every identity value in this tree attributable to a
published synthetic seed row rather than to an invented value.

### 11.2 Why the seeds rather than freshly minted card numbers

Alternatives Considered: two options were weighed. The first was generating fresh
reserved-range test primary account numbers with a documented check-digit generator, which
would give this tree an independent provenance story. The second was reusing the published
CardDemo synthetic seeds. The second was chosen for two reasons that the first cannot supply:
the card-to-cross-reference-to-account-to-customer chains **resolve consistently** because the
programs under test already validate against seed-shaped records, and the values **inherit the
seeds' documented non-person status**, so provenance is demonstrable without standing up and
attesting a second generator. A card number that happens to satisfy a check digit here does so
because the upstream synthetic seed made it so, not because it was matched to any issuer.

Trade-offs: reusing the seeds means this tree's identity values are **not unique to it** -- the
same card numbers appear in the house fixture tree and in the seeds themselves. The compromise
is accepted because uniqueness was never the goal; traceability was, and a value that can be
pointed back to a specific seed row by its key is more auditable than a unique value whose
origin is a generator run nobody kept.

### 11.3 Prohibitions that govern every byte in this tree

- **No secret, credential, connection string or endpoint** appears in any file here, in any
  form. A fixture is record data and nothing else.
- **No scenario creates, alters or seeds another service's schema.** Scenario rows load into
  the ephemeral test container only, **additively** on top of the baseline the sibling harness
  establishes (section 7.2.2). The harness owns the baseline; a scenario owns only its own
  rows.
- **Money never leaves fixed point** (section 5.5): no `float`, no `double`, no JSON number
  anywhere in the money path.
- The COBOL baseline under `app/**` is **never modified**. The export/import compilation
  defect and the final-account interest-flush omission stay exactly as they are in the
  baseline. The permitted framing is always *"the baseline does X; the migrated Java
  implements Y; the divergence is documented"* -- never that the baseline was put right.
- The parity oracle under `tests/**` and `scripts/**` is **never modified and never
  re-pinned**. Its documented aggregate return code of 4 is its **passing** state, caused
  solely by the export/import compilation defect, and it must never be reported as a
  regression introduced here. It is read as the pattern source and written to never, and no
  golden is ever regenerated.

### 11.4 Baseline counts, for citation discipline

When a scenario README refers to baseline scale, the figures are: **31** COBOL programs under
`app/cbl`, **44** `.cbl` sources across the whole migration scope including the three extension
trees, and exactly **30** copybooks under `app/cpy`. Assumptions: the three figures count
different things and are easy to merge into one wrong number, so each is stated with the tree
it counts.
