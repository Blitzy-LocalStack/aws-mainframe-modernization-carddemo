# =============================================================================
# infra/modules/kms/outputs.tf
# -----------------------------------------------------------------------------
# Purpose:
#   The entire public contract of the `kms` module. Nothing else in this
#   directory is reachable from outside it: the four customer-managed keys, the
#   four aliases and the four key policies declared in
#   infra/modules/kms/main.tf are all module-internal, so the thirteen values
#   below are the only way any other directory in this tree can encrypt anything
#   with a key this module owns.
#
#   Three values are published per key -- the key ARN, the bare key identifier
#   and the alias name -- for each of the four data classes the module keeps
#   apart, plus one ordering token the CloudFront logging path needs. They are grouped below in the order the keys are declared in main.tf,
#   and each group names the sibling module or modules that consume it:
#
#     aurora   infra/modules/aurora-postgresql, through its `kms_key_arn`
#              input. That key is what the cluster's `storage_encrypted`
#              setting encrypts with, and it is the value the cluster's
#              `kms_key_id`, managed master-credential secret and Performance
#              Insights arguments each take.
#     s3       infra/modules/s3-datasets, through `kms_key_arn`;
#              infra/modules/cloudfront-spa, through `s3_kms_key_arn`; and the
#              log-producing modules plus observability, through their log
#              group or topic key inputs. The root wires one data-domain key
#              into those uses because the target architecture defines a key per
#              DATA CLASS rather than a separate logging key -- logs and stored
#              objects are the same class of content here.
#     secrets  infra/modules/secrets, through `kms_key_arn`, and
#              infra/modules/cognito, through `secrets_kms_key_arn`. Each sets
#              it as the `kms_key_id` of the entries holding the credentials the
#              stack generates rather than commits.
#     sqs      infra/modules/sqs, through `kms_key_arn`. It becomes the
#              `kms_master_key_id` of all five queues and of each of their five
#              dead-letter queues.
#   The environment roots additionally read the AURORA group directly, rather
#   than through a sibling module: the ARN in the resource element of the card
#   workload's task-role policy, and the ALIAS NAME as both the
#   CARDDEMO_SECURITY_CVV_KEY_ID and the
#   CARDDEMO_SECURITY_CUSTOMER_IDENTIFIER_KEY_ID runtime parameters. Those are
#   the values the application enciphers ITSELF, calling KMS directly rather than
#   through an integrated service. A fifth key was published here for them and
#   has been withdrawn; the group note at the foot of this file records why.
#
# Parameters:
#   None. An outputs.tf declares no input. The module's twenty inputs, each
#   carrying its own `type` and `description`, are declared in
#   infra/modules/kms/variables.tf, and not one of them is republished here.
#   An output echoing an input returns to the caller only what that caller
#   passed in, while adding the value to this module's published surface and to
#   its state. The four trust lists are the case where that would actually cost
#   something: they name caller-supplied principals, so re-emitting them would
#   place those principal names in the plan output of every root that calls this
#   module, on behalf of no reader that needed them.
#
# Return values:
#   Thirteen outputs, every one of type string, every one resolved from an
#   attribute of a resource this module creates -- three per key, in group
#   order, plus one:
#
#     aurora_key_arn          aurora_key_id          aurora_key_alias_name
#     s3_key_arn              s3_key_id              s3_key_alias_name
#     secrets_key_arn         secrets_key_id         secrets_key_alias_name
#     sqs_key_arn             sqs_key_id             sqs_key_alias_name
#
#     s3_key_policy_id        -- the thirteenth, and the one exception to the
#                                shape above. It publishes the applied S3 key
#                                POLICY rather than a key, because the
#                                CloudFront log-delivery path needs an ordering
#                                token proving the grants exist before delivery
#                                is enabled. Named here rather than left to be
#                                discovered so the shape rule below reads as
#                                having one documented exception instead of
#                                being wrong.
#
#   Read as a table: each row is one data class, and the three columns are the
#   key ARN, the bare key identifier and the alias name. Every one of the thirteen
#   carries a `description`, which is at once this file's central obligation,
#   the whole of what a tool can check about it, and the only text that reaches
#   this module's generated documentation -- infra/.terraform-docs.yml is
#   configured not to read comments, so every sentence in this file outside a
#   `description` is written for a reader of the source and for no generator.
#
# Errors / Exceptions:
#   An output block raises nothing while it is evaluated, so the failure modes
#   worth naming are the two whose LOCATION this file's shape decides:
#     - A value naming an attribute that does not exist fails
#       `terraform validate` in this directory, before any caller is involved.
#       That is the reason each value below is a direct reference to a resource
#       declared in main.tf and never a string assembled from parts: a mistyped
#       resource name is caught as an error here, whereas a mistyped string
#       would be a well-formed value that fails much later, at the service, as a
#       key that cannot be found.
#     - A renamed or removed output does NOT fail here. It fails in the calling
#       root, as an unresolved reference to a `module` attribute. That asymmetry
#       is the whole of the one-way-contract note below, and it is the reason a
#       rename is a change to be made in the callers first and in this file last.
#   No output carries a `precondition`; the shared notes record why not.
#
# WHY (non-obvious design decisions):
#   - Trade-offs: no output here is marked `sensitive`, and that is a decision
#     rather than an omission.
#   - Alternatives Considered: discretely named outputs rather than one map
#     keyed by data class.
#   - Assumptions: key ARNs travel outward from this module only, never inward.
#   - Assumptions: these thirteen names are a one-way contract that six sibling
#     modules and both environment roots depend on.
#   - Assumptions: three values per key, with the ARN as the primary form.
#   - Alternatives Considered: no output guarded by a `precondition`.
#   Every bullet is expanded, with its mechanism, in the shared-notes section
#   immediately below -- all six govern all thirteen outputs rather than any one
#   of them, so stating them per group would create four copies to keep in step.
#
#   Nothing in this file reports on a provisioned stack. No key it names has
#   been created, rotated or used: this tree is authored and statically checked,
#   and applying it to a live account is an operator action outside this scope.
#   What the presence of this file does change under the tree's own gates is
#   measurable, and it is recorded at the end of the shared notes rather than
#   here, so that it sits beside the rule it satisfies.
# =============================================================================

