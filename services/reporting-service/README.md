# CardDemo `reporting-service` — transaction reports and account statements

> **Purpose.** This module is the migrated **reporting and statement bounded context**: it
> assembles the 133-column transaction detail report, renders the account statement pair in
> plain text and HTML, and starts an on-demand report execution. Two audiences read this file.
> An engineer who needs to build, run or gate the module should read §6 through §8. Anyone
> about to author a class under `services/reporting-service/src/**` should read §2 through §5
> first — those sections are the contract briefing for that work, and they are written so that
> a class author needs no other document to know what belongs here, what must never be added,
> which contracts are byte-exact, and exactly what the documentation gate will demand.
>
> **Source of truth.** Four COBOL programs are the behavioural oracle, cited throughout by
> path **and** line:
> [`app/cbl/CORPT00C.cbl`](../../app/cbl/CORPT00C.cbl) — the online report-request screen
> (CICS transaction `CR00`), whose map carries **42** `DFHMDF` field definitions;
> [`app/cbl/CBTRN03C.cbl`](../../app/cbl/CBTRN03C.cbl) — the transaction detail report writer,
> whose record is 133 columns wide (`FD-REPTFILE-REC` at **L85**);
> [`app/cbl/CBSTM03A.CBL`](../../app/cbl/CBSTM03A.CBL) — the statement generator, emitting
> **two** widths, an 80-byte plain-text line (`FD-STMTFILE-REC` at **L45**) and a 100-byte
> markup line (`FD-HTMLFILE-REC` at **L47**); and
> [`app/cbl/CBSTM03B.CBL`](../../app/cbl/CBSTM03B.CBL) — its dynamically called four-file
> access subprogram (`PROCEDURE DIVISION USING LK-M03B-AREA` at **L114**). The record and
> presentation contracts are [`app/cpy/CVTRA07Y.cpy`](../../app/cpy/CVTRA07Y.cpy) (73 lines),
> [`app/cpy/COSTM01.CPY`](../../app/cpy/COSTM01.CPY),
> [`app/cpy/CVCRD01Y.cpy`](../../app/cpy/CVCRD01Y.cpy) and
> [`app/cpy-bms/CORPT00.CPY`](../../app/cpy-bms/CORPT00.CPY); the job contracts are
> [`app/jcl/TRANREPT.jcl`](../../app/jcl/TRANREPT.jcl),
> [`app/jcl/CREASTMT.JCL`](../../app/jcl/CREASTMT.JCL) and
> [`app/jcl/PRTCATBL.jcl`](../../app/jcl/PRTCATBL.jcl); the resource contract is
> [`app/csd/CARDDEMO.CSD`](../../app/csd/CARDDEMO.CSD) (505 lines). The authoritative written
> convention is
> [`docs/CODE_DOCUMENTATION_STANDARD.md`](../../docs/CODE_DOCUMENTATION_STANDARD.md); the
> authoritative field-by-field derivations and the register of every documented behavioural
> divergence are in
> [`docs/architecture/cobol-to-service-traceability.md`](../../docs/architecture/cobol-to-service-traceability.md).
> Every citation below was verified against the cited file at the cited line.
>
> **Return values — what this module delivers and who consumes it.** One bootable Spring Boot
> jar published as `com.carddemo:reporting-service`, rooted at the Java package
> `com.carddemo.reporting`, parented by `com.carddemo:carddemo-services:1.0.0-SNAPSHOT` and
> depending on exactly one sibling, `common-lib`. It serves five HTTP operations declared by
> [`src/main/resources/openapi/reporting-api.yaml`](src/main/resources/openapi/reporting-api.yaml),
> consumed by the browser client `ui/src/api/reporting.ts` through the edge; and it writes
> report and statement artifacts to object storage, consumed by the batch state machine's
> statement and report states, which invoke this same image in `--job=` mode.
>
> **Exceptions or errors — the honest boundaries.** This module owns **no** relational object
> and runs **no** migration, so an absent view or an absent grant is a defect against
> [`data-migration/sql/V0__schemas_and_roles.sql`](../../data-migration/sql/V0__schemas_and_roles.sql)
> and
> [`data-migration/sql/V1__reporting_views.sql`](../../data-migration/sql/V1__reporting_views.sql),
> never something to repair here (§3.2, §4). One divergence from the baseline is registered
> against this module and only one — **D-2**, the two unchecked statement tables (§3.1). The
> screen program `CORPT00C` has **no** golden master, because the online programs cannot run
> without a CICS runtime; the three batch programs do (§3.16).
>
> **`app/**`, `tests/**` and `scripts/**` are REFERENCE-ONLY.** They are read, cited and never
> modified. The baseline must remain byte-identical because it is the parity oracle, and the
> reference suite carries zero permitted version drift for the same reason.

---

## 1. What this module is

### 1.1 Why it exists

The baseline splits reporting across one online screen and three batch programs that share no
code but do share contracts: the screen serialises a date range into job text, one batch
program formats a fixed-width report from a copybook layout, and a statement generator drives
a called subprogram to read four files. This module is the single deployable that carries all
four responsibilities, because they read the same rows and emit the same two artifact families.

It is the only one of the nine aggregator modules that **owns nothing in the database**. That
is not an accident of sequencing and it is not a smaller version of the other services; it is
the boundary that makes this module safe to scale and safe to grant. Reporting creates no
record, so it holds no write authority anywhere (§3.2).

### 1.2 Package root and coordinates

| Property | Value |
|---|---|
| Java package root | `com.carddemo.reporting` |
| Maven `artifactId` | `reporting-service` |
| Maven parent | `com.carddemo:carddemo-services:1.0.0-SNAPSHOT` ([`../pom.xml`](../pom.xml)) |
| Intra-project dependency | exactly one — [`common-lib`](../common-lib/README.md) |
| Owned database schema | **none** (§3.2) |
| Flyway migrations | **none** (§4) |

Assumptions: the parent is reached through the default `relativePath` of `../pom.xml`, so this
module must remain a direct child directory of `services/`. Moving it deeper resolves the
parent from a repository that never publishes `com.carddemo:carddemo-services`, and the
lookup can then only fail.

### 1.3 The one sibling dependency, and why cross-service imports are forbidden here

Rule T2 of the migration replaces the COBOL copybook include path with Java imports: one
former `COPY` becomes exactly one type import from the single package that owns that contract.
Money, the codecs, the error model, the keyset page envelope, the JWT role converter, the
timestamp formatter and the validation flags are consumed only from `com.carddemo.common.*`
and are never re-declared here.

Alternatives Considered: importing another service's `domain` package, which is more tempting
in this module than in any other — **every view this module reads is derived from tables that
four other services own**. It is rejected and the prohibition is an ArchUnit test rather than
a convention, because agreement with those services is reached through the **physical
database views** and their grants. A compiled dependency would add a second, silent agreement
that could drift from the first; a view that changes shape fails a query here immediately,
whereas a stale compiled contract fails somewhere later and reads as a mapping bug.

---

## 2. Contents map

```text
services/reporting-service/
├── pom.xml                     module descriptor; ONE sibling dependency, NO Flyway
├── Dockerfile                  two pinned stages; build context is the REPOSITORY ROOT
├── README.md                   this file
└── src/
    ├── main/
    │   ├── java/com/carddemo/reporting/
    │   │   ├── ReportingApplication.java    listener entry point (ECS service mode)
    │   │   ├── ReportingTask.java           the --job= task contract
    │   │   ├── ReportingTaskRunner.java     dispatches --job= runs, then exits with a status
    │   │   ├── package-info.java
    │   │   ├── api/            REST handlers; validation only, no business rule
    │   │   ├── service/        report assembly, statement rendering, execution start
    │   │   ├── repository/     read-only query surfaces over the reporting views
    │   │   ├── domain/         @Immutable view projections — NOT tables
    │   │   ├── dto/            request and response shapes
    │   │   ├── mapper/         the 133-column layout and the COBOL edit masks live HERE
    │   │   ├── config/         security, datasource, OpenAPI, object store, state machine
    │   │   ├── sink/           artifact writers for the two statement formats and the report
    │   │   └── task/           the three --job= tasks and the artifact publisher
    │   │        (every one of the nine packages above carries a package-info.java —
    │   │         the documentation gate of §8 requires it)
    │   └── resources/
    │       ├── application.yml            base profile
    │       ├── application-dev.yml        dev overrides (sizing and retention only)
    │       ├── application-prod.yml       prod overrides (sizing and retention only)
    │       └── openapi/reporting-api.yaml the published contract of record
    │
    │   ⚠ THERE IS NO src/main/resources/db/migration/ DIRECTORY, AND THERE MUST NOT BE.
    │     Its absence is a designed boundary, not an omission: this module owns no
    │     relational object, and the seven views it reads are created by an ETL step
    │     ordered AFTER the per-service migrations. A Flyway history here would order
    │     that DDL against the wrong baseline and fail whenever reporting migrated
    │     before the four services whose tables the views are built over. See §3.2 and §4.
    │
    └── test/
        ├── java/com/carddemo/reporting/
        │   ├── ReportingTaskRunnerTest.java
        │   └── api/ config/ domain/ dto/ fixtures/ mapper/ repository/ service/ task/
        │        (nine subpackages; each carries a package-info.java, because the
        │         documentation gate is NOT suppressed for test sources — §8)
        └── resources/
            ├── application-test.yml
            └── fixtures/       hand-authored records at their exact declared lengths
                ├── README.md   the per-file inventory, asserted by a contract test
                ├── acctfile.txt   300 bytes/record
                ├── custfile.txt   500 bytes/record
                ├── tcatbal.txt     50 bytes/record
                ├── trancatg.txt    60 bytes/record
                └── trantype.txt    60 bytes/record
```

