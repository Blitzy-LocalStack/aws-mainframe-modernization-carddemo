"""Toggle the CardDemo online-write gate used around the nightly batch chain.

Purpose
-------
Update one Systems Manager Parameter Store value to quiesce or resume online
writes. Separate Lambda functions use this same handler with different
``EXPECTED_ACTION`` and ``TARGET_VALUE`` environment settings, which keeps the
state-machine ARNs distinct while single-sourcing the mutation logic.

Environment
-----------
``PARAMETER_NAME``
    Absolute SSM parameter name holding ``true`` or ``false``.
``EXPECTED_ACTION``
    Either ``quiesce`` or ``resume``; mismatched events are rejected.
``TARGET_VALUE``
    Either ``true`` or ``false``; the value written with overwrite enabled.

Returns
-------
dict
    The action, resulting boolean state, SSM version, and execution name.

Raises
------
RuntimeError
    If required environment configuration is missing or malformed.
ValueError
    If the Step Functions payload does not match this function's configured
    action.
botocore.exceptions.BotoCoreError
    If the SSM write cannot be completed.
"""

from __future__ import annotations

import logging
import os
from typing import Any

import boto3

LOGGER = logging.getLogger(__name__)
LOGGER.setLevel(logging.INFO)


def _required_environment(name: str) -> str:
    """Return a non-blank environment value.

    Parameters
    ----------
    name:
        Environment variable to resolve.

    Returns
    -------
    str
        The trimmed value.

    Raises
    ------
    RuntimeError
        If the variable is absent or blank.
    """

    value = os.environ.get(name, "").strip()
    if not value:
        raise RuntimeError(f"Missing required environment variable {name}")
    return value


PARAMETER_NAME = _required_environment("PARAMETER_NAME")
EXPECTED_ACTION = _required_environment("EXPECTED_ACTION")
TARGET_VALUE = _required_environment("TARGET_VALUE").lower()

if EXPECTED_ACTION not in {"quiesce", "resume"}:
    raise RuntimeError("EXPECTED_ACTION must be quiesce or resume")
if TARGET_VALUE not in {"true", "false"}:
    raise RuntimeError("TARGET_VALUE must be true or false")
if not PARAMETER_NAME.startswith("/"):
    raise RuntimeError("PARAMETER_NAME must be an absolute SSM path")

SSM = boto3.client("ssm")


def handler(event: dict[str, Any], context: Any) -> dict[str, Any]:
    """Apply the configured online-write state.

    Parameters
    ----------
    event:
        Step Functions payload containing ``action`` and optionally
        ``executionName``.
    context:
        Lambda invocation context. It is intentionally not logged because its
        request metadata is already available in platform logs.

    Returns
    -------
    dict
        Stable result metadata suitable for a Step Functions ``ResultPath``.

    Raises
    ------
    ValueError
        If the event action is missing or targets the other function.
    botocore.exceptions.BotoCoreError
        If Parameter Store rejects the update.
    """

    del context
    action = str(event.get("action", "")).strip()
    if action != EXPECTED_ACTION:
        raise ValueError(
            f"Expected action {EXPECTED_ACTION!r}, received {action or '<missing>'!r}"
        )

    response = SSM.put_parameter(
        Name=PARAMETER_NAME,
        Value=TARGET_VALUE,
        Type="String",
        Overwrite=True,
    )

    execution_name = str(event.get("executionName", "")).strip()
    LOGGER.info(
        "event=online_write_gate_updated action=%s enabled=%s parameter=%s execution=%s",
        action,
        TARGET_VALUE,
        PARAMETER_NAME,
        execution_name or "unknown",
    )
    return {
        "action": action,
        "onlineWritesEnabled": TARGET_VALUE == "true",
        "parameterVersion": response["Version"],
        "executionName": execution_name,
    }
