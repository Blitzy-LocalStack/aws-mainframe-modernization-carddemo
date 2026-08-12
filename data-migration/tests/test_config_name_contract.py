"""Verify that the names this package resolves are the names the infrastructure creates.

Purpose
-------
Assert the producer-consumer contract between ``carddemo_migration.config`` and
``infra/modules/secrets/main.tf``: the extract-transform-load package reads a
credential from Secrets Manager and three settings from Parameter Store, and the
Terraform module creates them. If the two spell one object differently, nothing fails
until a load runs against a provisioned account, and the failure is a not-found error
that names a path nobody wrote.

Alternatives Considered:
    Asserting against a live account, or against a Terraform plan, was considered and
    rejected. Both need credentials this suite must not require, so the check would be
    skipped exactly where a drift is introduced -- in an ordinary edit to either file.
    The naming rule is short enough to state twice and is stated once in each artifact,
    so a test that reads the Terraform text and compares it against what this package
    resolves closes the loop without leaving the workstation.

Assumptions:
    The single most important expectation here is the LEADING SEPARATOR, because the
    two stores disagree about it: Parameter Store distinguishes a hierarchical name,
    which begins with one, from a flat name, which cannot; Secrets Manager rejects an
    identifier that begins with one outright. A name that is right for one store is
    therefore wrong for the other, and this is the one place both are checked together.
"""

from __future__ import annotations

import os
import re
from pathlib import Path

import pytest

from carddemo_migration import config
from carddemo_migration.config import (
    LOGIN_ROLE_NAMES,
    MIGRATION_SCHEMA_ROLES,
    OWNED_SCHEMA_ROLES,
    SCHEMA_NAMES,
    database_secret_name,
    database_secret_name_for_role,
    migration_role_for_schema,
    owner_role_for_schema,
    parameter_path,
    role_for_schema,
)
from carddemo_migration.loaders.protected_columns import (
    CARD_VERIFICATION_VALUE_PURPOSE,
    CONTEXT_PURPOSE_KEY,
    CUSTOMER_IDENTIFIER_PURPOSE,
)

# Assumptions: the Terraform module is located relative to this file rather than
#   to the working directory, so the expectation holds however the suite is invoked.
_SECRETS_MAIN_TF = Path(__file__).resolve().parents[2] / "infra" / "modules" / "secrets" / "main.tf"
_SECRETS_VARIABLES_TF = (
    Path(__file__).resolve().parents[2] / "infra" / "modules" / "secrets" / "variables.tf"
)
_BOOTSTRAP_SQL = (
    Path(__file__).resolve().parents[2] / "data-migration" / "sql" / "V0__schemas_and_roles.sql"
)
_SERVICES_DIR = Path(__file__).resolve().parents[2] / "services"
_ENVIRONMENT_ROOTS = tuple(
    Path(__file__).resolve().parents[2] / "infra" / "envs" / name for name in ("dev", "prod")
)

# Assumptions: the schema-to-service mapping is written out because the two names differ
#   for exactly one context -- the `ledger` schema belongs to transaction-service -- so
#   deriving one from the other would need the exception anyway. The seven entries are the
#   contexts that ship a Flyway migration, which is MIGRATION_SCHEMA_ROLES' own key set;
#   the test below asserts that agreement rather than assuming it.
_MIGRATING_SERVICE_BY_SCHEMA = {
    "auth": "auth-service",
    "account": "account-service",
    "card": "card-service",
    "ledger": "transaction-service",
    "reference": "reference-service",
    "batch": "batch-service",
    "authorization": "authorization-service",
}
_AURORA_MAIN_TF = (
    Path(__file__).resolve().parents[2] / "infra" / "modules" / "aurora-postgresql" / "main.tf"
)

# Assumptions: the environment name has no default in the package, on purpose --
#   a default would resolve one deployment's names while a caller believed it was
#   addressing another. Every test here therefore sets it explicitly.
_ENVIRONMENT = "dev"


