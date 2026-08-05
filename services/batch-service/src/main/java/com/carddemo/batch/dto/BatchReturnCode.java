package com.carddemo.batch.dto;

/**
 * The closed domain of a batch step's completion tier: three tiers, and no fourth.
 *
 * <h2>Purpose: one small number, and three separately authored consumers of it</h2>
 *
 * <p><b>Purpose.</b> This type models the outcome tiers a step of this module may finish in,
 * together with the exact number each tier is reported as. A tier declared here is not an internal
 * label. It leaves the container as a process exit status, it is read by the orchestration choice
 * that gates the following state, and it is recorded in the durable step ledger, so three artifacts
 * authored separately have to agree on one small set of numbers. Nothing in Java reports a
 * disagreement between them: the module compiles, the image builds, the task starts and finishes,
 * and the chain then continues or stops on the wrong runs. Giving the tiers one named type is what
 * makes that agreement statable, and testable, in a single place.</p>
 *
 * <p>Assumptions: the number is the contract, and the three consumers each read it as a number.
 * {@code com.carddemo.batch.BatchApplication} publishes the same three tiers as integer constants
 * because it has to hand one of them to the process exit call; the orchestration choice compares a
 * number; and {@code com.carddemo.batch.domain.BatchRun} persists one in its {@code return_code}
 * column. This type is the shared vocabulary for that number rather than a replacement for any of
 * the three, which is why it exposes the number through {@link #numericValue()} instead of hiding
 * it.</p>
 *
 * <h2>The three tiers, and the single job that can reach the middle one</h2>
 *
 * <p>Trade-offs: there are exactly three constants because the contract has exactly three tiers,
 * and the omissions cost this type the ability to describe a partial outcome. That cost is accepted
 * because the reference implementation emits no partial outcome to describe. A single statement in
 * the whole batch set assigns a non-zero code: {@code app/cbl/CBTRN02C.cbl:229} tests
 * {@code IF WS-REJECT-COUNT > 0} and line 230 reads {@code MOVE 4 TO RETURN-CODE}, against counters
 * declared at lines 185 and 186 as {@code WS-TRANSACTION-COUNT PIC 9(09) VALUE 0} and
 * {@code WS-REJECT-COUNT PIC 9(09) VALUE 0}. An intermediate tier added here would therefore be a
 * number no step can produce and no gate was written to interpret, and the first reader to find one
 * would reasonably assume some step produces it.</p>
 *
 * <p>Assumptions: <b>the middle tier belongs to transaction posting alone.</b> The other two batch
 * programs this type was derived alongside assign no return code at all -- searching
 * {@code app/cbl/CBACT04C.cbl}, 652 lines, for {@code RETURN-CODE} matches nothing, and searching
 * {@code app/cbl/CBTRN01C.cbl}, 494 lines, likewise matches nothing -- so interest accrual and the
 * pre-posting validation pass each finish either clean or abnormally, with nothing in between. A
 * middle-tier code arriving from any step other than posting is consequently a defect in that step
 * rather than a business outcome, and reading this type as though every job could report one would
 * turn that defect into an expected state.</p>
 *
 * <h2>The gate is a RUN predicate, and the sense is inverted exactly once</h2>
 *
 * <p>Refactoring Rationale: the mechanism that consumes this number is replaced, and the
 * replacement reads the comparison the opposite way round, which makes this the easiest error in
 * the translation to make and the hardest to notice. A job-control condition parameter is a SKIP
 * predicate: it states when the following step is to be bypassed. An orchestration choice is a RUN
 * predicate: it states when the following state is to be entered. The baseline's only gate with a
 * non-zero threshold is {@code app/jcl/TRANBKP.jcl:51}, which reads
 * {@code //STEP10 EXEC PGM=IDCAMS,COND=(4,LT)} -- "skip this step when 4 is less than the
 * accumulated code" -- so the step RUNS when the code is 4 or lower. What was wrong with carrying
 * the original spelling across is concrete rather than stylistic: a predicate written to mirror the
 * condition keyword would answer false for the middle tier, the state after a run that correctly
 * rejected transactions would be skipped, and the chain would stop -- on exactly those runs that
 * produced rejects and on no others. Every clean run would still look right, because a clean run
 * reports zero and satisfies both spellings. {@link #permitsDownstreamRun()} is therefore named and
 * written in the run sense, so the inversion is performed once, here, and is not available to be
 * performed again by a caller.</p>
 *
 * <p>Assumptions: <b>that gate is not the consumer of this type's middle tier, and the two must not
 * be conflated.</b> A condition parameter is evaluated only against the codes of earlier steps in
 * the same job and can observe nothing outside it, so {@code app/jcl/TRANBKP.jcl:51} gates its own
 * job's steps. It cannot observe posting, which runs in a different job entirely:
 * {@code app/jcl/POSTTRAN.jcl:23} reads {@code //STEP15 EXEC PGM=CBTRN02C} and carries no condition
 * parameter at all, so nothing in the baseline job control consumes posting's middle-tier code
 * downstream. The middle tier's authority is the PROGRAM's own contract at
 * {@code app/cbl/CBTRN02C.cbl:229-230}; {@code app/jcl/TRANBKP.jcl:51} is cited above solely as the
 * one place the baseline demonstrates the inverted SENSE of a threshold comparison, which is the
 * hazard being described. That its threshold coincides with posting's code is a coincidence, and
 * reading it as a data path would invent a dependency the baseline does not have.</p>
 *
 * <p>Assumptions: the clean tier's gate is the other condition form, and it needs no inversion
 * beyond the same rule. {@code COND=(0,NE)} reads "skip when 0 is not equal to the accumulated
 * code", so the step runs only when every predecessor finished clean; it appears at
 * {@code app/jcl/DEFGDGD.jcl:36}, {@code :47}, {@code :59} and {@code :82}, and at
 * {@code app/jcl/CREASTMT.JCL:56}, {@code :66} and {@code :79}. A state that must not run after a
 * middle-tier outcome therefore tests {@link #numericValue()} against zero rather than calling
 * {@link #permitsDownstreamRun()}, and the two gates stay distinguishable because this type never
 * collapses them into one predicate.</p>
 *
 * <p>Assumptions: one further condition form is deliberately absent from this type. A record
 * selection clause written inside a sort step -- {@code app/jcl/TRANREPT.jcl:47} is the instance --
 * shares the condition keyword but is not a step gate at all: it filters records, and it becomes a
 * query restriction rather than anything a completion tier can express. It is named here only so
 * that a reader who meets the shared keyword does not look for it among these three tiers.</p>
 *
 * <h2>Resolution clamps upward at the failure tier and rejects everything else</h2>
 *
 * <p>Assumptions: the failure tier is declared as "at or above 8" rather than "exactly 8", so
 * {@link #fromNumericValue(int)} maps every value at or above it onto the one failure constant. The
 * assumption this rests on is a property of the runtime rather than of the reference: a container
 * task that is terminated by the platform does not choose a tidy code on the way out, and a task
 * killed by a signal conventionally reports 128 plus the signal number. Requiring an exact match
 * would turn each of those into a rejected argument at precisely the moment the caller most needs a
 * tier to report, so the clamp is part of the contract and not defensive padding around it.</p>
 *
 * <p>Alternatives Considered: mapping every unrecognised value upward to the next tier at or above
 * it, so that nothing is ever rejected. Rejected, and the reason is specific to the values between
 * the tiers. Such a rule would send 1, 2 and 3 to the middle tier, and the middle tier satisfies
 * the run gate; a step that failed before it processed a single record, and reported one of those
 * values, would therefore be waved past the gate and the chain would continue as though rejects had
 * merely been written. Rejecting the value keeps that class of fault at the boundary where it
 * entered. The accepted cost is that a caller holding a genuinely unmodelled code has to handle an
 * exception rather than receive a tier, which is the outcome that stops a chain rather than
 * corrupting one.</p>
 *
 * <h2>Two other numeric scales in this repository are not this one</h2>
 *
 * <p>Alternatives Considered: modelling the reference program's internal file-status sentinels as
 * additional tiers here. Rejected, because they are not step outcomes. That program declares
 * {@code 01 APPL-RESULT PIC S9(9) COMP.} at {@code app/cbl/CBTRN02C.cbl:142} with condition names
 * at lines 143 and 144 covering a clean value and an end-of-file sentinel, and it assigns further
 * internal values while opening, reading and closing its files. Not one of those assignments
 * reaches {@code RETURN-CODE}: the only statement in all 731 lines that writes {@code RETURN-CODE}
 * is line 230. Promoting an internal control value to a completion tier would fabricate step
 * semantics the reference never publishes, and would do it in the one type the orchestration layer
 * trusts to enumerate them.</p>
 *
 * <p>Assumptions: the graded scale used by the parity oracle suite under {@code tests/} is a
 * separate scale with a separate meaning, and this type is not it. That suite grades its own runs
 * and treats a warn-level aggregate as its passing state, which is a rubric for judging a test run.
 * The Java build tooling here is binary -- the compiler, the documentation gate and the test runners
 * each pass or fail -- so borrowing that rubric to describe a build would let a real failure read as
 * a pass. The tiers below describe a business step's outcome, reported to an orchestrator as a
 * process exit status, and nothing about the grading of a test run may be read into them.</p>
 *
 * <h2>A completion tier is not a lifecycle state</h2>
 *
 * <p>Alternatives Considered: one type carrying both the numeric tier and the step's lifecycle
 * state. Rejected, because they are independent axes and
 * {@code com.carddemo.batch.domain.BatchRun} already owns the second one as a nested enumeration of
 * its own. A lifecycle state records how far a step got -- opened, finished, ended badly -- and
 * exists from the moment the step opens, when no tier is known yet. A tier records what a finished
 * step is reporting. Merging them would make an impossible pair representable, an opened step
 * already carrying a clean tier being the obvious one, and it would give the ledger two columns
 * whose disagreement nothing could resolve. The two types are related by the ledger's own
 * transitions rather than by inheritance: that entity admits only the clean and middle tiers on the
 * transition it treats as a finish, and requires the failure tier or nothing at all on the
 * transition it treats as a bad end, which is the same partition {@link #permitsDownstreamRun()}
 * draws.</p>
 *
 * <h2>Declaration order, and why {@link #ordinal()} is not part of the contract</h2>
 *
 * <p>Assumptions: the constants are declared in ascending order of the number they report, so the
 * file reads in the same direction as the gate that compares them. The cost of encoding an order in
 * a declaration is that the order becomes reachable as a number that means something else:
 * <b>{@link #ordinal()} is NOT part of any contract this type carries, and must never be persisted,
 * reported as a process exit status, written into an orchestration definition or compared against a
 * gate threshold.</b> The three ordinals are 0, 1 and 2 while the three contract numbers are 0, 4
 * and 8, so an ordinal reaching a gate written for the contract would place the failure tier at 2,
 * inside the range the gate admits. {@link #numericValue()} is the only number this type offers to
 * anything outside it, and it is offered precisely so that no caller has to reach for the
 * ordinal.</p>
 *
 * <h2>What this type deliberately does not have</h2>
 *
 * <p>Alternatives Considered: a second predicate answering whether a step finished clean. Rejected
 * for now, on the ground that it would be a second way to say something already expressible: a
 * caller needing that question compares the value against this type's clean constant directly, and
 * the comparison reads no worse than a call would. The predicate that IS provided earns its place
 * differently -- it encodes the inverted sense described above, which a caller cannot be expected to
 * re-derive correctly, and getting it wrong is silent. A member is added here when it carries a
 * decision, not when it merely wraps one.</p>
 *
 * <p>Alternatives Considered: a default or unknown constant, so that an unmodelled number would
 * still resolve to something. Rejected, because there is no tier that is legitimately the right
 * answer when the number is not one the contract defines, and a fallback would report a definite
 * outcome for an indefinite one. {@link #fromNumericValue(int)} raises instead, and this type has no
 * member that could serve as a fallback.</p>
 *
 * <p>Assumptions: this file declares no import at all, and the absence is a decision rather than an
 * accident of a small type. Everything it needs is an integer and the enumeration facilities the
 * language supplies. The charter of this package fixes the import discipline as an absolute -- no
 * other service module's domain package, no cloud provider software development kit type, no web or
 * servlet type, no batch framework internal -- and two further exclusions are worth naming because
 * this type sits so close to them. The interface that turns a result into a process exit status
 * belongs to the module's own entry point, which owns that translation, so importing it here would
 * make a transfer shape depend on the application entry point. And this module publishes no route,
 * so no request-body binding, response wrapper or interface-documentation annotation has a consumer
 * on this type. Cross-references in this file are written as code text rather than as resolved links
 * for the same reason.</p>
 *
 * <h2>Baseline lineage: provenance only</h2>
 *
 * <p>The citations in this file are provenance. Nothing under {@code app/**} is read at run time,
 * and nothing under it is altered by this migration -- the reference implementation is the
 * behavioural oracle and stays byte-identical. Where migrated behaviour differs from the reference,
 * the reference does one thing, the Java does another, and the divergence is registered in
 * {@code docs/architecture/cobol-to-service-traceability.md}. No claim is made anywhere in this file
 * that the reference itself was altered, because it was not. Line numbers refer to the source as
 * committed, and columns 73 to 80 of a COBOL or job-control line carry a sequence field that is not
 * part of the statement.</p>
 *
 * <h2>Parameters, return values and exceptions at type level: declared inapplicable</h2>
 *
 * <p>An enumeration declaration accepts no parameter, yields no value and raises nothing, so this
 * block carries no parameter, return or exception at-clause, and no authorship, availability or
 * revision at-clause either. The inapplicability is stated rather than left silent because the
 * project's single user-specified rule, Explainability, names at its line 39 a docstring that omits
 * parameters, return values or purpose among its forbidden patterns, and a reader has to be able to
 * tell a declared inapplicability from an oversight. The three elements that do apply to a type are
 * discharged above; the fourth is discharged on each member below.</p>
 */