Assumptions: `domain/` here holds view projections rather than entities, which is why the
package name matches the other services while its contents do not. Hibernate's `@Immutable`
marks each one, so an accidental write is rejected in the persistence layer rather than
travelling to the database to be refused there by the grant. The two controls are deliberately
redundant: the annotation gives a legible failure during development, and the grant is the one
that holds when the annotation is removed.

---

## 3. Behaviour preserved, and the reasoning behind each choice

Every subsection below is a claim a reader could reasonably question, so each carries its
reasoning under one of the four canonical labels and its citation by path and line.

**A note on the label form, because it differs from the illustration in some briefs.** The
labels below are written **bare** — `Assumptions:`, not `**Assumptions:**`. Assumptions: the
label is data, not presentation, and
[`docs/CODE_DOCUMENTATION_STANDARD.md`](../../docs/CODE_DOCUMENTATION_STANDARD.md) L232–L257
fixes exactly one permitted written form, naming `**Assumptions:**` among the spellings that
are wrong in Markdown as well as in code. The reason it gives is that a reviewer auditing this
tree against Rule 1's validation gate finds rationales by literal string search across seven
languages, since no linter parses prose in a Dockerfile, a `.tf` file or a SQL migration — one
spelling makes that search complete, and a second spelling makes it silently partial. All six
sibling service READMEs use the bare form and none uses the emphasised one, so adopting it
here keeps a single greppable spelling across the whole tree. The register is the **plural**
one throughout this file, and it is never mixed with the singular abbreviations the reference
test suite uses.

### 3.1 ⚠ Divergence D-2 — TWO independent unchecked tables, stated separately

Refactoring Rationale: [`app/cbl/CBSTM03A.CBL`](../../app/cbl/CBSTM03A.CBL) declares
`05  WS-CARD-TBL OCCURS 51 TIMES.` at **L226** with a nested
`10  WS-TRAN-TBL OCCURS 10 TIMES.` at **L228**, and separately
`05  WS-TRN-TBL-CTR OCCURS 51 TIMES.` at **L232**. Those are the only three `OCCURS` clauses
in the program and **neither table is bounds-checked**. The target replaces input-bounded
working-storage tables with collections sized by the input they represent, so there is no
fixed-size table to overrun and no bound to check.

The two **measured** thresholds are independent and arise from different tables:

| Threshold | Axis | Behaviour at the boundary | Test marker |
|---|---|---|---|
| **512** | transactions on **one card** | 512 render; the **513th** overruns the inner same-card table and **SIGSEGVs** | `F-STMT-INNER-OVERFLOW` |
| **51** | **distinct cards** | 51 render; the **52nd** overruns the `OCCURS 51` outer card table at **L226** and **SIGSEGVs** | `F-STMT-OUTER-OVERFLOW` |

⚠ **Three numbers must never be conflated:** `10` is the *declared* inner arity at **L228**;
`512` is the *measured* inner overrun; `51` is the *declared and measured* outer limit.
**There is no single "~51 transactions" limit — that figure conflates the two.** It takes the
outer table's *card* arity and reports it as a *transaction* count, which understates the
same-card limit by an order of magnitude and mislabels the fault as a transaction-volume
problem when the outer overrun is really a distinct-card problem. Anyone who writes that
figure has merged two distinct defects into one wrong sentence, and an implementer sizing a
fixture against it would size it against the wrong axis entirely.

`service/StatementService.java` therefore has **no fixed arity at all** — no array of 51, no
array of 512, and no configurable cap standing in for either. It streams. The COBOL is **not**
edited, because `app/**` is REFERENCE-ONLY, and both thresholds are registered as divergence
**D-2** in
[`docs/architecture/cobol-to-service-traceability.md`](../../docs/architecture/cobol-to-service-traceability.md),
which is where the divergence is defined; this file cites it and does not redefine it.
Corroborated by [`tests/README.md`](../../tests/README.md) §1.1, **L70 to L82**.

Assumptions: the divergence is an *absence of a limit* rather than a change to any rendered
output. Within both baseline bounds the two implementations produce the same statements, and
the reference suite's fixtures stay under **both** limits, so parity comparison is unaffected.

### 3.2 ⚠ This module owns no schema and reads through `SELECT`-only cross-schema views

Alternatives Considered: **two** designs were rejected here, and naming only one of them
would leave the more tempting one unaddressed.

1. **Owning a schema, as the other seven services do.** Rejected because reporting creates no
   record. A schema would carry `CREATE` and the ownership that comes with it, granting this
   module write authority over relations it must never modify — authority that no endpoint
   here would ever exercise and that would exist only to be misused or exploited.
2. **Pointing this module at a read replica**, which is the reflex for a reporting workload.
   Rejected on the target design's own reasoning: *"Reporting reads go to the writer through
   read-only cross-schema views; a replica adds cost and replica-lag semantics for no parity
   benefit."* A figure this module reports therefore cannot disagree with the ledger it
   reports on because of replication lag.

The readable surface is **seven read-only views** in the `reporting` schema, created by
[`data-migration/sql/V1__reporting_views.sql`](../../data-migration/sql/V1__reporting_views.sql)
as an ETL step ordered *after* the per-service migrations, and owned by a role separate from
the login role this module connects as. The login role holds `USAGE` on that one schema and
`SELECT` on those views and nothing else;
[`data-migration/sql/V0__schemas_and_roles.sql`](../../data-migration/sql/V0__schemas_and_roles.sql)
revokes its access to the four source schemas explicitly and revokes `CREATE` on `reporting`,
so a query reaching past a view fails on privilege rather than returning unmasked data.

Assumptions: the *owner* role, not the login role, is the one holding `SELECT` on the source
schemas. That split is what makes these views security barriers rather than conveniences — a
view definition runs as its owner, so the login role reads the masked projection without ever
holding a privilege on the table beneath it.

**A Flyway migration in this module is a defect.** The underlying tables and indexes belong to
their owning services. If a required view is missing, that is a `data-migration` defect to
report, not a thing to create here.

### 3.3 ⚠ The 133-column report contract, and its two DIFFERENT sign masks

Assumptions: [`app/cpy/CVTRA07Y.cpy`](../../app/cpy/CVTRA07Y.cpy) (73 lines) is normative for
every column position and every edit mask. The two masks are **not** the same:

| Band | Line | Mask |
|---|---|---|
| detail amount | **L30** | `PIC -ZZZ,ZZZ,ZZZ.ZZ` |
| page total | **L54** | `PIC +ZZZ,ZZZ,ZZZ.ZZ` |
| account total | **L60** | `PIC +ZZZ,ZZZ,ZZZ.ZZ` |
| grand total | **L66** | `PIC +ZZZ,ZZZ,ZZZ.ZZ` |

**The detail band and the total bands use different sign masks — never unify them.** Both
masks are 15 characters and land in the same span, **columns 98 to 112**, which is precisely
why the dot-leader `FILLER` widths deliberately differ: **86 / 84 / 86** compensating labels
of **11 / 13 / 11**.

| Band | Label | Label width | Leader width | Sum |
|---|---|---|---|---|
| page | `'Page Total'` (**L51–L52**) | 11 | 86 (**L53**) | 97 |
| account | `'Account Total'` (**L57–L58**) | 13 | 84 (**L59**) | 97 |
| grand | `'Grand Total'` (**L63–L64**) | 11 | 86 (**L65**) | 97 |

Never "simplify" the three leaders to one width: each pair sums to 97 so that the 15-character
mask begins at column 98 in all three bands, and equalising the leaders shifts two of the three
totals out of the column the detail amounts occupy.

**Mask semantics a default formatter gets wrong.** COBOL `Z` suppresses a leading zero to a
**blank**, not to `0`. Because **every** digit position is `Z` with no `9` anywhere —
including the cents — **zero renders as 15 blanks**, not `0.00`, not `.00` and not `+0.00`.
`Z`-suppression also blanks any comma to the **left** of the first significant digit. A fixed
`-` prints `-` for a negative value and a **blank** for a positive one; a fixed `+`
**always** prints a sign. The mask ceiling is `999,999,999.99`, which exactly fits
`TRAN-AMT PIC S9(09)V99`, so that value is the maximum-magnitude test case (§10).

Fixed-width emission goes through **`FixedWidthCodec`** from `common-lib`. Never hand-roll
padding per column: a per-column pad reproduces the widths above in a second place, and the
two copies then disagree the first time a leader changes.

Assumptions: two hard-coded `'-'` `FILLER`s at **L21** and **L25** are **hyphen joiners**
rendering `NN-Description`, so they are **data, not padding**, and must survive into the
rendered line. And `TRAN-REPORT-CAT-CD PIC 9(04)` at **L24** **preserves** leading zeros — it
prints `0001` — which is a different numeric regime from `Z` suppression (§3.12).

Assumptions: header literals carry across verbatim **including their internal spacing**.
`' to '` at **L12** has both a leading and a trailing space inside the literal, and
`'        Amount'` at **L46** has **eight** leading spaces. `TRANSACTION-HEADER-2` at **L48**
is `PIC X(133) VALUE ALL '-'`, the only explicit `133` in the copybook.

### 3.4 `states:StartExecution` replaces the `JOBS` / `INREADER` transient-data-queue submission

Refactoring Rationale: [`app/csd/CARDDEMO.CSD`](../../app/csd/CARDDEMO.CSD) **L499 to L505**
defines the submission path — `DEFINE TDQUEUE(JOBS) GROUP(CARDDEMO)` at **L499**,
`TYPE(EXTRA) DATABUFFERS(1) DDNAME(INREADER) ERROROPTION(IGNORE)` at **L501**, and
`OPENTIME(INITIAL) TYPEFILE(OUTPUT) RECORDSIZE(80)` at **L502**. Two things were wrong with
it, which is why the transport is replaced rather than reimplemented.