@pytest.fixture(autouse=True)
def environment_name(monkeypatch: pytest.MonkeyPatch) -> None:
    """Set the environment name for the duration of each test and clear the resolver caches.

    Parameters
    ----------
    monkeypatch : pytest.MonkeyPatch
        The fixture used to set the variable and to have it removed afterwards, so no test
        leaves an environment behind for the next one.

    Returns
    -------
    None
        The fixture exists for its effect on the process environment.
    """
    monkeypatch.setenv("CARDDEMO_ENVIRONMENT", _ENVIRONMENT)

    # Assumptions: the resolvers memoise, so a value set after one of them has run
    #   would otherwise be ignored and the test would assert against whatever the first
    #   caller resolved. Clearing is cheap and makes each test independent of order.
    for resolver in (parameter_path, database_secret_name):
        cache_clear = getattr(resolver, "cache_clear", None)
        if cache_clear is not None:
            cache_clear()


def test_parameter_path_is_hierarchical() -> None:
    """Assert a Parameter Store path begins with a separator so it can be fetched by path.

    Raises
    ------
    AssertionError
        If the path is not hierarchical. A flat name cannot be fetched by path at all, and
        the failure would surface as a not-found error rather than as a naming error.
    """
    assert parameter_path("aurora", "host").startswith("/")
    assert parameter_path("aurora", "host") == f"/carddemo/{_ENVIRONMENT}/aurora/host"


# Assumptions: all three connection parameters are pinned, not just one. They are
# produced by a Terraform aws_ssm_parameter resource per name, so each is an independent
# opportunity for the producer and this consumer to disagree; pinning only ``host`` would
# leave two names asserted by nothing. Naming them here makes the set an enforced target for
# whoever authors the producing resources rather than a convention read out of this module.
@pytest.mark.parametrize("setting", ["host", "port", "database"])
def test_every_aurora_connection_parameter_is_pinned(setting: str) -> None:
    """Assert each Aurora connection parameter resolves to its agreed hierarchical path.

    Parameters
    ----------
    setting : str
        One of the three connection settings the loader reads from Parameter Store.

    Raises
    ------
    AssertionError
        If the resolved path differs from the name the infrastructure is required to create.
        A mismatch is silent until a load runs against a provisioned account, where it
        surfaces as a not-found error naming a path no resource ever wrote.
    """
    assert parameter_path("aurora", setting) == f"/carddemo/{_ENVIRONMENT}/aurora/{setting}"


@pytest.mark.parametrize("setting", ["host", "port", "database"])
def test_terraform_produces_every_aurora_connection_parameter(setting: str) -> None:
    """Assert Terraform publishes every connection name consumed by the ETL.

    Parameters
    ----------
    setting : str
        One of the three connection coordinates.

    Raises
    ------
    AssertionError
        If the Aurora module omits the producer resource, the canonical name
        root, or the requested map key.
    """
    assert _AURORA_MAIN_TF.is_file(), f"the Aurora module is missing at {_AURORA_MAIN_TF}"
    terraform = _AURORA_MAIN_TF.read_text(encoding="utf-8")
    assert 'resource "aws_ssm_parameter" "connection"' in terraform
    assert 'parameter_name_root = "${var.parameter_prefix}/${var.environment}/aurora"' in terraform
    parameter_map = re.search(
        r"for_each\s*=\s*\{(?P<body>.*?)\n\s*\}\n\n\s*name\s*="
        r' "\$\{local\.parameter_name_root\}/\$\{each\.key\}"',
        terraform,
        re.DOTALL,
    )
    assert parameter_map is not None, (
        "the Aurora module must build one aws_ssm_parameter per connection map "
        "entry under ${local.parameter_name_root}/${each.key}"
    )
    assert re.search(rf"^\s*{setting}\s*=", parameter_map.group("body"), re.MULTILINE)


