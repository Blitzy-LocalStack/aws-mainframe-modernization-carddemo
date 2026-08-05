# =============================================================================
# infra/modules/ecs-service/main.tf
# -----------------------------------------------------------------------------
# Purpose:
#   Carries one CardDemo bounded context onto ECS Fargate with its log group,
#   roles, task definition, optional target group and service, and optional
#   autoscaling policy. Environment roots are required to instantiate this
#   reusable module once per bounded context, with batch using the task-only
#   shape defined by its conditional resources.
#
# Parameters:
#   None are declared here. Every input this file reads is declared in the
#   sibling variables.tf, which is the one place a caller's arguments are
#   accepted, typed and validated. Fifty-six variables are declared there
#   and every one of them is consumed inside this module, which has no other
#   consumer of them. That reconciliation is a standing constraint
#   rather than tidiness: this directory is never applied directly, so
#   .github/workflows/infra-ci.yml validates it only transitively, through
#   `init -backend=false` and `validate` on infra/bootstrap, infra/envs/dev
#   and infra/envs/prod. A reference here to an input variables.tf does not
#   declare would therefore surface at the calling root rather than here, and
#   tflint's terraform_unused_declarations rule fires in the other direction
#   on any variable, local or data source that nothing consumes.
#
# Return values:
#   None are declared here either; outputs.tf owns those. One of them is a
#   genuine cross-module contract and is the reason the target group lives in
#   this module at all: the target-group ARN. infra/modules/alb owns the load
#   balancer, its listener and the per-service listener rules, and it attaches
#   that ARN as a rule's forward target. The boundary is restated at the
#   target group below, because it is the single point where two modules meet.
#
# Exceptions or errors:
#   - A task_cpu and task_memory pair outside Fargate's permitted matrix is
#     rejected by RegisterTaskDefinition at apply. variables.tf validates the
#     whole matrix cross-variable, so the normal failure is the earlier one at
#     plan; the apply-time rejection is only the backstop.
#   - A task role missing a permission the application needs does not fail the
#     apply at all. The task starts, the application cannot reach its queue,
#     secret or key, and the symptom is a task that reaches RUNNING and then
#     fails its target-group health check until the deployment circuit breaker
#     returns the service to its previous task set. That indirection is why
#     the task role's contents are the caller's to declare.
#   - A target group still referenced by a listener rule in alb cannot be
#     destroyed, so a change to an immutable target-group attribute fails the
#     apply unless the replacement is created first.
#   - A service created before its execution role's inline policy exists fails
#     ECS's own permission validation with an error naming the role, because
#     the service references the role and not the policy.
#
# WHY (non-obvious design decisions):
#   - Refactoring Rationale: the blocks below are ordered by dependency --
#     locals and data, then the log group, the two roles, the task definition,
#     the target group, the service, and finally autoscaling -- rather than
#     alphabetically. Read top to bottom, no reference appears before the
#     thing it refers to, which mirrors how the AWS resources actually
#     compose: autoscaling addresses the service, the service addresses the
#     task definition and the target group, and the execution policy
#     addresses the log group. Alphabetical order was the alternative and
#     would have opened with the autoscaling policy and split the two roles
#     around the target group.
#   - Assumptions: three of the resources are conditional, because one of the
#     eight instantiations is not a long-running service at all. The gating is
#     explained once, at the target group, and reused at the service and the
#     autoscaling pair.
# =============================================================================

# -----------------------------------------------------------------------------
# Composed names, tags and container-definition fragments.
# -----------------------------------------------------------------------------

