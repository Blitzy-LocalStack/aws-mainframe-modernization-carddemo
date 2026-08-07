"""Run CardDemo database bootstrap and maintenance through the RDS Data API.

Purpose
-------
Provide two narrowly-scoped operations without packaging a PostgreSQL driver:

``bootstrap``
    Read every service role's credential from Secrets Manager, publish each as a
    bound-parameter session setting, and then execute
    ``V0__schemas_and_roles.sql`` transactionally so the script's own
    credential-application section can apply them.
``analyze``
    Refresh PostgreSQL planner statistics after the nightly batch chain.

The bootstrap SQL is packaged beside this module. Its outer ``BEGIN`` and
``COMMIT`` statements are omitted because the Data API transaction is the
authoritative boundary; dollar-quoted ``DO`` blocks remain intact.

Why the credentials are injected here
-------------------------------------
``V0__schemas_and_roles.sql`` states a caller contract it cannot enforce for
itself: before the script is sent, in the SAME session, the caller must set one
session setting per login role -- ``carddemo.credential.<role>`` -- to that
role's stored credential, passing the value as a BOUND PARAMETER. The script's
final section applies each one with ``ALTER ROLE`` inside a ``DO`` block and then
FAILS CLOSED, rolling the whole transaction back if any role would be left
unable to authenticate.

This module previously satisfied none of that: it read only the master secret and
set nothing, so a fresh apply rolled the bootstrap back. The ordering was
inverted as well -- the invocation ran BEFORE the module that creates the
credential entries, so there was nothing to read even in principle. Both are
corrected: the calling root now orders credential creation first and passes the
inventory below, and each value is published as a bound parameter before the
first statement of the script executes.

WHY : Assumptions: session settings survive from one ``ExecuteStatement`` to the
next because every call carries the same ``transactionId``, and the Data API pins
one database session for the life of a transaction. Outside a transaction the
service is free to use a different connection per call, so the same sequence
issued without one would set a value on a session the script never sees. This is
the reason the credential loop is INSIDE the existing transaction rather than a
preparatory step before it.

WHY : Alternatives Considered: interpolating each credential into the statement
text, which needs no parameter support at all. Rejected on the same grounds
``V0__schemas_and_roles.sql`` records: a value in statement text reaches
``log_statement``, ``pg_stat_activity`` and any error message quoting the failing
statement. Also considered was setting ``carddemo.bootstrap_allow_missing_
credentials`` so the script tolerates absent values. Rejected outright: it turns
a bootstrap that cannot succeed into one that reports success while leaving every
service unable to authenticate.

Environment
-----------
``DB_CLUSTER_ARN``
    Aurora cluster resource ARN.
``DB_MASTER_SECRET_ARN``
    RDS-managed master-user secret ARN.
``DB_NAME``
    Target database name.
``DB_CREDENTIAL_SECRETS``
    JSON object mapping each login role name to the NAME of the Secrets Manager
    entry holding its credential -- for example
    ``{"carddemo_auth": "carddemo/dev/aurora/carddemo_auth"}``. Required by
    ``bootstrap`` and unused by ``analyze``. The mapping is supplied whole rather
    than composed from a prefix here, so the naming convention stays owned by
    ``infra/modules/secrets`` and this module cannot address an entry that module
    never created.
``BOOTSTRAP_SQL_FILE``
    Optional packaged filename, defaulting to
    ``V0__schemas_and_roles.sql``.

Returns
-------
dict
    Operation name and executed statement count.

Raises
------
RuntimeError
    If required configuration or packaged SQL is unavailable.
ValueError
    If the requested action is unsupported or the SQL is malformed.
botocore.exceptions.BotoCoreError
    If a Data API operation fails.
"""

from __future__ import annotations

import json
import logging
import os
import re
from pathlib import Path
from typing import Any

import boto3

LOGGER = logging.getLogger(__name__)
LOGGER.setLevel(logging.INFO)


