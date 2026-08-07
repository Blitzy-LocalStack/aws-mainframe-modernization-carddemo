```text
# =============================================================================
# services/reference-service/src/test/resources/fixtures/README.md
# -----------------------------------------------------------------------------
# Purpose:
#       Tree-scope derivation contract for the reference-service fixed-width
#       test fixtures. This file authors no record bytes. It records HOW every
#       record file beneath this directory is derived from the COBOL copybooks
#       and the ASCII seed datasets, at byte granularity, so that a fixture in
#       this tree cannot be silently wrong. A reader holding this file and the
#       copybooks it cites can reproduce any fixture here byte for byte, and
#       can decide whether an existing one is correct.
#
# WHY (non-obvious design decisions):
#   1.  A fixed-width record file cannot carry a comment. Every byte position
#       is meaningful, so a '#' character would be DATA, not an annotation; and
#       the loaders reject any physical row whose length is not exactly the
#       record length, so a comment line is a wrong-length row and a hard load
#       failure. A README is the only carrier available for the WHY behind
#       these bytes. That is the reasoning the house fixture tree states at
#       tests/fixtures/README.md L59-L64, and this file inherits it.
#   2.  This document records the DERIVATION, not merely the bytes. The bytes
#       alone are self-consistent whether or not they are right; only the
#       derivation chain back to a copybook line can establish that they are.
#       Every layout row below therefore cites the copybook line it came from.
#   3.  Offsets here are 0-BASED, declared once in section 3 and never mixed.
#       The baseline itself uses BOTH conventions - IDCAMS KEYS(length offset)
#       is 0-based, DFSORT SYMNAMEs are 1-based - so leaving the base implicit
#       is a guaranteed one-byte error on every field, not a stylistic risk.
#   4.  User-specified Rule 1 (Explainability) is enforced here by authoring
#       discipline alone. config/checkstyle/checkstyle.xml L185 scopes its
#       Checker to fileExtensions="java", so no file in this directory is ever
#       scanned. There is no mechanical fallback; see section 2.3.
# =============================================================================
```

# reference-service test fixtures - derivation contract

Fixed-width fixture records for `reference-service`, derived from the COBOL
copybooks in `app/cpy/` and the ASCII seed datasets in `app/data/ASCII/`.

This document is the tree-scope Explainability artifact for this directory. It
is organised so that the four elements user-specified Rule 1 (Explainability)
requires of a module entry point are each findable in one place:

| Rule 1 element | Where it lives here | What it covers |
|---|---|---|
| Purpose (L18) | Sections 1 and 2 | What this tree is for, which service consumes it, and which rules govern it |
| Parameters (L19) | Sections 3, 4 and 5 | The byte-level contract per record type: field, `PICTURE`, offset, length, fill character, and the copybook line each came from |
| Return values (L20) | Section 9 | Which consumer reads each fixture and what it asserts with it |
| Exceptions and errors (L21) | Section 12 | The failure modes, each with the symptom it produces |

Sections 6, 7, 8, 10, 11, 13 and 14 carry the labelled decision rationales, the
verbatim seed content, the tree layout contract, the verified availability
status, the coverage analysis, the validation gates, and the prohibitions.

---

## 1. Purpose and scope

### 1.1 What this tree is

This directory holds **fixed-width, positional record files** that stand in for
the VSAM datasets `reference-service` was migrated from. They are consumed by
that module's own tests and by the shared fixed-width codecs in `common-lib`.

**Most** of this tree is **derived**: a record file is normally a copy or a subset
of an ASCII seed dataset under `app/data/ASCII/`, reshaped per scenario. The
seeds are inputs to derivation and are never edited in place. That is the house
minimal-change principle, stated at `tests/fixtures/README.md` L52-L57, and it
applies with full force here: `app/**`, `tests/**`, `scripts/**` and
`samples/**` are REFERENCE-ONLY.

**Two of the five domains have no seed to derive from at all, and their bytes are
therefore authored.** This is stated here rather than left to the scenario READMEs,
because a blanket claim that everything is derived is the kind of statement a reader
relies on without checking:

| Domain | Seed dataset | Derived or authored |
|---|---|---|
| `reference_list` | `app/data/ASCII/trantype.txt`, `trancatg.txt` | derived; 0 authored rows |
| `reference_update` | the same two | derived, **except 2 authored rows** -- one free type code and one unreferenced type code, each named and justified in its scenario README |
| `disclosure_group` | `app/data/ASCII/discgrp.txt` | derived; 0 authored rows, and no padding change either |
| `batch_reference_update` | **none exists** | **authored.** The 53-byte `WS-INPUT-REC` is an action-coded maintenance record and `app/data/ASCII/` holds no such file |
| `date_conversion` | **none exists** | **authored.** The 1000-byte MQ request is a payload, not a dataset record |

Assumptions: the seed rows are **not** uniformly the declared record width, so
"derived" includes a padding normalisation that is not a content change.
`app/data/ASCII/trantype.txt` carries rows of both 60 and 61 characters and
`trancatg.txt` carries 61, while every fixture row here is exactly the declared 60.
`discgrp.txt` needs no normalisation because its rows are already 50. A reader
comparing a fixture row against a seed row byte-for-byte will otherwise conclude the
row was authored when only its padding changed.

### 1.2 Why a documentation file exists in a fixtures directory

No row of the migration plan asks for a README at this path. The plan asks for
*"fixture records derived from the copybook layouts"* - records, not
documentation. This file exists because **user-specified Rule 1
(Explainability)** requires a docstring on every new module entry point (L15),
and a fixed-width `.txt` has no docstring construct at all. Rule 1 L22 names
the per-language formats and closes with `etc.`, which licenses a
language-appropriate analogue; for a directory of comment-less data files the
analogue is a tree-scope document plus one document per scenario.

The house fixture tree reached the same conclusion for the same reason and
records it at `tests/fixtures/README.md` L59-L64. This file is the
reference-service instance of that pattern, not an improvisation of it.

### 1.3 What this file does not do

It authors no record bytes, no SQL, no Java and no schema. The record bytes
belong to the scenario directories (section 8); the schema and seed belong to
`V1__reference.sql` and `V2__seed_reference.sql` (section 7.4). This file is a
**derived index** into the copybooks, never a competing declaration of them
(section 5.8).

---

## 2. Governing rules

### 2.1 User-specified Rule 1 (Explainability)

Rule 1 is the only user-specified rule on this project. Its full text is
available through the project rules document; it is cited here by clause, never
reproduced.

- **L15** requires a docstring on every new module entry point. This file is the
  entry point of the fixture module, so it carries all four docstring elements
  at tree scope - see the table above.
- **L18 to L21** enumerate those elements: Purpose, Parameters, Return values,
  and Exceptions or errors *where applicable*.
- **L23** permits a single-line docstring for a trivial accessor. **There is no
  analogue of that relief for a data file**, and it is not invoked here. A
  fixture's meaning is entirely in its derivation, so a thin document would
  discharge nothing.
- **L27** requires a comment to sit adjacent to what it explains. That is the
  rules-grounded basis for the per-scenario obligation in section 8.4: a
  scenario's byte-level justification belongs in that scenario's own directory,
  not aggregated here.
- **L28** requires comments to explain WHY, not WHAT. Applied here: a `WHAT:`
  line in the header block or a section preamble states a *role*, which is
  purpose and is required; a `WHAT:` line beside a single record row or literal
  would restate the data and is forbidden.
- **L40** forbids leaving a non-obvious choice undocumented when a reasonable
  alternative exists. Section 6 enumerates the eight such choices in this tree
  and labels each one.
- **L41** forbids vague rationale without specific justification. Every
  rationale in section 6 names the rejected alternative **and** the concrete
  consequence of taking it.
- **L43** is the validation gate, and it is conjunctive: missing *either* the
  docstring *or* the decision rationale fails review. L43 is the clause to cite
  for the obligation, because L29 says only "should" while L43 says "must".

One point of attribution, so this document does not overclaim: **L43's triad is
"purpose, parameters, and return values" - Exceptions is not in it.** The
failure-modes content in section 12 rests on **L21**, which qualifies
Exceptions with *where applicable*, together with the house convention at
`tests/README.md` L544-L549, which names all four elements explicitly.

### 2.2 The four canonical labels, and the one that is unavailable here

Rule 1 L31 to L34 name four rationale categories. Three of them are used in
this tree:

```text
Alternatives Considered:
Assumptions:
Trade-offs:
```

`Assumptions` and `Trade-offs` are **plural**, `Trade-offs` uses the ASCII
hyphen-minus, and the trailing colon is part of the label.

**A label carries no emphasis markup, in this file as in every other.** The rules
document renders the four category names in bold, and that bold is its own
typography rather than part of the label;
[`docs/CODE_DOCUMENTATION_STANDARD.md`](../../../../../../docs/CODE_DOCUMENTATION_STANDARD.md)
states the one permitted written form and names `**Assumptions:**` explicitly as
wrong in Markdown as well as in code. Every rationale in this file is therefore
written unemphasised, as `Assumptions: ` and not as `**Assumptions:** `.

Refactoring Rationale: this file previously argued only that the bold was "not
part of the label" and then wrote every one of its fifteen rationales in bold
anyway. The argument was right and the practice contradicted it, which is worse
than either alone: a reviewer auditing the tree for Rule 1 compliance finds every
rationale by literal string search across seven languages, and an emphasised
label is a rationale that search does not return. All fifteen were converted, and
every rationale added to this file since is written the same way, so a single search for `Assumptions:` finds this file's rationales
and the `.sql`, `.tf`, `.java` and `.ts` rationales in the same pass.

The fourth label, `Refactoring Rationale`, is **factually unavailable in this
tree**. L32 scopes it by its own definition to *replacing existing code*, and
nothing here replaces anything: this tree is purely additive beside the
untouched COBOL baseline and beside the untouched `tests/fixtures/` oracle tree,
which stays exactly where it is. This directory is not a relocation of that one
(section 6.7). The label's precondition is never met, so it appears nowhere in
this file. Where a derived fixture departs from its seed or from a house
default, the correct label is `Alternatives Considered:`.

For contrast, the house tree's use of that same label at
`tests/fixtures/README.md` L714 is substantively correct, because L715 to L719
record that the wording there genuinely replaced an earlier, weaker "should".
Here there is no prior revision to replace, so the label is unavailable on the
merits rather than merely by convention.

The plural orthography is also the settled convention of this module. The
sibling `V1__reference.sql` in this same service uses `Assumptions:` twelve
times, `Alternatives Considered:` five times and `Trade-offs:` twice.

### 2.3 This is a review gate with no mechanical fallback

`config/checkstyle/checkstyle.xml` L185 sets its `Checker` to
`fileExtensions="java"`, with its own adjacent note at L176 recording that only
Java carries Javadoc. **No file in this directory is ever scanned by
Checkstyle.** The gate is triple-anchored and every anchor is human:

1. Rule 1 L43 - code missing either element *"fails review"*.
2. `tests/README.md` L549 - *"This is a hard review gate."*
3. The `fileExtensions="java"` scoping, which removes any linter fallback.

**Authoring discipline is the only protection this tree has.**

`config/checkstyle/suppressions.xml` does carry an entry matching
`src/test/resources/fixtures/`, but it must not be presented as satisfying Rule
1. Because the `Checker` is already narrowed to Java, non-Java files under
`resources/` are outside the audit set regardless; that file says so itself at
L228 to L232 and describes its own entry as belt-and-braces for the narrow case
of a `.java` file generated into or co-located with a fixtures directory. A
suppression permits a path. It authors no content and discharges no obligation.

### 2.4 The migration transformation rules that bear on these bytes

These are a different namespace from the user-specified rule above and are
always written out in full to keep the two distinct.

- **Rule T1 (Copybook is normative).** A field's `PICTURE` determines its target
  column type, its Java type and its byte offset. `FILLER` is dropped in the
  target, and the drop is recorded per record - section 6.2 records all five.
  No field is renamed; none of the three documented baseline misspelling
  corrections falls in these five layouts. This is why every layout row in
  section 3 cites a copybook line: the copybook is the authority and this
  document is an index into it.
- **Rule T3 (Money never leaves fixed point).** `NUMERIC(p,2)` in SQL,
  `BigDecimal` at scale 2 in Java, `Decimal` in Python, and a JSON string on the
  wire; `float` and `double` are forbidden in the money path. Here it governs
  `DIS-INT-RATE PIC S9(04)V99`, which becomes
  `reference.disclosure_groups.interest_rate NUMERIC(6,2)`. **A fixture must
  never encode a rate as a float-looking literal**: the stored form is six
  zoned-decimal bytes with a sign overpunch and no literal decimal point at all
  (section 5.4).