locals {
  # WHY : Assumptions: variables.tf bounds name_prefix at twelve characters and
  #       service_name at fourteen, and environment is dev or prod, so this
  #       composed name is at most 12 + 1 + 14 + 1 + 4 = 32 characters. The
  #       target-group name below reserves nine characters for its separator and
  #       replacement hash, so this full name is a readable source stem rather
  #       than the final ELB name. The arithmetic remains explicit because the
  #       same value names the ECS service and task family without truncation.
  resource_name = "${var.name_prefix}-${var.service_name}-${var.environment}"

  # WHY : Refactoring Rationale: create_before_destroy cannot create a
  #       replacement target group under the old group's name. The former
  #       deterministic name therefore made every replacement fail before the
  #       listener rule could move. A stable hash of the configured
  #       replacement-forcing attributes gives a changed VPC/protocol/port a
  #       different name while leaving an unchanged plan stable.
  # WHY : Trade-offs: eight hexadecimal characters make accidental collision
  #       negligible without consuming the 32-character target-group budget.
  #       The readable stem is capped at 23 characters, with a trailing hyphen
  #       removed before the separator and hash are appended.
  target_group_replacement_hash = substr(sha1(join("|", [
    var.vpc_id,
    tostring(var.container_port),
    var.target_protocol,
    "ip",
  ])), 0, 8)
  target_group_name_stem = trimsuffix(substr(local.resource_name, 0, 23), "-")
  target_group_name      = "${local.target_group_name_stem}-${local.target_group_replacement_hash}"

  # WHY : Trade-offs: coalesce resolves the null default here rather than in
  #       variables.tf, because a variable default cannot reference another
  #       variable. The seven services with no cross-module contract get
  #       service_name; the batch instantiation sets container_name explicitly,
  #       because infra/modules/step-functions-batch addresses that container
  #       BY NAME in its run-task container overrides, making the name a
  #       contract both modules have to agree on.
  container_name = coalesce(var.container_name, var.service_name)

  # WHY : Refactoring Rationale: the log-group name is composed here rather
  #       than left for the provider to generate, because the execution role's
  #       policy has to scope logs:PutLogEvents to this one group's ARN. The
  #       /aws/ecs/ prefix is what files all eight services under one path, so
  #       an operator reading logs during the batch window is not hunting
  #       across unrelated prefixes.
  log_group_name = "/aws/ecs/${local.resource_name}"

  # WHY : Assumptions: the log-group ARN is normalised before use because the
  #       two Resource forms the policy needs differ only by a :* suffix, and
  #       the ARN the provider exposes has not consistently carried one.
  #       Trimming first and appending explicitly makes both statements correct
  #       under either form, rather than producing a doubled suffix that
  #       matches nothing.
  log_group_arn = trimsuffix(aws_cloudwatch_log_group.this.arn, ":*")

  # WHY : Assumptions: the calling root configures default_tags on its provider
  #       and the provider merges those into every taggable resource, so this
  #       merge exists only for the module's own two keys. var.tags is merged
  #       last, which is what lets a caller override either of them.
  tags = merge({
    Environment = var.environment
    Service     = var.service_name
  }, var.tags)

  # WHY : Refactoring Rationale: every online service already exposes
  #       `/actuator/prometheus`, but without a scraper those meters stay inside
  #       the task. A sidecar shares the task network namespace, so it can scrape
  #       loopback and receive OTLP traces without exposing either endpoint
  #       through a security group or a load-balancer route.
  telemetry_receivers = merge(
    {
      otlp = {
        protocols = {
          grpc = {
            endpoint = "0.0.0.0:4317"
          }
          http = {
            endpoint = "0.0.0.0:4318"
          }
        }
      }
    },
    var.create_service ? {
      prometheus = {
        config = {
          scrape_configs = [{
            job_name        = local.resource_name
            scrape_interval = "60s"
            metrics_path    = "/actuator/prometheus"
            scheme          = "https"
            static_configs = [{
              targets = ["127.0.0.1:${var.container_port}"]
            }]
            tls_config = {
              # WHY : Assumptions: the application certificate names the
              #       internal service hostname, while the task-local scrape
              #       deliberately uses loopback so the metrics endpoint is
              #       never exposed through a security-group rule. The TLS
              #       channel still protects bytes inside the task namespace;
              #       hostname verification cannot succeed against 127.0.0.1.
              insecure_skip_verify = true
            }
          }]
        }
      }
    } : {},
  )

  telemetry_processors = {
    memory_limiter = {
      check_interval  = "5s"
      limit_mib       = 128
      spike_limit_mib = 32
    }
    resource = {
      attributes = [
        {
          key    = "service.name"
          action = "upsert"
          value  = var.service_name
        },
        {
          key    = "deployment.environment.name"
          action = "upsert"
          value  = var.environment
        },
        {
          key    = "service.version"
          action = "upsert"
          value  = lookup(var.environment_variables, "CARDDEMO_VERSION", "unspecified")
        },
      ]
    }
    tail_sampling = {
      decision_wait = "10s"
      policies = [
        {
          name = "errors"
          type = "status_code"
          status_code = {
            status_codes = ["ERROR"]
          }
        },
        {
          name = "successful-sample"
          type = "probabilistic"
          probabilistic = {
            sampling_percentage = var.telemetry_success_sample_percentage
          }
        },
      ]
    }
    batch = {}
  }

  telemetry_exporters = {
    awsxray = {}
    awsemf = {
      namespace               = "CardDemo"
      log_group_name          = local.log_group_name
      log_stream_name         = "${var.service_name}-telemetry"
      dimension_rollup_option = "NoDimensionRollup"
      resource_to_telemetry_conversion = {
        enabled = true
      }
    }
  }

  telemetry_pipelines = merge(
    {
      traces = {
        receivers  = ["otlp"]
        processors = ["memory_limiter", "resource", "tail_sampling", "batch"]
        exporters  = ["awsxray"]
      }
    },
    var.create_service ? {
      metrics = {
        receivers  = ["prometheus"]
        processors = ["memory_limiter", "resource", "batch"]
        exporters  = ["awsemf"]
      }
    } : {},
  )

  telemetry_collector_configuration = yamlencode({
    receivers  = local.telemetry_receivers
    processors = local.telemetry_processors
    exporters  = local.telemetry_exporters
    service = {
      telemetry = {
        logs = {
          level = "warn"
        }
      }
      pipelines = local.telemetry_pipelines
    }
  })

  # WHY : Assumptions: the OpenTelemetry starter is on every service classpath
  #       through common-lib, but common defaults leave export disabled for
  #       local runs. These task-only variables activate OTLP explicitly and
  #       point it at loopback; no collector endpoint is exposed outside the
  #       task. Metrics remain Prometheus-scraped to avoid exporting the same
  #       meter through both OTLP and the collector's Prometheus receiver.
  telemetry_environment_variables = var.enable_telemetry_collector ? {
    OTEL_EXPORTER_OTLP_TRACES_ENDPOINT = "http://127.0.0.1:4318/v1/traces"
    OTEL_EXPORTER_OTLP_TRACES_PROTOCOL = "http/protobuf"
    OTEL_LOGS_EXPORTER                 = "none"
    OTEL_METRICS_EXPORTER              = "none"
    OTEL_RESOURCE_ATTRIBUTES           = "deployment.environment.name=${var.environment},service.version=${lookup(var.environment_variables, "CARDDEMO_VERSION", "unspecified")}"
    OTEL_SERVICE_NAME                  = var.service_name
    OTEL_TRACES_EXPORTER               = "otlp"
    OTEL_TRACES_SAMPLER                = "always-on"
  } : {}

  effective_environment_variables = merge(
    var.environment_variables,
    local.telemetry_environment_variables,
  )

  # WHY : Assumptions: ECS stores the environment array in the order supplied,
  #       and the provider compares the rendered container definitions as a
  #       string, so the order has to be stable across plans or every plan
  #       proposes a new task-definition revision for no change. Terraform
  #       already iterates a map in lexical key order; sorting the keys makes
  #       that dependence visible, so a later edit that iterates something
  #       other than a map cannot lose the property silently.
  container_environment = [
    for key in sort(keys(local.effective_environment_variables)) : {
      name  = key
      value = local.effective_environment_variables[key]
    }
  ]

  # WHY : Assumptions: ECS resolves a Parameter Store ARN and a Secrets Manager
  #       ARN through the same container secrets entry -- one name and one
  #       valueFrom -- which is why two typed inputs collapse into one list
  #       here. They stay two inputs at the module boundary because the
  #       execution role needs ssm:GetParameters for one store and
  #       secretsmanager:GetSecretValue for the other, and those same ARNs are
  #       what scope both statements.
  #       Trade-offs: merge gives secret_arns precedence, so a name declared in
  #       both maps resolves from Secrets Manager alone. Rejecting the overlap
  #       instead would need a precondition, and the precedence is
  #       deterministic and documented, so the ambiguity is resolved rather
  #       than merely permitted.
  ssm_secret_sources = {
    for name, arn in var.ssm_parameter_arns : name => {
      value_from   = arn
      resource_arn = arn
    }
  }

  secret_sources = merge(local.ssm_secret_sources, var.secret_arns)

  # WHY : Assumptions: these names are a property of the SERVICE IMAGES, not of the
  #       calling root: each one is read by a placeholder in that service's
  #       application.yml. Stating them here lets the precondition at the task
  #       definition refuse two failures that otherwise surface only at run time --
  #       a name no image reads (which silently does nothing) and a name an image
  #       requires but the root omitted (which starts a task that cannot serve).
  #       Trade-offs: the inventory has to be extended whenever a service learns a
  #       new setting, which is deliberate friction: the alternative is an open
  #       schema in which a typo in a root is indistinguishable from a setting.
  # WHY : Refactoring Rationale: configuration names are enumerated here rather
  #       than admitted by namespace. A namespace check can reject an obvious
  #       typo and still accepts a new CARDDEMO_* key that nobody reviewed,
  #       including one that carries protected material in the clear-text
  #       environment array. The exact inventories below are derived from the
  #       committed Spring configuration files and the ETL mask contract; adding
  #       a setting therefore requires a module diff beside the application diff.
  plain_environment_names = toset([
    "AWS_DEFAULT_REGION",
    "AWS_REGION",
    "CARDDEMO_DB_ALTERNATE_USERS",
    "CARDDEMO_DB_SSL_MODE",
    "CARDDEMO_DB_SSL_ROOT_CERT",
    "CARDDEMO_ENVIRONMENT",

    # WHY : Assumptions: this carries the NAME of the Parameter Store entry the
    #       quiesce and resume steps toggle around the batch window, not a value. It
    #       is a plain variable rather than a parameter injection precisely because
    #       an online service must read that entry at request time to decide whether
    #       writes are currently accepted; injecting the value once at task start
    #       would freeze the answer for the life of the task.
    "CARDDEMO_ONLINE_WRITES_PARAMETER",
    "CARDDEMO_PARAMETER_PREFIX",
    "CARDDEMO_SERVER_TLS_ENABLED",
    "CARDDEMO_TRUSTED_PROXY_PATTERN",
    "CARDDEMO_VERSION",
    "JAVA_TOOL_OPTIONS",
    "JDK_JAVA_OPTIONS",
    "LOGGING_LEVEL_ROOT",
    "MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE",
    "SERVER_PORT",
    "SPRING_PROFILES_ACTIVE",
    "TZ",
  ])

  parameter_environment_names = toset([
    # WHY : Assumptions: the three CARDDEMO_ACCOUNT_INQUIRY_* names and
    #       CARDDEMO_MESSAGING_PAUTH_REQUEST_QUEUE are admitted here because both
    #       environment roots publish them as runtime parameters -- account-service
    #       owns the COACCT01 inquiry request, reply and error queues, and
    #       authorization-service reads the pending-authorization request queue at
    #       its application.yml. A name a root supplies but this set omits is
    #       refused by the task-definition precondition below, and that refusal
    #       surfaces only at plan time against a real account, because
    #       `terraform validate` does not evaluate a lifecycle precondition.
    #       Admitting a name is not requiring it: the required_* maps below decide
    #       which services must carry which, and none of these four is required,
    #       so a root that publishes no queue parameter still plans.
    "CARDDEMO_ACCOUNT_INQUIRY_ERROR_QUEUE",
    "CARDDEMO_ACCOUNT_INQUIRY_REPLY_QUEUE",
    "CARDDEMO_ACCOUNT_INQUIRY_REQUEST_QUEUE",
    "CARDDEMO_AUTH_COGNITO_CLIENT_ID",
    "CARDDEMO_AUTH_COGNITO_USER_POOL_ID",
    "CARDDEMO_COGNITO_APP_CLIENT_ID",
    "CARDDEMO_CONFIG_PREFIX",
    "CARDDEMO_MESSAGING_PAUTH_REQUEST_QUEUE",
    "CARDDEMO_REFERENCE_INQUIRY_ERROR_QUEUE",
    "CARDDEMO_REFERENCE_INQUIRY_REPLY_QUEUE",
    "CARDDEMO_REFERENCE_INQUIRY_REQUEST_QUEUE",
    "CARDDEMO_REPORTING_S3_OUTPUT_BUCKET",
    "CARDDEMO_REPORTING_STEP_FUNCTIONS_STATE_MACHINE_ARN",
    "CARDDEMO_SECURITY_JWT_EXPECTED_CLIENT_ID",
    "SPRING_DATASOURCE_URL",
    "SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI",
  ])

  secret_environment_names = toset([
    # WHY : Assumptions: the client IDENTIFIER is injected from the same Secrets
    #       Manager document as the client secret rather than from Parameter Store,
    #       because both are JSON keys of the one entry Cognito's app client
    #       produces. Splitting them across two stores would mean two things to keep
    #       in step through a client-secret rotation.
    "CARDDEMO_AUTH_COGNITO_CLIENT_ID",
    "CARDDEMO_AUTH_COGNITO_CLIENT_SECRET",
    "CARDDEMO_MASK_HMAC_KEY",
    "CARDDEMO_SERVER_TLS_CERTIFICATE",
    "CARDDEMO_SERVER_TLS_PRIVATE_KEY",
    "SPRING_DATASOURCE_PASSWORD",
    "SPRING_DATASOURCE_USERNAME",
  ])

  # WHY : Assumptions: the reporting service is the only HTTP service whose
  #       trusted-proxy expression, Cognito audience and PEM pair use the
  #       canonical CARDDEMO_* names below. Batch is the only service that may
  #       launch the ETL image and therefore the only task allowed to receive
  #       the mask HMAC key. Binding these names to one service prevents a root
  #       from accidentally distributing either capability to every task.
  required_plain_environment_names = {
    auth        = toset([])
    account     = toset([])
    card        = toset([])
    transaction = toset([])
    reference   = toset([])
    # WHY : Refactoring Rationale: batch was listed as requiring
    #       CARDDEMO_DB_ALTERNATE_USERS and CARDDEMO_PARAMETER_PREFIX, and reporting
    #       as requiring CARDDEMO_TRUSTED_PROXY_PATTERN. Verified against the
    #       integrated tree: the first two are read only by
    #       carddemo_migration.config, which runs in the data-migration image and
    #       still requires them below, and the third resolves from a default in
    #       reporting-service's application.yml. A name belongs here only when the
    #       image cannot behave correctly without it, because requiring a value the
    #       image already knows forces every root to restate it.
    batch         = toset([])
    authorization = toset([])
    reporting     = toset([])
    data-migration = toset([
      "CARDDEMO_DB_ALTERNATE_USERS",
      "CARDDEMO_DB_SSL_MODE",
      "CARDDEMO_PARAMETER_PREFIX",
    ])
  }

  required_parameter_environment_names = {
    # WHY : Refactoring Rationale: CARDDEMO_AUTH_COGNITO_CLIENT_ID was required
    #       here, in the Parameter Store channel, and is now required in the
    #       Secrets Manager channel below instead. Both channels admit the name,
    #       and the deciding fact is how the value exists: the app client's
    #       identifier and its secret are two JSON keys of ONE Secrets Manager
    #       entry, so both roots inject them from that single entry through
    #       secret_arns. Requiring the identifier as a parameter obliged a root to
    #       publish a second copy of a value it already delivers, and made auth
    #       fail the required-name precondition at plan time -- which
    #       `terraform validate` cannot report, because it does not evaluate a
    #       lifecycle block. The pool identifier stays here: it is not a secret and
    #       both roots do publish it as a parameter.
    auth = toset([
      "CARDDEMO_AUTH_COGNITO_USER_POOL_ID",
      "CARDDEMO_SECURITY_JWT_EXPECTED_CLIENT_ID",
      "SPRING_DATASOURCE_URL",
      "SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI",
    ])
    account = toset([
      "CARDDEMO_SECURITY_JWT_EXPECTED_CLIENT_ID",
      "SPRING_DATASOURCE_URL",
      "SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI",
    ])
    card = toset([
      "CARDDEMO_SECURITY_JWT_EXPECTED_CLIENT_ID",
      "SPRING_DATASOURCE_URL",
      "SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI",
    ])
    transaction = toset([
      "CARDDEMO_SECURITY_JWT_EXPECTED_CLIENT_ID",
      "SPRING_DATASOURCE_URL",
      "SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI",
    ])
    reference = toset([
      "CARDDEMO_REFERENCE_INQUIRY_ERROR_QUEUE",
      "CARDDEMO_REFERENCE_INQUIRY_REPLY_QUEUE",
      "CARDDEMO_REFERENCE_INQUIRY_REQUEST_QUEUE",
      "CARDDEMO_SECURITY_JWT_EXPECTED_CLIENT_ID",
      "SPRING_DATASOURCE_URL",
      "SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI",
    ])
    batch = toset(["SPRING_DATASOURCE_URL"])
    authorization = toset([
      "CARDDEMO_SECURITY_JWT_EXPECTED_CLIENT_ID",
      "SPRING_DATASOURCE_URL",
      "SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI",
    ])
    # WHY : Refactoring Rationale: this set named CARDDEMO_COGNITO_APP_CLIENT_ID
    #       and now names CARDDEMO_SECURITY_JWT_EXPECTED_CLIENT_ID instead. The
    #       former is the name the precondition below already records as one "no
    #       root injects", and requiring it made the reporting workload fail that
    #       precondition at plan time -- a failure `terraform validate` cannot
    #       report, because it does not evaluate a lifecycle block. The latter is
    #       the name reporting-service actually binds with no fallback, at its
    #       application.yml carddemo.security.jwt.expected-client-id, and both
    #       roots publish it for every database workload, so requiring it matches
    #       what the service needs and what the roots supply. The app-client name
    #       stays ADMISSIBLE in parameter_environment_names above, because
    #       JwtDecoderConfig reads it as an optional override.
    reporting = toset([
      "CARDDEMO_REPORTING_S3_OUTPUT_BUCKET",
      "CARDDEMO_SECURITY_JWT_EXPECTED_CLIENT_ID",
      "CARDDEMO_REPORTING_STEP_FUNCTIONS_STATE_MACHINE_ARN",
      "SPRING_DATASOURCE_URL",
      "SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI",
    ])
    data-migration = toset([])
  }

  required_secret_environment_names = {
    auth = toset([
      "CARDDEMO_AUTH_COGNITO_CLIENT_ID",
      "CARDDEMO_AUTH_COGNITO_CLIENT_SECRET",
      "CARDDEMO_SERVER_TLS_CERTIFICATE",
      "CARDDEMO_SERVER_TLS_PRIVATE_KEY",
      "SPRING_DATASOURCE_USERNAME",
      "SPRING_DATASOURCE_PASSWORD",
    ])
    account = toset([
      "CARDDEMO_SERVER_TLS_CERTIFICATE",
      "CARDDEMO_SERVER_TLS_PRIVATE_KEY",
      "SPRING_DATASOURCE_USERNAME",
      "SPRING_DATASOURCE_PASSWORD",
    ])
    card = toset([
      "CARDDEMO_SERVER_TLS_CERTIFICATE",
      "CARDDEMO_SERVER_TLS_PRIVATE_KEY",
      "SPRING_DATASOURCE_USERNAME",
      "SPRING_DATASOURCE_PASSWORD",
    ])
    transaction = toset([
      "CARDDEMO_SERVER_TLS_CERTIFICATE",
      "CARDDEMO_SERVER_TLS_PRIVATE_KEY",
      "SPRING_DATASOURCE_USERNAME",
      "SPRING_DATASOURCE_PASSWORD",
    ])
    reference = toset([
      "CARDDEMO_SERVER_TLS_CERTIFICATE",
      "CARDDEMO_SERVER_TLS_PRIVATE_KEY",
      "SPRING_DATASOURCE_USERNAME",
      "SPRING_DATASOURCE_PASSWORD",
    ])
    # WHY : Refactoring Rationale: batch was listed as also requiring
    #       CARDDEMO_MASK_HMAC_KEY. Verified against the integrated tree: the keyed
    #       tag that variable names is derived in
    #       carddemo_migration.copybook.layouts, which runs in the data-migration
    #       image and still requires it below; no Java module reads it.
    batch = toset(["SPRING_DATASOURCE_USERNAME", "SPRING_DATASOURCE_PASSWORD"])
    authorization = toset([
      "CARDDEMO_SERVER_TLS_CERTIFICATE",
      "CARDDEMO_SERVER_TLS_PRIVATE_KEY",
      "SPRING_DATASOURCE_USERNAME",
      "SPRING_DATASOURCE_PASSWORD",
    ])
    reporting = toset([
      "SPRING_DATASOURCE_USERNAME",
      "SPRING_DATASOURCE_PASSWORD",
      "CARDDEMO_SERVER_TLS_CERTIFICATE",
      "CARDDEMO_SERVER_TLS_PRIVATE_KEY",
    ])
    data-migration = toset(["CARDDEMO_MASK_HMAC_KEY"])
  }

  container_secrets = [
    for key in sort(keys(local.secret_sources)) : {
      name      = key
      valueFrom = local.secret_sources[key].value_from
    }
  ]


  # WHY : Assumptions: a volume name cannot contain a separator, so each path
  #       becomes a name by dropping the leading slash and replacing the rest
  #       with hyphens -- /var/cache/app becomes var-cache-app. Keying a map by
  #       that derived name is deliberate: two different paths that derived the
  #       same name would fail at plan with a duplicate-key error naming the
  #       collision, instead of at apply with a rejection from ECS.
  writable_volumes = {
    for path in var.writable_mount_paths :
    replace(trimprefix(path, "/"), "/", "-") => path
  }

  # WHY : Assumptions: readonly_root_filesystem defaults to true, so a JVM
  #       writing a heap dump on out-of-memory, a temporary file or its own
  #       scratch data needs at least one writable path. Each entry becomes one
  #       Fargate ephemeral volume and the matching mount point, so the
  #       read-only root holds everywhere except the paths a caller names.
  container_mount_points = [
    for name, path in local.writable_volumes : {
      containerPath = path
      readOnly      = false
      sourceVolume  = name
    }
  ]

  # WHY : Assumptions: ECS rejects health_check_grace_period_seconds outright
  #       on a service with no load balancer, so the value has to become null
  #       rather than merely be ignored. The grace period only means anything
  #       while a target group is deciding whether a newly started task is
  #       healthy.
  health_check_grace_period = (
    var.attach_load_balancer ? var.health_check_grace_period_seconds : null
  )
}