# =============================================================================
# Notes shared by all thirteen outputs
# -----------------------------------------------------------------------------
# The six notes in this section govern every output in the file. They are stated
# once here rather than repeated per group for the same reason main.tf states its
# per-key argument reasoning once: four copies of one rationale drift apart, and
# a rationale corrected in three places out of four is worse than one kept in a
# single place, because the three corrected copies make the fourth look
# deliberate. What is genuinely per-key -- which data class a key protects and
# which sibling modules consume it -- is documented at each group below and in
# each `description` instead.
# =============================================================================

# Trade-offs: NO OUTPUT HERE IS MARKED `sensitive`, and the alternative is a real
# one -- redacting all thirteen on the reasoning that a value naming an encryption
# key is security-adjacent and so ought not to appear in plan output or logs. It
# is rejected on what the marking would actually do, in three parts.
#   First, what these values are. A key ARN and a key identifier are NAMES. They
#   carry no key material and confer no access. Holding the name of a key does
#   not permit encrypting or decrypting with it: that is decided by the key
#   policy each key carries in main.tf together with the caller's own IAM
#   permissions, and a principal absent from both is refused whether or not it
#   knows the name. Redaction therefore withholds nothing that possession of the
#   value would have granted.
#   Second, what it would cost. `sensitive` suppresses a value from
#   `terraform plan` output, and the plan is the artifact this tree is reviewed
#   through -- being able to read a plan as a review artifact is one of the
#   reasons the migration provisions with Terraform at all. Thirteen redacted
#   values reduce the one diff a reviewer reads to check the stack's encryption
#   wiring to thirteen placeholders, so the review that would catch the database
#   being pointed at the queue key is precisely the review the marking blinds.
#   Third, where it would spread. The marking propagates through expressions: an
#   environment root that passes a sensitive output into a sibling module makes
#   values derived from it sensitive in turn, so arguments and outputs in modules
#   with no stake in this decision begin redacting themselves, and each has to be
#   unwrapped by hand to be readable again. The cost is paid in other files.
#   What does protect these keys is stated where it is implemented rather than
#   claimed here: the four key policies in main.tf, each naming the account root
#   as administrator and granting cryptographic use only to the principals that
#   key's own trust list names. Output redaction is not part of that mechanism
#   and substituting it for that mechanism is the error this note exists to
#   prevent.