- **Rule T4 (Arithmetic order is preserved).** The consumer of these rates
  multiplies at full precision and only then divides. A fixture supplies the
  exact rate bytes; it never pre-computes a rounded product.
- **Rule T8 (User-visible strings are verbatim).** Every description **carried
  across from the seed** keeps its exact capitalisation and hyphenation. Section
  7 lists all 25 such values -- 7 `trantype` and 18 `trancatg`, measured -- and
  section 6.6 names the derivation source that would break this.

  Four descriptions in this tree are **authored, not carried**, and Rule T8 has
  no subject for any of them: each belongs to a type code the seed does not
  contain, so there is no baseline string to preserve. They are enumerated here
  rather than left for a reader diffing the fixtures against section 7 to find.

| Fixture | Code | Authored description | Why no seeded value can play the part |
|---|---|---|---|
| `batch_reference_update/add_record/trtype-update.txt` | `08` | `Fee Assessment` | The `'A'` action inserts. Codes `01` to `07` are all seeded, so inserting one asserts a duplicate key rather than the add path |
| `batch_reference_update/add_record/trtype-update.txt` | `09` | `Chargeback` | The second insert needs a second free code, and `09` follows `08` |
| `reference_update/delete_restricted_by_category/trantype.txt` | `99` | `Unreferenced type for delete fixture` | Needs a type **no** category references. Measured, all seven seeded types are referenced, so none can serve |
| `reference_update/happy_path/trantype.txt` | `08` | `Fixture Add Path Type` | The same duplicate-key reason as the batch add path, for the online flow |

  Assumptions: the two fixtures that both use code `08` are independent files
  for independent flows -- one a 53-byte batch input, the other a 60-byte table
  image -- and neither is a copy of the other, so the differing descriptions are
  not drift between two copies of one value. Each authored description follows
  the seed's own Title Case style, so a case-sensitive comparison against a
  seeded row still fails loudly if a carried value is ever tidied.

---

## 3. The five record layouts

### 3.1 Offsets in this document are 0-BASED

**Every offset in every table below is 0-based: the first byte of a record is at
offset 0.** This is stated as its own paragraph because the base cannot be
inferred and both conventions are genuinely in use in this repository:

| Source | Convention | Same field, `DIS-INT-RATE` |
|---|---|---|
| This document, sections 3.2 to 3.6 | **0-based** | offset 16, length 6 |
| `com.carddemo.common.codec.CopybookLayout` | **0-based** | `signedZoned("DIS-INT-RATE", 16, 4, 2)` |
| `app/jcl/DISCGRP.jcl` L40 `KEYS(16 0)` - IDCAMS key operands | **0-based** | key at offset 0, length 16 |
| `tests/fixtures/README.md` sec 5.7 (L437 to L450) | **1-based** | bytes `17-22` |
| `app/jcl/TRANREPT.jcl` DFSORT `SYMNAMES` | **1-based** | n/a for this record |

Both descriptions are correct for the same six bytes. A reader who does not know
which base is in force is off by one on **every** field, so the base is declared
once here and never mixed. Section 6.8 records the choice with its rationale.

### 3.2 `TRAN-TYPE-RECORD` - 60 bytes

Source: `app/cpy/CVTRA03Y.cpy`, 10 lines. The banner at L2 declares
`RECLN = 60`; L4 declares `01 TRAN-TYPE-RECORD.`.

| Copybook line | Field | `PICTURE` | Offset | Length | Fill |
|---|---|---|---:|---:|---|
| L5 | `TRAN-TYPE` | `X(02)` | 0 | 2 | right-pad space |
| L6 | `TRAN-TYPE-DESC` | `X(50)` | 2 | 50 | right-pad space |
| L7 | `FILLER` | `X(08)` | 52 | 8 | ASCII `'0'`, copied verbatim |

Byte-count check: 2 + 50 + 8 = **60**.

Every field is `PIC X(n)`, so no field in this record carries a sign and none is
overpunched. `FILLER` is dropped in the target: **8 bytes dropped.**

### 3.3 `TRAN-CAT-RECORD` - 60 bytes

Source: `app/cpy/CVTRA04Y.cpy`, 12 lines. The banner at L2 declares
`RECLN = 60`; L4 declares `01 TRAN-CAT-RECORD.`; **L5 declares
`05 TRAN-CAT-KEY.`, a 6-byte group that occupies no bytes of its own.**

| Copybook line | Field | `PICTURE` | Offset | Length | Fill |
|---|---|---|---:|---:|---|
| L6 | `TRAN-TYPE-CD` (level 10, in `TRAN-CAT-KEY`) | `X(02)` | 0 | 2 | right-pad space |
| L7 | `TRAN-CAT-CD` (level 10, in `TRAN-CAT-KEY`) | `9(04)` | 2 | 4 | left-pad `'0'` |
| L8 | `TRAN-CAT-TYPE-DESC` | `X(50)` | 6 | 50 | right-pad space |
| L9 | `FILLER` | `X(04)` | 56 | 4 | ASCII `'0'`, copied verbatim |

Byte-count check: 2 + 4 + 50 + 4 = **60**. `FILLER` dropped: **4 bytes.**

`TRAN-CAT-CD` is **unsigned display** - plain ASCII digits with **no
overpunch**. Its target column is `CHAR(4)`, not an integer type, so the literal
`0001` form with its leading zeros survives end to end.

> Assumptions: the group name `TRAN-CAT-KEY` is **not** globally unique in
> the copybook corpus. `CVTRA04Y` L5 brackets a 6-byte group under that name,
> while the transaction-category-balance record brackets a **17-byte** group
> under the same name. `CopybookLayout` records this collision as the reason
> field names resolve per layout rather than globally. A tool that looks a field
> up by name alone, across layouts, will silently read the wrong width.

### 3.4 `DIS-GROUP-RECORD` - 50 bytes

Source: `app/cpy/CVTRA02Y.cpy`, 13 lines. The banner at L2 declares
`RECLN = 50`; L4 declares `01 DIS-GROUP-RECORD.`; L5 declares
`05 DIS-GROUP-KEY.`.

| Copybook line | Field | `PICTURE` | Offset | Length | Fill |
|---|---|---|---:|---:|---|
| L6 | `DIS-ACCT-GROUP-ID` (level 10, in `DIS-GROUP-KEY`) | `X(10)` | 0 | 10 | right-pad space |
| L7 | `DIS-TRAN-TYPE-CD` (level 10, in `DIS-GROUP-KEY`) | `X(02)` | 10 | 2 | right-pad space |
| L8 | `DIS-TRAN-CAT-CD` (level 10, in `DIS-GROUP-KEY`) | `9(04)` | 12 | 4 | left-pad `'0'` |
| L9 | `DIS-INT-RATE` | `S9(04)V99` | 16 | 6 | left-pad `'0'`, sign in last byte |
| L10 | `FILLER` | `X(28)` | 22 | 28 | ASCII `'0'`, copied verbatim |

Byte-count check: 10 + 2 + 4 + 6 + 28 = **50**. `FILLER` dropped: **28 bytes.**

**All three key fields are level 10 inside `DIS-GROUP-KEY`**, so the read key is
the first 16 bytes: `DIS-ACCT-GROUP-ID + DIS-TRAN-TYPE-CD + DIS-TRAN-CAT-CD`.
`tests/fixtures/README.md` L448 to L450 says the same thing in prose, and
`app/jcl/DISCGRP.jcl` L40 `KEYS(16 0)` states the same 16 bytes at offset 0 as a
dataset definition.

`DIS-INT-RATE` is the only **zoned decimal with sign overpunch and an implied
decimal point** in this tree, and the only zoned field in the whole copybook
corpus with four integer digits. Rule T3 governs it; see section 5.4.

### 3.5 Three independent sources agree on these three records

The geometry above does not rest on the copybooks alone. Two further in-repo
sources state it, and all three agree exactly:

| Record | Copybook banner | IDCAMS cluster definition | `CopybookLayout` |
|---|---|---|---|
| `TRAN-TYPE-RECORD` | `CVTRA03Y` L2 `RECLN = 60` | `app/jcl/TRANTYPE.jcl` L41 `RECORDSIZE(60 60)`, L40 `KEYS(2 0)` | `RecordSpec("TRANTYPE", 60, 2, 0)` |
| `TRAN-CAT-RECORD` | `CVTRA04Y` L2 `RECLN = 60` | `app/jcl/TRANCATG.jcl` L41 `RECORDSIZE(60 60)`, L40 `KEYS(6 0)` | `RecordSpec("TRANCAT", 60, 6, 0)` |
| `DIS-GROUP-RECORD` | `CVTRA02Y` L2 `RECLN = 50` | `app/jcl/DISCGRP.jcl` L41 `RECORDSIZE(50 50)`, L40 `KEYS(16 0)` | `RecordSpec("DISGROUP", 50, 16, 0)` |

The IDCAMS key lengths independently confirm the group widths derived from the
copybook: `TRAN-TYPE` is 2; `TRAN-CAT-KEY` is 2 + 4 = 6; `DIS-GROUP-KEY` is
10 + 2 + 4 = 16. The `CopybookLayout` field lists declare the same per-field
offsets this document tables, in the same 0-based convention.

**Use this table when self-checking a fixture.** If a derived record disagrees
with any one of the three columns, the record is wrong - not the sources.

### 3.6 `WS-INPUT-REC` - 53 bytes

Source: `app/app-transaction-type-db2/cbl/COBTUPDT.cbl`, 237 lines. L71 declares
`01 WS-INPUT-REC.`. This is a program working-storage record rather than a
copybook, because the batch reference-update flow reads its input directly into
working storage.

| Line | Field | `PICTURE` | Offset | Length | Fill |
|---|---|---|---:|---:|---|
| L72, with L73 `VALUE SPACES.` | `INPUT-REC-TYPE` | `X(1)` | 0 | 1 | space |
| L74, with L75 `VALUE SPACES.` | `INPUT-REC-NUMBER` | `X(2)` | 1 | 2 | right-pad space |
| L76, with L77 `VALUE SPACES.` | `INPUT-REC-DESC` | `X(50)` | 3 | 50 | right-pad space |

Byte-count check: 1 + 2 + 50 = **53**.

Four facts about this record are easy to get wrong:

1. **`PIC` and `VALUE` sit on separate lines for all three fields.** A layout
   extractor that assumes one declaration per line mis-reads this record. The
   file also carries legacy sequence numbers in columns 73 to 80 (for example
   `00592033` on L72); identification-area content must never leak into a
   derived layout.
2. **This record has no `FILLER` at all** - 1 + 2 + 50 = 53 exactly. All three
   fields are `PIC X(n)`, so the generic right-pad-with-spaces rule governs the
   whole record and neither `FILLER` regime in section 5.3 applies to it.
   `FILLER` dropped: **none.**
3. **L101 reads `READ TR-RECORD NEXT RECORD INTO WS-INPUT-REC` - a sequential
   read.** A fixture is therefore a plain sequence of 53-byte rows with no
   key-ordering requirement beyond the scenario's own intent.
4. The dispatch at L109 to L129 is what a fixture in this domain exercises. L110
   is `EVALUATE INPUT-REC-TYPE`, and the branches are:

| Byte 0 | Line | Behaviour |
|---|---|---|
| `'A'` | L111, L113 | performs the insert paragraph |
| `'U'` | L114, L116 | performs the update paragraph |
| `'D'` | L117, L119 | performs the delete paragraph |
| `'*'` | L120, L121 | displays `'IGNORING COMMENTED LINE'`; **no database action** |
| anything else | L122, L124, L128 | builds `'ERROR: TYPE NOT VALID'` and performs `9999-ABEND`, which displays it, moves `RETURN-CODE` to 4 and returns to the loop |

Two consequences follow, and both must be stated so that neither is
over-generalised:

- **An invalid type byte is reported and warn-tiered, and the run continues.**
  Despite its name, `9999-ABEND` at L230 to L233 does not abend: its whole body
  is `DISPLAY WS-RETURN-MSG`, `MOVE 4 TO RETURN-CODE`, `EXIT`, with no
  `CALL 'CEE3ABD'` and no `STOP RUN` in it. Control returns through
  `1003-TREAT-RECORD` to the loop at L93 to L96, which reads the next record, so
  every later row is still processed and the program ends on the warn-tier
  `RETURN-CODE` 4. A fixture carrying a stray byte in position 0 therefore
  asserts a soft reject **plus continuation**, not a failed run. It is still not
  a quietly-ignored row -- unlike a `'*'` row it is reported and it moves the
  return code -- but the distinction is warn versus silent, not fail versus
  skip. The message `'ERROR: TYPE NOT VALID'` is verbatim under Rule T8.
  Assumptions: the paragraph NAME is the only thing here suggesting
  termination, which is exactly why this is stated; the program's one
  `STOP RUN` sits at L99, unreachable after the `EXIT` at L98, and is not on
  this path.
- **A `'*'` in byte 0 makes the row a program-recognised comment** - the one
  place in this entire tree where anything comment-like sits inside a
  fixed-width record. That row is nonetheless **still exactly 53 bytes and still
  data** to any length-checking loader; only the program interprets byte 0 as a
  comment marker. So the statement in the header block, that a fixed-width
  record file cannot carry a comment, remains true exactly as written: a `#`
  line would be a wrong-length row and a hard failure. The `'*'` case is a
  **typed record**, not a free comment.

### 3.7 `REQUEST-MSG-COPY` - 1000 bytes

Source: `app/app-vsam-mq/cbl/CODATE01.cbl`, 524 lines. L109 declares
`01 REQUEST-MSG-COPY.`.

| Line | Field | `PICTURE` and `VALUE` | Offset | Length | Fill |
|---|---|---|---:|---:|---|
| L110 | `WS-FUNC` | `X(04)` `VALUE SPACES` | 0 | 4 | right-pad space |
| L111 | `WS-KEY` | `9(11)` `VALUE ZEROES` | 4 | 11 | left-pad `'0'` |
| L112 | `WS-FILLER` | `X(985)` `VALUE SPACES` | 15 | 985 | **space**, per `VALUE SPACES` |

Byte-count check: 4 + 11 + 985 = **1000**. `FILLER` dropped: **985 bytes.**

Five properties of this declaration matter:

1. **Non-monotonic level numbering.** L109 is level `01` and its subordinates
   jump straight to level **10** with no intervening `05`. Harmless to the
   layout, but it defeats a parser that assumes levels advance by a fixed step.
2. **All four of L109 to L112 carry the identical legacy sequence number
   `011700` in columns 1 to 6** - four distinct declarations sharing one
   sequence number, which collapses under any tooling keyed on that area.
3. **`WS-KEY` is unsigned and left-padded with `'0'`.** The record is populated
   by a group `MOVE` at L322 from the raw 1000-byte buffer declared at L106, and
   re-initialised numerically at L294 by
   `INITIALIZE REQUEST-MSG-COPY REPLACING NUMERIC BY ZEROES`.
4. **`WS-KEY` is identity-shaped.** It is 11 digits, and this program's only
   file is ACCTDAT - L115 and L116 declare
   `LIT-ACCTFILENAME PIC X(8) VALUE 'ACCTDAT '` - whose key `ACCT-ID` is
   `PIC 9(11)`. The widths match exactly, so `WS-KEY` is best described as **an
   11-digit ACCTDAT key**, which is all the program itself establishes. This is
   what triggers the attestation obligation in section 8.5.
5. **Neither `WS-FUNC` nor `WS-KEY` is ever read.** Measured across all 524
   lines, the two fields appear at exactly three places: their declarations at
   L110 and L111, the numeric re-initialisation at L294, and the group `MOVE`
   at L322 that *fills* them. No `IF`, no `EVALUATE` and no `MOVE` ever takes a
   value **out** of either one. `4000-PROCESS-REQUEST-REPLY` at L360 issues
   `EXEC CICS ASKTIME`, then `FORMATTIME`, then `STRING`s
   `'SYSTEM DATE : '` and `'SYSTEM TIME : '` with the formatted clock values
   into the reply -- so the reply is a function of the clock alone and is
   **independent of every byte of the request**. Two consequences follow, and
   both bear on how a scenario in this domain may be named. A fixture that
   varies `WS-FUNC` or `WS-KEY` can assert exactly one thing: that the reply is
   produced unchanged regardless of those bytes. It cannot assert a rejection,
   because there is no branch to reject on -- `CODATE01` validates nothing and
   has no error path for a malformed request. Assumptions: this is the reason
   the **envelope** scenario is named `request_payload_ignored` rather than for a
   rejection it cannot reach; a name promising a refusal would describe a
   branch this program does not contain, which is the naming failure section
   8.3 warns against for `batch_reference_update`.

   Assumptions: this prohibition is scoped to a scenario whose SUBJECT is
   `CODATE01`, and the domain also carries one whose subject is not. A directory
   named `invalid_date_rejected` exists alongside `request_payload_ignored`, and
   the refusal in its name belongs to `DateEditValidator` -- invoked with the
   ten-character date and ten-character mask that `CSUTLDTC` declares as
   parameters, travelling BESIDE the envelope rather than inside it. Its bytes
   are a carrier and its own README section 4.3 records that no date lives in
   them. The two names are therefore both correct for what they describe, and
   the boundary is stated here rather than left to be inferred, because the
   paragraph above otherwise reads as forbidding a directory name the tree
   contains. What a scenario in this domain still may **not** claim is a refusal
   performed **by the envelope**: that would name a `CODATE01` branch which,
   measured across all 524 lines of the program, does not exist.

Note the contrast with section 3.6: here `PIC` and `VALUE` share one line, while
`COBTUPDT` splits them across two. Neither style is wrong, and a derivation
tool must tolerate both.

---

## 4. The lookup tables have no fixed-width dataset

This is stated positively rather than left to silence, because silence is
indistinguishable from oversight and because manufacturing a dataset the
baseline does not have is exactly the reasonable-looking alternative a future
author would reach for. Rule 1 L40 makes documenting the omission mandatory.

`app/cpy/CSLKPCDY.cpy` is 1318 lines and expresses its whole domain as **five
`88`-level allow-lists**, with the literal counts measured directly:

| Copybook line | Condition name | Literals |
|---|---|---:|
| L30 | `VALID-PHONE-AREA-CODE` | 490 |
| L521 | `VALID-GENERAL-PURP-CODE` | 410 |
| L931 | `VALID-EASY-RECOG-AREA-CODE` | 80 |
| L1013 | `VALID-US-STATE-CODE` | 56 |
| L1073 | `VALID-US-STATE-ZIP-CD2-COMBO` | 240 |

All three of its `01` levels are single **edit fields, not record layouts**: L24
`WS-US-PHONE-AREA-CODE-TO-EDIT PIC XXX`, L1012 `US-STATE-CODE-TO-EDIT PIC X(2)`
and L1071 `US-STATE-ZIPCODE-TO-EDIT`. There is no `.txt` seed for any of them:
`app/data/ASCII/` holds exactly nine files - `acctdata`, `carddata`, `cardxref`,
`custdata`, `dailytran`, `discgrp`, `tcatbal`, `trancatg`, `trantype` - and none
of them is a lookup dataset.

Two different totals are both correct, so both are recorded. **410 + 80 = 490**,
which means the general-purpose and easy-recognition lists **partition** the
phone-area-code list with an empty intersection. Distinct seeded **rows** are
490 + 56 + 240 = **786** across three tables, while total **literals** across
all five lists are 1276. That partition is why `V1__reference.sql` gives
`reference.us_phone_area_codes` a `code_class CHAR(1)` column constrained by
`CHECK (code_class IN ('G', 'E'))` alongside its `CHAR(3)` primary key: **the
flag is the partition.**

> Alternatives Considered: synthesising a fixed-width lookup dataset so that
> the lookup tables could be fixture-driven like the other three. Rejected
> because such a dataset would have **no copybook to be validated against** -
> `CSLKPCDY` declares edit fields and `88`-level value sets, not a record
> layout with offsets - so nothing in this repository could establish whether
> its bytes were right, and the three-source cross-check in section 3.5 would
> have no columns to fill. The consequence of adopting it would be a fixture
> that always loads and never proves anything.

**Any lookup-related expectation is therefore a count or membership assertion
against `V2__seed_reference.sql`, never a record file in this tree.** The
numbers to assert against are in section 7.4.

---

## 5. The encoding contract

This section is binding on every record file in this tree. It is the most
error-prone material here, and it should be read in full before authoring any
fixture. Each rule cites the house passage it inherits, and records the
refinements this tree adds.

### 5.1 Fixed width, positional fields, no delimiters

Inherited from `tests/fixtures/README.md` L135 to L142.

A field's meaning is determined **only** by its byte position within the record.
There are no commas, tabs or separators. Every record line is **exactly** the
record length for its type, and a line one byte short or long shifts every
subsequent field and silently corrupts the record.

> Assumptions: three of the five record lengths in this tree are new to this
> repository. The house passage enumerates the lengths it covers as *350, 300,
> 150, 50, 500, or 80* - **60 is not among them.** This directory introduces the
> **60-byte, 53-byte and 1000-byte** regimes for the first time. Only the
> 50-byte `DIS-GROUP-RECORD` falls inside the pre-existing set. A fixture author
> arriving from the house tree must not assume any tooling has seen 60, 53 or
> 1000 bytes before; section 3.5 and section 11.2 give the cross-checks that do
> exist for them.

### 5.2 Exact-length enforcement, and the only definition of "empty"

Inherited from `tests/fixtures/README.md` L144 to L151, which records this as
verified enforcement rather than guidance.

The loaders **reject** any physical row whose length is not exactly the record
length. Specifically, they do **not**:

- pad short rows,
- truncate long rows, or
- silently drop blank lines.

**The only input treated as empty is a genuinely zero-byte dataset** - zero
bytes, no records. An author who miscounts a width gets a hard failure at load,
not a corrupted record.

The Java side enforces the same contract:
`com.carddemo.common.codec.FixedWidthCodec` declares a `RecordLengthException`
for exactly this case, so a wrong-length record fails on decode rather than
decoding into shifted fields.

> Assumptions: the deliberate stance behind this, stated at
> `tests/fixtures/README.md` L150 to L151, is that a malformed monetary record
> must never be silently coerced into a well-formed-looking one. The rejected
> alternative - treating a blank line as "no record" and tolerating an
> off-length row - would quietly accept the row and shift every field after the
> error, producing a fixture that loads cleanly and asserts the wrong values.
> A loud failure at load is the only outcome that surfaces the mistake.

This is not hypothetical in this tree. **Five** of the eighteen record files here
are genuinely zero-byte -- one per `empty_input` scenario, of which
`reference_list/empty_input` carries two because the list screen reads both
reference datasets -- and each is 0 bytes with 0 carriage returns and 0 line
feeds. The oldest of them, `date_conversion/empty_input/date-request.txt`, is the
one that established the convention the other four follow, and it is the case
worth naming because a zero-byte resource is the one a copy step is most likely to
drop. That is the correct and only encoding of an empty 1000-byte-record input. A
file containing a single blank line would be a 1-byte file holding one zero-length
record, and would fail.

### 5.3 Padding, and the two opposite `FILLER` regimes

The generic **value**-padding rule comes from `tests/fixtures/README.md` L152 to
L156 and describes how a value is placed into a field:

| Field class | Padding |
|---|---|
| Text, `PIC X(n)` | pad on the **right with spaces** |
| Unsigned numeric, `PIC 9(n)` | pad on the **left with `'0'`** |
| Signed numeric, `PIC S9(n)V99` | pad on the **left with `'0'`**, sign carried in the last byte by overpunch |

That rule governs values. It says nothing about `FILLER`, which carries no
value - so the following is a refinement this document adds, and it matters
because **there are two opposite `FILLER` fill regimes and applying one to the
other's record corrupts it.**

| Record | `FILLER` offset and length | Fill byte | Established by |
|---|---|---|---|
| `TRAN-TYPE-RECORD` | 52, 8 | ASCII `'0'` (0x30) | measured in `app/data/ASCII/trantype.txt`, uniform across all 7 rows |
| `TRAN-CAT-RECORD` | 56, 4 | ASCII `'0'` | measured in `trancatg.txt`, uniform across all 18 rows |
| `DIS-GROUP-RECORD` | 22, 28 | ASCII `'0'` | measured in `discgrp.txt`, uniform across all 51 rows |
| `WS-INPUT-REC` | none | n/a | 1 + 2 + 50 = 53 exactly |
| `REQUEST-MSG-COPY` | 15, 985 | **space** | `CODATE01.cbl` L112 declares `VALUE SPACES` |

