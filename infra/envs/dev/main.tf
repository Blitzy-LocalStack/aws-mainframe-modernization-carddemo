# =============================================================================
# infra/envs/dev/main.tf
# -----------------------------------------------------------------------------
# Purpose:
#   Compose the complete CardDemo development stack from the sixteen reusable
#   infrastructure modules. This root is the only layer allowed to connect
#   producer outputs to consumer inputs, create cross-module IAM documents,
#   package operational Lambdas, and publish runtime configuration to SSM.
#
# Parameters:
#   Every configurable value is declared in variables.tf. Credentials and
#   private keys are generated during apply and never accepted as inputs.
#
# Return values:
#   outputs.tf publishes grouped operational handles and consumes every child
#   module's public output contract.
#
# Errors / Exceptions:
#   Module validation catches malformed values before apply. Runtime bootstrap
#   fails the apply if V0 SQL, credential rotation prerequisites, or the Data
#   API cannot complete; a stack is never reported ready with passwordless
#   service roles.
# =============================================================================

data "aws_partition" "current" {}
data "aws_region" "current" {}
data "aws_caller_identity" "current" {}

locals {
  health_check_path = "/actuator/health"
  parameter_prefix  = "/${var.name_prefix}"
  # WHY : Assumptions: the name the internal listeners are certified for is an
  #       input when the operator has a private zone for it and a composed default
  #       otherwise. The composed form is deliberately under `.internal`, which is
  #       not a resolvable public suffix, so a self-signed certificate for it cannot
  #       be mistaken for one that would be trusted anywhere outside this VPC.
  internal_service_dns_name = coalesce(var.internal_service_domain_name, "${var.name_prefix}-${var.environment}.services.internal")

  # WHY : Assumptions: authorization-service reaches the account context over the
  #       INTERNAL load balancer at the same name the listener certificate is issued
  #       for, so this is that name with the required scheme and nothing else -- no
  #       port, no path, no trailing separator. The client's own guard refuses a base
  #       address carrying a path, a query or a fragment, so composing anything more
  #       here would fail at container start rather than at plan.
  # WHY : Assumptions: this single local feeds BOTH the base address and the approved
  #       origin the client compares it against. One expression rather than two is the
  #       point: the comparison exists to catch a base address repointed without
  #       review, and two separately-typed values would instead make an ordinary
  #       deployment fail on a transcription difference while still admitting a
  #       coordinated edit of both.
  account_context_origin = "https://${coalesce(var.internal_service_domain_name, "${var.name_prefix}-${var.environment}.services.internal")}"

  # WHY : Assumptions: the reference context answers on the SAME internal origin, because every
  #       migrated service sits behind the one internal load balancer and is addressed by path.
  #       It is aliased rather than given its own copy of the expression above so that an edit to
  #       the internal domain cannot move one consumer and leave the other pointed at an address
  #       that no longer answers. Alternatives Considered: passing local.account_context_origin
  #       straight into the reference entries below. Rejected because the name would then say
  #       "account" at a call site that configures a reference lookup, which is exactly the kind
  #       of mismatch a later reader corrects in the wrong direction.
  reference_context_origin = local.account_context_origin

  # WHY : Assumptions: the telemetry sidecar's image is pulled from THIS
  #       deployment's registry, not from the public registry the upstream image
  #       is published in. infra/modules/network enumerates the application
  #       tier's egress rather than allowing 0.0.0.0/0, and the public registry
  #       has neither an interface endpoint nor a managed prefix list, so a task
  #       pointed at the upstream reference cannot pull its sidecar -- and because
  #       infra/modules/ecs-service attaches that sidecar to every workload by
  #       default, no task in this environment would start while the plan reported
  #       nothing. infra/modules/ecr provisions the mirror repository and
  #       .github/workflows/deploy.yml copies the pinned upstream image into it.
  # WHY : Assumptions: the tag is the UPSTREAM version and not this release's
  #       commit tag. The mirror holds a third-party artifact this repository does
  #       not build, so tagging it with a CardDemo commit would assert a
  #       provenance it does not have and would oblige a re-push on every release
  #       of unrelated code. Trade-offs: a collector upgrade is therefore two
  #       coordinated edits -- this value and the mirror push -- which is the same
  #       friction the pinned upstream reference already carried and is what keeps
  #       an upgrade a reviewed change rather than a silent one.
  # WHY : Refactoring Rationale: advanced from v0.48.0, which upstream superseded
  #       with v0.49.0 -- verified against the publishing registry rather than a
  #       release note: the repository's tag list carries 84 tags of which the
  #       highest semantic version is v0.49.0, and `latest` is the only non-semver
  #       entry. Running a superseded collector is a supply-chain position rather
  #       than a preference, because a sidecar attached to every workload is the one
  #       container in this estate whose version nothing else compensates for.
  # WHY : Assumptions: the tag names the artifact the deployment MIRRORS, and the
  #       digest that pins it lives in .github/workflows/deploy.yml beside the pull
  #       that resolves it. This value and that one are asserted equal by a gate in
  #       .github/workflows/infra-ci.yml, because a version advanced here and not
  #       there would mirror one release and register another.
  telemetry_collector_image_tag  = "v0.49.0"
  telemetry_collector_repository = "aws-otel-collector"

  # WHY : Assumptions: an operator-issued ALB certificate is used when supplied and
  #       the self-signed one otherwise. Both are terminated by the INTERNAL load
  #       balancer, which publishes no public listener, so the trust decision is the
  #       VPC's rather than a browser's -- which is what makes the self-signed
  #       fallback acceptable here and unacceptable at the CloudFront edge, where
  #       infra/modules/cloudfront-spa requires a real certificate unconditionally.
  #       Trade-offs: supplying a certificate means an apply cannot silently fall
  #       back to a self-signed leaf in an environment that has a real one, which is
  #       the failure this input exists to prevent.
  alb_certificate_arn = var.alb_certificate_arn
  jdbc_url            = "jdbc:postgresql://${module.aurora.writer_endpoint}:${module.aurora.port}/${module.aurora.database_name}"
  # WHY : Assumptions: the SPA's public origin is its FIRST alias, not the
  #       distribution's generated cloudfront.net name. cloudfront-spa requires a
  #       certificate and a non-empty alias list in every environment, so viewers
  #       always arrive on an alias and the generated name serves nothing. Reading
  #       the alias also keeps this value out of the distribution's dependency
  #       chain, so the API's CORS configuration -- which is what consumes it --
  #       does not have to wait on, or depend on, the distribution being created.
  spa_origin = "https://${var.cloudfront_aliases[0]}"

  # WHY : Assumptions: these seven contexts are the complete synchronous edge
  #       surface. Batch and data migration have task definitions but no
  #       long-running ECS service or ALB target group. One entry per CONTEXT, not
  #       per path segment: a context may own more than one top-level segment, and
  #       two do -- card carries `/api/v1/admin/cards` beside `/api/v1/cards` and
  #       transaction carries `/api/v1/billpay` beside `/api/v1/transactions` -- so
  #       its patterns list is the place that grows rather than this map.
  # WHY : Assumptions: every pattern carries the /api/v1 prefix the HTTP API's own
  #       route keys publish, so an ALB rule and the edge route it is reached through
  #       name the same path. A pattern without the prefix can never match a request
  #       forwarded from that edge, which is why infra/modules/alb validates it rather
  #       than accepting whatever a root supplies.
  online_services = {
    # WHY : Refactoring Rationale: auth-service carries TWO patterns, not four. An
    #       earlier revision added `/api/v1/users` and `/api/v1/users/*` on the
    #       reading that user administration sat on its own top-level segment. It
    #       does not: the auth contract publishes those five operations at
    #       `/api/v1/auth/users` and `/api/v1/auth/users/{userId}`, which is also
    #       what `SecurityConfig.USER_COLLECTION_PATH_PATTERN` and
    #       `USER_SUBTREE_PATH_PATTERN` gate and what that service's contract test
    #       asserts the two agree on. The wildcard `/api/v1/auth/*` below therefore
    #       already forwards every one of them, and the withdrawn pair named an
    #       address no contract publishes -- so a request to it would have been
    #       forwarded to a service with no handler for it, which reports as an
    #       unimplemented operation rather than as a misrouted one. Withdrawing it
    #       here is the second half of the same fix as withdrawing the
    #       `/api/v1/users` route keys in infra/modules/api-gateway-http; the two
    #       lists have to name the same paths or one of them is describing a
    #       topology that does not exist.
    # WHY : Assumptions: the bare pattern is listed beside the wildcard for the same
    #       reason the gateway pairs a bare key with a greedy one -- `/api/v1/auth/*`
    #       does not match `/api/v1/auth` itself. Two values is well within the five
    #       a single path-pattern condition accepts, so this needs no second rule and
    #       no second priority.
    auth = {
      repository = "auth-service"
      role       = "carddemo_auth"
      priority   = 10
      paths      = ["/api/v1/auth", "/api/v1/auth/*"]
    }
    # WHY : (1) Refactoring Rationale: this service is forwarded on FIVE patterns
    #       rather than two, because it publishes internal read operations on two
    #       prefixes outside its own subtree -- POST /api/v1/card-xrefs/lookup and
    #       the customer reads POST /api/v1/customers/lookup,
    #       POST /api/v1/customers/record and GET /api/v1/customers, alongside
    #       POST /api/v1/accounts/lookup inside its own. The two out-of-subtree
    #       prefixes were absent from this list while the controllers existed, which
    #       is not a cosmetic gap: a listener with no matching rule answers 404
    #       itself, so the pending-authorization context's cross-reference and
    #       customer calls failed at the load balancer without reaching a task, and
    #       no log in the account service recorded a request at all.
    #       (1a) Refactoring Rationale: those four addresses were written here as
    #       keyed GETs -- GET /api/v1/accounts/{accountId},
    #       GET /api/v1/customers/{customerId}, its /record segment, and the probe's
    #       HEAD -- which this context has not published for some time. Each moved
    #       its identifier into a request body because a load balancer composes its
    #       access record from the request line before any application code runs, and
    #       the sensitive-data contract in docs/architecture/observability.md names
    #       account and customer identifiers among the values a durable diagnostic
    #       may not hold. The FORWARDING is unaffected -- every one of them still
    #       falls under a pattern in the list below -- so this is a description
    #       catching up with the addresses rather than a routing change. The three
    #       end-user account operations have since moved for the same reason, to
    #       POST /api/v1/accounts/view, POST /api/v1/accounts/update and
    #       POST /api/v1/accounts/card-cross-references/search, and are likewise
    #       covered by the existing /api/v1/accounts/* pattern.
    #       (2) Assumptions: `/api/v1/card-xrefs/lookup` is listed as the EXACT
    #       operation path rather than as a `/api/v1/card-xrefs/*` subtree, and the
    #       narrower form is chosen deliberately. That prefix carries exactly one
    #       published operation and its request body carries an unmasked primary
    #       account number, so forwarding only the one path means any other request
    #       beneath the prefix is refused at the edge instead of reaching the service
    #       to be refused there. The customer prefix uses the subtree form because its
    #       operation is parameterised by identifier and an exact path cannot express
    #       that. Assumptions: the customer prefix keeps the subtree form although
    #       both of its operations are now fixed addresses, because listing the two
    #       exactly would need two values where the subtree needs one and this list
    #       is already at the five-value ceiling recorded below.
    #       (3) Refactoring Rationale: the bare `/api/v1/customers` pattern IS now
    #       carried, where this entry previously omitted it and justified the omission
    #       with "no operation sits on either bare prefix". That premise stopped
    #       holding when the ascending customer scan mounted ON the collection
    #       address: a path pattern matches `*` against zero or more characters
    #       including `/`, so `/api/v1/customers/*` covers both the presence check
    #       and the record read beneath the prefix but NOT the bare collection,
    #       which requires the separator to be present. Without this
    #       value the scan is answered 404 by the listener itself -- the same failure
    #       mode paragraph (1) records for the two prefixes that were missing
    #       entirely, and the one that leaves no request in the service's own log.
    #       (3a) Trade-offs: no bare `/api/v1/card-xrefs` pattern is carried, and this
    #       list is AT the ceiling: a single path-pattern condition accepts five
    #       values, as the card entry below records, and this entry uses all five.
    #       Pairing the card-xrefs prefix too would need six. It is not needed --
    #       no operation sits on that bare prefix -- and it is also what made the
    #       subtree form in paragraph (2) the only available way to route the two
    #       missing operations: three exact paths where one stood would have needed
    #       seven values. A sixth account pattern of any kind now requires splitting
    #       the rule rather than extending it.
    #       (4) Assumptions: these two prefixes are deliberately NOT added to the
    #       api-gateway-http route table. That module is the PUBLIC edge and its
    #       authorizer validates identity-provider tokens; every operation on these
    #       two prefixes requires a machine token the gateway cannot mint, and the
    #       account service's internal filter chain refuses an identity-provider
    #       token on these paths.
    #       Publishing them at the edge would therefore expose a PAN-carrying request
    #       path to the internet in exchange for no reachable operation.
    account = {
      repository = "account-service"
      role       = "carddemo_account"
      priority   = 20
      paths = [
        "/api/v1/accounts", "/api/v1/accounts/*",
        "/api/v1/card-xrefs/*",
        "/api/v1/customers", "/api/v1/customers/*"
      ]
    }
    # WHY : Refactoring Rationale: this service is forwarded on FOUR patterns rather
    #       than two, because its contract of record publishes an administrative
    #       card-detail operation under its own `/api/v1/admin/cards` prefix rather
    #       than as a segment beneath the card subtree. The prefix is what removes the
    #       rule-ordering dependency that the suffix spelling placed on the service's
    #       own authority table -- a subtree pattern also matches a suffix beneath it,
    #       so only the table's order kept an ordinary user out of the one operation
    #       that renders a full account number. Forwarding the prefix here is the
    #       first of the two hops that has to know about it; the gateway route keys in
    #       infra/modules/api-gateway-http are the second, and the two lists have to
    #       name the same paths or one of them describes a topology that does not
    #       exist.
    # WHY : Assumptions: four values still fit one path-pattern condition, which
    #       accepts five, so this needs no second rule and no second priority. Each
    #       prefix is listed as a bare pattern beside its wildcard for the same reason
    #       the auth entry above is -- `/api/v1/admin/cards/*` does not match
    #       `/api/v1/admin/cards` itself -- and the bare admin pattern is carried even
    #       though no operation sits on it, so that a request to it reports as an
    #       unimplemented operation from this service rather than as a misrouted one
    #       from the load balancer.
    card = {
      repository = "card-service"
      role       = "carddemo_card"
      priority   = 30
      paths = [
        "/api/v1/cards", "/api/v1/cards/*",
        "/api/v1/admin/cards", "/api/v1/admin/cards/*"
      ]
    }
    transaction = {
      repository = "transaction-service"
      role       = "carddemo_ledger"
      priority   = 40
      paths      = ["/api/v1/transactions", "/api/v1/transactions/*", "/api/v1/billpay", "/api/v1/billpay/*"]
    }
    reference = {
      repository = "reference-service"
      role       = "carddemo_reference"
      priority   = 50
      paths      = ["/api/v1/reference", "/api/v1/reference/*"]
    }
    authorization = {
      repository = "authorization-service"
      role       = "carddemo_authorization"
      priority   = 60
      paths      = ["/api/v1/authorizations", "/api/v1/authorizations/*"]
    }
    reporting = {
      repository = "reporting-service"
      role       = "carddemo_reporting"
      priority   = 70
      # WHY : Assumptions: reporting publishes ONE top-level prefix. Its statement
      #       operations are declared beneath it, at /api/v1/reports/statements and
      #       /api/v1/reports/statements/transactions, so the greedy reports pattern
      #       already reaches them. Two /api/v1/statements patterns were listed here
      #       and are removed: they matched a prefix no service answers, and a rule
      #       that matches nothing is worse than absent -- it makes the routing table
      #       read as though a second reporting surface existed.
      paths = ["/api/v1/reports", "/api/v1/reports/*"]
    }
  }

  # WHY : Assumptions: THE CONNECTION BUDGET IS DERIVED HERE BECAUSE THIS IS THE
  #       ONLY PLACE THAT KNOWS BOTH FACTORS. ADR-003 records "connection count
  #       grows with task count" as a named risk and states the relationship as
  #       tasks TIMES pool size rather than tasks plus pool size; the per-task
  #       pool size is a service configuration and the task count is this root's
  #       configuration, so neither the observability module nor a service can
  #       compute the product alone.
  #       Assumptions: the map below mirrors spring.datasource.hikari.maximum-pool-size
  #       in each service's application.yml, and it is NOT uniform -- six services
  #       run a pool of ten while reporting-service and batch-service run four,
  #       the smaller pools reflecting read-mostly and single-task workloads. A
  #       value here that drifts from a service's application.yml understates the
  #       budget, which is why each entry names the service it mirrors rather than
  #       being folded into one multiplier.
  #       Trade-offs: the product uses each workload's MAXIMUM task count, not its
  #       desired count, so the threshold is the total every configured pool could
  #       open at full autoscale. That is deliberately the same philosophy as the
  #       serverless-capacity alarm, whose own rationale rejects alarming at a
  #       fraction of a configured maximum on the ground that a fraction is a
  #       chosen number with no source in the repository. Reaching this figure
  #       means every pool is full and further demand queues; a fraction of it
  #       would fire during ordinary scale-out.
  connection_pool_sizes = {
    auth          = 10
    account       = 10
    card          = 10
    transaction   = 10
    reference     = 10
    authorization = 10
    reporting     = 4
    batch         = 4
  }

  # WHY : Assumptions: an online workload autoscales to twice its desired count
  #       (see the ecs_service max_capacity argument, which sets exactly that),
  #       while batch runs a single task. data-migration is absent from the pool
  #       map above because it holds no JDBC pool: the extract-transform-load
  #       package connects with psycopg for the duration of a load and is not a
  #       long-lived pooled service, so counting it would inflate the budget with
  #       connections no pool ever holds.
  database_connection_budget = sum([
    for name, pool in local.connection_pool_sizes :
    pool * (name == "batch" ? 1 : var.ecs_desired_count * 2)
  ])

  workloads = merge(
    {
      for name, service in local.online_services : name => merge(service, {
        online         = true
        container_name = name
      })
    },
    {
      batch = {
        repository     = "batch-service"
        role           = "carddemo_batch"
        online         = false
        container_name = "batch"
        priority       = null
        paths          = []
      }
      data-migration = {
        repository     = "data-migration"
        role           = null
        online         = false
        container_name = "data-migration"
        priority       = null
        paths          = []
      }
    },
  )

  # WHY : Assumptions: the command each workload's container health check runs is
  #       declared HERE, beside the image each workload uses, because only the image
  #       knows what it ships and the two schemes in this estate differ. Every entry
  #       reproduces that workload's own Dockerfile HEALTHCHECK exactly: the seven
  #       Java services answer HTTPS behind a per-task self-signed leaf, so the probe
  #       passes --insecure, while batch answers plain HTTP. ECS monitors ONLY the
  #       command in the task definition and never reads the image's HEALTHCHECK
  #       instruction, so without this the eight probes those images carry were never
  #       evaluated in the deployed estate.
  # WHY : Assumptions: data-migration is deliberately ABSENT, so the module receives
  #       null for it and declares no check. Its own Dockerfile records that its probe
  #       is a structural import test rather than a liveness claim and that
  #       orchestration judges the one-shot task by terminal state and exit code, so a
  #       health check would add a second verdict on a container that has already
  #       finished by the time one could be useful.
  container_health_check_commands = {
    for name, workload in local.workloads :
    name => concat(
      ["/usr/bin/curl", "--fail", "--silent", "--show-error"],
      workload.online ? ["--insecure"] : [],
      ["--max-time", "4", "--output", "/dev/null"],
      ["${workload.online ? "https" : "http"}://127.0.0.1:${module.network.app_container_port}${local.health_check_path}"],
    )
    if name != "data-migration"
  }

  # WHY : Assumptions: the eight RUNTIME service login roles are named by
  #       data-migration/sql/V0__schemas_and_roles.sql and created there without a
  #       password; this list is the same inventory in the same order, used to build
  #       the ETL's alternate-login map and nothing else. It is written out rather
  #       than derived from local.workloads because reporting and batch share no
  #       one-to-one mapping with a role in that structure -- data-migration has no
  #       role at all -- so deriving it would need a filter that says less than the
  #       list does.
  # WHY : Assumptions: the SEVEN carddemo_<context>_migrator logins V0 also creates
  #       are deliberately absent from this list, because the list feeds only the
  #       ETL's alternate-login allowlist and the ETL never connects as a migrator
  #       -- carddemo_migration.config.role_for_schema resolves the runtime role for
  #       every schema. The migration credentials are consumed by each service's
  #       Flyway configuration instead, and are projected into task definitions by
  #       local.database_secret_sources below. The eight NOLOGIN
  #       carddemo_<context>_owner roles are absent for a stronger reason: they hold
  #       no credential at all, so there is nothing an allowlist could name.
  service_role_names = [
    "carddemo_auth",
    "carddemo_account",
    "carddemo_card",
    "carddemo_transaction",
    "carddemo_reference",
    "carddemo_batch",
    "carddemo_authorization",
    "carddemo_reporting",
  ]

  database_workload_names = toset([
    "auth",
    "account",
    "card",
    "transaction",
    "reference",
    "authorization",
    "reporting",
    "batch",
  ])

  # WHY : Assumptions: the seven workloads that terminate an HTTP request, which is
  #       database_workload_names less batch. It is declared as its own list rather than
  #       derived with setsubtract so that the membership test reads as a statement about
  #       what these services DO -- each one runs a resource server that validates a
  #       bearer token -- instead of as an arithmetic accident of another list. A ninth
  #       workload added to this repository joins whichever list matches its behaviour,
  #       and the ecs-service authorisation clause that reads this set will refuse it
  #       until it joins the right one.
  request_serving_workload_names = toset([
    "auth",
    "account",
    "card",
    "transaction",
    "reference",
    "authorization",
    "reporting",
  ])

  lambda_names = {
    quiesce           = "${var.name_prefix}-${var.environment}-quiesce-online"
    resume            = "${var.name_prefix}-${var.environment}-resume-online"
    database_admin    = "${var.name_prefix}-${var.environment}-database-admin"
    dataset_retention = "${var.name_prefix}-${var.environment}-dataset-retention"
  }

  lambda_log_group_names = {
    for key, function_name in local.lambda_names :
    key => "/aws/lambda/${function_name}"
  }

  # WHY : Refactoring Rationale: reporting-service needs the ad-hoc machine ARN
  #       in its task definition, while that state machine needs the reporting
  #       task-definition ARN. Constructing the deterministic ARN breaks that
  #       otherwise irreducible cycle; a contract assertion below compares it
  #       with the module's real output so naming drift fails the plan.
  adhoc_report_state_machine_arn = "arn:${data.aws_partition.current.partition}:states:${data.aws_region.current.region}:${data.aws_caller_identity.current.account_id}:stateMachine:${var.name_prefix}-${var.environment}-adhoc-report"

  # Assumptions: these FOUR key prefixes are the complete set of object keys the
  #   reporting workload touches, and every one of them is READ FROM THE MODULE
  #   THAT PROVISIONS IT rather than restated here. Three come from
  #   `non_generation_prefixes`, whose entries carry the literal prefixes
  #   services/reporting-service/src/main/resources/application.yml declares --
  #   `statement-prefix: statements/`, `report-prefix:
  #   reports/transaction-detail/` and `category-balance-prefix:
  #   reports/category-balance/`, all three literals in the base document
  #   precisely so the layout is the same in every environment. The fourth is the
  #   `tranrept` generation family, which the nightly report publishes its second
  #   copy to. Nothing else in the bucket is a reporting artifact.
  #
  # Refactoring Rationale: this list held two literal strings and now reads the
  #   module's own outputs. The literals were correct when written and stopped
  #   being complete twice over: the nightly report gained a second destination
  #   under the `tranrept` generation prefix -- the family had no production
  #   writer at all before, so the lifecycle rule provisioned for it governed
  #   nothing -- and the category-balance report was added as a third fixed-key
  #   artifact. Both would have been written by a role with no grant for them,
  #   which fails at run time and not at plan time. Reading the outputs means a
  #   prefix added to the module is granted here by construction.
  # Alternatives Considered: keeping the literals and adding two more. Rejected:
  #   the same prefix would then be written down in the service configuration, in
  #   the module's variable and in both environment roots, and three of the four
  #   places have no gate that would notice a disagreement.
  #
  # Refactoring Rationale: the reporting task role held `s3:ListBucket` on the
  #   whole bucket and `s3:GetObject`, `s3:PutObject` and
  #   `s3:AbortMultipartUpload` on every object in it. That bucket also holds the
  #   ten nightly dataset generation families, including the transaction backup
  #   and combined generations, whose records carry an unmasked
  #   `TRAN-CARD-NUM PIC X(16)` at offset 262 of the 350-byte layout at
  #   app/cpy/CVTRA05Y.cpy. So the grant let a reporting task enumerate and read
  #   every primary account number the system has ever posted, which is neither
  #   something the workload does nor something it should be able to do. Scoping
  #   the grant to the four prefixes above removes the capability without removing
  #   any behaviour: the two orchestrated states this role serves write into
  #   those prefixes and read nothing.
  # Assumptions: `tranrept` is the ONE generation family this role reaches, and it
  #   is reached for `ListBucket` as well as for writing. The nightly publication
  #   numbers its generation by listing the date prefix it is about to write
  #   under, so a write grant alone would leave it unable to choose a number.
  #   Granting one family's prefix is not the whole-bucket grant this block
  #   removed: the transaction backup and combined generations stay unreachable.
  #
  # Trade-offs: a prefix condition and an object-ARN restriction are BOTH
  #   applied, rather than either alone, because they bound different calls. An
  #   object ARN cannot bound `ListBucket`, whose resource is the bucket itself
  #   and whose scope is expressed only by the `s3:prefix` condition key; and a
  #   prefix condition does not apply to `GetObject` or `PutObject`, whose scope
  #   is expressed only by the object ARN. Applying one and not the other would
  #   leave the other call unbounded.
  reporting_object_key_prefixes = concat(
    values(module.s3_datasets.non_generation_prefixes),
    [module.s3_datasets.dataset_prefixes["tranrept"]],
  )

  # Assumptions: ONE prefix, and it is the prefix the authorization-extract state
  #   machine composes its two destination keys under. It is declared here rather than
  #   written into the policy document below so that the grant and the machine's key
  #   layout cannot drift apart silently -- the machine's Command.$ expressions build
  #   `authorization/extract/dt=<date>/run=<execution>/roots.dat` and `.../children.dat`,
  #   and a grant narrower than that prefix presents as an access-denied error on
  #   PutObject rather than as a configuration mistake.
  # Trade-offs: the grant covers the whole prefix rather than one run's keys, because a
  #   run's keys contain its own execution name and no policy can be written before the
  #   execution exists. The narrowing that remains is real and is the one that matters:
  #   this role reaches nothing else in a bucket that also holds every nightly
  #   transaction generation.
  authorization_extract_key_prefix = "authorization/extract/"
}

