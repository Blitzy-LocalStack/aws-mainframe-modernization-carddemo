# Batch Service

> **Purpose.** Build, invoke, and reason about the batch bounded context of the
> CardDemo mainframe-to-AWS migration — the seven argument-driven jobs that
> re-express the nightly z/OS batch chain, the business rules they preserve
> byte-for-byte, and the two places where they deliberately diverge from the
> baseline.
>
> **Source of truth.** The COBOL and JCL under `app/**`, cited throughout by
> path and line; `services/batch-service/**` for the implementation;
> [`tests/README.md`](../../tests/README.md) for the parity oracle; and
> [`docs/CODE_DOCUMENTATION_STANDARD.md`](../../docs/CODE_DOCUMENTATION_STANDARD.md)
> for the documentation convention this file is itself bound by.

**This is the only module in `services/` measured directly against the
golden-master oracle.** `tests/README.md` §1.1 records that the online `CO*`
programs cannot run end to end without a CICS runtime, which is absent from the
runner. The batch programs are therefore the only place where COBOL output and
Java output can be compared byte for byte, and everything here is held to a
higher parity standard than anywhere else in the repository. That is why this
document carries every business rule with its originating line number rather
than summarising it: a rule stated loosely here becomes a rule implemented
loosely, and the golden masters would catch it only after the fact.

---

## 1. Purpose and scope

| Property | Value |
|---|---|
| Bounded context | Batch — the nightly chain |
| Java package root | `com.carddemo.batch` |
| Maven artifactId | `batch-service` |
| Parent | `com.carddemo:carddemo-services:1.0.0-SNAPSHOT` |
| Sibling dependency | `common-lib`, and no other |
| Owned schema | `batch` |
| Additional authority | Scoped cross-schema grants, detailed in §5 |
| HTTP surface | `/actuator/health` only, on port `8080` |
| Invocation | Process arguments, never an HTTP request |

**The module is argument-driven, not request-driven.** Each job runs as one
container task started by a state-machine state through a synchronous run-task
integration, with the task's command override carrying `--job=…` and
`--business-date=…`. There is no business REST surface, no administrative
trigger, and no published API contract; §9 records why, because choosing not to
expose one is a decision a reader could reasonably expect to have gone the other
way.

It **owns** the PostgreSQL schema `batch` — the `batch_run` step ledger plus the
Spring Batch `JobRepository` tables — and additionally holds narrowly scoped
cross-schema grants that let the posting unit of work stay a single ACID commit.
The grants are enumerated exactly in §5; this module documents them and does not
create them.

**`app/**` is reference-only.** Every COBOL program, copybook, BMS map, JCL
member, and seed dataset under it is the behavioural specification for this
module. It is read, cited by path and line, and never modified — including where
it contains a defect (§8). `tests/**` and `scripts/**` are likewise reference:
they are the parity oracle, and they are never modified and never re-pinned.

## 2. The seven jobs, with COBOL and JCL provenance

| Job class | COBOL | JCL driver | What it does | Business date |
|---|---|---|---|---|
| `PreflightDailyTransactionsJob` | `app/cbl/CBTRN01C.cbl` | none | Validates the daily transaction feed before posting; writes nothing | Required as an argument |
| `PostTransactionsJob` | `app/cbl/CBTRN02C.cbl` | `app/jcl/POSTTRAN.jcl:23-42` | Posts each daily transaction or rejects it with one of four reasons; the only job that produces the warn tier | Required as an argument |
| `CalculateInterestJob` | `app/cbl/CBACT04C.cbl` | `app/jcl/INTCALC.jcl:22-41` | Accrues interest per category row, flushes it to the account on each control break | Required, and consumed by the rule |
| `BackupTransactionsJob` | none — `IDCAMS REPRO` | `app/jcl/TRANBKP.jcl` | Exports the transaction master to a new dataset generation | Required as an argument |
| `CombineTransactionsJob` | none — DFSORT | `app/jcl/COMBTRAN.jcl:22-48` | Merges the posting and interest outputs in `TRAN-ID` order and loads the master | Required as an argument |
| `ExportJob` | `app/cbl/CBEXPORT.cbl` | `app/jcl/CBEXPORT.jcl:43` | Writes the 500-byte packed-decimal branch-migration record | Required as an argument |
| `ImportJob` | `app/cbl/CBIMPORT.cbl` | `app/jcl/CBIMPORT.jcl:22` | Reads that record back and splits it into normalised outputs | Required as an argument |

Four of those rows carry a fact that is easy to get wrong, so each is stated
rather than left to be rediscovered.

**The export and import programs each have a JCL driver, and only `CBTRN01C` does
not.** `app/jcl/CBEXPORT.jcl:43` reads `//STEP02 EXEC PGM=CBEXPORT` and
`app/jcl/CBIMPORT.jcl:22` reads `//STEP01 EXEC PGM=CBIMPORT`; both members are named
after the program they drive, which is why a search for a driver has to match on
`PGM=` rather than on the member name. Assumptions: "the parity oracle cannot run
it" and "the baseline never scheduled it" are independent facts and must not be
conflated. `CBEXPORT` and `CBIMPORT` are undriveable *by the open-source compiler*
because of the file-description defect (§8.1), and they are separately *driven* by
JCL on the mainframe — the driver is what fixes each step's position in the batch
chain and its dataset dispositions.

**`CBTRN01C` has no JCL driver anywhere in the baseline.** A search across all 38
members of `app/jcl` for that program name returns nothing; only an integration
test drives it. It is migrated regardless, because it is the feed-validation pass
the posting step depends on. Its baseline shape corroborates the absence:
`app/cbl/CBTRN01C.cbl:154` is a bare `PROCEDURE DIVISION.` with no `USING`
clause, the program contains no `WRITE` statement, and it never sets
`RETURN-CODE`. Assumptions: it is a pure validation pass with no dataset output
and no return code of its own, so its migrated step reports only success or
hard failure.

**`CBTRN02C` receives no parameter.** `app/jcl/POSTTRAN.jcl:23` reads
`//STEP15 EXEC PGM=CBTRN02C` and carries neither `PARM` nor `COND`. Its nine data
definitions follow at `:24-42`, and the reject stream at `:34-38` is created new
each run with `DCB=(RECFM=F,LRECL=430,BLKSIZE=0)` at `:36`.

**`CBACT04C` requires an injected date.** `app/jcl/INTCALC.jcl:22` reads
`//STEP15 EXEC PGM=CBACT04C,PARM='2022071800'`. §4.6 covers what that parameter
is for and the trap in its format.

### 2.1 `CombineTransactionsJob` — the verified algorithm

The DFSORT step this job replaces is not a single-input sort, and that matters
for correctness rather than for style:

1. **Two inputs are concatenated at generation `(0)`** —
   `AWS.M2.CARDDEMO.TRANSACT.BKUP(0)` at `app/jcl/COMBTRAN.jcl:23-24` and
   `AWS.M2.CARDDEMO.SYSTRAN(0)` at `:25-26`, the second arriving as a second `DD`
   statement with no name of its own. Assumptions: this is **the join point
   between the posting pipeline and the interest pipeline** — the backup carries
   posted transactions, the system-transaction dataset carries the interest
   transactions `CBACT04C` generated. A job that read only the first input would
   silently drop every interest transaction of the night and still complete.
2. **`SORT FIELDS=(TRAN-ID,A)`** at `:30`, over the symbol `TRAN-ID,1,16,CH`
   declared at `:27-28`, becomes `ORDER BY tran_id ASC`. The symbol's position
   and length agree with `TRAN-ID PIC X(16)` at `app/cpy/CVTRA05Y.cpy:5`.
3. **The output is written to `TRANSACT.COMBINED(+1)`** at `:37`, with
   `DCB=(*.SORTIN)` at `:35` inheriting the input record format.
4. **The combined dataset is loaded into the transaction master** by the second
   step at `:41-48`, whose `REPRO INFILE(TRANSACT) OUTFILE(TRANVSAM)` at `:48`
   reads the same `(+1)` generation re-referenced at `:44` and writes the master
   named at `:45-46`.

### 2.2 A dataset-name collision worth knowing before reading the JCL

`TRANFILE` and `TRANSACT` look interchangeable and are not. In
`app/jcl/POSTTRAN.jcl:28-29` the `TRANFILE` data definition names the VSAM
key-sequenced transaction master. In `app/jcl/INTCALC.jcl:37-41` the `TRANSACT`
data definition names a newly created **sequential** generation output. Two
different datasets, two different organisations, similar names. Assumptions:
anyone mapping data definitions to target resources has to disambiguate these by
dataset name rather than by data-definition name.

## 3. Invocation and the exit-status contract

Two options are read, **both required and neither defaulted**, and any other
argument is left to the framework's own command-line property source:

- `--job=<name>` selects the job. `<name>` is exactly one of
  `preflight-daily-transactions`, `post-transactions`, `calculate-interest`,
  `backup-transactions`, `combine-transactions`, `export`, `import`. The token is
  the name a job bean registers under, so it is looked up rather than switched on.
- `--business-date=<token>` supplies the business date under the job-parameter
  key `businessDate`. The token is exactly ten characters, each an ASCII digit or
  an ASCII hyphen-minus, and it is forwarded **verbatim** — see §4.6.

Assumptions: neither option carries a default because the invoking state passes
both as command overrides, and a default here would give one task two sources of
truth that can disagree, with the loser losing silently. An absent, blank,
repeated, or unrecognised option is a hard failure whose diagnostic enumerates
every accepted value, never a fall-back to something plausible. The interest job
is the one whose business rule actually consumes the date; the entry point
requires it for every token so that a run is reproducible whichever job it names.

### 3.1 The three exit-status tiers

| Exit status | Meaning | Baseline origin |
|---|---|---|
| `0` | Clean completion, no rejects | `CBTRN02C` leaves `RETURN-CODE` untouched when its reject counter is zero; ten of the fourteen `return_code.expected` files under `tests/golden/` record `0` |
| `4` | Soft warn — completed and produced rejects; the chain continues | `app/cbl/CBTRN02C.cbl:229` `IF WS-REJECT-COUNT > 0` and `:230` `MOVE 4 TO RETURN-CODE`; the remaining four expectation files record `4`, and every one is a posting reject scenario |
| `>= 8` | Hard failure — argument validation, infrastructure, I/O, or job execution | The baseline's `APPL-RESULT`: `8` is the value pre-set before an operation so a silent no-op still reads as failure, `12` marks an I/O failure and leads to the abend paragraph, `16` is an end-of-file sentinel rather than a severity |

A malformed command line reports in the hard-failure tier at `8`, not at the
parity oracle's usage tier of `2`. Alternatives Considered: `2` was rejected
because the orchestration gate is spelled `rc <= 4`, so a usage failure reported
as `2` would satisfy the gate and the chain would continue past a step that
never processed a single record. `8` is the lowest value the gate refuses.

### 3.2 The predicate inversion, stated in full

**A job-control `COND` is a skip predicate; a state-machine `Choice` is a run
predicate.** `app/jcl/TRANBKP.jcl:51` reads
`//STEP10 EXEC PGM=IDCAMS,COND=(4,LT)` — skip this step when 4 is less than the
accumulated return code — so the step **runs when the code is 4 or lower**. The
equivalent target gate is therefore spelled `rc <= 4`, with the comparison the
other way round.

