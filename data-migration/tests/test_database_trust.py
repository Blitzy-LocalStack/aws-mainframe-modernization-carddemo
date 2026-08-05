"""Verify the two controls that decide whom this package trusts to reach the database.

Purpose
-------
Assert the behaviour of the trust-anchor resolution and the alternate-user privilege
adjudication in ``carddemo_migration.config``. Both are controls whose failure mode is
silent: a substituted certificate authority still yields a successful ``verify-full``
connection, and an over-privileged alternate role still yields a successful load. Neither
raises anything at the point of use, so the only place the behaviour can be pinned down is
a test that asserts the refusals explicitly.

WHY (Alternatives Considered)
    Asserting the accept path against a real file staged under ``tmp_path`` was tried
    first and abandoned. The container this suite runs in has ``/tmp`` at mode 2777 --
    world-writable WITHOUT the sticky bit -- so every ancestor walk over a real temporary
    directory legitimately refuses, and the accept assertions failed for a reason that had
    nothing to do with the code under test. A test that passes or fails on the host's
    ``/tmp`` mode asserts the host, not the module. The accept path is therefore driven
    through a substituted filesystem, while every refusal path uses real files, because
    refusals are independent of the host's mode.

WHY (Assumptions)
    The privilege adjudicator is tested without a database, because it is deliberately
    separated from the query that feeds it: ``alternate_database_user_verification_sql``
    returns text and ``require_equivalent_database_user`` judges a row. That split is what
    makes the rule assertable here; the query itself is exercised against a live cluster by
    ``data-migration/sql/verify/alternate_database_users.sql``.

WHY (Trade-offs)
    The tests assert on refusal MESSAGES as well as on the fact of refusal, but only on the
    stable part of each -- the named attribute, the variable name, the word describing the
    defect. Asserting whole messages would make every wording improvement a test failure;
    asserting nothing about them would let a refusal degrade into one that does not tell an
    operator which file or which role to repair, which is the difference between a control
    that can be acted on and one that cannot.
"""

from __future__ import annotations

import os
import stat
from types import SimpleNamespace

import pytest

from carddemo_migration.config import (
    DEFAULT_SSL_ROOT_CERT,
    ENV_ALTERNATE_DB_USERS,
    ENV_ENVIRONMENT,
    ENV_SSL_ROOT_CERT,
    FORBIDDEN_DATABASE_ROLE_ATTRIBUTES,
    NON_PRODUCTION_ENVIRONMENTS,
    ConfigurationError,
    DatabaseUserAttributes,
    alternate_database_user_verification_sql,
    require_equivalent_database_user,
    reset_resolution_cache,
)

OWNING_ROLE = "carddemo_ledger"


@pytest.fixture(autouse=True)
def _clear_cache() -> None:
    """Discard memoised resolutions so each test observes its own environment.

    Purpose
    -------
    The resolvers are cached, so a value resolved by one test would otherwise be returned
    to the next one regardless of the environment that test set up.

    Returns
    -------
    None
        The fixture clears the cache before and after each test and yields nothing.
    """
    reset_resolution_cache()
    yield
    reset_resolution_cache()


def _permit_override(monkeypatch: pytest.MonkeyPatch, path: str) -> None:
    """Place the environment in a state where a trust-anchor override is admissible.

    Purpose
    -------
    Isolate the environment gate from the integrity checks, so a test asserting an
    integrity refusal cannot pass merely because the override was refused for being in the
    wrong environment.

    Parameters
    ----------
    monkeypatch : pytest.MonkeyPatch
        The patcher used to set the two environment variables.
    path : str
        The value to place in the trust-anchor override variable.

    Returns
    -------
    None
    """
    monkeypatch.setenv(ENV_ENVIRONMENT, "dev")
    monkeypatch.setenv(ENV_SSL_ROOT_CERT, path)


