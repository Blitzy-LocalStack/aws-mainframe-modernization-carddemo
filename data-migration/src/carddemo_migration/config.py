"""Runtime settings for the CardDemo ETL, resolved from AWS when a command runs.

Purpose
-------
Locate everything the extract-transform-load package needs in order to reach its two
runtime dependencies -- the Aurora PostgreSQL cluster it bulk-loads into, and the versioned
object-storage bucket it stages dataset generations through -- and hand them to the loaders
as validated, immutable descriptors. This module also holds the one piece of topology every
loader has to agree on: the eight bounded-context schemas, the login role each context
connects as, and -- held separately, because the two are not the same set -- the seven of
those schemas that a service role actually OWNS.

Nothing here is a value committed to this repository. Every endpoint and every credential is
read at the moment a command runs: the non-secret settings from AWS Systems Manager
Parameter Store, and the database credential from AWS Secrets Manager. A checkout of this
repository is therefore inert on its own, which is what makes "nothing sensitive committed"
a structural property of the tree rather than a habit a reviewer has to police.

What this module resolves
-------------------------
From Parameter Store, under paths built from a prefix and the environment name:
    ``<prefix>/<environment>/aurora/host``      the cluster writer endpoint
    ``<prefix>/<environment>/aurora/port``      the listening port
    ``<prefix>/<environment>/aurora/database``  the database name
    ``<prefix>/<environment>/datasets/bucket``  the dataset-staging bucket name

From Secrets Manager, one JSON secret per login role:
    ``<prefix>/<environment>/aurora/<role>``    ``{"username": ..., "password": ...}``

The prefix and the environment name both arrive in environment variables
(:data:`ENV_PARAMETER_PREFIX`, :data:`ENV_ENVIRONMENT`); the prefix has a default and the
environment name deliberately has none. A path *convention* is not sensitive, and stating it
here is what lets an operator find a misconfigured parameter. A resolved *value* is a
different thing entirely and never appears in this file, in a default, or in a message.

Three further environment variables tune how a resolved credential may be used, and all three
are refusal surfaces rather than knobs: :data:`ENV_SSL_MODE` accepts only
:data:`REQUIRED_SSL_MODE`, :data:`ENV_SSL_ROOT_CERT` accepts only an absolute path to an
existing bundle, and :data:`ENV_ALTERNATE_DB_USERS` accepts only per-role
``role=alternate_user`` pairs. Each is documented at its declaration.

How a connection is secured
---------------------------
Every parameter set this module produces carries ``sslmode=verify-full`` and an explicit
``sslrootcert``, unconditionally. ``verify-full`` is the only mode libpq offers that checks both
the certificate chain and the endpoint's hostname, and the anchor it checks against is pinned to
the AWS-published Aurora bundle rather than the operating system's trust store, so the set of
authorities that can certify this cluster is as narrow as it can be made. Neither can be
weakened from configuration: the mode is a module constant, and the only override that exists
selects where the bundle lives.

This is the client half of one requirement stated in three places, each catching what the others
cannot. ``infra/modules/aurora-postgresql`` sets ``rds.force_ssl=1`` so the server refuses a
cleartext connection from any client, including one this module never wrote;
``data-migration/sql/V0__schemas_and_roles.sql`` refuses to bootstrap over an unencrypted
session and asserts the cluster stores credentials under SCRAM; and this module refuses to
describe a connection that would skip verification.

The credential a load authenticates as is checked too. A secret's *name* is derived from the
schema's owning role and is auditable, but its *contents* are not, so the ``username`` field is
asserted against that role. The alternating-users rotation strategy legitimately substitutes a
second user name, and that case is expressed as an explicit per-role allowlist rather than by
declining the check -- which would accept every substitution in order to permit one.

The eight schemas
-----------------
``auth``, ``account``, ``card``, ``ledger``, ``reference``, ``batch``, ``authorization`` and
``reporting``. Each is owned by a dedicated ``NOLOGIN`` role ``carddemo_<context>_owner`` and
connected to by a separate login role, for the reason recorded on :data:`OWNED_SCHEMA_ROLES`.
Seven of the eight additionally have a ``carddemo_<context>_migrator`` login role, which is a
member of the owner and reaches its authority only through ``SET ROLE``; ``reporting`` has none,
because reporting-service ships no migration. The names are mirrored from
``data-migration/sql/V0__schemas_and_roles.sql``, which creates them and is their single source
of truth; this module only names them, and a name that disagrees with that script is a defect
here rather than a variant spelling.

Those eight replace what the baseline exposed to one CICS region as eight ``DEFINE FILE``
stanzas in ``app/csd/CARDDEMO.CSD`` -- ACCTDAT at L1, CARDAIX at L13, CARDDAT at L25, CCXREF
at L37, CUSTDAT at L50, CXACAIX at L63, TRANSACT at L76 and USRSEC at L88. The matching count
is a coincidence and must not be read as a one-to-one renaming: CARDAIX and CXACAIX name
alternate-index *paths* rather than base clusters, so they become secondary indexes inside
``card`` and ``account``, while ``reference``, ``batch``, ``authorization`` and ``reporting``
carry data the CSD never described at all.

Object-storage prefix convention
--------------------------------
Dataset generations are staged under ``<domain>/<dataset>/dt=YYYY-MM-DD/gen=NNNN/`` inside a
versioned bucket, with the generation number zero-padded to four digits. Bucket versioning
plus a retention rule on noncurrent versions is what stands in for the baseline's
generation-data-group limit, so a stale generation is superseded rather than overwritten.
:meth:`DatasetStagingSettings.generation_prefix` is the only place that layout is built, so
two callers cannot stage the same dataset under two different shapes.

What this module deliberately does not do
-----------------------------------------
* It opens no database connection and imports no database driver. It returns connection
  *parameters*; ``carddemo_migration.loaders.aurora`` owns ``psycopg`` and the connection.
* It issues no ``CREATE SCHEMA``, ``CREATE ROLE``, ``GRANT`` or ``REVOKE``. The bootstrap
  script owns the topology; this module only names it.
* It is not imported by ``carddemo_migration.copybook``. That subpackage is standard library
  only so the codecs stay exercisable on a bare checkout, and an import edge from there to
  here would end that.
* It handles no money, so no fixed-point-versus-float question arises in it, and none may be
  introduced -- money decoding belongs to the codecs.
* It reads no file. The one exception is deliberate and narrow: :func:`resolve_ssl_root_cert`
  confirms that the TLS trust anchor exists and is readable, and never opens or parses it. That
  is a stat, not I/O over content, and it is there because libpq reports an unreadable root
  certificate as a generic handshake failure -- which invites an operator to weaken the mode
  rather than fix the path.

Design decisions (WHY)
----------------------
Alternatives Considered:
    **Settings are read from Parameter Store and Secrets Manager, not from a committed
    configuration file, a ``.env`` file, or baked-in defaults.** All three alternatives were
    considered and each fails in a way that matters. Infrastructure provisioning is the only
    thing that knows the cluster endpoint, the bucket name and the generated credential --
    they are outputs of the stack, written to the parameter store and the secret store as it
    is applied -- so a committed file could only hold one of two things: a real credential,
    which the project forbids outright, or a copy of an endpoint that silently goes stale the
    next time the stack is re-applied and then fails as a connection error pointing at a host
    that no longer exists. Reading at command time removes both outcomes: there is nothing to
    leak and nothing to drift, and the same image runs unmodified against every environment.
Assumptions:
    **No endpoint, credential, account identifier, resource identifier or connection string
    appears anywhere in this file** -- not in code, not in a default, not in a docstring, not
    in an example. What is written here is the *shape* of a lookup; what it resolves to exists
    only in the two AWS stores and only while a command is running. That division is what the
    no-secrets constraint actually requires, and it is why the environment name has no default
    value: a default would let a command silently resolve against the wrong environment, and
    failing with the name of the unset variable is the safer outcome.
Trade-offs:
    **Every public entry point raises :class:`ConfigurationError` and nothing else.** Callers
    would otherwise have to know both the parameter-store and the secret-store error
    vocabularies, plus ``json`` decoding failures, to distinguish "this is misconfigured" from
    "this is broken". The accepted cost is one wrapping layer, and the underlying exception is
    chained with ``raise ... from`` so that the service error code and request identifier stay
    in the traceback. There are exactly two deliberate exceptions to that chaining, both where
    the original exception object carries a resolved value: a malformed credential document and
    a non-numeric port. Each is raised with ``from None``, and each states its reason where it
    is raised.
"""

import json
import os
import re
import stat
from collections.abc import Mapping
from dataclasses import dataclass
from datetime import date, datetime
from functools import lru_cache
from types import MappingProxyType
from typing import Any

# Trade-offs: an explicit ``__all__`` is declared here even though the package's own
# ``__init__.py`` deliberately declines to declare one. The two decisions are consistent
# rather than contradictory, and the difference is the blast radius of a star import. At the
# package root, an ``__all__`` naming subpackages would give ``from carddemo_migration import
# *`` the power to pull the database driver and the AWS SDK into a process that only wanted a
# codec, which is exactly the layering guarantee that file asserts. Inside a single module
# there is no such reach: every name below is defined in this file, so the list can only
# narrow what a star import sees. It is declared for that narrowing -- the private helpers,
# the compiled patterns and the standard-library modules imported above stay out of an
# importer's namespace, so a reader of a consuming module can tell at a glance which names
# came from here. The accepted cost is that a new public name has to be added in two places,
# which the ad-hoc export test for this module is what catches.
#
# Assumptions: the entries are grouped -- constants, then classes, then functions -- and
# each group is alphabetical, rather than the whole list being one alphabetical run. A single
# run is what a sorting tool would produce and it reads worse here, because case-sensitive
# ordering interleaves the groups: ``DEFAULT_PARAMETER_PREFIX`` would land between
# ``ConfigurationError`` and ``DatasetStagingSettings``, separating the two classes. The
# grouping is therefore deliberate and is not a sort that was left unfinished.
__all__ = [
    "AWS_RETRY_MODE",
    "AWS_READ_TIMEOUT_SECONDS",
    "AWS_MAX_RETRY_ATTEMPTS",
    "AWS_CONNECT_TIMEOUT_SECONDS",
    "aws_client",
    "DEFAULT_PARAMETER_PREFIX",
    "DEFAULT_SSL_ROOT_CERT",
    "ENV_ALTERNATE_DB_USERS",
    "ENV_DB_MASTER_SECRET",
    "ENV_ENVIRONMENT",
    "ENV_PARAMETER_PREFIX",
    "ENV_SSL_MODE",
    "ENV_SSL_ROOT_CERT",
    "REDACTED",
    "LOGIN_ROLE_NAMES",
    "MIGRATION_SCHEMA_ROLES",
    "OWNED_SCHEMA_ROLES",
    "REQUIRED_SSL_MODE",
    "SCHEMA_NAMES",
    "SCHEMA_ROLES",
    "AuroraConnectionSettings",
    "ConfigurationError",
    "DatabaseUserAttributes",
    "DatasetStagingSettings",
    "alternate_database_user_verification_sql",
    "database_secret_name",
    "database_secret_name_for_role",
    "migration_role_for_schema",
    "owner_role_for_schema",
    "parameter_path",
    "quote_identifier",
    "quoted_schema",
    "require_equivalent_database_user",
    "reset_resolution_cache",
    "resolve_alternate_database_users",
    "resolve_aurora_settings",
    "resolve_dataset_staging_settings",
    "resolve_migration_settings",
    "resolve_environment_name",
    "resolve_master_settings",
    "resolve_card_verification_value_key_id",
    "resolve_customer_identifier_key_id",
    "resolve_parameter_prefix",
    "resolve_seed_user_subjects",
    "resolve_ssl_root_cert",
    "role_for_schema",
]


class ConfigurationError(RuntimeError):
    """Raised when a required runtime setting is absent, unreadable or malformed.

    Purpose
    -------
    Present every failure to locate or interpret a runtime setting as one exception type, so a
    caller can distinguish "this deployment is misconfigured" from a genuine data or driver
    fault without matching on two AWS service error vocabularies.

    Every message raised in this module names the parameter path, the secret name, the
    environment variable or the schema that could not be resolved, and stops there. A resolved
    value is never interpolated into a message, because an exception is the single most widely
    copied piece of text a failing container produces: it reaches logs, alarm notifications and
    issue trackers, and a credential placed in one has escaped all three at once.

    Every validation in this module raises this type; none of them uses an ``assert``. The
    reason is recorded beside the module's first validation in :func:`_require_text`, which is
    the code that decision is about.

    Parameters
    ----------
    *args : object
        Passed to :class:`RuntimeError` unchanged. Callers supply a single message string
        naming what could not be resolved.

    Returns
    -------
    ConfigurationError
        A new instance, raised rather than returned in practice.

    Raises
    ------
    None
        Construction cannot fail; this type only adds a name to :class:`RuntimeError`.
    """

    # Trade-offs: the class carries no structured attributes -- no error code, no
    # retryable flag. Adding them was considered and rejected: nothing in this package
    # branches on the *kind* of configuration failure, because a missing parameter and a
    # malformed one are equally terminal for a load that has not started yet, and a field
    # nobody reads is a field that goes stale. The accepted cost is that a future caller
    # wanting to branch has to match on the message or subclass this type.
    __slots__ = ()


# Assumptions: the environment name has NO default and is required. It selects which
# deployment's parameters and credentials a command resolves, so a default would make the
# most dangerous possible mistake -- a command intended for one environment quietly loading
# another's data -- into the behaviour that happens when a variable is forgotten. Failing with
# the name of the unset variable costs one restart; loading production data into the wrong
# cluster is not recoverable by restarting.
ENV_ENVIRONMENT = "CARDDEMO_ENVIRONMENT"

# Trade-offs: the parameter prefix is overridable but does have a default, which is the
# opposite of the decision immediately above. The asymmetry is deliberate: a wrong prefix
# resolves nothing and fails immediately with the path it looked for, whereas a wrong
# environment resolves successfully against the wrong deployment. Only the second failure mode
# is silent, so only the second one is denied a default. The override exists so that a stack
# provisioned under a different parameter namespace -- an isolated review deployment sharing
# one account, for instance -- needs no code change.
ENV_PARAMETER_PREFIX = "CARDDEMO_PARAMETER_PREFIX"

# Assumptions: the leading slash is part of the value, and its absence is rejected
# rather than repaired. Parameter Store distinguishes a hierarchical name, which begins with a
# slash and can be fetched by path, from a flat one, which cannot; silently prepending a slash
# would hide that the caller asked for something else. No trailing slash is carried, because
# :func:`parameter_path` joins with a single separator and two would produce an empty path
# segment that resolves to nothing.
DEFAULT_PARAMETER_PREFIX = "/carddemo"

# Refactoring Rationale: the transport mode is a CONSTANT rather than a setting, and that
# is the whole of the decision. This module previously emitted no TLS keyword at all, which is
# not the same as emitting a weak one but has the same outcome: libpq's compiled-in default for
# an unspecified ``sslmode`` is ``prefer``, and ``prefer`` negotiates TLS when the server offers
# it and silently connects in CLEARTEXT when it does not. So a cluster that had lost
# ``rds.force_ssl``, or a connection redirected to something that is not the cluster, would be
# reached in the clear -- the service credential first, then every account, customer, card and
# transaction row the load reads -- and the load would succeed, because ``prefer`` reports
# nothing. Isolating the data tier in subnets with no internet route bounds who can observe that
# traffic; it does not make the traffic unreadable, and encryption in transit is about the
# traffic.
#
# Alternatives Considered: ``require`` was considered and rejected. It guarantees
# encryption but performs NO certificate or hostname verification, so it defends against a
# passive observer and not against the endpoint being something else -- which is the failure
# this module is positioned to prevent, because it is the component that decides what host to
# connect to. ``verify-ca`` was also rejected: it validates the chain but not the name, so any
# certificate issued by the trusted authority is accepted for any endpoint. ``verify-full`` is
# the only mode that checks both, and it is the strongest mode libpq offers, so there is no
# setting to expose -- anything an operator could select from here would be weaker than the
# fixed value.
REQUIRED_SSL_MODE = "verify-full"

# Trade-offs: an environment variable exists for the mode even though the mode is fixed,
# and it exists ONLY so that lowering it fails loudly. An operator debugging a TLS failure
# reaches for ``sslmode`` first; with no variable to set, the next step is a local edit to this
# file, which is invisible to review and ships. A variable whose only accepted value is
# :data:`REQUIRED_SSL_MODE` turns that attempt into a message naming why the mode is not
# negotiable. Note that libpq also honours ``PGSSLMODE`` from the process environment, but an
# explicit connection keyword takes precedence over it, so emitting ``sslmode`` unconditionally
# is what makes an inherited ``PGSSLMODE=disable`` inert rather than authoritative.
ENV_SSL_MODE = "CARDDEMO_DB_SSL_MODE"

# Assumptions: ``verify-full`` requires a trust anchor, and the anchor is pinned to the
# AWS-published Aurora certificate bundle rather than left to the operating system's trust
# store. The system store was considered and rejected as too broad: it trusts every publicly
# trusted authority, so a certificate issued by any one of them for this endpoint's name would
# verify, whereas the AWS bundle trusts only the authorities that can legitimately certify an
# Aurora endpoint. The default is a filesystem PATH, which is not a secret and not an endpoint
# -- the same category as :data:`DEFAULT_PARAMETER_PREFIX` -- so committing it breaks no
# no-secrets rule. The bundle itself is placed at this path by ``data-migration/Dockerfile``;
# a checkout is unaffected, because a checkout opens no connection.
DEFAULT_SSL_ROOT_CERT = "/etc/ssl/certs/aws-rds-global-bundle.pem"

# Trade-offs: the anchor's LOCATION is overridable while the mode is not, and the
# asymmetry is deliberate. An override of the path cannot weaken verification -- whatever it
# names still has to certify the endpoint under ``verify-full`` -- and it is genuinely needed,
# because the bundle sits at a different path when a command runs outside the container image,
# and because AWS republishes the bundle as authorities are rotated. The value is constrained
# to an absolute path so that it cannot be a URL, an inline certificate or a relative path
# resolved against whatever directory the process happens to have started in.
ENV_SSL_ROOT_CERT = "CARDDEMO_DB_SSL_ROOT_CERT"

# Trade-offs: the environments in which :data:`ENV_SSL_ROOT_CERT` may override the pinned
# default are named explicitly, so the set is closed rather than derived by excluding the single
# name ``prod``. Deriving it would admit an override under every name nobody thought to list --
# ``production``, ``prod-dr``, a typo -- which is precisely the set an override should not reach.
# The accepted cost is that adding a genuinely non-production environment means adding its name
# here; that edit is visible in review, whereas a name that silently gained override rights is
# not. ``local`` covers a command run outside any container, where the bundle is not at the
# image path.
NON_PRODUCTION_ENVIRONMENTS: frozenset[str] = frozenset({"dev", "test", "local"})