Refactoring Rationale: what was wrong with carrying the original spelling across
is concrete rather than stylistic. A predicate written `rc > 4` to mirror the
`COND` keyword would run the consuming step exactly when the baseline skipped it
and skip it exactly when the baseline ran it — and every clean night would still
look correct, because a clean night reports `0` under either spelling. The error
would surface only on the first night that rejected a transaction.

**That gate is not the consumer of this module's warn tier, and the two must not
be conflated.** A `COND` parameter is evaluated against the return codes of
earlier steps *in the same job* and can see nothing outside it. `TRANBKP.jcl:51`
gates its own job's cluster redefine against its own job's preceding steps: the
procedure-driven copy, and the `IDCAMS` delete whose code that step then
normalises to zero with `IF MAXCC LE 08 THEN SET MAXCC = 0` at `:42` and `:45`.
It cannot observe `CBTRN02C`, which runs in a different job entirely —
`app/jcl/POSTTRAN.jcl:23` carries no `COND` at all — so nothing in the baseline
job control consumes posting's `4` downstream. The warn tier's authority is the
program's own contract at `app/cbl/CBTRN02C.cbl:229-230` together with the four
committed expectation files that record it. `TRANBKP.jcl:51` is cited here solely
as the one place the baseline demonstrates the inverted *sense* of a threshold
comparison; the threshold value coinciding with posting's warn code is a
coincidence, and reading it as a data path would invent a dependency the baseline
does not have.

### 3.3 The verbatim counter lines

Posting emits two counter lines whose spacing is part of the observable output
the oracle compares:

- `app/cbl/CBTRN02C.cbl:227` — `TRANSACTIONS PROCESSED :`, **one** space before
  the colon.
- `app/cbl/CBTRN02C.cbl:228` — `TRANSACTIONS REJECTED  :`, **two** spaces before
  the colon.

Assumptions: the two widths are not a typo — they make the colons align in the
job log, and both were read character by character from the source before being
reproduced here. Standard output belongs to the running job; every diagnostic the
entry point writes goes to standard error instead, so it cannot interleave with
those bytes.

### 3.4 Where the numeric rubric is allowed to exist

Trade-offs: the graded `0` / `4` / `>= 8` scale is quarantined to exactly one
surface — the container's process exit status, which is numeric only because the
orchestrator reads it — and to the parity oracle under `tests/`, which grades
`0`, `2`, `4`, `8`, and `16`. **It never reaches a Maven, Checkstyle, Surefire,
Failsafe, or JUnit gate, all of which stay binary.** The cost of quarantining the
two vocabularies this strictly is that two adjacent parts of one repository count
success differently and a reader has to know which one is speaking. The cost of
not doing it is a build that reports success while failing, because a graded
rubric leaking into a build gate turns a real test failure into a tolerated
warning.

### 3.5 A look-alike that is not a step gate

`INCLUDE COND=(...)` inside a DFSORT step — for example
`app/jcl/TRANREPT.jcl:47` — is a **record-selection** predicate. It becomes a SQL
`WHERE` clause and must never be modelled as a `Choice` state. Assumptions: the
two forms share the `COND` keyword and nothing else, and conflating them turns a
row filter into a step gate, which would either skip a step that should run or
process rows that should have been excluded.

## 4. Business rules preserved exactly — the parity contract

Every rule below was read from the baseline source before being written here, and
every line reference was verified against the file. Where the migration
implements something differently, it says so and §8 registers it.

### 4.1 The posting reject reasons

The validation chain is `1500-VALIDATE-TRAN` at `app/cbl/CBTRN02C.cbl:370-378`,
which calls the cross-reference lookup and then, only if no reason has been set,
the account lookup. Each reject writes the reject stream, and any reject makes
the run report the warn tier.

| Reason | Condition | Message text, verbatim | Lines |
|---|---|---|---|
| `100` | Card number absent from the cross-reference | `INVALID CARD NUMBER FOUND` | `:380-392` |
| `101` | Account record not found | `ACCOUNT RECORD NOT FOUND` | `:393-399` |
| `102` | Transaction would exceed the credit limit | `OVERLIMIT TRANSACTION` | `:403-413` |
| `103` | Transaction received after account expiration | `TRANSACTION RECEIVED AFTER ACCT EXPIRATION` | `:414-420` |

Every message is carried character for character, per transformation rule T8.
These four strings and both boundaries below are the same ones
[`tests/README.md`](../../tests/README.md) §13 asserts verbatim.

**The over-limit boundary is inclusive on the accept side.** `:407` reads
`IF ACCT-CREDIT-LIMIT >= WS-TEMP-BAL` with `CONTINUE` on the true branch and
`MOVE 102` only in the `ELSE` at `:409-412`. A transaction landing **exactly at
the credit limit posts**; one cent over rejects. Assumptions: a `>` written where
the baseline has `>=` changes which transactions post, by exactly one cent's
worth of transactions, and produces a reject stream that differs from the golden
master in a way no compiler or type checker can detect.

**The balance the limit is tested against is the cycle balance, not the current
balance.** `:403-405` computes
`WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT`.
Assumptions: this is the same pair of cycle buckets that interest accrual zeroes
on every control break (§4.8), so the two programs are coupled through those two
columns even though neither calls the other.

**The expiration boundary is inclusive too.** `:414` reads
`IF ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS (1:10)`, so a transaction dated
**equal to** the expiration date posts. Assumptions: the comparison is a lexical
compare over the first ten characters of the 26-character originating timestamp,
which is only equivalent to a date comparison because the stored form is
ISO-ordered `YYYY-MM-DD`. The field name misspelling is the baseline's and is
discussed in §12.

**Reasons 102 and 103 are evaluated in sequence, not short-circuited.** Both
tests sit inside the one successful-read branch, so when a transaction is both
over limit and past expiration the `103` verdict overwrites the `102` verdict.
Only reason `100` short-circuits everything downstream, through the guard at
`:372-376`. Assumptions: an implementation that returned on the first failure
would report `102` where the baseline reports `103`, changing the reason code in
the reject stream while still rejecting the same transaction.

**The reject record is 430 bytes**, and that width is proven four independent
ways:

1. `app/cbl/CBTRN02C.cbl:176-178` — `REJECT-TRAN-DATA PIC X(350)` plus
   `VALIDATION-TRAILER PIC X(80)`.
2. `app/cbl/CBTRN02C.cbl:81-84` — the same split on the file-description side.
3. `app/jcl/POSTTRAN.jcl:36` — `DCB=(RECFM=F,LRECL=430,BLKSIZE=0)`.
4. `tests/golden/posting/reject_102_overlimit/dalyrejs.expected` — every line
   exactly 430 characters.

The 80-byte trailer is itself split at `:180-182` into
`WS-VALIDATION-FAIL-REASON PIC 9(04)` and
`WS-VALIDATION-FAIL-REASON-DESC PIC X(76)`, and `:447-448` shows the record being
assembled as the 350-byte daily transaction verbatim followed by that trailer. The
target preserves the contract as `raw_record CHAR(350)` plus
`reason_code SMALLINT` plus `reason_desc VARCHAR(76)` — the same three parts, at
the same widths, in a form a query can filter.

### 4.2 The three-write unit of work

`2000-POST-TRANSACTION` at `app/cbl/CBTRN02C.cbl:424-444` populates the
transaction record from the daily record and then performs **three writes as one
unit of work**, in this order, at `:440-442`:

1. `2700-UPDATE-TCATBAL` — the transaction category balance
2. `2800-UPDATE-ACCOUNT-REC` — the account
3. `2900-WRITE-TRANSACTION-FILE` — the transaction

In the target these are one `@Transactional` boundary, per transformation rule T5.

Alternatives Considered: the architecture decision this module implements states
it directly -- *"Rather than fragment that into a saga, `batch-service` runs against
the one Aurora cluster using a dedicated database role holding narrowly-scoped
cross-schema write grants on `ledger.*` and `account.*` only, so the unit of work
remains a single ACID commit. A transactional-outbox-plus-compensating-reversal
design was considered and rejected: it would introduce observable partial-posting
states that do not exist in the baseline, which would break golden-master parity
outright."* Concretely, a saga would make a state such as *a posted transaction
with an unposted balance* observable between its committed steps. That state does
not exist in the baseline at all, so the golden masters would correctly flag it as
a parity failure — the compensations would converge on the right numbers in the
end, and the intermediate states would still be wrong.

**This is the one documented exception to database-per-service purity in the whole
migration.** The grants themselves are created by
[`data-migration/sql/V0__schemas_and_roles.sql`](../../data-migration/sql/V0__schemas_and_roles.sql);
this module documents them and does not grant them.

The corollary is a structural rule rather than a convention. This module declares
its **own** local JPA mappings for the tables it reaches across a schema boundary
— `account.accounts` and `account.card_xref` are mapped in
`com.carddemo.batch.domain`. There is **no** Maven dependency on
`transaction-service` or `account-service` and **no** cross-service `domain`
import; the shared ArchUnit rule set forbids it, so a violation fails a test
rather than a review. Assumptions: **this module and the owning contexts agree
through the physical schema, never through code.** Both validate their mappings
against the same schema at startup, so a mapping that names a column the schema
does not have fails before a row is read — which is the agreement that actually
matters, and it costs no compile-time coupling to get it.

### 4.3 The category-balance branch

`2700-UPDATE-TCATBAL` at `app/cbl/CBTRN02C.cbl:467-501` reads the category row by
`(account, type, category)` and branches at `:495-499`:

- **Create** — `2700-A-CREATE-TCATBAL-REC` at `:503-524`: initialise the record,
  move the three key fields, `ADD DALYTRAN-AMT TO TRAN-CAT-BAL` at `:508`,
  `WRITE` at `:510`.
- **Update** — `2700-B-UPDATE-TCATBAL-REC` at `:526-542`:
  `ADD DALYTRAN-AMT TO TRAN-CAT-BAL` at `:527`, `REWRITE` at `:528`.

The target expresses this as an upsert **with both paths distinguishable and
separately tested**. Trade-offs: an opaque single-statement merge would be shorter
and would hide which path ran, and that is exactly what makes it unacceptable
here — the branch is behaviour the golden masters assert, and two independent
observables distinguish the paths in the baseline. The create path announces
itself on standard output at `:476-477`
(`TCATBAL record not found for key : … .. Creating.`), and the two paths carry
**distinct error literals**: `ERROR WRITING TRANSACTION BALANCE FILE` at `:520`
against `ERROR REWRITING TRANSACTION BALANCE FILE` at `:538`. An implementation
that cannot say which path it took cannot reproduce either observable, and the
accepted cost of keeping them separate is one extra branch in the service.

### 4.4 The interest calculation

The formula, verbatim from `app/cbl/CBACT04C.cbl:464-465`:

```cobol
           COMPUTE WS-MONTHLY-INT
            = ( TRAN-CAT-BAL * DIS-INT-RATE) / 1200
```

**Multiply at full precision first, then divide.** Alternatives Considered:
dividing first and multiplying second is algebraically identical and
computationally different — it yields different cents on many inputs, because the
intermediate quotient is rounded before the multiplication rather than after.
Re-ordering is forbidden, and the ordering is asserted by test to the cent.

