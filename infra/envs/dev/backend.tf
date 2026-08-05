# =============================================================================
# infra/envs/dev/backend.tf
# -----------------------------------------------------------------------------
# Purpose:
#   Declare the development root's partial S3 backend. Bucket, region and
#   DynamoDB lock-table values come from the separately applied bootstrap root
#   through `terraform init -backend-config=...`; backend blocks cannot consume
#   Terraform variables or module outputs.
#
# WHY : Alternatives Considered: committing one account's bootstrap output
#       values here. Rejected because account/region identifiers are deployment
#       data and would make this root unusable anywhere else. Partial
#       configuration keeps the state key stable while leaving account-specific
#       values at initialization time.
# =============================================================================

terraform {
  backend "s3" {
    key     = "carddemo/dev/terraform.tfstate"
    encrypt = true
  }
}