@pytest.mark.parametrize("schema", sorted(SCHEMA_NAMES))
def test_secret_name_has_no_leading_separator(schema: str) -> None:
    """Assert a secret name is a valid identifier for the store that holds it.

    Parameters
    ----------
    schema : str
        One of the eight bounded-context schema names.

    Raises
    ------
    AssertionError
        If the name begins with a separator. Secrets Manager rejects such an identifier, so
        every credential lookup would fail while the parameter lookups beside it succeeded --
        which reads as a permissions problem rather than as a naming one.
    """
    name = database_secret_name(schema)
    assert not name.startswith("/"), (
        f"a Secrets Manager identifier must not begin with a separator; {schema} resolved to {name}"
    )
    assert name == f"carddemo/{_ENVIRONMENT}/aurora/{role_for_schema(schema)}"


def test_secret_name_matches_the_terraform_composition() -> None:
    """Assert the module that creates the secrets composes the same name this package reads.

    Raises
    ------
    AssertionError
        If the Terraform module is absent, or if its composed name root differs from the
        root this package resolves. The two artifacts are the producer and the consumer of
        one object, and nothing else in the build compares them.
    """
    assert _SECRETS_MAIN_TF.is_file(), f"the secrets module is missing at {_SECRETS_MAIN_TF}"
    terraform = _SECRETS_MAIN_TF.read_text(encoding="utf-8")

    # Assumptions: the expectation is on the COMPOSITION and not on a resolved value,
    #   because the Terraform carries variables where this package carries resolved text.
    #   Matching the interpolation proves the two agree about the segment order and about the
    #   absence of a leading separator, which is the whole of the contract.
    composition = re.search(
        r'secret_name_root\s*=\s*"\$\{var\.name_prefix\}/\$\{var\.environment\}/aurora"',
        terraform,
    )
    assert composition is not None, (
        "infra/modules/secrets/main.tf must compose the secret name root as "
        '"${var.name_prefix}/${var.environment}/aurora" with no leading separator'
    )

    resolved_root = database_secret_name("ledger").rsplit("/", 1)[0]
    assert resolved_root == f"carddemo/{_ENVIRONMENT}/aurora"


def test_environment_name_has_no_default() -> None:
    """Assert an unset environment name is refused rather than defaulted.

    Raises
    ------
    AssertionError
        If a name resolves with no environment set. A default would silently address one
        deployment's parameters and credentials while the caller believed it had named
        another, which is the one configuration failure that is not self-announcing.
    """
    from carddemo_migration.config import ConfigurationError, resolve_environment_name

    previous = os.environ.pop("CARDDEMO_ENVIRONMENT", None)
    try:
        resolve_environment_name.cache_clear()
        with pytest.raises(ConfigurationError):
            resolve_environment_name()
    finally:
        if previous is not None:
            os.environ["CARDDEMO_ENVIRONMENT"] = previous
        resolve_environment_name.cache_clear()


@pytest.mark.parametrize("role", sorted(LOGIN_ROLE_NAMES))
def test_every_login_role_resolves_a_store_legal_secret_name(role: str) -> None:
    """Assert each of the fifteen login roles composes a secret name the store accepts.

    Parameters
    ----------
    role : str
        One of the fifteen login role names -- eight runtime plus seven migration.

    Raises
    ------
    AssertionError
        If the name begins with a separator, which Secrets Manager rejects outright, or if it
        does not match the composition the Terraform module uses. Either mismatch is silent
        until a load or a service startup runs against a provisioned account, where it
        surfaces as a not-found error naming a secret that was in fact created.
    """
    name = database_secret_name_for_role(role)
    assert not name.startswith("/"), (
        f"a Secrets Manager identifier must not begin with a separator; {role} resolved to {name}"
    )
    assert name == f"carddemo/{_ENVIRONMENT}/aurora/{role}"


