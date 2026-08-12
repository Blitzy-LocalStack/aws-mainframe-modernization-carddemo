# =============================================================================
# infra/modules/ecs-cluster/outputs.tf
# -----------------------------------------------------------------------------
# Purpose:
#   The ENTIRE public contract of the `ecs-cluster` module. A Terraform module
#   has no return statement, and a `resource` block yields nothing to a caller by
#   itself, so everything any other module ever learns about this cluster it
#   learns from the four `output` blocks below.
#
#   The output NAMES are a ONE-WAY contract. `ecs-service` reads `cluster_arn`
#   and `cluster_name` once per workload in each environment root, and
#   `step-functions-batch` reads the same ARN as its own `ecs_cluster_arn`; both
#   receive the values through the environment roots. Nothing in this module
#   reads either consumer, so adding an output is safe while renaming or removing
#   one breaks both consumers in both roots.
#
# Parameters:
#   None. This file declares no `variable`, no `resource`, no `data` source, no
#   `local`, no nested `module`, no `provider` and no `terraform` block -- it
#   holds `output` blocks and comments and nothing else. The module's inputs are
#   declared in variables.tf, its two resources in main.tf, and its provider
#   contract in versions.tf.
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
#   - Renaming or removing an output is a breaking change to both consumers, and
#     it surfaces in the CALLING ROOT rather than here: the root's `module` block
#     reports an unsupported attribute, naming a file the reader did not edit.
#   - All four values are unknown until the cluster is created, so a caller may
#     not use one where Terraform demands a value during planning -- as a
#     `count`, as a `for_each` key, or as a provider argument. Handing one to
#     another module's input, which is what both consumers do, is unaffected.
#   - Two of the four are SETS rather than lists, because that is how the pinned
#     provider declares the arguments they read, even though variables.tf
#     declares the corresponding inputs as lists. A caller that indexes either
#     one positionally fails at plan; the reasoning is recorded below.
#   - Nothing here asserts that the cluster exists or that the strategy is one
#     ECS accepts. This directory is a reusable module and never a root, so it is
#     checked by `terraform fmt`, by a transitive `terraform validate` from a
#     calling root, and by the lint gate.
#
# WHY (non-obvious design decisions):
#   - Assumptions: the name AND the ARN are both published, because the two
#     consumers hand them to two different AWS APIs that demand different forms
#     of one identity and neither substitutes for the other. Application Auto
#     Scaling identifies a service by the composite
#     `service/<cluster-name>/<service-name>`, which takes the bare name and
#     nothing else; `aws_ecs_service` and every IAM policy resource element take
#     the qualified reference, and least-privilege IAM is a non-negotiable
#     constraint on this migration.
#   - Alternatives Considered: publishing one of the two and letting each
#     consumer derive the other, rejected in both directions. Deriving a name
#     from an ARN pushes string surgery on a provider-owned layout into every
#     caller; deriving an ARN from a name is impossible without the partition,
#     the region and the ACCOUNT IDENTIFIER, so every consumer would have to
#     carry an account identifier in its own configuration -- which this package
#     forbids outright.
#   - Alternatives Considered: `cluster_id` is deliberately absent, because the
#     pinned provider gives it the same value as `arn`.
#   - Alternatives Considered: nothing owned by a sibling module is published.
#     This module declares exactly two resources, a cluster and its
#     capacity-provider association, so it has nothing to read a task definition,
#     a role, a log group, a target group, a subnet or a key from, and publishing
#     one would mean composing a value out of nothing. Each is published by the
#     module that creates it and wired together by the environment root. It is
#     also the output side of cardinality: the cluster exists once per
#     environment while `ecs-service` is instantiated once per workload, so a
#     cluster output reaching into a service's resources would have to pick one
#     instance, reintroducing exactly the per-service coupling that keeping the
#     two modules apart removes.
#   - Trade-offs: no output is marked `sensitive`. All four are identifiers,
#     which grant nothing on their own -- that is why the batch execution role
#     must name this cluster before it may start a task in it -- and marking them
#     would cost three things that are used. The aggregate `terraform output`
#     listing an operator reads back would redact them; the plan infra-ci.yml
#     retains as a review artifact would redact them, so a reviewer could not
#     tell which cluster a changed service was placed in; and Terraform
#     propagates the mark through every expression that touches the value, so
#     ecs-service's composed autoscaling identifier would redact as well. A
#     qualified reference does carry an account identifier once a plan resolves
#     it, which is why no such reference appears anywhere in this tree as a
#     literal or a variable default; what this package forbids is COMMITTING one,
#     and nothing in this file is a stored value at all.
# =============================================================================

