# `batch_reference_update/commented_line` - the `'*'` dispatch branch, and the one row in this tree that is comment-like from inside a record

Two 53-byte maintenance records. The first carries action byte `'*'`, which the
`COBTUPDT` batch reference-update flow reads as a commentary marker and passes over
without touching a table; the second carries a valid `'U'` action that is applied, and
exists to prove the reader advanced past the first.

> **Why this README exists.** `trtype-update.txt` is a positional record file: every
> byte offset carries meaning, so a `#` or `//` annotation would be *data* rather than
> an annotation, and the physical row carrying it would be the wrong length and would be
> refused at load rather than skipped. None of the documentation formats Rule 1 L22
> names - JSDoc, Javadoc, Python docstrings, XML comments - can be carried by such a
> file, so this document is the only conforming place the justification for these bytes
> can live. Rule 1 L27 requires that justification to sit adjacent to what it explains,
> which is why it is in this scenario's own directory rather than aggregated upward.
>
> One irony is worth stating once, because this is the scenario where a reader is most
> likely to expect otherwise: even here, where the *subject* is a comment, the
> Explainability carrier is still this README. The `'*'` in byte 0 is a typed data value
> the program interprets, not a place to put prose about the fixture.
>
> **The tree-wide byte contract is deliberately not reproduced here.** The offset base,
> the complete layout catalogue, the two opposing `FILLER` fill regimes, the
> zoned-decimal sign-overpunch tables, the line-ending and trailing-newline rulings, the
> canon of rationale labels and the validation gates are all single-sourced in
> [`../../README.md`](../../README.md), whose section 3.6 tables this record and whose
> section 8.4 sets the shape of this document. What follows records only what is
> specific to this scenario. Section 4.1 restates this record's three fields at scenario
> scope because section 8.4 requires a per-field offset, `PICTURE`, fill character and
> declaring line in a scenario README; section 3.6 remains normative, and where that
> table and this one ever disagree, that one governs and this one is wrong.

The numbered sections below are the house four-element scenario contract, and each
discharges one element of the docstring requirement Rule 1 L15 imposes: intent and rule
are its **Purpose** (L18), the byte tables are its **Parameters** (L19), the outcome
together with its consumer is its **Return values** (L20), and the byte-level failure
modes are its **Exceptions** (L21).

---

## 1. Scenario intent - Purpose

The commentary case, in the shape an operator actually produces it: one maintenance
line is disabled by writing `'*'` in its first column while the rest of the line is left
standing, so the line is read, reported as ignored, and passed over with no database
action of any kind. A second and entirely valid record follows it and **is** applied,
which is what makes the first row's non-effect observable rather than merely asserted.

This is also the one place anywhere in this tree where something comment-like sits
*inside* a record rather than beside it. That fact is the most misread thing in this
directory, so section 5 states what it does and does not license in **both** directions:
the row is not a free-form comment, and it is also not an accident of a catch-all arm.

---

## 2. The exact business rule it exercises - Purpose

### 2.1 The dispatch, and an arm that is one statement long

`1003-TREAT-RECORD` in
[`COBTUPDT.cbl`](../../../../../../../../app/app-transaction-type-db2/cbl/COBTUPDT.cbl)
opens at L109. Its `EVALUATE INPUT-REC-TYPE` opens at L110 and closes at L129, and the
paragraph's own `EXIT` is at L130. Those boundaries are cited individually rather than as
one span because the arm this scenario exercises is a single line inside them, and a span
invites a reader to go looking for a body that does not exist.

This scenario is the `WHEN '*'` arm at **L120**, and its entire body is the one statement
at **L121**, a `DISPLAY`. Nothing is performed after it. Four consequences follow, and
they are the whole rule:

- no embedded database statement is reached;
- nothing is built into `WS-RETURN-MSG`, declared `PIC X(80)` at L61;
- `9999-ABEND` is not performed; and therefore
- `RETURN-CODE` is not moved.

A commented row is nevertheless **not** invisible, and the distinction matters to anyone
reading a run log. `1002-READ-RECORDS` echoes every record the read returns, through the
`DISPLAY 'PROCESSING   ' WS-INPUT-REC` at **L105** under the end-of-data guard at L104.
A commented row is returned by the read at L101 like any other row, so it is echoed like
any other row and only then announced as ignored at L121. Read, reported and passed over
is a different thing from unread.

