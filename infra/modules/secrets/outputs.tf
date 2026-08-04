# =============================================================================
# infra/modules/secrets/outputs.tf
# -----------------------------------------------------------------------------
# Purpose:
#   The COMPLETE public contract of the `secrets` module. Everything a caller is
#   able to learn about the credentials created in
#   infra/modules/secrets/main.tf, it learns from the four outputs below.
#
#   Every one of them is an IDENTIFIER -- a secret ARN, a secret name, a login
#   name. Not one is, or is derived from, a credential. A consumer that needs an
#   actual credential reads it from Secrets Manager at run time, using the ARN or
#   the name published here and holding an IAM grant scoped to that entry; the
#   value itself never travels through a Terraform output, never appears in a
#   plan, and never appears in this repository. That is the whole shape of this
#   file, and the rest of this header explains why it is not enforced for us.
#
# The invariant, and who enforces it:
#   NEVER publish `random_password.*.result`, never publish
#   `aws_secretsmanager_secret_version.*.secret_string`, and never publish a
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
#   and through those roots so do infra/modules/aurora-postgresql (its
#   `master_credential_secret_arn` and `master_username` inputs) and
#   infra/modules/ecs-service (its `secret_arns` input, and the task-role
#   statement scoped to those ARNs). Nothing in THIS module depends on any of
#   those files, so nothing here breaks when they change -- the dependency runs
#   one way only. Renaming an output below therefore breaks every caller while
#   leaving this module valid in isolation, which is the failure mode a rename
#   is least likely to be tested for. Add outputs freely; rename them only by
#   changing every caller in the same commit.
#
# Parameters:
#   None. An outputs file declares no input. The module's ten inputs, their
#   types, defaults and `validation` blocks are declared in
#   infra/modules/secrets/variables.tf -- the mirror image of the cross-reference
#   that file carries under its own "Return values".
#
# Return values -- this file IS the return-value contract, in full:
#   database_master_secret_arn ...... ARN of the Aurora master credential entry
#   database_master_secret_name ..... name of that same entry, for by-name reads
#   database_master_username ........ the master login NAME, never its password
#   service_credential_secrets ...... role name -> { arn, name }, one per role
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
#   generated character, a name still reserved by an earlier deletion, a rotation
#   function that cannot reach the cluster) or to the `validation` blocks in
#   variables.tf. One ordering hazard IS this file's to close, and `depends_on`
#   below closes it: see the why-comment on database_master_secret_arn.
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
# Aurora PostgreSQL master credential
# -----------------------------------------------------------------------------

