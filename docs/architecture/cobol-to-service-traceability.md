# COBOL-to-Service Traceability

---

> **Purpose.** This document is the **authoritative traceability matrix** for the
> CardDemo migration and the **single register of every documented behavioural
> divergence**. It answers two questions exhaustively and answers no others: for
> each artifact in the COBOL baseline, *what in the target owns it* — and where the
> target deliberately behaves differently from the baseline, *what the difference
> is and why it is accepted*. It discharges the traceability portion of
> **Deliverable 2** of the seven numbered deliverables — *"/docs/architecture —
> target architecture diagram, service catalog, and data-mapping (VSAM/Db2/IMS →
> AWS) tables"* — and it is the artifact that discharges the standing constraint
> *"Functional parity with current business rules REQUIRED (document any
> intentional behavioral changes)"*. The register below **is** that documentation.
>
> It is authored **ninth and last** among the nine documents in this folder, and
> that position is deliberate: it is the **index**, not a second copy. Where a
> sibling owns a subject in depth, this document cites the sibling rather than
> restating it. Its four unique contributions are the complete artifact-to-target
> matrix, the paragraph-to-method pairs, the retirement register and the divergence
> register. Every canonical service and schema name, and every reconciled component
> population, comes from [`service-catalog.md`](service-catalog.md), which is the
> naming authority; subject-specific counts cite the sibling that measured them.
>
> **This document is the single authority for counts, mappings and divergences.**
> If any other document in the repository disagrees with a figure or a mapping
> recorded here, **that other document is the one to correct.** A traceability
> matrix with two authorities is not a matrix; the whole value of a single register
> is that a reader has exactly one place to look and exactly one place to fix.
>
> **The baseline is untouched and remains runnable.** Every citation below is a
> read. Nothing under [`app/`](../../app) is modified — not the COBOL, the
> copybooks, the mapsets, the symbolic maps, the JCL, the resource definitions, the
> control cards, the procedures, the assembler, the macros, the catalog listing, the
> scheduler definitions or the seed data. The original programs, data and jobs are
> exactly where they were and continue to work, which is precisely why reverting to
> the mainframe path requires **no un-migration**. **The migration adds a path; it
> does not remove one.**
>
> **Source of truth.** The whole baseline, enumerated directly rather than quoted
> from prose. All read, none modified:
>
> * the **44 migration-scope COBOL programs** — 31 under
>   [`app/cbl`](../../app/cbl) plus 13 across the three extension trees
>   ([`app/app-authorization-ims-db2-mq/cbl`](../../app/app-authorization-ims-db2-mq/cbl)
>   8, [`app/app-transaction-type-db2/cbl`](../../app/app-transaction-type-db2/cbl)
>   3, [`app/app-vsam-mq/cbl`](../../app/app-vsam-mq/cbl) 2);
> * the **30 copybooks** of [`app/cpy`](../../app/cpy) and the **17 symbolic map
>   copybooks** of [`app/cpy-bms`](../../app/cpy-bms), together with the 15
>   copybooks held in the extension trees;
> * the **21 BMS mapsets** — 17 under [`app/bms`](../../app/bms) plus 4 across the
>   extension trees — carrying **902** `DFHMDF` field definitions in the base tree
>   on a fixed 24×80 geometry;
> * the **38 JCL members** of [`app/jcl`](../../app/jcl), plus 8 across the
>   extension trees;
> * the **four CICS resource definitions** —
>   [`app/csd/CARDDEMO.CSD`](../../app/csd/CARDDEMO.CSD) (**505** lines),
>   [`CRDDEMO2.csd`](../../app/app-authorization-ims-db2-mq/csd/CRDDEMO2.csd),
>   [`CRDDEMOD.csd`](../../app/app-transaction-type-db2/csd/CRDDEMOD.csd) and
>   [`CRDDEMOM.csd`](../../app/app-vsam-mq/csd/CRDDEMOM.csd);
> * the **22 seed datasets** — 9 under [`app/data/ASCII`](../../app/data/ASCII) and
>   13 under [`app/data/EBCDIC`](../../app/data/EBCDIC);
> * the **remaining asset classes** — 1 control card
>   ([`app/ctl`](../../app/ctl)), 2 procedures ([`app/proc`](../../app/proc)), 2
>   assembler modules ([`app/asm`](../../app/asm)), 2 macros
>   ([`app/maclib`](../../app/maclib)), 1 catalog listing
>   ([`app/catlg`](../../app/catlg)), 2 scheduler definitions
>   ([`app/scheduler`](../../app/scheduler)), and the 8 IMS, 6 Db2 DDL and 3 Db2 DCL
>   members of the extension trees;
> * the **existing test suite** at [`tests/README.md`](../../tests/README.md),
>   which is the functional-parity oracle and is reference material here — its §1.1
>   documents the three known baseline limitations, its §8 the return-code rubric,
>   its §12 the explainability precedent this document follows and its §13 the
>   business rules asserted verbatim;
> * the root [`README.md`](../../README.md), whose component tables are one of the
>   four resource populations reconciled in [§1.2](#12-the-four-distinctly-labelled-resource-populations).
>
> **Delivers, and who consumes it.** It delivers four things that exist nowhere
> else: the artifact-to-target matrix covering all 44 programs and every non-program
> asset class; the paragraph-to-method pairs that make the rewrite auditable
> side-by-side; the retirement register, in two clearly separated kinds; and the
> divergence register. Its declared consumers are the root
> [`README.md`](../../README.md) and [`MIGRATION_README.md`](../../MIGRATION_README.md).
> `MIGRATION_README.md` links it at L156 as *"Traceability and divergences"*.
> Beyond those two, every service whose
> divergences are registered here consumes it: `auth-service` (D-4),
> `authorization-service` (D-5, D-6, and the segment loader's D-C,
> D-LOAD-PREFIX-REFUSED and D-LOAD-READ-BOUNDED), `batch-service` (D-1, D-3,
> D-POSTING-ATOMIC-NO-REJECT-109),
> `reporting-service` (D-2), and `account-service`, `card-service`,
> `transaction-service` and `reference-service` through the structural divergences
> in [§7.3](#73-structural-divergences-that-are-not-defects). **One consumer is not
> a service:** the user-interface shell under `ui/src/layout` consumes it for
> [D-7](#d-7--the-header-clock-and-the-zone-it-is-read-in), which belongs to no
> service because the header band it concerns is realised as a client-side component
> rather than behind an endpoint — the same reason two programs appear in
> [§2.9](#29-realised-as-user-interface-routes-rather-than-as-a-service) with no
> service owner. Eight sibling
> documents name this document as the owner of the register they defer to.
>
> **Measured implementation status.** Every baseline figure and line citation below
> was measured directly from the cited file and is a statement about the repository
> as it stands. Every target-side entry is a statement about an intended or authored
> artifact, labelled as such — the matrix records **ownership**, not deployment. No
> row asserts that any target artifact has run against a provisioned environment,
> because none has.
>
> **Caveats.** Five, stated here rather than buried. **First**, the baseline is
> reference-only, and that includes the three known limitations recorded at
> [`tests/README.md`](../../tests/README.md) §1.1: they are registered in
> [§7.1](#71-divergences-arising-from-the-three-known-baseline-limitations) as
> divergences and **no COBOL is edited**. **Second**, no live environment exists;
> `terraform apply` against a real account is an operator action outside this scope,
> so no mapping row implies provisioned infrastructure. **Third**, this document
> records mappings only — it defines no delivery groupings or implementation order,
> because a traceability matrix has no such structure to record. **Fourth**, the
> items listed in [§10](#10-out-of-scope-including-the-baselines-own-stated-future-work)
> are out of scope and appear nowhere as delivered, including four the baseline
> itself lists as its own future work. **Fifth**, the existing COBOL suite keeps its
> own workflow, its own pins and its own aggregate return code, and its documented
> **warn-level aggregate of 4 is the green state** — see
> [§9](#9-the-parity-oracle-and-why-warn-is-green), because misreading that 4 as a
> regression introduced by this work is the single most likely misreading of the
> whole migration.

---

## Five facts a careful reader would otherwise get wrong

These are stated first because a reasonable reader will assume the opposite of each
one, and because each is a figure or a claim that this document is the authority
for. Each is proven at its own section below.

1. **A repository-wide search for COBOL source returns a larger figure than the
   migration scope, and both figures are correct.** The migration scope is **44**
   programs. [`tests/cobol-unit`](../../tests/cobol-unit) holds 12 additional source
   files that belong to the test suite. The arithmetic and the reason those test
   programs are excluded are in
   [§1.1](#11-migration-scope-versus-repository-wide-source-files). A reader who
   quotes the repository-wide figure as a program count has counted the parity
   oracle as a migration target.
2. **The statement generator has two independent overflow thresholds, not one
   transaction limit.** [`CBSTM03A.CBL`](../../app/cbl/CBSTM03A.CBL) declares two
   unrelated unchecked tables, and they fault at **512 transactions for a single
   card** and at **51 distinct cards** respectively. Treating the outer table's card
   count as a transaction limit conflates the two and mislabels the larger threshold
   as the smaller. Both are recorded, separately, in
   [D-2](#d-2--the-two-unchecked-statement-tables).
3. **The centralised error-emission paragraph has fourteen call sites, not ten.**
   A figure of ten is in circulation and omits exactly four — the reply put, both
   segment writes and the queue close. All fourteen are enumerated in
   [§3](#3-paragraph-to-method-pairs), agreeing with
   [`observability.md`](observability.md), which owns the error-logging mapping.
4. **The resource-definition provenance operands do not identify the dangling
   program definition, even though they look as though they should.** Measured
   across the base region's 18 program stanzas, `CHANGEAGENT(CSDAPI)` appears on 14
   of them and the definition dates spread across fourteen distinct days, so
   neither operand discriminates. The signals that actually do are in
   [§6](#6-the-dangling-programcocrdsec).
5. **"Retired" means two entirely different things, and only two items in the whole
   baseline mean the stronger one.** Most retirements preserve the function and
   replace only the mechanism. Exactly **two** artifacts retire with no target and
   no analogue at all. The distinction is the whole structure of
   [§5](#5-the-retirement-register).

---

## WHY (non-obvious design decisions)

This section carries the reasoning for the choices this document makes *about
itself*; every mapping and divergence decision is justified at the point where it
is recorded, under the same four category names, following
[`../CODE_DOCUMENTATION_STANDARD.md`](../CODE_DOCUMENTATION_STANDARD.md) and the
convention already established for the test suite at
[`tests/README.md`](../../tests/README.md) §12.

- Refactoring Rationale: this document's dominant category is necessarily
  `Refactoring Rationale`, because the divergence register is literally an instance
  of it — the standard defines the category as *"when replacing existing code, what
  was wrong with the old approach"*, and every entry in
  [§7](#7-the-divergence-register) states a baseline behaviour, a different target
  behaviour and the reason the difference is accepted. The category is used with one
  hard boundary, stated once and observed throughout: **the register describes a
  difference between two implementations, never a repair of the baseline.** No entry
  claims that any COBOL was edited, because none was. The baseline's behaviour is
  cited by line and left exactly as it is; the target's behaviour is described as
  the target's. A register that blurred those two would misrepresent the state of
  the repository, which is the one thing a traceability document cannot afford.
- Trade-offs: this document has two jobs that pull in opposite directions, and the
  trade-off is resolved differently for each. **As a matrix it is exhaustive** —
  every one of the 44 programs and every non-program asset class appears, with the
  coverage arithmetic printed so a reader can audit it rather than trust it. **As a
  register it is restrained** — it states what differs and why, and it does not
  editorialise about the platform that differs. The accepted cost of exhaustiveness
  is length; the accepted cost of restraint is that a reader looking for a verdict
  on the mainframe will not find one here, which is correct, because a divergence
  records a difference between implementations and not a deficiency of a platform.
- Assumptions: the repository is authoritative and prose is not. Every count,
  line number and paragraph name below was measured from the file named beside it,
  and where a figure is easy to get wrong it is printed together with the
  measurement that establishes it. Three figures in circulation did not survive that
  measurement and are reconciled explicitly rather than quietly restated: the
  program count ([§1.1](#11-migration-scope-versus-repository-wide-source-files)), the error-emission
  call-site count ([§3](#3-paragraph-to-method-pairs)) and the provenance-operand
  signal ([§6](#6-the-dangling-programcocrdsec)). Where this document and a figure
  in circulation disagree, the measurement command is given so the disagreement is
  settleable rather than a matter of authority.
- Alternatives Considered: the alternative to a single register was to let each
  document record its own divergences beside the subject they affect, which is where
  a reader first encounters them. That was rejected for a specific reason rather
  than on taste: a divergence is only meaningful against the complete set, because
  the question a reviewer actually asks is *"is this the whole list?"* — and that
  question is unanswerable if the list is distributed across nine documents. The
  accepted cost is one indirection: eight siblings now defer to this file, so a
  reader arriving at a divergence in context has to follow one link to see it
  registered. Each sibling therefore names both the divergence and this document at
  the point of use, so the indirection is signposted rather than discovered.
- Alternatives Considered: the matrix is grouped **by owning target service** rather
  than by baseline directory. Grouping by directory would have made the baseline
  easier to walk, and it was rejected because the question this table exists to
  answer is *"who owns this program now"* — a reader with a program name can find
  it either way, but only service grouping makes an unowned program visible as a
  gap in a group rather than invisible in a long alphabetical list.
- Assumptions: the sibling documents are the naming authority for everything they
  own, and this document introduces no variant spelling of a service, schema, queue,
  table or index name. Where a mapping needs supporting detail — a column
  derivation, a state-machine mapping, a queue contract, a token mapping — the
  detail is cited, never copied, so that a later change to the owning document
  cannot leave a stale duplicate here.

---

## 1. The verified baseline inventory

Every later section depends on these figures, so they come first. Each was measured
directly against the repository; the commands that reproduce them are in
[§1.3](#13-reproducing-these-figures).

| Asset class | Migration scope | Repository-wide | Note |
|---|---|---|---|
| COBOL programs | **31** in [`app/cbl`](../../app/cbl) + **13** across the three extension trees = **44** | Migration scope plus 12 test-suite source files | Reconciled in [§1.1](#11-migration-scope-versus-repository-wide-source-files) |
| Extension programs | authorization **8**, transaction-type/Db2 **3**, VSAM/MQ **2** = **13** | — | The authorization tree holds **eight**, not seven — see [§4.1](#41-copaus2c-present-in-neither-transaction-population) |
| Copybooks | **30** in [`app/cpy`](../../app/cpy) | **62** | Counts repository files, not resolvable includes — see [§8](#8-inventory-caveats-a-reader-will-otherwise-contradict) |
| BMS mapsets | **17** in [`app/bms`](../../app/bms) | **21** | **902** `DFHMDF` field definitions in the base tree, fixed 24×80 |
| Symbolic map copybooks | **17** in [`app/cpy-bms`](../../app/cpy-bms) | 17 | Consumed as DTO field-shape sources |
| JCL members | **38** in [`app/jcl`](../../app/jcl) | **55** | 38 + 8 extension + 9 under `samples/**`; `samples/**` is reference and out of scope |
| CICS resource definitions | **4** | 4 | [`CARDDEMO.CSD`](../../app/csd/CARDDEMO.CSD) is **505** lines |
| Seed datasets | **9** ASCII + **13** EBCDIC = **22** | 22 | `usrsec` exists only in EBCDIC form |
| Control cards, procedures | **1** + **2** | 3 | Mapped into the ETL load and report steps |
| Assembler, macros | **2** + **2** | 4 | Retired — no cloud analogue |
| Catalog listing, scheduler definitions | **1** + **2** | 3 | Retired as syntax; the scheduler *intent* is carried forward |
| IMS, Db2 DDL, Db2 DCL | **8** + **6** + **3** | 17 | Mapped into the `authorization` and `reference` schema migrations |

### 1.1 Migration scope versus repository-wide source files

A repository-wide search for COBOL source returns **56** files. The migration scope
is **44**. Both numbers are right, and the difference is entirely the test suite:

```text
44  migration-scope programs   =  31  app/cbl
                                 +  8  app/app-authorization-ims-db2-mq/cbl
                                 +  3  app/app-transaction-type-db2/cbl
                                 +  2  app/app-vsam-mq/cbl
+ 12  test-suite programs       =  10  tests/cobol-unit/*_test.cbl
                                 +  1  tests/cobol-unit/CBACT04C_driver.cbl
                                 +  1  tests/cobol-unit/gcblunit.cbl
-------------------------------------------------------------------------
= 56  files matching a COBOL source extension, repository-wide
```

> Assumptions: this arithmetic is printed rather than asserted because it is an
> external-inventory contract that a reader will otherwise contradict. Anyone who
> runs the obvious repository-wide search gets the larger figure, and with no
> reconciliation in front of them the reasonable conclusion is that this document's
> program count is wrong. The 12 excluded files are the **parity oracle**, not
> migration targets: they exercise the baseline to produce the golden outputs
> against which the target is compared, so counting them as things to migrate would
> mean proposing to migrate the instrument that measures the migration. Ten carry
> the `*_test.cbl` suffix, one is a driver for the interest test and one is the
> vendored unit-test framework — none is a business program, and
> [`tests/README.md`](../../tests/README.md) §2 documents all three roles.

**Program counts do not resolve to a filename glob, in either tree.** Three of the
eight authorization programs use an uppercase source extension —
`DBUNLDGS.CBL`, `PAUDBLOD.CBL` and `PAUDBUNL.CBL` — while five use lowercase, so a
case-sensitive lowercase glob over that directory returns five rather than eight.
The same trap exists one directory over in the base tree, where `CBSTM03A.CBL` and
`CBSTM03B.CBL` are uppercase, and again among the copybooks, where `COSTM01.CPY` is
the single uppercase member of `app/cpy` —
[`data-model-and-schema-mapping.md`](data-model-and-schema-mapping.md) records that
last one as the reason a glob-based measurement of that directory returns 29 instead
of 30.

### 1.2 The four distinctly-labelled resource populations

Four different populations in this repository each answer the question "how many
online transactions are there?" with a different number, and **none of them is
"the" transaction count**. [`service-catalog.md`](service-catalog.md) owns the
reconciliation in full; the figures are reproduced here because the matrix in
[§2](#2-the-program-to-service-matrix--all-44) is checked against them.

| Population | What it measures | Figures |
|---|---|---|
| 1 — documented online components | the root [`README.md`](../../README.md) `Online Components` table | **24** rows |
| 2 — the base CICS region | [`app/csd/CARDDEMO.CSD`](../../app/csd/CARDDEMO.CSD) | **18** `DEFINE TRANSACTION`, **18** `DEFINE PROGRAM`, **17** `DEFINE MAPSET`, **8** `DEFINE FILE`, **2** `DEFINE LIBRARY`, **1** `DEFINE TDQUEUE` |
| 3 — all resource definitions | all four CSD files repository-wide | **25** `DEFINE TRANSACTION`, **26** `DEFINE PROGRAM`, **21** `DEFINE MAPSET`, **8** `DEFINE FILE` |
| 4 — documented and physical batch populations | the root `README.md` `Batch Components` table versus [`app/jcl`](../../app/jcl) | **27** documented rows versus **38** members |

The two online identities both hold, and each answers a different question:
**18 + 7 = 25** (base-region transactions plus the seven contributed by the three
extension definitions gives the repository-wide total), and **17 + 7 = 24** (the
seventeen the README and the base region agree on, plus the same seven, gives the
README's row count). The difference between the two left-hand terms is the single
entry `CDV1`, which is the whole discrepancy and is the subject of
[§6](#6-the-dangling-programcocrdsec). The batch identities hold on the same
pattern: **23 + 15 = 38** members of `app/jcl`, and **23 + 4 = 27** README batch
rows.

> Assumptions: `18` appears in three of those rows meaning three different things —
> eighteen transaction definitions, eighteen program definitions, and the eighteen
> filename-prefixed `CO*` members of `app/cbl` — and only one of the three is a
> count of online programs. The prefix is not a reliable guide: `COBSWAIT` carries
> the online prefix but contains **zero** `EXEC CICS` verbs and the README lists it
> under Batch Components, so the functional split of the eighteen `CO*` members is
> **17 online plus one batch utility**. All 12 `CB*` members likewise contain zero
> `EXEC CICS` verbs, which is the property that lets them run standalone under the
> open-source compiler and is the basis of the existing suite's automation.

### 1.3 Reproducing these figures

```bash
# WHAT: count the migration-scope programs per tree, then the repository-wide
#       total, so the 31/13/44 split and the repository-wide reconciliation are both
#       checkable from one place.
# WHY : Assumptions: the search must be case-INSENSITIVE and must not rely on a
#       shell glob. Three authorization programs and two base statement programs
#       carry an uppercase extension, so a lowercase `*.cbl` glob silently
#       undercounts two different directories -- which is exactly the measurement
#       error that makes a reader think this inventory is wrong.
for d in app/cbl app/app-authorization-ims-db2-mq/cbl \
         app/app-transaction-type-db2/cbl app/app-vsam-mq/cbl tests/cobol-unit; do
  printf '%-44s %s\n' "$d" "$(find "$d" -type f -iname '*.cbl' | wc -l)"
done
find . -path ./.git -prune -o -type f -iname '*.cbl' -print | wc -l
```

```bash
# WHAT: count the resource stanzas of the base region, then the same stanzas across
#       all four definitions, summing PER FILE rather than over a concatenation.
# WHY : Trade-offs: the per-file summation with an UNANCHORED pattern is used
#       because both of the obvious alternatives fail here, in ways that stay
#       plausible. A line-anchored pattern matches the base file but returns zero on
#       two of the three extension definitions, whose stanzas carry different
#       leading whitespace. Concatenating the four files instead joins one file's
#       last line to the next file's first, because two of them end without a
#       terminal newline -- so an anchored pattern over a concatenation reports 20
#       mapsets and 25 programs where the true figures are 21 and 26, while still
#       returning a correct 25 for transactions. That partial failure is the
#       dangerous one: the totals stay internally consistent and look right.
for k in TRANSACTION PROGRAM MAPSET FILE; do
  repo=0
  for f in $(find . -path ./.git -prune -o -type f -iname '*.csd' -print); do
    repo=$(( repo + $(grep -o "DEFINE $k(" "$f" | wc -l) ))
  done
  printf '%-12s base=%-3s repo=%s\n' "$k" \
    "$(grep -c "DEFINE $k(" app/csd/CARDDEMO.CSD)" "$repo"
done
```

Expected output — `base=18 repo=25`, `base=18 repo=26`, `base=17 repo=21`,
`base=8 repo=8` — matching rows 2 and 3 of the population table above.

---

## 2. The program-to-service matrix — all 44

One row per program, grouped by the owning target service. The canonical service
names are those of [`service-catalog.md`](service-catalog.md): `auth-service`,
`account-service`, `card-service`, `transaction-service`, `reference-service`,
`batch-service`, `authorization-service` and `reporting-service`. `common-lib` also
appears below as the owner of shared behaviour; it is **a Maven module, not a
bounded context**, and it is named where a program's logic is shared kernel rather
than service logic.

### 2.1 `auth-service`

| Program | Type | Source | Target artifact | Notes |
|---|---|---|---|---|
| `COSGN00C` | online | [`app/cbl/COSGN00C.cbl`](../../app/cbl/COSGN00C.cbl) | `AuthController`, `CognitoIdentityService` | Sign-on becomes a token exchange. The three sign-on messages carry across verbatim. Registered divergence: [D-4](#d-4--the-plaintext-credential-field-is-not-carried-forward) |
| `COUSR00C` | online | [`app/cbl/COUSR00C.cbl`](../../app/cbl/COUSR00C.cbl) | `UserController` list endpoint | Browse becomes keyset pagination |
| `COUSR01C` | online | [`app/cbl/COUSR01C.cbl`](../../app/cbl/COUSR01C.cbl) | `UserController` create, `UserService` | Field validation transcribed paragraph by paragraph |
| `COUSR02C` | online | [`app/cbl/COUSR02C.cbl`](../../app/cbl/COUSR02C.cbl) | `UserController` update, `UserService` | User-type domain restricted to `'A'`/`'U'` |
| `COUSR03C` | online | [`app/cbl/COUSR03C.cbl`](../../app/cbl/COUSR03C.cbl) | `UserController` delete | Re-key-to-confirm becomes an explicit confirmation |

### 2.2 `account-service`

| Program | Type | Source | Target artifact | Notes |
|---|---|---|---|---|
| `COACTVWC` | online | [`app/cbl/COACTVWC.cbl`](../../app/cbl/COACTVWC.cbl) | `AccountController` view, `AccountViewService` | Composes customer and cross-reference data |
| `COACTUPC` | online | [`app/cbl/COACTUPC.cbl`](../../app/cbl/COACTUPC.cbl) | `AccountController` update, `AccountUpdateService` | The before-image comparison becomes a version column; see [§7.3](#73-structural-divergences-that-are-not-defects) and [D-UPDATE-CASE-SENSITIVE-COMPARE](#d-update-case-sensitive-compare--the-concurrency-comparison-is-case-sensitive-where-the-baseline-folded-ten-fields) |
| `CBACT01C` | batch | [`app/cbl/CBACT01C.cbl`](../../app/cbl/CBACT01C.cbl) | `AccountRepository` sequential read | Account master access |
| `CBACT03C` | batch | [`app/cbl/CBACT03C.cbl`](../../app/cbl/CBACT03C.cbl) | `CardXrefController`, `CardXrefRepository` | Includes the by-account path that replaces `CXACAIX` |
| `CBCUS01C` | batch | [`app/cbl/CBCUS01C.cbl`](../../app/cbl/CBCUS01C.cbl) | `CustomerController`, `CustomerRepository` | Customer master access |
| `COACCT01` | online | [`app/app-vsam-mq/cbl/COACCT01.cbl`](../../app/app-vsam-mq/cbl/COACCT01.cbl) | `InquiryMessageListener` | The account-inquiry request/reply flow; contract in [`messaging-contracts.md`](messaging-contracts.md) |

### 2.3 `card-service`

| Program | Type | Source | Target artifact | Notes |
|---|---|---|---|---|
| `COCRDLIC` | online | [`app/cbl/COCRDLIC.cbl`](../../app/cbl/COCRDLIC.cbl) | `CardController` list, `CardListService` | The browse cursor becomes a keyset page envelope. Registered divergence: [D-CARD-SELECTOR](#d-card-selector--a-card-is-addressed-by-an-opaque-selector-and-the-list-no-longer-narrows-by-card-number) |
| `COCRDSLC` | online | [`app/cbl/COCRDSLC.cbl`](../../app/cbl/COCRDSLC.cbl) | `CardController` detail | Primary account number masked except on the administrative endpoint |
| `COCRDUPC` | online | [`app/cbl/COCRDUPC.cbl`](../../app/cbl/COCRDUPC.cbl) | `CardController` update, `CardUpdateService` | `SYNCPOINT` becomes a transaction boundary |
| `CBACT02C` | batch | [`app/cbl/CBACT02C.cbl`](../../app/cbl/CBACT02C.cbl) | `CardRepository` sequential read | Includes the by-account path that replaces `CARDAIX` |

### 2.4 `transaction-service`

| Program | Type | Source | Target artifact | Notes |
|---|---|---|---|---|
| `COTRN00C` | online | [`app/cbl/COTRN00C.cbl`](../../app/cbl/COTRN00C.cbl) | `TransactionController` list, `TransactionListService` | Forward and backward paging match the browse verbs |
| `COTRN01C` | online | [`app/cbl/COTRN01C.cbl`](../../app/cbl/COTRN01C.cbl) | `TransactionController` detail | Money transported as a string |
| `COTRN02C` | online | [`app/cbl/COTRN02C.cbl`](../../app/cbl/COTRN02C.cbl) | `TransactionController` create, `TransactionAddService` | Identifier generation preserved |
| `COBIL00C` | online | [`app/cbl/COBIL00C.cbl`](../../app/cbl/COBIL00C.cbl) | `BillPaymentController`, `BillPaymentService`, `AccountBalanceRepository` | Ledger row and account balance in ONE transaction, by the cross-schema exception in §7.3 |

### 2.5 `reference-service`

| Program | Type | Source | Target artifact | Notes |
|---|---|---|---|---|
| `CSUTLDTC` | utility | [`app/cbl/CSUTLDTC.cbl`](../../app/cbl/CSUTLDTC.cbl) | `DateEditValidator` in `common-lib`, surfaced by `DateConversionController` | Date-edit rules are shared kernel, so the transcription lands in `common-lib` and the service exposes it |
| `COTRTLIC` | online | [`COTRTLIC.cbl`](../../app/app-transaction-type-db2/cbl/COTRTLIC.cbl) | `TransactionTypeController` list | Inline edit over the reference tables |
| `COTRTUPC` | online | [`COTRTUPC.cbl`](../../app/app-transaction-type-db2/cbl/COTRTUPC.cbl) | `TransactionTypeController` maintenance | The referential-integrity restriction surfaces as a conflict response rather than a database error |
| `COBTUPDT` | batch | [`COBTUPDT.cbl`](../../app/app-transaction-type-db2/cbl/COBTUPDT.cbl) | Reference-data update service | Batch reference maintenance |
| `CODATE01` | online | [`CODATE01.cbl`](../../app/app-vsam-mq/cbl/CODATE01.cbl) | `DateConversionController` plus a queue consumer | The date-conversion request/reply flow |

### 2.6 `batch-service`

| Program | Type | Source | Target artifact | Notes |
|---|---|---|---|---|
| `CBTRN01C` | batch | [`app/cbl/CBTRN01C.cbl`](../../app/cbl/CBTRN01C.cbl) | `PreflightDailyTransactionsJob` | **Has no JCL driver in the baseline**; migrated regardless — see [§7.3](#73-structural-divergences-that-are-not-defects) |
| `CBTRN02C` | batch | [`app/cbl/CBTRN02C.cbl`](../../app/cbl/CBTRN02C.cbl) | `PostTransactionsJob`, `PostingValidationService`, `CategoryBalanceService` | The four reject reasons, both inclusive boundaries and the create-versus-update branch; pairs in [§3](#3-paragraph-to-method-pairs) |
| `CBACT04C` | batch | [`app/cbl/CBACT04C.cbl`](../../app/cbl/CBACT04C.cbl) | `CalculateInterestJob`, `InterestCalculationService` | Registered divergence: [D-3](#d-3--the-final-account-interest-flush) |
| `CBEXPORT` | batch | [`app/cbl/CBEXPORT.cbl`](../../app/cbl/CBEXPORT.cbl) | `ExportJob` | Registered divergence: [D-1](#d-1--the-exportimport-record-key-declaration) |
| `CBIMPORT` | batch | [`app/cbl/CBIMPORT.cbl`](../../app/cbl/CBIMPORT.cbl) | `ImportJob` | Registered divergence: [D-1](#d-1--the-exportimport-record-key-declaration) |

### 2.7 `authorization-service`

All eight members of the authorization extension tree, including the one that has no
transaction definition anywhere ([§4.1](#41-copaus2c-present-in-neither-transaction-population)).

| Program | Type | Source | Target artifact | Notes |
|---|---|---|---|---|
| `COPAUS0C` | online | [`COPAUS0C.cbl`](../../app/app-authorization-ims-db2-mq/cbl/COPAUS0C.cbl) | `PendingAuthController` summary | Pending-authorization summary list |
| `COPAUS1C` | online | [`COPAUS1C.cbl`](../../app/app-authorization-ims-db2-mq/cbl/COPAUS1C.cbl) | `PendingAuthController` detail | Detail view with its commit and rollback paths |
| `COPAUS2C` | online | [`COPAUS2C.cbl`](../../app/app-authorization-ims-db2-mq/cbl/COPAUS2C.cbl) | `FraudController` | *Mark Authorization Message Fraud*; reached by transfer of control, not by its own transaction |
| `COPAUA0C` | batch | [`COPAUA0C.cbl`](../../app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl) | `AuthorizationRequestListener`, `OutboxPublisher` | The queue consumer. Registered divergences: [D-5](#d-5--the-reply-published-before-the-decision-is-committed), [D-6](#d-6--the-distributed-commit-is-eliminated-not-emulated) |
| `CBPAUP0C` | batch | [`CBPAUP0C.cbl`](../../app/app-authorization-ims-db2-mq/cbl/CBPAUP0C.cbl) | `PurgeJob` | Expiry and purge of pending authorizations |
| `PAUDBLOD` | batch | [`PAUDBLOD.CBL`](../../app/app-authorization-ims-db2-mq/cbl/PAUDBLOD.CBL) | `LoadService` | Segment load utility. Registered divergences: [D-C](#d-c--an-unresolvable-parent-is-reported-where-the-nested-branch-leaves-it-unreported), [D-LOAD-PREFIX-REFUSED](#d-load-prefix-refused--an-undecodable-parent-key-is-reported-where-the-guard-has-no-else-branch), [D-LOAD-READ-BOUNDED](#d-load-read-bounded--the-load-walk-cannot-fail-to-terminate-where-two-read-branches-suspend-it) |
| `PAUDBUNL` | batch | [`PAUDBUNL.CBL`](../../app/app-authorization-ims-db2-mq/cbl/PAUDBUNL.CBL) | `UnloadService`, invoked by `UnloadAuthorizationsTask` under `--job=unload-authorizations` | Segment unload utility, and the **default** export form because it is the one a load reads back. Registered divergence: [D-UNLOAD-SKIP-REPORTED](#d-unload-skip-reported--a-root-the-unload-cannot-attribute-is-counted-and-reported-where-the-guard-passes-it-over-in-silence) |
| `DBUNLDGS` | batch | [`DBUNLDGS.CBL`](../../app/app-authorization-ims-db2-mq/cbl/DBUNLDGS.CBL) | `UnloadService` sequential path, reached by `--job=unload-authorizations --extract-form=sequential` | Sequential unload utility, selected by naming `UnloadForm.SEQUENTIAL`, which the task's form option is the operator-facing route to. Its child record is the **bare 200-byte segment**, per its two commented-out `WRITE`s at **L242** and **L281** and the `ISRT` operands at **L302**–**L304** and **L321**–**L323**; the 206-byte group surviving at its **L53**–**L56** is working storage nothing writes. Registered divergence: [D-UNLOAD-SKIP-REPORTED](#d-unload-skip-reported--a-root-the-unload-cannot-attribute-is-counted-and-reported-where-the-guard-passes-it-over-in-silence) |

### 2.8 `reporting-service`

| Program | Type | Source | Target artifact | Notes |
|---|---|---|---|---|
| `CORPT00C` | online | [`app/cbl/CORPT00C.cbl`](../../app/cbl/CORPT00C.cbl) | `ReportController` | The transient-data-queue submission becomes an orchestration start; see [`batch-orchestration.md`](batch-orchestration.md). Registered divergences: [D-REPORT-HANDLE](#d-report-handle--report-submission-returns-an-addressable-execution-handle), [D-REPORT-SUBMISSION-DEDUPLICATED](#d-report-submission-deduplicated--a-resubmitted-report-request-is-folded-onto-the-run-it-is-retrying) |
| `CBTRN03C` | batch | [`app/cbl/CBTRN03C.cbl`](../../app/cbl/CBTRN03C.cbl) | `TransactionReportService`, `TransactionReportMapper` | 133-column output with its exact edit masks. Registered divergences: [D-REPORT-GRAND-TOTAL](#d-report-grand-total--the-last-transactions-amount-is-counted-once-not-twice), [D-REPORT-CLOSING-TOTAL](#d-report-closing-total--the-last-card-group-is-closed-by-an-account-total-band) |
| `CBSTM03A` | batch | [`app/cbl/CBSTM03A.CBL`](../../app/cbl/CBSTM03A.CBL) | `StatementService` grouping, `StatementTextMapper`, `StatementHtmlMapper` | Registered divergences: [D-2](#d-2--the-two-unchecked-statement-tables), [D-STMT-PAIRED-BLANK-NAME](#d-stmt-paired-blank-name--an-empty-middle-name-leaves-the-two-statement-artifacts-disagreeing), [D-STMT-HTML-ESCAPING](#d-stmt-html-escaping--dynamic-statement-text-is-escaped-for-the-markup-artifact) |
| `CBSTM03B` | batch | [`app/cbl/CBSTM03B.CBL`](../../app/cbl/CBSTM03B.CBL) | `StatementService` rendering | Plain-text and HTML rendering |

### 2.9 Realised as user-interface routes rather than as a service

| Program | Type | Source | Target artifact | Notes |
|---|---|---|---|---|
| `COMEN01C` | online | [`app/cbl/COMEN01C.cbl`](../../app/cbl/COMEN01C.cbl) | the `/menu` route | Option text and numbering preserved verbatim |
| `COADM01C` | online | [`app/cbl/COADM01C.cbl`](../../app/cbl/COADM01C.cbl) | the `/admin` route | Guarded by the group claim rather than by a client-supplied field |

> Refactoring Rationale: these two have **no service owner**, and that is coverage
> rather than omission. Both do nothing but present an option list and dispatch to
> the chosen program, and the dispatch target travelled in the shared session
> structure that the target eliminates entirely. Assigning them to a service would
> require a server-side endpoint whose only output is a static list of routes the
> client already holds — and a "next program" field living on the server is exactly
> the state being removed. The administrator-versus-user split that decided which
> menu to present becomes a signed group claim on the request. The presentational
> mapping is owned by [`design-token-reference.md`](design-token-reference.md); the
> state analysis is owned by
> [`context-and-container-diagrams.md`](context-and-container-diagrams.md).

### 2.10 Retired

| Program | Type | Source | Target artifact | Notes |
|---|---|---|---|---|
| `COBSWAIT` | batch utility | [`app/cbl/COBSWAIT.cbl`](../../app/cbl/COBSWAIT.cbl) | none — **function preserved** | Retired **with** an analogue: see [§5.1](#51-retired-with-an-analogue--function-preserved-mechanism-replaced) |

### 2.11 Coverage arithmetic

The arithmetic is printed so a reader can audit the claim rather than accept it. If
a program appears in no group above and in no retirement entry, the matrix is
incomplete.

| Group | Count |
|---|---|
| `auth-service` | 5 |
| `account-service` | 6 |
| `card-service` | 4 |
| `transaction-service` | 4 |
| `reference-service` | 5 |
| `batch-service` | 5 |
| `authorization-service` | 8 |
| `reporting-service` | 4 |
| User-interface routes (`COMEN01C`, `COADM01C`) | 2 |
| Retired with an analogue (`COBSWAIT`) | 1 |
| **Total** | **44** |

The same total closes a second way, which is the check that matters because it
partitions by *source tree* rather than by owner: **28** members of `app/cbl` are
assigned to one of the eight services, **2** become user-interface routes and **1**
is retired — 28 + 2 + 1 = **31**, the whole of `app/cbl` — and 31 + 8 + 2 + 3 =
**44**. `authorization-service` draws all eight of its programs from the extension
tree and none from `app/cbl`, which is why its column total and its source tree
coincide.

---

## 3. Paragraph-to-method pairs

The service layer of every migrated program is organised so that each significant
COBOL paragraph becomes a named method. This section is the only place those pairs
are recorded.

> Refactoring Rationale: the paragraph-to-method mapping is what makes the rewrite
> **auditable** rather than merely reviewable. A reviewer holding this table can put
> a COBOL paragraph and a target method side by side and check one against the
> other; without it, a rewrite that reorganised the logic silently would be
> indistinguishable from one that preserved it, and the reviewer would be reduced to
> reading the target on its own terms. That is the specific failure this table
> prevents. The naming is deliberately literal — a method is named after its
> paragraph rather than after what a fresh design would have called it — and the
> accepted cost is target method names that read a little oddly in isolation.

### 3.1 `CBTRN02C` — posting

| COBOL paragraph | Line | Target method | What it does |
|---|---|---|---|
| `1500-VALIDATE-TRAN` | L370 | `PostingValidationService.validate` | Entry to the short-circuit validation chain |
| `1500-A-LOOKUP-XREF` | L380 | `PostingValidationService.lookupCrossReference` | Reject **100** `INVALID CARD NUMBER FOUND` at L385–L387 |
| `1500-B-LOOKUP-ACCT` | L393 | `PostingValidationService.lookupAccount` | Reject **101** `ACCOUNT RECORD NOT FOUND` at L397–L399; reject **102** `OVERLIMIT TRANSACTION` at L410–L412, gated by the **inclusive** `IF ACCT-CREDIT-LIMIT >= WS-TEMP-BAL` at L407; reject **103** `TRANSACTION RECEIVED AFTER ACCT EXPIRATION` at L417–L419, gated by the **inclusive** `>=` at L414 |
| `2000-POST-TRANSACTION` | L424 | `PostTransactionsJob.post` | The three-write unit of work, invoked at L440, L441 and L442 |
| `2500-WRITE-REJECT-REC` | L446 | `PostingValidationService.writeReject` | Writes the reject stream record |
| `2700-UPDATE-TCATBAL` | L467 | `CategoryBalanceService.upsert` | Reads the category balance and selects one of the two branches below |
| **`2700-A-CREATE-TCATBAL-REC`** | **L503** | `CategoryBalanceService.create` | The **create** branch, taken when the category row is new |
| **`2700-B-UPDATE-TCATBAL-REC`** | **L526** | `CategoryBalanceService.update` | The **update** branch, taken when the category row exists |
| `2800-UPDATE-ACCOUNT-REC` | L545 | `PostTransactionsJob.updateAccount` | Applies the balance change to the account master |
| `2900-WRITE-TRANSACTION-FILE` | L562 | `PostTransactionsJob.writeTransaction` | Writes the posted transaction record |

The reject-count exit contract sits outside any paragraph, in the main loop: L229–L231
set the return code to **4** when the reject count is non-zero, which is the soft-warn
tier the target preserves rather than treating as a failure.

> Assumptions: the two category-balance branches **must remain separately
> distinguishable and separately tested** in the target, even though a single upsert
> statement would express the same net effect in less code. The reason is specific:
> [`tests/README.md`](../../tests/README.md) §13 asserts the create-versus-update
> branch **both ways** as a named business rule, so the two paths are separately
> observable to the parity oracle. Collapsing them into one indistinguishable
> statement would not change the stored result, but it would make a divergence in
> either path invisible to the comparison that exists to catch it — the oracle would
> still pass while having stopped testing the thing it names. Note also that
> §13 refers to these paragraphs in abbreviated form as `2700-A-CREATE` and
> `2700-B-UPDATE`; the full declared names are the ones tabulated above.

### 3.2 `CBACT04C` — interest accrual

| COBOL paragraph | Line | Target method | What it does |
|---|---|---|---|
| `1000-TCATBALF-GET-NEXT` | L325 | `CalculateInterestJob` reader | Sequential read; sets end-of-file at L340 — the **only** place it is set |
| `1050-UPDATE-ACCOUNT` | L350 | `InterestCalculationService.flushAccount` | Applies accumulated interest to the account and zeroes the cycle amounts |
| `1100-GET-ACCT-DATA` | L372 | `InterestCalculationService.loadAccount` | Account lookup on control break |
| `1110-GET-XREF-DATA` | L393 | `InterestCalculationService.loadCrossReference` | Cross-reference lookup on control break |
| **`1200-GET-INTEREST-RATE`** | **L415** | `InterestCalculationService.rateFor` | Disclosure-group rate lookup; on status `'23'` at L436 it moves `'DEFAULT'` at L437 and invokes the fallback at **L438** |
| **`1200-A-GET-DEFAULT-INT-RATE`** | **L443** | `InterestCalculationService.defaultRateFor` | The **`DEFAULT`-group fallback** |
| **`1300-COMPUTE-INTEREST`** | **L462** | `InterestCalculationService.monthlyInterest` | The formula at **L464–L465** |
| `1300-B-WRITE-TX` | L473 | `InterestCalculationService.writeInterestTransaction` | Emits the generated interest transaction |
| `1400-COMPUTE-FEES` | L518 | `InterestCalculationService.computeFees` | Preserved as an explicit, documented extension point with no effect |

The formula is declared at L464–L465 as
`COMPUTE WS-MONTHLY-INT = ( TRAN-CAT-BAL * DIS-INT-RATE) / 1200`.

> Trade-offs: the target **multiplies at full precision and only then divides**, with
> an explicit scale and rounding mode, and the operation order is not free to change.
> Dividing first and multiplying second **yields a different number of cents on many
> inputs**, because the intermediate quotient is rounded to the target scale before
> being scaled back up, and that rounding error is then multiplied. The golden
> masters compare posted amounts byte-for-byte, so the reordered form produces a
> parity failure on ordinary data rather than on a contrived edge case. The accepted
> cost is that the target expression is more verbose than the COBOL one-liner and
> cannot be written with an infix operator sequence.

### 3.3 `COPAUA0C` — the authorization consumer

**All twenty-one paragraphs, with the line range each occupies.** The range is the
paragraph label to its own `-EXIT` label, both measured from the source.

| COBOL paragraph | Lines | Target owner | What it does |
|---|---|---|---|
| `1000-INITIALIZE` | L230–L249 | listener container start-up | Retrieves the trigger message at **L233–L236**, takes the queue name from it at **L238**, sets the wait interval at **L242**, then opens and issues the first read. In the target the queue reference is a configured property and the container performs the polling, so no application method corresponds |
| `1100-OPEN-REQUEST-QUEUE` | L255–L286 | listener container start-up | Opens the request queue |
| `1200-SCHEDULE-PSB` | L292–L319 | **no target — retired mechanism** | Schedules the hierarchical database's access block. Performed exactly once per message, from **L443**, so it is reachable and not dead code. Registered in [§5.1](#51-retired-with-an-analogue--function-preserved-mechanism-replaced) |
| `2000-MAIN-PROCESS` | L323–L347 | `AuthorizationRequestListener.onRequest` | One iteration is the method body; the per-message commit at **L335** is the method's transaction boundary |
| **`2100-EXTRACT-REQUEST-MSG`** | **L351–L382** | `CsvAuthCodec.decodeRequest` | Parses the positional payload; `2100-EXIT` at **L382** |
| **`3100-READ-REQUEST-MQ`** | **L386–L434** | **no application method** — the listener container's own receive | The bounded receive with its wait interval. The receive is performed by the messaging container, which is why the target has no method here and why its terminality on failure is structural rather than coded |
| **`5000-PROCESS-AUTH`** | **L438–L468** | `AuthorizationRequestListener.handleNewRequest` | Orchestrates the decision path, guarding the reads at **L448–L458** and the writes at **L463** on the cross-reference having resolved |
| `5100-READ-XREF-RECORD` | L472–L516 | `AccountContextClient.findCardXref` | Cross-reference lookup. The record belongs to the account context, so the target reads it through a port rather than mapping a table here |
| `5200-READ-ACCT-RECORD` | L520–L564 | `AccountContextClient.findAccount` | Account lookup, through the same port |
| `5300-READ-CUST-RECORD` | L568–L612 | `AccountContextClient.customerExists` | Customer lookup. The baseline tests only that the record exists, so the port exposes exactly that |
| **`5500-READ-AUTH-SUMMRY`** | **L616–L643** | `PendingAuthSummaryRepository.findByAccountId` | Reads the pending-authorization summary. Its two-branch outcome at **L627–L640** has no end-of-database branch, so absence is an empty optional and never an error |
| `5600-READ-PROFILE-DATA` | L647–L653 | **no target — an empty extension point** | The paragraph body is `CONTINUE` alone at **L650**. It IS performed, from **L456**, so it is reachable; it simply does nothing, in the same way `CBACT04C`'s fee paragraph does. Preserved as an absence rather than invented as behaviour |
| `6000-MAKE-DECISION` | L657–L734 | `AuthorizationDecisionService.decide` | The approve/decline decision, the available-credit fork and the reason ladder |
| `7100-SEND-RESPONSE` | L738–L782 | `AuthorizationRequestListener.enqueueReply`, then `OutboxPublisher.drain` | Builds and sends the reply; the no-syncpoint put is at **L753–L754** and the put itself at **L758**. The target SPLITS this paragraph in two: the row is written inside the deciding transaction and sent after it commits, which is the whole of [D-5](#d-5--the-reply-published-before-the-decision-is-committed) |
| `8000-WRITE-AUTH-TO-DB` | L786–L794 | `AuthorizationRequestListener.contribute` then its caller's detail save | Performs the two segment writes in order, summary at **L790** then detail at **L791**. The target keeps that order and splits the paragraph across two members, because the summary write is what CONFIRMS the approval the reply then carries: its row count reports whether the headroom was still there, so the decision cannot be settled before it runs |
| `8400-UPDATE-SUMMARY` | L798–L850 | `PendingAuthSummaryRepository.insertSummaryIfAbsent`, `reserveApprovedAuthorization` and `addDeclinedAuthorization` | The upsert. Its insert arm is **L801–L806** and its replace arm **L824–L828**; the target keeps the two arms distinguishable and adds the counters through atomic statements rather than through a written-back instance. The approval statement additionally carries the remaining-headroom test in its own `where` clause, so a second card of one account cannot be admitted against headroom the first already consumed |
| `8500-INSERT-AUTH` | L854–L935 | `PendingAuthDetailRepository.save` | Inserts the detail segment. `insertDetailIfAbsent` on the same repository is the loader's duplicate-tolerant form, not this path's |
| `9000-TERMINATE` | L940–L950 | listener container shutdown | Releases the database access block at **L943–L945** when it was scheduled, then closes the queue |
| `9100-CLOSE-REQUEST-QUEUE` | L953–L979 | listener container shutdown | Closes the request queue |
| **`9500-LOG-ERROR`** | **L983–L1012** | `AuthorizationMessageMapper.ErrorLogEntry` projected onto the structured logger, with `SqsConfig.RethrowingDigestErrorHandler` for the failure itself | The centralised error emission, invoked from **fourteen** call sites. Its severity test at **L1008–L1010** is what makes a critical severity terminal |
| `9990-END-ROUTINE` | L1016–L1024 | exception propagation to the transaction boundary | Terminates and returns at **L1019–L1022**. A task returning normally takes the platform's implicit end-of-task commit, which is why the target's rollback is a difference — registered as [D-D](#d-d--a-failed-segment-write-rolls-the-message-back-rather-than-letting-a-partial-write-stand) |

> Refactoring Rationale: this table previously named **nine target methods and types
> that do not exist in the repository**, and named sixteen of the twenty-one
> paragraphs. Both are corrected together because they are the same failure — a map
> that cannot be followed to a real symbol is indistinguishable from a map that stops
> early. The nine were an `AuthorizationService` type that exists under no name
> anywhere, carrying four methods (`process`, `loadCrossReference`, `loadAccount`,
> `decide`); an `AuthorizationErrorLogger.emit` that likewise exists nowhere; a
> `loadCustomer` on that absent type; `AuthorizationRequestListener.onMessage` and
> `.receive`, where the method is `onRequest` and the receive belongs to the container;
> `PendingAuthSummaryRepository.find`, where the method is `findByAccountId`;
> `PendingAuthDetailRepository.insert`, where this path uses `save`; and
> `OutboxPublisher.publishReply`, where the publisher's public surface is `drain` and
> `purgePublished`. Every name in the table above was checked against the source tree
> before it was written, and the three reads now name the port that owns them rather
> than a service in this context, which is the boundary the layering test enforces.
> Assumptions: the five paragraphs that were missing are `1000-INITIALIZE`,
> `5600-READ-PROFILE-DATA`, `8000-WRITE-AUTH-TO-DB`, `9000-TERMINATE` and
> `9990-END-ROUTINE`. Two of them carry a consequence a reader needs — the
> initialisation is where the wait interval and the dynamic queue name come from, and
> the end routine is where the implicit commit that makes `D-D` a difference happens —
> so omitting them dropped evidence rather than only rows. There is no `5400`, no
> `7000` and no `8100`, `8200` or `8300` paragraph in this program; a reader who infers
> one from the numbering gaps will not find it.

The fourteen call sites of `9500-LOG-ERROR` are at **L282, L316, L429, L500, L512,
L547, L560, L595, L608, L639, L778, L846, L931** and **L975**.

> Assumptions: **the call-site count is fourteen, and a figure of ten is in
> circulation that omits four of them.** The four omitted are the last four in the
> list — L778 the reply-queue put failure, L846 the summary segment write, L931 the
> detail segment write, and L975 the request-queue close. This matters beyond
> bookkeeping: the four dropped sites are precisely the *write and teardown* failure
> paths, so an implementation built from the shorter list would carry error emission
> on every read path and silently omit it on every path that persists or releases
> something. All fourteen line numbers are printed rather than summarised so a reader
> can see which four a shorter figure drops.
> [`observability.md`](observability.md) owns the error-logging mapping and reports
> the same fourteen; [`messaging-contracts.md`](messaging-contracts.md) owns the
> consumer semantics, the wire format and the timing literals.

### 3.4 `COSGN00C` — sign-on

| COBOL paragraph | Line | Target method | What it does |
|---|---|---|---|
| `READ-USER-SEC-FILE` | L209 | `CognitoIdentityService.authenticate` | The credential check. The direct comparison is at **L223**; registered as [D-4](#d-4--the-plaintext-credential-field-is-not-carried-forward) |
| the administrator branch | L232 | client-side navigation to `/admin` | Chosen from the signed group claim rather than from a client-supplied field |
| the user branch | L237 | client-side navigation to `/menu` | As above |
| `POPULATE-HEADER-INFO` | L177 | `ui/src/layout/ScreenHeader.tsx` | The shared two-row title band. Its target owner is a **client-side component, not a service method** — the paragraph paints the screen's own frame, which the target renders in the browser. The clock read at **L179** is registered as [D-7](#d-7--the-header-clock-and-the-zone-it-is-read-in). The band is authored **once** and reused because the baseline shares nothing here: 12 of the 17 screen-painting programs declare this paragraph by this name and the other five do the same work under a `1100-SCREEN-INIT` or `3100-SCREEN-INIT` paragraph, so the target replaces 17 copies with one component |

### 3.5 `PAUDBLOD` — the segment load

Assumptions: the line ranges below are read with **columns 73 to 80 stripped**, because
[`PAUDBLOD.CBL`](../../app/app-authorization-ims-db2-mq/cbl/PAUDBLOD.CBL) carries
eight-digit legacy sequence numbers in those columns. A paragraph-label search that does
not strip them returns nothing at all, which is why the stripping is stated rather than
assumed.

| COBOL paragraph | Line | Target method | What it does |
|---|---|---|---|
| `MAIN-PARA` | L169–L187 | `LoadService.load` | The **two-pass** control flow. Its root loop at **L177**–**L178** runs to exhaustion before its child loop at **L180**–**L181** begins, so the passes are sequential and never interleaved |
| `1000-INITIALIZE` | L190–L215 | *no method* | Opens both files. The target receives streams a caller already owns, so there is nothing to open. Its two clock reads at **L193**–**L194** feed only the `DISPLAY` at **L197** and are stored nowhere, so no date is parameterised — `CURRENT-YYDDD` is never referenced again, and `WS-AUTH-DATE`, `WS-EXPIRY-DAYS` and `WS-DAY-DIFF` are declared and never used |
| `2000-READ-ROOT-SEG-FILE` | L222–L237 | `LoadService.loadSummaries` | Reads one hundred-byte root image and moves it into the summary segment at **L229** |
| `2100-INSERT-ROOT-SEG` | L242–L263 | `LoadService.loadSummaries` | Inserts the root. **Three FLAT independently closed tests**: success at L253–L255, the duplicate status tolerated at **L256**–**L258**, and every other status reaching the abend at **L259**–**L262**. This is the shape the child path does not share, which is what establishes [D-C](#d-c--an-unresolvable-parent-is-reported-where-the-nested-branch-leaves-it-unreported) |
| `3000-READ-CHILD-SEG-FILE` | L269–L289 | `LoadService.loadDetails` | Reads one 206-byte prefixed record. Its numeric guard at **L275** has no `ELSE`; registered as [D-LOAD-PREFIX-REFUSED](#d-load-prefix-refused--an-undecodable-parent-key-is-reported-where-the-guard-has-no-else-branch). Its third read branch at **L287** sets no end flag and does not abend; registered as [D-LOAD-READ-BOUNDED](#d-load-read-bounded--the-load-walk-cannot-fail-to-terminate-where-two-read-branches-suspend-it), whose root-side counterpart is **L235** |
| `3100-INSERT-CHILD-SEG` | L292–L316 | `LoadService.requireParent` | Positions on the child's parent at **L296**–**L299**, terminated by a **period at L299**. **L305** opens the success branch and **L310**'s failure test is nested inside it, closed by one `END-IF.` at **L314**; registered as [D-C](#d-c--an-unresolvable-parent-is-reported-where-the-nested-branch-leaves-it-unreported) |
| `3200-INSERT-IMS-CALL` | L318–L336 | `LoadService.loadDetails` | Inserts the child. The flat three-way shape a second time: success at L326–L328, the duplicate tolerated at **L329**–**L331**, every other status abending at **L332**–**L336** |
| the read at L226 and L272 | L226, L272 | `LoadService.records` | Resolves a stream into whole fixed-length records. The strides are taken from the layout descriptors the two mappers own, never restated |
| the prefix decode | L277, and its FD at **L47** | `LoadService.decodeChild` | Decodes the six-byte `PIC S9(11) COMP-3` parent key through `com.carddemo.common.codec.PackedDecimalCodec`, never as text |
| `4000-FILE-CLOSE` | L341–L356 | *no method* | Closes both files, reporting a bad close without abending. The caller owns the streams, so the target closes neither |
| `9999-ABEND` | L360–L369 | a propagated exception | Sets return code **16** at **L365**. The target raises instead, and the surrounding transaction discards the load |

Assumptions: the job stream supplies **no parameters** to any of these paragraphs, and the
absence is recorded here because a reader looking for a parameter table will otherwise assume
one was omitted. Neither
[`LOADPADB.JCL`](../../app/app-authorization-ims-db2-mq/jcl/LOADPADB.JCL) nor
[`UNLDPADB.JCL`](../../app/app-authorization-ims-db2-mq/jcl/UNLDPADB.JCL) has a `SYSIN` DD —
across the module's five job streams only the purge job does — and `PRM-INFO`, declared at
`PAUDBLOD.CBL` **L129**, is never referenced in its procedure division.

Assumptions: **no `COND=` of either form occurs anywhere in this module's job streams**, and
the finding is stated because condition-code inversion is a headline hazard elsewhere in this
migration. All five streams were read whole with columns 73 to 80 stripped: no step-gating
`COND=`, no record-selecting `INCLUDE COND=` — the two share a keyword — no job-level `IF`,
`THEN` or `ELSE`, no `RESTART=` and no `CHKPT=`. There is nothing to invert and no branch
predicate to derive for this program.

### 3.6 `PAUDBUNL` and `DBUNLDGS` — the two segment unloads

Assumptions: the two programs are tabulated TOGETHER because they perform the same walk of
the same database and differ only in the shape of the child record they emit, so one target
class transcribes both and one table states both line ranges. Splitting them into two
sections would repeat every row to change one column. The same **columns 73 to 80 stripping**
applies as in [§3.5](#35-paudblod--the-segment-load): both
[`PAUDBUNL.CBL`](../../app/app-authorization-ims-db2-mq/cbl/PAUDBUNL.CBL) and
[`DBUNLDGS.CBL`](../../app/app-authorization-ims-db2-mq/cbl/DBUNLDGS.CBL) carry eight-digit
legacy sequence numbers there, and so do the continuation lines of
[`UNLDPADB.JCL`](../../app/app-authorization-ims-db2-mq/jcl/UNLDPADB.JCL) at its L50, L51,
L55 and L56.

| COBOL paragraph | `PAUDBUNL` | `DBUNLDGS` | Target method | What it does |
|---|---|---|---|---|
| `MAIN-PARA` | L157–L170 | L164–L179 | `UnloadService.unload` | One `PERFORM ... UNTIL WS-END-OF-ROOT-SEG = 'Y'` over the root walk, at PAUDBUNL **L163**–**L164** and DBUNLDGS **L172**–**L173**, then the file close |
| `1000-INITIALIZE` | L173–L200 | L182–L194 | *no method* | Opens the two output files. The target receives streams a caller already owns, so there is nothing to open. DBUNLDGS's `OPEN` statements are themselves **commented out** at its L195–L209 |
| `2000-FIND-NEXT-AUTH-SUMMARY` | L207–L247 | L216–L257 | `UnloadService.writeRoot` | Gets the next root and emits it, then walks its children. The root is written FIRST — PAUDBUNL **L233**, DBUNLDGS by way of **L243** — and the child loop follows at their **L235** and **L245** |
| the numeric guard on the account key | **L232** | **L241** | `UnloadService.exportableAccountId` | Encloses BOTH the root write and the child loop, and has no `ELSE`, so a root failing it emits nothing and its children are never read; registered as [D-UNLOAD-SKIP-REPORTED](#d-unload-skip-reported--a-root-the-unload-cannot-attribute-is-counted-and-reported-where-the-guard-passes-it-over-in-silence) |
| `3000-FIND-NEXT-AUTH-DTL` | L253–L284 | L263–L295 | `UnloadService.writeChildren` | Repeats a get-next-within-parent until the children are exhausted, which each detects from a segment-not-found status at PAUDBUNL **L273** and DBUNLDGS **L284** |
| the root record | its FD at **L44**, moved at **L227** | moved at **L236** | `UnloadService.rootRecord` | `01 OPFIL1-REC PIC X(100)` — the segment verbatim with **no** prefix. **Identical in both forms**, because a root's first six bytes already are its key |
| `3100-INSERT-PARENT-SEG-GSAM` | *absent* | L300–L315 | `UnloadService.rootRecord` | Sequential insert of the summary segment alone, at its **L302**–**L304**. Reaches the same hundred-byte image the other program writes |
| `3200-INSERT-CHILD-SEG-GSAM` | *absent* | L319–L334 | `UnloadService.childRecord` | Sequential insert of the detail segment alone, at its **L321**–**L323**, giving the **bare 200-byte** child |
| the child record | its FD at **L45**–**L48**, prefix populated at **L230**, written at **L271** | *its `WRITE` commented out at* **L281** | `UnloadService.childRecord` | The ONE place the two forms differ: a `PIC S9(11) COMP-3` parent key of six bytes ahead of a `PIC X(200)` segment, giving **206**, against the bare **200**. The prefix is encoded through `com.carddemo.common.codec.PackedDecimalCodec`, reached by `PendingAuthDetailMapper.unloadRecordLength`, never as text |
| `4000-FILE-CLOSE` | L289–L304 | L338–L339 | *no method* | Closes both files, reporting a bad close without abending. The caller owns the streams, so the target closes neither. DBUNLDGS's `CLOSE` statements are **commented out** at its L340–L353 |
| `9999-ABEND` | L308–L314 | L357–L363 | a propagated exception | Sets return code **16** at PAUDBUNL **L313** and DBUNLDGS **L362**. The target raises instead |

Assumptions: the **live** authority for the sequential form's two hundred bytes is the database
description and the insert operands, not the record group that appears in `DBUNLDGS.CBL`. That
program's `FILE SECTION` is commented out at its **L42**–**L48** and both `WRITE` statements at
its **L242** and **L281**, so the 206-byte group surviving at its **L53**–**L56** is working
storage used as a DL/I input-output area and written by nothing. The declared geometry is
[`PASFLDBD.DBD`](../../app/app-authorization-ims-db2-mq/ims/PASFLDBD.DBD) **L27**,
`RECORD=(100),RECFM=F`, and
[`PADFLDBD.DBD`](../../app/app-authorization-ims-db2-mq/ims/PADFLDBD.DBD) **L27**,
`RECORD=(200),RECFM=F`; the chain from job to declaration closes because the sequential job's
`PASFILOP` and `PADFILOP` data-definition names at
[`UNLDGSAM.JCL`](../../app/app-authorization-ims-db2-mq/jcl/UNLDGSAM.JCL) **L36** and **L39**
are exactly those descriptions' `DD2=` operands. Reading the commented group as the contract
yields 206 where the truth is 200.

Assumptions: the prefixed form is the **default** and the sequential form an explicit opt-in,
on three findings the job streams settle. Only the prefixed pair is read back — `ROOT.GSAM`
and `CHILD.GSAM` appear at `UNLDGSAM.JCL` **L36** and **L39** and nowhere else in the module,
while `ROOT.FILEO` and `CHILD.FILEO` are written by `UNLDPADB.JCL` **L48** and **L53** and read
by [`LOADPADB.JCL`](../../app/app-authorization-ims-db2-mq/jcl/LOADPADB.JCL) **L36** and
**L38**. Only the prefixed job provisions its own output, `DISP=(NEW,CATLG,DELETE)` with
`UNIT=3390` and `SPACE` at **L49**–**L51** and **L54**–**L56**, against
`DISP=(OLD,KEEP,KEEP)` with no unit, space or device characteristics at `UNLDGSAM.JCL` **L37**
and **L40** — and `OLD` requires the dataset to exist already. And only the prefixed job is
repeatable by construction: it is the module's one multi-step stream, whose `STEP0 EXEC
PGM=IEFBR14` at **L25** deletes both outputs at **L33**–**L36** first.

Assumptions: neither unload takes a **parameter** and neither is a **write**. Neither stream
has a `SYSIN` data definition — across the module's five streams only the purge job does — and
`PRM-INFO`, declared at `PAUDBUNL.CBL` **L119** and `DBUNLDGS.CBL` **L123**, is read only by
statements that are commented out at their **L179** and **L188**. Both run batch-exclusive
against read-only access specifications: `PARM='DLI,PAUDBUNL,PAUTBUNL,,,,,,,,,,,N'` at
`UNLDPADB.JCL` **L38**–**L39** and `PARM='DLI,DBUNLDGS,DLIGSAMP,,,,,,,,,,,N'` at
`UNLDGSAM.JCL` **L26**–**L27**, with `PROCOPT=GOTP` at
[`PAUTBUNL.PSB`](../../app/app-authorization-ims-db2-mq/ims/PAUTBUNL.PSB) **L18** and
[`DLIGSAMP.PSB`](../../app/app-authorization-ims-db2-mq/ims/DLIGSAMP.PSB) **L18** where the
load and purge jobs pass the update-capable `PSBPAUTB`, declared `PROCOPT=AP` at
[`PSBPAUTB.psb`](../../app/app-authorization-ims-db2-mq/ims/PSBPAUTB.psb) **L17**. Both unload
streams also carry ACTIVE database data-definition statements — `UNLDPADB.JCL` **L58**–**L59**
and `UNLDGSAM.JCL` **L42**–**L43** — where the load job's equivalents at `LOADPADB.JCL`
**L40**–**L41** are commented out, which is the signature of a job holding the database itself
rather than reaching it through the online region.

Assumptions: [`DBPAUTP0.jcl`](../../app/app-authorization-ims-db2-mq/jcl/DBPAUTP0.jcl) is **not
a third export shape** and appears in no row above, because it names no application program at
all: its **L16** is `PARM=(ULU,DFSURGU0,DBPAUTP0)`, the vendor's reorganisation-unload utility,
and its output at **L25**–**L29** is a variable-blocked `LRECL=27990` dump. It is the only job
in the module registered with the recovery control datasets, at **L40**–**L42**, and the only
one carrying a utility control statement, at **L34**–**L35**. Its disposition is
[§5.1](#51-retired-with-an-analogue--function-preserved-mechanism-replaced): the managed
backup and snapshot configuration in `infra/modules/aurora-postgresql`. The absence is stated
because the arithmetic invites the wrong conclusion — five job streams beside three load and
unload programs — and there are exactly **two** export shapes.

Assumptions: **no `COND=` of either form occurs anywhere in this module's job streams**, exactly
as [§3.5](#35-paudblod--the-segment-load) records, and the finding covers the unload pair too.
Even the prefixed form's two-step stream has no gate between its delete step and its unload
step, so every step edge in this module is the unconditional success edge.

Where a program's paragraph structure is analysed in depth by a sibling, the
analysis is cited rather than repeated:
[`batch-orchestration.md`](batch-orchestration.md) for the batch chain's step
contracts, [`messaging-contracts.md`](messaging-contracts.md) for the consumer and
payload paragraphs, [`data-model-and-schema-mapping.md`](data-model-and-schema-mapping.md)
for the record layouts every one of these paragraphs reads and writes, and
[`security-and-identity.md`](security-and-identity.md) for the sign-on path.

---

## 4. Non-program artifact coverage

**Every artifact in the baseline either appears as a source in at least one mapping
row or is explicitly listed as retired. There are no silent omissions.** This
section states the disposition of every non-program asset class so that the claim is
checkable.

| Asset class | Count | Disposition |
|---|---|---|
| Copybooks, [`app/cpy`](../../app/cpy) | 30 | **29** map to entities, DTOs, codecs, message catalogs or validators; **[`UNUSED1Y.cpy`](../../app/cpy/UNUSED1Y.cpy) retires with no target** — see [§5.2](#52-retired-with-no-target-at-all--exactly-two) |
| Copybooks, extension trees | 15 | All mapped — IMS segment layouts, MQ payload layouts and Db2 host structures |
| Symbolic map copybooks, [`app/cpy-bms`](../../app/cpy-bms) | 17 | All consumed as DTO field-shape sources |
| BMS mapsets | **21** | All become screen routes |
| JCL members, [`app/jcl`](../../app/jcl) | 38 | **36** map to orchestration states, ETL load steps, infrastructure resources or CI steps; **[`TXT2PDF1.JCL`](../../app/jcl/TXT2PDF1.JCL) retires with no target** and **[`FTPJCL.JCL`](../../app/jcl/FTPJCL.JCL) retires as a submission tunnel** |
| JCL members, extension trees | 8 | Dispositions owned by the services that own their data |
| CICS resource definitions | 4 | Mapped to container services, load-balancer rules, image repositories and least-privilege roles; **the dangling `PROGRAM(COCRDSEC)` entry is documented with no target** — see [§6](#6-the-dangling-programcocrdsec) |
| Seed datasets | 22 | All become ETL reader inputs and post-load verification baselines |
| Control card, procedures | 1 + 2 | Mapped into the ETL load and report steps |
| Assembler, macros | 2 + 2 | **Retired** — no cloud analogue |
| Catalog listing | 1 | **Retired** — a point-in-time listing of a catalog that no longer exists in the target |
| Scheduler definitions | 2 | **Retired as syntax**; the scheduling *intent* is carried forward |
| IMS definitions | 8 | Mapped into the `authorization` schema migration |
| Db2 DDL, DCL | 6 + 3 | Mapped into the `authorization` and `reference` schema migrations |

The owning siblings for the detail behind each row are
[`data-model-and-schema-mapping.md`](data-model-and-schema-mapping.md) for the
copybooks, seed data, IMS definitions and Db2 definitions;
[`design-token-reference.md`](design-token-reference.md) for the mapsets and
symbolic maps; [`batch-orchestration.md`](batch-orchestration.md) for the JCL, the
control card and the procedures; and
[`security-and-identity.md`](security-and-identity.md) plus
[`context-and-container-diagrams.md`](context-and-container-diagrams.md) for the
resource definitions.

### 4.1 `COPAUS2C`: present in neither transaction population

One real, implemented program appears in **neither** the documented inventory **nor**
any transaction definition anywhere in the repository:

* it does **not** appear in the root [`README.md`](../../README.md), so it is absent
  from the 24 documented `Online Components` rows;
* it has **no `DEFINE TRANSACTION`** in any of the four resource definitions;
* its only resource definition is `DEFINE PROGRAM(COPAUS2C)` at
  [`CRDDEMO2.csd`](../../app/app-authorization-ims-db2-mq/csd/CRDDEMO2.csd) **L32**
  — that file declares 3 transactions against 4 programs, and this is the extra one.

It is nonetheless a real program:
[`COPAUS2C.cbl`](../../app/app-authorization-ims-db2-mq/cbl/COPAUS2C.cbl) declares
its function at L5 as **"Mark Authorization Message Fraud"** and carries the fraud
action domain as condition names at L81–L82. Its program stanza names a transaction
identifier, but that transaction names `COPAUS1C` as *its* initial program, so
`COPAUS2C` is reached by transfer of control from the authorization detail screen
rather than by a transaction of its own — which is exactly why it has a program
definition and no transaction definition.

> Assumptions: **this is why the authorization extension tree holds eight programs
> and not seven.** An inventory built from transaction definitions — the natural way
> to enumerate an online application — misses it entirely, and an inventory built
> from the documented component tables misses it too. Only a directory listing finds
> it, and even that undercounts unless the extension match is case-insensitive
> ([§1.1](#11-migration-scope-versus-repository-wide-source-files)). Two independent measurement habits
> therefore have to fail simultaneously for the count to come out right, which is
> why the figure is called out rather than left to the table.
> [`service-catalog.md`](service-catalog.md) owns the population reconciliation.

---

## 5. The retirement register

"Retired" carries two entirely different meanings in this migration, and conflating
them is the failure mode this section is structured to prevent. In the first and much
larger group, **the function is preserved and only the mechanism that carried it is
replaced** — nothing the baseline did stops being done. In the second, the function
itself is not carried forward, and **exactly two artifacts in the whole baseline fall
into that group.**

In every case the baseline artifact remains in the repository, unmodified and
runnable. Retirement is a statement about what the *target* contains, never about
removing anything from the baseline.

### 5.1 Retired with an analogue — function preserved, mechanism replaced

| Baseline artifact | What it does | The analogue that preserves the function |
|---|---|---|
| [`COBSWAIT`](../../app/cbl/COBSWAIT.cbl), driven by [`WAITSTEP.jcl`](../../app/jcl/WAITSTEP.jcl) **L22** (`//WAIT     EXEC PGM=COBSWAIT`, centisecond count via `SYSIN` at L25–L26) | A pure delay between steps, with no business effect | **The orchestration state transition itself.** A state reached through the synchronous run-task integration does not begin until the prior task reports completion, so the dependency *is* the edge and there is nothing left for a delay to wait for. The program has no target artifact; its **function is fully preserved** |
| Resource-definition deployment, [`CBADMCDJ.jcl`](../../app/jcl/CBADMCDJ.jcl) | Installs the CICS resource definitions | Infrastructure-as-code plus CI-driven image deployment |
| The operator quiesce **mechanism**, [`CLOSEFIL.jcl`](../../app/jcl/CLOSEFIL.jcl) and [`OPENFIL.jcl`](../../app/jcl/OPENFIL.jcl) | Closes and reopens files around the batch window | **The behaviour is preserved as read-only flag steps** bracketing the batch chain; the operator-facility mechanism itself is not ported. [`batch-orchestration.md`](batch-orchestration.md) owns the write-path-scoping analysis, including why the bracket covers five of the eight files |
| The scheduler definitions, [`CardDemo.ca7`](../../app/scheduler/CardDemo.ca7) and [`CardDemo.controlm`](../../app/scheduler/CardDemo.controlm) | Declare the job graph and its calendar | **Retired as syntax**; the scheduling **intent** is carried by the managed scheduler. [`batch-orchestration.md`](batch-orchestration.md) owns the curation analysis |
| `IDCAMS BLDINDEX`, in [`TRANIDX.jcl`](../../app/jcl/TRANIDX.jcl) L49 and L52–L54 | Builds the alternate index from the base cluster | **The rebuild step is retired because the target database maintains indexes transactionally. The index itself is not dropped** — the alternate index becomes a real secondary index, and only the step that rebuilt it has nothing left to do |
| The assembler modules and macro library, [`app/asm`](../../app/asm) and [`app/maclib`](../../app/maclib) | Platform services invoked from COBOL, including the wait primitive | Retired — no cloud analogue; the behaviour that reached them is expressed natively |
| The reorganisation unload, [`DBPAUTP0.jcl`](../../app/app-authorization-ims-db2-mq/jcl/DBPAUTP0.jcl) | A whole-database utility unload. Its **L16** is `PARM=(ULU,DFSURGU0,DBPAUTP0)` — the vendor's own reorganisation-unload utility in utility-unload mode, naming **no application program** — and its output at **L25**–**L29** is a variable-blocked `LRECL=27990` dump, none of the 100, 200 or 206 byte application records the two application unloads emit. It is also the only job in the module registered with the recovery control datasets, at **L40**–**L42**, and the only one carrying a utility control statement, at **L34**–**L35** | **The managed store's automated backups and snapshots**, configured in `infra/modules/aurora-postgresql`. It is a **platform-utility** job, so it has no target service method and is **not a third export shape**: [§3.6](#36-paudbunl-and-dbunldgs--the-two-segment-unloads) states the two that exist and forecloses a third. Its **function is preserved** — a full, restorable copy of the database taken on a schedule — which is why it belongs here rather than in [§5.2](#52-retired-with-no-target-at-all--exactly-two) |
| Per-message resource scheduling, `1200-SCHEDULE-PSB` in [`COPAUA0C.cbl`](../../app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl) **L292–L319**, paired with the flag reset at **L337** | Schedules the hierarchical database's access block for the message about to be handled, and clears the flag again immediately after each commit | **The mechanism retires; its consequence is preserved.** Nothing in the target schedules anything — a connection is drawn from the pool for the transaction the handler opens and returned when it closes. What survives is the property the schedule-and-reset pairing produced, that **no resource and no accumulated value crosses from one message to the next**: every field of `AuthorizationRequestListener` is `final` and every per-message value is a local or a parameter. **This row exists because the paragraph is easy to mistake for dead code** — it is performed exactly once per message, from **L443** inside `5000-PROCESS-AUTH`, so it is reachable, and retiring it is a decision about mechanism rather than a removal of something unused |

> Refactoring Rationale: the `IDCAMS BLDINDEX` row is the one a reader most often
> misreads, so it is stated twice in different words. What is retired is the *step*,
> not the *access path*. The three alternate access paths all survive as real
> secondary indexes — the mapping is owned by
> [`data-model-and-schema-mapping.md`](data-model-and-schema-mapping.md) — and every
> browse that exists today still exists. A reader who took "index building is retired"
> to mean "the index is gone" would conclude that three access paths had been dropped,
> which would be a genuine loss of function rather than the change of mechanism it
> actually is.

### 5.2 Retired with no target at all — exactly two

**Exactly two artifacts in the entire baseline retire with no target and no
analogue.** The count is stated as a precise claim because a register that leaves it
vague invites the reader to assume there are more.

| Baseline artifact | Why nothing replaces it | Consequence |
|---|---|---|
| [`app/cpy/UNUSED1Y.cpy`](../../app/cpy/UNUSED1Y.cpy) | A **field-for-field anonymised clone of the security record** — the same six fields at the same widths in the same order, summing to the same 80 bytes — **referenced by no program anywhere in the repository.** Nothing consumes it, so nothing needs to replace it | None. No behaviour depends on it, so its absence from the target removes no capability. [`security-and-identity.md`](security-and-identity.md) notes it alongside the security-record discussion |
| [`app/jcl/TXT2PDF1.JCL`](../../app/jcl/TXT2PDF1.JCL) | The text-to-PDF conversion utility. It invokes a **product that is not part of this repository** (the external exec library named by `SYSEXEC` at L31), so there is nothing to migrate and reproducing its output would mean selecting a different renderer and then owning the difference between two renderings of the same text. **No PDF-generation equivalent is in scope** | **Two consequences, both stated rather than left implicit** — see below |

**The consequences of retiring the text-to-PDF utility.** A bare retirement entry
would hide two real changes, so both are recorded here:

1. **It collapses an edge in the scheduler dependency chain.** In
   [`CardDemo.ca7`](../../app/scheduler/CardDemo.ca7) the statement chain runs
   **`CREASTMT` (L468) → `TXT2PDF1` (L495) → `WAITSTEP` (L522)**. With the
   intermediate conversion gone, the edge from statement creation to the downstream
   wait step **collapses into a single transition** — the chain does not merely lose
   a node, it changes shape. Since the wait step is itself retired with an analogue
   ([§5.1](#51-retired-with-an-analogue--function-preserved-mechanism-replaced)), what
   remains in the target is statement generation followed directly by whatever
   depended on it.
2. **The statement output set is narrower: two formats where the baseline produced
   three.** The target emits plain text and HTML; the baseline additionally emitted
   PDF through this utility. This is disclosed both here and in
   [`batch-orchestration.md`](batch-orchestration.md), so that a reader arriving from
   either direction sees it.

### 5.3 Also retired as mechanisms with no cloud analogue

These are mechanisms rather than functions — the work they carried is done, but not by
anything shaped like them:

* **The submission tunnel** — [`FTPJCL.JCL`](../../app/jcl/FTPJCL.JCL) together with
  the `scripts/remote_*.sh` helpers. Job submission over a file-transfer session has
  no target equivalent; deployment and job start are authenticated API calls.
* **The internal-reader submission path** — the transient-data destination declared in
  [`CARDDEMO.CSD`](../../app/csd/CARDDEMO.CSD) that let an online program submit a
  batch job by writing job control to a queue. The *function* — an online request
  starting a batch run — is preserved as an orchestration start from
  `reporting-service`; the mechanism is not.

### 5.4 Two different populations both described as "no target"

Two documents in this folder both use the phrase "no target", counting **different
populations**, and the reconciliation belongs here because this document is the single
authority for counts.

| Framing | Population counted | Members |
|---|---|---|
| [`service-catalog.md`](service-catalog.md) "two baseline items with no target" | **programs** with no target service | `COBSWAIT`, `COCRDSEC` |
| This register, [§5.2](#52-retired-with-no-target-at-all--exactly-two) | **artifacts of any class** retiring with no target *and no analogue* | [`UNUSED1Y.cpy`](../../app/cpy/UNUSED1Y.cpy), [`TXT2PDF1.JCL`](../../app/jcl/TXT2PDF1.JCL) |

> Assumptions: both figures are two, and they are **not the same two** — a reader who
> assumes one framing is a restatement of the other will conclude that one of the two
> documents is wrong. The two populations differ on both axes. `COBSWAIT` has no
> target *service* but does have an analogue, so it belongs to the catalog's list and
> not to [§5.2](#52-retired-with-no-target-at-all--exactly-two). `COCRDSEC` is not a
> retirement in any sense — it is a resource definition with no implementation behind
> it, so there is no function to retire and nothing was ever built to be replaced
> ([§6](#6-the-dangling-programcocrdsec)). Conversely the two artifacts in
> [§5.2](#52-retired-with-no-target-at-all--exactly-two) are not programs at all, so
> they cannot appear in a program-scoped list. Both statements are correct within
> their stated scope, which is why each is stated with its scope attached.

---

## 6. The dangling `PROGRAM(COCRDSEC)`

The base region defines eighteen programs. **Seventeen have source; one does not.**
`COCRDSEC` is documented here with **no target**, and the evidence is set out in full
so the conclusion is auditable rather than asserted.

**The signals that establish it:**

1. **The definition exists and describes a business function.**
   `DEFINE PROGRAM(COCRDSEC)` sits at
   [`app/csd/CARDDEMO.CSD`](../../app/csd/CARDDEMO.CSD) **L211** with
   `DESCRIPTION(CREDIT CARD SEARCH)` at **L212**. The stanza carries no `TRANSID`
   operand.
2. **It is reachable only through a transaction that describes itself as a developer
   entry point.** `DEFINE TRANSACTION(CDV1)` at **L388** carries
   `DESCRIPTION(DEVELOPER TRANSACTION - 1)` at **L389** and names
   `PROGRAM(COCRDSEC)` at **L390**. A scratch entry point, by its own description,
   rather than a business function.
3. **`CDV1` is absent from the documented inventory.** It is the sole member of the
   base-region-only partition in [§1.2](#12-the-four-distinctly-labelled-resource-populations)
   — one transaction defined in the region and not among the README's 24
   `Online Components` rows — and that single entry is the whole discrepancy between
   the 18-based and 17-based online identities.
4. **No source file exists anywhere in the repository.** Matching all eighteen defined
   program names against every COBOL source file in the tree, case-insensitively,
   returns exactly one program with no source: `COCRDSEC`. **It is the only one of the
   eighteen with nothing behind it.**

**Two measurements that do *not* support the conclusion, recorded so a reader who
takes them does not contradict this document:**

* **The provenance operands do not discriminate.** `COCRDSEC` carries
  `CHANGEAGENT(CSDAPI)` at **L218** and `DEFINETIME(22/03/15 10:11:47)` at **L216**,
  and both look as though they might single the entry out. Measured across all
  eighteen program stanzas, **`CSDAPI` appears on 14 of them** and the definition
  dates spread across **fourteen distinct days** with no dominant cohort — the
  22/03/15 date is itself shared with `COMEN01C`. Neither operand distinguishes this
  definition from its peers, and neither is used as evidence above.
* **The absent `TRANSID` operand does not discriminate either.** **14 of the 18
  program stanzas carry no `TRANSID` operand**, so its absence from this one is the
  majority case rather than an anomaly. Signal 1 records the absence as a fact about
  the stanza, not as evidence.
* **The absent mapset definition is corroborating, not independent.** There is no
  `DEFINE MAPSET(COCRDSEC)`, which is consistent with the four signals above but is a
  consequence of the same absence rather than a separate finding.

> Assumptions: the conclusion that this is a developer artifact rests on the four
> signals **together** — a described definition, reachable only from a self-described
> developer transaction, absent from the documented inventory, and with no
> implementation — and on no single one of them. Signal 4 is the decisive one and is
> the only one that is individually sufficient: **the target has no artifact because
> there is no source to migrate.** The two negative results are recorded because they
> are the measurements a reader would naturally reach for first, and both look
> promising until they are actually taken; publishing the conclusion without them
> would leave a reader who measures `CHANGEAGENT` believing this document had
> overlooked a contradiction. [`service-catalog.md`](service-catalog.md) reaches the
> same conclusion independently and carries the same caveat about provenance
> operands.

---

## 7. The divergence register

This is the complete register of every place the target deliberately behaves
differently from the baseline. It is the artifact that discharges the standing
constraint *"document any intentional behavioral changes"*, and it is the only such
register — eight sibling documents defer to it.

**Every entry uses the identical six-part shape**, and the shape does not vary:

> **ID** · **Baseline behaviour**, with exact path and line · **Target behaviour** ·
> **Category** · **Why the difference is accepted** · **Where it is verified.**

The entries in [§7.4](#74-divergences-claimed-by-shipped-code) carry one further part,
**Files**, naming the source files that implement the divergence.
Refactoring Rationale: those entries exist because shipped source cites them, so the
lookup has to work in both directions -- from a comment to its entry, and from an entry
back to every file that claims it. Without the file list the reverse direction is a
repository-wide search for a phrase, which is how the entries came to be missing in the
first place. The first six entries predate any code citing them by identifier and are
left at six parts rather than being back-filled, because inventing a file list for an
entry no file names would assert a link that does not exist.

**The register describes differences, not repairs.** For each entry it states what the
COBOL does, cites the line, states what the target does, and labels the pair a
**documented divergence**. No entry asserts that any baseline source was edited,
because none was: the three limitations recorded in
[§7.1](#71-divergences-arising-from-the-three-known-baseline-limitations) are left
exactly as they are, and the register is precisely how the resulting difference is
made honest instead of silent.

### 7.1 Divergences arising from the three known baseline limitations

The three limitations documented at [`tests/README.md`](../../tests/README.md) §1.1
each produce one registered divergence.

#### D-1 — the export/import record-key declaration

* **Baseline behaviour.** [`CBEXPORT.cbl`](../../app/cbl/CBEXPORT.cbl) **L68** and
  [`CBIMPORT.cbl`](../../app/cbl/CBIMPORT.cbl) **L40** both declare
  `RECORD KEY IS EXPORT-SEQUENCE-NUM` on their file-control entry. That field exists
  only in working storage — the copybook that declares it is brought in at
  `CBEXPORT.cbl` **L96** and `CBIMPORT.cbl` **L113**, in both cases *after*
  `WORKING-STORAGE SECTION.` (at L94 and L111 respectively) — and **not** in the file
  description's record, which is the unstructured
  `01  EXPORT-OUTPUT-RECORD  PIC X(500).` at `CBEXPORT.cbl` **L92** and the
  corresponding `01  EXPORT-INPUT-RECORD  PIC X(500).` at `CBIMPORT.cbl` **L79**.
  The consequence is that only **ten of the twelve** batch programs build and run
  under the open-source compiler; the pair is classified as known-unsupported, and
  the export/import integration test is **skipped** with that reason.
  [`tests/README.md`](../../tests/README.md) §1.1 records that **no compiler flag can
  fix** it and that the reference-only principle forbids editing the source.
* **Target behaviour.** `ExportJob` and `ImportJob` key the record on the sequence
  number as a real field of the record, so the 500-byte packed round-trip is
  expressible and runnable.
* Refactoring Rationale: the target moves the key into the record contract rather
  than preserving a declaration the selected compiler cannot build.
* **Why the difference is accepted.** The target has to be runnable, and this is the
  one divergence where declining it would mean shipping two jobs that cannot execute
  at all — not a behavioural difference but an absence of behaviour. The divergence is
  narrowly scoped to the key declaration; every field offset, the 500-byte record
  length and the packed-decimal encoding are preserved unchanged, so the bytes the
  target writes are the bytes the baseline's own record layout describes.
* **Where it is verified.** Codec round-trip tests over the export record layout, and
  [`tests/README.md`](../../tests/README.md) §1.1, which documents the baseline
  limitation this diverges from. Because the baseline pair does not run, this is the
  one divergence with **no golden master to compare against** — the record layout is
  the oracle instead of a produced output.

#### D-2 — the two unchecked statement tables

* **Baseline behaviour.** [`CBSTM03A.CBL`](../../app/cbl/CBSTM03A.CBL) declares **two
  independent tables**, neither bounds-checked:
  `05  WS-CARD-TBL OCCURS 51 TIMES.` at **L226**, containing a nested
  `10  WS-TRAN-TBL OCCURS 10 TIMES.` at **L228**; and separately
  `05  WS-TRN-TBL-CTR OCCURS 51 TIMES.` at **L232**. These are the only three
  `OCCURS` clauses in the program. [`tests/README.md`](../../tests/README.md) §1.1
  records the two independent measured consequences: a single card renders up to
  **512** transactions, but the **513th** overruns the inner same-card table and
  faults (`F-STMT-INNER-OVERFLOW`); separately, up to **51 distinct cards** render,
  but the **52nd** overruns the outer card table and faults
  (`F-STMT-OUTER-OVERFLOW`).
* **Target behaviour.** `StatementService` uses dynamically sized collections with
  **no fixed arity**, so neither threshold exists and no bound has to be checked.
* Refactoring Rationale: the target replaces input-bounded working-storage tables
  with collections sized by the input they represent.
* **Why the difference is accepted.** The target has no fixed-size table to overrun,
  so the divergence is an absence of a limit rather than a change to any rendered
  output: within both baseline bounds the two implementations produce the same
  statements. The fixtures of the existing suite stay under **both** limits, so the
  parity comparison is unaffected by the divergence.
* **Where it is verified.** Statement grouping and output tests, and
  [`tests/README.md`](../../tests/README.md) §1.1 for the two measured thresholds.

> Assumptions: **there are two thresholds, they are independent, and they arise from
> two different tables — there is no single combined transaction limit.** A single
> transaction-count figure is in circulation and it conflates the two: it takes the
> outer table's *card* arity and reports it as a *transaction* count, which
> understates the same-card limit by an order of magnitude and mislabels the fault as
> a transaction-volume problem when the outer overrun is really a distinct-card
> problem. The two must never be collapsed into one number, because an implementer
> sizing a fixture against the conflated figure would size it against the wrong axis
> entirely.

#### D-3 — the final-account interest flush

* **Baseline behaviour.** [`CBACT04C.cbl`](../../app/cbl/CBACT04C.cbl) accrues
  interest with a control break on account change: the break test is at **L194**, and
  on a break it applies the accumulated total through
  `PERFORM 1050-UPDATE-ACCOUNT` at **L196**. A final-flush path is written at
  **L219–L220** — the `ELSE` of `IF END-OF-FILE = 'N'`, performing the same update
  paragraph. That path is **not reached**: the loop is
  `PERFORM UNTIL END-OF-FILE = 'Y'` at **L188**, which tests before each iteration,
  and `END-OF-FILE` is assigned `'Y'` at exactly one place — **L340**, inside
  `1000-TCATBALF-GET-NEXT`. Once the read reports end of file, the loop's own test at
  L188 terminates the loop before the body can be re-entered, so the body never
  executes with `END-OF-FILE = 'Y'`. **The accumulated interest for the last account
  group is therefore never applied.** `1050-UPDATE-ACCOUNT` at **L350–L370** is what
  does not run for that group: it adds the running total to the current balance,
  zeroes the cycle credit and debit amounts, and rewrites the account record.
* **Target behaviour.** `CalculateInterestJob` flushes the final account group when the
  input is exhausted, applying the same calculation and the same account update as
  every prior control break.
* Refactoring Rationale: the target makes the program's written final-flush intent
  reachable for the final account group.
* **Why the difference is accepted.** The surrounding code states the intent
  unambiguously — the final-flush path is written, it simply cannot be reached — and
  every other account group in the same run receives the update, so declining the
  divergence would mean deliberately treating the last group differently from all of
  its peers on a rule the program itself applies uniformly. The divergence is confined
  to *whether* the last group's update runs; the formula at **L464–L465**, the
  `DEFAULT`-group fallback at **L415**/**L438**/**L443**, the generated interest
  transaction and the field-by-field account update are all preserved exactly.
* **Where it is verified.** Interest parity tests including a final-account case, with
  the multiply-then-divide order asserted separately
  ([§3.2](#32-cbact04c--interest-accrual)).

### 7.2 Divergences owed to this register by its siblings

#### D-4 — the plaintext credential field is not carried forward

* **Baseline behaviour.** [`app/cpy/CSUSR01Y.cpy`](../../app/cpy/CSUSR01Y.cpy) **L21**
  declares `05 SEC-USR-PWD  PIC X(08).` — an eight-character credential held in the
  clear within the 80-byte security record — and
  [`app/cbl/COSGN00C.cbl`](../../app/cbl/COSGN00C.cbl) compares it directly at
  **L223** (`IF SEC-USR-PWD = WS-USER-PWD`), inside the `READ-USER-SEC-FILE` paragraph
  that begins at L209.
* **Target behaviour.** The field is **not carried forward at all.** Identity moves to
  a managed user pool; the target's user table keeps only a subject reference
  alongside the identity and role attributes, and carries **no credential column**.
  Seed users are created at provisioning time with generated credentials written to a
  managed secret store, so no credential value appears in source.
* **And it is not decoded on the way there.** The absence of a destination column is a
  property of the schema and says nothing about the migration path, which still reads
  the 80-byte record the eight bytes sit inside. So the field is marked **suppressed**
  on its field descriptor in
  [`layouts.py`](../../data-migration/src/carddemo_migration/copybook/layouts.py): the
  span stays declared, so the two fields after it keep their offsets and the record
  geometry check still holds, and the bytes are withheld from every decoded record and
  every rendered diagnostic the ETL produces. It is the only suppressed field in the
  registry, and `data-migration/tests/test_reader_factory.py` asserts that count.
* Refactoring Rationale: the target replaces direct cleartext comparison with a
  managed identity boundary that carries no credential column.
* Refactoring Rationale: this entry described only the destination for as long as the
  suppression lived inside one hand-written reader module, where the layout-driven
  reader the ETL command line actually builds could not see it — so the path this entry
  is silent about was, measurably, publishing the credential. The marker moved onto the
  shared descriptor and this bullet was added, because an entry that registers a
  divergence in the schema alone leaves a reader with no way to check the path.
* **Why the difference is accepted.** **This is the one place parity is explicitly
  declined,** and it is declined deliberately rather than as a side effect. Preserving
  the behaviour would mean carrying a cleartext credential column into a new datastore
  and re-implementing a direct comparison against it — the one change the migration
  will not make, because the constraint *"no secrets committed to repository"* and a
  cleartext credential column cannot both hold. The user-visible surface is preserved:
  the three sign-on outcomes and their exact message text carry across unchanged, so a
  user sees the same messages in the same situations.
* **Where it is verified.** Schema tests asserting that no credential column exists,
  and sign-on tests asserting the three outcomes by stable identifier.
  [`security-and-identity.md`](security-and-identity.md) owns the full treatment,
  including the identity mapping and the anonymised-clone note.

#### D-5 — the reply published before the decision is committed

* **Baseline behaviour.** In
  [`COPAUA0C.cbl`](../../app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl) the
  consumer **publishes its reply before it persists or commits the decision**, and the
  paragraph order is what establishes that rather than the line order. Inside
  `5000-PROCESS-AUTH` the decision is made at **L459**, the reply is put at **L461**
  (`PERFORM 7100-SEND-RESPONSE`, whose no-syncpoint `MQPUT1` is at **L753**–**L758**),
  and the authorization is written only afterwards at **L464**
  (`PERFORM 8000-WRITE-AUTH-TO-DB`, guarded by `IF CARD-FOUND-XREF` at **L463**).
  `5000-PROCESS-AUTH` is itself performed at **L330**, ahead of the
  `EXEC CICS SYNCPOINT` at **L335**. So the put, the write and the commit are three
  separate events in that order, and none of them is part of one recoverable unit with
  the others. A failure after the put and before or during the write or the commit
  leaves **a reply on the queue that no committed row accounts for** — and because the
  get at **L389** is a `MQGMO-NO-SYNCPOINT` destructive get, the request has already
  been consumed at-most-once and cannot be presented again to re-derive the decision.
  Assumptions: reading L335, L753 and L758 in numeric order suggests commit-then-publish,
  which is the opposite of the executed order; the paragraph numbers L330, L461 and L464
  are what settle it, and they are cited here for exactly that reason.
* **Target behaviour.** The reply is written to an outbox row **inside the same
  transaction** as the authorization decision and published from that row afterwards.
  A reply can therefore never precede the decision it reports, and because the row
  survives a publish failure and is retried, a reply exists for every committed
  decision.
* Refactoring Rationale: the target inverts the publish-then-persist ordering and puts
  the reply's durability inside the decision's transaction, so the single outbox row
  closes both directions of the disagreement with one mechanism.
* **Why the difference is accepted.** The window is observable to the requester rather
  than internal: in the baseline the caller can hold an authorization answer that the
  authorizer's own data never recorded, and no retry can reconcile it because the
  request was already consumed. Preserving the window would mean preserving a state in
  which the two endpoints permanently disagree about whether a decision was made. The
  wire format, the field order, the delimiter, the correlation identity and the reply
  routing are all preserved exactly, so nothing about the reply *as a message* changes
  — only when it is sent relative to the commit, and the guarantee that it is sent.
* **Where it is verified.** `OutboxPublisherTest` asserts that a row is published only
  from committed state, that a failed publish leaves the row pending with its attempt
  counter advanced, and that later rows in the same ordering group stay pending behind
  it. [`messaging-contracts.md`](messaging-contracts.md) owns the consumer semantics and
  records that the outbox is a target obligation rather than an existing baseline
  behaviour.

#### D-6 — the distributed commit is eliminated, not emulated

* **Baseline behaviour.** The authorization extension holds its pending-authorization
  summary and detail in IMS segments and its fraud rows in Db2, and a single logical
  authorization spans both, so committing one authorization requires a distributed
  commit coordinated across two resource managers.
* **Target behaviour.** The summary, detail, fraud and outbox data all live in **one**
  schema, so the same logical unit of work is **a single local transaction**. The
  distributed commit is **removed, not emulated** — there is no coordinator, no
  two-phase protocol and no equivalent construct in the target.
* Refactoring Rationale: co-locating the data replaces distributed coordination
  with a local transaction while preserving atomicity.
* **The resource definitions date the two-manager topology, and it is not the
  original one.** Four `DEFINETIME` stamps in
  [`CRDDEMO2.csd`](../../app/app-authorization-ims-db2-mq/csd/CRDDEMO2.csd) order it:
  `DEFINE DB2ENTRY(AWS01PLN)` is stamped `22/11/27 19:11:50` at **L73**, `COPAUS1C` is
  stamped `23/03/13 15:32:12` at **L30**, `COPAUS2C` is stamped `23/03/24 11:15:11` at
  **L37**, and the `DB2TRAN(CPVDTRAN)` that attaches Db2 to transaction `CPVD` is
  stamped `23/03/24 11:16:32` at **L77** — the same day as the program it serves and
  **81 seconds after it**. Before `COPAUS2C` existed, `CPVD` therefore had no Db2
  attachment at all and was a purely hierarchical transaction. Each half is provable
  by census as well:
  [`COPAUS1C.cbl`](../../app/app-authorization-ims-db2-mq/cbl/COPAUS1C.cbl) contains
  **no `EXEC SQL`** in its 604 lines, and
  [`COPAUS2C.cbl`](../../app/app-authorization-ims-db2-mq/cbl/COPAUS2C.cbl) contains
  **no `EXEC DLI`** and no program communication block in its 244 lines — and no
  `EXEC CICS SYNCPOINT` either, its only three monitor calls being the two clock
  requests at **L91** and **L95** and the return at **L218**, so it returns
  uncommitted and both writes hang on the caller's syncpoint at **L557**–**L558**.
  The second resource manager is an incremental accretion onto a single-manager
  transaction, so one schema **returns** the action to the one unit of work it began
  as rather than redesigning it.
* **Why the difference is accepted.** Co-locating the data makes the atomicity
  requirement expressible with strictly weaker machinery, and no observable state is
  added: the operation is atomic before and after, so a reader of the data can never
  tell which mechanism produced it. **Note that *exposing* distributed transactions is
  explicitly out of scope ([§10](#10-out-of-scope-including-the-baselines-own-stated-future-work)),
  and this entry is the opposite of that** — it records the removal of a distributed
  commit, not the exposure of one.
* **Where it is verified.** In the fraud path, by
  `services/authorization-service/src/test/java/com/carddemo/authorization/service/FraudMarkingServiceTest.java`,
  whose `aFailureOfTheSecondWriteRollsTheFirstBackWithIt` forces the SECOND write to
  fail after the first is staged and asserts both that the failure propagates — which
  is what marks the transaction for rollback — and that the operation carries a single
  `@Transactional` declaration, since propagation only rolls the first write back if
  that write shared the boundary. Assumptions: the annotation is read reflectively
  because a unit test with repository doubles has no real transaction to observe; the
  boundary's runtime effect is asserted against a live engine by this module's
  `*RepositoryIT`. [`messaging-contracts.md`](messaging-contracts.md) and
  [`data-model-and-schema-mapping.md`](data-model-and-schema-mapping.md) own the
  transport and schema halves respectively.

#### D-7 — the header clock and the zone it is read in

* **Baseline behaviour.** Every screen paint reads one clock in one zone.
  [`app/cbl/COSGN00C.cbl`](../../app/cbl/COSGN00C.cbl) declares
  `POPULATE-HEADER-INFO` at **L177** and its first statement is
  `MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA` at **L179**; the paragraph is
  reached from `SEND-SIGNON-SCREEN` at **L145** through
  `PERFORM POPULATE-HEADER-INFO` at **L147**, immediately before the
  `EXEC CICS SEND MAP` that paints the screen. `FUNCTION CURRENT-DATE` returns the
  **CICS region's** local date and time, so every terminal attached to a given
  region read the same wall clock in the same zone whatever the operator's own
  machine said. The captured value is reformatted through
  [`app/cpy/CSDAT01Y.cpy`](../../app/cpy/CSDAT01Y.cpy) — `WS-CURDATE-MM-DD-YY` at
  **L30–L35** produces `MM/DD/YY` and `WS-CURTIME-HH-MM-SS` at **L36–L41** produces
  `HH:MM:SS`, each eight characters with literal separators held in `FILLER` — and
  moved into the mapset's two right-hand slots;
  [`app/bms/COSGN00.bms`](../../app/bms/COSGN00.bms) **L47–L51** sizes `CURDATE` at
  `LENGTH=8`, `POS=(1,71)`, `INITIAL='mm/dd/yy'`. **This is not one program's
  habit.** 17 of the 18 members of [`app/cbl`](../../app/cbl) matching `CO*.cbl`
  read the clock this way — every one that paints a screen; the single exception is
  `COBSWAIT`, the retired batch wait utility, which paints none.
* **Target behaviour.** `ui/src/layout/ScreenHeader.tsx` renders the same two
  formats in the same two slots and takes the instant as an **optional prop**. Every
  one of the four production call sites now supplies it: `ui/src/api/serverClock.ts`
  derives the instant from the service's own response, `ui/src/hooks/useServerInstant.ts`
  hands it to a screen, and each screen passes it as `now={paintedAt}`. **The CLOCK
  substitution is therefore closed** — the displayed instant is the service's, not
  the browser's, so two operators reading one record across a midnight boundary read
  the same date.
* **What remains.** The **ZONE** substitution. The instant is server-derived but is
  formatted in the **browser's** zone, because the two eight-character slots carry no
  zone designator and nothing in the response conveys the service's own zone. One
  substitution remains rather than two, and the divergence is narrowed to it.
* Trade-offs: the remaining divergence is bounded to formatting rather than to the
  value, and the fallback path that reads the browser's clock survives in the
  component for a caller that renders it in isolation — no production caller takes
  it, and `ui/src/layout/screenHeaderClock.test.tsx` holds all four call sites to
  supplying the prop, so the fallback cannot silently return.
* Refactoring Rationale: this entry previously recorded that the shell "is expected
  to supply" the instant and that omitting it produced two substitutions. The
  expectation was met by no caller at the time it was written, which is what made the
  divergence real; it is met by every caller now, so the entry records one
  substitution rather than two.
* **Why the difference is accepted.** **There is no region clock left to read.** The
  baseline's single clock was a property of the single region every terminal attached
  to; the target has no region but a horizontally-scaled set of stateless handlers —
  the shape that the state elimination in
  [§7.3](#73-structural-divergences-that-are-not-defects) is what makes possible — so
  no one machine's wall clock is the authority any more, and the single-clock
  property can be restored only by having the *service* supply the instant, which is
  precisely what the prop exists for and what every production caller now does. The
  consequence that remains is narrower and is stated rather than hidden: the instant
  is the service's but its rendering is the browser's, so two operators in different
  zones can read the same instant as two different local times in the
  eight-character slots. The slots carry no zone designator, which is the baseline's
  own shape, so neither operator can tell which zone they are reading. Rendering the
  slots **blank** until a caller supplies an
  instant was the alternative and was rejected, because the baseline never showed an
  empty date, so a blank slot trades a small documented inaccuracy for a visible
  absence. Everything except the clock and the zone is preserved: the two formats,
  their separators, their widths and their positions are carried across verbatim, so
  the *shape* of what is rendered never differs — only which clock produced it.
* **Where it is verified.** Component tests render the band with an **injected**
  instant and assert both formats, so the format contract is asserted independently
  of any clock; the default path is verified by the deliberate **absence** of an
  assertion about it, because a test asserting a wall-clock value would be asserting
  the test runner's clock rather than the component's behaviour.
  `ui/src/layout/screenHeaderClock.test.tsx` additionally reads each of the four
  screen sources and asserts that each composes the band AND passes the prop, which
  is what keeps the closed half of this divergence closed. Like
  [D-1](#d-1--the-exportimport-record-key-declaration) this divergence has **no
  golden master to compare against** — the online programs cannot be run end to end
  without a CICS runtime ([`tests/README.md`](../../tests/README.md) §1.1), so the
  source's own format declarations in `CSDAT01Y.cpy` and the mapsets are the oracle
  instead of a produced output. The band's **typographic** treatment is owned by
  [`design-token-reference.md`](design-token-reference.md) §4.2, which resolves the
  title band to a heading token; the two **formats** are owned by no sibling and are
  therefore cited above from the copybook directly, because a working-storage
  reformatting group is a presentation-layer field layout and the schema document
  maps persisted records.

> Assumptions: **the paragraph name is not the population.** Grepping for
> `POPULATE-HEADER-INFO` finds **12** programs, not 17, and a reader who stopped
> there would register this divergence against two thirds of the screens it actually
> affects. The remaining five — `COACTUPC` (**L2668**), `COACTVWC` (**L431**),
> `COCRDLIC` (**L642**), `COCRDSLC` (**L427**) and `COCRDUPC` (**L1052**) — do the
> same work in a `1100-SCREEN-INIT` or `3100-SCREEN-INIT` paragraph instead. The
> measurable surface that covers all 17 is therefore the clock call itself,
> `FUNCTION CURRENT-DATE`, not the paragraph that contains it:
>
> ```bash
> # WHAT: the 17 screen-painting programs that read the region clock, and the 12
> #       that happen to name the paragraph POPULATE-HEADER-INFO.
> # WHY : Assumptions: the two figures are different and only the first is the
> #       divergence's population. Reporting the paragraph count as the program
> #       count understates it by five, and the five it drops are the account and
> #       card screens - the densest ones in the target.
> grep -rl 'FUNCTION CURRENT-DATE' app/cbl/CO*.cbl | wc -l   # 17
> grep -rl 'POPULATE-HEADER-INFO'  app/cbl/CO*.cbl | wc -l   # 12
> ```
>
> Assumptions: **the two slots are the same width in the copybook but not in every
> mapset.** Both reformatting groups are eight characters, and `CURDATE` is declared
> `LENGTH=8` at **L47** of all 17 base mapsets — but `CURTIME` is declared `LENGTH=8`
> at **L70** of 16 of them and `LENGTH=9` in
> [`COSGN00.bms`](../../app/bms/COSGN00.bms) alone, where its `INITIAL` operand is
> the nine-character `'Ahh:mm:ss'`. The eight-character COBOL value is therefore
> left-justified into a nine-character slot on the sign-on screen only. It is
> recorded because a reader checking "eight characters" against that one mapset would
> find a 9 and conclude the figure above is wrong, when in fact the copybook value is
> eight and the outlier is the slot.

### 7.3 Structural divergences that are not defects

These are recorded separately so that a reader does not mistake a deliberate design
change for a baseline limitation. None of them arises from anything being wrong; each
is a change in how a behaviour is expressed, and each carries its own category.

**Pseudo-conversational state is eliminated.** The session structure at
[`app/cpy/COCOM01Y.cpy`](../../app/cpy/COCOM01Y.cpy) **L19–L44** decomposes into four
different target mechanisms: the navigation fields become client-side router history,
the identity fields become signed token claims, the selection fields become request
path parameters, and the re-entry discriminator becomes **nothing at all** — a
stateless handler has no first-entry-versus-re-entry distinction to make.
Refactoring Rationale: the identity half is a strengthening rather than a
translation: in the baseline the structure is storage the client echoes back, whereas
a signed claim cannot be asserted by the client at all. The elimination of the
re-entry discriminator has one visible consequence — the field-highlight behaviour is
gated on re-entry in the baseline, so in the target it is driven purely by the
response body. Owned by
[`context-and-container-diagrams.md`](context-and-container-diagrams.md), with the
presentational half in [`design-token-reference.md`](design-token-reference.md).

**Optimistic concurrency is expressed natively.** `COACTUPC` already snapshots a
complete before-image of the record and compares it before rewriting, which *is*
optimistic concurrency; the target expresses the same check as a version column with
a conflict response. Refactoring Rationale: **the pattern is not new — it
is already in the COBOL** — so this is a change of expression, not of behaviour, and
the data-changed condition remains observable to the user in the same situations.

**Browse becomes keyset pagination.** The browse verbs with the cursor key carried in
the session structure become a single keyset-paginated query, with the page envelope
carrying the first key, the last key and whether a next page exists.
Trade-offs: offset pagination was the alternative and was **rejected
because it skips and repeats rows under concurrent inserts** — a row inserted before
the cursor shifts every later row's offset, so a reader paging forward misses one and
sees another twice. Browse-by-key has no such behaviour, so offset paging would have
changed observable behaviour that the baseline does not have. Owned by
[`data-model-and-schema-mapping.md`](data-model-and-schema-mapping.md).

**The posting unit of work stays a single ACID commit.** Posting writes the
transaction, the category balance and the account together, and the target keeps that
atomic by giving the batch role narrowly-scoped cross-schema write grants rather than
splitting the work. Trade-offs: a **saga was named and rejected**: it
would replace one atomic commit with a sequence of committed steps plus compensating
reversals, introducing observable intermediate states — a posted transaction with an
unposted balance — that do not exist in the baseline and that the golden masters would
correctly flag as a parity failure. The accepted cost is one documented exception to
schema-per-service ownership. Owned by
[`service-catalog.md`](service-catalog.md) and
[`batch-orchestration.md`](batch-orchestration.md).

**The payment unit of work stays a single ACID commit, by the same exception.**
[`app/cbl/COBIL00C.cbl`](../../app/cbl/COBIL00C.cbl) writes the payment row at
**L233**, computes the reduced balance at **L234** and rewrites the account master at
**L235**, all inside one CICS task, so the implicit task-end syncpoint commits both
effects together. The target keeps that atomic the same way posting does: the ledger
role holds `USAGE` on the `account` schema and `SELECT, UPDATE` on `account.accounts`
by name — section **4b** of
[`data-migration/sql/V0__schemas_and_roles.sql`](../../data-migration/sql/V0__schemas_and_roles.sql)
— and `AccountBalanceRepository` in the ledger context issues the balance statement on the
paying transaction's own connection. Refactoring Rationale: **this replaces an HTTP
call to the account context, which had two independent defects** and is recorded here
because the replacement is the second exception to schema-per-service ownership and a
reader meeting it should not have to infer why. The first defect was that the call was
unroutable — the account context publishes no payment operation, and the internal token
carries an account **read** scope only — so a deployed payment could not complete at
all. The second is the one that decides it: a local transaction cannot enlist a remote
write, so the reference's single syncpoint could not be reproduced while the change was
remote, and the state that leaked through was a reduced balance with no payment row
against it — money moved with nothing recording that it moved. Publishing the missing
endpoint would have fixed only the first. Trade-offs: a saga with a compensating
reversal, and an event published for the account context to apply, were both named and
rejected for the reason the posting paragraph above gives — each introduces an
observable half-applied state the baseline does not have. The accepted cost is bounded
three ways: one table, two privileges, and a statement that advances the same `version`
column the account context locks on, so that context's optimistic check still bites.
Proven against a real engine by
`services/transaction-service/src/test/java/com/carddemo/transaction/repository/BillPaymentAtomicityIT.java`.
Owned by [`service-catalog.md`](service-catalog.md) and
[`security-and-identity.md`](security-and-identity.md).

**Three misspelled baseline field names are spelled correctly in target column
names.** `ACCT-EXPIRAION-DATE`, `CARD-EXPIRAION-DATE` and
`PA-MERCHANT-CATAGORY-CODE` become `expiration_date` on the account table,
`expiration_date` on the card table and `merchant_category_code` on the
pending-authorization detail table. Refactoring Rationale: **these are
target naming decisions, not edits to the copybooks** — the copybooks keep their
declared spellings, and the lineage is recorded so that a reader tracing a column back
to a copybook is never left guessing. The third differs in kind from the first two,
because that misspelling reaches persisted schema definitions rather than only source;
[`data-model-and-schema-mapping.md`](data-model-and-schema-mapping.md) owns the mapping
and the cost analysis.

**The field-error highlight is driven by the response body.** The baseline moves an
error colour into a field's attribute and a literal marker into a blank field, gated
on the pseudo-conversational re-entry flag. The target renders the same condition from
a structured per-field error array in the response, including the marker for the blank
case. Refactoring Rationale: the gate has no target equivalent because the
flag it reads is the discriminator eliminated above, so the driving input necessarily
changes even though the rendered outcome does not. Owned by
[`design-token-reference.md`](design-token-reference.md).

**`CBTRN01C` has no JCL driver in the baseline and is migrated regardless.** No member
of [`app/jcl`](../../app/jcl) executes it; only the integration test drives it.
Assumptions: it is migrated because the program exists, implements
daily-transaction preflight and is reachable in the baseline through the test harness,
so treating the missing driver as evidence that the function is unwanted would drop a
real capability on the strength of an absence. Owned by
[`batch-orchestration.md`](batch-orchestration.md), which places it as a state of the
batch chain.

**Three of the authorization consumer's reads cross a context boundary and are
authenticated as a workload.** In the baseline, `5100-READ-XREF-RECORD`,
`5200-READ-ACCT-RECORD` and `5300-READ-CUST-RECORD` — mapped to target methods in
[§3.3](#33-copaua0c--the-authorization-consumer) — are `EXEC CICS READ` verbs issued
inside the same region against files the same region owns, so they present no
credential and cannot be refused for lacking one. In the target those three records are
owned by the **account** context, so the same three reads become calls across a service
boundary, and each carries a credential naming the calling **workload** rather than a
user: the queue message that triggers them names a card, an amount and a merchant, and
carries no user for a token to be minted from or forwarded on behalf of.
Refactoring Rationale: the identity is the workload's own precisely because there is no
user in the input — an authorization arriving from a point of sale is not a session, so
authenticating it as one would require inventing a principal the baseline never had.
Trade-offs: this introduces a refusal mode with no baseline analogue. A read that the
baseline could fail only by not finding a record can now also fail by presenting no
credential, an expired one, or one bound to a different method or path, and each of
those is answered 401 rather than reported as a missing record — which is why the
credential is minted per request from that request's own method and path rather than
cached, so a refusal localises to the call that earned it. The three addresses are
routed on the **internal** load balancer only and are published at no edge route, so
the new refusal mode is reachable from inside the private network and from nowhere
else. Owned by
[`security-and-identity.md`](security-and-identity.md), which holds the wire form, the
admitted caller set and the key's custody.

**The asymmetric rewrite-rollback discipline collapses into one boundary.**
[`app/cbl/COACTUPC.cbl`](../../app/cbl/COACTUPC.cbl) rewrites two records inside
`9600-WRITE-PROCESSING` at **L3888**, and its two failure paths are not written the same
way. The account arm at **L4076-L4081** sets the failure state and leaves the paragraph
with **no rollback**, because at that point nothing had been written. The customer arm at
**L4095-L4103** is otherwise identical but issues `EXEC CICS SYNCPOINT ROLLBACK` at
**L4099-L4101**, the verb itself on **L4100**, because by then the account rewrite had
already succeeded. In the target both arms sit inside the one transaction boundary that
replaces the commit at **L952-L954**: a failure propagates as an exception, the provider
discards the unit of work, and no rollback statement is written anywhere in the class.
Refactoring Rationale: the observable outcome is unchanged — neither baseline arm leaves
a partial write, and neither does the target — so this is a change of expression rather
than of behaviour, and it is recorded here because the asymmetry is exactly the shape a
reader expects to find mirrored and will otherwise go looking for. One boundary also
removes the hazard the asymmetry carries, which is a third rewrite added to the arm that
has no rollback statement to extend. Implemented by
`services/account-service/src/main/java/com/carddemo/account/service/AccountUpdateService.java`,
which carries the same citations at its update method.

### 7.4 Divergences claimed by shipped code

Every entry below is claimed as registered by a comment or docstring in shipped
source, and all **ninety-one** are cited **by identifier**, the identifier here being the
identifier used there character for character. They reached that state by three routes,
recorded because the routes explain the difference in tone between them. Some were cited
by identifier from the outset. Others were cited generically as "registered" or
"documented" without naming anything, or asserted the divergent behaviour while claiming
nothing at all, so an identifier was assigned here and added at each citing site, because
a claim of registration that names nothing cannot be checked, and a difference that claims
nothing cannot be found. The `D-REFDATA-*` entries that close the section were authored
the other way round — identifier first, then cited from the published reference contract —
which is the discipline this section asks of everything added after them. Assumptions:
ninety-eight is a measured count of the `####` headings in **the whole document** and not a
running tally kept by hand, so a reader adding an entry updates one number here and nothing
else. Count them document-wide and not within `## 7` alone: the register
continues past the horizontal rule that follows *Related documents*, where entries were
appended after this section had already been closed, so a count confined to `## 7` -- its
five subsections 7.1 through 7.5, which is the quantity the paragraph after next calls the
section-confined one -- omits those twelve and returns **eighty-six**. Refactoring Rationale:
that instruction read "the body between this heading and `## 8`", which is a THIRD quantity
again and returns seventy-seven, because it excludes the seven headings in 7.1 and 7.2.
Refactoring Rationale: that third figure was quoted as sixty-five, which was never a count of
any population in this document -- 7.1 and 7.2 hold three and four headings between them, so
excluding them from eighty-four leaves seventy-seven. It is restated as the measured value for
the same reason the others are: a figure carried forward without being re-measured is how the
first two came to be wrong. The
figure quoted beside it was always the whole of `## 7`, so the instruction is corrected to
name the population the figure counts rather than the figure being changed to match a
population nobody meant. Count the `####`
headings themselves rather than the ones beginning `D-`: one entry is identified
`C-ROUNDING`, so a count restricted to a `D-` identifier is short by one and returns
**ninety-seven**. Assumptions: a literal search for lines beginning `#### D-` returns
**ninety-six** rather than ninety-seven, because two headings carry their identifier in
backticks -- `C-ROUNDING` and `D-REJECT-109-DURABLE` -- so the second is a `D-` entry that
the naive pattern misses. The two figures are stated together so that the search result a
reader gets is predicted here rather than read as drift.
Refactoring Rationale: the figure read forty-three when the section already held forty-five
headings, so three entries were added against a number that was already two short. It is
restated as the measured value rather than incremented from the stale one, because
incrementing a wrong tally is how the figure came to be wrong. It was then restated twice
against the wrong population — as fifty-eight, and after `D-AUTH-REQUEST-WINDOW` was
withdrawn as the same figure again — by counting the whole document while this sentence
still said "this section", so the number quoted and the number a reader would measure could
not agree by construction. Both are corrected together: the figure is the document-wide
count, and the instruction now names that population and names what a section-confined
count returns instead. It went stale once more at sixty-five, for a reason the naming of the
population cannot prevent: entries continued to be appended, and an appender who adds a
heading without recounting leaves every figure here behind. All three numbers are therefore
re-measured together rather than adjusted by the number of entries anyone believes was added,
and the two derived figures are stated so that they check the first — the document-wide count
less the twelve appended after *Related documents* is the section-confined count, and less
the single `C-ROUNDING` heading is the count of `D-`-identified entries. A figure that disagrees with its own
two subtractions is wrong on its face, which is the closest a prose count can come to being
self-checking. It went stale a fourth time at sixty-five while the document held
seventy-five headings, and the correction is recorded rather than quietly applied because the
failure repeated in exactly the way this paragraph predicts: entries were appended and no
figure was recounted. All four were re-measured a fifth time when
`D-EXPORT-PROTECTED-SPANS-REDACTED`, `D-IMPORT-TRUNCATION-REFUSED` and
`D-EXPORT-STAGED-THROUGH-A-FILE` were added, and this time the recount was performed as part
of adding them rather than afterwards. All four were re-measured a sixth time, and this
time the measurement was taken from the file rather than adjusted: the population of this
subsection reads eighty-four, the document-wide count ninety-three, the section-confined count
eighty-two and the count of `D-`-identified entries ninety-two. Assumptions: a naive
search for `^#### D-` returns ninety-one rather than ninety-two, because two headings
carry their identifier inside backticks; the figure stated is the count of entries a
`D-` identifier NAMES, not the count of lines a literal search matches. Assumptions: the
recount was needed because several entries landed at once from independent work -- two register
entries were added, one stale entry was withdrawn into the one that supersedes it, four
headings gained a second identifier, and `D-ADD-KEY-EXCLUSIVE` was withdrawn outright when the
narrowing it registered was replaced by parity -- and a figure adjusted by the number of entries
anyone believes was added is exactly how this paragraph's own history reads. Assumptions: those three were placed **inside this
section's own body**, immediately after the last entry there, rather than appended past
*Related documents* as the previous eight were. That is deliberate and it is the cheap half of
the trade-off this paragraph closes with: placing an entry in the body keeps "this section"
true of it, grows the section-confined count and this section's population by one each so the
two stay in step, and adds nothing to the eleven the first subtraction has to discount. Every
figure above is therefore still checkable by the same two subtractions, and the population of
the appended continuation was unchanged at eleven at that recount, because nothing had been
appended to it. All four numbers here — this section's own population, the document-wide
count, the section-confined count and the `D-`-prefixed count — were re-measured together
against the current file when `D-EXPORT-RECORD-TYPES` and `D-IMPORT-TRUNCATED-ARTEFACT` were
appended, and the two subtractions above were
evaluated to confirm they agree. Assumptions: this section's population and the
section-confined count are DIFFERENT quantities and they have now DIVERGED, at eighty-four
against eighty-two, exactly as the sentence after next predicts they would: the two entries
named above were appended after *Related documents*, so they join this section's population
without joining the whole of `## 7`. The gap has stayed at two while both figures moved,
because the entries added after them — `D-BILLPAY-AMOUNT-WIDTH-REFUSED` and
`D-REPORT-SUBMISSION-DEDUPLICATED` — were added INSIDE this
section's body and therefore joined both counts, and because the withdrawal of
`D-ADD-KEY-EXCLUSIVE` from that same body took one off both of them together; all four figures
here were re-measured together against the current file when they
were added, and the two subtractions were evaluated to confirm they still agree. The paragraph is left standing rather than rewritten
because its prediction coming true is the most useful thing it says --
this section's entries are its own body plus the appended continuation while the
section-confined count is the whole of `## 7`. They will diverge again the moment an entry is
added to 7.1, 7.2, 7.3 or 7.5, so a reader must not treat one as a check on the other. Trade-offs: the alternative was to move the appended entries back
inside this section's body so that "this section" became true. That was rejected as the
larger and riskier change for the smaller gain — it relocates several hundred lines and
every anchor a reader may have bookmarked, to fix a sentence rather than a fact — and it
would leave the same trap for the next appender, whereas naming the population removes the
trap whether or not the entries are ever moved. All four figures were re-measured together
against the file a seventh time when `D-AUTH-SUMMARY-MONEY-DOMAIN` was added, and the two
subtractions were evaluated to confirm they still agree. They were re-measured an eighth time
when `D-TRAN-PAD-PROVENANCE` was added, and a ninth time when
`D-INTEREST-ROW-DISPLAY-WITHHELD` was added, taken from the file rather than adjusted on both
occasions, and the two subtractions were evaluated again: ninety-three less the eleven appended
after *Related documents* is eighty-two, and ninety-three less the single `C-ROUNDING` heading is
ninety-two. Assumptions: both entries were placed INSIDE this section's body, so each joins
both this section's population and the section-confined count and the gap between them stays at
two -- which is the cheap half of the trade-off recorded above, taken deliberately rather than
by default. Refactoring Rationale: the ninth re-measurement also corrected the population of the
appended continuation, which this paragraph twice stated as eight while the file held ELEVEN
headings past *Related documents*. The figure was stale in precisely the way this paragraph
predicts of any figure nobody recounts -- three entries were appended after it was written -- and
the first subtraction beside it had already been re-measured to eleven, so the two statements
contradicted each other. Both are now the measured value. All four were re-measured a tenth time,
from the file, when `D-PREFLIGHT-LOOKUP-PAST-END-OF-FILE` was added inside this section's body, and
the two subtractions were evaluated again: ninety-three less the eleven appended after *Related
documents* is eighty-two, and ninety-three less the single `C-ROUNDING` heading is ninety-two.
Assumptions: that entry was added for a reason this paragraph has no counterpart for, and it is
worth distinguishing. Every previous recount followed an entry that registered a difference nobody
had registered before. This one followed a difference that shipped source **claimed** was
registered here and was not: two citations named `D-7`, which is a §7.3 heading belonging to a
different program, so the claim resolved to the wrong entry and the difference itself appeared
nowhere. A dangling citation is worse than a missing one, because the reader who follows it stops
looking. All five figures were re-measured an eleventh time, from the file, when a batch of
entries authored independently of one another landed together and one entry was WITHDRAWN from this
section's body -- `D-COMBINE-DESC-PAD`, whose difference another change closed while it stood. The
measured values are: this section's population **ninety**, the document-wide count **ninety-nine**,
the section-confined count **eighty-six**, the count of `D-`-identified entries **ninety-eight** and
the literal `^#### D-` search result **ninety-seven**. Both subtractions were evaluated and agree:
ninety-nine less the thirteen appended after *Related documents* is eighty-six, and ninety-nine less
the single `C-ROUNDING` heading is ninety-eight. Assumptions: the gap between this section's
population and the section-confined count has GROWN from two to four, exactly as this paragraph
warned it would and for both of the reasons it named -- two more entries were appended past *Related
documents*, which join this section's population without joining the whole of `## 7`, and §7.5 was
opened with two withdrawn identifiers, which join the whole of `## 7` without joining this section.
Neither figure is a check on the other and the two subtractions above are; a reader wanting one
number from this paragraph should take the document-wide count, because it is the only one a single
search reproduces. Assumptions: the third quantity this paragraph corrects above -- the body between
the 7.4 heading and `## 8` -- now returns eighty rather than the seventy-seven recorded there,
because §7.5's two entries sit inside that span; the earlier figure is left standing as the record of
what was corrected and this one states what the same instruction measures today.
All five figures were re-measured a TWELFTH time, from the file, when
`D-POSTING-GENERATION-DATE` was added inside this section's body. The measured values are:
this section's population **ninety-one**, the document-wide count **one hundred**, the
section-confined count **eighty-seven**, the count of `D-`-identified entries **ninety-nine**
and the literal `^#### D-` search result **ninety-eight**. Both subtractions were evaluated and
agree: one hundred less the thirteen appended after *Related documents* is eighty-seven, and one
hundred less the single `C-ROUNDING` heading is ninety-nine. Assumptions: the gap between this
section's population and the section-confined count is unchanged at four, because the entry was
placed inside this section's body and therefore joined both counts together -- which is the
cheap half of the trade-off recorded above, taken deliberately rather than by default.

All five figures were re-measured a THIRTEENTH time, from the file, when the `C-ROUNDING`
entry that closes this register landed together with the twelfth pass's own edits. The measured
values are: this section's population **ninety**, the document-wide count **ninety-eight**, the
section-confined count **eighty-six**, the count of `D-`-identified entries **ninety-seven** and
the literal `^#### D-` search result **ninety-six**. Both subtractions were evaluated and agree:
ninety-eight less the twelve appended after *Related documents* is eighty-six, and ninety-eight
less the single `C-ROUNDING` heading is ninety-seven. ⚠️ Refactoring Rationale: the twelfth pass's
figures are left standing above as the record of what that pass measured, and they are the first
in this paragraph's history to have been stated for a tree the file never held: they counted a
`C-ROUNDING` heading and a thirteenth appended entry while the file carried neither, so the
subtraction `one hundred less the single C-ROUNDING heading` was arithmetic over a heading that
was not present. That is the failure mode this paragraph exists to catch, reached from a
direction it had not been exercised against -- not a figure left behind by an appender, but a
figure written AHEAD of the entry it counted. The entry has since landed, which is why the
subtraction is now sound; the figures are measured rather than deduced from that fact.

Assumptions: this was also the first recount in which a heading was REMOVED as well as added,
and the distinction is worth recording because a remover who decrements instead of recounting
reintroduces exactly the drift the fourth and fifth failures above record: a removal can take
one heading out of this section's body and another out of the appended continuation, and only a
measurement distinguishes that from two out of either. Every figure above is taken from the file
for that reason.

Assumptions: the gap between this section's population and the section-confined count is four,
and it decomposes exactly: the population is the seventy-eight headings in §7.4's body plus the
twelve appended past *Related documents*, while the confined count is the seven headings that
precede §7.4 plus that same seventy-eight plus the ONE heading in §7.5 -- so the gap is the
twelve appended less those eight, which is four. §7.5 holds one withdrawn identifier and not
two; an earlier statement of this reasoning above said two, and the mechanism it described is
right while the count was not. Neither figure is a check on the other and the two subtractions
are, which is why a reader wanting a single number should take the document-wide count.

Assumptions: several entries carry TWO identifiers in one heading, and both are the
identifier used in shipped source character for character. The four purge entries that
close this section are the case: the letter forms `D-E`, `D-F` and `D-G` are the ones the
authorization service's test package charters use, while the descriptive `D-PURGE-*` forms
are the ones `PurgeJob` itself uses, and the two schemes name the same differences. Assumptions: the
letter scheme is CLOSED to new entries, and `D-UNLOAD-SKIP-REPORTED` is descriptive-only for
that reason. `D-D` is already the receive-failure difference, in three citations across the
authorization service's two test package charters, so a second entry under that letter would
make every citation of it ambiguous in exactly the direction the reconcile-by-search
discipline below depends on. Citing
both in the heading is what keeps the reconcile-by-search discipline below workable from
either side; assigning one and retiring the other would silently break every citation
written in the scheme that lost.

Refactoring Rationale: a `D-AUTH-REQUEST-WINDOW` entry stood here and has been withdrawn,
because the difference it registered has been REMOVED rather than re-argued. It recorded
that the migrated request consumer enforced the five-hundred-request bound
[`COPAUA0C.cbl`](../../app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl) **declares** at
**L40**, where that program's own control flow admits **501** — its counter is incremented
after the request is handled at **L332** and then tested with a strict `>` at **L339**, so
counts one through five hundred all read another request and only the count past the limit
ends the run. `AuthorizationRequestListener` now publishes that offset as
`BASELINE_COMPARISON_OFFSET` and derives its admission allowance as the configured limit
plus it, so the observable behaviour is the same 501 requests per run. The reason for
withdrawing rather than keeping the entry is that functional parity with observable
behaviour is a stated constraint of this migration, and a divergence registered against a
difference that can simply be removed is a difference that should have been removed. The
entry's own accepted-difference argument — that enforcing 501 would make a constant reading
500 unreadable — is answered by keeping `carddemo.messaging.request-process-limit` at the
declared 500 and holding the offset in code beside the citation that derives it, so no
reader has to be told separately that 500 means 501. Assumptions: the anchor this heading
published is now dead, so every site that cited it has been rewritten in the same change
rather than left pointing at a missing heading:
`services/authorization-service/src/main/resources/application.yml`,
`services/authorization-service/src/test/resources/application-test.yml`,
`docs/architecture/messaging-contracts.md` and `docs/adr/ADR-004-messaging.md` in two
places.

Refactoring Rationale: a `D-REFDATA-CATEGORY-BATCH` entry stood here and has been
withdrawn. It registered the maintenance-action batch as additionally addressing
transaction categories, which the published contract does not do: the baseline's three
statements all name `CARDDEMO.TRANSACTION_TYPE` and its fifty-three-byte input record has
no field able to carry a category, so the `catCd` member was removed from
`MaintenanceAction` rather than registered — category writes already reach the category
operations on the same contract, so nothing was lost by removing it. A register entry for
a capability the contract does not publish is worse than no entry, because it is the one
document a reader consults to learn what the target does differently.

Refactoring Rationale: a `D-STATEMENT-MARKUP-ENCODED` entry stood here and has been
withdrawn into `D-STMT-HTML-ESCAPING`, which registers the same divergence. They were
never two: both recorded that the markup statement's embedded values are escaped where
[`CBSTM03A.CBL`](../../app/cbl/CBSTM03A.CBL) escapes nothing, both cited the same three
free-text items, and both accepted the change for the same reason. Keeping both was worse
than a duplicate, because the withdrawn one had drifted from the code in two ways a reader
would have acted on: it named `com.carddemo.common.security.HtmlTextEncoder` as the
escaper, which no longer exists — the escaping is performed inside `StatementHtmlMapper`,
which is the only class that owns the artifact's grammar — and it stated that an overlong
cell is **truncated at a whole character-reference boundary**, whereas the shipped mapper
**refuses** such a cell, exactly as the surviving entry says. Two entries for one
difference is how a register comes to contradict itself; the surviving entry keeps what
was distinct here, which is what the plain-text artifact does NOT do and why.

Refactoring Rationale: a `D-EXPORT-RECORD-TYPES` entry stood here and has been withdrawn,
because the shortfall it registered has been REMOVED rather than re-argued. It recorded that
`ExportJob` emitted three of the reference's five record types -- omitting customer and card,
because the two views read columns this module holds no decrypt authority for -- and it argued
that emitting them with blanked identifiers would be worse than omitting them. The shipped job
emits all five. What answers the argument is that the three protected spans are elided
STRUCTURALLY rather than blanked late: `Customer` maps neither protected column and `Card` maps
none, so no query this module can issue selects one, every span keeps its declared width, and
the two record types that were absent now cross with every field a consumer can act on. That
difference is registered as
[`D-EXPORT-PROTECTED-FIELDS-ELIDED`](#d-export-protected-fields-elided-also-d-export-protected-spans-redacted--the-export-dataset-carries-three-protected-spans-empty),
which is the entry a reader looking for the withdrawn one wants. Assumptions: no shipped file
cited the withdrawn identifier, so nothing is left pointing at a missing heading; the citations
that exist name the surviving entry under one of its two spellings. The reason for withdrawing
rather than keeping it is the same one this section applies to every other withdrawal -- a
divergence registered against a difference that has been closed tells a reader the target still
has it, which is the one thing this register exists to prevent.

Refactoring Rationale: a `D-ADD-KEY-EXCLUSIVE` entry stood here and has been withdrawn,
because the narrowing it registered has been REPLACED BY PARITY rather than re-argued. It
recorded that a capture carrying BOTH the account identifier and the card number was refused
with 400, where `VALIDATE-INPUT-KEY-FIELDS` at **L193** of
[`app/cbl/COTRN02C.cbl`](../../app/cbl/COTRN02C.cbl) accepts it: that paragraph is an
`EVALUATE TRUE`, so the account arm at **L196** fires whatever the card field holds, **L208**
reads the cross-reference and **L209** moves the resolved card number over the value the
operator keyed. The shipped request type applies `AtLeastOneKey`, which refuses only the third
arm the reference also refuses — neither key supplied, with the baseline's own
`'Account or Card Number must be entered...'` sentence at **L224** to **L229** — and
`TransactionAddService.validateInputKeyFields` resolves the account arm first and discards the
submitted card number silently, exactly as **L209** does. The published contract expresses the
rule as `anyOf` over the two single-key alternatives, with neither branch forbidding the other,
where it previously published `oneOf` with a `not` on each branch. So there is no difference
left to register: refusing the pair changed an OUTCOME rather than a message, transformation
rule T9 admits a behavioural change only as a documented divergence with a stated reason, and
the reason offered — that the request could not say which key the caller meant — was not real,
because the reference answers that question itself by resolving the account arm first. The
silent discard the baseline performs is preserved and is recorded on the method that performs
it rather than as a divergence, because it is the reference's behaviour rather than a departure
from it. Assumptions: two shipped test files cited the withdrawn identifier and both have been
rewritten in the same change rather than left pointing at a missing heading —
`services/transaction-service/src/test/java/com/carddemo/transaction/dto/TransactionAddRequestTest.java`
and
`services/transaction-service/src/test/java/com/carddemo/transaction/dto/TransactionApiContractTest.java`,
each of which now pins the inclusive rule and says so.

Refactoring Rationale: a `D-COMBINE-DESC-PAD` entry stood here and has been withdrawn,
because the difference it registered has been REMOVED rather than re-argued. It recorded that
the combined generation pads the description behind an accrual-written row with blanks where
[`CBACT04C.cbl`](../../app/cbl/CBACT04C.cbl) leaves the seventy-six low values the `STRING`
verb at **L485-L489** never wrote, and it accepted that difference on the ground that the
distinction is not recoverable from a relational row -- a description column remembers nothing
about how its writer padded it. That ground was half right: it holds of the pad BYTES and fails
of the PRODUCER. The accrual pass attributes every row it generates at **L484** with the
`System` source and gives it the `Int. for a/c ` prefix at **L485**, which is how the reference
itself tells its two writers apart, so `CombineTransactionsJob.layoutOf` recovers the producer
from those two fields, `TransactionRecordMapper.Layout` declares the byte each producer leaves
behind the description it wrote -- the blank under `TRAN`, the low value under `INTTRAN` -- and
the encoder pads the tail with it. A posted row of the artefact now carries blanks and an
accrual row carries the reference's low values, on exactly the bytes the withdrawn entry said
differed. Its rejection of inferring the producer from the description's leading text SURVIVES,
as the reason the recogniser requires the closed-domain source field as well rather than the
prefix alone. What is left of the mechanism -- a producer recovered from the record rather than
from the dataset it arrived in, with a posted row carrying both marks as the residual hazard --
is registered as
[`D-TRAN-PAD-PROVENANCE`](#d-tran-pad-provenance--a-re-emitted-records-padding-is-recovered-from-its-own-attribution),
which is the entry a reader looking for the withdrawn one wants. Assumptions: the anchor this
heading published is now dead, so both sites that cited it have been rewritten in the same
change rather than left pointing at a missing heading --
`services/batch-service/src/main/java/com/carddemo/batch/job/CombineTransactionsJob.java`,
whose class comment had asserted blanks for EVERY row, and
`services/batch-service/src/test/java/com/carddemo/batch/job/CombineTransactionsJobTest.java`,
whose case asserts each producer's own pad under the name `emitEachProducersOwnDescriptionPad`
and records the withdrawal beside it. Assumptions: the withdrawn entry's verification citation
named a case asserting that the two row classes carried the SAME pad, and that case no longer
exists -- a register entry whose only evidence has been deleted is the clearest signal
available that the difference went with it. The reason for withdrawing rather than keeping it is
the one this section applies to every withdrawal: a divergence registered against a difference
that has been closed tells a reader the target still has it.

Assumptions: not every reference to this document from shipped source is a claim of the kind
above, and the difference matters when auditing them. Most name the register as the place
divergences live, or state that a file claims none; those correctly carry no identifier. So do
the ones that point at [§7.3](#73-structural-divergences-that-are-not-defects), whose entries
lead with a bolded sentence rather than an identified heading — the eliminated re-entry
discriminator and the response-driven field-error highlight are both cited that way. A
reference with no identifier beside it is therefore not by itself evidence of an unhonoured
claim; what has to be honoured is a reference asserting that **a specific difference** is
registered, and each of those names its entry.

Refactoring Rationale: these entries are authored because their absence made the
register's own opening sentence false. A file that says a difference "is registered
in this document" and finds no entry has stated something untrue, and the reader who
goes looking is left unable to tell an omission from a difference nobody intended.
The register is the mechanism the standing constraint relies on, so a gap in it is
not a documentation shortfall but a failure of the constraint itself.

Assumptions: the reverse direction matters as much as this one. Any future difference
claimed in code must acquire an entry here **and** cite it by identifier, and any
entry here must name the files that implement it, so that the two sides can be
reconciled by search rather than by reading. That is the only discipline under which
a register of this size stays true.

#### D-SIGNED-ZERO-ZONED — the plain zoned pair normalises a negative-zero overpunch

* **Baseline behaviour.** A zoned-decimal field carries its sign in the low-order
  byte, and the overpunch table admits a **negative zero** — the byte `}` for a value
  of minus nothing — distinctly from the positive `{`. The seed carries both forms;
  [`app/data/ASCII/tcatbal.txt`](../../app/data/ASCII/tcatbal.txt) record 1 uses `{`
  as a positive-zero low-order digit, and a `}` in the same position is a different
  byte holding the same numeric value.
* **Target behaviour.** `ZonedDecimalCodec`'s plain `decode`/`encode` pair yields a
  `BigDecimal`, which has **no negative zero**, so a `}`-signed zero decodes to zero
  and re-encodes as `{`. The byte is not preserved on that path. A separate
  sign-representation-preserving pair — `decodePreservingSign`/`encodePreservingSign`
  over a `SignedZoned(value, negativeSign)` carrier — reproduces the original byte
  exactly, and `FixedWidthCodec.encodeRecordPreservingSign` routes every signed zoned
  field whose value is zero through it.
* **Category.** Documented divergence — sign representation of zero on the plain
  numeric path only.
* **Why the difference is accepted.** The loss is a property of the target's numeric
  type and not a choice: no exact decimal type in the platform can hold minus zero, so
  a plain numeric round trip cannot reproduce the byte at any price. Rather than
  changing the numeric type, the codec offers a second pair whose carrier keeps the
  sign alongside the value, so byte-exact round-tripping is available where a record
  must be reproduced and the ordinary arithmetic path stays free of a representation
  concern it has no use for.
* **Where it is verified.**
  `signPreservingPairReproducesTheNegativeZeroOverpunchByteForByte`,
  `signPreservingPairReproducesANonZeroNegativeSpan`,
  `signPreservingPairRefusesASignThatContradictsANonZeroValue` and
  `theSignPreservingRecordEncoderReproducesSignedZeroesByteForByte`, each of which also
  asserts the documented normalisation of the plain pair so the divergence is pinned
  from both sides.
* **Files.**
  `services/common-lib/src/main/java/com/carddemo/common/codec/ZonedDecimalCodec.java`,
  `services/common-lib/src/main/java/com/carddemo/common/codec/FixedWidthCodec.java`.

#### D-SIGNED-ZERO-PACKED — the plain packed pair normalises the zero sign nibble

* **Baseline behaviour.** A packed-decimal (`COMP-3`) field carries its sign in the
  low-order nibble, and a zero value may arrive with `0xC` (positive), `0xD`
  (negative) or `0xF` (unsigned). All three occur in the baseline's packed records —
  the export record at [`app/cpy/CVEXPORT.cpy`](../../app/cpy/CVEXPORT.cpy) and the
  two authorization segments — and all three denote the same numeric value.
* **Target behaviour.** `PackedDecimalCodec`'s plain pair yields a `BigDecimal` and
  re-encodes a zero with one canonical nibble, so the original is not preserved. The
  sign-preserving pair `decodePackedPreservingSign`/`encodePackedPreservingSign` over a
  `SignedPacked(value, signNibble)` carrier reproduces the original nibble, admitting
  exactly the three nibbles the codec itself emits.
* **Category.** Documented divergence — sign representation of zero on the plain
  numeric path only.
* **Why the difference is accepted.** For the reason given under
  `D-SIGNED-ZERO-ZONED`: the target's exact decimal type cannot express the
  distinction, so it is carried beside the value rather than inside it. Admitting only
  `0xC`, `0xD` and `0xF` is deliberate — a carrier that accepted the alternate
  encodings `0xA`, `0xB` and `0xE` would promise to reproduce bytes this codec never
  emits, which is a claim it cannot keep.
* **Where it is verified.** `signPreservingPackedPairReproducesBothZeroSignNibbles`,
  `signPreservingPackedPairReproducesSignedAndUnsignedFields` and
  `signPreservingPackedPairRefusesAnInadmissibleNibble`.
* **Files.**
  `services/common-lib/src/main/java/com/carddemo/common/codec/PackedDecimalCodec.java`,
  `services/common-lib/src/main/java/com/carddemo/common/codec/FixedWidthCodec.java`.

#### D-NEGATIVE-AUTH-AMOUNT — an out-of-domain authorization amount is refused

* **Baseline behaviour.** [`COPAUA0C.cbl`](../../app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl)
  performs **no domain check** on the requested transaction amount. It compares the
  amount against the available credit, finds that a negative value is never greater,
  and **approves**; the approval then adds the amount to the account's reserved
  balance, so a negative request releases credit rather than reserving it — on the one
  field the requester chooses freely.
* **Target behaviour.** `AuthorizationRequestPayload` bounds the amount to
  `0.00 .. 9999999999.99` and the listener refuses an out-of-domain value immediately
  after decode, **before** any lookup, decision or mutation.
* **Category.** Documented divergence — added input-domain validation.
* **Why the difference is accepted.** The refused values are ones the baseline
  processes into a wrong state rather than ones it processes differently: a negative
  amount inverts the meaning of the reservation, and an amount wider than the stored
  field cannot be persisted at all. Refusing before any lookup means the divergence
  cannot alter an approval decision for any value the baseline handled correctly,
  which bounds it to exactly the inputs that had no correct handling.
* **Where it is verified.** `AuthorizationRequestListenerTest` asserts refusal at the
  domain boundary and that no repository interaction occurs on a refused message;
  `AuthorizationPayloadDomainTest` asserts the accepted and rejected bounds.
* **Files.**
  `services/authorization-service/src/main/java/com/carddemo/authorization/dto/AuthorizationRequestPayload.java`,
  `services/authorization-service/src/main/java/com/carddemo/authorization/service/AuthorizationRequestListener.java`.

#### D-SUMMARY-LIMIT-REFRESH — stored limits are refreshed only when the account was read

* **Baseline behaviour.** `COPAUA0C.cbl` moves the account master's credit limit and
  cash limit into the pending-authorization summary **unconditionally**, at **L810**
  and **L811**. On the one path where the card resolves but the account master is
  missing, its own **L451** never populated that working storage, so the move writes
  whatever the previous message left there — or zero on the first message of the task.
* **Target behaviour.** The listener refreshes the stored limits **only when the
  account record was actually read**, leaving the previously stored values in place
  otherwise.
* **Category.** Documented divergence — conditional refresh of a stored field.
* **Why the difference is accepted.** Reproducing the unconditional move would destroy
  a real credit limit and then decline every subsequent authorization on that account
  for want of funds — a persistent wrong state produced by an uninitialised read, not
  a behaviour any reader of the program would defend. The divergence is confined to the
  single path where the account master is absent; on every path where it is present the
  two implementations write the same values.
* **Where it is verified.** `AuthorizationRequestListenerTest` covers the missing-account
  path and asserts the stored limits are unchanged, and the present-account path and
  asserts they are refreshed.
* **Files.**
  `services/authorization-service/src/main/java/com/carddemo/authorization/service/AuthorizationRequestListener.java`.

#### D-DECLINED-AMT-CURRENT — the declined total accumulates this request's amount

* **Baseline behaviour.** `COPAUA0C.cbl` adds `PA-TRANSACTION-AMT` to the running
  declined total at **L821**, but that detail-segment field is not populated until
  **L885**, in the following paragraph. The total it accumulates is therefore the
  **previous** message's amount, or zero for the first message of a task.
* **Target behaviour.** The listener adds the **current** request's amount to the
  declined total.
* **Category.** Documented divergence — corrected accumulation source.
* **Why the difference is accepted.** A running total that is off by one message
  describes nothing at all: it is neither the current amount nor a meaningful history,
  and on the first message of every task it is zero. The divergence changes which value
  is added, not when or whether the total is maintained, and the corresponding
  approved-side total is unaffected because its source is populated before use.
* **Where it is verified.** `AuthorizationRequestListenerTest` asserts the declined
  total after a decline equals that message's own amount, and after two declines equals
  their sum.
* **Files.**
  `services/authorization-service/src/main/java/com/carddemo/authorization/service/AuthorizationRequestListener.java`.

#### D-AUTH-SUMMARY-MONEY-DOMAIN — the summary's money columns saturate where the baseline truncates

* **Baseline behaviour.** Every money field of the pending-authorization summary segment is
  `PIC S9(09)V99 COMP-3` — the two limits and the two balances at **L23-L26** of
  [`app/app-authorization-ims-db2-mq/cpy/CIPAUSMY.cpy`](../../app/app-authorization-ims-db2-mq/cpy/CIPAUSMY.cpy)
  and the two running totals at its **L29-L30** — while three of the fields those members
  receive their values from are one decimal order **wider**. `ACCT-CREDIT-LIMIT` and
  `ACCT-CASH-CREDIT-LIMIT` are `PIC S9(10)V99` at **L8** and **L9** of
  [`app/cpy/CVACT01Y.cpy`](../../app/cpy/CVACT01Y.cpy), and `PA-TRANSACTION-AMT` and
  `PA-APPROVED-AMT` are `PIC S9(10)V99 COMP-3` at **L34** and **L35** of
  [`CIPAUDTY.cpy`](../../app/app-authorization-ims-db2-mq/cpy/CIPAUDTY.cpy).
  [`COPAUA0C.cbl`](../../app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl) crosses that
  boundary four times in one paragraph: `MOVE ACCT-CREDIT-LIMIT TO PA-CREDIT-LIMIT` at
  **L810**, `MOVE ACCT-CASH-CREDIT-LIMIT TO PA-CASH-LIMIT` at **L811**,
  `ADD WS-APPROVED-AMT TO PA-APPROVED-AUTH-AMT` and `TO PA-CREDIT-BALANCE` at **L815** and
  **L817**, and `ADD PA-TRANSACTION-AMT TO PA-DECLINED-AUTH-AMT` at **L821**. A COBOL `MOVE`
  or `ADD` into a narrower numeric field discards **high-order** digits and reports nothing, so
  a credit limit of one thousand million is stored as **zero**.
* **Target behaviour.** Each value is reduced to the segment's own domain by **saturation** —
  bounded at ±999,999,999.99 with its sign and scale preserved — and the reduction is reported
  as `event=auth.summary.money-narrowed` on the decision path and
  `event=authorization.purge.money-narrowed` on the expiry sweep, each naming the member and the
  bound and never the value. The bound is published once, as
  `PendingAuthSummary.MONEY_MAX_MAGNITUDE`, and is passed into the three arithmetic statements
  that accumulate, so the reduction holds whether the write goes through the entity or through a
  statement the database evaluates.
* **Category.** Documented divergence — narrowing arithmetic at a width boundary the copybook
  itself declares.
* **Why the difference is accepted.** The two narrowings are not comparable in consequence.
  Truncation maps one thousand million to zero, so the very next authorization on that account is
  declined for want of funds and the account is effectively frozen by a limit **increase**;
  saturation leaves the stored limit one cent short of a thousand million and the account keeps
  transacting. Saturation is also monotone in its input, where truncation is not — under
  truncation a larger limit can produce a smaller stored value — and a running total that is
  monotone is the only kind a reader of the summary screen can reason about at all.
* **What the divergence replaced, which was worse than either.** Before the reduction the
  out-of-domain value reached the database, which refused the whole statement with SQLSTATE
  `22003`. The refusal aborted the message's unit of work, so the requester received **no reply
  of any kind** — not a decline — and the request was redelivered four more times and
  dead-lettered. An account holding a large limit could not authorize even a small amount, and
  the same refusal aborted the expiry sweep mid-run, leaving the table partly purged with no
  later run able to complete while such a row existed. So this entry registers a divergence from
  the baseline that was chosen over a state the baseline does not have.
* **Why the columns are not widened instead.** Transformation rule T1 makes the copybook picture
  normative for the column type, and the schema's `NUMERIC(11,2)` is exactly what `S9(09)V99`
  derives to — the mapping is published in
  [`data-model-and-schema-mapping.md`](data-model-and-schema-mapping.md). The sibling transaction
  context refused the same widening for the same reason, recorded below as
  `D-BILLPAY-AMOUNT-WIDTH-REFUSED`. `PendingAuthSummaryRepositoryIT` additionally asserts the
  column refuses a twelve-digit value, so widening would have to change that case too.
* **What is deliberately *not* affected.** The **decision** is taken from the account master's
  full-width limit and the request's full-width amount before any narrowing, so a saturated limit
  never changes an approve into a decline. `pending_auth_detail` is `NUMERIC(12,2)` from its own
  wider picture, so the authorization itself is stored whole — the reply the requester receives
  and the row the detail screen renders both carry the amount as sent. Only the summary's
  **aggregates** are bounded.
* **Where it is verified.** `PendingAuthSummaryMoneyDomainTest` asserts the boundary in both
  signs, that the bound itself is admitted and one cent past it is not, and that accumulation
  rather than the addend is what saturates. `AuthorizationRequestListenerTest` asserts both write
  arms reduce an over-wide limit and that a requested amount of one thousand million is answered
  with an ordinary insufficient-funds decline whose detail row keeps the amount whole.
  `PendingAuthSummaryRepositoryIT` asserts the saturation against a real engine in both
  directions, beside the case that asserts the same column refuses a twelve-digit insert.
  `PurgeJobTest` asserts the sweep passes the segment's own bound and not the detail table's
  wider one.
* **Files.**
  `services/authorization-service/src/main/java/com/carddemo/authorization/domain/PendingAuthSummary.java`,
  `services/authorization-service/src/main/java/com/carddemo/authorization/repository/PendingAuthSummaryRepository.java`,
  `services/authorization-service/src/main/java/com/carddemo/authorization/service/AuthorizationRequestListener.java`,
  `services/authorization-service/src/main/java/com/carddemo/authorization/service/PurgeJob.java`.

#### D-SUMMARY-COUNTER-SATURATION — the summary's two counters saturate where the baseline truncates

* **Baseline behaviour.** `PA-APPROVED-AUTH-CNT` and `PA-DECLINED-AUTH-CNT` are
  `PIC S9(04) COMP` at **L27** and **L28** of
  [`app/app-authorization-ims-db2-mq/cpy/CIPAUSMY.cpy`](../../app/app-authorization-ims-db2-mq/cpy/CIPAUSMY.cpy)
  — signed, four decimal digits, so −9999 through 9999 —
  and [`COPAUA0C.cbl`](../../app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl) advances them
  with `ADD 1 TO PA-APPROVED-AUTH-CNT` at **L815** and `ADD 1 TO PA-DECLINED-AUTH-CNT` at
  **L821**. Neither statement carries an `ON SIZE ERROR` clause, and neither counter is reset
  anywhere in the program. The expiry sweep subtracts from the same two members with the same
  absence of a size clause, at **L288** and **L291** of
  [`CBPAUP0C.cbl`](../../app/app-authorization-ims-db2-mq/cbl/CBPAUP0C.cbl). A COBOL `ADD`
  without `ON SIZE ERROR` into a field too narrow to hold the sum discards the **high-order**
  digits and reports nothing, so the ten-thousandth authorization on an account stores a count of
  **0** and the program carries on as though nothing had happened.
* **Target behaviour.** Each counter is reduced to the same four-digit domain by **saturation** —
  held at 9999 on the way up and at −9999 on the way down, sign preserved — and the reduction is
  reported as `event=auth.summary.counter-narrowed` on the decision path and
  `event=authorization.purge.counter-narrowed` on the expiry sweep, each naming the account and
  the member. The two bounds are published once, as `PendingAuthSummary.COUNTER_MAX` and
  `PendingAuthSummary.COUNTER_MIN`, and are passed into the three arithmetic statements that
  advance a counter, so the reduction holds whether the write goes through the entity or through a
  statement the database evaluates. This mirrors exactly what
  `D-AUTH-SUMMARY-MONEY-DOMAIN` above does for the same row's money members, and the two entries
  are deliberately the same shape: one row, one policy.
* **Category.** Documented divergence — narrowing arithmetic at a width boundary the copybook
  itself declares.
* **Why the difference is accepted.** Truncation is not merely imprecise, it is undetectable. It
  maps 10000 to 0, and a count of zero on an account holding nine thousand nine hundred and
  ninety-nine live authorizations is a value no reader of the summary screen can recognise as
  wrong — it reads as a fresh account. Saturation is monotone in its input and leaves the counter
  resting on a published bound, which is recognisable **as** saturation; and because it is
  reported at both writers, the account and the member are named in a log line at the moment the
  information is lost. The lost information is real and is the price: a saturated counter
  understates the number of authorizations, and no later reversal restores the excess, which is
  why the expiry sweep's floor exists at all — a saturated root can legitimately be driven to
  −9999 by reversing children it never counted.
* **What the divergence replaced, which was worse than either.** The entity raised
  `IllegalStateException` at the bound, and the statements that actually write the row carried no
  clamp at all, so the out-of-domain value reached the database and the check constraint
  `ck_pending_auth_summary_counts` at **L266-L268** of
  `services/authorization-service/src/main/resources/db/migration/V1__authorization.sql`
  refused the whole statement. Either way the refusal was **invisible**: nothing on the
  authorization path presents it to a requester. It aborted the message's unit of work, the queue
  redelivered, and after five receives the request dead-lettered **unanswered** — so an account
  that reached 9999 approvals stopped being answerable at all, permanently, and no operator
  looking at a decline would find one, because there was no decline. On the expiry sweep the same
  raise abended the run mid-table, leaving the rows already deleted deleted and the rows behind
  them not, a state neither the reference nor a rerun reconstructs. So this entry registers a
  divergence chosen over a state the baseline does not have, exactly as its money sibling does.
* **Why the entity's refusal and the statements' silence were one defect, not two.** The aggregate
  is used on the insert arm, where a new root is composed in memory and saved; the three
  statements are used on every subsequent arm, where the row is updated in the database. A policy
  that stopped at the aggregate therefore governed only the first authorization an account ever
  had, and the arm that reached 9999 was never the arm the policy covered. Publishing the bounds
  and passing them into the statements is what makes the two arms agree; the `case when` guards in
  `PendingAuthSummaryRepository` are evaluated by the engine inside the same statement that
  advances the counter, so no read-modify-write window exists for a concurrent decision to slip
  through.
* **Why the columns are not widened instead.** Transformation rule T1 makes the copybook picture
  normative, and `SMALLINT` with a ±9999 check constraint is exactly what `PIC S9(04) COMP`
  derives to — the mapping is published in
  [`data-model-and-schema-mapping.md`](data-model-and-schema-mapping.md). A wider column would
  hold counts that no extract of the reference segment could round-trip and no reference reader
  could represent, which is the same reason the money members were not widened.
* **What is deliberately *not* affected.** The **decision** does not read either counter, so a
  saturated count never changes an approve into a decline. `pending_auth_detail` holds one row per
  authorization and is not bounded by this, so the authorizations themselves remain individually
  countable by query even when the summary's aggregate has saturated — which is the recovery path
  an operator has, and the reason saturation is tolerable rather than merely less bad.
* **Where it is verified.** `PendingAuthSummaryReversalTest` asserts saturation in both directions
  through the aggregate — that the counter rests exactly on the bound rather than wrapping, that
  the money member of the same arm still moves, that the ten-thousandth approval is recorded
  rather than refused, and that the classifier the writers consult agrees with the narrowing.
  `PendingAuthSummaryRepositoryIT` asserts the clamp against a real engine on all three
  statements, so the guard is proven where the check constraint actually lives.
  `AuthorizationRequestListenerTest` asserts the approve and decline arms pass the published
  ceiling and that the narrowing is reported. `PurgeJobTest` asserts the sweep passes the floor
  and reports its own narrowing with the stored sign.
* **Files.**
  `services/authorization-service/src/main/java/com/carddemo/authorization/domain/PendingAuthSummary.java`,
  `services/authorization-service/src/main/java/com/carddemo/authorization/repository/PendingAuthSummaryRepository.java`,
  `services/authorization-service/src/main/java/com/carddemo/authorization/service/AuthorizationRequestListener.java`,
  `services/authorization-service/src/main/java/com/carddemo/authorization/service/PurgeJob.java`.

#### D-AMOUNT-RECORD-WIDTH — the transaction amount is bounded by the record, not the screen

* **Baseline behaviour.** `TRAN-AMT` is `PIC S9(09)V99` at **L10** of
  [`app/cpy/CVTRA05Y.cpy`](../../app/cpy/CVTRA05Y.cpy) — nine integer digits — while
  the add-transaction screen accepts eight.
  [`app/cbl/COTRN02C.cbl`](../../app/cbl/COTRN02C.cbl) validates only eight digits from
  the second character at **L341**, parses the field into `WS-TRAN-AMT-N` at
  **L383-L384** (declared `PIC S9(9)V99` at **L58**, nine digits), moves that into
  `WS-TRAN-AMT-E` at **L385** (declared `PIC +99999999.99` at **L59**, eight), and
  writes the narrower edited form back over the screen field at **L386**.
* **Target behaviour.** `TransactionAddRequest` accepts all **nine** integer digits,
  bounded by a constraint expressed in whole cents so the record's own domain is the
  limit. `TransactionAddService`'s positional shape test — the transcription of the
  four alternatives at **L340-L343** — measures the same nine digits, so the width is
  the record's on every side of the boundary. The display width belongs to whichever
  client renders the field; a 3270-faithful one still renders the twelve characters
  `app/cpy-bms/COTRN02.CPY` declares at **L96**.
* **Category.** Documented divergence — accepted input width.
* **Why the difference is accepted.** Constraining the request to the display width
  would discard capacity the records demonstrably hold and would make the request
  depend on which screen submitted it. The edited widths differ between reference
  screens deliberately — [`app/cbl/COBIL00C.cbl`](../../app/cbl/COBIL00C.cbl) declares
  the same eight-digit transaction-amount edit at **L55** and a ten-digit balance edit
  at **L56** — so neither is a general rule. The over-wide message is carried across
  unchanged even though it names the eight-digit specimen, because transformation rule
  T8 forbids rewording a user-visible string and the message is the correct one for the
  condition the baseline raises it on.
* **Where it is verified.** `TransactionApiContractTest` binds the published amount
  pattern, the record constraint and the service's own shape-test width together, so a
  narrowing on any of the three fails. `TransactionAddServiceTest` asserts the
  behaviour at both edges of the domain: a nine-integer-digit amount is admitted and
  carried into the stored row, and a ten-integer-digit one is refused with the
  reference's format sentence.
* **A correction to this entry.** This divergence described three agreeing authorities
  when only two agreed. The service transcribed the **screen's** eight-digit picture at
  **L59** while the request record and the published contract admitted the record's
  nine, so a nine-digit amount cleared the boundary and was then refused after
  deserialization — the divergence was published as delivered and was not. The width
  the service measures is now the record's, and the contract test reads the service's
  constant so a third authority cannot dissent again unnoticed. Nothing about the
  *stated* target behaviour changed; the delivered code was brought up to it.
* **Files.**
  `services/transaction-service/src/main/java/com/carddemo/transaction/dto/TransactionAddRequest.java`,
  `services/transaction-service/src/main/java/com/carddemo/transaction/service/TransactionAddService.java`,
  `services/transaction-service/src/main/resources/openapi/transaction-api.yaml`.

#### D-TEXT-RECORD-WIDTH — three text fields are carried at record width, not screen width

* **Baseline behaviour.** The screen narrows three of the four text fields below the
  record's declared width: the description is `PIC X(100)` in the record and appears as
  `TDESCI PIC X(60)` at **L90** of the symbolic map, the merchant name is `PIC X(50)`
  and appears as `MNAMEI PIC X(30)` at **L120**, and the merchant city is `PIC X(50)`
  and appears as `MCITYI PIC X(25)` at **L126**. The merchant postal code is the one
  field the screen does not narrow, matching the record at `MZIPI PIC X(10)` on
  **L132**.
* **Target behaviour.** `TransactionAddRequest` carries all four at **record** width.
* **Category.** Documented divergence — accepted input width.
* **Why the difference is accepted.** For the reason given under
  `D-AMOUNT-RECORD-WIDTH`: the record is the storage contract and the screen is one
  presentation of it, so binding the request to the presentation would discard capacity
  the record holds. What is accepted is that a client rendering into a fixed-width
  column has to decide for itself what to do with the surplus, which is a client
  concern the baseline resolved by having only one client.
* **Where it is verified.** `TransactionApiContractTest` compares each published
  `maxLength` with the constraint on the corresponding record component.
* **Files.**
  `services/transaction-service/src/main/java/com/carddemo/transaction/dto/TransactionAddRequest.java`.

#### D-ERROR-ACCUMULATION — every violation is reported, not only the first

* **Baseline behaviour.** Each validation check in `COTRN02C.cbl` sets an error flag,
  moves one message, positions the cursor and sends the screen immediately, so an
  operator sees exactly **one** error per turn even when several fields are wrong.
* **Target behaviour.** Declarative validation evaluates every constraint and the
  response carries a per-field error array with **every** violation, while the
  aggregate message latches to the first failure exactly as the baseline's single
  message does.
* **Category.** Documented divergence — error reporting granularity.
* **Why the difference is accepted.** Transformation rule T7 asks for a per-field error
  array, so accumulation is the intended target shape rather than an accident of the
  validation framework. The baseline itself already separates the two channels — one
  latched message and one highlighted field — which is why the aggregate message can
  keep the baseline's behaviour while the array adds the rest. What is accepted is that
  a submission with four defects answers once with four entries where the baseline would
  have answered four times with one.
* **Where it is verified.** The class-level constraints on `TransactionAddRequest`
  together with `GlobalExceptionHandler`'s handling of global violations, which is what
  makes a class-level violation appear in the array rather than vanish.
* **Files.**
  `services/transaction-service/src/main/java/com/carddemo/transaction/dto/TransactionAddRequest.java`,
  `services/common-lib/src/main/java/com/carddemo/common/error/GlobalExceptionHandler.java`.

#### D-EDIT-MASK-OVERFLOW — a value too wide for its edit mask raises instead of truncating

* **Baseline behaviour.** Moving a value into a narrower edited field discards
  high-order digits **without signalling**. Two measured instances:
  [`app/cbl/CORPT00C.cbl`](../../app/cbl/CORPT00C.cbl) **L77** moves nine integer digits
  into an eight-position mask, yielding a well-formed amount one thousandth of the
  original; and [`app/cbl/CBSTM03A.CBL`](../../app/cbl/CBSTM03A.CBL) **L484** moves a
  balance declared `PIC S9(10)V99` at **L7** of
  [`app/cpy/CVACT01Y.cpy`](../../app/cpy/CVACT01Y.cpy) into a nine-digit field, so any
  balance of a thousand million or more loses one high-order digit.
* **Target behaviour.** `CobolEditMask` raises on overflow, naming the offending
  magnitude, its scale and the mask that could not hold it.
* **Category.** Documented divergence — overflow signalling.
* **Why the difference is accepted.** A truncated amount is the
  plausible-number-that-is-wrong this class exists to prevent, and it is unrecoverable
  downstream because the truncated string carries no evidence of the digits it lost.
  The guard is not an expected path for the report masks: the three accumulators that
  feed them are declared `PIC S9(09)V99` at **L134-L136** of
  [`app/cbl/CBTRN03C.cbl`](../../app/cbl/CBTRN03C.cbl), exactly the nine positions the
  masks provide. It stays reachable only because the shared money type admits ten
  integer digits, so the narrowing decision belongs to the caller assembling the band —
  the only place that can decide what a balance too wide for its own field should show.
* **Where it is verified.** `ReportingDtoMapperTest` asserts the exact edited forms
  within the mask's width and the raise beyond it, for both masks.
* **Files.**
  `services/reporting-service/src/main/java/com/carddemo/reporting/mapper/CobolEditMask.java`.

#### D-AUTH-REASON-WIDTH — the composed authorization reason is capped at the screen's twenty

* **Baseline behaviour.** The authorization reason is one composed field, not two.
  [`COPAUS1C.cbl`](../../app/app-authorization-ims-db2-mq/cbl/COPAUS1C.cbl) moves the
  four-character reason code into the first positions, overlays a separator at position
  5 at **L326**, and moves the description from position 6 onward at **L327**, writing a
  `'9999'` and `'ERROR'` pair at **L321-L323** when the table lookup finds no entry. The
  receiving field is `AUTHRSNO PIC X(20)` at **L248** of
  [`app/app-authorization-ims-db2-mq/cpy-bms/COPAU01.cpy`](../../app/app-authorization-ims-db2-mq/cpy-bms/COPAU01.cpy),
  corroborated by
  `AUTHRSNI PIC X(20)` at **L84**, and the move at L327 targets `AUTHRSNO(6:)` — a
  reference-modified receiver of exactly fifteen positions — so COBOL **truncates the
  sixteenth character** of the description on the way in.
* **Target behaviour.** `PendingAuthDetailResponse` carries the composed reason as one
  component bounded at **20** characters, reproducing that truncation.
* **Category.** Documented divergence — a corrected earlier target width, now matching
  the baseline exactly.
* **Why the difference is accepted.** This entry records a **narrowing back** to
  parity, not a departure from it. An earlier revision of the response carried 21
  characters on the stated ground that retaining the whole sixteen-character
  description was worth one character of divergence; that reasoning inverted the
  contract, because the twenty-first character exists nowhere in the baseline — not on
  the screen, not in the receiving field and not in any stored value. Carrying it made
  the payload wider than the only observable form of the value and would have let a
  projection publish a character the screen it mirrors cannot show. The composed field
  is also kept as one component rather than split into a code and a description,
  because splitting would invent two fields where the screen carried one and the
  `'9999'` fallback has no separate code field to live in.
* **Where it is verified.** `AuthorizationPayloadDomainTest` asserts the twenty-character
  bound and the composed shape, including the fallback pair.
* **Files.**
  `services/authorization-service/src/main/java/com/carddemo/authorization/dto/PendingAuthDetailResponse.java`.

#### D-REPORT-HANDLE — report submission returns an addressable execution handle

* **Baseline behaviour.** [`CORPT00C.cbl`](../../app/cbl/CORPT00C.cbl) submits a report
  by writing job-control images to a transient data queue mapped to the internal reader
  — the `TDQUEUE(JOBS)` definition at **L502** of
  [`app/csd/CARDDEMO.CSD`](../../app/csd/CARDDEMO.CSD) — and **returns nothing**. A
  malformed submission is discovered only when the reader consumes it.
* **Target behaviour.** `ReportSubmissionResponse` returns an execution identifier
  synchronously on acceptance, and a refused submission is an error response rather
  than a discarded record.
* **Category.** Documented divergence — submission acknowledgement.
* **Why the difference is accepted.** The queue-and-internal-reader mechanism has no
  cloud analogue and is retired ([§5.1](#51-retired-with-an-analogue--function-preserved-mechanism-replaced));
  its replacement starts a state-machine execution, which has an identity, so returning
  nothing would discard information the new mechanism produces for free. The divergence
  is in what the caller learns, not in which reports are produced or in their content.
* **Where it is verified.** Report submission tests assert an identifier on acceptance
  and an error response on refusal.
* **Files.**
  `services/reporting-service/src/main/java/com/carddemo/reporting/dto/ReportSubmissionResponse.java`.

#### D-EMPTY-PAGE — an unmatched card search answers with an empty page, not a screen message

* **Baseline behaviour.** [`COCRDLIC.cbl`](../../app/cbl/COCRDLIC.cbl) reports an
  unmatched search as a **message on the same screen**, leaving the operator on the
  list with nothing listed.
* **Target behaviour.** Both the card list operation and the card lookup operation
  answer **200 with an empty `items` array** and the page envelope's cursors null.
* **Category.** Documented divergence — representation of an empty result.
* **Why the difference is accepted.** A query that matched nothing **succeeded**, and
  404 would tell a client that the collection itself does not exist — a different and
  wrong statement. The baseline had one client and could put the distinction in prose on
  the screen; a published contract has to put it in the status code and the body, and an
  empty collection is the accurate encoding of it. The user-visible message itself is not
  lost: it is carried in the message catalogue and rendered by the screen that shows the
  empty list.
* **Where it is verified.** `CardApiContractTest` asserts that the list and lookup
  operations publish a 200 carrying the page envelope and declare no 404, so a later
  edit that reintroduced one fails.
* **Files.** `services/card-service/src/main/resources/openapi/card-api.yaml`,
  `ui/src/api/cards.ts`, `ui/src/messages/messages.ts`.

#### D-CVV-UNTOUCHED — a card update leaves the enciphered verification column untouched

* **Baseline behaviour.** [`COCRDUPC.cbl`](../../app/cbl/COCRDUPC.cbl) moves a
  never-populated working-storage field into the card record it writes, at
  **L1464-L1465**. No screen displays or captures that value, so the write stores
  whatever the field happened to hold.
* **Target behaviour.** An update carries no such value and leaves the stored
  enciphered verification column **unchanged**.
* **Category.** Documented divergence — a field excluded from an update.
* **Why the difference is accepted.** Reproducing the move would overwrite a stored
  verification value with an uninitialised one on every update, which destroys data on a
  path no operator can see or intend. The verification value is additionally one the
  migration's disclosure rules forbid returning on any endpoint, so there is no target
  path on which a client could supply a correct replacement. The divergence is confined
  to that one column; every other column the baseline's update writes is written here.
* **Where it is verified.** `CardApiContractTest` asserts the update request schema
  declares no verification component, so a later addition fails.
* **Files.** `services/card-service/src/main/resources/openapi/card-api.yaml`.

#### D-CARD-EXPIRY-MONTH-YEAR, also D-CARD-EXPIRY-DAY-CLAMP — a card update carries the expiry month and year, and no day

* **Baseline behaviour.** [`COCRDUPC.cbl`](../../app/cbl/COCRDUPC.cbl) stores the
  expiry as one ten-character field and lets an operator edit only the month and the
  year. Its day input is rendered non-display unconditionally at **L1285**, occupying
  exactly the slot between the status pair at **L1274-L1283** and the month pair at
  **L1287-L1296** where a seventh validation pair would have sat. Across all four arms
  of the redisplay `EVALUATE` — **L1100-L1106**, **L1107-L1112**, **L1113-L1123** and
  **L1124-L1129** — the day is never redisplayed from an entered value: the branch taken
  when changes have been made redisplays the four editable fields from their new values
  at **L1114-L1117** and takes the day alone from the pre-edit snapshot at **L1123**,
  with the statement that would have moved the entered day commented out at **L1122**.
  There is no `1270-EDIT-EXPIRY-DAY` paragraph, so the day is never validated, and the
  four attribute statements that would highlight it — **L1178**, **L1185**, **L1197**
  and **L1205** — are commented out. The detail screen has no day field at all:
  [`COCRDSL.CPY`](../../app/cpy-bms/COCRDSL.CPY) declares `EXPMONI` at **L84** and
  `EXPYEARI` at **L90** and no day. The record written at **L1467-L1474** does compose a
  day into the stored date, and because the field is non-display and always redisplayed
  from the snapshot, the day written equals the day read — an invariant the program holds
  **emergently**, as a consequence of how the screen is painted.
* **Target behaviour.** `CardUpdateRequest` carries `expirationMonth` and
  `expirationYear` and **no day member of any kind**. The day the stored date keeps is
  the day it already held, supplied from the row's own current value — **except where
  that day does not exist in the submitted month, in which case it is brought back to
  that month's last day.** The response shapes continue to carry the whole
  ten-character stored date, so the request and the response are deliberately not the
  same shape.
* **The clamp, stated explicitly.** A stored date of `2027-01-31` updated to month `02`
  becomes `2027-02-28`, not `2027-01-31` and not a refusal. This is registered as
  divergence **`D-CARD-EXPIRY-DAY-CLAMP`** and is asserted by
  `CardExpiryParityTest` in `services/card-service/src/test`. An earlier revision of
  this entry said only that the stored day is retained, which was **not true of every
  input** and did not register the clamp at all — so the one combination where the
  target cannot reproduce the baseline byte-for-byte was the one the document was
  silent about. Three properties make the clamp the right resolution rather than the
  convenient one. Carrying the day through unchanged is **not available**: the target
  column is a true `DATE`, and the impossible combination the baseline can hold — it
  concatenates characters into a ten-byte field at
  [`COCRDUPC.cbl:1467-1474`](../../app/cbl/COCRDUPC.cbl) without consulting a calendar
  — has no representation in it. Refusing the update would fail it over a value the
  caller was **never shown and cannot edit**, since the day is non-display in the
  baseline and absent from the request shape, so the caller could not act on the
  refusal. Bringing the day back preserves the two parts the caller did choose, which
  is the outcome closest to what was asked for. The cost is that one specific day value
  is not round-tripped exactly, and it is the one part of the date nothing in the
  baseline lets a user set.
* **Category.** Documented divergence — one stored field is edited as two, and a
  non-editable part of it is not expressible in the request.
* **Why the difference is accepted.** An earlier revision of the migrated request carried
  the whole ten-character date, on the reasoning that ten separated characters are the
  same form **L1467** assembles and that the day-equals-stored-day invariant could be
  enforced against stored state in the service layer. Two things were wrong with that.
  The first is a matter of fact: no such service layer exists in this context, so the
  invariant was enforced nowhere and the request published a day a caller could set to
  any value it liked — which is not a stricter reading of the baseline but a capability
  the baseline does not offer, on a field it deliberately renders non-display. The second
  is a matter of shape: an invariant that a submitted value must equal the stored value
  means the submitted value carries no information, and a member carrying no information
  should not be in the request. Removing the member removes the need to enforce anything
  about it, which is smaller and more durable than enforcing it correctly. Alternatives
  Considered: keeping the date and refusing any submission whose day differs from the
  stored one — rejected because every caller would first have to read the card to learn
  the one day value its update may carry, and a caller that got it wrong would receive a
  field error naming a field the screen has not got. Alternatives Considered: keeping the
  date and silently overwriting the submitted day with the stored one — rejected because
  it accepts a value and then ignores it, which is the least discoverable of the three.
  Trade-offs: the accepted cost is that a caller cannot round-trip a detail response
  straight back as an update. That asymmetry matches the screens, where the detail map
  declares a month and a year and no day while the stored record holds a whole date: the
  response reports what is stored and the request carries what is editable, and those
  were never the same set of fields.
* **Where it is verified.** `CardDtoContractTest` asserts the request declares exactly
  the five members it declares, that none of their names contains "day", and that the
  month and year bounds are the two windows **L95** and **L99** declare;
  `CardApiContractTest` asserts the published schema declares the same two properties.
* **Files.**
  `services/card-service/src/main/java/com/carddemo/card/dto/CardUpdateRequest.java`,
  `services/card-service/src/main/resources/openapi/card-api.yaml`,
  `ui/src/api/cards.ts`,
  `ui/src/screens/cardUpdate/index.tsx`.

#### D-CARD-MASKED-FORM-ENFORCED — a card response refuses an unmasked number at construction

* **Baseline behaviour.** No screen in the card set displays a masked rendering at all:
  [`COCRDSL.CPY`](../../app/cpy-bms/COCRDSL.CPY), `COCRDLI.CPY` and `COCRDUP.CPY` each
  present the card number in full, because the terminal's data stream travels between the
  terminal and the region and reaches no other store. Masking has no baseline counterpart
  to diverge from; what diverges is the strength of the guarantee the migrated system
  makes about it.
* **Target behaviour.** Every non-administrative card response carries the number only as
  a masked rendering, and the member holding it is constrained to the masked FORM — a run
  of mask characters followed by the visible trailing digits — both by the published
  schema and by a compact constructor that refuses anything else.
* **Category.** Documented divergence — a disclosure narrowing with no baseline
  counterpart, strengthened from a declared bound to an enforced one.
* **Why the difference is accepted.** An earlier revision bounded those members by LENGTH
  alone, reasoning that a sixteen-digit pattern would refuse the very value the member is
  defined to hold. The reasoning was sound and the conclusion drawn from it was not: a raw
  sixteen-digit number is itself exactly sixteen characters wide, so it satisfied the
  bound and nothing in the type system refused it. The constraint now describes the
  rendering the shared masker produces, and the expression is published FROM that masker
  rather than restated on each shape, so the rule has one definition and cannot drift from
  the renderer. The constructor check exists because a response is never validated — bean
  validation runs on a body the framework is asked to validate, not on one it serialises —
  so a mapper publishing raw storage would otherwise produce a response that violates its
  own declared constraint and is sent anyway. Assumptions: the check REFUSES and does not
  mask, so the mapping layer remains the one place the rendering is produced and the one
  place the disclosure decision is taken. Trade-offs: a diagnostic rendering of these
  shapes now withholds the cardholder's name, account number and expiry, so a developer
  reading a log line sees less; that is smaller than the alternative, in which every such
  line disclosed a real cardholder.
* **Where it is verified.** `CardDtoContractTest` asserts that an unmasked number is
  refused at construction, that the refusal does not echo it, that a properly masked
  rendering is still accepted, that seven differently-wrong renderings are refused, and
  that no diagnostic rendering carries a name, an unmasked account number or an expiry;
  `CardApiContractTest` asserts every schema carrying the rendering constrains the form.
* **Files.**
  `services/common-lib/src/main/java/com/carddemo/common/security/CardNumberMasker.java`,
  `services/card-service/src/main/java/com/carddemo/card/dto/CardDetail.java`,
  `services/card-service/src/main/java/com/carddemo/card/dto/CardSummary.java`,
  `services/card-service/src/main/java/com/carddemo/card/dto/CardUpdateRequest.java`,
  `services/card-service/src/main/resources/openapi/card-api.yaml`.

#### D-LASTKEY-RECEIVED — the page cursor names only rows the caller received

* **Baseline behaviour.** After filling a page of seven, `COCRDLIC.cbl` captures the
  seventh row's key at **L1194-L1195**, then issues one further read at **L1197** purely
  to discover whether anything follows — and on finding a row it **overwrites** the
  captured key with that row's key at **L1212-L1214**. The value it retains therefore
  identifies the **eighth** record on a page of seven, a row the terminal never
  displayed.
* **Baseline behaviour, second half — a short page leaves the field unrefreshed.**
  [`COTRN00C.cbl`](../../app/cbl/COTRN00C.cbl) shows the same field from the other side.
  Its capture of the trailing identifier sits at **L438-L439**, inside the branch that
  runs for the tenth display slot, so a turn whose fill loop populated fewer than ten
  slots reaches no capture at all and the field keeps whatever the previous turn placed
  there. The field is a communication-area member that survives the turn, so the stale
  value remains available to be sent back.
* **Target behaviour.** The page envelope's `lastKey` names the last row the caller
  **actually received**, and `hasNext` carries the look-ahead result separately. That
  holds for a short page as much as for a full one: the boundary is sealed from the last
  row of the page being returned whatever its length, because a stateless handler has no
  previous turn to inherit a position from and the boundary it returns is the only
  position the next request can carry.
* **Category.** Documented divergence — cursor identity.
* **Why the difference is accepted.** Splitting the two facts is what makes the cursor
  verifiable: every token a client is given corresponds to something it holds, so a
  client can check a returned cursor against its own page rather than trusting a value
  that names a row it never saw. The baseline conflated them because one field carried
  both, and on a full page the observable paging behaviour is unchanged — the same rows
  appear on the same pages, in the same order, with the same next-page availability. The
  short-page half of the difference is observable on exactly one input, a forward step
  taken from a page that was not full: the baseline resumes from the earlier full page's
  trailing identifier and so returns rows the caller already holds, where the target
  resumes after the short page's own last row. Both halves follow from one target rule
  rather than from two decisions, which is why they are registered together.
* **Where it is verified.** `CardApiContractTest` asserts all four page members —
  `items`, `firstKey`, `lastKey` and `hasNext` — are required and that `lastKey` is
  nullable exactly when `items` may be empty;
  `PageResponse`'s own tests assert the look-ahead sets `hasNext` without moving the
  cursor; `TransactionListServiceTest` asserts the trailing boundary names the tenth row
  and not the eleventh on a full page, and the last row returned on a short one.
* **Files.** `services/card-service/src/main/resources/openapi/card-api.yaml`,
  `services/common-lib/src/main/java/com/carddemo/common/web/PageResponse.java`,
  `services/transaction-service/src/main/java/com/carddemo/transaction/service/TransactionListService.java`.

#### D-PASSWORD-CHALLENGE — the credential-change exchange has no baseline counterpart

* **Baseline behaviour.** [`COSGN00C.cbl`](../../app/cbl/COSGN00C.cbl) compares a
  stored eight-character password directly at **L211-L256** and has **no notion** of a
  credential that must be changed before use. There is no such exchange to migrate.
* **Target behaviour.** `POST /api/v1/auth/challenge` completes a
  `NEW_PASSWORD_REQUIRED` challenge by exchanging a single-use session for a new
  credential.
* **Category.** Documented divergence — an operation added with no baseline source.
* **Why the difference is accepted.** It follows necessarily from `D-4`, the entry that
  records the plaintext credential field not being carried forward: identity moved to a
  managed user pool, and **both** populations of that pool are created with a temporary
  password — the seeded users by `infra/modules/cognito/seed_user_bootstrap.py` at
  provisioning time, and the runtime-created users by `CognitoUserProvisioningService`
  per `D-RUNTIME-CREDENTIAL-HANDOVER` below. That makes this exchange the **first**
  thing every user of either population does. Declining it
  would leave every provisioned user unable to sign on at all. Because there is no baseline
  counterpart, **no message literal is carried across** and none of the three sign-on
  sentences is reused — inventing a fourth sign-on message would breach transformation
  rule T8 in the opposite direction, by presenting new text as though it were the
  baseline's.
* **Where it is verified.** `AuthApiContractTest` asserts the operation is
  unauthenticated in both the contract and the filter chain, that its response
  discriminates on the outcome, and that the session and new-password components are
  write-only and required.
* **Files.** `services/auth-service/src/main/resources/openapi/auth-api.yaml`,
  `services/auth-service/src/main/java/com/carddemo/auth/config/SecurityConfig.java`.

#### D-RUNTIME-CREDENTIAL-HANDOVER — a created user's first credential is returned once, in the create response

* **Baseline behaviour.** [`COUSR01C.cbl`](../../app/cbl/COUSR01C.cbl) collects the new
  user's password on the add screen — the field is validated at **L136**, its sentence
  emitted at **L138**, its cursor set at **L140** — and moves the submitted value into
  the record at **L157**, at zero-based offset 48 of the 80-byte layout declared at
  [`CSUSR01Y.cpy`](../../app/cpy/CSUSR01Y.cpy) **L21**. The administrator therefore
  chooses the credential, types it, and knows it; the handover problem does not exist,
  because the value never leaves the administrator's hands.
* **Target behaviour.** No credential is accepted on `POST /api/v1/auth/users`.
  `CognitoUserProvisioningService.provision` generates a policy-compliant one-time
  value, supplies it to the pool as the created account's temporary password, and
  publishes it to a per-user Secrets Manager entry encrypted with the customer-managed
  key; the response names that entry in the `credentialSecretName` property of
  `CreatedUserResponse` and never carries the value. The account is created in the
  provider's force-change state, so the value buys one sign-on and is then replaced
  through `POST /api/v1/auth/challenge` per `D-PASSWORD-CHALLENGE`. No column stores it,
  and both `ProvisionedIdentity` and `CreatedUserResponse` override their generated
  rendering so that a record logged rather than a string cannot carry the locator
  either.
* **Category.** Documented divergence — the credential's origin moves from the caller to
  the service, and its lifetime from permanent to single-use.
* **Why the difference is accepted.** Accepting a caller-chosen credential would
  reinstate two of the four faces `D-4` removes: this boundary would have to validate a
  credential against a policy it does not own, and the submitted value would appear in
  the request log of every intermediary between the caller and here. Generating it and
  not returning it is not an option either, and that is the specific correction this
  entry records: an earlier revision created the account with delivery suppressed and no
  supplied password, so the pool minted one internally and sent it nowhere. The pool
  declares no email or phone attribute over which a reset message could be delivered,
  `seed_user_bootstrap.py` runs only inside `terraform apply` and reaches only the seed
  identities, and no reset operation exists in the reactor — so a created account was
  one nobody could ever sign on to. Alternatives Considered: writing each runtime
  credential to Secrets Manager as the seed path does. Rejected because an administrator
  using this contract holds a browser session and not a grant on a secrets store, so the
  handover would cross an authorization boundary the operation does not have, and because
  the number of secrets would then grow with the number of users, each needing its own
  deletion. Trade-offs: the value travels in a response body a client may hold in
  memory for the life of the calling view, and an administrator who discards it must
  create the user again. That is accepted against three properties: single-use, never
  persisted, never logged.
* **Where it is verified.** `FirstSignOnHandoverTest` drives the whole journey over one
  substituted pool shared by both halves — the account is created **with** a credential
  of the declared length, the caller is handed the same value, presenting it raises the
  `NEW_PASSWORD_REQUIRED` challenge, answering that challenge yields a token set, a
  different credential is refused rather than challenged, the blank value a broken
  handover would leave a caller holding reaches no pool at all, and no log line emitted
  anywhere along the way contains either credential.
  `CognitoUserProvisioningServiceTest` asserts the credential is supplied to the create
  call, that it satisfies all four required character classes on 64 consecutive draws,
  that 16 draws are distinct, and that `ProvisionedIdentity.toString` withholds it.
  `AuthApiContractTest` and `UserControllerTest` assert the published 201 shape.
* **Files.** `services/auth-service/src/main/java/com/carddemo/auth/service/CognitoUserProvisioningService.java`,
  `services/auth-service/src/main/java/com/carddemo/auth/service/ProvisionedIdentity.java`,
  `services/auth-service/src/main/java/com/carddemo/auth/dto/CreatedUserResponse.java`,
  `services/auth-service/src/main/java/com/carddemo/auth/service/UserService.java`,
  `services/auth-service/src/main/java/com/carddemo/auth/api/UserController.java`,
  `services/auth-service/src/main/resources/openapi/auth-api.yaml`,
  `ui/src/api/auth.ts`.

#### D-SEED-ENCODING-AUTHORITY — the EBCDIC extract decides the DEFAULT interest rate

* **Baseline behaviour.** The disclosure-group seed ships **twice**, and the two
  copies disagree about money. Nine datasets exist in both encodings, and across all
  fifty-one records of this one they are identical except at a single field of a single
  record: record 34, the `DEFAULT` group's `('07','0001')` combination, holds
  `DIS-INT-RATE` as `00150{` in
  [`app/data/EBCDIC/AWS.M2.CARDDEMO.DISCGRP.PS`](../../app/data/EBCDIC/AWS.M2.CARDDEMO.DISCGRP.PS)
  and as `00000{` in [`app/data/ASCII/discgrp.txt`](../../app/data/ASCII/discgrp.txt).
  Under `DIS-INT-RATE PIC S9(04)V99` at [`CVTRA02Y.cpy`](../../app/cpy/CVTRA02Y.cpy)
  those decode to **15.00** and **0.00**. Measured rate multiplicities differ with it:
  29/16/6 versus 30/15/6 for 0.00/15.00/25.00. That row is the one
  [`CBACT04C.cbl`](../../app/cbl/CBACT04C.cbl) **L415-L441** falls back to when an
  account's own group key is absent, so the disagreement is not cosmetic — it is the
  rate a fallback account accrues at.
* **Target behaviour.** Where a dataset ships in both encodings the **EBCDIC `.PS`
  extract is authoritative**, so this row is 15.00 on every route into
  `reference.disclosure_groups`: the Flyway seed and the ETL loaders now agree.
* **Category.** Documented divergence — seed-source authority, resolving a
  contradiction internal to the baseline rather than departing from it.
* **Why the difference is accepted.** There is no reading under which both values can
  be kept, so the only question is which extract to trust, and the extracts are not
  peers. The `.PS` files are the mainframe extracts the baseline programs read —
  fixed-length blocked records carrying sign overpunch and packed fields — while the
  `.txt` files are conversions of them whose fidelity is already known to be imperfect
  from two independent shape findings: `cardxref.txt` has lost its trailing `FILLER`,
  and three of the nine have acquired CRLF terminators. Neither is a property of the
  source data. A conversion that dropped fourteen bytes from one file is not the form
  to trust when it disagrees about a rate. Alternatives Considered: seeding the ASCII
  value for uniformity with the six other reference inserts, which is the cheaper edit.
  Rejected because uniformity of provenance is worth nothing beside uniformity of
  RESULT — two routes into one table must not disagree about an operand **L464-L465**
  multiplies a balance by. What makes the choice more than a preference is the baseline's
  own load job: [`DISCGRP.jcl`](../../app/jcl/DISCGRP.jcl) **L56-L61** defines the VSAM
  cluster and REPROs into it from the DD at **L57**, which names
  `DSN=AWS.M2.CARDDEMO.DISCGRP.PS` — the EBCDIC extract. No JCL in the repository loads the
  `.txt` form at all, so 15.00 is the value the reference system itself reads.
  Alternatives Considered: reconciling the two extracts by
  editing one. Refused outright, `app/**` being reference-only; the difference is
  resolved by choosing a source and never by changing a byte of either.
* **Why the parity oracle is unaffected.** Three reference artefacts read 0.00 for this row
  — `app/data/ASCII/discgrp.txt` row 34,
  [`tests/fixtures/interest/default_fallback/discgrp.txt`](../../tests/fixtures/interest/default_fallback/discgrp.txt)
  line 17 and [`tests/mocks/mock_discgrp.txt`](../../tests/mocks/mock_discgrp.txt) line 33 —
  and the objection they invite has to be answered rather than left standing. The latter two
  are INPUTS the suite hands to the program under test: a golden-master comparison supplies
  the same 0.00 to the reference program and to its migrated equivalent, so it is indifferent
  to what any seed holds. The reference-service fixture tree copies the ASCII form for a
  separate and deliberate reason — in the EBCDIC form the `DEFAULT` group prices `07|0001`
  identically to group `A000000000`, so a fallback fixture built from it could not
  discriminate a fallback from a direct hit.
* **Where it is verified.** Applying `V1__reference.sql` and `V2__seed_reference.sql`
  to an empty schema yields `('DEFAULT   ','07','0001')` at `15.00` and rate
  multiplicities of 29/16/6, matching the EBCDIC extract's own counts; the two
  extracts' single differing record is reproducible directly from the raw bytes by the
  command `data-migration/README.md` carries beside its twin-divergence table.
* **Files.**
  `services/reference-service/src/main/resources/db/migration/V2__seed_reference.sql`,
  `data-migration/README.md`.

#### D-AUTH-DIAGNOSTIC-DISCLOSURE — authorization segment bytes are withheld by default

* **Baseline behaviour.** The two authorization segments,
  [`CIPAUSMY.cpy`](../../app/app-authorization-ims-db2-mq/cpy/CIPAUSMY.cpy) **L19-L31**
  and [`CIPAUDTY.cpy`](../../app/app-authorization-ims-db2-mq/cpy/CIPAUDTY.cpy)
  **L19-L54**, classify nothing. A copybook declares widths and usages and carries no
  notion of a field whose content may not be logged, so the baseline has no disclosure
  policy to migrate — the question is created by the target's own diagnostics.
* **Target behaviour.** **Both** layout catalogues — the ETL's
  `layouts.py` and the shared kernel's `CopybookLayout.java` — resolve both segments
  through the same explicit **disclosable allowlist**: a field's raw bytes may appear in a
  decode diagnostic only when it is named as a closed-domain code, a date or time, a
  counter, the account key or the trailing pad. Everything else — the card number, the card
  expiry, both amounts, the merchant identity, name, city and postal code, the
  transaction identity, the customer identifier and the four stored balance and limit
  amounts — is withheld, and a field added to either segment later is withheld until it
  is deliberately named.
* Refactoring Rationale: This entry once described the ETL alone, because for a time the
  ETL alone applied the policy. The Java transcription of the same two copybooks marked one
  field sensitive in the detail segment and **none at all** in the summary, so the same
  malformed byte produced a bare diagnostic under Python and a diagnostic naming the credit
  limit, both balances or the transaction amount under Java. The Java side now carries
  `CopybookLayout.AUTHORIZATION_DISCLOSABLE_FIELDS` and the same fail-closed helper, and the
  two sets are held equal by a test rather than by intent.
* **Category.** Documented divergence — diagnostic disclosure policy, additive.
* **Why the difference is accepted.** The same record content is already classified
  this way one language over: `CsvAuthCodec.SENSITIVE_FIELD_NAMES` withholds exactly
  this set over the authorization wire. One record cannot carry two sensitivities, and
  the wider of the two has to win, because a malformed byte is precisely the case that
  produces a log line — so the narrower policy disclosed in full, on the requests
  already going wrong, what the wider one refuses to emit at all. Trade-offs: an
  allowlist is longer to read than the four extra markers it replaces, and that is the
  point: with a denylist a newly added field is disclosable until somebody remembers to
  mark it, so the cost of forgetting is disclosure rather than silence.
* **Where it is verified.** In both languages, and the cross-language equality is
  verified once rather than claimed twice.
  `AuthorizationDisclosurePolicyTest` in `services/common-lib` reads the ETL's own
  `layouts.py`, parses its allowlist literal and asserts set equality with the Java
  constant, so a name added on either side alone fails the Java build. Both suites
  additionally assert the fail-closed property over **every** field of both segments, that
  the allowlist names no field neither segment declares, that the two withheld sets are
  exactly seven and nine members, and that the geometry checks still close at 100 and 200
  bytes after the policy is applied. The disclosure itself is probed rather than inferred: a
  decode of each withheld field from an all-`0xFF` image must produce a message naming the
  field and its offset and carrying neither the packed codec's nibble reading nor a
  delegated cause detail — asserted against a **disclosable** field of the same storage
  regime, so a message that had merely become useless for everybody would not pass.
* **Files.** `services/common-lib/src/main/java/com/carddemo/common/codec/CopybookLayout.java`,
  `data-migration/src/carddemo_migration/copybook/layouts.py`,
  `data-migration/src/carddemo_migration/copybook/ebcdic_codec.py`.

#### D-ETL-CORPUS-DIAGNOSTIC-DISCLOSURE — the same allowlist regime now covers all twenty-two records

* **Baseline behaviour.** As for the authorization segments above, no copybook in
  [`app/cpy`](../../app/cpy) or in the export projection
  [`CVEXPORT.cpy`](../../app/cpy/CVEXPORT.cpy) classifies anything. There is no baseline
  disclosure policy to migrate for any record; the question exists only because the target
  renders record content in diagnostics and in a `decode-record` command that the baseline
  has no counterpart to.
* **Target behaviour.** `layouts.py` applies a second allowlist,
  `_CORPUS_DISCLOSABLE_FIELDS`, to the twenty records that are not authorization segments,
  and an **import-time audit** refuses to load the module if any record it declares leaves a
  field disclosable that neither allowlist names. A field is admitted for one of **four**
  reasons: a date or time, a closed-domain code, a count or sequence rather than a money
  value, or the trailing pad.
* **A fifth reason was withdrawn, and the withdrawal is the correction this entry now
  records.** The allowlist also admitted "an **account** identifier the published REST
  contracts already render in full", naming `ACCT-ID`, `EXP-ACCT-ID`, `CARD-ACCT-ID`,
  `EXP-CARD-ACCT-ID`, `XREF-ACCT-ID`, `EXP-XREF-ACCT-ID` and `TRANCAT-ACCT-ID`. That reason
  does not hold. A REST path and an operator diagnostic are different surfaces with different
  audiences, and [`observability.md`](observability.md) settles it directly: account
  identifiers are among the values a diagnostic must **omit** — "not its content, not its
  length, and not a digest of it". All seven were already marked sensitive at their
  declaration sites, so no rendering changed; what changed is the **declared** policy and the
  audit's admitted set, which is where the divergence had accumulated. The seven names now sit
  in `_PROHIBITED_NAMES` in
  [`test_corpus_disclosure.py`](../../data-migration/tests/test_corpus_disclosure.py), so a
  record that ever discloses one fails a test rather than passing an audit.
* Refactoring Rationale: the authorization segments were closed and the other twenty were
  not, and a per-field opt-in makes silence mean *disclose*. Measured across those twenty,
  **116 distinct field names** were rendered verbatim by every diagnostic — every account
  balance, credit limit and cycle total, every transaction and daily-transaction amount, the
  transaction-category balance, every merchant name, city, postal code and identifier, the
  customer credit score, the free-text transaction description, and the postal codes of both
  the account and the customer master. Closing them leaves, **re-measured against the module
  as it now stands**, `127` withheld and `112` disclosable field positions over `239` fields
  across the twenty-two records, and `81` distinct disclosable field names.
  - Refactoring Rationale: those three figures previously read "65 disclosable names" and
    "120 withheld / 119 disclosable", and they were stale rather than approximate — records
    were added to the corpus after the figures were written and nothing recomputed them. They
    are now stated as what a walk of `layouts._declared_layouts()` reports, which is the same
    walk the import-time audit and `test_corpus_disclosure.py` use, so a reader can reproduce
    them rather than trust them.
* **Category.** Documented divergence — diagnostic disclosure policy, additive and
  target-only.
* **Why the difference is accepted.** Three classifications look inconsistent read one at a
  time and are deliberate read together, and each follows a precedent already set one record
  over. A **card expiry** date is withheld although it is a date, matching
  `PA-CARD-EXPIRY-DATE`,
  because it is the value a card-not-present authorization asks for alongside the number;
  account open, expiration and reissue dates stay rendered, and the account expiration date
  is the value the posting reject-103 boundary turns on. A **postal code** is withheld while
  a state and a country code are rendered, because the three differ in identifying power
  rather than in kind. The three pure reference records — disclosure group, transaction type,
  transaction category — are rendered **in full**, rate and description included, because
  every byte is seeded configuration shared by a whole group and linked to no customer, and
  withholding the rate would make the `DEFAULT`-group fallback diagnostic unreadable.
  Trade-offs: withholding a record's key costs real ground — every account-keyed and
  identity-keyed record, which is the account master, the card master, the cross-reference, the
  category balance, the customer master and all four transaction shapes, no longer names the row
  an operator would look up, so a failure is localised by the keyed tag and then reproduced
  against the source dataset. The alternative was rendering a card-linked transaction
  identifier, a customer identifier and the account identifier that joins them in every
  diagnostic.
* **A narrower partial reveal than the parity oracle's.** The last-four concession now covers
  the four card-number field names only. `CUST-SSN` was in that set and is removed: "quoted
  by its last four" is a practice that is safe for a sixteen-digit card number, whose last
  four leave twelve unknown, and much less so for a nine-digit national identifier, whose
  leading five are the issuing area and group — and the ETL never matches on the field, so
  withholding it costs nothing. `tests/helpers/record_codec.py` **L1589** still carries five
  names including `CUST-SSN`; that file is the parity oracle and is reference-only, so it is
  not edited. Diverging in this direction cannot change a parity result, because the oracle
  compares record **bytes** after timestamp normalisation and never compares a masked
  rendering — a narrower mask can only withhold more from a log line.
* **One rendering that claims nothing.** In `decode-record`, a withheld **character** field
  shows a keyed tag of its declared width and a withheld **numeric** field shows the fixed
  literal `<withheld>`. A decoded number renders as its value — `194.00` for a twelve-byte
  zoned balance — so it is not the declared width, and the only chunk a tag could be computed
  from there is a constant, which would render two different balances identically while
  looking value-derived. That is the "a diff reports no difference where one exists" failure
  the keyed tag exists to prevent, so the literal is printed instead.
* **Where it is verified.** Twice, and on output rather than on flags.
  `layouts.py` refuses to import when the audit finds an unnamed disclosure, naming the
  record and field; `data-migration/tests/test_corpus_disclosure.py` re-runs the audit over
  the fully imported module — closing the gap that a record declared below the audit's own
  call site would otherwise open — and asserts, for every field of every one of the
  twenty-two records, that a masked rendering reproduces an admitted field exactly and
  reproduces a withheld field nowhere, using a sentinel unique to each field so a leak is
  attributable. It additionally asserts that a hand-listed set of prohibited names is
  withheld wherever declared, that the allowlist names no undeclared field, that the audit
  reports a synthetic unclosed record, and that neither national-identifier field reveals its
  trailing characters.
* **Files.** `data-migration/src/carddemo_migration/copybook/layouts.py`,
  `data-migration/src/carddemo_migration/cli.py`,
  `data-migration/tests/test_corpus_disclosure.py`, `data-migration/README.md`.

#### D-ETL-MASK-KEY-STRENGTH — weak masking-key material is refused rather than accepted

* **Baseline behaviour.** None. The baseline has no redaction tag, no masking key and no
  diagnostic that renders record content, so there is nothing here to preserve or diverge
  from — the control exists only because the target introduced the tag.
* **Target behaviour.** `layouts.py` resolves `CARDDEMO_MASK_HMAC_KEY` through a validator
  that requires **canonical standard base64 decoding to at least 32 bytes** — the
  HMAC-SHA-256 output size — and refuses a value that is not valid standard base64, a
  non-canonical spelling of valid material, material below the floor, or material that is a
  single repeated byte. Every refusal names the variable and the generation command and
  **never the value**. An **unset** variable keeps its documented meaning and takes the
  32-byte process-scoped random fallback; a variable that is **present but empty or
  whitespace-only** is refused, because absence means "run without a supplied key" while
  presence with no content means a key was expected and did not arrive — the shape a failed
  secret projection or an empty secret version takes. Accepting the second silently defeated
  the one mode that needs a supplied key at all, the cross-run verification pass, which then
  derived per-process tags and compared every field unequal with no diagnostic anywhere.
* Refactoring Rationale: any non-empty string was previously accepted and used by its
  UTF-8 bytes, so the enforcement contradicted the documented contract in the worst
  direction: an operator following `data-migration/README.md` got 32 random bytes while an
  operator typing a memorable phrase got five or six, and nothing reported the difference.
  The tag exists to make a redacted card number or national identifier unconfirmable, and
  that property rests entirely on the key being unguessable — with a guessable key an
  adversary holding a candidate recomputes the same HMAC and compares. A weak key therefore
  does not weaken the tag gradually; it returns it to the unkeyed digest it replaced.
* **Category.** Target-only cryptographic control, additive.
* **Why the shape is what it is.** Trade-offs: exactly one encoding is accepted, because a
  string that could be read as raw bytes, as base64 or as hexadecimal would denote different
  keys under different readings and a rotation between two readings of one string would
  silently change every tag. Two limits are recorded rather than overclaimed. A **hexadecimal**
  key is *accepted*, because 64 hexadecimal characters are also valid base64 and decode to 48
  bytes — no string can carry its own intended encoding, and the outcome is harmless because
  the material is longer than the floor and no more guessable. And the repeated-byte refusal
  is a **structural** floor, not an entropy test: no test on a single sample can establish
  that material was randomly generated, so only the class that is unmistakably weak and easy
  to produce by accident is refused. Alternatives Considered: stretching a passphrase with
  PBKDF2 or scrypt, rejected because the value is delivered from Secrets Manager by the
  deployment and no human types it, so a key-derivation function would add a cost parameter
  to agree on across runs in order to solve a problem this deployment does not have.
* **Where it is verified.** `data-migration/tests/test_corpus_disclosure.py` asserts the floor
  equals the hash's own output size, drives seven refusal classes through the resolver, proves
  the canonicality rule is reachable by flipping an unused trailing bit of otherwise
  conforming material, asserts no refusal echoes the value and every refusal names the
  remedy, and proves the enforcement is actually consulted by driving it through
  `mask_record` rather than through the resolver alone. A companion case asserts the tag is a
  function of the key — without which every refusal would be theatre.
* **Files.** `data-migration/src/carddemo_migration/copybook/layouts.py`,
  `data-migration/tests/test_corpus_disclosure.py`, `data-migration/README.md`,
  `docs/runbooks/deploy.md`, `infra/envs/dev/variables.tf`, `infra/envs/prod/variables.tf`.

#### D-AUTH-AMOUNT-TOLERANT-READ — the declared-width amount token is emitted and read whole

* **Baseline behaviour.**
  [`CCPAURQY.cpy`](../../app/app-authorization-ims-db2-mq/cpy/CCPAURQY.cpy) **L27**
  declares `PA-RQ-TRANSACTION-AMT PIC +9(10).99`, which is **fourteen** characters.
  The only consumer of that wire,
  [`COPAUA0C.cbl`](../../app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl), receives
  ordinal nine of its `UNSTRING` at **L364** into `WS-TRANSACTION-AMT-AN PIC X(13)`
  declared at **L63** — **thirteen** — and converts it with `FUNCTION NUMVAL` at
  **L376-L377**. An alphanumeric move into a shorter item drops the **last** character,
  and `NUMVAL` accepts one fraction digit as readily as two, so that consumer acts on a
  declared-width token as a different, entirely plausible value: a `+0000000100.99` on the
  wire is acted on as **100.90**. Nothing is raised anywhere.
* **Target behaviour.** `CsvAuthCodec` **emits** the copybook's fourteen
  (`REQUEST_MONEY_WIDTH`), so its canonical wire length is `REQUEST_WIRE_LENGTH` = 170,
  and it parses every character of a token at that width. It additionally **accepts** the
  thirteen-character form the reference receiver's own intermediate produces, and a token
  narrower still — one that lost a cents digit rather than its sign position — is
  **refused by name**, because the parser requires exactly two fraction digits.
* **Category.** Documented divergence — the reference receiver's truncation is a property
  of one consumer's working storage and is not reproduced.
* **Why the difference is accepted.** The copybook picture is normative for this wire, so
  narrowing the emission to match one consumer's intermediate would publish a payload that
  disagrees with the layout this repository ships. Alternatives Considered: reproducing the
  truncation on read, so that a fourteen-character token yields the amount the reference
  would act on. Rejected outright — it divides an amount by ten in the cents position with
  no diagnostic, which is exactly the class of defect the fixed-point rule exists to
  prevent. Alternatives Considered: emitting thirteen so the reference receiver is never
  handed a character it drops. Rejected because it makes every other consumer wrong to
  protect one, and because the reference consumer keeps thirteen of whatever it is sent
  either way — the truncation is in its `PIC X(13)` receiving item, not in the payload.
  Alternatives Considered: refusing the thirteen-character form on decode. Rejected because
  it would dead-letter a payload whose intent is unambiguous, purely because a third party
  built against that intermediate width — and no request producer exists here to correct.
  Reading both widths and emitting the declared one is the only combination that neither
  loses a value nor loses a message.
* **Where it is verified.** `AuthRequestWireFixtureTest` reads both committed fixtures from
  the classpath and asserts that the three amount vectors are the published wire length and
  decode to the negative, maximum and zero values they spell; that the receiver-width
  payload is one byte shorter and still decodes to the full value; that a token which lost
  its final cents digit is refused by name; and that re-emitting a decoded receiver-width
  payload produces exactly `REQUEST_WIRE_LENGTH` bytes with a fourteen-character amount.
* **Files.** `services/common-lib/src/main/java/com/carddemo/common/codec/CsvAuthCodec.java`,
  `services/authorization-service/src/main/resources/openapi/authorization-api.yaml`,
  `docs/architecture/messaging-contracts.md`.

#### D-STMT-PAIRED-BLANK-NAME — an empty middle name leaves the two statement artifacts disagreeing

* **Baseline behaviour.** [`CBSTM03A.CBL`](../../app/cbl/CBSTM03A.CBL) assembles the
  customer name at **L462-L469** by concatenating three 25-character parts, each
  **delimited by a blank** and each followed by an unconditional one-character blank
  literal at **L463**, **L465** and **L467**. The literals are separate operands of the
  concatenation, not separators emitted between non-empty parts, so an **empty or
  all-blank middle name yields two consecutive blanks** in the 75-character item declared
  at **L91**. The plain-text artifact prints that item whole at **L488**. The markup
  artifact does not: **L560** moves the same item into the 50-character item declared at
  **L220**, and **L563** then transfers it **delimited by two blanks** and re-appends
  exactly two, so the markup name cell stops at the paired blank and shows the **first
  name alone**. One customer, one assembly, two artifacts that disagree about the name.
* **Target behaviour.** Identical. `StatementTextMapper.assembleName` emits all three
  blank literals unconditionally, and `PreparedHeaderFields.markupName()` narrows without
  trimming, so the paired blank survives into the value the markup rendering reads.
* **Category.** Reproduced baseline behaviour — recorded because it looks like a defect.
* **Why the difference is accepted.** There is no difference, and that is the entry. The
  paired blank is the kind of artifact a later reader repairs on sight — collapsing it
  reads as tidying, and the plain-text band would then differ from the golden statement in
  the bytes of its first line, which transformation rule **T9** forbids. Recording it here
  is what makes the collapse visibly a change rather than a clean-up. The consequence is
  stated in full so nobody has to rediscover it: for a customer with no middle name, the
  two artifacts genuinely show different names, and neither is wrong.
* **Where it is verified.** `StatementTextMapperTest`
  `assembleNameEmitsThreeBlankLiteralsUnconditionally` asserts the two blanks at their own
  offsets and asserts that the narrowing keeps them;
  `narrowNameForMarkupPreservesTrailingBlanks` asserts the narrowing never trims.
* **Files.**
  `services/reporting-service/src/main/java/com/carddemo/reporting/mapper/StatementTextMapper.java`.

#### D-STMT-HTML-ESCAPING — dynamic statement text is escaped for the markup artifact

* **Baseline behaviour.** [`CBSTM03A.CBL`](../../app/cbl/CBSTM03A.CBL) composes each cell of
  the markup statement by concatenating a declared literal, a customer or transaction item
  and a closing literal into the 100-character buffer declared at **L149** — the name cell
  at **L562-L568**, the three address cells at **L570-L592**, the three basic-detail cells at
  **L612-L633** and the three transaction cells at **L685-L718**. Every one of those items is
  moved in **exactly as stored**: there is no encoding step anywhere in the program, and no
  character is given any treatment on its way into the buffer. The items are fixed-width
  alphanumeric — `CUST-FIRST-NAME` is `PIC X(25)` at
  [`CVCUS01Y.cpy`](../../app/cpy/CVCUS01Y.cpy) **L6** and `TRAN-DESC` is `PIC X(100)` at
  [`CVTRA05Y.cpy`](../../app/cpy/CVTRA05Y.cpy) **L9** — and an `X` picture constrains a
  field's **width** and not its **alphabet**, so an angle bracket stored in any of them
  reaches the artifact as an angle bracket. The output is written to a fixed-length
  sequential data set — **L94** of [`CREASTMT.JCL`](../../app/jcl/CREASTMT.JCL) fixes the
  record length at 100 bytes — and read on the mainframe by a utility, so the absence of
  encoding has no consequence there.
* **Target behaviour.** `StatementHtmlMapper` escapes the five markup-significant characters
  — `&`, `<`, `>`, `"` and `'`, as `&amp;`, `&lt;`, `&gt;`, `&quot;` and `&#39;` — in every
  dynamic value before it enters a cell, and in no declared fragment. The escape is applied
  **after** the `DELIMITED BY '  '` cut and not before it, so the cut still finds the blank
  pair at the offset the baseline finds it at. The mapper additionally **refuses** a value
  carrying a control character or a character outside US-ASCII, and refuses a cell whose
  escaped content would overrun the declared record length, rather than truncating it. The
  account-number heading is guarded by a digit-and-blank domain check instead of by escaping,
  because its assembled length is asserted exactly and any expansion would fail that
  assertion. **For the entire seed corpus the escaping is a no-op and the bytes are
  unchanged**: every value the seed data can produce is drawn from upper-case letters,
  digits, blank, full stop and hyphen, none of which is escaped, so the golden statement is
  reproduced byte for byte and only a hostile value produces different bytes.
* **Category.** Documented divergence — a target-platform output encoding added, and a
  fail-closed refusal where the baseline had no decision to make.
* **Why the difference is accepted.** The target's delivery path is not the baseline's. The
  markup statement is served to a browser, which parses what it is given, so a stored name or
  address holding `<script>` is executed when a statement is opened — stored cross-site
  scripting, CWE-79 — and the reader of a statement is not its subject, so the exposure
  reaches other people's data. That hazard is a property of the **platform the artifact now
  lands on** and not of any business rule, which is why encoding it is not a change of
  behaviour under transformation rule **T9**: the characters the customer's data holds are
  still the characters the reader sees. Alternatives Considered: escaping at ingestion, in
  `StatementTextMapper`, or upstream in the projection, so one step served both artifacts.
  Rejected because the correct escape depends on the context the value lands in — an HTML
  text node here, a fixed-width text band in the plain-text artifact, a JSON string in the
  transfer objects — so a value escaped upstream would arrive at the plain-text band carrying
  character references that band must not contain. The encoding therefore belongs to the
  markup boundary alone. Alternatives Considered: emitting the values raw for exact parity on
  all input and answering the exposure with delivery controls alone. Rejected because a
  delivery control is set by whatever serves the stored artifact and can be lost by a
  configuration change far from this code, whereas the escape travels with the bytes; the
  delivery controls are kept **as well**, not instead. Alternatives Considered: accepting an
  unrenderable character and letting the US-ASCII encoder substitute for it, which is what
  `String.getBytes(StandardCharsets.US_ASCII)` does. Rejected because the substitution is
  silent: a cardholder whose name carried an accented letter would have received a statement
  showing a question mark in its place, correctly sized, passing every width assertion, with
  nothing anywhere recording the loss. Alternatives Considered: widening the encoding to one
  that can represent those characters, which is the obvious way to keep the value. Rejected
  because `CREASTMT.JCL` **L94** declares a fixed-width record of 100 **bytes**, and under a
  variable-width encoding a 100-character record is no longer a 100-byte one, so every offset
  in the artifact would move. Trade-offs: a refusal costs the statement where a truncation or
  a substitution would have produced one, and that direction is chosen because a missing
  statement is visible while a corrupted one is not — and because a truncation could sever a
  character reference and put a bare ampersand back into the document. Trade-offs: the range
  guard and the encoder look redundant and are not. The range guard rejects characters
  US-ASCII *can* encode but this artifact cannot carry — the C0 controls and delete — because
  a line terminator inside a fixed-length stream forges a record boundary and displaces every
  record after it, and a control character in a diagnostic lets stored data forge a line in an
  operational log; the encoder, configured to report rather than substitute, rejects the code
  points above `0x7F` that US-ASCII *cannot* encode. Removing either would leave one class
  unhandled.
* **A second, related change is registered here rather than separately.** The overlength
  diagnostic no longer reproduces the assembled content. The content of an overlong cell is
  customer, address, account or transaction text, and an exception message reaches a log, so
  the message was itself a route by which that data left the process in the clear — and it
  took that route on precisely the inputs most likely to be hostile, since no conforming
  record can reach the length at all. The message now reports the component name and the two
  lengths, which is what a maintainer needs in order to act, and nothing that identifies a
  customer.
* **What is NOT diverged.** The plain-text statement is untouched. `StatementTextMapper`
  performs no escaping and must not: that artifact has no markup grammar for a character to
  be significant in, and it is the side a byte comparison against the recorded golden output
  runs on, so escaping there would break parity on the one artifact that has an oracle in
  order to protect the one that does not. The two artifacts therefore no longer treat a
  markup-significant character identically — the plain-text file remains the complete record
  of what the customer's data holds, and the markup file is the safe rendering of it. The
  guarantee is structural rather than asserted: the escaping is private to
  `StatementHtmlMapper`, so no other emitter can reach it. Alternatives Considered:
  narrowing the accepted alphabet of the three free-text source items instead, so that no
  value could ever carry a markup-significant character. Rejected because it would refuse
  every *legitimate* value carrying an ampersand — a merchant name of "AT&T" among them —
  and would still be the wrong control, a character being safe or unsafe only relative to
  the artifact it lands in.
* **Where it is verified.** `StatementHtmlMapperTest`
  `conformingDataIsUnchangedByTheEscaping` and
  `conformingTransactionRowIsUnchangedByTheEscaping` assert byte parity for conforming data
  against literal expected records, which is the proof that the golden artifact is unmoved;
  `declaredFragmentsAreNotEscaped` asserts the document's own tags are untouched;
  `aScriptShapedNameIsRenderedAsText`, `aScriptElementInTheDescriptionIsRenderedAsText`,
  `aStoredEntityReferenceIsEscapedAgain`, `bothQuoteCharactersAreEscaped` and
  `everyMarkupSignificantCharacterIsReplaced` assert the escaping in both directions and
  across both assembly regimes, the ampersand ordering included;
  `noFragmentOpensAUrlOrScriptAttribute` asserts the artifact offers no attribute or URL
  context for a value to reach, which is what makes a text-context escape sufficient rather
  than merely necessary; `theBlankPairCutPrecedesTheEscaping` asserts the ordering of the cut
  against the escape; `aControlCharacterIsRefusedWithoutQuotingTheValue` and
  `aNonAsciiCharacterIsRefused` assert the two refusals;
  `theTightestCellFailsClosedWhenEscapingOverrunsTheRecord` asserts the eleven-character
  headroom boundary from both sides; `everyRecordOfEveryEmissionIsTheDeclaredLength` asserts
  the record geometry survives; `theOverlengthDiagnosticWithholdsTheContent` asserts the
  message carries the lengths and no part of the value; and
  `theAccountHeadingRefusesAnythingButDigitsAndBlanks` asserts the heading's domain guard.
* **Files.**
  `services/reporting-service/src/main/java/com/carddemo/reporting/mapper/StatementHtmlMapper.java`,
  `services/reporting-service/src/test/java/com/carddemo/reporting/mapper/StatementHtmlMapperTest.java`.


#### D-REPORT-CLOSING-TOTAL — the last card group is closed by an account-total band

* **Baseline behaviour.** [`CBTRN03C.cbl`](../../app/cbl/CBTRN03C.cbl) writes the
  account-total band from **one place only**: the card-number break at **L181**, whose
  first-time guard at **L182** performs `1120-WRITE-ACCOUNT-TOTALS` at **L183**. That
  break fires when the NEXT card arrives, so it closes every group except the last. The
  end-of-file branch at **L198-L203** performs the page totals at **L202** and the grand
  totals at **L203** and never the account-total paragraph, so the report ends with the
  final group's own total never written — the amounts accumulated into
  `WS-ACCOUNT-TOTAL` at **L288** for that group are reset by nothing and reported by
  nothing.
* **Target behaviour.** `TransactionReportMapper.encodeAccountTotal` is available
  unconditionally, so the caller closes the last group exactly as it closes every other,
  and the report carries **one more account-total band** than the baseline's for the same
  input.
* **Category.** Documented divergence — an omitted closing band supplied.
* **Why the difference is accepted.** The band the baseline omits is not a band it
  decided against; the omission follows from the break being driven by the arrival of the
  next key, which cannot happen at end of file. Every group's total is a value the report
  is for, and dropping the last one makes the report's own bands fail to sum to its grand
  total, which a reader checking the arithmetic would read as data loss. Alternatives
  Considered: suppressing the last band to keep a byte comparison clean. Rejected because
  it would reproduce an arithmetic inconsistency in order to preserve a diff, which
  inverts the purpose of the parity check — the check exists to find differences worth
  explaining, and this one is explained here. Alternatives Considered: emitting it only
  when the run holds more than one card group. Rejected as a second rule with no
  reference basis, which would make the band's presence depend on the input's shape.
* **Where it is verified.** `TransactionReportMapperTest`
  `theAccountTotalBandEncodesTheFinalCardGroupExactlyAsAnyOther` asserts the band encodes at
  its declared length for a final group, and
  `theAccountTotalEncoderExposesNoSuppressionParameter` together with
  `aCardBreakTotalIsReachableUnconditionally` assert the encoder's signature carries exactly
  one parameter — the amount — so no end-of-run flag exists by which a caller could
  reintroduce the suppression. A byte comparison against a captured baseline artifact then
  shows exactly one additional 112-byte band, at the end, whose amount equals the final
  group's detail lines; the difference is bounded to that band and appears nowhere earlier in
  the report. Assumptions: only the mapper half of this divergence is asserted today, and the
  reason is narrower than it once was. `TransactionReportService` and `StatementService` are
  both present, but each composes report VALUES rather than report bytes — the 133-column
  fixed-width assembly stays in `com.carddemo.reporting.mapper` by the boundary that package's
  charter draws — so no production caller yet invokes `encodeAccountTotal`, and every current
  invocation is a test one. Refactoring Rationale: this entry previously said the report and
  statement emitting service was "not yet authored", which was accurate when written and is now
  false of both services. Left standing it would have misdirected the obligation: a reader would
  have gone looking for a service to write, when what is actually owed is the byte-emitting
  sequence that drives the mapper, and the two services that would host it already exist. The
  obligation is still owed; only its location is now known.
* **Files.** `services/reporting-service/src/main/java/com/carddemo/reporting/mapper/TransactionReportMapper.java`.

#### D-REPORT-GRAND-TOTAL — the last transaction's amount is counted once, not twice

* **Baseline behaviour.** [`CBTRN03C.cbl`](../../app/cbl/CBTRN03C.cbl) adds each
  transaction's amount into both accumulators at **L287-L288** as it writes the detail
  line. Its end-of-file branch then adds an amount into the SAME two accumulators again
  at **L200-L201**, reading `TRAN-AMT` from the record area left over from the last
  successful read — the read having already been reported at **L287-L288** on the previous
  iteration. **L202** then performs `1110-WRITE-PAGE-TOTALS`, which carries the page total
  into the grand total at **L297**, so the final transaction's amount reaches both the last
  page total and the grand total **twice**. The second addition into the card-break
  accumulator at **L201** is never emitted at all, because the card-break band itself is
  never written for the final group — see `D-REPORT-CLOSING-TOTAL` above.
* **Target behaviour.** Each amount is accumulated **once**, so the grand total is exactly
  the sum of the page totals and the page totals are exactly the sums of their detail
  lines.
* **Category.** Documented divergence — a double count in the baseline is not reproduced.
* **Why the difference is accepted.** The second addition reads a record area whose
  contents are undefined after end of file is reached; that it holds the last record's
  amount is an artifact of the runtime rather than a documented behaviour, so reproducing
  it would mean reproducing an artifact, not a rule. The result is also self-evidently
  wrong on its own terms: a total that does not equal the sum of the lines above it makes
  every figure in the report unusable for reconciliation, which is the report's purpose.
  Alternatives Considered: adding the final amount a second time to match the baseline
  byte for byte. Rejected because it would publish a figure the report's own detail lines
  contradict. Alternatives Considered: fixing the baseline. Refused outright, `app/**`
  being reference-only — which is exactly why the difference is registered here instead.
* **Where it is verified.** `TransactionReportMapperTest`
  `theGrandTotalBandCarriesTheSumOfThePageTotalsAndNotTheBaselinesRepetition` and
  `theTotalEncodersHoldNoAccumulator` assert that no total encoder holds state of any kind —
  a repeat call with one value is byte-identical, and a second call renders its own value
  rather than a running sum — so a total renders exactly what it was handed. For any input
  the emitted grand total then equals the sum of the emitted page totals, and each page total
  equals the sum of its own detail amounts; a captured baseline artifact differs from the
  emitted report in the grand-total field and in the final page-total field by exactly the
  last transaction's amount. Assumptions: as with the entry above, the half that says the
  emitting sequence adds each row's amount once belongs to the emitting service that is not
  yet authored, and this entry records that the obligation is owed there.
* **Files.** `services/reporting-service/src/main/java/com/carddemo/reporting/mapper/TransactionReportMapper.java`.

#### D-AUTH-FRAUD-TARGET-STATE — the fraud write names the state to end in, not a toggle

* **Baseline behaviour.**
  [`COPAUS1C.cbl`](../../app/app-authorization-ims-db2-mq/cbl/COPAUS1C.cbl) `MARK-AUTH-FRAUD`
  at **L230-L266** takes **no** action argument. It re-reads the detail segment at **L234**
  and then inverts whatever it found: `IF PA-FRAUD-CONFIRMED` at **L236** sets
  `PA-FRAUD-REMOVED` at **L237**, and the `ELSE` at **L239** sets `PA-FRAUD-CONFIRMED` at
  **L240**. The two condition names are declared on
  [`CIPAUDTY.cpy`](../../app/app-authorization-ims-db2-mq/cpy/CIPAUDTY.cpy) **L50-L52** as
  `PA-AUTH-FRAUD PIC X(01)` with `88 PA-FRAUD-CONFIRMED VALUE 'F'` and
  `88 PA-FRAUD-REMOVED VALUE 'R'`, so an untagged row — the column is neither `'F'` nor
  `'R'` — takes the `ELSE` and becomes `'F'`. The terminal reaches this paragraph through
  one PF key, so the resulting state is a function of the state already stored and of
  nothing the operator supplied. Pressing the key twice returns the row to where it began.
* **Target behaviour.** `PUT /api/v1/authorizations/{key}/fraud` carries
  `FraudMarkRequest.action`, whose closed domain is the same `'F'`/`'R'` pair, and the
  write sets the column to **the state the body names**. Two identical requests therefore
  leave the row in the state the first one produced; they do not return it to its original
  state.
* **Category.** Documented divergence — the reference toggle is replaced by an idempotent
  target-state write.
* **Why the difference is accepted.** The transport, not the rule, forces the choice.
  Assumptions: a `PUT` is retried by intermediaries, by client libraries and by operators
  who did not see a response, and none of those retries carries the operator's intent —
  only the fact that the first attempt's outcome is unknown. Under toggle semantics a
  retry **reverses** the operation the operator asked for, and the reversal is
  indistinguishable from success: the response reports a completed write either way, and
  the row ends up untagged when the operator asked for it to be tagged. That is a
  correctness failure in the direction that matters, because a fraud tag silently removed
  by a retry is exactly the state the tag exists to prevent. Alternatives Considered:
  reproducing the toggle by ignoring the body and inverting the stored column, which is
  byte-for-byte the reference rule. Rejected because the operation would then not be
  idempotent, and no status code can tell a caller whether its retry set or cleared the
  tag. Alternatives Considered: keeping the toggle but making the operation a `POST` on a
  non-idempotent sub-resource, so the method no longer promises what the semantics cannot
  deliver. Rejected because the promise is the point — the reference terminal had one
  operator watching one screen and could rely on the operator seeing the outcome, whereas
  this operation is reached over a network by clients that retry, so an unsafe-by-design
  write would move the defect from the contract into every caller. Trade-offs: an operator
  driving this endpoint cannot invert the stored state without first reading it, which the
  detail operation supplies in the same round trip the terminal needed to reach its own
  screen. Nothing observable is lost: the two reachable end states are the two the
  reference program can produce, the persisted characters are the reference characters, and
  the insert-versus-update distinction the reference write reports is preserved on the
  response.
* **Where it is verified.** `authorization-api.yaml` declares the member as a state rather
  than a command and cites this entry at the operation, at the request body and at the
  schema; the schema's closed `'F'`/`'R'` enum is asserted by the authorization contract
  test, which also asserts that no third character is admitted. `FraudMarkingServiceTest`
  asserts the write reaches the state the body names on both the insert path and the
  replace path, and `FraudControllerTest` asserts the operation answers **201** on the
  first request and **200** on the second, which is the insert-versus-update distinction
  the reference write reports.
* **Files.**
  `services/authorization-service/src/main/resources/openapi/authorization-api.yaml`,
  `services/authorization-service/src/main/java/com/carddemo/authorization/service/FraudMarkingService.java`,
  `services/authorization-service/src/main/java/com/carddemo/authorization/api/FraudController.java`.

#### D-AUTH-FRAUD-ONE-CLOCK — the two fraud report dates come from one clock, not two

* **Baseline behaviour.** One operator action writes the fraud report date **twice, from two
  independent clocks**.
  [`COPAUS2C.cbl`](../../app/app-authorization-ims-db2-mq/cbl/COPAUS2C.cbl) opens with
  `EXEC CICS ASKTIME ABSTIME(WS-ABS-TIME)` at **L91-L94** and
  `EXEC CICS FORMATTIME ABSTIME(WS-ABS-TIME) MMDDYY(WS-CUR-DATE) DATESEP` at **L95-L100**,
  then `MOVE WS-CUR-DATE TO PA-FRAUD-RPT-DATE` at **L101** — so the **segment** copy of the
  date, `PIC X(08)` at **L59**, is the transaction monitor's clock read on entry to the
  program. The **table** column is not set from that value: the insert names
  `FRAUD_RPT_DATE` in its column list at **L166** and supplies `CURRENT DATE` in its values
  list at **L194**, and the duplicate-key branch sets `FRAUD_RPT_DATE = CURRENT DATE` at
  **L225** — so the column is the *database server's* clock, evaluated when the statement
  executes. Neither program compares the two or reconciles them. They agree on almost every
  execution, and they can disagree in two ways: transiently, when the statement executes on
  the far side of midnight from the entry-time read, and permanently, when the region's
  local time and the database subsystem's local time are not the same time.
* **Target behaviour.** `FraudMarkingService` reads its injected clock **once** per request
  — `LocalDate today = LocalDate.now(this.clock)` — and derives both values from that one
  date: the segment-equivalent `PendingAuthDetail.fraudReportDate`, rendered `MM/dd/yy` to
  keep the reference's month-first eight characters, and the `auth_fraud.fraud_rpt_date`
  column, carried as a date. The two therefore always name the same day, and that day is
  the date of the **request** rather than the date of whichever write executed second.
* **Category.** Documented divergence — two clock reads collapse into one.
* **Why the difference is accepted.** Assumptions: the baseline's two reads are an artifact
  of the two stores, not a rule either program states. The segment write had to happen in
  application code because the monitor is the only clock a segment replace can reach, and
  the column write used the database's own function because that is the idiomatic way to
  date a row — so the operation ended up with two clocks because it spanned two stores, and
  the distributed commit that held them together is itself eliminated (see
  [D-6](#d-6--the-distributed-commit-is-eliminated-not-emulated)). Once both values live in
  one PostgreSQL schema written by one local transaction, reading two clocks would be a
  choice rather than a constraint, and the only behaviour it could add is disagreement.
  Alternatives Considered: reproducing the split by taking the column's date from the
  database with `CURRENT_DATE` and the segment's from the service clock, which is
  byte-for-byte the reference arrangement. Rejected because it reintroduces a midnight race
  into a single transaction for no gain, and because it would make the emitted value
  untestable — the assertion would have to accept either of two days, which is an assertion
  that cannot fail. Alternatives Considered: taking both from the database clock. Rejected
  because every other timestamp this service emits comes from the injected clock, and a
  service reading two clocks for two purposes is the arrangement this entry exists to
  remove. Trade-offs: the date is now the application's date rather than the store's, so a
  service whose clock has drifted from the database's dates rows by its own drift. Accepted
  because the clock is injected and therefore assertable, because container clocks are
  synchronised by the platform rather than by the application, and because the alternative
  trades that bounded risk for a guaranteed inconsistency between two columns describing one
  event.
* **Where it is verified.** `FraudMarkingServiceTest.bothReportDatesOneOperationWritesAreTheSameDay`
  pins the clock, performs one mark, and asserts that the day carried by the segment's
  month-first characters and the day carried by the row's date column are the **same day**
  and are the pinned day. The two renderings are pinned separately by
  `theAuthorizationRowIsMarkedWithTheSegmentDate` and by
  `secondMarkReplacesTheExistingRow`, so a change to either rendering fails on its own test
  rather than on this one.
* **Files.**
  `services/authorization-service/src/main/java/com/carddemo/authorization/service/FraudMarkingService.java`.

#### D-REFERENCE-INTEGRITY-SENTENCE — one integrity branch answers three integrity conditions

* **Baseline behaviour.** The reference programs separate exactly one integrity
  condition from the rest and answer the remainder generically. A restricted delete is
  distinguished on its own database code and answered with an instruction to the user —
  [`COTRTLIC.cbl`](../../app/app-transaction-type-db2/cbl/COTRTLIC.cbl) branches at
  **L1914**, sets its delete-requested state at **L1915**, moves
  `'Please delete associated child records first:'` at **L1919** and leaves through the
  normal exit at **L1925**, and
  [`COTRTUPC.cbl`](../../app/app-transaction-type-db2/cbl/COTRTUPC.cbl) takes the same
  branch at **L1638** with the same literal at **L1641**. Every other integrity failure
  falls to a catch-all: the insert paragraph at `COTRTUPC.cbl` **L1596** has only a zero
  arm at **L1605** and a `WHEN OTHER` at **L1607** that sets `TABLE-UPDATE-FAILED`
  (`'Update of record failed'`) and strings the SQLCODE and `SQLERRM` into the message,
  and the batch maintenance program
  [`COBTUPDT.cbl`](../../app/app-transaction-type-db2/cbl/COBTUPDT.cbl) does the same at
  its insert **L137** with a zero arm at **L152** and a negative arm at **L154**. A
  duplicate key on create and an absent parent on create therefore have **no
  distinguished outcome and no sentence of their own** anywhere in the baseline.
* **Target behaviour.** The reference contract answers a duplicate key on create, an
  absent parent on create and a restricted delete all with HTTP 409 carrying the
  restricted-delete sentence. All three arrive as one provider exception family —
  PostgreSQL raises SQLSTATE 23505 for the first and 23503 for the other two, and Spring
  surfaces every one of them as `DataIntegrityViolationException` — which
  `GlobalExceptionHandler` maps in a single branch to `MESSAGE_REFERENCED_ROW`.
  reference-service declares no advice of its own, so this is the only 409 body its
  create paths can return.
* **Category.** Documented divergence — a condition the baseline reports as a failed
  write is reported as a contention, and three conditions share one sentence.
* **Why the difference is accepted.** Alternatives Considered: composing a fourth
  sentence for the duplicate-key condition and selecting it from a
  `DuplicateKeyException` test placed ahead of the generic integrity test. Rejected on
  the text, not on the mechanism: the baseline declares no entity-agnostic
  already-exists literal to carry across. The three it does declare are
  `'Tran ID already exist...'` at [`COTRN02C.cbl`](../../app/cbl/COTRN02C.cbl) **L738**,
  the same string at [`COBIL00C.cbl`](../../app/cbl/COBIL00C.cbl) **L536**, and
  `'User ID already exist...'` at [`COUSR01C.cbl`](../../app/cbl/COUSR01C.cbl) **L263**,
  each naming its own entity — so a shared, entity-agnostic advice could only invent one,
  and transformation rule T8 makes every user-visible string baseline text rather than
  authored text. Alternatives Considered: reporting a duplicate key as HTTP 500 with the
  abend block, which is the closest literal reading of the baseline, since
  `'Update of record failed'` is published for exactly the case where a write failed for
  neither reason a client can act on. Rejected because a duplicate key IS a
  client-correctable condition — the caller chooses another code and succeeds — and
  answering it as a server fault would tell every client to retry unchanged and give up.
  Trade-offs: what is given up is that a caller reading only the sentence cannot tell a
  duplicate key from a restricted delete; what it buys is that the sentence is baseline
  text in all three cases and that the status is the one a client can act on. The
  compensating control is the request itself: the three conditions arise on different
  operations and, on the one operation where two of them can both arise, the addressed
  key is in the path, so a caller that reads its own request knows which it hit.
* **Where it is verified.** `GlobalExceptionHandlerTest` asserts that the referential
  branch emits the baseline literal character for character, both as a constant and as
  the message of the composed 409 body, so the sentence these three conditions share
  cannot drift. The contract states the sharing on each create operation and on the
  shared `Conflict` response.
* **Files.**
  `services/common-lib/src/main/java/com/carddemo/common/error/GlobalExceptionHandler.java`,
  `services/reference-service/src/main/resources/openapi/reference-api.yaml`.

#### D-REFERENCE-ACTION-DOMAIN — an unrecognised maintenance action refuses the request, not the record

* **Baseline behaviour.**
  [`COBTUPDT.cbl`](../../app/app-transaction-type-db2/cbl/COBTUPDT.cbl) reads a stream of action
  records and dispatches on a single record-type character at **L110** to **L129**. It recognises
  `'A'` at **L111**, `'U'` at **L114** and `'D'` at **L117**, skips a `'*'` comment line at **L120**,
  and for anything else takes the `WHEN OTHER` arm at **L122**, moves `'ERROR: TYPE NOT VALID'` at
  **L124** and performs `9999-ABEND` at **L128**. That paragraph, at **L230**, displays the message,
  moves 4 to `RETURN-CODE` at **L232** and returns; the read loop at **L94** to **L96** then reads the
  NEXT record. One unrecognised record therefore costs one record: every other record in the stream is
  still applied.
* **Target behaviour.** The submitted action is a closed enumeration of `INSERT`, `UPDATE` and
  `DELETE`, so a request carrying any other value is refused as a whole with HTTP 400 and **no action
  is attempted**. The comment line has no counterpart at all: it is a property of a fixed-width input
  file and has no expression in a JSON array of actions.
* **Category.** Documented divergence — the granularity of the refusal changes, while every other
  per-action outcome is preserved exactly.
* **Why the difference is accepted.** Alternatives Considered: typing the action as an unrestricted
  bounded string and reporting an unrecognised value as a per-action `FAILED` outcome carrying the
  baseline's own sentence, which would reproduce the granularity precisely. Rejected because the value
  is a discriminator the request cannot be interpreted without: a JSON body is validated as a whole
  before a handler sees it, and admitting any string here would move a structural check out of the
  schema and into the service on the one member that decides which statement runs — which is exactly
  the class of defect that made the batch's description requirement unenforceable before it was
  expressed as a conditional. Alternatives Considered: keeping the enumeration and additionally
  reporting the position of the offending action in the 400's field-error array, which is what the
  shared validation path already does. This is what happens, and it is why the divergence costs a
  caller nothing it cannot act on: the refusal names the position, so the caller corrects that action
  and resubmits, whereas the baseline operator has to read a job log to find which record was skipped.
  Trade-offs: what is given up is that a stream with one bad action applies none of the others in a
  single request, so a caller with a large batch pays a round trip it would not have paid on the
  mainframe; what it buys is that the request is either interpretable or refused, with no partially
  interpretable middle state. The baseline's sentence is not lost — it is published verbatim as
  `maintenanceTypeNotValid` on the `ReferenceMessageCatalogue` schema.
* **Where it is verified.** `ReferenceApiContractTest` asserts that the action enumeration is closed to
  the three verbs and that the batch response declares the per-action outcome state and the aggregate
  condition code, so the one refusal that is coarser than the baseline's cannot silently become the
  treatment of the other three outcomes as well.
* **Files.**
  `services/reference-service/src/main/resources/openapi/reference-api.yaml`.

#### D-REPLY-PUT-LENGTH — the reply is sent at the sixty-three built, not the sixty-four transmitted

* **Baseline behaviour.** [`COPAUA0C.cbl`](../../app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl)
  holds one value for two purposes. `WS-RESP-LENGTH` is declared `PIC S9(4) VALUE 1` at
  **L46**, serves as the `WITH POINTER` cursor of the outbound `STRING` at **L730** — so
  after the transfer it addresses the position one **past** the last character written — and
  is then moved into the put's buffer length at **L756** and passed to the call at **L762**.
  The program therefore transmits **64 bytes for the 63 it built**, the sixty-fourth being a
  space from the 200-byte put buffer declared at **L108**.
* **Target behaviour.** `CsvAuthCodec` emits exactly 63 characters, including the trailing
  comma that **L727** pairs with the sixth value, and its decoder tolerates a payload
  arriving with the extra byte.
* **Category.** Documented divergence — transmitted length of the reply.
* **Why the difference is accepted.** The extra byte carries no information: it is
  uninitialised buffer, and reproducing it would mean publishing a length this codec would
  have to compute from a cursor it deliberately does not conflate with a length. Inside this
  class a payload length and a scan position are always separate, differently named values,
  precisely because holding them in one is what produced the extra byte. Tolerating it on
  decode is what keeps the codec able to read the reference program's own traffic, so the
  divergence is in what is emitted and not in what is accepted.
* **Where it is verified.** `replyCanonicalLengthIsSixtyThreeNotSixtyTwo`,
  `replyDelimiterPositionsIncludeTheTrailingComma` and
  `replyEncoderEmitsExactlySixtyThreeCanonicalBytes`.
* **Files.** `services/common-lib/src/main/java/com/carddemo/common/codec/CsvAuthCodec.java`.


#### D-REPORTING-ISOLATION — reporting reads at read-committed, not at the baseline's uncommitted

* **Baseline behaviour.** [`app/csd/CARDDEMO.CSD`](../../app/csd/CARDDEMO.CSD) declares
  `READINTEG(UNCOMMITTED)` on **every one of its eight file stanzas** — at **L3** for the
  first, `ACCTDAT`, and at **L15**, **L27**, **L40**, **L53**, **L66**, **L78** and **L90**
  for the rest — so a reading task was permitted to see a value a concurrent task had not yet
  committed, on any file it read.
* **Target behaviour.** The reporting context declares **no isolation override anywhere**: it
  reads at the engine's default read-committed, through a pool marked `read-only: true`, so a
  dirty read the baseline allowed cannot occur.
* **Category.** Documented divergence — transaction isolation is tightened.
* **Why the difference is accepted.** The tightening removes a permission, not a capability:
  no report in the baseline depends on seeing uncommitted work, and a report that did would be
  reporting numbers that may never exist. Reproducing uncommitted reads would require setting
  an isolation level explicitly in order to obtain weaker guarantees than the default, which
  is a choice that would have to be justified rather than inherited. The cost accepted is that
  a reader blocked behind an uncommitted write waits where the baseline would have read
  through it, which for a read-only reporting context is latency rather than incorrectness.
* **Where it is verified.** By the absence the divergence consists of: no
  `transaction-isolation` property and no `Isolation` reference occurs anywhere in
  `services/reporting-service/src`, and the read-only pool property is declared at
  `application.yml` L572 with its own rationale beside it.
* **Files.**
  `services/reporting-service/src/main/java/com/carddemo/reporting/config/DataSourceConfig.java`,
  `services/reporting-service/src/main/resources/application.yml`.


#### D-REPORTING-DATA-AT-REST — the store read is encrypted and backed up where the baseline's was neither

* **Baseline behaviour.** The same eight file stanzas of
  [`app/csd/CARDDEMO.CSD`](../../app/csd/CARDDEMO.CSD) declare `JOURNAL(NO)` and
  `RECOVERY(NONE)` — at **L94** and **L96** for the last stanza, `USRSEC`, and on all eight —
  so the accurate description of the baseline is that it kept **no forward recovery log and no
  data-change journalling**, not that it journalled nothing at all: `JNLSYNCWRITE(YES)` sits
  beside them at **L96**.
* **Target behaviour.** The store this context reads is encrypted at rest under a
  customer-managed key and has automated backups: `infra/modules/aurora-postgresql/main.tf`
  declares `storage_encrypted = true`, `kms_key_id = var.kms_key_arn` and
  `backup_retention_period`.
* **Category.** Documented divergence — data-at-rest protection is added.
* **Why the difference is accepted.** There is nothing to carry across: this context writes
  nothing, so neither setting has a target analogue, and the protection is a property of the
  managed store rather than a behaviour of this service. Declining it to match the baseline
  would mean choosing an unencrypted, unbacked-up store for card and customer data, which the
  plan forbids outright. The divergence is invisible to every observable output.
* **Where it is verified.** In shipped infrastructure rather than in a test: the three
  arguments above are declared in the Aurora module, and the reporting context declares no
  migration and no writing datasource that could contradict them.
* **Files.**
  `services/reporting-service/src/main/java/com/carddemo/reporting/config/DataSourceConfig.java`,
  `infra/modules/aurora-postgresql/main.tf`.


#### D-CATEGORY-CODE-TEXT — the transaction category code is carried as four characters, not as a number

* **Baseline behaviour.** [`CVTRA04Y.cpy`](../../app/cpy/CVTRA04Y.cpy) declares
  `TRAN-CAT-CD PIC 9(04)` at **L7** — a **numeric** picture — inside the record's key group.
  Every other declaration of the same field in the baseline is alphanumeric or character:
  [`DCLTRCAT.dcl`](../../app/app-transaction-type-db2/dcl/DCLTRCAT.dcl) declares
  `TRC_TYPE_CATEGORY CHAR(4) NOT NULL` at **L30** and generates its host variable as
  `PIC X(4)` at **L42-L43**, which is what the programs read and write through, and the seed
  extract [`app/data/ASCII/trancatg.txt`](../../app/data/ASCII/trancatg.txt) stores the key by
  positional concatenation — its first row begins `010001`, a two-character type followed by a
  four-character category.
* **Target behaviour.** The published contract's `TransactionCategoryCode` is a string of
  exactly four characters and `reference.transaction_categories.cat_cd` is `CHAR(4)`, so
  `'0001'` is carried and compared as four characters.
* **Category.** Documented divergence — the declared type of a key field, not its value.
* **Why the difference is accepted.** An integer would drop the leading zeros that every other
  declaration of this field depends on, turning `'0001'` into `1`, and the seed extract would
  then no longer locate its own rows because it holds the key positionally. The numeric picture
  is the outlier among the baseline's own declarations rather than the rule, and following it
  would break the three that agree with each other. No value changes: the four characters
  carried are the four the record holds.
* **Where it is verified.** By the seeded rows themselves — `V2__seed_reference.sql` loads the
  extract's zero-padded codes, and a code that lost its padding would not match the composite
  primary key the categories table declares — and by the contract's own length bounds, which
  admit exactly four characters and no shorter form.
* **Files.**
  `services/reference-service/src/main/resources/openapi/reference-api.yaml`,
  `services/reference-service/src/main/resources/db/migration/V1__reference.sql`,
  `services/reference-service/src/main/resources/db/migration/V2__seed_reference.sql`.


#### D-REFDATA-DESCRIPTION-TRIM — a reference description is published at its content length, not re-padded to fifty

* **Baseline behaviour.** The two reference descriptions are declared at a **fixed** fifty
  characters on the VSAM side and are stored blank-padded to that width:
  [`CVTRA03Y.cpy`](../../app/cpy/CVTRA03Y.cpy) declares `TRAN-TYPE-DESC PIC X(50)` at **L6** and
  [`CVTRA04Y.cpy`](../../app/cpy/CVTRA04Y.cpy) declares `TRAN-CAT-TYPE-DESC PIC X(50)` at **L8**.
  The seed extracts hold the padding: the first row of
  [`app/data/ASCII/trancatg.txt`](../../app/data/ASCII/trancatg.txt) carries
  `Regular Sales Draft` across bytes **7 to 56**, the remaining thirty-one bytes being blanks, in
  a row sixty bytes long. Outbound, the padding survives: the online type screens move the whole
  field into a fixed 3270 map field —
  [`COTRTUPC.cbl`](../../app/app-transaction-type-db2/cbl/COTRTUPC.cbl) at **L1200** and
  [`COTRTLIC.cbl`](../../app/app-transaction-type-db2/cbl/COTRTLIC.cbl) at **L1417** — and the
  extract job re-pads a variable-length column back to fixed width with
  `CAST(TRC_CAT_DATA AS CHAR(50))` at **L109** of
  [`TRANEXTR.jcl`](../../app/app-transaction-type-db2/jcl/TRANEXTR.jcl).
* **Target behaviour.** Both columns are `description VARCHAR(50) NOT NULL` —
  `V1__reference.sql` **L117** for types and **L209** for categories — and both mappers publish
  `TransactionTypeMapper.trimTrailing(...)`, so a response body carries `Regular Sales Draft` and
  not that value followed by thirty-one spaces. `TransactionTypeMapper` applies it to the type
  description and `TransactionCategoryMapper` to the category description; there is one rule and
  two consumers of it.
* **Category.** Documented divergence — the width at which a stored value is published, not the
  value itself.
* **Why the difference is accepted.** The trailing blanks are padding to a fixed record length
  rather than content, and the baseline's own Db2 declarations say so: `TR_DESCRIPTION` is
  `VARCHAR(50)` at **L3** of [`TRNTYPE.ddl`](../../app/app-transaction-type-db2/ddl/TRNTYPE.ddl)
  and `TRC_CAT_DATA` is `VARCHAR(50)` at **L4** of
  [`TRNTYCAT.ddl`](../../app/app-transaction-type-db2/ddl/TRNTYCAT.ddl), whose host structure is
  generated as a length-plus-text pair at **L47-L51** of
  [`DCLTRCAT.dcl`](../../app/app-transaction-type-db2/dcl/DCLTRCAT.dcl) — so where the baseline
  had a type capable of expressing content length it used it, and the fixed forms are an artifact
  of the record and of the terminal. The consumer here is a JSON body, which has no fixed field to
  fill, so padding would be data a caller has to strip before comparing or displaying. Where a
  fixed width IS still required the padding is reapplied at that boundary rather than carried
  through the domain: the statement and report artifacts frame their own columns.
* **What a consumer must know.** A client that compares a description against a fifty-character
  literal will not match. Comparison is against the trimmed content.
* **Where it is verified.** By
  `services/reference-service/src/test/java/com/carddemo/reference/mapper/ReferenceDescriptionTrimTest.java`,
  which drives both mappers with a description padded to the full fifty — the form the store actually
  holds, asserted independently by `ReferenceFixtureContractTest`, which pins the fixture to
  `"Regular Sales Draft" + " ".repeat(31)` — and requires the published value to carry the content
  alone. A third case holds the rule to TRAILING blanks, so a change that removed blanks outright
  would fail rather than pass on length. The published contract's own
  `ReferenceDescription` pattern does **not** verify this and cannot: it refuses an all-blank value
  and admits trailing blanks otherwise, so a padded response would satisfy it.
* **Files.**
  `services/reference-service/src/main/java/com/carddemo/reference/mapper/TransactionTypeMapper.java`,
  `services/reference-service/src/main/java/com/carddemo/reference/mapper/TransactionCategoryMapper.java`,
  `services/reference-service/src/main/resources/db/migration/V1__reference.sql`,
  `services/reference-service/src/test/java/com/carddemo/reference/mapper/ReferenceDescriptionTrimTest.java`.


#### D-APPLIED-GROUP-VISIBLE — a rate answer names the group that answered, not only the group asked for

* **Baseline behaviour.** On a disclosure-group miss,
  [`CBACT04C.cbl`](../../app/cbl/CBACT04C.cbl) **overwrites its own group-id field** with the
  literal `'DEFAULT'` at **L437** and re-reads at **L443-L444**, so after the second read the
  field no longer records which group was asked for and nothing downstream can tell a fallback
  rate from a specific one.
* **Target behaviour.** The rate response echoes the requested group unchanged **and** reports
  `appliedAcctGroupId`, the group that actually answered, as two separate members.
* **Category.** Documented divergence — information added to a response, with no change of
  selection.
* **Why the difference is accepted.** The rate chosen is identical: the fallback rule is
  reproduced exactly, and this entry concerns only what the caller is told about it. The
  baseline could afford to lose the distinction because its one consumer was a batch program
  computing interest and discarding the key; a published endpoint answers callers who reconcile
  a charge against a rate table, and inferring a substitution from a rate is not something a
  caller can do. Reporting both values makes the substitution visible without making it
  optional.
* **Where it is verified.** By the contract, which declares both members and documents the
  substitution on the applied one, and by the fallback fixture
  `disclosure_group/default_fallback/discgrp.txt`, which is built on the one pair that makes a
  fallback assertion non-vacuous.
* **Files.**
  `services/reference-service/src/main/resources/openapi/reference-api.yaml`,
  `services/reference-service/src/test/resources/fixtures/disclosure_group/default_fallback/discgrp.txt`.


#### D-REFDATA-BATCH-CAP — the maintenance-action batch is capped at five hundred actions

* **Baseline behaviour.**
  [`COBTUPDT.cbl`](../../app/app-transaction-type-db2/cbl/COBTUPDT.cbl) is **EOF-driven
  and uncapped**. Its loop at **L93-L96** reads until `AT END` sets `LASTREC` at
  **L102**, so a stream of any length is processed and no count is kept, compared or
  reported anywhere in the program.
* **Target behaviour.** `MaintenanceActionBatchRequest.actions` declares
  `minItems: 1` and `maxItems: 500`. A submission above the ceiling is refused with 400
  before any action is applied.
* **Category.** Documented divergence — a bound introduced by the transport.
* **Why the difference is accepted.** The baseline's input is a dataset the operator
  staged; the target's is a request body an authenticated caller sends, and an unbounded
  body is a resource-exhaustion surface the baseline simply does not have. The lower
  bound is there because an empty batch has no outcome to report. **Splitting a longer
  stream reaches the same end state**, and it does so precisely because the batch is not
  atomic — each action is applied independently, so successive requests compose. The one
  rule a caller must observe is ordering: an action that depends on another, such as a
  category beneath a type created in the same stream, must not be separated into an
  **earlier** request than the action it depends on, because the foreign key is checked
  as each action is applied.
* **Where it is verified.** The `actions` member's description in the published contract
  states the ceiling, names this entry and records the splitting rule.
* **Files.** `services/reference-service/src/main/resources/openapi/reference-api.yaml`.


#### D-SIGNON-CASE-SENSITIVE-PASSWORD — the credential is compared case-sensitively where the baseline folded it

* **Baseline behaviour.** [`COSGN00C.cbl`](../../app/cbl/COSGN00C.cbl) folds **both**
  submitted fields to upper case before it reads anything: **L132-L134** moves
  `FUNCTION UPPER-CASE(USERIDI OF COSGN0AI)` into `WS-USER-ID` and `CDEMO-USER-ID`, and
  **L135-L136** moves `FUNCTION UPPER-CASE(PASSWDI OF COSGN0AI)` into `WS-USER-PWD`. The
  keyed read at **L211-L219** is issued on the folded identifier and the comparison at
  **L223** is `SEC-USR-PWD = WS-USER-PWD`, so the stored credential is matched against the
  folded submission. The baseline is therefore case-**insensitive** in the credential as
  well as in the identifier: `secret`, `SECRET` and `Secret` all authenticate one user.
* **Target behaviour.** The comparison is no longer performed by this migration at all. The
  identity provider performs it, and it is case-**sensitive** on the credential, so the
  three submissions above are three different credentials and at most one of them
  authenticates. The identifier keeps its fold, because that fold is a key normalisation
  rather than a credential weakening and the stored subject is looked up by it.
* **Category.** Documented divergence — a credential comparison strengthened by the change
  of the component that performs it.
* **Why the difference is accepted.** Restoring the old behaviour is possible and is
  refused. Folding a credential before comparison collapses the alphabet it is drawn from:
  for a credential of *n* letters it divides the space by 2ⁿ, so an eight-character
  all-letter credential loses 256-fold. The baseline's own field is
  `SEC-USR-PWD PIC X(08)` held in the clear, so this migration was already declining to
  carry that record forward for a stronger reason ([D-4](#d-4--the-plaintext-credential-field-is-not-carried-forward));
  re-introducing the fold would preserve a *consequence* of a design being abandoned. AAP
  Rule T9 admits the change as a documented divergence, which is what this entry is.
* **What is NOT diverged.** The identifier is still folded, and it is still eight
  characters wide, so an operator who typed a lower-case identifier at the terminal types
  one here and reaches the same row. Every one of the sign-on messages is still the
  baseline literal.
* **Where it is claimed.** `services/auth-service/src/main/resources/openapi/auth-api.yaml`
  states it in the sign-on operation's description and cites this identifier.

#### D-SIGNON-EXISTENCE-UNIFORM — one credential refusal answers both of the baseline's two

* **Baseline behaviour.** [`COSGN00C.cbl`](../../app/cbl/COSGN00C.cbl) distinguishes the two
  credential failure modes by response code. The `EVALUATE WS-RESP-CD` at **L221** takes
  `WHEN 0` and, on a comparison failure at **L223**, writes
  `'Wrong Password. Try again ...'` across **L242-L243**; it takes `WHEN 13` at **L247** for
  a record that does not exist and writes `'User not found. Try again ...'` at **L249**. An
  unauthenticated caller can therefore learn whether a user identifier exists by reading
  which of the two sentences comes back.
* **Target behaviour.** Both cases return one HTTP 401 carrying
  `Wrong Password. Try again ...`. The app client fixes
  `PreventUserExistenceErrors = "ENABLED"` at **L609** of
  [`infra/modules/cognito/main.tf`](../../infra/modules/cognito/main.tf), so the provider
  answers the two cases identically and the service has nothing to tell them apart by. The
  second literal remains in the message catalogue for traceability and is **not reachable
  through any published operation**. An unrelated provider failure keeps the third literal,
  `Unable to verify the User ...` (**L254**).
* **Category.** Documented divergence — a diagnostic withdrawn because it was an
  enumeration channel.
* **Why the difference is accepted.** The distinction is exactly a user-enumeration oracle,
  and it is reachable without any credential because sign-on is the one operation this
  migration publishes unauthenticated. What is bought is that the enumeration stops; what is
  given up is a diagnostic the baseline showed an operator at a terminal inside the
  enterprise, which is a materially different audience from an unauthenticated caller at an
  internet edge.
* **The alternative that was rejected, and why the register says so twice.** Exposing the
  provider setting as a module input, so a root could select the baseline's distinguishable
  responses, was considered and refused at **L603-L608** of the same Terraform file: a
  reachable legacy value ports the defect, putting the enumeration channel one line of
  `tfvars` away. That refusal has a consequence this entry exists partly to correct. The
  published contract used to describe the distinguishable behaviour as available "under an
  explicitly selected legacy provider posture", which was **unreachable** — no such input
  exists and the module declines to add one — so the contract advertised a posture no
  operator could select. Those sentences are withdrawn. A posture that cannot be selected is
  not a documented option; it is a false statement about the system.
* **What is NOT diverged.** Field-level presence validation still refuses a blank identifier
  or a blank credential with 400 and the baseline's own two literals, and the identifier is
  still the first of the two checked, so the pre-credential behaviour is unchanged. Nothing
  about this entry weakens a credential: the two cases are merged, not the check.
* **Where it is claimed.** `services/auth-service/src/main/resources/openapi/auth-api.yaml`,
  `infra/modules/cognito/main.tf`, `infra/modules/cognito/README.md` and
  `docs/architecture/context-and-container-diagrams.md`, each citing this identifier.

#### D-USER-ID-CANONICAL-DOMAIN — the logon identifier is held to a canonical form the baseline never checked

* **Baseline behaviour.** `SEC-USR-ID` is `PIC X(08)` at **L18** of
  [`app/cpy/CSUSR01Y.cpy`](../../app/cpy/CSUSR01Y.cpy), so every character the terminal can
  send is storable, and no program validates which characters arrive.
  [`COUSR01C.cbl`](../../app/cbl/COUSR01C.cbl) tests the field at **L134** for absence only,
  and [`COUSR02C.cbl`](../../app/cbl/COUSR02C.cbl) does the same at **L184**; the mapset field
  is `ATTRB=(FSET,NORM,UNPROT)` at **L111** of
  [`app/bms/COUSR01.bms`](../../app/bms/COUSR01.bms) — unprotected and alphanumeric, with no
  `NUM` attribute to restrict it. So a leading blank, an interior blank, a lower-case letter or
  a control character would all be accepted and stored as typed.
* **Target behaviour.** The service derives ONE canonical key —
  `UserService#canonicalKey`, which trims, folds under the root locale, and then refuses the
  result when it is empty, when it is wider than eight characters, or when it draws on a
  character outside the 94 printable invariant code points `0x21`–`0x7E`. The refusal names
  the `userId` field and precedes every side effect. The same two conditions are enforced by
  the database, as `ck_users_user_id_canonical` in
  `services/auth-service/src/main/resources/db/migration/V6__auth_canonical_user_id.sql`, so
  they hold for a writer that does not go through the service.
* **Category.** Documented divergence — input validation narrowed so that one identifier has
  exactly one canonical spelling.
* **Why the width check is not merely defensive.** Java's upper-case mapping is **not
  length-preserving**: the sharp s folds to two characters, so a submission of five such
  characters — admitted by the request record's own eight-character bound — canonicalises to
  ten. Before this entry nothing checked the canonical width, so the expanded key was probed
  for, handed to the identity provider, and refused only afterwards by the `CHAR(8)` column;
  the compensation that unwound the provider account then reported the integrity failure as a
  duplicate-key **409**. A caller was told an identifier already existed when no such row had
  ever been written, and a provider account had been created and withdrawn in the meantime.
  The four-sharp-character case, which canonicalises to exactly eight, is admitted — the check
  is a width bound and not a rejection of expansion.
* **Why the character domain is narrowed at all.** Two independent reasons, and neither is
  tidiness. First, the fold has to mean the same thing in two places: the service folds under
  the root locale while the column's guard folds through the engine's `upper()`, which is
  collation-dependent, and the two are only guaranteed to agree inside the invariant set.
  Measured on the engine, `'ÄBC'` satisfies `user_id = upper(btrim(user_id))` and is refused
  only by the domain term — so without that term the service's definition of the key and the
  column's could disagree on the same input, which is precisely the single-definition property
  the guard exists to provide. Second, the **space** is excluded because the reference itself
  cannot carry an identifier containing one: `COUSR01C.cbl` **L256**,
  [`COUSR02C.cbl`](../../app/cbl/COUSR02C.cbl) **L373** and
  [`COUSR03C.cbl`](../../app/cbl/COUSR03C.cbl) **L319** each render it with
  `STRING ... DELIMITED BY SPACE`, so the confirmation a user reads names the identifier
  truncated at its first blank — a different identifier from the one stored.
* **What the predecessor guard got wrong, which this entry also corrects.**
  `V4__auth_folded_user_id.sql` guarded the key with `CHECK (user_id = upper(user_id))` and its
  `COMMENT` described the stored key as "upper-case and blank-trimmed". Only the first half was
  enforced. Measured on the engine, `' ABC'` and `'  ABC'` both satisfy that predicate; the
  service resolves a request naming `' ABC'` to `ABC`, so a bypass writer could store a row
  addressable by no request, and `ABC` could then be inserted beside it as a second row for one
  logical identity with its own identity-pool account. V4's own comment recorded the omission
  and argued for it on two grounds — that a leading blank is "a different property from the
  fold" and that the identifier's shape "is already asserted at the adapter where it arrives" —
  and both are withdrawn in V5: the service's derivation is one expression, trim-then-fold, and
  an assertion at the adapter is exactly what a bypass writer does not pass through, which is
  the reason V4 gave for adding a database guard in the first place.
* **Why the domain is not narrowed further.** Restricting to the letters and digits the
  committed extract uses was considered and rejected. Every one of the ten identifiers in
  [`app/data/EBCDIC/AWS.M2.CARDDEMO.USRSEC.PS`](../../app/data/EBCDIC/AWS.M2.CARDDEMO.USRSEC.PS)
  draws on `[A-Z0-9]` alone, so that domain would fit the parity oracle exactly — and it would
  refuse punctuation the reference terminal can send and on which the two fold definitions
  already agree, which is a behavioural loss this correction has no reason to take.
* **What this costs, stated plainly.** A create or a keyed lookup naming an identifier outside
  the canonical domain now answers **400** with a `userId` field error where the baseline would
  have stored the row, and where the target previously answered 404 for a lookup. The values
  affected are exactly those for which "the row this key names" has no single answer. Nothing
  in the committed extract is affected, so the golden-master comparison is unchanged.
* **Why the constraint is `NOT VALID`.** A database on which the defective create path ran may
  hold an unfolded row and one on which a bypass writer ran may hold a blank-prefixed row; a
  validating `ADD` scans the table, fails on either, and a failed migration stops the service
  from starting — replacing a data defect a query can find with an outage. `NOT VALID` skips
  only the initial scan, so the invariant holds from the migration forward. The detection,
  remediation and `VALIDATE` steps are in the migration's own header, and its collision query
  supersedes V4's, which joined on `upper(u.user_id)` and therefore looked for a partner of
  `' ABC'` under `' ABC'` rather than under `'ABC'` — missing every whitespace drift.
* **Where it is verified.** `UserServiceTest` asserts the expansion refusal for four submitted
  forms and the two stores having NO interaction, which is the substance of the correction — a
  refusal at the column existed before, a refusal before the provider call did not — plus the
  at-the-bound admission that an off-by-one width check would fail, and the domain refusal in
  four shapes. `UserRepositoryIT` asserts the engine's own refusals through a native insert
  that bypasses the service: the leading blank, the unfolded key, the interior blank, the
  control character and the non-invariant letter, beside the admitted trailing-padded and
  punctuated keys, and it asserts from the catalogue that exactly one guard names the
  identifier and that the superseded one is retired.
* **Files.**
  `services/auth-service/src/main/java/com/carddemo/auth/service/UserService.java`,
  `services/auth-service/src/main/resources/db/migration/V6__auth_canonical_user_id.sql`.

#### D-REFDATA-ACTION-TOKEN — an unrecognised action token is refused, not soft-rejected

* **Baseline behaviour.**
  [`COBTUPDT.cbl`](../../app/app-transaction-type-db2/cbl/COBTUPDT.cbl) evaluates the
  one-character action at **L110-L129**, accepting `'A'`, `'U'` and `'D'`, **ignoring**
  `'*'` as a commented line at **L120-L121**, and for anything else composing
  `'ERROR: TYPE NOT VALID'` at **L124** and performing `9999-ABEND`. That paragraph, at
  **L230-L233**, displays the message and moves 4 to `RETURN-CODE` — it does **not** stop
  the run, so the loop reads the next record and every other record is still applied.
* **Target behaviour.** `MaintenanceActionType` is an enumeration of `INSERT`, `UPDATE`
  and `DELETE`, so an unrecognised token is refused with 400 naming the offending
  position, and no action of the submission is applied. There is no `COMMENT` value.
* **Category.** Documented divergence — request validation replaces a per-record
  outcome.
* **Why the difference is accepted.** The baseline reads a fixed-width dataset with no
  schema, so an unusable action byte can only be discovered per record; a JSON request
  is schema-validated before any handler runs, and transformation rule **T7** places
  field-level rejection in the response body at 400. Reporting it as a per-record
  outcome instead would require admitting an arbitrary string into the action member,
  which would weaken the one part of this request a caller can be told about before it
  is applied. The `'*'` convention has no analogue for the same reason a comment has no
  analogue in an array: a caller composing actions omits the ones it does not want.
  **Every other soft reject is preserved** — an unmatched key, a duplicate key, a
  category naming an absent type and a refused delete are each reported as one outcome
  with `applied` false and the baseline's own wording, and the submission's
  `completionTier` reports `WARN`, which is the migrated form of that return code of 4.
* **Where it is verified.** The operation's own note in the published contract records
  the divergence and cites the baseline lines; the `completionTier` member documents the
  two tiers the baseline reaches.
* **Files.** `services/reference-service/src/main/resources/openapi/reference-api.yaml`.

#### D-CARD-SELECTOR — a card is addressed by an opaque selector, and the list no longer narrows by card number

* **Baseline behaviour.** The card list screen carries two filter fields, an account
  number `ACCTSIDI PIC X(11)` at
  [`app/cpy-bms/COCRDLI.CPY`](../../app/cpy-bms/COCRDLI.CPY) **L66** and a card number
  `CARDSIDI PIC X(16)` at **L72**. [`COCRDLIC.cbl`](../../app/cbl/COCRDLIC.cbl) applies
  both from inside its read loop, calling `9500-FILTER-RECORDS` at **L1159** with the
  paragraph itself at **L1382**, so entering a card number **narrows the displayed list**
  to that card. The detail and update transactions are then reached with that same number
  in the record identifier the browse positions on, `WS-CARD-RID-CARDNUM` at
  **L137-L141**.
* **Target behaviour.** The published contract declares **no** card-number query
  parameter, and its single-card paths are keyed by an opaque selector —
  `/api/v1/cards/{cardKey}`, `/api/v1/admin/cards/{cardKey}` — which the service mints
  and every card response publishes as its `key` member. A card number a user types is
  carried in the **request body** of `POST /api/v1/cards/lookup`, which answers with that
  card. The browser screen therefore *opens* the card the entered number names instead of
  narrowing the browse to it, and its per-row controls route with the row's selector.
* **Category.** Documented divergence — a filter becomes a resolution, and a primary key
  becomes an opaque selector.
* **Why the difference is accepted.** The exposure is a property of HTTP rather than of
  this application, and the baseline had no equivalent: an operator's typed number reached
  the region in the terminal's own data stream, which travels between terminal and region
  and is written nowhere else — no request line, no browser history, no referrer header, no
  intermediary access log. In the target, a card number placed in a URI path or query
  string is persisted verbatim by the load balancer's access log, which
  [`infra/modules/alb/main.tf`](../../infra/modules/alb/main.tf) enables with no input
  able to disable it. That record is composed and delivered by the load-balancing service
  itself, from the request line, before any application code runs, so no masking the
  workload performs can bound it — which is why the earlier reasoning that
  `CardNumberMasker` answered the exposure was withdrawn. Under that spelling every card
  view, card edit and number-narrowed browse wrote one durable copy of a primary account
  number into an object store. A body is the only part of a request neither that log nor
  the edge's own records carry, so the number travels there and nowhere else.
* **How the selector is built.** The path parameter is a deployment-keyed authenticated
  sealing of the card's own primary key, produced by
  `com.carddemo.common.security.SealedSelector` and published by every list row and every
  detail response as its `key` member. Alternatives Considered: a keyed one-way token of
  the kind `OpaqueIdentifier` produces, which is genuinely non-reversible and therefore a
  stronger property than a sealing has. Rejected because a one-way token cannot be resolved
  back to a row without a stored column carrying it, a unique index over that column, a
  migration to add both and a populate step in the extract-and-load path; the sealing gives
  reversibility to the holder of the deployment key and to nobody else, which is what
  addressing actually needs, at no schema cost. The residual difference is stated plainly:
  a party holding the deployment secret can recover the number from a selector, and a party
  holding only the selector cannot. Assumptions: an account identifier and a customer
  identifier continue to travel as themselves — they are not primary account numbers, they
  are not sensitive authentication data, and no disclosure rule masks them, so sealing them
  would add a lookup for every account route and remove nothing. This is also why the
  selector and the paging cursor are sealed by two different primitives and publish two
  different declared shapes: a selector addresses a row that does not move and belongs in a
  bookmarkable route, while a cursor names a POSITION in a page sequence and is deliberately
  short-lived.
* **What is preserved.** The operator's workflow: one field, a typed number, and the card
  it names. The narrowing outcome is not lost so much as short-circuited, because the card
  number is the unique primary key of `card.cards`, so narrowing by it could only ever
  yield one row — the target returns that row directly. The account filter, which is not
  cardholder data, is unchanged and is still a query parameter. Per-row addressing is
  **restored** relative to the previous revision and is closer to the baseline's own
  row-selection fields `CRDSEL1I` through `CRDSEL7I` than a screen with no row controls
  was.
* **What is given up, and it is user-visible.** A card route can no longer be constructed
  from a typed number alone — one lookup call precedes it, so a user arriving with a number
  pays one extra round trip. Refactoring Rationale: an earlier revision of this entry also
  recorded that a selector expires with a configured token lifetime and that a bookmarked
  card URL therefore stops resolving. That is not the delivered behaviour and the sentence
  is corrected rather than dropped, because the cost it named is real in a different form.
  `SealedSelector` carries no clock, no lifetime and no expiry — the published
  `CardSelector` schema states that the value is stable for one card and that a route built
  from it is bookmarkable — so what invalidates an outstanding bookmark is **rotating the
  deployment key**, not the passage of time. The distinction matters operationally: time
  expiry would break every bookmark continuously and unavoidably, whereas key rotation
  breaks them at a moment an operator chooses. The cost was cited as a reason for
  withdrawing this shape once before; it is accepted now because the alternative is a
  permanent record of cardholder data in object storage, which does not trade against an
  interaction.
* **Where it is verified.** `CardApiContractTest` asserts that no published path template
  and no declared query parameter can carry a card number, that the selector schema refuses
  a card number and a masked rendering, that the one operation accepting a number takes it
  in a body, and that both response shapes require the selector; `SealedSelectorTest`
  asserts the sealing is deterministic, confidential against its own decoding,
  authenticated, canonical and purpose-separated; `ui/src/routes/cards.test.ts` asserts that
  a card number is refused as a route segment; and `ui/src/screens/cardScreens.test.tsx`
  asserts that a row control routes with the row selector and that no browse request carries
  a `cardNumber` member.
* **Files.** `services/common-lib/src/main/java/com/carddemo/common/security/SealedSelector.java`,
  `services/card-service/src/main/resources/openapi/card-api.yaml`,
  `services/card-service/src/main/java/com/carddemo/card/dto/CardSummary.java`,
  `services/card-service/src/main/java/com/carddemo/card/dto/CardDetail.java`,
  `ui/src/routes/cards.ts`, `ui/src/api/cards.ts`, `ui/src/screens/cardList/index.tsx`,
  `ui/src/screens/cardDetail/index.tsx`, `ui/src/screens/cardUpdate/index.tsx`,
  `infra/modules/api-gateway-http/variables.tf`, `infra/modules/alb/main.tf`,
  `docs/adr/ADR-006-api-and-ui.md`.
* **Registered once.** Refactoring Rationale: this divergence was carried under two
  identifiers — this one and a separate `D-CARD-SEALED-SELECTOR` describing the sealing
  mechanism — and the two are now one entry. They were never two divergences: one described
  the addressing change and the other the primitive that implements it, they cited the same
  contract and the same two response shapes, and they disagreed with each other on two
  points a reader could not settle without opening the code (the lookup operation's
  identifier, and whether a selector expires). A divergence register whose entries overlap
  invites exactly that, so the mechanism is folded in above and the register names this
  change once.

#### D-COMBINE-NO-LOADBACK — the combine flow stages its output and does not copy it back

* **Baseline behaviour.** [`COMBTRAN.jcl`](../../app/jcl/COMBTRAN.jcl) runs two steps. The
  first sorts the current backup and system-transaction generations together into a new
  combined generation, ordering on the sixteen-byte transaction identifier at **L30**. The
  second, at **L41-L48**, is an `IDCAMS REPRO` that copies the combined dataset **back into
  the transaction master**, because the system-generated transactions the interest job wrote
  live in a separate dataset and the master does not hold them until that copy runs.
* **Target behaviour.** `CombineTransactionsJob` performs the first step only: it resolves
  both current input generations, stages the ledger ordered by transaction identifier as a
  new generation of `TRANSACT.COMBINED`, and applies the retention rule. There is no
  load-back step and no migrated counterpart to one.
* **Why the difference is accepted.** The copy exists to fold rows into the master that were
  written outside it, and in the migrated model they were never outside it: the interest
  accrual writes its generated interest transactions to `ledger.transactions` directly,
  through the same repository the posting job writes to, so the master already holds
  everything the combined extract is assembled from. Reproducing the step would therefore not
  be a no-op — it would read every row of the ledger and write each one back over itself,
  touching every row's `@Version` column and every index entry, which is a large amount of
  write amplification to reach the state the database is already in. Alternatives Considered:
  keeping a load-back for symmetry with the job stream, rejected because a step that changes
  no value while bumping every version is worse than absent — it would make optimistic
  concurrency conflicts appear in unrelated services for the duration of the batch window.
* **What is preserved.** The record LAYOUT of the combined generation — 350 bytes per record,
  every field on the offset [`CVTRA05Y.cpy`](../../app/cpy/CVTRA05Y.cpy) declares, the amount
  carrying its sign as an overpunch — and the order the reference's sort produced, ascending on
  the sixteen-byte transaction identifier; and the two `(0)` input resolutions, which remain and
  still fail the step by name when an input family holds no generation, so a missing upstream
  generation is reported exactly as the reference's allocation failure reported it.
* **What is NOT preserved, and where each is registered.** Refactoring Rationale: this bullet
  read "the combined generation itself, **byte for byte** in the record layout", which claimed
  more than the code delivers and more than the paragraph above it argues. Two differences
  reach the SET the generation carries, and each has an entry of its own rather than being
  absorbed into a preservation claim: the generation's rows come from the RELATION and not from
  the two named objects ([`D-COMBINE-GENERATION-BYPASS`](#d-combine-generation-bypass--the-two-named-input-generations-are-a-precondition-and-not-a-source)),
  and the backup generation those inputs include is a superset of the reference's
  ([`D-COMBINE-BACKUP-SUPERSET`](#d-combine-backup-superset--the-backup-generation-already-holds-the-interest-rows)).
  A claim of byte-for-byte equality is checkable, so stating one that is false is worse than
  stating none: it tells a reader comparing this generation against a reference extract that any
  difference they find is their own error.
* **A third difference stood in that list and no longer does.** Refactoring Rationale: the
  description pad behind an accrual-written row was recorded here as blanks where the reference
  leaves low values, and registered as `D-COMBINE-DESC-PAD`. It is not a difference any more, so
  it is neither listed above nor left in the register: `CombineTransactionsJob` names the
  PRODUCER of each row from the attribution the reference's own accrual pass writes -- the
  `System` source at [`CBACT04C.cbl`](../../app/cbl/CBACT04C.cbl) **L484** and the
  `Int. for a/c ` description prefix at **L485-L489** -- and `TransactionRecordMapper` pads the
  description tail with the byte that named layout's producer leaves, so a posted row of this
  artefact carries blanks and an accrual row carries the reference's seventy-six low values. The
  withdrawal is recorded with the others in the preamble to
  [§7.4](#74-divergences-claimed-by-shipped-code), and what survives of the mechanism -- a
  producer recovered from the record rather than from the dataset it arrived in -- is registered
  as [`D-TRAN-PAD-PROVENANCE`](#d-tran-pad-provenance--a-re-emitted-records-padding-is-recovered-from-its-own-attribution).
* **Where it is verified.** `GenerationStagingJobsTest` asserts that the job resolves both
  current inputs before allocating, that it stages under the coordinate it allocated, and
  that a missing input fails the step by name with nothing allocated or staged;
  `BatchJobRosterTest` asserts the job is reachable under its advertised token.
  `CombineTransactionsJobTest` drives the job against a real PostgreSQL engine and is what
  carries the two claims above that the other two cannot reach — that the staged generation
  matches the reference's sort, and that no load-back occurs:
  * *the order* — `orderTheRecordsByteWiseAscendingByIdentifier` compares the emitted sequence
    against an expectation computed from the raw bytes, which is the contract
    [`COMBTRAN.jcl`](../../app/jcl/COMBTRAN.jcl) **L28** states as `TRAN-ID,1,16,CH`;
    `proveTheSeededIdentifiersDiscriminateByteOrderFromLinguisticOrder` keeps that case from
    holding vacuously by showing the same rows order differently under a punctuation-shifted
    collation; `theDeployedIdentifierColumnIsCollatedByteWise` reads the ordered column's
    collation out of the catalogue, because the job walks a derived finder that names no
    `COLLATE` clause and the pinned Alpine image's default is byte-wise while the deployed
    engine's is not; and `theEmittedOrderFollowsTheKeyColumnsPinnedCollation` closes the last
    gap between those two by re-pinning the column to a linguistic collation and re-running the
    job, so the emitted order is shown to FOLLOW the declared collation rather than merely to
    coexist with it.
  * *the absence of a load-back* — `leaveEveryRowOfTheRelationExactlyAsItWas` is the direct
    executable statement of this divergence: it reads every column of every row before and
    after the run and requires them identical, which is what distinguishes "no load-back" from
    "a load-back that happens to write the same values".
  * *the record image* — `emitFixedLengthRecordsWithNoDelimiter`,
    `placeEveryFieldOnItsDeclaredCopybookOffset`, `roundTripANegativeAmountThroughTheSignOverpunch`
    and `emitEachProducersOwnDescriptionPad` together carry the
    "byte for byte in the record layout" half of the preservation claim, and
    `emitAnIdenticalImageOnARerunOverTheSameRows` carries its determinism. The pad case asserts
    each producer's OWN pad rather than one shared pad, because the per-row layout selection the
    staging path performs makes the two row classes' padding a property of the row and not of
    the step — the reason the earlier `D-COMBINE-DESC-PAD` entry was withdrawn from this
    register rather than restated here.
  * *the image is composed from the baseline, not from the code* —
    `theStagedRecordMatchesAnIndependentlyComposedImage` asserts the layout claim above against
    an expected image composed from the copybook's own field widths rather than from the
    production layout registry, so the preservation this entry claims is measured against the
    baseline and not against the code that produces it.
  * *both inputs, each once* — `carryEveryCommittedRowExactlyOnceWhicheverPassWroteIt`
    and `readBothInputsCurrentAndAllocateOnlyTheOutput` state that the combined
    extract is assembled from the one relation that already holds both row classes, which is
    the premise the acceptance rests on.
* **Files.** `services/batch-service/src/main/java/com/carddemo/batch/job/CombineTransactionsJob.java`,
  `services/batch-service/src/test/java/com/carddemo/batch/job/GenerationStagingJobsTest.java`,
  `services/batch-service/src/test/java/com/carddemo/batch/job/CombineTransactionsJobTest.java`,
  `services/transaction-service/src/main/resources/db/migration/V3__ledger_bytewise_collation.sql`,
  `services/batch-service/src/test/resources/db/testharness/test-harness-schemas-and-foreign-tables.sql`.

#### D-COMBINE-GENERATION-BYPASS — the two named input generations are a precondition and not a source

* **Baseline behaviour.** [`COMBTRAN.jcl`](../../app/jcl/COMBTRAN.jcl) concatenates two physical
  datasets as the sort's input — `TRANSACT.BKUP(0)` at **L24** and `SYSTRAN(0)` at **L26** — so
  every byte of the combined output came out of one of those two files. The sort reads them; the
  contents of the master at that instant are irrelevant to it.
* **Target behaviour.** `CombineTransactionsJob` resolves both current generations and then reads
  NEITHER. It calls `requireCurrentGeneration` on each, logs the two generation numbers, and
  composes its output from `ledger.transactions` ordered by transaction identifier. The two
  resolutions are a **precondition assertion** — the step fails by family name when either family
  holds no generation — and nothing more.
* **Category.** Documented divergence — input provenance.
* **Why the difference is accepted.** AAP section 0.4.1.7 specifies state 7 as "Fargate task using
  SQL ordering", and section 0.4.1.3 places both producers' rows in one relation, so reading the
  relation is what the plan prescribes rather than a shortcut taken against it. It is also the only
  correct reading available: because interest rows are committed to `ledger.transactions` rather
  than to a separate dataset, the target's `transact-bkup` generation already contains them
  (registered separately as `D-COMBINE-BACKUP-SUPERSET`), so concatenating the two objects the way
  the reference does would emit every interest row **twice**. The precondition is kept rather than
  dropped because it preserves the reference's own failure mode: a night whose posting or accrual
  step produced no generation stops here, by name, instead of quietly combining a stale relation.
* **Consequence for an external reader.** The combined generation is **not** the concatenation of
  the two objects named in the log line. An operator who reads `transact-bkup` and `systran`,
  merges them, and compares the result against the combined generation will find the interest rows
  duplicated in their merge and once in ours. The log line names the two generations because they
  were resolved, not because they were read.
* **Consequence for a restore.** Editing or replacing either input object changes nothing about the
  next combined generation. A correction has to be applied to `ledger.transactions`; a corrected
  object staged under an input family will be resolved, counted in the log, and ignored.
* **Where it is verified.** `CombineTransactionsJobTest.theCombinedImageIsIndependentOfTheTwoNamedGenerations`
  runs the job twice with the two input families resolving to **different** generation numbers and
  asserts the staged bytes are identical, and asserts that `DatasetGenerationService` publishes no
  operation that returns a payload at all — so the independence is a property of the seam's surface
  and not only of this job's body. `GenerationStagingJobsTest` asserts the precondition half: a
  missing input fails the step by name with nothing allocated and nothing staged.
* **Files.** `services/batch-service/src/main/java/com/carddemo/batch/job/CombineTransactionsJob.java`,
  `services/batch-service/src/main/java/com/carddemo/batch/service/DatasetGenerationService.java`,
  `services/batch-service/src/test/java/com/carddemo/batch/job/CombineTransactionsJobTest.java`.


#### D-COMBINE-BACKUP-SUPERSET — the backup generation already holds the interest rows

* **Baseline behaviour.** [`TRANBKP.jcl`](../../app/jcl/TRANBKP.jcl) unloads
  `AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS` at **L26-L27** into `TRANSACT.BKUP(+1)` at **L33**. The
  master it copies **never holds the interest rows at that point**, whenever the job runs:
  [`INTCALC.jcl`](../../app/jcl/INTCALC.jcl) writes accrual output to `SYSTRAN(+1)` and not to the
  master, and those rows enter the master only through the `IDCAMS REPRO` at
  [`COMBTRAN.jcl`](../../app/jcl/COMBTRAN.jcl) **L41-L48**. So in the baseline
  `TRANSACT.BKUP` and `SYSTRAN` are **disjoint**, which is exactly why the sort can concatenate
  them.
* **Target behaviour.** `CalculateInterestJob` commits each generated interest row to
  `ledger.transactions` **and** appends it to the `SYSTRAN` generation. `BackupTransactionsJob`
  then runs as state 6 and stages the whole relation, so the `transact-bkup` generation it
  produces is a **superset** of the reference's — the difference being precisely the night's
  interest rows, which also appear in `systran`.
* **Category.** Documented divergence — dataset content, following from
  [`D-COMBINE-NO-LOADBACK`](#d-combine-no-loadback--the-combine-flow-stages-its-output-and-does-not-copy-it-back).
* **Why the difference is accepted.** It is the direct consequence of interest rows living in the
  master from the moment they are written, which is the same decision that removes the load-back
  step, and that decision is argued at that entry. Alternatives Considered: staging the backup
  BEFORE the interest state so the generation matches the reference's content. Rejected because the
  chain's order is what makes the backup useful — a backup taken before the night's accrual is not
  a backup of the night — and because the reference's own disjointness is an artefact of the
  interest rows having nowhere else to live, not a property anyone chose. Also considered:
  excluding interest-originated rows from the backup extract, rejected because the extract would
  then be a backup of nothing that ever existed, and because identifying them would mean guessing a
  producer from a description literal alone -- the objection
  [`D-TRAN-PAD-PROVENANCE`](#d-tran-pad-provenance--a-re-emitted-records-padding-is-recovered-from-its-own-attribution)
  answers for the pad by requiring the closed-domain source field as well, and which is not
  answerable here because a row's membership in a backup generation is not a property the row
  carries at all.
* **Consequence for an external reader.** `transact-bkup` ∪ `systran` **double-counts** the
  interest rows. The two objects are not disjoint in the target and are in the baseline, so any
  tool that concatenates them — including one written against the reference's job stream — produces
  duplicates on the transaction identifier.
* **Consequence for a restore.** Restoring `ledger.transactions` from `transact-bkup` alone yields
  a master that **already includes** the night's interest, so the combine step must not then be
  treated as the step that folds them in; there is no such step, and applying `systran` on top
  would duplicate them. Restoring the reference's backup required the opposite: the interest rows
  were still outside and had to be merged.
* **Where it is verified.** `BackupTransactionsJobTest` stages the full copy from the whole relation
  and asserts the image carries every committed row, whichever pass wrote it, so the superset is
  measured rather than described; `CombineTransactionsJobTest.carryEveryCommittedRowExactlyOnceWhicheverPassWroteIt`
  asserts the combined generation carries one record per relation row, so the duplication an
  external concatenation would produce is demonstrably absent from ours.
* **Files.** `services/batch-service/src/main/java/com/carddemo/batch/job/BackupTransactionsJob.java`,
  `services/batch-service/src/main/java/com/carddemo/batch/job/CalculateInterestJob.java`,
  `services/batch-service/src/main/java/com/carddemo/batch/job/CombineTransactionsJob.java`.

#### D-POSTING-GENERATION-DATE — the posting step is told its output generation at launch, where the reference resolved one at allocation

* **Baseline behaviour.** [`POSTTRAN.jcl`](../../app/jcl/POSTTRAN.jcl) runs one step,
  `//STEP15 EXEC PGM=CBTRN02C` at **L23**, and that `EXEC` carries **no `PARM=`** — the only
  `PARM=` anywhere in the batch chain is the interest job's, at
  [`INTCALC.jcl`](../../app/jcl/INTCALC.jcl) **L22**. The step is nevertheless told which
  generation of the reject stream to write, in a data definition rather than in a parameter:
  **L34-L38** allocate `DSN=AWS.M2.CARDDEMO.DALYREJS(+1)` with `DISP=(NEW,CATLG,DELETE)` and
  `LRECL=430`, and the catalog resolves that relative reference to the next generation at
  allocation time. So the step itself is unparameterised, and resubmitting the identical job
  stream allocates a further generation.
* **Target behaviour.** `PostTransactionsJob` requires the `businessDate` job parameter and
  reads it through `BatchConfig.generationDateOf`, using it for one purpose only: the `dt=`
  component of the reject stream's generation coordinate. It reaches no field of any posted or
  rejected record. Because the parameter is declared identifying, the date also names the job
  INSTANCE, so a second launch for the same date is a restart of the same instance rather than a
  new one, and a launch that omits the parameter is refused before the step opens.
* **Category.** Documented divergence — orchestration surface, no behavioural change to output
  content.
* **Why the difference is accepted.** There is no cloud analogue of a catalog that resolves
  `(+1)` on the operator's behalf, so the generation the step writes has to be named by
  something. The migration plan's rule T6 turns a `DD DSN=` into an object-store location and a
  GDG `(+1)` into a new generation prefix, and §0.4.1.7 fixes the prefix as
  `dt=YYYY-MM-DD/gen=NNNN`; §0.7.5 forbids deriving the date component from a clock, because a
  generation keyed off the clock is one a rerun cannot land in again and the golden-master
  comparison depends on a rerun reproducing its output. Passing the date the whole chain is
  already running for is therefore the narrowest available mechanism: the state machine supplies
  one `$.businessDate` to every state, so no new value is introduced and no two steps of one
  night can disagree about the day. Alternatives Considered: a distinct `generationDate`
  parameter, so the orchestration role would have its own carrier as well as its own accessor —
  rejected because the two roles always carry the same value, so a second parameter would add a
  way for one run to contradict itself and would then need validating back into agreement.
  Alternatives Considered: deriving the prefix from the run identifier alone and dropping the
  date — rejected because the `dt=` segment is what makes a generation listable and comparable by
  day, which is the property the five-generation retention rule at
  [`DALYREJS.jcl`](../../app/jcl/DALYREJS.jcl) **L24-L26** is applied over.
* **What is preserved.** Every byte of both outputs. The 430-byte reject record, the four reason
  codes, the aggregate return code and the three posted writes are all independent of this
  parameter; a run for any date over one feed produces identical content, differing only in the
  object key it lands under. The `(+1)` semantic is preserved too: each launch allocates a NEW
  generation rather than overwriting the current one, and the retained window stays five.
* **Where it is verified.** `PostTransactionsJobTest`'s `aMissingGenerationDateIsRefused` asserts
  the job's validator refuses a launch without the parameter;
  `aCleanPassStillStagesAnEmptyRejectGeneration` asserts a new generation is allocated and staged
  under the date supplied even when nothing was rejected, which is the `(+1)` semantic rather than
  an overwrite of the current generation; and `agedOutRejectGenerationsAreScratched` asserts the
  retained window. `DatasetGenerationServiceTest` pins the
  `dt=`/`gen=` prefix composition and the five-generation retention rule that the coordinate is
  listed under.
* **Files.** `services/batch-service/src/main/java/com/carddemo/batch/job/PostTransactionsJob.java`,
  `services/batch-service/src/main/java/com/carddemo/batch/config/BatchConfig.java`,
  `services/batch-service/src/test/java/com/carddemo/batch/job/PostTransactionsJobTest.java`.

#### D-INTEREST-ORPHAN-ROW — a balance row whose account cannot be read is skipped, not abended

* **Baseline behaviour.** [`CBACT04C.cbl`](../../app/cbl/CBACT04C.cbl) reads each category
  balance row's account through `1100-GET-ACCT-DATA`, and that paragraph abends when the read
  fails rather than continuing. The run therefore stops at the first balance row whose account
  is absent from the account master.
* **Target behaviour.** `CalculateInterestJob` logs the account identifier at warn level and
  skips the row, continuing with the remaining accounts.
* **Why the difference is accepted.** The step runs in one transaction, so an abend and a skip
  discard exactly the same written work — the outcome differs only in whether the remaining
  accounts are attempted. Attempting them is the more useful of the two behaviours for an
  operator, because one run then names **every** unresolvable account rather than the first,
  which is the difference between one corrective pass and one pass per orphaned row. Nothing
  is accrued for a skipped row, so no interest is invented for an account that does not exist.
  Assumptions: an orphaned balance row is a referential-integrity failure in the source data,
  and the migrated schema makes it unreachable going forward — `ledger.transaction_category_balances`
  is keyed on the account identifier and the load refuses a row whose account did not load — so
  this path is reachable only for extract data that was already inconsistent.
* **Where it is verified.** The account-absent branch is exercised by the interest job's own
  unit coverage of the control-break walk, which asserts that a row with no readable account
  produces no accrual and no account update while the surrounding rows still accrue.
* **Files.** `services/batch-service/src/main/java/com/carddemo/batch/job/CalculateInterestJob.java`.

#### D-UPDATE-CASE-SENSITIVE-COMPARE — the concurrency comparison is case-sensitive where the baseline folded ten fields

* **Baseline behaviour.** [`COACTUPC.cbl`](../../app/cbl/COACTUPC.cbl) decides whether the
  record moved under the task in `9700-CHECK-CHANGE-IN-REC` at **L4109**, whose body ends at
  **L4192**, by comparing each freshly-read field against the before-image captured in an
  earlier task. Ten of those comparisons are case-folded, and the census over **L4109-L4202**
  is exact. The lower-casing function occurs **twice**, at **L4139-L4140**, wrapping
  `ACCT-GROUP-ID` and its before-image counterpart and nothing else — those two are the only
  occurrences of that function anywhere in the program's 4236 lines. The upper-casing function
  occurs **eighteen** times over **L4152-L4173**, forming **nine** pairs: the three name
  fields, the three address lines, the state code, the country code and the government-issued
  identifier. A concurrent writer who altered only the letter case of one of those ten fields
  therefore did **not** make the baseline report a change, and the rewrite proceeded over it.
* **Target behaviour.** The comparison is the row's version member, which advances on any
  committed write regardless of which bytes that write changed. `AccountUpdateService` refuses
  a submission whose caller-held revision no longer names the versions the caller read, so a
  case-only concurrent change is refused — and refused with the baseline's own sentence,
  declared once for the migration as `ApiError.COACTUPC_RECORD_CHANGED` from **L521-L522** and
  selected for the stale-version kind by `GlobalExceptionHandler` at 409.
* **Category.** Documented divergence — concurrency-conflict sensitivity widened by the change
  of the mechanism that detects the conflict.
* **Why the difference is accepted.** The difference runs one way only: the target refuses a
  class of change the baseline admitted and admits none the baseline refused, so no write the
  baseline would have rejected now succeeds. A version member counts writes rather than
  comparing values, so there is no place in it for a per-field fold; and the ten folded fields
  are a group identifier, three names, three address lines, a state code, a country code and a
  government-issued identifier, every one of which a concurrent writer changing only case did
  still rewrite. Alternatives Considered: carrying the whole pre-edit record from the client so
  that the field-by-field comparison and its ten folds could be reproduced byte for byte.
  Rejected on two grounds — it would reinstate exactly the client-echoed state that the
  session-structure decomposition removed
  ([§7.3](#73-structural-divergences-that-are-not-defects)), and it would make the fold ten
  independent decisions each able to drift from the others, a condition already visible in the
  baseline itself, where one pair folds down and the other nine fold up.
* **Where it is verified.** `AccountUpdatePreservationTest` asserts the refusal and its
  consequence in `aStaleRevisionIsRefusedAndNothingIsWritten`, asserts that an absent
  precondition is refused in `aBlankRevisionIsRefused`, and asserts the accepting path in
  `thePublishedRevisionIsAcceptedByTheUpdate`, so both sides of the comparison are covered.
  The sentence itself is asserted in `common-lib` by `ApiErrorTest`.
* **Files.** `services/account-service/src/main/java/com/carddemo/account/service/AccountUpdateService.java`.

#### D-UPDATE-BODY-KEY-MUST-NAME-ROW — the two submitted keys must name the rows being updated, and are refused by name

* **Baseline behaviour.** [`COACTUPC.cbl`](../../app/cbl/COACTUPC.cbl) carries one account-number
  control and one customer-number control, and each serves two purposes at once. The account
  number selects the row on the path where nothing has been fetched — `1200-EDIT-MAP-INPUTS` at
  **L1429** tests the fetched marker at **L1433**, performs `1210-EDIT-ACCOUNT` and leaves at
  **L1446** — and the same value is written back into the update record at **L3960**. The customer
  number is received into `ACUP-NEW-CUST-ID-X` at **L1226-L1228** under the program's own comment
  at **L1222**, *Customer Id (actually not editable)*, and is written back at **L4009**. On the
  path where rows WERE fetched neither key is edited: **L1451-L1457** sets both key filters valid
  unconditionally and **L1460** goes straight to `1205-COMPARE-OLD-NEW`, whose first comparison is
  the account key at **L1684** and whose customer region opens with the customer key at
  **L1708-L1711**. Both controls nevertheless stay typeable — `ACCTSID DFHMDF ATTRB=(IC,UNPROT)`
  at [`COACTUP.bms`](../../app/bms/COACTUP.bms) **L84** and `ACSTNUM DFHMDF ATTRB=(UNPROT)` at
  **L254** — so an operator can overtype either after the fetch. What happens then is that the
  disagreement is discovered at the FILE: the rewrites at **L4066** and **L4086** rewrite the
  records the task read for update, so a `FROM` area whose prime key differs from the read
  record's key fails rather than relocating the row, and the program reports
  `LOCKED-BUT-UPDATE-FAILED`, `'Update of record failed'` at **L523-L524**, rolling back at
  **L4100-L4102**.
* **Target behaviour.** `AccountUpdateService.editMapInputs` edits both keys on the fetched path,
  BEFORE the old-versus-new comparison, through `editAccountKeyNamesRow` and
  `editCustomerKeyNamesRow`. Each requires the submitted value to be well formed and then to name
  the row the path addressed, comparing numerically because the baseline's own key fields are
  `PIC X(11)` redefined as `PIC 9(11)` at **L759-L761** and `PIC X(09)` redefined as `PIC 9(09)` at
  **L798-L800**. A key that names another row, and a customer key that names nothing at all, are
  refused at 400 with the offending request property named and with a target-authored sentence:
  `MESSAGE_ACCOUNT_KEY_NOT_ADDRESSED` and `MESSAGE_CUSTOMER_KEY_NOT_LOADED`. The two
  baseline-verbatim account-number sentences still answer the cases they describe, so a caller who
  merely omitted the account key sees the reference's own wording for that.
* **Category.** Documented divergence — a refusal moved from the store to the field, and given a
  sentence, for a disagreement the reference discovered only after editing everything else.
* **Why the difference is accepted.** The refusal runs in the same direction as the reference's and
  differs only in WHEN it happens and WHAT it says: nothing is written in either system, and the
  target names the control at fault where `'Update of record failed'` names only the operation. The
  target could not reproduce the late discovery even if that were desirable — its write rewrites
  the rows the repository loaded and neither mapper assigns a key, so no key-change fault exists
  for the store to raise, which makes the edit stage the only place the disagreement can be
  answered at all. Leaving it unanswered was not neutral, and that is the reason this entry exists
  rather than a note that the keys are ignored: both keys participate in the comparison, so an
  omitted or mismatched key made the comparison report a CHANGE where there was none, every
  remaining edit then passed because the rest of the submission matched, both rows were rewritten
  unchanged, and the caller was told `'Looks Good.... so far'` where
  `'No change detected with respect to values fetched.'` was the true answer. Alternatives
  Considered: dropping the two components from the request, which is the other way to make
  transport and service agree — rejected because the request is a field-for-field transcription of
  the frozen record contract and the two components are what a client echoes back from the view it
  read, so removing them would break the transcription property to avoid authoring one rule.
  Ignoring them inside the comparison instead — rejected because it hides the disagreement rather
  than answering it, and would leave the comparison silently disagreeing with fields the client
  did send. Assumptions: the customer key is the one component naming a row the caller never
  selected — it is reached from the account through the by-account cross-reference — so its edit is
  the only guard the caller's own submission gets against naming somebody else's customer.
* **Where it is verified.** `AccountUpdatePreservationTest` asserts all four refusals and that
  neither row is written in any of them — `anOmittedAccountKeyIsRefused`,
  `anAccountKeyNamingAnotherAccountIsRefused`, `anOmittedCustomerKeyIsRefused` and
  `aCustomerKeyNamingAnotherCustomerIsRefused` — and
  `aSubmissionThatChangesNothingReportsNoChangesDetected` asserts the outcome the refusals exist to
  keep reachable, reading the sentence off the RESPONSE because the two sentences are the whole of
  the observable difference between accepting and finding no change. That last case is also what
  fixed the fixture: its stored telephone number had been held without the separator the record
  stores, which no case had noticed because no case had compared a submitted number against a
  stored one.
* **Files.** `services/account-service/src/main/java/com/carddemo/account/service/AccountUpdateService.java`.

#### D-C — an unresolvable parent is reported, where the nested branch leaves it unreported

* **Baseline behaviour.**
  [`PAUDBLOD.CBL`](../../app/app-authorization-ims-db2-mq/cbl/PAUDBLOD.CBL)
  `3100-INSERT-CHILD-SEG` at **L292**–**L316** positions on a child record's parent with a
  `CALL 'CBLTDLI'` spanning **L296**–**L299** that is terminated by a **period at L299**, after
  which indentation no longer tracks scope. **L305**, `IF PAUT-PCB-STATUS = SPACES`, opens the
  branch taken when that positioning SUCCEEDED; the insert follows at **L309**; and the failure
  test at **L310**, `IF PAUT-PCB-STATUS NOT EQUAL TO SPACES AND 'II'`, sits **inside** that
  success branch, with one `END-IF.` at **L314** closing both. A genuine positioning failure
  therefore makes L305 false and control passes from L305 to **L315** without reaching either
  the insert or the failure test, so the child is neither stored nor reported.
* **Target behaviour.** `LoadService.loadDetails` resolves the parent explicitly before writing.
  When no `pending_auth_summary` row exists for the account the record's prefix names, it logs the
  account and the record's one-based ordinal at error level and raises
  `LoadService.UnresolvedParentException`, which carries both. The pass stops at that record.
* **Category.** Documented divergence — a condition the baseline passes over becomes a reported
  failure. No rule content changes.
* **Why the difference is accepted.** Three readings internal to the same program establish that
  the outcome follows from the nested structure rather than from a stated tolerance, and none of
  them rests on a judgement about the source. First, the sibling ROOT path
  `2100-INSERT-ROOT-SEG` is shaped differently: **L253**–**L262** are three FLAT tests each closed
  by its own `END-IF` — success at L253–L255, the duplicate status at L256–L258, and everything
  else at L259–L262, which reaches the abend — so the same program handles the same class of
  condition loudly one paragraph earlier. Second, the child insert at **L326**–**L336** carries
  that flat three-way shape a second time, leaving the nested shape at L305–L314 the only one of
  the three that differs. Third, L310's own body says what it was written for: **L311** writes
  `'ROOT GU CALL FAIL:'` with the status and **L312** writes the key feedback area, both
  describing a positioning failure the enclosing branch prevents them from observing.
  Alternatives Considered: reproducing the fall-through, so an unattributable child would be
  counted and passed over. Rejected because the two outcomes are not equally recoverable. Passing
  the record over loses an authorization with no trace of which one — the detail extract holds
  rows the summary extract does not account for, and afterwards neither the target nor the log
  names them, so the only way to learn what was lost is to re-derive it from the two files by
  hand. Refusing names the account on the first such record, which is the difference between one
  corrective pass and a shortfall discovered later from a balance that does not agree. The
  refusal is safe to make loud precisely because the re-run is safe: the duplicate tolerance the
  same class transcribes from L256–L258 and L329–L331 makes a second run over the same input skip
  everything already stored. Assumptions: the relational form asserts the same dependency in any
  case — `fk_pending_auth_detail_summary` in `V1__authorization.sql` declares `account_id` a
  foreign key onto `pending_auth_summary` — so leaving the check to the constraint was also
  weighed and rejected: a constraint violation surfaces from a flush, names the constraint rather
  than the extract record, and arrives wherever the provider chose to flush, identifying neither
  which record nor which account.
* **Where it is verified.** `AuthorizationExtractRoundTripTest` asserts both halves separately,
  because an implementation can have one without the other.
  `anUnresolvableParentIsRefusedAndNamesTheAccount` asserts the refusal type, the account it
  carries, the ordinal it carries and the account's presence in the message, and that nothing was
  written; `anUnresolvableParentEndsThePass` asserts that exactly one record is reached, so a
  loader that logged the account and continued would fail. Parity honesty: **no golden master
  exists for any path in this module** — the baseline programs are IMS-resident and the test suite
  runs none of them — so the oracle here is the copybook, DBD and job-stream geometry contracts
  together with the transcribed logic, and no produced output is compared.
* **Files.**
  `services/authorization-service/src/main/java/com/carddemo/authorization/service/LoadService.java`.

#### D-LOAD-PREFIX-REFUSED — an undecodable parent key is reported, where the guard has no else branch

* **Baseline behaviour.**
  [`PAUDBLOD.CBL`](../../app/app-authorization-ims-db2-mq/cbl/PAUDBLOD.CBL)
  `3000-READ-CHILD-SEG-FILE` at **L269**–**L289** guards the whole child insert with
  `IF ROOT-SEG-KEY IS NUMERIC` at **L275** — the six-byte `PIC S9(11) COMP-3` prefix its FD
  declares at **L47** — and supplies no `ELSE`. Its true branch moves the key at **L277** and
  performs the insert at **L281**; a record failing the test reaches the `END-IF` at **L282** and
  leaves the run with no message and no return code.
* **Target behaviour.** `LoadService.decodeChild` decodes the prefix through
  `com.carddemo.common.codec.PackedDecimalCodec`, reached by
  `PendingAuthDetailMapper.unloadedAccountId`, and re-raises a codec or arithmetic refusal as
  `LoadService.MalformedParentKeyException` carrying the record's one-based ordinal.
* **Category.** Documented divergence — a condition the baseline passes over becomes a reported
  failure. No rule content changes.
* **Why the difference is accepted.** It is the same argument as [D-C](#d-c--an-unresolvable-parent-is-reported-where-the-nested-branch-leaves-it-unreported)
  with less to work with, and the difference is why the two are registered separately. An
  undecodable prefix names no account at all, so unlike D-C this refusal cannot report a key —
  which is exactly why it reports the ordinal, the only handle a reader has on the record.
  Continuing past it would consume a record that no later run has any way to identify as missing,
  because nothing in the child segment names the account it belonged to. Assumptions: the refusal
  is caught around the PREFIX decode alone and not around the whole record. Both halves contain
  packed fields — the prefix, and the two amounts `cpy/CIPAUDTY.cpy` declares at its **L34** and
  **L35** — so both raise the same codec refusal for entirely different faults, and a catch wide
  enough to cover the record would report a malformed transaction amount as an undecodable parent
  key, sending an operator to inspect the one part of the record that was well formed.
* **Where it is verified.** `AuthorizationExtractRoundTripTest.anUndecodablePrefixIsRefused`
  corrupts the sign nibble of the first record's prefix — a digit in the sign position is what the
  baseline numeric test rejects, and it leaves the record still 206 bytes long, so the length check
  cannot catch it — and asserts the refusal type, its ordinal, its message and its retained cause,
  and that nothing was written.
  `theParentPrefixIsReadAsPackedDecimalAndNotAsText` asserts the storage regime rather than the
  plumbing: it states both readings of the same six bytes and asserts they disagree, because a
  reader taking them as characters would not fail but would attribute the child to a different
  account. Parity honesty: no golden master exists for this path either.
* **Files.**
  `services/authorization-service/src/main/java/com/carddemo/authorization/service/LoadService.java`.

#### D-LOAD-READ-BOUNDED — the load walk cannot fail to terminate, where two read branches suspend it

* **Baseline behaviour.**
  [`PAUDBLOD.CBL`](../../app/app-authorization-ims-db2-mq/cbl/PAUDBLOD.CBL)
  drives both files with `PERFORM ... UNTIL` at **L177**–**L178** and **L180**–**L181**, and each
  loop ends only when a file status of `'10'` sets its end flag — `END-ROOT-SEG-FILE` at **L233**
  and `END-CHILD-SEG-FILE` at **L285**. Each read paragraph also has a third branch, reached from
  any status that is neither success nor `'10'`, which writes a message and returns without setting
  the flag and without abending: **L235** for the root file and **L287** for the child file. Read
  against the two `UNTIL` conditions, reaching either leaves both the end flag and the file
  position unchanged.
* **Target behaviour.** `LoadService.records` resolves each stream into a known number of whole
  fixed-length records once, refusing a length remainder and naming the stride, after which each
  pass is a counted walk of that many elements. There is no third outcome that returns control to
  the top of a loop without consuming an element.
* **Category.** Documented divergence — a control-flow hazard is removed rather than transcribed.
  No rule content changes.
* **Why the difference is accepted.** The branch cannot be carried across as written, because the
  target has no file-status register for it to test: an input stream either yields bytes or ends.
  Transcribing its shape — reporting a condition and returning to the loop — would reproduce the
  suspension without reproducing anything a caller could act on. Settling the element count before
  the walk begins removes the possibility instead of guarding against it. Trade-offs: the stream is
  drained whole and divided before any record is decoded, which holds the whole extract in memory
  where a record-at-a-time read would hold one. Draining is chosen for a diagnostic reason the
  alternative cannot match. A length remainder is the symptom of one of these two files being handed
  to the other's reader, and the strides are 100 and 206, so the child file divided by the root
  stride leaves two whole records and a six-byte remainder — read one at a time those two records
  would be DECODED first, and a child record read against the summary layout fails inside a packed
  money field, so the run would report a malformed field rather than the mismatched file that caused
  it. The memory cost is bounded by the same transaction that already holds every entity the load
  produces, and the entities are the larger half.
* **Where it is verified.**
  `AuthorizationExtractRoundTripTest.aTruncatedRecordTerminatesRatherThanLooping` removes one byte
  from a whole summary extract and asserts the refusal names the stride, under a bounded timeout —
  expressed as a timeout deliberately, because the property under test is termination and a plain
  call would hang rather than fail if it were lost. `aPartialRecordIsRefused` asserts the same
  refusal for each file handed to the other's reader, naming both strides.
* **Files.**
  `services/authorization-service/src/main/java/com/carddemo/authorization/service/LoadService.java`.

#### D-UNLOAD-SKIP-REPORTED — a root the unload cannot attribute is counted and reported, where the guard passes it over in silence

* **Baseline behaviour.** Both unload programs guard the whole of a root's output with
  `IF PA-ACCT-ID IS NUMERIC` —
  [`PAUDBUNL.CBL`](../../app/app-authorization-ims-db2-mq/cbl/PAUDBUNL.CBL) at **L232** and
  [`DBUNLDGS.CBL`](../../app/app-authorization-ims-db2-mq/cbl/DBUNLDGS.CBL) at **L241** — and
  supply no `ELSE`. The guard encloses BOTH the root write and the child walk: PAUDBUNL writes the
  root at **L233** and performs its child loop at **L235**–**L236**, DBUNLDGS reaches the same two
  through **L243** and **L245**–**L246**, and each closes with one `END-IF` at **L237** and
  **L247**. A root failing the test therefore emits nothing, has its children left unread, and
  leaves the run with no message: neither program has a `DISPLAY` on that path, and the two
  counters they do keep — `WS-NO-SUMRY-READ` and `WS-AUTH-SMRY-PROC-CNT`, incremented at PAUDBUNL
  **L225**–**L226** — are never displayed at all.
* **Target behaviour.** `UnloadService.exportableAccountId` reproduces the skip exactly — no root
  record is written and the child walk is not reached, so the account costs no query — and then
  reports it twice: it increments `UnloadOutcome.rootsSkipped()` on the returned carrier and writes
  one `WARN` line naming the customer identifier the segment carries at
  [`CIPAUSMY.cpy`](../../app/app-authorization-ims-db2-mq/cpy/CIPAUSMY.cpy) **L20**, the account
  being exactly the value that is absent.
* **Category.** Documented divergence — additive diagnostics. A condition the baseline passes over
  in silence becomes a counted and logged occurrence. No record content, no record order and no
  rule content changes, and the bytes of both output files are identical to what the reference
  guard would have produced.
* **Why the difference is accepted.** The two outcomes differ in what an operator can conclude from
  a completed run. In silence the extract simply holds fewer roots than the table holds rows, and
  nothing in the run says which rows are missing or that any are — the shortfall is discoverable
  only by counting the file against the table afterwards, and a load of that extract then succeeds,
  because every record it does contain is well formed. With the count on the carrier, a caller
  comparing `rootsWritten()` against `rootsSkipped()` sees the shortfall in the result it already
  holds, and the identity `rootsWritten + rootsSkipped` equals the number of roots the walk reached,
  so there is no third disposition a returned outcome can hide. Alternatives Considered: raising
  instead, which is the treatment [D-C](#d-c--an-unresolvable-parent-is-reported-where-the-nested-branch-leaves-it-unreported)
  gives the mirror-image condition on the load side. Rejected here because the two sides are not
  symmetric in what continuing costs: a load that continues past an unattributable record loses an
  authorization permanently, whereas an unload that continues past one still exports every other
  root, and the row it passed over is still in the table to be exported by the next run. Refusing
  would discard the roots already written for no gain in what the operator learns. Assumptions: the
  condition is unreachable through the schema, so this is a statement about the walk rather than a
  live code path. `V1__authorization.sql` declares `account_id BIGINT NOT NULL` as
  `pending_auth_summary`'s whole primary key, and a keyed page predicate cannot return a row whose
  key is absent in any case. The guard is reproduced so that a reader comparing this walk against
  either reference program finds the same branch, and so that a summary reaching the exporter from
  somewhere other than that table is handled by a stated decision rather than by whatever a
  dereference happens to do.
* **Where it is verified.**
  `UnloadServiceTest.aSummaryWithNoAccountIdentifierIsSkippedAndCounted` presents an
  unattributable row ahead of the two fixture roots and asserts all three halves of the guard
  together: the skip count is one, both real roots are still exported, the root file holds exactly
  two hundred-byte records, and the child walk is never asked about the unattributable row while
  being asked exactly twice in total.
  `theOutcomeAccountsForEveryRootTheWalkReached` asserts the reconciliation identity itself rather
  than inferring it from those two counts agreeing in one case.
  `aPageWithNoResumableKeyEndsTheWalk` asserts the adjacent termination property under a bounded
  timeout — expressed as a timeout deliberately, because a walk that could not advance its position
  would hang rather than fail. Parity honesty: no golden master exists for any path in this module,
  so nothing here is compared against a recorded reference output; what is asserted is the branch
  structure of the two transcribed walks and the geometry the copybooks, the database descriptions
  and the job streams declare.
* **Files.**
  `services/authorization-service/src/main/java/com/carddemo/authorization/service/UnloadService.java`.

#### D-E, also D-PURGE-YEAR-BOUNDARY — ordinal day numbers are subtracted as plain integers

* **Baseline behaviour.**
  [`CBPAUP0C.cbl`](../../app/app-authorization-ims-db2-mq/cbl/CBPAUP0C.cbl) recovers the stored
  authorization date at **L280** by complementing it against 99999, subtracts it from the current day
  number at **L282**, and qualifies the row at **L284** with an inclusive comparison against the
  threshold. Both operands are five-digit ordinals of the form `YYDDD` and the subtraction is plain
  integer arithmetic, so it does not yield a calendar-day difference across a year boundary: ordinal
  23365 is 31 December 2023 and 24001 is 1 January 2024, one day apart, and subtracting them gives
  **636**. The receiving field is the second limb — `WS-DAY-DIFF` is declared `PIC S9(4) COMP` at
  **L47**, so it holds at most ±9999 while the widest ordinal span, 99999 less 00001, is 99998. The
  business date itself is read from the platform at **L187** by `ACCEPT CURRENT-YYDDD FROM DAY`.
* **Target behaviour.** `PurgeJob` converts both ordinals to calendar dates and differences those, so
  31 December and 1 January are one day apart, and the four-digit ceiling disappears. The business date
  is a required component of `PurgeJob.PurgeParameters` with no default at all, and the `Clock` that
  previously supplied one was removed from the constructor rather than left unused.
* **Category.** Documented divergence — date arithmetic, plus removal of a wall-clock read.
* **Why the difference is accepted.** With the five-day default from **L199**, 636 satisfies **L284**,
  so a run made near New Year removes December authorizations that are days old. Alternatives
  Considered: reproducing the ordinal subtraction for parity. Rejected because the rows it removes have
  not aged, and destroying live authorizations is not a behaviour a parity argument reaches. Retaining a
  no-argument entry point that read a clock was also evaluated and rejected: a caller omitting the date
  would obtain exactly the non-determinism this entry removes, and a rerun made to investigate a run
  would select a different set of rows from the run it was investigating.
* **Where it is verified.** `PurgeJobTest.theYearBoundaryDoesNotExpireAOneDayOldAuthorization` asserts
  that the ordinal subtraction gives 636, that 636 exceeds the default threshold, that the calendar
  difference is 1, and that the row is read but not removed — so the case cannot pass on arithmetic that
  merely happens to agree. `PendingAuthDetailNewYearFixtureTest` names both values against the committed
  fixture `pautdtl1-newyear-pair.bin`.
* **Files.**
  `services/authorization-service/src/main/java/com/carddemo/authorization/service/PurgeJob.java`.

#### D-F, also D-PURGE-DELETE-GUARD — one counter is tested twice, so the second condition is unreachable

* **Baseline behaviour.**
  [`CBPAUP0C.cbl`](../../app/app-authorization-ims-db2-mq/cbl/CBPAUP0C.cbl) **L156** reads
  `IF PA-APPROVED-AUTH-CNT <= 0 AND PA-APPROVED-AUTH-CNT <= 0`. The approved counter is named on both
  sides of the conjunction and the declined counter — `PA-DECLINED-AUTH-CNT` at
  [`CIPAUSMY.cpy`](../../app/app-authorization-ims-db2-mq/cpy/CIPAUSMY.cpy) **L28** — is never
  mentioned, so the second condition adds nothing and the root delete at **L157** runs on the approved
  count alone.
* **Target behaviour.** `PurgeJob` removes a summary only when BOTH counters have fallen to zero or
  below. The comparison stays `<=` rather than becoming `==`, because the counters are signed
  four-digit fields whose negative half the schema's own check constraint admits.
* **Category.** Documented divergence — delete-guard predicate widened to the field the baseline
  omitted.
* **Why the difference is accepted.** The consequence of the baseline guard is not cosmetic: a summary
  whose approved count has reached zero satisfies it while unexpired DECLINED authorizations remain
  beneath it, and the hierarchical delete then removes those rows too. Alternatives Considered:
  reproducing the duplicated condition. Rejected on the same ground as the entry above — it removes rows
  that have not aged. The change runs one way only, refusing a delete the baseline performed and
  performing none the baseline refused.
* **Where it is verified.** `PurgeJobTest.aSummaryWithLiveDeclinedChildrenIsNotDeleted` expires only the
  approved pair, which is precisely the state that distinguishes the two guards, and asserts that the
  summary survives and that no parent delete is issued.
* **Files.**
  `services/authorization-service/src/main/java/com/carddemo/authorization/service/PurgeJob.java`.

#### D-G, also D-PURGE-COUNTER-PERSISTENCE — four decrements are computed and never persisted

* **Baseline behaviour.**
  [`CBPAUP0C.cbl`](../../app/app-authorization-ims-db2-mq/cbl/CBPAUP0C.cbl) **L287 to L292** reverses
  the expiring authorization out of its parent's four running totals — subtracting one and the approved
  amount for an approved row at **L288** and **L289**, and one and the transaction amount otherwise at
  **L291** and **L292** — into the working-storage copy its **L223 to L226** retrieved. It then has no
  verb able to write that copy back: the program's complete data-language inventory is one `CHKP` at
  **L355**, two `DLET` at **L310** and **L335**, one `GN` at **L223** and one `GNP` at **L255**, with no
  `REPL` and no `ISRT` anywhere in its 386 lines. A summary that keeps some children is therefore left
  holding counters and totals that still include the rows just removed.
* **Target behaviour.** The reversal is applied to the managed entity and persisted at the same commit
  as the child delete, so the adjustment and the deletion cannot diverge.
* **Category.** Documented divergence — durability of an adjustment the baseline computes but cannot
  store.
* **Why the difference is accepted.** The baseline plainly INTENDS the reversal, because its own delete
  guard at **L156** reads the reversed values, so the arithmetic is load-bearing and only the
  hierarchical storage model prevents it being kept. Alternatives Considered: reproducing the behaviour
  by computing the reversal locally and leaving the row untouched. Rejected on two grounds — in the
  relational target the retrieved copy and the row ARE the same object, so not persisting would mean
  adding code to detach or shadow the entity in order to reproduce an artifact of the reference's
  storage model rather than any of its business rules, and the result would be a summary whose displayed
  counts permanently disagreed with the authorizations beneath it on a screen that shows both. The
  divergence is narrow: it is observable only in the two counters and two totals of a summary that
  survives a run, and it moves them towards agreement with the rows that remain.
* **Where it is verified.** `PurgeJobTest.expiringEveryChildBalancesTheParentToZero` asserts all four
  totals reach zero, with the fixture built so that each declined row's transaction amount differs from
  its approved amount — so an implementation passing one amount to both arms leaves the declined total
  short instead of at zero. `PurgeJobTest.aFailureInsideAWindowRollsTheWindowBack` asserts the same-commit
  property from the other side, by proving a failed window is rolled back and never committed.
* **Files.**
  `services/authorization-service/src/main/java/com/carddemo/authorization/service/PurgeJob.java`.

#### D-PURGE-EXPIRY-FLOOR — an expiry threshold of zero is refused rather than admitted

* **Baseline behaviour.** Three guards in `1000-INITIALIZE` of
  [`CBPAUP0C.cbl`](../../app/app-authorization-ims-db2-mq/cbl/CBPAUP0C.cbl) are not written alike.
  **L196** guards the expiry parameter with `IF P-EXPIRY-DAYS IS NUMERIC` alone, while **L201** and
  **L204** guard the two checkpoint parameters with `= SPACES OR 0 OR LOW-VALUES`, which additionally
  tests for zero. The card shipped at
  [`CBPAUP0J.jcl`](../../app/app-authorization-ims-db2-mq/jcl/CBPAUP0J.jcl) **L37** is
  `00,00001,00001,Y`; its first field is numeric, so **L196** is satisfied, **L197** moves it, the
  **L199** fallback of five is never reached, and **L284** then reads as a comparison against zero,
  which holds for every authorization dated on or before the run date. The two checkpoint values on the
  same card are `00001` rather than `00000`, so their zero guards never fire either, which is harmless
  because one is a serviceable frequency.
* **Target behaviour.** `PurgeJob.PurgeParameters` refuses a non-positive expiry threshold in its
  canonical constructor, before anything is read, and refuses both frequencies on the same terms. The
  three defaults remain the baseline's own five, five and ten from **L199**, **L202** and **L205**.
* **Category.** Documented divergence — parameter domain narrowed at the boundary.
* **Why the difference is accepted.** The threshold is the only value standing between a run and the
  whole table, and a parameter that disqualifies nothing is indistinguishable at the call site from one
  that was never supplied. Alternatives Considered: admitting zero and relying on the caller, as the
  baseline does. Rejected because the guard asymmetry above shows how easily a zero reaches the
  comparison unnoticed — had **L196** carried the same zero test as its two neighbours, the shipped card
  would have taken the default. Refusal is reported as an argument failure rather than as a failed run,
  because the baseline substitutes defaults for its parameters and never ends a run over one.
* **Where it is verified.** `PurgeJobTest.theRunParametersCarryTheReferenceDefaultsAndRefuseZero`
  asserts the three defaults are five, five and ten, and asserts each of the three counts is refused at
  zero with the offending parameter named in the message, together with an absent business date.
* **Files.**
  `services/authorization-service/src/main/java/com/carddemo/authorization/service/PurgeJob.java`.

Assumptions: `D-PURGE-BALANCE` is cited by identifier in shipped source — in `PurgeJob`, in
`PurgeJobTest` and in `PendingAuthSummaryReversalTest` — and deliberately has NO entry in this section,
because it names a PRESERVED asymmetry rather than a difference. The authorization consumer adds an
approved amount to the reserved credit balance at
[`COPAUA0C.cbl`](../../app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl) **L817** and the purge program
releases nothing — a search for `BALANCE` across all 386 lines of
[`CBPAUP0C.cbl`](../../app/app-authorization-ims-db2-mq/cbl/CBPAUP0C.cbl) returns no match — so an
account whose authorizations all expire retains the reserved balance, and the target retains it too. Its
rationale sits on `PendingAuthSummary.reverseApproved` and it is asserted by
`PurgeJobTest.expiringEveryChildBalancesTheParentToZero`. It is recorded here so that a reader searching
this register for the identifier learns why it is absent rather than concluding the claim is unhonoured.

#### D-REPORT-ORDER-FINGERPRINT — the report and the statement order on a keyed per-card fingerprint, not on the card number

* **Baseline behaviour.** Both artifacts are ordered by the card number itself, in the clear.
  [`TRANREPT.jcl`](../../app/jcl/TRANREPT.jcl) declares `SORT FIELDS=(TRAN-CARD-NUM,A,TRAN-PROC-DT,A)`
  at **L46** over the symbols it defines at **L41-L42**, so the detail lines arrive ordered by the
  sixteen-digit number and the group break at
  [`CBTRN03C.cbl`](../../app/cbl/CBTRN03C.cbl) compares the card it last saw against the one it holds.
  [`CREASTMT.JCL`](../../app/jcl/CREASTMT.JCL) sorts on the card number at **L53** for the same reason,
  and [`CBSTM03A.CBL`](../../app/cbl/CBSTM03A.CBL) breaks on `WS-SAVE-CARD PIC X(16)` at **L69**.
* **Target behaviour.** No relation the reporting context may read publishes the unmasked number: every
  view renders it as twelve asterisks and the last four digits. Ordering, grouping and joining are
  therefore performed on `card_fingerprint`, a keyed digest each view computes from a secret held in
  `reporting.card_grouping_key` — a table in the reporting schema that the reporting login is revoked
  from. Exact selection of one card, which a masked value cannot express, is performed by
  `reporting.resolve_card`, a definer-rights function that takes the whole number and returns the masked
  rendering, the fingerprint, the customer and the account.
* **Category.** Documented divergence — ordering key substituted, ordering PROPERTY preserved.
* **Why the difference is accepted.** Two properties are preserved and one is deliberately not. The
  fingerprint is injective on the card number, so one card's lines still sort together and a group break
  still occurs exactly where the baseline's does — which is what the report's group subtotals depend on.
  What is NOT preserved is the sequence BETWEEN cards: a digest orders differently from the number it
  digests, so two cards appear in a different relative order than the reference emits them in. That is
  accepted because the alternative is to publish the sixteen-digit number to the ordering, and an
  ordering key reaches an index, a query plan, a statistics sample and any log line that renders a sort
  key. Alternatives Considered: ordering on the masked rendering, which is what an earlier revision did.
  Rejected outright, and not merely as a weaker option: twelve of a masked value's sixteen positions are
  constant, so it names a TAIL rather than a card — two cardholders sharing a tail collapse into one
  group, and a lookup by it returned a different cardholder's statement where the requested card did not
  exist. Alternatives Considered: an unkeyed digest, or a keyed digest with a published salt. Both
  rejected because a sixteen-digit number with a check digit is a small enough candidate space to
  enumerate, so anyone holding a token could confirm which card produced it; and for the same reason no
  forward oracle is offered — `resolve_card` answers nothing for a card that does not exist, so it
  discloses a fingerprint only to a caller that already held the number.
* **Where it is verified.** `data-migration/tests/test_reporting_views.py` asserts that no view selects
  the base card column, that the fingerprint is computed from the secret concatenated with the trimmed
  number, and that the grouping-key table is revoked. `StatementServiceTest.aCardIsResolvedByTheWholeNumber`
  asserts the argument reaching the resolution query is the whole number and carries no mask character;
  `TransactionReportServiceTest.groupSubtotalsBreakOnTheCard` asserts the group band closes on the card
  rather than on the account, using two cards under one account so the two groupings give different
  answers. The whole chain — the three view edits and the function — was additionally applied to a live
  PostgreSQL 17 engine, where the fingerprint published by all three views was byte-identical to the one
  the function returns for the same card, and the reporting login was refused on the grouping-key table.
* **Files.** `data-migration/sql/V1__reporting_views.sql`,
  `services/reporting-service/src/main/java/com/carddemo/reporting/domain/CardXrefView.java`,
  `services/reporting-service/src/main/java/com/carddemo/reporting/repository/StatementCardXrefRepository.java`,
  `services/reporting-service/src/main/java/com/carddemo/reporting/repository/TransactionReportRepository.java`.

#### D-STMT-RESPONSE-BOUNDED — the statement HTTP response carries a bounded window, and reports the true count beside it

* **Baseline behaviour.** The statement generator has no request surface at all: it renders every
  transaction of every card into two datasets in one pass, and its per-card table has no bounds check —
  a single card renders up to 512 transactions and the 513th overruns it, which
  [`tests/README.md`](../../tests/README.md) §1.1 records as a measured defect of the immutable source.
* **Target behaviour.** The artifact path is unbounded in the same sense the reference is — it streams
  every transaction of every card, in bounded chunks, with no fixed arity anywhere. The HTTP path,
  which the reference does not have, returns at most `MAX_RESPONSE_TRANSACTIONS` lines and reports the
  bound in the body: the statement operation carries the card's TRUE transaction count in its heading,
  and the transactions operation carries that same count plus a derived `truncated` flag beside the
  rows. The contract declares the ceiling as `maxItems` on the array.
* **Category.** Documented divergence — a bound on a surface the baseline does not publish.
* **Why the difference is accepted.** The bound applies only to the added surface, so no artifact byte
  changes and the golden masters are untouched. It is necessary because an unbounded response is an
  unbounded allocation on a request thread driven by a value the caller does not control — the size of a
  cardholder's history. Alternatives Considered: paginating the response, which is what the report's
  line listing does. Rejected for this operation because a statement is a document rather than a
  browse: the reference emits one statement per card as a unit, and a paged statement would publish a
  shape the artifact has no equivalent of. Alternatives Considered: returning the count of rows in the
  body as the transaction count, which is what a naive implementation does. Rejected because the two
  agree on every statement short of the bound and disagree on exactly the statements where the
  difference matters, so a caller could not tell a truncated document from a complete one.
* **What was corrected after this entry was first written.** The bound was real and documented, and the
  argument above for why it stays detectable was true of ONE of the two operations. The statement
  operation returns the heading and so lets a caller compare; the transactions operation returns the
  row collection and nothing else, so for a caller of it the bound was silent — and the published
  contract asserted the opposite outright, describing the set as "returned whole" with "no maximum item
  count … declared". The collection now carries `transactionCount` and a `truncated` flag derived at
  construction, the contract declares `maxItems` equal to the service's own constant, and the two false
  paragraphs are withdrawn. No bound moved: what changed is that the bound is now stated where it
  applies.
* **Where it is verified.** `StatementServiceTest.aCappedWindowStillReportsTheTrueCount` stubs a card
  holding 4,211 transactions, asserts the window is requested at exactly the bound, and asserts the
  heading reports 4,211. `StatementServiceTest.aHeadingOnlyReadMaterialisesNoTransactionRow` asserts
  the summary operation reads no transaction row at all.
* **Files.**
  `services/reporting-service/src/main/java/com/carddemo/reporting/service/StatementService.java`.

#### D-ARTIFACT-REPLACE-ON-COMPLETION — a failed run leaves the previous artifact readable, where the reference leaves none

* **Baseline behaviour.** [`CREASTMT.JCL`](../../app/jcl/CREASTMT.JCL) deletes both outputs in a
  preliminary step: `STEP030` at **L66** runs `IEFBR14` over them with `DISP=(MOD,DELETE,DELETE)` across
  **L67-L75**, and only then does `STEP040` at **L79** allocate both fresh with
  `DISP=(NEW,CATLG,DELETE)` at **L87** and **L92**. A run that fails after the deletion step therefore
  leaves NO artifact at all.
* **Target behaviour.** Nothing is published at the destination key until a run completes: records
  accumulate into a fixed part buffer and are uploaded a part at a time, and the object appears only on
  the completion call. A failed run leaves the PREVIOUS artifact in place, and the dataset bucket is
  versioned with five noncurrent versions retained.
* **Category.** Documented divergence — failure outcome improved, success outcome identical.
* **Why the difference is accepted.** On the success path the two are indistinguishable: a write to an
  existing key replaces it exactly, which is the delete-then-allocate semantic. On the failure path the
  target's outcome is strictly better — an operator reading yesterday's statements is better served than
  one reading none — and a half-written object never becomes visible under either scheme. Alternatives
  Considered: reproducing the deletion literally, as a delete call before the first record. Rejected
  because it converts every transient storage failure into a total absence of the artifact, and the
  reference's reason for the step was that a sequential dataset opened `MOD` would otherwise be EXTENDED
  — a hazard object storage does not have, since a key write is a replacement.
* **Where it is verified.** `TaskDispatchWiringTest.aStatementRunPublishesTwoArtifacts` asserts a run
  publishes exactly the two run-wide objects the reference declares and no per-statement object;
  `StatementServiceTest.anEmptyRunStillClearsThePreviousArtifacts` asserts a run producing no statement
  still clears, which is the one property the reference's separate deletion step buys and which the
  target keeps as an explicit sink call rather than as an effect of the first write.
* **Files.**
  `services/reporting-service/src/main/java/com/carddemo/reporting/sink/S3ArtifactWriter.java`,
  `services/reporting-service/src/main/java/com/carddemo/reporting/sink/S3StatementSink.java`.

#### D-STMT-OPTIONAL-ADDRESS-BLANK — an absent middle name or second address line prints blank rather than stopping the run

* **Baseline behaviour.** [`COSTM01.CPY`](../../app/cpy/COSTM01.CPY) carries each printed customer
  attribute as a fixed-width character field, and a COBOL `MOVE` of a group with nothing in it leaves the
  receiving field space-filled. A customer with a one-line street address therefore prints a blank line
  and the run continues.
* **Target behaviour.** `account.customers` declares `middle_name` and `addr_line_2` nullable and every
  other projected attribute not null, and the statement heading normalises those two — and only those
  two — to blanks as it is constructed.
* **Category.** Documented divergence — none in observable output; a defect removed on the way to
  matching the reference.
* **Why the difference is accepted.** It is not a behavioural change at all: the rendered artifact is
  byte-identical to the reference's for the same record. It is registered because the code path it
  replaces was NOT identical — the band assembler refuses a null, which is correct of the assembler, so
  the first customer with no second address line aborted the whole night's statements with a
  null-pointer failure naming a field. Alternatives Considered: treating an absent value as an
  unresolved dimension, alongside the customer and account checks the same factory performs. Rejected
  because a nullable column is a legitimate record rather than a broken join, and abending over one
  would stop a run for a customer who simply has a short address.
* **Where it is verified.**
  `StatementServiceTest.aCustomerMissingOptionalAttributesStillStatements` drives a run whose only card
  belongs to a customer with neither attribute and asserts the statement is produced.
* **Files.**
  `services/reporting-service/src/main/java/com/carddemo/reporting/service/StatementService.java`.

#### D-BROWSE-PREDICATE-STATED — the paging comparison is stated in the query, not left to the access method

* **Baseline behaviour.** [`COTRN00C.cbl`](../../app/cbl/COTRN00C.cbl) positions its
  browse in `STARTBR-TRANSACT-FILE` at **L591**, naming the dataset, the record
  identification field and the key length at **L594-L596**. The line that would state
  the comparison — the greater-or-equal option at **L597** — is **commented out**, so
  which rows the position admitted was decided by the access method's default rather
  than by anything a reader of the source can determine. The same program's forward and
  backward reads then walk from wherever that position landed.
* **Target behaviour.** `TransactionListService` states the comparison explicitly in the
  query: strictly greater than the trailing key when paging forward, strictly less than
  the leading key when paging backward, each with the matching sort direction and a
  limit of one more row than the page holds.
* **Category.** Documented divergence — browse boundary.
* **Why the difference is accepted.** A predicate that is written down is a predicate
  that can be tested, and the strict form is the one that makes a cursor a cursor: an
  inclusive comparison re-delivers the row the cursor names, so a client paging forward
  would receive its own last row again as the first row of the next page. The baseline
  cannot exhibit that because its own key-capture and its next-page probe happen to
  advance past the boundary row, so the strict predicate reproduces the rows the
  terminal displayed. What changes is only that the rule is now legible at the call
  site rather than inherited from an access method's default.
* **Where it is verified.** `TransactionRepositoryIT`'s
  `theCardFilteredKeysetPathsStepForwardAndBackwardStrictly` steps a live page boundary in
  both directions and asserts the boundary row is not re-delivered, and
  `theKeysetPageBoundaryIsUnaffectedByAnInsertBehindTheCursor` asserts the stated
  predicate is what makes that hold under a concurrent insert.
  `KeysetPaginationGateProofTest` separately forbids any offset-paging construct in the
  list-handling packages, so the stated predicate cannot be replaced by one.
* **Files.**
  `services/transaction-service/src/main/java/com/carddemo/transaction/service/TransactionListService.java`,
  `services/transaction-service/src/main/java/com/carddemo/transaction/repository/TransactionRepository.java`.

#### D-BROWSE-TRAILING-KEY-ON-SHORT-PAGE — a short page still names the last row it carried

* **Baseline behaviour.** [`COTRN00C.cbl`](../../app/cbl/COTRN00C.cbl) captures the
  trailing browse key **inside** the branch that fills the tenth screen slot, at
  **L438-L439**, so a page that returned fewer than ten rows leaves the previously
  captured trailing key standing. Its leading key is captured unconditionally at
  **L393**, and **L315** sets the no-further-page condition whenever the fill read
  nothing at all.
* **Target behaviour.** The page envelope's `lastKey` names the last row the page
  actually carried, whether the page is full or short, and a page carrying no rows names
  no boundary at all and reports no further page.
* **Category.** Documented divergence — cursor identity on a short page.
* **Why the difference is accepted.** A stale trailing key is a cursor that names a row
  from an earlier page, so a client paging forward from it silently re-reads rows it has
  already seen. Because the baseline only refreshes the key on a full page, the
  condition is reachable exactly when the last page is short — which is every browse
  whose row count is not a multiple of ten. Naming the row actually carried removes the
  repetition without changing which rows any page contains, and it keeps the envelope's
  two facts independent in the same way
  [`D-LASTKEY-RECEIVED`](#d-lastkey-received--the-page-cursor-names-only-rows-the-caller-received)
  does for the card browse.
* **Where it is verified.** `PageResponseTest`'s `emptyPageNamesNoBoundary` asserts a page
  with no rows names neither boundary and reports no further page, and
  `finalPageMayNameItsTrailingBoundary` asserts a short final page still names the key of
  its own last row; `TransactionRepositoryIT`'s
  `theSurplusRowRevealsAFurtherPageWithoutBecomingPartOfIt` asserts the look-ahead row
  never becomes the boundary.
* **Files.**
  `services/transaction-service/src/main/java/com/carddemo/transaction/service/TransactionListService.java`,
  `services/common-lib/src/main/java/com/carddemo/common/web/PageResponse.java`.

#### D-POSTING-ATOMIC-NO-REJECT-109 — a failed account rewrite discards the whole post, and reason 109 still reaches no stream

* **Baseline behaviour.** [`CBTRN02C.cbl`](../../app/cbl/CBTRN02C.cbl) assigns reason
  **109** when the account rewrite hits `INVALID KEY`, at **L556** inside
  `2800-UPDATE-ACCOUNT-REC` at **L545**. That paragraph is performed at **L441** from
  `2000-POST-TRANSACTION` at **L424**, which the record loop enters at **L212** only after
  **L211** found the reason to be zero. The assignment therefore happens *after* the branch
  toward the reject write has already not been taken: the `ELSE` arm at **L213-L215** does
  not run, so no reject row is written and `WS-REJECT-COUNT` is not incremented; **L226-L228**
  leaves the return code unchanged; **L208-L209** clear the reason at the top of the next
  record. Meanwhile the transaction write at **L564** and the category-balance change at
  **L526** have already happened and both stand. The net baseline result is a **posted
  transaction whose account was never updated, with no record anywhere of why.**
* **Target behaviour.** The three posting writes of ONE FEED RECORD are one ACID commit, so a
  failed account update discards that record's transaction row and category-balance change
  with it. **Nothing is written to the reject stream for this failure**, and reason **109** is
  as unreachable in the target as it is in the reference: `PostTransactionsJob` declares one
  boundary per record — its tasklet is `PROPAGATION_NOT_SUPPORTED` and each record's decisions
  and writes run inside a `TransactionTemplate` — and it catches nothing around the account
  write, so the failure propagates, that record's transaction rolls back and the step fails
  into the orchestrator's per-state `Retry`. **Records committed before the failing one are not
  undone**, which is the reference's own behaviour rather than a weakening of it: the reference
  commits each record as it goes, and the durable step ledger records the failure so a redrive
  resumes rather than repeats.
  `RejectReason.isPersistedToRejectStream()` answers `false` for
  `ACCOUNT_NOT_FOUND_ON_REWRITE` alone, `PostingValidationResult` refuses to accept it as a
  validation outcome, and `TransactionRejectRecordMapper` declares the persisted reason-code
  domain as exactly {100, 101, 102, 103}. The reachable form of the failure is an
  optimistic-lock loss on the account's `@Version` column, because the account row was
  already read during validation and an account absent at READ time is reason **101** rather
  than this one.
* **Category.** Documented divergence — posting atomicity.
* **Why the difference is accepted.** The baseline state is not reconcilable from the data:
  a ledger row exists, a balance moved, the account did not, and nothing records the
  failure except a console line. Any downstream reader — a statement, a report, a balance
  enquiry — sees a posted transaction the account does not reflect, and no query can
  distinguish that from a data-entry error. The single commit removes that state outright,
  which is the whole of the difference being registered here. Note the scope precisely: the
  state removed is a posted transaction whose OWN account update failed, and removing it needs
  only that one record's writes to be atomic. Alternatives Considered:
  discarding the partial writes AND writing a durable reason-109 row from outside the
  rolled-back unit of work, so that the failure were queryable from
  `ledger.transaction_rejects` as well as being undone. **Rejected on three grounds.** It
  needs a second transaction boundary inside the one file that declares itself the sole
  owner of the boundary, so the property that makes the post atomic would be qualified by a
  writer that escapes it. It puts a row in the reject stream that the baseline's stream does
  not have, so the byte comparison [§9.1](#91-the-comparison-contract) rests on would differ
  on exactly the records whose account write failed — trading a real parity guarantee for a
  convenience. And the event is not in fact lost without it: the step fails, so the failure
  surfaces as a failed state in the batch state machine and as the task's own logged
  exception, which is where an operator looks for a failed step rather than in a data table.
  Trade-offs: a failed account write is therefore NOT queryable from the reject stream, and
  an operator correlating a missing post to a cause reads the step's log rather than a row.
  That is accepted because the transaction is not posted either — there is no inconsistent
  row for the reject row to explain, which was the whole reason the durable-row design was
  attractive while the partial post still stood.
* Refactoring Rationale: the *Target behaviour* above previously described the boundary as
  spanning the WHOLE PASS. That was accurate when written and is no longer: a pass-wide
  boundary discarded every correct posting that preceded a late failure, held every touched row
  locked for the batch window, and made the durable step ledger's resume promise untrue, so the
  boundary was narrowed to one transaction per record. The divergence being registered here is
  unaffected — within one record the target is still strictly more atomic than the baseline, and
  it is that intra-record atomicity, not any cross-record atomicity, that removes the
  unreconcilable state described below.
* Refactoring Rationale: this entry replaces `D-REJECT-109-DURABLE`, whose *Target
  behaviour* described the durable-row design above as shipped. No code ever wrote such a
  row: no main source anywhere names `ACCOUNT_NOT_FOUND_ON_REWRITE` except the constant, the
  predicate that excludes it and the result type that refuses it. A register entry asserting
  a behaviour the code does not have is worse than a missing entry, because a reader who
  finds it stops looking. The withdrawn identifier is recorded in
  [§7.5](#75-withdrawn-divergence-identifiers), and this entry is cited by identifier from
  both `PostTransactionsJob` and `RejectReason` so that the two sides can be reconciled by
  search.
* **Where it is verified.** `PostingUnitOfWorkIT`'s
  `aRefusalAtTheLastWriteRollsBackTheEarlierTwo` and `aRefusedFirstPostingLeavesNoTrace`
  assert against a real engine that a refusal inside the unit of work leaves neither schema
  changed, which is the atomicity half. `RejectReasonTest`'s
  `onlyTheAccountRewriteReasonIsNeverPersisted` and
  `thePersistedReasonsAreTheFourCodesTheRejectEntityDeclares`, and
  `PostingValidationServiceTest`'s `noInputEverReportsTheRewriteReason`, assert the
  unreachability half from both directions. `TransactionRejectRepositoryIT`'s
  `theColumnDomainAdmitsReasonOneHundredAndNineAlthoughTheReferenceWritePathNeverReachesIt`
  asserts the narrower fact its name states — that the COLUMN admits the code — which is a
  schema property and not a claim that anything writes it. The fixture and its reasoning are
  `services/transaction-service/src/test/resources/fixtures/reject_109_rewrite_invalid_key`.
  `RejectReason.ACCOUNT_NOT_FOUND_ON_REWRITE` keeps the code distinct from
  `ACCOUNT_NOT_FOUND_ON_READ` even though their descriptions are byte-identical.
* **Files.**
  `services/batch-service/src/main/java/com/carddemo/batch/dto/RejectReason.java`,
  `services/batch-service/src/main/java/com/carddemo/batch/job/PostTransactionsJob.java`,
  `services/transaction-service/src/main/java/com/carddemo/transaction/domain/TransactionReject.java`,
  `services/transaction-service/src/main/resources/db/migration/V1__ledger.sql`.

#### D-EXPORT-PROTECTED-FIELDS-ELIDED, also D-EXPORT-PROTECTED-SPANS-REDACTED — the export dataset carries three protected spans empty

* **Baseline behaviour.** [`CBEXPORT.cbl`](../../app/cbl/CBEXPORT.cbl) copies every field of
  every master into the export record verbatim, including three the target holds enciphered.
  **L294** moves `CUST-SSN` into `EXP-CUST-SSN`, **L295** moves `CUST-GOVT-ISSUED-ID` into
  `EXP-CUST-GOVT-ISSUED-ID`, and **L537** moves `CARD-CVV-CD` into `EXP-CARD-CVV-CD`. All three
  source fields are cleartext on the VSAM masters — `CUST-SSN PIC 9(09)` at
  [`CVCUS01Y.cpy`](../../app/cpy/CVCUS01Y.cpy) **L17**, `CUST-GOVT-ISSUED-ID PIC X(20)` at
  **L18**, and `CARD-CVV-CD PIC 9(03)` at [`CVACT02Y.cpy`](../../app/cpy/CVACT02Y.cpy) **L7** —
  so the flat file the baseline writes carries all three in the clear as well.
* **Target behaviour.** `ExportJob` emits all five record types, and the three spans above are
  written **empty for their own storage kinds** rather than carrying data: nine zero digits for
  `EXP-CUST-SSN`, an unsigned display field at [`CVEXPORT.cpy`](../../app/cpy/CVEXPORT.cpy)
  **L36**; twenty blanks for `EXP-CUST-GOVT-ISSUED-ID`, a character field at **L37**; and two
  zero bytes for `EXP-CARD-CVV-CD`, a `COMP` halfword at **L96**. The three empty forms differ
  from one another because their declared pictures differ, and each is a valid value of its own
  field, so every record still closes at 500 bytes, still decodes under its view, and is still
  routed by the import dispatcher rather than reaching its unknown-type handler.
* **Category.** Documented divergence — data exposure at a serialisation boundary.
* **Why the difference is accepted.** Refusing it would mean one of two things, and both are
  worse. Decrypting two national identifiers and a card verification value into the dataset would
  move all three out of the one place the migration keeps them encrypted and into a flat file with
  no field-level protection, for an artefact whose only in-tree consumer is the counterpart
  import. Not exporting customers and cards at all was the shipped state for one revision and is
  worse than it looks: the five record types share one sequence counter, so a three-type file
  renumbered the records it *did* carry as well as omitting those it did not, while the import
  dispatcher accepted it as structurally complete. The verification value in particular withdraws
  nothing a user could see — `V1__card.sql` records that it appears on none of the three card
  mapsets or their symbolic maps, so the baseline never displayed it and never accepted it as
  input. An operator who needs any of the three takes it from the owning context under that
  context's own disclosure rules. Assumptions: the elision is **structural rather than a filter**.
  The batch projections do not map the enciphered columns at all, so no accessor exists to read
  them through and no decryption path exists to reach them by, which means a later edit cannot
  reintroduce the disclosure by accident — a filter applied at the mapper could not promise that.
* **Where it is verified.** `DatasetJobBodiesTest.theProtectedFieldsAreExportedRedacted` reads the
  three spans out of the encoded bytes at the offsets the copybook declares and asserts each empty
  form separately, so the three storage kinds cannot be conflated;
  `theExportJobWritesAllFiveRecordTypes` asserts the five discriminators the elision made
  emittable; and `theImportJobSeparatesByRecordType` asserts that a customer and a card record
  survive the round trip and that no record reaches the error artefact. Like
  [D-1](#d-1--the-exportimport-record-key-declaration) this difference has **no golden master to
  compare against**, because the baseline pair does not compile under the open-source compiler, so
  the copybook layout is the oracle.
* **Files.**
  `services/batch-service/src/main/java/com/carddemo/batch/domain/Customer.java`,
  `services/batch-service/src/main/java/com/carddemo/batch/domain/Card.java`,
  `services/batch-service/src/main/java/com/carddemo/batch/mapper/ExportRecordMapper.java`,
  `services/batch-service/src/main/java/com/carddemo/batch/job/ExportJob.java`.

#### D-VIEW-READ-WITHOUT-LOCK — the detail read acquires no lock, because nothing rewrites

* **Baseline behaviour.** [`COTRN01C.cbl`](../../app/cbl/COTRN01C.cbl) reads the
  transaction record with the **UPDATE** option at **L275**, which acquires an exclusive
  read-for-update lock. The program contains no `REWRITE` and no `WRITE` anywhere in its
  330 lines, so the lock it takes is never used, and it is held until the task's own
  syncpoint.
* **Target behaviour.** `TransactionViewService` performs a plain read-only lookup with
  no lock mode requested, inside a read-only transaction boundary.
* **Category.** Documented divergence — locking and contention semantics.
* **Why the difference is accepted.** The lock protected nothing: no statement in the
  program modifies the record it locked, so its only observable effect was to exclude
  other readers-for-update and any writer for the duration of a screen read. Removing it
  cannot change what the screen displays — the row read is the same row — and it removes
  a contention point between a detail view and the posting job, which does write the same
  table. The direction of the change is worth stating plainly: this target holds *fewer*
  locks than the baseline, so a concurrent writer that the baseline would have blocked
  now proceeds, and a detail view is therefore a snapshot rather than a pin. That is the
  correct reading for a screen that displays and does not edit. The contexts that DO edit
  hold contention one of two ways, and which one is decided by the copybook rather than by
  preference: where the baseline record carries a before-image comparison the target
  expresses it as a version column, as the *Optimistic concurrency is expressed natively*
  note earlier in this document records; where it does not — the two authorization segments
  derive field for field from copybooks with no version member, so no version column exists
  to compare — the target takes a pessimistic row lock for the duration of the write instead,
  which is what the reference obtains implicitly by retrieving a segment through the
  command-level interface before replacing it. `FraudMarkingService` is that second case.
* **Where it is verified.** `TransactionViewServiceTest` asserts the read goes through the
  plain keyed lookup, that no method the repository declares or inherits carries a lock
  annotation of either family, and that a failed read is reported with this program's own
  capitalised failed-read sentence rather than as a contention refusal.
* **Files.**
  `services/transaction-service/src/main/java/com/carddemo/transaction/service/TransactionViewService.java`.

#### D-REFERENCE-UPSERT-NOT-EXPOSED — the maintenance screen's insert-on-miss has no single operation

* **Baseline behaviour.** Three baseline programs write `TRANSACTION_TYPE` with three
  different behaviours, and the difference is in what each does when the update matches no
  row. The maintenance screen is an **upsert**:
  [`COTRTUPC.cbl`](../../app/app-transaction-type-db2/cbl/COTRTUPC.cbl)
  `9600-WRITE-PROCESSING` at **L1531-L1592** issues the update and, on `SQLCODE +100`,
  performs `9700-INSERT-RECORD` instead of reporting a miss. The list screen's inline edit
  is a **strict update**:
  [`COTRTLIC.cbl`](../../app/app-transaction-type-db2/cbl/COTRTLIC.cbl)
  `9200-UPDATE-RECORD` at **L1837-L1894** issues the same update and, on `SQLCODE +100`,
  reports not found and inserts nothing. The batch driver **soft rejects and continues**:
  [`COBTUPDT.cbl`](../../app/app-transaction-type-db2/cbl/COBTUPDT.cbl)
  `10032-UPDATE-DB` at **L166-L195** routes every failure to `9999-ABEND`, which despite its
  name only displays the message and sets return code 4 at **L230-L233**, leaving the
  sequential read loop at **L93-L96** free to take the next record.
* **Target behaviour.** Two of the three are delivered as their own operation and the third
  is not. `PUT /api/v1/reference/transaction-types/{typeCd}` is the **strict** update: it
  answers 404 on a miss and inserts nothing, which is what the published contract declares by
  carrying 404 among that operation's answers. `POST /api/v1/reference/maintenance-actions` is
  the **soft-reject** run: each action commits in its own transaction and the reply carries one
  outcome per action with the worst condition code seen. There is **no operation whose single
  call is an upsert**; a client reaching for the maintenance screen's behaviour issues the
  create and, if it is refused as a duplicate, the replace.
* **Category.** Documented divergence — operation granularity, not rule content.
* **Why the difference is accepted.** The published contract decided it, and it decided it
  the same way in both directions: the create operation carries 409 among its answers and the
  replace operation carries 404, so a duplicate and a miss are each reportable conditions
  rather than silent branches. An upsert cannot report either — that is precisely what makes
  it an upsert — so exposing one would give this interface a third write whose reply could not
  distinguish a row it created from a row it replaced. Two alternatives were weighed. Adding
  an upsert operation to the contract was rejected because the document is the frozen
  agreement between this service and its clients, and widening it to recover a screen
  behaviour no migrated client performs is a change to the contract rather than to the code.
  Making `PUT` an upsert was rejected outright: it would take the 404 the contract publishes
  and make it unreachable, so a client that mistyped a code would silently create a type
  instead of being told the code does not exist. The cost is stated plainly: a caller
  reproducing the maintenance screen makes two calls where the baseline operator made one,
  and observes an intermediate 409 the baseline never surfaced.
* **Where it is verified.** `ReferenceApiRoutingContractTest` asserts in both directions that
  the delivered handlers and the declared operations are the same set and that the count is
  nineteen, so an upsert added later without a contract entry — or an entry without a handler
  — fails the build. `ReferenceWriteBehaviourTest` asserts that the strict replace reports the
  miss rather than inserting, and that the batch run applies later actions after an earlier one
  is rejected.
* **Files.**
  `services/reference-service/src/main/java/com/carddemo/reference/service/TransactionTypeService.java`,
  `services/reference-service/src/main/java/com/carddemo/reference/service/ReferenceBatchUpdateService.java`,
  `services/reference-service/src/main/java/com/carddemo/reference/api/TransactionTypeController.java`,
  `services/reference-service/src/main/resources/openapi/reference-api.yaml`.

#### D-BILLPAY-AMOUNT-WIDTH-REFUSED — a balance too wide for the amount column is refused, not truncated

* **Baseline behaviour.** The account balance is `ACCT-CURR-BAL PIC S9(10)V99` at **L7** of
  [`app/cpy/CVACT01Y.cpy`](../../app/cpy/CVACT01Y.cpy) — **ten** integer digit positions —
  and the transaction amount it is moved into is `TRAN-AMT PIC S9(09)V99` at **L10** of
  [`app/cpy/CVTRA05Y.cpy`](../../app/cpy/CVTRA05Y.cpy) — **nine**.
  [`app/cbl/COBIL00C.cbl`](../../app/cbl/COBIL00C.cbl) **L224** moves the wider field into
  the narrower one, so a balance needing ten integer digits loses its high-order digit
  there. **L233** then writes that row and **L234** subtracts the FULL, untruncated balance
  from the account, so the two writes of one unit of work disagree: the account is settled
  to zero while the row beside it records an amount smaller than the payment. Nothing
  signals it — a COBOL `MOVE` into a narrower numeric field discards high-order digits
  silently.
* **Target behaviour.** `BillPaymentService` refuses the request before any work is done. The
  bound is published as `LEDGER_AMOUNT_INTEGER_DIGITS`, measured against the balance's
  integer digits alone, and the refusal is the **first** statement of the payment method, so
  it spends no cross-context read and consumes no value from the identifier sequence. The
  failure carries the reference's own sentence for a payment that could not be written,
  `'Unable to Add Bill pay Transaction...'` from **L543**, and is rendered as 500.
* **Category.** Documented divergence — refusal in place of silent truncation.
* **Why the difference is accepted.** Truncating would reproduce the baseline byte for byte
  and would also reproduce a ledger that does not balance, which no downstream reader of the
  migrated schema can detect: `ledger.transactions.tran_amt` is `NUMERIC(11,2)`, so the
  narrower column is a real constraint rather than a display width, and the row it would hold
  is indistinguishable from a correct row for a smaller payment. Widening the column instead
  was rejected because it changes the storage contract transformation rule T1 makes normative
  from the copybook. Reporting the condition was chosen over both, and the sentence is the
  baseline's own rather than a new one, because transformation rule T8 forbids inventing
  user-visible text. **The condition is unreachable on the seed corpus** — no account in
  `app/data/ASCII/acctdata.txt` carries a balance of ten integer digits — so this refusal
  changes no observable outcome for any data the baseline was exercised on.
* **Where it is verified.** `BillPaymentServiceTest` asserts that a ten-integer-digit balance
  raises with that sentence, and arranges **neither** a card resolution **nor** an identifier
  allocation, so Mockito's strict stubbing proves the refusal is reached before either.
* **Files.**
  `services/transaction-service/src/main/java/com/carddemo/transaction/service/BillPaymentService.java`.

#### D-REPORT-SUBMISSION-DEDUPLICATED — a resubmitted report request is folded onto the run it is retrying

* **Baseline behaviour.** [`CORPT00C.cbl`](../../app/cbl/CORPT00C.cbl) submits by writing
  job-control images to the transient data queue defined at **L502** of
  [`app/csd/CARDDEMO.CSD`](../../app/csd/CARDDEMO.CSD), and every submission is **independent**.
  The program holds no submission identity, the queue holds no notion of a duplicate, and the
  internal reader runs whatever it consumes — so an operator pressing Enter twice on the
  confirmed screen produces **two jobs** that read the same range and write the same dataset,
  and neither the program nor the queue reports the second as a repeat. There is nothing to
  reconcile them by: the queue definition carries `ERROROPTION(IGNORE)` at **L501**, so even a
  discarded write is silent.
* **Target behaviour.** `ReportExecutionService.start` names the state-machine execution from the
  report type, both range bounds and a digest of the request's correlation identifier. A second
  request carrying the **same** correlation identifier therefore collides with the first, and the
  collision is answered with **201 and the handle of the run that already exists** rather than
  starting a second execution. A request carrying a **new** correlation identifier — which
  includes any deliberate reprint — is a new submission and starts a new run.
* **Category.** Documented divergence — submission identity added by the replacement transport.
* **Why the difference is accepted.** The replacement transport has a ten-second per-call ceiling,
  so a call this service abandons may still have been accepted, and a caller retrying it would
  otherwise start a second run over the same range — two sets of output objects with nothing to
  say which is current. The baseline had no equivalent exposure because its write was local to the
  region and effectively instantaneous, so the duplicate it could produce required a human pressing
  a key twice rather than a timed-out network call. Folding a retry onto its own run is therefore
  answering a condition the new transport introduces, not removing a behaviour the reference relied
  on. The identifier is the discriminator because it is the only value that distinguishes a retry
  from a reprint: the two carry byte-identical bodies, so no content-derived key can tell them
  apart, and a caller resending the header it already owns is exactly how it states which one it
  means.
* **Alternatives considered.** (a) Keying on the report type and range alone, which was the shipped
  behaviour and is what this entry replaces. Rejected: the orchestrator will not reuse an execution
  name for ninety days, so every reprint of one report over one range was refused for that whole
  period and the refusal reached the caller as an internal failure — the reference could be
  re-driven from its screen as often as an operator liked. (b) A purely random name, which never
  collides. Rejected: it removes the retry protection the ceiling makes necessary. (c) A truncated
  timestamp. Rejected: it protects a retry only inside its own truncation window, so whether a
  caller gets deduplication or a second run depends on where the clock happens to be.
* **Impact.** A caller that retries with its original correlation identifier observes one run
  instead of two. A caller that wants a fresh copy sends a new identifier, which is what a new
  request does by default. Neither reprint nor retry is refused.
* **Where it is verified.** `ReportExecutionServiceTest` asserts that two identifiers name two runs,
  that one identifier names one run, that a caller with no identifier is named freshly each time,
  and that an already-started name answers with the existing handle rather than raising.
* **Files.**
  `services/reporting-service/src/main/java/com/carddemo/reporting/service/ReportExecutionService.java`,
  `services/reporting-service/src/main/resources/openapi/reporting-api.yaml`.

#### D-TRANSACTION-ID-ALLOCATED — the ledger identifier is allocated, not read-then-incremented

* **Baseline behaviour.** Two online programs derive the next ledger identifier the same way.
  [`app/cbl/COTRN02C.cbl`](../../app/cbl/COTRN02C.cbl) moves high values into the key at
  **L444**, starts a browse at **L445**, reads one record BACKWARD at **L446**, ends the
  browse at **L447**, moves the key it landed on into `WS-TRAN-ID-N PIC 9(16)` declared at
  **L57** at **L448**, and adds one at **L449**.
  [`app/cbl/COBIL00C.cbl`](../../app/cbl/COBIL00C.cbl) performs the identical sequence at
  **L212-L217**. Neither program contains a `READNEXT`, so this is a maximum-key read
  followed by an increment and not a cursor. It is safe there because the CICS region
  serialised the two transactions against each other, which made the read and the
  increment indivisible in effect rather than by declaration.
* **Target behaviour.** Both services allocate from `ledger.transaction_id_seq`, created by
  `V2__ledger_transaction_id_allocator.sql` and advanced past the loaded data to
  `coalesce(max, 0) + 1` over identifiers matching `^[0-9]{16}$`. The allocated number is
  rendered to the sixteen characters `TRAN-ID PIC X(16)` declares at **L5** of
  [`app/cpy/CVTRA05Y.cpy`](../../app/cpy/CVTRA05Y.cpy), so leading zeros survive and the
  lexical ordering the browse queries use is unchanged. An empty ledger issues **one**,
  which is what the reference's zero sentinel at **L689** plus the increment at **L449**
  also produces.
* **Category.** Documented divergence — derivation mechanism, with two observable
  consequences.
* **Why the difference is accepted.** The reference's guarantee did not survive the
  platform change, and the target had to restore it a different way. Two Fargate tasks
  behind a load balancer are not serialised, so both read the same maximum, both add one
  and both attempt the same primary key — one caller is refused for a race it did not
  cause and cannot see. Worse, the maximum is not always a NUMBER in the target: the
  interest job composes identifiers from a business-date prefix followed by a six-digit
  suffix ([`app/cbl/CBACT04C.cbl`](../../app/cbl/CBACT04C.cbl) **L474-L480**), and such a
  value sorts ABOVE every sixteen-digit identifier, so once one exists the lexicographic
  maximum is a value the numeric parse rejects and EVERY interactive add and payment fails
  with a read sentence — indefinitely, and for a reason no operator can act on. The two
  observable consequences accepted are these. Identifiers develop **gaps**, because a
  sequence does not return a value a rolled-back turn consumed; nothing in the reference
  tree reads an identifier as a count — the report job orders by processing timestamp and
  card number — so no consumer can observe one. And the **duplicate-key branches remain
  implemented but become unreachable through allocation**, at **L735-L738** of
  `COTRN02C.cbl` and **L533-L534** of `COBIL00C.cbl`, which narrows when an existing answer
  is given rather than changing the answer. Alternatives Considered: a serialisable
  transaction around the read and the write, which would restore indivisibility without a
  sequence. Rejected because it converts every concurrent add into a serialisation failure
  the caller must retry — the same refusal the sequence removes — and it does nothing about
  the non-numeric maximum. Alternatives Considered: filtering the maximum read to all-digit
  identifiers so the interest job's format is skipped. Rejected as a half-measure: it
  addresses the format and leaves the race.
* **Where it is verified.** `TransactionAddServiceTest` asserts a fresh ledger yields the
  first identifier at the declared width, that a populated ledger keys the row by the
  allocated value and performs **no** maximum-key read, that a failed allocation reports
  the reference's own upper-case failed-read sentence, and that an allocation outside the
  key domain is refused rather than rendered. `BillPaymentServiceTest` asserts the same
  four properties for the payment path, including the absence of the maximum-key read.
  `TransactionAddServiceTest`'s copy-path case asserts exactly one probe read and exactly
  one allocation, so the copy read cannot begin consuming identifiers.
* **Files.**
  `services/transaction-service/src/main/java/com/carddemo/transaction/service/TransactionAddService.java`,
  `services/transaction-service/src/main/java/com/carddemo/transaction/service/BillPaymentService.java`,
  `services/transaction-service/src/main/resources/db/migration/V2__ledger_transaction_id_allocator.sql`.

#### D-CAPTURE-KEY-AT-LEAST-ONE — a correction: the capture keys are alternatives, not mutually exclusive ones

* **Baseline behaviour.** [`app/cbl/COTRN02C.cbl`](../../app/cbl/COTRN02C.cbl) **L195**
  opens an `EVALUATE TRUE` over the two key fields, so the FIRST arm whose condition holds
  is the only arm that runs. The account arm is first: **L196** takes it when the account
  identifier is not blank, **L197** requires it numeric, **L208** reads the cross-reference
  by account and **L209** moves the card number it found into the card field. **L210**
  takes the card arm only when no account identifier arrived, and **L224-L229** report
  `'Account or Card Number must be entered...'` only when NEITHER arrived. A submission
  carrying BOTH keys therefore runs the account arm, has its submitted card number
  overwritten at **L209**, and succeeds with **no message at all**.
* **Target behaviour.** `TransactionAddRequest` requires **at least one** key through its
  class-level `AtLeastOneKey` constraint, and `openapi/transaction-api.yaml` publishes the
  pair as `anyOf` with neither branch forbidding the other. `TransactionAddService`
  resolves account-first and discards a card number supplied alongside an account
  identifier, silently, exactly as **L209** does.
* **Category.** Not a divergence — a **correction** to one. The delivered code carried an
  unregistered divergence that is now removed, and this entry records it so a reader
  comparing the two revisions does not read the removal as a regression.
* **What was wrong.** The request record required EXCLUSIVE disjunction, reasoning from
  **L209** and **L223** that supplying both keys was a contradiction because one value
  would be overwritten. The observation was correct and the conclusion was not: the
  reference resolves the contradiction in the account's favour rather than refusing it. The
  boundary therefore refused a submission the reference accepts, and it made the
  account-first precedence transcribed in `TransactionAddService.validateInputKeyFields` —
  which documents the silent discard as preserved behaviour — unreachable through the
  published API for the one case that precedence exists to decide. A parity branch no
  request can reach is not parity. The refusal could not even have been worded truthfully:
  the only sentence the reference emits on this rule names a condition, *neither key
  present*, that a both-keys submission plainly does not meet.
* **Where it is verified.** `TransactionControllerTest.aCaptureCarryingBothKeysReachesTheService`
  posts both keys through the real validation pipeline and asserts **201**;
  `aCaptureCarryingNeitherKeyIsRefused` posts neither and asserts **400** with the per-field
  array naming `accountId` and `cardNumber` and carrying the reference sentence.
  `TransactionApiContractTest.theKeyRuleIsAtLeastOneOnBothSides` asserts the published
  construct is `anyOf`, that neither branch carries a `not` clause, and that the Java side
  declares the same rule. `TransactionAddServiceTest`'s precedence case — which passed
  unchanged while the boundary refused every such request, which is why the boundary
  assertion belongs at the wire — asserts the account direction wins and replaces the
  submitted card.
* **Files.**
  `services/transaction-service/src/main/java/com/carddemo/transaction/dto/TransactionAddRequest.java`,
  `services/transaction-service/src/main/resources/openapi/transaction-api.yaml`.

#### `C-ROUNDING` — the interest accrual rounds half up where the baseline discards the surplus digits

* **Baseline behaviour.** [`CBACT04C.cbl`](../../app/cbl/CBACT04C.cbl) computes the monthly
  accrual at **L464-L465**, `COMPUTE WS-MONTHLY-INT = ( TRAN-CAT-BAL * DIS-INT-RATE) / 1200`,
  and stores the quotient into `05 WS-MONTHLY-INT PIC S9(09)V99` declared at **L168**. The
  statement carries **no `ROUNDED` phrase**, and neither does any other statement in the
  program — a search for that phrase across all 652 lines returns no match — so the store
  discards the surplus fraction digits rather than rounding them, which is truncation toward
  zero.
* **Target behaviour.** `Money.monthlyInterest(rate)` forms the product at full precision and
  reduces the quotient once, at the division, with `Money.GENERAL_ROUNDING` — `HALF_UP`. The
  type declares that ONE mode and exposes no way to select another, so the accrual, general
  multiplication, general division and the reduction of a supplied amount all reduce
  identically.
* **The difference.** One cent, and only on a quotient landing exactly on a half cent. A
  category balance of `1000.80` at a rate of `2.50` forms the scale-4 product `2502.0000`,
  whose quotient is `2.0850` exactly: the baseline stores `2.08` and the target returns
  `2.09`. Away from the half the two agree — `1000.00` at `2.50` gives `2.08333...` and both
  return `2.08` — so the difference is unreachable on every vector the shipped interest
  fixtures carry, all of which drive `1000.00` at `15.00` for an exact `12.5000`.
* **Why the difference is accepted rather than removed.** Transformation rule T3 states the
  money path as an exact decimal at scale 2 with `RoundingMode.HALF_UP` in Java and states no
  exception for any operation; rule T4 constrains the accrual's operand ORDER and leaves its
  mode to T3, naming only "an explicit scale and rounding mode". The plan is frozen, and it
  admits a behavioural difference from the reference when the difference is registered — which
  is what this entry does — while admitting a departure from a transformation rule only where
  the plan states an exception. Reducing the accrual by discarding digits would satisfy parity
  by breaking the rule; reducing it half up satisfies the rule and registers the parity
  difference, which is the order the plan sets.
* Trade-offs: the cent does not stay local, and the register states so rather than
  understating the cost: **L467** adds each already-reduced term into the account total and
  **L352** adds that total to the account balance once per account, so a cent gained on a
  transaction category reaches the balance that the next over-limit comparison is made
  against, and that comparison is inclusive (`app/cbl/CBTRN02C.cbl` **L407**). An operator
  reconciling a migrated balance against a baseline balance should expect a difference of at
  most one cent per accrued category per accrual run, in the target's favour.
* Alternatives Considered: a second rounding constant fixed at `DOWN` and applied to the
  accrual alone, which is what the code carried for a time and which reduced this difference to
  nothing. Rejected on precedence: it put a parity argument above a frozen transformation rule,
  and it left the type with two money contracts a reader had to keep apart. Alternatives
  Considered: a rounding-mode parameter on the accrual entry point so a parity caller could ask
  for truncation. Rejected because two call sites computing the same accrual could then
  disagree by a cent with nothing in either one signalling that they had chosen differently.
  Alternatives Considered: amending the plan to admit truncation. Rejected outright — the plan
  is the agreed contract and is not editable from inside the migration.
* **Where it is verified.** `MoneyTest` asserts both discriminating vectors against the API,
  against an independently computed half-up counterfactual, and against the baseline's
  truncating arithmetic which must DIFFER — so a silent revert to truncation and a silent
  disappearance of this difference both fail. The negative side is asserted with two vectors
  because no single negative input separates half up from both truncation and flooring. The
  exact-quotient claim is asserted with `RoundingMode.UNNECESSARY`, so it throws rather than
  passing if a future edit makes the vector inexact.
  `InterestCalculationServiceTest.theNamedModeIsTheAppliedMode` and the sibling case in
  `BatchServicesTest` hold `InterestCalculationService.ACCRUAL_ROUNDING` and
  `Money.GENERAL_ROUNDING` to the same value, so the constant that names the mode beside the
  accrual cannot drift from the mode the kernel applies. The fixture-driven golden comparison
  in `CalculateInterestJobTest` is unaffected, which is itself evidence for the reachability
  claim above.
* **Files.** `services/common-lib/src/main/java/com/carddemo/common/money/Money.java`,
  `services/common-lib/src/main/java/com/carddemo/common/money/package-info.java`,
  `services/common-lib/src/test/java/com/carddemo/common/money/MoneyTest.java`,
  `services/batch-service/src/main/java/com/carddemo/batch/service/InterestCalculationService.java`,
  `services/batch-service/src/main/java/com/carddemo/batch/job/CalculateInterestJob.java`,
  `services/batch-service/src/main/java/com/carddemo/batch/dto/InterestRateLookup.java`.

<sub>Apache-2.0 · Authoritative artifact-to-target matrix, retirement register and
behavioural-divergence register for CardDemo. The baseline under `app/**` is cited
throughout and never modified. Convention:
[`docs/CODE_DOCUMENTATION_STANDARD.md`](../CODE_DOCUMENTATION_STANDARD.md).</sub>

#### D-TRAN-PAD-PROVENANCE — a re-emitted record's padding is recovered from its own attribution

* **Baseline behaviour.** The padding bytes of a fixed-width record are a property of the record
  area the writing program filled, and the two producers of the 350-byte transaction record fill
  theirs differently. [`CBACT04C.cbl`](../../app/cbl/CBACT04C.cbl) **L485-L489** builds
  `TRAN-DESC` with `STRING ... DELIMITED BY SIZE`, which writes only the twenty-four characters it
  was handed and leaves the remaining seventy-six bytes at the low values the record area held,
  while [`CBTRN02C.cbl`](../../app/cbl/CBTRN02C.cbl) **L429** performs a plain `MOVE` of a feed
  field that is already blank-padded. Neither program names the trailing `FILLER` at all, so it
  keeps low values in both. The reference never has to RECOGNISE which producer wrote a record,
  because the two write to two different datasets: the posting step to the transaction master and
  the accrual step to `SYSTRAN`, the generation [`INTCALC.jcl`](../../app/jcl/INTCALC.jcl)
  **L37-L41** allocates. The backup step copies one of those datasets as a byte image, and the
  combine step concatenates both, so each record's bytes travel unchanged.
* **Target behaviour.** Both producers commit to `ledger.transactions`, which stores a
  description's TEXT and not the bytes behind it, so the backup and combine states re-render each
  record rather than copying bytes. They therefore choose the pad per row:
  `InterestCalculationService.isAccrualGenerated` reports whether a row carries BOTH marks the
  accrual pass writes — the `System` source of **L484** and the `Int. for a/c ` description prefix
  of **L485** — and the two jobs name `TransactionRecordMapper.Layout.INTEREST_GENERATED` for such
  a row and `POSTED_MASTER` for every other. The mapper then writes that layout's measured
  description pad, and the trailing pad as low values under both.
* **Category.** Documented divergence — mechanism, byte-for-byte output preserved.
* **Why the difference is accepted.** There is no third option that keeps the bytes. Storing the
  pad would mean a column on a table `transaction-service` owns, carrying no information the record
  does not already carry; and leaving the codec's blank in place was measured as a twenty-byte
  difference per posted record and a ninety-six-byte difference per accrual record against the
  committed expectations, on spans that carry no data — so every field matched and the comparison
  failed anyway. Alternatives Considered: recognising the producer from the description text alone,
  rejected because a description is free text a feed record may hold anything in; and requiring only
  the source label, rejected because the conjunction means a row must match BOTH marks to be
  misclassified. Trade-offs: the residual difference is that a POSTED row that carried the `System`
  source and a description beginning `Int. for a/c ` would be padded as an accrual row. No feed
  record in the corpus does — the two source values in
  [`dailytran.txt`](../../app/data/ASCII/dailytran.txt) are `OPERATOR` and `POS TERM` — and the
  cost of the alternative is a wrong pad on every accrual row rather than on a hypothetical one.
* **What is preserved.** The emitted bytes. The staged interest generation now equals
  `tests/golden/interest/*/transact.expected` with only the two run-generated timestamp spans
  masked, including the seventy-six low values behind the description and the twenty in the
  trailing pad; the posted image equals `tests/golden/posting/*/tranfile.expected` in the same
  way. The category-balance record follows the same rule from the other direction: its plain encode
  writes the low values the create arm leaves, measured in
  `tests/golden/posting/zero_balance/tcatbal.expected`, and its source-image overload reproduces the
  ASCII zeros the update arm writes back.
* **Where it is verified.** `CalculateInterestJobTest` compares the staged generation against all
  three committed interest expectations byte for byte, with only the two stamps masked, and a guard
  asserts that every low value in each committed record lies inside one of the two pad regions — so
  a third region would fail rather than pass silently. `BackupTransactionsJobTest` and
  `CombineTransactionsJobTest` each assert the two row classes SIDE BY SIDE in one artefact, the
  accrual tail low-valued and the posting tail blank, with both trailing pads low-valued.
  `TransactionRecordMapperTest` asserts the plain encode now reproduces the committed parity record
  including its pad, and that the layout argument reaches exactly one span.
  `FixedWidthCodecTest` asserts the shared codec drops an inert low-value pad and keeps a printable
  one, which is what keeps the mapper package's "filler is dropped on decode" contract true for these
  records.
* **Files.** `services/batch-service/src/main/java/com/carddemo/batch/mapper/TransactionRecordMapper.java`,
  `services/batch-service/src/main/java/com/carddemo/batch/mapper/TransactionCategoryBalanceRecordMapper.java`,
  `services/batch-service/src/main/java/com/carddemo/batch/service/InterestCalculationService.java`,
  `services/batch-service/src/main/java/com/carddemo/batch/job/BackupTransactionsJob.java`,
  `services/batch-service/src/main/java/com/carddemo/batch/job/CombineTransactionsJob.java`,
  `services/common-lib/src/main/java/com/carddemo/common/codec/FixedWidthCodec.java`.

#### D-INTEREST-ROW-DISPLAY-WITHHELD — the per-row observation is kept, its record image is not

* **Baseline behaviour.** [`CBACT04C.cbl`](../../app/cbl/CBACT04C.cbl) **L193** executes
  `DISPLAY TRAN-CAT-BAL-RECORD` inside the read loop, on the line immediately after **L192**
  increments `WS-RECORD-COUNT`, so the program writes **one line per input category balance** to
  `SYSOUT`, in the key order the indexed read returns, and the line is the record's fifty bytes
  verbatim. [`CVTRA01Y.cpy`](../../app/cpy/CVTRA01Y.cpy) **L4-L10** lays those bytes out as
  `TRANCAT-ACCT-ID PIC 9(11)`, `TRANCAT-TYPE-CD PIC X(02)`, `TRANCAT-CD PIC 9(04)`,
  `TRAN-CAT-BAL PIC S9(09)V99` and a twenty-two-byte `FILLER` — so each displayed line carries an
  account identifier and a money balance in the clear. The program displays no counter pair and
  declares no reject stream; the per-row lines and the two framing banners at **L181** and **L230**
  are the whole of its operator output.
* **Target behaviour.** `CalculateInterestJob.accrueOneRow` emits one structured event per input
  row, `event=batch.interest.row-read`, at the same point in the walk — immediately after the row is
  counted, before the control break is evaluated — so the cardinality and the ordering of **L193**
  are reproduced exactly. What the event CARRIES is the row's disclosable identity only: its ordinal
  in this pass's ordered read, its transaction type code and its category code. The account
  identifier and the balance are omitted, and no abbreviation or digest stands in for either.
* **Category.** Documented divergence — observable operator output narrowed by a disclosure rule.
* **Why the difference is accepted.** The omission is not a choice made at this statement.
  [`observability.md`](observability.md) states the rule in three parts and the first is that **a
  prohibited value is OMITTED, not abbreviated**, naming the account identifier and every monetary
  amount, "not its content, not its length, and not a digest of it"; the third part names what may
  remain, and "a type or category code" is on that list. A displayed
  `TRAN-CAT-BAL-RECORD` holds one value from each of those two sets, so the record image cannot
  travel and the codes can. Alternatives Considered: emitting the fifty bytes at a lower log level,
  rejected because the rule governs what a retained line HOLDS rather than which level wrote it —
  every level lands in the same log group under the same retention, so a debug-level dump is the
  same disclosure with a smaller audience. Alternatives Considered: rendering the account through
  `OpaqueIdentifier`, which is the sanctioned control for a queue group identity; rejected because
  the same page refuses a digest of an account identifier explicitly, and an eleven-digit domain is
  small enough to enumerate against a keyed token an operator can also read. Trade-offs: a reader
  can no longer name the account a given line describes without re-reading the input in the same
  order, which the pass's deterministic key order makes reproducible; and the balance, which the
  reference put on the line for an operator inspecting a tape, is now read from the input or from
  `ledger.transaction_category_balances` instead.
* Refactoring Rationale: the migrated job previously emitted NO per-row line at all and reported
  only the closing total, on the argument that the count was the parity-bearing part of **L193**.
  That was wrong in one direction and right in the other: the disclosure rule does bar the record
  image, but it bars only two of the record's four data fields, and dropping the statement entirely
  gave up the two properties a total cannot carry — that each row was visited exactly once, and that
  the walk read in key order, which is what makes the control break at **L195** correct. The event
  was added and this entry registers what it withholds, rather than the statement staying absent and
  the difference staying unregistered.
* **What is preserved.** One observation per input row, in read order, at the same position in the
  loop, plus the two framing banners verbatim and the closing total. What is not preserved is the
  content of the line: four fields become three, and neither omitted field is represented.
* **Where it is verified.** `CalculateInterestJobTest` filters the emitted events to the per-row
  ones, asserts there is exactly one per staged input row, asserts their ORDER by reading the
  ordinal, type code and category code out of each in turn, and asserts that the account identifier
  at its full declared width and the staged balance appear in NO emitted line — so a regression that
  restored either value fails rather than passing as extra detail.
* **Files.** `services/batch-service/src/main/java/com/carddemo/batch/job/CalculateInterestJob.java`.

#### D-PREFLIGHT-LOOKUP-PAST-END-OF-FILE — the reference inspects one record it did not read

* **Baseline behaviour.** [`CBTRN01C.cbl`](../../app/cbl/CBTRN01C.cbl) **L164**-**L186** guards too
  little. The outer test at **L165** opens a block closing at **L185**, but the inner test at
  **L167** closes with its own `END-IF` at **L169** and so guards **only** the record display at
  **L168**. The two `MOVE` statements at **L170**-**L171** and the cross-reference lookup at
  **L172** sit outside that inner guard. On the iteration whose read at **L166** reaches end of file
  and sets the terminating flag, the program therefore moves a card number out of a record it did
  not read and performs one further cross-reference lookup against the stale record buffer — and,
  when that lookup resolves, one further account read at **L176**. A run over N records performs
  N+1 lookup pairs. When the last real record happened to be unresolvable, the stale card is looked
  up a second time and the diagnostic at **L181**-**L183** is emitted twice for one record.
* **Target behaviour.** `PreflightDailyTransactionsJob` drives its inspection from the batch the
  keyset query returned, which makes an iteration with no record unrepresentable. A run over N
  records performs exactly N lookup pairs and emits no trailing diagnostic.
* **Category.** Documented divergence — a reference defect of guard placement, not reproduced.
* **Why the difference is accepted.** The cost is bounded to counters and never reaches output data:
  the extra pair produced no record and changed no file, its only visible effect being one
  duplicated diagnostic line. What makes the divergence safe rather than merely defensible is that
  **no byte comparison can observe it** — `tests/golden/` holds no `prepost` or `preflight` tree, so
  there is no golden master for `CBTRN01C` for a read count to be compared against, and the program
  writes nothing and sets no `RETURN-CODE` at all. Reproducing the extra lookup would have preserved
  a read count nothing measures at the price of shipping a known defect.
* Refactoring Rationale: this entry exists because the divergence was previously **claimed to be
  registered and was not**. `PreflightDailyTransactionsJob`'s class documentation and
  `PreflightDailyTransactionsJobTest` both stated it was "registered as D-7 in" this document, but
  §7.3's [D-7](#d-7--the-header-clock-and-the-zone-it-is-read-in) is the online header clock,
  belonging to a different program — so the citation resolved to the wrong entry and the preflight
  behaviour appeared in no entry at all. Both citations now name this identifier. The numeric label
  **D-7** remains correct where it is used, as the class-local numbering inside that job's own
  documentation, exactly as `ImportJob` numbers its own **D-8** and **D-9**; the collision was
  between a class-local number and a register heading, which is why the register-side identifier is
  a name rather than a number.
* **What is preserved.** Every diagnostic the reference emits for a record it genuinely read, with
  its message text, and the order in which records are inspected. What is not preserved is the
  trailing lookup pair and the duplicate diagnostic it could produce.
* **Where it is verified.** `PreflightDailyTransactionsJobTest` seeds three records and asserts three
  lookups rather than four, and asserts that no trailing diagnostic follows the last real record.
* **Files.** `services/batch-service/src/main/java/com/carddemo/batch/job/PreflightDailyTransactionsJob.java`.

### 7.5 Withdrawn divergence identifiers

ONE identifier has been withdrawn, because the behaviour it registered was never built. It is
recorded here, outside §7.4, so that the register above contains only live differences while
the identifier still resolves to something for a reader who meets it in an older comment or
commit message. Assumptions: the heading level and the placement are both deliberate — a
withdrawn identifier kept as a `####` entry inside §7.4 would be counted by that section's own
measured heading count as though it registered a live difference, which is the one thing it
must not be read as.

Refactoring Rationale: this subsection held TWO entries and now holds one. `C-ROUNDING`, the
interest-accrual rounding difference, was withdrawn here on the reading that the accrual should
reduce as the baseline does; that disposition is reversed and the entry is LIVE again in §7.4,
because transformation rule T3 states half up for the money path with no exception for the
accrual and the plan is frozen. A reader arriving from a comment that describes `C-ROUNDING` as
withdrawn is reading a citation from that intervening period; the live entry in §7.4 is
authoritative, and it records the reversal in its own reasoning rather than leaving the two
readings to be reconciled from the outside.

#### `D-REJECT-109-DURABLE` — the durable reason-109 reject row was never built

This identifier is withdrawn because the behaviour it registered **does not exist and was
never implemented**, which is a different failure from a difference that was registered and
then reversed: this one described a design that no code ever carried. The live difference at that paragraph is registered as
[`D-POSTING-ATOMIC-NO-REJECT-109`](#d-posting-atomic-no-reject-109--a-failed-account-rewrite-discards-the-whole-post-and-reason-109-still-reaches-no-stream),
which states the atomicity that IS shipped and states plainly that no reject row is written.

* **What it claimed.** That a failed account rewrite both discarded the partial post AND
  wrote one row to `ledger.transaction_rejects` under reason code **109** with the
  description `ACCOUNT RECORD NOT FOUND`, from outside the rolled-back unit of work so the
  rollback could not take it away.
* **What is actually shipped.** The first half only. `PostTransactionsJob` owns one transaction
  boundary PER FEED RECORD and wraps the account write in no handler, so the failure propagates
  and that record's three writes roll back together; there is no second boundary and no writer
  outside it. Reason 109 is written nowhere:
  `RejectReason.isPersistedToRejectStream()` returns `false` for
  `ACCOUNT_NOT_FOUND_ON_REWRITE` alone, `PostingValidationResult` refuses to accept it, and
  `TransactionRejectRecordMapper` declares the persisted domain as {100, 101, 102, 103}. A
  search of every main source for `ACCOUNT_NOT_FOUND_ON_REWRITE` returns the constant, that
  predicate and that refusal, and nothing that persists anything.
* **How the claim survived.** The one test that appeared to demonstrate the row —
  `TransactionRejectRepositoryIT.aFailingAccountRewriteIsRecordedAsADurableReasonOneHundredAndNineRow`
  — constructed and saved the row itself and then asserted it was there. It never invoked the
  posting job, the validation rule or any producer, so it could not have failed if no
  producer existed, which is exactly what happened. That test is deleted rather than
  repaired: what it could honestly assert is that the reason-code COLUMN admits the value,
  and the case beside it already asserts precisely that, under a name that says so.
* **What was still carrying the claim, and is not any more.** This record and the case that
  survived the deletion both described the withdrawal in the past tense before the artifacts
  matched it. Three things did not: the test named above was still present and still running;
  and section 7.4 -- whose subject is divergences CLAIMED BY SHIPPED CODE -- still carried a
  full live entry under this identifier, once in its own body and once again in the
  continuation appended after *Related documents*, the two byte-identical to each other and
  both citing the deleted test as where the behaviour was verified. All three are now gone,
  and section 7.4's four heading counts were re-measured rather than decremented, which that
  section's own count paragraph records as its eighth pass. Refactoring Rationale: prose that
  describes a deletion is not a deletion, and the gap between the two is invisible to every
  gate this repository runs -- the test compiled, passed and proved nothing, and the stale
  entry read as authoritative to anyone who reached section 7.4 before reaching this one.
  Assumptions: the entry removed is not replaced, because the difference that IS shipped at
  that paragraph already has its own entry,
  [`D-POSTING-ATOMIC-NO-REJECT-109`](#d-posting-atomic-no-reject-109--a-failed-account-rewrite-discards-the-whole-post-and-reason-109-still-reaches-no-stream),
  and a second entry beside it would recreate exactly the contradiction being removed.
* **Why it is withdrawn rather than implemented.** The three grounds are recorded in full in
  the live entry: a second transaction boundary would qualify the atomicity that the same
  file exists to guarantee; a row the baseline's stream does not carry would break the
  byte-for-byte reject-stream comparison [§9.1](#91-the-comparison-contract) depends on; and
  the failure is already observable as a failed batch state with the task's own logged
  exception, so nothing is lost by not tabling it. Assumptions: this is a case where honouring
  the plan's parity requirement and declining an apparent improvement point the same way,
  which is why the decision needed no trade-off between them.
* **Files.**
  `services/batch-service/src/main/java/com/carddemo/batch/dto/RejectReason.java`,
  `services/batch-service/src/main/java/com/carddemo/batch/job/PostTransactionsJob.java`,
  `services/transaction-service/src/test/java/com/carddemo/transaction/repository/TransactionRejectRepositoryIT.java`,
  `services/transaction-service/src/test/resources/fixtures/reject_109_rewrite_invalid_key/README.md`.

## 8. Inventory caveats a reader will otherwise contradict

The inventory in [§1](#1-the-verified-baseline-inventory) counts repository
artifacts. It does not claim that every `COPY` statement resolves from the
repository, that every catalogued access path is a base dataset, or that every
member of a scheduler listing belongs in the one curated target state machine.
Those distinctions are load-bearing: changing any one of them changes a plausible
count without changing the repository.

### 8.1 Repository copybooks are not the whole compiler include path

[`COPAUA0C.cbl`](../../app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl) includes
`CMQPMOV` at **L167** and `CMQGMOV` at **L170**. Neither member exists under
[`app/cpy`](../../app/cpy), in an extension copybook directory or anywhere else in
this repository; both are vendor-supplied MQ copybooks resolved from the platform's
compiler library.

Assumptions: the figures **30** under `app/cpy` and **62** repository-wide are
therefore counts of copybook *files held by this repository*, not counts of every
include a compiler needs in order to build every program. Treating the two vendor
members as omissions from the 62 would mix a source-repository inventory with an
installed-toolchain inventory. Conversely, adding them to 62 would make the result
depend on which MQ distribution happened to be installed. The target does not
transcribe those option structures into application-owned classes;
[`messaging-contracts.md`](messaging-contracts.md) owns the field-by-field message
contract and the transport-client configuration that replaces them.

### 8.2 Ten persistent base clusters and three alternate indexes

The persistent VSAM population is **ten base KSDS clusters plus three alternate
indexes**:

| Population | Members | Target disposition |
|---|---|---|
| 10 base clusters | `ACCTDATA`, `CARDDATA`, `CARDXREF`, `CUSTDATA`, `TRANSACT`, `USRSEC`, `DISCGRP`, `TRANTYPE`, `TRANCATG`, `TCATBALF` | Tables in the eight canonical schemas, mapped field by field in [`data-model-and-schema-mapping.md`](data-model-and-schema-mapping.md) |
| 3 alternate indexes | `CARDAIX`, `CXACAIX`, `TRANSACT.VSAM.AIX` | `idx_cards_account_id`, `idx_card_xref_account_id`, `idx_transactions_proc_ts` |

A list of **eight** is answering a different question: the base CICS definition has
eight `DEFINE FILE` resources, but two of those are `CARDAIX` and `CXACAIX`
*paths*, leaving six base clusters. A repository grep can instead return
**eleven** KSDS names because the statement job creates a `TRXFL` work cluster,
loads it with the transaction master in card order, reads it, and discards it. That
work cluster is a non-persistent projection, not an eleventh master. The three
figures are not alternatives:

```text
  8  CICS FILE resources       = 6 base clusters + 2 AIX paths
 10  persistent base clusters  = the datastore population
 11  KSDS names found in JCL   = 10 persistent + 1 statement work cluster
```

Assumptions: the word *cluster* in the ten-count means a persistent base dataset.
It excludes both catalogued alternate-index objects and the statement work cluster.
[`context-and-container-diagrams.md`](context-and-container-diagrams.md) owns the
current-state picture and the reproducible count commands; the present table owns
the disposition.

### 8.3 `samples/**` is reference material, not migration scope

[`samples`](../../samples) contains **15** artifacts: 9 JCL examples, 4 procedure
examples and 2 packaged runtime archives. The tree is **REFERENCE** material. It is
not included in the 38-member base JCL count, its jobs and procedures do not create
extra matrix rows, and its packaged runtimes are not target build inputs. Every file
under `samples/**` is preserved exactly as supplied.

Trade-offs: counting the nine sample JCL members in the repository-wide JCL total
is useful — it explains how 38 base members plus 8 extension members plus 9 samples
produce 55 repository files — while treating them as migration targets would be
wrong. The accepted distinction is therefore *counted for reconciliation, excluded
from ownership scope*. That lets a repository-wide search close arithmetically
without turning examples into deployables.

### 8.4 Scheduler membership does not imply membership in the curated state machine

[`batch-orchestration.md`](batch-orchestration.md) consolidates the write-path work
into one state machine and explicitly defers the work outside that consolidation to
this register. Every deferred job has a disposition:

| Deferred scheduler work | Why it is not a state of the curated machine | Disposition |
|---|---|---|
| `READACCT`, `READCARD`, `READCUST`, `READXREF` | Read-only unloads, not write-path transitions | Service-owned read/export operations and migration-verification inputs for the account and card data they expose |
| `TRANTYPE`, `TRANCATG`, `TCATBALF` | Dataset refresh/load utilities | Idempotent data-load operations for `reference.transaction_types`, `reference.transaction_categories` and `ledger.transaction_category_balances` |
| `DISCGRP` | Disclosure-group dataset refresh | Idempotent load of `reference.disclosure_groups`, including the required `DEFAULT` rows |
| `CBPAUP0J` | Authorization-extension maintenance | `authorization-service` `PurgeJob`, mapped from `CBPAUP0C` in [§2.7](#27-authorization-service) |
| `MNTTRDB2` | Transaction-type maintenance | `reference-service` transaction-type maintenance, mapped from `COBTUPDT` in [§2.5](#25-reference-service) |
| `TRANEXTR` | Db2-to-flat reference extraction | Reference-data export/migration interface for transaction types and categories; it is not part of the posting unit of work |
| `TXT2PDF1` | The utility itself has no target | The no-target retirement and both consequences are recorded in [§5.2](#52-retired-with-no-target-at-all--exactly-two) |

Assumptions: the scheduler artifacts prove that these jobs were connected to other
jobs; they do not define the target's service boundaries. Grouping all of them into
one machine merely because one scheduler listing contains them would merge read-only
exports, reference maintenance, authorization retention and statement rendering
into the posting transaction. The owning data and behaviour determine the
disposition instead.


## 9. The parity oracle and why warn is green

The existing three-layer suite documented by
[`tests/README.md`](../../tests/README.md) is the **functional-parity oracle**. It is
REFERENCE material for this migration: its fixtures, golden masters, dependency
pins, runner semantics and expected aggregate result are not rewritten or re-pinned
to make a target implementation pass. Target-side Java, TypeScript and Python tests
are strictly additive.

### 9.1 The comparison contract

The parity protocol has four explicit steps:

1. run the COBOL pipeline over the controlled fixtures and retain its golden outputs;
2. run the equivalent target job over the same logical data after migration;
3. apply the **same timestamp normalisation** to both sides; and
4. compare the reject stream, posted transaction records, updated master records and
   return code.

The comparison is intentionally wider than a final row count. A job can preserve the
number of rows while changing which transactions posted, which rejects were
emitted, which account balances moved or which condition code was returned. Those
are observable business outcomes, so all four surfaces belong to the oracle.
[`batch-orchestration.md`](batch-orchestration.md) owns the state-by-state use of the
comparison, and [`observability.md`](observability.md) owns how parity results appear
in logs, metrics and CI reporting.

Trade-offs: golden masters are retained rather than regenerated from the target.
Regenerating them from target output would make the implementation define its own
expected result and would silently bless a divergence. The accepted cost is that an
intentional difference must be registered here and asserted explicitly rather than
made invisible by replacing the oracle.

This is the comparison **contract**. It is not a claim that an end-to-end comparison
has run against a provisioned target environment; the deployment boundary remains
the one stated in [§11](#11-deployment-and-reversibility-boundaries).

### 9.2 The return-code rubric

[`tests/README.md`](../../tests/README.md) §8 defines the suite's graded return codes,
and [`.github/workflows/tests.yml`](../../.github/workflows/tests.yml) **L28–L38**
defines their CI interpretation:

| Aggregate RC | Meaning | CI interpretation |
|---:|---|---|
| **0** | Pass | Green |
| **4** | Warn / soft reject | **Green — non-blocking warn** |
| **≥8** | Failure | Red |
| **16** | Fatal / unrecoverable | Red |

The runner aggregates the **highest** code observed. RC 2 is a usage error that
aborts immediately and does not enter aggregation, so it is not a fifth result
grade.

**Warn is green.** The documented aggregate of **4** is the expected non-blocking
state because [D-1](#d-1--the-exportimport-record-key-declaration)
prevents `CBEXPORT` and `CBIMPORT` from compiling under the open-source compiler:
ten of the twelve batch programs build, the export/import integration test is
skipped with that reason, and the suite reports the condition rather than hiding it.
The workflow deliberately lets `rc <= 4` succeed and makes `rc >= 8` fail.

Assumptions: that warn is a property of the immutable baseline and **must not be
read as a regression introduced by the migration**. A new target-side failure still
raises the aggregate to at least 8 and fails CI; the known 4 neither masks nor
downgrades it. [`observability.md`](observability.md) carries the same interpretation
so that dashboard colour, workflow colour and this traceability register cannot
disagree.


## 10. Out of scope, including the baseline's own stated future work

This section records absences, not deliverables. None of the following has a target
artifact in the matrix, none is implied by a sibling diagram, and none should be
inferred from the presence of a related managed service:

| Out of scope | Boundary recorded by this matrix |
|---|---|
| Multi-region topology and disaster recovery | The target design is single-region; no cross-region data plane, failover path or recovery objective is delivered |
| Blue-green and canary deployment | No parallel production colour, weighted cutover or canary analysis is delivered |
| Kafka and Kinesis | The baseline's asynchronous requirement is request/reply, and [`messaging-contracts.md`](messaging-contracts.md) maps it to queues; no streaming platform is added |
| Redis and ElastiCache | The baseline has no application cache tier and parity does not require one |
| Read replicas | Reporting reads use read-only cross-schema views against the writer; no replica topology is delivered |
| Db2 rewards extension | Listed in the root [`README.md`](../../README.md) **L381–L384** as roadmap material, not present as an implemented source program or a migration target |
| IMS DC implementation | Listed in `README.md` **L384** as roadmap material, not part of the supplied authorization IMS database extension |
| SFTP integration | Listed in `README.md` **L386–L389** as roadmap material; the existing FTP-to-JES tunnel is separately accounted for as a retired mechanism in [§5.3](#53-also-retired-as-mechanisms-with-no-cloud-analogue) |
| Exposure of transactions for distributed application integration | Listed in `README.md` **L389** as roadmap material; it is not the same thing as eliminating the internal two-resource commit in [D-6](#d-6--the-distributed-commit-is-eliminated-not-emulated) |
| External point-of-sale / authorization client | The baseline supplies no request producer, only [`tests/mocks/mq_request_stub.py`](../../tests/mocks/mq_request_stub.py), a deterministic test double; building a production client is not requested |

The absent external client explains why there is no program-to-service row for a
point-of-sale producer. The queue consumer, request and response copybooks, routing
metadata and authorization decision are all mapped; the actor that originates the
request is outside the supplied system boundary. Adding a row for it would falsely
turn a test instrument into a production component.

Assumptions: the root README's roadmap is evidence of exclusion, not an instruction
to implement those items in this migration. Four entries — Db2 rewards, IMS DC,
SFTP and distributed-application transaction exposure — are called out because the
baseline itself names them, while the first five are architecture choices excluded
consistently by all eight sibling documents. Recording them here prevents a reader
from treating an unlisted capability as an accidental omission or an implied
delivery.


## 11. Deployment and reversibility boundaries

The matrix records **traceability and ownership**, not evidence of a live
deployment. Target modules may be authored or statically validated, but
`terraform apply` against a real account is an operator action outside this scope.
Consequently:

* no row asserts that a service, table, queue, state machine, dashboard, alarm or
  user interface has run in a provisioned environment;
* no target count is presented as a runtime measurement, benchmark, service-level
  objective or production observation;
* no absence listed in [§10](#10-out-of-scope-including-the-baselines-own-stated-future-work)
  is presented as delivered; and
* target names identify the owner of migrated behaviour even where environment
  composition remains an operator concern.

Assumptions: an ownership mapping is useful before deployment because it closes
architectural gaps without pretending to close operational ones. Conflating the two
would make a source-to-target row look like execution evidence, which it is not.
The honest statement is narrower: every baseline artifact has a disposition, and
every intentional behavioural difference has an owner and a verification contract.

The reverse boundary is equally explicit. Every baseline artifact cited in this
document remains in place, unmodified and runnable on its existing path. The test
suite remains its oracle, the job and region definitions remain available, and none
of the target mappings deletes or rewrites them. **Reverting to the mainframe path
requires no un-migration**, because there is no destructive migration step to undo.
**The migration adds a path; it does not remove one.**

Trade-offs: preserving both paths means the repository carries two expressions of
the business rules and must keep their differences visible. The alternative —
describing the target as a replacement and the baseline as retired — was rejected
because it would contradict the repository state and remove the independent oracle
needed for parity. The accepted cost is the divergence register in [§7](#7-the-divergence-register):
differences are explicit and reviewable rather than hidden behind a claim that one
path supersedes the other.


## 12. Completeness and the single-authority contract

This document is complete when, and only when, all four of these statements remain
true:

1. the service matrix accounts for all **44** migration-scope programs, with its
   arithmetic printed in [§2.11](#211-coverage-arithmetic);
2. [§4](#4-non-program-artifact-coverage) accounts for every non-program asset
   class, with inventory traps recorded in [§8](#8-inventory-caveats-a-reader-will-otherwise-contradict);
3. [§5](#5-the-retirement-register) gives every retirement a named analogue or
   places it in the exactly-two no-target population, while [§6](#6-the-dangling-programcocrdsec)
   keeps the source-less CSD definition distinct from retirement; and
4. [§7](#7-the-divergence-register) is the single register of every intentional
   behavioural difference, with [§9](#9-the-parity-oracle-and-why-warn-is-green)
   defining the oracle that detects unregistered ones.

If a count, mapping, retirement or divergence in another document disagrees with
this file, **this file remains the authority and the other document must be aligned
to it**. Siblings own the details of their domains; this document owns the
cross-domain set. That division is what allows a reviewer to ask both *"how does
this work?"* in the owning sibling and *"is this the complete set?"* here without
maintaining nine competing inventories.

Refactoring Rationale: the former short-form matrix mixed ownership rows and
divergences without the coverage arithmetic, retirement populations, paragraph
pairs or inventory caveats needed to prove completeness. Expanding it into one
authoritative index makes omissions mechanically visible: a program is either in a
service subtotal, a UI-route subtotal or the retirement subtotal; an asset class has
a disposition; and a behavioural difference has a six-part register entry. The
accepted cost is document length, which is bounded by citing the owning sibling
rather than duplicating its implementation detail.


## Related documents

All eight architecture siblings and the documentation standard are present and
linked. Each sibling owns detail that this index cites rather than restates.

| Document | What it owns that this document indexes |
|---|---|
| [`service-catalog.md`](service-catalog.md) | The canonical eight service names, responsibilities, owned data, dependency edges and reconciliation of the four component populations |
| [`data-model-and-schema-mapping.md`](data-model-and-schema-mapping.md) | Field-by-field copybook-to-column lineage, fixed-record decoding, the ten-cluster and three-index storage mapping, and data-model-specific divergences |
| [`context-and-container-diagrams.md`](context-and-container-diagrams.md) | Current-state and target-state boundaries, actors, containers, resource populations and control-flow placement |
| [`batch-orchestration.md`](batch-orchestration.md) | Job-to-state mapping, condition-code semantics, generation datasets, scheduler curation and the `TXT2PDF1` edge-collapse consequence |
| [`messaging-contracts.md`](messaging-contracts.md) | Queue mapping, positional wire format, correlation, ordering, deduplication, expiry handling and the transactional-outbox obligation |
| [`security-and-identity.md`](security-and-identity.md) | Identity mapping, authorization policy, credential non-carry-forward, encryption and network isolation |
| [`observability.md`](observability.md) | Logs, metrics, traces, alarms, the fourteen error-emission call sites and the warn-is-green interpretation |
| [`design-token-reference.md`](design-token-reference.md) | The mapset-to-route and field-to-token presentation mapping, including the response-driven field-error highlight |
| [`../CODE_DOCUMENTATION_STANDARD.md`](../CODE_DOCUMENTATION_STANDARD.md) | The documentation convention this document follows, including the four rationale labels and the paired what-and-why comment idiom |

The root [`README.md`](../../README.md) and
[`MIGRATION_README.md`](../../MIGRATION_README.md) are this document's declared
consumers. The in-repository precedent for the explainability convention is
[`tests/README.md`](../../tests/README.md) §12, and
[`CONTRIBUTING.md`](../../CONTRIBUTING.md) states the convention for the migrated
trees.

---


#### D-BROWSE-PREDICATE-STATED — the paging comparison is stated in the query, not left to the access method

* **Baseline behaviour.** [`COTRN00C.cbl`](../../app/cbl/COTRN00C.cbl) positions its
  browse in `STARTBR-TRANSACT-FILE` at **L591**, naming the dataset, the record
  identification field and the key length at **L594-L596**. The line that would state
  the comparison — the greater-or-equal option at **L597** — is **commented out**, so
  which rows the position admitted was decided by the access method's default rather
  than by anything a reader of the source can determine. The same program's forward and
  backward reads then walk from wherever that position landed.
* **Target behaviour.** `TransactionListService` states the comparison explicitly in the
  query: strictly greater than the trailing key when paging forward, strictly less than
  the leading key when paging backward, each with the matching sort direction and a
  limit of one more row than the page holds.
* **Category.** Documented divergence — browse boundary.
* **Why the difference is accepted.** A predicate that is written down is a predicate
  that can be tested, and the strict form is the one that makes a cursor a cursor: an
  inclusive comparison re-delivers the row the cursor names, so a client paging forward
  would receive its own last row again as the first row of the next page. The baseline
  cannot exhibit that because its own key-capture and its next-page probe happen to
  advance past the boundary row, so the strict predicate reproduces the rows the
  terminal displayed. What changes is only that the rule is now legible at the call
  site rather than inherited from an access method's default.
* **Where it is verified.** `TransactionRepositoryIT`'s
  `theCardFilteredKeysetPathsStepForwardAndBackwardStrictly` steps a live page boundary in
  both directions and asserts the boundary row is not re-delivered, and
  `theKeysetPageBoundaryIsUnaffectedByAnInsertBehindTheCursor` asserts the stated
  predicate is what makes that hold under a concurrent insert.
  `KeysetPaginationGateProofTest` separately forbids any offset-paging construct in the
  list-handling packages, so the stated predicate cannot be replaced by one.
* **Files.**
  `services/transaction-service/src/main/java/com/carddemo/transaction/service/TransactionListService.java`,
  `services/transaction-service/src/main/java/com/carddemo/transaction/repository/TransactionRepository.java`.

#### D-BROWSE-TRAILING-KEY-ON-SHORT-PAGE — a short page still names the last row it carried

* **Baseline behaviour.** [`COTRN00C.cbl`](../../app/cbl/COTRN00C.cbl) captures the
  trailing browse key **inside** the branch that fills the tenth screen slot, at
  **L438-L439**, so a page that returned fewer than ten rows leaves the previously
  captured trailing key standing. Its leading key is captured unconditionally at
  **L393**, and **L315** sets the no-further-page condition whenever the fill read
  nothing at all.
* **Target behaviour.** The page envelope's `lastKey` names the last row the page
  actually carried, whether the page is full or short, and a page carrying no rows names
  no boundary at all and reports no further page.
* **Category.** Documented divergence — cursor identity on a short page.
* **Why the difference is accepted.** A stale trailing key is a cursor that names a row
  from an earlier page, so a client paging forward from it silently re-reads rows it has
  already seen. Because the baseline only refreshes the key on a full page, the
  condition is reachable exactly when the last page is short — which is every browse
  whose row count is not a multiple of ten. Naming the row actually carried removes the
  repetition without changing which rows any page contains, and it keeps the envelope's
  two facts independent in the same way
  [`D-LASTKEY-RECEIVED`](#d-lastkey-received--the-page-cursor-names-only-rows-the-caller-received)
  does for the card browse.
* **Where it is verified.** `PageResponseTest`'s `emptyPageNamesNoBoundary` asserts a page
  with no rows names neither boundary and reports no further page, and
  `finalPageMayNameItsTrailingBoundary` asserts a short final page still names the key of
  its own last row; `TransactionRepositoryIT`'s
  `theSurplusRowRevealsAFurtherPageWithoutBecomingPartOfIt` asserts the look-ahead row
  never becomes the boundary.
* **Files.**
  `services/transaction-service/src/main/java/com/carddemo/transaction/service/TransactionListService.java`,
  `services/common-lib/src/main/java/com/carddemo/common/web/PageResponse.java`.

#### D-VIEW-READ-WITHOUT-LOCK — the detail read acquires no lock, because nothing rewrites

* **Baseline behaviour.** [`COTRN01C.cbl`](../../app/cbl/COTRN01C.cbl) reads the
  transaction record with the **UPDATE** option at **L275**, which acquires an exclusive
  read-for-update lock. The program contains no `REWRITE` and no `WRITE` anywhere in its
  330 lines, so the lock it takes is never used, and it is held until the task's own
  syncpoint.
* **Target behaviour.** `TransactionViewService` performs a plain read-only lookup with
  no lock mode requested, inside a read-only transaction boundary.
* **Category.** Documented divergence — locking and contention semantics.
* **Why the difference is accepted.** The lock protected nothing: no statement in the
  program modifies the record it locked, so its only observable effect was to exclude
  other readers-for-update and any writer for the duration of a screen read. Removing it
  cannot change what the screen displays — the row read is the same row — and it removes
  a contention point between a detail view and the posting job, which does write the same
  table. The direction of the change is worth stating plainly: this target holds *fewer*
  locks than the baseline, so a concurrent writer that the baseline would have blocked
  now proceeds, and a detail view is therefore a snapshot rather than a pin. That is the
  correct reading for a screen that displays and does not edit. The contexts that DO edit
  hold contention one of two ways, and which one is decided by the copybook rather than by
  preference: where the baseline record carries a before-image comparison the target
  expresses it as a version column, as the *Optimistic concurrency is expressed natively*
  note earlier in this document records; where it does not — the two authorization segments
  derive field for field from copybooks with no version member, so no version column exists
  to compare — the write carries its own contention control. Which form it carries is decided
  by the shape of the write. A write that ASSIGNS fields from values the caller supplied has no
  single-statement form, so the target takes a pessimistic row lock for the duration of it,
  which is what the reference obtains implicitly by retrieving a segment through the
  command-level interface before replacing it; `FraudMarkingService` is that case. A write that
  INCREMENTS members instead is performed as one arithmetic statement computed in the database,
  and where the increment must respect a limit that statement additionally carries the test in
  its own `where` clause; the pending-authorization summary is that case, and it takes no lock at
  all.
* **Where it is verified.** `TransactionViewServiceTest` asserts the read goes through the
  plain keyed lookup, that no method the repository declares or inherits carries a lock
  annotation of either family, and that a failed read is reported with this program's own
  capitalised failed-read sentence rather than as a contention refusal.
* **Files.**
  `services/transaction-service/src/main/java/com/carddemo/transaction/service/TransactionViewService.java`.

#### D-REFERENCE-UPSERT-NOT-EXPOSED — the maintenance screen's insert-on-miss has no single operation

* **Baseline behaviour.** Three baseline programs write `TRANSACTION_TYPE` with three
  different behaviours, and the difference is in what each does when the update matches no
  row. The maintenance screen is an **upsert**:
  [`COTRTUPC.cbl`](../../app/app-transaction-type-db2/cbl/COTRTUPC.cbl)
  `9600-WRITE-PROCESSING` at **L1531-L1592** issues the update and, on `SQLCODE +100`,
  performs `9700-INSERT-RECORD` instead of reporting a miss. The list screen's inline edit
  is a **strict update**:
  [`COTRTLIC.cbl`](../../app/app-transaction-type-db2/cbl/COTRTLIC.cbl)
  `9200-UPDATE-RECORD` at **L1837-L1894** issues the same update and, on `SQLCODE +100`,
  reports not found and inserts nothing. The batch driver **soft rejects and continues**:
  [`COBTUPDT.cbl`](../../app/app-transaction-type-db2/cbl/COBTUPDT.cbl)
  `10032-UPDATE-DB` at **L166-L195** routes every failure to `9999-ABEND`, which despite its
  name only displays the message and sets return code 4 at **L230-L233**, leaving the
  sequential read loop at **L93-L96** free to take the next record.
* **Target behaviour.** Two of the three are delivered as their own operation and the third
  is not. `PUT /api/v1/reference/transaction-types/{typeCd}` is the **strict** update: it
  answers 404 on a miss and inserts nothing, which is what the published contract declares by
  carrying 404 among that operation's answers. `POST /api/v1/reference/maintenance-actions` is
  the **soft-reject** run: each action commits in its own transaction and the reply carries one
  outcome per action with the worst condition code seen. There is **no operation whose single
  call is an upsert**; a client reaching for the maintenance screen's behaviour issues the
  create and, if it is refused as a duplicate, the replace.
* **Category.** Documented divergence — operation granularity, not rule content.
* **Why the difference is accepted.** The published contract decided it, and it decided it
  the same way in both directions: the create operation carries 409 among its answers and the
  replace operation carries 404, so a duplicate and a miss are each reportable conditions
  rather than silent branches. An upsert cannot report either — that is precisely what makes
  it an upsert — so exposing one would give this interface a third write whose reply could not
  distinguish a row it created from a row it replaced. Two alternatives were weighed. Adding
  an upsert operation to the contract was rejected because the document is the frozen
  agreement between this service and its clients, and widening it to recover a screen
  behaviour no migrated client performs is a change to the contract rather than to the code.
  Making `PUT` an upsert was rejected outright: it would take the 404 the contract publishes
  and make it unreachable, so a client that mistyped a code would silently create a type
  instead of being told the code does not exist. The cost is stated plainly: a caller
  reproducing the maintenance screen makes two calls where the baseline operator made one,
  and observes an intermediate 409 the baseline never surfaced.
* **Where it is verified.** `ReferenceApiRoutingContractTest` asserts in both directions that
  the delivered handlers and the declared operations are the same set and that the count is
  nineteen, so an upsert added later without a contract entry — or an entry without a handler
  — fails the build. `ReferenceWriteBehaviourTest` asserts that the strict replace reports the
  miss rather than inserting, and that the batch run applies later actions after an earlier one
  is rejected.
* **Files.**
  `services/reference-service/src/main/java/com/carddemo/reference/service/TransactionTypeService.java`,
  `services/reference-service/src/main/java/com/carddemo/reference/service/ReferenceBatchUpdateService.java`,
  `services/reference-service/src/main/java/com/carddemo/reference/api/TransactionTypeController.java`,
  `services/reference-service/src/main/resources/openapi/reference-api.yaml`.

#### D-REFERENCE-TRIM-TRAILING-ONLY — stored descriptions keep a leading blank the baseline would have dropped

* **Baseline behaviour.** The baseline offers **three** behaviours for the transaction-type and
  transaction-category description, and they do not agree with one another. Two trim:
  [`COTRTLIC.cbl`](../../app/app-transaction-type-db2/cbl/COTRTLIC.cbl) **L1841-L1842** moves a
  `FUNCTION TRIM` of the description into the generated host variable while **L1843-L1844**
  computes the accompanying length from the **untrimmed** field, and
  [`COTRTUPC.cbl`](../../app/app-transaction-type-db2/cbl/COTRTUPC.cbl) **L1539-L1542** does the
  same. `FUNCTION TRIM` with no `LEADING` or `TRAILING` operand removes blanks from **both** ends.
  The third does not trim at all:
  [`COBTUPDT.cbl`](../../app/app-transaction-type-db2/cbl/COBTUPDT.cbl) **L172-L174** binds the raw
  fixed host variables declared at its **L74** `05 INPUT-REC-NUMBER PIC X(2)` and **L76**
  `05 INPUT-REC-DESC PIC X(50)` rather than the generated structure, so it preserves leading blanks.
  What the three converge on is reasoned from the language definition rather than observed from
  stored bytes: both trimming sites pass a **fixed** fifty-character item — `COTRTLIC.cbl` **L172**
  declares `30 WS-ROW-TR-DESC-IN PIC X(50)` and `COTRTUPC.cbl` **L335** declares
  `15 TTUP-NEW-TTYP-TYPE-DESC PIC X(50)` — and the length function applied to a fixed alphanumeric
  item yields its declared storage size rather than its content length, so the length host variable
  receives fifty unconditionally. The three therefore **agree on trailing blanks and differ only on
  leading ones**, which narrows the disagreement to exactly one dimension.
* **Target behaviour.** One normalisation serves the whole reference package:
  `TransactionTypeMapper.trimForStorage` removes **trailing** blanks only. A description submitted
  as `"  FEE"` is therefore stored as `"  FEE"`, where the two trimming baseline programs would have
  stored `"FEE"`.
* **Category.** Documented divergence — stored representation of one column, not rule content.
* **Why the difference is accepted.** The baseline is three-way inconsistent on this field, so **no
  single package rule can match all three** — matching the two screens would contradict the batch
  driver and matching the batch driver would contradict the screens. Trailing-only is chosen because
  it is the dimension all three agree on, and because it is the conservative direction: it preserves
  a byte the source record was given rather than discarding one, and a leading blank in a submitted
  description is content a caller typed. The alternative — trimming both ends at the mapping site —
  was rejected on scope rather than on preference: the same member is the normalisation for
  `TransactionCategoryMapper`, `TransactionCategoryService` and `ReferenceBatchUpdateService`, so
  re-deciding it there would silently change the stored form for three other paths, which is exactly
  the divergence-between-records that a package-scope ruling exists to prevent.
* **What was corrected alongside this entry.** Two comments — one in `TransactionTypeMapper` and one
  in that package's `package-info.java` — asserted that this difference was **already registered
  here**. It was not: this register held `D-REFERENCE-INTEGRITY-SENTENCE`,
  `D-REFERENCE-ACTION-DOMAIN` and `D-REFERENCE-UPSERT-NOT-EXPOSED` and nothing about trimming, so a
  reader following the citation found no entry and a reviewer checking the claim found it false.
  This entry is what those comments now truthfully cite.
* **Where equality is NOT affected.** The baseline treats surrounding blanks as **insignificant for
  equality**: every description comparison it makes goes through a trim, at `COTRTLIC.cbl` **L1065**
  and **L1069** and at `COTRTUPC.cbl` **L791** and **L795**. The target's no-change comparison
  therefore trims **both** ends before comparing, so a submission differing from the stored value
  only in leading blanks is recognised as describing the same state — as the baseline recognises it
  — even though the two stored forms would differ byte for byte. Storage and equality are separate
  decisions here, and the baseline makes them separately too.
* **Where it is verified.** `ReferenceWriteBehaviourTest` asserts the stored form keeps a leading
  blank and drops trailing ones on both create and replace, that the no-change comparison ignores
  blanks at both ends, and that a category created against a type reuses the same normalisation.
* **Files.**
  `services/reference-service/src/main/java/com/carddemo/reference/mapper/TransactionTypeMapper.java`,
  `services/reference-service/src/main/java/com/carddemo/reference/mapper/package-info.java`,
  `services/reference-service/src/main/java/com/carddemo/reference/service/TransactionTypeService.java`.

#### D-AUTHORIZATION-FIFO-IDENTITY-METADATA — the queue's two identities are the card and transaction values themselves

* **Baseline behaviour.** The reference consumer publishes its reply through a message
  descriptor that carries no grouping or deduplication concept at all.
  [`COPAUA0C.cbl`](../../app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl) saves the
  requester's correlation identifier at **L412** and its reply-to queue at
  **L413**–**L414**, echoes the correlation value at **L745** and puts the reply with
  `MQPUT1` at **L758**. Ordering is not expressed in metadata because it does not need to
  be: the program reads one message, decides it, commits at **L335** and only then reads
  the next, at **L326**–**L342**, so per-card order is a consequence of a single task
  reading a single queue. Duplicate suppression does not exist — the get at **L389**
  carries `MQGMO-NO-SYNCPOINT`, so a request is destroyed on read and never presented
  again.
* **Target behaviour.** The reply queue is FIFO, so both concepts must be expressed as
  message metadata. `MessageGroupId` is the **card number itself**, sixteen characters
  from `PA-RQ-CARD-NUM`, and `MessageDeduplicationId` is the **transaction identifier
  itself**, fifteen characters from `PA-RQ-TRANSACTION-ID`. The two values are stored on
  the outbox row in `order_group_id` and `deduplication_id` and copied onto the send
  unchanged. A primary account number therefore appears in queue metadata, which sits
  outside the message body that server-side encryption protects and which is reported in
  queue telemetry and in send traces.
* **Category.** Documented divergence — a data-exposure surface the baseline does not
  have, created by expressing in metadata what the reference system expressed by being
  single-threaded.
* **Why the difference is accepted.** The identities are not this target's to choose.
  &sect;0.4.1.8 of the technical specification states them literally —
  "`MessageGroupId = card_num` preserves per-card ordering; `MessageDeduplicationId =
  transaction_id` gives exactly-once" — and &sect;0.7.6 repeats the grouping rule as the
  mechanism that preserves per-card ordering. Refactoring Rationale: an earlier revision
  derived both through a keyed HMAC so that neither raw value reached metadata, and that
  derivation is withdrawn because it removed the guarantees it was layered on. A group
  identity orders one card's messages only while **every** producer on the queue computes
  the same value for that card, and a deduplication identity suppresses a resend only
  while the **requester** that may resend can predict it; a value keyed from this
  service's own secret satisfies neither, so a second producer built to the specification
  would have placed one card's messages in a different group and lost the ordering
  guarantee outright, and an honest resend arriving by any other path would have been
  accepted as new. Trade-offs: the accepted cost is the metadata exposure, and it is
  bounded by three controls that are provisioned in infrastructure code rather than
  assumed — the queues are encrypted server-side with a customer-managed KMS key
  (`infra/modules/sqs`), they are reachable only through an interface endpoint inside the
  private network (`infra/modules/network`), and receive and send capability is scoped to
  the task roles of this consumer and of the requesting producer. What those controls do
  not reach is queue telemetry and any log line that records a group identity, which is
  why the exposure is held to **one** metadata field and asserted to be nowhere else.
  Assumptions: this states a difference between the baseline and the target and asserts
  nothing about either being wrong; the baseline needed no such metadata and is unchanged.
  Alternatives Considered: masking the number to its last four digits in the group
  identity. Rejected because grouping would then be by masked form, so two different cards
  sharing four trailing digits would serialise against each other while appearing to be
  correctly grouped — a correctness change disguised as a redaction. Alternatives
  Considered: obtaining an approved specification change to permit a derived identity.
  Rejected as the resolution here because the specification is the frozen agreed source of
  truth for this migration and code is aligned to it; anyone revisiting the judgement
  revisits &sect;0.4.1.8, not the publisher.
* **Where it is verified.** `OutboxMetadataConfidentialityTest` asserts the group
  identity equals the card number, the deduplication identity equals the transaction
  identifier, the body still carries the number so the positional wire contract at
  [`CCPAURLY.cpy`](../../app/app-authorization-ims-db2-mq/cpy/CCPAURLY.cpy) **L19** holds,
  and — the containment rule — that no other metadata field carries the whole number, its
  leading six digits or its trailing four. `AuthorizationRequestListenerTest` asserts the
  two identities on the row the consumer writes, on both the first-decision and the
  redelivery paths, and `AuthorizationFixtureContractTest` asserts that seven declined
  replies for one card carry seven distinct deduplication identities inside one shared
  ordering group.
* **Files.**
  `services/authorization-service/src/main/java/com/carddemo/authorization/domain/AuthReplyOutbox.java`,
  `services/authorization-service/src/main/java/com/carddemo/authorization/domain/OutboxMessage.java`,
  `services/authorization-service/src/main/java/com/carddemo/authorization/service/OutboxPublisher.java`,
  `services/authorization-service/src/main/java/com/carddemo/authorization/mapper/AuthorizationMessageMapper.java`,
  `services/authorization-service/src/main/java/com/carddemo/authorization/config/SqsConfig.java`,
  `services/authorization-service/src/main/resources/db/migration/V3__authorization_outbox_fifo_identities.sql`.

#### D-D — a failed segment write rolls the message back rather than letting a partial write stand

* **Baseline behaviour.** Both of the authorization consumer's segment writes end the
  same way, and neither ends the unit of work.
  [`COPAUA0C.cbl`](../../app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl)
  `8400-UPDATE-SUMMARY` at **L835–L847** and `8500-INSERT-AUTH` at **L920–L932** each
  move the database status into the return field, test `IF STATUS-OK CONTINUE`, and on
  the `ELSE` set an error location — `'I003'` and `'I004'` — together with the critical
  level, the subsystem, the status code, a message, and the card number as the event
  key, then perform the error paragraph at **L846** and **L931** and fall through to
  their own exit. **Neither sets an abort flag and neither skips the syncpoint at
  L335.** What ends the task instead is the critical level itself: the error paragraph
  tests it at **L1008–L1010** and performs the end routine at **L1016–L1024**, which
  terminates and issues `EXEC CICS RETURN` at **L1021–L1022** — and a task returning
  normally takes the platform's implicit end-of-task syncpoint, so **the partial write
  commits.** The request cannot be presented again to re-derive the missing half,
  because the get at **L389** is `MQGMO-NO-SYNCPOINT` and destroyed it on read.
  The receive path has the same shape without the severity: `3100-READ-REQUEST-MQ` at
  **L386–L434** handles a reason other than no-message-available at **L418** by logging
  `'M003'` at **L429**, after which the paragraph simply ends at **L430–L432** and exits
  at **L434**, setting neither the no-more-messages condition nor the loop-end flag.
* **Target behaviour.** The whole of one message's handling is one transaction, so a
  failed write rolls back the summary contribution, the detail row and the outbox reply
  together. `AuthorizationRequestListener.onRequest` lets the exception propagate — its
  `@throws` clauses name both types that leave — the message becomes visible again after
  its visibility timeout, and the redrive policy moves it to the dead-letter queue at the
  fifth receive. A receive failure is terminal for the poll cycle structurally rather
  than by a coded flag: there is no retained get buffer to re-extract, so there is no
  second decision to prevent.
* **Category.** Documented divergence — durability of a partially-written unit of work.
* **Why the difference is accepted.** On the axis of *stopping*, the two systems agree:
  the critical severity ends the baseline task and the exception ends this delivery, so
  neither goes on to handle further messages as though nothing had happened. The
  divergence is on the axis of *durability* — a rollback here against an implicit commit
  there. It is accepted because the alternative is not available: reproducing the
  baseline would require committing a decision whose contribution reached only one of the
  two segments, and the reply for that decision has already been published by then, so
  the requester holds an answer that the stored state does not account for and the
  request that would let it be re-derived no longer exists. A rollback leaves the message
  on the queue, which is the only outcome from which the correct state is still
  reachable. Assumptions: this is a statement about the target's obligations, not a
  verdict on the baseline; `app/**` is the parity oracle and is unchanged.
  One classification difference is recorded here as a non-divergence, so that a reader
  comparing log dimensions does not raise it as one: the baseline attributes the receive
  failure to the transaction monitor, setting `ERR-CICS` at **L421**, while attributing
  the reply put at **L770** to the message transport with `ERR-MQ`. The target reports
  both as messaging faults. Nothing observable turns on it — the field is a log
  dimension and not a control value.
* **Where it is verified.** `AuthorizationRequestListenerTest`'s
  `aVanishedSummaryRefusesTheDecision` asserts that a disagreement discovered during the
  write raises and that **neither** the outbox row **nor** the detail row is written, so
  the reply cannot precede the state that justifies it; `anUnlistedReplyDestinationRefusesTheRequest`,
  `aRequestNamingNoReplyDestinationIsRefused`, `aRequestDeclaringNoWireFormatIsRefused`
  and `aNonCanonicalCorrelationAttributeRefusesTheMessage` each assert a refusal
  together with the absence of every side effect, which is what shows nothing is logged
  and then swallowed. `everyReceivedMessageCountsTowardsTheWindow` asserts that a
  malformed request still consumes its place in the admission window, so the bound
  advances on failure exactly as the baseline counter at **L332** does.
  `AuthorizationDecisionUnitOfWorkRepositoryIT` runs the unit of work against a live
  database and asserts that a failure leaves no row of any of the three kinds behind.
* **Files.**
  `services/authorization-service/src/main/java/com/carddemo/authorization/service/AuthorizationRequestListener.java`,
  `services/authorization-service/src/main/java/com/carddemo/authorization/mapper/AuthorizationMessageMapper.java`,
  `services/authorization-service/src/test/java/com/carddemo/authorization/service/AuthorizationRequestListenerTest.java`.

#### D-EXPORT-STAGED-THROUGH-A-FILE — the export and import round trip stages through a temporary file

* **Baseline behaviour.** Each program holds ONE record area and nothing wider.
  [`CBEXPORT.cbl`](../../app/cbl/CBEXPORT.cbl) writes each 500-byte record as it is composed
  and [`CBIMPORT.cbl`](../../app/cbl/CBIMPORT.cbl) reads each one as it arrives, so the working
  set of either program is independent of how many rows the masters hold.
* **Target behaviour.** The export composes its records into a temporary file and uploads that
  file; the import streams the object it reads rather than materialising it; and every
  temporary file is removed on every path, including a failed upload. The bytes, the record
  order, the sequence numbering and the six published artefacts are unchanged.
* **Category.** Structural divergence with no observable difference, registered rather than
  absorbed.
* **Why the difference is accepted.** It is what RESTORES the baseline's own property on a
  platform whose object client wants a body. Composing the whole dataset in memory first would
  make the working set proportional to the row count of five masters, which is the one property
  the reference implementation does not have -- and it is the discipline the posting, backup and
  combine jobs of this module already follow, so the round trip is no longer the exception.
* **Alternatives considered.** (a) Buffering the dataset in a byte array, which is the shortest
  code and was the first written. Rejected on the working-set argument above: it fails on the
  populations this artefact exists to move, and it fails late, on an operator-invoked utility.
  (b) A multipart upload streamed straight from the composer, which needs no temporary file at
  all. Rejected as more machinery than the size warrants, and because a failed part leaves an
  incomplete upload to reap, where a temporary file is removed by the same block that created
  it.
* **Where it is verified.** `DatasetJobBodiesTest.theStagedDatasetIsPublishedOnceAndCleanedUp`
  asserts one put of the staged file and that no temporary artefact survives the run, and
  `aCompletedImportRemovesEveryStagedArtefact` asserts the same of the import direction.
* **Files.**
  `services/batch-service/src/main/java/com/carddemo/batch/job/ExportJob.java`,
  `services/batch-service/src/main/java/com/carddemo/batch/job/ImportJob.java`,
  `services/batch-service/src/main/java/com/carddemo/batch/job/DatasetPayloadWriter.java`.

#### D-IMPORT-TRUNCATED-ARTEFACT, also D-IMPORT-TRUNCATION-REFUSED — a truncated export artefact fails the import instead of being read as far as it goes

* **Baseline behaviour.** The reference cannot meet this condition. Its input is selected
  `ORGANIZATION IS SEQUENTIAL` and allocated `RECFM=FB` at
  [`CBIMPORT.jcl`](../../app/jcl/CBIMPORT.jcl) **L28**–**L32**, and a fixed-blocked dataset's
  length is always a whole multiple of its record length, so a partial trailing image is not a
  state the storage can hold. `RETURN-CODE` appears nowhere in
  [`CBIMPORT.cbl`](../../app/cbl/CBIMPORT.cbl): the program ends normally or abends.
* **Target behaviour.** The input is an object whose length is whatever was written, so the
  condition is reachable. `ImportJob` assembles the 132-byte diagnostic record for the short
  image, logs `event=batch.import.short-record` with the offset, the bytes remaining and the
  bytes expected, and then **raises before `4000-FINALIZE` is reached** — so none of the six
  artefacts is published and the step reports the hard-failure tier. This is labelled **D-9**
  in `ImportJob`'s own class documentation.
* **Category.** Documented divergence — an answer to a state the reference's storage made
  impossible.
* **Why the difference is accepted.** The earlier behaviour was to record the short image, stop
  reading, reconcile the whole records already read, publish all six artefacts and return the
  clean tier. Each step is individually defensible and the combination is not: these artefacts
  have **no golden master** — the ground is that `CBEXPORT` and `CBIMPORT` do not compile under
  the open-source compiler, recorded at [`tests/README.md`](../../tests/README.md) **L53**–**L69**
  — so a consumer has nothing to compare them against and no way to distinguish a complete set
  from one missing everything after a truncation point. A clean status on a knowingly partial
  product is the one outcome that cannot be detected downstream.
* **Alternatives considered.** (a) The soft-warn tier, publishing what was read and grading the
  run four. Rejected: that tier exists to let the nightly chain CONTINUE — `COND=(4,LT)` at
  [`TRANBKP.jcl`](../../app/jcl/TRANBKP.jcl) **L51** — so it would leave the incomplete
  artefacts in the store for a loader to consume. (b) Publishing the diagnostic artefact alone.
  Rejected: one diagnostic artefact beside five absent ones is indistinguishable from a run that
  never wrote them, and the log line already carries the same three facts.
* **Impact.** An operator meeting a truncated artefact sees a failed step rather than a clean
  one, and re-stages the artefact rather than discovering the shortfall in a target system.
* **Where it is asserted.** `DatasetJobBodiesTest.aTruncatedDatasetFailsTheRun` asserts both
  halves — the raise, and that `putObject` was never called.
* **Files.**
  `services/batch-service/src/main/java/com/carddemo/batch/job/ImportJob.java`,
  `services/batch-service/src/test/java/com/carddemo/batch/job/DatasetJobBodiesTest.java`.

#### D-BATCH-FAILURE-NOTIFICATION, also D-BATCH-FAILURE-EVENT-PUBLISHED — a failed nightly run notifies a queue the baseline never wrote to

* **Baseline behaviour.** A reference batch program signals failure by terminating
  abnormally, and by writing one line of text to the job log while doing so.
  [`CBTRN02C.cbl`](../../app/cbl/CBTRN02C.cbl) carries the paragraph at **L707-L711**:
  L708 displays `'ABENDING PROGRAM'`, L709 moves zero into the timing argument, L710
  moves `999` into the abend code and L711 calls the environment's abend service with
  the two. The same paragraph appears in each sibling program this module migrates —
  [`CBACT04C.cbl`](../../app/cbl/CBACT04C.cbl) **L629** displays the identical text,
  [`CBEXPORT.cbl`](../../app/cbl/CBEXPORT.cbl) **L578** displays
  `'CBEXPORT: ABENDING PROGRAM'` and [`CBIMPORT.cbl`](../../app/cbl/CBIMPORT.cbl)
  **L483** displays `'CBIMPORT: ABENDING PROGRAM'`. **No program under `app/cbl` and no
  job under `app/jcl` names a queue, opens one or issues any message verb at all**, so
  there is no baseline producer here to transcribe. The queue name the target sink is the
  analogue of belongs to the inquiry extension and to nothing else: `CARD.DEMO.ERROR` is
  moved into an error-queue-name field at exactly two places in the whole reference tree,
  [`COACCT01.cbl`](../../app/app-vsam-mq/cbl/COACCT01.cbl) **L294** and
  [`CODATE01.cbl`](../../app/app-vsam-mq/cbl/CODATE01.cbl) **L243**, and both are online
  programs that migrate to other bounded contexts.
* **Target behaviour.** `BatchApplication` publishes one `BatchErrorEvent` per failed run
  to the deployment's single terminal error queue, through `BatchErrorPublisher` and the
  validated binding `SqsConfig.ErrorSinkBinding`. It publishes exactly once per run rather
  than once per failed step, names the step that failed when a step execution identifies
  one, and publishes **only** for a tier that stops the chain — the warn tier is not
  published, because the baseline reaches it by design at
  [`CBTRN02C.cbl`](../../app/cbl/CBTRN02C.cbl) **L229-L230** on a run that did its job
  correctly. The whole configuration is gated on `carddemo.messaging.error-queue-url`, so a
  deployment that supplies no sink address publishes nothing and still runs every job.
* **Category.** Documented divergence — an **addition**, not a changed behaviour. Nothing
  the parity oracle compares is altered: the reject stream, the posted records, the updated
  masters and the process return code are all exactly as before, and the notification is a
  further channel beside them.
* **Why the difference is accepted.** What was wrong with the baseline signal is specific
  and threefold, and none of the three is a defect in the reference so much as a property of
  a job log. A line of text is not machine-readable, so nothing can route or alarm on it. It
  carries no identifier shared with any other step, so two failures in one run cannot be
  related to each other or to the run they belong to. And it is deposited in the job's own
  output rather than delivered anywhere, so reaching it is a retrieval an operator performs
  instead of a notification an operator receives. The target additionally has three
  properties the baseline does not: the migration provisions a standard queue as one
  terminal error sink for the whole deployment, this module is one of only two bounded
  contexts granted a queue configuration at all, and every state of the batch state machine
  carries a catch route to a failure-notification state — so the notification is a state that
  exists and needed a payload.
  Assumptions: the payload carries no record image, no primary account number, no account
  identifier and no customer identifier, and that is enforced on what goes in rather than
  observed of what happens to be there: `BatchErrorEvent`'s canonical constructor masks
  identifier-shaped digit runs, replaces any component naming a credential, and refuses a
  return-code tier that permits the chain to continue. An operator joins the run identifier
  and step name to the durable `batch.batch_run` row and the correlation identifier to the
  run's log lines, and reads the failing input there, inside stores that have the controls
  for it.
  Assumptions: a fault raised while publishing is swallowed and logged rather than
  propagated. The caller is already on a failure path and its exit status is the only channel
  the orchestrator reads, so a publish that threw would replace a graded, reported failure
  with an unreported one — and would do so precisely when an unreachable queue is most
  likely. No notification is published on the `Error` path at all, for the same reason the
  framework's exit helper is kept off it: after an `Error` the runtime may be unable to
  allocate what a send needs.
* **Where it is verified.** `BatchApplicationTest`'s `failedRunPublishesOneNotification`
  asserts one notification carrying the failing step, `softWarnRunPublishesNothing` asserts
  the warn and clean tiers publish nothing at all, `unconfiguredDeploymentRaisesNothing`
  asserts a deployment with no producer still completes the attempt,
  `throwingProducerIsSuppressed` asserts a throwing producer is recorded and not propagated,
  and `failingStepIsNamedFromTheExecution` asserts the step-naming rule and its no-step
  substitution. `BatchErrorPublisherTest` asserts the send is the one the binding builds,
  with exactly three attributes and neither ordered-queue identifier, that a transport fault
  is reported without its message and without the sink's address, and that a null event is
  still raised. `SqsConfigTest` asserts the property gate in both directions — a producer
  when the address is supplied, nothing when it is not — and that the gate names the property
  the environment roots publish.
* **Files.**
  `services/batch-service/src/main/java/com/carddemo/batch/BatchApplication.java`,
  `services/batch-service/src/main/java/com/carddemo/batch/service/BatchErrorPublisher.java`,
  `services/batch-service/src/main/java/com/carddemo/batch/config/SqsConfig.java`,
  `services/batch-service/src/main/java/com/carddemo/batch/dto/BatchErrorEvent.java`,
  `infra/modules/sqs/outputs.tf`, `infra/modules/ecs-service/main.tf`,
  `infra/envs/dev/main.tf`, `infra/envs/prod/main.tf`.



#### D-DALY-CARD-TIE-BREAK — the daily-subset ordering carries a tie-break the reference sort does not

* **Baseline behaviour.** [`TRANREPT.jcl`](../../app/jcl/TRANREPT.jcl) unloads the
  transaction master to `TRANSACT.BKUP(+1)` at **L29-L33** and then sorts it at
  **L37-L55**. The sort declares exactly **one** control field —
  `SORT FIELDS=(TRAN-CARD-NUM,A)` at **L46**, resolved through the `SYMNAMES` entry
  `TRAN-CARD-NUM,263,16,ZD` at **L41** — and writes the selected subset to
  `TRANSACT.DALY(+1)` at **L55**. With a single control field the relative order of two
  records carrying the **same** card number is not determined by the job: DFSORT preserves
  input order only when the `EQUALS` option is in effect, and whether it is comes from the
  installation default rather than from anything in this JCL. So the byte order of the
  reference's own output is not a property a reader of the baseline can establish, and it
  is not necessarily the same from one installation to the next.
* **Target behaviour.** `TransactionRepository.streamProcessedInWindowOrderedByCard`
  orders by `cardNum ASC, transactionId ASC`. The first key is the reference's; the second
  is added. `BackupTransactionsJob` walks that stream to stage the `TRANSACT.DALY`
  generation, so the staged artifact is byte-identical between two runs over the same rows.
* **Category.** Documented divergence — output ordering made total.
* **Why the difference is accepted.** The alternative is an ordering that is only partial,
  and a partial ordering has no defensible target behaviour at all: two runs over identical
  data could stage different bytes, which would make the generation unusable as a
  comparison baseline and would make any byte-level parity assertion over it non-repeatable.
  Adding the primary key as a second key cannot change which records appear or how they
  are grouped — every record with one card number still forms one contiguous run, which is
  the only property `CBTRN03C` depends on, since it breaks its per-card total on a change of
  card number and never on adjacency within a card. The tie-break decides only the order
  **inside** a run, where the reference decides nothing.
* **Alternatives considered.** Reproducing the unspecified order was rejected because there
  is nothing to reproduce — an installation default is not a contract, and picking one of
  its two outcomes would be inventing a baseline rather than transcribing one. Ordering by
  `procTs` inside a card was also rejected: `TRAN-PROC-DT` is only the first ten characters
  of a `TIMESTAMP(6)`, so two transactions posted in the same microsecond would tie again
  and the ordering would still not be total.
* **Where it is verified.** In two places, because the two halves need different evidence.
  `BackupTransactionsJobTest.theDailySubsetIsOrderedByCardThenIdentifier` stages a window
  holding two cards with two transactions each, presented out of order, and asserts the
  staged bytes carry the four records grouped by card and ascending by identifier within
  each card — that is the JOB honouring what the finder returns.
  `PostingUnitOfWorkIT.theDailySubsetFinderOrdersAndBoundsAtTheDatabase` then asserts the
  FINDER itself against a real PostgreSQL: the composite ordering resolves in the declared
  sequence, and rows seeded one microsecond outside each edge of the half-open window are
  excluded while rows one microsecond inside are included. A stubbed repository can model an
  `ORDER BY` but cannot evaluate one, and the strict upper bound on a `TIMESTAMP(6)` is a
  boundary only an engine decides, which is why the unit case alone would not have closed this.
* **Files.**
  `services/batch-service/src/main/java/com/carddemo/batch/repository/TransactionRepository.java`,
  `services/batch-service/src/main/java/com/carddemo/batch/job/BackupTransactionsJob.java`.


#### D-PRTCATBL-LRECL — the category-balance line is forty bytes, not the forty-one its OUTREC composes

* **Baseline behaviour.** [`PRTCATBL.jcl`](../../app/jcl/PRTCATBL.jcl) sorts the
  category-balance backup at **L43-L56** and writes the result to
  `AWS.M2.CARDDEMO.TCATBALF.REPT` at **L59-L63**. Its `OUTREC` at **L53-L56** composes,
  in order: the eleven-digit account identifier, one blank, the two-character type code,
  one blank, the four-digit category code, one blank, the balance under
  `EDIT=(TTTTTTTTT.TT)`, and nine further blanks. The edit mask emits **twelve**
  characters — nine integer digit positions, a period, two decimal positions — so the
  composed length is `11 + 1 + 2 + 1 + 4 + 1 + 12 + 9 = 41`. The `SORTOUT` DD at **L61**
  declares `DCB=(LRECL=40,RECFM=FB,BLKSIZE=0)`. The two disagree by one byte, and the
  baseline is internally inconsistent as written.
* **Target behaviour.** `CategoryBalanceLineLayout` renders a line of exactly **forty**
  bytes, composing the same seven fields in the same order and emitting **eight** trailing
  blanks rather than nine. `CategoryBalanceReportService` writes those lines in
  `account_id, type_cd, category_cd` order — the order **L52** declares — and
  `CategoryBalanceArtifactPublisher` publishes them as `category-balance.txt`.
* **Category.** Documented divergence — record length, resolved in favour of the declared
  length.
* **Why the difference is accepted.** The two candidate resolutions differ only in padding:
  the byte in dispute is the last of the nine trailing blanks, so honouring `LRECL=40`
  truncates padding and never data. Every value-bearing field, its width, its position and
  its edit mask are identical under either reading. Choosing the declared length also
  matches what the reference itself would produce, because a fixed-blocked dataset defined
  at forty bytes does not grow to accept a forty-one-byte record — DFSORT would either
  fail the step or truncate to the declared length, and truncation removes exactly the
  blank in question.
* **Why the tie is broken differently here than for the statements.** The comparable
  disagreement in `CREASTMT.JCL` is settled by a COBOL program: `CBSTM03A.CBL` declares
  its statement lines as `PIC X(80)` and `PIC X(100)`, so the program's own record
  declaration outranks the JCL. `PRTCATBL.jcl` has **no** program — the whole report is a
  DFSORT `OUTREC` — so there is no third statement to appeal to and the only two
  candidates are the composition and the declaration. The declaration wins for the reason
  above, and the asymmetry between the two resolutions is recorded here so a reader does
  not read it as inconsistency.
* **Where it is verified.** `CategoryBalanceLineLayoutTest` asserts the rendered length is
  forty for every case, asserts the field offsets and the single-blank separators, asserts
  the edit mask against the reference's `EDIT=(TTTTTTTTT.TT)` for positive, negative, zero
  and nine-integer-digit balances, asserts the identifier and category code are
  zero-padded while the type code is blank-padded, and asserts a value too wide for its
  field is refused rather than silently truncated into its neighbour.
* **Files.**
  `services/reporting-service/src/main/java/com/carddemo/reporting/mapper/CategoryBalanceLineLayout.java`,
  `services/reporting-service/src/main/java/com/carddemo/reporting/service/CategoryBalanceReportService.java`,
  `services/reporting-service/src/main/java/com/carddemo/reporting/task/CategoryBalanceArtifactPublisher.java`.

<sub>Apache-2.0 · Authoritative artifact-to-target matrix, retirement register and
behavioural-divergence register for CardDemo. The baseline under `app/**` is cited
throughout and never modified. Convention:
[`docs/CODE_DOCUMENTATION_STANDARD.md`](../CODE_DOCUMENTATION_STANDARD.md).</sub>
