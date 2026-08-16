# =============================================================================
# infra/modules/network/main.tf
# -----------------------------------------------------------------------------
# Purpose:
#   Provisions the complete single-Region network boundary for CardDemo: one
#   VPC spanning EXACTLY three availability zones; public, private-application
#   and isolated-data subnets in every zone; one NAT gateway per zone; private
#   interface paths to ten AWS services; an S3 gateway path; three security
#   groups governing every permitted tier-to-tier flow; and encrypted VPC flow
#   logs.
#   Refactoring Rationale: "four security groups" is corrected to three. A fourth
#   existed, carried only by the interface-endpoint ENIs; the frozen plan
#   specifies three (AAP section 0.5.1.12), so the ENIs now carry the application
#   group and the task-to-endpoint flow is a self reference on it. The full
#   reasoning, and the one permission that widens as a result, are recorded at the
#   rule pair itself.
#   Refactoring Rationale: "up to three" is corrected to "exactly three" because
#   nothing in this module or its inputs permits fewer. variables.tf constrains
#   az_count to the single value 3, and the VPC carries a precondition that fails
#   planning when the Region exposes fewer usable zones, so the module fails
#   CLOSED rather than degrading to two. "up to" described a flexibility that was
#   never implemented and would have let a reader plan a two-zone environment
#   that cannot exist.
#
# Parameters:
#   All thirteen inputs are declared, typed, described and validated in
#   variables.tf; this file only consumes them. name_prefix (string) and
#   environment (string) compose every resource name. vpc_cidr (string),
#   az_count (number) and subnet_newbits (number) drive the address arithmetic
#   in locals. interface_endpoint_services (set(string)) drives the
#   interface-endpoint for_each, and it is the ONLY way a dependency becomes
#   reachable from the application tier -- the identity-provider egress input that
#   used to open TCP 443 to any public destination is withdrawn, so this module
#   declares no internet-egress rule at all. app_container_port (number) and
#   database_port (number) are consumed by security-group rules and republished
#   by outputs.tf so a root passes one value to both a rule and its listener.
#   tags (map(string)) merges into every taggable resource.
#   flow_log_retention_days (number) and flow_log_kms_key_arn (string, nullable)
#   configure the flow-log group.
#
# Return values:
#   This file declares no output. outputs.tf publishes sixteen values read
#   by the environment roots and their sibling modules: the VPC id and CIDR,
#   the resolved availability-zone list, the three per-tier subnet-id lists,
#   the three consumer-facing security-group ids (alb, app, data), the NAT
#   gateway ids and public addresses, the interface-endpoint id map and the S3
#   gateway endpoint id, the two shared port contracts, and the flow-log group
#   name. Route-table ids are deliberately not published; outputs.tf records the
#   measurement behind that at the position they occupied.
#
# Exceptions or errors:
#   Planning fails when the selected Region exposes fewer usable availability
#   zones than az_count; the VPC carries a precondition so that surfaces as a
#   targeted diagnostic rather than a slice error. Planning also fails when
#   vpc_cidr is too small to carry 3 * az_count subnets at subnet_newbits, and
#   when a name in interface_endpoint_services is not offered in the Region.
#   Applying can fail when the caller-supplied KMS key policy does not permit
#   the regional CloudWatch Logs service to use the key; the kms module owns
#   that policy and this module deliberately accepts only the key ARN.
#   Four plan-time assertions beyond the VPC's zone precondition refuse a
#   configuration this file cannot make correct: an internal_alb_client_edges
#   inventory that is empty or repeats an entry, which would leave the
#   application-to-listener rule without the justification it is held to; an S3
#   gateway endpoint that resolves no prefix list, which would leave the
#   application tier's object-storage egress rule with no destination; and either
#   set of S3 route-table associations covering fewer zones than the network
#   spans, which would leave one zone's subnets without the prefix-list route the
#   others have. Each is a lifecycle precondition or postcondition, so it is
#   evaluated during plan and NOT by terraform validate.
#   WARNING: vpc_cidr and subnet_newbits are replacement-forcing. Changing
#   either after apply destroys and recreates the VPC and every subnet in it,
#   which cascades into every resource the sibling modules place in those
#   subnets - Aurora, the ECS tasks, the load balancer and the endpoint ENIs.
#   Neither is a safe in-place edit on a provisioned environment.
#
# WHY (non-obvious design decisions):
#   - Alternatives Considered: two subnet tiers with Aurora beside the tasks.
#     Rejected because an isolated-data route table with no default route makes
#     the database egress boundary a routing fact, not a security-group policy
#     that a later edit can accidentally widen.
#   - Alternatives Considered: one NAT gateway shared by every zone. Rejected
#     because losing its zone would remove egress from the whole application
#     tier and turn healthy zones into cross-zone clients. Three hourly gateway
#     charges are accepted so dev validates the same topology prod uses.
#   - Assumptions: the application tier's permitted flows are these five, and the
#     list is exhaustive because the group carries no allow-all default:
#       1. inbound from the load-balancer group on app_container_port;
#       2. outbound to the data group on database_port;
#       3. outbound to the endpoint group on 443, for the ten private AWS
#          service endpoints;
#       4. outbound to the LOAD-BALANCER group on 443, which is how all three
#          delivered synchronous service-to-service edges reach each other -
#          they are addressed by the internal load balancer's own origin, so
#          without this rule every one of them fails as a connect timeout;
#       5. outbound to the S3 gateway endpoint's managed prefix list on 443.
#     Flows 1 to 4 name a peer group this module also owns, so each is declared
#     as an egress rule and a matching ingress rule. Flow 5 reaches a managed
#     destination that has no security group at all - a prefix list - so it is
#     egress-only by necessity rather than by choice, and the destination is what
#     narrows it instead of a peer group.
#     ⚠️ Refactoring Rationale: flow 5 named a SECOND destination, "outbound to
#     the identity provider on 443", and that rule instantiated 0.0.0.0/0 in both
#     roots. It is withdrawn from this enumeration because its default is now the
#     empty set: cognito-idp is one of the ten endpointed services, so identity
#     resolves through flow 3 like every other managed dependency. The rule
#     itself is still declared, keyed one per entry of
#     identity_provider_egress_cidrs, so an operator can admit a narrow
#     destination without editing this file - but with nothing to enumerate, a
#     list of the tier's permitted flows that still counted it would overstate
#     what the group admits, which is the one thing this enumeration exists to
#     get right.
#   - Alternatives Considered: attaching the interface-endpoint ENIs to one of
#     the three consumer-facing groups, so that exactly three groups exist. An
#     interface endpoint must carry a group, so the task-to-endpoint flow has to
#     terminate somewhere. Putting the ENIs on the application group needs a
#     self-referencing 443 rule, which would also permit task-to-task 443;
#     putting them on the ALB group would fold the endpoint permission into the
#     application-to-ALB 443 rule that the service-to-service path already
#     requires, so withdrawing either permission would withdraw both. A fourth
#     group therefore exists that nothing but the endpoints attaches to. It is
#     created but not published, because no sibling module attaches to it.
#     Trade-offs: one more group to reason about, accepted in exchange for a
#     rule set in which each permitted flow has exactly one source group and
#     one destination group.
#   - Trade-offs: the application group's egress is ENUMERATED rather than left
#     as the implicit allow-all a new group would otherwise carry. FOUR rules
#     carry it, each separately addressable and separately reasoned: to the
#     Aurora group on the database port, to the interface-endpoint group on 443,
#     to the load-balancer group on 443, and to the S3 gateway endpoint's managed
#     prefix list on 443. There is no fifth rule and no rule with a 0.0.0.0/0
#     destination anywhere in this module.
#     Refactoring Rationale: a fifth rule did stand here, admitting 0.0.0.0/0 on
#     443 for Cognito identity and X-Ray on the premise that neither had an
#     endpoint in the frozen set. Both are in that set now, so the premise had
#     lapsed while the rule -- the one unrestricted destination in the whole
#     module, in a workload holding cardholder data -- remained. It is withdrawn
#     and the reasoning is recorded at the position it occupied.
#     Refactoring Rationale: this note read "Only the two destinations above are
#     reachable". That was true of the rule set as written and was the defect: the
#     internal load balancer, the S3 gateway endpoint, Cognito and X-Ray are all
#     required at runtime and none was reachable, so a correct-looking enumeration
#     described a topology in which no task could start and no service could call
#     a sibling. The friction the note claims is still the point; it now has to be
#     paid by a rule that is genuinely absent rather than by one that was missing
#     because a flow count said three.
#   - Refactoring Rationale: app_container_port, database_port and
#     interface_endpoint_services are module inputs rather than literals. The
#     ports are returned as outputs so the root can pass one value to both the
#     rule and its listener; the endpoint set is validated as exact so a root
#     cannot silently route one environment through a public service endpoint.
# =============================================================================

data "aws_availability_zones" "available" {
  state = "available"

  # WHY : Assumptions: zones requiring an explicit account opt-in are usable
  #       only after that opt-in. Including both ordinary and opted-in zones
  #       permits the same module in Regions containing either kind while
  #       excluding zones the account cannot place a subnet in.
  filter {
    name   = "opt-in-status"
    values = ["opt-in-not-required", "opted-in"]
  }
}

data "aws_region" "current" {}

# WHY : Both are needed to build fully-qualified ARNs by hand rather than by
#       wildcard. Two places in this file require them.
#
#       (1) The flow-log role's trust policy carries `aws:SourceAccount` and
#           `aws:SourceArn` confused-deputy conditions. A service principal
#           trust with no such condition is assumable by that service on behalf
#           of ANY account: the VPC Flow Logs service would present this role's
#           ARN to STS for a flow log belonging to a different customer, and STS
#           would issue credentials. Both keys are needed, not one — the account
#           key bounds WHOSE flow log may drive the assumption, and the ARN key
#           bounds WHICH flow log resource within that account.
#
#       (2) The log-group discovery statement is scoped to an account- and
#           Region-qualified log-group ARN instead of a bare `*`.
#
#       Alternatives Considered: hard-coding `aws` as the partition. Rejected —
#       the same module applied in an isolated partition (`aws-us-gov`,
#       `aws-cn`) would then build ARNs that match nothing, and every condition
#       built from them would silently fail closed. Reading the partition costs
#       one metadata lookup at plan time and removes that whole failure mode.
data "aws_caller_identity" "current" {}

data "aws_partition" "current" {}

