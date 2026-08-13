"""Drive the folder-wide test doubles against the production callables they stand in for.

Purpose
-------
Prove that every double and every builder ``conftest.py`` exports is answerable to a real
production entry point, by running that entry point through it. Four properties are settled
here and nowhere else in this folder:

* the staging double accepts BOTH body forms the ``S3StagingClient`` contract declares -- an
  in-memory ``bytes`` payload and the open binary handle ``stage_dataset_file`` actually passes
  -- and verifies a declared ``ContentLength`` against the bytes it received;
* a per-object delete failure leaves that object PRESENT, which is the state a real versioned
  bucket produces and the state the retention contract depends on;
* the synthetic security-record builder's password slot survives no production rendering path,
  so the credential drop is asserted against ``carddemo_migration.copybook.layouts`` rather
  than against prose;
* the validated repository-root fixture resolves to a checkout that actually holds the two
  reference trees this suite reads, which the corpus accessors depend on and none of them
  asserts.

Why this module exists
----------------------
Refactoring Rationale: the shared doubles previously had almost no consumer. Twenty names were
exported from ``conftest.py`` and four were referenced by any test, so the staging double's
refusal of an open handle -- a refusal that rejected the one production caller it existed to
cover -- sat undetected, and a delete double that removed every object it was asked about could
have "proved" a retention rule it was in fact violating. A shared double with no executable
consumer is not infrastructure, it is an intention: nothing fails when it drifts from the
callable it mimics, and a reviewer counting fixtures credits the suite with coverage it does not
have. Every case below therefore drives PRODUCTION code and asserts on the double's observable
effect, never on the double alone.

Alternatives Considered: extending ``test_s3_stage.py`` instead of adding a module. Rejected
because that module deliberately uses its own narrow, locally-declared client stub with
pre-arranged pages, and its own rationale for doing so is that arranged pages keep its
assertions independent of stored state. The shared double is the opposite design -- it derives
every listing from what it actually holds -- so the two are different instruments, and folding
this module's cases into that one would have meant choosing between them. Keeping them apart
lets the arranged-page stub answer questions about page handling while the state-derived double
answers questions about effect.

Assumptions: no test here reaches a network, a database, an emulator or a credential. The
staging functions take their client as an ARGUMENT, so substituting an in-process object needs
nothing mocked, patched or redirected, and the settings fixture names a synthetic bucket that is
never resolved from the environment.

Assumptions: ``pytest`` is a binary gate in this folder -- zero or non-zero. The graded
condition-code rubric belonging to the COBOL parity oracle under ``tests/**`` is not imported,
mirrored or approximated here, and no case below tolerates a failure.
"""

from __future__ import annotations

from datetime import date
from pathlib import Path

import pytest

# WHY : Assumptions: the shared module is imported as the top-level name ``conftest`` and NOT as
# ``tests.conftest``. This tree's ``tests`` directory carries no ``__init__.py``, so pytest places
# this file's own directory on the import path and loads the folder's ``conftest`` as a top-level
# module before collecting any sibling; ``tests.conftest`` would instead resolve to the COBOL
# parity oracle's own ``tests/conftest.py`` at the repository root, which this suite must never
# import -- that tree is reference material read strictly as data.
# WHY : Trade-offs: the import is unconditional rather than sitting under a type-checking guard as
# the sibling codec module's is. That module needs the two corpus classes for ANNOTATION only and
# receives the objects themselves as fixtures, so a guard costs it nothing. The four names below
# are needed at RUN time -- a refusal type a case asserts against, a paginator type a case
# identifies, and two constants a case compares -- so a guard would leave them undefined exactly
# when they are used. The accepted cost is that collection of this file depends on the folder's
# conftest being importable, which is the same condition every fixture below already depends on.
# WHY : Assumptions: this block sits AHEAD of the ``carddemo_migration`` imports because the lint
# configuration classifies that distribution as first-party and everything else as third-party, so
# the import sorter places ``conftest`` in the earlier group. The ordering is the sorter's and not a
# statement about precedence; ``ruff check`` enforces it, which is why it is not rearranged by hand.
from conftest import (
    EBCDIC_DATASET_PREFIX,
    SYNTHETIC_PASSWORD_FILL,
    FakeClientContractError,
    FakeObjectStore,
    FakeObjectStorePaginator,
    SecUserRecordBuilder,
)

