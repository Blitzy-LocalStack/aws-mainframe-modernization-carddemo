"""Pin the seed-identity subject contract that joins the Cognito pool to ``auth.users``.

Purpose
-------
``auth.users`` declares ``cognito_sub UUID NOT NULL UNIQUE`` and seeds no rows, and the
80-byte ``USRSEC`` record carries no such field, so every row the ETL loads needs a subject
that only the identity provider can mint. The value therefore travels: the Cognito module
publishes it, each environment root writes it to Parameter Store, and
:func:`carddemo_migration.config.resolve_seed_user_subjects` reads it back. Every hop is an
independent chance for producer and consumer to disagree, and a disagreement is silent until
a load runs against a provisioned account -- where it surfaces as a not-found error naming a
path nothing wrote, or as an integrity violation partway through a bulk insert.

WHY : Alternatives Considered: asserting only that the resolver parses a document handed to
it. Rejected because that is the half that cannot break in isolation: the resolver and its own
fixture would agree by construction while the Terraform that has to produce the document went
unchecked. The producing configuration is read from disk here for the same reason
``test_config_name_contract`` reads the Aurora module -- so the target is enforced against
whoever authors the infrastructure rather than restated from the consumer.

WHY : Assumptions: the ten baseline identities are named explicitly rather than counted.
``app/jcl/DUSRSECJ.jcl`` L35-L44 is the roster, and a test that merely counted ten would pass
against ten invented ids -- which is precisely the state this contract was written to end.
"""

from __future__ import annotations

import json
import re
from pathlib import Path

import pytest

from carddemo_migration.config import (
    ConfigurationError,
    parameter_path,
    reset_resolution_cache,
    resolve_seed_user_subjects,
)

_ENVIRONMENT = "dev"

_REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
_COGNITO_OUTPUTS_TF = _REPOSITORY_ROOT / "infra" / "modules" / "cognito" / "outputs.tf"
_COGNITO_MAIN_TF = _REPOSITORY_ROOT / "infra" / "modules" / "cognito" / "main.tf"
_ENVIRONMENT_ROOTS = (
    _REPOSITORY_ROOT / "infra" / "envs" / "dev" / "main.tf",
    _REPOSITORY_ROOT / "infra" / "envs" / "prod" / "main.tf",
)

# Assumptions: transcribed from app/jcl/DUSRSECJ.jcl L35-L44 at the SEC-USR-ID offset of
#   app/cpy/CSUSR01Y.cpy L18. Upper case is the record's own case and is what the ETL will
#   write into auth.users, so the pool has to agree with it rather than title-case it.
_BASELINE_USER_IDS = (
    "ADMIN001",
    "ADMIN002",
    "ADMIN003",
    "ADMIN004",
    "ADMIN005",
    "USER0001",
    "USER0002",
    "USER0003",
    "USER0004",
    "USER0005",
)

_SAMPLE_SUBJECTS = {
    user_id: f"11111111-2222-3333-4444-{index:012d}"
    for index, user_id in enumerate(_BASELINE_USER_IDS, start=1)
}


@pytest.fixture(autouse=True)
def _environment(monkeypatch: pytest.MonkeyPatch) -> None:
    """Pin the environment name and clear every memoised resolution around each case.

    Parameters
    ----------
    monkeypatch : pytest.MonkeyPatch
        Fixture used to set the environment name for the duration of one case.

    Returns
    -------
    None
        The fixture exists for its effect on the process environment and the module caches.
    """
    monkeypatch.setenv("CARDDEMO_ENVIRONMENT", _ENVIRONMENT)
    # Assumptions: cleared both before and after. Before, because a previous case may have
    #   memoised a resolution under a different document; after, because leaving this
    #   module's substitute document cached would let an unrelated test observe it and pass
    #   for the wrong reason.
    reset_resolution_cache()
    yield
    reset_resolution_cache()