# Alternatives Considered: DISCRETELY NAMED OUTPUTS RATHER THAN ONE MAP.
# A single map keyed by data class would replace thirteen blocks with one and is
# the obvious compression. It is rejected on how the two forms fail. All four
# ARNs are strings, so nothing in the type system tells them apart: a map lookup
# for the dataset-bucket key handed to the database module's `kms_key_arn` is
# accepted, plans cleanly, and creates the cluster encrypted under the wrong key.
# That mis-wiring then surfaces only when someone asks which key opens the
# database backups, and by then it is not a value that can be edited -- changing
# the key of an encrypted cluster means creating a new cluster. With discrete
# names the same mistake reads as an s3-named value passed to a database input,
# on one line of the calling root, where the argument name and the value name
# disagree in plain sight and a reviewer needs no knowledge of this module to see
# it.
#   Trade-offs: the accepted cost is real and is not merely length -- thirteen
#   blocks and thirteen descriptions instead of one, a fifth key would add three
#   more rather than one map entry, and a caller wanting to treat all four keys
#   uniformly has to assemble its own map from the four names. The cost has
#   already been paid and then partly refunded: a fifth key added three blocks
#   here and withdrawing it removed them again. That is the measured price of the decision rather than a
#   hypothetical one, and it is still accepted for the reason below. That is accepted
#   because no caller in this tree does treat them uniformly: each key goes to a
#   differently-named input on a different sibling module, so there is no
#   iteration for a map to serve and its only function would be brevity here at
#   the cost of legibility there.

# Assumptions: KEY ARNS TRAVEL OUTWARD FROM THIS MODULE ONLY, NEVER INWARD. An
# ARN's fields include the AWS account identifier, and the project admits no
# committed secret, credential or account identifier -- with no "it is only an
# account number" exemption, because an exemption is exactly what makes such a
# constraint unverifiable by search. Every value below is composed during `plan`
# from a resource this module creates, out of the partition and account main.tf
# resolves from whichever session the operator runs with, so this file names no
# account and one unmodified module serves any account it is run against. The
# mirror of this note sits at the head of infra/modules/kms/variables.tf, which
# is why no input there accepts a key ARN and no default there holds one: the
# direction is a property of the module rather than of either file, and stating
# it at only one end would leave the other end looking like an oversight.