# -----------------------------------------------------------------------------
# Lambda deployment packages.
# -----------------------------------------------------------------------------

data "archive_file" "online_write_flag" {
  type        = "zip"
  source_file = "${path.root}/../../lambda/online_write_flag.py"
  output_path = "${path.root}/.terraform/online-write-flag.zip"
}

data "archive_file" "database_admin" {
  type        = "zip"
  output_path = "${path.root}/.terraform/database-admin.zip"

  source {
    content  = file("${path.root}/../../lambda/database_admin.py")
    filename = "database_admin.py"
  }

  source {
    content  = file("${path.root}/../../../data-migration/sql/V0__schemas_and_roles.sql")
    filename = "V0__schemas_and_roles.sql"
  }
}

data "archive_file" "dataset_retention" {
  type        = "zip"
  source_file = "${path.root}/../../lambda/dataset_generation_retention.py"
  output_path = "${path.root}/.terraform/dataset-generation-retention.zip"
}

# -----------------------------------------------------------------------------
# Foundational modules and internal TLS material.
# -----------------------------------------------------------------------------

module "cloudfront_spa" {
  source = "../../modules/cloudfront-spa"

  name_prefix    = var.name_prefix
  environment    = var.environment
  s3_kms_key_arn = module.kms.s3_key_arn
  price_class    = var.cloudfront_price_class
  force_destroy  = !var.deletion_protection

  # WHY : Assumptions: the certificate and the alias list are ONE decision and
  #       both are required by the module. A distribution answering only on its
  #       generated cloudfront.net name has to use CloudFront's default
  #       certificate, which pins the viewer security policy to TLSv1, so
  #       "no custom domain" is not an available state in either environment.
  #       Both values are deployment-specific and arrive as TF_VAR_* rather
  #       than from terraform.tfvars.
  acm_certificate_arn = var.cloudfront_acm_certificate_arn
  aliases             = var.cloudfront_aliases
  log_retention_days  = var.log_retention_days

  # WHY : Assumptions: no access-log retention value is passed because the module
  #       publishes no access-log destination -- CloudFront standard logging
  #       records the resolved viewer URI, and this SPA's routes carry account,
  #       card and transaction identifiers. Route-level request history comes from
  #       the API Gateway access log instead, which records the matched route key.
  api_connect_src_origins = var.cloudfront_api_connect_src_origins

  # WHY : Assumptions: this is an ORDERING token, not a value the module uses. The
  #       log-delivery resource asserts it is non-empty so delivery cannot be
  #       created before the S3 key policy that grants the delivery service its
  #       data-key permission exists; without the edge, Terraform is free to create
  #       delivery first and the service rejects the destination.
  #       Assumptions: this does NOT close a cycle, because the distribution itself
  #       depends only on the KEY (s3_kms_key_arn) while the key POLICY is a
  #       separate resource -- so the chain is delivery -> key policy ->
  #       distribution -> key, which terminates.
  s3_kms_key_policy_id = module.kms.s3_key_policy_id
}

module "kms" {
  source = "../../modules/kms"

  name_prefix                 = var.name_prefix
  environment                 = var.environment
  deletion_window_in_days     = var.environment == "dev" ? 7 : 30
  cloudfront_distribution_arn = module.cloudfront_spa.distribution_arn

  # WHY : Assumptions: naming the buckets narrows every S3 grant on this key from
  #       "any bucket that references the key" to these four exact buckets, through
  #       the aws:s3:arn encryption context. It is safe to wire because a bucket
  #       depends on the KEY, not on the key POLICY, so the policy can wait for the
  #       bucket ARNs without either waiting for the other.
  s3_encryption_context_bucket_arns = [
    module.s3_datasets.bucket_arn,
    module.s3_datasets.audit_bucket_arn,
    module.cloudfront_spa.spa_bucket_arn,
    module.cloudfront_spa.log_bucket_arn,
  ]

  # WHY : Assumptions: the CloudWatch Logs delivery service generates the data key
  #       that encrypts each delivered access-log object, so it needs its own grant,
  #       narrowed to this one delivery source.
  cloudwatch_log_delivery_source_arns = [module.cloudfront_spa.log_delivery_source_arn]

  # WHY : Alternatives Considered: also wiring cloudwatch_log_group_arns and
  #       sns_topic_arns, which would narrow those two grants from an
  #       account-and-region pattern to exact ARNs. REJECTED on an apply-ordering
  #       ground rather than a security one: every log group here is created WITH
  #       this key, and CreateLogGroup fails with AccessDenied unless the key policy
  #       already permits the Logs service. Passing the group ARNs makes the policy
  #       depend on the groups, so Terraform would create a group before the policy
  #       that authorizes it and the apply would fail. The same holds for the SNS
  #       topic, which is created with the key. The module therefore renders its
  #       pattern-scoped statements for those two purposes, bounded to this account,
  #       this region and this deployment's name prefix.
}

# -----------------------------------------------------------------------------
# GitHub Actions SPA publication role.
# -----------------------------------------------------------------------------

data "aws_iam_policy_document" "spa_publication_assume_role" {
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [var.github_oidc_provider_arn]
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }

    # WHY : Assumptions: GitHub's environment subject binds this role to the
    #       named repository AND this Terraform environment. Repository
    #       environment protection rules then control which branches/reviewers
    #       may request the token; a branch wildcard in IAM is neither needed
    #       nor accepted.
    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:sub"
      values   = ["repo:${var.github_repository}:environment:${var.environment}"]
    }
  }
}

resource "aws_iam_role" "spa_publication" {
  name                 = "${var.name_prefix}-${var.environment}-spa-publication"
  description          = "OIDC-assumed GitHub Actions role that publishes only the ${var.environment} SPA bundle."
  assume_role_policy   = data.aws_iam_policy_document.spa_publication_assume_role.json
  max_session_duration = 3600
}

data "aws_iam_policy_document" "spa_publication" {
  statement {
    sid       = "ListSpaOrigin"
    actions   = ["s3:GetBucketLocation", "s3:ListBucket", "s3:ListBucketMultipartUploads"]
    resources = [module.cloudfront_spa.spa_bucket_arn]
  }

  statement {
    sid       = "SynchronizeSpaObjects"
    actions   = ["s3:AbortMultipartUpload", "s3:DeleteObject", "s3:GetObject", "s3:ListMultipartUploadParts", "s3:PutObject"]
    resources = ["${module.cloudfront_spa.spa_bucket_arn}/*"]
  }

  statement {
    sid = "EncryptSpaObjects"
    actions = [
      "kms:Decrypt",
      "kms:DescribeKey",
      "kms:Encrypt",
      "kms:GenerateDataKey*",
      "kms:ReEncrypt*",
    ]
    resources = [module.kms.s3_key_arn]
  }

  statement {
    sid       = "InvalidatePublishedSpa"
    actions   = ["cloudfront:CreateInvalidation", "cloudfront:GetInvalidation"]
    resources = [module.cloudfront_spa.distribution_arn]
  }
}

resource "aws_iam_role_policy" "spa_publication" {
  name   = "${var.name_prefix}-${var.environment}-spa-publication"
  role   = aws_iam_role.spa_publication.id
  policy = data.aws_iam_policy_document.spa_publication.json
}

module "network" {
  source = "../../modules/network"

  name_prefix             = var.name_prefix
  environment             = var.environment
  vpc_cidr                = var.vpc_cidr
  app_container_port      = 8080
  database_port           = 5432
  flow_log_retention_days = var.log_retention_days
  flow_log_kms_key_arn    = module.kms.s3_key_arn
}

module "ecr" {
  source = "../../modules/ecr"

  name_prefix  = var.name_prefix
  environment  = var.environment
  kms_key_arn  = module.kms.s3_key_arn
  force_delete = !var.deletion_protection
}

module "aurora" {
  source = "../../modules/aurora-postgresql"

  name_prefix                  = var.name_prefix
  environment                  = var.environment
  isolated_subnet_ids          = module.network.isolated_data_subnet_ids
  security_group_ids           = [module.network.data_security_group_id]
  kms_key_arn                  = module.kms.aurora_key_arn
  secrets_kms_key_arn          = module.kms.secrets_key_arn
  engine_version               = var.aurora_engine_version
  parameter_group_family       = var.aurora_parameter_group_family
  port                         = module.network.database_port
  min_capacity                 = var.aurora_min_capacity
  max_capacity                 = var.aurora_max_capacity
  seconds_until_auto_pause     = var.aurora_seconds_until_auto_pause
  backup_retention_period      = var.aurora_backup_retention_period
  preferred_backup_window      = var.aurora_preferred_backup_window
  preferred_maintenance_window = var.aurora_preferred_maintenance_window
  deletion_protection          = var.deletion_protection
  skip_final_snapshot          = var.skip_final_snapshot
  enable_http_endpoint         = true
}

# =============================================================================
# Internal listener material -- DELIBERATELY ABSENT from this root.
# -----------------------------------------------------------------------------
# WHY : Refactoring Rationale: this position held a key generator, a self-signed
#       leaf, an imported ACM certificate and two Secrets Manager entries with
#       their versions -- eight resources that produced ONE RSA private key, wrote
#       it into Terraform state, imported it into ACM, copied it into Secrets
#       Manager and injected it into every online task. Anyone able to read this
#       environment's state file held the server private key of all eight services
#       at once, and the comments here asserted the opposite. All eight are
#       deleted. Each task now mints its OWN key pair and self-signed certificate
#       at startup, in config/docker/generate-listener-material.sh, onto the task's
#       encrypted ephemeral volume, and that material is destroyed with the task.
#       No listener private key exists in state, in Secrets Manager, in a task
#       definition or in plan output, and no two tasks share one.
# WHY : Assumptions: the imported ACM certificate was already DEAD before this
#       change. Its ARN was referenced only through
#       `coalesce(var.alb_certificate_arn, ...)`, and var.alb_certificate_arn is
#       `nullable = false` with no default, so the coalesce could never select it --
#       the root created an ACM certificate on every apply that no listener ever
#       used, while persisting its private key. Deleting it removes a resource, a
#       cost and a key custody, and changes no behaviour.
# WHY : Alternatives Considered: keeping the chain and generating the key with the
#       tls provider's EPHEMERAL resource, writing it through the aws provider's
#       write-only `secret_string_wo` and `private_key_wo` arguments. All three
#       mechanisms exist in the pinned provider versions, and `terraform validate`
#       still refuses the wiring: "Ephemeral values are not valid for
#       \"private_key_pem\", because it is not a write-only attribute and must be
#       persisted to state." The self-signed-certificate resource has no write-only
#       attribute, so the key could be kept out of state only by giving up the
#       certificate entirely.
# WHY : Alternatives Considered: an AWS Private CA, so that ACM generates and holds
#       the key. Rejected on recurring cost for one internal listener behind a
#       private integration, and unnecessary once the tasks certify themselves. The
#       generator script's header records the third rejected option, a Lambda that
#       mints and imports the pair, together with the measurement of the Lambda
#       runtime contents that rules it out.
# =============================================================================

# -----------------------------------------------------------------------------
# Messaging HMAC key.
# -----------------------------------------------------------------------------
#
# WHY : Assumptions: this key exists so that the pending-authorization queue's
#       FIFO group identity can be a purpose-scoped opaque derivation of the card
#       number instead of the card number itself. A group identifier is message
#       METADATA: it sits outside the encrypted body, it is reported in queue
#       telemetry, and it is carried into every log and metric that observes the
#       queue -- which is exactly where ADR-008 requires an account number to be
#       masked. docs/adr/ADR-004-messaging.md states the requirement under
#       "Ordering is grouped by card"; the derivation and its purpose string are
#       specified in docs/architecture/messaging-contracts.md.
# WHY : Refactoring Rationale: this secret did not exist, and only
#       CARDDEMO_MASK_HMAC_KEY was provisioned -- to the data-migration workload
#       alone. authorization therefore had no key at all, so the one per-card
#       stable value it held was the card number and the card number became the
#       published group identity on every reply. Adding the secret here, rather
#       than widening the mask key's distribution, is what keeps the two trust
#       purposes separable.
# WHY : Alternatives Considered: reusing var.mask_hmac_secret_arn for both
#       purposes, which is one fewer secret to provision and rotate. Rejected on
#       two counts: it would give a one-off migration workload that reads
#       cardholder extracts the ability to compute production queue group
#       identities, and rotating either purpose would then require a coordinated
#       stop of an interactive consumer and a batch workload together.
# WHY : Alternatives Considered: accepting the key as an input variable, the way
#       var.mask_hmac_secret_arn is accepted. Rejected because an operator-supplied
#       value has a tfvars file to be committed in, and because nothing outside
#       this stack produces or consumes this key -- unlike the mask key, whose
#       tags must stay stable across extract loads that may predate this root.
#       Generating it here means the "no secrets committed" constraint holds
#       structurally rather than by reviewer vigilance.
# WHY : Assumptions: this is generated ONCE per environment and shared by every
#       producer on the queue, and it is deliberately not per task or per apply in
#       effect. The group identity must be equal for equal cards across producers
#       and across restarts, because that equality IS the per-card ordering
#       guarantee; a value that changed per task would scatter one card's messages
#       across as many groups as there are running tasks and remove the ordering
#       silently. The write-only version pinned to 1 below is what stops an
#       unrelated plan re-issuing it.

ephemeral "random_password" "messaging_hmac" {
  # WHY : Assumptions: this is EPHEMERAL rather than a managed random_password, so
  #       the generated key is available while the provider writes it to Secrets
  #       Manager and is absent from Terraform state afterwards. A managed resource
  #       retains its result in every state file and in every plan artifact, which
  #       for key material means the state file becomes as sensitive as the secret
  #       store it was meant to keep the material out of. This is the same control
  #       infra/modules/secrets applies to the database credentials.
  # WHY : Assumptions: the length is 64 printable characters, which is twice the
  #       32-byte floor com.carddemo.common.security.OpaqueIdentifier enforces.
  #       Sixty-four is chosen rather than exactly 32 because the Java side accepts
  #       the value as raw text when it is not valid base64, so the character count
  #       is the byte count -- and sitting at the floor would make any future
  #       trimming or encoding change fail at container start.
  #       Trade-offs: special characters are excluded. They add entropy per
  #       character, and they are excluded because this value travels as an
  #       environment variable through a task definition and a shell-quoting
  #       accident on any operator path would corrupt the key silently rather than
  #       visibly; the extra length more than compensates for the smaller alphabet.
  length  = 64
  special = false
}

# WHY : Assumptions: the card-selector signing key is generated here and never
#       authored, so no selector key exists in source. It follows the same
#       write-only shape as the messaging key below: an ephemeral generator whose
#       result reaches only secret_string_wo, so the value is absent from state.
# WHY : Assumptions: a SEPARATE secret from the messaging and internal-identity
#       keys, rather than one key reused. Only card-service holds this one, and the
#       biconditional precondition in infra/modules/ecs-service asserts that from
#       both sides; sharing a key would let any holder mint a row address that
#       card-service would open.
ephemeral "random_password" "card_selector" {
  length  = 64
  special = false
}

resource "aws_secretsmanager_secret" "card_selector" {
  #checkov:skip=CKV2_AWS_57:A selector minted under one key cannot be opened under another, and services/card-service/src/main/resources/application.yml records why that matters here: a selector is a card row's stable address and must keep opening for as long as a client might hold one, unlike a pagination cursor, which names a position in one browse and is meant to expire. An unattended rotation function would therefore invalidate every selector already issued, and each single-card route a client reached from a list it still has on screen would stop resolving. Rotation is an attended procedure that reissues selectors with the deployment, documented in docs/runbooks/deploy.md, rather than an automatic one. The four sibling key secrets in this root -- the messaging HMAC key, the two per-caller internal-identity signing keys and the pagination cursor key -- each carry the same exception for the same class of reason, every one naming the runbook section that performs its own rotation. This sentence said TWO siblings, which was already short and became shorter when the single internal-identity key was split per caller.
  name        = "${var.name_prefix}/${var.environment}/card/selector-signing-key"
  description = "Purpose-scoped key the CardDemo card service seals and opens opaque card row selectors under, in the ${var.environment} environment. Generated by this root and injected into the task as a scalar secret."

  kms_key_id              = module.kms.secrets_key_arn
  recovery_window_in_days = var.secret_recovery_window_in_days
}

resource "aws_secretsmanager_secret_version" "card_selector" {
  secret_id = aws_secretsmanager_secret.card_selector.id

  # WHY : Assumptions: written through secret_string_wo, the write-only argument, so
  #       the generated value never enters state -- the same discipline every other
  #       generated secret in this root uses.
  secret_string_wo = ephemeral.random_password.card_selector.result

  # WHY : Trade-offs: pinned to the literal 1, matching the sibling generated secrets in
  #       this root. The provider rewrites the stored value only when this version
  #       changes, so pinning it is what stops an unrelated plan replacing the key merely
  #       because the ephemeral generator produced fresh bytes. Here that rewrite would be
  #       especially damaging: every selector already issued was sealed under the previous
  #       key, so a silent rotation would make every list row a client still holds
  #       unopenable. Rotating deliberately means incrementing this literal, which is a
  #       visible plan change rather than a side effect.
  secret_string_wo_version = 1
}

resource "aws_secretsmanager_secret" "messaging_hmac" {
  #checkov:skip=CKV2_AWS_57:Rotating this key requires every producer on the pending-authorization queue to adopt the new value in the same instant, because the FIFO group identity must stay equal for equal cards across producers. An unattended rotation function would change the key for one reader at a time and split one card's in-flight messages across two groups, losing the ordering guarantee the key exists to preserve. Rotation is therefore an attended procedure documented in docs/runbooks/batch-operations.md rather than an automatic one.
  name        = "${var.name_prefix}/${var.environment}/messaging/hmac-key"
  description = "Purpose-scoped HMAC key the CardDemo authorization service derives pending-authorization queue group and correlation identities under, in the ${var.environment} environment. Generated by this root and injected into the task as a scalar secret. Distinct from the data-migration masking key."

  kms_key_id              = module.kms.secrets_key_arn
  recovery_window_in_days = var.secret_recovery_window_in_days
}

resource "aws_secretsmanager_secret_version" "messaging_hmac" {
  secret_id = aws_secretsmanager_secret.messaging_hmac.id

  # WHY : Assumptions: the value is written through secret_string_wo, the write-only
  #       argument, so it reaches Secrets Manager without being recorded in state.
  #       Pairing it with the ephemeral generator above is one control rather than
  #       two: either half alone would still leave the key in a state file.
  secret_string_wo = ephemeral.random_password.messaging_hmac.result

  # WHY : Trade-offs: pinned to the literal 1, matching infra/modules/secrets. It is
  #       what stops an unrelated plan rewriting the stored key merely because the
  #       ephemeral generator produced fresh bytes -- and here that rewrite would be
  #       worse than for a password, because a changed key changes every group
  #       identity at once and splits in-flight messages for every card
  #       simultaneously. Advancing it is the deliberate re-issue an attended
  #       rotation performs.
  secret_string_wo_version = 1
}

# -----------------------------------------------------------------------------
# Internal machine identity signing key
# -----------------------------------------------------------------------------
# Purpose:
#   The symmetric key the pending-authorization service signs its
#   machine-to-machine bearer token with and the account service verifies that
#   token against. It is the only credential standing between the three internal
#   account-context reads and an unauthenticated caller inside the network.
#
# Why this exists rather than a managed identity-provider grant:
#   Alternatives Considered: a client-credentials grant from the Cognito user
#   pool, which is the conventional way for one service to authenticate to
#   another and would need no key here at all. It is unavailable in this
#   topology: infra/modules/cognito provisions the pool with domain_prefix set to
#   null, so the pool exposes no hosted domain and therefore no token endpoint
#   for a machine caller to obtain a token from. Adding a hosted domain solely to
#   mint machine tokens was rejected as a wider change than the problem warrants
#   -- it would publish an additional internet-facing authentication surface for
#   a caller that never leaves the VPC.
#   Alternatives Considered: mutual TLS between the two tasks. Rejected as
#   disproportionate for a single caller inside one private network, since it
#   would introduce certificate issuance, distribution and rotation for two
#   services and place a certificate expiry on the authorization path.
#   Alternatives Considered: a static shared header value. Rejected outright: it
#   carries no expiry, names no audience and cannot be scoped, so one capture
#   would be a permanent credential for every internal read.
#
# Why it is a SECOND key rather than the messaging key above:
#   Assumptions: the two are separate secrets and the separation is deliberate.
#   The messaging key derives opaque queue-metadata tokens and is held by the
#   authorization service alone; this one is a signing key deliberately shared
#   with exactly one other service. Sharing a single value would mean that
#   rotating the account context's trust anchor also changed every FIFO group
#   identity in flight, and that a holder of either capability could exercise the
#   other.

# Why there are TWO keys rather than one shared by both callers:
#   Refactoring Rationale: this was one key held by the authorization service, the
#   transaction service and the account service. Because both callers signed with
#   the same bytes, either could mint a token carrying the OTHER's subject, and the
#   account service -- which examined no subject at all -- could not tell. Its
#   refusal and audit records therefore named a caller they had no way to verify,
#   and because one scope covered every internal address, the impersonation was
#   also worth performing. There is now one key per CALLING service, each labelled
#   with the subject it belongs to, and the account service selects the
#   verification key by the subject a presented token names -- so a token minted
#   with one caller's key under another caller's name fails its signature check.
#   Alternatives Considered: keeping one key and merely validating the subject
#   claim. Rejected because with a shared key the subject is a value any holder can
#   write, so validating it would refuse an unknown name while still admitting a
#   known one written by the wrong party.
#   Trade-offs: two secrets are two things to provision and rotate rather than one,
#   and each rotation still has to be attended because a signer and its verifier
#   must adopt a new value together. The cost is accepted because the two rotations
#   are now INDEPENDENT: re-issuing one caller's key stalls that caller alone,
#   where re-issuing the shared key stalled both at once.

ephemeral "random_password" "internal_identity_authorization" {
  # WHY : Assumptions: EPHEMERAL rather than a managed random_password, for the same
  #       reason as the messaging key above -- a managed resource retains its result
  #       in every state file and every plan artifact, which for a signing key would
  #       make the state file as sensitive as the secret store.
  # WHY : Assumptions: 64 printable characters, twice the 32-byte floor
  #       com.carddemo.common.security.InternalServiceToken enforces and that
  #       account-service config/InternalApiSecurityConfig.java enforces
  #       independently. Sixty-four rather than exactly 32 because both Java sides
  #       accept the value as raw text when it is not valid base64, so the character
  #       count is the byte count, and sitting at the floor would make any future
  #       encoding change fail at container start on both services at once.
  #       Trade-offs: special characters are excluded. They add entropy per
  #       character and are excluded because this value travels as an environment
  #       variable through two task definitions, so a shell-quoting accident on any
  #       operator path would corrupt it silently; the extra length more than
  #       compensates for the smaller alphabet.
  length  = 64
  special = false
}

ephemeral "random_password" "internal_identity_transaction" {
  # WHY : Assumptions: generated INDEPENDENTLY of the authorization key above rather
  #       than derived from it. A derived value would mean a holder of one could
  #       compute the other, which is precisely the impersonation the split closes.
  #       Every other property matches the sibling above for the same reasons.
  length  = 64
  special = false
}

resource "aws_secretsmanager_secret" "internal_identity_authorization" {
  #checkov:skip=CKV2_AWS_57:Rotating this key requires BOTH the authorization service and the account service to adopt the new value in the same instant, because one signs with it and the other verifies against it. An unattended rotation function would change the stored value while one of the two tasks still held the old one, and every internal account-context read from the authorization service would be refused with a 401 for the duration -- which stalls the authorization consumer rather than degrading it. Rotation is therefore an attended procedure that redeploys both services together, documented in docs/runbooks/deploy.md, rather than an automatic one.
  name        = "${var.name_prefix}/${var.environment}/internal-identity/authorization-signing-key"
  description = "Symmetric signing key for the AUTHORIZATION service's internal machine-to-machine bearer tokens in the ${var.environment} environment: minted by that service under the subject carddemo-authorization-service, verified by the account service against this key alone. Generated by this root and injected into exactly those two tasks as a scalar secret. Distinct from the transaction service's own internal-identity key, from the messaging HMAC key and from the data-migration masking key."

  kms_key_id              = module.kms.secrets_key_arn
  recovery_window_in_days = var.secret_recovery_window_in_days
}

