# =============================================================================
# infra/modules/network/main.tf
# -----------------------------------------------------------------------------
# Purpose:
#   Provisions the complete single-Region network boundary for CardDemo: one
#   VPC spanning up to three availability zones; public, private-application
#   and isolated-data subnets in every zone; one NAT gateway per zone; private
#   interface paths to eight AWS services; an S3 gateway path; the security
#   groups governing every permitted tier-to-tier flow; and encrypted VPC flow
#   logs.
#
# Parameters:
#   All eleven inputs are declared, typed, described and validated in
#   variables.tf; this file only consumes them. name_prefix (string) and
#   environment (string) compose every resource name. vpc_cidr (string),
#   az_count (number) and subnet_newbits (number) drive the address arithmetic
#   in locals. interface_endpoint_services (set(string)) drives the
#   interface-endpoint for_each. app_container_port (number) and database_port
#   (number) are consumed by security-group rules and republished by outputs.tf
#   so a root passes one value to both a rule and its listener. tags
#   (map(string)) merges into every taggable resource.
#   flow_log_retention_days (number) and flow_log_kms_key_arn (string, nullable)
#   configure the flow-log group.
#
# Return values:
#   This file declares no output. outputs.tf publishes twenty-one values read
#   by the environment roots and their sibling modules: the VPC id and CIDR,
#   the resolved availability-zone list, the three per-tier subnet-id lists,
#   the three consumer-facing security-group ids (alb, app, data), the public
#   route-table id and the two per-zone route-table id maps, the NAT gateway
#   ids and public addresses, the interface-endpoint id map and the S3 gateway
#   endpoint id, the two shared port contracts, and the flow-log group name,
#   group ARN and flow-log id.
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
#   - Alternatives Considered: attaching the interface-endpoint ENIs to one of
#     the three consumer-facing groups, so that exactly three groups exist. An
#     interface endpoint must carry a group, so the task-to-endpoint flow has to
#     terminate somewhere. Putting the ENIs on the application group needs a
#     self-referencing 443 rule, which would also permit task-to-task 443;
#     putting them on the ALB group needs an application-to-ALB 443 rule, which
#     would let every task open 443 on the edge listener group. Both widen a
#     flow beyond the three this topology allows, so a fourth group exists that
#     nothing but the endpoints attaches to. It is created but not published,
#     because no sibling module attaches to it.
#     Trade-offs: one more group to reason about, accepted in exchange for a
#     rule set in which each permitted flow has exactly one source group and
#     one destination group.
#   - Trade-offs: the application group's egress is enumerated rather than left
#     as the implicit allow-all a new group would otherwise carry. Only the two
#     destinations above are reachable, so any future outbound dependency has to
#     be added as a named rule. That is deliberate friction: it keeps the
#     egress surface reviewable in the diff instead of invisible in a default.
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

