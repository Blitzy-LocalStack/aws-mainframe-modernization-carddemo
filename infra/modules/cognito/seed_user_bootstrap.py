"""Create or remove one Cognito seed user without persisting its password.

Purpose
-------
Generate a policy-compliant temporary password in process memory, apply it to
one Cognito user through AWS CLI JSON files, and write the same value to the
user's opaque Secrets Manager entry. Terraform passes identifiers and policy
flags only; no credential enters configuration, plan output, state, or process
arguments.

Parameters
----------
--delete : command-line flag, optional
    Delete the configured user instead of creating or converging it.

Returns
-------
None
    The process exits zero after the requested state is reached.

Raises
------
BootstrapError
    If required configuration is absent or AWS CLI rejects an operation.
"""

from __future__ import annotations

import json
import os
import secrets
import string
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import Final

_DELETE_FLAG: Final[str] = "--delete"


class BootstrapError(RuntimeError):
    """Report a stable bootstrap failure without retaining credential data."""


def _required(name: str) -> str:
    """Return one required environment value.

    Parameters
    ----------
    name : str
        Environment variable to read.

    Returns
    -------
    str
        Non-empty value.

    Raises
    ------
    BootstrapError
        If the value is absent or blank.
    """

    value = os.environ.get(name, "")
    if not value:
        raise BootstrapError(f"required seed-user configuration {name} is absent")
    return value


def _flag(name: str) -> bool:
    """Parse one required lower-case boolean environment value."""

    value = _required(name)
    if value not in {"true", "false"}:
        raise BootstrapError(f"seed-user configuration {name} must be true or false")
    return value == "true"


def _run_aws(arguments: list[str], *, allow_not_found: bool = False) -> subprocess.CompletedProcess[str]:
    """Run AWS CLI without placing any credential in its arguments.

    Parameters
    ----------
    arguments : list[str]
        Service and operation arguments. Secret-bearing requests reference a
        mode-0600 JSON file rather than embedding a value.
    allow_not_found : bool
        Accept Cognito's UserNotFoundException during delete or existence tests.

    Returns
    -------
    subprocess.CompletedProcess[str]
        Completed invocation with captured output.

    Raises
    ------
    BootstrapError
        If AWS CLI fails for any reason other than the permitted absent user.
    """

    result = subprocess.run(
        ["aws", *arguments, "--no-cli-pager"],
        check=False,
        capture_output=True,
        text=True,
    )
    if result.returncode == 0:
        return result
    if allow_not_found and "UserNotFoundException" in result.stderr:
        return result
    # Assumptions: the command arguments carry file paths and identifiers
    #       only, while stderr may include service-generated context. Returning
    #       the final line gives an operator the AWS error class without
    #       serialising the request document or process environment.
    detail = result.stderr.strip().splitlines()[-1] if result.stderr.strip() else "AWS CLI failed"
    raise BootstrapError(detail)


def _temporary_password() -> str:
    """Generate a password satisfying the configured Cognito class policy."""

    length = int(_required("CARDDEMO_PASSWORD_LENGTH"))
    if length < 12 or length > 128:
        raise BootstrapError("CARDDEMO_PASSWORD_LENGTH is outside 12 through 128")

    classes: list[str] = []
    if _flag("CARDDEMO_REQUIRE_LOWERCASE"):
        classes.append(string.ascii_lowercase)
    if _flag("CARDDEMO_REQUIRE_UPPERCASE"):
        classes.append(string.ascii_uppercase)
    if _flag("CARDDEMO_REQUIRE_NUMBERS"):
        classes.append(string.digits)
    if _flag("CARDDEMO_REQUIRE_SYMBOLS"):
        classes.append(_required("CARDDEMO_PASSWORD_SYMBOLS"))
    if not classes:
        classes.append(string.ascii_letters + string.digits)

    if len(classes) > length:
        raise BootstrapError("password length is smaller than the required character classes")
    password = [secrets.choice(character_class) for character_class in classes]
    alphabet = "".join(classes)
    password.extend(secrets.choice(alphabet) for _ in range(length - len(password)))
    secrets.SystemRandom().shuffle(password)
    return "".join(password)


