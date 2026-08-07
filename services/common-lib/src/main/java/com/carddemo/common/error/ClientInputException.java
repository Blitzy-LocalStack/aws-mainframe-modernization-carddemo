package com.carddemo.common.error;

import com.carddemo.common.validation.FieldValidationFlag;
import java.util.List;

import java.util.Objects;

/**
 * Signals that a value SUPPLIED BY A CALLER was refused, and that the refusal is the caller's to fix.
 *
 * <h2>The problem this exists for</h2>
 *
 * <p>Refactoring Rationale: the shared advice used to claim the whole {@link IllegalArgumentException}
 * family and answer every member of it with HTTP 400. The intent was right -- transformation rule T7
 * requires a refused input to surface as a structured per-field array rather than as an abend -- but the
 * type it was hung on is far wider than caller input. Every internal invariant in this migration is
 * expressed with that same exception: a transfer object rejecting an out-of-range component it was
 * constructed with in code, a page envelope refusing a cursor the SERVICE built, an edit mask refusing a
 * value too wide for the band the SERVICE composed. Each of those is a defect in this system, and each
 * was being reported to the caller as "you sent something wrong" with warning severity and a 200-level
 * operational posture. The consequences are two and both are serious: a client is told to correct a
 * request it sent correctly, and a real server fault never reaches the 500 channel, so the alerting that
 * watches that channel cannot see it.</p>
 *
 * <p>This type draws the line the exception hierarchy could not. It is raised only where the value
 * being refused came from outside the process, and the advice claims THIS type rather than its
 * supertype. Everything else that is an {@link IllegalArgumentException} keeps travelling to the
 * internal-failure handler, which is where an invariant failure belongs.</p>
 *
 * <h2>The redaction contract, and why it is on the type</h2>
 *
 * <p>Assumptions: every subclass GUARANTEES that its own message is safe to write to an operational
 * log -- it names the field, the constraint and the observed shape, and it never reproduces the value of
 * a field its own sensitivity policy withholds. That guarantee is what lets the advice log a detail at
 * all. Before this type existed the advice logged {@code getMessage()} from the whole
 * {@link IllegalArgumentException} family, which includes messages composed by libraries and parsers
 * this repository has never seen; a parser that quotes the token it could not read will quote a primary
 * account number when that is the token, and a log line is the one destination the masking applied at
 * the API edge does not reach. Sanitising control characters, which is all the advice previously did,
 * removes the ability to forge a log RECORD and does nothing about the content of one.</p>
 *
 * <p>Trade-offs: the guarantee is a documented obligation on subclasses rather than something this
 * class can enforce, and that is the accepted weakness of the design. What makes it hold in practice is
 * that the set of subclasses is small, is entirely inside this repository, and each one composes its
 * message through a per-field sensitivity gate that is unit-tested. Alternatives Considered: holding the
 * message in a private field and exposing only a code, so that no free text could escape. Rejected
 * because the free text is the diagnostic -- an operator reading a refusal needs to know which field and
 * which constraint, and a bare code moves that information into a lookup table that has to be maintained
 * beside the code.</p>
 *
 * <h2>What the code is for</h2>
 *
 * <p>Assumptions: the stable code is a short token an alert rule and a log query can match on, and it is
 * deliberately NOT the {@link ApiError} code. That one is a response contract a client reads; this one
 * names the internal cause a first responder greps for, so the two are free to change independently.
 * Alternatives Considered: reusing the response code here. Rejected because it is the same value for
 * every refused input, so it distinguishes nothing in a log.</p>
 */
public class ClientInputException extends IllegalArgumentException {

    /**
     * Serialisation identity for a throwable, which the platform requires to be declared.
     *
     * <p>Assumptions: the value is fixed at one because this type's serialised form is not a contract
     * anything reads -- a refusal is rendered as JSON and never as a serialised Java object -- so the
     * identity exists to satisfy the platform rather than to version anything.</p>
     */
    private static final long serialVersionUID = 1L;

    /**
     * The stable token an alert rule or a log query matches this refusal on.
     */
    private final String code;

    /**
     * The logical fields the refusal is attributed to, empty when the request as a whole is.
     *
     * <p>Refactoring Rationale: this is a LIST where an earlier revision held a single name, and the
     * widening was forced by a class of refusal the single name cannot express: a cross-field
     * disagreement. The pending-authorization fraud operation is the concrete case -- its request body
     * repeats the three key components that its path selector already names, and
     * {@code services/authorization-service/src/main/resources/openapi/authorization-api.yaml} publishes
     * that a request in which they disagree is refused with the disagreeing members NAMED, plural,
     * "rather than one of the two namings being preferred silently". With one name available, two of the
     * three disagreements would have had to be dropped, and a client shown one of three wrong members
     * corrects one and is refused again.</p>
     *
     * <p>Alternatives Considered: raising one exception per disagreeing member. Rejected because only the
     * first would ever be rendered -- an exception unwinds the handler -- so the client would see a
     * single-member refusal again, arrived at by a longer route. Alternatives Considered: a separate
     * exception type carrying a list, leaving this one single-valued. Rejected because the shared advice
     * would then need a second handler producing the identical body, and two handlers for one status and
     * one shape are two places the shape can drift.</p>
     *
     * <p>Assumptions: the list is unmodifiable and never {@code null}. A refusal naming no member at all
     * is the empty list rather than a null one, so the advice reads a single state for "no member" and
     * the two absence encodings a nullable list would admit do not arise.</p>
     */
    private final List<String> fields;