**The quotient is truncated toward zero, matching the baseline cent for cent.**
Assumptions: the reference stores its result into `WS-MONTHLY-INT PIC S9(09)V99` at
`:168` and a COBOL `COMPUTE` without `ROUNDED` discards the surplus digits toward
zero — and no statement anywhere in that program's 652 lines carries `ROUNDED`. The
accrual therefore reduces under `Money.BASELINE_INTEREST_ROUNDING`, which is
`RoundingMode.DOWN` and which governs that one operation; every other reduction in
the money path uses `Money.GENERAL_ROUNDING`, half up.
`InterestCalculationService.ACCRUAL_ROUNDING` names the mode beside this service and
is asserted equal to the shared constant, so a name that drifted from the behaviour
fails the build. `Money.monthlyInterest` takes no rounding-mode parameter and no call
site can select another.

Refactoring Rationale: the accrual reduced with `HALF_UP` and the resulting cent was
registered as divergence **C-ROUNDING**. That is withdrawn — the accrual formula is
one of the business rules the reference test suite asserts verbatim, so a cent of
drift in it is a parity failure rather than a rounding preference, and the cent did
not stay local: line 467 adds each reduced term into the account total and line 352
adds that total to the account balance, which the next **inclusive** over-limit
comparison is made against. The identifier survives only as a withdrawal record in
§7.5 of `docs/architecture/cobol-to-service-traceability.md`.
Alternatives Considered: keeping a mode parameter so a parity caller could ask for
truncation while others kept half up. Rejected because a selectable mode is a second
money contract in disguise: two call sites computing the same accrual could disagree
by a cent with nothing signalling that they had chosen differently.

The operand types set the shape of the arithmetic:

- `TRAN-CAT-BAL PIC S9(09)V99` at `app/cpy/CVTRA01Y.cpy:9`
- `DIS-INT-RATE PIC S9(04)V99` at `app/cpy/CVTRA02Y.cpy:9`

Assumptions: `DIS-INT-RATE` is an **annual percentage** with four integer digits
and two decimals, so its domain reaches `9999.99` percent, and the divisor `1200`
is the twelve months times the hundred of a percentage in one constant. Storing
the rate as a fraction instead would need the divisor changed in step, and the
seeded reference data is expressed as percentages.

**Interest is reduced per category row and then summed**, not accumulated at full
precision and reduced once: `:467` reads `ADD WS-MONTHLY-INT TO WS-TOTAL-INT`,
after the `COMPUTE` has already stored into a two-place field. Assumptions: the
order of reduction and summation is observable in the account balance, because
rounding does not distribute over addition — summing first would produce a total
that differs by cents on a multi-row account.

**One transaction row is written per category row, not per account.** `:468`
performs `1300-B-WRITE-TX` from *inside* `1300-COMPUTE-INTEREST`, and the gate is
`IF DIS-INT-RATE NOT = 0` at `:214`. Assumptions: in Java that gate is
`rate.compareTo(BigDecimal.ZERO) != 0` and **not** `equals(BigDecimal.ZERO)`,
because `equals` on `BigDecimal` compares scale as well as value, so a rate read
as `0.00` would not equal `ZERO` and every zero-rate row would generate a
zero-amount interest transaction the baseline never writes.

### 4.5 The `DEFAULT` disclosure-group fallback

`1200-GET-INTEREST-RATE` at `app/cbl/CBACT04C.cbl:415-440` reads the disclosure
group by composite key. On file status `'23'` — record not found — `:436-438`
does `MOVE 'DEFAULT' TO FD-DIS-ACCT-GROUP-ID` at `:437` and re-reads through
`1200-A-GET-DEFAULT-INT-RATE`. Only the group-id component is overwritten; the
type code and category code moved in at `:211-212` are left exactly as they were.

**A missing `DEFAULT` row abends the program.** The retry at `:443-460` has **no
`INVALID KEY` clause** — `:444` is a bare `READ` — and `:446` accepts only status
`'00'`, sending anything else to `ERROR READING DEFAULT DISCLOSURE GROUP` at
`:455` and then to the abend paragraph. Assumptions: the fallback does not degrade
to zero interest, and an implementation that returned zero on a missing default
would complete a night that the baseline refuses to complete.

**The seed requirement is not one row — it is one row per `(type code, category
code)` pair.** The retry key is `'DEFAULT   '` (10) ‖ the original type code (2)
‖ the original category code (4) = 16 characters, so a default row must exist for
every pair the accrual walk can present. The reference data seeds **seventeen**
such rows.

**Cross-service precondition.** Those rows are seeded by `reference-service`
through
[`V2__seed_reference.sql`](../reference-service/src/main/resources/db/migration/V2__seed_reference.sql),
not by this module. Assumptions: a missing default row therefore presents *here*
as an abend whose cause **is not local**, and stating that plainly is the point —
an operator who does not know it will debug this module for a defect that lives in
another one.

**The padding trap.** `DIS-ACCT-GROUP-ID` is `PIC X(10)` at
`app/cpy/CVTRA02Y.cpy:6`, so the seven-character literal `'DEFAULT'` is
space-padded to `'DEFAULT   '`. Assumptions: PostgreSQL compares `CHAR(n)`
ignoring trailing blanks, so a `CHAR(10)` column matches `= 'DEFAULT'` either
way; were the column `VARCHAR`, the seeded value would have to be exactly
`'DEFAULT'` unpadded, and a padded seed would never match. The seed writes the
padded form, which is correct for `CHAR(10)` and is why the width appears in the
seed file.

**The key-order trap, which is the one most likely to be got wrong.** The
*physical* key order is `ACCT-GROUP-ID X(10)` → `TRAN-TYPE-CD X(02)` →
`TRAN-CAT-CD 9(04)`, established at `app/cpy/CVTRA02Y.cpy:5-8` and confirmed
independently by the file description at `app/cbl/CBACT04C.cbl:76-82`. **But the
COBOL moves the fields in a different order** at `:210-212`: group id, then
**category** code, then **type** code. Assumptions: **move order is not field
order** — the moves target named subfields, so their sequence is irrelevant to
the resulting key, and reading them as the layout produces
`group_id ‖ cat_cd ‖ type_cd`, which is wrong. The correct composite key is
`group_id(10) ‖ type_cd(2) ‖ cat_cd(4)`.

### 4.6 The business date is a job parameter, never a clock read

`app/jcl/INTCALC.jcl:22` injects `PARM='2022071800'`. Assumptions: the business
date is passed as a parameter and never read from the clock, and that is what
makes a rerun of a given night reproduce its predecessor — which is what makes
golden comparison possible at all. `LocalDate.now()`, `Instant.now()`, or any
equivalent on the business path is a defect, not a shortcut.

**The format trap.** The baseline parameter is the compact ten-character token
`2022071800` — `yyyyMMdd` followed by `00` — and **not** an ISO date. The
generated interest transaction id is `PARM-DATE` (10) concatenated with a
six-digit sequence, `DELIMITED BY SIZE`, at `app/cbl/CBACT04C.cbl:476-480`, into
`TRAN-ID PIC X(16)` (`app/cpy/CVTRA05Y.cpy:5`), which the two parts fill exactly.
With `PARM='2022071800'` the first generated id is therefore
`2022071800000001`, while a naive `businessDate.toString()` would emit
`2022-07-18000001` — the same sixteen characters, silently different.

**The resolution is a passthrough, not a reformat.** The token is validated for
width and character class only and forwarded verbatim under the job parameter
`businessDate`. Assumptions: the baseline field is `PARM-DATE PIC X(10)` at
`app/cbl/CBACT04C.cbl:178`, which is alphanumeric, and the concatenation applies
no formatting whatsoever — whatever the caller supplied is emitted as supplied.
Both shapes are committed expectation files:
`tests/golden/interest/happy_path/transact.expected` begins `2024-01-15000001`,
the ISO form, and
`tests/golden/interest/e2e_interest_cycle_transactions.expected` begins
`2022071800000001`, the compact form. Alternatives Considered: normalising the
token to either single layout was rejected because it would change the identifiers
of one of those two committed scenarios and break its golden comparison. Ten
characters plus a six-digit suffix is the invariant; the layout inside those ten
characters is the caller's.

Assumptions: the baseline's own linkage carries a halfword length ahead of the
date — `01 EXTERNAL-PARMS.` at `:176`, `PARM-LENGTH PIC S9(04) COMP` at `:177`,
`PARM-DATE PIC X(10)` at `:178` — so the date sits at offset two of the parameter
area. That prefix is an artefact of how the reference operating system passes a
parameter string, has no analogue in a process argument list, and is dropped
rather than modelled. Only the ten-character payload crosses into this module.

**The one legitimate clock read.** `app/cbl/CBACT04C.cbl:496-498` stamps **both**
`TRAN-ORIG-TS` and `TRAN-PROC-TS` from the wall clock, not from the parameter;
posting does the same for `TRAN-PROC-TS` at `app/cbl/CBTRN02C.cbl:437-438` while
copying `TRAN-ORIG-TS` from the daily record at `:436`. Trade-offs: the target
injects a `java.time.Clock` bean for record stamping so a test can pin it, while
the **business date** always comes from the job parameter. The two are different
concerns that a single `now()` call would conflate, and the timestamps are the
reason the parity method normalises timestamps before comparing (§10.4).

### 4.7 Generated interest-transaction field values

`1300-B-WRITE-TX` at `app/cbl/CBACT04C.cbl:473-515` writes one transaction per
qualifying category row. The golden masters assert these values, so they are
reproduced exactly:

| Field | Value | Line | Note |
|---|---|---|---|
| `TRAN-ID` | business-date token ‖ six-digit sequence | `:476-480` | Fills `X(16)` exactly |
| `TRAN-TYPE-CD` | `'01'` | `:482` | `X(02)`, stored as given |
| `TRAN-CAT-CD` | `'05'` | `:483` | The field is `PIC 9(04)`, so the two-character literal is stored as **`0005`**, not `05` followed by blanks |
| `TRAN-SOURCE` | `'System'` | `:484` | `X(10)`, blank-padded |
| `TRAN-DESC` | `'Int. for a/c '` ‖ account id | `:485-489` | `X(100)`, blank-padded |
| `TRAN-AMT` | the reduced monthly interest | `:490` | `S9(09)V99` |
| `TRAN-MERCHANT-ID` | `0` | `:491` | |
| `TRAN-MERCHANT-NAME` / `-CITY` / `-ZIP` | spaces | `:492-494` | |
| `TRAN-CARD-NUM` | the cross-reference card number | `:495` | Read by account id — see §4.11 |
| `TRAN-ORIG-TS` / `TRAN-PROC-TS` | the same wall-clock stamp | `:496-498` | Normalised before comparison |

**The sequence counter increments before use.** `:474` is
`ADD 1 TO WS-TRANID-SUFFIX`, against `WS-TRANID-SUFFIX PIC 9(06) VALUE 0` at
`:173`, so the first transaction of a run carries `000001`. Assumptions: the
counter is monotonic across the whole run and is never reset on a control break,
so ids are unique across accounts rather than per account.

### 4.8 The account update on control break