The dispatch order the program declares is `'A'` at L111, `'U'` at L114, `'D'` at L117,
`'*'` at L120, and `WHEN OTHER` at L122. The JCL driver documents the same four action
values in the order `A`, `D`, `U`, `*` at L11 to L14 of
[`MNTTRDB2.jcl`](../../../../../../../../app/app-transaction-type-db2/jcl/MNTTRDB2.jcl);
that ordering is presentational, and the program's order is the authoritative one.

### 2.2 What the two nearest dispatch neighbours do instead

Both are named because this scenario's non-effect is only meaningful against them:

- **`WHEN 'U'` at L114** is the arm the *second* row of this fixture selects. It reports
  a progress line at L115 and performs `10032-UPDATE-DB` at L116, whose statement at
  L171 to L175 is keyed on `INPUT-REC-NUMBER` and writes `INPUT-REC-DESC`. That arm is
  exercised here only as evidence of continuation, not as this scenario's subject.
- **`WHEN OTHER` at L122** builds `'ERROR: TYPE NOT VALID'` at L124 into
  `WS-RETURN-MSG` through the `STRING` at L123 to L127 and performs `9999-ABEND` at
  L128. The paragraph name is misleading and is worth reading before relying on it:
  `9999-ABEND` at L230 to L233 displays the message, moves 4 to `RETURN-CODE` and exits,
  with no `STOP RUN` and no language-environment abend call on that path. It is a
  warn-tier soft reject that returns through `1003-TREAT-RECORD` to the read loop, not a
  termination. That arm belongs to the sibling
  [`../invalid_type_abend`](../invalid_type_abend/README.md).

The gap between L120 and L122 is one line of source and two opposite outcomes, which is
why section 3.2 draws the line against that sibling explicitly rather than trusting a
reader to infer it.

### 2.3 The loop is what makes the second row reachable

`1001-READ-NEXT-RECORDS` at L91 primes with a single `PERFORM 1002-READ-RECORDS` at L92
and then runs `PERFORM UNTIL LASTREC = 'Y'` at L93, performing `1003-TREAT-RECORD` at
L94 and reading again at L95 before the `END-PERFORM` at L96. The end-of-data flag is set
at L102, and the run closes through `2001-CLOSE-STOP` at L234.

So the second outcome in section 3.1 is not merely present, it is *produced by* the loop
having advanced past the commented row. That is the only reason a two-row file is used
here, and the reason is recorded under `Alternatives Considered:` in section 7.

---

## 3. Expected outcome - Return values

### 3.1 The result, record by record

| Row | Byte 0 | `RecordAction` | Outcome | Message the outcome carries | `WS-RETURN-MSG` | `RETURN-CODE` | Effect on `reference.transaction_types` |
|---:|:---:|---|---|---|---|---|---|
| 1 | `*` | `COMMENT` | applied | the L121 display text, `IGNORING COMMENTED LINE` | nothing is built into it | not moved | **none at all** |
| 2 | `U` | `UPDATE` | applied | the update-success text reported at L179, `RECORD UPDATED SUCCESSFULLY` | nothing is built into it | not moved | type `01` has its description set to the value it already holds |

At run level: two outcomes in stream order, the `COMMENT` one **followed by** the
`UPDATE` one; a processed count of 2; no refusal recorded; and a derived return code of
0, the clean value, because nothing on either row reaches the paragraph that moves 4.

Three points in that table are easy to state loosely, so they are stated exactly:

- **A commented row is a success, not a refusal.** It is reported and it is passed over,
  and those two facts are not in tension. Classifying it as a refusal would raise the
  run-level aggregate on an input the baseline reports as clean.
- **A message is carried, and `WS-RETURN-MSG` is still untouched.** These are two
  different things and conflating them is the likeliest misreading of this table. The
  L121 `DISPLAY` text is what the outcome reports; the register the `WHEN OTHER` arm
  composes into at L126 is never written on this path.
- **The second row is deliberately a no-change update.** Setting type `01` to the
  description it already carries is a real write on a real row, so the update arm is
  genuinely taken, while the seeded table is left in the state it started in. The reason
  that trade is worth making is recorded under `Trade-offs:` in section 7.

