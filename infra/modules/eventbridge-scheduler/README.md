# EventBridge Scheduler module

This module creates the nightly schedule, its exact Step Functions invocation
role and the encrypted SQS failure destination.

## Design decisions

**Assumptions:** the target input carries EventBridge Scheduler's immutable
scheduled-time context. The state machine derives its business date from that
value so retries and redrives do not read a later wall-clock date.

**Trade-offs:** flexible windows are disabled by default because shifting the
start changes which business date and online-write quiesce window an execution
represents.

## Validation

```bash
terraform -chdir=infra/modules/eventbridge-scheduler init -backend=false
terraform -chdir=infra/modules/eventbridge-scheduler validate
tflint --chdir=infra/modules/eventbridge-scheduler --config="$(pwd)/infra/.tflint.hcl"
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
| [aws_iam_role.this](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/iam_role) | resource |
| [aws_iam_role_policy.this](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/iam_role_policy) | resource |
| [aws_scheduler_schedule.this](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/scheduler_schedule) | resource |
| [aws_scheduler_schedule_group.this](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/scheduler_schedule_group) | resource |
| [aws_caller_identity.current](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/caller_identity) | data source |
| [aws_iam_policy_document.assume_role](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_iam_policy_document.permissions](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_partition.current](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/partition) | data source |
| [aws_region.current](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/region) | data source |

### Inputs

| Name | Description | Type | Default | Required |
|------|-------------|------|---------|:--------:|
| <a name="input_dead_letter_arn"></a> [dead\_letter\_arn](#input\_dead\_letter\_arn) | ARN of the SQS queue that receives an invocation EventBridge Scheduler could not deliver to the state machine. This is what makes a failed nightly trigger captured and inspectable rather than silently lost. | `string` | n/a | yes |
| <a name="input_environment"></a> [environment](#input\_environment) | Environment this schedule belongs to. Supplies the `-<env>` suffix that keeps the dev and prod copies of every resource this module creates distinct. Accepted values: dev, prod. | `string` | n/a | yes |
| <a name="input_state_machine_arn"></a> [state\_machine\_arn](#input\_state\_machine\_arn) | ARN of the `carddemo-daily-batch` Step Functions state machine this schedule starts. Published as an output by infra/modules/step-functions-batch and passed in by the environment root; the schedule's IAM role is granted states:StartExecution on exactly this value. | `string` | n/a | yes |
| <a name="input_dead_letter_kms_key_arn"></a> [dead\_letter\_kms\_key\_arn](#input\_dead\_letter\_kms\_key\_arn) | Customer-managed KMS key encrypting the dead-letter queue, when that queue is CMK-encrypted; null when it relies on SQS-managed encryption. Controls whether the schedule's role is additionally granted kms:GenerateDataKey and kms:Decrypt on that key. | `string` | `null` | no |
| <a name="input_flexible_time_window_minutes"></a> [flexible\_time\_window\_minutes](#input\_flexible\_time\_window\_minutes) | Width in whole minutes of the window an invocation may be shifted within. Must be null when flexible\_time\_window\_mode is OFF and must be an integer from 1 to 1440 when the mode is FLEXIBLE. | `number` | `null` | no |
| <a name="input_flexible_time_window_mode"></a> [flexible\_time\_window\_mode](#input\_flexible\_time\_window\_mode) | Whether EventBridge Scheduler may shift an invocation within a window rather than firing at the exact expression time. OFF fires at the expression time; FLEXIBLE spreads it across the window given by flexible\_time\_window\_minutes. Accepted values: OFF, FLEXIBLE. | `string` | `"OFF"` | no |
| <a name="input_kms_key_arn"></a> [kms\_key\_arn](#input\_kms\_key\_arn) | Customer-managed KMS key used to encrypt the schedule's stored target payload, or null to use the service-owned key. When set, main.tf grants the schedule execution role kms:Decrypt on this exact key so it can read the payload before invoking the state machine. | `string` | `null` | no |
| <a name="input_maximum_event_age_in_seconds"></a> [maximum\_event\_age\_in\_seconds](#input\_maximum\_event\_age\_in\_seconds) | Outer bound, in seconds, on how long retry attempts may continue before the invocation is sent to the dead-letter queue. Accepts 60 to 86400, the range the pinned AWS provider enforces. | `number` | `86400` | no |
| <a name="input_maximum_retry_attempts"></a> [maximum\_retry\_attempts](#input\_maximum\_retry\_attempts) | Retries attempted, with exponential backoff, before the invocation is sent to the dead-letter queue. Accepts 0 to 185, the range the pinned AWS provider enforces. | `number` | `5` | no |
| <a name="input_name_prefix"></a> [name\_prefix](#input\_name\_prefix) | Leading token shared by the schedule, its schedule group and the IAM role the schedule assumes, so the three resources that make up one nightly trigger are recognisable as a set in the console and in cost reporting. | `string` | `"carddemo"` | no |
| <a name="input_schedule_expression"></a> [schedule\_expression](#input\_schedule\_expression) | Cron expression in the six-field EventBridge Scheduler form `cron(minutes hours day-of-month month day-of-week year)` fixing when the batch chain is started. Only the cron(...) form is accepted; rate(...) and at(...) are rejected. | `string` | `"cron(0 0 * * ? *)"` | no |
| <a name="input_schedule_expression_timezone"></a> [schedule\_expression\_timezone](#input\_schedule\_expression\_timezone) | IANA time zone name, such as UTC or America/New\_York, that the cron expression above is interpreted in. | `string` | `"UTC"` | no |
| <a name="input_schedule_state"></a> [schedule\_state](#input\_schedule\_state) | Whether the schedule fires. ENABLED starts the batch chain on the expression above; DISABLED provisions the schedule, its group and its role but never triggers a run, which lets a dev root stand the trigger up and verify it without running the nightly chain. Accepted values: ENABLED, DISABLED. | `string` | `"ENABLED"` | no |
| <a name="input_start_date"></a> [start\_date](#input\_start\_date) | RFC3339 instant before which the schedule must not fire, or null to let it fire from the moment it is created. | `string` | `null` | no |
| <a name="input_tags"></a> [tags](#input\_tags) | Tags applied to the schedule group and to the schedule's IAM role. They are not applied to the schedule itself, because aws\_scheduler\_schedule exposes no tags argument. | `map(string)` | `{}` | no |
| <a name="input_target_input"></a> [target\_input](#input\_target\_input) | Additional key/value pairs merged into the JSON document handed to StartExecution. Keys are added to the payload the module already builds; they cannot remove or replace it. | `map(string)` | `{}` | no |

### Outputs

| Name | Description |
|------|-------------|
| <a name="output_schedule_arn"></a> [schedule\_arn](#output\_schedule\_arn) | ARN of that same schedule, for referring to it from outside this module: a dashboard or alarm identifies a schedule by ARN rather than by name, and a runbook step quotes it to establish which environment's trigger an execution came from. |
| <a name="output_schedule_group_name"></a> [schedule\_group\_name](#output\_schedule\_group\_name) | Name of the schedule group the schedule belongs to. The group is functional and not organisational: its ARN is the value the role's trust policy matches on `aws:SourceArn`, so the group is what bounds which schedules may assume that role at all. It is also where this module's tags land, because the schedule resource itself accepts none. |
| <a name="output_schedule_name"></a> [schedule\_name](#output\_schedule\_name) | Name of the nightly EventBridge Scheduler schedule that starts the `carddemo-daily-batch` Step Functions state machine on the cron expression supplied to this module. This is the handle an operator uses to locate that trigger, and the one to name when disabling or re-enabling it. |
| <a name="output_scheduler_role_arn"></a> [scheduler\_role\_arn](#output\_scheduler\_role\_arn) | ARN of the execution role EventBridge Scheduler assumes to act for this schedule. It is permitted to call `states:StartExecution` on the one state machine supplied and `sqs:SendMessage` on the one dead-letter queue supplied, plus the two data-key operations a CMK-encrypted queue needs when one is named, and nothing further. Published so a root can reference the trigger's identity and audit its effective privilege without reading the module. |
<!-- END_TF_DOCS -->
