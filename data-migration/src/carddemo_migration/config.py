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
``reporting``. The first seven are each owned by the matching ``carddemo_*`` login role; the
eighth, ``reporting``, is owned by the dedicated ``NOLOGIN`` role ``carddemo_reporting_owner``
for the reason recorded on :data:`OWNED_SCHEMA_ROLES`. The names are mirrored
from ``data-migration/sql/V0__schemas_and_roles.sql``, which creates them and is their single
source of truth; this module only names them, and a name that disagrees with that script is a
defect here rather than a variant spelling.

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
from collections.abc import Mapping
from dataclasses import dataclass
from datetime import date, datetime
from functools import lru_cache
from types import MappingProxyType
from typing import Any

# WHY : Trade-offs: an explicit ``__all__`` is declared here even though the package's own
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
# WHY : Assumptions: the entries are grouped -- constants, then classes, then functions -- and
# each group is alphabetical, rather than the whole list being one alphabetical run. A single
# run is what a sorting tool would produce and it reads worse here, because case-sensitive
# ordering interleaves the groups: ``DEFAULT_PARAMETER_PREFIX`` would land between
# ``ConfigurationError`` and ``DatasetStagingSettings``, separating the two classes. The
# grouping is therefore deliberate and is not a sort that was left unfinished.
__all__ = [
    "DEFAULT_PARAMETER_PREFIX",
    "DEFAULT_SSL_ROOT_CERT",
    "ENV_ALTERNATE_DB_USERS",
    "ENV_ENVIRONMENT",
    "ENV_PARAMETER_PREFIX",
    "ENV_SSL_MODE",
    "ENV_SSL_ROOT_CERT",
    "REDACTED",
    "OWNED_SCHEMA_ROLES",
    "REQUIRED_SSL_MODE",
    "SCHEMA_NAMES",
    "SCHEMA_ROLES",
    "AuroraConnectionSettings",
    "ConfigurationError",
    "DatasetStagingSettings",
    "database_secret_name",
    "owner_role_for_schema",
    "parameter_path",
    "quote_identifier",
    "quoted_schema",
    "reset_resolution_cache",
    "resolve_alternate_database_users",
    "resolve_aurora_settings",
    "resolve_dataset_staging_settings",
    "resolve_environment_name",
    "resolve_parameter_prefix",
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

    # WHY : Trade-offs: the class carries no structured attributes -- no error code, no
    # retryable flag. Adding them was considered and rejected: nothing in this package
    # branches on the *kind* of configuration failure, because a missing parameter and a
    # malformed one are equally terminal for a load that has not started yet, and a field
    # nobody reads is a field that goes stale. The accepted cost is that a future caller
    # wanting to branch has to match on the message or subclass this type.
    __slots__ = ()


# WHY : Assumptions: the environment name has NO default and is required. It selects which
# deployment's parameters and credentials a command resolves, so a default would make the
# most dangerous possible mistake -- a command intended for one environment quietly loading
# another's data -- into the behaviour that happens when a variable is forgotten. Failing with
# the name of the unset variable costs one restart; loading production data into the wrong
# cluster is not recoverable by restarting.
ENV_ENVIRONMENT = "CARDDEMO_ENVIRONMENT"

# WHY : Trade-offs: the parameter prefix is overridable but does have a default, which is the
# opposite of the decision immediately above. The asymmetry is deliberate: a wrong prefix
# resolves nothing and fails immediately with the path it looked for, whereas a wrong
# environment resolves successfully against the wrong deployment. Only the second failure mode
# is silent, so only the second one is denied a default. The override exists so that a stack
# provisioned under a different parameter namespace -- an isolated review deployment sharing
# one account, for instance -- needs no code change.
ENV_PARAMETER_PREFIX = "CARDDEMO_PARAMETER_PREFIX"

# WHY : Assumptions: the leading slash is part of the value, and its absence is rejected
# rather than repaired. Parameter Store distinguishes a hierarchical name, which begins with a
# slash and can be fetched by path, from a flat one, which cannot; silently prepending a slash
# would hide that the caller asked for something else. No trailing slash is carried, because
# :func:`parameter_path` joins with a single separator and two would produce an empty path
# segment that resolves to nothing.
DEFAULT_PARAMETER_PREFIX = "/carddemo"

# WHY : Refactoring Rationale: the transport mode is a CONSTANT rather than a setting, and that
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
# WHY : Alternatives Considered: ``require`` was considered and rejected. It guarantees
# encryption but performs NO certificate or hostname verification, so it defends against a
# passive observer and not against the endpoint being something else -- which is the failure
# this module is positioned to prevent, because it is the component that decides what host to
# connect to. ``verify-ca`` was also rejected: it validates the chain but not the name, so any
# certificate issued by the trusted authority is accepted for any endpoint. ``verify-full`` is
# the only mode that checks both, and it is the strongest mode libpq offers, so there is no
# setting to expose -- anything an operator could select from here would be weaker than the
# fixed value.
REQUIRED_SSL_MODE = "verify-full"

# WHY : Trade-offs: an environment variable exists for the mode even though the mode is fixed,
# and it exists ONLY so that lowering it fails loudly. An operator debugging a TLS failure
# reaches for ``sslmode`` first; with no variable to set, the next step is a local edit to this
# file, which is invisible to review and ships. A variable whose only accepted value is
# :data:`REQUIRED_SSL_MODE` turns that attempt into a message naming why the mode is not
# negotiable. Note that libpq also honours ``PGSSLMODE`` from the process environment, but an
# explicit connection keyword takes precedence over it, so emitting ``sslmode`` unconditionally
# is what makes an inherited ``PGSSLMODE=disable`` inert rather than authoritative.
ENV_SSL_MODE = "CARDDEMO_DB_SSL_MODE"

# WHY : Assumptions: ``verify-full`` requires a trust anchor, and the anchor is pinned to the
# AWS-published Aurora certificate bundle rather than left to the operating system's trust
# store. The system store was considered and rejected as too broad: it trusts every publicly
# trusted authority, so a certificate issued by any one of them for this endpoint's name would
# verify, whereas the AWS bundle trusts only the authorities that can legitimately certify an
# Aurora endpoint. The default is a filesystem PATH, which is not a secret and not an endpoint
# -- the same category as :data:`DEFAULT_PARAMETER_PREFIX` -- so committing it breaks no
# no-secrets rule. The bundle itself is placed at this path by ``data-migration/Dockerfile``;
# a checkout is unaffected, because a checkout opens no connection.
DEFAULT_SSL_ROOT_CERT = "/etc/ssl/certs/aws-rds-global-bundle.pem"

# WHY : Trade-offs: the anchor's LOCATION is overridable while the mode is not, and the
# asymmetry is deliberate. An override of the path cannot weaken verification -- whatever it
# names still has to certify the endpoint under ``verify-full`` -- and it is genuinely needed,
# because the bundle sits at a different path when a command runs outside the container image,
# and because AWS republishes the bundle as authorities are rotated. The value is constrained
# to an absolute path so that it cannot be a URL, an inline certificate or a relative path
# resolved against whatever directory the process happens to have started in.
ENV_SSL_ROOT_CERT = "CARDDEMO_DB_SSL_ROOT_CERT"

# WHY : Refactoring Rationale: the credential's user name is now asserted against the role the
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
# WHY : Assumptions: the allowlist is keyed BY ROLE (``role=alternate``, comma separated) rather
# than being a flat list of accepted user names. A flat list would be satisfied by any entry for
# any schema, so allowlisting one role's rotation clone would simultaneously permit that clone --
# or any other listed name -- as the credential for all eight. Keying by role keeps each
# exception scoped to the role it was granted for, and an alternate that is itself one of the
# eight owning roles is refused outright, because that spelling is not a rotation clone but a
# cross-role substitution.
ENV_ALTERNATE_DB_USERS = "CARDDEMO_DB_ALTERNATE_USERS"

# WHY : Trade-offs: the redaction is a fixed constant that encodes nothing about the value it
# stands for -- deliberately weaker than the reference record codec under ``tests/helpers/``,
# which masks a sensitive field to a short deterministic digest so that a masked diff still
# reveals *which* field changed. That property is worth having for a card number inside a
# golden comparison and is a liability for a credential: a digest is stable, so anyone holding
# two rendered objects can tell whether the password is the same in both, and anyone holding a
# guess can confirm it by hashing. The accepted cost is that two differently-configured
# processes render identically here, which is acceptable because the fields that actually
# identify a misconfiguration -- host, port, database, user -- are all rendered in clear.
REDACTED = "***redacted***"

# WHY : Assumptions: a path segment is restricted to the characters Parameter Store accepts in
# a name component, and must start with an alphanumeric. The pattern is applied to the
# environment name, to each segment of the prefix, and to each segment appended to a path, so
# a value carrying a slash cannot smuggle in an extra level of hierarchy and reach a parameter
# the caller did not name. Rejecting it here turns that into a startup failure instead of a
# lookup that succeeds against the wrong path.
_PATH_SEGMENT_PATTERN = re.compile(r"\A[A-Za-z0-9][A-Za-z0-9_.-]*\Z")

# WHY : Assumptions: an object-storage prefix component may not be empty and may not contain a
# forward slash, because the slash is the separator that gives the staged layout its shape. A
# domain or dataset carrying one would silently deepen the hierarchy, so a staged generation
# would land somewhere a reader looking for it by convention would never find it. Every other
# character is left alone: object keys are far more permissive than parameter names, and
# narrowing them further here would reject a legitimate dataset name for no benefit.
_PREFIX_COMPONENT_PATTERN = re.compile(r"\A[^/]+\Z")

# WHY : Assumptions: an allowlisted alternate user name must be a plain, unquoted PostgreSQL
# role name -- it starts with a letter or underscore, continues with letters, digits, underscore
# or dollar, and is at most the 63 bytes the server truncates at. The pattern is narrow on
# purpose. A name needing quotes to be legal is not one this project creates: every role in
# ``data-migration/sql/V0__schemas_and_roles.sql`` is a bare ``carddemo_*`` identifier, and the
# alternating-users rotation strategy derives its second user by suffixing that name. Accepting a
# name outside this shape would mean accepting one whose spelling depends on how it is quoted,
# and a comparison against an unquoted secret payload cannot settle that.
_ROLE_NAME_PATTERN = re.compile(r"\A[A-Za-z_][A-Za-z0-9_$]{0,62}\Z")

# WHY : Assumptions: the generation number is rendered in a fixed four digits so that staged
# generations under one date sort correctly as strings, which is the only ordering an
# object-storage listing offers. The width is declared once and the accepted range is derived
# from it, rather than both being written out, because the two are one fact: a value above the
# range would still format -- Python widens rather than truncates, so 10000 renders as
# ``gen=10000`` -- and would then sort ahead of ``gen=9999``, defeating the padding entirely.
_GENERATION_DIGITS = 4
_MAX_GENERATION = 10**_GENERATION_DIGITS - 1

# WHY : Assumptions: these are the parameter-store error codes that mean "the caller named
# something that is not there", as distinct from a permission or transport failure. They are
# matched by code rather than by exception class so that no service exception type has to be
# imported at module scope, which is what keeps this module importable with the SDK absent;
# see :func:`_aws_client`. ``ParameterVersionNotFound`` is included because a parameter that
# exists but has had the referenced version removed is, to a caller that named a path, the
# same actionable condition as one that was never created.
_MISSING_PARAMETER_CODES = frozenset({"ParameterNotFound", "ParameterVersionNotFound"})

# WHY : Assumptions: the secret store reports an absent secret under a single code, and a
# secret that is scheduled for deletion reports the same one, so both resolve to the same
# actionable message. The distinction between them is not one this module can act on: neither
# yields a credential, and both are fixed by provisioning rather than by retrying.
_MISSING_SECRET_CODES = frozenset({"ResourceNotFoundException"})

# WHY : Trade-offs: the two grouping segments are named once here because each is used from
# more than one place -- ``aurora`` by the three parameter lookups and by the secret name,
# ``datasets`` by the bucket lookup -- so a rename must not be able to move one and leave the
# other pointing at a path infrastructure no longer writes. The leaf names (``host``, ``port``,
# ``database``, ``bucket``) are deliberately left as literals at their single use site, where
# they sit beside the value they fetch and read as the path they form; promoting a
# single-use literal to a constant moves it away from its only reader for no protection.
_AURORA_SEGMENT = "aurora"
_DATASETS_SEGMENT = "datasets"

# WHY : Assumptions: these are the two keys the secret document is read by, and they match the
# field names a managed relational-database credential is written with. Naming them as
# constants rather than inline is what lets the ad-hoc and unit tests build a payload from the
# same two strings the resolver reads, so a test cannot pass against a spelling the resolver
# does not use. No other key is read: a secret carrying extra fields -- an engine name, a host,
# a port -- is accepted, and those fields are ignored in favour of Parameter Store, so that
# there is exactly one authority for the endpoint rather than two that can disagree.
_SECRET_USERNAME_KEY = "username"
_SECRET_PASSWORD_KEY = "password"

# WHY : Trade-offs: the eight entries are declared as a plain dict wrapped in a read-only
# proxy rather than as an enum or a set of module constants. An enum was considered and
# rejected: the values here are database identifiers that appear in composed SQL and in a
# secret name, so every use site would have to unwrap ``.value``, and an enum member that
# reaches a query by accident renders as ``Schema.LEDGER`` rather than failing. The proxy is
# what makes the mapping genuinely read-only -- a bare module-level dict is mutable, so any
# importer could add a ninth schema at runtime and every consumer would silently believe it.
# The accepted cost is that a proxy cannot be updated in place, which is the intent.
#
# WHY : Refactoring Rationale: this mapping answers "which role does the ETL CONNECT AS for
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
    # by side. Seven of the eight are also the schema's owner; ``reporting`` is not, and the
    # note on that entry records the difference at the point a reader meets it.
    #
    # WHY : Assumptions: every schema name here is stored BARE, and the quoted form is obtained
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
    # WHY : Assumptions: this entry is the one that is NOT a schema owner. The reporting
    # context owns no table: it reads the other contexts' data through the SELECT-only grants
    # the bootstrap script establishes, and the cross-schema views it reads through live in a
    # reporting schema owned by the dedicated ``NOLOGIN`` role ``carddemo_reporting_owner``
    # that the script creates for exactly that purpose. ``carddemo_reporting`` is
    # therefore the role the ETL connects as for reporting work, with USAGE and SELECT and no
    # CREATE anywhere. Anything that needs an OWNER -- creating a view, altering one -- must
    # not read it from here; :func:`owner_role_for_schema` refuses this schema explicitly and
    # says why.
    "reporting": "carddemo_reporting",
}

