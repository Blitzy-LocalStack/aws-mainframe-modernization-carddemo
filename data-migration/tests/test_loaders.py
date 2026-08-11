"""Exercise the ETL's two write paths -- the Aurora bulk load and the S3 generation staging.

Purpose
-------
Drive :mod:`carddemo_migration.loaders.aurora` and
:mod:`carddemo_migration.loaders.s3_stage` end to end against in-process doubles, and pin the
properties that make a cutover load trustworthy rather than merely successful: that a dataset
lands wholly or not at all, that money stays exact, that the anti-corruption projection drops
what it must and renames only what is documented, that a re-run adds nothing, that the loader
issues no statement its login role does not hold, and that a staged generation is the source
extract's bytes unchanged under the one prefix convention the pipeline agrees on.

Hermeticity is a property of the whole module, not of individual tests: nothing here needs an
AWS credential, a reachable database, a live endpoint, a container daemon or a network. Every
boundary is crossed through a double from ``conftest.py``.

Alternatives Considered: an emulator-backed test, reaching S3 and PostgreSQL the way the
reference parity suite does. That suite's ``tests/helpers/localstack_setup.py`` runs every AWS
call as an ``awslocal`` SUBPROCESS against a running emulator, which is the right design for
proving the emulated staging path works and the wrong one here, because it makes an external
process a prerequisite of every assertion. It was rejected on two independent grounds. A live
endpoint or a real credential makes this module unrunnable wherever either is absent -- and no
credential may exist in this repository at all -- so such a test could only ever be SKIPPED,
and a verification suite that silently skips is indistinguishable from one that passed. The
reference suite reaches the same conclusion about its own emulator layer, which is why that
layer is opt-in there. Separately, an in-process double can be asked something no real server
can: which statements were NEVER issued. The negative privilege contract below is only
checkable because of that.

Alternatives Considered: authoring fresh extracts for this module rather than reading the
committed corpora under ``app/data`` and ``tests/fixtures`` through the ``seed_corpus`` and
``fixture_corpus`` accessors. Rejected on what each choice proves. The committed bytes were
produced by the reference compiler and nothing in this package chose them, so staging them
demonstrates that the loader preserves data it did not author; a locally invented extract would
demonstrate only that this package agrees with itself. That distinction is decisive for the
verbatim-bytes property in particular, because a transcoding fault returns plausible bytes
rather than an error. Copying vectors into this directory was the other option and was rejected
because it would duplicate a reference corpus this migration may neither modify nor re-pin,
leaving a second artifact to keep in step with files nobody may edit. The one exception is the
security-user record, which is synthesised by ``conftest.py``'s builder: the committed
``USRSEC`` extract carries a plaintext password field, and no test reproduces one.

Assumptions: every figure this module asserts -- record lengths, byte sizes, generation counts,
key spans, schema-to-role pairs -- is read from a descriptor in the package or measured from the
committed corpus. The module declares no geometry of its own, so a failure here is a statement
about the loader or the corpus rather than about a third copy of a contract kept in a test.

Trade-offs: several properties are asserted by parsing the loader's own source with :mod:`ast`
rather than by executing it. That is a deliberate narrowing, and it is narrower than a text
search on purpose: both loaders discuss ``endpoint_url``, ``boto3.client``, ``CREATE INDEX``,
``TRUNCATE`` and clock reads at length in their WHY comments in order to explain why they use
none of them, so a substring search over the file text reports a hit on every one of those and
proves nothing. Restricting the walk to executable nodes -- calls, keywords, imports and
non-docstring literals -- is what makes the absence of a wall-clock read or a literal endpoint a
checkable fact rather than a claim.
"""

from __future__ import annotations

import ast
import base64
import hashlib
import subprocess
import sys
from datetime import date
from decimal import Decimal
from functools import lru_cache
from pathlib import Path
from types import MappingProxyType
from typing import TYPE_CHECKING, Any, Final, NamedTuple

# Assumptions: the doubles are imported from ``conftest`` by name because the fixtures hand back
#   INSTANCES and several annotations below need the classes. pytest's default import mode puts
#   the suite directory on the path, so this sibling import resolves under every documented
#   invocation. It is an absolute import of a top-level module, not a relative one, so it
#   satisfies the ban this package places on relative imports.
# Trade-offs: the import sorter groups ``conftest`` with the third-party block because it cannot
#   tell a sibling test module from an installed distribution. The placement is accepted rather
#   than suppressed, exactly as ``test_doubles.py`` accepts it, since a per-file ignore would
#   switch the rule off for every future import in this module too.
import pytest
from conftest import (
    FORBIDDEN_LOADER_STATEMENTS,
    SYNTHETIC_PASSWORD_FILL,
    FakeAuroraDatabase,
    FakeObjectStore,
    FixtureCorpus,
    SecUserRecordBuilder,
    SeedCorpus,
)

from carddemo_migration.config import (
    REDACTED,
    SCHEMA_ROLES,
    AuroraConnectionSettings,
    DatasetStagingSettings,
    quoted_schema,
    role_for_schema,
)
from carddemo_migration.copybook import layouts
from carddemo_migration.loaders import aurora, s3_stage
from carddemo_migration.readers import account, discgrp, usrsec, xref

if TYPE_CHECKING:  # pragma: no cover - imported for annotations only
    from collections.abc import Mapping


class _TargetTable(NamedTuple):
    """One expected load target: the record name and the table it lands in.

    Purpose
    -------
    Name a target as the migration plan names it -- by record and by qualified table -- so the
    inventory assertion below reads as the published contract rather than as an index into a
    mapping.

    Attributes
    ----------
    record : str
        The registered record name the reader dispatch and the target map are both keyed by.
    schema : str
        The owning bounded-context schema.
    table : str
        The table within that schema.
    """

    record: str
    schema: str
    table: str


class _GenerationFamilyFact(NamedTuple):
    """One expected generation family with the baseline evidence that establishes it.

    Purpose
    -------
    Carry a family's cloud-side path segment together with the mainframe dataset base name and
    the exact job and line the base is defined at, so the enumeration assertion cites its source
    instead of asserting a list somebody typed.

    Attributes
    ----------
    key : str
        The dataset path segment the staged prefix uses.
    base_name : str
        The baseline generation-data-group base name.
    defined_in : str
        Repository-relative path of the job that defines the base.
    name_line : str
        The line number of the ``NAME`` operand within that job, spelled as it is recorded on
        the family descriptor.
    """

    key: str
    base_name: str
    defined_in: str
    name_line: str


# WHY : Assumptions: the eleven targets are transcribed from the migration plan's own
#   enumeration rather than read back from ``aurora.TARGETS``, because a test that derives its
#   expectation from the thing under test cannot detect a target being dropped. The eleven span
#   FIVE distinct schemas -- auth, account, card, ledger and reference -- and that figure is
#   counted from this table below rather than stated as prose, for the same reason the
#   generation count is: a count written next to a list goes stale without the list changing.
#   The three remaining schemas the bootstrap creates (batch, authorization and reporting) own no
#   extract, so no load target exists for them.
_ELEVEN_TARGET_TABLES: Final[tuple[_TargetTable, ...]] = (
    _TargetTable("SECUSER", "auth", "users"),
    _TargetTable("ACCOUNT", "account", "accounts"),
    _TargetTable("CUSTOMER", "account", "customers"),
    _TargetTable("XREF", "account", "card_xref"),
    _TargetTable("CARD", "card", "cards"),
    _TargetTable("TRAN", "ledger", "transactions"),
    _TargetTable("DALYTRAN", "ledger", "daily_transactions"),
    _TargetTable("TCATBAL", "ledger", "transaction_category_balances"),
    _TargetTable("TRANTYPE", "reference", "transaction_types"),
    _TargetTable("TRANCAT", "reference", "transaction_categories"),
    _TargetTable("DISGROUP", "reference", "disclosure_groups"),
)

# WHY : Assumptions: there are TEN generation families, not six, and the arithmetic is written
#   out so a reader can add it up: SIX are defined in ``app/jcl/DEFGDGB.jcl`` (NAME operands at
#   L25, L31, L37, L43, L49 and L55), THREE in ``app/jcl/DEFGDGD.jcl`` (L28, L51, L74) and ONE in
#   ``app/jcl/DALYREJS.jcl`` (L25, inside the DEFINE opened at L24). 6 + 3 + 1 = 10, and every one
#   of the ten declares ``LIMIT(5)`` and ``SCRATCH`` on the two lines following its NAME operand.
#   Counting SIX is the specific error this table exists to prevent, and it is the easy one to
#   make: ``DEFGDGB.jcl`` defines six bases in a single step and reads as complete on its own, so
#   an enumeration derived from that file alone under-provisions the pipeline by four families
#   and nothing fails -- the four missing families' steps would still write their objects, into
#   prefixes carrying no retention contract, and their generations would accumulate without
#   limit. One of the four is ``DALYREJS``, the audit trail of every transaction the posting run
#   declined to post.
_TEN_GENERATION_FAMILIES: Final[tuple[_GenerationFamilyFact, ...]] = (
    _GenerationFamilyFact(
        "transact-bkup", "AWS.M2.CARDDEMO.TRANSACT.BKUP", "app/jcl/DEFGDGB.jcl", "L25"
    ),
    _GenerationFamilyFact(
        "transact-daly", "AWS.M2.CARDDEMO.TRANSACT.DALY", "app/jcl/DEFGDGB.jcl", "L31"
    ),
    _GenerationFamilyFact("tranrept", "AWS.M2.CARDDEMO.TRANREPT", "app/jcl/DEFGDGB.jcl", "L37"),
    _GenerationFamilyFact(
        "tcatbalf-bkup", "AWS.M2.CARDDEMO.TCATBALF.BKUP", "app/jcl/DEFGDGB.jcl", "L43"
    ),
    _GenerationFamilyFact("systran", "AWS.M2.CARDDEMO.SYSTRAN", "app/jcl/DEFGDGB.jcl", "L49"),
    _GenerationFamilyFact(
        "transact-combined", "AWS.M2.CARDDEMO.TRANSACT.COMBINED", "app/jcl/DEFGDGB.jcl", "L55"
    ),
    _GenerationFamilyFact(
        "trantype-bkup", "AWS.M2.CARDDEMO.TRANTYPE.BKUP", "app/jcl/DEFGDGD.jcl", "L28"
    ),
    _GenerationFamilyFact(
        "trancatg-bkup", "AWS.M2.CARDDEMO.TRANCATG.PS.BKUP", "app/jcl/DEFGDGD.jcl", "L51"
    ),
    _GenerationFamilyFact(
        "discgrp-bkup", "AWS.M2.CARDDEMO.DISCGRP.BKUP", "app/jcl/DEFGDGD.jcl", "L74"
    ),
    _GenerationFamilyFact("dalyrejs", "AWS.M2.CARDDEMO.DALYREJS", "app/jcl/DALYREJS.jcl", "L25"),
)

# WHY : Assumptions: the business date is the one ``app/jcl/INTCALC.jcl`` injects at L22 as
#   ``PARM='2022071800'``. Reusing the baseline's own injected date rather than inventing one
#   keeps every staged prefix in this module comparable with the reference pipeline's, and it is
#   the same date the parity suite runs its interest cycle against.
_BUSINESS_DATE: Final[date] = date(2022, 7, 18)

# WHY : Assumptions: a second date is needed by exactly one property -- that ``(+1)`` is scoped
#   to the target business date while ``(0)`` spans the family -- and it is the day after the
#   first so the ordering between the two is unambiguous.
_LATER_BUSINESS_DATE: Final[date] = date(2022, 7, 19)

#: The committed EBCDIC extract the verbatim-bytes property is proven against, spelled the way
#: :meth:`conftest.SeedCorpus.ebcdic_path` accepts it.
_BINARY_EXTRACT: Final[str] = "EXPORT.DATA.PS"

# WHY : Assumptions: these three figures are MEASURED from the committed file rather than
#   declared -- 250000 bytes carrying five stray 0x0A bytes and eleven stray 0x0D bytes -- and
#   they are what makes the verbatim assertion meaningful instead of decorative. A text-mode
#   write or a newline normalisation would rewrite exactly those sixteen bytes, and a byte count
#   alone would not notice: on this platform a 0x0D0A pair collapsing to 0x0A shortens the file,
#   but a lone 0x0A expanding to 0x0D0A lengthens it, so a corruption pass can leave the size
#   unchanged. The reference emulator seeder records the same hazard at its own upload site,
#   noting that raw bytes "including binary EBCDIC with NULs and overpunch sign bytes" must be
#   uploaded verbatim and that round-tripping such a dataset through a UTF-8 write would corrupt
#   it. Comparing the whole body byte for byte is the only assertion that covers both directions.
_BINARY_EXTRACT_BYTE_SIZE: Final[int] = 250000
_BINARY_EXTRACT_STRAY_LINE_FEEDS: Final[int] = 5
_BINARY_EXTRACT_STRAY_CARRIAGE_RETURNS: Final[int] = 11

# WHY : Assumptions: the padding cases straddle every digit boundary the four-digit component
#   has -- one, nine, ten, ninety-nine, one hundred and one thousand -- because zero padding is
#   what makes a LEXICAL prefix listing sort in numeric order. Without it ``gen=10`` sorts before
#   ``gen=9``, and the highest-existing-generation discovery below reads the wrong generation as
#   the newest while every individual prefix still looks well formed.
_GENERATION_PADDING_CASES: Final[tuple[tuple[int, str], ...]] = (
    (1, "gen=0001/"),
    (9, "gen=0009/"),
    (10, "gen=0010/"),
    (99, "gen=0099/"),
    (100, "gen=0100/"),
    (1000, "gen=1000/"),
)

# WHY : Assumptions: exactly three baseline field names are misspelled and each correction is
#   recorded here with the record and table it lands in, so the assertion covers the correction
#   AND its destination. Two are the same misspelling of "expiration" in two different masters
#   and both become ``expiration_date`` in their own table, which is why the table has to name
#   the record: asserting the column alone would pass if both corrections landed on one table.
#   The third is on a pending-authorization field, which reaches PostgreSQL through the
#   authorization service's own migration rather than through this loader, so it is asserted
#   against the layout registry instead of against a load target.
_MISSPELLING_CORRECTIONS: Final[tuple[tuple[str, str, str], ...]] = (
    ("ACCT-EXPIRAION-DATE", "ACCOUNT", "expiration_date"),
    ("CARD-EXPIRAION-DATE", "CARD", "expiration_date"),
)

#: The one misspelled baseline field with no load target, corrected by the layout registry alone.
_UNTARGETED_MISSPELLING: Final[tuple[str, str]] = (
    "PA-MERCHANT-CATAGORY-CODE",
    "merchant_category_code",
)

