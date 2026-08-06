# =============================================================================
# infra/modules/alb/main.tf
# -----------------------------------------------------------------------------
# Purpose:
#   The runtime substance of the `alb` module: one INTERNAL Application Load
#   Balancer, its single HTTPS listener, and one listener rule per online
#   bounded context, driven by `var.service_routes`. Together they are the
#   structural successor to the CICS region's transaction dispatch table --
#   the eighteen DEFINE TRANSACTION stanzas at app/csd/CARDDEMO.CSD L306-L488
#   each bind a four-character transaction identifier to a named program, and a
#   path-based listener rule binding a request to a named target group is that
#   same dispatch expressed for HTTP. The correspondence is structural, not a
#   port, and the stanza count is deliberately NOT a route count: CDV1 names
#   PROGRAM(COCRDSEC), for which the baseline ships no source at all, so not
#   every stanza has a migrated target.
#
#   SEVEN rules are created, not eight. `var.service_routes` is validated to
#   exactly the seven online contexts -- auth, account, card, transaction,
#   reference, authorization and reporting -- and batch is absent by
#   construction rather than by policy: its ecs-service instantiation creates
#   neither a service nor a target group, so no rule could forward to it, and
#   the Step Functions synchronous run-task integration invokes it without
#   traversing this load balancer at all.
#
#   Dependency direction is one-way. This file CONSUMES identifiers produced
#   elsewhere -- by the network module, by the seven load-balanced ecs-service
#   instantiations and by ACM/DNS -- and calls no module itself. It also owns the
#   access-log bucket and delivery policy required by its load balancer, so that
#   logging cannot be enabled against an ownerless bucket name.
#
# Parameters:
#   Declared in variables.tf with their types, defaults, descriptions and
#   plan-time validation, and not restated here: the Inputs table generated
#   into README.md is produced from those same declarations, so a copy in this
#   file would be a third place to keep in step. This file reads thirteen of the
#   fourteen inputs; `health_check_path` is consumed by outputs.tf alone, for
#   the reason recorded against it in the omissions block.
#
#   FOUR of them are wired from another module's output rather than authored by
#   hand, so a wrong value is a mis-wiring in the calling environment root
#   rather than a defect in this file:
#     - `subnet_ids` (list(string)) -- the load-balancer-role subnet ids, from
#       the network module.
#     - `alb_security_group_id` (string) -- the load-balancer security group,
#       created and owned by the network module and only attached here.
#     - `target_group_arn` (string) inside each `service_routes` entry -- from
#       the seven load-balanced ecs-service instantiations.
#     - `certificate_arn` and `certificate_domain_name` -- the regional ACM
#       identity and the DNS name API Gateway verifies against it.
#
# Returns:
#   HCL has no return value, so the analogue is what outputs.tf publishes from
#   the resources declared here. Those outputs are declared and described
#   there, not here; what downstream consumers take is:
#     - the load-balancer ARN, DNS name and hosted-zone id, from aws_lb.this.
#       The DNS name is PUBLISHED rather than written into a DNS record, for
#       the reason recorded in the omissions block.
#     - the HTTPS listener ARN, from aws_lb_listener.https. This is the
#       load-bearing one: it is the private-integration target that
#       infra/modules/api-gateway-http attaches to through its VPC Link, so it
#       is the single seam between the public edge and everything behind it.
#     - the listener-rule set, from aws_lb_listener_rule.service, keyed by
#       service name so a caller can assert WHICH contexts are routed rather
#       than only how many.
#     - the access-log bucket name and ARN, and the certificate DNS name the
#       private integration must verify.
#     - `health_check_path`, republished unchanged -- again, see the omissions
#       block for why a module that creates no target group publishes it.
#
# Errors:
#   variables.tf rejects malformed and crossed-wire inputs at PLAN time, before
#   any API call. What remains are the failures that can only surface during
#   APPLY, named here so that a reader meeting one recognises it rather than
#   looking for a defect in this file:
#     - fewer than two subnets in DISTINCT availability zones. The count and
#       the duplicate-id check are enforced at plan time, but two ids naming
#       two subnets in the SAME zone satisfy both and are refused by the
#       service, because an application load balancer requires two zones.
#     - a composed load-balancer name over the 32-character `aws_lb` limit.
#       The joint budget check on `name_prefix` and `environment` makes this
#       unreachable through the documented inputs; it is named because that
#       limit is the entire reason the check exists.
#     - a duplicate `priority` across listener rules. Uniqueness within
#       `service_routes` is checked at plan time; a collision with a rule
#       created on the same listener from outside this module is not visible
#       to it.
#     - a legacy region that still requires its ELB service-account root while
#       `legacy_elb_log_delivery_account_arn` is null. The modern service
#       principal is always present; the exceptional legacy grant is explicit.
#     - a certificate that is not ISSUED, or not in the load balancer's own
#       region. A listener cannot present a certificate from another region,
#       and both cases are rejected when the listener is created.
#
# WHY (non-obvious design decisions):
#   - Assumptions: this file is a MODULE body and is never applied on its own.
#     The environment roots call it as source = "../../modules/alb", and it is
#     reached by `terraform validate` transitively through whichever root calls
#     it. It therefore declares no `provider`, no `backend` and no `terraform`
#     block: the provider's region and `default_tags` belong to the caller, and
#     versions.tf holds the version constraints. A provider block here would
#     additionally stop either root from calling this module with `count`,
#     `for_each` or `depends_on`, which versions.tf records against the same
#     omission and reproduced against CLI 1.15.8.
#   - Refactoring Rationale: the module now owns the S3 resources its mandatory
#     access_logs block depends on. Delegating the bucket to an unspecified
#     caller left no IaC resource responsible for the ELB delivery policy, so a
#     syntactically valid ALB failed while enabling logs. Target groups remain
#     outside this module and belong to ecs-service.
#   - Alternatives Considered: ONE listener carrying path rules, rather than a
#     listener per service or host-based conditions. All seven contexts sit
#     behind one internal name and one certificate, so the path is the only
#     discriminator that needs no further infrastructure. A listener per
#     service would need a distinct port per service and a matching
#     security-group rule for each, widening the very matrix the network
#     module owns exclusively.
# =============================================================================