- `ERROROPTION(IGNORE)` means a failed write is **silently swallowed**, so a user could
  believe a report had been requested when nothing had been queued.
  [`app/cbl/CORPT00C.cbl`](../../app/cbl/CORPT00C.cbl) even carries an
  `'Unable to Write TDQ (JOBS)...'` message at **L531** that the `IGNORE` option makes
  unreachable for exactly the failure it names.
- The request was **serialised as JCL card images** rather than as data. `CORPT00C.cbl`
  **L99 to L125** builds them as 80-byte literals — using **double** quotes — including two
  cards byte-identical to [`app/jcl/TRANREPT.jcl`](../../app/jcl/TRANREPT.jcl) **L41** and
  **L42** (`"TRAN-CARD-NUM,263,16,ZD"` at **L100** and `"TRAN-PROC-DT,305,10,CH"` at
  **L102**), a step-qualified inline DD override `"//STEP10R.DATEPARM DD *"` at **L116**, and
  the JES internal-reader sentinel `"/*EOF"` at **L125**. Sort field positions were therefore
  compiled into presentation strings.

`states:StartExecution` returns an execution identifier and **fails loudly**, which is what
gives the caller a handle to observe, address or retry a run.

⚠ Cite this stanza as **L499 to L505, with `DDNAME(INREADER)` at L501**. The commonly-quoted
"L502" alone does not contain it — L502 carries `RECORDSIZE(80)`.

### 3.5 `INCLUDE COND=(...)` becomes a SQL `WHERE`, never a `Choice` state

Refactoring Rationale: [`app/jcl/TRANREPT.jcl`](../../app/jcl/TRANREPT.jcl) **L47 to L48**
declares
`INCLUDE COND=(TRAN-PROC-DT,GE,PARM-START-DATE,AND,TRAN-PROC-DT,LE,PARM-END-DATE)`. That is a
**record-selection** predicate — an inclusive `BETWEEN` on the date part only — and it becomes
a SQL `WHERE` clause.

Contrast the *other* kind of `COND`: the `COND=(0,NE)` **step** gates at
[`app/jcl/CREASTMT.JCL`](../../app/jcl/CREASTMT.JCL) **L56 / L66 / L79** are step gating,
where the JCL **skip** sense **inverts** into a state machine's **run** predicate. The two
forms share a keyword and must never be mixed up: modelling the record filter as a `Choice`
state would gate the whole step on one row's date, and modelling the step gate as a `WHERE`
would run a step that should have been skipped.

### 3.6 `CBSTM03B` is a collaborator inside `StatementService`, not its own job

Assumptions: [`app/cbl/CBSTM03B.CBL`](../../app/cbl/CBSTM03B.CBL) declares
`PROCEDURE DIVISION USING LK-M03B-AREA` at **L114**, which proves it is a dynamically called
**subprogram** rather than a main. It owns **four** file definitions — `TRNX-FILE` at **L58**,
`XREF-FILE` at **L65**, `CUST-FILE` at **L70** and `ACCT-FILE` at **L75** — and it is driven
from **13** `CALL 'CBSTM03B'` sites inside
[`app/cbl/CBSTM03A.CBL`](../../app/cbl/CBSTM03A.CBL), at **L351, L377, L401, L734, L746,
L769, L787, L805, L835, L860, L877, L893** and **L909**.

It therefore becomes a **collaborator**: not a separate batch job, and not a separate service
class with its own entry point. Giving it an entry point would publish as a unit of work
something the baseline only ever reached through a caller that owned the surrounding control
flow.

### 3.7 The date range is a request or job parameter, never a wall-clock read

Assumptions: [`app/jcl/TRANREPT.jcl`](../../app/jcl/TRANREPT.jcl) **L43 to L44** inject
`PARM-START-DATE,C'2022-01-01'` and `PARM-END-DATE,C'2022-07-06'` as SYMNAMES, and
[`app/cbl/CBTRN03C.cbl`](../../app/cbl/CBTRN03C.cbl) receives them through the `DATEPARM` DD
channel. Injected dates are what make reruns reproducible and golden comparison possible, so
`LocalDate.now()` anywhere in the report path is a defect.

**The one nuance:** the report *request* endpoint may resolve the calendar presets of §3.25
**once**, through an injectable `java.time.Clock`, because the baseline screen reads the clock
at exactly that point and nowhere else. The report *generator* never reads a clock. An
injected `Clock` is what keeps that resolution testable at a fixed instant; a direct call to
the system clock would make the preset untestable and would put a clock read inside the
generator by inheritance.

### 3.8 `ORDER BY` card number then processing date — and the determinism requirement

Assumptions: [`app/jcl/TRANREPT.jcl`](../../app/jcl/TRANREPT.jcl) **L41 to L42** declare
`TRAN-CARD-NUM,263,16,ZD` and `TRAN-PROC-DT,305,10,CH` in **one-based** positions,
independently corroborating the zero-based offsets **262** and **304** in
[`app/cpy/CVTRA05Y.cpy`](../../app/cpy/CVTRA05Y.cpy). Two unrelated sources agreeing is what
removes doubt from every offset-dependent decision downstream.

⚠ **L46 is `SORT FIELDS=(TRAN-CARD-NUM,A)` — a SINGLE key with no `EQUALS`, so the baseline's
tie order is non-deterministic.** The Java **must** add a stable secondary key, the
transaction identifier, so that golden comparison is reproducible. That is an explicit
determinism requirement rather than an embellishment: without it two runs over identical data
can order two transactions on the same card differently, and a byte comparison then fails on
a difference that is not a defect.

Contrast [`app/jcl/CREASTMT.JCL`](../../app/jcl/CREASTMT.JCL) **L53**,
`SORT FIELDS=(263,16,CH,A,1,16,CH,A)` — two keys — and
[`app/jcl/PRTCATBL.jcl`](../../app/jcl/PRTCATBL.jcl) **L52**, three keys. Both are already
deterministic, so only the report path needs the added key.

### 3.9 `IDCAMS BLDINDEX` is retired, and the index is owned by `transaction-service`

Refactoring Rationale: PostgreSQL maintains indexes transactionally, so what retires is the
**rebuild step**, not the index. The index itself is not dropped, and it is created by
`transaction-service` — never here, because this module creates no relational object at all
(§3.2). Reading the retirement of the rebuild as permission to define the index here would
put one index under two owners.

### 3.10 The card-ordered transaction view is an index plus a read-only projection, not a table

Alternatives Considered: the baseline **materialises a separate physical dataset**, sorting
the transaction master and then copying it with `IDCAMS REPRO`
([`app/jcl/CREASTMT.JCL`](../../app/jcl/CREASTMT.JCL) **L44 to L61**). The target rejects
materialisation: this module defines no table and materialises nothing. A materialised copy
would need a writable relation this module has no authority to create, a refresh step to keep
it current, and a defined staleness window — three new failure modes bought for an ordering
that an index already provides.

### 3.11 Date validation is delegated to `DateEditValidator` in `common-lib`

Refactoring Rationale: Rule T2 — one implementation, imported, never re-declared. The contract
to preserve is `CSUTLDTC-PARM` at [`app/cbl/CORPT00C.cbl`](../../app/cbl/CORPT00C.cbl)
**L129 to L136**: a 10-character date and a 10-character format go in; a **4-character
severity**, a **4-character message number** and a **61-character message** come out.

So `ApiError` carries that structured **triple**, not a bare boolean. Reducing it to a boolean
would discard the severity that distinguishes a warning from a rejection and the message
number that identifies which edit failed, leaving a caller unable to say anything more
specific than that a date was unacceptable.

### 3.12 Money is `BigDecimal` scale 2 on the API and a COBOL edit mask in the text report

Alternatives Considered: serialising money as a JSON **number**. Rejected because most clients
parse a JSON number into an IEEE-754 double, which destroys exactness at the boundary the user
actually sees. Money is therefore a JSON **string**, applied centrally by `MoneyModule` from
`common-lib`.

**Four numeric regimes coexist here and none is interchangeable with another:**

| # | Regime | Where | Leading zeros | Sign |
|---|---|---|---|---|
| i | COBOL `Z`-suppression `-ZZZ,ZZZ,ZZZ.ZZ` / `+ZZZ,ZZZ,ZZZ.ZZ` | the 133-column report (§3.3) | suppressed to **blanks** | `-` prints on negative only; `+` always prints |
| ii | COBOL unsigned `9(nn)` | `TRAN-REPORT-CAT-CD` at `CVTRA07Y.cpy` **L24** | **preserved** (`0001`) | none |
| iii | DFSORT `EDIT=(TTTTTTTTT.TT)` | `PRTCATBL.jcl` **L53 to L56** (§3.22) | **preserved** | **none emitted** |
| iv | API JSON | every response body | not applicable | carried in the string |

Regime iv is `BigDecimal` at scale 2 with `RoundingMode.HALF_UP`, serialised as a **string**.
`float`, `double` and JSON numbers are forbidden in the money path, and the prohibition is
ArchUnit-tested rather than reviewed.

### 3.13 The UPPERCASE source filenames

Assumptions: `CBSTM03A.CBL`, `CBSTM03B.CBL`, `COSTM01.CPY`, `CREASTMT.JCL` and every
`app/cpy-bms/*.CPY` — including `CORPT00.CPY` — are **uppercase on disk**, while
`CORPT00C.cbl`, `CBTRN03C.cbl`, `CVTRA07Y.cpy`, `CVCRD01Y.cpy`, `TRANREPT.jcl`,
`PRTCATBL.jcl` and `CORPT00.bms` carry a lowercase extension. A lowercase citation of an
uppercase file is a **dead reference**: it resolves to nothing on a case-sensitive filesystem
and survives review on a case-insensitive one, so the defect appears only in CI.

### 3.14 Keyset pagination, not offset