locals {
  name_stem = "${var.name_prefix}-${var.environment}"

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
  #       names resolve to their public addresses - a failure that raises no
  #       error and shows up only as traffic taking the NAT path instead.
  #
  # WHY : Assumption: this merge form is used on every taggable resource in the
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

# WHY : Assumption: Terraform does not create this group - AWS creates one per
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

# WHY : Assumption: this is the VPC's only path to and from the internet, and it
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
#       Rejected for two independent reasons. Availability: losing the gateway's
#       zone would remove egress from every private-application subnet, so a
#       single zone failure becomes a whole-tier outage, and the two surviving
#       zones would meanwhile be paying a cross-zone data path for every
#       outbound byte. Parity: dev and prod are specified to differ in sizing and
#       retention only, never in topology, because a dev environment with a
#       different network shape cannot falsify the prod one - so a single-NAT
#       cost toggle is not available to this module.
#       Trade-offs: stated plainly, NAT gateways are charged per hour per gateway
#       plus per GB processed, so one per zone multiplies the hourly term by
#       three, and that is the single largest fixed cost in this module. It is
#       accepted rather than minimised. The interface endpoints below claw back
#       part of the per-GB term by keeping AWS API traffic off these gateways
#       altogether.
resource "aws_nat_gateway" "this" {
  for_each = aws_subnet.public

  allocation_id = aws_eip.nat[each.key].id
  subnet_id     = each.value.id

  tags = merge(var.tags, {
    Name = "${local.name_stem}-nat-${each.key}"
  })

  # WHY : Assumption: a NAT gateway cannot be created until the internet gateway
  #       is attached to the VPC, and nothing in this resource's arguments
  #       references it, so Terraform cannot infer that ordering on its own. The
  #       dependency is declared rather than discovered.
  depends_on = [aws_internet_gateway.this]
}

# WHY : Assumption: these tables are per-zone precisely because there is a NAT
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
# WHY : Assumption: stated mechanically, the isolation here is a routing fact
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

# WHY : Alternatives Considered: reusing one of the three consumer-facing groups
#       for the interface-endpoint ENIs, which would leave exactly three groups.
#       An interface endpoint has to carry a group, so the task-to-endpoint flow
#       must terminate on some group. Reusing the application group requires a
#       self-referencing 443 rule, which would also permit task-to-task 443 and
#       widen the blast radius the isolated-data tier exists to narrow. Reusing
#       the ALB group requires an application-to-ALB 443 rule, which would let
#       every task reach the edge listener group on 443 - a flow no component
#       makes. A dedicated group keeps each of the three permitted flows at
#       exactly one source and one destination.
#       Assumption: this is the one group this module attaches itself, so it is
#       deliberately absent from outputs.tf; no sibling module has anything to
#       attach to it.
resource "aws_security_group" "vpc_endpoints" {
  name_prefix            = "${local.name_stem}-vpce-"
  description            = "Interface VPC endpoint ENIs receiving only CardDemo application-tier TLS"
  vpc_id                 = aws_vpc.this.id
  revoke_rules_on_delete = true

  tags = merge(var.tags, {
    Name = "${local.name_stem}-vpce-sg"
  })
}

# WHY : Alternatives Considered: inline ingress and egress blocks on each group
#       instead of the separate rule resources used below. Rejected for three
#       reasons. A rule declared as its own resource is individually
#       addressable, so a plan shows which single rule changed rather than
#       replacing an opaque set. The two forms conflict if a group ever ends up
#       carrying both, and committing to one form here removes that failure mode
#       instead of documenting it. And a rule resource carries its own
#       description attribute, which is where each flow's reason is recorded -
#       an inline block has nowhere to put it.
#       Assumption: every rule below names its peer with
#       referenced_security_group_id rather than a CIDR block. A CIDR rule
#       admits whatever happens to hold an address in range, whereas a group
#       reference admits only the role actually attached to that group, and it
#       stays correct if a subnet is ever resized or a zone added.
#
# The edge-to-listener rule is deliberately absent from this module. API Gateway
# VPC Link ENIs carry a dedicated group that api-gateway-http creates beside the
# link, and that module opens this ALB group from it by passing
# alb_security_group_id into aws_vpc_security_group_ingress_rule.alb_from_vpc_link_https.
# WHY : Alternatives Considered: declaring the rule here as a self reference on
# the ALB group, on the premise that the link and the load balancer share one
# group. Rejected because the link holds its own group, so a self reference
# would permit ALB-to-ALB 443 that no component uses while leaving the real
# source group unnamed, and it would add a fourth flow to the three this
# topology is specified to allow: balancer to application, application to
# Aurora, and application to interface endpoint. Authoring it here is also not
# open to this module, because api-gateway-http already consumes
# alb_security_group_id from here, so referencing that module's group in return
# would close a cycle between the two.
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

# WHY : Trade-offs: this pair is the whole of the application tier's AWS API
#       reachability. An earlier revision of this module also carried an egress
#       rule to 0.0.0.0/0 on 443 for managed dependencies outside the mandated
#       eight-service set, which was a fourth flow beyond the three this
#       topology allows. It was removed: the application group's egress is now
#       enumerated, so a future outbound dependency has to arrive as a named
#       rule that shows up in a plan diff rather than being absorbed by an
#       allow-all default. The friction is the point.
resource "aws_vpc_security_group_egress_rule" "app_to_endpoints" {
  security_group_id            = aws_security_group.app.id
  referenced_security_group_id = aws_security_group.vpc_endpoints.id
  description                  = "Allow CardDemo tasks to initiate TLS sessions to private AWS service endpoints"
  ip_protocol                  = "tcp"
  from_port                    = 443
  to_port                      = 443
}

resource "aws_vpc_security_group_ingress_rule" "app_to_endpoints" {
  security_group_id            = aws_security_group.vpc_endpoints.id
  referenced_security_group_id = aws_security_group.app.id
  description                  = "Allow private AWS service endpoints to receive TLS from CardDemo tasks only"
  ip_protocol                  = "tcp"
  from_port                    = 443
  to_port                      = 443
}

# -----------------------------------------------------------------------------
# Private AWS service endpoints
# -----------------------------------------------------------------------------

# WHY : Assumption: each of the eight services in the set has a named consumer
#       in this system, so the set is exact rather than a convenient round
#       number. ecr.api and ecr.dkr are the two halves of an image pull, and a
#       task that cannot reach both cannot start. logs carries container log
#       delivery. secretsmanager is read once per task at start-up for the
#       database credential. kms performs the envelope decryption behind that
#       read and behind Aurora and S3 access. sqs carries the authorization and
#       inquiry queues. states is called by reporting-service to start an
#       on-demand batch execution. ssm is read by the batch tasks for the
#       read-only flag that brackets the batch window.
#       Alternatives Considered: a per-endpoint boolean so a root could disable
#       one. Rejected because every one of the eight is on a start-up or
#       transaction path, so disabling any of them substitutes a public path for
#       a private one silently; variables.tf validates the set as exact instead.
#       Trade-offs: interface endpoints are charged per hour per endpoint per
#       availability zone plus per GB processed, so eight endpoints across three
#       zones fix the hourly term. The per-GB part is largely an offset rather
#       than an addition - this traffic stops traversing the NAT gateways, so it
#       no longer accrues NAT data-processing charges.
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

  # WHY : Assumption: private DNS is what makes the endpoint transparent. With
  #       it, an SDK's default service hostname resolves to the endpoint ENI, so
  #       no application code or configuration changes. Without it the endpoint
  #       exists and nothing uses it - the hostname resolves publicly and the
  #       call leaves through NAT, which fails silently as a different network
  #       path rather than as an error.
  private_dns_enabled = true

  # WHY : Assumption: the ENIs belong in the private-application tier because
  #       that is where the callers are. ENIs in the isolated tier would be
  #       unreachable by the tasks, and ENIs in the public tier would put the
  #       endpoint on the one tier with a route to the internet gateway, which
  #       is the path the endpoints exist to avoid.
  subnet_ids = [
    for zone in local.availability_zones :
    aws_subnet.private_app[zone].id
  ]
  security_group_ids = [aws_security_group.vpc_endpoints.id]

  tags = merge(var.tags, {
    Name = "${local.name_stem}-vpce-${replace(each.value, ".", "-")}"
  })
}

