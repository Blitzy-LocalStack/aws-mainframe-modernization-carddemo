# ADR-003: Datastore Targets

> **Purpose.** Record the target stores for record data and generation datasets.
>
> **Source of truth.** AAP decision D3, the copybooks under `app/cpy/**`, and
> `docs/architecture/data-model-and-schema-mapping.md`.

- **Status:** Accepted
- **Decision:** Store record data in Aurora PostgreSQL Serverless v2 and
  generation-style artifacts in versioned Amazon S3.

## Context

The baseline has keyed records, secondary access paths, referential constraints,
and a posting unit of work that updates transaction, balance, and account state
atomically. Batch artifacts also require retained generations.

## Options Considered

1. **Aurora PostgreSQL plus S3 — selected.**
2. **Key-value storage for all records.** Rejected because referential integrity,
   cross-record atomic posting, and ordered reporting queries are first-class
   requirements.
3. **Self-managed PostgreSQL on compute instances.** Rejected because backups,
   failover, patching, and capacity management would move into the application
   team's operating scope.

## Rationale

Refactoring Rationale: relational tables preserve exact keys, constraints,
secondary indexes, and ACID posting. Versioned S3 prefixes preserve generation
semantics without treating immutable batch artifacts as relational rows.

## Cost Implications

Aurora cost follows provisioned serverless capacity, storage, I/O, and backup
retention; development may scale to zero while production retains warm
capacity. S3 cost follows stored versions, requests, encryption, and audit
events, bounded by lifecycle policies.

## Trade-offs and Risks

Trade-offs: one cluster hosts schema-per-service boundaries, so IAM/database
roles and grants must enforce ownership. The batch role receives only the
cross-schema writes required to preserve the atomic posting unit.