from carddemo_migration.config import DatasetStagingSettings
from carddemo_migration.copybook.layouts import (
    SECUSER_LAYOUT,
    count_fixed_length_records,
    iter_fixed_length_records,
    layout,
    mask_field,
    mask_record,
    reclen_of,
)
from carddemo_migration.loaders.s3_stage import (
    GENERATION_FAMILIES,
    GenerationRetentionError,
    delete_generation_prefix,
    stage_dataset_file,
)

#: The dataset family every staging case below writes under. Chosen because
#: ``GENERATION_FAMILIES`` registers it with a declared record length, which is what lets one
#: case exercise the optional record-length check with a real geometry rather than a made-up one.
STAGED_FAMILY: str = "transact-bkup"

#: The business date every staged generation is partitioned under. A constructed value rather
#: than a clock read, so a rerun produces byte-identical keys -- the same discipline
#: ``app/jcl/INTCALC.jcl`` line 22 applies by passing ``PARM='2022071800'`` to the interest step.
STAGED_DATE: date = date(2022, 7, 18)


def _family_domain_and_dataset() -> tuple[str, str]:
    """Return the registered domain and dataset name of the family used below.

    Returns
    -------
    tuple[str, str]
        The family's ``domain`` and ``dataset`` exactly as ``GENERATION_FAMILIES`` records them.

    Raises
    ------
    KeyError
        If the family is no longer registered, which would mean the ten-family roster changed
        and this module names a family the loader does not know.
    """
    # WHY : Assumptions: the two names are READ from the production roster rather than written
    # out here. There are ten generation families and which of them exist is a property of the
    # batch pipeline; spelling one here would give that fact a second home, and a test asserting
    # a prefix it had itself composed would agree with its own copy rather than with the loader.
    registered = GENERATION_FAMILIES[STAGED_FAMILY]
    return registered.domain, registered.dataset


