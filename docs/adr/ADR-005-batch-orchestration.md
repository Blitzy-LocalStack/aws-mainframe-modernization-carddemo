# ADR-005: Batch Orchestration

> **Purpose.** Record the replacement for JCL scheduling, step gating, and
> restart behavior.
>
> **Source of truth.** AAP decision D5 and
> `docs/architecture/batch-orchestration.md`.

- **Status:** Accepted
- **Decision:** Use EventBridge Scheduler to start Step Functions workflows
  whose work states run Spring Batch jobs on ECS Fargate.

## Context

The nightly chain needs explicit dependencies, condition-code branching,
timeouts, retry/catch handling, restart, and generation-dataset outputs.

## Options Considered

1. **Step Functions plus Fargate — selected.**
2. **AWS Batch.**

This was the second genuine close call. AWS Batch supplies queues and compute
environments that benefit heterogeneous queued workloads. CardDemo has a fixed
dependency graph and already uses Fargate task definitions, so those extra
control planes would add no required behavior.

## Rationale

Refactoring Rationale: a state machine makes the JCL graph and inverted `COND=`
semantics reviewable. Each work state has a timeout, retry, and catch path;
Step Functions redrive plus `batch.batch_run` supplies restart and idempotency.

## Cost Implications

Step Functions charges for workflow transitions and integrations; Fargate
charges while each batch task runs. AWS Batch would add compute-environment and
queue management even when no nightly job is active.

## Trade-offs and Risks

Trade-offs: orchestration state and application job state exist in two systems.
The durable step ledger and deterministic job parameters prevent a redrive from
duplicating a completed financial step. Return code 4 remains the documented
green-with-warning tier.
