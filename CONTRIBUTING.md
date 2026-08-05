# Contributing Guidelines

Thank you for your interest in contributing to our project. Whether it's a bug report, new feature, correction, or additional
documentation, we greatly value feedback and contributions from our community.

Please read through this document before submitting any issues or pull requests to ensure we have all the necessary
information to effectively respond to your bug report or contribution.


## Reporting Bugs/Feature Requests

We welcome you to use the GitHub issue tracker to report bugs or suggest features.

When filing an issue, please check existing open, or recently closed, issues to make sure somebody else hasn't already
reported the issue. Please try to include as much information as you can. Details like these are incredibly useful:

* A reproducible test case or series of steps
* The version of our code being used
* Any modifications you've made relevant to the bug
* Anything unusual about your environment or deployment


## Contributing via Pull Requests
Contributions via pull requests are much appreciated. Before sending us a pull request, please ensure that:

1. You are working against the latest source on the *main* branch.
2. You check existing open, and recently merged, pull requests to make sure someone else hasn't addressed the problem already.
3. You open an issue to discuss any significant work - we would hate for your time to be wasted.

To send us a pull request, please:

1. Fork the repository.
2. Modify the source; please focus on the specific change you are contributing. If you also reformat all the code, it will be hard for us to focus on your change.
3. Ensure local tests pass.
4. Commit to your fork using clear commit messages.
5. Send us a pull request, answering any default questions in the pull request interface.
6. Pay attention to any automated CI failures reported in the pull request, and stay involved in the conversation.
7. Ensure any new or modified code satisfies the documentation requirements in *Code documentation and explainability* below.