# WHY : Assumptions: these are the misspelling fragments the baseline actually contains, and the
#   assertion that no FOURTH field is renamed is expressed by scanning every registered layout
#   for them rather than by counting the mapping's entries. A count says the mapping has three
#   rows; the scan says the baseline has three misspellings, which is the property that matters
#   -- a fourth misspelled field left uncorrected would satisfy the count and fail the scan.
_MISSPELLING_FRAGMENTS: Final[tuple[str, ...]] = ("EXPIRAION", "CATAGORY")

# WHY : Assumptions: the wall-clock readers are enumerated as DOTTED CALL names because that is
#   the shape the AST walk below can match exactly. Both spellings of each are listed -- the
#   bare ``date.today`` a module gets from ``from datetime import date`` and the qualified
#   ``datetime.date.today`` it gets from ``import datetime`` -- since a module choosing the other
#   import form would otherwise slip past a list written for one of them.
_WALL_CLOCK_CALLS: Final[frozenset[str]] = frozenset(
    {
        "date.today",
        "datetime.now",
        "datetime.today",
        "datetime.utcnow",
        "datetime.date.today",
        "datetime.datetime.now",
        "datetime.datetime.today",
        "datetime.datetime.utcnow",
        "time.time",
        "time.time_ns",
        "time.localtime",
        "time.gmtime",
    }
)

#: Third-party service clients whose absence from a module's imports is the layering guard.
_SERVICE_CLIENT_MODULES: Final[frozenset[str]] = frozenset({"boto3", "botocore", "psycopg"})

# WHY : Assumptions: the seven statement kinds the loader must never issue are named here, in this
#   module, even though the double already carries the patterns that detect them. The detection
#   vocabulary is the entire strength of the negative privilege assertion below: that assertion
#   reads ``forbidden_statements() == ()``, which stays true if the vocabulary is ever narrowed,
#   so a narrowing would weaken the guarantee to nothing while the test still reported green.
#   Pinning the set converts that silent weakening into a failure. The seven are the DDL and
#   data-removal verbs withheld from every ``carddemo_<context>`` login role by
#   data-migration/sql/V0__schemas_and_roles.sql, which grants ``SELECT``, ``INSERT`` and
#   ``UPDATE`` on the owning schema and nothing more.
_WITHHELD_STATEMENT_KINDS: Final[frozenset[str]] = frozenset(
    {
        "CREATE SCHEMA",
        "CREATE ROLE",
        "GRANT",
        "REVOKE",
        "DELETE",
        "TRUNCATE",
        "CREATE INDEX",
    }
)

# WHY : Assumptions: the seeding helper below suppresses retention entirely rather than passing a
#   count matched to the generations it writes, and the reason is that retention is scoped to the
#   FAMILY and not to a business date. A count of one while staging one generation for a second
#   date therefore scratches the first date's generations, which was measured rather than
#   anticipated: it left a discovery assertion reading a one-object bucket and reporting the first
#   generation as the newest. Setting the count to the highest expressible generation means the
#   seeding step can never prune what a test has just arranged. Retention behaviour itself is
#   exercised by the staging module's own tests and is deliberately not re-tested here.
_RETENTION_KEEPING_EVERY_GENERATION: Final[int] = s3_stage.MAX_GENERATION


def _connect_as_owning_role(
    database: FakeAuroraDatabase,
    schema: str,
    *,
    host: str = "aurora.carddemo.invalid",
) -> Any:  # noqa: ANN401 -- the double and the driver return unrelated connection types
    """Open one connection for a schema, authenticated as that schema's own login role.

    Purpose
    -------
    Reproduce the per-schema connection discipline a load is required to follow, so every test
    below reaches the double the way ``cli.py`` reaches the real cluster: settings resolved for
    one schema, translated by ``as_connection_params``, and never a shared connection reused
    across schemas.

    Parameters
    ----------
    database : FakeAuroraDatabase
        The in-process database double the connection is acquired from.
    schema : str
        The bounded-context schema being loaded. Its login role is looked up in
        :data:`carddemo_migration.config.SCHEMA_ROLES`, never spelled out here.
    host : str
        Endpoint name to record on the connection. Defaults to a name in the reserved
        ``.invalid`` top-level domain, so the settings object cannot reach anything even if some
        future caller handed it to a real driver.

    Returns
    -------
    Any
        A recording connection from the double. Typed loosely because the double and the real
        driver return unrelated types and this helper is deliberately indifferent to which.

    Raises
    ------
    KeyError
        If ``schema`` is not one of the eight the bootstrap creates.
    carddemo_migration.config.ConfigurationError
        Propagated from the double if the translated parameters would not have verified the
        server's certificate.
    """
    # WHY : Assumptions: the role comes from the published schema-to-role map rather than from an
    #   f-string over the schema name. The two agree today, and writing the derivation here would
    #   make this helper pass for a schema whose role the bootstrap never created -- which is the
    #   one mistake a role assertion exists to catch.
    settings = AuroraConnectionSettings(
        host=host,
        port=5432,
        database="carddemo",
        user=SCHEMA_ROLES[schema],
        password=SYNTHETIC_PASSWORD_FILL,
        ssl_root_cert="/nonexistent/synthetic-test-anchor.pem",
    )
    # WHY : Assumptions: the parameters are produced by ``as_connection_params`` rather than
    #   assembled by hand, because the settings attribute is spelled ``database`` and the driver
    #   keyword is ``dbname``. Passing the fields through by name is a mistake only a connect call
    #   catches, and routing every test through this one translation is what keeps it caught.
    return database.connect(**settings.as_connection_params())


@lru_cache(maxsize=None)
def _module_tree(module_path: str) -> ast.Module:
    """Parse one module's committed source into a syntax tree.

    Purpose
    -------
    Give the source-level assertions a tree to walk, parsed once per module however many tests
    ask for it.

    Parameters
    ----------
    module_path : str
        Absolute filesystem path of the module to parse. A path is taken rather than a module
        object so the result is cacheable by a hashable key.

    Returns
    -------
    ast.Module
        The parsed tree.

    Raises
    ------
    OSError
        If the source cannot be read.
    SyntaxError
        If the source does not parse, which would already have failed at import.
    """
    # WHY : Trade-offs: the parameter is a PATH STRING rather than the module object,
    #   purely so the result can be memoised -- a module is unhashable for caching
    #   purposes here in the sense that callers hold different objects for the same file.
    #   The accepted cost is that every caller reaches for ``module.__file__`` at the call
    #   site; what it buys is one parse per module across all the source-level assertions
    #   instead of one per test, over two files of roughly two and a half thousand lines.
    return ast.parse(Path(module_path).read_text(encoding="utf-8"))


def _docstring_constant_ids(tree: ast.Module) -> frozenset[int]:
    """Identify every string constant that is a docstring rather than executable data.

    Purpose
    -------
    Let the literal scan below exclude prose. Both loaders explain at length in their docstrings
    why they use no literal endpoint and issue no index creation, so a scan that included
    docstrings would report those very explanations as findings.

    Parameters
    ----------
    tree : ast.Module
        The parsed module.

    Returns
    -------
    frozenset[int]
        The :func:`id` of each string-constant node occupying the docstring position of the
        module, a class, or a function.

    Raises
    ------
    None
    """
    documented: set[int] = set()
    for node in ast.walk(tree):
        if not isinstance(node, (ast.Module, ast.ClassDef, ast.FunctionDef, ast.AsyncFunctionDef)):
            continue
        body = node.body
        # WHY : Assumptions: a docstring is recognised STRUCTURALLY -- the first statement of a
        #   body being a bare string expression -- rather than by matching text. That is exactly
        #   the rule the language itself applies, so this cannot disagree with what Python treats
        #   as a docstring, and it correctly leaves a string used as a value in first position
        #   inside the scan.
        if body and isinstance(body[0], ast.Expr) and isinstance(body[0].value, ast.Constant):
            if isinstance(body[0].value.value, str):
                documented.add(id(body[0].value))
    return frozenset(documented)


def _executable_string_literals(module: Any) -> tuple[str, ...]:  # noqa: ANN401 -- any module
    """Collect every string literal a module evaluates, excluding its documentation.

    Parameters
    ----------
    module : Any
        The imported module whose committed source is read. Typed loosely because
        :class:`types.ModuleType` is imported for annotation only elsewhere in this suite and a
        module is not otherwise constrained.

    Returns
    -------
    tuple[str, ...]
        Every string constant outside a docstring position, in traversal order.

    Raises
    ------
    OSError
        If the module's source cannot be read.
    """
    # WHY : Assumptions: docstrings are excluded by NODE IDENTITY rather than by comparing
    #   text. Two identical strings can appear both as documentation and as data, and a
    #   text-based exclusion would then drop the data occurrence as well -- silently
    #   narrowing the scan that exists to catch a literal endpoint.
    tree = _module_tree(module.__file__)
    documented = _docstring_constant_ids(tree)
    return tuple(
        node.value
        for node in ast.walk(tree)
        if isinstance(node, ast.Constant)
        and isinstance(node.value, str)
        and id(node) not in documented
    )


def _dotted_name(node: ast.expr) -> str | None:
    """Render an attribute or name expression as its dotted spelling.

    Parameters
    ----------
    node : ast.expr
        The expression in a call's function position.

    Returns
    -------
    str | None
        The dotted spelling, for example ``config.aws_client``, or ``None`` when the expression
        is neither a name nor a chain of attributes over one -- a subscript or a call result, for
        which no static spelling exists.

    Raises
    ------
    None
    """
    # WHY : Trade-offs: an expression with no static spelling reports ``None`` rather than a
    #   best guess. A call on a subscript or on another call result cannot be named without
    #   evaluating it, and inventing a partial name would put an entry in the call set that
    #   no assertion could interpret -- worse than an acknowledged gap, because it would
    #   read as a real call target.
    parts: list[str] = []
    while isinstance(node, ast.Attribute):
        parts.append(node.attr)
        node = node.value
    if isinstance(node, ast.Name):
        parts.append(node.id)
        return ".".join(reversed(parts))
    return None


def _called_dotted_names(module: Any) -> frozenset[str]:  # noqa: ANN401 -- any module
    """Collect the dotted spelling of every call a module makes.

    Parameters
    ----------
    module : Any
        The imported module whose committed source is read.

    Returns
    -------
    frozenset[str]
        One entry per statically spellable call target.

    Raises
    ------
    OSError
        If the module's source cannot be read.
    """
    # WHY : Assumptions: EVERY call in the module is collected, including calls inside a
    #   function body and inside a nested scope. A walk restricted to module level would
    #   miss precisely the calls that matter here: both loaders defer their client and driver
    #   acquisition into the one function that needs it.
    called = {
        _dotted_name(node.func)
        for node in ast.walk(_module_tree(module.__file__))
        if isinstance(node, ast.Call)
    }
    return frozenset(name for name in called if name is not None)


def _keyword_argument_names(module: Any) -> frozenset[str]:  # noqa: ANN401 -- any module
    """Collect every keyword argument name a module passes to any call.

    Parameters
    ----------
    module : Any
        The imported module whose committed source is read.

    Returns
    -------
    frozenset[str]
        The keyword names, excluding ``**`` unpackings, which carry no static name.

    Raises
    ------
    OSError
        If the module's source cannot be read.
    """
    # WHY : Assumptions: a ``**`` unpacking is skipped because it carries no static name, and
    #   that limit is stated rather than hidden: a module could in principle pass an endpoint
    #   through an unpacked mapping and this collection would not see it. The companion
    #   literal scan is what covers that case, which is why the endpoint assertion uses both.
    return frozenset(
        keyword.arg
        for node in ast.walk(_module_tree(module.__file__))
        if isinstance(node, ast.Call)
        for keyword in node.keywords
        if keyword.arg is not None
    )


def _imported_module_names(module: Any) -> frozenset[str]:  # noqa: ANN401 -- any module
    """Collect the top-level distribution name of every module a module imports.

    Purpose
    -------
    Answer the layering question -- which third-party clients can this module reach -- including
    imports deferred inside a function, which is where both loaders deliberately put theirs.

    Parameters
    ----------
    module : Any
        The imported module whose committed source is read.

    Returns
    -------
    frozenset[str]
        Top-level names only, so ``botocore.exceptions`` reports as ``botocore``.

    Raises
    ------
    OSError
        If the module's source cannot be read.
    """
    # WHY : Assumptions: names are reduced to their TOP-LEVEL distribution and a relative
    #   import is skipped by its non-zero level. Reducing is what makes
    #   ``botocore.exceptions`` answer the question actually being asked -- can this module
    #   reach the AWS SDK -- which a check on the full dotted path would answer only for the
    #   one submodule somebody happened to name.
    imported: set[str] = set()
    for node in ast.walk(_module_tree(module.__file__)):
        if isinstance(node, ast.Import):
            imported.update(alias.name.split(".")[0] for alias in node.names)
        elif isinstance(node, ast.ImportFrom) and node.module is not None and node.level == 0:
            imported.add(node.module.split(".")[0])
    return frozenset(imported)


def _client_operations(module: Any) -> frozenset[str]:  # noqa: ANN401 -- any module
    """Collect the operation names a module invokes on its injected service client.

    Purpose
    -------
    Establish which service operations the staging loader can possibly perform, by reading the
    calls it makes on the parameter every one of its functions names ``client``.

    Parameters
    ----------
    module : Any
        The imported module whose committed source is read.

    Returns
    -------
    frozenset[str]
        The attribute names called on ``client``.

    Raises
    ------
    OSError
        If the module's source cannot be read.
    """
    # WHY : Assumptions: the injected client is matched by the PARAMETER NAME ``client``,
    #   which every public function in the staging module uses for it. That is a convention
    #   rather than a language guarantee, and the cost of it being broken is visible rather
    #   than silent: a renamed parameter empties this set, and the assertion comparing it
    #   against the protocol's four operations fails rather than passing vacuously.
    return frozenset(
        node.func.attr
        for node in ast.walk(_module_tree(module.__file__))
        if isinstance(node, ast.Call)
        and isinstance(node.func, ast.Attribute)
        and isinstance(node.func.value, ast.Name)
        and node.func.value.id == "client"
    )


@lru_cache(maxsize=1)
def _all_declared_layouts() -> Mapping[str, layouts.RecordSpec]:
    """Collect every record layout the copybook registry declares, registered or not.

    Purpose
    -------
    Reach the layouts that ``layouts.names()`` does not dispatch on. The reader registry covers
    fourteen records, but the module also declares the export payload's six nested layouts and the
    two pending-authorization segments, and two of the three misspelling corrections belong to
    records outside the registry -- so a scan driven by ``names()`` alone would miss them.

    Returns
    -------
    Mapping[str, carddemo_migration.copybook.layouts.RecordSpec]
        Every module-level record specification, keyed by the constant that declares it.

    Raises
    ------
    None
    """
    # WHY : Assumptions: the layouts are discovered by TYPE from the module's own namespace rather
    #   than listed here by name. A list would go stale the moment a layout is added, and it would
    #   go stale silently -- the scan would keep passing while covering less than it claims, which
    #   is the exact failure mode a completeness check exists to prevent.
    return MappingProxyType(
        {
            name: value
            for name, value in vars(layouts).items()
            if isinstance(value, layouts.RecordSpec)
        }
    )