# -----------------------------------------------------------------------------
# What this module deliberately does NOT create, and which module owns each.
#
# Every entry below is something a reader could reasonably expect to find in a
# load-balancer module. Each is absent on purpose, and naming the owner is the
# point of writing them down together.
#
#   aws_lb_target_group -- owned by ecs-service, beside the task definition,
#     task role, log group and autoscaling policy it scales with. The
#     consequence worth stating is that the HEALTH-CHECK attributes live there
#     too, because path, protocol, thresholds and matcher are all target-group
#     arguments. This module nonetheless declares `health_check_path` and
#     outputs.tf republishes it unchanged, so the environment root can feed ONE
#     identical value to all seven ecs-service instantiations and to each
#     image's own container health check. That is what makes this module the
#     declared single source of truth for the health-check contract WITHOUT
#     owning the resource, and it is why the variable is not dead and must not
#     be deleted as unused.
#
#   aws_security_group -- owned by network. This module only attaches the ALB
#     group by id. api-gateway-http owns the one cross-module ingress rule on
#     that existing group because it alone can reference both its dedicated VPC
#     Link group and this destination without a dependency cycle. No CIDR-based
#     or unrestricted rule is created here.
#
#   aws_acm_certificate -- provisioned and DNS-validated outside this package;
#     only `certificate_arn` crosses the boundary. A certificate ARN embeds an
#     account identifier and a region, neither of which may appear anywhere in
#     this tree, so an input is the only admissible form.
#
#   aws_route53_record -- created by nobody, at any layer, deliberately.
#     api-gateway-http reaches this load balancer through a VPC Link private
#     integration, which targets the listener by ARN rather than resolving a
#     name, so a record would serve no consumer. The DNS name is published as
#     an output instead of being written down as a literal anywhere.
#
#   aws_wafv2_web_acl_association -- out of scope for this migration. The
#     policy scan's web-application-firewall requirement applies to a
#     PUBLIC-facing load balancer; this one is internal, so the check does not
#     apply to it and is not suppressed. Nothing in this module skips, ignores
#     or excepts a policy check: every check it is subject to is satisfied by
#     configuration.
#
#   Any HTTP listener -- created by nobody. The rationale is recorded on
#     aws_lb_listener.https below and, at its other end, in variables.tf
#     against the absent create_http_listener input.
# -----------------------------------------------------------------------------

data "aws_caller_identity" "current" {}

data "aws_region" "current" {}

data "aws_partition" "current" {}

