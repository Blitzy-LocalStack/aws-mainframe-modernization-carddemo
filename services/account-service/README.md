# Account Service

> **Purpose.** The account, customer and card-cross-reference bounded context of
> the AWS CardDemo mainframe-to-AWS migration.
>
> **Source of truth.** The COBOL baseline is the specification. This module
> encodes it; it never redefines it, and it never edits it.


## Executive Summary

This module owns the **`account`** PostgreSQL schema — **four** tables, of which
three descend from a copybook record (`accounts`, `customers` and `card_xref`) and
the fourth, `inquiry_reply_ledger`, descends from no copybook at all and is
operational, being what stops a redelivered inquiry from being answered twice. It
is the only deployable permitted to write any of the four. The schema is created by
**two** migrations rather than one, and both the ledger and the second migration are
described under [`account.inquiry_reply_ledger`](#accountinquiry_reply_ledger).

This module replaces **six** COBOL programs: the two online CICS
transactions [`app/cbl/COACTVWC.cbl`](../../app/cbl/COACTVWC.cbl) (account view,
941 lines, transaction `CAVW`) and
[`app/cbl/COACTUPC.cbl`](../../app/cbl/COACTUPC.cbl) (account update, 4236 lines
and the largest online program in the baseline, transaction `CAUP`); the three
sequential readers [`app/cbl/CBACT01C.cbl`](../../app/cbl/CBACT01C.cbl) (430
lines), [`app/cbl/CBACT03C.cbl`](../../app/cbl/CBACT03C.cbl) (178 lines) and
[`app/cbl/CBCUS01C.cbl`](../../app/cbl/CBCUS01C.cbl) (178 lines); and the
queue-driven inquiry extension
[`app/app-vsam-mq/cbl/COACCT01.cbl`](../../app/app-vsam-mq/cbl/COACCT01.cbl)
(620 lines).

Two behavioural contracts in this module are the ones a reader is most likely to
get backwards, so both are stated with their evidence in [Behavioural Contracts
Preserved](#behavioural-contracts-preserved): this context needs **no
transactional outbox**, because the baseline inquiry flow is already one atomic
unit of work; and the baseline **already implements optimistic concurrency**, so
the target expresses it natively with a version column rather than inventing it.


## Table of Contents

- [Executive Summary](#executive-summary)
- [Table of Contents](#table-of-contents)
- [Description](#description)
- [Technologies](#technologies)
- [Installation](#installation)
- [Running the Service](#running-the-service)
- [Configuration and Environment Variables](#configuration-and-environment-variables)
- [API Endpoints](#api-endpoints)
- [Data Model](#data-model)
- [Messaging — the Account Inquiry Consumer](#messaging--the-account-inquiry-consumer)
- [Migrated Program Inventory](#migrated-program-inventory)
- [Behavioural Contracts Preserved](#behavioural-contracts-preserved)
- [Testing](#testing)
- [Known Limitations](#known-limitations)
- [Code Documentation Standard](#code-documentation-standard)
- [Technical Highlights](#technical-highlights)
- [Support](#support)
- [Contributing](#contributing)
- [License](#license)


## Description

The account context answers three questions that the baseline answered from
three separate VSAM files: *what is this account*, *who holds it*, and *which
cards point at it*. In the baseline those files are `ACCTDAT`, `CUSTDAT` and
`CCXREF`, with a fourth CICS resource — `CXACAIX` — existing only to reach the
cross-reference by account instead of by card. All four are defined in
[`app/csd/CARDDEMO.CSD`](../../app/csd/CARDDEMO.CSD), and all four are collapsed
here into one schema owned by one deployable.

| Property | Value |
|---|---|
| Maven coordinates | `com.carddemo:account-service:1.0.0-SNAPSHOT` |
| Parent | `com.carddemo:carddemo-services:1.0.0-SNAPSHOT` (`services/pom.xml`) |
| Packaging | `jar` (bootable, via `spring-boot-maven-plugin`) |
| Java package root | `com.carddemo.account` |
| Entry point | `com.carddemo.account.AccountApplication` |
| Owned schema | `account` — `accounts`, `customers`, `card_xref`, `inquiry_reply_ledger` |
| Schema migrations | `src/main/resources/db/migration/V1__account.sql` (the three copybook-derived tables) and `V2__account_inquiry_reply_ledger.sql` (the duplicate-reply ledger) |
| API contract | `src/main/resources/openapi/account-api.yaml` (OpenAPI 3.1) |
| Listening port | 8080 |

The internal layering is uniform across every service module in this reactor and
is mechanically enforced rather than merely intended:

```text
api/         3 controllers — request binding, validation, status selection; no business rule
service/     7 types       — the rules transcribed from the COBOL paragraphs, plus the inquiry consumer
repository/  5 types       — Spring Data JPA, the keyset projection, and the reply ledger
domain/      3 entities    — one per copybook record
dto/        12 types       — field-for-field from the copybook and symbolic-map layouts
mapper/      5 types       — the hand-written anti-corruption layer
config/      7 types       — security, datasource, OpenAPI, SQS, KMS, identifier protection
```

Every count above is production types only, excluding each package's `package-info.java`
charter, and each is re-measured rather than adjusted:
`find src/main/java/com/carddemo/account/<package> -maxdepth 1 -name '*.java' ! -name
'package-info.java' | wc -l`. Refactoring Rationale: three of the seven had drifted low —
`service/` by the inquiry consumer, `repository/` by the reply ledger, and `dto/` by the
account-context and customer-display projections — because a listing of this kind is
maintained by incrementing it and an increment is skipped exactly when a type arrives
alongside other work. Stating the command beside the figures makes the next reader able to
re-derive all seven in one line instead of trusting them.

The one permitted intra-reactor dependency is `common-lib`. This module declares
**no dependency on any sibling service module**, and a cross-service
`..domain..` import would fail the build rather than a review: `LayeringRulesTest`
is authored once in `common-lib`'s test tree, packaged as a test-jar, and re-run
by the architecture-rules Surefire execution in `services/pom.xml` against this
module's own compiled classes.


## Technologies

### Core Technologies

| Technology | Version | Role in this module |
|---|---|---|
| Java | 21 (LTS) | `maven.compiler.release` is 21; records carry the DTO shapes |
| Spring Boot | 4.1.0 | Parent POM; brings Spring Framework 7 |
| Spring Web MVC | managed | The three REST controllers |
| Spring Data JPA | managed | The four repositories and the version columns |
| Spring Validation | managed | Declarative constraints transcribed from the edit paragraphs |
| Spring Security + OAuth2 Resource Server | managed | Cognito JWT validation, group-to-authority conversion |
| Spring Boot Actuator | managed | `/actuator/health`, `/actuator/info`, the metrics scrape path |
| Spring Boot RestClient starter | managed | The outbound call to the reference context's lookup tables |
| PostgreSQL JDBC driver | 42.7.13 | Aurora PostgreSQL connectivity (`runtime` scope) |
| Flyway | 13.0.0 | Applies `V1__account.sql` at startup |
| `flyway-database-postgresql` | 13.0.0 | **Mandatory companion** — see [Data Model](#data-model) |
| springdoc-openapi | 3.0.3 | Serves the OpenAPI 3.1 document |
| Spring Cloud AWS (`io.awspring.cloud`) | 4.1.0 | SQS listener, Parameter Store, Secrets Manager |
| AWS SDK for Java v2 | 2.49.3 | KMS client for customer-identifier protection |
| Micrometer (Prometheus registry) | managed | Metric export (`runtime` scope) |

Every version above is inherited from `services/pom.xml`. This module's POM pins
nothing of its own, so a version can never drift between two service modules.

### Optional Technologies

These are used by the test and container tiers only. None is required to run the
service, and none reaches the runtime classpath.

| Technology | Version | Role |
|---|---|---|
| JUnit 5, Mockito, AssertJ, MockMvc | managed | Unit and web-layer tests |
| Spring Security Test | managed | Authority and filter-chain assertions |
| Testcontainers (JUnit Jupiter, PostgreSQL, LocalStack) | 2.0.5 | Real PostgreSQL for the repository integration tests |
| ArchUnit (`archunit-junit5`) | 1.4.2 | Runs `common-lib`'s layering rules against this module |
| Checkstyle | 13.8.0 | The documentation gate — see [Code Documentation Standard](#code-documentation-standard) |
| Docker | any current | Building the container image |

Two entries deserve a word, because their presence looks optional and is not.
`flyway-database-postgresql` is listed under *Core*, not *Optional*, because
Flyway core alone fails at **runtime** rather than at build time. And ArchUnit is
listed under *Optional* only in the sense that it is test-scoped; selecting no
architecture test is a build **failure**, not a silent pass, because the
`architecture-rules-fail-if-absent` profile in `services/pom.xml` adds
`failIfNoTests` wherever a `src/main/java` directory exists.


## Installation

### Prerequisites

| Requirement | Version validated | Needed for |
|---|---|---|
| JDK | Amazon Corretto **21.0.12** | Compiling and running the module |
| Apache Maven | **3.9.16** | Driving the reactor and the documentation gate |
| Docker | **29.7.0** | Building the container image (optional) |
| PostgreSQL | **17.x** reachable over TLS | Running the service and the repository integration tests |

`JAVA_HOME` must point at the JDK and Maven must be on `PATH`. Every command in
this file is run **from the repository root**, not from this module directory.

```bash
# WHY : Assumptions: the toolchain profile script is LOGIN-only, so a
#       non-interactive shell -- which is what a script, an editor task or a CI
#       step gets -- starts without it. Exporting both here rather than relying
#       on the profile is what makes every command below reproducible from any
#       shell instead of only from an interactive login.
export JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto
export PATH="$JAVA_HOME/bin:/opt/maven/bin:$PATH"
```

### Build

```bash
# WHY : Assumptions: `common-lib` must be built first and is never published to
#       a registry, so the reactor is the only place its jar and its test-jar
#       come from. This command also runs Surefire over `*Test`, Failsafe over
#       `*IT`, the ArchUnit layering rules against this module's own classes,
#       and the Checkstyle documentation gate at `validate` -- so a missing
#       Javadoc fails here, locally, rather than in a pipeline round trip.
mvn -B -f services/pom.xml clean verify
```

```bash
# WHY : Trade-offs: `-am` ("also make") is REQUIRED and is not an optimisation.
#       Without it Maven resolves `com.carddemo:common-lib:1.0.0-SNAPSHOT` from
#       the local repository, so the build either fails outright on a clean
#       machine or -- worse -- succeeds against a STALE installed jar and hides
#       a contract change made in `common-lib`. The cost of `-am` is compiling
#       one extra module; the cost of omitting it is a green build that proves
#       nothing about the shared kernel this module depends on.
mvn -B -f services/pom.xml -pl account-service -am clean verify
```

```bash
# WHY : Trade-offs: `validate` is the phase the gate is bound to, so this stops
#       before compiling, testing or packaging anything. It is the fastest way
#       to answer "does my Javadoc satisfy Rule 1" and it is deliberately the
#       narrowest -- it proves nothing about compilation or behaviour, so it
#       supplements the two commands above rather than replacing either.
mvn -B -f services/account-service/pom.xml validate
```

```bash
# WHY : Assumptions: the build context is the REPOSITORY ROOT -- the trailing
#       `.` is the whole point of this command and is not interchangeable with
#       the module directory. The Dockerfile reaches THREE paths outside this
#       module: `services/common-lib/` and `services/pom.xml`, because that
#       sibling is unpublished and must be compiled from source in the same
#       reactor; the root `config/checkstyle/` directory, because the parent POM
#       addresses the ruleset at a repository-root relative path and the
#       ruleset's `SuppressionFilter` is declared `optional="false"` -- and
#       fail-closed means a missing suppressions file is an error, not a silent
#       switch to auditing nothing, so a build that could not see that directory
#       would stop rather than quietly skip the gate; and
#       `config/docker/generate-listener-material.sh`, the shared entry point
#       that mints this task's TLS keystore. Running `docker build .` from inside
#       `services/account-service/` therefore FAILS, reporting each of those
#       paths as not found -- none of them is inside that context.
docker build -f services/account-service/Dockerfile -t carddemo/account-service:local .
```

Two properties of that image build are worth stating because they are easy to
assume the other way round. The Checkstyle gate runs **inside** it, since
`validate` precedes `package`, so incomplete Javadoc in either module compiled
there fails `docker build`. And the build is **validation-only** in this
repository: it proves the image assembles and the gates hold. Publishing to a
registry is a pipeline concern authenticated by short-lived federated role
assumption, and no long-lived credential exists anywhere in this repository to
do it with.


## Running the Service

The service reads every endpoint, credential and key identifier from its
environment. Nothing is compiled in, and the two profiles change values only —
never topology.

Both profiles carry the **same ten keys** and differ only in their values, so the
two files can be diffed side by side; a key present in one and absent from the
other would be a topology difference in disguise. The four permitted axes are
pool sizing, SQS listener sizing, log level and retention.

| Key | `dev` | `prod` |
|---|---|---|
| `spring.datasource.hikari.maximum-pool-size` | `4` | `20` |
| `spring.datasource.hikari.minimum-idle` | `0` | `5` |
| `spring.datasource.hikari.connection-timeout` | `30000` | `10000` |
| `spring.cloud.aws.sqs.listener.max-concurrent-messages` | `2` | `10` |
| `spring.cloud.aws.sqs.listener.max-messages-per-poll` | `2` | `10` |
| `logging.level.root` | `INFO` | `WARN` |
| `logging.level.com.carddemo` | `DEBUG` | `INFO` |
| `logging.level.io.awspring.cloud.sqs` | `DEBUG` | `WARN` |
| `logging.level.org.hibernate.SQL` | `DEBUG` | `WARN` |
| `logging.level.org.hibernate.orm.jdbc.bind` | `WARN` | `WARN` |

Three of those rows are worth reading together rather than as separate numbers.

Trade-offs: the `dev` idle floor is `0` because the dev cluster is provisioned to
scale to zero and pauses only while no connection from user activity exists, so a
floor above zero would pin one open and forfeit the saving entirely. What is
traded for it is first-request latency after a pause, accepted in development
only — `prod` holds its floor at `5` so no production caller ever pays a resume.
The longer `dev` acquisition timeout is the direct consequence of that floor: the
first caller after a pause waits for the cluster to resume, and at the `prod`
value of `10000` that caller would instead fail with an acquisition timeout every
time the cluster had paused.

Assumptions: the listener concurrency and the pool ceiling are one decision, not
two. Each in-flight inquiry holds a connection for the length of its three reads —
the account, the customer and the cross-reference — so concurrency is held at or
below the pool ceiling in both profiles. A ceiling above the pool converts queue
depth into connection-acquisition waits and times out handlers that would
otherwise have succeeded.

`logging.level.org.hibernate.orm.jdbc.bind` is pinned to `WARN` in **both**
profiles and must never be raised in either. It is the bind-parameter logger, and
at `DEBUG` or `TRACE` it prints bound parameter **values** — which here include
the plaintext side of `customers.ssn_encrypted` and
`customers.govt_issued_id_encrypted`, columns stored encrypted and returned
masked precisely so they are never readable. The masking applied at the API edge
does not reach log storage.

Alternatives Considered: two keys are deliberately **absent** from both profiles
rather than pinned in both. `spring.jpa.show-sql` was rejected as the statement
control because it writes to standard output outside the logging subsystem, so no
level can narrow it once enabled and it escapes the shared console pattern as
well; `org.hibernate.SQL` above is the supported control and is what the profiles
vary instead. `management.endpoint.health.show-details` was rejected because
`GET /actuator/health` is the one route left unauthenticated — neither the load
balancer target group nor the container health check can present a token — so a
detail payload would describe the data tier to any caller able to reach the port.
Both stay as `application.yml` closes them, which keeps the actuator surface and
the echo flag identical in both environments by construction rather than by two
values that could drift apart.

Assumptions: retention is the fourth axis and has no key in either profile,
because it is not an application property at all — it is a CloudWatch log-group
property owned by `infra/modules/observability`. An application-level retention
key would be accepted by the property binder, would appear to have been set, and
would do nothing.

Refactoring Rationale: the shared defaults from `common-lib` and the **optional**
AWS Parameter Store and Secrets Manager prefixes are imported once, by
`application.yml`, and neither profile restates the import list. Both profiles
previously did. That was removed because a config-data import is resolved per
document and is therefore inherited, so the restatement bought nothing and cost
a second source of truth — and the `dev` copy had already drifted to omit the
Secrets Manager location the base declares. The same jar runs with or without
Parameter Store reachable, because every remote location is `optional:`.

A local run needs four things reachable before it will start, and they fail in
this order, so bring them up first: the **database** (the migration credential is
used before anything else), the **token issuer** (the resource server fetches
issuer discovery while the context refreshes, not on first request), a **free
port**, and an **SQS endpoint whose three inquiry queues already exist** —
`application.yml` pins `queue-not-found-strategy: fail`, so the listener aborts
startup rather than creating queues that nothing else publishes to. Point the
client at a local emulator with `--spring.cloud.aws.sqs.endpoint=<url>` and create
the three queues first.

Secrets are prepared **out of band**, in a file that cannot be committed, and the
launch command then references variables that are already populated. Create the
file once:

```text
# .env.account-service.local — ignored by .gitignore (`.env.*`), never committed.
# Fill each value in from your own secret store. Leave no value in shell history.
AWS_REGION=
SPRING_DATASOURCE_URL=
SPRING_DATASOURCE_USERNAME=
SPRING_DATASOURCE_PASSWORD=
SPRING_FLYWAY_USER=
SPRING_FLYWAY_PASSWORD=
SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI=
CARDDEMO_SECURITY_JWT_EXPECTED_CLIENT_ID=
CARDDEMO_SECURITY_CUSTOMER_IDENTIFIER_KEY_ID=
CARDDEMO_INTERNAL_IDENTITY_AUTHORIZATION_SIGNING_KEY=
CARDDEMO_INTERNAL_IDENTITY_TRANSACTION_SIGNING_KEY=
CARDDEMO_PAGINATION_CURSOR_SIGNING_KEY=
CARDDEMO_REFERENCE_CONTEXT_BASE_URL=
CARDDEMO_ACCOUNT_INQUIRY_REQUEST_QUEUE=
CARDDEMO_ACCOUNT_INQUIRY_REPLY_QUEUE=
CARDDEMO_ACCOUNT_INQUIRY_ERROR_QUEUE=
```

```bash
# WHY : Assumptions: `umask 077` is set BEFORE the file is created, not fixed
#       afterwards with `chmod`. A `chmod` after the fact leaves a window in
#       which the file existed group- and world-readable, and on a shared
#       developer host that window is enough. The `chmod` below is therefore a
#       belt-and-braces assertion for a file that may already exist from an
#       earlier session, not the primary control.
# WHY : Assumptions: the filename is `.env.account-service.local` specifically
#       because `.gitignore` ignores `.env.*`, which `git check-ignore -v
#       .env.account-service.local` will confirm naming the rule and line.
#       Refactoring Rationale: this section previously named
#       `account-service.local.env`, which matches NO ignore rule in this
#       repository — a file holding a database password and three signing keys
#       was one `git add -A` away from being committed. The extension-last
#       spelling is the whole defect: it reads like an env file to a human and
#       like an ordinary tracked file to git.
umask 077
touch .env.account-service.local
chmod 600 .env.account-service.local
git check-ignore -v .env.account-service.local   # prints the rule that protects it
```

```bash
# WHY : Assumptions: the credentials arrive by SOURCING the prepared file, so no
#       secret is typed on this command line, appears in shell history, or shows
#       up in the process table of a machine other developers can read.
#       `set -a` exports every assignment the file makes and `set +a` stops that
#       as soon as it has been read, which keeps the export behaviour scoped to
#       the file rather than to the rest of the session.
#       Refactoring Rationale: these values used to be written inline as
#       `VAR=<description>`. That is not merely untidy — bash parses the
#       unquoted `<` as an input redirection, so the command failed before Java
#       started, with a message naming the first word after the angle bracket
#       rather than anything about configuration. It also invited a real
#       credential to be pasted onto a command line. Note that `bash -n` accepts
#       the old form, so a syntax check was never enough to catch it; only
#       running it was.
# WHY : Assumptions: each variable in that file has NO fallback in
#       `application.yml`, so the context refuses to start rather than starting
#       bound to nothing. That is deliberate: a service that starts with an
#       unset datasource URL becomes a task that reports healthy and then fails
#       every read it is sent, which is strictly harder to diagnose than a
#       startup failure naming the missing key.
# WHY : Trade-offs: `SERVER_SSL_ENABLED=false` applies to a LOCAL run only.
#       `application.yml` enables TLS and reads its listener material from a
#       PKCS#12 keystore that the image entry point mints per task, so no
#       keystore exists on a developer machine and the process would fail while
#       opening one. Disabling TLS for the loopback hop states plainly that a
#       local run does not exercise the deployed transport, rather than
#       appearing to.
# WHY : Assumptions: `SERVER_ADDRESS=127.0.0.1` is what MAKES the run
#       loopback-only, and it is a configured property rather than an
#       assumption. No `application.yml` in this repository sets
#       `server.address`; the variable reaches Spring Boot's
#       `ServerProperties.address` through relaxed binding. Verified by running
#       a service both ways and reading the kernel's listening socket: with the
#       variable set, /proc/net/tcp shows the local address `0100007F`
#       (127.0.0.1) and a request to the host's routable address is REFUSED;
#       without it the row is `00000000` and that same request returns HTTP 200
#       from off-host. Refactoring Rationale: this block disabled TLS without
#       binding an address, so the prose called it a loopback hop while the
#       listener was in fact answering cleartype requests on every interface
#       the machine has. Disabling TLS is only defensible once nothing outside
#       the machine can reach the port.
# WHY : Assumptions: `AWS_REGION` is required to START, not merely to reach AWS.
#       This module declares the SQS starter for the inquiry consumer and the
#       SQS client bean is built while the context is constructed, so a context
#       with no region enters the SDK resolution chain and waits on instance
#       metadata discovery before failing. Credentials are NOT needed to start,
#       because they resolve on first call.
mvn -B -f services/pom.xml -pl account-service -am package

set -a
. ./.env.account-service.local
set +a

export SPRING_PROFILES_ACTIVE=dev
export SERVER_SSL_ENABLED=false
export SERVER_ADDRESS=127.0.0.1

java -jar services/account-service/target/account-service.jar
```

> Refactoring Rationale:  The jar name carries no version. This command read
> `account-service-1.0.0-SNAPSHOT.jar`, which wrote a version inherited from
> `services/pom.xml` into two files that cannot read it — this README and the
> Dockerfile — so a routine version bump silently invalidated both. The module POM now
> pins `<finalName>account-service</finalName>`, which `services/card-service` already
> did, so the path is stable across every version change and the name is a contract
> between the POM, the Dockerfile and this document.

Confirm the binding rather than trusting it — the check costs one command:

```bash
# WHY : Alternatives Considered: `ss -lntp` or `netstat -lntp`. Rejected because
#       neither is present in every container this repository is developed in,
#       and their absence produces a "nothing is listening" answer that reads as
#       a pass. Reading /proc/net/tcp has no such failure mode. Assumptions: the
#       address is hex and byte-reversed — `0100007F` is 127.0.0.1 and
#       `00000000` is every interface. Trade-offs: `--noproxy '*'` is not
#       decoration; where a transparent HTTP proxy is configured, curl otherwise
#       answers 502 from the proxy and never reaches the port under test, which
#       would make a bound listener look unreachable and an unbound one look
#       refused.
# WHY : Assumptions: the port is matched as the literal hex string `1F90` (8080)
#       rather than converted with `strtonum`. That is deliberate — `strtonum` is
#       a GNU awk extension and the `awk` on a Debian-family image is usually
#       mawk, which aborts with "function strtonum never defined". Matching the
#       hex directly works under either awk.
awk 'NR>1 && $4=="0A" && $2 ~ /:1F90$/ {split($2,a,":"); print "listen address:", a[1]}' \
    /proc/net/tcp
curl -s --noproxy '*' --max-time 5 http://127.0.0.1:8080/actuator/health
curl -sf --noproxy '*' --max-time 5 "http://$(hostname -i | awk '{print $1}'):8080/actuator/health" \
    && echo 'REACHABLE OFF-HOST — server.address did not take effect' \
    || echo 'refused from the routable address, as intended'
```

```bash
# WHY : Alternatives Considered: passing each variable with a repeated `-e`
#       flag. Rejected because the values include credentials and key material,
#       and a `-e` list puts every one of them in this shell's history and in
#       the host process table. An env file keeps them in one owner-only place.
#       In deployment neither form is used, because the ECS task definition
#       resolves them from Parameter Store and Secrets Manager instead.
# WHY : Assumptions: an env file does NOT make these values confidential from
#       anyone who can query the Docker daemon. Docker resolves `--env-file` when
#       it CREATES the container and stores the resulting variables in the
#       container's own configuration, so `docker inspect` prints them under
#       `Config.Env` exactly as it would for `-e`. What the file buys is
#       narrower and still worth having: the secrets stay out of shell history
#       and out of the process table. Refactoring Rationale: this note used to
#       claim the file kept values out of `docker inspect`. That was a false
#       assurance, and a false assurance about secret handling is worse than no
#       note at all, because it invites a reader to treat daemon access as
#       harmless. Treat anyone with daemon access as holding every value in the
#       file.
# WHY : Assumptions: the file is the same ignored, owner-only
#       `.env.account-service.local` prepared above — not a second file under a
#       different name. It is also read by the daemon rather than by this shell,
#       so it takes `KEY=value` lines literally: no `export`, no quoting, and no
#       variable expansion.
# WHY : Assumptions: the container publishes 8080 and nothing else. That port is
#       a contract with the security groups and with the load-balancer target
#       group, not a local preference, so remapping the container side of `-p`
#       would diverge from the deployed shape. The HOST side is bound to
#       `127.0.0.1` on purpose: `-p 8080:8080` publishes on every host
#       interface, which puts a container carrying a database credential on the
#       network for anything that can route to this machine, and Docker's
#       published ports are inserted ahead of most host firewall rules rather
#       than filtered by them.
docker run --rm --env-file ./.env.account-service.local -p 127.0.0.1:8080:8080 \
  carddemo/account-service:local
```

The image runs as the non-root identity `10001:10001` and declares a
`HEALTHCHECK` that polls its own `/actuator/health` over TLS on the loopback
interface every 30 seconds after a 30-second start period. That is the **same**
path the load-balancer target group polls, which is the point: container-level and
load-balancer-level health cannot disagree about one instance, because there is
only one answer to disagree about.


## Configuration and Environment Variables

Three files carry this module's configuration, and their division of labour is
fixed: `src/main/resources/application.yml` holds everything that is true in
every environment, and `application-dev.yml` and `application-prod.yml` vary
values along a small, deliberately narrow set of axes — connection-pool sizing,
SQL logging, log levels and health-detail exposure. Neither profile restates a
topology decision, so `dev` and `prod` cannot drift into different shapes.

**No endpoint, credential, key identifier or secret appears in this repository.**
Every one arrives from Terraform outputs by way of AWS Systems Manager Parameter
Store and AWS Secrets Manager, and is read at startup through the active Spring
profile. That is not a modern nicety bolted onto a mainframe habit — it is the
baseline's own habit, made explicit. `01 QUEUE-INFO.` at
[`COACCT01.cbl`](../../app/app-vsam-mq/cbl/COACCT01.cbl) line 92 declares four
`PIC X(48)` queue-name fields, and every one of them is `VALUE SPACES`: the
queue-manager name on line 93, the input queue on 94, the reply queue on 95 and
the error queue on 96. The baseline resolved its destinations at run time too.

The table below documents variable **names** and where each one comes from. The
value column is `—` throughout, by design.

| Variable | Value | Source | Fallback in `application.yml`? |
|---|---|---|---|
| `SPRING_DATASOURCE_URL` | — | Parameter Store (Aurora writer endpoint) | none |
| `SPRING_DATASOURCE_USERNAME` | — | Secrets Manager (`account` schema role) | none |
| `SPRING_DATASOURCE_PASSWORD` | — | Secrets Manager | none |
| `SPRING_FLYWAY_USER` | — | Secrets Manager (migration role) | none |
| `SPRING_FLYWAY_PASSWORD` | — | Secrets Manager | none |
| `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI` | — | Terraform output (Cognito user pool issuer) | none |
| `CARDDEMO_SECURITY_JWT_EXPECTED_CLIENT_ID` | — | Terraform output (Cognito app client) | none |
| `CARDDEMO_SECURITY_CUSTOMER_IDENTIFIER_KEY_ID` | — | Terraform output (KMS key for identifier protection) | none |
| `CARDDEMO_INTERNAL_IDENTITY_AUTHORIZATION_SIGNING_KEY` | — | Secrets Manager | none |
| `CARDDEMO_INTERNAL_IDENTITY_TRANSACTION_SIGNING_KEY` | — | Secrets Manager | none |
| `CARDDEMO_PAGINATION_CURSOR_SIGNING_KEY` | — | Secrets Manager | none |
| `CARDDEMO_REFERENCE_CONTEXT_BASE_URL` | — | Terraform output (internal load balancer) | none |
| `CARDDEMO_ACCOUNT_INQUIRY_REQUEST_QUEUE` | — | Terraform output (SQS) | none |
| `CARDDEMO_ACCOUNT_INQUIRY_REPLY_QUEUE` | — | Terraform output (SQS) | none |
| `CARDDEMO_ACCOUNT_INQUIRY_ERROR_QUEUE` | — | Terraform output (SQS) | none |
| `CARDDEMO_SERVER_TLS_KEYSTORE_PASSWORD` | — | minted per task by the image entry point | none |
| `AWS_REGION` | — | ECS task definition | none |
| `CARDDEMO_ONLINE_WRITES_PARAMETER` | — | ECS task definition (SSM parameter name) | none in effect |
| `CARDDEMO_ENVIRONMENT` | — | ECS task definition | has one |
| `CARDDEMO_COGNITO_ADMIN_GROUP_NAME` | — | Terraform output | has one |
| `CARDDEMO_COGNITO_USER_GROUP_NAME` | — | Terraform output | has one |
| `CARDDEMO_DB_SSL_ROOT_CERT` | — | container filesystem path | has one |
| `CARDDEMO_SERVER_TLS_KEYSTORE` | — | path the entry point writes | has one |
| `CARDDEMO_SERVER_TLS_KEY_ALIAS` | — | fixed alias | has one |

`CARDDEMO_PAGINATION_CURSOR_SIGNING_KEY` and `CARDDEMO_ONLINE_WRITES_PARAMETER` are
the two inputs that are *not* written as placeholders in `application.yml`: Spring's
relaxed binding maps them onto `carddemo.pagination.cursor.signing-key` and
`carddemo.online-writes.parameter`, both declared in the shared kernel's
auto-configuration. They are called out here because a reader auditing the file for
`${...}` placeholders will not find either and could reasonably conclude they are
optional. Neither is, and they fail in opposite ways. The cursor key **stops
startup**: its bean is `@ConditionalOnProperty` and `service/AccountViewService`
takes a `CursorToken` as a mandatory constructor argument. The write gate **removes
itself silently**: both its beans are conditional on the same property and nothing
outside the auto-configuration injects them, so an absent value leaves the account
edit accepting writes during the nightly batch window with nothing in the log to say
the gate is gone. `infra/modules/ecs-service` requires the cursor key by name and
makes the gate name biconditional for the seven web workloads, so a root that drops
either fails at `plan`.

Refactoring Rationale: the second name was missing from the table above, which is
the more consequential of the two omissions precisely because its absence is silent —
the only symptom is a write accepted at a moment the batch window meant to refuse it.

### Health and metrics

The actuator surface answers `GET /actuator/health`, `GET /actuator/info` and the
metrics scrape path in every environment. Health is served at a single unchanging
path and is **not** token-protected, for a specific reason rather than a lax one:
the two things that poll it — the load-balancer target group and the container
`HEALTHCHECK` — cannot present a token. Both read that same endpoint, which is
why container-level and load-balancer-level health are always the same verdict.

Both probes speak **TLS**, and that is a constraint rather than a preference:
`infra/modules/ecs-service` constrains the target protocol to the single value
`HTTPS`, and that one setting fixes the protocol of both the target group and its
health check. A task serving cleartext would therefore receive every probe over
TLS against a listener that cannot complete a handshake — it would never turn
healthy, would be deregistered and replaced, and would look perfectly fine in its
own logs the whole time.


## API Endpoints

The published contract is `src/main/resources/openapi/account-api.yaml`, an
**OpenAPI 3.1** document. It is the contract of record: a client is written
against it rather than against the controller signatures, and the browser SPA's
contract test reads it directly — `ui/src/api/contracts.test.ts` resolves
`services/*/src/main/resources/openapi/` and maps this context to
`account-api.yaml`, so drift between this module's contract and what the SPA
expects fails the UI build rather than surfacing at run time.

The eleven operations — three end-user and eight internal — fall into two tiers,
and the contract tags every one of them
so the tier is machine-readable. `end-user` operations are reached by the browser
through the API gateway and are authorized from a Cognito JWT. `internal`
operations are service-to-service and additionally require the internal service
token, with a per-operation scope such as `internal:account-context.account.read`.

### End-user operations

| Method | Path | Purpose | Baseline origin |
|---|---|---|---|
| `POST` | `/api/v1/accounts/view` | Read one account and its customer for the account-view screen | `COACTVWC` |
| `POST` | `/api/v1/accounts/update` | Apply an edited account and customer under a revision precondition | `COACTUPC` |
| `POST` | `/api/v1/accounts/card-cross-references/search` | List an account's cross-reference rows in card-number order | `CBACT03C` via the `CXACAIX` path |

Every one of the three is a `POST` on a fixed address, and two of them are reads.
That is deliberate and it is not REST pedantry being ignored — it is the only
control available over one specific exposure. Load-balancer access logging is
mandatory in this deployment and an ELB access record has no field allow-list: the
**request line** is always written, in full, composed by the load balancer itself
before any application code runs, so neither `LogSafeText` nor `CardNumberMasker`
nor `GlobalExceptionHandler` can reach it. This migration's sensitive-data contract
in [`docs/architecture/observability.md`](../../docs/architecture/observability.md)
names account and customer identifiers alongside the primary account number as
values a durable diagnostic may not hold, so the identifier has to travel where the
access record does not look, which is the body. The keyed forms — `GET
/api/v1/accounts/{accountId}/view`, `PUT /api/v1/accounts/{accountId}` and `GET
/api/v1/accounts/{accountId}/card-cross-references` — are **removed** rather than
retained as aliases: an alias would leave the disclosure reachable by anyone who
addressed the older shape. `AccountContextContractTest` fails the build if any
published path template or declared parameter regains a place to put an identifier.

What the two reads give up is cacheability by method semantics, which costs
nothing here: both answers carry the revision a caller submits back as `If-Match`,
so a cached body would produce a conflict that did not exist. What the edit gives
up is idempotence by method semantics, which it never had in effect — the
precondition refuses a repeated submission rather than applying it twice. The
`cursor` and `direction` parameters of the walk stay in the query string, because a
sealed cursor is confidential by construction and a direction is one of two
published words, so neither is a value the contract prohibits.

### Internal operations

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/v1/accounts/lookup` | Read the limits and posted balance of one account |
| `POST` | `/api/v1/card-xrefs/lookup` | Resolve a primary account number to its account and customer |
| `POST` | `/api/v1/card-xrefs/lookup-by-account` | Resolve an account to the account and customer its lowest card names |
| `POST` | `/api/v1/card-xrefs/search-by-account` | Walk one account's cross-reference rows a page at a time |
| `GET` | `/api/v1/customers` | Read one ascending page of the customer master |
| `POST` | `/api/v1/customers/lookup` | Report whether the customer master holds one customer |
| `POST` | `/api/v1/customers/record` | Read one customer of the customer master by its key |
| `POST` | `/api/v1/customers/display` | Read the nine screen-display fields of one customer |

Three of these are the by-account access path that replaces the `CXACAIX`
alternate index — `lookup-by-account`, `search-by-account` and the end-user
`card-cross-references/search` walk. See [The alternate index is a real access
path](#the-alternate-index-is-a-real-access-path) for why that path has to exist
at all.

`POST /api/v1/customers/display` is the one whose scope is a decision rather than
the obvious choice, so it is worth a paragraph. It answers with exactly nine
members — `firstName`, `middleName`, `lastName`, `addressLine1`, `addressLine2`,
`addressLine3`, `stateCode`, `zipCode` and `phoneNumber1` — and with no protected
value of any kind: the two encrypted identifiers and the credit score are **absent
rather than masked**, because a masked member is still a member a future consumer
would begin reading. It exists because a neighbouring context's pending-authorization
detail screen had nowhere to read those fields from and was reading them from the
wrong place — it issued the existence check at `/api/v1/customers/lookup`, which
answers 204 or 404 with **no body at all** by contract, so a bodiless answer
deserialised to nothing and the screen's cardholder fields rendered as absent on
every request while nothing anywhere failed.

Its scope is `internal:account-context.customer.read`, the **decision-read** authority
that consumer already holds, and deliberately **not** `internal:customer-master.read`.
That second scope is what `/api/v1/customers/record` requires and this system mints it
for no context, because the record read answers with all eighteen fields including a
national identifier, a government-issued identifier and a credit score for any
customer. Pointing the consumer at the record read would have needed no new type at
all; it was rejected on least privilege, since granting the master scope to a context
that renders nine fields is exactly the escalation the scope split was introduced to
prevent. What the projection costs is coupling accepted on purpose — this contract now
carries a field list belonging to another context's screen, so widening that screen
later requires a change here. What it buys is that the exposure is a decision this
context takes and can refuse, which is what owning the customer master means.

### The update endpoint returns 409 on a version conflict

`POST /api/v1/accounts/update` responds **409 Conflict** when the stored
revision has moved since the client read it, and the response body carries this
message character-for-character:

```text
Record changed by some one else. Please review
```

Note *"some one"* as two words. That is not a typo introduced here — it is the
literal declared on line 522 of
[`COACTUPC.cbl`](../../app/cbl/COACTUPC.cbl), under the `88` condition name
`DATA-WAS-CHANGED-BEFORE-UPDATE` on line 521, and it is 46 characters long. The
constant lives once, in `common-lib`'s `ApiError`, and the mapping from the
persistence exception to the 409 is `GlobalExceptionHandler` in `common-lib`.
Neither is re-implemented here, and neither should be.

### Security and identity

- **Identity comes from a signed claim, never from a request field.** The filter
  chain is an OAuth2 resource server validating the Cognito JWT, and
  `JwtRoleConverter` from `common-lib` turns the `cognito:groups` claim into
  authorities. A token whose claim is absent, or is not a collection, is granted
  an **empty** authority set — authenticated but unauthorized, which are
  deliberately not the same condition.
- **No route of this context is administrator-only.** The baseline reaches both
  account view and account update from the main menu, which both user types
  reach, so restricting either would remove a capability an ordinary user has
  today.
- **Card verification values are never returned by any endpoint here.** This
  context does not read, store or expose one.
- **Primary account numbers are masked to their last four digits wherever this
  context returns one.** The single administrative endpoint that returns an
  unmasked number belongs to **`card-service`, not to this module** — so a reader
  looking for the exception to the masking rule will not find it here, and should
  not add one.
- **Social security numbers and government-issued identifiers are stored
  encrypted and returned masked.** See [Data Model](#data-model).


## Data Model

This module owns the `account` schema and nothing else. **Three of its four tables**
descend directly from a copybook record, and every column type in those three was
derived mechanically from a `PICTURE` clause rather than chosen. The fourth,
`inquiry_reply_ledger`, derives from no copybook — it carries no migrated record and
no reference field — so it is documented separately below and is deliberately absent
from the derivation table that follows, whose whole purpose is to record a baseline
provenance for every column it lists.

| Record | Declared length | Copybook |
|---|---|---|
| `ACCOUNT-RECORD` | **300** bytes | [`app/cpy/CVACT01Y.cpy`](../../app/cpy/CVACT01Y.cpy) |
| `CUSTOMER-RECORD` | **500** bytes | [`app/cpy/CVCUS01Y.cpy`](../../app/cpy/CVCUS01Y.cpy) |
| `CARD-XREF-RECORD` | **50** bytes | [`app/cpy/CVACT03Y.cpy`](../../app/cpy/CVACT03Y.cpy) |

Each length is declared in the copybook's own header comment, so it is the
baseline's figure and not a measurement of ours.

### `account.accounts`

| Column | Type | From |
|---|---|---|
| `account_id` | `BIGINT` (primary key) | `ACCT-ID PIC 9(11)` |
| `active_status` | `CHAR(1)`, `CHECK IN ('Y','N')` | `ACCT-ACTIVE-STATUS PIC X(01)` |
| `curr_bal` | `NUMERIC(12,2)` | `ACCT-CURR-BAL PIC S9(10)V99` (line 7) |
| `credit_limit` | `NUMERIC(12,2)` | `ACCT-CREDIT-LIMIT PIC S9(10)V99` |
| `cash_credit_limit` | `NUMERIC(12,2)` | `ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99` |
| `open_date` | `DATE` | `ACCT-OPEN-DATE PIC X(10)` |
| `expiration_date` | `DATE` | `ACCT-EXPIRAION-DATE PIC X(10)` (line 11) — **name corrected** |
| `reissue_date` | `DATE` | `ACCT-REISSUE-DATE PIC X(10)` |
| `curr_cyc_credit` | `NUMERIC(12,2)` | `ACCT-CURR-CYC-CREDIT PIC S9(10)V99` |
| `curr_cyc_debit` | `NUMERIC(12,2)` | `ACCT-CURR-CYC-DEBIT PIC S9(10)V99` |
| `addr_zip` | `CHAR(10)` | `ACCT-ADDR-ZIP PIC X(10)` |
| `group_id` | `CHAR(10)` | `ACCT-GROUP-ID PIC X(10)` |
| `version` | `BIGINT NOT NULL DEFAULT 0` | *no source* — the JPA `@Version` column |

Assumptions: the four `PIC X(10)` date fields narrow to `DATE` rather than
staying `CHAR(10)` because the baseline already stores them in `'YYYY-MM-DD'`
form. That ordering is the reason the narrowing is safe: an ISO-ordered string
compares lexically in exactly the order it compares chronologically, so every
existing comparison and sort keeps its result while gaining type checking and a
real date domain. Had the baseline used `MM/DD/YYYY`, this narrowing would have
silently changed ordering behaviour and could not have been done this way.

### `account.customers`

| Column | Type | From |
|---|---|---|
| `customer_id` | `BIGINT` (primary key) | `CUST-ID PIC 9(09)` |
| `first_name`, `last_name` | `VARCHAR(25) NOT NULL` | `PIC X(25)` |
| `middle_name` | `VARCHAR(25)` nullable | `PIC X(25)` |
| `addr_line_1`, `addr_line_3` | `VARCHAR(50) NOT NULL` | `PIC X(50)` |
| `addr_line_2` | `VARCHAR(50)` nullable | `PIC X(50)` |
| `addr_state_cd` | `CHAR(2)` | `CUST-ADDR-STATE-CD PIC X(02)` |
| `addr_country_cd` | `CHAR(3)` | `CUST-ADDR-COUNTRY-CD PIC X(03)` |
| `addr_zip` | `CHAR(10)` | `CUST-ADDR-ZIP PIC X(10)` |
| `phone_num_1`, `phone_num_2` | `VARCHAR(15)` | `PIC X(15)` |
| `ssn_encrypted` | `BYTEA NOT NULL` | `CUST-SSN PIC 9(09)` |
| `govt_issued_id_encrypted` | `BYTEA` nullable | `CUST-GOVT-ISSUED-ID PIC X(20)` |
| `dob` | `DATE` | `CUST-DOB-YYYY-MM-DD PIC X(10)` |
| `eft_account_id` | `CHAR(10)` | `CUST-EFT-ACCOUNT-ID PIC X(10)` |
| `pri_card_holder_ind` | `CHAR(1)`, no check — the copybook declares no value set; the Y-or-N rule is enforced on the update path by `AccountUpdateService.editYesNo`, where `1220-EDIT-YESNO` enforces it | `CUST-PRI-CARD-HOLDER-IND PIC X(01)` |
| `fico_credit_score` | `SMALLINT` | `CUST-FICO-CREDIT-SCORE PIC 9(03)` (line 22) |
| `version` | `BIGINT NOT NULL DEFAULT 0` | *no source* — the JPA `@Version` column |

Trade-offs: the two protected identifiers become `BYTEA` holding ciphertext
rather than a typed `CHAR(9)` and `VARCHAR(20)` holding plaintext. What is given
up is real: a `BYTEA` column cannot be searched by value, range-scanned or
usefully indexed, and every read pays a decrypt. What is bought is that a
database dump, a snapshot restore and a replica all carry ciphertext instead of
national identifiers. `CustomerIdentifierCipher` owns that boundary — it is the
one class that frames an envelope for these two columns — and
`CustomerIdentifierProtectionConfig` only wires it to the mapper's port. The
values are returned **masked** rather than decrypted for display.

> ⚠️ **Refactoring Rationale.** This paragraph named both classes as owners, and
> for a period both were: the configuration held a private nested implementation
> that framed `[length][key][IV][ciphertext]` while the cipher framed
> `[CDCI][version][length][key][IV][ciphertext]`, and which one wrote a given row
> depended on which beans a context registered. Nothing detected it because this
> service publishes no decipher path for these two columns. The configuration now
> delegates, so there is exactly **one** writer of this envelope — which is what
> lets `data-migration` reproduce it byte for byte, asserted across both trees by
> `CustomerIdentifierCipherTest` and `test_protected_columns.py`.

Assumptions: the FICO score narrows to `SMALLINT` because `PIC 9(03)` bounds
it to three digits, which fits `SMALLINT` with room to spare; `INTEGER` would
have cost twice the width for a domain that cannot use it.

### `account.card_xref`

| Column | Type | From |
|---|---|---|
| `card_num` | `CHAR(16)` (primary key) | `XREF-CARD-NUM PIC X(16)` |
| `customer_id` | `BIGINT` | `XREF-CUST-ID PIC 9(09)` |
| `account_id` | `BIGINT` | `XREF-ACCT-ID PIC 9(11)` |

One secondary index exists on this table and it is not an optimisation:

```sql
CREATE INDEX idx_card_xref_account_id ON account.card_xref (account_id);
```

### `account.inquiry_reply_ledger`

The fourth table, created by `V2__account_inquiry_reply_ledger.sql` rather than by
`V1__account.sql`, and the only one here with **no copybook behind it**. It exists
because the asynchronous inquiry consumer sends its reply and then returns, and the
queue acknowledges the request only on that clean return — so a task killed between
the two leaves the request visible again, and the next delivery would send a *second*
reply for one question. The ledger records the answer, commits it, and only then
sends; a redelivery finds the recorded answer and re-sends **the same bytes** instead
of composing a new one.

| Column | Type | Holds |
|---|---|---|
| `request_key` | `VARCHAR(128)` (primary key) | The claim key: the queue service's own identifier for the delivery, which is stable across every redelivery of one message and unique per accepted send |
| `status` | `VARCHAR(8) NOT NULL` | `PENDING` or `SENT`, and no third value — `ck_inquiry_reply_ledger_status` |
| `reply_payload` | `TEXT NOT NULL` | The framed reply, verbatim, exactly as it went to the queue |
| `reply_to_queue_url` | `VARCHAR(1024) NOT NULL` | The resolved destination the first send used |
| `correlation_id`, `message_id` | `VARCHAR(128)` nullable | The two identities the request supplied, echoed back unchanged; null where it supplied none, or where the consumer refused one |
| `claimed_at` | `TIMESTAMP(6) NOT NULL` | When the answer was recorded — the column pruning is expressed over |
| `sent_at` | `TIMESTAMP(6)` nullable | When it reached the queue; tied to `status` by `ck_inquiry_reply_ledger_sent_instant` |
| `attempts` | `INTEGER NOT NULL DEFAULT 0` | Sends of this answer, and nothing else |

One index exists, and like the cross-reference index above it is not an optimisation
of a read this module performs:

```sql
CREATE INDEX idx_inquiry_reply_ledger_claimed_at
    ON account.inquiry_reply_ledger (claimed_at);
```

Every read the exchange performs is by primary key, so the exchange needs no index at
all. This one exists for **pruning**, which is worth stating plainly because the
lifecycle is not self-managing. The table grows by one row per distinct request and
**nothing in the exchange ever removes a row** — deliberately, since the runtime role
holds no `DELETE` on this schema. Retention is therefore an operator action under a
privileged role, and a row is safe to remove once it is older than the request queue's
**message-retention period**: past that point the request it answers can no longer be
redelivered, so the row can no longer suppress anything. Without the index a prune
would scan the whole ledger; with it the prune is bounded by the rows it deletes.

Assumptions: this is a claim-then-send ledger and **not** a transactional outbox, and
the difference is a requirement rather than a preference. The authorization context
does use an outbox for its reply, because it writes business rows the reply describes
and must guarantee a reply **exists** for every committed decision. This exchange
writes nothing — the account read is read-only — so there is no committed state a
missing reply would contradict. What it needs is the opposite guarantee, that a reply
is not sent **twice**, and a claim-then-send ledger provides that with no second
background component to operate. See [NO transactional outbox in this service — and
why](#no-transactional-outbox-in-this-service--and-why).

### `FILLER` is dropped, and each drop is recorded

`FILLER` in these records is padding to a fixed length, not data. Every drop is
recorded per record so the arithmetic between the declared length and the column
list is always reproducible:

| Record | Dropped | Copybook line |
|---|---|---|
| `ACCOUNT-RECORD` | `FILLER PIC X(178)` | `CVACT01Y.cpy` line **17** |
| `CUSTOMER-RECORD` | `FILLER PIC X(168)` | `CVCUS01Y.cpy` line **23** |
| `CARD-XREF-RECORD` | `FILLER PIC X(14)` | `CVACT03Y.cpy` line **8** |

### Migration and ownership

**Two** migrations exist in `src/main/resources/db/migration`, and a reader looking
for a table must know which one to open:

| Migration | Creates | Why it is separate |
|---|---|---|
| `V1__account.sql` | `accounts`, `customers`, `card_xref`, and `idx_card_xref_account_id` | The three copybook-derived tables, versioned together because they migrate together |
| `V2__account_inquiry_reply_ledger.sql` | `inquiry_reply_ledger` and `idx_inquiry_reply_ledger_claimed_at` | Purely additive — it creates one table and one index and alters nothing that exists. Folding it into `V1` would have rewritten a migration that had already been applied, which Flyway refuses by checksum, so a separate version is the only shape that reaches an existing database at all |

Both carry a file header block plus an adjacent `-- WHY :` comment on every
non-obvious constraint and index — in `V1` the two `BYTEA` encryption choices, the
secondary index standing in for `CXACAIX`, the `DATE` narrowing and the two version
columns; in `V2` the claim key's width and provenance, the two-state domain, the
verbatim payload, and the pruning index. A reader who wants the reasoning for a column
reads it next to the column, not in this file.

Assumptions: `V2` needs no grant of its own. `V0__schemas_and_roles.sql` sets default
privileges for tables the schema owner `carddemo_account_owner` creates, granting
`SELECT`, `INSERT` and `UPDATE` to the runtime role `carddemo_account` — exactly the
three verbs the ledger uses, and deliberately **not** `DELETE`, which is why pruning is
an operator action rather than something the exchange can do to itself.

Flyway applies both migrations at startup, in version order. Two artifacts are required, not one:
`flyway-core` and **`flyway-database-postgresql`**, both at 13.0.0 and both
inherited from the parent POM. The companion is mandatory because Flyway 10 and
later moved PostgreSQL support out of core, so core alone compiles and packages
perfectly and then fails at **runtime** — which is the worst place to discover a
missing dependency and the reason it is called out here rather than left to the
POM.

The schema itself, its owning role and its grants are **not** created by this
module. They are bootstrapped by
[`data-migration/sql/V0__schemas_and_roles.sql`](../../data-migration/sql/V0__schemas_and_roles.sql),
which creates all eight schemas and their roles. This module's migration creates
tables inside a schema that already exists.

### Two mapper decisions that are not one-to-one

The anti-corruption layer is hand-written, and these two places are the clearest
demonstration of why a generated mapper could not stand in for it. Neither
mapping is derivable from the field names, so each carries its justification at
the mapping site.

1. **The screen's city field maps to `CUST-ADDR-LINE-3`.** `ACSCITYI PIC X(50)`
   sits at line 252 of [`app/cpy-bms/COACTUP.CPY`](../../app/cpy-bms/COACTUP.CPY),
   but **there is no `CUST-CITY` field in the customer record** — `CVCUS01Y.cpy`
   declares `CUST-ADDR-LINE-1`, `-2` and `-3` at lines 9 to 11, then jumps
   straight to `CUST-ADDR-STATE-CD`. Line 3 is where the city lives, and the two
   widths agree at 50, which is what makes the mapping sound rather than merely
   convenient.
2. **The screen's ZIP field is narrower than the record's.** `ACSZIPCI PIC X(5)`
   at line 246 of the same symbolic map feeds `CUST-ADDR-ZIP PIC X(10)` at
   `CVCUS01Y.cpy` line 14. The screen accepts five characters; the record holds
   ten. The mapper must not treat the widths as interchangeable in either
   direction.

### Encryption at rest is a correction, not a port

The baseline stored none of this data recoverably. Every CICS file resource
backing this context is defined with `RECOVERY(NONE)` and `JOURNAL(NO)` in
[`app/csd/CARDDEMO.CSD`](../../app/csd/CARDDEMO.CSD): `ACCTDAT` at line 9,
`CCXREF` at line 46, `CUSTDAT` at line 59 and `CXACAIX` at line 72. The target
encrypts at rest with a customer-managed key and takes automated backups, and
that is a deliberate, documented **improvement** rather than a preserved
behaviour — there is nothing here to be faithful to.

The same definitions also declare `READINTEG(UNCOMMITTED)`, which is weaker than
PostgreSQL's default `READ COMMITTED`. The target's isolation is therefore
strictly **stronger** than the baseline's. That direction matters and is worth
stating: a migration may tighten isolation without breaking parity, because no
baseline behaviour depended on reading an uncommitted change, but loosening it
would have introduced reads the baseline could never produce.

### Address validation queries reference data it does not own

`AddressValidationService` **queries** three allow-list tables and owns none of
them; `reference-service` seeds them, and `RestReferenceAddressLookup` is the
adapter that reaches them. The codes originate in
[`app/cpy/CSLKPCDY.cpy`](../../app/cpy/CSLKPCDY.cpy) (1318 lines), whose header
names exactly three repositories — North America phone area codes, United States
state codes, and United States state plus the first two of ZIP:

| Repository | Declaration | Condition name |
|---|---|---|
| Phone area code | `01 WS-US-PHONE-AREA-CODE-TO-EDIT PIC XXX.` (line 24) | `88 VALID-PHONE-AREA-CODE` (line 30) |
| State code | `01 US-STATE-CODE-TO-EDIT PIC X(2).` (line 1012) | `88 VALID-US-STATE-CODE` (line 1013) |
| State + ZIP prefix | `01 US-STATE-ZIPCODE-TO-EDIT.` (line 1071) with `02 US-STATE-AND-FIRST-ZIP2 PIC X(4)` (line 1072) | `88 VALID-US-STATE-ZIP-CD2-COMBO` (line 1073) |

Two structural details of the third repository are easy to miss and both change
what the check actually does. The `88` sits on the **`02`** level, not the `01`,
so the combination test examines only the **first four characters** — a state code
plus two ZIP digits. And the group has a fourth component,
`02 LAST-3-OF-ZIP PIC X(3)` at line 1314, which exists to make the group span a
full ZIP but is **not itself validated** by any condition name.

Assumptions: the phone area-code list is a fixed allow-list captured at a
point in time, not a live lookup against the numbering plan that maintains it.
The practical consequence is that a newly assigned area code is rejected until
the seeded reference data is refreshed. This module inherits that behaviour
deliberately rather than substituting a permissive regular expression, because
loosening the check would accept input the baseline rejects and that is a parity
change, not an improvement.


## Messaging — the Account Inquiry Consumer

[`app/app-vsam-mq/cbl/COACCT01.cbl`](../../app/app-vsam-mq/cbl/COACCT01.cbl) is a
620-line MQ program that answers account inquiries over a request queue and a
reply queue. It belongs to **this** context rather than to a service of its own,
and that is a reading of the program rather than an interpretation: line 171 is
`COPY CVACT01Y.`, so it consumes the same 300-byte `ACCOUNT-RECORD` this context
owns, and its `EXEC CICS READ` at lines 396 to 401 reads straight `INTO
(ACCOUNT-RECORD)`. Standing it up separately would have split write ownership of
one table across two deployables, which is the failure mode bounded contexts
exist to prevent.

> **Citation convention for this program.** `COACCT01.cbl` is in **legacy
> sequence-numbered format** — columns 1 to 6 carry sequence numbers and columns
> 73 to 80 a second identifier field — so its sequence numbers do **not** equal
> its physical line numbers. Every line number in this file is a **physical**
> line, reproducible with `sed -n '<n>p'`.

### Target queues

| Target queue | Replaces | Type | Notes |
|---|---|---|---|
| `carddemo-inquiry-request-<env>` | the MQ request queue | Standard | Inquiry has no ordering requirement |
| `carddemo-inquiry-reply-<env>` | the MQ reply queue | Standard | The reply destination travels on the request |
| `carddemo-error-<env>` | the MQ error queue | Standard | Terminal error sink |

Each of the three has its own dead-letter queue with a redrive policy at
`maxReceiveCount` **5**.

There are **three** queues here, not two, and the third is easy to overlook. The
baseline opens all three in separate paragraphs: `2300-OPEN-INPUT-QUEUE` at line
222, `2400-OPEN-OUTPUT-QUEUE` at line 255 and `2100-OPEN-ERROR-QUEUE` at line
289. The dedicated error sink is part of the contract, not an addition.

### How the message descriptor maps

The baseline reads the MQ message descriptor field by field once the get
succeeds, at lines 363 to 372, and saves three of those values for the reply. Each
one has a direct SQS counterpart:

| Baseline | Physical line | Target |
|---|---|---|
| `MOVE MQMD-MSGID TO MQ-MSG-ID` | 364 | the SQS message identifier |
| `MOVE MQMD-CORRELID TO MQ-CORRELID` | 365 | the `correlationId` message attribute |
| `MOVE MQMD-REPLYTOQ TO MQ-QUEUE-REPLY` | **366** | the `replyToQueueUrl` message attribute |
| `MOVE MQ-CORRELID TO SAVE-CORELID` | 370 | held across the turn for the reply |
| `MOVE SAVE-CORELID TO MQMD-CORRELID` | 470 | echoed **verbatim** onto the reply |
| `MOVE 5000 TO MQGMO-WAITINTERVAL` | 337 | `WaitTimeSeconds=5` long polling |
| `MQRC-NO-MSG-AVAILABLE` → `SET NO-MORE-MSGS TO TRUE` | 377 to 378 | the bounded drain's exit condition |
| `MOVE MQFMT-STRING TO MQMD-FORMAT` | 471 | the `contentType` attribute — `text/plain` |

`01 SAVE-CORELID PIC X(24).` at line 55 is the field that carries the correlation
identity across the turn, and the reply echoes it **unchanged**. The
`correlationId` and `replyToQueueUrl` attribute names are published as constants
on `InquiryMessageListener` so a producer and a test can assert on them rather
than repeating string literals.

Assumptions: the reply destination arrives **on the message** and is not
configured. Line 366 reads it out of the descriptor rather than out of
`REPLY-QUEUE-NAME`, so a requester chooses where its answer goes. The target
honours that: the listener resolves the destination from the `replyToQueueUrl`
attribute, and the configured reply queue is the fallback rather than the rule.

Assumptions: with `MQFMT-STRING` set at line 471 the payload is a string, so
**field position and width *are* the interface.** The fixed-layout reply is
reproduced exactly — a fixed one-thousand-character message, padded with spaces —
using `InquiryRequestCodec` and `ZonedDecimalCodec` from `common-lib` together
with `Money` for the amounts. None of those layouts is re-declared in this module;
a second copy of a record layout is a second thing that can drift.

⚠️ **The declared `contentType` is `text/plain`, deliberately *not* `text/csv`.**
Alternatives Considered: `text/csv` is the obvious label for a string payload on
a migrated MQ queue and is wrong here. This inquiry payload is **fixed-width**, not
delimited; `text/csv` is reserved in this migration for the authorization flow,
whose request genuinely is an eighteen-field comma-separated record. A consumer
that trusted a `text/csv` label on this queue would split on commas and find a
single field, or would split one free-text value that happened to contain a comma
into two. Getting these two labels the wrong way round is the same class of
cross-service error as the outbox question below.

### Listener posture

The listener is a `@Service` carrying
`@SqsListener(queueNames = "${carddemo.account.inquiry.request-queue}")`, and its
acknowledgement model is **delete-on-success under the queue's own visibility
period**. Nothing is acknowledged for a request that was not answered; a failed
handling returns without acknowledging, the message reappears after the
visibility period, and the redrive policy parks it at five receives. The
visibility period itself is provisioned by `infra/modules/sqs` — its
`visibility_timeout_seconds` defaults to 60 — and is supplied to the container as
a property, so the client's own bounds can be sized against it.

`PROGRAM-ID. COACCT01 IS INITIAL.` on line 2 is worth one line of its own.
`IS INITIAL` resets `WORKING-STORAGE` to its declared state on **every**
invocation, so the baseline program deliberately carried nothing between
messages. That is precisely the posture of a stateless per-message listener bean,
and it means no listener state persists from one message to the next here either
— the analogue is exact rather than approximate.

**This flow needs no transactional outbox, and that is an evidenced decision
rather than an omission.** The reasoning is in [NO transactional outbox in this
service — and why](#no-transactional-outbox-in-this-service--and-why), which is
the first thing to read before adding one.


## Migrated Program Inventory

Six programs. Every line count below is a **physical** line count, obtained with
`wc -l` against the file named, not a COBOL sequence number.

| Source program | Physical lines | CICS transaction | Target Java type | Notes |
|---|---|---|---|---|
| [`app/cbl/COACTVWC.cbl`](../../app/cbl/COACTVWC.cbl) | 941 | `CAVW` | `AccountViewService`, `AccountController` | Three-hop read composition |
| [`app/cbl/COACTUPC.cbl`](../../app/cbl/COACTUPC.cbl) | 4236 | `CAUP` | `AccountUpdateService` | Largest online program in the baseline; 16 validation paragraphs |
| [`app/cbl/CBACT01C.cbl`](../../app/cbl/CBACT01C.cbl) | 430 | — | `AccountRepository` read path | Sequential account-file reader |
| [`app/cbl/CBACT03C.cbl`](../../app/cbl/CBACT03C.cbl) | 178 | — | `CardXrefRepository` read path | Sequential cross-reference reader |
| [`app/cbl/CBCUS01C.cbl`](../../app/cbl/CBCUS01C.cbl) | 178 | — | `CustomerRepository` read path | Sequential customer reader |
| [`app/app-vsam-mq/cbl/COACCT01.cbl`](../../app/app-vsam-mq/cbl/COACCT01.cbl) | 620 | — | `InquiryMessageListener` | MQ account-inquiry extension |

### The two CICS transaction bindings

[`app/csd/CARDDEMO.CSD`](../../app/csd/CARDDEMO.CSD) binds both online programs to
a transaction identifier:

| Transaction | Defined at | Program binding |
|---|---|---|
| `CAUP` | line 306, described on line 307 | line 308 — `PROGRAM(COACTUPC) TWASIZE(0)` |
| `CAVW` | line 317, with no description clause | line 318 — `PROGRAM(COACTVWC) TWASIZE(0)` |

Both carry **`TWASIZE(0)`**, and that single value is what makes a stateless REST
target viable rather than merely desirable. A Transaction Work Area is the one
place a CICS transaction could have kept private storage across a screen turn; at
size zero there is none, so *all* continuity between turns lived in the
`COMMAREA` the client echoed back. There is no hidden server-side state to
reproduce, because the baseline had nowhere to hide any.

### `COACTVWC` composes three reads, in order, short-circuiting on each miss

`9000-READ-ACCT` at line **687** is the whole read contract of the account-view
screen, and it is three keyed reads chained in a fixed order:

| Step | Paragraph | Line | Reads | On a miss |
|---|---|---|---|---|
| 1 | `9200-GETCARDXREF-BYACCT` | 723 | the cross-reference **by account**, via the `CXACAIX` alternate index (`DATASET` on line 728) | `GO TO 9000-READ-ACCT-EXIT` |
| 2 | `9300-GETACCTDATA-BYACCT` | 774 | the account master (`DATASET` on line 777) | `GO TO 9000-READ-ACCT-EXIT` |
| 3 | `9400-GETCUSTDATA-BYCUST` | 825 | the customer master (`DATASET` on line 827) | `GO TO 9000-READ-ACCT-EXIT` |

Assumptions: the order is a data dependency, not a style. Step 3 is keyed by a
customer identifier the program does not have until step 1 has run — the paragraph
moves `CDEMO-CUST-ID` into the customer read key only *after* the cross-reference
read returns. The three hops therefore cannot be reordered or issued in parallel,
and `AccountViewService` composes them in the same sequence with the same
short-circuit at each stage, so a missing cross-reference row produces the
cross-reference message rather than a customer-not-found message.

### `COACTUPC` carries sixteen validation paragraphs

They span physical lines **1783 to 2536**, driven from `1200-EDIT-MAP-INPUTS` at
line 1429. Each becomes a named method on `AccountUpdateService`, which is what
lets the traceability matrix cite paragraph-to-method pairs rather than gesture at
a file:

| Paragraph | Line | | Paragraph | Line |
|---|---|---|---|---|
| `1210-EDIT-ACCOUNT` | 1783 | | `1250-EDIT-SIGNED-9V2` | 2180 |
| `1215-EDIT-MANDATORY` | 1824 | | `1260-EDIT-US-PHONE-NUM` | 2225 |
| `1220-EDIT-YESNO` | 1856 | | ` EDIT-AREA-CODE` | 2246 |
| `1225-EDIT-ALPHA-REQD` | 1898 | | ` EDIT-US-PHONE-PREFIX` | 2316 |
| `1230-EDIT-ALPHANUM-REQD` | 1955 | | ` EDIT-US-PHONE-LINENUM` | 2370 |
| `1235-EDIT-ALPHA-OPT` | 2012 | | `1265-EDIT-US-SSN` | 2431 |
| `1240-EDIT-ALPHANUM-OPT` | 2061 | | `1270-EDIT-US-STATE-CD` | 2493 |
| `1245-EDIT-NUM-REQD` | 2109 | | `1275-EDIT-FICO-SCORE` | 2514 |
| | | | `1280-EDIT-US-STATE-ZIP-CD` | 2536 |

The three indented entries are nested inside `1260-EDIT-US-PHONE-NUM`: the
baseline validates a phone number as three independent components — area code,
prefix and line number — and the migrated validation keeps that decomposition,
because a single combined pattern could not reproduce the per-component
diagnostics the screen shows.


## Behavioural Contracts Preserved

Structure changed everywhere in this module; behaviour did not. Where behaviour
*does* differ, the difference is disclosed here and registered in
[`docs/architecture/cobol-to-service-traceability.md`](../../docs/architecture/cobol-to-service-traceability.md),
never absorbed silently.

The first two subsections are the ones that matter most. Both are cross-service
traps — each has a sibling context where the correct answer is the **opposite** —
so both are stated with the evidence that decides them.

### NO transactional outbox in this service — and why

**The account-inquiry flow is already one atomic unit of work, so it has no
lost-reply window and needs no transactional outbox.**

The evidence is in the MQ option fields of
[`COACCT01.cbl`](../../app/app-vsam-mq/cbl/COACCT01.cbl):

| Where | Physical line | What it sets |
|---|---|---|
| `4000-MAIN-PROCESS` (line 325) | 326 to 328 | `EXEC CICS SYNCPOINT` — opens the unit of work at the top of every loop turn |
| `3000-GET-REQUEST` (line 334) | **347** | `COMPUTE MQGMO-OPTIONS = MQGMO-SYNCPOINT + MQGMO-FAIL-IF-QUIESCING + MQGMO-CONVERT + MQGMO-WAIT` |
| `3000-GET-REQUEST` | 352 | `CALL 'MQGET'` — the get is *inside* the unit of work |
| the business read | 396 to 401 | `EXEC CICS READ ... INTO (ACCOUNT-RECORD)` — also inside it |
| `4100-PUT-REPLY` (line 462) | **475** | `COMPUTE MQPMO-OPTIONS = MQPMO-SYNCPOINT + MQPMO-DEFAULT-CONTEXT + MQPMO-FAIL-IF-QUIESCING` |
| `4100-PUT-REPLY` | 479 | `CALL 'MQPUT'` — the reply is inside it too |
| `9000-ERROR` (line 501) | 512 | also puts under `MQPMO-SYNCPOINT`, so even the error reply is transactional |

Get, business logic and put are therefore bracketed by one syncpoint. If the task
fails anywhere in between, the get is backed out and the request becomes visible
again — there is no state in which the work was done but the reply was lost. The
target reproduces exactly that with an SQS listener using **delete-on-success
under the queue's visibility period**, bounded by a dead-letter queue at five
receives. Nothing is acknowledged for a request that was not answered.

Assumptions: the absence of an outbox here is a *decision resting on those two
option values*, not an oversight and not work left undone. It is recorded in this
form deliberately, because the natural instinct on reading a request/reply
consumer is to look for the lost-reply window and close it — and doing so here
would add a table, a publisher and a poller to defend against a failure mode this
flow cannot exhibit.

#### The opposite is true in `authorization-service` — do not transpose them

This is the single most likely cross-service error in the migration, and the two
programs differ in the *same option field* with the *opposite value*:

| Program | Get option (line) | Put option (line) | Consequence |
|---|---|---|---|
| `COACCT01.cbl` — **this module** | `MQGMO-SYNCPOINT` (347) | `MQPMO-SYNCPOINT` (475) | Atomic. **No outbox.** |
| `COPAUA0C.cbl` — `authorization-service` | `MQGMO-NO-SYNCPOINT` (389) | `MQPMO-NO-SYNCPOINT` (753) | Reply published outside the commit. **Outbox required.** |

`COPAUA0C` commits its database work separately, at its own `EXEC CICS SYNCPOINT`
on line 335, so a crash between that commit and the put on line 753 loses a reply
the data says was produced. That is a genuine lost-reply window and
`authorization-service` closes it with an outbox. Those two lines — 389 and 753 —
are the **only** occurrences of `NO-SYNCPOINT` in that program, and there are
**none** in `COACCT01.cbl`. The distinction is mechanical and checkable; please
check it rather than reasoning by analogy from the sibling service.

### Optimistic concurrency already exists in the baseline — express it natively

`COACTUPC` implements a genuine before-image optimistic concurrency check across
the pseudo-conversational gap. It is not being introduced by the migration; it is
being expressed in the target's own vocabulary.

- **The before-image.** `05 ACUP-OLD-DETAILS.` begins at physical line **669** and
  its last line is **756**; `05 ACUP-NEW-DETAILS.` begins at **757**. The complete
  pre-edit record is snapshotted there and carried across the turn.
  > ⚠️ The design specification cites line 666 for this group. The verified
  > physical line is **669**. The correction is recorded here once so nobody
  > re-derives it.
- **Every numeric is held twice.** Each is a display field with a numeric
  `REDEFINES` over it — `ACUP-OLD-CURR-BAL PIC X(12)` at line 675 redefined as
  `ACUP-OLD-CURR-BAL-N ... PIC S9(10)V99` at lines 676 to 677, and the same
  X-over-9 pairing for the account identifier (lines 671 to 673), credit limit,
  cash credit limit, open date, expiration date and reissue date.
- **The change flag.** `05 WS-DATACHANGED-FLAG PIC X(1).` at line **168**, with
  `88 NO-CHANGES-FOUND VALUE '0'` on line 169 and
  `88 CHANGE-HAS-OCCURRED VALUE '1'` on line 170.
- **The comparison.** `9700-CHECK-CHANGE-IN-REC` at line **4109** compares the
  before-image against the record field by field, by hand.
- **The lock.** `9600-WRITE-PROCESSING` at line **3888** re-reads both `ACCTDAT`
  and `CUSTDAT` with `EXEC CICS READ FILE(...) UPDATE` *before* comparing, so the
  comparison happens under a lock that is taken and released within one task.
- **Commit.** `EXEC CICS SYNCPOINT` at lines **952 to 954**, immediately followed
  by `EXEC CICS XCTL` at lines 956 to 959.
- **Rollback.** `EXEC CICS SYNCPOINT ROLLBACK` at lines **4095 to 4103**, with the
  `ROLLBACK` verb itself on line **4100**.

#### One `88` level does two jobs — and it is not on the flag

`88 DATA-WAS-CHANGED-BEFORE-UPDATE` on line **521** is *not* a condition name on
`WS-DATACHANGED-FLAG` at line 168. It sits on the 75-character message field, and
its literal is on line **522**. Its neighbours on that same field are
`COULD-NOT-LOCK-ACCT-FOR-UPDATE` (517 to 518),
`COULD-NOT-LOCK-CUST-FOR-UPDATE` (519 to 520) and `LOCKED-BUT-UPDATE-FAILED`
(523 to 524).

The consequence is that `SET DATA-WAS-CHANGED-BEFORE-UPDATE TO TRUE` does two
things at once: it signals the conflict *and* loads the user-visible text. In the
target that two-in-one idiom necessarily splits into two artifacts — an
`OptimisticLockException` raised by the persistence layer, and an `ApiError`
carrying the message. Anyone looking for a single target construct that does both
will not find one, and should not build one.

The message, character-for-character:

```text
Record changed by some one else. Please review
```

Refactoring Rationale: the target maps this onto a JPA **`@Version`** column
on `Account` and `Customer`, with `OptimisticLockException` surfacing as **HTTP
409 Conflict** carrying that message — mapped by `GlobalExceptionHandler` in
`common-lib`, which is not re-implemented here. `EXEC CICS SYNCPOINT` becomes an
`@Transactional` boundary and `EXEC CICS SYNCPOINT ROLLBACK` becomes exception
propagation, never a manual rollback call.

Nothing is lost in that translation, and the reason is worth stating because it is
the crux: **the CICS read-for-update lock was never held across client
think-time.** It is taken in `9600-WRITE-PROCESSING` and released at the
syncpoint, all within one task — the user is not holding it while deciding what to
type. *That is exactly why the before-image exists at all.* A version column
occupies the same role: it detects a concurrent change between read and write
without holding anything in between.

#### Divergence — the comparison is case-sensitive where the baseline folded ten fields

Trade-offs: `9700-CHECK-CHANGE-IN-REC` does not compare raw bytes. It folds
case on exactly **ten** fields before comparing: one through `FUNCTION LOWER-CASE`
(the account group identifier) and nine through `FUNCTION UPPER-CASE` (first,
middle and last name, address lines 1 to 3, the state code, the country code and
the government-issued identifier) — twenty `FUNCTION` calls in ten pairs.

A version column compares a revision number, not field contents, so a
**case-only** concurrent change that the baseline would have treated as "no
change" now produces a 409. The target is therefore strictly **stricter**, never
looser. That direction is the whole point: a stricter conflict check can only
reject an update the baseline would have accepted, which is a safe failure a user
resolves by re-reading, whereas a looser one would silently overwrite a concurrent
edit. Registered as `D-UPDATE-CASE-SENSITIVE-COMPARE`.

#### Divergence — the baseline's REWRITE rollback is asymmetric

Refactoring Rationale: the two failure paths in `9600-WRITE-PROCESSING` do not
behave alike, and the asymmetry is correct in the baseline rather than a defect:

| Failure | Lines | Rollback? | Why the baseline is right |
|---|---|---|---|
| account `REWRITE` fails | 4076 to 4081 | **no** | It is the first write; nothing has been written yet, so there is nothing to back out |
| customer `REWRITE` fails | 4095 to 4104 | **yes** (`ROLLBACK` on 4100) | The account row is already rewritten, so a partial update exists and must be discarded |

Both paths set `LOCKED-BUT-UPDATE-FAILED` and branch to the same exit. What the
baseline expresses as two hand-written cases, a single `@Transactional` boundary
subsumes uniformly: propagating the exception discards whatever the transaction
had done, whether that is nothing or one row. The old approach was correct but
required the author to reason about write order at every failure site — which is
precisely the reasoning that stops being necessary, and stops being possible to
get wrong, once one boundary owns it.

### Money is exact fixed point, end to end

`ACCT-CURR-BAL PIC S9(10)V99` at line **7** of
[`app/cpy/CVACT01Y.cpy`](../../app/cpy/CVACT01Y.cpy) is the normative money
exemplar for the whole migration, and this context is where it is declared. The
contract holds at every hop:

| Hop | Representation |
|---|---|
| PostgreSQL | `NUMERIC(12,2)` |
| Java | `BigDecimal`, scale 2, `RoundingMode.HALF_UP` |
| JSON | a **string** |

`float`, `double` and JSON numbers are **forbidden** in the money path, and the
prohibition is not a convention: it is asserted by `LayeringRulesTest` in
`common-lib`, which runs against this module's own compiled classes on every
build.

Alternatives Considered: transporting money as a JSON *number*, which is the
obvious choice and is wrong here. A JSON number is parsed into an IEEE-754 double
by most clients — `JSON.parse` in a browser produces exactly that — and a double
cannot represent every two-decimal value exactly. The corruption is silent and
lands at the boundary the user actually sees: a balance renders a cent adrift with
nothing in any log to explain it. A string costs two quote characters and a parse
step at the edge, and buys exactness that cannot be lost in transit. `MoneyModule`
in `common-lib` owns the serialisation so no service can opt out of it.

The sign convention is the same concern one layer down, and the existing COBOL
suite documents it for us: `tests/README.md` §5.2 (lines 267 to 274) records that
`-fsign=EBCDIC` is **required** because the default `-fsign=ASCII` "misreads the
zoned-decimal sign overpunch and silently corrupts negative balances". Every money
field in this context's records is zoned decimal with sign overpunch, not packed,
which is the direct justification for `ZonedDecimalCodec` in `common-lib` being a
separate codec rather than a formatting helper.

### DTO numerics transport as strings, and identifiers differ from money

Assumptions: the account identifier and every money amount cross the API as a
**string**, not as a JSON number — but they are **two different string shapes**, and
conflating them is the mistake to avoid:

| Kind | Shape on the wire | Schema | Accepts |
|---|---|---|---|
| Identifiers (`accountId`, `customerId`, `cardNumber`) | digits only, no sign, no separator | `pattern: '^[0-9]{1,11}$'` for `accountId` | `'00000000011'` |
| Money (`creditLimit`, `currentBalance`, cycle credit and debit, cash limit) | **signed**, decimal point **required**, exactly two fractional digits | [`Money`](src/main/resources/openapi/account-api.yaml) — `pattern: '^-?[0-9]{1,10}\.[0-9]{2}$'` | `'5000.00'`, `'-123.45'`, `'0.00'` |

Refactoring Rationale: this section previously said that the identifier *and*
every money amount cross the API as a "digits-only string". That is right for
identifiers and wrong for money in the two ways that matter most. A negative
balance is a normal state in this domain — the reference layout declares
`ACCT-CURR-BAL` as `PIC S9(10)V99`, signed — so a client that implemented
"digits-only" would reject the leading `-` on every credit balance it was sent.
And the decimal point is not optional: `MoneyModule` in `common-lib` serialises
through `Money.toPlainString()` at scale two, so the emitted form always carries
the point and two fractional digits, and a client validating digits-only would
reject **every** amount, including `'0.00'`. The distinction is worth stating as a
table rather than a sentence because the two rules are genuinely different and a
single adjective cannot carry both.

Two independent pieces of baseline evidence support carrying these fields as
strings at all, which is why the string form is a reading of the contract rather
than a preference.

1. **The baseline itself treats these fields as characters.** The X-over-9
   `REDEFINES` pairs in `ACUP-OLD-DETAILS` exist precisely so that a value can be
   moved and compared as characters and only *interpreted* as a number when
   arithmetic is needed.
2. **Every money field on both symbolic maps is alphanumeric, not numeric.** All
   ten are `PIC X(15)`:

| Field | [`COACTVW.CPY`](../../app/cpy-bms/COACTVW.CPY) | [`COACTUP.CPY`](../../app/cpy-bms/COACTUP.CPY) |
|---|---|---|
| `ACRDLIMI` | line 78 | line 90 |
| `ACSHLIMI` | line 90 | line 114 |
| `ACURBALI` | line 102 | line 138 |
| `ACRCYCRI` | line 108 | line 144 |
| `ACRCYDBI` | line 120 | line 156 |

There is one asymmetry between the two maps worth knowing about, because it
decides which one the target follows. `ACCTSIDI` is `PIC 99999999999` on the view
map (`COACTVW.CPY` line **60**) but `PIC X(11)` on the update map (`COACTUP.CPY`
line **60**). The target takes the **stricter** update-map form: a digits-only
string, validated as eleven digits, rather than a numeric type that would accept a
sign or silently normalise leading zeros.

### The corrected misspelling

Refactoring Rationale: `ACCT-EXPIRAION-DATE` at line **11** of
`app/cpy/CVACT01Y.cpy` becomes `accounts.expiration_date` in SQL and
`expirationDate` in Java. The baseline spelling is a typographical error, not a
domain term, and propagating it into a public API and a database schema would make
every downstream consumer carry it forever — including a browser client where it
would be visible to anyone reading the network tab.

The correction is registered in
[`docs/architecture/data-model-and-schema-mapping.md`](../../docs/architecture/data-model-and-schema-mapping.md).
Two boundaries on it matter:

- **No other field in this context is renamed.** Every other column name is the
  copybook name mechanically transformed, so a reader can always predict one from
  the other.
- **The two sibling corrections belong elsewhere.** `CARD-EXPIRAION-DATE` is
  `card-service`'s and `PA-MERCHANT-CATAGORY-CODE` is
  `authorization-service`'s. There are three corrections in the migration in
  total; this module owns exactly one of them.

### The alternate index is a real access path

Assumptions: `CXACAIX` is a genuine access path that online programs read, not
decoration on a file definition. It becomes the secondary index
**`idx_card_xref_account_id`** plus a by-account repository query, and four
independent facts confirm it has to exist at all:

1. **It is defined as a CICS file in its own right.**
   [`app/csd/CARDDEMO.CSD`](../../app/csd/CARDDEMO.CSD) line **63** is
   `DEFINE FILE(CXACAIX)`, and line **64** is
   `DESCRIPTION(ALTERNATE INDEX TO CCXREF VIA ACCOUNT KEY)`.
2. **Its dataset name says what it is.** The `DSNAME` on line 65 ends
   `.CARDXREF.VSAM.AIX.PATH` — the cross-reference cluster's name plus an
   alternate-index path suffix.
3. **An online program names and uses it.**
   [`app/cbl/COACTVWC.cbl`](../../app/cbl/COACTVWC.cbl) declares
   `LIT-CARDXREFNAME-ACCT-PATH ... VALUE 'CXACAIX '` at lines **192 to 193** and
   consumes it at `DATASET (LIT-CARDXREFNAME-ACCT-PATH)` on line **728**.
4. **The base cluster cannot answer the question.**
   [`app/cbl/CBACT03C.cbl`](../../app/cbl/CBACT03C.cbl) line **32** declares
   `RECORD KEY IS FD-XREF-CARD-NUM` — the primary key is the **card number**, not
   the account identifier. Reaching the cross-reference by account is therefore
   impossible through the base key, which is precisely why a separate index exists.

> ⚠️ The CSD name of the **base cluster** is **`CCXREF`** (defined at line 37),
> not `CARDXREF`. `CARDXREF` appears only inside the dataset names. Searching the
> CSD for `DEFINE FILE(CARDXREF)` finds nothing and is a common wrong turn.

Without that index, a by-account cross-reference lookup degrades to a full scan of
the table — which is the same access path the base VSAM cluster offers and the same
reason the baseline had to build an alternate index instead of using it.

### CICS pseudo-conversational state is eliminated, not relocated

The baseline's `CDEMO-*` `COMMAREA` structure was one struct doing four unrelated
jobs. It decomposes into four different target mechanisms, and no server-side
session store is among them:

| `COMMAREA` role | Target mechanism |
|---|---|
| Navigation (`CDEMO-FROM-*`, `CDEMO-TO-*`, `CDEMO-LAST-MAP*`) | Client-side router history. **No server-side "next program" field exists at all.** |
| Identity (`CDEMO-USER-ID`, `CDEMO-USER-TYPE`) | Validated JWT claims |
| Selection context (`CDEMO-ACCT-ID`, `CDEMO-CARD-NUM`, `CDEMO-CUST-ID`) | REST path and query parameters |
| Re-entry discriminator (`CDEMO-PGM-CONTEXT`) | **Gone entirely** |

Two of those rows carry more weight than the table shows. Moving identity into a
signed claim is a **security improvement**, not a like-for-like port: the
`COMMAREA` was storage the client echoed back, so in principle a client could
assert its own user type; a signed group claim cannot be asserted by the client at
all. And moving selection context into the request path is what makes every
request **self-describing** and therefore independently authorizable — the server
never has to consult remembered state to know which account a call is about.

`TWASIZE(0)` on both transactions (CSD lines 308 and 318) confirms there was no
other hiding place. This module is consequently fully stateless: **no sticky
sessions and no server-side session store**, which is what allows several Fargate
tasks behind a load balancer to serve the same user's consecutive requests.

### Keyset pagination, never offset

Alternatives Considered: offset pagination — `LIMIT ... OFFSET ...` — was
rejected for every list this module returns. Under concurrent inserts and deletes
an offset shifts beneath the reader, so a row can be **skipped** or **repeated**
across two consecutive pages. A browse by key cannot do either, because the next
page is defined relative to the last key actually seen rather than to a count of
rows the reader assumes are still there.

That matters here for a parity reason rather than an aesthetic one: the baseline's
browse *is* keyset. It carries the cursor key across the turn and asks for the next
record strictly after it, so offset paging would have introduced skipped and
duplicated rows that the baseline could not produce. Every list here therefore
returns `PageResponse` from `common-lib` and pages over the same key columns, with
the cursor signed so a client cannot forge a position.

### User-visible message text is carried across character-for-character

Every message a user can see is reproduced exactly as the baseline emits it —
including its spacing, its capitalisation and its typographical errors. Two traps
in this context deserve to be pointed at directly, because both look like defects
worth tidying and neither is.

⚠️ **Trap 1 — a double space, and two different texts for the same condition.**
The literal `2210-EDIT-ACCOUNT` actually emits, on line **672** of
[`COACTVWC.cbl`](../../app/cbl/COACTVWC.cbl), is:

```text
Account Filter must  be a non-zero 11 digit number
```

There are **two spaces** between "must" and "be". Meanwhile the `88`-level
declarations at lines **126** and **128** of the same program carry *different*
wording for the same condition:

```text
Account number must be a non zero 11 digit number
```

Note the second is not merely respaced — it says "number" rather than "Filter",
and "non zero" rather than "non-zero". Alternatives Considered: the two
reasonable-looking alternatives are to normalise the double space as an obvious
typo, or to adopt the `88`-level wording because it reads better. Both are
rejected: **the emitted literal is what a user sees, so line 672 is the one carried
forward**, and either substitution would change a string the baseline puts on a
screen. Both texts are recorded here so the choice is auditable rather than
looking like carelessness.

⚠️ **Trap 2 — "some one" is two words.** The 409 conflict text is
`Record changed by some one else. Please review`, declared on line **522** of
[`COACTUPC.cbl`](../../app/cbl/COACTUPC.cbl). It is 46 characters. Correcting it
to "someone" would be a one-character parity break in a string a user reads.

The remaining messages in this context, verbatim and with their source lines:

| Message | Program | Line |
|---|---|---|
| `Account number not provided` | `COACTVWC.cbl` | 122 |
| `No input received` | `COACTVWC.cbl` | 124 |
| `Did not find this account in account card xref file` | `COACTVWC.cbl` | 130 |
| `Did not find this account in account master file` | `COACTVWC.cbl` | 132 |
| `Did not find associated customer in master file` | `COACTVWC.cbl` | 134 |
| `Error reading account card xref File` | `COACTVWC.cbl` | 136 |
| `Did not find cards for this search condition` | `COACTUPC.cbl` | 516 |
| `Could not lock account record for update` | `COACTUPC.cbl` | 518 |
| `Could not lock customer record for update` | `COACTUPC.cbl` | 520 |
| `Update of record failed` | `COACTUPC.cbl` | 524 |

Two of those have irregular capitalisation — `xref file` in one and `xref File` in
another, on lines 130 and 136 of the same program — and that inconsistency is
preserved too, for the same reason as the double space.


## Testing

<!-- test-inventory: 32 tests + 8 integration tests -->
**40** test classes: **32** unit and web-layer tests matching `*Test`, run by
Surefire, and **8** integration tests matching `*IT`, run by Failsafe. Every one of
the seven test packages also carries a `package-info.java`, because the
documentation gate audits test sources too.

Refactoring Rationale: this census read 26 classes (23 plus 3) and its `api` row
named four of the six classes in that package, omitting `CardXrefControllerTest`
and `CustomerControllerTest` — the web-layer tests for the cross-reference and
customer routes. Both routes are described elsewhere in this document as covered,
so the omission made the coverage of two published endpoints look like a gap that
a reader might then fill with a duplicate class. The comment line above this
paragraph is not decoration: `ServiceReadmeInventoryTest` in `common-lib` parses it
and re-measures both figures against this module's test tree, so this count now
fails the build when it drifts instead of ageing quietly.

Refactoring Rationale: the unit figure then read 28 while the tree held 29, and the
build said so: `ProtectedValueIsolationTest` was added to the `domain` package to
hold the copy-in and copy-out property of `Customer.ProtectedValueUpdate`, and the
marker above was not moved with it. That is the census check working as intended —
the figure is re-measured here rather than incremented, and the `domain` row below
names the new class so the row and the figure can be compared by reading.

Refactoring Rationale: the integration figure then read 5 while the `repository`
row below named only two of the classes in that package, and the sentence after the
table still said three. `AccountRepositoryIT` — which holds the account master's own
storage contract, its exact-decimal columns, its date narrowing and its keyed
windows — took the count higher and made that drift fail the build, which is how it
was found.

Refactoring Rationale: the paragraph above then said "all six are now named in the
table", and that figure was wrong in two directions at once, which is why it is
restated here rather than edited in place. The `repository` package holds **seven**
integration tests — `AccountRepositoryIT`, `AccountScreenProjectionIT`,
`AccountUpdateAtomicityIT`, `CardXrefRepositoryIT`, `CustomerMasterRepositoryIT`,
`CustomerRepositoryIT` and `InquiryReplyLedgerIT` — and the module holds **eight**,
because `AwsStarterRuntimeIT` lives in `config` and is not a repository test at all.
So a reader checking "six" against the table found seven and checking it against the
tree found eight, with no way to tell which of the three numbers was the defect. Both
are re-measured rather than adjusted: `find src/test -name '*IT.java' | wc -l` gives
eight, and the same command under `src/test/java/com/carddemo/account/repository`
gives seven. Assumptions: the two counts are deliberately kept as two, because the
`*IT` suffix is what Failsafe selects on while the package is what says whether a
class needs a database container — and a single figure covering both would hide that
one of the eight needs neither.

| Package | Classes | What they cover |
|---|---|---|
| `api` | `AccountControllerTest`, `AccountDispatcherTest`, `AccountContextContractTest`, `CustomerReadRouteTest`, `CardXrefControllerTest`, `CustomerControllerTest` | Web-layer binding, routing, status selection and the published contract, including the cross-reference and customer read routes |
| `service` | `AccountUpdatePreservationTest`, `CustomerMasterReadTest`, `CardXrefByAccountReadTest`, `AccountAddressValidationTest`, `InquiryMessageListenerTest`, `RestReferenceAddressLookupTest`, `CustomerIdentifierCipherTest` | The transcribed rules — the update path including the 409-on-version-conflict branch, the read composition, the by-account cross-reference read, address validation, the inquiry consumer, and identifier protection |
| `config` | `SecurityConfigTest`, `InternalApiSecurityConfigTest`, `SecurityChainDispatchTest`, `SqsConfigTest`, `OpenApiDocumentTest`, `AccountApiContractGateTest`, `AccountConfigPackageTest`, `CustomerIdentifierProtectionConfigTest`, `CustomerIdentifierProtectionWiringTest`, `AwsIntegrationStartupTest`, `AwsStarterRuntimeIT` | Filter chain and authority mapping, the internal-token chain, how BOTH chains decide a container ERROR dispatch, listener wiring, the served OpenAPI document, the committed contract's agreement with the runtime it describes, and startup |
| `mapper` | `AccountMapperTest`, `CardXrefMapperTest`, `AccountInquiryReplyMapperTest` | The anti-corruption layer — masking at the shared contract width, the misspelling correction, `FILLER` removal, the fixed-width reply |
| `repository` | `AccountRepositoryIT`, `AccountScreenProjectionIT`, `AccountUpdateAtomicityIT`, `CardXrefRepositoryIT`, `CustomerMasterRepositoryIT`, `CustomerRepositoryIT`, `InquiryReplyLedgerIT` | Testcontainers-backed PostgreSQL — the account master's column contract, exact-decimal scale, date narrowing, version conflict and keyed windows; the joined screen projection and its outer-join arms; the two-write commit boundary of the update path; the cross-reference table's own contract together with the query plan the engine chooses for the by-account read that replaces `CXACAIX`; the customer master's column widths and schema ownership; the customer record's own contract — its layout, fixture bytes, keyed read, version column and keyed windows; and the inquiry reply ledger's second-delivery conflict |
| `domain` | `DiagnosticRenderingTest`, `ProtectedValueIsolationTest` | Entity rendering — that no protected value leaks into a diagnostic string — and that a protected-value update intent is fixed when it is created, so neither the caller's array nor the value handed back can alter what is stored |
| `dto` | `AccountUpdateResponseShapeTest` | Response shape, including money as a JSON string |

All eight integration tests are named individually rather than described as a
`*RepositoryIT` family, because four of them are not named that way and one —
`AwsStarterRuntimeIT` — is not a repository test at all. Failsafe selects on the
`IT` suffix, not on the package.

```bash
# WHY : Assumptions: Failsafe binds to `integration-test` and `verify`, so the
#       eight `*IT` classes run under `verify` and NOT under `test`. A run that
#       stops at `test` therefore exercises the 32 `*Test` classes and skips all
#       eight, and with them every Testcontainers-backed database assertion --
#       including the by-account query that stands in for the CXACAIX alternate
#       index and the plan assertion that proves it resolves through an index,
#       which is the one query a reader is most likely to assume is covered.
mvn -B -f services/pom.xml -pl account-service -am verify
```

```bash
# WHY : Assumptions: these are scoped with `-pl account-service` and deliberately
#       WITHOUT `-am`, which is the opposite of every other command in this
#       section. A `-Dtest=` filter is a GLOBAL property: with `-am` the reactor
#       also builds the aggregator, whose `architecture-rules` Surefire execution
#       runs `LayeringRulesTest` out of the shared kernel, and that execution
#       receives the same filter, matches nothing, and fails the build with "No
#       tests matching pattern ... were executed!" before account-service is ever
#       reached. Refactoring Rationale: both of these commands previously carried
#       `-am` and BOTH failed for that reason, whatever was named after `-Dtest`
#       — so the shape was wrong, not just the pattern.
# WHY : Assumptions: dropping `-am` means `common-lib` is resolved from the local
#       repository rather than rebuilt, so run the full build once first (the
#       `verify` command above installs nothing, so use
#       `mvn -B -f services/pom.xml -pl common-lib install` on a fresh clone).
#       Trade-offs: that is a real prerequisite, accepted because the alternative
#       is passing `-Dsurefire.failIfNoSpecifiedTests=false` to silence the
#       aggregator — which would also silence the genuine mistyped-name failure
#       this command wants to keep.
# WHY : Assumptions: a `#` selector matches the METHOD NAME. Surefire does not
#       match `@DisplayName` text, so a pattern drawn from a display name selects
#       nothing and, correctly, fails. Refactoring Rationale: the second line read
#       `-Dtest='AccountMapperTest#*Masked*'`; no method in that class contains
#       "Masked" — the masking cases are named for what they withhold, e.g.
#       `theEchoWithholdsExactlyTheFourIdentifierComponents`. Verified by running
#       all three forms: the exact name selects 1 test, `#*Identifier*` selects 3,
#       and `#*Masked*` selects 0 and exits non-zero.
# WHY : Trade-offs: `-Dsurefire.failIfNoSpecifiedTests=false` is omitted on
#       purpose. With a mistyped class name the build then FAILS rather than
#       reporting a green run in which nothing was selected, which is the failure
#       mode this flag exists to hide.
mvn -B -f services/pom.xml -pl account-service test -Dtest=AccountUpdatePreservationTest
mvn -B -f services/pom.xml -pl account-service test -Dtest='AccountMapperTest#*Identifier*'
```

```bash
# WHY : Assumptions: a working container runtime is required -- Testcontainers
#       starts a real PostgreSQL rather than substituting an in-memory engine. That
#       is deliberate: an in-memory database would not enforce `CHAR(n)` padding,
#       `NUMERIC(12,2)` scale or the secondary index this module's queries depend
#       on, so it would pass on schemas PostgreSQL rejects.
# WHY : Assumptions: this invokes Failsafe's two goals DIRECTLY after
#       `test-compile` rather than running `verify` with the Surefire tier
#       suppressed, because no property can suppress it here. Refactoring
#       Rationale: this command used to read
#       `verify -Dtest=skip -DfailIfNoTests=false`, and neither half worked.
#       `-Dtest=skip` selects a class named "skip", which matches nothing;
#       `-DfailIfNoTests=false` cannot rescue it because this module inherits an
#       `architecture-rules` Surefire execution whose POM configuration sets
#       `<failIfNoTests>true</failIfNoTests>` explicitly, and an explicit
#       execution configuration wins over a command-line user property. Adding
#       `-Dsurefire.failIfNoSpecifiedTests=false` does not help either: that flag
#       governs a different check, and the execution still fails with the plainer
#       "No tests were executed!". Verified by running all four variants; two
#       failed at `architecture-rules`, one silently ran nothing, and only this
#       form ran the integration tier alone.
# WHY : Trade-offs: invoking plugin goals directly bypasses the lifecycle, so the
#       jar is not repackaged and nothing is installed. That is exactly what is
#       wanted for a fast database-only loop, and it is why the full `verify`
#       above remains the command to trust before pushing. Expect eight IT
#       classes declaring 49 integration tests, of which 47 run;
#       `AwsStarterRuntimeIT` contributes 0 of them by design, because its
#       LocalStack precondition is absent.
mvn -B -f services/pom.xml -pl account-service test-compile \
    failsafe:integration-test failsafe:verify -Dit.test='*IT'
```

### Fixtures are fixed-width at the declared record length

Test fixtures live under `src/test/resources/fixtures/` and are byte-exact images
of the copybook layouts — **300** bytes for an account record, **500** for a
customer record and **50** for a cross-reference record. The two account fixtures
currently present are each exactly 300 bytes, and a fixture that is not is a
broken fixture rather than a lenient one.

This is the house rule, not a local one: `tests/README.md` §12 requires that
records *"must match the `app/cpy/` copybook layouts exactly — e.g. 350-byte
daily-tran, 300-byte account — with correct zoned-decimal sign overpunch"*, and it
requires that a layout is never duplicated but kept single-sourced from
`app/cpy/`. This module follows both: layout knowledge lives in `common-lib`'s
codecs, and a fixture is data rather than a second declaration of a layout.

That is also why `config/checkstyle/suppressions.xml` exempts
`src/test/resources/fixtures/` from the documentation gate — a 300-byte account
image has no purpose, parameters or return value to describe. The exemption stops
at `resources/`: **`src/test/java/` is audited**, because that is where the rules
transcribed from the COBOL are actually asserted.

### Report directories must not be relocated

`target/surefire-reports` and `target/failsafe-reports` are collected by
`.github/workflows/services-ci.yml`, which uploads
`services/*/target/surefire-reports/**` and
`services/*/target/failsafe-reports/**` and then publishes the XML from them.
Moving or renaming either directory breaks that collection, and it breaks it
loudly rather than quietly: the upload step is declared
`if-no-files-found: error`, so an absent directory fails the job instead of
producing a green run with no test evidence attached.


## Known Limitations

Documented honestly here so that no claim made above hides a blocked capability —
the same financial-enterprise auditability requirement the COBOL suite states for
its own limitations in `tests/README.md` §1.1.

- **There is no golden-master parity oracle for this service.** The existing COBOL
  suite cannot produce one, and three independent readings of `tests/README.md`
  confirm it. First, lines **83 to 85** state that online `CO*` CICS programs
  "cannot run end-to-end without a CICS runtime", which is absent on the runner —
  and two of this module's six programs are exactly that. Second, §13 "Business
  rules under test (verbatim)" (lines **553 to 590**) asserts rules for
  `CBTRN02C`, `CBACT04C` and `TCATBAL` only; **not one** of its rules governs
  `COACTVWC`, `COACTUPC`, `CBACT01C`, `CBACT03C`, `CBCUS01C` or `COACCT01`. Third,
  and most directly: **none of those six program names appears anywhere in
  `tests/README.md` at all.**

  *Mitigation.* Parity for this context rests entirely on transcribed validation
  logic and on the copybook contracts, both cited line by line above. Tests are
  authored from the COBOL paragraphs directly — they *encode* the specification as
  the production source documents it and do not redefine it, which is the same
  stance §13 takes for the programs it does cover.

  Trade-offs: stating plainly that no oracle exists is worth more than
  the reassurance of implying one. A reader who believed a golden master backed
  these paths would treat a green build as evidence of behavioural parity, stop
  reading the COBOL, and lose the only thing that actually establishes parity here
  — the paragraph-by-paragraph correspondence. The cost of this disclosure is that
  parity claims for this module must be argued from citations rather than asserted
  from a passing suite. That cost is accepted; the alternative is a false sense of
  coverage.

- **`tests/**` and `scripts/**` are REFERENCE-ONLY.** They are never modified and
  never re-pinned by this module, and the new Java tests are strictly **additive**.
  The COBOL suite keeps its own pinned dependency set and its own workflow so that
  it can go on serving as the parity oracle for the programs it *does* cover.

- **The COBOL suite's aggregate `RC=4` is its green state, and it is not a
  regression.** That suite grades outcomes on the mainframe condition-code rubric
  in `tests/README.md` §8 (lines **412 to 427**) — 0 pass, 2 usage, 4 warn, 8 fail,
  16 fatal — with runners aggregating the worst code seen. A warn-level aggregate
  is the documented healthy result there, because two baseline programs carry an
  unfixable record-key defect in immutable reference source.

  **That rubric belongs to the COBOL oracle alone and must never reach a Maven,
  Checkstyle, Surefire or Failsafe gate in this module.** This build's exit status
  is **binary**: it passes or it fails. Nothing here skips a gate, downgrades a
  violation or tolerates a non-zero result, and nothing that does should be added.
  Reading `RC=4` as a template for "an acceptable warning level" in the Java build
  is the specific mistake this bullet exists to prevent.

- **The two screen field counts in circulation are both correct, and they count
  different things.** The design specification cites 128 fields for account update
  and 100 for account view. Those are `DFHMDF` counts in `app/bms/*.bms`, and they
  include every protected label and literal on the map. The symbolic map copybooks
  materialise only **named data fields**, of which there are **54** in
  `app/cpy-bms/COACTUP.CPY` and **37** in `app/cpy-bms/COACTVW.CPY`. Both figures
  are verifiable and neither contradicts the other; the DTO surface follows the
  symbolic-map figure, because a protected literal has nothing to transport. This
  is recorded so nobody hunts a phantom discrepancy between two documents.

- **Message-field widths differ between the screen and the program, and the
  narrower one is the contract.** `INFOMSGI` is `PIC X(45)` on both symbolic maps
  (`COACTVW.CPY` line 234, `COACTUP.CPY` line 318) while `WS-INFO-MSG` is
  `PIC X(40)` (`COACTVWC.cbl` line 110). `ERRMSGI` is `PIC X(78)` (lines 240 and
  324) while `WS-RETURN-MSG` is `PIC X(75)` (`COACTVWC.cbl` line 117). **The
  75-character program-side width is the contract to honour**, because the program
  is what composes the text; the extra three characters on the map are room the
  program never fills. Sizing a response field to 78 would permit a message the
  baseline cannot emit.

- **`AwsStarterRuntimeIT` skips when no emulator token is present.** The pinned
  emulator release exits with status 55 without an authentication token, so with
  that environment variable unset the test calls `Assumptions.abort` and reports a
  **skip** rather than a failure. A green run on such a machine has therefore *not*
  exercised the AWS starter wiring, and reading it as though it had is the mistake
  to avoid. The test provides an opt-in that turns the skip into a hard failure, so
  a pipeline that intends to cover this layer can require it rather than hope for
  it — the same posture `tests/README.md` §6 takes for its own optional AWS layer.
  The other seven integration tests need only a container runtime and always run.


## Code Documentation Standard

One user-specified rule governs this migration, and every file in this module is
subject to it.

> **Explainability rule (mandatory).** Every new or modified class, method, and
> module entry point in this module must carry a Javadoc docstring stating
> **Purpose, Parameters, Returns, and Exceptions**, and its inline comments must
> explain **why** (documenting at least one of Alternatives Considered,
> Refactoring Rationale, Assumptions, or Trade-offs) — never restate what the code
> does. This is a hard review gate.

Trivial accessors — getters or setters with no logic — may use a single-line
docstring. Everything else owes the full set.

### The four labels are plural, and that spelling is canonical

`Alternatives Considered:` · `Refactoring Rationale:` · `Assumptions:` ·
`Trade-offs:`

Use these forms and no others. The plural is canonical because **that is how the
rule itself writes them**, and the singular variants (`Assumption:`,
`Trade-off:`) that appear in prose elsewhere are not accepted spellings. This is
stated explicitly so that a later reader who encounters a singular form does not
"harmonise" the codebase toward it — the harmonisation would run the wrong way and
would break a literal search for a canonical label.

That rule is applied to this file without exception, including in one place where
the house precedent reads the other way. `tests/README.md` §1.1 tags its own
limitation with a singular, parenthesised `WHY (Trade-off):`; the corresponding
clause under [Known Limitations](#known-limitations) keeps that section's shape but
writes the label **plural**. Assumptions: the parenthesised `WHY (...)` form is a
prose convention of that section and is preserved, whereas the label *inside* it is
a canonical category name and is therefore subject to the spelling rule above. A
file that declared the singular unacceptable and then used it would be its own
counter-example.

### Where the convention is written down

- [`docs/CODE_DOCUMENTATION_STANDARD.md`](../../docs/CODE_DOCUMENTATION_STANDARD.md)
  is **the** authoritative, per-language form of this convention, with a
  conforming worked example for every language in the tree. Read it before
  authoring in this module.
- [`CONTRIBUTING.md`](../../CONTRIBUTING.md) states the obligation and owns the
  pull-request, code-of-conduct, security-notification and licensing process. For
  the per-language *form* it defers to the standard above, and so should you.
- The linters are a **floor, not a ceiling.** They implement only the subset a
  tool can decide, so passing them is necessary and never sufficient — no linter
  can determine whether a sentence is true or whether a rationale is genuine.

### The Checkstyle documentation gate

Java compliance is mechanically enforced. The facts below are the configuration as
it stands, not a summary of intent:

| Setting | Value |
|---|---|
| Bound to Maven phase | **`validate`** |
| Execution id | `checkstyle-documentation-gate` |
| Engine | `com.puppycrawl.tools:checkstyle:13.8.0` |
| Ruleset | `config/checkstyle/checkstyle.xml` |
| `failOnViolation` | `true` |
| `violationSeverity` | `warning` |
| `consoleOutput` | `true` |
| `includeTestSourceDirectory` | `true` |

Because it is bound to `validate` — the first phase of the default lifecycle — the
gate runs on **every local build**, not only in CI, and it runs *before* anything
is compiled. `violationSeverity` is deliberately looser than the ruleset's own
`error` level so that the two keep agreeing: if a rule is ever added at warning
severity it still fails the build instead of becoming a message nobody reads.

The `SuppressionFilter` in the ruleset is declared **`optional="false"`**, which
makes it **fail closed**. A missing suppressions file is an error rather than a
silent switch to auditing nothing — the failure mode that setting exists to
prevent is a green build that verified nothing at all.

**Ten checks are enabled, and these are the whole set:**

`JavadocPackage` · `MissingJavadocType` · `MissingJavadocMethod` ·
`MissingJavadocPackage` · `JavadocType` · `JavadocMethod` ·
`NonEmptyAtclauseDescription` · `SummaryJavadoc` · `AtclauseOrder` ·
`CommentsIndentation`

The ruleset declares thirteen `module` elements; `Checker`, `TreeWalker` and
`SuppressionFilter` are a container, a container and a filter, which leaves
exactly those ten. A maintainer who counts declarations reaches thirteen, so the
arithmetic is written out here to stop that difference reading as a defect.

### What the gate demands of you, concretely

Five consequences are easy to be surprised by, and all five are load-bearing in
this module:

1. **`package-info.java` is required in every package, and must itself carry
   Javadoc.** All seven main packages and all seven test packages have one. Adding
   a package without one fails the build.
2. **Every DTO record component needs an `@param`.** `JavadocType` sets
   `allowMissingParamTags="false"`, and a record's components are its type
   parameters for this purpose. All ten DTOs here are records, so this applies to
   every one of them.
3. **Private methods are in scope.** `JavadocMethod` sets
   `accessModifiers="public, protected, package, private"` and both
   `MissingJavadocType` and `MissingJavadocMethod` set `scope="private"`. There is
   no visibility below which the obligation stops.
4. **`@return` and `@throws` are mandatory, not situational.**
   `allowMissingReturnTag="false"` means every non-void method needs an `@return`,
   and `validateThrows="true"` means every declared or thrown exception needs an
   `@throws`.
5. **Entry points and configuration classes are not exempt.** `skipAnnotations` is
   deliberately **not** set, even though the upstream example for
   `MissingJavadocType` sets it to `SpringBootApplication` and `Configuration`.
   Copying that example would have exempted `AccountApplication` and every class
   under `config/` — precisely the module entry point and configuration types the
   rule's presence clause names. All eight are audited in full.

**Test sources are audited too.** `includeTestSourceDirectory` is `true`, and that
is not an oversight: the tests in this module are where the rules transcribed from
the COBOL are actually written down, so they are the last place documentation
should be optional.

### The suppressions charter, and what must never be added to it

`config/checkstyle/suppressions.xml` is the only place an exemption may be
recorded, and its charter is narrow: **generated sources and test-fixture material,
and nothing else.** Exactly two entries exist — one for
`target/generated-sources/` and one for `src/test/resources/fixtures/`.

The following must **never** be suppressed:

- **`services/*/src/main/java/**`** — not one entry, and not one pattern that could
  match it. This is the hand-authored production code the gate exists to police.
- **`mapper/**` — and this is the trap most likely to be walked into**, because
  "mapper" reads as "generated" in most codebases. Here it is the exact opposite:
  the mappers are the hand-written anti-corruption layer, and they are the
  *highest-value* documentation target in this module. Each one carries decisions a
  reader cannot recover from the code — dropping `FILLER`, masking the primary
  account number, encrypting two identifiers, renaming a misspelled field, mapping
  a screen city field onto address line 3. Exempting them would remove exactly the
  explanations that justify them.
- **`AccountApplication` or the `config/` package** — see consequence 5 above.
- **`src/test/java/` as a whole** — see the paragraph above.
- **A catch-all** — a `files` value of `.*`, or an element with neither a `files`
  nor a `checks` attribute, silently disables the entire gate while leaving the
  ruleset apparently in force.

There is also no in-code bypass, by design: the ruleset configures no
`SuppressWarningsFilter`, no `SuppressionCommentFilter` and no
`SuppressWithNearbyCommentFilter`, so a suppression cannot be written into a Java
source as an annotation or a magic comment. Every exemption is a durable, reviewable
entry in one file.

### Never relax the gate

The following are **forbidden** in this module, in the POM, in a local invocation
and in any workflow:

| Forbidden | Why |
|---|---|
| `-Dcheckstyle.skip` | Turns the gate off for a build that still reports success |
| `<skip>true</skip>` | The same, made durable in the POM |
| `failOnViolation=false` | Downgrades every violation to a message nobody reads |
| `\|\| true` after the command | Discards the exit status the gate exists to produce |
| `continue-on-error: true` | The workflow equivalent of the same |

If a build fails this gate, the fix is the missing Javadoc or the missing
rationale. **Fix the code, never the gate.**

### Modelling the rule in prose

This file follows the same convention it documents, in the one form prose can:
every fenced command block above carries a `# WHAT:` line saying what the command
does and a `# WHY :` line carrying a canonical label and a specific reason. Note
the aligned single space before the colon in `WHY :` — it exists so the two labels
line up in a fixed-width font.

```text
# WHY : <Canonical Label:> <specific justification>
```

That idiom belongs to **prose command blocks only** — this file, the other
`README.md` files and `docs/runbooks/**`. It does not belong on a statement inside
a `.java`, `.sql`, `.yml`, `.tf` or `Dockerfile` source, where a `WHAT:` line above
a statement would restate what the statement already says. In code, purpose lives
in the Javadoc and an inline comment carries one canonical label, the reason, and
what would differ under the alternative. A file-header or module-header block is
the documented exception, because for a format with no docstring construct that
header *is* the docstring.


## Technical Highlights

| Property | How it is achieved |
|---|---|
| Fully stateless | No sticky sessions and no server-side session store; identity from a signed claim, selection from the request path |
| Exact money end to end | `NUMERIC(12,2)` → `BigDecimal` scale 2 `HALF_UP` → JSON **string**, enforced by ArchUnit |
| Optimistic concurrency | JPA `@Version` on `Account` and `Customer`; `OptimisticLockException` → HTTP 409 with the baseline's verbatim message |
| One ACID boundary per request | `@Transactional`; rollback by exception propagation, never a manual call |
| Keyset pagination | `PageResponse` from `common-lib`, over the same key columns the baseline browsed, with a signed cursor |
| Real secondary access path | `idx_card_xref_account_id` replacing the `CXACAIX` alternate index |
| Encryption at rest and in transit | Customer-managed KMS key for the two protected identifiers; TLS on the listener and on the database connection |
| Zero configuration in source | Every endpoint, credential and key identifier from Parameter Store and Secrets Manager |
| Documentation enforced, not requested | Checkstyle bound to `validate`, fail-closed, ten checks, test sources included |
| Layering enforced, not intended | `LayeringRulesTest` from `common-lib` re-run against this module's own classes |

### Deliberately absent, and why

Everything below is missing **on purpose**. Each entry records its reason so that a
later reader does not add it back as an oversight. The module's POM carries the same
reasoning beside the dependency block, so the two cannot drift apart.

- **No Lombok.** Alternatives Considered: generated accessors cannot carry the
  Javadoc that Rule 1's validation gate requires, and the inherited Checkstyle
  configuration runs `MissingJavadocMethod` with `allowMissingPropertyJavadoc="false"`
  — so every generated getter would fail the gate. Java 21 records plus explicit
  constructors give the same brevity with members that can be documented.
- **No MapStruct.** Alternatives Considered: rejected for two independent
  reasons. Its most recent published release is a beta; and, decisively, the
  copybook-to-DTO mapping here is **not mechanical** — it drops `FILLER`, masks the
  primary account number to its last four digits, never serialises a card
  verification value, encrypts two customer identifiers, renames a misspelled field
  and maps a screen city field onto address line 3. Every one of those needs an
  inline justification at the mapping site, and a generated mapper has nowhere to
  hold it.
- **No resilience library and no circuit breaker.** Alternatives Considered:
  `resilience4j-spring-boot3` targets Spring Boot 3.x, and the superseded
  `spring-retry` is unnecessary because Spring Framework 7 — which arrives through
  the Boot 4.1.0 parent — moved retry into the framework core. Where declarative
  retry is used the attribute is **`maxRetries`** (total attempts equal one *plus*
  that value), enabled by **`@EnableResilientMethods`** on a configuration class.
  It is **not** `@EnableRetry` and **not** `maxAttempts`; those are the older API
  and will not activate — a silent no-op rather than a compile error, which is why
  it is written down here. A circuit breaker is omitted because the only
  synchronous hop is inside the VPC behind an internal load balancer with bounded
  timeouts, so a breaker would add a failure mode without removing one. The durable
  retry tier is the queue's own redelivery, bounded by a dead-letter queue.
- **No `BatchConfig` and no Spring Batch.** Chunk-oriented jobs and the durable job
  repository belong to `batch-service`. This module's messaging configuration is
  `SqsConfig` and there is no batch configuration of any kind here.
- **No Redis, ElastiCache, Kafka, Kinesis or read replica.** The baseline has no
  cache tier and no replica, so none is needed for parity, and each would add
  either a staleness window or a replica-lag semantic that the baseline cannot
  exhibit.
- **No two-phase commit and no saga.** Nothing in this context spans two
  datastores. The one genuinely multi-entity write — an account row and a customer
  row updated together — is a single local transaction, exactly as the baseline's
  single syncpoint made it. A saga would replace one atomic commit with committed
  steps plus compensating reversals, introducing observable partial-update states
  the baseline never produces.
- **No Maven dependency on any sibling service module.** `common-lib` is the only
  permitted intra-reactor dependency. A cross-service `..domain..` import fails
  `LayeringRulesTest` rather than a review.


## Support

For questions, issues, or improvement requests, please raise an issue in the
repository with detailed information about your concern. The maintainers will
respond according to availability.

Before raising an issue against this module, two things are worth checking, because
both are the intended behaviour rather than a defect: an aggregate `RC=4` from the
COBOL suite is its documented green state, and `AwsStarterRuntimeIT` skipping
without an emulator token is by design. Both are explained in [Known
Limitations](#known-limitations).


## Contributing

See [`CONTRIBUTING.md`](../../CONTRIBUTING.md) for the pull-request process, the
code of conduct, security-issue notification and licensing terms.

Two module-specific expectations apply on top of that process:

1. **Satisfy the documentation gate before pushing.** It is bound to `validate`, so
   `mvn -B -f services/account-service/pom.xml validate` answers the question in
   seconds. See [Code Documentation Standard](#code-documentation-standard).
2. **Never modify `app/**`.** The COBOL baseline — programs, copybooks, BMS
   mapsets, JCL, the CICS resource definitions and the seed data — is
   reference-only. Cite it by path and line, as this file does throughout; never
   edit it. The same holds for `tests/**` and `scripts/**`.


## License

This project is released under the Apache 2.0 license. See
[`LICENSE`](../../LICENSE) and [`NOTICE`](../../NOTICE) at the repository root.

---

<sub>Apache‑2.0 · This module is additive; the COBOL baseline under `app/**` is
never modified. See the root [`README.md`](../../README.md) for the application
overview, [`MIGRATION_README.md`](../../MIGRATION_README.md) for the end-to-end
build, deploy, migrate and roll-back guide, and
[`docs/CODE_DOCUMENTATION_STANDARD.md`](../../docs/CODE_DOCUMENTATION_STANDARD.md)
for the documentation convention this module is held to.</sub>