`1050-UPDATE-ACCOUNT` at `app/cbl/CBACT04C.cbl:350-370` makes **three** state
changes, not one, and then rewrites the record at `:356`:

1. `ADD WS-TOTAL-INT TO ACCT-CURR-BAL` at `:352`
2. `MOVE 0 TO ACCT-CURR-CYC-CREDIT` at `:353`
3. `MOVE 0 TO ACCT-CURR-CYC-DEBIT` at `:354`

Assumptions: steps 2 and 3 are a **billing-cycle reset**, and they are the whole
reason the update is more than an interest posting. They zero exactly the two
columns the posting program's over-limit test reads at
`app/cbl/CBTRN02C.cbl:403-405`, so the reset re-bases the credit-limit check for
the next cycle. Omitting them would leave every account's over-limit basis
growing without bound while the interest figures still looked correct.

The control break itself is at `:194-206`: `TRANCAT-ACCT-ID PIC 9(11)` is compared
against `WS-LAST-ACCT-NUM PIC X(11) VALUE SPACES` at `:167`, and a first-iteration
guard at `:195-199` suppresses the flush before any account has been accumulated.
Assumptions: the target uses a nullable account-id field plus an explicit
first-iteration flag rather than reproducing the blanks sentinel, because a
blank-filled numeric sentinel is a COBOL storage idiom with no Java equivalent
that would not also be a lie about the field's type.

Two further observable properties:

- `DISPLAY TRAN-CAT-BAL-RECORD` at `:193` emits exactly **50 bytes per input
  row** to standard output — a hard standard-output parity requirement, not
  diagnostic noise.
- The category-balance walk is ascending on
  `(account id, transaction type code, transaction category code)`, which is what
  makes a control break on account id sufficient to detect the account boundary.

### 4.9 The fee paragraph is an empty stub

`app/cbl/CBACT04C.cbl:518-520` is, in full:

```cobol
       1400-COMPUTE-FEES.
      * To be implemented
           EXIT.
```

Trade-offs: fee computation is **unimplemented in the baseline**, even though the
job's own header comment at `app/jcl/INTCALC.jcl:20` says it computes "interest
and fees". The target preserves it as an explicit, documented no-op extension
point and adds no fee logic. Implementing fees would be the more useful-looking
choice and would break parity immediately, because every account with a fee-liable
balance would then differ from its golden master; leaving the seam visible costs
one empty method and keeps the comparison meaningful.

### 4.10 Two numeric regimes, two codecs

The master files this module reads carry money as **zoned decimal with sign
overpunch**, decoded by `ZonedDecimalCodec` in **EBCDIC sign mode**. Assumptions:
[`tests/README.md`](../../tests/README.md) §5.2 records that `-fsign=EBCDIC` is
required and that the default ASCII convention **silently corrupts negative
balances** — silently being the operative word, since the failure produces
plausible positive numbers rather than an error.

The export and import record carries money as **packed decimal**, decoded by
`PackedDecimalCodec`. `app/cpy/CVEXPORT.cpy` is 103 lines, declares its total
length as **500 bytes** at `:5` (composed as 1 + 26 + 4 + 4 + 5 + 460), and is
**the only base copybook in the repository that uses `COMP` or `COMP-3`** — a
search across `app/cpy/*.cpy` matches nothing else. It carries a full `REDEFINES`
tree of alternative record layouts over the same 460-byte data area.

**The mixed-usage minefield at `app/cpy/CVEXPORT.cpy:50-57` is the canonical test
case for the three codec paths.** The identical picture `S9(10)V99` appears in
**three different usages inside one record**: packed at `:50` and `:52` (seven
bytes), display-zoned at `:51` and `:56` (twelve bytes), and binary at `:57`
(eight bytes). Assumptions: a decoder that infers width from the picture alone
mis-reads two of the three and, because each wrong width shifts every subsequent
field, corrupts the remainder of the record rather than just that field.

Both codecs come from `common-lib` and are **never re-declared locally**, per
transformation rule T2 — the direct analogue of compiling every COBOL program
against one copybook include path.

Money is exact fixed point end to end: `NUMERIC(p,2)` in SQL, `BigDecimal` at
scale 2 in Java, and a JSON **string** on any wire. Alternatives Considered:
`float`, `double`, and a bare JSON number are forbidden in the money path and the
prohibition is enforced by an ArchUnit assertion rather than by review. A JSON
number is parsed into an IEEE-754 double by most clients, which destroys
exactness at the boundary a user actually reads.

### 4.11 Record lengths, verified

Each length below was confirmed both from the copybook and from the reading
program's file description, and the copybook totals were recomputed field by
field:

| Copybook | Record | Length | Verified from |
|---|---|---|---|
| `app/cpy/CVTRA06Y.cpy` | daily transaction | **350** | Header `RECLN = 350`; file description in `CBTRN02C` (16 + 334); the daily dataset in `POSTTRAN.jcl` |
| `app/cpy/CVTRA05Y.cpy` | `TRAN-RECORD` | **350** | Header `RECLN = 350`; `app/cbl/CBACT04C.cbl:89-92` (16 + 334); `INTCALC.jcl:39` `LRECL=350` |
| `app/cpy/CVTRA01Y.cpy` | `TRAN-CAT-BAL-RECORD` | **50** | Header `RECLN = 50`; 11 + 2 + 4 + 11 + 22; file description `CBACT04C:61-67` |
| `app/cpy/CVTRA02Y.cpy` | `DIS-GROUP-RECORD` | **50** | Header `RECLN = 50`; 10 + 2 + 4 + 6 + 28; file description `CBACT04C:76-82` |
| `app/cpy/CVACT01Y.cpy` | `ACCOUNT-RECORD` | **300** | Header `RECLN 300`; file description `CBACT04C:84-87` (11 + 289) |
| `app/cpy/CVACT03Y.cpy` | card cross-reference | **50** | Header `RECLN 50`; file description `CBACT04C:69-74` (16 + 9 + 11 + 14) |
| `app/cpy/CVEXPORT.cpy` | `EXPORT-RECORD` | **500** | Self-declared at `:5`; 1 + 26 + 4 + 4 + 5 + 460 |
| — | `REJECT-RECORD` | **430** | `CBTRN02C:176-178` and `:81-84`; `POSTTRAN.jcl:36`; the golden master |

**The two jobs read the cross-reference through different access paths, and the
JCL proves it.** `app/jcl/INTCALC.jcl:31-32` mounts a second data definition,
`XREFFIL1`, on `AWS.M2.CARDDEMO.CARDXREF.VSAM.AIX.PATH` — the **alternate index
by account id** — in addition to the base cluster at `:29-30`. Posting mounts only
the base cluster, keyed by card number, at `app/jcl/POSTTRAN.jcl:32-33`.
Assumptions: interest accrual looks the cross-reference up **by account id**
because its driving file is ordered by account, while posting looks it up **by
card number** because its driving record carries a card number. In the target the
account-id path is a secondary-index query, and the index itself is owned by
`account-service`.

## 5. The `batch` schema

`V1__batch.sql` creates the durable step ledger and the Spring Batch job-history
tables, and nothing else.

### 5.1 `batch.batch_run` — the step ledger

`batch_run(run_id, step_name, status, started_at, finished_at, return_code)` over
a surrogate key, **unique on `(run_id, step_name)`**, so an orchestrator redrive
of an already-completed step is a no-op rather than a duplicate row. The natural
key is not the identity because both of its parts are externally supplied
strings.

Three check constraints keep the ledger from recording an impossible run, and
each earns its place:

- `status` is one of `STARTED`, `COMPLETED`, `FAILED`, stored by name rather than
  by ordinal so that reordering the Java constants cannot redefine stored rows.
- `return_code` is null, `0`, `4`, or `8` and above — the same three tiers as §3.1
  and no other value.
- The lifecycle constraint ties them together: `STARTED` carries neither an end
  time nor a code; `COMPLETED` carries an end time and a code of `0` or `4`;
  `FAILED` carries an end time and either a code of `8` or above **or no code at
  all**. Assumptions: only `FAILED` admits a null code, because a killed container
  reaches a terminal state without ever publishing one, and a row must not be able
  to claim it completed and hard-failed at once.

The Spring Batch `JobRepository` tables — job instance, job execution, execution
parameters, step execution, and the two execution-context tables, with their
sequences — are created in the same `batch` schema, so job history and the step
ledger are backed up and restored as one unit.

### 5.2 Restart is an improvement, not a port

**No baseline checkpoint contract exists.** The only `RESTART=` in the entire tree
is **commented out**, at `app/jcl/DEFGDGD.jcl:2` (`//*  RESTART=STEP30`), and
there is **no `CHKPT=` anywhere** in the 38 members of `app/jcl` — a search
matches zero files. Refactoring Rationale: a failed step therefore had to be
resubmitted by hand, from the top, with no durable record of what had already
completed. Per-state retry, redrive, and the `batch_run` idempotency key are
therefore **a strict improvement, not a port of an existing capability**, and
saying so in exactly those terms matters — describing them as a migration of
mainframe restart would imply a baseline behaviour to be faithful to, and there
is none.

### 5.3 Migration scope, and the grants this module does not create

`V1__batch.sql` creates **only** `batch` objects. **This module must not create,
alter, or seed `ledger.*`, `account.*`, or `reference.*` tables.** It writes rows
into two of them under grant; the schema definitions belong to the contexts that
own them, and Flyway here is configured with `create-schemas` disabled and its
locations pointed at this module's migrations alone.

The grants, created by
[`data-migration/sql/V0__schemas_and_roles.sql`](../../data-migration/sql/V0__schemas_and_roles.sql)
for the batch database role, are narrower than "write access to two schemas":

| Schema | Authority | Why this module needs it |
|---|---|---|
| `batch` | Owner | The step ledger and job history |
| `ledger` | `SELECT`, `INSERT`, `UPDATE` on tables | Posts transactions and maintains category balances |
| `account` | `SELECT` on the schema; `UPDATE` on `account.accounts` **by name only** | Applies the posting balance update and the interest flush |
| `card` | `SELECT` only | Reads card data during validation |
| `reference` | `SELECT` only | Reads disclosure groups for the interest rate |

Assumptions: nothing in that table grants `DELETE` anywhere, and the only
`UPDATE` outside `ledger` is on one named table. The runtime `search_path` spans
`batch`, `ledger`, `account`, `card`, and `reference`, and the cross-schema
mappings name their schema explicitly on the mapping rather than relying on that
path, so a write into a schema this module has no right to fails as a permission
error naming the table rather than resolving somewhere unexpected.

### 5.4 Flyway needs two coordinates, not one

Migration is activated by the Spring Boot Flyway starter — which is what puts the
Flyway autoconfiguration on the classpath, since Boot 4 splits autoconfiguration
into one module per technology — **plus the mandatory
`flyway-database-postgresql` companion**. Assumptions: from Flyway 10 onward,
per-database support was moved out of the core artifact into one module per
database, and the starter pulls no database module of its own. The build
therefore compiles, packages, and passes every test without that companion and
then fails at first container start with a missing-database-plugin error. That
build-green, runtime-broken asymmetry is why the companion is declared explicitly
and adjacent to the starter: omitting it produces a defect **no build gate in this
repository can catch**. Both versions come from the parent POM, so they cannot
drift apart.

## 6. Dataset generations

