# CardDemo `reference-service` — the reference-data bounded context

> **Purpose.** Build, run, test, configure and operate the reference-data service:
> the migrated form of the Db2 transaction-type screens, the batch reference
> updater and the synchronous half of the date conversion. This page is the per-package
> contract for anyone who has to compile this module, start it, probe it, change
> its schema or reason about the behaviour it is not allowed to lose.
>
> **Source of truth.** The reference-only COBOL baseline and its Db2 DDL, cited by
> path and line throughout: `app/app-transaction-type-db2/cbl/COTRTLIC.cbl`,
> `app/app-transaction-type-db2/cbl/COTRTUPC.cbl`,
> `app/app-transaction-type-db2/cbl/COBTUPDT.cbl`, `app/app-vsam-mq/cbl/CODATE01.cbl`,
> `app/cbl/CSUTLDTC.cbl`, `app/cbl/CBACT04C.cbl`,
> `app/app-transaction-type-db2/ddl/TRNTYCAT.ddl`,
> `app/app-transaction-type-db2/ddl/XTRNTYCAT.ddl`, the record layouts
> `app/cpy/CVTRA02Y.cpy`, `app/cpy/CVTRA03Y.cpy` and `app/cpy/CVTRA04Y.cpy`, the
> lookup asset `app/cpy/CSLKPCDY.cpy`, and the seed data under `app/data/ASCII/`.
> Where this page and the baseline disagree, **the baseline wins and this page is
> the defect.** The whole `app/**` tree is read here and modified nowhere.
>
> **Governing convention.** [`docs/CODE_DOCUMENTATION_STANDARD.md`](../../docs/CODE_DOCUMENTATION_STANDARD.md),
> whose Markdown clause binds this file: a header naming purpose and source of
> truth, a reason under one of four named categories for every non-obvious
> assertion, and the `# WHAT:` / `# WHY :` idiom in every fenced command block.

