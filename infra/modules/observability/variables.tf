# =============================================================================
# infra/modules/observability/variables.tf
# -----------------------------------------------------------------------------
# Purpose:
#   The complete input surface of the `observability` module -- the module that
#   provisions CardDemo's centralized log groups, its operator dashboard, its
#   metric alarms and the notification topic those alarms publish to. Every
#   value a calling environment root can configure is declared here, and nothing
#   else is configurable: anything absent from the list below is a property of
#   the module fixed in main.tf, not a per-environment choice.
#
#   Lineage: this surface is ADDED by the migration rather than ported. Every
#   CICS file in the baseline is defined RECOVERY(NONE) JOURNAL(NO)
#   (app/csd/CARDDEMO.CSD lines 7 and 9), and the batch chain's only operational
#   signals are the job log routed by MSGCLASS, the operator notification from
#   NOTIFY=&SYSUID on the JOB card (app/jcl/POSTTRAN.jcl lines 1 to 2) and the
#   SYSPRINT and SYSOUT streams (app/jcl/POSTTRAN.jcl lines 26 to 27). The
#   inputs below are the cloud equivalents of exactly those three things: where
#   output goes, how long it is kept, and who is told when a step fails.
#
#   Nothing here is read from the ambient environment and nothing is generated
#   inside the module. Every input arrives from infra/envs/dev/main.tf or
#   infra/envs/prod/main.tf, which is what keeps the only differences between the
#   two environments visible in their own terraform.tfvars files.
#
# Parameters -- twelve required, seventeen optional:
#   environment                     string       REQUIRED. Names every resource.
#   kms_key_arn                     string       REQUIRED. Encrypts the log
#                                                groups and the topic.
#   ecs_cluster_name                string       REQUIRED. ECS metric dimension.
#   alb_arn_suffix                  string       REQUIRED. ALB metric dimension.
#   service_target_group_arn_suffixes
#                                   map(string)  REQUIRED. Per-service target
#                                                group metric dimensions.
#   api_gateway_id                  string       REQUIRED. API metric dimension.
#   api_gateway_stage_name          string       REQUIRED. Stage metric dimension.
#   aurora_cluster_identifier       string       REQUIRED. RDS metric dimension.
#   aurora_max_capacity             number       REQUIRED. Configured ACU ceiling.
#   queue_names                     map(string)  REQUIRED. SQS metric dimensions.
#   daily_state_machine_arn         string       REQUIRED. Batch metric dimension.
#   vpc_flow_log_group_name         string       REQUIRED. Flow-log query source.
#   name_prefix                     string       Common resource-name prefix.
#   tags                            map(string)  Module-specific tags.
#   log_retention_days              number       Retention for the log groups
#                                                this module owns.
#   log_group_names                 map(string)  Exact log-group names this
#                                                module creates for producers
#                                                that do not create their own.
#   dashboard_service_names         list(string) Services the dashboard renders
#                                                a row of widgets for.
#   cloudfront_distribution_id      string|null  Optional dashboard dimension.
#   alarm_email_endpoints           list(string) Subscribers on the topic.
#   alarm_evaluation_periods        number       Consecutive breaching periods
#                                                needed to raise an alarm.
#   alarm_period_seconds            number       Length of one such period.
#   service_error_count_threshold   number       Server-error count per period
#                                                that raises a service alarm.
#   database_cpu_threshold_percent  number       Cluster processor utilisation
#                                                that raises a database alarm.
#   dead_letter_depth_threshold     number       Messages in a dead-letter queue
#                                                that raise a messaging alarm.
#   batch_failure_threshold         number       Failed executions per period
#                                                that raise a batch alarm.
#   reply_queue_age_threshold_seconds
#                                   number       Stale-reply alarm threshold.
#   work_queue_age_threshold_seconds
#                                   number       Stale-work alarm threshold for
#                                                the primary request and error
#                                                queues.
#   access_log_bucket_force_destroy bool         Teardown behavior for the
#                                                shared access-log destination.
#   rotation_lambda_function_names  set(string)  Rotation functions whose
#                                                errors raise alarms.
#
#   Each block below carries the full `type` and `description` that tflint's
#   terraform_typed_variables and terraform_documented_variables rules require;
#   the summary above is a map of the surface, not a second copy of it.
#
# Return values:
#   None. A variables.tf declares no output, so the topic ARN, the dashboard
#   name and the log group names and ARNs this module publishes to its caller
#   are declared in infra/modules/observability/outputs.tf.
#
# Errors / Exceptions:
#   The twelve required identifiers have no default, so omitting a producer
#   contract stops the calling root at `plan` rather than creating alarms that
#   can never receive a datapoint. The key is required specifically to keep
#   encryption of the log groups and topic unskippable. Every other bounded
#   input carries a validation block rejecting an out-of-domain value at plan
#   time rather than letting a service reject it partway through an apply.
#
# WHY (non-obvious design decisions):
#   - Assumptions: this module does NOT own every log group in the stack, and the
#     `log_group_names` input is deliberately narrow because of it. Each ECS
#     service creates its own log group through the `ecs-service` module, which
#     takes its own retention and key inputs, and the batch state machine creates
#     its own through `step-functions-batch`. A log group created here for a
#     producer that also creates one would be an empty duplicate that still
#     bills, and an operator reading the empty one would conclude the producer
#     was silent.
#   - Trade-offs: the alarm inputs are FOUR named thresholds rather than one
#     generic list of alarm definitions. A generic list would let a root add an
#     alarm without a module change, at the cost of moving the alarm's metric,
#     namespace, statistic and comparison operator into tfvars, where none of
#     them can be validated and none carries a rationale. Four named thresholds
#     keep the alarm definitions in main.tf, where each can be explained, and
#     expose only the number a root actually has reason to vary.
#   - Assumptions: no alarm threshold defaults to a value that disables the
#     alarm. A threshold high enough never to be crossed produces a dashboard
#     and an alarm set that look complete and report nothing, which is worse than
#     having no alarm at all because it removes the reason to look.
#   - Assumptions: no input here accepts a credential or an account identifier,
#     and no default holds an ARN literal. The one ARN input is required
#     precisely so that no specimen default is needed: an ARN embeds an AWS
#     account identifier and the project's no-secrets-in-source constraint admits
#     no exception. Email endpoints are the one input that carries an address,
#     and it defaults to empty for the same reason -- a real address in tfvars is
#     a person's contact detail committed to source control.
#   - Where a comment below reasons about `terraform apply` or about an alarm
#     firing, it is describing what an input MEANS at those points, not reporting
#     on a provisioned stack. This tree is authored and statically validated --
#     formatted, validated, planned, linted and policy-scanned; applying it to a
#     live account is an operator action outside this scope.
# =============================================================================

