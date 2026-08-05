"""Run CardDemo database bootstrap and maintenance through the RDS Data API.

Purpose
-------
Provide two narrowly-scoped operations without packaging a PostgreSQL driver:

``bootstrap``
    Execute ``V0__schemas_and_roles.sql`` transactionally before any service
    credential is rotated.
``analyze``
    Refresh PostgreSQL planner statistics after the nightly batch chain.

The bootstrap SQL is packaged beside this module. Its outer ``BEGIN`` and
``COMMIT`` statements are omitted because the Data API transaction is the
authoritative boundary; dollar-quoted ``DO`` blocks remain intact.

Environment
-----------
``DB_CLUSTER_ARN``
    Aurora cluster resource ARN.
``DB_MASTER_SECRET_ARN``
    RDS-managed master-user secret ARN.
``DB_NAME``
    Target database name.
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


def _bootstrap() -> int:
    """Execute the packaged schema-and-role bootstrap transactionally.

    Returns
    -------
    int
        Number of executable statements sent to Aurora.

    Raises
    ------
    RuntimeError
        If the packaged SQL file is missing.
    botocore.exceptions.BotoCoreError
        If the Data API cannot begin, execute, commit, or roll back.
    """

    if not SQL_PATH.is_file():
        raise RuntimeError(f"Bootstrap SQL file is missing: {SQL_PATH.name}")
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
    return len(statements)


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
        Action and executed statement count.

    Raises
    ------
    ValueError
        If the action is unsupported.
    RuntimeError
        If bootstrap assets or environment configuration are unavailable.
    botocore.exceptions.BotoCoreError
        If the Data API operation fails.
    """

    del context
    action = str(event.get("action", "")).strip()
    if action == "bootstrap":
        statement_count = _bootstrap()
    elif action == "analyze":
        statement_count = _analyze()
    else:
        raise ValueError("action must be bootstrap or analyze")

    LOGGER.info(
        "event=database_admin_completed action=%s statements=%d execution=%s",
        action,
        statement_count,
        str(event.get("executionName", "")).strip() or "terraform",
    )
    return {"action": action, "statementCount": statement_count}