# -----------------------------------------------------------------------------
# Discovered account and region context.
# -----------------------------------------------------------------------------

# Assumptions: the awslogs driver needs the region as a literal string
#       inside the container definition, and this module must not carry one.
#       Reading it from the provider is what lets the same module text run in
#       whatever region the root chooses. The region attribute is read rather
#       than name, which hashicorp/aws marks deprecated.
data "aws_region" "current" {}

# Assumptions: task-definition family ARNs include the active partition. Reading
# it from the provider keeps the module portable without committing a partition
# literal or parsing it out of another identifier.
data "aws_partition" "current" {}

# WHY : Assumptions: the account identifier is needed for the source-account
#       condition on the trust policy below, and discovering it is the only way
#       to have that condition without writing a twelve-digit account number
#       into the repository.
data "aws_caller_identity" "current" {}

# -----------------------------------------------------------------------------
# IAM policy documents.
# -----------------------------------------------------------------------------

# Assumptions: ecs-tasks.amazonaws.com is the principal for both roles.
#       ECS assumes the execution role to start the task, before the container
#       exists, and assumes the task role on the application's behalf once it
#       is running. One trust policy for two roles is correct precisely because
#       the difference between them is what each is permitted to do, not who
#       may assume it.
data "aws_iam_policy_document" "task_assume_role" {
  statement {
    sid     = "AllowEcsTasksAssumeRole"
    effect  = "Allow"
    actions = ["sts:AssumeRole"]

    principals {
      identifiers = ["ecs-tasks.amazonaws.com"]
      type        = "Service"
    }

    # WHY : Assumptions: without a source-account condition the trust policy
    #       names a service principal that any account's ECS could in
    #       principle present, which is the confused-deputy shape. Binding it
    #       to the discovered account means only this account's ECS can assume
    #       either role. The value is read from a data source rather than
    #       written as a literal so that no account number reaches the
    #       repository, which is the same reasoning that keeps every endpoint
    #       and credential out of it.
    condition {
      test     = "StringEquals"
      values   = [data.aws_caller_identity.current.account_id]
      variable = "aws:SourceAccount"
    }
  }
}