# -----------------------------------------------------------------------------
# Naming and environment
# -----------------------------------------------------------------------------

# WHY this is required with no default. Trade-offs: a defaulted environment name
# is how one environment's alarms end up publishing to the other environment's
# topic, and an alarm that pages about the wrong environment is worse than one
# that does not page at all, because it teaches the recipient to ignore it.
# Requiring the value costs each root one line.
#
# WHY the accepted values are exactly two. Assumptions: two environment roots
# exist, infra/envs/dev and infra/envs/prod, and this module is called only from
# those two.
variable "environment" {
  description = "Environment name interpolated into the log group paths, the dashboard name, every alarm name and the topic name, so an alarm's own name says which environment raised it; must be `dev` or `prod`, the two environments that have a Terraform root under infra/envs/."
  type        = string

  validation {
    condition     = contains(["dev", "prod"], var.environment)
    error_message = "environment must be exactly \"dev\" or \"prod\", matching the environment root under infra/envs/ that calls this module."
  }
}

# WHY the characters are checked rather than trusted. Trade-offs: this prefix is
# concatenated into a dashboard name, a topic name and a set of log group paths,
# and those three accept different character sets. Restricting it to lowercase
# letters, digits and hyphens keeps it legal in all three at once and catches a
# space or an uppercase letter at plan time rather than during an apply that has
# already created other resources.
#
# WHY the name and the default match the rest of the tree instead of being chosen
# here. Assumptions: every directory under infra/ takes its prefix through a
# variable of this name with this default, which is what lets a root pass one
# value to every module it calls.
variable "name_prefix" {
  description = "Prefix concatenated into the dashboard name, the topic name, every alarm name and every log group path this module creates, giving the observability surface one greppable identity shared with the rest of the stack; lowercase letters, digits and hyphens only, at most 32 characters."
  type        = string
  default     = "carddemo"

  validation {
    condition     = can(regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?$", var.name_prefix)) && length(var.name_prefix) <= 32
    error_message = "name_prefix must be 1 to 32 characters of lowercase letters, digits and hyphens, beginning and ending with a letter or digit -- for example \"carddemo\"."
  }
}

# WHY an empty default reads as complete here rather than as an oversight.
# Assumptions: the baseline tag set is not this module's to supply. Each calling
# root configures `default_tags` on its own `provider "aws"` block and the
# provider merges that map into every taggable resource it creates, so the log
# groups, alarms and topic carry the root's common tags whether or not this
# variable is passed. What this input adds is the layer above that.
variable "tags" {
  description = "Tags merged onto the resources this module creates, layered on top of the common tag set the calling root already applies through its provider's `default_tags`; defaults to none, because the baseline tags arrive from the root rather than from this module."
  type        = map(string)
  default     = {}
}

# -----------------------------------------------------------------------------
# Producer identifiers and metric dimensions
# -----------------------------------------------------------------------------
#
# WHY : Alternatives Considered: deriving these values from name_prefix and
#       environment would shorten every module call, but it would duplicate the
#       naming rules owned by the producer modules. A rename would then leave a
#       syntactically valid alarm pointed at a dimension that no metric uses,
#       which CloudWatch reports as silence rather than as a wiring error.
#       Requiring the producer outputs at the environment root keeps the
#       dependency visible and passes the exact provider-returned identifier.

variable "ecs_cluster_name" {
  description = "Exact ECS cluster name used as the ClusterName dimension for Container Insights widgets. Required from the ecs-cluster module output so a cluster rename cannot leave this dashboard querying a derived, obsolete name."
  type        = string

  validation {
    condition     = can(regex("^[A-Za-z0-9_-]{1,255}$", var.ecs_cluster_name))
    error_message = "ecs_cluster_name must be 1 to 255 letters, digits, underscores or hyphens, matching the ECS cluster-name contract."
  }
}

variable "alb_arn_suffix" {
  description = "Provider-returned ARN suffix of the internal Application Load Balancer, used as the LoadBalancer dimension in AWS/ApplicationELB metrics. A full ARN is invalid for this dimension and produces a permanently empty alarm."
  type        = string

  validation {
    condition     = can(regex("^app/[^/]+/[A-Za-z0-9]+$", var.alb_arn_suffix))
    error_message = "alb_arn_suffix must have the Application Load Balancer suffix form app/<name>/<id>, not a full ARN."
  }
}

variable "service_target_group_arn_suffixes" {
  description = "Map of service name to provider-returned target-group ARN suffix for the seven online services. The map key labels dashboard and alarm outputs; an empty map deliberately creates no per-service load-balancer alarm."
  type        = map(string)

  validation {
    condition = alltrue([
      for service, suffix in var.service_target_group_arn_suffixes :
      can(regex("^[a-z][a-z0-9-]*$", service)) &&
      can(regex("^targetgroup/[^/]+/[A-Za-z0-9]+$", suffix))
    ])
    error_message = "service_target_group_arn_suffixes must map lower-case service names to targetgroup/<name>/<id> suffixes."
  }
}

variable "api_gateway_id" {
  description = "HTTP API identifier used as the ApiId dimension for edge 5xx widgets and alarms. It comes from api-gateway-http rather than being reconstructed from the endpoint URL."
  type        = string

  validation {
    condition     = can(regex("^[a-z0-9]+$", var.api_gateway_id))
    error_message = "api_gateway_id must contain only the lower-case letters and digits used by API Gateway identifiers."
  }
}

variable "api_gateway_stage_name" {
  description = "Created HTTP API stage name used as the Stage dimension beside api_gateway_id. The reserved $default stage is valid and must be passed literally when that is what the producer module created."
  type        = string

  validation {
    condition     = length(var.api_gateway_stage_name) > 0 && length(var.api_gateway_stage_name) <= 128
    error_message = "api_gateway_stage_name must be a non-empty API Gateway stage name no longer than 128 characters."
  }
}

variable "aurora_cluster_identifier" {
  description = "Provider-returned Aurora cluster identifier used as the DBClusterIdentifier dimension for processor, connection and serverless-capacity metrics."
  type        = string

  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{0,62}$", var.aurora_cluster_identifier))
    error_message = "aurora_cluster_identifier must begin with a lower-case letter and contain at most 63 lower-case letters, digits or hyphens."
  }
}