public enum BatchReturnCode {

    /**
     * The step finished with nothing to report, reported as zero.
     *
     * <p>Authority: the implicit alternative at {@code app/cbl/CBTRN02C.cbl:229}. That line tests
     * {@code IF WS-REJECT-COUNT > 0} and the assignment it guards is the only one in the program, so
     * a run whose reject counter stayed at its declared initial value of zero leaves the code
     * untouched and finishes here. Both of the other programs cited on this type finish here on
     * every successful run, having no statement that could report anything else.</p>
     *
     * <p>Assumptions: this is the only tier the stricter of the two baseline gate forms admits.
     * {@code COND=(0,NE)} at {@code app/jcl/DEFGDGD.jcl:36} and at
     * {@code app/jcl/CREASTMT.JCL:56} runs its step only when every predecessor finished clean, so a
     * state carrying that requirement is gated on this constant specifically and not on
     * {@link #permitsDownstreamRun()}, which also admits the tier below.</p>
     */
    CLEAN(0),

    /**
     * The step did its work, rejected at least one record, and the following state may still run,
     * reported as four.
     *
     * <p>Authority: {@code app/cbl/CBTRN02C.cbl:229-230}, where {@code IF WS-REJECT-COUNT > 0}
     * selects {@code MOVE 4 TO RETURN-CODE}. This is the only statement in the batch set that
     * assigns a non-zero code, and the tier it creates is the reason this type cannot be a boolean:
     * a run that correctly rejected transactions has done its job and must not read as a failure,
     * while the following state still needs to know that rejects were written.</p>
     *
     * <p>Assumptions: transaction posting is the only step that can legitimately report this tier,
     * for the reason recorded on this type -- neither of the other two programs cited contains a
     * {@code RETURN-CODE} statement anywhere. This tier arriving from any other step is a defect in
     * that step, not an outcome to be interpreted.</p>
     */
    SOFT_WARN(4),

