package com.carddemo.reference.dto;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * One requested reference-data maintenance action, satisfying the contract schema
 * {@code MaintenanceAction}.
 *
 * <p>Purpose: an element of the maintenance batch body. It is the migrated form of one record of the
 * baseline's reference-update file, which the batch driver reads and applies one row at a time.</p>
 *
 * <p>Assumptions: the description is optional UNCONDITIONALLY on this shape and required CONDITIONALLY
 * by {@link DescriptionSuppliedForWrite}, because a delete needs none and the batch carries all three
 * action kinds in one stream. The baseline expresses exactly this: {@code TTYP-UPDATE-RECORD} reuses one
 * record layout for all three actions, so the description position is simply unused on a delete.
 * Requiring it unconditionally would refuse a well-formed delete.</p>
 *
 * <p>⚠️ Refactoring Rationale: the conditional requirement is enforced HERE, and it used to be deferred
 * to the service and reported as that action's own outcome under a 200. Two things were wrong with that.
 * The published contract already places the rule on this shape -- the {@code MaintenanceAction} schema
 * declares {@code required: [description]} under an {@code if} on the action being INSERT or UPDATE -- so
 * a caller reading the document expected a request-level refusal and received a success-shaped reply.
 * And the sentence that reply carried, 'Description is required...', was text this migration authored:
 * the baseline has no arm for the condition at all, {@code 10031-INSERT-DB} carrying only a zero arm at
 * line 152 and a negative arm at line 154, so there was nothing to carry across and transformation rule
 * T8 admits only text the baseline declares. Enforcing it here makes the refusal a 400 with a per-field
 * entry keyed {@code description}, which is transformation rule T7's per-field error and what a form can
 * render beside the input -- and the sentence it carries is the baseline's own blank-field wording.</p>
 *
 * <p>Alternatives Considered: three separate element shapes, one per action kind, were evaluated and
 * rejected. The contract declares a single element schema with an action discriminator, so three
 * shapes would satisfy no schema the document publishes, and a heterogeneous array would have to be
 * bound by a discriminator this document does not declare.</p>
 *
 * @param action the action kind, one of {@code INSERT}, {@code UPDATE} or {@code DELETE}
 * @param typeCd the two-character transaction type the action applies to
 * @param description the description an insert or an update supplies; absent on a delete, where the
 *     baseline record leaves the position unused
 */
