package com.carddemo.common.error;

import java.util.Objects;

/**
 * Signals that a write was refused because the row it targeted is in contention, and names WHICH kind.
 *
 * <h2>The problem this exists for</h2>
 *
 * <p>Refactoring Rationale: the shared advice recognised three conflict conditions by walking the cause
 * chain for a persistence provider's own exception names, and answered each with the right sentence.
 * That works for the conditions a provider raises and cannot express two things the published contracts
 * promise. The first is the CURRENT version of the contended row: a caller told only that the record
 * changed has to re-read it to discover what it changed to, whereas a caller told the version it lost to
 * can decide immediately. The second is the SUBSYSTEM: the contracts declare an optimistic-lock refusal
 * as arising in the relational store, and every conflict the advice built went through a factory that
 * hardcodes the application subsystem, so the emitted body contradicted the document describing it.
 * Neither gap is fixable by recognising more provider exception names, because neither the version nor
 * the subsystem is recoverable from one.</p>
 *
 * <p>A service that knows it is in contention -- because it compared a version, or because it refused a
 * delete whose dependents still exist -- raises this instead, and the advice renders it with the right
 * sentence, the right subsystem and the version when there is one.</p>
 *
 * <h2>Why the kind is an enum rather than three types</h2>
 *
 * <p>Alternatives Considered: one exception type per condition, which would let the advice dispatch on
 * type and carry no switch. Rejected because the conditions share every component and differ only in
 * which sentence and which secondary code they select, so a type per condition would be one copy of one
 * shape per condition, and each further condition would then be a further class rather than a further
 * constant. The enum keeps the set closed and visible in one place, and because the advice selects the
 * sentence with a switch that yields a value and declares no default arm, the compiler requires it to
 * cover every member.</p>
 *
 * <p>Refactoring Rationale: this paragraph and the enumeration's own note below both said THREE, and the
 * enumeration has carried FOUR since {@link Kind#DUPLICATE_KEY} was added. The counts are not merely
 * restated here: they are removed from the argument, because the argument does not depend on how many
 * constants there are and a number written into it goes stale on every addition. What the argument does
 * depend on -- that the conditions share one shape and that the compiler enforces exhaustiveness -- is
 * stated directly instead.</p>
 *
 * <p>Assumptions: the sentences themselves are NOT held here. They live on
 * {@code GlobalExceptionHandler} beside the other user-visible strings, because they are message
 * constants carried across verbatim under transformation rule T8 and the migration keeps every such
 * string in one place per layer rather than distributing them among the types that trigger them.</p>
 */
public class RecordConflictException extends RuntimeException {

    /**
     * Serialisation identity for a throwable, which the platform requires to be declared.
     *
     * <p>Assumptions: fixed at one for the reason given on {@link ClientInputException}: a conflict is
     * rendered as JSON and never as a serialised Java object, so the identity satisfies the platform
     * rather than versioning a contract.</p>
     */
    private static final long serialVersionUID = 1L;

    /**
     * The closed set of contention conditions this migration distinguishes, currently four.
     *
     * <p>Assumptions: every member is a condition the baseline itself distinguishes, and each is cited
     * on its own constant below and again where the advice selects its sentence. They are not
     * interchangeable, and the remedy differs for all four:</p>
     *
     * <ul>
     *   <li>{@link #STALE_VERSION} — the caller must RE-READ the row and resubmit against the version it
     *       lost to, because the value it sent is no longer the stored one. Retrying the same body
     *       unchanged will be refused again.</li>
     *   <li>{@link #LOCK_UNAVAILABLE} — the caller should RETRY UNCHANGED, because nothing was compared
     *       and nothing was written; the body it sent is still the body it wants to send.</li>
     *   <li>{@link #REFERENCED_ROW} — the caller must DELETE THE DEPENDENTS or stop. No retry of the
     *       same delete can succeed while a referencing row exists, so retrying is the one wrong
     *       response.</li>
     *   <li>{@link #DUPLICATE_KEY} — the caller should RESUBMIT, because the key was derived by the
     *       service rather than supplied by the caller, so a fresh attempt derives a fresh key. This is
     *       the one condition whose remedy is a plain resubmission of an unchanged request that is
     *       expected to succeed.</li>
     * </ul>
     */
    public enum Kind {

