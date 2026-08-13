"""Verify the seed-dataset registry and its agreement with the orchestrator definition."""

from __future__ import annotations

import re
from pathlib import Path

import pytest

from carddemo_migration import seed_datasets
from carddemo_migration.copybook import layouts
from carddemo_migration.loaders import aurora, s3_stage
from carddemo_migration.seed_datasets import SeedDatasetError

#: Repository root, reached from this file rather than from the working directory.
#: WHY : Assumptions: the path is derived from ``__file__`` because these tests read
#: repository artifacts -- a Terraform variable file and the baseline extracts -- that are not
#: on any import path. Deriving from the working directory was the alternative and was rejected
#: because it makes the tests pass or fail depending on where pytest was invoked from.
_REPOSITORY_ROOT = Path(__file__).resolve().parents[2]

#: The Terraform variable whose value set must equal the registry's tokens.
_SEED_DATASETS_VARIABLE = (
    _REPOSITORY_ROOT / "infra" / "modules" / "step-functions-batch" / "variables.tf"
)


def test_every_descriptor_names_a_registered_layout() -> None:
    """Bind every seed dataset to a layout the copybook registry actually declares."""
    registered = set(layouts.names())
    for token in seed_datasets.seed_dataset_tokens():
        descriptor = seed_datasets.seed_dataset(token)
        assert descriptor.layout_name in registered


def test_record_length_is_derived_from_the_layout_registry() -> None:
    """Read each record length from the layout rather than from a second copy of it."""
    for token in seed_datasets.seed_dataset_tokens():
        descriptor = seed_datasets.seed_dataset(token)
        # WHY : Assumptions: this asserts DERIVATION, not a list of expected widths. Pinning the
        #   numbers here would create exactly the second spelling the registry avoids, and the
        #   test would then keep passing after a copybook correction while the staged geometry
        #   check used the stale width.
        assert (
            seed_datasets.record_length(descriptor) == layouts.layout(descriptor.layout_name).reclen
        )


def test_every_registered_source_object_exists_and_divides_exactly() -> None:
    """Confirm each descriptor names a real baseline extract of whole records."""
    extracts = _REPOSITORY_ROOT / "app" / "data" / "EBCDIC"
    for token in seed_datasets.seed_dataset_tokens():
        descriptor = seed_datasets.seed_dataset(token)
        path = extracts / descriptor.source_object
        assert path.is_file(), f"{token} names {descriptor.source_object}, which does not exist"
        # WHY : Assumptions: the byte length is required to divide exactly by the declared record
        #   length. An EBCDIC extract is a fixed-length blocked image with no terminators, so a
        #   non-zero remainder means either the declared width is wrong or the file is truncated
        #   -- and in either case no field offset in this package can be trusted for it.
        assert path.stat().st_size % seed_datasets.record_length(descriptor) == 0


def test_the_transaction_master_is_seeded_from_the_baselines_own_choice() -> None:
    """Source the transaction master from the extract the baseline JCL REPROs it from."""
    descriptor = seed_datasets.seed_dataset("transactions")
    # WHY : Assumptions: this pins a substitution that looks wrong until traced, so a future
    #   reader does not "correct" it to a TRANSACT extract that does not exist. No TRANSACT
    #   extract ships -- the master is pipeline-produced -- and app/jcl/TRANFILE.jcl defines the
    #   TRANSACT KSDS and REPROs it from DALYTRAN.PS.INIT, so the baseline itself nominates this
    #   file. The assertion below reads that JCL rather than restating its conclusion.
    assert descriptor.source_object == "AWS.M2.CARDDEMO.DALYTRAN.PS.INIT"
    jcl = (_REPOSITORY_ROOT / "app" / "jcl" / "TRANFILE.jcl").read_text(encoding="utf-8")
    assert "AWS.M2.CARDDEMO.DALYTRAN.PS.INIT" in jcl
    assert "AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS" in jcl