def _key_window_columns(target: aurora.TableTarget) -> tuple[str, ...]:
    """Derive a target's conflict columns from its record descriptor's key geometry.

    Purpose
    -------
    Compute, from ``key_offset`` and ``key_length`` alone, which target columns make up the
    record's primary key -- so the declared conflict target can be checked against the
    descriptor instead of against a second hand-written list.

    Parameters
    ----------
    target : aurora.TableTarget
        The load target. Its record name is the key into the layout registry, and its own column
        mapping translates each key field to the column it becomes.

    Returns
    -------
    tuple[str, ...]
        The target column names of the fields overlapping the descriptor's key window, in
        declaration order.

    Raises
    ------
    carddemo_migration.copybook.layouts.LayoutError
        If the target's record name is not registered.
    """
    record = layouts.layout(_record_name_of(target))
    window_end = record.key_offset + record.key_length
    # WHY : Assumptions: OVERLAP is tested rather than containment, so a key window that ends
    #   mid-field still selects that field. Containment would silently return a shorter tuple for
    #   such a layout, and a shorter tuple compared against a declared conflict key would fail
    #   with a message about the declaration when the fault was in this derivation.
    return tuple(
        target.columns[field.name]
        for field in record.fields
        if field.start < window_end
        and field.start + field.length > record.key_offset
        and field.name in target.columns
    )


def _record_name_of(target: aurora.TableTarget) -> str:
    """Resolve the registered record name a load target belongs to.

    Purpose
    -------
    Recover the registry key for a target, since a target carries its schema and table but not
    the record name it is filed under.

    Parameters
    ----------
    target : aurora.TableTarget
        The load target to identify.

    Returns
    -------
    str
        The record name whose entry in :data:`carddemo_migration.loaders.aurora.TARGETS` is this
        target.

    Raises
    ------
    AssertionError
        If the target is not one of the declared eleven, which can only happen for a target a
        test constructed itself.
    """
    # WHY : Assumptions: the comparison is IDENTITY rather than equality. A target is a frozen
    #   dataclass, so two structurally identical declarations would compare equal and this
    #   lookup could return either name -- which would make the key-window derivation read a
    #   different record's geometry while appearing to succeed.
    for record_name in aurora.target_names():
        if aurora.TARGETS[record_name] is target:
            return record_name
    raise AssertionError(f"{target.schema}.{target.table} is not a declared load target")


def _stage_generations(
    store: FakeObjectStore,
    settings: DatasetStagingSettings,
    family_key: str,
    business_date: date,
    generations: tuple[int, ...],
    source: Path,
) -> tuple[str, ...]:
    """Stage one extract under several explicit generations of a family.

    Purpose
    -------
    Seed the object-store double with generations that already exist, which is the precondition
    every discovery assertion needs. The generations are written through the loader itself rather
    than poked into the double, so what discovery later reads is exactly what staging writes.

    Parameters
    ----------
    store : FakeObjectStore
        The object-store double receiving the objects.
    settings : DatasetStagingSettings
        Validated staging settings owning the prefix layout.
    family_key : str
        The registered family whose domain and dataset segments the prefixes use.
    business_date : date
        Business date the generations belong to.
    generations : tuple[int, ...]
        The explicit generation numbers to write, in order.
    source : Path
        Local extract whose bytes each generation carries.

    Returns
    -------
    tuple[str, ...]
        The staged prefix of each generation, in the order written.

    Raises
    ------
    carddemo_migration.loaders.s3_stage.GenerationRetentionError
        If the family is unknown or a generation number is unacceptable.
    carddemo_migration.loaders.s3_stage.DatasetSourceError
        If the extract cannot be staged.
    """
    # WHY : Assumptions: the generations are written through the staging function itself
    #   rather than poked into the double as objects, so what the discovery assertions later
    #   read is exactly what staging writes. Arranging the keys by hand would let a test pass
    #   against a prefix shape the loader does not actually produce, which is the one thing
    #   these tests exist to detect. Retention is suppressed here; see
    #   :data:`_RETENTION_KEEPING_EVERY_GENERATION` for the measurement behind that.
    registered = s3_stage.family(family_key)
    staged: list[str] = []
    for generation in generations:
        staged.append(
            s3_stage.stage_dataset_file(
                client=store,
                settings=settings,
                domain=registered.domain,
                dataset=registered.dataset,
                business_date=business_date,
                generation=generation,
                source=source,
                retention_count=_RETENTION_KEEPING_EVERY_GENERATION,
            ).prefix
        )
    return tuple(staged)


# ---------------------------------------------------------------------------
# The Aurora bulk load: targets, roles and transaction scope
# ---------------------------------------------------------------------------


def test_the_eleven_load_targets_are_the_tables_the_migration_declares() -> None:
    """Assert the loader targets exactly the eleven tables, each in its owning schema.

    Purpose
    -------
    Hold the load surface to the published inventory in both directions, so neither a dropped
    target nor an invented one can pass.

    Returns
    -------
    None
        Nothing; a difference in either direction is reported as an assertion failure naming it.

    Raises
    ------
    None
    """
    expected = {(row.record, row.schema, row.table) for row in _ELEVEN_TARGET_TABLES}
    declared = {
        (record, aurora.TARGETS[record].schema, aurora.TARGETS[record].table)
        for record in aurora.target_names()
    }

    assert declared == expected, (
        "the declared load targets and the migration's inventory disagree: declared but not"
        f" expected={sorted(declared - expected)}; expected but not declared"
        f"={sorted(expected - declared)}"
    )
    assert len(_ELEVEN_TARGET_TABLES) == 11
    # WHY : Assumptions: the qualified names are asserted to be DISTINCT as well as complete. Two
    #   records mapped to one table would satisfy the set comparison above only if both rows were
    #   also wrong in the same way, but a duplicate table is worth its own check because it is the
    #   shape a copy-paste error takes, and it would silently load one record over another.
    qualified = [aurora.TARGETS[record].qualified_name for record in aurora.target_names()]
    assert len(set(qualified)) == len(qualified), f"two records share a table: {sorted(qualified)}"


def test_the_eleven_targets_span_the_five_schemas_that_own_an_extract() -> None:
    """Assert every target schema is a bootstrap schema, and name the five that are used.

    Purpose
    -------
    Pin which bounded contexts the ETL writes into. Three of the eight schemas the bootstrap
    creates own no extract, and a target appearing in one of them would mean the loader had
    acquired a write path the migration never designed.

    Returns
    -------
    None
        Nothing; a target in an unexpected schema is reported as an assertion failure.

    Raises
    ------
    None
    """
    # WHY : Assumptions: the five are COUNTED from the inventory table rather than written out as
    #   a number here. A figure stated beside a list is the thing that goes stale without the list
    #   changing, which is the same failure mode the generation count guards against, so the
    #   expectation is derived and only the membership is declared.
    used = {aurora.TARGETS[record].schema for record in aurora.target_names()}
    expected = {row.schema for row in _ELEVEN_TARGET_TABLES}

    assert used == expected
    assert used == {"auth", "account", "card", "ledger", "reference"}
    assert used <= set(SCHEMA_ROLES), (
        f"a target names a schema the bootstrap does not create: {sorted(used - set(SCHEMA_ROLES))}"
    )
    assert set(SCHEMA_ROLES) - used == {"batch", "authorization", "reporting"}


@pytest.mark.parametrize("row", _ELEVEN_TARGET_TABLES, ids=lambda row: row.record.lower())
def test_each_target_loads_as_its_own_schema_s_login_role(
    row: _TargetTable, fake_aurora: FakeAuroraDatabase
) -> None:
    """Assert one connection per schema, authenticated as that schema's ``carddemo_`` role.

    Purpose
    -------
    Establish that the load reaches each table through the least-privilege role the bootstrap
    created for it, rather than through one shared connection. A load that only works as a
    superuser proves nothing about whether the service owning the table can write it.

    Parameters
    ----------
    row : _TargetTable
        One expected target, supplying the schema whose role is under test.
    fake_aurora : FakeAuroraDatabase
        The in-process database double, whose ``connect`` validates the translated parameters and
        records them with the credential masked.

    Returns
    -------
    None
        Nothing; a connection authenticated as the wrong role, or one carrying an unmasked
        credential, is reported as an assertion failure.

    Raises
    ------
    None
    """
    target = aurora.TARGETS[row.record]
    _connect_as_owning_role(fake_aurora, target.schema)

    recorded = fake_aurora.connection_params[-1]
    assert recorded["user"] == f"carddemo_{row.schema}"
    # WHY : Assumptions: the expected role is ALSO resolved through the published accessor, so the
    #   assertion above and this one check each other. The literal spelling proves the naming
    #   convention the bootstrap SQL uses; the accessor proves the loader can reach that spelling
    #   without a test having told it what to expect.
    assert recorded["user"] == role_for_schema(target.schema)
    assert recorded["dbname"] == "carddemo"
    # WHY : Assumptions: the credential is asserted PRESENT and REDACTED rather than absent. The
    #   double masks a non-empty password and leaves an empty one alone, so this distinguishes "a
    #   credential was supplied and cannot be printed" from "no credential was supplied at all" --
    #   and the second is a real defect that an assertion on absence would call a pass.
    assert recorded["password"] == REDACTED
    assert SYNTHETIC_PASSWORD_FILL not in str(recorded)
    assert len(fake_aurora.connections) == 1


def test_every_qualified_table_name_quotes_both_identifiers() -> None:
    """Assert each target renders its schema and table as quoted identifiers.

    Purpose
    -------
    Establish the mechanism that makes a reserved word usable as a schema name. Quoting is
    applied uniformly rather than only where it is needed, so no target depends on a reader
    remembering which names require it.

    Returns
    -------
    None
        Nothing; an unquoted identifier is reported as an assertion failure.

    Raises
    ------
    None
    """
    for record in aurora.target_names():
        target = aurora.TARGETS[record]
        assert target.qualified_name == f'"{target.schema}"."{target.table}"'
        assert target.copy_statement().startswith(f"COPY {target.qualified_name} (")
        # WHY : Assumptions: the column list is checked for quoting too, not just the table. A
        #   column named after a reserved word would break exactly the same way, and the two are
        #   rendered by different code paths.
        for column in target.copy_columns():
            assert f'"{column}"' in target.copy_statement()


def test_a_target_in_the_reserved_word_schema_is_quoted_by_the_same_path() -> None:
    """Assert a target in the ``authorization`` schema is quoted, and its role resolves.

    Purpose
    -------
    Cover the one schema name that is a PostgreSQL reserved word. No extract loads into it, so
    the property cannot be observed on a declared target -- it is established here by
    constructing one, which is what proves the reserved word is safe should a target ever be
    declared there.

    Returns
    -------
    None
        Nothing; an unquoted reserved word, or a schema the role map cannot resolve, is reported
        as an assertion failure.

    Raises
    ------
    None
    """
    # WHY : Assumptions: ``authorization`` is a reserved word in PostgreSQL, so an unquoted
    #   reference to it is a syntax error rather than a name resolution failure -- the statement
    #   would not parse, and the error would name the token rather than the schema. Constructing a
    #   target here rather than asserting on a declared one is deliberate: the authorization
    #   context's segments reach the database through its own service migration, so this is the
    #   only way to exercise the quoting path for that name at all.
    reserved = aurora.TableTarget(
        schema="authorization",
        table="pending_auth_summary",
        columns=MappingProxyType({"PA-ACCOUNT-ID": "account_id"}),
    )

    assert reserved.qualified_name == '"authorization"."pending_auth_summary"'
    assert reserved.copy_statement() == (
        'COPY "authorization"."pending_auth_summary" ("account_id") FROM STDIN'
    )
    # WHY : Assumptions: the rendering is also compared against the accessor the configuration
    #   module publishes for this purpose, not only against a literal. The two spell the quoting
    #   convention independently -- the target builds its own qualified name, while every
    #   connection-time schema reference goes through the accessor -- so a change to one and not
    #   the other would leave the loader and its configuration disagreeing about the single name in
    #   the whole schema set that cannot survive being unquoted.
    assert quoted_schema("authorization") == '"authorization"'
    assert reserved.qualified_name.startswith(quoted_schema(reserved.schema) + ".")
    assert SCHEMA_ROLES["authorization"] == "carddemo_authorization"
    assert role_for_schema("authorization") == "carddemo_authorization"


def test_one_dataset_loads_inside_exactly_one_transaction(
    fake_aurora: FakeAuroraDatabase, fixture_corpus: FixtureCorpus
) -> None:
    """Assert a whole dataset commits once, so a partial load is never observable.

    Purpose
    -------
    Establish the unit of work. A dataset either loads wholly or not at all, which is what makes
    the row-count verification pass meaningful: a load that stopped partway would report as a
    count mismatch indistinguishable from a decode fault or a wrong extract.

    Parameters
    ----------
    fake_aurora : FakeAuroraDatabase
        The database double, recording commits, rollbacks and every copied row.
    fixture_corpus : FixtureCorpus
        Read-only accessor over the committed scenario corpus, supplying the account extract.

    Returns
    -------
    None
        Nothing; more than one commit, any rollback, or a row count disagreeing with the extract
        is reported as an assertion failure.

    Raises
    ------
    None
    """
    target = aurora.TARGETS["ACCOUNT"]
    connection = _connect_as_owning_role(fake_aurora, target.schema)
    extract = fixture_corpus.path("provisioning/happy_path", "acctdata.txt")
    expected_rows = len(fixture_corpus.records("provisioning/happy_path", "acctdata.txt"))

    outcome = aurora.load_records(connection, target, account.read_ascii_accounts(extract))

    assert outcome.staged == expected_rows
    assert outcome.inserted == expected_rows
    assert outcome.skipped == 0
    assert fake_aurora.commits == 1, "a dataset must commit exactly once, not per row or per chunk"
    assert fake_aurora.rollbacks == 0
    # WHY : Assumptions: a single COPY statement is asserted as well as a single commit. One
    #   commit around many statements would still be one unit of work, but the load is documented
    #   as streaming every row through ONE server-side COPY, and a per-row INSERT loop wrapped in
    #   one transaction would satisfy the commit count while abandoning that contract entirely.
    assert len(fake_aurora.copy_statements) == 1
    assert len(fake_aurora.copied_rows) == expected_rows


