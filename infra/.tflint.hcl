# =============================================================================
# infra/.tflint.hcl
# -----------------------------------------------------------------------------
# TFLint configuration for the CardDemo Terraform tree. This is the LINT half
# of the HCL documentation gate; infra/.terraform-docs.yml is the other half.
#
# Purpose:
#   HCL has no docstring construct, so the project-wide documentation
#   obligation -- "a file-header comment block in every .tf file, a
#   `description` on every `variable` and `output`, and a why-comment on each
#   non-obvious resource argument", per the "HCL (Terraform)" section of
#   docs/CODE_DOCUMENTATION_STANDARD.md -- cannot be checked by a docstring
#   parser the way Java, TypeScript and Python are checked by Checkstyle,
#   eslint-plugin-jsdoc and ruff's pydocstyle family. This file is what makes
#   the HCL half MECHANICAL rather than aspirational: it turns the
#   `description` clause into a build failure. It governs the entire Terraform
#   tree -- the three roots (infra/bootstrap, infra/envs/dev, infra/envs/prod)
#   and every module under infra/modules/.
#
#   Inventory -- the planned catalogue and the measured present state, kept
#   distinct because conflating them is how a false count spreads:
#     - The module catalogue this migration plans is SIXTEEN modules, which with
#       the three roots gives nineteen Terraform directories.
#     - FIFTEEN module directories exist at this checkpoint, giving EIGHTEEN
#       Terraform directories. The sixteenth, infra/modules/step-functions-batch,
#       is authored at a later index of the same plan.
#     - Nothing here has to change when it lands. The traversal is `--recursive`
#       over the tree, so the directory list is derived from the filesystem and
#       never enumerated in this file; the counts above are documentation, not
#       configuration.
#
# Parameters -- the blocks below, and what each one controls:
#   config             Traversal and failure behaviour: whether a finding fails
#                      the run, and whether a root's `module` calls are followed
#                      into the child directories they name.
#   plugin "terraform" The bundled Terraform-language ruleset, and which preset
#                      of it is active.
#   plugin "aws"       The AWS-resource ruleset, pinned by version, which
#                      validates provider-specific arguments and values.
#   rule "<name>"      Per-rule opt-in and opt-out, stated explicitly so the
#                      intent is readable here instead of being inherited
#                      invisibly from whichever preset happens to be selected.
#
# Return values:
#   A run over this configuration exits 0 when it finds nothing and 2 when it
#   finds one or more violations, which reddens the gating lint step in
#   .github/workflows/infra-ci.yml -- a workflow authored at a later index of the
#   same plan, so at this checkpoint the run is invoked by hand rather than by
#   CI. There is no tolerated-finding tier and no return-code band: the outcome
#   is binary, clean or failed. Exit 1 means TFLint itself could not run -- see
#   Errors.
#
#   State at this checkpoint, stated because a "gating" description otherwise
#   reads as a claim that the gate is currently green: a recursive run exits 2
#   with 257 findings, and every one of them belongs to one of exactly two rule
#   families -- 217 terraform_unused_declarations and 40
#   terraform_standard_module_structure. Both are consequences of the tree being
#   incomplete rather than of a defect in the HCL authored so far: no directory
#   has its main.tf or outputs.tf yet, so no variable is consumed and no
#   directory satisfies the standard file set. Both families clear as those two
#   files land per directory, and the trade-off note on
#   terraform_standard_module_structure below records that the transient state is
#   accepted deliberately. What DOES pass today, and is the whole of what a
#   provider-constraint-and-input-surface foundation can pass: HCL parse of every
#   .tf file, `terraform fmt -check -recursive infra/`, and a type plus a
#   description on every declared variable.
#
# Errors / Exceptions -- the three ways this gate can MISREPORT, every one of
# them a property of the invocation rather than of this file:
#
#   1. TFLint exits 1 without linting anything if this file fails to parse, if
#      a plugin declared below has not been installed by `tflint --init`, or if
#      the path handed to --config cannot be opened.
#
#   2. `--recursive` makes TFLint change into each directory in turn and
#      resolve --config RELATIVE TO THAT DIRECTORY. A relative
#      `--config=.tflint.hcl` therefore fails in every subdirectory, and
#      omitting --config altogether is worse than failing: each subdirectory
#      then finds no configuration, falls back to TFLint's built-in defaults,
#      and the run exits 0 having checked none of the rules below. That is a
#      gate failing OPEN, so the absolute-path requirement recorded on the
#      config block is not a preference.
#
#   3. Raising the failure floor with --minimum-failure-severity disarms the
#      two documentation rules specifically, because both report at `notice`
#      rather than at `warning` or `error`. See terraform_documented_variables.
#
#   Alternatives Considered: three further checks could plausibly have been
#   added here, and each was declined because another gate already owns it, so
#   running both would report a single finding under two owners --
#     - formatting ................ `terraform fmt -check -recursive infra/`
#     - generated module docs and
#       their freshness / drift ... infra/.terraform-docs.yml
#     - security policy scanning .. the separate, severity-thresholded policy
#                                   scan step in infra-ci.yml
#   None of the three is therefore absent by oversight.
#
# WHY this file exists at all, stated once here because nothing in the
# migration requirements themselves calls for it:
#   - Alternatives Considered: treating the documentation obligation as silent
#     on languages that have no docstring construct. Rejected. infra/** is the
#     largest body of net-new non-obvious decisions in the repository -- three
#     roots and a sixteen-module catalogue covering networking, key management,
#     database capacity, identity and IAM -- so exempting it would leave exactly
#     the code most in need of explanation as the only code with no mechanical
#     check on whether it was explained. A linter plus a per-directory README
#     is the closest available equivalent to a docstring parser, so both are
#     used: this file is the linter, and the README in each module directory and
#     both environment roots is the prose.
#   - Assumptions: what this file can decide is narrower than the obligation it
#     serves, and the difference is recorded so that a green run is not read as
#     a documented tree. TFLint decides that a `description` is PRESENT on every
#     variable and output and that the prescribed file set exists. It assesses
#     nothing about whether a description is informative, and it does not read a
#     why-comment at all -- terraform_comment_syntax below checks comment SYNTAX,
#     never comment CONTENT. The file-header block and the per-argument
#     why-comment are therefore human-review obligations with no machine backing
#     anywhere, which is precisely why the per-directory README is required as
#     the prose half rather than offered as a courtesy. No README exists in any
#     directory at this checkpoint; they are a later deliverable of the same plan.
# =============================================================================