def test_an_unknown_token_is_refused_and_names_every_legal_value() -> None:
    """Refuse a value neither vocabulary carries, and list every legal one in the refusal."""
    # WHY : ⚠️ Refactoring Rationale: the probe is a value NEITHER vocabulary carries, where it was
    #   the copybook layout name "ACCOUNT". A layout name is no longer unregistered: this registry
    #   now carries every seed dataset under its orchestration token AND its layout name, and it
    #   asserts at import that the two sets cannot collide -- every token is lower-case with
    #   underscores and every layout name upper-case -- so "ACCOUNT" resolves to the same descriptor
    #   "accounts" does. Probing it would now assert that a legal value is refused.
    # WHY : Assumptions: the refusal is required to name BOTH vocabularies rather than only the
    #   tokens. An operator who mistyped a token and saw only the layout names listed would
    #   reasonably conclude tokens are not accepted here, and rewrite a working orchestrator
    #   definition to match; the reverse mistake is equally available, which is why both sets are
    #   asserted rather than either.
    with pytest.raises(SeedDatasetError) as raised:
        seed_datasets.seed_dataset("not-a-registered-dataset")
    message = str(raised.value)
    for identifier in seed_datasets.dataset_identifiers():
        assert identifier in message
    for token in seed_datasets.seed_dataset_tokens():
        assert token in message


def test_the_registry_is_a_read_only_view() -> None:
    """Refuse mutation of the published registry mapping."""
    with pytest.raises(TypeError):
        seed_datasets.SEED_DATASETS["injected"] = seed_datasets.seed_dataset("accounts")  # type: ignore[index]


def test_retention_agrees_with_the_staging_loaders_default() -> None:
    """Keep one retention default across the registry and the staging loader."""
    # WHY : Assumptions: the registry deliberately does not import the staging loader -- that
    #   would couple a descriptor table to an S3 client module -- so the two constants are
    #   separate declarations. This test is what makes them one fact: both express the baseline's
    #   LIMIT(5), and every one of the ten generation bases is defined at LIMIT(5).
    assert seed_datasets.DEFAULT_GENERATION_RETENTION == s3_stage.DEFAULT_GENERATION_RETENTION
    for token in seed_datasets.seed_dataset_tokens():
        assert seed_datasets.seed_dataset(token).retention_limit == 5


def _terraform_seed_dataset_names() -> list[str]:
    """Read the orchestrator's seed-dataset default list out of its Terraform variable.

    Purpose
    -------
    Recover the orchestrator's own vocabulary from the authored HCL, so the agreement asserted
    below is measured against the deployed definition rather than against a copy of it.

    Returns
    -------
    list of str
        The names in the ``seed_datasets`` variable's ``default`` list, in declared order.

    Raises
    ------
    AssertionError
        If the variable or its default list cannot be located, because a silent empty result
        would make the agreement test vacuous.
    """
    text = _SEED_DATASETS_VARIABLE.read_text(encoding="utf-8")
    block = re.search(r'variable\s+"seed_datasets"\s*\{(.*?)\n\}', text, re.DOTALL)
    assert block is not None, "the seed_datasets variable could not be located"
    default = re.search(r"default\s*=\s*\[(.*?)\]", block.group(1), re.DOTALL)
    assert default is not None, "the seed_datasets default list could not be located"
    names = re.findall(r'"([a-z_]+)"', default.group(1))
    assert names, "the seed_datasets default list parsed as empty"
    return names


