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
  # WHY : Refactoring Rationale: this read
  #       `coalesce(var.internal_service_domain_name, "<prefix>-<env>.services.internal")`
  #       and the note above it explained when the composed `.internal` default would
  #       be selected. It never could be. That variable is declared `nullable = false`
  #       with NO default, so Terraform demands a value and refuses null, and its
  #       validation regex additionally refuses the empty string that is the only other
  #       input `coalesce` would skip. The fallback was therefore unreachable, and the
  #       rationale described a behaviour this root does not have -- which is worse than
  #       no comment, because a reader planning a deployment without a private zone
  #       would have believed a default existed. The alias is now the input itself, and
  #       the requirement is visible at the point the value is read.
  internal_service_dns_name = var.internal_service_domain_name

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
  # WHY : Refactoring Rationale: this carried the same unreachable `coalesce` as the
  #       alias above, spelled out a second time. Both are gone, and this now composes
  #       from the same required input -- so there is one place the internal name comes
  #       from and no expression here that implies a default exists.
  account_context_origin = "https://${var.internal_service_domain_name}"

  # WHY : Assumptions: the reference context answers on the SAME internal origin, because every
  #       migrated service sits behind the one internal load balancer and is addressed by path.
  #       It is aliased rather than given its own copy of the expression above so that an edit to
  #       the internal domain cannot move one consumer and leave the other pointed at an address
  #       that no longer answers. Alternatives Considered: passing local.account_context_origin
  #       straight into the reference entries below. Rejected because the name would then say
  #       "account" at a call site that configures a reference lookup, which is exactly the kind
  #       of mismatch a later reader corrects in the wrong direction.
  reference_context_origin = local.account_context_origin

  # WHY : Refactoring Rationale: two locals stood here, telemetry_collector_image_tag
  #       and telemetry_collector_repository, naming the version and the mirror
  #       repository of an AWS Distro for OpenTelemetry collector sidecar. Both are
  #       withdrawn with the sidecar itself; the argument is recorded at the
  #       ecs-service module block below, which is where the inputs they fed used to
  #       be passed.

  # WHY : Refactoring Rationale: this described a self-signed ALB certificate used
  #       "when an operator-issued one is not supplied". There is no such fallback and
  #       there is no longer a self-signed certificate resource in this root at all --
  #       the generator was deleted, each task now mints its own leaf, and
  #       var.alb_certificate_arn is `nullable = false` with no default, so an
  #       operator-issued ARN is the only value this can ever hold. The note is
  #       withdrawn rather than reworded because every clause in it rested on a
  #       fallback that cannot occur, including its Trade-offs sentence about an apply
  #       "silently falling back".
  # WHY : Assumptions: the certificate is terminated by the INTERNAL load balancer,
  #       which publishes no public listener, so the trust decision is the VPC's rather
  #       than a browser's. That remains the reason this listener and the CloudFront
  #       edge take their certificates from two separate required inputs.
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
  # WHY : Assumptions: these patterns carry BUSINESS operations only, and the omission
  #       of each service's own API-description addresses is deliberate rather than an
  #       oversight. Two contexts -- card and reference -- serve a generated document,
  #       the committed contract and an interactive view at /v3/api-docs,
  #       /<service>-api.yaml and /swagger-ui.html, and their filter chains grant those
  #       addresses to a token carrying either group. None of them is listed here, so a
  #       request for one at the public edge is answered by the load balancer's default
  #       action: they are reachable from inside the VPC only. That is the intended
  #       posture -- an interactive request-issuing console at the internet edge is a
  #       wider surface than AAP 0.4.1.9 accepts for a document already committed to
  #       this repository under services/*/src/main/resources/openapi/ -- and the
  #       services assert their half of it, so the two cannot drift apart silently.
  #       See the reachability rationale on SecurityConfig.DOCUMENTATION_PATHS and
  #       ContractPublicationTest in each of those two modules.
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

  # WHY : Assumptions: the NINE login identities in V0's service_roles array -- the
  #       eight bounded-context service roles plus the read-only verification
  #       identity -- are named by data-migration/sql/V0__schemas_and_roles.sql and
  #       created there without a password; this list is the same inventory in the
  #       same order, used to build the ETL's alternate-login map and nothing else. It is written out rather
  #       than derived from local.workloads because reporting and batch share no
  #       one-to-one mapping with a role in that structure -- data-migration has no
  #       role at all -- so deriving it would need a filter that says less than the
  #       list does.
  # WHY : ⚠️ Refactoring Rationale: the fourth entry read carddemo_transaction, which
  #       V0 declares nowhere. The role for the transaction service's schema is
  #       carddemo_ledger, because the schema is named ledger rather than transaction
  #       -- V0's service_roles array names it at L252 and
  #       carddemo_migration.config.ROLE_FOR_SCHEMA maps "ledger" to it. The cost was
  #       not cosmetic: this list becomes the ETL's alternate-login ALLOWLIST, so a
  #       name absent from the database made the real rotated ledger login
  #       unrecognised and refused, while the invented name matched nothing and so
  #       raised no error of its own. Nothing in an apply could have caught it, which
  #       is why a closure gate against V0 now guards the list -- see the
  #       "database role inventory" step in .github/workflows/infra-ci.yml.
  #       Alternatives Considered: deriving the list from local.workloads and mapping
  #       the transaction workload to its schema name, which is what would have made
  #       the drift impossible in the first place. Rejected for the reason already
  #       recorded above -- the mapping is not one-to-one for reporting, batch or
  #       data-migration -- so the list stays explicit and the GATE, rather than the
  #       expression, is what holds it to V0.
  # WHY : ⚠️ Refactoring Rationale: carddemo_verifier was missing, and it is the SAME
  #       defect as the one above rather than a separate concern. The ETL resolves the
  #       verifier's credential through the identical resolver every other role goes
  #       through -- carddemo_migration.config.verification_settings delegates to
  #       _resolve_settings_for_role, whose contract refuses a secret whose user name
  #       is "neither VERIFIER_ROLE nor an alternate allowlisted for it" -- so a
  #       rotated verifier credential was refused for exactly the reason a rotated
  #       ledger credential was. It was invisible for the same reason too: an absent
  #       entry raises nothing until a rotation happens.
  #       Assumptions: the SEVEN migrator logins stay absent even though V0 declares
  #       them as credentialed identities in its section-6 array, because that array
  #       is a different inventory answering a different question -- which roles need
  #       a password applied -- while this list answers which roles the ETL itself
  #       connects as. The gate therefore closes against V0's FIRST service_roles
  #       array, the role-creation one, and not the section-6 sixteen.
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
    "carddemo_ledger",
    "carddemo_reference",
    "carddemo_batch",
    "carddemo_authorization",
    "carddemo_reporting",
    "carddemo_verifier",
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

  # WHY : Assumptions: this is the batch chain's object-key surface, and it is exactly
  #   the TEN generation families module.s3_datasets publishes -- not the bucket. The
  #   nightly chain writes and re-reads generations (the backup, the combined file, the
  #   reject stream, the statement and report inputs it stages) and touches nothing
  #   else in the bucket. The three non-generation reporting prefixes and the
  #   authorization extract prefix are deliberately absent: they belong to two other
  #   workloads whose own roles above grant them, and a batch task that could read them
  #   would be able to read every statement and every pending-authorization extract in
  #   the estate.
  # WHY : Trade-offs: derived from the module's published map rather than written out
  #   here, so adding a generation family to that module extends this grant with no edit
  #   while a prefix that is not a generation family stays outside it. Writing the ten
  #   names here instead would be a second inventory to keep in step, and the copy that
  #   goes stale is the one nothing validates.
  batch_generation_key_prefixes = values(module.s3_datasets.dataset_prefixes)

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

  # Assumptions: the TEN generation-family prefixes, taken from the module's own
  #   `dataset_prefixes` output rather than restated, so this list cannot name a
  #   family the module does not provision or miss one it does. Each value is the
  #   `<domain>/<dataset>/` form; the `dt=` and `gen=` segments below it are chosen
  #   per run by the writer and no policy can enumerate them.
  # Assumptions: this is exactly the set the retention function can legitimately
  #   reach. infra/lambda/dataset_generation_retention.py derives its working prefix
  #   with `_family_prefix`, which returns everything before the `dt=` segment and
  #   therefore returns one of these ten values or None -- a non-generation key is
  #   ignored rather than pruned. Granting these ten is granting what the function
  #   does, and nothing wider.
  # Trade-offs: a prefix condition and an object-ARN restriction are BOTH applied to
  #   the retention role, for the reason already recorded against
  #   `reporting_object_key_prefixes` above -- an object ARN cannot bound
  #   `ListBucket`, whose resource is the bucket itself, and an `s3:prefix` condition
  #   does not apply to `DeleteObject`, whose scope is expressed only by the object
  #   ARN. Applying one and not the other would leave the other call unbounded.
  dataset_generation_key_prefixes = values(module.s3_datasets.dataset_prefixes)
}

# -----------------------------------------------------------------------------
# Lambda deployment packages.
# -----------------------------------------------------------------------------