`reference.transaction_categories` is unaffected by either row, and that is a property of
the program rather than of the fixture: L54 of `COBTUPDT.cbl` includes the transaction
type table declaration and nothing else, so the program holds no category declaration to
write through. A consumer asserting any change to the category table would be asserting
something this input cannot cause. For row 1 the stronger statement holds - it touches
**no** table at all.

### 3.2 The contrast that gives this scenario its meaning

The sibling [`../invalid_type_abend`](../invalid_type_abend/README.md) is the
nearest neighbour in the whole dispatch and the single most confusable pair in this
directory. Set side by side, on a fixture whose byte 0 is the only difference that
matters:

| | this scenario, byte 0 = `*` | the sibling, byte 0 outside the declared set |
|---|---|---|
| Arm | `WHEN '*'`, L120 | `WHEN OTHER`, L122 |
| Message composed into `WS-RETURN-MSG` | none | `'ERROR: TYPE NOT VALID'`, L124 |
| `9999-ABEND` performed | no | yes, L128 |
| `RETURN-CODE` | not moved | moved to 4 at L232 |
| Run-level classification | applied, `RecordAction.COMMENT` | refused, `RecordAction.INVALID` |
| Run continues to the next record | yes | yes |

The last row is the one both scenarios agree on, and it is why the sibling's name is not
a claim that the run stops: `9999-ABEND` ends a paragraph, not a run. That sibling is
[`../invalid_type_abend`](../invalid_type_abend/README.md), named for the paragraph its
invalid byte performs; its own section 8 and section 10 of
[`../../README.md`](../../README.md) both record why the name is read that way. The
two scenarios are opposites, so a reader looking for the refused-type case wants that
directory and not this one.

### 3.3 Consumers, with availability stated rather than implied

- **The production consumer is present on this branch.**
  [`ReferenceBatchUpdateService`](../../../../../main/java/com/carddemo/reference/service/ReferenceBatchUpdateService.java)
  declares `RECORD_LENGTH` as 53; `BatchUpdateResult apply(java.io.InputStream)` reads
  contiguous 53-byte records, and `RecordOutcome applyRecord(byte[])` transcribes
  `1003-TREAT-RECORD` in the dispatch order `A` / `U` / `D` / `*` / OTHER. The member
  this scenario is aimed at is `RecordAction.COMMENT`, a first-class enum member sitting
  alongside `ADD`, `UPDATE`, `DELETE` and `INVALID` and carrying the L121 text as its
  display text. Its `'*'` arm yields an applied outcome, and the aggregate the run
  reports is the derived clean return code of section 3.1.
- **What that consumer is not.** It is a plain service method over a record stream.
  There is no batch job, no step, no job repository and no run ledger on this path, and
  none is to be described as though there were: the baseline behind it is a single
  sequential read loop at L93 to L96.
- **Three consumers now resolve this scenario's path.** `ReferenceFixtureContractTest`
  and `ReferenceFixtureTest` in `com.carddemo.reference.fixtures` resolve fixtures from
  the test classpath as `fixtures/<domain>/<scenario>/<file>.txt` and raise rather than
  skip on an absent name; both now enrol
  `fixtures/batch_reference_update/commented_line/trtype-update.txt`, so its 108 bytes,
  its two 53-byte records, its LF-only terminators and its single trailing newline are
  asserted rather than declared. `ReferenceBatchUpdateServiceTest`, in its
  `on the stored scenario fixtures` group, feeds these exact bytes through
  `BatchUpdateService.apply` and asserts the ordered pair of outcomes section 3.1
  tabulates: `COMMENT` on type `03` carrying `IGNORING COMMENTED LINE`, then `UPDATE` on
  type `01` carrying `RECORD UPDATED SUCCESSFULLY`, with nothing refused and the clean
  return code. It also asserts row 1's non-effect as an ABSENCE of any persistence
  interaction, which is the one form in which "touches no table at all" is checkable.
- **This section previously recorded the opposite, and the reason is worth keeping.** It
  read "no consumer resolves this scenario's path" and concluded that **this document, and
  not a test, is what establishes that these 108 bytes are right** - which was accurate
  when written and was the correct thing to state. A record file that nothing loads by its
  own path can be edited into something wrong with every test still passing, and that is
  exactly what a review found here. What changed is not the reasoning but the fact: the
  bytes are now load-bearing, and `ReferenceFixtureTest` additionally sweeps the packaged
  tree so no fixture can fall out of the enrolment again.
