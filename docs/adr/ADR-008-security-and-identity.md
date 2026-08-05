# ADR-008: Security and Identity

> **Purpose.** Record the target identity, network, encryption, and authorization
> posture.
>
> **Source of truth.** AAP decision D8 and
> `docs/architecture/security-and-identity.md`.

- **Status:** Accepted
- **Decision:** Use Cognito groups and signed tokens for user identity, a
  three-AZ tiered VPC, least-privilege IAM/database roles, customer-managed KMS
  keys, Secrets Manager, and TLS with verified peers.

## Context

The baseline stores an eight-character plaintext password and carries user type
inside conversational state. The target must not reproduce either trust model.

## Options Considered

1. **Managed identity and isolated AWS controls — selected.**
2. **Port the USRSEC credential store.** Rejected because it preserves plaintext
   comparison and application-owned password lifecycle.
3. **Public service endpoints protected only by tokens.** Rejected because
   network isolation is an independent blast-radius control.

## Rationale

Assumptions: `auth-service` receives the plaintext credential transiently over
TLS and forwards it to Cognito; it does not persist, compare, log, trace, or
return that value. Group names are a single cross-language contract validated
at startup.

## Cost Implications

The posture adds NAT/endpoints, KMS requests and keys, secret versions,
CloudTrail data events, Cognito usage, TLS certificates, and security logs.
Those managed controls replace self-hosted identity, key management, and
network-security operations.

## Trade-offs and Risks

Trade-offs: customer-managed keys and private endpoints increase policy
complexity. Exact ARN conditions, service/encryption-context constraints,
non-overridable invariants, and static policy checks keep that complexity
reviewable. Java strings cannot be erased immediately, so credential safety
depends on avoiding copies and retaining no reference.
