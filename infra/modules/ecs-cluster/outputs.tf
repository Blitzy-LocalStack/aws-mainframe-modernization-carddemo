# =============================================================================
# infra/modules/ecs-cluster/outputs.tf
# -----------------------------------------------------------------------------
# Purpose:
#   The ENTIRE public contract of the `ecs-cluster` module. A Terraform module
#   has no return statement, and a `resource` block yields nothing to a caller
#   by itself, so everything any other module ever learns about this cluster it
#   learns from the four `output` blocks below.
#
#   The output NAMES are a ONE-WAY contract, and two modules already bind to
#   them by name:
#     ecs-service ............ reads `cluster_arn` AND `cluster_name`, once per
#                              service, eight times in each environment root
#     step-functions-batch ... reads the same ARN as its own `ecs_cluster_arn`
#   Both receive the values through infra/envs/dev/main.tf and
#   infra/envs/prod/main.tf, so renaming an output here breaks both consumers in
#   both roots. The dependency runs strictly ONE WAY: nothing in this module --
#   not this file, not main.tf, not variables.tf -- reads either consumer.
#   Adding an output is therefore safe; renaming or removing one is not.
#
# Parameters:
#   None. This file declares no `variable`. It also declares no `resource`, no
#   `data` source, no `local`, no nested `module`, no `provider` and no
#   `terraform` block -- it holds `output` blocks and comments, and nothing
#   else. The module's inputs are declared in variables.tf, its two resources
#   in main.tf, and its provider contract in versions.tf.
#
# Provides (the HCL analogue of a return value) -- four outputs, none of them
# conditional and none of them sensitive:
#   cluster_name ......................... string. Bare cluster name; the form
#                                          Application Auto Scaling requires
#                                          inside a composite resource
#                                          identifier.
#   cluster_arn .......................... string. Fully qualified cluster
#                                          reference; the form the ECS service
#                                          argument takes, and the only form an
#                                          IAM policy can be scoped to.
#   capacity_provider_names .............. set(string). The providers the
#                                          cluster holds, so a caller writing a
#                                          per-service strategy knows which
#                                          names are eligible.
#   default_capacity_provider_strategy ... set(object). The cluster-level
#                                          placement default a task inherits
#                                          when it names neither a launch type
#                                          nor a strategy of its own.
#
# Failure modes (the HCL analogue of a raised exception):
#   - Renaming or removing an output is a breaking change to ecs-service and to
#     step-functions-batch, and it surfaces in the CALLING ROOT rather than
#     here: the root's `module` block reports an unsupported attribute, naming a
#     file the reader did not edit.
#   - All four values are unknown until the cluster is created, so a caller may
#     not use one where Terraform demands a value during planning -- as a
#     `count`, as a `for_each` key, or as a provider argument. Handing one to
#     another module's input, which is what both consumers do, is unaffected.
#   - Two of the four are SETS rather than lists, because that is how the pinned
#     provider declares the arguments they read, even though variables.tf
#     declares the corresponding inputs as lists. A caller that indexes either
#     one positionally fails at plan; the reasoning is recorded on each.
#   - Nothing here asserts that the cluster exists or that the strategy is one
#     ECS accepts. This directory is a reusable module and never a root, so it
#     is checked by `terraform fmt`, by a transitive `terraform validate` from a
#     calling root, and by the lint gate. Whether the cluster these values
#     describe exists is settled when an operator runs `terraform apply` from an
#     environment root, which is outside what this tree checks.
#
# WHY (non-obvious design decisions):
#   - Assumptions: the name AND the ARN are both published, because the two
#     consumers hand them to two different AWS APIs that demand different forms
#     of one identity, and neither form substitutes for the other.
#   - Alternatives Considered: `cluster_id` is deliberately absent, because the
#     pinned provider gives it the same value as `arn`.
#   - Alternatives Considered: nothing owned by a sibling module is published,
#     because this module creates none of it and would have to compose the
#     value out of nothing.
#   - Trade-offs: no output is marked `sensitive`; all four are identifiers, and
#     redacting them would cost the review path that reads them.
# =============================================================================

# -----------------------------------------------------------------------------
# One decision governs all four outputs, so it is recorded once here rather
# than four times below.
#
# Trade-offs: no output in this file is marked `sensitive`, and that is a
# decision rather than an omission. A cluster name and a cluster reference are
# IDENTIFIERS -- holding either grants nothing on its own, which is exactly why
# the batch state machine's execution role has to name this cluster before it
# may start a task in it. Marking them would cost three things that are used
# and buy nothing. The aggregate `terraform output` listing would render them
# redacted, and that listing is how an operator reads back what a root
# produced. The plan that .github/workflows/infra-ci.yml retains as a review
# artifact would redact them too, so a reviewer could no longer tell WHICH
# cluster a changed service was being placed in -- the single question a
# cluster diff is read to answer. And Terraform propagates the mark through
# every expression that touches the value, so ecs-service's composed
# autoscaling identifier, `service/<cluster-name>/<service-name>`, would redact
# as well, taking an unrelated diff down with it.
#
# Assumptions: the boundary is stated rather than left as an absolute claim,
# because one half of it is genuinely true. A fully qualified cluster reference
# DOES carry an account identifier, once a plan resolves it against whichever
# account the caller's provider is configured for. That is precisely why no
# such reference appears anywhere in this tree as a literal or as a variable
# default, and why the ECS Exec key reaches main.tf as a nullable input rather
# than a defaulted one. What this package forbids is COMMITTING such a value; a
# value computed during planning from the caller's own credentials is not
# committed, and nothing in this file is a stored value at all -- all four are
# references into the two resources main.tf declares.
# -----------------------------------------------------------------------------