#: Read-only mapping of each bounded-context schema name to the ``carddemo_*`` login role the
#: ETL connects as for work in that schema. Seven of the eight roles also own their schema;
#: ``reporting`` does not -- use :data:`OWNED_SCHEMA_ROLES` when ownership is what matters.
SCHEMA_ROLES: Mapping[str, str] = MappingProxyType(_SCHEMA_ROLES)

# WHY : Assumptions: this is the subset of :data:`SCHEMA_ROLES` in which the role genuinely
# owns the schema, and it is derived rather than typed out a second time, so the two cannot
# disagree about the seven they share. ``reporting`` is the single exclusion, named as a
# constant beside the derivation so the exclusion is legible instead of arithmetic.
# WHY : Trade-offs: a derived subset means adding a ninth owning context is one edit above and
# nothing here, while adding a second non-owned schema is one edit here. Two hand-maintained
# lists of overlapping names was the alternative and is how one of them ends up with seven
# entries and the other with eight.
_UNOWNED_SCHEMA = "reporting"

_OWNED_SCHEMA_ROLES: dict[str, str] = {
    schema: role for schema, role in _SCHEMA_ROLES.items() if schema != _UNOWNED_SCHEMA
}

#: Read-only mapping of the seven bounded-context schemas that a ``carddemo_*`` role owns, to
#: that owning role. Mirrors the seven ``CREATE SCHEMA ... AUTHORIZATION <role>`` statements in
#: ``data-migration/sql/V0__schemas_and_roles.sql``; the eighth schema, ``reporting``, is owned
#: by the dedicated ``NOLOGIN`` role ``carddemo_reporting_owner`` and is deliberately absent:
#: that role holds no credential, so no loader can ever connect as it.
OWNED_SCHEMA_ROLES: Mapping[str, str] = MappingProxyType(_OWNED_SCHEMA_ROLES)