GitHub provides additional document on [forking a repository](https://help.github.com/articles/fork-a-repo/) and
[creating a pull request](https://help.github.com/articles/creating-a-pull-request/).


## Code documentation and explainability

Every contribution to the migrated code in this repository must document both *what* the code does and *why* particular implementation decisions were made. This is a hard review gate, not a preference. Code missing either its required docstring (or analogous file header) or its adjacent rationale fails review, and the mechanical checks listed further down fail the build.

The authoritative, per-language form of this convention -- with a conforming example for each language in the tree -- is [docs/CODE_DOCUMENTATION_STANDARD.md](docs/CODE_DOCUMENTATION_STANDARD.md). This section states the obligation; that document is where the worked examples live. Read the two together, and read the linters as a floor rather than a ceiling: they implement only the subset of the convention a tool can decide, so passing them is necessary but never sufficient. Review still owns whether the prose is accurate and the rationale genuine, because no linter can determine whether a sentence is true.

The convention itself is not new. It generalises the rule the COBOL test suite already applies, recorded in [tests/README.md](tests/README.md), which asks for exactly the same docstring elements and exactly the same four rationale categories. We extended that existing gate to the other languages rather than writing a second one, because two overlapping conventions would let a contributor or a reviewer satisfy whichever of them happened to be weaker. There is one convention here, and it now covers every language in the repository.

**Where it applies.** All newly authored code and configuration:

* `services/**` -- Java
* `ui/**` -- TypeScript and TSX
* `data-migration/**` -- Python
* `infra/**` -- Terraform/HCL
* SQL migrations under `services/*/src/main/resources/db/migration/**` and `data-migration/sql/**`
* every `Dockerfile`
* the migration workflows `.github/workflows/services-ci.yml`, `.github/workflows/ui-ci.yml`, `.github/workflows/infra-ci.yml` and `.github/workflows/deploy.yml`
* the migration documentation under `docs/**`

**Where it does not apply.** The COBOL baseline under `app/**` -- programs, copybooks, BMS mapsets, JCL, the CSD (CICS resource definitions), and the seed data -- is reference-only and is never modified, so no retrofitting of Javadoc-equivalent commentary into `app/cbl/*.cbl` is required -- or permitted. That baseline is the behavioural oracle against which functional parity is verified, and it has to stay byte-identical for the comparison to mean anything; it carries no documentation obligation because it is authoritative source material to be cited by path and line, not because it is deprecated. The convention likewise changes nothing under `tests/**` or `scripts/**`: the existing test suite already satisfies the equivalent house convention, keeps its pinned dependencies exactly as they are, and continues to run unchanged on its own pipeline, `.github/workflows/tests.yml`.

**Docstrings and API comments.** Every new or modified function, class, and module entry point must carry a docstring -- expressed in the language's standard API-comment form where applicable -- containing all of these elements:

* **Purpose** -- what the function, class, or entry point does and why it exists
* **Parameters** -- the name, type, and description of each parameter
* **Return values** -- the type and description of what is returned
* **Exceptions or errors** -- anything that may be raised or reported, where applicable

Trivial accessors -- getters or setters with no logic -- may use a single-line docstring.

Use the language's standard format:

* Java uses Javadoc (`/** ... */`) with `@param`, `@return`, and `@throws` where applicable.
* TypeScript and TSX use JSDoc/TSDoc-compatible comments (`/** ... */`) with `@param` and `@returns`.
* Python uses module, class, and function docstrings with `Args:`, `Returns:`, and `Raises:` sections where applicable.

**Inline rationale.** Place comments adjacent to the code they explain, and use them to explain *why* a decision was made rather than *what* the code already says. Every non-obvious decision must document at least one of the same four categories the COBOL suite already uses:

* **Alternatives Considered** -- which other approaches were evaluated and why this one was chosen
* **Refactoring Rationale** -- when replacing code, what was wrong with the old approach
* **Assumptions** -- which external contracts, data formats, or behaviours the decision depends on
* **Trade-offs** -- which compromises were accepted and why

**Formats without docstrings.** Where a language or file format has no docstring construct, provide the analogous information in its native comments or metadata:

* Each Terraform/HCL `.tf` file starts with a file-header block. Every `variable` and `output` has a `description`, non-obvious resource arguments have adjacent why-comments, and every module directory and environment root has a `README.md`.
* Each SQL migration starts with a header naming its purpose and source copybook or table. Adjacent why-comments explain every non-obvious constraint, check, or index, including the `ON DELETE RESTRICT` foreign key that preserves legacy referential behaviour and the descending-order index that matches the legacy fraud index.
* Every `Dockerfile` starts with a header and explains the base-image pin and layer-ordering decisions.
* Each migration CI workflow YAML file starts with a header and explains job ordering, caching strategy, the least-privilege `permissions` block, and why every action is pinned to an immutable commit SHA.
* Every newly authored shell entry point in an in-scope path starts with a header block describing its purpose, inputs, outputs, failure behaviour, and non-obvious choices.

**Forbidden patterns.** Review rejects all of the following:

* comments that restate the code, such as `increment counter` next to `counter++`
* docstrings that omit purpose, parameters, or return values
* a non-obvious implementation choice left undocumented when a reasonable alternative exists
* vague rationales such as `this is better` or `for performance` without specific justification

**Mechanical validation gates.** The prose requirement is enforced mechanically at five points:

1. Java uses `config/checkstyle/checkstyle.xml` and `config/checkstyle/suppressions.xml` through `services/pom.xml`, where Checkstyle is bound to Maven's `validate` phase. Binding the gate to `validate` makes it run on every local build rather than only in CI, so a contributor sees missing Javadoc before pushing; the trade-off is a marginally slower local build in exchange for faster feedback.
2. TypeScript and TSX use `eslint-plugin-jsdoc` through `ui/eslint.config.js`, requiring JSDoc on exported components and functions.
3. Python's documentation gate is the pydocstyle `D` family selected under `[tool.ruff.lint]` in `data-migration/pyproject.toml`.
4. Terraform/HCL uses `infra/.tflint.hcl` for documented variables and outputs and `infra/.terraform-docs.yml` for generated-documentation drift checks.
5. CI repeats the gates as required steps in `.github/workflows/services-ci.yml`, `.github/workflows/ui-ci.yml`, and `.github/workflows/infra-ci.yml`. The existing `.github/workflows/tests.yml` remains unchanged because the test suite already carries the equivalent convention.

Do not bump the TypeScript pin in `ui/package.json` without first checking the upper bound on the compiler version accepted by the type-aware lint toolchain. Crossing that bound can leave the manifest installable while silently preventing the type-aware ESLint pass, and therefore the JSDoc gate, from running; verify the compatible range and upgrade procedure in [the documentation standard](docs/CODE_DOCUMENTATION_STANDARD.md) before changing the pin.


## Finding contributions to work on
Looking at the existing issues is a great way to find something to contribute on. As our projects, by default, use the default GitHub issue labels (enhancement/bug/duplicate/help wanted/invalid/question/wontfix), looking at any 'help wanted' issues is a great place to start.


## Code of Conduct
This project has adopted the [Amazon Open Source Code of Conduct](https://aws.github.io/code-of-conduct).
For more information see the [Code of Conduct FAQ](https://aws.github.io/code-of-conduct-faq) or contact
opensource-codeofconduct@amazon.com with any additional questions or comments.


## Security issue notifications
If you discover a potential security issue in this project we ask that you notify AWS/Amazon Security via our [vulnerability reporting page](http://aws.amazon.com/security/vulnerability-reporting/). Please do **not** create a public github issue.


## Licensing

See the [LICENSE](LICENSE) file for our project's licensing. We will ask you to confirm the licensing of your contribution.
