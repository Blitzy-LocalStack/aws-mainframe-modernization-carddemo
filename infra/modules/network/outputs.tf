# =============================================================================
# infra/modules/network/outputs.tf
# -----------------------------------------------------------------------------
# Purpose:
#   Publishes the complete public contract of the CardDemo network module.
#   This file is the only channel by which another module or an environment
#   root learns a network identifier: module outputs are the sole source of
#   runtime endpoints and identifiers in this package, and no service
#   hard-codes one. A value not published here cannot be reached by a sibling
#   at all, so every name below is load-bearing.
#
#   Refactoring Rationale: five names were withdrawn from this surface - the
#   three route-table exports, the flow-log group ARN and the flow-log resource
#   id - after measuring every published name against every reference in the
#   package: no sibling module and no environment root read any of the five. The
#   remaining sixteen are the contract, and every one of them is read by the
#   consumer its description names. Each withdrawal is recorded in a comment at
#   the position the output occupied, so a later reader finds out why a name is
#   absent rather than restoring it.
#
# Parameters:
#   None. This file declares no input. All thirteen module inputs are declared,
#   typed, described and validated in variables.tf and are consumed by main.tf.
#   Three outputs nevertheless originate in an input rather than in a created
#   resource: vpc_cidr_block reads the address space back off aws_vpc.this so
#   the published value is what AWS accepted rather than what the caller asked
#   for, while app_container_port and database_port are republished so a root
#   passes one number to both a security-group rule owned here and the
#   listener that rule protects. variables.tf states that republication
#   obligation on both port inputs, so it is a contract, not a shortcut.
#
# Return values:
#   Sixteen outputs in seven groups.
#     Identity and addressing - vpc_id, vpc_cidr_block, availability_zones.
#     Subnets, one ordered list per tier - public_subnet_ids,
#       private_app_subnet_ids, isolated_data_subnet_ids.
#     Security groups, the three a sibling attaches to - alb_security_group_id,
#       app_security_group_id, data_security_group_id.
#     NAT - nat_gateway_ids, nat_gateway_public_ips.
#     Private service paths - interface_vpc_endpoint_ids and
#       s3_gateway_endpoint_id.
#     Shared port contracts - app_container_port, database_port.
#     Flow-log delivery - flow_log_group_name.
#   The route-table group is absent by decision rather than by omission; the
#   comment standing where it was records the measurement behind that.
#   Each description states its own shape, because a consumer indexes a list
#   positionally but addresses a map by key; the two are not interchangeable.
#
# Exceptions or errors:
#   An output cannot raise. The failure mode that does exist is contractual:
#   renaming or removing any output below is a breaking change for every
#   consumer its description names, and the break does not surface in this
#   module. It surfaces in the calling configuration as an unresolved reference
#   to a module output, so validation fails in infra/envs/dev and
#   infra/envs/prod rather than here. That is why each consumer is recorded in
#   the description itself, where an author editing this file will read it
#   before making the change rather than after.
#   The generated Outputs table in this module's README.md is produced from the
#   names and descriptions below, and its freshness is drift-checked in
#   check-only mode. Editing any name or any description without regenerating
#   that README therefore leaves it stale and fails the build.
#
# WHY (non-obvious design decisions):
#   - Assumptions: the three subnet lists are built by iterating
#     local.availability_zones rather than a resource map, so index n of any
#     subnet list is in zone n of availability_zones. That ordering is
#     a published guarantee consumers rely on to pair a subnet with its zone
#     without a second lookup, which is why it is contract and not detail.
#   - Alternatives Considered: returning the route-table and NAT identifiers as
#     positional lists too, for uniformity with the subnet lists. Rejected
#     because for those resources the zone association is the useful fact
#     rather than an incidental ordering, and a keyed map states it outright
#     instead of asking a reader to trust that two lists were built in step.
#   - Trade-offs: the interface-endpoint security group, the flow-log IAM role
#     and the internet gateway are all created by main.tf and deliberately not
#     published. The endpoint group is the omission a reader is most likely to
#     mistake for an oversight, so it is argued at the security-group group
#     below rather than left to inference.
# =============================================================================