# -----------------------------------------------------------------------------
# Cluster identity
#
# Assumptions: the two outputs below identify ONE object, and publishing both is
# required rather than redundant, because the consumers pass them to two
# different AWS APIs that accept different forms.
#
#   The BARE NAME is what Application Auto Scaling needs. It identifies a
#   service by the composite string `service/<cluster-name>/<service-name>`,
#   which ecs-service composes for its scalable target's resource identifier.
#   That composite takes the name and nothing else, so an ARN cannot stand in.
#
#   The ARN is what the ECS service argument and every IAM policy need.
#   aws_ecs_service takes the qualified reference as its `cluster` argument, and
#   the nightly batch chain runs each of its working states as a Fargate task
#   through the synchronous `ecs:runTask.sync` integration, whose execution role
#   is granted `ecs:RunTask`, `ecs:StopTask` and `ecs:DescribeTasks` scoped to
#   named resources rather than to `*`. An IAM policy's resource element cannot
#   be written against a bare cluster name, and least-privilege IAM is a
#   non-negotiable constraint on this migration, so the qualified form is load
#   bearing here rather than a convenience.
#
# Alternatives Considered: publishing ONE of the two and letting each consumer
# derive the other. Rejected in both directions, and both consumers already
# record the rejection from their own side.
#   Publishing only the ARN would push `split("/", ...)` or a regex into every
#   caller -- string surgery on an identifier whose internal layout belongs to
#   the provider rather than to this module, and which would silently produce a
#   wrong name if that layout ever gained a field. ecs-service declares
#   `cluster_name` as a second input specifically so that no caller has to.
#   Publishing only the name would not work at all: an ARN cannot be recovered
#   from a name without additionally knowing the partition, the region and the
#   ACCOUNT IDENTIFIER, so every consumer would have to obtain and carry an
#   account identifier in its own configuration to rebuild a value this module
#   already holds -- which is the one thing this package forbids outright. Both
#   consumers validate the qualified shape on arrival, each requiring a
#   twelve-digit account field, so a reconstructed value would have to be
#   complete to be accepted anyway.
# -----------------------------------------------------------------------------

output "cluster_name" {
  # WHY : Assumptions: read from the RESOURCE attribute rather than from
  #       local.cluster_name or var.cluster_name, even though all three resolve
  #       to the same string after a successful apply. Reading the resource is
  #       what makes this value depend on the cluster having been created; the
  #       local is a constant as far as Terraform's graph is concerned, so a
  #       consumer could otherwise receive a name, and place a service against
  #       it, before the cluster it names exists. main.tf takes this same
  #       attribute for this same reason when it associates the capacity
  #       providers, so the two agree by construction rather than by
  #       coincidence.
  description = "Bare name -- not the ARN -- of the ECS Fargate cluster this module creates. ecs-service consumes it to compose the Application Auto Scaling target identifier `service/<cluster-name>/<service-name>`, which AWS accepts in no other form."
  value       = aws_ecs_cluster.this.name
}

output "cluster_arn" {
  # WHY : Assumptions: unlike the name, this cannot be composed in advance from
  #       the module's inputs. `arn` is a computed attribute whose partition,
  #       region and account fields are only known once the provider resolves
  #       them against the caller's credentials, which is what makes this output
  #       the only place a consumer can obtain the qualified form -- and why
  #       neither consumer declares a default for the input that receives it.
  description = "ARN of the ECS Fargate cluster this module creates. aws_ecs_service takes it as its `cluster` argument, the batch state machine carries it in each task state's parameters, and the execution and task role policies are scoped to it so a task may be started only in this cluster."
  value       = aws_ecs_cluster.this.arn
}

# Alternatives Considered: `cluster_id` was considered and is deliberately NOT
# published. The pinned provider does expose an `id` attribute on
# aws_ecs_cluster, so the output could be written -- a schema probe confirms it
# is present as a plain computed string. It is omitted because the provider
# documents `id` and `arn` as carrying the SAME value, the cluster ARN, so there
# is no second identifier behind it to publish. Putting one value into the
# contract twice under two names carries a concrete cost rather than a stylistic
# one, and the failure it invites is the one ecs-service already warns about: a
# consumer meeting `cluster_id` beside `cluster_arn` reasonably infers that a
# distinct SHORT identifier exists, and wires the id into the input named for a
# name. That input is typed `string` and carries no shape validation -- only the
# ARN input does -- so the swap type-checks, plans cleanly, and then composes an
# Application Auto Scaling resource identifier with a whole ARN embedded where
# the cluster name belongs, failing at apply against a resource the reader did
# not know was involved. One name per value is what removes the inference.