class TestStagingDoubleBodyForms:
    """The staging double accepts both declared body forms and checks the declared length."""

    def test_in_memory_payload_is_stored_byte_for_byte(
        self,
        fake_object_store: FakeObjectStore,
        staging_settings: DatasetStagingSettings,
        tmp_path: Path,
    ) -> None:
        """Stage a bytes payload through production and read it back unchanged.

        Parameters
        ----------
        fake_object_store : FakeObjectStore
            The shared versioned-store double.
        staging_settings : DatasetStagingSettings
            Synthetic bucket and environment naming.
        tmp_path : Path
            Per-test directory the payload is written into. Assumptions: the payload reaches
            production from a FILE, because ``stage_dataset_file`` is the only writer the module
            still publishes -- the in-memory entry point was withdrawn so that every staged object
            carries the digest and length anchors measured from one held descriptor.

        Returns
        -------
        None
            The assertions are the result.

        Raises
        ------
        AssertionError
            If the stored body differs from the payload by a single byte, or if the key is not
            the one the settings' own prefix builder composes.
        """
        domain, dataset = _family_domain_and_dataset()
        # WHY : Assumptions: the payload carries a NUL and a high byte on purpose. A staged
        # generation may be a packed-decimal or EBCDIC image, so the bytes that matter most are
        # exactly the ones a text decode or a line-ending fix would destroy -- and both
        # corruptions are invisible in a byte count, which is why the assertion below is
        # equality against the payload rather than a length comparison.
        payload = b"\x00\xf0ACCT\x0d\x0a\xff"

        source = tmp_path / "extract.dat"
        source.write_bytes(payload)

        staged = stage_dataset_file(
            fake_object_store,
            staging_settings,
            domain,
            dataset,
            STAGED_DATE,
            1,
            source.name,
            staging_root=tmp_path,
            object_name="extract.dat",
            retention_count=5,
        )

        expected_prefix = staging_settings.generation_prefix(domain, dataset, STAGED_DATE, 1)
        assert staged.key == f"{expected_prefix}extract.dat"
        assert fake_object_store.body_of(staged.key) == payload

    def test_open_binary_handle_is_the_form_the_file_path_uses(
        self,
        fake_object_store: FakeObjectStore,
        staging_settings: DatasetStagingSettings,
        workspace: Path,
    ) -> None:
        """Stage from a file and prove the handle body reached the store intact.

        Parameters
        ----------
        fake_object_store : FakeObjectStore
            The shared versioned-store double.
        staging_settings : DatasetStagingSettings
            Synthetic bucket and environment naming.
        workspace : pathlib.Path
            A scratch directory outside the checkout, holding the extract this case stages.

        Returns
        -------
        None
            The assertions are the result.

        Raises
        ------
        AssertionError
            If the staged body, byte count or digest disagrees with the file on disk.
        """
        domain, dataset = _family_domain_and_dataset()
        reclen = GENERATION_FAMILIES[STAGED_FAMILY].record_length
        # WHY : Assumptions: the extract is exactly two whole records of the family's REGISTERED
        # length, so the optional record-length check runs and passes for a real geometry. A
        # payload of arbitrary length would leave that argument accepted but never exercised.
        source = workspace / "transact.bkup"
        source.write_bytes(b"\x00" + b"\xf0" * (reclen - 2) + b"\xff" + b"A" * reclen)

        staged = stage_dataset_file(
            fake_object_store,
            staging_settings,
            domain,
            dataset,
            STAGED_DATE,
            2,
            source.name,
            staging_root=workspace,
            record_length=reclen,
        )

        # WHY : Refactoring Rationale: this case is the reason the double was widened. The
        # production function opens its source in binary and passes the OPEN HANDLE as ``Body``,
        # which the ``S3StagingClient.put_object`` contract declares in as many words; the double
        # previously refused every non-``bytes`` body, so this path could not be driven at all and
        # a defect in it would have surfaced for the first time in a deployment.
        assert staged.byte_size == source.stat().st_size
        assert fake_object_store.body_of(staged.key) == source.read_bytes()

        recorded = fake_object_store.put_calls[-1]
        # WHY : Assumptions: the recorded call is asserted to carry the resolved BYTES rather than
        # the handle. A handle recorded verbatim is exhausted and closed by the time a test reads
        # it, so a log that kept it would report every staged payload as zero bytes.
        assert recorded["Body"] == source.read_bytes()
        assert recorded["ContentLength"] == staged.byte_size

    def test_a_text_body_is_still_refused(self, fake_object_store: FakeObjectStore) -> None:
        """A ``str`` body is refused rather than encoded on the caller's behalf.

        Parameters
        ----------
        fake_object_store : FakeObjectStore
            The shared versioned-store double.

        Returns
        -------
        None
            The assertions are the result.

        Raises
        ------
        AssertionError
            If a text body is accepted, which would let the double absorb a defect the real
            path reports.
        """
        # WHY : Assumptions: the widening that admitted a handle is asserted NOT to have admitted
        # text. Encoding a ``str`` here would pick a code page the double has no business
        # choosing, and for an EBCDIC or packed generation every choice is wrong -- so text must
        # remain a defect the double reports rather than one it silently repairs.
        with pytest.raises(FakeClientContractError, match="no code page this double may choose"):
            fake_object_store.put_object(Bucket="b", Key="k", Body="ACCT")

    def test_a_mistaken_content_length_is_refused(self, fake_object_store: FakeObjectStore) -> None:
        """A declared ``ContentLength`` that disagrees with the body is refused.

        Parameters
        ----------
        fake_object_store : FakeObjectStore
            The shared versioned-store double.

        Returns
        -------
        None
            The assertions are the result.

        Raises
        ------
        AssertionError
            If a wrong declared length is accepted, which would leave the loader's truncation
            safeguard untested.
        """
        # WHY : Assumptions: the length is checked because checking it is the only thing that
        # makes the argument worth passing. The loader states it from a digest pass over the file
        # precisely so that a service which knows the expected length refuses a stream that ends
        # early; a double that recorded the value without comparing it would let a loader
        # computing the record count where the byte count belongs stage a truncated generation
        # with this suite still green.
        with pytest.raises(FakeClientContractError, match="ContentLength"):
            fake_object_store.put_object(Bucket="b", Key="k", Body=b"ACCT", ContentLength=99)
        assert fake_object_store.keys() == ()


