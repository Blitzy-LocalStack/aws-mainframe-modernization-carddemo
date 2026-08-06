package com.carddemo.common.error;

import com.carddemo.common.observability.LogSafeText;

/**
 * Carries the four components of the reference baseline's abend data block as one structured value.
 *
 * <p>The reference baseline declares those components under a single group item,
 * {@code 01 ABEND-DATA}, at {@code app/cpy/CSMSG02Y.cpy} lines 21 to 29. This record is that group
 * item's migrated form: the same four components, in the same order, under the same names, carried
 * as data in a response body rather than as characters written to a terminal.</p>
 *
 * <h2>The inherited contract</h2>
 *
 * <pre>
 * component      baseline field    PIC      width   declared at
 * abendCode      ABEND-CODE        X(4)         4   lines 22 and 23
 * abendCulprit   ABEND-CULPRIT     X(8)         8   lines 24 and 25
 * abendReason    ABEND-REASON      X(50)       50   lines 26 and 27
 * abendMsg       ABEND-MSG         X(72)       72   lines 28 and 29
 *                                            ----
 *                                             134   bytes in the group item
 * </pre>
 *
 * <p>Each component spends two lines in the copybook, its {@code PIC} clause on one and its
 * {@code VALUE SPACES} clause on the next, so the four components plus the group item occupy exactly
 * nine lines. Each width is exposed as a constant on this record so that a reader, a test or a
 * downstream renderer can name the number rather than restate it: {@link #ABEND_CODE_LENGTH},
 * {@link #ABEND_CULPRIT_LENGTH}, {@link #ABEND_REASON_LENGTH}, {@link #ABEND_MSG_LENGTH} and their
 * sum {@link #ABEND_DATA_LENGTH}.</p>
 *
 * <p>Assumptions: the group item has exactly four components and this record has exactly four, and
 * the sum {@code 4 + 8 + 50 + 72 = 134} is stated because it is what makes the component list
 * verifiable rather than asserted -- a fifth component would change it. No fifth component is added
 * here. A diagnostic timestamp, a correlation identifier, a severity or a rendered stack trace would
 * each be an invention at this level, and the response-shaping concerns that need them belong to
 * {@code ApiError}, which is the type that assembles a response and the only one of the two that
 * knows a request is in progress.</p>
 *
 * <p>Assumptions: the copybook announces itself under a different name -- its line 2 reads
 * {@code 000800* CABENDD.CPY}, which is neither the name the file is stored under nor the name any
 * program uses to include it. The mismatch is recorded so that a reader tracing this record's lineage
 * searches for the storage name, and so that finding no file under the announced title is not
 * mistaken for something missing.</p>
 *
 * <h2>Why a record, and why the two message widths stay two</h2>
 *
 * <p>Alternatives Considered: an ordinary class with library-generated accessors, specifically
 * Lombok, was evaluated and rejected. A generated accessor has no source of its own on which a
 * Javadoc block can be written, so a Lombok-built form of this type either fails the documentation
 * gate outright or has to be exempted from it, and no exemption is available: the audit
 * configuration at {@code config/checkstyle/checkstyle.xml} enables no comment-driven or
 * annotation-driven suppression filter at all, and its companion suppressions file reaches only
 * generated sources and test fixtures. A Java record reaches the same brevity by a route that keeps
 * the members documentable, and the four {@code @param} tags below are enforced there rather than
 * merely conventional.</p>
 *
 * <p>Alternatives Considered: collapsing {@code ABEND-REASON} and {@code ABEND-MSG} into one message
 * component was evaluated and rejected. The two are declared at different widths -- 50 at line 26 and
 * 72 at line 28 -- and a difference in declared width is a difference in contract, so merging them
 * would leave no way to say which of the two an inherited string came from. They also answer
 * different questions: one names the condition, the other is the text that was to be read. Keeping
 * them apart costs one extra component and preserves both the widths and that distinction.</p>
 *
 * <p>Assumptions: the 50-byte width is a house convention rather than a coincidence of this one
 * copybook, recurring across three unrelated files -- {@code CCDA-MSG-THANK-YOU} and
 * {@code CCDA-MSG-INVALID-KEY} at {@code app/cpy/CSMSG01Y.cpy} lines 18 and 20,
 * {@code ABEND-REASON} at {@code app/cpy/CSMSG02Y.cpy} line 26, and {@code ERR-MESSAGE} at
 * {@code CCPAUERY.cpy} line 39. This record contributes two of the package's four message-width
 * regimes, 50 and 72; the other two belong elsewhere and are named only so that no reader concludes
 * this record was meant to carry them -- 75 is the terminal message line at
 * {@code app/cpy/CVCRD01Y.cpy} lines 28 and 29, and 80 is the date edit utility's diagnostic
 * out-parameter at {@code app/cbl/CSUTLDTC.cbl} line 86. All four are modelled as four, and none is
 * treated as a canonical width from which the others are padded or truncated.</p>
 *
 * <p>Assumptions: the four widths are enforced at run time, and they are enforced the way the
 * baseline enforces them -- by shortening, never by refusing. An alphanumeric {@code MOVE} into a
 * {@code PIC X(n)} field pads a short value on the right with spaces and discards the surplus of a
 * long one from the right, so both directions are lossy-but-accepting and neither raises a
 * condition. The constructor applies exactly that: a component wider than the number beside it in
 * the table above is truncated on the right to that number, and a shorter one is left at its own
 * width. This is what lets the record assert something it could not assert before -- that any value
 * it holds would still have fitted the block it came from.</p>
 *
 * <p>Alternatives Considered: a rejecting constructor, and carrying the widths as documented
 * provenance only. The first was rejected on the evidence, because it would refuse inputs the
 * baseline itself accepts and would turn a diagnostic value into a second failure at the exact
 * moment the first one is being reported. The second was rejected because it left the constants
 * describing a constraint nothing applied, so a component could hold a string longer than its own
 * declared width and the table above would be documentation of an intention rather than of a
 * property. Truncating keeps the first objection satisfied -- nothing is refused and nothing is
 * thrown -- while making the table true.</p>
 *
 * <p>Trade-offs: what is given up is any signal that a value was shortened; truncation is silent, as
 * it is in the baseline. What is kept is that the widths are stated once, as constants, at the only
 * place that inherits them, and are now applied from there as well as quoted from there.</p>
 *
 * <h2>What "empty" means here, and what it deliberately cannot mean</h2>
 *
 * <p>Assumptions: this record's four components live in the blank-filled regime and take no part in
 * the low-values regime, and the two are not interchangeable. All four are initialised
 * {@code VALUE SPACES}, at lines 23, 25, 27 and 29 of {@code app/cpy/CSMSG02Y.cpy}, whereas
 * {@code app/cpy/CVCRD01Y.cpy} line 30 declares {@code 88 CCARD-RETURN-MSG-OFF VALUE LOW-VALUES}, a
 * sentinel attaching to the return message on line 29 alone. A blank-filled field is a message that
 * exists and is empty; a low-values field is a message that is off. Reducing both to one
 * representation would erase a difference the baseline can still see. This record therefore has
 * exactly one kind of empty, the blank one, and no message-off state to confuse with it; the
 * aggregate message whose absence is meaningful is {@code ApiError}'s, and it is nullable there for
 * that reason. The constructor below is what makes this structural rather than advisory.</p>
 *
 * <h2>From a terminal frame to a data surface, and the boundaries of this type</h2>
 *
 * <p>Refactoring Rationale: the baseline wrote these four fields to absolute positions on a terminal
 * frame of 24 rows by 80 columns, so their layout and their content arrived at the reader as one
 * thing. There is no terminal on the migrated path and no absolute position to write to, and the
 * replacement is not a smaller frame but a different kind of surface: the four fields become
 * components of a value that a caller places wherever its own layout puts them. The fields survive
 * and the positions do not, which is the intended outcome rather than a loss -- a value whose
 * geometry is decided by its consumer is renderable in a reflowing layout, quotable in a log line and
 * assertable in a test, none of which an absolutely positioned character block is. Nothing beneath
 * {@code app} is altered by this migration: the copybook stays byte-identical, the baseline does one
 * thing, the Java does another, and the divergence is documented rather than silently introduced.</p>
 *
 * <p>Assumptions: this record is a value and nothing else, and four capabilities a reader might
 * expect here live elsewhere on purpose. It renders no constant-width form of itself, because turning
 * these components back into a 134-byte layout is codec work and belongs beside the other record
 * layouts in {@code com.carddemo.common.codec}. It carries no serialisation annotation, because how a
 * value reaches the wire is settled once for the whole module and not per type. It logs nothing and
 * reads nothing, so it is safe to construct on a failure path where the cause of the failure may be
 * the very subsystem a logger would reach for. And it names no other type in this package: the
 * dependency runs from {@code ApiError} to this record and never back, so this record stays
 * constructible and testable without a response, a request or a servlet container.</p>
 *
 * <p>Assumptions: every component of this group item is a character field, so the sign-convention
 * hazard that governs the baseline's monetary records does not reach this one. Those records store
 * their values as zoned decimal with sign overpunch, which is why the pinned compiler invocation
 * recorded at {@code tests/README.md} line 268 selects {@code -fsign=EBCDIC} -- the default misreads
 * the overpunch and silently corrupts negative balances. No value carried here passes through a
 * numeric conversion at all, and none of this record's four components is a monetary amount.</p>
 *
 * @param abendCode the short condition code identifying the abend, inherited from
 *     {@code ABEND-CODE PIC X(4)} at {@code app/cpy/CSMSG02Y.cpy} line 22, whose declared width of
 *     4 is published as {@link #ABEND_CODE_LENGTH}; never {@code null} once constructed, and the
 *     empty string where the baseline held blanks
 * @param abendCulprit the name of the component held responsible for the abend, inherited from
 *     {@code ABEND-CULPRIT PIC X(8)} at {@code app/cpy/CSMSG02Y.cpy} line 24, whose declared width
 *     of 8 is published as {@link #ABEND_CULPRIT_LENGTH} and matches the length of an external
 *     program name in the baseline; never {@code null} once constructed
 * @param abendReason the reason the condition arose, inherited from
 *     {@code ABEND-REASON PIC X(50)} at {@code app/cpy/CSMSG02Y.cpy} line 26, whose declared width
 *     of 50 is published as {@link #ABEND_REASON_LENGTH} and is the recurring message width of the
 *     reference baseline; never {@code null} once constructed
 * @param abendMsg the text intended for whoever reads the failure, inherited from
 *     {@code ABEND-MSG PIC X(72)} at {@code app/cpy/CSMSG02Y.cpy} line 28, whose declared width of
 *     72 is published as {@link #ABEND_MSG_LENGTH} and is the widest component of the group item;
 *     never {@code null} once constructed, and kept distinct from {@code abendReason} rather than
 *     merged with it
 */
