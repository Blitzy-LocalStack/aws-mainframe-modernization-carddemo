package com.carddemo.common.security;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Objects;

/**
 * Refuses a configured service-to-service base address that is not an approved absolute HTTPS origin.
 *
 * <p>Purpose: every internal HTTP client in this migration attaches a credential to its requests -- a
 * minted machine token on two of them, the calling user's relayed bearer token on the third -- and the
 * first request some of them make carries a primary account number. A base address is configuration, so
 * it can be changed after review by whoever edits a parameter file; validating it before the client is
 * constructed is what stops a changed address from receiving a live credential or cardholder data. This
 * class performs that validation once for every such client.</p>
 *
 * <h2>Why this lives in the shared kernel</h2>
 *
 * <p>Refactoring Rationale: two service modules carried a private copy of this check and a third was
 * about to. Transformation rule T2 puts a shared concern in the kernel exactly once, and the failure mode
 * of not doing so is specific rather than aesthetic: a check duplicated three times is a check that gets
 * strengthened in one copy. The copies were byte-identical in structure and differed only in the property
 * prefix they name and in the one clause that says WHAT is at risk on that particular seam, which is why
 * both of those are parameters here and the seven refusals are not.</p>
 *
 * <p>Assumptions: the messages this class raises are the messages the two former copies raised, character
 * for character, because a caller supplies the same prefix and the same clauses those copies hard-coded.
 * That is deliberate: an operator's runbook and two existing test suites name those sentences, and an
 * extraction that reworded them would be a behavioural change wearing a refactor's clothes.</p>
 *
 * <p>Alternatives Considered: a Spring {@code Validator} or a bean-validation constraint on a
 * configuration-properties type. Rejected because the check has to run BEFORE the client is built and its
 * failure has to prevent the context from starting; a constraint on a properties bean would fire for
 * whichever module bound the properties, leaving a module that reads the value through {@code @Value} --
 * which all three of these clients do -- unchecked. A static call from the constructor cannot be
 * bypassed.</p>
 *
 * <p>Alternatives Considered: matching the address against a host suffix or a pattern rather than an exact
 * origin. Rejected because the legitimate value is a deployment fact published by a Terraform output, not
 * a shape, and any pattern loose enough to cover a legitimate internal origin also covers a host an
 * attacker could arrange to control.</p>
 */
public final class ApprovedOriginPolicy {

    /**
     * The only scheme an internal base address may use.
     *
     * <p>Assumptions: HTTPS is required rather than preferred even though every one of these hops is
     * inside the private network. The traffic carries credentials and account data, the migration's
     * security design encrypts in transit end to end, and "internal" is a property of today's topology
     * rather than of the address itself.</p>
     */
    public static final String REQUIRED_SCHEME = "https";

    /**
     * Withholds construction: the class publishes one static check and holds no state.
     *
     * <p>Assumptions: the constructor is private rather than the class being an interface with static
     * members, because an interface would be implementable and this contract has no implementations.</p>
     */
    private ApprovedOriginPolicy() {
    }

    /**
     * The three clauses that say what is at risk on the seam being validated.
     *
     * <p>Assumptions: three clauses rather than one, because the three refusals they appear in are read in
     * three different situations -- an address that was never configured, an address configured in clear
     * text, and an address configured to somewhere unapproved -- and an operator acts differently on each.
     * A single shared clause would have to be vague enough to fit all three, and a vague reason is what
     * the Explainability rule forbids.</p>
     *
     * <p>Assumptions: each clause is a sentence FRAGMENT that continues the refusal it is appended to,
     * never a whole sentence, so the composed message reads as one statement. The callers' clauses are the
     * ones their former private copies carried.</p>
     *
     * @param whenAbsent why no default address is safe, appended after a colon
     * @param whenNotHttps why clear text is unacceptable on this seam, appended after "and"
     * @param whenUnapproved why an unapproved destination is harmful here, appended after a colon
     */
    public record Sensitivity(String whenAbsent, String whenNotHttps, String whenUnapproved) {

        /**
         * Creates the clause set, refusing a partially supplied one.
         *
         * @param whenAbsent why no default address is safe on this seam; must not be {@code null}
         * @param whenNotHttps why clear text is unacceptable on this seam; must not be {@code null}
         * @param whenUnapproved why an unapproved destination is harmful here; must not be {@code null}
         * @throws NullPointerException if any clause is {@code null}
         */
        public Sensitivity {
            Objects.requireNonNull(whenAbsent, "whenAbsent must not be null");
            Objects.requireNonNull(whenNotHttps, "whenNotHttps must not be null");
            Objects.requireNonNull(whenUnapproved, "whenUnapproved must not be null");
        }
    }