def test_the_orchestrator_and_the_registry_share_one_vocabulary() -> None:
    """Require the Terraform seed-dataset list and the registry tokens to be the same set."""
    # WHY : Refactoring Rationale: this is the test that makes single-sourcing enforceable across
    #   two languages that share no build. The orchestrator's list and this package's registry are
    #   authored in HCL and Python respectively and can never import one another, so nothing but a
    #   test can hold them together. Before the registry existed the two disagreed completely --
    #   the orchestrator sent `accounts` while the command line accepted only `ACCOUNT` -- and
    #   every staging branch failed. Reading the HCL here means a name added on either side
    #   without the other fails the build rather than a nightly execution.
    terraform_names = _terraform_seed_dataset_names()
    registry_tokens = list(seed_datasets.seed_dataset_tokens())

    assert sorted(terraform_names) == sorted(registry_tokens), (
        "the orchestrator's seed_datasets default and carddemo_migration.seed_datasets must "
        "name the same datasets; only in Terraform: "
        f"{sorted(set(terraform_names) - set(registry_tokens))}; only in the registry: "
        f"{sorted(set(registry_tokens) - set(terraform_names))}"
    )
    # WHY : Assumptions: the count is asserted separately so a duplicated name on either side is
    #   caught. Set comparison alone would accept a list that named one dataset twice, which the
    #   Terraform variable's own validation block also forbids.
    assert len(terraform_names) == len(set(terraform_names)) == len(registry_tokens)


def test_every_orchestrator_name_resolves_to_a_complete_descriptor() -> None:
    """Resolve each orchestrator name to a descriptor with every staging value populated."""
    for name in _terraform_seed_dataset_names():
        descriptor = seed_datasets.seed_dataset(name)
        # WHY : Assumptions: each member is checked for being NON-EMPTY rather than for a
        #   specific value. The specific values are asserted by the tests above against the
        #   layout registry and the baseline extracts; what this adds is that resolving an
        #   orchestrator name yields nothing blank, because a blank domain or source object would
        #   compose a syntactically valid prefix that points nowhere.
        assert descriptor.token == name
        assert descriptor.domain
        assert descriptor.dataset_segment
        assert descriptor.source_object
        assert seed_datasets.record_length(descriptor) > 0


def test_every_registered_source_object_is_an_ebcdic_extract() -> None:
    """Assert the naming that lets one constant state the seed form for the whole registry.

    Purpose
    -------
    Pin the premise behind :data:`seed_datasets.SEED_SOURCE_ENCODING`. The seed data ships in two
    forms -- the fixed-length EBCDIC datasets named ``AWS.M2.CARDDEMO.*`` and the newline-delimited
    ASCII ``*.txt`` twins -- and only the first has sign overpunch and a fixed record stride. A
    descriptor edited onto an ASCII form would keep the EBCDIC default and decode text as packed
    bytes, which yields plausible wrong numbers rather than an error.

    Returns
    -------
    None
        Nothing; a descriptor naming anything but an EBCDIC extract is an assertion failure.

    Raises
    ------
    None
    """
    assert seed_datasets.SEED_SOURCE_ENCODING == "ebcdic"
    for token in seed_datasets.seed_dataset_tokens():
        descriptor = seed_datasets.seed_dataset(token)
        assert descriptor.source_object.startswith(seed_datasets.EBCDIC_SOURCE_PREFIX)
        # WHY : Assumptions: the file is asserted to EXIST in the baseline EBCDIC tree as well as
        #   to be named like one. The prefix alone would be satisfied by a plausible name for a
        #   dataset nobody ships, and the whole point of the registry is that a token resolves to
        #   an extract an operator can actually put where the task reads it.
        assert (_REPOSITORY_ROOT / "app" / "data" / "EBCDIC" / descriptor.source_object).is_file()


def test_a_token_and_a_layout_name_are_never_confusable() -> None:
    """Assert the disjointness that lets one selector accept either vocabulary.

    Purpose
    -------
    Pin the property the load and verification commands rely on when they accept ``--dataset`` as
    either an orchestrator token or a copybook layout name. If any value were in both registries
    the selector would be ambiguous, and the ambiguity would be resolved silently.

    Returns
    -------
    None
        Nothing; an overlapping name, or a predicate that admits the wrong vocabulary, fails.

    Raises
    ------
    None
    """
    tokens = set(seed_datasets.seed_dataset_tokens())
    names = set(layouts.names())
    assert not tokens & names

    for token in tokens:
        assert seed_datasets.is_seed_dataset_token(token)
    for name in names:
        assert not seed_datasets.is_seed_dataset_token(name)
    # WHY : Assumptions: the predicate is asserted to be exact rather than case-insensitive. A
    #   predicate that upper-cased its input would accept `ACCOUNTS`, which is neither a token
    #   nor a layout, and would then resolve it to a descriptor an operator never named.
    assert not seed_datasets.is_seed_dataset_token("ACCOUNTS")
    assert not seed_datasets.is_seed_dataset_token("")