**There are ten generation-dataset bases, not six, and every one is defined with
`LIMIT(5)`.** Six live in one member and are easy to find; the other four are
spread across two more, which is how a count of six happens.

| # | Generation base | Defined at |
|---|---|---|
| 1 | `AWS.M2.CARDDEMO.TRANSACT.BKUP` | `app/jcl/DEFGDGB.jcl:25-26` |
| 2 | `AWS.M2.CARDDEMO.TRANSACT.DALY` | `app/jcl/DEFGDGB.jcl:31-32` |
| 3 | `AWS.M2.CARDDEMO.TRANREPT` | `app/jcl/DEFGDGB.jcl:37-38` |
| 4 | `AWS.M2.CARDDEMO.TCATBALF.BKUP` | `app/jcl/DEFGDGB.jcl:43-44` |
| 5 | `AWS.M2.CARDDEMO.SYSTRAN` | `app/jcl/DEFGDGB.jcl:49-50` |
| 6 | `AWS.M2.CARDDEMO.TRANSACT.COMBINED` | `app/jcl/DEFGDGB.jcl:55-56` |
| 7 | `AWS.M2.CARDDEMO.TRANTYPE.BKUP` | `app/jcl/DEFGDGD.jcl:28-29` |
| 8 | `AWS.M2.CARDDEMO.TRANCATG.PS.BKUP` | `app/jcl/DEFGDGD.jcl:51-52` |
| 9 | `AWS.M2.CARDDEMO.DISCGRP.BKUP` | `app/jcl/DEFGDGD.jcl:74-75` |
| 10 | `AWS.M2.CARDDEMO.DALYREJS` | `app/jcl/DALYREJS.jcl:24-28` |

### 6.1 The object-key convention

A generation becomes an object-key prefix of the form
`<domain>/<dataset>/dt=YYYY-MM-DD/gen=NNNN/` inside the environment's dataset
bucket, with bucket versioning enabled and a lifecycle rule retaining **five
noncurrent versions** — the direct analogue of `LIMIT(5) SCRATCH`. Under
transformation rule T6, a `(+1)` reference resolves to a new generation prefix and
a `(0)` reference to the current one.

Assumptions: the bucket **name** is deliberately absent from this document. It is
an environment-parameterised infrastructure output resolved at startup from
Parameter Store, exactly like the datasource location, so writing it here would
create a second place it is defined and would embed one environment's identity in
documentation shared by all of them.

**Ownership boundary.** `DatasetGenerationService` owns the **resolution** of a
generation reference to a prefix. The bucket, the prefixes, and the lifecycle rules
belong to `infra/modules/s3-datasets`, not here.

Two behaviours of the baseline's generation handling carry across:

- **A `(+1)` referenced twice within one job resolves to the same physical
  generation.** `app/jcl/COMBTRAN.jcl:37` writes it and `:44` reads it back inside
  the same job. Assumptions: resolution is per job, not per reference, so the
  target resolves the new generation once and reuses the resolved prefix; treating
  each reference independently would have the second step read an empty
  generation.
- **Delete-if-exists must be non-fatal and idempotent.** The baseline brackets its
  deletes with `IF MAXCC LE 08 THEN SET MAXCC = 0` at `app/jcl/TRANBKP.jcl:42`
  and `:45`, which normalises "it was not there" to success. Assumptions: the
  target's equivalent must not fail when the object is already absent, because the
  first run of any environment is exactly that case.

## 7. Retired JCL mechanisms

| Baseline mechanism | Target |
|---|---|
| `IDCAMS REPRO` | A dataset-generation export, or an ETL load step |
| DFSORT `SORT` | `ORDER BY` |
| DFSORT `INCLUDE COND=` | A SQL `WHERE` clause — never a `Choice` state (§3.5) |
| `IDCAMS BLDINDEX` | **Retired outright** |

Refactoring Rationale: `BLDINDEX` is retired rather than migrated because
PostgreSQL maintains indexes transactionally — an index is correct as soon as the
transaction that wrote the rows commits, so there is nothing left for a separate
build step to do, and running one would be pure cost. The **key** survives as an
index definition owned by `transaction-service`; only the **build step**
disappears. The baseline needed it because a VSAM alternate index is a separate
physical object that must be constructed after its base cluster is loaded, which
is also why `app/jcl/TRANBKP.jcl:43-44` deletes an alternate index alongside the
cluster it belongs to.

## 8. Divergence register

The governing principle first: `app/**` is reference-only, so it is read here and
never edited. In every case below **the baseline keeps the behaviour it has, `app/**`
is untouched, the target independently implements different behaviour, and that
difference is registered** in
[`docs/architecture/cobol-to-service-traceability.md`](../../docs/architecture/cobol-to-service-traceability.md)
rather than silently absorbed. A divergence that is implemented but not registered is
indistinguishable from a migration bug the next time the goldens disagree.

### 8.1 D-1 — the `CBEXPORT` / `CBIMPORT` file-description record key

`app/cbl/CBEXPORT.cbl:68` and `app/cbl/CBIMPORT.cbl:40` both declare
`RECORD KEY IS EXPORT-SEQUENCE-NUM` on files declared
`ORGANIZATION IS INDEXED` with `ACCESS MODE IS SEQUENTIAL` (`CBEXPORT.cbl:65-69`,
`CBIMPORT.cbl:37-41`). The named key is `EXPORT-SEQUENCE-NUM PIC 9(9) COMP`,
declared at `app/cpy/CVEXPORT.cpy:16` — a binary field that reaches the program
through working storage and **is not a field of the file record**. A file
description cannot key on a field the record does not contain, so the pair **does
not compile** under the open-source compiler and its integration test is skipped.
The copybook itself corroborates the confusion: `:3` describes the data as stored
in the **sequential** export file while both file selections say indexed.

**This single defect is the sole cause of the repository's aggregate warn-level
green state. It is not a regression and must never be reported as one.**

`ExportJob` and `ImportJob` implement the **correct** keying — the sequence number
is a field *in* the record — over the same 500-byte packed-decimal layout.
`CBIMPORT`'s own header at `:28-32` states the business purpose the import job
carries forward: a branch-migration import that reads complete customer profiles
from the export file, splits a multi-record layout into normalised target files,
validates integrity using checksums, and produces import statistics and error
reports.

### 8.2 D-3 — the final-account interest flush omission

The last account's accrued interest is never flushed, and the omission is provable
from the source rather than inferred from output.

`1000-TCATBALF-GET-NEXT` at `app/cbl/CBACT04C.cbl:325-348` handles end of file by
setting the end-of-file flag at `:340` and **never calls `1050-UPDATE-ACCOUNT`**.
The only other final-flush path is the `ELSE` branch at `:219-220`, and it is
**unreachable**: the loop is `PERFORM UNTIL END-OF-FILE = 'Y'`, which tests its
condition **before** each iteration, so once the read sets the flag mid-body the
inner guard at `:191` fails, the iteration ends, and the loop exits — the `ELSE`
can only fire if the flag were already set at the *top* of an iteration, which the
`UNTIL` test makes impossible.

**The compounding consequence.** Because `1050-UPDATE-ACCOUNT` also zeroes
`ACCT-CURR-CYC-CREDIT` and `ACCT-CURR-CYC-DEBIT` (§4.8), the last account's
**cycle buckets are never reset either** — so the defect is not merely a missing
interest posting but a cross-cycle data defect that also leaves that account's
over-limit basis carrying forward. The Java flushes the final account correctly,
which restores both the interest and the reset.

### 8.3 D-2 belongs elsewhere

For completeness: **D-2** — the two unchecked statement tables in `CBSTM03A`,
whose measured overflow thresholds are 512 same-card transactions and 51 distinct
cards — belongs to `reporting-service`, not to this module. It is named here only
so that a reader tracking the divergence numbering does not go looking for it in
the batch context.

## 9. Configuration

| Class | This module's ownership | Purpose |
|---|---|---|
| [`DataSourceConfig`](src/main/java/com/carddemo/batch/config/DataSourceConfig.java) | **Owned** | Binds the HikariCP pool from `spring.datasource.hikari`, and verifies once, before any step runs, that the connection's effective schema is the one Flyway was configured to migrate |
| `BatchConfig` | **Owned** | Chunk-oriented step definitions and their reader, processor and writer wiring. **This is the only module in the repository that owns one.** See below for why it is a separate class rather than annotations spread across the jobs |
| `SqsConfig` | **Owned** where a job publishes or consumes | Queue wiring; the listener must not auto-start, because a job runs on command rather than on arrival |
| `OpenApiConfig` | **Excluded** | See §9.1 |
| `SecurityConfig` | **Excluded** | See §9.1 |

Assumptions: the column states ownership rather than presence, and the distinction is
the load-bearing one. **Owned** means the class belongs to this module's configuration
contract and no sibling may declare it on this module's behalf; **Excluded** means it
must never appear here at all, for the reason §9.1 argues. A reader deciding where a
configuration concern goes needs those two answers, and neither changes when a file is
added. Alternatives Considered: a presence column reporting what the `config/`
directory holds. Rejected because it answers a question `ls` already answers, it says
nothing about what may not be added, and a stale row in it reads as a contradiction of
the contract rather than as an out-of-date listing.

Assumptions: `DataSourceConfig`'s verification carries more weight in this module than in
any sibling. Every other service initialises its connections with a single-schema search
path, so an ordering mistake has nothing to resolve against and fails at the first
unqualified statement. This module's path is `batch, ledger, account, reference` —
four schemas, because the posting unit of work commits the transaction, the category
balance and the account together and is kept a single ACID commit rather than fragmented
into a saga, and the interest job reads the disclosure-group rate. Refactoring Rationale:
a fifth entry, `card`, stood on the path and was removed. It was justified on the reading that `app/cbl/CBTRN01C.cbl` validates the daily feed against the card master, and that reading does not hold: the program OPENS `CARD-FILE` at `app/cbl/CBTRN01C.cbl:309` and never issues a READ against it, its only three reads being the daily feed at `:203`, the cross-reference at `:229` and the account at `:243`. The cross-reference it does read is `CVACT03Y`, which this module maps to `account.card_xref`, and no entity here declares a `card` schema. A reordered path would therefore still resolve an unqualified write, against
a real table in the wrong schema, so the failure would be a plausible row rather than an
error. The check compares the pool's effective schema against Flyway's separately
configured default, which proves the two settings agree instead of deriving one from the
other — if they ever disagreed this module would migrate one schema and write into
another, and each setting alone would look correct.

Assumptions: `BatchConfig` is authored, and it was authored WITH the jobs rather than
ahead of them, because a step definition has nothing to define until the jobs exist.
`job/` now holds all seven job classes §2 assigns it plus a shared dataset writer and its
package charter, and `config/BatchConfig` carries exactly what those seven share: the time
source the durable step ledger stamps its rows with, and one nested builder that wraps a
job's unit of work in a ledger-guarded step. Alternatives Considered: authoring it earlier
with the job repository and transaction manager registered in it. Rejected on two counts
that still hold: Spring Boot already auto-configures both from the data source, so the
registration would restate a framework default and then have to be kept in step with it;
and a configuration class whose stated purpose is step infrastructure, holding no step,
reads to the next author as though the steps had been considered and omitted — which is
why the class arrived with the steps and not before them. Refactoring Rationale: this
paragraph said `BatchConfig` was absent and `job/` held only its charter, which was
measured before the jobs landed; both statements are now false of the directory.

