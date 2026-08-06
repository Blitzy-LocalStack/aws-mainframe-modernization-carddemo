# CardDemo `common-lib` — the shared kernel

> **What this file is for.** Two audiences read it. An engineer who needs to
> build, test or gate this module should read §2. Anyone about to author a class
> under `services/common-lib/src/**` should read §3 through §9 first — this
> README is the contract briefing for that work, and it is written so that a
> class author needs no other document to know what belongs here, what must
> never be added, which contracts are exact, and exactly what the documentation
> gate will demand.
>
> **Sources of truth.** The immutable COBOL baseline under `app/**`, which is
> the behavioural oracle for the whole migration; the reference test suite under
> `tests/**`, which is the functional-parity oracle; and
> [`docs/CODE_DOCUMENTATION_STANDARD.md`](../../docs/CODE_DOCUMENTATION_STANDARD.md),
> which is the authoritative written documentation convention. Every citation
> below was verified against the cited file at the cited line.
>
> **`app/**`, `tests/**` and `scripts/**` are REFERENCE-ONLY.** They are read,
> cited and never modified. The baseline must remain byte-identical, and the
> reference suite carries zero permitted version drift — that is precisely what
> allows it to keep serving as the parity oracle.

---

## 1. What this module is

### 1.1 Why it exists

`common-lib` is the **shared kernel** of the migrated CardDemo services. It is
the Java analogue of compiling every COBOL program against one copybook include
path: the reference suite builds each program with
`cobc -fixed -fsign=EBCDIC --std=ibm-strict -I app/cpy`
[`tests/README.md` line 268] and requires that a record layout is *never*
duplicated but kept single-sourced from `app/cpy/` [`tests/README.md` lines 541
to 542].

Migration rule **T2** carries that discipline into Java: one `COPY` becomes one
import, and *"Shared concerns — codecs, money, errors, validation flags — live
only in `common-lib`."* The transformation plan states the consequence directly:
*"Shared concerns are never re-declared per service … This is the Java analogue
of compiling every COBOL program with a single copybook include path, and it is
the reason `common-lib` exists at all."*

So the test for whether something belongs here is not "is it useful in more than
one place" but "is it a **contract** that more than one service must honour
identically". A money rounding mode is such a contract. A record layout is such
a contract. A service's own business rule is not.

### 1.2 Package root

Fixed and non-negotiable: **`com.carddemo.common`**. Every type in this module
lives beneath it. That is what lets the architectural rule in §7 forbid
cross-service imports by package name alone, so the constraint is checkable
without a naming convention anyone has to remember.

The nine package roots in the reactor are `com.carddemo.common`, `.auth`,
`.account`, `.card`, `.transaction`, `.reference`, `.batch`, `.authorization`
and `.reporting`.

### 1.3 A library, not a deployable

This module is built **first** in the reactor and is the only module every other
module depends on. **The dependency arrow points inward only:** all eight
service modules depend on `common-lib`, and `common-lib` depends on none of
them. Its POM therefore declares zero intra-reactor dependencies.

Because it is a library and not a process, it deliberately has **none** of the
following, and their absence is a design decision rather than an omission:

| Absent here | Why |
|---|---|
| `Dockerfile` | Nothing to run. It is a compile-time dependency resolved inside each service's own build stage. |
| `<Svc>Application.java` | No executable main class, so the Spring Boot repackage goal is never applied to it. |
| Actuator health endpoint | Nothing to health-check; a library has no liveness. |
| `src/main/resources/db/migration/` | Owns no schema. Schemas belong to the services that own their data. |
| `openapi/` | Publishes no HTTP contract of its own. |
| `application.yml` | Configures no application. It ships `carddemo-common-defaults.yml`, which is a defaults contribution consumed by a host application, not an application configuration. |
| `.gitignore` | Redundant — see below. |
| Container-registry repository | Publishes no image. See §1.4. |

On the ignore file specifically: `target/` is already ignored repository-wide by
a **deliberately non-root-anchored** pattern [`.gitignore` line 92], precisely
because every `target/` appears nested under `services/<module>/` and never at
the repository root, where a leading slash would match nothing. A local ignore
file here would be redundant, and a redundant ignore rule is one more place for
the two to disagree.

### 1.4 The 9 / 8 / 10 asymmetry — do not "correct" it

Three counts in this repository deliberately disagree, and every one of them is
right:

- **9** Maven modules under `services/` — verified by counting `services/*/pom.xml`
- **8** Dockerfiles under `services/` — this module has none
- **10** container images — the eight services plus `ui` plus `data-migration`
  [`infra/modules/ecr/variables.tf` lines 95 to 104]

`common-lib` is the ninth module and the reason the first two figures differ.
Reconciling them is a real and recorded hazard: *"Counting nine Maven modules as
nine service images would therefore create an eleventh phantom repository whose
emptiness would not make `terraform apply` fail."*
[`infra/modules/ecr/README.md` line 38]. This module's own POM records the same
conclusion — *"provision an eleventh phantom registry repository for a module
that publishes no image."* [`services/common-lib/pom.xml` line 63]. A failure
that does not fail the apply is the worst kind, so the asymmetry is documented
in three places on purpose.

---

## 2. Build, test and gate commands

### 2.1 The two commands that are this module's acceptance criteria

```bash
# WHAT: build the whole reactor and run its gates and tests, in dependency order.
# WHY : this is the canonical command [services/pom.xml line 18]. It builds
#       common-lib FIRST, because eight modules depend on it and Maven derives
#       reactor order from the dependency graph. It needs no prior `install`,
#       which is exactly why it is the canonical one rather than a per-module
#       build.
mvn -f services/pom.xml clean verify
```

```bash
# WHAT: fire this module's documentation gate on its own, compiling nothing.
# WHY : the gate is bound to `validate`, the first phase of every lifecycle, so
#       it runs without a compile step and cannot be reached around. Use this as
#       the fast feedback loop while authoring Javadoc.
mvn -f services/common-lib/pom.xml validate
```

### 2.2 Narrower loops

```bash
# WHAT: compile this module and run its unit tests only.
# WHY : `test` stops before packaging, so it is the shortest loop that still
#       executes the codec, money, security, web and time suites.
mvn -f services/common-lib/pom.xml clean test

# WHAT: build this module inside the reactor, together with what it depends on.
# WHY : `-pl` selects one project and `-am` ("also make") adds the projects that
#       selection depends on, so the aggregator is built alongside it and the
#       parent is read from the working tree. Prefer this over a standalone
#       module build: the standalone form resolves this module's artifacts from
#       the local repository and therefore needs a prior
#       `mvn -f services/common-lib/pom.xml install` — and it needs BOTH
#       artifacts, the jar of main classes and the test artifact carrying the
#       shared architecture rules [services/pom.xml lines 23 to 32].
mvn -f services/pom.xml -pl common-lib -am clean verify

# WHAT: run the shared layering rules alone.
# WHY : `-Dtest=` selects by simple class name, which is the same name the
#       `architecture-rules` Surefire execution selects by. Running it alone is
#       the quickest way to confirm an architectural boundary before pushing.
mvn -f services/common-lib/pom.xml test -Dtest=LayeringRulesTest
```

Until `LayeringRulesTest` is authored (§7), that last command fails with
`No tests matching pattern "LayeringRulesTest" were executed!` — Surefire treats
an explicit selector that matches nothing as an error. That is the correct
outcome and a useful signal: it proves the class is genuinely absent rather than
silently skipped, and it is the same guarantee that makes the pinned filename in
§7 load-bearing.

### 2.3 Where reports land — do not relocate them

Surefire writes to `services/common-lib/target/surefire-reports/` and Failsafe
to `services/common-lib/target/failsafe-reports/`. Both are the plugin
defaults, left unset on purpose, because the services pipeline collects from
`services/*/target/surefire-reports/` and `services/*/target/failsafe-reports/`
[`services/pom.xml` lines 1027 to 1028 and 1073 to 1074]. A suite whose reports
land outside those directories passes locally and reports nothing in CI, which
is a silent loss of signal rather than a visible failure.

