"""Rotate CardDemo database credentials through the Aurora RDS Data API.

Purpose
-------
Implement the four-step AWS Secrets Manager rotation contract for the eight
CardDemo service database roles. The function uses the RDS-managed master
secret only through the Data API, creates a bounded ``_clone`` login for
alternating-user rotation, and applies the pending password without exposing it
to Terraform state, process arguments, shell history, or application logs.

Parameters
----------
event : dict[str, object]
    Secrets Manager rotation event containing ``SecretId``,
    ``ClientRequestToken``, and one of the four standard ``Step`` values.
context : object
    Lambda invocation context. The function does not inspect it.

Returns
-------
None
    A successful step returns after its idempotent state transition completes.

Raises
------
RotationError
    If the event, secret document, role mapping, database state, or version
    stages violate the closed rotation contract.
botocore.exceptions.BotoCoreError
    If an AWS API call fails before the service can return a response.
botocore.exceptions.ClientError
    If Secrets Manager or RDS Data API rejects a request.

The function deliberately depends only on ``boto3``, which the Lambda Python
runtime supplies. A native PostgreSQL driver would require a platform-specific
wheel in every deployment package; Data API keeps the package reproducible and
lets IAM scope every database operation to one Aurora cluster and one
RDS-managed master secret.
"""

from __future__ import annotations

import json
import logging
import os
import re
from collections.abc import Mapping
from typing import Any, Final

import boto3

_LOGGER = logging.getLogger(__name__)
_LOGGER.setLevel(os.environ.get("LOG_LEVEL", "INFO"))

_ROLE_PATTERN: Final[re.Pattern[str]] = re.compile(r"^[a-z][a-z0-9_]{0,62}$")
_PASSWORD_PATTERN: Final[re.Pattern[str]] = re.compile(r"^[A-Za-z0-9]{32,128}$")
_CLONE_SUFFIX: Final[str] = "_clone"
_REQUIRED_SECRET_FIELDS: Final[frozenset[str]] = frozenset(
    {"engine", "host", "username", "password", "dbname", "port", "masterarn"}
)
_SUPPORTED_ENGINE: Final[str] = "aurora-postgresql"
_ROTATION_STEPS: Final[frozenset[str]] = frozenset(
    {"createSecret", "setSecret", "testSecret", "finishSecret"}
)


class RotationError(RuntimeError):
    """Report a stable rotation-contract failure without retaining secret data."""


def _required_environment(name: str) -> str:
    """Return one required non-secret Lambda environment value.

    Parameters
    ----------
    name : str
        Environment-variable name to read.

    Returns
    -------
    str
        Non-empty value with surrounding whitespace removed.

    Raises
    ------
    RotationError
        If the variable is absent or blank.
    """

    value = os.environ.get(name, "").strip()
    if not value:
        raise RotationError(f"required rotation configuration {name} is absent")
    return value


_CLUSTER_ARN: Final[str] = _required_environment("AURORA_CLUSTER_ARN")
_MASTER_SECRET_ARN: Final[str] = _required_environment("AURORA_MASTER_SECRET_ARN")
_DATABASE_HOST: Final[str] = _required_environment("AURORA_HOST")
_DATABASE_NAME: Final[str] = _required_environment("AURORA_DATABASE")
_DATABASE_PORT: Final[int] = int(_required_environment("AURORA_PORT"))


def _load_role_map() -> Mapping[str, str]:
    """Load the exact secret-ARN to owning-role mapping.

    Parameters
    ----------
    None
        The JSON document comes from ``SECRET_ROLE_MAP``.

    Returns
    -------
    Mapping[str, str]
        Validated mapping containing exactly the service secrets this function
        may rotate.

    Raises
    ------
    RotationError
        If the document is malformed, empty, or contains an invalid ARN or role.
    """

    raw = _required_environment("SECRET_ROLE_MAP")
    try:
        parsed = json.loads(raw)
    except json.JSONDecodeError as error:
        raise RotationError("SECRET_ROLE_MAP is not valid JSON") from error
    if not isinstance(parsed, dict) or not parsed:
        raise RotationError("SECRET_ROLE_MAP must be a non-empty JSON object")

    result: dict[str, str] = {}
    for secret_arn, role_name in parsed.items():
        if (
            not isinstance(secret_arn, str)
            or ":secretsmanager:" not in secret_arn
            or not isinstance(role_name, str)
            or _ROLE_PATTERN.fullmatch(role_name) is None
            or role_name.endswith(_CLONE_SUFFIX)
        ):
            raise RotationError("SECRET_ROLE_MAP contains an invalid entry")
        result[secret_arn] = role_name
    return result


