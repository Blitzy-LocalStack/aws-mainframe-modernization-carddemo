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
 * <p>A service that knows it is in contention -- because it compared a version, because it refused a
 * delete whose dependents still exist, or because a table refused the insert it aimed there -- raises
 * this instead, and the advice renders it with the right sentence, the right subsystem, the table when
 * the condition names one and the version when there is one.</p>
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
     * The closed set of contention conditions this migration distinguishes.
     *
     * <p>Assumptions: every member is a condition the baseline itself distinguishes, and each is cited
     * on its own constant below and again where the advice selects its sentence. They are not
     * interchangeable, and the remedy differs for every one of them:</p>
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
     *   <li>{@link #INSERT_REFUSED} — the caller must CHANGE THE KEY it supplied, or address the row that
     *       already holds it through the replace route. Neither resubmitting nor deleting anything can
     *       help, because the key came from the caller rather than from the service.</li>
     * </ul>
     *
     * <p>Refactoring Rationale: the count of members used to be written into this paragraph and into the
     * sentence above it, and both went stale twice -- first when {@link #DUPLICATE_KEY} joined the set
     * and again when {@link #INSERT_REFUSED} did. The count is removed rather than corrected a third
     * time, because nothing the paragraph argues depends on it: what matters is that the set is closed
     * and that each member names a different remedy.</p>
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
        DUPLICATE_KEY,

        /**
         * An insert was refused by the integrity rules of the table it named, which the refusal carries.
         *
         * <p>Assumptions: this is the migrated form of the one failing arm of {@code 9700-INSERT-RECORD}
         * at lines 1607 to 1618 of {@code app/app-transaction-type-db2/cbl/COTRTUPC.cbl}. That paragraph
         * has a zero arm and a {@code WHEN OTHER} and nothing else, so EVERY non-zero outcome of the
         * insert -- a repeated primary key, a parent that is absent, a tablespace fault -- composes the
         * one sentence that names the table the insert was aimed at. The sentence reaches the screen: the
         * composition writes {@code WS-RETURN-MSG} after the failure condition has already put its own
         * text there, and line 1264 moves that field into the screen's error field.</p>
         *
         * <p>Assumptions: this is distinct from {@link #REFERENCED_ROW} because the baseline composes the
         * two in DIFFERENT paragraphs and the discriminator is the statement rather than the constraint.
         * The child-records sentence belongs to {@code 9800-DELETE-PROCESSING} at line 1641 and is
         * reached only from the SQLCODE that a restricted DELETE raises; no insert can reach it. Both
         * statements can be refused by the SAME foreign key -- from the parent side on a delete and from
         * the child side on an insert -- so classifying on the constraint alone told a caller who named a
         * parent that does not exist to go and delete dependent rows.</p>
         *
         * <p>Assumptions: this is distinct from {@link #DUPLICATE_KEY} because that condition's remedy is
         * a plain resubmission -- its key is derived by the service -- while a caller refused here
         * supplied the key itself and must change it. The two sentences are the baseline's own and belong
         * to different screens: the transaction-writing screens declare theirs at line 738 of
         * {@code app/cbl/COTRN02C.cbl}, and this one is declared in the reference-data maintenance
         * screen.</p>
         *
         * <p>Assumptions: this is the ONE member that carries a component beyond the kind, because its
         * sentence names the table and one renderer serves more than one of them. The table is supplied
         * through {@link #insertRefusedBy(String)} and read back through {@link #targetTable()}.</p>
         */
        INSERT_REFUSED
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
     * The table an insert was refused by, or {@code null} for every other condition.
     *
     * <p>Assumptions: this is the BASELINE's own table name rather than the migrated one, because the
     * sentence built around it is carried across verbatim under transformation rule T8 and the baseline
     * writes its own name into it. Keeping the baseline name has a second effect worth stating: no
     * response body discloses the schema or table names this migration actually created.</p>
     */
    private final String targetTable;

    /**
     * Creates a conflict carrying no version, for a condition that has none.
     *
     * @param kind which contention condition is being reported; must not be {@code null} and must not be
     *     {@link Kind#INSERT_REFUSED}, which names a table and is raised through
     *     {@link #insertRefusedBy(String)}
     * @throws NullPointerException if {@code kind} is {@code null}
     * @throws IllegalArgumentException if {@code kind} is {@link Kind#INSERT_REFUSED}
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
     * @param kind which contention condition is being reported; must not be {@code null} and must not be
     *     {@link Kind#INSERT_REFUSED}, which names a table and is raised through
     *     {@link #insertRefusedBy(String)}
     * @param currentVersion the version the row now holds, or {@code null} when the condition carries
     *     none -- a lock timeout compared nothing, and a restricted delete contends on a relationship
     *     rather than on a version
     * @throws NullPointerException if {@code kind} is {@code null}
     * @throws IllegalArgumentException if {@code kind} is {@link Kind#INSERT_REFUSED}
     */
    public RecordConflictException(Kind kind, Long currentVersion) {
        // WHY : Assumptions: no message is composed here and none is passed to the supertype, so
        //       getMessage returns null. The sentence a client sees is selected by the advice from the
        //       kind, which keeps every user-visible string in one place under transformation rule T8;
        //       composing one here as well would create a second sentence for one condition and no rule
        //       for choosing between them.
        super();
        Objects.requireNonNull(kind, "kind must not be null");
        // WHY : Trade-offs: the insert refusal is refused HERE rather than tolerated with a null table.
        //       The advice composes that condition's sentence around the table name, so a member reaching
        //       it without one would render the word "null" inside a user-visible string. Failing at the
        //       raise site instead means the mistake is a fault at the one line that made it, and the
        //       exception's own message names the factory that supplies the missing component.
        // WHY : Alternatives Considered: accepting the table through a second two-argument constructor
        //       taking (Kind, String). Declined because it would sit beside this one and make the literal
        //       expression (kind, null) ambiguous between them, turning a null table from a loud
        //       IllegalArgumentException into a compile error whose message names overload resolution
        //       rather than the rule being broken. The single-argument constructor below cannot collide
        //       with anything.
        if (kind == Kind.INSERT_REFUSED) {
            throw new IllegalArgumentException("Kind." + Kind.INSERT_REFUSED
                    + " names the table whose insert was refused, so it is raised through"
                    + " RecordConflictException.insertRefusedBy(String)");
        }
        this.kind = kind;
        this.currentVersion = currentVersion;
        this.targetTable = null;
    }

    /**
     * Creates the insert refusal for a named table, for a subclass that fixes the table it serves.
     *
     * <p>Assumptions: the kind is fixed rather than accepted, so this constructor cannot be used to raise
     * any other condition and the invariant that {@link Kind#INSERT_REFUSED} always carries a table holds
     * without a second check. It takes no version because an insert that was refused wrote nothing, so
     * there is no revision for a caller to compare against.</p>
     *
     * @param targetTable the baseline name of the table whose integrity rules refused the insert; must
     *     not be {@code null} or blank
     * @throws IllegalArgumentException if {@code targetTable} is {@code null} or blank
     */
    protected RecordConflictException(String targetTable) {
        super();
        if (targetTable == null || targetTable.isBlank()) {
            throw new IllegalArgumentException(
                    "targetTable must name the table whose insert was refused");
        }
        this.kind = Kind.INSERT_REFUSED;
        this.currentVersion = null;
        this.targetTable = targetTable;
    }

    /**
     * Creates the insert refusal for a named table.
     *
     * <p>Assumptions: this factory exists so that a service raising the condition directly, rather than
     * through a subclass of its own, states the table at the raise site and cannot omit it.</p>
     *
     * @param targetTable the baseline name of the table whose integrity rules refused the insert, for
     *     instance {@code TRANSACTION_TYPE}; must not be {@code null} or blank
     * @return the refusal to throw, carrying {@link Kind#INSERT_REFUSED} and that table; never
     *     {@code null}
     * @throws IllegalArgumentException if {@code targetTable} is {@code null} or blank
     */
    public static RecordConflictException insertRefusedBy(String targetTable) {
        return new RecordConflictException(targetTable);
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

    /**
     * Returns the baseline name of the table whose insert was refused.
     *
     * @return the table name for {@link Kind#INSERT_REFUSED}, and {@code null} for every other condition
     */
    public String targetTable() {
        return this.targetTable;
    }
}