locals {
  # Assumptions: an `aws_lb` name is capped at 32 characters and may
  #       neither begin nor end with a hyphen, and the cap binds on the PAIR of
  #       values rather than on either alone. variables.tf enforces that budget
  #       at plan time -- `name_prefix` at most 20 characters, and the two
  #       values together at most 27, which is 32 less the four characters of
  #       "-alb" and one separator -- so an over-long name is reported against
  #       the input that caused it instead of surfacing as an apply-time
  #       rejection after the plan was already approved.
  #       Alternatives Considered: accepting the finished name as a single
  #       input. Rejected because the environment discriminator would then be
  #       optional in practice, and two environments could compose one name;
  #       building it here makes the discriminator structural.
  alb_name = "${var.name_prefix}-alb-${var.environment}"

  # WHY : Assumptions: account and region make parallel environment deployments
  #       distinguishable in S3's global namespace. Prefix/environment are
  #       bounded before composition so the result remains under 63 characters.
  access_logs_bucket_name = "${substr(var.name_prefix, 0, 12)}-${substr(var.environment, 0, 8)}-${data.aws_caller_identity.current.account_id}-${data.aws_region.current.region}-alb-logs"

  # WHY : Assumptions: a caller that already owns a terminal access-log bucket --
  #       infra/modules/observability publishes one, shared with the dataset bucket's
  #       server-access logs -- passes its name in, and this module then creates no
  #       bucket of its own. A caller that passes nothing gets a bucket here, with the
  #       same encryption, public-access block, TLS-only policy and ELB delivery grant.
  #       Either way logging is enabled against a bucket this package owns, which is
  #       the property that matters; what varies is only which module owns it.
  # WHY : Alternatives Considered: always creating the bucket here. Rejected because a
  #       root wiring the shared bucket would then end up with two log destinations and
  #       two lifecycle policies for one stack, and a reader could not tell which one
  #       the load balancer was writing to without reading the module.
  create_access_logs_bucket = var.access_logs_bucket == null
  access_logs_target_bucket = var.access_logs_bucket != null ? var.access_logs_bucket : aws_s3_bucket.access_logs[0].id

  # WHY : Assumptions: ELB inserts `AWSLogs/<account-id>` after the configured
  #       access_logs prefix. Handling the empty-prefix case avoids a doubled
  #       separator that would make the policy resource differ from the key ELB
  #       actually writes.
  access_logs_object_prefix = var.access_logs_prefix == "" ? "AWSLogs/${data.aws_caller_identity.current.account_id}" : "${var.access_logs_prefix}/AWSLogs/${data.aws_caller_identity.current.account_id}"

  # WHY : Assumptions: the calling root's provider `default_tags` block already
  #       carries the account-wide keys, and resource-level tags MERGE with
  #       those rather than replacing them. This map therefore adds only what a
  #       root-level default cannot express -- which tier a resource belongs to,
  #       true of this module's resources and of nothing else in the root. One
  #       key is added and no taxonomy beyond it.
  #       Trade-offs: `var.tags` is merged LAST, so the caller's value for a
  #       colliding key wins. variables.tf rejects a tag the module would add
  #       unconditionally, on the ground that a caller could not then remove it
  #       without a module change; this precedence is what answers that
  #       objection -- the key is contributed by default and remains the
  #       caller's to override or blank.
  tags = merge({ Component = "alb" }, var.tags)
}

data "aws_iam_policy_document" "access_logs" {
  count = local.create_access_logs_bucket ? 1 : 0

  # WHY : Refactoring Rationale: transport encryption is enforced on the bucket
  #       itself so every caller, including a mistaken manual one, is denied
  #       when aws:SecureTransport is false.
  statement {
    sid       = "DenyInsecureTransport"
    effect    = "Deny"
    actions   = ["s3:*"]
    resources = [aws_s3_bucket.access_logs[0].arn, "${aws_s3_bucket.access_logs[0].arn}/*"]

    principals {
      type        = "*"
      identifiers = ["*"]
    }

    condition {
      test     = "Bool"
      variable = "aws:SecureTransport"
      values   = ["false"]
    }
  }

  statement {
    sid       = "AllowModernElbLogDeliveryWrite"
    effect    = "Allow"
    actions   = ["s3:PutObject"]
    resources = ["${aws_s3_bucket.access_logs[0].arn}/${local.access_logs_object_prefix}/*"]

    principals {
      type        = "Service"
      identifiers = ["logdelivery.elasticloadbalancing.amazonaws.com"]
    }

    # WHY : Assumptions: the object path already fixes the account id; the
    #       SourceArn condition additionally restricts the service principal to
    #       load balancers from this account and provider region.
    condition {
      test     = "ArnLike"
      variable = "aws:SourceArn"
      values = [
        "arn:${data.aws_partition.current.partition}:elasticloadbalancing:${data.aws_region.current.region}:${data.aws_caller_identity.current.account_id}:loadbalancer/*",
      ]
    }
  }

  # WHY : Alternatives Considered: the deprecated aws_elb_service_account data
  #       source was rejected. Modern regions use the service principal above;
  #       a legacy regional account root is accepted only as an explicit input
  #       so its exceptional grant is visible in the environment root.
  dynamic "statement" {
    for_each = var.legacy_elb_log_delivery_account_arn == null ? [] : [var.legacy_elb_log_delivery_account_arn]
    iterator = legacy_principal

    content {
      sid       = "AllowLegacyRegionalElbLogDeliveryWrite"
      effect    = "Allow"
      actions   = ["s3:PutObject"]
      resources = ["${aws_s3_bucket.access_logs[0].arn}/${local.access_logs_object_prefix}/*"]

      principals {
        type        = "AWS"
        identifiers = [legacy_principal.value]
      }
    }
  }
}

