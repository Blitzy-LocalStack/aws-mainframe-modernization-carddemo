package com.carddemo.batch.dto;

import java.util.Optional;

/**
 * The decoded container command line of one batch step, held as one immutable record.
 *
 * <p>This is the type at which an orchestration decision becomes a Java one, and therefore the type
 * at which a malformed argument list is rejected rather than defaulted. The reference platform
 * started a step by naming a program and, where that program needed one, a parameter string:
 * {@code app/jcl/INTCALC.jcl:22} reads {@code //STEP15 EXEC PGM=CBACT04C,PARM='2022071800'}. The
 * migrated platform starts a step by running a container task whose command overrides carry the same
 * facts as process arguments. That translation is the specification's mapping rule T6, under which
 * {@code EXEC PGM=} becomes a state invoking a task and {@code PARM=} becomes a job parameter, and
 * this record is the Java end of it.</p>
 *
 * <h2>The measurement the validation rests on</h2>
 *
 * <p>Assumptions: the requirement stated in the table below rests on a count taken over the
 * reference job library rather than on a preference, and the count is reproducible. A search for
 * {@code PARM=} across the thirty-eight members of {@code app/jcl/} returns four statements, and
 * three of them do not describe a migrated batch program: {@code app/jcl/CBADMCDJ.jcl:28} passes
 * control options to a resource-definition utility, {@code app/jcl/FTPJCL.JCL:31} is commented out
 * with {@code //*}, and {@code app/jcl/TXT2PDF1.JCL:25} belongs to a utility that retires with no
 * target. The fourth is {@code app/jcl/INTCALC.jcl:22}, and it is the only place in the reference
 * baseline where a migrated batch program is handed a parameter. Its receiver is declared at
 * {@code app/cbl/CBACT04C.cbl:175-180}: line 175 opens {@code LINKAGE SECTION.}, line 176 declares
 * {@code 01 EXTERNAL-PARMS.}, line 177 declares {@code PARM-LENGTH PIC S9(04) COMP}, line 178
 * declares {@code PARM-DATE PIC X(10)} and line 180 reads
 * {@code PROCEDURE DIVISION USING EXTERNAL-PARMS.}</p>
 *
 * <p>Assumptions: every other migrated step is driven with no parameter at all, which is why a
 * blanket requirement would not have been a reading of the baseline.
 * {@code app/jcl/POSTTRAN.jcl:23} reads {@code //STEP15 EXEC PGM=CBTRN02C},
 * {@code app/jcl/CBEXPORT.jcl:43} reads {@code //STEP02 EXEC PGM=CBEXPORT} and
 * {@code app/jcl/CBIMPORT.jcl:22} reads {@code //STEP01 EXEC PGM=CBIMPORT}, and not one of the three
 * carries a {@code PARM}. One migrated step has no driver whatsoever: no member of {@code app/jcl/}
 * names {@code CBTRN01C}, so {@link BatchJobName#PREFLIGHT_DAILY_TRANSACTIONS} is reached in the
 * reference only through the parity oracle's own integration test.</p>
 *
 * <p>Assumptions: a second ground exists that the reference did not need, and it comes from the
 * target rather than from the baseline. {@link DatasetGeneration} locates a generation by a
 * {@code dt=} date segment followed by a {@code gen=} number, so a step that addresses a generation
 * must be able to name a date. The reference had no such need because it addressed a generation
 * relatively and a relative reference resolves against the catalogue:
 * {@code app/jcl/POSTTRAN.jcl:38} names {@code DALYREJS(+1)}, {@code app/jcl/INTCALC.jcl:41} names
 * {@code SYSTRAN(+1)}, {@code app/jcl/TRANBKP.jcl:33} names {@code TRANSACT.BKUP(+1)}, and
 * {@code app/jcl/COMBTRAN.jcl:24} and {@code :26} read two current generations while {@code :37}
 * creates one.</p>
 *
 * <h2>The per-job business-date requirement</h2>
 *
 * <p>The rule below is enforced by the constructor, so an invalid combination cannot be built. It is
 * stated as a table rather than left to be read out of a chain of conditionals, because it is the
 * one part of this type a caller has to know before calling it.</p>
 *
 * <table>
 *   <caption>Whether a business date must accompany each job, and the ground for the answer</caption>
 *   <thead>
 *     <tr>
 *       <th scope="col">Job</th>
 *       <th scope="col">Business date</th>
 *       <th scope="col">Ground</th>
 *     </tr>
 *   </thead>
 *   <tbody>
 *     <tr>
 *       <td>{@link BatchJobName#PREFLIGHT_DAILY_TRANSACTIONS}</td>
 *       <td>Optional</td>
 *       <td>Neither ground applies. It receives no parameter and creates no generation, and it is
 *           the one job with no driver anywhere in {@code app/jcl/}.</td>
 *     </tr>
 *     <tr>
 *       <td>{@link BatchJobName#POST_TRANSACTIONS}</td>
 *       <td>Required with a generation, optional without one</td>
 *       <td>Ground two only. {@code app/jcl/POSTTRAN.jcl:23} passes no parameter, but line 38
 *           creates {@code DALYREJS(+1)}, whose target coordinate needs a {@code dt=} segment.</td>
 *     </tr>
 *     <tr>
 *       <td>{@link BatchJobName#CALCULATE_INTEREST}</td>
 *       <td><strong>Always required</strong></td>
 *       <td>Both grounds. {@code app/jcl/INTCALC.jcl:22} is the sole surviving {@code PARM}, and
 *           line 41 additionally creates {@code SYSTRAN(+1)}.</td>
 *     </tr>
 *     <tr>
 *       <td>{@link BatchJobName#BACKUP_TRANSACTIONS}</td>
 *       <td>Required with a generation, optional without one</td>
 *       <td>Ground two only. {@code app/jcl/TRANBKP.jcl:33} creates
 *           {@code TRANSACT.BKUP(+1)}.</td>
 *     </tr>
 *     <tr>
 *       <td>{@link BatchJobName#COMBINE_TRANSACTIONS}</td>
 *       <td>Required with a generation, optional without one</td>
 *       <td>Ground two only. {@code app/jcl/COMBTRAN.jcl:37} creates
 *           {@code TRANSACT.COMBINED(+1)} from the two current generations read at lines 24 and
 *           26.</td>
 *     </tr>
 *     <tr>
 *       <td>{@link BatchJobName#EXPORT}</td>
 *       <td>Optional</td>
 *       <td>Neither ground applies. {@code app/jcl/CBEXPORT.jcl:43} passes no parameter and the job
 *           creates no generation.</td>
 *     </tr>
 *     <tr>
 *       <td>{@link BatchJobName#IMPORT}</td>
 *       <td>Optional</td>
 *       <td>Neither ground applies. {@code app/jcl/CBIMPORT.jcl:22} passes no parameter and the job
 *           creates no generation.</td>
 *     </tr>
 *   </tbody>
 * </table>
 *
 * <p>Assumptions: "required with a generation" is a coupling rather than a second rule, and it falls
 * out of {@link DatasetGeneration} carrying a {@link BusinessDate} of its own. Whenever this record
 * carries a generation it must also carry a business date, and the two must be equal; a record
 * naming one date at top level and a different one inside its coordinate would leave a reader unable
 * to say which date the step used, and would let a step write into a partition its own parameters
 * disagree with.</p>
 *
 * <h2>This rule is a floor, and the entry point's contract is stricter</h2>
 *
 * <p><strong>Assumptions: satisfying this record does not mean a container command line will be
 * accepted.</strong> {@code com.carddemo.batch.BatchApplication} requires
 * {@link #BUSINESS_DATE_OPTION} for ALL SEVEN jobs, and its own documentation gives a third ground
 * that this record deliberately does not enforce: it adds the token as an IDENTIFYING job parameter,
 * so the token forms part of the job instance's identity and is what makes one business date's run
 * distinguishable from the next one's. A job left with no identifying date would have a single job
 * instance for all time and its second invocation would be refused whatever date it was given.</p>
 *
 * <p>Trade-offs: this record still models the date as optional where the table records no ground for
 * it, and the divergence from the entry point is accepted deliberately. This type is a general
 * parameter carrier with several construction sites -- a job's own code, the module's batch
 * configuration, and tests -- whereas the entry point is one producer guarding one process boundary,
 * and a boundary is entitled to be narrower than the type it produces. Enforcing the identity ground
 * here as well would make the record reject combinations that are perfectly meaningful in-process,
 * such as a unit test exercising the export job's mapping with no date in sight. The cost accepted
 * is exactly this paragraph: a reader must not infer from this record's permissiveness that the
 * option may be omitted from a container command, because the entry point will refuse it. Every
 * argument vector the entry point accepts also satisfies this record; the converse does not
 * hold.</p>
 *
 * <h2>Where the framework stops</h2>
 *
 * <p>Refactoring Rationale: no framework type appears anywhere in this file, and the omission is
 * load-bearing rather than incidental. The adaptation from the framework's own arguments abstraction
 * to the plain {@code String[]} that {@link #fromArguments(String[])} accepts belongs to
 * {@code com.carddemo.batch.BatchApplication}, which already owns the process boundary. Keeping it
 * there means this record is constructible and assertable in a plain unit test with no application
 * context, no datasource and no credential, and it keeps the whole parameter layer free of the
 * framework. Importing the arguments abstraction here would be the convenient change and would cost
 * exactly that testability, because a record that names a framework type can no longer be exercised
 * without the framework that defines it. Converting these parameters into the batch framework's own
 * parameter type is likewise somebody else's work, performed where the job is launched, so that type
 * is absent here too.</p>
 *
 * <h2>One record, not seven</h2>
 *
 * <p>Trade-offs: a family of seven per-job parameter records was considered and rejected. The seven
 * jobs share one two-option surface and six of them take at most a business date and a generation
 * coordinate, so seven types would model one shape seven times. The rejected design also carries
 * concrete machinery this one does not: {@link #fromArguments(String[])} would need a seven-way
 * dispatch on the decoded token to choose which record to build, and every holder of the result --
 * the module entry point among them -- would need a common supertype to declare, which is more
 * structure than a two-option surface earns. The compromise accepted is that the requiredness rule
 * becomes a run-time check in one constructor instead of a compile-time property of seven types.
 * That is precisely why the rule is written out as a table above and covered case by case by test:
 * the table and the constructor are the only two places it lives, and a test failing is what stops
 * them drifting apart.</p>
 *
 * <p>Trade-offs: the two conditional components are declared as {@code Optional} holders rather than
 * as plain nullable components. A nullable component was the alternative and is rejected on a
 * specific ground: absence would then be invisible in the signature, so every reading site would be
 * free to dereference without deciding what absence means, and the omission would surface as a
 * null-pointer failure inside whichever job read it rather than as a refusal at the call site that
 * forgot. An {@code Optional} makes the decision unavoidable and lets
 * {@link #requireBusinessDate()} carry the insisting form once instead of every job repeating it.
 * Two costs are accepted for that. An {@code Optional} is an awkward component type for a record
 * that has to be serialised or persisted, which is affordable here only because this one is neither
 * -- it is decoded from an argument vector and consumed in the same process, and nothing maps it to a
 * column or a wire format. And the holder itself can be passed as {@code null}, which is a state a
 * nullable component could not have; the constructor therefore rejects a null holder explicitly
 * rather than leaving that third state to be discovered later.</p>
 *
 * <h2>What this record does not do</h2>
 *
 * <p>Assumptions: no clock is read here, by this record or on any path it takes. A business date
 * arrives as a parameter for the same reason the reference took one through its
 * {@code LINKAGE SECTION} rather than reading the system date -- so that re-running a given business
 * date reproduces that date's figures instead of the figures for whichever day the re-run happens on.
 * A clock fallback for an absent date would make the same input produce different output on a
 * different day, which is the one property golden comparison cannot survive. There is consequently
 * no default job either: a defaulted job token would silently run a workload nobody asked for
 * against production data.</p>
 *
 * <p>Trade-offs: the generation coordinate is CARRIED here, never DERIVED here. Which family a job
 * writes is the job's own knowledge -- {@code app/jcl/POSTTRAN.jcl:38},
 * {@code app/jcl/INTCALC.jcl:41}, {@code app/jcl/TRANBKP.jcl:33} and
 * {@code app/jcl/COMBTRAN.jcl:37} each name a different one -- so mapping a job to its family inside
 * this record would put the same knowledge in two places, and two places holding one mapping is how
 * they come to disagree. The cost accepted is that a caller wanting a coordinate has to build it,
 * which is a line of code at the one site that already knows the answer.</p>
 *
 * <p>Assumptions: nothing in this record is read from the environment, from a system property, from
 * a file or from the classpath. It decodes a vector it is handed and holds the result, which is what
 * makes its behaviour a function of its arguments alone and therefore reproducible on any machine.</p>
 *
 * <p>Assumptions: the generated {@code toString} is safe to write to a log, and the reason is a
 * property of the data rather than an accident of the implementation. The whole parameter surface is
 * a job token drawn from a closed set of seven, an opaque ten-character date token, and a dataset
 * coordinate made of a family name, that same date and a small integer. No credential, account
 * identifier, card number or cardholder detail can reach any of the three, so there is nothing here
 * to redact and no reason to hand-write a masking {@code toString} that a future reader would have to
 * verify. The generated {@code equals} and {@code hashCode} are kept for the same reason: component
 * equality is exactly the equality this type wants.</p>
 *
 * <p>Assumptions: the job renders in that generated {@code toString} as its CONSTANT NAME rather
 * than as its wire token, because {@link BatchJobName} declines to override {@code toString} and an
 * enum's default is its name. A log line therefore reads {@code jobName=CALCULATE_INTEREST} where the
 * command line said the kebab-case token, and the two are not interchangeable: the wire token comes
 * from {@link BatchJobName#token()} and from nowhere else. The rendering is left as it stands rather
 * than reworked, because a value formatted for a human reading a log is not the value formatted for
 * an orchestrator, and making {@code toString} emit the token would produce a string that looks
 * substitutable for the accessor and would eventually be used as one.</p>
 *
 * @param jobName the job this invocation selects, never {@code null}; its token is the value that
 *     followed {@link #JOB_OPTION} on the command line and is also the name the corresponding job
 *     bean registers under
 * @param businessDate the business-date token this invocation carries, empty only for a job the
 *     table above records as taking none and which carries no generation; the {@code Optional}
 *     itself is never {@code null}, because an absent date is expressed as an empty {@code Optional}
 *     and never as a null one
 * @param targetGeneration the generation coordinate this step reads or creates, empty whenever the
 *     caller supplied none -- which includes every set of parameters produced by
 *     {@link #fromArguments(String[])}, since no option supplies a coordinate; the {@code Optional}
 *     itself is never {@code null}
 */