output "database_master_secret_arn" {
  description = <<-EOT
    ARN of the Secrets Manager entry holding the Aurora PostgreSQL master
    credential. This is the primary handle for the whole module: the calling
    root passes it to infra/modules/aurora-postgresql as
    `master_credential_secret_arn` when the cluster is created, and it is the
    single resource an IAM `secretsmanager:GetSecretValue` statement is scoped
    to so that a task role, or an operator performing break-glass access, can
    read the credential without being granted the secret store wholesale. The
    ARN is a reference to where the value lives; it is not the value, and it
    confers no access on its own.
  EOT
  value       = aws_secretsmanager_secret.database_master.arn

  # WHY : Trade-offs: `sensitive` is deliberately NOT set, here or on any output
  #       in this file, and the uniform posture is the decision -- an
  #       output-by-output mix would be the thing needing an explanation. Two
  #       specific consequences were reproduced against the CLI this module pins
  #       rather than reasoned about abstractly, and together they are why
  #       marking these outputs is not the free precaution it looks like:
  #         1. Sensitivity propagates through references, through `length()` and
  #            through a `for` expression, and it crosses a module boundary. A
  #            `sensitive` map handed to infra/modules/ecs-service as
  #            `secret_arns` therefore reaches its
  #            `for_each = length(var.secret_arns) > 0 ? ...` and fails outright:
  #            "Sensitive values, or values derived from sensitive values, cannot
  #            be used as for_each arguments." That marking would break a real
  #            consumer that exists in this tree today, and break it in the exact
  #            place the per-service map exists to serve -- one narrowly scoped
  #            grant per task role.
  #         2. A root that re-exports a `sensitive` module output without marking
  #            its own copy fails with "Output refers to sensitive values". So
  #            marking here would not contain a risk; it would push an obligation
  #            onto infra/envs/dev/outputs.tf and infra/envs/prod/outputs.tf,
  #            files this module does not own, and would make `terraform output`
  #            print "(sensitive value)" in place of the ARN a runbook step needs
  #            to read.
  # WHY : Assumptions: what makes the absence CORRECT rather than merely
  #       convenient is that there is nothing here to protect. Sensitivity in
  #       Terraform travels along references, and no output in this file
  #       references `random_password.result` or `secret_string` -- every value
  #       comes from an `aws_secretsmanager_secret` name or ARN, or from
  #       `var.database_master_username`. An ARN and a secret name are not
  #       credentials; they are published in plan output and in generated module
  #       documentation on purpose, because a reviewer has to be able to see
  #       WHICH secret is being wired into a cluster. Note the asymmetry this
  #       creates and do not misread it: a value-bearing output would need
  #       `sensitive`, and the answer to that is not to mark one but to never
  #       declare one.
  # WHY : Assumptions: `depends_on` names the version resource even though the
  #       ARN above does not reference it, and without it this output is subtly
  #       wrong. `aws_secretsmanager_secret.database_master.arn` is resolved as
  #       soon as the empty secret SHELL exists, so a consumer wired only to the
  #       ARN may be created while the entry still holds no value -- a cluster
  #       built from a credential that has not been written yet. Naming the
  #       version here makes the published handle mean "a secret that HAS a
  #       value", which is what every consumer assumes it means. This is the same
  #       apply-order race main.tf already closes with `depends_on` between the
  #       rotation configuration and this version, resolved the same way at the
  #       module boundary; it is worth closing for the same reason recorded
  #       there, that a race which only sometimes has the version present is the
  #       hardest class of failure to reproduce afterwards.
  depends_on = [aws_secretsmanager_secret_version.database_master]
}

output "database_master_secret_name" {
  description = <<-EOT
    Name of the Secrets Manager entry holding the Aurora PostgreSQL master
    credential, composed by this module as
    `<name_prefix>/<environment>/aurora/master`. Published for the readers that
    resolve a secret by name rather than by ARN: a `get-secret-value
    --secret-id <name>` call in the data-migration and batch-operations
    runbooks, and a service or ETL profile that names the entry rather than
    embedding an account-specific ARN. Carries no leading slash, which is a
    requirement rather than a style -- Secrets Manager rejects a `SecretId`
    beginning with `/`.
  EOT
  value       = aws_secretsmanager_secret.database_master.name

  # WHY : Alternatives Considered: publishing the name at all, when the ARN above
  #       already identifies the same entry and ECS resolves secrets by ARN. It
  #       is published because a by-name reader exists and derives the name
  #       INDEPENDENTLY: data-migration's config helper builds
  #       `<prefix>/<environment>/aurora/<role>` from its own copy of the
  #       convention and passes it as `SecretId`. That independent derivation is
  #       deliberate on its side, so the useful thing this module can do is
  #       publish the name it actually created, letting a calling root wire a
  #       name it was given and letting an operator confirm the two agree. The
  #       alternative -- callers composing the name themselves -- multiplies
  #       copies of a convention that nothing compares.
  # WHY : Assumptions: read from the resource attribute rather than from
  #       `local.database_master_secret_name`, even though the two are equal by
  #       construction. The attribute is what Secrets Manager holds; the local is
  #       what this configuration asked for. Publishing the attribute means a
  #       caller receives the name of a secret that exists, so if the two ever
  #       diverge the published value stays the true one.
  # WHY : same ordering guarantee as the ARN above, for the same reason: a name
  #       that resolves to a valueless entry is as useless to a by-name reader as
  #       an ARN that does.
  depends_on = [aws_secretsmanager_secret_version.database_master]
}

