# =============================================================================
# infra/bootstrap/outputs.tf
# -----------------------------------------------------------------------------
# Purpose:
#   Publishes the entire public interface of the infra/bootstrap root: the name
#   of the S3 bucket holding Terraform state, the name of the DynamoDB table
#   serialising concurrent writes to it, and the region both live in. Those
#   three values are the whole contract this root offers -- nothing else it
#   creates is addressable from outside this directory.
#
#   Each value is transcribed into the S3 backend block of every other
#   Terraform root in this repository, which is to say into the two environment
#   roots under infra/envs/. A `backend "s3"` block accepts only literal
#   arguments, so that transcription is performed by an operator, or supplied
#   through `-backend-config`, rather than resolved by reference. That property
#   is what makes the output NAMES below a stable contract two other roots
#   depend on rather than a convenience for reading a plan.
#
# Parameters:
#   None. This file declares no variable, no local, no data source and no
#   resource. It reads two resource attributes from infra/bootstrap/main.tf and
#   one input from infra/bootstrap/variables.tf, and declares nothing itself.
#
# Return values -- the three outputs, with the consumer argument each feeds:
#   state_bucket_name     string. The state bucket's name. Feeds the `bucket`
#                         argument of each environment root's S3 backend.
#   state_lock_table_name string. The state-lock table's name. Feeds the
#                         `dynamodb_table` argument of that same backend.
#   aws_region            string. The region holding both. Feeds the `region`
#                         argument of that same backend.
#
# Errors / Exceptions:
#   An output declaration cannot fail on its own, so every failure mode here is
#   a failure to READ one. All three are meaningful only after a successful
#   `terraform apply` of this root: before that the resources they describe do
#   not exist and `terraform output` reports no outputs at all rather than
#   empty strings. Reading them also requires the LOCAL terraform.tfstate this
#   root writes beside these files on its first apply, because versions.tf
#   declares no `backend` block by design -- this root creates the backend, so
#   it cannot keep its own state in one. An operator who has lost that local
#   file gets nothing back from `terraform output`, and the recovery is
#   `terraform import`, which works precisely because the names reported here
#   are deterministic rather than randomly suffixed.
#
# WHY (non-obvious design decisions):
#   - Refactoring Rationale: the baseline this migration replaces had no
#     mechanism for publishing a resource name, so it repeated them instead.
#     The account-master dataset name occurs 27 times across 9 files --
#     app/csd/CARDDEMO.CSD, app/catlg/LISTCAT.txt and the seven JCL decks that
#     read or write that file -- and the load-library name occurs 21 times.
#     Nothing but discipline held those copies in step, and renaming the
#     dataset meant editing every deck that named it. Declaring each name once
#     here, and reading it back off the resource that carries it, replaces that
#     discipline with a mechanism.
#   - Assumptions: the three names below are an external contract rather than
#     an internal detail. The two environment roots under infra/envs/ are this
#     root's only consumers in the Terraform graph, and each transcribes these
#     names into a `backend "s3"` block. A backend block is read before
#     Terraform evaluates any expression, so it can hold no variable, local or
#     reference -- there is no indirection available to hide a rename behind.
#     Renaming an output here therefore breaks `terraform init` in both
#     environments, and it breaks in those directories rather than in this one.
#   - Trade-offs: exactly three outputs are declared and no more. An S3 backend
#     takes a bucket name, a table name and a region from this root, so an ARN,
#     a bucket identifier or a composed configuration object would all be
#     surface that no consumer reads. Unused output surface is a liability
#     rather than a courtesy: it invites a consumer to couple to it, and
#     withdrawing it afterwards is a breaking change to a root that is applied
#     once per account and seldom revisited. The lint boundary is worth
#     stating accurately -- infra/.tflint.hcl does enable
#     terraform_unused_declarations, but that rule covers variables, locals,
#     data sources and provider aliases, NOT outputs, which are external
#     interface by definition. Holding the surface at three is therefore design
#     discipline that no linter here enforces.
#   - Alternatives Considered: marking all three `sensitive = true`, which is
#     the first hardening a reviewer is likely to propose. Rejected, and the
#     reason is mechanical rather than a judgement about how secret a name is.
#     Because a backend block cannot take a reference, the documented procedure
#     under docs/runbooks/ and in this directory's README has an operator READ
#     these values and write them into each environment's backend
#     configuration. `sensitive = true` redacts a value from `terraform
#     output`, which would leave that procedure impossible to carry out while
#     protecting nothing: a bucket name and a table name are configuration, not
#     credentials, and grant no access by themselves -- access is decided by
#     IAM, by the bucket policy and by the public-access block that main.tf
#     configures. Nothing secret is published here, so there is nothing here to
#     redact.
# =============================================================================

