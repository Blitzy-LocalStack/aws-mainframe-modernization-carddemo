# ADR-002: Compute Platform

> **Purpose.** Record the compute model for online services, batch work, and
> small orchestration helpers.
>
> **Source of truth.** AAP decision D2 and the service/batch lifecycle described
> in `docs/architecture/service-catalog.md`.

- **Status:** Accepted
- **Decision:** Run the eight service deployables on ECS Fargate, run batch as
  Step-Functions-invoked Fargate tasks, and reserve Lambda for bounded glue.

## Context

Online APIs need warm JVMs and JDBC pools. Batch steps may exceed a function
runtime limit and must expose a terminal process result to the orchestrator.

## Options Considered

1. **ECS Fargate — selected.** Fits long-running APIs and one-shot JVM tasks
   without host management.
2. **Lambda for all workloads.** Rejected for long-running batch and warm JDBC
   pool behavior.
3. **EC2-hosted containers.** Rejected because instance sizing, patching, and
   capacity management add an operational layer the workload does not require.

## Rationale

Assumptions: stateless request handling allows service tasks to scale
horizontally. Batch containers terminate when the job finishes, allowing
`ecs:runTask.sync` to use the task exit state as the step result.

## Cost Implications

Fargate charges for requested task CPU and memory while tasks run. It avoids
idle host fleets and their administration. Lambda remains appropriate only for
short glue paths where per-invocation billing is advantageous.

## Trade-offs and Risks

Trade-offs: Fargate has per-task startup latency and requires deliberate task
sizing. The infrastructure bounds CPU, memory, autoscaling, timeouts, and
deployment rollback; production images are digest-pinned.