def test_money_reaches_its_numeric_column_as_an_exact_decimal(
    fake_aurora: FakeAuroraDatabase, fixture_corpus: FixtureCorpus
) -> None:
    """Assert every monetary field arrives as :class:`decimal.Decimal` at scale two.

    Purpose
    -------
    Establish the fixed-point rule at the last boundary the ETL controls. A binary float cannot
    represent ten cents exactly, so a money total routed through one is wrong by an amount that
    grows with the row count and is invisible in any single value.

    Parameters
    ----------
    fake_aurora : FakeAuroraDatabase
        The database double. Its copy stream refuses a ``float`` outright, so this test asserts
        the positive property and the double enforces the negative one.
    fixture_corpus : FixtureCorpus
        Read-only accessor supplying the account extract, whose five records carry six zoned
        monetary fields each.

    Returns
    -------
    None
        Nothing; a monetary column carrying anything other than an exact two-place ``Decimal`` is
        reported as an assertion failure.

    Raises
    ------
    None
    """
    target = aurora.TARGETS["ACCOUNT"]
    connection = _connect_as_owning_role(fake_aurora, target.schema)
    extract = fixture_corpus.path("provisioning/happy_path", "acctdata.txt")

    aurora.load_records(connection, target, account.read_ascii_accounts(extract))

    # WHY : Assumptions: which columns hold money is read from the record descriptor's zoned
    #   fields rather than from a list of column names written here. The descriptor is where the
    #   PICTURE clause's scale lives, so a field changing kind moves this assertion with it
    #   instead of leaving a stale name behind that no longer names a monetary column.
    record = layouts.layout("ACCOUNT")
    columns = target.copy_columns()
    money_positions = [
        columns.index(target.columns[field.name])
        for field in record.fields
        if field.kind is layouts.Kind.ZONED and field.name in target.columns
    ]
    assert money_positions, "the account record declares no zoned field, so nothing was checked"

    for _statement, row in fake_aurora.copied_rows:
        for position in money_positions:
            value = row[position]
            assert isinstance(value, Decimal), (
                f"column {columns[position]} carried {type(value).__name__};"
                " money must stay exact fixed point"
            )
            assert not isinstance(value, float)
            # WHY : Assumptions: the scale is asserted at exactly two places, matching the
            #   ``NUMERIC(p,2)`` the migration declares. A Decimal of the right value but the
            #   wrong scale renders differently -- 158 rather than 158.00 -- and the golden
            #   comparisons the parity oracle performs are byte comparisons.
            assert -value.as_tuple().exponent == 2, f"{columns[position]} is not at scale two"


# ---------------------------------------------------------------------------
# The Aurora bulk load: the anti-corruption projection
# ---------------------------------------------------------------------------


@pytest.mark.parametrize("record", aurora.target_names())
def test_filler_is_dropped_at_the_load_boundary_and_kept_in_the_descriptor(record: str) -> None:
    """Assert padding fields are excluded from the projection while staying in the layout.

    Purpose
    -------
    Establish where ``FILLER`` stops. It has to remain in the descriptor, because the descriptor
    is what proves a record's fields are contiguous and sum to its declared length; it must not
    reach a column, because padding to a fixed record length is not data.

    Parameters
    ----------
    record : str
        One registered record name, supplied for each of the eleven load targets.

    Returns
    -------
    None
        Nothing; a padding field reaching a column, or vanishing from the descriptor, is reported
        as an assertion failure.

    Raises
    ------
    None
    """
    target = aurora.TARGETS[record]
    layout = layouts.layout(record)
    padding = tuple(field for field in layout.fields if "FILLER" in field.name.upper())

    for field in padding:
        assert field.name not in target.columns, (
            f"{field.name} reached column {target.columns.get(field.name)!r} of"
            f" {target.qualified_name}; padding is not data"
        )

    # WHY : Assumptions: the contiguity property is re-asserted here rather than taken on trust
    #   from the descriptor tests, because it is the reason dropping FILLER at this boundary is
    #   safe. The fields must still tile the record exactly, which is what lets a reader slice by
    #   offset; if padding were removed from the descriptor instead, the sum would fall short of
    #   the declared length and every offset after the gap would be wrong.
    covered = sum(field.length for field in layout.fields)
    assert covered == layout.reclen, (
        f"{record} declares {covered} bytes of fields against a record length of {layout.reclen}"
    )

    # WHY : Assumptions: every field is accounted for as exactly one of three things -- mapped to
    #   a column, padding, or suppressed by the disclosure policy -- so this closes the projection
    #   rather than only checking the padding half. A field silently absent from all three would
    #   be data the migration dropped without deciding to, which no assertion about FILLER alone
    #   would notice.
    unaccounted = [
        field.name
        for field in layout.fields
        if field.name not in target.columns
        and "FILLER" not in field.name.upper()
        and not field.suppressed
    ]
    assert not unaccounted, (
        f"{record} declares fields the projection neither maps nor drops: {unaccounted}"
    )


def test_the_password_field_never_reaches_the_user_table(
    fake_aurora: FakeAuroraDatabase, secuser_builder: SecUserRecordBuilder
) -> None:
    """Assert the baseline's plaintext password is absent from the ``auth.users`` projection.

    Purpose
    -------
    Establish the one deliberate departure from parity in the load path. The baseline security
    record carries an eight-character plaintext password and sign-on compares it directly; the
    target carries no such column at all, identity having moved to a managed user pool. This
    asserts the field is dropped rather than merely unused.

    Parameters
    ----------
    fake_aurora : FakeAuroraDatabase
        The database double, recording the COPY statement and the row streamed into it.
    secuser_builder : SecUserRecordBuilder
        Builder for a SYNTHETIC security record. No committed ``USRSEC`` record is read or
        reproduced anywhere in this module, because every one of them carries a password value.

    Returns
    -------
    None
        Nothing; a password column in the projection, or a password value in the streamed row, is
        reported as an assertion failure.

    Raises
    ------
    None
    """
    target = aurora.TARGETS["SECUSER"]
    layout = layouts.layout("SECUSER")
    suppressed = tuple(field for field in layout.fields if field.suppressed)
    assert suppressed, "the security record declares no suppressed field, so nothing was checked"

    for field in suppressed:
        assert field.name not in target.columns
    assert not any(
        "pwd" in column.lower() or "password" in column.lower()
        for column in target.columns.values()
    )

    connection = _connect_as_owning_role(fake_aurora, target.schema)
    raw = secuser_builder.build_bytes(
        user_id="SYNTH001",
        first_name="SYNTHETIC",
        last_name="TESTUSER",
        user_type="A",
    )
    decoded = usrsec.decode_ebcdic_security_user(raw)
    # WHY : Assumptions: the identity-provider subject is supplied through the load context
    #   because ``auth.users`` declares that column NOT NULL and derives it from the published
    #   seed-user document rather than from the extract. The value is an all-zero identifier in
    #   version-4 shape and is not a credential: a subject NAMES a user, it does not authenticate
    #   one, so nothing is disclosed by writing it here.
    context = aurora.LoadContext(subjects={"SYNTH001": "00000000-0000-4000-8000-000000000001"})

    outcome = aurora.load_records(connection, target, [decoded], context)

    assert outcome.inserted == 1
    statement = fake_aurora.copy_statements[0]
    assert "pwd" not in statement.lower()
    assert SYNTHETIC_PASSWORD_FILL not in statement
    _statement, row = fake_aurora.copied_rows[0]
    assert SYNTHETIC_PASSWORD_FILL not in row
    assert row == ("SYNTH001", "SYNTHETIC", "TESTUSER", "A", "00000000-0000-4000-8000-000000000001")


@pytest.mark.parametrize(
    ("field_name", "record", "column"), _MISSPELLING_CORRECTIONS, ids=lambda value: str(value)
)
def test_a_documented_misspelling_is_corrected_on_its_target_column(
    field_name: str, record: str, column: str
) -> None:
    """Assert one baseline misspelling maps to the corrected column in its own table.

    Purpose
    -------
    Establish that the load boundary is where the baseline's spelling defects are repaired, and
    that each repair lands in the table that owns the record rather than merely producing the
    right column name somewhere.

    Parameters
    ----------
    field_name : str
        The misspelled baseline field name, exactly as the copybook declares it.
    record : str
        The registered record the field belongs to.
    column : str
        The corrected target column name.

    Returns
    -------
    None
        Nothing; a misspelling that survives, or a correction landing on the wrong table, is
        reported as an assertion failure.

    Raises
    ------
    None
    """
    layout = layouts.layout(record)
    target = aurora.TARGETS[record]

    assert any(field.name == field_name for field in layout.fields), (
        f"{record} no longer declares {field_name}; the baseline copybook is reference-only, so"
        " this spelling cannot change"
    )
    assert target.columns[field_name] == column
    assert layouts.MISSPELLED_FIELDS[field_name] == column
    # WHY : Assumptions: the corrected spelling is asserted absent from the SOURCE field names as
    #   well as present on the column. The correction is a rename at this boundary only -- the
    #   copybook keeps its misspelling because ``app/**`` is reference-only -- so a layout that had
    #   acquired the corrected spelling would mean the baseline had been edited.
    corrected_spelling = field_name.replace("EXPIRAION", "EXPIRATION")
    assert all(field.name != corrected_spelling for field in layout.fields)


def test_no_field_beyond_the_three_documented_ones_is_renamed() -> None:
    """Assert the misspelling registry covers every misspelled field and adds no fourth.

    Purpose
    -------
    Close the corrections in both directions. Three baseline fields are misspelled and each is
    corrected; a fourth entry would be a rename dressed as a correction, and a misspelled field
    missing from the registry would reach a column carrying the defect.

    Returns
    -------
    None
        Nothing; a registry entry with no misspelled field behind it, or a misspelled field with
        no entry, is reported as an assertion failure.

    Raises
    ------
    None
    """
    assert dict(layouts.MISSPELLED_FIELDS) == {
        "ACCT-EXPIRAION-DATE": "expiration_date",
        "CARD-EXPIRAION-DATE": "expiration_date",
        _UNTARGETED_MISSPELLING[0]: _UNTARGETED_MISSPELLING[1],
    }

    # WHY : Assumptions: completeness is established by SCANNING every declared layout for the two
    #   misspelling fragments the baseline actually contains, rather than by counting the registry's
    #   rows. A count says the registry has three entries; the scan says the baseline has no
    #   misspelling the registry does not account for, and only the second detects a fourth
    #   misspelled field that nobody registered -- which would reach its column with the defect
    #   intact. The scan covers every declared record, not only the fourteen the reader registry
    #   dispatches on, because two of the three corrections belong to records outside it.
    misspelled = {
        field.name
        for spec in _all_declared_layouts().values()
        for field in spec.fields
        if any(fragment in field.name.upper() for fragment in _MISSPELLING_FRAGMENTS)
    }
    registered = set(layouts.MISSPELLED_FIELDS)
    assert registered <= misspelled, (
        "the registry corrects a field no declared layout carries:"
        f" {sorted(registered - misspelled)}"
    )

    # WHY : Assumptions: the export payload re-declares two of the three misspellings under an
    #   ``EXP-`` prefix, and they are accounted for as ECHOES of a registered spelling rather than
    #   admitted as two more corrections. That distinction is the point: the export record is a
    #   500-byte wire image that round-trips through the baseline's own export and import pair, so
    #   it has no target column to correct -- a registry entry for it would assert a rename that
    #   nothing performs. Matching by suffix is what keeps a genuinely NEW misspelling failing here
    #   instead of being waved through as another echo.
    unaccounted = {
        name
        for name in misspelled - registered
        if not any(name.endswith(key) for key in registered)
    }
    assert unaccounted == set(), (
        "a misspelled field is neither corrected nor an echo of a corrected one:"
        f" {sorted(unaccounted)}"
    )

    # WHY : Assumptions: the load boundary is closed separately, because it is where a rename has
    #   an observable effect. Every mapped field carrying a misspelling must be one the registry
    #   corrects, and must land on the column the registry names. The check is deliberately narrow
    #   -- it does not try to derive a column from a field name -- because the transformation is
    #   genuinely not mechanical: CUST-ID becomes customer_id and TRAN-TYPE becomes type_cd, so a
    #   general rule would reject correct mappings.
    for name in aurora.target_names():
        target = aurora.TARGETS[name]
        for field_name, column in target.columns.items():
            if not any(fragment in field_name.upper() for fragment in _MISSPELLING_FRAGMENTS):
                continue
            assert field_name in layouts.MISSPELLED_FIELDS, (
                f"{field_name} reaches column {column!r} of {target.qualified_name} while carrying"
                " a misspelling the registry does not correct"
            )
            assert column == layouts.MISSPELLED_FIELDS[field_name]


def test_a_load_diagnostic_names_the_field_and_never_its_value(
    fake_aurora: FakeAuroraDatabase,
) -> None:
    """Assert a refused load quotes no sensitive value and echoes no raw record.

    Purpose
    -------
    Establish that the loader's own failure messages are safe to log. A diagnostic is the one
    place record content escapes the pipeline unbidden, and a message that quoted the record it
    refused would put a primary account number, a national identifier, a name or a date of birth
    into a log line.

    Parameters
    ----------
    fake_aurora : FakeAuroraDatabase
        The database double, supplying a connection the refused load rolls back.

    Returns
    -------
    None
        Nothing; a diagnostic carrying a sensitive value is reported as an assertion failure.

    Raises
    ------
    None
        The failure this test provokes is caught by :func:`pytest.raises`, which verifies
        :class:`carddemo_migration.loaders.aurora.AuroraLoadError`.
    """
    # WHY : Assumptions: the sensitive values are SYNTHETIC and each is unmistakable in a
    #   message, which is what makes a substring search over the diagnostic a sound assertion.
    #   Reading real values out of the corpus was the alternative and was rejected: a corpus value
    #   can legitimately coincide with a fragment of a column name or a byte count, so a false
    #   pass would be indistinguishable from a real one.
    sensitive = {
        "pan": "4111111111111111",
        "national-identifier": "999-00-1234",
        "government-identifier": "GOVTID9999999999",
        "first-name": "SYNTHETICFIRST",
        "last-name": "SYNTHETICLAST",
        "date-of-birth": "1970-01-02",
    }
    target = aurora.TARGETS["CUSTOMER"]
    connection = _connect_as_owning_role(fake_aurora, target.schema)
    # Assumptions: the record is deliberately INCOMPLETE, carrying the sensitive fields and
    #   omitting a mapped one, because a refusal is the only way to make the loader speak. A
    #   complete record loads silently and produces no diagnostic to inspect.
    incomplete = {
        "CUST-ID": "000000001",
        "CUST-FIRST-NAME": sensitive["first-name"],
        "CUST-LAST-NAME": sensitive["last-name"],
        "CUST-SSN": sensitive["national-identifier"],
        "CUST-GOVT-ISSUED-ID": sensitive["government-identifier"],
        "CUST-DOB-YYYY-MM-DD": sensitive["date-of-birth"],
    }

    with pytest.raises(aurora.AuroraLoadError) as refusal:
        aurora.load_records(connection, target, [incomplete])

    message = str(refusal.value)
    for label, value in sensitive.items():
        assert value not in message, f"the diagnostic echoed the {label}"
    assert "customers" in message
    assert "missing the mapped field" in message
    assert fake_aurora.rollbacks == 1
    assert fake_aurora.commits == 0

    # WHY : Assumptions: the card path is exercised as well, because the primary account number is
    #   the value with the strictest disclosure rule in this system and it lives in a different
    #   record with a different projection -- the card's verification value is enciphered, so its
    #   refusal takes another branch of the same function.
    card_connection = _connect_as_owning_role(fake_aurora, aurora.TARGETS["CARD"].schema)
    with pytest.raises(aurora.AuroraLoadError) as card_refusal:
        aurora.load_records(
            card_connection, aurora.TARGETS["CARD"], [{"CARD-NUM": sensitive["pan"]}]
        )
    assert sensitive["pan"] not in str(card_refusal.value)