    /**
     * The step did not complete its work and the following state must not run, reported as eight.
     *
     * <p>Authority: the reference program's abnormal-termination paragraph at
     * {@code app/cbl/CBTRN02C.cbl:707-711}, which displays {@code 'ABENDING PROGRAM'}, moves
     * {@code 999} into its abend code and calls the environment's abend service. An abnormal
     * termination is not a code the program assigns -- it never reaches the assignment at line 230 --
     * so the migrated equivalent is this tier rather than a number transcribed from that
     * paragraph.</p>
     *
     * <p>Assumptions: the tier is declared as "at or above eight" and eight is its canonical
     * representative, which is why {@link #fromNumericValue(int)} clamps rather than matching
     * exactly. Eight is also the lowest value the run gate refuses, so it is the smallest number
     * that can carry this meaning at all.</p>
     */
    HARD_FAILURE(8);

    /**
     * The number this tier is reported as, exactly as the orchestration gate and the step ledger
     * read it.
     *
     * <p>Assumptions: the field is final and holds a primitive, so a constant cannot be re-pointed
     * at a different number after class initialisation and every reader of {@link #numericValue()}
     * sees the same value for the life of the process. Holding the number in a field populated from
     * each constant's argument list, rather than deriving it from {@link #ordinal()} by arithmetic,
     * is what keeps the declared numbers 0, 4 and 8 rather than 0, 1 and 2: no arithmetic on the
     * ordinal produces the contract's numbers, and one that produced two of them would fail on the
     * third while still compiling.</p>
     */
    private final int numericValue;