variable "aurora_max_capacity" {
  description = "Maximum Aurora Serverless capacity units configured by the environment root. The capacity-ceiling alarm compares against this declared configuration fact rather than inventing an independent target."
  type        = number

  validation {
    condition     = var.aurora_max_capacity >= 1 && var.aurora_max_capacity <= 256 && var.aurora_max_capacity * 2 == floor(var.aurora_max_capacity * 2)
    error_message = "aurora_max_capacity must be from 1 through 256 ACUs in half-unit increments."
  }
}

variable "queue_names" {
  description = "Map of logical queue key to the exact SQS QueueName dimension. Keys ending in _dlq receive dead-letter alarms, keys ending in _reply receive stale-reply alarms, and every other key receives a primary work-queue age alarm; an empty map creates no queue alarm."
  type        = map(string)

  validation {
    condition = alltrue([
      for key, name in var.queue_names :
      can(regex("^[a-z][a-z0-9_]*$", key)) &&
      can(regex("^[A-Za-z0-9_-]{1,75}(\\.fifo)?$", name))
    ])
    error_message = "queue_names must map snake_case logical keys to valid standard or .fifo SQS queue names."
  }

  validation {
    # WHY : Refactoring Rationale: this second rule exists because the first one
    #       cannot catch the failure that actually matters here. Alarm membership is
    #       decided by the key SUFFIX -- `_dlq` for the dead-letter alarm, `_reply`
    #       for the stale-reply alarm -- so a caller that renamed its keys while
    #       passing perfectly valid queue names would satisfy every rule above and
    #       silently receive ZERO dead-letter alarms, with nothing said at plan time.
    #       A dead-letter alarm is the highest-signal messaging alarm this module
    #       creates, so its silent disappearance is the worst available outcome.
    #       Assumptions: only the dead-letter suffix is asserted, not all three. Every
    #       queue in the messaging design has a dead-letter companion, so the presence
    #       of one `_dlq` key is evidence the whole convention is being followed;
    #       requiring a `_reply` key as well would refuse a caller that legitimately
    #       passes a subset -- a single queue and its dead-letter queue, for instance
    #       -- which the empty-map contract above already establishes as permitted.
    #       Trade-offs: an empty map is admitted, because an empty map is the
    #       documented way to create no queue alarm at all and is not a naming
    #       mistake.
    condition = length(var.queue_names) == 0 || anytrue([
      for key in keys(var.queue_names) : endswith(key, "_dlq")
    ])
    error_message = "queue_names must contain at least one key ending in _dlq, because dead-letter alarm membership is decided by that suffix; without one the module would create no dead-letter alarm and report nothing."
  }
}