def _required_environment(name: str) -> str:
    """Resolve a required environment variable.

    Parameters
    ----------
    name:
        Variable name.

    Returns
    -------
    str
        Trimmed value.

    Raises
    ------
    RuntimeError
        If the value is absent or blank.
    """

    value = os.environ.get(name, "").strip()
    if not value:
        raise RuntimeError(f"Missing required environment variable {name}")
    return value


CLUSTER_ARN = _required_environment("DB_CLUSTER_ARN")
MASTER_SECRET_ARN = _required_environment("DB_MASTER_SECRET_ARN")
DATABASE_NAME = _required_environment("DB_NAME")
BOOTSTRAP_SQL_FILE = os.environ.get(
    "BOOTSTRAP_SQL_FILE", "V0__schemas_and_roles.sql"
).strip()
SQL_PATH = Path(__file__).with_name(BOOTSTRAP_SQL_FILE)
RDS_DATA = boto3.client("rds-data")
SECRETS = boto3.client("secretsmanager")

# WHY : Assumptions: this is read with a plain ``os.environ.get`` rather than through
#       ``_required_environment``, and the asymmetry with the three above is
#       deliberate. Module-level resolution runs on EVERY invocation including
#       ``analyze``, which needs no credential at all, so requiring it here would make
#       the nightly maintenance state fail on configuration it does not use.
#       ``_bootstrap`` requires it at the point of use instead.
CREDENTIAL_SECRETS_RAW = os.environ.get("DB_CREDENTIAL_SECRETS", "").strip()

# WHY : Assumptions: the setting name is composed from a THREE-segment form,
#       ``carddemo.credential.<role>``, which PostgreSQL accepts even though its
#       documentation describes custom options as two segments. Verified against
#       PostgreSQL 17.10 by replaying the bootstrap script, which sets and reads back
#       every one of the fifteen names.
_CREDENTIAL_SETTING_PREFIX = "carddemo.credential."

# WHY : Assumptions: the credential document is the shape ``infra/modules/secrets``
#       writes and ``carddemo_migration.config`` reads -- a JSON object with
#       ``username`` and ``password`` members. Only the password is used here, because
#       the role name is already known: it is the key this loop is iterating, and the
#       script applies the credential to that role by name. Reading the username and
#       comparing it would duplicate a check the ETL already owns and would add a
#       failure mode this step cannot act on.
_SECRET_PASSWORD_KEY = "password"

_DOLLAR_TAG = re.compile(r"\$[A-Za-z_][A-Za-z0-9_]*\$|\$\$")


def _split_sql(script: str) -> list[str]:
    """Split PostgreSQL SQL without breaking quoted or dollar-quoted bodies.

    Parameters
    ----------
    script:
        Complete SQL script.

    Returns
    -------
    list[str]
        Non-empty statements without trailing semicolons.

    Raises
    ------
    ValueError
        If a quote, dollar-quoted body, or block comment is unterminated.
    """

    statements: list[str] = []
    current: list[str] = []
    index = 0
    single_quote = False
    double_quote = False
    line_comment = False
    block_comment_depth = 0
    dollar_tag: str | None = None

    while index < len(script):
        char = script[index]
        following = script[index + 1] if index + 1 < len(script) else ""

        if line_comment:
            current.append(char)
            if char == "\n":
                line_comment = False
            index += 1
            continue

        if block_comment_depth:
            current.append(char)
            if char == "/" and following == "*":
                current.append(following)
                block_comment_depth += 1
                index += 2
            elif char == "*" and following == "/":
                current.append(following)
                block_comment_depth -= 1
                index += 2
            else:
                index += 1
            continue

        if dollar_tag is not None:
            if script.startswith(dollar_tag, index):
                current.append(dollar_tag)
                index += len(dollar_tag)
                dollar_tag = None
            else:
                current.append(char)
                index += 1
            continue

        if single_quote:
            current.append(char)
            if char == "'" and following == "'":
                current.append(following)
                index += 2
            else:
                if char == "'":
                    single_quote = False
                index += 1
            continue

        if double_quote:
            current.append(char)
            if char == '"' and following == '"':
                current.append(following)
                index += 2
            else:
                if char == '"':
                    double_quote = False
                index += 1
            continue

        if char == "-" and following == "-":
            current.extend((char, following))
            line_comment = True
            index += 2
            continue
        if char == "/" and following == "*":
            current.extend((char, following))
            block_comment_depth = 1
            index += 2
            continue
        if char == "'":
            current.append(char)
            single_quote = True
            index += 1
            continue
        if char == '"':
            current.append(char)
            double_quote = True
            index += 1
            continue
        if char == "$":
            match = _DOLLAR_TAG.match(script, index)
            if match:
                dollar_tag = match.group(0)
                current.append(dollar_tag)
                index = match.end()
                continue
        if char == ";":
            statement = "".join(current).strip()
            if statement:
                statements.append(statement)
            current.clear()
            index += 1
            continue

        current.append(char)
        index += 1

    if single_quote or double_quote or dollar_tag is not None or block_comment_depth:
        raise ValueError("Bootstrap SQL contains an unterminated quoted construct")
    trailing = "".join(current).strip()
    if trailing:
        statements.append(trailing)
    return statements


