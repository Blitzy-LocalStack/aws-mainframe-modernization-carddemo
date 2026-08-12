#!/bin/sh
# =============================================================================
# config/docker/install-rds-trust-anchor.sh
# Build-stage installer for the Aurora/RDS certificate-authority bundle that
# every CardDemo service image ships as its database trust anchor.
# -----------------------------------------------------------------------------
# Purpose:
#   Fetch the AWS-published RDS global certificate bundle, verify it against a
#   pinned SHA-256 digest, confirm it actually contains PEM certificates, and
#   write it to the path given as the single argument. The runtime stage of each
#   service image then copies that file to
#   /etc/ssl/certs/carddemo-rds-ca-bundle.pem, which is the literal every
#   service's application.yml resolves `spring.datasource.hikari.data-source-
#   properties.sslrootcert` to and the literal both environment roots pass as
#   CARDDEMO_DB_SSL_ROOT_CERT.
#
#   Without this file the connection contract cannot be met at all: every
#   service connects with sslmode=verify-full, and pgjdbc opens the configured
#   sslrootcert path before it opens a socket, so a missing anchor is not a
#   downgrade to an unverified connection -- it is a service that cannot reach
#   its database and reports an unreadable-file error while looking correctly
#   configured.
#
# Parameters:
#   $1  Absolute path this script writes the verified bundle to. Required; the
#       script refuses to guess, because a default would let a caller that
#       forgot the argument produce a build that silently installs nothing at
#       the path its runtime stage copies from. Its parent directory must exist.
#
# Return values:
#   None on stdout beyond two progress lines. The observable result is the file
#   at $1, whose content is byte-identical to the pinned digest.
#
# Exceptions or errors:
#   Exits non-zero, before writing $1, on every failure path, and the four codes
#   are distinct so a build log identifies the cause without being read for
#   context:
#     64  no target-path argument was given.
#     65  the download's SHA-256 does not match the pinned digest.
#     66  the download carries no PEM certificate block.
#     other  curl's own exit status for a failed, redirected-away or timed-out
#            transfer, propagated unchanged rather than remapped, so the standard
#            curl code stays searchable.
#   Every one of those is a hard failure of the image build rather than a
#   warning, because an image that builds without its trust anchor is an image
#   whose tasks cannot start.
#
# Governing convention:
#   docs/CODE_DOCUMENTATION_STANDARD.md -- shell entry points carry a header
#   block stating purpose plus a why-comment on any non-obvious ordering or flag.
#
# WHY (non-obvious design decisions):
#   1. Alternatives Considered: trusting the base image's own CA store instead
#      of shipping a bundle. Rejected as far too broad -- that store trusts every
#      publicly trusted authority, so a certificate issued by any one of them for
#      the cluster endpoint name would verify, whereas this bundle trusts only the
#      authorities that can legitimately certify an Aurora endpoint. This is the
#      same reasoning data-migration/Dockerfile records for the ETL image.
#   2. Alternatives Considered: mounting a bundle at run time from a volume or a
#      secret. Rejected because it makes a security-critical file an operator step
#      that can be forgotten, and the failure then appears as eight services that
#      will not start in an environment that Terraform reports as applied.
#   3. Alternatives Considered: committing the PEM into the repository and copying
#      it with no network access at build time. It is the more hermetic shape and it
#      was rejected for two reasons: the bundle is a 165 KB artifact that AWS
#      rotates on its own schedule, so the repository would carry a copy that goes
#      stale silently, and the existing ETL image already establishes fetch-then-
#      verify as this repository's pattern for exactly this file. A second pattern
#      for one artifact is worse than one pattern used twice.
#   4. Assumptions: an unverified fetch would be self-defeating in the most
#      literal way -- a trust anchor obtained over an unauthenticated channel
#      anchors nothing -- so the digest below is checked before the file is moved
#      into place. The check is done with sha256sum rather than by piping into a
#      language runtime, because /usr/bin/sha256sum, /usr/bin/curl and
#      /usr/bin/grep were each verified present inside the exact
#      maven:3.9.16-amazoncorretto-21-al2023 digest the eight service images pin,
#      so this script installs no package and adds no build dependency.
#   5. Trade-offs: AWS republishes the bundle when a certificate authority is
#      rotated, and on that day every service image build fails with a digest
#      mismatch until EXPECTED_SHA256 below is refreshed. That is the correct
#      behaviour and the same trade-off the pinned base-image digests make.
#      Refreshing it is one command whose output goes on the EXPECTED_SHA256 line:
#      `curl -fsSL https://truststore.pki.rds.amazonaws.com/global/global-bundle.pem
#      | sha256sum`.
#   6. Refactoring Rationale: this is ONE script copied into eight build stages
#      rather than eight copies of the same fetch-and-verify block. The digest is
#      the reason: a pinned hash duplicated eight times is a pinned hash that
#      diverges the first time one of the eight is refreshed, and seven images
#      would then fail a build for a reason that reads like a network fault.
#   7. Assumptions: data-migration/Dockerfile keeps its OWN copy of this logic and
#      of this digest, and that duplication is forced rather than careless -- that
#      image is built with data-migration/ as its context, so config/docker/ is
#      not inside its context and no COPY of it can resolve. The two pinned
#      digests are asserted EQUAL by a gate in .github/workflows/services-ci.yml,
#      so a rotation that updates one and not the other fails a pull request
#      instead of a deployment.
# =============================================================================

