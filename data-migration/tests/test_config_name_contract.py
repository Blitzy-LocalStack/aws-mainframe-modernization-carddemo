"""Verify that the names this package resolves are the names the infrastructure creates.

Purpose
-------
Assert the producer-consumer contract between ``carddemo_migration.config`` and
``infra/modules/secrets/main.tf``: the extract-transform-load package reads a
credential from Secrets Manager and three settings from Parameter Store, and the
Terraform module creates them. If the two spell one object differently, nothing fails
until a load runs against a provisioned account, and the failure is a not-found error
that names a path nobody wrote.

WHY (Alternatives Considered)
    Asserting against a live account, or against a Terraform plan, was considered and
    rejected. Both need credentials this suite must not require, so the check would be
    skipped exactly where a drift is introduced -- in an ordinary edit to either file.
    The naming rule is short enough to state twice and is stated once in each artifact,
    so a test that reads the Terraform text and compares it against what this package
    resolves closes the loop without leaving the workstation.

WHY (Assumptions)
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
    SCHEMA_NAMES,
    database_secret_name,
    parameter_path,
    role_for_schema,
)

# Assumptions: the Terraform module is located relative to this file rather than
#   to the working directory, so the expectation holds however the suite is invoked.
_SECRETS_MAIN_TF = Path(__file__).resolve().parents[2] / "infra" / "modules" / "secrets" / "main.tf"
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
