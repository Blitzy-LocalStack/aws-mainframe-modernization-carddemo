package com.carddemo.batch.dto;

import com.carddemo.common.error.AbendDetail;

/**
 * The one message this module publishes: a batch step's failure, addressed to the terminal sink.
 *
 * <h2>Purpose, and the approach this record adds rather than ports</h2>
 *
 * <p><b>Purpose.</b> This record is the payload of a batch step's failure notification. It names the
 * run and the step that failed, the job the step belongs to, the completion tier the step reported,
 * the correlation identifier under which the run's log lines were written, and the structured abend
 * detail, blank in the case of a failure that produced no abend. It is published once, by the state's
 * failure path, to a sink that nothing reads back into this module.</p>
 *
 * <p>Refactoring Rationale: <b>this record is an addition, not a port, and the reference baseline
 * published no such message at all.</b> A reference batch program signals failure by terminating
 * abnormally. The paragraph that does it is at {@code app/cbl/CBTRN02C.cbl:707-711}: line 708 writes
 * {@code 'ABENDING PROGRAM'} to the job log, line 709 moves zero into its timing argument, line 710
 * moves {@code 999} into its abend code, and line 711 calls the environment's abend service with
 * those two arguments. The same paragraph appears in each of the other reference programs this module
 * migrates -- {@code app/cbl/CBACT04C.cbl:629} writes the identical text,
 * {@code app/cbl/CBEXPORT.cbl:578} writes {@code 'CBEXPORT: ABENDING PROGRAM'} and
 * {@code app/cbl/CBIMPORT.cbl:483} writes {@code 'CBIMPORT: ABENDING PROGRAM'}. What was wrong with
 * relying on that signal is specific and threefold. A line of text in a job log is not
 * machine-readable, so nothing can route on it or alert on it. It carries no identifier shared with
 * any other step, so two failures in one run cannot be related to each other or to the run they
 * belong to. And it is deposited in the job's own output rather than delivered anywhere, so reaching
 * it is a retrieval an operator performs instead of a notification an operator receives. This record
 * is what the migrated failure path publishes in place of that; the reference does one thing, the
 * Java does another, and the divergence is registered in
 * {@code docs/architecture/cobol-to-service-traceability.md}.</p>
 *
 * <p>Assumptions: the justification for publishing anything at all rests on three properties of the
 * target architecture rather than on the reference. The migration provisions a standard queue as a
 * terminal error sink for the whole deployment. This module is one of only two bounded contexts in
 * the migration granted a queue configuration class at all. And every state of the batch state
 * machine carries a catch route to a failure-notification state ahead of its terminal failure state,
 * so the notification is a state that exists and needs a payload. This record is that payload.</p>
 *
 * <h2>The sink's name is inherited from the inquiry extension, not from batch</h2>
 *
 * <p>Assumptions: the queue name the target sink is the analogue of belongs to the reference
 * baseline's inquiry extension and to nothing else. {@code CARD.DEMO.ERROR} is moved into an
 * error-queue-name field at exactly two places in the whole reference tree,
 * {@code app/app-vsam-mq/cbl/COACCT01.cbl:294} and {@code app/app-vsam-mq/cbl/CODATE01.cbl:243}, and
 * it appears nowhere under {@code app/cbl/} -- so no reference batch program names it, opens it or
 * writes to it. The target's per-environment error queue is that name's analogue, and this module
 * publishes to it because the migration gives the deployment one terminal sink rather than one per
 * bounded context. The distinction is recorded because the two facts are easy to fuse into a single
 * false one: that batch used to publish to an error queue and now publishes to its successor. Batch
 * never published anything.</p>
 *
 * <h2>The reply queue's lapse resolution does not reach a terminal sink</h2>
 *
 * <p>Alternatives Considered: carrying the instant at which this message stops being worth acting
 * on, so that a consumer could drop a stale one. Rejected, and the reason is that the mechanism it
 * would imitate answers a question this sink does not ask. The migration's messaging analysis records
 * exactly one genuine semantic gap: the reference sets a five-second validity horizon on the
 * authorization reply message, the target queue service offers no per-message time-to-live, and the
 * resolution is to carry that horizon as a message attribute the consumer honours by discarding a
 * message that has outlived it. That resolution belongs to a reply path, where a requester has
 * stopped waiting and a
 * late answer is worse than none. This sink is terminal: nothing replies to it, nothing waits on it,
 * and there is no receiver whose interest in a failure lapses. A horizon carried here would
 * therefore have exactly one effect, which is to license a consumer to discard the diagnostics
 * somebody is looking for. The non-applicability is stated rather than left implicit because the
 * surrounding pattern -- a queue payload in a migration whose specification prescribes a validity
 * attribute -- is a close enough match to invite the field by analogy.</p>
 *
 * <h2>Data minimisation: what this payload may never carry</h2>
 *
 * <p>Assumptions: <b>no value on this record is sensitive, and that is a property enforced on what
 * goes in rather than a happy accident of the six components chosen.</b> The ground for it is the
 * migration's own handling of the same values everywhere else: a primary account number is reduced
 * to its last four digits on every path but one administrative detail endpoint, a card verification
 * value is returned by no endpoint at all, and a national identifier and a government-issued
 * identifier are stored encrypted and returned reduced. A terminal error sink is the worst place in
 * a deployment to relax that. It is long-lived, it is readable by everyone who operates the system,
 * and its contents are routinely copied into incident records and log aggregation systems, which is
 * to say into stores that inherit none of the sink's own controls. Carrying none of those values is
 * therefore easier to guarantee than carrying them carefully, and it is what this record does: no
 * component is a card number, a primary account number, an account identifier, a customer
 * identifier, a national identifier, a government-issued identifier, a credential, a connection
 * string, a storage location, a cloud resource identifier or an environment value.</p>
 *
 * <p>Trade-offs: the record image that failed is deliberately absent, and the cost is real. The
 * reference's reject stream writes each rejected daily-transaction image out in full alongside its
 * reason, so an equivalent component here would let a reader see the exact input that provoked a
 * failure without opening anything else. It would also place cardholder data on a long-lived queue,
 * and it would do so on the one path that runs when something has already gone wrong -- the path
 * least likely to have been exercised carefully. The compromise accepted instead is that this
 * payload identifies the failure and does not describe it: an operator joins {@link #runId()} and
 * {@link #stepName()} to the durable step ledger row and {@link #correlationId()} to the run's log
 * lines, and reads the input there, inside stores that do have the controls for it.</p>
 *
 * <p>Assumptions: the inherited {@code toString} is consequently safe to write into a log line, and
 * it is safe <b>because</b> the constraint above is enforced rather than incidentally. The inherited
 * form renders every component, so its safety is entirely a property of which components exist and
 * of what callers are permitted to put in them; it would leak the moment a seventh component did not
 * observe the paragraph above. Two further properties make it safe in practice as well as in
 * principle. Four of the six components are drawn from closed or key vocabularies -- two enumerated
 * types whose constants are declared in this package, and two ledger keys -- and every component of
 * the abend detail is stripped of control characters by {@code AbendDetail}'s own constructor before
 * it is ever held, so the free-text half of this payload cannot inject a line break into a log record
 * and forge a second line out of one.</p>
 *
 * <h2>The only message payload in this package, and the only one there will be</h2>
 *
 * <p>Alternatives Considered: a family of lifecycle events -- one for a step starting, one for a step
 * completing, one for a warn-tier completion -- so that the module's whole progress could be
 * followed on the queue. Rejected, because the outcomes such a family would report are already
 * reported, and reporting them twice creates the possibility that the two reports disagree. A clean
 * or warn-tier outcome is described by {@code BatchRunSummary} and persisted to the durable
 * {@code batch.batch_run} ledger, which is also the module's restart authority, so the ledger is the
 * record a reader has to trust. A second channel carrying the same facts would be a second version
 * of them, and the two would be reconciled by whichever one a given reader happened to consult.
 * <b>This record is therefore the only message payload type in this package, and this module
 * publishes exactly one kind of message and consumes none.</b> A sibling event type added beside it
 * is not an extension of this design; it is a departure from it.</p>
 *
 * <h2>Value equality, and where the wire form is decided</h2>
 *
 * <p>Assumptions: value equality is the contract. Two events with equal components are equal, and
 * the inherited {@code equals} and {@code hashCode} express exactly that, so neither is written out
 * here. Nothing about an event's identity lies outside its components: there is no generated
 * identifier and no ingestion marker, so two events that agree on all six describe the same failure
 * of the same step of the same run and are interchangeable.</p>
 *
 * <p>Assumptions: this record carries no serialization annotation, and the omission is a decision
 * that was checked rather than assumed. The JSON form of every shape in this migration is decided in
 * code, once, for the whole module: {@code com.carddemo.common.money.MoneyModule} is contributed as
 * a mapper module bean by {@code com.carddemo.common.CardDemoCommonAutoConfiguration}, which is the
 * registration pattern every wire concern in this tree follows. The mapper binds a record through
 * its canonical constructor using the component metadata the language emits for a record, so the six
 * components below are read and written under their own names with nothing declared per field. An
 * annotation here would add a second place the wire form is decided, and the second place is the one
 * that silently wins.</p>
 *
 * <p>Assumptions: a consequence worth naming is that the validation below runs on the way in as well
 * as on the way out, because the canonical constructor is the mapper's entry point. A malformed
 * message read off the sink is therefore rejected at construction rather than accepted as a
 * half-populated event, which is the behaviour a consumer of a terminal sink wants: an event it
 * cannot interpret should fail loudly at the boundary and not be counted as a failure report.</p>
 *
 * <h2>What this record deliberately does not have</h2>
 *
 * <p>Alternatives Considered: a component recording when the failure happened. Rejected on two
 * independent grounds. This module reads no clock in a transfer type -- a value that must be
 * reproducible from a run's inputs cannot be sourced from ambient state -- so such a component would
 * have to be supplied by the caller, which makes it a value the caller asserts rather than a fact
 * the record establishes. And the fact is already established elsewhere: the durable
 * {@code batch.batch_run} ledger row for this run and step records its own start and finish, so a
 * component here would be a second recording of a column the ledger owns, joinable to it by
 * {@link #runId()} and {@link #stepName()} anyway.</p>
 *
 * <p>Assumptions: the destination is not a component either. Which queue this payload is published
 * to is deployment configuration, resolved from the parameter store the infrastructure layer
 * publishes, and it differs per environment; a payload that named its own destination would carry a
 * value only the publisher can be right about and would be wrong the moment it were forwarded. For
 * the same reason no queue client, request type or provider software development kit type is named
 * anywhere in this file: this record is a payload, and publishing it belongs to the module's queue
 * configuration and to the producer that uses it.</p>
 *
 * <p>Assumptions: there is no monetary amount here, and none is missing. A failure event reports that
 * a step did not complete; it does not report a sum. The migration's money contract therefore has no
 * bearing on this record, and no component of it is a numeric quantity of any kind apart from the
 * completion tier's own number, which {@link BatchReturnCode} owns.</p>
 *
 * <h2>Baseline lineage: provenance only</h2>
 *
 * <p>The citations in this file are provenance. Nothing under {@code app/**} is read at run time, and
 * nothing under it is altered by this migration -- the reference implementation is the behavioural
 * oracle and stays byte-identical. Where migrated behaviour differs from the reference, the reference
 * does one thing, the Java does another, and the divergence is registered in
 * {@code docs/architecture/cobol-to-service-traceability.md}. No claim is made anywhere in this file
 * that the reference itself was altered, because it was not. Line numbers refer to the source as
 * committed, and columns 73 to 80 of a COBOL line carry a sequence field that is not part of the
 * statement.</p>
 *
 * @param runId the identifier of the batch run the failed step belongs to, matching the
 *     {@code run_id} column of the durable {@code batch.batch_run} ledger so that an operator holding
 *     this event can find the row without a further lookup; never {@code null} and never blank, and
 *     carried exactly as supplied
 * @param stepName the name of the step that failed, matching the {@code step_name} column of the same
 *     ledger row; paired with {@code runId} it selects one row of one run, which is the whole of this
 *     event's route back to the run's own record of itself; never {@code null} and never blank
 * @param jobName the job the failed step belongs to, as one of the closed set of job tokens
 *     {@link BatchJobName} declares, so that a consumer routes on a value that cannot be misspelled
 *     rather than on free text; never {@code null}
 * @param returnCode the completion tier the step reported, restricted to the failure tier alone,
 *     because a tier that lets the following state run is not a failure and has no business on a
 *     terminal sink; never {@code null}
 * @param correlationId the correlation identifier the run's log lines were written under, duplicated
 *     into this payload so that the event is self-describing even when it is read apart from its
 *     message attributes; never {@code null} and never blank
 * @param abendDetail the structured abend detail, carrying the four components the reference declares
 *     as one group item at {@code app/cpy/CSMSG02Y.cpy:21-29}; never {@code null}, and
 *     {@link #ABSENT_ABEND_DETAIL} where the failure has no abend analogue
 */