**The two regimes coexist one byte apart inside the single 1000-byte
`REQUEST-MSG-COPY` record.** `WS-KEY` is `'0'`-left-padded at bytes 4 to 14
(unsigned numeric, `VALUE ZEROES` at L111, reinforced by the numeric
re-initialisation at L294), while `WS-FILLER` is space-filled at bytes 15 to 999.
The distinction is not merely across files; it is across a single byte boundary
in one record.

> Alternatives Considered: applying the generic space-padding rule uniformly
> to `FILLER` as well as to values, which would be simpler and would need no
> per-record measurement. Rejected because it produces **twenty-eight spaces
> where `discgrp` has twenty-eight ASCII zeros** - a different 50 bytes, and
> therefore a different record, that no length check would catch. The ruling is
> that **`FILLER` content is copied byte for byte from the source row and is
> never synthesised from the padding rule.**

Under Rule T1, `FILLER` is dropped in the target schema, and the drop is
recorded per record: 8 bytes for `TRAN-TYPE-RECORD`, 4 for `TRAN-CAT-RECORD`, 28
for `DIS-GROUP-RECORD`, none for `WS-INPUT-REC`, 985 for `REQUEST-MSG-COPY`.

### 5.4 Zoned decimal sign overpunch

Inherited from `tests/fixtures/README.md` L186 to L234. In this tree it applies
to exactly one field, `DIS-INT-RATE`.

A signed numeric field contains **no** `+` or `-` character and **no** literal
decimal point. The sign is folded into the last byte together with that byte's
digit:

| Sign | 0 | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 |
|---|---|---|---|---|---|---|---|---|---|---|
| positive | `{` | `A` | `B` | `C` | `D` | `E` | `F` | `G` | `H` | `I` |
| negative | `}` | `J` | `K` | `L` | `M` | `N` | `O` | `P` | `Q` | `R` |

**Position in the string is the digit value**, which is why encoding is a direct
index and decoding a direct index lookup. The same two tables appear in
`tests/helpers/record_codec.py` at L137 and L138 as
`_POS_OVERPUNCH = "{ABCDEFGHI"` and `_NEG_OVERPUNCH = "}JKLMNOPQR"`. **The
overpunch replaces the low-order digit only, never the whole field.**

The house document's four worked vectors are canonical; self-check against them:

| Source | `PICTURE` | Value | Encoded |
|---|---|---|---|
| L213 to L217 | `S9(10)V99` | `+194.00` | `00000001940{` |
| L219 to L223 | `S9(09)V99` | `+504.77` | `0000005047G` |
| L225 to L228 | `S9(09)V99` | `-919.00` | `0000009190}` |
| **L230 to L234** | **`S9(04)V99`** | **`15.00`** | **`00150{`** |

The second vector is worth spelling out, because reversing it is the classic
overpunch error: **`G` is +7 and it is the LAST digit, so the value is 504.77,
not 50.47.**

The fourth vector is literally this tree's case, and the house document says so -
it identifies `00150{` as the `DIS-INT-RATE` of the first row of `discgrp`.
Walking it: `15.00` gives the six digits `001500`; the first five characters
carry `00150`; the sixth digit is `0` with a positive sign, which overpunches to
`{`; the result is **`00150{`**. Measured against the seed, row 1 of
`discgrp.txt` is exactly
`A00000000001000100150{0000000000000000000000000000`, which confirms it.

**Only three distinct raw rate values exist across all 51 rows of the seed**, and
a fixture in this tree should contain no others unless its scenario README says
why:

| Raw bytes | Decoded value | Occurrences in the seed |
|---|---|---:|
| `00000{` | `0.00` | 30 |
| `00150{` | `15.00` | 15 |
| `00250{` | `25.00` | 6 |

**Implied decimal**, from `tests/fixtures/README.md` L236 to L242: `V` marks a
decimal point that occupies **no byte**. `V99` means the last two digits of the
field are cents, and **no literal `.` is stored**. Rule T3 is what makes this
non-negotiable: the value stays exact fixed point from these bytes through
`NUMERIC(6,2)` and `BigDecimal` to a JSON string, and never passes through a
binary floating-point type.

> Assumptions: a trailing letter is a sign byte **only** in a signed field.
> Values such as `3580010001P` look overpunched but are unsigned identifier
> fields, where the trailing character is data. **Never apply overpunch decoding
> to a `9(n)` field.** In this tree that means `TRAN-CAT-CD`, `DIS-TRAN-CAT-CD`
> and `WS-KEY` are plain digits with no sign byte - which is exactly how
> `CopybookLayout` declares them, using `uint(...)` for the first two and
> reserving `signedZoned(...)` for `DIS-INT-RATE` alone.

One naming trap needs scoping correctly, because two in-repo statements about the
same mapping appear to disagree and do not. `tests/README.md` L273 records that
`-fsign=EBCDIC` is required when compiling the COBOL, because the default
`-fsign=ASCII` misreads the zoned-decimal sign overpunch and silently corrupts
negative balances. `tests/helpers/record_codec.py` L135 calls that same mapping
*"the canonical IBM ASCII trailing-sign mapping"*. Both are correct and describe
different things: the overpunch **characters** are ASCII-printable, while the
**convention** they implement is the EBCDIC one. That compiler flag is a fact
about building the COBOL oracle; **it is not an encoding instruction for the
ASCII bytes in this tree**, and it is relevant only where zoned decimal occurs
at all - that is, `DIS-GROUP-RECORD`, and neither `trantype.txt` nor
`trancatg.txt`, which are pure `PIC X(n)` plus one unsigned `PIC 9(04)`.

### 5.5 Line endings

The house default is stated at `tests/fixtures/README.md` L168: *"LF is
therefore the default for all new fixtures."* The reason, at L164 to L168, is
that the loader treats each physical line as exactly one fixed-length record, so
a stray carriage return is absorbed into the trailing field or `FILLER` and
pushes the record one byte over the record length. L170 to L174 name `tcatbal`,
`trancatg` and `trantype` as the seeds that ship CRLF.

**The three seeds this tree derives from do not share one convention.** Measured
directly:

| Seed | CR | LF | Bytes | Composition |
|---|---:|---:|---:|---|
| `trantype.txt` | 6 | 7 | 433 | `7 x 60 + 6 x 2 + 1` - **CRLF on rows 1 to 6, bare LF on row 7** |
| `trancatg.txt` | 18 | 18 | 1116 | `18 x 60 + 18 x 2` - uniformly CRLF |
| `discgrp.txt` | 0 | 51 | 2601 | `51 x 50 + 51` - LF only |

The mixed shape is **systematic, not a `trantype` quirk**: `tcatbal.txt`
measures 49 CR, 50 LF and 2599 bytes, the same CRLF-with-a-bare-LF-final-row
pattern. Two of the three CRLF-shipping seeds end their last row with a bare LF;
only `trancatg` is uniformly CRLF.

**The ruling for this tree: every record file is derived to LF only.** Section
6.1 records the decision with its label. A fixture that intentionally preserves
CRLF must say so in its own scenario README, and must then also state which of
the two source shapes it is preserving.

### 5.6 Exactly one trailing newline

Inherited from `tests/fixtures/README.md` L176 to L184. End every record file
with a **single newline after the last record**, so that `wc -l` equals the
record count and CI has a cheap integrity check.

**No blank lines between records, and no second trailing blank line.** An empty
line is a zero-length record that fails fixed-width parsing (section 5.2). The
sole exception is the zero-byte file, which has no records and therefore no
trailing newline either.

### 5.7 Acceptance arithmetic

Because every record file is LF only with one trailing newline, its size is
fully determined:

```text
bytes = rows x RECLN + rows
```

Verified vectors, so an author can self-check without re-measuring the seeds:

| Fixture | Arithmetic | Bytes |
|---|---|---:|
| full `trantype` derived to LF | `7 x 60 + 7` | 427 |
| full `trancatg` derived to LF | `18 x 60 + 18` | 1098 |
| full `discgrp`, already LF | `51 x 50 + 51` | 2601 |
| single-group `discgrp` subset | `17 x 50 + 17` | **867** |
| single-row `discgrp` subset | `1 x 50 + 1` | **51** |
| an `N`-row 53-byte input | `N x 53 + N` | - |
| an `N`-row 1000-byte request | `N x 1000 + N` | - |
| an empty dataset of any record type | zero-byte file | 0 |

The two highlighted rows are independent confirmations of the rule rather than
predictions: `tests/fixtures/interest/default_fallback/discgrp.txt` measures
exactly 867 bytes with 17 rows and zero carriage returns, and both
`tests/fixtures/interest/happy_path/discgrp.txt` and
`tests/fixtures/interest/zero_balance/discgrp.txt` measure exactly 51 bytes with
one row. **A single-row or single-group subset of a 51-row seed is an
established house pattern, not a shortcut.**

Worth stating positively: **all three seeds match their copybook record length
exactly** - 433 = 7x60 + 6x2 + 1, 1116 = 18x60 + 18x2, and 2601 = 51x50 + 51.
The house tree openly carries a mismatch of this kind, documenting `cardxref.txt`
at L674 as a *"36-byte seed / 50-byte copybook"*, so the **absence** of any such
mismatch in these three is a fact worth recording rather than assuming.

### 5.8 Single-sourcing: cite layouts, never re-implement them

`tests/README.md` L540 to L542 states the principle directly: COBOL unit tests
resolve record layouts through the compiler copybook path (`cobc -I app/cpy`)
and *never duplicate a layout; keep it single-sourced from `app/cpy/`*. The Java
analogue of that copybook include path is
`com.carddemo.common.codec.CopybookLayout` in `common-lib`.

**The layout tables in section 3 are a derived index that cites a copybook line
for every field. They are not a competing declaration.** Where they and a
copybook ever disagree, the copybook wins and this document is wrong - that is
what Rule T1 (Copybook is normative) means in practice.

The corresponding instruction for consuming code: **Java-side layout knowledge
belongs in `CopybookLayout` and must never be restated per service.** A test in
this module obtains geometry by asking for the registered layout, not by
hard-coding an offset copied out of the tables above.

---

## 6. The eight derivation decisions

Rule 1 L40 makes each of these mandatory to document, because for every one a
reasonable alternative demonstrably exists. Rule 1 L41 requires each rationale to
be specific, so each names the rejected alternative **and** the concrete
consequence of taking it. Three canonical labels are available here; the
`Refactoring Rationale` label is not, for the reason in section 2.2.

### 6.1 Every record file is derived to LF only

> Trade-offs: the rejected alternative is preserving each seed's original
> line endings, which would be the more literal derivation. It is rejected
> because the seeds disagree with each other and the disagreement carries no
> behavioural meaning: `trancatg` is uniformly CRLF, `discgrp` is uniformly LF,
> and `trantype` is CRLF on rows 1 to 6 with a bare LF on row 7 - so preserving
> origin would leave **row 7 of `trantype` framed one byte differently from every
> other row in the same file**, for no reason a reader could act on. The
> compromise accepted is that a derived file is no longer byte-identical to its
> seed, which is why section 5.7 publishes the LF-only arithmetic (427, 1098,
> 2601) so the divergence is predicted rather than discovered.

### 6.2 `FILLER` is copied byte for byte, not synthesised

> Alternatives Considered: applying the generic value-padding rule of section
> 5.3 to `FILLER` as well, which needs no per-record measurement. Rejected
> because the two regimes are opposite: the three seed datasets zero-fill
> `FILLER` with ASCII `'0'`, while `CODATE01`'s `WS-FILLER` is space-filled per
> its `VALUE SPACES` at L112. Space-padding `discgrp` would put **twenty-eight
> spaces where the seed has twenty-eight zeros** - a different 50 bytes that
> passes every length check and every line-ending check, and fails only as a
> wrong value much later. The two regimes coexist one byte apart inside
> `REQUEST-MSG-COPY`, so there is no file-level shortcut either.

### 6.3 A co-present DEFAULT-fallback scenario must use pair `07|0001`

**This is the single most important fixture-design fact in this tree.** It was
established by diffing the seed group by group.

`discgrp.txt` holds exactly **three group ids x the same seventeen (type,
category) pairs = 51 rows**. The group ids are `A000000000`, `DEFAULT   ` (seven
characters plus three spaces, exactly ten) and `ZEROAPR   `. The seventeen pairs
are 01/0001 to 0004, 02/0001 to 0003, 03/0001 to 0003, 04/0001 to 0003, 05/0001,
06/0001 to 0002, and 07/0001 - each appearing exactly once per group.

