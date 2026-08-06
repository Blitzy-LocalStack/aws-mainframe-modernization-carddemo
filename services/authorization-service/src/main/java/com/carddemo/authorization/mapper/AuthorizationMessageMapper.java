package com.carddemo.authorization.mapper;

import com.carddemo.authorization.dto.AuthorizationReplyPayload;
import com.carddemo.authorization.dto.AuthorizationRequestPayload;
import com.carddemo.common.codec.CsvAuthCodec.AuthReply;
import com.carddemo.common.codec.CsvAuthCodec.AuthRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Carries the eighteen-field request and the six-field reply between the wire records and this
 * context's queue payload types, validating the payload on every crossing.
 *
 * <p><b>Purpose.</b> Two representations of one contract exist in this migration, deliberately and for
 * different consumers. {@link AuthRequest} and {@link AuthReply} in the shared kernel are the CSV wire
 * records: the field order, the comma delimiter, the declared widths and the edited money rendering all
 * belong there, because with a string-format payload those things ARE the interface for the requester
 * already waiting on the queue. {@link AuthorizationRequestPayload} and
 * {@link AuthorizationReplyPayload} are this context's structured representation of the same contract,
 * carrying the copybook documentation and the Jakarta constraints, and offered additively for a consumer
 * that reads a structured envelope rather than a delimited string. This class is the ONE crossing
 * between them.</p>
 *
 * <p>Refactoring Rationale: before this class existed the two representations had no connection at all.
 * Nothing converted between them, nothing validated a payload, and the constraints declared on the two
 * payload records were therefore unreachable -- they described a contract that no code path applied.
 * Two consequences followed. The constraints had already drifted from the wire records they were
 * supposed to mirror, restricting three character fields to digits that the copybook admits any
 * character in, and pinning three widths as exact that the codec pads. And the charter for the payload
 * package claimed the wire form lived in one place, which was accurate about the field ORDER's authority
 * and inaccurate about there being one contract. Both payload records are corrected, this crossing makes
 * their constraints executable, and the contract test beside this class asserts field for field that the
 * two representations agree -- so the duplication is now checked rather than asserted.</p>
 *
 * <p>Assumptions: the wire record is the NORMATIVE side and the payload is derived. Every mapping below
 * moves component values one for one in declared order and renames exactly one member,
 * {@code cardNum} to {@code cardNumber}; nothing is reordered, defaulted, trimmed or reformatted here,
 * because normalisation is the wire record's own compact-constructor concern and money rendering is the
 * codec's.</p>
 *
 * <p>Trade-offs: validation runs on the crossing rather than inside either record. The wire records
 * already refuse a null, an over-wide value and an embedded delimiter at construction, and adding a
 * second copy of those checks here would give two answers to one question; what the payload adds is a
 * value DOMAIN -- a closed response-code set, a closed reason set, a non-negative amount -- and this is
 * the one place a caller crossing between the two forms can be told about a violation in terms of the
 * component that carries it.</p>
 *
 * <p>Assumptions: this class discharges the payload half of the role the package charter assigns to
 * {@code AuthorizationMessageMapper}. That charter also assigns it the crossing onto the persistent
 * entity, onto the outbox row and onto the 122-byte error-log record of
 * {@code app/app-authorization-ims-db2-mq/cpy/CCPAUERY.cpy}; those remain planned rather than landed,
 * and they are named here so the difference between the two is visible rather than inferred.</p>
 */
@Component
public class AuthorizationMessageMapper {

    /**
     * The validation engine the payload constraints are applied through.
     */
    private final Validator validator;

    /**
     * Creates the mapper.
     *
     * <p>Assumptions: the engine arrives through the constructor rather than being created here, so this
     * class uses the application's own configured engine -- the same one the web layer applies to a
     * request body -- and a unit test can supply an engine built from the default provider without a
     * container.</p>
     *
     * @param validator the validation engine; must not be {@code null}
     */
    public AuthorizationMessageMapper(Validator validator) {
        this.validator = validator;
    }

    /**
     * Derives the structured request payload from the decoded wire record.
     *
     * <p>Assumptions: the payload is validated AFTER it is built and not before, because the constraints
     * are declared on the payload's own components and there is nothing to apply them to until it
     * exists. A wire record that decoded successfully can still carry a value the payload's domain
     * refuses -- a negative amount is exactly that case -- so this crossing can fail even though the
     * decode did not.</p>
     *
     * @param source the decoded wire record; must not be {@code null}
     * @return the equivalent payload, never {@code null}
     * @throws NullPointerException if {@code source} is {@code null}
     * @throws ConstraintViolationException if the derived payload violates its declared contract
     */
    public AuthorizationRequestPayload toPayload(AuthRequest source) {
        if (source == null) {
            throw new NullPointerException("request wire record must not be null");
        }
        AuthorizationRequestPayload payload = new AuthorizationRequestPayload(source.authDate(),
                source.authTime(), source.cardNum(), source.authType(), source.cardExpiryDate(),
                source.messageType(), source.messageSource(), source.processingCode(),
                source.transactionAmount(), source.merchantCategoryCode(),
                source.acquirerCountryCode(), source.posEntryMode(), source.merchantId(),
                source.merchantName(), source.merchantCity(), source.merchantState(),
                source.merchantZip(), source.transactionId());
        requireValid(payload, "authorization request payload");
        return payload;
    }