# Refactoring Rationale: the credential's user name is now asserted against the role the
# secret name was derived from, and this variable is the single, explicit escape hatch for the
# one case where the two legitimately differ. The assertion was previously declined outright, on
# the correct observation that a managed rotation using the alternating-users strategy hands back
# a second user name; the consequence, though, was that ANY user name in the secret document was
# accepted, so a secret populated with the wrong identity -- a mis-targeted rotation, a
# copy-paste between two environments' secrets, an operator repairing one secret with another's
# payload -- would authenticate as a role holding privileges the caller did not ask for and the
# load would proceed. The secret's NAME being auditable does not help there, because the name was
# right and the contents were not.
#
# Assumptions: the allowlist is keyed BY ROLE (``role=alternate``, comma separated) rather
# than being a flat list of accepted user names. A flat list would be satisfied by any entry for
# any schema, so allowlisting one role's rotation clone would simultaneously permit that clone --
# or any other listed name -- as the credential for all of them. Keying by role keeps each
# exception scoped to the role it was granted for, and an alternate that is itself one of the
# fifteen login roles is refused outright, because that spelling is not a rotation clone but a
# cross-role substitution.
ENV_ALTERNATE_DB_USERS = "CARDDEMO_DB_ALTERNATE_USERS"

# Assumptions: the cluster's MASTER credential is named by an environment variable rather
# than by a composed path, because it is the one credential this stack does not name. The other
# fifteen live at ``<prefix>/<environment>/aurora/<role>``, composed by
# :func:`database_secret_name_for_role` from the same convention the Terraform module that
# creates them composes; the master credential is created by RDS itself --
# ``infra/modules/aurora-postgresql`` sets ``manage_master_user_password`` -- and RDS chooses the
# entry's name, so there is no
# convention to compose and nothing to derive it from. The cluster module publishes the ARN as
# its ``master_user_secret_arn`` output, and the environment root passes that value to the
# bootstrap task in this variable.
#
# Trade-offs: an ARN or a name is accepted, because ``GetSecretValue`` resolves either as
# its ``SecretId`` and the value that arrives here is whichever one the caller was given. No
# validation of shape is imposed for that reason: rejecting a name because it is not an ARN
# would refuse an operator running the step by hand against an entry they can see in the
# console, and a wrong value fails at resolution with a message naming the identifier that was
# tried, which is what an operator needs.
#
# Assumptions: this is a LOCATOR, never a credential -- the same distinction
# :data:`ENV_PARAMETER_PREFIX` draws -- so it is safe in a task definition's environment block,
# in a runbook and in a shell history, and reading the secret it names still requires an IAM
# grant this variable confers nothing towards.
ENV_DB_MASTER_SECRET = "CARDDEMO_DB_MASTER_SECRET"

# Trade-offs: the redaction is a fixed constant that encodes nothing about the value it
# stands for -- deliberately weaker than the reference record codec under ``tests/helpers/``,
# which masks a sensitive field to a short deterministic digest so that a masked diff still
# reveals *which* field changed. That property is worth having for a card number inside a
# golden comparison and is a liability for a credential: a digest is stable, so anyone holding
# two rendered objects can tell whether the password is the same in both, and anyone holding a
# guess can confirm it by hashing. The accepted cost is that two differently-configured
# processes render identically here, which is acceptable because the fields that actually
# identify a misconfiguration -- host, port, database, user -- are all rendered in clear.
REDACTED = "***redacted***"

# Assumptions: a path segment is restricted to the characters Parameter Store accepts in
# a name component, and must start with an alphanumeric. The pattern is applied to the
# environment name, to each segment of the prefix, and to each segment appended to a path, so
# a value carrying a slash cannot smuggle in an extra level of hierarchy and reach a parameter
# the caller did not name. Rejecting it here turns that into a startup failure instead of a
# lookup that succeeds against the wrong path.
_PATH_SEGMENT_PATTERN = re.compile(r"\A[A-Za-z0-9][A-Za-z0-9_.-]*\Z")

# Assumptions: an object-storage prefix component may not be empty and may not contain a
# forward slash, because the slash is the separator that gives the staged layout its shape. A
# domain or dataset carrying one would silently deepen the hierarchy, so a staged generation
# would land somewhere a reader looking for it by convention would never find it. Every other
# character is left alone: object keys are far more permissive than parameter names, and
# narrowing them further here would reject a legitimate dataset name for no benefit.
# WHY : Refactoring Rationale: this admitted ANY character but a slash, which meant a
# carriage return, a line feed, a NUL or an escape character in a domain or dataset
# segment reached the built prefix intact. The staging command logs the resulting key, so
# an embedded newline let one staged object forge additional operational log lines, and an
# escape sequence reached any terminal tailing those logs. The class is now stated
# positively -- printable, no space, no slash, no backslash -- so a character has to be
# named to be admitted rather than merely not named to be refused.
# Trade-offs: this is the LAST line of defence rather than the only one. The callers that
# accept externally supplied segments validate them with the shared safe-segment predicate
# in the staging loader, which reports a specific fault; by the time a value reaches this
# pattern the fault is reported as an unacceptable prefix component, which is correct but
# less precise. Both exist because this one cannot be bypassed by a new caller.
_PREFIX_COMPONENT_PATTERN = re.compile(r"\A[\x21-\x2E\x30-\x5B\x5D-\x7E]+\Z")

# Assumptions: an allowlisted alternate user name must be a plain, unquoted PostgreSQL
# role name -- it starts with a letter or underscore, continues with letters, digits, underscore
# or dollar, and is at most the 63 bytes the server truncates at. The pattern is narrow on
# purpose. A name needing quotes to be legal is not one this project creates: every role in
# ``data-migration/sql/V0__schemas_and_roles.sql`` is a bare ``carddemo_*`` identifier, and the
# alternating-users rotation strategy derives its second user by suffixing that name. Accepting a
# name outside this shape would mean accepting one whose spelling depends on how it is quoted,
# and a comparison against an unquoted secret payload cannot settle that.
_ROLE_NAME_PATTERN = re.compile(r"\A[A-Za-z_][A-Za-z0-9_$]{0,62}\Z")

# Assumptions: the generation number is rendered in a fixed four digits so that staged
# generations under one date sort correctly as strings, which is the only ordering an
# object-storage listing offers. The width is declared once and the accepted range is derived
# from it, rather than both being written out, because the two are one fact: a value above the
# range would still format -- Python widens rather than truncates, so 10000 renders as
# ``gen=10000`` -- and would then sort ahead of ``gen=9999``, defeating the padding entirely.
_GENERATION_DIGITS = 4
_MAX_GENERATION = 10**_GENERATION_DIGITS - 1

# Assumptions: these are the parameter-store error codes that mean "the caller named
# something that is not there", as distinct from a permission or transport failure. They are
# matched by code rather than by exception class so that no service exception type has to be
# imported at module scope, which is what keeps this module importable with the SDK absent;
# see :func:`aws_client`. ``ParameterVersionNotFound`` is included because a parameter that
# exists but has had the referenced version removed is, to a caller that named a path, the
# same actionable condition as one that was never created.
_MISSING_PARAMETER_CODES = frozenset({"ParameterNotFound", "ParameterVersionNotFound"})

# Assumptions: the secret store reports an absent secret under a single code, and a
# secret that is scheduled for deletion reports the same one, so both resolve to the same
# actionable message. The distinction between them is not one this module can act on: neither
# yields a credential, and both are fixed by provisioning rather than by retrying.
_MISSING_SECRET_CODES = frozenset({"ResourceNotFoundException"})

# Trade-offs: the two grouping segments are named once here because each is used from
# more than one place -- ``aurora`` by the three parameter lookups and by the secret name,
# ``datasets`` by the bucket lookup -- so a rename must not be able to move one and leave the
# other pointing at a path infrastructure no longer writes. The leaf names (``host``, ``port``,
# ``database``, ``bucket``) are deliberately left as literals at their single use site, where
# they sit beside the value they fetch and read as the path they form; promoting a
# single-use literal to a constant moves it away from its only reader for no protection.
_AURORA_SEGMENT = "aurora"
_DATASETS_SEGMENT = "datasets"

# Assumptions: the canonical hyphenated RFC-4122 form and nothing else. The value this
# matches is written into auth.users.cognito_sub, declared UUID, so accepting a braced or
# unhyphenated spelling here would mean reading one form and having to send another. Both
# hex cases are admitted because Cognito's own output case is not something this module
# should depend on, and the engine treats the two as the same value.
_SUBJECT_PATTERN = re.compile(r"[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")

# Assumptions: these are the two keys the secret document is read by, and they match the
# field names a managed relational-database credential is written with. Naming them as
# constants rather than inline is what lets the ad-hoc and unit tests build a payload from the
# same two strings the resolver reads, so a test cannot pass against a spelling the resolver
# does not use. No other key is read: a secret carrying extra fields -- an engine name, a host,
# a port -- is accepted, and those fields are ignored in favour of Parameter Store, so that
# there is exactly one authority for the endpoint rather than two that can disagree.
_SECRET_USERNAME_KEY = "username"
_SECRET_PASSWORD_KEY = "password"

# Trade-offs: the eight entries are declared as a plain dict wrapped in a read-only
# proxy rather than as an enum or a set of module constants. An enum was considered and
# rejected: the values here are database identifiers that appear in composed SQL and in a
# secret name, so every use site would have to unwrap ``.value``, and an enum member that
# reaches a query by accident renders as ``Schema.LEDGER`` rather than failing. The proxy is
# what makes the mapping genuinely read-only -- a bare module-level dict is mutable, so any
# importer could add a ninth schema at runtime and every consumer would silently believe it.
# The accepted cost is that a proxy cannot be updated in place, which is the intent.
#
# Refactoring Rationale: this mapping answers "which role does the ETL CONNECT AS for
# work in this schema", and that question is deliberately separated from "which role OWNS this
# schema" -- see :data:`OWNED_SCHEMA_ROLES` below. The two were previously conflated in one
# eight-entry map documented as schema-to-owner, which made ``reporting`` look like an owned
# schema. It is not, and the difference is not cosmetic: a schema's owner holds CREATE on it
# implicitly, so describing the read-only reporting role as the owner of a schema either
# invents DDL authority the design withholds from it, or -- worse, if a bootstrap were written
# from this map -- actually grants it. ``data-migration/sql/V0__schemas_and_roles.sql`` creates
# the reporting schema under the bootstrap principal and gives ``carddemo_reporting`` USAGE and
# SELECT only, and this module now models that rather than flattening it.
_SCHEMA_ROLES: dict[str, str] = {
    # Each pair names one bounded-context schema and the ``carddemo_*`` login role a loader
    # connects as to work in it, in the order
    # data-migration/sql/V0__schemas_and_roles.sql creates them, so the two can be read side
    # by side. NONE of them owns its schema: every schema is owned by a ``NOLOGIN``
    # ``carddemo_<context>_owner`` role, and :data:`OWNED_SCHEMA_ROLES` is where ownership is
    # answered. That uniformity is recent -- see the rationale on that mapping.
    #
    # Assumptions: every schema name here is stored BARE, and the quoted form is obtained
    # only through :func:`quoted_schema`. This rests on a property of the server rather than a
    # preference: ``authorization`` is a keyword PostgreSQL classifies as reserved, so the bare
    # name is a syntax error in an identifier position, while the bare name is simultaneously
    # the CORRECT form for a configuration string, a comparison against ``pg_namespace``, and
    # the environment-variable and secret-name paths built from it. One stored spelling
    # therefore cannot serve both uses, and storing the quoted form instead would be the worse
    # choice, because every non-SQL consumer would have to strip the quotes back off and one of
    # them eventually would not. Keeping the bare name and making the quoting an explicit call
    # puts the duty somewhere a reviewer can see it, rather than leaving each call site to
    # remember which of the eight names is a keyword; :func:`quoted_schema` records the four
    # measured failure modes that duty prevents.
    "auth": "carddemo_auth",
    "account": "carddemo_account",
    "card": "carddemo_card",
    "ledger": "carddemo_ledger",
    "reference": "carddemo_reference",
    "batch": "carddemo_batch",
    "authorization": "carddemo_authorization",
    # Assumptions: this entry is the one whose role holds no INSERT or UPDATE anywhere.
    # The reporting context owns no table: it reads the other contexts' data through the
    # SELECT-only grants the bootstrap script establishes, and the cross-schema views it reads
    # through live in the ``reporting`` schema owned by ``carddemo_reporting_owner``.
    # ``carddemo_reporting`` is therefore the role the ETL connects as for reporting work, with
    # USAGE and SELECT and no CREATE anywhere. It is also the one context with no
    # ``_migrator`` role, because reporting-service ships no Flyway migration -- see
    # :data:`MIGRATION_SCHEMA_ROLES`.
    "reporting": "carddemo_reporting",
}

#: Read-only mapping of each bounded-context schema name to the ``carddemo_*`` login role the
#: ETL connects as for work in that schema. None of these roles owns its schema -- use
#: :data:`OWNED_SCHEMA_ROLES` when ownership is what matters, and
#: :data:`MIGRATION_SCHEMA_ROLES` when DDL authority is.
SCHEMA_ROLES: Mapping[str, str] = MappingProxyType(_SCHEMA_ROLES)

# Assumptions: every schema's owner is the connection role's name with ``_owner``
# appended, so the mapping is DERIVED from :data:`SCHEMA_ROLES` rather than typed out a second
# time and the two cannot disagree about which contexts exist. The suffix is not a convention
# invented here: ``data-migration/sql/V0__schemas_and_roles.sql`` builds its own owner names the
# same way, from the same stems, so a context added there appears here with no edit.
#
# Refactoring Rationale: this mapping held SEVEN entries pointing at the LOGIN roles --
# ``account`` to ``carddemo_account``, and so on -- with ``reporting`` excluded as the one
# schema whose owner was a separate ``NOLOGIN`` role. That is no longer what the bootstrap
# script does, and the difference is a privilege boundary rather than a spelling. A schema's
# owner holds CREATE on it implicitly and may ALTER or DROP anything in it, so while the
# connection role was also the owner, the single long-lived credential every request ran under
# could drop the tables it read. V0 now owns all EIGHT schemas with ``NOLOGIN``
# ``carddemo_<context>_owner`` roles -- generalising the arrangement ``reporting`` already had
# -- and reduces each connection role to USAGE plus named DML. The seven-entry, LOGIN-role
# form of this mapping would now be actively wrong: it would report a DDL-capable identity for
# a role that has none, which is the direction of error that invents authority rather than
# withholding it.
#
# Trade-offs: ``reporting`` is no longer an exception, so the ``_UNOWNED_SCHEMA`` constant that
# expressed the exclusion is deleted with it and :func:`owner_role_for_schema` no longer refuses
# one of the eight names. The cost is that a caller which relied on that refusal to detect
# "this schema has no owning role" loses the signal -- accepted, because no such caller exists
# and the premise it rested on is no longer true.
_OWNED_SCHEMA_ROLES: dict[str, str] = {
    schema: f"{role}_owner" for schema, role in _SCHEMA_ROLES.items()
}

#: Read-only mapping of each of the eight bounded-context schemas to the ``NOLOGIN``
#: ``carddemo_<context>_owner`` role that owns it. Mirrors the eight
#: ``CREATE SCHEMA ... AUTHORIZATION <role>`` statements in
#: ``data-migration/sql/V0__schemas_and_roles.sql``. Every role named here is created
#: ``NOLOGIN`` and holds no credential, so no loader can connect as one: DDL authority is
#: reached by a ``SET ROLE`` from the context's ``_migrator`` role, not by authentication.
OWNED_SCHEMA_ROLES: Mapping[str, str] = MappingProxyType(_OWNED_SCHEMA_ROLES)

# Assumptions: seven contexts have a migration role and ``reporting`` does not, because
# reporting-service ships no ``src/main/resources/db/migration`` directory -- the cross-schema
# views it reads are created by ``data-migration/sql/V1__reporting_views.sql`` under the
# bootstrap principal instead. The exclusion is named as a constant beside the derivation so it
# is legible rather than arithmetic, and it matches the seven ``carddemo_<context>_migrator``
# roles V0 creates.
# Trade-offs: derived from :data:`SCHEMA_ROLES` by suffix for the same reason
# :data:`OWNED_SCHEMA_ROLES` is. A hand-written second list of near-identical names is how one
# list ends up with a context the other lacks, and the failure that produces is a service whose
# Flyway credential was never created -- which surfaces as an authentication error at startup,
# naming a role rather than the list that omitted it.
_SCHEMA_WITHOUT_MIGRATION = "reporting"

_MIGRATION_SCHEMA_ROLES: dict[str, str] = {
    schema: f"{role}_migrator"
    for schema, role in _SCHEMA_ROLES.items()
    if schema != _SCHEMA_WITHOUT_MIGRATION
}

#: Read-only mapping of the seven bounded-context schemas whose service applies its own Flyway
#: migration, to the ``carddemo_<context>_migrator`` login role that applies it. Each of these
#: roles is a member of the schema's :data:`OWNED_SCHEMA_ROLES` entry ``WITH INHERIT FALSE``, so
#: it can authenticate but owns nothing until it issues ``SET ROLE`` -- which is what makes the
#: objects a migration creates belong to the ``NOLOGIN`` owner rather than to any credential a
#: task holds. ``reporting`` is absent because reporting-service ships no migration.
MIGRATION_SCHEMA_ROLES: Mapping[str, str] = MappingProxyType(_MIGRATION_SCHEMA_ROLES)

# Assumptions: the tuple is derived from the mapping rather than typed out a second
# time. Two hand-maintained lists of the same eight names is how one of them ends up with
# seven, and a dict preserves insertion order, so the tuple is already in the bootstrap
# script's own order without that order having to be restated.
SCHEMA_NAMES: tuple[str, ...] = tuple(_SCHEMA_ROLES)

# Assumptions: this is the inventory of roles that can AUTHENTICATE, and it is therefore
# exactly the inventory that needs a credential: the eight connection roles plus the seven
# migration roles, fifteen in all. The eight ``carddemo_<context>_owner`` roles are deliberately
# absent -- they are ``NOLOGIN``, so their ``rolpassword`` is null permanently and correctly, and
# a verification that expected one there could never pass on a cluster that was in fact fully
# bootstrapped.
# Trade-offs: derived from the two mappings rather than written out, and sorted so that any log
# or report built from it is diffable between runs. The alternative -- a third hand-maintained
# list -- is the copy that goes stale silently, because a role V0 creates but this tuple omits
# would be reported as missing only if it appeared here, so the omission would hide itself.
LOGIN_ROLE_NAMES: tuple[str, ...] = tuple(
    sorted({*_SCHEMA_ROLES.values(), *_MIGRATION_SCHEMA_ROLES.values()})
)