resource "aws_secretsmanager_secret" "internal_identity_transaction" {
  #checkov:skip=CKV2_AWS_57:Rotating this key requires BOTH the transaction service and the account service to adopt the new value in the same instant, because one signs with it and the other verifies against it. An unattended rotation function would change the stored value while one of the two tasks still held the old one, and every internal account-context read from the transaction service would be refused with a 401 for the duration -- which fails every transaction add and every bill payment. Rotation is therefore an attended procedure that redeploys both services together, documented in docs/runbooks/deploy.md, rather than an automatic one.
  name        = "${var.name_prefix}/${var.environment}/internal-identity/transaction-signing-key"
  description = "Symmetric signing key for the TRANSACTION service's internal machine-to-machine bearer tokens in the ${var.environment} environment: minted by that service under the subject carddemo-transaction-service, verified by the account service against this key alone. Generated by this root and injected into exactly those two tasks as a scalar secret. Distinct from the authorization service's own internal-identity key, from the messaging HMAC key and from the data-migration masking key."

  kms_key_id              = module.kms.secrets_key_arn
  recovery_window_in_days = var.secret_recovery_window_in_days
}

resource "aws_secretsmanager_secret_version" "internal_identity_authorization" {
  secret_id = aws_secretsmanager_secret.internal_identity_authorization.id

  # WHY : Assumptions: written through secret_string_wo, the write-only argument, so
  #       the value reaches Secrets Manager without being recorded in state. Pairing
  #       it with the ephemeral generator above is one control rather than two:
  #       either half alone would still leave the key in a state file.
  secret_string_wo = ephemeral.random_password.internal_identity_authorization.result

  # WHY : Trade-offs: pinned to the literal 1, matching infra/modules/secrets and the
  #       messaging key above. Here an unintended rewrite is worse than for a
  #       password, because the two services read the stored value at task start:
  #       rewriting it without redeploying both leaves one signing with a key the
  #       other does not verify, and the symptom is a 401 on every internal read from
  #       this caller with nothing in either service's configuration having changed.
  #       Advancing it is the deliberate re-issue an attended rotation performs.
  secret_string_wo_version = 1
}

resource "aws_secretsmanager_secret_version" "internal_identity_transaction" {
  secret_id = aws_secretsmanager_secret.internal_identity_transaction.id

  secret_string_wo         = ephemeral.random_password.internal_identity_transaction.result
  secret_string_wo_version = 1
}

# -----------------------------------------------------------------------------
# Pagination cursor signing key
# -----------------------------------------------------------------------------
# Purpose:
#   The symmetric key com.carddemo.common.web.CursorToken seals and opens keyset
#   pagination cursors with. Every list operation in this deployment returns its
#   page boundaries as sealed tokens rather than as raw keys, so without this key
#   no service that publishes a list can construct the bean at all -- the
#   auto-configuration in
#   services/common-lib/src/main/java/com/carddemo/common/CardDemoCommonAutoConfiguration.java
#   resolves carddemo.pagination.cursor.signing-key with NO default and withholds
#   the bean when it is unset, and a controller that requires the bean then fails
#   context refresh.
#
# Why the key exists at all, rather than returning raw keys:
#   Assumptions: the values being sealed are the row keys the baseline browse
#   carried in its communication area -- a sixteen-character card number, a
#   sixteen-character transaction identifier. Returning them raw would publish a
#   primary account number in a response body and then accept it back in a query
#   string, where the load balancer's mandatory access log records it verbatim.
#   Sealing makes the cursor opaque to the client and unforgeable, so a caller
#   cannot page into rows the query never scoped to it.
#
# Why this is a THIRD key rather than reusing either key above:
#   Assumptions: purpose-scoping is the control, not key economy. This key is held
#   by the SEVEN services holding a component whose constructor requires the
#   CursorToken bean; the messaging key is held by the authorization consumer alone
#   and each of the two per-caller internal-identity keys by exactly two services,
#   its minter and account-service as the verifier. Refactoring Rationale: this last
#   clause said "the internal-identity key by exactly three services", which
#   describes the single shared key that was split per caller precisely so that a
#   caller's subject became verifiable rather than asserted; the paragraph below
#   already records the split, so the two disagreed on the same page.
#   Refactoring Rationale: this said FOUR, and it was stale in both directions --
#   the distribution gate below named five services at the time, and the measured
#   holder set is seven. A count that matches neither the code beside it nor the
#   tree it describes is worse than none, because it reads as corroboration. Sharing one value would mean a holder of any one capability
#   could exercise the others -- a service able to seal a cursor could mint an
#   internal bearer token -- and rotating any one purpose would invalidate every
#   other at the same moment.
#   Trade-offs: five secrets cost five entries to provision and five attended
#   rotations rather than one. That is accepted because the failure a shared key
#   admits is a privilege escalation across contexts, while the cost of separate
#   keys is only operational. Every one of the five carries a recorded
#   CKV2_AWS_57 exception naming the runbook section that performs its rotation.
#   Refactoring Rationale: this block called the cursor key "a THIRD key", said it
#   was "held by the four services that publish a paged list", and said "the
#   internal-identity key" was held "by exactly three services". All three were
#   wrong and they were wrong in different ways. There are five key secrets, not
#   three. The cursor key is held by SEVEN services -- auth, account, card,
#   transaction, reference, authorization and reporting, which is every service in
#   the distribution gate below except batch and data-migration -- and
#   characterising them as "the services that publish a paged list" is what made
#   the earlier count of four look plausible, so the holder set is stated as the set
#   that BINDS the name rather than as a property of what a service publishes. This
#   paragraph said FIVE and named auth-service and card-service as deliberately
#   outside the set; both hold it, and a count contradicting the paragraph three
#   lines above it is worse than none because a reader takes the more specific of
#   the two. And there is no longer one internal-identity key held by three
#   services: there are two, one per calling caller, each held by exactly two --
#   its minter and the verifier -- which is what makes a caller's subject
#   verifiable rather than merely asserted. The inventory is spelled out above so
#   that a future key cannot be added without this list disagreeing with the
#   gates below.

ephemeral "random_password" "pagination_cursor" {
  # WHY : Assumptions: EPHEMERAL rather than a managed random_password, for the same
  #       reason as the FOUR keys above, every one of which is also ephemeral -- a
  #       managed resource retains its result in
  #       every state file and every plan artifact, which for a signing key would make
  #       the state file as sensitive as the secret store it exists to keep the
  #       material out of.
  # WHY : Assumptions: 48 characters, and this length is arithmetic rather than taste.
  #       This key is the ONE of the three that the Java side requires to be BASE64 --
  #       CardDemoCommonAutoConfiguration.decodeSigningKey uses a strict decoder and
  #       raises naming the property when the value is not base64, with NO raw-text
  #       fallback of the kind the internal-identity and messaging keys rely on. The
  #       value stored below is therefore base64encode() of these characters, and 48
  #       characters encode to exactly 64 base64 characters that decode back to 48
  #       bytes -- comfortably above the 32-byte floor CursorToken.MIN_KEY_LENGTH
  #       imposes, with no padding ambiguity because 48 is divisible by three.
  #       Alternatives Considered: generating 64 characters and storing them directly,
  #       as the four keys above all do -- each generates length 64 with special = false
  #       and stores the characters unencoded. Rejected here because it would only APPEAR
  #       to work: with
  #       special characters excluded the alphabet is alphanumeric, every character of
  #       which happens to be in the base64 alphabet, and 64 is a multiple of four, so
  #       the strict decoder would accept the raw string and silently use a different
  #       48 bytes than anyone intended. That accident breaks the moment the length is
  #       changed to a value that is not a multiple of four, and it breaks at container
  #       start on all seven consuming services at once. Encoding explicitly makes the
  #       byte count a stated fact rather than a coincidence of the alphabet.
  #       Trade-offs: special characters are excluded. They add entropy per character
  #       and are excluded because the encoded value travels as an environment variable
  #       through seven task definitions, so a shell-quoting accident on any operator
  #       path would corrupt it silently; base64's own alphabet is shell-safe and the
  #       length more than compensates for the smaller input alphabet.
  length  = 48
  special = false
}

resource "aws_secretsmanager_secret" "pagination_cursor" {
  #checkov:skip=CKV2_AWS_57:Rotating this key invalidates every cursor currently held by a client, because a token sealed under the old key cannot be opened under the new one. An unattended rotation function would do that at an arbitrary moment and every operator mid-browse would receive a refused cursor with nothing having changed on their side. Rotation is therefore an attended procedure timed outside the online window and documented in docs/runbooks/deploy.md, and it is safe to perform because a refused cursor costs a re-listing rather than data.
  name        = "${var.name_prefix}/${var.environment}/pagination/cursor-signing-key"
  description = "Purpose-scoped symmetric key the CardDemo services seal and open keyset pagination cursors with in the ${var.environment} environment, read by com.carddemo.common.web.CursorToken. Generated by this root and injected as a scalar secret into exactly the seven services holding a component whose constructor requires the CursorToken bean. Distinct from the internal-identity signing key, the messaging HMAC key and the data-migration masking key."

  kms_key_id              = module.kms.secrets_key_arn
  recovery_window_in_days = var.secret_recovery_window_in_days
}

resource "aws_secretsmanager_secret_version" "pagination_cursor" {
  secret_id = aws_secretsmanager_secret.pagination_cursor.id

  # WHY : Assumptions: written through secret_string_wo, the write-only argument, so
  #       the value reaches Secrets Manager without being recorded in state. Pairing
  #       it with the ephemeral generator above is one control rather than two: either
  #       half alone would still leave the key in a state file.
  # WHY : Assumptions: base64encode() wraps the generated characters because this key
  #       is consumed through a STRICT base64 decoder. It is the ONLY one of the five that
  #       is base64-encoded at rest; the four keys above accept raw text, which is why
  #       each of them stores 48 fewer decisions than this one does. Storing the characters unencoded would make the stored value
  #       and the value the Java side derives two different byte sequences, and the
  #       divergence is invisible until a client redeems a cursor.
  secret_string_wo = base64encode(ephemeral.random_password.pagination_cursor.result)

  # WHY : Trade-offs: pinned to the literal 1, matching infra/modules/secrets and both
  #       keys above. It is what stops an unrelated plan rewriting the stored key
  #       merely because the ephemeral generator produced fresh bytes -- and that
  #       rewrite would refuse every cursor held by every client at once, across all
  #       seven holding services, without any configuration having changed. Advancing it is the
  #       deliberate re-issue an attended rotation performs.
  secret_string_wo_version = 1
}

# -----------------------------------------------------------------------------
# Reporting artifact identity key
# -----------------------------------------------------------------------------
# Purpose:
#   The symmetric key com.carddemo.common.security.OpaqueIdentifier derives the
#   token that NAMES a stored statement artifact under. reporting-service
#   publishes each statement as two objects in the dataset bucket, and
#   config/ArtifactIdentityConfig.java binds
#   carddemo.reporting.artifact.hmac-key through a fallback-free @Value that
#   refuses a blank value -- so without this secret the reporting task does not
#   start degraded, it fails context refresh and is replaced.
#
# Why the key exists at all, rather than naming the object after the cardholder:
#   Assumptions: an object key is METADATA, not content. The store writes it to
#   its own access log for every request that touches the object, indexes it for
#   listing, and reports it in a bucket inventory -- and server-side encryption
#   reaches none of those, because it protects the object's BYTES. So whatever a
#   key spells out is disclosed to a wider audience than the cardholder the
#   statement belongs to, and listing a prefix would enumerate the portfolio
#   without a single object being read.
#   Assumptions: the derivation is KEYED rather than a bare digest for the reason
#   OpaqueIdentifier itself records: an account identifier is eleven digits and a
#   card number sixteen, so an unkeyed digest of either is confirmed by guessing
#   across a space small enough to enumerate. A confirmable token discloses the
#   value it was meant to withhold while looking like a control.
#
# Why this is a FOURTH generated key rather than reusing one above:
#   Assumptions: purpose-scoping is the control, not key economy. This key is
#   held by reporting alone, the pagination key by the list-publishing services,
#   the internal-identity key by exactly three services and the messaging key by
#   the authorization consumer. infra/modules/ecs-service asserts each holder set
#   biconditionally, so a shared value would additionally have to be handed to
#   workloads those gates refuse. Sharing one value would also mean a holder of
#   any single capability could exercise the others, and rotating one purpose
#   would invalidate all four at once -- which here means every statement object
#   already written becomes unlocatable by the application's own lookup.
#   Trade-offs: a fourth secret costs a fourth entry to provision and a fourth
#   attended rotation. That is accepted because the failure a shared key admits
#   is a privilege escalation across contexts, while the cost is only
#   operational.

ephemeral "random_password" "reporting_artifact" {
  # WHY : Assumptions: EPHEMERAL rather than a managed random_password, for the same
  #       reason as the three keys above -- a managed resource retains its result in
  #       every state file and every plan artifact, which for key material would make
  #       the state file as sensitive as the secret store it exists to keep the
  #       material out of.
  # WHY : Assumptions: 48 characters, encoded below, and the arithmetic is deliberate
  #       rather than copied. config/ArtifactIdentityConfig.java decodes base64 FIRST
  #       and uses the result only when it reaches OpaqueIdentifier's 32-byte floor,
  #       falling back to the raw text otherwise. A 64-character alphanumeric value --
  #       the shape the messaging key uses -- would therefore take the base64 branch by
  #       coincidence of the alphabet rather than by intent, and the branch it takes
  #       decides which bytes the tokeniser uses. Generating 48 characters and storing
  #       base64encode() of them makes the byte count a stated fact: 48 characters
  #       encode to exactly 64 base64 characters that decode back to 48 bytes, half
  #       again above the floor, with no padding ambiguity because 48 divides by three.
  #       Alternatives Considered: storing 64 raw characters, as the messaging and
  #       internal-identity keys do. Rejected here because the consequence of the
  #       branch flipping is worse for this key than for those: the derived bytes
  #       change, so every token changes, so every statement object already written
  #       stops being locatable by the lookup the application performs -- and nothing
  #       fails, which is what makes it worse than a start-up error.
  #       Trade-offs: special characters are excluded. They add entropy per character
  #       and are excluded because the encoded value travels as an environment variable
  #       through a task definition, so a shell-quoting accident on any operator path
  #       would corrupt it silently; base64's own alphabet is shell-safe and the length
  #       more than compensates for the smaller input alphabet.
  length  = 48
  special = false
}

resource "aws_secretsmanager_secret" "reporting_artifact" {
  #checkov:skip=CKV2_AWS_57:A token minted under one key cannot be recomputed under another, and the artifact token is how the application locates a statement object it wrote on an earlier night. An unattended rotation function would therefore orphan every statement already published -- the objects remain, correctly encrypted, and nothing can name them again short of a full listing -- and it would do so at an arbitrary moment with no failure to observe. Rotation is an attended procedure timed outside the statement window and documented in docs/runbooks/deploy.md, performed together with a re-publication of the affected generations. The three sibling generated secrets in this root carry the same exception for the same class of reason.
  name        = "${var.name_prefix}/${var.environment}/reporting/artifact-hmac-key"
  description = "Purpose-scoped key the CardDemo reporting service derives stored statement artifact object-key tokens under, in the ${var.environment} environment, read by com.carddemo.common.security.OpaqueIdentifier. Generated by this root and injected as a scalar secret into the reporting task alone. Distinct from the pagination cursor key, the internal-identity signing key, the messaging HMAC key and the data-migration masking key."

  kms_key_id              = module.kms.secrets_key_arn
  recovery_window_in_days = var.secret_recovery_window_in_days
}

resource "aws_secretsmanager_secret_version" "reporting_artifact" {
  secret_id = aws_secretsmanager_secret.reporting_artifact.id

  # WHY : Assumptions: written through secret_string_wo, the write-only argument, so
  #       the value reaches Secrets Manager without being recorded in state. Pairing it
  #       with the ephemeral generator above is one control rather than two: either half
  #       alone would still leave the key in a state file.
  # WHY : Assumptions: base64encode() wraps the generated characters so the byte count
  #       the tokeniser receives is the one the generator's length states, rather than
  #       whichever branch of the reader's decode the raw characters happened to satisfy.
  #       The generator's comment above records why that distinction matters for this key
  #       specifically.
  secret_string_wo = base64encode(ephemeral.random_password.reporting_artifact.result)

  # WHY : Trade-offs: pinned to the literal 1, matching infra/modules/secrets and the
  #       three keys above. It is what stops an unrelated plan rewriting the stored key
  #       merely because the ephemeral generator produced fresh bytes -- and that rewrite
  #       would silently rename every future artifact while leaving the previously
  #       published ones under names nothing recomputes. Advancing it is the deliberate
  #       re-issue an attended rotation performs, alongside re-publishing the artifacts.
  secret_string_wo_version = 1
}

module "ecs_cluster" {
  source = "../../modules/ecs-cluster"

  name_prefix = var.name_prefix
  environment = var.environment

  # Assumptions: OVERRIDE is what makes the two arguments below take effect. The
  #   module's own variables.tf refuses OVERRIDE unless a log-group name accompanies
  #   it, so this line and the next cannot drift apart into the inconsistent pair.
  execute_command_logging = "OVERRIDE"

  # Assumptions: the group's own `name` attribute is referenced rather than a
  #   composed string, and that choice is what orders the apply. ECS validates the
  #   referenced group when the cluster is written, so it must already exist;
  #   referencing the resource makes Terraform infer that edge itself. A composed
  #   string would carry no edge, and a `depends_on = [module.observability]` added to
  #   supply one produces a CYCLE, because observability already depends on this
  #   cluster for its name and on the services for their target-group suffixes. That
  #   is also why the group is owned here rather than added to observability's managed
  #   set: the consumer of a group cannot be upstream of the module that creates it.
  execute_command_log_group_name = aws_cloudwatch_log_group.ecs_execute_command.name
  kms_key_arn                    = module.kms.s3_key_arn
}

# WHY : Assumptions: this group is created by the ROOT, which is the layer that also
#       creates this environment's parameters, secrets and private zone, and it is
#       created here for an ordering reason rather than a stylistic one -- see the
#       cycle note above. infra/modules/ecs-cluster deliberately creates no log group,
#       taking the name as an input so that one resource has exactly one owner; before
#       this resource existed nothing created it at all, so the cluster referenced a
#       group that would never appear and every ECS Exec session would have failed to
#       start.
# WHY : Assumptions: encrypted with the same key the cluster's session configuration
#       names. The two must agree: the session channel is encrypted under
#       kms_key_arn, and a group encrypted under a different key -- or under the
#       service key -- would leave the stored transcript protected by something other
#       than what the channel promised.
resource "aws_cloudwatch_log_group" "ecs_execute_command" {
  name              = "/aws/ecs/${var.name_prefix}-${var.environment}/execute-command"
  retention_in_days = var.log_retention_days
  kms_key_id        = module.kms.s3_key_arn

  tags = { Name = "${var.name_prefix}-${var.environment}-ecs-execute-command" }
}

# -----------------------------------------------------------------------------
# Runtime control parameters and Lambda IAM.
# -----------------------------------------------------------------------------

resource "aws_ssm_parameter" "online_writes_enabled" {
  name        = "${local.parameter_prefix}/${var.environment}/batch/online-writes-enabled"
  description = "Runtime gate set false while the nightly posting chain owns the write window."
  type        = "String"
  value       = "true"

  lifecycle {
    # WHY : Assumptions: quiesce/resume Lambdas own this value after creation.
    #       Terraform retains the resource and metadata without undoing a live
    #       batch-window transition during an unrelated apply.
    ignore_changes = [value]
  }
}

# -----------------------------------------------------------------------------
# Online-write bracket lease
# -----------------------------------------------------------------------------
# WHY : Refactoring Rationale: this table did not exist, and without it neither
#       online-write function could start at all. infra/lambda/online_write_flag.py
#       resolves LEASE_TABLE_NAME through _required_environment at IMPORT time, so a
#       deployment with no table and no environment variable fails at the quiesce
#       function's cold start -- which is the first state of the nightly chain, so
#       the whole chain could never run. The lease is what makes the bracket an
#       OWNERSHIP record rather than a bare boolean: the flag reports whether writes
#       are permitted, and this item records which execution is entitled to change
#       that.
# WHY : Alternatives Considered: holding the owner in the SSM parameter alongside the
#       boolean, which needs no new resource. Rejected because Parameter Store has no
#       compare-and-set primitive -- a read followed by a write is not atomic, so two
#       executions starting in the same instant would both conclude they had acquired
#       the bracket. A conditional write is the whole mechanism, and DynamoDB is the
#       cheapest managed store in this stack that has one.
# WHY : Trade-offs: PAY_PER_REQUEST rather than provisioned capacity. The access
#       pattern is a handful of writes per night plus one strongly-consistent read
#       per reconcile cycle, so provisioned capacity would bill continuously for a
#       table that is idle almost all of the time and would additionally need a
#       capacity decision nobody can inform.
resource "aws_dynamodb_table" "online_write_lease" {
  name         = "${var.name_prefix}-${var.environment}-online-write-lease"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "LeaseName"

  attribute {
    name = "LeaseName"
    type = "S"
  }

  # WHY : Assumptions: TTL is CLEAN-UP and never the expiry mechanism. DynamoDB
  #       deletes an expired item opportunistically -- the documented window is up to
  #       48 hours -- so a lease's expiry has to be enforced by the condition
  #       expressions in the function, which compare expiresAt against the current
  #       time on every write. TTL is enabled anyway so an abandoned item eventually
  #       leaves the table instead of sitting there for ever confusing an operator
  #       who reads it.
  ttl {
    attribute_name = "expiresAt"
    enabled        = true
  }

  server_side_encryption {
    enabled = true

    # WHY : Assumptions: the SQS key is reused rather than a fifth key created. This
    #       item is coordination metadata -- an execution name, an ARN and two epoch
    #       seconds -- and carries no cardholder data, so it belongs with the other
    #       operational-messaging resources rather than under the Aurora key that
    #       protects record data.
    kms_key_arn = module.kms.sqs_key_arn
  }

  # WHY : Trade-offs: point-in-time recovery is enabled even though the table holds
  #       one short-lived item. It costs storage on a table measured in bytes, and it
  #       is what lets an investigation reconstruct who held the bracket at a moment
  #       in the past -- which is exactly the question asked after a night where
  #       online writes stayed disabled.
  point_in_time_recovery {
    enabled = true
  }

  tags = { Name = "${var.name_prefix}-${var.environment}-online-write-lease" }
}

data "aws_iam_policy_document" "lambda_assume_role" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["lambda.${data.aws_partition.current.dns_suffix}"]
    }
  }
}

resource "aws_iam_role" "lambda" {
  for_each = {
    online_write      = "online-write"
    database_admin    = "database-admin"
    dataset_retention = "dataset-retention"
  }

  name               = "${var.name_prefix}-${var.environment}-${each.value}-lambda"
  assume_role_policy = data.aws_iam_policy_document.lambda_assume_role.json
}

# WHY : Assumptions: the marker the batch state machines stamp on every task they
#       launch is composed HERE, in the one place that wires both the state-machine
#       module and the resume function, because both need the identical literal. The
#       module stamps it as StartedBy and filters ListTasks by it; the function
#       filters the same call by it when a reconciling release has to prove no task
#       is still writing. A second spelling of it in either place would be a filter
#       that matches nothing, which reads as "no tasks are running" and would let a
#       release proceed while one was.
# WHY : Trade-offs: 36 characters is the smallest length any published surface states
#       for the field, so the value is composed to fit inside it and the module's own
#       validation REFUSES anything longer rather than truncating. At this root's
#       8-character name prefix the composed value is well inside the bound.
locals {
  batch_task_started_by = substr("${var.name_prefix}-${var.environment}-sfn", 0, 36)

  lambda_role_log_keys = {
    online_write      = ["quiesce", "resume"]
    database_admin    = ["database_admin"]
    dataset_retention = ["dataset_retention"]
  }
}

data "aws_iam_policy_document" "lambda_logs" {
  for_each = local.lambda_role_log_keys

  statement {
    sid     = "WriteOwnLambdaLogs"
    actions = ["logs:CreateLogStream", "logs:PutLogEvents"]
    resources = [
      for key in each.value :
      "${module.observability.managed_log_group_arns[key]}:*"
    ]
  }
}

data "aws_iam_policy_document" "online_write_lambda" {
  source_policy_documents = [data.aws_iam_policy_document.lambda_logs["online_write"].json]

  # WHY : Assumptions: the handler READS the flag before it writes it, so the grant
  #       needs GetParameter as well as PutParameter. The read is what makes the
  #       quiesce call a lease acquisition rather than a blind overwrite -- it is how
  #       the function learns whether another execution already owns the write window
  #       -- so the two actions are one capability and are granted together.
  # WHY : Trade-offs: both actions are scoped to this ONE parameter ARN rather than to
  #       a path prefix. A prefix would survive renaming the parameter without an IAM
  #       edit; naming the ARN means a rename fails the plan instead, which is the
  #       preferred failure for a resource this role exists solely to toggle.
  statement {
    sid       = "UpdateOnlineWriteGate"
    actions   = ["ssm:GetParameter", "ssm:PutParameter"]
    resources = [aws_ssm_parameter.online_writes_enabled.arn]
  }

  # WHY : Assumptions: the four item actions are ONE capability and are granted
  #       together because the handler uses all four across its two edges -- a
  #       conditional PutItem to acquire, a conditional UpdateItem to claim a
  #       release, a conditional DeleteItem to complete it, and a
  #       strongly-consistent GetItem to report who holds a lease it was refused.
  #       Withholding any one of them turns a working edge into an access-denied at
  #       the moment the bracket is being taken or given up.
  # WHY : Trade-offs: scoped to the table ARN rather than to a leading-key condition.
  #       The table holds exactly one item and this role is the only writer, so a key
  #       condition would restrict nothing that the table scope does not already.
  statement {
    sid = "ArbitrateOnlineWriteLease"
    actions = [
      "dynamodb:GetItem",
      "dynamodb:PutItem",
      "dynamodb:UpdateItem",
      "dynamodb:DeleteItem",
    ]
    resources = [aws_dynamodb_table.online_write_lease.arn]
  }

  # WHY : Assumptions: the lease table is encrypted with a customer-managed key, so
  #       every item call needs key use as well as table access -- a role with the
  #       four actions above and no kms grant fails on the first write with an access
  #       denied naming KMS rather than DynamoDB. The condition ties the grant to
  #       DynamoDB's use of the key, so it cannot decrypt an SQS message that happens
  #       to be under the same key.
  statement {
    sid       = "UseLeaseTableKey"
    actions   = ["kms:Decrypt", "kms:GenerateDataKey"]
    resources = [module.kms.sqs_key_arn]

    condition {
      test     = "StringEquals"
      variable = "kms:ViaService"
      values   = ["dynamodb.${data.aws_region.current.region}.amazonaws.com"]
    }
  }

  # WHY : Assumptions: the second half of the same decision. An ECS task started by a
  #       synchronous run-task state OUTLIVES the state that started it when that
  #       state times out or its execution is aborted, so a terminal execution does
  #       not imply terminal writers; the function lists tasks carrying the chain's
  #       startedBy marker and reads their lastStatus before releasing. ListTasks and
  #       DescribeTasks take the cluster condition AWS's own identity-based policy
  #       example uses for them, so neither can reach another cluster.
  statement {
    sid       = "ConfirmBatchTasksTerminal"
    actions   = ["ecs:ListTasks", "ecs:DescribeTasks"]
    resources = ["*"]

    condition {
      test     = "ArnEquals"
      variable = "ecs:cluster"
      values   = [module.ecs_cluster.cluster_arn]
    }
  }
}