variable "daily_state_machine_arn" {
  description = "ARN of the daily batch state machine used as the StateMachineArn dimension for failed and timed-out execution alarms."
  type        = string

  validation {
    condition     = can(regex("^arn:[a-z0-9-]+:states:[a-z0-9-]+:[0-9]{12}:stateMachine:[A-Za-z0-9_-]+$", var.daily_state_machine_arn))
    error_message = "daily_state_machine_arn must be a revision-independent Step Functions state-machine ARN."
  }
}

variable "vpc_flow_log_group_name" {
  description = "Exact CloudWatch log-group name created by the network module for VPC flow logs. It feeds the dashboard Logs Insights query and is never recreated here, preserving the network module's ownership of the flow-log lifecycle."
  type        = string

  validation {
    condition     = can(regex("^/[A-Za-z0-9_./#-]+$", var.vpc_flow_log_group_name))
    error_message = "vpc_flow_log_group_name must be an absolute CloudWatch log-group name beginning with /."
  }
}

# WHY : Trade-offs: null keeps the global-metric widget optional without
#       requiring every caller to configure a provider alias that this module
#       never uses for resources or alarms. Supplying an identifier enables only
#       the widget and preserves the module's single-provider contract.
variable "cloudfront_distribution_id" {
  description = "Optional CloudFront distribution identifier shown on the dashboard and alarmed for server-error rate. Null omits both the widget and the alarm. The alarm is additionally conditional on the provider region being us-east-1, because CloudFront publishes distribution metrics to us-east-1 alone; an earlier revision of this description said global metrics require a different provider region, which turned that conditional constraint into a blanket impossibility and omitted a signal both environment roots can in fact create, since both set aws_region to us-east-1."
  type        = string
  default     = null

  validation {
    condition     = var.cloudfront_distribution_id == null || can(regex("^[A-Z0-9]+$", var.cloudfront_distribution_id))
    error_message = "cloudfront_distribution_id must be null or an upper-case alphanumeric CloudFront distribution identifier."
  }
}

# -----------------------------------------------------------------------------
# Encryption
# -----------------------------------------------------------------------------

variable "kms_key_arn" {
  description = "ARN of the customer-managed key the log groups and notification topic are encrypted with. Required rather than optional because all eight CICS VSAM FILE resources are configured without recovery or journalling, so customer-controlled encryption is a target property that must not become skippable. Alternatives Considered: allowing null to select the services' managed-encryption fallback was rejected because it would make that target property optional and diverge from both environment roots, which provide a customer-managed key."

  type = string

  validation {
    # WHY : Assumptions: both the `kms:` service field and the `:key/`
    #       resource type are asserted, because an alias ARN is the plausible
    #       wrong value -- an alias is what a human reads in the console -- and
    #       it carries resource type `alias/`, which a log group does not accept.
    #       Catching that here names the input; letting it through produces an
    #       apply-time failure against the log group instead.
    condition     = can(regex("^arn:[a-z0-9-]+:kms:[a-z0-9-]+:[0-9]{12}:key/", var.kms_key_arn))
    error_message = "kms_key_arn must be a KMS key ARN of the form arn:<partition>:kms:<region>:<account-id>:key/<key-id>, not an alias ARN."
  }
}

# -----------------------------------------------------------------------------
# Log groups and retention
# -----------------------------------------------------------------------------

variable "log_retention_days" {
  description = "Days the log groups this module creates retain events. Retention is always set explicitly and never falls back to the service's unlimited default: 29 of the 38 baseline JCL members route job logs with MSGCLASS=0, so finite retention is part of the migration contract rather than an implicit service setting. This is one of the retention values the dev and prod roots are permitted to set differently without changing the stack's shape, and it is the direct analogue of how long a mainframe job log was kept before it aged off the spool."
  type        = number
  default     = 30

  validation {
    # WHY : Assumptions: the set is closed because the service accepts only
    #       these values and nothing else -- an arbitrary number is rejected
    #       during apply, after other resources exist. 0 is excluded deliberately
    #       even though the service reads it as never expire: unbounded retention
    #       is a cost that grows without anyone deciding to accept it, and a
    #       caller who wants it can widen this check rather than pass a value
    #       that looks like a mistake.
    condition     = contains([1, 3, 5, 7, 14, 30, 60, 90, 120, 150, 180, 365, 400, 545, 731, 1096, 1827, 2192, 2557, 2922, 3288, 3653], var.log_retention_days)
    error_message = "log_retention_days must be one of the retention periods CloudWatch Logs accepts: 1, 3, 5, 7, 14, 30, 60, 90, 120, 150, 180, 365, 400, 545, 731, 1096, 1827, 2192, 2557, 2922, 3288 or 3653."
  }
}