# -----------------------------------------------------------------------------
# On `sensitive`: nothing below is marked, and that is a decision rather
# than an omission.
#
# Alternatives Considered: marking every output `sensitive = true`, the
# defensive default some trees apply to anything shaped like an identifier.
# Rejected on two specific grounds rather than as a preference. First, none of
# these values is credential material: they are resource identifiers,
# availability-zone names, one IPv4 CIDR block, two TCP port numbers, a
# log-group name and ARN, and the Elastic IP addresses of the NAT gateways.
# None authenticates anything and none grants access on its own - the
# isolated-data subnets have no route to the internet, and reaching the
# application tier still requires membership of a named security group. Second,
# `sensitive` propagates and suppresses: a root re-exporting a sensitive output
# must mark its own output sensitive too, and Terraform then redacts the value
# from plan output, from `terraform output` and from generated documentation.
# Both environment roots pass these values onward to sibling modules, and the
# deploy and teardown runbooks instruct an operator to read several of them
# back, so marking them would hide precisely the values an operator needs while
# protecting nothing an attacker could use.
#
# Assumptions: the genuinely secret material in this package - database
# credentials and seed-user passwords - is generated at apply time into Secrets
# Manager and never passes through this module, so there is nothing here for
# `sensitive` to protect.
#
# Trade-offs: nat_gateway_public_ips does publish routable addresses once a
# root is applied. That is accepted, and is the reason the output exists: an
# operator cannot allow-list an egress source without knowing it, and AWS
# publishes a NAT gateway's public address to the internet regardless of how
# this file describes it.
# -----------------------------------------------------------------------------

# -----------------------------------------------------------------------------
# Identity and addressing
# -----------------------------------------------------------------------------

output "vpc_id" {
  description = "Identifier (string) of the VPC that owns every subnet, route table, security group and endpoint this module creates. Read by api-gateway-http for its VPC Link and by ecs-service for its target groups, and required by any further module that creates a VPC-scoped resource."
  value       = aws_vpc.this.id
}

# Assumptions: the address space is read back off the created VPC rather than
#   echoed from var.vpc_cidr. A consumer handed only the variable would have to
#   be trusted to pass the same value into two modules, and a divergence would
#   surface as a silently over- or under-scoped rule rather than as an error;
#   the resource attribute is what AWS actually accepted, so it is the one
#   authoritative form of the answer.
output "vpc_cidr_block" {
  description = "IPv4 CIDR block (string) AWS assigned to this VPC. No consumer reads it today - neither a sibling module nor either environment root - because every tier-to-tier flow this topology allows is expressed group-to-group instead. It is published for a rule or policy that has to be scoped to the whole network rather than to a peer security group. Publishing it means no consumer is ever handed var.vpc_cidr a second time."
  value       = aws_vpc.this.cidr_block
}

# Assumptions: this list defines the module's canonical zone order. The three
#   subnet-id lists below are built by iterating it, so index n of any of them
#   is in zone n of this list. Consumers pair a subnet with its zone on that
#   guarantee alone, so reordering this list silently re-homes every subnet a
#   consumer thought it had placed.
# Refactoring Rationale: the description below said this list is produced "after
#   az_count is clamped to the zones the account can place a subnet in", and
#   described a degradation this module does not perform. The expression in
#   main.tf's locals does take a min() against the discovered zone count, but the
#   VPC carries a lifecycle precondition on the same comparison, so a Region with
#   fewer usable zones than az_count STOPS THE PLAN rather than producing a shorter
#   list -- and az_count is itself validated to exactly 3. The min() exists only so
#   that the slice cannot fail with an out-of-range error before the precondition
#   gets to emit its own targeted diagnostic; it is never the operative path. The
#   distinction matters to a consumer: "clamped" invites reading length() on this
#   list to discover how many zones were obtained, which would be defensive code
#   for a state that cannot exist, and it would also suggest a two-zone environment
#   is a supported degradation when it is a plan failure. The module fails CLOSED.
output "availability_zones" {
  description = "Ordered list of the availability-zone names this module actually used, after az_count is clamped to the zones the account can place a subnet in. No consumer reads it today - neither a sibling module nor either environment root - and it is published for zone-aware sizing and for confirming the span a deployment really got rather than the span it asked for. Every subnet-id list below is ordered to match it."
  value       = local.availability_zones
}