        /**
         * A before-image or version comparison failed, so the row changed under the caller.
         *
         * <p>Assumptions: this is the migrated form of the pre-edit snapshot comparison at line 669 and
         * the change flag at lines 521 and 522 of {@code app/cbl/COACTUPC.cbl}.</p>
         */
        STALE_VERSION,

        /**
         * A row lock could not be obtained in time, so nothing was compared and nothing was written.
         *
         * <p>Assumptions: distinct from {@link #STALE_VERSION} because the baseline distinguishes them
         * -- lock acquisition at lines 517 to 520 of {@code app/cbl/COACTUPC.cbl} sits above the
         * comparison at 521 -- and a caller told the wrong one retries the wrong way.</p>
         */
        LOCK_UNAVAILABLE,

        /**
         * A delete was refused because rows in another table still reference the target.
         *
         * <p>Assumptions: this is the migrated form of the {@code ON DELETE RESTRICT} relationship that
         * preserves the baseline's own {@code XTRNTYCAT} constraint.</p>
         */
        REFERENCED_ROW,

        /**
         * An insert was refused because the key it carried is already stored.
         *
         * <p>Assumptions: this is the migrated form of the TWO duplicate conditions the baseline's two
         * transaction-writing screens each handle together. {@code app/cbl/COTRN02C.cbl} names the
         * duplicate-key condition at line 735 and the duplicate-record condition at line 736 and falls
         * through to one arm whose sentence is at line 738, and {@code app/cbl/COBIL00C.cbl} does the
         * same at lines 533, 534 and 536 with the identical sentence. One kind therefore covers both
         * conditions of both programs, because none of the four draws a distinction a caller could act
         * on.</p>
         *
         * <p>Assumptions: this is distinct from {@link #REFERENCED_ROW} even though the persistence
         * provider reports both as one integrity violation. A caller refused for a duplicate key acts by
         * submitting again, because the key is derived rather than supplied; a caller refused for a
         * dependent row acts by deleting the dependents or stopping. Answering the first with the
         * second's sentence would name child records to a caller that deleted nothing.</p>
         */
        DUPLICATE_KEY
    }

    /**
     * Which contention condition this refusal reports.
     */
    private final Kind kind;

    /**
     * The version the contended row currently holds, or {@code null} when there is none to report.
     */
    private final Long currentVersion;

    /**
     * Creates a conflict carrying no version, for a condition that has none.
     *
     * @param kind which contention condition is being reported; must not be {@code null}
     * @throws NullPointerException if {@code kind} is {@code null}
     */
    public RecordConflictException(Kind kind) {
        this(kind, null);
    }

    /**
     * Creates a conflict reporting the version the contended row currently holds.
     *
     * <p>Assumptions: the version is the row's CURRENT value rather than the one the caller sent, since
     * the caller already knows what it sent. Reporting the value it lost to is what lets a client decide
     * without a second read.</p>
     *
     * @param kind which contention condition is being reported; must not be {@code null}
     * @param currentVersion the version the row now holds, or {@code null} when the condition carries
     *     none -- a lock timeout compared nothing, and a restricted delete contends on a relationship
     *     rather than on a version
     * @throws NullPointerException if {@code kind} is {@code null}
     */
    public RecordConflictException(Kind kind, Long currentVersion) {
        // WHY : Assumptions: no message is composed here and none is passed to the supertype, so
        //       getMessage returns null. The sentence a client sees is selected by the advice from the
        //       kind, which keeps every user-visible string in one place under transformation rule T8;
        //       composing one here as well would create a second sentence for one condition and no rule
        //       for choosing between them.
        super();
        this.kind = Objects.requireNonNull(kind, "kind must not be null");
        this.currentVersion = currentVersion;
    }

    /**
     * Returns which contention condition this refusal reports.
     *
     * @return the kind supplied at construction, never {@code null}
     */
    public Kind kind() {
        return this.kind;
    }

    /**
     * Returns the version the contended row currently holds.
     *
     * @return the current version, or {@code null} when the condition carries none
     */
    public Long currentVersion() {
        return this.currentVersion;
    }
}
