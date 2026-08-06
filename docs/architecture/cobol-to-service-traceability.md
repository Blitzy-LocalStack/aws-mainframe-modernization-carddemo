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
> `authorization-service` (D-5, D-6), `batch-service` (D-1, D-3),
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
| `COACTUPC` | online | [`app/cbl/COACTUPC.cbl`](../../app/cbl/COACTUPC.cbl) | `AccountController` update, `AccountUpdateService` | The before-image comparison becomes a version column; see [§7.3](#73-structural-divergences-that-are-not-defects) |
| `CBACT01C` | batch | [`app/cbl/CBACT01C.cbl`](../../app/cbl/CBACT01C.cbl) | `AccountRepository` sequential read | Account master access |
| `CBACT03C` | batch | [`app/cbl/CBACT03C.cbl`](../../app/cbl/CBACT03C.cbl) | `CardXrefController`, `CardXrefRepository` | Includes the by-account path that replaces `CXACAIX` |
| `CBCUS01C` | batch | [`app/cbl/CBCUS01C.cbl`](../../app/cbl/CBCUS01C.cbl) | `CustomerController`, `CustomerRepository` | Customer master access |
| `COACCT01` | online | [`app/app-vsam-mq/cbl/COACCT01.cbl`](../../app/app-vsam-mq/cbl/COACCT01.cbl) | `InquiryMessageListener` | The account-inquiry request/reply flow; contract in [`messaging-contracts.md`](messaging-contracts.md) |

### 2.3 `card-service`

| Program | Type | Source | Target artifact | Notes |
|---|---|---|---|---|
| `COCRDLIC` | online | [`app/cbl/COCRDLIC.cbl`](../../app/cbl/COCRDLIC.cbl) | `CardController` list, `CardListService` | The browse cursor becomes a keyset page envelope |
| `COCRDSLC` | online | [`app/cbl/COCRDSLC.cbl`](../../app/cbl/COCRDSLC.cbl) | `CardController` detail | Primary account number masked except on the administrative endpoint |
| `COCRDUPC` | online | [`app/cbl/COCRDUPC.cbl`](../../app/cbl/COCRDUPC.cbl) | `CardController` update, `CardUpdateService` | `SYNCPOINT` becomes a transaction boundary |
| `CBACT02C` | batch | [`app/cbl/CBACT02C.cbl`](../../app/cbl/CBACT02C.cbl) | `CardRepository` sequential read | Includes the by-account path that replaces `CARDAIX` |

### 2.4 `transaction-service`

| Program | Type | Source | Target artifact | Notes |
|---|---|---|---|---|
| `COTRN00C` | online | [`app/cbl/COTRN00C.cbl`](../../app/cbl/COTRN00C.cbl) | `TransactionController` list, `TransactionListService` | Forward and backward paging match the browse verbs |
| `COTRN01C` | online | [`app/cbl/COTRN01C.cbl`](../../app/cbl/COTRN01C.cbl) | `TransactionController` detail | Money transported as a string |
| `COTRN02C` | online | [`app/cbl/COTRN02C.cbl`](../../app/cbl/COTRN02C.cbl) | `TransactionController` create, `TransactionAddService` | Identifier generation preserved |
| `COBIL00C` | online | [`app/cbl/COBIL00C.cbl`](../../app/cbl/COBIL00C.cbl) | `BillPaymentController`, `BillPaymentService` | Balance-affecting write inside one transaction |

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
| `COPAUA0C` | batch | [`COPAUA0C.cbl`](../../app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl) | `AuthorizationRequestListener`, `OutboxPublisher` | The queue consumer. Registered divergences: [D-5](#d-5--the-reply-published-outside-the-commit), [D-6](#d-6--the-distributed-commit-is-eliminated-not-emulated) |
| `CBPAUP0C` | batch | [`CBPAUP0C.cbl`](../../app/app-authorization-ims-db2-mq/cbl/CBPAUP0C.cbl) | `PurgeJob` | Expiry and purge of pending authorizations |
| `PAUDBLOD` | batch | [`PAUDBLOD.CBL`](../../app/app-authorization-ims-db2-mq/cbl/PAUDBLOD.CBL) | `LoadService` | Segment load utility |
| `PAUDBUNL` | batch | [`PAUDBUNL.CBL`](../../app/app-authorization-ims-db2-mq/cbl/PAUDBUNL.CBL) | `UnloadService` | Segment unload utility |
| `DBUNLDGS` | batch | [`DBUNLDGS.CBL`](../../app/app-authorization-ims-db2-mq/cbl/DBUNLDGS.CBL) | `UnloadService` sequential path | Sequential unload utility |

### 2.8 `reporting-service`

| Program | Type | Source | Target artifact | Notes |
|---|---|---|---|---|
| `CORPT00C` | online | [`app/cbl/CORPT00C.cbl`](../../app/cbl/CORPT00C.cbl) | `ReportController` | The transient-data-queue submission becomes an orchestration start; see [`batch-orchestration.md`](batch-orchestration.md) |
| `CBTRN03C` | batch | [`app/cbl/CBTRN03C.cbl`](../../app/cbl/CBTRN03C.cbl) | `TransactionReportService`, `TransactionReportMapper` | 133-column output with its exact edit masks. Registered divergences: [D-REPORT-EOF-DOUBLE-COUNT](#d-report-eof-double-count--the-last-transactions-amount-is-counted-once-not-twice), [D-REPORT-FINAL-CARD-BREAK](#d-report-final-card-break--the-last-card-group-is-closed-with-a-total-band-like-every-other) |
| `CBSTM03A` | batch | [`app/cbl/CBSTM03A.CBL`](../../app/cbl/CBSTM03A.CBL) | `StatementService` grouping, `StatementTextMapper` | Registered divergences: [D-2](#d-2--the-two-unchecked-statement-tables), [D-STMT-PAIRED-BLANK-NAME](#d-stmt-paired-blank-name--an-empty-middle-name-leaves-the-two-statement-artifacts-disagreeing) |
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

| COBOL paragraph | Line | Target method | What it does |
|---|---|---|---|
| `1100-OPEN-REQUEST-QUEUE` | L255 | listener container start-up | Opens the request queue |
| `1200-SCHEDULE-PSB` | L292 | transaction manager enlistment | Schedules the database access block |
| `2000-MAIN-PROCESS` | L323 | `AuthorizationRequestListener.onMessage` loop | Commits per message at **L335** |
| **`2100-EXTRACT-REQUEST-MSG`** | **L351–L382** | `CsvAuthCodec.decodeRequest` | Parses the positional payload; `2100-EXIT` at **L382** |
| **`3100-READ-REQUEST-MQ`** | **L386–L434** | `AuthorizationRequestListener.receive` | The bounded receive with its wait interval |
| **`5000-PROCESS-AUTH`** | **L438–L468** | `AuthorizationService.process` | Orchestrates the decision path |
| `5100-READ-XREF-RECORD` | L472 | `AuthorizationService.loadCrossReference` | Cross-reference lookup |
| `5200-READ-ACCT-RECORD` | L520 | `AuthorizationService.loadAccount` | Account lookup |
| `5300-READ-CUST-RECORD` | L568 | `AuthorizationService.loadCustomer` | Customer lookup |
| **`5500-READ-AUTH-SUMMRY`** | **L616–L643** | `PendingAuthSummaryRepository.find` | Reads the pending-authorization summary |
| `6000-MAKE-DECISION` | L657–L734 | `AuthorizationService.decide` | The approve/decline decision |
| `7100-SEND-RESPONSE` | L738–L782 | `OutboxPublisher.publishReply` | Builds and sends the reply; the no-syncpoint put is at **L753** |
| `8400-UPDATE-SUMMARY` | L798–L850 | `PendingAuthSummaryRepository.save` | Updates the summary segment |
| `8500-INSERT-AUTH` | L854–L935 | `PendingAuthDetailRepository.insert` | Inserts the detail segment |
| `9100-CLOSE-REQUEST-QUEUE` | L953–L979 | listener container shutdown | Closes the request queue |
| **`9500-LOG-ERROR`** | **L983** | `AuthorizationErrorLogger.emit` | The centralised error emission, invoked from **fourteen** call sites |

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
* Refactoring Rationale: the target replaces direct cleartext comparison with a
  managed identity boundary that carries no credential column.
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

#### D-5 — the reply published outside the commit

* **Baseline behaviour.** In
  [`COPAUA0C.cbl`](../../app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl) the
  consumer commits its database work at **L335** (`EXEC CICS SYNCPOINT`) and publishes
  its reply with a no-syncpoint put at **L753**, so the two are not part of one
  recoverable unit. A failure between them leaves persisted data that says a decision
  was made and a reply produced, with no reply ever having been sent.
* **Target behaviour.** The reply is written to an outbox row **inside the same
  transaction** as the authorization decision and published from that outbox
  afterwards, so a reply exists for every committed decision.
* Refactoring Rationale: the target replaces the split commit-and-publish boundary
  with an outbox written atomically with the decision.
* **Why the difference is accepted.** The window is observable to the requester rather
  than internal: the caller waits for a reply that the authorizer's own data says was
  produced, and no retry can recover it because the request has already been consumed.
  Preserving the window would mean preserving a state in which the two endpoints
  disagree about whether a decision was communicated. The wire format, the field
  order, the delimiter, the correlation identity and the reply routing are all
  preserved exactly, so nothing about the reply *as a message* changes — only the
  guarantee that it is sent.
* **Where it is verified.** Repository and publisher tests asserting idempotent
  delivery. [`messaging-contracts.md`](messaging-contracts.md) owns the consumer
  semantics and records that the outbox is a target obligation rather than an existing
  baseline behaviour.

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
* **Why the difference is accepted.** Co-locating the data makes the atomicity
  requirement expressible with strictly weaker machinery, and no observable state is
  added: the operation is atomic before and after, so a reader of the data can never
  tell which mechanism produced it. **Note that *exposing* distributed transactions is
  explicitly out of scope ([§10](#10-out-of-scope-including-the-baselines-own-stated-future-work)),
  and this entry is the opposite of that** — it records the removal of a distributed
  commit, not the exposure of one.
* **Where it is verified.** Integration tests asserting atomic rollback across the
  co-located tables. [`messaging-contracts.md`](messaging-contracts.md) and
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
  formats in the same two slots and takes the instant as an **optional prop**. The
  application shell is expected to supply a **server-derived** instant, which is the
  only value that reproduces the baseline's single-clock property. When the prop is
  omitted the component reads the **browser's** clock and formats it in the
  **browser's** zone — two substitutions rather than one, because a client machine
  can be both skewed and in a different zone from the service.
* Trade-offs: the divergence is bounded to the omitted case rather than removed,
  and it is bounded at the one prop that controls it.
* **Why the difference is accepted.** **There is no region clock left to read.** The
  baseline's single clock was a property of the single region every terminal attached
  to; the target has no region but a horizontally-scaled set of stateless handlers —
  the shape that the state elimination in
  [§7.3](#73-structural-divergences-that-are-not-defects) is what makes possible — so
  no one machine's wall clock is the authority any more, and the single-clock
  property can be restored only by having the *service* supply the instant, which is
  precisely what the prop exists for. The named consequence of omitting it
  is user-visible and is stated rather than hidden: two operators looking at one
  record across a midnight boundary can read two different dates in the
  eight-character date slot. Rendering the slots **blank** until a caller supplies an
  instant was the alternative and was rejected, because the baseline never showed an
  empty date, so a blank slot trades a small documented inaccuracy for a visible
  absence. Everything except the clock and the zone is preserved: the two formats,
  their separators, their widths and their positions are carried across verbatim, so
  the *shape* of what is rendered never differs — only which clock produced it.
* **Where it is verified.** Component tests render the band with an **injected**
  instant and assert both formats, so the format contract is asserted independently
  of any clock; the default path is verified by the deliberate **absence** of an
  assertion about it, because a test asserting a wall-clock value would be asserting
  the test runner's clock rather than the component's behaviour. Like
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

### 7.4 Divergences claimed by shipped code

Every entry below is claimed as registered by a comment or docstring in shipped
source, and all **thirty-three** are cited **by identifier**, the identifier here being the
identifier used there character for character. They reached that state by three routes,
recorded because the routes explain the difference in tone between them. Some were cited
by identifier from the outset. Others were cited generically as "registered" or
"documented" without naming anything, or asserted the divergent behaviour while claiming
nothing at all, so an identifier was assigned here and added at each citing site, because
a claim of registration that names nothing cannot be checked, and a difference that claims
nothing cannot be found. The `D-REFDATA-*` entries that close the section were authored
the other way round — identifier first, then cited from the published reference contract —
which is the discipline this section asks of everything added after them. Assumptions:
thirty-three is a measured count of the `####` headings in this section and not a running
tally kept by hand, so a reader adding an entry updates one number here and nothing else.

Refactoring Rationale: a `D-REFDATA-CATEGORY-BATCH` entry stood here and has been
withdrawn. It registered the maintenance-action batch as additionally addressing
transaction categories, which the published contract does not do: the baseline's three
statements all name `CARDDEMO.TRANSACTION_TYPE` and its fifty-three-byte input record has
no field able to carry a category, so the `catCd` member was removed from
`MaintenanceAction` rather than registered — category writes already reach the category
operations on the same contract, so nothing was lost by removing it. A register entry for
a capability the contract does not publish is worse than no entry, because it is the one
document a reader consults to learn what the target does differently.

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

#### C-ROUNDING — interest accrual truncates in the baseline and rounds half-up here

* **Baseline behaviour.** [`CBACT04C.cbl`](../../app/cbl/CBACT04C.cbl) computes the
  monthly interest at **L464-L465** and stores the quotient into a fixed-scale field
  with **no `ROUNDED` phrase**; a search for that phrase across all 652 lines of the
  program returns no match. A store into a fixed-scale item without it discards the
  surplus digits, so the baseline behaviour is **truncation toward zero**.
* **Target behaviour.** `Money` implements half-up as the general contract and exposes
  the accrual entry point with the rounding mode as an explicit parameter, so a caller
  states which of the two documented behaviours it wants and a test can assert either.
* **Category.** Documented divergence — arithmetic rounding mode.
* **Why the difference is accepted.** The plan requires half-up for money, and the
  difference is exactly one cent and only on a quotient landing exactly on a half
  cent. On the vectors the reference fixtures carry the two modes agree: a balance of
  `1000.00` at a rate of `2.50` yields `2.08333...` and both return `2.08`. They part
  company only at an exact half cent, as with `1000.80` at `2.50`, where the quotient
  is `2.0850` exactly, truncation returns `2.08` and half-up returns `2.09`. Making
  the mode a parameter rather than a silent choice is what keeps the difference
  visible at the call site instead of buried in a constant.
* **Where it is verified.** `MoneyTest` asserts both modes on the half-cent vectors
  above, and the multiply-then-divide order is asserted separately
  ([§3.2](#32-cbact04c--interest-accrual)).
* **Files.** `services/common-lib/src/main/java/com/carddemo/common/money/Money.java`,
  `services/common-lib/src/main/java/com/carddemo/common/money/package-info.java`.

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
  limit.
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
  pattern and the record constraint together, so a narrowing on either side fails.
* **Files.**
  `services/transaction-service/src/main/java/com/carddemo/transaction/dto/TransactionAddRequest.java`.

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

#### D-LASTKEY-RECEIVED — the page cursor names only rows the caller received

* **Baseline behaviour.** After filling a page of seven, `COCRDLIC.cbl` captures the
  seventh row's key at **L1194-L1195**, then issues one further read at **L1197** purely
  to discover whether anything follows — and on finding a row it **overwrites** the
  captured key with that row's key at **L1212-L1214**. The value it retains therefore
  identifies the **eighth** record on a page of seven, a row the terminal never
  displayed.
* **Target behaviour.** The page envelope's `lastKey` names the last row the caller
  **actually received**, and `hasNext` carries the look-ahead result separately.
* **Category.** Documented divergence — cursor identity.
* **Why the difference is accepted.** Splitting the two facts is what makes the cursor
  verifiable: every token a client is given corresponds to something it holds, so a
  client can check a returned cursor against its own page rather than trusting a value
  that names a row it never saw. The baseline conflated them because one field carried
  both, and the observable paging behaviour is unchanged — the same rows appear on the
  same pages, in the same order, with the same next-page availability.
* **Where it is verified.** `CardApiContractTest` asserts all seven page members are
  required and that `lastKey` is nullable exactly when `items` may be empty;
  `PageResponse`'s own tests assert the look-ahead sets `hasNext` without moving the
  cursor.
* **Files.** `services/card-service/src/main/resources/openapi/card-api.yaml`,
  `services/common-lib/src/main/java/com/carddemo/common/web/PageResponse.java`.

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
  managed user pool whose seeded users are provisioned with temporary passwords, which
  makes this exchange the **first** thing every provisioned user does. Declining it
  would leave every seeded user unable to sign on at all. Because there is no baseline
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
* **Target behaviour.** The ETL layout catalogue resolves both segments through an
  explicit **disclosable allowlist**: a field's raw bytes may appear in a decode
  diagnostic only when it is named as a closed-domain code, a date or time, a counter,
  the account key or the trailing pad. Everything else — the card number, the card
  expiry, both amounts, the merchant identity, name, city and postal code, the
  transaction identity, the customer identifier and the four stored balance and limit
  amounts — is withheld, and a field added to either segment later is withheld until it
  is deliberately named.
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
* **Where it is verified.** The two layouts' geometry checks still close at 100 and 200
  bytes after the policy is applied, and the resulting per-field classification matches
  `CsvAuthCodec.SENSITIVE_FIELD_NAMES` name for name, merchant STATE excepted in both.
* **Files.** `data-migration/src/carddemo_migration/copybook/layouts.py`,
  `data-migration/src/carddemo_migration/copybook/ebcdic_codec.py`.

#### D-AUTH-AMOUNT-TOLERANT-READ — a declared-width amount token is read whole, not truncated

* **Baseline behaviour.**
  [`CCPAURQY.cpy`](../../app/app-authorization-ims-db2-mq/cpy/CCPAURQY.cpy) **L27**
  declares `PA-RQ-TRANSACTION-AMT PIC +9(10).99`, which is **fourteen** characters.
  The only consumer of that wire,
  [`COPAUA0C.cbl`](../../app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl), receives
  ordinal nine of its `UNSTRING` at **L364** into `WS-TRANSACTION-AMT-AN PIC X(13)`
  declared at **L63** — **thirteen** — and converts it with `FUNCTION NUMVAL` at
  **L376-L377**. An alphanumeric move into a shorter item drops the **last** character,
  and `NUMVAL` accepts one fraction digit as readily as two, so a producer emitting the
  declared width has its amount received as a different, entirely plausible value: an
  emitted `+0000000100.99` is acted on as **100.90**. Nothing is raised anywhere.
* **Target behaviour.** `CsvAuthCodec` is a **tolerant reader and a strict writer**. It
  accepts a fourteen-character token and parses every character of it, so the producer's
  intent survives; it **emits** thirteen (`REQUEST_MONEY_WIDTH`), so its canonical wire
  length is `REQUEST_WIRE_LENGTH` = 169 and the reference receiver is never handed a
  token it would truncate; and a token that has already been truncated to thirteen
  characters is **refused by name**, because the parser requires exactly two fraction
  digits.
* **Category.** Documented divergence — a silent truncation is not reproduced.
* **Why the difference is accepted.** Three behaviours were available and only one is
  defensible. Alternatives Considered: reproducing the truncation, so that a
  fourteen-character token yields the same amount the reference would act on. Rejected
  outright — it divides an amount by ten in the cents position with no diagnostic, which
  is exactly the class of defect the fixed-point rule exists to prevent. Alternatives
  Considered: refusing the fourteen-character token, on the grounds that the emitted wire
  is thirteen. Rejected because it would dead-letter a payload whose intent is
  unambiguous, purely because a third party built to the width this repository publishes
  as the layout — and no request producer exists here to correct. Reading it whole is the
  only option that neither loses the value nor loses the message. The asymmetry is
  deliberate: tolerance is confined to the inbound direction, so nothing this service
  emits can be truncated by the reference receiver.
* **Where it is verified.** `AuthRequestWireFixtureTest` reads both committed fixtures
  from the classpath and asserts that the three 169-byte payloads decode with a
  thirteen-character amount, that the 170-byte declared-width payload decodes to the full
  value it spells, that a token truncated to thirteen characters is refused by name, and
  that re-emitting the decoded declared-width payload produces exactly 169 bytes with a
  thirteen-character amount.
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
  the report. Assumptions: only the mapper half of this divergence is asserted today. The
  emitting sequence that has to CALL the encoder for the last group belongs to the statement
  and report emitting service, which is not yet authored, and this entry is the record that
  the obligation is owed there.
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
  at **L230-L243** takes **no** action argument. It re-reads the detail segment at **L234**
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
  test, which also asserts that no third character is admitted.
* **Files.** `services/authorization-service/src/main/resources/openapi/authorization-api.yaml`.

#### D-AUTH-REQUEST-WINDOW — the declared five-hundred-request bound is enforced, not the observed 501

* **Baseline behaviour.**
  [`COPAUA0C.cbl`](../../app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl) declares
  `05 WS-REQSTS-PROCESS-LIMIT PIC S9(4) COMP VALUE 500` at **L40**, increments its
  counter after each request at **L332**, and tests it with `>` rather than `>=` at
  **L339**. Counts one through five hundred therefore all take the `ELSE` and read
  another request at **L342**; only count **501** sets the loop-end flag at **L340**.
  The declared limit is 500 and the observed behaviour is 501, and the difference
  follows entirely from the order of the increment and the comparison rather than from
  any rule the program states.
* **Target behaviour.** `AuthorizationRequestListener` counts every request it takes
  off the queue and closes its processing window on exactly
  `DEFAULT_REQUEST_PROCESS_LIMIT` = **500**, overridable by
  `carddemo.messaging.request-process-limit`. Closing the window means closing
  **intake**: `ContainerCyclingWindowBoundary` stops the listener container and starts
  it again, so the request that would have been the 501st of the window is never
  received and stays on the queue until the next window opens.
* **Category.** Documented divergence — an off-by-one in the reference implementation
  is not reproduced.
* **Why the difference is accepted.** Both the field name and its literal state five
  hundred, so five hundred is the rule and 501 is what the code happens to do;
  reproducing 501 would promote an implementation defect into a contract. Alternatives
  Considered: refusing the 501st request inside the handler rather than closing intake.
  Rejected because neither outcome available there is correct — throwing sends a
  legitimate request toward the dead-letter queue over a bound that has nothing to do
  with the request, and returning without handling deletes a request nobody answered.
  Only closing intake before the receive keeps the bound and the message both intact,
  which is exactly what the reference program's test-before-next-read achieves.
  Alternatives Considered: leaving the bound unimplemented and treating the container's
  concurrency and poll settings as its replacement. Rejected because those settings
  bound work **in flight** rather than work **completed**, so they express a different
  quantity: a consumer configured for ten concurrent messages still handles an
  unbounded number of them. Trade-offs: cycling a container is heavier than the
  reference program's run simply ending, and the cost is a brief pause in intake at
  each boundary. Every failure path in the boundary therefore leaves intake **open** —
  a window that failed to reopen would halt every authorization in the system, which is
  strictly worse than a window that ran long. Trade-offs: the counter is per task
  rather than per queue, so the platform-wide figure is the quota times the task count;
  that matches the reference system, where the limit bounded one running program and the
  queue could trigger more than one, and a shared counter would need a coordination
  round trip on the hot path of every authorization.
* **Where it is verified.** `AuthorizationRequestListenerTest` asserts that no window
  closes before the quota, that the window closes on the quota-th message carrying the
  quota as its reported count, that successive windows each close on their own full
  quota so the counter resets, that a dropped stale request and a request whose handling
  threw each occupy their place in the window, that a non-positive window size is refused
  at construction, and that the compiled default is five hundred rather than 501.
* **Files.**
  `services/authorization-service/src/main/java/com/carddemo/authorization/service/AuthorizationRequestListener.java`,
  `services/authorization-service/src/main/java/com/carddemo/authorization/service/RequestWindowBoundary.java`,
  `services/authorization-service/src/main/java/com/carddemo/authorization/service/ContainerCyclingWindowBoundary.java`,
  `docs/architecture/messaging-contracts.md`.

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

<sub>Apache-2.0 · Authoritative artifact-to-target matrix, retirement register and
behavioural-divergence register for CardDemo. The baseline under `app/**` is cited
throughout and never modified. Convention:
[`docs/CODE_DOCUMENTATION_STANDARD.md`](../CODE_DOCUMENTATION_STANDARD.md).</sub>