def _with_document(monkeypatch: pytest.MonkeyPatch, document: str) -> None:
    """Substitute the Parameter Store read so a case supplies the document under test.

    Parameters
    ----------
    monkeypatch : pytest.MonkeyPatch
        Fixture used to replace the module-private parameter reader.
    document : str
        Exact parameter value the resolver should observe.

    Returns
    -------
    None
        The substitution lasts for the duration of one case.

    Raises
    ------
    AssertionError
        If the resolver asks for any path other than the agreed one, which would mean the
        case proved nothing about the name infrastructure has to create.
    """
    expected = parameter_path("identity", "seed-user-subjects")

    def _read(path: str) -> str:
        """Return the case's document, asserting the requested path first."""
        assert path == expected, f"resolver read {path!r} rather than {expected!r}"
        return document

    monkeypatch.setattr("carddemo_migration.config._ssm_parameter", _read)


def _configuration_only(terraform: str) -> str:
    """Return HCL with every ``#`` comment removed, so an assertion cannot match prose.

    Parameters
    ----------
    terraform : str
        Contents of a ``.tf`` file.

    Returns
    -------
    str
        The same text with each ``#`` comment stripped to end of line.

    Raises
    ------
    None
        Stripping cannot fail; a line with no comment is returned unchanged.
    """
    # Refactoring Rationale: this helper exists because the first version of the
    #   destroy-provisioner assertion below failed against the module's own explanation of
    #   the construct it had just removed. That is not a false alarm to suppress -- an
    #   assertion that reads comments cannot tell a removed mechanism from a described one,
    #   so it would equally have PASSED on a module that merely commented the mechanism out.
    #   Stripping comments is what makes the assertion about configuration.
    # Assumptions: only ``#`` comments are stripped, because that is the only comment form
    #   this module uses, and no string literal in it contains a ``#``. A ``//`` or ``/* */``
    #   comment introduced later would not be stripped, and the assertion would then fail
    #   loudly rather than silently weaken -- the safe direction for this helper to be wrong.
    return "\n".join(line.split("#", 1)[0] for line in terraform.splitlines())


def test_the_subject_path_is_the_one_terraform_writes() -> None:
    """Assert the resolved path matches the composition both environment roots publish.

    Raises
    ------
    AssertionError
        If the consumer's path and the producer's name differ. The mismatch is invisible
        until a load runs against a provisioned account.
    """
    assert (
        parameter_path("identity", "seed-user-subjects")
        == f"/carddemo/{_ENVIRONMENT}/identity/seed-user-subjects"
    )


@pytest.mark.parametrize("root", _ENVIRONMENT_ROOTS, ids=lambda path: path.parent.name)
def test_each_environment_root_publishes_the_subject_document(root: Path) -> None:
    """Assert each root writes the subject map to the platform parameter the ETL reads.

    Parameters
    ----------
    root : pathlib.Path
        ``main.tf`` of one environment root.

    Raises
    ------
    AssertionError
        If the root omits the platform-parameter entry, or publishes something other than
        the Cognito module's ``seed_user_subjects`` output under it.
    """
    assert root.is_file(), f"the environment root is missing at {root}"
    terraform = root.read_text(encoding="utf-8")
    # Assumptions: the key and the value are asserted together on one line rather than
    #   separately. Either alone would pass on a root that published the right name carrying
    #   the wrong value, or the right value under a name nothing reads.
    assert re.search(
        r'"identity/seed-user-subjects"\s*=\s*jsonencode\(module\.cognito\.seed_user_subjects\)',
        terraform,
    ), (
        f"{root.parent.name} must publish "
        '"identity/seed-user-subjects" = jsonencode(module.cognito.seed_user_subjects)'
    )