def _statement_keyword(statement: str) -> str:
    """Return the first executable SQL keyword, ignoring leading comments.

    Parameters
    ----------
    statement:
        SQL statement, possibly beginning with comments.

    Returns
    -------
    str
        Upper-case first keyword, or an empty string for comment-only text.

    Raises
    ------
    None
        The function is a lexical classification and does not parse SQL.
    """

    without_block = re.sub(r"/\*.*?\*/", " ", statement, flags=re.DOTALL)
    without_lines = re.sub(r"(?m)^\s*--.*$", " ", without_block)
    match = re.search(r"[A-Za-z]+", without_lines)
    return match.group(0).upper() if match else ""


def _credential_secrets() -> dict[str, str]:
    """Return the role-to-secret-name mapping whose credentials must reach the session.

    Returns
    -------
    dict of str to str
        Login role name to the name of the Secrets Manager entry holding its credential.

    Raises
    ------
    RuntimeError
        If the mapping is unset, is not valid JSON, is not a JSON object, is empty, or
        carries an entry whose role name or secret name is not non-empty text.

    Notes
    -----
    Assumptions: an empty mapping is refused rather than treated as "no credentials to
    publish". A caller that supplies none has misconfigured this function, and accepting
    it would send the bootstrap script into a section that raises for every role -- so
    the failure would name fifteen roles instead of the one variable that was empty.

    Assumptions: each value is validated as text before use because it becomes a
    ``SecretId``. A non-string would surface as a client-side type error from botocore
    naming an argument rather than the configuration that produced it.
    """

    if not CREDENTIAL_SECRETS_RAW:
        raise RuntimeError(
            "Missing required environment variable DB_CREDENTIAL_SECRETS; the "
            "bootstrap action must read one credential per login role"
        )
    try:
        mapping = json.loads(CREDENTIAL_SECRETS_RAW)
    except json.JSONDecodeError as failure:
        raise RuntimeError(
            "DB_CREDENTIAL_SECRETS is not valid JSON; a role-to-secret-name object "
            "is expected"
        ) from failure
    if not isinstance(mapping, dict) or not mapping:
        raise RuntimeError(
            "DB_CREDENTIAL_SECRETS must be a non-empty JSON object mapping each login "
            "role name to the name of the secret holding its credential"
        )
    for role, secret_name in mapping.items():
        if not isinstance(role, str) or not role.strip():
            raise RuntimeError("DB_CREDENTIAL_SECRETS contains a blank role name")
        if not isinstance(secret_name, str) or not secret_name.strip():
            raise RuntimeError(
                f"DB_CREDENTIAL_SECRETS names no secret for the role {role}"
            )
    return {role.strip(): secret_name.strip() for role, secret_name in mapping.items()}