_SECRET_ROLE_MAP: Final[Mapping[str, str]] = _load_role_map()
_SECRETS = boto3.client("secretsmanager")
_RDS_DATA = boto3.client("rds-data")


def _secret_document(secret_id: str, stage: str, token: str | None = None) -> dict[str, Any]:
    """Read and validate one credential document without logging its values.

    Parameters
    ----------
    secret_id : str
        Exact Secrets Manager ARN from the closed role map.
    stage : str
        Version stage to read, normally ``AWSCURRENT`` or ``AWSPENDING``.
    token : str | None
        Version identifier required when reading the pending stage.

    Returns
    -------
    dict[str, Any]
        Validated credential document.

    Raises
    ------
    RotationError
        If the value is binary, malformed JSON, incomplete, or points outside
        the configured cluster and master-secret boundary.
    """

    request: dict[str, str] = {"SecretId": secret_id, "VersionStage": stage}
    if token is not None:
        request["VersionId"] = token
    response = _SECRETS.get_secret_value(**request)
    raw = response.get("SecretString")
    if not isinstance(raw, str):
        raise RotationError("database credential must be a JSON text secret")
    try:
        document = json.loads(raw)
    except json.JSONDecodeError as error:
        raise RotationError("database credential is not valid JSON") from error
    if not isinstance(document, dict) or not _REQUIRED_SECRET_FIELDS.issubset(document):
        raise RotationError("database credential does not match the required schema")

    expected_role = _SECRET_ROLE_MAP[secret_id]
    username = document.get("username")
    if username not in {expected_role, f"{expected_role}{_CLONE_SUFFIX}"}:
        raise RotationError("database credential names a role outside its rotation pair")
    password = document.get("password")
    if not isinstance(password, str) or _PASSWORD_PATTERN.fullmatch(password) is None:
        raise RotationError("database credential password does not match the bounded grammar")
    if (
        document.get("engine") != _SUPPORTED_ENGINE
        or document.get("host") != _DATABASE_HOST
        or document.get("dbname") != _DATABASE_NAME
        or document.get("port") != _DATABASE_PORT
        or document.get("masterarn") != _MASTER_SECRET_ARN
    ):
        raise RotationError("database credential points outside the configured Aurora boundary")
    return document


def _alternate_username(username: str) -> str:
    """Return the other member of one bounded alternating-user pair.

    Parameters
    ----------
    username : str
        Current base or ``_clone`` role name.

    Returns
    -------
    str
        Clone when given the base role, or base role when given the clone.

    Raises
    ------
    RotationError
        If appending the suffix would exceed PostgreSQL's identifier limit.
    """

    if username.endswith(_CLONE_SUFFIX):
        return username[: -len(_CLONE_SUFFIX)]
    alternate = f"{username}{_CLONE_SUFFIX}"
    if len(alternate) > 63:
        raise RotationError("alternate database role would exceed 63 characters")
    return alternate


def _sql_identifier(value: str) -> str:
    """Quote one already validated PostgreSQL role identifier.

    Parameters
    ----------
    value : str
        Role name from the closed role map.

    Returns
    -------
    str
        Double-quoted PostgreSQL identifier.

    Raises
    ------
    RotationError
        If the value is not a plain lower-case role identifier.
    """

    if _ROLE_PATTERN.fullmatch(value) is None:
        raise RotationError("database role identifier is invalid")
    return f'"{value}"'


def _require_password(value: str) -> str:
    """Validate and return one generated alphanumeric password.

    Parameters
    ----------
    value : str
        Pending password generated by Secrets Manager.

    Returns
    -------
    str
        The original password after validation.

    Raises
    ------
    RotationError
        If the value falls outside the bounded alphanumeric grammar.
    """

    if _PASSWORD_PATTERN.fullmatch(value) is None:
        raise RotationError("pending password does not match the bounded grammar")
    return value


def _data_parameter(name: str, value: str) -> dict[str, object]:
    """Build one string-valued RDS Data API parameter.

    Parameters
    ----------
    name : str
        SQL parameter name.
    value : str
        Non-secret value to bind.

    Returns
    -------
    dict[str, object]
        Data API parameter shape.

    Raises
    ------
    None
        The helper performs no I/O and accepts values already validated by its
        callers.
    """

    return {"name": name, "value": {"stringValue": value}}