Alternatives Considered: offset pagination. Rejected because it skips and repeats rows under
concurrent inserts, changing observable behaviour that a browse by key does not.

The baseline corroborates the choice from the inside: `LK-M03B-KEY-LN PIC S9(4)` at
[`app/cbl/CBSTM03B.CBL`](../../app/cbl/CBSTM03B.CBL) **L111**, paired with
`LK-M03B-KEY PIC X(25)` at **L110**, is a **significant-key-length generic browse** — the
subprogram is told how many bytes of the supplied key are significant, which is a keyed
positioning contract and not an ordinal one. Any list surface here uses `PageResponse` from
`common-lib`.

### 3.15 The COBOL return-code rubric is quarantined

Trade-offs: the graded 0 / 2 / 4 / 8 / 16 rubric belongs **exclusively** to the COBOL parity
oracle, and it is not carried into any gate in this module. The reference suite's permanent
aggregate **RC=4** green state arises **solely** from the out-of-scope `CBEXPORT` / `CBIMPORT`
FD `RECORD KEY` defect (`app/cbl/CBEXPORT.cbl:68`, `app/cbl/CBIMPORT.cbl:40`), which is
registered as divergence **D-1** and owned by `batch-service`; it is **never** a regression
and must not be read as one.

A Java gate — Maven, Checkstyle, Surefire, Failsafe, JUnit — is **binary**. The compromise
accepted is that the two tiers report differently and a reader has to know which one they are
looking at; the alternative, teaching a Java gate to tolerate a graded code, would make every
real failure in this module indistinguishable from the baseline's known warn state.

### 3.16 `CORPT00C` has no golden master; the three batch programs do

Assumptions: [`tests/README.md`](../../tests/README.md) **L83 to L85** states it plainly —
*"**Online `CO*` CICS programs** cannot run end-to-end without a CICS runtime (absent on the
runner); only their extractable field-validation logic is unit-tested"*. So for `CORPT00C` the
**field validation** is parity-testable and the transient-data-queue submission is not.

`CBTRN03C`, `CBSTM03A` and `CBSTM03B` **are** batch programs, so the golden-master oracle does
apply to their output: run the COBOL pipeline to produce the goldens, run the Java over
migrated data, and compare after the same timestamp normalisation.

### 3.17 The four-schema `search_path` against three-schema direct evidence

Assumptions: the DD evidence from the two baseline programs does **not** cover four schemas,
and the discrepancy is recorded rather than smoothed over. The statement job joins four data
definitions at [`app/jcl/CREASTMT.JCL`](../../app/jcl/CREASTMT.JCL) **L83 to L86** (`TRNXFILE`,
`XREFFILE`, `ACCTFILE`, `CUSTFILE`); the report job joins its own at
[`app/jcl/TRANREPT.jcl`](../../app/jcl/TRANREPT.jcl) **L65 to L74**. Their union is **three**
schemas — `ledger`, `account` and `reference` — and **neither job declares a `CARDFILE` data
definition anywhere**, so neither reads the card store directly. The cross-reference is an
account-side store despite its name.

The configured `search_path` nonetheless names **four**, including `card`, because a card
attribute does surface on an endpoint here, masked, on the ledger-derived views. That is the
whole of the discrepancy. It is stated rather than reconciled because the honest version tells
a reader which schema has direct baseline evidence and which does not.

### 3.18 TWO message widths — 75 and 78 — and both are correct

Assumptions: the two widths belong to two different layers and neither is a mistake.

| Width | Field | Location | Layer |
|---|---|---|---|
| **75** | `CCARD-ERROR-MSG` | [`app/cpy/CVCRD01Y.cpy`](../../app/cpy/CVCRD01Y.cpy) **L28** | COMMAREA carrier |
| **75** | `CCARD-RETURN-MSG` | `CVCRD01Y.cpy` **L29** | COMMAREA carrier |
| **78** | `ERRMSGI` | [`app/cpy-bms/CORPT00.CPY`](../../app/cpy-bms/CORPT00.CPY) **L120** | screen field, input side |
| **78** | `ERRMSGO` | `app/cpy-bms/CORPT00.CPY` **L224** | screen field, output side |

The 75-character message is moved into a 78-character field left-justified and blank-padded.
**Do not "fix" either to match the other** — equalising them would either truncate a carrier
that the baseline fills to 75 or widen a screen field whose column span is fixed by its map.
Note the sentinel at `CVCRD01Y.cpy` **L30**: `88 CCARD-RETURN-MSG-OFF VALUE LOW-VALUES` tests
for **`LOW-VALUES`, not spaces**, so a blank-filled message is *present and empty* rather than
absent.

For completeness the repository carries further message regimes — 50 characters for the
`CSMSG01Y` constants, and 72 for the abend message at
[`app/cpy/CSMSG02Y.cpy`](../../app/cpy/CSMSG02Y.cpy) **L21 to L29** (`ABEND-CODE` `X(4)`,
`ABEND-CULPRIT` `X(8)`, `ABEND-REASON` `X(50)`, `ABEND-MSG` `X(72)`). That file is only **35**
lines long, so a citation of the abend fields to L45–L53 is a dead reference. Only the
75-character contract survives here, as a rendering constraint on the API and the UI.

### 3.19 Statements are produced in BOTH plain text and HTML

Assumptions: two widths are declared for the two outputs in
[`app/jcl/CREASTMT.JCL`](../../app/jcl/CREASTMT.JCL) — `STMTFILE` at **L87 to L91** is
`LRECL=80`, and `HTMLFILE` at **L92 to L96** is `LRECL=100`. The HTML width is **triply**
corroborated: the CREATE stanza at L92–L96; the fact that the `LRECL=80` on the **DELETE**
stanza at **L67 to L71** is inert because `IEFBR14` never writes data; and
[`app/cbl/CBSTM03A.CBL`](../../app/cbl/CBSTM03A.CBL)'s own `HTML-ADDR-LN` (**L221**),
`HTML-BSIC-LN` (**L222**) and `HTML-TRAN-LN` (**L223**), all `PIC X(100)`, under
`FD-HTMLFILE-REC PIC X(100)` at **L47**.

**HTML is the baseline's own second output format, not a redesign.** Both artifacts are
written to object storage.

### 3.20 A rerun REPLACES, never appends

Assumptions: [`app/jcl/CREASTMT.JCL`](../../app/jcl/CREASTMT.JCL) `STEP030` runs `IEFBR14`
with `DISP=(MOD,DELETE,DELETE)` at **L66 to L75**, and `STEP040` then writes with
`DISP=(NEW,CATLG,DELETE)` from **L79** onward. The delete-then-create pair is the baseline's
replace semantic and it is reproduced: a rerun of a period overwrites the artifacts it
replaces rather than accumulating a second copy. Appending instead would leave two renderings
of one period in the same location with no way to tell which is current.

### 3.21 Baseline artifact observations are documented honestly, NEVER asserted as defects

Trade-offs: [`tests/README.md`](../../tests/README.md) **L50 to L51** states the house
doctrine — *"Documented honestly here so that no runnable claim above hides a blocked feature
(a financial-enterprise auditability requirement):"*. The observations below are recorded for
that reason. The baseline is immutable, so **none of these is edited, and none is asserted as
a defect**; each is a property of reference material that an implementer would otherwise
rediscover and mistake for their own error.

| Observation | Location |
|---|---|
| duplicate step name `STEP05R` on two different steps | [`app/jcl/TRANREPT.jcl`](../../app/jcl/TRANREPT.jcl) **L23** and **L37** |
| corrupted overlapping text inside a DD continuation | [`app/jcl/CREASTMT.JCL`](../../app/jcl/CREASTMT.JCL) **L90** |
| the same HTML dataset carrying two different `LRECL` values | `CREASTMT.JCL` **L69** (80) against **L94** (100) |
| an `OUTREC` copying 328 of 350 bytes (16 + 262 + 50) | `CREASTMT.JCL` **L54** |
| single-key, non-deterministic sort | `TRANREPT.jcl` **L46** (see §3.8) |
| leftover `DISPLAY` of an accumulator | [`app/cbl/CBTRN03C.cbl`](../../app/cbl/CBTRN03C.cbl) **L199** |
| garbled comment text | [`app/jcl/PRTCATBL.jcl`](../../app/jcl/PRTCATBL.jcl) **L41** |
| stray backtick in a comment ruler | `TRANREPT.jcl` **L20** |
| a 25-byte `LK-M03B-KEY` against a 32-byte `TRNX-KEY` | [`app/cbl/CBSTM03B.CBL`](../../app/cbl/CBSTM03B.CBL) **L110** against [`app/cpy/COSTM01.CPY`](../../app/cpy/COSTM01.CPY) **L21 to L23** |
| the subtotal band's label naming a different key from the one it breaks on | see §3.31 |

The last key observation is worth its own note: `TRNX-KEY` is `TRNX-CARD-NUM PIC X(16)` plus
`TRNX-ID PIC X(16)` = **32** bytes, while the browse area that carries it is `PIC X(25)`. The
significant-key length of §3.14 is what makes the shorter area workable for a prefix browse.

### 3.22 `PRTCATBL` is a DIFFERENT report and is not part of the 133-column contract

Assumptions: [`app/jcl/PRTCATBL.jcl`](../../app/jcl/PRTCATBL.jcl) is formatted **entirely by
DFSORT with no COBOL program at all**, is **41** bytes wide, and uses
`EDIT=(TTTTTTTTT.TT)` at **L53 to L56**, which **preserves** leading zeros and emits **no
sign** — regime iii of §3.12. Its SYMNAMES at **L47 to L50** are `TRANCAT-ACCT-ID,1,11,ZD`,
`TRANCAT-TYPE-CD,12,2,CH`, `TRANCAT-CD,14,4,ZD` and `TRAN-CAT-BAL,18,11,ZD`. It consumes
`TCATBALF.BKUP(+1)` (**L39**), sorts on **three** keys (**L52**), and names the `REPROC`
internal step `PRC001` (**L32** and **L35**, under `PROC=REPROC` at **L29**).