# Alternatives Considered: attaching the AWS-managed
#       AmazonECSTaskExecutionRolePolicy was rejected. That policy grants ECR
#       pull and log write against every resource in the account, so each of
#       the eight services would be able to pull every other service's image
#       and write into every other service's log group. Every statement below
#       instead names its actions and scopes them to the ARNs this
#       instantiation was handed, which is what makes the per-service boundary
#       real rather than nominal. This matters because RACF has no cloud
#       analogue and is not being ported: least-privilege task roles are what
#       replace it, so a convenience policy here would quietly remove the
#       control it stands in for.
data "aws_iam_policy_document" "execution" {
  # WHY : Assumptions: this is the only statement whose Resource is a wildcard,
  #       and the reason is the AWS API rather than convenience.
  #       GetAuthorizationToken returns a registry-wide token and accepts no
  #       resource qualifier, so there is nothing narrower that could be
  #       written. Note what stays narrow even here: the ACTION is named, never
  #       ecr:*. A wildcard action is what the pipeline's policy scan rejects,
  #       and neither role in this module contains one.
  statement {
    sid       = "AllowEcrAuthorization"
    effect    = "Allow"
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"]
  }

  # WHY : Assumptions: these three actions are the image pull itself, and they
  #       are scoped to the single repository this service's image lives in, so
  #       a compromised execution role cannot pull another bounded context's
  #       image. The repository ARN arrives as an input from infra/modules/ecr
  #       through the root rather than being reconstructed from the image URI,
  #       because parsing an ARN out of a registry reference would encode the
  #       registry hostname format in this module.
  statement {
    sid    = "AllowEcrImagePull"
    effect = "Allow"

    actions = [
      "ecr:BatchCheckLayerAvailability",
      "ecr:BatchGetImage",
      "ecr:GetDownloadUrlForLayer",
    ]

    resources = [var.ecr_repository_arn]
  }

  # WHY : Assumptions: logs:CreateLogGroup is deliberately absent, and its
  #       absence is a decision rather than an omission. Terraform creates the
  #       group below with a retention period and a key, so a task able to
  #       create groups could only ever create an unmanaged one with neither --
  #       a permission with no legitimate use here, and one that would let a
  #       misconfigured service silently log outside the retention policy. Both
  #       Resource forms are required: the group ARN authorises writes to the
  #       group, and the :* suffix covers the log streams inside it.
  statement {
    sid    = "AllowLogWrite"
    effect = "Allow"

    actions = [
      "logs:CreateLogStream",
      "logs:PutLogEvents",
    ]

    resources = [
      local.log_group_arn,
      "${local.log_group_arn}:*",
    ]
  }

  # WHY : Assumptions: dynamic with a one-or-zero element for_each is what
  #       makes an absent input produce no statement at all. The alternatives
  #       are both worse: a statement with an empty Resource list is not a
  #       valid policy, and a statement with a wildcard Resource would defeat
  #       the scoping this whole document exists for. The same construction is
  #       reused for the two remaining stores below.
  dynamic "statement" {
    for_each = length(var.ssm_parameter_arns) > 0 ? [true] : []

    content {
      sid       = "AllowSsmParameterRead"
      effect    = "Allow"
      actions   = ["ssm:GetParameters"]
      resources = values(var.ssm_parameter_arns)
    }
  }

  dynamic "statement" {
    for_each = length(var.secret_arns) > 0 ? [true] : []

    content {
      sid     = "AllowSecretRead"
      effect  = "Allow"
      actions = ["secretsmanager:GetSecretValue"]
      resources = distinct([
        for source in values(var.secret_arns) : source.resource_arn
      ])
    }
  }

  # WHY : Assumptions: every Secrets Manager entry injected into a task is
  #       protected by a project CMK. Decrypt is therefore constrained both to
  #       exact keys and to calls arriving through Secrets Manager for one of
  #       this task's exact secret ARNs; the execution role cannot use the same
  #       key directly against an unrelated ciphertext.
  dynamic "statement" {
    for_each = length(var.execution_secret_kms_key_arns) > 0 && length(var.secret_arns) > 0 ? [true] : []

    content {
      sid       = "AllowKmsDecrypt"
      effect    = "Allow"
      actions   = ["kms:Decrypt"]
      resources = var.execution_secret_kms_key_arns

      condition {
        test     = "StringEquals"
        variable = "kms:ViaService"
        values   = ["secretsmanager.${data.aws_region.current.region}.amazonaws.com"]
      }

      condition {
        test     = "StringLike"
        variable = "kms:EncryptionContext:SecretARN"
        values   = distinct([for source in values(var.secret_arns) : source.policy_arn])
      }
    }
  }
}

# WHY : Refactoring Rationale: a request/reply consumer may read a
#       replyToQueueUrl attribute, but that input is routing data and never an
#       authorization decision. The caller passes the queues this one service is
#       allowed to use; IAM then denies any attempted send, receive or delete
#       outside those lists even if application validation regresses.
data "aws_iam_policy_document" "task_sqs" {
  count = length(var.sqs_send_queue_arns) > 0 || length(var.sqs_receive_queue_arns) > 0 ? 1 : 0

  dynamic "statement" {
    for_each = length(var.sqs_send_queue_arns) > 0 ? [true] : []

    content {
      sid       = "AllowExactQueueSend"
      effect    = "Allow"
      actions   = ["sqs:SendMessage"]
      resources = sort(tolist(var.sqs_send_queue_arns))
    }
  }

  dynamic "statement" {
    for_each = length(var.sqs_receive_queue_arns) > 0 ? [true] : []

    content {
      sid    = "AllowExactQueueConsume"
      effect = "Allow"
      actions = [
        "sqs:ChangeMessageVisibility",
        "sqs:DeleteMessage",
        "sqs:GetQueueAttributes",
        "sqs:GetQueueUrl",
        "sqs:ReceiveMessage",
      ]
      resources = sort(tolist(var.sqs_receive_queue_arns))
    }
  }
}


# -----------------------------------------------------------------------------
# Log destination.
# -----------------------------------------------------------------------------

# Refactoring Rationale: the baseline wrote job and console output to the
#       JES spool through SYSOUT and SYSPRINT DD statements, which meant the
#       output was readable only from the system the job ran on. One CloudWatch
#       log group per service is the equivalent destination, and
#       infra/modules/observability builds its dashboards and alarms on top of
#       these groups rather than creating a parallel set of its own -- which is
#       why the group is created here, where the service that writes to it is
#       defined, rather than centrally.
resource "aws_cloudwatch_log_group" "this" {
  name = local.log_group_name

  # WHY : Trade-offs: retention is one of only three things the two environment
  #       roots are permitted to differ on -- with task count and task size --
  #       and topology is never one of them. A short dev retention costs less
  #       to store; a long prod retention is what makes an incident
  #       investigable after the fact, and the two pull in opposite directions,
  #       which is exactly why it is an input rather than a constant.
  retention_in_days = var.log_retention_in_days

  # WHY : Assumptions: null selects the CloudWatch Logs AWS-managed key, which
  #       is still encryption at rest; a customer-managed key ARN from
  #       infra/modules/kms is what additionally makes the rotation schedule
  #       and the key's own audit trail this account's. Either value is an
  #       improvement rather than a port, because the baseline defined every
  #       VSAM file with RECOVERY(NONE) and no encryption at all.
  kms_key_id = var.log_group_kms_key_arn

  tags = local.tags
}

# -----------------------------------------------------------------------------
# Task execution role and application task role.
# -----------------------------------------------------------------------------