output "cluster_name" {
  # WHY : Assumptions: read from the RESOURCE attribute rather than from
  #       local.cluster_name or var.cluster_name, even though all three resolve to
  #       the same string after a successful apply. Reading the resource is what
  #       makes this value depend on the cluster having been created; the local is
  #       a constant as far as Terraform's graph is concerned, so a consumer could
  #       otherwise place a service against a name before the cluster it names
  #       exists. main.tf takes this same attribute for this same reason when it
  #       associates the capacity providers.
  description = "Bare name -- not the ARN -- of the ECS Fargate cluster this module creates. ecs-service consumes it to compose the Application Auto Scaling target identifier `service/<cluster-name>/<service-name>`, which AWS accepts in no other form."
  value       = aws_ecs_cluster.this.name
}

output "cluster_arn" {
  # WHY : Assumptions: unlike the name, this cannot be composed in advance from
  #       the module's inputs. `arn` is a computed attribute whose partition,
  #       region and account fields are known only once the provider resolves them
  #       against the caller's credentials, which is what makes this output the
  #       only place a consumer can obtain the qualified form -- and why neither
  #       consumer declares a default for the input that receives it.
  description = "ARN of the ECS Fargate cluster this module creates. aws_ecs_service takes it as its `cluster` argument, the batch state machine carries it in each task state's parameters, and the execution and task role policies are scoped to it so a task may be started only in this cluster."
  value       = aws_ecs_cluster.this.arn
}

# Alternatives Considered: `cluster_id` is deliberately NOT published, even though
# the pinned provider exposes an `id` attribute on aws_ecs_cluster. The provider
# documents `id` and `arn` as carrying the SAME value, so there is no second
# identifier behind it. Putting one value into the contract twice under two names
# invites a concrete failure: a consumer meeting `cluster_id` beside `cluster_arn`
# reasonably infers a distinct SHORT identifier exists and wires the id into the
# input named for a name. That input is typed `string` with no shape validation --
# only the ARN input has any -- so the swap type-checks, plans cleanly, and then
# composes an Application Auto Scaling identifier with a whole ARN where the
# cluster name belongs, failing at apply against a resource the reader did not know
# was involved. One name per value is what removes the inference.

# -----------------------------------------------------------------------------
# Capacity-provider configuration
#
# Assumptions: both outputs below read the ASSOCIATION resource rather than the
# variables that configured it, and the difference is observable. A caller that
# narrows `capacity_providers` produces an association Terraform has to update; a
# consumer reading the resource sees what the cluster actually holds once that
# update has been applied, where a consumer reading the variable would see the
# requested value whether or not the association had converged. Stated as
# ordering: reading the resource sequences a consumer after the association, so a
# per-service strategy cannot name a provider the cluster has not yet been given.
#
# Trade-offs: both arrive as SETS, and the type is the provider's rather than a
# choice made here -- the pinned schema declares `capacity_providers` as a set of
# strings and `default_capacity_provider_strategy` as a set of blocks, while
# variables.tf declares the corresponding inputs as lists. So the shape a caller
# passes in is not the shape it reads back, and a `[0]` on either is refused
# because set elements have no addressable key. Where that refusal lands is
# further along than it looks: across a module boundary the output's type is not
# resolved during `terraform validate`, so the mistake passes validation and is
# reported at plan. A consumer wanting one specific entry selects it by content,
# with a `for` expression and an `if` clause on the provider name. Nothing
# meaningful is lost with the order -- ECS apportions placement by weight rather
# than by position, and variables.tf already forbids more than one entry carrying
# a base.
# -----------------------------------------------------------------------------

output "capacity_provider_names" {
  # WHY : Alternatives Considered: named `capacity_provider_names` rather than
  #       `capacity_providers`, which would have matched the resource argument it
  #       reads. The values are ECS SHORT NAMES, and the inputs elsewhere in this
  #       tree that take a collection of AWS resources take ARNs and validate for
  #       them, so an unqualified name invites a caller to pass this where a
  #       provider ARN is expected. Both forms are strings, so the type cannot warn
  #       them; the suffix states the form.
  description = "Short names of the capacity providers associated with the cluster, as a set. A caller writing a per-service capacity-provider strategy may name only a provider that appears here, because ECS refuses a strategy entry naming a provider the cluster does not hold."
  value       = aws_ecs_cluster_capacity_providers.this.capacity_providers
}

output "default_capacity_provider_strategy" {
  # WHY : Assumptions: reading the nested block off the association resource means
  #       this reports an EMPTY set when a caller passes an empty strategy list,
  #       which is the state main.tf reaches by emitting no strategy block at all.
  #       An empty set is a meaningful answer rather than a missing value: it tells
  #       a consumer the cluster carries no inherited default and that its own
  #       services must name a launch type or a strategy themselves. That is the
  #       one case in which reading this output changes what a caller has to write.
  description = "Cluster-level default placement strategy, as a set of objects carrying `capacity_provider`, `weight` and `base`. ECS applies it to any task or service naming neither a launch type nor a strategy of its own; a consumer that sets its own strategy replaces this one outright rather than merging with it. An empty set means the cluster has no default and a launch type or strategy must be named per service."
  value       = aws_ecs_cluster_capacity_providers.this.default_capacity_provider_strategy
}