- **The `'*'` behaviour is also covered independently of these bytes.** The service's own
  unit test exercises the commentary branch through records it builds in-test and pins the
  display text against the baseline literal. So the branch is asserted twice over: once
  from composed records and once from this file.

**The bytes are the contract.** A consumer's expected value is whatever these 108 bytes
decode to, so a fixture that is wrong does not fail - it produces a passing assertion
that proves nothing. A disagreement between a consumer and this file is settled by
reading the bytes, not by editing them.

---

## 4. Fixture bytes and governance - Parameters

### 4.1 The record, field by field

Normative source: `01 WS-INPUT-REC.` at **L71** of `COBTUPDT.cbl`, normative because
**L101** reads `READ TR-RECORD NEXT RECORD INTO WS-INPUT-REC` and the `EVALUATE` at L110
inspects that copy. Offsets are **0-based**, matching
[`../../README.md`](../../README.md) section 3.1.

| Declared at | Field | `PICTURE` | Offset | Length | Fill |
|---|---|---|---:|---:|---|
| L72, with L73 `VALUE SPACES.` | `INPUT-REC-TYPE` | `X(1)` | 0 | 1 | single byte, nothing to pad |
| L74, with L75 `VALUE SPACES.` | `INPUT-REC-NUMBER` | `X(2)` | 1 | 2 | right-pad with spaces |
| L76, with L77 `VALUE SPACES.` | `INPUT-REC-DESC` | `X(50)` | 3 | 50 | right-pad with spaces |

`1 + 2 + 50 = 53`.

Two reading hazards in that declaration are worth naming, because a reader who misses
either derives a different record:

- **`PIC` and `VALUE` sit on separate physical lines for all three fields** - L72 with
  L73, L74 with L75, L76 with L77. A layout extractor that assumes one declaration per
  line mis-reads this record.
- **The record is bytes 0 through 52 and nothing else.** `COBTUPDT.cbl` carries legacy
  sequence numbers in source columns 73 to 80, for example `00592033` on L72.
  Identification-area content is an artifact of the source file's own format and must
  never leak into a derived record. That warning bites hardest in *this* directory,
  because a COBOL comment line and a `'*'` fixture row look superficially alike and are
  nothing like each other: a row in this file is 53 bytes of data, and it carries no
  sequence number, no `'*'` in column 7 and nothing at all from the source's
  identification area.

A byte-identical 53-byte group is also declared on the file side, as `01 WS-INPUT-VARS.`
at L40 to L46 under `FD TR-RECORD RECORDING MODE F.` at L39. It is mentioned only because
it independently confirms records of one declared length at 53 bytes, and it is not the
group the dispatch reads.

### 4.2 The file, and arithmetic that makes the check reproducible

| File | Record width | Records | Line ending |
|---|---|---:|---|
| `trtype-update.txt` | 53 bytes | 2 | LF, exactly one trailing newline, zero carriage returns |

```text
bytes = rows x 53 + rows  =  2 x 53 + 2  =  108
```

So `wc -c` reads **108** and `wc -l` reads **2**, and the two together are a complete
geometry check that needs no decoder. The commented row counts as a row exactly like any
other, in the record count and in this arithmetic both; that is the first half of section
5, expressed as measurement.

The rows, in file order, and the **expected byte-0 set is `{*, U}`** - stated explicitly
so a dispatch-coverage audit of this directory is unambiguous:

| Row | Offset 0 | Offsets 1 to 2 | Offsets 3 to 52 |
|---:|:---:|:---:|---|
| 1 | `*` | `03` | `Credit` then 44 spaces |
| 2 | `U` | `01` | `Purchase` then 42 spaces |

### 4.3 The width is corroborated twice, from mutually independent artifacts

The 53 above is not asserted from a single source:

1. **The `PICTURE` arithmetic** at `COBTUPDT.cbl` L71 to L77, summed in section 4.1.
2. **The JCL driver's own comment block** at `MNTTRDB2.jcl` L11 to L18, which documents
   column 1 as the action code, columns 2 to 3 as the transaction type described as a
   numeric value, and columns 4 to 53 as the description. Column 53 is the last column
   documented, so the record is 53 bytes - reached without reading a `PICTURE` clause.