# Assumptions: this is deliberately a different role from the task role
#       below, and the split is not ceremonial. ECS uses this one before the
#       container exists, so what it needs is fully determined by this module's
#       own resources and is therefore this module's to compose. What the
#       application needs once it is running is not.
resource "aws_iam_role" "execution" {
  name                 = "${local.resource_name}-execution"
  description          = "ECS task execution role for ${local.resource_name}."
  assume_role_policy   = data.aws_iam_policy_document.task_assume_role.json
  permissions_boundary = var.permissions_boundary_arn
  tags                 = local.tags

  lifecycle {
    precondition {
      condition     = split(":", var.permissions_boundary_arn)[4] == data.aws_caller_identity.current.account_id
      error_message = "permissions_boundary_arn must belong to the same AWS account as the ECS roles."
    }
  }
}

# Alternatives Considered: a standalone aws_iam_policy plus an attachment
#       was rejected. The document is generated from this instantiation's own
#       ARNs and is meaningful for no other role, so a managed policy would add
#       an independently addressable object that outlives the role and could be
#       attached elsewhere. An inline policy is deleted with the role, which
#       matches the lifetime the permissions actually have.
resource "aws_iam_role_policy" "execution" {
  name   = "${local.resource_name}-execution"
  role   = aws_iam_role.execution.id
  policy = data.aws_iam_policy_document.execution.json
}

# Alternatives Considered: composing a union of every service's needs
#       inside this module was rejected outright. Because one module body has
#       to satisfy all eight instantiations, that union would grant the
#       reporting service the authorization service's queues and the auth
#       service's secrets -- every service every other service's access, which
#       is the exact opposite of the per-service least privilege that stands in
#       for RACF here. The caller passes what its own service needs -- its own
#       queues, secrets and key usage -- while the telemetry statement below is
#       invariant across all services and contains no business resource. No
#       wildcard action reaches this role; the sole wildcard Resource is on the
#       two X-Ray ingestion actions, which do not support resource scoping.
resource "aws_iam_role" "task" {
  name                 = "${local.resource_name}-task"
  description          = "Application task role for ${local.resource_name}."
  assume_role_policy   = data.aws_iam_policy_document.task_assume_role.json
  permissions_boundary = var.permissions_boundary_arn
  tags                 = local.tags
}

# WHY : Refactoring Rationale: the task policy is assembled from typed
#       capability inputs above instead of accepting arbitrary JSON or managed
#       policy ARNs. The previous generic channels let a caller grant wildcard
#       actions and resources while still satisfying this module's validation;
#       a typed queue, bucket, state-machine, user-pool or key ARN now selects a
#       fixed minimum action set and no caller can widen it from a tfvars file.
resource "aws_iam_role_policy" "task" {
  count = var.create_task_role_policy ? 1 : 0

  name   = "${local.resource_name}-task"
  role   = aws_iam_role.task.id
  policy = var.task_role_policy_json
}

# WHY : Assumptions: absent when both queue sets are empty, because an inline
#       policy with no statements is invalid rather than harmless. Kept separate
#       from task_role_policy_json so a caller cannot accidentally widen or
#       replace the confused-deputy boundary while supplying unrelated
#       service-specific permissions.
resource "aws_iam_role_policy" "task_sqs" {
  count = length(var.sqs_send_queue_arns) > 0 || length(var.sqs_receive_queue_arns) > 0 ? 1 : 0

  name   = "${local.resource_name}-task-sqs"
  role   = aws_iam_role.task.id
  policy = data.aws_iam_policy_document.task_sqs[0].json
}

# WHY : Assumptions: for_each over a set here rather than count over the list,
#       because an attachment keyed by its own policy ARN is stable under
#       reordering. With count the addresses would be positional, so inserting
#       one ARN at the head of the list would destroy and recreate every
#       attachment after it for no change in effect. Contrast the conditional
#       resources in this file, which use count precisely because a
#       zero-or-one gate is what count expresses directly.
resource "aws_iam_role_policy_attachment" "task" {
  for_each = toset(var.task_role_managed_policy_arns)

  role       = aws_iam_role.task.name
  policy_arn = each.value
}

data "aws_iam_policy_document" "task_telemetry" {
  statement {
    sid    = "AllowTelemetryLogExport"
    effect = "Allow"

    actions = [
      "logs:CreateLogStream",
      "logs:DescribeLogStreams",
      "logs:PutLogEvents",
    ]

    resources = [
      local.log_group_arn,
      "${local.log_group_arn}:*",
    ]
  }

  statement {
    sid    = "AllowXrayTraceExport"
    effect = "Allow"

    # WHY : Assumptions: X-Ray ingestion actions do not support resource-level
    #       permissions, so the Resource wildcard is imposed by the API while
    #       the action set remains limited to writing trace segments and
    #       telemetry records. Sampling happens in the task-local collector and
    #       requires no X-Ray rule-read permission.
    actions = [
      "xray:PutTelemetryRecords",
      "xray:PutTraceSegments",
    ]

    resources = ["*"]
  }
}

# WHY : Alternatives Considered: requiring every one of the eight callers to
#       repeat these statements was rejected because the permissions are a
#       property of this module-created sidecar, not of any bounded context.
#       Keeping them here means disabling the sidecar removes the policy too,
#       while each service's queue, database and secret grants stay root-owned.
resource "aws_iam_role_policy" "task_telemetry" {
  count = var.enable_telemetry_collector ? 1 : 0

  name   = "${local.resource_name}-telemetry"
  role   = aws_iam_role.task.id
  policy = data.aws_iam_policy_document.task_telemetry.json
}


# -----------------------------------------------------------------------------
# Task definition.
# -----------------------------------------------------------------------------