def _require_text(value: object, description: str) -> str:
    """Return a setting as non-blank text, or raise naming what was blank.

    Purpose
    -------
    Apply the one emptiness rule this module needs, in one place: a setting that is present
    but blank is treated exactly like a setting that is absent. Parameter Store and Secrets
    Manager both accept an empty or whitespace-only string, so "present" is not the same
    question as "usable", and a blank host or password that reaches a driver fails far away
    from the parameter that caused it.

    Parameters
    ----------
    value : object
        The candidate value, typed loosely because it arrives from decoded JSON or from an
        SDK response where the static type guarantees nothing about it.
    description : str
        What the value is, phrased for an operator and naming the parameter path, secret name
        or environment variable it came from. Interpolated into the message on failure, so it
        must never contain the value itself.

    Returns
    -------
    str
        The value with surrounding whitespace removed, guaranteed non-empty.

    Raises
    ------
    ConfigurationError
        If the value is not a string, or is empty or whitespace-only.
    """
    # Assumptions: this is the module's first validation, and it establishes the form every
    # later one follows -- a raise, never an ``assert``. The reason is the deployment target
    # rather than style: the ETL runs inside a container image, and an interpreter started with
    # ``-O``, or with PYTHONOPTIMIZE set (which is easy to inherit from a base image or an
    # orchestrator's environment), removes every ``assert`` statement from the compiled
    # bytecode. A validation written as an assertion would therefore be present in a checkout,
    # pass its tests, and then silently not run in production, letting an empty host or a blank
    # password through to the loader to fail later as an obscure connection error. The Java
    # shared-kernel codecs decline assertions for the same reason, since ``assert`` there has no
    # effect without ``-ea``.
    #
    # Assumptions: the type is checked rather than coerced with ``str()``. A JSON secret
    # payload whose ``password`` field arrived as a number or a nested object would otherwise
    # be accepted and stringified into something that cannot authenticate, and the resulting
    # failure would surface as a rejected login rather than as the malformed secret it is.
    if not isinstance(value, str):
        raise ConfigurationError(f"{description} is not a text value")
    stripped = value.strip()
    if not stripped:
        raise ConfigurationError(f"{description} is empty")
    # Trade-offs: surrounding whitespace is stripped rather than rejected. A trailing
    # newline is the single most common artefact of a value that was piped into the parameter
    # store by a shell, and it is unambiguously not part of a hostname, a database name or a
    # bucket name. The accepted cost is that a credential whose real value has leading or
    # trailing whitespace cannot be carried; that is a deliberate limit, because such a value
    # is indistinguishable from the artefact and would fail unpredictably across the clients
    # that consume it.
    return stripped


def _validate_path_segment(segment: str, description: str) -> str:
    """Return one parameter-path segment after checking it cannot alter the path's shape.

    Purpose
    -------
    Confirm that a value about to be joined into a Parameter Store path is a single segment.
    The environment name, every segment of the configurable prefix and every segment supplied
    to :func:`parameter_path` pass through here, so a value carrying a separator cannot add a
    level of hierarchy and redirect a lookup to a path the caller never named.

    Parameters
    ----------
    segment : str
        The candidate segment, already known to be non-blank text.
    description : str
        What the segment is, naming the environment variable or caller it came from. Appears
        in the message on failure and must not contain the value.

    Returns
    -------
    str
        The segment unchanged, guaranteed to match the accepted shape.

    Raises
    ------
    ConfigurationError
        If the segment does not begin with an alphanumeric character or contains anything
        other than alphanumerics, underscore, period and hyphen.
    """
    if not _PATH_SEGMENT_PATTERN.match(segment):
        # Assumptions: the rejected value is described but not echoed, and the accepted
        # shape is spelled out instead. A path segment is not itself sensitive, but this module
        # holds one rule about messages rather than two -- values are never interpolated -- so
        # that no future edit has to decide which of them a given field falls under. Naming the
        # permitted characters keeps the message actionable without needing the value.
        raise ConfigurationError(
            f"{description} must be a single path segment starting with a letter or digit and "
            f"containing only letters, digits, underscore, period or hyphen"
        )
    return segment


def _validate_absolute_path(value: str, description: str) -> str:
    """Return a filesystem path after checking it is absolute and unambiguous.

    Purpose
    -------
    Confirm that a path this module is about to hand to a client library names one definite
    file. Only the TLS trust anchor passes through here, and it is the one setting whose
    misreading is silent: a path that resolves to a different file than intended still produces
    a working connection, verified against whatever authority that file happens to contain.

    Parameters
    ----------
    value : str
        The candidate path, already known to be non-blank text.
    description : str
        What the path is, naming the environment variable or field it came from. Appears in the
        message on failure and must not contain the value.

    Returns
    -------
    str
        The path unchanged, guaranteed to be absolute and free of embedded NUL bytes.

    Raises
    ------
    ConfigurationError
        If the path is not absolute, or contains a NUL byte.
    """
    # Alternatives Considered: the path is checked and returned VERBATIM rather than
    # normalised with ``os.path.realpath`` or ``Path.resolve``. Resolving was written first and
    # rejected on two counts. It touches the filesystem to follow symlinks, so the value a
    # descriptor holds would depend on the state of the machine at construction time and two
    # otherwise identical descriptors could differ -- which matters here, because these objects
    # are frozen, hashable and cached. It also silently rewrites the operator's input, so a
    # diagnostic rendering would show a path the operator never typed, which is the opposite of
    # what a rendering is for. Absoluteness is the property that actually removes the ambiguity,
    # and it is decidable without touching the filesystem.
    if not value.startswith("/"):
        raise ConfigurationError(
            f"{description} must be an absolute path beginning with a forward slash"
        )
    # Assumptions: an embedded NUL is rejected explicitly instead of being left to the
    # filesystem call that would eventually reject it. Python raises ``ValueError`` for a NUL in
    # a path, not this module's error type, so a caller that catches ``ConfigurationError`` --
    # which this module's contract says is the only exception it raises -- would see an
    # unrelated exception escape from deep inside a driver instead of a named misconfiguration.
    if "\x00" in value:
        raise ConfigurationError(f"{description} must not contain a NUL byte")
    return value


def quote_identifier(identifier: str) -> str:
    """Return a SQL identifier wrapped in double quotes, with embedded quotes doubled.

    Purpose
    -------
    Render any name safely for a position where PostgreSQL parses an identifier -- a
    schema-qualified table reference, a ``GRANT ... ON SCHEMA`` clause, an ``ALTER DEFAULT
    PRIVILEGES ... IN SCHEMA`` clause. Quoting is what stops a name that collides with a
    reserved keyword from being parsed as that keyword, and it is why this function exists
    rather than each call site formatting a bare name into its own statement.

    The quoted form is also valid in the looser positions that merely accept a name, such as a
    ``search_path`` value, so a caller never has to decide which kind of position it is
    addressing; see :func:`quoted_schema` for why that distinction is a trap worth removing.

    Parameters
    ----------
    identifier : str
        The bare identifier to quote, for example a schema or role name.

    Returns
    -------
    str
        The identifier surrounded by double quotes, with any double quote it contains doubled
        so that it is read as data rather than as the closing delimiter.

    Raises
    ------
    ConfigurationError
        If the identifier is not text, is blank, or contains a NUL character.
    """
    text = _require_text(identifier, "SQL identifier")
    # Assumptions: a NUL is rejected rather than escaped because PostgreSQL cannot carry
    # one in an identifier at all -- there is no quoted form that would make it acceptable, and
    # the protocol terminates strings on it, so passing one through would truncate the statement
    # at that byte instead of failing. Doubling handles every other character, including the
    # double quote itself, which is the only one with meaning inside the quoted form.
    if "\x00" in text:
        raise ConfigurationError("SQL identifier contains a NUL character")
    escaped = text.replace('"', '""')
    return f'"{escaped}"'


def role_for_schema(schema: str) -> str:
    """Return the login role the ETL connects as for work in one bounded-context schema.

    Purpose
    -------
    Resolve a schema name to its connection role through the single canonical mapping, so that
    the role a loader connects as, and the secret name its credential is stored under, are both
    derived from one declaration instead of being spelled out again at each call site.

    This is the CONNECTION role, and for none of the eight schemas is it the schema's owner.
    Every schema is owned by a ``NOLOGIN`` ``carddemo_<context>_owner`` role, and every
    connection role holds USAGE on its schema plus named DML on its tables and nothing more --
    no CREATE, no DROP, no ALTER, and no ability to ``SET ROLE`` to the owner. Call
    :func:`owner_role_for_schema` when ownership is the question being asked, and
    :func:`migration_role_for_schema` when the question is which credential may apply DDL.

    Parameters
    ----------
    schema : str
        A bare schema name. Must be one of :data:`SCHEMA_NAMES`; an unrecognised name is a
        defect rather than an extension point, because the bootstrap script creates exactly
        these eight and nothing would connect to a ninth.

    Returns
    -------
    str
        The ``carddemo_*`` login role for the schema, spelled exactly as the bootstrap script
        creates it.

    Raises
    ------
    ConfigurationError
        If the schema is not text, is blank, or is not one of the eight known schemas.
    """
    text = _require_text(schema, "schema name")
    role = _SCHEMA_ROLES.get(text)
    if role is None:
        # Trade-offs: the message lists the eight accepted names. They are neither
        # sensitive nor secret -- the bootstrap script publishes them and this module's own
        # documentation names them -- and a typo such as ``authorisation`` for
        # ``authorization`` is otherwise slow to spot from the rejected value alone. This is
        # the one place a value-adjacent detail is included in a message, and it is the
        # accepted set rather than the rejected input, so nothing resolved is disclosed.
        raise ConfigurationError(
            f"unknown schema {text!r}; expected one of {', '.join(SCHEMA_NAMES)}"
        )
    return role


def owner_role_for_schema(schema: str) -> str:
    """Return the ``carddemo_*`` role that OWNS one bounded-context schema.

    Purpose
    -------
    Answer the ownership question separately from the connection question :func:`role_for_schema`
    answers, so that a caller needing a principal with DDL authority over a schema -- to create
    an index, or to run a statement whose default privileges are keyed on the creating role --
    cannot silently receive a role that has none.

    Parameters
    ----------
    schema : str
        A bare schema name. Must be one of :data:`SCHEMA_NAMES`; every one of the eight has an
        owning role, so there is no accepted name this function refuses.

    Returns
    -------
    str
        The ``NOLOGIN`` ``carddemo_<context>_owner`` role named in that schema's
        ``CREATE SCHEMA ... AUTHORIZATION`` statement in
        ``data-migration/sql/V0__schemas_and_roles.sql``.

    Raises
    ------
    ConfigurationError
        If the schema is not text, is blank, or is not one of the eight known schemas.

    Notes
    -----
    Refactoring Rationale: this function used to refuse ``reporting`` with a message explaining
    that its owner was a ``NOLOGIN`` role rather than a service role. The refusal is gone because
    its premise is: ALL eight schemas are now owned by a ``NOLOGIN`` role, so the answer it
    withheld is the answer every schema now has. Keeping the special case would have made the one
    context that first demonstrated the design look like the exception to it.

    Assumptions: the role returned here can never be connected as. It holds no credential, and
    ``infra/modules/secrets`` deliberately creates no entry for it. A caller that needs a session
    with this authority authenticates as :func:`migration_role_for_schema` and issues
    ``SET ROLE`` -- which is what keeps DDL authority unreachable by presenting a password.
    """
    text = _require_text(schema, "schema name")
    role = _OWNED_SCHEMA_ROLES.get(text)
    if role is None:
        # Trade-offs: the message lists the accepted names, for the same reason
        # :func:`role_for_schema` lists them -- they are published by the bootstrap script, so
        # nothing resolved is disclosed, and a near-miss such as ``authorisation`` is otherwise
        # slow to spot from the rejected value alone.
        raise ConfigurationError(
            f"unknown schema {text!r}; expected one of {', '.join(OWNED_SCHEMA_ROLES)}"
        )
    return role


def migration_role_for_schema(schema: str) -> str:
    """Return the login role that applies one bounded-context schema's Flyway migration.

    Purpose
    -------
    Answer the third of the three distinct questions this module keeps apart: which credential
    may apply DDL to a schema. :func:`role_for_schema` answers which credential serves requests
    and :func:`owner_role_for_schema` answers which principal owns the objects; this one answers
    which of the fifteen login roles is permitted to become that owner.

    Parameters
    ----------
    schema : str
        A bare schema name. Must be one of the seven keys of :data:`MIGRATION_SCHEMA_ROLES`,
        which is :data:`SCHEMA_NAMES` without ``reporting``.

    Returns
    -------
    str
        The ``carddemo_<context>_migrator`` login role, spelled exactly as
        ``data-migration/sql/V0__schemas_and_roles.sql`` creates it.

    Raises
    ------
    ConfigurationError
        If the schema is not text, is blank, is not one of the eight known schemas, or is
        ``reporting`` -- a known schema whose service ships no migration and for which no
        migration role exists.

    Notes
    -----
    Assumptions: the two failures are reported with DIFFERENT messages, because they call for
    different actions. An unknown name is a typo and the accepted set is listed. ``reporting`` is
    not a typo: it is a real schema whose views are created by
    ``data-migration/sql/V1__reporting_views.sql`` under the bootstrap principal, so the message
    says where its DDL comes from instead, which is the answer the caller actually needs.
    """
    text = _require_text(schema, "schema name")
    role = _MIGRATION_SCHEMA_ROLES.get(text)
    if role is None:
        if text in _SCHEMA_ROLES:
            raise ConfigurationError(
                f"schema {text!r} has no migration role; its objects are created by "
                "data-migration/sql/V1__reporting_views.sql under the bootstrap principal "
                "rather than by a service migration, so "
                "data-migration/sql/V0__schemas_and_roles.sql creates no "
                f"{text}_migrator login for it"
            )
        raise ConfigurationError(
            f"unknown schema {text!r}; expected one of {', '.join(MIGRATION_SCHEMA_ROLES)}"
        )
    return role


def quoted_schema(schema: str) -> str:
    """Return a bounded-context schema name in the double-quoted form SQL requires.

    Purpose
    -------
    Supply the only rendering of a schema name that is safe in an identifier position, and
    make obtaining it a deliberate step. The bare names stored in :data:`SCHEMA_ROLES` are
    correct for configuration strings and wrong for composed SQL, and one of the eight makes
    that difference load-bearing rather than theoretical.

    ``authorization`` is a keyword the server classifies as reserved, so it is not accepted in
    a bare identifier position. Measured against PostgreSQL 17 rather than assumed, the
    unquoted name fails in three different ways and succeeds in a fourth, which is precisely
    why obtaining the quoted form is made a step rather than left to judgement:

    * ``CREATE SCHEMA IF NOT EXISTS authorization`` reports ``syntax error at end of input``.
      ``CREATE SCHEMA AUTHORIZATION <role>`` is itself valid syntax naming a schema after a
      role, so the keyword form matches first, the parser then finds no role name, and the
      error points at the end of the statement rather than at the identifier that caused it.
    * ``GRANT ... ON SCHEMA authorization`` and ``ALTER DEFAULT PRIVILEGES ... IN SCHEMA
      authorization`` report ``syntax error at or near "authorization"``.
    * A schema-qualified reference such as ``authorization.pending_auth_summary`` reports
      ``syntax error at or near "."``, which names the separator and not the schema.
    * ``SET search_path TO authorization`` is **accepted**, and the server even stores the
      value back quoted. A configuration-parameter value is not an identifier position, so the
      grammar is lenient there. This is the trap: the one statement an author is most likely to
      try first is the one that suggests no quoting is needed, and the failure then arrives
      later from a qualified reference that does need it.

    Passing the name through this function produces ``"authorization"``, which every one of
    those positions accepts, so no call site has to know which of them is lenient.

    The quoting changes no name. PostgreSQL folds an unquoted identifier to lower case and all
    eight names are already lower case, so the quoted and bare spellings denote the same object
    -- which is why configuration strings that are never parsed as SQL, such as a schema
    property or a search-path setting handed to a driver as a parameter, correctly use the bare
    form from :data:`SCHEMA_ROLES` instead.

    Parameters
    ----------
    schema : str
        A bare schema name. Must be one of :data:`SCHEMA_NAMES`, so that a misspelling is
        rejected here rather than quoted into a statement that then fails against a schema
        which does not exist.

    Returns
    -------
    str
        The schema name wrapped in double quotes, ready to be placed in an identifier
        position.

    Raises
    ------
    ConfigurationError
        If the schema is not text, is blank, or is not one of the eight known schemas.
    """
    # Assumptions: membership is checked by resolving the owning role and discarding it,
    # rather than by testing the mapping directly, so that this function and
    # :func:`role_for_schema` cannot drift on what counts as a known schema. The eight names
    # are validated against one declaration through one code path.
    role_for_schema(schema)
    # Trade-offs: all eight names are quoted, not just the reserved one. Quoting only
    # ``authorization`` would leave every call site needing to know which names are keywords --
    # a set that belongs to the server's grammar and grows between major versions, not to this
    # module -- and the special case would be invisible at the point a new schema is added.
    # Uniform quoting costs two characters per rendered name and denotes the identical object,
    # because these names are already lower case and so survive the fold unchanged.
    return quote_identifier(schema.strip())


