package com.carddemo.reference.api;

import com.carddemo.common.error.ApiError;
import com.carddemo.common.error.ClientInputException;
import com.carddemo.common.validation.DateEditValidator;
import com.carddemo.reference.dto.DateConversionRequest;
import com.carddemo.reference.dto.DateConversionResponse;
import com.carddemo.reference.mapper.DateInquiryReplyMapper;
import com.carddemo.reference.service.DateConversionService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The synchronous date surface of the reference context, and the only handler the contract declares.
 *
 * <p>Purpose: binds the candidate date and the picture to read it against, states the two width bounds
 * the published contract declares for them, and hands the pair to the one member that holds the
 * migrated date rules. It reaches no datastore, decides no rule and keeps nothing between requests.
 * The migrated programs behind it are the date-edit utility at {@code app/cbl/CSUTLDTC.cbl} and the
 * queue-borne date service at {@code app/app-vsam-mq/cbl/CODATE01.cbl}, and the register at
 * {@code docs/architecture/cobol-to-service-traceability.md} names this class as their surface.</p>
 *
 * <h2>Two date pictures coexist here, and they are two contracts rather than one</h2>
 *
 * <p>Assumptions: this endpoint ACCEPTS and ECHOES the ten-character ordering
 * {@code YYYY-MM-DD}, published as {@link DateEditValidator#DATE_FORMAT_MASK}, together with the
 * eight-character unseparated ordering {@code YYYYMMDD} published as
 * {@link DateEditValidator#BASELINE_DATE_FORMAT_MASK}, and it EMITS no date of its own beyond echoing
 * the one it was given. That picture is a parameter rather than a constant because the baseline
 * declared it as one: {@code app/cbl/CSUTLDTC.cbl} L85 declares
 * {@code 01 LS-DATE-FORMAT PIC X(10)} in its linkage, so a caller states the picture and the rules
 * read the date by it. The sibling contract is different and is deliberately left different: the
 * queue-borne date service EMITS the United States ordering {@code MM-DD-YYYY} beside an
 * {@code HH:MM:SS} time, requested at {@code app/app-vsam-mq/cbl/CODATE01.cbl} L349 as
 * {@code MMDDYYYY(WS-MMDDYYYY)} and L351 as {@code TIME(WS-TIME)}. Neither picture is converted into
 * the other anywhere in this class, and the ten-character ordering is not "the" date form of the file:
 * one contract is what a caller SENDS to be judged, the other is what a queue reply CARRIES.</p>
 *
 * <p>Assumptions: the two are independent contracts in the baseline as well, which is established by a
 * count rather than by reading intent into the source. A search for {@code CSUTLDTC} in
 * {@code app/app-vsam-mq/cbl/CODATE01.cbl} returns zero occurrences, so the queue-borne date service
 * never calls the date-edit utility. That is how the two were designed, and it is why this endpoint
 * judges a submitted date without converting it and why nothing here validates the reply picture
 * against the ten-character one.</p>
 *
 * <h2>The reply the sibling route carries, and why this class does not compose it</h2>
 *
 * <p>Assumptions: the queue reply is a forty-six-character string whose shape is load-bearing, and it
 * is composed by {@link DateInquiryReplyMapper} rather than here. Its two labels are exactly fourteen
 * characters each, {@code 'SYSTEM DATE : '} and {@code 'SYSTEM TIME : '}: the word, a space, four
 * letters, a space, a colon and a trailing space, so the space on BOTH sides of the colon and the
 * trailing space are part of the label. The composition at
 * {@code app/app-vsam-mq/cbl/CODATE01.cbl} L355 to L360 concatenates them with the values under
 * {@code DELIMITED BY SIZE}, which inserts NOTHING between the date value and the second label. The
 * total is therefore 14 + 10 + 14 + 8 = 46, published as
 * {@link DateInquiryReplyMapper#REPLY_BODY_LENGTH}, over the two declared value widths
 * {@code WS-MMDDYYYY PIC X(10)} at L37 and {@code WS-TIME PIC X(8)} at L38. Inserting a space, a comma
 * or a line break between the date value and the second label would leave every label present and the
 * total wrong, which is the one way that shape breaks without looking broken.</p>
 *
 * <p>Assumptions: the separators in that reply are asymmetric, and the asymmetry is in the source
 * rather than in a reading of it. L350 requests {@code DATESEP('-')} WITH an argument, so the date is
 * hyphen-separated; L352 requests {@code TIMESEP} with NO argument at all, so the time takes the
 * platform default, which is a colon. The absence of the argument is what settles the colon, so
 * mirroring the hyphen onto the time would contradict the source while looking symmetrical.</p>
 *
 * <h2>Where the current instant comes from, and why not from here</h2>
 *
 * <p>Refactoring Rationale: the baseline read its instant from the platform, at
 * {@code app/app-vsam-mq/cbl/CODATE01.cbl} L343 to L345 through
 * {@code EXEC CICS ASKTIME ABSTIME(WS-ABS-TIME)}, and rendered it at L347 to L353. This endpoint
 * reports on a date a caller submits, so it needs no instant and reads none: there is no clock call in
 * this class and no clock member on it. The route that does need one, the reply composition described
 * above, takes it from a {@code java.time.Clock} supplied to
 * {@code com.carddemo.reference.service.DateInquiryMessageListener} at construction. That is the
 * supported path rather than a preference, because
 * {@code com.carddemo.common.time.TimestampFormatter} publishes a member taking a clock and
 * deliberately publishes no argument-free equivalent, and it is what lets a reply be asserted byte for
 * byte instead of only pattern-matched.</p>
 *
 * <h2>Why one shared validator is the faithful shape and not an abstraction invented here</h2>
 *
 * <p>Refactoring Rationale: the date rules are held once, in
 * {@code com.carddemo.common.validation.DateEditValidator}, and no part of them is restated in this
 * package. That reproduces the baseline's own structure rather than reorganising it:
 * {@code app/cbl/CSUTLDTC.cbl} L83 opens a {@code LINKAGE SECTION} and L88 declares
 * {@code PROCEDURE DIVISION USING LS-DATE, LS-DATE-FORMAT, LS-RESULT}, which is a callable subprogram
 * taking a date, the picture to read it by and a result. A callable whose picture arrives as a
 * parameter is already one implementation serving many callers, so collapsing the rules into one
 * shared holder is transformation rule T2 applied to a callable rather than to a record layout.</p>
 *
 * <p>Alternatives Considered: implementing the calendar and range edits inside this controller. It was
 * rejected because those edits would then exist twice, and the two copies would answer the same
 * question for different callers -- this endpoint against every other migrated screen that edits a
 * dated field through the shared holder -- with nothing keeping them equal. The baseline made the
 * opposite choice for the same reason, which is why the utility is a subprogram and not a paragraph
 * inside a screen program.</p>
 *
 * <h2>The result width this surface answers for, and the two widths it is not</h2>
 *
 * <p>Assumptions: the width of the date-edit result is eighty characters, declared at
 * {@code app/cbl/CSUTLDTC.cbl} L86 as {@code 01 LS-RESULT PIC X(80)} and published as
 * {@link DateEditValidator#RESULT_LENGTH}. It is a different contract from the message widths this
 * package carries elsewhere, which are forty for an informational message and seventy-five for an
 * error, declared at {@code app/app-transaction-type-db2/cbl/COTRTUPC.cbl} L142 as
 * {@code 05 WS-INFO-MSG PIC X(40)} and L167 as {@code 05 WS-RETURN-MSG PIC X(75)}. The
 * working-storage widths are the contract in both cases and the wider screen fields that display them
 * are not, so none of the three may be applied to another: eighty belongs to the result structure a
 * date evaluation fills, and forty and seventy-five belong to messages a maintenance screen writes.</p>
 *
 * <h2>How a refusal reaches the caller</h2>
 *
 * <p>Assumptions: no refusal is rendered here. {@code com.carddemo.common.error.GlobalExceptionHandler}
 * is the one shared advice for every service, so this class declares none of its own and hands back no
 * body it composed itself; a second advice for the same type in this module would make which of the
 * two answered depend on ordering. A refusal of the caller's input therefore leaves this class as a
 * raised refusal and arrives as the 400 the contract declares, carrying the shared problem shape
 * {@code ApiError} with its per-field array.</p>
 *
 * <p>Assumptions: this class is the ONE handler of this method and path, and the migration plan's file
 * list together with the traceability register name it as such. A second class carrying the same pair
 * would leave the container unable to choose and the contract with two answers to one question, which
 * the bidirectional comparison in {@code ReferenceApiRoutingContractTest} exists to prevent.</p>
 *
 * <p>Assumptions: the width bounds the contract declares for the two query parameters are stated on the
 * HANDLER PARAMETERS below, because that is where the framework applies them. The same bounds declared
 * on a private member are inert -- no validation runs there -- so a contract width stated in that
 * position would read as enforced while admitting anything.</p>
 */
@RestController
@RequestMapping(DateConversionController.BASE_PATH)
public class DateConversionController {

    /** The path the published contract declares for the date evaluation. */
    public static final String BASE_PATH = "/api/v1/reference/date-evaluations";

    /** The query-parameter name carrying the candidate date. */
    public static final String PARAM_DATE = "date";

    /** The query-parameter name carrying the picture to read the candidate against. */
    public static final String PARAM_MASK = "mask";

    /**
     * Least width the contract admits for the candidate date, the unseparated ordering.
     *
     * <p>Assumptions: the bound is taken from the width the shared holder publishes rather than
     * written as a number here, so the boundary and the rules cannot come to hold two different
     * eights. It is the width of the unseparated ordering.</p>
     */
    private static final int MIN_DATE_WIDTH = DateEditValidator.PACKED_DATE_LENGTH;

    /**
     * Greatest width the contract admits for the candidate date, the separated ordering.
     *
     * <p>Assumptions: taken from the shared holder for the same reason as the bound above, and equal
     * to the width the baseline linkage declares for its own date argument.</p>
     */
    private static final int MAX_DATE_WIDTH = DateEditValidator.MASKED_DATE_LENGTH;

    /**
     * Least width the contract admits for the picture, one character.
     *
     * <p>Assumptions: the contract states the picture as a bounded string of at least one character,
     * so a supplied but empty picture is refused at the boundary while an ABSENT one is admitted and
     * defaulted by the member this class delegates to. The two cases are therefore distinguishable,
     * which is what lets the reply echo the picture that was actually applied.</p>
     */
    private static final int MIN_MASK_WIDTH = 1;

    /**
     * Greatest width the contract admits for the picture, ten characters.
     *
     * <p>Assumptions: this is the declared width of the baseline's own picture argument,
     * {@code 01 LS-DATE-FORMAT PIC X(10)} at {@code app/cbl/CSUTLDTC.cbl} L85, and it is deliberately
     * NOT narrowed to the set of pictures the rules recognise. A picture outside that set is answered
     * with the unusable-pattern verdict and not with a refusal, and narrowing the bound to an
     * enumeration would make that verdict unreachable because the boundary would refuse the request
     * before any evaluation ran.</p>
     */
    private static final int MASK_WIDTH = 10;

    /**
     * The constant diagnostic a width refusal is recorded and answered with.
     *
     * <p>Assumptions: the sentence names the parameter and the constraint and repeats no value the
     * caller sent, which is the discipline every refusal in this context follows. A constant sentence
     * is what lets an operational rule match this refusal without matching on caller input, and that is
     * the concrete reason the refusal below does not pass through the diagnostic it caught: the
     * diagnostic the shared holder raises names the picture the caller supplied and both widths, so
     * forwarding it would echo up to {@link #MASK_WIDTH} characters of caller input into an operational
     * record and would give the same refusal a different sentence for every request.</p>
     */
    private static final String WIDTH_REFUSAL_MESSAGE =
            PARAM_DATE + " must be the width the picture it was sent with declares, "
                    + MIN_DATE_WIDTH + " characters for the unseparated ordering and "
                    + MAX_DATE_WIDTH + " for the separated one";

    /** The holder of the migrated date rules this handler delegates to. */
    private final DateConversionService evaluator;

    /**
     * Builds the controller over the holder of the date rules.
     *
     * <p>Alternatives Considered: taking {@code DateEditValidator} directly and calling it from the
     * handler. It was rejected because the member reached below does two things beyond calling those
     * rules -- it applies the default picture when a caller sends none, and it assembles the six
     * members of the reply -- so calling the rules straight from here would put both of those in the
     * boundary layer, whose whole obligation is to hold no date rule. The rules are held in one place
     * either way; what this choice settles is the ROUTE to them.</p>
     *
     * <p>Refactoring Rationale: this parameter is typed for the evaluation it reaches and not for a
     * transport. It formerly took {@code DateConversionMessageListener}, a type that carried a second
     * queue consumer competing with {@code DateInquiryMessageListener} for the same request queue;
     * consolidating that flow removed the competing consumer and left this evaluation as the only
     * member the controller needed, so the type was withdrawn rather than kept as a wrapper. A
     * controller depending on a type named for a queue listener is a dependency a reader has to
     * explain away, and there is now nothing to explain.</p>
     *
     * @param evaluator the holder of the migrated date rules, as a {@link DateConversionService};
     *     must not be {@code null}
     */
    public DateConversionController(DateConversionService evaluator) {
        this.evaluator = evaluator;
    }

    /**
     * Reports the verdict on one candidate date read against one picture.
     *
     * <p>Purpose: the migrated form of the date-edit utility's linkage, taken over query parameters
     * and answered as the structured verdict the contract publishes. The candidate is bound as text
     * and deliberately not as a date: the whole point of the operation is to report on a value whose
     * usability is in question, and a binder that parsed it would refuse precisely the inputs a caller
     * is asking about, answering a generic binding failure where the specific verdict was wanted.</p>
     *
     * <p>Assumptions: the picture accepted here is the ten-character {@code YYYY-MM-DD} ordering or
     * the eight-character {@code YYYYMMDD} one, and the only date this operation emits is the
     * candidate echoed back unchanged. It emits neither the United States ordering nor a time, because
     * those belong to the queue reply described on this class and are not produced on this route.</p>
     *
     * <p>Assumptions: the verdict is returned exactly as the rules reported it, and no acceptance flag
     * is derived from it here. The severity and the message number travel as two members because two
     * baseline callers accept a rejected evaluation when the message number is the tolerated one while
     * the shared driver accepts none, so which of those readings applies is the caller's to choose and
     * would be lost if this handler collapsed them into one answer.</p>
     *
     * @param date the candidate date as text, required by the contract, admitted between
     *     {@link #MIN_DATE_WIDTH} and {@link #MAX_DATE_WIDTH} characters and reported on rather than
     *     refused when it is a well-formed width that names no usable day
     * @param mask the picture to read the candidate against, as text of at most {@link #MASK_WIDTH}
     *     characters, or {@code null} when the caller sends none and the default the contract
     *     publishes applies
     * @return the verdict as a {@link DateConversionResponse}, carrying the named feedback code, the
     *     severity, the message number, the verdict wording and the candidate and picture echoed back
     * @throws ClientInputException if the candidate's width does not match the picture it was sent
     *     with, which the contract declares a request error; the type extends
     *     {@code IllegalArgumentException} and the shared advice answers it as 400 carrying the
     *     per-field array
     */
    @GetMapping
    public DateConversionResponse evaluateDate(
            @RequestParam(name = PARAM_DATE)
            @NotBlank @Size(min = MIN_DATE_WIDTH, max = MAX_DATE_WIDTH) String date,
            @RequestParam(name = PARAM_MASK, required = false)
            @Size(min = MIN_MASK_WIDTH, max = MASK_WIDTH) String mask) {

        try {
            // WHY : Alternatives Considered: composing a verdict here from the rules directly.
            //       Rejected because the member called applies the default picture and assembles the
            //       six members of the reply, so composing here would put a date rule in the boundary
            //       layer and would give the two behaviours two homes. The picture is handed on exactly
            //       as received, including as null, so that the default is applied in the one place
            //       that also echoes which picture was used.
            // WHY : Assumptions: this evaluation is reached from this route ALONE, and no shared path
            //       with the queue route exists to be preserved. The queue route is
            //       DateInquiryMessageListener; it emits the current system date and time, reads no
            //       field of its request and calls no evaluation, so the two transports answer
            //       different questions. Stating that here keeps a reader from looking for a common
            //       evaluation to hold the two to.
            return this.evaluator.convert(new DateConversionRequest(date, mask));
        } catch (DateEditValidator.DateWidthException widthRefusal) {
            // WHY : Refactoring Rationale: the width mismatch is re-raised as a caller refusal rather
            //       than allowed to propagate, because the shared advice tests for the caller-refusal
            //       type and deliberately not for its supertype -- widening it there would report
            //       every internal invariant in the migration as a 400 the caller should act on. Left
            //       to propagate, this one would answer 500 while the contract states the case is 400,
            //       telling a caller its own input was the service's fault.
            // WHY : Alternatives Considered: catching the validator's supertype instead of its OWN
            //       width condition. Rejected because the supertype sweeps up every internal invariant
            //       the validator raises -- a feedback record whose severity contradicts its own code,
            //       an unknown date-component identity, a year outside the four-digit domain, a value
            //       too wide for a four-digit field -- and would report each to the caller as a
            //       four-hundred naming the date parameter. Two things would be lost at once: a caller
            //       told to correct a request it had sent correctly, and a genuine service defect that
            //       never reaches the five-hundred channel the alerting watches. Narrowing the catch
            //       costs this boundary nothing, because deciding which picture demands which width
            //       belongs to the validator, which is where the rule already lives.
            // WHY : Assumptions: no other input-dependent refusal reaches this catch, so narrowing it
            //       loses no case. A picture the rules do not recognise is RETURNED as the
            //       unusable-pattern verdict rather than raised; a component that is not numeric, a
            //       month or day outside its domain and a day below the supported calendar floor are
            //       each returned as their own verdict; and a null candidate is excluded by the presence
            //       bound on the parameter above.
            // WHY : Assumptions: the caught condition is deliberately not passed through. Its message
            //       names the picture the caller supplied and both widths, so forwarding it would echo
            //       up to MASK_WIDTH characters of caller input into an operational record and would
            //       give the same refusal a different sentence for every request.
            throw new ClientInputException(ApiError.CODE_VALIDATION, PARAM_DATE,
                    WIDTH_REFUSAL_MESSAGE);
        }
    }
}
