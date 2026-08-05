# =============================================================================
# infra/modules/secrets/outputs.tf
# -----------------------------------------------------------------------------
# Purpose:
#   The COMPLETE public contract of the `secrets` module. Everything a caller is
#   able to learn about the credentials created in
#   infra/modules/secrets/main.tf, it learns from the three outputs below.
#
#   Every one of them is an IDENTIFIER -- a secret ARN, a secret name, or a
#   Lambda identifier. Not one is, or is derived from, a credential. A consumer that needs an
#   actual credential reads it from Secrets Manager at run time, using the ARN or
#   the name published here and holding an IAM grant scoped to that entry; the
#   value itself never travels through a Terraform output, never appears in a
#   plan, and never appears in this repository. That is the whole shape of this
#   file, and the rest of this header explains why it is not enforced for us.
#
# The invariant, and who enforces it:
#   NEVER publish `ephemeral.random_password.*.result`, never publish a
#   Secrets Manager secret value, and never publish a
#   value derived from either -- not in clear text, not marked `sensitive`, not
#   nested inside a map, not base64-encoded, and not commented out for a future
#   reader to uncomment.
#
#   Assumptions: Terraform does NOT hold this line for us, and the belief that it
#   does is the thing most likely to erode the file. Terraform's guard --
#   "Output refers to sensitive values ... Terraform requires that any root module
#   output containing sensitive data be explicitly marked as sensitive" -- fires
#   only in a ROOT module. This directory is a MODULE. Measured against the
#   pinned CLI rather than assumed: a child-module output whose value is
#   `random_password.x.result`, with no `sensitive` argument at all, passes
#   `terraform validate` AND `terraform plan` without a word of complaint, and the
#   value is then carried into the calling root's state. So no gate in this
#   repository would report a credential published from here: not the CLI, not
#   `infra/.tflint.hcl` (which checks that a `description` is PRESENT, never what
#   a value contains), and not `infra/.terraform-docs.yml` (which compares a
#   generated table against the HCL beside it). The prohibition above is
#   therefore a review obligation with zero machine backing, which is exactly why
#   it is stated here in full rather than left implicit in the absence of a
#   value-bearing output.
#
# Contract stability -- these output NAMES are a one-way contract:
#   infra/envs/dev/main.tf and infra/envs/prod/main.tf reference them by name,
#   and through those roots so does infra/modules/ecs-service (its
#   `secret_sources` input, and the execution-role
#   statement scoped to those ARNs). Nothing in THIS module depends on any of
#   those files, so nothing here breaks when they change -- the dependency runs
#   one way only. Renaming an output below therefore breaks every caller while
#   leaving this module valid in isolation, which is the failure mode a rename
#   is least likely to be tested for. Add outputs freely; rename them only by
#   changing every caller in the same commit.
#
# Parameters:
#   None. An outputs file declares no input. The module's inputs, their
#   types, defaults and `validation` blocks are declared in
#   infra/modules/secrets/variables.tf -- the mirror image of the cross-reference
#   that file carries under its own "Return values".
#
# Return values -- this file IS the return-value contract, in full:
#   service_credential_secrets ...... role name -> { arn, name }, one per role
#   rotation_lambda_arn ............. module-owned rotation function ARN
#   rotation_lambda_name ............ module-owned rotation function name
#
#   Deliberately absent, so that a later editor does not add either back
#   believing it was overlooked:
#     - Any output carrying a credential VALUE, for the reason given above.
#     - The version identifier of either secret version resource. See the note
#       at the foot of this file; it is omitted for a stronger reason than
#       having no consumer.
#
# Errors / Exceptions:
#   No output below declares a `precondition`, so none of them can fail a plan on
#   its own -- an output is an expression over resources this module already
#   created, and every failure worth naming belongs to main.tf (an unacceptable
#   generated value, a name still reserved by an earlier deletion, a rotation
#   function that cannot reach the cluster) or to the `validation` blocks in
#   variables.tf. One ordering hazard IS this file's to close, and `depends_on`
#   below closes it by waiting for every initial service-secret version.
#
# WHY (the decisions that govern the whole file, recorded once here):
#   - Assumptions: the module publishes HANDLES because the run-time contract is
#     a handle. infra/modules/ecs-service resolves a secret through the container
#     secrets mechanism from an ARN; data-migration's config helper resolves one
#     with `get_secret_value(SecretId=<name>)` from a name it derives
#     independently as `<prefix>/<environment>/aurora/<role>`. Neither reads a
#     Terraform output to obtain a value, and publishing one would not help
#     either of them -- it would only add a second place the credential exists.
#   - Trade-offs: NOT ONE output below is marked `sensitive`, and that uniformity
#     is a measured decision rather than an oversight. The full argument, with
#     the two failure modes that were reproduced against the pinned CLI, is on
#     the first output; it is placed there rather than here because that is the
#     first line a reader will question.
#   - Alternatives Considered: publishing fewer outputs -- just the two ARNs --
#     and letting each caller compose the names itself from the prefix and the
#     environment. Rejected. The composition rule lives in main.tf's `locals`,
#     and a caller that rebuilt a name would be a second copy of it that nothing
#     compares against; the ETL helper is already an independent derivation of
#     the same rule, and one independent copy is the most this design should
#     carry. Publishing the name means the calling root can pass a name it was
#     GIVEN rather than one it guessed.
# =============================================================================

