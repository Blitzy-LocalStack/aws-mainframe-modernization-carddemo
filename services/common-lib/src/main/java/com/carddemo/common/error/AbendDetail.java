package com.carddemo.common.error;

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
 * <p>Each width is exposed as a constant on this record so that a reader, a test or a downstream
 * renderer can name the number rather than restate it: {@link #ABEND_CODE_LENGTH},
 * {@link #ABEND_CULPRIT_LENGTH}, {@link #ABEND_REASON_LENGTH}, {@link #ABEND_MSG_LENGTH} and their
 * sum {@link #ABEND_DATA_LENGTH}.</p>
 *
 * <h2>Why a record, and why exactly four components</h2>
 *
 * <p>Alternatives Considered: an ordinary class with library-generated accessors, specifically
 * Lombok, was evaluated and rejected. A generated accessor has no source of its own on which a
 * Javadoc block can be written, so a Lombok-built form of this type either fails the documentation
 * gate outright or has to be exempted from it, and no exemption is available: the audit
 * configuration at {@code config/checkstyle/checkstyle.xml} enables no comment-driven or
 * annotation-driven suppression filter at all, and its companion suppressions file reaches only
 * generated sources and test fixtures. A Java record reaches the same brevity by a route that keeps
 * the members documentable, because {@code JavadocType} is configured there with
 * {@code allowMissingParamTags="false"} and {@code MissingJavadocType} lists {@code RECORD_DEF}
 * among its tokens, so the four {@code @param} tags below are enforced rather than merely
 * conventional.</p>
 *
 * <p>Assumptions: the group item has exactly four components and this record has exactly four.
 * Read from {@code app/cpy/CSMSG02Y.cpy}: {@code ABEND-CODE PIC X(4)} at line 22,
 * {@code ABEND-CULPRIT PIC X(8)} at line 24, {@code ABEND-REASON PIC X(50)} at line 26 and
 * {@code ABEND-MSG PIC X(72)} at line 28. Those widths sum as {@code 4 + 8 + 50 + 72 = 134} bytes,
 * and the arithmetic is stated because it is what makes the component list verifiable rather than
 * asserted: a fifth component would change the sum. No fifth component is added here. A diagnostic
 * timestamp, a correlation identifier, a severity or a rendered stack trace would each be an
 * invention at this level, and the response-shaping concerns that need them belong to
 * {@code ApiError}, which is the type that assembles a response and the only one of the two that
 * knows a request is in progress.</p>
 *
 * <h2>The extent of the block, and a citation that cannot be right</h2>
 *
 * <p>Refactoring Rationale: an earlier statement of this record's provenance placed the abend
 * fields at lines 45 to 53 of {@code app/cpy/CSMSG02Y.cpy}. That file is 35 lines long, so the
 * range names lines that do not exist and the file itself refutes it; the citations throughout this
 * type are the ones read from the file. The reason the range was plausible is worth recording,
 * because it is the shape of the error rather than the error itself that would otherwise recur:
 * every component in this group item spends two lines, its {@code PIC} clause on one and its
 * {@code VALUE SPACES} clause on the next, so four components produce eight declarations and the
 * eight declarations plus the group item occupy exactly nine lines, 21 through 29. Counting the
 * components as one line each understates the block, and counting from the wrong origin then
 * carries the whole range past the end of a file that has 35 lines in it.</p>
 *
 * <p>Assumptions: that same copybook announces itself under a different name. Its line 2 reads
 * {@code 000800* CABENDD.CPY}, which is neither the name the file is stored under nor the name any
 * program uses to include it. The mismatch is recorded here so that a reader tracing this record's
 * lineage searches for the storage name and is not sent looking for a second copybook under the
 * announced title, and so that finding no such file is not mistaken for something missing. The file
 * carries further evidence of the same history: its lines 1 to 4 still hold legacy sequence numbers
 * 000700 through 001000 while lines 5 to 20 hold none, and its lines 30 to 32 are bare blank
 * lines.</p>
 *
 * <h2>Fifty and seventy-two are two widths, and they stay two</h2>
 *
 * <p>Alternatives Considered: collapsing {@code ABEND-REASON} and {@code ABEND-MSG} into one
 * message component was evaluated and rejected. The two are declared at different widths -- 50 at
 * line 26 and 72 at line 28 -- and a difference in declared width is a difference in contract, so
 * merging them would fold two of this package's four message-width regimes into one and leave no
 * way to say which of the two an inherited string came from. The reason and the message also answer
 * different questions in the block they come from: one names the condition, the other is the text
 * that was to be read. Keeping them apart costs one extra component and preserves both the widths
 * and that distinction.</p>
 *
 * <p>Assumptions: the 50-byte width is a house convention rather than a coincidence of this one
 * copybook, which is why it is recorded as a regime rather than as a local number. It recurs across
 * three unrelated files: {@code CCDA-MSG-THANK-YOU} at {@code app/cpy/CSMSG01Y.cpy} lines 18 and 19
 * and {@code CCDA-MSG-INVALID-KEY} at its lines 20 and 21, {@code ABEND-REASON} at
 * {@code app/cpy/CSMSG02Y.cpy} lines 26 and 27, and {@code ERR-MESSAGE} at {@code CCPAUERY.cpy}
 * line 39. This record contributes two of the package's four regimes, 50 and 72. The remaining two
 * belong elsewhere and are named here only so that no reader concludes this record was meant to
 * carry them: 75 is the terminal message line, declared as {@code CCARD-ERROR-MSG} and
 * {@code CCARD-RETURN-MSG} at {@code app/cpy/CVCRD01Y.cpy} lines 28 and 29 -- that copybook and not
 * {@code CSMSG01Y}, which declares no field of that width -- and 80 is the date edit utility's
 * diagnostic out-parameter, {@code 01 LS-RESULT PIC X(80)} in the linkage section at
 * {@code app/cbl/CSUTLDTC.cbl} line 86. All four regimes are modelled as four, and not one of them
 * is treated as the canonical width from which the others are padded or truncated.</p>
 *
 * <p>Trade-offs: the four widths are carried as documented provenance and are not enforced at run
 * time, so a component of this record accepts a string longer than the number beside it in the
 * table above. A rejecting constructor was considered and rejected on the evidence: the baseline
 * pads rather than rejects. All four components are declared {@code VALUE SPACES} -- at lines 23,
 * 25, 27 and 29 -- and a declared-width character field is blank-filled to its width, so a
 * length-rejecting constructor would refuse inputs the baseline itself accepts and would turn a
 * diagnostic value into a second failure at the exact moment the first one is being reported. What
 * is given up is the ability to prove by construction that a migrated abend value would still have
 * fitted the block it came from. What is kept is that the widths are stated once, as constants, at
 * the only place that inherits them.</p>
 *
 * <h2>What "empty" means here, and what it deliberately cannot mean</h2>
 *
 * <p>Assumptions: this record's four components live in the blank-filled regime and take no part in
 * the low-values regime, and the two are not interchangeable. All four are initialised
 * {@code VALUE SPACES}, at lines 23, 25, 27 and 29 of {@code app/cpy/CSMSG02Y.cpy}. A different
 * copybook encodes absence a different way: {@code app/cpy/CVCRD01Y.cpy} line 30 declares
 * {@code 88 CCARD-RETURN-MSG-OFF VALUE LOW-VALUES}, a sentinel that attaches to the return message
 * on line 29 alone. A blank-filled field and a low-values field are distinguishable in the baseline
 * and they mean different things: the first is a message that exists and is empty, the second is a
 * message that is off. Reducing both to one representation would erase a difference the baseline can
 * still see. This record therefore has exactly one kind of empty, the blank one, and it has no
 * message-off state to confuse with it; the aggregate message whose absence is meaningful is
 * {@code ApiError}'s, and it is nullable there for that reason. The constructor below is what makes
 * this structural rather than advisory.</p>
 *
 * <h2>From a terminal frame to a data surface</h2>
 *
 * <p>Refactoring Rationale: the baseline wrote these four fields to absolute positions on a
 * terminal frame of 24 rows by 80 columns, so their layout and their content arrived at the reader
 * as one thing. There is no terminal on the migrated path and no absolute position to write to, and
 * the replacement is not a smaller frame but a different kind of surface: the four fields become
 * components of a value that a caller places wherever its own layout puts them. The fields survive
 * and the positions do not, which is the intended outcome rather than a loss -- a value whose
 * geometry is decided by its consumer is renderable in a reflowing layout, quotable in a log line
 * and assertable in a test, none of which an absolutely positioned character block is. Nothing
 * beneath {@code app} is altered by this migration: the copybook stays byte-identical, the baseline
 * does one thing, the Java does another, and the divergence is documented rather than silently
 * introduced.</p>
 *
 * <h2>Boundaries of this type</h2>
 *
 * <p>Assumptions: this record is a value and nothing else, and four capabilities that a reader might
 * expect to find here live elsewhere on purpose. It renders no constant-width form of itself, because
 * turning these components back into a 134-byte layout is codec work and belongs beside the other
 * record layouts in {@code com.carddemo.common.codec}. It carries no serialisation annotation,
 * because how a value reaches the wire is settled once for the whole module and not per type. It
 * logs nothing and reads nothing, so it is safe to construct on a failure path where the cause of
 * the failure may be the very subsystem a logger would reach for. And it names no other type in this
 * package: the dependency runs from {@code ApiError} to this record and never back, so this record
 * stays constructible and testable without a response, a request or a servlet container.</p>
 *
 * <p>Assumptions: the sign-convention hazard that governs the baseline's monetary records does not
 * reach this one, and saying so is cheaper than leaving a reader to work it out. Those records store
 * their values as zoned decimal with sign overpunch, which is why the pinned compiler invocation
 * recorded at {@code tests/README.md} line 268 selects {@code -fsign=EBCDIC}: the default
 * {@code -fsign=ASCII} misreads the overpunch and silently corrupts negative balances, as that file
 * states at its lines 273 and 274. Every component of this group item is a character field, so no
 * value carried here passes through a numeric conversion at all and none of this record's four
 * components is a monetary amount.</p>
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
     * throws nothing and declares no exception clause, which is a deliberate consequence of the
     * padding behaviour recorded on this record: a value that overruns a declared width is accepted
     * here exactly as the baseline's blank-filling accepts a short one. The alternative -- rejecting
     * either a {@code null} or an over-long component -- was considered and rejected because this
     * type is constructed on the path that reports a failure, and a constructor that can fail there
     * replaces a diagnostic the caller wanted with a second one it did not ask for. Substituting the
     * empty string is lossless in the direction that matters: no caller can distinguish it from the
     * blanks the baseline would have held, and the blank state remains reachable on purpose, by
     * passing {@code null} or {@code ""} for every component, which is this record's equivalent of
     * the group item's initialised state.</p>
     *
     * @param abendCode the short condition code, or {@code null} to record it as empty
     * @param abendCulprit the name of the component held responsible, or {@code null} to record it
     *     as empty
     * @param abendReason the reason the condition arose, or {@code null} to record it as empty
     * @param abendMsg the text intended for whoever reads the failure, or {@code null} to record it
     *     as empty
     */
    public AbendDetail {
        abendCode = blankIfNull(abendCode);
        abendCulprit = blankIfNull(abendCulprit);
        abendReason = blankIfNull(abendReason);
        abendMsg = blankIfNull(abendMsg);
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