    /**
     * The per-field validation state a form draws its marker from, never {@code null}.
     *
     * <p>Assumptions: the state is carried on the refusal rather than decided by whatever renders it,
     * because only the refusing code knows whether the control was left empty or filled with a value an
     * edit rejected, and the baseline's templated highlight at lines 17 to 27 of
     * {@code app/cpy/CSSETATY.cpy} draws a different marker for each -- an asterisk into the field for
     * the blank case and the colour attribute alone for the rejected-value case. A renderer that assumed
     * one state could express only one of the two.</p>
     *
     * <p>Trade-offs: the two older signatures store the rejected-value state, so their meaning is
     * unchanged and no existing raise site had to be edited when this component was added. What is given
     * up is that a caller wanting the blank state has to name it; what is bought is that no existing
     * refusal silently changed the marker a form draws.</p>
     */
    private final FieldValidationFlag state;

    /**
     * Creates a refusal attributed to the request as a whole.
     *
     * @param code the stable token this refusal is matched on in operational tooling; must not be
     *     {@code null} or blank
     * @param message the redacted diagnostic, naming the field and the constraint and never reproducing
     *     the value of a withheld field; must not be {@code null}
     * @throws NullPointerException if {@code code} or {@code message} is {@code null}
     * @throws IllegalArgumentException if {@code code} is blank
     */
    public ClientInputException(String code, String message) {
        // WHY : Assumptions: the absent field is expressed as an EMPTY LIST rather than as a cast null,
        //       because a bare null is ambiguous between the two three-argument constructors and a cast
        //       null would name one of them only to have it convert the null straight back to this same
        //       empty list. Delegating to the storing constructor is one step instead of two and states
        //       the meaning -- no member is named -- rather than encoding it as an absent name.
        this(code, List.<String>of(), message);
    }

    /**
     * Creates a refusal attributed to one named field.
     *
     * @param code the stable token this refusal is matched on in operational tooling; must not be
     *     {@code null} or blank
     * @param field the logical field the refusal belongs to, as a client would key it, or {@code null}
     *     when the request as a whole is at fault
     * @param message the redacted diagnostic, naming the field and the constraint and never reproducing
     *     the value of a withheld field; must not be {@code null}
     * @throws NullPointerException if {@code code} or {@code message} is {@code null}
     * @throws IllegalArgumentException if {@code code} is blank
     */
    public ClientInputException(String code, String field, String message) {
        this(code, singletonOrEmpty(field), FieldValidationFlag.NOT_OK, message);
    }

    /**
     * Wraps one possibly-absent field name as the list the storing constructor takes.
     *
     * <p>Refactoring Rationale: this exists to keep the two constructors unambiguous, and the reason is
     * a language rule rather than a style preference. Inlining the choice as a conditional expression
     * makes it a POLY conditional -- one of its branches is a generic method invocation without explicit
     * type arguments -- so its type is inferred from the target, and both constructors then appear
     * applicable to the delegating call, which the compiler reports as an ambiguous reference. A
     * statically typed helper resolves the type before the call is made, so exactly one constructor
     * applies.</p>
     *
     * @param field the field name, or {@code null} when the request as a whole is at fault
     * @return a one-element list holding {@code field}, or an empty list when it is {@code null}; never
     *     {@code null}
     */
    private static List<String> singletonOrEmpty(String field) {
        List<String> empty = List.of();
        return field == null ? empty : List.of(field);
    }

    /**
     * Creates a refusal attributed to several named fields at once.
     *
     * <p>Assumptions: the order of {@code fields} is PRESERVED and is significant, because the advice
     * renders one per-field entry per name in the order supplied and a client rendering a form draws its
     * markers in that order. A raise site that has a natural order -- a key's own component order, for
     * instance -- should supply it rather than leave the order to a set's iteration.</p>
     *
     * <p>Trade-offs: a duplicate name is neither rejected nor collapsed. Rejecting it would put a
     * validation concern on an exception constructor, which is a poor place to report a defect at a raise
     * site, and collapsing it would silently discard an entry a caller meant to send; the two would each
     * hide a mistake this class cannot distinguish from an intention.</p>
     *
     * @param code the stable token this refusal is matched on in operational tooling; must not be
     *     {@code null} or blank
     * @param fields the logical fields the refusal belongs to, as a client would key them, in the order
     *     they should be rendered; must not be {@code null} and must contain no {@code null} element,
     *     and may be empty when the request as a whole is at fault
     * @param message the redacted diagnostic, naming the fields and the constraint and never reproducing
     *     the value of a withheld field; must not be {@code null}
     * @throws NullPointerException if {@code code}, {@code fields} or {@code message} is {@code null}, or
     *     if any element of {@code fields} is {@code null}
     * @throws IllegalArgumentException if {@code code} is blank
     */
    public ClientInputException(String code, List<String> fields, String message) {
        this(code, fields, FieldValidationFlag.NOT_OK, message);
    }