def _execute(
    sql: str,
    *,
    parameters: list[dict[str, object]] | None = None,
    transaction_id: str | None = None,
) -> dict[str, Any]:
    """Execute one statement as the RDS-managed master user.

    Parameters
    ----------
    sql : str
        Statement text. Callers never log it.
    parameters : list[dict[str, object]] | None
        Optional Data API bind parameters.
    transaction_id : str | None
        Existing transaction identifier for a mutating rotation step.

    Returns
    -------
    dict[str, Any]
        Raw Data API response.

    Raises
    ------
    botocore.exceptions.ClientError
        If Aurora rejects the statement or credentials.
    """

    request: dict[str, Any] = {
        "resourceArn": _CLUSTER_ARN,
        "secretArn": _MASTER_SECRET_ARN,
        "database": _DATABASE_NAME,
        "sql": sql,
        "includeResultMetadata": True,
    }
    if parameters:
        request["parameters"] = parameters
    if transaction_id:
        request["transactionId"] = transaction_id
    return _RDS_DATA.execute_statement(**request)


def _role_state(role_name: str, transaction_id: str | None = None) -> dict[str, bool] | None:
    """Return security-relevant attributes for one PostgreSQL role.

    Parameters
    ----------
    role_name : str
        Role to inspect.
    transaction_id : str | None
        Rotation transaction when called from ``setSecret``.

    Returns
    -------
    dict[str, bool] | None
        Attribute mapping, or ``None`` when the role does not yet exist.

    Raises
    ------
    RotationError
        If Aurora returns an unexpected record shape.
    """

    response = _execute(
        """
        SELECT rolcanlogin, rolsuper, rolcreatedb, rolcreaterole,
               rolreplication, rolbypassrls, rolpassword IS NOT NULL
          FROM pg_catalog.pg_authid
         WHERE rolname = :role_name
        """,
        parameters=[_data_parameter("role_name", role_name)],
        transaction_id=transaction_id,
    )
    records = response.get("records", [])
    if not records:
        return None
    if len(records) != 1 or len(records[0]) != 7:
        raise RotationError("database role inspection returned an unexpected shape")
    names = (
        "can_login",
        "is_superuser",
        "can_create_database",
        "can_create_role",
        "can_replicate",
        "bypasses_rls",
        "has_password",
    )
    try:
        return {name: bool(cell["booleanValue"]) for name, cell in zip(names, records[0], strict=True)}
    except (KeyError, TypeError) as error:
        raise RotationError("database role inspection returned a non-boolean attribute") from error


def _require_safe_role(role_name: str, state: Mapping[str, bool]) -> None:
    """Refuse a login role whose attributes exceed the bounded-context contract.

    Parameters
    ----------
    role_name : str
        Role being checked; used only in a non-sensitive diagnostic.
    state : Mapping[str, bool]
        Attributes returned by :func:`_role_state`.

    Returns
    -------
    None
        Returns when the role is LOGIN and holds no administrative attribute.

    Raises
    ------
    RotationError
        If the role cannot log in or holds an administrative capability.
    """

    forbidden = (
        state["is_superuser"]
        or state["can_create_database"]
        or state["can_create_role"]
        or state["can_replicate"]
        or state["bypasses_rls"]
    )
    if not state["can_login"] or forbidden:
        raise RotationError(f"database role {role_name} exceeds the service-role contract")


def _begin_transaction() -> str:
    """Begin one Data API transaction for a password and membership change.

    Returns
    -------
    str
        Transaction identifier.

    Raises
    ------
    botocore.exceptions.ClientError
        If Aurora cannot begin the transaction.
    """

    response = _RDS_DATA.begin_transaction(
        resourceArn=_CLUSTER_ARN,
        secretArn=_MASTER_SECRET_ARN,
        database=_DATABASE_NAME,
    )
    transaction_id = response.get("transactionId")
    if not isinstance(transaction_id, str) or not transaction_id:
        raise RotationError("RDS Data API returned no transaction identifier")
    return transaction_id


