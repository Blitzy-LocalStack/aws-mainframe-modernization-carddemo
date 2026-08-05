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
#   All eleven inputs are declared and validated in variables.tf. Identity,
#   address arithmetic and log retention are consumed directly here. The two
#   shared ports are consumed by security-group rules and republished by
#   outputs.tf for the service and database modules. The endpoint service set
#   is consumed by the interface-endpoint for_each.
#
# Return values:
#   None are declared in this file. outputs.tf publishes the VPC, subnet,
#   route-table, gateway, endpoint and security-group identifiers together with
#   the shared port contracts.
#
# Exceptions or errors:
#   Planning fails when the selected Region exposes fewer usable availability
#   zones than az_count. Applying can also fail when the caller-supplied KMS key
#   policy does not permit the regional CloudWatch Logs service to use the key;
#   the kms module owns that policy and this module deliberately accepts only
#   the key ARN.
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
#   - Assumptions: the ALB security group is also attached to the API Gateway VPC
#     Link and the managed interface endpoint ENIs. Its self-referenced 443 rule
#     is therefore the edge-to-listener flow, while application-to-group 443 is
#     the task-to-endpoint flow and the separate egress rule to the application
#     group is listener-to-container. Reusing one managed-boundary group keeps
#     the AAP's three-group topology without attaching the broader application
#     identity to the edge.
#   - Trade-offs: application tasks retain outbound TCP 443 through their
#     zone-local NAT gateway for managed endpoints that have no endpoint in the
#     mandated eight-service set, including identity-provider metadata. Routes
#     still prefer interface and S3 gateway endpoints for the services this
#     module provisions, so those flows do not traverse NAT.
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
  #       private-application N..2N-1 and isolated-data 2N..3N-1.
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
  #       names resolve to their public addresses.
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

# WHY : Assumptions: adopting the VPC's default group and declaring no rules
#       prevents an accidentally launched resource from inheriting AWS's
#       default same-group ingress and unrestricted egress. Every intended
#       resource attaches one of the explicit groups below instead.
resource "aws_default_security_group" "this" {
  vpc_id  = aws_vpc.this.id
  ingress = []
  egress  = []

  tags = merge(var.tags, {
    Name = "${local.name_stem}-default-deny"
  })
}

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

resource "aws_nat_gateway" "this" {
  for_each = aws_subnet.public

  allocation_id = aws_eip.nat[each.key].id
  subnet_id     = each.value.id

  tags = merge(var.tags, {
    Name = "${local.name_stem}-nat-${each.key}"
  })

  depends_on = [aws_internet_gateway.this]
}

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

