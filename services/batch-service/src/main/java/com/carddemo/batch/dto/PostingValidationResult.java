package com.carddemo.batch.dto;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The outcome of validating one daily transaction: at most one reject reason, and the projected
 * balance that decided the credit-limit test.
 *
 * <h2>Purpose: this type OWNS the reject-reason precedence, it does not merely describe it</h2>
 *
 * <p><b>Purpose.</b> This record carries what validating one daily transaction concluded -- whether
 * the transaction may post, which single reason refused it if one did, and the projected balance the
 * credit-limit comparison was made against. Its reason for existing is narrower and sharper than
 * "hold two values": {@link #resolve(boolean, boolean, boolean, boolean, BigDecimal)} is the ONLY
 * place in this codebase that decides WHICH failure is reported when a transaction fails more than
 * one condition. The service that validates a transaction reports which conditions it found to have
 * failed; this type decides which of those failures survives. A caller therefore cannot invert the
 * precedence, because a caller never selects a winner.</p>
 *
 * <p>Refactoring Rationale: the precedence lives here rather than in
 * {@code com.carddemo.batch.service.PostingValidationService} because the reference program
 * expresses it as an ABSENCE rather than as a statement. There is no line in
 * {@code app/cbl/CBTRN02C.cbl} that says one reason beats another; there is only a guard that is
 * missing between two assignments. A missing line has nothing to transcribe, so it survives
 * translation only if somebody states it deliberately, and a rule restated at each call site is a
 * rule that will eventually be restated wrongly at one of them. Centralising it in a single factory
 * reduces the number of places the rule can be got wrong from "every caller" to one.</p>
 *
 * <h2>At most ONE reason, and never a collection of them</h2>
 *
 * <p>The reference records a refusal in a single field. {@code app/cbl/CBTRN02C.cbl:180-182}
 * declares the trailer as {@code WS-VALIDATION-FAIL-REASON PIC 9(04)} followed by
 * {@code WS-VALIDATION-FAIL-REASON-DESC PIC X(76)} -- one code and one description, with no
 * {@code OCCURS} and no table. When two conditions hold, the second assignment overwrites the first
 * and only the survivor is ever recorded.</p>
 *
 * <p>Alternatives Considered: carrying every failed condition, so that a caller could report all of
 * them. Rejected because it would make a state representable that the reference cannot produce and
 * that the reject stream cannot encode: the 80-character trailer at
 * {@code app/cbl/CBTRN02C.cbl:178} has room for exactly one code and one description, so a second
 * reason would have nowhere to go at the moment the record is written. The committed expectation
 * files of the functional-parity oracle would never observe such a state either, which is the worse
 * half of the objection -- an unobservable state is a state that diverges silently.</p>
 *
 * <h2>Acceptance is the ABSENCE of a reason, not a sentinel reason</h2>
 *
 * <p>A transaction that failed nothing carries no reason at all. {@link #isAccepted()} answers
 * whether that is the case and {@link #rejectReason()} yields an empty result.</p>
 *
 * <p>Assumptions: the reference resets the trailer before every record, at
 * {@code app/cbl/CBTRN02C.cbl:208-209}, moving zero into the code and spaces into the description,
 * and then selects posting over rejection on that zero at {@code :211}. Acceptance is therefore
 * "the field holds no reason" rather than "the field holds a reason meaning acceptance", and
 * {@link RejectReason} deliberately declares no zero-valued member so that the distinction cannot be
 * blurred. Modelling acceptance as a sentinel constant here would reintroduce exactly what that
 * decision was taken to avoid, because every member of {@link RejectReason} answers a question that
 * has no meaning for a transaction that posted.</p>
 *
 * <h2>103 beats 102, because the reference has no guard between the two assignments</h2>
 *
 * <p><b>When a transaction is both over its credit limit and past its account expiration, the
 * reference reports 103 and not 102.</b> Inside the {@code NOT INVALID KEY} branch of the account
 * read, the credit-limit test at {@code app/cbl/CBTRN02C.cbl:407} and the expiration test at
 * {@code :414} are two sequential, independent {@code IF} blocks. The first assigns 102 at
 * {@code :410}; the first block closes at {@code :413}; the second block opens immediately at
 * {@code :414} and assigns 103 at {@code :417}. Nothing between them tests whether a reason has
 * already been assigned, so the later assignment overwrites the earlier one.</p>
 *
 * <p>Assumptions: the absence of that guard is a positive finding rather than a failure to look. The
 * text {@code IF WS-VALIDATION-FAIL-REASON = 0} occurs exactly twice in the 731 lines of that
 * program -- at {@code :211}, where it selects posting over rejection for the record as a whole, and
 * at {@code :372}, where it stops the account lookup running at all once the cross-reference lookup
 * has failed. There is no third occurrence, and in particular none between {@code :413} and
 * {@code :414}.</p>
 *
 * <p>Alternatives Considered: writing the boundary pair in Java as a single either-or, in the order
 * the two tests are read in the source. That is the shape a naive transcription produces and it is
 * wrong in a specific, measurable way -- it reports 102 for a transaction that fails both, where the
 * reference reports 103. The reject stream is compared byte for byte against committed expectation
 * files, so the divergence appears only on transactions that trip BOTH conditions and never on
 * transactions that trip one, which is precisely why it survives casual testing: every
 * single-condition scenario continues to agree.</p>
 *
 * <p>Assumptions: the precedence is NOT {@link Enum#ordinal()} and is not a comparison of reason
 * codes. It is the order the reference's own control flow reaches the assignments in, which
 * {@link RejectReason} records separately and exposes through
 * {@link RejectReason#baselineAssignmentOrder()}. This type resolves by selecting the maximum under
 * that ordering rather than by branching, so reordering the constants in {@link RejectReason} for
 * readability cannot change which reason is reported here.</p>
 *
 * <h2>Only {102, 103} can co-occur, which is what makes the rule small</h2>
 *
 * <p>The precedence question is a two-way question rather than a four-way one, because the
 * reference's structure makes the other reasons mutually exclusive:</p>
 *
 * <ul>
 *   <li>100 excludes everything downstream. The guard at {@code app/cbl/CBTRN02C.cbl:372} performs
 *       the account lookup only while no reason has been assigned, so a failed cross-reference read
 *       ends validation.</li>
 *   <li>101 excludes both 102 and 103. It is assigned in the {@code INVALID KEY} branch of the
 *       account read, while both boundary tests sit in the {@code NOT INVALID KEY} branch of that
 *       same read, so a missing account leaves neither balance nor date evaluated.</li>
 *   <li>102 and 103 CAN both hold, and they are exactly the pair with no guard between them.</li>
 * </ul>
 *
 * <p>Assumptions: stating the exclusivity is what keeps
 * {@link #resolve(boolean, boolean, boolean, boolean, BigDecimal)} honest and small. There is one
 * pair to decide, the reference decides it, and the resolution is a transcription rather than a
 * policy invented here.</p>
 *
 * <h2>Both boundaries are INCLUSIVE on the passing side, so equality posts</h2>
 *
 * <p>Each boundary is written in the reference as a PASS guard using {@code &gt;=}, so each reject
 * predicate is the strict complement. Both framings are given together every time either is
 * mentioned, because they are inverted in the retelling with striking regularity:</p>
 *
 * <ul>
 *   <li><b>Credit limit.</b> {@code app/cbl/CBTRN02C.cbl:407} reads
 *       {@code IF ACCT-CREDIT-LIMIT &gt;= WS-TEMP-BAL} and PASSES on that comparison. A projection
 *       landing exactly ON the limit therefore POSTS, and 102 is reported only when the projection
 *       is STRICTLY GREATER than the limit. One cent over rejects; the limit itself does not.</li>
 *   <li><b>Expiration.</b> {@code app/cbl/CBTRN02C.cbl:414} reads
 *       {@code IF ACCT-EXPIRAION-DATE &gt;= DALYTRAN-ORIG-TS (1:10)} and PASSES on that comparison.
 *       A transaction dated exactly ON the expiration date therefore POSTS, and 103 is reported only
 *       when the expiration date is STRICTLY EARLIER than the transaction date. The field name is
 *       misspelled in the reference and is cited above as it is spelled there.</li>
 * </ul>
 *
 * <p>Assumptions: both senses are corroborated independently of this file by the functional-parity
 * oracle. {@code tests/golden/posting/boundary_exact_limit/dalyrejs.expected} and
 * {@code tests/golden/posting/boundary_expiry_equal/dalyrejs.expected} are each EMPTY, which is
 * consistent only with the inclusive reading of both guards; had either boundary been exclusive,
 * each would hold one 430-character reject record instead.</p>
 *
 * <h2>The projected balance comes from the CYCLE accumulators, never the current balance</h2>
 *
 * <p>{@code app/cbl/CBTRN02C.cbl:403-405} forms the quantity the credit-limit test compares:</p>
 *
 * <pre>{@code COMPUTE WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT
 *                     - ACCT-CURR-CYC-DEBIT
 *                     + DALYTRAN-AMT}</pre>
 *
 * <p>Assumptions: <b>the two operands are the cycle accumulators, and
 * {@code ACCT-CURR-BAL} appears nowhere in that computation.</b> The account record declares all
 * three fields, at {@code app/cpy/CVACT01Y.cpy:7}, {@code :13} and {@code :14}, so reaching for the
 * current balance instead compiles perfectly well and yields rejections that look entirely
 * reasonable while being wrong on every account whose cycle totals differ from its balance. Naming
 * the distinction here is the only defence available to this type, which receives the projection
 * rather than computing it.</p>
 *
 * <p>Trade-offs: carrying the projection at all is a deliberate cost. No column anywhere stores it
 * -- it is a computed intermediate, formed during validation and discarded once the outcome is
 * known -- so this component widens the record by a value that has no counterpart in the schema. It
 * is carried because it is the value the credit-limit decision turned on, which makes a 102 auditable
 * against the account and the transaction it was formed from; the alternative, recomputing it from
 * those two wherever it is wanted, would duplicate the very formula the paragraph above exists to
 * protect.</p>
 *
 * <p>Refactoring Rationale: this paragraph also offered the projection as something "meaningful to
 * log", and that clause is withdrawn rather than softened. The projection is a monetary amount, and
 * the disclosure rule this migration works to -- the rendering section of
 * {@code docs/architecture/observability.md} -- puts a monetary amount in the OMITTED class rather
 * than the abbreviated one: there is no width, no rounding and no digest of a balance that is safe to
 * emit, because a projection is a balance plus a transaction and either one is recoverable from it
 * given the other. Recommending it as log material invited exactly the disclosure {@link #toString()}
 * below exists to prevent, and a rationale that argues for the opposite of the code beneath it is
 * worse than no rationale, because a later reader trusts it. Auditability is served instead by the
 * reject stream, which records the reason and the 350-character transaction image under the same
 * access controls as the ledger itself.</p>
 *
 * <h2>Fixed point, at scale 2, and nothing else</h2>
 *
 * <p>Assumptions: the projection is a {@link BigDecimal} whose scale is exactly
 * {@link #PROJECTED_BALANCE_SCALE}. Its reference declaration is
 * {@code WS-TEMP-BAL PIC S9(09)V99} at {@code app/cbl/CBTRN02C.cbl:187}, which is nine integer
 * digits and two decimal places, and which the migration plan maps to {@code NUMERIC(11,2)}. The
 * plan's transformation rule T3 forbids the money path leaving exact fixed point at any hop, and a
 * comparison against a credit limit is a hop: a projection carried in a binary radix cannot represent
 * every two-place decimal exactly, so it can land one representable step either side of the limit,
 * which is exactly the distinction the inclusive guard at {@code :407} turns on. The prohibition on
 * the inexact binary types is not left to good intentions -- it is asserted mechanically by the
 * architecture rules in {@code common-lib}.</p>
 *
 * <h2>The 80-character trailer, and the 430-character record it sits in</h2>
 *
 * <p>{@link #trailerField()} renders the trailer this outcome contributes to a reject record: four
 * characters of zero-padded code followed by 76 characters of space-padded description, 80 in all.
 * The widths are declared at {@code app/cbl/CBTRN02C.cbl:181-182} and their sum is the
 * {@code VALIDATION-TRAILER PIC X(80)} of {@code :178}, which follows
 * {@code REJECT-TRAN-DATA PIC X(350)} at {@code :177} to make the 430-character record that
 * {@code app/jcl/POSTTRAN.jcl:36} allocates as {@code LRECL=430}.</p>
 *
 * <p>Assumptions: this type contributes the 80-character trailer and NOT the 350-character
 * transaction image that precedes it. The reference builds the record from two separate moves, at
 * {@code app/cbl/CBTRN02C.cbl:447-448}: the image is moved from the daily-transaction record and the
 * trailer from the validation fields. The image is the writer's to supply and
 * {@code com.carddemo.batch.domain.TransactionReject} is what persists it, so no part of it is
 * modelled here -- which is also why no card number and no account identifier appears on this
 * record, and therefore why no masking question arises for it.</p>
 *
 * <p>Assumptions: the absence of an identifier does NOT make this record safe to render. It carries a
 * monetary amount, which the disclosure rule treats exactly as it treats an identifier, so the
 * rendering question this type has to answer is about the projection rather than about a card number.
 * {@link #toString()} answers it.</p>
 *
 * <h2>109 is a write failure, so this type can never report it</h2>
 *
 * <p>Assumptions: the reference assigns reason 109 at {@code app/cbl/CBTRN02C.cbl:556}, in the
 * {@code INVALID KEY} branch of the account REWRITE inside {@code 2800-UPDATE-ACCOUNT-REC}. That
 * paragraph is reached only from {@code :441}, inside the posting paragraph, which {@code :212}
 * enters only when validation assigned no reason at all. It is therefore not a validation outcome in
 * any sense -- it describes a transaction that passed validation, posted, and then could not be
 * recorded. This record refuses it rather than silently accepting it, and the refusal is expressed
 * through {@link RejectReason#isPersistedToRejectStream()} rather than by testing for the number
 * 109, so the two files cannot drift apart.
 * {@code com.carddemo.batch.domain.TransactionReject} independently declares the persisted domain as
 * exactly {@code {100, 101, 102, 103}}, and no committed expectation file under
 * {@code tests/golden/posting/} carries {@code 0109} in the trailer's code position.</p>
 *
 * <h2>Value equality, and the reference this type is measured against</h2>
 *
 * <p>Assumptions: value equality is the contract. Two outcomes are equal when they carry the same
 * reason and the same projection, which the record's generated {@code equals} and {@code hashCode}
 * give directly; nothing here has an identity worth distinguishing, and the accepted outcome is
 * produced fresh rather than shared so that its projection can differ between transactions.</p>
 *
 * <p>Baseline paths above are cited for provenance only. Nothing under {@code app/**} is read at run
 * time and nothing under it is altered by this migration: the reference is the behavioural oracle and
 * stays byte-identical. Where this type's behaviour departs from the reference, the reference does one
 * thing, this type does another, and the divergence is registered in
 * {@code docs/architecture/cobol-to-service-traceability.md}. Line numbers refer to the source as
 * committed, and columns 73 to 80 of a COBOL or JCL line carry a sequence field that is not part of
 * the statement. No authorship, availability or revision at-clause appears in this file, matching the
 * rest of this package.</p>
 *
 * @param reason the single reason that refused the transaction, or {@code null} when the transaction
 *     failed no condition and may post; a caller reading this component directly has to handle that
 *     {@code null}, which is why {@link #rejectReason()} exists as the total view of the same value,
 *     and which is never {@link RejectReason#ACCOUNT_NOT_FOUND_ON_REWRITE} because that reason
 *     describes a failed write rather than a failed validation
 * @param projectedCycleBalance the balance the credit-limit test was made against, formed as the
 *     reference forms it at {@code app/cbl/CBTRN02C.cbl:403-405} from the cycle credit total minus
 *     the cycle debit total plus the transaction amount, held at exactly
 *     {@link #PROJECTED_BALANCE_SCALE} decimal places; {@code null} when validation ended before the
 *     account was read and no projection could exist, which is exactly the outcomes whose reason
 *     reports {@link RejectReason#terminatesValidation()}
 */
public record PostingValidationResult(RejectReason reason, BigDecimal projectedCycleBalance) {

    /**
     * The exact number of decimal places the projected balance is held at, two.
     *
     * <p>Assumptions: the scale comes from {@code WS-TEMP-BAL PIC S9(09)V99} at
     * {@code app/cbl/CBTRN02C.cbl:187}, whose {@code V99} is two decimal places, and it is asserted
     * as an EXACT scale rather than a maximum. A value at scale 0 or scale 1 is rejected rather than
     * widened, and a value at scale 3 is rejected rather than rounded, because rounding a projection
     * that has already been computed would move the very quantity the inclusive limit guard at
     * {@code :407} compares -- and it would do so inside a constructor, where the adjustment would be
     * invisible at the call site that produced the wrong scale.</p>
     */
    public static final int PROJECTED_BALANCE_SCALE = 2;

    /**
     * The four characters the reason-code field carries for a transaction that failed no condition.
     *
     * <p>Assumptions: the reference resets the code to zero at
     * {@code app/cbl/CBTRN02C.cbl:208}, and the field is
     * {@code WS-VALIDATION-FAIL-REASON PIC 9(04)} at {@code :181}, so numeric-display zero in four
     * characters renders as four zero digits rather than as blanks. This is the sentinel form only;
     * see {@link #trailerField()} for why it is never emitted.</p>
     */
    private static final String ACCEPTED_CODE_FIELD = "0000";

    /**
     * Checks the two components against the three invariants this outcome is only meaningful under.
     *
     * <p>The reason, when present, has to be one the reference can actually write to the reject
     * stream; the projection, when present, has to be at exactly
     * {@link #PROJECTED_BALANCE_SCALE} decimal places; and the projection has to be present in
     * exactly those outcomes where the reference could have computed one.</p>
     *
     * @throws IllegalArgumentException if {@code reason} is a reason the reference assigns outside
     *     validation and therefore cannot write to the reject stream, if
     *     {@code projectedCycleBalance} is present at any scale other than
     *     {@link #PROJECTED_BALANCE_SCALE}, if {@code projectedCycleBalance} is present on an outcome
     *     whose reason ended validation before the account was read, or if
     *     {@code projectedCycleBalance} is absent on an outcome that reached the boundary tests
     */
    public PostingValidationResult {

        // WHY : Assumptions: 109 is barred here rather than left to a caller's discretion, because
        //       app/cbl/CBTRN02C.cbl:556 assigns it in the INVALID KEY branch of the account REWRITE
        //       inside 2800-UPDATE-ACCOUNT-REC, which :441 performs from the posting paragraph and
        //       which :212 enters only when validation assigned no reason at all. An outcome of
        //       validation therefore cannot carry it, and admitting it would let a value reach the
        //       reject writer that the reference never emits -- no committed expectation file under
        //       tests/golden/posting/ holds 0109 in the trailer's code position.
        // WHY : Alternatives Considered: testing for the literal 109 here. Rejected because the set
        //       of reasons that can reach the reject stream is already stated once, on
        //       RejectReason.isPersistedToRejectStream(), and a second statement of the same set in
        //       this file could disagree with the first without anything failing. Asking the
        //       predicate keeps one definition; a future reason assigned off the validation path is
        //       excluded here automatically rather than by remembering to edit this line.
        if (reason != null && !reason.isPersistedToRejectStream()) {
            throw new IllegalArgumentException(
                    "reason " + reason.code() + " is assigned outside validation and cannot be a "
                            + "posting-validation outcome");
        }

        if (projectedCycleBalance != null) {
            // WHY : Assumptions: the scale is checked for EXACT equality rather than normalised,
            //       because the projection arrives already computed and rescaling it here would
            //       silently move the quantity the inclusive credit-limit guard at
            //       app/cbl/CBTRN02C.cbl:407 compares. A value at scale 3 carries a third decimal
            //       digit that WS-TEMP-BAL PIC S9(09)V99 at :187 has no room for, so rounding it
            //       would decide the boundary on this constructor's rounding mode rather than on the
            //       caller's arithmetic, and a value at scale 0 signals a caller that computed in
            //       whole units and lost the cents entirely.
            // WHY : Trade-offs: refusing to normalise costs every caller an explicit scale, which is
            //       friction at each call site. It is accepted because the failure it prevents is
            //       silent and the friction it creates is not: an over-scaled projection raises at
            //       the value that produced it, naming the scale, whereas a normalised one produces
            //       a plausible outcome that only the byte-for-byte reject comparison would catch.
            if (projectedCycleBalance.scale() != PROJECTED_BALANCE_SCALE) {
                throw new IllegalArgumentException(
                        "projectedCycleBalance must be held at scale " + PROJECTED_BALANCE_SCALE
                                + " but was scale " + projectedCycleBalance.scale());
            }
        }

        // WHY : Assumptions: the projection exists exactly when the account was read, and
        //       RejectReason.terminatesValidation() is precisely that condition rather than an
        //       approximation of it. The reference computes WS-TEMP-BAL at
        //       app/cbl/CBTRN02C.cbl:403-405 from fields of the account record, inside the account
        //       read's NOT INVALID KEY branch, so no projection can exist for a cross-reference
        //       failure -- the guard at :372 stops the account lookup running -- nor for an account
        //       read that found nothing. Coupling the two components makes both impossible states
        //       unrepresentable: a 100 cannot arrive carrying a projection it could not have had, and
        //       a 102 cannot arrive without the projection that decided it.
        // WHY : Alternatives Considered: enumerating the two reasons that forbid a projection.
        //       Rejected for the same reason as the 109 test above -- the set is already named once,
        //       by the predicate, and RejectReason derives it from the guard at :372 and the
        //       INVALID KEY branch at :396 rather than from a list this file would have to keep in
        //       step.
        boolean validationEndedBeforeAccountRead = reason != null && reason.terminatesValidation();
        if (validationEndedBeforeAccountRead && projectedCycleBalance != null) {
            throw new IllegalArgumentException(
                    "reason " + reason.code() + " ends validation before the account is read, so no "
                            + "projectedCycleBalance can exist for it");
        }
        if (!validationEndedBeforeAccountRead && projectedCycleBalance == null) {
            throw new IllegalArgumentException(
                    "projectedCycleBalance is required for an outcome that reached the credit-limit "
                            + "and expiration tests");
        }
    }

    /**
     * Returns the outcome for a transaction that failed none of the four conditions.
     *
     * @param projectedCycleBalance the balance the credit-limit test was made against and found to be
     *     within the limit, formed as {@code app/cbl/CBTRN02C.cbl:403-405} forms it and held at
     *     exactly {@link #PROJECTED_BALANCE_SCALE} decimal places; required, because an accepted
     *     transaction is one that reached both boundary tests and so necessarily had a projection
     * @return an accepted outcome carrying no reason, never {@code null}
     * @throws IllegalArgumentException if {@code projectedCycleBalance} is {@code null} or is held at
     *     any scale other than {@link #PROJECTED_BALANCE_SCALE}, propagated unchanged from the
     *     constructor
     */
    public static PostingValidationResult accepted(BigDecimal projectedCycleBalance) {
        // WHY : Assumptions: acceptance is expressed by passing no reason rather than by passing a
        //       reason that means acceptance, mirroring app/cbl/CBTRN02C.cbl:208-209, which resets the
        //       code to zero and the description to spaces before each record and then treats that
        //       zero as "no reason" at :211. RejectReason declares no zero-valued member precisely so
        //       that this is the only way to say it.
        return new PostingValidationResult(null, projectedCycleBalance);
    }

    /**
     * Returns the outcome for a transaction refused under one already-decided reason.
     *
     * <p>This factory takes the reason as given and applies no precedence, so it is the right entry
     * point only when a single condition is known to have failed. A caller holding several findings
     * uses {@link #resolve(boolean, boolean, boolean, boolean, BigDecimal)} instead, which is the one
     * place the precedence between them is decided.</p>
     *
     * @param reason the reason that refused the transaction; must not be {@code null}, and must be
     *     one the reference can write to the reject stream, so
     *     {@link RejectReason#ACCOUNT_NOT_FOUND_ON_REWRITE} is not accepted
     * @param projectedCycleBalance the balance the credit-limit test was made against, held at
     *     exactly {@link #PROJECTED_BALANCE_SCALE} decimal places, or {@code null} when
     *     {@code reason} ended validation before the account was read and no projection could exist
     * @return a refusing outcome carrying exactly that one reason, never {@code null}
     * @throws NullPointerException if {@code reason} is {@code null}, which is the accepted outcome
     *     and is expressed by {@link #accepted(BigDecimal)} rather than by a null reason here
     * @throws IllegalArgumentException if {@code reason} is assigned outside validation, or if
     *     {@code projectedCycleBalance} does not agree with {@code reason} on whether a projection can
     *     exist, or is present at the wrong scale, all propagated unchanged from the constructor
     */
    public static PostingValidationResult rejected(RejectReason reason,
            BigDecimal projectedCycleBalance) {
        // WHY : Assumptions: a null reason is refused here rather than quietly treated as acceptance,
        //       because the two outcomes are reported through different factories on purpose. Letting
        //       a null through would make this method a second, undocumented way to build an accepted
        //       outcome, and a caller that reached it by accident would report a posted transaction
        //       where it meant to report a refused one.
        Objects.requireNonNull(reason, "reason must not be null; use accepted(BigDecimal) instead");
        return new PostingValidationResult(reason, projectedCycleBalance);
    }

    /**
     * Resolves the four findings of validation into the single outcome the reference reports, and is
     * the only place that precedence is decided.
     *
     * <p><b>When a transaction is both over its credit limit and past its account expiration this
     * method reports 103, not 102.</b> The two boundary tests in the reference are sequential,
     * independent {@code IF} blocks at {@code app/cbl/CBTRN02C.cbl:407-420}: the first assigns 102 at
     * {@code :410} and closes at {@code :413}, the second opens at {@code :414} and assigns 103 at
     * {@code :417}, and <b>there is no {@code IF WS-VALIDATION-FAIL-REASON = 0} between them</b>. The
     * later assignment therefore overwrites the earlier one, and the reason that survives a record is
     * whichever the control flow assigned LAST.</p>
     *
     * <p>The other two findings short-circuit, so of the four only the boundary pair can compete. A
     * missing cross-reference ends validation at the guard at {@code :372}, and a missing account is
     * assigned in the {@code INVALID KEY} branch of a read whose {@code NOT INVALID KEY} branch is
     * where both boundary tests live, so neither boundary is evaluated without an account.</p>
     *
     * @param crossReferenceMissing whether the card number resolved to no cross-reference row, which
     *     the reference finds in the {@code INVALID KEY} branch at {@code app/cbl/CBTRN02C.cbl:384};
     *     when this holds, nothing downstream was evaluated and the other three findings are ignored
     * @param accountMissing whether the account the cross-reference named could not be read, which
     *     the reference finds in the {@code INVALID KEY} branch at {@code :396}; when this holds,
     *     neither boundary was evaluated and both boundary findings are ignored
     * @param overCreditLimit whether the projected balance was STRICTLY GREATER than the credit
     *     limit, which is the strict complement of the inclusive PASS guard at {@code :407}, so a
     *     projection landing exactly ON the limit passes and this argument is then {@code false}
     * @param pastAccountExpiration whether the account expiration date was STRICTLY EARLIER than the
     *     transaction's originating date, which is the strict complement of the inclusive PASS guard
     *     at {@code :414}, so a transaction dated exactly ON the expiration date passes and this
     *     argument is then {@code false}
     * @param projectedCycleBalance the balance the credit-limit finding was made against, formed as
     *     {@code :403-405} forms it from the cycle credit total minus the cycle debit total plus the
     *     transaction amount and held at exactly {@link #PROJECTED_BALANCE_SCALE} decimal places, or
     *     {@code null} when either lookup failed and no projection could have been computed
     * @return the one outcome the reference reports for these findings, never {@code null}
     * @throws IllegalArgumentException if {@code projectedCycleBalance} does not agree with the
     *     findings on whether a projection can exist, or is present at the wrong scale, both
     *     propagated unchanged from the constructor
     */
    public static PostingValidationResult resolve(boolean crossReferenceMissing,
            boolean accountMissing, boolean overCreditLimit, boolean pastAccountExpiration,
            BigDecimal projectedCycleBalance) {

        // WHY : Assumptions: these two findings return rather than joining the comparison below,
        //       because the reference genuinely short-circuits on both and the guards that do it are
        //       present in the source. app/cbl/CBTRN02C.cbl:372 performs the account lookup only
        //       while no reason has been assigned, so a cross-reference failure ends validation; and
        //       a failed account read takes the INVALID KEY branch at :396, leaving the two boundary
        //       tests in the NOT INVALID KEY branch unevaluated. Returning here is the transcription
        //       of a guard that EXISTS, which is the opposite case from the boundary pair below.
        if (crossReferenceMissing) {
            return rejected(RejectReason.CARD_NUMBER_NOT_IN_CROSS_REFERENCE, projectedCycleBalance);
        }
        if (accountMissing) {
            return rejected(RejectReason.ACCOUNT_NOT_FOUND_ON_READ, projectedCycleBalance);
        }

        // WHY : Assumptions: the surviving boundary reason is SELECTED as a maximum rather than
        //       chosen by a branch, because the reference's two assignments at :410 and :417 are not
        //       alternatives -- nothing between :413 and :414 tests whether a reason has been
        //       assigned, so a transaction tripping both has 103 overwrite 102. Expressing that as a
        //       maximum under RejectReason.baselineAssignmentOrder() makes the answer independent of
        //       the order the two candidates are written on the following lines, so this method
        //       cannot be broken by rearranging it.
        // WHY : Alternatives Considered: an either-or over the two conditions in the order the source
        //       reads them, which is what a direct transcription produces. Rejected because it
        //       reports 102 where the reference reports 103, and it does so only for transactions
        //       that trip BOTH conditions -- every single-condition scenario keeps agreeing, so the
        //       defect passes casual testing and surfaces only as a byte-for-byte reject-stream
        //       divergence on the population least likely to appear in a hand-built fixture.
        // WHY : Assumptions: the ordering asked for is the recorded assignment order and expressly
        //       NOT Enum.ordinal(), so reordering the constants in RejectReason for readability
        //       cannot change which reason this method reports.
        return Stream.of(
                        overCreditLimit ? RejectReason.OVER_CREDIT_LIMIT : null,
                        pastAccountExpiration ? RejectReason.RECEIVED_AFTER_ACCOUNT_EXPIRATION : null)
                .filter(Objects::nonNull)
                .max(RejectReason.baselineAssignmentOrder())
                .map(surviving -> rejected(surviving, projectedCycleBalance))
                .orElseGet(() -> accepted(projectedCycleBalance));
    }

    /**
     * Answers whether the transaction failed no condition and may therefore post.
     *
     * @return {@code true} when this outcome carries no reason at all, which is the state the
     *     reference represents as a zero reason code at {@code app/cbl/CBTRN02C.cbl:208} and tests at
     *     {@code :211} to take the posting path; {@code false} when a reason refused it
     */
    public boolean isAccepted() {
        return this.reason == null;
    }

    /**
     * Answers whether one reason refused the transaction, which is the negation of acceptance.
     *
     * <p>Both senses are provided because each reads correctly in a different place: a caller
     * deciding whether to post asks {@link #isAccepted()}, and a caller deciding whether to write a
     * reject record and count it asks this method, matching the two branches the reference takes at
     * {@code app/cbl/CBTRN02C.cbl:211-216}.</p>
     *
     * @return {@code true} when this outcome carries a reason, {@code false} when it carries none;
     *     always the exact negation of {@link #isAccepted()}
     */
    public boolean isRejected() {
        // WHY : Trade-offs: the negation is derived from the predicate rather than from the field a
        //       second time. Two independent field tests would be one edit apart from disagreeing,
        //       and a pair of predicates that can both answer false is a state no caller checks for.
        return !isAccepted();
    }

    /**
     * Returns the reason that refused the transaction, as a total view over the nullable component.
     *
     * @return the single reason, or an empty {@link Optional} when the transaction failed no
     *     condition; never {@code null}, and never holding
     *     {@link RejectReason#ACCOUNT_NOT_FOUND_ON_REWRITE}
     */
    public Optional<RejectReason> rejectReason() {
        // WHY : Trade-offs: the component itself is a nullable RejectReason and this is the total
        //       view of it, rather than the component being declared as an Optional. Declaring it as
        //       an Optional would make the record's own generated accessor total, which is the
        //       cleaner-reading half of the trade, and it was not chosen for two concrete reasons: a
        //       record component is a field, and an Optional field is a serialisation hazard because
        //       Optional is deliberately not serialisable; and the generated canonical constructor
        //       would then demand a wrapped argument from every caller, so every construction site
        //       would carry Optional.of or Optional.empty even though this type already offers named
        //       factories for both cases. The accepted cost is precisely one nullable public
        //       accessor, reason(), whose null is documented on the record's own @param and whose
        //       total alternative is this method.
        return Optional.ofNullable(this.reason);
    }

    /**
     * Renders the 80-character validation trailer this outcome contributes to a reject record.
     *
     * <p>The trailer is four characters of zero-padded reason code followed by 76 characters of
     * space-padded reason description, and four plus 76 is the
     * {@code VALIDATION-TRAILER PIC X(80)} declared at {@code app/cbl/CBTRN02C.cbl:178}. It is the
     * second of the two parts of a reject record: the first is the 350-character transaction image of
     * {@code :177}, which this type does not model and which the writer supplies, and 350 plus 80 is
     * the 430 that {@code app/jcl/POSTTRAN.jcl:36} allocates as {@code LRECL=430}.</p>
     *
     * <p>For an accepted transaction this returns the sentinel form, four zero digits followed by 76
     * spaces. <b>That form is never written anywhere.</b> The reference reaches its reject writer only
     * on the branch at {@code app/cbl/CBTRN02C.cbl:215}, which is taken only when a reason is
     * present, so an accepted transaction contributes no record at all; the sentinel is what the
     * reset at {@code :208-209} leaves in the field, and it is rendered here so that this method is
     * total rather than because any caller emits it.</p>
     *
     * @return exactly {@link RejectReason#TRAILER_WIDTH} characters -- the reason's own rendering when
     *     one refused the transaction, and the four-zero plus 76-space sentinel when none did; never
     *     {@code null}
     */
    public String trailerField() {
        if (isAccepted()) {
            // WHY : Assumptions: the accepted rendering is built from the reset at
            //       app/cbl/CBTRN02C.cbl:208-209 rather than delegated to a reason, because there is
            //       no reason to delegate to -- acceptance is the absence of one, and RejectReason
            //       declares no zero-valued member to ask. The code half is four zero digits because
            //       WS-VALIDATION-FAIL-REASON PIC 9(04) at :181 is numeric-display, so a zero
            //       occupies it as digits rather than as blanks, and the description half is spaces
            //       because :209 moves SPACES into WS-VALIDATION-FAIL-REASON-DESC PIC X(76) at :182.
            // WHY : Trade-offs: returning a well-formed trailer for a record the reference never
            //       writes is a state this method invents, and the alternative was to raise on an
            //       accepted outcome instead. Raising was rejected because it would make the method
            //       partial for the ordinary case -- most transactions in a run are accepted -- so
            //       every caller would have to test acceptance before rendering, which is the
            //       duplication the named predicates already exist to avoid. The width is asserted
            //       against RejectReason's own constants so this branch cannot drift from the other.
            return ACCEPTED_CODE_FIELD + " ".repeat(RejectReason.DESCRIPTION_WIDTH);
        }
        // WHY : Assumptions: the rejected rendering is delegated rather than rebuilt, because
        //       RejectReason already owns both field widths and both padding conventions and is
        //       asserted against the oracle's committed expectation files. Concatenating the two
        //       fields again here would be a second implementation of one layout, free to disagree
        //       with the first about a width that positions every following byte of the record.
        return this.reason.trailerField();
    }

    /**
     * Renders this outcome for a log line, carrying the reason and never the projection.
     *
     * <p>Assumptions: the reason-code field is rendered and the projected balance is OMITTED. The code
     * is a bounded four-character token drawn from a five-member closed domain, which the rendering
     * rule of {@code docs/architecture/observability.md} admits on the same footing as a status code;
     * the projection is a monetary amount, which that rule places in the omitted class without an
     * abbreviated form. Whether a projection EXISTS is rendered as a boolean, because that fact
     * distinguishes an outcome refused before the account was read from one refused after it and
     * discloses nothing about the value.</p>
     *
     * <p>Refactoring Rationale: this override was absent, so the compiler-generated record rendering
     * applied and emitted {@code projectedCycleBalance} in full. That is the whole of a cycle-credit
     * total minus a cycle-debit total plus a transaction amount, and it appeared in any log line, any
     * exception message and any collection rendering that reached an instance of this type -- a
     * monetary disclosure requiring no error to trigger it. A record inherits a rendering it never
     * declared, which is why the absence was invisible to review.</p>
     *
     * <p>Alternatives Considered: rendering the projection's SCALE, or a fixed-width digest of it,
     * which is the geometry-not-content form used where a width is itself the contract. Rejected on
     * both counts: the scale is invariant at {@link #PROJECTED_BALANCE_SCALE} and so carries no
     * information at all, and a digest of a monetary amount is enumerable -- the value space a cycle
     * projection occupies is small enough that a digest is a lookup rather than a one-way function.</p>
     *
     * @return a rendering naming the reason code and whether a projection was formed, never
     *     {@code null} and never containing the projected balance
     */
    @Override
    public String toString() {
        return "PostingValidationResult[reasonCode=" + reasonCodeField()
                + ", projectionFormed=" + (this.projectedCycleBalance != null) + "]";
    }

    /**
     * Names the reason-code field this outcome contributes, without disclosing anything further.
     *
     * <p>Assumptions: the accepted outcome reports the same four zero characters the reference writes
     * for a transaction that failed no condition, so one rendering covers both arms and no caller has
     * to test for absence before reading it.</p>
     *
     * @return the four-character reason-code field, never {@code null}
     */
    private String reasonCodeField() {
        return this.reason == null ? ACCEPTED_CODE_FIELD : this.reason.codeField();
    }
}
