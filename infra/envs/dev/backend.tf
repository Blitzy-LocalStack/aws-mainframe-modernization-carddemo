# =============================================================================
# infra/envs/dev/backend.tf
# -----------------------------------------------------------------------------
# Purpose:
#   Declare where the `dev` root keeps its Terraform state: the versioned,
#   encrypted S3 bucket and the DynamoDB lock table that `infra/bootstrap`
#   creates. The declaration is deliberately PARTIAL -- it commits only the state
#   key and the encryption flag, and receives the account-resolved values at
#   initialization. A reviewer reading a `backend "s3"` block with no `bucket`
#   argument is looking at a complete file, not an unfinished one; the reason is
#   under Non-obvious design decisions below.
#
#   This file declares no `variable`, `output`, `resource` or `module`, so it
#   returns no value and has no output contract to describe. Binding this root to
#   one state object is its only effect. `versions.tf` owns the toolchain and
#   provider constraints and the root's single `provider "aws"`; the `terraform`
#   block here and the one there are separate blocks in separate files, which is
#   the intended split rather than a duplication to be merged.
#
# Parameters:
#   The four values this backend does not carry are supplied at initialization,
#   each read from the correspondingly named `infra/bootstrap` output:
#
#     -backend-config="bucket=<state-bucket>"           <- state_bucket_name
#     -backend-config="region=<region>"                 <- aws_region
#     -backend-config="dynamodb_table=<lock-table>"     <- state_lock_table_name
#     -backend-config="kms_key_id=<state-kms-key-arn>"  <- state_kms_key_arn
#
#   Those keys are this file's entire input surface, and they appear nowhere else
#   in it, so they are recorded here rather than left to be inferred from a block
#   that omits them. The runnable command form lives in `README.md` beside this
#   file; `.github/workflows/deploy.yml` passes the same four on the deploy path.
#
# Errors / Exceptions:
#   - `init` fails while the bootstrap backend does not exist, because the bucket
#     and table it names cannot be reached. Apply `infra/bootstrap` once per AWS
#     account, out of band, before this root initializes; do not fall back to
#     local state. `docs/runbooks/deploy.md` owns that sequence.
#   - Any state-touching operation fails with a lock error while another
#     operation holds the lock, which is the lock doing its job.
#     `docs/runbooks/teardown.md` owns the `force-unlock` procedure and the
#     judgement of when a lock is genuinely orphaned. It is not restated here:
#     one recovery procedure written down twice is how the two copies diverge.
#
# Non-obvious design decisions:
#   - Alternatives Considered: committing the bucket name here, which is the
#     obvious form and is rejected. Bootstrap composes that name as
#     `<prefix>-tfstate-<account-id>-<region>`, because the S3 namespace is
#     global and the account identifier is what makes the name unique, so the
#     literal would carry an account-identifying value into source control and
#     would additionally pin this root to the one account whose identifier it
#     names, when the same configuration has to initialize against any of them.
#     Reading it from a `variable` was considered next: that is not merely
#     discouraged but impossible, since Terraform resolves the backend before
#     variables exist, so the reference has nothing to evaluate against. A
#     committed `*.tfbackend` file was considered last and fails for the same
#     reason as the literal, having only moved the value to another tracked file.
#     Partial configuration is what survives all three, and it settles three
#     things at once: no identifying literal is committed, `infra/bootstrap`
#     remains the single source of those values, and `infra-ci.yml` can still
#     validate this root with no AWS credential at all.
#   - Trade-offs: that credential-free gate runs `init -backend=false` and then
#     `validate`. `validate` needs an initialized directory for provider schemas
#     and module sources, but a plain `init` would read this file, reach for S3
#     and demand credentials a fork pull request cannot have, so `-backend=false`
#     initializes modules and providers while skipping the backend. The accepted
#     cost is that the gate does not check this backend configuration itself, and
#     a malformed backend block therefore survives it. That is a check deferred
#     rather than skipped: it surfaces on the first backend-enabled `init` in
#     `deploy.yml`, where it cannot be missed.
#   - Assumptions: the table passed as `dynamodb_table` has a partition key named
#     exactly `LockID` of type String. That is the schema Terraform's S3 backend
#     requires and writes its lock item under, not a naming preference, so a
#     table built to any other shape initializes and then fails when it first
#     tries to lock. `infra/bootstrap` creates it to that contract.
#   - Assumptions: `.github/workflows/deploy.yml` sets `cancel-in-progress:
#     false` and rests that decision entirely on this lock, which stands in for
#     the serialized `CSD(READWRITE)` access the mainframe deploy job held
#     (`app/jcl/CBADMCDJ.jcl`). Cancelling a half-finished `apply` would leave the
#     environment partly provisioned AND abandon the lock, blocking every later
#     apply until an operator force-unlocks, so queueing a deployment is
#     preferable to aborting one mid-flight. Removing the lock reference from
#     here would invalidate that guarantee in a file that cannot observe this one.
#   - Trade-offs: Terraform 1.15.8 accepts `dynamodb_table` and warns that the
#     parameter is deprecated in favour of `use_lockfile`, S3-native
#     conditional-write locking. It emits that warning whether the argument is
#     committed or passed at `init`, so the documented command form above
#     produces it as expected output rather than as a misconfiguration.
#     `use_lockfile = true` is deliberately not set here, and adding it would not
#     quiet that warning in any case: the warning tracks the presence of
#     `dynamodb_table`, which the documented initialization still passes, so
#     enabling both merely locks twice and warns anyway. The flag buys nothing
#     until the table argument goes away, and dropping the table is the step
#     `docs/adr/ADR-009-iac-tool.md` reserves: enable the flag in every root
#     together, confirm operations lock against the object store, and only then
#     remove the argument and the table. The `prod` root beside this one commits
#     these same two arguments, so a lock flag in `dev` alone would also break the
#     property that the state key is the only line differing between the two
#     roots. Because Terraform permits both mechanisms at once, the replacement
#     stays one argument away with no window in which state goes unlocked.
# =============================================================================

terraform {
  backend "s3" {
    # WHY : Assumptions: this key is the one line that differs from the `prod`
    #       root's otherwise identical backend, and both roots share the single
    #       bucket bootstrap creates per account. It names a path within that
    #       bucket and identifies no account, which is why it is the part that can
    #       be committed; editing it would silently repoint this root at another
    #       environment's state rather than fail.
    key = "carddemo/dev/terraform.tfstate"

    # WHY : Trade-offs: the flag is committable because a boolean names nothing,
    #       while the customer-managed key that performs the encryption arrives as
    #       `kms_key_id` at initialization because its ARN embeds the account
    #       identifier. Encryption at rest does not rest on either argument being
    #       supplied: `infra/bootstrap` sets the bucket's DEFAULT encryption to
    #       SSE-KMS under that same project-owned key, and the S3 backend relies on
    #       that default rather than sending an encryption header of its own. This
    #       argument therefore states the client's requirement in agreement with
    #       the bucket instead of competing with it.
    encrypt = true
  }
}