def test_terraform_creates_a_secret_for_exactly_the_login_roles() -> None:
    """Assert the secrets module's credential inventory equals the package's login roles.

    Raises
    ------
    AssertionError
        If the module's `service_credential_names` default omits a login role or adds a name
        the package does not know. The two artifacts are the producer and the consumer of one
        set of objects, and a disagreement is invisible until the affected service starts and
        cannot resolve its credential.

    Notes
    -----
    Assumptions: the default list in `variables.tf` is read as text rather than through a
    Terraform plan, for the reason this module's header records -- a plan needs credentials
    this suite must not require, so the check would be skipped exactly where a drift is
    introduced.
    """
    assert _SECRETS_VARIABLES_TF.is_file(), (
        f"the secrets module is missing at {_SECRETS_VARIABLES_TF}"
    )
    terraform = _SECRETS_VARIABLES_TF.read_text(encoding="utf-8")
    declared = set(re.findall(r'"(carddemo_[a-z_]+)"', terraform))
    missing = set(LOGIN_ROLE_NAMES) - declared
    assert not missing, f"infra/modules/secrets does not create a credential for {sorted(missing)}"


@pytest.mark.parametrize("schema", sorted(SCHEMA_NAMES))
def test_no_owner_role_can_be_issued_a_credential(schema: str) -> None:
    """Assert a schema owner has no secret name and no entry in the Terraform inventory.

    Parameters
    ----------
    schema : str
        One of the eight bounded-context schema names.

    Raises
    ------
    AssertionError
        If an owner role composes a secret name, or if the Terraform module would create a
        credential for one. Either would make schema ownership -- and therefore ALTER and
        DROP on every table the context holds -- reachable by presenting a password, which is
        the property the NOLOGIN split exists to remove.
    """
    from carddemo_migration.config import ConfigurationError

    owner = owner_role_for_schema(schema)
    assert owner == f"{role_for_schema(schema)}_owner"
    assert owner not in LOGIN_ROLE_NAMES
    with pytest.raises(ConfigurationError):
        database_secret_name_for_role(owner)

    terraform = _SECRETS_VARIABLES_TF.read_text(encoding="utf-8")
    assert f'"{owner}"' not in terraform, (
        f"infra/modules/secrets must not create a credential for the NOLOGIN owner {owner}"
    )


def test_the_bootstrap_sql_creates_every_role_the_package_names() -> None:
    """Assert V0 declares all fifteen login roles and all eight owner roles.

    Raises
    ------
    AssertionError
        If the bootstrap script is absent, or if it does not name a role this package expects
        it to create. A role the package resolves a credential for but the script never
        creates fails at credential application; a role the script creates but the package
        never applies a credential to fails later and less legibly, at the first
        authentication attempt.
    """
    assert _BOOTSTRAP_SQL.is_file(), f"the bootstrap script is missing at {_BOOTSTRAP_SQL}"
    sql = _BOOTSTRAP_SQL.read_text(encoding="utf-8")
    for role in (*LOGIN_ROLE_NAMES, *OWNED_SCHEMA_ROLES.values()):
        assert role in sql, f"{_BOOTSTRAP_SQL.name} does not name the role {role}"


@pytest.mark.parametrize("schema", sorted(_MIGRATING_SERVICE_BY_SCHEMA))
def test_each_migrating_service_binds_its_migration_credential_and_assumes_the_owner(
    schema: str,
) -> None:
    """Assert a service's Flyway configuration names the migration credential and the owner.

    Parameters
    ----------
    schema : str
        One of the seven bounded-context schemas whose service ships a Flyway migration.

    Raises
    ------
    AssertionError
        If the service's application.yml omits either credential placeholder or the SET ROLE
        statement. Omitting the credential would migrate under the runtime role, which the
        bootstrap SQL leaves without CREATE; omitting the SET ROLE would create every object
        owned by the migration role, which leaves each ALTER DEFAULT PRIVILEGES FOR ROLE
        clause in that script inert and the runtime role with no grant on its own tables.

    Notes
    -----
    Assumptions: the file is matched as text rather than parsed as YAML, so the assertion
    holds regardless of quoting style and needs no YAML dependency in this suite. The three
    tokens are distinctive enough that a substring match cannot pass accidentally.
    """
    service = _MIGRATING_SERVICE_BY_SCHEMA[schema]
    path = _SERVICES_DIR / service / "src" / "main" / "resources" / "application.yml"
    assert path.is_file(), f"{service} has no application.yml at {path}"
    configuration = path.read_text(encoding="utf-8")

    assert "${SPRING_FLYWAY_USER}" in configuration, (
        f"{service} must bind spring.flyway.user to SPRING_FLYWAY_USER so Flyway "
        f"authenticates as {migration_role_for_schema(schema)} rather than as "
        f"{role_for_schema(schema)}"
    )
    assert "${SPRING_FLYWAY_PASSWORD}" in configuration, (
        f"{service} must bind spring.flyway.password to SPRING_FLYWAY_PASSWORD"
    )
    assert f"SET ROLE {owner_role_for_schema(schema)};" in configuration, (
        f"{service} must issue SET ROLE {owner_role_for_schema(schema)} as a Flyway init "
        f"statement, or every object its migration creates is owned by the migration role"
    )