# WHY : Assumptions: the tuple is derived from the mapping rather than typed out a second
# time. Two hand-maintained lists of the same eight names is how one of them ends up with
# seven, and a dict preserves insertion order, so the tuple is already in the bootstrap
# script's own order without that order having to be restated.
SCHEMA_NAMES: tuple[str, ...] = tuple(_SCHEMA_ROLES)


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
    # WHY : Assumptions: this is the module's first validation, and it establishes the form every
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
    # WHY : Assumptions: the type is checked rather than coerced with ``str()``. A JSON secret
    # payload whose ``password`` field arrived as a number or a nested object would otherwise
    # be accepted and stringified into something that cannot authenticate, and the resulting
    # failure would surface as a rejected login rather than as the malformed secret it is.
    if not isinstance(value, str):
        raise ConfigurationError(f"{description} is not a text value")
    stripped = value.strip()
    if not stripped:
        raise ConfigurationError(f"{description} is empty")
    # WHY : Trade-offs: surrounding whitespace is stripped rather than rejected. A trailing
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
        # WHY : Assumptions: the rejected value is described but not echoed, and the accepted
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
    # WHY : Alternatives Considered: the path is checked and returned VERBATIM rather than
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
    # WHY : Assumptions: an embedded NUL is rejected explicitly instead of being left to the
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
    # WHY : Assumptions: a NUL is rejected rather than escaped because PostgreSQL cannot carry
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

    This is the CONNECTION role, which is not always the schema's owner. For seven of the eight
    schemas the two coincide; for ``reporting`` they do not, because that schema is owned by the
    ``NOLOGIN`` role ``carddemo_reporting_owner`` while ``carddemo_reporting`` holds only USAGE
    and SELECT. Call :func:`owner_role_for_schema` when ownership is the question being asked.

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
        # WHY : Trade-offs: the message lists the eight accepted names. They are neither
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
        A bare schema name. Must be one of the seven keys of :data:`OWNED_SCHEMA_ROLES`, which
        is :data:`SCHEMA_NAMES` without ``reporting``.

    Returns
    -------
    str
        The ``carddemo_*`` role named in that schema's ``CREATE SCHEMA ... AUTHORIZATION``
        statement in ``data-migration/sql/V0__schemas_and_roles.sql``.

    Raises
    ------
    ConfigurationError
        If the schema is not text, is blank, is not one of the eight known schemas, or is
        ``reporting`` -- which is a known schema that no service role owns.
    """
    text = _require_text(schema, "schema name")
    role = _OWNED_SCHEMA_ROLES.get(text)
    if role is None:
        # WHY : Trade-offs: the two failures are reported with DIFFERENT messages rather than
        # one shared "not an owned schema", because they call for different actions. A name
        # that is not a schema at all is a typo, and the accepted set is listed for the same
        # reason :func:`role_for_schema` lists it. ``reporting`` is not a typo: it is a real
        # schema whose owner is deliberately not a service role, so the message says who owns
        # it instead, which is the answer the caller actually needs.
        if text in _SCHEMA_ROLES:
            raise ConfigurationError(
                f"schema {text!r} is not owned by a carddemo_* role; it is owned by the "
                "NOLOGIN role carddemo_reporting_owner that "
                "data-migration/sql/V0__schemas_and_roles.sql creates, because "
                f"{_SCHEMA_ROLES[text]} is read-only and a schema owner would hold CREATE "
                "on it implicitly"
            )
        raise ConfigurationError(
            f"unknown schema {text!r}; expected one of {', '.join(OWNED_SCHEMA_ROLES)}"
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
    # WHY : Assumptions: membership is checked by resolving the owning role and discarding it,
    # rather than by testing the mapping directly, so that this function and
    # :func:`role_for_schema` cannot drift on what counts as a known schema. The eight names
    # are validated against one declaration through one code path.
    role_for_schema(schema)
    # WHY : Trade-offs: all eight names are quoted, not just the reserved one. Quoting only
    # ``authorization`` would leave every call site needing to know which names are keywords --
    # a set that belongs to the server's grammar and grows between major versions, not to this
    # module -- and the special case would be invisible at the point a new schema is added.
    # Uniform quoting costs two characters per rendered name and denotes the identical object,
    # because these names are already lower case and so survive the fold unchanged.
    return quote_identifier(schema.strip())


# WHY : Trade-offs: both descriptors are frozen and slotted. Frozen because a settings object
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

    Notes
    -----
    There is deliberately no ``ssl_mode`` attribute. The mode is the module constant
    :data:`REQUIRED_SSL_MODE` and :meth:`as_connection_params` emits it unconditionally, so no
    instance of this class -- however it was constructed, including directly by a test -- can
    describe a connection that would be made without full certificate and hostname
    verification. Making it a field would make the strongest guarantee in this module a
    per-instance choice.
    """

    host: str
    port: int
    database: str
    user: str
    password: str

    # WHY : Trade-offs: this field carries a default while the five above do not. The default is
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
        # WHY : Assumptions: normalisation goes through ``object.__setattr__`` because the
        # class is frozen and a plain assignment would raise. This is the documented idiom for
        # a frozen dataclass that needs to canonicalise its own input, and doing it here rather
        # than in the resolver is what makes the guarantee unconditional -- a test or a future
        # loader that constructs an instance directly gets the same normalised object that a
        # resolved one is, so no consumer has to ask which path produced the value it holds.
        object.__setattr__(self, "host", _require_text(self.host, "database host"))
        object.__setattr__(self, "database", _require_text(self.database, "database name"))
        object.__setattr__(self, "user", _require_text(self.user, "database user"))

        # WHY : Assumptions: the password is checked for emptiness but is deliberately NOT
        # stripped, unlike every other field above. A hostname or a database name cannot
        # meaningfully begin or end with a space, so stripping one there only ever removes an
        # artefact; a generated credential can legitimately contain any printable character,
        # and silently trimming one would produce a password that differs from the stored
        # secret by a byte and fails authentication with no indication why. Emptiness is still
        # rejected, because an empty credential cannot authenticate under any configuration.
        if not isinstance(self.password, str) or not self.password:
            raise ConfigurationError("database password is empty")

        # WHY : Assumptions: ``bool`` is excluded explicitly because it is a subclass of
        # ``int`` in Python, so a port passed as ``True`` would otherwise validate and connect
        # to port 1. The upper bound is the largest value a TCP port number can express, so a
        # parameter holding a year or a timestamp by mistake is rejected here rather than
        # producing an opaque socket error.
        if isinstance(self.port, bool) or not isinstance(self.port, int):
            raise ConfigurationError("database port is not an integer")
        if not 1 <= self.port <= 65535:
            raise ConfigurationError("database port is outside the range 1 to 65535")

        # WHY : Assumptions: the trust anchor's path is validated HERE rather than only where it
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
        # WHY : Trade-offs: the generated ``repr`` is replaced rather than augmented, because
        # the dataclass default renders every field including the credential, and it is
        # reached implicitly -- an f-string, a ``print``, an unhandled exception's argument
        # list, a test assertion diff. Overriding it is the only way to make the safe
        # rendering the default one rather than something each call site has to remember to
        # ask for. ``__str__`` is intentionally not defined, so it falls back to this method
        # and the two cannot diverge. The accepted cost is that the credential cannot be
        # inspected through a rendering at all, which is the point: a caller that genuinely
        # needs it reads ``.password`` explicitly, and that is a line a reviewer can see.
        #
        # WHY : Assumptions: the trust-anchor path is rendered in clear alongside the endpoint
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
        # WHY : Trade-offs: this method returns connection PARAMETERS and this module never
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
        # WHY : Trade-offs: a fresh dict is built on each call rather than a cached one being
        # returned. The dict is mutable and holds the credential, so sharing a single instance
        # would let one caller's edit reach another's connect call, and the cost of rebuilding
        # five entries is irrelevant beside opening a database connection. Returning an
        # immutable mapping instead was rejected because a client's connect call expects
        # keyword expansion, and a read-only proxy cannot be expanded with ``**``.
        #
        # WHY : Assumptions: the two TLS keywords are emitted on EVERY call and are not
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

    # WHY : Trade-offs: unlike :class:`AuroraConnectionSettings`, this class keeps the
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
        # WHY : Assumptions: both fields are normalised, with none of the exemption
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

        # WHY : Assumptions: a datetime is rejected even though it satisfies ``isinstance``
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

        # WHY : Assumptions: ``bool`` is excluded before the integer check for the same reason
        # as the database port -- it is a subclass of ``int``, so ``True`` would otherwise
        # render as ``gen=0001`` and stage a generation the caller never asked for.
        if isinstance(generation, bool) or not isinstance(generation, int):
            raise ConfigurationError("dataset generation is not an integer")
        if not 0 <= generation <= _MAX_GENERATION:
            raise ConfigurationError(
                f"dataset generation is outside the range 0 to {_MAX_GENERATION}"
            )

        # WHY : Assumptions: the date is rendered with ``isoformat`` rather than ``strftime``.
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
    # WHY : Assumptions: the two are returned as a tuple because they are siblings in the SDK's
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
    # WHY : Assumptions: the response document is navigated defensively rather than indexed,
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


