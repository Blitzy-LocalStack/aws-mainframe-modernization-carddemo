# ADR-001: Language and Runtime

> **Purpose.** Record the disposition of the COBOL application code.
>
> **Source of truth.** AAP decision D1 and the immutable programs under
> `app/**`.

- **Status:** Accepted
- **Decision:** Rewrite migrated behavior idiomatically in Java 21 with Spring
  Boot 4.1.0. GnuCOBOL remains a parity-test tool, not a production runtime.

## Context

The online programs depend on CICS command-level APIs and the batch programs
depend on z/OS file and job conventions. The target must run natively on Linux
and AWS with no runtime callback to CICS, VSAM, Db2, IMS, or IBM MQ.

## Options Considered

1. **Idiomatic Java rewrite — selected.** It removes the CICS runtime
   dependency while preserving copybook and business-rule contracts.
2. **Transpile or run COBOL on Linux.** Rejected because it preserves the
   maintenance model and does not remove the CICS integration problem.
3. **Managed mainframe replatforming.** Rejected because the selected AWS
   managed path is unavailable to a new customer and does not satisfy the
   long-term modularity objective.

## Rationale

Refactoring Rationale: Java provides exact `BigDecimal` money, mature Spring
data/batch/security integration, and independently deployable service modules.
The rewrite changes structure, not business meaning; the COBOL suite remains the
behavioral oracle.

## Cost Implications

Java increases one-time implementation and parity-test effort. It reduces the
specialist-runtime and CICS operations burden and runs on the same Fargate
billing model as the rest of the target services.

## Trade-offs and Risks

Trade-offs: a rewrite can introduce semantic drift. The mitigation is
field-by-field copybook mapping, paragraph-to-method traceability, exact
fixed-point arithmetic, and golden-master comparison. The reference COBOL is
never edited to make the target easier to implement.
