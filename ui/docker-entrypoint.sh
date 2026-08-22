#!/bin/sh
# Publishes this container's runtime configuration and content-security policy, then starts nginx.
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
#
# WHY this script also publishes `config.json` — Refactoring Rationale: it used to render the policy
# token and nothing else, and the image had no way at all to be told which API to call. The bundle
# carries no compiled `VITE_API_BASE_URL` -- it is built before any environment exists, which is the
# whole reason a runtime document exists -- and `ui/src/api/runtimeConfig.ts` treats an ABSENT
# document as "use the compiled value", so a container started from this image mounted an application
# whose every request was unconfigured. What an operator met was a working sign-on screen answering
# 'Unable to verify the User ...', which reads as a rejected credential rather than as a deployment
# with no API address, and no credential had left the browser at all.
#
# Assumptions: the same value that configures the API is the value the policy is derived from, so
# `connect-src` and the published document cannot disagree. Deriving one from the other is the point:
# two separately supplied values would let a container be configured to call an origin its own policy
# blocks, which the browser reports as a network failure on whichever screen asked first.
#
# Assumptions: the document is REQUIRED input rather than optional. A container that starts without
# it can serve nothing usable, and starting anyway defers a deployment error until an operator meets
# it on a screen -- which is the failure this file already refuses for a malformed policy origin.
set -eu

TEMPLATE=/etc/nginx/templates/default.conf.template
RENDERED=/etc/nginx/conf.d/default.conf
TOKEN=__CARDDEMO_API_CONNECT_SRC__

# Assumptions: this is the `root` of every location in `ui/nginx.conf`, and the directory the final
#       image stage copies the built bundle into as uid 10001 -- the same uid this script runs as, so
#       the document below is writable without any elevation.
DOCUMENT_ROOT=/usr/share/nginx/html
CONFIG_DOCUMENT="$DOCUMENT_ROOT/config.json"

# Assumptions: the staging file sits in the DOCUMENT ROOT rather than in /tmp, because `mv` is atomic
#       only within one filesystem; across filesystems it degrades to copy-then-unlink and a reader
#       can observe a partial document. It is also written before nginx is exec'd, so nothing is
#       serving while it exists.
CONFIG_STAGING="$DOCUMENT_ROOT/.config.json.staging"

# The one sentence every refusal below ends with, so a rejected value is answered with the shapes
# that would have been accepted rather than only with what was wrong.
ACCEPTED_SHAPES="accepted shapes are an absolute https URL ending in /api/v1 (for example https://api.example.com/api/v1) or the same-origin path /api/v1"

if [ ! -r "$TEMPLATE" ]; then
    echo "carddemo-ui: configuration template $TEMPLATE is missing or unreadable" >&2
    exit 1
fi

# WHY : Refactoring Rationale: refusals are funnelled through one function rather than repeated at
#       each branch, because the accepted-shape sentence has to be identical in every one of them --
#       four hand-written copies is four chances for the message to describe a rule the code below no
#       longer applies.
# @param $1 - What was wrong with the supplied value.
refuse_api_base_url() {
    echo "carddemo-ui: CARDDEMO_API_BASE_URL $1; $ACCEPTED_SHAPES" >&2
    exit 1
}

api_base_url=${CARDDEMO_API_BASE_URL:-}

if [ -z "$api_base_url" ]; then
    refuse_api_base_url "is unset or empty, and this image cannot serve a usable application without it"
fi

# WHY : Assumptions: these four characters are refused before any shape is matched, and each is
#       refused for its own reason. A wildcard would be a policy hole wearing a configuration's
#       clothes; a query or a fragment cannot survive on a base URL, because the client appends
#       operation paths to it and both would end up in the middle of a request line; and whitespace
#       means the variable holds more than one value, which is a list this script does not accept
#       here -- CARDDEMO_API_CONNECT_SRC_ORIGINS is the list.
case "$api_base_url" in
    *[[:space:]]*)
        refuse_api_base_url "must be a single value carrying no whitespace"
        ;;
    *\**)
        refuse_api_base_url "must carry no wildcard"
        ;;
    *\?* | *\#*)
        refuse_api_base_url "must carry no query and no fragment"
        ;;
esac