The suffix decides the phase, so it is part of the contract too: `*Test` classes
run under **Surefire** in the `test` phase; `*IT` classes run under **Failsafe**
in `integration-test` and are asserted in `verify`. A test named for the wrong
suffix runs in the wrong phase.

### 2.4 Never tamper with a gate

None of the following may appear in any command, POM, configuration or workflow
that touches this module:

- `-Dcheckstyle.skip`
- `<skip>true</skip>`
- `failOnViolation=false`
- `|| true`
- `continue-on-error: true`
- any other return-code-tolerance wrapper

The documentation gate is the machine-checkable half of this project's
Explainability rule (§9). A skipped gate does not report a passing build; it
reports nothing at all, while looking identical to a passing build.

### 2.5 ⚠ The graded return-code rubric belongs to the COBOL oracle — never to a Java gate

This is the single easiest mistake to make in this repository, because the two
worlds sit side by side and use the word "pass" differently.

The COBOL parity oracle under `tests/**` uses a **graded aggregate return-code
rubric** — 0, 2, 4, 8, 16 — and its runners aggregate the **worst (highest)**
code seen [`tests/README.md` §8, lines 412 to 423]. That rubric exists because
it mirrors the mainframe condition-code convention the migrated programs came
from, and it belongs **exclusively** to that suite.

Maven, Checkstyle, Surefire, Failsafe and JUnit are **binary**: zero or
non-zero. The reactor states this explicitly — *"Exit status of this build is
BINARY: it either passes or it fails. There is no tolerated warning level"*
[`services/pom.xml` lines 69 to 71]. Three consequences:

- **Never import the graded rubric into a Maven, Checkstyle or JUnit gate.** A
  Java gate has no warn tier. There is no "return 4 and continue" here, and
  building one would be indistinguishable from disabling the gate.
- The reference suite's documented **warn-level aggregate RC = 4 remains its
  green state.** It is caused solely by the pre-existing, out-of-scope
  `CBEXPORT` / `CBIMPORT` FD `RECORD KEY` defect, which no compiler flag can fix
  and which the REFERENCE-only policy forbids editing
  [`tests/README.md` §1.1]. **It must not be misread as a regression introduced
  by this migration.**
- New Java tests are **strictly additive**. `tests/**` and `scripts/**` are
  never modified and never re-pinned — zero version drift, which is what allows
  that suite to keep serving as the functional-parity oracle.

---

## 3. What lives here

### 3.1 Module layout

Nine packages, nine `package-info.java` files under `src/main/java` (one per
package — see §8, they are mandatory), and one more per test package.

```text
src/main/java/com/carddemo/common/
  package-info.java
  CardDemoCommonAutoConfiguration.java
  money/          package-info.java · Money.java · MoneyModule.java
  codec/          package-info.java · CopybookLayout.java · FixedWidthCodec.java
                  ZonedDecimalCodec.java · PackedDecimalCodec.java · CsvAuthCodec.java
  error/          package-info.java · ApiError.java · GlobalExceptionHandler.java
                  AbendDetail.java
  web/            package-info.java · CorrelationIdFilter.java · PageResponse.java
                  CursorToken.java
  security/       package-info.java · JwtRoleConverter.java · CardNumberMasker.java
                  CognitoAccessTokenValidator.java · OpaqueIdentifier.java
  observability/  package-info.java · MetricsConfig.java · LogSafeText.java
  time/           package-info.java · TimestampFormatter.java
  validation/     package-info.java · DateEditValidator.java · FieldValidationFlag.java

src/main/resources/
  carddemo-common-defaults.yml
  META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
  META-INF/services/tools.jackson.databind.JacksonModule

src/test/java/com/carddemo/common/
  package-info.java · CardDemoCommonAutoConfigurationIT.java
  architecture/   package-info.java   ← LayeringRulesTest.java is pinned here, §7
  money/          package-info.java · MoneyTest.java · MoneyModuleTest.java
  codec/          package-info.java · FixedWidthCodecTest.java
                  ZonedDecimalCodecTest.java · PackedDecimalCodecTest.java
                  CsvAuthCodecTest.java
  error/          package-info.java · GlobalExceptionHandlerTest.java
                  GlobalExceptionHandlerPathMaskingTest.java
  web/            package-info.java · CorrelationIdFilterTest.java · CursorTokenTest.java
  security/       package-info.java · JwtRoleConverterTest.java
                  CognitoAccessTokenValidatorTest.java · CardNumberMaskerTest.java
  observability/  package-info.java · LogSafeTextTest.java
  time/           package-info.java · TimestampFormatterTest.java
```

Two facts about that tree are worth stating rather than leaving to be inferred.
The module publishes **two** artifacts — its jar of main classes and a
**test artifact** carrying the `architecture` package alone, which is how the
eight services receive the one shared layering rule class. And the `validation`
package currently has no matching test package; a class author adding tests
there creates the package and its `package-info.java` together, because §8
requires both.

> **Assumptions:** the tree above is the tree on disk, enumerated from it, not a
> target copied from a plan. `LayeringRulesTest` is named in §7 because its
> filename is already a build contract, and it is marked as pinned rather than
> listed as present because it has not been authored — the `architecture` package
> exists and holds only its `package-info.java`.
> **Trade-offs:** describing the tree as it is means this table needs an edit
> whenever a class lands, which a copied target list would not. That cost is
> accepted because the alternative fails in the worse direction: a README that
> lists classes which do not exist sends a reader hunting for them, and one that
> omits classes which do exist invites a duplicate. `mvn -f services/pom.xml test`
> is the authority that cannot go stale; this table is a map, and §2 is how you
> check the territory.

### 3.2 Responsibility and source authority, one line each

Every class here encodes a contract taken from the baseline. The authority column
is where that contract is written down, and it is the citation a class author
repeats in the type's own Javadoc.

| Class | Responsibility | Source authority |
|---|---|---|
| `Money` | `BigDecimal` scale-2 arithmetic with `RoundingMode.HALF_UP`; exposes the multiply-then-divide interest helper | `app/cpy/CVACT01Y.cpy` line 7; `app/cbl/CBACT04C.cbl` lines 464 to 465 |
| `MoneyModule` | Jackson module serialising money as a JSON **string** | §5.1 |
| `CopybookLayout` | The layout descriptor — offset, length and type per field; one descriptor, many readers | `app/cpy/**` record copybooks |
| `FixedWidthCodec` | Record ⇄ field-map by offset and length | all base record copybooks |
| `ZonedDecimalCodec` | Sign-overpunch decode and encode, with an explicit sign convention | `app/cpy/CVACT01Y.cpy` line 7; `tests/README.md` lines 273 to 274 |
| `PackedDecimalCodec` | `COMP-3` decode and encode | `app/cpy/CVEXPORT.cpy`; the two authorization segment copybooks |
| `CsvAuthCodec` | The 18-field authorization request and 6-field reply CSV | `CCPAURQY.cpy` lines 19 to 36; `CCPAURLY.cpy` lines 19 to 24 |
| `ApiError` | The problem shape, carrying a per-field error array | `app/cpy/CSMSG01Y.cpy`; migration rules T7 and T8 |
| `GlobalExceptionHandler` | The `@RestControllerAdvice` mapping exceptions to that shape | §6.3 |
| `AbendDetail` | The structured `ABEND-DATA` equivalent, four components | `app/cpy/CSMSG02Y.cpy` lines 21 to 29 |
| `CorrelationIdFilter` | Correlation id in, MDC and response header out — the message-queue correlation-id analogue for HTTP | `COPAUA0C.cbl` message-descriptor handling |
| `PageResponse<T>` | The keyset envelope: `items`, `firstKey`, `lastKey`, `hasNext` | `app/cbl/COCRDLIC.cbl` lines 229 to 248 |
| `CursorToken` | Opaque encoding of a composite browse key, so a cursor is not a client-forgeable field | `app/cbl/COCRDLIC.cbl` lines 230 to 235 |
| `JwtRoleConverter` | `cognito:groups` to Spring Security authorities | `app/cpy/COCOM01Y.cpy` lines 26 to 28; `app/cpy/CSUSR01Y.cpy` line 22 |
| `CognitoAccessTokenValidator` | Validates the access token's issuer and audience claims before its groups are trusted | §6.4 |
| `CardNumberMasker` | Masks a primary account number to its last four digits | `app/cpy/CVACT02Y.cpy` |
| `OpaqueIdentifier` | Keyed, non-reversible surrogate for an identifier that must not travel in the clear | §4 |
| `MetricsConfig` | Micrometer common tags `service`, `environment`, `version` | no baseline analogue; additive |
| `LogSafeText` | Strips control characters and truncates before a value reaches a log line | no baseline analogue; additive |
| `TimestampFormatter` | The exact 26-character `YYYY-MM-DD HH:MM:SS.mmmmmm` form | `app/cpy/CVTRA05Y.cpy` line 17; `app/cbl/CBTRN02C.cbl` line 159 |
| `DateEditValidator` | The date-edit rules, including leap-year and range handling | `app/cbl/CSUTLDTC.cbl`; `app/cpy/CSUTLDPY.cpy`; `app/cpy/CSUTLDWY.cpy` |
| `FieldValidationFlag` | The `FLG-*-NOT-OK` / `FLG-*-BLANK` equivalent, including the `'*'` blank marker | `app/cpy/CSSETATY.cpy` lines 17 to 27 |
| `CardDemoCommonAutoConfiguration` | Registers the shared filter, advice and Jackson module in a host application, so no service wires them by hand | no baseline analogue; additive |
| `LayeringRulesTest` | The ArchUnit layering rules, inherited by all eight services | §7 |