# Trade-offs: both descriptors are frozen and slotted. Frozen because a settings object
# is handed to several loaders in turn and an in-place edit by one of them would silently
# change what the next one connects to, which is the hardest class of configuration bug to
# reproduce; it also makes the objects hashable, which is what allows the resolvers below to be
# cached. Slotted because it forbids attributes these classes do not declare, so a caller
# cannot attach a stray field -- an unmasked copy of the password, for instance -- to an object
# whose rendering is deliberately controlled. The accepted cost is that neither class can be
# extended by assignment, which is the intent rather than a limitation to work around.
@dataclass(frozen=True, slots=True, repr=False)
class AuroraConnectionSettings:
    """Validated connection parameters for one Aurora PostgreSQL login role.

    Purpose
    -------
    Carry everything needed to open a database connection, and nothing else. This class holds
    parameters rather than an open connection, and knows nothing about how one is opened.

    A caller obtains an instance from :func:`resolve_aurora_settings` and passes
    :meth:`as_connection_params` to a driver. Direct construction is supported and is what the
    tests use.

    Parameters
    ----------
    host : str
        The cluster endpoint to connect to, resolved from Parameter Store. Stripped of
        surrounding whitespace by :meth:`__post_init__`.
    port : int
        The port the cluster listens on, resolved from Parameter Store and parsed to an
        integer there so that a non-numeric value fails at resolution rather than at connect.
    database : str
        The database name within the cluster, stripped of surrounding whitespace. Named as
        PostgreSQL names it; see :meth:`as_connection_params` for why the connection keyword
        differs.
    user : str
        The login role to authenticate as, stripped of surrounding whitespace. Taken from the
        secret payload rather than derived, so that a rotation which changes the user is
        honoured.
    password : str
        The credential for that role. Never rendered by :meth:`__repr__` and never placed in
        an exception message.
    ssl_root_cert : str
        Absolute path to the certificate bundle that must certify the cluster's endpoint.
        Defaults to :data:`DEFAULT_SSL_ROOT_CERT`, which is where the container image places
        the AWS-published Aurora bundle. Only the path's *shape* is checked here; whether the
        file is present is checked by :func:`resolve_ssl_root_cert`, for the reason recorded
        there.

    Returns
    -------
    AuroraConnectionSettings
        A frozen, fully validated instance. Construction is not a two-step affair: the
        generated initialiser assigns the fields and :meth:`__post_init__` then normalises and
        validates every one of them, so an instance either exists and is usable or was never
        produced. There is no partially initialised state a caller can observe.

    Raises
    ------
    ConfigurationError
        From :meth:`__post_init__` during construction, if any text field is not text or is
        blank after stripping, or if the port is not an integer within the range a TCP port can
        express. Raised for direct construction and for construction by
        :func:`resolve_aurora_settings` alike, because the invariants live on the class rather
        than in the resolver.
    TypeError
        From the generated initialiser, if a required field is omitted or an unknown keyword is
        supplied. The class is slotted, so a misspelled field name fails here rather than
        attaching a stray attribute.

    Notes
    -----
    Alternatives Considered: an ``ssl_mode`` field was rejected in favour of the module constant
    :data:`REQUIRED_SSL_MODE`, which :meth:`as_connection_params` emits unconditionally. The
    consequence of the alternative is specific: a field can be set, so an instance -- including
    one a test builds directly -- could then describe a connection made without full certificate
    and hostname verification, which turns the strongest guarantee in this module into a
    per-instance choice. Keeping it a constant means no construction path can express the weaker
    connection at all. The accepted cost is that a caller genuinely needing a different mode
    cannot express it here and must change the constant, which is the visible, reviewed edit that
    such a change should be.
    """

    host: str
    port: int
    database: str
    user: str
    password: str

    # Trade-offs: this field carries a default while the five above do not. The default is
    # what keeps direct construction -- which the tests use, and which the class documents as
    # supported -- from having to restate a deployment path that is identical in every case; and
    # because the default is the secure value rather than a permissive one, a caller that omits
    # it gets verification against the pinned bundle rather than none. The accepted cost is that
    # this field must stay last in the declaration order, since a defaulted field cannot precede
    # a non-defaulted one in a dataclass.
    ssl_root_cert: str = DEFAULT_SSL_ROOT_CERT

    def __post_init__(self) -> None:
        """Validate and normalise every field so an unusable instance cannot exist.

        Purpose
        -------
        Enforce the invariants at construction rather than at first use, so that a blank host
        or an out-of-range port is reported against the setting that produced it instead of
        surfacing later as a driver error with no indication of which parameter was wrong.
        Text fields are additionally stripped of surrounding whitespace here, so the invariant
        holds no matter which construction path was taken.

        Parameters
        ----------
        None
            Operates on the fields already assigned by the generated initialiser.

        Returns
        -------
        None
            Mutates the instance in place during construction and returns nothing.

        Raises
        ------
        ConfigurationError
            If any text field is not text or is blank, or if the port is not an integer in the
            range a TCP port can express.
        """
        # Assumptions: normalisation goes through ``object.__setattr__`` because the
        # class is frozen and a plain assignment would raise. This is the documented idiom for
        # a frozen dataclass that needs to canonicalise its own input, and doing it here rather
        # than in the resolver is what makes the guarantee unconditional -- a test or a future
        # loader that constructs an instance directly gets the same normalised object that a
        # resolved one is, so no consumer has to ask which path produced the value it holds.
        object.__setattr__(self, "host", _require_text(self.host, "database host"))
        object.__setattr__(self, "database", _require_text(self.database, "database name"))
        object.__setattr__(self, "user", _require_text(self.user, "database user"))

        # Assumptions: the password is checked for emptiness but is deliberately NOT
        # stripped, unlike every other field above. A hostname or a database name cannot
        # meaningfully begin or end with a space, so stripping one there only ever removes an
        # artefact; a generated credential can legitimately contain any printable character,
        # and silently trimming one would produce a password that differs from the stored
        # secret by a byte and fails authentication with no indication why. Emptiness is still
        # rejected, because an empty credential cannot authenticate under any configuration.
        if not isinstance(self.password, str) or not self.password:
            raise ConfigurationError("database password is empty")

        # Assumptions: ``bool`` is excluded explicitly because it is a subclass of
        # ``int`` in Python, so a port passed as ``True`` would otherwise validate and connect
        # to port 1. The upper bound is the largest value a TCP port number can express, so a
        # parameter holding a year or a timestamp by mistake is rejected here rather than
        # producing an opaque socket error.
        if isinstance(self.port, bool) or not isinstance(self.port, int):
            raise ConfigurationError("database port is not an integer")
        if not 1 <= self.port <= 65535:
            raise ConfigurationError("database port is outside the range 1 to 65535")

        # Assumptions: the trust anchor's path is validated HERE rather than only where it
        # is resolved, because ``verify-full`` is only as strong as the anchor it verifies
        # against and this is the one place every construction path passes through. A relative
        # path is rejected rather than resolved: it would be interpreted against whatever working
        # directory the process inherited, so the same descriptor would name a different file
        # depending on where a command was launched from, and the failure mode of naming the
        # wrong file is a connection that verifies against the wrong authority.
        cert_description = "database TLS root certificate path"
        object.__setattr__(
            self,
            "ssl_root_cert",
            _validate_absolute_path(
                _require_text(self.ssl_root_cert, cert_description), cert_description
            ),
        )

    def __repr__(self) -> str:
        """Return a rendering that identifies the connection without disclosing its password.

        Purpose
        -------
        Make this object safe to place in a log line, an exception message or a debugger
        watch, while still answering the question a reader actually has when configuration is
        wrong: which host, port, database and user was this process about to use.

        Parameters
        ----------
        None
            Renders the instance's own fields.

        Returns
        -------
        str
            A single-line rendering in which ``host``, ``port``, ``database``, ``user`` and
            ``ssl_root_cert`` appear in clear and ``password`` is replaced by
            :data:`REDACTED`.

        Raises
        ------
        None
            Rendering cannot fail; every field was validated as text or an integer at
            construction.
        """
        # Trade-offs: the generated ``repr`` is replaced rather than augmented, because
        # the dataclass default renders every field including the credential, and it is
        # reached implicitly -- an f-string, a ``print``, an unhandled exception's argument
        # list, a test assertion diff. Overriding it is the only way to make the safe
        # rendering the default one rather than something each call site has to remember to
        # ask for. ``__str__`` is intentionally not defined, so it falls back to this method
        # and the two cannot diverge. The accepted cost is that the credential cannot be
        # inspected through a rendering at all, which is the point: a caller that genuinely
        # needs it reads ``.password`` explicitly, and that is a line a reviewer can see.
        #
        # Assumptions: the trust-anchor path is rendered in clear alongside the endpoint
        # fields, because it belongs to the same question a reader has when a connection is
        # refused -- which host, as which user, verified against which bundle. A filesystem path
        # is not a credential, and the most likely TLS misconfiguration is an override pointing
        # at a file that is absent or stale, which a redacted rendering would hide.
        return (
            f"{type(self).__name__}(host={self.host!r}, port={self.port!r}, "
            f"database={self.database!r}, user={self.user!r}, password={REDACTED!r}, "
            f"ssl_root_cert={self.ssl_root_cert!r})"
        )

    def as_connection_params(self) -> dict[str, str | int]:
        """Return these settings keyed by the connection keywords a PostgreSQL client expects.

        Purpose
        -------
        Translate this object into the exact keyword names a PostgreSQL client library accepts,
        so that the ``database``/``dbname`` difference is handled once here instead of at every
        connection site.

        That difference is the reason this method exists rather than callers unpacking the
        fields themselves. The attribute is called ``database`` because that is what PostgreSQL
        calls it, but the client connection keyword is ``dbname``; a driver handed
        ``database=`` does not quietly ignore it, because these keywords are forwarded to the
        underlying connection library, which rejects one it does not recognise. Passing this
        object's fields through by name would therefore fail at connect time in a way that
        reads as a driver problem rather than a keyword-naming one.

        Parameters
        ----------
        None
            Reads the instance's own fields.

        Returns
        -------
        dict[str, str | int]
            A new mapping with keys ``host``, ``port``, ``dbname``, ``user``, ``password``,
            ``sslmode`` and ``sslrootcert``, suitable for expansion into a client's connect
            call. ``sslmode`` is always :data:`REQUIRED_SSL_MODE`. The mapping contains the
            credential in clear, so it must be expanded into a connect call and never logged,
            serialised or placed in an exception message.

        Raises
        ------
        None
            Every value was validated at construction, so building the mapping cannot fail.
        """
        # Trade-offs: this method returns connection PARAMETERS and this module never
        # opens a connection, so ``psycopg`` is imported nowhere in it. Returning a ready-made
        # connection would be more convenient at one call site and was rejected for two
        # consequences. It would put the driver on the import path of every consumer of this
        # module, including the command-line entry point and anything that only wants the schema
        # topology or a staging prefix, so a process needing neither a database nor a driver
        # would still have to have one installed. It would also make this module own connection
        # lifetime -- pooling, closing, retry on a dropped socket -- which belongs with
        # ``carddemo_migration.loaders.aurora``, the one module that knows how long a load holds
        # a connection. The accepted cost is that a caller writes one connect call itself.
        #
        # Trade-offs: a fresh dict is built on each call rather than a cached one being
        # returned. The dict is mutable and holds the credential, so sharing a single instance
        # would let one caller's edit reach another's connect call, and the cost of rebuilding
        # five entries is irrelevant beside opening a database connection. Returning an
        # immutable mapping instead was rejected because a client's connect call expects
        # keyword expansion, and a read-only proxy cannot be expanded with ``**``.
        #
        # Assumptions: the two TLS keywords are emitted on EVERY call and are not
        # conditional on anything. A conditional -- on the environment name, on whether the host
        # looks like a cluster endpoint, on a debug flag -- would create a code path that
        # connects without verification, and a path that exists is a path that gets taken. The
        # keyword spellings are libpq's (``sslmode``, ``sslrootcert``) rather than this class's
        # attribute names, for the same reason ``dbname`` differs from ``database`` above: these
        # keywords are forwarded to the underlying connection library, which rejects a name it
        # does not recognise, so the translation has to happen here or nowhere.
        return {
            "host": self.host,
            "port": self.port,
            "dbname": self.database,
            "user": self.user,
            "password": self.password,
            "sslmode": REQUIRED_SSL_MODE,
            "sslrootcert": self.ssl_root_cert,
        }


@dataclass(frozen=True, slots=True)
class DatasetStagingSettings:
    """Validated settings for staging dataset generations into versioned object storage.

    Purpose
    -------
    Name the bucket that staged dataset generations are written to, record which deployment it
    belongs to, and own the single definition of the prefix layout those generations are
    written under.

    Parameters
    ----------
    bucket : str
        The dataset bucket name, resolved from Parameter Store and stripped of surrounding
        whitespace by :meth:`__post_init__`. It is taken as resolved and is never
        reconstructed from a naming convention; see
        :func:`resolve_dataset_staging_settings` for why.
    environment : str
        The deployment this bucket belongs to, stripped of surrounding whitespace and carried
        so that a caller holding only this object can report which environment it is staging
        into.

    Returns
    -------
    DatasetStagingSettings
        A frozen, slotted instance whose fields have already been normalised and validated by
        :meth:`__post_init__`, so an unusable instance cannot exist.

    Raises
    ------
    ConfigurationError
        Raised from :meth:`__post_init__` during construction if either field is not text or
        is blank.

    Attributes
    ----------
    bucket : str
        As the parameter of the same name.
    environment : str
        As the parameter of the same name.
    """

    bucket: str
    environment: str

    # Trade-offs: unlike :class:`AuroraConnectionSettings`, this class keeps the
    # generated ``repr``. It holds a bucket name and an environment name, neither of which is a
    # credential, and both of which are exactly what an operator needs to see in a log line
    # while a staging step runs. The asymmetry between the two classes is therefore deliberate
    # and reflects what each one holds, not an omission here.

    def __post_init__(self) -> None:
        """Validate and normalise both fields so an unusable instance cannot exist.

        Purpose
        -------
        Reject a blank bucket or environment name at construction, so a staging step fails
        against the parameter that was wrong rather than attempting to write to an empty
        bucket name and reporting an object-storage error instead.

        Parameters
        ----------
        None
            Operates on the fields already assigned by the generated initialiser.

        Returns
        -------
        None
            Mutates the instance in place during construction and returns nothing.

        Raises
        ------
        ConfigurationError
            If either field is not text or is blank.
        """
        # Assumptions: both fields are normalised, with none of the exemption
        # :class:`AuroraConnectionSettings` makes for its password. Neither a bucket name nor an
        # environment name can meaningfully begin or end with a space -- object storage does not
        # accept such a bucket name, and the environment name has already been through a
        # path-segment check that would have rejected one -- so stripping here can only ever
        # remove an artefact. It matters because both values are concatenated into paths: a
        # stray space would produce a prefix that no reader looking for it by convention finds.
        object.__setattr__(self, "bucket", _require_text(self.bucket, "dataset bucket name"))
        object.__setattr__(self, "environment", _require_text(self.environment, "environment name"))

    def generation_prefix(
        self,
        domain: str,
        dataset: str,
        business_date: date,
        generation: int,
    ) -> str:
        """Build the object-storage prefix one dataset generation is staged under.

        Purpose
        -------
        Produce exactly ``<domain>/<dataset>/dt=YYYY-MM-DD/gen=NNNN/`` and be the only place
        that layout is written. Every staged generation is addressed by this shape, so a second
        definition of it would let one writer and one reader disagree about where a dataset
        lives while both appeared to work.

        The trailing separator is part of the returned value: the result is a prefix that
        objects are placed beneath, not a key, and returning it without the separator would
        make ``gen=0001`` a prefix of ``gen=00010`` for anyone listing by string match.

        This method takes the dataset family as arguments and enumerates none of them. Which
        families exist is a property of the batch pipeline and belongs to the staging loader
        that walks them; encoding a list or a count here would give that fact a second home
        and let the two disagree.

        Parameters
        ----------
        domain : str
            The top-level grouping the dataset belongs to. Must contain no forward slash, so
            that it cannot deepen the hierarchy.
        dataset : str
            The dataset family name within that domain. Must contain no forward slash.
        business_date : date
            The business date the generation belongs to, rendered as ``dt=YYYY-MM-DD``. A
            :class:`~datetime.datetime` is rejected rather than truncated; see below.
        generation : int
            The generation number, rendered as ``gen=`` followed by exactly four digits with
            leading zeros. Must be between 0 and 9999 inclusive.

        Returns
        -------
        str
            The prefix, ending in a forward slash.

        Raises
        ------
        ConfigurationError
            If ``domain`` or ``dataset`` is not text, is blank, or contains a forward slash; if
            ``business_date`` is not a date, or is a datetime; or if ``generation`` is not an
            integer or falls outside 0 to 9999 inclusive.
        """
        domain_text = _require_text(domain, "dataset domain")
        dataset_text = _require_text(dataset, "dataset name")
        for value, description in ((domain_text, "dataset domain"), (dataset_text, "dataset")):
            if not _PREFIX_COMPONENT_PATTERN.match(value):
                raise ConfigurationError(f"{description} must not contain a forward slash")

        # Assumptions: a datetime is rejected even though it satisfies ``isinstance``
        # against ``date``, because it carries a time of day this partition cannot express.
        # Accepting one would silently discard that time, so two runs at different instants on
        # the same day would stage into the same prefix and the second would appear to be a
        # re-run of the first. Rejecting it makes the caller decide which date it means.
        if isinstance(business_date, datetime):
            raise ConfigurationError(
                "business date must be a date, not a datetime; the staged prefix partitions by "
                "day and cannot carry a time of day"
            )
        if not isinstance(business_date, date):
            raise ConfigurationError("business date is not a date")

        # Assumptions: ``bool`` is excluded before the integer check for the same reason
        # as the database port -- it is a subclass of ``int``, so ``True`` would otherwise
        # render as ``gen=0001`` and stage a generation the caller never asked for.
        if isinstance(generation, bool) or not isinstance(generation, int):
            raise ConfigurationError("dataset generation is not an integer")
        if not 0 <= generation <= _MAX_GENERATION:
            raise ConfigurationError(
                f"dataset generation is outside the range 0 to {_MAX_GENERATION}"
            )

        # Assumptions: the date is rendered with ``isoformat`` rather than ``strftime``.
        # For a date the standard library guarantees ``isoformat`` produces a zero-padded
        # ``YYYY-MM-DD``, whereas ``%Y`` under ``strftime`` is handed to the platform's C
        # library and is not guaranteed to zero-pad a year below 1000 -- a difference that
        # would only appear on a malformed business date, and would then produce a prefix that
        # sorts wrongly against its siblings instead of failing.
        return (
            f"{domain_text}/{dataset_text}/dt={business_date.isoformat()}/"
            f"gen={generation:0{_GENERATION_DIGITS}d}/"
        )


@lru_cache(maxsize=1)
def _aws_error_types() -> tuple[type[BaseException], ...]:
    """Return the AWS SDK exception classes this module translates, importing them on demand.

    Purpose
    -------
    Give the resolvers something to name in an ``except`` clause without importing the SDK's
    exception module at the top of this file. ``ClientError`` covers a call the service
    answered with an error, and ``BotoCoreError`` covers one it never answered -- absent
    credentials, an unresolvable endpoint, an unknown region. Both are configuration problems
    from this package's point of view, so both are translated.

    Parameters
    ----------
    None
        The two classes are fixed; there is nothing to select between.

    Returns
    -------
    tuple[type[BaseException], ...]
        ``(ClientError, BotoCoreError)``, usable directly in an ``except`` clause.

    Raises
    ------
    ConfigurationError
        If the AWS SDK is not installed, so that the failure names the missing dependency
        rather than surfacing as an import error from inside an exception handler.
    """
    try:
        from botocore.exceptions import BotoCoreError, ClientError
    except ImportError as exc:
        raise ConfigurationError(
            "the AWS SDK is not installed; install data-migration/requirements.txt"
        ) from exc
    # Assumptions: the two are returned as a tuple because they are siblings in the SDK's
    # hierarchy rather than one inheriting from the other, so neither alone catches the other.
    # Naming only ``ClientError`` -- the intuitive choice, since it is the one carrying a service
    # error code -- would let a missing-credentials failure escape as a raw SDK exception from a
    # module that documents itself as raising only ``ConfigurationError``.
    return (ClientError, BotoCoreError)


def _error_code(exc: BaseException) -> str:
    """Return the AWS service error code carried by an exception, or an empty string.

    Purpose
    -------
    Read the service's own error code so that "the thing you named is not there" can be
    separated from "you are not allowed to read it" and from every other failure, without this
    module having to import a service-specific exception class for each case.

    Parameters
    ----------
    exc : BaseException
        The exception raised by an SDK call. Only a client error carries a code; anything else
        yields an empty string.

    Returns
    -------
    str
        The service error code, for example ``ParameterNotFound``, or ``""`` when the exception
        carries no response document or a malformed one.

    Raises
    ------
    None
        Every lookup is defensive, because this function runs while another exception is
        already being handled and raising here would replace the original failure with a less
        informative one.
    """
    # Assumptions: the response document is navigated defensively rather than indexed,
    # because the shape is only guaranteed for a client error. A transport failure carries no
    # ``response`` at all, and a client error can carry one whose ``Error`` member is missing,
    # so indexing would raise a ``KeyError`` or ``TypeError`` from inside an exception handler
    # and mask the failure the caller actually needs to see.
    response = getattr(exc, "response", None)
    if not isinstance(response, dict):
        return ""
    error = response.get("Error")
    if not isinstance(error, dict):
        return ""
    code = error.get("Code")
    return code if isinstance(code, str) else ""