Two consequences follow. Its 41-byte line must never be validated against the 133-column
contract — the widths are unrelated. And `TRAN-CAT-BAL` being declared **`ZD`** independently
proves the base-master money regime is **zoned decimal**, not packed, which is the fact every
codec choice on the read path rests on.

### 3.23 Plural category labels only

Assumptions: [`docs/CODE_DOCUMENTATION_STANDARD.md`](../../docs/CODE_DOCUMENTATION_STANDARD.md)
resolves the singular in-code abbreviations and the plural forms as **two registers of the same
four categories**, so that the two forms are never read as two different rules. The rules
document itself writes them plural, and this module uses the plural register **exclusively**
and never mixes registers within a file. See the note at the head of §3 for the closely
related question of emphasis markup, which the same clause settles.

### 3.24 The grand total is a sum of PAGE totals, not of transactions

Assumptions: the roll-up in [`app/cbl/CBTRN03C.cbl`](../../app/cbl/CBTRN03C.cbl) is a
three-level structure, not a flat aggregate:

| Line | Effect |
|---|---|
| **L287** | `ADD TRAN-AMT TO WS-PAGE-TOTAL` and `WS-ACCOUNT-TOTAL` — the transaction enters two accumulators |
| **L294** | `MOVE WS-PAGE-TOTAL TO REPT-PAGE-TOTAL` — the page band is emitted |
| **L297** | `ADD WS-PAGE-TOTAL TO WS-GRAND-TOTAL` — **the grand total accumulates the page total** |
| **L298** | `MOVE 0 TO WS-PAGE-TOTAL` — the page accumulator resets |

Reproduce that structure rather than a single flat `SUM` over transactions. The two agree only
while every rounding is exact at the same scale; replacing the roll-up with one aggregate
discards the intermediate page figures the report itself prints, so the printed bands would no
longer be the values the grand total was built from.

### 3.25 The calendar presets, and what they actually compute

Assumptions: both presets are resolved by the screen from the current date, and both are
reproduced exactly rather than "corrected".

- **MONTHLY** — [`app/cbl/CORPT00C.cbl`](../../app/cbl/CORPT00C.cbl) **L213 to L236**. The
  start bound is the **first day of the current month** (**L217 to L219** move the current
  year and month with a literal `'01'` day). The end bound is **computed**: **L223 to L228**
  set the day to 1 and advance the month, rolling the year at `> 12`, and then **L229 to
  L230** apply `FUNCTION DATE-OF-INTEGER(FUNCTION INTEGER-OF-DATE(...) - 1)`. Since
  `WS-CURDATE-N` redefines the `YYYYMMDD` group as `PIC 9(08)`
  ([`app/cpy/CSDAT01Y.cpy`](../../app/cpy/CSDAT01Y.cpy) **L23**, over the year, month and day
  fields at **L20 to L22**), that idiom is *first of next month, minus one day* — the **last
  day of the current month**.
- **YEARLY** — `CORPT00C.cbl` **L239 to L252**. The start bound is `YYYY-01-01` (**L243 to
  L246**) and the end bound is `YYYY-12-31`, written as the two literals `'12'` and `'31'` at
  **L250** and **L251**.

Refactoring Rationale: a "month-to-date" reading of MONTHLY — that it ends **today** — is a
**misreading of L223 to L230**, and it is recorded here because it is the natural one to reach
without following the redefinition. Both presets in fact span a **whole calendar period**, so
both end on a **future** date for most of that period. Narrowing either bound to today would
silently shrink the selected row set and break golden comparison against a baseline range.

The genuine asymmetry between the two is the **mechanism**, and it matters when transcribing
them: MONTHLY's end bound is arithmetic, which is exactly what makes it correct across 28-,
29-, 30- and 31-day months and across a December rollover, whereas YEARLY's is two constants
that need no such care.

### 3.26 Report pagination is not API pagination

Assumptions: `IF FUNCTION MOD(WS-LINE-COUNTER, WS-PAGE-SIZE) = 0` at
[`app/cbl/CBTRN03C.cbl`](../../app/cbl/CBTRN03C.cbl) **L282** is **report-level** pagination —
it decides where a page-total band and a repeated header are emitted into a printed byte
stream. It must never be conflated with the API's **keyset** paging of §3.14. They are
different mechanisms serving different consumers: one lays out a document for a reader, the
other bounds a response for a client, and binding either to the other's page size changes the
artifact the batch states publish.

### 3.27 The `CONFIRM` gate is preserved

Assumptions: [`app/cpy-bms/CORPT00.CPY`](../../app/cpy-bms/CORPT00.CPY) carries a `PIC X(1)`
confirmation field on both sides of the map — `CONFIRMI` at **L114** and `CONFIRMO` at
**L218** — so submitting a report execution is a two-step interaction in the baseline. The
target preserves an explicit-confirmation semantic before an execution starts, and does
**not** silently drop it: a declining answer is a successful request that starts nothing,
which is a different outcome from a failed request and is reported as such.

### 3.28 Six screen date components consolidate to one ISO string per bound

Trade-offs: the 3270 screen takes each bound as **six separate fields** — `2/2/4` per bound,
at [`app/cbl/CORPT00C.cbl`](../../app/cbl/CORPT00C.cbl) **L60 to L71**, with literal hyphen
separators at **L62**, **L64**, **L68** and **L70** — while the REST API takes one ISO
`YYYY-MM-DD` string per bound. The compromise accepted is a **narrower wire shape** than the
screen's: a caller cannot submit a month without a day. Per-component validation errors are
still preserved individually in the structured error array, so the narrower request does not
cost the caller the ability to see *which* component was rejected (§3.11).

### 3.29 17 named fields out of 42 `DFHMDF` definitions

Assumptions: `grep -c DFHMDF app/bms/CORPT00.bms` returns exactly **42**, and **25** of those
are unnamed literal or label fields — headings, captions and the screen furniture — which
carry no value to or from the program. The DTO therefore has **17** members, not 42. Sizing it
at 42 would publish 25 properties that no caller can set and no handler reads.

### 3.30 ⭐ The report page size is 20 lines

Assumptions: [`app/cbl/CBTRN03C.cbl`](../../app/cbl/CBTRN03C.cbl) **L131 to L132** declares
`WS-PAGE-SIZE PIC 9(03) COMP-3 VALUE 20`, and combined with the modulo test at **L282** a
page-total band fires every **20** detail lines. Any other page length produces a different
byte stream, so the value is part of the output contract rather than a display preference.

Three further facts from the same block matter when transcribing it:

- **L133** declares `WS-BLANK-LINE PIC X(133) VALUE SPACES` — a **second** explicit 133-byte
  declaration in the program. With `FD-REPTFILE-REC PIC X(133)` at **L85**,
  [`app/cpy/CVTRA07Y.cpy`](../../app/cpy/CVTRA07Y.cpy) **L48** and the `LRECL=133` DCB at
  [`app/jcl/TRANREPT.jcl`](../../app/jcl/TRANREPT.jcl) **L78**, the report width is
  corroborated **three independent ways**, so it is not an assumption at all.
- **L134 to L136** declare the page, account and grand accumulators all as `PIC S9(09)V99`,
  which maps to `NUMERIC(11,2)` and matches the mask ceiling of §3.3 exactly. A wider column
  would accept a value the mask cannot render.
- **L128** declares `WS-FIRST-TIME PIC X VALUE 'Y'`, the first-page header-suppression flag —
  the reason the first page's header is emitted by a different path from every later page's.

### 3.31 ⚠ The subtotal breaks on CARD NUMBER while the band literal reads `'Account Total'`

Assumptions: three independent lines fix this, and they disagree with each other's naming:

| Evidence | Location | What it says |
|---|---|---|
| the break variable | [`app/cbl/CBTRN03C.cbl`](../../app/cbl/CBTRN03C.cbl) **L137** | `WS-CURR-CARD-NUM PIC X(16) VALUE SPACES` — a **16-byte card number** |
| the band literal | [`app/cpy/CVTRA07Y.cpy`](../../app/cpy/CVTRA07Y.cpy) **L56 to L58** | the band is labelled `'Account Total'` |
| the sort key | [`app/jcl/TRANREPT.jcl`](../../app/jcl/TRANREPT.jcl) **L46** | sorts by `TRAN-CARD-NUM` only |

⇒ **the subtotal is per-CARD, labelled `'Account Total'`.** Implementing `GROUP BY account_id`,
or breaking on account, produces **different output from the golden master whenever one
account holds more than one card** — the bands would fall in different places and carry
different sums, while every individual detail line still looked right. The Java must therefore
break on **card number** and must keep the literal text `'Account Total'` **verbatim**, since
user-visible strings are carried character-for-character.

This is registered as an **artifact observation** of the immutable baseline — a label against
semantics mismatch (§3.21) — and never as an asserted defect. The label is data; the break is
behaviour; both carry across unchanged.

### 3.32 The `DATEPARM` record is 80 bytes with a ONE-SPACE separator, and `CBSTM03B` dispatches on six opcodes

Assumptions: [`app/cbl/CORPT00C.cbl`](../../app/cbl/CORPT00C.cbl) **L117 to L121** builds the
parameter record as 10 characters + **one space** + 10 characters + 59 blanks = **80**, which
agrees with `FD-DATEPARM-REC PIC X(80)` at
[`app/cbl/CBTRN03C.cbl`](../../app/cbl/CBTRN03C.cbl) **L88** and with `RECORDSIZE(80)` at
[`app/csd/CARDDEMO.CSD`](../../app/csd/CARDDEMO.CSD) **L502**. The separator is a single
space, so a delimiter of any other width mis-positions the end date by that difference.

