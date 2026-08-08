"""Verify the seed-dataset registry and its agreement with the orchestrator definition."""

from __future__ import annotations

import re
from pathlib import Path

import pytest

from carddemo_migration import seed_datasets
from carddemo_migration.copybook import layouts
from carddemo_migration.loaders import s3_stage
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
    """Refuse an unregistered token and list the legal ones in the refusal."""
    with pytest.raises(SeedDatasetError) as raised:
        seed_datasets.seed_dataset("ACCOUNT")
    message = str(raised.value)
    # WHY : Assumptions: the probe is "ACCOUNT" -- a copybook LAYOUT name -- because that is the
    #   vocabulary this command line previously accepted, so it is the mistake a reader of the
    #   old contract will actually make. The refusal must therefore list the tokens rather than
    #   simply saying no.
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