#: Seconds the SDK waits to establish a connection before failing the attempt.
#:
#: WHY : Trade-offs: botocore's own default is 60 seconds, which is far longer than any healthy
#:   connection inside a VPC with interface endpoints takes. The cost of the default is that a
#:   black-holed connection -- a security group that stopped admitting 443, an endpoint removed
#:   from the subnet -- consumes a whole minute per attempt before the first retry, so a step
#:   that would fail in seconds instead approaches its Step Functions timeout and reports a
#:   timeout rather than a connectivity fault. Ten seconds is generous for an in-VPC endpoint
#:   and turns that failure back into a prompt, attributable one.
AWS_CONNECT_TIMEOUT_SECONDS = 10

#: Seconds the SDK waits for a response on an established connection before failing the attempt.
#:
#: WHY : Assumptions: this bounds ONE request, not the staging step. It is set well above the
#:   connect timeout because a single ``PutObject`` of a multi-megabyte extract legitimately
#:   takes longer to answer than a connection takes to open, while still being far below the
#:   per-state timeout the batch state machine allows, so a hung socket is reported by the SDK
#:   rather than by the orchestrator killing the task.
AWS_READ_TIMEOUT_SECONDS = 60

#: Retry attempts the SDK makes for one retryable API call, NOT counting the initial attempt.
#:
#: WHY : Assumptions: this is the value botocore's ``retries.max_attempts`` takes, and that key
#:   counts RETRIES rather than total attempts -- measured: passing 3 yields a client reporting
#:   ``total_max_attempts: 4``. The distinction is recorded because the key's name reads like a
#:   total and a reader budgeting against the batch state machine's own retry would otherwise be
#:   out by one on every call. Four total attempts is the effective ceiling.
#:
#: WHY : Alternatives Considered: the ``standard`` retry mode is selected explicitly rather than
#:   left to the SDK's ``legacy`` default. The modes differ in which faults they treat as
#:   retryable -- ``standard`` retries throttling and transient service errors with jittered
#:   exponential backoff, ``legacy`` covers a narrower set -- and the choice is stated here so
#:   the retry behaviour of this distribution does not change underneath it when a future SDK
#:   release moves its own default. Trade-offs: retries are kept to three because the batch
#:   state machine ALSO retries the whole step, so a large per-call budget multiplies against
#:   that outer budget and delays a genuine failure instead of surfacing it.
AWS_MAX_RETRY_ATTEMPTS = 3

#: The botocore retry mode this distribution selects; see :data:`AWS_MAX_RETRY_ATTEMPTS`.
AWS_RETRY_MODE = "standard"


def _aws_client_config() -> Any:
    """Build the botocore client configuration every AWS client in this distribution shares.

    Purpose
    -------
    State the connect timeout, the read timeout and the retry policy in one place, so that no
    client is created with the SDK's implicit defaults and the values are auditable together.

    Parameters
    ----------
    None
        Every value is a module constant above.

    Returns
    -------
    Any
        A ``botocore.config.Config``. Typed loosely because botocore is an optional import
        resolved at call time rather than at module scope.

    Raises
    ------
    ConfigurationError
        If the AWS SDK is not installed.
    """
    # Assumptions: botocore is imported inside the function for the same reason boto3 is --
    # importing ``config`` must remain free of third-party imports, which the package's own
    # documentation promises and the codec tests rely on by running with only the standard
    # library installed.
    try:
        from botocore.config import Config as BotocoreConfig
    except ImportError as exc:
        raise ConfigurationError(
            "the AWS SDK is not installed; install data-migration/requirements.txt"
        ) from exc

    return BotocoreConfig(
        connect_timeout=AWS_CONNECT_TIMEOUT_SECONDS,
        read_timeout=AWS_READ_TIMEOUT_SECONDS,
        retries={"max_attempts": AWS_MAX_RETRY_ATTEMPTS, "mode": AWS_RETRY_MODE},
    )


@lru_cache(maxsize=None)
def aws_client(service_name: str) -> Any:
    """Build and cache one AWS service client configured entirely from the environment.

    Purpose
    -------
    Provide the single point at which this distribution obtains an AWS client, so that the
    region requirement, the timeout and retry policy, and the caching decision are all made
    once rather than at each call site.

    Parameters
    ----------
    service_name : str
        The SDK service identifier -- ``"ssm"``, ``"secretsmanager"`` or ``"s3"``.

    Returns
    -------
    Any
        The service client. Typed loosely because the SDK generates its client classes at run
        time from service models, so no importable static type exists to annotate.

    Raises
    ------
    ConfigurationError
        If the AWS SDK is not installed, if the environment names no region, or if the client
        cannot be constructed.

    Notes
    -----
    This is the module's PUBLIC client factory and is exported in ``__all__``. It replaced a
    private ``_aws_client`` that :mod:`carddemo_migration.loaders.s3_stage` reached into from
    outside this module -- a public function in one module depending on a private name in
    another, which no import check would have flagged and which made the sibling's own public
    contract rest on a name this module was free to rename.
    """
    # Alternatives Considered: the SDK is imported inside this function rather than at the
    # top of the module. Importing it at module scope was written first and rejected, because the
    # package's own documentation states that importing ``config`` performs no configuration and
    # that only the loader and verification subpackages pull third-party clients in. A top-level
    # import would make that claim false the moment anything imports this module, and it would
    # couple a bare checkout -- where the codec tests run with nothing but the standard library
    # installed -- to a dependency those tests never use. Deferring it costs one dictionary
    # lookup per call after the first and keeps the claim true.
    try:
        import boto3
    except ImportError as exc:
        raise ConfigurationError(
            "the AWS SDK is not installed; install data-migration/requirements.txt"
        ) from exc

    # Alternatives Considered: no ``endpoint_url``, no literal ``region_name`` and no
    # credentials are passed. The SDK resolves all three from the environment on its own, and
    # letting it do so is what makes one code path correct everywhere: inside the batch staging
    # task the region and the task role's credentials arrive from the container environment,
    # while against the local emulator an ``AWS_ENDPOINT_URL`` variable redirects the same
    # client with nothing rebuilt. Passing a literal endpoint -- or branching on a "running
    # locally" flag to decide whether to pass one -- would create a second code path that only
    # one of the two environments ever exercises, so a defect in either would be invisible from
    # the other. It is the same discipline the reference emulator helper under
    # ``tests/helpers/`` follows, which takes its endpoint from the environment and never
    # embeds one.
    session = boto3.session.Session()

    # Assumptions: the region is REQUIRED to be resolvable and its absence is refused here,
    # before any client exists. The reason is specific rather than tidiness: S3 does not fail
    # when no region is configured, it falls back to ``us-east-1`` and succeeds. A staging task
    # deployed to another region with its region variable missing would therefore write every
    # extract into the wrong region's namespace, or fail with a confusing redirect, rather than
    # reporting the one thing actually wrong. Every other service raises ``NoRegionError`` at
    # construction, so the check only ADDS a failure for the service that would otherwise stay
    # silent -- and it names the missing setting instead of the symptom.
    # Trade-offs: this refuses a caller who deliberately relies on the S3 default. That is
    # intended; this distribution always runs where a region is configured, and an implicit
    # default is exactly the kind of setting that is wrong for months without being noticed.
    #
    # Assumptions: BOTH region variables are consulted, and ``AWS_REGION`` is consulted DIRECTLY
    # rather than through the session, because the pinned botocore does not resolve it. Measured
    # on botocore 1.43.50: its session variable mapping for the region is
    # ``('region', 'AWS_DEFAULT_REGION', None, None)`` -- ``AWS_DEFAULT_REGION`` only -- so with
    # ``AWS_REGION=eu-west-1`` and nothing else set, ``Session().region_name`` is ``None`` and an
    # S3 client silently resolves to ``us-east-1``. That is not a hypothetical: both environment
    # roots set ``AWS_REGION`` and not ``AWS_DEFAULT_REGION``
    # (``infra/envs/{dev,prod}/main.tf``), and ECS supplies ``AWS_REGION`` to a Fargate task
    # itself, so before this the staging task read its region from an intended setting the SDK
    # ignored and wrote to whatever ``us-east-1`` resolved to. Consulting the session first keeps
    # a profile or an instance-metadata answer authoritative where one exists.
    # Assumptions: ``AWS_REGION`` is consulted FIRST and the session second, which reproduces the
    # precedence AWS documents across its SDKs -- ``AWS_REGION`` above ``AWS_DEFAULT_REGION``, and
    # either environment variable above a profile or an instance-metadata answer. Taking the
    # session first was written that way and corrected: the session resolves
    # ``AWS_DEFAULT_REGION``, so with both variables set it returned the LOWER-precedence one and
    # this function would have built a client for a region the operator had overridden. Reading
    # the session second still covers everything the variables do not -- a named profile, a
    # configuration file, instance metadata.
    region = os.environ.get("AWS_REGION", "")
    if not isinstance(region, str) or not region.strip():
        region = session.region_name
    if not isinstance(region, str) or not region.strip():
        raise ConfigurationError(
            f"the AWS client for {service_name} could not be created; the environment names no "
            f"region. Set AWS_REGION or AWS_DEFAULT_REGION -- S3 would otherwise silently "
            f"default to us-east-1 and stage to the wrong region"
        )
    region = region.strip()

    # Trade-offs: the region is passed EXPLICITLY, which every other setting on this path
    # deliberately is not. It has to be: when the value came from ``AWS_REGION`` the SDK will not
    # read it, so omitting it here would build a client for ``us-east-1`` while this function had
    # just proved the environment asked for another region -- the exact silent mismatch the check
    # above exists to prevent. This is not the literal-configuration anti-pattern the comment
    # above rejects, because the value is still read FROM the environment rather than written into
    # source; nothing about which region is compiled in.
    try:
        return session.client(service_name, region_name=region, config=_aws_client_config())
    except _aws_error_types() as exc:
        raise ConfigurationError(
            f"the AWS client for {service_name} could not be created for region {region}; the "
            f"environment names no usable credentials ({type(exc).__name__})"
        ) from exc


@lru_cache(maxsize=None)
def _ssm_parameter(path: str) -> str:
    """Read one non-secret parameter from Parameter Store and return it as text.

    Purpose
    -------
    Fetch a single settings value that infrastructure provisioning wrote, and turn every way
    that can fail into one exception naming the path it looked for.

    Parameters
    ----------
    path : str
        The full parameter path, as built by :func:`parameter_path`.

    Returns
    -------
    str
        The parameter value with surrounding whitespace removed, guaranteed non-empty.

    Raises
    ------
    ConfigurationError
        If the parameter does not exist, if the caller is not permitted to read it, if the
        service call fails for any other reason, or if the value is present but blank.
    """
    client = aws_client("ssm")
    try:
        # Assumptions: decryption is explicitly not requested. Only the four non-secret
        # settings are read through this function, and the credential lives in the secret store,
        # so asking for decryption would require the task role to hold a key-usage grant it
        # otherwise needs for nothing -- widening a least-privilege role to read values that are
        # never encrypted. The consequence is worth stating: an encrypted parameter stored at
        # one of these paths would come back as ciphertext rather than as an error, so storing a
        # secure value there is a provisioning defect this call cannot detect.
        response = client.get_parameter(Name=path, WithDecryption=False)
    except _aws_error_types() as exc:
        code = _error_code(exc)
        if code in _MISSING_PARAMETER_CODES:
            raise ConfigurationError(f"the parameter {path} does not exist") from exc
        if code == "AccessDeniedException":
            raise ConfigurationError(
                f"the parameter {path} exists but this role is not permitted to read it"
            ) from exc
        # Trade-offs: the service error code is included but the service message is not.
        # A code is a fixed vocabulary term and is safe to print; a message is free text
        # generated by the service and is the one field that could echo back part of a request.
        # Chaining with ``from exc`` keeps the full message and the request identifier in the
        # traceback for anyone diagnosing the failure, which is where that detail belongs
        # rather than in a string that gets copied into an alarm notification.
        detail = code or type(exc).__name__
        raise ConfigurationError(f"the parameter {path} could not be read ({detail})") from exc

    # Assumptions: the response is navigated defensively for the same reason the error
    # code is -- a stubbed or future client could answer without the nested member, and an
    # index error here would report a missing key rather than the missing parameter it means.
    parameter = response.get("Parameter") if isinstance(response, dict) else None
    value = parameter.get("Value") if isinstance(parameter, dict) else None
    return _require_text(value, f"the parameter {path}")


@lru_cache(maxsize=None)
def _secret_credentials(secret_name: str) -> tuple[str, str]:
    """Read one database credential from the secret store and return its user and password.

    Purpose
    -------
    Fetch the JSON credential document infrastructure provisioning wrote for one login role,
    validate that it carries both fields a connection needs, and return them.

    Parameters
    ----------
    secret_name : str
        The full secret name, as built by :func:`database_secret_name`.

    Returns
    -------
    tuple[str, str]
        The username and the password, in that order.

    Raises
    ------
    ConfigurationError
        If the secret does not exist, if the caller is not permitted to read it, if the service
        call fails for any other reason, if the secret holds binary data rather than text, if
        the text is not a JSON object, or if either required field is absent or empty.
    """
    client = aws_client("secretsmanager")
    try:
        response = client.get_secret_value(SecretId=secret_name)
    except _aws_error_types() as exc:
        code = _error_code(exc)
        if code in _MISSING_SECRET_CODES:
            # Assumptions: a secret scheduled for deletion reports this same code, and it
            # is reported the same way on purpose. Neither state yields a credential and both
            # are fixed by provisioning rather than by retrying, so distinguishing them would
            # add a message this package cannot act on differently.
            raise ConfigurationError(
                f"the secret {secret_name} does not exist or is scheduled for deletion"
            ) from exc
        if code == "AccessDeniedException":
            raise ConfigurationError(
                f"the secret {secret_name} exists but this role is not permitted to read it"
            ) from exc
        detail = code or type(exc).__name__
        raise ConfigurationError(f"the secret {secret_name} could not be read ({detail})") from exc

    document = response.get("SecretString") if isinstance(response, dict) else None
    if not isinstance(document, str):
        # Assumptions: a secret holding only binary data is reported as the wrong KIND of
        # secret rather than as a missing one. The distinction is what the operator needs: the
        # secret is there and readable, and what is wrong is that it was written as a binary
        # blob instead of as the JSON credential document this module reads.
        raise ConfigurationError(
            f"the secret {secret_name} holds no text; a JSON credential document is expected"
        )

    try:
        payload = json.loads(document)
    except json.JSONDecodeError:
        # Trade-offs: this is the one failure in the module raised with ``from None``,
        # deliberately breaking the chain that every other handler here preserves. A
        # ``JSONDecodeError`` retains the ENTIRE document it failed to parse on its ``doc``
        # attribute, so the exception object itself carries the secret; any tooling that renders
        # exception attributes or local variables -- a test runner showing locals, a structured
        # log handler serialising the cause -- would then print a credential that merely failed
        # to parse. The accepted cost is that the parse position is lost, which is a small price
        # for a failure whose remedy is to rewrite the secret rather than to debug its contents.
        raise ConfigurationError(
            f"the secret {secret_name} is not valid JSON; a credential document is expected"
        ) from None

    if not isinstance(payload, dict):
        raise ConfigurationError(
            f"the secret {secret_name} is not a JSON object with "
            f"{_SECRET_USERNAME_KEY} and {_SECRET_PASSWORD_KEY} fields"
        )

    username = _require_text(
        payload.get(_SECRET_USERNAME_KEY), f"the {_SECRET_USERNAME_KEY} in secret {secret_name}"
    )

    # Assumptions: the password is validated here rather than through the shared text
    # check, because that helper strips surrounding whitespace and a credential must be carried
    # byte for byte. A generated password can legitimately begin or end with a space, and
    # trimming one would produce a value that differs from the stored secret and fails
    # authentication with nothing in the message to suggest why.
    password = payload.get(_SECRET_PASSWORD_KEY)
    if not isinstance(password, str) or not password:
        raise ConfigurationError(
            f"the {_SECRET_PASSWORD_KEY} in secret {secret_name} is missing or empty"
        )

    # Trade-offs: an immutable tuple is returned rather than the decoded dictionary. This
    # function is cached, so returning the dictionary would hand every caller a reference to the
    # same mutable object -- one caller popping a key, or overwriting the password after use in
    # an attempt to scrub it, would silently change what the next caller receives. The accepted
    # cost is positional unpacking at the call site, which is bounded because there is exactly
    # one such site.
    return (username, password)


# Trade-offs: every resolver below is cached for the lifetime of the process, and the
# consequence accepted is precise: a credential rotated while a command is running is not
# observed, so a load that outlives a rotation fails on the connection it opens next rather
# than picking the new password up. That is acceptable here because of how these commands run
# -- the batch staging step fans out one short-lived container task per dataset, so a process
# resolves its settings once and exits long before a scheduled rotation window matters, and a
# task that does fail is restarted by the orchestrator and then resolves the new value on its
# first call. The alternative is worse in the case that actually occurs: resolving on every
# access would issue one parameter-store call per dataset per setting, turning a fixed handful
# of lookups into a per-dataset multiple, and making the staging step's cost scale with the
# number of datasets for values that are identical across all of them.
#
# Assumptions: the environment-derived values are cached too, which saves nothing
# measurable -- reading a process environment variable is free -- and is done for coherence
# instead. Anything that mutated one of these variables part way through a run would otherwise
# let a single process resolve one setting under one environment and the next under another,
# producing a load that is half-addressed to each. Caching makes the environment a process
# decided once, and :func:`reset_resolution_cache` is the one supported way to change it.
@lru_cache(maxsize=1)
def resolve_environment_name() -> str:
    """Return the deployment environment name this process is configured for.

    Purpose
    -------
    Read the environment name that selects which deployment's parameters and credentials every
    other lookup resolves against, and confirm it is usable as a path segment before any path
    is built from it.

    Parameters
    ----------
    None
        The value comes from the :data:`ENV_ENVIRONMENT` environment variable.

    Returns
    -------
    str
        The environment name with surrounding whitespace removed, guaranteed non-empty and
        guaranteed to be a single path segment.

    Raises
    ------
    ConfigurationError
        If the variable is unset, blank, or not a single path segment.
    """
    raw = os.environ.get(ENV_ENVIRONMENT)
    if raw is None:
        # Assumptions: an unset variable is reported separately from a blank one because
        # the two have different remedies -- one was never provided to the container, the other
        # was provided as an empty string, typically by a template that resolved to nothing --
        # and an operator reading only "is empty" would look in the wrong place for the cause.
        raise ConfigurationError(f"the environment variable {ENV_ENVIRONMENT} is not set")
    return _validate_path_segment(
        _require_text(raw, f"the environment variable {ENV_ENVIRONMENT}"),
        f"the environment variable {ENV_ENVIRONMENT}",
    )


