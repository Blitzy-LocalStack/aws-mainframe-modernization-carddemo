# =============================================================================
# infra/modules/secrets/outputs.tf
# -----------------------------------------------------------------------------
# Purpose:
#   The COMPLETE public contract of the `secrets` module. Everything a caller is
#   able to learn about the credentials created in
#   infra/modules/secrets/main.tf, it learns from the single output below.
#
#   Every member of it is an IDENTIFIER -- a secret ARN, a secret name, or an
#   ECS field selector built from one. Not one is, or is derived from, a
#   credential. A consumer that needs an
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
#   service_credential_secrets ...... role name -> { arn, name,
#                                     username_reference, password_reference },
#                                     one per role
#
#   Deliberately absent, so that a later editor does not add any of them back
#   believing it was overlooked:
#     - Any output carrying a credential VALUE, for the reason given above.
#     - The version identifier of the secret version resource. See the note
#       at the foot of this file; it is omitted for a stronger reason than
#       having no consumer.
#     - Any rotation-function identifier. This module creates no rotation
#       function; a schedule is attached only from an ARN the calling ROOT
#       supplies, so the root already holds every identifier it could publish and
#       an output here would only echo an input back.
#     - Any TLS certificate or private-key handle. Those entries, and the
#       secret-bearing inputs that fed them, were removed: a reusable module is
#       the wrong custodian for private-key material, and the calling root now
#       owns both the material it generates and the entries it writes it to.
#
# Errors / Exceptions:
#   The output below declares no `precondition`, so it cannot fail a plan on
#   its own -- an output is an expression over resources this module already
#   created, and every failure worth naming belongs to main.tf (an unacceptable
#   generated value, or a name still reserved by an earlier deletion) or to the
#   `validation` blocks in variables.tf. One ordering hazard IS this file's to
#   close, and `depends_on` below closes it by waiting for every initial
#   service-secret version.
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

    This module STORES each credential; it does not APPLY it. Binding a stored
    value to the matching PostgreSQL role -- the ALTER ROLE that lets the role
    authenticate with it -- belongs to whatever runs the schema bootstrap, and so
    does any later rotation. A caller therefore has three obligations, not one:
    grant each task role read access to its own entry alone, grant the
    bootstrapping identity read access to every entry in this map so it can bind
    the values it finds, and -- if the deployment wants rotation -- supply a
    rotation function through this module's `rotation_lambda_arn` input, because
    this module deliberately creates none.
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
  #       The fifteen role names are NOT restated here: they are fixed by the
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

  # WHY : Assumptions: an entry's ARN and name exist as soon as the secret shell
  #       is created, but a consumer that resolves the entry expects a value to be
  #       there, so this output waits for every initial version to be written.
  #       Trade-offs: `depends_on` accepts a resource address but not an indexed
  #       one, so every entry in this map waits for every service version rather
  #       than only for its own. That is a wider constraint than each entry
  #       strictly needs, and it is accepted because the alternative available in
  #       the language is no ordering guarantee at all.
  depends_on = [aws_secretsmanager_secret_version.service]
}
# -----------------------------------------------------------------------------
# The shared workload credential
# -----------------------------------------------------------------------------

# =============================================================================
# The version identifier is deliberately NOT published, and the reason is
# stronger than "no caller needs it".
#
#   Assumptions: `aws_secretsmanager_secret_version.service[*].version_id`
#   records the version THIS configuration created, and main.tf is built so that
#   that version stops being the current one wherever rotation is configured. Its
#   write-only initial document hands authority for subsequent values to whatever
#   rotation function the calling ROOT supplies, so the first rotation moves the
#   AWSCURRENT label to a version this module has never seen. An output named for
#   the current version would then be confidently wrong -- and wrong precisely
#   when someone reached for it, which is after a rotation. Nothing consumes it
#   either: the container secrets mechanism resolves from the ARN and the ETL
#   helper resolves by name. A stale identifier that nobody asked for is worth
#   less than the absence of one, so it is absent, and the absence is recorded
#   here so it reads as a decision rather than a gap.
# =============================================================================