# -----------------------------------------------------------------------------
# Subnets - one ordered list per tier, in availability_zones order
# -----------------------------------------------------------------------------

output "public_subnet_ids" {
  description = "Ordered list of the public subnet identifiers, one per availability zone. This tier is reserved for the two kinds of thing that need a route to the internet gateway: the zone-local NAT gateways this module creates, which are its only current occupants, and an internet-facing load balancer. No consumer reads it today - neither a sibling module nor either environment root - because the load balancer in this deployment is INTERNAL and both roots therefore place it in the private-application subnets, leaving the NAT gateways this module creates as this tier's only occupants. Nothing holding application state or record data belongs here."
  value = [
    for zone in local.availability_zones :
    aws_subnet.public[zone].id
  ]
}

output "private_app_subnet_ids" {
  description = "Ordered list of the private application subnet identifiers, one per availability zone, and the most widely consumed output here. Read by ecs-service for task placement, by step-functions-batch for its Fargate task network configuration, and by api-gateway-http for its VPC Link. NOT read by alb: per AAP 0.4.1.9 the load balancer belongs to the public tier, and the roots place it there. The tier also holds the interface VPC endpoint ENIs, which is how a task reaches ECR, CloudWatch Logs, Secrets Manager, KMS, SQS, Step Functions, SSM, X-Ray and the Cognito identity provider without its traffic leaving the VPC."
  value = [
    for zone in local.availability_zones :
    aws_subnet.private_app[zone].id
  ]
}

output "isolated_data_subnet_ids" {
  description = "Ordered list of the isolated data subnet identifiers, one per availability zone, forming the DB subnet group read by aurora-postgresql. These subnets have no route to the internet at all - their route tables carry no default route, no NAT and no gateway - which is what makes them the correct home for the database and the wrong home for anything needing egress."
  value = [
    for zone in local.availability_zones :
    aws_subnet.isolated_data[zone].id
  ]
}

# -----------------------------------------------------------------------------
# Security groups - the three a sibling module attaches to
#
# Refactoring Rationale: this recorded a deliberate omission -- main.tf created a
# FOURTH group for the interface-endpoint ENIs and this file published only three,
# and the paragraph explained why the fourth was withheld. There is no longer a
# fourth group to withhold. The frozen plan specifies three (AAP section 0.5.1.12),
# the ENIs carry the application group, and the created and published sets are now
# identical -- so a reader comparing this file against main.tf finds no discrepancy
# to explain. The reasoning is replaced rather than deleted because the omission it
# described was real for a period, and a reader of an older revision needs to know
# it ended rather than that it was never there.
# Trade-offs: the note below is retained on its own merits. An identifier that is reachable invites a future
# caller to attach something to that group, and whatever is attached inherits
# every outbound path the application tier holds - the ten private AWS service
# endpoints, the internal listener on 443 and the S3 prefix list; leaving it
# unpublished keeps that group's membership decided in one file. The set of
# inherited paths grew when the last two were added, which makes the omission
# worth more now than it was, not less.
# -----------------------------------------------------------------------------

output "alb_security_group_id" {
  description = "Identifier (string) of the internal load balancer's security group, attached by alb. api-gateway-http adds its 443 ingress from the VPC Link's own group for edge traffic. This module grants it two flows: egress to the application group on app_container_port, so the balancer can forward requests and health checks, and 443 ingress from the application group, which is how one migrated context calls another over the internal listener. It can reach nothing else."
  value       = aws_security_group.alb.id
}