# ---------------------------------------------------------------------------
# The Aurora bulk load: idempotency and the negative privilege contract
# ---------------------------------------------------------------------------


@pytest.mark.parametrize("record", aurora.target_names())
def test_the_conflict_target_is_read_from_the_record_descriptor(record: str) -> None:
    """Assert a declared conflict key equals the columns of the descriptor's key window.

    Purpose
    -------
    Establish that idempotency is a property of the layout rather than of a per-table code path.
    The conflict target is the record's own primary key, computed here from ``key_offset`` and
    ``key_length`` and compared against what the target declares.

    Parameters
    ----------
    record : str
        One registered record name, supplied for each of the eleven load targets.

    Returns
    -------
    None
        Nothing; a conflict key that is not the descriptor's key window is reported as an
        assertion failure.

    Raises
    ------
    None
        For a target declaring no conflict key this test asserts a refusal instead, caught by
        :func:`pytest.raises`, which verifies
        :class:`carddemo_migration.loaders.aurora.AuroraLoadError` -- such a target has no merge
        statement to produce.
    """
    target = aurora.TARGETS[record]
    derived = _key_window_columns(target)
    layout = layouts.layout(record)

    # WHY : Assumptions: the key window is asserted NON-EMPTY for every record, because a record
    #   with no primary key would make the derivation below vacuously agree with an empty declared
    #   key -- so the property would hold for a target that had simply lost its conflict target.
    assert derived, f"{record} yields no key column from key_offset/key_length"
    assert layout.key_length > 0
    assert layout.key_offset >= 0

    if target.conflict_key:
        # WHY : Assumptions: the conflict target is compared against the DESCRIPTOR rather than
        #   against a column list written in this test, which is the whole point. A hard-coded
        #   expectation would drift from the layout exactly as a hard-coded conflict target in the
        #   loader would, and the two would then agree with each other while both disagreed with
        #   the key the table is actually declared on.
        assert target.conflict_key == derived, (
            f"{record} declares ON CONFLICT {target.conflict_key} but its descriptor's key window"
            f" is {derived}"
        )
        assert f"ON CONFLICT ({', '.join(chr(34) + c + chr(34) for c in derived)})" in (
            target.merge_statement()
        )
    else:
        # WHY : Assumptions: a target with no conflict key is asserted to have no merge statement
        #   rather than merely being skipped. Only a table with a SECOND writer merges; a
        #   single-writer master loads through a plain COPY that fails on a second run, and that
        #   failure is useful information a silent no-op would destroy.
        with pytest.raises(aurora.AuroraLoadError):
            target.merge_statement()


def test_loading_one_dataset_twice_adds_nothing_and_raises_nothing(
    fake_aurora: FakeAuroraDatabase, seed_corpus: SeedCorpus
) -> None:
    """Assert a repeated load of a merge-path dataset yields the same rows and no key error.

    Purpose
    -------
    Establish idempotency by construction on the tables that need it. The disclosure groups are
    seeded by the reference service's own migration before this loader ever runs, so a second
    pass has to compose with rows already present rather than abort on the first key collision.

    Parameters
    ----------
    fake_aurora : FakeAuroraDatabase
        The database double. Its arranged affected-row count stands in for what the server would
        report for the merge, since a double has no rows of its own to conflict.
    seed_corpus : SeedCorpus
        Read-only accessor over the committed seed datasets, supplying the disclosure groups.

    Returns
    -------
    None
        Nothing; a differing row set between the two passes, or any raised error, is reported as
        an assertion failure.

    Raises
    ------
    None
    """
    target = aurora.TARGETS["DISGROUP"]
    assert target.conflict_key, "the disclosure groups must load through the merge path"
    extract = seed_corpus.ascii_path("discgrp.txt")
    expected_rows = len(seed_corpus.ascii_records("discgrp.txt"))

    first_connection = _connect_as_owning_role(fake_aurora, target.schema)
    fake_aurora.arrange_affected_rows("INSERT INTO", expected_rows)
    first = aurora.load_records(
        first_connection, target, discgrp.read_ascii_disclosure_groups(extract)
    )
    first_rows = tuple(row for _statement, row in fake_aurora.copied_rows)

    # WHY : Assumptions: the second pass arranges ZERO affected rows, which is what the server
    #   reports for ``ON CONFLICT ... DO NOTHING`` against a table that already holds every key.
    #   That is the state a re-run actually meets, and it is a SUCCESS: the outcome distinguishes
    #   rows offered from rows gained, so a re-run reports every row skipped and raises nothing.
    fake_aurora.arrange_affected_rows("INSERT INTO", 0)
    second_connection = _connect_as_owning_role(fake_aurora, target.schema)
    second = aurora.load_records(
        second_connection, target, discgrp.read_ascii_disclosure_groups(extract)
    )
    second_rows = tuple(row for _statement, row in fake_aurora.copied_rows)[len(first_rows) :]

    assert first.staged == second.staged == expected_rows
    assert first.inserted == expected_rows
    assert second.inserted == 0
    assert second.skipped == expected_rows
    # WHY : Assumptions: the FINAL ROW SET is compared, not just the counts. Idempotency means the
    #   table ends in the same state, so two passes offering the same keys with different values --
    #   which a non-deterministic decode would produce -- must fail here even though both counts
    #   would match.
    assert second_rows == first_rows
    assert fake_aurora.commits == 2
    assert fake_aurora.rollbacks == 0


@pytest.mark.parametrize(
    ("record", "reader", "dataset"),
    (
        pytest.param("ACCOUNT", "copy", "acctdata.txt", id="copy-path-accounts"),
        pytest.param("DISGROUP", "merge", "discgrp.txt", id="merge-path-disclosure-groups"),
    ),
)
def test_the_loader_issues_no_statement_its_login_role_does_not_hold(
    record: str,
    reader: str,
    dataset: str,
    fake_aurora: FakeAuroraDatabase,
    fixture_corpus: FixtureCorpus,
    seed_corpus: SeedCorpus,
) -> None:
    """Assert neither load path issues schema, role, grant, delete, truncate or index statements.

    Purpose
    -------
    Establish the negative half of the privilege contract on both code paths. Each login role
    holds ``SELECT``, ``INSERT`` and ``UPDATE`` on its own schema and nothing else -- no DDL, no
    ownership, no ``DELETE`` and no ``TRUNCATE`` -- so a loader issuing any of those would fail at
    runtime partway through a cutover, which is the worst moment to discover it. The detector's
    own vocabulary is pinned to all seven withheld statement kinds first, so that an empty breach
    tuple cannot be read as a pass when nothing was being looked for.

    Parameters
    ----------
    record : str
        The registered record being loaded.
    reader : str
        Which path the target takes, ``"copy"`` or ``"merge"``, asserted against the target's own
        declaration so the parametrisation cannot silently exercise one path twice.
    dataset : str
        File name of the extract to load.
    fake_aurora : FakeAuroraDatabase
        The database double, whose recorded statement log is what makes a never-issued statement
        observable at all.
    fixture_corpus : FixtureCorpus
        Read-only accessor supplying the account extract.
    seed_corpus : SeedCorpus
        Read-only accessor supplying the disclosure-group extract.

    Returns
    -------
    None
        Nothing; any forbidden statement is reported as an assertion failure naming the breach and
        the offending SQL.

    Raises
    ------
    None
    """
    target = aurora.TARGETS[record]
    assert bool(target.conflict_key) is (reader == "merge")
    connection = _connect_as_owning_role(fake_aurora, target.schema)

    if reader == "merge":
        fake_aurora.arrange_affected_rows("INSERT INTO", 1)
        records = discgrp.read_ascii_disclosure_groups(seed_corpus.ascii_path(dataset))
    else:
        records = account.read_ascii_accounts(
            fixture_corpus.path("provisioning/happy_path", dataset)
        )

    outcome = aurora.load_records(connection, target, records)

    assert outcome.staged > 0, "an empty load would prove nothing about the statements issued"
    # WHY : Assumptions: the detector's vocabulary is pinned before it is trusted. An empty breach
    #   tuple means either that no forbidden statement was issued or that nothing was looked for,
    #   and those two readings are indistinguishable from the tuple alone -- so the seven kinds are
    #   compared against the set this module declares, which makes a narrowed detector fail here
    #   instead of quietly reducing the assertion below to a tautology.
    assert set(FORBIDDEN_LOADER_STATEMENTS) == _WITHHELD_STATEMENT_KINDS, (
        "the double no longer detects every statement kind withheld from the login roles:"
        f" missing {sorted(_WITHHELD_STATEMENT_KINDS - set(FORBIDDEN_LOADER_STATEMENTS))}"
    )
    # WHY : Assumptions: the breach set comes from the double's recorded log rather than from a
    #   text search over the loader's source. Both loaders discuss CREATE INDEX and TRUNCATE at
    #   length in their comments in order to explain why they issue neither, so a source search
    #   reports those explanations; the log reports what was executed.
    assert fake_aurora.forbidden_statements() == (), (
        "the load issued statements the login role does not hold:"
        f" {fake_aurora.forbidden_statements()}"
    )

    # WHY : Assumptions: index creation is called out separately even though the log check above
    #   already covers it, because it is RETIRED rather than merely unprivileged. The baseline ends
    #   three of its ten load jobs with an ``IDCAMS BLDINDEX`` step -- app/jcl/CARDFILE.jcl:110,
    #   app/jcl/XREFFILE.jcl:100 and app/jcl/TRANFILE.jcl:109 -- because VSAM builds an alternate
    #   index by reading the base cluster after the data is there. PostgreSQL maintains indexes
    #   transactionally, so a port of that step would have nothing to do, and the three surviving
    #   access paths are created by the owning services' own migrations. Creating them here would
    #   put two sources of truth on one index.
    executed = " ".join(fake_aurora.executed_sql()).upper()
    assert "CREATE INDEX" not in executed
    assert "BLDINDEX" not in executed
    assert "DROP " not in executed


def test_an_empty_extract_loads_zero_rows_and_succeeds(
    fake_aurora: FakeAuroraDatabase, fixture_corpus: FixtureCorpus
) -> None:
    """Assert a zero-byte extract loads nothing, commits, and raises nothing.

    Purpose
    -------
    Establish that an absent or empty extract is a normal state rather than a fault. The cutover's
    read-then-verify-then-switch sequence needs a load that reports zero rows successfully, so
    that a table with no extract is distinguishable from a table whose load failed.

    Parameters
    ----------
    fake_aurora : FakeAuroraDatabase
        The database double, recording the commit and the absence of copied rows.
    fixture_corpus : FixtureCorpus
        Read-only accessor supplying one of the committed zero-byte extracts.

    Returns
    -------
    None
        Nothing; a raised error, an uncommitted transaction, or any copied row is reported as an
        assertion failure.

    Raises
    ------
    None
    """
    # WHY : Assumptions: a COMMITTED zero-byte extract is used rather than a file this test
    #   creates, because the corpus already ships seven of them and they are the same inputs the
    #   reference suite drives its empty-input scenarios with. Writing a fresh empty file would
    #   prove the loader handles a file this test wrote.
    extract = fixture_corpus.path("provisioning/empty_input", "acctdata.txt")
    assert extract.stat().st_size == 0
    target = aurora.TARGETS["ACCOUNT"]
    connection = _connect_as_owning_role(fake_aurora, target.schema)

    outcome = aurora.load_records(connection, target, account.read_ascii_accounts(extract))

    assert outcome.staged == 0
    assert outcome.inserted == 0
    assert outcome.skipped == 0
    assert fake_aurora.copied_rows == []
    assert fake_aurora.commits == 1, "a zero-row load still closes its transaction"
    assert fake_aurora.rollbacks == 0
    assert fake_aurora.forbidden_statements() == ()


def test_a_failure_partway_through_rolls_back_rather_than_half_applying(
    fake_aurora: FakeAuroraDatabase,
) -> None:
    """Assert a mid-dataset refusal rolls the transaction back and raises the typed load error.

    Purpose
    -------
    Establish the failure half of the unit of work. A record the target cannot map is discovered
    partway through the stream, and by then earlier rows have already been written into the copy;
    the transaction has to be discarded so no partially-loaded master is left behind.

    Parameters
    ----------
    fake_aurora : FakeAuroraDatabase
        The database double, recording that a rollback happened and that no commit did.

    Returns
    -------
    None
        Nothing; a commit, a missing rollback, or an untyped failure is reported as an assertion
        failure.

    Raises
    ------
    None
        The provoked failure is caught by :func:`pytest.raises`, which verifies
        :class:`carddemo_migration.loaders.aurora.AuroraLoadError`.
    """
    target = aurora.TARGETS["XREF"]
    connection = _connect_as_owning_role(fake_aurora, target.schema)
    # WHY : Assumptions: the good record's field values are built from the record descriptor's own
    #   widths rather than typed at their literal lengths, so the fixture cannot drift from the
    #   layout, and no value here resembles a real card number or customer identifier.
    layout = layouts.layout("XREF")
    widths = {field.name: field.length for field in layout.fields}
    complete = {
        "XREF-CARD-NUM": "9" * widths["XREF-CARD-NUM"],
        "XREF-CUST-ID": "0" * (widths["XREF-CUST-ID"] - 1) + "1",
        "XREF-ACCT-ID": "0" * (widths["XREF-ACCT-ID"] - 1) + "2",
    }
    truncated = {"XREF-CARD-NUM": complete["XREF-CARD-NUM"]}

    with pytest.raises(aurora.AuroraLoadError) as refusal:
        aurora.load_records(connection, target, [complete, truncated])

    assert fake_aurora.rollbacks == 1
    assert fake_aurora.commits == 0, "a refused load must not commit the rows it had already sent"
    # WHY : Assumptions: the first row IS observed in the copy log, and that is the point rather
    #   than an inconvenience. It proves the failure was genuinely mid-stream, so the rollback is
    #   what makes the partial write unobservable -- a test whose failure arrived before any row
    #   was sent would assert the same rollback while proving nothing about partial application.
    assert len(fake_aurora.copied_rows) == 1
    assert "nothing is loaded" in str(refusal.value)
    assert target.table in str(refusal.value)