variable "log_group_names" {
  description = "Map of logical producer key to the exact CloudWatch log-group name this module creates for producers that do not own a group resource. Empty means no additional group is created; full names are required because Lambda and other managed producers write only to their service-defined paths."

  type = map(string)

  # Assumptions: the default is EMPTY on purpose, and the emptiness is the
  # decision rather than an unfinished thought. Every producer in this stack that
  # writes logs already owns its group: each of the eight container services
  # creates one through the `ecs-service` module, the nightly chain creates one
  # through `step-functions-batch`, and the HTTP API creates its access log group
  # through `api-gateway-http`. Creating a group for any of them here would leave
  # an empty duplicate that still bills, and an operator who opened the empty one
  # would reasonably conclude the producer had gone silent.
  #
  # WHY the input exists at all if the default is empty. Trade-offs: a producer
  # with no Terraform resource of its own -- the read-only-flag function, or an
  # operational script run from a task -- otherwise causes its log group to be
  # created implicitly on first write, with the service's default of unlimited
  # retention and no customer-managed key. That is the failure this input exists
  # to let a root avoid. Exact names rather than suffixes are accepted because a
  # Lambda writes to `/aws/lambda/<function-name>` and cannot be redirected to a
  # module-composed path; accepting suffixes would create a second, empty group.
  default = {}

  validation {
    # WHY : Assumptions: the map key is a Terraform identity and the value is
    #       the exact service path. Keeping the two separate makes a path change
    #       an in-place update rather than a destroy-and-create caused by a
    #       `for_each` key change.
    condition = alltrue([
      for key, name in var.log_group_names :
      can(regex("^[a-z][a-z0-9_-]*$", key)) &&
      can(regex("^/[A-Za-z0-9_./#-]+$", name))
    ])
    error_message = "Each log_group_names key must be snake_case and each value must be an absolute CloudWatch log-group name beginning with / and containing only supported path characters."
  }
}

# -----------------------------------------------------------------------------
# Dashboard
# -----------------------------------------------------------------------------

variable "dashboard_service_names" {
  description = "Service names the dashboard renders a row of widgets for, in the order given. The order is preserved because it is the order an operator reads the dashboard in, and a request travels through these services in roughly that sequence."

  type = list(string)

  # Assumptions: the eight defaults are the eight bounded contexts of this
  # migration, named exactly as their ECS services are, because a widget's metric
  # dimension is the service name and a mismatch produces a widget that renders
  # with no datapoint rather than an error. They are ordered as a reader
  # encounters them rather than alphabetically: sign-on first, then the account
  # and card lookups, then the ledger writes, then reference data, then the
  # nightly chain, then the asynchronous authorization path, then reporting.
  #
  # WHY this is an input rather than fixed in main.tf. Trade-offs: the dashboard
  # is the one resource here whose usefulness is a matter of operator taste, and
  # a root that wants a narrower board -- during an incident, or in a development
  # environment where six of the eight are idle -- should not need a module edit
  # to get one. The accepted cost is that a caller can pass a name matching no
  # service, which renders an empty widget; that is visible on the dashboard
  # itself, which is the cheapest place for it to be visible.
  default = [
    "auth-service",
    "account-service",
    "card-service",
    "transaction-service",
    "reference-service",
    "batch-service",
    "authorization-service",
    "reporting-service",
  ]

  validation {
    # WHY : Assumptions: each entry is used as a metric dimension value and as
    #       part of a widget title, so it is restricted to the characters an ECS
    #       service name accepts. An empty list is permitted deliberately: it is
    #       how a root asks for a dashboard with no per-service row, leaving the
    #       shared database and messaging widgets main.tf always renders.
    condition     = alltrue([for s in var.dashboard_service_names : can(regex("^[a-zA-Z0-9][a-zA-Z0-9_-]*$", s))])
    error_message = "Each dashboard_service_names entry must be a service name of letters, digits, hyphens and underscores, beginning with a letter or digit."
  }
}

# -----------------------------------------------------------------------------
# Notification
# -----------------------------------------------------------------------------