config {
  # WHAT: a finding is a failure.
  # WHY : Assumptions: the lint step in .github/workflows/infra-ci.yml is
  #       GATING -- it carries no `|| true`, no `continue-on-error` and no
  #       return-code tolerance -- and this attribute is what makes that true
  #       rather than decorative. `force = true` returns zero even with
  #       findings, which is the single setting that would reduce this whole
  #       file to a description of a check nobody enforces.
  #       This is deliberately NOT the condition-code rubric the COBOL suite
  #       uses (0 pass / 4 warn / 8 fail / 16 fatal, where 4 is that suite's
  #       documented green state). That band exists only to accommodate one
  #       immutable defect in the REFERENCE-only baseline, it belongs to that
  #       suite alone, and importing an `rc <= 4` tolerance here would accept
  #       findings while still reporting green.
  force = false

  # WHAT: follow a root's `module` calls into the child directories they name.
  # WHY : Trade-offs: measured rather than assumed. Under `none` each directory
  #       is judged only on what it literally contains, so a value-dependent
  #       defect that exists only once a caller supplies a value is invisible:
  #       a module taking a capacity or an instance type as an input cannot
  #       know what the root passed it, so the AWS ruleset check on that value
  #       never runs. That matters here in particular because infra/envs/dev
  #       and infra/envs/prod are identical in topology and differ ONLY in
  #       sizing, capacity and retention values -- exactly the values those
  #       checks validate -- so `none` would blind the gate to the only axis
  #       along which the two roots can actually diverge. The accepted cost of
  #       `local` is that one defect inside a module that BOTH roots call is
  #       reported once per calling root (two reports for one defect, given two
  #       roots) and is attributed to the `module` block in the calling root's
  #       main.tf rather than to the module's own file; one fix clears every
  #       report. Linting a module on its own is unaffected: with no caller its
  #       input values are unknown, so the value checks are skipped rather than
  #       guessed and no false positive is produced.
  #       Alternatives Considered: `all`, which additionally resolves module
  #       sources fetched from a registry or a git remote. Rejected -- every
  #       module source in this tree is a local relative path, so `all` would
  #       resolve nothing extra while making the lint step depend on a
  #       populated module cache, and therefore on a prior `terraform init`
  #       reaching the network, for no gain.
  call_module_type = "local"

  # WHAT: no recursion setting, even though recursion is what makes ONE
  #       invocation cover every governed directory rather than only the one it
  #       was launched from.
  # WHY : Assumptions: TFLint accepts no `recursive` attribute -- traversal is a
  #       property of the command rather than of the configuration, so it can
  #       only be supplied as `--recursive`. The invocation contract that
  #       follows from that is recorded here rather than expressed above, and it
  #       is not optional:
  #
  #           tflint --init --config="$PWD/infra/.tflint.hcl"
  #           cd infra && tflint --recursive --config="$(pwd)/.tflint.hcl"
  #
  #       The --config path MUST be absolute, for the reason given as Errors
  #       item 2 in the header: under --recursive a relative path resolves
  #       against each visited directory, and an absent one makes the run pass
  #       while checking nothing. Setting TFLINT_CONFIG_FILE to that same
  #       absolute path is equivalent.
  #       Alternatives Considered: one single-directory invocation per root and
  #       per module, each of which would be free to use a relative path.
  #       Rejected -- it multiplies the CI surface by the number of directories
  #       and, more seriously, turns the directory list into something a human
  #       maintains, so the next module added would silently go unlinted with the
  #       gate still reporting green. That is not hypothetical here: the module
  #       catalogue is not complete, so a hand-maintained list would be wrong the
  #       day step-functions-batch lands. `--recursive` derives the list from the
  #       tree itself and cannot be wrong.
}