def _install_password_helper(transaction_id: str) -> None:
    """Install a transaction-local helper that keeps passwords out of SQL text.

    Parameters
    ----------
    transaction_id : str
        Data API transaction identifier.

    Returns
    -------
    None
        The helper exists only in the transaction's temporary schema.

    Raises
    ------
    botocore.exceptions.ClientError
        If Aurora refuses the temporary function.
    """

    # Alternatives Considered: interpolating the generated password into
    #       ALTER ROLE text. PostgreSQL utility statements do not accept a bind
    #       parameter in the PASSWORD clause, so direct interpolation would put
    #       the value in the Data API SQL field and potentially in database
    #       statement diagnostics. A pg_temp function receives both values as
    #       ordinary SELECT parameters and performs the unavoidable quoting
    #       inside the server; it disappears with the transaction session.
    _execute(
        """
        CREATE OR REPLACE FUNCTION pg_temp.carddemo_apply_service_password(
            role_name text,
            password_value text
        ) RETURNS void
        LANGUAGE plpgsql
        AS $rotation$
        BEGIN
            IF role_name !~ '^[a-z][a-z0-9_]{0,62}$' THEN
                RAISE EXCEPTION 'invalid service role name';
            END IF;
            IF password_value !~ '^[A-Za-z0-9]{32,128}$' THEN
                RAISE EXCEPTION 'invalid service password grammar';
            END IF;
            IF EXISTS (
                SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = role_name
            ) THEN
                EXECUTE format(
                    'ALTER ROLE %I LOGIN PASSWORD %L',
                    role_name,
                    password_value
                );
            ELSE
                EXECUTE format(
                    'CREATE ROLE %I LOGIN NOSUPERUSER NOCREATEDB '
                    'NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD %L',
                    role_name,
                    password_value
                );
            END IF;
        END
        $rotation$
        """,
        transaction_id=transaction_id,
    )


def _apply_role_password(role_name: str, password: str, transaction_id: str) -> None:
    """Apply one validated password through the transaction-local helper.

    Parameters
    ----------
    role_name : str
        Base or clone role from the closed role map.
    password : str
        Pending generated credential.
    transaction_id : str
        Data API transaction identifier.

    Returns
    -------
    None
        Returns after Aurora creates or updates the role.

    Raises
    ------
    RotationError
        If the role or password grammar is invalid.
    botocore.exceptions.ClientError
        If Aurora rejects the helper invocation.
    """

    if _ROLE_PATTERN.fullmatch(role_name) is None:
        raise RotationError("database role identifier is invalid")
    _execute(
        "SELECT pg_temp.carddemo_apply_service_password(:role_name, :password_value)",
        parameters=[
            _data_parameter("role_name", role_name),
            _data_parameter("password_value", _require_password(password)),
        ],
        transaction_id=transaction_id,
    )


def _create_secret(secret_id: str, token: str) -> None:
    """Create the pending alternating-user credential when it does not exist."""

    current = _secret_document(secret_id, "AWSCURRENT")
    try:
        _secret_document(secret_id, "AWSPENDING", token)
        return
    except _SECRETS.exceptions.ResourceNotFoundException:
        pass

    pending = dict(current)
    pending["username"] = _alternate_username(str(current["username"]))
    password_response = _SECRETS.get_random_password(
        PasswordLength=32,
        ExcludePunctuation=True,
        RequireEachIncludedType=True,
    )
    password = password_response.get("RandomPassword")
    if not isinstance(password, str) or _PASSWORD_PATTERN.fullmatch(password) is None:
        raise RotationError("Secrets Manager returned a password outside the bounded grammar")
    pending["password"] = password
    _SECRETS.put_secret_value(
        SecretId=secret_id,
        ClientRequestToken=token,
        SecretString=json.dumps(pending, separators=(",", ":")),
        VersionStages=["AWSPENDING"],
    )


def _set_secret(secret_id: str, token: str) -> None:
    """Apply the pending password and alternating-role membership atomically."""

    current = _secret_document(secret_id, "AWSCURRENT")
    pending = _secret_document(secret_id, "AWSPENDING", token)
    expected_role = _SECRET_ROLE_MAP[secret_id]
    if pending["username"] != _alternate_username(str(current["username"])):
        raise RotationError("pending credential is not the expected alternating role")

    base_role = _sql_identifier(expected_role)
    pending_name = str(pending["username"])
    pending_role = _sql_identifier(pending_name)
    pending_password = _require_password(str(pending["password"]))
    transaction_id = _begin_transaction()
    try:
        _install_password_helper(transaction_id)
        base_state = _role_state(expected_role, transaction_id)
        if base_state is None:
            _apply_role_password(expected_role, pending_password, transaction_id)
        else:
            _require_safe_role(expected_role, base_state)
            # Refactoring Rationale: V0 deliberately creates LOGIN roles
            #       with no password. During the first rotation the current
            #       secret therefore cannot authenticate even though its role
            #       exists. Applying the pending value to that passwordless base
            #       role closes the bootstrap gap; subsequent rotations leave a
            #       password-bearing current role untouched until it becomes the
            #       pending member of the alternating pair.
            if not base_state["has_password"] and current["username"] == expected_role:
                _apply_role_password(expected_role, pending_password, transaction_id)

        pending_state = _role_state(pending_name, transaction_id)
        if pending_state is None:
            _apply_role_password(pending_name, pending_password, transaction_id)
        else:
            _require_safe_role(pending_name, pending_state)
            _apply_role_password(pending_name, pending_password, transaction_id)

        if pending_name != expected_role:
            _execute(
                f"GRANT {base_role} TO {pending_role} WITH INHERIT TRUE, SET TRUE",
                transaction_id=transaction_id,
            )
        _RDS_DATA.commit_transaction(
            resourceArn=_CLUSTER_ARN,
            secretArn=_MASTER_SECRET_ARN,
            transactionId=transaction_id,
        )
    except Exception:
        try:
            _RDS_DATA.rollback_transaction(
                resourceArn=_CLUSTER_ARN,
                secretArn=_MASTER_SECRET_ARN,
                transactionId=transaction_id,
            )
        except Exception:
            _LOGGER.exception("database credential rotation rollback failed")
        raise