    /**
     * Creates a refusal naming the field, the state its control is in, and the diagnostic.
     *
     * <p>Refactoring Rationale: this signature exists because the others can express only the
     * rejected-value state, and the migrated screens need both states rendered. Transformation rule T7
     * turns the baseline's paired not-acceptable and blank condition names into one structured array
     * entry per field, so a refusal unable to say which of the two it is forces whatever renders it to
     * guess, and a form told that a blank control holds a rejected value draws no asterisk where the
     * baseline draws one.</p>
     *
     * <p>Assumptions: the message supplied here carries the same obligation the class contract states for
     * every other signature. It is composed from constants and from values that have passed a per-field
     * sensitivity gate, never from text a caller supplied and never from a library's own report of what
     * it could not parse.</p>
     *
     * @param code the stable token this refusal is matched on in operational tooling; must not be
     *     {@code null} or blank
     * @param field the logical field the refusal belongs to, as a client would key it, or {@code null}
     *     when the request as a whole is at fault
     * @param state the validation state the field's control is in; must not be {@code null}
     * @param message the redacted diagnostic, naming the field and the constraint and never reproducing
     *     the value of a withheld field; must not be {@code null}
     * @throws NullPointerException if {@code code}, {@code state} or {@code message} is {@code null}
     * @throws IllegalArgumentException if {@code code} is blank
     */
    public ClientInputException(String code, String field, FieldValidationFlag state, String message) {
        this(code, singletonOrEmpty(field), state, message);
    }

    /**
     * Creates a refusal attributed to several named fields at once, all in one state.
     *
     * <p>Assumptions: ONE state covers every name, and that is a deliberate bound rather than an
     * oversight. The only multi-field raise site in this repository is a cross-field COMPARISON, where
     * the two members fail together and for the same reason, so a state per name would offer a
     * distinction no raise site can make. A refusal whose members genuinely differ in state is two
     * refusals, and the advice already renders an array.</p>
     *
     * @param code the stable token this refusal is matched on in operational tooling; must not be
     *     {@code null} or blank
     * @param fields the logical fields the refusal belongs to, in rendering order; must not be
     *     {@code null} and must contain no {@code null} element
     * @param state the validation state every named field's control is in; must not be {@code null}
     * @param message the redacted diagnostic; must not be {@code null}
     * @throws NullPointerException if any argument is {@code null}, or if any element of {@code fields}
     *     is {@code null}
     * @throws IllegalArgumentException if {@code code} is blank
     */
    public ClientInputException(String code, List<String> fields, FieldValidationFlag state,
            String message) {

        super(Objects.requireNonNull(message, "message must not be null"));
        Objects.requireNonNull(code, "code must not be null");
        if (code.isBlank()) {
            // WHY : Assumptions: a blank code is refused at construction rather than accepted and
            //       defaulted, because the code's only purpose is to be matched on, and a blank one
            //       matches every refusal and therefore none. Failing here reports the omission at the
            //       raise site, which is the only place it can be corrected.
            throw new IllegalArgumentException("code must not be blank");
        }
        this.code = code;
        // WHY : Assumptions: the copy is defensive AND null-hostile in one step. List.copyOf rejects a
        //       null element, which is what stops a per-field entry keyed by nothing reaching a client,
        //       and it returns an unmodifiable view so a raise site that keeps its own list cannot alter
        //       what the advice will render after the throw.
        this.fields = List.copyOf(Objects.requireNonNull(fields, "fields must not be null"));
        this.state = Objects.requireNonNull(state, "state must not be null");

    }

    /**
     * Returns the stable token this refusal is matched on in operational tooling.
     *
     * @return the code supplied at construction, never {@code null} and never blank
     */
    public String code() {
        return this.code;
    }

    /**
     * Returns the logical field this refusal is attributed to.
     *
     * <p>Assumptions: this accessor is retained unchanged in meaning for the single-field case, which is
     * every raise site in this repository other than the cross-field comparison that forced
     * {@link #fields()} to exist. It answers the FIRST name when several were supplied, because a caller
     * asking for one name from a multi-member refusal wants the one the client should be positioned on,
     * and the constructor documents the supplied order as the rendering order.</p>
     *
     * @return the first field name supplied at construction, or {@code null} when no field was named and
     *     the request as a whole is at fault
     */
    public String field() {
        return this.fields.isEmpty() ? null : this.fields.get(0);
    }

    /**
     * Returns every logical field this refusal is attributed to, in the order it should be rendered.
     *
     * @return an unmodifiable list of field names, empty when the request as a whole is at fault and no
     *     member can be named; never {@code null}
     */
    public List<String> fields() {
        return this.fields;
    }

    /**
     * Returns the validation state the refused field's control is in.
     *
     * @return the state supplied at construction, the rejected-value one when no signature named a
     *     state, never {@code null}
     */
    public FieldValidationFlag state() {
        return this.state;
    }
}