Two agreeing derivations from unrelated files are what make the width checkable rather
than merely stated. **The JCL block is 1-based; the tables in sections 4.1 and 4.2 and
the whole of [`../../README.md`](../../README.md) are 0-based.** The bases are called out
because the baseline itself uses both conventions, and leaving one implicit is a
guaranteed one-byte error on every field rather than a stylistic preference.

That same JCL block is also why this scenario is not an inference. Its gloss for column 1
value `*` is the single word `COMMENT`, at L14, alongside `ADD`, `DELETE` and `UPDATE`.
The commentary semantic is a documented part of the input contract, described by the job
that feeds the program, and not a behaviour discovered by reading a fall-through.

Worth recording positively: **the 53-byte regime is new to this repository.** The house
record-length enumeration at `tests/fixtures/README.md` L140 to L142 lists 350, 300, 150,
50, 500 and 80, and 53 is not among them. No existing fixture width can be copied here,
which is exactly why this scenario carries its derivation rather than pointing at a
precedent.

### 4.4 What governs the fill: this record has no `FILLER` at all

Stated positively, because a reader arriving from the two opposing `FILLER` regimes in
[`../../README.md`](../../README.md) section 5.3 will otherwise ask which of them applies
here. **Neither applies, because there is no `FILLER` to fill.** `1 + 2 + 50 = 53`
accounts for the whole record, all three fields are `PIC X(n)`, and the program's own
`VALUE SPACES` on each of them at L73, L75 and L77 settles the pad character. The generic
right-pad-with-spaces rule therefore governs this record completely. `FILLER` dropped:
**none**. No zoned decimal, no sign overpunch and no packed field arises anywhere in this
scenario, so none of that machinery is in play and none of it can be got wrong here.

### 4.5 Sequential access, and the house step that does not transfer

`COBTUPDT.cbl` L31 to L34 declares `ORGANIZATION IS SEQUENTIAL` with
`ACCESS MODE IS SEQUENTIAL`, and L101 is a `READ ... NEXT RECORD`. A fixture in this
domain is therefore a plain sequence of 53-byte rows with **no key-ordering requirement**
beyond the scenario's own intent.

Assumptions: the house pre-sort step does not transfer to this domain, and saying so is
itself an obligation rather than a courtesy. `tests/fixtures/README.md` section 9.2 item 5
requires indexed inputs to be pre-sorted by key for its indexed loader, and section 4.2
of that document scopes the requirement to inputs declared `ORGANIZATION IS INDEXED`.
This input is declared sequential and no COBOL loader consumes these fixtures at all, so
the step has no subject here. An unexplained absence would read as an oversight, which is
why the non-transfer is written down; [`../../README.md`](../../README.md) section 8.5
records which house steps transfer and which do not.

Row order **is** load bearing in this file, for an entirely different reason: the
commented row is first so that the applied row after it proves the loop advanced. That is
a property of the scenario's intent, not of a key, and it is argued in section 7.

### 4.6 No synthetic-data attestation is owed here

Also stated positively, because an unexplained absence is indistinguishable from an
oversight. The house attestation obligation at `tests/fixtures/README.md` section 10.3 is
scoped to scenario directories holding primary account or identity data. `WS-INPUT-REC` is
an action byte, a two-character reference code and a description: it carries **no primary
account number, no account or customer identifier, no national or government-issued
identifier, no date of birth, no telephone number and no credit score**, so the obligation
does not attach to this directory and its absence is deliberate.
[`../../README.md`](../../README.md) section 8.6 records the same scoping across all five
domains of this tree.

The commented row is worth confirming separately, since it is the one row in this tree
whose content a reader might expect to be free text: its bytes are a transaction type code
and a description lifted from the seed, so it introduces no identity-shaped value and no
secret-shaped value either.

---

## 5. The `'*'` byte, stated in both directions

Two over-generalisations are available here and they point in **opposite** directions.
Each one is wrong, and each one is wrong in a way that damages something different, so
both are stated. This is the content this document exists for.