def test_the_cognito_module_publishes_a_subject_per_user_id() -> None:
    """Assert the module output is keyed by user id and carries the resource's own subject.

    Raises
    ------
    AssertionError
        If the output is absent, is keyed by the opaque secret handle instead of the user
        id, or reads a subject from anything other than the native user resource.
    """
    assert _COGNITO_OUTPUTS_TF.is_file(), f"missing {_COGNITO_OUTPUTS_TF}"
    outputs = _COGNITO_OUTPUTS_TF.read_text(encoding="utf-8")
    assert 'output "seed_user_subjects"' in outputs
    # Assumptions: the comprehension is matched rather than just the output name, because
    #   the key is the whole point -- the two secret outputs beside it are deliberately keyed
    #   by an opaque handle, and a subject map keyed that way would answer nothing the ETL
    #   asks.
    assert re.search(
        r"for user_id, user in aws_cognito_user\.seed_user\s*:\s*\n\s*user_id => user\.sub",
        outputs,
    ), "seed_user_subjects must map each user id to aws_cognito_user.seed_user[...].sub"


def test_the_identity_is_a_native_resource_with_no_delete_on_update_path() -> None:
    """Assert the seed identity is a native Cognito user, not a replace-on-change stand-in.

    Raises
    ------
    AssertionError
        If the identity is carried by a ``terraform_data`` whose replacement triggers
        include a profile field, or if any destroy-time provisioner survives in the module.
        Either would mean an ordinary attribute change destroys the identity and mints a new
        subject, invalidating every ``cognito_sub`` this contract has already written.
    """
    assert _COGNITO_MAIN_TF.is_file(), f"missing {_COGNITO_MAIN_TF}"
    terraform = _configuration_only(_COGNITO_MAIN_TF.read_text(encoding="utf-8"))
    assert 'resource "aws_cognito_user" "seed_user"' in terraform
    assert 'resource "terraform_data" "seed_user"' not in terraform, (
        "the identity must not be carried by a terraform_data: it cannot update in place, "
        "so every attribute change would destroy and recreate the Cognito identity"
    )
    # Assumptions: the credential resource is allowed to be replaced -- that is what a
    #   rotation is -- so what is asserted is the absence of a DESTROY-TIME provisioner
    #   anywhere in the module, since that is the only thing that deleted the identity.
    assert "when = destroy" not in terraform, (
        "no destroy-time provisioner may remain in the Cognito module"
    )
    assert "--delete" not in terraform


def test_every_baseline_identity_is_seeded_by_both_roots() -> None:
    """Assert both roots seed exactly the ten baseline user ids and no invented one.

    Raises
    ------
    AssertionError
        If a root omits a baseline id or seeds one that ``USRSEC`` does not contain. An
        invented id has no ``auth.users`` row to join, and an omitted one leaves a loaded
        row with no subject for a column declared ``NOT NULL``.
    """
    for root in _ENVIRONMENT_ROOTS:
        terraform = root.read_text(encoding="utf-8")
        block = re.search(r"seed_users = \[(?P<body>.*?)\n  \]", terraform, re.DOTALL)
        assert block is not None, f"{root.parent.name} declares no seed_users list"
        seeded = re.findall(r'user_id\s*=\s*"([^"]+)"', block.group("body"))
        assert sorted(seeded) == sorted(_BASELINE_USER_IDS), (
            f"{root.parent.name} must seed exactly the ten identities of "
            f"app/jcl/DUSRSECJ.jcl L35-L44; found {sorted(seeded)}"
        )


