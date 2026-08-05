# COBOL-to-Service Traceability

> **Purpose.** Map every in-scope COBOL program to its target bounded context
> and maintain the single register of intentional behavioral divergences.
>
> **Source of truth.** The 44 program files under `app/cbl/**` and the three
> extension `cbl/**` trees are immutable behavioral references. The AAP service
> catalogue defines the target ownership.

This document never uses "fixed", "corrected", or "patched" to describe
`app/**`. The baseline remains byte-identical; a target difference is recorded
here with its reason and verification obligation.

Assumptions: the 44 programs below are the migration scope. Test programs are
not production programs and are not added to this count.

## Program Ownership Matrix

| Baseline program | Target owner | Target responsibility |
|---|---|---|
| `app/cbl/COSGN00C.cbl` | auth-service | Sign-on handoff to Cognito and admin/user navigation decision |
| `app/cbl/COUSR00C.cbl` | auth-service | Keyset-paged user list |
| `app/cbl/COUSR01C.cbl` | auth-service | User creation validation |
| `app/cbl/COUSR02C.cbl` | auth-service | User update validation |
| `app/cbl/COUSR03C.cbl` | auth-service | User deletion |
| `app/cbl/COACTVWC.cbl` | account-service | Account detail view |
| `app/cbl/COACTUPC.cbl` | account-service | Account update and optimistic concurrency |
| `app/cbl/CBACT01C.cbl` | account-service | Account record access |
| `app/cbl/CBACT03C.cbl` | account-service | Card/account cross-reference access |
| `app/cbl/CBCUS01C.cbl` | account-service | Customer record access |
| `app/app-vsam-mq/cbl/COACCT01.cbl` | account-service | Inquiry request/reply adapter |
| `app/cbl/COCRDLIC.cbl` | card-service | Card list and keyset paging |
| `app/cbl/COCRDSLC.cbl` | card-service | Card detail |
| `app/cbl/COCRDUPC.cbl` | card-service | Card update |
| `app/cbl/CBACT02C.cbl` | card-service | Card record access |
| `app/cbl/COTRN00C.cbl` | transaction-service | Transaction list and keyset paging |
| `app/cbl/COTRN01C.cbl` | transaction-service | Transaction detail |
| `app/cbl/COTRN02C.cbl` | transaction-service | Transaction creation |
| `app/cbl/COBIL00C.cbl` | transaction-service | Bill payment |
| `app/app-transaction-type-db2/cbl/COTRTLIC.cbl` | reference-service | Transaction-type list |
| `app/app-transaction-type-db2/cbl/COTRTUPC.cbl` | reference-service | Transaction-type maintenance |
| `app/app-transaction-type-db2/cbl/COBTUPDT.cbl` | reference-service | Batch reference update |
| `app/app-vsam-mq/cbl/CODATE01.cbl` | reference-service | Date-conversion request/reply adapter |
| `app/cbl/CSUTLDTC.cbl` | common-lib / reference-service | Date editing and conversion rules |
| `app/cbl/CBTRN01C.cbl` | batch-service | Daily-transaction preflight |
| `app/cbl/CBTRN02C.cbl` | batch-service | Posting, rejects, balances, and account update |
| `app/cbl/CBACT04C.cbl` | batch-service | Interest calculation |
| `app/cbl/CBEXPORT.cbl` | batch-service | Packed-decimal export |
| `app/cbl/CBIMPORT.cbl` | batch-service | Packed-decimal import |
| `app/cbl/CBSTM03A.CBL` | reporting-service | Statement grouping and generation |
| `app/cbl/CBSTM03B.CBL` | reporting-service | Statement rendering |
| `app/cbl/CBTRN03C.cbl` | reporting-service | Transaction report generation |
| `app/cbl/CORPT00C.cbl` | reporting-service | On-demand report request |
| `app/app-authorization-ims-db2-mq/cbl/COPAUS0C.cbl` | authorization-service | Pending-authorization summary |
| `app/app-authorization-ims-db2-mq/cbl/COPAUS1C.cbl` | authorization-service | Pending-authorization detail |
| `app/app-authorization-ims-db2-mq/cbl/COPAUS2C.cbl` | authorization-service | Fraud marking |
| `app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl` | authorization-service | Authorization queue consumer and reply |
| `app/app-authorization-ims-db2-mq/cbl/CBPAUP0C.cbl` | authorization-service | Pending-authorization purge |
| `app/app-authorization-ims-db2-mq/cbl/PAUDBLOD.CBL` | authorization-service | Segment load |
| `app/app-authorization-ims-db2-mq/cbl/PAUDBUNL.CBL` | authorization-service | Segment unload |
| `app/app-authorization-ims-db2-mq/cbl/DBUNLDGS.CBL` | authorization-service | GSAM unload |
| `app/cbl/COMEN01C.cbl` | UI shell | Main-menu route and option contract |
| `app/cbl/COADM01C.cbl` | UI shell | Administrator-menu route and option contract |
| `app/cbl/COBSWAIT.cbl` | Retired mechanism | Wait-step utility replaced by orchestrator state transitions |