### 5.1 Direction one: a `'*'` row is not a free comment

It is a **typed record**. Byte 0 happens to carry a value the *program* interprets as
"pass over me", but to anything that checks lengths the row is still 53 bytes of data
exactly like its neighbour. Concretely, and each of these is a measurement rather than an
opinion:

- it must be padded out to the full 53 bytes, which is why offsets 3 to 52 of row 1 carry
  44 trailing spaces after `Credit`;
- it counts as a record in the record count, so `wc -l` reads 2 and not 1;
- it counts in the acceptance arithmetic of section 4.2, which is why the file is 108
  bytes and not 54.

**So the tree-level statement that a positional record file cannot carry a comment remains
true exactly as written.** A `#` line, a `--` line, or a bare `*` alone on its own line
would each be a row of the wrong length and a hard failure at load, not an ignored
annotation. Nothing in this scenario softens that rule, and nothing here licenses adding a
prose line to any file in this tree. The one comment-like thing in this directory is a
53-byte record that satisfies the width contract in full.

### 5.2 Direction two: the program really does treat it as a comment, and that is contract

The opposite error is to conclude that any byte outside `'A'`, `'U'`, `'D'` is quietly
skipped. It is not, and the counter-example is one line away in the source:

- `'*'` has its **own explicit `WHEN` at L120**. It is not reached through the catch-all.
- `MNTTRDB2.jcl` L14 documents column 1 value `*` as `COMMENT`, in the same block that
  documents `A`, `D` and `U`. The semantic is declared by the job that feeds the program.
- The consumer keeps the distinction in its type system: `RecordAction.COMMENT` is a
  first-class enum member, not a default case.
- **The very next arm, `WHEN OTHER` at L122, does the opposite of skipping** - it composes
  a message and performs the paragraph that moves 4 into `RETURN-CODE`.

So `'*'` is a member of the declared action domain that happens to have no action, and
every other byte outside that domain is a reported refusal. A fixture author who
generalises from this row to "unrecognised bytes are ignored" has described L120 and
attributed it to L122, and the sibling
[`../invalid_type_abend`](../invalid_type_abend/README.md) is the scenario that
contradicts them.

---

## 6. Failure modes these bytes can produce - Exceptions

Assumptions: these are the failure modes of the *bytes*, which is the Exceptions element
of Rule 1 L21 applied to a fixture rather than to a function. Every one of them is a way
this file can be edited into something that still looks plausible.

- **A one-byte miscount shifts every field after the miscount.** All three fields are
  character fields, so a compensated miscount that keeps the row at 53 bytes decodes
  without complaint into plausible wrong values rather than raising anything. Sections 4.1
  and 4.2 state the offsets and the contents so a boundary can be checked instead of
  assumed.
- **A blank line is a zero-length record, not a spacer.** It fails positional parsing
  outright. This warning is placed emphatically in *this* scenario because "a blank line
  separating my comment from the data" is precisely the instinct a comment row invites,
  and it is the one edit that would break the file while looking like tidying. A commented
  row is separated from the data by nothing at all: it is a record, and it is followed
  immediately by the next record's first byte.
- **A `#`-style prose line is a row of the wrong length and a hard failure at load.** If a
  note is wanted, it belongs in this document. Section 5.1 is the reason, and it applies to
  every file in this tree including this one.
- **Only a genuinely zero-byte file counts as empty.** A 108-byte file is never treated as
  empty by anything, so this scenario cannot silently degrade into the empty case; that
  case is [`../empty_input`](../empty_input/README.md), whose file is 0 bytes.
- **A rewrite to CRLF adds a byte to every record.** The carriage return would be absorbed
  into the trailing field, pushing each row to 54 bytes and breaking the arithmetic in
  section 4.2.
- **Upper-casing or otherwise adjusting byte 0 of row 1 changes the scenario into a
  different one.** `'*'` is the whole subject; any other byte in that position selects
  another arm, and nothing in the directory name would reveal the change.

Assumptions: the loaders reject any row whose length is not exactly the record length -
they do not pad a short row, do not truncate a long one and do not drop a blank line -
because a malformed record must never be silently coerced into a well-formed-looking one.
That stance is inherited rather than chosen here, and `tests/fixtures/README.md` section
3.1 states it under the heading "Verified enforcement". The rejected alternative,
tolerating an off-length row, would accept the row and shift every field after the error,
producing a fixture that loads cleanly and asserts the wrong values.