def test_the_migrating_schemas_are_exactly_the_ones_with_a_migration_role() -> None:
    """Assert the package's migration inventory matches the services that ship a migration.

    Raises
    ------
    AssertionError
        If a service owns a db/migration directory but has no migration role, or holds a
        migration role but ships no migration. The first fails at startup with an
        authentication error; the second provisions a credential nothing uses, which is a
        standing grant with no purpose.
    """
    assert set(MIGRATION_SCHEMA_ROLES) == set(_MIGRATING_SERVICE_BY_SCHEMA)
    for schema, service in _MIGRATING_SERVICE_BY_SCHEMA.items():
        migrations = _SERVICES_DIR / service / "src" / "main" / "resources" / "db" / "migration"
        assert migrations.is_dir(), f"{service} holds a migration role but ships no migration"
        assert any(migrations.glob("V*.sql")), f"{service} has an empty migration location"

    unowned = set(SCHEMA_NAMES) - set(MIGRATION_SCHEMA_ROLES)
    assert unowned == {"reporting"}
    reporting_migrations = (
        _SERVICES_DIR / "reporting-service" / "src" / "main" / "resources" / "db" / "migration"
    )
    assert not reporting_migrations.exists(), (
        "reporting-service has no migration role, so it must ship no migration"
    )


