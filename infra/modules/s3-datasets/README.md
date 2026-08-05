# S3 datasets module

This module creates the versioned CMK-encrypted dataset bucket, ten generation
family prefixes, non-generation staging prefixes and a separate versioned
CloudTrail audit bucket for object-level access events.

## Design decisions

**Alternatives Considered:** eleven generation families were rejected because
the extra source grep hit redefines `TRANREPT`; it is not a distinct base. The
module retains the ten distinct families and five noncurrent versions.

**Trade-offs:** object-level CloudTrail data events add storage and event costs,
accepted because state and dataset reads/writes otherwise leave no durable
object-access audit.

## Validation

```bash
terraform -chdir=infra/modules/s3-datasets init -backend=false
terraform -chdir=infra/modules/s3-datasets validate
tflint --chdir=infra/modules/s3-datasets --config="$(pwd)/infra/.tflint.hcl"
```

<!-- BEGIN_TF_DOCS -->
### Requirements

| Name | Version |
|------|---------|
| <a name="requirement_terraform"></a> [terraform](#requirement\_terraform) | >= 1.15.0 |
| <a name="requirement_aws"></a> [aws](#requirement\_aws) | ~> 6.56 |

### Providers

| Name | Version |
|------|---------|
| <a name="provider_aws"></a> [aws](#provider\_aws) | 6.57.1 |

### Resources

| Name | Type |
|------|------|
| [aws_cloudtrail.dataset_object_access](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/cloudtrail) | resource |
| [aws_lambda_permission.dataset_generation_retention](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/lambda_permission) | resource |
| [aws_s3_bucket.audit](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/s3_bucket) | resource |
| [aws_s3_bucket.datasets](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/s3_bucket) | resource |
| [aws_s3_bucket_lifecycle_configuration.audit](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/s3_bucket_lifecycle_configuration) | resource |
| [aws_s3_bucket_lifecycle_configuration.datasets](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/s3_bucket_lifecycle_configuration) | resource |
| [aws_s3_bucket_logging.datasets](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/s3_bucket_logging) | resource |
| [aws_s3_bucket_notification.datasets](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/s3_bucket_notification) | resource |
| [aws_s3_bucket_ownership_controls.audit](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/s3_bucket_ownership_controls) | resource |
| [aws_s3_bucket_ownership_controls.datasets](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/s3_bucket_ownership_controls) | resource |
| [aws_s3_bucket_policy.audit](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/s3_bucket_policy) | resource |
| [aws_s3_bucket_policy.datasets](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/s3_bucket_policy) | resource |
| [aws_s3_bucket_public_access_block.audit](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/s3_bucket_public_access_block) | resource |
| [aws_s3_bucket_public_access_block.datasets](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/s3_bucket_public_access_block) | resource |
| [aws_s3_bucket_server_side_encryption_configuration.audit](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/s3_bucket_server_side_encryption_configuration) | resource |
| [aws_s3_bucket_server_side_encryption_configuration.datasets](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/s3_bucket_server_side_encryption_configuration) | resource |
| [aws_s3_bucket_versioning.audit](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/s3_bucket_versioning) | resource |
| [aws_s3_bucket_versioning.datasets](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/s3_bucket_versioning) | resource |
| [aws_caller_identity.current](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/caller_identity) | data source |
| [aws_iam_policy_document.audit_bucket](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_iam_policy_document.tls_only](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_partition.current](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/partition) | data source |
| [aws_region.current](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/region) | data source |

### Inputs

| Name | Description | Type | Default | Required |
|------|-------------|------|---------|:--------:|
| <a name="input_environment"></a> [environment](#input\_environment) | Deployment environment that owns this bucket, supplied by the calling root: infra/envs/dev passes dev and infra/envs/prod passes prod. It appears verbatim in the composed bucket name, which is what stops two environments in one account resolving to the same bucket, and it is the only axis along which this module's inputs are expected to differ. | `string` | n/a | yes |
| <a name="input_kms_key_arn"></a> [kms\_key\_arn](#input\_kms\_key\_arn) | ARN of the S3 customer-managed KMS key produced by infra/modules/kms, used as the SSE-KMS key for every object written to this bucket. Required, because the module offers no unencrypted mode. | `string` | n/a | yes |
| <a name="input_object_created_lambda_arn"></a> [object\_created\_lambda\_arn](#input\_object\_created\_lambda\_arn) | ARN of the Lambda function invoked for S3 ObjectCreated events to enforce five-generation retention across distinct dt=/gen= keys. Required because lifecycle version retention cannot enforce a count across different object keys. | `string` | n/a | yes |
| <a name="input_abort_incomplete_multipart_upload_days"></a> [abort\_incomplete\_multipart\_upload\_days](#input\_abort\_incomplete\_multipart\_upload\_days) | Age in days after which an incomplete multipart upload is aborted and its already-uploaded parts deleted. Applies to the whole bucket rather than to one prefix. | `number` | `7` | no |
| <a name="input_access_log_bucket_name"></a> [access\_log\_bucket\_name](#input\_access\_log\_bucket\_name) | Name of an existing bucket that receives S3 server access logs for this bucket. Null disables access logging, which is the module default so that the module can be instantiated without a logging bucket already in place. | `string` | `null` | no |
| <a name="input_audit_log_retention_days"></a> [audit\_log\_retention\_days](#input\_audit\_log\_retention\_days) | Finite lifecycle horizon for validated CloudTrail dataset object-access logs. | `number` | `2557` | no |
| <a name="input_dataset_families"></a> [dataset\_families](#input\_dataset\_families) | Generation-dataset families to provision a prefix and a noncurrent-version lifecycle rule for, keyed by the S3-safe family name main.tf uses as the dataset path segment. Each value carries: domain, the bounded context owning the data, which becomes the leading path segment; description, recording the baseline generation-data-group base the family replaces and the JCL line defining it; and noncurrent\_versions, an optional per-family override of noncurrent\_version\_retention that is left unset on every entry in the default. | <pre>map(object({<br/>    domain              = string<br/>    description         = string<br/>    noncurrent_versions = optional(number)<br/>  }))</pre> | <pre>{<br/>  "dalyrejs": {<br/>    "description": "Daily transaction reject-stream generations, carrying the reject record the posting run writes for each of the four documented reject reasons. Replaces GDG base AWS.M2.CARDDEMO.DALYREJS named at app/jcl/DALYREJS.jcl:L25 inside the DEFINE opened at L24, with LIMIT(5) at L26 and SCRATCH at L27.",<br/>    "domain": "ledger"<br/>  },<br/>  "discgrp-bkup": {<br/>    "description": "Disclosure-group reference backup generations, the interest-rate table the interest run reads. Replaces GDG base AWS.M2.CARDDEMO.DISCGRP.BKUP defined at app/jcl/DEFGDGD.jcl:L74 with LIMIT(5) at L75 and SCRATCH at L76; first generation loaded as (+1) at app/jcl/DEFGDGD.jcl:L86 at LRECL=50.",<br/>    "domain": "reference"<br/>  },<br/>  "systran": {<br/>    "description": "System-generated transaction generations, the interest and fee transactions the interest run emits. Replaces GDG base AWS.M2.CARDDEMO.SYSTRAN defined at app/jcl/DEFGDGB.jcl:L49 with LIMIT(5) at L50 and SCRATCH at L51; read back as (0) by app/jcl/COMBTRAN.jcl:L26.",<br/>    "domain": "ledger"<br/>  },<br/>  "tcatbalf-bkup": {<br/>    "description": "Transaction-category-balance backup generations. Replaces GDG base AWS.M2.CARDDEMO.TCATBALF.BKUP defined at app/jcl/DEFGDGB.jcl:L43 with LIMIT(5) at L44 and SCRATCH at L45.",<br/>    "domain": "ledger"<br/>  },<br/>  "trancatg-bkup": {<br/>    "description": "Transaction-category reference backup generations. Replaces GDG base AWS.M2.CARDDEMO.TRANCATG.PS.BKUP defined at app/jcl/DEFGDGD.jcl:L51 with LIMIT(5) at L52 and SCRATCH at L53; first generation loaded as (+1) at app/jcl/DEFGDGD.jcl:L63 at LRECL=60.",<br/>    "domain": "reference"<br/>  },<br/>  "tranrept": {<br/>    "description": "Transaction report generations, the 133-column fixed-width output. Replaces GDG base AWS.M2.CARDDEMO.TRANREPT defined at app/jcl/DEFGDGB.jcl:L37 with LIMIT(5) at L38 and SCRATCH at L39; written as (+1) by app/jcl/TRANREPT.jcl:L80 at LRECL=133. A second, conflicting definition of the same base exists at app/jcl/REPTFILE.jcl:L25-L28 with LIMIT(10) and no SCRATCH; the LIMIT(5) definition is the one applied.",<br/>    "domain": "reporting"<br/>  },<br/>  "transact-bkup": {<br/>    "description": "Transaction master backup generations. Replaces GDG base AWS.M2.CARDDEMO.TRANSACT.BKUP defined at app/jcl/DEFGDGB.jcl:L25 with LIMIT(5) at L26 and SCRATCH at L27; written as (+1) by app/jcl/TRANBKP.jcl:L33 at LRECL=350 and read back as (0) by app/jcl/COMBTRAN.jcl:L24.",<br/>    "domain": "ledger"<br/>  },<br/>  "transact-combined": {<br/>    "description": "Combined transaction generations, the merge of the transaction backup and the system transactions. Replaces GDG base AWS.M2.CARDDEMO.TRANSACT.COMBINED defined at app/jcl/DEFGDGB.jcl:L55 with LIMIT(5) at L56 and SCRATCH at L57; written as (+1) by app/jcl/COMBTRAN.jcl:L37.",<br/>    "domain": "ledger"<br/>  },<br/>  "transact-daly": {<br/>    "description": "Daily transaction generations staged for posting. Replaces GDG base AWS.M2.CARDDEMO.TRANSACT.DALY defined at app/jcl/DEFGDGB.jcl:L31 with LIMIT(5) at L32 and SCRATCH at L33; written as (+1) by app/jcl/TRANREPT.jcl:L55.",<br/>    "domain": "ledger"<br/>  },<br/>  "trantype-bkup": {<br/>    "description": "Transaction-type reference backup generations. Replaces GDG base AWS.M2.CARDDEMO.TRANTYPE.BKUP defined at app/jcl/DEFGDGD.jcl:L28 with LIMIT(5) at L29 and SCRATCH at L30; first generation loaded as (+1) at app/jcl/DEFGDGD.jcl:L40 at LRECL=60.",<br/>    "domain": "reference"<br/>  }<br/>}</pre> | no |
| <a name="input_force_destroy"></a> [force\_destroy](#input\_force\_destroy) | Whether Terraform may delete this bucket while it still holds objects, including noncurrent versions. False makes a destroy of a non-empty bucket fail rather than discard its contents. | `bool` | `false` | no |
| <a name="input_name_prefix"></a> [name\_prefix](#input\_name\_prefix) | Leading token of the bucket name, which main.tf composes as <name\_prefix>-datasets-<environment>-<account-id>-<region>. This is what distinguishes the CardDemo dataset bucket from every other bucket in the account, and it is also the stem the module derives its resource names and tags from. | `string` | `"carddemo"` | no |
| <a name="input_non_generation_prefixes"></a> [non\_generation\_prefixes](#input\_non\_generation\_prefixes) | Prefixes for baseline datasets that are NOT generation data groups, keyed by the S3-safe name main.tf uses as the dataset path segment. Each value carries a domain, the owning bounded context, and a description recording the baseline dataset and the JCL line that writes it. Held separately from dataset\_families so these can never be counted as additional generation families. | <pre>map(object({<br/>    domain      = string<br/>    description = string<br/>  }))</pre> | <pre>{<br/>  "statement-html": {<br/>    "description": "HTML customer statements. Replaces sequential dataset AWS.M2.CARDDEMO.STATEMNT.HTML, deleted by the IEFBR14 step at app/jcl/CREASTMT.JCL:L71 and rewritten by CBSTM03A at L96 with DCB=(LRECL=100,BLKSIZE=800,RECFM=FB) declared at L94. Not a generation data group: no GENERATIONDATAGROUP base for it exists in the baseline.",<br/>    "domain": "reporting"<br/>  },<br/>  "statement-text": {<br/>    "description": "Plain-text customer statements. Replaces sequential dataset AWS.M2.CARDDEMO.STATEMNT.PS, deleted by the IEFBR14 step at app/jcl/CREASTMT.JCL:L75 and rewritten by CBSTM03A at L91 with DCB=(LRECL=80,BLKSIZE=8000,RECFM=FB) declared at L89. Not a generation data group: no GENERATIONDATAGROUP base for it exists in the baseline.",<br/>    "domain": "reporting"<br/>  }<br/>}</pre> | no |
| <a name="input_noncurrent_version_retention"></a> [noncurrent\_version\_retention](#input\_noncurrent\_version\_retention) | Default retention count shared by two mechanisms: data-migration's staging writer keeps this many logical dt=/gen= generation prefixes per family, reproducing LIMIT(5) SCRATCH; S3 lifecycle also keeps this many newer noncurrent versions of any one object key as repeat-write recovery. Distinct gen= prefixes are not noncurrent versions of each other. | `number` | `5` | no |
| <a name="input_noncurrent_version_transition_days"></a> [noncurrent\_version\_transition\_days](#input\_noncurrent\_version\_transition\_days) | Age in days at which a noncurrent version moves to the storage class named by noncurrent\_version\_transition\_storage\_class. Null disables the transition entirely, leaving noncurrent versions in the class they were written to until they expire. | `number` | `null` | no |
| <a name="input_noncurrent_version_transition_storage_class"></a> [noncurrent\_version\_transition\_storage\_class](#input\_noncurrent\_version\_transition\_storage\_class) | Storage class a noncurrent version transitions into, read only when noncurrent\_version\_transition\_days is non-null. Ignored entirely while that value is null. | `string` | `"STANDARD_IA"` | no |
| <a name="input_tags"></a> [tags](#input\_tags) | Additional tags merged onto the resources this module creates, over and above whatever the calling root's provider-level default\_tags already applies. Empty by default, so the module contributes no tags of its own unless a caller asks for them. | `map(string)` | `{}` | no |

### Outputs

| Name | Description |
|------|-------------|
| <a name="output_audit_bucket_arn"></a> [audit\_bucket\_arn](#output\_audit\_bucket\_arn) | ARN of the versioned dataset object-access audit bucket, consumed by exact KMS encryption-context policy wiring in the environment root. |
| <a name="output_audit_bucket_name"></a> [audit\_bucket\_name](#output\_audit\_bucket\_name) | Name of the versioned bucket receiving validated CloudTrail data-event logs for dataset object reads and writes. |
| <a name="output_bucket_arn"></a> [bucket\_arn](#output\_bucket\_arn) | ARN of the dataset bucket in its bucket-level form, for the IAM task-role policies that authorise access to it: the batch-service and data-migration ETL roles scope object permissions to this value with /* appended, and scope bucket-level operations such as a prefix listing to this value unsuffixed. |
| <a name="output_bucket_name"></a> [bucket\_name](#output\_bucket\_name) | Name of the versioned dataset bucket, for the callers that must be given it rather than hard-code it: infra/envs/dev/main.tf and infra/envs/prod/main.tf write it into Parameter Store, infra/modules/step-functions-batch passes it to each Fargate batch task as a container override, and data-migration/src/carddemo\_migration/loaders/s3\_stage.py reads it to stage dataset generations. This is the application dataset bucket, not the Terraform state bucket that infra/bootstrap/outputs.tf publishes as state\_bucket\_name. |
| <a name="output_dataset_prefixes"></a> [dataset\_prefixes](#output\_dataset\_prefixes) | Key prefix per generation-dataset family: one entry for each of the ten families, keyed exactly as var.dataset\_families is keyed and valued as the <domain>/<dataset>/ prefix that family's generations live under. Read by infra/modules/step-functions-batch for its per-state container overrides, by data-migration/src/carddemo\_migration/loaders/s3\_stage.py, and by the IAM policies that scope a task role to one family's prefix. Supplies the <domain>/<dataset>/ portion only; the dt= and gen= segments of a generation key are chosen per run by the writer. |
| <a name="output_dataset_uris"></a> [dataset\_uris](#output\_dataset\_uris) | Fully-qualified s3:// URI per generation-dataset family: the same ten keys as dataset\_prefixes, each resolved against the created bucket. This is the form infra/modules/step-functions-batch puts in a Fargate container override in place of a JCL DD DSN= statement, whereas dataset\_prefixes carries the bare-prefix form that an IAM resource pattern and a boto3 Prefix= argument need. Addresses the family, not a generation: a writer appends its own dt= and gen= segments. |
| <a name="output_non_generation_prefixes"></a> [non\_generation\_prefixes](#output\_non\_generation\_prefixes) | Key prefix per non-generation dataset -- the two sequential statement artifacts, plain text and HTML -- keyed exactly as var.non\_generation\_prefixes is keyed. Read by the GenerateStatements batch state, which writes both statements to S3. Deliberately separate from dataset\_prefixes: neither artifact has a generation-data-group base in the baseline, so counting them among the generation families would report twelve where variables.tf, docs/architecture/batch-orchestration.md and data-migration/README.md all publish ten. |
| <a name="output_non_generation_uris"></a> [non\_generation\_uris](#output\_non\_generation\_uris) | Fully-qualified s3:// URI per non-generation dataset: the same two keys as non\_generation\_prefixes, each resolved against the created bucket, so the GenerateStatements batch state receives its plain-text and HTML output locations as container overrides in the same form the generation-writing states receive theirs. |
| <a name="output_noncurrent_version_retention"></a> [noncurrent\_version\_retention](#output\_noncurrent\_version\_retention) | Effective number of noncurrent object versions the module retains per prefix, republished so a runbook, a verification query or a compliance review can confirm the baseline's LIMIT(5) SCRATCH generation limit is still being reproduced without reading the module's HCL. Reflects the module-level value only: a per-family override supplied through a dataset\_families entry's noncurrent\_versions member is not folded in. |
| <a name="output_object_access_trail_arn"></a> [object\_access\_trail\_arn](#output\_object\_access\_trail\_arn) | ARN of the CloudTrail trail whose advanced selector audits object-level access to the CardDemo dataset bucket. |
<!-- END_TF_DOCS -->