This service owns the PostgreSQL `reference` schema. Two of its properties are
load-bearing for **other** contexts, which is why they are stated before anything
else and repeated in [§12](#12-behavioural-contracts-that-must-not-regress):
deleting a transaction type that still has categories must answer **HTTP 409**,
never HTTP 500; and the `'DEFAULT'` disclosure-group row must exist, because
`app/cbl/CBACT04C.cbl` abends the interest batch without it.

Before relying on parity claims here, read [§13](#13-known-limitations) — **no
golden master covers this module's online paths**, and that limit is real.

---

## 1. Overview

`reference-service` is the reference-data bounded context of the CardDemo
mainframe-to-AWS migration. It serves transaction-type and category maintenance,
disclosure-group interest-rate lookup, the seeded United States address
allow-lists, and date conversion — over REST, and over REST only. This module
consumes no queue and publishes no message.

Refactoring Rationale: it did consume one. The baseline drives BOTH of its inquiry
programs from a single request destination —
`DEFINE QLOCAL('CARDDEMO.REQUEST.QUEUE')` at
[`app/app-vsam-mq/README.md`](../../app/app-vsam-mq/README.md) L53, aliased to CICS
as `MQQUEUE(CARDREQ)` at L71 — and the migrated topology provisions that one queue
rather than one per consumer. A queue admits exactly **one owning consumer**,
because a receive hides the message from every other consumer rather than
delivering a copy to each, so two consumers on one queue do not share work: each
takes work only the other can answer. The owner is `account-service`'s
`InquiryMessageListener`, which dispatches on the request's four-character function
code — `INQA` to the account inquiry, `DATE` to the shared reply renderer in
`common-lib`, anything else to `COACCT01`'s own invalid-parameters reply. What stays
here is the date **evaluation** of `CSUTLDTC`, answered synchronously, which is a
different question: the queue route emits the current system date and time and reads
no field of its request, while this route judges a date a caller submits. The
baseline keeps them apart too — a search for `CSUTLDTC` across all 524 lines of
`CODATE01.cbl` returns zero occurrences.

### 1.1 What this module replaces

| Baseline program | Path | CICS transaction | Becomes here |
|---|---|---|---|
| `COTRTLIC` | `app/app-transaction-type-db2/cbl/COTRTLIC.cbl` | `CTLI` | Transaction-type and category browse, keyset-paged |
| `COTRTUPC` | `app/app-transaction-type-db2/cbl/COTRTUPC.cbl` | `CTTU` | Transaction-type and category add, edit and delete |
| `COBTUPDT` | `app/app-transaction-type-db2/cbl/COBTUPDT.cbl` | none | A reference-maintenance **service method**, reached over the API |
| `CODATE01` | `app/app-vsam-mq/cbl/CODATE01.cbl` | `CDRD` | A date-conversion REST endpoint here; its **queue** route is answered by `account-service`, the single owner of the shared inquiry request queue |
| `CSUTLDTC` | `app/cbl/CSUTLDTC.cbl` | none | Already migrated into `common-lib` as `DateEditValidator`; **this module calls it and does not re-implement it** |

The transaction and program bindings above are the baseline's own: `CTLI` is
defined at `app/app-transaction-type-db2/csd/CRDDEMOD.csd` L25 and bound to
`PROGRAM(COTRTLIC)` at L26, `CTTU` at L35 and L36, and `CDRD` at
`app/app-vsam-mq/csd/CRDDEMOM.csd` L27 and L28.

Refactoring Rationale: `CSUTLDTC` is a dynamically-`CALL`'d subprogram in the
baseline, built as a shared module rather than as a main program — the parity
suite compiles it with `cobc -m` for exactly that reason
([`tests/README.md`](../../tests/README.md) §5.2). A subprogram that several
callers link at run time has one implementation by construction, so the faithful
target shape is one shared `DateEditValidator` in `common-lib` that this module
consumes. A per-service copy would turn one set of date-edit rules into eight
that drift independently, which is the property the baseline did not have.

Assumptions: `app/app-vsam-mq/csd/CRDDEMOM.csd` defines two transactions and only
one of them is this context's. Its L17 defines `TRANSACTION(CDRA)` and L18 binds
`PROGRAM(COACCT01)` — the account-inquiry consumer, which belongs to
`account-service`. Both transactions arrive over the same queue-driven pattern and,
in the baseline, over the same request queue, which is why the migrated queue half
of BOTH is answered in that one module and neither is answered here.

---

## 2. Bounded context and ownership

Maven coordinates, verified against [`pom.xml`](pom.xml):

| Property | Value |
|---|---|
| Parent | `com.carddemo:carddemo-services:1.0.0-SNAPSHOT` |
| Artifact | `com.carddemo:reference-service:1.0.0-SNAPSHOT`, packaging `jar` |
| Java package root | `com.carddemo.reference` |
| Sibling module dependency | `common-lib`, and nothing else |
| Owned schema | `reference` |

The one sibling dependency arrives as two artifacts: the main jar, and the
`test-jar` that carries the shared ArchUnit layering rules. It is one module, not
two.

### 2.1 The six owned tables

| Table | Holds | Baseline source |
|---|---|---|
| `reference.transaction_types` | Transaction type codes and descriptions | `app/cpy/CVTRA03Y.cpy`, `app/data/ASCII/trantype.txt` |
| `reference.transaction_categories` | Type and category pairs | `app/cpy/CVTRA04Y.cpy`, `app/data/ASCII/trancatg.txt` |
| `reference.disclosure_groups` | Interest rate by group, type and category | `app/cpy/CVTRA02Y.cpy`, `app/data/ASCII/discgrp.txt` |
| `reference.us_phone_area_codes` | Area-code allow-list with its classification | `app/cpy/CSLKPCDY.cpy` |
| `reference.us_states` | State-code allow-list | `app/cpy/CSLKPCDY.cpy` |
| `reference.us_state_zip_prefixes` | State plus first two ZIP digits allow-list | `app/cpy/CSLKPCDY.cpy` |

### 2.2 Two other contexts read this data — over the API or the schema, never through code

- **`batch-service`** reads the disclosure-group rate to calculate interest. That
  rate is one of the two operands of `app/cbl/CBACT04C.cbl` L464–L465.
- **`account-service`** reads the three allow-list tables to validate an address.

Neither may depend on this module as a Maven artifact, and neither may import a
type from `com.carddemo.reference`. The prohibition is mechanical rather than
conventional: the shared ArchUnit rules in `common-lib`'s test artifact forbid
cross-service domain imports, and they run in every module's own Surefire
execution, so a violation fails the build instead of a review.

Refactoring Rationale: the migration's candidate decomposition proposed a separate
"Transaction-Type Ref" service, and it was folded into this module because
*"each is an alternate transport over data another context already owns —
standing them up as separate services would have split ownership of a single
table across two deployables, which is the failure mode bounded contexts exist to
prevent."* The same reasoning keeps the date conversion here rather than in a
service of its own: it is a second front door onto rules this context already
fronts, not a second owner of data.

Assumptions: schema, role and grant bootstrap is **not** owned here. It belongs to
[`data-migration/sql/V0__schemas_and_roles.sql`](../../data-migration/sql/V0__schemas_and_roles.sql),
which creates the eight schemas and the per-service roles. This module's Flyway
migrations create and seed tables **inside** an existing `reference` schema and
author no `CREATE SCHEMA`, no `CREATE ROLE` and no `GRANT`. A migration that
tried to would need privileges the runtime role deliberately does not hold.

---

## 3. Prerequisites

| Tool | Version | Needed for |
|---|---|---|
| JDK | Amazon Corretto **21** | Compiling and running this module; the enforcer rule requires `[21,)` |
| Maven | **3.9.16** or newer | The reactor build; the enforcer rule requires `[3.9.0,)` |
| Container runtime | any Docker-compatible daemon | The twelve Testcontainers-backed `*IT` classes, and building the image |
| PostgreSQL | **17** reachable | Running the service locally; Flyway applies both migrations at startup |
| Python | **3.13** | Only for the repository-wide Rule 1 gate described in [§14](#14-documentation-gate) |

Every version above is the one the toolchain is pinned to. The database driver is
`org.postgresql:postgresql` 42.7.13 and Flyway is 13.0.0, both managed centrally
in [`../pom.xml`](../pom.xml) rather than declared here.

Alternatives Considered: restating each managed dependency version in this module's
own POM, which would let a reader see them without opening the parent. Rejected
because two declarations of one version drift independently, and the enforcer rule
that bans dynamic versions cannot detect a stale duplicate — it only checks that a
version is fixed, not that it is the same fixed version the other eight modules
resolve. Naming the parent as the single place they live keeps a reader one hop away
from a value that cannot disagree with itself.

---

## 4. Build

```bash
# WHAT: build every module in the services reactor, run its gates and run its
#       tests, in dependency order.
# WHY : Assumptions: this is the canonical command, and it needs no prior
#       `install`. `common-lib` is unpublished -- no registry holds a
#       1.0.0-SNAPSHOT of it -- and it is the first of the nine modules the
#       aggregator declares, so the reactor compiles it before this module and
#       resolves the dependency from the same pass rather than from a repository.
mvn -B -f services/pom.xml clean verify
```

```bash
# WHAT: build this module together with the module it depends on, and nothing
#       else.
# WHY : Assumptions: `-pl` selects one project and `-am` adds the projects that
#       selection depends on, so `common-lib` and the parent are built from the
#       working tree. Trade-offs: this is the loop to use while changing this
#       module, and it proves strictly less than the reactor build above -- it
#       exercises none of the other seven services, so a change here that breaks
#       a shared contract still passes.
mvn -B -f services/pom.xml -pl reference-service -am clean verify
```

```bash
# WHAT: fire this module's documentation gate on its own, compiling nothing.
# WHY : Assumptions: the Checkstyle execution is bound to Maven's `validate`
#       phase, the first phase of every lifecycle, so it runs without a compile
#       step and cannot be reached around by skipping later phases. This is the
#       fast feedback loop while authoring Javadoc.
# WHY : Assumptions: a single-module invocation resolves the shared Checkstyle
#       configuration correctly even though that configuration lives two
#       directories above this one. Two file-activated profiles in
#       `services/pom.xml` -- `checkstyle-config-beside-reactor-root` and
#       `checkstyle-config-one-level-above-reactor-root` -- pick the right depth
#       from the module's own base directory, so the reactor build and this
#       command read the same rules.
mvn -B -f services/reference-service/pom.xml validate
```

Surefire runs the classes matching `*Test`; Failsafe runs those matching `*IT`.
Both tiers and their report locations are described in [§6](#6-test).

### 4.1 What must not be added to this module

| Prohibited | Why it stays out |
|---|---|
| Lombok | Generated accessors cannot carry the Javadoc the documentation gate demands at `private` scope |
| MapStruct | The mappers here are a hand-written anti-corruption layer; each field decision needs a rationale at the mapping site that a generated mapper cannot hold |
| Any resilience or circuit-breaker library | Retry lives in Spring Framework core, configured with `maxRetries` and enabled by `@EnableResilientMethods`. The older `@EnableRetry` and the `maxAttempts` attribute belong to libraries this tree does not use |
| Redis, ElastiCache, Kafka, Kinesis, read replicas | The baseline has no cache, no stream and no replica; none is required for parity |
| A dependency on a sibling **service** module | See [§2.2](#22-two-other-contexts-read-this-data--over-the-api-or-the-schema-never-through-code) |
| A second `@RestControllerAdvice` | The error model is inherited from `common-lib`; see [§12.1](#121-on-delete-restrict-surfaces-as-http-409-never-500) |
| Spring Batch and a `BatchConfig` | `COBTUPDT` becomes a service method, not a job-repository owner. Spring Batch belongs to `batch-service` |

There is no `Makefile`, no `docker-compose.yml`, no `settings.xml`, no Maven
wrapper and no module-level `.dockerignore` in this module. Every command on this
page uses the tools listed in [§3](#3-prerequisites) directly.

---

## 5. Run locally

A local run needs, in the order the startup sequence requires them: a reachable
**database** — the migration credential is used before the runtime one — a
reachable **token issuer**, whose discovery document is fetched while the context
is being built rather than on the first request, and a
free port. **No queue, no queue endpoint and no region are needed**: this module
builds no queue client.

Every setting is supplied by name from an out-of-band file. **No value of any of
them appears anywhere in this repository**, and [§8](#8-configuration-and-environment-variables)
lists the names.

```bash
# WHAT: create the local settings file with owner-only permissions before writing
#       anything into it, then confirm git cannot see it.
# WHY : Assumptions: the name must begin `.env.` because `.gitignore` L223 ignores
#       `.env.*`; the `git check-ignore -v` line prints the matching rule and its
#       line number, so the claim is proven rather than asserted. A name such as
#       `reference-service.env` matches no ignore rule and would be staged by
#       `git add -A` together with the database password.
# WHY : Assumptions: `umask 077` is applied BEFORE the file is created rather than
#       corrected afterwards, because a `chmod` after the fact leaves a window in
#       which the file was group- and world-readable.
umask 077
touch .env.reference-service.local
git check-ignore -v .env.reference-service.local
```

```bash
# WHAT: build the bootable jar, then start it with the local settings applied to
#       that one process only.
# WHY : Assumptions: the jar produced by the Boot repackage goal is
#       self-contained, so no class path is assembled at launch.
# WHY : Trade-offs: the settings are exported into a subshell rather than into the
#       interactive shell, so a credential does not outlive the run or leak into
#       the shell history of the next command. The cost is that the run cannot be
#       restarted without re-entering the subshell.
# WHY : Assumptions: SERVER_SSL_ENABLED is set false for a LOCAL run only.
#       `application.yml` enables TLS and opens a PKCS#12 keystore whose password
#       placeholder has no fallback, because the deployed target group speaks
#       HTTPS to the task. No keystore exists on a developer machine, so the
#       process would fail while trying to open one. SERVER_ADDRESS binds the
#       listener to loopback, which is what makes disabling TLS defensible here:
#       nothing off-host can reach the port.
# WHY : Refactoring Rationale: AWS_REGION is NOT exported here and is no longer
#       required to start. It was, because a declared SQS starter builds its client
#       while the context is constructed; the starter is withdrawn from this module
#       with its consumer, so a local run needs no region and no credential at all.
mvn -B -f services/pom.xml -pl reference-service -am package

( set -a; . ./.env.reference-service.local; set +a
  SERVER_SSL_ENABLED=false SERVER_ADDRESS=127.0.0.1 \
  java -jar services/reference-service/target/reference-service-1.0.0-SNAPSHOT.jar )
```

Flyway applies **all three** migrations during startup — `V1__reference.sql` creates
the six reference tables, `V2__seed_reference.sql` seeds them, and
`V3__reference_inquiry_reply_ledger.sql` creates the one operational table the
date-inquiry exchange records its answers in. The actuator health endpoint reports
not-ready until that finishes.

Refactoring Rationale: three inquiry queue names, a region and a
`queue-not-found-strategy` pin used to be startup requirements of this module and
are all withdrawn. They existed for the queue consumer described in
[§1](#1-overview), which now lives in `account-service` because the single shared
request queue has a single owner. `ReferenceServiceStructureTest` asserts that no
member of this module binds a queue listener AND that the queue-listener annotation
does not resolve from this module's classpath at all, so the withdrawal is measured
rather than described — re-adding a consumer here would have to restore the starter,
the configuration keys and a queue receive grant together, each of which is a
visible decision.

---

## 6. Test

<!-- test-inventory: 34 tests + 12 integration tests -->
**46** test classes across nine packages: **34** matching `*Test`, run by
Surefire, and **12** matching `*IT`, run by Failsafe. Those two totals are
machine-checked — `ServiceReadmeInventoryTest` in `common-lib` parses the HTML
comment above this paragraph and re-counts both tiers from this module's test
tree, so adding a test class without updating the marker fails the build.

| Package | Classes | Package | Classes |
|---|---|---|---|
| `service` | 12 (11 `*Test`, 1 `*IT`) | `api` | 9 |
| `repository` | 10 (all `*IT`) | `config` | 5 |
| `mapper` | 3 | `dto` | 2 |
| `fixtures` | 2 | `domain` | 1 |
| root `com.carddemo.reference` | 1 | | |

Refactoring Rationale: four classes left these counts and one arrived, on the one
topology fact recorded in [§1](#1-overview) rather than on four decisions.
`config/SqsConfigTest` covered a queue client this module no longer builds;
`mapper/DateInquiryReplyMapperTest` covered a renderer that moved to `common-lib`,
where `DateInquiryReplyCodecTest` now covers it;
`service/DateInquiryMessageListenerTest` covered a consumer that moved to
`account-service`, where `InquiryMessageListenerTest` covers it — including the date
dispatch; and `service/ReferenceQueueConsumerContractTest` asserted that exactly ONE
consumer was bound here, a claim now inverted inside
`service/ReferenceServiceStructureTest`, which asserts that none is and that the
queue-listener annotation does not resolve from this module at all.
`service/DateConversionFlowContractTest` arrived in place of
`DateConversionMessageListenerTest`, holding the same flow's surviving properties —
the withdrawal itself, the copybook-versus-codec offset agreement, the two
ten-character date pictures, the delegation of every rule to the shared validator,
and the two separate four-character codes — without driving a transport.

One further file under `src/test/java` is deliberately not in those counts:
`repository/ReferencePersistenceBase.java` is the shared Testcontainers base
class, and it matches neither suffix because it carries no test case of its own.

```bash
# WHAT: run both test tiers for this module and the module it depends on.
# WHY : Assumptions: `verify` rather than `test`, because Failsafe binds to the
#       `integration-test` and `verify` phases. Stopping at `test` runs 36 of the
#       46 classes and skips all TEN Failsafe classes -- every persistence
#       assertion in the module, including the referential refusal and the padded
#       `DEFAULT` seed, which are the two properties this schema exists to
#       preserve. A container runtime is required.
mvn -B -f services/pom.xml -pl reference-service -am verify
```

```bash
# WHAT: run the unit tier only, with no container runtime available.
# WHY : Trade-offs: this is the loop for a machine with no Docker daemon, and it
#       is honest about proving less -- it cannot execute a single `*IT` class, so
#       it cannot observe the foreign key or the seed at all. Run `verify` before
#       pushing.
mvn -B -f services/pom.xml -pl reference-service -am test
```

Refactoring Rationale: the persistence tier is tested against a real PostgreSQL
container rather than an in-memory database. An in-memory engine cannot honour
`ON DELETE RESTRICT` with PostgreSQL's semantics, does not raise PostgreSQL's
`23503` SQLSTATE, and is not a dialect Flyway's PostgreSQL support targets — and
those three behaviours are precisely what the `*IT` classes exist to prove. A
substitute engine would turn the module's two most important contracts into
assertions about the substitute.

**Report paths — do not relocate them:**

| Tier | Location |
|---|---|
| Surefire | `services/reference-service/target/surefire-reports/` |
| Failsafe | `services/reference-service/target/failsafe-reports/` |

Assumptions: `reports/` at the repository root is reserved by
[`.github/workflows/tests.yml`](../../.github/workflows/tests.yml) for the COBOL
parity oracle's JUnit XML. Writing this module's reports there would let a Java
run overwrite the oracle's output, and the oracle is the only parity evidence the
repository has.

---

## 7. Container image

```bash
# WHAT: From the repository root, build this image with:
#       docker build --file services/reference-service/Dockerfile \
#         --tag carddemo-reference-service:validation .
# WHY : Assumptions: the repository root is the build context, not `services/`,
#       because the image compiles the unpublished `common-lib` sibling from
#       source and the inherited Checkstyle documentation gate reads
#       `config/checkstyle`, which sits OUTSIDE the services reactor. Every COPY
#       that reads from the CONTEXT is repository-root-relative.
# WHY : Alternatives Considered: narrowing the context to `services/`, the Maven
#       reactor root, which would send the daemon a far smaller tree. Rejected on
#       measurement rather than preference: `config/checkstyle` and `config/docker`
#       both sit outside `services/`, so under that context the Checkstyle COPY
#       and both `config/docker` COPYs resolve to nothing and the build fails
#       outright -- and the only way to make it succeed would be to drop the
#       documentation gate this module is required to run. It is also the context
#       that both pipelines pass for all eight service images, so this file and
#       the pipelines agree about one root.
# WHY : Trade-offs: the accepted cost is a genuinely larger context. The committed
#       root `.dockerignore` removes git metadata, build output and tool caches,
#       but it does NOT exclude the reference-only trees, so `app/`, `tests/` and
#       `samples/` are still sent to the daemon on every build. That is stated
#       plainly because a reader who believed they were excluded would draw the
#       wrong conclusion about what adding an exclusion here would cost.
docker build --file services/reference-service/Dockerfile \
  --tag carddemo-reference-service:validation .
```

The command above is reproduced from the header of [`Dockerfile`](Dockerfile),
which is the authority for it.

### 7.1 Pins

| Stage | Reference |
|---|---|
| build | `maven:3.9.16-amazoncorretto-21-al2023`, digest-pinned |
| runtime | `public.ecr.aws/amazoncorretto/amazoncorretto:21.0.12-al2023-headless`, digest-pinned |

Both stages carry the multi-platform index digest beside the tag, so two builds of
one commit resolve one runtime. The build JDK matches the runtime's vendor and
Java major version, so a class-file or vendor-specific behaviour cannot first
appear after the jar is shipped.

> ⚠ **There is no Alpine variant of the Corretto image.** The repository publishes
> only `-al2` and `-al2023` tags with `headful`, `headless`, `generic` and `jdk`
> suffixes, and the highest published 21.x is `21.0.12`. An intuitive `21-alpine`
> tag *does not exist and would have failed every image build.*
>
> Assumptions: this is recorded because the substitution looks like a size
> optimisation and is in fact an unresolvable reference. It is not a smaller
> alternative to weigh — the build fails outright.

### 7.2 Shape of the image

- Two stages: neither Maven, the compiler, nor the resolved dependency cache
  reaches the shipped layer, which carries one bootable jar, the database trust
  anchor and the listener-material entry point.
- Non-root: a fixed `10001:10001` identity owns `/app` and runs the process.
- `EXPOSE 8080`, carrying both the business surface and the actuator health group
  over one TLS connector.
- A `HEALTHCHECK` probing `/actuator/health` over **HTTPS** on loopback with
  `curl --fail --insecure`.

Assumptions: the health probe speaks HTTPS and waives certificate verification,
and both halves are deliberate. TLS is enabled in the base profile, so a plaintext
request would fail the check on every healthy task; and the entry point mints a
self-signed leaf per task, so no store in the image holds its issuer and
anchoring cannot succeed even though the certificate does carry the loopback
address. The request never leaves the container's own network namespace, so there
is no second party to impersonate.

Assumptions: the same `/actuator/health` path is read by the load balancer target
group **and** by this container check. Two probes reading one endpoint cannot reach
opposite verdicts about one instance, whereas a container check on a bespoke path
could report healthy while the load balancer drained the task.

### 7.3 The 9 / 8 / 10 / 11 counts — state them correctly and do not "correct" them

| Count | Value | What it is |
|---|---|---|
| Maven modules | **9** | `common-lib` plus eight services, as `services/pom.xml` declares |
| Service Dockerfiles | **8** | One per service; **`common-lib` has none** |
| Container images this project builds | **10** | The eight services plus `ui` and `data-migration` |
| ECR repositories provisioned | **11** | Those ten plus `aws-otel-collector`, a mirror of a third-party image this project does not build |

Assumptions: `common-lib` is a library, not a deployable, which is why the first
two numbers differ; the third and fourth differ for an unrelated reason, which is
that one repository holds an image built elsewhere. `infra/modules/ecr` is the
authority for the fourth: its `repository_names` default lists eleven entries and
a validation block refuses any set that is not exactly those eleven, naming the
eleventh as "the mirror of the pinned telemetry sidecar image". The mirror exists
because the observability sidecar every task runs must be pullable without giving
the application tier egress to the public internet, which the network module does
not grant. Borrowing the image count for the module count invents a tenth Maven
module that does not exist; borrowing the module count for either registry figure
builds one image too few.

Refactoring Rationale: this table stated **10** for "Container images and ECR
repositories" as one row, and the paragraph beneath it said that borrowing the
module count "produces an eleventh phantom repository". The eleventh repository is
not a phantom -- it is provisioned, it is validated as mandatory, and a plan that
omitted it would be refused. Keeping the two figures in one row also made the
sentence unfalsifiable in the direction that matters: a reader reconciling this
README against `terraform plan` finds eleven repositories and no row that admits
one, and the available conclusions are that the plan is wrong or that this
document is. Splitting the row is what lets both be right.

### 7.4 Publishing and rollback

- Images are tagged with the **commit SHA**. A mutable moving tag such as
  `latest` is never used alone, because it makes a task definition
  non-reproducible and defeats rolling back to a known image.
- Deployment is **rolling** on the container service. There is no blue-green
  deployment and no canary.
- Rollback is the documented teardown procedure in
  [`docs/runbooks/teardown.md`](../../docs/runbooks/teardown.md).
- The registry scans on push, so there is no separate scanning step to run.
- The pipeline authenticates to the registry by short-lived federated role
  assumption. No long-lived credential exists in this repository.

Assumptions: the image build in
[`.github/workflows/services-ci.yml`](../../.github/workflows/services-ci.yml) is
validation-only — a plain `docker build`, with no builder plugin, no registry
login and **no push**. It proves the Dockerfile still builds; it publishes
nothing.

---

## 8. Configuration and environment variables

**This section lists names. It lists no values, and none exists in this
repository.** Every endpoint, credential and identifier arrives at run time from
Terraform module outputs, delivered through AWS Systems Manager Parameter Store
and AWS Secrets Manager and read through the active Spring profile.

Assumptions: *"No service hard-codes an endpoint."* The datasource location, the
queue names, the issuer and every key identifier are resolved at startup, which is
why no default for any of them exists in
[`application.yml`](src/main/resources/application.yml) and why a value in this
document would be wrong as well as unsafe.

### 8.1 Required to start

Twelve placeholders in the profiles declare **no fallback**, and one further
setting is required by a bean condition rather than by a placeholder. All
thirteen must be present or the context fails to refresh.

| Variable | Selects |
|---|---|
| `SPRING_DATASOURCE_URL` | Location of the cluster |
| `SPRING_DATASOURCE_USERNAME` | Runtime role for the `reference` schema |
| `SPRING_DATASOURCE_PASSWORD` | Credential for that role |
| `SPRING_FLYWAY_USER` | Migration role, distinct from the runtime role |
| `SPRING_FLYWAY_PASSWORD` | Credential for the migration role |
| `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI` | Issuer whose keys validate a presented token |
| `CARDDEMO_SECURITY_JWT_EXPECTED_CLIENT_ID` | App client a presented token must name |
| `CARDDEMO_SERVER_TLS_KEYSTORE_PASSWORD` | Opens the listener keystore; not read while TLS is disabled |

| `CARDDEMO_PAGINATION_CURSOR_SIGNING_KEY` | Seals keyset cursors; binds to `carddemo.pagination.cursor.signing-key` |

Assumptions: the cursor signing key is required even though it appears in no
`${...}` placeholder here, so a reader auditing the YAML for placeholders will not
find it and could reasonably conclude it is optional. Spring's relaxed binding maps
it onto `carddemo.pagination.cursor.signing-key`, which is declared in the shared
kernel's auto-configuration; the bean that reads it is conditional on that
property, and **two** controllers in this module — `TransactionTypeController` and
`TransactionCategoryController` — take that bean as a constructor parameter with no
optional wrapper. An absent value therefore fails context refresh with a
missing-bean report that names the bean rather than the setting, which is how it is
most often missed. `AddressLookupController` uses only the type's validation
constants and holds no instance of it.

### 8.2 Carrying a documented default

`CARDDEMO_COGNITO_ADMIN_GROUP_NAME`, `CARDDEMO_COGNITO_USER_GROUP_NAME`,
`CARDDEMO_DB_SSL_ROOT_CERT`, `CARDDEMO_SERVER_TLS_KEYSTORE` and
`CARDDEMO_SERVER_TLS_KEY_ALIAS` each fall back to a value declared in
`application.yml`. The admin group name defaults to the `carddemo-admin` group
whose claim guards every write; see [§8.5](#85-security).

One further setting deserves its own note. `CARDDEMO_ONLINE_WRITES_PARAMETER`
binds by relaxed binding to `carddemo.online-writes.parameter`, and when it is
absent the write-gate beans **remove themselves silently**: the administrative
writes here keep accepting traffic during the batch window with nothing in the log
to say the gate is gone. The infrastructure module therefore makes the name
biconditional for the web workloads, so a root that omits it fails at plan time
rather than deploying a task with no gate.

### 8.3 Profiles

`application.yml` carries the base configuration, with `application-dev.yml` and
`application-prod.yml` layered over it.

Refactoring Rationale: the active profile arrives from the task definition and is
**not** baked into the image, which declares no environment variable at all. Baking
it in would force either two images built from one commit — making the artifact
promoted to production one that nobody tested — or a stale layer deciding which
environment's configuration a task loads. Leaving it unset is what keeps
promote-the-same-digest true.

### 8.4 Datasource and schema

The JDBC search path is pinned to `reference`, and Flyway is configured with
`reference` as both its schema and its default schema, reading migrations from
`classpath:db/migration` with `baseline-on-migrate` disabled.

Assumptions: the schema and the roles already exist when this service starts —
they are created by
[`data-migration/sql/V0__schemas_and_roles.sql`](../../data-migration/sql/V0__schemas_and_roles.sql),
not here. Disabling baselining is what makes a non-empty schema with no history
table an error rather than something Flyway silently adopts.

### 8.5 Security

Authorization is a Cognito JWT resource server. `JwtRoleConverter` from
`common-lib` maps the `cognito:groups` claim onto Spring authorities, and this
module adds no converter of its own.

- **Writes are administrative.** `POST`, `PUT`, `PATCH` and `DELETE` each require
  the `carddemo-admin` authority.
- **Reads require a recognized CardDemo authority — either group.** A `GET` on the
  reference surface is granted by `carddemo-user` **or** `carddemo-admin`, so an
  administrator is never denied a read while a token carrying neither group reaches
  nothing. This is what `SecurityConfig`'s read rule installs
  (`hasAnyAuthority(BUSINESS_AUTHORITIES)`), and it is the authority the published
  contract marks each read with.

  Refactoring Rationale: this bullet read "available to any authenticated caller",
  and that is measurably not what the chain enforces — the catch-all below is
  `denyAll()`, so a token issued by the configured pool but carrying no CardDemo
  group is refused rather than admitted. The claim was also internally inconsistent
  with the `denyAll()` paragraph two lines further down, which argues that
  `authenticated()` would be too permissive. The wrong direction of that error is
  the dangerous one: a reader integrating another context would have provisioned a
  group-less client, seen every read answered `403`, and had nothing in this
  document to point at.
- The health endpoint is unauthenticated so the load balancer and the container
  check can both reach it; the build-identity and metric-scrape endpoints are
  restricted.

Assumptions: the catch-all is `denyAll()` rather than `authenticated()`. A token
issued by the configured pool but carrying no expected group is still fully
authenticated, so a catch-all of `authenticated()` would admit a principal that
belongs to neither group. A route added without an explicit rule is refused rather
than quietly exposed.

---

## 9. Database schema and migrations

Three migrations, all applied at startup.

| Migration | Contents |
|---|---|
| [`V1__reference.sql`](src/main/resources/db/migration/V1__reference.sql) | The six tables, their keys, the referential constraint and the classification check |
| [`V2__seed_reference.sql`](src/main/resources/db/migration/V2__seed_reference.sql) | The seed rows, idempotently |
| [`V3__reference_inquiry_reply_ledger.sql`](src/main/resources/db/migration/V3__reference_inquiry_reply_ledger.sql) | `reference.inquiry_reply_ledger` — one row per answered date-conversion request, so a redelivery is answered with the FIRST answer rather than a later timestamp. See [§12.6](#126-date-conversion--two-front-doors-one-implementation) |

Assumptions: `V3` is the only table in this schema that holds no reference DATA, and
it is called out here so a reader browsing the schema does not take it for a lookup
that failed to seed. The migration header records why it lives beside the context
that owns the exchange rather than in a schema of its own.

### 9.1 Why this module carries the only seed migration in the tree

Assumptions: no other service in the tree ships a SEED migration. This one does
because the data it seeds is read by `batch-service` and `account-service`, which
must never own it — a seed placed in a consuming service would make two services
authoritative for one table, and a seed omitted entirely would make the interest
batch abend. Seeding beside the schema that owns the tables is what keeps one
owner and one source.

Refactoring Rationale: this heading and its opening sentence said this module carries
"the only `V2`" and that "no other service in the tree has a second migration", which
was a claim about migration COUNTS rather than about seeding and is no longer true of
either — `account-service`, `card-service`, `transaction-service`, `auth-service` and
`authorization-service` all carry more than one, and this module now carries three.
The property that is actually distinctive, and the one the paragraph argues for, is
that the seed lives with the schema that owns the tables; the heading now states
that instead of a count that has to be re-measured every time a module adds a table.

### 9.2 The referential constraint

`transaction_categories.type_cd` references `transaction_types (type_cd)` **`ON
DELETE RESTRICT`**. That is the baseline's own semantic:
`app/app-transaction-type-db2/ddl/TRNTYCAT.ddl` is seven lines long, and L6–L7
declare `FOREIGN KEY TRC_TYPE_CODE (TRC_TYPE_CODE)` `REFERENCES
CARDDEMO.TRANSACTION_TYPE (TR_TYPE) ON DELETE RESTRICT`.

**The foreign key may never be dropped, weakened or deferred.** Its refusal is the
documented behaviour of [§12.1](#121-on-delete-restrict-surfaces-as-http-409-never-500).

Assumptions: the composite primary key `(type_cd, cat_cd)` provides the access path
that `app/app-transaction-type-db2/ddl/XTRNTYCAT.ddl` builds, so **no separate
index is created** for it. That baseline index is `CREATE UNIQUE INDEX` over
`(TRC_TYPE_CODE ASC, TRC_TYPE_CATEGORY ASC)` — the same columns in the same order,
also unique — and PostgreSQL implements a primary key with a unique index, so a
second one would be redundant storage that the planner would never prefer. A reader
looking for a `CREATE INDEX` matching that DDL finds the constraint instead, which
is why the equivalence is written down.

### 9.3 The seeded rows, counted

Every count below was measured against the baseline file it derives from, so a
future diff is auditable rather than a matter of trust.

| Table | Rows | Measured against |
|---|---|---|
| `transaction_types` | **7** | `app/data/ASCII/trantype.txt`, 7 records |
| `transaction_categories` | **18** | `app/data/ASCII/trancatg.txt`, 18 records |
| `disclosure_groups` | **51**, of which **17** carry `DEFAULT` | `app/data/ASCII/discgrp.txt`, 51 records with 17 `DEFAULT` |
| `us_phone_area_codes` | **490** | `app/cpy/CSLKPCDY.cpy`, 410 general-purpose plus 80 easy-recognition |
| `us_states` | **56** | `app/cpy/CSLKPCDY.cpy` |
| `us_state_zip_prefixes` | **240** | `app/cpy/CSLKPCDY.cpy` |

The three allow-list tables therefore hold **786** rows between them.

Trade-offs: every insert is `ON CONFLICT ... DO NOTHING`. A re-baselined
environment, or a migration replayed against a schema that already holds the seed,
must not double-insert; the cost accepted is that a row already present with a
*different* description is left as it is rather than corrected, so the seed
establishes state and does not enforce it.

Assumptions: Flyway 13.0.0 needs its database-specific companion,
`flyway-database-postgresql`, at the same version. Flyway 10 and later moved
PostgreSQL support out of the core artifact, so core alone resolves at build time
and then fails at run time when it tries to select a dialect. Both are managed in
[`../pom.xml`](../pom.xml) and this module declares the companion explicitly.

---

## 10. API endpoints

Nineteen operations across thirteen paths, all under `/api/v1/reference`, as
published by
[`openapi/reference-api.yaml`](src/main/resources/openapi/reference-api.yaml).
Write operations require the `carddemo-admin` authority; reads require a recognized
CardDemo authority — `carddemo-user` **or** `carddemo-admin`, the two being
interchangeable for a read. The **Authority** column below states `carddemo-user`
on every read for that reason, matching what
[`openapi/reference-api.yaml`](src/main/resources/openapi/reference-api.yaml) marks
each operation with; a token carrying neither group reaches nothing, because the
chain's catch-all is `denyAll()`.

Refactoring Rationale: every read row of this table read `authenticated`, and the
sentence above it said reads "require only an authenticated caller". Neither matched
`SecurityConfig`, whose read rule is `hasAnyAuthority(BUSINESS_AUTHORITIES)` over the
two CardDemo groups. A table is where an integrator reads an authority off, so the
column is corrected to the authority actually required rather than the one it was
described as.

| Method | Path | Purpose | Authority | Derives from |
|---|---|---|---|---|
| `GET` | `/transaction-types` | Keyset page of type codes | `carddemo-user` | `COTRTLIC` |
| `POST` | `/transaction-types` | Add a type | `carddemo-admin` | `COTRTUPC` |
| `GET` | `/transaction-types/{typeCd}` | One type | `carddemo-user` | `COTRTUPC` |
| `PUT` | `/transaction-types/{typeCd}` | Edit a type | `carddemo-admin` | `COTRTUPC` |
| `DELETE` | `/transaction-types/{typeCd}` | Delete a type; **409 when categories reference it** | `carddemo-admin` | `COTRTUPC` |
| `GET` | `/transaction-categories` | Keyset page of type and category pairs | `carddemo-user` | `COTRTLIC` |
| `POST` | `/transaction-categories` | Add a pair | `carddemo-admin` | `COTRTUPC` |
| `GET` | `/transaction-categories/{typeCd}/{catCd}` | One pair | `carddemo-user` | `COTRTUPC` |
| `PUT` | `/transaction-categories/{typeCd}/{catCd}` | Edit a pair | `carddemo-admin` | `COTRTUPC` |
| `DELETE` | `/transaction-categories/{typeCd}/{catCd}` | Delete a pair | `carddemo-admin` | `COTRTUPC` |
| `GET` | `/disclosure-groups/{acctGroupId}/{tranTypeCd}/{tranCatCd}` | Interest rate for one key | `carddemo-user` | the lookup `CBACT04C` performs |
| `GET` | `/us-phone-area-codes` | Keyset page of area codes, optionally by classification | `carddemo-user` | `CSLKPCDY` |
| `GET` | `/us-phone-area-codes/{areaCd}` | One area code | `carddemo-user` | `CSLKPCDY` |
| `GET` | `/us-states` | Keyset page of state codes | `carddemo-user` | `CSLKPCDY` |
| `GET` | `/us-states/{stateCd}` | One state code | `carddemo-user` | `CSLKPCDY` |
| `GET` | `/us-state-zip-prefixes` | Keyset page of state and ZIP-prefix pairs | `carddemo-user` | `CSLKPCDY` |
| `GET` | `/us-state-zip-prefixes/{stateZipCd}` | One state and ZIP-prefix pair | `carddemo-user` | `CSLKPCDY` |
| `GET` | `/date-evaluations` | Evaluate a date against the baseline edit rules | `carddemo-user` | `CSUTLDTC`, `CODATE01` |
| `POST` | `/maintenance-actions` | Apply a batch of reference maintenance actions | `carddemo-admin` | `COBTUPDT` |

The contract is **OpenAPI 3.1**. Assumptions:
[`ui/src/api/reference.ts`](../../ui/src/api/reference.ts) is written against it —
that client documents, at its own head, that a delete "may answer 409, and a caller
must render that as a refusal". A change to this contract is therefore a
browser-client-breaking change, not an internal one.

---

## 11. Messaging

**This module consumes no queue and publishes no message.** It declares no messaging
starter, builds no queue client, requires no region and holds no queue name.

| Role | Consumed or produced | Notes |
|---|---|---|
| Request | consumed by `DateInquiryMessageListener` | Standard queue; delete-on-success, with the composed reply recorded and committed **before** it is sent — see [§12.6](#126-date-conversion--two-front-doors-one-implementation) |
| Reply | produced | Shared with the account-inquiry flow, which publishes its own replies to the same queue |
| Error | produced | Terminal sink for a message this service cannot answer |

Assumptions: what this module answers for on that flow is the date **evaluation**
of `CSUTLDTC`, over REST, plus the shared request GEOMETRY —
`DateConversionFlowContractTest` holds the independent copybook transcription
against `common-lib`'s codec, and asserts that no withdrawn consumer has
returned.

---

## 12. Behavioural contracts that must not regress

Two of the contracts below are the module's acceptance criteria. Stated so they can
be checked rather than believed:

1. **Deleting a transaction type that still has categories answers HTTP 409.**
2. **The `'DEFAULT'` disclosure-group row exists after `V2`, and the fallback
   lookup returns its rate.**

### 12.1 `ON DELETE RESTRICT` surfaces as HTTP 409, never 500

The refusal travels a four-step chain, and every step already exists:

| Step | Where |
|---|---|
| Db2 `SQLCODE -532` | `app/app-transaction-type-db2/cbl/COTRTUPC.cbl` L1638 |
| PostgreSQL `SQLSTATE 23503` | raised by the foreign key of [§9.2](#92-the-referential-constraint) |
| `DataIntegrityViolationException` | translated by the persistence layer |
| **HTTP 409** | `GlobalExceptionHandler` in `common-lib` |

The baseline's own behaviour is what this preserves. `COTRTUPC` performs
`9800-DELETE-PROCESSING` at L1624, branches on `WHEN SQLCODE = -532` at L1638, sets
its delete-failed flag and builds the operator message from the literal
`'Please delete associated child records first:'` at L1641. A 409 carrying that
refusal is the same outcome expressed in the target's vocabulary.

**A 500 with a driver stack trace is a defect, not an alternative rendering.** It
leaks the constraint name and the driver's class name to the caller, and it tells
the browser client that the service failed when in fact the service refused
correctly. The typed client is written to render the 409 as a refusal.

Refactoring Rationale: the mapping is inherited from `common-lib` and is not
duplicated here. This module must not declare a second `@RestControllerAdvice` —
two advices competing for the same exception make the status a function of bean
ordering, which is exactly the kind of behaviour that differs between a test
context and a running task.

### 12.2 The mandatory `'DEFAULT'` disclosure-group row

`V2__seed_reference.sql` seeds it, and **it is not optional.**

`app/cbl/CBACT04C.cbl` L436–L439 reads: when the group-specific read returns VSAM
status `'23'` — record not found — the program moves `'DEFAULT'` into the group-id
field and performs `1200-A-GET-DEFAULT-INT-RATE`. The rate that lookup returns then
feeds the interest formula at L464–L465, `COMPUTE WS-MONTHLY-INT = ( TRAN-CAT-BAL *
DIS-INT-RATE) / 1200`.

⚠ Assumptions: **a missing row does not degrade gracefully.**
`1200-A-GET-DEFAULT-INT-RATE`, at that file's L443, re-reads the disclosure-group
file and, on any status other than `'00'`, displays `ERROR READING DEFAULT
DISCLOSURE GROUP` at L455 and then performs `9999-ABEND-PROGRAM` at L458. It
**hard-abends the interest batch.**

This is why nobody may ever "clean up" those rows as redundant-looking seed data.
Their absence presents as a failure in a *different* service — `batch-service`
abending — with no local defect of its own to find, and the row that is missing is
in a schema that service does not own.

Assumptions: the group id is stored space-padded. `app/cpy/CVTRA02Y.cpy` declares
`DIS-ACCT-GROUP-ID PIC X(10)`, so `'DEFAULT'` occupies seven of ten characters and
the baseline compares the whole fixed-width field. The seed writes the padded
literal for that reason; an unpadded value would not match the key the fallback
constructs.

### 12.3 The seed counts have consequences elsewhere

The counts are in [§9.3](#93-the-seeded-rows-counted). Two of them are worth reading
twice.

Alternatives Considered: the 490 area codes could have become **two** tables, one
per baseline condition name, because `app/cpy/CSLKPCDY.cpy` declares three
overlapping `88`-level condition names over the **same** `PIC XXX` field —
`VALID-PHONE-AREA-CODE` over the whole list, `VALID-GENERAL-PURP-CODE` over 410 of
them and `VALID-EASY-RECOG-AREA-CODE` over 80. Measurement settled it: the two
subsets are disjoint and their union is exactly the 490-code master list, so they
are a partition of one domain rather than two domains. One table with a
classification column, constrained to the two classes, represents that partition
without letting a code exist in neither or in both — which two tables could not
prevent.

Assumptions: an area code omitted from the seed becomes a **false validation
rejection** in `account-service`, refusing a customer address that the baseline
accepts. There is nothing local to this module that would reveal it, which is why
the counts are published here rather than left implicit.

Assumptions: the seeded rates in `app/data/ASCII/discgrp.txt` are zoned decimal
with sign overpunch, not plain digits. The rate field of the first rows reads
`00150{`, where `{` encodes a final digit `0` carrying a positive sign, so the value
is `+0015.00`. Reading those bytes as text yields a rate of `00150` followed by a
brace; they are decoded on load.

### 12.4 Record layouts, and money that never leaves fixed point

| Copybook | Record | Length | Fields |
|---|---|---|---|
| `app/cpy/CVTRA03Y.cpy` | `TRAN-TYPE-RECORD` | **60** | `TRAN-TYPE PIC X(02)`, `TRAN-TYPE-DESC PIC X(50)`, `FILLER PIC X(08)` |
| `app/cpy/CVTRA04Y.cpy` | `TRAN-CAT-RECORD` | **60** | key `PIC X(02)` + `PIC 9(04)`, `TRAN-CAT-TYPE-DESC PIC X(50)`, `FILLER PIC X(04)` |
| `app/cpy/CVTRA02Y.cpy` | `DIS-GROUP-RECORD` | **50** | key `PIC X(10)` + `PIC X(02)` + `PIC 9(04)`, `DIS-INT-RATE PIC S9(04)V99`, `FILLER PIC X(28)` |

`FILLER` is **dropped**, and each drop is recorded per record — it is padding to the
fixed record length, not data. **No field is renamed in this module**; the three
documented misspelling corrections in the migration belong to other contexts.

The rate path is exact fixed point at every hop: `NUMERIC(6,2)` in the column, a
scale-2 decimal with half-up rounding in Java, and a **JSON string** on the wire
through the shared money module.

Alternatives Considered: emitting the rate as a JSON **number**, which is what a
generated contract would do by default. Rejected on a specific mechanism rather
than on principle: most clients parse a JSON number into an IEEE-754 double, and
`DIS-INT-RATE` is one of the two operands of the interest formula at
`app/cbl/CBACT04C.cbl` L464–L465. A rate that arrives a fraction off therefore does
not produce one wrong field on one screen; it propagates into the amount of every
interest transaction the nightly chain generates. Single-precision and
double-precision floating point are forbidden in this path and the prohibition is
enforced by an architecture test rather than by review.

Assumptions: fixed codes are `CHAR(n)` rather than `VARCHAR`. Fixed width is part
of the contract — the baseline keys are fixed-width fields compared whole — and the
padded `'DEFAULT'` group id of [§12.2](#122-the-mandatory-default-disclosure-group-row)
only resolves because the column pads it to ten characters.

### 12.5 Pagination, validation, messages and state

- **Keyset pagination, using the shared page envelope.** Alternatives Considered:
  offset pagination, which the framework offers directly. Rejected because under
  concurrent inserts an offset page skips and repeats rows, and the baseline's
  browse-by-key does not — so adopting offsets would change observable behaviour
  in the one place the baseline was well defined. Cursors are sealed with the
  signing key of [§8.1](#81-required-to-start).
- **Validation flags become a structured per-field error array.** The baseline's
  `FLG-*-NOT-OK` and `FLG-*-BLANK` condition pattern is carried into the shared
  error model with its field-validation flag type, **including the `'*'` marker the
  baseline writes into a blank field.**
- **User-visible strings are verbatim, character for character**, keyed by the
  copybook or program they come from. The `PIC X(75)` message-line width is
  preserved as a rendering constraint on the client, not as a truncation here.
- **CICS pseudo-conversational state is eliminated.** Program transfer becomes a
  client-side route change, identity comes from validated token claims rather than
  from a field the client echoes back, the type code becomes a path parameter, and
  the re-entry discriminator `CDEMO-PGM-CONTEXT` **disappears entirely** — a
  stateless handler that answers with a field-error array has no first-entry versus
  re-entry distinction to make. The service holds **no session state**: no sticky
  sessions and no server-side session store.

### 12.6 Date conversion — two questions, two owners, one set of rules

`CODATE01`'s queue-driven request and reply becomes a synchronous REST endpoint on
`DateConversionController` here, and a queue route owned by `account-service` as the
single consumer of the shared inquiry request queue (see [§11](#11-messaging)).

Refactoring Rationale: this section described both routes as this module's two front
doors onto one implementation. That reading was wrong about the relationship even
before the queue route moved, and the baseline is what settles it: the two routes
answer DIFFERENT questions. This one judges a date a caller submits; the queue route
emits the current system date and time and reads no field of its request —
`WS-FUNC` and `WS-KEY` are declared at `CODATE01.cbl` L110 and L111 and never read.
A search for `CSUTLDTC` across all 524 lines of that file returns **zero**
occurrences, so the queue-borne program never called the date-edit utility at all.

**The evaluation delegates to `DateEditValidator` in `common-lib`.** The rules exist
in exactly one place and are **not re-implemented here** — `DateConversionService`
imports that type and calls it with the shared format mask. The queue reply's layout
is likewise single-sourced, in `common-lib`'s `DateInquiryReplyCodec`, beside the
request codec of the same one-thousand-character wire.

Assumptions: the baseline exposed the emission over a message queue only, so this
REST **evaluation** endpoint is an addition rather than a replacement — and it is an
addition of a different capability, not a second transport over the same one.

Assumptions: **no transactional outbox is needed on this path**, and that is not the
same as needing nothing. The baseline's inquiry get runs under syncpoint —
`app/app-vsam-mq/cbl/CODATE01.cbl` L296–L299 composes its get options from
`MQGMO-SYNCPOINT`, `MQGMO-FAIL-IF-QUIESCING`, `MQGMO-CONVERT` and `MQGMO-WAIT` — so
unlike the authorization flow there is no lost-reply window to close: this service
commits no business row before it answers, so no committed state a missing reply
would contradict exists. A drained outbox would therefore be machinery with nothing
to guarantee.

Refactoring Rationale: this section previously concluded from that observation that
the baseline discipline "maps cleanly onto visibility timeout plus
delete-on-success", full stop. The observation is right and the conclusion was not.
On the target the send completes **before** the listener acknowledges, and the
acknowledgement is a separate call — so a task killed between the two leaves the
request visible again. Because this reply body is the system date and time read at
the moment of composition (L343–L353), a redelivery that recomposed would answer with
a **later timestamp**, and a requester pairing on one correlation identifier would
hold two replies that disagree with nothing on the wire to say which was the answer.

What the consumer therefore does is narrower than an outbox and stronger than
delete-on-success alone: it records the composed reply in
`reference.inquiry_reply_ledger`, commits, sends, and only then marks the row sent.
A redelivery whose row is outstanding re-sends the **recorded bytes**; one whose row
is retired sends nothing. The guarantee is that one request is never answered with
two *different* answers — not that the reply is sent only once. The residual window,
a task dying between the send and the mark, sends a byte-identical duplicate carrying
the same two echoed identities, and is recorded in [§13](#13-known-limitations).

Assumptions: both requester identities are echoed and both are **bounded at intake**.
The baseline restores its saved message identifier at L373 and its saved correlation
identifier at L374 immediately before the put at L383, so a reply carrying one of the
two carries less than the baseline carried; and each is admitted only if it satisfies
the shared queue-identity rule in `common-lib`
(`MessagingCorrelationId.isCanonical` — non-blank, within the shared length bound,
printable US-ASCII other than the space). A value that fails it is treated as
**absent**: not echoed, not recorded, not keyed on, with the business answer still
sent and a protocol diagnostic naming the attribute and its *length* published to the
error sink. Truncating instead would store and echo a value that is neither the
requester's nor absent.

Assumptions: the delivery guarantees of the queue path — visibility timeout plus
delete-on-success, and the absence of any transactional outbox on it — belong with
its consumer and are recorded there rather than restated here.

Refactoring Rationale: a queue configuration class used to be required here and is
withdrawn with the consumer that used it — a queue client nothing injects still makes
a region a startup requirement and still reads as evidence of a message flow. There
is likewise **no** batch configuration class: `COBTUPDT` becomes a
reference-maintenance service method reached over `/maintenance-actions`, not a
Spring Batch job-repository owner.

---

## 13. Known limitations

Recorded plainly, in the manner of [`tests/README.md`](../../tests/README.md) §1.1,
so that no claim above hides a gap.

- ⚠ **A date-inquiry reply can still be delivered twice — never with two different
  answers.** The reply ledger of [§12.6](#126-date-conversion--two-front-doors-one-implementation)
  records the composed reply, commits, sends, and only then marks the row sent. A task
  that dies **after** the send and **before** the mark leaves the row outstanding, so
  the redelivery re-sends it and the requester receives two copies. The copies are
  byte-identical and carry the same two echoed identities, so the far end can discard
  either; `reference.inquiry_reply_ledger.attempts` counts the sends, which is how an
  operator measures whether this window was actually entered.

  Alternatives Considered: committing the send and the mark together, which would
  close the window entirely. Rejected because that is a distributed transaction across
  the database and the queue, and AAP §0.7.6 records this migration's elimination of
  two-phase commit as a property to keep rather than a gap to refill. Trade-offs: what
  is bought instead is that a **differing** answer is unreachable — the failure that
  matters here, because the reply body is a timestamp and a requester cannot tell which
  of two disagreeing replies was the answer.

- ⚠ **No golden master covers this module's paths.** The repository's parity oracle
  is a batch oracle. `tests/README.md` §1.1 records that the online `CO*` programs
  "cannot run end-to-end without a CICS runtime" — absent on any runner — so only
  their extractable field-validation logic is unit-tested there. `COTRTLIC`,
  `COTRTUPC` and `CODATE01` are all online or queue-driven programs. Parity for this
  module therefore rests on **transcribed logic checked against the DDL and copybook
  contracts**, not on a byte-for-byte comparison against a recorded run. The one
  baseline program this module reads that *is* covered by the oracle is
  `app/cbl/CBACT04C.cbl`, and it is covered as `batch-service`'s interest job rather
  than as anything here.

  Alternatives Considered: standing up a CICS-equivalent harness so these three
  programs could be replayed and compared. Rejected because it would make the
  comparison depend on a re-implementation of the terminal and transaction manager,
  so a mismatch could not be attributed — the harness would be as likely to be wrong
  as the migrated code, and a parity oracle that cannot localise a failure is not an
  oracle. Stating the gap plainly leaves the reader with an accurate picture of what
  is proven, which is the alternative actually available.
- **The graded return-code rubric belongs to the parity oracle alone.** The
  `0` / `2` / `4` / `8` / `16` convention, and the aggregate `RC = 4` that
  `tests/README.md` documents as the suite's **green** state, describe the COBOL
  suite. That warn-level aggregate is **not a regression** and must not be read as
  one. It must also never reach a Maven, Surefire, Failsafe, Checkstyle or JUnit
  gate: those are binary, and a tolerated non-zero status there would mean a failing
  build reported as a pass.

  Trade-offs: the two conventions therefore coexist in one repository and a reader
  has to know which suite a status came from. That is accepted because collapsing
  them the other way — teaching a Java gate to treat one non-zero status as success
  — buys nothing and removes the only signal those gates carry.
- **`tests/**` and `scripts/**` are reference-only** — never modified and never
  re-pinned. The Java tests in this module are strictly additive and share nothing
  with them.
- **Baseline artefacts observed and deliberately not ported.** Both are cited so a
  reader does not mistake the divergence for an oversight, and neither is fixed in
  COBOL. Assumptions: the baseline is the behavioural oracle, so it has to stay
  byte-identical — correcting either of these in place would change the artefact the
  migration is measured against, and any modification whatsoever to `app/**` is out
  of scope. The target implements the correct behaviour and the divergence is
  registered in the traceability document instead:
  - `app/app-transaction-type-db2/cbl/COBTUPDT.cbl` has **no** `SQLCODE -532`
    handling at all. Its only negative-code path is the generic
    `WHEN SQLCODE < 0` at L154, which reports the code without distinguishing a
    referential refusal from any other failure. The maintenance endpoint here
    surfaces the refusal as a 409 instead.
  - The operator message in `app/app-transaction-type-db2/cbl/COTRTUPC.cbl`
    concatenates its literals with no separating space — L1641 ends
    `...child records first:` and L1642 begins `SQLCODE :` — so the rendered text
    runs the sentence into the code label. The message text is carried across
    verbatim where it is user-visible; the spacing defect is not reproduced in new
    output and not corrected in the baseline.

---

## 14. Documentation gate

Exactly **one** user-specified rule governs this project: **Rule 1,
"Explainability"**. It requires documentation that explains both what the code does
and *why* particular implementation decisions were made — a docstring on every
function, class and module entry point stating purpose, parameters, return values
and exceptions, and inline comments that justify decisions rather than narrate
mechanics. Its validation gate is blunt: code missing either the docstring or the
decision rationale fails review. There is no Rule 2.

Rule 1's four justification categories are the same four the repository already
required of its test suite at [`tests/README.md`](../../tests/README.md) §12, so
this module extends an established convention rather than importing a new one. The
polyglot form is defined once in
[`docs/CODE_DOCUMENTATION_STANDARD.md`](../../docs/CODE_DOCUMENTATION_STANDARD.md).

### 14.1 The label form is binding

```text
Alternatives Considered:
Refactoring Rationale:
Assumptions:
Trade-offs:
```

Plural, unparenthesised, colon retained, **no emphasis markup**. Assumptions: the
label is read by a text search before it is read by a person, and the reference-only
baseline tags three of the four in the singular — `Assumption:`, `Trade-off:` and
`Alternative Considered:`. Those spellings name the **same** four categories, not
different ones; the equivalence is stated here once and the plural is then used
throughout this file without exception. Mixing forms inside one file makes an audit
silently partial, and a rationale a search cannot find is a rationale a review
cannot count.

⚠ `**Assumptions:**` is a **violation**, not a styling preference. Markdown emphasis
is presentation; the label is data. The gate named below fails on the emphasised,
parenthesised, colon-dropped and singular forms alike.

A rationale must also name a mechanism and its consequence. Phrases such as "this is
better" or "for performance", with no specific justification, fail review — Rule 1
lists them as a forbidden pattern by name.

### 14.2 The two machine gates

| Gate | Mechanism | What it checks about this module |
|---|---|---|
| Javadoc completeness | `maven-checkstyle-plugin`, execution `checkstyle-documentation-gate`, bound to Maven phase **`validate`**, engine **13.8.0**, reading [`config/checkstyle/checkstyle.xml`](../../config/checkstyle/checkstyle.xml) with `failOnViolation=true`, `violationSeverity=warning` and `includeTestSourceDirectory=true` | Javadoc presence and completeness down to `private` scope, in main **and** test sources; `package-info.java` in **every** package of both trees; `@param`, `@return` and `@throws` all mandatory |
| Rationale label form | [`config/rule1/rule1_gate.py`](../../config/rule1/rule1_gate.py), run fail-closed over the whole tree by the services, UI and infrastructure pipelines | That every rationale label in governed files — **including this Markdown file** — is in the one canonical form |

Assumptions: the Checkstyle gate is bound to `validate` rather than to a reporting
phase, so it runs before compilation on every local build and inside `docker build`
as well, not only in the pipeline.
[`.github/workflows/services-ci.yml`](../../.github/workflows/services-ci.yml)
additionally runs an explicitly labelled Checkstyle step as deliberate redundancy.

### 14.3 The suppressions charter, and its limit

[`config/checkstyle/suppressions.xml`](../../config/checkstyle/suppressions.xml)
contains exactly **two** entries, chartered for generated sources and test fixtures:
`target/generated-sources/**` and `src/test/resources/fixtures/**`.

**Nothing under `src/main/java/**` may be suppressed, and above all nothing under
`mapper/**`.** Assumptions: the mappers are the hand-written anti-corruption layer —
the one place where `FILLER` truncation, fixed-width padding and the wire
representation of money are decided — which makes them the highest-value
documentation target in the repository. Suppressing them would remove the
explanation from precisely the code whose correctness cannot be inferred by reading
it.

### 14.4 Never weaken a gate

`-Dcheckstyle.skip`, a `<skip>true</skip>` element, `failOnViolation=false`, a
severity downgrade, a trailing `|| true`, a `continue-on-error` step and any other
form of return-code tolerance are all prohibited, in this module and in the pipeline
alike. Assumptions: a skipped gate reports the same green as a satisfied one, so the
tolerance is invisible in exactly the run where it matters. A missing Javadoc is a
defect in the source being compiled, not a statement about behaviour, and it is
fixed by writing the Javadoc.

---

## 15. Source traceability

| This module | Baseline authority |
|---|---|
| Transaction-type and category browse | `app/app-transaction-type-db2/cbl/COTRTLIC.cbl` |
| Transaction-type and category maintenance, and the 409 refusal | `app/app-transaction-type-db2/cbl/COTRTUPC.cbl` L1624, L1638, L1641 |
| Reference-maintenance batch action | `app/app-transaction-type-db2/cbl/COBTUPDT.cbl` |
| Date conversion, both transports | `app/app-vsam-mq/cbl/CODATE01.cbl` L2, L285–L286, L296–L299 |
| Date-edit rules, consumed from `common-lib` | `app/cbl/CSUTLDTC.cbl` |
| The `DEFAULT` fallback and the rate's use | `app/cbl/CBACT04C.cbl` L436–L439, L443, L455, L458, L464–L465 |
| The referential constraint | `app/app-transaction-type-db2/ddl/TRNTYCAT.ddl` L6–L7 |
| The composite-key access path | `app/app-transaction-type-db2/ddl/XTRNTYCAT.ddl` |
| Record layouts | `app/cpy/CVTRA02Y.cpy`, `app/cpy/CVTRA03Y.cpy`, `app/cpy/CVTRA04Y.cpy` |
| The three allow-lists | `app/cpy/CSLKPCDY.cpy` L30–L520, L521–L930, L931–L1011, L1013–L1071, L1073–L1313 |
| Seed rows | `app/data/ASCII/trantype.txt`, `trancatg.txt`, `discgrp.txt` |
| CICS transaction bindings | `app/app-transaction-type-db2/csd/CRDDEMOD.csd` L25–L26, L35–L36; `app/app-vsam-mq/csd/CRDDEMOM.csd` L27–L28 |

Trade-offs: line numbers are cited for the `app/**` baseline and for the shared
build configuration, and **not** for this module's own Java. The baseline is
reference-only and cannot drift, so a line citation into it stays true; a citation
into a file under active change goes stale silently, and an earlier revision of this
page had already drifted on two internal line references while reading as precise.
Target code is therefore identified by class and member name, which survives editing.
The cost is one extra hop for a reader who wants the exact statement.

**Every path above is read, never written.** Any modification whatsoever to `app/**`
is out of scope for this migration; the baseline is the behavioural oracle and stays
byte-identical.

### 15.1 Related documents

- [`docs/architecture/cobol-to-service-traceability.md`](../../docs/architecture/cobol-to-service-traceability.md)
  — the authoritative matrix, and the register of every documented divergence
- [`docs/architecture/service-catalog.md`](../../docs/architecture/service-catalog.md)
  — the naming authority for this module and its siblings
- [`docs/architecture/data-model-and-schema-mapping.md`](../../docs/architecture/data-model-and-schema-mapping.md)
  — the field-by-field mapping tables
- [`docs/architecture/messaging-contracts.md`](../../docs/architecture/messaging-contracts.md)
  — the queue mapping and payload contracts
- [`docs/runbooks/deploy.md`](../../docs/runbooks/deploy.md) and
  [`docs/runbooks/teardown.md`](../../docs/runbooks/teardown.md) — exact commands
- [`../common-lib/README.md`](../common-lib/README.md) — the shared kernel this
  module consumes
- [`MIGRATION_README.md`](../../MIGRATION_README.md) — build, deploy, run, migrate,
  validate and roll back, across the whole migration

---

<sub>Apache-2.0 · This module is additive. The COBOL baseline under `app/**`, the
parity suite under `tests/**` and its runners under `scripts/**` are reference-only
and are never modified. See [`../../README.md`](../../README.md) for the application
overview and [`../../CONTRIBUTING.md`](../../CONTRIBUTING.md) for contribution
conventions.</sub>
