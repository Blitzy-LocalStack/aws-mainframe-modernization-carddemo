package com.carddemo.authorization.dto;

import com.carddemo.common.web.CursorToken;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Carries an authorization row key and the fraud-marking command accepted at the HTTP boundary.
 *
 * <p><strong>Purpose.</strong> This request identifies one pending authorization and asks the
 * service either to report or to remove its fraud mark without accepting the persisted detail
 * payload from a browser.
 *
 * <h2>Boundary shape</h2>
 *
 * <p>Alternatives Considered: The baseline passes the complete 200-byte {@code COPY CIPAUDTY}
 * segment through {@code DFHCOMMAREA} at {@code cbl/COPAUS2C.cbl} L77-L78. Mirroring that segment
 * in the request body would save the server one read, but the callers have different trust
 * boundaries: the baseline caller is a sibling program reached by {@code EXEC CICS LINK} inside
 * the same CICS task, while the target caller is a browser over HTTP. Accepting the segment from
 * that browser would let it choose persisted content rather than only the row and action. The
 * target therefore carries the key and action and re-reads the detail. The fixed-width merchant
 * name described below also means a client-trimmed segment would not round-trip byte for byte.
 *
 * <p>Assumptions: The bidirectional {@code DFHCOMMAREA} at {@code cbl/COPAUS2C.cbl} L74 occupies
 * 272 bytes: 11 + 9 + 200 + 1 + 1 + 50. The copied detail contributes 200 bytes from 28 elementary
 * items; its four packed-decimal widths are 3, 5, 7 and 7 bytes. The target request derives from
 * the request-direction fields at L75, L76 and L80, while the response derives from L83 and L86.
 * One COBOL area serves both directions, whereas HTTP represents request and response separately,
 * so the migration expresses the two directions as distinct Java records.
 *
 * <p>Assumptions: {@code PA-MERCHANT-NAME} is {@code PIC X(22)} at
 * {@code cpy/CIPAUDTY.cpy} L40 and is not trimmed before persistence.
 * {@code cbl/COPAUS2C.cbl} L130 moves {@code LENGTH OF PA-MERCHANT-NAME}, which is always 22, into
 * the level-49 Db2 {@code VARCHAR} length field; L131 then moves the text. Comparisons and
 * round-trips against the persisted value must therefore account for all trailing spaces.
 *
 * <p>Trade-offs: Re-reading the detail costs one additional data-store read per request. In
 * exchange, the caller selects the row and action but cannot supply persisted row content.
 *
 * <h2>Type and identity rulings</h2>
 *
 * <p>Refactoring Rationale: the row is now identified by ONE opaque selector, and previously it was
 * identified by three caller-supplied key parts -- an account identifier, an authorization date key and
 * an authorization time key -- alongside a customer identifier the operation never used. Three
 * consequences followed, and each is a defect rather than a matter of taste. A caller could address ANY
 * authorization on ANY account by arithmetic on three numbers, so the row a client marked as fraudulent
 * was chosen by the client rather than by the page it was shown. The client also had no way to obtain
 * those numbers legitimately, because the summary payload it renders carried no key at all -- the
 * baseline holds that key in the communication area, which the migration retires -- so the contract
 * required a value the system never issued. And the two key parts were documented as nines-complement
 * values, which the persisted schema deliberately does not store: {@code V1__authorization.sql} keeps
 * the decoded ordinal date and time and expresses the baseline's descending read order as a descending
 * index instead, so a caller sending the complement would have addressed a different row than it meant.
 *
 * <p>Assumptions: the selector is the value {@code PendingAuthSummaryResponse} publishes for the row,
 * one per displayed row, which is the migrated form of
 * {@code MOVE PA-AUTHORIZATION-KEY TO CDEMO-CPVS-AUTH-KEYS(n)} at {@code cbl/COPAUS0C.cbl} L545, L557,
 * L569, L581 and L593 and of the mark that moves one of those slots into
 * {@code CDEMO-CPVS-PAU-SELECTED} across L288 to L305. It is sealed with
 * {@code com.carddemo.common.web.CursorToken}, whose authentication code is bound to the query and the
 * authenticated subject, so a token is usable only by the subject it was issued to; this record asserts
 * its SHAPE, and the service that holds the key material verifies the code and recovers the three key
 * parts of the {@code authorization.pending_auth_detail} primary key from it.
 *
 * <p>Alternatives Considered: keeping the account identifier alongside the selector, so a reader of a
 * request body can see which account is being acted on. Rejected because the account is already inside
 * the sealed token and a second copy would either be redundant or disagree with it -- and if the two
 * disagreed, whichever one the service happened to read would decide which account was affected.
 *
 * <p>Alternatives Considered: Lombok is unnecessary because a Java 21 record exposes the two
 * components directly and keeps their boundary documentation together. MapStruct is not used
 * because reconstructing the persistent detail is not a mechanical projection: sealed selectors and
 * fixed-width padding require explicit mapping decisions and adjacent rationale.
 *
 * <h2>Validation and result contract</h2>
 *
 * <p>Assumptions: Bean Validation annotations are the sole executable authority for required
 * values, the selector's shape and the two-value action domain; a compact constructor
 * does not duplicate those checks as binding exceptions would lose field-level validation
 * context. The test channel must prove that {@code F} and {@code R} are accepted, {@code S} is
 * rejected, that a raw composite key offered as a selector is refused, and that a selector issued for
 * a row recovers that row's {@code authorization.pending_auth_detail} primary key unchanged.
 *
 * <p><strong>Return value.</strong> This declaration is an input value type and declares no
 * operation with a return value.
 *
 * @param authorizationId the opaque sealed selector for the row being marked, as published for that row
 *     by {@code PendingAuthSummaryResponse} and as the baseline holds it in
 *     {@code CDEMO-CPVS-AUTH-KEYS(n)} at {@code cbl/COPAUS0C.cbl} L545 onward; it carries the account
 *     identifier and the two authorization key parts, sealed, and is refused unless it has the sealed
 *     token shape
 * @param action the one-character {@code WS-FRD-ACTION PIC X(01)} command at
 *     {@code cbl/COPAUS2C.cbl} L80: {@code F} means {@code WS-REPORT-FRAUD} at L81 and {@code R}
 *     means {@code WS-REMOVE-FRAUD} at L82; it is distinct from adjacent
 *     {@code WS-FRD-UPDATE-STATUS}, where {@code F} means update failed at L85
 */