locals {
  name_stem = "${var.name_prefix}-${var.environment}"

  # WHY : Assumptions: the identity provider's short service name is named ONCE here and
  #       read where the endpoint policy is selected, rather than written as a literal at
  #       that expression. The name appears in three places that must agree -- the exact
  #       set in variables.tf, this local, and the endpoint whose policy differs -- and a
  #       literal at the selection site is the one of those three a reader would not think
  #       to check. If the name here matched nothing in the set, every endpoint would
  #       silently receive the account-scoped document and sign-on would fail closed at the
  #       endpoint, which is the failure this arrangement exists to prevent; the exact-set
  #       validation on that variable is what keeps the name it must match from moving.
  identity_provider_endpoint_service = "cognito-idp"

  # WHY : Assumptions: min prevents slice itself from failing before the VPC's
  #       precondition can emit the targeted insufficient-zone diagnostic.
  #       With enough zones, which both deployment roots require, the result is
  #       exactly az_count entries.
  availability_zones = slice(
    data.aws_availability_zones.available.names,
    0,
    min(var.az_count, length(data.aws_availability_zones.available.names)),
  )

  # WHY : Assumptions: consecutive netnum ranges keep the three tiers
  #       non-overlapping by construction. Public occupies 0..N-1,
  #       private-application N..2N-1 and isolated-data 2N..3N-1. Worked
  #       example at the defaults: 10.0.0.0/16 with four new prefix bits yields
  #       sixteen /20 blocks, of which three zones consume nine - netnums 0..2
  #       public, 3..5 private-application, 6..8 isolated-data - leaving seven
  #       blocks of deliberate headroom for a later tier.
  #       Alternatives Considered: three hand-written per-tier CIDR lists.
  #       Rejected because nine literal blocks have to be kept mutually
  #       non-overlapping and consistent with az_count by hand, and a single
  #       derivation cannot drift out of step with either.
  #       Trade-offs: the caller loses control over exact block placement, which
  #       is what buys the guarantee that no two tiers can ever overlap.
  public_subnet_cidrs = {
    for index, zone in local.availability_zones :
    zone => cidrsubnet(var.vpc_cidr, var.subnet_newbits, index)
  }
  private_app_subnet_cidrs = {
    for index, zone in local.availability_zones :
    zone => cidrsubnet(var.vpc_cidr, var.subnet_newbits, var.az_count + index)
  }
  isolated_data_subnet_cidrs = {
    for index, zone in local.availability_zones :
    zone => cidrsubnet(var.vpc_cidr, var.subnet_newbits, (2 * var.az_count) + index)
  }

  # WHY : Assumptions: 443 is not an input here and cannot be, because it is not
  #       this module's choice. It is the port the sibling internal load balancer
  #       fixes on its own listener, which that module states is deliberately not
  #       an input either because "a non-default port would reach the same
  #       listener while additionally having to be mirrored in the load-balancer
  #       security group" - that group being this one. The same number is also
  #       the TLS port of the private AWS service endpoints, of the S3 gateway
  #       path and of the identity provider. Naming it once here keeps the four
  #       rules that use it from drifting apart, which is the only failure this
  #       constant can prevent.
  https_port = 443

  # WHY : Assumptions: every synchronous service-to-service call in this system
  #       resolves through the INTERNAL load balancer, so the rule pair that
  #       admits the application group to the load-balancer group on
  #       local.https_port is the single network dependency of all three edges
  #       below. Each edge is a delivered HTTP client, not a planned one, and the
  #       list is what the rule's precondition asserts against:
  #         - account-service -> reference-service, through
  #           com.carddemo.account.service.RestReferenceAddressLookup, reading
  #           the three seeded address allow-lists;
  #         - transaction-service -> account-service, through
  #           com.carddemo.transaction.service.RestAccountContextClient;
  #         - authorization-service -> account-service, through
  #           com.carddemo.authorization.service.RestAccountContextClient.
  #       Each client's base address is supplied as a *_CONTEXT_BASE_URL
  #       environment value that the environment roots set to the internal load
  #       balancer's own origin, which is what makes this a network fact rather
  #       than an application detail.
  #       Alternatives Considered: leaving the edges recorded only in
  #       docs/architecture/service-catalog.md. Rejected because that document
  #       had drifted from the delivered clients while the rule set had no
  #       matching rule at all, and neither error was visible from the other. A
  #       reader of the rule now sees the edges it exists for, and the plan stops
  #       if that justification is edited away.
  internal_alb_client_edges = [
    "account-service -> reference-service",
    "transaction-service -> account-service",
    "authorization-service -> account-service",
  ]
}

# -----------------------------------------------------------------------------
# VPC and default boundary
# -----------------------------------------------------------------------------

resource "aws_vpc" "this" {
  cidr_block           = var.vpc_cidr
  enable_dns_support   = true
  enable_dns_hostnames = true
  instance_tenancy     = "default"

  # WHY : Assumptions: interface-endpoint private DNS depends on both VPC DNS
  #       attributes being enabled. A caller cannot disable either through a
  #       variable because doing so leaves every endpoint present while SDK
  #       names resolve to their public addresses - which, with this module's
  #       egress enumerated to in-VPC destinations only, means every AWS call
  #       from a task is dropped at the security group. Refactoring Rationale:
  #       this note described the symptom as traffic quietly taking the NAT path
  #       instead, which held while an unrestricted 443 egress rule existed. That
  #       rule is withdrawn, so the same misconfiguration now fails loudly and
  #       totally rather than silently and expensively.
  #
  # WHY : Assumptions: this merge form is used on every taggable resource in the
  #       file, and it composes with the calling root rather than replacing
  #       anything. The root's provider block sets default_tags, which the
  #       provider applies underneath these, so var.tags layers onto that set and
  #       Name layers onto var.tags. Name is the only tag this module adds on its
  #       own initiative; ownership, cost-allocation and environment tags belong
  #       to the root, which is why none are invented here.
  tags = merge(var.tags, {
    Name = "${local.name_stem}-vpc"
  })

  lifecycle {
    precondition {
      condition     = length(data.aws_availability_zones.available.names) >= var.az_count
      error_message = "The selected AWS Region exposes fewer available or opted-in availability zones than az_count requires."
    }
  }
}

# WHY : Assumptions: Terraform does not create this group - AWS creates one per
#       VPC automatically, and it ships with a self-referencing ingress rule and
#       unrestricted egress. Adopting it here with an empty rule set is the only
#       way to assert in code that it admits nothing, because there is no way to
#       delete it. Anything launched without an explicit group lands here and
#       therefore reaches nothing, instead of inheriting the permissive default.
#       Alternatives Considered: leaving it unmanaged, which is what omitting
#       this resource would mean. Rejected because the group would then remain
#       permissive while nothing in this codebase described it, so a reader could
#       not tell whether that was intended - and the policy scanner's
#       "default security group restricts all traffic" condition would fail with
#       no resource to attach the finding to.
resource "aws_default_security_group" "this" {
  vpc_id  = aws_vpc.this.id
  ingress = []
  egress  = []

  tags = merge(var.tags, {
    Name = "${local.name_stem}-default-deny"
  })
}

# WHY : Assumptions: this is the VPC's only path to and from the internet, and it
#       is reachable from exactly one route table - the public one below. Which
#       tiers can use it is therefore decided by route-table association rather
#       than by anything declared here, which is why the two private tiers need
#       no attribute to opt out of it.
resource "aws_internet_gateway" "this" {
  vpc_id = aws_vpc.this.id

  tags = merge(var.tags, {
    Name = "${local.name_stem}-igw"
  })
}

# -----------------------------------------------------------------------------
# Three subnet tiers
# -----------------------------------------------------------------------------

resource "aws_subnet" "public" {
  for_each = local.public_subnet_cidrs

  vpc_id                  = aws_vpc.this.id
  availability_zone       = each.key
  cidr_block              = each.value
  map_public_ip_on_launch = false

  # WHY : Alternatives Considered: enabling public-address assignment because
  #       these subnets have an internet-gateway route. Rejected: the load
  #       balancer is internal and NAT gateways allocate their own Elastic IPs,
  #       so no workload launched here needs an automatically assigned address.
  tags = merge(var.tags, {
    Name = "${local.name_stem}-public-${each.key}"
  })
}

resource "aws_subnet" "private_app" {
  for_each = local.private_app_subnet_cidrs

  vpc_id                  = aws_vpc.this.id
  availability_zone       = each.key
  cidr_block              = each.value
  map_public_ip_on_launch = false

  tags = merge(var.tags, {
    Name = "${local.name_stem}-private-app-${each.key}"
  })
}

resource "aws_subnet" "isolated_data" {
  for_each = local.isolated_data_subnet_cidrs

  vpc_id                  = aws_vpc.this.id
  availability_zone       = each.key
  cidr_block              = each.value
  map_public_ip_on_launch = false

  tags = merge(var.tags, {
    Name = "${local.name_stem}-isolated-data-${each.key}"
  })
}

# -----------------------------------------------------------------------------
# Public routing and per-zone NAT egress
# -----------------------------------------------------------------------------

# WHY : Alternatives Considered: one public route table per zone, mirroring the
#       per-zone shape of the private-application tables below. Rejected because
#       every public subnet takes the identical egress path - one default route
#       to the single internet gateway - so per-zone tables would be three copies
#       of one rule that must then be kept identical by hand. The private tables
#       are per-zone for a reason that does not apply here: their next hop
#       differs by zone.
resource "aws_route_table" "public" {
  vpc_id = aws_vpc.this.id

  tags = merge(var.tags, {
    Name = "${local.name_stem}-public-rt"
  })
}

resource "aws_route" "public_internet" {
  route_table_id         = aws_route_table.public.id
  destination_cidr_block = "0.0.0.0/0"
  gateway_id             = aws_internet_gateway.this.id
}

resource "aws_route_table_association" "public" {
  for_each = aws_subnet.public

  subnet_id      = each.value.id
  route_table_id = aws_route_table.public.id
}

resource "aws_eip" "nat" {
  for_each = aws_subnet.public

  # WHY : Refactoring Rationale: domain is the current spelling of what the
  #       provider once expressed as `vpc = true`. That older argument is
  #       deprecated in the 6.x line pinned by versions.tf, so writing it would
  #       raise a deprecation warning today and stop parsing in a later major.
  domain = "vpc"

  # WHY : Assumptions: an address is allocated once per zone because the NAT
  #       gateway in that zone is the only consumer. The provider can allocate
  #       the address before the gateway exists, while the explicit internet
  #       gateway dependency on the NAT resource below ensures the address is
  #       not attached to a gateway with no path out.
  tags = merge(var.tags, {
    Name = "${local.name_stem}-nat-eip-${each.key}"
  })
}