def _role_credential(secret_id: str) -> str:
    """Read one login role's stored credential from Secrets Manager.

    Parameters
    ----------
    secret_id:
        Name of the Secrets Manager entry holding the credential, as supplied by
        :func:`_credential_secrets`.

    Returns
    -------
    str
        The credential held in that entry.

    Raises
    ------
    RuntimeError
        If the entry holds no text, is not a JSON object, or carries no non-empty
        ``password`` member.
    botocore.exceptions.ClientError
        If the entry cannot be read.

    Notes
    -----
    Assumptions: no failure message here quotes any part of the resolved document. The
    secret NAME is named because it is auditable and is what an operator must inspect;
    the value is not, and a message that echoed a malformed payload would put a
    credential in the log group this function writes to.
    """

    payload = SECRETS.get_secret_value(SecretId=secret_id).get("SecretString")
    if not payload:
        raise RuntimeError(
            f"The credential entry {secret_id} holds no text; a JSON document with a "
            f"password member is expected"
        )
    try:
        document = json.loads(payload)
    except json.JSONDecodeError as failure:
        raise RuntimeError(
            f"The credential entry {secret_id} is not valid JSON"
        ) from failure
    if not isinstance(document, dict):
        raise RuntimeError(
            f"The credential entry {secret_id} is not a JSON object"
        )
    credential = document.get(_SECRET_PASSWORD_KEY)
    if not isinstance(credential, str) or not credential:
        raise RuntimeError(
            f"The credential entry {secret_id} carries no non-empty "
            f"{_SECRET_PASSWORD_KEY} member"
        )
    return credential


def _publish_credentials(transaction_id: str, secrets: dict[str, str]) -> int:
    """Publish one bound-parameter session setting per role inside an open transaction.

    Parameters
    ----------
    transaction_id:
        Identifier of the open Data API transaction whose session must carry the
        settings. Passing it is what keeps every statement on one database session.
    secrets:
        Login role name to secret name, as returned by :func:`_credential_secrets`.

    Returns
    -------
    int
        Number of settings published, which equals ``len(secrets)``.

    Raises
    ------
    RuntimeError
        Propagated from :func:`_role_credential` when an entry is unreadable or
        malformed.
    botocore.exceptions.BotoCoreError
        If the Data API rejects a statement.

    Notes
    -----
    Assumptions: both the setting NAME and its VALUE are bound parameters, not
    interpolated text. The value has to be bound for the reason the bootstrap script
    records -- statement text reaches ``log_statement`` and ``pg_stat_activity`` -- and
    the name is bound for a second reason: a role name arriving from configuration and
    concatenated into SQL would be an injection point in the one function whose whole
    purpose is establishing trust.

    Assumptions: ``is_local`` is ``false``, so each setting outlives the statement that
    set it and is still readable when the script's final section runs. It does not
    outlive the transaction: a rollback restores the prior value, and the script clears
    each setting itself as soon as it has applied it.
    """

    for role in sorted(secrets):
        RDS_DATA.execute_statement(
            resourceArn=CLUSTER_ARN,
            secretArn=MASTER_SECRET_ARN,
            database=DATABASE_NAME,
            transactionId=transaction_id,
            sql="SELECT set_config(:setting_name, :setting_value, false)",
            parameters=[
                {
                    "name": "setting_name",
                    "value": {"stringValue": f"{_CREDENTIAL_SETTING_PREFIX}{role}"},
                },
                {
                    "name": "setting_value",
                    "value": {"stringValue": _role_credential(secrets[role])},
                },
            ],
        )
    return len(secrets)