data "aws_iam_policy_document" "database_admin_lambda" {
  source_policy_documents = [data.aws_iam_policy_document.lambda_logs["database_admin"].json]

  statement {
    sid = "UseAuroraDataApi"
    actions = [
      "rds-data:BeginTransaction",
      "rds-data:CommitTransaction",
      "rds-data:ExecuteStatement",
      "rds-data:RollbackTransaction",
    ]
    resources = [module.aurora.cluster_arn]
  }

  statement {
    sid       = "ReadRdsManagedMasterSecret"
    actions   = ["secretsmanager:GetSecretValue"]
    resources = [module.aurora.master_user_secret_arn]
  }

  statement {
    sid       = "DecryptRdsManagedMasterSecret"
    actions   = ["kms:Decrypt"]
    resources = [module.kms.aurora_key_arn]
  }

  # WHY : Assumptions: the bootstrap function reads every per-role credential because
  #       data-migration/sql/V0__schemas_and_roles.sql applies them and can only do so
  #       from session settings the CALLER supplies -- the script fails closed on any
  #       login role it finds no value for. The grant is enumerated from
  #       module.secrets' own output rather than written as a prefix wildcard, so it
  #       covers exactly the entries that module created and shrinks or grows with the
  #       role inventory instead of standing open over every secret sharing the name
  #       prefix. The Cognito seed-user entries sit under that same prefix, which is
  #       what a wildcard here would additionally have reached.
  # WHY : Trade-offs: this function therefore holds read access to all sixteen database
  #       credentials at once, which is more than any service task holds -- each task
  #       reads exactly its own. That concentration is inherent to a bootstrap step and
  #       is bounded three ways: the function has no other permission, it is invoked
  #       only by Terraform through aws_lambda_invocation, and neither it nor the
  #       statements it sends puts a credential in text a log could capture.
  statement {
    sid       = "ReadServiceCredentialSecrets"
    actions   = ["secretsmanager:GetSecretValue"]
    resources = [for secret in module.secrets.service_credential_secrets : secret.arn]
  }

  # WHY : Assumptions: a second key, distinct from the Aurora key above. The RDS-managed
  #       master secret is encrypted under module.kms.aurora_key_arn and the per-role
  #       entries under module.kms.secrets_key_arn, so a single statement naming one key
  #       would leave every GetSecretValue above failing with an access-denied error
  #       that names the KEY rather than the missing grant.
  statement {
    sid       = "DecryptServiceCredentialSecrets"
    actions   = ["kms:Decrypt"]
    resources = [module.kms.secrets_key_arn]
  }
}

data "aws_iam_policy_document" "dataset_retention_lambda" {
  source_policy_documents = [data.aws_iam_policy_document.lambda_logs["dataset_retention"].json]
}

locals {
  lambda_policy_json = {
    online_write      = data.aws_iam_policy_document.online_write_lambda.json
    database_admin    = data.aws_iam_policy_document.database_admin_lambda.json
    dataset_retention = data.aws_iam_policy_document.dataset_retention_lambda.json
  }
}

resource "aws_iam_role_policy" "lambda" {
  for_each = local.lambda_policy_json

  name   = "${var.name_prefix}-${var.environment}-${each.key}"
  role   = aws_iam_role.lambda[each.key].id
  policy = each.value
}

# WHY : Refactoring Rationale: states:DescribeExecution is granted by a SEPARATE inline
#       policy on the same role, and the separation is forced rather than stylistic. The
#       grant has to name the daily machine's executions, so it reads
#       module.step_functions' output -- and that module takes both online-write
#       function ARNs as inputs, while aws_lambda_function.quiesce and .resume declare
#       depends_on = [aws_iam_role_policy.lambda]. Folding this statement into that
#       policy therefore closes a cycle: policy -> state machine -> function -> policy.
#       A second inline policy carries the same grant to the same role with no edge back
#       into the functions, and `terraform validate` is what proved the distinction --
#       the combined form is refused outright with the full cycle printed.
# WHY : Assumptions: it must NOT be added to either function's depends_on for the same
#       reason, which is why the attachment is deliberately unordered with respect to
#       them. The consequence is bounded and self-healing: a reconcile invocation that
#       fires in the gap between the functions being created and this policy landing
#       gets an access-denied from DescribeExecution, which the handler treats as
#       "terminality could not be established" and answers by REFUSING the release. It
#       reports that refusal and exits successfully, so the gap costs one skipped
#       reconcile cycle rather than an error, an alarm or a wrongly-granted release.
# WHY : Trade-offs: the resource is a wildcard over the daily machine's execution
#       identifiers, because an execution ARN ends in the execution NAME and those are
#       generated per run. The machine segment is exact, so the grant cannot read
#       another state machine's history -- and only the daily machine takes the bracket,
#       so the other three are deliberately absent.
data "aws_iam_policy_document" "online_write_reconcile" {
  statement {
    sid       = "ReadOwningExecutionStatus"
    actions   = ["states:DescribeExecution"]
    resources = ["${replace(module.step_functions.daily_state_machine_arn, ":stateMachine:", ":execution:")}:*"]
  }
}

resource "aws_iam_role_policy" "online_write_reconcile" {
  name   = "${var.name_prefix}-${var.environment}-online-write-reconcile"
  role   = aws_iam_role.lambda["online_write"].id
  policy = data.aws_iam_policy_document.online_write_reconcile.json
}

resource "aws_lambda_function" "quiesce" {
  function_name    = local.lambda_names.quiesce
  role             = aws_iam_role.lambda["online_write"].arn
  runtime          = "python3.13"
  handler          = "online_write_flag.handler"
  filename         = data.archive_file.online_write_flag.output_path
  source_code_hash = data.archive_file.online_write_flag.output_base64sha256
  timeout          = 30
  memory_size      = 128

  environment {
    variables = {
      PARAMETER_NAME  = aws_ssm_parameter.online_writes_enabled.name
      EXPECTED_ACTION = "quiesce"
      TARGET_VALUE    = "false"

      # WHY : Assumptions: the lease table is REQUIRED by the handler at import time,
      #       so it is named on both functions rather than only on the one that
      #       releases. The quiesce edge is the writer that takes the lease; a
      #       function configured without this variable does not fail on the write,
      #       it fails to start.
      LEASE_TABLE_NAME = aws_dynamodb_table.online_write_lease.name
    }
  }

  depends_on = [aws_iam_role_policy.lambda]
}

resource "aws_lambda_function" "resume" {
  function_name    = local.lambda_names.resume
  role             = aws_iam_role.lambda["online_write"].arn
  runtime          = "python3.13"
  handler          = "online_write_flag.handler"
  filename         = data.archive_file.online_write_flag.output_path
  source_code_hash = data.archive_file.online_write_flag.output_base64sha256
  timeout          = 30
  memory_size      = 128

  environment {
    variables = {
      PARAMETER_NAME  = aws_ssm_parameter.online_writes_enabled.name
      EXPECTED_ACTION = "resume"
      TARGET_VALUE    = "true"

      LEASE_TABLE_NAME = aws_dynamodb_table.online_write_lease.name

      # WHY : Assumptions: these two are set on the RESUME function only, because only
      #       a release confirms that the chain's tasks are terminal -- the reconciler
      #       always, and the bracket finalizer because it fires the instant an
      #       execution ends, when a task abandoned by a timed-out synchronous state
      #       may still be writing. The handler reads them lazily and REFUSES to
      #       release when either is blank, so a misconfiguration withholds a release
      #       rather than granting one it could not justify.
      # WHY : Assumptions: the marker comes from the root local that is ALSO passed to
      #       the state-machine module, so the string this function filters by and the
      #       string the tasks carry have one source. It cannot be read back from the
      #       module instead: that module takes this function's ARN as an input, so a
      #       module output here would close a dependency cycle Terraform refuses.
      BATCH_TASK_CLUSTER_ARN = module.ecs_cluster.cluster_arn
      BATCH_TASK_STARTED_BY  = local.batch_task_started_by
    }
  }

  depends_on = [aws_iam_role_policy.lambda]
}

resource "aws_lambda_function" "database_admin" {
  function_name    = local.lambda_names.database_admin
  role             = aws_iam_role.lambda["database_admin"].arn
  runtime          = "python3.13"
  handler          = "database_admin.handler"
  filename         = data.archive_file.database_admin.output_path
  source_code_hash = data.archive_file.database_admin.output_base64sha256
  timeout          = 300
  memory_size      = 512

  environment {
    variables = {
      DB_CLUSTER_ARN       = module.aurora.cluster_arn
      DB_MASTER_SECRET_ARN = module.aurora.master_user_secret_arn
      DB_NAME              = module.aurora.database_name
      BOOTSTRAP_SQL_FILE   = "V0__schemas_and_roles.sql"

      # WHY : Assumptions: the mapping is projected from module.secrets' output, so the
      #       secret NAMES are the ones that module composed and this root restates no
      #       part of its naming convention. A prefix-plus-role-list form was the
      #       alternative and would have put that convention in a fourth place -- the
      #       module, carddemo_migration.config, its contract test, and here -- where
      #       the copy that goes stale is the one nothing validates.
      # WHY : Trade-offs: a JSON object in an environment variable, roughly 1.5 KB at
      #       sixteen entries, against Lambda's 4 KB total. Names are used rather than
      #       ARNs for exactly that reason: ARNs carry an account, a region and a
      #       six-character suffix each, which would take the same mapping past 2.5 KB
      #       and leave little headroom. GetSecretValue resolves either, and the IAM
      #       statement above is scoped to the ARNs regardless.
      DB_CREDENTIAL_SECRETS = jsonencode({
        for role, secret in module.secrets.service_credential_secrets : role => secret.name
      })
    }
  }

  depends_on = [aws_iam_role_policy.lambda]
}

resource "aws_lambda_function" "dataset_retention" {
  function_name    = local.lambda_names.dataset_retention
  role             = aws_iam_role.lambda["dataset_retention"].arn
  runtime          = "python3.13"
  handler          = "dataset_generation_retention.handler"
  filename         = data.archive_file.dataset_retention.output_path
  source_code_hash = data.archive_file.dataset_retention.output_base64sha256
  timeout          = 120
  memory_size      = 256

  environment {
    variables = {
      RETENTION_COUNT = "5"
    }
  }

  depends_on = [aws_iam_role_policy.lambda]
}

resource "aws_lambda_invocation" "database_bootstrap" {
  function_name = aws_lambda_function.database_admin.function_name
  input         = jsonencode({ action = "bootstrap" })
  triggers = {
    function_code = data.archive_file.database_admin.output_base64sha256
    bootstrap_sql = filesha256("${path.root}/../../../data-migration/sql/V0__schemas_and_roles.sql")
    cluster_arn   = module.aurora.cluster_arn

    # WHY : Assumptions: a change to the ROLE INVENTORY has to re-invoke this, and none
    #       of the three triggers above notices one. Adding a bounded context changes
    #       the bootstrap SQL and so is already covered; replacing a stored credential
    #       is not, and the function is what binds a stored value to its PostgreSQL
    #       role. Hashing the mapping rather than embedding it keeps the trigger a fixed
    #       length and keeps sixteen secret names out of the plan diff.
    credential_inventory = sha256(jsonencode({
      for role, secret in module.secrets.service_credential_secrets : role => secret.name
    }))
  }

  # WHY : Assumptions: stated explicitly even though the trigger above already reads
  #       module.secrets, because the guarantee is about ORDER rather than about value
  #       resolution and a reader should not have to infer it from an expression. The
  #       bootstrap reads every per-role credential and its SQL refuses to commit
  #       without one, so every secret must exist and hold a generated value before the
  #       function is invoked even once.
  depends_on = [module.secrets]
}

module "secrets" {
  source = "../../modules/secrets"

  name_prefix             = var.name_prefix
  environment             = var.environment
  kms_key_arn             = module.kms.secrets_key_arn
  recovery_window_in_days = var.secret_recovery_window_in_days

  # WHY : Assumptions: the module is given the master role's NAME and no other
  #       cluster coordinate. It records the name in each credential document as
  #       the escalation identity, and it needs nothing else: the writer endpoint,
  #       the listener port and the database name are non-secret and are already
  #       published to Parameter Store by module.aurora under the same
  #       <prefix>/<environment>/aurora path the credential names are composed
  #       from, so a consumer reads the coordinates there and only the credential
  #       from Secrets Manager. Passing them to the secrets module as well would
  #       make it a second copy of values module.aurora owns.
  #       Alternatives Considered: passing the RDS-managed master secret ARN, as
  #       this root previously did. Rejected with the module-owned rotation
  #       function it existed to serve: a reusable credential-store module has no
  #       remit to hold a reference to the master credential of the cluster its
  #       consumers connect to.
  #       Assumptions: `database_master_username` is deliberately NOT passed. This
  #       root does not set module.aurora's `master_username` either, so both
  #       modules take their own default and those defaults are the same value by
  #       construction -- each states that it must match the other. Wiring the two
  #       together would mean publishing a `master_username` output from the aurora
  #       module, which has a deliberately closed output contract; widening it to
  #       restate a value neither module's caller overrides would buy nothing.
  #       Trade-offs: a root that ever does override the cluster's master role name
  #       must set this input to the same value in the same change, which is why
  #       both variables' descriptions name each other.

  # WHY : Assumptions: neither rotation input is supplied, so no rotation schedule
  #       is created. infra/modules/secrets deliberately implements no rotation and
  #       creates no rotation function -- the only rotation this package owns is KMS
  #       KEY rotation, in module.kms -- and its two rotation inputs are a
  #       pass-through hook for a root that brings a function of its own. This root
  #       brings none, so binding a stored credential to its PostgreSQL role remains
  #       the schema-bootstrap step's responsibility.
  #       Trade-offs: the accepted cost is that a credential is not re-issued on a
  #       schedule until an operator supplies a rotation function and its interval.
  #       Inventing one here instead would recreate, at the root, the same
  #       out-of-scope function that was removed from the module.
  # WHY : Refactoring Rationale: this module carried
  #       `depends_on = [aws_lambda_invocation.database_bootstrap]`, ordering credential
  #       CREATION after the bootstrap that consumes those credentials. The ordering was
  #       exactly backwards and it made a fresh apply unrunnable, not merely untidy:
  #       data-migration/sql/V0__schemas_and_roles.sql applies each credential from a
  #       session setting the caller supplies and RAISES for any login role it finds no
  #       value for, so on a first apply the bootstrap rolled back before any secret
  #       existed to read. The edge is removed and inverted -- the invocation below now
  #       depends on this module -- which is also the only order in which the function's
  #       DB_CREDENTIAL_SECRETS mapping can be resolved at all.
}

module "cognito" {
  source = "../../modules/cognito"

  name_prefix                    = var.name_prefix
  environment                    = var.environment
  secrets_kms_key_arn            = module.kms.secrets_key_arn
  callback_urls                  = ["${local.spa_origin}/callback"]
  logout_urls                    = [local.spa_origin]
  mfa_configuration              = var.environment == "prod" ? "ON" : "OPTIONAL"
  advanced_security_mode         = var.environment == "prod" ? "ENFORCED" : "AUDIT"
  deletion_protection            = var.deletion_protection ? "ACTIVE" : "INACTIVE"
  secret_recovery_window_in_days = var.secret_recovery_window_in_days

  # WHY : Assumptions: the module defaults this to an EMPTY list, so a root that
  #       says nothing gets a pool with no identities and no way to sign on. The
  #       list below is the baseline's TEN demo identities, transcribed field for
  #       field from app/jcl/DUSRSECJ.jcl L35-L44 -- the job that writes
  #       AWS.M2.CARDDEMO.USRSEC.PS -- at the SEC-USR-ID, SEC-USR-FNAME,
  #       SEC-USR-LNAME and SEC-USR-TYPE offsets of app/cpy/CSUSR01Y.cpy L18-L22.
  #       That covers the baseline's 'A'/'U' authorization split
  #       (app/cpy/COCOM01Y.cpy L27-L28) five identities either way.
  #       Refactoring Rationale: this list was two INVENTED identities, ADM00001
  #       and USR00001, and their intersection with the baseline was empty. That
  #       broke identity continuity end to end rather than merely being untidy.
  #       auth.users declares cognito_sub UUID NOT NULL UNIQUE and seeds no rows
  #       (V1__auth.sql), and the ETL reader loads its rows from USRSEC keyed on
  #       SEC-USR-ID -- so not one loaded row could find a subject, while the two
  #       identities that did have subjects matched no row. Every baseline id is
  #       now present, so every row the ETL loads has exactly one subject and
  #       every subject belongs to exactly one row.
  #       Assumptions: the names are carried in the baseline's UPPER CASE rather
  #       than title-cased. The ETL writes auth.users.first_name and last_name
  #       from those same record bytes, and the pool and the table have to agree
  #       on the value, so title-casing here would manufacture a mismatch on
  #       every one of the ten.
  #       Assumptions: SEC-USR-PWD is NOT transcribed. The baseline record carries
  #       the literal "PASSWORD" in the clear for all ten, and refusing to carry
  #       it is the one place this migration deliberately declines parity. Each
  #       initial credential is generated during apply and written to Secrets
  #       Manager; no password appears here or in any tfvars file.
  seed_users = [
    {
      user_id     = "ADMIN001"
      given_name  = "MARGARET"
      family_name = "GOLD"
      user_type   = "A"
    },
    {
      user_id     = "ADMIN002"
      given_name  = "RUSSELL"
      family_name = "RUSSELL"
      user_type   = "A"
    },
    {
      user_id     = "ADMIN003"
      given_name  = "RAYMOND"
      family_name = "WHITMORE"
      user_type   = "A"
    },
    {
      user_id     = "ADMIN004"
      given_name  = "EMMANUEL"
      family_name = "CASGRAIN"
      user_type   = "A"
    },
    {
      user_id     = "ADMIN005"
      given_name  = "GRANVILLE"
      family_name = "LACHAPELLE"
      user_type   = "A"
    },
    {
      user_id     = "USER0001"
      given_name  = "LAWRENCE"
      family_name = "THOMAS"
      user_type   = "U"
    },
    {
      user_id     = "USER0002"
      given_name  = "AJITH"
      family_name = "KUMAR"
      user_type   = "U"
    },
    {
      user_id     = "USER0003"
      given_name  = "LAURITZ"
      family_name = "ALME"
      user_type   = "U"
    },
    {
      user_id     = "USER0004"
      given_name  = "AVERARDO"
      family_name = "MAZZI"
      user_type   = "U"
    },
    {
      user_id     = "USER0005"
      given_name  = "LEE"
      family_name = "TING"
      user_type   = "U"
    },
  ]
}

module "sqs" {
  source = "../../modules/sqs"

  name_prefix = var.name_prefix
  environment = var.environment
  kms_key_arn = module.kms.sqs_key_arn
}

module "s3_datasets" {
  source = "../../modules/s3-datasets"

  name_prefix = var.name_prefix
  environment = var.environment
  kms_key_arn = module.kms.s3_key_arn
  # WHY : Refactoring Rationale: this call used to pass
  #       `object_created_lambda_arn = aws_lambda_function.dataset_retention.arn`,
  #       and the module declared the bucket notification and the invoke permission
  #       that wired it up. Both moved here, because an aws_s3_bucket_notification
  #       is a whole-bucket resource and a reusable module that claims it takes the
  #       bucket's only notification slot away from every consumer. The resources
  #       are declared below over module.s3_datasets.bucket_name, so the behaviour
  #       is unchanged and the ownership is where the function is.
  access_log_bucket_name = module.observability.access_log_bucket_name
  force_destroy          = !var.deletion_protection

  # WHY : Refactoring Rationale: this argument is NEW and it is what turns the
  #       module's network boundary from a comment into a wired control. The bucket
  #       previously refused only plaintext requests, so a dataset generation --
  #       records derived from the cardholder masters -- was readable over HTTPS from
  #       anywhere on the internet by any principal holding an IAM grant. The module
  #       now denies object reads and writes whose aws:SourceVpce is not this
  #       endpoint, and it needs the identifier to name it.
  # WHY : Assumptions: it reads module.network.s3_gateway_endpoint_id rather than a
  #       tfvars value, so the identifier can never be transcribed wrongly or point
  #       at a stale endpoint after a network replacement -- Terraform recomputes the
  #       dependency and the policy follows the endpoint. This is also the first real
  #       consumer of that output: it was published and unread, which is precisely the
  #       shape a review flags as a speculative contract.
  s3_gateway_endpoint_id = module.network.s3_gateway_endpoint_id
}

data "aws_iam_policy_document" "dataset_retention_s3" {
  statement {
    sid       = "ListDatasetGenerationPrefixes"
    actions   = ["s3:ListBucket"]
    resources = [module.s3_datasets.bucket_arn]
  }

  statement {
    sid       = "DeleteObsoleteGenerationObjects"
    actions   = ["s3:DeleteObject"]
    resources = ["${module.s3_datasets.bucket_arn}/*"]
  }
}

resource "aws_iam_role_policy" "dataset_retention_s3" {
  name   = "${var.name_prefix}-${var.environment}-dataset-retention-s3"
  role   = aws_iam_role.lambda["dataset_retention"].id
  policy = data.aws_iam_policy_document.dataset_retention_s3.json
}

# WHY : Refactoring Rationale: these two resources were previously declared inside
#       infra/modules/s3-datasets, from an `object_created_lambda_arn` input. They
#       are declared here now because an aws_s3_bucket_notification is a
#       WHOLE-BUCKET resource: a reusable module that declares one claims the
#       bucket's only notification slot for every consumer, and the function being
#       wired up belongs to this root, not to the module. The behaviour is
#       unchanged -- the same function, on the same event, over the same bucket.
# WHY : Assumptions: this hook and the module's own noncurrent-version lifecycle
#       rule are complementary rather than alternative. A lifecycle rule bounds the
#       VERSIONS of one object key, whereas each dataset generation is written under
#       a distinct `<family>/dt=.../gen=.../` key, so S3 cannot see generation six as
#       a version of generation five. Enforcing the five-generation limit
#       (the LIMIT(5) SCRATCH analogue of app/jcl/DEFGDGB.jcl) therefore needs a
#       function that lists prefixes, and it is invoked on object creation rather
#       than from a batch state so that ad-hoc and retry writers are covered too.
resource "aws_lambda_permission" "dataset_retention_from_s3" {
  statement_id  = "AllowDatasetGenerationRetentionFromS3"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.dataset_retention.function_name
  principal     = "s3.amazonaws.com"

  # WHY : Assumptions: both SourceArn and SourceAccount are stated. The bucket ARN
  #       binds invocation to this bucket, and the account condition blocks a
  #       confused-deputy request from a same-named bucket in another account --
  #       neither is redundant, because a bucket name is globally unique but an ARN
  #       alone does not prove which account asked.
  source_arn     = module.s3_datasets.bucket_arn
  source_account = data.aws_caller_identity.current.account_id
}

resource "aws_s3_bucket_notification" "dataset_generations" {
  bucket = module.s3_datasets.bucket_name

  lambda_function {
    lambda_function_arn = aws_lambda_function.dataset_retention.arn
    events              = ["s3:ObjectCreated:*"]
  }

  # WHY : Assumptions: the invoke permission must exist before S3 will validate and
  #       store a notification configuration, so this edge is required even though
  #       neither resource references the other. Without it a first apply fails
  #       although both the function and the bucket already exist.
  depends_on = [aws_lambda_permission.dataset_retention_from_s3]
}

# -----------------------------------------------------------------------------
# Runtime parameter publication.
# -----------------------------------------------------------------------------