public record BatchErrorEvent(
        String runId,
        String stepName,
        BatchJobName jobName,
        BatchReturnCode returnCode,
        String correlationId,
        AbendDetail abendDetail) {

    /**
     * The abend detail that means the failure produced no abend analogue: four blank components.
     *
     * <p>Assumptions: this is the reference baseline's own representation of an unpopulated abend
     * block rather than an invention of this record. Each of the four components of
     * {@code 01 ABEND-DATA} is declared {@code VALUE SPACES} -- at
     * {@code app/cpy/CSMSG02Y.cpy:23}, {@code :25}, {@code :27} and {@code :29} -- so in the
     * reference the block always exists and is blank until something writes to it. There is no state
     * in which the block is absent, and this constant is the migrated form of the state in which it
     * is empty.</p>
     *
     * <p>Assumptions: it is expressed as four empty strings rather than as four nulls, even though
     * {@code AbendDetail}'s constructor substitutes the empty string for a null component and both
     * spellings therefore produce an equal value. The explicit spelling is chosen so that the intent
     * reads off this line without the reader having to know that substitution rule, and so that this
     * constant does not depend on it.</p>
     *
     * <p>Trade-offs: publishing this constant adds one name to this record's surface, and it is
     * published rather than kept private because the absent form has to be nameable by a caller and
     * by a test. The cost of leaving it private is four call sites each spelling the blank form for
     * themselves, which is how two spellings of one value come to exist and how a test comes to
     * assert the spelling it was written beside instead of the contract.</p>
     */
    public static final AbendDetail ABSENT_ABEND_DETAIL = new AbendDetail("", "", "", "");

    /**
     * Validates every component and stores each exactly as received.
     *
     * <p>Nothing is altered, normalised or substituted. The three character components are checked
     * for presence, the two enumerated components for presence, the completion tier additionally for
     * membership of the failure tier, and the abend detail for presence.</p>
     *
     * <p>Successful construction yields this record instance and no separate return value.</p>
     *
     * @param runId the candidate run identifier; must be non-null and must contain at least one
     *     non-whitespace character
     * @param stepName the candidate step name; must be non-null and must contain at least one
     *     non-whitespace character
     * @param jobName the job the failed step belongs to; must be non-null
     * @param returnCode the completion tier the step reported; must be non-null and must be a tier
     *     that does not permit the following state to run
     * @param correlationId the candidate correlation identifier; must be non-null and must contain at
     *     least one non-whitespace character
     * @param abendDetail the structured abend detail, or {@link #ABSENT_ABEND_DETAIL} where the
     *     failure has no abend analogue; must be non-null
     * @throws IllegalArgumentException if any component is {@code null}, if {@code runId},
     *     {@code stepName} or {@code correlationId} is blank, or if {@code returnCode} is a tier that
     *     permits the following state to run
     */
    public BatchErrorEvent {
        // WHY : Alternatives Considered: raising the platform's null-argument exception for an absent
        //       component and the argument exception for a blank or wrongly-tiered one, so that the
        //       two failure classes were distinguishable by type. Rejected because a caller can do
        //       nothing different about them -- both mean this event cannot be published as supplied
        //       -- and because the sibling record in this package raises the argument exception for
        //       an absent value as well as a mis-sized one, so a caller catching one type here and
        //       another there would be responding to the order the two types were authored in rather
        //       than to anything about the values.
        requireNonBlank(runId, "runId");
        requireNonBlank(stepName, "stepName");

        if (jobName == null) {
            throw new IllegalArgumentException("jobName is required on a batch error event");
        }

        if (returnCode == null) {
            throw new IllegalArgumentException("returnCode is required on a batch error event");
        }

        // WHY : Assumptions: the tier is restricted to the failure tier, and the restriction is a
        //       contract rather than defensive padding. A clean or warn-tier outcome is already
        //       reported by BatchRunSummary and persisted to the durable batch.batch_run ledger, so a
        //       success reaching this constructor is a mis-wired catch route and not an outcome to
        //       describe; the reference itself reaches its warn tier by design, at
        //       app/cbl/CBTRN02C.cbl:229-230, where a non-zero reject count selects a code of four on
        //       a run that did its job correctly. Publishing that to a terminal sink would raise an
        //       operator for a run that succeeded, and the credibility of the sink is spent the first
        //       time it does. The constructor is where that dies because it is the only point every
        //       publication path passes through.
        // WHY : Alternatives Considered: comparing the tier against the failure constant directly.
        //       Rejected in favour of the predicate because the predicate is the partition the
        //       orchestration gate itself evaluates, so this check and that gate cannot come to
        //       disagree about which tiers stop a chain; an equality test would restate the partition
        //       in a second place and would need editing if the tier set ever moved, at which point
        //       the two spellings are one edit apart from contradicting each other.
        if (returnCode.permitsDownstreamRun()) {
            throw new IllegalArgumentException("returnCode " + returnCode.name() + " reports "
                    + returnCode.numericValue() + ", which permits the following state to run; a "
                    + "batch error event carries only a tier that does not");
        }

        // WHY : Trade-offs: the correlation identifier is duplicated here even though a publisher
        //       also sets it as a message attribute, and the cost is one value carried twice. It is
        //       paid because a message pulled off a dead-letter queue and written to a file arrives
        //       through several common tooling paths WITHOUT its attributes, and a payload that is
        //       not self-describing cannot then be joined back to the run it came from -- which is
        //       the one thing a reader of a dead-lettered failure needs to do. The identifier is the
        //       same one the run's log lines carry, held in the logging context under the key
        //       com.carddemo.common.web.CorrelationIdFilter publishes as correlationId.
        requireNonBlank(correlationId, "correlationId");

        // WHY : Trade-offs: the abend detail is REQUIRED rather than nullable, and the case that
        //       makes nullability tempting is real -- a failure raised as a Java exception has no
        //       abend analogue, so there are genuinely four components with nothing to put in them.
        //       It is required anyway, and the absent case is carried as ABSENT_ABEND_DETAIL, because
        //       the reference has no absent state to model: the four components are declared
        //       VALUE SPACES at app/cpy/CSMSG02Y.cpy:23, :25, :27 and :29, so the block always
        //       exists and blank IS its empty form. The cost of this choice is that a consumer
        //       distinguishes an abend from a non-abend failure by reading blank components rather
        //       than by a null test. The cost of the alternative is larger and falls on every
        //       consumer rather than on one: a nullable component makes the null check the
        //       consumer's obligation on a path that runs only when something has already failed,
        //       which is the path least likely to have been exercised.
        // WHY : Assumptions: the reason and message components of the abend detail are free-form
        //       text, and they are operator-facing diagnostics ONLY -- a caller must never populate
        //       either with record content. The ground for that is the migration's own handling of
        //       the same content elsewhere: a primary account number is reduced to its last four
        //       digits on every path but one administrative detail endpoint, a card verification
        //       value is returned nowhere at all, and a national or government-issued identifier is
        //       stored encrypted and returned reduced. Free text is the one component of this
        //       payload wide enough to defeat all of that by accident, and it is also the one this
        //       constructor cannot police -- the shared type conforms the widths and strips control
        //       characters but cannot know what a string means -- so the constraint is stated here,
        //       at the point the value enters, rather than assumed.
        if (abendDetail == null) {
            throw new IllegalArgumentException("abendDetail is required on a batch error event; use "
                    + "ABSENT_ABEND_DETAIL where the failure has no abend analogue");
        }
    }

    /**
     * Asserts that a character component is present and carries something other than whitespace.
     *
     * <p>The value is neither trimmed nor otherwise altered; only its presence is asserted.</p>
     *
     * @param value the candidate component value, which may be {@code null}
     * @param componentName the component's own name, used to name the offending component in the
     *     rejection message and never itself derived from the value
     * @throws IllegalArgumentException if {@code value} is {@code null} or contains no
     *     non-whitespace character
     */
    private static void requireNonBlank(String value, String componentName) {
        // WHY : Alternatives Considered: repeating this pair of tests inline at each of the three
        //       character components. Rejected because the three share one rule and the rule includes
        //       a decision -- reject rather than normalise -- that has to be stated once to stay one
        //       decision; three copies are three places it can be relaxed independently.
        // WHY : Trade-offs: a value carrying leading or trailing whitespace is carried through
        //       verbatim rather than trimmed, and the accepted cost is that such a value will not
        //       match the ledger key it was taken from. Trimming was rejected because it would make
        //       the published identifier differ from the one the caller believes it published, which
        //       turns a caller's error into a silent mismatch discovered by a join that returns
        //       nothing. Rejecting only the wholly blank value keeps the useless case out while
        //       leaving the caller the owner of its own bytes.
        // WHY : Assumptions: the width of these components is deliberately NOT bounded here. The
        //       twenty-four character bound published by
        //       com.carddemo.common.web.CorrelationIdFilter governs an INBOUND request header, and
        //       this module serves no route for such a header to arrive on, so applying that bound
        //       here would import a constraint from a mechanism this module does not run and would
        //       reject an identifier this module itself generated.
        if (value == null) {
            throw new IllegalArgumentException(
                    componentName + " is required on a batch error event and was null");
        }

        // WHY : Assumptions: the rejection message names the component and restates the constraint,
        //       and it quotes no part of the value. That is a minimisation rule rather than a
        //       formatting preference: an exception message is copied into logs and incident records
        //       by every layer it passes through, so a message that echoes a component is a second,
        //       unaudited path by which content could leave. Nothing is lost by omitting it, because
        //       the only values that reach here are absent or blank and neither renders as anything.
        if (value.isBlank()) {
            throw new IllegalArgumentException(componentName
                    + " is required on a batch error event and carried only whitespace");
        }
    }

    /**
     * Returns the identifier of the batch run the failed step belongs to.
     *
     * <p>This is the value an operator joins to the {@code run_id} column of the durable
     * {@code batch.batch_run} ledger, and it is byte-for-byte what the constructor received.</p>
     *
     * <p>This accessor accepts no parameters.</p>
     *
     * @return the run identifier, unaltered and never blank; never {@code null}
     */
    public String runId() {
        // WHY : Assumptions: the six accessors are written out rather than left implicit for one
        //       reason -- an implicit accessor carries no Javadoc block, so it can carry no @return,
        //       and the guarantee that matters about each of these methods is precisely the one an
        //       implicit accessor cannot state: that it returns the component unaltered.
        return runId;
    }

    /**
     * Returns the name of the step that failed.
     *
     * <p>Paired with {@link #runId()} this selects one row of one run in the durable
     * {@code batch.batch_run} ledger.</p>
     *
     * <p>This accessor accepts no parameters.</p>
     *
     * @return the step name, unaltered and never blank; never {@code null}
     */
    public String stepName() {
        return stepName;
    }

    /**
     * Returns the job the failed step belongs to.
     *
     * <p>This accessor accepts no parameters.</p>
     *
     * @return one of the job tokens {@link BatchJobName} declares; never {@code null}
     */
    public BatchJobName jobName() {
        return jobName;
    }

    /**
     * Returns the completion tier the failed step reported.
     *
     * <p>The constructor admits only a tier that does not permit the following state to run, so this
     * never answers a clean or warn-tier value.</p>
     *
     * <p>This accessor accepts no parameters.</p>
     *
     * @return the failure tier the step reported; never {@code null}, and never a tier for which
     *     {@link BatchReturnCode#permitsDownstreamRun()} answers {@code true}
     */
    public BatchReturnCode returnCode() {
        return returnCode;
    }

    /**
     * Returns the correlation identifier the failed run's log lines were written under.
     *
     * <p>This accessor accepts no parameters.</p>
     *
     * @return the correlation identifier, unaltered and never blank; never {@code null}
     */
    public String correlationId() {
        return correlationId;
    }

    /**
     * Returns the structured abend detail carried with this failure.
     *
     * <p>Where the failure has no abend analogue this answers {@link #ABSENT_ABEND_DETAIL}, whose
     * four components are blank, rather than {@code null}.</p>
     *
     * <p>This accessor accepts no parameters.</p>
     *
     * @return the abend detail, in the shared form {@code com.carddemo.common.error.AbendDetail}
     *     declares; never {@code null}
     */
    public AbendDetail abendDetail() {
        // WHY : Refactoring Rationale: the four components this returns are declared as one group
        //       item in the reference at app/cpy/CSMSG02Y.cpy:21-29 -- a code of four characters, a
        //       culprit of eight, a reason of fifty and a message of seventy-two -- and they are
        //       consumed here from common-lib rather than re-declared as four components of this
        //       record. Re-declaring them would give one inherited contract two definitions in one
        //       codebase, which is the defect the migration's shared-concerns rule exists to prevent:
        //       the two would then be free to disagree about a width, and nothing would report it.
        //       The shared type additionally applies the widths and strips control characters, so
        //       consuming it inherits those properties instead of restating them.
        return abendDetail;
    }

    /**
     * Builds an event for a failure that produced no abend analogue.
     *
     * <p>The abend detail is set to {@link #ABSENT_ABEND_DETAIL}. Every other component is validated
     * exactly as the constructor validates it, because this factory delegates to it.</p>
     *
     * @param runId the identifier of the batch run the failed step belongs to; must be non-null and
     *     must contain at least one non-whitespace character
     * @param stepName the name of the step that failed; must be non-null and must contain at least
     *     one non-whitespace character
     * @param jobName the job the failed step belongs to; must be non-null
     * @param returnCode the completion tier the step reported; must be non-null and must be a tier
     *     that does not permit the following state to run
     * @param correlationId the correlation identifier the run's log lines were written under; must be
     *     non-null and must contain at least one non-whitespace character
     * @return an event describing the failure, carrying the blank abend detail; never {@code null}
     * @throws IllegalArgumentException if any argument is {@code null}, if {@code runId},
     *     {@code stepName} or {@code correlationId} is blank, or if {@code returnCode} is a tier that
     *     permits the following state to run
     */
    public static BatchErrorEvent withoutAbendDetail(
            String runId,
            String stepName,
            BatchJobName jobName,
            BatchReturnCode returnCode,
            String correlationId) {
        // WHY : Alternatives Considered: leaving callers to spell the blank abend detail themselves
        //       at the constructor. Rejected because the absent form is a decision, not a value, and
        //       a decision spelled at each call site is a decision each call site may spell
        //       differently -- one caller writing four empty strings, another four nulls, a third a
        //       single space that is no longer blank at all. Naming it once here means the absent
        //       case has one form and one place a reader looks to learn what it is.
        // WHY : Assumptions: this factory takes exactly the five components that are not the abend
        //       detail and nothing else. No parameter it accepts could carry a record image or an
        //       identifier of a cardholder, an account or a customer, so the minimisation property
        //       recorded on this type cannot be circumvented by preferring this entry point to the
        //       constructor.
        return new BatchErrorEvent(
                runId, stepName, jobName, returnCode, correlationId, ABSENT_ABEND_DETAIL);
    }
}