public record BatchJobParameters(
        BatchJobName jobName,
        Optional<BusinessDate> businessDate,
        Optional<DatasetGeneration> targetGeneration) {

    /**
     * Command-line option selecting the job to run, {@code --job=}.
     *
     * <p>Assumptions: this spelling is not chosen here. It must stay byte-identical to the option
     * {@code com.carddemo.batch.BatchApplication} publishes, because both read the same argument
     * vector and an orchestration state definition that this repository's Java cannot inspect
     * supplies it. The prefix form with a trailing equals sign is also what the framework recognises
     * as an option argument, so the same token both selects the job and appears in the framework's
     * own command-line property source.</p>
     *
     * <p>Trade-offs: the option is republished here rather than imported from the entry point.
     * Importing it would point the parameter layer at the application entry point, inverting the
     * dependency the layering rules exist to keep one-way, and would drag a framework-annotated class
     * onto this record's compilation path. The cost of republishing is that one string now has two
     * homes and could drift, which is paid down by a test asserting the two are equal character for
     * character rather than by hoping nobody edits one of them.</p>
     */
    public static final String JOB_OPTION = "--job=";

    /**
     * Command-line option supplying the business date, {@code --business-date=}.
     *
     * <p>The value that follows is the opaque ten-character token described on {@link BusinessDate}.
     * It is forwarded exactly as received and is never reformatted, so both the separated and the
     * compact layout survive decoding unchanged.</p>
     *
     * <p>Assumptions: the same byte-identity requirement and the same republishing trade-off recorded
     * on {@link #JOB_OPTION} apply to this option too.</p>
     */
    public static final String BUSINESS_DATE_OPTION = "--business-date=";

    /**
     * The one accepted character width of a business-date token, ten.
     *
     * <p>Assumptions: the number comes from {@code PARM-DATE PIC X(10)} at
     * {@code app/cbl/CBACT04C.cbl:178}, and it is held privately for the reason {@link BusinessDate}
     * gives for holding its own copy privately: the published authority for this number in this
     * module is the module entry point, and a third public constant for one value would let a caller
     * compile against whichever of the three it happened to find first.</p>
     */
    private static final int BUSINESS_DATE_LENGTH = 10;

    /**
     * Rejects any combination of components that could not describe a real step invocation.
     *
     * <p>Three conditions are enforced: the job is present; a carried generation is accompanied by
     * the same business date it names; and a business date is present for every job the table on this
     * type records as requiring one. Successful construction yields this record instance and no
     * separate return value.</p>
     *
     * @param jobName the job this invocation selects; must not be {@code null}
     * @param businessDate the business-date token, empty where the type's table permits; the
     *     {@code Optional} reference itself must not be {@code null}
     * @param targetGeneration the generation coordinate, empty where the caller supplied none; the
     *     {@code Optional} reference itself must not be {@code null}
     * @throws IllegalArgumentException if {@code jobName} is {@code null}; if either
     *     {@code Optional} reference is {@code null}; if {@code targetGeneration} is present while
     *     {@code businessDate} is empty, or is present with a business date other than the one
     *     {@code businessDate} carries; or if {@code businessDate} is empty for a job the type's
     *     table records as always requiring one
     */
    public BatchJobParameters {
        // WHY : Assumptions: one exception type covers every CONSTRUCTION rejection here, and it is
        //       the type BusinessDate, DatasetGeneration and the module entry point already raise. A
        //       caller can do nothing different about an absent job than about a mismatched date, so
        //       a second type would divide these rejections without giving anyone a second response,
        //       and a rejection then reads the same in a container log wherever in the module it
        //       happened. The one deliberate exception is requireBusinessDate() below, which raises a
        //       state failure instead because by then the record is already valid; that decision is
        //       argued at its own call site rather than here.
        if (jobName == null) {
            throw new IllegalArgumentException(
                    "batch job parameters require a job name and none was given");
        }

        // WHY : Assumptions: an absent value is expressed as an empty Optional and never as a null
        //       Optional reference, so a null reference is a caller error rather than a second
        //       spelling of absence. Accepting it would put a value into this record that every
        //       accessor and the generated toString would then dereference, turning one bad call into
        //       a failure at an unrelated site later.
        if (businessDate == null) {
            throw new IllegalArgumentException(
                    "batch job parameters require a business-date holder, empty rather than null, "
                            + "and null was given");
        }

        if (targetGeneration == null) {
            throw new IllegalArgumentException(
                    "batch job parameters require a target-generation holder, empty rather than "
                            + "null, and null was given");
        }

        // WHY : Assumptions: a carried generation obliges a business date because DatasetGeneration
        //       locates a generation by a dt= segment, so the coordinate already contains a date of
        //       its own. Leaving the top-level date empty beside a populated coordinate would let one
        //       record answer "which business date is this step running for" two different ways
        //       depending on which member a reader asked.
        if (targetGeneration.isPresent() && businessDate.isEmpty()) {
            throw new IllegalArgumentException(JOB_OPTION + jobName.token()
                    + " carries a target dataset generation, which addresses a date partition, so "
                    + "it also requires a business date and none was given");
        }

        // WHY : Assumptions: equality is asserted rather than the coordinate's own date being copied
        //       up or the top-level date being pushed down. Silently adopting one of the two would
        //       resolve a genuine contradiction by discarding half of it, and the half discarded
        //       decides which object-storage partition the step writes into -- so the failure would
        //       surface as a generation written under the wrong date rather than as a rejected
        //       invocation.
        if (targetGeneration.isPresent()
                && !targetGeneration.get().businessDate().equals(businessDate.get())) {
            throw new IllegalArgumentException("business date '"
                    + businessDate.get().token()
                    + "' disagrees with the date '"
                    + targetGeneration.get().businessDate().token()
                    + "' named by the target dataset generation, and one step cannot run for two "
                    + "business dates");
        }

        // WHY : Assumptions: this is the measured half of the rule, and it is deliberately narrow.
        //       app/jcl/INTCALC.jcl:22 is the only surviving PARM= on a migrated batch program and
        //       app/cbl/CBACT04C.cbl:476-480 concatenates the token's bytes into every TRAN-ID the
        //       accrual generates, so for this job alone an absent date does not merely leave a
        //       coordinate unnamed -- it leaves a stored identifier unformable. Requiring the date of
        //       the other six here as well would state a rule the baseline does not support; the
        //       stricter contract that does require it of all seven belongs to the module entry
        //       point, and this type's documentation records that it is stricter.
        if (jobName == BatchJobName.CALCULATE_INTEREST && businessDate.isEmpty()) {
            throw new IllegalArgumentException(JOB_OPTION + jobName.token()
                    + " always requires " + BUSINESS_DATE_OPTION
                    + " because the token is concatenated into every generated transaction "
                    + "identifier, and none was given");
        }
    }

    /**
     * Decodes a container command line into one set of batch job parameters.
     *
     * <p><strong>Any argument this method does not recognise is ignored rather than rejected.</strong>
     * Only {@link #JOB_OPTION} and {@link #BUSINESS_DATE_OPTION} are read. Every other element of the
     * vector is skipped, whether it is another framework option such as a profile selection or a
     * property override, or a bare word carrying no option prefix at all. The one argument whose
     * absence is fatal is {@link #JOB_OPTION}, because no default job exists to fall back to.</p>
     *
     * <p>A worked example, using the container command overrides the orchestration state supplies
     * verbatim:</p>
     *
     * <pre>{@code
     * // "Command": ["--job=calculate-interest", "--business-date=2022-07-18"]
     * BatchJobParameters parameters = BatchJobParameters.fromArguments(
     *         new String[] {"--job=calculate-interest", "--business-date=2022-07-18"});
     *
     * parameters.jobName();                       // BatchJobName.CALCULATE_INTEREST
     * parameters.requireBusinessDate().token();   // "2022-07-18", byte for byte as supplied
     * parameters.targetGeneration();              // empty: no option supplies a coordinate
     * }</pre>
     *
     * <p>The compact form the reference baseline itself injects decodes identically:
     * {@code app/jcl/INTCALC.jcl:22} supplies {@code PARM='2022071800'}, and a vector carrying that
     * ten-character token yields exactly {@code 2022071800} with no separator inserted and nothing
     * reformatted.</p>
     *
     * @param arguments the container command arguments to decode; {@code null} is accepted and is
     *     treated as carrying no options at all, as is an empty array and an array whose elements are
     *     all unrecognised, and each of those three therefore fails on the absent job option
     * @return the decoded parameters, carrying the resolved job, the business date when one was
     *     supplied, and always an empty generation coordinate; never {@code null}
     * @throws IllegalArgumentException if {@link #JOB_OPTION} is absent, blank or supplied more than
     *     once; if its value is not byte-identical to one of the seven job tokens; if
     *     {@link #BUSINESS_DATE_OPTION} is supplied more than once or carries a token that is not
     *     exactly ten characters of ASCII digits and ASCII hyphen-minus; or if the decoded
     *     combination is one the constructor refuses
     */
    public static BatchJobParameters fromArguments(String[] arguments) {
        String jobToken = optionValue(arguments, JOB_OPTION);

        // WHY : Assumptions: an absent job option is fatal rather than defaulted, and the entry point
        //       is what makes that the only safe answer -- it bakes in no default job, so there is no
        //       workload that is legitimately the one to run when nobody said which. Choosing one
        //       would run a job nobody asked for, to completion, against production data, with
        //       nothing in the log marking it as unasked-for. The message names the option so that a
        //       container log identifies the malformed command without access to this source.
        if (jobToken == null) {
            throw new IllegalArgumentException(JOB_OPTION
                    + " is required and was not supplied; no default job exists");
        }

        BatchJobName resolvedJob = BatchJobName.resolve(jobToken);
        String dateToken = optionValue(arguments, BUSINESS_DATE_OPTION);

        Optional<BusinessDate> decodedDate;
        if (dateToken == null) {
            decodedDate = Optional.empty();
        } else {
            requireWellFormedBusinessDateToken(dateToken);
            decodedDate = Optional.of(new BusinessDate(dateToken));
        }

        // WHY : Trade-offs: no option supplies a generation coordinate, so this method always yields
        //       an empty one. A --generation= flag was considered and rejected: no reference driver
        //       passes anything of the kind, and the coordinate needs a family, which is the job's own
        //       knowledge rather than the operator's -- an operator naming the wrong family would
        //       direct a step's output at another domain's prefix. A caller needing a coordinate
        //       builds it at the site that already knows the family and uses the canonical
        //       constructor.
        return new BatchJobParameters(resolvedJob, decodedDate, Optional.empty());
    }

    /**
     * Returns the job this invocation selects.
     *
     * <p>This accessor accepts no parameters.</p>
     *
     * @return the selected job; never {@code null}, because the constructor refuses an absent one
     */
    public BatchJobName jobName() {
        return jobName;
    }

    /**
     * Returns the business-date token this invocation carries, if it carries one.
     *
     * <p>An empty result means the caller supplied no business date AND the job is one the table on
     * this type records as taking none. It never means a date was supplied and discarded, and it
     * never means a date should now be obtained from somewhere else: there is no clock fallback and
     * no default. A caller whose job cannot proceed without a date should ask
     * {@link #requireBusinessDate()} instead of unwrapping this result itself.</p>
     *
     * <p>This accessor accepts no parameters.</p>
     *
     * @return the business date when one is carried, otherwise an empty {@code Optional}; the
     *     {@code Optional} reference itself is never {@code null}
     */
    public Optional<BusinessDate> businessDate() {
        return businessDate;
    }

    /**
     * Returns the dataset generation this step reads or creates, if one was supplied.
     *
     * <p>An empty result means no coordinate was supplied, which is the case for every set of
     * parameters {@link #fromArguments(String[])} produces, since no command-line option carries a
     * coordinate. It does not mean the job creates no generation: four of the seven jobs do, and each
     * of them knows its own family, so the coordinate reaches this record from the job rather than
     * being inferred here.</p>
     *
     * <p>This accessor accepts no parameters.</p>
     *
     * @return the generation coordinate when one is carried, otherwise an empty {@code Optional}; the
     *     {@code Optional} reference itself is never {@code null}
     */
    public Optional<DatasetGeneration> targetGeneration() {
        return targetGeneration;
    }

    /**
     * Returns the carried business date, refusing to continue when none is carried.
     *
     * <p>This exists so that a job requiring a business date does not repeat an unwrapping idiom, and
     * so that the failure it produces when the date is missing names the job rather than reporting a
     * bare absent value. It is intended for {@link BatchJobName#CALCULATE_INTEREST}, whose date the
     * constructor already guarantees, and for any job invoked with a generation coordinate, whose date
     * the constructor likewise guarantees; for those callers this method cannot fail, and it is the
     * clearest way to say so at the call site. It is equally available to the remaining jobs, for
     * which it can fail and is the correct way to insist.</p>
     *
     * <p>This accessor accepts no parameters.</p>
     *
     * @return the carried business date; never {@code null}
     * @throws IllegalStateException if no business date is carried, which can only happen for a job
     *     the table on this type records as taking none and which carries no generation coordinate
     */
    public BusinessDate requireBusinessDate() {
        // WHY : Trade-offs: this raises a state failure rather than the argument failure every
        //       construction rejection in this file raises, and the distinction is deliberate. The
        //       record is already valid by the time anyone calls this, so nothing about the caller's
        //       arguments is wrong; what is wrong is that a job asked for a component its own
        //       parameters never promised it. Keeping the two types apart lets a reader of a container
        //       log tell "the command line was malformed" from "a job demanded a date its invocation
        //       was not required to carry", which one shared type would merge into a single
        //       indistinguishable failure. The cost is that this file raises two types instead of one,
        //       which is why both are named in the documentation above.
        return businessDate.orElseThrow(() -> new IllegalStateException(JOB_OPTION
                + jobName.token() + " was invoked without " + BUSINESS_DATE_OPTION
                + " and the running job requires one"));
    }

    /**
     * Reads the single value of one option out of an argument vector, ignoring everything else.
     *
     * @param arguments the container command arguments to search, which may be {@code null} and is
     *     then treated as carrying no options at all
     * @param option the option prefix to match, including its trailing equals sign
     * @return the text following the prefix, or {@code null} when the option is absent from the vector
     *     or is present with a blank value
     * @throws IllegalArgumentException if the option appears more than once in the vector
     */
    private static String optionValue(String[] arguments, String option) {
        if (arguments == null) {
            return null;
        }

        String found = null;
        for (String argument : arguments) {
            // WHY : Alternatives Considered: rejecting an argument this method does not recognise was
            //       evaluated and rejected outright. The vector is SHARED with the framework, which
            //       consumes its own --key=value options for profile selection and property overrides,
            //       and the orchestration state or an operator may add more. A strict parser would
            //       therefore turn an ordinary framework option into a failed step, and it would fail
            //       at container start with a diagnostic pointing at the argument list rather than at
            //       whatever actually needed attention -- so the run would look broken in the one
            //       place it was not. Skipping what this method does not own is the permissive half of
            //       a deliberate asymmetry: unforgiving about the one option nothing else can supply,
            //       permissive about every option it does not own.
            if (argument == null || !argument.startsWith(option)) {
                continue;
            }

            // WHY : Assumptions: a repeated option is rejected rather than resolved by taking the
            //       first or the last occurrence. Both conventions silently discard half of an
            //       ambiguous instruction and the two disagree with each other, so a command assembled
            //       from two overlapping sources would run whichever job the convention happened to
            //       favour and no reader of the log could tell which. This matches the entry point,
            //       which rejects a repeat for the same reason, so one vector cannot be accepted by
            //       one reader and refused by the other.
            if (found != null) {
                // WHY : Assumptions: the message names the option and echoes neither conflicting
                //       value. They are the values in dispute, so a reader gains nothing from them
                //       that the option name does not already give, and echoing them would place two
                //       unbounded caller-supplied strings into a log record instead of none.
                throw new IllegalArgumentException(option + " was supplied more than once");
            }
            found = argument.substring(option.length());
        }

        // WHY : Assumptions: a present-but-blank option is indistinguishable from an absent one for
        //       every caller of this method, so both take one path and produce one diagnostic.
        //       Treating a blank value as supplied would push an empty token into job resolution,
        //       whose failure would report an unrecognised job rather than the missing argument that
        //       actually caused it.
        if (found == null || found.isBlank()) {
            return null;
        }
        return found;
    }

    /**
     * Asserts that a business-date token has the accepted width and character class.
     *
     * @param token the candidate business-date token, never {@code null}
     * @throws IllegalArgumentException if {@code token} is not exactly ten characters, or if any
     *     character is neither an ASCII digit nor an ASCII hyphen-minus
     */
    private static void requireWellFormedBusinessDateToken(String token) {
        // WHY : Assumptions: this check belongs here because BusinessDate deliberately does not carry
        //       it. That type validates width alone and records that the character class is "gated
        //       upstream", naming the module entry point as the only construction site in the module
        //       and asking any second construction site to gate it too. This factory IS that second
        //       site, so gating here keeps the layered contract whole instead of opening a hole in it,
        //       and the alternative -- moving the predicate down into BusinessDate -- would rewrite a
        //       contract this file does not own.
        // WHY : Assumptions: the digit test is written against the ASCII range rather than delegated
        //       to the platform's is-a-digit predicate, which accepts every decimal digit in Unicode.
        //       A fullwidth or Arabic-Indic digit would pass that predicate, be forwarded verbatim as
        //       this contract requires, and then occupy more than one byte in a fixed-width record,
        //       shifting every field after it.
        boolean wellFormed = token.length() == BUSINESS_DATE_LENGTH;
        for (int index = 0; wellFormed && index < token.length(); index++) {
            char character = token.charAt(index);
            boolean asciiDigit = character >= '0' && character <= '9';
            wellFormed = asciiDigit || character == '-';
        }

        if (!wellFormed) {
            throw new IllegalArgumentException(BUSINESS_DATE_OPTION + " value '" + token
                    + "' is not exactly " + BUSINESS_DATE_LENGTH
                    + " characters of ASCII digits and hyphen-minus");
        }
    }
}