variable "alarm_email_endpoints" {
  description = "Email addresses subscribed to the notification topic, which is what replaces the baseline's NOTIFY=&SYSUID operator notification. Empty by default, because an address is a person's contact detail and this repository is not the place to record one."

  type = list(string)

  # WHY the default is empty rather than a distribution list. Assumptions: the
  # project's constraint is that nothing which belongs in a secret store or an
  # operator's own configuration is committed here. An address is not a
  # credential, but it is personal data and it changes with staffing rather than
  # with the architecture, so it belongs in a root's tfvars or in a subscription
  # created outside Terraform. The topic still exists with no subscriber, which
  # is deliberate: alarm publication remains wired, and adding a subscriber
  # changes delivery without changing the alarm resources.
  #
  # WHY email and not a chat or paging integration. Alternatives Considered: a
  # richer integration was considered and rejected for this module. Every such
  # integration needs an endpoint URL carrying a token, which is exactly the
  # class of value that may not be committed, so it would have to be read from a
  # secret at apply time and would make this module depend on the secrets module.
  # A topic with an email subscription needs no credential at all, and a root
  # that wants a richer integration can subscribe to the same topic without
  # changing this module.
  default = []

  validation {
    # WHY : Trade-offs: the check is deliberately a loose shape check rather
    #       than an attempt at full address validation. Its purpose is to catch
    #       the two mistakes that actually happen -- a bare username with no
    #       domain, and a value that is a URL rather than an address -- both of
    #       which the service accepts into a subscription that can never be
    #       confirmed. Rejecting genuinely unusual but legal addresses would be a
    #       worse failure than accepting them.
    condition     = alltrue([for e in var.alarm_email_endpoints : can(regex("^[^@[:space:]]+@[^@[:space:]]+\\.[A-Za-z]{2,}$", e))])
    error_message = "Each alarm_email_endpoints entry must look like an email address, with a local part, an @ sign, a domain and a top-level domain of at least two letters."
  }
}

# -----------------------------------------------------------------------------
# Alarm evaluation window
#
# The two inputs below apply to every alarm this module creates. They are shared
# rather than per-alarm because the window is a property of how quickly the
# operator wants to hear, not of which metric is being watched, and four
# independent windows would make two alarms about the same incident fire minutes
# apart for no reason a reader could reconstruct.
# -----------------------------------------------------------------------------

variable "alarm_evaluation_periods" {
  description = "Consecutive breaching periods required before an alarm changes state. Anything above one is what distinguishes a sustained problem from a single unlucky period, at the cost of delaying the notification by that many periods."
  type        = number
  default     = 2

  validation {
    # WHY : Trade-offs: one is permitted, because a batch chain that runs once a
    #       night has no second period to wait for and a single failed execution
    #       is the whole signal. The ceiling of 12 is where an alarm stops being
    #       an alarm: at the default period length that is an hour of sustained
    #       breach before anyone is told.
    condition     = var.alarm_evaluation_periods >= 1 && var.alarm_evaluation_periods <= 12 && floor(var.alarm_evaluation_periods) == var.alarm_evaluation_periods
    error_message = "alarm_evaluation_periods must be a whole number from 1 to 12 inclusive."
  }
}

variable "alarm_period_seconds" {
  description = "Length of one evaluation period, in seconds. The alarm's complete detection window is this value multiplied by the evaluation period count, so the two inputs are read together."
  type        = number
  default     = 300

  validation {
    # WHY : Assumptions: the accepted set is closed to the values CloudWatch
    #       treats as valid periods -- the three high-resolution values and then
    #       whole minutes up to a day. An arbitrary number such as 45 is rejected
    #       by the service during apply, and a value below 60 additionally
    #       requires the metric itself to be published at high resolution, which
    #       none of the metrics this module watches is; the smaller values remain
    #       in the set so admitting a high-resolution metric needs no change to
    #       this validation contract.
    condition     = contains([1, 5, 10, 30, 60, 120, 300, 600, 900, 1800, 3600, 21600, 86400], var.alarm_period_seconds)
    error_message = "alarm_period_seconds must be one of the periods CloudWatch accepts: 1, 5, 10, 30, 60, 120, 300, 600, 900, 1800, 3600, 21600 or 86400."
  }
}

# -----------------------------------------------------------------------------
# Alarm thresholds -- one per watched signal
# -----------------------------------------------------------------------------

# WHY : Refactoring Rationale: this input was named `service_error_rate_threshold`
#       and is renamed here because the name asserted something the alarm does not
#       do. It is applied as an absolute Sum of 5xx responses within one evaluation
#       period, not as a proportion of requests, so a low-traffic service alarms at
#       a far higher error RATE than a busy one and a reader budgeting from the old
#       name would have mis-set it. No environment root passed the old name, so the
#       rename moves no configured value.
# WHY : Alternatives Considered: keeping the name and implementing a true rate
#       through a metric-query expression over 5xx divided by request count.
#       Rejected because a rate needs a percentage threshold, and this repository
#       defines no error-rate objective from which one could be derived -- inventing
#       a percentage would read as authoritative while being arbitrary, which is
#       exactly the failure this module's own preamble refuses. A count is what the
#       alarm can state honestly, so the name is corrected to say count.
variable "service_error_count_threshold" {
  description = "Count of server-error responses within one evaluation period that raises the per-service alarm. This is an absolute Sum of the load balancer's own 5xx count and not a proportion of requests, so it fires for a service that is failing requests regardless of whether the service itself is still logging."
  type        = number
  default     = 5

  validation {
    # WHY : Trade-offs: the floor is 1 rather than 0. A threshold of zero is
    #       breached by any single server error, including the one a rolling
    #       deployment can produce as a task drains, which trains an operator to
    #       dismiss the alarm. A small positive threshold keeps the alarm
    #       meaningful; a root that genuinely wants zero tolerance can pass 1
    #       with an evaluation period count of 1.
    condition     = var.service_error_count_threshold >= 1 && floor(var.service_error_count_threshold) == var.service_error_count_threshold
    error_message = "service_error_count_threshold must be a whole number of 1 or more; zero would be breached by a single error, including one produced by a rolling deployment."
  }
}

