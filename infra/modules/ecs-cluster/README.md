# ECS cluster module

This module creates the shared Fargate cluster, enables Container Insights and
configures the capacity-provider strategy inherited by service and batch tasks.

## Design decisions

**Alternatives Considered:** an EC2-backed cluster would add host patching,
capacity management and drain procedures without a workload constraint that
requires host control. Fargate keeps those operations outside this package.

**Assumptions:** task definitions and services remain in `ecs-service`; this
module publishes only cluster identity and capacity-provider facts.

## Validation

```bash
terraform -chdir=infra/modules/ecs-cluster init -backend=false
terraform -chdir=infra/modules/ecs-cluster validate
tflint --chdir=infra/modules/ecs-cluster --config="$(pwd)/infra/.tflint.hcl"
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
| [aws_ecs_cluster.this](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/ecs_cluster) | resource |
| [aws_ecs_cluster_capacity_providers.this](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/ecs_cluster_capacity_providers) | resource |

### Inputs

| Name | Description | Type | Default | Required |
|------|-------------|------|---------|:--------:|
| <a name="input_environment"></a> [environment](#input\_environment) | Deployment-environment discriminator appended to `name_prefix` to compose the cluster name. | `string` | n/a | yes |
| <a name="input_capacity_providers"></a> [capacity\_providers](#input\_capacity\_providers) | Capacity providers to associate with the cluster; only the two Fargate providers are supported. | `list(string)` | <pre>[<br/>  "FARGATE",<br/>  "FARGATE_SPOT"<br/>]</pre> | no |
| <a name="input_cluster_name"></a> [cluster\_name](#input\_cluster\_name) | Explicit cluster name that overrides the composed `name_prefix`-`environment` value; null keeps the composed name. | `string` | `null` | no |
| <a name="input_container_insights"></a> [container\_insights](#input\_container\_insights) | Cluster-level Container Insights tier written into the containerInsights cluster setting. | `string` | `"enabled"` | no |
| <a name="input_default_capacity_provider_strategy"></a> [default\_capacity\_provider\_strategy](#input\_default\_capacity\_provider\_strategy) | Default placement strategy for tasks that specify no launch type or strategy of their own. | <pre>list(object({<br/>    capacity_provider = string<br/>    weight            = optional(number)<br/>    base              = optional(number)<br/>  }))</pre> | <pre>[<br/>  {<br/>    "base": 1,<br/>    "capacity_provider": "FARGATE",<br/>    "weight": 4<br/>  },<br/>  {<br/>    "capacity_provider": "FARGATE_SPOT",<br/>    "weight": 1<br/>  }<br/>]</pre> | no |
| <a name="input_execute_command_log_group_name"></a> [execute\_command\_log\_group\_name](#input\_execute\_command\_log\_group\_name) | Name of an existing CloudWatch log group for ECS Exec session output; null omits the log configuration. | `string` | `null` | no |
| <a name="input_execute_command_logging"></a> [execute\_command\_logging](#input\_execute\_command\_logging) | ECS Exec log destination; one of NONE, DEFAULT or OVERRIDE. | `string` | `"DEFAULT"` | no |
| <a name="input_kms_key_arn"></a> [kms\_key\_arn](#input\_kms\_key\_arn) | Customer-managed KMS key reference encrypting the ECS Exec channel; null uses the AWS-managed default. | `string` | `null` | no |
| <a name="input_name_prefix"></a> [name\_prefix](#input\_name\_prefix) | Leading component of the composed cluster name; joined to `environment` with a hyphen. | `string` | `"carddemo"` | no |
| <a name="input_tags"></a> [tags](#input\_tags) | Additional tags merged onto the cluster resources, on top of the provider's default\_tags. | `map(string)` | `{}` | no |

### Outputs

| Name | Description |
|------|-------------|
| <a name="output_capacity_provider_names"></a> [capacity\_provider\_names](#output\_capacity\_provider\_names) | Short names of the capacity providers associated with the cluster, as a set. A caller writing a per-service capacity-provider strategy may name only a provider that appears here, because ECS refuses a strategy entry naming a provider the cluster does not hold. |
| <a name="output_cluster_arn"></a> [cluster\_arn](#output\_cluster\_arn) | ARN of the ECS Fargate cluster this module creates. aws\_ecs\_service takes it as its `cluster` argument, the batch state machine carries it in each task state's parameters, and the execution and task role policies are scoped to it so a task may be started only in this cluster. |
| <a name="output_cluster_name"></a> [cluster\_name](#output\_cluster\_name) | Bare name -- not the ARN -- of the ECS Fargate cluster this module creates. ecs-service consumes it to compose the Application Auto Scaling target identifier `service/<cluster-name>/<service-name>`, which AWS accepts in no other form. |
| <a name="output_default_capacity_provider_strategy"></a> [default\_capacity\_provider\_strategy](#output\_default\_capacity\_provider\_strategy) | Cluster-level default placement strategy, as a set of objects carrying `capacity_provider`, `weight` and `base`. ECS applies it to any task or service naming neither a launch type nor a strategy of its own; a consumer that sets its own strategy replaces this one outright rather than merging with it. An empty set means the cluster has no default and a launch type or strategy must be named per service. |
<!-- END_TF_DOCS -->