    /**
     * Binds one constant to the number it is reported as.
     *
     * @param numericValue the number for this constant, supplied as a literal in the constant's own
     *     argument list above and required to equal the corresponding tier published by
     *     {@code com.carddemo.batch.BatchApplication} and accepted by the {@code return_code} column
     *     of {@code com.carddemo.batch.domain.BatchRun}
     */
    private BatchReturnCode(int numericValue) {
        // WHY : Trade-offs: the argument is stored as received, with no range check and no
        //       normalisation. A check here could only compare the literal against another literal in
        //       the same file, so it would restate the declaration rather than test it, and it would
        //       run during class initialisation where a failure surfaces as an initialisation error
        //       rather than as a readable message. The three literals sit a few lines apart in one
        //       file, so a mismatch is visible to a reader, and it is additionally asserted by test
        //       against the tiers the entry point publishes.
        this.numericValue = numericValue;
    }

    /**
     * Returns the number this tier is reported as.
     *
     * @return the contract number for this tier: zero for a clean finish, four for a finish that
     *     produced rejects and eight for a failure; never the {@link #ordinal()}, which is not part
     *     of any contract this type carries
     */
    public int numericValue() {
        return numericValue;
    }

    /**
     * Answers whether a step reporting this tier permits the following state to run.
     *
     * <p>The answer is true for a clean finish and for a finish that produced rejects, and false for
     * a failure. Expressed as a comparison, that is the number being four or lower, which is the
     * predicate the orchestration choice evaluates.</p>
     *
     * @return {@code true} when the following state may run, which is the case for every tier at or
     *     below four; {@code false} for the failure tier, on which the state's catch handler routes
     *     to failure notification instead
     */
    public boolean permitsDownstreamRun() {
        // WHY : Assumptions: this predicate is written in the RUN sense, and the baseline's
        //       equivalent is written in the SKIP sense, so the comparison is inverted here exactly
        //       once. The baseline's only non-zero threshold is
        //       app/jcl/TRANBKP.jcl:51, //STEP10 EXEC PGM=IDCAMS,COND=(4,LT), which skips its step
        //       when 4 is less than the accumulated code and therefore runs it when the code is 4 or
        //       lower. An orchestration choice states when a state RUNS, so the same rule is spelled
        //       as the comparison below.
        // WHY : Refactoring Rationale: mirroring the condition keyword instead of its meaning is the
        //       specific error this method exists to prevent. A body written to skip on 4 or lower
        //       would answer false for the middle tier, the state after a run that correctly rejected
        //       transactions would be bypassed, and the chain would stop -- on exactly those runs
        //       that produced rejects and on no others, while every clean run continued to look
        //       correct because a clean run reports zero either way. Performing the inversion here,
        //       behind a name that states the run sense, is what stops a caller re-deriving it and
        //       getting it backwards a second time.
        return numericValue <= SOFT_WARN.numericValue;
    }