@pytest.mark.parametrize(
    "module", (aurora, s3_stage), ids=lambda module: module.__name__.rsplit(".", 1)[-1]
)
def test_no_validation_in_a_loader_relies_on_a_bare_assert(module: Any) -> None:  # noqa: ANN401
    """Assert neither loader validates with ``assert``, which optimisation strips.

    Purpose
    -------
    Establish that every refusal survives a production interpreter. ``python -O`` and
    ``PYTHONOPTIMIZE`` remove every ``assert`` statement from the compiled bytecode, so an
    assertion is not a validation -- it is a validation that disappears under exactly the flag a
    production container is most likely to set. A dataset whose reader and target disagreed about
    its shape would then be loaded silently instead of refused.

    Parameters
    ----------
    module : Any
        One of the two loader modules, whose committed source is parsed. Typed loosely because a
        module object is not otherwise constrained here.

    Returns
    -------
    None
        Nothing; any ``assert`` statement in a loader is reported as an assertion failure naming
        its line.

    Raises
    ------
    None
    """
    tree = _module_tree(module.__file__)
    asserts = [node.lineno for node in ast.walk(tree) if isinstance(node, ast.Assert)]

    assert asserts == [], f"{module.__name__} validates with assert at line(s) {asserts}"

    # WHY : Assumptions: the positive half is asserted too -- that each module raises its OWN typed
    #   error -- because a module could satisfy the check above by validating nothing at all.
    raised = {
        node.exc.func.id
        for node in ast.walk(tree)
        if isinstance(node, ast.Raise)
        and isinstance(node.exc, ast.Call)
        and isinstance(node.exc.func, ast.Name)
    }
    expected = (
        {"AuroraLoadError"}
        if module is aurora
        else {"GenerationRetentionError", "DatasetSourceError", "GenerationDiscoveryError"}
    )
    assert expected <= raised, f"{module.__name__} raises {sorted(raised)}, missing {expected}"


# ---------------------------------------------------------------------------
# The S3 generation staging: the prefix convention and verbatim bytes
# ---------------------------------------------------------------------------


def test_the_staged_prefix_is_rendered_by_the_one_canonical_builder(
    fake_object_store: FakeObjectStore,
    staging_settings: DatasetStagingSettings,
    seed_corpus: SeedCorpus,
) -> None:
    """Assert a staged object lands under ``<domain>/<dataset>/dt=YYYY-MM-DD/gen=NNNN/``.

    Purpose
    -------
    Establish that the staged key comes from the configuration module's prefix builder rather than
    from a format string at the staging site. A second definition of the layout would let one
    writer and one reader disagree about where a dataset lives while both looked correct in
    isolation.

    Parameters
    ----------
    fake_object_store : FakeObjectStore
        The object-store double, recording the key each put was made under.
    staging_settings : DatasetStagingSettings
        Validated staging settings naming a synthetic bucket, owning the prefix builder.
    seed_corpus : SeedCorpus
        Read-only accessor supplying a committed extract to stage.

    Returns
    -------
    None
        Nothing; a key that is not the builder's own output is reported as an assertion failure.

    Raises
    ------
    None
    """
    registered = s3_stage.family("dalyrejs")
    extract = seed_corpus.ascii_path("discgrp.txt")

    staged = s3_stage.stage_dataset_file(
        client=fake_object_store,
        settings=staging_settings,
        domain=registered.domain,
        dataset=registered.dataset,
        business_date=_BUSINESS_DATE,
        generation=7,
        source=extract,
    )

    # WHY : Assumptions: the expectation is the BUILDER's output, not a string composed here. A
    #   literal expectation would be a second copy of the layout inside a test that exists to prove
    #   there is only one copy, so the two could agree while both diverged from the convention the
    #   Terraform-provisioned prefixes actually use.
    expected_prefix = staging_settings.generation_prefix(
        registered.domain, registered.dataset, _BUSINESS_DATE, 7
    )
    assert staged.prefix == expected_prefix
    assert staged.prefix == "ledger/dalyrejs/dt=2022-07-18/gen=0007/"
    assert staged.key == f"{expected_prefix}{extract.name}"
    assert fake_object_store.keys() == (staged.key,)
    # WHY : Assumptions: the trailing separator is part of the prefix and is asserted as such,
    #   because without it ``gen=0001`` is a string prefix of ``gen=00010`` for anyone listing by
    #   match -- so a listing scoped to one generation would return another's objects too.
    assert staged.prefix.endswith("/")
    assert staged.business_date == _BUSINESS_DATE
    assert staged.generation == 7
    # WHY : Assumptions: the family's own delegating builder is checked to agree, because a caller
    #   addressing a dataset by family name reaches the prefix through that method instead, and the
    #   two paths have to produce one answer or a writer and a reader can each look correct.
    assert registered.generation_prefix(staging_settings, _BUSINESS_DATE, 7) == expected_prefix


def test_a_binary_ebcdic_extract_is_staged_byte_for_byte(
    fake_object_store: FakeObjectStore,
    staging_settings: DatasetStagingSettings,
    seed_corpus: SeedCorpus,
) -> None:
    """Assert the staged body is the source extract's raw bytes, unchanged.

    Purpose
    -------
    Establish that staging performs no transcoding, no newline normalisation and no re-encoding.
    The property is proven against a binary EBCDIC extract rather than a text one because that is
    the case where a corruption is silent: the file is not UTF-8, carries no trailing newline, and
    holds stray control bytes that a text-mode write would rewrite.

    Parameters
    ----------
    fake_object_store : FakeObjectStore
        The object-store double, which holds and returns bodies as the exact bytes handed in.
    staging_settings : DatasetStagingSettings
        Validated staging settings naming a synthetic bucket.
    seed_corpus : SeedCorpus
        Read-only accessor over ``app/data``, supplying the committed EBCDIC extract in place.

    Returns
    -------
    None
        Nothing; any difference between the staged body and the source file is reported as an
        assertion failure.

    Raises
    ------
    None
    """
    extract = seed_corpus.ebcdic_path(_BINARY_EXTRACT)
    source_bytes = extract.read_bytes()

    # WHY : Assumptions: the extract's measured shape is asserted BEFORE it is staged, so a
    #   corruption of the committed file itself is reported as such rather than as a staging fault.
    #   ``app/data`` is reference-only and this module reads it in place; the five stray line feeds
    #   and eleven stray carriage returns are the bytes a text write would rewrite, and their
    #   presence is what gives the comparison below something to catch.
    assert len(source_bytes) == _BINARY_EXTRACT_BYTE_SIZE
    assert source_bytes.count(b"\x0a") == _BINARY_EXTRACT_STRAY_LINE_FEEDS
    assert source_bytes.count(b"\x0d") == _BINARY_EXTRACT_STRAY_CARRIAGE_RETURNS
    assert b"\x00" in source_bytes, "the extract carries no NUL byte, so it is not the binary image"

    staged = s3_stage.stage_dataset_file(
        client=fake_object_store,
        settings=staging_settings,
        domain=s3_stage.family("transact-bkup").domain,
        dataset=s3_stage.family("transact-bkup").dataset,
        business_date=_BUSINESS_DATE,
        generation=1,
        source=extract,
    )

    body = fake_object_store.body_of(staged.key)
    assert body == source_bytes, "the staged body is not the source extract's bytes"
    assert len(body) == _BINARY_EXTRACT_BYTE_SIZE
    assert body.count(b"\x0a") == _BINARY_EXTRACT_STRAY_LINE_FEEDS
    assert body.count(b"\x0d") == _BINARY_EXTRACT_STRAY_CARRIAGE_RETURNS
    # WHY : Assumptions: the committed file is re-read AFTER staging and compared again, which is
    #   what proves the loader read the extract without rewriting it. ``app/data`` is
    #   reference-only,
    #   so a staging step that normalised in place would be a far worse defect than one that
    #   uploaded corrupted bytes, and it would leave the assertion above passing.
    assert extract.read_bytes() == source_bytes
    assert extract.stat().st_size == _BINARY_EXTRACT_BYTE_SIZE


def test_the_byte_size_and_digest_anchors_are_recorded_with_the_object(
    fake_object_store: FakeObjectStore,
    staging_settings: DatasetStagingSettings,
    seed_corpus: SeedCorpus,
) -> None:
    """Assert the staged length and SHA-256 are computed and stored alongside the object.

    Purpose
    -------
    Establish the audit anchors a later verification pass reads. The digest has to travel with the
    object rather than only in a log line, so the check stays authoritative rather than
    self-reported even when staging and verification run in different processes on different days.

    Parameters
    ----------
    fake_object_store : FakeObjectStore
        The object-store double, recording each put's arguments and the metadata stored with the
        object.
    staging_settings : DatasetStagingSettings
        Validated staging settings naming a synthetic bucket.
    seed_corpus : SeedCorpus
        Read-only accessor supplying the committed EBCDIC extract.

    Returns
    -------
    None
        Nothing; a missing or disagreeing anchor is reported as an assertion failure.

    Raises
    ------
    None
    """
    extract = seed_corpus.ebcdic_path(_BINARY_EXTRACT)
    # WHY : Assumptions: the expected digest is computed HERE from the file's own bytes rather than
    #   pinned as a literal. A pinned hexadecimal string would have to be re-measured whenever the
    #   corpus changed, and a stale one fails with a message about a digest mismatch when the real
    #   fact is that the extract moved -- which sends a reader to the wrong file.
    expected = hashlib.sha256(extract.read_bytes())

    staged = s3_stage.stage_dataset_file(
        client=fake_object_store,
        settings=staging_settings,
        domain=s3_stage.family("systran").domain,
        dataset=s3_stage.family("systran").dataset,
        business_date=_BUSINESS_DATE,
        generation=1,
        source=extract,
    )

    assert staged.byte_size == _BINARY_EXTRACT_BYTE_SIZE
    assert staged.sha256 == expected.hexdigest()

    metadata = fake_object_store.metadata_of(staged.key)
    assert metadata["carddemo-sha256"] == expected.hexdigest()
    assert metadata["carddemo-byte-size"] == str(_BINARY_EXTRACT_BYTE_SIZE)

    put = fake_object_store.put_calls[-1]
    assert put["ContentLength"] == _BINARY_EXTRACT_BYTE_SIZE
    # WHY : Assumptions: the service-side checksum parameter is asserted as well as the metadata,
    #   because the two are not interchangeable. Metadata is an opaque string the service stores
    #   without reading, so on its own it records a CLAIM that the upload was intact; the checksum
    #   parameter makes the service recompute the digest over the bytes it received and reject the
    #   write when they disagree, which is what detects an in-flight corruption.
    assert "ChecksumSHA256" in put
    assert put["ChecksumSHA256"] == base64.b64encode(expected.digest()).decode("ascii")


# ---------------------------------------------------------------------------
# The S3 generation staging: ten families, not six
# ---------------------------------------------------------------------------


def test_the_generation_family_enumeration_holds_exactly_ten_entries() -> None:
    """Assert the canonical family enumeration is the ten baseline generation-data groups.

    Purpose
    -------
    Hold the enumeration to ten in both directions. This is the single most likely error in the
    staging contract: six of the ten are defined together in one job that reads as complete, so an
    enumeration derived from that file alone under-provisions the pipeline by four families and
    nothing fails -- the missing families' steps still write their objects, into prefixes carrying
    no retention contract, and their generations accumulate without limit.

    Returns
    -------
    None
        Nothing; a count other than ten, or a name in one collection and not the other, is reported
        as an assertion failure.

    Raises
    ------
    None
    """
    declared = s3_stage.family_names()

    assert len(declared) == 10, f"the enumeration holds {len(declared)} families, not ten"
    assert len(_TEN_GENERATION_FAMILIES) == 10
    assert set(declared) == {fact.key for fact in _TEN_GENERATION_FAMILIES}
    assert len(set(declared)) == len(declared), "a family is enumerated twice"

    # WHY : Assumptions: the BASE NAMES are asserted too, not only the path segments. The segment is
    #   a cloud-side name this migration chose; the base name is the mainframe dataset the family
    #   stands for, and it is what ties a staged prefix back to the job that produced it. A segment
    #   pointing at the wrong base would be invisible in a key.
    assert {s3_stage.family(name).base_name for name in declared} == {
        fact.base_name for fact in _TEN_GENERATION_FAMILIES
    }

    # WHY : Assumptions: the arithmetic is asserted per defining job, because the failure this test
    #   guards against is not "the count is wrong" but "one job was read and the other two were
    #   not". Six plus three plus one is the shape of the mistake, so it is the shape of the check.
    from_job: dict[str, int] = {}
    for name in declared:
        from_job[s3_stage.family(name).defined_in] = (
            from_job.get(s3_stage.family(name).defined_in, 0) + 1
        )
    assert from_job == {
        "app/jcl/DEFGDGB.jcl": 6,
        "app/jcl/DEFGDGD.jcl": 3,
        "app/jcl/DALYREJS.jcl": 1,
    }


@pytest.mark.parametrize("fact", _TEN_GENERATION_FAMILIES, ids=lambda fact: fact.key)
def test_each_family_cites_its_defining_job_and_keeps_five_generations(
    fact: _GenerationFamilyFact,
) -> None:
    """Assert one family names its baseline base, its defining job and the scratch limit of five.

    Purpose
    -------
    Establish per family that the descriptor carries its own provenance and the retention intent
    the baseline declares. Every one of the ten generation-data groups is defined with
    ``LIMIT(5)`` and ``SCRATCH``, so five is the number of generations the cloud side must keep.

    Parameters
    ----------
    fact : _GenerationFamilyFact
        The expected family, carrying the base name, the defining job and the line its ``NAME``
        operand sits on.

    Returns
    -------
    None
        Nothing; a missing family, a wrong base name, a wrong citation or a retention limit other
        than five is reported as an assertion failure.

    Raises
    ------
    None
    """
    registered = s3_stage.family(fact.key)

    assert registered.dataset == fact.key
    assert registered.base_name == fact.base_name
    assert registered.defined_in == fact.defined_in
    # WHY : Assumptions: the citation is matched as the ``<line> NAME`` pair anywhere in the
    #   descriptor's recorded lines rather than as its opening text. Nine of the ten record the NAME
    #   operand first, but ``dalyrejs`` records the DEFINE verb that opens its block at L24 ahead of
    #   its NAME at L25 -- measured, not assumed -- so a prefix match would fail on the one family
    #   whose definition spans a line the others' do not. Matching the pair pins the same fact
    #   without depending on how much of the block a descriptor chose to cite.
    assert f"{fact.name_line} NAME" in registered.definition_lines
    assert "LIMIT(5)" in registered.definition_lines
    assert "SCRATCH" in registered.definition_lines

    # WHY : Assumptions: five is asserted against the module's published default as well as
    #   literally, so the two cannot drift apart. The literal is what ties the value to the
    #   baseline's ``LIMIT(5)``; the constant is what the staging path actually applies when a
    #   caller supplies no retention count.
    assert registered.retention_limit == 5
    assert registered.retention_limit == s3_stage.DEFAULT_GENERATION_RETENTION
    assert registered.domain and "/" not in registered.domain
    assert "/" not in registered.dataset


