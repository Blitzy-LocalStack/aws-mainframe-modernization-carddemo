package com.carddemo.common.error;

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
     * The logical field the refusal is attributed to, or {@code null} when the request as a whole is.
     */
    private final String field;

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
        this(code, null, message);
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
        this.field = field;
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
     * @return the field name supplied at construction, or {@code null} when the request as a whole is
     *     at fault and no single field can be named
     */
    public String field() {
        return this.field;
    }
}