def _test_secret(secret_id: str, token: str) -> None:
    """Verify the pending role attributes and inherited owning-role authority."""

    pending = _secret_document(secret_id, "AWSPENDING", token)
    pending_name = str(pending["username"])
    expected_role = _SECRET_ROLE_MAP[secret_id]
    state = _role_state(pending_name)
    if state is None:
        raise RotationError("pending database role does not exist")
    _require_safe_role(pending_name, state)
    if pending_name == expected_role:
        return

    response = _execute(
        "SELECT pg_has_role(:member_name, :owner_name, 'USAGE')",
        parameters=[
            _data_parameter("member_name", pending_name),
            _data_parameter("owner_name", expected_role),
        ],
    )
    try:
        has_membership = bool(response["records"][0][0]["booleanValue"])
    except (KeyError, IndexError, TypeError) as error:
        raise RotationError("database membership test returned an unexpected shape") from error
    if not has_membership:
        raise RotationError("pending clone does not inherit its owning database role")


def _finish_secret(secret_id: str, token: str) -> None:
    """Move ``AWSCURRENT`` to the tested pending version idempotently."""

    metadata = _SECRETS.describe_secret(SecretId=secret_id)
    versions = metadata.get("VersionIdsToStages", {})
    for version_id, stages in versions.items():
        if version_id == token and "AWSCURRENT" in stages:
            return
    current_version = next(
        (version_id for version_id, stages in versions.items() if "AWSCURRENT" in stages),
        None,
    )
    if current_version is None:
        raise RotationError("secret has no AWSCURRENT version to replace")
    _SECRETS.update_secret_version_stage(
        SecretId=secret_id,
        VersionStage="AWSCURRENT",
        MoveToVersionId=token,
        RemoveFromVersionId=current_version,
    )


def lambda_handler(event: Mapping[str, object], context: object) -> None:
    """Execute one idempotent Secrets Manager rotation step.

    Parameters
    ----------
    event : Mapping[str, object]
        Standard rotation event.
    context : object
        Lambda context, intentionally unused.

    Returns
    -------
    None
        Returns after the requested step succeeds.

    Raises
    ------
    RotationError
        If the request is outside the configured role and version boundary.
    """

    del context
    raw_secret_id = event.get("SecretId")
    token = event.get("ClientRequestToken")
    step = event.get("Step")
    if not isinstance(raw_secret_id, str) or not isinstance(token, str) or step not in _ROTATION_STEPS:
        raise RotationError("rotation event does not match the required contract")

    metadata = _SECRETS.describe_secret(SecretId=raw_secret_id)
    secret_id = metadata.get("ARN")
    if not isinstance(secret_id, str) or secret_id not in _SECRET_ROLE_MAP:
        raise RotationError("rotation request names a secret outside the configured role map")
    if not metadata.get("RotationEnabled"):
        raise RotationError("rotation is not enabled for the requested secret")
    stages = metadata.get("VersionIdsToStages", {}).get(token)
    if not isinstance(stages, list):
        raise RotationError("rotation token is not a version of the requested secret")
    if "AWSCURRENT" in stages:
        return
    if "AWSPENDING" not in stages:
        raise RotationError("rotation token is not staged as AWSPENDING")

    handlers = {
        "createSecret": _create_secret,
        "setSecret": _set_secret,
        "testSecret": _test_secret,
        "finishSecret": _finish_secret,
    }
    handlers[str(step)](secret_id, token)
    _LOGGER.info("database credential rotation step completed for role %s", _SECRET_ROLE_MAP[secret_id])