def _write_json(path: Path, payload: object) -> None:
    """Write one private JSON request document.

    Parameters
    ----------
    path : pathlib.Path
        File inside a private temporary directory.
    payload : object
        JSON-serialisable AWS request.

    Returns
    -------
    None
        The file is created with owner-only permissions.

    Raises
    ------
    OSError
        If the private request document cannot be written.
    """

    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
        json.dump(payload, stream, separators=(",", ":"))


def _delete_user(region: str, pool_id: str, username: str) -> None:
    """Delete one seed user idempotently."""

    _run_aws(
        [
            "cognito-idp",
            "admin-delete-user",
            "--region",
            region,
            "--user-pool-id",
            pool_id,
            "--username",
            username,
        ],
        allow_not_found=True,
    )


def _converge_user(region: str, pool_id: str, username: str, secret_arn: str) -> None:
    """Create or reset one seed user and persist its temporary handover value."""

    password = _temporary_password()
    attributes = [
        {"Name": "given_name", "Value": _required("CARDDEMO_GIVEN_NAME")},
        {"Name": "family_name", "Value": _required("CARDDEMO_FAMILY_NAME")},
        {"Name": "custom:user_type", "Value": _required("CARDDEMO_USER_TYPE")},
    ]

    with tempfile.TemporaryDirectory(prefix="carddemo-seed-user-") as directory:
        root = Path(directory)
        create_path = root / "create-user.json"
        reset_path = root / "reset-password.json"
        attributes_path = root / "update-attributes.json"
        secret_path = root / "secret.json"
        _write_json(
            create_path,
            {
                "UserPoolId": pool_id,
                "Username": username,
                "TemporaryPassword": password,
                "UserAttributes": attributes,
                "MessageAction": "SUPPRESS",
            },
        )
        _write_json(
            reset_path,
            {
                "UserPoolId": pool_id,
                "Username": username,
                "Password": password,
                "Permanent": False,
            },
        )
        _write_json(
            attributes_path,
            {
                "UserPoolId": pool_id,
                "Username": username,
                "UserAttributes": attributes,
            },
        )
        _write_json(secret_path, {"username": username, "password": password})

        exists = (
            _run_aws(
                [
                    "cognito-idp",
                    "admin-get-user",
                    "--region",
                    region,
                    "--user-pool-id",
                    pool_id,
                    "--username",
                    username,
                ],
                allow_not_found=True,
            ).returncode
            == 0
        )
        if exists:
            _run_aws(
                [
                    "cognito-idp",
                    "admin-set-user-password",
                    "--region",
                    region,
                    "--cli-input-json",
                    f"file://{reset_path}",
                ]
            )
            _run_aws(
                [
                    "cognito-idp",
                    "admin-update-user-attributes",
                    "--region",
                    region,
                    "--cli-input-json",
                    f"file://{attributes_path}",
                ]
            )
        else:
            _run_aws(
                [
                    "cognito-idp",
                    "admin-create-user",
                    "--region",
                    region,
                    "--cli-input-json",
                    f"file://{create_path}",
                ]
            )

        _run_aws(
            [
                "secretsmanager",
                "put-secret-value",
                "--region",
                region,
                "--secret-id",
                secret_arn,
                "--secret-string",
                f"file://{secret_path}",
            ]
        )


def main() -> None:
    """Execute create/converge or delete from process configuration."""

    pool_id = _required("CARDDEMO_USER_POOL_ID")
    username = _required("CARDDEMO_USER_ID")
    region = pool_id.split("_", 1)[0]
    if len(sys.argv) == 2 and sys.argv[1] == _DELETE_FLAG:
        _delete_user(region, pool_id, username)
        return
    if len(sys.argv) != 1:
        raise BootstrapError(f"usage: {Path(sys.argv[0]).name} [{_DELETE_FLAG}]")
    _converge_user(region, pool_id, username, _required("CARDDEMO_SEED_SECRET_ARN"))


if __name__ == "__main__":
    main()