# WHY : Alternatives Considered: one NAT gateway shared by all three zones,
#       which is the obvious way to cut the largest fixed cost in this tier.
#       Rejected on parity grounds: dev and prod are specified to differ in sizing
#       and retention only, never in topology, because a dev environment with a
#       different network shape cannot falsify the prod one - so a single-NAT cost
#       toggle is not available to this module. A gateway per zone also keeps this
#       tier symmetric with the per-zone private route tables below, which is what
#       makes any future bounded public dependency one security-group rule rather
#       than a change of topology.
#       Trade-offs: stated plainly, NAT gateways are charged per hour per gateway
#       plus per GB processed, so one per zone multiplies the hourly term by
#       three, and that is the single largest fixed cost in this module. It is
#       accepted rather than minimised. The interface endpoints below hold the
#       per-GB term down by keeping the ten endpointed services' API traffic off
#       these gateways altogether, and the S3 gateway endpoint keeps image-layer
#       and dataset traffic off them as well.
#       Refactoring Rationale: both halves of the cost and availability argument
#       above were, until this change, claims about traffic that could not exist.
#       The application security group's egress rules named only the Aurora group
#       and the interface-endpoint group, so NOTHING in the private-application
#       tier could send a packet toward a NAT gateway: three gateways were billed
#       hourly to carry nothing, "losing its zone would remove egress from every
#       private-application subnet" described the removal of a capability that was
#       not in use, and the per-GB term the endpoints were said to claw back was
#       already zero. The rules are unchanged in intent, and what the gateways carry
#       is now exactly one flow: the OPT-IN identity-provider egress rule, created
#       only when a caller supplies reviewed destinations. So the availability
#       reasoning applies to that dependency when it is configured -- a zone whose
#       gateway is lost can no longer resolve an issuer from a task in that zone --
#       and where it is not configured the gateways carry no application traffic at
#       all and their hourly cost buys the ability to enable that flow without a
#       topology change.
#       Refactoring Rationale: this paragraph previously named "Cognito identity and
#       X-Ray" as the traffic traversing the gateways. X-Ray left with the collector
#       sidecar, and the identity flow is no longer created by default, so naming
#       both as live dependencies would have overstated what these gateways carry.
#       The
#       correction is recorded rather than the paragraph simply rewritten, because
#       "we accept this cost for resilience" is exactly the kind of claim a cost
#       review takes at face value.
resource "aws_nat_gateway" "this" {
  for_each = aws_subnet.public

  allocation_id = aws_eip.nat[each.key].id
  subnet_id     = each.value.id

  tags = merge(var.tags, {
    Name = "${local.name_stem}-nat-${each.key}"
  })

  # WHY : Assumptions: a NAT gateway cannot be created until the internet gateway
  #       is attached to the VPC, and nothing in this resource's arguments
  #       references it, so Terraform cannot infer that ordering on its own. The
  #       dependency is declared rather than discovered.
  depends_on = [aws_internet_gateway.this]
}

# WHY : Assumptions: these tables are per-zone precisely because there is a NAT
#       gateway per zone. A route table carries one next hop for a destination,
#       so a single shared table could name only one of the three gateways -
#       which would push two zones' egress across a zone boundary and reinstate
#       the single point of failure the per-zone gateways were created to remove.
#       The two decisions are one decision: per-zone gateways require per-zone
#       tables, and neither is useful without the other.
resource "aws_route_table" "private_app" {
  for_each = local.private_app_subnet_cidrs

  vpc_id = aws_vpc.this.id

  tags = merge(var.tags, {
    Name = "${local.name_stem}-private-app-rt-${each.key}"
  })
}

resource "aws_route" "private_app_internet" {
  for_each = aws_route_table.private_app

  route_table_id         = each.value.id
  destination_cidr_block = "0.0.0.0/0"
  nat_gateway_id         = aws_nat_gateway.this[each.key].id
}

resource "aws_route_table_association" "private_app" {
  for_each = aws_subnet.private_app

  subnet_id      = each.value.id
  route_table_id = aws_route_table.private_app[each.key].id
}

# -----------------------------------------------------------------------------
# Isolated data routing
# -----------------------------------------------------------------------------

# These tables carry the implicit local VPC route and nothing else. There is no
# default route, no NAT route and no internet-gateway route, and the absence is
# the point rather than an omission - it is the invariant this module exists to
# assert, so a later edit that adds one has changed the design, not fixed a gap.
#
# WHY : Assumptions: stated mechanically, the isolation here is a routing fact
#       rather than a policy statement. An egress attempt from the data tier has
#       no next hop to resolve, so it fails at the route table - and it fails
#       that way regardless of any security-group rule, any IAM policy and any
#       credential that has leaked. A policy can be widened by one careless edit
#       and a credential can be stolen; a route that was never added cannot be
#       used. That asymmetry is what makes this the strongest boundary available
#       in this module.
#       Alternatives Considered: a two-tier design placing Aurora in the
#       private-application subnets beside the tasks, which is simpler and one
#       fewer tier to reason about. Rejected because it removes exactly this
#       guarantee: a compromised task in a subnet that HAS an egress route is a
#       materially different exposure from one in a subnet that does not, and no
#       amount of security-group care recovers the difference.
#       Trade-offs: named honestly, this costs real operability. There is no
#       direct operator access to the data tier and no package or update egress
#       from it. Reaching the database is done through the application tier or a
#       controlled session mechanism, and anything the tier needs from outside
#       has to be brought to it. That is the price of the guarantee above, and it
#       is accepted rather than worked around.
#       One entry that is NOT an internet route is added further down: the S3
#       gateway endpoint is associated with these tables. That installs a route
#       to a managed service prefix list, which can carry traffic to that one
#       service and to nothing else, so it does not contradict any of the above.
resource "aws_route_table" "isolated_data" {
  for_each = local.isolated_data_subnet_cidrs

  vpc_id = aws_vpc.this.id

  tags = merge(var.tags, {
    Name = "${local.name_stem}-isolated-data-rt-${each.key}"
  })
}

resource "aws_route_table_association" "isolated_data" {
  for_each = aws_subnet.isolated_data

  subnet_id      = each.value.id
  route_table_id = aws_route_table.isolated_data[each.key].id
}

# -----------------------------------------------------------------------------
# Security groups
# -----------------------------------------------------------------------------

resource "aws_security_group" "alb" {
  name_prefix            = "${local.name_stem}-alb-"
  description            = "Internal ALB listener boundary forwarding approved traffic to CardDemo tasks"
  vpc_id                 = aws_vpc.this.id
  revoke_rules_on_delete = true

  # WHY : Assumptions: revoke_rules_on_delete makes a destroy deterministic. A
  #       group cannot be deleted while any rule still references it, and the
  #       edge group is referenced from api-gateway-http, so leaving the default
  #       would make teardown order-dependent on a module this one cannot see.
  tags = merge(var.tags, {
    Name = "${local.name_stem}-alb-sg"
  })
}

resource "aws_security_group" "app" {
  name_prefix            = "${local.name_stem}-app-"
  description            = "CardDemo application tasks receiving ALB traffic and reaching approved dependencies"
  vpc_id                 = aws_vpc.this.id
  revoke_rules_on_delete = true

  tags = merge(var.tags, {
    Name = "${local.name_stem}-app-sg"
  })
}

resource "aws_security_group" "data" {
  name_prefix            = "${local.name_stem}-data-"
  description            = "Aurora PostgreSQL receiving only CardDemo application-tier database traffic"
  vpc_id                 = aws_vpc.this.id
  revoke_rules_on_delete = true

  tags = merge(var.tags, {
    Name = "${local.name_stem}-data-sg"
  })
}

# WHY : ⚠️ Alternatives Considered: reusing one of the three consumer-facing groups
#       for the interface-endpoint ENIs, which would leave exactly three groups --
#       the count AAP §0.5.1.12 states for this module. This module creates FOUR,
#       and the extra group is therefore a DOCUMENTED DIVERGENCE from that count,
#       registered as D-NETWORK-ENDPOINT-SECURITY-GROUP in
#       docs/architecture/cobol-to-service-traceability.md rather than left as an
#       unexplained difference. It is taken deliberately, because closing it would
#       violate the OTHER half of the same specification: §0.4.1.9 states that the
#       security groups permit only balancer-to-application on the container port,
#       application-to-Aurora on the database port and application-to-endpoint on
#       443, and every three-group arrangement adds a permitted flow beyond those.
#       An interface endpoint has to carry a group, so the task-to-endpoint flow
#       must terminate on some group. Reusing the application group requires a
#       self-referencing 443 rule, which would also permit task-to-task 443 and
#       widen the blast radius the isolated-data tier exists to narrow. Reusing
#       the ALB group would collapse two genuinely different flows onto one rule:
#       an application-to-ALB 443 rule now exists for service-to-service calls,
#       and folding the endpoint ENIs into the same group would make that one rule
#       also grant every task access to the eight private service endpoints, so
#       withdrawing either permission would withdraw both. A dedicated group keeps
#       each permitted flow at exactly one source group and one destination group.
#       Refactoring Rationale: the ALB half of this argument previously read that
#       an application-to-ALB 443 rule "would let every task reach the edge
#       listener group on 443 - a flow no component makes". Three components make
#       it -- the authorization, transaction and account contexts all call a
#       sibling over the internal listener -- so the premise was false and the
#       rule it was used to rule out was a rule the system requires. The
#       conclusion is unchanged and now rests on separation of the two flows,
#       which is a reason that survives the correction.
# WHY : Refactoring Rationale: the paragraph above previously justified the
#       dedicated group partly on the claim that an application-to-ALB 443 rule
#       would permit "a flow no component makes". That claim was false and it is
#       withdrawn: authorization-service and transaction-service both reach the
#       ACCOUNT context over this internal load balancer at
#       https://<internal name>, which each environment root supplies as
#       CARDDEMO_ACCOUNT_CONTEXT_BASE_URL, and account-service reaches the
#       REFERENCE context the same way through CARDDEMO_REFERENCE_CONTEXT_BASE_URL.
#       The flow is therefore made on every authorization decision, every
#       transaction add and every account update, and the rule pair below now
#       permits it. The dedicated endpoint group is still correct for the reason
#       that survives: a self-referencing 443 rule on the application group would
#       additionally permit task-to-task 443, which this rule pair does not,
#       because it names the ALB group as the peer rather than the application
#       group itself.
# WHY : Refactoring Rationale: a FOURTH security group stood here, `vpc_endpoints`, for the
#       interface-endpoint ENIs, and it is WITHDRAWN. AAP section 0.5.1.12 specifies three
#       security groups for this module, and the ENIs now carry the application group -- so
#       the task-to-endpoint flow is the self-referencing 443 rule pair below rather than a
#       rule naming a fourth group.
# WHY : Assumptions: the group was already carried by nothing when it was removed, which is
#       what made the removal safe rather than a topology change. The endpoint resource
#       names aws_security_group.app, no rule resource referenced this group, and
#       outputs.tf never published it -- so it was a created-and-unused resource, and an
#       empty group attached to no interface is a permission boundary that bounds nothing
#       while appearing in a plan as though it did.
# WHY : Trade-offs: the self reference re-permits task-to-task 443, which the dedicated
#       group did not. Accepted, and bounded rather than assumed: var.app_container_port is
#       validated to exclude 443, so no service listens there, and the only 443 listener in
#       this VPC carries the ALB group. The reasoning is recorded in full at that rule pair
#       and the widened permission is registered as a documented divergence in
#       docs/architecture/cobol-to-service-traceability.md.