@pytest.mark.parametrize("root", _ENVIRONMENT_ROOTS, ids=lambda path: path.name)
def test_each_environment_root_grants_the_migration_task_its_envelope_key(root: Path) -> None:
    """Assert both roots let the migration task draw envelope data keys for the columns it seals.

    Purpose
    -------
    The loader seals three columns before writing them -- ``customers.ssn_encrypted``,
    ``customers.govt_issued_id_encrypted`` and ``cards.cvv_encrypted`` -- by drawing a fresh
    envelope data key per value from the Aurora key. The two key aliases arrive from parameters
    both roots already publish, so a root that publishes the aliases and grants nothing produces a
    load that fails closed on the FIRST card or customer record, as an access denial rather than as
    a configuration error.

    Parameters
    ----------
    root : Path
        The `dev` or `prod` environment root directory.

    Raises
    ------
    AssertionError
        If the migration task's policy document omits the grant, omits either encryption-context
        purpose the loader uses, or grants ``kms:Decrypt`` on the Aurora key -- which the loader
        needs for nothing, because it publishes no decipher path at all.
    """
    main = root / "main.tf"
    assert main.is_file(), f"the {root.name} root is missing at {main}"
    terraform = main.read_text(encoding="utf-8")
    document = terraform.split('data "aws_iam_policy_document" "data_migration_runtime"', 1)
    assert len(document) == 2, (
        f"the {root.name} root declares no data_migration_runtime policy document, so the "
        f"migration task's privileges cannot be asserted"
    )
    # WHY : Assumptions: the document is bounded at the NEXT top-level block rather than read to
    #   the end of the file, because every workload grant in this root lives in the same file and
    #   an unbounded read would let the card workload's own Aurora-key statement satisfy an
    #   assertion about the migration task's. The two are adjacent and near-identical, which is
    #   exactly the confusion this bound removes.
    # WHY : Assumptions: COMMENT LINES ARE STRIPPED before anything is matched, and that is not
    #   tidiness -- it is what makes the assertions below mean what they say. The rationale beside
    #   this grant necessarily quotes both encryption-context values in prose, so a check run over
    #   the raw text was satisfied by the explanation of the grant rather than by the grant, and
    #   passed with a purpose deleted from the condition. This is the second reading of the same
    #   file for the same reason and the strip belongs to both.
    body = "\n".join(
        line
        for line in document[1].split("\n}\n", 1)[0].splitlines()
        if not line.lstrip().startswith("#")
    )

    assert "kms:GenerateDataKey*" in body, (
        f"the {root.name} root must let the migration task draw an envelope data key; without it "
        f"every CARDDATA and CUSTDATA load is refused by KMS on the first record"
    )
    assert "module.kms.aurora_key_arn" in body, (
        f"the {root.name} root must grant that on the Aurora key, which is the key both published "
        f"aliases resolve to"
    )
    # WHY : the condition VARIABLE is composed from the loader's own context key rather than
    #   written out, on the same reasoning as the values below: the whole condition stops matching
    #   if either half is renamed on one side only.
    assert f"kms:EncryptionContext:{CONTEXT_PURPOSE_KEY}" in body, (
        f"the {root.name} root must condition the grant on the encryption-context key the loader "
        f"actually sets"
    )
    # WHY : the condition's value list is parsed and compared as a SET, rather than each value
    #   being searched for anywhere in the document. The two purposes are read from the loader's own
    #   module so a rename there fails this test, and the set comparison is what makes the failure
    #   real in both directions: a missing purpose is a load that fails closed, and an extra one is
    #   a grant over ciphertext this task has no business producing.
    condition_values = re.findall(r"values\s*=\s*\[([^\]]*)\]", body)
    assert condition_values, (
        f"the {root.name} root declares no encryption-context condition on the migration task's "
        f"envelope grant"
    )
    granted_purposes = {
        value.strip().strip('"') for value in condition_values[-1].split(",") if value.strip()
    }
    assert granted_purposes == {CARD_VERIFICATION_VALUE_PURPOSE, CUSTOMER_IDENTIFIER_PURPOSE}, (
        f"the {root.name} root conditions the migration task's envelope grant on "
        f"{sorted(granted_purposes)}, where the loader seals under "
        f"{sorted({CARD_VERIFICATION_VALUE_PURPOSE, CUSTOMER_IDENTIFIER_PURPOSE})}"
    )
    # WHY : the ABSENCE of a decrypt grant is asserted, and it is the one place this task's policy
    #   is deliberately NARROWER than the card and account workloads' equivalent grants. The loader
    #   publishes no decipher member -- a migration writes protected columns and never reads them
    #   back -- so a decrypt grant would widen what a compromised migration task can do with
    #   nothing using it.
    aurora_decrypt_statements = [
        fragment
        for fragment in body.split("statement {")
        if "module.kms.aurora_key_arn" in fragment and "kms:Decrypt" in fragment
    ]
    assert not aurora_decrypt_statements, (
        f"the {root.name} root grants the migration task decrypt on the Aurora key; the loader "
        f"publishes no decipher path, so nothing would use it"
    )