_DATASET_FAMILIES_VARIABLE = _REPOSITORY_ROOT / "infra" / "modules" / "s3-datasets" / "variables.tf"


def test_every_backup_family_names_a_registered_seed_dataset() -> None:
    """Bind every backup family to a token the registry declares, keyed by that same token."""
    tokens = set(seed_datasets.seed_dataset_tokens())
    for key, family in seed_datasets.BACKUP_FAMILIES.items():
        assert key == family.token
        assert family.token in tokens


def test_every_backup_family_declares_the_record_length_its_extract_carries() -> None:
    """Hold each family's declared record length against the layout its extract is read at."""
    for family in seed_datasets.BACKUP_FAMILIES.values():
        descriptor = seed_datasets.seed_dataset(family.token)
        # WHY : Assumptions: this is the check the baseline's own DCB makes checkable. Each IEBGENER
        #   step declares the length it writes -- LRECL=60 for both reference tables at
        #   app/jcl/DEFGDGD.jcl:42 and :65, LRECL=50 for the disclosure groups at :88 -- and a copy
        #   is only verbatim if the bytes it copies are that length. A disagreement here means the
        #   family is bound to the wrong extract, which no key or byte-count assertion would catch.
        assert family.declared_record_length == seed_datasets.record_length(descriptor)


def test_every_backup_family_segment_is_a_provisioned_generation_family() -> None:
    """Assert each family segment is one the dataset bucket provisions a prefix and rule for."""
    declared = _DATASET_FAMILIES_VARIABLE.read_text(encoding="utf-8")
    for family in seed_datasets.BACKUP_FAMILIES.values():
        assert f'"{family.dataset_segment}" = {{' in declared
        # WHY : the DOMAIN is asserted too, because the prefix the module builds is
        #   "<domain>/<segment>/" -- a segment declared under another domain would give the module a
        #   lifecycle rule on one prefix while the staging step wrote to a different one, and both
        #   halves would look correct read on their own.
        assert f'domain      = "{family.domain}"' in declared


def test_the_three_bound_families_are_exactly_the_extract_seeded_ones() -> None:
    """Pin the bound set to the three the baseline creates from an extract, and to no others."""
    # WHY : Assumptions: this is the one place a literal list is right rather than derived. Every
    #   other assertion here checks the registry against something else; this checks the registry
    #   against the BASELINE, and the baseline is a fixed set of three IEBGENER steps in one job.
    #   Deriving it from the registry would make the test agree with whatever the registry said.
    assert set(seed_datasets.BACKUP_FAMILIES) == {
        "transaction_types",
        "transaction_categories",
        "disclosure_groups",
    }
    # WHY : the seven unbound tokens are asserted explicitly as unbound, because the failure this
    #   guards is a binding ADDED for a dataset the baseline does not copy -- which would write a
    #   generation the reference has no equivalent of and consume a retained slot for it.
    for token in seed_datasets.seed_dataset_tokens():
        if token in {"transaction_types", "transaction_categories", "disclosure_groups"}:
            assert seed_datasets.backup_family(token) is not None
        else:
            assert seed_datasets.backup_family(token) is None