# WHY : Alternatives Considered: inline ingress and egress blocks on each group
#       instead of the separate rule resources used below. Rejected for three
#       reasons. A rule declared as its own resource is individually
#       addressable, so a plan shows which single rule changed rather than
#       replacing an opaque set. The two forms conflict if a group ever ends up
#       carrying both, and committing to one form here removes that failure mode
#       instead of documenting it. And a rule resource carries its own
#       description attribute, which is where each flow's reason is recorded -
#       an inline block has nowhere to put it.
#       Assumptions: every rule below names its peer with
#       referenced_security_group_id rather than a CIDR block. A CIDR rule
#       admits whatever happens to hold an address in range, whereas a group
#       reference admits only the role actually attached to that group, and it
#       stays correct if a subnet is ever resized or a zone added.
#
# The EDGE-to-listener rule is deliberately absent from this module, and it is a
# different rule from the application-to-listener rule that is present below. API
# Gateway VPC Link ENIs carry a dedicated group that api-gateway-http creates
# beside the link, and that module opens this ALB group from it by passing
# alb_security_group_id into aws_vpc_security_group_ingress_rule.alb_from_vpc_link_https.
# WHY : Alternatives Considered: declaring that rule here as a self reference on
# the ALB group, on the premise that the link and the load balancer share one
# group. Rejected because the link holds its own group, so a self reference
# would permit ALB-to-ALB 443 that no component uses while leaving the real
# source group unnamed. Authoring it here is also not open to this module,
# because api-gateway-http already consumes alb_security_group_id from here, so
# referencing that module's group in return would close a cycle between the two.
# WHY : Refactoring Rationale: this note also gave as a reason that the rule
# "would add a fourth flow to the three this topology is specified to allow:
# balancer to application, application to Aurora, and application to interface
# endpoint". That three-flow inventory was never complete and is not the test
# being applied. The permitted flows are enumerated by the rule resources below
# and are now five: balancer to application on the container port, application to
# Aurora on the database port, application to the endpoint ENIs on 443 -- a self
# reference, since those ENIs carry the application group -- application to the
# internal balancer listener on 443, and application to the S3 gateway prefix list
# on 443. Each has its own addressable resource and its own recorded reason.
# Refactoring Rationale: a sixth entry, "application to unendpointed AWS services on
# 443 through NAT", is withdrawn from this list because no such rule exists. The
# egress rule that admitted it named 0.0.0.0/0 and was withdrawn as a finding in its
# own right; .github/workflows/infra-ci.yml now fails any egress rule naming an open
# destination, so a service absent from the endpoint set is DROPPED at this group
# rather than reaching NAT. Listing a flow the rule set does not permit is worse than
# omitting one, because a reader plans a dependency on it. Counting flows was the wrong gate in any case: it made "there are
# already three" an argument against a rule the system needs, which is how the
# service-to-service path came to be unreachable.
resource "aws_vpc_security_group_egress_rule" "alb_to_app" {
  security_group_id            = aws_security_group.alb.id
  referenced_security_group_id = aws_security_group.app.id
  description                  = "Allow the internal ALB to forward requests and health checks to CardDemo tasks"
  ip_protocol                  = "tcp"
  from_port                    = var.app_container_port
  to_port                      = var.app_container_port
}

resource "aws_vpc_security_group_ingress_rule" "alb_to_app" {
  security_group_id            = aws_security_group.app.id
  referenced_security_group_id = aws_security_group.alb.id
  description                  = "Allow CardDemo tasks to receive requests and health checks from the internal ALB"
  ip_protocol                  = "tcp"
  from_port                    = var.app_container_port
  to_port                      = var.app_container_port
}

# WHY : Assumptions: this pair carries every synchronous service-to-service call
#       in the system, and it carries them by way of the load balancer rather
#       than task to task. Each of the three clients named in
#       local.internal_alb_client_edges is configured with a *_CONTEXT_BASE_URL
#       that the environment roots set to the internal load balancer's own
#       origin, on the certificate those roots issue for that same name, so the
#       hop a caller makes is application group to load-balancer group on
#       local.https_port and then the existing alb_to_app pair onward to the
#       callee. Without this pair the two rules on either side of it exist and
#       the call still fails, as a connect timeout rather than as a refusal - and
#       for the authorization consumer that timeout surfaces as redelivery until
#       the queue dead-letters the request, so the operator's symptom is a
#       stalled queue rather than a network error anyone would look for.
#       Assumptions: the port is local.https_port (443) and NOT
#       var.app_container_port. The container port is what the load balancer
#       forwards TO, and the rule for that flow is the alb_to_app pair above;
#       this pair is what reaches the LISTENER, which infra/modules/alb fixes at
#       443 on the same name its certificate is issued for.
#       Refactoring Rationale: a second, separately named pair once declared this
#       same flow with a literal 443 in place of local.https_port. A
#       security-group rule is identified by its tuple and not by its resource
#       name, so the two were one rule written twice, and whichever was created
#       second failed the apply with InvalidPermission.Duplicate - the module
#       could not provision at all. The duplicate is withdrawn, and the two
#       rulings it carried that this pair did not - the port distinction and the
#       stalled-queue symptom - are folded in above rather than lost with it.
#       Alternatives Considered: a task-to-task rule instead - a self reference on
#       the application group on the callee's container port. Rejected because it
#       would permit every task to reach every other task, including the pairs
#       that have no client between them, and because the callers do not address
#       each other directly: they hold a name that resolves to the load balancer,
#       so a task-to-task rule would permit a flow nothing uses while leaving the
#       flow that is actually made still blocked.
#       Trade-offs: the load balancer is in the request path of an internal call,
#       which adds a hop and its latency to every one of the three edges. Accepted
#       because that hop is what supplies health-based target removal and one
#       stable name per context, and because the alternative - direct addressing -
#       would put target discovery into the callers.
resource "aws_vpc_security_group_egress_rule" "app_to_alb" {
  security_group_id            = aws_security_group.app.id
  referenced_security_group_id = aws_security_group.alb.id
  description                  = "Allow CardDemo tasks to call other contexts through the internal ALB HTTPS listener"
  ip_protocol                  = "tcp"
  from_port                    = local.https_port
  to_port                      = local.https_port

  lifecycle {
    # WHY : Assumptions: what this asserts is the rule's JUSTIFICATION, not its
    #       reachability - reachability follows from the rule existing. An
    #       unrestricted 443 egress toward the edge listener group is exactly the
    #       rule an earlier revision of this module refused to write, so it is
    #       held to naming the delivered edges that require it. An empty list
    #       would mean the last client had been withdrawn and the rule should go
    #       with it; a duplicated entry would mean the inventory had been edited
    #       without being read, which is how the service catalogue came to claim
    #       one edge that does not exist while omitting two that do.
    precondition {
      condition = (
        length(local.internal_alb_client_edges) > 0 &&
        length(local.internal_alb_client_edges) == length(toset(local.internal_alb_client_edges))
      )
      error_message = "local.internal_alb_client_edges must name at least one delivered synchronous edge and must not repeat one; it is the stated justification for admitting the application group to the load-balancer group on 443."
    }
  }
}

resource "aws_vpc_security_group_ingress_rule" "app_to_alb" {
  security_group_id            = aws_security_group.alb.id
  referenced_security_group_id = aws_security_group.app.id
  description                  = "Allow the internal ALB HTTPS listener to receive context-to-context calls from CardDemo tasks"
  ip_protocol                  = "tcp"
  from_port                    = local.https_port
  to_port                      = local.https_port
}

resource "aws_vpc_security_group_egress_rule" "app_to_data" {
  security_group_id            = aws_security_group.app.id
  referenced_security_group_id = aws_security_group.data.id
  description                  = "Allow CardDemo tasks to initiate PostgreSQL sessions to Aurora"
  ip_protocol                  = "tcp"
  from_port                    = var.database_port
  to_port                      = var.database_port
}

resource "aws_vpc_security_group_ingress_rule" "app_to_data" {
  security_group_id            = aws_security_group.data.id
  referenced_security_group_id = aws_security_group.app.id
  description                  = "Allow Aurora PostgreSQL to receive sessions from CardDemo tasks only"
  ip_protocol                  = "tcp"
  from_port                    = var.database_port
  to_port                      = var.database_port
}