# -----------------------------------------------------------------------------
# Per-service database role credentials
# -----------------------------------------------------------------------------

output "service_credential_secrets" {
  description = <<-EOT
    The Secrets Manager entry created for each per-service database role, as a
    map keyed by role name -- `carddemo_auth`, `carddemo_account` and the rest
    of the roles named in `service_credential_names` -- whose value carries
    that entry's base `arn` for IAM, its created `name` for by-name reads, and
    distinct `username_reference` / `password_reference` values in ECS's
    `<base-arn>:<json-key>::` syntax. One entry exists per element of that input,
    so the map is empty only if the input is. The calling root projects the
    `arn` fields into infra/modules/ecs-service's `secret_sources` input, keyed
    by the container environment-variable name each service expects, and scopes
    one `secretsmanager:GetSecretValue` statement per task role to the single
    ARN that role is entitled to read. The `name` fields are what a by-name
    reader passes as `SecretId`, matching the role name character for
    character.

    These entries are not inert. Each value is APPLIED to the matching
    PostgreSQL role by the schema-bootstrap step, which reads the entry, passes
    the credential as a bound parameter on a session setting, and runs
    data-migration/sql/V0__schemas_and_roles.sql -- which issues the ALTER ROLE
    inside the same transaction that creates the roles and refuses to commit
    while any role still lacks a credential. A caller therefore has two
    obligations, not one: grant the bootstrap identity read access to every
    entry in this map, and grant each task role read access to its own entry
    alone.
  EOT

  # WHY : Trade-offs: a MAP KEYED BY ROLE NAME rather than a list of objects, and
  #       the distinction is load-bearing rather than cosmetic. A caller looks a
  #       role up by name -- `[each.key].arn` -- so it never depends on position;
  #       with a list, every index would shift the moment a role was added to or
  #       removed from `service_credential_names`, and a caller that had wired
  #       `[2]` would silently start reading a different role's secret. Keying by
  #       name also makes the map directly usable as a `for_each`, which is what
  #       one-grant-per-role needs, and it mirrors main.tf's own choice to key
  #       `local.service_secret_names` on the role name for the same reason.
  # WHY : Trade-offs: ONE map of objects rather than two parallel maps of
  #       strings, one of ARNs and one of names. Two maps could disagree in
  #       length or in keys, and a caller would have to zip them by key to use
  #       both; a single object per role cannot come apart. The accepted cost is
  #       that a caller wanting only ARNs writes a one-line projection
  #       (`{ for role, secret in ... : role => secret.arn }`) instead of
  #       receiving one ready-made -- a projection cheap enough that publishing a
  #       redundant second output to save it would add a member to a one-way
  #       contract for no gain.
  # WHY : Assumptions: built with a `for` expression over the `for_each`
  #       resource, so the inventory is read out of what was actually created.
  #       The eight role names are NOT restated here: they are fixed by the
  #       bootstrap SQL, arrive as `var.service_credential_names` and are checked
  #       there against a closed list, so a literal list in this file would be a
  #       fourth copy of an inventory that already has as many as it should. It
  #       would also be the copy that goes stale silently, because an output is
  #       not validated -- it would simply publish a role the module never
  #       created, or omit one it did.
  # WHY : Assumptions: iterated over `aws_secretsmanager_secret.service` rather
  #       than over `var.service_credential_names` directly. Both are keyed
  #       identically, so the resulting keys are the same either way, but only
  #       the resource carries the `arn` and the created `name` -- and iterating
  #       the resource means the map cannot describe an entry that does not exist.
  value = {
    for role_name, secret in aws_secretsmanager_secret.service :
    role_name => {
      arn  = secret.arn
      name = secret.name

      # WHY : Assumptions: an ECS `valueFrom` that must inject ONE field of a JSON
      #       secret takes the `<base-arn>:<json-key>::` form, while an IAM
      #       Resource takes the base ARN with no suffix. Publishing both suffixed
      #       forms here keeps that syntax owned by the module that knows the
      #       document's shape.
      #       Alternatives Considered: letting each caller append the suffix.
      #       Rejected because two callers appended it two ways in review, and a
      #       malformed selector surfaces only when a task fails to start with an
      #       error naming the secret rather than the syntax.
      username_reference = "${secret.arn}:username::"
      password_reference = "${secret.arn}:password::"
    }
  }

  # Assumptions: the same ordering guarantee as the master credential outputs above
  #       holds here, applied
  #       to the whole family at once. `depends_on` accepts a resource address
  #       but not an indexed one, so every entry in this map waits for every
  #       service version rather than only for its own. That is a wider
  #       constraint than each entry strictly needs and it is accepted for the
  #       same reason main.tf accepts it on the service rotation resource: the
  #       alternative available in the language is no ordering guarantee at all.
  depends_on = [aws_secretsmanager_secret_version.service]
}

