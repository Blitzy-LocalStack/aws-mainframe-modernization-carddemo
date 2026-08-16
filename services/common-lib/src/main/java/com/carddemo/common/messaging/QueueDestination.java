package com.carddemo.common.messaging;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Objects;

/**
 * The one place a configured queue destination is judged, and the one place a queue's name is recovered
 * from its address.
 *
 * <h2>Purpose</h2>
 * <p>Every queue destination in this system is supplied to a service as a fully-qualified queue URL, because
 * that is what the deployment publishes: each {@code CARDDEMO_*_QUEUE_URL} task-definition variable in
 * {@code infra/envs/dev/main.tf} and {@code infra/envs/prod/main.tf} carries a {@code module.sqs.*_queue_url}
 * output. This class turns that fact into something a service can enforce at start-up rather than discover on
 * its first publication.</p>
 *
 * <h2>Why a shape check rather than trust</h2>
 * <p>Refactoring Rationale: the two inquiry consumers previously accepted any non-blank string as a
 * destination and then passed it to the queue service as a queue NAME, resolving it to an address on first
 * use. Because the deployment supplies an address, that resolution was asked to look up a queue whose name
 * was a URL. It could only fail, and it failed on the reply and diagnostic paths alone -- the paths a request
 * reaches only after it has already been taken off the request queue -- so requests were consumed and never
 * answered while start-up, health and the request-side configuration all looked correct. A check that runs at
 * construction converts that into a refusal to start, which is the failure an operator can act on.</p>
 *
 * <p>Alternatives Considered: accepting either form, and resolving a bare name to an address once at
 * construction. Rejected on two counts. It would put a network call inside bean creation, so an unreachable
 * queue service would become a start-up failure indistinguishable from a misconfiguration; and it would keep
 * two spellings of one deployment fact alive, which is what allowed a URL to be read as a name in the first
 * place. Accepting exactly one form is what makes the property name and the property value agree.</p>
 *
 * <p>Alternatives Considered: matching the canonical public form
 * {@code https://sqs.<region>.amazonaws.com/<account-id>/<queue-name>} with an anchored expression. Rejected
 * because it is not the only address this system legitimately receives one from: the emulator the test suite
 * and the local runner use publishes {@code http://sqs.<region>.localhost.localstack.cloud:4566/<account-id>/<queue-name>}
 * and {@code http://localhost:4566/<account-id>/<queue-name>}, and a service-specific interface endpoint
 * publishes a third host shape again. An expression tight enough to reject a bare name and loose enough to
 * admit every real address is the structural rule below: an absolute HTTP or HTTPS address whose path names
 * both an owning account and a queue.</p>
 *
 * <h2>Why the name is recovered rather than configured separately</h2>
 * <p>Assumptions: the last path segment of a queue URL is the queue's name. The diagnostic block the inquiry
 * flows publish carries the failing queue's NAME in a declared forty-eight-character field, following
 * {@code app/app-vsam-mq/cbl/CODATE01.cbl}, which moves a queue name -- not an address, because the baseline
 * has none -- into that field. Deriving the name from the address keeps that field truthful without adding a
 * second property that could name a different queue than the one being published to.</p>
 *
 * <p>This class holds no state and cannot be instantiated. It accepts no construction parameter, yields no
 * instance and raises nothing from its own initialisation.</p>
 */
public final class QueueDestination {

    /**
     * The scheme an unencrypted address carries.
     *
     * <p>Assumptions: plain HTTP is admitted because the emulator this system is developed and tested against
     * publishes it, and refusing it would make the shape check pass only in a deployed environment -- which
     * is the one environment where a misconfiguration is most expensive to discover. Transport confidentiality
     * for real traffic is not this check's job: the deployed addresses come from
     * {@code module.sqs.*_queue_url}, which are HTTPS, and the task's egress reaches the queue service only
     * through the interface endpoint {@code infra/modules/network} provisions.</p>
     */
    private static final String SCHEME_HTTP = "http";

    /**
     * The scheme a deployed address carries.
     */
    private static final String SCHEME_HTTPS = "https";

    /**
     * How many path segments a queue address names.
     *
     * <p>Assumptions: exactly two -- the owning account identifier and the queue name -- in every address
     * form named on this class. Requiring two is what separates an address from a bare queue name, which has
     * no path at all, and from a host-only URL, which has one empty one.</p>
     */
    private static final int PATH_SEGMENT_COUNT = 2;

    /**
     * The path separator queue addresses use.
     */
    private static final char PATH_SEPARATOR = '/';

    /**
     * Refuses instantiation.
     *
     * @throws AssertionError always, because every member of this class is static and an instance would
     *     suggest otherwise
     */
    private QueueDestination() {
        throw new AssertionError("QueueDestination is a static holder and must not be instantiated");
    }