# WHY : Assumptions: this pair carries the application tier's traffic to the eight
#       PRIVATE service endpoints and nothing else. It is not the whole of that
#       tier's outbound reachability, and the rules that follow are the rest
#       of it. Private DNS on those endpoints is what makes this pair the path the
#       traffic actually takes: for each of the eight, the SDK's default hostname
#       resolves to the endpoint ENI inside this VPC, so the packet is destined for
#       the endpoint group and is matched here rather than leaving the VPC.
#       Refactoring Rationale: this note said the pair stays "load-bearing rather
#       than redundant with the broader rule below ... even though a wider rule
#       exists". There is no wider rule any more: the identity-provider egress that
#       followed defaulted to 0.0.0.0/0 and now defaults to creating no rule at all,
#       so nothing here is shadowed by a broader destination and the clause would
#       have had a reader looking for one.
#       Refactoring Rationale: both rules formerly named the APPLICATION group on
#       both sides, which made the flow a self reference and therefore also
#       permitted task-to-task traffic on 443. Naming the dedicated endpoint
#       group on the far side of each rule preserves the reachability exactly -
#       the endpoint ENIs carry that group - while withdrawing the peer-to-peer
#       path the self reference permitted as an unintended side effect.
resource "aws_vpc_security_group_egress_rule" "app_to_endpoints" {
  security_group_id            = aws_security_group.app.id
  referenced_security_group_id = aws_security_group.app.id
  description                  = "Allow CardDemo tasks to initiate TLS sessions to the private AWS service endpoint ENIs"
  ip_protocol                  = "tcp"
  from_port                    = local.https_port
  to_port                      = local.https_port
}

resource "aws_vpc_security_group_ingress_rule" "app_to_endpoints" {
  security_group_id            = aws_security_group.app.id
  referenced_security_group_id = aws_security_group.app.id
  description                  = "Allow the private AWS service endpoint ENIs to receive TLS from CardDemo application tasks only"
  ip_protocol                  = "tcp"
  from_port                    = local.https_port
  to_port                      = local.https_port
}

# =============================================================================
# The application tier has NO public-internet egress rule, and its absence is
# the control rather than an omission.
# -----------------------------------------------------------------------------
# WHY : ⚠️ Refactoring Rationale: an `app_to_identity_provider` egress rule stood
#       here, created once per entry of an `identity_provider_egress_cidrs` input
#       whose default was `0.0.0.0/0`. Both are withdrawn. It was the one rule in
#       this module that let a task open a TLS session to ANY public destination,
#       which in a cardholder-data workload is an exfiltration path that the rest
#       of this topology -- an isolated data tier with no route out, and every AWS
#       dependency reached over a private endpoint -- exists to deny. Its own
#       Trade-offs note conceded the width and offered narrowing as an operator
#       action, which is a control nobody applies in the environment that ships.
# WHY : Assumptions: nothing in the application tier needs that rule any more, and
#       the reason is the endpoint set rather than an argument about likelihood.
#       Every destination a task reaches at start-up and in service now resolves
#       inside the VPC: images through `ecr.api`, `ecr.dkr` and the S3 gateway
#       route; configuration through `ssm`; credentials through `secretsmanager`;
#       envelope keys through `kms`; queues through `sqs`; workflow calls through
#       `states`; logs through `logs`; trace segments through `xray`; and the
#       identity provider through `cognito-idp`, which carries BOTH the user pools
#       API the auth context calls to authenticate a sign-on and the discovery and
#       key-set documents every service fetches before it can validate a token,
#       because the provider hosts those documents on the API host this endpoint
#       intercepts. `private_dns_enabled` is set on every interface endpoint, which
#       is what makes the public service name resolve to the endpoint ENI without a
#       single service changing an address.
#       Refactoring Rationale: the withdrawn rule's rationale asserted that "the
#       mandated endpoint set is validated as EXACT in variables.tf and the identity
#       provider is not in it". That stopped being true when `cognito-idp` and
#       `xray` were added to that exact set, so the rule's stated justification had
#       already lapsed while the rule itself remained.
# WHY : Assumptions: what the application group is permitted is now exactly and
#       only the four flows the rules above and below declare -- to the interface
#       endpoint group on 443, to the internal load-balancer group on 443 for
#       service-to-service calls, to the S3 gateway prefix list on 443, and to the
#       data group on the database port. There is no rule with a `0.0.0.0/0`
#       destination anywhere in this module, and `variables.tf` no longer accepts an
#       input that could reintroduce one, so open application-tier egress is not
#       merely unconfigured -- it is unexpressible without editing this file under
#       review.
#       Trade-offs: an AWS service this migration later adopts, and for which no
#       interface endpoint is configured, will be refused at the group rather than
#       routed through NAT. That is the intended failure: it surfaces as a task that
#       cannot reach a dependency, in the plan and in the flow log, instead of as a
#       silent public path that nobody revisits. The remedy is to add the endpoint
#       to `interface_endpoint_services`, which is a reviewed change to the exact
#       set, and NOT to reopen an egress rule.
#       Assumptions: the per-zone NAT gateways stay, and they are not made pointless
#       by this. The public subnets keep them for the route they give the public tier
#       and for any future workload placed there; what changed is that no
#       application-tier security group permits traffic toward them.
# =============================================================================

# WHY : The rule below cannot be written as a security-group reference the way
#       the interface-endpoint pair above is. A gateway endpoint installs a
#       route-table entry pointing at this prefix list; it places no elastic
#       network interface and therefore carries no security group for the
#       application group to reference. Egress to S3 is consequently evaluated
#       against the destination addresses in this list, and a security group
#       whose only egress rule references the endpoint group matches none of
#       them.
#       Assumptions: the list id is Region-scoped and assigned by AWS, so it is
#       read at plan time rather than written down. Alternatives Considered:
#       hard-coding the id, or listing S3's published address ranges as CIDRs.
#       Both rejected - the id differs per Region and the ranges change without
#       notice, so either form would need editing to stay correct, and a stale
#       value fails as dropped traffic rather than as a plan error.
data "aws_ec2_managed_prefix_list" "s3" {
  name = "com.amazonaws.${data.aws_region.current.region}.s3"
}

# WHY : This closes a gap that made every object-storage call from the
#       application and batch tiers fail. The route existed - both
#       private-application and isolated-data route tables are associated with
#       the S3 gateway endpoint below - but a route only decides where admitted
#       traffic goes, and the security group decides whether it is admitted at
#       all. With the endpoint reference as the group's only egress, the packet
#       was dropped at the ENI before the route table was ever consulted, so the
#       gateway endpoint was provisioned and unreachable.
#
#       Assumptions: this is not an internet path and does not weaken the tier
#       boundaries. A prefix-list destination resolves to the address ranges of
#       one AWS service, the traffic leaves through the gateway endpoint's route
#       entry rather than through NAT or the internet gateway, and the ranges
#       carry nothing else.
#       Alternatives Considered: (a) an interface endpoint for S3 so the existing
#       endpoint-group reference would cover it - rejected, the sibling endpoint
#       resource documents why the gateway form was chosen, and switching would
#       add an hourly charge per zone to buy a rule this one line already
#       expresses; (b) a broad 0.0.0.0/0:443 egress rule, which would also
#       admit S3 - rejected for the reason the earlier revision's allow-all rule
#       was removed, and because it would make the S3 flow indistinguishable
#       from every other outbound flow in a plan diff or a flow log.
resource "aws_vpc_security_group_egress_rule" "app_to_s3_gateway" {
  security_group_id = aws_security_group.app.id
  prefix_list_id    = data.aws_ec2_managed_prefix_list.s3.id
  description       = "Allow CardDemo tasks to reach S3 through the gateway endpoint prefix list"
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
}

# WHY : The isolated-data tier is associated with the same gateway endpoint, and
#       its own comment states the purpose - a snapshot export reaching object
#       storage without any internet path. That association was subject to the
#       same omission: the data group's egress permits nothing outbound, so the
#       association could not carry traffic. This rule is deliberately separate
#       from the application one rather than shared, so removing either tier's
#       object-storage reach is a single-resource plan diff.
#       Trade-offs: the data tier gains one outbound destination it did not have.
#       That is the narrowest possible widening - one service's prefix list on
#       one port - and it does not touch the no-internet-route invariant, which
#       is a property of the route tables and is unchanged.
resource "aws_vpc_security_group_egress_rule" "data_to_s3_gateway" {
  security_group_id = aws_security_group.data.id
  prefix_list_id    = data.aws_ec2_managed_prefix_list.s3.id
  description       = "Allow the isolated data tier to reach S3 through the gateway endpoint prefix list"
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
}
# -----------------------------------------------------------------------------
# Private AWS service endpoints
# -----------------------------------------------------------------------------

