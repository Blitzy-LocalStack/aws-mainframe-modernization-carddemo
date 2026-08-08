package com.carddemo.common.error;

import com.carddemo.common.observability.LogSafeText;
import com.carddemo.common.observability.ThrowableDigest;
import com.carddemo.common.validation.FieldValidationFlag;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
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
import org.springframework.validation.method.ParameterErrors;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingMatrixVariableException;
import org.springframework.web.bind.MissingRequestCookieException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingRequestValueException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

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
 * {@link ApiError} defines and never from the text of a caught exception; a reduced description of the
 * caught exception goes to the operational record instead, under the same correlation identity the
 * response carries, so a client can be answered with one short sentence while the failure stays
 * diagnosable. Assumptions: "reduced" is exact rather than a hedge -- every site here logs the
 * exception's TYPE and no site logs its message, the two deliberate exceptions being the
 * repository-authored refusal sentence of {@link ClientInputException}, which is sanitised, and the
 * type-and-frame digest of the generic five-hundred path. A provider's own message reaches neither a
 * response nor a log line. The
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
     * <p>Refactoring Rationale: this value previously read "Could not lock the record for update" and
     * was documented here as the one message in this class that was not a baseline literal. That was a
     * paraphrase, and it was an unnecessary one: the baseline already declares an entity-agnostic form
     * of this sentence, character for character, in two independent programs -- at lines 205 and 206 of
     * {@code app/cbl/COCRDUPC.cbl} and again at lines 181 and 182 of
     * {@code app/app-transaction-type-db2/cbl/COTRTUPC.cbl} -- and neither names the row. Transformation
     * rule T8 carries a user-visible string across verbatim, so the definite article that had been
     * inserted made the emitted body differ from the baseline for no gain, and made every published
     * contract that quoted the baseline wording describe a body this advice would not return. It is now
     * the baseline literal, measured at 32 characters.</p>
     *
     * <p>Trade-offs: the baseline ALSO has two entity-SPECIFIC variants of this sentence, which name the
     * row at 40 and 41 characters, because the program raising one knows which row it was writing. This
     * advice is shared by every bounded context and is reached by propagation, so at the point it
     * renders the response the identity of the row is no longer available to it, and emitting one of the
     * two specific literals would mean naming the wrong row roughly half the time. Both stay published
     * above as {@link #MESSAGE_ACCOUNT_LOCK_FAILED} and {@link #MESSAGE_CUSTOMER_LOCK_FAILED} for the
     * context that does know, which supplies one through {@link ApiError} instead. What is given up is
     * that a caller reading this default is not told which row was contended; what it buys is that the
     * three sentences are all baseline text and none of them is invented.</p>
     */
    public static final String MESSAGE_LOCK_UNAVAILABLE = "Could not lock record for update";

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
     * The verbatim message the baseline displays when a row cannot be deleted because rows point at it.
     *
     * <p>Refactoring Rationale: this value previously read "Cannot delete: other records still refer to
     * this entry", which described the rule in this migration's own words rather than the baseline's.
     * Unlike the lock sentence above, no constraint forced a paraphrase here: the baseline's own wording
     * names no entity, no table and no constraint, so the entity-agnostic sentence this advice needs
     * already existed. It is declared identically in the two programs that own the relationship -- line
     * 1919 of {@code app/app-transaction-type-db2/cbl/COTRTLIC.cbl} and line 1641 of that tree's
     * {@code COTRTUPC.cbl} -- and transformation rule T8 carries such a string across verbatim. It is
     * now that literal, measured at 44 characters.</p>
     *
     * <p>Assumptions: the trailing colon is retained because the baseline retains it, and NOTHING is
     * appended after it. The baseline follows the colon with the database's own diagnostic, which is why
     * the colon is there; appending that diagnostic to a client-facing body would name the schema, the
     * table and the constraint and would disclose an internal detail on a response the caller reads, so
     * the sentence ends at the colon and the diagnostic goes to the operational record instead.</p>
     *
     * <p>Assumptions: the condition itself is unchanged. The reference-data foreign key carries
     * {@code ON DELETE RESTRICT}, preserving the semantic the baseline's own {@code XTRNTYCAT}
     * constraint already asserts, so deleting a transaction type is impossible while categories still
     * reference it.</p>
     */
    public static final String MESSAGE_REFERENCED_ROW =
            "Please delete associated child records first:";

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
     * The field-entry key used when a rejected request identifies no member of its own.
     *
     * <p>Assumptions: a per-field array whose entry names the request rather than a control is the
     * honest shape for a failure that carries no field identity, and it is already the shape this class
     * produces for a class-level constraint violation, which the framework reports keyed by the bound
     * object's name. The key is published as a constant so a client can match on it, and so the two
     * sites that emit an unattributed entry cannot drift into two different spellings of the same
     * idea.</p>
     */
    public static final String FIELD_REQUEST = "request";

    /**
     * The field entry a contention refusal reports the contended row's current version under.
     *
     * <p>Assumptions: the name is the one the entities' optimistic-lock column is mapped to, so a client
     * that reads a version from a representation and sends it back finds the refusal keyed by the same
     * name it used. It is published as a constant rather than written at the one site that emits it,
     * because the published contracts document the key and a document and a literal are two statements
     * of one name.</p>
     *
     * <p>Alternatives Considered: a dedicated response component named for the version instead of a
     * field entry. Rejected because the published contracts seal the problem shape against unknown
     * properties, so a component added for this refusal would have to be admitted on every other
     * response and then documented as absent there.</p>
     */
    public static final String FIELD_VERSION = "version";

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
     * It names the classification and not the cause: the cause reaches the operational record as the
     * reduced representation {@link com.carddemo.common.observability.ThrowableDigest} composes -- its
     * chain of type names and originating frames -- rather than being interpolated into any string this
     * class builds.</p>
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
     * The bean-validation constraint names that assert a value was supplied at all.
     *
     * <p>Assumptions: these three are the complete set of standard presence constraints. Every other
     * standard constraint -- pattern, size, bounds, digits -- describes the SHAPE of a value that was
     * supplied, and a shape edit is meaningless on a value that is not there, which is exactly why the
     * reference programs run their presence edit first and stop.</p>
     *
     * <p>Trade-offs: a custom presence constraint carrying some other name ranks with the shape edits
     * and so could supply the aggregate sentence for a never-supplied value. Accepted because a name
     * list is checkable and reviewable, whereas inspecting a constraint's implementation to classify it
     * would put reflection on an error path -- and an error path is the one place a surprise must not
     * happen. A new presence constraint is added here by name.</p>
     */
    private static final Set<String> PRESENCE_CONSTRAINTS =
            Set.of("NotNull", "NotBlank", "NotEmpty");

    /**
     * The sort rank given to a failed presence constraint, placing it ahead of every shape constraint.
     */
    private static final int CONSTRAINT_RANK_PRESENCE = 0;

    /**
     * The sort rank given to every constraint that is not a presence constraint.
     */
    private static final int CONSTRAINT_RANK_OTHER = 1;

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

        // WHY : Refactoring Rationale: a body that declares its own check order gets the baseline's
        //       first-message-wins behaviour, and every other body is untouched. Bean Validation
        //       evaluates every constraint in no defined order, so a screen whose reference program
        //       stops at the FIRST failure -- sign-on being the case where the sentence itself is
        //       observable, at lines 118 to 125 of app/cbl/COSGN00C.cbl -- could not have its sentence
        //       carried across verbatim as transformation rule T8 requires: the aggregate was a generic
        //       sentence and the array order was whatever the provider produced. Sorting into the body's
        //       declared order and latching the first entry's own message reproduces exactly the two
        //       halves of the baseline's behaviour -- one latched sentence, and per-field markers that
        //       accumulate.
        // WHY : Alternatives Considered: doing this unconditionally for every body. Rejected because
        //       most migrated screens accumulate rather than latch -- the divergence register carries
        //       D-ERROR-ACCUMULATION for precisely that -- so an unconditional change would alter the
        //       observable behaviour of every one of them in order to fix one.
        List<ApiError.FieldError> ordered = fieldErrors;
        String aggregate = MESSAGE_VALIDATION_FAILED;
        if (failure.getTarget() instanceof FieldOrdering ordering) {
            ordered = sortByDeclaredOrder(fieldErrors, ordering.fieldOrder());
            aggregate = ordered.isEmpty() ? MESSAGE_VALIDATION_FAILED : ordered.get(0).message();
        }

        return ResponseEntity.badRequest().body(ApiError.ofFieldErrors(aggregate,
                HttpStatus.BAD_REQUEST.value(), correlationId(), pathOf(request), ordered,
                this.clock));
    }

    /**
     * Sorts per-field entries into a body's declared check order, placing unrecognised names last.
     *
     * <p>Assumptions: the sort is STABLE, so two entries for one field keep the order the provider
     * produced them in, and entries whose field the body did not declare keep their encounter order after
     * the recognised ones. That is what makes a typo in a declared order degrade to encounter order for
     * one field rather than losing its entry or reordering the rest.</p>
     *
     * <p>Trade-offs: the position is resolved by a list lookup per entry rather than by building a map
     * once. A request body declares a handful of fields and a rejection carries a handful of entries, so
     * the two are small enough that a map would cost more to allocate than the lookups cost to run, and
     * the lookup form keeps the method to one readable expression.</p>
     *
     * @param fieldErrors the entries in the order the validation provider produced them; never
     *     {@code null}
     * @param declaredOrder the field names in the order the body declares they are checked; never
     *     {@code null}
     * @return a new list carrying every supplied entry, ordered by declared position with unrecognised
     *     names last, never {@code null}
     */
    private static List<ApiError.FieldError> sortByDeclaredOrder(
            List<ApiError.FieldError> fieldErrors, List<String> declaredOrder) {

        List<ApiError.FieldError> ordered = new ArrayList<>(fieldErrors);
        ordered.sort(Comparator.comparingInt(entry -> {
            int position = declaredOrder.indexOf(entry.field());
            // WHY : Assumptions: an unrecognised name sorts to the END rather than to the front, because
            //       the aggregate sentence is taken from the first entry and a name the body does not
            //       declare cannot be the field its screen checks first. Sorting it to the front would
            //       let a typo choose the sentence.
            return position < 0 ? Integer.MAX_VALUE : position;
        }));
        return List.copyOf(ordered);
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
     * <p>Refactoring Rationale: the aggregate sentence is LATCHED from the first rejected parameter,
     * where an earlier form always reported the generic one. The change is needed because several
     * migrated screens edit a parameter rather than a body member and publish the reference program's own
     * sentence for it -- the pending-authorization list is the case that forced it, whose contract
     * publishes {@code Please enter Acct Id...} and {@code Acct Id must be Numeric ...} verbatim from
     * lines 268 and 277 of {@code app/app-authorization-ims-db2-mq/cbl/COPAUS0C.cbl} as the aggregate of
     * a 400 and not only as a per-field entry. With a generic aggregate those responses were
     * unreachable, so transformation rule T8's requirement that a user-visible string be carried across
     * character for character could not be met for any parameter edit.</p>
     *
     * <p>Assumptions: the FIRST entry is the one latched, and first means the earliest rejected parameter
     * in the handler method's own signature order, which is the order the framework reports its results
     * in. That reproduces the reference behaviour rather than approximating it: those programs short-circuit,
     * testing the next edit only when the previous one passed, so the sentence a terminal displayed was
     * always the first failing edit's. The per-field entries still accumulate, which is the other half of
     * the baseline's behaviour and is why both are kept.</p>
     *
     * <p>Alternatives Considered: latching only when some marker interface is present, the way the body
     * handler consults a declared field order. Rejected because a parameter list has no bound object to
     * carry such a marker -- that absence is why this handler exists at all -- so the condition would have
     * to be expressed somewhere other than on the thing it governs. Alternatives Considered: leaving the
     * generic aggregate and asking each contract to publish it. Rejected because it would require editing
     * every published example away from the reference wording, which is the opposite of what rule T8
     * requires.</p>
     *
     * @param failure the parameter validation failure the framework raised; must not be {@code null}
     * @param request the request that failed, read only for its path
     * @return HTTP 400 carrying {@link ApiError#CODE_VALIDATION}, one per-field entry for each rejected
     *     parameter, and the first such entry's own sentence as the aggregate, or the generic sentence
     *     when the failure carried no entry at all; never {@code null}
     */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiError> onInvalidParameter(HandlerMethodValidationException failure,
            HttpServletRequest request) {

        List<ApiError.FieldError> fieldErrors = new ArrayList<>();
        for (var parameterResult : failure.getParameterValidationResults()) {
            // WHY : Refactoring Rationale: a NESTED body member is keyed by its own name and not by the
            //       parameter that carried it. This branch is reached because the framework routes a
            //       @Valid @RequestBody through THIS exception -- rather than through the bound-body
            //       exception below -- whenever the same handler method also carries a constraint on a
            //       path variable or a query parameter, which every operation with a sealed path selector
            //       does. Without the branch every such rejection was keyed by the parameter name, so a
            //       body member out of its domain arrived as an entry keyed "request", and a client could
            //       not mark the control at fault. The published contracts name body members as their
            //       per-field keys, so the parameter name is the one name that is never right here.
            if (parameterResult instanceof ParameterErrors nested) {
                for (FieldError rejected : nested.getFieldErrors()) {
                    FieldValidationFlag state = isNeverSupplied(rejected.getRejectedValue())
                            ? FieldValidationFlag.BLANK
                            : FieldValidationFlag.NOT_OK;
                    fieldErrors.add(new ApiError.FieldError(rejected.getField(), state,
                            messageOf(rejected)));
                }
                // WHY : Assumptions: a class-level violation on a nested body names no member, so it is
                //       carried keyed by the object name for the same reason the bound-body handler
                //       carries one: reading only the field errors would DROP it and answer 400 with an
                //       empty array, which is a rejection with nothing for the client to display.
                for (ObjectError global : nested.getGlobalErrors()) {
                    fieldErrors.add(new ApiError.FieldError(global.getObjectName(),
                            FieldValidationFlag.NOT_OK, messageOf(global)));
                }
                continue;
            }

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
            // WHY : Refactoring Rationale: two constraints failing on ONE parameter are ordered
            //       presence-constraint first, because the validation provider evaluates the constraints
            //       of a parameter in no defined order and the aggregate sentence is taken from the first
            //       entry. The reference programs test blank BEFORE format and stop at the first failure
            //       -- the account scope of the pending-authorization list is the case that forced this,
            //       rejecting a blank value at line 264 of
            //       app/app-authorization-ims-db2-mq/cbl/COPAUS0C.cbl and reaching its numeric test only
            //       at line 273 -- so an empty value must report the blank sentence and not the format
            //       one. Without this ordering the aggregate was whichever of the two the provider
            //       happened to produce first, which is not stable between runs and cannot be made to
            //       match a published example.
            // WHY : Refactoring Rationale: the rank is taken from the FAILING CONSTRAINT and not from the
            //       rejected VALUE, which is what an earlier revision of this branch got wrong. The state
            //       below is a property of the value, so a never-supplied value makes EVERY entry of that
            //       parameter blank -- both the presence entry and the format entry -- and a comparator
            //       keyed on the state therefore compared equal on exactly the pair it had to separate,
            //       leaving the provider's arbitrary order in place. Ranking by constraint separates them
            //       whatever the value is.
            // WHY : Assumptions: the sort is STABLE and is applied per PARAMETER rather than across the
            //       whole list, so two entries of one rank keep their encounter order and a later
            //       parameter's presence entry cannot overtake an earlier parameter's format entry.
            //       Sorting the whole list would reorder parameters against the handler's signature
            //       order, which is the order the reference edits run in.
            List<MessageSourceResolvable> ordered =
                    new ArrayList<>(parameterResult.getResolvableErrors());
            ordered.sort(Comparator.comparingInt(GlobalExceptionHandler::constraintRank));
            for (MessageSourceResolvable resolvable : ordered) {
                FieldValidationFlag state = isNeverSupplied(parameterResult.getArgument())
                        ? FieldValidationFlag.BLANK
                        : FieldValidationFlag.NOT_OK;
                fieldErrors.add(new ApiError.FieldError(fieldIdentity, state,
                        resolvedMessageOf(resolvable)));
            }
        }

        LOG.warn("event=api.request.rejected code={} status=400 path={} parameters={}",
                ApiError.CODE_VALIDATION, pathOf(request), fieldErrors.size());

        // WHY : Assumptions: the empty case falls back to the generic sentence rather than to an empty
        //       aggregate. A validation failure carrying no resolvable error at all should not be
        //       reachable, but if it ever is, a 400 whose aggregate is blank tells a caller nothing,
        //       whereas the generic sentence at least names the class of failure.
        String aggregate = fieldErrors.isEmpty()
                ? MESSAGE_VALIDATION_FAILED
                : fieldErrors.get(0).message();

        return ResponseEntity.badRequest().body(ApiError.ofFieldErrors(aggregate,
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

        // WHY : Refactoring Rationale: a bounded context needing the reference's own absence wording now
        //       gets it by raising the standard no-such-element type with that sentence, which
        //       referenceMessageOrNull proves came from the catalogue. Before this the sentence was
        //       discarded and the fixed one returned, so the wording each contract promises for a 404
        //       -- "Transaction ID NOT found..." at line 285 of app/cbl/COTRN01C.cbl among them -- was
        //       unreachable through this advice.
        String rendered = referenceMessageOrNull(failure.getMessage());

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiError.of(ApiError.CODE_NOT_FOUND,
                rendered == null ? MESSAGE_NOT_FOUND : rendered, HttpStatus.NOT_FOUND.value(),
                correlationId(), pathOf(request), this.clock));
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
        // WHY : Refactoring Rationale: all three branches now render through
        //       ApiError.ofConflict with Subsystem.RELATIONAL, where they previously used ApiError.of
        //       and therefore emitted Subsystem.APPLICATION. The published contracts declare a
        //       contention refusal as arising in the relational store and their examples show
        //       RELATIONAL, so the body a client parsed contradicted the document describing it. Each
        //       of these three conditions is raised by the persistence provider or by the database
        //       itself, which is exactly what RELATIONAL names.
        if (isOfType(failure, OPTIMISTIC_LOCK_EXCEPTION_NAME)) {
            LOG.warn("event=api.conflict.optimistic-lock code={} status=409 path={} exception={}",
                    ApiError.CODE_CONFLICT, pathOf(request), failure.getClass().getName());
            return conflictResponse(MESSAGE_RECORD_CHANGED, request, null);
        }

        if (isOfType(failure, PESSIMISTIC_LOCK_EXCEPTION_NAME)) {
            LOG.warn("event=api.conflict.lock-unavailable code={} status=409 path={} exception={}",
                    ApiError.CODE_CONFLICT, pathOf(request), failure.getClass().getName());
            return conflictResponse(MESSAGE_LOCK_UNAVAILABLE, request, null);
        }

        if (isOfType(failure, INTEGRITY_VIOLATION_EXCEPTION_NAME)) {
            LOG.warn("event=api.conflict.integrity code={} status=409 path={} exception={}",
                    ApiError.CODE_CONFLICT, pathOf(request), failure.getClass().getName());
            return conflictResponse(MESSAGE_REFERENCED_ROW, request, null);
        }

        // WHY : Assumptions: this branch exists even though the framework would route a
        //       ClientInputException to onRejectedCallerInput on its own, because the framework's
        //       dispatch is not the only way into this method. A caller inside the application, and
        //       every test that exercises the mapping directly, calls this method with a
        //       RuntimeException and is entitled to the same answer the framework would produce.
        //       Without the delegation the class would hold two different mappings for one type and
        //       which one applied would depend on how the failure arrived, which is precisely the kind
        //       of divergence a shared kernel must not have. The three conflict tests above run first
        //       because none of them is a ClientInputException and their answer is more specific.
        // WHY : Refactoring Rationale: the test is ClientInputException and NOT its supertype
        //       IllegalArgumentException, which is what it used to be. Widening it to the supertype
        //       swept up every internal invariant in the migration -- a transfer object refusing a
        //       component the SERVICE constructed it with, a page envelope refusing a cursor the
        //       SERVICE built, an edit mask refusing a band the SERVICE composed -- and reported each
        //       to the caller as a 400 it should correct, with warning severity. Two things were lost
        //       at once: a client was told to fix a request it had sent correctly, and a genuine server
        //       defect never reached the 500 channel the alerting watches. A bare
        //       IllegalArgumentException now falls through to onUnexpectedFailure, which is where an
        //       invariant failure belongs.
        if (failure instanceof ClientInputException rejectedInput) {
            return onRejectedCallerInput(rejectedInput, request);
        }

        if (failure instanceof RecordConflictException contention) {
            return onRecordConflict(contention, request);
        }

        return onUnexpectedFailure(failure, request);
    }

    /**
     * The three-dot ending every operator sentence the reference screens emit is written with.
     *
     * <p>Assumptions: this terminator is what distinguishes a sentence composed in this repository from a
     * message a library produced. Every screen message in the reference programs ends this way -- the
     * empty-identifier complaint at line 149 of {@code app/cbl/COTRN01C.cbl}, the absent-record one at
     * line 285 and the failed-read one at line 292 all do -- and no exception text a framework, a parser
     * or a driver produces does. Recognising the shape is therefore a test for provenance rather than a
     * test for content.</p>
     */
    private static final String REFERENCE_MESSAGE_TERMINATOR = "...";

    /**
     * The greatest number of characters a carried sentence may hold to be rendered.
     *
     * <p>Assumptions: this is the declared width of the reference message field, so a sentence longer
     * than this could not have come from the message catalogue whatever else it looks like. The bound is
     * a second, independent reason to refuse a library message and it also keeps the rendered aggregate
     * inside the width the published contracts declare for it.</p>
     */
    private static final int MAX_REFERENCE_MESSAGE_LENGTH = 75;

    /**
     * The number of consecutive digits at which a run is treated as an account or card identifier.
     *
     * <p>Assumptions: the shortest primary account number in circulation is thirteen digits, so a run of
     * that length is refused whether or not it is one. The correlation filter applies the same threshold
     * to the identifier a client supplies, and using one number in both places keeps a single rule rather
     * than two that could drift.</p>
     */
    private static final int SENSITIVE_DIGIT_RUN = 13;

    /**
     * Returns a carried sentence when it is provably one of this repository's own, otherwise
     * {@code null}.
     *
     * <p>Refactoring Rationale: this gate exists because the three rendering paths below used to answer
     * with a fixed sentence and discard the one the service raised, and the published contracts of six
     * bounded contexts promise the reference wording verbatim -- transformation rule T8 requires it, and
     * a client shown "Please correct the highlighted fields" where the document promises
     * "Tran ID can NOT be empty..." is being shown something the contract does not describe. Discarding
     * the message was not an oversight: it is what kept a library's own report of what it could not parse
     * out of a response body and out of log storage, which is the one destination the masking applied at
     * the API edge does not reach. The gate keeps that property and stops paying for it with the
     * contract, by deciding provenance from the SHAPE of the sentence rather than trusting the raise
     * site to have been careful.</p>
     *
     * <p>Alternatives Considered: carrying a renderable flag on the exception, so that a raise site could
     * declare its own message safe. It was rejected because it moves the decision to the place with the
     * least reason to think about it and the most reason to want a shortcut: a service wrapping a driver
     * failure would set the flag to get a better message out, and nothing downstream could tell that
     * apart from a catalogue constant. A shape test cannot be talked into anything.</p>
     *
     * <p>Assumptions: four independent conditions must all hold. The sentence ends with the reference
     * terminator, which no framework message does; it is no longer than the reference field width; every
     * character is printable seven-bit text, which excludes the control characters a forged log record
     * needs and the multi-byte content a decoded record could carry; and it holds no digit run long
     * enough to be an account or card number. A catalogue constant satisfies all four by construction. A
     * driver message quoting the value it rejected fails the last, a parser message quoting a token fails
     * the first, and a stack-derived message fails the second.</p>
     *
     * <p>Trade-offs: a library message that happened to satisfy all four would be rendered, and that is
     * the residual risk accepted here. What is bought is that the six contracts promising reference
     * wording are satisfiable at all without every service reaching into this class to say so. The
     * balance is defensible because the terminator condition alone is not something a message written
     * anywhere but this repository's catalogue ends with.</p>
     *
     * @param message the sentence the failure carried, or {@code null} when it carried none
     * @return the same sentence when all four conditions hold, otherwise {@code null}
     */
    private static String referenceMessageOrNull(String message) {
        if (message == null || !message.endsWith(REFERENCE_MESSAGE_TERMINATOR)
                || message.length() > MAX_REFERENCE_MESSAGE_LENGTH) {
            return null;
        }

        int digitRun = 0;
        for (int index = 0; index < message.length(); index++) {
            char character = message.charAt(index);
            if (character < ' ' || character > '~') {
                return null;
            }
            digitRun = character >= '0' && character <= '9' ? digitRun + 1 : 0;
            if (digitRun >= SENSITIVE_DIGIT_RUN) {
                return null;
            }
        }
        return message;
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
     * The caught throwable is logged as its type chain and originating frames, with every message
     * dropped by {@link com.carddemo.common.observability.ThrowableDigest}, and the response carries
     * only the fixed sentence. Refactoring Rationale: this sentence used to say the throwable was
     * logged with its stack trace "where the appender configuration governs it". It named a control
     * that does not exist -- this repository ships no appender configuration at all -- and it named it
     * two paragraphs after correctly observing that a provider message "routinely carries a statement,
     * a connection detail or a vendor code". The reduction now happens at the call site, where it
     * cannot be configured away.</p>
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
     * @param failure the unclaimed failure; recorded as the reduced representation
     *     {@link ThrowableDigest} produces -- its chain of type names and originating frames, with every
     *     message dropped -- and never rendered into the response. The declared
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

        // WHY : (1) Refactoring Rationale: the throwable was formerly passed as a trailing argument, and
        //       the note here said that doing so "leaves redaction and layout to the appender
        //       configuration, which one place owns". No such place exists. This repository ships no
        //       logback.xml, logback-spring.xml or log4j2.xml anywhere, and deliberately so -- three
        //       environment profiles record that adding one would take over the appender chain wholesale
        //       -- so the chain is the framework default and no repository-owned filter sits in it. The
        //       facade renders a trailing throwable by printing its message, then every cause's message;
        //       those sentences are composed by drivers, parsers and validation libraries, not here, and
        //       any of them can carry a primary account number or a whole request record. This handler is
        //       by definition the one that fires for failures nobody anticipated, so the content was
        //       unbounded by construction and the named control was fictional.
        //       (2) Assumptions: what is logged instead is a reduced representation that keeps the type
        //       chain and the originating frames and drops every message. That split is not a compromise
        //       between safety and usefulness: a type name and a frame are facts about CODE, so no request
        //       value can reach either, while a message is the only part of a throwable a value can be
        //       interpolated into. The two questions an operator asks first -- what failed, and where --
        //       are both answered by what survives.
        //       (3) Trade-offs: a driver's account of a constraint violation, a parser's position in a
        //       document and a validator's rule name are all messages, so none of them survives here.
        //       Recovering one is a deliberate, scoped act -- raising debug logging for the specific
        //       package and reproducing the failure -- rather than the default posture of every 500.
        //       (4) Assumptions: the digest is sanitised even though a type name and a frame cannot
        //       ordinarily carry a control character, because a generated proxy or lambda name is still a
        //       string this code did not author, and a value of external provenance entering a structured
        //       line is exactly what that helper exists for.
        LOG.error("event=api.request.failed code={} status=500 path={} exception={} failure={}",
                ApiError.CODE_INTERNAL, pathOf(request), failure.getClass().getName(),
                LogSafeText.sanitize(ThrowableDigest.of(failure)));

        // WHY : Assumptions: the four components are supplied as constants rather than composed from the
        //       caught throwable. A culprit or reason built from an exception's own class name or message
        //       would put provider-internal text into a value that is operator-facing prose, and the
        //       message component is the one place a caught message could plausibly be routed to a
        //       structured field. Supplying fixed text removes that route rather than filtering it.
        // WHY : Refactoring Rationale: the aggregate is the sentence the failure carried only when the
        //       thrown type is EXACTLY the standard illegal-state one and referenceMessageOrNull proves
        //       the sentence came from the catalogue. Both tests are needed and neither alone would do.
        //       The type test is an equality rather than an instance test on purpose: every persistence
        //       and cloud failure that reaches here arrives as a SUBCLASS of a framework exception, so
        //       an instance test would admit provider text that the shape test might not catch, whereas
        //       nothing but code in this repository throws the bare type. This is what makes the
        //       failed-read wording each contract promises -- "Unable to lookup Transaction..." at line
        //       292 of app/cbl/COTRN01C.cbl -- reachable without opening a route for provider internals.
        // WHY : Assumptions: the abend detail's own four components stay fixed even when the aggregate
        //       is the carried sentence. Those are operator-facing prose about where the failure was
        //       recognised rather than about what the caller asked for, and the reference's own abend
        //       fields at lines 45 to 53 of app/cpy/CSMSG02Y.cpy carry the same kind of content.
        String carried = failure.getClass() == IllegalStateException.class
                ? referenceMessageOrNull(failure.getMessage())
                : null;
        String aggregate = carried == null ? MESSAGE_INTERNAL : carried;

        AbendDetail abend = new AbendDetail(ABEND_CODE_UNEXPECTED, ABEND_CULPRIT_ADVICE,
                ABEND_REASON_UNEXPECTED, MESSAGE_INTERNAL);

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiError.ofAbend(aggregate, correlationId(), pathOf(request), abend,
                        this.clock));
    }

    /**
     * Renders a failure the caller's own input provoked as HTTP 400 with the per-field array.
     *
     * <p>Refactoring Rationale: every caller-input failure that was not one of three named persistence
     * conflicts used to reach {@link #onUnexpectedFailure(Exception, HttpServletRequest)} and be
     * answered as HTTP 500 with severity critical and an abend block. Two things were wrong with that
     * and they are separate. It told a caller the service had failed when the caller had in fact sent
     * something the service correctly refused -- a malformed date being the canonical case, and
     * transformation rule T7 requires exactly that shape of refusal to surface as a structured
     * per-field array. And it raised the severity reserved for an abend on an ordinary typing mistake,
     * so the channel that is supposed to mean a service is broken would carry a steady stream of
     * requests that were merely wrong, which is how an alert channel stops being read.
     *
     * <p>Refactoring Rationale: the claimed type is {@link ClientInputException} and NOT its supertype
     * {@link IllegalArgumentException}, which is what an earlier revision claimed. The wider claim swept
     * up every internal invariant this migration expresses with that exception -- a transfer object
     * refusing a component the service constructed it with, a page envelope refusing a cursor the
     * service built, an edit mask refusing a band the service composed -- and answered each with a 400
     * at warning severity. That is wrong twice over: a client is told to correct a request it sent
     * correctly, and a real server defect is kept out of the 500 channel the alerting watches, so the
     * failure is invisible to exactly the mechanism meant to see it. Narrowing to a type raised only
     * where the refused value came from OUTSIDE the process restores both, and the members that used to
     * arrive here legitimately still do: the shared codecs' format failure and the shared date
     * validator's refusal are both {@link ClientInputException} subclasses.
     *
     * <p>Assumptions: a bare {@link IllegalArgumentException}, a {@link NumberFormatException} raised by
     * a numeric parse the service performed on a value it had already validated, and every other member
     * of that family now reach {@link #onUnexpectedFailure(Exception, HttpServletRequest)} and are
     * answered as HTTP 500. That is the intended consequence, not a regression: a service that parses an
     * unvalidated caller value with a platform parser is missing a validation, and reporting the missing
     * validation as a server fault is what gets it fixed. A service that WANTS the 400 shape for a
     * caller value raises {@link ClientInputException}, which also gives it a field key and a stable
     * code the earlier form could not carry.
     *
     * <p>Alternatives Considered: matching a wrapped cause as well, the way the persistence conflicts
     * are matched by walking the cause chain. It was rejected because the two situations are not alike.
     * A persistence provider deliberately wraps its own failures, so the cause chain is where the
     * meaning lives; a refusal discovered somewhere inside an infrastructure failure is evidence about
     * that infrastructure rather than about the caller. The test here is therefore the declared type
     * alone.
     *
     * @param failure the rejected-input failure that propagated out of a handler method; its stable code
     *     and its redacted message are logged, its message is never rendered, and it is never
     *     {@code null} on any path the framework reaches this method by
     * @param request the request that failed, read only for its path
     * @return HTTP 400 carrying {@link ApiError#CODE_VALIDATION}, {@link #MESSAGE_VALIDATION_FAILED},
     *     warning severity, no abend detail, and one field entry per member the refusal names, in the
     *     order it named them, or a single entry keyed by the request when it names none; never
     *     {@code null}
     */
    @ExceptionHandler(ClientInputException.class)
    public ResponseEntity<ApiError> onRejectedCallerInput(ClientInputException failure,
            HttpServletRequest request) {

        // WHY : Refactoring Rationale: the log line carries a STABLE CODE, and it carries the message
        //       only because the claimed type guarantees the message is redacted. The earlier form
        //       logged getMessage() from the whole IllegalArgumentException family after sanitising
        //       control characters, which is a defence against forging a log RECORD and no defence at
        //       all against the CONTENT of one: a library or parser message quotes the token it could
        //       not read, and when that token is a primary account number the value lands in log
        //       storage -- the one destination the masking applied at the API edge does not reach.
        //       ClientInputException's contract is that its own subclasses compose their messages
        //       through a per-field sensitivity gate, so the detail is safe by construction here and
        //       only here.
        // WHY : Assumptions: the code is logged as its own field rather than folded into the message,
        //       because it exists to be matched on by an alert rule and a log query, and a token inside
        //       a sentence is matchable only by substring.
        LOG.warn("event=api.request.rejected code={} status=400 path={} reason={} exception={} detail={}",
                ApiError.CODE_VALIDATION, pathOf(request), failure.code(),
                failure.getClass().getName(), LogSafeText.sanitize(failure.getMessage()));

        // WHY : Assumptions: the entries are keyed by the refusal's own fields when it names any, and by a
        //       name for the request as a whole when it does not. Keying by the field is what lets a form
        //       draw its marker against the right control, which transformation rule T7 requires and
        //       which the earlier form could not do because the exception carried no field identity. The
        //       fallback is not a new convention: a class-level constraint violation reaching
        //       onInvalidBody names no member either and is reported there keyed by the object name.
        // WHY : Assumptions: the state is the not-acceptable-value one rather than the blank one,
        //       because the blank state asks a form to draw a marker for an empty control and a refused
        //       value is not empty. Answering with an EMPTY array was rejected: rule T7 makes the array
        //       the way a rejection is expressed, and an empty one gives a client a 400 with nothing to
        //       display.
        // WHY : Refactoring Rationale: EVERY named member gets an entry, where an earlier form emitted
        //       exactly one. The change is forced by cross-field refusals, whose whole content is that
        //       several members disagree: the pending-authorization fraud operation repeats its three key
        //       components in its body and its contract publishes that a disagreement names the
        //       disagreeing members rather than preferring one naming silently. Emitting one entry meant a
        //       client corrected one member and was refused again on the next, learning the set one round
        //       trip at a time.
        // WHY : Refactoring Rationale: the sentence is the one the refusal CARRIED when
        //       referenceMessageOrNull proves it came from this repository's catalogue, and the fixed one
        //       otherwise. An earlier form always used the fixed one, which is why six published
        //       contracts promised reference wording that no response could produce.
        // WHY : Assumptions: the state comes from the refusal rather than being fixed at the
        //       rejected-value one, so the blank case can draw the asterisk the reference draws for it.
        //       A signature that names no state stores the rejected-value one, so every older raise site
        //       renders exactly as it did before.
        // WHY : Assumptions: one state and one sentence cover every named member, because the only
        //       multi-member refusal this repository raises is a cross-field COMPARISON -- the members
        //       fail together and for the same reason, so a per-member sentence would repeat one
        //       sentence and a per-member state would offer a distinction no raise site can make.
        String rendered = referenceMessageOrNull(failure.getMessage());
        String aggregate = rendered == null ? MESSAGE_VALIDATION_FAILED : rendered;
        List<ApiError.FieldError> fieldErrors;
        if (failure.fields().isEmpty()) {
            fieldErrors = List.of(new ApiError.FieldError(
                    FIELD_REQUEST, failure.state(), aggregate));
        } else {
            List<ApiError.FieldError> named = new ArrayList<>(failure.fields().size());
            for (String fieldKey : failure.fields()) {
                named.add(new ApiError.FieldError(fieldKey, failure.state(), aggregate));
            }
            fieldErrors = List.copyOf(named);
        }


        return ResponseEntity.badRequest().body(ApiError.ofFieldErrors(aggregate,
                HttpStatus.BAD_REQUEST.value(), correlationId(), pathOf(request), fieldErrors,
                this.clock));
    }

    /**
     * Renders an absent required header, cookie, query parameter or matrix variable as HTTP 400,
     * naming the value that was not supplied.
     *
     * <p>Refactoring Rationale: these four failures reached
     * {@link #onUnexpectedFailure(Exception, HttpServletRequest)} before this handler existed and were
     * answered as HTTP 500 with severity critical and an abend block, because
     * {@code ServletRequestBindingException} is a CHECKED {@code ServletException} rather than a runtime
     * one, so neither {@link #onRejectedCallerInput(ClientInputException, HttpServletRequest)} nor
     * {@link #onRuntimeFailure(RuntimeException, HttpServletRequest)} could claim it and the
     * catch-all did. That was measured on the account update, whose {@code If-Match} precondition is
     * required: omitting the header produced a 500 carrying the abend shape. The objection is the one
     * already recorded on {@link #onRejectedCallerInput(ClientInputException, HttpServletRequest)} --
     * it told a caller the service had failed when the caller had in fact omitted something the
     * service correctly requires, and it raised the severity reserved for an abend on a caller's
     * omission, which is how an alert channel stops being read.
     *
     * <p>Assumptions: {@code MissingPathVariableException} is deliberately NOT claimed here even
     * though it is the fifth member of the same {@code MissingRequestValueException} family. It is
     * raised when a handler declares a path variable the URI template does not contain, which is a
     * mapping defect in THIS repository rather than anything a caller did, and Spring's own
     * {@code getStatusCode()} on that type returns 500 for exactly that reason. Folding it in would
     * report a server defect to a caller as their mistake and would remove it from the channel that
     * exists to surface server defects.
     *
     * <p>Assumptions: the entry is keyed by the value's own name -- the header name, the cookie name,
     * the parameter name or the variable name -- and carries {@link FieldValidationFlag#BLANK}, which
     * is the state the reference programs set through their {@code FLG-*-BLANK} conditions when a
     * control was left empty. A caller can act on that pair without parsing prose: it names what to
     * supply and says that nothing was supplied.
     *
     * <p>Alternatives Considered: rendering the framework's own {@code getMessage()}, which already
     * names the missing value. Rejected for the reason recorded on
     * {@link #onUnexpectedFailure(Exception, HttpServletRequest)}: a framework sentence is composed
     * outside this repository, so its content is not bounded by anything here. The name is read from
     * the typed accessor instead and the sentence is this repository's own constant.
     *
     * @param failure the binding failure naming the absent value; never {@code null} on any path the
     *     framework reaches this method by
     * @param request the request that failed, read only for its path
     * @return HTTP 400 carrying {@link ApiError#CODE_VALIDATION}, the aggregate sentence and one
     *     per-field entry keyed by the absent value's name, never {@code null}
     */
    @ExceptionHandler({MissingRequestHeaderException.class, MissingRequestCookieException.class,
            MissingServletRequestParameterException.class, MissingMatrixVariableException.class})
    public ResponseEntity<ApiError> onMissingRequestValue(MissingRequestValueException failure,
            HttpServletRequest request) {

        // WHY : Assumptions: the name is read through a pattern switch over the four claimed types
        //   rather than through one accessor, because the family's common supertype publishes no name
        //   at all -- each subtype names its own accessor after the kind of value it carries. The
        //   switch is exhaustive over what the annotation claims, and the default is unreachable for
        //   that reason; it is present because the compiler cannot see the annotation, and it degrades
        //   to the whole-request key rather than to an empty array so that a fifth type admitted here
        //   later still produces a displayable refusal.
        String name = switch (failure) {
            case MissingRequestHeaderException header -> header.getHeaderName();
            case MissingRequestCookieException cookie -> cookie.getCookieName();
            case MissingServletRequestParameterException parameter -> parameter.getParameterName();
            case MissingMatrixVariableException variable -> variable.getVariableName();
            default -> FIELD_REQUEST;
        };

        // WHY : Assumptions: the delegation constructs the refusal this repository already renders
        //   rather than assembling a second problem document here. One rendering means the shape a
        //   client parses for an omitted header is byte-identical to the shape it parses for a rejected
        //   value, which is what lets a client keep one handler for HTTP 400.
        // WHY : Assumptions: the name is sanitised before it is keyed. A header or parameter name is
        //   chosen by the CALLER, not by this repository -- an unknown name never reaches here, but the
        //   name of a DECLARED value is echoed back and a request may present it with control
        //   characters in the raw bytes -- so it is a value of external provenance entering a
        //   structured field, which is what that helper exists for.
        return onRejectedCallerInput(new ClientInputException(ApiError.CODE_VALIDATION,
                LogSafeText.sanitize(name), FieldValidationFlag.BLANK, MESSAGE_VALIDATION_FAILED),
                request);
    }

    /**
     * Renders a path or query value that cannot be converted to the type its handler declares as
     * HTTP 400, naming the parameter and never echoing the value.
     *
     * <p>Refactoring Rationale: this failure reached
     * {@link #onRuntimeFailure(RuntimeException, HttpServletRequest)} before this handler existed --
     * it is a {@code TypeMismatchException} and therefore a runtime one -- matched none of that
     * method's three contention branches, and fell through to the same HTTP 500 abend shape. It was
     * measured on the account routes, whose identifier path variables are declared as {@code long}: a
     * non-numeric segment produced a 500. Six published contracts declare HTTP 400 on operations whose
     * only reachable 400 was this one, so the status a client was told to expect was one no response
     * could produce.
     *
     * <p>Assumptions: the offending VALUE is never rendered and never logged, so neither the response
     * nor the log line can echo it back. The reason is not squeamishness about diagnostics: the values
     * that arrive in these positions include account identifiers, and the framework's own
     * {@code getMessage()} quotes the value it could not convert verbatim. Reporting which parameter
     * was wrong is actionable without it -- a caller holds the value it sent.
     *
     * <p>Assumptions: the state is {@link FieldValidationFlag#NOT_OK} rather than the blank one,
     * because a value WAS supplied and was refused. That distinction is the reference's own: its
     * templated highlight at {@code app/cpy/CSSETATY.cpy} lines 17 to 27 draws an asterisk for a blank
     * control and only the colour change for a rejected value.
     *
     * @param failure the conversion failure naming the parameter it could not bind; never {@code null}
     *     on any path the framework reaches this method by
     * @param request the request that failed, read only for its path
     * @return HTTP 400 carrying {@link ApiError#CODE_VALIDATION}, the aggregate sentence and one
     *     per-field entry keyed by the parameter's name, never {@code null}
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> onUnconvertibleValue(MethodArgumentTypeMismatchException failure,
            HttpServletRequest request) {

        // WHY : Assumptions: getName() is the handler parameter's own name, which is authored in this
        //   repository, so the sanitisation applied here guards against nothing a caller controls. It
        //   is applied anyway for the reason the unexpected-failure handler records about generated
        //   names: a value this code did not author entering a structured line is what the helper is
        //   for, and applying it unconditionally removes the need for a reader to verify the
        //   provenance of every key.
        return onRejectedCallerInput(new ClientInputException(ApiError.CODE_VALIDATION,
                LogSafeText.sanitize(failure.getName()), FieldValidationFlag.NOT_OK,
                MESSAGE_VALIDATION_FAILED), request);
    }

    /**
     * Renders a contention refusal a service raised deliberately, with its kind and its version.
     *
     * <p>Refactoring Rationale: this handler exists because the three provider-name branches of
     * {@link #onRuntimeFailure(RuntimeException, HttpServletRequest)} cannot express two things the
     * published contracts promise. They cannot report the CURRENT version of the contended row, because
     * a provider exception does not carry it, so a caller told only that the record changed has to
     * re-read it to discover what it changed to. And they only fire when the provider or the database
     * raised the failure, so a service that compared a version ITSELF -- which is what the baseline's
     * before-image comparison does at lines 669 and 521 of {@code app/cbl/COACTUPC.cbl} -- had no way to
     * produce the contention shape at all and would have fallen through to a 500.
     *
     * <p>Assumptions: the version is reported as a per-field entry keyed {@link #FIELD_VERSION} rather
     * than as a new component of the problem shape. The published contracts seal that shape against
     * unknown properties, so a component added for this one refusal would have to be admitted on every
     * other response and documented as absent there; one entry inside the array every client already
     * parses says the same thing and keeps the shape satisfiable. This is the concrete, satisfiable
     * form the reference contract's conflict schema describes.
     *
     * <p>Assumptions: the subsystem is {@link ApiError.Subsystem#RELATIONAL} for all three kinds, which
     * is what the contracts declare. It is the relational store the contention is over even when this
     * service, rather than the provider, is what noticed it.
     *
     * @param failure the contention a service raised, naming which condition and carrying the current
     *     version when the condition has one; never {@code null} on any path that reaches here
     * @param request the request that failed, read only for its path
     * @return HTTP 409 carrying {@link ApiError#CODE_CONFLICT}, the sentence its kind selects, the
     *     relational subsystem, and a version entry when one was supplied; never {@code null}
     */
    @ExceptionHandler(RecordConflictException.class)
    public ResponseEntity<ApiError> onRecordConflict(RecordConflictException failure,
            HttpServletRequest request) {

        // WHY : Assumptions: the sentence is selected here rather than held on the exception, so every
        //       user-visible string in this layer stays in one place under transformation rule T8. The
        //       switch is exhaustive over the enum, so a fourth condition cannot be added without this
        //       method failing to compile -- which is the property that keeps the mapping complete.
        String message = switch (failure.kind()) {
            case STALE_VERSION -> MESSAGE_RECORD_CHANGED;
            case LOCK_UNAVAILABLE -> MESSAGE_LOCK_UNAVAILABLE;
            case REFERENCED_ROW -> MESSAGE_REFERENCED_ROW;
        };

        LOG.warn("event=api.conflict.declared code={} status=409 path={} kind={} versionReported={}",
                ApiError.CODE_CONFLICT, pathOf(request), failure.kind(),
                failure.currentVersion() != null);

        return conflictResponse(message, request, failure.currentVersion());
    }

    /**
     * Builds the one contention response shape every conflict path in this class returns.
     *
     * <p>Refactoring Rationale: four call sites used to compose this response separately, and three of
     * them composed it through a factory that hardcoded the application subsystem. One builder means the
     * code, the status, the subsystem and the version-entry convention are stated once, so a fifth
     * conflict condition cannot be added with a different shape.</p>
     *
     * @param message the user-visible contention sentence, carried verbatim from its baseline source
     * @param request the request that failed, read only for its path
     * @param currentVersion the version the contended row now holds, or {@code null} when the condition
     *     carries none, in which case the field array is empty
     * @return the 409 response, never {@code null}
     */
    private ResponseEntity<ApiError> conflictResponse(String message, HttpServletRequest request,
            Long currentVersion) {

        // WHY : Assumptions: the array is EMPTY rather than carrying a placeholder when no version is
        //       available, because an entry naming a version this response does not know would be a
        //       value invented for the shape's sake. An empty array is already what a non-validation
        //       problem shape carries everywhere else in this class.
        List<ApiError.FieldError> fieldErrors = currentVersion == null
                ? List.of()
                : List.of(new ApiError.FieldError(FIELD_VERSION, FieldValidationFlag.NOT_OK,
                        String.valueOf(currentVersion)));

        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiError.ofConflict(message,
                ApiError.Subsystem.RELATIONAL, correlationId(), pathOf(request), fieldErrors,
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
     * <p>Assumptions: what this returns is safe to place in a JSON body and in a log line, and is NOT
     * escaped for HTML. Three things are done to it and they are the three that matter for those two
     * destinations: control characters are neutralised so a value cannot forge a log record, primary
     * account numbers are masked, and the serialiser escapes whatever JSON requires. An angle bracket, a
     * quote, an ampersand or a SQL metacharacter that a caller put in its own request target therefore
     * survives into the body as literal data -- correctly, because it is data, and because a JSON API
     * that mangled the path a caller sent would make a 404 harder to diagnose than the path itself
     * is.</p>
     *
     * <p>Trade-offs: the consequence is one obligation on the other side of the boundary and it is
     * recorded here because that side cannot infer it. A client rendering this member into a document
     * has to escape it at the point of insertion, exactly as it would any other server-supplied text,
     * rather than treating it as pre-sanitised because it came from an error body. Escaping it here
     * instead was rejected: HTML escaping in a JSON payload would double-escape for every non-document
     * consumer, and the one consumer that needs it is the one that already owns an escaping step.</p>
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
     * Ranks a rejected parameter constraint so that presence edits sort ahead of every other edit.
     *
     * <p>Assumptions: the constraint's own name is the LAST entry of the resolvable's code array. The
     * framework builds those codes most specific first -- handler-and-parameter qualified, then
     * parameter qualified, then type qualified, then bare -- so the bare annotation name is always the
     * final element. The name is additionally cut at its first {@code '.'}, which is a no-op for the
     * bare form and keeps the rank correct if a future framework release stops emitting one.</p>
     *
     * <p>Alternatives Considered: reading the constraint annotation off the violation descriptor
     * instead. That would need the bean-validation violation, which this advice deliberately does not
     * depend on -- it handles the framework's own validation failure type so that a service without a
     * validation provider on its path still gets the same response shape -- so the resolvable's codes
     * are the only identity available here.</p>
     *
     * <p>Trade-offs: package-private rather than private, so that a test can assert the rank of a
     * presence constraint against the rank of a shape constraint directly. Accepted because the
     * alternative leaves the behaviour effectively unassertable: the validation provider reports the
     * constraints of one element in an order derived from a hash set, so a request-level test observes
     * whichever order that hash happens to give and cannot be made to observe the other one. A
     * request-level test therefore cannot fail when this ranking is removed, whereas a direct assertion
     * can. The method stays out of the published surface -- nothing outside this package can reach
     * it.</p>
     *
     * @param resolvable the framework's own resolvable error for one failed constraint on one parameter
     * @return {@link #CONSTRAINT_RANK_PRESENCE} when the failed constraint asserts that a value was
     *     supplied at all, otherwise {@link #CONSTRAINT_RANK_OTHER}
     */
    static int constraintRank(MessageSourceResolvable resolvable) {
        String[] codes = resolvable.getCodes();
        if (codes == null || codes.length == 0) {
            return CONSTRAINT_RANK_OTHER;
        }
        String constraint = codes[codes.length - 1];
        int qualifier = constraint.indexOf('.');
        if (qualifier >= 0) {
            constraint = constraint.substring(0, qualifier);
        }
        return PRESENCE_CONSTRAINTS.contains(constraint)
                ? CONSTRAINT_RANK_PRESENCE
                : CONSTRAINT_RANK_OTHER;
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
