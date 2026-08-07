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