---

## 4. What must NOT be added here

This section is as load-bearing as §3. A shared kernel decays by accretion, and
every entry below is a boundary that has already been reasoned about.

**Layers that belong to the services, not here**

- **No `service/`, `api/`, `repository/`, `domain/` or `dto/` package.** Those
  are the eight service modules' own layers. A business rule transcribed from a
  COBOL paragraph belongs to the service that owns that paragraph's data.
- **No `config/` package.** Metric configuration lives under `observability/`,
  next to the concern it configures, rather than in a bucket named after the
  fact that it is configuration.
- Note the one thing this module does have no `domain` package for a second
  reason: the layering rule in §7 constrains web and cloud-SDK types *inside*
  `..domain..` packages, and this module has no `domain` package at all, so
  holding those types here crosses no boundary.

**Deployable-shaped artifacts** — see the table in §1.3 for each one's reason

- **No `Dockerfile`, no `application.yml`, no `db/migration/`, no `openapi/`,
  no `.gitignore`, no `<Svc>Application.java`, no actuator health endpoint.**

**Dependencies that are deliberately absent**

- **No Lombok.** *"Generated accessors cannot carry the Javadoc the
  explainability rule requires."* Java 21 `record` types plus explicit
  constructors give the same brevity with documentable members — and §8's
  `allowMissingParamTags="false"` means a record must document every component
  anyway, which a generated accessor cannot do.
- **No MapStruct.** Its most recent published release is a beta, and
  copybook-to-DTO mapping is not mechanical: it drops `FILLER`, masks the primary
  account number to its last four digits, suppresses the card verification value
  entirely, encrypts the national and government-issued identifiers, and renames
  three misspelled baseline fields (§6.8). Every one of those needs an inline
  justification at the mapping site, which a generated mapper has nowhere to put.
- **No resilience library and no circuit breaker.** Spring Framework 7, which
  arrives inside the Boot 4.1.0 parent, relocated retry into the framework core.
  Two API facts are easy to get wrong and must be commented at any use site: the
  annotation attribute is **`maxRetries`** — total attempts are one *more* than
  that value — and the enabling annotation is **`@EnableResilientMethods`**, not
  the older `@EnableRetry`, so a service that copies a Boot 3 example will not
  compile. A breaker is omitted on purpose: the only synchronous hops run inside
  the private network behind an internal load balancer with explicit connect and
  read timeouts, so a breaker would add a failure mode of its own — an open
  circuit rejecting calls a healthy dependency could have served — without
  removing one.
- **No Redis, ElastiCache, Kafka or Kinesis, and no read replicas.** The
  baseline has no cache tier and the messaging requirement is request/reply.
- **No AWS SDK, no JPA, no Flyway and no JDBC driver in this module.** A codec
  that knows about a datastore is no longer a codec.

**Enforcement that must have exactly one owner**

- **No Checkstyle `ImportControl` configuration anywhere.** `ImportControl` is
  deliberately excluded from `config/checkstyle/checkstyle.xml` so that layering
  has exactly **one** owner: `LayeringRulesTest` (§7). Two enforcement points for
  one invariant drift apart, and the drift is silent — each one keeps passing
  while they disagree about what the rule is.

**Shapes that would quietly change behaviour**

- **No offset-pagination helper of any kind**, not even as a convenience
  alongside the keyset envelope. §6.1 explains what offset paging breaks; a
  helper that exists will be used.
- **No hard-coded secret, credential, endpoint or hostname.** No service
  hard-codes an endpoint; runtime endpoints and identifiers arrive from
  parameter storage at startup. Keyed material for `CursorToken` and
  `OpaqueIdentifier` is supplied by configuration and never defaulted to a
  literal in source.

---

## 5. The three highest-risk contracts

Fixed-point and character-set fidelity are *"the highest-risk area in the
migration, because an error here is silent — it produces plausible numbers that
are wrong."* All three mitigations live in this module, which is why this section
exists at all: a bug in any of them does not throw, does not fail a health check
and does not look wrong in a log. It just posts the wrong amount.

### 5.1 Money is exact fixed point end to end

One invariant, at every hop:

| Hop | Representation |
|---|---|
| SQL | `NUMERIC(p,2)` |
| Java | `BigDecimal`, scale 2, `RoundingMode.HALF_UP` |
| Python (the ETL) | `Decimal` |
| JSON on the wire | **string** |

`float`, `double` and JSON numbers are **forbidden** in the money path, and the
prohibition is **architecture-tested** by `LayeringRulesTest` (§7) rather than
left to review. `Money` and `MoneyModule` are the **single** enforcement point:
no service re-implements rounding, and no DTO carries a bare `BigDecimal` that
has escaped `Money`'s scale check.

**Why a JSON string rather than a JSON number.** A JSON number is parsed into an
IEEE-754 double by most clients, which destroys exactness *"at the boundary the
user actually sees."* Serialising as a string removes the client's opportunity to
make that mistake; there is no representation in which a string of digits becomes
approximate.

**The baseline agrees, which makes this preservation rather than departure.**
`CCPAURQY.cpy` line 27 and `CCPAURLY.cpy` line 24 both declare money as
`PIC +9(10).99` — an **edited numeric** picture, fourteen characters of signed
decimal **text**, sign and decimal point included as literal characters. The
baseline already transports money as text on its message wire. A JSON string
therefore *preserves* the original contract; a JSON number would be the novel
choice.

### 5.2 Two numeric regimes, two codecs — never one

The two codecs are not duplication. They decode two physically different
encodings that a `PIC` clause alone does not distinguish.

**Regime one — zoned decimal with sign overpunch (display).** Every money field
in the ten base master records is zoned decimal, **not** packed: for example
`ACCT-CURR-BAL PIC S9(10)V99` [`app/cpy/CVACT01Y.cpy` line 7]. This is the
regime `ZonedDecimalCodec` exists for.

**Regime two — packed decimal (`COMP-3`).** It appears only in
`app/cpy/CVEXPORT.cpy` and, heavily, in the two authorization segment copybooks,
where it reaches persisted target data. This is `PackedDecimalCodec`'s regime.

