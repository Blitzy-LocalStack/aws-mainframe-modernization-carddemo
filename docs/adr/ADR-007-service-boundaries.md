# ADR-007: Service Boundaries

> **Purpose.** Record the bounded-context decomposition.
>
> **Source of truth.** AAP decision D7 and
> `docs/architecture/service-catalog.md`.

- **Status:** Accepted
- **Decision:** Use eight deployable bounded contexts: auth, account, card,
  transaction, reference, batch, authorization, and reporting.

## Context

The candidate seams contain two transport-specific extensions whose data is
owned elsewhere. Making every extension a service would split table ownership
and create cross-service domain coupling.

## Options Considered

1. **Eight bounded contexts — selected.** Account inquiry belongs to account;
   transaction-type maintenance belongs to reference.
2. **One service per candidate list item.** Rejected because transport is not a
   data-ownership boundary.
3. **One migrated monolith.** Rejected because it preserves the CICS region's
   deployment and ownership coupling.

## Rationale

Refactoring Rationale: each schema has one owning service, shared technical
contracts live in `common-lib`, and reporting reads masked security-barrier
views through a SELECT-only role.

## Cost Implications

More services create more task definitions, logs, health checks, and deployment
events. They also permit independent scaling and failure isolation. Folding the
two transport extensions into their owners avoids two unnecessary continuously
running service footprints.

## Trade-offs and Risks

Trade-offs: distributed boundaries require explicit API and messaging
contracts. Cross-service domain imports are prohibited by architecture tests;
the one cross-schema batch transaction is a documented parity exception to
database-per-service purity.
