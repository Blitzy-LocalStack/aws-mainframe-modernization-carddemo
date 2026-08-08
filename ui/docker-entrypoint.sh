#!/bin/sh
# Renders the container's content-security policy, then starts nginx in the foreground.
#
# WHY this script exists — Refactoring Rationale: every Content-Security-Policy header this image
# served carried `connect-src 'self' https:`. That admits an XHR or fetch to ANY host on the
# internet over HTTPS, which is precisely the exfiltration path a content-security policy exists to
# close, so the directive was defeating its own purpose while reading as though a policy were in
# force. The reason it was written that way is sound as far as it goes: the API origin is not
# knowable when this image is BUILT, and hard-coding one would make the image environment-specific,
# so `dev` and `prod` could no longer share a reviewed artifact.
#
# The conclusion that does not follow is that the origin cannot be known AT ALL. It is unknown at
# build time and known at CONTAINER START time, which is where this script runs. The image therefore
# stays environment-agnostic -- it ships a template with a placeholder, not an origin -- while the
# header the browser actually receives names exact origins.
#
# Alternatives Considered: the official nginx image's own template mechanism, which runs `envsubst`
# over `/etc/nginx/templates/*.template` from `/docker-entrypoint.sh`. Rejected because that
# auto-configuration is guarded by a root check -- the script skips it entirely when `id -u` is not 0
# -- and this image deliberately runs as the unprivileged user 10001. Relying on it would mean the
# substitution silently did not happen and the placeholder was served verbatim inside the header.
#
# Alternatives Considered: dropping the directive to a bare `connect-src 'self'` and accepting that
# the containerised path cannot reach a cross-origin API. Rejected because it would make this image
# unusable for local and container use, which is the only reason it exists -- the DEPLOYED path is
# CloudFront over a private S3 origin, as `infra/modules/cloudfront-spa/main.tf` records.
#
# Assumptions: `sed` is used rather than `envsubst`. `sed` is part of busybox and is therefore
# present in this base image by construction, whereas `envsubst` arrives with an optional package;
# choosing the one that cannot be absent removes a failure mode that would only appear at run time.
#
# Assumptions: an unset or empty origin list renders `connect-src 'self'` and NOT a wildcard. That
# fails closed, and it matches the deployed path exactly -- the distribution's policy in
# `infra/modules/cloudfront-spa/main.tf` joins `'self'` with its configured origins and so yields
# `connect-src 'self'` for an empty input too. The two delivery paths therefore agree in every
# directive, including this one, which they did not before.
set -eu

TEMPLATE=/etc/nginx/templates/default.conf.template
RENDERED=/etc/nginx/conf.d/default.conf
TOKEN=__CARDDEMO_API_CONNECT_SRC__

if [ ! -r "$TEMPLATE" ]; then
    echo "carddemo-ui: configuration template $TEMPLATE is missing or unreadable" >&2
    exit 1
fi

# WHY : Trade-offs: every supplied origin is validated, and one bad entry fails the START rather
#       than being dropped. A dropped origin produces a container that serves a policy quietly
#       missing the host the application needs, which surfaces to a user as an unexplained blocked
#       request; refusing to start surfaces it to whoever set the value.
#       Assumptions: the accepted shape is scheme, host and optional port -- no path, no wildcard,
#       no scheme other than https. It matches the validation on the Terraform variable that feeds
#       the deployed path, so a value that is legal for one path is legal for the other. A wildcard
#       is refused rather than expanded because it would admit every endpoint in a partition, which
#       is the blanket policy being removed here wearing a narrower disguise.
rendered_origins=""
for origin in ${CARDDEMO_API_CONNECT_SRC_ORIGINS:-}; do
    case "$origin" in
        https://*\** | https://*/*)
            echo "carddemo-ui: connect-src origin '$origin' must carry no wildcard and no path" >&2
            exit 1
            ;;
        https://*)
            if ! printf '%s' "$origin" | grep -Eq '^https://[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?(:[0-9]{1,5})?$'; then
                echo "carddemo-ui: connect-src origin '$origin' is not a bare https origin" >&2
                exit 1
            fi
            rendered_origins="$rendered_origins $origin"
            ;;
        *)
            echo "carddemo-ui: connect-src origin '$origin' must begin with https://" >&2
            exit 1
            ;;
    esac
done

# Assumptions: the token is replaced with the origins INCLUDING their leading space, and with nothing
#       at all when there are none. That is what keeps `connect-src 'self';` well formed in the empty
#       case; a token that rendered as an empty string after a space would leave a stray separator
#       that some parsers accept and others do not.
sed "s|$TOKEN|$rendered_origins|g" "$TEMPLATE" > "$RENDERED"

if grep -q "$TOKEN" "$RENDERED"; then
    # WHY : Assumptions: the rendered file is re-read and checked. `sed` reports success even when it
    #       replaced nothing, so without this a substitution that silently failed would ship the
    #       placeholder inside a live header -- a policy no browser can parse, which some treat as
    #       absent. Failing the start is the only outcome that cannot be mistaken for working.
    echo "carddemo-ui: $TOKEN survived rendering; refusing to serve an unrendered policy" >&2
    exit 1
fi

exec nginx -g 'daemon off;'