# WHY the value is read off the resource rather than from main.tf's
# `local.state_bucket_name`, which resolves to the same string -- Assumptions:
# the local describes the name this root ASKED for, resolved by `coalesce` from
# either an operator override or a composed default; the resource attribute
# reports the name that was actually created. The two are expected to agree,
# and reading the resource is what guarantees this contract can never report a
# name that was not provisioned -- where the create failed there is no value to
# read, instead of a plausible one that exists nowhere in the account.
#
# WHY `.bucket` rather than `.id` -- Trade-offs: the AWS provider resolves both
# to the bucket name, and main.tf itself uses `.id` where it wires the
# sub-resources to their bucket. `.bucket` states which attribute is meant at
# the reference site, where `.id` leaves a reader consulting the provider
# schema to confirm it is not an ARN or a generated identifier. The accepted
# cost is one deliberate inconsistency with the sibling file's internal wiring,
# taken because this expression is the published contract and its meaning
# should not depend on a schema lookup.
output "state_bucket_name" {
  description = "Name of the versioned, encrypted S3 bucket that holds Terraform state for every other root in this repository. Supply it as the `bucket` argument of each environment root's S3 backend block."
  value       = aws_s3_bucket.state.bucket
}

# Read off the resource rather than main.tf's `local.lock_table_name`, for the
# reason recorded on the bucket above.
#
# WHY the description names `dynamodb_table`, an argument HashiCorp documents
# as deprecated -- Assumptions: main.tf records the full position at the lock
# table itself and this file must not contradict it. Terraform's S3 backend
# offers two locking mechanisms: `use_lockfile`, which locks through S3, and
# `dynamodb_table`, which is the deprecated one. The table is provisioned
# because a DynamoDB lock table is a mandated deliverable of this root. Naming
# the deprecated argument here forecloses nothing, because the two mechanisms
# may be configured together: a backend that additionally sets `use_lockfile`
# still needs this table's name in order to reach the table at all.
output "state_lock_table_name" {
  description = "Name of the DynamoDB table Terraform uses to serialise concurrent writes to the state object. Supply it as the `dynamodb_table` argument of each environment root's S3 backend block. Its partition key is `LockID` of type String, which is the schema the S3 backend requires and writes the lock item under."
  value       = aws_dynamodb_table.state_lock.name
}

# WHY this one echoes an input, in a file whose other two values come off
# resources -- Assumptions: an S3 backend requires a region, and it has to be
# the region the bucket actually occupies. var.aws_region is the same value
# versions.tf hands the provider and the same value main.tf composes into the
# default bucket name, so echoing it makes it impossible for the region
# reported to the environment backends, the region the bucket was created in,
# and the region embedded in that bucket's name to disagree.
#
# Alternatives Considered: a `data "aws_region"` lookup. Rejected because it
# would be a second, independent source of one fact -- it reports whichever
# region the ambient credentials resolved to, which is exactly the value that
# can differ from the operator's intent without anything failing, and can drift
# from the region already baked into the bucket name. main.tf declares no such
# data source for the same reason.
output "aws_region" {
  description = "AWS region containing both the state bucket and the lock table. Supply it as the `region` argument of each environment root's S3 backend block."
  value       = var.aws_region
}