    /**
     * Resolves a number reported by a finished step to the tier it belongs to.
     *
     * <p>Zero and four resolve to their own tiers. Every value at or above eight resolves to the
     * failure tier, because that tier is declared as a threshold rather than as a single value.
     * Nothing else resolves.</p>
     *
     * @param candidateValue the number to resolve, as reported by a finished step through its
     *     process exit status; values at or above the failure tier are all accepted and all resolve
     *     to that one tier, while a value strictly between two tiers, or below zero, is accepted as
     *     input and rejected as a tier because no tier claims it
     * @return the tier the number belongs to, never {@code null}
     * @throws IllegalArgumentException if {@code candidateValue} is neither zero nor four nor at
     *     least eight; the message names the offending value and the tiers that exist, so that a
     *     container log identifies the unmodelled number without access to this source
     */
    public static BatchReturnCode fromNumericValue(int candidateValue) {
        // WHY : Assumptions: the failure tier is a threshold, not a value, so it is tested first and
        //       with a relational comparison rather than by equality. A step does not always choose
        //       the number it exits with: a task terminated by the platform reports 128 plus the
        //       signal number by convention, so 137 and 139 are ordinary sightings and each has to
        //       land on the failure tier. Requiring an exact match would reject the argument at the
        //       moment the caller most needs a tier, and a caller handling that rejection would have
        //       to re-implement this clamp to recover.
        if (candidateValue >= HARD_FAILURE.numericValue) {
            return HARD_FAILURE;
        }
        for (BatchReturnCode tier : values()) {
            if (tier.numericValue == candidateValue) {
                return tier;
            }
        }
        // WHY : Alternatives Considered: mapping a value between the tiers upward to the next tier
        //       instead of raising. Rejected, because the next tier above 1, 2 or 3 is the middle
        //       one, and the middle tier PASSES the run gate: a step that failed before processing a
        //       single record and reported one of those numbers would be waved downstream as though
        //       it had merely written rejects. Raising keeps that fault at the boundary it entered.
        //       Returning null or an empty optional was rejected too -- both push the same decision
        //       onto every caller, and a caller that forgot to make it would fail further from the
        //       unmodelled number that caused it.
        StringBuilder accepted = new StringBuilder();
        for (BatchReturnCode tier : values()) {
            if (accepted.length() > 0) {
                accepted.append(", ");
            }
            accepted.append(tier.name()).append('=').append(tier.numericValue);
        }
        throw new IllegalArgumentException("unmodelled batch return code: " + candidateValue
                + "; expected " + accepted + ", or any value at or above "
                + HARD_FAILURE.numericValue + " for " + HARD_FAILURE.name());
    }
}