**`ZonedDecimalCodec` must carry an explicit sign-overpunch mode.** The
justification is a sentence from the reference suite, reproduced verbatim, and it
must also be cited in the codec's own Javadoc:

> *"`-fsign=EBCDIC` is REQUIRED — the default `-fsign=ASCII` misreads the
> zoned-decimal sign overpunch and silently corrupts negative balances."*
> — [`tests/README.md` lines 273 to 274]

**"EBCDIC" here names a *sign convention*, not a character encoding.** That
confusion is worth heading off explicitly, because the word appears in a compiler
flag next to a character-set name and the two are unrelated. The overpunch table
is:

| Sign | Overpunch characters | Meaning |
|---|---|---|
| positive | `{ A B C D E F G H I` | `+0 +1 … +9` |
| negative | `} J K L M N O P Q R` | `-0 -1 … -9` |
| unsigned | plain digits, no overpunch | `PIC 9(n)` carries no sign at all |

So the codec must treat **signed and unsigned display fields distinctly**. An
unsigned `PIC 9(n)` field has a plain digit in its last position, and reading it
through the signed path either invents a sign or rejects a valid record.

**The decisive structural reason both codecs are needed.** In
`app/cpy/CVEXPORT.cpy` the **same** `PIC S9(10)V99` clause appears five times
across **three different `USAGE` clauses**, and therefore at three different
physical widths:

| Declaration | Lines | Physical width |
|---|---|---|
| `PIC S9(10)V99 COMP-3` | 50, 52 | 7 bytes, packed |
| `PIC S9(10)V99` (display) | 51, 56 | 12 bytes, zoned |
| `PIC S9(10)V99 COMP` | 57 | 8 bytes, binary |

**The `PIC` clause alone never determines physical width — the `USAGE` clause
does.** A layout that records only the picture will mis-align every field after
the first money field in that record.

### 5.3 Multiply before divide

`Money` exposes the multiply-then-divide helpers — `monthlyInterest` and
`monthlyInterestTruncated` — so that no caller has to reconstruct the ordering.
The formula they implement is, verbatim from the baseline
[`app/cbl/CBACT04C.cbl` lines 464 to 465]:

```text
COMPUTE WS-MONTHLY-INT
 = ( TRAN-CAT-BAL * DIS-INT-RATE) / 1200
```

**The COBOL source itself parenthesises the multiplication.** The ordering is
explicit in the baseline; it is not inferred from a convention. Multiply at
**full precision first**, then divide with an explicit scale and
`RoundingMode.HALF_UP`. *"Dividing first and multiplying second yields different
cents on many inputs"* — re-ordering is forbidden.

Two worked cases make the failure concrete rather than theoretical, both
computed on a balance of `1000.00`:

| Annual rate | Multiply first (correct) | Divide the rate first (wrong) |
|---|---|---|
| `15.00` | **`12.50`** | `10.00` |
| `2.50` | **`2.08`** | `0.00` |

The second row is the one to remember. Rounding `2.50 / 1200` to scale 2 gives
`0.00`, and every subsequent multiplication is then a multiplication by zero — the
interest silently disappears for every account at a low rate, with no error
anywhere.

**The accumulator subtlety.** The baseline performs
`ADD WS-MONTHLY-INT TO WS-TOTAL-INT` **per transaction**
[`app/cbl/CBACT04C.cbl` line 467], so the total is the sum of **rounded** values,
not a rounded sum. Accumulating at full precision and rounding once at the end
produces a different total, and it is the wrong one for parity purposes. Round
each term, then add.

---

## 6. Other contracts owned here

### 6.1 `PageResponse<T>` — keyset, never offset

The baseline browse state **is already a keyset cursor**, which makes this a
one-to-one mapping rather than an approximation.
[`app/cbl/COCRDLIC.cbl` lines 229 to 248] persists five things in the
communication area between screen turns: a **last-key pair**, a **first-key
pair**, a screen number, a last-page-displayed flag and a next-page-exists
indicator. `PageResponse<T>` is a record carrying exactly `items`, `firstKey`,
`lastKey` and `hasNext`.

Two details a reader will otherwise miss:

- **The keys are composite pairs, not scalars** — card number `PIC X(16)` plus
  account id `PIC 9(11)` [lines 230 to 235]. A cursor type that assumes a single
  scalar key cannot express this, which is why `CursorToken` encodes the pair.
- **The page size is 7 rows.** The comment at line 250 reads
  `File Data Array         28 CHARS X 7 ROWS = 196`. A forward query therefore
  fetches size **plus one** to determine `hasNext` — which is exactly how the
  baseline sets its own next-page indicator, by discovering one more record than
  fits.

**Offset pagination is REJECTED.** Under concurrent inserts it skips and repeats
rows, changing observable behaviour that browse-by-key does not. A forward page
is "keyed strictly greater than `lastKey`, ordered ascending, limit size + 1"; a
backward page is "keyed strictly less than `firstKey`, ordered descending" —
which is precisely what read-previous did.

### 6.2 `TimestampFormatter` — 26 characters, and a mask that must never be transliterated

The width is the contract: `TRAN-PROC-TS PIC X(26)`
[`app/cpy/CVTRA05Y.cpy` line 17], `01 DB2-FORMAT-TS PIC X(26)`
[`app/cbl/CBTRN02C.cbl` line 159], and the field-by-field redefine beneath it
sums `4+1+2+1+2+1+2+1+2+1+2+1+2+4` to 26, proving the width a second time by
arithmetic. The emitted form is `YYYY-MM-DD HH:MM:SS.mmmmmm`.

**⚠ The naming trap.** `app/cbl/CBTRN02C.cbl` line 149 carries a commentary mask
describing database-native punctuation:

```text
* T I M E S T A M P   D B 2  X(26)     EEEE-MM-DD-UU.MM.SS.HH0000
```

That is a **COBOL commentary mask** and it must **never** be transliterated into
a Java `DateTimeFormatter` pattern. Two of its letters mean something else
entirely in Java: `EEEE` is the day-of-week name, so the rendered value would
open with a weekday where the year belongs, and `UU` is not a pattern letter at
all, so pattern construction raises `IllegalArgumentException` while the class is
still initialising and every caller fails before reaching any timestamp.
**Implement the emitted form, not the mask.**

The punctuation divergence is deliberate and documented rather than accidental,
and it is defensible for three reasons: the target column type renders natively
in the space-and-colon form, so carrying the database punctuation would need a
translation at every persistence boundary instead of none; the baseline is not
self-consistent about this very field, since the posting path passes a
space-and-colon value straight through [`app/cbl/CBTRN02C.cbl` line 436] while
the interest path stamps the hyphen-and-dot form [`app/cbl/CBACT04C.cbl` lines
496 to 498]; and the only production consumer reads the first ten bytes and
nothing more — the sort symbol `TRAN-PROC-DT,305,10,CH`
[`app/jcl/TRANREPT.jcl` line 42] — which are byte-identical under either
punctuation.

**Fractional resolution.** The final four fractional digits are structurally
always `0000`, because the source of precision is `COB-MIL PIC X(02)` —
hundredths — and the paragraph then moves the literal `'0000'` into the four
remaining positions [`app/cbl/CBTRN02C.cbl` line 701]. Every fixture and golden
in the reference suite carries `.000000`. What the contract carries is the
26-character width, not six digits of real precision.

### 6.3 The error model — three message widths, two sentinels, four abend components

Migration rule **T7**: the `FLG-*-NOT-OK` / `FLG-*-BLANK` condition pattern
becomes a **structured per-field error array** in the response body, rendered by
the client as a field-level error rather than a screen-level one. Migration rule
**T8**: message text is carried **verbatim, character-for-character**, keyed by
its originating copybook.

**⚠ Message widths are THREE regimes, not one: 50 / 72 / 75.** Conflating them,
or truncating everything to a single width, silently reformats user-visible text:

| Width | Where | Contents |
|---|---|---|
| `X(50)` | `app/cpy/CSMSG01Y.cpy` lines 18 to 21 | exactly **two** messages — the thank-you and the invalid-key text |
| `X(50)` and `X(72)` | `app/cpy/CSMSG02Y.cpy` lines 21 to 29 | the abend reason and the abend message |
| `X(75)` | `app/cpy/CVCRD01Y.cpy` lines 28 to 30 | the message-line contract — `CCARD-ERROR-MSG` and `CCARD-RETURN-MSG` |

**Two sentinels, and they are not interchangeable.** `LOW-VALUES`
[`app/cpy/CVCRD01Y.cpy` line 30] maps to null / absent; `SPACES` maps to a blank
string. A codec that trims both to the empty string destroys the distinction
between *explicitly blank* and *never set* — and that distinction is what the
`88`-level condition names in the baseline are testing.

**`AbendDetail` has exactly four components**
[`app/cpy/CSMSG02Y.cpy` lines 21 to 29]:

| Component | Picture |
|---|---|
| `ABEND-CODE` | `X(4)` |
| `ABEND-CULPRIT` | `X(8)` |
| `ABEND-REASON` | `X(50)` |
| `ABEND-MSG` | `X(72)` |

⚠ **A correction worth recording explicitly:** an earlier draft of the folder
specification cited lines 45 to 53 for these fields. `app/cpy/CSMSG02Y.cpy` is
only **35 lines long**, so those lines cannot exist. **Lines 21 to 29 are
correct**, and they were re-verified against the file.

**`GlobalExceptionHandler` status mapping.** An optimistic-lock failure maps to
**HTTP 409 Conflict**, carrying the same data-changed semantic the baseline's
before-image comparison produced; a violation of the reference data's
`ON DELETE RESTRICT` constraint also maps to **HTTP 409**. Neither ever surfaces
as a raw database error, because a database error message is both unhelpful to
the caller and a disclosure risk.

### 6.4 `JwtRoleConverter` — a signed claim replacing an echoed field

The mapping, confirmed in two source files —
[`app/cpy/COCOM01Y.cpy` lines 26 to 28] and [`app/cpy/CSUSR01Y.cpy` line 22]:

| Baseline `SEC-USR-TYPE` / `CDEMO-USER-TYPE` | Target group |
|---|---|
| `'A'` | `carddemo-admin` |
| `'U'` | `carddemo-user` |

This is an improvement rather than a port, and the reason is worth quoting
because it is the whole justification for not simply carrying the field across:
*"in the baseline the COMMAREA is storage the client echoes back, so a client
could in principle assert its own user type. In the target the client cannot
assert anything; the group claim is signed."*

`SEC-USR-PWD PIC X(08)` [`app/cpy/CSUSR01Y.cpy` line 21] is **deliberately not
carried forward at all** — not hashed, not migrated, not stored. Identity moves
to the managed user pool and the user table keeps only a subject reference. This
is the one place where parity is explicitly declined, and it is declined on
purpose.

### 6.5 `FieldValidationFlag` — three states, not two

The enum carries **`VALID`, `NOT_OK` and `BLANK`**. `BLANK` is a **subset** of
error with an *additional* treatment: the literal `'*'` marker
[`app/cpy/CSSETATY.cpy` lines 23 to 25]. Modelling it as a boolean loses the
marker; modelling it as a sibling of `NOT_OK` loses the fact that a blank field
is also in error.

**The coupling that is severed.** In the baseline the highlight is gated on the
pseudo-conversational re-entry flag — line 20 of `app/cpy/CSSETATY.cpy` reads
`AND CDEMO-PGM-REENTER`. `CDEMO-PGM-CONTEXT` **disappears entirely** in the
target, because a stateless handler returning a field-error array has no
first-entry-versus-re-entry distinction to make. Error presentation is therefore
driven purely by the response body.

⚠ **`app/cpy/CSSETATY.cpy` is a PROCEDURE DIVISION macro fragment**, not a data
structure. It is `COPY ... REPLACING` material with `(TESTVAR1)`, `(SCRNVAR2)`
and `(MAPNAME3)` substitution placeholders. A layout parser must not treat it as
a record; it has no `01` level and no fields.

### 6.6 `CopybookLayout` and `FixedWidthCodec` — one descriptor, many readers

This is the Factory pattern doing real work: record-layout knowledge is declared
once per record and imported by every reader, so a layout cannot drift between
two consumers. Per migration rule **T1**, `FILLER` is dropped and **the drop is
recorded per record** — a dropped field that is not recorded is indistinguishable
from a field that was missed.

The eleven canonical dataset layouts and their verified record lengths:

| Dataset | Copybook | Record length |
|---|---|---|
| `USRSEC` | `CSUSR01Y` | **80** |
| `ACCTDATA` | `CVACT01Y` | **300** |
| `CARDDATA` | `CVACT02Y` | **150** |
| `CUSTDATA` | `CVCUS01Y` | **500** |
| `CARDXREF` | `CVACT03Y` | **50** |
| `DALYTRAN` | `CVTRA06Y` | **350** |
| `TRANSACT` | `CVTRA05Y` | **350** |
| `DISCGRP` | `CVTRA02Y` | **50** |
| `TRANCATG` | `CVTRA04Y` | **60** |
| `TRANTYPE` | `CVTRA03Y` | **60** |
| `TCATBALF` | `CVTRA01Y` | **50** |

Two notes on reading those lengths from the copybooks. The banner style is
**not** uniform — `CVACT01Y` writes `RECLN 300` with no equals sign, `CVTRA01Y`
writes `RECLN = 50` with one, and `CVEXPORT` uses prose,
`Total Record Length: 500 bytes`. And `CSUSR01Y` has **no banner at all**: its 80
bytes are derived by summing the declared field widths
(`8 + 20 + 20 + 8 + 1 + 23`), which is the only available authority for that
record.

Offsets are worth cross-checking the same way, because two independent sources
agree and that agreement removes doubt from every offset-dependent decision.
Summing `CVTRA05Y` puts the card number at offset 262 and the processing
timestamp at 304 (zero-based); the report job's sort control independently
declares `TRAN-CARD-NUM,263,16,ZD` and `TRAN-PROC-DT,305,10,CH` in one-based
positions [`app/jcl/TRANREPT.jcl` lines 41 to 42]. The two agree exactly.

### 6.7 Copybook-parsing hazards

These are the traps that silently mis-align a record. They are collected here so
that every codec author has them in one place rather than rediscovering them one
production defect at a time. Each one was confirmed against the file named.

- **`REDEFINES` aliases storage and must not advance the offset.** A naive
  width-summing parser over `app/cpy/CVCRD01Y.cpy` double-counts exactly **36
  spurious bytes** — the three redefines at lines 36, 39 and 42 re-describe
  `X(11)`, `X(16)` and `X(09)`, which sum to 36.
- **`OCCURS` appears at both group and elementary level.** In
  `app/cpy/CVEXPORT.cpy` it is a group-level clause (line 29, `OCCURS 3 TIMES`
  over an `X(50)`; line 34, `OCCURS 2 TIMES` over an `X(15)`) — ignoring both
  loses `100 + 15 = ` **115 bytes**. In `CIPAUSMY.cpy` line 22 it is elementary
  and sits **on the same line as the `PIC`**
  (`PIC X(02) OCCURS 5 TIMES`) — ignoring it mis-aligns by **8 bytes**.
- **Level numbers are neither contiguous nor monotonic.** `app/cbl/COCRDLIC.cbl`
  runs `01 → 10 → 15` and then back to `05` at line 252, and
  `app/cbl/CBTRN02C.cbl` uses level `06`. Use a level **stack**, never a counter
  and never an assumption that levels step by five.
- **`FILLER` is sometimes named.** `app/cpy/CSUSR01Y.cpy` line 23 declares
  `SEC-USR-FILLER`, so matching the literal token `FILLER` misses it. Match on
  role, and record the drop either way.