variable "database_cpu_threshold_percent" {
  description = "Cluster processor utilisation, as a percentage, that raises the database alarm. On a serverless cluster this is a scaling signal as much as a saturation one: sustained high utilisation means the workload is pressed against its configured maximum capacity."
  type        = number
  default     = 80

  validation {
    # WHY : Assumptions: the range is 1 to 100 because the metric is a
    #       percentage; 0 would alarm permanently and a value above 100 could
    #       never be crossed, and both are accepted silently without this check.
    condition     = var.database_cpu_threshold_percent >= 1 && var.database_cpu_threshold_percent <= 100
    error_message = "database_cpu_threshold_percent must be between 1 and 100 inclusive, because the metric it is compared against is a percentage."
  }
}

variable "database_connection_threshold" {
  description = "Cluster connection count that raises the connection-saturation alarm, or null to create no such alarm. This is a DERIVED value, not a tuned one: ADR-003 states the relationship as tasks times pool size rather than tasks plus pool size, so a caller computes the product of its service task count and its per-task connection-pool size and passes that. Null is the correct value only for a composition that runs no pooled service, because otherwise cluster connections can be exhausted by scale-out while processor utilisation and serverless capacity both stay well inside their own alarms."
  type        = number
  default     = null

  validation {
    # WHY : Assumptions: null is a valid answer and must survive the check, so
    #       the condition short-circuits on it rather than comparing null to a
    #       number. A floor of 1 is used instead of 0 because a threshold of 0
    #       is crossed by an idle cluster that has merely opened a health-check
    #       connection, which would alarm permanently and train a reader to
    #       ignore the channel.
    condition     = var.database_connection_threshold == null || var.database_connection_threshold >= 1
    error_message = "database_connection_threshold must be null or a positive connection count; 0 would be breached by an idle cluster's own housekeeping connections."
  }
}

variable "cloudfront_5xx_error_rate_threshold_percent" {
  description = "Percentage of viewer requests answered with a server error that raises the distribution alarm. A rate rather than a count, because a static single-page application's request volume differs by orders of magnitude between working hours and overnight, and a count meaningful at one volume is noise or silence at the other. Ignored when cloudfront_distribution_id is null or when the provider region is not us-east-1, because CloudFront publishes distribution metrics to us-east-1 alone."
  type        = number
  default     = 5

  validation {
    # WHY : Assumptions: 1 to 100 because the metric is a percentage. A floor of
    #       1 rather than 0 keeps a permanently-breaching configuration
    #       unreachable, matching the processor-utilisation threshold above; the
    #       two are validated the same way deliberately, so a reader tuning one
    #       does not have to check whether the other behaves differently.
    condition     = var.cloudfront_5xx_error_rate_threshold_percent >= 1 && var.cloudfront_5xx_error_rate_threshold_percent <= 100
    error_message = "cloudfront_5xx_error_rate_threshold_percent must be between 1 and 100 inclusive, because the metric it is compared against is a percentage."
  }
}

variable "dead_letter_depth_threshold" {
  description = "Visible messages in any dead-letter queue that raise the messaging alarm. A dead-letter queue is empty in normal operation, so this is the one threshold whose default is the smallest value that can be breached."
  type        = number
  default     = 1

  validation {
    # WHY : Assumptions: the floor of 1 is not a conservative guess, it is the
    #       semantics of the queue. A message reaches a dead-letter queue only
    #       after the configured number of failed receives, so its presence is
    #       already the evidence of a repeated failure and there is no benign
    #       reason for the depth to be non-zero. Raising this threshold does not
    #       reduce noise; it hides messages that have exhausted every retry the
    #       system offers.
    condition     = var.dead_letter_depth_threshold >= 1 && floor(var.dead_letter_depth_threshold) == var.dead_letter_depth_threshold
    error_message = "dead_letter_depth_threshold must be a whole number of 1 or more; a dead-letter queue holds only messages that have exhausted every retry, so any depth above zero is a real failure."
  }
}