### 9.1 Why there is no `OpenApiConfig`, no `SecurityConfig`, and no API contract

Alternatives Considered: exposing an administrative endpoint to trigger a job over
HTTP was evaluated and rejected. **There is no administrative trigger endpoint.**
The only invocation path in the target architecture is a synchronous run-task call
from a state-machine state, which passes job selection and the business date as
command overrides. An HTTP trigger would add a second invocation path and, with
it, a second authorization surface to design, test, and defend, for no operational
gain — and two paths that can start the same job are two paths that can start it
twice.

Consequently there is **no `openapi/` contract directory in this module**: there
are no business endpoints to describe, and an empty contract would be worse than
none because it would advertise a surface that does not exist. Shipping empty
`OpenApiConfig` and `SecurityConfig` classes to match the other services was
considered and rejected for the same reason — a class that configures nothing
tells a reader the opposite of the truth.

The actuator health endpoint **is** exposed, health-only and with details
disabled, solely so the container health check has a probe target. Trade-offs: that
is also why the web starter is on the classpath at all, and it carries an embedded
listener into an otherwise one-shot process. A marker-file or process-only probe
was evaluated and rejected because it cannot report an unreachable datasource,
while the database-aware health indicator can. The listener cannot keep a finished
task alive, because the entry point closes the context and exits with the
translated job status.

### 9.2 Nothing is hard-coded

**No endpoint, credential, or secret appears in this module or in this document.**
The datasource location, the dataset bucket, queue destinations, and encryption key
identifiers are all infrastructure outputs, resolved at startup from Parameter
Store under an environment-scoped path. Job selection and parameterisation come
**only** from command-line arguments, which is what lets the orchestrator pass
step parameters through the container command override.

These are the variable **names** the container reads. No value of any of them
appears anywhere in the repository:

| Variable | Supplies |
|---|---|
| `SPRING_PROFILES_ACTIVE` | Which environment profile to load |
| `CARDDEMO_ENVIRONMENT` | The parameter-store path segment for this environment |
| `SPRING_DATASOURCE_URL` | The database location |
| `SPRING_DATASOURCE_USERNAME` | The batch database role |
| `SPRING_DATASOURCE_PASSWORD` | Injected from the secret store, never from a file in this repository |
| `CARDDEMO_DB_SSL_ROOT_CERT` | Path to the trust anchor for verified TLS; defaulted to the image's bundle |
| `AWS_REGION` | The region for every client |
| `CARDDEMO_BATCH_RUN_ID` | The orchestrator's execution name, published to the logging context so a night's tasks share one correlation identifier |

Assumptions: connections require verified TLS, so the trust-anchor variable is not
optional in any deployed environment — the database refuses an unencrypted
connection outright.

### 9.3 What is deliberately not on the classpath

Alternatives Considered: each library below is one a reader might reasonably
expect on the classpath, and each is absent for a stated reason.

- **No *declared* resilience library, no CardDemo *use* of one, and no circuit
  breaker.** Retry lives in the Spring Framework core that arrives with the
  parent. The annotation attribute is **`maxRetries`** — total attempts are one
  plus that value — and the enabler is **`@EnableResilientMethods`**, *not*
  `@EnableRetry`; both are easy to get wrong from memory. The published
  third-party resilience integration targets the previous Boot major version, and
  the older retry project is superseded by the in-framework support, so adding
  either would layer a second retry mechanism over the one already present.
  **The wording distinguishes declaration from presence deliberately**, because
  this module is one of the four where the older retry project IS on the compile
  classpath: `spring-cloud-aws-starter-sqs` 4.1.0 → `spring-cloud-aws-sqs` 4.1.0 →
  `org.springframework.retry:spring-retry` 2.0.13, which that integration uses for
  its own listener-container polling back-off and which therefore cannot be
  excluded. What is guaranteed instead is that no `com.carddemo` class depends on
  it, and rule **A5** of the shared layering gate fails this module's build if one
  does. Durable retry comes from per-state retry in the invoking
  state machine and from queue redelivery into a **dead-letter queue at five
  receives**, both of which survive a task dying outright where an in-process retry
  cannot. A circuit breaker is omitted because this module makes no synchronous
  call to another service that could be broken open — it would add a failure mode
  without removing one.
- **No annotation-driven accessor generator.** Its generated members carry no
  Javadoc, which would make this module structurally incapable of passing the
  inherited documentation gate (§11). Java records with explicit constructors give
  the same brevity while leaving every member documentable.
- **No generated bean mapper.** The record-to-transfer-object mapping in this
  repository is not mechanical: it drops `FILLER` padding, masks the primary
  account number to its last four digits, suppresses the card verification value
  entirely, encrypts identifiers, and names two fields the baseline spells
  differently.
  Each of those needs a justification at the mapping site, and a generated mapper
  has nowhere to put one.
- **No cache, no streaming platform, no read replica.** The baseline has no cache
  tier, so adding one would introduce a coherence question functional parity does
  not ask.
- **No distributed-transaction coordinator, saga framework, or compensating-
  reversal machinery** — §4.2 gives the reason in full.

## 10. Build, run, and test

### 10.1 Build and test

```bash
# WHAT: build common-lib and this module, run the unit tests through Surefire,
#       reach the Failsafe integration tier, and package the executable jar the
#       container image copies.
# WHY : Assumptions: the integration tier is reached but this module contributes
#       nothing to it. Failsafe is bound in the parent POM and executes for both
#       selected modules, and neither `common-lib` nor `batch-service` holds an
#       `*IT` class, so it reports zero tests here. That matters because §10.4's
#       container-backed atomicity assertion is NOT proven by this command --
#       writing that the command "runs the repository integration tests" would let
#       a reader believe otherwise. The reactor's integration tests are
#       `services/common-lib/src/test/java/com/carddemo/common/CardDemoCommonAutoConfigurationIT.java`
#       and
#       `services/transaction-service/src/test/java/com/carddemo/transaction/repository/TransactionRepositoryIT.java`;
#       the second is the working reference for the shape this module's own `*IT`
#       takes.
# WHY : Assumptions: common-lib supplies both the main jar and the test artifact
#       carrying the shared architecture rules, so it must be built first; -am
#       builds it from the reactor rather than resolving a published version.
#       This is the form to use while working inside this module - see the note
#       below for why a module-scoped build is the form to reach for.
mvn -B -f services/pom.xml -pl common-lib,batch-service -am clean verify
```

**The whole-reactor build completes.** `mvn -B -f services/pom.xml clean verify`
succeeds for all ten modules, and it is what
[`.github/workflows/services-ci.yml`](../../.github/workflows/services-ci.yml) runs as
its authoritative gate.

Assumptions: the module-scoped command above is offered for iteration speed and for
nothing else — the whole-reactor form is not broken and needs no workaround. Every
service declares a Spring Boot entry point, so `repackage` produces an executable jar
for each of the eight and the reactor reaches `verify` for all ten modules.
Trade-offs: the module-scoped form builds two modules instead of ten, so it is faster
and its output is easier to read, but it cannot detect a change here that breaks a
sibling. Run the whole-reactor form before pushing; CI runs it regardless.

```bash
# WHAT: run the documentation gate alone against this module, without compiling.
# WHY : Assumptions: the gate is bound to validate, which precedes compile, so it
#       reports missing or incomplete Javadoc in seconds rather than after a full
#       build. Section 11 describes what it enforces.
mvn -B -f services/batch-service/pom.xml validate
```

### 10.2 Build the image

```bash
# WHAT: build the non-root batch image, from the repository root.
# WHY : Assumptions: the context MUST be the repository root, not services/. The
#       Dockerfile copies services/pom.xml, services/common-lib/**, and
#       config/checkstyle — it compiles the unpublished sibling from source and
#       the inherited documentation gate reads a directory outside the reactor, so
#       a narrower context cannot see everything the build needs.
docker build --file services/batch-service/Dockerfile \
  --tag carddemo-batch-service:validation .
```

### 10.3 Run one job locally

```bash
# WHAT: run a single posting job against a reachable database, with the business
#       date injected rather than read from the clock.
# WHY : Assumptions: every setting arrives from the environment (§9.2) and no
#       value of any of them is committed. Both arguments are required and
#       neither is defaulted, so this command line is the minimum that runs.
java -jar services/batch-service/target/batch-service-1.0.0-SNAPSHOT.jar \
  --job=post-transactions --business-date=2022-07-18
```

```bash
# WHAT: run the interest job for the same night as the committed parity fixture.
# WHY : Assumptions: the compact ten-character token is forwarded verbatim, so
#       this run reproduces the transaction identifiers beginning 2022071800000001
#       that app/jcl/INTCALC.jcl:22 produces with PARM='2022071800'. Passing the
#       ISO form here would yield different identifiers - see section 4.6.
java -jar services/batch-service/target/batch-service-1.0.0-SNAPSHOT.jar \
  --job=calculate-interest --business-date=2022071800
```

### 10.4 What the tests must prove

Each test below pins one rule from §4, and the rule it pins is what makes it worth
running rather than its coverage contribution.

**This table is a specification, not an inventory.** Each row states a test this
module's contract requires and the rule that test pins; a row's presence here is not a
claim that the test exists, and nothing in §10 proves the container-backed atomicity
assertion in the last row. Assumptions: the table is written as required coverage
because that is the durable half — the rule each test must pin comes from §4 and from
the baseline it cites, and it does not change when the test is written. Trade-offs: a
reader cannot learn from this table which of the seven currently run, and must ask the
module's test tree instead. That is the intended direction, because the tree answers it
exactly and a column here would answer it only until the next commit.

| Required test | The rule it pins |
|---|---|
| `PostingValidationServiceTest` | All four reject reasons with their exact message text, **and both inclusive boundaries** — exactly at the credit limit posts, one cent over rejects `102`; equal to the expiration date posts, one day past rejects `103` |
| The exit-status test | A run with rejects reports `4` **and emits the counter line verbatim**, two spaces before the colon; a clean run reports `0` |
| `CategoryBalanceServiceTest` | The create path and the update path **separately**, so an upsert that collapsed them would fail |
| `InterestCalculationServiceTest` | The multiply-before-divide result **to the cent**, the truncating reduction under `RoundingMode.DOWN` on a datum where half up would differ, per-row truncation rather than truncation of the sum, the `DEFAULT` fallback carrying type and category through, a missing `DEFAULT` row failing hard, and the final-account flush that the baseline does not reach |
| `ExportJob` / `ImportJob` round trip | The 500-byte packed-decimal record survives a write-then-read unchanged, including the three usages of one picture at `app/cpy/CVEXPORT.cpy:50-57` |
| The business-date test | The date comes from a **parameter**: injecting a fixed date twice produces byte-identical output, and no code path reads a clock for it |
| `*RepositoryIT` | Against a real PostgreSQL container, the three-write unit of work **commits atomically and rolls back atomically**, across `ledger.*` and `account.*`, in **one** transaction |