# Assumptions: THESE SIXTEEN NAMES ARE A ONE-WAY CONTRACT. Six sibling modules --
# aurora-postgresql, s3-datasets, cloudfront-spa, secrets, cognito and sqs --
# receive a value from this file, and each receives it through
# infra/envs/dev/main.tf or infra/envs/prod/main.tf, which name these outputs
# literally. The dependency runs one way only: nothing in this module reads,
# imports or is conditioned on any of those callers, which is what lets this
# directory be understood and validated on its own, and which is also why
# renaming an output here breaks every root and every consuming module while
# breaking nothing that `terraform validate` would report in this directory.
#   Refactoring Rationale: this paragraph previously recorded that both roots
#   held "no composition file yet" and that the names below were therefore a
#   contract those roots "will be written against". Both roots now hold a
#   main.tf that names these outputs literally -- infra/envs/dev/main.tf and
#   infra/envs/prod/main.tf each read `module.kms.aurora_key_alias_name`
#   and `module.kms.aurora_key_arn`, among others -- so the paragraph is
#   restated as describing wiring that exists. The naming rule it justified is
#   unchanged and is the reason TWELVE of the thirteen follow one shape -- the
#   data class, then `_key_arn`, `_key_id` or `_key_alias_name` -- with no
#   abbreviation and no per-key exception. A root author transcribing them
#   should be able to derive each one from the key it belongs to instead of
#   looking it up. The thirteenth, `s3_key_policy_id`, is deliberately outside
#   the rule because it names a policy rather than a key, and a name that
#   pretended otherwise would be the more confusing of the two.

# Assumptions: THREE VALUES PER KEY, AND THE ARN IS THE PRIMARY FORM. Every
# consumer in this tree takes the ARN, which is why it leads each group: the
# cluster, bucket, secret and queue arguments named at the top of this file all
# accept a key ARN. The other two values exist because the ARN cannot stand in
# for them.
#   The bare identifier is published as its own value rather than left as a
#   substring for a caller to extract. Some resource arguments and some IAM
#   policy condition keys are written against a key identifier and not an ARN,
#   and the alternative -- each such caller splitting the trailing field off an
#   ARN -- copies this module's assumption about ARN structure into every file
#   that does it, where a caller that splits on the wrong field still produces a
#   string and still plans cleanly. Publishing the attribute the provider
#   already exposes keeps that structural knowledge in one place.
#   The ARN is nonetheless primary because it carries fields the identifier does
#   not: partition, region and account. A sibling module depends on exactly that
#   -- aurora-postgresql compares those three fields of this key's ARN against
#   those of the cluster's credential secret in a precondition, to catch a root
#   that has wired a key from one account to a secret in another. That check is
#   unwritable against a bare identifier, which names none of the three.
#   The alias name is the durable handle. An identifier is opaque about what it
#   opens and does not survive replacement of the key, whereas the alias name is
#   stable across that replacement -- the property recorded at the alias
#   declarations in main.tf. A reference intended to outlive any single key,
#   such as one an operator maintains in a runbook or in a parameter an
#   application reads at startup, therefore belongs against the alias, and this
#   file publishes the finished name so that such a reference never has to be
#   reassembled by hand from this module's naming inputs.

# Alternatives Considered: NO OUTPUT IS GUARDED BY A `precondition`. Adding one
# per output -- asserting that an ARN is non-empty, or that an alias name is
# well-formed -- was considered and rejected as a check with nothing left to
# check. Each value is an attribute the provider populates from a key it has just
# created, so it cannot be absent or malformed without that resource having
# failed and stopped the run before any output is evaluated. The assertions that
# do earn their place in this module guard its INPUTS, where a caller-supplied
# value genuinely can be wrong, and they are already there: the nineteen
# `validation` blocks in variables.tf. A precondition here would restate a
# guarantee the provider already makes, while having to be read and maintained as
# though it were load-bearing.

# Assumptions: WHAT THIS FILE'S PRESENCE CHANGES UNDER THE TREE'S OWN GATES,
# stated as measured rather than as intended, because main.tf deliberately
# records the state before it. That header notes one outstanding finding against
# this directory -- the module-structure rule reporting the absent outputs.tf --
# and leaves it unannotated on the grounds that a suppression for a condition
# which resolves itself would outlive the condition. This file is that
# resolution: with it present, a lint run over this directory against
# infra/.tflint.hcl reports no findings at all, `terraform fmt -check` is clean,
# and `terraform validate` succeeds both here and through a root that calls this
# directory as a local module source. The boundary beyond that is unchanged and
# is not narrowed by this file: `plan` and `apply` against a live account need
# credentials no part of this repository holds.

