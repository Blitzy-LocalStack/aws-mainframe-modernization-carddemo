# =============================================================================
# Deterministic deployment packages for the operational Lambda functions
# =============================================================================
#
# Invocation:
#   python3 infra/lambda/build_packages.py [--check]
#
# WHY : Assumptions: no shebang and no execute bit. Every caller -- both pipeline
#       steps and the deploy runbook -- invokes this through the interpreter
#       explicitly, so a shebang would be decoration that the linter's EXE001 rule
#       correctly reports as inconsistent with the file's mode. Naming the
#       invocation above carries the same information without the inconsistency.
#
# Purpose:
#   Assemble one zip per operational Lambda function from the reviewed sources in
#   this directory, writing them to `dist/` for the environment roots to consume
#   through `aws_lambda_function.filename`.
#
# Provides:
#   Three packages, named for the functions that read them:
#     dist/online-write-flag.zip            online_write_flag.py
#     dist/database-admin.zip               database_admin.py plus the migration
#                                           DDL the handler applies
#     dist/dataset-generation-retention.zip dataset_generation_retention.py
#
# Errors:
#   - A missing source aborts with the path named, before any package is written,
#     so a partial `dist/` is never produced.
#   - A write failure surfaces as itself; the caller is a pipeline step that must
#     stop rather than plan against a stale package.
#
# WHY : ⚠️ Refactoring Rationale: these packages were built INSIDE Terraform, by
#       three `data "archive_file"` blocks, which required the root to declare
#       `hashicorp/archive`. That provider is not in the package's frozen
#       dependency inventory (AAP section 0.6.1.4 names exactly the Terraform CLI,
#       `hashicorp/aws` and `hashicorp/random`), and an in-file comment recording
#       the divergence does not amend the plan. Packaging here consumes no provider
#       at all: the standard library's zipfile module is the whole dependency, and
#       the roots consume the result through the AWS provider alone.
# WHY : Alternatives Considered: committing the three zips to the repository, which
#       would remove this script and the pipeline step that runs it. Rejected
#       because a committed archive hides reviewed source behind an opaque binary --
#       a reader can no longer see what the function runs, and a diff cannot show a
#       change to it. Also considered: shipping the functions as container images
#       through the ECR repositories that already exist. Rejected because it would
#       add repositories to an inventory AAP section 0.4.1.6 fixes at ten, and
#       because a 4 KB handler in an image is a container build per deploy for no
#       gain.
# WHY : Trade-offs: `terraform plan` now depends on a step that ran before it. A
#       plan produced without it fails while resolving `filebase64sha256` on an
#       absent path, which names the missing file -- an actionable failure rather
#       than a silent one -- and the pipeline steps and the deploy runbook each run
#       this script first.
# =============================================================================

from __future__ import annotations

import argparse
import sys
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPOSITORY_ROOT = HERE.parent.parent

# WHY : Assumptions: a FIXED timestamp, not the source file's mtime and not the
#       clock. Every function's `source_code_hash` is derived from these bytes, so a
#       timestamp that moved per build would change the hash on every run and make
#       `terraform plan` report a replacement of all four functions on a commit that
#       changed no handler. 1980-01-01 00:00:00 is the earliest instant the zip
#       format can represent, so it is the one value that cannot be mistaken for
#       real provenance.
FIXED_TIMESTAMP = (1980, 1, 1, 0, 0, 0)

# WHY : Assumptions: 0o644 for a regular file with no execute bit. Lambda imports the
#       handler as a module rather than executing the file, so an execute bit would
#       be capability the function does not use, and pinning the mode is part of what
#       makes the archive byte-identical across machines with different umasks.
FILE_MODE = 0o644

#: Package name mapped to the archive members it carries, each an (archive name,
#: repository-relative source path) pair. The archive name is what the handler
#: imports or opens, so it is stated rather than derived from the source path --
#: `database_admin.py` reads its DDL by bare filename from the package root.
PACKAGES: dict[str, tuple[tuple[str, str], ...]] = {
    "online-write-flag": (("online_write_flag.py", "infra/lambda/online_write_flag.py"),),
    "database-admin": (
        ("database_admin.py", "infra/lambda/database_admin.py"),
        (
            "V0__schemas_and_roles.sql",
            "data-migration/sql/V0__schemas_and_roles.sql",
        ),
    ),
    "dataset-generation-retention": (
        (
            "dataset_generation_retention.py",
            "infra/lambda/dataset_generation_retention.py",
        ),
    ),
}