@lru_cache(maxsize=None)
def _aws_client(service_name: str) -> Any:
    """Build and cache one AWS service client configured entirely from the environment.

    Purpose
    -------
    Provide the single point at which this module obtains an AWS client, so that both the
    configuration discipline below and the caching decision are made once rather than at each
    call site.

    Parameters
    ----------
    service_name : str
        The SDK service identifier, ``"ssm"`` or ``"secretsmanager"``.

    Returns
    -------
    Any
        The service client. Typed loosely because the SDK generates its client classes at run
        time from service models, so no importable static type exists to annotate.

    Raises
    ------
    ConfigurationError
        If the AWS SDK is not installed, or if the client cannot be constructed because the
        environment names no region.
    """
    # WHY : Alternatives Considered: the SDK is imported inside this function rather than at the
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

    # WHY : Alternatives Considered: no ``endpoint_url``, no ``region_name`` and no credentials
    # are passed. The SDK resolves all three from the environment on its own, and letting it do
    # so is what makes one code path correct everywhere: inside the batch staging task the
    # region and the task role's credentials arrive from the container environment, while
    # against the local emulator an ``AWS_ENDPOINT_URL`` variable redirects the same client with
    # nothing rebuilt. Passing a literal endpoint -- or branching on a "running locally" flag to
    # decide whether to pass one -- would create a second code path that only one of the two
    # environments ever exercises, so a defect in either would be invisible from the other. It
    # is the same discipline the reference emulator helper under ``tests/helpers/`` follows,
    # which takes its endpoint from the environment and never embeds one.
    try:
        return boto3.client(service_name)
    except _aws_error_types() as exc:
        raise ConfigurationError(
            f"the AWS client for {service_name} could not be created; the environment names no "
            f"usable region or credentials ({type(exc).__name__})"
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
    client = _aws_client("ssm")
    try:
        # WHY : Assumptions: decryption is explicitly not requested. Only the four non-secret
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
        # WHY : Trade-offs: the service error code is included but the service message is not.
        # A code is a fixed vocabulary term and is safe to print; a message is free text
        # generated by the service and is the one field that could echo back part of a request.
        # Chaining with ``from exc`` keeps the full message and the request identifier in the
        # traceback for anyone diagnosing the failure, which is where that detail belongs
        # rather than in a string that gets copied into an alarm notification.
        detail = code or type(exc).__name__
        raise ConfigurationError(f"the parameter {path} could not be read ({detail})") from exc

    # WHY : Assumptions: the response is navigated defensively for the same reason the error
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
    client = _aws_client("secretsmanager")
    try:
        response = client.get_secret_value(SecretId=secret_name)
    except _aws_error_types() as exc:
        code = _error_code(exc)
        if code in _MISSING_SECRET_CODES:
            # WHY : Assumptions: a secret scheduled for deletion reports this same code, and it
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
        # WHY : Assumptions: a secret holding only binary data is reported as the wrong KIND of
        # secret rather than as a missing one. The distinction is what the operator needs: the
        # secret is there and readable, and what is wrong is that it was written as a binary
        # blob instead of as the JSON credential document this module reads.
        raise ConfigurationError(
            f"the secret {secret_name} holds no text; a JSON credential document is expected"
        )

    try:
        payload = json.loads(document)
    except json.JSONDecodeError:
        # WHY : Trade-offs: this is the one failure in the module raised with ``from None``,
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

    # WHY : Assumptions: the password is validated here rather than through the shared text
    # check, because that helper strips surrounding whitespace and a credential must be carried
    # byte for byte. A generated password can legitimately begin or end with a space, and
    # trimming one would produce a value that differs from the stored secret and fails
    # authentication with nothing in the message to suggest why.
    password = payload.get(_SECRET_PASSWORD_KEY)
    if not isinstance(password, str) or not password:
        raise ConfigurationError(
            f"the {_SECRET_PASSWORD_KEY} in secret {secret_name} is missing or empty"
        )

    # WHY : Trade-offs: an immutable tuple is returned rather than the decoded dictionary. This
    # function is cached, so returning the dictionary would hand every caller a reference to the
    # same mutable object -- one caller popping a key, or overwriting the password after use in
    # an attempt to scrub it, would silently change what the next caller receives. The accepted
    # cost is positional unpacking at the call site, which is bounded because there is exactly
    # one such site.
    return (username, password)


# WHY : Trade-offs: every resolver below is cached for the lifetime of the process, and the
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
# WHY : Assumptions: the environment-derived values are cached too, which saves nothing
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
        # WHY : Assumptions: an unset variable is reported separately from a blank one because
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
        # WHY : Trade-offs: an empty or whitespace-only override falls back to the default
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
        # WHY : Assumptions: a trailing slash is rejected rather than trimmed. Parameter Store
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
    # WHY : Assumptions: the comparison is case-folded because libpq accepts the mode
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

    # WHY : Refactoring Rationale: existence is checked HERE and not in
    # ``AuroraConnectionSettings.__post_init__``, which checks only the path's shape. The split
    # follows what each place can promise. A descriptor is constructed directly by tests and is
    # frozen, hashable and cached, so making its validity depend on the filesystem would make
    # two identical descriptors differ by machine and would require every test to materialise a
    # certificate file. This function, by contrast, runs only on the resolution path that
    # precedes a real connection, which is exactly where an absent bundle is actionable.
    #
    # WHY : Trade-offs: a missing bundle is refused rather than being left to libpq. Under
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
    return path


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
    eight owning roles in :data:`SCHEMA_ROLES`, and a role may appear more than once, which is
    how a rotation that has provisioned more than one clone is expressed.

    Parameters
    ----------
    None
        Reads :data:`ENV_ALTERNATE_DB_USERS` from the process environment.

    Returns
    -------
    Mapping[str, frozenset[str]]
        A read-only mapping from owning role to the alternate user names allowlisted for it.
        Empty when the variable is unset or blank, which is the expected configuration.

    Raises
    ------
    ConfigurationError
        If any entry is empty, does not contain exactly one ``=``, names a role that is not one
        of the eight owning roles, has an alternate that is not a plain PostgreSQL role name, or
        has an alternate that is itself one of the eight owning roles.
    """
    raw = os.environ.get(ENV_ALTERNATE_DB_USERS)
    if raw is None or not raw.strip():
        return MappingProxyType({})

    description = f"the environment variable {ENV_ALTERNATE_DB_USERS}"
    owning_roles = frozenset(SCHEMA_ROLES.values())
    allowlist: dict[str, set[str]] = {}

    # WHY : Trade-offs: an empty entry is refused rather than skipped, so a trailing comma or a
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
            # WHY : Assumptions: an unrecognised role is refused rather than ignored. An ignored
            # entry is the worst outcome available here, because the operator believes an
            # exception is in force, the misspelling means it is not, and the discovery comes as
            # a refused connection during a rotation window. The eight accepted roles are named
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
            # WHY : Assumptions: allowlisting one owning role as another's alternate is refused
            # outright, and this is the guard that keeps the escape hatch from becoming a
            # privilege bridge. A rotation clone is a NEW user created for one role; a spelling
            # that names a different owning role is not a clone, and accepting it would let the
            # secret for, say, the reporting schema hand back the batch role -- which holds
            # write grants on the ledger and account schemas -- and be treated as legitimate.
            raise ConfigurationError(
                f"{description} allowlists an owning role as the alternate for role {role!r}; "
                f"an alternate must be a rotation user distinct from all eight owning roles"
            )
        allowlist.setdefault(role, set()).add(alternate)

    # WHY : Assumptions: the returned mapping is wrapped in a read-only proxy over frozen sets
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
    """
    if username == role:
        return username
    if username in resolve_alternate_database_users().get(role, frozenset()):
        return username
    # WHY : Assumptions: the rejected user name is NOT interpolated into the message, while the
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
        # WHY : Assumptions: a call with no segments is rejected rather than returning the
        # environment root. The root is a path prefix rather than a parameter name, so returning
        # it would produce a value that reads like a path and cannot be fetched, and the caller
        # that forgot its segments would see a not-found error against a path it never wrote.
        raise ConfigurationError("a parameter path needs at least one segment")

    description = "a parameter path segment"
    validated = [
        _validate_path_segment(_require_text(segment, description), description)
        for segment in segments
    ]
    # WHY : Assumptions: the prefix already carries its leading slash and carries no trailing
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
        The full secret name, for example ``<prefix>/<environment>/aurora/<role>``.

    Raises
    ------
    ConfigurationError
        If the schema is not one of the eight known schemas, or if the prefix or the environment
        name cannot be resolved.
    """
    # WHY : Alternatives Considered: the secret sits under the SAME ``aurora`` grouping segment
    # as the three non-secret parameters, rather than under a separate ``secrets`` grouping of
    # its own. A separate grouping was the tidier-looking option and was rejected because these
    # values are only ever read together: a credential authenticates against exactly the
    # endpoint the sibling parameters name. Sharing one grouping means a prefix or environment
    # change moves the endpoint and the credential as a unit, so they cannot come to describe
    # two different deployments. Nothing is weakened by the shared path, because the two stores
    # are separate services with separate permissions -- a path is a name, not an access grant.
    return parameter_path(_AURORA_SEGMENT, role_for_schema(schema))


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
    secret_name = database_secret_name(schema)
    host_path = parameter_path(_AURORA_SEGMENT, "host")
    port_path = parameter_path(_AURORA_SEGMENT, "port")
    database_path = parameter_path(_AURORA_SEGMENT, "database")

    host = _ssm_parameter(host_path)
    port_text = _ssm_parameter(port_path)
    database = _ssm_parameter(database_path)
    username, password = _secret_credentials(secret_name)

    try:
        port = int(port_text)
    except ValueError:
        # WHY : Trade-offs: the chain is broken here for the same reason it is broken for a
        # malformed secret. The message a failed integer conversion produces embeds the rejected
        # text verbatim, and this module holds one rule about messages rather than two -- a
        # resolved value is never printed -- so the position of the offending character is given
        # up in exchange for that rule holding without exception. The path is named instead,
        # which is what an operator needs in order to correct the parameter.
        raise ConfigurationError(f"the parameter {port_path} is not a whole number") from None

    # WHY : Refactoring Rationale: the username IS now asserted against the role the secret name
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
    username = _require_matching_database_user(schema, role_for_schema(schema), username)

    # WHY : Assumptions: the two transport checks run AFTER the parameters and the credential
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
    # WHY : Alternatives Considered: the bucket name is read from Parameter Store, never
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
    # WHY : Alternatives Considered: the caches are found by scanning this module's own globals
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