output "database_master_username" {
  description = <<-EOT
    Login name of the Aurora PostgreSQL master user, as stored in the
    `username` field of the master credential document. An identifier, not a
    credential -- the matching password is generated during apply and is not
    published by this module in any form. Consumed by the calling root as the
    `master_username` it passes to infra/modules/aurora-postgresql, so that the
    name the cluster is created with and the name stored beside the generated
    password come from one place and cannot disagree.
  EOT
  value       = var.database_master_username

  # WHY : Assumptions: this is the one credential-ADJACENT value the module
  #       publishes, and it is published because a user name is an identifier.
  #       It has to be legible in the Aurora cluster definition, in a connection
  #       string and in an operator runbook, so treating it as a secret would be
  #       self-defeating rather than cautious; knowing the login name of a
  #       database reachable only from the isolated data subnets grants nothing,
  #       because authentication is what the generated password protects. The
  #       distinction drawn here is exactly the one variables.tf draws on the
  #       input side, and it is restated at the point of publication because this
  #       is where publishing it could be mistaken for a slip.
  #       The password has no counterpart output, and the asymmetry is the point:
  #       there is no `database_master_password`, no `..._credentials` object
  #       pairing the two, and nothing derived from the generated value.
  # WHY : Alternatives Considered: echoing an input is unusual for an output, and
  #       the alternative is to let each caller pass its own
  #       `database_master_username` twice -- once into this module and once into
  #       the Aurora module. Rejected because the two must be identical for the
  #       cluster to authenticate against its own stored credential, and two
  #       independent arguments in a root's configuration are two things a typo
  #       can separate, failing at cluster creation rather than at review.
  #       Publishing the value this module actually stored makes the agreement
  #       structural: the root wires one output into one input.
  # WHY : Trade-offs: no `depends_on`, unlike the two outputs above, and the
  #       difference is deliberate. This value is known at plan time from a
  #       variable and does not describe a stored entry, so there is no
  #       shell-without-a-value state for it to be read too early in. Adding an
  #       ordering constraint anyway would make a plan-time constant appear to
  #       wait on an apply-time resource, which misrepresents what it is.
}

# -----------------------------------------------------------------------------
# Per-service database role credentials
# -----------------------------------------------------------------------------

output "service_credential_secrets" {
  description = <<-EOT
    The Secrets Manager entry created for each per-service database role, as a
    map keyed by role name -- `carddemo_auth`, `carddemo_account` and the rest
    of the roles named in `service_credential_names` -- whose value carries
    that entry's `arn` and `name`. One entry exists per element of that input,
    so the map is empty only if the input is. The calling root projects the
    `arn` fields into infra/modules/ecs-service's `secret_arns` input, keyed by
    the container environment-variable name each service expects, and scopes
    one `secretsmanager:GetSecretValue` statement per task role to the single
    ARN that role is entitled to read. The `name` fields are what a by-name
    reader passes as `SecretId`, matching the role name character for
    character.
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
    }
  }

  # WHY : same ordering guarantee as the master credential outputs above, applied
  #       to the whole family at once. `depends_on` accepts a resource address
  #       but not an indexed one, so every entry in this map waits for every
  #       service version rather than only for its own. That is a wider
  #       constraint than each entry strictly needs and it is accepted for the
  #       same reason main.tf accepts it on the service rotation resource: the
  #       alternative available in the language is no ordering guarantee at all.
  depends_on = [aws_secretsmanager_secret_version.service]
}

# =============================================================================
# The version identifier is deliberately NOT published, and the reason is
# stronger than "no caller needs it".
#
#   Assumptions: `aws_secretsmanager_secret_version.*.version_id` records the
#   version THIS configuration created, and main.tf is built so that that
#   version stops being the current one. Its `ignore_changes = [secret_string]`
#   hands authority for the stored value to the store after creation, and its
#   rotation resource exists so a supplied function can replace that value; the
#   first rotation therefore moves the AWSCURRENT label to a version this module
#   has never seen. An output named for the current version would then be
#   confidently wrong -- and wrong precisely when someone reached for it, which is
#   after a rotation. Nothing consumes it either: the container secrets
#   mechanism resolves from the ARN, the ETL helper resolves by name, and
#   infra/modules/aurora-postgresql takes only the ARN. A stale identifier that
#   nobody asked for is worth less than the absence of one, so it is absent, and
#   the absence is recorded here so it reads as a decision rather than a gap.
# =============================================================================