---

## 7. Why these bytes and not others

Each item below is a decision a later author could reverse without any assertion anywhere
failing, which is precisely why Rule 1 L40 requires the reason to be written beside it.
Each one also names the alternative that was rejected together with the concrete
consequence of taking it, because Rule 1 L41 forbids a rationale that records a preference
without one.

Alternatives Considered: **a live row is paired with the commented row, rather than the
commented row standing alone.** A single-row file carrying only `'*'` was the obvious
minimal fixture and is rejected, because it cannot distinguish "the commented row was
passed over" from "the read loop never advanced at all" - both produce a run with no
database effect, so the assertion would be vacuous and would pass against a reader that
stopped after one record. With the applied row present, the result carries the `COMMENT`
outcome **followed by** the `UPDATE` outcome, and the second outcome exists only because
the loop at L93 to L96 came back for it. The commented row is also deliberately
**first**: were it last, a final row that was never reached or was truncated away would
still leave the applied outcome present, and a consumer would pass for the wrong reason.

Trade-offs: **offsets 1 to 52 of the commented row carry a correctly paired real type
code and its verbatim seed description**, rather than being left blank or filled with
prose. The program never reads those bytes on this path, so this needed justifying rather
than being chosen silently. The benefit is that the row demonstrates the operational
semantic the JCL block describes - an operator disables one maintenance line by putting
`*` in column 1 and leaves the line otherwise intact - while every byte stays inside its
own field's declared domain, so nothing in the row reads as stray text. The cost is real
and is named rather than hidden: a reader skimming the file may believe type `03` was
touched. It was not. **On a `'*'` row, offsets 1 to 52 are read into `WS-INPUT-REC` along
with everything else and then simply not used by the arm that runs.** Three alternatives
were rejected, each for a concrete reason:

- **All spaces across offsets 1 to 52.** Simplest, and consistent with the program's own
  `VALUE SPACES` initialisation at L73, L75 and L77. Rejected because the row would carry
  no indication of *what* had been commented out, which is the only thing that makes a
  commented line intelligible to the next reader.
- **Free prose in offsets 3 to 52**, for example a sentence explaining the scenario.
  Rejected because prose inside a positional data field is read as that field's value:
  it would decode as a transaction description, and it would model exactly the habit
  section 5.1 forbids.
- **A deliberately mismatched pairing**, for example `02` with `Reversal`. Rejected
  outright even though each value would individually be seed-verbatim, because the file
  would then teach a wrong code-to-description pairing to anyone reading it as an example.

Trade-offs: **the applied row is a description-preserving update.** Setting type `01` to
the description it already holds makes the row idempotent, so the scenario needs no
cleanup and is safe to re-run, and it stays self-contained against the seeded table. The
compromise accepted is that the row proves *continuation* rather than *mutation*;
demonstrating that an update changes a value is the business of the `reference_update`
scenarios and is not this one's subject. Two alternatives were rejected: `'A'`, which
would require a type code and a description invented outside the seed and would add a row
to the table for a scenario that is not about inserting; and `'D'`, which is destructive
and, because every seeded type is referenced by a category, would drag in the delete
restriction and its SQLSTATE - a concern that belongs to
[`../../reference_update/delete_restricted_by_category`](../../reference_update/delete_restricted_by_category/README.md)
and not here.

Assumptions: **both values are lifted verbatim from the ASCII seed.** Row 1 carries `03`
with `Credit` and row 2 carries `01` with `Purchase`, taken from
[`app/data/ASCII/trantype.txt`](../../../../../../../../app/data/ASCII/trantype.txt),
whose seven pairs are `01` `Purchase`, `02` `Payment`, `03` `Credit`, `04`
`Authorization`, `05` `Refund`, `06` `Reversal` and `07` `Adjustment`, mixed case included.
AAP Rule T8 requires user-visible strings to be carried across character for character,
and `V2__seed_reference.sql` seeds those same seven rows in that same mixed case, so a
description that differs in case would not match the row in the schema.