Per-group rate distribution, measured:

| Group id | `00000{` (0.00) | `00150{` (15.00) | `00250{` (25.00) |
|---|---:|---:|---:|
| `A000000000` | 6 | 8 | 3 |
| `DEFAULT   ` | 7 | 7 | 3 |
| `ZEROAPR   ` | 17 | 0 | 0 |

`ZEROAPR` is therefore the natural zero-rate control group.

**Sixteen of the seventeen rows are byte-identical between `A000000000` and
`DEFAULT`. The sole difference is pair `07|0001`:**

| Group id | Rate bytes at type `07`, category `0001` | Decoded |
|---|---|---|
| `A000000000` | `00150{` | 15.00 |
| `DEFAULT   ` | `00000{` | 0.00 |
| `ZEROAPR   ` | `00000{` | 0.00 |

> Alternatives Considered: exercising any other (type, category) pair in a
> scenario that asserts the DEFAULT fallback fired while **both** the account's
> own group and `DEFAULT` are present in the fixture. Rejected because on any of
> the other sixteen pairs the two groups return byte-identical rates, so the
> assertion **passes identically whether the fallback fired or not** - it is
> vacuous, and it would keep passing if the fallback were removed entirely.
> Pair `07|0001` is the only discriminator in the seed.

The behaviour being exercised, cited precisely: the lookup key is
`ACCT-GROUP-ID + TRAN-TYPE + TRAN-CAT`. On an invalid key - VSAM file status
**23** - `app/cbl/CBACT04C.cbl` L436 tests that status, L437 moves `'DEFAULT'`
into the group id, and L438 re-reads through the paragraph
`1200-A-GET-DEFAULT-INT-RATE`. If the `DEFAULT` rows are absent, L455 reports the
failure and L458 reaches `9999-ABEND-PROGRAM` at L628, which issues a Language
Environment abend via `CALL 'CEE3ABD'` with ABCODE **999** at L631 and L632. One
framing consequence follows: **a missing `DEFAULT` group presents as a crash in
the batch flow, with no local defect of its own** - so a fixture that omits the
`DEFAULT` group is asserting an abend, and its scenario README must say so.

**This tree's design differs deliberately from the house fixture, and the
difference is not a gap.** `tests/fixtures/README.md` L587 to L592 records that
all seed ACCOUNT rows carry a **blank** `ACCT-GROUP-ID`, so the seed path
naturally triggers the fallback; accordingly
`tests/fixtures/interest/happy_path/discgrp.txt` sets
`ACCT-GROUP-ID = A000000000` to force a direct hit (measured: 51 bytes, 1 row),
and `tests/fixtures/interest/default_fallback/discgrp.txt` supplies only the
`DEFAULT` group (measured: 867 bytes, 17 rows, 0 CR). **The house fixture proves
the fallback by the ABSENCE of the account's own group.** The `07|0001`
discriminator addresses a different and harder case - both groups present in one
fixture - where absence is unavailable as evidence. Both designs are valid, and
neither supersedes the other.

For context on why the rate must stay exact: `tests/fixtures/README.md` L573
states the consuming formula as
`COMPUTE WS-MONTHLY-INT = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200`, the divisor
being twelve months times one hundred, with the worked example of `1000.00` at
rate `15.00` yielding `12.50` asserted exactly and with no floating-point
tolerance. That is Rule T4 (Arithmetic order is preserved) in practice - multiply
at full precision, only then divide - and Rule T3 in the fixture, where the rate
never leaves fixed point.

### 6.4 Pair `01|0005` is an observed baseline property, and a scenario in its own right

`trancatg` contains pair `01|0005` with the description `Interest Amount`.
`discgrp` contains **zero** rows for `01|0005` in any of the three groups -
verified by direct count. That is precisely why `trancatg` carries eighteen pairs
while `discgrp` carries seventeen.

A rate lookup for `01|0005` therefore misses the account's own group **and**
misses the `DEFAULT` fallback, which is the path that drives the re-read and, on
any status other than success, the abend chain in section 6.3. It is also
semantically apt: `01|0005` is the interest program's own generated category,
which by design should never itself accrue interest.

> Assumptions: this document treats the absent pair as an **observed property
> of the baseline data**, and fixtures depend on it staying absent. The rejected
> alternative is adding the seventeen-to-eighteen row that would make `discgrp`
> symmetric with `trancatg`. It is rejected because the asymmetry is the only
> no-rate path the seed offers, so filling it in would delete the negative-path
> scenario and leave nothing in the tree that exercises a lookup miss on both
> the account group and `DEFAULT`.

This is a first-class negative-path scenario. It is **not** a defect, and section
10 of the prose discipline in this document forbids describing it as one.

### 6.5 "Empty" means a zero-byte file and nothing else

> Assumptions: this tree depends on the external contract stated at
> `tests/fixtures/README.md` L144 to L151 and mirrored by
> `FixedWidthCodec.RecordLengthException` - that a row whose length is not
> exactly the record length is rejected outright, with no padding, no truncation
> and no silent dropping of blank lines. The rejected alternative is authoring an
> "empty" scenario as a file containing a single blank line, which reads as empty
> to a human. Its concrete consequence is that the file is not empty at all: it
> is a one-byte file holding one zero-length record, which fails fixed-width
> parsing instead of exercising the empty-input path the scenario intended. The
> five zero-byte files in this tree are each the correct form at 0 bytes, and
> `date_conversion/empty_input/date-request.txt` is the oldest of them.

### 6.6 Descriptions are derived from `app/data/ASCII/`, not from the Db2 control cards

`V2__seed_reference.sql` seeds the reference tables from the VSAM-lineage ASCII
files, and fixtures in this tree follow the same source so that the two agree.

> Alternatives Considered: deriving descriptions from the Db2 control-card
> seeds `app/app-transaction-type-db2/ctl/DB2LTTYP.ctl` and `DB2LTCAT.ctl`, which
> is superficially attractive because they are the reference-data loader for the
> very extension tree these screens come from. Rejected because those files are
> **UPPERCASE on all 25 rows and additionally carry the misspelling
> `'REVERAL'`**. A fixture derived from them would carry uppercase descriptions
> plus a misspelling, would no longer match the rows seeded into the schema, and
> would violate Rule T8 (User-visible strings are verbatim). This is the
> derivation mistake most likely to look reasonable, which is why the rejected
> source is named by path here rather than merely left unmentioned.

### 6.7 There is no `tests/golden` mirror for this tree, and none is to be created

`tests/fixtures/README.md` L92 to L109 defines a one-for-one fixtures-to-golden
mirroring rule, worded `MUST`. Its own rationale at L99 to L103 is that
`tests/helpers/golden_compare.py` pairs each input scenario with its expected
output **purely by path**, so if the two trees drift the comparator cannot locate
the golden master and the test cannot assert.

> Alternatives Considered: mirroring this tree under `tests/golden/` to
> follow that rule literally. Rejected because **the rule is conditioned on a
> consumer this tree does not have.** These fixtures feed Java repository and
> service assertions and `common-lib` codec round-trips; nothing compares a
> program's output against a `.expected` file by path, so a mirror would create
> directories that no comparator reads and that no test could fail on. The
> corroborating fact is that the rule is already not literally universal in the
> existing tree: `tests/golden` covers `interest`, `posting`, `provisioning`,
> `reporting` and `statement`, and does **not** mirror the `export` or `prepost`
> fixture domains.

Two clarifications that must not be blurred:

- **No golden master exists or is promised for `reference-service`.**
  `tests/README.md` section 1.1 records that the online `CO*` programs cannot be
  run end to end without a CICS runtime, which is absent on the runner, so the
  programs behind this service have no golden-master oracle at all. Nothing in
  this tree should be read as claiming or planning one.
- **This directory is not a relocation of `tests/fixtures/`.** That tree is the
  COBOL parity oracle's input tree, it is REFERENCE-ONLY, it stays exactly where
  it is, and it keeps its six domains, twenty scenario directories and twenty
  scenario READMEs. This is a separate, additive tree with a different consumer.
  **No deletion is owed anywhere.**

### 6.8 Offsets are 0-based rather than the house 1-based tables

> Alternatives Considered: tabling offsets 1-based, to match
> `tests/fixtures/README.md` section 5.7 (L437 to L450), which describes this
> same `discgrp` record with `DIS-INT-RATE` at bytes `17-22`. Rejected because
> the consumers of this tree are 0-based: `CopybookLayout` declares
> `signedZoned("DIS-INT-RATE", 16, 4, 2)`, and Java array and `byte[]` slicing
> is 0-based throughout, so a 1-based table would need mental conversion at
> every use and would invite an off-by-one at each one. The trade accepted is a
> deliberate, visible disagreement with the house table for the same record,
> which section 3.1 tabulates side by side so neither can be mistaken for the
> other. **The concrete consequence of not declaring a base at all** - the option
> actually rejected here - **is a one-byte error on every field**, and the
> baseline supports both readings: IDCAMS `KEYS(16 0)` is 0-based while DFSORT
> `SYMNAMES` positions are 1-based.

---

## 7. Verbatim seed content

Rule T8 (User-visible strings are verbatim) governs every value below. They are
reproduced here so that a fixture author never has to guess and never has to
re-derive them from the seed.

### 7.1 `trantype` - 7 rows of 60 bytes

`FILLER` is eight ASCII `'0'` at offset 52. Descriptions occupy `PIC X(50)` at
offset 2, right-padded with spaces.

| `TRAN-TYPE` | `TRAN-TYPE-DESC` |
|---|---|
| `01` | `Purchase` |
| `02` | `Payment` |
| `03` | `Credit` |
| `04` | `Authorization` |
| `05` | `Refund` |
| `06` | `Reversal` |
| `07` | `Adjustment` |

### 7.2 `trancatg` - 18 rows of 60 bytes

`FILLER` is four ASCII `'0'` at offset 56. Descriptions occupy `PIC X(50)` at
offset 6.

| Type | Category | `TRAN-CAT-TYPE-DESC` |
|---|---|---|
| `01` | `0001` | `Regular Sales Draft` |
| `01` | `0002` | `Regular Cash Advance` |
| `01` | `0003` | `Convenience Check Debit` |
| `01` | `0004` | `ATM Cash Advance` |
| `01` | `0005` | `Interest Amount` |
| `02` | `0001` | `Cash payment` |
| `02` | `0002` | `Electronic payment` |
| `02` | `0003` | `Check payment` |
| `03` | `0001` | `Credit to Account` |
| `03` | `0002` | `Credit to Purchase balance` |
| `03` | `0003` | `Credit to Cash balance` |
| `04` | `0001` | `Zero dollar authorization` |
| `04` | `0002` | `Online purchase authorization` |
| `04` | `0003` | `Travel booking authorization` |
| `05` | `0001` | `Refund credit` |
| `06` | `0001` | `Fraud reversal` |
| `06` | `0002` | `Non-fraud reversal` |
| `07` | `0001` | `Sales draft credit adjustment` |

Per-type counts: 01 = 5, 02 = 3, 03 = 3, 04 = 3, 05 = 1, 06 = 2, 07 = 1, summing
to **18**. **Every type code above is one of the seven in `trantype`**, verified
by set comparison, so the restricting foreign key in `V1__reference.sql` is
satisfiable by the seed exactly as it stands.

**The capitalisation is internally inconsistent, and the inconsistency is data.**
This is the trap an author walks into unprompted, so it is spelled out:

- `Cash payment`, `Electronic payment` and `Check payment` lowercase the second
  word, while `Refund credit` and `Fraud reversal` do the same.
- `Credit to Account` capitalises "Account", but `Credit to Purchase balance`
  capitalises "Purchase" and not "balance", and `Credit to Cash balance`
  capitalises "Cash" and not "balance".
- `Regular Sales Draft` is title case, while `Zero dollar authorization` and
  `Sales draft credit adjustment` are not.
- `Non-fraud reversal` carries an ASCII hyphen-minus, not a dash of any other
  kind.

An author will instinctively tidy this. **Rule T8 forbids it**, and section 6.6
records that this is exactly why the uppercase control-card seeds were rejected
as a derivation source.

### 7.3 `discgrp` - 51 rows of 50 bytes