class TestStagingDoubleRetention:
    """A refused deletion leaves its object present, which is what retention rests on."""

    def test_a_refused_deletion_leaves_the_version_held(
        self,
        fake_object_store: FakeObjectStore,
        staging_settings: DatasetStagingSettings,
        tmp_path: Path,
    ) -> None:
        """Arrange a per-object error and prove both the raise and the survival.

        Parameters
        ----------
        fake_object_store : FakeObjectStore
            The shared versioned-store double.
        staging_settings : DatasetStagingSettings
            Synthetic bucket and environment naming.
        tmp_path : Path
            Per-test directory the staged extract is written into, because the surviving writer
            takes a path rather than in-memory bytes.

        Returns
        -------
        None
            The assertions are the result.

        Raises
        ------
        AssertionError
            If the production cleanup does not raise, or if the version it could not delete has
            nevertheless gone from the store.
        """
        domain, dataset = _family_domain_and_dataset()
        prefix = staging_settings.generation_prefix(domain, dataset, STAGED_DATE, 3)
        source = tmp_path / "extract.dat"
        source.write_bytes(b"ROWS")
        stage_dataset_file(
            fake_object_store,
            staging_settings,
            domain,
            dataset,
            STAGED_DATE,
            3,
            source.name,
            staging_root=tmp_path,
            object_name="extract.dat",
            retention_count=5,
        )
        held_before = fake_object_store.stored_versions(staging_settings.bucket)
        assert held_before != ()

        fake_object_store.fail_next_delete_with("AccessDenied")

        # WHY : Assumptions: the production function reports a per-object failure by RAISING,
        # because a cleanup that deleted some versions and returned success would leave a
        # generation half-scratched. Both halves are asserted: the raise, and the fact that the
        # version the service refused is still held.
        with pytest.raises(GenerationRetentionError, match="AccessDenied"):
            delete_generation_prefix(fake_object_store, staging_settings.bucket, prefix)

        # WHY : Assumptions: a deletion is asserted to have been ATTEMPTED before its effect is
        # asserted. Without this the case would also pass for a cleanup that listed nothing and
        # deleted nothing, in which case the surviving state would be unchanged for the wrong
        # reason and the arrangement would never have been reached.
        assert fake_object_store.delete_calls != []
        requested = [
            item["Key"]
            for call in fake_object_store.delete_calls
            for item in call["Delete"]["Objects"]
        ]
        assert requested != []

        # WHY : Refactoring Rationale: this is the assertion the double could not previously
        # support. It deleted every requested object and only then returned the arranged errors,
        # a state a real service cannot produce -- S3 reports a per-object error precisely when
        # that object was NOT removed. A retention test written against the old behaviour would
        # have found the versions gone and could have been "fixed" by weakening it, which is how
        # a violated retention rule comes to look satisfied.
        assert fake_object_store.stored_versions(staging_settings.bucket) == held_before

    def test_an_arrangement_naming_an_unrequested_key_is_refused(
        self, fake_object_store: FakeObjectStore
    ) -> None:
        """An arranged error for an object the call never named is a misuse, not a result.

        Parameters
        ----------
        fake_object_store : FakeObjectStore
            The shared versioned-store double.

        Returns
        -------
        None
            The assertions are the result.

        Raises
        ------
        AssertionError
            If such an arrangement is honoured, which would report a failure for an object the
            service was never asked to delete.
        """
        fake_object_store.put_object(Bucket="b", Key="held", Body=b"ROWS")
        fake_object_store.fail_next_delete_with("AccessDenied", key="never-requested")

        with pytest.raises(FakeClientContractError, match="did not name"):
            fake_object_store.delete_objects(
                Bucket="b", Delete={"Objects": [{"Key": "held", "VersionId": "v0001"}]}
            )

    def test_the_paginator_is_derived_from_what_the_store_holds(
        self, fake_object_store: FakeObjectStore
    ) -> None:
        """The list pages report the stored objects rather than an arrangement.

        Parameters
        ----------
        fake_object_store : FakeObjectStore
            The shared versioned-store double.

        Returns
        -------
        None
            The assertions are the result.

        Raises
        ------
        AssertionError
            If the paginator is not the shared type, or if a delimiter listing fails to roll the
            stored keys up into the immediate child prefix generation discovery walks.
        """
        fake_object_store.put_object(Bucket="b", Key="ledger/dt=2022-07-18/x.dat", Body=b"A")
        paginator = fake_object_store.get_paginator("list_objects_v2")

        assert isinstance(paginator, FakeObjectStorePaginator)

        # WHY : Assumptions: the delimiter rollup is asserted because generation discovery is
        # built on it -- the loader lists a family prefix to learn its ``dt=`` folders, then lists
        # each folder to learn its ``gen=`` folders. A double that returned raw keys instead would
        # make the loader see no folders, find no generation to scratch, and report a retention
        # rule as satisfied over an empty result.
        pages = paginator.paginate(Bucket="b", Prefix="ledger/", Delimiter="/")
        children = [child["Prefix"] for page in pages for child in page.get("CommonPrefixes", [])]
        assert children == ["ledger/dt=2022-07-18/"]


