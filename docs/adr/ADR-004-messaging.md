# ADR-004: Messaging

> **Purpose.** Record the replacement for the IBM MQ request/reply flows.
>
> **Source of truth.** AAP decision D4 and
> `docs/architecture/messaging-contracts.md`.

- **Status:** Accepted
- **Decision:** Use Amazon SQS, with FIFO request/reply queues for authorization
  and standard queues for inquiry, plus dedicated dead-letter queues.

## Context

Authorization needs per-card ordering, duplicate suppression, correlation, and
reply routing. Inquiry does not require ordering. The CSV wire contracts remain
externally observable.

## Options Considered

1. **SQS — selected.**
2. **Amazon MQ for IBM MQ compatibility.**

This was one of the migration's two genuine close calls. Amazon MQ preserves
broker concepts more directly; SQS removes broker sizing, patching, and failover
while still satisfying the required semantics through explicit attributes,
FIFO grouping, deduplication, visibility timeout, and DLQs.

## Rationale

Assumptions: authorization uses an opaque HMAC-derived group/correlation token,
never the PAN. Poison messages cross a quarantine boundary and are replayed one
at a time under review so FIFO order is not silently violated.

## Cost Implications

SQS cost follows API requests, payload size, KMS use, and retained messages.
Amazon MQ would add continuously provisioned broker capacity and broker
operations. The selected design pays primarily for message traffic.

## Trade-offs and Risks

Trade-offs: SQS has no per-message time to live equivalent to the baseline
five-second expiry. Messages carry `expiresAt`; consumers drop and audit stale
messages, and reply retention exceeds the complete visibility/retry budget.
The transactional outbox closes the baseline's database-commit/reply-publish
window.