Trade-offs: the repository test pays a real container start rather than using an
in-memory engine, deliberately. The property under test is multi-schema
search-path resolution, real grant enforcement, and real transactional semantics —
none of which an in-memory substitute reproduces faithfully, so a test that passed
against one would prove nothing about the thing that matters. This is settled
rather than open: the sibling `TransactionRepositoryIT` in `transaction-service`
runs on a real PostgreSQL container under `@ServiceConnection` and applies the
module's Flyway migration inside it, so the mechanism is demonstrated and this
module's `*IT` follows it rather than choosing again.

**Three assertions must be demonstrably green before this module is considered
done:**

1. The interest calculation matches the COBOL **to the cent** on a multi-input
   fixture set.
2. The posting unit of work **commits and rolls back atomically** across both
   schemas in one transaction.
3. A run with rejects exits **`4`** while emitting the counter line **verbatim**,
   and a clean run exits **`0`**.

### 10.5 The parity method

Run the COBOL pipeline to produce the golden outputs; run the equivalent Java job
over migrated data; compare **after the same timestamp normalisation**. Four things
are compared: the reject stream, the posted transaction records, the updated
masters, and the return code. Assumptions: normalisation is required because both
programs stamp records from the wall clock (§4.6), so a byte comparison without it
fails on every run for a reason that has nothing to do with correctness. Where the
target intentionally differs — D-1 and D-3 in §8 — the difference is **registered,
not absorbed**.

New Java tests are **strictly additive**. `tests/**` and `scripts/**` are
reference: never modified, never re-pinned. Their aggregate warn-level return code
is the documented green state, and §8.1 gives its single cause.

## 11. The Checkstyle documentation gate

The gate is bound to Maven **`validate`** by `services/pom.xml`, under the
execution id `checkstyle-documentation-gate`, reading
[`config/checkstyle/checkstyle.xml`](../../config/checkstyle/checkstyle.xml) with
`failOnViolation` true and `violationSeverity` at warning. Assumptions: binding it
to `validate` rather than to a verification phase means it runs on **every local
build**, before compilation, so a documentation failure is found by the developer
who caused it rather than by continuous integration. `includeTestSourceDirectory`
is enabled, so test sources are held to the same standard as main sources.

What that means in practice for code in this module:

- **`package-info.java` is required in every package** — enforced twice over, once
  by the file-system check and once inside the tree walker.
- **Annotation-driven classes get no exemption.** The allowed-annotations list is
  empty, so `BatchApplication` and every `config/*Config.java` needs full Javadoc
  despite being configuration rather than logic.
- **Every record component needs an `@param`.** The type check runs with
  `allowMissingParamTags` false, which applies to record components as well as to
  type parameters.
- **Every method needs its returns and throws documented.** The method check runs
  with `allowMissingReturnTag` false and `validateThrows` true, and at-clause
  descriptions may not be empty.

Suppressions are chartered for **generated-source and test-fixture suppressions
only**. **Suppressing `src/main/java/**` — and above all `service/**`, where the
transcribed business rules live — is absolutely prohibited.** Never add
`-Dcheckstyle.skip`, a plugin-level skip flag, `failOnViolation=false`, a
trailing `|| true`, a continue-on-error step, or any other return-code tolerance.
Assumptions: this gate is the mechanism by which Rule 1 is machine-checked rather
than aspirational, and the business-rule services are precisely the files whose
rationale a future reader will most need.

## 12. Baseline typos and artifacts

Documented so that a reader does not mistake a faithful transcription for a
transcription error, or a baseline artifact for a new defect.

### 12.1 Two field names the target spells differently from the baseline

The baseline spells the field `EXPIRAION` in two record layouts and `app/**` is
untouched, so that spelling stands. The target independently names both columns
`expiration_date`: `ACCT-EXPIRAION-DATE` becomes `accounts.expiration_date` and
`CARD-EXPIRAION-DATE` becomes `cards.expiration_date`. Each naming divergence is
registered in
[`docs/architecture/data-model-and-schema-mapping.md`](../../docs/architecture/data-model-and-schema-mapping.md)
so the lineage is never ambiguous. Assumptions: the baseline spelling is what appears
in the COBOL this module transcribes — `app/cbl/CBTRN02C.cbl:414` tests
`ACCT-EXPIRAION-DATE` directly — and it propagates into the export layout at
`app/cpy/CVEXPORT.cpy:54`, so both spellings will be encountered while reading.

### 12.2 Baseline artifacts observed while reading

| Artifact | Where | Why it is called out |
|---|---|---|
| `FD-FD-TRAN-CAT-DATA` | `app/cbl/CBACT04C.cbl:67`, and the same doubled prefix in the posting program | It is a doubled prefix, not a distinct field |
| **`FD-ACCT-DATA` declared twice in one program** | `app/cbl/CBACT04C.cbl:87` as `X(289)` and `:92` as `X(334)` | The two are different fields under different file descriptions. A naive flat symbol table **will collide**, and the collision silently picks one width |
| `FD-TRANS-ID` versus `FD-TRAN-ID` | `app/cbl/CBACT04C.cbl:91` against the posting program's spelling | Two programs, two spellings, one concept |
| `ERROR OPENING DALY REJECTS FILE` | `app/cbl/CBACT04C.cbl:281`, inside `0200-DISCGRP-OPEN` (which begins at `:270`) | A copy-paste artifact: a disclosure-group open reports a daily-rejects failure. The message is misleading in the baseline and is **not** reproduced verbatim for that path |
| `TRANSACATION` | `app/jcl/TRANBKP.jcl:35` and `:49` | A comment-only misspelling |
| A sequence field in columns 73-80 | For example `JOB05067` on `app/jcl/DEFGDGD.jcl:1` and `:2` | Any parser reading these members **must ignore** columns 73-80, or it will read the sequence number as part of the statement |

### 12.3 Interest accrual has no soft-failure path

`CBACT04C` contains **no reject stream and no return-code statement at all** —
searches for a rejects file description and for `RETURN-CODE` both match zero
occurrences in the file. Assumptions: any I/O anomaly there abends rather than
degrading, so **only `CBTRN02C` produces the warn tier**. A migrated interest job
that invented a soft-failure path would let a night continue that the baseline
stops.

## 13. Known limitations and cross-service preconditions

Stated explicitly, following the house precedent that a runnable claim must not
hide a blocked dependency.

### 13.1 Limitations

- **`PreflightDailyTransactionsJob` has no baseline JCL driver** (§2), so its step
  ordering in the nightly chain is derived from the architecture rather than
  transcribed from a job.
- **`CBACT04C` and `CBTRN01C` publish no return code of their own** (§12.3), so
  their migrated steps report only clean success or hard failure.
- **The seven job beans, the business-rule services, and `BatchConfig` described in
  §2, §4, and §9 are the module's target contract.** The process entry point,
  `DataSourceConfig`, the closed set of job tokens, the business-date validation, the
  cross-schema domain mappings, and the `batch` schema migration are authored; no
  `Job` bean is registered yet, so once the §13.2 preconditions hold an invocation
  with a valid token reaches a by-name resolution failure that reports both the
  requested token and the registry's actual contents. Before those preconditions
  hold it fails earlier, at startup mapping validation, naming the missing table.
  Argument parsing, validation, and the usage diagnostic run without a database, a
  credential, or a job bean at all. This is recorded
  here because the token set is an orchestration contract that the state machine
  and each job bean are authored against, and a reader has to be able to tell a
  not-yet-registered bean from a misspelled token.
- **The shared ArchUnit rule set** referenced in §4.2 and §4.10 is authored once in
  `common-lib` and scanned into this module's test run by the aggregator's
  architecture-rules execution, against this module's own classes. Maven passes a
  module's main classes to its consumers and never its test classes, which is why
  `common-lib` publishes the rules as a test artifact and this module declares that
  artifact explicitly — without it, no rule would ever be evaluated against
  `com.carddemo.batch`.

### 13.2 Cross-service preconditions

None of these belong to this module, and all of them must hold before it runs:

| Precondition | Owner | Symptom if absent |
|---|---|---|
| The seventeen `'DEFAULT'` disclosure-group rows are seeded | `reference-service` | The interest job **abends** (§4.5) — a non-local defect |
| The `ledger.*` schema exists | `transaction-service` | Startup mapping validation fails naming the missing table |
| The `account.*` schema exists | `account-service` | Startup mapping validation fails naming the missing table |
| The cross-schema grants are applied | `data-migration/sql/V0__schemas_and_roles.sql` | A permission error on the first cross-schema write |
| The dataset bucket, prefixes, and lifecycle rules exist | `infra/modules/s3-datasets` | Generation resolution fails in the backup, combine, and export jobs |

## 14. References

- [`docs/CODE_DOCUMENTATION_STANDARD.md`](../../docs/CODE_DOCUMENTATION_STANDARD.md)
  — the governing documentation convention, including the idiom in §15.1
- [`docs/architecture/cobol-to-service-traceability.md`](../../docs/architecture/cobol-to-service-traceability.md)
  — the authoritative divergence register behind §8
- [`docs/architecture/batch-orchestration.md`](../../docs/architecture/batch-orchestration.md)
  — job-to-state mapping, condition-code inversion, and the generation families
- [`docs/architecture/data-model-and-schema-mapping.md`](../../docs/architecture/data-model-and-schema-mapping.md)
  — field-by-field mapping tables and the corrected field names
- [`docs/adr/ADR-005-batch-orchestration.md`](../../docs/adr/ADR-005-batch-orchestration.md)
  — why the nightly chain is expressed as a state machine over container tasks
- [`docs/adr/ADR-003-datastore-targets.md`](../../docs/adr/ADR-003-datastore-targets.md)
  — why every record datastore is one relational cluster
- [`docs/runbooks/batch-operations.md`](../../docs/runbooks/batch-operations.md)
  — deployed execution, inspection, and redrive
- [`MIGRATION_README.md`](../../MIGRATION_README.md) — build, deploy, run, migrate,
  validate, roll back
- [`tests/README.md`](../../tests/README.md) — the parity oracle, its three layers,
  its known limitations, and the business rules it asserts verbatim
- [`services/common-lib/README.md`](../common-lib/README.md) — the shared codecs,
  money type, and architecture rules this module consumes

## 15. Documented rationale register

Rule 1, Explainability, is the project's single user-specified rule. Applied to a
prose document it is a content obligation: **this README is where every mandated
rationale is stated once, in narrative form, with its citation**, so that a reader
who never opens a `.java` file still learns *why*. The register below is the index
of those decisions; each row points at the section that argues it.

### 15.1 The house idiom

[`docs/CODE_DOCUMENTATION_STANDARD.md`](../../docs/CODE_DOCUMENTATION_STANDARD.md)
is the governing written convention. In code, purpose belongs in the
language-standard documentation block — the Javadoc, or, for a language that has
none, the file-header comment block — and an inline comment carries a canonical
rationale label and nothing else. The paired what-and-why form is written with the
comment marker of each language — two slashes in Java, two hyphens in SQL, a hash
in the Dockerfile and in the fenced command blocks of this document. Assumptions:
the what half belongs to a file header or to a command block and **never** to a
statement, because a comment restating the statement beneath it is the first
forbidden pattern the convention names. The label widths are deliberate — `WHAT:` takes no space
before its colon and `WHY :` takes one, so both are eight characters wide and their
text starts in the same column.