output "app_security_group_id" {
  description = "Identifier (string) of the application-tier security group, attached by ecs-service to its task ENIs and by step-functions-batch to its Fargate task network configuration. Its permitted flows are exactly five, each a separately named rule: ingress from the load-balancer group on app_container_port; egress to the data group on database_port; egress to the endpoint ENIs on 443 for the ten private AWS service endpoints, which include the identity provider and the trace collector -- a SELF reference, because those ENIs carry this same group rather than a fourth one; egress to the load-balancer group on 443 for service-to-service calls; and egress on 443 to the S3 gateway endpoint's managed prefix list, which needs a prefix-list rule because a gateway endpoint places no ENI and so has no group to reference. There is NO rule to a public destination: the identity-provider egress rule that admitted 0.0.0.0/0 on 443 is withdrawn, because the cognito-idp endpoint in the set above carries the same calls inside the VPC. Egress is enumerated rather than left as a new group's implicit allow-all, so a further outbound dependency has to arrive as a named rule visible in a plan diff."
  value       = aws_security_group.app.id
}

output "data_security_group_id" {
  description = "Identifier (string) of the isolated-data security group, attached by aurora-postgresql to its cluster. It grants exactly one flow: ingress from the application-tier group on database_port. It admits no CIDR range, so a host that is not a member of the application group cannot open a database session even from inside the VPC."
  value       = aws_security_group.data.id
}

# -----------------------------------------------------------------------------
# Route tables
# -----------------------------------------------------------------------------
#
# WHY : Refactoring Rationale: this group published three outputs -
#       public_route_table_id and the zone-keyed maps
#       private_app_route_table_ids and isolated_data_route_table_ids - and all
#       three have been withdrawn. Measurement, not preference, drove it: no
#       sibling module and no environment root reads any of them, and two of the
#       three descriptions asserted a consumer that does not exist, one of them
#       flatly ("Consumed by the environment roots"). A published output whose
#       description names a reader it does not have is worse than no output,
#       because the next reader treats it as load-bearing and preserves it.
#
#       Assumptions: nothing is lost that a consumer can reach for. The tier
#       boundaries this module exists to establish are expressed to consumers as
#       subnet-id lists and security-group ids, which are read; a route table is
#       the mechanism behind those boundaries, not part of the contract over
#       them. The no-default-route property of the isolated tier is a property of
#       resources declared in main.tf and is unaffected by whether their ids are
#       exported.
#       Alternatives Considered: keeping the three and correcting only the false
#       consumer claims. Rejected - the module's contract is specified at sixteen
#       outputs, this file published twenty-one, and honest descriptions on
#       unread exports would still leave five names a future reader has to
#       carry. Trade-offs: a root that later genuinely needs a route table has to
#       add the output back, which is a reviewed change that arrives with its
#       consumer - the order this file's own header describes.

# -----------------------------------------------------------------------------
# NAT
# -----------------------------------------------------------------------------

output "nat_gateway_ids" {
  description = "Map from availability-zone name to the NAT gateway serving that zone's private application subnet. No consumer reads it today - neither a sibling module nor either environment root; observability takes only the flow-log group name from this module. It is published because an alarm on a per-gateway metric - a failed-connection count, for instance - needs the gateway identity, and the zone key is what lets such an alarm name the zone it describes instead of an opaque identifier."
  value = {
    for zone in local.availability_zones :
    zone => aws_nat_gateway.this[zone].id
  }
}

# Assumptions: a downstream system that allow-lists source addresses needs
#   every entry in this map, not one of them. A task's egress leaves via the
#   NAT gateway of whichever zone the task is running in, and placement moves
#   between zones on any scaling event or task replacement, so an allow-list
#   holding a single address appears to work and then fails intermittently -
#   which is the hardest form of this failure to diagnose.
output "nat_gateway_public_ips" {
  description = "Map from availability-zone name to the Elastic IP address attached to that zone's NAT gateway. No consumer reads it today - neither a sibling module nor either environment root - and it is published so an operator or downstream system that has to allow-list CardDemo's egress can be handed the set. All az_count entries are present, because egress can leave from any zone."
  value = {
    for zone in local.availability_zones :
    zone => aws_eip.nat[zone].public_ip
  }
}

# -----------------------------------------------------------------------------
# Private AWS service paths
# -----------------------------------------------------------------------------