# WHY : Refactoring Rationale: this document is NEW. Every interface endpoint
#       previously carried the provider default, which is a full-access endpoint
#       policy -- Allow every action, on every resource, to every principal --
#       so the endpoints added a private network path and no authorisation
#       boundary of their own. That is the gap this closes.
#       Assumptions: the boundary this policy draws is the PRINCIPAL boundary, and
#       drawing exactly that one is the decision rather than an economy. An
#       interface endpoint is reachable from anything with a route into this VPC
#       and it authorises on the caller's credentials, not on where the packet came
#       from; a credential belonging to another account, or a role in another
#       account that a task somehow assumes, would otherwise be able to use these
#       endpoints as an on-ramp to that account's own resources -- reaching, for
#       instance, another account's Secrets Manager over this VPC's private path,
#       leaving no trace in this account's own resource policies. Pinning
#       aws:PrincipalAccount to this account makes every call through these ten
#       endpoints' ENIs attributable to a principal this account owns.
#       Alternatives Considered: additionally narrowing each endpoint to the exact
#       actions its consumers call -- ecr:GetAuthorizationToken,
#       ecr:BatchCheckLayerAvailability, ecr:BatchGetImage and
#       ecr:GetDownloadUrlForLayer on the two ECR endpoints; logs:CreateLogStream,
#       logs:PutLogEvents and logs:DescribeLogStreams on logs;
#       secretsmanager:GetSecretValue; kms:Decrypt; the six SQS actions;
#       states:StartExecution; and the SSM parameter reads. Every one of those is
#       measurable from the task-role documents in the sibling ecs-service and
#       step-functions-batch modules, so the list could be written. It is rejected
#       on maintenance risk, and the risk is specific rather than general: it would
#       create a SECOND action inventory, in a different module from the roles that
#       define the first, and the two would be edited apart. A role that gains an
#       action the endpoint policy does not list fails with an AccessDenied
#       attributed to the endpoint rather than to the role, at runtime, on a call
#       the role is correctly permitted to make -- and the least-privilege
#       narrowing the second inventory buys is close to nil, because every
#       principal holding one of those grants is a task role in this account that
#       needs it. Action scoping is therefore delegated, deliberately and on the
#       record, to the task-role IAM policies, which are the single place the
#       action inventory lives.
#       Trade-offs: what this gives up is that a compromised task role in this
#       account is not further constrained by the endpoint. That is the correct
#       division: the endpoint bounds WHOSE credentials may traverse it, the role
#       bounds what those credentials may do, and the security group bounds what may
#       reach it at all.
data "aws_iam_policy_document" "interface_endpoint" {
  statement {
    sid    = "AllowThisAccountOnly"
    effect = "Allow"

    # WHY : Refactoring Rationale: this list was `["*"]` and is now one
    #       service-prefix wildcard per endpointed service, DERIVED from
    #       var.interface_endpoint_services rather than written out. Two reasons,
    #       and the second is why it is derived rather than literal.
    #       First, `actions = ["*"]` beside `resources = ["*"]` is the
    #       full-administrative-privilege shape, and the repository's material
    #       policy gate fails it -- CKV_AWS_1 and CKV2_AWS_40 both fired on this
    #       document. A skip comment would have silenced them, and that is not
    #       available here: this tree's stated convention is that its gates are
    #       satisfied by construction rather than by exemption, and the CI workflow
    #       asserts an exact inventory of the skips that do exist, so adding one
    #       would fail a different gate.
    #       Second, deriving the list from the endpoint set means the two can never
    #       disagree. An eleventh endpoint added to that set brings its own action prefix
    #       with it, and an endpoint removed takes its prefix away, with no second
    #       edit to remember and no possibility of a policy that permits a service
    #       no endpoint serves.
    #       Assumptions: the split on "." collapses ecr.api and ecr.dkr onto the one
    #       ecr prefix, and toset removes the duplicate, so the set yields nine
    #       prefixes for ten endpoints. That is correct rather than a coincidence
    #       to be preserved carefully: both endpoints front the same service and
    #       authorise against the same ecr action namespace.
    #       Trade-offs: a service-prefix wildcard is not per-action narrowing, and
    #       that is deliberate for the reason recorded above -- a second action
    #       inventory in a different module from the roles that define the first
    #       would be edited apart, and an endpoint policy that lags a role fails at
    #       runtime on a call the role is correctly permitted to make. What this
    #       shape buys over `["*"]` is that these endpoints cannot be used to reach
    #       a service they do not front, which is a real narrowing rather than a
    #       gate-shaped one.
    actions = [
      for service in toset([
        for name in var.interface_endpoint_services : split(".", name)[0]
      ]) : "${service}:*"
    ]

    # WHY : Assumptions: the resource wildcard stays, and it has to. An endpoint
    #       policy is evaluated before the request reaches the service, on requests
    #       whose target may be an account-owned queue or key, an account-owned log
    #       group, or -- for ecr:GetAuthorizationToken -- no resource at all, since
    #       that action supports no resource-level permission. Enumerating resources
    #       here would require this module to know the ARNs of every queue, key,
    #       secret, parameter, log group and state machine its siblings create, none
    #       of which it can see without closing a dependency cycle. Resource scoping
    #       is where the task-role policies do their work; this document's
    #       contribution is the account condition and the service narrowing above.
    resources = ["*"]

    # WHY : Assumptions: the principal block is the wildcard and the NARROWING is
    #       done by the condition, which is the required shape for an endpoint
    #       policy rather than a shortcut. An endpoint policy cannot enumerate the
    #       principals it admits by ARN -- the task roles are created by a sibling
    #       module and naming them here would close a dependency cycle -- so the
    #       account condition is what an endpoint policy has instead. Anything the
    #       single Allow does not cover is refused by the endpoint's own implicit
    #       deny, so no companion Deny statement is needed.
    principals {
      type        = "*"
      identifiers = ["*"]
    }

    condition {
      test     = "StringEquals"
      variable = "aws:PrincipalAccount"
      values   = [data.aws_caller_identity.current.account_id]
    }
  }
}

# WHY : Assumptions: each of the ten services in the set has a named consumer in
#       this system, so the set is exact rather than a convenient round number,
#       and since the application group carries no internet-egress rule at all a
#       service ABSENT from this set is not routed through NAT -- it is dropped at
#       the group. ecr.api and ecr.dkr are the two halves of an image pull, and a
#       task that cannot reach both cannot start. logs carries container log
#       delivery. secretsmanager is read once per task at start-up for the
#       database credential. kms performs the envelope decryption behind that
#       read and behind Aurora and S3 access. sqs carries the authorization and
#       inquiry queues. states is called by reporting-service to start an
#       on-demand batch execution. ssm is read by the batch tasks for the
#       read-only flag that brackets the batch window.
# WHY : Assumptions: the ninth and tenth names have named consumers too. xray carries the
#       trace export section 0.9.3 requires, and cognito-idp carries the identity-provider
#       calls every service makes -- issuer discovery and the JSON web key set at start-up,
#       and auth-service's user-pool operations.
# WHY : Refactoring Rationale: cognito-idp was WITHDRAWN from this set for a period on a
#       finding that was right about the mechanism, and the finding is recorded here
#       because what answers it sits a few lines below rather than at the endpoint itself.
#       The account-scoped document above was attached to EVERY endpoint, while the
#       identity calls that matter on this path are UNAUTHENTICATED by construction --
#       discovery, the key set, and the sign-on, challenge-response, refresh, revoke and
#       sign-out operations a user-pool client performs before or without holding any IAM
#       credential. Those requests carry no principal for the condition to satisfy, so the
#       shared policy denied them and the symptom would have been every sign-on refused at
#       the endpoint rather than an error naming a policy.
#       Assumptions: the answer is a PER-ENDPOINT policy, not removal. Removal's stated
#       fallback was an application-tier egress rule to the provider, and that rule
#       admitted 0.0.0.0/0 and is withdrawn -- .github/workflows/infra-ci.yml now fails any
#       egress rule naming an open destination. With the name absent AND that rule gone
#       there is no path to the provider at all, so the two withdrawals were individually
#       defensible and jointly fatal to sign-on. The identity_provider_endpoint document
#       below therefore SOURCES the shared document, keeping the account condition for the
#       signed administrative calls, and adds one statement admitting exactly the five
#       unauthenticated operations by name with no principal condition.
#       Alternatives Considered: a per-endpoint boolean so a root could disable
#       one. Rejected because every one of the eight is on a start-up or
#       transaction path, so disabling any of them substitutes a public path for
#       a private one silently; variables.tf validates the set as exact instead.
#       Trade-offs: interface endpoints are charged per hour per endpoint per
#       availability zone plus per GB processed, so ten endpoints across three zones fix
#       the hourly term at thirty endpoint-zone-hours. The per-GB part is largely an offset rather
#       than an addition - this traffic stops traversing the NAT gateways, so it
#       no longer accrues NAT data-processing charges.
# WHY : Refactoring Rationale: this document exists because ONE shared endpoint policy
#       cannot serve both kinds of traffic the identity-provider endpoint carries. The
#       shared document admits only principals in this account, which is correct for the
#       nine endpoints whose every caller is a task role signing its requests -- and wrong
#       for this one, whose sign-on traffic is unsigned by construction. Attaching the
#       shared document here denied every sign-on; attaching a document without the account
#       condition to all ten would have widened nine endpoints to any principal in any
#       account. Splitting the attachment is what keeps each endpoint at its own correct
#       boundary.
# WHY : Assumptions: the shared document is SOURCED rather than restated, so the account
#       condition, the derived service-prefix action list and the resource wildcard here
#       are literally the same statement the other nine endpoints get. A second copy would
#       be edited apart, and the failure of a copy that lagged would be an authorization
#       denial on a call a task role is correctly permitted to make.
# WHY : Assumptions: the added statement enumerates FIVE operations rather than granting
#       cognito-idp:* without a principal condition. These are exactly the unauthenticated
#       user-pool operations com.carddemo.auth.service.CognitoIdentityService performs --
#       InitiateAuth and RespondToAuthChallenge for sign-on and the new-password challenge,
#       GetTokensFromRefreshToken for rotation, and RevokeToken and GlobalSignOut for
#       sign-off, each of which authenticates with a token in the request body and carries
#       no signature. Every ADMINISTRATIVE operation the service calls -- AdminCreateUser,
#       AdminGetUser, AdminSetUserPassword, AdminAddUserToGroup, AdminDeleteUser -- is
#       signed by the task role and is admitted by the sourced statement instead, so it
#       remains bounded by the account condition. An unauthenticated caller reaching this
#       endpoint therefore gains the five operations any client of a public user pool may
#       already call, and nothing administrative.
# WHY : Assumptions: OIDC discovery and the JSON web key set need no entry. They are plain
#       HTTPS GETs on the same API host rather than IAM-authorized actions, so no endpoint
#       policy statement governs them; what they need is the private DNS interception the
#       endpoint below enables, which is why start-up issuer resolution works over this
#       path at all.
# WHY : Alternatives Considered: conditioning the added statement on the user-pool ARN so
#       it could not be used against a pool in another account. Rejected because these
#       operations take a CLIENT ID rather than a pool ARN and support no resource-level
#       permission, so the condition would deny every call it was meant to scope.
data "aws_iam_policy_document" "identity_provider_endpoint" {
  source_policy_documents = [data.aws_iam_policy_document.interface_endpoint.json]

  statement {
    sid    = "AllowUnauthenticatedIdentityOperations"
    effect = "Allow"

    actions = [
      "cognito-idp:InitiateAuth",
      "cognito-idp:RespondToAuthChallenge",
      "cognito-idp:GetTokensFromRefreshToken",
      "cognito-idp:RevokeToken",
      "cognito-idp:GlobalSignOut",
    ]

    resources = ["*"]

    principals {
      type        = "*"
      identifiers = ["*"]
    }
  }
}