LF only. `FILLER` is twenty-eight ASCII `'0'` at offset 22. Group ids, pair
structure and the three rate values are given in full in section 6.3.

### 7.4 Facts about the seeded schema that a fixture must not contradict

From `V1__reference.sql` and `V2__seed_reference.sql`, both read directly:

| Table | Key and notable columns |
|---|---|
| `reference.transaction_types` | `PRIMARY KEY (type_cd CHAR(2))`; `description VARCHAR(50) NOT NULL` |
| `reference.transaction_categories` | `PRIMARY KEY (type_cd CHAR(2), cat_cd CHAR(4))`; `description VARCHAR(50) NOT NULL`; `FOREIGN KEY (type_cd) REFERENCES transaction_types (type_cd) ON DELETE RESTRICT` |
| `reference.disclosure_groups` | `PRIMARY KEY (acct_group_id CHAR(10), tran_type_cd CHAR(2), tran_cat_cd CHAR(4))`; `interest_rate NUMERIC(6,2) NOT NULL` |
| `reference.us_phone_area_codes` | `PRIMARY KEY (area_cd CHAR(3))`; `code_class CHAR(1)` with `CHECK (code_class IN ('G', 'E'))` |
| `reference.us_states` | `PRIMARY KEY (state_cd CHAR(2))` |
| `reference.us_state_zip_prefixes` | `PRIMARY KEY (state_zip_cd CHAR(4))` |

- `interest_rate NUMERIC(6,2)` is the exact image of `S9(04)V99` - four integer
  digits, two decimal - which is what satisfies Rule T3 across the boundary.
- `'DEFAULT   '` is space-padded to `CHAR(10)`, matching the measured seven
  characters plus three spaces. `CHAR(10)` reproduces that padding rather than
  storing an unpadded literal as a distinct value.
- **The restricting foreign key is preserved by the constraint, and the baseline
  index order is preserved by the primary key.** `V1__reference.sql` L176 to
  L185 records that the composite `PRIMARY KEY (type_cd, cat_cd)` already builds
  a unique B-tree on exactly those two columns in exactly that order, which is
  what the baseline's `XTRNTYCAT.ddl` unique index names, so **no separate
  `CREATE INDEX` is issued** - a second index on the same columns in the same
  order would enforce nothing and be written on every insert.
- **The refused-delete chain**, which the `reference_update` domain exercises: a
  delete blocked by that foreign key raises PostgreSQL SQLSTATE **23503**, which
  Spring surfaces as `DataIntegrityViolationException`, which
  `com.carddemo.common.error.GlobalExceptionHandler` maps to HTTP **409**.
- `V2__seed_reference.sql` seeds **862 rows** in total: 76 reference rows
  (7 + 18 + 51) plus 786 lookup rows (490 + 56 + 240). Measured directly from its
  value tuples. Every statement is idempotent - seven `ON CONFLICT` clauses, no
  `TRUNCATE` and no `DELETE FROM` anywhere.

**Row counts in a fixture must agree with those numbers only where a test asserts
against the seeded schema.** Where a test asserts against a fixture subset, a
subset is entirely legitimate: the house `default_fallback` and `happy_path`
fixtures are a 17-row and a 1-row subset of the same 51-row seed (section 5.7).

---

## 8. Tree layout, and what is delegated downward

### 8.1 Shape

```text
fixtures/
  <domain>/
    <scenario>/
      <record-file>.txt
      README.md          <- one per scenario directory, mandatory
  README.md              <- this file, tree scope
```

### 8.2 Four binding naming rules

1. **`<domain>` names the business flow the fixture exercises, never the
   program.** The house precedent is unambiguous on this: its domains are
   `interest`, not `cbact04c`, and `posting`, not `cbtrn02c`. Its six domains -
   `export`, `interest`, `posting`, `prepost`, `provisioning`, `statement` - are
   precedent for the *style* only, not a vocabulary this tree reuses, because
   `reference-service` exercises different flows.
2. **`<scenario>` is snake_case and describes the behaviour, not the data.**
   Established house names include `happy_path`, `zero_balance`,
   `default_fallback`, `empty_input`, `boundary_exact_limit`,
   `boundary_expiry_equal`, `reject_100_card_missing` and `unmatched_account`.
3. **`<record-file>` is named for the source dataset, not for the copybook** -
   `discgrp.txt`, `trantype.txt`, `trancatg.txt`, and **never** `CVTRA02Y.txt`.
   No file anywhere in the house tree is copybook-named.
4. **Every scenario directory carries its own `README.md`** - see section 8.4.
   Note that the house prose writes `README` without an extension while all
   twenty committed files are `README.md`; **follow the files, not the prose.**

### 8.3 The five domain directories

| Domain | Program or flow it exercises | Record files |
|---|---|---|
| `disclosure_group` | rate lookup and the `DEFAULT` fallback: `CVTRA02Y`, `app/jcl/DISCGRP.jcl`, and `CBACT04C`'s `1200-A-GET-DEFAULT-INT-RATE` | `discgrp.txt` |
| `reference_list` | the `COTRTLIC` transaction-type and category list | `trantype.txt`, `trancatg.txt` |
| `reference_update` | `COTRTUPC` add, edit and delete, including the foreign-key restrict path that surfaces as HTTP 409 | `trantype.txt`, `trancatg.txt` |
| `batch_reference_update` | `COBTUPDT` sequential input | a 53-byte input file |
| `date_conversion` | the `CODATE01` request envelope. **Not** `CSUTLDTC`'s date-edit rules: those take a date and a ten-byte mask as parameters, not a 1000-byte message, so no fixture here carries one | a 1000-byte request file |

`reference_update` and `batch_reference_update` are genuinely distinct flows
despite the shared prefix - an online screen against the reference tables versus
a sequential batch reader over a completely different 53-byte record - so neither
name is redundant and neither may be folded into the other.

### 8.4 Every scenario directory MUST carry its own README

Two independent authorities require this, and they converge:

- **User-specified Rule 1 L27** requires a comment to sit adjacent to what it
  explains. A scenario's byte-level justification is about that scenario's bytes,
  so it belongs in that scenario's directory rather than aggregated here.
- **The house mandate at `tests/fixtures/README.md` section 9.1 (L695 to L719)**,
  worded `MUST` rather than "should", with compliance measured at twenty of
  twenty scenario directories. That document records at L714 to L719 that the
  `MUST` wording replaced an earlier "should" precisely because the weaker
  wording let scenario directories ship with **no** Explainability carrier at
  all.

The required content is the house four-element scenario contract:

1. **Scenario intent** - what condition this scenario represents.
2. **The exact business rule it exercises** - the house document's own worked
   example is literally *"`CBACT04C` DEFAULT-group fallback on VSAM status 23"*.
3. **The expected outcome** - the specific asserted value or error, not a
   description of one.
4. **Fixture bytes and governance** - per-file record width, record count and
   line ending, plus the attestation of section 8.5 where it applies.

Those four map onto Rule 1's four docstring elements as follows, so a scenario
README discharges the same obligation this file discharges at tree scope:

| Rule 1 element | Scenario README content |
|---|---|
| Purpose (L18) | items 1 and 2 |
| Parameters (L19) | item 4, plus offset, `PICTURE`, fill character and copybook line per field |
| Return values (L20) | item 3, plus the consumer and what it asserts |
| Exceptions (L21) | the failure modes this scenario's bytes can produce |

### 8.5 Which of the house add-a-scenario steps transfer here

The house procedure at `tests/fixtures/README.md` section 9.2 (L721 to L733) has
five steps. **Exactly two transfer.** Each non-transfer is recorded with its
reason, because stating a non-transfer without one would itself be an
undocumented choice under Rule 1 L40.

| Step | Transfers? | Reason |
|---|---|---|
| 1. Author at the exact record length with the overpunch contract honoured | **Yes** | This is the core contract of sections 3 and 5 and applies unchanged |
| 2. Mirror byte-identically under `tests/golden/` | **No** | Conditioned on `golden_compare.py`, a consumer this tree does not have - section 6.7 |
| 3. Keep the processing timestamp blank and inject the run date as a parameter | **No** | `CVTRA03Y`, `CVTRA04Y` and `CVTRA02Y` contain **zero timestamp fields** between them, so the rule has no target here |
| 4. Add the scenario README | **Yes** | Independently required by Rule 1 L27 - section 8.4 |
| 5. Pre-sort indexed inputs for the indexed loader | **No** | No COBOL loader consumes these fixtures, and `COBTUPDT` L101 is a *sequential* read, so even the 53-byte input has no key-ordering requirement |

### 8.6 Attestation scoping

The house attestation obligation at `tests/fixtures/README.md` section 10.3 (L818
to L831) is scoped to scenario READMEs whose folder holds PAN or identity data.
It requires three statements: that the bytes are synthetic and seed-derived,
citing the seed file and where useful the row key; that they represent no real
person or account; and that any business-rule field reshaped away from its seed
value is named. That document records that this colocated attestation is what
makes a fixture tree audit-defensible for financial-enterprise review.

Applying that scoping to this tree, **it triggers in exactly one of the five
domains.** This is stated positively rather than by silence, because silence is
indistinguishable from oversight:

| Domain | Attestation | Why |
|---|---|---|
| `reference_list` | not required | `TRAN-TYPE-RECORD` is a 2-byte code plus a description |
| `reference_update` | not required | `TRAN-CAT-RECORD` is a type plus a category plus a description |
| `batch_reference_update` | not required | `WS-INPUT-REC` is a type byte plus a 2-byte number plus a description |
| `disclosure_group` | not required | `DIS-ACCT-GROUP-ID` is a **disclosure-group classifier** - `A000000000`, `DEFAULT`, `ZEROAPR` - and **not an account number** |
| `date_conversion` | **required** | `WS-KEY PIC 9(11)` is an 11-digit ACCTDAT key, and therefore identity-shaped (section 3.7) |

All four `date_conversion` scenario READMEs carry the attestation obligation
explicitly, and they discharge it in the three places it attaches: the `happy_path`,
`request_payload_ignored` and `invalid_date_rejected` READMEs each carry the full
three-part statement in their own section 4.2, and the `empty_input` README records
positively that a zero-byte file holds no identity data and so has nothing to attest
to. The other four domains hold no PAN and no identity data at all, so the obligation
does not attach to them and its absence there is deliberate.

---

## 9. The consumer contract

This section is the Return-values element of Rule 1 L20 at tree scope: who reads
these bytes, and what they assert with them.

### 9.1 Consumers

- **`services/reference-service/src/test/java/**`** - the `*RepositoryIT`
  integration tests, which Failsafe runs, and the `*ServiceTest` and
  `*ControllerTest` unit tests, which Surefire runs. `services/pom.xml` records
  `*RepositoryIT` as this project's naming convention for the container-backed
  integration tests. **See section 10: the directory now exists, but none of
  those three test kinds is present in it yet.**
- **`com.carddemo.reference.fixtures`** - the executable consumers of this tree, both
  run by Surefire. `ReferenceFixtureContractTest` resolves all eighteen record files
  from the test classpath and asserts the geometry section 10 measures;
  `ReferenceFixtureTest` asserts the field-level claims this document makes about
  them - that an empty file is zero bytes and raises `RecordLengthException`, that
  the seeded types re-encode byte for byte with their zero filler intact, that the
  two disclosure groups differ at exactly pair `07|0001`, that the delete fixture
  pairs a referenced type with an unreferenced one, that every category names a
  declared type, that no file repeats a key, and that the two
  program-working-storage records carry their documented fields.
- **`com.carddemo.common.codec.FixedWidthCodec`** - decodes a record into a field
  map and encodes one back, raising `RecordLengthException` when a record is not
  exactly its declared length and `FieldCodecException` when a field will not
  decode. Round-trip assertions read these files as vectors.
- **`com.carddemo.common.codec.CopybookLayout`** - supplies the geometry the
  codec decodes against, through `layout("TRANTYPE")`, `layout("TRANCAT")` and
  `layout("DISGROUP")`. A test asks this registry for offsets; it never
  hard-codes one copied out of section 3.
- **`com.carddemo.common.codec.ZonedDecimalCodec`** - decodes `DIS-INT-RATE` to a
  `BigDecimal` and encodes it back, with `decodePreservingSign` and
  `encodePreservingSign` available where the exact sign byte of a zero value must
  survive a round trip.