output "rotation_lambda_arn" {
  description = "ARN of the module-owned Lambda that applies and rotates every service-role credential through RDS Data API."
  value       = aws_lambda_function.rotation.arn
}

output "rotation_lambda_name" {
  description = "Name of the module-owned service-role credential rotation Lambda, for alarms and operator inspection."
  value       = aws_lambda_function.rotation.function_name
}

output "rotation_role_arn" {
  description = "ARN of the module-owned rotation Lambda role, consumed by the Secrets Manager KMS key policy so rotation can decrypt and replace the exact managed credential documents."
  value       = aws_iam_role.rotation.arn
}

output "rotation_log_group_name" {
  description = "Name of the rotation Lambda CloudWatch log group, consumed by operator diagnostics and root-level observability wiring."
  value       = aws_cloudwatch_log_group.rotation.name
}

output "rotation_log_group_arn" {
  description = "ARN of the rotation Lambda CloudWatch log group, consumed by the exact encryption-context KMS policy assembled in the environment root."
  value       = aws_cloudwatch_log_group.rotation.arn
}

# =============================================================================
# The version identifier is deliberately NOT published, and the reason is
# stronger than "no caller needs it".
#
#   Assumptions: `aws_secretsmanager_secret_version.*.version_id` records the
#   version THIS configuration created, and main.tf is built so that that
#   version stops being the current one. Its write-only initial document hands
#   authority for subsequent values to the module-owned rotation function; the
#   first rotation therefore moves the AWSCURRENT label to a version this module
#   has never seen. An output named for the current version would then be
#   confidently wrong -- and wrong precisely when someone reached for it, which is
#   after a rotation. Nothing consumes it either: the container secrets
#   mechanism resolves from the ARN and the ETL helper resolves by name. A stale identifier that
#   nobody asked for is worth less than the absence of one, so it is absent, and
#   the absence is recorded here so it reads as a decision rather than a gap.
# =============================================================================

output "service_tls_secrets" {
  description = <<-EOT
    Handles for the scalar certificate and private-key secrets used by the
    services' internal HTTPS listeners. Each object publishes the base `arn`,
    the created `name`, and a `value_reference`. Because each secret stores one
    scalar PEM value, `value_reference` intentionally equals `arn`: the same
    base ARN is valid for ECS injection and for the task execution role's IAM
    Resource. Empty when the caller left service_tls_certificate and
    service_tls_private_key null, which is how a root that issues and owns its own
    pair says it does not want this module's entries -- a consumer therefore reads
    this map through `lookup` or `try` rather than indexing it unconditionally.
  EOT

  # WHY : Refactoring Rationale: publishing these handles closes the gap between
  #       mandatory SERVER_SSL_CERTIFICATE properties and the infrastructure
  #       graph. A root can inject the exact entries this module created instead
  #       of relying on manually populated environment variables.
  # WHY : Assumptions: presence is decided from the RESOURCE count rather than from
  #       local.create_service_tls_secrets, even though the two are always equal. The
  #       local is derived from two `sensitive` inputs, and a conditional that reads a
  #       sensitive value makes the whole result sensitive -- which would force this
  #       output to be marked sensitive and would redact the very ARNs a reviewer of a
  #       least-privilege grant has to see. The resource count carries the same fact
  #       with none of the sensitivity.
  value = length(aws_secretsmanager_secret.service_tls_certificate) > 0 ? {
    certificate = {
      arn             = aws_secretsmanager_secret.service_tls_certificate[0].arn
      name            = aws_secretsmanager_secret.service_tls_certificate[0].name
      value_reference = aws_secretsmanager_secret.service_tls_certificate[0].arn
    }
    private_key = {
      arn             = aws_secretsmanager_secret.service_tls_private_key[0].arn
      name            = aws_secretsmanager_secret.service_tls_private_key[0].name
      value_reference = aws_secretsmanager_secret.service_tls_private_key[0].arn
    }
  } : {}

  # WHY : Assumptions: secret shells are created before their versions. This
  #       edge makes the published scalar references wait until both PEM values
  #       have been written, so a dependent ECS service cannot start against an
  #       empty entry during the same apply.
  depends_on = [
    aws_secretsmanager_secret_version.service_tls_certificate,
    aws_secretsmanager_secret_version.service_tls_private_key,
  ]
}
