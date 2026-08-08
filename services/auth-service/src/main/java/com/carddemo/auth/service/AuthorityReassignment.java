package com.carddemo.auth.service;

import java.util.Objects;

/**
 * Evidence that a user's authority now stands at one reference type rather than another, produced by
 * the provider exchange that moved it and required by the only method permitted to move the local row.
 *
 * <p>Purpose: this type exists so that "the provider agreed" is a value a compiler can insist on
 * rather than an ordering a reader has to trust. Effective authority in this migration comes from one
 * place and one place only: {@code cognito:groups} arrives as a signed claim and
 * {@code com.carddemo.common.security.JwtRoleConverter} turns it into the authorities every guarded
 * route is matched against. The {@code auth.users.user_type} column names the same thing but grants
 * nothing, so a row moved from {@code "U"} to {@code "A"} on its own is a record of an intention that
 * never took effect -- and worse, it reads afterwards as though it had. Requiring an instance of this
 * record before that column may be assigned makes the two impossible to separate.</p>
 *
 * <p>Assumptions: an instance is only ever minted by
 * {@link CognitoUserProvisioningService#reassignGroup(String, String, String)}, immediately after the
 * provider has accepted the membership change, and by the {@code unchanged} factory below for the case
 * where there was nothing to move. Nothing here enforces that -- a record's canonical constructor is as
 * public as the record is, so this class cannot hide it -- and no attempt is made to pretend otherwise:
 * the guarantee is that a caller assembling one by hand is visibly asserting something, in a type whose
 * whole documented purpose is that assertion, rather than silently omitting a step. That is a
 * reviewable act instead of an invisible one, which is the property being bought.</p>
 *
 * <p>Alternatives Considered: a nested class inside {@code CognitoUserProvisioningService} with a
 * private constructor, which WOULD make the provenance unforgeable, because only the enclosing class
 * could construct it. Rejected on a boundary the repository already keeps: every one of the ten
 * service classes across this reactor imports from its sibling {@code mapper} package and not one
 * mapper imports from a {@code service} package, so making this evidence unforgeable would have meant
 * the mapper importing the provider-facing service class and introducing the first edge in that
 * direction anywhere in the codebase. A standalone value type in this package leaves the mapper
 * depending on a record that names two reference type letters and nothing else.</p>
 *
 * <p>Trade-offs: the record carries the identifier and the two type letters and no provider handle, no
 * group name and no timestamp. A group name would tie this evidence to the naming of infrastructure
 * resources, which {@code infra/modules/cognito} is free to change; a timestamp would invite a caller
 * to treat the evidence as durable when it is valid only within the call that produced it. What is
 * given up is that a log line built from this value alone cannot name the group that moved, which is
 * why the service that performs the move logs the group itself.</p>
 *
 * @param userId the provider username and row identifier the reassignment applied to, at most the
 *     eight characters {@code SEC-USR-ID PIC X(08)} declares at {@code app/cpy/CSUSR01Y.cpy} L18
 * @param previousUserType the reference type the identity held before the exchange, {@code "A"} or
 *     {@code "U"} per {@code app/cpy/COCOM01Y.cpy} L27 and L28
 * @param currentUserType the reference type the identity holds now, from the same two-value domain
 * @param providerMutated {@code true} when the provider's membership was actually changed, and
 *     {@code false} when the requested type already matched the held one and nothing was called; a
 *     caller needing to compensate a later failure uses this to tell a move from a no-op
 */
public record AuthorityReassignment(
        String userId, String previousUserType, String currentUserType, boolean providerMutated) {

    /**
     * Rejects an instance that does not describe a whole reassignment.
     *
     * <p>Assumptions: every component is required, and the two type values are not re-validated
     * against the two-member domain here. The service that mints an instance has already put both
     * through {@code groupFor}, which admits {@code "A"} and {@code "U"} and raises on anything else,
     * so a domain check here would duplicate a refusal that has already happened one call earlier and
     * would report it from a place that cannot say which argument was wrong.</p>
     *
     * @throws NullPointerException when any of the three string components is null, which would be a
     *     defect in the minting code rather than a condition a caller can cause
     */
    public AuthorityReassignment {
        Objects.requireNonNull(userId, "userId must not be null on an authority reassignment");
        Objects.requireNonNull(
                previousUserType, "previousUserType must not be null on an authority reassignment");
        Objects.requireNonNull(
                currentUserType, "currentUserType must not be null on an authority reassignment");
    }

    /**
     * Records that a requested type already matched the held one, so no provider call was made.
     *
     * <p>Assumptions: this is evidence of alignment and not of inaction. A caller that receives it may
     * assign the local column exactly as it may with a mutating instance, because the column is being
     * assigned the value it already carries; what it may not do is register a compensation, since there
     * is nothing at the provider to put back. The {@code providerMutated} flag is what tells the two
     * apart, and it is the reason this factory exists rather than callers passing {@code false}
     * literals at each site.</p>
     *
     * @param userId the row identifier and provider username; must not be {@code null}
     * @param userType the reference type held both before and after; must not be {@code null}
     * @return evidence that the identity's authority already stands where it was asked to stand; never
     *     {@code null}
     */
    public static AuthorityReassignment unchanged(String userId, String userType) {
        return new AuthorityReassignment(userId, userType, userType, false);
    }

    /**
     * Renders the reassignment for a diagnostic line.
     *
     * <p>Assumptions: every component is rendered, and that is compliant rather than an exception to
     * the rendering contract in {@code docs/architecture/observability.md}. That contract withholds
     * account identifiers, customer identifiers, monetary amounts and card verification values; an
     * operator identifier and two single-character reference type letters are none of those, and the
     * service that mints this value already logs the identifier and the group beside it. A rendering
     * that withheld the two type letters would remove the only information that makes the line worth
     * having, since which way an authority moved is the whole subject of the record.</p>
     *
     * @return a single-line rendering naming the identifier, both type letters and whether the provider
     *     was called; never {@code null}
     */
    @Override
    public String toString() {
        return "AuthorityReassignment[userId=" + this.userId
                + ", previousUserType=" + this.previousUserType
                + ", currentUserType=" + this.currentUserType
                + ", providerMutated=" + this.providerMutated + "]";
    }
}