locals {
  # WHY : Refactoring Rationale: all three of these were published to
  #       database_workload_names, which includes BATCH, and two of them do not
  #       belong to it. batch-service resolves neither
  #       SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI nor
  #       CARDDEMO_SECURITY_JWT_EXPECTED_CLIENT_ID in any of its profiles -- it runs
  #       Spring Batch jobs and serves no request, so it has no resource server for an
  #       issuer or an audience to configure. Handing it both meant a workload held
  #       token-validation configuration it cannot apply, and the ecs-service module's
  #       admissibility set could not object because admissibility is per NAME and not
  #       per workload. Splitting the loop is what lets that module assert the pairing.
  # WHY : Assumptions: the datasource URL genuinely IS common to all eight, so it stays
  #       on database_workload_names rather than being duplicated into both sets. batch
  #       is a database workload and not a request-serving one, and that is the whole
  #       distinction these two locals now draw.
  # WHY : Trade-offs: two loops where there was one, and a second workload list to keep
  #       in step. Accepted because the alternative that keeps one loop is to widen the
  #       ecs-service authorisation clause to admit batch, which would assert that batch
  #       is entitled to an audience expectation it has no reader for -- writing the
  #       over-grant into the contract instead of removing it.
  common_runtime_parameters = merge(concat([
    for service in local.database_workload_names : {
      "${service}|SPRING_DATASOURCE_URL" = {
        service          = service
        environment_name = "SPRING_DATASOURCE_URL"
        value            = local.jdbc_url
      }
    }
    ], [
    for service in local.request_serving_workload_names : {
      "${service}|SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI" = {
        service          = service
        environment_name = "SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI"
        value            = module.cognito.issuer_uri
      }
      "${service}|CARDDEMO_SECURITY_JWT_EXPECTED_CLIENT_ID" = {
        service          = service
        environment_name = "CARDDEMO_SECURITY_JWT_EXPECTED_CLIENT_ID"
        value            = module.cognito.user_pool_client_id
      }
    }
  ])...)

  special_runtime_parameters = {
    "auth|CARDDEMO_AUTH_COGNITO_USER_POOL_ID" = {
      service          = "auth"
      environment_name = "CARDDEMO_AUTH_COGNITO_USER_POOL_ID"
      value            = module.cognito.user_pool_id
    }

    # WHY : (1) Assumptions: both of the next two are published because
    #       services/auth-service/src/main/resources/application.yml resolves them
    #       with NO fallback -- credential-secret-prefix and
    #       credential-secret-kms-key-arn are bare ${...} references -- so a
    #       deployment that omits either does not start. That is deliberate on the
    #       service's side and it makes publishing them here mandatory rather than
    #       optional: the values are deployment addresses, and a default address is
    #       a wrong one that looks right.
    #       (2) Assumptions: the prefix is read from module.cognito rather than
    #       composed here. The service derives each entry's full name from it, and
    #       the IAM statement below scopes the write grant to a wildcard beneath the
    #       same value, so the two agree by construction; restating the module's
    #       naming rule in this root is what would let them disagree, and the
    #       failure would surface as an access denial on a user creation rather than
    #       at plan time.
    "auth|CARDDEMO_AUTH_CREDENTIAL_SECRET_PREFIX" = {
      service          = "auth"
      environment_name = "CARDDEMO_AUTH_CREDENTIAL_SECRET_PREFIX"
      value            = module.cognito.credential_secret_name_prefix
    }

    # WHY : Assumptions: the SECRETS key, which is the same key module.cognito
    #       encrypts its own seeded initial-password entries with -- it is passed
    #       secrets_kms_key_arn from this same source. One key for every pool
    #       credential means one key policy and one rotation schedule govern all of
    #       them, whereas a second key would give two entries holding the same class
    #       of value two different grant surfaces. The ARN is published rather than
    #       the alias because CreateSecret takes a key identifier that the store
    #       resolves under the CALLING identity, and an alias resolved that way is
    #       one more indirection that can be repointed without the statement below
    #       changing.
    "auth|CARDDEMO_AUTH_CREDENTIAL_SECRET_KMS_KEY_ARN" = {
      service          = "auth"
      environment_name = "CARDDEMO_AUTH_CREDENTIAL_SECRET_KMS_KEY_ARN"
      value            = module.kms.secrets_key_arn
    }
    "reference|CARDDEMO_REFERENCE_INQUIRY_REQUEST_QUEUE" = {
      service          = "reference"
      environment_name = "CARDDEMO_REFERENCE_INQUIRY_REQUEST_QUEUE"
      value            = module.sqs.date_inquiry_request_queue_url
    }
    "reference|CARDDEMO_REFERENCE_INQUIRY_REPLY_QUEUE" = {
      service          = "reference"
      environment_name = "CARDDEMO_REFERENCE_INQUIRY_REPLY_QUEUE"
      value            = module.sqs.inquiry_reply_queue_url
    }
    "reference|CARDDEMO_REFERENCE_INQUIRY_ERROR_QUEUE" = {
      service          = "reference"
      environment_name = "CARDDEMO_REFERENCE_INQUIRY_ERROR_QUEUE"
      value            = module.sqs.error_queue_url
    }
    "account|CARDDEMO_ACCOUNT_INQUIRY_REQUEST_QUEUE" = {
      service          = "account"
      environment_name = "CARDDEMO_ACCOUNT_INQUIRY_REQUEST_QUEUE"
      value            = module.sqs.account_inquiry_request_queue_url
    }
    "account|CARDDEMO_ACCOUNT_INQUIRY_REPLY_QUEUE" = {
      service          = "account"
      environment_name = "CARDDEMO_ACCOUNT_INQUIRY_REPLY_QUEUE"
      value            = module.sqs.inquiry_reply_queue_url
    }
    "account|CARDDEMO_ACCOUNT_INQUIRY_ERROR_QUEUE" = {
      service          = "account"
      environment_name = "CARDDEMO_ACCOUNT_INQUIRY_ERROR_QUEUE"
      value            = module.sqs.error_queue_url
    }
    # WHY : Refactoring Rationale: this parameter did not exist, and its absence made
    #       batch-service's whole error-sink configuration inert in every environment.
    #       config/SqsConfig.java is annotated
    #       @ConditionalOnProperty(carddemo.messaging.error-queue-url), so with nothing
    #       supplying the address the class contributed no client and no binding, the
    #       publisher bean did not exist, and the durable step ledger found an empty
    #       Optional and published nothing -- while that class's own documentation
    #       described what "a published event carries". The declared inventory named a
    #       publish path the running system did not have. Publishing the address here is
    #       one of the two halves that make it real; the other is the send-only grant the
    #       batch entry of local.sqs_permissions_by_workload below carries.
    # WHY : Assumptions: the value is the SAME terminal sink account-service and
    #       reference-service publish to, and that is deliberate rather than an
    #       oversight of a batch-specific queue. modules/sqs provisions ONE error queue
    #       as the migration plan's section 0.4.1.8 maps CARD.DEMO.ERROR, and a consumer
    #       tells the producers apart by the contentType message attribute --
    #       text/plain for the two fixed-width producers transcribing their reference
    #       error paragraphs, application/json for this one, which has no reference
    #       paragraph to transcribe because the batch programs of app/cbl carry no
    #       message-queue verb at all.
    # WHY : Trade-offs: only the ADDRESS is published, and the media type and the two
    #       ERR-APPLICATION / ERR-PROGRAM identifiers are not. Those three carry
    #       defaults at the binding method's own parameters, so publishing them would put
    #       a second copy of a value the image already holds into a place that can drift
    #       from it. The address has no default and cannot have one, which is exactly why
    #       it is the gate.
    # WHY : Refactoring Rationale: this parameter did not exist in either root, and its
    #       absence disabled a whole configuration class rather than one setting.
    #       batch-service's config/SqsConfig.java is annotated
    #       @ConditionalOnProperty(name = "carddemo.messaging.error-queue-url"), so with
    #       nothing publishing that name the queue client, the validated sink binding and
    #       the producer bean were all skipped -- and a batch run that failed put no
    #       message on the terminal error sink the state machine's failure-notification
    #       state exists to watch. Publishing it here is the configuration half of that
    #       fix; the IAM half is the batch_service entry in sqs_permissions_by_workload
    #       below, and the two must travel together because an address without the send
    #       action is an access-denied on the failure path and the action without the
    #       address is a grant nothing uses.
    # WHY : Assumptions: the value is the SAME module.sqs.error_queue_url the account and
    #       reference contexts already read for their own error-queue variables. There is
    #       one terminal sink for the whole deployment rather than one per bounded context,
    #       so three publishers addressing one queue is the design and not a duplication.
    # WHY : Assumptions: the variable is named for the shared messaging concern rather than
    #       for this context -- CARDDEMO_MESSAGING_ERROR_QUEUE_URL, not
    #       CARDDEMO_BATCH_ERROR_QUEUE -- because relaxed binding maps this exact spelling
    #       to the property the condition above names. A name following the sibling
    #       CARDDEMO_<CONTEXT>_* shape would bind nothing and would leave the gate closed
    #       while looking as though it had been supplied.
    "batch|CARDDEMO_MESSAGING_ERROR_QUEUE_URL" = {
      service          = "batch"
      environment_name = "CARDDEMO_MESSAGING_ERROR_QUEUE_URL"
      value            = module.sqs.error_queue_url
    }
    "authorization|CARDDEMO_MESSAGING_PAUTH_REQUEST_QUEUE" = {
      service          = "authorization"
      environment_name = "CARDDEMO_MESSAGING_PAUTH_REQUEST_QUEUE"
      value            = module.sqs.pauth_request_queue_url
    }
    # WHY : Refactoring Rationale: this parameter and the two below did not exist, and
    #       their absence stopped the service from starting at all rather than
    #       degrading it. authorization-service resolves
    #       carddemo.messaging.reply-queue-allowlist and
    #       carddemo.account-context.base-url from these names with NO default -- the
    #       first because an unset allowlist would otherwise leave every requester
    #       silently unanswered, the second because a default address would decline
    #       every authorization with the not-found reason. A placeholder no root
    #       supplies is an unresolvable placeholder, and Spring aborts context refresh
    #       on one, so the task crash-looped.
    # WHY : Assumptions: the allowlist is the pending-authorization REPLY queue and
    #       nothing else. The property is comma-separated so it can hold more than one
    #       address, and it is given exactly one here because this stack provisions
    #       exactly one reply destination; adding a second requester means adding its
    #       queue to this expression, which is the reviewable change it should be.
    # WHY : Assumptions: authorization-service compares each request's reply-to attribute
    #       against this list by EXACT match before it commits a reply, so the list is the
    #       set of destinations a reply may reach. The attribute is chosen by whoever can put
    #       a message on the request queue and a reply carries a card number and an
    #       authorization outcome, so an unlisted address is answered by no reply at all
    #       rather than by a reply sent somewhere unintended. That makes the composition
    #       self-consistent: the only destination the task role may send to is also the only
    #       one the application will send to. It is one entry rather than a wildcard, for the
    #       reason above.

    "authorization|CARDDEMO_MESSAGING_REPLY_QUEUE_ALLOWLIST" = {
      service          = "authorization"
      environment_name = "CARDDEMO_MESSAGING_REPLY_QUEUE_ALLOWLIST"
      value            = module.sqs.pauth_reply_queue_url
    }
    # WHY : Assumptions: the address is the INTERNAL load balancer's origin, reached
    #       over HTTPS on the certificate this root issues for that same name, and the
    #       account context is selected by the listener's path rules rather than by a
    #       distinct hostname. That is why the value is an origin with no path: the
    #       client appends its own three paths, and its base-address guard refuses a
    #       value carrying a path precisely so those three constants stay the complete
    #       description of what it requests.
    # WHY : Assumptions: this value and the approved origin below come from ONE
    #       expression, so the guard compares a value against itself in a deployed
    #       environment and cannot be tripped by a transcription difference between two
    #       tfvars entries. The guard's purpose is to refuse a base address changed
    #       WITHOUT a corresponding change here, which a shared expression makes a
    #       visible one-line diff rather than a silent one.
    "authorization|CARDDEMO_ACCOUNT_CONTEXT_BASE_URL" = {
      service          = "authorization"
      environment_name = "CARDDEMO_ACCOUNT_CONTEXT_BASE_URL"
      value            = local.account_context_origin
    }
    "authorization|CARDDEMO_ACCOUNT_CONTEXT_APPROVED_ORIGIN" = {
      service          = "authorization"
      environment_name = "CARDDEMO_ACCOUNT_CONTEXT_APPROVED_ORIGIN"
      value            = local.account_context_origin

    }
    # WHY : Refactoring Rationale: the transaction context needs the same address, and it was
    #       absent here while that context's own configuration read it with no default. Two of
    #       its four migrated programs resolve a card number through the account context's
    #       cross-reference -- READ-CXACAIX-FILE at app/cbl/COTRN02C.cbl:576 and
    #       app/cbl/COBIL00C.cbl:408 -- and the payment program also reads and updates the
    #       account balance there. Without this entry the task would fail to start on an
    #       unresolvable placeholder, which is the correct failure and the wrong place for it:
    #       the address is known here, from the same expression the authorization entry uses.
    # WHY : Assumptions: it reads local.account_context_origin rather than repeating the
    #       expression, so the two callers cannot be pointed at different addresses by an edit
    #       that updates one entry and not the other.
    "transaction|CARDDEMO_ACCOUNT_CONTEXT_BASE_URL" = {
      service          = "transaction"
      environment_name = "CARDDEMO_ACCOUNT_CONTEXT_BASE_URL"
      value            = local.account_context_origin
    }
    # WHY : Refactoring Rationale: the account context reads the reference context's three
    #       address allow-lists, which originate in app/cpy/CSLKPCDY.cpy at L521, L931, L1013 and
    #       L1073 and which the reference schema owns. app/cbl/COACTUPC.cbl edits all three on
    #       every account update, at lines 1600, 1635, 1643 and 1667, so the migrated update path
    #       needs the address. Its service/AddressValidationService.java is an annotated component
    #       whose only dependency is that lookup, so an absent entry stops the context starting
    #       rather than degrading one screen.
    "account|CARDDEMO_REFERENCE_CONTEXT_BASE_URL" = {
      service          = "account"
      environment_name = "CARDDEMO_REFERENCE_CONTEXT_BASE_URL"
      value            = local.reference_context_origin
    }
    "account|CARDDEMO_REFERENCE_CONTEXT_APPROVED_ORIGIN" = {
      service          = "account"
      environment_name = "CARDDEMO_REFERENCE_CONTEXT_APPROVED_ORIGIN"
      value            = local.reference_context_origin
    }
    "reporting|CARDDEMO_REPORTING_STEP_FUNCTIONS_STATE_MACHINE_ARN" = {
      service          = "reporting"
      environment_name = "CARDDEMO_REPORTING_STEP_FUNCTIONS_STATE_MACHINE_ARN"
      value            = local.adhoc_report_state_machine_arn
    }
    "reporting|CARDDEMO_REPORTING_S3_OUTPUT_BUCKET" = {
      service          = "reporting"
      environment_name = "CARDDEMO_REPORTING_S3_OUTPUT_BUCKET"
      value            = module.s3_datasets.bucket_name
    }

    # WHY : Assumptions: the ALIAS name, not the key identifier. An alias survives
    #       replacement of the key behind it, so a key rotation that replaces the
    #       key material's container does not require this task definition to be
    #       revised; a task still holding a replaced identifier would instead fail
    #       on its next card write.
    # WHY : Trade-offs: the consuming property carries NO default on the service
    #       side, deliberately. carddemo.security.cvv.key-id is read by
    #       com.carddemo.card.service.CardVerificationValueCipher through @Value and
    #       refused when blank, so a deployment that failed to publish this
    #       parameter does not start. The alternative -- a default key identifier --
    #       would let the service start against a key nobody chose, and the wrong
    #       key is not a recoverable mistake once values have been written under it:
    #       an envelope is readable only through the key that produced its data key.
    "card|CARDDEMO_SECURITY_CVV_KEY_ID" = {
      service          = "card"
      environment_name = "CARDDEMO_SECURITY_CVV_KEY_ID"
      value            = module.kms.aurora_key_alias_name
    }

    # WHY : Assumptions: the account workload's protected-identifier key alias, published for the same
    #       reasons as the card alias above and reading the SAME key. The two contexts protect
    #       different columns -- a card verification value there, a national identifier and a
    #       government-issued identifier here -- and they are kept apart not by separate keys but by the
    #       encryption CONTEXT each envelope carries, which is authenticated additional data. One key
    #       therefore means one rotation schedule and one grant to manage, while an envelope moved
    #       between the two contexts still fails its authentication check.
    # WHY : Refactoring Rationale: both aliases named a FIFTH customer-managed key that existed only for
    #       application-side envelope operations. That key was withdrawn: the specified model is four
    #       keys with rotation, one per data-at-rest domain, and both values these envelopes protect are
    #       Aurora columns -- so both parameters now publish the AURORA alias. Nothing changed on the
    #       service side; the property names, the ciphers and the two encryption contexts are unchanged,
    #       and the grant moved with the key it lives on.
    # WHY : Trade-offs: the consuming property carries NO default on the service side, deliberately.
    #       carddemo.security.customer-identifier.key-id is read by
    #       com.carddemo.account.config.CustomerIdentifierProtectionConfig through @Value and refused
    #       when blank, so a deployment that failed to publish this parameter does not start. A default
    #       would let the service start against a key nobody chose, and the wrong key is not a
    #       recoverable mistake once identifiers have been written under it.
    "account|CARDDEMO_SECURITY_CUSTOMER_IDENTIFIER_KEY_ID" = {
      service          = "account"
      environment_name = "CARDDEMO_SECURITY_CUSTOMER_IDENTIFIER_KEY_ID"
      value            = module.kms.aurora_key_alias_name
    }
  }

  runtime_parameters = merge(
    local.common_runtime_parameters,
    local.special_runtime_parameters,
  )

  platform_parameters = {
    "aurora/host"                       = module.aurora.writer_endpoint
    "aurora/port"                       = tostring(module.aurora.port)
    "aurora/database"                   = module.aurora.database_name
    "datasets/bucket"                   = module.s3_datasets.bucket_name
    "batch/daily-state-machine-arn"     = module.step_functions.daily_state_machine_arn
    "reporting/adhoc-state-machine-arn" = local.adhoc_report_state_machine_arn

    # WHY : Assumptions: the operator-invoked dataset round-trip machine is
    #       published here for discovery, alongside the daily machine, and it is
    #       taken from the module OUTPUT rather than composed from the name prefix.
    #       The ad-hoc report entry above is composed deterministically only because
    #       reporting-service's task definition and that machine would otherwise
    #       form a dependency cycle; no such cycle exists here, so the output is the
    #       correct source and a rename inside the module cannot leave this value
    #       pointing at a machine that does not exist.
    # WHY : Assumptions: publishing the ARN grants nothing. A principal that needs
    #       to start this machine is granted states:StartExecution on exactly this
    #       resource in its own runtime policy, as the reporting task is for the
    #       ad-hoc machine; an operator uses their own role and the exact command in
    #       docs/runbooks/batch-operations.md. This entry exists so neither has to
    #       compose an ARN by hand.
    "batch/dataset-roundtrip-state-machine-arn" = module.step_functions.dataset_roundtrip_state_machine_arn

    # WHY : Assumptions: the operator-invoked authorization-extract machine is
    #       published on the same reasoning as the round trip above -- from the module
    #       output, for discovery, and granting nothing by being published. It is under
    #       the `authorization/` prefix rather than `batch/` because the machine runs the
    #       authorization image against the authorization schema; filing it under batch
    #       would put it in the prefix the batch task role reads and imply an ownership
    #       that does not exist.
    "authorization/extract-state-machine-arn" = module.step_functions.authorization_extract_state_machine_arn

    # WHY : Assumptions: this is the ONE value the ETL cannot derive from the data
    #       it is loading. auth.users declares cognito_sub UUID NOT NULL UNIQUE
    #       (V1__auth.sql) and the 80-byte USRSEC record has no such field, so the
    #       reader has to be told each identity's subject by whoever minted it.
    #       Publishing it here reaches the ETL through the path it already uses:
    #       config.parameter_path("identity", "seed-user-subjects") resolves to
    #       exactly this name, and the data_migration_runtime policy in this root
    #       already grants ssm:GetParameter across this prefix, so the value needs
    #       no new grant.
    #       Alternatives Considered: a Terraform output the operator copies into
    #       the load command. Rejected because it makes a correct load depend on a
    #       manual transcription of ten UUIDs, and a mistyped one is a NOT NULL
    #       UNIQUE violation at best and a row bound to the wrong person at worst.
    #       Trade-offs: JSON in a single String parameter rather than one parameter
    #       per identity. A parameter per identity would be individually readable
    #       but would make the ETL guess which ids exist before it can ask for
    #       them; one document is fetched in one call and enumerates its own keys.
    #       It is a String rather than a SecureString because a subject is not a
    #       credential -- it is the value the identity provider already places in
    #       the sub claim of every token -- so encrypting it would imply a secrecy
    #       the value does not have and does not need.
    "identity/seed-user-subjects" = jsonencode(module.cognito.seed_user_subjects)
  }
}

resource "aws_ssm_parameter" "runtime" {
  for_each = local.runtime_parameters

  name        = "${local.parameter_prefix}/${var.environment}/${each.value.service}/${each.value.environment_name}"
  description = "Runtime value injected as ${each.value.environment_name} for ${each.value.service}."
  type        = "String"
  value       = each.value.value
}

resource "aws_ssm_parameter" "platform" {
  for_each = local.platform_parameters

  name        = "${local.parameter_prefix}/${var.environment}/${each.key}"
  description = "CardDemo ${var.environment} platform endpoint published by Terraform."
  type        = "String"
  value       = each.value
}