@pytest.mark.parametrize("root", _ENVIRONMENT_ROOTS, ids=lambda path: path.name)
def test_each_environment_root_projects_the_migration_credential(root: Path) -> None:
    """Assert both environment roots inject the migration credential into migrating tasks.

    Parameters
    ----------
    root : Path
        The `dev` or `prod` environment root directory.

    Raises
    ------
    AssertionError
        If the root does not project SPRING_FLYWAY_USER and SPRING_FLYWAY_PASSWORD from the
        `_migrator` secret, or does not exclude reporting from that projection. A root that
        publishes only the runtime credential leaves every migrating service unable to start,
        because the placeholders in its application.yml have no fallback.
    """
    main = root / "main.tf"
    assert main.is_file(), f"the {root.name} root is missing at {main}"
    terraform = main.read_text(encoding="utf-8")

    for name in ("SPRING_FLYWAY_USER", "SPRING_FLYWAY_PASSWORD"):
        assert name in terraform, f"the {root.name} root must project {name}"
    assert '_migrator"].arn' in terraform, (
        f"the {root.name} root must resolve the migration credential from the "
        f"<role>_migrator secret, not from the runtime role's secret"
    )
    assert 'setsubtract(local.database_workload_names, ["reporting"])' in terraform, (
        f"the {root.name} root must exclude reporting from the migration projection, because "
        f"the bootstrap SQL creates no carddemo_reporting_migrator role"
    )


def test_aws_client_is_the_modules_public_client_factory() -> None:
    """Export the client factory, so a sibling module need not reach into a private name.

    WHY : Refactoring Rationale: the factory was ``_aws_client``, and
    ``loaders.s3_stage.s3_client`` -- a PUBLIC function -- called it. A public contract in one
    module therefore rested on a private name in another, excluded from that module's exported
    surface and free to be renamed without any import check noticing. Asserting both the callable
    and its presence in ``__all__`` is what keeps the promotion from being undone by tidying.
    """
    assert callable(config.aws_client)
    assert "aws_client" in config.__all__
    assert not hasattr(config, "_aws_client")
    # WHY : Assumptions: the cache-clearing hook is asserted because
    #   ``reset_resolution_cache`` discards memoised clients by SCANNING this module's globals for
    #   it. A factory without it would be silently exempt from the reset, so a test substituting an
    #   endpoint between cases -- or a process picking up rotated credentials -- would get a fresh
    #   parameter-store client and a stale S3 client from the same call.
    assert hasattr(config.aws_client, "cache_clear")


def test_the_service_error_code_reader_is_public() -> None:
    """Export the service error-code reader, so a sibling module need not reach into a private name.

    WHY : Refactoring Rationale: the reader was ``_error_code``, and
    ``loaders.s3_stage._claim_generation`` -- reached from that module's PUBLIC
    ``reserve_generation`` -- called it to recognise a generation-claim conflict. A public contract
    in one module therefore rested on a private name in another, and the consequence of a rename
    was specific rather than abstract: every concurrent claim conflict would have been re-raised
    as a failure at the one moment two runs met. This is the second promotion of exactly this
    shape in this module, after ``_aws_client``, and it is asserted the same way for the same
    reason.

    WHY : Assumptions: the private spelling is asserted ABSENT rather than left as an alias. Two
    names for one function is how the private one comes back: a later edit reaches for whichever
    it finds, and an alias makes both findable.
    """
    assert callable(config.error_code)
    assert "error_code" in config.__all__
    assert not hasattr(config, "_error_code")
    # WHY : the reader's defensiveness is asserted here rather than only where it is used, because
    #   it runs while another exception is already being handled -- an exception with no response
    #   document must yield a code rather than raise, or the failure an operator needs to see is
    #   replaced by one from the code that was reading it.
    assert config.error_code(RuntimeError("no response document")) == ""
    assert config.error_code(_ServiceError("PreconditionFailed")) == "PreconditionFailed"


class _ServiceError(Exception):
    """Minimal stand-in for an SDK client error carrying a service error code.

    Purpose
    -------
    Give the error-code reader the one attribute shape it navigates, without importing botocore
    into a suite that must run where the SDK is absent.

    Parameters
    ----------
    code : str
        The service error code to carry, as the SDK would place it.

    Raises
    ------
    None
        Construction stores the response document and validates nothing.
    """

    def __init__(self, code: str) -> None:
        """Build an exception carrying one service error code.

        Parameters
        ----------
        code : str
            The service error code to carry.

        Returns
        -------
        None
            Stores the response document.

        Raises
        ------
        None
        """
        super().__init__(code)
        self.response = {"Error": {"Code": code}}