The lift is also mechanically clean, which is worth recording so nobody re-pads it by
hand. `trantype.txt` is the 60-byte `CVTRA03Y` record - `TRAN-TYPE PIC X(02)`,
`TRAN-TYPE-DESC PIC X(50)` and `FILLER PIC X(08)`, declared at L5 to L7 of
[`CVTRA03Y.cpy`](../../../../../../../../app/cpy/CVTRA03Y.cpy) under the group item at
L4 - so a seed row's
description already occupies exactly 50 right-space-padded bytes at 0-based offsets 2 to
51. The move is seed `[2:52)` into fixture `[3:53)` with **no re-padding at all**, and it
cannot pick up the seed's line ending either: rows 1 to 6 of that file end CRLF and the
carriage return sits at 0-based byte 60, immediately after the eight-byte `FILLER` region
at offsets 52 to 59 and far beyond the description's last byte. Mind the two positions when
checking it - offsets 2 to 51 in the seed, offsets 3 to 52 here, same 50 bytes of content.

Alternatives Considered: **taking a description from the Db2 control card**
[`DB2LTTYP.ctl`](../../../../../../../../app/app-transaction-type-db2/ctl/DB2LTTYP.ctl),
or its category counterpart `DB2LTCAT.ctl`. Superficially attractive, because those files
are the reference-data loader for the very extension tree this flow belongs to. Rejected
on two concrete grounds: their values are UPPERCASE on every row, and `DB2LTTYP.ctl` L22
carries `'REVERAL'` where the seed carries `Reversal`. `V2__seed_reference.sql`
deliberately seeds from the ASCII files instead, so a control-card-derived value would not
match the row seeded into the schema and would breach AAP Rule T8. This is the derivation
mistake most likely to look reasonable here, so the rejected source is named by path
rather than left unmentioned; [`../../README.md`](../../README.md) section 6.6 rules the
same way for the tree as a whole.

---

## 8. How this file discharges Rule 1

Rule 1 is the **only** user-specified rule on this project. It is cited above by clause -
L15, L18 to L21, L22, L27, L40, L41 and L43 - and never reproduced; its full text is
available through the project rules document. The migration's own transformation rules are
a different namespace and are written out in full wherever they appear, as **AAP Rule T8**
above, so the two can never be read as one numbering.

The four docstring elements Rule 1 L18 to L21 name are discharged as follows, which is the
mapping [`../../README.md`](../../README.md) section 8.4 sets for every scenario README in
this tree:

| Rule 1 element | Where it is discharged here |
|---|---|
| Purpose (L18) | sections 1 and 2 - the scenario's intent and the exact arm it exercises |
| Parameters (L19) | section 4 - the per-field table with offsets, `PICTURE`, fill and declaring line, plus the file geometry |
| Return values (L20) | section 3 - the specific outcome per record, the run-level aggregate, and the consumer |
| Exceptions (L21) | section 6 - the failure modes these bytes can produce |

Every non-obvious choice carries one of the three labels this tree uses -
`Alternatives Considered:`, `Assumptions:` and `Trade-offs:` - written plural,
unemphasised and with the colon as part of the label, as
[`../../README.md`](../../README.md) section 2.2 requires. The fourth label Rule 1 L31 to
L34 names is scoped by L32 to replacing existing code; nothing in this directory replaces
anything, so its precondition is never met and it appears nowhere in this file.

**No mechanical gate applies to this document.** `config/checkstyle/checkstyle.xml`
narrows its `Checker` to `fileExtensions="java"`, so no file in this directory is ever
scanned, and while `config/checkstyle/suppressions.xml` does carry an entry matching
`src/test/resources/fixtures/`, that file describes its own entry as belt-and-braces for
the narrow case of a `.java` file co-located with a fixtures directory. A suppression
permits a path; it authors no content and discharges no obligation. Rule 1 L43 is
satisfied here by **review alone**, and authoring discipline is the only protection these
bytes have. That is the same ruling [`../../README.md`](../../README.md) section 2.3
records for the tree.

<sub>Reference-only inputs cited above - `app/app-transaction-type-db2/**`,
`app/cpy/CVTRA03Y.cpy`, `app/data/ASCII/trantype.txt` and `tests/fixtures/README.md` - are
read as the specification for these bytes and are never modified. The seeds are inputs to
derivation: a fixture is created by copying rows out of them.</sub>