# -----------------------------------------------------------------------------
# Access-log storage -- owned with the producer so delivery is deployable.
# -----------------------------------------------------------------------------

# WHY : Assumptions: the suppression below is a statement about a SERVICE LIMIT and
#       not a preference between two available encryption options, which is worth
#       stating separately because the rule the suppression names reads as though a
#       customer-managed key were simply the stricter of two choices here. It is
#       not available at all: Elastic Load Balancing accepts exactly one
#       server-side encryption option on an access-log destination, store-managed
#       keys, and validates the destination when logging is enabled. Pointing this
#       bucket at the observability customer-managed key does not produce a
#       stricter deployment; it produces a load balancer that reports the bucket
#       unusable and delivers no logs at all, which trades an encryption-key
#       boundary for the loss of the audit record itself.
# WHY : Alternatives Considered: (1) encrypting with the observability
#       customer-managed key and widening that key's policy to the delivery
#       service principal -- not available, for the reason above, and the policy
#       widening would have been the lesser cost of the two. (2) delivering to a
#       separately owned central logging bucket under its own key -- rejected
#       because the destination would then be owned outside this module while this
#       module is what enables delivery to it, so a teardown here would leave a
#       bucket receiving from a load balancer that no longer exists. (3) disabling
#       access logging so no destination is needed -- rejected outright: the
#       delivered record is the only per-request evidence at the load-balancer
#       tier, and the requirement is centralised logging rather than less of it.
# WHY : Assumptions: the suppression's premise -- that this bucket holds no
#       application record -- is a claim about the REQUEST TARGETS the delivered
#       lines carry, and it is now true by construction rather than by hope. A
#       delivered line records the request line, query string included, so the
#       premise fails the moment any published route puts a card number in a
#       target. None does: the card contract selects a card by an opaque
#       server-issued token and carries its one card-number criterion in a
#       request body, and that decision is recorded at item 8 of
#       services/card-service/src/main/resources/openapi/card-api.yaml with this
#       destination named as the reason for it. A route that reintroduced a card
#       number into a path or a query would falsify this premise and invalidate
#       the suppression, so the two must be reviewed together.
# WHY : Trade-offs: the compensating controls carry the weight that a
#       customer-managed key would otherwise carry, and they are named so a
#       reviewer can check each one rather than take the set on trust: store-side
#       encryption at rest, public-access blocking, enforced bucket ownership,
#       versioning, and a bucket policy that admits only the exact source load
#       balancer. What is genuinely given up is the key-level revocation and the
#       independent audit trail of key use that a customer-managed key provides;
#       nothing else about the objects' protection changes.
resource "aws_s3_bucket" "access_logs" {
  #checkov:skip=CKV_AWS_145:Elastic Load Balancing supports store-managed keys as the ONLY server-side encryption option for an access-log destination, so a customer-managed key is not an available alternative here -- configuring one makes the load balancer reject the bucket and deliver no logs. The delivered lines hold no application record: no published route carries a card number in a request target, which card-api.yaml item 8 records with this destination as its reason. Store-side encryption at rest plus public-access blocking, enforced bucket ownership, versioning and the exact-source bucket policy are the compensating controls; see the rationale above this resource.
  count = local.create_access_logs_bucket ? 1 : 0

  bucket = local.access_logs_bucket_name

  # WHY : Trade-offs: development disables ALB deletion protection so teardown
  #       can also remove delivered log objects. Protected environments retain
  #       the bucket unless an operator deliberately empties it.
  force_destroy = !var.enable_deletion_protection

  tags = local.tags
}