Separately, [`app/cbl/CBSTM03B.CBL`](../../app/cbl/CBSTM03B.CBL) **L103 to L108** declares
**six** operation codes, and **L118**'s `EVALUATE LK-M03B-DD` proves dispatch is on the **DD
name first** and the opcode second — making it a generic four-file access layer rather than
four specialised readers:

| Opcode | Meaning | Exercised here |
|---|---|---|
| `'O'` | OPEN | yes |
| `'C'` | CLOSE | yes |
| `'R'` | READ | yes |
| `'K'` | READ-K, a keyed read | yes |
| `'W'` | WRITE | **no** |
| `'Z'` | REWRITE | **no** |

Because this module is `SELECT`-only, **only `O`, `C`, `R` and `K` are exercised here**; `W`
and `Z` belong to a write path owned elsewhere, and reproducing them here would need authority
this module deliberately does not hold (§3.2). `'K'` together with `LK-M03B-KEY-LN` is the
generic-key browse that justifies keyset paging from inside the statement path (§3.14).

---

## 4. What must NOT be added here

This section is as load-bearing as §3. Every entry is a boundary that has already been
reasoned about, and each one is reachable by a plausible, well-intentioned edit.

1. ⭐ **No schema, table, index, view, constraint or grant — and no `db/migration/` directory
   and no Flyway migration**, anywhere in this module. Assumptions: the seven views this
   module reads are built over tables that four *other* services' migrations create, and the
   views are an ETL step ordered after those migrations. A Flyway history here would order
   that DDL against the wrong baseline and fail whenever reporting migrated first (§3.2).
2. ⭐ **No read replica.** Rejected with its reasoning in §3.2: a replica adds cost and
   replica-lag semantics for no parity benefit, and it would let a figure reported here
   disagree with the ledger it reports on.
3. **No `BatchConfig`.** This module *starts* a state-machine execution; `batch-service` owns
   the Spring Batch job repository and the `batch.batch_run` step ledger. A job repository here
   would need writable tables this module has no authority to create.
4. **No `SqsConfig` unless a queue is actually used.** If none is, omit the class rather than
   shipping an empty one — an empty configuration class reads as a wiring that exists, and the
   next author extends it instead of asking whether it should exist at all.
5. **No Maven dependency on any sibling service module** — only `common-lib`. Cross-service
   `domain` imports are ArchUnit-forbidden **even though every view this module reads derives
   from another service's tables**; agreement is reached through the **physical views**, never
   through compiled code (§1.3).
6. **No Lombok, no MapStruct, no resilience library, no circuit breaker, and no Redis,
   ElastiCache, Kafka or Kinesis.** Retry lives in Spring Framework 7 core: the attribute is
   **`maxRetries`** (total attempts = 1 + value) and the enabler is
   **`@EnableResilientMethods`**, **not** `@EnableRetry`. Comment both at any use site.
   Assumptions: those two names are the ones most easily written from memory as the older
   Spring Retry spellings, and the older spellings compile against nothing here, so the error
   arrives as a missing symbol rather than as a hint about the framework version.
7. **No offset pagination** on any list surface (§3.14).
8. **No `float`, no `double`, and no JSON number** in the money path (§3.12).
9. **No wall-clock read in the report path.** Date ranges are parameters, with the single
   documented `java.time.Clock` nuance for preset resolution in the request endpoint (§3.7,
   §3.25).
10. **No hard-coded credential, endpoint, identifier or bucket name.** Primary account numbers
    are masked to their last four digits on statements and reports, and **no card verification
    value is ever returned by any endpoint**.
11. **No `.gitignore` in this module.** The repository-root file already covers `target/`, and
    a second ignore file scoped to one module would make the effective rule set depend on
    which directory a reader happened to open.
12. **No modification to `app/**`, `tests/**` or `scripts/**`**, and no re-pinning of the
    existing test suite. Those trees are the parity oracle; zero version drift is what allows
    them to keep serving as one.
13. **No temporal estimate and no schedule language** anywhere in this document.

---

## 5. The highest-risk contracts

These are the contracts where a mistake is **silent** — the code runs, the artifact is
produced, and it is wrong in a way no exception reports.

Trade-offs: this section deliberately **restates nothing and specifies nothing**. Each entry
is an index into the subsection of §3 that defines the contract, plus the one thing §3 does not
say: which way that contract fails. The cost is a second place to look; the benefit is that a
reviewer with limited attention has an ordered list of what to check first, and a contract
defined in two places cannot drift because it is only ever defined once.

### 5.1 Four numeric regimes, none interchangeable

Enumerated in the table at §3.12. Assumptions: all four regimes render a plausible-looking
number, so substituting one for another produces output that passes every eye check and fails
only a byte comparison — which is what makes this the highest risk in the module rather than
merely the most detailed contract. The two most easily confused are regime i and regime iii:
both print a decimal point and grouped digits, but `Z`-suppression blanks leading zeros and
`EDIT=(TTTTTTTTT.TT)` preserves them, so a value below 100,000,000 distinguishes them and a
value above it does not.

### 5.2 Four message-width regimes

| Width | Carrier | Cited at |
|---|---|---|
| **75** | `CCARD-ERROR-MSG` / `CCARD-RETURN-MSG`, the COMMAREA carrier | `app/cpy/CVCRD01Y.cpy` **L28**, **L29** |
| **78** | `ERRMSGI` / `ERRMSGO`, the screen field | `app/cpy-bms/CORPT00.CPY` **L120**, **L224** |
| **50** | the `CSMSG01Y` message constants | see §3.18 |
| **72** | `ABEND-MSG`, the abend message | `app/cpy/CSMSG02Y.cpy` **L21 to L29** |

Only the **75**-character contract survives here as a rendering constraint on the API and the
UI (§3.18). Assumptions: the `LOW-VALUES` sentinel at `CVCRD01Y.cpy` **L30** is part of that
contract, so **absent and blank are different states** — testing a message for spaces reports
an empty message as absent and suppresses a band the baseline would have emitted.

### 5.3 The 133-column layout

Specified in full at §3.3 and corroborated three independent ways per §3.30. Assumptions: the
column span **98 to 112** is shared by the detail mask and all three total masks, and the
**86 / 84 / 86** dot-leader widths against **11 / 13 / 11** labels are the only thing holding
that span constant — which is why the three leaders are the most dangerous thing in the layout
to tidy. Emission goes through `FixedWidthCodec`; the masks live in `mapper/` and nowhere else,
so there is exactly one place a width can be wrong.

### 5.4 Divergence D-2

Specified at §3.1: two independent thresholds, **512** transactions on one card and **51**
distinct cards, from two different unchecked tables. The target has no fixed arity, the COBOL
is unedited, and the divergence is defined in
[`docs/architecture/cobol-to-service-traceability.md`](../../docs/architecture/cobol-to-service-traceability.md).

Assumptions: the risk this contract carries is **not** an overrun — the target cannot overrun —
but a **re-introduced cap**. Any bounded buffer, fetch size, page size or list capacity added
to the statement path would recreate one of the two limits the divergence exists to remove, and
it would do so without failing anything: the statement would simply stop early, which is a
missing-rows defect rather than a crash and therefore harder to notice than the baseline fault
it replaced.

---

## 6. Build, run and test

Run every command from the **repository root**.

```bash
# WHAT: build the whole aggregator so common-lib is installed before this module
# WHY : Assumptions: the parent lists common-lib first and reporting-service last in <modules>,
#       so a full-tree build is the only ordering guaranteed to resolve the sibling dependency
mvn -f services/pom.xml clean verify
```

```bash
# WHAT: build only this module and the sibling it needs
# WHY : Trade-offs: -pl with -am rebuilds common-lib too, which is slower than a bare -pl but is
#       the only form that cannot resolve a stale installed artifact
mvn -f services/pom.xml -pl reporting-service -am clean verify
```

```bash
# WHAT: fire the Checkstyle documentation gate on its own, without compiling or testing
# WHY : Assumptions: the parent binds Checkstyle to the validate phase, which runs before compile,
#       so validate alone is sufficient to prove Rule 1 compliance
mvn -f services/reporting-service/pom.xml validate
```

```bash
# WHAT: run unit tests only (Surefire matches *Test)
# WHY : Trade-offs: skipping the Testcontainers *RepositoryIT suite trades coverage for speed during
#       inner-loop development; the full gate is mvn verify, which also runs Failsafe
mvn -f services/reporting-service/pom.xml clean test
```

```bash
# WHAT: build the container image, from the REPOSITORY ROOT
# WHY : Assumptions: the parent resolves Checkstyle from config/checkstyle/ at the repository root and
#       the build needs services/pom.xml plus services/common-lib, so the context cannot be this directory
docker build -f services/reporting-service/Dockerfile -t carddemo/reporting-service:local .
```

### 6.1 The two invocation modes of the built image

One image serves both, and the entry point forwards its arguments to select between them:

| Invocation | Behaviour |
|---|---|
| no arguments | `ReportingApplication` starts the listener and stays up — the ECS service mode |
| `--job=<name>` | `ReportingTaskRunner` takes the run, builds a context with no web application, and exits with a status the caller reads |

Assumptions: the batch statement and report states invoke the `--job=` mode through container
overrides, which is why the accepted job names and their required date options are a contract
rather than a convenience. Operating those runs is documented in
[the batch runbook](../../docs/runbooks/batch-operations.md).

---

## 7. Configuration and environment

**Names and provenance only. No value of any setting appears anywhere in this repository**, and
that is a structural property rather than an observed one: credentials are generated at
provisioning time into a managed store, the environment parameter files carry sizing and
retention values only, and deployment authenticates by short-lived federated role assumption.

Every setting arrives as an environment variable, supplied by the ECS task definition from
**Terraform outputs via AWS Systems Manager Parameter Store and AWS Secrets Manager**, because
**no service hard-codes an endpoint**.