# Assumptions: created unconditionally, including for the one
#       instantiation that has no service. ADR-002 puts batch on
#       Step-Functions-invoked Fargate tasks rather than on a long-running
#       service, and the state machine's synchronous run-task integration needs
#       exactly this and nothing more: a registered task definition it can
#       start with per-step container overrides.
resource "aws_ecs_task_definition" "this" {
  family = local.resource_name

  # WHY : Assumptions: Fargate requires cpu and memory at the task level and
  #       rejects any pair outside its published matrix, so these are not free
  #       numbers. variables.tf validates the whole matrix cross-variable,
  #       which turns what would be an apply-time RegisterTaskDefinition
  #       rejection into a plan-time error naming both values.
  cpu    = var.task_cpu
  memory = var.task_memory

  # WHY : Alternatives Considered: EC2 and Lambda were both rejected, per
  #       ADR-002. EC2 would add instance patching and capacity management for
  #       a workload that is eight uniform request-response services. Lambda
  #       cannot host them: the batch steps that share this module's task
  #       definitions exceed its fifteen-minute execution ceiling, and a warm
  #       JDBC connection pool has no natural home in an invocation-scoped
  #       runtime, so every request would pay connection setup.
  requires_compatibilities = ["FARGATE"]

  # WHY : Assumptions: awsvpc is required by Fargate rather than selected among
  #       options. It is what gives each task its own elastic network interface
  #       and its own address inside the private application subnets, which is
  #       in turn why the target group below registers targets by IP.
  network_mode = "awsvpc"

  execution_role_arn = aws_iam_role.execution.arn
  task_role_arn      = aws_iam_role.task.arn

  # WHY : Assumptions: Linux is the first of this migration's non-negotiable
  #       constraints, and X86_64 matches the architecture of the base images
  #       the service Dockerfiles are built on. Naming both explicitly rather
  #       than letting Fargate infer them means an image built for another
  #       architecture fails at registration, where the error names the
  #       mismatch, instead of at task start where it does not.
  runtime_platform {
    cpu_architecture        = "X86_64"
    operating_system_family = "LINUX"
  }

  # WHY : Assumptions: one Fargate ephemeral volume per writable path, carrying
  #       a name and nothing else. Fargate supports only bind-mount host
  #       volumes and no volume driver configuration, so there is nothing
  #       further to set; the backing storage is the task's own ephemeral
  #       storage and it is discarded with the task, which is the property that
  #       makes it safe for scratch data and unsafe for anything else.
  dynamic "volume" {
    for_each = local.writable_volumes

    content {
      name = volume.key
    }
  }

  dynamic "volume" {
    for_each = var.enable_telemetry_collector ? [true] : []

    content {
      # WHY : Assumptions: the collector runs with a read-only root filesystem
      #       and receives its own ephemeral /tmp rather than sharing an
      #       application scratch volume that may contain business data.
      name = "telemetry-tmp"
    }
  }

  container_definitions = jsonencode(concat([
    {
      name  = local.container_name
      image = var.image_uri

      # WHY : Assumptions: the application remains essential even when the
      #       telemetry sidecar is present. Otherwise the task could remain in
      #       RUNNING after the only container serving business traffic exited.
      essential = true

      # WHY : Assumptions: START waits only for the collector process to begin,
      #       not for an external health endpoint. OTLP exporters buffer and
      #       retry during the short interval before its receivers are ready,
      #       while omitting the dependency can lose the first startup spans
      #       before the sidecar process exists at all.
      dependsOn = var.enable_telemetry_collector ? [{
        containerName = "aws-otel-collector"
        condition     = "START"
      }] : []

      # WHY : Assumptions: container_user carries a numeric uid, which must
      #       match the non-root user the service's own Dockerfile creates -- a
      #       uid absent from the image fails at task start. This is a
      #       preserved property rather than an invented hardening measure:
      #       app/csd/CARDDEMO.CSD sets TASKDATAKEY(USER) on all eighteen of
      #       its DEFINE TRANSACTION stanzas and EXECKEY(USER) on all eighteen
      #       DEFINE PROGRAM stanzas, so the baseline already refused to run
      #       application work in the privileged CICS storage key. Dropping to
      #       a non-root uid here is the same decision expressed in the new
      #       runtime, not a new one.
      user = var.container_user

      # WHY : Assumptions: the privileged key is deliberately absent rather
      #       than present and false. AWS documents the parameter as not
      #       supported for tasks run on Fargate, and Fargate's security model
      #       states plainly that privileged containers are unavailable there,
      #       so asserting even the false value risks a RegisterTaskDefinition
      #       rejection this module has no way to verify against a real Fargate
      #       control plane.
      #       Alternatives Considered: an explicit false reads as more
      #       auditable, and it was rejected for exactly that reason -- the
      #       omission is what Fargate accepts unambiguously, Fargate's
      #       effective default is unprivileged, and both spellings therefore
      #       describe the identical running container. Choosing the spelling
      #       that cannot be rejected costs nothing that this comment does not
      #       restore.

      # WHY : Trade-offs: a read-only root filesystem removes the write access
      #       an attacker needs in order to drop a binary into the image's own
      #       tree, and the accepted cost is that every path the JVM writes to
      #       has to be named up front. writable_mount_paths defaults to /tmp
      #       alone, which is where a heap dump on out-of-memory and the JVM's
      #       own scratch files land; a service needing more has to say so,
      #       which is the point.
      readonlyRootFilesystem = var.readonly_root_filesystem

      mountPoints = local.container_mount_points

      # WHY : Assumptions: awsvpc gives the task its own interface, so the
      #       container port is a port on that interface and no host port is
      #       mapped at all. tcp is named explicitly because ECS stores a
      #       default protocol in the revision it registers; omitting it here
      #       leaves the rendered JSON and the registered revision different,
      #       and the provider compares them as strings, so every subsequent
      #       plan would propose a new revision for no change.
      portMappings = [
        {
          containerPort = var.container_port
          protocol      = "tcp"
        }
      ]

      # WHY : Assumptions: environment carries non-secret configuration only,
      #       and variables.tf enforces that at the boundary with a namespace
      #       allowlist and an outright refusal of secret-bearing names.
      #       Anything with a value worth protecting travels instead as a
      #       reference in secrets, which ECS resolves at task start from
      #       Parameter Store or Secrets Manager. That is what keeps every
      #       endpoint, connection string, password and token out of the task
      #       definition, where it would otherwise be readable by anyone able
      #       to describe it -- and it is the same set of ARNs that scopes the
      #       execution policy above, so configuration by reference is what
      #       keeps that policy wildcard-free rather than being a separate
      #       discipline.
      environment = local.container_environment
      secrets     = local.container_secrets

      # WHY : Assumptions: awslogs is one of the three log drivers Fargate
      #       supports and the only one of them that needs no sidecar
      #       container. The group named is the one created above rather than
      #       an arbitrary string, which is precisely what lets the execution
      #       policy scope logs:PutLogEvents to a single ARN instead of to
      #       every log group in the account.
      logConfiguration = {
        logDriver = "awslogs"

        options = {
          "awslogs-group"         = aws_cloudwatch_log_group.this.name
          "awslogs-region"        = data.aws_region.current.region
          "awslogs-stream-prefix" = var.service_name
        }
      }

      # WHY : Alternatives Considered: a container-level healthCheck was
      #       considered and deliberately left out. The command a container
      #       health check runs has to exist inside the image, and only the
      #       service's own Dockerfile knows which binaries its base image
      #       actually ships -- a headless Corretto runtime image carries no
      #       curl. Each Dockerfile therefore owns its HEALTHCHECK, where the
      #       base image is pinned and known, and the target group below owns
      #       the check that decides whether a task receives traffic. A third
      #       check here, with a command this module cannot verify against an
      #       image it never sees, would add a failure mode without adding a
      #       signal.
    }
    ],
    var.enable_telemetry_collector ? [
      {
        name      = "aws-otel-collector"
        image     = var.telemetry_collector_image
        essential = true

        # WHY : Assumptions: the image supports an environment-backed config
        #       URI. Supplying the complete typed configuration through the
        #       task definition avoids an S3 config object, its read policy and
        #       a second deployment artifact that could drift from this revision.
        command = ["--config=env:AOT_CONFIG_CONTENT"]

        cpu               = 128
        memoryReservation = 128

        # WHY : Assumptions: tail sampling waits up to ten seconds before a
        #       decision. A thirty-second stop window lets the collector make
        #       that decision and flush its final batch after the application
        #       exits, which is load-bearing for short-lived batch tasks.
        stopTimeout = 30

        readonlyRootFilesystem = true
        mountPoints = [{
          containerPath = "/tmp"
          readOnly      = false
          sourceVolume  = "telemetry-tmp"
        }]

        environment = [
          {
            name  = "AOT_CONFIG_CONTENT"
            value = local.telemetry_collector_configuration
          },
          {
            name  = "AWS_REGION"
            value = data.aws_region.current.region
          },
        ]

        portMappings = [
          {
            containerPort = 4317
            protocol      = "tcp"
          },
          {
            containerPort = 4318
            protocol      = "tcp"
          },
        ]

        # WHY : Assumptions: application and collector share the task network
        #       namespace, while the task security group admits only the
        #       application port. Declaring the OTLP ports documents the
        #       listeners without exposing them outside the task.
        logConfiguration = {
          logDriver = "awslogs"
          options = {
            "awslogs-group"         = aws_cloudwatch_log_group.this.name
            "awslogs-region"        = data.aws_region.current.region
            "awslogs-stream-prefix" = "telemetry"
          }
        }
      },
    ] : [],
  ))

  tags = local.tags

  lifecycle {
    precondition {
      condition = (
        length(setsubtract(toset(keys(var.environment_variables)), local.plain_environment_names)) == 0 &&
        length(setsubtract(toset(keys(var.ssm_parameter_arns)), local.parameter_environment_names)) == 0 &&
        length(setsubtract(toset(keys(var.secret_arns)), local.secret_environment_names)) == 0
      )
      error_message = "container configuration includes a name outside the exact CardDemo environment, Parameter Store or Secrets Manager schema."
    }

    precondition {
      condition = (
        length(setintersection(toset(keys(var.environment_variables)), toset(keys(var.ssm_parameter_arns)))) == 0 &&
        length(setintersection(toset(keys(var.environment_variables)), toset(keys(var.secret_arns)))) == 0 &&
        length(setintersection(toset(keys(var.ssm_parameter_arns)), toset(keys(var.secret_arns)))) == 0
      )
      error_message = "an environment-variable name may be supplied through exactly one configuration channel."
    }

    precondition {
      condition = (
        lookup(var.environment_variables, "CARDDEMO_ENVIRONMENT", null) == var.environment &&
        length(setsubtract(local.required_plain_environment_names[var.service_name], toset(keys(var.environment_variables)))) == 0 &&
        length(setsubtract(local.required_parameter_environment_names[var.service_name], toset(keys(var.ssm_parameter_arns)))) == 0 &&
        length(setsubtract(local.required_secret_environment_names[var.service_name], toset(keys(var.secret_arns)))) == 0
      )
      error_message = "the service is missing a required configuration name, or CARDDEMO_ENVIRONMENT does not exactly match the module environment."
    }

    precondition {
      # WHY : Refactoring Rationale: a second precondition stood here and forbade
      #       CARDDEMO_DB_SSL_ROOT_CERT outright whenever the environment was
      #       `prod`, on the same premise the block below withdraws -- that a
      #       production task should inherit its image's own default. The two could
      #       not both hold: one demanded the name in every environment and the
      #       other refused it in one, so no production configuration satisfied
      #       both and `terraform plan` would fail for prod while
      #       `terraform validate` reported nothing, because it does not evaluate a
      #       lifecycle block. The forbidding clause is removed rather than
      #       narrowed: both roots set the path in every environment, and the
      #       reason below is why they must.
      # WHY : Refactoring Rationale: this required the anchor path in dev only, on the
      #       reasoning that production should inherit the image's own default. That is
      #       withdrawn: the two images install the bundle at DIFFERENT paths --
      #       data-migration's Dockerfile at aws-rds-global-bundle.pem, the service
      #       images at carddemo-rds-ca-bundle.pem -- so an inherited default is a
      #       different file per image and invisible in the task definition. Every
      #       environment now states the path, and this asserts that it did.
      condition = lookup(
        var.environment_variables,
        "CARDDEMO_DB_SSL_ROOT_CERT",
        null,
      ) != null
      error_message = "every task must set CARDDEMO_DB_SSL_ROOT_CERT to the image-local trust-anchor path, because the service images and the data-migration image install the Aurora certificate bundle at different locations and verify-full needs the right one."
    }

    precondition {
      # WHY : Refactoring Rationale: two clauses named the wrong owner and are
      #       corrected here. The mask key was required of batch as well as
      #       data-migration, but the keyed tag is derived in
      #       carddemo_migration.copybook.layouts, which runs only in the ETL image.
      #       Reporting was identified by CARDDEMO_COGNITO_APP_CLIENT_ID, which no root
      #       injects; its actual exclusive parameters are the report output bucket and
      #       the on-demand state machine it starts. Every clause is biconditional on
      #       purpose: a name reaching the wrong service is as much a defect as a name
      #       missing from the right one.
      condition = (
        (var.service_name == "data-migration") == contains(keys(var.secret_arns), "CARDDEMO_MASK_HMAC_KEY") &&
        (var.service_name == "reporting") == contains(keys(var.environment_variables), "CARDDEMO_TRUSTED_PROXY_PATTERN") &&
        (var.service_name == "reporting") == contains(keys(var.ssm_parameter_arns), "CARDDEMO_REPORTING_S3_OUTPUT_BUCKET") &&
        (var.service_name == "auth") == contains(keys(var.secret_arns), "CARDDEMO_AUTH_COGNITO_CLIENT_SECRET")
      )
      error_message = "service-specific secret and trust configuration was distributed to the wrong bounded context."
    }

    precondition {
      condition     = (length(var.secret_arns) == 0) == (length(var.execution_secret_kms_key_arns) == 0)
      error_message = "secret_sources and execution_secret_kms_key_arns must either both be empty or both be non-empty, so every injected secret has a constrained decrypt grant and no unused decrypt grant is created."
    }
  }
}


