package com.carddemo.auth.service;

import java.util.Objects;

/**
 * Reports that a user identifier submitted for creation is already taken.
 *
 * <p>Purpose: carries the one condition the create operation answers with 409. The published contract
 * requires that status to render {@code 'User ID already exist...'}, reproduced exactly as
 * {@code app/cbl/COUSR01C.cbl} line 263 writes it -- missing {@code s} and no space before the ellipsis --
 * and this type is what lets the adapter answer with that sentence.</p>
 *
 * <p>Alternatives Considered: raising the shared kernel's {@code RecordConflictException}, which the
 * shared advice already maps to 409. Rejected because that type carries a THREE-MEMBER condition
 * enumeration -- a stale version, an unavailable lock and a referenced row -- and the advice selects its
 * sentence from that enumeration with an exhaustive switch. None of the three describes a taken
 * identifier, so the response would have rendered one of three sentences about row contention in place of
 * the sentence the contract names. Widening that shared enumeration was rejected in turn: the condition
 * is specific to this one operation, and a fourth member would oblige every service that maps a conflict
 * to account for a case none of them can raise.</p>
 *
 * <p>Assumptions: the adapter answers this with a handler declared on itself rather than in the shared
 * advice, which is the same placement the sibling {@code AuthController} uses for its own refusal and for
 * the same reason: a controller-local handler is preferred over any advice for exceptions raised in that
 * controller, so the mapping is narrowed to the operations that can raise it instead of applying
 * service-wide.</p>
 *
 * <p>Assumptions: this condition is detected in two independent places and both raise this one type. The
 * local table may already hold the row, and the identity provider may already hold the account even when
 * the table does not -- the two stores can disagree, because a create that provisioned an identity and
 * then failed to insert leaves exactly that state behind. A caller is entitled to the same answer either
 * way, since from outside the identifier is taken in both.</p>
 *
 * <p>Trade-offs: the identifier is carried on the exception but is NOT rendered into the response body,
 * whose sentence is the fixed baseline literal. It is carried so the adapter can log which identifier
 * collided, which is what an operator needs; echoing it to the caller would add nothing, because the
 * caller submitted it and already knows it.</p>
 */
public class DuplicateUserException extends RuntimeException {

    /** The identifier that is already taken. */
    private final String userId;

    /**
     * Builds the refusal naming the identifier that is already taken.
     *
     * @param userId the submitted identifier a row or an identity already carries; must not be
     *     {@code null}
     * @param diagnostic a description of which store reported the collision, for the log alone; must not
     *     be {@code null}
     * @throws NullPointerException if {@code userId} or {@code diagnostic} is {@code null}
     */
    public DuplicateUserException(String userId, String diagnostic) {
        super(Objects.requireNonNull(diagnostic, "diagnostic must not be null"));
        this.userId = Objects.requireNonNull(userId, "userId must not be null");
    }

    /**
     * Returns the identifier that is already taken.
     *
     * @return the submitted identifier, never {@code null}
     */
    public String userId() {
        return this.userId;
    }
}