The four category names are used in the one written form the standard defines:
plural, unparenthesised, colon retained, no emphasis markup. A singular
abbreviation is not an accepted variant.

### 15.2 The register

| Decision | Category | Citation | Argued in |
|---|---|---|---|
| Multiply before divide in the interest calculation; dividing first yields different cents | Alternatives Considered: | `app/cbl/CBACT04C.cbl:464-465` | §4.4 |
| Truncation for that divide, because there is no `ROUNDED` phrase, while half-up rounding remains the default elsewhere | Alternatives Considered: | `app/cbl/CBACT04C.cbl:464-465`, receiving field `:168` | §4.4 |
| The cross-schema grant keeps the posting unit of work a single ACID commit; saga and outbox-plus-compensating-reversal named and rejected because they would make partial-posting states observable and break golden-master parity outright | Alternatives Considered: | `app/cbl/CBTRN02C.cbl:424-444`, writes at `:440-442` | §4.2 |
| A return code of 4 is a warn tier rather than a failure, and the skip-predicate to run-predicate inversion is spelled out | Refactoring Rationale: | producer `app/cbl/CBTRN02C.cbl:229-230`; inverted sense demonstrated at `app/jcl/TRANBKP.jcl:51` | §3.1, §3.2 |
| The counter line carries two spaces before its colon, as observable output | Assumptions: | `app/cbl/CBTRN02C.cbl:228`, against `:227` for contrast | §3.3 |
| The business date is a job parameter, never a clock read — what makes reruns reproducible | Assumptions: | `app/jcl/INTCALC.jcl:22` | §4.6 |
| The ten-character token is forwarded verbatim rather than normalised, because both the compact and ISO layouts are committed goldens | Assumptions: | `app/cbl/CBACT04C.cbl:474-480`, field `:178` | §4.6 |
| The over-limit boundary is inclusive on the accept side | Assumptions: | `app/cbl/CBTRN02C.cbl:403-413`, decisive at `:407` | §4.1 |
| The over-limit test is based on the cycle buckets, not the current balance | Assumptions: | `app/cbl/CBTRN02C.cbl:403-405` | §4.1, §4.8 |
| The expiration boundary is inclusive, and the comparison is lexical over ten ISO-ordered characters | Assumptions: | `app/cbl/CBTRN02C.cbl:414-420` | §4.1 |
| Reasons 102 and 103 evaluate in sequence, so 103 overwrites 102; only 100 short-circuits | Assumptions: | `app/cbl/CBTRN02C.cbl:372-376`, `:403-420` | §4.1 |
| The category-balance upsert keeps both branches distinguishable and separately tested | Trade-offs: | `app/cbl/CBTRN02C.cbl:467-542`, literals `:520` and `:538`, create announcement `:476-477` | §4.3 |
| The zoned codec runs in EBCDIC sign mode; the default silently corrupts negative balances | Assumptions: | `tests/README.md` §5.2 | §4.10 |
| The export and import jobs key on a field the record actually contains, where the baseline declares one it does not; the baseline declaration stands and is the sole cause of the repository's warn-level green state | Refactoring Rationale: | `app/cbl/CBEXPORT.cbl:68`, `app/cbl/CBIMPORT.cbl:40`, key declared `app/cpy/CVEXPORT.cpy:16` | §8.1 |
| The target flushes the final account's interest and performs the cycle-bucket reset with it, where the baseline does neither for that one account | Refactoring Rationale: | `app/cbl/CBACT04C.cbl:325-348` and `:219-220`; reset at `:350-356` | §8.2 |
| `batch_run` is an idempotency ledger — an improvement, not a port, because no baseline checkpoint contract exists | Refactoring Rationale: | `app/jcl/DEFGDGD.jcl:2` commented `RESTART=`; no `CHKPT=` in any of the 38 members | §5.2 |
| `IDCAMS BLDINDEX` is retired rather than migrated, because indexes are maintained transactionally | Refactoring Rationale: | Transformation rule T6; `app/jcl/TRANBKP.jcl:43-44` for the object it built | §7 |
| Ten generation families, not six, every one at `LIMIT(5)` | Assumptions: | `app/jcl/DEFGDGB.jcl:25-56`, `app/jcl/DEFGDGD.jcl:28-75`, `app/jcl/DALYREJS.jcl:24-28` | §6 |
| A `(+1)` referenced twice in one job is one physical generation | Assumptions: | `app/jcl/COMBTRAN.jcl:37` and `:44` | §6.1 |
| Delete-if-exists must be non-fatal and idempotent | Assumptions: | `app/jcl/TRANBKP.jcl:42` and `:45` | §6.1 |
| The combine step joins two pipelines, so reading one input would silently drop the night's interest transactions | Assumptions: | `app/jcl/COMBTRAN.jcl:23-26`, sort `:30` | §2.1 |
| `CBTRN01C` is migrated despite having no JCL driver | Assumptions: | A search across all 38 members of `app/jcl` matches nothing; `app/cbl/CBTRN01C.cbl:154` | §2, §13.1 |
| The empty fee paragraph is preserved as a documented no-op extension point | Trade-offs: | `app/cbl/CBACT04C.cbl:518-520`, against the header claim at `app/jcl/INTCALC.jcl:20` | §4.9 |
| Money is `BigDecimal` at scale 2 and a JSON string, never a binary floating-point type | Alternatives Considered: | Transformation rule T3; enforced by an ArchUnit assertion | §4.10 |
| The container exits with a graded numeric status while every build and test gate stays binary | Trade-offs: | `tests/README.md` §8 grades 0/2/4/8/16; `services/batch-service/Dockerfile` entry point | §3.4 |
| The runtime image tag is the headless Amazon Linux variant, because **no Alpine variant of that image exists** | Assumptions: | `services/batch-service/Dockerfile` | §10.2 |
| No accessor generator, no bean mapper, no declared resilience library and no use of the transitive one, no circuit breaker, no cache, no streaming platform | Alternatives Considered: | `services/batch-service/pom.xml`, deliberately-absent-dependencies block | §9.3 |
| The disclosure-group composite key's physical order differs from the order the COBOL moves the fields in | Assumptions: | `app/cpy/CVTRA02Y.cpy:5-8` and `app/cbl/CBACT04C.cbl:76-82`, against the moves at `:210-212` | §4.5 |
| The `'DEFAULT'` seed is one row per type-and-category pair, space-padded to ten characters, and its absence abends | Assumptions: | `app/cbl/CBACT04C.cbl:437` and `:443-460`; width at `app/cpy/CVTRA02Y.cpy:6` | §4.5 |
| Interest reads the cross-reference by account id through an alternate index while posting reads it by card number | Assumptions: | `app/jcl/INTCALC.jcl:31-32` against `app/jcl/POSTTRAN.jcl:32-33` | §4.11 |
| There is no administrative trigger endpoint, so no API-documentation class, no security class, and no contract directory ship | Alternatives Considered: | `services/batch-service/pom.xml`; no controller or contract exists under `src` | §9.1 |
| The web starter is carried solely to serve the actuator health probe | Trade-offs: | `services/batch-service/Dockerfile` health check | §9.1 |
| The PostgreSQL companion artifact is mandatory, because the starter alone fails at **runtime** | Assumptions: | `services/batch-service/pom.xml`, the two Flyway coordinates | §5.4 |
| The control break's account update also zeroes both cycle buckets — a billing-cycle reset | Assumptions: | `app/cbl/CBACT04C.cbl:352-354` | §4.8 |
| The rate gate compares numerically rather than by equality, because scale makes a zero rate unequal to zero | Assumptions: | `app/cbl/CBACT04C.cbl:214` | §4.4 |
| A malformed command line exits at 8 rather than at the oracle's usage tier of 2, which the run-predicate would accept | Alternatives Considered: | `tests/README.md` §8 defines 2 as the usage tier; gate spelled `rc <= 4` | §3.1 |
| Interest is reduced per category row and then summed, not summed and then reduced | Assumptions: | `app/cbl/CBACT04C.cbl:464-467` | §4.4 |
| The interest sequence counter increments before use and is never reset across the run | Assumptions: | `app/cbl/CBACT04C.cbl:474`, counter `:173` | §4.7 |
| A two-character literal moved into a four-digit numeric field is stored as `0005` | Assumptions: | `app/cbl/CBACT04C.cbl:483`, field `app/cpy/CVTRA05Y.cpy:7` | §4.7 |
| The repository integration test pays a real container start rather than using an in-memory engine | Trade-offs: | `app/cbl/CBTRN02C.cbl:440-442`; grants in `data-migration/sql/V0__schemas_and_roles.sql`; demonstrated by `services/transaction-service/src/test/java/com/carddemo/transaction/repository/TransactionRepositoryIT.java` | §10.4 |
| The dataset bucket name is resolved from configuration and is absent from this document | Assumptions: | `services/batch-service/src/main/resources/application.yml` | §6.1, §9.2 |

## 16. Standing prohibitions

Collected so that they are auditable in one place rather than inferred from
thirteen sections. Each is argued where it is stated.

1. **Never modify anything under `app/**`.** Cite it by path and line only. D-1 and
   D-3 stay exactly as the baseline states them; the target implements different
   behaviour and the difference is registered as a divergence (§8).
2. **Never modify or re-pin `tests/**` or `scripts/**`.** Their aggregate
   warn-level return code **is** the green state, and it follows directly from the
   D-1 baseline declaration, so reporting it as a regression wastes a debugging
   cycle on a documented, registered divergence (§8.1).
3. **`common-lib` is the only sibling dependency.** No dependency on
   `transaction-service`, `account-service`, or any other service, and no
   cross-service `domain` import; the architecture rules forbid it (§4.2).
4. **No saga, no two-phase commit, no compensating reversal** for transaction
   posting (§4.2).
5. **No `float`, no `double`, no bare JSON number in the money path** (§4.10).
6. **No wall-clock read on the business path.** Business dates are job parameters;
   record timestamps come from an injected clock (§4.6).
7. **Never re-order the interest arithmetic** to divide before multiplying (§4.4).
8. **Never collapse the warn tier**, and never let the graded condition-code rubric
   reach a Maven, Checkstyle, Surefire, Failsafe, or JUnit gate (§3.4).
9. **Never create, alter, or seed another service's schema.** `ledger.*` and
   `account.*` are written under grant only (§5.3).
10. **No accessor generator, no bean mapper, no *declared* resilience library
    and no CardDemo use of one, no circuit breaker, no cache, no streaming
    platform, no read replica.** Retry is the framework's own: attribute
    `maxRetries`, enabler `@EnableResilientMethods`. Spring Retry is a
    compile-scoped transitive of the SQS starter in this module and may not be
    used from `com.carddemo` code, which gate rule A5 enforces (§9.3).
11. **No secret, credential, or endpoint is hard-coded** — in the module or in this
    document. Variable **names** only (§9.2).
12. **No temporal or schedule language.** This document describes the system as it
    is.
13. **No ignore file in this module.** Build output is excluded by the repository's
    root configuration.

---

<sub>Apache-2.0 · This module is additive; production `app/**` is never modified.
See [`MIGRATION_README.md`](../../MIGRATION_README.md) for the migration overview
and [`CONTRIBUTING.md`](../../CONTRIBUTING.md) for contribution conventions.</sub>