resource "aws_vpc_endpoint" "interface" {
  for_each = var.interface_endpoint_services

  vpc_id = aws_vpc.this.id

  # WHY : Refactoring Rationale: the Region comes from the data source rather
  #       than a literal or a variable, so one module body is correct in any
  #       Region the root selects. The AWS provider v6 line renamed this data
  #       source's `name` attribute to `region` and deprecated the former, so
  #       `.region` is correct under the pinned `~> 6.56` constraint - it is not
  #       a typo to be "fixed" back to `.name`.
  service_name      = "com.amazonaws.${data.aws_region.current.region}.${each.value}"
  vpc_endpoint_type = "Interface"

  # WHY : Assumptions: private DNS is what makes the endpoint transparent. With
  #       it, an SDK's default service hostname resolves to the endpoint ENI, so
  #       no application code or configuration changes. Without it the endpoint
  #       exists and nothing uses it - the hostname resolves to a public address
  #       that no egress rule on the application group matches, so the call is
  #       dropped at the group and presents as an unreachable dependency rather
  #       than as a configuration error naming the cause.
  #       Refactoring Rationale: this note said such a call "leaves through NAT",
  #       which was true only while an unrestricted 443 egress rule existed. That
  #       rule is withdrawn, so the failure mode is now a drop rather than a
  #       silent public detour - a worse outage and a far easier diagnosis.
  private_dns_enabled = true

  # WHY : Assumptions: the ENIs belong in the private-application tier because
  #       that is where the callers are. ENIs in the isolated tier would be
  #       unreachable by the tasks, and ENIs in the public tier would put the
  #       endpoint on the one tier with a route to the internet gateway, which
  #       is the path the endpoints exist to avoid.
  subnet_ids = [
    for zone in local.availability_zones :
    aws_subnet.private_app[zone].id
  ]
  # WHY : Assumptions: the ENIs carry the APPLICATION group, so the
  #       task-to-endpoint flow is a self-referencing 443 rule on that group. The
  #       comment block at that rule pair records why, and records the one
  #       permission that widens as a result.
  #       Refactoring Rationale: a dedicated endpoint group carried these ENIs for
  #       a period and was the narrower arrangement. It is withdrawn because the
  #       frozen plan specifies three security groups for this module (AAP section
  #       0.5.1.12); the re-permitted task-to-task 443 path reaches no listener,
  #       because app_container_port cannot be 443 and the only 443 listener in
  #       this VPC carries the ALB group.
  security_group_ids = [aws_security_group.app.id]

  # WHY : Assumptions: nine of the ten endpoints share ONE document rather than taking a
  #       per-service one, because the boundary it draws -- this account's principals only
  #       -- is identical for every service whose callers sign their requests, and does not
  #       vary with the action set. Its full reasoning, and the per-service action narrowing
  #       that was considered and rejected, are recorded above the document itself.
  # WHY : Assumptions: the identity-provider endpoint is the ONE exception, and the
  #       conditional is keyed on the service name rather than on a boolean input so no
  #       root can attach the wrong document. That endpoint carries unsigned sign-on
  #       traffic that the account condition cannot admit; the document selected for it
  #       sources this one and adds the five unauthenticated operations, for the reasons
  #       recorded above that document.
  policy = each.value == local.identity_provider_endpoint_service ? data.aws_iam_policy_document.identity_provider_endpoint.json : data.aws_iam_policy_document.interface_endpoint.json

  tags = merge(var.tags, {
    Name = "${local.name_stem}-vpce-${replace(each.value, ".", "-")}"
  })
}

# WHY : Alternatives Considered: an interface endpoint for S3, matching the
#       ten above. Rejected on both mechanism and cost. Mechanically a gateway
#       endpoint is a route-table entry pointing at a managed service prefix
#       list, not an ENI with a security group - which is why it is associated
#       with route tables rather than subnets, and why it needs no rule on the
#       endpoint group. On cost, a gateway endpoint carries no hourly charge at
#       all, where an interface endpoint would add one per zone.
#       Trade-offs: a gateway endpoint is reachable only from inside the VPC and
#       cannot be called from on-premises over a private connection. Nothing in
#       this system needs that, and it is what the absent hourly charge buys.
#       Assumptions: this endpoint deliberately keeps the provider's default
#       full-access policy where the ten interface endpoints above are narrowed to
#       this account's principals, and the asymmetry is required rather than an
#       oversight. An ECR image pull downloads its LAYERS from an AWS-owned S3
#       bucket using a presigned URL, and a presigned request does not carry
#       aws:PrincipalAccount for this account; a StringEquals condition on that key
#       therefore evaluates false and the endpoint would refuse the pull, so every
#       task would fail to start with an error naming S3 rather than the endpoint
#       policy. Applying the same document here would break the deployment.
#       Alternatives Considered: narrowing by resource instead, naming the dataset
#       and access-log bucket ARNs. Rejected for the same reason plus a structural
#       one: this module does not know those bucket names -- the sibling s3-datasets
#       and alb modules own them and both already consume values from here -- and
#       any resource list narrow enough to be worth writing would have to include
#       the Region's AWS-owned starport layer bucket, whose name is an
#       implementation detail of a service rather than a contract.
#       Trade-offs: the network boundary for S3 is therefore enforced on the BUCKET
#       rather than on the endpoint. The sibling s3-datasets module denies object
#       reads and writes whose aws:SourceVpce is not this endpoint, which is the
#       narrower control of the two because it names one bucket and three actions;
#       s3_gateway_endpoint_id is published by outputs.tf so the roots can wire it,
#       and that is what makes this division of responsibility a wiring fact rather
#       than a comment.
resource "aws_vpc_endpoint" "s3" {
  vpc_id            = aws_vpc.this.id
  service_name      = "com.amazonaws.${data.aws_region.current.region}.s3"
  vpc_endpoint_type = "Gateway"

  tags = merge(var.tags, {
    Name = "${local.name_stem}-vpce-s3"
  })

  lifecycle {
    # WHY : Assumptions: the prefix list identifier is the destination of the
    #       application tier's S3 egress rule, so it has to resolve to a value.
    #       A gateway endpoint populates it and an interface endpoint does not,
    #       which means an edit changing vpc_endpoint_type here would leave that
    #       rule with an empty destination. Asserting it on the endpoint puts the
    #       failure where the cause is, rather than on the rule that consumes it.
    postcondition {
      condition     = self.prefix_list_id != ""
      error_message = "the S3 gateway endpoint resolved no prefix list identifier, so aws_vpc_security_group_egress_rule.app_to_s3 would have no destination and the application tier would reach object storage through neither the route nor the rule."
    }
  }
}

# WHY : Alternatives Considered: the route_table_ids argument on the endpoint
#       itself, which expresses the same associations in one list. Rejected for
#       the reason the security-group rules above are separate resources - an
#       association declared as its own resource is individually addressable, so
#       adding or removing one tier's association is a single-resource plan diff
#       rather than an in-place rewrite of a list. The two forms also conflict if
#       both are ever present on one endpoint, so only this one is used.
#       Assumptions: the private-application association is what lets the batch
#       and ETL tasks read and write the dataset generations that the sibling
#       s3-datasets module provisions.
resource "aws_vpc_endpoint_route_table_association" "private_app" {
  for_each = aws_route_table.private_app

  vpc_endpoint_id = aws_vpc_endpoint.s3.id
  route_table_id  = each.value.id

  lifecycle {
    # WHY : Assumptions: object storage reachability from the application tier is
    #       three separate permissions - this association, the egress rule on the
    #       application group, and the endpoint itself - and all three have to hold
    #       for a dataset read to succeed. The association count is asserted
    #       against the zone count because a partial for_each would leave one
    #       zone's tasks silently unable to reach S3 while the other zones worked,
    #       which is the hardest shape of this failure to diagnose from a log.
    precondition {
      condition     = length(aws_route_table.private_app) == length(local.availability_zones)
      error_message = "every private-application route table must be associated with the S3 gateway endpoint; a subset would leave the tasks in one availability zone unable to reach object storage while the others succeeded."
    }
  }
}

# WHY : Assumptions: associating the isolated-data route tables does not weaken
#       the no-route invariant asserted above, and the two statements do not
#       contradict each other. The entry a gateway endpoint installs is a route
#       to a managed service prefix list, not a default route: it can carry
#       traffic to that one service and to nothing else, and it has neither a NAT
#       nor an internet-gateway next hop. The association is made so that the
#       ROUTE is in place if an S3-using capability is ever added to the data tier,
#       without that change also having to touch this module's routing.
#       Refactoring Rationale: this note previously concluded that "the data tier
#       therefore reaches object storage - for a snapshot export, for instance".
#       It does not, and the same mistake was made here as with the application
#       tier: a route is necessary for reachability but not sufficient, because the
#       packet still leaves an interface governed by a security group. The Aurora
#       group carries exactly one rule -- ingress on the database port from the
#       application group -- and no egress rule at all, so its implicit deny stops
#       this traffic. No egress rule is added to close that gap, because unlike the
#       application tier there is nothing to unblock: the sibling
#       aurora-postgresql module configures no aws_s3 extension, no S3 import or
#       export role and no snapshot-export destination, so no configured capability
#       uses the path. Adding an egress rule to make a documented sentence true
#       would be giving the isolated tier an outbound permission nothing needs,
#       which is the opposite of what that tier exists for. The claim is narrowed
#       to what is actually provisioned instead.
resource "aws_vpc_endpoint_route_table_association" "isolated_data" {
  for_each = aws_route_table.isolated_data

  vpc_endpoint_id = aws_vpc_endpoint.s3.id
  route_table_id  = each.value.id

  lifecycle {
    # WHY : Assumptions: the data tier's reachability to object storage is this
    #       association and nothing else. It carries no egress rule toward the
    #       prefix list, because the data group admits only inbound sessions from
    #       the application group and initiates nothing outbound that this module
    #       permits; the snapshot export the association exists for is initiated by
    #       the managed service rather than by a client in this group. Asserting
    #       the zone count here for the same reason as above also records that the
    #       no-default-route invariant is unaffected: this adds a prefix-list route
    #       and no default route, in every zone equally.
    precondition {
      condition     = length(aws_route_table.isolated_data) == length(local.availability_zones)
      error_message = "every isolated-data route table must be associated with the S3 gateway endpoint; a subset would leave one availability zone's data-tier subnet without the prefix-list route the others have."
    }
  }
}

# -----------------------------------------------------------------------------
# VPC flow logs
# -----------------------------------------------------------------------------