    /**
     * Derives the wire record from the structured request payload.
     *
     * <p>Assumptions: the payload is validated BEFORE the wire record is built, which is the reverse of
     * the direction above and is deliberate. The wire record's compact constructor raises a
     * width-or-delimiter failure naming a copybook field; validating first means a caller with a
     * structurally invalid payload is told which COMPONENT is wrong, in the vocabulary it supplied,
     * rather than being told about a copybook field it never named.</p>
     *
     * @param payload the structured payload; must not be {@code null}
     * @return the equivalent wire record, never {@code null}
     * @throws NullPointerException if {@code payload} is {@code null}
     * @throws ConstraintViolationException if the payload violates its declared contract
     */
    public AuthRequest toWireRecord(AuthorizationRequestPayload payload) {
        if (payload == null) {
            throw new NullPointerException("request payload must not be null");
        }
        requireValid(payload, "authorization request payload");
        return new AuthRequest(payload.authDate(), payload.authTime(), payload.cardNumber(),
                payload.authType(), payload.cardExpiryDate(), payload.messageType(),
                payload.messageSource(), payload.processingCode(), payload.transactionAmount(),
                payload.merchantCategoryCode(), payload.acquirerCountryCode(),
                payload.posEntryMode(), payload.merchantId(), payload.merchantName(),
                payload.merchantCity(), payload.merchantState(), payload.merchantZip(),
                payload.transactionId());
    }

    /**
     * Derives the structured reply payload from the wire record.
     *
     * @param source the reply wire record; must not be {@code null}
     * @return the equivalent payload, never {@code null}
     * @throws NullPointerException if {@code source} is {@code null}
     * @throws ConstraintViolationException if the derived payload violates its declared contract
     */
    public AuthorizationReplyPayload toPayload(AuthReply source) {
        if (source == null) {
            throw new NullPointerException("reply wire record must not be null");
        }
        AuthorizationReplyPayload payload = new AuthorizationReplyPayload(source.cardNum(),
                source.transactionId(), source.authIdCode(), source.authRespCode(),
                source.authRespReason(), source.approvedAmount());
        requireValid(payload, "authorization reply payload");
        return payload;
    }

    /**
     * Derives the wire record from the structured reply payload.
     *
     * @param payload the structured payload; must not be {@code null}
     * @return the equivalent wire record, never {@code null}
     * @throws NullPointerException if {@code payload} is {@code null}
     * @throws ConstraintViolationException if the payload violates its declared contract
     */
    public AuthReply toWireRecord(AuthorizationReplyPayload payload) {
        if (payload == null) {
            throw new NullPointerException("reply payload must not be null");
        }
        requireValid(payload, "authorization reply payload");
        return new AuthReply(payload.cardNumber(), payload.transactionId(), payload.authIdCode(),
                payload.authResponseCode(), payload.authResponseReason(), payload.approvedAmount());
    }

    /**
     * Applies the declared constraints of one payload and raises on any violation.
     *
     * <p>Assumptions: every violation is reported rather than the first one, because a producer
     * correcting a payload with three wrong fields should learn about three of them from one rejection.
     * The exception type is the specification's own, so a caller already handling validation failures
     * from the web layer handles these identically.</p>
     *
     * @param <T> the payload type being validated
     * @param candidate the payload to validate; must not be {@code null}
     * @param description the payload's name, used to introduce the violations in the failure message
     * @throws ConstraintViolationException if the payload violates its declared contract
     */
    private <T> void requireValid(T candidate, String description) {
        Set<ConstraintViolation<T>> violations = this.validator.validate(candidate);
        if (!violations.isEmpty()) {
            // WHY : Assumptions: the message names the payload and the count and nothing else, while the
            // violation set travels on the exception for a caller that renders per-component detail. A
            // message composed from the violations themselves would quote the offending values, and on
            // this payload those values are a primary account number and an authorization amount.
            throw new ConstraintViolationException(description + " violates its declared contract in "
                    + violations.size() + " component(s)", violations);
        }
    }
}