# =============================================================================
# Aurora PostgreSQL -- consumed by infra/modules/aurora-postgresql
#
# The relational records the VSAM masters become, and the cluster's automated
# backups and managed master-credential secret alongside them. This is the key
# whose loss would leave the migrated system of record unreadable, which is why
# it leads the four groups and why it is a key of its own rather than one shared
# with the three data classes below.
# =============================================================================

output "aurora_key_arn" {
  description = "ARN of the customer-managed key that encrypts the Aurora PostgreSQL cluster at rest -- the account, customer, card, ledger, reference and authorization records, together with the cluster's automated backups and its managed master-credential secret. A calling environment root passes this into the aurora-postgresql module's `kms_key_arn` input: it is the key that module's `storage_encrypted` cluster is encrypted with, and the value its `kms_key_id`, master-credential-secret and Performance Insights arguments each take."
  value       = aws_kms_key.aurora.arn
}

output "aurora_key_id" {
  description = "Bare identifier -- not the ARN -- of the key that encrypts the Aurora PostgreSQL cluster, for a consumer whose resource argument or IAM policy condition key is written against a key identifier rather than a full ARN, and for naming this key unambiguously in an operator procedure."
  value       = aws_kms_key.aurora.key_id
}

output "aurora_key_alias_name" {
  description = "Alias name this module assigns to the Aurora PostgreSQL key, carrying the module's name prefix and the environment so one environment's key is distinguishable from the other's. It remains valid if the key behind it is replaced, so a runbook step or a stored parameter that identifies the database's key should reference this rather than the identifier."
  value       = aws_kms_alias.aurora.name
}

# =============================================================================
# S3 objects, CloudWatch logs and alerts -- consumed by s3-datasets,
# cloudfront-spa, observability and each log-producing module
#
# One key serves both buckets because both hold the same class of data: content
# this stack produces and serves, rather than its system of record. Two consumers
# therefore read one group, and the second of them takes the value under a
# differently-named input, which is why both input names are spelled out in the
# ARN's description rather than left to be inferred from the group heading.
# =============================================================================

output "s3_key_arn" {
  description = "ARN of the customer-managed key for stored objects, CloudWatch log groups and the encrypted alert topic. A calling root passes it to s3-datasets and cloudfront-spa for bucket SSE-KMS, to network/ecs-service/api-gateway-http/step-functions-batch for log-group encryption, and to observability for its managed groups and SNS topic; the key policy admits only the regional logging and notification service paths in this account."
  value       = aws_kms_key.s3.arn
}

output "s3_key_id" {
  description = "Bare identifier -- not the ARN -- of the key that encrypts the object, log and alert data class, for a consumer whose resource argument or IAM policy condition key is written against a key identifier rather than a full ARN."
  value       = aws_kms_key.s3.key_id
}

output "s3_key_alias_name" {
  description = "Alias name this module assigns to the S3 key, carrying the module's name prefix and the environment. It survives replacement of the key behind it, so it is the reference an operator procedure should use when recording which key a stored dataset generation was encrypted under."
  value       = aws_kms_alias.s3.name
}

output "s3_key_policy_id" {
  description = "Provider identifier of the fully applied S3 key policy. The CloudFront logging v2 delivery consumes this as an ordering token ONLY -- it is not a key reference and it does not encrypt a log object, the delivery destination being SSE-S3 -- so that delivery is not enabled before the exact distribution and delivery-source grants on this key exist."
  value       = aws_kms_key_policy.s3.id
}

# =============================================================================
# Secrets Manager -- consumed by infra/modules/secrets and
# infra/modules/cognito
#
# The credentials the stack generates at provisioning time instead of committing.
# Of the four keys this is the one whose compromise would be worth the most to an
# attacker, since what it protects is the material that opens everything else --
# which is the strongest single reason the module provisions a key per data class
# rather than one.
# =============================================================================