def test_staging_never_creates_a_bucket_or_applies_a_lifecycle(
    fake_object_store: FakeObjectStore,
    staging_settings: DatasetStagingSettings,
    seed_corpus: SeedCorpus,
) -> None:
    """Assert the staging loader provisions nothing, leaving the bucket to the infrastructure.

    Purpose
    -------
    Establish the boundary between the ETL and the infrastructure. The dataset bucket, its
    versioning and its noncurrent-version lifecycle -- the ``LIMIT(5) SCRATCH`` analogue -- are all
    Terraform's, so a loader that created a bucket or wrote a lifecycle configuration would put two
    sources of truth on the same resource and would need privileges its task role is not granted.

    Parameters
    ----------
    fake_object_store : FakeObjectStore
        The object-store double, whose recorded calls are the evidence. It offers no
        bucket-creation or lifecycle operation at all, so a loader attempting one would fail here.
    staging_settings : DatasetStagingSettings
        Validated staging settings naming a synthetic bucket that this test never creates.
    seed_corpus : SeedCorpus
        Read-only accessor supplying a committed extract to stage.

    Returns
    -------
    None
        Nothing; a provisioning call, or a client operation outside the declared four, is reported
        as an assertion failure.

    Raises
    ------
    None
    """
    s3_stage.stage_dataset_file(
        client=fake_object_store,
        settings=staging_settings,
        domain=s3_stage.family("tranrept").domain,
        dataset=s3_stage.family("tranrept").dataset,
        business_date=_BUSINESS_DATE,
        generation=1,
        source=seed_corpus.ascii_path("trantype.txt"),
    )

    # WHY : Assumptions: the double is asserted to OFFER no provisioning operation, which is a
    #   stronger statement than observing that none was called. A double carrying a create-bucket
    #   method would let a loader acquire the behaviour later and still pass a call-log assertion
    #   written today, because the log check only ever sees the calls this one test provoked.
    provisioning = (
        "create_bucket",
        "put_bucket_lifecycle_configuration",
        "put_bucket_versioning",
    )
    for operation in provisioning:
        assert not hasattr(fake_object_store, operation), (
            f"the object-store double offers {operation}, so a provisioning call could go unnoticed"
        )

    # WHY : Assumptions: the loader's reachable operations are read from its own source, so the
    #   guarantee covers every code path rather than the one this test walked. The four it calls are
    #   exactly the four its client protocol declares, and none of them can create a bucket or set a
    #   lifecycle rule.
    assert _client_operations(s3_stage) == {
        "get_paginator",
        "put_object",
        "get_object",
        "delete_objects",
    }
    for call in fake_object_store.put_calls:
        assert "LifecycleConfiguration" not in call
        assert "VersioningConfiguration" not in call
    assert all("Lifecycle" not in literal for literal in _executable_string_literals(s3_stage))


# ---------------------------------------------------------------------------
# The S3 generation staging: resolving (+1) and (0)
# ---------------------------------------------------------------------------


def test_a_new_generation_is_discovered_from_the_highest_existing_prefix(
    fake_object_store: FakeObjectStore,
    staging_settings: DatasetStagingSettings,
    seed_corpus: SeedCorpus,
) -> None:
    """Assert the baseline ``(+1)`` reference resolves by listing the object store.

    Purpose
    -------
    Establish where the next generation number comes from. The baseline's relative references are
    resolved by the catalog at run time, and discovery from the bucket's own prefixes is the
    faithful analogue of that.

    Parameters
    ----------
    fake_object_store : FakeObjectStore
        The object-store double, seeded with two existing generations through the loader itself.
    staging_settings : DatasetStagingSettings
        Validated staging settings naming a synthetic bucket.
    seed_corpus : SeedCorpus
        Read-only accessor supplying a committed extract to stage.

    Returns
    -------
    None
        Nothing; a next generation that is not one past the highest existing one is reported as an
        assertion failure.

    Raises
    ------
    None
    """
    # WHY : Alternatives Considered: a locally held counter was available and was rejected. It
    #   cannot survive a process restart, and it cannot survive a second concurrent stager: the
    #   batch chain stages through a state-machine Map state that runs one containerised branch per
    #   dataset, so every branch's counter would start from the same base and the second write would
    #   land on the first one's key. A retried branch is worse still -- it would recompute
    #   the number
    #   its failed attempt already used and overwrite a generation that had completed. The bucket's
    #   own prefix listing is the one durable state every branch and every attempt agrees on.
    registered = s3_stage.family("dalyrejs")
    extract = seed_corpus.ascii_path("discgrp.txt")
    prefixes = _stage_generations(
        fake_object_store, staging_settings, "dalyrejs", _BUSINESS_DATE, (1, 2), extract
    )
    assert prefixes[0].endswith("gen=0001/")
    assert prefixes[1].endswith("gen=0002/")

    following = s3_stage.next_generation(
        fake_object_store,
        staging_settings,
        registered.domain,
        registered.dataset,
        _BUSINESS_DATE,
    )

    assert following == 3
    # WHY : Assumptions: a reservation is asserted to agree with the discovery, because a caller
    #   staging by family with no explicit generation reaches the number through the reservation
    #   path instead. The two must produce one answer or the ``(+1)`` form would depend on which
    #   entry point a step happened to use.
    reserved = s3_stage.reserve_generation(
        fake_object_store,
        staging_settings,
        registered.domain,
        registered.dataset,
        _BUSINESS_DATE,
        "synthetic-execution-token",
    )
    assert reserved == 3
    # WHY : Assumptions: the discovery is scoped to the TARGET business date, so a generation staged
    #   under a later date does not advance an earlier date's sequence. Without that scoping,
    #   re-running one day after a subsequent day had been staged would skip generation numbers and
    #   leave the two dates' sequences uncomparable.
    _stage_generations(
        fake_object_store, staging_settings, "dalyrejs", _LATER_BUSINESS_DATE, (1,), extract
    )
    assert (
        s3_stage.next_generation(
            fake_object_store,
            staging_settings,
            registered.domain,
            registered.dataset,
            _BUSINESS_DATE,
        )
        == 3
    )


def test_the_first_generation_of_a_business_date_is_the_documented_minimum(
    fake_object_store: FakeObjectStore, staging_settings: DatasetStagingSettings
) -> None:
    """Assert an empty family allocates the published minimum generation rather than zero.

    Purpose
    -------
    Establish the base case of the discovery. An empty family has no highest generation to add to,
    and the number it starts from is a published constant rather than an accident of arithmetic --
    zero is reserved for the prefix builder's own range and is not a generation any write uses.

    Parameters
    ----------
    fake_object_store : FakeObjectStore
        The object-store double, left empty so the family holds no generation.
    staging_settings : DatasetStagingSettings
        Validated staging settings naming a synthetic bucket.

    Returns
    -------
    None
        Nothing; a first generation other than the published minimum is reported as an assertion
        failure.

    Raises
    ------
    None
    """
    registered = s3_stage.family("tcatbalf-bkup")
    assert fake_object_store.keys() == ()

    first = s3_stage.next_generation(
        fake_object_store,
        staging_settings,
        registered.domain,
        registered.dataset,
        _BUSINESS_DATE,
    )

    assert first == s3_stage.MIN_GENERATION
    assert first == 1
    # WHY : Assumptions: the listing is asserted empty as well, so the answer above is known to come
    #   from a genuine discovery over nothing rather than from a short-circuit that never listed. A
    #   loader that returned the minimum without looking would pass the value check and fail here.
    assert (
        s3_stage.list_generation_prefixes(
            fake_object_store,
            staging_settings.bucket,
            s3_stage.family_prefix(staging_settings, registered.domain, registered.dataset),
        )
        == ()
    )


def test_the_current_generation_resolves_to_the_highest_and_is_absent_when_none_exists(
    fake_object_store: FakeObjectStore,
    staging_settings: DatasetStagingSettings,
    seed_corpus: SeedCorpus,
) -> None:
    """Assert the baseline ``(0)`` reference returns the newest generation, or reports none.

    Purpose
    -------
    Establish the read side of the convention. A consuming step must read the generation the
    baseline job would have read, and where a family holds nothing the answer must be an explicit
    absence -- never a fabricated generation zero, which would address a prefix no write ever used.

    Parameters
    ----------
    fake_object_store : FakeObjectStore
        The object-store double, first empty and then seeded with three generations.
    staging_settings : DatasetStagingSettings
        Validated staging settings naming a synthetic bucket.
    seed_corpus : SeedCorpus
        Read-only accessor supplying a committed extract to stage.

    Returns
    -------
    None
        Nothing; a resolved generation that is not the highest, or a fabricated one where none
        exists, is reported as an assertion failure.

    Raises
    ------
    None
    """
    registered = s3_stage.family("transact-combined")

    empty = s3_stage.latest_generation(
        fake_object_store, staging_settings, registered.domain, registered.dataset
    )
    # WHY : Assumptions: the empty answer is asserted to be an ABSENCE and explicitly not zero. The
    #   two are easy to conflate because both are falsey, and the difference matters: zero
    #   renders as ``gen=0000``, which is a well-formed prefix that no write ever creates, so
    #   a caller taking it
    #   as a generation would read an empty prefix and report a dataset as staged-but-empty.
    assert empty is None
    assert empty != 0

    _stage_generations(
        fake_object_store,
        staging_settings,
        "transact-combined",
        _BUSINESS_DATE,
        (1, 2, 3),
        seed_corpus.ascii_path("trancatg.txt"),
    )

    current = s3_stage.latest_generation(
        fake_object_store, staging_settings, registered.domain, registered.dataset
    )

    assert current is not None
    assert current.generation == 3
    assert current.business_date == _BUSINESS_DATE
    assert current.prefix.endswith("gen=0003/")


def test_an_exhausted_generation_space_raises_the_typed_staging_error(
    fake_object_store: FakeObjectStore,
    staging_settings: DatasetStagingSettings,
    seed_corpus: SeedCorpus,
) -> None:
    """Assert a family whose four-digit space is used up refuses rather than wrapping.

    Purpose
    -------
    Establish the boundary of the generation component. Once the highest generation a four-digit
    field can express is staged for a business date, there is no next number, and the discovery has
    to say so with its own typed error rather than return a value that would overwrite an existing
    generation.

    Parameters
    ----------
    fake_object_store : FakeObjectStore
        The object-store double, seeded with the highest expressible generation.
    staging_settings : DatasetStagingSettings
        Validated staging settings naming a synthetic bucket.
    seed_corpus : SeedCorpus
        Read-only accessor supplying a committed extract to stage.

    Returns
    -------
    None
        Nothing; a next generation returned instead of a refusal is reported as an assertion
        failure.

    Raises
    ------
    None
        The refusal is caught by :func:`pytest.raises`, which verifies
        :class:`carddemo_migration.loaders.s3_stage.GenerationDiscoveryError`.
    """
    registered = s3_stage.family("trantype-bkup")
    _stage_generations(
        fake_object_store,
        staging_settings,
        "trantype-bkup",
        _BUSINESS_DATE,
        (s3_stage.MAX_GENERATION,),
        seed_corpus.ascii_path("trantype.txt"),
    )

    with pytest.raises(s3_stage.GenerationDiscoveryError) as exhausted:
        s3_stage.next_generation(
            fake_object_store,
            staging_settings,
            registered.domain,
            registered.dataset,
            _BUSINESS_DATE,
        )

    assert "exhausted" in str(exhausted.value)
    assert f"gen={s3_stage.MAX_GENERATION}" in str(exhausted.value)
    # WHY : Assumptions: the error's PLACE IN THE HIERARCHY is asserted, not only its type. Both
    #   staging errors descend from one base so a caller that does not care which failed can catch
    #   the base; a type that stopped descending from it would silently escape every such handler
    #   while this test still passed on the class name alone.
    assert isinstance(exhausted.value, s3_stage.GenerationRetentionError)
    assert issubclass(s3_stage.GenerationDiscoveryError, s3_stage.GenerationRetentionError)


def test_the_business_date_is_a_required_parameter(
    fake_object_store: FakeObjectStore,
    staging_settings: DatasetStagingSettings,
    seed_corpus: SeedCorpus,
) -> None:
    """Assert staging refuses to run without an injected business date.

    Purpose
    -------
    Establish that the ``dt=`` component is supplied by the caller. A default would make the staged
    prefix depend on when the step ran rather than on which business day it was processing, so a
    re-run of a past day would stage under today's date and neither be idempotent nor comparable.

    Parameters
    ----------
    fake_object_store : FakeObjectStore
        The object-store double, which must receive no put at all from the refused call.
    staging_settings : DatasetStagingSettings
        Validated staging settings naming a synthetic bucket.
    seed_corpus : SeedCorpus
        Read-only accessor supplying a committed extract that is never staged here.

    Returns
    -------
    None
        Nothing; a defaulted business date is reported as an assertion failure.

    Raises
    ------
    None
        The refusal is caught by :func:`pytest.raises`, which verifies :class:`TypeError` -- the
        interpreter's own report of a missing required argument, which is the strongest possible
        form of the guarantee because no code path can bypass it.
    """
    # WHY : Assumptions: the baseline injects the business date as a job parameter for exactly this
    #   reason -- ``app/jcl/INTCALC.jcl`` L22 reads ``EXEC PGM=CBACT04C,PARM='2022071800'`` -- so a
    #   re-run reproduces its output rather than producing a new one. Reading the clock instead
    #   would make staged prefixes non-deterministic and a re-run non-idempotent, and it would do so
    #   silently: every prefix would still be well formed.
    registered = s3_stage.family("discgrp-bkup")
    extract = seed_corpus.ascii_path("discgrp.txt")

    with pytest.raises(TypeError) as missing:
        s3_stage.stage_dataset_file(  # type: ignore[call-arg]
            client=fake_object_store,
            settings=staging_settings,
            domain=registered.domain,
            dataset=registered.dataset,
            generation=1,
            source=extract,
        )

    assert "business_date" in str(missing.value)
    assert fake_object_store.put_calls == []

    with pytest.raises(TypeError):
        s3_stage.stage_family_file(  # type: ignore[call-arg]
            client=fake_object_store,
            settings=staging_settings,
            family_name="discgrp-bkup",
            source=extract,
            generation=1,
        )
    assert fake_object_store.put_calls == []