def _install_clean_filesystem(monkeypatch: pytest.MonkeyPatch, path: str) -> None:
    """Substitute a filesystem in which ``path`` is a safely-permissioned regular file.

    Purpose
    -------
    Let the accept path be asserted independently of the host's directory modes, which the
    module header explains cannot be relied on in this container.

    Parameters
    ----------
    monkeypatch : pytest.MonkeyPatch
        The patcher used to replace the four filesystem calls the resolver makes.
    path : str
        The absolute path that the substituted filesystem reports as a clean regular file.
        Every directory above it is reported as mode 0755.

    Returns
    -------
    None

    Notes
    -----
    Assumptions: the substitution is deliberately narrow. It replaces only the four calls
    the resolver makes -- ``isfile``, ``access``, ``islink`` and ``stat`` -- so a future
    check added to the resolver that consults something else will not be silently satisfied
    by this helper; it will fail until the helper is taught about it, which is the correct
    direction for a test double to fail in.
    """
    monkeypatch.setattr(os.path, "isfile", lambda candidate: candidate == path)
    monkeypatch.setattr(os, "access", lambda candidate, mode: candidate == path)
    monkeypatch.setattr(os.path, "islink", lambda candidate: False)
    monkeypatch.setattr(
        os,
        "stat",
        lambda candidate: SimpleNamespace(
            st_mode=(stat.S_IFREG | 0o644) if candidate == path else (stat.S_IFDIR | 0o755),
            st_uid=os.getuid(),
        ),
    )


def _write_anchor(directory, name: str = "ca.pem", mode: int = 0o644) -> str:
    """Create a file to stand in for a certificate bundle and return its path.

    Parameters
    ----------
    directory : pathlib.Path
        The directory to create the file in.
    name : str
        The file name to use.
    mode : int
        The permission bits to apply.

    Returns
    -------
    str
        The absolute path of the created file.
    """
    target = directory / name
    target.write_text("-----BEGIN CERTIFICATE-----\n")
    os.chmod(target, mode)
    return str(target)