public record AbendDetail(
        String abendCode,
        String abendCulprit,
        String abendReason,
        String abendMsg) {

    /**
     * The declared width of the abend code component, four characters.
     *
     * <p>Assumptions: read from {@code ABEND-CODE PIC X(4)} at {@code app/cpy/CSMSG02Y.cpy} line
     * 22, whose {@code VALUE SPACES} clause follows on line 23. It is declared here as a named
     * constant rather than left as a literal at each place a width is quoted, because the number is
     * inherited from a file this migration does not alter: a literal repeated at three call sites
     * can disagree with the copybook at one of them without anything failing, whereas a single
     * constant can only be wrong everywhere at once and is therefore checkable by one test.</p>
     */
    public static final int ABEND_CODE_LENGTH = 4;

    /**
     * The declared width of the culprit component, eight characters.
     *
     * <p>Assumptions: read from {@code ABEND-CULPRIT PIC X(8)} at {@code app/cpy/CSMSG02Y.cpy}
     * line 24, whose {@code VALUE SPACES} clause follows on line 25. The width is not arbitrary and
     * that is why it is worth naming: eight characters is the length of an external program name in
     * the reference baseline, so this component was sized to hold the name of whichever program was
     * blamed, and a reader who knows that reads the value as an identifier rather than as free
     * text.</p>
     */
    public static final int ABEND_CULPRIT_LENGTH = 8;

    /**
     * The declared width of the reason component, fifty characters.
     *
     * <p>Assumptions: read from {@code ABEND-REASON PIC X(50)} at {@code app/cpy/CSMSG02Y.cpy}
     * line 26, whose {@code VALUE SPACES} clause follows on line 27. This is one of two distinct
     * widths this record contributes to the package, and it is deliberately not the same constant
     * as {@link #ABEND_MSG_LENGTH}: the reference baseline declares 50 here and 72 two components
     * later, and one constant serving both would assert an equality the copybook denies.</p>
     */
    public static final int ABEND_REASON_LENGTH = 50;

    /**
     * The declared width of the message component, seventy-two characters.
     *
     * <p>Assumptions: read from {@code ABEND-MSG PIC X(72)} at {@code app/cpy/CSMSG02Y.cpy} line
     * 28, whose {@code VALUE SPACES} clause follows on line 29. It is the widest component of the
     * group item, and it is the second of the two widths this record contributes to the package's
     * four message-width regimes.</p>
     */
    public static final int ABEND_MSG_LENGTH = 72;

    /**
     * The total declared width of the whole group item, one hundred and thirty-four bytes.
     *
     * <p>Assumptions: this is the sum of the four component widths and nothing else --
     * {@code 4 + 8 + 50 + 72 = 134} -- because {@code 01 ABEND-DATA} at
     * {@code app/cpy/CSMSG02Y.cpy} line 21 declares no filler and no other subordinate item across
     * lines 22 to 29. It is expressed below as that addition rather than as the digits 134 so that
     * the identity is checked by the compiler instead of being restated by hand, which is what
     * makes a future change to any one component width unable to leave this total stale.</p>
     */
    public static final int ABEND_DATA_LENGTH =
            ABEND_CODE_LENGTH + ABEND_CULPRIT_LENGTH + ABEND_REASON_LENGTH + ABEND_MSG_LENGTH;

    /**
     * The one message an externally visible abend carries, in place of the internal one.
     *
     * <p>Assumptions: the text is fixed and says only that the condition was recorded and how to reach
     * the record of it. It is deliberately identical for every abend, so two failures with different
     * internal causes are indistinguishable to a caller that provoked them, while the log the operator
     * reads still carries both causes in full. Its width is inside {@link #ABEND_MSG_LENGTH}, so the
     * external form of a detail needs no truncation of its own.</p>
     *
     * <p>Assumptions: that last property is a constraint on the wording rather than an observation about
     * it, and the wording is held to it deliberately. This literal measures 69 characters against the
     * declared 72 of {@code ABEND-MSG PIC X(72)} at {@code app/cpy/CSMSG02Y.cpy} line 28. The margin
     * matters because {@link #external()} passes this value back through the constructor, which shortens
     * an over-width component on the right without reporting that it did so: a longer sentence would
     * therefore reach a client severed mid-word, and the one message whose entire purpose is to tell a
     * caller how to have the failure investigated would be the one message that arrived unreadable.
     * Anything added here has to be paid for by removing something else.</p>
     *
     * <p>Alternatives Considered: composing an external message from the abend code, so a client could
     * read something specific. Rejected because the code already travels as its own component, so the
     * composition would add no information for the client while creating a second place the code is
     * rendered and could drift from the first.</p>
     */
    public static final String EXTERNAL_ABEND_MSG =
            "The request could not be completed. Quote the correlation identifier.";

    /**
     * Builds an abend detail, substituting the empty string for any component supplied as
     * {@code null}.
     *
     * <p>Assumptions: normalisation runs in the constructor so that an instance of this record can
     * never hold {@code null}, which is what keeps the blank-filled regime and the low-values regime
     * apart in the migrated model. The four components are declared {@code VALUE SPACES} at lines
     * 23, 25, 27 and 29 of {@code app/cpy/CSMSG02Y.cpy}, so every one of them always holds a value
     * and none of them can be absent; the sentinel that does mean absence, the
     * {@code VALUE LOW-VALUES} condition at {@code app/cpy/CVCRD01Y.cpy} line 30, belongs to a
     * different field in a different copybook. Admitting {@code null} here would introduce a third
     * state that the group item has no encoding for, and it is precisely that third state a caller
     * would then be tempted to read as message-off. Refusing to store it removes the temptation at
     * the only point where it could enter.</p>
     *
     * <p>Trade-offs: this constructor normalises and does not validate. It rejects nothing, so it
     * throws nothing and declares no exception clause. The alternative -- rejecting either a
     * {@code null} or an over-long component -- was considered and rejected because this type is
     * constructed on the path that reports a failure, and a constructor that can fail there replaces
     * a diagnostic the caller wanted with a second one it did not ask for. Substituting the empty
     * string is lossless in the direction that matters: no caller can distinguish it from the blanks
     * the baseline would have held, and the blank state remains reachable on purpose, by passing
     * {@code null} or {@code ""} for every component, which is this record's equivalent of the group
     * item's initialised state.</p>
     *
     * <p>Assumptions: normalisation also brings each component <b>to</b> its declared width, by
     * truncating on the right anything wider. That is not an addition to the baseline's rule, it is
     * the other half of it. An alphanumeric {@code MOVE} into a {@code PIC X(n)} field pads a short
     * value on the right with spaces and truncates a long one on the right, so a value that overruns
     * one of these four widths has never been stored whole by the baseline either -- the surplus
     * characters are discarded at the move, and every {@code MOVE} into
     * {@code app/cpy/CSMSG02Y.cpy}'s four components behaves that way. Accepting an over-width value
     * into this record would therefore let it hold something the group item it models cannot
     * represent, which is precisely the fidelity the four width constants exist to assert.</p>
     *
     * <p>Alternatives Considered: rejecting an over-width component with an exception, which reads as
     * the stricter option. Rejected, because it refuses input the baseline accepts -- the baseline
     * silently shortens it -- and because throwing here would defeat the whole reason this type
     * exists. Also considered: leaving over-width values intact and enforcing the widths only where
     * the record is serialised. Rejected because it would put the same rule in as many places as
     * there are writers of this record, each free to apply it differently, and a record that has
     * already been constructed too wide gives no writer a principled way to decide which end to
     * shorten. Applying it once, at the only point where a value enters, makes the width a property
     * of the type rather than of its consumers.</p>
     *
     * <p>Trade-offs: truncation is silent, so a caller that supplied a message longer than
     * {@code ABEND_MSG_LENGTH} receives an instance holding less than it passed and is told nothing.
     * That is accepted for the same reason nothing is thrown: this object is built while a failure is
     * already being reported, and the useful part of a diagnostic is at its beginning. Truncating on
     * the right rather than the left follows from that too -- and from the baseline, which discards
     * from the right -- so the condition code, the culprit and the opening words of the reason
     * survive in every case. What is given up is the ability to notice, from the value alone, that
     * something was shortened.</p>
     *
     * @param abendCode the short condition code, or {@code null} to record it as empty; truncated on
     *     the right to {@code ABEND_CODE_LENGTH} characters when wider
     * @param abendCulprit the name of the component held responsible, or {@code null} to record it
     *     as empty; truncated on the right to {@code ABEND_CULPRIT_LENGTH} characters when wider
     * @param abendReason the reason the condition arose, or {@code null} to record it as empty;
     *     truncated on the right to {@code ABEND_REASON_LENGTH} characters when wider
     * @param abendMsg the text intended for whoever reads the failure, or {@code null} to record it
     *     as empty; truncated on the right to {@code ABEND_MSG_LENGTH} characters when wider
     */
    public AbendDetail {
        abendCode = conformToDeclaredWidth(abendCode, ABEND_CODE_LENGTH);
        abendCulprit = conformToDeclaredWidth(abendCulprit, ABEND_CULPRIT_LENGTH);
        abendReason = conformToDeclaredWidth(abendReason, ABEND_REASON_LENGTH);
        abendMsg = conformToDeclaredWidth(abendMsg, ABEND_MSG_LENGTH);
    }

    /**
     * Returns the given value normalised against {@code null} and shortened to a declared width.
     *
     * <p>Assumptions: the two rules are applied together because they are two halves of one
     * baseline behaviour -- an alphanumeric {@code MOVE} into a fixed-width field neither leaves it
     * absent nor lets it overrun -- and separating them would let a caller of this class apply one
     * without the other. A short value is <b>not</b> padded out to the width here, because this
     * migration treats the trailing spaces of a declared-width field as padding rather than as data;
     * widening a short value would add characters the caller did not supply, which is a different
     * and worse infidelity than the one being corrected.</p>
     *
     * <p>Trade-offs: the width arrives as a parameter rather than being selected inside this method
     * from the component being normalised. The alternative -- four single-purpose methods, or one
     * method switching on a component name -- was rejected because the association between a
     * component and its width is already stated once, visibly, at the four call sites in the
     * constructor above, and restating it here would give it a second home that could disagree with
     * the first.</p>
     *
     * @param value the component value as supplied by the caller, possibly {@code null}
     * @param declaredWidth the count of characters the corresponding copybook component declares;
     *     always one of the four width constants on this record
     * @return the empty string when the value is {@code null}, the value unchanged when it already
     *     fits the declared width, otherwise its leading {@code declaredWidth} characters
     */
    private static String conformToDeclaredWidth(String value, int declaredWidth) {
        String normalised = blankIfNull(value);

        // WHY : Assumptions: the comparison is on the count of CHARACTERS, and that is the same as a
        //       count of bytes for the values this record carries because the copybook components are
        //       single-byte alphanumeric fields. The guard is written as a length test rather than an
        //       unconditional substring so that the common case -- a value that already fits --
        //       returns the caller's own instance untouched, which keeps the verbatim guarantee
        //       literal rather than merely equivalent.
        String withinWidth = normalised.length() <= declaredWidth
                ? normalised
                : normalised.substring(0, declaredWidth);

        // WHY : Assumptions: a control character reaching a component is a genuine hazard rather than a
        //       cosmetic defect, because these four values are written to a structured log line and
        //       rendered into a response body. A carriage return or line feed in composed text splits
        //       one log record into two, so a value carrying one can forge a second record that looks
        //       as authentic as the first, and the same value in a response body corrupts the framing
        //       of whatever renders it. Replacing each control character with a space keeps the
        //       component's declared width and its reading order exactly as the baseline has them while
        //       removing the only characters that can change how the value is FRAMED rather than what
        //       it says.
        //       Alternatives Considered: rejecting a value that carries a control character. Declined --
        //       this record is constructed on the failure path, so a refusal here would replace the
        //       failure being reported with a second failure in the reporting itself, which is the one
        //       moment a diagnostic must not be lost. Substitution preserves the report.
        //       Alternatives Considered: escaping rather than substituting, so the original bytes stay
        //       recoverable. Declined because an escape sequence is longer than what it replaces, which
        //       either overflows the declared width this method exists to hold or forces a second
        //       truncation, and neither leaves the verbatim guarantee intact.
        return sanitizeControlCharacters(withinWidth);
    }

    /**
     * Returns the form of this detail that is safe to place in a response body.
     *
     * <p>Refactoring Rationale: this record served two audiences with one value, and they need
     * different things. An operator reading a log needs the culprit, the reason and the message,
     * because those three name which component failed and why. A client receiving an error response
     * needs a stable code it can branch on and nothing else: the other three are composed from
     * internal state -- a class or program name, an internal reason, an internal message -- and
     * returning them describes the inside of the system to whoever provoked the failure, which is how a
     * probe turns an error into a map. Separating the two forms here means the choice is made once, in
     * a named method, rather than by each handler deciding how much of a failure to disclose.</p>
     *
     * <p>Assumptions: the abend code is the one component that crosses the boundary, because it is a
     * short condition code drawn from a closed set rather than composed text -- the baseline declares
     * it {@code PIC X(4)} at {@code app/cpy/CSMSG02Y.cpy} line 22 -- so it identifies the class of
     * failure without describing its circumstances. The message is replaced by
     * {@link #EXTERNAL_ABEND_MSG}, a fixed literal, so that two failures of different internal causes
     * are indistinguishable from outside while remaining fully distinguishable in the log the operator
     * reads.</p>
     *
     * <p>Trade-offs: a client can no longer read the cause of a failure out of the response, so
     * diagnosing a report of one requires correlating to the log by the correlation identifier the
     * shared filter attaches. That correlation is the intended path and it exists for this purpose; the
     * alternative -- a response that explains the internals of the failure -- makes every failure a
     * source of information about the system to anyone able to cause one.</p>
     *
     * @return a detail carrying this detail's abend code, {@link #EXTERNAL_ABEND_MSG} as its message,
     *     and the blank state for the culprit and the reason; never {@code null}, and never sharing a
     *     component with this instance beyond the code
     */
    public AbendDetail external() {
        return new AbendDetail(abendCode, "", "", EXTERNAL_ABEND_MSG);
    }

    /**
     * Replaces every ISO control character in a value with a single space.
     *
     * <p>Refactoring Rationale: the scan itself now lives in
     * {@link com.carddemo.common.observability.LogSafeText} and this method delegates to it. It was
     * moved because a second site needed the identical rule -- the batch entry point sanitises an
     * operator-supplied argument and an orchestrator-supplied environment override before logging
     * either -- and two copies of one neutralisation rule are two places for the covered character
     * range to be narrowed unevenly. This method is kept rather than inlined at its one call site so
     * that the reason the components of this type are sanitised at all stays recorded where the
     * components are.</p>
     *
     * @param value the width-conformed component value, never {@code null}
     * @return {@code value} itself when it carries no control character, so the verbatim guarantee is
     *     literal for the ordinary case, and otherwise a same-length copy with each control character
     *     replaced by a space
     */
    private static String sanitizeControlCharacters(String value) {
        return LogSafeText.sanitize(value);
    }

    /**
     * Returns the given value, or the empty string when it is {@code null}.
     *
     * <p>Alternatives Considered: writing the same conditional inline at each of the four
     * assignments above, or delegating to a general-purpose utility from a third-party library. The
     * first was rejected because four copies of one rule are four places it can be edited unevenly,
     * and the rule here is a contract read from a copybook rather than a convenience. The second was
     * rejected because this module is the shared kernel every service depends on and nothing depends
     * on in turn, so taking a dependency to avoid one conditional would push that dependency onto
     * all eight services. Naming the rule once, privately, also gives the reason for it a single
     * place to be written down.</p>
     *
     * @param value the component value as supplied by the caller, possibly {@code null}
     * @return the same value when it is non-{@code null}, otherwise the empty string, so that the
     *     component matches the blank state the reference baseline initialises the group item to
     */
    private static String blankIfNull(String value) {
        return value == null ? "" : value;
    }
}