- **Field names repeat across copybooks.** `TRAN-CAT-KEY`, `TRAN-TYPE-CD` and
  `TRAN-CAT-CD` all appear in both `CVTRA01Y` and `CVTRA04Y`, so a layout
  registry keyed on bare field names collides. **Scope descriptors per
  copybook.**
- **Level-number indentation varies**, sometimes within a single file — compare
  lines 34 and 36 of `app/cpy/CVCRD01Y.cpy`. Tokenise on whitespace runs, never
  on fixed columns.
- **Legacy sequence numbers occupy columns 1 to 6** and the identification area
  occupies **columns 73 to 80** in some copybooks; both must be ignored, and the
  sequence numbers must never be used for ordering. `app/cpy/CSMSG02Y.cpy` and
  `app/cpy/CVCRD01Y.cpy` both carry them.
- **A `*` in column 7 comments the line out.** Several declarations in
  `app/cpy/CVCRD01Y.cpy` are dead this way — lines 20, 22, 25, 26, 27, 31, 32
  and 33. A parser that ignores column 7 will read fields that do not exist.
- **`PIC` and `VALUE` clauses may sit on separate lines.** A declaration
  terminates at the **period**, not at the newline —
  `app/cpy/CSMSG02Y.cpy` lines 22 to 23 are one declaration, and
  `app/cpy/CVCRD01Y.cpy` lines 34 to 35 are another.
- **`88`-level `VALUE` literals are sometimes quoted and sometimes bare.**
  `app/cpy/COCOM01Y.cpy` shows both in one file: quoted `'A'` and `'U'` at lines
  27 to 28, bare numeric `0` and `1` at lines 30 to 31. `LOW-VALUES` is a third
  form again.
- **Four of the authorization payload copybooks begin at level `05` with no
  `01`** — `CCPAURQY.cpy` and `CCPAURLY.cpy` among them. The layout API must
  therefore accept an **externally-supplied root name**; it cannot require a
  root level in the file.
- **Banner position is not a reliable data-start heuristic.** Find the first
  `01` level, or use the supplied root.

### 6.8 The three deliberate misspelling corrections

The baseline misspells three field names. The target corrects them, and each
correction carries a `Refactoring Rationale:` comment at the mapping site so the
lineage is never ambiguous:

| Baseline name | Target name |
|---|---|
| `ACCT-EXPIRAION-DATE` [`app/cpy/CVACT01Y.cpy` line 11] | `expiration_date` |
| `CARD-EXPIRAION-DATE` | `expiration_date` |
| `PA-RQ-MERCHANT-CATAGORY-CODE` [`CCPAURQY.cpy` line 28] | `merchant_category_code` |

A rename is exactly the kind of change that looks like a typo to the next reader,
so the comment is not optional — it is what distinguishes a deliberate correction
from an accidental divergence.

---

## 7. `LayeringRulesTest` — the rule that cannot rot

Ports and adapters are enforced here by ArchUnit rather than by convention, and
the reason is the whole point of the design: *"The rule is a test, so it cannot
rot."* A documented boundary decays quietly; a failing build does not.

This **single** test file is inherited by all eight services — it is published in
this module's **test artifact** and scanned into every module, so the rules are
evaluated against each module's own compiled classes. It must assert at minimum:

1. **`..domain..` may not import cloud-SDK types or web types** — no AWS SDK,
   no `org.springframework.web..`, no `jakarta.servlet..`. A domain object that
   knows how it is transported is no longer a domain object.
2. **No service may import another service's `..domain..` package.** The nine
   package roots are `com.carddemo.common`, `.auth`, `.account`, `.card`,
   `.transaction`, `.reference`, `.batch`, `.authorization` and `.reporting`.
   This is the rule that keeps one database table under one owner.
3. **Money is never `double` or `float`** — no field, parameter or return type in
   the money path may use them. This is the architecture-tested half of §5.1.

**The location is pinned character-for-character**, because the Surefire
`architecture-rules` execution selects it by filename
(`**/LayeringRulesTest.java`, [`services/pom.xml` line 1047]):

```text
services/common-lib/src/test/java/com/carddemo/common/architecture/LayeringRulesTest.java
    package  com.carddemo.common.architecture
    class    LayeringRulesTest
```

**Do not relocate, rename or split it.** The `architecture` test package already
exists and reserves that location with its `package-info.java`. Because the
include pattern matches by name, a rule class under any other name is not
selected by that execution — it would simply never run, and the build would stay
green while asserting nothing.

**The dependency** is `com.tngtech.archunit:archunit-junit5` at **1.4.2**,
test-scoped, with the **version managed by `services/pom.xml`**
[lines 649 to 651] and never re-pinned in this module. Re-pinning a version here
would silently diverge this module from the other eight, which is precisely the
failure the single copybook include path exists to prevent on the COBOL side.

**Prove the gate.** After authoring the rules, introduce a deliberate violation —
a `double` in a money signature, or a cross-service domain import — confirm that
`LayeringRulesTest` **fails**, then remove the violation. *A gate that cannot
fail is not a gate*, and an ArchUnit rule whose package selector matches nothing
passes vacuously.

---

## 8. The documentation gate this module must satisfy

### 8.1 The binding

`services/pom.xml` binds `maven-checkstyle-plugin` (Checkstyle **13.8.0**) to the
Maven **`validate`** phase against `config/checkstyle/checkstyle.xml`, with
`failOnViolation=true` and `violationSeverity=warning`. It therefore runs on
**every local build**, not only in CI — `validate` is the first phase of every
lifecycle a developer can invoke, which is what makes the gate structurally
unskippable rather than merely customary. The suppression filter is loaded with
`optional=false`, so a missing suppressions file is an error rather than a
silently empty filter.

### 8.2 What it demands, concretely

- **`package-info.java` is REQUIRED in every package** (`JavadocPackage` plus
  `MissingJavadocPackage`). For `src/main/java` that is **nine** files: the root
  plus `money`, `codec`, `error`, `web`, `security`, `observability`, `time` and
  `validation`.
- **`JavadocType`** runs with `allowMissingParamTags="false"`, so a **record
  needs an `@param` tag for every component**. This is the record and DTO guard,
  and it is the concrete reason Lombok is absent (§4).
- **`JavadocMethod`** runs with
  `accessModifiers="public, protected, package, private"`,
  `allowMissingParamTags="false"`, `allowMissingReturnTag="false"` and
  **`validateThrows="true"`**. Private methods are in scope; nothing is exempt by
  visibility.
- **`MissingJavadocType`** runs at `scope="private"` with tokens
  `INTERFACE_DEF, CLASS_DEF, ENUM_DEF, ANNOTATION_DEF, RECORD_DEF`, and
  **`MissingJavadocMethod`** at `scope="private"` with
  `allowMissingPropertyJavadoc="false"`.
- **Annotation-based escapes are deliberately not relaxed:**
  `MissingJavadocMethod` sets `allowedAnnotations=""` — an empty list — so **no
  class or method escapes the gate on the basis of an annotation**. An
  `@Override` does not excuse a missing docstring.
- Also active: `NonEmptyAtclauseDescription` (an `@param` with no description
  fails), `AtclauseOrder`, `CommentsIndentation`, and Checker-level
  `charset="UTF-8"` matched by `project.build.sourceEncoding` so the gate reads
  the same bytes the compiler does.
- **`SummaryJavadoc`** forbids a set of summary fragments that maps directly onto
  Rule 1's forbidden patterns: `TODO`, `FIXME`, `TBD`, the phrase *this is
  better*, the phrase *for performance*, and a summary that merely says the
  member is a getter or setter. Rule 1's ban on vague rationales is therefore
  partly **mechanical**, not only reviewed.

### 8.3 Test sources are in scope, and the suppressions charter is narrow

`includeTestSourceDirectory` resolves to **`true`**
[`services/pom.xml` line 888]. Two consequences follow, and neither is optional:

