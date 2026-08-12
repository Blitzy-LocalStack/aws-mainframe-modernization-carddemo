# CardDemo `card-service`

> **Purpose.** Module guide for the **card** bounded context of the CardDemo
> mainframe-to-AWS migration: what this module builds, how to run and test it, how
> it is configured, and the COBOL provenance of every behaviour it preserves.
>
> **Source of truth.** Four COBOL programs -- `app/cbl/COCRDLIC.cbl`,
> `app/cbl/COCRDSLC.cbl`, `app/cbl/COCRDUPC.cbl` and `app/cbl/CBACT02C.cbl` -- plus
> the record contract `app/cpy/CVACT02Y.cpy`. Those paths are **REFERENCE-ONLY**:
> this module reads them and cites them by path and line, and never modifies them.
> Where this document and the artifact it describes disagree, **the artifact is
> authoritative** -- please open a fix rather than diverging.
>
> **Documentation convention.** Every file in this module, this one included, is
> documented under `docs/CODE_DOCUMENTATION_STANDARD.md`. The obligation and the
> gate that enforces it are summarised in
> [Documentation standard](#documentation-standard).
>
> **ASCII note.** This file is deliberately ASCII-only. Where it quotes a passage
> from `tests/README.md` that contains non-breaking hyphens, the quotation is
> normalised to ASCII hyphens and the normalisation is stated at the point of use.


## Purpose

`card-service` is the **card** bounded context: the card list, the card detail
read, the card update, and the sequential read path over the card file. It is the
migration target of four COBOL programs and owns exactly one PostgreSQL schema.

| Property | Value |
|---|---|
| Bounded context | card |
| Owned schema | `card` |
| Owned tables | `card.cards`, and only that one |
| Java package root | `com.carddemo.card` |
| Maven artifactId | `card-service` |
| Maven parent | `com.carddemo:carddemo-services:1.0.0-SNAPSHOT` |
| Packaging | `jar`, with `finalName` fixed to `card-service` |
| Sibling dependency | `common-lib`, and only `common-lib` |
| Queues consumed | none |
| Batch jobs run | none |

Two of the migration's most sensitive contracts live in this module.

- **The browse-to-keyset conversion.** `COCRDLIC` is the canonical CICS browse of
  the baseline, so the reference implementation of the browse-to-keyset mapping is
  this module's `CardListService` and `CardRepository`. Other contexts that page a
  list follow the shape established here, which is why the reasoning behind it is
  written out in full in
  [Behaviour preserved from the baseline](#behaviour-preserved-from-the-baseline)
  rather than assumed.
- **Primary account number masking with total card-verification-value
  suppression.** The card record is the only record in this migration that carries
  both a full primary account number and a card verification value
  (`app/cpy/CVACT02Y.cpy:5` and `:7`), so this module is where both disclosure
  decisions are made and where they are enforced.

The mainframe path is unaffected by any of this. The three CICS transactions still
exist and still run against the same VSAM files; this module adds a second path to
the same business function rather than removing the first.


## COBOL provenance

Four programs migrate into this module. The line counts below were measured
against the source branch, not estimated.

| COBOL program | Lines | Role in the baseline | Java target |
|---|---|---|---|
| `app/cbl/COCRDLIC.cbl` | 1459 | Card list -- the canonical CICS browse | `service/CardListService.java` |
| `app/cbl/COCRDSLC.cbl` | 887 | Card detail read | `service/CardViewService.java` |
| `app/cbl/COCRDUPC.cbl` | 1560 | Card update | `service/CardUpdateService.java` |
| `app/cbl/CBACT02C.cbl` | 178 | Sequential card-file reader, read-path specification | `repository/CardRepository.java` |

The three online programs are reached through three CICS transactions. These rows
are reproduced from the transaction inventory in the root `README.md:295-297`.

| Transaction | Mapset | Program | Function |
|---|---|---|---|
| `CCLI` | `COCRDLI` | `COCRDLIC` | Credit Card List |
| `CCDL` | `COCRDSL` | `COCRDSLC` | Credit Card View |
| `CCUP` | `COCRDUP` | `COCRDUPC` | Credit Card Update |

The CICS resource definitions describe each program in its own words. Quoted
verbatim from `app/csd/CARDDEMO.CSD`:

| Citation | `DESCRIPTION` string | Program |
|---|---|---|
| `app/csd/CARDDEMO.CSD:204` | `LIST CARDS` | `COCRDLIC` |
| `app/csd/CARDDEMO.CSD:220` | `VIEW CARD DETAIL` | `COCRDSLC` |
| `app/csd/CARDDEMO.CSD:228` | `CREDIT CARD UPDATE SCREEN` | `COCRDUPC` |

Two files back those programs, and both matter to the target design:
`CARDDAT` at `app/csd/CARDDEMO.CSD:25-36` is the base key-sequenced dataset, and
`CARDAIX` at `app/csd/CARDDEMO.CSD:13-24` is the account-keyed alternate-index
path. The transaction stanzas are `CCDL` at `:347-356`, `CCLI` at `:357-366` and
`CCUP` at `:367-377`.

One factual observation about the resource definitions, stated plainly and without
being characterised as an error: the `COCRDLIC` program stanza declares
`TRANSID(CC00)` at `app/csd/CARDDEMO.CSD:207`, while the transaction that actually
names `PROGRAM(COCRDLIC)` is `CCLI` at `app/csd/CARDDEMO.CSD:357-358`. `CC00` is
separately defined at `app/csd/CARDDEMO.CSD:378-379` naming `PROGRAM(COSGN00C)`,
the sign-on program, which belongs to the auth bounded context and not to this
one. Both facts are recorded here; neither is mapped into this module.

`app/**` is reference-only for the whole of this migration. This module reads the
programs, the copybook, the symbolic maps and the resource definitions as its
specification, cites them by path and line, and modifies none of them.


## Module layout

```text
services/card-service/
|-- pom.xml                    parent, dependencies, failsafe binding, finalName
|-- Dockerfile                 multi-stage, non-root, HEALTHCHECK
|-- README.md                  this file
|-- src/main/java/com/carddemo/card/
|   |-- CardApplication.java             Spring Boot entry point
|   |-- package-info.java
|   |-- api/
|   |   |-- CardController.java          REST layer, no business rules
|   |   |-- package-info.java
|   |-- service/
|   |   |-- CardListService.java                 <- COCRDLIC browse
|   |   |-- CardViewService.java                 <- COCRDSLC detail read
|   |   |-- CardUpdateService.java               <- COCRDUPC validation chain
|   |   |-- CardAdminViewService.java            administrative disclosure path
|   |   |-- CardVerificationValueCipher.java     envelope encryption of the CVV
|   |   |-- package-info.java
|   |-- repository/
|   |   |-- CardRepository.java          keyset queries plus the keyed read
|   |   |-- package-info.java
|   |-- domain/
|   |   |-- Card.java                    one entity, one copybook record
|   |   |-- EncryptedCvv.java            ciphertext holder, no plaintext accessor
|   |   |-- EncryptedCvvConverter.java
|   |   |-- package-info.java
|   |-- dto/
|   |   |-- CardSummary.java             list row
|   |   |-- CardDetail.java              detail body, masked
|   |   |-- AdminCardDetail.java         detail body, unnarrowed
|   |   |-- CardLookupRequest.java       account and card, both required
|   |   |-- CardPageQuery.java           keyset cursor request
|   |   |-- CardUpdateRequest.java
|   |   |-- package-info.java
|   |-- mapper/
|   |   |-- CardMapper.java              the ONLY masking/suppression site
|   |   |-- package-info.java
|   |-- config/
|       |-- SecurityConfig.java          resource server, authority rules
|       |-- OpenApiConfig.java           OpenAPI 3.1 metadata
|       |-- DataSourceConfig.java        search_path pin, pool sizing
|       |-- CardSelectorConfig.java      sealed card selector
|       |-- KmsConfig.java               key client for the CVV cipher
|       |-- package-info.java
|-- src/main/resources/
|   |-- application.yml
|   |-- application-dev.yml
|   |-- application-prod.yml
|   |-- db/migration/V1__card.sql        objects inside the card schema only
|   |-- db/migration/V2__card_num_digit_domain.sql
|   |                                    card_num character-domain check
|   |-- openapi/card-api.yaml            OpenAPI 3.1 contract of record
|-- src/test/
    |-- java/com/carddemo/card/**        22 test classes, package-info per package
                                         (21 *Test + 1 *IT)
    |-- resources/application-test.yml
    |-- resources/fixtures/              12 fixture files, 40 CVACT02Y records, 150 bytes each
```

**The fixture line counts files and records separately, deliberately.** The
directory holds **12 fixture files carrying 40 records in total**, every record
exactly **150 bytes** plus its line terminator — the declared length of a
`CVACT02Y` card record. The two figures differ because most fixtures carry more
than one record: the list-page corpus holds 18 so that a seven-row browse window
has a row beyond it to discover, the by-account corpus and the expiry-boundary
fixture hold 4 each, and several rule-rejection fixtures pair a rejected record
with an accepted one. One file, `card-empty-input.txt`, is **deliberately zero
bytes** — it is the empty-input case, and a fixture-contract test measures it as
zero rather than treating the absence of records as a broken file.

Refactoring Rationale: this line read "12 CVACT02Y records, 150 bytes each", which
conflated files with records and understated the corpus by 28 records. The
conflation matters beyond arithmetic: a reader sizing a paging assertion would have
believed no fixture could exercise a page boundary, since 12 records spread over 12
files cannot fill a seven-row window with a row to spare — which is exactly what
the 18-record corpus exists to do. The per-record byte contract is retained
unchanged, because that is the part a fixture author must not violate.

Three absences in that tree are deliberate and are recorded so that a reader does
not read them as omissions.

- **There is no `SqsConfig`.** This module neither publishes nor consumes a
  message. The baseline's decoupled messaging extensions belong to other bounded
  contexts, so a messaging client here would enlarge both the image and the task
  role's permission set with no caller to justify either.
- **There is no `BatchConfig`.** None of the four migrated programs is a nightly
  job. `CBACT02C` is a read-and-print utility, and its logic becomes the read path
  on `CardRepository` rather than a chunk-oriented step, so a job repository and
  its tables would be provisioned for nothing.
- **There is no module-local Checkstyle or suppressions file.** Both live only
  under `config/checkstyle`, which is what keeps this module linting under the same
  ruleset as its eight siblings. See
  [Documentation standard](#documentation-standard).

Every package under `src/main/java` and `src/test/java` carries a
`package-info.java`. That is not decoration: the Checkstyle configuration runs
`JavadocPackage` at `Checker` level, so a package without one fails the build
before compilation begins.


## Behaviour preserved from the baseline

This section is the parity record. Each entry names the decision, labels the
reasoning under one of the four categories the repository's Explainability rule
recognises, and cites the baseline line that the decision answers to.

### Keyset pagination, never offset paging

Alternatives Considered: the baseline browse state is *already* a keyset
cursor, so the target does not invent a paging model -- it names one that is
present. `app/cbl/COCRDLIC.cbl:229-248` declares `WS-THIS-PROGCOMMAREA` and
persists five things across the pseudo-conversational gap: a last-key pair
(`:230-232`), a first-key pair (`:233-235`), a screen number (`:237`), a
last-page-displayed flag (`:239`) and a next-page-exists indicator (`:242`, whose
condition name `CA-NEXT-PAGE-EXISTS` takes the value `Y` at `:244`). The page size
is fixed at 7 by `WS-MAX-SCREEN-LINES` at `app/cbl/COCRDLIC.cbl:176-178`, and the
comment at `:250` reads `28 CHARS X 7 ROWS = 196`. `CardListService.PAGE_SIZE` is
the same 7.

The browse itself is driven by the four CICS browse verbs. The line numbers below
were taken by grep against the source file rather than transcribed:

| Verb | Lines in `app/cbl/COCRDLIC.cbl` |
|---|---|
| `STARTBR` | `:1129`, `:1273` |
| `READNEXT` | `:1146`, `:1197` |
| `READPREV` | `:1294`, `:1322` |
| `ENDBR` | `:1258`, `:1376` |

Offset paging was considered and **rejected**, for one concrete reason rather than
a preference: under a concurrent insert an offset window skips rows and repeats
rows, because the row an offset counts to moves when a row is inserted ahead of it.
Browse-by-key does not, because the key it resumes from does not move. Rejecting
offset paging therefore preserves an observable behaviour rather than expressing a
taste.

The mapping is one-to-one:

| Direction | Predicate | Ordering | Fetch size |
|---|---|---|---|
| Forward page | key strictly greater than `lastKey` | `ORDER BY card_num ASC` | `LIMIT size + 1` |
| Backward page | key strictly less than `firstKey` | `ORDER BY card_num DESC` | `LIMIT size + 1` |

The backward result set is reversed in the service before it is returned, so a
caller always receives rows in ascending key order regardless of which direction
produced them.

Assumptions: the extra row in the `size + 1` fetch is not an optimisation
device and is not the target's idea. It is exactly how the COBOL discovers whether
a next page exists -- it reads one record more than the screen can hold, at
`app/cbl/COCRDLIC.cbl:1197`, and sets its next-page indicator from whether that
read succeeded. `CardListService` requests `PAGE_SIZE + 1`, publishes `PAGE_SIZE`
rows, and reports `hasNext` from whether the extra row arrived. Drop the extra row
and the indicator has nothing to derive itself from.

The COMMAREA cursor fields become the `PageResponse` envelope supplied by
`common-lib` -- `items`, `firstKey`, `lastKey` and `hasNext` -- which is a renaming
of the baseline's two key pairs and its further-rows indicator and not an addition
to them. The one COMMAREA field with no envelope member is the screen ordinal at
`app/cbl/COCRDLIC.cbl:237-238`, whose migrated home is the browser client's own
navigation state.

**Backward availability is not a component of the envelope, and
`CardListService.backwardAvailable(page, openingPage)` composes it from two facts:
the page's leading position and the caller's own opening-page state.** The first
half -- `firstKey` being present -- reports only that a backward request could be
ADDRESSED from the page, and on its own it is not a claim that a row waits there;
every page carrying rows names its own first row, so reading the answer off the
page alone reported a backward step as available on the opening page, the one page
the reference refuses it from. The second half therefore arrives as an argument.
The two facts are separated because only one of them is a property of the page:
whether a row waits at that position is a property of where the CALLER stands in
the walk, and the reference keeps exactly that on the terminal side. `app/cbl/COCRDLIC.cbl:237-238` declares
the one-digit page ordinal with `88 CA-FIRST-PAGE VALUE 1`, `:902-903` raises
`'NO PREVIOUS PAGES TO DISPLAY'` on that condition **without reading anything**,
and `:492` and `:508` move the ordinal as the two paging keys are pressed. A
revision of the shared envelope published the availability answer as a fifth
component; it is withdrawn, `PageResponseTest` refuses that member by name, and
the SPA refuses the backward key while it holds the first page, rendering the
reference's own notice at `:1301-1302`. What the envelope owes such a client is
the position to seek from, and its canonical constructor admits `hasNext == true`
only alongside a present `lastKey` so a caller told to continue always holds the
position to continue from.

**A backward request issued from the opening page anyway keeps the caller's rows.**
`CardListService.list` answers a backward read that finds no row before its cursor
with the page beginning AT that cursor, never with an empty page, because that is
what the reference does when the key is pressed regardless of the ordinal: the arm
at `app/cbl/COCRDLIC.cbl:443-444` pairs the backward key with the first-page
condition, moves the page's own first card number into the record identifier at
`:445-446`, performs **`9000-READ-FORWARD`** at `:449-450` and sends the map at
`:451-452`, while `:901-903` adds `'NO PREVIOUS PAGES TO DISPLAY'`. Re-reading the
opening page is the same page: the backward query returned nothing strictly less
than the cursor within the narrowing, so the cursor names the lowest key that
narrowing holds. The empty envelope therefore remains reachable only where it is
the truth -- an empty table, or a narrowing that matches nothing -- which is the
state the reference reports through `WS-NO-RECORDS-FOUND` at `:1241-1244` rather
than through a paging key.

### Optimistic concurrency, and HTTP 409

Refactoring Rationale: `app/cbl/COCRDUPC.cbl:470` commits the update with
`EXEC CICS SYNCPOINT`, and it is the only syncpoint verb in the program. The
program also carries its own before-image discipline, snapshotting the pre-edit
record into `CCUP-OLD-DETAILS` at `app/cbl/COCRDUPC.cbl:291` onwards so that it can
detect a change made by somebody else across the gap between reading the screen and
rewriting the record. The old approach could not hold a record lock across that
gap -- a pseudo-conversational task ends at every screen turn -- so the before-image
comparison was the only mechanism available to it.

The target expresses the same intent natively: a `@Transactional` boundary for the
commit, and a JPA `@Version` column on `Card` for the conflict detection. A version
conflict surfaces as **HTTP 409 Conflict** carrying the data-changed semantic,
mapped by `GlobalExceptionHandler` in `common-lib`. Rollback is exception
propagation, never a manual rollback call, so a failed update cannot leave a
partially applied row behind.

Assumptions: the difference in guarantee between the two platforms must be
stated honestly rather than presented as a repair. `CARDDAT` and `CARDAIX` are both
defined `RECOVERY(NONE)` (`app/csd/CARDDEMO.CSD:33` and `:21`) with `JOURNAL(NO)`
(`:31` and `:19`) and `READINTEG(UNCOMMITTED)` (`:27` and `:15`), and each is
defined with `STRINGS(1)` (`:28` and `:16`). The target's transactional and
versioning guarantees are therefore a **platform-capability difference** between a
non-recoverable VSAM dataset and an ACID relational engine. The COBOL is not
defective in this respect; it is running on a platform that was configured without
recovery, and the target runs on one that has it.

### The alternate index becomes a real secondary index

Assumptions: `CARDAIX` at `app/csd/CARDDEMO.CSD:13-24` is an account-keyed
VSAM path over the card data, surfaced to CICS as a file in its own right with
`BROWSE(YES)` and `READ(YES)` at `app/csd/CARDDEMO.CSD:19`. It is a real access
path that the online code reads: `app/cbl/COCRDLIC.cbl:215-217` holds its name in
`LIT-CARD-FILE-ACCT-PATH` with the value `CARDAIX`, alongside
`LIT-CARD-FILE` with the value `CARDDAT` at `:213-214`.

It becomes `idx_cards_account_id` on `card.cards (account_id)` plus a by-account
predicate on the repository queries. The justification for the index is that
provenance -- an alternate index is an access path the baseline code depends on, not
decoration -- and it is not a claim about throughput. Both list filters, by account
and by card number, are carried in the same keyset query rather than in separate
methods, so a filtered page and an unfiltered page traverse identical code and
cannot drift apart in their cursor handling.

### Primary account number masking, and total CVV suppression

Trade-offs: the primary account number is masked to its last four digits on
every response body **except** the administrative card-detail body, which is
reachable only with the `carddemo-admin` authority. The compromise accepted is
plain: an administrator can see a whole primary account number where an ordinary
user cannot. That is the narrowest disclosure that still preserves the baseline's
`VIEW CARD DETAIL` function (`app/csd/CARDDEMO.CSD:220`), which existed to let a
human read a card record. Removing the disclosure entirely would have removed a
baseline function; widening it to every caller would have disclosed more than the
function needs.

Refactoring Rationale: the card verification value has **no serialised
representation at all** -- not masked, not truncated, not a placeholder, absent. It
is persisted as `cvv_encrypted BYTEA`, and it is never returned in a success body,
never returned in an error body, and never written to a log line. Masking it was
considered and rejected because a masked field still discloses that the value
exists and how wide it is, and a three-digit field
(`CARD-CVV-CD PIC 9(03)`, `app/cpy/CVACT02Y.cpy:7`) narrowed to a mask discloses
nearly everything a mask could disclose. Absence is the only representation that
discloses nothing.

Source basis, phrased as what the target adds rather than as a criticism of the
baseline: all three card transactions declare `CONFDATA(NO)`
(`app/csd/CARDDEMO.CSD:353`, `:363`, `:374`) alongside `DUMP(YES)` and `TRACE(YES)`
on the immediately preceding lines (`:352`, `:362`, `:373`). The target therefore
**adds** a suppression discipline that the platform was not previously configured to
apply, rather than repairing one that was.

All masking and all suppression live **only** in `mapper/CardMapper.java`. No
controller, service, repository or DTO performs either. Concentrating both
decisions in one class is what makes the guarantee checkable by reading a single
file, and it is why suppressing `mapper/**` from the documentation gate is
prohibited -- see [Documentation standard](#documentation-standard).

A related consequence of the same reasoning: the `{cardKey}` path segment is a
sealed opaque selector, not a primary account number. A route that carried the
number itself would place it in request lines, browser history and access logs,
which are precisely the places the masking above exists to keep it out of.

### The corrected misspelling, and the dropped FILLER

Refactoring Rationale: the baseline field name `CARD-EXPIRAION-DATE` at
`app/cpy/CVACT02Y.cpy:9` is misspelled -- the second `T` of "expiration" is absent.
The target column is `expiration_date` and the Java field is `expirationDate`. This
is the **only** renamed field in this module. The rename is recorded in
`docs/architecture/data-model-and-schema-mapping.md` and justified again at the
mapping site in `CardMapper`, because the baseline field keeps its original spelling
permanently and a reader tracing the two names needs the correspondence written
down somewhere both ends can reach.

`FILLER PIC X(59)` at `app/cpy/CVACT02Y.cpy:11` is dropped. It is padding that
brings the record to its fixed length of 150 bytes rather than data, and the drop is
recorded here and in the schema-mapping document so that the 91 bytes of named
fields plus 59 bytes of padding continue to account for the full record.

### Fixed-width identifiers travel as strings

Assumptions: the card number is `PIC X(16)` at `app/cpy/CVACT02Y.cpy:5`, so its
fixed width is part of the contract and not an artefact of storage. It is `CHAR(16)`
in SQL and a digits-only **string** in every DTO, never a numeric type. A 16-digit
value routed through an IEEE-754 double loses its low-order digits silently, and a
16-digit value routed through a signed 32-bit integer does not fit at all; a string
carries all 16 digits and preserves leading zeros, which a numeric type discards.

The baseline itself treats these identifiers as characters and only as numbers when
it needs to test them: `app/cpy/CVCRD01Y.cpy:34-39` declares `CC-ACCT-ID` as
`PIC X(11)` with `CC-ACCT-ID-N REDEFINES` it as `PIC 9(11)`, and `CC-CARD-NUM` as
`PIC X(16)` with `CC-CARD-NUM-N REDEFINES` it as `PIC 9(16)`. The digits-only string
DTO is the same arrangement expressed in a language that has no `REDEFINES`.

### The validation chain, and the message-line contract

The update validation chain is transcribed from `COCRDUPC` paragraph by paragraph
rather than reimagined. The baseline's `FLG-*-NOT-OK` and `FLG-*-BLANK` condition
pattern becomes a structured per-field error array in the response body, carried by
`ApiError` from `common-lib`, and the `'*'` marker the baseline writes into a blank
field is preserved as part of that structure. Every user-visible string is carried
across verbatim, character for character, keyed by the copybook or program it came
from.

Assumptions: the message-line width governing this module is the
**75-character** form declared at `app/cpy/CVCRD01Y.cpy:28-30`:
`CCARD-ERROR-MSG PIC X(75)` at `:28`, `CCARD-RETURN-MSG PIC X(75)` at `:29`, and the
`LOW-VALUES` sentinel condition `CCARD-RETURN-MSG-OFF` at `:30`. It must not be
conflated with the 50-character message constants in `CSMSG01Y`, which belong to a
different copybook and a different width. Note also that the BMS symbolic map field
is `X(80)` while the governing copybook contract is the `X(75)` form -- the wider map
field is the screen slot, the narrower copybook field is the contract, and the
target honours the contract.

### CICS pseudo-conversational state is eliminated, not relocated

The `CDEMO-*` session structure does not travel to the target. It decomposes into
four separate mechanisms:

| Baseline COMMAREA concern | Target mechanism |
|---|---|
| Navigation (`XCTL` to a next program) | Client-side route change; no server-side next-program field exists |
| Identity (`CDEMO-USER-ID`, `CDEMO-USER-TYPE`) | Validated JWT claims |
| Selection context (`CDEMO-CARD-NUM`, `CDEMO-ACCT-ID`) | REST path and request-body parameters |
| Browse cursor | The `PageResponse` envelope |
| Re-entry discriminator (`CDEMO-PGM-CONTEXT`) | Disappears entirely |

The re-entry discriminator has no target counterpart because a stateless handler
that answers with a status code and a field-error array has no first-entry versus
re-entry distinction to draw. Error presentation is driven purely by the response
body.

Assumptions: all three card transactions declare `TWASIZE(0)` --
`app/csd/CARDDEMO.CSD:348` for `CCDL`, `:358` for `CCLI` and `:369` for `CCUP` --
so no transaction work area was allocated and every scrap of continuation state
travelled in the COMMAREA. That is why replacing the COMMAREA with a request-carried
cursor envelope is a faithful mapping of the whole of the baseline's continuation
state rather than a simplification that quietly discards a second store.

The service consequently holds no session state: no sticky sessions, no server-side
session store, and no affinity requirement between a caller and a task.

### Authorization moves to a signed claim

Refactoring Rationale: all three card transactions declare `RESSEC(NO)` and
`CMDSEC(NO)`, both on a single line each at `app/csd/CARDDEMO.CSD:354` for `CCDL`,
`:364` for `CCLI` and `:375` for `CCUP`. CICS therefore performed no resource-level
and no command-level authorization for these transactions, and the administrator
versus ordinary-user distinction lived in the `CDEMO-USER-TYPE` field of a COMMAREA
that the client echoed back. What was wrong with that arrangement is structural
rather than a coding error: a value the client supplies cannot be the basis of a
decision about what the client may do.

`SecurityConfig` together with `JwtRoleConverter` from `common-lib` move the
decision onto a signed token the client cannot assert. The administrative
card-detail route requires the administrator authority outright; the card collection
and card subtree require the user authority, which the administrator authority also
satisfies. This is again a platform-capability difference rather than a defect
claim: the mainframe had an external security manager available to it and these
particular transactions were defined without resource or command security.



## API surface

`src/main/resources/openapi/card-api.yaml` is the **OpenAPI 3.1** contract of
record for this module -- the document declares `openapi: 3.1.1` -- and the browser
client's card module is written against it rather than against the Java. The
document is also served at run time by `springdoc`, so a client can detect a
drifted contract without reading the repository.

Three addresses serve it: `/card-api.yaml` is the hand-authored contract above,
`/v3/api-docs` is the document generated from the controllers, and
`/swagger-ui.html` is the browser view of the first. **All three require a bearer
token carrying either CardDemo group** -- they are granted by a documentation rule
in `SecurityConfig`, so an unauthenticated fetch is refused with `401` exactly as
every other route is. They were reachable by nobody until that rule was added: the
chain ends in a deny-all catch-all and named no documentation path, so a configured,
generated and packaged document answered `403` to every valid token. The rule is
held to the configuration by `ContractPublicationTest`, which reads the three
addresses out of `application.yml` and requires that a chain pattern cover each of
them, so moving a `springdoc` key cannot quietly un-publish the document again.
Because the grant is by authority, opening `/swagger-ui.html` straight in a browser
is refused -- a navigation carries no token -- which is the accepted cost of not
publishing the API description anonymously.

| Method | Path | Authority required | Success | Error statuses |
|---|---|---|---|---|
| `POST` | `/api/v1/cards/search` | user | `200` | `400`, `401`, `403`, `500` |
| `POST` | `/api/v1/cards/lookup` | user | `200` | `400`, `401`, `403`, `404`, `500` |
| `GET` | `/api/v1/cards/{cardKey}` | user | `200` | `400`, `401`, `403`, `404`, `500` |
| `PUT` | `/api/v1/cards/{cardKey}` | user | `200` | `400`, `401`, `403`, `404`, **`409`**, `500`, `503` |
| `GET` | `/api/v1/admin/cards/{cardKey}` | **`carddemo-admin`** | `200` | `400`, `401`, `403`, `404`, `500` |

Reading the table against the baseline: `/api/v1/cards/search` is `CCLI`
(`LIST CARDS`), `/api/v1/cards/lookup` and `GET /api/v1/cards/{cardKey}` are `CCDL`
(`VIEW CARD DETAIL`), `PUT /api/v1/cards/{cardKey}` is `CCUP`
(`CREDIT CARD UPDATE SCREEN`), and `/api/v1/admin/cards/{cardKey}` is the narrowed
disclosure variant of `CCDL` described in
[Behaviour preserved from the baseline](#behaviour-preserved-from-the-baseline).

`409` appears on exactly one route, the update, and it carries the version-conflict
semantic. `503` appears on the update alone as well, because the update is the only
route that depends on the key service used to protect the card verification value,
and a route that cannot reach that service must say so rather than write a row
without it.

The two authority tiers are declared as data in `SecurityConfig` rather than as a
chain of inline matchers, so the rules can be enumerated by a test. The
administrative route admits the administrator authority only; the collection and
the card subtree admit the user authority, which the administrator authority also
satisfies.

That table holds the **published operations** only. The chain grants four groups in
total and the other three are stated separately: health openly, because a
load-balancer probe presents no token; `/actuator/info` and `/actuator/prometheus`
by loopback network position, because their only consumer is the task-local
collector, which presents none either; and the documentation addresses above by the
user authority. Keeping the documentation paths out of the operation table is
deliberate -- that table is compared against the contract's operation list in both
directions, and a documentation path inside it would be read as an operation the
contract had failed to declare.

**No endpoint, no request parameter and no DTO in this module exposes an offset or
a page-number pagination control.** Paging is by key only. The list request body is
`CardPageQuery`, whose three components are an optional account-number filter, an
opaque `cursor` and a `direction`; the response is the `PageResponse` envelope with
`items`, `firstKey`, `lastKey` and `hasNext`. There is no field a
caller could set
to "row 200 onwards", which is the point -- see the rejection rationale in
[Keyset pagination, never offset paging](#keyset-pagination-never-offset-paging).


## Data model and migrations

`card.cards` is the single table this module owns. Its columns derive mechanically
from the 150-byte `CARD-RECORD` declared at `app/cpy/CVACT02Y.cpy`, whose header
comment at `:2` states the record length:

```text
L5  CARD-NUM            PIC X(16)   offset   1 to  16   -> CHAR(16) primary key, digits-only String DTO
L6  CARD-ACCT-ID        PIC 9(11)   offset  17 to  27   -> BIGINT account_id, idx_cards_account_id
L7  CARD-CVV-CD         PIC 9(03)   offset  28 to  30   -> cvv_encrypted BYTEA, never serialised
L8  CARD-EMBOSSED-NAME  PIC X(50)   offset  31 to  80   -> VARCHAR(50)
L9  CARD-EXPIRAION-DATE PIC X(10)   offset  81 to  90   -> expiration_date (baseline misspelling corrected)
L10 CARD-ACTIVE-STATUS  PIC X(01)   offset  91          -> CHAR(1) CHECK IN ('Y','N')
L11 FILLER              PIC X(59)   offset  92 to 150   -> dropped, drop recorded
                                    16+11+3+50+10+1+59 = 150 verified
```

The `offset` values above are **byte positions inside the fixed-width record**.
They are not pagination controls, and nothing in the API surface exposes them.

The resulting schema:

```sql
CREATE TABLE card.cards (
    card_num          CHAR(16)     NOT NULL,
    account_id        BIGINT       NOT NULL,
    cvv_encrypted     BYTEA,
    embossed_name     VARCHAR(50)  NOT NULL,
    expiration_date   DATE         NOT NULL,
    active_status     CHAR(1)      NOT NULL,
    version           INTEGER      NOT NULL DEFAULT 0,
    CONSTRAINT pk_cards PRIMARY KEY (card_num),
    CONSTRAINT ck_cards_active_status CHECK (active_status IN ('Y', 'N'))
);

CREATE INDEX idx_cards_account_id ON card.cards (account_id);
```

Four of those lines answer directly to a decision recorded earlier:
`CHAR(16)` rather than a numeric type is the fixed-width contract of
`app/cpy/CVACT02Y.cpy:5`; `cvv_encrypted` is nullable because a record may legally
arrive without a verification value and the column must not force a fabricated one;
`version` is the optimistic-concurrency column that replaces the before-image
comparison; and `idx_cards_account_id` is the target of `CARDAIX`.

### Migration ownership

This module owns **two** migrations, and both own **only the objects inside the
`card` schema**. The schema itself, the database role that owns it and the grants
that role holds are bootstrapped once by
`data-migration/sql/V0__schemas_and_roles.sql`, outside any service. The runtime
configuration reflects that split: Flyway is pointed at the `card` schema and is
configured not to create schemas, so a migration run cannot silently bring a
schema into existence that the bootstrap did not authorise.

| Version | File | What it installs |
|---|---|---|
| V1 | `src/main/resources/db/migration/V1__card.sql` | The `card.cards` table, its primary key, its `NOT NULL` and domain constraints, and `idx_cards_account_id` |
| V2 | `src/main/resources/db/migration/V2__card_num_digit_domain.sql` | `ck_cards_card_num_digits`, closing the `card_num` character domain to exactly sixteen digit characters |

**Why the check arrived as V2 rather than as an edit to V1.** V1 declares
`card_num CHAR(16)` and states in its own text that a shorter or non-numeric value
is outside the contract, but it installs no constraint saying so, so the column
accepted values the API cannot render: an alphabetic sixteen, and a fifteen-digit
value that the fixed-width column pads with a blank. Runtime testing found the
consequence — such a row could not be masked into the shape the listing row
publishes, and the browse answered every caller with a server failure. V1 is **not**
edited to add the check, because V1's checksum is recorded in the Flyway history
table of every database already migrated and altering an applied migration makes
`flyway validate` fail there; V1's own header instructs that a correction arrives as
a new version. Assumptions: this module's `V2__` and `reference-service`'s
`V2__seed_reference.sql` do not collide — a version number is scoped to the history
table of the schema it is applied into, so each context numbers independently.

**Applying V2 to a database that already holds a non-conforming row.** The
constraint is added validating, not `NOT VALID`, so the migration fails rather than
admitting a row it cannot render. List the offending rows first with
`SELECT card_num, account_id FROM card.cards WHERE card_num !~ '^[0-9]{16}$';` and
correct or remove each one; the migration's own header carries the same procedure at
its point of use.

### Flyway needs two coordinates, not one

Assumptions: Flyway 10 and later moved PostgreSQL support out of `flyway-core`
into a separate dialect artifact, so `org.flywaydb:flyway-database-postgresql` is
declared explicitly at `services/card-service/pom.xml:349-352` alongside the Spring
Boot Flyway starter at `services/card-service/pom.xml:345-348`. This is worth
stating because the failure mode has no compile-time signal: with the dialect
artifact absent the module still compiles and still packages, and then fails at
application startup when Flyway cannot resolve a database type for the JDBC URL it
was handed. A missing dialect is discovered by starting the service, not by
building it.

Trade-offs: the same asymmetry applies one level up, and the starter is declared
rather than relied on as a transitive of the JPA starter. The cost accepted is one
more coordinate in a file that a reader might expect to inherit it. What it buys is
that the activation cannot disappear when an unrelated starter reorganises its own
dependencies -- and without the Flyway autoconfiguration on the classpath every
migration setting in `src/main/resources/application.yml` is inert,
`V1__card.sql` never runs, and the service starts and reports healthy against a
database that has no `cards` table at all. Build-green and runtime-broken is the
expensive shape of failure here, which is why both coordinates are named rather than
inferred.



## Build, test and run

Every command below is run from the **repository root** unless it says otherwise.

```bash
# WHAT: build and test every service module, this one included.
# WHY : the reactor builds common-lib before card-service, so a module-scoped
#       build cannot resolve either the parent POM or the sibling dependency on
#       its own. Surefire runs the classes whose names end in Test during the
#       test phase and Failsafe runs CardRepositoryIT during verify, so only a
#       goal that reaches verify starts the database container.
mvn -B -f services/pom.xml clean verify
```

```bash
# WHAT: fire the inherited Checkstyle documentation gate for this module alone.
# WHY : the gate is bound to the Maven validate phase, which runs before
#       compilation, so this invocation reports a missing Javadoc comment or a
#       missing package-info.java in isolation instead of behind a compiler
#       error. Invoking the lifecycle phase rather than the plugin goal directly
#       is deliberate: a bare goal invocation bypasses the configuration the
#       parent POM supplies and audits with plugin defaults, which reports a
#       result that does not match the gate CI runs.
mvn -B -f services/card-service/pom.xml validate
```

```bash
# WHAT: build this module and the shared library it depends on, and nothing else.
# WHY : Trade-offs: this builds two modules instead of nine, and it proves less
#       in exchange -- it cannot detect that a change to common-lib broke a
#       sibling bounded context. Use it while iterating on this module; use the
#       full clean verify above before committing.
mvn -B -f services/pom.xml -pl card-service -am verify
```

```bash
# WHAT: run this module's unit tests only, without starting a container.
# WHY : Assumptions: the split is by class-name suffix -- a Test suffix binds to
#       the test phase, an IT suffix to integration-test and verify -- so
#       stopping at test deliberately excludes CardRepositoryIT and therefore
#       needs no container runtime available.
mvn -B -f services/pom.xml -pl card-service -am test
```

Starting locally takes two steps, and the first is not optional: the ten variables
under [Configuration](#configuration) that have no fallback must already be in the
environment, and they are prepared **out of band** so that no credential or key
identifier is ever typed on a command line.

```bash
# WHAT: create the environment file with owner-only permissions, before writing
#       any value into it, then fill it in with an editor.
# WHY : Assumptions: the name is `.env.card-service.local` specifically because
#       `.gitignore` ignores `.env.*`, which the `git check-ignore` line confirms
#       by printing the rule and its line number. A name such as
#       `card-service.env` matches no ignore rule in this repository and would be
#       staged by `git add -A` along with the CVV key identifier and the card
#       selector signing key it holds.
# WHY : Assumptions: `umask 077` is applied BEFORE creation rather than corrected
#       afterwards. A later `chmod` leaves a window in which the file was group-
#       and world-readable; the `chmod` below is a second assertion for a file
#       surviving from an earlier session, not the primary control.
umask 077
touch .env.card-service.local
chmod 600 .env.card-service.local
git check-ignore -v .env.card-service.local

# Fill in one KEY=value per line, with no `export` and no quoting:
#   SPRING_DATASOURCE_URL, SPRING_DATASOURCE_USERNAME, SPRING_DATASOURCE_PASSWORD,
#   SPRING_FLYWAY_USER, SPRING_FLYWAY_PASSWORD,
#   SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI,
#   CARDDEMO_SECURITY_JWT_EXPECTED_CLIENT_ID, CARDDEMO_SECURITY_CVV_KEY_ID,
#   CARDDEMO_SECURITY_CARD_SELECTOR_SIGNING_KEY
```

```bash
# WHAT: the COMPLETE local launch contract, in the order it has to be performed.
# WHY : (1) Refactoring Rationale: this block used to be the package step and the
#       `java -jar` alone. Following it could not start the service, and the two
#       reasons are independent. Ten variables listed under Configuration have NO
#       fallback in application.yml, so the process stopped at startup naming the
#       first key it could not resolve; and `application.yml` enables TLS and opens
#       a PKCS#12 keystore that exists only inside the deployed image, so even a
#       complete environment failed on the keystore. The environment source and the
#       TLS disable are therefore part of the launch contract rather than asides,
#       and they are ordered before it because that is the order they have to
#       happen in.
#       (2) Assumptions: the profile is named on the command line and is never
#       baked into the image, because the same image artifact must be able to run
#       under either profile; an image carrying a profile could only ever serve one.
#       (3) Assumptions: the ELEVEN no-fallback variables stop the process at startup
#       rather than letting it serve requests bound to nothing, and Configuration
#       marks each of the eleven `none` so an environment file can be checked against
#       it. Ten are `${...}` placeholders in `application.yml`; the eleventh,
#       CARDDEMO_PAGINATION_CURSOR_SIGNING_KEY, is not written in any profile and
#       reaches the shared kernel through relaxed binding, so an audit of the
#       profiles alone will not find it -- see the rationale under Configuration. Trade-offs: the failure names the SYMPTOM rather than the key for the
#       framework-bound values. Measured on the sibling transaction service with
#       nothing set, the first failure is `'url' must start with "jdbc"` -- Spring
#       Boot's binder leaves an unresolvable placeholder as its own literal text, so
#       the property holds the characters `${SPRING_DATASOURCE_URL}` rather than
#       being reported absent. Values injected with `@Value` do name themselves,
#       because that path resolves through the environment rather than the binder.
#       An earlier revision of this note said the startup failure names the key it
#       could not resolve; that is true of some values and not of these.
#       (4) Trade-offs: those values are supplied from an environment file that is
#       deliberately not committed, rather than typed on the command line, because
#       a shell history is a poor place for a credential and `.gitignore` already
#       excludes `.env`. THREE of the eleven are key material -- the card selector
#       key, the card-verification key identifier and the paging cursor key -- so
#       this is not a hypothetical concern for this service.
#       (5) Assumptions: `SERVER_SSL_ENABLED` is not a `${...}` placeholder in any
#       profile; it reaches `server.ssl.enabled` through the framework's relaxed
#       binding of an environment name onto a property. Its fallback is therefore
#       `true` rather than `none`, which is why an absent value does not stop
#       startup -- the listener stays encrypted and then cannot open its keystore.
# WHY : Alternatives Considered: minting a local certificate so a local run also
#       speaks TLS. Rejected because the certificate would not match the loopback
#       name for any caller that verified it, and because the deployed path mints
#       its own material per task in the image entry point; disabling the listener
#       locally states plainly that a local run does not exercise the deployed
#       transport rather than appearing to.
mvn -B -f services/pom.xml -pl card-service -am package

export SERVER_SSL_ENABLED=false
set -a && . ./card-service.env && set +a
java -jar services/card-service/target/card-service.jar --spring.profiles.active=dev
```

```bash
# WHAT: build the service image from the repository root
# WHY : the Checkstyle SuppressionFilter is declared optional="false", so
#       config/checkstyle/** must be inside the build context or the
#       validate phase fails; that directory sits above services/
# WHY : Assumptions: the optional="false" setting is at
#       config/checkstyle/checkstyle.xml:257-260, and the copy that satisfies it is
#       services/card-service/Dockerfile:201. Building from the module directory
#       instead puts config/checkstyle/ outside the context, and the failure
#       surfaces as an unresolved suppressions file rather than as a missing file,
#       so the cause is not obvious from the message.
docker build -f services/card-service/Dockerfile -t carddemo/card-service:local .
```

That container build is **validation-only**: it proves the image assembles, that
the offline reactor inside it resolves, and that the documentation gate passes
inside the build context. It publishes nothing.

Publication is a **separate workflow**, and the distinction is between the two
pipelines rather than between the repository and the outside world.
[`.github/workflows/services-ci.yml`](../../.github/workflows/services-ci.yml) —
the continuous-integration workflow that runs on a change — declares no registry
login
and no cloud credential at any scope, so nothing it does can push an image.
[`.github/workflows/deploy.yml`](../../.github/workflows/deploy.yml) does push:
it assumes a role by **GitHub OIDC** rather than holding a long-lived access key,
logs in to **Amazon ECR**, and builds and pushes the project's **ten** immutable
service images, this module's among them; it additionally mirrors the AWS
OpenTelemetry collector image, which is why
[`infra/modules/ecr`](../../infra/modules/ecr) provisions **eleven** repositories
rather than ten.

Refactoring Rationale: this paragraph stated that nothing in this repository
pushes the image to a registry. That was false of `deploy.yml`, and the error was
not merely factual — a reader auditing how images reach production would have
concluded the path was undocumented or manual and gone looking for a credential
that does not exist, when the actual path is keyless OIDC role assumption and is
worth knowing precisely because it holds no stored secret. What remains true, and
is now said separately, is that **nothing here has been run against a live AWS
account** — see [Known limitations](#known-limitations).

> **Note.** The documentation gate must never be weakened, in this module or in
> any command that builds it. Passing `-Dcheckstyle.skip`, adding a plugin-level
> `<skip>true</skip>`, setting `failOnViolation=false`, appending `|| true` to a
> build command, or setting `continue-on-error: true` on a workflow step are each
> **prohibited**. The reason is specific rather than procedural: the gate is the
> only mechanism that enforces the repository's single user-specified rule, so a
> build that skips it produces an artifact whose compliance nobody has checked
> while still reporting success.


## Configuration

Values are **not** reproduced here, by design. Each row names a variable and says
what it selects; nothing in this repository carries a credential, an endpoint, an
account identifier or a key identifier.

| Variable | Selects | Fallback |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | Active profile, `dev` or `prod` | Spring default |
| `SPRING_DATASOURCE_URL` | JDBC location of the cluster | none |
| `SPRING_DATASOURCE_USERNAME` | Runtime database role | none |
| `SPRING_DATASOURCE_PASSWORD` | Credential for that role | none |
| `SPRING_FLYWAY_USER` | Migration role, distinct from the runtime role | none |
| `SPRING_FLYWAY_PASSWORD` | Credential for the migration role | none |
| `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI` | Token issuer the resource server trusts | none |
| `CARDDEMO_SECURITY_JWT_EXPECTED_CLIENT_ID` | App client a presented token must name | none |
| `CARDDEMO_SECURITY_CVV_KEY_ID` | Key used to protect the verification value | none |
| `CARDDEMO_SECURITY_CARD_SELECTOR_SIGNING_KEY` | Key that seals the opaque card selector | none |
| `CARDDEMO_SERVER_TLS_KEYSTORE_PASSWORD` | Password of the listener keystore | none |
| `CARDDEMO_PAGINATION_CURSOR_SIGNING_KEY` | Key that seals the paging cursor the card list issues | none |
| `CARDDEMO_ONLINE_WRITES_PARAMETER` | Names the SSM flag that closes writes during the batch window | none in effect |
| `CARDDEMO_SERVER_TLS_KEYSTORE` | Location of the listener keystore | present |
| `CARDDEMO_SERVER_TLS_KEY_ALIAS` | Listener key alias inside that keystore | present |
| `CARDDEMO_COGNITO_ADMIN_GROUP_NAME` | Group mapped to the administrator authority | present |
| `CARDDEMO_COGNITO_USER_GROUP_NAME` | Group mapped to the user authority | present |
| `CARDDEMO_DB_SSL_ROOT_CERT` | Trust anchor for the database connection | present |
| `CARDDEMO_ENVIRONMENT` | Environment tag applied as a Micrometer common tag | present |
| `CARDDEMO_VERSION` | Build identity reported by the actuator | present |
| `SERVER_SSL_ENABLED` | Whether the listener is encrypted; set `false` for a loopback-only local run | `true`, set in `application.yml` |

The **eleven** rows marked `none` have no fallback on purpose, rather than letting the
service come up bound to a default that happens to be wrong. Trade-offs: a missing one
does not always name itself. The values read through `@Value` do, but the
framework-bound ones do not — measured on the sibling transaction service with its
Flyway rows omitted, the first failure was `FATAL: password authentication failed for
user "${SPRING_FLYWAY_USER}"`, because the binder leaves an unresolvable placeholder as
its own literal text and the driver was handed those characters as a username.

Refactoring Rationale: **three rows above are not `${...}` placeholders in any
profile**, and two of them were missing from this table entirely.
`CARDDEMO_PAGINATION_CURSOR_SIGNING_KEY` and `CARDDEMO_ONLINE_WRITES_PARAMETER` reach
`carddemo.pagination.cursor.signing-key` and `carddemo.online-writes.parameter` through
relaxed binding, and both properties are declared in the shared kernel's
auto-configuration rather than here — so an audit of this module's profiles could not
see them. They fail in opposite ways, which is why both are named: the cursor key
**stops startup**, because its bean is `@ConditionalOnProperty` and
`service/CardListService` takes a `CursorToken` as a mandatory constructor argument with
no `ObjectProvider` wrapper, so the card list is unreachable without it; the write gate
**removes itself silently**, because both its beans are conditional on the same property
and nothing outside the auto-configuration injects them, so an absent value leaves the
card update accepting writes during the nightly batch window with nothing in the log to
say the gate is gone. `infra/modules/ecs-service` requires the cursor key by name and
makes the gate name biconditional for the seven web workloads, so a root that drops
either fails at `plan`. `SERVER_SSL_ENABLED` is the third and the only harmless one: it
reaches `server.ssl.enabled` the same way, which is why it is listed with a fallback
rather than as required, and why a local run has to set it even though nothing complains
when it does not.

Three properties are configured rather than injected, and are named here because a
reader looking for an environment variable will not find one:

- **The listener port.** `application.yml` sets `server.port` to a literal value,
  so there is no port placeholder to supply. It remains overridable through Spring
  relaxed binding by an environment variable of the corresponding name, which is a
  framework capability rather than something this module declares.
- **The management-endpoint exposure.** `management.endpoints.web` is configured in
  `application.yml`, which narrows what the actuator publishes rather than leaving
  the framework default in place.
- **The schema.** `spring.flyway.schemas` and `spring.flyway.default-schema` both
  name `card`, and `spring.flyway.create-schemas` is false.

Every value that is injected is supplied at run time from AWS Systems Manager
Parameter Store and AWS Secrets Manager, sourced from Terraform module outputs.
**No service hard-codes an endpoint**, and no credential, endpoint or identifier
appears anywhere in this repository.

Assumptions: `src/main/java/com/carddemo/card/config/DataSourceConfig.java` pins
the JDBC session `search_path` to `card`, and the migration side is pinned
independently at `src/main/resources/application.yml:596-597`, which names `card` as
both the Flyway schema and its default schema, with schema creation switched off at
`src/main/resources/application.yml:630`. That matters because no query in
`CardRepository` names a schema: with the path pinned, an unqualified table reference
can only resolve inside this bounded context's schema, whereas with the path left at
its default an unqualified name could silently resolve into a table of the same name
in another context's schema and return another context's rows without erroring.
Pinning it in two independent places is deliberate, so neither the runtime session
nor the migration run depends on the other having got it right.

The region the AWS clients resolve is taken from the standard AWS region resolution
chain rather than from a module-specific property, so a task inherits the region it
is running in without this module restating it.



## Tests

<!-- test-inventory: 21 tests + 1 integration tests -->
**22** test classes: **21** matching `*Test`, run by Surefire, and **1** matching `*IT`, run by
Failsafe. That census is machine-checked — `ServiceReadmeInventoryTest` in `common-lib` parses the
comment above and re-measures both figures against this module's test tree, so the count fails the
build when it drifts rather than ageing quietly in prose.

The five classes below carry the parity assertions that matter most to this module.
They are named individually because each one is the only place a particular
guarantee is checked.

| Test class | Kind | What it proves |
|---|---|---|
| `api/CardControllerTest` | MockMvc over a hand-assembled web context | The administrator and ordinary-caller split on the card-detail route, both directions; that an unauthenticated request is challenged rather than served; that a token carrying no recognised group reaches no card route; that a masked read discloses only the last four digits; that a stale revision is answered with the reference sentence; and that every asserted sentence fits the program-side message width |
| `service/CardListServiceTest` | unit | A forward step resumes past the last returned key; a backward step resumes before the first returned key; the row beyond the window discovers a further page; a row arriving between two requests is neither hidden nor repeated; and the browse window is seven rows |
| `service/CardUpdateServiceTest` | unit | Every transcribed validation gate, including that all four gates run so every fault is reported, that the summary sentence follows the reference gate order, that a refused submission is never written, and that a stale token is refused even when nothing would change |
| `repository/CardRepositoryIT` | Testcontainers | The account-keyed access path that replaces `CARDAIX`, the keyed read, forward and backward keyset paging, the concurrent-insert boundary, empty results, that both migrations were applied in version order, and that `ck_cards_card_num_digits` refuses a stored key outside the sixteen-digit domain while admitting a conforming one |
| `mapper/CardMapperTest` | unit | That no serialised body carries the verification value, that the card number is masked on every non-administrative body, that the administrative disclosure returns it whole, that no body carries the record's trailing padding, that both identifiers travel as digit strings with leading zeros intact, and that a stored key outside the published domain costs its own listing row rather than the whole page while a single-card read still refuses |

**`CardControllerTest` does not use `@WebMvcTest`, and cannot.** It builds an
`AnnotationConfigWebApplicationContext` itself, registers the controller and the
security configuration into it, and wraps the result with
`MockMvcBuilders.webAppContextSetup(context)`, applying the real
`springSecurityFilterChain` bean. Assumptions: this is a deliberate shape, not an
older style left in place — Spring Boot 4 moved the servlet slice annotation into a
separate artifact that this module's POM does not declare and that
`spring-boot-starter-test` does not carry, so the annotation is not on the test
class path at all. The class records that finding at its own head, along with the
verification that the resolved `spring-boot-test-autoconfigure` jar contains a
slice descriptor only for the JSON testers. Trade-offs: assembling the context by
hand costs a few lines of setup and buys two things worth more than the brevity —
the REAL resource-server filter, authentication converter and role converter all
run, so the authority split under assertion is the deployed one rather than a
mocked stand-in; and `standaloneSetup`, the other obvious route, was rejected
because it installs no filter chain and so could not assert that an
unauthenticated request is challenged. The sign-on slice in `auth-service` reached
the same conclusion and is assembled the same way.

Refactoring Rationale: the table labelled this class `@WebMvcTest`. The label was
wrong in a way that would cost a reader real time: someone extending the suite
would have copied an annotation that does not resolve, and someone auditing the
authority assertions would have assumed the framework was auto-configuring a
sliced context when in fact the full security chain is exercised — an assumption
that understates what the class proves.

The wider test tree holds more than those five. It contains contract-census tests
that assert the published members of each DTO and each route against
`openapi/card-api.yaml`, a fixture-contract test that measures every fixture against
the copybook, a security-configuration test that enumerates the authority rules as
data, cipher and persistence-boundary tests around the encrypted verification value,
and the shared `LayeringRulesTest` from `common-lib`, which is what applies the
architecture rules to `com.carddemo.card` rather than leaving them authored but
unenforced here.

The module's own test tree holds **22 classes**. Adding the inherited
`LayeringRulesTest`, a `mvn -B -f services/pom.xml -pl card-service -am clean verify`
run reports **335 tests across 23 report files, with no failures, no errors and
nothing skipped** -- 301 from the Surefire execution, 9 from the architecture-rules
execution and 25 from Failsafe. Those figures were measured from the reports that run
produced rather than carried over from a previous one.

Assumptions: the architecture rules arrive as a separate Surefire execution
because a rule can only analyse the classpath of the module whose tests run it, and
Maven passes a module's main classes to its consumers but never its test classes. The
rules are authored once in `common-lib` and delivered here through that module's test
jar, which is what makes them apply to `com.carddemo.card` at all. Without that
delivery the rules would be written but would analyse nothing here, which is not
distinguishable from not having written them.

Alternatives Considered: an embedded in-memory database for
`CardRepositoryIT`, rejected in favour of a real PostgreSQL engine through
Testcontainers. All three properties that test exists to check are engine
behaviours: keyset ordering under a strictly-greater-than predicate, `CHAR(16)`
blank-padding semantics on the card number declared at `app/cpy/CVACT02Y.cpy:5`, and
whether the planner actually selects `idx_cards_account_id`, the index standing in
for `CARDAIX` at `app/csd/CARDDEMO.CSD:13-24`. An in-memory engine reproduces each of
the three differently, or not at all, so a green test against one would say nothing
about the engine the service runs on -- which is the opposite of what an integration
test is for. Trade-offs: the cost accepted is that this test needs a container
runtime available, which is why it is bound to `verify` rather than to `test` and why
a plain `test` invocation deliberately excludes it.

### Fixtures, and the absence of a golden master

Fixtures live under `src/test/resources/fixtures/` and are `CARD-RECORD` images at
the verified **150-byte** record length of `app/cpy/CVACT02Y.cpy`. A register in
`src/test/resources/fixtures/README.md` states the contract each file is measured
against, and `fixtures/CardFixtureContractTest` enforces that register, so a fixture
cannot drift away from the copybook without a test failing.

This module ships fixtures with **no golden-master counterpart**, and that is
internally consistent rather than an omission. There is house precedent for exactly
this shape: `tests/README.md:143-146` records that the `export` domain of the
existing suite "ships fixtures but **no** `golden/` directory", because that domain
drives a byte-identical round-trip whose expectation is expressed by the round-trip
itself. The same reasoning applies here, and there is a second, stronger reason
recorded in [Known limitations](#known-limitations): a golden master for this module
could not be produced at all.


## Known limitations

This section is deliberately blunt. Overstatement anywhere in this document would
do the most damage here.

> **Note.** There is **no golden master for any path in this module**, and none can
> be produced on the runner. `tests/README.md:83-85` records that the online `CO*`
> CICS programs "cannot run end-to-end without a CICS runtime (absent on the
> runner); only their extractable field-validation logic is unit-tested". All three
> online programs this module migrates are `CO*` programs, so the repository's
> golden-master oracle covers **batch flows only** and covers nothing here. Parity
> for this module therefore rests on two things and no third: validation logic
> transcribed paragraph by paragraph from the COBOL, and the copybook and copybook
> message contracts cited throughout this document. No claim of golden-master
> parity is made for this module, because there is no oracle to make it against.

The remaining limitations, each stated as a fact about the baseline or about the
boundary of this work:

- **The existing suite is reference.** `tests/**` and `scripts/**` are never
  modified and never re-pinned by this module. That suite's documented aggregate
  worst-case return code of **4** is its **green** state and must not be read as a
  regression introduced here. Quoting `tests/README.md:232-234`, with the original's
  non-breaking hyphens normalised to ASCII hyphens: it "aggregates a worst-case
  return code across all layers ... and returns that single status to the caller, so
  CI gets one deterministic pass/fail signal."
  Assumptions: the COBOL 0/2/4/8/16 return-code rubric must never reach a Maven,
  Surefire, Failsafe or JUnit gate. A Java gate is binary -- it passes or it fails --
  so a rubric that treats 4 as an acceptable outcome cannot be expressed there, and
  attempting to express it would turn a soft warn in one system into a green build in
  another.
- **An unreachable paragraph in the baseline.** `9150-GETCARD-BYACCT` at
  `app/cbl/COCRDSLC.cbl:779-810` is unreachable: no `PERFORM` anywhere in the program
  names it, and `9000-READ-DATA` at `app/cbl/COCRDSLC.cbl:726-730` performs only
  `9100-GETCARD-BYACCTCARD`. This is recorded as a factual observation about the
  baseline. Nothing here claims to have fixed it, and it is **not** the justification
  for `idx_cards_account_id` -- that justification rests entirely on `COCRDLIC`'s
  browse and on the `CARDAIX` definition at `app/csd/CARDDEMO.CSD:13-24`.
- **Two opposite key conventions, both preserved.** The detail screen requires
  **both** an account identifier and a card number: `app/cbl/COCRDSLC.cbl:648` and
  `:688` set the account and card filter flags to their not-OK state as the default,
  so an absent value is an error. The list screen does the opposite:
  `app/cbl/COCRDLIC.cbl:1004` and `:1039` set the corresponding flags to their blank
  state, so an absent value means "do not filter" rather than "invalid". The two
  conventions are preserved exactly as they are, which is why the lookup route
  requires a card number while the browse narrowing is optional.
- **The clear-field sentinel is not reproduced.** `app/cbl/COCRDSLC.cbl:614-627`
  normalises a literal `*` in either key field exactly as it normalises blanks -- the
  comment at `:614` reads `REPLACE * WITH LOW-VALUES`. That is a 3270 terminal
  affordance for clearing a field that a keyboard could not otherwise empty, and it
  has no REST analogue: a JSON client omits a member or sends an empty string. The
  omission is deliberate and is asserted by a controller test so that it cannot be
  reintroduced by accident.
- **The two-step update flow collapses into one request.**
  `app/cbl/COCRDUPC.cbl:276-290` carries a change-action state machine whose values
  distinguish details-not-yet-fetched, show-details, changes-made,
  changes-OK-but-not-confirmed and changes-confirmed-and-done. That sequence existed
  because a 3270 screen turn cannot both validate and commit. The target answers a
  single idempotent request. This is an intentional structural change with no
  behavioural loss -- the same validation gates run, in the same order, and produce
  the same sentences -- and it is recorded as such rather than presented as an
  improvement to the program.
- **Message-string casing is inconsistent in the baseline, and stays inconsistent.**
  Some user-visible strings across these programs are upper case and some are mixed
  case. They are carried across verbatim, inconsistency included. That is reported
  here as a fact about the strings, not as something corrected.
- **The deployment boundary.** The infrastructure for this stack is authored and
  statically validated elsewhere in this repository. This module has **not** been
  run against, or provisioned into, a live AWS account; applying that infrastructure
  to a real account is an operator action outside the boundary of this work. The
  container build described above assembles and validates an image locally and
  nothing more.
- **Out of scope for this module, explicitly.** So that absence is not mistaken for
  an oversight, none of the following is part of this module and none is claimed as
  a feature of it: multi-region or disaster-recovery topology; blue-green or canary
  release; Kafka or Kinesis; Redis or ElastiCache; a read replica or any
  read/write routing; the Db2 rewards extension; IMS DC; SFTP integration; and
  exposing distributed transactions. The reasoning for the two a reader is most
  likely to expect is specific: a cache tier would introduce an invalidation
  contract that no baseline behaviour expresses an expectation about, so a stale read
  would be a behaviour change nothing could detect; and this module neither publishes
  nor consumes a message, so a streaming platform would have no producer and no
  consumer here.



## Documentation standard

Every file in this module is bound by the repository's single user-specified rule,
**Explainability**. The rule's full text is not reproduced here;
`docs/CODE_DOCUMENTATION_STANDARD.md` is the written convention and the source of
full text, and `review_rules` is where the rule itself lives. What follows is a
summary of the obligation as it applies to this module.

- **A docstring on every class, method and module entry point**, in Javadoc form for
  Java, specifying **Purpose**, **Parameters**, **Return values** and **Exceptions
  or errors** where applicable. A trivial accessor with no logic may use a
  single-line form.
- **A `package-info.java` in every package**, main and test alike.
- **An `@param` for every DTO record component.** A record's components are its
  parameters, so a record declaration with an undocumented component is an
  incomplete docstring rather than a stylistic lapse.
- **Inline comments adjacent to the code they explain**, saying **why** rather than
  restating what the code already shows. Each non-obvious decision carries at least
  one of `Alternatives Considered:`, `Refactoring Rationale:`, `Assumptions:` or
  `Trade-offs:` -- the same four labels used throughout this document.

### Precedence, when the prose and the linter disagree

The written convention lives at `docs/CODE_DOCUMENTATION_STANDARD.md`. **Where that
prose standard and the linter configuration at `config/checkstyle/checkstyle.xml`
disagree, the linter configuration is authoritative.** The reason is not that the
prose matters less: it is that the linter is the artifact that actually decides
whether a build passes, so a contributor who satisfies the prose and fails the
linter is still blocked, and a rule that cannot be enforced is a recommendation.
Discovering a disagreement is a reason to fix the prose, not to argue with the gate.

### The gate, and its exact parameters

Checkstyle is bound to the Maven **`validate`** phase by the parent
`services/pom.xml`, not to a CI-only step, so the gate runs on every local build
before compilation begins. The parameters that make it a gate rather than a report:

| Setting | Value | Effect |
|---|---|---|
| `failOnViolation` | `true` | A violation fails the build |
| `violationSeverity` | `warning` | There is no tolerated warning tier below the failure threshold |
| `includeTestSourceDirectory` | `true` | The test tree is held to the same obligation as the main tree |
| `JavadocType` / `allowMissingParamTags` | `false` | Every type parameter and every record component must be documented |
| `JavadocMethod` / `allowMissingParamTags` | `false` | Enforces the **Parameters** element |
| `JavadocMethod` / `allowMissingReturnTag` | `false` | Enforces the **Return values** element |
| `JavadocMethod` / `validateThrows` | `true` | Enforces the **Exceptions or errors** element; it defaults to `false`, so it must be set explicitly |

`JavadocPackage` runs at `Checker` level and inspects the file system for a
`package-info.java`, which is why the absence of one is a build failure rather than a
missing audit entry.

This module deliberately does **not** re-declare the Checkstyle plugin in its own
`pom.xml`, and holds no module-local `checkstyle.xml` or `suppressions.xml`.
Trade-offs: single-sourcing the configuration in the parent means that reading
this module's POM alone does not tell you which rules apply, and that cost is
accepted because re-declaring the plugin would open three ways to diverge at once --
re-pinning the Checkstyle version so this module lints under a different ruleset than
its siblings, duplicating the config location so the two copies can drift, or
relaxing the setting that makes a violation fail.

### The suppression charter

`config/checkstyle/suppressions.xml` currently holds exactly two entries, and both
are chartered: one for generated sources under `target/generated-sources/`, which
have no author to document them, and one for the fixed-width record fixtures under
`src/test/resources/fixtures/`, which are data files rather than code.

**Suppressing anything under `src/main/java/**` is prohibited, and suppressing
`mapper/**` above all.** The reason is concrete rather than procedural: `CardMapper`
is the single place where the masking and suppression decisions of this module are
made, so its inline rationale is the only written record of why a card number is
narrowed to four digits, why the verification value has no serialised form, and why
one baseline field is renamed. Suppressing that file would delete the audit trail
for precisely the decisions most likely to be questioned. No such suppression exists
today, and none may be added.


## References

Every path below was read while writing this document. Paths marked
**REFERENCE-ONLY** are read as specification and are never modified by this module.

Baseline programs, REFERENCE-ONLY:

- `app/cbl/COCRDLIC.cbl` -- 1459 lines; card list, the canonical CICS browse
- `app/cbl/COCRDSLC.cbl` -- 887 lines; card detail read
- `app/cbl/COCRDUPC.cbl` -- 1560 lines; card update
- `app/cbl/CBACT02C.cbl` -- 178 lines; sequential card-file reader

Baseline contracts, REFERENCE-ONLY:

- `app/cpy/CVACT02Y.cpy` -- `01 CARD-RECORD`, 150 bytes; the storage contract
- `app/cpy/CVCRD01Y.cpy` -- the `X(75)` message-line contract and the X-over-9
  `REDEFINES` pairs for both identifiers
- `app/cpy-bms/COCRDLI.CPY`, `app/cpy-bms/COCRDSL.CPY`, `app/cpy-bms/COCRDUP.CPY` --
  the three symbolic maps, whose field shapes are the DTO field-shape source. The
  extension on disk is **uppercase** `.CPY`, which matters to any case-sensitive
  path reference
- `app/csd/CARDDEMO.CSD` -- the CICS resource definitions: `CARDAIX` at `:13-24`,
  `CARDDAT` at `:25-36`, the three program descriptions at `:204`, `:220` and `:228`,
  and the transactions `CCDL` at `:347-356`, `CCLI` at `:357-366` and `CCUP` at
  `:367-377`

Repository documents, REFERENCE-ONLY:

- `tests/README.md` -- the house style this document follows, the no-CICS-runtime
  limitation at `:83-85`, the fixtures-without-goldens precedent at `:143-146`, the
  return-code aggregation at `:232-234`, and the `# WHAT:` / `# WHY :` idiom at
  `:301-304`
- `README.md` (repository root) -- the transaction inventory; the three card rows are
  at `:295-297`

Migration artifacts this module depends on:

- `docs/CODE_DOCUMENTATION_STANDARD.md` -- the written documentation convention and
  the source of the rule's full text
- `docs/architecture/data-model-and-schema-mapping.md` -- the register of the
  `expiration_date` rename and of the dropped `FILLER`
- `config/checkstyle/checkstyle.xml` and `config/checkstyle/suppressions.xml` -- the
  documentation gate and its chartered suppressions
- `services/pom.xml` -- the aggregator that binds the gate to `validate` and manages
  every dependency version
- `services/common-lib` -- the shared kernel supplying `PageResponse`, `ApiError`,
  `GlobalExceptionHandler`, `JwtRoleConverter` and the architecture rules
- `data-migration/sql/V0__schemas_and_roles.sql` -- the bootstrap that creates the
  `card` schema, its role and its grants