# WHY : Alternatives Considered: `preset = "recommended"`, which is the obvious
#       choice and is wrong for this repository. `recommended` omits precisely
#       the rules this file exists to run -- terraform_documented_variables and
#       terraform_documented_outputs, which ARE the HCL half of the
#       documentation obligation, plus terraform_comment_syntax and
#       terraform_naming_convention. Selecting it would leave a configuration
#       that lints Terraform style while enforcing nothing about documentation,
#       which is the one outcome that would make this file pointless. `all`
#       enables the full ruleset; the explicit `rule` blocks below then record
#       intent for each load-bearing rule, and declare the single deliberate
#       exception, so that a reader learns the policy from this file instead of
#       having to know a preset's contents by heart.
#       No `version` or `source` is given because this ruleset is bundled with
#       the binary rather than downloaded, so its version is fixed by the
#       TFLint release the runner installs and cannot drift independently.
plugin "terraform" {
  enabled = true
  preset  = "all"
}

# WHAT: the AWS-resource ruleset, which checks provider-specific arguments and
#       values that the language ruleset knows nothing about.
# WHY : Assumptions: this tree provisions Aurora, KMS, Cognito, SQS, Step
#       Functions, ECS, an ALB, an HTTP API, CloudFront and S3, so most of what
#       can be wrong in it is an AWS argument rather than an HCL construct --
#       an invalid capacity value or an unrecognised resource argument is
#       invisible to the language ruleset and is caught here instead, before
#       `plan` rather than during it.
#       The version is pinned exactly, not floated, for the same reason the
#       Python test dependencies are pinned with `==` and hash-verified: a gate
#       whose rule set can change underneath an unchanged tree produces
#       findings that nobody changed anything to cause, and cannot be
#       reproduced from the repository alone. 0.48.0 was resolved from the
#       ruleset's own published releases and verified to install and load
#       against the TFLint release in use, rather than being assumed.
#       Alternatives Considered: omitting this plugin so that no network fetch
#       is needed at all, leaving only the bundled ruleset. Rejected -- it
#       would drop every provider-level check over the tree's entire surface,
#       and the fetch happens once per runner in `tflint --init`, which the
#       gate already performs.
#       No `signing_key` is declared: this ruleset is published by the TFLint
#       project itself, and its signing key is built into the binary.
plugin "aws" {
  enabled = true
  version = "0.48.0"
  source  = "github.com/terraform-linters/tflint-ruleset-aws"
}

# -----------------------------------------------------------------------------
# The documentation gate proper.
#
# The two rules in this section are the reason this file is in the repository at
# all. Every other rule below is a supporting check; these two are the
# obligation itself, expressed mechanically.
# -----------------------------------------------------------------------------

# WHY : Assumptions: this rule and terraform_documented_outputs immediately
#       below ARE the HCL half of the project's docstring requirement. A
#       `variable` block is a module's public input contract, and HCL gives it
#       no place to carry a docstring except `description`; a variable without
#       one is therefore an undocumented public parameter, which is the exact
#       omission the documentation standard forbids and which Checkstyle,
#       eslint-plugin-jsdoc and the pydocstyle family each forbid in their own
#       language. This rule is what turns that clause from a review convention
#       into a failed build, so it is the one rule in this file that must never
#       be relaxed. It is also the reason the header insists the failure floor
#       stay at its default: this rule and the next report at `notice`
#       severity, so a run invoked with --minimum-failure-severity=warning
#       finds both violations, prints both, and still exits 0 -- the gate would
#       be disarmed without any edit to this file and without any visible sign
#       in the log.
#       Declared explicitly even though `preset = "all"` already enables it,
#       because a policy this load-bearing should not depend on a reader
#       knowing which rules a preset contains.
rule "terraform_documented_variables" {
  enabled = true
}

