package com.carddemo.common.error;

import com.carddemo.common.time.TimestampFormatter;
import com.carddemo.common.validation.FieldValidationFlag;
import java.time.Clock;
import java.util.List;
import java.util.Objects;

/**
 * The one problem shape every migrated CardDemo service returns for a failed request.
 *
 * <p>This is the migrated form of the two message channels the reference baseline maintains side by
 * side. The aggregate sentence a user reads is
 * {@code 10 CCARD-ERROR-MSG PIC X(75).} at line 28 of {@code app/cpy/CVCRD01Y.cpy}; the per-field
 * marking a form renders is the {@code FLG-*-NOT-OK} / {@code FLG-*-BLANK} pair read by the
 * templated highlight copybook at lines 17 to 27 of {@code app/cpy/CSSETATY.cpy}. Transformation
 * rule T7 turns the second into the structured array carried by {@link #fieldErrors()}, and this
 * package's charter records why the two channels stay separate: the aggregate text is written under
 * a message-off latch and therefore reports the FIRST failure only, while the field markers are set
 * unconditionally and therefore accumulate across every failure the validation chain finds.</p>
 *
 * <p>Assumptions: the machine-readable {@link #code()} and the human-readable {@link #message()} are
 * separate members, following the precedent of the baseline's own structured record, whose two
 * nine-character code fields at lines 37 and 38 of {@code app/cpy/CSMSG02Y.cpy} sit apart from the
 * fifty-character message at line 39. Two other baseline sites assemble a code into the sentence a
 * user reads -- lines 544 to 549 of the authorization extension's {@code COPAUS1C.cbl} and lines 306
 * to 313 of {@code app/cpy/CSUTLDPY.cpy} -- and that is the pattern deliberately not carried across:
 * one string cannot serve an operator who needs a stable token to search for and a user who needs a
 * sentence that can be reworded without anything breaking.</p>
 *
 * <p>Assumptions: nothing assembled here is ever taken from the text of a caught exception. Every
 * member is supplied by the code that decided to fail, so there is no path by which a credential, a
 * connection string, a stack trace or a vendor error code reaches a response body. Diagnostic detail
 * goes to the operational record instead, keyed by the same {@link #correlationId()} this shape
 * carries, which is what makes it possible to answer a client with one short sentence and still
 * investigate the failure afterwards.</p>
 *
 * <p>Trade-offs: the 75-character width of the aggregate message is documented rather than enforced.
 * A client rendering into a reflowing browser layout has no 24-row terminal to overflow, so rejecting
 * a longer sentence would refuse text no consumer would have struggled with; what is given up is the
 * ability to prove by construction that a migrated message would still have fitted the original
 * screen. {@link #MESSAGE_RENDERING_WIDTH} publishes the number so a renderer that does care can
 * apply it.</p>
 *
 * <p>Alternatives Considered: the framework's own problem-detail type, and a service-local error
 * shape per bounded context. The first was rejected because it models neither the per-field array nor
 * the abend aggregate, so both would have to travel as loosely-typed extension properties that no
 * compiler checks; the second was rejected for the reason the baseline settles the same question --
 * every record layout resolves through one copybook include path, and transformation rule T2 carries
 * that discipline into Java, so eight service-local problem shapes would drift until the day a client
 * parses seven services' failures and mishandles the eighth.</p>
 *
 * @param code the stable machine-readable identifier for the failure, matched on by clients and by
 *     operational tooling and never reworded; never {@code null} or blank once constructed
 * @param message the human-readable aggregate sentence, carried verbatim from the originating
 *     copybook where the failure has one; never {@code null} once constructed, and the empty string
 *     where a failure has no sentence to display
 * @param status the HTTP status the response carries, restated inside the body so a logged or
 *     archived payload is self-describing without its response envelope
 * @param correlationId the identity of the unit of work that failed, inherited from the inbound
 *     request rather than invented here; the empty string when the request carried none
 * @param path the request path that failed, so a client reporting a problem does not have to
 *     reconstruct which call produced it; the empty string when no path is available
 * @param timestamp the instant of the failure in the baseline's 26-character form, produced by
 *     {@link TimestampFormatter}; the empty string when no clock reading is available
 * @param fieldErrors the per-field array, one entry per offending field in the order the validating
 *     code reported them; never {@code null} once constructed, empty when the failure is not
 *     field-attributable, and always unmodifiable
 * @param abend the structured abend aggregate when the failure was an abend, {@code null} otherwise;
 *     nullable because the baseline can ask whether a message has been set and this member reproduces
 *     that question rather than inventing a sentinel for it
 */