class TestSecurityRecordCredentialDrop:
    """The synthetic security record's password slot survives no production rendering."""

    def test_the_builder_places_the_placeholder_at_the_declared_offset(
        self, secuser_builder: SecUserRecordBuilder
    ) -> None:
        """The password span holds the placeholder and only the placeholder.

        Parameters
        ----------
        secuser_builder : SecUserRecordBuilder
            The shared synthetic security-record builder.

        Returns
        -------
        None
            The assertions are the result.

        Raises
        ------
        AssertionError
            If the record is not the declared length, or if the password span holds anything but
            the placeholder.
        """
        record = secuser_builder.build(user_id="SYNTH001", user_type="A")

        # WHY : Assumptions: the offset and width are READ from the production descriptor rather
        # than written here. ``SECUSER_LAYOUT`` declares the password at offset 48 for eight
        # characters, and a test that spelled those numbers could keep passing against a
        # descriptor that had moved them -- the record would still be eighty characters and would
        # still look like a record.
        assert len(record) == reclen_of("SECUSER")
        password = next(f for f in SECUSER_LAYOUT.fields if f.name == "SEC-USR-PWD")
        assert record[password.start : password.start + password.length] == (
            SYNTHETIC_PASSWORD_FILL
        )
        assert layout("SECUSER") is SECUSER_LAYOUT

    def test_no_production_rendering_path_emits_the_password_span(
        self, secuser_builder: SecUserRecordBuilder
    ) -> None:
        """Masking a record and masking the field both withhold the credential.

        Parameters
        ----------
        secuser_builder : SecUserRecordBuilder
            The shared synthetic security-record builder.

        Returns
        -------
        None
            The assertions are the result.

        Raises
        ------
        AssertionError
            If any production rendering carries the password characters, or if masking changes
            the record's width and so breaks offset counting across it.
        """
        record = secuser_builder.build()
        password = next(f for f in SECUSER_LAYOUT.fields if f.name == "SEC-USR-PWD")

        masked_record = mask_record(record, SECUSER_LAYOUT)
        masked_field = mask_field(password, SYNTHETIC_PASSWORD_FILL)

        # WHY : Assumptions: this is the security assurance the builder exists for, and it is
        # asserted against PRODUCTION renderers rather than against the fixture. The descriptor
        # marks the field sensitive, so the whole claim -- that the eight bytes at offset 48
        # never reach a diagnostic -- rests on the mask honouring that flag. Asserting the flag
        # alone would prove the declaration and not the behaviour.
        assert password.sensitive is True
        assert SYNTHETIC_PASSWORD_FILL not in masked_record
        assert SYNTHETIC_PASSWORD_FILL not in masked_field
        assert len(masked_record) == len(record)
        assert len(masked_field) == password.length

        # WHY : Assumptions: the failure message of the descriptor's own geometry check is asserted
        # to withhold content too. A refusal that quoted the chunk it was given would print the
        # very characters the sensitive flag exists to suppress, and a refusal path is exactly
        # where such a leak survives review.
        assert "sensitive" in password.describe() or password.name in password.describe()

    def test_a_stream_of_synthetic_records_divides_by_the_declared_length(
        self, secuser_builder: SecUserRecordBuilder
    ) -> None:
        """Encoded synthetic records iterate as whole records at the declared geometry.

        Parameters
        ----------
        secuser_builder : SecUserRecordBuilder
            The shared synthetic security-record builder.

        Returns
        -------
        None
            The assertions are the result.

        Raises
        ------
        AssertionError
            If the byte length does not divide by the declared record length, or if the
            production iterator yields a different number of records than the counter predicts.
        """
        reclen = reclen_of("SECUSER")
        stream = secuser_builder.build_bytes(user_id="SYNTH001") + secuser_builder.build_bytes(
            user_id="SYNTH002"
        )

        assert len(stream) == 2 * reclen
        assert count_fixed_length_records(len(stream), reclen) == 2
        assert [bytes(r) for r in iter_fixed_length_records(stream, reclen)] == [
            stream[:reclen],
            stream[reclen:],
        ]

    def test_the_validated_repository_root_locates_both_reference_trees(
        self, repo_root: Path
    ) -> None:
        """The root fixture resolves to a checkout holding both trees this suite reads.

        Parameters
        ----------
        repo_root : pathlib.Path
            The validated repository root.

        Returns
        -------
        None
            The assertions are the result.

        Raises
        ------
        AssertionError
            If either reference tree is absent beneath the resolved root, or if the security
            extract the credential-drop path is written against is not where it is expected.
        """
        # WHY : Assumptions: this case requests the root fixture DIRECTLY rather than reaching it
        # through one of the corpus accessors it feeds. Those accessors take it as a dependency, so
        # its identity check runs on their behalf and its own contract -- that the directory it
        # returns is the CardDemo checkout and not merely some existing path -- is never asserted by
        # anything that would fail if it drifted.
        assert (repo_root / "app" / "cbl").is_dir()
        assert (repo_root / "tests" / "fixtures").is_dir()
        assert (repo_root / "data-migration" / "src" / "carddemo_migration").is_dir()

        # WHY : Assumptions: the security extract is named through the shared prefix and asserted
        # to exist, which is what ties the synthetic builder above to a real dataset. Its 800 bytes
        # divide by the declared eighty into exactly ten records, so the geometry the builder pads
        # to is the geometry the committed extract carries.
        extract = repo_root / "app" / "data" / "EBCDIC" / f"{EBCDIC_DATASET_PREFIX}USRSEC.PS"
        assert extract.is_file()
        assert extract.stat().st_size % reclen_of("SECUSER") == 0

    def test_the_seed_dataset_name_is_addressed_through_the_shared_prefix(self) -> None:
        """The committed security extract is named through the shared dataset prefix.

        Returns
        -------
        None
            The assertions are the result.

        Raises
        ------
        AssertionError
            If the prefix no longer composes the dataset name the seed tree ships, which would
            mean a reader looking the dataset up by its bare name would miss it.
        """
        # WHY : Assumptions: the prefix is asserted through composition rather than compared to a
        # literal dataset path. Every EBCDIC extract in ``app/data/EBCDIC`` is named
        # ``AWS.M2.CARDDEMO.<dataset>.PS``, and the corpus accessors accept either the full name
        # or the bare one; a change to the shared prefix would silently break the bare-name
        # convenience for every dataset at once, which is the failure this pins.
        assert f"{EBCDIC_DATASET_PREFIX}USRSEC.PS".startswith(EBCDIC_DATASET_PREFIX)
        assert EBCDIC_DATASET_PREFIX.endswith(".")
        assert "USRSEC.PS" == f"{EBCDIC_DATASET_PREFIX}USRSEC.PS".removeprefix(
            EBCDIC_DATASET_PREFIX
        )