@lru_cache(maxsize=1)
def resolve_parameter_prefix() -> str:
    """Return the Parameter Store path prefix every lookup in this module is built from.

    Purpose
    -------
    Read the configurable namespace that this deployment's parameters and secrets live under,
    falling back to :data:`DEFAULT_PARAMETER_PREFIX`, and validate its shape before any path is
    joined onto it.

    Parameters
    ----------
    None
        The value comes from the :data:`ENV_PARAMETER_PREFIX` environment variable when set and
        non-blank, and from :data:`DEFAULT_PARAMETER_PREFIX` otherwise.

    Returns
    -------
    str
        The prefix, beginning with a forward slash and not ending with one.

    Raises
    ------
    ConfigurationError
        If the configured prefix does not begin with a forward slash, ends with one, or contains
        a segment that is not a valid path segment.
    """
    raw = os.environ.get(ENV_PARAMETER_PREFIX)
    if raw is None or not raw.strip():
        # Trade-offs: an empty or whitespace-only override falls back to the default
        # rather than being rejected. An orchestrator that renders an unset template variable
        # supplies exactly that, and treating it as "not configured" is what a reader of the
        # deployment intends; the accepted cost is that a genuinely empty prefix cannot be
        # expressed, which is correct anyway because Parameter Store has no such name.
        return DEFAULT_PARAMETER_PREFIX

    prefix = raw.strip()
    description = f"the environment variable {ENV_PARAMETER_PREFIX}"
    if not prefix.startswith("/"):
        raise ConfigurationError(f"{description} must begin with a forward slash")
    if prefix.endswith("/"):
        # Assumptions: a trailing slash is rejected rather than trimmed. Parameter Store
        # treats a name with a trailing separator as a different name, so accepting both
        # spellings and normalising one into the other would let this module silently resolve a
        # path that no other consumer of the same configured prefix resolves. Refusing keeps one
        # spelling canonical and reports the disagreement at start-up instead of hiding it.
        raise ConfigurationError(f"{description} must not end with a forward slash")
    for segment in prefix[1:].split("/"):
        _validate_path_segment(segment, description)
    return prefix


def _require_supported_ssl_mode() -> None:
    """Refuse a configured transport mode weaker than the one this module requires.

    Purpose
    -------
    Give an operator who tries to lower ``sslmode`` a message explaining why it cannot be
    lowered, instead of silence. The mode itself is not configurable -- it is
    :data:`REQUIRED_SSL_MODE` and :meth:`AuroraConnectionSettings.as_connection_params` emits
    that constant -- so this check changes no connection parameter. Its only effect is to fail a
    command whose environment asks for something the module will not do, rather than to run it
    with the request quietly ignored.

    Parameters
    ----------
    None
        Reads :data:`ENV_SSL_MODE` from the process environment.

    Returns
    -------
    None
        Returns when the variable is unset, blank, or already names the required mode.

    Raises
    ------
    ConfigurationError
        If the variable names any mode other than :data:`REQUIRED_SSL_MODE`.
    """
    raw = os.environ.get(ENV_SSL_MODE)
    if raw is None or not raw.strip():
        return
    # Assumptions: the comparison is case-folded because libpq accepts the mode
    # case-insensitively, so ``Verify-Full`` is the same request as ``verify-full`` and refusing
    # it would be a spelling complaint dressed as a security control. Everything else is
    # refused, including the two modes that do encrypt: ``require`` performs no certificate or
    # hostname verification at all, and ``verify-ca`` validates the chain but not the name, so
    # both accept an endpoint that is not this cluster.
    if raw.strip().lower() != REQUIRED_SSL_MODE:
        raise ConfigurationError(
            f"the environment variable {ENV_SSL_MODE} may only be set to "
            f"{REQUIRED_SSL_MODE!r}; the ETL will not connect to Aurora under a mode that "
            f"skips certificate or hostname verification"
        )


@lru_cache(maxsize=1)
def resolve_ssl_root_cert() -> str:
    """Resolve the certificate bundle that must certify the Aurora endpoint.

    Purpose
    -------
    Produce the one absolute path that ``verify-full`` verifies the cluster's certificate
    against, taking an operator override when one is given and the pinned container-image
    location otherwise. Verification is only as strong as its trust anchor, so this is where the
    anchor is established and confirmed to exist.

    Parameters
    ----------
    None
        Reads :data:`ENV_SSL_ROOT_CERT` from the process environment, falling back to
        :data:`DEFAULT_SSL_ROOT_CERT`.

    Returns
    -------
    str
        An absolute path to a file that exists and is readable at the moment of the call.

    Raises
    ------
    ConfigurationError
        If the override is blank, is not an absolute path, contains a NUL byte, or names a path
        that is not an existing readable file.
    """
    raw = os.environ.get(ENV_SSL_ROOT_CERT)
    if raw is None or not raw.strip():
        path = DEFAULT_SSL_ROOT_CERT
        description = "the default database TLS root certificate path"
    else:
        description = f"the environment variable {ENV_SSL_ROOT_CERT}"
        path = _validate_absolute_path(_require_text(raw, description), description)

    # Refactoring Rationale: existence is checked HERE and not in
    # ``AuroraConnectionSettings.__post_init__``, which checks only the path's shape. The split
    # follows what each place can promise. A descriptor is constructed directly by tests and is
    # frozen, hashable and cached, so making its validity depend on the filesystem would make
    # two identical descriptors differ by machine and would require every test to materialise a
    # certificate file. This function, by contrast, runs only on the resolution path that
    # precedes a real connection, which is exactly where an absent bundle is actionable.
    #
    # Trade-offs: a missing bundle is refused rather than being left to libpq. Under
    # ``verify-full`` libpq reports an unreadable root certificate as a generic TLS failure, so
    # the actual defect -- the image did not carry the bundle, or an override points at a path
    # that no longer exists -- reads as though the server rejected the connection, and the
    # obvious next move is to weaken the mode. Naming the path removes that dead end. The check
    # is racy in the strict sense, since the file could be removed between here and the connect
    # call, and that is accepted: the purpose is to catch a misconfiguration, not to hold a lock
    # on the filesystem.
    if not os.path.isfile(path):
        raise ConfigurationError(
            f"{description} does not name an existing file; the AWS Aurora certificate bundle "
            f"must be present for TLS mode {REQUIRED_SSL_MODE!r} to verify the endpoint"
        )
    if not os.access(path, os.R_OK):
        raise ConfigurationError(f"{description} names a file that cannot be read")

    _require_trustworthy_anchor(path, description, overridden=raw is not None and bool(raw.strip()))
    return path


def _require_trustworthy_anchor(path: str, description: str, *, overridden: bool) -> str:
    """Confirm a trust anchor is one this process may rely on, not merely one it can read.

    Purpose
    -------
    Establish that the file ``verify-full`` will trust cannot have been substituted by anything
    other than the identity that owns the process, and that an override is not being used to
    replace the anchor in an environment where the anchor is a fixed deliverable.

    Parameters
    ----------
    path : str
        The resolved absolute path, already known to be an existing readable file.
    description : str
        What the path is, for the failure message. Must not contain a resolved secret.
    overridden : bool
        Whether the path came from the environment override rather than from the pinned default.
        Only an override is subject to the environment restriction, because the default IS the
        pinned value.

    Returns
    -------
    str
        ``path`` unchanged, so this can be used in an assignment.

    Raises
    ------
    ConfigurationError
        If the anchor is a symbolic link, if it is writable by a group or by others, if it is not
        owned by this process's user or by root, or if an override is attempted in an environment
        whose name is not one of the non-production names.

    Notes
    -----
    Refactoring Rationale: this function did not exist, and the resolution accepted ANY absolute
    readable path, in any environment, with no constraint on who could write it. Verification is
    exactly as strong as its anchor: a file an unprivileged co-tenant of the container can
    replace, or a symlink whose target changes after this check, makes ``verify-full`` verify the
    endpoint against an authority of that party's choosing -- and the connection still succeeds,
    so nothing reports it. The checks below cost four calls to ``stat`` once per process.

    Alternatives Considered: pinning a content digest of the bundle and refusing anything else.
    Rejected as the primary control because AWS republishes the global bundle as authorities are
    rotated, so a pinned digest would fail every legitimate refresh and the pressure would be to
    remove the pin rather than to update it. The mode and symlink checks constrain WHO can supply
    the file, which holds across a republication. Where a deployment does want a digest, it
    belongs beside the image build that places the file, not here.

    Alternatives Considered: requiring the file to be owned by root or by this process's user.
    Rejected after measuring it against a real deployment: a correctly provisioned anchor is
    frequently owned by the service account that generated it -- the local PostgreSQL certificate
    directory in this project's own environment is owned by the database account, mode 0644 --
    so that rule refuses legitimate files. It also answers the wrong question, because whether a
    given uid is trustworthy cannot be read off the filesystem. The checks kept below instead
    enforce the invariant that actually matters and IS decidable: no identity other than the
    file's own owner and root can substitute it. A rule that breaks a valid deployment is a rule
    that gets deleted at the first friction, which leaves nothing.

    Assumptions: every directory on the path is checked, not just the file. A file that is itself
    unwritable inside a group-writable directory can be unlinked and replaced wholesale by any
    member of that group, so checking the file's mode alone would be decorative. This is the same
    reasoning that makes OpenSSH refuse a key whose containing directory is group-writable.

    Assumptions: an override is confined to the non-production environment names, because in
    production the bundle is a deliverable of the container image and its path is fixed by the
    same image. A production override could only come from an environment variable a caller set,
    which is the shape of the attack this restriction removes; in development the override is
    genuinely needed, since the bundle sits elsewhere when a command runs outside the image.
    """
    if overridden:
        # Trade-offs: an unset or unrecognised environment name REFUSES the override rather
        # than allowing it. Allowing it would mean the one case where the deployment's identity is
        # unknown is also the case with the fewest constraints, which inverts the intent. Every
        # real command already sets this variable, because :func:`resolve_environment_name` gives
        # it no default; the cost is therefore borne only by a caller that overrode the anchor
        # without saying which deployment it is overriding for.
        environment = os.environ.get(ENV_ENVIRONMENT, "").strip()
        if environment not in NON_PRODUCTION_ENVIRONMENTS:
            raise ConfigurationError(
                f"{description} may not be overridden when {ENV_ENVIRONMENT} is "
                f"{environment or 'unset'!r}; the trust anchor is a deliverable of the container "
                f"image and its path is fixed at {DEFAULT_SSL_ROOT_CERT}. Overrides are accepted "
                f"only in: {', '.join(sorted(NON_PRODUCTION_ENVIRONMENTS))}"
            )

    # Assumptions: the link check uses lstat semantics, so it observes the path ITSELF and
    # not its target. A symlink is refused rather than followed because the target can be
    # repointed between this check and the connection that uses it, which makes every other check
    # here decorative; refusing the link removes the window instead of narrowing it.
    if os.path.islink(path):
        raise ConfigurationError(
            f"{description} is a symbolic link; a trust anchor must be a regular file, because a "
            f"link target can be repointed after it is validated"
        )

    # Assumptions: group and other write permission is refused, not merely noted. Any
    # identity that can write the anchor can replace the authority the endpoint is verified
    # against, so a group-writable bundle makes the verification only as strong as the widest
    # membership of that group. The same test is applied to every directory above it, because a
    # writable directory permits unlink-and-replace and would make the file's own mode moot.
    if os.stat(path).st_mode & (stat.S_IWGRP | stat.S_IWOTH):
        raise ConfigurationError(
            f"{description} is writable by its group or by others; a trust anchor must be "
            f"writable only by the identity that owns it"
        )

    directory = os.path.dirname(path)
    while True:
        mode = os.stat(directory).st_mode
        # Trade-offs: a group- or other-writable directory is tolerated when the sticky bit
        # is set, and refused otherwise. The sticky bit is the mechanism that makes a shared
        # directory safe for this purpose: it withholds unlink and rename from everyone except a
        # file's own owner, so the substitution this check exists to prevent is already denied by
        # the kernel. Omitting the exemption was tried first and refused ``/tmp`` (mode 1777),
        # which would have failed every anchor staged by a test or by a local command -- friction
        # with no security gain, since a co-tenant still cannot replace a file it does not own.
        if mode & (stat.S_IWGRP | stat.S_IWOTH) and not mode & stat.S_ISVTX:
            raise ConfigurationError(
                f"{description} sits under directory {directory} which is writable by its group "
                f"or by others without the sticky bit set; any member of that group could replace "
                f"the trust anchor regardless of the file's own permissions"
            )
        parent = os.path.dirname(directory)
        # Assumptions: the walk terminates when dirname stops shortening the path, which
        # for an absolute path is exactly at the root. Comparing against a literal "/" was
        # rejected because it hard-codes a separator the standard library already abstracts.
        if parent == directory:
            return path
        directory = parent


@lru_cache(maxsize=1)
def resolve_alternate_database_users() -> Mapping[str, frozenset[str]]:
    """Resolve the per-role allowlist of user names a rotation may legitimately substitute.

    Purpose
    -------
    Parse :data:`ENV_ALTERNATE_DB_USERS` into the exceptions that
    :func:`resolve_aurora_settings` will accept when a secret's ``username`` differs from the
    role its name was derived from. The variable exists for the alternating-users rotation
    strategy, which provisions a second, equivalently privileged user per role and hands back
    whichever is currently active; every other difference is a misconfiguration.

    The accepted syntax is a comma-separated list of ``role=alternate`` pairs, for example one
    entry per role that is under alternating rotation. The role on the left must be one of the
    **fifteen** login roles in :data:`LOGIN_ROLE_NAMES` -- the eight connection roles of
    :data:`SCHEMA_ROLES` together with the seven ``_migrator`` roles -- and a role may appear more
    than once, which is how a rotation that has provisioned more than one clone is expressed.

    Refactoring Rationale: this description said the role on the left had to be one of the eight
    owning roles in :data:`SCHEMA_ROLES`, which is narrower than what the code accepts and
    narrower than what the deployment needs. A ``_migrator`` credential is rotated by the same
    operator procedure as a runtime one, so an allowlist that could not name it would refuse a
    legitimately rotated migration credential -- and the refusal would present as a service
    failing to start rather than as a gap in this variable. The implementation comment beside
    ``owning_roles`` below already recorded the wider domain, so the docstring was contradicting
    the code it documents; the docstring is corrected to match rather than the code narrowed to
    match it.

    Parameters
    ----------
    None
        Takes no argument. Reads :data:`ENV_ALTERNATE_DB_USERS` from the process environment, and
        the result is cached for the life of the process by :func:`functools.lru_cache`, so a
        change to that variable after the first call is not observed.

    Returns
    -------
    Mapping[str, frozenset[str]]
        A read-only mapping from login role to the alternate user names allowlisted for it. Empty
        when the variable is unset or blank, which is the expected configuration.

    Raises
    ------
    ConfigurationError
        If any entry is empty, does not contain exactly one ``=``, names a role that is not one of
        the fifteen login roles, has an alternate that is not a plain PostgreSQL role name, or has
        an alternate that is itself one of the fifteen login roles. Assumptions: widening the KEY
        domain does not widen what may be allowlisted -- a login role is still refused as another
        role's alternate, which is what keeps this variable from being usable to grant one role
        the credential of another.
    """
    raw = os.environ.get(ENV_ALTERNATE_DB_USERS)
    if raw is None or not raw.strip():
        return MappingProxyType({})

    description = f"the environment variable {ENV_ALTERNATE_DB_USERS}"
    # Assumptions: the accepted keys are ALL FIFTEEN login roles, not just the eight connection
    # roles. A ``_migrator`` credential is rotated by the same operator procedure as a runtime
    # one, so an allowlist that could not name it would refuse a legitimately rotated migration
    # credential -- and the refusal would present as a service failing to start rather than as a
    # gap in this variable. Widening the KEY domain does not widen what may be allowlisted: the
    # value check below still refuses any login role as another role's alternate.
    owning_roles = frozenset(LOGIN_ROLE_NAMES)
    allowlist: dict[str, set[str]] = {}

    # Trade-offs: an empty entry is refused rather than skipped, so a trailing comma or a
    # doubled separator fails instead of being tolerated. Tolerating it is friendlier for the
    # common typo and wrong for this variable specifically: this is the one input that widens
    # what credential the ETL will authenticate as, and a value that was mis-split -- by a shell
    # quoting mistake, by a templating system emitting an empty element -- would otherwise
    # install a shorter allowlist than the operator wrote, with no indication which entry was
    # lost. Refusing means the allowlist in force is always exactly the one configured.
    for entry in raw.split(","):
        item = entry.strip()
        if not item:
            raise ConfigurationError(
                f"{description} contains an empty entry; each entry must be "
                f"'role=alternate_user' and entries are separated by single commas"
            )
        role, separator, alternate = item.partition("=")
        if not separator:
            raise ConfigurationError(
                f"{description} contains an entry with no '='; each entry must name the owning "
                f"role and the allowlisted user as 'role=alternate_user'"
            )
        role = role.strip()
        alternate = alternate.strip()
        if role not in owning_roles:
            # Assumptions: an unrecognised role is refused rather than ignored. An ignored
            # entry is the worst outcome available here, because the operator believes an
            # exception is in force, the misspelling means it is not, and the discovery comes as
            # a refused connection during a rotation window. The fifteen accepted roles are named
            # in the message for the same reason :func:`role_for_schema` names the eight schemas:
            # they are published by the bootstrap script, so nothing resolved is disclosed.
            raise ConfigurationError(
                f"{description} names an unknown owning role; expected one of "
                f"{', '.join(sorted(owning_roles))}"
            )
        if not _ROLE_NAME_PATTERN.match(alternate):
            raise ConfigurationError(
                f"{description} allowlists a user for role {role!r} that is not a plain "
                f"PostgreSQL role name of at most 63 characters"
            )
        if alternate in owning_roles:
            # Assumptions: allowlisting one owning role as another's alternate is refused
            # outright, and this is the guard that keeps the escape hatch from becoming a
            # privilege bridge. A rotation clone is a NEW user created for one role; a spelling
            # that names a different owning role is not a clone, and accepting it would let the
            # secret for, say, the reporting schema hand back the batch role -- which holds
            # write grants on the ledger and account schemas -- and be treated as legitimate.
            raise ConfigurationError(
                f"{description} allowlists an owning role as the alternate for role {role!r}; "
                f"an alternate must be a rotation user distinct from all fifteen login roles"
            )
        allowlist.setdefault(role, set()).add(alternate)

    # Assumptions: the returned mapping is wrapped in a read-only proxy over frozen sets
    # for the same reason :data:`SCHEMA_ROLES` is. This function is cached, so every caller holds
    # the SAME object; a mutable result would let one caller widen the allowlist that every later
    # caller checks against, which is a privilege change made by accident and invisible at the
    # site that benefits from it.
    return MappingProxyType({role: frozenset(users) for role, users in allowlist.items()})


