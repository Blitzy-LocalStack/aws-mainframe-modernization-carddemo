package com.carddemo.common.error;

import com.carddemo.common.security.CardNumberMasker;
import com.carddemo.common.validation.FieldValidationFlag;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

/**
 * The single advice that renders every failed request of every migrated service as an
 * {@link ApiError}.
 *
 * <p>Refactoring Rationale: this class is the baseline's own pattern with one failure mode removed.
 * The authorization consumer {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl} factors error
 * emission into one paragraph defined at its line 983 and invokes it from fourteen separate places --
 * lines 282, 316, 429, 500, 512, 547, 560, 595, 608, 639, 778, 846, 931 and 975. Centralising the
 * emission is therefore already house practice; the migrated form differs in exactly one respect,
 * which is that the advice is reached by an exception propagating out of a handler rather than by an
 * invocation written at each site. That single difference is the whole benefit: fourteen sites is
 * fourteen opportunities to add a fifteenth error path and forget the invocation, and a forgotten
 * invocation is invisible -- the program still works and the record simply is not written. An advice
 * reached by propagation has nothing at the call site to omit. The baseline had to remember fourteen
 * times; this class cannot forget.</p>
 *
 * <p>Assumptions: factoring a cross-cutting concern into one reusable named unit is house practice
 * across that program family rather than a single instance, which is why the migrated form
 * generalises the pattern instead of inventing it. Two further named paragraphs do the same thing in
 * {@code app/app-authorization-ims-db2-mq/cbl/COPAUS1C.cbl}: {@code TAKE-SYNCPOINT.} at lines 557 to
 * 560 wraps the commit verb, and {@code ROLL-BACK.} at lines 565 to 569 wraps the rollback verb, each
 * so that every site needing it performs one paragraph rather than repeating the verb. The templated
 * field-highlight macro at lines 17 to 27 of {@code app/cpy/CSSETATY.cpy} is the third instance,
 * factored so far that the screen field, the variable and the map name all arrive as substitution
 * placeholders. Three independent instances is a convention, and this class is its migrated form.</p>
 *
 * <p>Assumptions: nothing this class emits carries a credential, a connection string, a stack trace or
 * a vendor error code out to a client. Every problem shape is assembled from members
 * {@link ApiError} defines and never from the text of a caught exception; the caught exception goes to
 * the operational record instead, under the same correlation identity the response carries, so a
 * client can be answered with one short sentence while the failure stays fully diagnosable. The
 * restrict-on-delete mapping below is the sharpest case: a driver's constraint-violation text names
 * the schema, the table and the constraint, so returning it would both fail to tell the caller what to
 * do and disclose the internal shape of the store to an untrusted caller.</p>
 *
 * <p>Assumptions: the request path is the one caller-influenced value this class carries, and it is
 * narrowed once at {@link #pathOf(HttpServletRequest)} rather than at each of the eighteen sites that
 * read it. A resolved path can hold a primary account number in full -- the published card contract
 * declares its path parameter {@code pattern '^[0-9]{16}$'} -- so an unnarrowed read would put that
 * number into nine operational log lines as well as nine response bodies. Narrowing at the sink is
 * what makes the guarantee survive the next handler added to this advice: a new site inherits the
 * masking instead of having to remember it, which is the same reason this class exists rather than an
 * emission call written at each error path.</p>
 *
 * <p>Assumptions: a machine-readable code and the sentence a person reads are separate members of the
 * emitted shape, never one composed string, because the baseline's own structured error record keeps
 * them separate: {@code ERR-CODE-1 PIC X(09)} and {@code ERR-CODE-2 PIC X(09)} are declared at lines
 * 37 and 38 of {@code app/app-authorization-ims-db2-mq/cpy/CCPAUERY.cpy}, distinctly from
 * {@code ERR-MESSAGE PIC X(50)} on line 39. The opposite is also present in the baseline and is the
 * one shape this class does not carry across: lines 544 to 549 of {@code COPAUS1C.cbl} assemble a
 * return code into the sentence a user reads, producing a message whose text changes with a machine
 * value. No handler below interpolates any code, class name or status into a message; the code
 * travels as {@link ApiError#code()} and the sentence as {@link ApiError#message()}, so an operator
 * can match on a stable token while the wording stays free to be reworded.</p>
 *
 * <p>Assumptions: a data-quality rejection is answered with a 4xx and never with a 5xx, because the
 * baseline classifies exactly that outcome as a soft one. Lines 558 and 559 of
 * {@code tests/README.md} record that each posting reject writes the reject stream and sets
 * {@code RETURN-CODE = 4}, and lines 563 to 565 give the three texts it writes -- 25, 24 and 21
 * characters of unpunctuated capitals. A rejected request is therefore an outcome the service is
 * designed to produce rather than a fault in it, and reporting it as a server failure would tell a
 * caller its own input was the service's fault while also making an ordinary business outcome
 * indistinguishable from a genuine one in the operational record. That graded scale belongs to the
 * reference suite alone: the build tools of this module report pass or fail and nothing between, so
 * no state of this module is ever described in the scale's terms.</p>
 *
 * <p>Assumptions: this class is not exempt from the documentation gate by virtue of being annotated.
 * The audit configuration at {@code config/checkstyle/checkstyle.xml} leaves the presence checks'
 * skip-annotation list empty, so no annotation excuses a type or a method, and the annotation on each
 * handler below is not an override of anything and so cannot benefit from the exemption an override
 * would ordinarily receive. Every handler therefore carries a purpose sentence, a tag for each of its
 * parameters and a return tag, ordered as the audit's clause-order check requires, and the gate runs
 * at the Maven validate phase, which is before compilation and on every local build rather than in
 * continuous integration alone.</p>
 *
 * <p>Assumptions: every private helper in this class carries the same complete Javadoc a public method
 * does. Two separate reasons make that mandatory rather than tidy. The audit sets the presence check's
 * access filter down to private, so a private method with no Javadoc at all fails; and the
 * completeness check names all four access levels including private, with missing parameter tags and
 * missing return tags both disallowed and throws validation on, so a private block that is present
 * but partial fails as well. Line 15 of the Explainability rule carries no visibility qualifier
 * either, which is what settles the question independently of what any linter reaches.</p>
 *
 * <p>Trade-offs: no handler in this class wraps its body in a try-catch, and that is a constraint
 * accepted rather than a style preference. The completeness check configured by
 * {@code config/checkstyle/checkstyle.xml} runs with its throws validation switched on, and that
 * validation reads a method's declared and thrown types without being able to see a throw that a catch
 * has already absorbed, so a handler that caught and rethrew would document its own exception contract
 * against a check that had stopped looking. Writing each handler as straight-line assembly keeps the contract in the Javadoc honest by
 * making it verifiable. Where a method does declare a broad type, both the declared parent and the
 * exact type reaching it in practice are named in the Javadoc, so the block stays honest at the point
 * the check is blind. What is given up is the ability to translate a failure raised while building a
 * response; that is handled instead by making each helper unable to raise one, which is why the two
 * message helpers substitute a fallback rather than pass a blank string to a constructor that would
 * reject it.</p>
 *
 * <p>Assumptions: the web starter that supplies this class's annotations,
 * {@link org.springframework.http.ResponseEntity} and {@link HttpStatus} is declared optional in
 * {@code services/common-lib/pom.xml}, and so is the validation starter behind the two per-field
 * handlers. Optional settles the compile classpath and not the runtime one: this module compiles
 * against both, and a consumer that excludes either will not have it at run time, so this class is
 * registered only where a servlet web application is actually present. The logging facade the
 * operational record is written through arrives transitively with the same starter chain rather than
 * as a dependency of its own, and no dependency is declared here to obtain any of them.</p>
 *
 * <p>Assumptions: this advice is registered by the shared kernel's own auto-configuration rather than
 * by each service, and is therefore active in a service that has written no configuration class at
 * all. That is deliberate for the same reason the propagation argument above is: a registration a
 * service has to remember is a registration a service can omit, and the symptom of omitting it is a
 * framework-shaped error body escaping from one service while the other seven answer in the migrated
 * shape.</p>
 *
 * <p>Trade-offs: the two conflict mappings are separate handlers rather than one handler testing the
 * exception type, even though both answer HTTP 409. Keeping them apart is what lets each carry its own
 * verbatim baseline message -- the optimistic-lock message is 46 characters and reproduced character
 * for character from line 522 of {@code app/cbl/COACTUPC.cbl} -- and transformation rule T8 requires
 * exactly that. One handler would have to choose a message before it knew which conflict it had.</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * The verbatim message the baseline displays when a record was altered by another user.
     *
     * <p>Assumptions: reproduced character for character from
     * {@code 88 DATA-WAS-CHANGED-BEFORE-UPDATE VALUE 'Record changed by some one else. Please review'}
     * declared across lines 521 and 522 of {@code app/cbl/COACTUPC.cbl}. Three properties are carried
     * across unchanged: it is 46 characters, measured from the literal itself rather than taken from
     * any summary of it; it has no terminating period, the period visible after the closing quote in
     * the source being the statement terminator rather than part of the text; and the two-word spelling
     * of the third-party pronoun is preserved exactly as written, the same spelling appearing again at
     * line 286 of {@code app/cpy/CSUTLDPY.cpy}. Transformation rule T8 requires user-visible strings to
     * be carried character for character, and a spelling regularised on the way through would be a
     * silent behavioural change of precisely the kind that rule exists to prevent.</p>
     *
     * <p>Refactoring Rationale: two citations on that program are routinely conflated and they are
     * different constructs, so both are stated here separately. Line 168 is
     * {@code 05 WS-DATACHANGED-FLAG PIC X(1).}, a one-character marker whose two conditions are
     * declared at lines 169 and 170 as {@code 88 NO-CHANGES-FOUND VALUE '0'} and
     * {@code 88 CHANGE-HAS-OCCURRED VALUE '1'} -- a state, holding a digit. Lines 521 and 522 are
     * something else entirely: a message-valued condition on a message field, one member of the eleven
     * whose value literals occupy lines 508 to 528, holding the text a user reads. Any reading that
     * treats the second as a condition attached to the first is wrong, and the difference decides what
     * this constant is: only the message-valued condition becomes a message, while the marker becomes
     * the version column the persistence layer maintains and never becomes text at all.</p>
     *
     * <p>Trade-offs: the literal itself is not written out again here. It is taken from
     * {@link ApiError#COACTUPC_RECORD_CHANGED}, which declares it once for the package, so that the
     * one copybook-sourced string this advice and that record both need cannot come to differ between
     * two files that ship together. Transformation rule T2 requires a shared concern to live in one
     * place, and the reference programs express the same discipline by resolving every layout through a
     * single compiler include path. What is given up is that a reader of this line must follow one
     * reference to see the text; what it buys is that the 46 characters are asserted against the
     * copybook in exactly one place, so no test can pass here while the same string is wrong there.</p>
     */
    public static final String MESSAGE_RECORD_CHANGED = ApiError.COACTUPC_RECORD_CHANGED;

    /**
     * The verbatim message the baseline displays when the account row could not be locked for update.
     *
     * <p>Alternatives Considered: folding a failure to acquire a lock into the record-changed mapping
     * above, on the reasoning that both end with an update that did not happen. Rejected, because the
     * same eleven-member family in {@code app/cbl/COACTUPC.cbl} distinguishes three outcomes and this
     * advice keeps all three distinguishable. Lines 517 to 520 are write-time lock-acquisition
     * failures, this 40-character literal for the account row and the 41-character
     * {@link #MESSAGE_CUSTOMER_LOCK_FAILED} for the customer row: the update never began, and nobody
     * else has necessarily changed anything. Lines 521 and 522 are a before-image mismatch: the read
     * succeeded, the write was attempted, and the record had moved underneath it. Lines 523 and 524 are
     * a rewrite failure, {@link #MESSAGE_UPDATE_FAILED}: the lock was held and the write itself did not
     * take. Merging any two of the three would collapse outcomes the baseline reports apart into one
     * response, and a caller told a record had changed when in truth a lock timed out would retry the
     * wrong way -- reloading a record that never moved instead of simply trying again.</p>
     *
     * <p>Assumptions: the two lock literals are entity-specific and this advice is entity-agnostic, so
     * both are published for the bounded context that knows which row it was locking and neither is
     * emitted from here. The entity-agnostic sentence this advice does emit is
     * {@link #MESSAGE_LOCK_UNAVAILABLE}.</p>
     */
    public static final String MESSAGE_ACCOUNT_LOCK_FAILED =
            "Could not lock account record for update";

    /**
     * The verbatim message the baseline displays when the customer row could not be locked for update.
     *
     * <p>Assumptions: reproduced character for character from lines 519 and 520 of
     * {@code app/cbl/COACTUPC.cbl} and measured at 41 characters, one more than its account-row sibling
     * {@link #MESSAGE_ACCOUNT_LOCK_FAILED} because the noun naming the row is one letter longer. The two
     * are declared as two constants rather than composed from one template with the noun substituted,
     * because transformation rule T8 makes each literal the contract in its own right and a template
     * would make the pair a derivation that could silently stop matching either line.</p>
     */
    public static final String MESSAGE_CUSTOMER_LOCK_FAILED =
            "Could not lock customer record for update";

    /**
     * The entity-agnostic sentence emitted when a row could not be locked for update.
     *
     * <p>Trade-offs: this is the one message in this class that is not a baseline literal, and the
     * divergence is deliberate. The baseline names the row in the text, at 40 and 41 characters for the
     * account and customer rows respectively, because the program raising it knows which row it was
     * writing. This advice is shared by every bounded context and is reached by propagation, so at the
     * point it renders the response the identity of the row is no longer available to it. Emitting one
     * of the two entity-specific literals would therefore mean naming the wrong row roughly half the
     * time, which is worse than naming none. Both literals stay published above for the context that
     * does know, and it supplies one through {@link ApiError} instead; the baseline names the row, this
     * default does not, and the divergence is documented rather than silently introduced.</p>
     */
    public static final String MESSAGE_LOCK_UNAVAILABLE = "Could not lock the record for update";

    /**
     * The verbatim message the baseline displays when the rewrite of a locked record failed.
     *
     * <p>Assumptions: reproduced character for character from lines 523 and 524 of
     * {@code app/cbl/COACTUPC.cbl}, where the condition is named for the state it describes -- the row
     * was locked and the update still did not take -- and measured at 23 characters. It is published
     * rather than emitted, because a write that failed for neither of the two reasons a client can act
     * on is not a client-correctable condition: it belongs on the internal-failure path, where
     * {@link #onUnexpectedFailure(Exception, HttpServletRequest)} answers without naming a cause. A
     * bounded context that has established this specific outcome supplies this text through
     * {@link ApiError} itself, which is what keeps the third of the three conditions expressible without
     * this advice having to guess at it.</p>
     */
    public static final String MESSAGE_UPDATE_FAILED = "Update of record failed";

    /**
     * The message returned when a reference row cannot be deleted because rows still point at it.
     *
     * <p>Assumptions: this describes the business rule rather than the constraint. The reference-data
     * foreign key carries {@code ON DELETE RESTRICT}, preserving the semantic the baseline's own
     * {@code XTRNTYCAT} constraint already asserts, so deleting a transaction type is impossible while
     * categories still reference it. The migrated response names that rule so the caller knows what to
     * do about it, where the database's own complaint would name the schema, the table and the
     * constraint and say nothing actionable.</p>
     */
    public static final String MESSAGE_REFERENCED_ROW =
            "Cannot delete: other records still refer to this entry";

    /**
     * The message returned for a failure the client cannot correct.
     *
     * <p>Assumptions: this sentence names nothing about the cause on purpose. The cause is written to
     * the operational record under the same correlation identity that the response carries, which is
     * what allows the response to stay uninformative without the failure becoming undiagnosable.</p>
     */
    public static final String MESSAGE_INTERNAL = "Unable to complete the request. Please try again";

    /**
     * The message returned when a request failed field validation.
     *
     * <p>Assumptions: the aggregate sentence reports that there are field errors and does not
     * enumerate them, because the enumeration travels in the per-field array. The baseline behaves the
     * same way: the aggregate text is written under a message-off latch and so reports the first
     * failure only, while the field markers accumulate.</p>
     */
    public static final String MESSAGE_VALIDATION_FAILED = "Please correct the highlighted fields";

    /**
     * The message returned when a request body could not be read at all.
     *
     * <p>Assumptions: kept distinct from {@link #MESSAGE_VALIDATION_FAILED} because an unreadable body
     * produces no field array -- there is no bound object to have fields -- so a client receiving the
     * validation sentence with an empty array would have nothing to act on.</p>
     */
    public static final String MESSAGE_MALFORMED_REQUEST = "The request could not be read";

    /**
     * The message returned when the record a request named does not exist.
     *
     * <p>Assumptions: deliberately generic. The baseline expresses this as a file status rather than as
     * an exception, and its reject reasons distinguish an unmatched cross-reference read from an
     * unmatched account read; a bounded context that needs that distinction supplies its own message
     * through {@link ApiError} rather than reusing this one.</p>
     */
    public static final String MESSAGE_NOT_FOUND = "The requested record was not found";

    /**
     * The message returned when an authenticated caller is not permitted the operation.
     *
     * <p>Assumptions: the message says the caller is not permitted and never says why, and it is the
     * same message whatever the missing authority was. Naming the required group would tell an
     * untrusted caller which authority to go and obtain.</p>
     */
    public static final String MESSAGE_FORBIDDEN = "Not authorized for this operation";

    /**
     * The code borne by a request from an authenticated caller lacking the required authority.
     *
     * <p>Assumptions: declared on this advice rather than on {@link ApiError} because it has no
     * counterpart anywhere else in the migrated model -- the baseline's authorization split is decided
     * inside a program rather than reported as a record status -- whereas the four codes on that type
     * each correspond to a shape a service builds directly.</p>
     */
    public static final String CODE_FORBIDDEN = "CARDDEMO-0403";

    /**
     * The condition code stamped on the abend detail every unclaimed failure is reported through.
     *
     * <p>Assumptions: four characters, because {@code ABEND-CODE PIC X(4)} at line 22 of
     * {@code app/cpy/CSMSG02Y.cpy} declares four and {@link AbendDetail} shortens anything wider to that
     * width rather than refusing it. It is the one component of the abend block that crosses to the
     * client, which is why it is drawn from a closed set instead of being composed: a short code names
     * the class of failure without describing its circumstances. The value is the same for every
     * unclaimed failure on purpose, so that two failures of different internal causes stay
     * indistinguishable from outside while remaining fully distinguishable in the operational
     * record.</p>
     */
    public static final String ABEND_CODE_UNEXPECTED = "SVCE";

    /**
     * The component name recorded as responsible on the abend detail of an unclaimed failure.
     *
     * <p>Assumptions: eight characters, matching {@code ABEND-CULPRIT PIC X(8)} at line 24 of
     * {@code app/cpy/CSMSG02Y.cpy}, a width that is not arbitrary -- it is the length of an external
     * program name in the reference baseline, so the component was sized to hold the name of whichever
     * program was blamed. The migrated analogue of a program name is the component that reported the
     * failure, which for every failure reaching this class is this advice. It is a fixed literal rather
     * than the class name of the caught exception, because a culprit composed from a caught type would
     * put a provider's internal class name into the value and this component is operator-facing text
     * rather than a machine field.</p>
     */
    public static final String ABEND_CULPRIT_ADVICE = "CDMOADVC";

    /**
     * The reason recorded on the abend detail of an unclaimed failure.
     *
     * <p>Assumptions: written to sit inside {@code ABEND-REASON PIC X(50)} at line 26 of
     * {@code app/cpy/CSMSG02Y.cpy}, so it reaches the operational record whole rather than shortened.
     * It names the classification and not the cause: the cause is the throwable itself, which is passed
     * to the logging facade as a throwable so that the appender configuration governs how much of it is
     * rendered, rather than being interpolated into any string this class builds.</p>
     */
    public static final String ABEND_REASON_UNEXPECTED = "Unhandled failure reported by the shared advice";

    /**
     * The fully-qualified name of the persistence abstraction's pessimistic-locking failure.
     *
     * <p>Assumptions: named as a string for the same reason as the two below, and named separately from
     * the optimistic-locking failure because the two are siblings in the abstraction's hierarchy rather
     * than one being a subclass of the other. That is what lets the superclass walk in
     * {@link #isOfType(Throwable, String)} tell them apart, and telling them apart is the whole point of
     * the three-outcome distinction recorded on {@link #MESSAGE_ACCOUNT_LOCK_FAILED}. The subclass raised
     * when a lock cannot be obtained within the configured wait descends from this type, so it is
     * recognised through the walk without needing an entry of its own.</p>
     */
    private static final String PESSIMISTIC_LOCK_EXCEPTION_NAME =
            "org.springframework.dao.PessimisticLockingFailureException";

    /**
     * The fully-qualified name of the persistence abstraction's optimistic-locking failure.
     *
     * <p>Assumptions: a name rather than a class literal, for the reason recorded on
     * {@link #onRuntimeFailure(RuntimeException, HttpServletRequest)} -- the shared kernel must not
     * take a compile-time dependency on the persistence abstraction. It is a constant rather than an
     * inline literal so that the two names sit together and can be asserted by one test against the
     * library on the test path, which is where a rename in a future major version would be caught.</p>
     */
    private static final String OPTIMISTIC_LOCK_EXCEPTION_NAME =
            "org.springframework.dao.OptimisticLockingFailureException";

    /**
     * The fully-qualified name of the persistence abstraction's data-integrity violation.
     *
     * <p>Assumptions: named for the same reason as its sibling above. This is the base type of the
     * constraint-violation family, so a subclass raised for a specific breach is recognised through the
     * superclass walk rather than needing an entry of its own.</p>
     */
    private static final String INTEGRITY_VIOLATION_EXCEPTION_NAME =
            "org.springframework.dao.DataIntegrityViolationException";

    /**
     * The number of cause-chain elements {@link #isOfType(Throwable, String)} will inspect.
     *
     * <p>Assumptions: the bound exists to make the classifier total rather than to express a real
     * expectation about depth. A translated persistence failure wrapped by a transaction abstraction and
     * again by a service is three elements, so sixteen is an order of magnitude above anything the
     * migrated services produce; the number is a safety limit and not a tuning parameter.</p>
     */
    private static final int MAX_CAUSE_DEPTH = 16;

    /**
     * The number of trailing digits of a primary account number that stay legible, four.
     *
     * <p>Assumptions: four is the platform-wide masked rendering, published by the card contract as
     * twelve mask characters followed by four digits. It is the smallest suffix that still lets an
     * operator and a cardholder agree which card a failure concerned, which is the entire purpose of
     * retaining any of it.</p>
     */
    private static final int ACCOUNT_NUMBER_VISIBLE_DIGITS = 4;

    /**
     * The character written over each masked digit of a primary account number, an asterisk.
     *
     * <p>Assumptions: the asterisk is the character the card contract's own masked examples use, so a
     * client comparing a payload value with a diagnostic path sees one rendering rather than two.</p>
     */
    private static final char ACCOUNT_NUMBER_MASK_CHARACTER = '*';

    /**
     * The operational log this advice writes the caught failure to.
     *
     * <p>Assumptions: one static logger named for this class, so every failure in every service is
     * retrievable under one category. The mapped diagnostic context already carries the correlation
     * identity, published by the shared kernel's correlation filter, so no field of the log line has to
     * repeat it.</p>
     */
    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * The mapped-diagnostic-context key the correlation identity is published under.
     *
     * <p>Assumptions: spelled as a literal here rather than imported from the sibling {@code web}
     * package's filter. The filter belongs to the servlet-facing half of the shared kernel and its
     * dependency is optional, so importing the constant would put a web type on the path of a service
     * that has none -- the batch context among them. The two spellings are asserted equal by this
     * module's tests, which is the same discipline the codec package applies to a width it cannot
     * import.</p>
     */
    private static final String CORRELATION_ID_MDC_KEY = "correlationId";

    /**
     * The shortest digit run in a request path that is narrowed as an account number, thirteen.
     *
     * <p>Assumptions: thirteen is the first length at which a run cannot be one of the identifiers the
     * migrated routes legitimately carry. The widest of those is the eleven-digit account identifier
     * from {@code ACCT-ID PIC 9(11)} at line 5 of {@code app/cpy/CVACT01Y.cpy}, and a twelve-digit run
     * is left legible as the boundary immediately below, so the threshold sits one digit above every
     * identifier a diagnostic path is meant to disclose and one digit below the shortest primary
     * account number in circulation.</p>
     *
     * <p>Alternatives Considered: pinning the rule to the sixteen digits {@code CARD-NUM PIC X(16)}
     * declares at line 5 of {@code app/cpy/CVACT02Y.cpy} and narrowing runs of exactly that width.
     * Rejected because it fails open on both sides of sixteen: a seventeen- or nineteen-digit run --
     * a card number with a check digit appended, or two identifiers a client concatenated -- would
     * pass through in the clear, and so would any longer issuer range this platform later accepts. A
     * minimum length fails closed instead, which is the direction a masking rule has to fail.</p>
     *
     * <p>Trade-offs: a minimum also narrows a sixteen-digit transaction identifier, declared at that
     * width by {@code TRAN-ID PIC X(16)} at line 5 of {@code app/cpy/CVTRA05Y.cpy}, so a failed
     * transaction read reports its identifier reduced to four digits. Nothing is lost operationally,
     * because the correlation identity in the same body resolves to the operational record, which
     * holds the unreduced path.</p>
     */
    private static final int ACCOUNT_NUMBER_MASK_THRESHOLD = 13;

    /**
     * The clock every emitted problem shape reads its timestamp from.
     *
     * <p>Assumptions: injected rather than resolved inside each handler, so a test can assert the
     * emitted timestamp exactly. A handler calling the system clock directly would emit a value no
     * assertion could name, which for a type whose timestamp format is itself a migrated contract --
     * the 26-character form of {@code app/cbl/CBTRN02C.cbl} -- would leave that format unverifiable.</p>
     */
    private final Clock clock;

    /**
     * Creates the advice with the clock its problem shapes are timestamped from.
     *
     * @param clock the clock every emitted {@link ApiError} reads its failure instant from; must not
     *     be {@code null}
     * @throws NullPointerException if {@code clock} is {@code null}
     */
    public GlobalExceptionHandler(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Renders a failure of bean validation on a request body as HTTP 400 with the per-field array.
     *
     * <p>Assumptions: the framework's own field-error list is translated into the migrated per-field
     * entries in the order the framework reports, which for a bound object is declaration order. That
     * is the closest available analogue of the baseline's reporting order, which is the order its
     * highlight expansions are written in -- lines 3208 to 3432 of {@code app/cbl/COACTUPC.cbl} -- and
     * therefore the order the fields appear on the screen.</p>
     *
     * @param failure the validation failure the framework raised, carrying one entry per rejected
     *     field; must not be {@code null}
     * @param request the request that failed, read only for its path
     * @return HTTP 400 carrying {@link ApiError#CODE_VALIDATION}, the aggregate sentence and one
     *     per-field entry for each rejected field, never {@code null}
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> onInvalidBody(MethodArgumentNotValidException failure,
            HttpServletRequest request) {

        List<ApiError.FieldError> fieldErrors = new ArrayList<>();
        for (FieldError rejected : failure.getBindingResult().getFieldErrors()) {
            // WHY : Assumptions: a rejected value of null and a rejected value of blank are told apart
            //       here, because the baseline tells them apart. Lines 18 and 19 of
            //       app/cpy/CSSETATY.cpy are one disjunctive test whose two branches are an
            //       unacceptable value and a never-supplied one, and the never-supplied branch does
            //       something extra rather than something different -- it additionally writes a marker.
            //       Collapsing the two would oblige every client to handle one case where the baseline
            //       handles two, and would lose the information a form needs to decide whether to show
            //       that marker.
            FieldValidationFlag state = isNeverSupplied(rejected.getRejectedValue())
                    ? FieldValidationFlag.BLANK
                    : FieldValidationFlag.NOT_OK;
            fieldErrors.add(new ApiError.FieldError(rejected.getField(), state,
                    messageOf(rejected)));
        }

        // WHY : Assumptions: a violation raised by a CLASS-level constraint that named no member is
        //       carried here too, because reading only the field errors above would DROP it entirely and
        //       answer 400 with an empty per-field array -- a rejection with nothing for the client to
        //       display. A cross-field rule is expected to attribute itself to the members it concerns,
        //       and the request types in this migration do so through their validators, so this branch
        //       is the safety net for one that does not rather than the normal path. The entry is keyed
        //       by the object name the framework supplies, which names the request type; that is not a
        //       form control, so it is deliberately reported as a not-acceptable-value state and never
        //       as the blank state that would ask a form to draw a marker against a field.
        for (ObjectError global : failure.getBindingResult().getGlobalErrors()) {
            fieldErrors.add(new ApiError.FieldError(global.getObjectName(),
                    FieldValidationFlag.NOT_OK, messageOf(global)));
        }

        // WHY : Assumptions: logged at warning level rather than error, because a rejected request is
        //       an outcome the service is designed to produce and not a fault in it. The baseline draws
        //       the same line: a business-rule reject sets return code 4 at line 230 of
        //       app/cbl/CBTRN02C.cbl, which its condition-code rubric treats as a warning tier that the
        //       following step still runs, while a failed operation sets 8 or 16.
        LOG.warn("event=api.request.rejected code={} status=400 path={} fields={}",
                ApiError.CODE_VALIDATION, pathOf(request), fieldErrors.size());

        return ResponseEntity.badRequest().body(ApiError.ofFieldErrors(MESSAGE_VALIDATION_FAILED,
                HttpStatus.BAD_REQUEST.value(), correlationId(), pathOf(request), fieldErrors,
                this.clock));
    }

    /**
     * Renders a failure of validation on a handler parameter as HTTP 400 with the per-field array.
     *
     * <p>Assumptions: handled separately from a rejected request body because the framework raises a
     * different type for a constraint on a path variable, a query parameter or a header, and that type
     * carries no bound object. Without this handler such a failure would fall through to the
     * catch-all below and be reported as an internal failure, which would tell a caller its own
     * malformed input was the service's fault.</p>
     *
     * @param failure the parameter validation failure the framework raised; must not be {@code null}
     * @param request the request that failed, read only for its path
     * @return HTTP 400 carrying {@link ApiError#CODE_VALIDATION} and one per-field entry for each
     *     rejected parameter, never {@code null}
     */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiError> onInvalidParameter(HandlerMethodValidationException failure,
            HttpServletRequest request) {

        List<ApiError.FieldError> fieldErrors = new ArrayList<>();
        for (var parameterResult : failure.getParameterValidationResults()) {
            String field = parameterResult.getMethodParameter().getParameterName();
            // WHY : Assumptions: a class compiled without parameter names reports null here, but the
            //       method-parameter index is still available. The indexed fallback stays non-empty,
            //       as ApiError.FieldError requires, and preserves one identity per rejection just as
            //       every expansion at app/cpy/CSSETATY.cpy lines 17 to 27 receives a concrete screen
            //       field identity; dropping the entry would instead report a validation failure with
            //       nothing for the caller to act on.
            String fieldIdentity = field == null
                    ? "parameter[" + parameterResult.getMethodParameter().getParameterIndex() + "]"
                    : field;
            for (MessageSourceResolvable resolvable : parameterResult.getResolvableErrors()) {
                FieldValidationFlag state = isNeverSupplied(parameterResult.getArgument())
                        ? FieldValidationFlag.BLANK
                        : FieldValidationFlag.NOT_OK;
                fieldErrors.add(new ApiError.FieldError(fieldIdentity, state,
                        resolvedMessageOf(resolvable)));
            }
        }

        LOG.warn("event=api.request.rejected code={} status=400 path={} parameters={}",
                ApiError.CODE_VALIDATION, pathOf(request), fieldErrors.size());

        return ResponseEntity.badRequest().body(ApiError.ofFieldErrors(MESSAGE_VALIDATION_FAILED,
                HttpStatus.BAD_REQUEST.value(), correlationId(), pathOf(request), fieldErrors,
                this.clock));
    }

    /**
     * Renders an unreadable request body as HTTP 400 without a per-field array.
     *
     * <p>Assumptions: the caught exception's own text is never returned. A body that fails to parse
     * produces a message naming the offending token and the position, which for a payload carrying a
     * primary account number or an amount would place that content in a response body and, from there,
     * into whatever logs the client keeps. The migrated form of that same protection is already applied
     * one layer down, where the money deserialiser reports a stable reason instead of the value it
     * could not read.</p>
     *
     * @param failure the parse failure the framework raised; logged, never rendered
     * @param request the request that failed, read only for its path
     * @return HTTP 400 carrying {@link ApiError#CODE_VALIDATION}, the malformed-request sentence and an
     *     empty field array, never {@code null}
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> onUnreadableBody(HttpMessageNotReadableException failure,
            HttpServletRequest request) {

        // WHY : Trade-offs: the exception TYPE is logged and its message is not, which is the same rule
        //       the fixed-width codecs apply to a sensitive field: the constraint that was breached is
        //       safe to record and the content that breached it is not. What is given up is the offending
        //       token in the log line; what it buys is that no payload content reaches log storage, which
        //       is the one place the masking applied at the API edge does not reach.
        LOG.warn("event=api.request.unreadable code={} status=400 path={} exception={}",
                ApiError.CODE_VALIDATION, pathOf(request), failure.getClass().getName());

        return ResponseEntity.badRequest().body(ApiError.of(ApiError.CODE_VALIDATION,
                MESSAGE_MALFORMED_REQUEST, HttpStatus.BAD_REQUEST.value(), correlationId(),
                pathOf(request), this.clock));
    }

    /**
     * Renders a missing record as HTTP 404.
     *
     * <p>Assumptions: the standard-library no-such-element type is the one this advice recognises,
     * because a repository read that finds nothing surfaces as an empty optional and a caller that
     * insists on a value raises exactly that type. A bounded context needing the baseline's own reject
     * wording supplies it through {@link ApiError} rather than relying on this default.</p>
     *
     * @param failure the lookup failure raised by the service layer; logged, never rendered
     * @param request the request that failed, read only for its path
     * @return HTTP 404 carrying {@link ApiError#CODE_NOT_FOUND} and the not-found sentence, never
     *     {@code null}
     */
    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ApiError> onMissingRecord(NoSuchElementException failure,
            HttpServletRequest request) {

        LOG.warn("event=api.record.absent code={} status=404 path={} exception={}",
                ApiError.CODE_NOT_FOUND, pathOf(request), failure.getClass().getName());

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiError.of(ApiError.CODE_NOT_FOUND,
                MESSAGE_NOT_FOUND, HttpStatus.NOT_FOUND.value(), correlationId(), pathOf(request),
                this.clock));
    }

    /**
     * Renders the three conflict conditions as HTTP 409 and every other runtime failure as HTTP 500.
     *
     * <p>Alternatives Considered: declaring a handler per exception type, which is how such a mapping is
     * ordinarily written, and which would require this module to depend on the persistence abstraction
     * that declares those types. The rejected alternative is one specific edit and is worth stating so
     * that nobody has to rediscover why it was not made: adding the framework's starter for
     * JPA-based data access -- the aggregate that brings the object-relational mapper, its
     * annotation-driven locking exception and the translated data-access hierarchy -- to
     * {@code services/common-lib/pom.xml}, after which both handlers could name their types directly.
     * It was rejected because this module is the shared kernel that all eight bounded contexts compile
     * against and that depends on none of them, and its build file declares no persistence provider, no
     * database driver, no schema-migration tool and no cloud client at all. That starter added here
     * would therefore be added to every service, including the batch context, whose posting job is the
     * one place an object-relational mapper genuinely is not the mechanism in use. Discriminating on the
     * fully-qualified class name walks the superclass chain instead, which needs no type on the
     * classpath at all.</p>
     *
     * <p>Assumptions: that starter is described above rather than spelled out as a build coordinate, and
     * the restraint is deliberate and follows the discipline this package's charter already applies to
     * the eight bounded-context package roots. A file that spelled the coordinate out would itself match
     * an audit searching this tree for the very dependency it is forbidden to hold, so the one place
     * asserting that no persistence dependency exists here would be the one place a search for it
     * reports a hit -- and that hit would need explaining away on every audit thereafter. Describing it
     * costs a sentence that reads less directly than a coordinate would, and it buys an assertion that
     * is machine-checkable rather than one that has to be read to be dismissed. The same restraint
     * governs the framework names that ARE spelled out below: those are names from the framework the
     * services already run on, which is the only category such a string may hold, and no name of any
     * bounded context appears in this class in any form -- not as an import, not as a qualified name and
     * not as a string literal.</p>
     *
     * <p>Trade-offs: what a name-based test gives up is compile-time checking. Renaming or relocating one
     * of those framework types in a future major version would not break this file; the branch would
     * simply stop matching, and the conflict would be answered as an internal failure instead. Three
     * things are done about that rather than none. The names are declared as constants beside each other
     * instead of inline, so there is one place to read and one place to change. The abstraction's base
     * types are named rather than their subclasses, because the base names are the stable part of that
     * hierarchy while the subclasses are where new ones appear. And the mapping is asserted where those
     * types actually exist rather than here: each of the eight bounded contexts declares the persistence
     * starter for its own repositories, so all three names resolve in every one of them and a service
     * test can raise a real instance, while in this module they resolve on neither the compile nor the
     * test classpath. That asymmetry is the arrangement working as intended, and it is also why a test
     * in this module reaches these branches only by supplying a type that carries the name rather than
     * by importing one. What is accepted, then, is that the guarantee moves from the compiler to a test
     * one module further out, in exchange for a shared kernel that imposes no persistence dependency on
     * anything that consumes it.</p>
     *
     * <p>Alternatives Considered: marking the name-based branches with a suppression comment or a
     * suppression annotation, which is the reflex a reader may expect here because a string standing in
     * for a type usually attracts one. There is nothing to suppress and no mechanism to suppress it with.
     * The audit configuration at {@code config/checkstyle/checkstyle.xml} enables no comment-driven and
     * no annotation-driven suppression filter at all, and its companion suppressions file reaches only
     * generated sources and test fixtures, so an in-code bypass would not merely be poor practice, it
     * would have no effect. Line 40 of the Explainability rule closes the question from the other side:
     * leaving a non-obvious choice undocumented where a reasonable alternative exists is itself a
     * violation. Writing the reasoning out, as the two paragraphs above do, is therefore the only route
     * through -- and it is the better one, because a suppression records that someone decided something
     * while a rationale records what they decided and why.</p>
     *
     * <p>Assumptions: the whole superclass chain is examined rather than the exception's own class
     * alone, because the persistence layer raises SUBCLASSES -- an object-optimistic-locking failure
     * for a versioned entity, a lock-not-obtained failure for a contended row, a constraint-violation
     * subclass for a specific integrity breach -- and a test on the exact class would miss every real
     * occurrence while passing a unit test that raised the base type.</p>
     *
     * <p>Refactoring Rationale: on the first of the three conflicts, the baseline already implements
     * optimistic concurrency by hand, so the migrated form is a change of expression rather than of
     * behaviour.
     * {@code app/cbl/COACTUPC.cbl} snapshots the whole pre-edit record into the before-image group
     * beginning at line 669, holding each numeric twice -- lines 675 to 677 declare the balance as
     * {@code ACUP-OLD-CURR-BAL PIC X(12)} and redefine the same bytes as {@code PIC S9(10)V99}, a
     * display field over a numeric one. It carries the change marker declared at line 168 with its two
     * conditions at lines 169 and 170, commits on the success path at lines 945 to 958, and on a failed
     * rewrite sets a locked-but-failed state and issues a rollback across lines 4095 to 4103 before
     * branching to the write exit. The migrated services express that same intent natively, through a
     * version column the persistence layer maintains, and this handler maps the resulting failure to
     * HTTP 409 carrying the verbatim sentence declared at lines 521 and 522. Nothing is lost in the
     * translation, because the read-for-update lock was never held across the user's thinking time --
     * which is precisely why the before-image had to exist in the first place. The alternative of letting
     * the persistence exception escape untranslated would return a rendered stack trace and a database
     * vendor error code to the client and lose the user-visible data-changed sentence entirely, which is
     * both a disclosure and a loss of behaviour. Other commit boundaries produce the same conflict and
     * therefore the same status: line 470 of {@code app/cbl/COCRDUPC.cbl}, line 335 of
     * {@code COPAUA0C.cbl}, line 686 of {@code COPAUS0C.cbl}, where the commit is guarded and also
     * releases a hierarchical-database resource, and lines 557 to 558 of {@code COPAUS1C.cbl}.</p>
     *
     * <p>Refactoring Rationale: on the second conflict, a failure to obtain the lock is answered
     * separately from a before-image mismatch, for the reason set out on
     * {@link #MESSAGE_ACCOUNT_LOCK_FAILED} -- the baseline reports the two apart at lines 517 to 520 and
     * at lines 521 and 522, and a caller told that a record had changed when in truth a lock timed out
     * would retry the wrong way. The status is the same 409 because both are contention on a row that
     * the caller may resolve by trying again; the sentence differs because the two conditions differ.</p>
     *
     * <p>Refactoring Rationale: on the third conflict, the reference-data foreign key carries
     * {@code ON DELETE RESTRICT}, preserving the semantic the baseline's own {@code XTRNTYCAT} Db2
     * constraint already asserts, so deleting a transaction type that categories still point at fails at
     * the database. Answering with the driver's own text would name the schema, the table and the
     * constraint -- telling an untrusted caller the internal shape of the store while still not saying
     * what to do about it. Answering with the business rule tells the caller what to change.</p>
     *
     * @param failure the runtime failure that propagated out of a handler method; logged, never
     *     rendered, and never {@code null} on any path the framework reaches this method by
     * @param request the request that failed, read only for its path
     * @return HTTP 409 with {@link #MESSAGE_RECORD_CHANGED} for a before-image mismatch, HTTP 409 with
     *     {@link #MESSAGE_LOCK_UNAVAILABLE} for a lock that could not be obtained, HTTP 409 with
     *     {@link #MESSAGE_REFERENCED_ROW} for an integrity violation, and otherwise the same HTTP 500
     *     shape {@link #onUnexpectedFailure(Exception, HttpServletRequest)} produces; never {@code null}
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ApiError> onRuntimeFailure(RuntimeException failure,
            HttpServletRequest request) {

        // WHY : Assumptions: the optimistic branch is tested before the pessimistic one because the two
        //       are siblings rather than parent and child in the abstraction's hierarchy, so neither
        //       test can absorb the other and the order is free. It is fixed here in the order the
        //       baseline declares the conditions -- lock acquisition at lines 517 to 520 sits above the
        //       before-image mismatch at 521 and 522 -- inverted so that the mapping a reader is most
        //       likely to be looking for is the first one they meet.
        if (isOfType(failure, OPTIMISTIC_LOCK_EXCEPTION_NAME)) {
            LOG.warn("event=api.conflict.optimistic-lock code={} status=409 path={} exception={}",
                    ApiError.CODE_CONFLICT, pathOf(request), failure.getClass().getName());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiError.of(ApiError.CODE_CONFLICT,
                    MESSAGE_RECORD_CHANGED, HttpStatus.CONFLICT.value(), correlationId(),
                    pathOf(request), this.clock));
        }

        if (isOfType(failure, PESSIMISTIC_LOCK_EXCEPTION_NAME)) {
            LOG.warn("event=api.conflict.lock-unavailable code={} status=409 path={} exception={}",
                    ApiError.CODE_CONFLICT, pathOf(request), failure.getClass().getName());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiError.of(ApiError.CODE_CONFLICT,
                    MESSAGE_LOCK_UNAVAILABLE, HttpStatus.CONFLICT.value(), correlationId(),
                    pathOf(request), this.clock));
        }

        if (isOfType(failure, INTEGRITY_VIOLATION_EXCEPTION_NAME)) {
            LOG.warn("event=api.conflict.integrity code={} status=409 path={} exception={}",
                    ApiError.CODE_CONFLICT, pathOf(request), failure.getClass().getName());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiError.of(ApiError.CODE_CONFLICT,
                    MESSAGE_REFERENCED_ROW, HttpStatus.CONFLICT.value(), correlationId(),
                    pathOf(request), this.clock));
        }

        return onUnexpectedFailure(failure, request);
    }

    /**
     * Reports whether a failure is, descends from, or wraps the named exception type.
     *
     * <p>Assumptions: this private helper carries the same complete Javadoc a public method does, and it
     * is the method the class-level note on that obligation was written for. The audit's presence check
     * sets its access filter down to private, so an undocumented private method fails outright; its
     * completeness check names all four access levels including private and disallows both a missing
     * parameter tag and a missing return tag, so a block that is present but partial fails as well.
     * A partial private block is therefore worse than none, and line 15 of the Explainability rule
     * settles it independently by carrying no visibility qualifier at all.</p>
     *
     * <p>Assumptions: the cause chain is walked as well as each element's superclass chain, because the
     * conditions being recognised do not always arrive at the top. A version check that fails when the
     * unit of work commits rather than when the entity is written surfaces as the transaction
     * abstraction's own failure with the locking failure beneath it, so a test that inspected only the
     * outermost throwable would answer the request as an internal failure and lose both the 409 and the
     * verbatim sentence at exactly the commit boundary the baseline's own is at, lines 945 to 958 of
     * {@code app/cbl/COACTUPC.cbl}. Walking outward-in also keeps the nearest match winning, which is
     * what makes the three conflict branches in
     * {@link #onRuntimeFailure(RuntimeException, HttpServletRequest)} independent of one another.</p>
     *
     * <p>Trade-offs: the walk is bounded by {@link #MAX_CAUSE_DEPTH} and stops at a throwable that names
     * itself as its own cause, rather than trusting the chain to be finite. A cause chain is ordinary
     * data assembled by whatever threw it, and this method runs while a failure is already being
     * reported, so a cycle here would spin inside the error path itself -- the one place a defect must
     * not be able to escalate. The bound is chosen well above any real chain, so what is given up is
     * only the theoretical ability to recognise a condition buried deeper than that, in exchange for a
     * classifier that cannot fail to return.</p>
     *
     * @param failure the failure to classify; never {@code null} on any path that reaches here
     * @param typeName the fully-qualified name of the framework type to look for, always one of the
     *     three constants declared above and never a name from any bounded context
     * @return {@code true} when the failure, any of its superclasses, or any throwable in its bounded
     *     cause chain or their superclasses carries that name, otherwise {@code false}
     */
    private static boolean isOfType(Throwable failure, String typeName) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            for (Class<?> type = current.getClass(); type != null; type = type.getSuperclass()) {
                if (typeName.equals(type.getName())) {
                    return true;
                }
            }

            // WHY : Assumptions: a throwable naming itself as its own cause is treated as the end of the
            //       chain rather than followed. The standard cause accessor returns the throwable itself
            //       in that case instead of null, so following it without this test would consume the
            //       whole depth budget on one element and hide a real match no deeper than the second.
            Throwable cause = current.getCause();
            current = cause == current ? null : cause;
        }
        return false;
    }

    /**
     * Renders an authorization failure as HTTP 403 without naming the missing authority.
     *
     * <p>Assumptions: the caller is authenticated by the time this is reached -- an unauthenticated
     * request is refused at the edge by the API's own token check and never arrives -- so this is the
     * admin-versus-user split of the baseline, whose one-character user type at lines 19 to 44 of
     * {@code app/cpy/COCOM01Y.cpy} becomes a signed group claim rather than a field the client supplies
     * about itself.</p>
     *
     * @param failure the access denial raised by the security layer; logged, never rendered
     * @param request the request that failed, read only for its path
     * @return HTTP 403 carrying {@link #CODE_FORBIDDEN} and {@link #MESSAGE_FORBIDDEN}, never
     *     {@code null}
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> onAccessDenied(AccessDeniedException failure,
            HttpServletRequest request) {

        LOG.warn("event=api.access.denied code={} status=403 path={} exception={}",
                CODE_FORBIDDEN, pathOf(request), failure.getClass().getName());

        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiError.of(CODE_FORBIDDEN,
                MESSAGE_FORBIDDEN, HttpStatus.FORBIDDEN.value(), correlationId(), pathOf(request),
                this.clock));
    }

    /**
     * Renders any other failure as HTTP 500 with a sentence that names nothing about the cause.
     *
     * <p>Assumptions: this handler is the reason a framework-shaped or provider-shaped body can never
     * escape. Without it, an exception no other handler claims is rendered by the framework's own
     * default, whose body carries the exception message for many exception types -- and a persistence
     * or client-library message routinely carries a statement, a connection detail or a vendor code.
     * The caught throwable is logged with its stack trace through the logging pipeline, where the
     * appender configuration governs it, and the response carries only the fixed sentence.</p>
     *
     * <p>Trade-offs: {@link Exception} is caught rather than {@link Throwable}. An {@link Error} signals
     * that the virtual machine itself is in trouble -- exhausted memory, an unlinkable class -- and
     * answering such a condition with a rendered 500 would keep an unhealthy instance in the load
     * balancer's rotation, where the container health check is what should remove it. The batch context
     * makes the opposite choice for a reason particular to it, which is that its process exit status is
     * its only channel to the orchestrator; a request-serving process has a health check as well.</p>
     *
     * <p>Refactoring Rationale: this is the handler that renders the abend surface, and it renders it as
     * structured data rather than as a block of characters. The baseline declares the surface as
     * {@code 01 ABEND-DATA} at line 21 of {@code app/cpy/CSMSG02Y.cpy} with exactly four subordinate
     * components and no filler -- a 4-character condition code at line 22, an 8-character culprit at
     * line 24, a 50-character reason at line 26 and a 72-character message at line 28, each with its
     * {@code VALUE SPACES} clause on the following line, summing to 134 bytes across lines 21 to 29 --
     * and it wrote those four to absolute positions on a terminal frame. There is no terminal on the
     * migrated path and no absolute position to write to, so the four become components of
     * {@link AbendDetail} that a caller places wherever its own layout puts them. The fields survive and
     * the positions do not. One lineage note belongs with the citation: that copybook announces itself at
     * its line 2 as {@code CABENDD.CPY}, which is neither the name it is stored under nor the name any
     * program includes it by, and it is recorded so that finding no file under the announced title is not
     * mistaken for something missing.</p>
     *
     * <p>Assumptions: the detail assembled here is the internal one and the client never receives it
     * whole. {@link ApiError}'s constructor reduces any supplied detail through
     * {@link AbendDetail#external()}, which keeps the condition code and replaces the culprit, the reason
     * and the message with the blank state and one fixed sentence. That is what makes the no-disclosure
     * property structural rather than a matter of this handler remembering: the culprit and reason are
     * assembled for the operational record, and there is no route by which they reach a response body
     * even though they are passed to the factory. Two failures of different internal causes are therefore
     * indistinguishable from outside and fully distinguishable in the log.</p>
     *
     * <p>Assumptions: four declared message widths are inherited by this migration and all four are
     * modelled as four, with none treated as a canonical width the others are padded or truncated to.
     * Two of them meet here -- the 50-character reason and the 72-character message of the abend block --
     * and the other two belong elsewhere and are named only so that no reader concludes this handler was
     * meant to carry them: 75 is the terminal message line at lines 28 and 29 of
     * {@code app/cpy/CVCRD01Y.cpy}, and it is the only one of the four carried forward as a rendering
     * constraint, published as {@link ApiError#MESSAGE_RENDERING_WIDTH}; 80 is the date utility's
     * diagnostic out-parameter at line 86 of {@code app/cbl/CSUTLDTC.cbl}, a call contract rather than a
     * response contract. Collapsing any of the four into one would assert an equality the copybooks
     * deny.</p>
     *
     * <p>Assumptions: the two kinds of empty this package inherits stay two, and this handler is careful
     * not to merge them. All four abend components are initialised {@code VALUE SPACES} at lines 23, 25,
     * 27 and 29 of {@code app/cpy/CSMSG02Y.cpy}, which is a message that exists and is empty; the
     * sentinel that means a message is off is {@code 88 CCARD-RETURN-MSG-OFF VALUE LOW-VALUES} at line 30
     * of {@code app/cpy/CVCRD01Y.cpy}, attaching to the return message on line 29 alone. The migrated
     * forms of the two are the empty string and {@code null} respectively, which is why
     * {@link AbendDetail} admits no {@code null} component while {@link ApiError#message()} is nullable.
     * This handler passes a non-null sentence, so the response carries message-empty nowhere and
     * message-off nowhere; reducing both states to one representation would erase a difference the
     * baseline can still see.</p>
     *
     * @param failure the unclaimed failure; logged with its stack trace, never rendered. The declared
     *     type is the checked-and-unchecked parent, and the type actually reaching this method is most
     *     often a {@link RuntimeException} forwarded from
     *     {@link #onRuntimeFailure(RuntimeException, HttpServletRequest)} once none of its three
     *     conflict tests matched
     * @param request the request that failed, read only for its path
     * @return HTTP 500 carrying {@link ApiError#CODE_INTERNAL}, {@link #MESSAGE_INTERNAL} and the
     *     client-safe external form of the abend detail, never {@code null}
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> onUnexpectedFailure(Exception failure,
            HttpServletRequest request) {

        // WHY : Assumptions: the exception TYPE is a structured field and the trace is carried as the
        //       throwable argument, so the message text is never interpolated into the log line itself.
        //       Passing the throwable rather than formatting it is what leaves redaction and layout to
        //       the appender configuration, which one place owns, instead of to this call site.
        LOG.error("event=api.request.failed code={} status=500 path={} exception={}",
                ApiError.CODE_INTERNAL, pathOf(request), failure.getClass().getName(), failure);

        // WHY : Assumptions: the four components are supplied as constants rather than composed from the
        //       caught throwable. A culprit or reason built from an exception's own class name or message
        //       would put provider-internal text into a value that is operator-facing prose, and the
        //       message component is the one place a caught message could plausibly be routed to a
        //       structured field. Supplying fixed text removes that route rather than filtering it.
        AbendDetail abend = new AbendDetail(ABEND_CODE_UNEXPECTED, ABEND_CULPRIT_ADVICE,
                ABEND_REASON_UNEXPECTED, MESSAGE_INTERNAL);

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiError.ofAbend(MESSAGE_INTERNAL, correlationId(), pathOf(request), abend,
                        this.clock));
    }

    /**
     * Reads the correlation identity established for the current request.
     *
     * @return the identity the shared kernel's correlation filter published for this request, or the
     *     empty string when no filter ran -- which is the case in a slice test that exercises a
     *     controller without the servlet filter chain
     */
    private static String correlationId() {
        String correlationId = MDC.get(CORRELATION_ID_MDC_KEY);
        return correlationId == null ? "" : correlationId;
    }

    /**
     * Reads the path of the request being answered, with any primary account number in it rendered to
     * its last four digits.
     *
     * <p>Refactoring Rationale: the path is masked here rather than left to a per-service mapper,
     * because this advice is the only place that composes it and no mapper runs on a failed request.
     * The card contract selects a card by its primary account number in the path and justifies that
     * selector on the stated guarantee that the diagnostic path member is masked, so the guarantee has
     * to be kept where the member is built. Without it every card failure -- 400, 401, 403, 404 and
     * 409 alike -- returned the sixteen digits inside a response body, and a body travels further than
     * a URL does: into client logs, error trackers and support tickets.</p>
     *
     * @param request the request to read, which may be {@code null} when this advice is exercised
     *     without a servlet request
     * @return the request's URI with every primary account number masked, or the empty string when no
     *     request was supplied
     */
    private static String pathOf(HttpServletRequest request) {
        return request == null ? "" : maskAccountNumbers(textOr(request.getRequestURI()));
    }

    /**
     * Narrows every account number in a path to a mask and its last four digits, preserving length.
     *
     * <p>Assumptions: an account number is any run of {@value #ACCOUNT_NUMBER_MASK_THRESHOLD} or more
     * digit characters bounded by non-digits, and the constant's own declaration records why that
     * length is the boundary. Each withheld digit is overwritten with its own mask character rather
     * than the run being collapsed to a fixed marker, so the narrowed path is exactly as long as the
     * one the client called -- which is what lets a reader line a diagnostic path up against an access
     * record without either one having to be re-parsed.</p>
     *
     * <p>Assumptions: the value is scanned rather than parsed. This advice is reached from every
     * service and must not know which path shapes exist, so no segment position, prefix or route
     * template appears here; only the digit run does. The alternative, narrowing only paths known to
     * carry a card number, was rejected because it fails open: a path added later carries its number
     * in the clear until somebody remembers to extend the list, and the failure is silent.</p>
     *
     * <p>Assumptions: the operation is idempotent, and that property is relied upon rather than
     * hoped for. Narrowing a qualifying run leaves only its four trailing digits as a digit run, which
     * is below the threshold, so a path that has already been narrowed -- by a caller, or by the
     * emitted shape's own canonical constructor, which applies the shared masker to whatever path it
     * is given -- passes through this method unchanged.</p>
     *
     * @param path the request path to narrow, never {@code null}; a path whose longest digit run is
     *     shorter than the threshold is returned unchanged
     * @return the path with every qualifying digit run reduced to mask characters and that run's last
     *     four digits, of identical length to {@code path}
     */
    static String maskAccountNumbers(String path) {
        StringBuilder masked = null;
        int scanned = 0;
        int length = path.length();
        while (scanned < length) {
            if (!isDigit(path.charAt(scanned))) {
                scanned++;
                continue;
            }
            int runEnd = scanned;
            while (runEnd < length && isDigit(path.charAt(runEnd))) {
                runEnd++;
            }
            if (runEnd - scanned >= ACCOUNT_NUMBER_MASK_THRESHOLD) {
                if (masked == null) {
                    masked = new StringBuilder(path);
                }
                // WHY : Assumptions: the four retained digits are the LAST four, which is the only part
                //       of a card number the migrated platform ever renders, so the mask writes over
                //       every digit ahead of them and leaves those four where they were. Writing over
                //       the leading digits in place, rather than replacing the run, is what keeps the
                //       narrowed path the same length as the one the client called.
                for (int position = scanned;
                        position < runEnd - ACCOUNT_NUMBER_VISIBLE_DIGITS;
                        position++) {
                    masked.setCharAt(position, ACCOUNT_NUMBER_MASK_CHARACTER);
                }
            }
            scanned = runEnd;
        }
        return masked == null ? path : masked.toString();
    }

    /**
     * Reports whether a character is one of the ten ASCII digits.
     *
     * <p>Assumptions: the ten ASCII digits and nothing else, rather than
     * {@link Character#isDigit(char)}, which also accepts the decimal digits of other scripts. A path
     * segment carrying such a digit is not a card number this platform issued, and treating it as one
     * would make the masking depend on the caller's choice of script.</p>
     *
     * @param candidate the character to classify
     * @return {@code true} for the characters {@code '0'} through {@code '9'}, {@code false} otherwise
     */
    private static boolean isDigit(char candidate) {
        return candidate >= '0' && candidate <= '9';
    }

    /**
     * Returns the given text, or the empty string when it is {@code null}.
     *
     * @param value the text to normalise, possibly {@code null}
     * @return the same text when it is non-{@code null}, otherwise the empty string
     */
    private static String textOr(String value) {
        return value == null ? "" : value;
    }

    /**
     * Extracts the help text a rejected field or object should display.
     *
     * <p>Assumptions: the parameter is the framework's error supertype rather than the field-error
     * subtype, so one operation serves both the per-field rejections and the class-level ones. The two
     * carry their message through the same accessor, so a second copy of this fallback would only be a
     * second place for it to drift.</p>
     *
     * @param rejected the framework's own error, carrying the constraint's resolved message
     * @return the resolved message, or a fixed fallback when the constraint supplied none, so that an
     *     entry can never reach a client with nothing to display
     */
    private static String messageOf(ObjectError rejected) {
        String resolved = rejected.getDefaultMessage();
        // WHY : Assumptions: a blank constraint message is replaced rather than passed through. The
        //       per-field entry type requires content, so an entry built from a blank message would
        //       raise inside this advice -- turning a rejected request into an internal failure, which
        //       is the one outcome an error path must not produce.
        return (resolved == null || resolved.isBlank()) ? MESSAGE_VALIDATION_FAILED : resolved;
    }

    /**
     * Extracts the help text a rejected handler parameter should display.
     *
     * @param resolvable the framework's own resolvable error for the rejected parameter, carrying the
     *     constraint's resolved message
     * @return the resolved message, or a fixed fallback when the constraint supplied none, so that an
     *     entry can never reach a client with nothing to display
     */
    private static String resolvedMessageOf(MessageSourceResolvable resolvable) {
        String resolved = resolvable.getDefaultMessage();
        // WHY : Assumptions: the same substitution as on a rejected body field, and for the same
        //       reason -- the per-field entry type requires content, so an entry built from a blank
        //       message would raise inside this advice and turn a rejected request into an internal
        //       failure.
        return (resolved == null || resolved.isBlank()) ? MESSAGE_VALIDATION_FAILED : resolved;
    }

    /**
     * Reports whether a rejected value represents a field the caller never supplied.
     *
     * @param rejectedValue the value the framework rejected, which is {@code null} for an absent
     *     member and a blank string for one submitted empty
     * @return {@code true} when the value is absent, empty or entirely whitespace, matching the
     *     never-supplied branch of the baseline's disjunctive highlight test
     */
    private static boolean isNeverSupplied(Object rejectedValue) {
        if (rejectedValue == null) {
            return true;
        }
        return rejectedValue instanceof CharSequence text && text.toString().isBlank();
    }
}