    /**
     * Validates a configured queue destination and returns it in the form a publication may use.
     *
     * <p>Assumptions: the value is TRIMMED before it is judged and the trimmed value is what is returned.
     * A destination arriving from a task-definition variable or a YAML scalar can carry surrounding
     * whitespace, and an address that differs from the published one only by a trailing space would fail
     * every publication for a reason no log line would explain.</p>
     *
     * @param value the configured destination, expected to be a fully-qualified queue URL; must not be
     *     {@code null}
     * @param property the property or environment-variable name the value came from, so that a refusal names
     *     exactly what an operator has to set; must not be {@code null}
     * @return the trimmed, validated queue URL, never {@code null}
     * @throws NullPointerException if {@code value} or {@code property} is {@code null}
     * @throws IllegalArgumentException if the value is blank, is not an absolute HTTP or HTTPS address, or
     *     does not name both an owning account and a queue -- each of which would leave this service able to
     *     take work off a queue and unable to answer it
     */
    public static String requireQueueUrl(String value, String property) {
        Objects.requireNonNull(property, "property must not be null");
        Objects.requireNonNull(value, property + " must not be null");

        String candidate = value.trim();
        if (candidate.isEmpty()) {
            throw new IllegalArgumentException(property
                    + " must be a fully-qualified queue URL: without one this service would take work off a"
                    + " queue and be unable to answer any of it");
        }

        URI address;
        try {
            address = new URI(candidate);
        } catch (URISyntaxException malformed) {
            // WHY : Assumptions: the offending VALUE is named in the refusal, and that is safe here in a way
            //   it would not be for wire content: this string came from the deployment's own configuration,
            //   not from a message, so it carries no cardholder data and an operator cannot correct a
            //   malformed address they are not shown.
            throw new IllegalArgumentException(property
                    + " must be a fully-qualified queue URL but is not a valid address: " + candidate,
                    malformed);
        }

        String scheme = address.getScheme();
        boolean addressed = address.isAbsolute()
                && (SCHEME_HTTP.equalsIgnoreCase(scheme) || SCHEME_HTTPS.equalsIgnoreCase(scheme))
                && address.getHost() != null
                && pathSegmentCount(address.getPath()) == PATH_SEGMENT_COUNT;

        if (!addressed) {
            // WHY : Assumptions: the message states the SHAPE rather than a single example host, because the
            //   three legitimate host forms -- the public endpoint, the emulator and a VPC interface endpoint
            //   -- are all valid and naming one would send an operator to change a correct value.
            throw new IllegalArgumentException(property
                    + " must be a fully-qualified queue URL of the form"
                    + " <http|https>://<host>/<account-id>/<queue-name>, not a bare queue name, but is: "
                    + candidate);
        }

        return candidate;
    }

    /**
     * Reports the queue name carried by a validated queue URL.
     *
     * @param queueUrl a queue URL that {@link #requireQueueUrl(String, String)} has already accepted; must
     *     not be {@code null}
     * @return the queue name, which is the address's last path segment, never {@code null} or empty
     * @throws NullPointerException if {@code queueUrl} is {@code null}
     * @throws IllegalArgumentException if the value names no queue, which can only mean it was never put
     *     through {@link #requireQueueUrl(String, String)}
     */
    public static String queueNameOf(String queueUrl) {
        Objects.requireNonNull(queueUrl, "queueUrl must not be null");

        int lastSeparator = queueUrl.lastIndexOf(PATH_SEPARATOR);
        String name = lastSeparator < 0 ? "" : queueUrl.substring(lastSeparator + 1);
        if (name.isEmpty()) {
            throw new IllegalArgumentException(
                    "queueUrl names no queue and cannot have been validated: " + queueUrl);
        }
        return name;
    }

    /**
     * Counts the non-empty segments of an address path.
     *
     * <p>Assumptions: empty segments are not counted, so a trailing separator neither adds a segment nor
     * removes one. A leading separator is likewise not a segment, which is why an address path of
     * {@code /000000000000/carddemo-reference-inquiry-reply} counts as two rather than three.</p>
     *
     * @param path the address path, or {@code null} when the address carried none
     * @return the number of non-empty segments, zero when the path is absent or empty
     */
    private static int pathSegmentCount(String path) {
        if (path == null || path.isEmpty()) {
            return 0;
        }

        int segments = 0;
        int position = 0;
        while (position < path.length()) {
            int separator = path.indexOf(PATH_SEPARATOR, position);
            int end = separator < 0 ? path.length() : separator;
            if (end > position) {
                segments++;
            }
            position = end + 1;
        }
        return segments;
    }
}