# WHY : Assumptions: the other half of the same clause. An `output` is a
#       module's public return value, and it is consumed across a directory
#       boundary by a caller that cannot see the resource it came from, so its
#       `description` is the only thing standing between the caller and reading
#       the module's implementation to find out what it received. This rule
#       additionally protects the other gate: infra/.terraform-docs.yml
#       generates each module's README from exactly these descriptions, so an
#       undocumented output becomes a blank cell in generated documentation
#       that is itself drift-checked.
rule "terraform_documented_outputs" {
  enabled = true
}

# -----------------------------------------------------------------------------
# Toolchain-contract checks.
# -----------------------------------------------------------------------------

# WHY : Assumptions: every governed directory is required to state a Terraform
#       CLI floor, and this rule is what detects a directory that forgot. The
#       values themselves are owned by the per-directory versions.tf files and
#       are deliberately not repeated here -- this rule asserts only
#       that the declaration is present, so the floor can be raised in those
#       files without editing this one. The check earns its place because a
#       root or module with no floor does not fail loudly on an unsupported
#       CLI; it fails obscurely, at whichever construct that CLI cannot parse.
rule "terraform_required_version" {
  enabled = true
}

# WHY : Assumptions: the companion check, that every provider a directory uses
#       is version-constrained in `required_providers`. In this tree an
#       unconstrained provider is a genuine defect rather than untidiness: the
#       AWS provider constraint carries a real floor, because the zero-minimum
#       Aurora capacity that the dev environment depends on is not accepted by
#       older provider releases, and a directory that omits the constraint
#       resolves to whatever the lock file or the registry offers instead. That
#       failure surfaces as a rejected capacity argument during `plan`, far
#       from the missing declaration that caused it. As with the rule above,
#       the constraint values live in the versions.tf files; this rule only
#       asserts that they exist.
rule "terraform_required_providers" {
  enabled = true
}

# -----------------------------------------------------------------------------
# Contract-clarity checks.
# -----------------------------------------------------------------------------

# WHY : Assumptions: a variable with no `type` accepts anything, so its contract
#       is undocumented in the strongest sense available -- a reader, and the
#       generated README, can see that an input exists but not what shape it
#       takes, and a caller passing a string where a list was intended fails
#       inside the module rather than at the call site. A `description` says
#       what an input means and a `type` says what it accepts; the
#       documentation obligation is only half met with one of them.
rule "terraform_typed_variables" {
  enabled = true
}

# WHY : Trade-offs: this rule reports variables, locals and data sources that
#       are declared and never used, and the reason to accept its noise is that
#       a stale declaration is a FALSE contract rather than dead weight.
#       infra/.terraform-docs.yml generates each module's README from its
#       declared variables, so an input the module has stopped honouring is
#       published to callers as though it still worked -- documentation that is
#       present, current, drift-checked and wrong, which is worse than
#       documentation that is missing. The cost accepted is that removing a
#       resource now requires removing the declarations that fed it in the same
#       change; several versions.tf files in this tree cite this rule as the
#       reason they omit a provider they do not consume.
#       Note this rule is distinct from terraform_unused_required_providers
#       below, which is also enabled: this one covers variables, locals, data
#       sources and provider ALIASES, and that one covers entries in
#       `required_providers`.
rule "terraform_unused_declarations" {
  enabled = true
}

# WHY : Assumptions: the governed directories are authored independently, so the
#       naming convention cannot be held by anyone remembering it. The rule's
#       default is snake_case for every identifier, which is what the Terraform
#       language documentation recommends and what the tree already uses; its
#       value is that it holds for the directory authored last as firmly as for
#       the one authored first. It also keeps identifiers predictable for the
#       generated documentation, where a resource name appears verbatim.
rule "terraform_naming_convention" {
  enabled = true
}