locals {
  runtime_parameter_arns_by_service = {
    for service in keys(local.workloads) :
    service => {
      for composite, parameter in aws_ssm_parameter.runtime :
      split("|", composite)[1] => parameter.arn
      if split("|", composite)[0] == service
    }
  }

  # WHY : Assumptions: every database workload except reporting applies its own
  #       Flyway migration, so every one of them except reporting needs a SECOND
  #       database credential. Measured against the tree: auth, account, card,
  #       transaction, reference, batch and authorization each own a
  #       src/main/resources/db/migration directory; reporting-service owns none,
  #       because the cross-schema views it reads are created by
  #       data-migration/sql/V1__reporting_views.sql rather than by the service.
  #       data-migration/sql/V0__schemas_and_roles.sql creates a
  #       carddemo_<context>_migrator role for exactly these seven and none for
  #       reporting, so a set derived by subtracting reporting is the same
  #       inventory the bootstrap SQL and infra/modules/secrets both hold. It is
  #       derived rather than written out so the two lists cannot disagree.
  migration_workload_names = setsubtract(local.database_workload_names, ["reporting"])

  # WHY : Assumptions: the two pairs below are two DIFFERENT database identities
  #       for one task, and that separation is the whole point. The
  #       SPRING_DATASOURCE_* pair carries the runtime role, which the bootstrap
  #       SQL grants USAGE on its schema plus SELECT, INSERT and UPDATE on its
  #       tables -- and explicitly REVOKEs CREATE from, so it can neither create
  #       nor drop nor alter anything, and cannot SET ROLE to the identity that
  #       can. The SPRING_FLYWAY_* pair carries carddemo_<context>_migrator, a
  #       member of the NOLOGIN schema owner WITH INHERIT FALSE: it can
  #       authenticate but owns nothing until the service's
  #       spring.flyway.init-sqls issues SET ROLE carddemo_<context>_owner, after
  #       which every object the migration creates belongs to the owner rather
  #       than to any credential a task holds.
  # WHY : Refactoring Rationale: this map projected only the SPRING_DATASOURCE_*
  #       pair, and the runtime role it names was also the owner of the schema and
  #       of every table Flyway created in it -- so the single long-lived
  #       credential that served every request could also ALTER and DROP the
  #       tables it read. Adding the migrator projection is what lets the
  #       bootstrap SQL reduce the runtime role to named DML, because the DDL a
  #       startup migration still legitimately needs now arrives under a second,
  #       separately granted secret.
  # WHY : Trade-offs: the migrator secret is injected into the SERVING task rather
  #       than into a separate migration task, so a serving container does hold a
  #       credential that can reach DDL authority for its own schema. That is the
  #       accepted cost of the shipped architecture, which applies migrations
  #       in-process before the JPA EntityManagerFactory is built so no task can
  #       start against a schema its own code predates. The residual exposure is
  #       bounded three ways: the credential reaches DDL only for the ONE schema
  #       its context owns, it reaches it only after an explicit SET ROLE, and
  #       INHERIT FALSE means a compromised session that does not issue that
  #       SET ROLE can read and write nothing at all.
  database_secret_sources = {
    for service in local.database_workload_names :
    service => merge(
      {
        SPRING_DATASOURCE_USERNAME = {
          value_from   = "${module.secrets.service_credential_secrets[local.workloads[service].role].arn}:username::"
          resource_arn = module.secrets.service_credential_secrets[local.workloads[service].role].arn
        }
        SPRING_DATASOURCE_PASSWORD = {
          value_from   = "${module.secrets.service_credential_secrets[local.workloads[service].role].arn}:password::"
          resource_arn = module.secrets.service_credential_secrets[local.workloads[service].role].arn
        }
      },
      contains(local.migration_workload_names, service) ? {
        SPRING_FLYWAY_USER = {
          value_from   = "${module.secrets.service_credential_secrets["${local.workloads[service].role}_migrator"].arn}:username::"
          resource_arn = module.secrets.service_credential_secrets["${local.workloads[service].role}_migrator"].arn
        }
        SPRING_FLYWAY_PASSWORD = {
          value_from   = "${module.secrets.service_credential_secrets["${local.workloads[service].role}_migrator"].arn}:password::"
          resource_arn = module.secrets.service_credential_secrets["${local.workloads[service].role}_migrator"].arn
        }
      } : {},
    )
  }

  # WHY : Refactoring Rationale: a `tls_secret_sources` map stood here, projecting
  #       two Secrets Manager ARNs into the certificate and private-key environment
  #       variables of every online workload. It is deleted with the entries it
  #       named: listener material is minted by each task rather than injected, so
  #       there is nothing to project and no task role needs read access to a
  #       certificate secret. infra/modules/ecs-service no longer admits either
  #       name, so re-adding this map would now fail its validation.

  auth_client_secret_sources = {
    CARDDEMO_AUTH_COGNITO_CLIENT_ID = {
      value_from   = "${module.cognito.app_client_secret_arn}:client_id::"
      resource_arn = module.cognito.app_client_secret_arn
    }
    CARDDEMO_AUTH_COGNITO_CLIENT_SECRET = {
      value_from   = "${module.cognito.app_client_secret_arn}:client_secret::"
      resource_arn = module.cognito.app_client_secret_arn
    }
  }

  # WHY : Assumptions: the ETL image derives a keyed fingerprint for protected
  #       fields (carddemo_migration.copybook.layouts), and the key must be the same
  #       on every run or two loads of one record produce two different tags. It is
  #       therefore a supplied secret rather than generated per task, and it arrives
  #       as a secret rather than a plain variable because it IS key material.
  mask_hmac_secret_sources = {
    CARDDEMO_MASK_HMAC_KEY = {
      value_from   = var.mask_hmac_secret_arn
      resource_arn = var.mask_hmac_secret_arn
    }
  }

  # WHY : Assumptions: the name is the one the authorization image reads --
  #       application.yml resolves carddemo.messaging.hmac-key from
  #       ${CARDDEMO_MESSAGING_HMAC_KEY} -- and infra/modules/ecs-service asserts
  #       biconditionally that authorization receives it and that no other service
  #       does, so the name is a contract rather than a convention.
  #       Refactoring Rationale: this family did not exist, and the absence was the
  #       whole defect. Only mask_hmac_secret_sources above was defined, and it goes
  #       to data-migration alone; authorization therefore started with no key, and
  #       the only per-card stable value it held was the card number, which then
  #       became the published FIFO group identity on every reply. The entry reads
  #       from the SCALAR secret this root creates, so value_from is the base ARN
  #       with no JSON-key selector and IAM authorizes exactly the ARN the container
  #       reads -- the same shape as tls_secret_sources above and deliberately not
  #       the composite shape auth_client_secret_sources needs.
  messaging_hmac_secret_sources = {
    CARDDEMO_MESSAGING_HMAC_KEY = {
      value_from   = aws_secretsmanager_secret.messaging_hmac.arn
      resource_arn = aws_secretsmanager_secret.messaging_hmac.arn
    }
  }

  # WHY : Assumptions: the name is the one the card image reads -- application.yml
  #       resolves carddemo.security.card-selector.signing-key from
  #       ${CARDDEMO_SECURITY_CARD_SELECTOR_SIGNING_KEY} -- and
  #       infra/modules/ecs-service asserts biconditionally that card receives it and
  #       that no other service does, so the name is a contract rather than a
  #       convention. It reads the SCALAR secret above, so value_from is the base ARN
  #       with no JSON-key selector.
  card_selector_secret_sources = {
    CARDDEMO_SECURITY_CARD_SELECTOR_SIGNING_KEY = {
      value_from   = aws_secretsmanager_secret.card_selector.arn
      resource_arn = aws_secretsmanager_secret.card_selector.arn
    }
  }

  # WHY : (1) Assumptions: the names are the ones the consuming images read, and
  #       there are TWO of them rather than one shared name.
  #       carddemo.internal-identity.authorization-signing-key resolves from
  #       ${CARDDEMO_INTERNAL_IDENTITY_AUTHORIZATION_SIGNING_KEY} in
  #       services/authorization-service/src/main/resources/application.yml L1316
  #       and services/account-service/src/main/resources/application.yml L1479;
  #       carddemo.internal-identity.transaction-signing-key resolves from
  #       ${CARDDEMO_INTERNAL_IDENTITY_TRANSACTION_SIGNING_KEY} in
  #       services/transaction-service/src/main/resources/application.yml L940 and
  #       services/account-service/src/main/resources/application.yml L1480.
  #       infra/modules/ecs-service asserts each membership as its own
  #       biconditional, so each name is a contract rather than a convention.
  #       (2) Assumptions: each family goes to exactly TWO workloads -- its one
  #       minting caller and the verifying callee -- and that is inherent rather
  #       than incidental: these are symmetric signing keys, so a verifier holds
  #       what its minter holds. Authorization MINTS with the first through its own
  #       InternalIdentityConfig and transaction MINTS with the second; account
  #       VERIFIES both and therefore holds both, while neither caller holds the
  #       other's. No further holder may be added to either: any additional holder
  #       could mint a token the account context accepts on its internal reads,
  #       which is precisely the capability these keys exist to withhold. The
  #       pagination-cursor family below is a multi-holder family too, at a wider
  #       count, so a gate naming more than one service is not unique to these two.
  #       Refactoring Rationale: this note has had the shape wrong twice. It first
  #       said BOTH images and TWO workloads while naming authorization and account
  #       and omitting transaction; it was then corrected to ONE shared entry read
  #       by ALL THREE images under a single CARDDEMO_INTERNAL_IDENTITY_SIGNING_KEY.
  #       That name is now in no image, no gate and neither root -- the two maps
  #       below are what this root passes -- and the split is substance rather than
  #       bookkeeping: one shared key made the two callers mutually impersonating,
  #       because a compromised transaction task held the bytes needed to mint an
  #       authorization-scoped token that account would accept, so scoping the token
  #       bought nothing. Per-minter material is what gives the scope claim force.
  #       The same correction is recorded in infra/modules/secrets/main.tf.
  #       (3) Assumptions: each entry reads from a SCALAR secret this root creates,
  #       so value_from is the base ARN with no JSON-key selector and IAM authorizes
  #       exactly the ARN each container reads -- the same shape as
  #       tls_secret_sources and deliberately not the composite shape
  #       auth_client_secret_sources needs.
  internal_identity_authorization_secret_sources = {
    CARDDEMO_INTERNAL_IDENTITY_AUTHORIZATION_SIGNING_KEY = {
      value_from   = aws_secretsmanager_secret.internal_identity_authorization.arn
      resource_arn = aws_secretsmanager_secret.internal_identity_authorization.arn
    }
  }

  internal_identity_transaction_secret_sources = {
    CARDDEMO_INTERNAL_IDENTITY_TRANSACTION_SIGNING_KEY = {
      value_from   = aws_secretsmanager_secret.internal_identity_transaction.arn
      resource_arn = aws_secretsmanager_secret.internal_identity_transaction.arn
    }
  }

  # WHY : (1) Assumptions: the name is the one every consuming image reads --
  #       carddemo.pagination.cursor.signing-key resolves from
  #       ${CARDDEMO_PAGINATION_CURSOR_SIGNING_KEY} through the relaxed binding
  #       Spring Boot applies, and
  #       services/common-lib/.../CardDemoCommonAutoConfiguration.java resolves it
  #       with no default. infra/modules/ecs-service asserts biconditionally that
  #       exactly the list-publishing services receive it, so the name is a
  #       contract rather than a convention.
  #       (2) Assumptions: SIX workloads receive it, and the six are the whole of
  #       the gate: account, card, transaction, reference, reporting and authorization.
  #       Each holds at least one component that requires the CursorToken bean --
  #       AccountViewService; CardListService; TransactionController;
  #       TransactionTypeController, TransactionCategoryController and
  #       AddressLookupController; ReportController; and PendingAuthViewMapper -- and a
  #       service whose context requires the bean cannot refresh without the key. batch
  #       and data-migration are absent because neither constructs the bean, and giving
  #       them the key would widen the set able to forge a cursor for no capability
  #       either exercises.
  #       (3) Refactoring Rationale: account joined the set when it began publishing a
  #       keyset scan of the customer master, whose page boundaries
  #       service/AccountViewService.java seals. Until then it constructed no sealer and
  #       was listed here as an absence for that reason.
  #       (3) Refactoring Rationale: card was MISSING from this gate while its
  #       service/CardListService.java already declared CursorToken as a constructor
  #       argument, and the two comments here previously asserted the holder set was
  #       complete. The consequence was not a missing feature: common-lib's
  #       CardDemoCommonAutoConfiguration withholds the CursorToken bean when the
  #       property is unset, so card-service failed application-context refresh and
  #       crash-looped while `terraform plan` stayed green. Both the enumeration above
  #       and the gate below now name it, and the biconditional in
  #       infra/modules/ecs-service names it from the other side, so the omission
  #       cannot recur silently. The separate CARDDEMO_SECURITY_CARD_SELECTOR_SIGNING_KEY
  #       is unaffected: a selector seals ONE row's identity and a cursor seals a page
  #       BOUNDARY, so one key cannot serve both without making a row selector and a
  #       page position interchangeable.
  #       (3) Assumptions: the entry reads from the SCALAR secret this root creates, so
  #       value_from is the base ARN with no JSON-key selector and IAM authorizes
  #       exactly the ARN each container reads -- the same shape as
  #       the internal-identity secret sources above and deliberately not the composite
  #       shape auth_client_secret_sources needs.
  pagination_cursor_secret_sources = {
    CARDDEMO_PAGINATION_CURSOR_SIGNING_KEY = {
      value_from   = aws_secretsmanager_secret.pagination_cursor.arn
      resource_arn = aws_secretsmanager_secret.pagination_cursor.arn
    }
  }

  # WHY : (1) Assumptions: the name is the one the reporting image reads --
  #       carddemo.reporting.artifact.hmac-key resolves from
  #       ${CARDDEMO_REPORTING_ARTIFACT_HMAC_KEY} through the relaxed binding Spring
  #       Boot applies, and
  #       services/reporting-service/.../config/ArtifactIdentityConfig.java resolves it
  #       with no default and refuses a blank value. infra/modules/ecs-service asserts
  #       biconditionally that exactly the reporting service receives it, so the name is
  #       a contract rather than a convention.
  #       (2) Assumptions: ONE workload receives it, and that is the whole of the gate.
  #       reporting is the only context that publishes a stored statement, so it is the
  #       only one that has to name one. Handing the key to any other task would let
  #       that task recompute the token for any account identifier it can guess and then
  #       locate that cardholder's statement objects by listing a prefix -- a disclosure
  #       that needs no read on the object itself, because it is carried by the key.
  #       (3) Assumptions: the entry reads from the SCALAR secret this root creates, so
  #       value_from is the base ARN with no JSON-key selector and IAM authorizes exactly
  #       the ARN the container reads -- the same shape as
  #       pagination_cursor_secret_sources above and deliberately not the composite shape
  #       auth_client_secret_sources needs.
  reporting_artifact_secret_sources = {
    CARDDEMO_REPORTING_ARTIFACT_HMAC_KEY = {
      value_from   = aws_secretsmanager_secret.reporting_artifact.arn
      resource_arn = aws_secretsmanager_secret.reporting_artifact.arn
    }
  }


  # WHY : (1) Assumptions: this maps each workload to the EXACT queue ARNs its task
  #       role may receive from and send to, taking them from the sqs module's own
  #       service_queue_permissions output rather than composing them here. That
  #       output is the single place the per-service boundary is decided, and its own
  #       description instructs a caller to pass these lists rather than granting
  #       values(queue_arns) -- a wildcard grant would let any consumer read any
  #       queue, which for the pending-authorization request queue means reading
  #       cardholder data it has no part in.
  #       (2) Refactoring Rationale: this local did not exist, and the omission had
  #       become a runtime failure rather than a latent gap.
  #       docs/architecture/messaging-contracts.md recorded that the module boundary
  #       was authored but "not yet composed into a deployable stack", which was
  #       accurate while neither inquiry consumer existed. Both now exist and both
  #       SEND -- account-service and reference-service each publish a reply and can
  #       publish a diagnostic -- so without these grants each would poll
  #       successfully and then fail every reply with an access-denied error, which
  #       presents as an unanswered requester rather than as a permissions problem.
  #       (3) Assumptions: a workload absent from this map receives an empty set,
  #       which grants nothing. That is the correct default: data-migration puts no
  #       message on any of these queues, and the four workloads that do are named
  #       explicitly so adding a fifth is a visible edit rather than an inherited
  #       grant.
  #       (4) Refactoring Rationale: the batch entry was ABSENT and the note above
  #       said batch "put no message on any of these queues", which stopped being
  #       true and had in fact never been the intent. batch-service ships a queue
  #       configuration gated on the terminal error sink's address and its durable
  #       step ledger reports every terminal step failure through it, so the claim
  #       described the grant rather than the code: with no grant and no published
  #       address the gate simply never opened, and a night that hard-failed
  #       reported to the sink that exists for exactly that. The entry grants SEND
  #       on the error queue and RECEIVE on nothing, which is the queue module's own
  #       batch_service boundary -- this module consumes no queue, because no
  #       reference batch program contains an MQ verb and the job is selected from a
  #       command argument rather than from message arrival.
  sqs_permissions_by_workload = {
    authorization = module.sqs.service_queue_permissions.authorization_service
    account       = module.sqs.service_queue_permissions.account_service
    reference     = module.sqs.service_queue_permissions.reference_service
    # WHY : Refactoring Rationale: batch was absent from this map, so its task role held no
    #       sqs statement of any kind while the module it runs carries a queue configuration
    #       whose one purpose is to notify the terminal error sink that a run failed. The
    #       first send the producer ever attempted would therefore have been refused, on the
    #       failure path, which is the least-exercised path in the deployment. The queue
    #       module's batch_service entry grants sqs:SendMessage on the error queue alone and
    #       an empty receive list, so this is a send-only boundary rather than a widened one.
    # WHY : Assumptions: the grant travels with the CARDDEMO_MESSAGING_ERROR_QUEUE_URL
    #       parameter published above, and neither is useful without the other -- the address
    #       without the action is an access-denied at the moment a failure is being reported,
    #       and the action without the address is a permission nothing exercises.
    batch = module.sqs.service_queue_permissions.batch_service
  }

  # WHY : Assumptions: the fallback for a workload that touches no queue is declared
  #       once here rather than inline at each of the two module arguments, so the two
  #       cannot come to disagree about what "no queue permissions" means. Its member
  #       names match the queue module's own output shape, which is what lets `lookup`
  #       unify the two arms into one type.
  no_queue_permissions = {
    receive = []
    send    = []
  }

  secret_sources_by_workload = {
    for service, workload in local.workloads :
    service => merge(
      contains(local.database_workload_names, service) ? local.database_secret_sources[service] : {},
      service == "auth" ? local.auth_client_secret_sources : {},
      service == "data-migration" ? local.mask_hmac_secret_sources : {},
      # WHY : Assumptions: gated on the authorization service by exact name, matching
      #       the biconditional precondition in infra/modules/ecs-service. It is the
      #       only producer on the pending-authorization queue this repository
      #       contains; widening the gate would hand key material to tasks that put
      #       no message on that queue and have no use for it.
      service == "authorization" ? local.messaging_hmac_secret_sources : {},

      # WHY : Assumptions: gated on the card service by exact name. It is the only
      #       context that addresses a row by an opaque selector, and the biconditional
      #       precondition in infra/modules/ecs-service refuses both a card task without
      #       the key and any other task holding it.
      service == "card" ? local.card_selector_secret_sources : {},

      # WHY : Assumptions: gated as TWO pairs by exact name, and each pair is the whole of
      #       its own gate. The authorization service signs with the first key and the
      #       transaction service with the second; the account service verifies both, so it
      #       holds both and each caller holds only its own. Nothing else may hold either:
      #       any further holder of a key could mint a token the account service accepts
      #       under that key's caller, which is precisely the capability the split exists to
      #       withhold. The biconditional preconditions in infra/modules/ecs-service assert
      #       the same two pairs from the other side, so adding a service here without adding
      #       it there fails at plan time rather than silently widening the trust set.
      # WHY : Refactoring Rationale: this was ONE gate over three services holding one key.
      #       Because both callers signed with the same bytes, either could mint a token
      #       carrying the other's subject and the account service could not tell -- so its
      #       audit record named a caller it had no way to verify, and one scope authorised
      #       every internal address rather than the ones a caller needs. Two pairs also make
      #       the two rotations independent: re-issuing one caller's key stalls that caller
      #       alone, where re-issuing the shared key stalled both at once.
      contains(["authorization", "account"], service) ? local.internal_identity_authorization_secret_sources : {},
      contains(["transaction", "account"], service) ? local.internal_identity_transaction_secret_sources : {},

      # WHY : Assumptions: gated on the services holding a component whose constructor
      #       requires the CursorToken bean, by exact name, and that set is the whole of
      #       the gate. That bean is withheld when the key is unset, so a service in this
      #       set without the key does not start degraded -- it fails context refresh and
      #       crash-loops. The biconditional precondition in infra/modules/ecs-service
      #       asserts the same set from the other side, so adding a service here without
      #       adding it there fails at plan time rather than silently widening the set
      #       able to forge a cursor.
      # WHY : Refactoring Rationale: this gate named FIVE services and omitted AUTH and
      #       CARD, both of which hold the dependency unconditionally --
      #       auth-service service/UserService.java and card-service
      #       service/CardListService.java each declare a final CursorToken field and take
      #       one as a constructor argument. Both contexts therefore crash-looped on every
      #       deployment. The omission could not be repaired here alone: because the
      #       module's precondition is biconditional, the narrower set there actively
      #       FORBADE supplying the key to either service, so the module gate was widened
      #       first and this gate matches it. Measured across the integrated tree the set
      #       is nine components in seven services; the two workloads correctly outside it
      #       are batch, which publishes no HTTP surface, and data-migration, which is not
      #       a Java workload.
      contains(["auth", "account", "card", "transaction", "reference", "reporting", "authorization"], service) ? local.pagination_cursor_secret_sources : {},
      service == "reporting" ? local.reporting_artifact_secret_sources : {},
    )
  }

  environment_variables_by_workload = {
    for service, workload in local.workloads :
    service => merge(
      {
        AWS_REGION           = var.aws_region
        CARDDEMO_ENVIRONMENT = var.environment

        # WHY : Assumptions: the release identity is derived from the SAME expression
        #       that chooses this workload's image below, so the label a task emits and
        #       the artifact it runs can never disagree. A digest is used when one is
        #       supplied for this artifact and the commit tag otherwise, which is the
        #       posture image_uri already encodes -- production pins the exact build,
        #       development iterates by pushing over a tag.
        # WHY : Refactoring Rationale: no root supplied this variable at all, and
        #       carddemo-common-defaults.yml, the telemetry collector's resource
        #       processor and OTEL_RESOURCE_ATTRIBUTES all fall back to the literal
        #       "unspecified" without it -- so every log record, metric series and span
        #       this estate produced was unattributable to a release. The service module
        #       now refuses a task whose value is absent, blank or that same placeholder.
        # WHY : Trade-offs: for a digest reference the label is the digest and not a
        #       human-readable version. That is preferred here: a digest is the only
        #       identity that cannot be moved after the fact, and the commit tag remains
        #       recoverable from the registry, whereas a friendly version string supplied
        #       independently could name a build the task is not running.
        CARDDEMO_VERSION = (
          lookup(var.image_digests, workload.repository, null) != null
          ? var.image_digests[workload.repository]
          : var.image_tag
        )
      },

      # WHY : Assumptions: the trust-anchor PATH differs per image because each
      #       image installs the bundle at its own location -- data-migration's
      #       Dockerfile places it at /etc/ssl/certs/aws-rds-global-bundle.pem, while
      #       the service images resolve
      #       /etc/ssl/certs/carddemo-rds-ca-bundle.pem, which is the default their
      #       application.yml files already carry. Passing the matching path
      #       explicitly rather than relying on either default keeps the value
      #       visible in the task definition, which is where an operator debugging a
      #       verify-full failure looks first.
      service == "data-migration" ? {
        CARDDEMO_DB_SSL_ROOT_CERT = "/etc/ssl/certs/aws-rds-global-bundle.pem"
        } : {
        CARDDEMO_DB_SSL_ROOT_CERT = "/etc/ssl/certs/carddemo-rds-ca-bundle.pem"
      },

      # WHY : Assumptions: the ETL image reads its whole database configuration from
      #       Parameter Store and requires three settings the Java images do not.
      #       CARDDEMO_PARAMETER_PREFIX names the namespace,
      #       CARDDEMO_DB_SSL_MODE is accepted only at the value
      #       carddemo_migration.config fixes -- it exists so that LOWERING it fails
      #       loudly rather than silently negotiating a weaker mode -- and
      #       CARDDEMO_DB_ALTERNATE_USERS lists, per role, the one alternate login
      #       that role's rotation may present. The `_clone` suffix is the
      #       alternating-user naming convention the operator-managed rotation
      #       procedure in docs/runbooks/deploy.md follows; nothing in this tree
      #       creates those logins, and the allowlist is inert until one exists,
      #       because carddemo_migration.config consults it only when a secret's
      #       username differs from the role its name was derived from.
      #       Refactoring Rationale: this said the clones were created by "the
      #       credential-rotation function". No such function exists -- neither root
      #       supplies infra/modules/secrets a rotation_lambda_arn, so its
      #       aws_secretsmanager_secret_rotation is created with zero instances --
      #       and describing an absent component as the producer of these names told
      #       an operator to look for automation rather than to run the documented
      #       procedure. Publishing the allowlist ahead of any rotation is still
      #       right: it means a rotation does not additionally require a task-
      #       definition change to be accepted.
      service == "data-migration" ? {
        CARDDEMO_PARAMETER_PREFIX   = local.parameter_prefix
        CARDDEMO_DB_SSL_MODE        = "verify-full"
        CARDDEMO_DB_ALTERNATE_USERS = join(",", [for role in local.service_role_names : "${role}=${role}_clone"])
        } : {
        SPRING_PROFILES_ACTIVE = var.environment
      },
      workload.online ? {
        CARDDEMO_ONLINE_WRITES_PARAMETER = aws_ssm_parameter.online_writes_enabled.name
      } : {},

      # WHY : Assumptions: reporting-service is the one service that reads
      #       X-Forwarded-For, because it is the one that renders an operator-facing
      #       address into a report header. Tomcat honours the header only from a
      #       proxy it trusts, so the pattern must match the load balancer's own
      #       addresses -- which are in this VPC -- and nothing else. It is derived
      #       from var.vpc_cidr rather than written as a literal so a root that
      #       changes its address space cannot leave a pattern behind that silently
      #       stops trusting its own load balancer.
      #       Assumptions: only the first two octets are matched, which describes
      #       EXACTLY a /16 -- and variables.tf now requires this root's vpc_cidr to be
      #       a /16 for precisely this reason, so the pattern admits this VPC's address
      #       space and nothing beyond it.
      #       Refactoring Rationale: this note previously accepted the two-octet match
      #       as admitting "any address inside a /16" on the grounds that such
      #       addresses are private and inside this VPC "in either case". That was true
      #       only while the block WAS a /16. The network module accepts /16 to /20, so
      #       a root configured with, say, a /20 would have derived a pattern matching
      #       sixteen times its own allocation, and Tomcat would then have honoured a
      #       client-supplied X-Forwarded-For header from addresses outside this VPC
      #       entirely. The defect is closed at the input rather than here, because
      #       constraining the prefix keeps this expression exact instead of making it
      #       conditional.
      #       Alternatives Considered: enumerating the three private-application subnet
      #       ranges as a regex, which is what an arbitrary prefix length would force.
      #       Still rejected: it reimplements CIDR arithmetic in a string, and an error
      #       in that arithmetic fails OPEN.
      service == "reporting" ? {
        CARDDEMO_TRUSTED_PROXY_PATTERN = format(
          "%s\\.%s\\.\\d+\\.\\d+",
          split(".", var.vpc_cidr)[0],
          split(".", var.vpc_cidr)[1],
        )
      } : {},
    )
  }
}

# -----------------------------------------------------------------------------
# Per-workload business IAM policies.
# -----------------------------------------------------------------------------

# WHY : Assumptions: the card workload's only privilege beyond its database
#       connection is envelope encryption of ONE stored value. The baseline holds
#       CARD-CVV-CD as three display digits in the clear (app/cpy/CVACT02Y.cpy),
#       and the migrated column holds an envelope instead;
#       com.carddemo.card.service.CardVerificationValueCipher is the only class
#       that reaches a key in order to produce or open one.
# WHY : Trade-offs: two actions, not five. GenerateDataKey covers the entire write
#       path -- the workload receives one data key in plaintext and enciphered form
#       together and enciphers locally -- and Decrypt covers the entire read path.
#       kms:Encrypt is therefore deliberately ABSENT: a role holding it could use
#       this key as a general-purpose encryption oracle for arbitrary plaintext,
#       which nothing in the card path needs. kms:DescribeKey is absent for the
#       same reason, the workload already holding the alias from its configuration.
# WHY : Alternatives Considered: passing this task role to the kms module as
#       application_envelope_user_role_arns, so the KEY policy named it directly.
#       REJECTED for consistency with every other key in this root -- none of the
#       four *_key_user_role_arns inputs is wired, because the Aurora key
#       policy already delegates authorization to IAM through its account-root
#       statement, and naming a task role in a key policy would make that policy
#       depend on the ECS service that consumes the key's own alias. Granting from
#       the identity side keeps the key and the workloads that use it independently
#       replaceable.
# WHY : Assumptions: the encryption-context condition is what makes this grant
#       specific rather than merely small. Without it the role could encipher and
#       decipher anything under this key; with it, it can work only with ciphertext
#       produced for the card verification value. The identical condition is
#       asserted a second time by the key policy in infra/modules/kms, so removing
#       it from either side still leaves the other enforcing it.
data "aws_iam_policy_document" "card_runtime" {
  statement {
    sid = "EnvelopeEncryptCardVerificationValue"

    actions = [
      "kms:GenerateDataKey*",
      "kms:Decrypt",
    ]

    resources = [module.kms.aurora_key_arn]

    condition {
      test     = "StringEquals"
      variable = "kms:EncryptionContext:carddemo:purpose"
      values   = ["card-cvv"]
    }
  }
}

