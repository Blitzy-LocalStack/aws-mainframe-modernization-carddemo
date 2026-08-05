# ADR-006: API and User Interface

> **Purpose.** Record the online interface strategy replacing CICS/BMS screens.
>
> **Source of truth.** AAP decision D6, the BMS maps under `app/bms/**`, and
> `docs/architecture/design-token-reference.md`.

- **Status:** Accepted
- **Decision:** Publish REST/JSON APIs described by OpenAPI 3.1 and implement the
  21 online screens as a React 19 TypeScript SPA using Ant Design 6.

## Context

The browser must replace fixed 3270 presentation while preserving field widths,
messages, PF-key behavior, validation state, and stateless navigation.

## Options Considered

1. **REST plus React SPA — selected.**
2. **gRPC.** Rejected because browser use would require an additional proxy and
   would not improve the user-facing contract.
3. **Recreate the 24-by-80 character grid.** Rejected because absolute
   positioning conflicts with responsive browser delivery; grouping, order, and
   keyboard behavior are the preserved contracts.

## Rationale

Assumptions: money remains a JSON string, opaque identifiers replace PANs in
browser paths, and user-visible strings remain verbatim. Ant Design supplies
the form, table, descriptions, alert, and layout primitives required by the
screen inventory.

## Cost Implications

Static SPA hosting uses S3 and CloudFront request, transfer, logging, and
invalidation dimensions. REST avoids a browser protocol translation tier.

## Trade-offs and Risks

Trade-offs: the layout is intentionally modernized, so parity is behavioral and
semantic rather than character-cell positional. Component tests cover field
constraints, message text, and PF-key bindings.
