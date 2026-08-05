# =============================================================================
# infra/bootstrap/outputs.tf
# -----------------------------------------------------------------------------
# Purpose: publishes the state bucket name, lock-table name, and AWS region
#   required to configure the S3 backend of every environment root.
# Assumptions: backend configuration is evaluated before Terraform expressions,
#   so an operator or -backend-config must transcribe these literal values after
#   bootstrap is applied.
# Trade-offs: exactly three non-sensitive configuration identifiers are exposed.
#   Publishing ARNs or whole resource objects would add unused contract surface,
#   while marking names sensitive would prevent normal backend initialization
#   without protecting credential material.
# =============================================================================

# Assumptions: reading the resource reports the bucket that AWS created rather
#   than a composed local that may never have been provisioned.
# Trade-offs: `.bucket` is chosen over the equivalent `.id` because the published
#   contract is explicit without requiring provider-schema knowledge.
output "state_bucket_name" {
  description = "Name of the versioned, encrypted S3 bucket that holds Terraform state for every other root in this repository. Supply it as the `bucket` argument of each environment root's S3 backend block."
  value       = aws_s3_bucket.state.bucket
}

# Assumptions: the mandated DynamoDB lock table remains configurable through the
#   backend's `dynamodb_table` argument even when S3 lockfile support is also used.
output "state_lock_table_name" {
  description = "Name of the DynamoDB table Terraform uses to serialise concurrent writes to the state object. Supply it as the `dynamodb_table` argument of each environment root's S3 backend block. Its partition key is `LockID` of type String, which is the schema the S3 backend requires and writes the lock item under."
  value       = aws_dynamodb_table.state_lock.name
}

# Assumptions: the provider and bucket-name composition both consume
#   var.aws_region, so echoing that input keeps backend configuration aligned.
# Alternatives Considered: a region data source would duplicate the provider
#   input and could report ambient configuration rather than operator intent.
output "aws_region" {
  description = "AWS region containing both the state bucket and the lock table. Supply it as the `region` argument of each environment root's S3 backend block."
  value       = var.aws_region
}

output "state_audit_bucket_name" {
  description = "Name of the versioned S3 bucket receiving validated CloudTrail data-event logs for every Terraform state object read and write."
  value       = aws_s3_bucket.state_audit.bucket
}

output "state_object_access_trail_arn" {
  description = "ARN of the CloudTrail trail whose advanced selector records object-level access to the Terraform state bucket."
  value       = aws_cloudtrail.state_object_access.arn
}

output "state_kms_key_arn" {
  description = "ARN of the bootstrap-owned customer-managed KMS key encrypting Terraform state, the lock table and immutable access-audit logs."
  value       = aws_kms_key.state.arn
}