data "aws_iam_policy_document" "account_runtime" {
  statement {
    sid       = "ConsumeAccountInquiryRequests"
    actions   = ["sqs:ReceiveMessage", "sqs:DeleteMessage", "sqs:GetQueueAttributes"]
    resources = [module.sqs.account_inquiry_request_queue_arn]
  }

  statement {
    sid       = "PublishAccountInquiryResults"
    actions   = ["sqs:SendMessage"]
    resources = [module.sqs.inquiry_reply_queue_arn, module.sqs.error_queue_arn]
  }

  statement {
    sid       = "UseEncryptedInquiryQueues"
    actions   = ["kms:Decrypt", "kms:GenerateDataKey"]
    resources = [module.kms.sqs_key_arn]
  }

  # WHY : Refactoring Rationale: this statement was ABSENT, and its absence was not
  #       a missing hardening measure -- it made a shipped feature fail closed. The
  #       account workload is configured with a key alias as
  #       CARDDEMO_SECURITY_CUSTOMER_IDENTIFIER_KEY_ID, and
  #       com.carddemo.account.config.CustomerIdentifierProtectionConfig wires
  #       com.carddemo.account.service.CustomerIdentifierCipher to draw a fresh
  #       envelope data key per identifier from it. With the alias supplied and no
  #       grant, every write of a national identifier or a government-issued
  #       identifier would have been refused by KMS -- an AccessDenied on
  #       GenerateDataKey during an account update, surfacing as a failed update
  #       rather than as a configuration error. The card context had the equivalent
  #       grant and this one did not, so the asymmetry was the defect.
  # WHY : Trade-offs: two actions, not five, exactly as on the card grant above.
  #       GenerateDataKey* covers the whole write path -- the workload receives one
  #       data key in plaintext and enciphered form together and enciphers locally
  #       -- and Decrypt covers the whole read path. kms:Encrypt is deliberately
  #       ABSENT: a role holding it could use this key as a general-purpose
  #       encryption oracle for arbitrary plaintext, which nothing in the account
  #       path needs. kms:DescribeKey is absent because the workload already holds
  #       the alias from its configuration.
  # WHY : Assumptions: the purpose value is "customer-identifier", which is the
  #       literal CONTEXT_PURPOSE_VALUE that CustomerIdentifierProtectionConfig and
  #       CustomerIdentifierCipher both put in the encryption context. It is not
  #       "card-cvv" and the two must not be merged: the condition is what keeps
  #       this role unable to open a card verification value under the same key,
  #       and keeps the card role unable to open a customer identifier. A wrong
  #       value here fails identically to a missing statement, which is why it is
  #       taken from the code rather than chosen to look plausible.
  # WHY : Assumptions: the resource is the AURORA key. The ciphertext is stored in
  #       customers.ssn_encrypted and customers.govt_issued_id_encrypted, which are
  #       Aurora columns, and the frozen design allocates four customer-managed
  #       keys by data domain with no separate application key. infra/modules/kms
  #       asserts the same encryption-context condition on that key's own policy,
  #       so removing this condition from either side still leaves the other
  #       enforcing it.
  statement {
    sid = "EnvelopeEncryptCustomerIdentifiers"

    actions = [
      "kms:GenerateDataKey*",
      "kms:Decrypt",
    ]

    resources = [module.kms.aurora_key_arn]

    condition {
      test     = "StringEquals"
      variable = "kms:EncryptionContext:carddemo:purpose"
      values   = ["customer-identifier"]
    }
  }
}

data "aws_iam_policy_document" "reference_runtime" {
  statement {
    sid       = "ConsumeDateInquiryRequests"
    actions   = ["sqs:ReceiveMessage", "sqs:DeleteMessage", "sqs:GetQueueAttributes"]
    resources = [module.sqs.date_inquiry_request_queue_arn]
  }

  statement {
    sid       = "PublishDateInquiryResults"
    actions   = ["sqs:SendMessage"]
    resources = [module.sqs.inquiry_reply_queue_arn, module.sqs.error_queue_arn]
  }

  statement {
    sid       = "UseEncryptedInquiryQueues"
    actions   = ["kms:Decrypt", "kms:GenerateDataKey"]
    resources = [module.kms.sqs_key_arn]
  }
}

data "aws_iam_policy_document" "authorization_runtime" {
  statement {
    sid       = "ConsumePendingAuthorizationRequests"
    actions   = ["sqs:ReceiveMessage", "sqs:DeleteMessage", "sqs:GetQueueAttributes"]
    resources = [module.sqs.pauth_request_queue_arn]
  }

  statement {
    sid       = "PublishPendingAuthorizationReplies"
    actions   = ["sqs:SendMessage"]
    resources = [module.sqs.pauth_reply_queue_arn]
  }

  statement {
    sid       = "UseEncryptedAuthorizationQueues"
    actions   = ["kms:Decrypt", "kms:GenerateDataKey"]
    resources = [module.kms.sqs_key_arn]
  }

  # Refactoring Rationale: these three statements were added with the
  #   authorization-extract state machine. Without them the segment export had no
  #   destination that outlived its own container and the extract load had no source it
  #   could be given -- com.carddemo.authorization.task.ExtractStore is the access path
  #   and this role is the identity it authenticates as, so the capability was
  #   unreachable for want of a privilege as much as for want of an entry point.
  # Assumptions: BOTH a write and a read are granted, because the two directions are
  #   separate jobs on one role. The export writes the two objects; the load reads two
  #   an operator names, which may be the export's own output or the reference programs'.
  statement {
    sid     = "WriteAuthorizationExtracts"
    actions = ["s3:PutObject", "s3:AbortMultipartUpload"]
    resources = [
      "${module.s3_datasets.bucket_arn}/${local.authorization_extract_key_prefix}*"
    ]
  }

  statement {
    sid       = "ReadAuthorizationExtracts"
    actions   = ["s3:GetObject"]
    resources = ["${module.s3_datasets.bucket_arn}/${local.authorization_extract_key_prefix}*"]
  }

  # Assumptions: the listing is bounded by the `s3:prefix` condition key, which is the
  #   only way a list call can be bounded at all -- its resource is the BUCKET, so an
  #   object-ARN restriction does not narrow it. Unconditioned, this grant would let an
  #   authorization task enumerate every nightly transaction generation in the same
  #   bucket.
  # Trade-offs: `StringLike` against the prefix followed by a wildcard rather than
  #   `StringEquals` against the bare prefix, for the reason the reporting document
  #   records: a listing of one run's own sub-prefix is legitimate and `StringEquals`
  #   would refuse it.
  statement {
    sid       = "ListAuthorizationExtractPrefix"
    actions   = ["s3:ListBucket"]
    resources = [module.s3_datasets.bucket_arn]

    condition {
      test     = "StringLike"
      variable = "s3:prefix"
      values   = ["${local.authorization_extract_key_prefix}*"]
    }
  }

  # Assumptions: the dataset bucket is encrypted with the S3 customer-managed key, so a
  #   put and a get both need the key as well as the object grant. Decrypt is required
  #   by the read and GenerateDataKey by the write; neither implies the other.
  statement {
    sid       = "UseAuthorizationExtractKey"
    actions   = ["kms:Decrypt", "kms:GenerateDataKey"]
    resources = [module.kms.s3_key_arn]
  }
}

data "aws_iam_policy_document" "reporting_runtime" {
  statement {
    sid       = "StartAdhocReportExecution"
    actions   = ["states:StartExecution"]
    resources = [local.adhoc_report_state_machine_arn]
  }

  # Assumptions: the listing is bounded by the `s3:prefix` condition key, which
  #   is the ONLY way a list call can be bounded at all -- its resource is the
  #   bucket, so an object-ARN restriction has no effect on it. Without the
  #   condition the grant enumerates every key in the bucket, which is how a
  #   reporting task could discover the nightly transaction generations.
  # Trade-offs: the match is `StringLike` against each prefix followed by a
  #   wildcard rather than `StringEquals` against the bare prefix. The nightly
  #   report ISSUES a narrower listing -- it lists
  #   `reporting/tranrept/dt=<business date>/` to number the generation it is
  #   about to write -- so `StringEquals` would permit only the exact top-level
  #   listing and refuse the one call this grant exists to allow, which would make
  #   it correct on paper and unusable in practice.
  statement {
    sid       = "ListReportOutputPrefixes"
    actions   = ["s3:ListBucket"]
    resources = [module.s3_datasets.bucket_arn]

    condition {
      test     = "StringLike"
      variable = "s3:prefix"
      values   = [for prefix in local.reporting_object_key_prefixes : "${prefix}*"]
    }
  }

  # Assumptions: the write actions are retained and only their scope is
  #   narrowed, because this role IS the writer of every reporting artifact --
  #   the two statements, the transaction detail report under both its
  #   request-scoped and its generation key, and the category-balance report. The nightly
  #   chain's GenerateStatements and GenerateReports states run a Fargate task
  #   whose definition is `module.ecs_service["reporting"].task_definition_arn`
  #   -- the very definition the online reporting service runs, which is why
  #   ReportingTaskRunner exists to give that image a task mode. Reassigning the
  #   writes to a different role would leave those two states unable to write
  #   their output.
  # Refactoring Rationale: `s3:GetObject` is REMOVED rather than narrowed.
  #   Neither state reads an object: the report is assembled from the read-only
  #   database views and the statement from the same, and the key layout is
  #   deterministic precisely so a rerun replaces rather than appends. A read
  #   grant that no code path exercises is a capability held for no purpose, and
  #   a future download endpoint should acquire it together with the endpoint so
  #   the two are reviewed as one change.
  statement {
    sid     = "WriteReportOutputs"
    actions = ["s3:PutObject", "s3:AbortMultipartUpload"]
    resources = [
      for prefix in local.reporting_object_key_prefixes :
      "${module.s3_datasets.bucket_arn}/${prefix}*"
    ]
  }

  statement {
    sid       = "UseReportOutputKey"
    actions   = ["kms:Decrypt", "kms:GenerateDataKey"]
    resources = [module.kms.s3_key_arn]
  }
}

data "aws_iam_policy_document" "batch_runtime" {
  statement {
    sid       = "ListDatasetBucket"
    actions   = ["s3:ListBucket"]
    resources = [module.s3_datasets.bucket_arn]
  }

  # WHY : Assumptions: this ONE statement covers both directions of the dataset
  #       bucket, and the read half is now load-bearing rather than incidental. The
  #       seed-refresh state's `refresh-dataset` command READS each exported extract
  #       out of module.s3_datasets.source_extract_prefix before it stages, decodes and
  #       loads it; every generation-writing batch state WRITES under the ten
  #       generation prefixes; and CombineTransactions reads a generation back. All of
  #       those keys are inside this one bucket, so the resource pattern already grants
  #       the read the refresh needs.
  # WHY : Alternatives Considered: splitting this into a read statement scoped to the
  #       source-extract prefix and a read/write statement scoped to the dataset
  #       prefixes. Rejected because it would express no narrower privilege while
  #       creating a way for the two to fall out of step: the prefixes are owned by the
  #       s3-datasets module and are ALL of the keys this bucket holds -- twelve dataset
  #       prefixes plus the source-extract prefix -- so a per-prefix enumeration here
  #       would grant exactly what "/*" grants today and would silently omit whichever
  #       prefix a later change added. The bucket itself is the privilege boundary: it
  #       holds only this workload's datasets, it is created by this root, and its own
  #       policy refuses non-TLS access and any principal outside this account.
  statement {
    sid       = "ReadWriteDatasetGenerations"
    actions   = ["s3:GetObject", "s3:PutObject", "s3:AbortMultipartUpload"]
    resources = ["${module.s3_datasets.bucket_arn}/*"]
  }

  statement {
    sid       = "UseDatasetKey"
    actions   = ["kms:Decrypt", "kms:GenerateDataKey"]
    resources = [module.kms.s3_key_arn]
  }
}

data "aws_iam_policy_document" "data_migration_runtime" {
  source_policy_documents = [data.aws_iam_policy_document.batch_runtime.json]

  statement {
    sid = "ReadRuntimeParameters"
    actions = [
      "ssm:GetParameter",
      "ssm:GetParameters",
      "ssm:GetParametersByPath",
    ]
    resources = [
      "arn:${data.aws_partition.current.partition}:ssm:${data.aws_region.current.region}:${data.aws_caller_identity.current.account_id}:parameter/${var.name_prefix}/${var.environment}/*",
    ]
  }

  statement {
    sid       = "ReadServiceDatabaseCredentials"
    actions   = ["secretsmanager:GetSecretValue"]
    resources = [for secret in values(module.secrets.service_credential_secrets) : secret.arn]
  }

  statement {
    sid       = "DecryptServiceDatabaseCredentials"
    actions   = ["kms:Decrypt"]
    resources = [module.kms.secrets_key_arn]
  }

  # WHY : Refactoring Rationale: this statement was ABSENT, and its absence made a
  #   DELIVERED load path fail closed rather than leaving a hardening measure
  #   undone. loaders/aurora.py seals two projections before it writes them --
  #   SEALED_IDENTIFIER for customers.ssn_encrypted and
  #   customers.govt_issued_id_encrypted, SEALED_VERIFICATION_VALUE for
  #   cards.cvv_encrypted -- and loaders/protected_columns.py draws a fresh
  #   envelope data key per value to do it. cli.py resolves both key aliases from
  #   the two parameters this root already publishes, so the aliases arrive and the
  #   grant did not: every CARDDATA and CUSTDATA load would have been refused by
  #   KMS with an AccessDenied on GenerateDataKey, surfacing as a failed migration
  #   step rather than as a missing permission. The card and account workloads hold
  #   the equivalent grants above; the migration task, which writes the very
  #   columns they later read, held none, so the asymmetry was the defect.
  # WHY : Trade-offs: ONE action, not two. GenerateDataKey* covers the whole write
  #   path -- the caller receives one data key in plaintext and enciphered form
  #   together and enciphers locally -- and kms:Decrypt is deliberately ABSENT
  #   where both the card and account grants carry it. loaders/protected_columns.py
  #   publishes no decipher member at all and records why: a migration writes
  #   protected columns and never reads them back, so a decrypt grant here would
  #   widen what a compromised migration task can do with nothing using it. That is
  #   the one asymmetry between this grant and the two above, and it is chosen.
  #   kms:Encrypt is absent for the reason those grants give -- a role holding it
  #   could use this key as a general-purpose encryption oracle -- and
  #   kms:DescribeKey is absent because the task already holds the alias from the
  #   parameters it reads.
  # WHY : Assumptions: BOTH purpose values are listed on ONE statement, where the
  #   workload grants carry one each. The migration task legitimately writes both
  #   projections -- it is the only component that loads the customer and card
  #   extracts -- so splitting them into two statements would express no narrower
  #   privilege while doubling what has to stay in step. The two values are taken
  #   verbatim from loaders/protected_columns.py, which puts "customer-identifier"
  #   and "card-cvv" in the encryption context as authenticated additional data; a
  #   value that merely looked plausible would fail identically to a missing
  #   statement.
  # WHY : Assumptions: the resource is the AURORA key, because both parameters
  #   published above resolve to that key's alias and both ciphertexts are stored
  #   in Aurora columns. The condition is what makes the grant specific rather than
  #   merely small, and infra/modules/kms asserts the same encryption-context
  #   condition on that key's own policy, so removing it from either side still
  #   leaves the other enforcing it.
  # WHY : Refactoring Rationale: these two statements were ABSENT, and their absence
  #   made a DELIVERED retention contract fail on the sixth run rather than leaving a
  #   feature undone. The baseline defines every one of its ten generation data groups
  #   with LIMIT(5) SCRATCH -- app/jcl/DEFGDGB.jcl:25-57, app/jcl/DEFGDGD.jcl:28-76 and
  #   app/jcl/DALYREJS.jcl:24-26 -- so a sixth generation SCRATCHES the oldest, and
  #   loaders/s3_stage.py reproduces that by listing a family's object versions and
  #   deleting the oldest generation prefix. It inherited only ListBucket, GetObject,
  #   PutObject and AbortMultipartUpload, so the first five stagings of each family
  #   succeeded and the sixth failed with an access denial on the version listing --
  #   a failed batch step, five runs after the deployment anyone would have tested.
  # WHY : Assumptions: the version-list action is granted at BUCKET level with a prefix
  #   condition, and the delete actions at OBJECT level over the same prefixes, because
  #   that is how S3 authorises them. s3:ListBucketVersions is a bucket operation whose
  #   only scoping mechanism is the s3:prefix condition key; s3:DeleteObject and
  #   s3:DeleteObjectVersion are object operations scoped by resource ARN. Granting
  #   either at the other's level would either be rejected as a malformed policy or
  #   silently authorise the whole bucket.
  # WHY : Assumptions: the prefixes come from module.s3_datasets.dataset_prefixes rather
  #   than being written out, so this grant covers exactly the ten generation families
  #   that module declares and moves with them. Writing them here would create a second
  #   inventory free to disagree, and the disagreement's failure mode is a family whose
  #   retention sweep is denied -- which is the defect being fixed.
  # WHY : Assumptions: the two STATEMENT prefixes and the inbox prefix are deliberately
  #   EXCLUDED. Neither is a generation family: the statement artifacts are rewritten in
  #   place by the reporting task and carry no gen= segment for a sweep to prune, and the
  #   inbox holds the operator's delivered export, whose retention is the operator's
  #   decision. A delete grant over either would let the migration task destroy data no
  #   part of it is responsible for.
  # WHY : Trade-offs: s3:DeleteObjectVersion is granted alongside s3:DeleteObject rather
  #   than instead of it. The bucket is versioned, so a delete naming a VersionId --
  #   which is what the sweep issues, because a plain delete on a versioned bucket adds a
  #   marker and reclaims nothing -- requires the version-scoped action; the unversioned
  #   action is granted because the same batched call is authorised against both when any
  #   entry omits a version, and a partial authorisation reports as a partial failure
  #   that is far harder to read than a denial.
  statement {
    sid       = "ListDatasetGenerationVersions"
    actions   = ["s3:ListBucketVersions"]
    resources = [module.s3_datasets.bucket_arn]

    condition {
      test     = "StringLike"
      variable = "s3:prefix"
      values = flatten([
        for prefix in values(module.s3_datasets.dataset_prefixes) : [prefix, "${prefix}*"]
      ])
    }
  }

  statement {
    sid = "ScratchOldestDatasetGeneration"

    actions = [
      "s3:DeleteObject",
      "s3:DeleteObjectVersion",
    ]

    resources = [
      for prefix in values(module.s3_datasets.dataset_prefixes) :
      "${module.s3_datasets.bucket_arn}/${prefix}*"
    ]
  }

  statement {
    sid = "EnvelopeEncryptMigratedProtectedColumns"

    actions = ["kms:GenerateDataKey*"]

    resources = [module.kms.aurora_key_arn]

    condition {
      test     = "StringEquals"
      variable = "kms:EncryptionContext:carddemo:purpose"
      values   = ["card-cvv", "customer-identifier"]
    }
  }
}

# WHY : (1) Refactoring Rationale: the auth workload had NO task-role policy at all,
#       which was correct while the identity a user row is bound to arrived as a
#       request field. It no longer does: services/auth-service now creates the pool
#       account itself and reads the provider-minted subject back, because a caller
#       able to nominate that subject was a caller able to decide which pool identity
#       a new row authenticates as -- the signed group claim wins every authorization
#       decision, so a row bound to an administrator's subject while recording 'U'
#       would carry administrator authority with nothing to contradict it. Those
#       calls need this policy; without it every user creation fails with an access
#       denial at the provider and the workload is the only one whose task role holds
#       no statement.
#       (2) Assumptions: the FIVE actions are exactly the five the service issues,
#       and no sixth is granted speculatively. The set was derived by enumerating
#       every provider call in services/auth-service/src/main/java rather than from
#       the shape of the feature: adminCreateUser, adminAddUserToGroup,
#       adminRemoveUserFromGroup, adminUpdateUserAttributes and adminDeleteUser.
#       AdminCreateUser and AdminAddUserToGroup are the two halves of provisioning --
#       the account must exist before it can join a group, and membership is a
#       separate call rather than an attribute -- and AdminDeleteUser is the
#       compensating withdrawal that removes an account whose row write then failed.
#       Refactoring Rationale: this statement granted only THREE and the paragraph
#       asserted three was exhaustive, while
#       service/CognitoUserProvisioningService.java calls two more.
#       AdminRemoveUserFromGroup and AdminUpdateUserAttributes are both issued when a
#       user's type changes -- the synchronisation path removes the old group, adds
#       the new one and carries the changed attributes. Both would have failed with an
#       access denial at the provider, so every user-type change and every attribute
#       update was broken, and the group removal failing mid-sequence is the worst
#       shape of that failure: the user would already have been removed from their old
#       group by a call that was denied, or left in both. Two non-admin calls,
#       initiateAuth and respondToAuthChallenge, are deliberately NOT granted: they
#       are unauthenticated pool operations authorised by the app-client credential
#       rather than by IAM, so a statement for them would grant nothing.
#       Refactoring Rationale: this paragraph named an in-process `restoreGroup`
#       compensation that re-added the old group when the synchronisation failed
#       part-way. That method is deleted: the durable ledger the service records each
#       pending synchronisation in reaches the same outcome by retrying the whole
#       operation, and an in-process callback could not, because a process death
#       between the provider call and the commit left the two stores disagreeing with
#       nothing recording it. Naming a deleted mechanism in the justification for a
#       grant makes the grant look narrower than it is.
#       Still absent is AdminSetUserPassword, and the reason has changed. The service
#       DOES now create a credential -- a generated one-time password, published to a
#       managed-secret entry for its owner to collect, which is what makes a
#       runtime-created account reachable at all -- but it supplies that value as the
#       temporary password ON AdminCreateUser rather than setting it afterwards, so
#       there is no call site for the action and granting it would hand this task an
#       authority nothing uses. Refactoring Rationale: the earlier wording justified
#       the absence by asserting this service creates no credential. That is no longer
#       true, and left standing it would have read as a licence to remove the two
#       statements below.
#       (3) Trade-offs: the resource is the single pool ARN rather than a wildcard, so
#       a second pool in the same account is unreachable from this task even by
#       accident. The cost is that the statement cannot be written before the pool
#       exists, which is why it lives in the root beside the module call rather than
#       inside modules/ecs-service where the role is created.
data "aws_iam_policy_document" "auth_runtime" {
  statement {
    sid = "ProvisionPoolIdentities"
    actions = [
      "cognito-idp:AdminCreateUser",
      "cognito-idp:AdminAddUserToGroup",
      "cognito-idp:AdminRemoveUserFromGroup",
      "cognito-idp:AdminUpdateUserAttributes",
      "cognito-idp:AdminDeleteUser",
    ]
    resources = [module.cognito.user_pool_arn]
  }

  # WHY : (1) Refactoring Rationale: these two statements exist because a
  #       runtime-created pool account was unreachable without them. The account was
  #       created with the message action suppressed and no temporary password, so the
  #       provider generated one and delivered it nowhere -- and the pool's schema
  #       carries no email and no phone attribute, so there was no address a message
  #       could have reached. Its owner could not obtain a credential, could not
  #       answer the force-change challenge and could not sign in by any path. The
  #       service now generates the temporary password itself and writes it to one
  #       managed-secret entry per account; without these two statements every user
  #       creation fails at the store instead, which is a louder failure than the
  #       silent one it replaces but still a broken operation.
  #       (2) Assumptions: the actions are exactly the three the service issues and no
  #       fourth. CreateSecret writes the entry; PutSecretValue is the retry path,
  #       taken when CreateSecret reports the entry already exists, which is what
  #       makes a repeated provisioning attempt idempotent rather than fatal; and
  #       DeleteSecret is the compensating withdrawal, issued when a later
  #       provisioning step fails and the account is being removed, so a credential
  #       never outlives the identity it opens. GetSecretValue is deliberately NOT
  #       granted: this task writes these entries and never reads one back, and the
  #       collecting principal is an out-of-band operator identity rather than this
  #       role -- the same division the seeded initial-password entries already use.
  #       (3) Trade-offs: the resource is a wildcard BENEATH the pool credential
  #       prefix rather than a set of entry ARNs. It has to be, because the names are
  #       derived at run time from identifiers that do not exist at apply time. The
  #       compensation is that the prefix is narrow -- the `/runtime-user/` infix is
  #       written by nothing else, and the seeded entries sit under a sibling
  #       `/seed-user/` infix that this statement does not reach, so this task cannot
  #       overwrite or delete a seeded credential.
  statement {
    sid = "PublishRuntimeUserCredentials"
    actions = [
      "secretsmanager:CreateSecret",
      "secretsmanager:PutSecretValue",
      "secretsmanager:DeleteSecret",
    ]
    resources = [
      "arn:${data.aws_partition.current.partition}:secretsmanager:${data.aws_region.current.region}:${data.aws_caller_identity.current.account_id}:secret:${module.cognito.credential_secret_name_prefix}/runtime-user/*",
    ]
  }

  # WHY : Assumptions: the key grant is separate from the entry grant and carries
  #       Encrypt and GenerateDataKey but NOT Decrypt. The store performs the
  #       cryptographic calls under the CALLING identity, so writing an entry
  #       encrypted with a customer-managed key needs those two actions in this
  #       policy as well as the entry actions above -- which is precisely the property
  #       that makes a customer-managed key a stronger control than the account's
  #       default managed key, where a read grant alone would suffice. Decrypt is
  #       withheld because this task never reads a credential back, so a grant for it
  #       would let a compromised task recover every one-time password it had ever
  #       written rather than only influence the next.
  statement {
    sid       = "EncryptRuntimeUserCredentials"
    actions   = ["kms:Encrypt", "kms:GenerateDataKey"]
    resources = [module.kms.secrets_key_arn]
  }
}

locals {
  task_role_policy_json = {
    auth          = data.aws_iam_policy_document.auth_runtime.json
    card          = data.aws_iam_policy_document.card_runtime.json
    account       = data.aws_iam_policy_document.account_runtime.json
    reference     = data.aws_iam_policy_document.reference_runtime.json
    authorization = data.aws_iam_policy_document.authorization_runtime.json
    reporting     = data.aws_iam_policy_document.reporting_runtime.json
    # WHY : Assumptions: batch names batch_runtime here and NOT a document that wraps it with
    #       the error-queue send statement, even though the batch task is the one workload that
    #       publishes to that queue. The send grant travels by a different route -- the batch
    #       entry of local.sqs_permissions_by_workload above, which modules/ecs-service turns
    #       into a send-only statement for that task alone -- so it is already scoped to one
    #       workload without a second document existing to scope it.
    #       Alternatives Considered: adding the statement to batch_runtime itself, which is the
    #       shortest edit and was written that way first. Rejected because
    #       data_migration_runtime SOURCES batch_runtime to inherit the dataset-bucket and key
    #       grants the two tasks genuinely share, so a statement added there is inherited by a
    #       task that publishes no error event, holds no queue client and is never handed the
    #       sink's address.
    #       Trade-offs: the queue's encryption-key grant is not restated on either route.
    #       modules/sqs provisions every queue encrypted under the customer-managed queue key
    #       and attaches the use-the-key grant to the callers it is told about, so a grant
    #       written here would be a duplicate whose drift from that list would be invisible. A
    #       publish that fails with a key error is fixed in that module's caller list.
    batch          = data.aws_iam_policy_document.batch_runtime.json
    data-migration = data.aws_iam_policy_document.data_migration_runtime.json
  }
}