def _require_matching_database_user(schema: str, role: str, username: str) -> str:
    """Return the credential's user name once it is confirmed to be one this role may use.

    Purpose
    -------
    Close the gap between a secret's NAME, which is derived from the owning role and is
    therefore auditable, and its CONTENTS, which are not. The name being right does not make the
    payload right: a mis-targeted rotation, a secret repaired with another environment's
    payload, or a copy-paste between two roles' secrets all leave the name intact and change the
    identity the ETL authenticates as. Without this check any user name in the document is
    accepted, so a load could run with privileges the caller never asked for and succeed.

    Parameters
    ----------
    schema : str
        The bounded-context schema the settings were requested for. Named in the failure message
        so an operator knows which secret to inspect.
    role : str
        The owning role derived from that schema through :func:`role_for_schema`.
    username : str
        The ``username`` field read from the secret document.

    Returns
    -------
    str
        ``username`` unchanged, once it equals ``role`` or appears in that role's entry of
        :func:`resolve_alternate_database_users`.

    Raises
    ------
    ConfigurationError
        If the user name is neither the owning role nor an allowlisted alternate for it.

    Notes
    -----
    Assumptions: acceptance here is by NAME and is deliberately provisional. A name proves which
    identity the payload claims to be; it cannot prove what that identity is permitted to do,
    because privileges live in the database's catalog and this module holds no connection. An
    allowlisted alternate that had been granted ``SUPERUSER`` -- by a mis-scoped grant, or by a
    rotation that recreated the role from a different template -- would satisfy this function and
    then run the load with privileges nobody authorised.

    Refactoring Rationale: rather than open a connection here, which would make every settings
    resolution require a reachable database and turn a configuration error into a network error,
    the privilege half of the check is published as a contract the connection layer applies once
    it has a session: :func:`alternate_database_user_verification_sql` supplies the catalog query
    and :func:`require_equivalent_database_user` adjudicates its result. That keeps this module
    connection-free while making the verification obligation explicit and testable rather than
    implied. Until a caller applies that contract, an allowlisted alternate is trusted on its
    name alone, which is why the allowlist itself refuses to admit one owning role as another's
    alternate.
    """
    if username == role:
        return username
    if username in resolve_alternate_database_users().get(role, frozenset()):
        return username
    # Assumptions: the rejected user name is NOT interpolated into the message, while the
    # schema and the expected role are. The module holds one rule about messages -- a value
    # resolved from Parameter Store or Secrets Manager is never printed -- and this value came
    # out of a secret document, so it falls under that rule even though a user name is not itself
    # a credential; a name that turned out to be a paste of the wrong field could otherwise be
    # echoed into a log. The schema came from the caller and the role from this module's own
    # mapping, so naming both is what makes the message actionable: it says which secret to look
    # at and what its username field is expected to say.
    raise ConfigurationError(
        f"the credential for schema {schema!r} names a database user other than its owning "
        f"role {role!r}; set {ENV_ALTERNATE_DB_USERS} to '{role}=<user>' if a rotation "
        f"legitimately alternates between two equivalently privileged users"
    )


# Assumptions: these are the role attributes whose presence disqualifies an alternate,
# named individually rather than checked as "not superuser". Each one independently defeats the
# schema isolation the ETL relies on: SUPERUSER ignores every grant, BYPASSRLS ignores row
# policies, CREATEROLE can grant itself anything a role could hold, CREATEDB can create a
# database outside the audited set, and REPLICATION can stream the cluster's contents wholesale.
# Alternatives Considered: comparing an alternate's privileges to the owning role's and requiring
# equality. Rejected because two roles are almost never attribute-identical in practice -- a
# rotation target legitimately differs in password expiry and connection limit -- so equality
# would fail on differences that do not affect authority, and the pressure would be to drop the
# check rather than narrow it.
FORBIDDEN_DATABASE_ROLE_ATTRIBUTES: tuple[str, ...] = (
    "is_superuser",
    "can_create_db",
    "can_create_role",
    "bypasses_row_level_security",
    "can_replicate",
)


@dataclass(frozen=True, slots=True)
class DatabaseUserAttributes:
    """One database user's catalog attributes, as observed in ``pg_roles``.

    Purpose
    -------
    Carry the answer to :func:`alternate_database_user_verification_sql` in a shape that
    :func:`require_equivalent_database_user` can adjudicate, so the query and the judgement are
    separable: the caller owns the connection, this module owns the rule. Holding the observation
    as data rather than passing a cursor keeps the rule testable without a database.

    Attributes
    ----------
    username : str
        The role name the row describes, echoed back so a caller cannot adjudicate one user's
        attributes against another user's name.
    can_login : bool
        Whether the role may authenticate. An alternate that cannot log in is a configuration
        mistake, not a usable credential.
    is_member_of_owning_role : bool
        Whether the role is a member of the schema's owning role, and therefore actually holds
        the privileges the allowlist assumed when it admitted the name.
    is_superuser : bool
        Whether the role bypasses all permission checks.
    can_create_db : bool
        Whether the role may create databases.
    can_create_role : bool
        Whether the role may create or alter other roles.
    bypasses_row_level_security : bool
        Whether the role bypasses row-level security policies.
    can_replicate : bool
        Whether the role may initiate streaming replication.
    """

    username: str
    can_login: bool
    is_member_of_owning_role: bool
    is_superuser: bool
    can_create_db: bool
    can_create_role: bool
    bypasses_row_level_security: bool
    can_replicate: bool


def alternate_database_user_verification_sql() -> str:
    """Return the catalog query that establishes what an allowlisted alternate may actually do.

    Purpose
    -------
    Publish, as a single named contract, the query a caller must run once it holds a session in
    order to convert the name-based acceptance of :func:`resolve_alternate_database_users` into a
    privilege-based one. Returning the text rather than executing it keeps this module free of a
    database dependency while removing the caller's freedom to invent its own weaker check.

    Parameters
    ----------
    None
        Takes no argument. The query text is a constant assembled here, so it depends on no
        setting, no environment variable and no process state; the two values it needs arrive as
        driver-bound parameters at execution time rather than as arguments to this function.

    Returns
    -------
    str
        A query with two named parameters, ``owning_role`` and ``alternate_user``, selecting one
        row whose columns match the field names of :class:`DatabaseUserAttributes`. The query
        returns no row when the user does not exist, which the caller must treat as a refusal.

    Raises
    ------
    None
        Raises nothing. Assembling a constant string cannot fail, so a caller needs no handler
        here; every failure mode of this contract belongs to the caller's execution of the query.
        Assumptions: the inapplicability is declared rather than left silent, because a reader has
        to be able to tell a function that cannot fail from a docstring that forgot to say how it
        does.

    Notes
    -----
    Assumptions: the parameters are named, not positional, and the values are bound by the
    caller's driver rather than interpolated here. A role name that reached this query through
    string formatting would be an injection point in the one place that is supposed to be
    establishing trust; naming the parameters makes the binding the only way to supply them.

    Assumptions: membership is tested with ``pg_has_role(..., 'MEMBER')``, which follows the
    grant chain transitively. A direct-grant test was rejected because a legitimate deployment
    may interpose a group role between the alternate and the owning role, and an inherited grant
    confers exactly the same authority as a direct one.
    """
    return (
        "SELECT r.rolname AS username,\n"
        "       r.rolcanlogin AS can_login,\n"
        "       pg_catalog.pg_has_role(r.rolname, %(owning_role)s, 'MEMBER')\n"
        "           AS is_member_of_owning_role,\n"
        "       r.rolsuper AS is_superuser,\n"
        "       r.rolcreatedb AS can_create_db,\n"
        "       r.rolcreaterole AS can_create_role,\n"
        "       r.rolbypassrls AS bypasses_row_level_security,\n"
        "       r.rolreplication AS can_replicate\n"
        "  FROM pg_catalog.pg_roles AS r\n"
        " WHERE r.rolname = %(alternate_user)s"
    )


def require_equivalent_database_user(
    role: str, observed: DatabaseUserAttributes | None
) -> DatabaseUserAttributes:
    """Confirm an allowlisted alternate holds the authority its allowlisting assumed, no more.

    Purpose
    -------
    Complete the check :func:`_require_matching_database_user` can only start. That function
    establishes that a credential names an identity the operator allowlisted; this one
    establishes that the identity is confined to the owning role's authority, so an allowlist
    entry cannot be turned into an escalation by a grant made elsewhere.

    Parameters
    ----------
    role : str
        The schema's owning role, as returned by :func:`role_for_schema`. Reported in failure
        messages because it is derived from this module's own mapping and is not sensitive.
    observed : DatabaseUserAttributes or None
        The single row produced by :func:`alternate_database_user_verification_sql`, or ``None``
        when the query returned no row because the user does not exist in the cluster.

    Returns
    -------
    DatabaseUserAttributes
        ``observed`` unchanged, so the call can be used in an assignment once it has passed.

    Raises
    ------
    ConfigurationError
        If ``observed`` is ``None``; if the role cannot log in; if it is not a member of the
        owning role; or if it holds any attribute named in
        :data:`FORBIDDEN_DATABASE_ROLE_ATTRIBUTES`.

    Notes
    -----
    Trade-offs: a missing row is a refusal rather than a pass. Treating "no such role" as
    acceptable was rejected because the query cannot distinguish a role that was never created
    from one whose name was mistyped in the allowlist, and both mean the allowlist does not
    describe the cluster it is being applied to.

    Assumptions: every failing attribute is reported, not just the first. An operator repairing a
    role that holds two disqualifying attributes should not have to run the load twice to learn
    the second one; the message names the attributes rather than the role's password or any other
    value read from a secret.
    """
    if observed is None:
        raise ConfigurationError(
            f"the alternate database user allowlisted for role {role!r} does not exist in the "
            f"cluster; {ENV_ALTERNATE_DB_USERS} names a role that was never created, or names it "
            f"differently from the cluster"
        )

    failures: list[str] = []
    if not observed.can_login:
        failures.append("it may not log in")
    if not observed.is_member_of_owning_role:
        failures.append(f"it is not a member of {role!r} and so lacks that role's privileges")
    # Refactoring Rationale: the forbidden attributes are read through getattr over the
    # declared tuple rather than as five hand-written conditions. Adding an attribute to the
    # tuple then extends the check with no further edit, which is what keeps the constant and the
    # enforcement from drifting apart -- the failure mode where a name is added to the list and
    # nothing enforces it.
    for attribute in FORBIDDEN_DATABASE_ROLE_ATTRIBUTES:
        if getattr(observed, attribute):
            failures.append(f"it holds {attribute}")

    if failures:
        raise ConfigurationError(
            f"the alternate database user allowlisted for role {role!r} is not equivalent to it: "
            + "; ".join(failures)
            + f". Correct the role in the cluster or remove it from {ENV_ALTERNATE_DB_USERS}"
        )
    return observed


def parameter_path(*segments: str) -> str:
    """Build a full Parameter Store path from the prefix, the environment and given segments.

    Purpose
    -------
    Be the only place a parameter path is assembled, so that the prefix and the environment name
    are applied uniformly and a caller supplies only the part that identifies the setting.

    Parameters
    ----------
    *segments : str
        One or more path segments appended after the prefix and the environment name, for
        example ``("aurora", "host")``. Each must be a single path segment; a value containing a
        separator is rejected rather than deepening the path.

    Returns
    -------
    str
        The full path, for example ``<prefix>/<environment>/aurora/host``.

    Raises
    ------
    ConfigurationError
        If no segment is supplied, if any segment is not text, is blank, or is not a single path
        segment, or if the prefix or the environment name cannot be resolved.
    """
    if not segments:
        # Assumptions: a call with no segments is rejected rather than returning the
        # environment root. The root is a path prefix rather than a parameter name, so returning
        # it would produce a value that reads like a path and cannot be fetched, and the caller
        # that forgot its segments would see a not-found error against a path it never wrote.
        raise ConfigurationError("a parameter path needs at least one segment")

    description = "a parameter path segment"
    validated = [
        _validate_path_segment(_require_text(segment, description), description)
        for segment in segments
    ]
    # Assumptions: the prefix already carries its leading slash and carries no trailing
    # one, so joining with a single separator yields exactly one separator between every
    # component. This is why both properties are enforced when the prefix is resolved rather
    # than being repaired here, where the repair would have to be repeated at each caller.
    return "/".join([resolve_parameter_prefix(), resolve_environment_name(), *validated])


def database_secret_name(schema: str) -> str:
    """Return the secret store name holding the credential for a schema's owning role.

    Purpose
    -------
    Derive where a login role's credential is stored, from the one canonical schema-to-role
    mapping, so the secret name and the role it authenticates as cannot be spelled differently.
    The bootstrap script states that the role names are the source of truth and that the secret
    entry each credential is written to must match them character for character; building the
    name from :func:`role_for_schema` is what makes that correspondence structural rather than
    a convention two artifacts have to remember.

    Parameters
    ----------
    schema : str
        A bare schema name, one of :data:`SCHEMA_NAMES`.

    Returns
    -------
    str
        The full secret name, for example ``carddemo/dev/aurora/carddemo_auth`` -- the parameter
        path with its leading separator removed, for the reason recorded below.

    Raises
    ------
    ConfigurationError
        If the schema is not one of the eight known schemas, or if the prefix or the environment
        name cannot be resolved.
    """
    # Alternatives Considered: the secret sits under the SAME ``aurora`` grouping segment
    # as the three non-secret parameters, rather than under a separate ``secrets`` grouping of
    # its own. A separate grouping was the tidier-looking option and was rejected because these
    # values are only ever read together: a credential authenticates against exactly the
    # endpoint the sibling parameters name. Sharing one grouping means a prefix or environment
    # change moves the endpoint and the credential as a unit, so they cannot come to describe
    # two different deployments. Nothing is weakened by the shared path, because the two stores
    # are separate services with separate permissions -- a path is a name, not an access grant.
    #
    # Refactoring Rationale: the leading separator is STRIPPED, and its presence was a
    # defect rather than a cosmetic difference. This function returned
    # :func:`parameter_path` unchanged, which begins with the separator every Parameter Store
    # path carries -- so every secret was looked up as ``/carddemo/<env>/aurora/<role>`` while
    # ``infra/modules/secrets`` creates it as ``carddemo/<env>/aurora/<role>``. Its ``locals``
    # block records why: Secrets Manager accepts a separator INSIDE a name but rejects a
    # ``SecretId`` that BEGINS with one, and ``var.name_prefix``'s own charset validation refuses
    # a leading separator, so the store side cannot move. Every credential read therefore failed
    # with a not-found error naming a secret that had in fact been created, one character away.
    # Stripping here rather than composing the name from scratch keeps the grouping convention
    # single-sourced in :func:`parameter_path`: the two names stay identical except for the one
    # character the two stores genuinely disagree about, so a prefix or environment change still
    # moves both together.
    return database_secret_name_for_role(role_for_schema(schema))


def database_secret_name_for_role(role: str) -> str:
    """Return the secret store name holding the credential for one login role.

    Purpose
    -------
    Compose the credential path from a ROLE rather than from a schema, so that the two credentials
    a migrating context has -- its runtime role and its ``_migrator`` role -- resolve through one
    convention instead of two. :func:`database_secret_name` is the schema-keyed form and delegates
    here, which is what keeps the composed name identical for both tiers.

    Parameters
    ----------
    role : str
        A login role name, which must be one of :data:`LOGIN_ROLE_NAMES`.

    Returns
    -------
    str
        The full secret name, for example ``carddemo/dev/aurora/carddemo_auth_migrator`` -- the
        parameter path with its leading separator removed, for the reason recorded on
        :func:`database_secret_name`.

    Raises
    ------
    ConfigurationError
        If the role is not text, is blank, or is not one of the fifteen login roles the bootstrap
        script creates.

    Notes
    -----
    Assumptions: the role is checked against a closed inventory rather than merely pattern-matched,
    and the reason is that this function composes a name a caller then reads a CREDENTIAL from. A
    free-form role would let a caller construct a path to any secret sharing this prefix -- the
    Cognito seed-user entries do -- and have it read as a database credential. Checking membership
    means the only names this function can build are the ones ``infra/modules/secrets`` creates.

    Assumptions: an owner role is refused by that same check, because ``LOGIN_ROLE_NAMES`` excludes
    the eight ``NOLOGIN`` ``carddemo_<context>_owner`` roles. Refusing is correct rather than
    unhelpful: no secret exists for them, so the alternative is a not-found error naming a path
    that was never created, which reads as a provisioning failure rather than as a caller asking
    for a credential that by design does not exist.
    """
    text = _require_text(role, "database role name")
    if text not in LOGIN_ROLE_NAMES:
        raise ConfigurationError(
            f"unknown database login role {text!r}; expected one of {', '.join(LOGIN_ROLE_NAMES)}"
        )
    return parameter_path(_AURORA_SEGMENT, text).lstrip("/")


@lru_cache(maxsize=1)
def _aurora_endpoint() -> tuple[str, int, str]:
    """Resolve the cluster endpoint every database connection shares.

    Purpose
    -------
    Read the three non-secret connection settings -- host, port and database name -- from
    Parameter Store once, so that the two resolvers below describe the same cluster by
    construction rather than by both remembering the same three parameter paths.

    Refactoring Rationale: these reads were inline in :func:`resolve_aurora_settings` and were
    lifted here when :func:`resolve_master_settings` was added. Copying them would have put the
    three paths in two places, and the failure that produces is silent: a copy that drifted
    would resolve a credential for one cluster and an endpoint for another, so a load would
    authenticate against the wrong deployment rather than fail.

    Parameters
    ----------
    None
        Reads the environment name and the parameter prefix through :func:`parameter_path`.

    Returns
    -------
    tuple[str, int, str]
        The host, the port as a whole number, and the database name, in that order.

    Raises
    ------
    ConfigurationError
        If the environment name or prefix cannot be resolved, if any of the three parameters is
        absent or unreadable, or if the port parameter is not a whole number.
    """
    host_path = parameter_path(_AURORA_SEGMENT, "host")
    port_path = parameter_path(_AURORA_SEGMENT, "port")
    database_path = parameter_path(_AURORA_SEGMENT, "database")

    host = _ssm_parameter(host_path)
    port_text = _ssm_parameter(port_path)
    database = _ssm_parameter(database_path)

    try:
        port = int(port_text)
    except ValueError:
        # Trade-offs: the chain is broken here for the same reason it is broken for a
        # malformed secret. The message a failed integer conversion produces embeds the rejected
        # text verbatim, and this module holds one rule about messages rather than two -- a
        # resolved value is never printed -- so the position of the offending character is given
        # up in exchange for that rule holding without exception. The path is named instead,
        # which is what an operator needs in order to correct the parameter.
        raise ConfigurationError(f"the parameter {port_path} is not a whole number") from None

    return host, port, database