resource "aws_route_table" "isolated_data" {
  for_each = local.isolated_data_subnet_cidrs

  vpc_id = aws_vpc.this.id

  # WHY : Alternatives Considered: adding a default route to the zone-local NAT
  #       gateway, matching the private-application tier. Rejected because the
  #       absence of that route is the data tier's strongest boundary: an
  #       egress attempt has no next hop regardless of credentials or a later
  #       security-group change.
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
  description            = "Managed edge and endpoint boundary forwarding approved traffic to CardDemo services"
  vpc_id                 = aws_vpc.this.id
  revoke_rules_on_delete = true

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

resource "aws_vpc_security_group_egress_rule" "app_to_endpoints" {
  security_group_id            = aws_security_group.app.id
  referenced_security_group_id = aws_security_group.alb.id
  description                  = "Allow CardDemo tasks to initiate TLS sessions to private AWS service endpoints"
  ip_protocol                  = "tcp"
  from_port                    = 443
  to_port                      = 443
}

resource "aws_vpc_security_group_ingress_rule" "app_to_endpoints" {
  security_group_id            = aws_security_group.alb.id
  referenced_security_group_id = aws_security_group.app.id
  description                  = "Allow private AWS service endpoints to receive TLS from CardDemo tasks only"
  ip_protocol                  = "tcp"
  from_port                    = 443
  to_port                      = 443
}

# WHY : Trade-offs: this outbound-only rule is broader than the endpoint rule
#       above because not every managed dependency has a PrivateLink endpoint
#       in the mandated set. Restricting the port to TLS and retaining private
#       routes for the eight endpoints bounds the exposure while keeping
#       identity metadata and other HTTPS-only managed APIs reachable through
#       the zone-local NAT gateway. There is no matching internet ingress rule.
resource "aws_vpc_security_group_egress_rule" "app_https" {
  security_group_id = aws_security_group.app.id
  cidr_ipv4         = "0.0.0.0/0"
  description       = "Allow outbound TLS to managed services without a VPC endpoint"
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
}

# -----------------------------------------------------------------------------
# Private AWS service endpoints
# -----------------------------------------------------------------------------

resource "aws_vpc_endpoint" "interface" {
  for_each = var.interface_endpoint_services

  vpc_id              = aws_vpc.this.id
  service_name        = "com.amazonaws.${data.aws_region.current.region}.${each.value}"
  vpc_endpoint_type   = "Interface"
  private_dns_enabled = true
  subnet_ids = [
    for zone in local.availability_zones :
    aws_subnet.private_app[zone].id
  ]
  # WHY : Alternatives Considered: attaching endpoint ENIs to the application
  #       group and using a self-referenced 443 rule. Rejected because that
  #       would also permit task-to-task TLS. The managed-boundary group is
  #       already shared by the VPC Link and ALB, so attaching endpoints there
  #       preserves three groups while keeping application ENIs on a distinct
  #       identity.
  security_group_ids = [aws_security_group.alb.id]

  tags = merge(var.tags, {
    Name = "${local.name_stem}-vpce-${replace(each.value, ".", "-")}"
  })
}

resource "aws_vpc_endpoint" "s3" {
  vpc_id            = aws_vpc.this.id
  service_name      = "com.amazonaws.${data.aws_region.current.region}.s3"
  vpc_endpoint_type = "Gateway"
  route_table_ids = concat(
    [for zone in local.availability_zones : aws_route_table.private_app[zone].id],
    [for zone in local.availability_zones : aws_route_table.isolated_data[zone].id],
  )

  # WHY : Assumptions: associating the isolated route tables does not create an
  #       internet path. A gateway endpoint installs a prefix-list route to S3
  #       only; it cannot carry arbitrary destinations and has no NAT or
  #       internet-gateway next hop.
  tags = merge(var.tags, {
    Name = "${local.name_stem}-vpce-s3"
  })
}

# -----------------------------------------------------------------------------
# VPC flow logs
# -----------------------------------------------------------------------------

resource "aws_cloudwatch_log_group" "flow_logs" {
  name              = "/aws/vpc/${local.name_stem}/flow-logs"
  retention_in_days = var.flow_log_retention_days
  kms_key_id        = var.flow_log_kms_key_arn

  tags = merge(var.tags, {
    Name = "${local.name_stem}-vpc-flow-logs"
  })
}

resource "aws_iam_role" "flow_logs" {
  name = "${local.name_stem}-vpc-flow-logs"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid    = "VPCFlowLogsAssumeRole"
      Effect = "Allow"
      Principal = {
        Service = "vpc-flow-logs.amazonaws.com"
      }
      Action = "sts:AssumeRole"
    }]
  })

  tags = merge(var.tags, {
    Name = "${local.name_stem}-vpc-flow-logs"
  })
}

resource "aws_iam_role_policy" "flow_logs" {
  name = "${local.name_stem}-vpc-flow-logs"
  role = aws_iam_role.flow_logs.id

  # WHY : Assumptions: the log group is created before delivery and therefore
  #       CreateLogGroup is deliberately absent. Scoping the remaining actions
  #       to this group's stream ARN prevents the role from writing to any
  #       application or audit log group in the account.
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "WriteVPCFlowLogStreams"
        Effect = "Allow"
        Action = [
          "logs:CreateLogStream",
          "logs:DescribeLogStreams",
          "logs:PutLogEvents",
        ]
        Resource = "${aws_cloudwatch_log_group.flow_logs.arn}:*"
      },
      {
        # WHY : Assumptions: DescribeLogGroups does not support resource-level
        #       permissions, so the wildcard is required by that API rather
        #       than a grant to write broadly. Every mutating action remains
        #       scoped to this module's one flow-log group above.
        Sid      = "DiscoverVPCFlowLogGroup"
        Effect   = "Allow"
        Action   = "logs:DescribeLogGroups"
        Resource = "*"
      },
    ]
  })
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