class TestTrustAnchorEnvironmentGate:
    """The override is admissible only in an explicitly non-production environment."""

    @pytest.mark.parametrize("environment", sorted(NON_PRODUCTION_ENVIRONMENTS))
    def test_override_is_admitted_in_each_non_production_environment(
        self, environment: str, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        """Every name in the allowlist admits an override of a clean anchor.

        Parameters
        ----------
        environment : str
            One name from the non-production allowlist.
        monkeypatch : pytest.MonkeyPatch
            Used to set the environment and substitute the filesystem.

        Returns
        -------
        None
        """
        from carddemo_migration.config import resolve_ssl_root_cert

        path = "/opt/carddemo/ca.pem"
        monkeypatch.setenv(ENV_ENVIRONMENT, environment)
        monkeypatch.setenv(ENV_SSL_ROOT_CERT, path)
        _install_clean_filesystem(monkeypatch, path)
        assert resolve_ssl_root_cert() == path

    @pytest.mark.parametrize("environment", ["prod", "production", "staging", "PROD"])
    def test_override_is_refused_outside_the_allowlist(
        self, environment: str, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        """An override is refused in any environment not named in the allowlist.

        Parameters
        ----------
        environment : str
            An environment name outside the allowlist, including a case variant of one
            inside it, since the comparison is exact rather than case-folded.
        monkeypatch : pytest.MonkeyPatch
            Used to set the environment and substitute the filesystem.

        Returns
        -------
        None
        """
        from carddemo_migration.config import resolve_ssl_root_cert

        path = "/opt/carddemo/ca.pem"
        monkeypatch.setenv(ENV_ENVIRONMENT, environment)
        monkeypatch.setenv(ENV_SSL_ROOT_CERT, path)
        _install_clean_filesystem(monkeypatch, path)
        with pytest.raises(ConfigurationError) as refusal:
            resolve_ssl_root_cert()
        assert "may not be overridden" in str(refusal.value)
        assert DEFAULT_SSL_ROOT_CERT in str(refusal.value)

    def test_override_is_refused_when_the_environment_is_unset(
        self, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        """An unset environment refuses the override rather than allowing it.

        Parameters
        ----------
        monkeypatch : pytest.MonkeyPatch
            Used to delete the environment variable and substitute the filesystem.

        Returns
        -------
        None

        Notes
        -----
        Assumptions: this is the fail-closed case that matters most. If an unknown
        deployment were the least constrained one, the control would be inverted.
        """
        from carddemo_migration.config import resolve_ssl_root_cert

        path = "/opt/carddemo/ca.pem"
        monkeypatch.delenv(ENV_ENVIRONMENT, raising=False)
        monkeypatch.setenv(ENV_SSL_ROOT_CERT, path)
        _install_clean_filesystem(monkeypatch, path)
        with pytest.raises(ConfigurationError) as refusal:
            resolve_ssl_root_cert()
        assert "unset" in str(refusal.value)

    def test_the_pinned_default_is_not_subject_to_the_gate(
        self, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        """With no override set, the pinned default resolves even in production.

        Parameters
        ----------
        monkeypatch : pytest.MonkeyPatch
            Used to set a production environment, clear the override and substitute the
            filesystem so the pinned path reports as clean.

        Returns
        -------
        None
        """
        from carddemo_migration.config import resolve_ssl_root_cert

        monkeypatch.setenv(ENV_ENVIRONMENT, "prod")
        monkeypatch.delenv(ENV_SSL_ROOT_CERT, raising=False)
        _install_clean_filesystem(monkeypatch, DEFAULT_SSL_ROOT_CERT)
        assert resolve_ssl_root_cert() == DEFAULT_SSL_ROOT_CERT


class TestTrustAnchorIntegrity:
    """A readable absolute path is not sufficient; it must also be unsubstitutable."""

    def test_symbolic_link_is_refused(self, tmp_path, monkeypatch: pytest.MonkeyPatch) -> None:
        """A link is refused rather than followed, because its target can be repointed.

        Parameters
        ----------
        tmp_path : pathlib.Path
            Directory supplied by pytest to hold the real files.
        monkeypatch : pytest.MonkeyPatch
            Used to permit the override.

        Returns
        -------
        None
        """
        from carddemo_migration.config import resolve_ssl_root_cert

        target = _write_anchor(tmp_path)
        link = tmp_path / "link.pem"
        link.symlink_to(target)
        _permit_override(monkeypatch, str(link))
        with pytest.raises(ConfigurationError, match="symbolic link"):
            resolve_ssl_root_cert()

    @pytest.mark.parametrize(
        ("label", "mode"),
        [("group-writable", 0o664), ("other-writable", 0o646), ("world-writable", 0o666)],
    )
    def test_writable_anchor_is_refused(
        self, label: str, mode: int, tmp_path, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        """An anchor writable by anyone but its owner is refused.

        Parameters
        ----------
        label : str
            Human-readable description of the permission being tested.
        mode : int
            The permission bits to apply to the anchor.
        tmp_path : pathlib.Path
            Directory supplied by pytest to hold the real file.
        monkeypatch : pytest.MonkeyPatch
            Used to permit the override.

        Returns
        -------
        None
        """
        from carddemo_migration.config import resolve_ssl_root_cert

        anchor = _write_anchor(tmp_path, name=f"{label}.pem", mode=mode)
        _permit_override(monkeypatch, anchor)
        with pytest.raises(ConfigurationError, match="writable by its group or by others"):
            resolve_ssl_root_cert()

    def test_anchor_under_a_writable_directory_is_refused(
        self, tmp_path, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        """A directory writable without the sticky bit permits unlink-and-replace.

        Parameters
        ----------
        tmp_path : pathlib.Path
            Directory supplied by pytest, inside which a permissive directory is created.
        monkeypatch : pytest.MonkeyPatch
            Used to permit the override.

        Returns
        -------
        None

        Notes
        -----
        Assumptions: the anchor itself is mode 0644 here, so the only reason to refuse is
        the directory. That isolation is the point -- it proves the directory walk is what
        rejects, and that checking the file's own mode alone would have passed this case.
        """
        from carddemo_migration.config import resolve_ssl_root_cert

        permissive = tmp_path / "permissive"
        permissive.mkdir()
        os.chmod(permissive, 0o777)
        anchor = _write_anchor(permissive)
        _permit_override(monkeypatch, anchor)
        with pytest.raises(ConfigurationError, match="writable by its group"):
            resolve_ssl_root_cert()

    def test_relative_and_missing_paths_are_refused(
        self, tmp_path, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        """A relative path and an absent file are both refused before any mode is read.

        Parameters
        ----------
        tmp_path : pathlib.Path
            Directory supplied by pytest, used to build a path to a file never created.
        monkeypatch : pytest.MonkeyPatch
            Used to permit the override.

        Returns
        -------
        None
        """
        from carddemo_migration.config import resolve_ssl_root_cert

        _permit_override(monkeypatch, "relative/ca.pem")
        with pytest.raises(ConfigurationError, match="absolute path"):
            resolve_ssl_root_cert()

        reset_resolution_cache()
        _permit_override(monkeypatch, str(tmp_path / "absent.pem"))
        with pytest.raises(ConfigurationError, match="existing file"):
            resolve_ssl_root_cert()

    def test_no_refusal_echoes_the_anchor_contents(
        self, tmp_path, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        """A refusal names the path and the defect, never a byte of the file.

        Parameters
        ----------
        tmp_path : pathlib.Path
            Directory supplied by pytest to hold the real file.
        monkeypatch : pytest.MonkeyPatch
            Used to permit the override.

        Returns
        -------
        None

        Notes
        -----
        Assumptions: a certificate bundle is public, so echoing it would not disclose a
        secret. The assertion exists because the module holds one uniform rule -- a
        diagnostic reports shape, never content -- and a bundle read from a path an
        operator controls is exactly where a private key could be pasted by mistake.
        """
        from carddemo_migration.config import resolve_ssl_root_cert

        secret_marker = "PRIVATE-KEY-MATERIAL-SHOULD-NEVER-APPEAR"
        anchor = tmp_path / "leaky.pem"
        anchor.write_text(secret_marker)
        os.chmod(anchor, 0o666)
        _permit_override(monkeypatch, str(anchor))
        with pytest.raises(ConfigurationError) as refusal:
            resolve_ssl_root_cert()
        assert secret_marker not in str(refusal.value)


class TestAlternateUserVerificationQuery:
    """The published query binds its inputs and answers the attributes the rule judges."""

    def test_query_uses_named_parameters_rather_than_interpolation(self) -> None:
        """Both inputs are named placeholders, so a role name cannot be formatted in.

        Returns
        -------
        None
        """
        sql = alternate_database_user_verification_sql()
        assert "%(owning_role)s" in sql
        assert "%(alternate_user)s" in sql

    def test_query_selects_every_field_the_rule_judges(self) -> None:
        """Each dataclass field is produced by the query, so no attribute is unchecked.

        Returns
        -------
        None

        Notes
        -----
        Assumptions: this is the assertion that keeps the query and the rule from
        drifting. Adding a field to the dataclass without selecting it would otherwise
        surface only as a positional mismatch at run time, against a live cluster.
        """
        sql = alternate_database_user_verification_sql()
        for field in DatabaseUserAttributes.__dataclass_fields__:
            assert f"AS {field}" in sql, f"the query does not produce {field}"

    def test_query_reads_pg_roles_and_not_pg_authid(self) -> None:
        """The narrower catalog is used, so no password hash is ever selected.

        Returns
        -------
        None
        """
        sql = alternate_database_user_verification_sql()
        assert "pg_catalog.pg_roles" in sql
        assert "pg_authid" not in sql

    def test_membership_is_tested_transitively(self) -> None:
        """Membership follows the grant chain, so an interposed group role still counts.

        Returns
        -------
        None
        """
        assert "pg_has_role" in alternate_database_user_verification_sql()


class TestAlternateUserAdjudication:
    """An allowlisted name is accepted only once its authority is shown to be bounded."""

    @staticmethod
    def _attributes(**overrides: object) -> DatabaseUserAttributes:
        """Build an otherwise-acceptable observation with the given fields overridden.

        Parameters
        ----------
        **overrides : object
            Field values to replace on the acceptable baseline.

        Returns
        -------
        DatabaseUserAttributes
            The observation to adjudicate.
        """
        baseline: dict[str, object] = {
            "username": "carddemo_ledger_rotating",
            "can_login": True,
            "is_member_of_owning_role": True,
            "is_superuser": False,
            "can_create_db": False,
            "can_create_role": False,
            "bypasses_row_level_security": False,
            "can_replicate": False,
        }
        baseline.update(overrides)
        return DatabaseUserAttributes(**baseline)  # type: ignore[arg-type]

    def test_a_bounded_member_is_accepted(self) -> None:
        """A login role that is a member and holds no extra attribute passes.

        Returns
        -------
        None
        """
        observed = self._attributes()
        assert require_equivalent_database_user(OWNING_ROLE, observed) is observed

    def test_a_missing_role_is_refused(self) -> None:
        """No row means the allowlist does not describe this cluster, so it is refused.

        Returns
        -------
        None
        """
        with pytest.raises(ConfigurationError) as refusal:
            require_equivalent_database_user(OWNING_ROLE, None)
        assert "does not exist in the cluster" in str(refusal.value)
        assert ENV_ALTERNATE_DB_USERS in str(refusal.value)

    def test_a_role_that_cannot_log_in_is_refused(self) -> None:
        """A member that cannot authenticate is a dead allowlist entry, not a credential.

        Returns
        -------
        None
        """
        with pytest.raises(ConfigurationError, match="may not log in"):
            require_equivalent_database_user(OWNING_ROLE, self._attributes(can_login=False))

    def test_a_non_member_is_refused(self) -> None:
        """A role outside the owning role lacks the privileges the allowlist assumed.

        Returns
        -------
        None
        """
        with pytest.raises(ConfigurationError) as refusal:
            require_equivalent_database_user(
                OWNING_ROLE, self._attributes(is_member_of_owning_role=False)
            )
        assert "is not a member of" in str(refusal.value)
        assert OWNING_ROLE in str(refusal.value)

    @pytest.mark.parametrize("attribute", FORBIDDEN_DATABASE_ROLE_ATTRIBUTES)
    def test_each_forbidden_attribute_is_refused_individually(self, attribute: str) -> None:
        """Every attribute in the constant is actually enforced, not merely listed.

        Parameters
        ----------
        attribute : str
            One field name from the forbidden-attribute constant.

        Returns
        -------
        None

        Notes
        -----
        Assumptions: parametrising over the constant is what prevents the list and the
        enforcement from drifting. A name added to the constant with no enforcement behind
        it fails here immediately, rather than reading as a control that is not one.
        """
        with pytest.raises(ConfigurationError) as refusal:
            require_equivalent_database_user(OWNING_ROLE, self._attributes(**{attribute: True}))
        assert attribute in str(refusal.value)

    def test_every_failing_attribute_is_reported_in_one_refusal(self) -> None:
        """Two defects are both named, so a repair does not need a second run to find one.

        Returns
        -------
        None
        """
        with pytest.raises(ConfigurationError) as refusal:
            require_equivalent_database_user(
                OWNING_ROLE, self._attributes(is_superuser=True, can_create_db=True)
            )
        message = str(refusal.value)
        assert "is_superuser" in message
        assert "can_create_db" in message

    def test_a_refusal_never_echoes_the_observed_user_name(self) -> None:
        """The message names the owning role and the defect, not the value from the secret.

        Returns
        -------
        None

        Notes
        -----
        Assumptions: the owning role is derived from this module's own mapping and is safe
        to print, whereas the observed name arrived in a secret document and falls under
        the module's rule that a resolved secret value is never printed.
        """
        observed = self._attributes(username="pasted-from-the-wrong-secret", is_superuser=True)
        with pytest.raises(ConfigurationError) as refusal:
            require_equivalent_database_user(OWNING_ROLE, observed)
        assert observed.username not in str(refusal.value)
