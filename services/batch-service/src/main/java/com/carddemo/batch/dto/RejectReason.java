package com.carddemo.batch.dto;

import java.util.Comparator;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * The closed vocabulary of posting-validation failure reasons: five codes, and no sixth.
 *
 * <h2>Purpose: a type exists here because one of these rules is an absence in the source</h2>
 *
 * <p>This type carries the reason codes and reason descriptions that transaction
 * posting assigns when a daily transaction cannot be posted, the fixed-width renderings those two
 * values take in the reject stream, and the rule that decides which reason survives when more than
 * one condition holds. It is a vocabulary type. It states what the reasons ARE and what bytes each
 * one becomes; it holds no rule about when any of them applies. The conditions themselves belong to
 * {@code com.carddemo.batch.service}, and the persisted reject row belongs to
 * {@code com.carddemo.batch.domain.TransactionReject}.</p>
 *
 * <p>Assumptions: five constants exist because the reference program assigns five distinct codes,
 * at {@code app/cbl/CBTRN02C.cbl:385}, {@code :397}, {@code :410}, {@code :417} and {@code :556}.
 * Four of them can reach the reject stream and the fifth cannot, a distinction
 * {@link #isPersistedToRejectStream()} answers rather than leaving to a reader who happens to trace
 * the control flow.</p>
 *
 * <p>Alternatives Considered: declaring the five descriptions as string constants beside the code
 * that emits them, with no type at all. Rejected because the single most consequential fact about
 * these five values is not any one of them -- it is the precedence between two of them, and that
 * precedence is expressed in the reference program as an ABSENCE rather than as a statement. A
 * missing guard has no line to transcribe, so it survives translation only if somebody writes it
 * down. This type is where it is written down, and
 * {@link #lastWriterWins(RejectReason)} is the member that carries it.</p>
 *
 * <h2>The precedence rule: 103 wins over 102, and the source says so by omission</h2>
 *
 * <p><b>When a transaction is both over its credit limit and past its account expiration, the
 * reference program reports 103, not 102.</b> The evidence is the shape of
 * {@code 1500-B-LOOKUP-ACCT}. Inside the {@code NOT INVALID KEY} branch of the account read, the
 * credit-limit test at {@code app/cbl/CBTRN02C.cbl:407} and the expiration test at {@code :414}
 * are two sequential, independent {@code IF} blocks. The first assigns 102 at {@code :410} and the
 * second assigns 103 at {@code :417}, and nothing between them tests whether a reason has already
 * been assigned, so the second assignment overwrites the first. The reason that survives is
 * whichever the control flow assigned LAST.</p>
 *
 * <p>Assumptions: the absence of a guard between those two blocks is a positive finding, not a
 * failure to look. The string {@code IF WS-VALIDATION-FAIL-REASON = 0} occurs exactly twice in the
 * 731 lines of that program: once at {@code app/cbl/CBTRN02C.cbl:211}, where it selects posting
 * over rejection for the record as a whole, and once at {@code :372}, where it stops
 * {@code 1500-B-LOOKUP-ACCT} from running at all after the cross-reference lookup has already
 * failed. There is no third occurrence, and in particular none between {@code :413} and
 * {@code :414}.</p>
 *
 * <p>Alternatives Considered: expressing the two checks in Java as a chain that returns on its
 * first match, in the order the constants happen to be declared here. That is the shape most
 * authors reach for and it is wrong in a specific, measurable way: it reports 102 for a transaction
 * that is both over limit and past expiration, where the reference reports 103. The reject stream
 * is compared byte for byte against committed expectation files, so the divergence surfaces as a
 * parity failure on exactly those transactions that trip both conditions, while every transaction
 * that trips only one continues to agree. Routing the decision through
 * {@link #lastWriterWins(RejectReason)} is what stops a caller re-deriving the order and getting
 * it backwards.</p>
 *
 * <h2>Which reasons can co-occur at all: exactly one pair</h2>
 *
 * <p>Assumptions: the precedence question is narrow because the reference program's structure makes
 * most of these reasons mutually exclusive. 100 excludes everything downstream, because the guard at
 * {@code app/cbl/CBTRN02C.cbl:372} runs the account lookup only when no reason has been assigned;
 * 101 excludes both 102 and 103, because it is assigned in the {@code INVALID KEY} branch of the
 * account read while both boundary tests live in the {@code NOT INVALID KEY} branch of that same
 * read; and 109 co-occurs with nothing, because {@code :211} enters the posting path only when
 * validation assigned no reason at all. That leaves {@code {102, 103}} as the one pair that can both
 * hold, and it is precisely the pair with no guard between them -- so
 * {@link #lastWriterWins(RejectReason)} is a transcription rather than a policy invented here.</p>
 *
 * <h2>Both boundaries are inclusive on the passing side, so equality posts</h2>
 *
 * <p>Assumptions: each of the two boundary tests is written in the reference as a PASS guard using
 * {@code &gt;=}, so the reject predicate is the strict complement -- a projection landing exactly ON
 * the credit limit posts, and a transaction dated exactly ON the expiration date posts. Both senses
 * are corroborated independently by the parity oracle, whose
 * {@code tests/golden/posting/boundary_exact_limit} and
 * {@code tests/golden/posting/boundary_expiry_equal} each expect an EMPTY reject stream and a return
 * code of zero. Both framings are restated on {@link #OVER_CREDIT_LIMIT} and
 * {@link #RECEIVED_AFTER_ACCOUNT_EXPIRATION}, where a caller reads them, because readers invert them
 * in the retelling.</p>
 *
 * <h2>The fixed-width renderings, and the record length that determines them</h2>
 *
 * <p>A rejected transaction is written as one 430-character record: the 350-character transaction
 * image, then an 80-character trailer holding this type's two values as
 * {@code WS-VALIDATION-FAIL-REASON PIC 9(04)} and
 * {@code WS-VALIDATION-FAIL-REASON-DESC PIC X(76)}, declared at
 * {@code app/cbl/CBTRN02C.cbl:176-182}. The 430 total is asserted independently by the driver at
 * {@code app/jcl/POSTTRAN.jcl:36}, which allocates the stream with
 * {@code DCB=(RECFM=F,LRECL=430,BLKSIZE=0)}.</p>
 *
 * <p>Assumptions: the renderings live here, on {@link #codeField()},
 * {@link #descriptionField()} and {@link #trailerField()}, because the sibling that persists a
 * reject row stores its description unpadded and defers the widening to whatever emits the record.
 * Putting the two widths on the type that owns the two values means the emitter inherits correct
 * bytes instead of re-deriving a width, and a width can then be wrong in one place rather than in
 * every writer.</p>
 *
 * <h2>Acceptance is the absence of a reason, so there is no constant for it</h2>
 *
 * <p>Alternatives Considered: a sixth constant standing for "accepted", carrying the code zero and
 * a blank description, mirroring the reset the reference performs before each record at
 * {@code app/cbl/CBTRN02C.cbl:208-209}. Rejected because it would make acceptance a KIND of
 * rejection. Every member of this type answers a question about a rejected transaction -- which
 * bytes it contributes to the reject stream, whether it is persisted, which reason beats it -- and
 * none of those questions has a meaning for a transaction that posted. A constant that answered
 * them anyway would render a well-formed trailer for an accepted record, which is a row the
 * reference never writes. Acceptance is therefore modelled as the absence of a value: an empty
 * {@link Optional} from {@link #fromCode(int)}, or no reason at all on the validation result.</p>
 *
 * <h2>The descriptions are data, not copy</h2>
 *
 * <p>Assumptions: the five description strings are carried across character for character, and the
 * migration plan's transformation rule T8 requires exactly that of every user-visible string. Here
 * the requirement is stronger than presentation fidelity, because these strings are not shown to a
 * user at all -- they are bytes in a data file that the parity comparison reads. Three spellings
 * are worth naming so that a later reader does not improve them: {@code OVERLIMIT} is one word;
 * {@code ACCT} is abbreviated in the expiration description while {@code ACCOUNT} is spelled out in
 * the two account descriptions; and the cross-reference description ends in {@code FOUND} while
 * describing a row that was not found. None of the three is a defect to repair in this file.</p>
 *
 * <h2>Two properties a caller has to know before comparing reasons</h2>
 *
 * <p>Assumptions: reasons 101 and 109 carry BYTE-IDENTICAL descriptions under different codes, so a
 * description does not identify a reason. {@link #code()} is what a caller compares, and a caller
 * matching on text would silently conflate the two. Precedence is
 * {@link #lastWriterWins(RejectReason)} and expressly NOT {@link #ordinal()}: relying on declaration
 * order would make the answer a property of how this file is written rather than of what the
 * reference does.</p>
 *
 * <p>Baseline paths are cited for provenance only; nothing under {@code app/**} is read at run time
 * or altered by this migration, and every divergence from it is registered in
 * {@code docs/architecture/cobol-to-service-traceability.md}.</p>
 */
public enum RejectReason {

    /**
     * The transaction's card number has no cross-reference row, reason 100.
     *
     * <p>Authority: {@code app/cbl/CBTRN02C.cbl:385-387}, the {@code INVALID KEY} branch of the
     * cross-reference read in {@code 1500-A-LOOKUP-XREF}. Line 385 assigns the code and line 386
     * carries the literal, whose 25 characters are reproduced here exactly.</p>
     *
     * <p>Assumptions: this reason ends validation for the record. The guard at {@code :372} performs
     * the account lookup only while no reason has been assigned, so nothing downstream of the
     * cross-reference read is evaluated once this reason is set, which is what
     * {@link #terminatesValidation()} reports.</p>
     */
    CARD_NUMBER_NOT_IN_CROSS_REFERENCE(100, "INVALID CARD NUMBER FOUND", 1),

    /**
     * The account named by the cross-reference row does not exist, reason 101.
     *
     * <p>Authority: {@code app/cbl/CBTRN02C.cbl:397-399}, the {@code INVALID KEY} branch of the
     * account read in {@code 1500-B-LOOKUP-ACCT}. Line 397 assigns the code and line 398 carries
     * the literal.</p>
     *
     * <p>Assumptions: this reason is structurally exclusive with both boundary reasons. The
     * credit-limit test at {@code :407} and the expiration test at {@code :414} sit in the
     * {@code NOT INVALID KEY} branch of the same read, so an account that was not found leaves both
     * unevaluated. Its description is byte-identical to {@link #ACCOUNT_NOT_FOUND_ON_REWRITE}'s and
     * the two are deliberately not merged; the reason is recorded on that constant.</p>
     */
    ACCOUNT_NOT_FOUND_ON_READ(101, "ACCOUNT RECORD NOT FOUND", 2),

    /**
     * Posting the transaction would carry the account past its credit limit, reason 102.
     *
     * <p>Authority: {@code app/cbl/CBTRN02C.cbl:410-412}, the failing branch of the credit-limit
     * test. Line 410 assigns the code and line 411 carries the literal, in which
     * {@code OVERLIMIT} is one word.</p>
     *
     * <p>Assumptions: <b>the guard is inclusive on the passing side, so the reject predicate is
     * strict.</b> {@code :407} reads {@code IF ACCT-CREDIT-LIMIT &gt;= WS-TEMP-BAL} and passes on
     * that comparison, so a projection landing exactly ON the limit POSTS and this reason is
     * assigned only when the projection is STRICTLY GREATER than the limit. One cent over rejects;
     * the limit itself does not. {@code tests/golden/posting/boundary_exact_limit} pins the
     * inclusive sense by expecting an empty reject stream.</p>
     *
     * <p>Assumptions: the quantity compared against the limit is a projection formed at
     * {@code :403-405} as the cycle credit total minus the cycle debit total plus the transaction
     * amount, and NOT the account's current balance. The distinction matters because reaching for
     * the current balance instead yields rejections that are entirely plausible and wrong on
     * accounts whose cycle totals differ from their balance.</p>
     */
    OVER_CREDIT_LIMIT(102, "OVERLIMIT TRANSACTION", 3),

    /**
     * The transaction was received after the account expired, reason 103.
     *
     * <p>Authority: {@code app/cbl/CBTRN02C.cbl:417-419}, the failing branch of the expiration
     * test. Line 417 assigns the code and line 418 carries the literal, whose 42 characters make it
     * the longest of the five and in which {@code ACCT} is abbreviated.</p>
     *
     * <p>Assumptions: <b>the guard is inclusive on the passing side, so an equal date posts.</b>
     * {@code :414} reads {@code IF ACCT-EXPIRAION-DATE &gt;= DALYTRAN-ORIG-TS (1:10)} and passes on
     * that comparison, so a transaction dated exactly ON the expiration date POSTS and this reason
     * is assigned only when the expiration date is STRICTLY EARLIER than the transaction date. The
     * comparison is lexical over the first ten characters of the 26-character originating stamp,
     * which is equivalent to comparing dates only because the stored form is ISO-ordered.
     * {@code tests/golden/posting/boundary_expiry_equal} pins the inclusive sense by expecting an
     * empty reject stream. The field name is misspelled in the reference and is cited above as it
     * is spelled there.</p>
     *
     * <p>Assumptions: <b>this reason beats {@link #OVER_CREDIT_LIMIT} when both hold.</b> Its
     * assignment at {@code :417} is reached after the credit-limit assignment at {@code :410} with
     * no guard between the two blocks, so it overwrites. The rule is carried by
     * {@link #lastWriterWins(RejectReason)}.</p>
     */
    RECEIVED_AFTER_ACCOUNT_EXPIRATION(103, "TRANSACTION RECEIVED AFTER ACCT EXPIRATION", 4),

    /**
     * Rewriting the account record after a successful post did not find the record, reason 109.
     *
     * <p>Authority: {@code app/cbl/CBTRN02C.cbl:556-558}, the {@code INVALID KEY} branch of the
     * account rewrite in {@code 2800-UPDATE-ACCOUNT-REC}. Line 556 assigns the code and line 557
     * carries the literal, which is byte-identical to {@link #ACCOUNT_NOT_FOUND_ON_READ}'s.</p>
     *
     * <p>Assumptions: <b>the reference assigns this code on a path that cannot write it, so it is
     * never persisted.</b> {@code 2800-UPDATE-ACCOUNT-REC} is performed only from {@code :441},
     * inside the posting paragraph, which is itself performed only from {@code :212} on the branch
     * taken when validation assigned no reason. The paragraph that writes the reject stream is
     * performed only from {@code :215}, on the other branch of that same decision at
     * {@code :211-216}, and {@code :208} resets the reason before the next record. Nothing
     * therefore emits 109, which the committed expectation files confirm: the code {@code 0109}
     * appears in none of them. This type carries the constant because the code exists in the
     * reference's vocabulary, and {@link #isPersistedToRejectStream()} answers false for it. The
     * migrated job surfaces a failed account rewrite through its hard-failure exit tier instead --
     * the posting transaction rolls back and the step fails, so nothing is posted and nothing is
     * rejected -- and the divergence is registered as {@code D-POSTING-ATOMIC-NO-REJECT-109} in
     * {@code docs/architecture/cobol-to-service-traceability.md}.</p>
     *
     * <p>Assumptions: <b>no durable reject row carries this code, in this target or the
     * reference.</b> The register briefly carried an entry, {@code D-REJECT-109-DURABLE}, describing
     * a row written from outside the rolled-back unit of work so that the failure would be
     * queryable; no code ever wrote one, and that identifier is now recorded as withdrawn in the
     * register's withdrawn-identifier section. This is stated here because this constant is the
     * first thing a reader looking for reason 109 finds, and an unqualified "hard-failure tier"
     * leaves open which artifact records the failure. The answer is the batch state machine and the
     * task's own log, not {@code ledger.transaction_rejects}.</p>
     *
     * <p>Assumptions: <b>this constant is kept distinct from {@link #ACCOUNT_NOT_FOUND_ON_READ}
     * despite the identical description.</b> The reference assigns two different codes for two
     * different events -- 101 when the account READ finds nothing at {@code :397}, and 109 when the
     * account REWRITE finds nothing at {@code :556} -- and the codes, not the descriptions, are what
     * the reject stream carries and what a reader of that stream can act on. Collapsing them into
     * one constant would erase the difference between a transaction that could not be validated and
     * a transaction that posted and then could not be recorded, which are unrelated conditions with
     * unrelated remedies. The two names differ only in the operation they name, {@code ON_READ}
     * against {@code ON_REWRITE}, because that operation is the entire distinction.</p>
     */
    ACCOUNT_NOT_FOUND_ON_REWRITE(109, "ACCOUNT RECORD NOT FOUND", 5);

    /**
     * The character width of the reason-code field in the reject trailer, four.
     *
     * <p>Assumptions: the width comes from {@code WS-VALIDATION-FAIL-REASON PIC 9(04)} at
     * {@code app/cbl/CBTRN02C.cbl:181}. All five codes this type declares are three-digit values
     * held in that four-digit field, so every rendering is padded rather than truncated, and the
     * padding character is a zero because the field is numeric-display rather than
     * alphanumeric.</p>
     */
    public static final int CODE_WIDTH = 4;

    /**
     * The character width of the reason-description field in the reject trailer, 76.
     *
     * <p>Assumptions: the width comes from {@code WS-VALIDATION-FAIL-REASON-DESC PIC X(76)} at
     * {@code app/cbl/CBTRN02C.cbl:182}. The longest of the five descriptions is 42 characters, so
     * every rendering pads on the right and none is ever truncated.</p>
     */
    public static final int DESCRIPTION_WIDTH = 76;

    /**
     * The character width of the whole reject trailer, 80.
     *
     * <p>Assumptions: the value is expressed as the sum of the two field widths rather than as a
     * literal 80, so the identity that makes the record cohere is stated in code and not only in
     * prose. It has to equal {@code VALIDATION-TRAILER PIC X(80)} at
     * {@code app/cbl/CBTRN02C.cbl:178}, which follows {@code REJECT-TRAN-DATA PIC X(350)} at line
     * 177 to make the 430-character record that {@code app/jcl/POSTTRAN.jcl:36} allocates as
     * {@code LRECL=430}. Writing the literal instead would let one of the two field widths change
     * without the sum following it.</p>
     */
    public static final int TRAILER_WIDTH = CODE_WIDTH + DESCRIPTION_WIDTH;

    /**
     * The numeric reason code this constant is reported and stored as.
     *
     * <p>Assumptions: the field is final and holds a primitive taken from each constant's own
     * argument list, so the declared numbers stay 100, 101, 102, 103 and 109. Deriving them by
     * arithmetic on {@link #ordinal()} cannot produce that sequence, because the gap between 103 and
     * 109 is not the gap between any two adjacent ordinals.</p>
     */
    private final int code;

    /**
     * The reason description exactly as the reference program writes it, unpadded.
     *
     * <p>Assumptions: the string is stored at its natural length and widened only by
     * {@link #descriptionField()}, matching the sibling that persists a reject row, which also
     * stores the description unpadded. Storing it pre-padded would make every comparison against a
     * literal typed from the reference fail on trailing blanks.</p>
     */
    private final String description;

    /**
     * The position of this reason's assignment in the order the reference program reaches it.
     *
     * <p>Assumptions: the five assignments are reached in the order 100, 101, 102, 103, 109, at
     * {@code app/cbl/CBTRN02C.cbl:385}, {@code :397}, {@code :410}, {@code :417} and {@code :556},
     * so the sequence runs 1 through 5. Because the reference records a reason by moving a value
     * into one shared field, the reason that survives a record is the one assigned LAST, which makes
     * a higher sequence beat a lower one. This field is the whole of that rule; see
     * {@link #lastWriterWins(RejectReason)}.</p>
     *
     * <p>Alternatives Considered: ordering by {@link #code} instead of carrying a separate sequence,
     * since the five codes happen to ascend in the same order as their assignments. Rejected because
     * the agreement is a coincidence of how the reference numbered its codes and not the rule being
     * modelled: a reason added later with a lower number than its predecessor would silently invert
     * the precedence, and nothing would fail until a transaction tripped both conditions.
     * {@link #ordinal()} was rejected for the stronger version of the same objection -- it is a
     * property of the order the constants are typed in this file, so reordering them for readability
     * would change behaviour.</p>
     */
    private final int baselineAssignmentSequence;

    /**
     * Binds one constant to its code, its verbatim description and its assignment position.
     *
     * @param code the numeric reason code for this constant, supplied as a literal in the constant's
     *     own argument list above and required to equal the value the reference program moves into
     *     {@code WS-VALIDATION-FAIL-REASON}
     * @param description the reason description for this constant, reproduced character for
     *     character from the literal the reference program moves into
     *     {@code WS-VALIDATION-FAIL-REASON-DESC}, unpadded and at most
     *     {@link #DESCRIPTION_WIDTH} characters
     * @param baselineAssignmentSequence the one-based position of this reason's assignment in the
     *     order the reference program reaches the five assignments, where a higher value denotes a
     *     later assignment and therefore a reason that overwrites an earlier one
     */
    private RejectReason(int code, String description, int baselineAssignmentSequence) {
        // WHY : Trade-offs: the three arguments are stored as received, with no range or width check
        //       here. A check at this point could only compare one literal in this file against
        //       another literal in the same file, so it would restate the declaration rather than
        //       test it, and it would run during class initialisation where a failure surfaces as an
        //       initialisation error naming this type rather than as a readable assertion. The
        //       widths and the codes are asserted instead by RejectReasonTest, which compares them
        //       against expectation files of the functional-parity oracle rather than against
        //       neighbouring literals, so the check has an independent authority to fail against.
        this.code = code;
        this.description = description;
        this.baselineAssignmentSequence = baselineAssignmentSequence;
    }

    /**
     * Returns the numeric reason code this constant is reported and stored as.
     *
     * @return the reason code, one of 100, 101, 102, 103 or 109; never the {@link #ordinal()}, which
     *     is not part of any contract this type carries
     */
    public int code() {
        return code;
    }

    /**
     * Returns the reason description exactly as the reference program writes it, unpadded.
     *
     * @return the description at its natural length, never {@code null} and never longer than
     *     {@link #DESCRIPTION_WIDTH}; two constants return equal strings, so this value alone does
     *     not identify a reason and {@link #code()} is what a caller compares
     */
    public String description() {
        return description;
    }

    /**
     * Renders the reason code as the four characters the reject trailer's numeric field carries.
     *
     * @return the code zero-padded on the left to exactly {@link #CODE_WIDTH} characters, so 100
     *     renders as {@code 0100} and 109 as {@code 0109}; never {@code null}
     */
    public String codeField() {
        // WHY : Assumptions: the field is numeric-display, WS-VALIDATION-FAIL-REASON PIC 9(04) at
        //       app/cbl/CBTRN02C.cbl:181, so a three-digit code occupies it with a leading zero
        //       rather than with a leading blank. The committed expectation files of the
        //       functional-parity oracle show exactly that: characters 351 to 354 of each record in
        //       tests/golden/posting/reject_10*/dalyrejs.expected read 0100, 0101, 0102 and 0103.
        // WHY : Alternatives Considered: formatting without an explicit locale, which is the form
        //       most authors write. Rejected because the conversion of %d is locale-sensitive: under
        //       a default locale whose numbering system is not Latin -- ar-EG-u-nu-arab,
        //       hi-IN-u-nu-deva and bn-IN-u-nu-beng among them -- the same call renders the digits in
        //       that system's glyphs, so the field would carry non-ASCII characters into a data file
        //       that is compared byte for byte. Locale.ROOT pins the Latin digits regardless of the
        //       ambient default, and the ambient default of a container is not this module's to
        //       assume.
        return String.format(Locale.ROOT, "%0" + CODE_WIDTH + "d", code);
    }

    /**
     * Renders the reason description as the 76 characters the reject trailer's text field carries.
     *
     * @return the description padded on the right with spaces to exactly
     *     {@link #DESCRIPTION_WIDTH} characters, so that its untrimmed prefix is
     *     {@link #description()}; never {@code null}
     * @throws IllegalArgumentException if this constant's description is longer than
     *     {@link #DESCRIPTION_WIDTH}, which no declared constant is; the condition is reachable only
     *     if a future constant is declared with an over-wide description
     */
    public String descriptionField() {
        // WHY : Assumptions: the field is alphanumeric, WS-VALIDATION-FAIL-REASON-DESC PIC X(76) at
        //       app/cbl/CBTRN02C.cbl:182, and a COBOL move into an alphanumeric target
        //       left-justifies the value and fills the remainder with spaces. Characters 355 to 430
        //       of every record in the oracle's committed reject expectations are exactly 76
        //       characters wide with the description followed by blanks, which is that fill.
        // WHY : Alternatives Considered: a left-justifying width specifier, %-76s, which reads as the
        //       more direct translation of the move. Rejected on its failure mode rather than on its
        //       clarity: a width specifier treats 76 as a MINIMUM and returns the value unchanged
        //       when it is longer, so an over-wide description would yield a field wider than 76 and
        //       silently push every following byte of the 430-character record out of position. The
        //       subtraction below cannot do that -- it raises instead -- so an over-wide description
        //       fails at the value that caused it rather than corrupting a record that still looks
        //       well formed.
        return description + " ".repeat(DESCRIPTION_WIDTH - description.length());
    }

    /**
     * Renders the whole 80-character reject trailer for this reason.
     *
     * @return the four-character code field followed immediately by the 76-character description
     *     field, exactly {@link #TRAILER_WIDTH} characters in all; never {@code null}
     * @throws IllegalArgumentException if this constant's description is longer than
     *     {@link #DESCRIPTION_WIDTH}, propagated unchanged from {@link #descriptionField()}
     */
    public String trailerField() {
        // WHY : Assumptions: the two fields are adjacent and in this order because the reference
        //       moves the trailer group as a whole, MOVE WS-VALIDATION-TRAILER TO VALIDATION-TRAILER
        //       at app/cbl/CBTRN02C.cbl:448, and that group declares the code at line 181 before the
        //       description at line 182. The concatenation is therefore the group's own layout, and
        //       its 4 + 76 characters are the VALIDATION-TRAILER PIC X(80) of line 178. The caller
        //       that writes the record supplies the 350-character transaction image that precedes
        //       this, which is why no part of that image is modelled here; 350 + 80 is the 430 that
        //       app/jcl/POSTTRAN.jcl:36 allocates.
        return codeField() + descriptionField();
    }

    /**
     * Answers whether this reason can ever be written to the reject stream.
     *
     * <p>The answer is true for the four reasons the validation paragraphs assign and false for the
     * one the account-rewrite paragraph assigns.</p>
     *
     * @return {@code true} for every reason the reference can persist, which is the four whose codes
     *     are 100, 101, 102 and 103; {@code false} for the reason whose code is 109, which the
     *     reference assigns on a path that cannot reach the writer
     */
    public boolean isPersistedToRejectStream() {
        // WHY : Assumptions: the exclusion rests on reachability, not on preference. 109 is assigned
        //       from the posting paragraph, which app/cbl/CBTRN02C.cbl:212 performs on the branch
        //       taken when validation assigned no reason, while the reject writer is performed from
        //       :215 on the other branch of that same decision; the two are alternatives and :208
        //       resets the reason before the next record, so no execution can carry 109 to the
        //       writer. The oracle agrees independently -- the code 0109 appears in none of the
        //       committed reject expectation files under tests/golden/posting/. Defining the narrower
        //       persisted set HERE, in terms of the wider vocabulary, is also what keeps this type
        //       and the sibling that persists a reject row -- whose reason-code domain is exactly
        //       {100, 101, 102, 103} -- from each restating a list the other cannot see.
        return this != ACCOUNT_NOT_FOUND_ON_REWRITE;
    }

    /**
     * Answers whether assigning this reason stops any further validation of the same transaction.
     *
     * <p>The answer is true for the two lookup failures and false for the two boundary failures. It
     * is also false for the reason assigned during the account rewrite, which is assigned after
     * validation has already finished.</p>
     *
     * @return {@code true} when no further validation runs once this reason is assigned, which is
     *     the case for the cross-reference and account-read failures; {@code false} when validation
     *     continues, which is the case for both boundary failures and for the rewrite failure
     */
    public boolean terminatesValidation() {
        // WHY : Assumptions: the reference short-circuits in one place and not in the other, and the
        //       asymmetry is the whole content of this predicate. app/cbl/CBTRN02C.cbl:372 guards the
        //       account lookup with IF WS-VALIDATION-FAIL-REASON = 0, so a cross-reference failure
        //       ends validation, and an account-read failure takes the INVALID KEY branch at :396,
        //       leaving the two tests in the NOT INVALID KEY branch unevaluated; the two boundary
        //       tests at :407 and :414 carry no such guard between them, so assigning 102 does NOT
        //       stop the expiration test from overwriting it. The rewrite failure answers false for a
        //       different reason -- there is no validation left for it to terminate, because :211
        //       reaches its assignment at :556 only after validation completed with no reason at
        //       all.
        return this == CARD_NUMBER_NOT_IN_CROSS_REFERENCE || this == ACCOUNT_NOT_FOUND_ON_READ;
    }

    /**
     * Returns whichever of this reason and another the reference program would have assigned last.
     *
     * <p>This is the precedence rule, and on the only pair that can genuinely co-occur it selects
     * {@link #RECEIVED_AFTER_ACCOUNT_EXPIRATION} over {@link #OVER_CREDIT_LIMIT}. Comparing a reason
     * with itself returns that reason.</p>
     *
     * @param other the competing reason, which must not be {@code null}; a transaction with no
     *     reason at all is modelled as the absence of a value rather than as a null argument, so
     *     accumulate reasons with {@link #baselineAssignmentOrder()} instead of passing null here
     * @return the reason the reference would have left in the field, which is the one it assigns
     *     later of the two; never {@code null}
     * @throws NullPointerException if {@code other} is {@code null}
     */
    public RejectReason lastWriterWins(RejectReason other) {
        Objects.requireNonNull(other, "other reject reason must not be null");
        // WHY : Assumptions: the reference records a reason by moving a value into one shared field,
        //       so co-occurring conditions do not combine -- the later assignment simply overwrites
        //       the earlier one. Inside 1500-B-LOOKUP-ACCT the credit-limit test at
        //       app/cbl/CBTRN02C.cbl:407 and the expiration test at :414 are two sequential,
        //       independent IF blocks with NOTHING between them that tests whether a reason has
        //       already been assigned, so MOVE 103 at :417 overwrites MOVE 102 at :410 whenever a
        //       transaction trips both. The absence of that guard is verifiable rather than assumed:
        //       IF WS-VALIDATION-FAIL-REASON = 0 occurs exactly twice in the program's 731 lines, at
        //       :211 and :372, and neither sits between those two blocks.
        // WHY : Alternatives Considered: a Java chain that returns on its first matching condition in
        //       the order these constants are declared. Rejected because it inverts this pair: it
        //       would report 102 for a transaction that is both over limit and past expiration,
        //       where the reference reports 103. The reject stream is compared byte for byte against
        //       committed expectations, so the inversion would pass every test that trips one
        //       condition and fail only on transactions that trip both -- the population least
        //       likely to appear in a hand-built fixture and most likely to appear in real data.
        return baselineAssignmentSequence >= other.baselineAssignmentSequence ? this : other;
    }

    /**
     * Returns a comparator ordering reasons by when the reference program assigns them.
     *
     * <p>The ordering is ascending in assignment position, so the greatest element under this
     * comparator is the reason the reference would have assigned last. Selecting the maximum of the
     * reasons whose conditions hold therefore yields the reason the reference reports, and selecting
     * the maximum of none yields an empty result, which is the accepted transaction.</p>
     *
     * @return a comparator that is consistent with equals over this type and imposes a total order
     *     on its five constants; never {@code null}
     */
    public static Comparator<RejectReason> baselineAssignmentOrder() {
        // WHY : Assumptions: the comparator exists so that a caller collecting the conditions a
        //       transaction tripped can resolve them with a maximum rather than with a hand-written
        //       chain, and so that the empty case falls out of the same expression. An empty stream
        //       yields an empty optional, and this type models an accepted transaction as exactly
        //       that absence rather than as a constant, so the accepted case needs no branch of its
        //       own.
        // WHY : Assumptions: the ordering is over the recorded assignment position and NOT over
        //       ordinal() or code(). Ordinal would make the declaration order in this file
        //       behavioural, so reordering the constants for readability would change which reason a
        //       caller reports; code() agrees with the assignment order only by coincidence of how
        //       the reference numbered these five values, and the coincidence is not the rule.
        return Comparator.comparingInt(reason -> reason.baselineAssignmentSequence);
    }

    /**
     * Resolves a numeric reason code to the reason that claims it, if any reason does.
     *
     * <p>Each of the five declared codes resolves to its own constant. Every other value, the
     * accepted sentinel zero included, resolves to an empty result.</p>
     *
     * @param candidateCode the numeric code to resolve, typically read from the four-character
     *     numeric field of a reject trailer; any {@code int} is accepted as input, including zero
     *     and negative values, and a value no constant claims is reported by an empty result rather
     *     than by an exception
     * @return the reason whose {@link #code()} equals {@code candidateCode}, or an empty
     *     {@link Optional} when no declared reason claims that value; never {@code null}
     */
    public static Optional<RejectReason> fromCode(int candidateCode) {
        // WHY : Alternatives Considered: raising on an unclaimed code, as the sibling that resolves
        //       this module's completion tiers does for an unmodelled number. Rejected here because
        //       the code space this method reads from contains a documented non-error value that the
        //       sibling's does not: app/cbl/CBTRN02C.cbl:208-209 resets the field to zero with a
        //       blank description before every record, so zero is the reference's own way of saying
        //       "no reason", and across a run it is by far the most common value the field holds. A
        //       raising factory would therefore throw on the ordinary case, and every caller would
        //       have to test for zero before calling -- which would copy the knowledge of the
        //       sentinel into each caller, the duplication this type exists to prevent. An empty
        //       result is the precisely correct answer for zero and an adequate one for a code no
        //       constant claims.
        // WHY : Trade-offs: the accepted cost is that this method does not distinguish the sentinel
        //       zero from an unmodelled non-zero code, since both return empty. That is accepted
        //       because the two are told apart by the value the caller already holds, and because a
        //       malformed code in a stored record is a row-level validation concern belonging to
        //       whatever reads that record rather than to this vocabulary.
        for (RejectReason reason : values()) {
            if (reason.code == candidateCode) {
                return Optional.of(reason);
            }
        }
        return Optional.empty();
    }
}
