# =============================================================================
# infra/modules/sqs/versions.tf
# -----------------------------------------------------------------------------
# Purpose:
#   Declares the Terraform CLI version floor and the AWS provider constraint
#   for the `sqs` module. That module provisions ten queues -- five primary
#   queues plus one dead-letter queue for each -- which together replace the
#   CardDemo baseline's five IBM MQ queues: a FIFO pair carrying the
#   pending-authorization request and reply, a standard pair carrying the
#   account-inquiry request and reply, and a standard terminal error sink.
#
#   This file deliberately declares NO provider configuration, because `sqs` is
#   a reusable module rather than a root; the two environment roots that call
#   it own that configuration. The closing comment carries the full rationale.
#
#   Parameters: none -- this file declares no `variable` blocks. The module's
#   inputs (environment name, encryption key, queue tuning and tags) are
#   declared in variables.tf.
#
#   Return values: none -- this file declares no `output` blocks. The module's
#   outputs (queue URLs, ARNs and names, consumed by the container services and
#   by the batch state machine) are declared in outputs.tf.
#
#   Errors:
#     * A Terraform CLI older than the floor declared below aborts the calling
#       root at `init` with an unsupported-version error, before any resource
#       is planned.
#     * An AWS provider release outside the constraint declared below aborts
#       the calling root at `init` with a no-matching-version error.
#     * Provider configuration added to this file would make any call of this
#       module that uses `count`, `for_each` or `depends_on` fail outright,
#       because Terraform rejects those arguments on a module that carries its
#       own provider configuration.
#
# WHY (non-obvious design decisions):
#   - Assumptions: this directory is never initialised or applied on its own. It
#     is consumed as `source = "../../modules/sqs"` from infra/envs/dev and
#     infra/envs/prod, and it is checked transitively when one of those roots
#     runs `init -backend=false` and then `validate`. Every constraint below is
#     therefore a contract imposed on those callers rather than a standalone
#     build configuration, which is why a local check confined to this
#     directory is not the authoritative one.
#   - Trade-offs: the module restates `required_providers` even though a calling
#     root already constrains the provider for the whole configuration. The
#     restatement buys a contract that is explicit per directory, greppable and
#     checkable by `tflint` without a root present; the cost is one more file
#     to touch when the provider major version is raised.
#   - Alternatives Considered: no state configuration appears here. A `backend`
#     block is meaningful only in a root, so state remains the exclusive
#     concern of infra/bootstrap and of the backend.tf held by each of the two
#     environment roots; declaring one here would read as though this directory
#     owned state that it cannot own.
# =============================================================================

terraform {
  # WHAT: the oldest Terraform CLI that a caller of this module may run.
  # WHY : Assumptions: the whole infra/ tree is authored against the 1.15
  #       language behaviour and is validated on 1.15.8 specifically. Declaring
  #       the floor makes an older CLI stop at `init` and name the version it
  #       needs, rather than failing further in with a parse error that points
  #       at an incidental syntax detail instead of at the real cause. The
  #       upper bound is left open because the 1.x line has held configuration
  #       compatibility forward, so a newer CLI needs no edit here.
  required_version = ">= 1.15.0"

  required_providers {
    # WHAT: the single AWS provider constraint that every directory under
    #       infra/ repeats verbatim.
    # WHY : Assumptions: one identical constraint tree-wide means a root
    #       resolves exactly one provider version for every module at
    #       once; constraints that drifted per module could leave a root unable
    #       to satisfy every child simultaneously. The 6.56 floor is carried
    #       even here, where no database is provisioned, because the
    #       aurora-postgresql module needs a provider that accepts a zero
    #       minimum Aurora Serverless capacity -- supported from 5.81.0 onward
    #       -- and a per-module floor would let a root satisfy this module
    #       while starving that one.
    # WHY : Trade-offs: `~>` against a two-part version pins the major line
    #       only, so this admits 6.56.0 and any later 6.x release while
    #       excluding 7.0.0, where the provider collects its breaking changes.
    #       Accepting 6.x minor releases lets upstream resource fixes reach
    #       this tree without editing every module and all three roots, and each
    #       root's .terraform.lock.hcl records the version actually resolved so
    #       a run stays reproducible; the accepted cost is that a root with no
    #       lock entry yet may resolve a newer 6.x than the one last reviewed.
    #       An exact `=` pin would trade that away for a mandatory
    #       tree-wide edit per upstream patch, and a bare `>=` would admit
    #       7.0.0 without review.
    aws = {
      # WHAT: the fully-qualified registry address rather than the bare name.
      # WHY : Assumptions: tflint's terraform_required_providers rule expects an
      #       explicit source, and naming the namespace removes any doubt over
      #       which `aws` provider a caller resolves through a mirror.
      source  = "hashicorp/aws"
      version = "~> 6.56"
    }

    # WHY : Alternatives Considered: infra/README.md lists random alongside aws
    #       as a provider of this project, so declaring it here for uniformity
    #       is the obvious-looking choice. Nothing in this module generates a
    #       random value, though -- random serves apply-time credential
    #       generation in the secrets and cognito modules, and a queue needs
    #       neither a password nor a globally unique name. A declared but unused
    #       provider is reported by tflint's terraform_unused_required_providers
    #       rule, and tflint runs as a gating step in
    #       .github/workflows/infra-ci.yml with no `|| true` and no
    #       continue-on-error, so the declaration would fail the pipeline
    #       outright rather than merely read as untidy. The asymmetry matters:
    #       the aws entry above stops being unused the moment main.tf declares
    #       the queues, whereas a random entry would never stop being unused.
    #       infra/bootstrap omits it for that same reason.
  }
}

# WHY : Alternatives Considered: configuring the provider here would let this
#       directory be applied by itself, which looks convenient. It would also
#       end the module's reusability -- a caller could no longer choose the
#       region or supply credentials, and this file's configuration would
#       compete with the root's own. Region and credentials therefore stay with
#       infra/envs/dev and infra/envs/prod, which supply them from the
#       configuration that calls this module.
# WHY : Trade-offs: infra/bootstrap/versions.tf does carry a provider block, and
#       correctly so, because bootstrap is a root and not a module. The
#       difference between the two files is intentional and is recorded here so
#       that a later reader does not "fix" it. The concrete consequence is that
#       this module inherits no provider-level `default_tags`, so main.tf must
#       attach the `tags` variable to every queue and every dead-letter queue
#       individually; omitting it from any one resource would silently leave
#       that queue untagged.