Fixtures reach those consumers from the **test classpath**, not by filesystem
path: Maven copies `src/test/resources/` into `target/test-classes/`, so a record
file resolves as `fixtures/<domain>/<scenario>/<file>.txt`. This is verified twice
over: every one of the eighteen record files appears under
`services/reference-service/target/test-classes/fixtures/` after a build -- for
example the zero-byte
`services/reference-service/target/test-classes/fixtures/date_conversion/empty_input/date-request.txt`,
which is the case worth naming because a zero-byte resource is the one a copy step
is most likely to drop -- and both `ReferenceFixtureContractTest` and
`ReferenceFixtureTest` resolve them through that same classpath contract with
`getResourceAsStream`, raising rather than skipping if any one of them is absent, so
a file that failed to be packaged fails a test instead of being read from source.

Assumptions: a stale `target/test-classes/` can hold a file that no longer exists in
the source tree, because Maven's resource copy does not delete what has disappeared
unless the module is cleaned. That is why the consumer asserts the presence of names
that must exist rather than the absence of names that must not, and why a rename is
verified by loading the new name rather than by failing to load the old one.

### 9.2 The direction of the contract

**The bytes are the contract.** A consumer's expected value is whatever these
files encode, so a fixture that is wrong does not fail - it produces a **green
test that proves nothing**. That asymmetry is the whole reason this document
exists, and it is why section 13 states the audits as obligations on the tree
rather than as suggestions.

---

## 10. Availability status, verified against the branch

This section follows the house precedent at `tests/fixtures/README.md` L24 to
L45, which carries an availability section headed *"verified against the branch -
not aspirational"*. Everything below was checked directly rather than assumed.

**Present in this tree now** -- enumerated and measured from the directory, not from a
plan:

- this file, at tree scope;
- **18 record files** across **14 scenario directories** in **5 domains**, being
  `batch_reference_update` (3 scenarios, 3 files), `date_conversion` (4, 4),
  `disclosure_group` (3, 3), `reference_list` (2, 4 -- **both** scenarios carry both
  reference files) and `reference_update` (2, 4 -- both carry both);
- **14 scenario READMEs**, one per scenario directory, each carrying the four-element
  contract of section 8.4. The obligation that section records is discharged, not
  outstanding;
- **5 of the 18 record files are genuinely zero-byte**, which is the correct encoding
  of an empty fixed-width input (section 5.2): the two in
  `reference_list/empty_input`, which is the one `empty_input` scenario carrying two
  files, and one each in `batch_reference_update/empty_input`,
  `date_conversion/empty_input` and `disclosure_group/empty_input`. Of these,
  `date_conversion/empty_input/date-request.txt` is the one file in the tree that
  pre-dates the others and it is left byte-for-byte as it was.

| Domain | Scenario | Record files | Bytes | Rows x width |
|---|---|---|---:|---|
| `reference_list` | `happy_path` | `trantype.txt`, `trancatg.txt` | 427, 1098 | 7 x 60, 18 x 60 |
| `reference_list` | `empty_input` | `trantype.txt`, `trancatg.txt` | 0, 0 | zero-byte |
| `reference_update` | `happy_path` | `trantype.txt`, `trancatg.txt` | 488, 1098 | 8 x 60, 18 x 60 |
| `reference_update` | `delete_restricted_by_category` | `trantype.txt`, `trancatg.txt` | 122, 122 | 2 x 60, 2 x 60 |
| `disclosure_group` | `happy_path` | `discgrp.txt` | 51 | 1 x 50 |
| `disclosure_group` | `default_fallback` | `discgrp.txt` | 1734 | 34 x 50 |
| `disclosure_group` | `empty_input` | `discgrp.txt` | 0 | zero-byte |
| `batch_reference_update` | `add_record` | `trtype-update.txt` | 108 | 2 x 53 |
| `batch_reference_update` | `invalid_type_soft_reject` | `trtype-update.txt` | 108 | 2 x 53 |
| `batch_reference_update` | `empty_input` | `trtype-update.txt` | 0 | zero-byte |
| `date_conversion` | `happy_path` | `date-request.txt` | 1001 | 1 x 1000 |
| `date_conversion` | `request_payload_ignored` | `date-request.txt` | 1001 | 1 x 1000 |
| `date_conversion` | `invalid_date_rejected` | `date-request.txt` | 1001 | 1 x 1000 |
| `date_conversion` | `empty_input` | `date-request.txt` | 0 | zero-byte |

Refactoring Rationale: two rows of this table gained a second record file. An earlier
revision listed `reference_list/happy_path` as carrying `trantype.txt` alone and
`reference_update/delete_restricted_by_category` likewise, while both directories held a
`trancatg.txt` that this table did not name -- and neither file was enrolled in any
consumer inventory, so nothing read it and nothing noticed. Both are now tabled with the
byte counts they actually have, both are enrolled in the geometry, keyed-fixture and
populated-fixture inventories of the two consumers, and both are asserted semantically:
the list scenario's eighteen rows for ordering, uniqueness, referential closure over its
seven types and byte-identity with the update scenario's copy, and the restrict
scenario's two rows for referring to exactly the one type whose delete must be refused.

Assumptions: `reference_list/happy_path/trancatg.txt` and
`reference_update/happy_path/trancatg.txt` are the SAME 1098 bytes, which is why the two
rows above report one figure twice rather than two derivations of one seed. Section 6.4
of this charter derives that figure as `18 x 60 + 18`, and a consumer asserts the two
files equal so the shared extract cannot be edited apart.

Every non-empty file is LF only with exactly one trailing newline and zero CR bytes,
and every byte count above is `rows x (width + 1)`, which is the section 5.7
acceptance arithmetic.

Refactoring Rationale: this list previously named exactly two present artifacts --
this file and `date_conversion/empty_input/date-request.txt` -- and recorded that that
one scenario directory carried no `README.md`, so the section 8.4 obligation was
outstanding. Both halves of that statement are now false: seventeen more record files
landed, and all fourteen scenario READMEs were authored. A status section that
under-reports what exists is worse than one that is merely incomplete, because a
reader trusts it and stops looking -- and in this tree that reader is the one deciding
whether a fixture already covers the case they were about to add.

Assumptions: the counts above are **measured from the directory on every revision**,
and three files this list previously omitted are the reason they moved from fifteen to
eighteen. One is `date_conversion/invalid_date_rejected/date-request.txt`, described in
the next paragraph. The other two are `reference_list/happy_path/trancatg.txt` and
`reference_update/delete_restricted_by_category/trancatg.txt`, each an 18-row and a
2-row category file respectively, which landed in their scenario directories without
being entered here. **Both are still undescribed by their own scenario README**, and
that gap is named rather than closed by a count: `reference_list/happy_path/README.md`
does not mention its category file at all, and
`reference_update/delete_restricted_by_category/README.md` carries a `Trade-offs:` at
its own item arguing that the directory deliberately holds no category rows -- which
the present file contradicts. Their geometry is asserted by both test classes so the
bytes cannot drift unnoticed, but a reader deciding whether either file is intended
must resolve that with the author of the scenario, not from this list. Trade-offs:
recording the discrepancy is preferred over silently deleting either file or inventing
a derivation rationale for it; what is given up is a tidy status section, and what is
kept is that no number in it is wrong and no gap in it is invisible.

Assumptions: `date_conversion` carries **four** scenarios rather than the three the
counts above previously gave, and the fourth is the one whose subject is not
`CODATE01` at all. `invalid_date_rejected` holds a carrier envelope for the date-edit
rule, whose refusal happens to a ten-character date passed as a parameter beside these
bytes; the naming discussion at section 3.7 governs the envelope scenario and is not
contradicted by it, and that scenario's own README section 4.3 records that no date
lives in the bytes. Its envelope is **byte-identical** to `request_payload_ignored`'s,
because both are derived from the same `REQUEST-MSG-COPY` declaration and reach the
same three field values; the fixture contract test pins the identity so that
"differentiating" one of them cannot silently invalidate the other's derivation table.
The count is stated here because a reader comparing this list against the directory
would otherwise find one more scenario than the list admits and have no way to know
which is right.

Refactoring Rationale: the `batch_reference_update` scenario in the table above was
named `invalid_type_abend` and is now `invalid_type_soft_reject`. The rename is not
cosmetic: `9999-ABEND` in
[`COBTUPDT.cbl`](../../../../../../app/app-transaction-type-db2/cbl/COBTUPDT.cbl)
displays a message, moves 4 to `RETURN-CODE` and EXITs -- it does not `STOP RUN` --
so control returns to the read loop and the record after the invalid one IS
processed. The old name described the paragraph's label rather than its body, and a
fixture whose second row exists precisely to prove the loop advanced read as though
it proved the run halted.

Assumptions: the file count, the scenario count and the byte counts are measurements
taken from the directory, so they will drift as scenarios are added. Section 9's
classpath contract is the mechanism that makes a drift visible -- a consumer resolves
`fixtures/<domain>/<scenario>/<file>.txt` and fails on a name that is not there,
rather than silently testing nothing.

**Present and depended upon elsewhere:**

- `services/reference-service/src/main/resources/db/migration/V1__reference.sql`
  and `V2__seed_reference.sql` - the six tables and the 862 seeded rows that
  section 7.4 tabulates.
- `com.carddemo.common.codec.CopybookLayout`, `FixedWidthCodec` and
  `ZonedDecimalCodec` in `common-lib`, which `reference-service` declares as its
  only permitted reactor sibling.
- `data-migration/sql/V0__schemas_and_roles.sql`, which creates the schemas and
  roles these tables live in. It is owned entirely by the `data-migration`
  package: **nothing here copies it, stubs it, or extracts from it.**

**Present, and one of them IS a consumer of these payloads:**

- `services/reference-service/src/test/java/` holds three test packages.
  `com.carddemo.reference.config` holds `ReferenceApiContractTest`, which holds
  the published OpenAPI document, `V1__reference.sql` and `SecurityConfig` to
  each other; `com.carddemo.reference.dto` holds `ReferenceWireContractTest`,
  which holds that same document to the Java records that realise it. Neither
  reads a record file described here: their inputs are the packaged contract, the
  migration and the DTOs, not these fixtures.
- `com.carddemo.reference.fixtures` holds the two classes that **do** read them.
  `ReferenceFixtureContractTest` resolves each one as
  `fixtures/<domain>/<scenario>/<file>.txt` from the test classpath and asserts
  the geometry section 10 measures, raising rather than skipping on an absent
  name; `ReferenceFixtureTest` asserts the field-level and cross-file claims this
  document makes, and contributes thirty-nine executed assertions over the eighteen
  files. **The consumers named in section 9.1 remain a contract for when they
  land** -- no `*RepositoryIT`, `*ServiceTest` or `*ControllerTest` exists yet --
  but the classpath contract itself is no longer unexercised.

Refactoring Rationale: this block used to say the test tree held ONE package,
that it was named `com.carddemo.reference.dto`, and that nothing read these
payloads. All three statements have been overtaken: there are three packages, the
contract test that the sentence described actually lives in `config`, and the two
classes in `fixtures` read all eighteen files between them. A "not yet a consumer" note that
outlives its own subject is the kind a reader believes, and believing it here
means authoring a second fixture reader beside one that already exists.

> Trade-offs: writing the byte-level contract before its consumers exist. The
> rejected alternative is waiting for those consumers to land and documenting
> the fixtures afterwards. Its concrete consequence is that whoever authors the
> record files in the meantime has no stated derivation rule to author against,
> so the bytes settle first and the contract is then written to match whatever
> they happen to be - which makes the document a description of a possibly-wrong
> tree instead of the standard that tree is measured by. The compromise accepted
> is that this section must name artifacts that do not exist yet, which is why it
> separates them explicitly rather than listing them alongside the present ones.

**The live check that closes the loop:** once a test under
`services/reference-service/src/test/java/` reads these payloads,

```bash
# WHAT: build reference-service and the common-lib it depends on, and run both
#       test phases with these fixtures resolved from the test classpath.
# WHY : Assumptions: -am is required because the codecs that decode these
#       fixtures live in common-lib, and a reactor build that excludes it would
#       resolve a stale installed artifact instead of the sources under review.
mvn -f services/pom.xml -pl reference-service -am clean verify
```

must pass. The `-am` in that command is not optional and the reason is worth
stating twice: a reactor build that excluded `common-lib` resolves the last
INSTALLED copy of the codecs from the local repository instead of the sources
under review, so a fixture can be validated against a stale decoder and report
green. That failure mode has been observed on this branch, which is why the flag
is written into the command rather than left to habit.

---