def _bootstrap() -> tuple[int, int]:
    """Publish every service credential, then execute the packaged bootstrap.

    Returns
    -------
    tuple of int
        The number of executable SQL statements sent to Aurora and the number of
        credentials published to the session, in that order.

    Raises
    ------
    RuntimeError
        If the packaged SQL file is missing, the credential inventory is unset, or an
        entry is unreadable or malformed.
    botocore.exceptions.BotoCoreError
        If the Data API cannot begin, execute, commit, or roll back.

    Notes
    -----
    Assumptions: the credentials are published INSIDE the transaction and BEFORE the
    first statement of the script, because the script's final section reads them with
    ``current_setting`` and raises for any role it finds no value for. Publishing them
    after the statements, or in a separate transaction, would leave that section reading
    an empty session and rolling the whole bootstrap back.

    Assumptions: a failure to read one entry aborts before any statement runs, because
    the inventory is resolved and the settings published first. That ordering makes the
    common misconfiguration -- a missing or unreadable entry -- fail without having
    created or altered anything.
    """

    if not SQL_PATH.is_file():
        raise RuntimeError(f"Bootstrap SQL file is missing: {SQL_PATH.name}")
    secrets = _credential_secrets()
    statements = [
        statement
        for statement in _split_sql(SQL_PATH.read_text(encoding="utf-8"))
        if _statement_keyword(statement) not in {"", "BEGIN", "COMMIT"}
    ]
    transaction_id = RDS_DATA.begin_transaction(
        resourceArn=CLUSTER_ARN,
        secretArn=MASTER_SECRET_ARN,
        database=DATABASE_NAME,
    )["transactionId"]
    try:
        credential_count = _publish_credentials(transaction_id, secrets)
        for statement in statements:
            RDS_DATA.execute_statement(
                resourceArn=CLUSTER_ARN,
                secretArn=MASTER_SECRET_ARN,
                database=DATABASE_NAME,
                transactionId=transaction_id,
                sql=statement,
                continueAfterTimeout=True,
            )
        RDS_DATA.commit_transaction(
            resourceArn=CLUSTER_ARN,
            secretArn=MASTER_SECRET_ARN,
            transactionId=transaction_id,
        )
    except Exception:
        RDS_DATA.rollback_transaction(
            resourceArn=CLUSTER_ARN,
            secretArn=MASTER_SECRET_ARN,
            transactionId=transaction_id,
        )
        raise
    return len(statements), credential_count


def _analyze() -> int:
    """Run PostgreSQL ANALYZE outside a Data API transaction.

    Returns
    -------
    int
        One, the number of statements executed.

    Raises
    ------
    botocore.exceptions.BotoCoreError
        If Aurora rejects the maintenance statement.
    """

    # Alternatives Considered: VACUUM ANALYZE, which is the usual
    #       maintenance pairing. The Data API wraps a statement in a transaction
    #       context and PostgreSQL refuses VACUUM there; ANALYZE alone refreshes
    #       the planner statistics this batch state is responsible for.
    RDS_DATA.execute_statement(
        resourceArn=CLUSTER_ARN,
        secretArn=MASTER_SECRET_ARN,
        database=DATABASE_NAME,
        sql="ANALYZE",
        continueAfterTimeout=True,
    )
    return 1


def handler(event: dict[str, Any], context: Any) -> dict[str, Any]:
    """Dispatch a database administration operation.

    Parameters
    ----------
    event:
        Payload containing ``action`` equal to ``bootstrap`` or ``analyze``.
    context:
        Lambda context, unused because platform logs already record invocation
        metadata.

    Returns
    -------
    dict
        Action, executed statement count, and -- for ``bootstrap`` -- the number of
        credentials published to the bootstrap session. ``analyze`` reports zero.

    Raises
    ------
    ValueError
        If the action is unsupported.
    RuntimeError
        If bootstrap assets or environment configuration are unavailable.
    botocore.exceptions.BotoCoreError
        If the Data API operation fails.

    Notes
    -----
    Assumptions: the response and the log line report COUNTS and never a role name or a
    credential. A count is enough to tell a fifteen-credential bootstrap from an
    eight-credential one, which is the question an operator reading an apply transcript
    actually has; a role list would put the credential inventory in Terraform state,
    because ``aws_lambda_invocation`` stores the function's response there.
    """

    del context
    action = str(event.get("action", "")).strip()
    if action == "bootstrap":
        statement_count, credential_count = _bootstrap()
    elif action == "analyze":
        statement_count, credential_count = _analyze(), 0
    else:
        raise ValueError("action must be bootstrap or analyze")

    LOGGER.info(
        "event=database_admin_completed action=%s statements=%d credentials=%d execution=%s",
        action,
        statement_count,
        credential_count,
        str(event.get("executionName", "")).strip() or "terraform",
    )
    return {
        "action": action,
        "statementCount": statement_count,
        "credentialCount": credential_count,
    }