| Setting | What it selects | Provenance |
|---|---|---|
| `SPRING_DATASOURCE_URL` | JDBC location of the cluster writer | Parameter Store, from a Terraform output |
| `SPRING_DATASOURCE_USERNAME` | the `SELECT`-only reporting login role | managed credential store |
| `SPRING_DATASOURCE_PASSWORD` | credential for that role | managed credential store |
| `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI` | the Cognito issuer whose keys validate presented tokens | Terraform output |
| `CARDDEMO_SECURITY_JWT_EXPECTED_CLIENT_ID` | the app client a presented token must name | Parameter Store |
| `CARDDEMO_REPORTING_STEP_FUNCTIONS_STATE_MACHINE_ARN` | the state machine an on-demand report execution starts (§3.4) | Parameter Store, from a Terraform output |
| `CARDDEMO_REPORTING_S3_OUTPUT_BUCKET` | where report and statement artifacts are written (§3.19) | Parameter Store, from a Terraform output |
| `CARDDEMO_REPORTING_ARTIFACT_HMAC_KEY` | keys the artifact identifier that names every statement object | managed credential store |
| `CARDDEMO_PAGINATION_CURSOR_SIGNING_KEY` | seals keyset cursors (§3.14) | managed credential store |
| `CARDDEMO_SERVER_TLS_KEYSTORE_PASSWORD` | opens the listener keystore | minted per task by the image entry point |
| KMS key identifiers | the customer-managed keys protecting the datastore, the artifact store and the credential store | Terraform outputs |

Assumptions: those ten named settings have **no fallback anywhere**, so an incomplete
environment stops the context during start-up rather than serving requests bound to nothing.
Everything else — the group names, the database trust anchor path, the API call timeout, the
environment and version tags — carries a documented default in
[`application.yml`](src/main/resources/application.yml).

Assumptions: two of those names are **not** written as `${...}` placeholders in any profile, so
a reader auditing the YAML for placeholders will not find them and could reasonably conclude
they are optional. They are not. Relaxed binding maps them onto properties declared in the
shared kernel's auto-configuration, and their absence fails differently in each case — one
stops context refresh with a missing-bean report, the other removes a conditional bean
silently. The infrastructure module therefore makes the second name biconditional for the web
workloads, so a root that omits it fails at plan time rather than producing a task that starts
and then behaves as though the feature were switched off.

### 7.1 Profiles

| File | Role |
|---|---|
| [`src/main/resources/application.yml`](src/main/resources/application.yml) | base profile; every placeholder and every documented default |
| [`src/main/resources/application-dev.yml`](src/main/resources/application-dev.yml) | dev overrides — sizing and retention only |
| [`src/main/resources/application-prod.yml`](src/main/resources/application-prod.yml) | prod overrides — sizing and retention only |
| [`src/test/resources/application-test.yml`](src/test/resources/application-test.yml) | test profile |

Assumptions: `dev` and `prod` differ only in sizing and retention and never in topology, which
is what makes a defect reproducible in `dev` rather than only observable in `prod`.

### 7.2 Actuator health serves two probes, not one

Assumptions: the Actuator health endpoint is polled by **two** independent consumers that fail
in different places — the container `HEALTHCHECK` declared in
[`Dockerfile`](Dockerfile), which decides whether the task is serving, and the ALB target
group, which decides whether to route to the task at all. Removing the actuator dependency
therefore breaks both at once, and the second failure presents as a networking fault rather
than as a missing dependency, which is why the coupling is recorded rather than left to be
rediscovered.

---

## 8. The Checkstyle documentation gate

### 8.1 The binding

The gate is **inherited from [`services/pom.xml`](../pom.xml)** and bound to Maven
**`validate`**: Checkstyle **13.8.0**, ruleset
[`config/checkstyle/checkstyle.xml`](../../config/checkstyle/checkstyle.xml), with
`failOnViolation=true` and `violationSeverity=warning`. Because `validate` is the first phase of
every lifecycle, the gate runs on **every local build** — not only in CI — and it cannot be
reached around by skipping a later phase.

### 8.2 What it demands of this module, concretely

- **`package-info.java` is required in every package.** All nine main packages and all nine
  test subpackages carry one (§2).
- **`skipAnnotations` is not relaxed**, so an annotated type is not exempt:
  `ReportingApplication.java` and every `config/*Config.java` needs full Javadoc.
- **`JavadocType` runs with `allowMissingParamTags="false"`**, so **every DTO record component
  needs an `@param`**. A record with five components and four `@param` tags fails.
- **`JavadocMethod` runs with `validateThrows="true"` and `allowMissingReturnTag="false"`**, so
  a declared or thrown exception needs an `@throws` and a non-void method needs an `@return`.
- **`src/test/java` is not suppressed**, so **test classes also need full Javadoc**.

Assumptions: the last point is the one most often discovered late. A test class added to close
a review finding fails the gate on its own missing Javadoc, and the failure names Checkstyle
rather than the test, so the cause reads as a build problem rather than as a documentation one.

```bash
# WHAT: print the fully-resolved POM for this module and read the gate's settings
# WHY : Assumptions: every setting above is INHERITED, so none of it can be confirmed by reading
#       services/reporting-service/pom.xml alone; the effective POM is the only authority that cannot go stale
mvn -f services/reporting-service/pom.xml help:effective-pom
```

### 8.3 The suppressions charter is narrow, and this module needs nothing from it

[`config/checkstyle/suppressions.xml`](../../config/checkstyle/suppressions.xml) is chartered
for **generated-source and test-fixture suppressions only**. Suppressing anything under
`src/main/java/**` is **absolutely prohibited**, and two paths are the ones that would be
reached for first and must never be:

- **`mapper/**`** — where the 133-column edit masks live (§3.3, §5.3). A suppression there
  would remove the documentation from precisely the code whose correctness is invisible without
  it, since a wrong mask produces a plausible line.
- **`service/StatementService.java`** — where divergence D-2 is justified (§3.1, §5.4). A
  suppression there would delete the record of why the class has no fixed arity, which is the
  one thing that stops a later author from adding a cap back.

### 8.4 The never-list

**Never** add `-Dcheckstyle.skip`, `<skip>true</skip>`, `failOnViolation=false`, a trailing
`|| true`, `continue-on-error: true`, or any return-code tolerance to this module's build or to
any workflow step that gates it. Trade-offs: each of those makes a red build green without
making the code compliant, and the graded-return-code habit of the COBOL oracle (§3.15) is
exactly the reflex that makes the last one feel reasonable. A Java gate here is binary by
design.

---

## 9. Rule 1 (Explainability) — the obligation, reproduced

**Rule 1 is the single user-specified rule for this project, and it governs this README too** —
a README is a module entry point. Its clauses are reproduced here in full rather than
referenced, so that a class author never has to leave this file to know what is required.

> Every new or modified function, class, and module entry point must include a docstring. Each
> docstring must specify — **Purpose:** What the function or class does; **Parameters:** Name,
> type, and description for each parameter; **Return values:** Type and description of what is
> returned; **Exceptions or errors:** Any that may be raised (where applicable). Follow the
> language's standard docstring format (**Javadoc for Java**). Trivial accessors
> (getters/setters with no logic) may use a single-line docstring.

> Place comments adjacent to the code they explain. Comments must explain WHY a decision was
> made, not WHAT the code does (the code already shows that). Each non-obvious implementation
> decision should document at least one of: **`Alternatives Considered:`** ·
> **`Refactoring Rationale:`** · **`Assumptions:`** · **`Trade-offs:`**

Assumptions: the four labels in the clause above carry the rule's own bold typography **with the
label itself in a backtick span**, which is the one written form that reproduces the rule
verbatim without becoming a violation of it. A bare `**Label:**` is the emphasised form that
[`docs/CODE_DOCUMENTATION_STANDARD.md`](../../docs/CODE_DOCUMENTATION_STANDARD.md) L232–L257
prohibits, and `config/rule1/rule1_gate.py` enforces that prohibition on `.md` files with no
exception for a quotation — so writing the clause the way the rule document typesets it would
fail the very gate the clause establishes. The backtick span resolves it because the gate masks
code spans and because a label is data rather than presentation, which is exactly what a code
span says about it. This is the form [`../common-lib/README.md`](../common-lib/README.md) §9.1
uses for the same reproduction.

> Forbidden: writing comments that restate what the code does; adding docstrings that omit
> parameters, return values, or purpose; leaving a non-obvious implementation choice
> undocumented when a reasonable alternative exists; using vague rationales ("this is better",
> "for performance") without specific justification.

> **Validation Gate:** Every new or modified function must have a docstring with purpose,
> parameters, and return values. Every non-obvious implementation decision must have an inline
> comment explaining why that approach was chosen using at least one of the categories above.
> **Code missing either fails review.**

### 9.1 The gate is conjunctive

"Missing either fails" means both halves are independently required: a fully documented class
with an undocumented non-obvious choice fails, and a well-justified class missing its Javadoc
fails. Neither half compensates for the other. Checkstyle can decide the first half
mechanically; **the second half is a review obligation and no linter in this repository can
check it.**

### 9.2 The four labels, and the house idiom

Use the canonical **plural** forms, character-for-character, and unemphasised:

```text
Alternatives Considered:
Refactoring Rationale:
Assumptions:
Trade-offs:
```

In fenced command blocks, use the twin idiom: **`# WHAT:` has no space before the colon and
`# WHY :` has exactly one**, which makes both labels eight characters wide so their text starts
in the same column and continuation lines align under both. The house form is established in
[`tests/README.md`](../../tests/README.md) §12, **L516 to L549**, whose blockquote at **L544 to
L549** states the same obligation for the reference suite: a docstring giving Purpose,
Parameters, Returns and Exceptions, and inline comments that explain **why** under at least one
of the four categories, never restating what the code does. That convention is cited here
because it is the repository's own written precedent for this rule; the full polyglot
specification is
[`docs/CODE_DOCUMENTATION_STANDARD.md`](../../docs/CODE_DOCUMENTATION_STANDARD.md).

