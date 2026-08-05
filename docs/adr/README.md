# Architecture Decision Records

> **Purpose.** Index the nine frozen architecture decisions that govern the
> CardDemo migration.
>
> **Source of truth.** The Agent Action Plan (AAP) §0.1.2 defines the decision
> set and the accepted option for each record. These files explain those choices;
> they do not reopen them.

Every record contains the decision, alternatives considered, rationale, AWS
cost shape, and risks. A later implementation change that conflicts with a
record requires a superseding ADR rather than a silent edit to the decision.

Assumptions: "cost" means the billing dimensions and operational burden that
distinguish the options. No unverified currency estimate is presented.

| ADR | Decision |
|---|---|
| [ADR-001](ADR-001-language-and-runtime.md) | Java 21 and Spring Boot rewrite |
| [ADR-002](ADR-002-compute-platform.md) | ECS Fargate for services and batch tasks |
| [ADR-003](ADR-003-datastore-targets.md) | Aurora PostgreSQL plus versioned S3 |
| [ADR-004](ADR-004-messaging.md) | SQS request/reply messaging |
| [ADR-005](ADR-005-batch-orchestration.md) | EventBridge Scheduler, Step Functions, and Spring Batch |
| [ADR-006](ADR-006-api-and-ui.md) | REST/OpenAPI and a React SPA |
| [ADR-007](ADR-007-service-boundaries.md) | Eight bounded contexts with schema ownership |
| [ADR-008](ADR-008-security-and-identity.md) | Cognito, isolated networking, KMS, and least privilege |
| [ADR-009](ADR-009-iac-tool.md) | Terraform |
