"""Assign one Cognito seed user's temporary password without persisting it.

Purpose
-------
Generate a policy-compliant temporary password in process memory, apply it to
one EXISTING Cognito user through an AWS CLI JSON file, and write the same value
to that user's opaque Secrets Manager entry. Terraform passes identifiers and
policy flags only; no credential enters configuration, plan output, state, or
process arguments.

Scope
-----
This script assigns a credential and nothing else. It deliberately does NOT
create a user, does not set or update any user attribute, and has NO delete
path.

WHY : Refactoring Rationale: it previously also created users, converged their
given_name, family_name and custom:user_type, and -- under a ``--delete`` flag
invoked from a Terraform ``when = destroy`` provisioner -- removed the identity
outright. ``infra/modules/cognito/main.tf`` now declares the identity as a native
``aws_cognito_user``, which converges those attributes in place and deletes the
user only when that resource is itself destroyed. Leaving creation and attribute
authority here would duplicate the resource's authority over the same values, and
leaving the delete path here would keep alive the mechanism by which an ordinary
attribute or rotation change destroyed a Cognito identity -- a new ``sub`` and the
loss of the user's chosen password, MFA factors and remembered devices.

WHY : Assumptions: the user is guaranteed to exist. The calling resource takes
the user's ``sub`` as a replacement trigger, so Terraform cannot schedule this
before the identity is created. A genuinely absent user is therefore a real
failure and is reported rather than tolerated -- which is also why no
"not found" tolerance survives in this file.

Returns
-------
None
    The process exits zero once the credential is applied and stored.

Raises
------
BootstrapError
    If required configuration is absent, if the script is given any argument, or
    if AWS CLI rejects an operation.
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


def _run_aws(arguments: list[str]) -> subprocess.CompletedProcess[str]:
    """Run AWS CLI without placing any credential in its arguments.

    Parameters
    ----------
    arguments : list[str]
        Service and operation arguments. Secret-bearing requests reference a
        mode-0600 JSON file rather than embedding a value.

    Returns
    -------
    subprocess.CompletedProcess[str]
        Completed invocation with captured output.

    Raises
    ------
    BootstrapError
        If AWS CLI fails for any reason.
    """

    result = subprocess.run(
        ["aws", *arguments, "--no-cli-pager"],
        check=False,
        capture_output=True,
        text=True,
    )
    if result.returncode == 0:
        return result
    # Trade-offs: there is no tolerated failure, and in particular
    #       UserNotFoundException is NOT tolerated. It was, to let a delete and an
    #       existence probe pass over an absent user; both of those callers are
    #       gone, and the identity is now created by aws_cognito_user before this
    #       script can run. An absent user here therefore means the pool and the
    #       state have diverged, which is worth failing an apply over rather than
    #       silently reporting success for a credential nobody can use.
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


def _assign_credential(region: str, pool_id: str, username: str, secret_arn: str) -> None:
    """Apply one generated temporary password and publish it to Secrets Manager.

    Purpose
    -------
    Reset an existing seed user's password to a freshly generated, policy-compliant
    value that must be changed at first sign-in, and store that same value in the
    user's opaque Secrets Manager entry so an operator can hand it over once.

    Parameters
    ----------
    region : str
        AWS region derived from the user pool identifier.
    pool_id : str
        Identifier of the pool holding the user.
    username : str
        Eight-character SEC-USR-ID of the existing Cognito user.
    secret_arn : str
        ARN of that user's Secrets Manager entry.

    Returns
    -------
    None
        Both the password reset and the secret write have completed.

    Raises
    ------
    BootstrapError
        If the password policy cannot be satisfied or AWS CLI rejects either call.
    OSError
        If a private request document cannot be written.
    """

    password = _temporary_password()

    with tempfile.TemporaryDirectory(prefix="carddemo-seed-user-") as directory:
        root = Path(directory)
        reset_path = root / "reset-password.json"
        secret_path = root / "secret.json"

        # Assumptions: Permanent is false, so Cognito places the user in
        #       FORCE_CHANGE_PASSWORD and the value below is a one-time handover
        #       rather than a working credential. That is what makes storing it
        #       acceptable at all: it buys exactly one sign-in, after which the
        #       stored value is inert and the person's real password has never
        #       existed outside their own session.
        # Assumptions: admin-set-user-password rather than admin-create-user. The
        #       identity already exists -- aws_cognito_user created it -- so a
        #       create call would fail with UsernameExistsException on every run,
        #       and the reset is the operation that is correct both on the first
        #       apply and on every later rotation.
        _write_json(
            reset_path,
            {
                "UserPoolId": pool_id,
                "Username": username,
                "Password": password,
                "Permanent": False,
            },
        )
        # Trade-offs: the username is stored beside the password because the
        #       secret's own name is deliberately opaque, so without it a holder
        #       of the secret cannot tell which identity it opens. The pairing is
        #       already protected by the entry's customer-managed key, and naming
        #       the identity inside the protected payload is strictly better than
        #       naming it in the unprotected resource name.
        _write_json(secret_path, {"username": username, "password": password})

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
    """Assign and publish one seed user's temporary credential from configuration.

    Returns
    -------
    None
        The process exits zero once the credential is applied and stored.

    Raises
    ------
    BootstrapError
        If required configuration is absent, if any argument is supplied, or if
        AWS CLI rejects an operation.
    """

    # Assumptions: the script takes NO arguments. It accepted one flag, --delete,
    #       and rejecting every argument now is deliberate rather than incidental:
    #       an invocation that still passes that flag fails loudly instead of
    #       being read as a request to assign a credential.
    if len(sys.argv) != 1:
        name = Path(sys.argv[0]).name
        raise BootstrapError(
            f"usage: {name} (no arguments; deletion is not this script's function)"
        )

    pool_id = _required("CARDDEMO_USER_POOL_ID")
    username = _required("CARDDEMO_USER_ID")
    # Assumptions: a Cognito pool id is "<region>_<suffix>", so the region is read
    #       from it rather than taken as a separate input that could disagree with
    #       the pool it is meant to describe.
    region = pool_id.split("_", 1)[0]
    _assign_credential(region, pool_id, username, _required("CARDDEMO_SEED_SECRET_ARN"))


if __name__ == "__main__":
    main()