- **The test classes need full Javadoc too** — class-level and method-level —
  which is consistent with the house convention that *"Every new test, fixture
  builder, helper, mock, and runner routine"* carries a full docstring
  [`tests/README.md` lines 544 to 549].
- Because `JavadocPackage` is a **file-level** check, each **test** package also
  needs a `package-info.java`. Those files exist for every current test package;
  a new test package must be created with one.

To re-confirm that resolution rather than trusting this paragraph, read it out of
the effective POM:

```bash
# WHAT: print the fully-resolved POM for this module and read the gate's settings.
# WHY : the value is inherited from the aggregator, so it cannot be confirmed by
#       reading services/common-lib/pom.xml alone; the effective POM is the only
#       authority that cannot go stale.
mvn -f services/common-lib/pom.xml help:effective-pom | grep -A2 includeTestSourceDirectory
```

**The suppressions charter is six words: *generated-source and test-fixture
suppressions*.** `config/checkstyle/suppressions.xml` contains exactly two
`suppress` elements — one for `target/generated-sources/` and one for
`src/test/resources/fixtures/`. Nothing else is suppressed anywhere.

> **Suppressing anything under `services/common-lib/src/main/java/**` is
> ABSOLUTELY PROHIBITED.** A suppression is indistinguishable from compliance in
> a passing build, so a single entry there would quietly exempt the most widely
> consumed code in the repository from the one rule this project specified.

And to restate §2.4 because this is where the temptation actually arises:
`-Dcheckstyle.skip`, `<skip>true</skip>`, `failOnViolation=false`, `|| true` and
`continue-on-error: true` must never appear in any command, POM, configuration or
workflow touching this module.

---

## 9. Rule 1 (Explainability) — what the gate will demand of you

**Rule 1 is the single user-specified rule for this project, and it governs this
README too** — a README is a module entry point. It is also the artifact that
makes the rule uniformly checkable for everything under `src/`, which is why its
requirements are reproduced here in full rather than referenced: a class author
should never have to leave this file to know what is required.

### 9.1 The rule's requirements, reproduced

> **Docstrings.** *"Every new or modified function, class, and module entry point
> must include a docstring"*, specifying **Purpose** (*"What the function or class
> does"*), **Parameters** (*"Name, type, and description for each parameter"*),
> **Return values** (*"Type and description of what is returned"*), and
> **Exceptions or errors** (*"Any that may be raised (where applicable)"*).
> *"Follow the language's standard docstring format (… Javadoc for Java …)"*.
> *"Trivial accessors (getters/setters with no logic) may use a single-line
> docstring."*

> **Inline comments.** *"Place comments adjacent to the code they explain."*
> *"Comments must explain WHY a decision was made, not WHAT the code does (the
> code already shows that)."* Each non-obvious decision must document at least one
> of: **`Alternatives Considered:`** *"What other approaches were evaluated and why
> this one was chosen"* · **`Refactoring Rationale:`** *"When replacing existing
> code, what was wrong with the old approach"* · **`Assumptions:`** *"What external
> contracts, data formats, or behaviors this code depends on"* ·
> **`Trade-offs:`** *"What compromises were accepted (performance vs.
> readability, simplicity vs. flexibility, etc.)"*

> **Forbidden patterns.** *"Writing comments that restate what the code does
> (e.g., "// increment counter" next to counter++)"* · *"Adding docstrings that
> omit parameters, return values, or purpose"* · *"Leaving a non-obvious
> implementation choice undocumented when a reasonable alternative exists"* ·
> *"Using vague rationales ("this is better", "for performance") without specific
> justification"*

> **Validation Gate.** *"Every new or modified function must have a docstring with
> purpose, parameters, and return values. Every non-obvious implementation
> decision must have an inline comment explaining why that approach was chosen
> using at least one of the categories above. Code missing either fails
> review."*

**The gate is conjunctive.** "Missing either fails" means both halves are
independently required: a fully-Javadoc'd class with an undocumented non-obvious
choice fails, and a well-justified class missing its Javadoc fails. Neither half
compensates for the other.

**This module carries the densest documentation obligation in the repository**,
because every type here is consumed by eight modules. A vague rationale in a
service is a local cost; a vague rationale on a codec is inherited eight times.

### 9.2 The four labels, and the house comment idiom

Use the **canonical plural / hyphenated** forms, character-for-character:

```text
Alternatives Considered:
Refactoring Rationale:
Assumptions:
Trade-offs:
```

These four names are quoted from Rule 1 itself, and Rule 1's Validation Gate is
what gets audited, so the written form matters.
[`docs/CODE_DOCUMENTATION_STANDARD.md`](../../docs/CODE_DOCUMENTATION_STANDARD.md)
mandates the same four labels *"written character-for-character … and in no other
form"* (see its section "The four labels, and their one permitted written form").

⚠ **Do not use the singular variants** `Assumption:` or `Trade-off:` that appear
in the pre-existing reference tooling, and **never mix forms within one file**. A
mixed file defeats the point of a greppable label.

Alongside the labels, use the house comment idiom adapted to Java, placed
immediately above the code it explains. The spacing is deliberate — `WHAT` has
**no** space before its colon and `WHY` has **exactly one**, so the two colons
align:

```java
// WHAT: multiply at full precision, then divide with an explicit scale.
// WHY : Assumptions: app/cbl/CBACT04C.cbl lines 464 to 465 parenthesise the
//       multiplication, so the baseline's own ordering is explicit rather than
//       inferred. Trade-offs: an intermediate of wider scale is carried for one
//       step in exchange for cent-exact parity; dividing first yields different
//       cents on many inputs, and at a 2.50 annual rate it yields 0.00.
```

### 9.3 `@throws` coverage is mandatory, not situational

Rule 1 requires *"Exceptions or errors: Any that may be raised (where
applicable)"*, and `JavadocMethod` enforces it with `validateThrows="true"`.
Every codec in this module can raise, and each of these must be documented:

- decode failures on malformed input
- record-length mismatches
- invalid sign-overpunch characters (§5.2)
- invalid packed-decimal nibbles
- cursor decode failures on a tampered or truncated token
- money values that exceed the declared magnitude or arrive at the wrong scale

### 9.4 The ledger of non-obvious decisions

These are the decisions in this module that a reasonable engineer could have made
differently, so each one **must** carry a named category at its site. The ledger
is collected here so a class author does not have to rediscover which choices
need justifying.

| Decision | Category or categories | What the comment must say |
|---|---|---|
| `Money` scale 2 with `HALF_UP`, and the multiply-then-divide helper | **Assumptions** + **Trade-offs** | Cite `app/cbl/CBACT04C.cbl` lines 464 to 465; state that dividing first *"yields different cents on many inputs"* and that at a 2.50 rate it yields `0.00` |
| Money serialised as a JSON string | **Alternatives Considered** | Name the JSON number and reject it — most clients parse it into an IEEE-754 double and destroy exactness at the boundary the user sees |
| `ZonedDecimalCodec`'s explicit EBCDIC sign mode | **Assumptions** | Quote `tests/README.md` lines 273 to 274 verbatim; note that EBCDIC here names a sign convention, not an encoding |
| `PackedDecimalCodec` existing at all | **Assumptions** | The ten base masters are zoned, not packed — two regimes, two codecs; packed appears only in `CVEXPORT.cpy` and the two authorization segments |
| `PageResponse` keyset over offset | **Alternatives Considered** | Name offset pagination and state what it breaks: skipped and repeated rows under concurrent inserts |
| `CsvAuthCodec` preserving field order and delimiter | **Assumptions** | With a string-format payload, field order and delimiter **are** the contract; a JSON envelope is additive, never a replacement |
| `CsvAuthCodec`'s 13-character request money field | **Assumptions** + **Trade-offs** | The consumer's receiver is `PIC X(13)` at `COPAUA0C.cbl` line 63, one narrower than the copybook's 14 — see §10.2 |
| `JwtRoleConverter` replacing `CDEMO-USER-TYPE` | **Refactoring Rationale** | The communication area was client-echoed storage; the group claim is signed |
| `FieldValidationFlag` dropping the re-entry gate | **Refactoring Rationale** | `CDEMO-PGM-CONTEXT` has no stateless analogue, so presentation is driven by the response body |
| `TimestampFormatter`'s fixed 26-character form | **Assumptions** | The `PIC X(26)` width is the contract; the commentary mask at `CBTRN02C.cbl` line 149 must not be transliterated |
| The three misspelling corrections (§6.8) | **Refactoring Rationale** | Name the baseline spelling so the rename reads as deliberate |
| Every hand-written accessor, given Lombok's absence | **Alternatives Considered** | *"Generated accessors cannot carry the Javadoc the explainability rule requires"*; Java 21 `record` types plus explicit constructors give the same brevity with documentable members |
| Any use of core retry | **Assumptions** | The attribute is `maxRetries` (attempts = 1 + value) and the enabling annotation is `@EnableResilientMethods`, not `@EnableRetry` |