## Intentional Behavioral Divergences

| ID | Baseline behavior | Target behavior | Reason and verification |
|---|---|---|---|
| **D-1** | `CBEXPORT` and `CBIMPORT` name a record key that is not in the FD record, so the immutable pair is known-unsupported by the open-source compiler. | Export/import use the actual sequence key and round-trip the 500-byte packed record. | The target must be runnable. Codec round-trip tests and the divergence-aware parity report verify the target without changing the baseline. |
| **D-2** | `CBSTM03A` has two independent unchecked tables: one card supports 512 transactions before the 513th overruns; 51 distinct cards fit before the 52nd overruns. | Statement processing uses dynamically sized collections with no fixed arity. | The two thresholds must never be represented by one aggregate transaction limit. Tests cover grouping and output rather than reproducing either overrun. |
| **D-3** | `CBACT04C` omits the final account flush after end-of-file. | The final accumulated account is flushed using the same calculation and transaction rules as every prior control break. | The target preserves the intended per-account result. Interest parity tests include a final-account case and the exact multiply-then-divide formula. |
| **D-4** | `USRSEC` stores and compares an eight-character plaintext password. | Cognito owns credential storage/comparison; `auth.users` stores only identity linkage and role metadata. | This is the explicit security divergence. Schema tests prove no password column exists; sign-on tests use stable error identifiers without provider exception text. |
| **D-5** | The authorization reply is published outside the database commit, leaving a committed-decision/lost-reply window. | A transactional outbox is committed with the decision and publishes afterward. | Every committed decision must eventually have a reply. Repository and publisher tests verify idempotent delivery. |
| **D-6** | Authorization data spans IMS and Db2 and requires distributed transaction coordination. | Summary, detail, fraud, and outbox data share one PostgreSQL schema and one local transaction. | Co-location removes the need for two-phase commit without exposing a partial state. Integration tests verify atomic rollback. |

## Presentation and Platform Deviations

These changes preserve behavior but not the retired platform mechanism:

- CICS `XCTL` becomes client-side routing; `LINK` becomes an in-process or
  authenticated in-network call.
- The fixed 24-by-80 grid becomes responsive Ant Design layouts while field
  order, constraints, keyboard actions, and message text remain authoritative.
- VSAM browse cursors become authenticated, context-bound keyset cursor tokens.
- JCL step gating becomes Step Functions choices with JCL skip predicates
  inverted into run predicates.
- Generation datasets become ten versioned S3 prefix families with bounded
  noncurrent retention.

## Verification Contract

The immutable COBOL suite remains the parity oracle and its documented aggregate
return code **4 is green** because known unsupported programs and expected soft
rejects are warnings. Target validation adds Maven, UI, Python, database, and
Terraform checks; it does not reinterpret a warn-level oracle result as failure.