# WHY : Alternatives Considered: an interface endpoint for S3, matching the
#       eight above. Rejected on both mechanism and cost. Mechanically a gateway
#       endpoint is a route-table entry pointing at a managed service prefix
#       list, not an ENI with a security group - which is why it is associated
#       with route tables rather than subnets, and why it needs no rule on the
#       endpoint group. On cost, a gateway endpoint carries no hourly charge at
#       all, where an interface endpoint would add one per zone.
#       Trade-offs: a gateway endpoint is reachable only from inside the VPC and
#       cannot be called from on-premises over a private connection. Nothing in
#       this system needs that, and it is what the absent hourly charge buys.
resource "aws_vpc_endpoint" "s3" {
  vpc_id            = aws_vpc.this.id
  service_name      = "com.amazonaws.${data.aws_region.current.region}.s3"
  vpc_endpoint_type = "Gateway"

  tags = merge(var.tags, {
    Name = "${local.name_stem}-vpce-s3"
  })
}

# WHY : Alternatives Considered: the route_table_ids argument on the endpoint
#       itself, which expresses the same associations in one list. Rejected for
#       the reason the security-group rules above are separate resources - an
#       association declared as its own resource is individually addressable, so
#       adding or removing one tier's association is a single-resource plan diff
#       rather than an in-place rewrite of a list. The two forms also conflict if
#       both are ever present on one endpoint, so only this one is used.
#       Assumption: the private-application association is what lets the batch
#       and ETL tasks read and write the dataset generations that the sibling
#       s3-datasets module provisions.
resource "aws_vpc_endpoint_route_table_association" "private_app" {
  for_each = aws_route_table.private_app

  vpc_endpoint_id = aws_vpc_endpoint.s3.id
  route_table_id  = each.value.id
}

# WHY : Assumption: associating the isolated-data route tables does not weaken
#       the no-route invariant asserted above, and the two statements do not
#       contradict each other. The entry a gateway endpoint installs is a route
#       to a managed service prefix list, not a default route: it can carry
#       traffic to that one service and to nothing else, and it has neither a NAT
#       nor an internet-gateway next hop. The data tier therefore reaches object
#       storage - for a snapshot export, for instance - while still having no
#       path to the internet.
resource "aws_vpc_endpoint_route_table_association" "isolated_data" {
  for_each = aws_route_table.isolated_data

  vpc_endpoint_id = aws_vpc_endpoint.s3.id
  route_table_id  = each.value.id
}

# -----------------------------------------------------------------------------
# VPC flow logs
# -----------------------------------------------------------------------------

# WHY : Assumption: flow logging is something the target adds, not something it
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
  #       its own. Assumption: both environment roots pass the customer-managed
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
data "aws_iam_policy_document" "flow_logs_assume_role" {
  statement {
    sid     = "VPCFlowLogsAssumeRole"
    effect  = "Allow"
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["vpc-flow-logs.amazonaws.com"]
    }
  }
}

data "aws_iam_policy_document" "flow_logs" {
  # WHY : Assumption: the log group is created by this module and its ARN is
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

  # WHY : Assumption: DescribeLogGroups is in the delivery service's documented
  #       minimum permission set and does not support resource-level scoping in
  #       that call, so this one action carries a wildcard because the API
  #       requires it - not to widen what the role may write. It is read-only
  #       and enumerates nothing beyond log-group metadata, and every mutating
  #       action stays scoped to the single group above.
  #       Alternatives Considered: omitting the action to remove the wildcard
  #       entirely. Rejected because dropping a documented-required permission
  #       trades a reviewable, bounded wildcard for a delivery failure that
  #       would surface only after apply.
  statement {
    sid       = "DiscoverVPCFlowLogGroup"
    effect    = "Allow"
    actions   = ["logs:DescribeLogGroups"]
    resources = ["*"]
  }
}

resource "aws_iam_role" "flow_logs" {
  name               = "${local.name_stem}-vpc-flow-logs"
  assume_role_policy = data.aws_iam_policy_document.flow_logs_assume_role.json

  tags = merge(var.tags, {
    Name = "${local.name_stem}-vpc-flow-logs"
  })
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