# -----------------------------------------------------------------------------
# Load-balancer target group.
# -----------------------------------------------------------------------------

# Assumptions: the module boundary is worth stating here because this is
#       the one point at which two modules meet. infra/modules/alb owns the
#       load balancer, its listener and the per-service listener rules; this
#       module owns the target group and publishes its ARN, which alb attaches
#       as a rule's forward target. Putting the target group in alb instead
#       would have forced alb to re-derive this module's container name,
#       container port and health-check path -- three values it has no other
#       reason to know. That is also why no aws_lb, aws_lb_listener or
#       aws_lb_listener_rule appears anywhere in this file.
#       Trade-offs: count gates this resource on two inputs rather than one.
#       attach_load_balancer is false for the batch instantiation, which has a
#       task definition and no service at all, and create_service is the
#       broader gate; requiring both means a target group is never created with
#       nothing that could register in it.
#       Alternatives Considered: a separate ecs-task module for the batch shape
#       was rejected. It would duplicate the task definition, both roles and
#       the log group -- precisely the duplication this module exists to
#       prevent -- and the two shapes would then drift apart on everything
#       except the parts that differ. The accepted cost is three boolean inputs
#       that seven of the eight callers never touch. count rather than for_each
#       because a zero-or-one conditional is what count expresses directly;
#       for_each would need a synthetic key carrying no meaning.
resource "aws_lb_target_group" "this" {
  count = var.create_service && var.attach_load_balancer ? 1 : 0

  name   = local.target_group_name
  port   = var.container_port
  vpc_id = var.vpc_id

  # WHY : Assumptions: one protocol input drives both the forwarded traffic and
  #       the health probe below, because a probe on a different protocol from
  #       the traffic proves the wrong thing -- it would report a task healthy
  #       on a hop that real requests never take.
  protocol = var.target_protocol

  # WHY : Assumptions: awsvpc tasks have their own interfaces and no host port,
  #       so ip is the only target type able to address them at all; instance
  #       targets are impossible under Fargate. This is an AWS contract rather
  #       than a preference, which is why it is a literal and not an input.
  target_type = "ip"

  # WHY : Trade-offs: the deregistration delay is a ceiling on how long the
  #       load balancer keeps draining a target it has removed, not an estimate
  #       of how long a request takes. A shorter value returns capacity to the
  #       pool sooner during a rolling replacement; a longer one is what stops
  #       an in-flight request from being cut off mid-response.
  deregistration_delay = var.deregistration_delay

  # WHY : Assumptions: statelessness is what makes this entire module viable,
  #       so stickiness is disabled explicitly rather than merely omitted,
  #       which is what makes the decision auditable in a plan rather than
  #       inferable from an absence. app/cpy/COCOM01Y.cpy:L19-L44 defines the
  #       CARDDEMO-COMMAREA that carried every scrap of continuity between
  #       screen turns: CDEMO-FROM-TRANID and CDEMO-FROM-PROGRAM,
  #       CDEMO-TO-TRANID and CDEMO-TO-PROGRAM, CDEMO-USER-ID and
  #       CDEMO-USER-TYPE with its 'A' and 'U' condition names, the customer,
  #       account and card selection fields, CDEMO-LAST-MAP and
  #       CDEMO-LAST-MAPSET, and the CDEMO-PGM-CONTEXT re-entry discriminator.
  #       All of it is gone, decomposed into four separate mechanisms:
  #       navigation became client-side router history, identity became signed
  #       token claims, selection context became request path parameters, and
  #       the re-entry discriminator was eliminated outright. No task therefore
  #       holds anything a later request needs, and two properties this module
  #       depends on follow directly -- autoscaling may remove a task without
  #       losing session state, and a rolling deployment may replace every task
  #       for the same reason. A sticky cookie would pin a browser to one task
  #       and quietly reintroduce exactly the coupling the migration removed,
  #       making both of those safe operations unsafe again.
  stickiness {
    enabled = false

    # WHY : Assumptions: the provider requires a stickiness type even when
    #       stickiness is disabled, so lb_cookie is named to satisfy the schema
    #       rather than to select a behaviour. Nothing reads it while enabled
    #       is false.
    type = "lb_cookie"
  }

  health_check {
    enabled = true

    # WHY : Assumptions: /actuator/health is Spring Boot Actuator's endpoint,
    #       and every service includes the actuator starter for this consumer
    #       specifically. The same endpoint answers the load balancer here and
    #       the HEALTHCHECK in each service's own Dockerfile, so a task is
    #       judged by one definition of healthy rather than two.
    path = var.health_check_path

    # WHY : Assumptions: the same protocol as the forwarded traffic, for the
    #       reason given at protocol above.
    protocol = var.target_protocol

    # WHY : Assumptions: traffic-port rather than a literal keeps the probe on
    #       whatever port the target group forwards to, so container_port stays
    #       a single input that cannot fall out of step with itself.
    port = "traffic-port"

    matcher             = var.health_check_matcher
    interval            = var.health_check_interval
    timeout             = var.health_check_timeout
    healthy_threshold   = var.healthy_threshold
    unhealthy_threshold = var.unhealthy_threshold
  }

  tags = local.tags

  # WHY : Assumptions: a target group referenced by a listener rule in alb
  #       cannot be destroyed while that reference exists, so replacing this
  #       resource in place -- which a change to name or target_type forces --
  #       fails the apply with a resource-in-use error naming the listener
  #       unless the replacement exists first and the rule can be repointed
  #       onto it.
  #       Refactoring Rationale: local.target_group_name includes a stable hash
  #       of every configured replacement-forcing attribute. A replacement
  #       therefore arrives under a distinct name before this resource is
  #       destroyed, while an unchanged configuration keeps the same name and
  #       produces no churn.
  lifecycle {
    create_before_destroy = true
  }
}


# -----------------------------------------------------------------------------
# The service.
# -----------------------------------------------------------------------------

