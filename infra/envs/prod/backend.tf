# =============================================================================
# infra/envs/prod/backend.tf
# -----------------------------------------------------------------------------
# Purpose:
#   Declare the production root's partial S3 backend. Bucket, region and
#   DynamoDB lock-table values come from the separately applied bootstrap root
#   through `terraform init -backend-config=...`; backend blocks cannot consume
#   Terraform variables or module outputs.
#
# WHY : Trade-offs: production uses a distinct state key in the same bootstrap
#       backend so its state, lock and review history cannot collide with
#       development while operators retain one account-level backend.
# =============================================================================

terraform {
  backend "s3" {
    key     = "carddemo/prod/terraform.tfstate"
    encrypt = true
  }
}
