# =============================================================================
# infra/modules/network/outputs.tf
# -----------------------------------------------------------------------------
# Purpose:
#   Publishes the complete consumer contract of the CardDemo network module.
#   Environment roots use these values to place load balancers, VPC Links,
#   services, batch tasks and Aurora into the correct tier without looking up a
#   resource by name.
#
# Parameters:
#   None are declared here. Every value is derived from resources in main.tf or
#   from the two shared port inputs whose purpose is to keep network rules and
#   their listeners on one source of truth.
#
# Return values:
#   Eighteen outputs: VPC identity and address space; zone and subnet placement;
#   three published security groups; route tables and NAT gateways; private
#   endpoint identifiers; and the application and database ports.
#
# Exceptions or errors:
#   Terraform does not evaluate an output until the resource it references is
#   in the graph. A failed network resource therefore prevents the matching
#   output from being produced rather than publishing a partial value.
#
# WHY (non-obvious design decisions):
#   - Assumptions: subnet outputs are ordered by availability_zones rather than
#     by a resource-map iteration. Consumers receive stable positional lists,
#     while route-table and gateway outputs remain maps keyed by zone where the
#     association itself is the useful information.
#   - Refactoring Rationale: the two port inputs are intentionally returned.
#     The environment root passes these outputs to ecs-service and
#     aurora-postgresql, which prevents a listener from drifting from the
#     security-group rule this module owns.
#   - Trade-offs: the flow-log role and internet gateway are not published. No
#     sibling attaches to them, and exposing them would invite a second owner
#     for an internal boundary.
# =============================================================================

output "vpc_id" {
  description = "Identifier of the VPC that owns every subnet, route table, security group and endpoint this module creates; consumed by the ALB, API Gateway, Aurora and observability modules."
  value       = aws_vpc.this.id
}

output "vpc_cidr_block" {
  description = "IPv4 CIDR block of the VPC, published for consumers that must scope an AWS rule or validate that an address belongs to this network without repeating the input."
  value       = aws_vpc.this.cidr_block
}

output "availability_zones" {
  description = "Ordered availability-zone names used by all three subnet tiers. Every subnet-id list below follows this same order."
  value       = local.availability_zones
}

output "public_subnet_ids" {
  description = "Ordered identifiers of the public subnets, one per availability zone, for the internal ALB and the zone-local NAT gateways."
  value = [
    for zone in local.availability_zones :
    aws_subnet.public[zone].id
  ]
}

output "private_app_subnet_ids" {
  description = "Ordered identifiers of the private application subnets, one per availability zone, for ECS tasks, API Gateway VPC Link interfaces and interface endpoint ENIs."
  value = [
    for zone in local.availability_zones :
    aws_subnet.private_app[zone].id
  ]
}

output "isolated_data_subnet_ids" {
  description = "Ordered identifiers of the isolated data subnets, one per availability zone, whose route tables contain no default route and which form the Aurora DB subnet group."
  value = [
    for zone in local.availability_zones :
    aws_subnet.isolated_data[zone].id
  ]
}

output "alb_security_group_id" {
  description = "Identifier of the internal ALB's listener security group, attached by the alb module and opened on 443 by the api-gateway-http module from the VPC Link's own group; this module gives it only egress to the application group on app_container_port."
  value       = aws_security_group.alb.id
}

output "app_security_group_id" {
  description = "Identifier of the application-tier security group attached to ECS service and batch-task interfaces; it admits ALB traffic and permits only the declared dependency flows."
  value       = aws_security_group.app.id
}

output "data_security_group_id" {
  description = "Identifier of the isolated-data security group attached to Aurora, admitting PostgreSQL sessions from the application-tier group only."
  value       = aws_security_group.data.id
}

output "public_route_table_id" {
  description = "Identifier of the public route table whose default route targets the internet gateway and which is associated only with public subnets."
  value       = aws_route_table.public.id
}

output "private_app_route_table_ids" {
  description = "Map from availability-zone name to the private-application route-table identifier whose default route targets that zone's NAT gateway."
  value = {
    for zone in local.availability_zones :
    zone => aws_route_table.private_app[zone].id
  }
}

output "isolated_data_route_table_ids" {
  description = "Map from availability-zone name to the isolated-data route-table identifier. These tables have no default route; only the service-specific S3 gateway route is added."
  value = {
    for zone in local.availability_zones :
    zone => aws_route_table.isolated_data[zone].id
  }
}

output "nat_gateway_ids" {
  description = "Map from availability-zone name to the NAT gateway serving the private application subnet in that zone."
  value = {
    for zone in local.availability_zones :
    zone => aws_nat_gateway.this[zone].id
  }
}

output "nat_gateway_public_ips" {
  description = "Map from availability-zone name to the Elastic IP attached to that zone's NAT gateway, for allow-listing and operational inventory."
  value = {
    for zone in local.availability_zones :
    zone => aws_eip.nat[zone].public_ip
  }
}

output "interface_vpc_endpoint_ids" {
  description = "Map from short AWS service name to the interface VPC endpoint identifier that provides its private path."
  value = {
    for service, endpoint in aws_vpc_endpoint.interface :
    service => endpoint.id
  }
}

output "s3_gateway_endpoint_id" {
  description = "Identifier of the S3 gateway endpoint associated with the private-application and isolated-data route tables."
  value       = aws_vpc_endpoint.s3.id
}

output "app_container_port" {
  description = "Application listener port enforced by the ALB-to-application security-group rules; pass this output to every ecs-service container and target group."
  value       = var.app_container_port
}

output "database_port" {
  description = "Aurora PostgreSQL listener port enforced by the application-to-data security-group rules; pass this output to aurora-postgresql."
  value       = var.database_port
}

output "flow_log_group_name" {
  description = "Exact CloudWatch log-group name receiving VPC flow records. Pass this to observability.vpc_flow_log_group_name so its Logs Insights widget queries the group this module actually created."
  value       = aws_cloudwatch_log_group.flow_logs.name
}

output "flow_log_group_arn" {
  description = "CloudWatch log-group ARN without the trailing stream wildcard, for IAM policies that grant read access to VPC flow records without granting access to every log group."
  value       = trimsuffix(aws_cloudwatch_log_group.flow_logs.arn, ":*")
}

output "flow_log_id" {
  description = "Identifier of the VPC flow-log resource for inventory and diagnostics that need to distinguish the delivery configuration from its destination log group."
  value       = aws_flow_log.this.id
}