def resolve_members(members: tuple[tuple[str, str], ...]) -> list[tuple[str, bytes]]:
    """Read every member of one package, failing before anything is written.

    Parameters:
        members (tuple): (archive name, repository-relative source path) pairs.
    Returns:
        list[tuple[str, bytes]]: Archive name and content for each member, in the
            declared order.
    Raises:
        FileNotFoundError: If a source path does not exist, naming that path. Read
            eagerly and in full precisely so this surfaces before the archive is
            opened -- a half-written package would otherwise be hashed and deployed.
    """
    resolved = []
    for archive_name, source in members:
        path = REPOSITORY_ROOT / source
        if not path.is_file():
            raise FileNotFoundError(f"Lambda package source is missing: {path}")
        resolved.append((archive_name, path.read_bytes()))
    return resolved


def write_package(destination: Path, members: list[tuple[str, bytes]]) -> None:
    """Write one deterministic zip containing exactly the given members.

    Parameters:
        destination (Path): Path of the archive to create, overwritten if present.
        members (list[tuple[str, bytes]]): Archive name and content per member.
    Returns:
        None. The archive is the result.
    Raises:
        OSError: If the archive cannot be written.
    """
    # WHY : Assumptions: DEFLATE at the default level, matching what the provider
    #       this replaces produced, so the package this script writes is the same
    #       size class as the one the previous revision deployed. Stored (no
    #       compression) would be equally deterministic and is rejected only
    #       because it makes the upload larger for no benefit.
    with zipfile.ZipFile(destination, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for archive_name, content in members:
            info = zipfile.ZipInfo(filename=archive_name, date_time=FIXED_TIMESTAMP)
            # WHY : Assumptions: the mode is shifted into the high 16 bits of
            #       external_attr, which is where the zip format carries Unix
            #       permissions. Leaving external_attr at its default writes a mode
            #       of 0, and Lambda then cannot read the member.
            info.external_attr = FILE_MODE << 16
            archive.writestr(info, content)


def main(argv: list[str] | None = None) -> int:
    """Build every package, or report which ones are stale without writing.

    Parameters:
        argv (list[str] | None): Argument vector, defaulting to the process's.
    Returns:
        int: 0 when every package was written, or when --check found all of them
            current; 1 when --check found any package missing or out of date.
    Raises:
        FileNotFoundError: Propagated from resolve_members when a source is absent,
            because a build that cannot see its input must not report success.
    """
    parser = argparse.ArgumentParser(description=__doc__)
    # WHY : Assumptions: --check exists so a pipeline can assert the packages match
    #       the sources WITHOUT writing, which is what lets a review job prove a
    #       committed plan was produced from the reviewed handlers. It compares
    #       bytes rather than mtimes, because the archive is deterministic and byte
    #       equality is therefore the exact question.
    parser.add_argument(
        "--check",
        action="store_true",
        help="Report whether each package already matches its sources; write nothing.",
    )
    arguments = parser.parse_args(argv)

    output_directory = HERE / "dist"
    output_directory.mkdir(exist_ok=True)

    stale = []
    for name, members in sorted(PACKAGES.items()):
        resolved = resolve_members(members)
        destination = output_directory / f"{name}.zip"

        if arguments.check:
            if not destination.is_file():
                stale.append(f"{destination.name} (absent)")
                continue
            existing = destination.read_bytes()
            write_package(destination, resolved)
            if destination.read_bytes() != existing:
                stale.append(f"{destination.name} (out of date)")
            continue

        write_package(destination, resolved)
        print(f"built {destination.relative_to(REPOSITORY_ROOT)} ({destination.stat().st_size} bytes)")

    if arguments.check:
        if stale:
            print("Lambda packages are not current: " + "; ".join(stale), file=sys.stderr)
            return 1
        print(f"all {len(PACKAGES)} Lambda packages match their sources")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