## 11. Two comparisons a reader will otherwise have to make themselves

### 11.1 Why this tree is fixed-width when the auth-service tree is SQL

`services/auth-service/src/test/resources/fixtures/` is **flat, holds two `.sql`
files, and carries no tree-scope README** - because a `.sql` file *can* carry a
`--` comment, so that peer discharges Rule 1 in-file and needs no separate
carrier. The apparent inconsistency between the two folders is not a
disagreement: **both apply the same rule to opposite facts.**

Each reason that peer has for departing from the fixed-width convention fails
here:

| That peer's reason for SQL | Does it hold for `reference-service`? |
|---|---|
| that service performs no fixed-width decoding at all | **No** - the consumers here include `FixedWidthCodec` and `CopybookLayout` round-trips, which need bytes |
| there is no golden master for that service | Holds here too, but on its own this selects no file format |
| no money and no zoned decimal in that context | **No** - `DIS-INT-RATE PIC S9(04)V99` is zoned decimal with a sign overpunch, six bytes, mapping to `NUMERIC(6,2)` |
| single-sourcing honoured by citing rather than re-implementing layouts | Holds here too - section 5.8 |

Two of the four fail outright, and they are the two that determine the format.
**The five domain directories, with per-scenario subdirectories and per-scenario
READMEs, are the correct house-consistent shape for fixed-width COBOL fixtures.**

### 11.2 The Python oracle does not cover two of these three layouts

This is why the tables in sections 3.2 and 3.3 cannot be replaced by a citation.
Three independent observations:

1. **The Python oracle.** `tests/helpers/record_codec.py` builds a `LAYOUTS`
   registry with exactly eleven keys - `ACCOUNT`, `DALYTRAN`, `DISGROUP`,
   `XREF`, `TCATBAL`, `CARD`, `CUSTOMER`, `TRAN`, `TRNX`, `REJECT`, `INTTRAN`.
   **There is no `TRANTYPE` key and no `TRANCAT` key.** The three base masters it
   does not register are `CVTRA03Y`, `CVTRA04Y` and `CSUSR01Y` - **two of the
   three are this service's seed layouts.**
2. **The house layout tables.** Its nine section 5.x layout tables cover
   `CVTRA06Y`, `CVACT01Y`, `CVACT03Y`, `CVACT02Y`, `CVTRA05Y`, `CVTRA01Y`,
   `CVTRA02Y`, `CVCUS01Y` and `CSUSR01Y`. **Neither `CVTRA03Y` nor `CVTRA04Y` is
   among them.**
3. **The house derivation table** at L670 to L680 names a copybook **and** a
   record length for every seed row *except* `trancatg.txt` and `trantype.txt`,
   which read only *"transaction category reference"* and *"transaction type
   reference"* with no copybook and no length.

**So this document is the first place in the repository to table the byte layouts
of `CVTRA03Y` and `CVTRA04Y` for fixture authoring.**

The claim must be scoped precisely, because the two oracles differ:

- **The Python oracle genuinely lacks them.** A `trantype` or `trancatg` fixture
  **cannot** be cross-checked with `record_codec.py`.
- **The Java oracle has them.** `CopybookLayout` registers `TRANTYPE` at 60 bytes
  with a 2-byte key at offset 0 and `TRANCAT` at 60 bytes with a 6-byte key at
  offset 0, and its own commentary notes that these are two of the three base
  masters the parity oracle's codec does not register. Its per-field offsets
  match section 3.2 and section 3.3 exactly.
- **`DISGROUP` is covered by both.** It is `record_codec.py`'s `DISGROUP` layout
  and `CopybookLayout`'s `DISGROUP` at 50 bytes with a 16-byte key, so `discgrp`
  fixtures can be cross-checked either way.

The practical instruction: derive `trantype` and `trancatg` byte for byte from
`app/data/ASCII/` plus the copybook widths in sections 3.2 and 3.3, and confirm
the result against the three-source table in section 3.5 - the copybook banner,
the IDCAMS cluster definition, and `CopybookLayout`. The absence of Python
support removes one cross-check; it does not leave the geometry unverifiable.

---

## 12. Failure modes

The Exceptions-and-errors element of Rule 1 L21. Each row pairs a mistake with
the symptom it actually produces, because the dangerous ones are silent.

| Failure | Symptom |
|---|---|
| A record is one byte short or long | Rejected at load, and `FixedWidthCodec` raises `RecordLengthException`. **Loud** - this is the good case |
| `FILLER` synthesised as spaces instead of copied `'0'` | Correct length, correct line endings, wrong value. **Silent** until an assertion on a later field or a schema comparison fails for an unrelated-looking reason |
| A stray carriage return survives derivation | The record is one byte over its length, so the row is rejected at load. **Loud**, and section 5.5 is what prevents it |
| A blank line used to represent "empty" | A one-byte file holding one zero-length record; fails fixed-width parsing instead of exercising the empty path. Only a zero-byte file is empty |
| Overpunch decoding applied to an unsigned `9(n)` field | A digit is read as a sign and the value is wrong by orders of magnitude. **Silent** - section 5.4 lists the three unsigned fields in this tree |
| The overpunch read as the high-order rather than the low-order digit | `0000005047G` decodes to 50.47 instead of 504.77. **Silent** |
| A rate written as a float-looking literal such as `15.00` in the field | Six bytes are expected and five characters plus a `.` are supplied, so the field is the wrong width and every later field shifts. Violates Rule T3 |
| A description tidied for consistent capitalisation | No length change at all, so nothing detects it until a comparison against the seeded description fails. **Silent**, and forbidden by Rule T8 |
| A DEFAULT-fallback scenario built on any pair other than type `07` category `0001` while both groups are present | The assertion passes whether or not the fallback fired. **Vacuous** - the worst case, because it looks like coverage |
| A `discgrp` fixture that omits the `DEFAULT` group | The consuming batch flow abends rather than returning a value; the scenario is asserting a crash and its README must say so |
| An invalid byte in position 0 of a 53-byte record | Reading `9999-ABEND` as a termination. It displays the message, moves `RETURN-CODE` to 4 and **continues with the next record**, so the scenario asserts a soft reject, not a crash |
| Offsets read with the wrong base | Every field is off by one. Section 3.1 declares the base for exactly this reason |
| A layout restated in test code instead of obtained from `CopybookLayout` | Two declarations of one geometry, which drift independently. Forbidden by section 5.8 |

---

## 13. The validation gates

Ten audits. The first six and the ninth apply to record files; the seventh and
eighth apply to documents including this one; the tenth applies to the module.

1. **Length audit.** Every physical line in every record file is exactly its
   declared record length - 60, 60, 50, 53 or 1000 - and the file's byte count
   equals `rows x RECLN + rows`. Use the vectors in section 5.7.
2. **Line-ending audit.** Zero carriage-return bytes in every file, exactly one
   trailing newline, and no blank lines. A zero-byte file is the sole exception
   and has no trailing newline.
3. **Fill audit.** `FILLER` bytes match the source regime byte for byte - ASCII
   `'0'` for the three seed-derived datasets, spaces for a `CODATE01`-derived
   request, and none at all for the 53-byte record (section 5.3).
4. **Overpunch audit.** Every `DIS-INT-RATE` value is one of `00000{`, `00150{`
   or `00250{`, and decodes through the section 5.4 tables to 0.00, 15.00 or
   25.00. Any other value needs a stated reason in its scenario README.
5. **Verbatim audit.** Every description matches section 7 character for
   character, including capitalisation and hyphenation.
6. **Discriminator audit.** Any scenario claiming to prove the `DEFAULT` fallback
   with both groups present exercises type `07` category `0001`; any scenario
   claiming to prove the no-rate path exercises type `01` category `0005`.
7. **README completeness audit.** A `README.md` exists at tree scope and in every
   scenario directory - measured at fourteen of fourteen. Each states its purpose, the byte-level contract with
   copybook line citations, the consumer and what it asserts, and the failure
   modes. Each non-obvious derivation carries one of the three available
   canonical labels, spelled plural, in ASCII, unbolded and unparenthesised.
8. **Prose audit.** Zero non-ASCII bytes; no vague rationale without a stated
   consequence; no framing of a baseline property as a defect; the
   `Refactoring Rationale` label absent for the reason in section 2.2; no
   secret-shaped string; no schedule language.
9. **Reference-integrity audit.** `git status` shows no modification to any path
   under `app/**`, `tests/**`, `scripts/**` or `samples/**`.
10. **Live check.** The reactor command given in section 10 passes with these
    fixtures resolved from the test classpath. `ReferenceFixtureTest` is what makes
    this gate assert something: gates 1, 2, 4 and 6 are mechanical facts about the
    bytes, and it checks all four of them on every build rather than only when
    someone remembers to run the one-liner below.

Gates 1 to 4 are mechanical and worth running as a one-liner over the tree:

```bash
# WHAT: assert the length, line-ending and trailing-newline contract for one
#       record file, given its expected record length.
# WHY : Assumptions: LC_ALL=C keeps wc and grep byte-oriented rather than
#       locale-oriented, so a multi-byte interpretation can never make a
#       wrong-length record look correct. Trade-offs: this checks geometry only.
#       The fill, overpunch and verbatim audits compare against the seed and
#       cannot be reduced to a size arithmetic check, so gates 3 to 6 stay
#       human or test-driven rather than being approximated here.
f=<record-file>; reclen=<60|50|53|1000>
rows=$(LC_ALL=C grep -c '' "$f")
bytes=$(LC_ALL=C wc -c < "$f")
cr=$(LC_ALL=C tr -cd '\r' < "$f" | wc -c)
test "$cr" -eq 0 && test "$bytes" -eq $(( rows * reclen + rows )) \
  && echo "OK $f rows=$rows bytes=$bytes" \
  || echo "FAIL $f rows=$rows bytes=$bytes cr=$cr"
```

---

## 14. Not to be created in this tree, and not to be touched

- **No `.java`.** Test classes live in `services/reference-service/src/test/java`,
  a sibling directory this tree does not own.
- **No `.sql`.** The schema and seed belong to `V1__reference.sql` and
  `V2__seed_reference.sql`. A duplicate definition here would contradict the
  module's `validate` schema check rather than support it.
- **No copy, stub or extract of `data-migration/sql/V0__schemas_and_roles.sql`.**
  It is owned by the `data-migration` package (section 10).
- **No `.env`, credential, key or secret file** of any kind, and no endpoint,
  account identifier or resource name anywhere in this tree.
- **No `.gitignore` in this module.** The migration modifies exactly three
  pre-existing files repository-wide, all at the repository root.
- **No EBCDIC `.PS` fixture.** The three reference datasets this service owns
  exist in ASCII form, and `app/data/EBCDIC/**` is decoded only by the Python
  ETL, never by this service's tests.
- **No golden-master or `.expected` file, and no `tests/golden` mirror** -
  section 6.7.
- **No modification of any file under `app/**`, `tests/**`, `scripts/**` or
  `samples/**`.** These are REFERENCE-ONLY. The seeds are inputs to derivation;
  a fixture is created by copying rows out of them, never by editing them.

---

## 15. The six things a fresh reader cannot re-derive

Each of these was expensive to establish and each is silent when got wrong.

1. **`A000000000` and `DEFAULT` differ in exactly one of their seventeen rows,
   and it is type `07` category `0001`.** Any other pair makes a co-present
   DEFAULT-fallback assertion vacuous. Section 6.3.
2. **Type `01` category `0005` exists in `trancatg` but has zero rate rows in any
   group.** An observed property of the baseline data, and the tree's only
   no-rate negative path. Section 6.4.
3. **Two opposite `FILLER` fill regimes exist, and they coexist one byte apart
   inside a single 1000-byte record** - ASCII `'0'` in the seed datasets, spaces
   in `CODATE01`. Section 5.3.
4. **The 60-, 53- and 1000-byte record-length regimes are new to this
   repository.** The house enumeration of record lengths does not include 60.
   Section 5.1.
5. **The Python oracle covers eight of eleven base-master layouts, and two of the
   three gaps are this service's.** `discgrp` has support there; `trantype` and
   `trancatg` do not, though the Java `CopybookLayout` and the IDCAMS cluster
   definitions both cover all three. Sections 3.5 and 11.2.
6. **There is no `tests/golden` mirror for this tree and none is to be created.**
   The mirroring rule is conditioned on a comparator this tree does not use, and
   `reference-service` has no golden-master oracle at all. Section 6.7.