def test_aws_client_refuses_an_environment_that_names_no_region(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Fail fast rather than letting S3 fall back to its default region.

    WHY : Assumptions: the refusal matters for S3 specifically. Every other service raises
    ``NoRegionError`` at construction, but S3 succeeds against ``us-east-1``, so without this
    check a task deployed to another region with its region setting missing wrote every extract
    into the wrong region's namespace and reported success.
    """
    for variable in ("AWS_REGION", "AWS_DEFAULT_REGION", "AWS_PROFILE"):
        monkeypatch.delenv(variable, raising=False)
    monkeypatch.setenv("AWS_CONFIG_FILE", "/nonexistent")
    config.reset_resolution_cache()

    with pytest.raises(config.ConfigurationError, match="names no region"):
        config.aws_client("s3")

    config.reset_resolution_cache()


@pytest.mark.parametrize(
    ("variables", "expected"),
    [
        ({"AWS_REGION": "eu-west-1"}, "eu-west-1"),
        ({"AWS_DEFAULT_REGION": "ap-south-1"}, "ap-south-1"),
        ({"AWS_REGION": "eu-west-1", "AWS_DEFAULT_REGION": "ap-south-1"}, "eu-west-1"),
    ],
    ids=["aws-region-only", "aws-default-region-only", "aws-region-wins"],
)
def test_aws_client_resolves_the_region_from_either_variable(
    variables: dict[str, str], expected: str, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Build a client for the region the environment names, from either variable.

    WHY : Refactoring Rationale: the ``AWS_REGION``-only case is the one that was broken, and it is
    the case this deployment actually runs. Measured on botocore 1.43.50, the session's variable
    mapping for the region is ``AWS_DEFAULT_REGION`` alone, so with ``AWS_REGION`` set and nothing
    else the session answers ``None`` and an S3 client silently resolves to ``us-east-1``. Both
    environment roots set ``AWS_REGION`` -- ``infra/envs/{dev,prod}/main.tf`` -- and ECS supplies it
    to a Fargate task itself, so the staging task read its region from a setting the SDK ignored.

    WHY : Assumptions: the both-variables case asserts ``AWS_REGION`` WINS, which is the precedence
    AWS documents across its SDKs. Consulting the session first would return the lower-precedence
    variable, so an operator's override would be quietly discarded.
    """
    for variable in ("AWS_REGION", "AWS_DEFAULT_REGION", "AWS_PROFILE"):
        monkeypatch.delenv(variable, raising=False)
    monkeypatch.setenv("AWS_CONFIG_FILE", "/nonexistent")
    monkeypatch.setenv("AWS_ACCESS_KEY_ID", "test-access-key")
    monkeypatch.setenv("AWS_SECRET_ACCESS_KEY", "test-secret-key")
    for name, value in variables.items():
        monkeypatch.setenv(name, value)
    config.reset_resolution_cache()

    client = config.aws_client("s3")

    assert client.meta.region_name == expected
    # WHY : Assumptions: the timeout and retry policy are asserted on the SAME client rather than
    #   in a separate case, because the point is that a client this factory returns carries them --
    #   a client built any other way does not, and botocore's own defaults leave the connect
    #   timeout at sixty seconds.
    assert client.meta.config.connect_timeout == config.AWS_CONNECT_TIMEOUT_SECONDS
    assert client.meta.config.read_timeout == config.AWS_READ_TIMEOUT_SECONDS
    # WHY : Assumptions: the assertion reads ``total_max_attempts`` and expects one MORE than the
    #   configured value, because botocore's ``retries.max_attempts`` counts retries rather than
    #   total attempts and it normalises the pair on the built client. Asserting the configured key
    #   instead raises ``KeyError`` on the built client, which is how the off-by-one in the
    #   constant's own documentation was found.
    assert client.meta.config.retries["total_max_attempts"] == config.AWS_MAX_RETRY_ATTEMPTS + 1
    assert client.meta.config.retries["mode"] == config.AWS_RETRY_MODE

    config.reset_resolution_cache()