set -eu

target="${1:-}"
if [ -z "${target}" ]; then
    echo "install-rds-trust-anchor.sh: a target path argument is required" >&2
    exit 64
fi

# WHY : Assumptions: the URL is the AWS-published global bundle, which carries
#       every regional authority in one file. A per-region bundle was considered
#       and rejected: the image would then have to know which region it will be
#       deployed into at BUILD time, and one image is deployed to both
#       environments by digest.
url="https://truststore.pki.rds.amazonaws.com/global/global-bundle.pem"

# WHY : Assumptions: this is the digest of the bundle as published at the time
#       this pin was reviewed -- 165408 bytes carrying 108 certificates, measured
#       rather than quoted. It is the same value data-migration/Dockerfile pins,
#       and the gate named in note 7 above keeps the two identical.
EXPECTED_SHA256="e5bb2084ccf45087bda1c9bffdea0eb15ee67f0b91646106e466714f9de3c7e3"

# WHY : Assumptions: the download lands in a scratch file beside the target and
#       is moved into place only after both checks pass, so a failed build never
#       leaves a partially written or unverified file at the path the runtime
#       stage copies from. A truncated transfer that still exited zero would
#       otherwise be indistinguishable from a good one at COPY time.
# WHY : Trade-offs: the curl flags are chosen for a build step rather than for an
#       interactive fetch. --fail turns an HTTP error page into a non-zero exit
#       instead of a 404 body written to the target; --location follows the
#       publisher's redirect; --retry 3 with --retry-connrefused absorbs a
#       transient DNS or connection failure on a build runner rather than failing
#       an otherwise-good build; --max-time bounds the step so a hung transfer
#       fails instead of holding the runner; --proto '=https' refuses a redirect
#       that would downgrade the transport, which matters more here than anywhere
#       else in the build because the payload IS a trust anchor.
scratch="${target}.download"

# WHY : Assumptions: the scratch file is removed on ANY non-zero exit, so a
#       failed run leaves the directory exactly as it found it. Inside an image
#       build the layer is discarded anyway, but this script is also runnable by
#       hand to refresh the digest, and a half-downloaded file left beside the
#       real one is precisely the thing that gets copied by mistake later.
trap 'status=$?; [ "${status}" -eq 0 ] || rm -f "${scratch}"; exit "${status}"' EXIT

echo "install-rds-trust-anchor.sh: fetching ${url}"
curl --fail --silent --show-error --location \
     --retry 3 --retry-connrefused --max-time 120 \
     --proto '=https' --tlsv1.2 \
     --output "${scratch}" \
     "${url}"

# WHY : Alternatives Considered: piping the pin into `sha256sum --check --strict`
#       and letting its own exit status gate the build. It is the shorter form and
#       it was rejected on the message it produces: the operator sees
#       "<path>: FAILED" and a warning line, with nothing to say that AWS rotates
#       this bundle or how to refresh the pin. Comparing the two digests here
#       costs one command substitution and lets the failure name the cause, the
#       expected value, the value actually served and the one command that fixes
#       it -- which is the difference between a five-minute and a one-hour
#       diagnosis on the morning of a rotation.
actual_sha256="$(sha256sum "${scratch}" | cut -d ' ' -f 1)"
if [ "${actual_sha256}" != "${EXPECTED_SHA256}" ]; then
    echo "install-rds-trust-anchor.sh: Aurora CA bundle digest mismatch." >&2
    echo "  expected: ${EXPECTED_SHA256}" >&2
    echo "  actual:   ${actual_sha256}" >&2
    echo "  AWS republishes this bundle when a certificate authority is rotated." >&2
    echo "  Verify the new bundle, then set EXPECTED_SHA256 in" >&2
    echo "  config/docker/install-rds-trust-anchor.sh AND the matching EXPECTED" >&2
    echo "  value in data-migration/Dockerfile to the output of:" >&2
    echo "    curl -fsSL ${url} | sha256sum" >&2
    exit 65
fi

# WHY : Assumptions: the digest check already proves the exact bytes, so this
#       second check exists for the case the digest is refreshed by hand. It
#       catches a value pasted from the wrong artifact -- an HTML error page or a
#       DER file both have a digest, and only a PEM bundle is usable by pgjdbc.
if ! grep -q -- '-----BEGIN CERTIFICATE-----' "${scratch}"; then
    echo "install-rds-trust-anchor.sh: ${url} returned no PEM certificate block" >&2
    exit 66
fi

mv "${scratch}" "${target}"
echo "install-rds-trust-anchor.sh: verified bundle written to ${target}"