@MaintenanceActionRequest.DescriptionSuppliedForWrite
public record MaintenanceActionRequest(
        @NotBlank @Pattern(regexp = "INSERT|UPDATE|DELETE") String action,
        @NotBlank @Size(min = 2, max = 2) @Pattern(regexp = "(?:0[1-9]|[1-9][0-9])") String typeCd,
        @Size(min = 1, max = 50)
        @Pattern(regexp = "[A-Za-z0-9 ]*[A-Za-z0-9][A-Za-z0-9 ]*") String description) {

    /**
     * The action name that selects an insert, as this shape's own pattern and the contract both spell it.
     *
     * <p>Assumptions: the three action names are declared HERE and read from here by
     * {@code ReferenceBatchUpdateService}, rather than the reverse. The rule below has to recognise two of
     * them, and a request shape must not import a service -- no shape in this migration does -- so the
     * vocabulary lives on the shape that publishes it and the service reads it. That also removes the
     * second copy of the three literals that otherwise sat beside this record's own action pattern.</p>
     */
    public static final String ACTION_INSERT = "INSERT";

    /** The action name that selects an update, as this shape's own pattern and the contract spell it. */
    public static final String ACTION_UPDATE = "UPDATE";

    /** The action name that selects a delete, as this shape's own pattern and the contract spell it. */
    public static final String ACTION_DELETE = "DELETE";

    /**
     * The verbatim sentence the refusal carries, from the reference tree's own blank-field wording.
     *
     * <p>Assumptions: reproduced character for character from lines 177 and 178 of
     * {@code app/app-transaction-type-db2/cbl/COTRTUPC.cbl}, measured at 17 characters, and already
     * published by this module's contract twice over -- once on the {@code ReferenceMessageCatalogue}
     * schema and once as the {@code FieldError} schema's own example, which shows exactly
     * {@code field: description, state: BLANK, message: No input received}. The example is therefore a
     * body this shape can now actually produce rather than an illustration of one it could not.</p>
     */
    public static final String MESSAGE_NO_INPUT_RECEIVED = "No input received";

    /**
     * Requires a description when the action writes one, and permits its absence when the action does not.
     *
     * <p>Alternatives Considered: an {@code @AssertTrue} predicate member on this record, which is the
     * shorter way to express a cross-member rule. Rejected for the reason the transaction context records
     * against its own key-selection rule: Bean Validation derives a violation's property path from the
     * METHOD a predicate annotates, so the entry a client received would name a synthetic property no
     * submitted field carries and no form control can bind to. The validator below suppresses the default
     * violation and raises one keyed {@code description}, so the entry names the member the caller
     * actually sent.</p>
     *
     * <p>Assumptions: declared on the type so that its violation joins the same accumulated set as every
     * member constraint and reaches the same per-field array. A check performed after binding would answer
     * on a separate path, so a submission with no description AND a malformed type code would produce two
     * response shapes instead of one.</p>
     *
     * <p>An annotation type declares no parameter, returns no value and raises nothing, so this block
     * carries no parameter, return or exception tag; the three members below carry their own.</p>
     */
    @Documented
    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    @Constraint(validatedBy = DescriptionSuppliedForWriteValidator.class)
    public @interface DescriptionSuppliedForWrite {

        /**
         * The sentence the raised violation carries.
         *
         * @return {@link MaintenanceActionRequest#MESSAGE_NO_INPUT_RECEIVED}, the baseline's own wording
         */
        String message() default MESSAGE_NO_INPUT_RECEIVED;

        /**
         * The validation groups this constraint belongs to.
         *
         * @return an empty array, because this request is validated in the default group only
         */
        Class<?>[] groups() default {};

        /**
         * The payload types a client may attach to a violation of this constraint.
         *
         * @return an empty array; no payload is attached, and the member exists because the platform's
         *     constraint contract requires it
         */
        Class<? extends Payload>[] payload() default {};
    }

    /**
     * Enforces {@link DescriptionSuppliedForWrite} and attributes the violation to the description.
     *
     * <p>Assumptions: the validator holds no state and reads nothing outside the value handed to it, so a
     * single instance is safe for the framework to share across requests.</p>
     */
    public static final class DescriptionSuppliedForWriteValidator
            implements ConstraintValidator<DescriptionSuppliedForWrite, MaintenanceActionRequest> {

        /**
         * Reports whether this action carries a description on the actions that need one.
         *
         * @param action the bound action, which the framework may pass as {@code null} when the element
         *     itself was absent
         * @param context the context a violation is raised through; never {@code null}
         * @return {@code true} when the action is a delete, when the action is unrecognised, or when a
         *     description was supplied; {@code false} only for an insert or an update carrying none
         */
        @Override
        public boolean isValid(MaintenanceActionRequest action, ConstraintValidatorContext context) {
            // WHY : Assumptions: an absent element is reported valid here rather than false, because its
            //       absence is a different failure with its own diagnostic and naming a field the caller
            //       never sent would be an entry it cannot act on.
            if (action == null) {
                return true;
            }
            // WHY : Assumptions: an UNRECOGNISED action is reported valid here, and that is not a gap.
            //       The action member carries its own pattern constraint, so an unrecognised value is
            //       already refused with an entry keyed 'action'; adding a second entry about a
            //       description would attribute the same one mistake to two fields, and a caller that
            //       corrected the description would still be refused.
            if (!ACTION_INSERT.equals(action.action()) && !ACTION_UPDATE.equals(action.action())) {
                return true;
            }
            // WHY : Assumptions: only a NULL description reaches this test in practice. A blank or
            //       whitespace-only value is already refused by the size and pattern constraints on the
            //       member, which is why this rule tests for absence rather than emptiness -- testing
            //       emptiness as well would raise a second entry for one mistake.
            if (action.description() != null) {
                return true;
            }

            // WHY : Assumptions: the default violation is suppressed and replaced by one keyed to the
            //       member, so the entry a client receives names a submitted field. Its rejected value is
            //       null, which is what makes the shared advice report the BLANK state rather than the
            //       not-acceptable one -- the distinction the reference's templated highlight draws at
            //       lines 17 to 27 of app/cpy/CSSETATY.cpy, where a never-supplied control additionally
            //       gets an asterisk. That is exactly the state the contract's own example publishes.
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate(
                            context.getDefaultConstraintMessageTemplate())
                    .addPropertyNode("description")
                    .addConstraintViolation();
            return false;
        }
    }
}
