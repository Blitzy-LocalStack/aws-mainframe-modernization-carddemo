package com.carddemo.batch.dto;

import java.util.Map;

/**
 * The outcome of one batch step as the step itself measured it: its identity in the durable step
 * ledger, the tier it finished in, four record counts, and an optional per-record-type breakdown.
 *
 * <h2>Purpose, and the one measurement that fixes this record's shape</h2>
 *
 * <p><b>Purpose.</b> This record carries what a finished step knows about its own run, so that the
 * ledger row keyed to that step, the orchestration decision taken after it, and any line the step
 * chooses to write are all reading one set of values rather than three separately assembled ones. It
 * is a value and nothing more: it holds no connection, opens no file, reads no clock, and renders no
 * text.</p>
 *
 * <p>The shape below follows from a measurement rather than from taste, and the measurement is worth
 * reading before the components are. The five reference programs this module re-expresses keep
 * between zero and eight run counters each, and <b>no two of them agree</b>. Every counter named
 * here is declared {@code PIC 9(09) VALUE 0}:</p>
 *
 * <dl>
 *   <dt>{@code app/cbl/CBTRN01C.cbl}, 494 lines: ZERO counters</dt>
 *   <dd>The pre-posting validation pass declares no counter group whatever and writes no summary
 *       line. A summary describing this step therefore reports four zeroes quite legitimately, and a
 *       reader who takes four zeroes for a defect has misread the reference rather than the
 *       run.</dd>
 *
 *   <dt>{@code app/cbl/CBACT04C.cbl}, 652 lines: ONE counter</dt>
 *   <dd>{@code WS-RECORD-COUNT} at line 172, incremented at line 192. It is never displayed
 *       anywhere: the program's last line, 230, writes only
 *       {@code 'END OF EXECUTION OF PROGRAM CBACT04C'}. An interest summary consequently reports a
 *       read count, no rejects and the clean tier.</dd>
 *
 *   <dt>{@code app/cbl/CBTRN02C.cbl}, 731 lines: TWO counters</dt>
 *   <dd>{@code WS-TRANSACTION-COUNT} at line 185 and {@code WS-REJECT-COUNT} at line 186,
 *       incremented at lines 206 and 214 respectively. These two are the only counters in the whole
 *       reference set that reach a return code, by way of the invariant recorded below.</dd>
 *
 *   <dt>{@code app/cbl/CBEXPORT.cbl}, 582 lines: SIX counters</dt>
 *   <dd>Lines 139 to 144, one per record type and then a total:
 *       {@code WS-CUSTOMER-RECORDS-EXPORTED}, {@code WS-ACCOUNT-RECORDS-EXPORTED},
 *       {@code WS-XREF-RECORDS-EXPORTED}, {@code WS-TRAN-RECORDS-EXPORTED},
 *       {@code WS-CARD-RECORDS-EXPORTED} and {@code WS-TOTAL-RECORDS-EXPORTED}.</dd>
 *
 *   <dt>{@code app/cbl/CBIMPORT.cbl}, 487 lines: EIGHT counters</dt>
 *   <dd>Lines 140 to 147: {@code WS-TOTAL-RECORDS-READ}, then the same five per-type counters in
 *       their imported form, then {@code WS-ERROR-RECORDS-WRITTEN} at line 146 and
 *       {@code WS-UNKNOWN-RECORD-TYPE-COUNT} at line 147.</dd>
 * </dl>
 *
 * <p>Trade-offs: <b>four generic counters plus an optional per-type breakdown, rather than a counter
 * set per job.</b> Zero, one, two, six and eight is not a shape that can be shared, so a faithful
 * per-job design would need five separate records and a common supertype for the orchestration layer
 * to hold them by -- five types to document, five to test, and a supertype whose only members would
 * be the identity and the tier. The compromise accepted instead is that some job-specific meaning is
 * flattened into four general names: the reference's own {@code WS-TRANSACTION-COUNT} and
 * {@code WS-TOTAL-RECORDS-READ} both arrive here as a read count, and their difference in emphasis
 * is lost. The per-type breakdown exists precisely to repay that loss for the two jobs where the
 * detail is the point, since the six and eight counters above are mostly one number per record
 * type.</p>
 *
 * <p>Trade-offs: <b>the four counters are declared flat rather than grouped into a nested holder
 * record.</b> Grouping them would shorten this type's component list from nine to six, and it was
 * evaluated on exactly that ground. It is not done because the invariant recorded below spans three
 * components at once -- the job name, the tier and the rejected count -- and nesting one of the three
 * behind a holder gives that invariant a precondition it does not otherwise have: the constructor
 * would first have to establish that the holder exists before it could evaluate the rule that
 * matters. The non-negativity of the four counters would likewise have to live in the nested type's
 * own constructor, which splits one rule across two constructors in one file. The accepted cost is
 * that this is the widest record in the package, with nine parameter descriptions to write and nine
 * accessors to document.</p>
 *
 * <p>Assumptions: <b>a skipped count exists as a fourth counter because the reference distinguishes
 * skipping from rejecting</b>, and collapsing the two would lose a distinction the reference draws.
 * {@code app/cbl/CBIMPORT.cbl:147} declares {@code WS-UNKNOWN-RECORD-TYPE-COUNT} separately from the
 * error counter one line above it, so a record of no recognised type is counted apart from a record
 * that failed a rule. The interest accrual draws the same line differently: its rate gate at
 * {@code app/cbl/CBACT04C.cbl:214}, {@code IF DIS-INT-RATE NOT = 0}, passes over a category balance
 * that carries no rate, and that row was read and counted at line 192 without being rejected by
 * anything. Folding either case into the rejected count would inflate a number the posting invariant
 * reads, and folding it into the read count would lose it entirely.</p>
 *
 * <h2>This record renders nothing, and the refusal is deliberate</h2>
 *
 * <p>Alternatives Considered: a method on this record that renders the counts as summary lines.
 * <b>Rejected, because there is no format to render.</b> The reference programs use four different
 * ones and share none:</p>
 *
 * <ul>
 *   <li>{@code app/cbl/CBTRN02C.cbl:227-228} writes bare upper-case text with no program prefix,
 *       and aligns its two colons by padding: line 227 is
 *       {@code DISPLAY 'TRANSACTIONS PROCESSED :' WS-TRANSACTION-COUNT} with <b>one</b> space before
 *       the colon, and line 228 is {@code DISPLAY 'TRANSACTIONS REJECTED  :' WS-REJECT-COUNT} with
 *       <b>two</b>, so that the two colons land in the same column. That padding is a byte-exact
 *       contract wherever those lines are compared, and <b>rendering it belongs to the posting job,
 *       not to this record.</b> It is written down here so that the job's author finds the spacing
 *       recorded rather than inferred.</li>
 *   <li>{@code app/cbl/CBEXPORT.cbl:563-572} prefixes every line with {@code 'CBEXPORT: '} and uses
 *       mixed case with no alignment padding at all.</li>
 *   <li>{@code app/cbl/CBIMPORT.cbl:468-477} does the same with its own {@code 'CBIMPORT: '}
 *       prefix.</li>
 *   <li>{@code app/cbl/CBACT04C.cbl} and {@code app/cbl/CBTRN01C.cbl} write no summary line of any
 *       kind; their closing output is limited to error and abnormal-termination text.</li>
 * </ul>
 *
 * <p>A single renderer on this type would therefore have to invent a fifth format, and would emit
 * text that no reference program produces while looking authoritative enough to be trusted. Each job
 * writes its own lines from these values instead. That is also why the per-type breakdown makes no
 * promise about iteration order, as recorded further down.</p>
 *
 * <h2>The warn-tier invariant, and exactly how far it reaches</h2>
 *
 * <p>Assumptions: <b>for the posting job the soft-warn tier holds if and only if the rejected count
 * exceeds zero</b>, and this constructor enforces the biconditional in both directions.
 * {@code app/cbl/CBTRN02C.cbl:229-231} is the whole of the rule -- line 229 tests
 * {@code IF WS-REJECT-COUNT > 0}, line 230 reads {@code MOVE 4 TO RETURN-CODE}, and line 231 closes
 * it -- so a posting summary claiming the warn tier with nothing rejected, or the clean tier with
 * records rejected, describes a run the reference cannot produce. Enforcing it here makes such a
 * summary unconstructable rather than merely wrong, which matters because this is the value the
 * following orchestration choice reads: that choice admits a tier of four or lower, so a fabricated
 * warn tier would send the chain down a path the reference never takes, and a fabricated clean tier
 * would hide rejects that were written.</p>
 *
 * <p>Assumptions: <b>only the posting job may report the soft-warn tier at all</b>, and that is
 * enforced too. Searching each of the five reference programs for {@code RETURN-CODE} finds exactly
 * one occurrence in the entire set, at {@code app/cbl/CBTRN02C.cbl:230}: the pre-posting pass, the
 * interest accrual, the export and the import each contain no such statement anywhere. The tier
 * arriving from any other job is therefore a defect in that job rather than a business outcome, which
 * is the same position {@code BatchReturnCode} states on its middle constant and the same one the
 * step ledger entity records on its return-code column.</p>
 *
 * <p>Assumptions: <b>the biconditional reaches the posting job only, and this scope is load-bearing
 * rather than cautious.</b> The import program declares {@code WS-ERROR-RECORDS-WRITTEN} at
 * {@code app/cbl/CBIMPORT.cbl:146} and writes those records, yet sets no return code at all, so an
 * import run that rejected records finishes in the clean tier. Applying the rule to every job would
 * make that faithful summary unconstructable and would force the import job to misreport either its
 * tier or its rejects. The rule is scoped to the one job whose reference sets a code.</p>
 *
 * <p>Assumptions: <b>the failure tier is exempt from the biconditional</b>, and the exemption is not
 * a loophole. A step that ends abnormally never reaches line 230 to assign anything, so its tier
 * carries no information about its counters: a run can reject records and then fail. Extending the
 * rule to require that a failed run rejected nothing would reject the most ordinary partial-run
 * summary there is.</p>
 *
 * <h2>What this record deliberately does not carry</h2>
 *
 * <p>Alternatives Considered: carrying the step's lifecycle state here alongside the tier. Rejected,
 * because they are independent axes and {@code com.carddemo.batch.domain.BatchRun} already owns the
 * second one as a nested enumeration of its own. A lifecycle state records how far a step got --
 * opened, finished, ended badly -- and exists from the moment the step opens, when no tier is known
 * yet; a tier records what a finished step is reporting. One type carrying both would make an
 * impossible pair representable, an opened step already reporting a clean tier being the obvious
 * one. <b>This record declares no lifecycle enumeration and no lifecycle constant.</b></p>
 *
 * <p>Trade-offs: <b>no start or finish time is carried, and no elapsed measure.</b> Those are set
 * where the ledger row is written, in the {@code started_at} and {@code finished_at} columns of the
 * entity named above, and the twenty-six-character microsecond text form they render through belongs
 * to {@code com.carddemo.common.time.TimestampFormatter}. Carrying them here as well would give one
 * column two sources that nothing could reconcile once they disagreed. The cost accepted is real and
 * should be understood by anyone reading a summary in isolation: <b>this value does not describe when
 * its run happened, and it cannot be made to.</b> A reader needing that asks the ledger row this
 * summary is keyed to.</p>
 *
 * <p>Refactoring Rationale: an earlier forecast of this type, recorded in this package's charter
 * while the type was still marked as not yet authored, described it as aligning member for member
 * with the ledger columns -- lifecycle state and the two time columns included -- and as echoing
 * itself to standard output. That forecast is superseded by the two findings above, and the reason it
 * was wrong is specific rather than stylistic. Aligning with the ledger columns would have imported
 * an axis that another type already owns and duplicated two columns that are set at persistence,
 * while echoing to standard output presumes a shared line format that the four divergent reference
 * formats listed above demonstrate does not exist. What the charter entry got right is the part kept
 * here: the identity pair below is exactly the ledger's own key.</p>
 *
 * <p>Assumptions: <b>the export program's sequence counter is excluded, and the near miss is worth
 * naming.</b> {@code WS-SEQUENCE-COUNTER} at {@code app/cbl/CBEXPORT.cbl:123} is nine digits like
 * every counter above, and it sits among the export program's own working fields, so it reads like a
 * seventh export count. It is not one: lines 277, 346 and 410 each move it into
 * {@code EXPORT-SEQUENCE-NUM}, the sequence number of the record being written. It numbers records;
 * it does not summarise a run, and reporting it as a count would inflate a total with a generator's
 * high-water mark.</p>
 *
 * <p>Alternatives Considered: a convenience predicate here answering whether the run finished
 * cleanly. Rejected, because {@code BatchReturnCode} already owns both questions a caller asks of a
 * tier -- the number it reports and whether it permits the following state to run -- and a predicate
 * here would merely wrap one enumeration comparison behind a second name. That type's own charter
 * takes the same position in its own words, that a member is added when it carries a decision and
 * not when it wraps one, and a caller with a summary reaches the same answer in one hop through
 * {@link #returnCode()}.</p>
 *
 * <h2>Identity, immutability and equality</h2>
 *
 * <p>Assumptions: <b>the run identifier and the step name are both carried because together they are
 * the ledger's key.</b> The entity named above declares them a unique pair, and that pair is not only
 * the row key: it is the per-step idempotency key that lets a redriven step recognise work it has
 * already done and complete as a no-op. A summary without both cannot be matched to its row, and a
 * redrive could not tell a repeat from a first attempt. Their column widths, eighty and one hundred
 * characters, are the entity's constraint and are deliberately not restated here, so that one rule
 * does not come to exist in two files with two chances to drift.</p>
 *
 * <p>Trade-offs: <b>the counters are {@code long} rather than {@code int}.</b> Every reference
 * counter is {@code PIC 9(09)}, which stops at 999,999,999 and fits an {@code int} with room to
 * spare, so the wider type is not needed to hold a single step's count. It is chosen because these
 * values are summed -- across the steps of one run, and across runs -- and a width that is merely
 * sufficient per step invites the question of whether an aggregate can overflow at every place an
 * aggregate is taken. The wider type answers that question once, here, and the cost of the choice is
 * four bytes per component that no run will use.</p>
 *
 * <p>Assumptions: <b>the per-type breakdown is copied on the way in and is unmodifiable
 * afterwards.</b> A record component holding a collection is not immutable by virtue of being a
 * record component: the caller keeps a reference to whatever was passed, and through it could change
 * a summary that has already been read, compared or written to the ledger. The copy is therefore
 * load-bearing rather than ceremonial, and it also settles what the map may contain -- no absent key,
 * no absent count, and no negative count, the last checked by the same rule the four flat counters
 * obey.</p>
 *
 * <p>Assumptions: <b>the breakdown's iteration order is not part of this record's contract.</b> The
 * copy is unordered by construction, and a caller that renders one line per entry by iterating it
 * will produce lines in an order this type does not promise. The export program's six lines at
 * {@code app/cbl/CBEXPORT.cbl:563-572} are in one settled order, so a job reproducing them iterates
 * its own list of keys and looks each one up here. Map equality is order-independent, so nothing
 * about equality depends on this.</p>
 *
 * <p>The generated {@code equals} and {@code hashCode} are left exactly as the record produces them,
 * and value equality is the contract: two summaries agreeing on all nine components are the same
 * summary. The breakdown participates in that equality, by map equality, so two summaries whose
 * breakdowns hold equal entries are equal whatever order those entries were supplied in, and one
 * differing entry makes them unequal. The generated {@code toString} is likewise left alone, which
 * wraps the values in the type and component names and so cannot be mistaken for a rendered summary
 * line -- a hand-written one returning bare text would eventually be used as though it were the
 * output format this type has just declined to own.</p>
 *
 * <h2>Baseline lineage: provenance only</h2>
 *
 * <p>The citations in this file are provenance. Nothing under {@code app/**} is read at run time and
 * nothing under it is altered by this migration: the reference implementation is the behavioural
 * oracle and stays byte-identical. Where migrated behaviour differs, the reference does one thing,
 * the Java does another, and the divergence is registered in
 * {@code docs/architecture/cobol-to-service-traceability.md}. No claim is made anywhere in this file
 * that the reference itself was altered, because it was not. Line numbers refer to the source as
 * committed, and columns 73 to 80 of a COBOL or job-control line carry a sequence field that is not
 * part of the statement.</p>
 *
 * @param runId the identifier of the run this step belonged to, as supplied by the orchestration
 *     execution rather than generated here; the first half of the ledger's unique key, required to be
 *     present and not blank
 * @param stepName the name of the step within that run, the second half of the ledger's unique key
 *     and the granularity at which a redrive decides whether work has already been done; required to
 *     be present and not blank
 * @param jobName the job this step ran, which determines whether the soft-warn tier is available to
 *     it at all and whether the reject invariant applies; required to be present
 * @param returnCode the tier this step finished in, which the following orchestration choice reads
 *     to decide whether the chain continues; required to be present
 * @param recordsRead how many input records the step read, the general form of the reference's own
 *     read counters such as {@code WS-TRANSACTION-COUNT} and {@code WS-TOTAL-RECORDS-READ}; zero or
 *     greater, and legitimately zero for a step whose reference keeps no counter
 * @param recordsWritten how many records the step wrote to its outputs, counted by the step itself
 *     rather than derived here from the other three counters, because no reference program defines
 *     such a derivation and inventing one would report a number nothing measured; zero or greater
 * @param recordsRejected how many records the step rejected on a business rule, the general form of
 *     {@code WS-REJECT-COUNT} and of the import program's {@code WS-ERROR-RECORDS-WRITTEN}; zero or
 *     greater, and tied to the tier by the posting invariant recorded on this type
 * @param recordsSkipped how many records the step passed over without either processing or rejecting
 *     them, which the reference does distinguish: {@code WS-UNKNOWN-RECORD-TYPE-COUNT} at
 *     {@code app/cbl/CBIMPORT.cbl:147} counts a record of no recognised type, and the interest
 *     accrual's rate gate at {@code app/cbl/CBACT04C.cbl:214}, {@code IF DIS-INT-RATE NOT = 0},
 *     passes over a category balance carrying no rate without rejecting it; zero or greater
 * @param recordTypeCounts a count per record type for the jobs that keep one, the five-way detail of
 *     {@code app/cbl/CBEXPORT.cbl:139-143} and {@code app/cbl/CBIMPORT.cbl:141-145}; may be supplied
 *     as {@code null} or empty by a job that keeps no such detail, is held as an unmodifiable copy of
 *     whatever was supplied, and promises no iteration order
 */