# -----------------------------------------------------------------------------
# Capacity-provider configuration
#
# Assumptions: both outputs below read the ASSOCIATION resource rather than the
# variables that configured it, and the difference is observable rather than
# academic. A caller that narrows `capacity_providers` produces an association
# Terraform has to update; a consumer reading the resource sees what the cluster
# actually holds once that update has been applied, where a consumer reading the
# variable would see the requested value whether or not the association had
# converged. The dependency edge is the same point stated as ordering: reading
# the resource sequences a consumer after the association, so a per-service
# strategy cannot name a provider the cluster has not yet been given.
#
# Trade-offs: both arrive as SETS, and the type is the provider's rather than a
# choice made here -- the pinned schema declares `capacity_providers` as a set
# of strings and `default_capacity_provider_strategy` as a set of blocks, while
# variables.tf declares the corresponding inputs as lists. So the shape a caller
# passes in is not the shape it reads back, and a consumer can neither index
# either one positionally nor rely on the order it wrote -- Terraform refuses a
# `[0]` on both, because set elements have no addressable key. Where that
# refusal lands is worth stating, because it is further along than it looks:
# across a module boundary the output's type is not resolved during `terraform
# validate`, so the mistake passes validation and is reported at plan. The
# accepted cost is that a consumer wanting one specific entry selects it by
# content, with a `for` expression and an `if` clause on the provider name,
# which is the remediation Terraform's own error message names. Nothing
# meaningful is lost with the order: ECS apportions placement by weight rather
# than by position, and variables.tf already forbids more than one entry
# carrying a base, so no entry is distinguished by where it sat in the list.
# -----------------------------------------------------------------------------

output "capacity_provider_names" {
  # WHY : Alternatives Considered: named `capacity_provider_names` rather than
  #       `capacity_providers`, which would have matched the resource argument
  #       it reads. The values are ECS SHORT NAMES, and the inputs elsewhere in
  #       this tree that take a collection of AWS resources take ARNs and
  #       validate for them, so an unqualified `capacity_providers` invites a
  #       caller to pass this where a provider ARN is expected. Both forms are
  #       strings, so the type cannot warn them; the suffix states the form,
  #       which is the one thing a caller cannot read off the signature.
  description = "Short names of the capacity providers associated with the cluster, as a set. A caller writing a per-service capacity-provider strategy may name only a provider that appears here, because ECS refuses a strategy entry naming a provider the cluster does not hold."
  value       = aws_ecs_cluster_capacity_providers.this.capacity_providers
}

output "default_capacity_provider_strategy" {
  # WHY : Assumptions: reading the nested block off the association resource
  #       means this reports an EMPTY set when a caller passes an empty strategy
  #       list, which is the state main.tf reaches by emitting no strategy block
  #       at all. An empty set is therefore a meaningful answer rather than a
  #       missing value: it tells a consumer the cluster carries no inherited
  #       default and that its own services must name a launch type or a
  #       strategy themselves. That is the one case in which reading this output
  #       changes what a caller has to write, which is why it is published
  #       instead of being left for a caller to infer from its own tfvars.
  description = "Cluster-level default placement strategy, as a set of objects carrying `capacity_provider`, `weight` and `base`. ECS applies it to any task or service naming neither a launch type nor a strategy of its own; a consumer that sets its own strategy replaces this one outright rather than merging with it. An empty set means the cluster has no default and a launch type or strategy must be named per service."
  value       = aws_ecs_cluster_capacity_providers.this.default_capacity_provider_strategy
}

# -----------------------------------------------------------------------------
# Deliberately not published
#
# Alternatives Considered: the outputs a reader may arrive here expecting, each
# paired with the sibling module that owns it. None is withheld as a judgement
# about what a caller ought to want -- this module declares exactly two
# resources, a cluster and its capacity-provider association, so it has nothing
# to read any of these from, and publishing one would mean composing a value out
# of nothing and presenting the invention as a contract:
#   task definition ARN, task and execution role ARNs,
#   per-service log group, target group ARN, service
#   name, desired count, autoscaling target ......... ecs-service
#   VPC id, the three subnet tiers, security group
#   ids ............................................. network
#   log groups, dashboards, alarms, the
#   notification topic .............................. observability
#   the internal load balancer and its per-service
#   listener rules .................................. alb
#   the four customer-managed keys .................. kms
#   the container image repositories ................ ecr
#   the nightly batch state machine ................. step-functions-batch
#
# This is the output side of the narrowness main.tf argues for on cardinality.
# The cluster exists ONCE PER ENVIRONMENT while ecs-service is instantiated ONCE
# PER SERVICE, eight times in each root, so a cluster output reaching into a
# service's resources would have to pick one of the eight -- and picking would
# reintroduce into this contract exactly the per-service coupling that keeping
# the two modules apart removes. Each value above is published by the module
# that creates it and wired together by the environment root, which is also why
# no consumer needs this file to forward anything on a sibling's behalf.
# -----------------------------------------------------------------------------