public record FraudMarkRequest(
        @NotNull @Size(max = CursorToken.MAX_TOKEN_LENGTH) @SealedRowSelector String authorizationId,
        @NotNull @Pattern(regexp = ACTION_DOMAIN) String action) {

    /**
     * The two commands the baseline's fraud action admits, one character each.
     *
     * <p>Assumptions: this domain is closed by the two condition names at
     * {@code app/app-authorization-ims-db2-mq/cbl/COPAUS2C.cbl} L81 and L82 and by nothing else, so a
     * third character is not an unhandled case but a value the reference program cannot represent.
     */
    private static final String ACTION_DOMAIN = "[FR]";

    /**
     * Constrains a component to the sealed-token shape the shared kernel issues.
     *
     * <p>Assumptions: this is nested inside the record it constrains, for the same reason the sibling
     * amount constraint is: the package charter names six record types as its complete set, and this
     * annotation has exactly one subject.</p>
     *
     * <p>Assumptions: the shape rule itself is NOT restated here. It is
     * {@code CursorToken.hasSealedShape}, published by the shared kernel beside the sealing it
     * validates, so this boundary cannot come to accept a shape the sealer no longer produces.</p>
     */
    @Documented
    @Constraint(validatedBy = FraudMarkRequest.SealedRowSelectorValidator.class)
    @Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
    @Retention(RetentionPolicy.RUNTIME)
    public @interface SealedRowSelector {

        /**
         * The message reported when the value is not a sealed selector.
         *
         * @return the violation message
         */
        String message() default "authorizationId must be the opaque row selector published for the"
                + " row, not a key assembled by the caller";

        /**
         * The validation groups this constraint belongs to.
         *
         * @return the groups, empty by default so the constraint is always applied
         */
        Class<?>[] groups() default {};

        /**
         * The payload types a client may attach to this constraint.
         *
         * @return the payload types, empty by default
         */
        Class<? extends Payload>[] payload() default {};
    }

    /**
     * Applies the sealed-token shape on behalf of {@link SealedRowSelector}.
     *
     * <p>Trade-offs: only the SHAPE is judged here and not the authentication code. Verifying the code
     * needs the key material, which belongs to the service layer; judging the shape at the validation
     * boundary is what turns a caller-assembled key -- the failure this constraint exists for -- into a
     * per-field rejection rather than an exception raised later in a service method.</p>
     */
    public static final class SealedRowSelectorValidator
            implements ConstraintValidator<SealedRowSelector, String> {

        /**
         * Creates the validator.
         *
         * <p>Assumptions: the specification requires a public no-argument constructor, which the
         * validation provider invokes reflectively. It is written out rather than left implicit because
         * the documentation gate requires a docstring on every constructor.</p>
         */
        public SealedRowSelectorValidator() {
            // Assumptions: empty by design. This validator holds no configuration, so there is nothing
            // to initialise from the annotation instance.
        }

        /**
         * Judges one selector against the sealed-token shape.
         *
         * @param candidate the selector supplied, which may be {@code null}
         * @param context the constraint context, unused because the default message names the fault and
         *     no per-value detail could be added without quoting a value that may be a raw key
         * @return {@code true} when the value is {@code null} or carries the sealed shape, {@code false}
         *     otherwise
         */
        @Override
        public boolean isValid(String candidate, ConstraintValidatorContext context) {
            // WHY : Assumptions: a null passes, because requiredness is the separate concern of the
            // annotation beside this one on the same component. A constraint that also refused null
            // would report two violations for one omission.
            return candidate == null || CursorToken.hasSealedShape(candidate);
        }
    }
}