public record BatchRunSummary(
        String runId,
        String stepName,
        BatchJobName jobName,
        BatchReturnCode returnCode,
        long recordsRead,
        long recordsWritten,
        long recordsRejected,
        long recordsSkipped,
        Map<String, Long> recordTypeCounts) {

    /**
     * Validates every component and replaces the supplied breakdown with an unmodifiable copy.
     *
     * <p>Four groups of checks run, in this order: the identity pair and the two enumerated
     * components must be present and, for the strings, not blank; each of the four counters must be
     * zero or greater; the soft-warn tier must belong to the job reporting it and, for the posting
     * job, must agree with the rejected count in both directions; and the breakdown, once copied,
     * must hold no blank key and no negative count. The reasons for the third and fourth groups are
     * recorded on this type.</p>
     *
     * <p>Successful construction yields this record instance and no separate return value.</p>
     *
     * @param runId the candidate run identifier; must be present and contain at least one
     *     non-whitespace character
     * @param stepName the candidate step name; must be present and contain at least one
     *     non-whitespace character
     * @param jobName the job this step ran; must be present, and must be the posting job if the
     *     soft-warn tier is being reported
     * @param returnCode the tier this step finished in; must be present, and must agree with
     *     {@code recordsRejected} when the job is the posting job and the tier is not the failure
     *     tier
     * @param recordsRead the candidate read count; must be zero or greater
     * @param recordsWritten the candidate written count; must be zero or greater
     * @param recordsRejected the candidate rejected count; must be zero or greater, and governs the
     *     tier for the posting job
     * @param recordsSkipped the candidate skipped count; must be zero or greater
     * @param recordTypeCounts the candidate per-record-type breakdown, or {@code null} for a job that
     *     keeps none; every count it holds must be zero or greater and every key must contain at
     *     least one non-whitespace character
     * @throws IllegalArgumentException if either identity string is absent or blank, if either
     *     enumerated component is absent, if any of the four counters is negative, if a job other
     *     than the posting job reports the soft-warn tier, if a posting summary reports the soft-warn
     *     tier with nothing rejected or a non-failure tier that disagrees with its rejected count, or
     *     if the breakdown holds a blank key or a negative count
     * @throws NullPointerException if the supplied breakdown holds an absent key or an absent count,
     *     which the copying step rejects before any count is inspected
     */
    public BatchRunSummary {
        // WHY : Assumptions: one exception type covers every rejection in this constructor, matching
        //       the sibling contracts in this package, which raise the same type for an absent value
        //       rather than letting a null-pointer failure escape. A caller can do nothing different
        //       about an absent identifier than about a blank one, so splitting the two would offer a
        //       distinction no caller can act on. The messages carry the offending value instead,
        //       because a container log is where this is read and the source is not available there.
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException(
                    "run identifier is required and must not be blank, but was: " + runId);
        }
        if (stepName == null || stepName.isBlank()) {
            throw new IllegalArgumentException(
                    "step name is required and must not be blank, but was: " + stepName);
        }
        if (jobName == null) {
            throw new IllegalArgumentException("job name is required and was absent");
        }
        if (returnCode == null) {
            throw new IllegalArgumentException("return code is required and was absent");
        }

        // WHY : Trade-offs: the four counters are checked one at a time against their own message
        //       rather than together against a shared one. A shared check would be shorter and would
        //       report that some count was negative without saying which, and the caller reading that
        //       message in a container log has no way to narrow it down. Four checks are accepted for
        //       four unambiguous diagnostics.
        // WHY : Assumptions: the width is long rather than int because these values are summed across
        //       the steps of a run and across runs, not because a single step could exceed an int.
        //       Every reference counter is PIC 9(09) and stops at 999,999,999, which an int holds
        //       comfortably; the wider type settles the aggregate question once instead of raising it
        //       at each place an aggregate is taken.
        if (recordsRead < 0) {
            throw new IllegalArgumentException(
                    "records read must be zero or greater, but was: " + recordsRead);
        }
        if (recordsWritten < 0) {
            throw new IllegalArgumentException(
                    "records written must be zero or greater, but was: " + recordsWritten);
        }
        if (recordsRejected < 0) {
            throw new IllegalArgumentException(
                    "records rejected must be zero or greater, but was: " + recordsRejected);
        }
        if (recordsSkipped < 0) {
            throw new IllegalArgumentException(
                    "records skipped must be zero or greater, but was: " + recordsSkipped);
        }

        // WHY : Assumptions: the soft-warn tier belongs to the posting job alone. Searching all five
        //       reference programs for RETURN-CODE finds exactly one occurrence in the set, at
        //       app/cbl/CBTRN02C.cbl:230; app/cbl/CBTRN01C.cbl, app/cbl/CBACT04C.cbl,
        //       app/cbl/CBEXPORT.cbl and app/cbl/CBIMPORT.cbl contain no such statement at all, so
        //       none of them can report this tier as a business outcome. Refusing it here keeps a
        //       defect in one of those jobs from reaching the orchestration choice as a tier that
        //       choice would wave downstream.
        if (returnCode == BatchReturnCode.SOFT_WARN && jobName != BatchJobName.POST_TRANSACTIONS) {
            throw new IllegalArgumentException("the soft-warn tier is reportable only by '"
                    + BatchJobName.POST_TRANSACTIONS.token() + "', but was reported by '"
                    + jobName.token() + "'");
        }

        // WHY : Assumptions: for the posting job the soft-warn tier holds if and only if the rejected
        //       count exceeds zero, which is the whole of app/cbl/CBTRN02C.cbl:229-231 --
        //       IF WS-REJECT-COUNT > 0 selects MOVE 4 TO RETURN-CODE, and nothing else assigns a
        //       code. The inequality below compares the two sides of that biconditional, so it
        //       catches BOTH inconsistent quadrants: a warn tier with nothing rejected, and a clean
        //       tier with records rejected. Enforcing it makes an inconsistent posting summary
        //       unconstructable, which matters because the following choice admits a tier of four or
        //       lower: a fabricated warn tier would send the chain down a path the reference never
        //       takes, and a fabricated clean tier would conceal rejects that were written.
        // WHY : Assumptions: the failure tier is excluded from the comparison, and the exclusion is
        //       required rather than lenient. A step that ends abnormally never reaches line 230 to
        //       assign anything, so its tier says nothing about its counters; a run may reject records
        //       and then fail, and demanding that a failed run rejected nothing would refuse the most
        //       ordinary partial-run summary there is.
        // WHY : Trade-offs: the rule is scoped to the posting job rather than applied to every job,
        //       and the scope is load-bearing. app/cbl/CBIMPORT.cbl:146 declares
        //       WS-ERROR-RECORDS-WRITTEN and the program writes those records while setting no return
        //       code, so an import run that rejected records finishes in the clean tier. A rule
        //       applied to all jobs would make that faithful summary unconstructable and force the
        //       import job to misreport either its tier or its rejects. The cost accepted is that the
        //       other six jobs get no cross-check between tier and count, which is correct, because
        //       their references define no relationship to check.
        if (jobName == BatchJobName.POST_TRANSACTIONS && returnCode != BatchReturnCode.HARD_FAILURE
                && (returnCode == BatchReturnCode.SOFT_WARN) != (recordsRejected > 0)) {
            throw new IllegalArgumentException("inconsistent '"
                    + BatchJobName.POST_TRANSACTIONS.token() + "' summary: return code "
                    + returnCode.numericValue() + " with a rejected count of " + recordsRejected
                    + "; the soft-warn tier holds exactly when the rejected count exceeds zero");
        }

        // WHY : Alternatives Considered: validating the supplied map and then storing it, or storing
        //       it and then validating what was stored. Both were rejected in favour of copying first
        //       and validating the copy. Validating the caller's map and copying afterwards leaves the
        //       validated object and the stored object as two different objects, so a map that is
        //       mutated between the two steps -- a concurrent map, or one a caller shares -- can place
        //       a negative count into the copy after the check passed. Storing the caller's map
        //       without copying leaves the component mutable through the reference the caller keeps,
        //       which would let a summary already read, compared or written to the ledger change
        //       afterwards, and a record whose component can change is not the immutable value this
        //       type claims to be. Copying first makes the object that was checked and the object that
        //       is held the same object.
        // WHY : Assumptions: an absent map is taken to mean an empty breakdown rather than being
        //       refused. Three of the seven jobs keep no per-type detail at all -- the pre-posting
        //       pass keeps no counter whatever and posting and interest keep only the general ones --
        //       so absent and empty carry the same meaning to every consumer, and no consumer can act
        //       on the difference. Refusing an absent map would turn an optional detail into a
        //       construction failure and would make the common case carry ceremony to say nothing.
        // WHY : Assumptions: the copying step rejects an absent key or an absent count on its own,
        //       before the loop below runs, which is why that loop tests neither for null. A count
        //       that is absent is not a count, so nothing is lost by refusing it there.
        Map<String, Long> breakdownSnapshot =
                recordTypeCounts == null ? Map.of() : Map.copyOf(recordTypeCounts);
        for (Map.Entry<String, Long> countedType : breakdownSnapshot.entrySet()) {
            // WHY : Assumptions: the entries obey the same two rules the four flat counters obey, so
            //       that a per-type count cannot carry a value its general counterpart would have
            //       been refused for. A blank key is refused as well, because the key names a record
            //       type in rendered output and a blank one would label a line with nothing.
            if (countedType.getKey().isBlank()) {
                throw new IllegalArgumentException(
                        "record-type breakdown keys must not be blank, but one was");
            }
            if (countedType.getValue() < 0) {
                throw new IllegalArgumentException("record-type count for '" + countedType.getKey()
                        + "' must be zero or greater, but was: " + countedType.getValue());
            }
        }
        recordTypeCounts = breakdownSnapshot;
    }

    /**
     * Returns the identifier of the run this step belonged to.
     *
     * <p>This is the first half of the step ledger's unique key, and it is the value a redrive
     * matches on together with the step name.</p>
     *
     * <p>This accessor accepts no parameters.</p>
     *
     * @return the run identifier exactly as supplied; never {@code null} and never blank
     */
    public String runId() {
        // WHY : Assumptions: all nine accessors on this record are written out rather than left
        //       implicit, for one reason that covers them all and is therefore stated once here. An
        //       implicit record accessor carries no Javadoc block, so it can carry no return
        //       at-clause, and the guarantees that matter about these nine are exactly what an
        //       implicit accessor cannot state: that the identity strings are never blank, that the
        //       counts are never negative, and that the breakdown cannot be modified through the
        //       reference handed back.
        return runId;
    }

    /**
     * Returns the name of the step within its run.
     *
     * <p>This is the second half of the step ledger's unique key, and the granularity at which a
     * redriven step decides whether the work has already been done.</p>
     *
     * <p>This accessor accepts no parameters.</p>
     *
     * @return the step name exactly as supplied; never {@code null} and never blank
     */
    public String stepName() {
        return stepName;
    }

    /**
     * Returns the job this step ran.
     *
     * <p>This determines whether the soft-warn tier was available to the step at all and whether the
     * reject invariant recorded on this type applied to it.</p>
     *
     * <p>This accessor accepts no parameters.</p>
     *
     * @return the job name; never {@code null}
     */
    public BatchJobName jobName() {
        return jobName;
    }

    /**
     * Returns the tier this step finished in.
     *
     * <p>This is the value the following orchestration choice reads to decide whether the chain
     * continues, and the type returned owns both the number it reports and the run-sense predicate
     * over it.</p>
     *
     * <p>This accessor accepts no parameters.</p>
     *
     * @return the completion tier; never {@code null}
     */
    public BatchReturnCode returnCode() {
        return returnCode;
    }

    /**
     * Returns how many input records the step read.
     *
     * <p>This accessor accepts no parameters.</p>
     *
     * @return the read count, zero or greater; legitimately zero for a step whose reference program
     *     keeps no counter, the pre-posting validation pass being the one that keeps none at all
     */
    public long recordsRead() {
        return recordsRead;
    }

    /**
     * Returns how many records the step wrote to its outputs.
     *
     * <p>This accessor accepts no parameters.</p>
     *
     * @return the written count, zero or greater, as counted by the step itself rather than derived
     *     from the other three counters
     */
    public long recordsWritten() {
        return recordsWritten;
    }

    /**
     * Returns how many records the step rejected on a business rule.
     *
     * <p>For the posting job this value and the tier agree by construction, in both directions, for
     * the reason recorded on this type.</p>
     *
     * <p>This accessor accepts no parameters.</p>
     *
     * @return the rejected count, zero or greater
     */
    public long recordsRejected() {
        return recordsRejected;
    }

    /**
     * Returns how many records the step passed over without processing or rejecting them.
     *
     * <p>This accessor accepts no parameters.</p>
     *
     * @return the skipped count, zero or greater; a record of no recognised type and a category
     *     balance carrying no rate are the two cases the reference programs distinguish this way
     */
    public long recordsSkipped() {
        return recordsSkipped;
    }

    /**
     * Returns the per-record-type breakdown, as an unmodifiable map.
     *
     * <p>The map returned is the copy this record took when it was constructed, so a caller cannot
     * reach the summary's state through it and an attempt to modify it is refused. It is empty for a
     * job that keeps no per-type detail, including where {@code null} was supplied. Its iteration
     * order is not part of this record's contract: a caller rendering one line per record type
     * iterates its own list of keys and looks each one up here.</p>
     *
     * <p>This accessor accepts no parameters.</p>
     *
     * @return an unmodifiable map of record-type name to count, possibly empty but never
     *     {@code null}, holding no blank key and no negative count
     */
    public Map<String, Long> recordTypeCounts() {
        return recordTypeCounts;
    }

    /**
     * Renders the run's identity, its outcome and its four counters, with the per-type map summarised.
     *
     * <p>Purpose. Eight of the nine components are safe by the third clause of
     * {@code docs/architecture/observability.md} L1093 to L1112 -- a run identifier, a step name, a job
     * name, a return code and four record counters are exactly the "identity that discloses nothing" that
     * clause describes, and they are the whole diagnostic content of a batch step. The ninth is a map, and
     * a map is rendered as its SIZE for the collection reason: its key set is derived from the data a run
     * happened to process, so its rendered length is a function of input rather than of this type.</p>
     *
     * <p>Alternatives Considered: rendering the map's keys but not its values. Rejected because the keys
     * are the interesting half only when there are few of them, and nothing bounds how many there are; the
     * accessor returns the map unmodified to any caller that needs it, which is the right place for a
     * reader that wants the breakdown.</p>
     *
     * <p>Assumptions: the counters print as counts and not as amounts. A record count is not money even
     * when the records carry money, so no clause of that rule reaches them, and a batch step whose read
     * and written counts disagree is diagnosed from precisely those two numbers.</p>
     *
     * @return a rendering naming the run identifier, the step and job names, the return code, the four
     *     record counters and the size of the per-type breakdown; never {@code null}
     */
    @Override
    public String toString() {
        return "BatchRunSummary[runId=" + this.runId
                + ", stepName=" + this.stepName
                + ", jobName=" + this.jobName
                + ", returnCode=" + this.returnCode
                + ", recordsRead=" + this.recordsRead
                + ", recordsWritten=" + this.recordsWritten
                + ", recordsRejected=" + this.recordsRejected
                + ", recordsSkipped=" + this.recordsSkipped
                + ", recordTypeCounts=" + (this.recordTypeCounts == null ? "absent"
                        : this.recordTypeCounts.size() + " entries") + ']';
    }
}
