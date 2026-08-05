# ADR-009: Infrastructure-as-Code Tool

> **Purpose.** Record the infrastructure authoring and lifecycle tool.
>
> **Source of truth.** AAP decision D9 and the Terraform package under
> `infra/**`.

- **Status:** Accepted
- **Decision:** Use Terraform 1.15.x with the reviewed HashiCorp AWS 6.x and
  Random 3.x provider lines.

## Context

The target needs reusable modules, identical dev/prod topology, reviewable
plans, remote state protection, and deterministic teardown.

## Options Considered

1. **Terraform — selected.**
2. **CloudFormation.** Rejected because the required reusable multi-environment
   module composition and cross-provider tooling are less direct for this
   package.
3. **AWS CDK.** Rejected because synthesized output adds another generated layer
   between review and the deployed resource graph.

## Rationale

Refactoring Rationale: `plan` is a first-class review artifact, modules share
one source across dev and prod, and `destroy` supports the acceptance criterion.
Provider lock files and `-lockfile=readonly` make exact provider changes
review-visible even though `~> 6.56` admits later 6.x releases.

## Cost Implications

Terraform itself adds no AWS runtime resource. The protected state backend adds
S3 storage/versions, DynamoDB requests, KMS use, CloudTrail data events, and log
retention. These costs buy locking, encryption, recovery, and audit.

## Trade-offs and Risks

Trade-offs: state is sensitive infrastructure metadata and becomes an
operational asset. The bootstrap uses a dedicated CMK, bounded noncurrent
versions, TLS-only bucket policy, public-access blocks, object-level audit, and
is destroyed last.