# WHY : ⚠️ Refactoring Rationale: three archive-provider data sources stood here and
#       built these packages during plan, which obliged this root to declare
#       `hashicorp/archive`. AAP section 0.6.1.4 fixes the provider inventory at the
#       Terraform CLI, `hashicorp/aws` and `hashicorp/random`, and a comment recording
#       the extra provider does not amend a frozen plan. The packages are now built by
#       infra/lambda/build_packages.py -- standard library only, no provider -- and
#       consumed here through the AWS provider alone.
# WHY : Assumptions: `filebase64sha256` reads the SAME bytes Lambda receives, so the
#       hash still changes exactly when a handler changes and never otherwise. That
#       property is what the builder's fixed timestamps exist to preserve; without them
#       every plan would show all four functions being updated.
# WHY : Trade-offs: a plan now requires the packages to exist, and they are ignored by
#       git rather than committed. A plan run without the build step fails while
#       resolving the hash and names the absent path, which is actionable; committing
#       the archives instead would hide reviewed source behind an opaque binary. Both
#       pipeline plan steps and docs/runbooks/deploy.md run the builder first.
locals {
  lambda_package_directory = "${path.root}/../../lambda/dist"

  lambda_packages = {
    online_write_flag = "${local.lambda_package_directory}/online-write-flag.zip"
    database_admin    = "${local.lambda_package_directory}/database-admin.zip"
    dataset_retention = "${local.lambda_package_directory}/dataset-generation-retention.zip"
  }
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

  # WHY : ⚠️ Assumptions: this closes the failure path of the batch bracket. The two
  #       EventBridge RULES module.step_functions declares -- the daily finalizer and
  #       the bracket reconciler, both named `<prefix>-<environment>-batch-*` -- deliver
  #       to a dead-letter queue this root encrypts with module.kms.sqs_key_arn below.
  #       An EventBridge Rule calls KMS as `events.amazonaws.com`, which the key's
  #       scheduler grant does not cover, so without this input every event those rules
  #       could not deliver was ALSO undeliverable: the bracket-release failure was
  #       dropped with a KMS access denial and the online read-only flag stayed set with
  #       nothing recording why.
  # WHY : Assumptions: a PATTERN is passed rather than the two exact rule ARNs, and the
  #       reason is a module cycle rather than convenience. Those rules are created by
  #       module.step_functions, which consumes this key's ARN; feeding their ARNs back
  #       into module.kms would make each module wait for the other. The pattern is
  #       composed from the same name_prefix and environment those rules are named from,
  #       so it is resolvable before either module is planned and still confines the
  #       grant to this deployment's rules -- the kms module additionally requires the
  #       calling account and kms:ViaService for this region's queue service, and its
  #       validation refuses a pattern with a wildcard account or region.
  # WHY : Alternatives Considered: leaving that one queue on SQS-managed encryption so
  #       no grant were needed. Rejected: it is the queue that records which night's
  #       bracket release was lost, and AAP §0.4.1.9 places every queue in this stack
  #       under a customer-managed key, so the single exception would be the one store
  #       whose access an operator could not reason about beside the others.
  sqs_key_eventbridge_rule_source_arn_patterns = [
    "arn:${data.aws_partition.current.partition}:events:${data.aws_region.current.region}:${data.aws_caller_identity.current.account_id}:rule/${var.name_prefix}-${var.environment}-batch-*",
  ]

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

# WHY : Assumptions: this role is assumed from OUTSIDE the account by a workflow,
#       which makes the boundary matter more here than on an in-account role and not
#       less. Its inline document is scoped to the SPA bucket and the distribution,
#       but the boundary is what holds if that document is ever widened by an edit
#       that looks local -- the trust policy already lets a repository outside this
#       account assume it.
resource "aws_iam_role" "spa_publication" {
  name                 = "${var.name_prefix}-${var.environment}-spa-publication"
  description          = "OIDC-assumed GitHub Actions role that publishes only the ${var.environment} SPA bundle."
  assume_role_policy   = data.aws_iam_policy_document.spa_publication_assume_role.json
  max_session_duration = 3600

  # WHY : ⚠️ Refactoring Rationale: this role carried no boundary while
  #       var.permissions_boundary_arn was described as applying to "every role this
  #       deployment creates". Of all ten roles that description over-claimed, this is
  #       the one where the gap mattered most: it is assumed from OUTSIDE the account by
  #       a GitHub Actions workflow through OIDC, so its ceiling is the only thing
  #       standing between a compromised or mis-edited workflow and the rest of the
  #       account. The boundary is evaluated in addition to its inline policy, so a
  #       statement the boundary does not permit is denied even if that policy allows it.
  # WHY : Assumptions: the short session duration and the subject-scoped trust policy are
  #       not substitutes for it. Both bound WHO may assume the role and for how long;
  #       neither bounds what the role may DO once assumed.
  permissions_boundary = var.permissions_boundary_arn
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
    # WHY : Assumptions: kms:ViaService confines this key to use made THROUGH S3 in this
    #       Region, so a principal that reached this role cannot call Decrypt directly on
    #       ciphertext of its own choosing -- which is the capability an unconditioned
    #       grant on a shared key hands out.
    # WHY : Alternatives Considered: additionally conditioning on
    #       kms:EncryptionContext:aws:s3:arn to name the bucket. Rejected here, and the
    #       reason is specific rather than general: with bucket keys enabled -- they are,
    #       at infra/modules/s3-datasets/main.tf and infra/modules/cloudfront-spa/main.tf
    #       -- S3 sets that context to the BUCKET ARN, but it sets it to the OBJECT ARN
    #       when they are not, so a StringEquals here would turn a storage-configuration
    #       change into a runtime access denial on a path that only fails when it is
    #       used. The narrowing it would express is already enforced anyway: the object
    #       statements in this same document name the exact ARNs and prefixes, and a KMS
    #       grant alone reaches no object without them.
    condition {
      test     = "StringEquals"
      variable = "kms:ViaService"
      values   = ["s3.${data.aws_region.current.region}.amazonaws.com"]
    }

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

  name_prefix        = var.name_prefix
  environment        = var.environment
  vpc_cidr           = var.vpc_cidr
  app_container_port = 8080
  database_port      = 5432

  # WHY : Assumptions: flow-log retention is one of the five values AAP §0.4.1.6
  #       permits dev and prod to differ by, so it is driven from a root variable
  #       rather than fixed in the module. It reads the same var.log_retention_days
  #       every other log group in this root reads, because a VPC flow log outliving
  #       -- or expiring before -- the application logs it is correlated against
  #       makes an incident reconstructible from only one half of the evidence.
  flow_log_retention_days = var.log_retention_days

  # WHY : Assumptions: infra/modules/network declares this input nullable with a null
  #       default so that customer-managed encryption is AVAILABLE without the module
  #       taking a dependency on the sibling kms module. That keeps the module reusable
  #       in a root that has no key module; it also means the join has to happen
  #       somewhere, and an environment root composing both is the only place that can
  #       see both. This is that join, and it is deliberate rather than incidental.
  # WHY : Trade-offs: supplying a key means the flow-log group is encrypted under a
  #       CardDemo-owned CMK instead of the AWS-owned key CloudWatch would use by
  #       default, at the cost of one more grant the log-delivery principal must hold
  #       (infra/modules/kms carries it as cloudwatch_log_delivery_source_arns). The
  #       grant is what makes the encryption real; leaving the input null would have
  #       encrypted nothing and reported success. The baseline is the reason the cost
  #       is accepted: every one of the eight CICS file resources in
  #       app/csd/CARDDEMO.CSD is defined RECOVERY(NONE) with JOURNAL(NO) -- eight
  #       occurrences of each -- so the mainframe kept neither encryption nor a
  #       journal, and the four CMKs are net-new protection this migration adds rather
  #       than a mainframe property being ported across.
  flow_log_kms_key_arn = module.kms.s3_key_arn

  # WHY : Assumptions: the SAME account boundary goes to every module that creates a
  #       role, so "every role this deployment creates" means one ceiling rather than a
  #       per-module one. infra/modules/ecs-service has always received it; network,
  #       step_functions and eventbridge_scheduler did not, which is why their roles were
  #       unbounded while this root's variable description claimed otherwise.
  permissions_boundary_arn = var.permissions_boundary_arn
}

# -----------------------------------------------------------------------------
# Container image registry.
# -----------------------------------------------------------------------------
#
# WHY : Assumptions: var.repository_names is deliberately NOT passed. The image
#       inventory is topology rather than sizing, and AAP §0.4.1.6 confines dev/prod
#       differences to sizing and retention, so the inventory lives once in
#       infra/modules/ecr/variables.tf where both roots inherit the same list. That
#       module does not merely default the set, it ASSERTS it with a validation, so
#       overriding it here with a hand-written list is the one way to turn a
#       shared contract back into two copies that can drift.
# WHY : Assumptions: the repository count is TEN, which is what specification
#       section 0.4.1.6 states, and every one of the ten is built from this
#       repository -- the eight Spring Boot services plus the browser SPA and the ETL
#       image.
# WHY : Refactoring Rationale: this note read ELEVEN and argued for an eleventh
#       repository, `aws-otel-collector`, mirroring a pinned third-party image that
#       this repository does not build. The argument was that
#       infra/modules/ecs-service attached a telemetry sidecar to every workload
#       while infra/modules/network enumerates the application tier's egress instead
#       of allowing 0.0.0.0/0, and the public registry the collector ships from has
#       neither an interface endpoint nor a managed prefix list, so without a mirror
#       no task could pull its sidecar. That reasoning was sound about the mirror and
#       wrong about the sidecar: neither the collector nor an eleventh repository
#       appears in the specification, so the cause is removed rather than the
#       consequence defended. ecs-service no longer composes the sidecar and records
#       what is kept for the observability concern in its place.
# WHY : Assumptions: `services/` holds NINE Maven modules but only EIGHT of them are
#       images. `common-lib` is a library the eight services compile against, not a
#       deployable, so it has no repository here. Naming it would create an eleventh
#       repository that .github/workflows/deploy.yml never pushes to, and an empty
#       repository is not a visible failure -- it is a scan target that never
#       reports, a lifecycle policy that never expires anything, and a line in the
#       registry a later reader has to disprove.
module "ecr" {
  source = "../../modules/ecr"

  name_prefix = var.name_prefix
  environment = var.environment
  kms_key_arn = module.kms.s3_key_arn

  # WHY : Assumptions: force_delete tracks deletion protection rather than being set
  #       independently, so `terraform destroy` satisfies the AAP §0.9.1 teardown
  #       criterion in an environment that has opted out of protection. A repository
  #       still holding images refuses deletion, which would leave a destroy
  #       half-complete and a state file describing resources that no longer match
  #       the account. In prod the same expression resolves the other way and the
  #       registry is retained.
  force_delete = !var.deletion_protection
}

module "aurora" {
  source = "../../modules/aurora-postgresql"

  name_prefix = var.name_prefix
  environment = var.environment

  # WHY : Assumptions: the two names are DELIBERATELY different and this line is an
  #       interface bridge, not a typo awaiting correction. infra/modules/network
  #       publishes `isolated_data_subnet_ids` because it names three tiers and has to
  #       say which one; infra/modules/aurora-postgresql accepts `isolated_subnet_ids`
  #       because a database module has only one tier to be placed in and qualifying it
  #       would say nothing. A reader who "fixes" either side to match the other breaks
  #       the wiring at plan time, so the mismatch is recorded here rather than left to
  #       look like an oversight.
  # WHY : Assumptions: these subnets carry NO route to the internet at all -- that is
  #       the property that makes them the database tier rather than merely a third set
  #       of private subnets, and it is why the cluster is placed here instead of in
  #       private_app_subnet_ids alongside the tasks.
  isolated_subnet_ids = module.network.isolated_data_subnet_ids

  # WHY : Assumptions: a singular producer feeding a plural consumer, so the value is
  #       wrapped in a list to reconcile the arity. infra/modules/network publishes
  #       exactly one `data_security_group_id`, while the database module accepts
  #       `security_group_ids` as a list so that a caller with a second group -- a
  #       bastion or an analytics client -- can attach it without the module changing.
  #       This root has no such caller and attaches exactly one group, so the list has
  #       one element by design and not by omission.
  # WHY : Alternatives Considered: publishing a list from the network module so no wrap
  #       were needed. Rejected because that module creates one data security group and
  #       a list-typed output would invite a consumer to assume it may contain several,
  #       moving the arity question from this visible call site into every reader of
  #       that output.
  security_group_ids = [module.network.data_security_group_id]

  kms_key_arn            = module.kms.aurora_key_arn
  secrets_kms_key_arn    = module.kms.secrets_key_arn
  engine_version         = var.aurora_engine_version
  parameter_group_family = var.aurora_parameter_group_family
  port                   = module.network.database_port

  # WHY : Assumptions: these three values are passed and NOT re-checked here.
  #       infra/modules/aurora-postgresql owns the capacity invariant -- capacity within
  #       0-256 in half-unit increments, auto-pause within 300-86400 seconds, and a zero
  #       minimum making auto-pause mandatory while forcing the maximum to at least one
  #       -- as `validation` blocks with a `lifecycle` precondition behind them.
  #       Repeating any part of that rule in this root would create a second place to
  #       maintain one invariant, and the two copies drift silently because only the
  #       stricter of them ever fires.
  # WHY : Trade-offs: dev sets the minimum to zero so the cluster scales to nothing
  #       between runs, accepting a resume latency on the first query after a pause.
  #       That is acceptable in an environment whose load is a test run and is why prod
  #       passes a non-zero floor through the same three inputs -- the permitted
  #       dev/prod difference of AAP §0.4.1.6, expressed as values rather than as
  #       different wiring.
  min_capacity             = var.aurora_min_capacity
  max_capacity             = var.aurora_max_capacity
  seconds_until_auto_pause = var.aurora_seconds_until_auto_pause

  backup_retention_period = var.aurora_backup_retention_period

  # WHY : Assumptions: the backup window and the nightly batch chain's START MUST NOT
  #       overlap, and the coupling is invisible unless stated because the two values are
  #       set in different module calls -- this one and module.eventbridge_scheduler
  #       below. As configured in infra/envs/dev/terraform.tfvars the chain is triggered
  #       by `cron(0 2 * * ? *)` and this window is "07:00-08:00", both interpreted in UTC
  #       (the scheduler module's timezone default). It is no longer left to a reader to
  #       re-derive that: terraform_data.batch_window_disjoint below asserts it at plan
  #       time, which is what makes moving either value fail loudly.
  # WHY : ⚠️ Refactoring Rationale: this block used to conclude that the two are "disjoint
  #       by five hours", and that was true only of the two START instants -- which is not
  #       the claim the sentence appeared to make. The chain's own ceiling,
  #       var.state_machine_timeout_seconds, defaults to 61200 seconds; an execution that
  #       begins at 02:00 and uses its full budget therefore runs until 19:00 and CONTAINS
  #       this window rather than avoiding it. The five-hour figure is retained because
  #       the start separation is the property actually being engineered, but it is now
  #       stated as such, because a reader who took "disjoint" to cover the whole run
  #       would conclude a backup can never coincide with posting, which is false.
  # WHY : Assumptions: an overlap between a LONG run and the backup window is tolerated
  #       rather than prevented, and the reason it is tolerable is the trade-off recorded
  #       immediately below: the snapshot stays transactionally consistent, so nothing
  #       fails. What is genuinely prevented is a chain STARTING inside the window, which
  #       is the case that would put a fresh fan-out of eleven loader tasks against the
  #       cluster at the moment the backup begins -- and in this environment the capacity
  #       ceiling is deliberately low, so that contention is felt.
  # WHY : Trade-offs: a backup taken while the posting chain holds its write window
  #       would snapshot the ledger mid-chain. The snapshot would still be
  #       transactionally consistent, so nothing would fail and no alarm would fire --
  #       the cost is that restoring it lands the estate between posting steps, which is
  #       a state the baseline's nightly cycle never produced and none of the golden
  #       masters describe. Either value may move; they may not be moved onto each
  #       other.
  preferred_backup_window      = var.aurora_preferred_backup_window
  preferred_maintenance_window = var.aurora_preferred_maintenance_window

  deletion_protection  = var.deletion_protection
  skip_final_snapshot  = var.skip_final_snapshot
  enable_http_endpoint = true
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
# WHY : Refactoring Rationale: a generated messaging tokeniser secret was declared
#       here -- an ephemeral random_password, an aws_secretsmanager_secret named
#       messaging_hmac and its write-only version -- and injected into the
#       authorization task as CARDDEMO_MESSAGING_HMAC_KEY. All of it is withdrawn
#       together, because the component that read the property is deleted: the name
#       is now read by no image, and the sibling task-definition module retired its
#       own clause admitting it.
# WHY : Assumptions: the whole family goes rather than just the injection. A secret
#       provisioned and never read is a live credential with no reader and no
#       rotation owner, which is worse than either having it wired or not having it
#       at all -- it costs money, it appears in a secret census as though something
#       depended on it, and the first reader to need key material would reasonably
#       assume it was already serving someone. The purpose-secret census in this
#       root's README moves with it.
# WHY : Trade-offs: reintroducing a tokeniser later means a new secret and a new
#       recovery window rather than reusing this one. Accepted: the value was never
#       consumed, so nothing is derived from it that a later key would have to match.


resource "aws_secretsmanager_secret" "card_selector" {
  #checkov:skip=CKV2_AWS_57:A selector minted under one key cannot be opened under another, and services/card-service/src/main/resources/application.yml records why that matters here: a selector is a card row's stable address and must keep opening for as long as a client might hold one, unlike a pagination cursor, which names a position in one browse and is meant to expire. An unattended rotation function would therefore invalidate every selector already issued, and each single-card route a client reached from a list it still has on screen would stop resolving. Rotation is an attended procedure that reissues selectors with the deployment, documented in docs/runbooks/deploy.md, rather than an automatic one. The five sibling key secrets in this root -- the messaging HMAC key, the two per-caller internal-identity signing keys, the pagination cursor key and the reporting-artifact key -- each carry the same exception for the same class of reason, every one naming the runbook section that performs its own rotation. This sentence said TWO siblings and then FOUR; both were short, the first because the single internal-identity key was later split per caller and the second because the reporting-artifact key was added after it, so the siblings are now enumerated rather than counted.
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
# Why it is a SEPARATE key rather than one shared with another purpose:
#   Assumptions: every key secret in this root is purpose-scoped, and this one is
#   the only one deliberately shared with a second service -- its minter signs and
#   the account service verifies, so both must hold the same bytes, which is a
#   property of a symmetric signing key rather than a relaxation. The card selector
#   key above is held by one service and seals a row address; sharing one value
#   across the two would mean a holder of either capability could exercise the
#   other, and rotating the account context's trust anchor would invalidate every
#   card selector a client still holds.
#   Refactoring Rationale: this heading read "a SECOND key rather than the messaging
#   key above" and its body compared this key with a messaging HMAC key that derived
#   queue-metadata tokens. That key is withdrawn -- nothing injected the bean it fed
#   once the queue identities became the literal values the specification freezes --
#   so the comparison is restated against the key that is actually above this one.
#   The heading is now purpose-based rather than ordinal, because an ordinal counted
#   from a list that changes is what made this sentence wrong when the list changed.

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
  #       reason as every generated key in this root -- a managed resource retains its
  #       result in every state file and every plan artifact, which for a signing key
  #       would make the state file as sensitive as the secret store.
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
  description = "Symmetric signing key for the AUTHORIZATION service's internal machine-to-machine bearer tokens in the ${var.environment} environment: minted by that service under the subject carddemo-authorization-service, verified by the account service against this key alone. Generated by this root and injected into exactly those two tasks as a scalar secret. Distinct from the transaction service's own internal-identity key and from the data-migration masking key."

  kms_key_id              = module.kms.secrets_key_arn
  recovery_window_in_days = var.secret_recovery_window_in_days
}

resource "aws_secretsmanager_secret" "internal_identity_transaction" {
  #checkov:skip=CKV2_AWS_57:Rotating this key requires BOTH the transaction service and the account service to adopt the new value in the same instant, because one signs with it and the other verifies against it. An unattended rotation function would change the stored value while one of the two tasks still held the old one, and every internal account-context read from the transaction service would be refused with a 401 for the duration -- which fails every transaction add and every bill payment. Rotation is therefore an attended procedure that redeploys both services together, documented in docs/runbooks/deploy.md, rather than an automatic one.
  name        = "${var.name_prefix}/${var.environment}/internal-identity/transaction-signing-key"
  description = "Symmetric signing key for the TRANSACTION service's internal machine-to-machine bearer tokens in the ${var.environment} environment: minted by that service under the subject carddemo-transaction-service, verified by the account service against this key alone. Generated by this root and injected into exactly those two tasks as a scalar secret. Distinct from the authorization service's own internal-identity key and from the data-migration masking key."

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

  # WHY : Trade-offs: pinned to the literal 1, matching infra/modules/secrets and every
  #       generated key in this root. Here an unintended rewrite is worse than for a
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
# Why this is a purpose-scoped key of its own rather than a reuse of another:
#   Assumptions: purpose-scoping is the control, not key economy. This root
#   generates SIX key secrets and the holder set of each is stated once here, so a
#   reader can check the gates below against a written inventory rather than
#   reconstructing one:
#     - the messaging HMAC key, held by the authorization consumer alone;
#     - the card-selector key, held by card-service alone;
#     - the internal-identity key for the AUTHORIZATION caller, held by exactly two
#       services -- authorization-service as its minter and account-service as the
#       verifier;
#     - the internal-identity key for the TRANSACTION caller, held by exactly two
#       on the same terms;
#     - this pagination-cursor key, held by SEVEN services -- auth, account, card,
#       transaction, reference, reporting and authorization, which is every service
#       in the distribution gate below except batch and data-migration;
#     - the reporting-artifact key, held by reporting-service alone.
#   Assumptions: the internal-identity keys are TWO and not one, one per calling
#   caller, and the split is what makes a caller's subject verifiable rather than
#   merely asserted -- a single shared value would let either caller mint a token
#   the verifier attributes to the other.
#   Assumptions: the cursor holder set is stated as the set that BINDS the property
#   rather than as a property of what a service publishes. Characterising it as
#   "the services that publish a paged list" is what made an earlier count of four
#   look plausible: auth-service and card-service both hold the key, and
#   common-lib withholds the CursorToken bean when the property is unset, so a
#   holder omitted from the gate fails context refresh instead of degrading.
#   Sharing one value across purposes would mean a holder of any one capability
#   could exercise the others -- a service able to seal a cursor could mint an
#   internal bearer token -- and rotating any one purpose would invalidate all six
#   at the same moment.
#   Trade-offs: six secrets cost six entries to provision and six attended
#   rotations rather than one. That is accepted because the failure a shared key
#   admits is a privilege escalation across contexts, while the cost of separate
#   keys is only operational. Every one of the six carries a recorded
#   CKV2_AWS_57 exception naming the runbook section that performs its rotation.
#   Refactoring Rationale: this block previously carried three counts that
#   contradicted each other and the code beside them on the same page -- it called
#   this "a THIRD key", said there were "five key secrets", said "the
#   internal-identity key" was "held by exactly three services", and named
#   auth-service and card-service as deliberately outside the cursor set while the
#   gate below admits both. Each was stale from a different revision, and a count
#   that matches neither the resource list above it nor the gate below it is worse
#   than none because it reads as corroboration. The inventory is now enumerated
#   rather than counted, so a key added later cannot leave a bare number behind to
#   go stale, and the six ephemeral blocks and six secret resources in this file are
#   what the list is checkable against.

ephemeral "random_password" "pagination_cursor" {
  # WHY : Assumptions: EPHEMERAL rather than a managed random_password, for the same
  #       reason as the four keys declared above it -- the messaging HMAC key, the
  #       card-selector key and the two per-caller internal-identity keys -- every one
  #       of which is also ephemeral. A
  #       managed resource retains its result in
  #       every state file and every plan artifact, which for a signing key would make
  #       the state file as sensitive as the secret store it exists to keep the
  #       material out of.
  # WHY : Assumptions: 48 characters, and this length is arithmetic rather than taste.
  #       This key is the only one in this root whose consumer requires STRICT base64 --
  #       CardDemoCommonAutoConfiguration.decodeSigningKey uses a strict decoder and
  #       raises naming the property when the value is not base64, with NO raw-text
  #       fallback of the kind the card selector and internal-identity keys rely on.
  #       Refactoring Rationale: this said "the ONE of the three", counting a set that
  #       included a withdrawn messaging key and excluded the reporting artifact key,
  #       whose consumer decodes base64 FIRST and falls back to raw text -- so it is
  #       stored encoded too and the old phrasing implied otherwise. The distinction
  #       that matters is strict-versus-fallback decoding rather than a count. The
  #       value stored below is therefore base64encode() of these characters, and 48
  #       characters encode to exactly 64 base64 characters that decode back to 48
  #       bytes -- comfortably above the 32-byte floor CursorToken.MIN_KEY_LENGTH
  #       imposes, with no padding ambiguity because 48 is divisible by three.
  #       Alternatives Considered: generating 64 characters and storing them directly,
  #       as the three keys above all do -- each generates length 64 with special = false
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
  description = "Purpose-scoped symmetric key the CardDemo services seal and open keyset pagination cursors with in the ${var.environment} environment, read by com.carddemo.common.web.CursorToken. Generated by this root and injected as a scalar secret into exactly the seven services holding a component whose constructor requires the CursorToken bean. Distinct from the two per-caller internal-identity signing keys, the card selector key and the data-migration masking key."

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
  #       is consumed through a STRICT base64 decoder. It is ONE OF TWO of the six that
  #       is base64-encoded at rest -- this cursor key and the reporting artifact HMAC
  #       key below -- while the other four accept raw text, which is why each of those
  #       four stores 48 fewer decisions than this one does.
  #       Refactoring Rationale: this claimed to be "the ONLY one of the five", and it
  #       was wrong twice over: the reporting artifact key is base64-encoded through the
  #       identical expression, and there are six generated secrets rather than five. A
  #       uniqueness claim is the most costly kind to get wrong here, because a reader
  #       adding a seventh key would have taken it as evidence that raw text is the norm
  #       and strict decoding the exception. Storing the characters unencoded would make the stored value
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
# Why this is a purpose-scoped key of its own rather than a reuse of one above:
#   Assumptions: purpose-scoping is the control, not key economy. This is the
#   SIXTH and last generated key secret in this root, and the full inventory with
#   each holder set is enumerated once above the pagination-cursor key rather than
#   restated here: this one is held by reporting-service alone, the cursor key by
#   seven services, each of the TWO per-caller internal-identity keys by exactly
#   two, and the messaging and card-selector keys by one each.
#   infra/modules/ecs-service asserts each holder set biconditionally, so a shared
#   value would additionally have to be handed to workloads those gates refuse.
#   Sharing one value would also mean a holder of any single capability could
#   exercise the others, and rotating one purpose would invalidate all six at once
#   -- which here means every statement object already written becomes unlocatable
#   by the application's own lookup.
#   Refactoring Rationale: this paragraph called it "a FOURTH generated key", said
#   the internal-identity key was held "by exactly three services" and that
#   rotation would invalidate "all four at once". All three were stale: the single
#   internal-identity key was split per caller into two, and two further keys have
#   been added since. It now points at the one enumerated inventory instead of
#   carrying a second count that can go stale independently.
#   Trade-offs: a sixth secret costs a sixth entry to provision and a sixth
#   attended rotation. That is accepted because the failure a shared key admits
#   is a privilege escalation across contexts, while the cost is only
#   operational.

ephemeral "random_password" "reporting_artifact" {
  # WHY : Assumptions: EPHEMERAL rather than a managed random_password, for the same
  #       reason as the five keys declared above it -- a managed resource retains its result in
  #       every state file and every plan artifact, which for key material would make
  #       the state file as sensitive as the secret store it exists to keep the
  #       material out of.
  # WHY : Assumptions: 48 characters, encoded below, and the arithmetic is deliberate
  #       rather than copied. config/ArtifactIdentityConfig.java decodes base64 FIRST
  #       and uses the result only when it reaches OpaqueIdentifier's 32-byte floor,
  #       falling back to the raw text otherwise. A 64-character alphanumeric value --
  #       the shape the three raw-text keys above use -- would therefore take the base64
  #       branch by coincidence of the alphabet rather than by intent, and the branch it takes
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
  #checkov:skip=CKV2_AWS_57:A token minted under one key cannot be recomputed under another, and the artifact token is how the application locates a statement object it wrote on an earlier night. An unattended rotation function would therefore orphan every statement already published -- the objects remain, correctly encrypted, and nothing can name them again short of a full listing -- and it would do so at an arbitrary moment with no failure to observe. Rotation is an attended procedure timed outside the statement window and documented in docs/runbooks/deploy.md, performed together with a re-publication of the affected generations. The five sibling generated secrets in this root -- the messaging HMAC key, the card-selector key, the two per-caller internal-identity signing keys and the pagination cursor key -- carry the same exception for the same class of reason.
  name        = "${var.name_prefix}/${var.environment}/reporting/artifact-hmac-key"
  description = "Purpose-scoped key the CardDemo reporting service derives stored statement artifact object-key tokens under, in the ${var.environment} environment, read by com.carddemo.common.security.OpaqueIdentifier. Generated by this root and injected as a scalar secret into the reporting task alone. Distinct from the pagination cursor key, the two per-caller internal-identity signing keys, the card selector key and the data-migration masking key."

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

  # WHY : Trade-offs: pinned to the literal 1, matching infra/modules/secrets and every
  #       generated key above. It is what stops an unrelated plan rewriting the stored key
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

# WHY : Assumptions: this parameter exists so no service hard-codes the write gate, and
#       like every other parameter this root publishes (AAP §0.5.3.5) it carries a
#       runtime control value only -- never a credential, which is what Secrets Manager
#       holds. It is the cloud analogue of the SDSF operator quiesce in
#       app/jcl/CLOSEFIL.jcl and app/jcl/OPENFIL.jcl: the mainframe closed the CICS
#       files so the batch window owned the masters, and here the online services read
#       this flag and refuse writes while the nightly chain holds the bracket.
# WHY : Assumptions: `type = "String"` because a boolean gate every online task must
#       read is not secret. The reader set is narrow and named rather than estate-wide:
#       SEVEN task roles hold ssm:GetParameter on THIS ONE ARN, because
#       infra/modules/ecs-service composes that statement itself from
#       online_write_gate_parameter_arn and this root passes the ARN for exactly the
#       seven entries of local.workloads that carry online = true -- batch and
#       data-migration receive null and get no grant at all. The quiesce/resume Lambda
#       role adds GetParameter and PutParameter on the same single ARN, and the ETL task
#       role reaches this parameter only incidentally, through the one prefix-wide read
#       in this root: the ReadRuntimeParameters statement of data_migration_runtime.
# WHY : Refactoring Rationale: this sentence said "eight task roles ... over this
#       prefix" and was wrong twice over. There are seven online workloads, not eight --
#       local.workloads merges local.online_services with batch and data-migration, and
#       only the first group is online -- and the grant names one ARN rather than a
#       prefix, so describing it as a prefix read overstated by the whole namespace what
#       an online task can actually fetch. Both halves matter for a type argument
#       justified by how widely the value is legible.
# WHY : Trade-offs: encrypting a value whose two possible states are already inferable
#       from whether writes are being accepted -- and which the batch state machine's
#       own execution history discloses -- would imply a confidentiality it does not
#       have, and would oblige every one of those seven roles to carry a kms:Decrypt
#       grant for the gate alone.
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

  # WHY : ⚠️ Refactoring Rationale: all THREE roles this resource creates carried no
  #       boundary while var.permissions_boundary_arn was described as applying to
  #       "every role this deployment creates". They are attached now, which is what
  #       makes that description a fact rather than an intention.
  # WHY : Assumptions: these three are not interchangeable and the boundary matters for a
  #       different reason in each. `online_write` writes the read-only flag and the
  #       quiesce lease, so it can open or close the online write path. `database_admin`
  #       reaches Aurora with credentials from Secrets Manager. `dataset_retention`
  #       deletes object generations. A boundary is the one control that caps all three
  #       from above without having to re-review each inline document after every edit.
  # WHY : Assumptions: one boundary for all three rather than a per-function input, for
  #       the same reason the state-machine roles share one -- a boundary is an
  #       account-level ceiling, and the narrowing BETWEEN these three is already done by
  #       their separate inline policies. A per-function axis would let one be given a
  #       wider ceiling than its siblings by accident.
  permissions_boundary = var.permissions_boundary_arn
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
    # WHY : Assumptions: BOTH conditions are applied. kms:ViaService confines the key to
    #       use made through Secrets Manager in this Region, so the grant cannot be spent
    #       on ciphertext this principal supplied itself; the encryption-context condition
    #       confines it to the exact secrets this document already authorises, because
    #       Secrets Manager sets SecretARN to the secret being read or written on every
    #       request. The key here is the AURORA key, which protects the RDS-managed master secret and nothing else, so the context names that one secret.
    # WHY : Trade-offs: StringLike rather than StringEquals on the context, matching the
    #       reasoning recorded in infra/modules/ecs-service/main.tf -- Secrets Manager
    #       appends a six-character suffix to the ARN it puts in the context when the
    #       caller supplied a name-only ARN, and each pattern still names one secret.
    condition {
      test     = "StringEquals"
      variable = "kms:ViaService"
      values   = ["secretsmanager.${data.aws_region.current.region}.amazonaws.com"]
    }

    condition {
      test     = "StringLike"
      variable = "kms:EncryptionContext:SecretARN"
      values   = [module.aurora.master_user_secret_arn]
    }

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
    # WHY : Assumptions: BOTH conditions are applied. kms:ViaService confines the key to
    #       use made through Secrets Manager in this Region, so the grant cannot be spent
    #       on ciphertext this principal supplied itself; the encryption-context condition
    #       confines it to the exact secrets this document already authorises, because
    #       Secrets Manager sets SecretARN to the secret being read or written on every
    #       request. The set is the same one the GetSecretValue statement above authorises, so the two cannot diverge into a key grant wider than the secret grant.
    # WHY : Trade-offs: StringLike rather than StringEquals on the context, matching the
    #       reasoning recorded in infra/modules/ecs-service/main.tf -- Secrets Manager
    #       appends a six-character suffix to the ARN it puts in the context when the
    #       caller supplied a name-only ARN, and each pattern still names one secret.
    condition {
      test     = "StringEquals"
      variable = "kms:ViaService"
      values   = ["secretsmanager.${data.aws_region.current.region}.amazonaws.com"]
    }

    condition {
      test     = "StringLike"
      variable = "kms:EncryptionContext:SecretARN"
      values   = [for secret in values(module.secrets.service_credential_secrets) : secret.arn]
    }

  }
}

data "aws_iam_policy_document" "dataset_retention_lambda" {
  source_policy_documents = [data.aws_iam_policy_document.lambda_logs["dataset_retention"].json]

  # WHY : Assumptions: these two statements exist because of the failure destination
  #       configured in aws_lambda_function_event_invoke_config.dataset_retention. Lambda
  #       delivers an exhausted asynchronous event to that destination using THIS
  #       function's execution role, so without the grant the destination is configured
  #       and silently non-functional -- the delivery fails and the event is dropped
  #       exactly as it would have been with no destination at all, which is the failure
  #       this whole arrangement exists to end.
  statement {
    sid       = "RecordExhaustedRetentionEvents"
    actions   = ["sqs:SendMessage"]
    resources = [module.sqs.error_queue_arn]
  }

  # WHY : Assumptions: the error queue is encrypted with the CardDemo SQS customer
  #       managed key, and SendMessage to an SSE-KMS queue needs GenerateDataKey on that
  #       key -- a send-only producer needs Decrypt too, because SQS decrypts the queue's
  #       existing data key to attach the message. Granting SendMessage alone is the exact
  #       asymmetry that left the batch role unable to reach this same queue.
  # WHY : Trade-offs: both conditions are applied. kms:ViaService confines the key to use
  #       made THROUGH SQS in this Region, so the grant cannot be spent on ciphertext this
  #       function supplied itself, and the encryption-context condition confines it to the
  #       one queue this document already authorises -- SQS sets that context to the queue
  #       ARN on every request, so the key grant cannot be wider than the queue grant.
  statement {
    sid = "UseErrorQueueEncryptionKey"

    actions = [
      "kms:GenerateDataKey",
      "kms:Decrypt",
    ]

    resources = [module.kms.sqs_key_arn]

    condition {
      test     = "StringEquals"
      variable = "kms:ViaService"
      values   = ["sqs.${data.aws_region.current.region}.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "kms:EncryptionContext:aws:sqs:arn"
      values   = [module.sqs.error_queue_arn]
    }
  }
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

# WHY : Assumptions: this is a SEPARATE inline policy attached to all three Lambda roles
#       rather than a statement folded into each of their three policy documents. One
#       declaration cannot drift from itself, whereas the same statement written three
#       times can be corrected in two places and missed in the third -- and a missing
#       X-Ray grant does not fail an invocation, it silently produces an untraced one,
#       which is the failure mode hardest to notice.
# WHY : Trade-offs: the resource is `*`, which is the one wildcard in this root that is
#       not a narrowing failure. The X-Ray segment-ingestion actions do not support
#       resource-level permissions -- there is no ARN for a segment, because the segment
#       does not exist until the call that submits it -- so `*` is the only form the
#       service accepts, and naming a resource here would produce a policy that denies
#       every write. The narrowing that IS available is applied instead: exactly two
#       actions, both write-only, on roles that hold no X-Ray read permission at all, so
#       a compromised function can contribute trace data and cannot read anyone's.
# WHY : Alternatives Considered: the AWSXRayDaemonWriteAccess managed policy, which is
#       what most examples attach. Rejected because it additionally grants
#       xray:GetSamplingRules, xray:GetSamplingTargets and
#       xray:GetSamplingStatisticSummaries, none of which these functions use -- they
#       sample through the Lambda service, not through the SDK's sampler -- so the
#       managed policy would widen the grant for no capability.
# WHY : Assumptions: this attachment is deliberately NOT in any function's depends_on.
#       The functions depend on aws_iam_role_policy.lambda, and adding an edge from a
#       function to a second policy is unnecessary here for the same self-healing reason
#       recorded for online_write_reconcile: an invocation landing before the grant does
#       loses one function segment, and the state machine's own trace still records the
#       step. Trace data is not transactional.
data "aws_iam_policy_document" "lambda_xray" {
  statement {
    sid = "WriteTraceSegments"

    actions = [
      "xray:PutTraceSegments",
      "xray:PutTelemetryRecords",
    ]

    resources = ["*"]
  }
}

resource "aws_iam_role_policy" "lambda_xray" {
  for_each = aws_iam_role.lambda

  name   = "${var.name_prefix}-${var.environment}-${each.key}-xray"
  role   = each.value.id
  policy = data.aws_iam_policy_document.lambda_xray.json
}

resource "aws_lambda_function" "quiesce" {
  function_name    = local.lambda_names.quiesce
  role             = aws_iam_role.lambda["online_write"].arn
  runtime          = "python3.13"
  handler          = "online_write_flag.handler"
  filename         = local.lambda_packages.online_write_flag
  source_code_hash = filebase64sha256(local.lambda_packages.online_write_flag)
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

  # WHY : Assumptions: PassThrough is Lambda's default, and under it these functions
  #       emit no segment of their own -- they only forward a trace header if one
  #       arrives. Step Functions IS traced, so the daily batch chain produced a trace
  #       in which every state machine transition was visible and the four glue steps
  #       that actually quiesce writes, bootstrap the database and scratch generations
  #       were blank. Active makes each invocation a sampled segment, which is what
  #       closes that gap.
  # WHY : Trade-offs: Active sampling bills per trace recorded and per trace scanned,
  #       against four functions that run a handful of times a night -- so the cost is
  #       negligible here in a way it would not be for a request-serving workload. The
  #       alternative of leaving these opaque was rejected because the batch bracket is
  #       exactly where an operator needs causality: a quiesce that silently failed and
  #       a quiesce that was never invoked are indistinguishable without a segment.
  tracing_config {
    mode = "Active"
  }

  depends_on = [aws_iam_role_policy.lambda]
}

resource "aws_lambda_function" "resume" {
  function_name    = local.lambda_names.resume
  role             = aws_iam_role.lambda["online_write"].arn
  runtime          = "python3.13"
  handler          = "online_write_flag.handler"
  filename         = local.lambda_packages.online_write_flag
  source_code_hash = filebase64sha256(local.lambda_packages.online_write_flag)
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

  # WHY : Assumptions: PassThrough is Lambda's default, and under it these functions
  #       emit no segment of their own -- they only forward a trace header if one
  #       arrives. Step Functions IS traced, so the daily batch chain produced a trace
  #       in which every state machine transition was visible and the four glue steps
  #       that actually quiesce writes, bootstrap the database and scratch generations
  #       were blank. Active makes each invocation a sampled segment, which is what
  #       closes that gap.
  # WHY : Trade-offs: Active sampling bills per trace recorded and per trace scanned,
  #       against four functions that run a handful of times a night -- so the cost is
  #       negligible here in a way it would not be for a request-serving workload. The
  #       alternative of leaving these opaque was rejected because the batch bracket is
  #       exactly where an operator needs causality: a quiesce that silently failed and
  #       a quiesce that was never invoked are indistinguishable without a segment.
  tracing_config {
    mode = "Active"
  }

  depends_on = [aws_iam_role_policy.lambda]
}

resource "aws_lambda_function" "database_admin" {
  function_name    = local.lambda_names.database_admin
  role             = aws_iam_role.lambda["database_admin"].arn
  runtime          = "python3.13"
  handler          = "database_admin.handler"
  filename         = local.lambda_packages.database_admin
  source_code_hash = filebase64sha256(local.lambda_packages.database_admin)
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

  # WHY : Assumptions: PassThrough is Lambda's default, and under it these functions
  #       emit no segment of their own -- they only forward a trace header if one
  #       arrives. Step Functions IS traced, so the daily batch chain produced a trace
  #       in which every state machine transition was visible and the four glue steps
  #       that actually quiesce writes, bootstrap the database and scratch generations
  #       were blank. Active makes each invocation a sampled segment, which is what
  #       closes that gap.
  # WHY : Trade-offs: Active sampling bills per trace recorded and per trace scanned,
  #       against four functions that run a handful of times a night -- so the cost is
  #       negligible here in a way it would not be for a request-serving workload. The
  #       alternative of leaving these opaque was rejected because the batch bracket is
  #       exactly where an operator needs causality: a quiesce that silently failed and
  #       a quiesce that was never invoked are indistinguishable without a segment.
  tracing_config {
    mode = "Active"
  }

  depends_on = [aws_iam_role_policy.lambda]
}

resource "aws_lambda_function" "dataset_retention" {
  function_name    = local.lambda_names.dataset_retention
  role             = aws_iam_role.lambda["dataset_retention"].arn
  runtime          = "python3.13"
  handler          = "dataset_generation_retention.handler"
  filename         = local.lambda_packages.dataset_retention
  source_code_hash = filebase64sha256(local.lambda_packages.dataset_retention)
  # WHY : Trade-offs: ONE concurrent execution, which is a deliberate throughput ceiling
  #       rather than a sizing oversight. This function lists a generation family's
  #       versions and deletes everything past the fifth, so two invocations for the same
  #       family running together read the same version list and each decide to delete the
  #       same objects -- the second delete is redundant at best, and at worst the two
  #       interleave with an upload and scratch a generation that had just become the
  #       fifth. Serialising removes the race outright instead of guarding it with a lock
  #       the function would have to implement itself. Assumptions: a throttled
  #       ASYNCHRONOUS invocation is not lost -- Lambda retries it, so bursts queue behind
  #       the running one rather than being dropped, which is what makes a ceiling of one
  #       safe here where it would be unacceptable for a synchronous API.
  # WHY : Alternatives Considered: buffering the notifications through a dedicated SQS
  #       queue with its own dead-letter queue and a bounded consumer. Rejected because
  #       the frozen plan fixes the queue inventory at four queues plus one error sink
  #       (AAP section 0.4.1.8), so a sixth queue would be exactly the kind of inventory
  #       divergence recorded against the eleventh ECR repository -- and reserved
  #       concurrency plus a failure destination gives the same two properties, a bounded
  #       consumer and a durable record of exhausted events, without adding one.
  reserved_concurrent_executions = 1

  timeout     = 120
  memory_size = 256

  environment {
    variables = {
      RETENTION_COUNT = "5"
    }
  }

  # WHY : Assumptions: PassThrough is Lambda's default, and under it these functions
  #       emit no segment of their own -- they only forward a trace header if one
  #       arrives. Step Functions IS traced, so the daily batch chain produced a trace
  #       in which every state machine transition was visible and the four glue steps
  #       that actually quiesce writes, bootstrap the database and scratch generations
  #       were blank. Active makes each invocation a sampled segment, which is what
  #       closes that gap.
  # WHY : Trade-offs: Active sampling bills per trace recorded and per trace scanned,
  #       against four functions that run a handful of times a night -- so the cost is
  #       negligible here in a way it would not be for a request-serving workload. The
  #       alternative of leaving these opaque was rejected because the batch bracket is
  #       exactly where an operator needs causality: a quiesce that silently failed and
  #       a quiesce that was never invoked are indistinguishable without a segment.
  tracing_config {
    mode = "Active"
  }

  depends_on = [aws_iam_role_policy.lambda]
}

# WHY : Assumptions: an S3 notification invokes a function ASYNCHRONOUSLY, and Lambda's
#       default handling of an asynchronous invocation that keeps failing is to retry
#       twice and then DISCARD the event. For a retention function that discard is
#       silent and consequential: the generations that event would have scratched simply
#       stay, so the bucket drifts past its five-generation contract with nothing
#       recording that it happened. This configuration is what converts that silence
#       into a durable record.
# WHY : Trade-offs: the failure destination is the EXISTING carddemo error queue, not a
#       new one. The frozen plan designates that queue the terminal error sink (AAP
#       section 0.4.1.8, replacing CARD.DEMO.ERROR), which is precisely this role, and
#       the same root already uses it as the EventBridge scheduler's dead-letter target
#       -- so the pattern is established rather than invented, and the queue inventory
#       the plan fixes is unchanged.
# WHY : Assumptions: maximum_event_age_in_seconds is set well below Lambda's six-hour
#       ceiling. An event older than this is retention work for a generation that has
#       since been superseded by the next nightly run, so completing it late is not
#       merely useless but potentially wrong -- and the point of the age bound is that
#       such an event lands on the error queue where it is visible, instead of being
#       retried for hours against a bucket state it no longer describes.
# WHY : Alternatives Considered: maximum_retry_attempts = 0, failing fast to the queue.
#       Rejected because the failures this function realistically sees are throttles from
#       its own concurrency ceiling of one and transient S3 list/delete errors, both of
#       which a retry clears; zero retries would route ordinary contention to the error
#       queue and train an operator to ignore it.
resource "aws_lambda_function_event_invoke_config" "dataset_retention" {
  function_name = aws_lambda_function.dataset_retention.function_name

  maximum_retry_attempts       = 2
  maximum_event_age_in_seconds = 3600

  destination_config {
    on_failure {
      destination = module.sqs.error_queue_arn
    }
  }
}

resource "aws_lambda_invocation" "database_bootstrap" {
  function_name = aws_lambda_function.database_admin.function_name
  input         = jsonencode({ action = "bootstrap" })
  triggers = {
    function_code = filebase64sha256(local.lambda_packages.database_admin)
    bootstrap_sql = filesha256("${path.root}/../../../data-migration/sql/V0__schemas_and_roles.sql")
    cluster_arn   = module.aurora.cluster_arn

    # WHY : Assumptions: a change to the ROLE INVENTORY has to re-invoke this, and none
    #       of the three triggers above notices one. Adding a bounded context changes
    #       the bootstrap SQL and so is already covered; adding or renaming a role is
    #       not, and the function is what binds a stored value to its PostgreSQL role.
    #       Hashing the mapping rather than embedding it keeps the trigger a fixed
    #       length and keeps sixteen secret names out of the plan diff.
    credential_inventory = sha256(jsonencode({
      for role, secret in module.secrets.service_credential_secrets : role => secret.name
    }))

    # WHY : ⚠️ Assumptions: the inventory hash above cannot see a re-issue of the stored
    #       VALUES, and this trigger is what does. Every secret name, ARN and role name
    #       is byte-identical across a re-issue -- what changes is the write-only version
    #       infra/modules/secrets writes each document at, which it publishes as this
    #       non-secret revision. Without this entry, incrementing that version replaces
    #       all sixteen passwords in Secrets Manager while every PostgreSQL role keeps
    #       the password ALTER ROLE last set, and the next task rollout authenticates
    #       with credentials the database does not hold. The failure surfaces as every
    #       service failing its health check at once, from an apply whose plan showed
    #       nothing but a secret version changing.
    # WHY : Alternatives Considered: hashing the secret VALUES so any change to them
    #       re-invoked the function. Rejected outright: the values are written through
    #       the write-only argument precisely so they never enter state or a plan
    #       artifact, and reading them back to hash them would defeat that control and
    #       put sixteen live database passwords in the state file.
    # WHY : Trade-offs: the revision is module-wide rather than per role, so a re-issue
    #       re-binds all sixteen credentials rather than only the one that changed.
    #       That matches how the module writes them -- one shared literal governs every
    #       document -- and the bootstrap function is idempotent per role, so the extra
    #       ALTER ROLE statements restate a password each role already has.
    credential_revision = module.secrets.service_credential_revision
  }

  # WHY : Assumptions: stated explicitly even though the trigger above already reads
  #       module.secrets, because the guarantee is about ORDER rather than about value
  #       resolution and a reader should not have to infer it from an expression. The
  #       bootstrap reads every per-role credential and its SQL refuses to commit
  #       without one, so every secret must exist and hold a generated value before the
  #       function is invoked even once.
  # WHY : Assumptions: the credentials this invocation binds are the SIXTEEN LOGIN roles
  #       data-migration/sql/V0__schemas_and_roles.sql declares, and the arithmetic is
  #       written out because "sixteen" is otherwise a number a reader has to go and
  #       count. Eight are runtime logins, one per bounded context plus reporting --
  #       carddemo_auth, _account, _card, _ledger, _reference, _batch, _authorization and
  #       _reporting. Seven are migration logins, the same contexts EXCEPT reporting,
  #       which owns no schema and so has nothing to migrate -- carddemo_auth_migrator,
  #       _account_migrator, _card_migrator, _ledger_migrator, _reference_migrator,
  #       _batch_migrator and _authorization_migrator. The sixteenth is
  #       carddemo_migration, the extract-transform-load login. The eight *_owner roles
  #       are deliberately NOT in this set: they are NOLOGIN, hold no credential, and are
  #       reached by SET ROLE rather than by authenticating, which is the property that
  #       keeps a stolen runtime password unable to alter a schema.
  # WHY : Assumptions: a FAILED rebinding stops the rollout rather than being reported
  #       and passed over. infra/lambda/database_admin.py raises on every failure it can
  #       detect -- a missing environment variable, an unterminated construct in the SQL,
  #       a role with no credential, a statement the Data API refuses -- so the function
  #       returns a Lambda function error and this resource fails the apply. Because
  #       module.ecs_service carries `depends_on = [aws_lambda_invocation.database_bootstrap]`,
  #       an apply that could not bind the credentials never reaches the services, so no
  #       task is ever started against a database whose roles hold a different password
  #       from the one the task will present.
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

  name_prefix         = var.name_prefix
  environment         = var.environment
  secrets_kms_key_arn = module.kms.secrets_key_arn

  # WHY : ⚠️ Refactoring Rationale: both URL lists are EMPTY, where this root passed
  #       ["${local.spa_origin}/callback"] and [local.spa_origin]. Supplying either one
  #       switched the module's derived `oauth_enabled` local true, which added the
  #       authorization-code flow, the openid and profile scopes and both redirect
  #       lists to the app client -- a whole authentication path that nothing in this
  #       system uses and that could not have been completed if anything tried. The
  #       code flow needs a hosted sign-in domain, and no root sets var.domain_prefix,
  #       so the pool has none; the SPA never speaks to the pool at all
  #       (ui/.env.example configures no pool coordinates), and the browser posts the
  #       credential to services/auth-service, which authenticates server-side with
  #       the client secret. The callback target itself was fiction: /callback is not
  #       a route ui/src/router.tsx declares, so an approved redirect pointed at a
  #       path the SPA answers with its not-found screen.
  #       Assumptions: empty is the module's own documented default and the
  #       configuration its app-client comment describes as expected, so this is a
  #       return to the design rather than a new restriction. Least privilege is the
  #       reason to prefer it: an enabled flow with an approved redirect target is a
  #       second way to obtain a token from this pool, and a capability nothing
  #       exercises is a capability nobody is watching.
  #       Trade-offs: a future hosted-UI or PKCE arrangement has to add a domain, the
  #       redirect targets and the browser-side flow together. That is the correct
  #       shape for that change and it is cheaper than leaving a half-built flow
  #       enabled in the meantime.
  callback_urls = []
  logout_urls   = []

  # WHY : ⚠️ Refactoring Rationale: OFF, where this root passed ON in prod and
  #       OPTIONAL elsewhere. A multi-factor posture is only half a capability: the
  #       pool raises a challenge and something has to answer it, and
  #       services/auth-service answers exactly one -- NEW_PASSWORD_REQUIRED -- and
  #       reports every other challenge as an exchange it could not evaluate, which
  #       its contract renders as HTTP 500. OPTIONAL therefore handed a 500 to every
  #       user who enrolled a software token, on every sign-in after enrolment, and ON
  #       would hand one to every user's FIRST sign-in because the pool answers
  #       MFA_SETUP there. The module now validates this input to OFF for both
  #       environments and records the precondition for raising it.
  #       Assumptions: multi-factor authentication is not a requirement of this
  #       migration -- the reference identity record carries a password and nothing
  #       else, and the Agent Action Plan's identity mapping asks for the pool, the two
  #       groups and the claim conversion -- so declining to advertise a factor the
  #       service cannot answer is the resolution rather than a deferral of one.
  mfa_configuration = "OFF"

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

# -----------------------------------------------------------------------------
# Messaging: the five IBM MQ queues, re-expressed as six.
# -----------------------------------------------------------------------------
#
# WHY : Assumptions: the queue inventory and each queue's FIFO-versus-standard nature
#       are topology and live in infra/modules/sqs, so this root passes only the key.
#       Two FIFO queues carry the pending-authorization request and reply, and three
#       standard queues carry the SHARED inquiry request, the shared inquiry reply and
#       the terminal error sink -- five primaries, each with its own dead-letter queue
#       at a redrive threshold of five receives, so ten queues in total.
# WHY : ⚠️ Refactoring Rationale: "the two inquiry flows" used to be described as two
#       request queues, one per consumer, and the module provisioned them. There is ONE
#       request queue for both flows, because the baseline defines one:
#       DEFINE QLOCAL('CARDDEMO.REQUEST.QUEUE') at app/app-vsam-mq/README.md L53,
#       aliased to CICS as MQQUEUE(CARDREQ) at L71, feeds both COACCT01 and CODATE01.
#       Two queues also could not be reconciled with the transport: a queue admits
#       exactly one OWNING consumer, since a receive hides the message from every other
#       consumer rather than delivering a copy to each, so a producer holding one
#       request address could reach only one of the two flows. account-service is that
#       one consumer and dispatches on the request's own four-character function code.
# WHY : Assumptions: FIFO here does NOT mean a single-threaded queue, which is the
#       reading a reviewer arrives at by default and the reason this is written down.
#       Ordering in a FIFO queue is per MESSAGE GROUP, and the producer sets the group
#       from the card the authorization belongs to, so two authorizations for one card
#       are delivered in the order they were sent while authorizations for different
#       cards remain free to be processed in parallel. Per-card ordering is the property
#       the baseline had by construction -- one MQ queue read sequentially by one
#       consumer -- and it is the only ordering the business rules actually depend on.
# WHY : Assumptions: the deduplication identifier is the transaction identifier, which
#       is what converts the baseline's at-least-once redelivery into exactly-once
#       ACCEPTANCE inside the deduplication window. app/app-authorization-ims-db2-mq
#       reads its queue with a no-syncpoint get, so a consumer that crashed after
#       processing and before acknowledging saw the same authorization twice and had no
#       mechanism to tell that it had; posting it twice would double-count the amount.
#       Content-based deduplication was not usable for this: two genuinely distinct
#       authorizations for the same card and amount hash identically, so the queue would
#       silently discard the second.
# WHY : ⚠️ Refactoring Rationale: this note claimed the group identifier published on the
#       wire was a keyed derivation of the card number. It is the card number itself.
#       Sections 0.4.1.8 and 0.7.6 of the technical specification freeze
#       MessageGroupId = card_num and MessageDeduplicationId = transaction_id, and the
#       producer emits those literals -- services/authorization-service
#       domain/OutboxMessage.java documents the group as the card number, and
#       config/MessagingIdentityConfig.java records why the derivation was withdrawn
#       rather than kept: a group identity orders only while it is equal for equal cards
#       across every producer, and a deduplication identity suppresses only while the
#       requester that may resend can predict it, so keying either from one service's
#       secret removes both guarantees for everyone else on the queue.
# WHY : Trade-offs: the consequence is accepted and registered rather than mitigated in
#       the identifier's shape -- a primary account number appears in queue metadata, as
#       divergence D-AUTHORIZATION-FIFO-IDENTITY-METADATA in
#       docs/architecture/cobol-to-service-traceability.md. What bounds it is the queue's
#       posture: SSE with the customer-managed key passed below, reachability only from
#       the private subnets, and read access only through the task roles this root grants.
module "sqs" {
  source = "../../modules/sqs"

  name_prefix = var.name_prefix
  environment = var.environment

  # WHY : Assumptions: the queue key and not the S3 key. infra/modules/kms publishes
  #       four separate CMKs so that one compromised grant reaches one store, and an
  #       authorization message body carries cardholder data, so reusing the
  #       object-store key here would collapse two of those four boundaries at a call
  #       site nobody would think to audit.
  kms_key_arn = module.kms.sqs_key_arn
}

# -----------------------------------------------------------------------------
# Generation datasets: the GDG bases, re-expressed as versioned S3 prefixes.
# -----------------------------------------------------------------------------
#
# WHY : Assumptions: var.dataset_families is deliberately NOT passed. There are TEN
#       generation-dataset families -- not six, and not eleven -- and the inventory with
#       its per-family provenance lives once in infra/modules/s3-datasets/variables.tf
#       so both roots provision identical prefix topology. Six is what a reader gets
#       from app/jcl/DEFGDGB.jcl alone, and that file looks complete because it is
#       headed as the GDG bases needed by the project and defines six in one IDCAMS
#       step; the other four are elsewhere, three in app/jcl/DEFGDGD.jcl (L28, L51,
#       L74) and one in app/jcl/DALYREJS.jcl (L25).
# WHY : Assumptions: an exhaustive search of the baseline for DEFINE
#       GENERATIONDATAGROUP returns ELEVEN statements over TEN distinct base names, so
#       the eleventh hit is not an eleventh family. AWS.M2.CARDDEMO.TRANREPT is defined
#       twice -- app/jcl/DEFGDGB.jcl:L37 and again standalone at app/jcl/REPTFILE.jcl:L26
#       -- and a maintainer who counts DEFINE statements rather than names will
#       "correct" ten to eleven and provision a prefix no batch step ever writes to.
#       Provisioning six is the more damaging error in the other direction: the four
#       missing steps would still write their objects, into a prefix carrying no
#       lifecycle rule, so nothing would fail and those generations would accumulate
#       without limit.
# WHY : Alternatives Considered: honouring TRANREPT's LIMIT(10) from
#       app/jcl/REPTFILE.jcl:L27 by overriding that one family's retention. Rejected
#       because the two baseline definitions contradict each other -- DEFGDGB.jcl:L38
#       says LIMIT(5) with SCRATCH, REPTFILE.jcl:L27 says LIMIT(10) with no SCRATCH --
#       and AAP §0.4.1.7 fixes a uniform five-generation retention across all ten. One
#       family retaining ten would make the lifecycle rule non-uniform for no stated
#       benefit and would leave the next reader unable to tell the exception from a
#       mistake. The module keeps a per-family override available so the decision stays
#       reversible without a topology change if the conflict is ever resolved the other
#       way.
module "s3_datasets" {
  source = "../../modules/s3-datasets"

  name_prefix = var.name_prefix
  environment = var.environment

  # WHY : Assumptions: the S3 customer-managed key, because these objects are dataset
  #       generations derived from the cardholder masters.
  # WHY : Refactoring Rationale: this rationale used to continue "Bucket versioning plus
  #       a five-noncurrent-version lifecycle rule is what reproduces LIMIT(5) SCRATCH:
  #       a sixth generation makes the oldest noncurrent version expire". It does not,
  #       and the claim contradicted both the module being called and the notification
  #       hook declared below in this same file. Each generation is written under a
  #       DISTINCT `<family>/dt=YYYY-MM-DD/gen=NNNN/` key, so S3 never sees generation
  #       six as a version of generation five and no lifecycle rule can count
  #       generations at all -- infra/modules/s3-datasets/main.tf says so in the same
  #       words. The LIMIT(5) SCRATCH analogue is aws_lambda_function.dataset_retention,
  #       invoked on every ObjectCreated event through the notification below: it lists a
  #       family's date and generation prefixes, orders them by business date and
  #       generation number, and deletes the current objects under every prefix past the
  #       newest five. What the module's noncurrent-version rules bound is a REWRITE of
  #       one key -- the same generation staged twice by a retry -- together with the
  #       delete markers and superseded versions that pruning itself leaves behind.
  #       Trade-offs: the mistaken reading is the dangerous one, because it makes the
  #       retention function look redundant. Remove it on that reading and generations
  #       accumulate without limit while no rule errors and no alarm fires.
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

# WHY : ⚠️ Refactoring Rationale: both statements were unbounded within the bucket --
#       `s3:ListBucket` on the bucket ARN with no condition, and `s3:DeleteObject` on
#       `<bucket>/*`. That is DELETE over every object the dataset bucket holds, which
#       is not only the ten generation families: the same bucket carries the source
#       extracts the nightly refresh reads, the statement and report outputs under
#       `non_generation_prefixes`, and the authorization extract. A defect in this
#       function -- or a principal that reached its role -- could therefore have
#       removed the inputs a rerun needs and the statements an operator had already
#       been sent, neither of which this function has any business touching. The
#       narrowing is to the ten prefixes it actually derives.
# WHY : Alternatives Considered: scoping the delete to one family per invocation by
#       templating the policy from the event key. Rejected because an identity policy
#       is written at apply time and the key is known only at invocation time, so the
#       policy would have to be rewritten per event -- and a role whose policy changes
#       per request is not a bound at all. The ten-prefix grant is the narrowest set
#       expressible before the event exists.
# WHY : Trade-offs: within one family the grant still covers every generation rather
#       than only the ones past the retention count, for the same reason -- which
#       generations are obsolete is decided by the listing the function has just
#       performed. What the narrowing buys is that the blast radius is bounded to data
#       this function is the lifecycle owner of, and excludes every object another
#       role wrote.
data "aws_iam_policy_document" "dataset_retention_s3" {
  statement {
    sid       = "ListDatasetGenerationPrefixes"
    actions   = ["s3:ListBucket"]
    resources = [module.s3_datasets.bucket_arn]

    # Assumptions: StringLike with a trailing wildcard covers BOTH depths the
    #   function lists at -- the family level, where it enumerates `dt=` common
    #   prefixes, and the generation level, where it enumerates the objects under one
    #   `dt=.../gen=.../`. A list call with no prefix, or with a prefix outside these
    #   ten, matches no value here and is denied, which is the whole-bucket
    #   enumeration this condition exists to remove.
    condition {
      test     = "StringLike"
      variable = "s3:prefix"
      values   = [for prefix in local.dataset_generation_key_prefixes : "${prefix}*"]
    }
  }

  statement {
    sid     = "DeleteObsoleteGenerationObjects"
    actions = ["s3:DeleteObject"]

    # Assumptions: the action stays singular even though the function calls the batch
    #   DeleteObjects API. That API authorises `s3:DeleteObject` against each key it
    #   is given, so the plural call needs no separate action and the per-object ARNs
    #   below bound every key inside it.
    resources = [
      for prefix in local.dataset_generation_key_prefixes :
      "${module.s3_datasets.bucket_arn}/${prefix}*"
    ]
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

  # WHY : Refactoring Rationale: this was ONE unfiltered block, so every object created
  #       anywhere in the dataset bucket invoked the retention function -- including the
  #       reporting and statement outputs and the authorization extracts, none of which
  #       carry generations and none of which the function can act on. It answered those
  #       by deriving no family prefix and returning, so the work was correct and the
  #       invocation was pure waste, competing for the same concurrency as the events
  #       that do matter. Filtering to the ten generation families bounds the fan-out at
  #       its SOURCE, which is the half of the problem no concurrency ceiling or failure
  #       destination addresses.
  # WHY : Assumptions: the ten prefixes are exactly the families the function itself
  #       recognises -- local.dataset_generation_key_prefixes is projected from the same
  #       module output the IAM narrowing uses, so the notification, the delete grant and
  #       the handler's own derivation cannot disagree about what a generation family is.
  #       S3 refuses OVERLAPPING prefix filters for one event type, and these ten do not
  #       overlap because each is a distinct <domain>/<dataset>/ pair.
  # WHY : Trade-offs: ten notification blocks where there was one, generated rather than
  #       written out. The handler's "no family derived" branch becomes unreachable
  #       through this path and is deliberately kept, because it still guards a direct
  #       invocation and a future notification added without a filter.
  dynamic "lambda_function" {
    for_each = toset(local.dataset_generation_key_prefixes)

    content {
      lambda_function_arn = aws_lambda_function.dataset_retention.arn
      events              = ["s3:ObjectCreated:*"]
      filter_prefix       = lambda_function.value
    }
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
    # WHY : (1) Assumptions: all six queue entries below carry the queue's URL and not
    #       its NAME, and the two are not interchangeable. Every value is a
    #       module.sqs *_queue_url output, and each consuming property is spelled
    #       carddemo.<context>.inquiry.{request,reply,error}-queue -- a name that
    #       reads as though it wanted a name. The consumers accept EITHER
    #       representation for exactly that reason:
    #       com.carddemo.account.service.InquiryMessageListener and
    #       com.carddemo.reference.service.DateInquiryMessageListener each use a
    #       configured value carrying an http or https scheme as an address directly
    #       and resolve only a bare name through GetQueueUrl, and each records the
    #       decision at its own queueUrl method.
    #       (2) Refactoring Rationale: this note did not exist, and its absence was
    #       part of a defect rather than a documentation gap. The consumers used to
    #       resolve every configured value as a NAME, so the URLs published here were
    #       passed to GetQueueUrl as queue names -- which no queue can be, since a
    #       name admits only alphanumerics, hyphens and underscores. Both inquiry
    #       flows therefore failed at the resolution call before their send in every
    #       provisioned environment: the request became visible again, was
    #       redelivered to the same failure and dead-lettered, and the requester was
    #       answered with nothing at all. Neither side was wrong on its own, which is
    #       why nothing failed at plan time and no test in either module could see
    #       it, and it is why the representation this root produces is now stated
    #       here beside the values rather than left to be inferred from an output
    #       name.
    #       (3) Trade-offs: URLs are kept rather than switched to names, and the
    #       reason is that an address is the value the caller actually needs. A name
    #       obliges every consumer to spend a GetQueueUrl call at start-up, on a
    #       queue this root has already resolved, and to hold the IAM grant for it;
    #       an address needs neither. The accepted cost is that the property names
    #       read as names, which is what item (1) exists to reconcile. The
    #       Resolve*DestinationsByName statements further down still grant
    #       sqs:GetQueueUrl on exactly these reply and error ARNs, so a local or
    #       future configuration supplying a name is authorised rather than failing
    #       closed.
    #       (4) Assumptions: the reply and error queues are SHARED between the two
    #       inquiry contexts -- both read module.sqs.inquiry_reply_queue_url and
    #       module.sqs.error_queue_url -- while each request queue is its own. That
    #       matches the queue module's own provisioning and the messaging contract
    #       document: a reply is routed to its requester by correlation identity
    #       rather than by queue, so one reply queue serves both flows, whereas a
    #       request queue is what selects which consumer receives a request.
    "account|CARDDEMO_ACCOUNT_INQUIRY_REQUEST_QUEUE_URL" = {
      service          = "account"
      environment_name = "CARDDEMO_ACCOUNT_INQUIRY_REQUEST_QUEUE_URL"
      value            = module.sqs.inquiry_request_queue_url
    }
    "account|CARDDEMO_ACCOUNT_INQUIRY_REPLY_QUEUE_URL" = {
      service          = "account"
      environment_name = "CARDDEMO_ACCOUNT_INQUIRY_REPLY_QUEUE_URL"
      value            = module.sqs.inquiry_reply_queue_url
    }
    "account|CARDDEMO_ACCOUNT_INQUIRY_ERROR_QUEUE_URL" = {
      service          = "account"
      environment_name = "CARDDEMO_ACCOUNT_INQUIRY_ERROR_QUEUE_URL"
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
    "aurora/host"     = module.aurora.writer_endpoint
    "aurora/port"     = tostring(module.aurora.port)
    "aurora/database" = module.aurora.database_name
    "datasets/bucket" = module.s3_datasets.bucket_name

    # WHY : ⚠️ Refactoring Rationale: FOUR state-machine ARN entries stood here and are
    #       removed -- `batch/daily-state-machine-arn`,
    #       `reporting/adhoc-state-machine-arn`,
    #       `batch/dataset-roundtrip-state-machine-arn` and
    #       `authorization/extract-state-machine-arn`. Each was justified as published
    #       "for discovery", and that justification did not survive checking who
    #       discovers anything from it: NOTHING reads them. The one runtime consumer,
    #       reporting-service, receives the ad-hoc ARN as the container environment
    #       variable CARDDEMO_REPORTING_STEP_FUNCTIONS_STATE_MACHINE_ARN in
    #       local.special_service_environment above, never from Parameter Store; and
    #       docs/runbooks/batch-operations.md obtains all four by reading
    #       `terraform output -json batch_orchestration` and selecting each with `jq`.
    #       A grep for the four parameter names across services/, ui/src and
    #       data-migration/src returns nothing.
    # WHY : Trade-offs: a parameter nobody reads is not free. It is four more resources
    #       to create, four more to keep in step with a module rename, and -- because
    #       the batch and reporting task roles are granted read access by PREFIX -- four
    #       values inside a prefix those roles can read, for no purpose. Removing them
    #       also removes the standing question of which source is authoritative when
    #       the parameter and the Terraform output disagree.
    # WHY : Assumptions: this does NOT reduce what an operator can reach. The Terraform
    #       output is the authoritative source and always was; these were a second copy
    #       of it that could go stale independently.

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

# -----------------------------------------------------------------------------
# Parameter Store publication -- the root's own contract with the services.
# -----------------------------------------------------------------------------
#
# WHY : Assumptions: these parameters exist so that NO service hard-codes an endpoint,
#       which AAP §0.5.3.5 makes the root's responsibility precisely because none of the
#       sixteen modules can discharge it -- a module knows the value it produced but not
#       which of eight consumers needs it under which name. Every endpoint and
#       identifier a container needs at startup is resolved here from a module output and
#       written to one namespace, so a redeployed database or a replaced queue moves the
#       value and the services follow it without an image rebuild or a tfvars edit.
# WHY : Assumptions: this resource carries ENDPOINTS AND IDENTIFIERS ONLY, and never a
#       credential. Credentials are generated during apply and written to Secrets
#       Manager by module.secrets and module.cognito, and the services read them from
#       there through a separate grant.
# WHY : Refactoring Rationale: the reason given here used to be that "Parameter Store is
#       read by every task role in the estate over one shared prefix", which is not how
#       these entries are granted. A service reads its OWN parameters and no others:
#       local.runtime_parameter_arns_by_service below filters this resource by the
#       service half of each composite key, and infra/modules/ecs-service turns that map
#       into a single AllowSsmParameterRead statement granting ssm:GetParameters on
#       exactly those ARNs -- held by the EXECUTION role, which resolves the container's
#       environment once before it starts and which the application never assumes. No
#       task role in this estate holds a path-prefix read except data-migration's.
#       Keeping the false premise would have made the boundary look like a convention
#       rather than the enforced grant it is, and would have justified widening a
#       per-service ARN list on the reading that it changed nothing.
# WHY : Assumptions: the boundary is still exactly where it was, for two reasons that do
#       hold. (a) One role DOES read the whole namespace: the data_migration_runtime
#       document in this root grants ssm:GetParameter, GetParameters and
#       GetParametersByPath across parameter/<name_prefix>/<environment>/* because the
#       ETL resolves its own inputs by path, so a credential keyed here under ANY
#       service would be legible to the ETL task role whatever its key said. (b) The
#       value is an ARGUMENT of this resource rather than something generated during
#       apply, so it is recorded in the state file and rendered in a plan diff --
#       which is the exposure module.secrets and module.cognito exist to avoid by
#       generating into Secrets Manager and publishing only an ARN.
# WHY : Assumptions: `type = "String"` is therefore the correct type and not a
#       weakening. A value that genuinely needed SecureString would be a credential,
#       which means it would belong in Secrets Manager and should not appear in this
#       resource at all -- so a SecureString parameter appearing here is the signal that
#       the boundary above has been crossed, rather than a stronger way to hold the same
#       kind of value.
# WHY : Assumptions: names are composed from var.name_prefix and var.environment through
#       local.parameter_prefix and never written literally, so the prod root publishes a
#       parallel namespace that cannot collide with this one even in a shared account,
#       and no service resolves a dev endpoint from a prod profile.
resource "aws_ssm_parameter" "runtime" {
  for_each = local.runtime_parameters

  name        = "${local.parameter_prefix}/${var.environment}/${each.value.service}/${each.value.environment_name}"
  description = "Runtime value injected as ${each.value.environment_name} for ${each.value.service}."
  type        = "String"
  value       = each.value.value
}

# WHY : Assumptions: the same two guarantees as the per-service parameters above apply
#       to this resource -- it exists so no service hard-codes an endpoint, and it
#       carries identifiers only and never a credential. It is a SEPARATE resource
#       because its keys are platform-wide rather than service-scoped: the Aurora writer
#       endpoint, port and database name, the dataset bucket, the batch state-machine
#       ARNs and the seeded identity subjects are each read by several workloads and by
#       the ETL, so keying them per service would publish the same value under seven
#       names and leave a reader unable to tell which copy is authoritative.
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
  # WHY : Refactoring Rationale: a messaging_hmac_secret_sources local stood here,
  #       injecting CARDDEMO_MESSAGING_HMAC_KEY into the authorization task. It is
  #       withdrawn because the component that read it is gone: the property
  #       carddemo.messaging.hmac-key had exactly one consumer, and with that
  #       consumer deleted the name is read by nothing in any image. The sibling
  #       module retired its own biconditional clause for the same name, so leaving
  #       the injection here would publish a secret to a container that never opens
  #       it -- a live credential with no reader, which is the shape a review is
  #       least likely to question and most likely to inherit.
  # WHY : Assumptions: the SECRET itself is deliberately left provisioned. Its value
  #       is generated at apply time and never leaves Secrets Manager, the
  #       environment inventory and the secret census both count six purpose
  #       secrets, and removing a secret is a destroy-and-recreate whose recovery
  #       window is a separate operational decision from withdrawing an injection.
  #       What is closed here is the exposure -- no task receives it.


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
  #       (2) Assumptions: SEVEN workloads receive it, and the seven are the whole of
  #       the gate: auth, account, card, transaction, reference, reporting and
  #       authorization.
  #       Each holds at least one component that requires the CursorToken bean --
  #       UserService; AccountViewService; CardListService; TransactionController;
  #       TransactionTypeController, TransactionCategoryController and
  #       AddressLookupController; ReportController; and PendingAuthViewMapper -- and a
  #       service whose context requires the bean cannot refresh without the key. batch
  #       and data-migration are absent because neither constructs the bean, and giving
  #       them the key would widen the set able to forge a cursor for no capability
  #       either exercises.
  #       (2a) Refactoring Rationale: this said SIX and omitted auth, while the
  #       contains() list below has admitted it since auth-service began publishing a
  #       keyset page of the user master -- service/UserService.java declares
  #       CursorToken as a constructor argument and seals its page boundaries with it.
  #       A count one short of the gate a few lines under it is worse than none,
  #       because a reader checking the gate against the prose would conclude the gate
  #       was over-broad and narrow it, which would stop auth-service refreshing its
  #       context at all.
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
    # WHY : ⚠️ Refactoring Rationale: there is deliberately no `reference` entry. The queue
    #       module publishes no reference_service member any more, because that context
    #       consumes no queue: the single shared inquiry request queue has exactly one
    #       owning consumer, in the account context, and a second receive grant would let a
    #       second identity take messages only that consumer can answer. A workload absent
    #       from this map falls through to local.no_queue_permissions below, so both queue
    #       arguments are empty lists and modules/ecs-service creates no queue-boundary
    #       policy for it at all.
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

  # WHY : Assumptions: derived from secret_sources_by_workload rather than from
  #       local.workloads, so a workload that holds no secret is given no key and the
  #       two cannot disagree about which tasks need one.
  secret_kms_key_arns_by_workload = {
    for service in keys(local.workloads) :
    service => length(local.secret_sources_by_workload[service]) == 0 ? [] : distinct(concat(
      [module.kms.secrets_key_arn],

      # WHY : Assumptions: gated on data-migration by exact name, matching the gate on
      #       mask_hmac_secret_sources above. It is the only task that reads the
      #       fingerprint secret, so it is the only one granted the key that protects it.
      service == "data-migration" ? [var.mask_hmac_secret_kms_key_arn] : [],
    ))
  }

  secret_sources_by_workload = {
    for service, workload in local.workloads :
    service => merge(
      contains(local.database_workload_names, service) ? local.database_secret_sources[service] : {},
      service == "auth" ? local.auth_client_secret_sources : {},
      service == "data-migration" ? local.mask_hmac_secret_sources : {},

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
        #       carddemo-common-defaults.yml falls back to the literal "unspecified"
        #       without it -- so every log record and metric series this estate produced
        #       was unattributable to a release. The service module now refuses a task
        #       whose value is absent, blank or that same placeholder.
        #       Refactoring Rationale: this sentence also credited "the telemetry
        #       collector's resource processor and OTEL_RESOURCE_ATTRIBUTES" with the same
        #       fallback, and named spans among the affected signals. Neither consumer
        #       exists now: the collector sidecar was withdrawn because the
        #       specification's module inventory contains none, so nothing in this estate
        #       emits a span and the only remaining consumer of the label is the
        #       application's own configuration.
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

# WHY : ⚠️ Refactoring Rationale: the consume statement below names the ONE shared
#       inquiry request queue and used to name an account-specific one. The baseline
#       drives BOTH inquiry programs from a single request destination, DEFINE
#       QLOCAL('CARDDEMO.REQUEST.QUEUE') at app/app-vsam-mq/README.md L53 aliased to
#       CICS as MQQUEUE(CARDREQ) at L71, and modules/sqs provisions that one queue. A
#       queue admits exactly one OWNING consumer, because a receive hides the message
#       from every other consumer rather than delivering a copy to each, so this task
#       role is the only one in the deployment holding sqs:ReceiveMessage on it and the
#       reference task role holds no queue action at all.
# WHY : Assumptions: the three actions are the whole of what a consumer needs and
#       sqs:GetQueueUrl is deliberately not among them. The listener resolves each
#       destination's address by NAME with GetQueueUrl, which is an unauthenticated-by-
#       policy read of a name the caller already holds -- the roots publish the queue
#       NAME rather than the URL for exactly that reason -- while receive, delete and
#       attribute-read are the actions that touch messages.
data "aws_iam_policy_document" "account_runtime" {
  statement {
    sid       = "ConsumeInquiryRequests"
    actions   = ["sqs:ReceiveMessage", "sqs:DeleteMessage", "sqs:GetQueueAttributes"]
    resources = [module.sqs.inquiry_request_queue_arn]
  }

  statement {
    sid       = "PublishInquiryResults"
    actions   = ["sqs:SendMessage"]
    resources = [module.sqs.inquiry_reply_queue_arn, module.sqs.error_queue_arn]
  }

  # WHY : Refactoring Rationale: this statement was ABSENT, and its absence was one
  #       half of a defect that made both inquiry flows undeliverable in a provisioned
  #       environment. The two publish targets above are supplied to the workload as
  #       queue URLs by the parameter block earlier in this root, and the consumers
  #       resolved a configured destination through GetQueueUrl with the value as a
  #       queue NAME -- so a URL was passed where a name was required and every reply
  #       failed before its send. The consumers now accept either representation
  #       directly, which removes the call on the URL path; this statement covers the
  #       NAME path, so a local or future configuration that supplies a name is
  #       authorised rather than merely expressible. Without it that path would fail
  #       closed with an AccessDenied on a queue the same role may already send to.
  # WHY : Trade-offs: the action is scoped to the SAME two ARNs the send statement
  #       names and is deliberately not merged into it. GetQueueUrl is a read of a
  #       queue's address and SendMessage is a write to its contents, and keeping them
  #       as two statements means the send grant can be narrowed or widened later
  #       without silently carrying the lookup with it. A wildcard resource was
  #       rejected outright: GetQueueUrl on every queue in the account would let this
  #       role discover the address of the authorization and batch queues it must never
  #       reach.
  # WHY : Assumptions: the request queue needs no entry of its own. The listener
  #       container resolves that queue through the starter, which is already granted
  #       GetQueueAttributes on it above, and the consumer never calls GetQueueUrl for
  #       it.
  statement {
    sid       = "ResolveAccountInquiryDestinationsByName"
    actions   = ["sqs:GetQueueUrl"]
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

# WHY : Refactoring Rationale: a `reference_runtime` policy document stood here and is
#       WITHDRAWN whole. All four of its statements existed for one consumer -- a
#       date-inquiry queue listener in reference-service -- and that consumer is retired:
#       AAP section 0.4.1.8 fixes the topology at five queues with no per-function queue,
#       so date inquiries ride the shared inquiry request queue, whose single consumer is
#       com.carddemo.account.service.InquiryMessageListener dispatching on its FUNCTION
#       field. Two of the statements had already stopped resolving -- they named
#       module.sqs.date_inquiry_request_queue_arn, an output modules/sqs withdrew with the
#       queue pair it addressed -- so the document could not be evaluated at all.
# WHY : Assumptions: nothing replaces it, and the absence is the mechanism rather than a
#       gap. local.task_role_policy_json below deliberately carries no `reference` key, and
#       modules/ecs-service takes create_task_role_policy from whether that key is present,
#       so the inline policy resource is simply absent for that workload -- a reference task
#       holds exactly what the module composes for every service and nothing else. A
#       statement-less document left in place would instead have produced an invalid policy
#       at apply time.
# WHY : Trade-offs: the reply and error-queue send grants are NOT relocated. The workload
#       that publishes those replies is the account task, whose own document already names
#       both queues, so moving them would have duplicated a grant the correct role already
#       holds and left this root asserting a capability for a container that opens no queue
#       client.

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
    # WHY : Assumptions: kms:ViaService confines this key to use made THROUGH S3 in this
    #       Region, so a principal that reached this role cannot call Decrypt directly on
    #       ciphertext of its own choosing -- which is the capability an unconditioned
    #       grant on a shared key hands out.
    # WHY : Alternatives Considered: additionally conditioning on
    #       kms:EncryptionContext:aws:s3:arn to name the bucket. Rejected here, and the
    #       reason is specific rather than general: with bucket keys enabled -- they are,
    #       at infra/modules/s3-datasets/main.tf and infra/modules/cloudfront-spa/main.tf
    #       -- S3 sets that context to the BUCKET ARN, but it sets it to the OBJECT ARN
    #       when they are not, so a StringEquals here would turn a storage-configuration
    #       change into a runtime access denial on a path that only fails when it is
    #       used. The narrowing it would express is already enforced anyway: the object
    #       statements in this same document name the exact ARNs and prefixes, and a KMS
    #       grant alone reaches no object without them.
    condition {
      test     = "StringEquals"
      variable = "kms:ViaService"
      values   = ["s3.${data.aws_region.current.region}.amazonaws.com"]
    }

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
    # WHY : Assumptions: kms:ViaService confines this key to use made THROUGH S3 in this
    #       Region, so a principal that reached this role cannot call Decrypt directly on
    #       ciphertext of its own choosing -- which is the capability an unconditioned
    #       grant on a shared key hands out.
    # WHY : Alternatives Considered: additionally conditioning on
    #       kms:EncryptionContext:aws:s3:arn to name the bucket. Rejected here, and the
    #       reason is specific rather than general: with bucket keys enabled -- they are,
    #       at infra/modules/s3-datasets/main.tf and infra/modules/cloudfront-spa/main.tf
    #       -- S3 sets that context to the BUCKET ARN, but it sets it to the OBJECT ARN
    #       when they are not, so a StringEquals here would turn a storage-configuration
    #       change into a runtime access denial on a path that only fails when it is
    #       used. The narrowing it would express is already enforced anyway: the object
    #       statements in this same document name the exact ARNs and prefixes, and a KMS
    #       grant alone reaches no object without them.
    condition {
      test     = "StringEquals"
      variable = "kms:ViaService"
      values   = ["s3.${data.aws_region.current.region}.amazonaws.com"]
    }

  }
}

data "aws_iam_policy_document" "batch_runtime" {
  # WHY : ⚠️ Refactoring Rationale: this listing was unbounded -- `s3:ListBucket` on the
  #       bucket ARN with no condition, which enumerates EVERY key the bucket holds.
  #       The dataset bucket is shared: besides the ten generation families it carries
  #       the three reporting artifact prefixes, whose statements and reports name
  #       accounts, and the authorization extract prefix, whose objects are pending
  #       authorization roots and children. A batch task could therefore discover, and
  #       under the object statement below read, data belonging to two contexts it has
  #       no part in. The bucket is not a privilege boundary when four workloads write
  #       into it.
  # WHY : Assumptions: `s3:prefix` is the ONLY way a list call can be bounded, because
  #       its resource is the bucket rather than an object -- an object-ARN restriction
  #       has no effect on it. This is the same pairing the reporting role above uses,
  #       and it is applied here for the same reason.
  # WHY : Trade-offs: `StringLike` against each prefix followed by a wildcard rather
  #       than `StringEquals` against the bare prefix. The batch states list a
  #       generation family's date prefix to choose the generation they are about to
  #       write -- `<family>/dt=<business date>/` -- so an equality test would permit
  #       only a bare top-level listing and refuse the one call the grant exists for.
  # WHY : Assumptions: the statement id names the BATCH grant specifically rather than
  #       reusing the retention role's `ListDatasetGenerationPrefixes` above. That role
  #       is a different principal with a different, deliberately unbounded listing
  #       grant -- the retention function enumerates every family to count generations
  #       -- so two statements sharing one id would make "is this listing bounded?"
  #       unanswerable by grep, which is the question an audit of this file asks first.
  statement {
    sid       = "ListBatchGenerationPrefixes"
    actions   = ["s3:ListBucket"]
    resources = [module.s3_datasets.bucket_arn]

    condition {
      test     = "StringLike"
      variable = "s3:prefix"
      values   = [for prefix in local.batch_generation_key_prefixes : "${prefix}*"]
    }
  }

  # WHY : ⚠️ Refactoring Rationale: this statement's resource was
  #       `<bucket>/*` -- read and overwrite on every object in the bucket. It is now
  #       one resource per generation family. The rationale that stood here argued that
  #       a per-prefix enumeration "would grant exactly what /* grants today", on the
  #       premise that the generation prefixes plus the source-extract prefix are ALL of
  #       the keys the bucket holds. That premise was false: infra/modules/s3-datasets
  #       also publishes three non-generation prefixes for reporting artifacts, and this
  #       root composes an authorization extract prefix in the same bucket, so the old
  #       grant let a batch task read every statement, every transaction report and
  #       every pending-authorization extract, and overwrite them.
  # WHY : Assumptions: the ten generation families are what the batch chain actually
  #       touches -- BackupTransactions and CombineTransactions write and read
  #       generations, the export and import jobs round-trip one, and the reject stream
  #       is a generation of its own. It reads no reporting artifact: those are written
  #       by the reporting task definition under its own role, which the statements
  #       above grant. And it reads no source extract: `refresh-dataset` runs on the
  #       data-migration task definition, whose policy sources this document and adds
  #       that prefix for itself.
  # WHY : Trade-offs: the enumeration is derived from module.s3_datasets.dataset_prefixes
  #       rather than written out, so a family added to that module is covered here
  #       without an edit while a prefix that is NOT a generation family stays out of
  #       reach. That is the drift the old rationale feared, closed by deriving the list
  #       instead of by widening the grant.
  statement {
    sid     = "ReadWriteDatasetGenerations"
    actions = ["s3:GetObject", "s3:PutObject", "s3:AbortMultipartUpload"]
    resources = [
      for prefix in local.batch_generation_key_prefixes :
      "${module.s3_datasets.bucket_arn}/${prefix}*"
    ]
  }

  statement {
    sid       = "UseDatasetKey"
    actions   = ["kms:Decrypt", "kms:GenerateDataKey"]
    resources = [module.kms.s3_key_arn]
    # WHY : Assumptions: kms:ViaService confines this key to use made THROUGH S3 in this
    #       Region, so a principal that reached this role cannot call Decrypt directly on
    #       ciphertext of its own choosing -- which is the capability an unconditioned
    #       grant on a shared key hands out.
    # WHY : Alternatives Considered: additionally conditioning on
    #       kms:EncryptionContext:aws:s3:arn to name the bucket. Rejected here, and the
    #       reason is specific rather than general: with bucket keys enabled -- they are,
    #       at infra/modules/s3-datasets/main.tf and infra/modules/cloudfront-spa/main.tf
    #       -- S3 sets that context to the BUCKET ARN, but it sets it to the OBJECT ARN
    #       when they are not, so a StringEquals here would turn a storage-configuration
    #       change into a runtime access denial on a path that only fails when it is
    #       used. The narrowing it would express is already enforced anyway: the object
    #       statements in this same document name the exact ARNs and prefixes, and a KMS
    #       grant alone reaches no object without them.
    condition {
      test     = "StringEquals"
      variable = "kms:ViaService"
      values   = ["s3.${data.aws_region.current.region}.amazonaws.com"]
    }

  }
}

data "aws_iam_policy_document" "data_migration_runtime" {
  source_policy_documents = [data.aws_iam_policy_document.batch_runtime.json]

  # WHY : ⚠️ Refactoring Rationale: these two statements were not needed while
  #       batch_runtime granted the whole bucket, and they ARE needed now that it grants
  #       the ten generation prefixes only. The seed-refresh state runs
  #       `refresh-dataset` on this task definition, and that command reads each exported
  #       extract out of module.s3_datasets.source_extract_prefix before it stages,
  #       decodes and loads it. Sourcing the batch document gives this role the
  #       generation prefixes it stages INTO; the extract prefix it reads FROM is this
  #       workload's alone, so it is granted here rather than to every batch task.
  # WHY : Assumptions: the listing is bounded by `s3:prefix` for the same reason it is
  #       above -- a list call's resource is the bucket, so only the condition key can
  #       narrow it -- and the loader does list the prefix rather than only fetching
  #       known keys, because it resolves which extract objects a dataset has.
  # WHY : Trade-offs: `s3:GetObject` only. This role never writes an extract: the
  #       extracts are placed by the operator sync documented in
  #       docs/runbooks/data-migration.md, and a write grant here would let a migration
  #       task alter the very inputs its verification passes compare the loaded rows
  #       against -- which would make the money-total and digest gates self-referential.
  statement {
    sid       = "ListSourceExtractPrefix"
    actions   = ["s3:ListBucket"]
    resources = [module.s3_datasets.bucket_arn]

    condition {
      test     = "StringLike"
      variable = "s3:prefix"
      values   = ["${module.s3_datasets.source_extract_prefix}*"]
    }
  }

  statement {
    sid       = "ReadSourceExtracts"
    actions   = ["s3:GetObject"]
    resources = ["${module.s3_datasets.bucket_arn}/${module.s3_datasets.source_extract_prefix}*"]
  }

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
    # WHY : Assumptions: BOTH conditions are applied. kms:ViaService confines the key to
    #       use made through Secrets Manager in this Region, so the grant cannot be spent
    #       on ciphertext this principal supplied itself; the encryption-context condition
    #       confines it to the exact secrets this document already authorises, because
    #       Secrets Manager sets SecretARN to the secret being read or written on every
    #       request. The set matches the GetSecretValue statement above it, so this task can spend the key only on the credentials it is already allowed to read.
    # WHY : Trade-offs: StringLike rather than StringEquals on the context, matching the
    #       reasoning recorded in infra/modules/ecs-service/main.tf -- Secrets Manager
    #       appends a six-character suffix to the ARN it puts in the context when the
    #       caller supplied a name-only ARN, and each pattern still names one secret.
    condition {
      test     = "StringEquals"
      variable = "kms:ViaService"
      values   = ["secretsmanager.${data.aws_region.current.region}.amazonaws.com"]
    }

    condition {
      test     = "StringLike"
      variable = "kms:EncryptionContext:SecretARN"
      values   = [for secret in values(module.secrets.service_credential_secrets) : secret.arn]
    }

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
#       (2) Assumptions: the SIX actions are exactly the six the service issues,
#       and no seventh is granted speculatively. The set was derived by enumerating
#       every provider call in services/auth-service/src/main/java rather than from
#       the shape of the feature: adminCreateUser, adminAddUserToGroup,
#       adminRemoveUserFromGroup, adminUpdateUserAttributes, adminDeleteUser and
#       adminUserGlobalSignOut.
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
#       Refactoring Rationale: a SIXTH action is granted, AdminUserGlobalSignOut, and
#       the paragraph above asserted five was exhaustive. Two call sites need it and
#       both are privilege REDUCTIONS. A demotion withdraws the administrative group
#       and then ends every session that carries the claim, inside the write span, so
#       that a reported success cannot precede the withdrawal; and a deletion signs the
#       account out before removing it, because deleting an account does not invalidate
#       the tokens already minted from it -- a resource server checks a signature and
#       an expiry against the pool's public keys, so a deleted administrator's token
#       goes on being accepted with its group claim intact until it expires. Without
#       this statement both calls fail with an access denial, and the demotion's
#       failure is the worse of the two: it rolls the row back, so the demotion an
#       administrator asked for cannot be performed at all.
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
      "cognito-idp:AdminUserGlobalSignOut",
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
    # WHY : Assumptions: BOTH conditions are applied. kms:ViaService confines the key to
    #       use made through Secrets Manager in this Region, so the grant cannot be spent
    #       on ciphertext this principal supplied itself; the encryption-context condition
    #       confines it to the exact secrets this document already authorises, because
    #       Secrets Manager sets SecretARN to the secret being read or written on every
    #       request. The pattern is the same runtime-user prefix the PutSecretValue statement above names, so the write grant and the key grant describe one boundary rather than two.
    # WHY : Trade-offs: StringLike rather than StringEquals on the context, matching the
    #       reasoning recorded in infra/modules/ecs-service/main.tf -- Secrets Manager
    #       appends a six-character suffix to the ARN it puts in the context when the
    #       caller supplied a name-only ARN, and each pattern still names one secret.
    condition {
      test     = "StringEquals"
      variable = "kms:ViaService"
      values   = ["secretsmanager.${data.aws_region.current.region}.amazonaws.com"]
    }

    condition {
      test     = "StringLike"
      variable = "kms:EncryptionContext:SecretARN"
      values   = ["arn:${data.aws_partition.current.partition}:secretsmanager:${data.aws_region.current.region}:${data.aws_caller_identity.current.account_id}:secret:${module.cognito.credential_secret_name_prefix}/runtime-user/*"]
    }

  }
}

locals {
  task_role_policy_json = {
    auth    = data.aws_iam_policy_document.auth_runtime.json
    card    = data.aws_iam_policy_document.card_runtime.json
    account = data.aws_iam_policy_document.account_runtime.json
    # WHY : ⚠️ Refactoring Rationale: there is deliberately NO `reference` key here. The
    #       document it named is withdrawn with the queue consumer that gave it its three
    #       statements -- the withdrawal is argued at that document's former site above --
    #       and the absence of the key is the mechanism, not a side effect: the module
    #       argument create_task_role_policy is contains(keys(local.task_role_policy_json),
    #       each.key), so a missing key makes the inline policy resource absent, whereas a
    #       key pointing at a statement-less document would produce an invalid policy. A
    #       reference task therefore holds exactly the permissions modules/ecs-service
    #       composes for every workload and nothing service-specific, which is the correct
    #       state for a context that reaches no queue, no key and no bucket of its own.
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

  # WHY : Refactoring Rationale: two inputs stood here, telemetry_collector_image and
  #       telemetry_collector_repository_arn, resolving the mirrored AWS Distro for
  #       OpenTelemetry collector image and authorizing the execution role to pull it.
  #       Both are WITHDRAWN because infra/modules/ecs-service no longer composes a
  #       collector sidecar, and it no longer does so because the sidecar was outside
  #       the frozen specification and was forcing two topology changes that are also
  #       outside it -- an eleventh ECR repository to mirror a public image into,
  #       against the ten that section 0.4.1.6 states, and a ninth interface endpoint
  #       for xray, against the eight that section 0.4.1.9 states. The module records
  #       the full argument and the alternatives weighed against it.
  #       Trade-offs: what this root loses is span export to a managed tracing
  #       backend. What it keeps is every observability artifact the specification
  #       actually names: container logs in each workload's own group, the Actuator
  #       Prometheus surface each service already exposes, the common metric tags
  #       common-lib's MetricsConfig applies, and end-to-end request correlation
  #       through common-lib's CorrelationIdFilter.
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
  sqs_receive_queue_arns = lookup(local.sqs_permissions_by_workload, each.key, local.no_queue_permissions).receive
  sqs_send_queue_arns    = lookup(local.sqs_permissions_by_workload, each.key, local.no_queue_permissions).send

  # WHY : Assumptions: the key is passed only to the four workloads that hold queue
  #       permissions, and null to the rest, so no task role carries a key grant for a
  #       queue it cannot use. The module emits the statement only when a queue list is
  #       non-empty, so these two conditions agree by construction rather than by a
  #       reader checking them against each other.
  # WHY : Refactoring Rationale: this replaces three per-service grants of the same key
  #       written directly into account_runtime, reference_runtime and
  #       authorization_runtime, which were UNCONDITIONED -- and a fourth that was
  #       simply absent from batch_runtime. Passing it here instead means the grant
  #       carries the module's kms:ViaService and per-queue encryption-context
  #       conditions, which an inline grant in this root did not, and means batch gets
  #       the grant its error-queue send has always required.
  sqs_kms_key_arn         = contains(keys(local.sqs_permissions_by_workload), each.key) ? module.kms.sqs_key_arn : null
  create_task_role_policy = contains(keys(local.task_role_policy_json), each.key)
  task_role_policy_json   = lookup(local.task_role_policy_json, each.key, null)
  # WHY : Assumptions: the list is per workload rather than one shared value, because
  #       data-migration reads a secret this root did not create and therefore one
  #       protected by a key this root does not own. Granting the union for that
  #       workload alone keeps every other task's grant at the single CMK.
  # WHY : Trade-offs: for data-migration the statement names two keys and conditions on
  #       that workload's secret ARNs, so the grant is nominally the cross-product of
  #       both. The effective privilege is still exact -- a key decrypts only the
  #       ciphertext sealed under it -- and the alternative, one statement per key with
  #       its own secret-ARN condition, would require the module to accept a key-to-
  #       secret mapping rather than a list, for no narrowing that IAM can enforce.
  execution_secret_kms_key_arns = local.secret_kms_key_arns_by_workload[each.key]

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

  # WHY : Assumptions: the SAME account boundary goes to every module that creates a
  #       role, so "every role this deployment creates" means one ceiling rather than a
  #       per-module one. infra/modules/ecs-service has always received it; network,
  #       step_functions and eventbridge_scheduler did not, which is why their roles were
  #       unbounded while this root's variable description claimed otherwise.
  permissions_boundary_arn = var.permissions_boundary_arn
}

# WHY : Assumptions: this resource asserts at PLAN time that the window in which the nightly
#       chain can be STARTED does not intersect the Aurora backup window or the weekly
#       maintenance window. It creates nothing; it exists only to turn a three-value coupling
#       into a plan-time failure.
# WHY : Refactoring Rationale: this coupling was previously carried by a comment on
#       module.aurora alone, which asked a reader to re-derive it from two tfvars entries and
#       a module default. That is exactly the kind of invariant that survives review and then
#       breaks when someone moves one of the three values for an unrelated reason, because
#       nothing recomputes the relationship. The comment stays -- it explains WHY the
#       separation matters -- and this resource makes it checkable.
# WHY : Assumptions: the interval asserted is the START window, not the whole run. It runs
#       from the cron hour to the cron hour plus var.batch_schedule_maximum_event_age_seconds,
#       because a delivery that fails is retried for up to that long and a RETRIED delivery
#       starts the chain at whatever time it eventually succeeds. Bounding that age is what
#       makes the 02:00 choice mean anything; the reasoning for the specific value is recorded
#       at the variable. The chain's own 17-hour ceiling deliberately is NOT asserted against
#       these windows: a long run may overlap a backup, which is tolerated for the reason
#       recorded at module.aurora's preferred_backup_window.
# WHY : Assumptions: neither window is permitted to wrap midnight, and that is asserted rather
#       than assumed. Arithmetic on wrapped intervals needs case analysis that would make this
#       gate harder to read than the invariant it protects, and no CardDemo environment has a
#       reason to straddle midnight -- so the wrap case is refused with a message that says to
#       split the window instead.
locals {
  # Parse `cron(<minute> <hour> ...)` in the scheduler module's default UTC timezone.
  batch_cron_fields = split(" ", trimsuffix(trimprefix(var.batch_schedule_expression, "cron("), ")"))
  batch_start_hour  = tonumber(local.batch_cron_fields[1])

  # The START window: the scheduled hour plus the bounded delivery-retry age.
  batch_start_begin = local.batch_start_hour
  batch_start_end   = local.batch_start_hour + (var.batch_schedule_maximum_event_age_seconds / 3600)

  # Backup window "hh:mm-hh:mm"; maintenance window "ddd:hh:mm-ddd:hh:mm".
  backup_begin_hour = tonumber(split(":", split("-", var.aurora_preferred_backup_window)[0])[0])
  backup_end_hour   = tonumber(split(":", split("-", var.aurora_preferred_backup_window)[1])[0])

  maintenance_begin_hour = tonumber(split(":", split("-", var.aurora_preferred_maintenance_window)[0])[1])
  maintenance_end_hour   = tonumber(split(":", split("-", var.aurora_preferred_maintenance_window)[1])[1])
}

resource "terraform_data" "batch_window_disjoint" {
  input = {
    batch_start_window = "${local.batch_start_begin}:00-${local.batch_start_end}:00 UTC"
    backup_window      = var.aurora_preferred_backup_window
    maintenance_window = var.aurora_preferred_maintenance_window
  }

  lifecycle {
    precondition {
      condition     = local.batch_start_end > local.batch_start_begin && local.batch_start_end <= 24 && local.backup_end_hour > local.backup_begin_hour && local.maintenance_end_hour > local.maintenance_begin_hour
      error_message = "The batch start window, the Aurora backup window and the maintenance window must each begin and end on the same UTC day; one of them wraps midnight. The batch start window wraps when the cron hour plus batch_schedule_maximum_event_age_seconds passes 24:00 -- shorten that age rather than widening this check, because interval arithmetic across midnight cannot be expressed here without case analysis that would obscure the invariant."
    }

    precondition {
      condition     = local.batch_start_end <= local.backup_begin_hour || local.batch_start_begin >= local.backup_end_hour
      error_message = "The window in which the nightly batch chain can be STARTED overlaps the Aurora backup window. A chain that begins as a backup begins puts a fresh fan-out of loader tasks against the cluster at its least available moment. Move batch_schedule_expression, move aurora_preferred_backup_window, or shorten batch_schedule_maximum_event_age_seconds -- but do not move them onto each other."
    }

    precondition {
      condition     = local.batch_start_end <= local.maintenance_begin_hour || local.batch_start_begin >= local.maintenance_end_hour
      error_message = "The window in which the nightly batch chain can be STARTED overlaps the Aurora weekly maintenance window. Maintenance may fail the cluster over, which would abort in-flight loader tasks and leave the online-write bracket engaged until the finalizer rule clears it. Move batch_schedule_expression or aurora_preferred_maintenance_window."
    }
  }
}

# WHY : Assumptions: this resource is a plan-time-only gate that is satisfied once EVERY
#       resource in the dataset-retention invocation path exists. It creates nothing; its sole
#       purpose is to give the nightly schedule below one dependency edge that covers the whole
#       path.
# WHY : Refactoring Rationale: the schedule used to depend on `aws_iam_role_policy.dataset_retention_s3`
#       ALONE, and that single edge covered only one of the four ways the retention path can be
#       incomplete when the first cron fires. The hazard it was written for is real -- a schedule is
#       live the moment it is created, so it can start the chain before Terraform finishes the
#       retention wiring -- but an IAM policy is the LAST of the four to matter. Without
#       `aws_lambda_function.dataset_retention` there is no function to invoke; without
#       `aws_lambda_permission.dataset_retention_from_s3` S3 is not allowed to invoke it; without
#       `aws_s3_bucket_notification.dataset_generations` S3 does not even attempt to, so no
#       invocation is recorded anywhere. Those three failures are strictly worse than the denied
#       S3 call the original edge guarded, because a denied call at least appears in the function's
#       own logs, whereas a missing notification produces no invocation, no log and no metric.
# WHY : Assumptions: naming all four in one `terraform_data` is preferred to naming all four in the
#       module's `depends_on`. Both order the apply identically. This form additionally gives the
#       relationship a name a reader can search for, and keeps the reasoning in one place rather
#       than repeating it at the module block -- which matters because the identical gate exists in
#       the prod root and the two must not drift.
# WHY : Trade-offs: `input` is used rather than `triggers_replace`, because nothing here needs to be
#       REPLACED when the retention path changes; the resource exists only to carry ordering. Using
#       `triggers_replace` would churn this resource on every function-code change for no effect.
resource "terraform_data" "dataset_retention_path_ready" {
  input = {
    function_arn        = aws_lambda_function.dataset_retention.arn
    s3_policy_id        = aws_iam_role_policy.dataset_retention_s3.id
    invoke_permission   = aws_lambda_permission.dataset_retention_from_s3.id
    bucket_notification = aws_s3_bucket_notification.dataset_generations.id
  }
}

module "eventbridge_scheduler" {
  source = "../../modules/eventbridge-scheduler"

  name_prefix             = var.name_prefix
  environment             = var.environment
  state_machine_arn       = module.step_functions.daily_state_machine_arn
  dead_letter_arn         = module.sqs.error_queue_arn
  dead_letter_kms_key_arn = module.kms.sqs_key_arn

  # WHY : Assumptions: the boundary is an account-level policy this deployment does not
  #       create, for the reason recorded on every other call that passes it -- a boundary
  #       a deployment can rewrite bounds nothing. The role it bounds here is the
  #       scheduler's invocation role, whose only capability is StartExecution on the
  #       daily state machine; the boundary is passed anyway so that capability cannot be
  #       widened past the account ceiling by a later edit to that module.
  permissions_boundary_arn = var.permissions_boundary_arn
  # WHY : Assumptions: this cron is the other half of the backup-window coupling noted
  #       on module.aurora's preferred_backup_window above. The two are set in different
  #       module calls and nothing enforces their disjointness, so neither may be moved
  #       onto the other. It replaces the intent of app/scheduler/CardDemo.ca7 and
  #       CardDemo.controlm, not their syntax -- those definitions are retired rather
  #       than ported.
  schedule_expression = var.batch_schedule_expression

  # WHY : Assumptions: the scheduler module defaults this to 86400 -- the top of the
  #       accepted range -- and the root NARROWS it. The reasoning is recorded at the
  #       variable; in one line, a 24-hour delivery-retry age lets a retried trigger start
  #       the chain at any hour of the following day, which discards the whole point of
  #       scheduling it at 02:00. terraform_data.batch_window_disjoint above asserts the
  #       resulting start window against the backup and maintenance windows.
  maximum_event_age_in_seconds = var.batch_schedule_maximum_event_age_seconds
  kms_key_arn                  = module.kms.s3_key_arn

  # WHY : Assumptions: this edge is NOT redundant, which is the default expectation for
  #       an explicit depends_on and the reason it is justified rather than left bare.
  #       The schedule references none of the retention resources -- it takes only the
  #       state-machine ARN and the two keys -- so Terraform infers no ordering between
  #       them and reference inference cannot see the relationship at all.
  # WHY : Assumptions: the relationship is real because a schedule is LIVE the moment it
  #       is created. It starts the nightly chain, whose staging state writes dataset
  #       generations, and each of those writes notifies the retention function that
  #       enforces the five-generation limit. Created before that path is complete, the
  #       schedule can fire into a gap: the generations are written and the pruning
  #       silently fails, which is the one failure mode in this chain that produces no
  #       error and no alarm -- objects accumulate past LIMIT(5) while every state
  #       reports success.
  # WHY : Refactoring Rationale: the target is the readiness gate above rather than the
  #       S3 policy alone. The policy is only one of the four resources that path needs,
  #       and it is the one whose absence fails most VISIBLY; the reasoning for covering
  #       all four is recorded at the gate.
  # WHY : Trade-offs: the edge serialises the schedule behind the retention path, costing
  #       a little apply parallelism. Accepted because the alternative is a first apply
  #       whose correctness depends on the cron not firing before Terraform reaches the
  #       last of those resources -- a race whose outcome varies with the time of day the
  #       apply is run.
  depends_on = [terraform_data.dataset_retention_path_ready]
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
  # WHY : Refactoring Rationale: the four execution log groups the batch module publishes
  #       are passed HERE, and until this argument existed they were published and
  #       consumed by nothing -- while that module's output descriptions stated that
  #       observability attached metric filters and log-based alarms to them. Wiring them
  #       closes a real gap rather than only a documentation one: the AWS/States alarms
  #       the module raises are dimensioned on the daily machine's ARN alone, so a
  #       failure of the ad-hoc report, dataset round-trip or authorization-extract
  #       machine produced no signal at all.
  # WHY : Assumptions: NAMES rather than ARNs, because a metric filter is created against
  #       a log-group name; the matching ARN outputs stay unconsumed by design and are
  #       described in the owning module as identities for policy scoping and discovery.
  # WHY : Assumptions: all four machines are passed, keyed exactly as that module keys its
  #       execution roles -- daily, adhoc, dataset, authz -- so an operator reading an
  #       alarm name, an execution-role name and a log group is reading one key set
  #       rather than three spellings of one.
  state_machine_log_group_names = {
    daily   = module.step_functions.daily_log_group_name
    adhoc   = module.step_functions.adhoc_report_log_group_name
    dataset = module.step_functions.dataset_roundtrip_log_group_name
    authz   = module.step_functions.authorization_extract_log_group_name
  }
  vpc_flow_log_group_name    = module.network.flow_log_group_name
  cloudfront_distribution_id = module.cloudfront_spa.distribution_id
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