# WHY : Assumptions: `"${var.name}"` wrapping a single reference is 0.11-era
#       syntax that still parses, still works, and is therefore invisible to
#       everything except a rule that looks for it -- `terraform validate`
#       accepts it and `terraform fmt` does not rewrite it, so nothing else in
#       the gate would report it. Left unchecked it accumulates, and it matters
#       more than style here because the wrapped form silently coerces its
#       reference to a string: applied to a number or a bool it changes the
#       value's type, which is a real defect in a tree whose environment roots
#       differ mainly in numeric capacity and retention values.
rule "terraform_deprecated_interpolation" {
  enabled = true
}

# WHY : Assumptions: this rule is load-bearing for the documentation convention
#       itself, not merely for uniformity. It rejects `//` in favour of `#`, and
#       `#` is the form every why-comment in this tree is written in -- the
#       existing test-suite documentation and used by this file's own comments.
#       Allowing both forms would fork that idiom in a language where the
#       comment IS the docstring, leaving a tree whose documentation cannot be
#       recognised by a single convention, and infra/.terraform-docs.yml would
#       have to accommodate two header shapes rather than one. Of every rule
#       here, this is the one whose justification is the documentation standard
#       rather than Terraform behaviour.
rule "terraform_comment_syntax" {
  enabled = true
}

# WHY : Assumptions: the per-directory file set is prescribed -- versions.tf,
#       main.tf, variables.tf and outputs.tf in every governed directory, plus
#       backend.tf and terraform.tfvars in the two environment roots and a
#       README.md throughout -- and this rule is what enforces the main.tf,
#       variables.tf and outputs.tf part of it, including that `variable` and
#       `output` blocks live in the file named for them rather than wherever
#       they were convenient. That placement is what the generated README and
#       every reader depend on.
#       This rule was verified against the prescribed layout rather than
#       assumed compatible with it: run over a directory holding exactly that
#       file set it reports nothing, and it does not object to the additional
#       files the layout adds beyond the three it requires. Enabling it
#       therefore holds the tree to its own documented shape instead of
#       fighting it.
#       Trade-offs: it reports a directory as non-conforming while that
#       directory is incomplete, so it constrains the order in which a new
#       module can be added -- a module contributes findings until all three
#       files exist. That is accepted, because a module missing its outputs.tf
#       is exactly the state the rule should refuse to call clean.
rule "terraform_standard_module_structure" {
  enabled = true
}

# -----------------------------------------------------------------------------
# No global exception. The one rule that previously carried one is enabled here,
# and its single legitimate exception is suppressed locally instead.
# -----------------------------------------------------------------------------

# WHY : the rule reports a provider that is declared in `required_providers`
#       but used by no resource in the SAME directory, and it cannot follow a
#       `module` call to see the resources one level down. It is kept ON so that
#       a module declaring -- and so publishing a constraint on -- a provider it
#       does not consume is still reported, which is the case the rule exists
#       for and the one this tree cannot detect any other way.
#       Assumptions: exactly one class of finding here is legitimate and
#       permanent. Both environment roots declare the random provider without
#       using it directly, deliberately: its only consumers are the secrets and
#       cognito modules, which generate the database credential and the
#       seed-user passwords straight into Secrets Manager, and a root-level
#       constraint is the only thing that resolves the whole module graph to one
#       release of that provider instead of letting each child resolve it
#       independently and drift. That finding does not clear once those modules
#       are called, because the resources the rule is looking for are never in
#       the file it is reading, so each of those two declarations carries a
#       `# tflint-ignore: terraform_unused_required_providers` annotation at the
#       point of use, with the reason recorded beside it. Every other finding
#       this rule currently raises is transient: the module directories declare
#       their providers before their `main.tf` exists, so the rule has no
#       resources to match yet and each finding clears when that file lands.
#       Transient findings are deliberately left unannotated -- annotating them
#       would leave a permanent suppression behind for a condition that resolves
#       itself, which is how a gate quietly stops covering the case it was
#       written for.
#       Alternatives Considered: disabling the rule tree-wide, which is the one
#       global exception this file previously carried. Rejected because it buys
#       silence on two known declarations at the price of never again reporting
#       a module that constrains a provider it genuinely does not use, and
#       because a suppression written at the point of use states which
#       declaration is excused and why, where a disabled rule states neither.
#       Trade-offs: the accepted cost is that the two annotations must be kept
#       in step with the roots that carry them, and that this gate reports the
#       module directories until their `main.tf` files exist.
#       terraform_unused_declarations above still covers unused variables,
#       locals, data sources and provider aliases, and
#       terraform_required_providers still requires every provider that IS used
#       to be constrained.
rule "terraform_unused_required_providers" {
  enabled = true
}