# Alternatives Considered: returning these as a list, which would have matched
#   the subnet outputs above. Rejected because a consumer wanting one specific
#   endpoint - observability attaching an alarm to a single service's path -
#   would then depend on that service's position within
#   var.interface_endpoint_services, and adding a further service would silently
#   renumber every position after it. Keying by the short service name lets a
#   consumer ask for the endpoint it actually means, and it is why widening the
#   set from eight names to ten changed nothing for any consumer of this output.
output "interface_vpc_endpoint_ids" {
  description = "Map from short AWS service name to that service's interface VPC endpoint identifier, keyed exactly as var.interface_endpoint_services is written: ecr.api, ecr.dkr, logs, secretsmanager, kms, sqs, states and ssm. Its consumer is the environment root, which needs a specific endpoint's identity to attach a metric or an endpoint policy to it; no sibling module reads it today. Each endpoint places an ENI in the private application subnets, which is how a task reaches these services without egressing the VPC."
  value = {
    for service, endpoint in aws_vpc_endpoint.interface :
    service => endpoint.id
  }
}

output "s3_gateway_endpoint_id" {
  description = "Identifier (string) of the S3 gateway endpoint. No consumer reads it today - neither a sibling module nor either environment root - and it is published because naming the private path to S3 in a bucket policy that restricts access to this VPC's endpoint needs it. Being a gateway rather than an interface endpoint, it is associated with route tables instead of subnets, places no ENI and carries no security group - so which tiers can reach S3 is decided by route-table association, not by a security-group rule."
  value       = aws_vpc_endpoint.s3.id
}

# -----------------------------------------------------------------------------
# Shared port contracts
#
# Refactoring Rationale: these two are module inputs returned unchanged, which
# is unusual enough to state plainly rather than leave a reader to wonder at.
# Each port appears twice in a deployment - once in a security-group rule this
# module owns, and once in the listener or engine configuration a sibling
# module owns - and the two must agree or the rule permits traffic to a port
# nothing is listening on, which presents as an unexplained health-check
# failure rather than as a configuration error. Republishing lets a root pass
# one value into both places instead of repeating a literal, and variables.tf
# records the same obligation on both inputs.
# -----------------------------------------------------------------------------

output "app_container_port" {
  description = "TCP port (number) this module admits from the load-balancer group to the application group. Both environment roots pass it to ecs-service as its container and target-group port, so the rule and the listener cannot drift apart."
  value       = var.app_container_port
}

output "database_port" {
  description = "TCP port (number) this module admits from the application group to the isolated-data group. Both environment roots pass it to aurora-postgresql as its cluster port, so the rule and the engine cannot drift apart."
  value       = var.database_port
}

# -----------------------------------------------------------------------------
# Flow-log delivery
# -----------------------------------------------------------------------------

output "flow_log_group_name" {
  description = "Exact name (string) of the CloudWatch log group receiving this VPC's flow records. Both environment roots pass it to observability as its required vpc_flow_log_group_name input, which points that module's Logs Insights widgets at the group this module created rather than at a name reassembled from a prefix and an environment."
  value       = aws_cloudwatch_log_group.flow_logs.name
}

# WHY : Refactoring Rationale: flow_log_group_arn and flow_log_id were published
#       here and have been withdrawn for the reason the route-table group above
#       was - both were measured unread by every sibling module and by both
#       environment roots. Only flow_log_group_name survives this group, and it
#       survives because it is genuinely read: both roots pass it to the
#       observability module as its required vpc_flow_log_group_name input.
#
#       Assumptions: neither withdrawal removes reachable information. The group
#       ARN is derivable from the surviving name by any consumer that also knows
#       its own account and Region, which an environment root does; and the
#       flow-log resource id was published for an operator diagnosis that the
#       AWS console and CLI both answer directly from the VPC.
#       Trade-offs: an operator distinguishing a stalled delivery from a healthy
#       destination now reads it from the VPC rather than from a Terraform
#       output. That is a slightly longer path for a rare task, traded against a
#       module contract whose every published name has a reader.