variable "batch_failure_threshold" {
  description = "Failed nightly-chain executions within one evaluation period that raise the batch alarm. This is the metric that replaces reading a job log for a non-zero condition code, and it counts executions the state machine itself reported as failed."
  type        = number
  default     = 1

  validation {
    # WHY : Assumptions: the floor of 1 follows from the schedule rather than
    #       from caution: the chain runs once per night, so there is never a
    #       second failure within a period to corroborate the first, and a
    #       threshold above 1 would mean a failed night went unreported.
    #       Trade-offs: this alarm counts only executions the machine reported as
    #       FAILED. A posting step that returned the graded warn status and let
    #       the chain continue is a success by design, per the return-code
    #       semantics recorded in infra/modules/step-functions-batch, so it does
    #       not appear here; the reject stream is where that outcome is visible.
    condition     = var.batch_failure_threshold >= 1 && floor(var.batch_failure_threshold) == var.batch_failure_threshold
    error_message = "batch_failure_threshold must be a whole number of 1 or more; the nightly chain runs once, so a threshold above 1 would leave a failed night unreported."
  }
}

variable "reply_queue_age_threshold_seconds" {
  description = "Oldest-message age that raises a reply-queue alarm. Five seconds is derived from the baseline request/reply expiry contract rather than an invented service objective; the consumer still enforces expiresAt because SQS has no per-message expiry."
  type        = number
  default     = 5

  validation {
    # WHY : Assumptions: whole seconds are required because the SQS metric is
    #       published in seconds. Zero would keep the alarm permanently
    #       breached and a fractional threshold would claim precision the
    #       source metric does not expose.
    condition     = var.reply_queue_age_threshold_seconds >= 1 && floor(var.reply_queue_age_threshold_seconds) == var.reply_queue_age_threshold_seconds
    error_message = "reply_queue_age_threshold_seconds must be a whole number of one second or more."
  }
}

# WHY : Assumptions: this is a DETECTION DEFAULT and not a service objective, and it
#       is the second input in this file whose comparison point is a tuning decision
#       rather than a structural fact -- the database utilisation percentage is the
#       other, and it carries the same caveat. The repository defines no queue-latency
#       objective and none is invented here.
# WHY : Assumptions: the default is one full evaluation period of this module's own
#       default period, so the condition it states is "no consumer took this message
#       within a whole period in which the alarm was looking". Deriving it from the
#       period rather than choosing a round number of minutes keeps the two settings
#       coherent: a caller that shortens the period tightens this alarm in the same
#       proportion, which is the behaviour a reader expects and would otherwise have
#       to arrange by hand.
# WHY : Alternatives Considered: reusing reply_queue_age_threshold_seconds, whose
#       default is five seconds. Rejected because that value is the baseline's
#       request/reply EXPIRY contract, which applies to a reply nobody has consumed
#       and does not apply to inbound work at all; a five-second threshold on a
#       request queue would fire during any ordinary processing backlog and the
#       channel would be ignored.
variable "work_queue_age_threshold_seconds" {
  description = "Oldest-message age that raises a primary work-queue alarm, covering the request queues and the error queue. This is a detection default equal to one full evaluation period, not a latency objective; the repository defines none."
  type        = number
  default     = 300

  validation {
    # WHY : Assumptions: the floor is 60 rather than 1. The lowest value this alarm
    #       can act on is bounded below by the period it is evaluated over, and a
    #       threshold under a minute on a queue drained by a long-polling consumer
    #       would alarm on the poll interval itself. The ceiling is the SQS maximum
    #       message retention of fourteen days, past which no message can still be
    #       waiting for the alarm to observe.
    condition     = var.work_queue_age_threshold_seconds >= 60 && var.work_queue_age_threshold_seconds <= 1209600 && floor(var.work_queue_age_threshold_seconds) == var.work_queue_age_threshold_seconds
    error_message = "work_queue_age_threshold_seconds must be a whole number of seconds from 60 through 1209600, the SQS maximum message retention."
  }
}

# WHY : Trade-offs: false makes a non-empty access-log bucket stop teardown
#       rather than silently deleting its audit trail. A caller can opt in to
#       destructive cleanup explicitly when preserving those objects is not the
#       desired teardown contract.
variable "access_log_bucket_force_destroy" {
  description = "Whether Terraform may remove the shared ALB and S3 access-log destination while it still contains current or noncurrent objects. False preserves the audit trail and makes an operator purge it explicitly before teardown."
  type        = bool
  default     = false
}

# -----------------------------------------------------------------------------
# Credential-rotation functions to watch
# -----------------------------------------------------------------------------
# WHY : Assumptions: the names arrive from the composing root rather than being
#       discovered here, because this module deliberately reads no data source and
#       references no sibling module -- the same reason every other identifier it
#       alarms on is an input. infra/modules/secrets publishes
#       rotation_lambda_name for exactly this wiring.
#       Trade-offs: a set rather than one string, so a root that grows a second
#       rotation function gains an alarm by extending a list instead of by editing
#       this module.
variable "rotation_lambda_function_names" {
  description = "Set of Secrets Manager rotation Lambda function names that receive a non-zero Errors alarm. Empty creates no rotation alarm and is appropriate only when rotation is not provisioned in the composed root."
  type        = set(string)
  default     = []
}