module "ecs_service" {
  for_each = local.workloads
  source   = "../../modules/ecs-service"

  service_name           = each.key
  environment            = var.environment
  name_prefix            = var.name_prefix
  cluster_arn            = module.ecs_cluster.cluster_arn
  cluster_name           = module.ecs_cluster.cluster_name
  vpc_id                 = module.network.vpc_id
  private_app_subnet_ids = module.network.private_app_subnet_ids
  security_group_ids     = [module.network.app_security_group_id]
  # WHY : Assumptions: a digest is used when one is supplied for this artifact and
  #       the mutable tag otherwise. infra/modules/ecs-service refuses a
  #       non-digest reference when environment is "prod", so this expression is
  #       what lets one root shape serve both postures: dev iterates by pushing over
  #       a tag, production pins the exact build. A tag can be moved after the task
  #       definition is registered, which would let a scale-out event start a
  #       different build than the one that was reviewed.
  image_uri = (
    lookup(var.image_digests, each.value.repository, null) != null
    ? "${module.ecr.repository_urls[each.value.repository]}@${var.image_digests[each.value.repository]}"
    : "${module.ecr.repository_urls[each.value.repository]}:${var.image_tag}"
  )
  ecr_repository_arn = module.ecr.repository_arns[each.value.repository]

  # WHY : Assumptions: the sidecar image and the repository ARN authorizing its pull
  #       are passed TOGETHER, because either alone is a broken deployment: the image
  #       without the grant is a pull the execution role is refused, and the grant
  #       without the image authorizes a repository nothing fetches. Both read from
  #       the same mirror repository key, so they cannot name different repositories.
  # WHY : Refactoring Rationale: this reference now prefers the mirrored image's
  #       DIGEST and falls back to its tag, where it previously always named the tag.
  #       A tag identifies an artifact by a label the registry lets a push move,
  #       whereas the digest identifies the bytes; the eight service task definitions
  #       already resolve their own images this way through image_digests, and the
  #       sidecar -- the one container every workload runs -- was the exception.
  #       .github/workflows/deploy.yml records the mirror's digest under the
  #       `aws-otel-collector` key after the mirror step, so a deployment resolves
  #       the digest form and a plan run before any mirror exists still resolves the
  #       tag form rather than failing on a missing key.
  # WHY : Trade-offs: repository immutability makes the tag form safe as a fallback,
  #       so this is defence in depth rather than a correction of something broken.
  #       What it buys is that the task definition records WHICH collector bytes ran,
  #       which a tag cannot answer after the fact.
  telemetry_collector_image = (
    lookup(var.image_digests, local.telemetry_collector_repository, null) != null
    ? "${module.ecr.repository_urls[local.telemetry_collector_repository]}@${var.image_digests[local.telemetry_collector_repository]}"
    : "${module.ecr.repository_urls[local.telemetry_collector_repository]}:${local.telemetry_collector_image_tag}"
  )
  telemetry_collector_repository_arn = (
    module.ecr.repository_arns[local.telemetry_collector_repository]
  )
  container_name       = each.value.container_name
  container_port       = module.network.app_container_port
  task_cpu             = var.ecs_task_cpu
  task_memory          = var.ecs_task_memory
  attach_load_balancer = each.value.online
  create_service       = each.value.online
  desired_count        = each.value.online ? var.ecs_desired_count : 1
  enable_autoscaling   = each.value.online
  min_capacity         = var.ecs_desired_count
  max_capacity         = max(var.ecs_desired_count, var.ecs_desired_count * 2)
  health_check_path    = local.health_check_path
  # WHY : Assumptions: looked up with a null default rather than indexed, because
  #       data-migration is intentionally absent from the map and the module reads null
  #       as "this workload has no in-container probe".
  container_health_check_command = lookup(local.container_health_check_commands, each.key, null)
  target_protocol                = "HTTPS"
  log_retention_in_days          = var.log_retention_days
  log_group_kms_key_arn          = module.kms.s3_key_arn

  # WHY : Assumptions: passed for exactly the online workloads, which is the same
  #       gate that injects CARDDEMO_ONLINE_WRITES_PARAMETER into this workload's
  #       environment above. The module requires the two to travel together, because
  #       the flag's NAME without permission to read it is an access-denied on every
  #       gated request, and the permission without the name is a grant the task
  #       cannot use. Null for batch and data-migration: batch is the workload the
  #       quiesce PROTECTS and must keep writing through the window, and
  #       data-migration serves no request.
  # WHY : Assumptions: the ARN rather than the name, because an IAM Resource element
  #       cannot be a bare parameter name. The module composes the ssm:GetParameter
  #       statement itself from this one value, so no policy document assembled here
  #       can widen it.
  online_write_gate_parameter_arn = each.value.online ? aws_ssm_parameter.online_writes_enabled.arn : null

  # WHY : Assumptions: the same online/offline topology fact is passed a second time,
  #       as a boolean, because the module selects the write-gate policy's cardinality
  #       from it. The ARN above is this root's own aws_ssm_parameter attribute and is
  #       unknown until that parameter exists, so a count derived from it could not be
  #       decided during plan; the boolean is known here before anything is created.
  #       The module refuses the two halves separately, so passing one without the other
  #       fails at plan time rather than producing a task that cannot read the flag.
  create_online_write_gate_policy = each.value.online

  environment_variables = local.environment_variables_by_workload[each.key]
  ssm_parameter_arns    = local.runtime_parameter_arns_by_service[each.key]
  secret_arns           = local.secret_sources_by_workload[each.key]
  # WHY : Assumptions: the two grants are looked up with a default of the empty set
  #       rather than indexed, so a workload that puts no message on any queue --
  #       data-migration is now the only one -- receives no SQS statement at all instead of
  #       failing the plan on a missing map key.
  # WHY : Refactoring Rationale: this comment named BATCH as a workload that puts no message
  #       on any queue, and that stopped being true when batch-service's error-sink producer
  #       was wired. It now appears in sqs_permissions_by_workload above with a send-only
  #       boundary on the error queue, so the default arm covers data-migration alone. The
  #       default is kept rather than replaced by direct indexing because data-migration
  #       still has no queue of any kind and indexing would fail the plan on its missing key.
  # WHY : Refactoring Rationale: `lookup` with a default, NOT `try`. Both express the
  #       same intent and only one of them plans. `try` returns an UNKNOWN value
  #       whenever its expression contains one, because it cannot decide in advance
  #       whether evaluation would have failed -- and these ARNs are the queue module's
  #       own attributes, so on a first apply they are unknown. The module selects the
  #       cardinality of its queue policy from `length()` of these lists, so the whole
  #       package failed with `Invalid count argument: the "count" value depends on
  #       resource attributes that cannot be determined until apply` for every
  #       queue-using workload. `lookup` decides from the map's KEYS, which are known
  #       here before anything is created, so the length is known even though the ARNs
  #       inside are not. Measured both forms against an unknown-valued map before
  #       changing this.
  # WHY : Assumptions: the default object declares BOTH members, because lookup must
  #       return one type for every key. An empty object would make the two arms
  #       disagree and fail type unification rather than falling back.
  sqs_receive_queue_arns        = lookup(local.sqs_permissions_by_workload, each.key, local.no_queue_permissions).receive
  sqs_send_queue_arns           = lookup(local.sqs_permissions_by_workload, each.key, local.no_queue_permissions).send
  create_task_role_policy       = contains(keys(local.task_role_policy_json), each.key)
  task_role_policy_json         = lookup(local.task_role_policy_json, each.key, null)
  execution_secret_kms_key_arns = length(local.secret_sources_by_workload[each.key]) > 0 ? [module.kms.secrets_key_arn] : []

  # WHY : Assumptions: the boundary is an account-level policy this deployment
  #       does not create, because a boundary a deployment can rewrite bounds
  #       nothing. Passing it to every task means the inline policy this root
  #       composes per service cannot exceed the account ceiling even if the
  #       document is later widened.
  permissions_boundary_arn = var.permissions_boundary_arn

  # WHY : Assumptions: no task may be created before the database bootstrap has run.
  #       Every service resolves a credential at startup and then migrates or queries
  #       immediately, so a task that started first would fail authentication against a
  #       role that did not yet exist -- and on a load-balanced service that presents as
  #       a crash loop rather than as a missing precondition. The edge is on the MODULE
  #       rather than on the task definition because the service, not the definition, is
  #       what starts a container.
  # WHY : Refactoring Rationale: this edge was absent. The dependency reached
  #       module.secrets implicitly through local.secret_sources_by_workload, so a task
  #       could not be created before its secret existed -- but nothing ordered it after
  #       the bootstrap that creates the ROLE that secret authenticates as. Terraform was
  #       free to create the services in parallel with the invocation, which is the
  #       narrow window in which a task starts against a database holding no CardDemo
  #       role at all.
  depends_on = [aws_lambda_invocation.database_bootstrap]
}

# =============================================================================
# Internal name resolution for the load balancer.
#
# Purpose:
#   Make local.internal_service_dns_name resolve to the internal load balancer
#   from inside this VPC, so one CardDemo service can reach another by the name
#   the listener certificate covers.
#
# Refactoring Rationale: nothing resolved that name before, at any layer. The
#   name was already used in two places -- as the listener certificate's identity
#   and as the TLS server name the HTTP API verifies on its private integration --
#   and neither needs DNS: the API Gateway integration targets the listener by ARN
#   and resolves nothing. infra/modules/alb records that absence deliberately and
#   publishes alb_zone_id precisely so a caller that later wants a friendly
#   internal name can build the alias itself, noting that creating the record
#   inside a load-balancer module would leave the record and the service it names
#   owned by different layers. This is that caller, and this is that record.
#
# Assumptions: a PRIVATE hosted zone, associated with this VPC alone. The
#   certificate is a publicly-validated ACM certificate for a name the operator
#   owns, so the operator's public DNS may well also answer for it -- and inside
#   this VPC that answer must not be used, because the load balancer is internal
#   and has no publicly routable address. A private zone for exactly this name
#   shadows the public answer for exactly this name and for nothing else: the zone
#   apex IS the hostname, so no sibling name in the same parent domain is
#   affected.
#
# Alternatives Considered: pointing the base URL at the load balancer's
#   AWS-assigned name instead, which resolves inside the VPC with no zone at all.
#   Rejected because the listener certificate covers the operator's name and not
#   the assigned one, so every call would fail hostname verification -- and the
#   remedy for that would be to stop verifying the hostname, which is the control
#   being relied on.
#
# Alternatives Considered: an optional hosted-zone id input, so a root could
#   supply a zone it already owns and this configuration would create only the
#   record. Rejected because an optional input defaulting to null makes the record
#   optional in practice, and an absent record is exactly the defect being fixed:
#   it would surface as every internal lookup failing to connect, in an
#   environment that planned and applied cleanly.
# =============================================================================

resource "aws_route53_zone" "internal_service" {
  name = local.internal_service_dns_name

  # WHY : Assumptions: the comment is what distinguishes this zone in a console
  #       listing from the operator's public zone for the same name, which is the
  #       one place the two are genuinely easy to confuse.
  comment = "Private zone resolving ${local.internal_service_dns_name} to the internal CardDemo load balancer inside the ${var.environment} VPC."

  vpc {
    vpc_id = module.network.vpc_id
  }

  tags = { Name = "${var.name_prefix}-internal-${var.environment}" }
}

resource "aws_route53_record" "internal_service" {
  zone_id = aws_route53_zone.internal_service.zone_id
  name    = local.internal_service_dns_name

  # WHY : Assumptions: an A record with an alias target, not a CNAME. The record is
  #       at the zone APEX, and a CNAME at an apex is prohibited -- the apex must
  #       also carry the zone's own SOA and NS records, which a CNAME may not
  #       coexist with. An alias is the mechanism that answers with an address at an
  #       apex, and it costs nothing per query.
  type = "A"

  alias {
    name    = module.alb.alb_dns_name
    zone_id = module.alb.alb_zone_id

    # WHY : Trade-offs: target health IS evaluated. With it, a caller resolving this
    #       name during a load-balancer outage receives no answer rather than an
    #       address that refuses connections, so the failure arrives as a resolution
    #       failure at once instead of as a connection timeout after the client's
    #       connect budget. The cost is that a resolution failure is a less obvious
    #       diagnostic than a refused connection; the gain is that the pending
    #       authorization consumer, whose calls run inside a transaction holding a
    #       pooled connection, fails fast rather than holding that connection for
    #       the whole timeout.
    evaluate_target_health = true
  }
}

# WHY : Refactoring Rationale: subnet_ids passed module.network.private_app_subnet_ids,
#       which CONTRADICTED the frozen target topology and contradicted the alb module's
#       own input documentation at the same time. AAP 0.4.1.9 places the load balancer
#       in the PUBLIC subnets -- "public subnets carrying only the load balancer and NAT
#       gateways, private application subnets carrying the ECS tasks, and isolated data
#       subnets with no internet route at all carrying Aurora" -- and
#       infra/modules/alb/variables.tf already documented that "in this package the
#       caller passes the public subnets" while this line passed the private ones. The
#       code is aligned to the frozen plan rather than the plan reinterpreted, because
#       the topology is a frozen decision and this root is not the layer that may revise
#       it.
# WHY : Assumptions: PUBLIC SUBNETS AND AN INTERNAL LOAD BALANCER ARE NOT IN TENSION,
#       and reading them as a contradiction is the expected mistake -- which is why the
#       alb module records the reconciliation at the input rather than leaving it to be
#       re-derived. `internal = true` in that module is what withholds public addresses
#       and the internet-routable name, and it does so whatever the subnets' route
#       tables say. So this placement changes where the nodes' elastic network
#       interfaces live; it does not make the load balancer reachable from the internet.
#       Nothing else about the flow changes either: the security-group rules are
#       unaffected by placement, the API Gateway VPC Link continues to reach the
#       listener by ARN through a private integration, and service-to-service calls
#       continue to resolve the private zone's apex record.
module "alb" {
  source = "../../modules/alb"

  name_prefix                = var.name_prefix
  environment                = var.environment
  subnet_ids                 = module.network.public_subnet_ids
  alb_security_group_id      = module.network.alb_security_group_id
  certificate_arn            = local.alb_certificate_arn
  certificate_domain_name    = local.internal_service_dns_name
  access_logs_bucket         = module.observability.access_log_bucket_name
  enable_deletion_protection = var.deletion_protection
  health_check_path          = local.health_check_path
  service_routes = {
    for name, service in local.online_services :
    name => {
      priority         = service.priority
      path_patterns    = service.paths
      target_group_arn = module.ecs_service[name].target_group_arn
    }
  }
}

module "api_gateway" {
  source = "../../modules/api-gateway-http"

  name_prefix                 = var.name_prefix
  environment                 = var.environment
  cognito_issuer_uri          = module.cognito.issuer_uri
  cognito_app_client_ids      = [module.cognito.user_pool_client_id]
  route_authorization_scopes  = module.cognito.interactive_route_authorization_scopes
  alb_listener_arn            = module.alb.https_listener_arn
  private_app_subnet_ids      = module.network.private_app_subnet_ids
  alb_security_group_id       = module.network.alb_security_group_id
  spa_cors_allow_origins      = [local.spa_origin]
  log_retention_days          = var.log_retention_days
  access_log_kms_key_arn      = module.kms.s3_key_arn
  integration_tls_server_name = local.internal_service_dns_name
}

module "step_functions" {
  source = "../../modules/step-functions-batch"

  name_prefix                        = var.name_prefix
  environment                        = var.environment
  ecs_cluster_arn                    = module.ecs_cluster.cluster_arn
  batch_task_definition_arn          = module.ecs_service["batch"].task_definition_arn
  data_migration_task_definition_arn = module.ecs_service["data-migration"].task_definition_arn
  reporting_task_definition_arn      = module.ecs_service["reporting"].task_definition_arn

  # WHY : Assumptions: a FOURTH image is wired because the operator-invoked
  #       authorization-extract machine runs the authorization container, not the batch
  #       one. The segment export reads the authorization schema and only that context's
  #       database role may, so the capability cannot be hosted in an image that already
  #       had a task definition here.
  authorization_task_definition_arn = module.ecs_service["authorization"].task_definition_arn

  batch_container_name          = module.ecs_service["batch"].container_name
  data_migration_container_name = module.ecs_service["data-migration"].container_name
  reporting_container_name      = module.ecs_service["reporting"].container_name
  authorization_container_name  = module.ecs_service["authorization"].container_name
  private_app_subnet_ids        = module.network.private_app_subnet_ids
  task_security_group_id        = module.network.app_security_group_id

  # WHY : Assumptions: every role a state machine may run a task AS is enumerated --
  #       the task role and the task EXECUTION role of each task definition THAT
  #       machine runs. The module turns each list into the Resource of that machine's
  #       own iam:PassRole statement, so an omitted entry is not a narrower grant but a
  #       run-task that fails with an access-denied error naming iam:PassRole rather
  #       than the missing role.
  # WHY : Refactoring Rationale: this was one flat list of eight ARNs granted to a
  #       single execution role every machine shared. It is keyed by machine because
  #       the roles are now per machine: the ad-hoc report machine runs the reporting
  #       image and nothing else, so listing the batch and authorization task roles
  #       under it would have re-created exactly the union grant the split removes.
  #       The batch image's two roles appear twice on purpose -- the daily chain and
  #       the dataset round trip both run it.
  pass_role_arns = {
    daily = [
      module.ecs_service["batch"].task_role_arn,
      module.ecs_service["batch"].execution_role_arn,
      module.ecs_service["data-migration"].task_role_arn,
      module.ecs_service["data-migration"].execution_role_arn,
      module.ecs_service["reporting"].task_role_arn,
      module.ecs_service["reporting"].execution_role_arn,
    ]
    adhoc = [
      module.ecs_service["reporting"].task_role_arn,
      module.ecs_service["reporting"].execution_role_arn,
    ]
    dataset = [
      module.ecs_service["batch"].task_role_arn,
      module.ecs_service["batch"].execution_role_arn,
    ]
    authz = [
      module.ecs_service["authorization"].task_role_arn,
      module.ecs_service["authorization"].execution_role_arn,
    ]
  }
  quiesce_function_arn        = aws_lambda_function.quiesce.arn
  resume_function_arn         = aws_lambda_function.resume.arn
  analyze_tables_function_arn = aws_lambda_function.database_admin.arn

  # WHY : Assumptions: the same parameter the two functions already receive
  #       through their own environment variables is named again here, so the
  #       state machine's definition records which flag its quiesce bracket
  #       toggles instead of leaving that discoverable only from the function
  #       resources above. One owner, referenced twice, rather than two
  #       independently maintained spellings.
  # WHY : Assumptions: the value the module puts in each invocation payload is a
  #       CROSS-CHECK and not a redirect. The handler writes the parameter its own
  #       environment names and REFUSES an invocation naming any other, so the two
  #       references above cannot silently disagree -- a root that wired a
  #       different parameter here than into the functions fails the invocation
  #       instead of reporting a resume it never performed. It is therefore not a
  #       second flag and there is no multi-flag capability to configure.
  read_only_flag_parameter_name = aws_ssm_parameter.online_writes_enabled.name

  notification_topic_arn = module.observability.notification_topic_arn
  dataset_bucket_name    = module.s3_datasets.bucket_name

  # WHY : Refactoring Rationale: this input replaces the withdrawn dataset_staging_root,
  #       which named a filesystem path (/mnt/carddemo-extracts) that nothing in this
  #       root provisions -- the Fargate tasks carry no volume and the data-migration
  #       image ships no extract -- so all ten branches of the seed-refresh state read an
  #       absent file. The value comes from the module that OWNS the prefix, provisions
  #       its lifecycle rule and publishes it, rather than being restated here, so the
  #       prefix the refresh reads and the prefix the bucket governs cannot disagree.
  #       Populating it remains an operator action, and it is the sync
  #       docs/runbooks/data-migration.md already documents.
  dataset_source_extract_prefix = module.s3_datasets.source_extract_prefix

  log_retention_days = var.log_retention_days

  # WHY : Assumptions: the same local is passed to the module and set on the resume
  #       function's environment above, which is the whole reason it is a local. See
  #       its declaration for why a second spelling would be a silent failure.
  task_started_by = local.batch_task_started_by

  # WHY : Assumptions: the queue key rather than the S3 key, because what it encrypts
  #       is the bracket-release dead-letter QUEUE. Every other queue in this
  #       deployment, including the scheduler's dead-letter target, is under this key.
  dead_letter_kms_key_arn = module.kms.sqs_key_arn

  # WHY : Assumptions: the S3 customer-managed key is the one this stack uses for
  #       CloudWatch log groups as well, so the state machines' two execution log
  #       groups are encrypted under a project-owned key rather than CloudWatch's
  #       service-managed one. The module's input is nullable and defaults to null
  #       precisely so a module can be applied without a key; this root supplies
  #       one, because encryption at rest is one of the properties the migration
  #       adds over a baseline whose every CICS file ran RECOVERY(NONE) JOURNAL(NO).
  log_group_kms_key_arn = module.kms.s3_key_arn

  # WHY : Assumptions: the SQS customer-managed key is supplied here even though this
  #       root creates no queue in that module's shape. The step-functions module
  #       declares one queue of its own -- the dead-letter queue holding a
  #       bracket-release event EventBridge could not deliver -- and its input is
  #       nullable so the module can be applied without a key module beside it. This
  #       root has one, and a queue whose contents identify which night's release was
  #       lost is encrypted under a project-owned key for the same reason every other
  #       store in this stack is.
  # WHY : Alternatives Considered: passing the S3 key already wired above, which would
  #       need no second reference. Rejected because the kms module publishes four keys
  #       precisely so that one compromised grant does not reach two stores, and reusing
  #       the object-store key for a queue would undo that at the one call site nobody
  #       would think to check.
}

module "eventbridge_scheduler" {
  source = "../../modules/eventbridge-scheduler"

  name_prefix             = var.name_prefix
  environment             = var.environment
  state_machine_arn       = module.step_functions.daily_state_machine_arn
  dead_letter_arn         = module.sqs.error_queue_arn
  dead_letter_kms_key_arn = module.kms.sqs_key_arn
  schedule_expression     = var.batch_schedule_expression
  kms_key_arn             = module.kms.s3_key_arn

  depends_on = [aws_iam_role_policy.dataset_retention_s3]
}

module "observability" {
  source = "../../modules/observability"

  name_prefix      = var.name_prefix
  environment      = var.environment
  ecs_cluster_name = module.ecs_cluster.cluster_name
  alb_arn_suffix   = module.alb.alb_arn_suffix
  service_target_group_arn_suffixes = {
    for name in keys(local.online_services) :
    name => module.ecs_service[name].target_group_arn_suffix
  }
  api_gateway_id                = module.api_gateway.api_id
  api_gateway_stage_name        = module.api_gateway.stage_name
  aurora_cluster_identifier     = module.aurora.cluster_identifier
  aurora_max_capacity           = var.aurora_max_capacity
  database_connection_threshold = local.database_connection_budget
  queue_names                   = module.sqs.queue_names
  daily_state_machine_arn       = module.step_functions.daily_state_machine_arn
  vpc_flow_log_group_name       = module.network.flow_log_group_name
  cloudfront_distribution_id    = module.cloudfront_spa.distribution_id
  # WHY : Assumptions: the same fact is also passed as a boolean, because the module
  #       selects the distribution alarm's cardinality from it. The identifier above is
  #       created by this root and is unknown until then, so a count derived from it
  #       could not be decided during plan. This root always creates the distribution,
  #       so the alarm is always wanted.
  create_cloudfront_alarm         = true
  kms_key_arn                     = module.kms.s3_key_arn
  log_retention_days              = var.log_retention_days
  log_group_names                 = local.lambda_log_group_names
  dashboard_service_names         = sort(keys(local.online_services))
  alarm_email_endpoints           = var.alarm_email_endpoints
  access_log_bucket_force_destroy = !var.deletion_protection

  # WHY : Assumptions: rotation_lambda_function_names is deliberately left at its
  #       empty default, so no rotation-Errors alarm is created. There is no
  #       rotation function in this stack to alarm on: infra/modules/secrets
  #       implements no rotation and this root supplies none, and the observability
  #       module's own input contract states that an empty set is the correct value
  #       when rotation is not provisioned in the composed root. Naming a function
  #       that does not exist would create an alarm permanently in INSUFFICIENT_DATA,
  #       which is indistinguishable from a control that is running cleanly.
}

# WHY : Assumptions: this assertion is the guard on the one deterministically
#       composed ARN used to break the reporting task/state-machine cycle.
resource "terraform_data" "cross_module_contracts" {
  input = {
    expected_adhoc_report_arn = local.adhoc_report_state_machine_arn
    actual_adhoc_report_arn   = module.step_functions.adhoc_report_state_machine_arn
  }

  lifecycle {
    precondition {
      condition     = local.adhoc_report_state_machine_arn == module.step_functions.adhoc_report_state_machine_arn
      error_message = "The deterministic ad-hoc report state-machine ARN no longer matches the step-functions-batch module output; update the cycle-breaking contract and its consumer together."
    }
  }
}