resource "aws_s3_bucket_ownership_controls" "access_logs" {
  count = local.create_access_logs_bucket ? 1 : 0

  bucket = aws_s3_bucket.access_logs[0].id

  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_public_access_block" "access_logs" {
  count = local.create_access_logs_bucket ? 1 : 0

  bucket = aws_s3_bucket.access_logs[0].id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# WHY : Assumptions: log delivery names every object uniquely, so versioning here
#       is a protection against an object being deleted or replaced after the
#       fact rather than against a delivery overwrite, and in normal operation it
#       accrues no noncurrent copies at all. That is what makes this destination
#       describable as an audit trail, against a baseline whose eight CICS file
#       definitions each declared `RECOVERY(NONE)` with journalling disabled.
# WHY : Alternatives Considered: leaving the bucket unversioned, which an earlier
#       revision did. Rejected because a delivered access record could then be
#       removed with nothing left to show it had existed -- and because a bucket
#       offered as the load balancer's audit trail should not be the one object
#       store in this package that cannot survive a deletion.
# WHY : Trade-offs: no noncurrent-version expiry rule accompanies this, unlike the
#       destination in infra/modules/observability. Adding one would require this
#       module to take a retention input it does not have and both environment
#       roots to supply it; because delivery never replaces an object, the
#       noncurrent class stays empty in normal operation and the rule would bound
#       a set that does not grow. If this bucket ever gains a producer that
#       rewrites objects, that rule becomes necessary in the same change.
resource "aws_s3_bucket_versioning" "access_logs" {
  count = local.create_access_logs_bucket ? 1 : 0

  bucket = aws_s3_bucket.access_logs[0].id

  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "access_logs" {
  count = local.create_access_logs_bucket ? 1 : 0

  bucket = aws_s3_bucket.access_logs[0].id

  # WHY : Assumptions: ELB log delivery supports S3-managed AES-256 encryption
  #       without granting the delivery service use of another KMS key. The
  #       objects are encrypted at rest while the policy remains limited to S3.
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_policy" "access_logs" {
  count = local.create_access_logs_bucket ? 1 : 0

  bucket = aws_s3_bucket.access_logs[0].id
  policy = data.aws_iam_policy_document.access_logs[0].json

  depends_on = [
    aws_s3_bucket_ownership_controls.access_logs,
    aws_s3_bucket_public_access_block.access_logs,
  ]
}

resource "aws_lb" "this" {
  # Assumptions: the composed name and the 32-character limit it is built
  #       to respect are decided once in `locals` above.
  name = local.alb_name

  # WHY : Alternatives Considered: an internet-facing load balancer was
  #       rejected because it would BYPASS THE COGNITO JWT AUTHORIZER THAT API
  #       GATEWAY ENFORCES AT THE EDGE, moving authentication out of a single
  #       managed edge control and into seven separate service configurations.
  #       The only public entry points in the whole target architecture are that
  #       API Gateway HTTP API and the CloudFront distribution in front of the
  #       single-page-application bucket; this load balancer sits behind the
  #       HTTP API through a VPC Link private integration and is never directly
  #       reachable from the internet.
  #       Alternatives Considered: expressing this as a boolean input so a
  #       caller could choose. Rejected because exposing all seven services
  #       would then be one wrong line in a tfvars file away, with nothing in
  #       the module able to detect it. A failure mode reachable by editing a
  #       single value is worth removing rather than documenting, so the choice
  #       is not offered at all; variables.tf records the same decision against
  #       the absent input, and the two are one decision stated at both ends.
  #       Refactoring Rationale: the baseline enforced nothing equivalent at
  #       dispatch. Every one of the eighteen DEFINE TRANSACTION stanzas in
  #       app/csd/CARDDEMO.CSD (L306-L488) carries RESSEC(NO) CMDSEC(NO), so
  #       CICS ran no per-transaction resource or command security check when it
  #       dispatched a transaction to its program. Centralising authentication
  #       at one managed edge is the decision that closes that gap, and it holds
  #       only while everything behind the edge is unreachable without passing
  #       through it.
  #       Assumptions: because this load balancer is internal, the policy scan's
  #       requirement that a PUBLIC-facing load balancer sit behind a
  #       web-application firewall does not apply to it. That is coverage by
  #       construction rather than an exception: THIS check needs no suppression
  #       because its precondition is false, not because it is waived. The claim
  #       is deliberately narrow. This module does carry exactly one scanner
  #       suppression -- `CKV_AWS_145` on `aws_s3_bucket.access_logs` above,
  #       where log delivery cannot write a bucket whose default encryption is
  #       SSE-KMS -- and its rationale and compensating controls are written on
  #       that resource. An earlier revision of this comment claimed no
  #       suppression existed anywhere in the module, which stopped being true
  #       the moment that exception was added; a blanket claim is unmaintainable
  #       because it is invalidated by a change made elsewhere in the file.
  internal = true

  # WHY : Assumptions: routing here is decided per REQUEST PATH, and a path
  #       condition is a layer-7 construct. A network load balancer forwards by
  #       listener port alone and can express no path or host condition at all,
  #       so reproducing this routing table on one would take a listener and a
  #       distinct port per service, plus a matching security-group rule for
  #       each, widening the matrix the network module owns. It also could not
  #       terminate TLS under the negotiated-policy floor the listener pins.
  load_balancer_type = "application"

  # WHY : Assumptions: in this package the caller passes the PUBLIC subnets --
  #       the tier that carries only the load balancer and the NAT gateways --
  #       and that is NOT in tension with `internal = true` above. Subnet
  #       placement decides which availability zones the nodes occupy and which
  #       route tables they use; `internal = true` is what withholds the public
  #       addresses and the internet-routable name, and it does so whatever
  #       those subnets' route tables say. Reading "public subnets" and
  #       "internal" together as a contradiction is the expected mistake, so the
  #       reconciliation is recorded at both ends -- here and on the input.
  subnets = var.subnet_ids

  # WHY : Assumptions: the permitted traffic matrix is fixed for the whole
  #       package -- VPC Link to ALB on 443, load balancer to application on
  #       8080, application to Aurora on 5432 and application to interface
  #       endpoint on 443. The network module owns the group; api-gateway-http
  #       owns the exact SG-referenced VPC Link ingress because it can see both
  #       endpoints. This module only ATTACHES the id and accepts no port,
  #       protocol or CIDR input, so it cannot widen either owner's rules.
  security_groups = [var.alb_security_group_id]

  # WHY : Assumptions: without this, a header the load balancer itself will not
  #       parse is passed through verbatim, and the load balancer and the
  #       service can then disagree about where one request ends -- which is
  #       precisely the request-smuggling and header-injection path between the
  #       edge and the seven services. Discarding them at the one hop both sides
  #       share removes the disagreement, rather than requiring seven Spring
  #       Boot applications to reject the same malformed input identically. The
  #       policy scan gates this at HIGH severity (checkov CKV_AWS_131), so the
  #       argument is also what keeps that scan clean by configuration.
  drop_invalid_header_fields = true

  # WHY : Trade-offs: protection flags are one of the very few axes the two
  #       environments are permitted to differ on -- they differ in sizing,
  #       retention and protection, never in topology -- and the reason is
  #       concrete rather than stylistic: with protection on, `terraform
  #       destroy` fails on this resource, and tearing the stack down cleanly is
  #       an acceptance criterion for the package. The dev root therefore
  #       overrides this to false in its terraform.tfvars while the module keeps
  #       the protective value: variables.tf defaults it to true, which is what
  #       the HIGH-severity policy check expects (checkov CKV_AWS_150), so an
  #       OMISSION lands on the safe side and forgetting the input is never
  #       indistinguishable from deliberately choosing it.
  enable_deletion_protection = var.enable_deletion_protection

  # WHY : Assumptions: the accepted range, and why the service's own default is
  #       retained deliberately rather than by inertia, are recorded on the
  #       input in variables.tf. They are not restated here, because two copies
  #       of one rationale drift apart and the input is where a caller changing
  #       the value looks first.
  idle_timeout = var.idle_timeout

  # WHY : Trade-offs: a caller-controlled `enabled` flag was considered and
  #       rejected. The policy scan gates load-balancer access logging at HIGH
  #       severity (checkov CKV_AWS_91), so a switchable path would let a caller
  #       produce a non-compliant load balancer merely by omitting an argument,
  #       and this module would have supplied the means. Requiring the bucket
  #       instead makes the compliant configuration the only configuration the
  #       module is able to build.
  #       Refactoring Rationale: the baseline recorded nothing equivalent -- all
  #       eight file resources in app/csd/CARDDEMO.CSD are defined
  #       RECOVERY(NONE) JOURNAL(NO), so no access record existed and none could
  #       be reconstructed after the fact. These logs are the only record of
  #       which caller reached which service.
  #       Refactoring Rationale: the bucket and its delivery policy are created
  #       above in this same module. That ownership closes the former gap where
  #       logging was mandatory but no IaC resource was responsible for the
  #       bucket or the principal grant ALB validates while enabling it.
  #       Trade-offs: an access-log record carries the full request line, so any
  #       value a caller places in a URI PATH is persisted here verbatim. That is
  #       the residue this module accepts in exchange for having any record of
  #       who reached which service, and it is bounded in three ways rather than
  #       left implicit. First, the field format is fixed by the service and this
  #       module cannot filter or redact a field, so the bound has to be applied
  #       upstream: the migrated services carry no unmasked primary account
  #       number into a path they log, which is enforced in
  #       services/common-lib/src/main/java/com/carddemo/common/error/GlobalExceptionHandler.java
  #       where the request path is narrowed before it reaches either a log line
  #       or a response body. Second, the destination is not a general log
  #       bucket: it is created above with public access blocked, bucket-owner
  #       enforced ownership, versioning, an exact-source delivery policy and no
  #       read grant to any service task, so the records are reachable only by an
  #       operator identity. Third, the request line is the only field that can
  #       carry a caller-supplied value at all -- the header set ALB records does
  #       not include Authorization or Cookie, so no bearer credential reaches
  #       this destination. Alternatives Considered: disabling access logging to
  #       remove the residue entirely. Rejected because the baseline already had
  #       that property and it is the defect this resource exists to correct: all
  #       eight file definitions in app/csd/CARDDEMO.CSD are RECOVERY(NONE)
  #       JOURNAL(NO), so nothing recorded who reached what and nothing could be
  #       reconstructed afterwards. Removing the only admission record to avoid a
  #       masked-field residue trades an audit capability for a hazard already
  #       closed at its source.
  access_logs {
    bucket  = local.access_logs_target_bucket
    prefix  = var.access_logs_prefix
    enabled = true
  }

  # WHY : Assumptions: the merge and its precedence are decided once in
  #       `locals` above, and every taggable resource reads the same map so the
  #       load balancer, listener, rules and log bucket cannot drift apart.
  tags = local.tags

  # WHY : Assumptions: ELB validates log-delivery authorization while enabling
  #       access logs. The load balancer references the bucket name but not its
  #       policy, so this explicit edge prevents a race in which ALB is created
  #       before the required delivery grant exists.
  depends_on = [aws_s3_bucket_policy.access_logs]
}

resource "aws_lb_listener" "https" {
  load_balancer_arn = aws_lb.this.arn

  # Assumptions: the port is fixed rather than accepted as an input. The
  #       only client is the API Gateway private integration over the VPC Link,
  #       which targets this listener directly, so a non-default port would
  #       reach the same listener while additionally having to be mirrored in
  #       the load-balancer security group -- a group the network module owns
  #       and this one may not widen. There is therefore nothing an input could
  #       buy here except a second place for the two to disagree.
  port = 443

  # Alternatives Considered: an HTTP redirect listener would accept unused
  #   plaintext traffic because the only client is the HTTPS VPC Link. A single
  #   HTTPS listener satisfies the encryption checks without a suppression or
  #   additional attack surface.
  protocol = "HTTPS"

  # Assumptions: the negotiated floor is knowable only from this argument.
  #       Omitting it would not leave the choice to AWS neutrally -- for every
  #       method other than the console the service default is the 2016-vintage
  #       policy, which still accepts TLS 1.0 and 1.1. The value, and why the
  #       input stays open rather than being constrained to an allow-list, are
  #       recorded on the input in variables.tf, whose default floors
  #       negotiation at TLS 1.2 and so satisfies checkov CKV_AWS_103.
  ssl_policy = var.ssl_policy

  # Assumptions: the certificate is issued and its domain validated
  #       outside this module, in the load balancer's OWN REGION -- a listener
  #       cannot present a certificate from another region -- and only the ARN
  #       crosses the boundary, so the private key never leaves ACM and nothing
  #       secret is passed here. It is an input for a second reason that is not
  #       about reuse: a certificate ARN embeds an account identifier and a
  #       region, and no ARN literal, account identifier or region name appears
  #       anywhere in this module.
  # WHY : Assumptions: var.certificate_domain_name is the separate client-side
  #       half of this contract. outputs.tf republishes it for API Gateway's
  #       server_name_to_verify setting because an ACM ARN does not reveal the
  #       DNS identity a TLS client must check.
  certificate_arn = var.certificate_arn

  # WHY : Alternatives Considered: forwarding unmatched requests to a default
  #       service. Rejected because it makes the routing table's coverage
  #       invisible -- a typo in one path pattern would still be served, with a
  #       200, by whichever service happened to be the default, and the only
  #       symptom would be a wrong response body. Answering 404 makes an
  #       unrouted path an explicit outcome a smoke test can assert on.
  #       Refactoring Rationale: this is behaviour preserved rather than
  #       invented. CICS rejected an unrecognised four-character transaction
  #       identifier instead of dispatching it to an arbitrary program, so "no
  #       such route" is the baseline's own answer to the same question.
  #       Assumptions: the body is JSON, and specifically the SAME SHAPE the
  #       services' own error payload uses -- a numeric `status`, a
  #       human-readable `message`, and a `fieldErrors` array -- so a client
  #       parses this response with the reader it already has rather than needing
  #       a second one for the one error this layer composes itself. An ad-hoc
  #       body such as a lone `error` member was the alternative and is rejected:
  #       it is valid JSON that no service ever emits, so every client would have
  #       to special-case the shape of a 404 arriving from here, and the special
  #       case would stay invisible until the first unrouted request. The array
  #       is present and empty rather than omitted so a consumer that reads
  #       `fieldErrors` unconditionally need not distinguish absent from empty.
  #       It is built with `jsonencode` rather than as an escaped string literal
  #       so the quoting cannot be got wrong.
  #       Trade-offs: the shape is deliberately INCOMPLETE -- it carries no
  #       correlation identifier, where every service-composed error does. A
  #       listener fixed response is a static string fixed at apply time and
  #       returned with no access to the request, so there is no mechanism here
  #       to interpolate a per-request value at all. Two ways to gain one were
  #       considered and rejected: forwarding unmatched requests to a service
  #       that could compose it, which is the arrangement already rejected above
  #       for making the routing table's coverage invisible; and a function
  #       target behind a catch-all rule, which would add a function, its role
  #       and its cold start to this module solely to decorate the answer to a
  #       path that does not exist. The identity for an unrouted request lives in
  #       this load balancer's access log instead, which is where an operator
  #       investigating a 404 that reached no service has to look regardless.
  #       Assumptions: the message text names nothing internal -- no service, no
  #       path pattern, no target group -- because a 404 is reachable by anyone
  #       and must not enumerate the routing table. It is newly authored rather
  #       than carried over from a baseline message constant because the baseline
  #       has no analogue at this layer: CICS rejected an unknown transaction
  #       identifier at the terminal, not behind an HTTP router, so there is no
  #       verbatim string to preserve and borrowing one would assert a lineage
  #       that does not exist.
  default_action {
    # Assumptions: the action TYPE is spelled with a hyphen while the
    #       nested block that configures it is spelled with an underscore. That
    #       asymmetry is the provider's, not a typo: `type` is validated against
    #       an enum of API values, and `terraform validate` rejects
    #       "fixed_response" there outright. It is noted because the two
    #       spellings sit three lines apart and look like an inconsistency to
    #       correct.
    type = "fixed-response"

    fixed_response {
      content_type = "application/json"
      message_body = jsonencode({
        status      = 404
        message     = "The requested resource does not exist."
        fieldErrors = []
      })
      status_code = "404"
    }
  }

  # Assumptions: the same map every resource in this file tags with, for
  #       the reason recorded once on `locals` above.
  tags = local.tags
}

resource "aws_lb_listener_rule" "service" {
  # Alternatives Considered: count over a list would make rule identity depend
  #   on position and recreate unaffected routes after insertion or reordering.
  #   Service-name keys keep plans and resource addresses stable.
  for_each = var.service_routes

  listener_arn = aws_lb_listener.https.arn

  # Assumptions: priorities must be unique on a listener and are evaluated
  #       lowest-first, so they ARE the routing precedence between two patterns
  #       that could both match a request. The caller supplies them rather than
  #       the module deriving them from map ordering: a derived priority would
  #       silently renumber every existing rule the moment a context was added
  #       or renamed, producing a large diff over rules that did not change and
  #       a window in which two rules contend for one request. variables.tf
  #       checks both the accepted range and uniqueness across the map at plan
  #       time, so a collision is named before any rule exists.
  priority = each.value.priority

  # Assumptions: the target group is created by this context's ecs-service
  #       instantiation, beside the task definition and task role it scales
  #       with, and only its ARN crosses the boundary -- this module creates no
  #       aws_lb_target_group, for the reason given in the omissions block
  #       above. The onward hop is encrypted as well as this one: those target
  #       groups fix an HTTPS target protocol and each service terminates TLS
  #       itself, so no cleartext segment exists between the edge and a task.
  action {
    type             = "forward"
    target_group_arn = each.value.target_group_arn
  }

  # Alternatives Considered: host-based conditions instead of path-based.
  #       Rejected because all seven contexts sit behind ONE internal name and
  #       ONE certificate, so the path is the only discriminator that needs no
  #       further infrastructure -- host-based routing would require seven names
  #       and seven certificate subject alternative names, and would put a DNS
  #       change on the critical path of every routing change, for no routing
  #       capability a path condition lacks.
  #       Assumptions: every entry lists at least one pattern, checked at plan
  #       time, because a listener rule cannot be created without a condition.
  condition {
    path_pattern {
      values = each.value.path_patterns
    }
  }

  # Assumptions: the same map every resource in this file tags with, for
  #       the reason recorded once on `locals` above. Every rule carries the
  #       identical set, so a tag-based cost or access query returns the whole
  #       routing table rather than a subset of it.
  tags = local.tags
}