### 9.3 The ledger of non-obvious decisions in this module

Every entry in §3 is one, and each already carries its label and citation. The ones a reviewer
should check first, because they are the ones a plausible edit would undo, are: the two
different sign masks (§3.3), the card-number break under an `'Account Total'` label (§3.31),
the absence of any fixed arity in the statement path (§3.1), the absence of a migration
directory (§3.2), the 20-line page size (§3.30), the added secondary sort key (§3.8), and the
computed month-end bound (§3.25).

---

## 10. Testing and parity

### 10.1 The four must-be-green assertions — this module's definition of done

1. **The report line is exactly 133 columns**, with byte-exact edit masks across four cases: a
   **signed-negative** case (`-ZZZ,ZZZ,ZZZ.ZZ`), a **leading-plus** case
   (`+ZZZ,ZZZ,ZZZ.ZZ`), a **zero-suppression** case (leading **blanks**, not zeros — and zero
   itself rendering as **15 blanks**), and a **maximum-magnitude** case (`999,999,999.99`).
2. **A statement renders correctly for one card with far more than 512 transactions** —
   asserting **correct output**, not an exception.
3. **A statement renders correctly for far more than 51 distinct cards** — again asserting
   **correct output**, not an exception.
4. **The module's database role is provably unable to write**, and **no `db/migration/`
   directory is present in the built artifact**.

Assumptions: assertions 2 and 3 are written to assert output rather than to assert that a
guard rejects the input, because D-2 removes a limit rather than replacing it with a checked
one (§3.1). A test that asserted an exception at 513 would re-specify the baseline's defect as
target behaviour.

### 10.2 Test inventory

<!-- test-inventory: 27 tests + 1 integration tests -->
**28** test classes across nine subpackages and the module root: **27** matching `*Test`, run
by Surefire, and **1** matching `*IT` — `repository/ReportingQueryBootstrapIT` — run by
Failsafe against a Testcontainers-backed PostgreSQL, with the Testcontainers BOM at **2.0.5**
managed by the parent. Every test package carries a `package-info.java`, because the
documentation gate audits test sources (§8.2).

Assumptions: the marker comment above this paragraph is **machine-checked**, not decorative.
`ServiceReadmeInventoryTest` in `common-lib` parses it, re-measures both figures against this
module's test tree, and additionally requires that the stated total equals their sum — so
editing the prose without editing the marker, or adding a test class without updating either,
fails the build in `common-lib` rather than here.

| Package | Classes |
|---|---|
| `mapper` | 8 |
| `config` | 5 |
| `api` | 3 |
| `service` | 4 |
| `dto` | 3 |
| `domain` | 1 |
| `fixtures` | 1 |
| `task` | 1 |
| `repository` | 1 (`ReportingQueryBootstrapIT`) |
| module root | 1 |

### 10.3 What the suites must cover

| Suite | What it proves |
|---|---|
| `@WebMvcTest` controller tests | the report-request and statement endpoints, including the confirmation gate of §3.27 answering a declining request as a success that starts nothing |
| report-layout test | the four mask cases of §10.1, byte-exact at 133 columns |
| two arity-removal statement tests | assertions 2 and 3 — correct output past both former thresholds |
| date-range test | the range comes from the request or job parameter and a **fixed** range yields reproducible output (§3.7), which also exercises the added secondary sort key of §3.8 |
| execution-start test | `ReportExecutionService` **starts the state machine and does not run the report inline** (§3.4) |
| `*RepositoryIT` | the read-only role **cannot write**, and the views return the expected **card-then-date** ordering |

Assumptions: the execution-start test asserts an absence as well as a presence. Asserting only
that the state machine was called would still pass if the handler also assembled the report
synchronously, which would move a batch-length unit of work onto a request thread.

### 10.4 Fixture record lengths

Fixtures are hand-authored at their **verified** declared record lengths, and the contract test
asserts each one per **row** rather than per file:

| Copybook | Record length |
|---|---|
| `CVTRA05Y` | **350** |
| `CVTRA01Y` | **50** |
| `CVACT01Y` | **300** |
| `CVACT02Y` | **150** |
| `CVCUS01Y` | **500** |
| `CVACT03Y` | **50** |
| `COSTM01` | **350** (`TRNX-KEY` 32 + `TRNX-REST` 318) |

Assumptions: the length is verified per row because a file-level byte count would be satisfied
by one row a byte short and the next a byte long, after which every field of both rows is read
from the wrong offsets while the file still measures correctly. The `COSTM01` split is
corroborated inside the baseline by `WS-TRAN-REST PIC X(318)` at
[`app/cbl/CBSTM03A.CBL`](../../app/cbl/CBSTM03A.CBL) **L230**, against the 32-byte `TRNX-KEY`
at [`app/cpy/COSTM01.CPY`](../../app/cpy/COSTM01.CPY) **L21 to L23**.

### 10.5 Parity method, and that new tests are additive

For the three batch programs (§3.16): run the COBOL pipeline to produce the goldens, run the
equivalent Java over migrated data, and compare after the same timestamp normalisation. The
only registered divergence for this module is **D-2** (§3.1), so any other difference is a
defect rather than an expected variance.

**New tests are strictly additive.** `tests/**` and `scripts/**` are REFERENCE: never modified,
never re-pinned. Their aggregate **RC=4** is the documented green state and arises from a
divergence owned by another module (§3.15).

---

## 11. Self-validation

Checks a reviewer can run against this file, and what each is for.

```bash
# WHAT: verify the twin-idiom spelling in this file — balanced label counts, and no unspaced variant
# WHY : Assumptions: the aligned pair is what makes continuation lines line up, so an unbalanced count
#       or a missing space before a colon is a real defect rather than a cosmetic one. The third
#       pattern is written with a bracketed colon so that this command does not itself match it
cd services/reporting-service && grep -c '# WHAT:' README.md && grep -c '# WHY :' README.md && ! grep -nE '# WHY[:]' README.md
```

- [x] The document opens with a header stating **Purpose** and **Source of truth**, cited by
      path **and** line.
- [x] All **32** non-obvious assertions appear in §3, each with a **plural** category label
      attached and its citation.
- [x] Divergence **D-2** states **both** thresholds **separately** — 512 inner (the 513th
      faults, `F-STMT-INNER-OVERFLOW`) and 51 outer distinct cards (the 52nd overruns the
      `OCCURS 51` at [`app/cbl/CBSTM03A.CBL`](../../app/cbl/CBSTM03A.CBL) **L226**,
      `F-STMT-OUTER-OVERFLOW`) — and explicitly warns that there is no single combined
      transaction limit. Assumptions: the conflated figure itself is spelled out exactly once in
      this file, inside that warning in §3.1 and nowhere else, so it is never readable as an
      assertion; this entry therefore describes it rather than repeating it.
- [x] `grep -c '# WHAT:'` equals `grep -c '# WHY :'`, and the unspaced-label scan
      `grep -nE '# WHY[:]'` returns nothing. Assumptions: that third pattern is written with a
      bracketed colon deliberately, so that this checklist entry does not itself become the one
      match it asserts is absent.
- [x] The contents map shows **no** `db/migration/` and annotates its deliberate absence.
- [x] The document does **not** claim divergence **D-1** or **D-3**; both are owned by
      `batch-service`, and D-1 is cited only as the source of the reference suite's RC=4.
- [x] The explainability convention is cited to [`tests/README.md`](../../tests/README.md)
      **§12, L516 to L549** and to
      [`docs/CODE_DOCUMENTATION_STANDARD.md`](../../docs/CODE_DOCUMENTATION_STANDARD.md) — and
      **not** to `CONTRIBUTING.md`, which carries no explainability content.
- [x] Every citation of an uppercase source file uses the uppercase spelling (§3.13).
- [x] The transient-data-queue stanza is cited as
      [`app/csd/CARDDEMO.CSD`](../../app/csd/CARDDEMO.CSD) **L499 to L505** with
      `DDNAME(INREADER)` at **L501** — never "L502" alone.
- [x] The abend fields are cited at [`app/cpy/CSMSG02Y.cpy`](../../app/cpy/CSMSG02Y.cpy)
      **L21 to L29** — never L45 to L53, which is past that file's 35-line end.
- [x] **No credential, endpoint, identifier or bucket value appears anywhere.** A scan for
      cloud resource identifiers, for the public service-domain suffix, and for a bucket-name
      assignment returns nothing from this file — and those three patterns are described here
      rather than spelled, so that this entry does not become the only match it asserts is
      absent. Assumptions: a case-insensitive scan for the two words that name a credential
      does match §7, and every hit there is a **name or a provenance** rather than a value —
      the managed store's own product name, and the settings whose own spelling contains the
      word. Names are what §7 publishes; values are what it withholds.
- [x] No temporal estimate and no schedule language appears anywhere.
- [x] No sentence restates a command without adding reasoning, and no vague rationale appears.
- [x] Every Markdown link resolves to a real path in this repository.

---

<sub>Apache-2.0 · This module is additive. `app/**`, `tests/**` and `scripts/**` are
REFERENCE-ONLY and are never modified. See [`../common-lib/README.md`](../common-lib/README.md)
for the shared kernel this module depends on,
[`../../docs/architecture/service-catalog.md`](../../docs/architecture/service-catalog.md) for
the module's place in the catalog, and
[`../../docs/architecture/cobol-to-service-traceability.md`](../../docs/architecture/cobol-to-service-traceability.md)
for the authoritative program-by-program derivations and the divergence register.</sub>