---

## 10. Testing

### 10.1 What each suite must cover

All `*Test` classes run under Surefire in the `test` phase; the one `*IT` class
runs under Failsafe (§2.3). This module needs no database and no container for
its unit suites, which is why they are fast enough to run on every build.

| Suite | Must cover |
|---|---|
| `MoneyTest` | scale and rounding behaviour, and multiply-before-divide including the `0.00` failure case that divide-first produces |
| `MoneyModuleTest` | money round-trips as a JSON **string**, and a JSON number is not silently accepted |
| `ZonedDecimalCodecTest` | round-trip including **negative** values, unsigned fields, and rejection of invalid overpunch characters |
| `PackedDecimalCodecTest` | round-trip including negative values and rejection of invalid nibbles |
| `FixedWidthCodecTest` | offset and length handling, `FILLER` drops, and record-length mismatch rejection |
| `CsvAuthCodecTest` | the exact 18-field and 6-field shapes, field order, delimiter placement — see §10.2 |
| `TimestampFormatterTest` | the 26-character form, and that the commentary mask is never used as a pattern |
| `GlobalExceptionHandlerTest`, `GlobalExceptionHandlerPathMaskingTest` | the 409 mappings, the per-field error array, and that no raw database text escapes |
| `JwtRoleConverterTest`, `CognitoAccessTokenValidatorTest` | the `'A'`/`'U'` group mapping, and that issuer and audience are checked before groups are trusted |
| `CardNumberMaskerTest`, `LogSafeTextTest` | masking to the last four digits; control-character stripping and truncation |
| `CorrelationIdFilterTest`, `CursorTokenTest` | correlation id propagation; composite-key round-trip and tamper rejection |
| `LayeringRulesTest` | the three assertions in §7 |

### 10.2 The wire shapes, so no test author re-derives them

⚠ **The copybook arithmetic and the emitted contract differ, in two places, for
two documented reasons.** Both numbers are given because a test that asserts the
naive figure will fail against a correct implementation.

**Authorization request** — 18 fields, `CCPAURQY.cpy` lines 19 to 36:

| Measure | Value | Why |
|---|---|---|
| Copybook declared width sum | **153** | money declared `PIC +9(10).99`, fourteen characters [line 27] |
| **Emitted** width sum | **152** | the money field is emitted at **13** characters to fit the consumer's receiver, `WS-TRANSACTION-AMT-AN PIC X(13)` [`COPAUA0C.cbl` line 63] |
| **Emitted** wire length | **169** | 152 plus **17 interior** delimiters |

**Authorization reply** — 6 fields, `CCPAURLY.cpy` lines 19 to 24, widths
`16, 15, 6, 2, 4, 14`:

| Measure | Value | Why |
|---|---|---|
| Declared width sum | **57** | the reply money field stays at 14 characters |
| **Emitted** wire length | **63** | 57 plus **six** delimiters — the reply carries a **trailing** delimiter, because the baseline's `STRING` appends `','` after *every* field including the last [`COPAUA0C.cbl` lines 722 to 727] |

With a string-format payload, field order and delimiter *are* the interface, so
neither may be "tidied". The reference consumer's `UNSTRING ... DELIMITED BY ','`
imposes no total length — only that each token fit its receiver — so the emitted
lengths above are an emission self-check rather than an observed producer
contract.

### 10.3 Verified vectors — the fastest route to a correct codec

Reuse the existing fixtures under `tests/fixtures/**` as inputs: *"Round-trip
tests using the existing fixed-width fixtures as vectors."* **Read them; never
modify them.** Each vector below was confirmed present in, or computed from, those
files.

| Vector | Decodes to | Note |
|---|---|---|
| `00000020650{` | `2065.00` | 12 characters, `S9(10)V99`; `{` is `+0` |
| `0000005047G` | `504.77` | 11 characters, `S9(09)V99`; `G` is `+7` |
| `0000009190}` | `-919.00` | **negative**; `}` is `-0`; from `tests/fixtures/export/happy_path/trandata.txt` |
| leading `ACCT-ID` of an `acctdata.txt` record | plain digits | `PIC 9(11)` unsigned, **no** overpunch — a guard against over-eager sign parsing. One record reads `00000000007` then status `Y` then `00000001930{`, so unsigned sits directly beside signed |
| `2022-06-10 19:27:53.000000` | — | exactly **26** characters; the final four fractional digits are structurally always `0000` (§6.2) |
| balance `1000.00` × rate `15.00` ÷ 1200 | **`12.50`** | exact; divide-first gives `10.00` |
| balance `1000.00` × rate `2.50` ÷ 1200 | **`2.08`** | `2.0833…` under `HALF_UP`; **divide-first gives `0.00`** — the failure multiply-before-divide prevents |
| `'DEFAULT   '` | `DEFAULT` | a disclosure-group id arrives space-padded; **trailing blanks are padding, not data** |

⚠ **A false-positive class to avoid when hunting for negative overpunch.**
Sequences such as `3580010001P` look like a negative overpunch but are unsigned
digits followed by an unrelated alphanumeric field — in `trandata.txt` that `P`
opens the merchant descriptor `POS TERM`. **Anchor on the field's declared offset
and length, never on a regular expression over the whole record.** This is the
same discipline §6.7 demands of the layout parser, for the same reason.

Three layouts have **no** round-trip vector available in the reference fixtures —
`CVTRA03Y`, `CVTRA04Y` and `CSUSR01Y`. Those must be tested from the copybook
declarations directly, which for `CSUSR01Y` also means its 80-byte length is
derived by summing field widths rather than read from a banner (§6.6).

### 10.4 New tests are additive

`tests/**` is REFERENCE. New Java tests are **strictly additive**: they are never
a replacement for the COBOL parity oracle, they never modify it, and they never
move its pins. See §2.5 for why its warn-level aggregate return code is its green
state and not a regression.

---

## 11. References

| Topic | Where |
|---|---|
| The written documentation convention | [`docs/CODE_DOCUMENTATION_STANDARD.md`](../../docs/CODE_DOCUMENTATION_STANDARD.md) |
| The reactor, the gates and the test split | [`services/pom.xml`](../pom.xml) |
| This module's descriptor and its dependency rationale | [`services/common-lib/pom.xml`](./pom.xml) |
| The documentation gate's rules | [`config/checkstyle/checkstyle.xml`](../../config/checkstyle/checkstyle.xml) |
| The suppressions charter | [`config/checkstyle/suppressions.xml`](../../config/checkstyle/suppressions.xml) |
| The functional-parity oracle, its toolchain and its business rules | [`tests/README.md`](../../tests/README.md) |
| Contribution conventions | [`CONTRIBUTING.md`](../../CONTRIBUTING.md) |
| The image-count asymmetry | [`infra/modules/ecr/README.md`](../../infra/modules/ecr/README.md) |

<sub>Apache-2.0 · This module is additive. The COBOL baseline under <code>app/**</code>
and the reference suite under <code>tests/**</code> are read, cited and never
modified.</sub>