# Assumptions: absent for the batch instantiation, gated on the same
#       create_service input explained at the target group above. ADR-002 runs
#       batch as Step-Functions-invoked tasks, so batch needs the task
#       definition and no long-running service holding a desired count.
resource "aws_ecs_service" "this" {
  count = var.create_service ? 1 : 0

  name            = local.resource_name
  cluster         = var.cluster_arn
  task_definition = aws_ecs_task_definition.this.arn
  desired_count   = var.desired_count

  # WHY : Refactoring Rationale: launch_type = "FARGATE" was removed because it
  #       bypasses the cluster's capacity-provider model and is mutually
  #       exclusive with the strategy blocks below. The default input still
  #       selects on-demand FARGATE only, preserving the availability decision:
  #       a Spot interruption replaces tasks on two minutes' notice regardless
  #       of what they are serving, and interrupting sign-on, account, card or
  #       transaction work mid-request is the wrong cost trade. A caller may opt
  #       into FARGATE_SPOT explicitly, and the resulting plan shows that choice
  #       instead of inheriting it invisibly from the cluster.
  dynamic "capacity_provider_strategy" {
    for_each = var.capacity_provider_strategy

    content {
      capacity_provider = capacity_provider_strategy.value.capacity_provider
      weight            = capacity_provider_strategy.value.weight
      base              = capacity_provider_strategy.value.base
    }
  }

  # WHY : Trade-offs: the platform version is pinned rather than left at
  #       LATEST, so a platform change arrives as a reviewed edit instead of on
  #       the next task start, where it would be indistinguishable from an
  #       application regression. The accepted cost is that a new platform's
  #       fixes need that edit before they reach the service.
  platform_version = var.platform_version

  # WHY : Alternatives Considered: this is the rolling deployment controller,
  #       and both alternatives are explicitly out of scope for this migration.
  #       Blue-green through a CODE_DEPLOY controller was rejected: it requires
  #       a second, green target group, a CodeDeploy application and deployment
  #       group, and an appspec traffic-shifting configuration, none of which
  #       this infrastructure package provisions -- which is why no
  #       aws_codedeploy_ resource and no second aws_lb_target_group appears
  #       anywhere in this file. Canary was rejected on the same ground: it is
  #       a CodeDeploy traffic-shifting configuration rather than an ECS-native
  #       capability, so selecting it would pull in the identical four
  #       resources. ECS rolling replacement needs none of them; it replaces
  #       tasks inside the one target group under the percentage bounds below.
  #       The operational contract that follows is worth writing down because
  #       it is what an operator actually does: roll forward is a rolling
  #       deployment with an updated image tag, and roll back is
  #       `terraform -chdir=infra/envs/<env> destroy`.
  deployment_controller {
    type = "ECS"
  }

  # WHY : Assumptions: these two percentages ARE the rolling strategy, and it
  #       is the pair that makes it zero-downtime -- a minimum of one hundred
  #       means no existing task is stopped before its replacement is healthy,
  #       and a maximum of two hundred is the headroom that lets the
  #       replacement exist alongside it. Lowering the minimum would trade
  #       serving capacity during a deployment for a smaller peak task
  #       footprint.
  deployment_minimum_healthy_percent = var.deployment_minimum_healthy_percent
  deployment_maximum_percent         = var.deployment_maximum_percent

  # WHY : Assumptions: the circuit breaker is a property of the rolling
  #       controller above, not a second deployment strategy, and saying so
  #       matters because the word rollback reads like blue-green at a glance.
  #       It watches whether the new tasks reach a steady state and, if they do
  #       not, returns the service to the last known-good task set -- inside
  #       the single target group, with no traffic shifting and no green
  #       environment, so it introduces none of the resources rejected above.
  #       Trade-offs: it is gated rather than unconditional so that a caller
  #       debugging a task which will not start can keep the failed revision in
  #       place to inspect, instead of having it rolled away before the logs
  #       are read.
  dynamic "deployment_circuit_breaker" {
    for_each = var.enable_deployment_circuit_breaker ? [true] : []

    content {
      enable   = true
      rollback = true
    }
  }

  # WHY : Refactoring Rationale: assign_public_ip is a hard-coded false rather
  #       than an input, and that is the decision rather than an oversight. The
  #       tasks sit in the private application subnets, and
  #       infra/modules/network provisions interface endpoints for the ECR API
  #       and Docker registry, CloudWatch Logs, Secrets Manager, KMS, SQS, Step
  #       Functions and SSM plus an S3 gateway endpoint, so every AWS API call
  #       a task makes stays inside the VPC and needs no public address.
  #       Exposing this as a variable would let one caller quietly defeat that
  #       tiering for one service, and a network boundary a caller can opt out
  #       of is not a boundary -- so the choice is withheld rather than
  #       defaulted.
  network_configuration {
    assign_public_ip = false
    security_groups  = var.security_group_ids
    subnets          = var.private_app_subnet_ids
  }

  # WHY : Assumptions: keyed off the target group's own presence rather than
  #       off attach_load_balancer a second time, so the block and the resource
  #       it references cannot disagree. There is no arrangement of inputs that
  #       produces a load_balancer block pointing at a target group that was
  #       never created.
  dynamic "load_balancer" {
    for_each = toset(aws_lb_target_group.this[*].arn)

    content {
      container_name   = local.container_name
      container_port   = var.container_port
      target_group_arn = load_balancer.value
    }
  }

  # WHY : Assumptions: ECS rejects this argument outright on a service with no
  #       load balancer, so it has to resolve to null rather than to a harmless
  #       number -- see the local that computes it. The grace period only means
  #       anything while a target group is deciding whether a newly started
  #       task is healthy: it is the window in which a slow JVM start is not
  #       yet counted as a failure, which is why it exists at all for a Spring
  #       Boot service.
  health_check_grace_period_seconds = local.health_check_grace_period

  # WHY : Assumptions: managed tags with SERVICE propagation put the service's
  #       own tags onto each task ECS starts, so cost allocation and per-task
  #       traceability both come from the tag set already declared here. The
  #       alternative was a second tagging mechanism at the task level, which
  #       could drift from this one and then disagree about which service a
  #       cost belonged to.
  enable_ecs_managed_tags = true
  propagate_tags          = "SERVICE"

  tags = local.tags

  # WHY : Assumptions: ECS validates the execution role's permissions when the
  #       service is created, and the service references the ROLE while the
  #       permissions live in a separate inline-policy resource. Terraform
  #       infers ordering only from references, so without this explicit edge
  #       it may create the service before the policy exists, and the apply
  #       fails intermittently in a way that reads like a transient AWS error
  #       rather than a missing dependency.
  depends_on = [aws_iam_role_policy.execution]

  # WHY : Trade-offs: Application Auto Scaling writes desired_count at run
  #       time, so Terraform has to stop reconciling it -- otherwise every plan
  #       after a scaling event shows a diff and every apply fights the scaler
  #       back to the configured number. var.desired_count therefore sets the
  #       INITIAL count only. variables.tf now rejects the long-running service
  #       shape when autoscaling is false, so every resource that reaches this
  #       lifecycle has one runtime owner for desired_count and there is no
  #       unsupported fixed-size exception for Terraform to reconcile.
  lifecycle {
    ignore_changes = [desired_count]
  }
}

# -----------------------------------------------------------------------------
# Autoscaling.
# -----------------------------------------------------------------------------

# Assumptions: gated on create_service as well as enable_autoscaling,
#       because a scalable target addresses a service by name and there is no
#       service to address in the batch shape.
resource "aws_appautoscaling_target" "this" {
  count = var.create_service && var.enable_autoscaling ? 1 : 0

  service_namespace = "ecs"

  # WHY : Assumptions: this composite string is an Application Auto Scaling API
  #       contract rather than a naming convention -- a service is addressed as
  #       service/<cluster name>/<service name> and nothing else is accepted.
  #       It is also the reason variables.tf declares cluster_name separately
  #       from cluster_arn, which would otherwise look redundant: the
  #       alternative was splitting the name back out of the ARN with
  #       element(split("/", ...)), which is brittle string surgery over a
  #       value the calling root already holds and can simply pass.
  resource_id = "service/${var.cluster_name}/${aws_ecs_service.this[0].name}"

  # WHY : Assumptions: DesiredCount is the only scalable dimension ECS exposes
  #       for a service, so this is a fixed string rather than a choice among
  #       options.
  scalable_dimension = "ecs:service:DesiredCount"

  # WHY : Trade-offs: these two bounds are the real cost control, because
  #       target tracking will otherwise add capacity for as long as the metric
  #       stays above target. The minimum is what keeps the service available
  #       across an availability-zone loss rather than merely running.
  min_capacity = var.min_capacity
  max_capacity = var.max_capacity

  tags = local.tags
}

# Assumptions: gated identically to the scalable target it attaches to,
#       so the two can never exist apart.
resource "aws_appautoscaling_policy" "cpu" {
  count = var.create_service && var.enable_autoscaling ? 1 : 0

  name               = "${local.resource_name}-cpu"
  resource_id        = aws_appautoscaling_target.this[0].resource_id
  scalable_dimension = aws_appautoscaling_target.this[0].scalable_dimension
  service_namespace  = aws_appautoscaling_target.this[0].service_namespace

  # WHY : Alternatives Considered: step scaling was rejected because it needs
  #       hand-tuned alarm thresholds and the baseline supplies no data from
  #       which to derive them. app/csd/CARDDEMO.CSD gives all eighteen
  #       transactions the same PRIORITY(1) and the same TRANCLASS(DFHTCL00)
  #       dispatch class -- both attributes occur exactly eighteen times -- so
  #       there is no per-transaction demand signal anywhere in the source to
  #       build steps out of. Target tracking needs exactly one number, and
  #       that number is one an operator can reason about from observed
  #       utilisation rather than invent. Worth noting the direction of travel:
  #       per-service autoscaling is a documented improvement over that single
  #       uniform dispatch class, not a port of it, because the baseline could
  #       not give one transaction more capacity than another.
  policy_type = "TargetTrackingScaling"

  target_tracking_scaling_policy_configuration {
    target_value = var.autoscaling_target_cpu_utilization

    # WHY : Assumptions: average CPU across the service is the predefined
    #       metric that needs no custom metric plumbing, and it is the right
    #       signal for these services specifically -- they spend their time on
    #       JSON serialisation and exact fixed-point arithmetic rather than
    #       blocked on a connection pool, so CPU rises with real demand instead
    #       of flattening under contention the way a request-count metric
    #       would.
    predefined_metric_specification {
      predefined_metric_type = "ECSServiceAverageCPUUtilization"
    }

    # WHY : Trade-offs: the two cooldowns are deliberately asymmetric, and the
    #       asymmetry is the decision rather than an accident of defaults.
    #       Scaling out is cheap and immediately reversible, so its cooldown is
    #       the shorter of the two; scaling in removes capacity that an
    #       arriving request cannot get back quickly, so its cooldown is the
    #       longer one. Making them equal would either add capacity too slowly
    #       under a rising load or shed it too eagerly on a momentary dip.
    scale_in_cooldown  = var.autoscaling_scale_in_cooldown
    scale_out_cooldown = var.autoscaling_scale_out_cooldown
  }
}