output "secrets_key_arn" {
  description = "ARN of the customer-managed key that encrypts the Secrets Manager entries holding the generated database credential and the seed-user passwords -- values the stack generates at provisioning time rather than committing, which is the mechanism that lets no password field be carried into any target schema. A calling environment root passes this into the secrets module's `kms_key_arn` input and into the cognito module's `secrets_kms_key_arn` input, each of which sets it as the `kms_key_id` of the entries that module creates."
  value       = aws_kms_key.secrets.arn
}

output "secrets_key_id" {
  description = "Bare identifier -- not the ARN -- of the key that encrypts the stored credentials, for a consumer whose resource argument or IAM policy condition key is written against a key identifier rather than a full ARN."
  value       = aws_kms_key.secrets.key_id
}

output "secrets_key_alias_name" {
  description = "Alias name this module assigns to the Secrets Manager key, carrying the module's name prefix and the environment. It remains valid across replacement of the key behind it, so a credential-rotation or recovery procedure should identify the key by this name rather than by its identifier."
  value       = aws_kms_alias.secrets.name
}

# =============================================================================
# SQS -- consumed by infra/modules/sqs
#
# The queue payloads. These are not incidental data: the authorization request
# and reply are carried as delimited text whose fields include the card number,
# so a message body is cardholder data at rest for as long as it sits in a queue,
# and the dead-letter queues hold the same fields for longer.
# =============================================================================

output "sqs_key_arn" {
  description = "ARN of the customer-managed key that encrypts queue message payloads at rest -- the authorization request and reply, the split account/date inquiry requests, the shared inquiry reply and the error sink. A calling environment root passes this into the sqs module's `kms_key_arn` input, which sets it on every queue that module creates. That is five request, reply and error queues per the target messaging design, plus a sixth because the single inquiry request queue is split at the ownership boundary into an account-inquiry and a date-conversion request queue -- a divergence registered in docs/architecture/messaging-contracts.md, not an extra key. Each of the six has its own dead-letter queue, and the key covers those too."
  value       = aws_kms_key.sqs.arn
}

output "sqs_key_id" {
  description = "Bare identifier -- not the ARN -- of the key that encrypts the queue payloads, for a consumer whose resource argument or IAM policy condition key is written against a key identifier rather than a full ARN."
  value       = aws_kms_key.sqs.key_id
}

output "sqs_key_alias_name" {
  description = "Alias name this module assigns to the SQS key, carrying the module's name prefix and the environment. It survives replacement of the key behind it, so a procedure that inspects or redrives a queue should identify the key by this name rather than by its identifier."
  value       = aws_kms_alias.sqs.name
}

# =============================================================================
# Application-enciphered values -- deliberately NOT a fifth key's contract
#
# WHY (Refactoring Rationale): three outputs stood here - application_key_arn,
# application_key_id and application_key_alias_name - publishing a FIFTH
# customer-managed key that the application drew envelope data keys from. The key
# has been withdrawn, because the specified model is four keys with rotation, one
# per data-at-rest domain: Aurora, S3, Secrets Manager and SQS. The grant it
# carried now lives on the AURORA key, whose policy is where the placement
# rationale is recorded.
#
# Assumptions: no consumer loses a value it needs, so nothing is republished
# under a new name here. The two runtime parameters that read
# application_key_alias_name - CARDDEMO_SECURITY_CVV_KEY_ID for card-service and
# CARDDEMO_SECURITY_CUSTOMER_IDENTIFIER_KEY_ID for account-service - now read
# aurora_key_alias_name, and the card workload's task-role policy names
# aurora_key_arn in its resource element. Both are already published above, so
# the withdrawal removes three names without removing any reachable value.
# Alternatives Considered: keeping the three as aliases of the Aurora outputs, so
# no environment root had to change. Rejected - two names for one key is exactly
# the ambiguity that let a fifth key look like four, and the roots are the place
# where which key protects what should be legible.
# =============================================================================