    /**
     * Refuses the configured base address unless it is the approved absolute HTTPS origin.
     *
     * <p>Assumptions: seven conditions are refused and each is refused separately, naming which one
     * failed. A single combined message would leave an operator to compare their value against a
     * description; a named condition tells them what to change. The conditions are: an absent base
     * address; an absent approved origin; an address that is not parseable; a scheme other than
     * {@link #REQUIRED_SCHEME}; an address that names no host; an address carrying user information, a
     * path, a query or a fragment; and an address that is not the approved origin.</p>
     *
     * <p>Assumptions: the offending VALUE is never quoted into any message. It is configuration rather
     * than cardholder data, so quoting it would be defensible -- and it is withheld anyway, because a
     * misconfigured address has on occasion been a credential-bearing one and these messages reach a
     * startup log that is retained. The property name locates the fault without the value.</p>
     *
     * <p>Trade-offs: a trailing slash is tolerated on either value and normalised away before they are
     * compared, so a deployment that publishes one with a slash and one without still starts. The
     * alternative -- exact string equality -- would refuse two addresses that are the same origin, which
     * is a refusal an operator cannot act on because both values look correct.</p>
     *
     * @param propertyPrefix the configuration prefix the two properties sit under, such as
     *     {@code carddemo.account-context}, used to name the offending property in every refusal; must not
     *     be {@code null}
     * @param baseUrl the configured base address to validate; may be {@code null} or blank, which is
     *     itself a refusal
     * @param approvedOrigin the origin the base address is required to equal; may be {@code null} or
     *     blank, which is itself a refusal
     * @param sensitivity the three clauses describing what is at risk on this seam; must not be
     *     {@code null}
     * @throws NullPointerException if {@code propertyPrefix} or {@code sensitivity} is {@code null}
     * @throws IllegalStateException if either configured value is absent, or the base address is not an
     *     absolute HTTPS origin free of user information, path, query and fragment, or it is not the
     *     approved origin
     */
    public static void require(String propertyPrefix, String baseUrl, String approvedOrigin,
            Sensitivity sensitivity) {

        Objects.requireNonNull(propertyPrefix, "propertyPrefix must not be null");
        Objects.requireNonNull(sensitivity, "sensitivity must not be null");

        String baseProperty = propertyPrefix + ".base-url";
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException(baseProperty + " must be supplied: "
                    + sensitivity.whenAbsent());
        }
        if (approvedOrigin == null || approvedOrigin.isBlank()) {
            throw new IllegalStateException(
                    propertyPrefix + ".approved-origin must be supplied when it is set at all");
        }

        URI address;
        try {
            address = new URI(baseUrl.trim());
        } catch (URISyntaxException malformed) {
            throw new IllegalStateException(baseProperty + " is not a valid address", malformed);
        }

        if (!REQUIRED_SCHEME.equalsIgnoreCase(address.getScheme())) {
            throw new IllegalStateException(baseProperty + " must use the " + REQUIRED_SCHEME
                    + " scheme, because " + sensitivity.whenNotHttps()
                    + " and plain HTTP would put it on the wire in clear text");
        }
        if (address.getHost() == null) {
            throw new IllegalStateException(baseProperty + " must be absolute and name a host");
        }
        if (address.getUserInfo() != null) {
            throw new IllegalStateException(baseProperty + " must carry no user information: it would"
                    + " place a credential in a header this client never declares");
        }
        String path = address.getPath();
        if (path != null && !path.isEmpty() && !"/".equals(path)) {
            throw new IllegalStateException(baseProperty + " must carry no path, because this client"
                    + " appends its own and a base path would silently re-root every call");
        }
        if (address.getQuery() != null || address.getFragment() != null) {
            throw new IllegalStateException(baseProperty + " must carry no query and no fragment,"
                    + " because either would be attached to all three published requests");
        }
        if (!normaliseOrigin(baseUrl).equals(normaliseOrigin(approvedOrigin))) {
            throw new IllegalStateException(baseProperty + " is not the approved origin: "
                    + sensitivity.whenUnapproved());
        }
    }

    /**
     * Reduces an address to a comparable origin by discarding one trailing separator.
     *
     * <p>Assumptions: only a TRAILING separator is discarded and nothing else is rewritten -- no case
     * folding of the host, no default-port removal. Each of those would make two textually different
     * values compare equal, and the comparison exists precisely to detect a value that differs from the
     * approved one.</p>
     *
     * @param address the configured value, already established as non-blank; must not be {@code null}
     * @return the value trimmed of surrounding whitespace and of one trailing separator, never
     *     {@code null}
     */
    private static String normaliseOrigin(String address) {
        String trimmed = address.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }
}