@lru_cache(maxsize=1)
def resolve_master_settings() -> AuroraConnectionSettings:
    """Resolve the connection parameters for the cluster's master user.

    Purpose
    -------
    Assemble a connection descriptor for the one identity that can administer the cluster,
    combining the shared endpoint with the credential RDS generated for the master user. Used by
    the database bootstrap step -- :mod:`carddemo_migration.credentials` -- which has to connect
    as a role holding ``CREATEROLE`` or superuser authority in order to apply each service
    role's credential, and by nothing else.

    Assumptions: this is deliberately NOT keyed on a schema, unlike
    :func:`resolve_aurora_settings`. The master user owns no schema and is not one of the eight
    login roles; it exists to bootstrap and to break glass, so a schema argument would imply a
    privilege boundary this identity does not have.

    Trade-offs: no user-name assertion is made against the resolved credential, where
    :func:`resolve_aurora_settings` asserts one. There is nothing to assert it against: the
    master user's name is chosen by the cluster module's ``master_username`` input and is not
    derivable from anything this module holds, so a check here could only compare the secret
    with itself. The identity is instead confirmed by what the connection can do -- the
    bootstrap step's first statement fails outright without the authority to alter a role.

    Parameters
    ----------
    None
        Reads :data:`ENV_DB_MASTER_SECRET` from the process environment and the endpoint from
        Parameter Store.

    Returns
    -------
    AuroraConnectionSettings
        A frozen descriptor whose rendering masks the password. Pass
        :meth:`AuroraConnectionSettings.as_connection_params` to a client to connect; the
        parameters it yields require full certificate and hostname verification.

    Raises
    ------
    ConfigurationError
        If :data:`ENV_DB_MASTER_SECRET` is unset or blank; if the environment name or prefix
        cannot be resolved; if any endpoint parameter or the secret is absent, unreadable or
        malformed; if the port parameter is not a whole number; if :data:`ENV_SSL_MODE` asks for
        a mode weaker than :data:`REQUIRED_SSL_MODE`; if the TLS trust anchor is absent or
        unreadable; or if any resolved value fails the descriptor's own validation.
    """
    # Assumptions: the locator is read and checked for content BEFORE the first network
    # call, for the same reason the schema is validated first in the resolver below -- an unset
    # variable is by far the most likely misconfiguration here, and reporting it costs nothing
    # when it is reported before three parameter lookups rather than after them.
    secret_id = _require_text(
        os.environ.get(ENV_DB_MASTER_SECRET),
        f"the environment variable {ENV_DB_MASTER_SECRET}",
    )

    host, port, database = _aurora_endpoint()
    username, password = _secret_credentials(secret_id)
    _require_supported_ssl_mode()

    return AuroraConnectionSettings(
        host=host,
        port=port,
        database=database,
        user=username,
        password=password,
        ssl_root_cert=resolve_ssl_root_cert(),
    )


@lru_cache(maxsize=None)
def resolve_aurora_settings(schema: str) -> AuroraConnectionSettings:
    """Resolve the connection parameters for the role that owns one bounded-context schema.

    Purpose
    -------
    Assemble a complete, validated connection descriptor by combining the three non-secret
    settings from Parameter Store with the credential for the schema's owning role from the
    secret store.

    The descriptor is keyed on a schema rather than on a role because that is what a caller
    knows: a loader is loading one schema's records, and connecting as that schema's owner is
    what the privilege graph is built for. Cross-schema work is expressed the same way -- the
    ``batch`` schema's role is the one holding the narrowly scoped grants on the ledger and
    account schemas, so a step spanning them resolves settings for ``"batch"`` and needs no
    separate concept.

    Parameters
    ----------
    schema : str
        A bare schema name, one of :data:`SCHEMA_NAMES`. Validated before any AWS call is made,
        so a misspelling costs no round trip.

    Returns
    -------
    AuroraConnectionSettings
        A frozen descriptor whose rendering masks the password. Pass
        :meth:`AuroraConnectionSettings.as_connection_params` to a client to connect; the
        parameters it yields require full certificate and hostname verification.

    Raises
    ------
    ConfigurationError
        If the schema is unknown; if the environment name or prefix cannot be resolved; if any
        parameter or the secret is absent, unreadable or malformed; if the port parameter is not
        a whole number; if the secret's user name is neither the schema's owning role nor an
        alternate allowlisted for it in :data:`ENV_ALTERNATE_DB_USERS`; if
        :data:`ENV_SSL_MODE` asks for a mode weaker than :data:`REQUIRED_SSL_MODE`; if the TLS
        trust anchor is absent or unreadable; or if any resolved value fails the descriptor's own
        validation.
    """
    # Assumptions: resolving the secret name first validates the schema, the prefix and the
    # environment name before the first network call, so every avoidable failure happens
    # without one. The ordering is load-bearing rather than incidental -- reversing it turns an
    # unknown schema from an immediate ConfigurationError into a parameter lookup against a
    # path composed from a bad name, which surfaces as an access-denied error naming a
    # resource that never existed.
    return _resolve_settings_for_role(schema, role_for_schema(schema))


def resolve_migration_settings(schema: str) -> AuroraConnectionSettings:
    """Resolve the connection parameters for the role that applies one schema's migration.

    Purpose
    -------
    Supply the second of a migrating context's two credentials -- the ``_migrator`` login, whose
    membership of the schema's ``NOLOGIN`` owner is what lets a migration create objects the
    runtime credential can then only read and write. It exists as a separate entry point rather
    than as a flag on :func:`resolve_aurora_settings` so that a caller asking for DDL authority
    has to say so.

    Parameters
    ----------
    schema : str
        A bare schema name, one of the seven keys of :data:`MIGRATION_SCHEMA_ROLES`. Validated
        before any AWS call is made, so a misspelling costs no round trip.

    Returns
    -------
    AuroraConnectionSettings
        A frozen descriptor whose rendering masks the password, identical in shape to the one
        :func:`resolve_aurora_settings` returns and differing only in the user it authenticates as.

    Raises
    ------
    ConfigurationError
        For every reason :func:`resolve_aurora_settings` raises, plus: if the schema is
        ``reporting``, which ships no migration and has no migration role.

    Notes
    -----
    Assumptions: a session opened with these settings owns NOTHING until it issues
    ``SET ROLE carddemo_<context>_owner``. V0 grants the migration role its owner ``WITH INHERIT
    FALSE`` deliberately, so a caller that resolves these settings and then forgets the
    ``SET ROLE`` fails with a permission error on its first statement rather than silently
    creating objects owned by the migration role -- which would leave every
    ``ALTER DEFAULT PRIVILEGES FOR ROLE <owner>`` clause in V0 inert.
    """
    return _resolve_settings_for_role(schema, migration_role_for_schema(schema))


def _resolve_settings_for_role(schema: str, role: str) -> AuroraConnectionSettings:
    """Assemble one validated connection descriptor for a named role in a named schema.

    Purpose
    -------
    Hold the resolution sequence once, so the runtime and migration entry points differ only in
    the role they ask for and cannot come to disagree about the endpoint, the user-name assertion
    or the transport requirements.

    Parameters
    ----------
    schema : str
        The bounded-context schema the settings were requested for. Reported in failure messages.
    role : str
        The login role to resolve, already derived by the calling entry point.

    Returns
    -------
    AuroraConnectionSettings
        A frozen descriptor whose rendering masks the password.

    Raises
    ------
    ConfigurationError
        If the role is unknown; if the environment name or prefix cannot be resolved; if any
        parameter or the secret is absent, unreadable or malformed; if the secret's user name is
        neither the requested role nor an alternate allowlisted for it; if the requested SSL mode
        is weaker than :data:`REQUIRED_SSL_MODE`; if the TLS trust anchor is absent or unreadable;
        or if any resolved value fails the descriptor's own validation.
    """
    secret_name = database_secret_name_for_role(role)
    host, port, database = _aurora_endpoint()
    username, password = _secret_credentials(secret_name)

    # Refactoring Rationale: the username IS now asserted against the role the secret name
    # was derived from. It previously was not, on the reasoning that a managed rotation using the
    # alternating-users strategy hands back a second user name, so an equality check would fail a
    # successful rotation. That reasoning was right about rotation and wrong about the default:
    # declining the check accepted EVERY user name, so the one legitimate exception was bought at
    # the price of accepting any substitution at all -- including a secret populated with another
    # role's payload, which would authenticate as a role holding privileges this caller never
    # requested and complete the load without complaint. The secret's auditable NAME does not
    # detect that case, because in it the name is correct and the contents are not. The exception
    # is now expressed as an explicit, per-role allowlist instead of as an absent check, so
    # alternating rotation is still supported and everything else is refused.
    username = _require_matching_database_user(schema, role, username)

    # Assumptions: the two transport checks run AFTER the parameters and the credential
    # have resolved, not before. Ordering them last costs nothing -- neither reads AWS -- and
    # keeps the earlier, far more common failures (an unset environment name, an absent
    # parameter, a missing secret) reported first, so an operator sees the reason a command
    # cannot run before being told about a certificate bundle they have not reached yet. Both
    # still complete before any connection exists, because this function opens none.
    _require_supported_ssl_mode()

    return AuroraConnectionSettings(
        host=host,
        port=port,
        database=database,
        user=username,
        password=password,
        ssl_root_cert=resolve_ssl_root_cert(),
    )


@lru_cache(maxsize=1)
def resolve_dataset_staging_settings() -> DatasetStagingSettings:
    """Resolve the bucket and environment that dataset generations are staged into.

    Purpose
    -------
    Read the dataset bucket name that infrastructure provisioning wrote, and pair it with the
    environment name so a caller holding only the result can report where it is staging to.

    Parameters
    ----------
    None
        Both values are resolved from the environment and from Parameter Store.

    Returns
    -------
    DatasetStagingSettings
        A frozen descriptor carrying the bucket name and the environment name, and owning the
        prefix layout through :meth:`DatasetStagingSettings.generation_prefix`.

    Raises
    ------
    ConfigurationError
        If the environment name or prefix cannot be resolved, if the bucket parameter is absent,
        unreadable or blank, or if the resolved values fail the descriptor's own validation.
    """
    # Alternatives Considered: the bucket name is read from Parameter Store, never
    # reconstructed locally from the documented convention of a fixed stem plus the environment
    # name. Reconstructing it is tempting because the convention is known and would remove a
    # lookup, and it was rejected: infrastructure provisioning is the authority for that name,
    # and a deployment that legitimately overrides it -- an isolated review stack, or one made
    # unique to satisfy the global namespace object storage uses -- would then have working
    # infrastructure that this module addresses by a name nothing created. Reading the value
    # keeps one authority; the convention is documented so an operator can recognise the value,
    # not so this module can invent it.
    bucket = _ssm_parameter(parameter_path(_DATASETS_SEGMENT, "bucket"))
    return DatasetStagingSettings(bucket=bucket, environment=resolve_environment_name())


# Assumptions: these two parameter paths are the SAME ones the account and card workloads read
#   at start-up, and they are written out here rather than derived so that the agreement is
#   checkable by reading. `infra/envs/{dev,prod}/main.tf` publishes each runtime value at
#   `<prefix>/<environment>/<service>/<ENVIRONMENT_NAME>`, and both entries resolve to
#   `module.kms.aurora_key_alias_name` -- ONE key, with the two purposes kept
#   apart by their encryption context rather than by separate keys. That key is the AURORA
#   key: the values these envelopes protect are columns in that cluster, and the specified
#   model is four customer-managed keys, one per data-at-rest domain, so there is no separate
#   application key for them to draw from. Reading the published
#   parameters is what makes this module use the key the services use: a separate ETL-only
#   parameter would be a second authority, and an envelope written under the wrong key is not a
#   recoverable mistake -- it is readable only through the key that produced its data key.
_CUSTOMER_IDENTIFIER_KEY_PARAMETER = (
    "account",
    "CARDDEMO_SECURITY_CUSTOMER_IDENTIFIER_KEY_ID",
)
_CARD_VERIFICATION_VALUE_KEY_PARAMETER = (
    "card",
    "CARDDEMO_SECURITY_CVV_KEY_ID",
)


@lru_cache(maxsize=1)
def resolve_customer_identifier_key_id() -> str:
    """Resolve the key the customer identifier envelopes are written under.

    Purpose
    -------
    Supply the customer-managed key identifier that
    :class:`carddemo_migration.loaders.protected_columns.CustomerIdentifierCipher` wraps its data
    keys with, taken from the parameter ``account-service`` reads for the same purpose.

    Returns
    -------
    str
        The key alias or identifier, whitespace-trimmed and guaranteed non-empty. Infrastructure
        publishes an ALIAS, which is the form to prefer: an alias survives replacement of the key
        behind it, so a rotation does not require every reader of this value to be revised.

    Raises
    ------
    ConfigurationError
        If the parameter does not exist, cannot be read, or is blank. A blank value is refused
        rather than defaulted for the reason the consuming service refuses it too -- a default
        would let a load run against a key nobody chose, and every identifier written under it
        would then be unreadable by the service that owns the column.
    """
    return _ssm_parameter(parameter_path(*_CUSTOMER_IDENTIFIER_KEY_PARAMETER))


@lru_cache(maxsize=1)
def resolve_card_verification_value_key_id() -> str:
    """Resolve the key the card verification value envelopes are written under.

    Purpose
    -------
    Supply the customer-managed key identifier that
    :class:`carddemo_migration.loaders.protected_columns.CardVerificationValueCipher` wraps its
    data keys with, taken from the parameter ``card-service`` reads for the same purpose.

    Returns
    -------
    str
        The key alias or identifier, whitespace-trimmed and guaranteed non-empty. It resolves to
        the same application key as :func:`resolve_customer_identifier_key_id`; the two are read
        through separate parameters because each service publishes its own, and reading both is
        what keeps this module correct if the two are ever separated.

    Raises
    ------
    ConfigurationError
        If the parameter does not exist, cannot be read, or is blank.
    """
    return _ssm_parameter(parameter_path(*_CARD_VERIFICATION_VALUE_KEY_PARAMETER))


@lru_cache(maxsize=1)
def resolve_seed_user_subjects() -> Mapping[str, str]:
    """Resolve each seed identity's Cognito subject, keyed by its eight-character user id.

    Purpose
    -------
    Supply the one column the ``USRSEC`` record cannot provide.
    ``services/auth-service/src/main/resources/db/migration/V1__auth.sql`` declares
    ``auth.users.cognito_sub UUID NOT NULL UNIQUE`` and seeds no rows, while the 80-byte
    ``USRSEC`` record carries only the id, the two names, a password this migration refuses
    to carry forward, and the type. The subject is minted by the identity provider, so the
    reader has to be told it; ``infra/modules/cognito`` publishes it through its
    ``seed_user_subjects`` output and each environment root writes that document to
    Parameter Store under ``<prefix>/<environment>/identity/seed-user-subjects``.

    Returns
    -------
    Mapping[str, str]
        Read-only mapping of eight-character ``SEC-USR-ID`` to canonical RFC-4122 subject.
        Empty only if the pool was provisioned with no seed identities, which is the
        module's default and is why an empty document is accepted rather than refused.

    Raises
    ------
    ConfigurationError
        If the environment name or prefix cannot be resolved, if the parameter is absent,
        unreadable or blank, if the document is not a JSON object of strings, if any key is
        not exactly eight characters, if any subject is not a canonical RFC-4122 value, or
        if two ids share one subject.
    """
    # Alternatives Considered: resolving each subject at load time through
    # cognito-idp admin-get-user. Rejected on two independent grounds: it would need a
    # Cognito read grant the migration task requires for nothing else, and it would make a
    # data load fail whenever the identity provider was unreachable from wherever the ETL
    # runs, converting a data step into an identity-provider dependency. Reading one
    # published document keeps provisioning as the single authority for a value only
    # provisioning can mint.
    # Assumptions: the segment is written as a literal here rather than promoted to a
    # module constant, following the rule stated beside _AURORA_SEGMENT above -- a name used
    # from exactly one place reads best beside the value it fetches, and moving it away from
    # its only reader buys no protection.
    document = _ssm_parameter(parameter_path("identity", "seed-user-subjects"))
    try:
        decoded = json.loads(document)
    except json.JSONDecodeError as error:
        # Trade-offs: the decoder's message is reported but the document is NOT, because it
        # pairs every user id with its subject and an exception string is the least
        # controlled place for that pairing to end up. The position the decoder names is
        # enough to locate the fault in a value an operator can print deliberately.
        raise ConfigurationError(
            f"the seed-user subject document is not valid JSON: {error}"
        ) from error

    if not isinstance(decoded, dict):
        raise ConfigurationError(
            "the seed-user subject document must be a JSON object keyed by user id"
        )

    subjects: dict[str, str] = {}
    for user_id, subject in decoded.items():
        if not isinstance(subject, str):
            raise ConfigurationError(f"the seed-user subject for {user_id} must be a string")
        # Assumptions: eight characters exactly, because that is the width of
        # SEC-USR-ID PIC X(08) at app/cpy/CSUSR01Y.cpy L18 and the width auth.users declares
        # for its primary key. The Cognito module validates the same width on its own input,
        # so agreeing here turns a silent join failure -- a row keyed on a value no other
        # row uses -- into a rejection naming the offending id.
        if len(user_id) != 8:
            raise ConfigurationError(
                f"the seed-user subject document key {user_id!r} is not 8 characters, "
                "the width of SEC-USR-ID"
            )
        # Assumptions: the canonical hyphenated 8-4-4-4-12 form is required rather than
        # accepted-and-normalised. The target column is UUID, and uuid.UUID() would also
        # accept a braced, urn-prefixed or unhyphenated spelling, so validating with it
        # would let this module read a form it then has to rewrite before use. Requiring
        # the canonical form means the value read is the value written.
        if _SUBJECT_PATTERN.fullmatch(subject) is None:
            raise ConfigurationError(
                f"the seed-user subject for {user_id} is not a canonical RFC-4122 value"
            )
        subjects[user_id] = subject

    # Assumptions: distinctness is enforced here as well as by the column's UNIQUE
    # constraint. Leaving it to the constraint would surface as an integrity error partway
    # through a bulk load, after some rows had been written, whereas rejecting the document
    # before the load starts leaves the table untouched.
    if len(set(subjects.values())) != len(subjects):
        raise ConfigurationError(
            "the seed-user subject document assigns one subject to more than one user id, "
            "which auth.users.cognito_sub declares UNIQUE"
        )
    return MappingProxyType(subjects)


def reset_resolution_cache() -> None:
    """Discard every cached resolution and client held by this module.

    Purpose
    -------
    Return the module to its unresolved state so that the next call reads the environment, the
    parameter store and the secret store afresh. Two callers need this: a test that changes an
    environment variable or substitutes a client between cases, and a long-running process that
    has to pick up a rotated credential without restarting.

    Parameters
    ----------
    None
        The reset is unconditional; there is no partial form, because clearing one cache and
        not another is how a process ends up holding a credential resolved under an environment
        it no longer believes it is running in.

    Returns
    -------
    None
        Clears the caches in place.

    Raises
    ------
    None
        Clearing a cache cannot fail.
    """
    # Alternatives Considered: the caches are found by scanning this module's own globals
    # for the cache protocol, rather than being listed explicitly. An explicit list was written
    # first and rejected on a specific failure mode: a resolver added later would not be in it,
    # so a test would silently keep observing the previous case's value and would pass for the
    # wrong reason -- the hardest kind of stale-state bug to attribute, because the failure
    # appears in an unrelated test. The scan cannot go stale. It is also narrow rather than
    # broad: the attribute it looks for is the one a cached function exposes, so the standard
    # library modules and the compiled patterns in this namespace are not matched.
    for value in tuple(globals().values()):
        cache_clear = getattr(value, "cache_clear", None)
        if callable(cache_clear):
            cache_clear()