def test_a_well_formed_document_resolves_every_baseline_identity(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Assert the resolver returns one canonical subject per baseline id.

    Parameters
    ----------
    monkeypatch : pytest.MonkeyPatch
        Fixture used to substitute the parameter read.

    Raises
    ------
    AssertionError
        If any id is dropped or any subject is altered in transit.
    """
    _with_document(monkeypatch, json.dumps(_SAMPLE_SUBJECTS))
    resolved = resolve_seed_user_subjects()
    assert dict(resolved) == _SAMPLE_SUBJECTS
    assert sorted(resolved) == sorted(_BASELINE_USER_IDS)


def test_the_resolved_mapping_cannot_be_mutated(monkeypatch: pytest.MonkeyPatch) -> None:
    """Assert the memoised mapping is read-only so one caller cannot corrupt another's view.

    Parameters
    ----------
    monkeypatch : pytest.MonkeyPatch
        Fixture used to substitute the parameter read.

    Raises
    ------
    AssertionError
        If the returned mapping accepts a write. The value is cached, so a mutation would
        be observed by every later caller in the process.
    """
    _with_document(monkeypatch, json.dumps(_SAMPLE_SUBJECTS))
    resolved = resolve_seed_user_subjects()
    with pytest.raises(TypeError):
        resolved["ADMIN001"] = "00000000-0000-0000-0000-000000000000"  # type: ignore[index]


def test_an_empty_document_is_accepted(monkeypatch: pytest.MonkeyPatch) -> None:
    """Assert a pool provisioned with no seed identities resolves to an empty mapping.

    Parameters
    ----------
    monkeypatch : pytest.MonkeyPatch
        Fixture used to substitute the parameter read.

    Raises
    ------
    AssertionError
        If an empty document is refused. The module defaults ``seed_users`` to an empty
        list, so an empty map is a legitimate provisioned state rather than a fault, and
        refusing it would make a valid deployment unloadable.
    """
    _with_document(monkeypatch, "{}")
    assert dict(resolve_seed_user_subjects()) == {}


@pytest.mark.parametrize(
    ("document", "reason"),
    [
        ("not json at all", "not valid JSON"),
        ('["ADMIN001"]', "must be a JSON object"),
        ('{"ADMIN001": 1}', "must be a string"),
        ('{"ADMIN01": "11111111-2222-3333-4444-555555555555"}', "is not 8 characters"),
        ('{"ADMIN0001": "11111111-2222-3333-4444-555555555555"}', "is not 8 characters"),
        ('{"ADMIN001": "not-a-uuid"}', "canonical RFC-4122"),
        ('{"ADMIN001": "111111112222333344445555555555555"}', "canonical RFC-4122"),
        (
            '{"ADMIN001": "11111111-2222-3333-4444-555555555555",'
            ' "ADMIN002": "11111111-2222-3333-4444-555555555555"}',
            "declares UNIQUE",
        ),
    ],
    ids=[
        "malformed-json",
        "json-array",
        "non-string-subject",
        "short-user-id",
        "long-user-id",
        "non-uuid-subject",
        "unhyphenated-subject",
        "duplicate-subject",
    ],
)
def test_a_malformed_document_is_refused(
    monkeypatch: pytest.MonkeyPatch, document: str, reason: str
) -> None:
    """Assert each way the document can be wrong is refused before any row is written.

    Parameters
    ----------
    monkeypatch : pytest.MonkeyPatch
        Fixture used to substitute the parameter read.
    document : str
        A malformed parameter value.
    reason : str
        Fragment the raised message must contain, so a case cannot pass on the wrong
        rejection.

    Raises
    ------
    AssertionError
        If the document is accepted, or is rejected for a different reason than the one the
        case is about.
    """
    _with_document(monkeypatch, document)
    with pytest.raises(ConfigurationError, match=reason):
        resolve_seed_user_subjects()


def test_the_rejection_message_withholds_the_document(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Assert a decode failure does not quote the document back into the exception.

    Parameters
    ----------
    monkeypatch : pytest.MonkeyPatch
        Fixture used to substitute the parameter read.

    Raises
    ------
    AssertionError
        If a user id or a subject appears in the message. The document pairs every id with
        its subject, and an exception string is the least controlled place for that pairing
        to travel; the decoder's position is enough to locate the fault.
    """
    trailing_comma = json.dumps(_SAMPLE_SUBJECTS)[:-1] + ","
    _with_document(monkeypatch, trailing_comma)
    with pytest.raises(ConfigurationError) as raised:
        resolve_seed_user_subjects()
    rendered = str(raised.value)
    for user_id, subject in _SAMPLE_SUBJECTS.items():
        assert user_id not in rendered
        assert subject not in rendered