# WHY : Assumptions: flow logging is something the target adds, not something it
#       carries over. The baseline records no network or data audit trail to
#       preserve - all eight file resources in app/csd/CARDDEMO.CSD are defined
#       with JOURNAL(NO) (L7) and RECOVERY(NONE) (L9), which is a factual
#       property of that configuration rather than a shortcoming, and the
#       mainframe path continues to run exactly as it does today. With no
#       trail to port, the target's is specified directly here.
#       Alternatives Considered: putting this group in the sibling observability
#       module with the application log groups, dashboards and alarms. Rejected
#       on lifecycle: a flow log cannot outlive its VPC, and this group is
#       created and destroyed with the VPC, so the boundary is drawn at "what
#       shares the VPC's lifecycle" rather than at "everything that logs".
#       Alternatives Considered: delivering to object storage instead. Rejected
#       because retention here is a single numeric attribute the environment
#       roots already vary, and the group is queryable without a separate
#       ingestion step.
#       Trade-offs: object storage would be cheaper for long retention, at the
#       cost of a query path this module would then have to describe.
resource "aws_cloudwatch_log_group" "flow_logs" {
  name              = "/aws/vpc/${local.name_stem}/flow-logs"
  retention_in_days = var.flow_log_retention_days

  # WHY : Trade-offs: the key is an optional input defaulting to null, which
  #       falls back to service-default encryption and keeps this module free of
  #       any hard dependency on the sibling kms module - it can be planned on
  #       its own. Assumptions: both environment roots pass the customer-managed
  #       key, so the encrypted path is the one that actually ships; the
  #       condition is resolved at the root rather than waived here.
  kms_key_id = var.flow_log_kms_key_arn

  tags = merge(var.tags, {
    Name = "${local.name_stem}-vpc-flow-logs"
  })
}

# WHY : Refactoring Rationale: both policies are built with the IAM policy
#       document data source rather than jsonencode. The data source validates
#       structure at plan time and renders canonical JSON, so a misspelled
#       argument is a plan error instead of a document that applies cleanly and
#       grants nothing. Alternatives Considered: an inline jsonencode object,
#       which is shorter but accepts any key name silently.
# WHY : Refactoring Rationale: the two condition blocks below are NEW. The trust
#       policy previously named only the delivery service as principal, with no
#       condition at all, which is the textbook confused-deputy shape: any AWS
#       account able to create a flow log could name THIS role as its delivery
#       role, and the delivery service -- acting on that third party's behalf but
#       with this role's credentials -- would write that account's flow records
#       into this VPC's log group. The account would not be able to read them
#       back, so the loss is integrity and cost rather than confidentiality: an
#       audit trail specified to record this VPC's traffic would silently carry
#       another VPC's, and this account would be billed for the ingestion.
#       Assumptions: the two conditions bound different things and both are
#       needed. aws:SourceAccount pins the caller to this account, which alone
#       closes the cross-account case. aws:SourceArn pins it further to flow-log
#       resources within this account and Region, so a different flow log in the
#       same account -- one an operator creates by hand against another VPC --
#       also cannot borrow this role. Neither is expressible as a principal, which
#       is why the principal block alone could not carry this.
#       Assumptions: the source ARN is a wildcard over flow-log ids rather than
#       one exact ARN, and that is a construction limit rather than a choice: the
#       role must exist before aws_flow_log.this can reference it, so its own id
#       is unknown while this document is being built. Naming it exactly would
#       close a cycle between the role and the flow log. The wildcard is bounded to
#       this account, this Region and the vpc-flow-log resource type, which is the
#       narrowest form available without that cycle.
#       Alternatives Considered: a two-stage apply that creates the role, reads
#       back the flow-log ARN and narrows the condition. Rejected because it makes
#       a single-command apply impossible, which the deployment criterion requires.
data "aws_iam_policy_document" "flow_logs_assume_role" {
  statement {
    sid     = "VPCFlowLogsAssumeRole"
    effect  = "Allow"
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["vpc-flow-logs.amazonaws.com"]
    }

    # WHY : Both conditions close the confused-deputy hole a bare service-
    #       principal trust leaves open. Without them the trust reads "the VPC
    #       Flow Logs service may assume this role", with no statement about
    #       WHOSE flow log it is acting for - so the service would assume it on
    #       behalf of a flow log in any account that named this role's ARN, and
    #       that account's traffic metadata would land in this account's log
    #       group. The account key bounds whose flow log may drive the
    #       assumption; the ARN key bounds which resource within that account.
    #       Neither key alone is sufficient: the account key still admits any
    #       ARN-shaped source in the account, and the ARN key is only as strong
    #       as the account segment inside the pattern.
    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [data.aws_caller_identity.current.account_id]
    }

    # WHY : Assumptions: the source ARN is the flow-log resource itself, whose id
    #       is assigned by the API at create time and therefore cannot be known
    #       while the role it depends on is still being planned. Naming it
    #       exactly would make the role depend on the flow log and the flow log
    #       depend on the role.
    #       Alternatives Considered: (a) referencing
    #       aws_flow_log.main.arn directly - rejected, that is the cycle just
    #       described; (b) two applies, the first creating the role with a
    #       permissive condition and the second narrowing it - rejected, an
    #       idempotent single-apply module is an acceptance criterion, and a
    #       half-applied state would be the widest one. What remains is a
    #       pattern bounded on partition, service, Region, account and resource
    #       TYPE, leaving only the resource id open - so the only sources it
    #       admits are flow logs this account owns in this Region.
    condition {
      test     = "ArnLike"
      variable = "aws:SourceArn"
      values = [
        format(
          "arn:%s:ec2:%s:%s:vpc-flow-log/*",
          data.aws_partition.current.partition,
          data.aws_region.current.region,
          data.aws_caller_identity.current.account_id,
        )
      ]
    }
  }
}

data "aws_iam_policy_document" "flow_logs" {
  # WHY : Assumptions: the log group is created by this module and its ARN is
  #       handed to the flow log directly, so CreateLogGroup is deliberately
  #       absent from the grant. Alternatives Considered: the resource wildcard
  #       used by the published example policy. Rejected because it would let
  #       this role write into any log group in the account, including the
  #       application and audit groups the observability module owns; naming one
  #       group's stream ARN is the boundary least privilege actually requires.
  statement {
    sid    = "WriteVPCFlowLogStreams"
    effect = "Allow"
    actions = [
      "logs:CreateLogStream",
      "logs:DescribeLogStreams",
      "logs:PutLogEvents",
    ]
    resources = ["${aws_cloudwatch_log_group.flow_logs.arn}:*"]
  }

  # WHY : DescribeLogGroups is in the delivery service's documented minimum
  #       permission set, so the action stays. What changed is its resource
  #       element: an unqualified `*` matches every log group in every account
  #       and every Region of every partition, and that is what the frozen
  #       wildcard-free IAM construction requirement is about - not the trailing
  #       segment of an otherwise-qualified ARN.
  #
  #       Assumptions: this call authorises on the log-group resource type but
  #       cannot be narrowed to ONE group - the caller is discovering which
  #       groups exist, so a single-group ARN denies the request outright. The
  #       AWS CloudWatch Logs guidance for exactly this situation is to qualify
  #       the ARN down to account and Region and leave only the group-name
  #       segment open: `arn:<partition>:logs:<region>:<account>:log-group:*`.
  #       That is the form used here, built from the partition, Region and
  #       account this module is applied into rather than written out, so it
  #       cannot drift from where it is deployed.
  #
  #       Alternatives Considered: (a) keeping the bare `*` and documenting it as
  #       required - rejected, it is not required, only the log-group resource
  #       TYPE is, and the bare form additionally admits other partitions and
  #       Regions; (b) omitting the action to remove the wildcard entirely -
  #       rejected, dropping a documented-required permission trades a bounded
  #       resource element for a delivery failure that surfaces only after
  #       apply; (c) naming the single flow-log group's ARN - rejected, that is
  #       the denial described above.
  #       Trade-offs: the role can still enumerate log-group metadata for other
  #       groups in this account and Region. That is the smallest scope this API
  #       accepts, it is read-only, it returns no log content, and every
  #       mutating action stays pinned to the single group in the statement
  #       above.
  statement {
    sid     = "DiscoverVPCFlowLogGroup"
    effect  = "Allow"
    actions = ["logs:DescribeLogGroups"]
    resources = [
      format(
        "arn:%s:logs:%s:%s:log-group:*",
        data.aws_partition.current.partition,
        data.aws_region.current.region,
        data.aws_caller_identity.current.account_id,
      )
    ]
  }
}

resource "aws_iam_role" "flow_logs" {
  name               = "${local.name_stem}-vpc-flow-logs"
  assume_role_policy = data.aws_iam_policy_document.flow_logs_assume_role.json

  # WHY : ⚠️ Refactoring Rationale: this role carried no boundary while both
  #       environment roots described their `permissions_boundary_arn` as applying to
  #       "every role this deployment creates". It is attached here rather than left
  #       to the caller because a caller cannot attach a boundary to a role it does
  #       not declare -- the promise was unkeepable from outside this module.
  # WHY : Assumptions: a flow-log delivery role looks harmless enough to except, and
  #       is deliberately not excepted. Its inline policy grants CreateLogStream and
  #       PutLogEvents, and the boundary is what stops a later edit here from
  #       widening that to a log group this deployment does not own.
  permissions_boundary = var.permissions_boundary_arn

  tags = merge(var.tags, {
    Name = "${local.name_stem}-vpc-flow-logs"
  })

  lifecycle {
    precondition {
      # WHY : Assumptions: a boundary in ANOTHER account is not an error IAM reports --
      #       it is accepted and then bounds nothing, because the policy it names does
      #       not resolve. Comparing the ARN's account field against the caller's is
      #       what turns that silent no-op into a plan-time failure.
      condition     = split(":", var.permissions_boundary_arn)[4] == data.aws_caller_identity.current.account_id
      error_message = "permissions_boundary_arn must belong to the same AWS account as the VPC flow-log role."
    }
  }
}

resource "aws_iam_role_policy" "flow_logs" {
  name   = "${local.name_stem}-vpc-flow-logs"
  role   = aws_iam_role.flow_logs.id
  policy = data.aws_iam_policy_document.flow_logs.json
}

resource "aws_flow_log" "this" {
  vpc_id                   = aws_vpc.this.id
  traffic_type             = "ALL"
  log_destination_type     = "cloud-watch-logs"
  log_destination          = aws_cloudwatch_log_group.flow_logs.arn
  iam_role_arn             = aws_iam_role.flow_logs.arn
  max_aggregation_interval = 60

  # WHY : Alternatives Considered: ACCEPT or REJECT alone. Each records only
  #       half of the audit trail; ALL is fixed rather than parameterised
  #       because no environment is permitted to omit successful or refused
  #       network flows.
  tags = merge(var.tags, {
    Name = "${local.name_stem}-vpc-flow-log"
  })

  depends_on = [aws_iam_role_policy.flow_logs]
}