# Assumptions: the two shapes are matched with anchored expressions rather than with prefix globs,
#       because the properties that matter are at both ends -- the scheme at the front and the
#       operation prefix at the back -- and a glob cannot state both without admitting a path
#       segment such as `..` in between. The character class is the unreserved set from RFC 3986,
#       which is what keeps the value safe to interpolate into the JSON document below without an
#       escaper: a quote or a backslash cannot match it.
# Assumptions: an intermediate path is admitted -- `https://host/stage/api/v1` as well as
#       `https://host/api/v1` -- because an HTTP API published under a named stage carries one, and
#       `normalizeApiBaseUrl` in `ui/src/api/runtimeConfig.ts` admits exactly the same set. The two
#       validators are held to one rule so a document this script writes is never one the browser
#       then refuses.
# Assumptions: cleartext is refused for every host INCLUDING loopback, which is deliberately
#       narrower than the browser's own rule. A loopback address inside a container names the
#       container itself, so it can never address an API on the host and admitting it would only
#       accept a value that cannot work; local cleartext development is served by `npm run dev`,
#       where the browser's loopback exemption applies.
# Assumptions: a trailing slash is refused rather than trimmed. This script PRODUCES the canonical
#       document, so accepting a second spelling of one value would put two forms into circulation
#       and make the container and the CloudFront path differ in what they publish for the same
#       input. The browser tolerates one because it also reads documents this image did not write.
absolute_api='^https://[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?(:[0-9]{1,5})?(/[A-Za-z0-9._~-]+)*/api/v1$'
same_origin_api='^(/[A-Za-z0-9._~-]+)*/api/v1$'

api_origin=""
if printf '%s' "$api_base_url" | grep -Eq "$absolute_api"; then
    # Assumptions: the origin is everything up to the first slash after the scheme, which is exactly
    #       what a `connect-src` entry may carry -- the directive matches by origin, and a path in it
    #       is ignored by some agents and rejected by others.
    api_origin=$(printf '%s' "$api_base_url" | sed -E 's#^(https://[^/]+).*$#\1#')
elif ! printf '%s' "$api_base_url" | grep -Eq "$same_origin_api"; then
    refuse_api_base_url "is not one of the accepted shapes"
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

# WHY : Assumptions: the API's own origin is unioned into the list rather than left to be supplied a
#       second time through CARDDEMO_API_CONNECT_SRC_ORIGINS. Requiring it twice is a configuration
#       that can contradict itself: a container told to call one origin and to permit another starts
#       cleanly, serves a policy that reads as deliberate, and then has every request blocked by the
#       browser -- which reaches an operator as an unexplained failure on whichever screen asked
#       first. Deriving it here makes that state unreachable.
#       Assumptions: a same-origin base contributes NOTHING, because it needs nothing: the request is
#       covered by `default-src 'self'`, and adding the page's own origin explicitly would widen the
#       directive by exactly zero while implying a cross-origin call is being made.
#       Assumptions: the entry is de-duplicated, so naming the API origin in both variables renders
#       one source rather than the same host twice. A repeated source is legal in the grammar and
#       harmless to enforcement, and it is still removed -- a header that lists one host twice reads
#       as a rendering fault to whoever is auditing it.
if [ -n "$api_origin" ]; then
    case " $rendered_origins " in
        *" $api_origin "*) ;;
        *) rendered_origins="$rendered_origins $api_origin" ;;
    esac
fi

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

if [ ! -d "$DOCUMENT_ROOT" ] || [ ! -w "$DOCUMENT_ROOT" ]; then
    # WHY : Assumptions: this is checked rather than left to the redirection below, because `set -e`
    #       would abort with the shell's own "cannot create" message, which names a path and not the
    #       reason -- and the reason is nearly always that the document root was mounted over, or
    #       re-owned, so that uid 10001 can no longer write the one file it must publish.
    echo "carddemo-ui: document root $DOCUMENT_ROOT is missing or not writable by this user; the runtime configuration cannot be published" >&2
    exit 1
fi

# WHY : Assumptions: the document is written to a staging path and MOVED into place, so a reader
#       never observes a partial one. `mv` within a filesystem is a rename, which is atomic: a
#       request for /config.json either reads the previous document or the new one, never a truncated
#       one. Writing straight to the served path would leave a window -- short, but real on a
#       restart of a running container against a live health check -- in which the document parses as
#       invalid JSON, and `ui/src/api/runtimeConfig.ts` rejects that rather than ignoring it, so the
#       application would refuse to start for a value that was about to be correct.
#       Assumptions: no JSON escaper is used and none is needed. Both accepted shapes are matched
#       against the RFC 3986 unreserved character set above, so the value cannot contain a quote, a
#       backslash or a control character -- the three things an escaper would exist to handle. This
#       is stated because the absence of an escaper otherwise reads as an oversight.
#       Assumptions: the mode is set explicitly rather than inherited from the umask, because the
#       document must be readable by the nginx worker. The workers run as this same unprivileged
#       user, so 0644 is already sufficient today; setting it means a future umask change cannot
#       silently publish a document nginx answers 403 for.
printf '{"apiBaseUrl":"%s"}\n' "$api_base_url" > "$CONFIG_STAGING"
chmod 0644 "$CONFIG_STAGING"
mv -f "$CONFIG_STAGING" "$CONFIG_DOCUMENT"

exec nginx -g 'daemon off;'