def test_backup_family_reports_none_for_an_unregistered_token() -> None:
    """Report an unknown token as having no family rather than raising."""
    # WHY : Trade-offs: this lookup returns None where seed_dataset() raises, and the asymmetry is
    #   deliberate. The caller has already resolved a descriptor by the time it asks, so an unknown
    #   token here cannot happen through the production path; making it raise would add a second
    #   failure mode to a question whose answer "no family" is already meaningful.
    assert seed_datasets.backup_family("not-a-token") is None
    assert seed_datasets.backup_family("") is None


def test_a_layout_name_resolves_to_the_same_descriptor_as_its_token() -> None:
    """Accept either accepted spelling of a dataset and answer with one descriptor."""
    # WHY : Refactoring Rationale: the registry took only the ten lower-case tokens while
    #   `decode-record`, `load-dataset` and the three verification passes took the upper-case
    #   layout names. That split was not a documentation gap: the batch chain's `Map` state
    #   iterates ONE dataset list and hands the same `$.dataset` value to every branch, so a
    #   staging branch and a load branch could not be driven from the same list at all.
    # Assumptions: accepting both introduces no ambiguity, and that is measured rather than hoped
    #   -- every token is lower-case with underscores and every layout name is upper-case, so the
    #   two key sets are disjoint. The module asserts that disjointness at import; this asserts the
    #   consequence, that a value resolves to exactly one descriptor either way.
    for token in seed_datasets.seed_dataset_tokens():
        descriptor = seed_datasets.seed_dataset(token)
        assert seed_datasets.seed_dataset(descriptor.layout_name) is descriptor
        assert seed_datasets.layout_name_for(token) == descriptor.layout_name
        assert seed_datasets.layout_name_for(descriptor.layout_name) == descriptor.layout_name


def test_the_two_dataset_vocabularies_are_disjoint_and_both_published() -> None:
    """Publish one closed identifier list covering both spellings, with no overlap."""
    # WHY : Assumptions: the disjointness is asserted HERE as well as at import because the import
    #   check raises only when a collision already exists. This test states the property as a
    #   requirement, so a proposed registration that would collide is refused by a named failing
    #   test rather than by an import error in whatever command happened to run first.
    tokens = seed_datasets.seed_dataset_tokens()
    identifiers = seed_datasets.dataset_identifiers()
    layout_names = tuple(seed_datasets.seed_dataset(token).layout_name for token in tokens)
    assert not set(tokens) & set(layout_names)
    assert identifiers == (*tokens, *layout_names)
    assert len(set(identifiers)) == len(identifiers)


def test_every_declared_load_target_has_a_registered_seed_dataset() -> None:
    """Register one seed dataset for every table the loader declares a target for.

    Purpose
    -------
    Close the gap that made the combined verification gate unrunnable. Pass 3 totals nine money
    columns across five tables from the committed query, and it REFUSES the whole run when a column
    whose layout ships a committed extract contributes no source total -- so a declared load target
    with no registered seed dataset is not a smaller migration, it is a verification that cannot
    reach a verdict at all.

    Parameters
    ----------
    None
        Reads the loader's target registry and this module's own registry.

    Returns
    -------
    None
        Nothing; an unregistered target is reported as an assertion failure naming it.

    Raises
    ------
    None
    """
    # WHY : Assumptions: the direction asserted is targets-into-tokens and not the reverse. A layout
    #   with a seed dataset but no load target would be a dataset staged and never loaded, which is
    #   a different defect and one this registry cannot detect -- the loader owns that half. What
    #   this catches is the half that broke: `ledger.daily_transactions` was a declared target, its
    #   `amount` column was in the money query, `readers/dalytran.py` declared a committed extract,
    #   and no token existed to stage or load it.
    registered = {
        seed_datasets.seed_dataset(token).layout_name
        for token in seed_datasets.seed_dataset_tokens()
    }
    unregistered = sorted(set(aurora.target_names()) - registered)
    assert not unregistered, (
        "every declared Aurora load target needs a registered seed dataset or the combined"
        f" verification gate cannot cover it; unregistered: {unregistered}"
    )