@pytest.mark.parametrize(
    "module", (aurora, s3_stage), ids=lambda module: module.__name__.rsplit(".", 1)[-1]
)
def test_neither_loader_reads_the_wall_clock(module: Any) -> None:  # noqa: ANN401 -- any module
    """Assert no loader derives a date or a timestamp from the current time.

    Purpose
    -------
    Establish determinism at the source. Both loaders take every date they use as a parameter, and
    a single clock read anywhere in either would make a re-run produce a different prefix or a
    different timestamp from the run it was repeating.

    Parameters
    ----------
    module : Any
        One of the two loader modules, whose committed source is parsed. Typed loosely because a
        module object is not otherwise constrained here.

    Returns
    -------
    None
        Nothing; any wall-clock call is reported as an assertion failure naming it.

    Raises
    ------
    None
    """
    # WHY : Assumptions: the check parses CALLS rather than searching the file's text, and that is
    #   load-bearing here rather than fastidious. Both loaders state in prose that a date is "never
    #   derived from a clock", and the staging module's parameter documentation names the clock
    #   explicitly, so a substring search for ``now`` or ``today`` reports those very promises as
    #   violations. Only the call graph distinguishes a promise from a breach.
    called = _called_dotted_names(module)
    offending = sorted(called & _WALL_CLOCK_CALLS)

    assert offending == [], f"{module.__name__} reads the wall clock via {offending}"


@pytest.mark.parametrize(("generation", "expected"), _GENERATION_PADDING_CASES)
def test_the_generation_is_zero_padded_to_four_digits(
    generation: int, expected: str, staging_settings: DatasetStagingSettings
) -> None:
    """Assert the ``gen=`` component is four digits, so a lexical listing sorts numerically.

    Purpose
    -------
    Establish the padding across every digit boundary the component has. Discovery reads the
    highest existing generation out of a string-ordered prefix listing, so without fixed-width
    padding ``gen=10`` would sort before ``gen=9`` and the newest generation would be misread while
    every individual prefix still looked well formed.

    Parameters
    ----------
    generation : int
        The generation number to render.
    expected : str
        The exact ``gen=`` segment, including its trailing separator.
    staging_settings : DatasetStagingSettings
        Validated staging settings owning the one canonical prefix builder.

    Returns
    -------
    None
        Nothing; a segment of the wrong width is reported as an assertion failure.

    Raises
    ------
    None
    """
    # WHY : Assumptions: the padded segment is asserted against the module's published digit
    #   count as well as against a literal four. The literal ties the width to the
    #   ``gen=NNNN`` convention the infrastructure provisions; the constant is what the
    #   builder actually applies, so a change to one without the other fails here.
    prefix = staging_settings.generation_prefix("ledger", "dalyrejs", _BUSINESS_DATE, generation)

    assert prefix.endswith(expected)
    assert f"dt={_BUSINESS_DATE.isoformat()}/" in prefix
    digits = prefix.rsplit("gen=", 1)[1].rstrip("/")
    assert len(digits) == s3_stage.GENERATION_DIGITS == 4
    assert digits == str(generation).zfill(4)


def test_zero_padding_makes_a_lexical_listing_sort_numerically(
    fake_object_store: FakeObjectStore,
    staging_settings: DatasetStagingSettings,
    seed_corpus: SeedCorpus,
) -> None:
    """Assert generations that straddle a digit boundary are discovered in numeric order.

    Purpose
    -------
    Turn the padding property into the behaviour it exists for. Nine and ten are the pair that
    exposes an unpadded component, so staging both and asking for the newest is what proves the
    ordering is numeric rather than merely that the strings are the right width.

    Parameters
    ----------
    fake_object_store : FakeObjectStore
        The object-store double, seeded with generations either side of the boundary.
    staging_settings : DatasetStagingSettings
        Validated staging settings naming a synthetic bucket.
    seed_corpus : SeedCorpus
        Read-only accessor supplying a committed extract to stage.

    Returns
    -------
    None
        Nothing; a newest generation resolved to nine rather than ten is reported as an assertion
        failure.

    Raises
    ------
    None
    """
    # WHY : Assumptions: nine and ten are the specific pair that exposes an unpadded
    #   component, because ``gen=10`` sorts before ``gen=9`` as text while sorting after it as
    #   a number. Any other pair either straddles no boundary or fails for a second reason,
    #   so this is the smallest arrangement that isolates the ordering property.
    registered = s3_stage.family("transact-daly")
    _stage_generations(
        fake_object_store,
        staging_settings,
        "transact-daly",
        _BUSINESS_DATE,
        (9, 10),
        seed_corpus.ascii_path("tcatbal.txt"),
    )

    current = s3_stage.latest_generation(
        fake_object_store, staging_settings, registered.domain, registered.dataset
    )

    assert current is not None
    assert current.generation == 10, "an unpadded component would have made nine the newest"
    assert (
        s3_stage.next_generation(
            fake_object_store,
            staging_settings,
            registered.domain,
            registered.dataset,
            _BUSINESS_DATE,
        )
        == 11
    )


# ---------------------------------------------------------------------------
# Hermeticity and layering: no literal endpoint, no leaked client, no credential
# ---------------------------------------------------------------------------


def test_the_staging_client_is_built_through_the_configuration_module() -> None:
    """Assert the staging loader obtains its client from the configuration module's factory.

    Purpose
    -------
    Establish the single point of client construction. The configuration module memoises its
    clients and discards them all when the package's resolution state is reset; a client built
    locally in the staging module would not be in that set, so a caller resetting the state would
    get a fresh parameter-store client and a stale S3 client from the same call.

    Returns
    -------
    None
        Nothing; a locally constructed client is reported as an assertion failure.

    Raises
    ------
    None
    """
    from carddemo_migration import config

    called = _called_dotted_names(s3_stage)

    # WHY : Assumptions: the factory is named through the configuration module's PUBLIC surface.
    #   This module's suite already asserts that the private spelling the staging loader once
    #   reached for is gone, so a call to it resolves to nothing at run time -- an attribute error
    #   raised from a function no other test exercises, which is the shape of defect a call-graph
    #   assertion catches and an import check cannot.
    assert "config.aws_client" in called, (
        "the staging loader does not obtain its client from config.aws_client; the calls it makes"
        f" are {sorted(name for name in called if name.startswith('config.'))}"
    )
    assert hasattr(config, "aws_client")
    assert "aws_client" in config.__all__
    assert "config._aws_client" not in called

    # WHY : Assumptions: every configuration name the staging loader calls is required to EXIST,
    #   which is the invariant a cross-module private reach actually breaks. The loader does still
    #   reach one private helper -- the service-error-code reader its conditional-claim path
    #   needs --
    #   and that reach is a design cost rather than a defect, because the name is there. Demanding
    #   that no private name be called at all would be asserting an API change this module is not
    #   the place to make; demanding that every called name resolve catches the failure the client
    #   factory actually suffered, where the callee was renamed and the caller was not.
    for name in sorted(called):
        if not name.startswith("config."):
            continue
        attribute = name.split(".", 1)[1]
        assert hasattr(config, attribute), (
            f"the staging loader calls {name}, which the configuration module no longer provides;"
            " a renamed callee leaves this caller raising AttributeError on its first real use"
        )


def test_the_staging_module_constructs_no_literal_endpoint(
    staging_settings: DatasetStagingSettings,
) -> None:
    """Assert no endpoint, region, credential or account identifier is written into the loader.

    Purpose
    -------
    Establish why this suite needs no emulator. The SDK resolves the region, the credentials and any
    endpoint override from the ambient environment, so one code path serves the batch staging task
    and a local emulator alike -- and a literal endpoint, or a flag deciding whether to pass one,
    would create a second path that only one environment ever exercises.

    Parameters
    ----------
    staging_settings : DatasetStagingSettings
        Validated staging settings, asserted to carry a synthetic bucket name and no credential.

    Returns
    -------
    None
        Nothing; a literal endpoint, a hard-coded host or a credential-shaped literal is reported as
        an assertion failure.

    Raises
    ------
    None
    """
    assert "endpoint_url" not in _keyword_argument_names(s3_stage)
    assert "endpoint_url" not in _keyword_argument_names(aurora)

    # WHY : Assumptions: the literal scan skips docstrings, and here that is the difference between
    #   a meaningful check and a guaranteed failure: the staging module's own rationale explains at
    #   length that it passes no ``endpoint_url``, and the phrase appears in that explanation.
    for module in (aurora, s3_stage):
        for literal in _executable_string_literals(module):
            assert "amazonaws.com" not in literal
            assert "://" not in literal
            assert not literal.startswith("AKIA")
            assert "AWS_ACCESS_KEY" not in literal
            assert "AWS_SECRET" not in literal

    # WHY : Assumptions: the bucket is a PARAMETER carried on validated settings rather than a
    #   literal in the loader, which is what lets one image stage into a development bucket and a
    #   production one without a code change. The fixture's own value is asserted synthetic so that
    #   nothing in this module could be mistaken for a deployed resource name.
    assert "synthetic" in staging_settings.bucket
    assert staging_settings.environment == "test"
    assert not any(
        staging_settings.bucket in literal for literal in _executable_string_literals(s3_stage)
    )


def test_only_the_aurora_loader_reaches_a_third_party_service_client() -> None:
    """Assert the database driver and the AWS SDK are confined to the modules entitled to them.

    Purpose
    -------
    Establish the layering that keeps a codec-only process free of service dependencies. The bulk
    loader is the one module permitted the database driver, and it defers that import into the
    single function that needs it; the staging loader is permitted the SDK and does not import it at
    all, taking its client from the configuration module instead.

    Returns
    -------
    None
        Nothing; a service client imported by a module not entitled to it is reported as an
        assertion failure.

    Raises
    ------
    None
    """
    aurora_imports = _imported_module_names(aurora)
    staging_imports = _imported_module_names(s3_stage)

    assert aurora_imports & _SERVICE_CLIENT_MODULES == {"psycopg"}
    # WHY : Assumptions: the staging loader importing NO AWS SDK is the property that makes the
    #   in-process double substitutable with nothing mocked, patched or redirected. It takes its
    #   client as an argument and obtains the default from configuration, so a test supplies its own
    #   object at a seam that already exists rather than intercepting a constructor.
    assert staging_imports & _SERVICE_CLIENT_MODULES == set()

    # WHY : Assumptions: the driver import is asserted to be DEFERRED inside a function rather than
    #   merely present. A module-scope import would make importing the loader fail on a host without
    #   the driver, and the codec tests in this suite deliberately run in exactly that condition.
    module_scope = {
        alias.name.split(".")[0]
        for node in _module_tree(aurora.__file__).body
        if isinstance(node, ast.Import)
        for alias in node.names
    }
    assert "psycopg" not in module_scope


def test_a_record_layout_import_pulls_in_no_service_client() -> None:
    """Assert importing the copybook and reader layers loads neither the driver nor the SDK.

    Purpose
    -------
    Establish the layering claim as a runtime fact rather than a source-level one. A codec-only
    consumer imports the layouts and a reader with no credentials configured and no database
    reachable, so a client constructed anywhere along that import chain would raise.

    Returns
    -------
    None
        Nothing; a service client present in the child interpreter's module table is reported as an
        assertion failure naming it.

    Raises
    ------
    None
    """
    # WHY : Alternatives Considered: the check runs in a CHILD interpreter rather than inspecting
    #   this process's module table. In-process inspection was written first and is worthless here:
    #   this module imports both loaders at its top, so ``psycopg`` and the SDK may already be in
    #   ``sys.modules`` before the assertion runs, and the test would pass or fail according to
    #   which other test module pytest happened to collect first. A fresh interpreter is the only
    #   state in which the question has one answer.
    probe = (
        "import sys;"
        "import carddemo_migration.copybook.layouts;"
        "import carddemo_migration.readers.account;"
        "import carddemo_migration.readers.xref;"
        "print(','.join(sorted(m for m in ('psycopg', 'boto3', 'botocore') if m in sys.modules)))"
    )
    completed = subprocess.run(  # noqa: S603 -- fixed argument vector, no shell, no user input
        [sys.executable, "-c", probe],
        capture_output=True,
        text=True,
        check=False,
    )

    assert completed.returncode == 0, (
        f"the layering probe failed to import the codec layer: {completed.stderr.strip()}"
    )
    leaked = completed.stdout.strip()
    assert leaked == "", f"importing the codec layer pulled in {leaked}"
    # WHY : Assumptions: the readers named in the probe are the ones this module actually loads
    #   records through, so the probe covers the path these tests take rather than an arbitrary
    #   sample of the package.
    assert xref.__name__.endswith("readers.xref")
    assert account.__name__.endswith("readers.account")


def test_connection_settings_never_render_their_credential(
    fake_aurora: FakeAuroraDatabase,
) -> None:
    """Assert a settings object masks its password wherever it is rendered.

    Purpose
    -------
    Establish that a failure message cannot leak a credential. The bulk loader renders the settings
    object -- not the parameter mapping -- when a connection attempt fails, and that is only safe
    because the object's representation withholds the password.

    Parameters
    ----------
    fake_aurora : FakeAuroraDatabase
        The database double, used to confirm that the parameters it records are masked too, so the
        recording that ends up in assertion output cannot carry the value either.

    Returns
    -------
    None
        Nothing; a credential appearing in any rendering is reported as an assertion failure.

    Raises
    ------
    None
    """
    settings = AuroraConnectionSettings(
        host="aurora.carddemo.invalid",
        port=5432,
        database="carddemo",
        user=SCHEMA_ROLES["ledger"],
        password=SYNTHETIC_PASSWORD_FILL,
        ssl_root_cert="/nonexistent/synthetic-test-anchor.pem",
    )

    assert SYNTHETIC_PASSWORD_FILL not in repr(settings)
    assert SYNTHETIC_PASSWORD_FILL not in str(settings)
    assert REDACTED in repr(settings)
    # WHY : Assumptions: the credential is asserted to be RETRIEVABLE from the attribute while
    #   absent from every rendering. The masking is a presentation guarantee, not encryption -- the
    #   connection needs the real value -- so a test that found the attribute masked as well would
    #   be describing a settings object that could not open a connection.
    assert settings.password == SYNTHETIC_PASSWORD_FILL
    assert settings.as_connection_params()["password"] == SYNTHETIC_PASSWORD_FILL

    fake_aurora.connect(**settings.as_connection_params())
    recorded = fake_aurora.connection_params[-1]
    assert recorded["password"] == REDACTED
    assert SYNTHETIC_PASSWORD_FILL not in repr(recorded)