public record ApiError(
        String code,
        String message,
        int status,
        String correlationId,
        String path,
        String timestamp,
        List<FieldValidationFlag.FieldError> fieldErrors,
        AbendDetail abend) {

    /**
     * The width a renderer may hold the aggregate message to, seventy-five characters.
     *
     * <p>Assumptions: read from {@code 10 CCARD-ERROR-MSG PIC X(75).} at line 28 of
     * {@code app/cpy/CVCRD01Y.cpy}, and from the identically-declared
     * {@code 10 CCARD-RETURN-MSG PIC X(75).} on line 29 beside it. It is published as a constant
     * rather than restated at each renderer because the number is inherited from a file this
     * migration does not alter: one constant can only be wrong everywhere at once, and is therefore
     * checkable by one test against the copybook line it came from.</p>
     */
    public static final int MESSAGE_RENDERING_WIDTH = 75;

    /**
     * The code borne by a failure that a client can correct by changing its request.
     *
     * <p>Assumptions: the four code constants below are the closed set this shared kernel defines,
     * and a bounded context is free to publish its own alongside them. They are spelled as a fixed
     * prefix and a four-digit ordinal so that a code is recognisable as one at a glance and sorts
     * stably in an operational search, and so that no code can be mistaken for a sentence.</p>
     */
    public static final String CODE_VALIDATION = "CARDDEMO-0400";

    /**
     * The code borne by a failure caused by concurrent modification of the same record.
     *
     * <p>Assumptions: this is the migrated form of the baseline's own before-image comparison. The
     * account update program snapshots the whole pre-edit record from line 669 of
     * {@code app/cbl/COACTUPC.cbl}, carries the one-character change marker declared at its line 168,
     * and rolls back at lines 4097 to 4103 when the rewrite finds the record altered. One code covers
     * that case and the reference-data restrict-on-delete case, because both answer HTTP 409 and both
     * are told apart by {@link #message()} rather than by status.</p>
     */
    public static final String CODE_CONFLICT = "CARDDEMO-0409";

    /**
     * The code borne by a failure to find the record a request named.
     *
     * <p>Assumptions: the baseline expresses this as a file status rather than as an exception -- the
     * posting program's reject reason 100 is an unmatched cross-reference read and reason 101 an
     * unmatched account read -- so the migrated code is deliberately generic and the reject reason it
     * corresponds to travels in {@link #message()}.</p>
     */
    public static final String CODE_NOT_FOUND = "CARDDEMO-0404";

    /**
     * The code borne by a failure the client cannot correct.
     *
     * <p>Assumptions: this is the only code whose {@link #message()} is a fixed sentence that names
     * nothing about the cause. The cause is written to the operational record under the same
     * correlation identity, which is what allows the response to stay uninformative without the
     * failure becoming undiagnosable.</p>
     */
    public static final String CODE_INTERNAL = "CARDDEMO-0500";

    /**
     * The HTTP status an abend is reported with, five hundred.
     *
     * <p>Assumptions: declared as a constant in this module rather than taken from the web framework's
     * status enumeration, because the shared kernel's web-facing dependency is optional and this type
     * has to stay constructible in a service that does not have it on the path -- the batch context
     * builds problem shapes for its own operational record and serves no HTTP request at all.</p>
     */
    public static final int INTERNAL_SERVER_ERROR_STATUS = 500;

    /**
     * Normalises every component so that no instance can hold {@code null} except the abend
     * aggregate.
     *
     * <p>Assumptions: normalisation runs in the constructor because this type is built on the path
     * that REPORTS a failure, and a constructor that can itself fail there would replace the
     * diagnostic the caller wanted with a second one nobody asked for. A blank {@code code} is the one
     * component that cannot be defaulted usefully, so it is normalised to
     * {@link #CODE_INTERNAL} rather than rejected -- an unlabelled failure would otherwise reach a
     * client with no token to match on at all.</p>
     *
     * <p>Trade-offs: {@code fieldErrors} is copied and made unmodifiable. Copying costs an allocation
     * on every failure; what it buys is that a caller which keeps a reference to the list it passed
     * cannot mutate a problem shape after it has been built, which on a shared validation accumulator
     * would change a response body between assembly and serialisation.</p>
     *
     * @param code the stable machine-readable identifier, normalised to {@link #CODE_INTERNAL} when
     *     {@code null} or blank
     * @param message the aggregate sentence, normalised to the empty string when {@code null}
     * @param status the HTTP status, carried through unaltered
     * @param correlationId the inherited unit-of-work identity, normalised to the empty string when
     *     {@code null}
     * @param path the failing request path, normalised to the empty string when {@code null}
     * @param timestamp the 26-character failure instant, normalised to the empty string when
     *     {@code null}
     * @param fieldErrors the per-field array, normalised to an empty list when {@code null} and
     *     otherwise copied into an unmodifiable list
     * @param abend the structured abend aggregate, or {@code null} when the failure was not an abend
     */
    public ApiError {
        code = (code == null || code.isBlank()) ? CODE_INTERNAL : code;
        message = message == null ? "" : message;
        correlationId = correlationId == null ? "" : correlationId;
        path = path == null ? "" : path;
        timestamp = timestamp == null ? "" : timestamp;
        fieldErrors = fieldErrors == null ? List.of() : List.copyOf(fieldErrors);
    }

    /**
     * Builds a problem shape that is not attributable to any particular field.
     *
     * @param code the stable machine-readable identifier for the failure, one of this type's
     *     constants or a code published by a bounded context
     * @param message the aggregate sentence to display, carried verbatim from the originating
     *     copybook where the failure has one
     * @param status the HTTP status the response will carry
     * @param correlationId the identity of the unit of work, as resolved from the inbound request
     * @param path the request path that failed
     * @param clock the clock the failure instant is read from, supplied rather than resolved so a
     *     test can assert the emitted timestamp; must not be {@code null}
     * @return a problem shape carrying an empty field array and no abend aggregate, never
     *     {@code null}
     * @throws NullPointerException if {@code clock} is {@code null}
     */
    public static ApiError of(String code, String message, int status, String correlationId,
            String path, Clock clock) {
        Objects.requireNonNull(clock, "clock must not be null");
        return new ApiError(code, message, status, correlationId, path,
                TimestampFormatter.formatNow(clock), List.of(), null);
    }

    /**
     * Builds a problem shape carrying the per-field array of transformation rule T7.
     *
     * <p>Assumptions: the array's order is the order the validating code reported, and it is
     * preserved rather than sorted. The baseline highlights fields by running its expansions in the
     * order they are written -- from line 3208 to line 3432 of {@code app/cbl/COACTUPC.cbl}, which is
     * the order the fields appear on the screen -- so re-ordering here would change which field a
     * rendered form highlights first.</p>
     *
     * @param message the aggregate sentence to display, which reports the first failure only, matching
     *     the message-off latch the baseline writes it under
     * @param status the HTTP status the response will carry, ordinarily 400
     * @param correlationId the identity of the unit of work, as resolved from the inbound request
     * @param path the request path that failed
     * @param fieldErrors the offending fields in reporting order, as produced by
     *     {@link FieldValidationFlag#collectErrors}; may be empty but not {@code null}
     * @param clock the clock the failure instant is read from; must not be {@code null}
     * @return a problem shape carrying {@link #CODE_VALIDATION} and the supplied field array, never
     *     {@code null}
     * @throws NullPointerException if {@code fieldErrors} or {@code clock} is {@code null}
     */
    public static ApiError ofFieldErrors(String message, int status, String correlationId,
            String path, List<FieldValidationFlag.FieldError> fieldErrors, Clock clock) {
        Objects.requireNonNull(fieldErrors, "fieldErrors must not be null");
        Objects.requireNonNull(clock, "clock must not be null");
        return new ApiError(CODE_VALIDATION, message, status, correlationId, path,
                TimestampFormatter.formatNow(clock), fieldErrors, null);
    }

    /**
     * Builds a problem shape carrying the structured abend aggregate.
     *
     * <p>Assumptions: an abend is reported with {@link #CODE_INTERNAL} and a message that names
     * nothing about the cause, while the four components of {@link AbendDetail} carry the
     * operator-facing detail. That split is what lets the same payload be safe to return and useful to
     * an operator reading it out of the operational record.</p>
     *
     * @param message the aggregate sentence to display, which names nothing a client cannot act on
     * @param correlationId the identity of the unit of work, as resolved from the inbound request
     * @param path the request path that failed
     * @param abend the structured abend aggregate, already normalised to its declared widths by
     *     {@link AbendDetail}; must not be {@code null}
     * @param clock the clock the failure instant is read from; must not be {@code null}
     * @return a problem shape carrying HTTP 500, an empty field array and the supplied abend
     *     aggregate, never {@code null}
     * @throws NullPointerException if {@code abend} or {@code clock} is {@code null}
     */
    public static ApiError ofAbend(String message, String correlationId, String path,
            AbendDetail abend, Clock clock) {
        Objects.requireNonNull(abend, "abend must not be null");
        Objects.requireNonNull(clock, "clock must not be null");
        return new ApiError(CODE_INTERNAL, message, INTERNAL_SERVER_ERROR_STATUS, correlationId, path,
                TimestampFormatter.formatNow(clock), List.of(), abend);
    }

    /**
     * Reports whether this problem shape attributes the failure to one or more named fields.
     *
     * @return {@code true} when the per-field array holds at least one entry, {@code false} when the
     *     failure is not field-attributable
     */
    public boolean hasFieldErrors() {
        return !this.fieldErrors.isEmpty();
    }
}
