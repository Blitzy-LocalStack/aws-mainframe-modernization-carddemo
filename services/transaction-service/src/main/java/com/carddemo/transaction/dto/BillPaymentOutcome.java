package com.carddemo.transaction.dto;

/**
 * The two shapes a bill-payment submission can be answered with, as one closed type.
 *
 * <p><b>Purpose.</b> {@code app/cbl/COBIL00C.cbl} answers a submission in one of two materially
 * different ways, and {@code openapi/transaction-api.yaml} publishes both as CLOSED objects with
 * different member sets. A turn that paid answers with {@code BillPaymentResponse} -- the assigned
 * transaction identifier, the balance that was paid and the discriminator fixed true. A turn that did
 * not pay answers with {@code BillPaymentPreview} -- no identifier at all, the payable balance under
 * its own member name, and the discriminator fixed false. This interface is what lets the service
 * decide between them, since the decision is the reference program's own evaluation order and belongs
 * in the service layer rather than in a controller.</p>
 *
 * <p>⚠️ Refactoring Rationale: this type exists because one record was being used for both shapes, and
 * the body a caller received was invalid against the contract that describes it. The service returned
 * {@code BillPaymentResponse} on every path, so a preview serialised {@code transactionId: null} --
 * which the published preview schema forbids outright, {@code additionalProperties} being false and
 * {@code transactionId} not being among its properties -- and reported the balance under
 * {@code currentBalance} where both the schema and {@code ui/src/api/transactions.ts} name it
 * {@code payableBalance}. A strict client rejected the preview body, and a lenient one read no balance
 * from it at all because it was looking for a member that was not there. Making the two shapes two
 * types is what makes each closed object satisfiable; the alternative of widening the schema to admit
 * a null identifier was rejected because it would publish a body that claims a payment shape while
 * naming no payment, which is exactly the ambiguity the {@code paid} discriminator exists to remove.
 *
 * <p>Alternatives Considered: declaring the two return types on two service methods and letting the
 * controller choose between them. Rejected because the choice IS a business rule: the reference
 * re-tests its error flag at lines 169, 197 and 208, and each re-test gates the block after it, so
 * which shape a submission earns depends on an evaluation order transcribed from the program. Putting
 * that decision in the API layer would move a transcribed rule out of the layer this module's
 * architecture rules place it in, and would oblige the controller to re-derive it from the request.
 *
 * <p>Alternatives Considered: an unsealed interface, or no common type at all with the service
 * returning {@code Object}. Rejected because sealing is what makes the set of shapes checkable: a
 * third answer cannot be added without naming it here, and a caller switching on the two shapes gets
 * an exhaustiveness guarantee from the compiler rather than a default branch that can only guess.
 *
 * <p>Assumptions: the interface declares exactly one member, and it is the discriminator both shapes
 * already publish. Both records carry a {@code paid} component whose value is fixed at its own
 * construction site, so the accessor is already present on each and this declaration adds no
 * implementation anywhere -- it only makes the member reachable without first deciding which shape is
 * in hand. Nothing else is declared, because the two shapes deliberately have no other member in
 * common: the balance is named differently on each, and only one of them has an identifier.
 *
 * <p>Assumptions: no serialisation annotation appears on this interface. Jackson serialises the
 * runtime type of the value a controller returns, so a preview serialises as a preview and a payment
 * as a payment with no type information written into either body -- which is what keeps both closed
 * objects satisfiable. Adding a type-info annotation would inject a discriminator property into both
 * bodies that neither published schema admits.
 *
 * @see BillPaymentPreview the shape a turn that paid nothing is answered with
 * @see BillPaymentResponse the shape a turn that paid is answered with
 */
public sealed interface BillPaymentOutcome permits BillPaymentPreview, BillPaymentResponse {

    /**
     * Reports whether money moved on the turn this outcome answers.
     *
     * <p>Assumptions: this is read to choose the HTTP status and, on a payment, to address the written
     * transaction. It is deliberately the only member in common: a caller that needs the identifier
     * has to establish which shape it holds first, which the sealed hierarchy lets it do exhaustively.
     * </p>
     *
     * @return {@code true} when a payment was written, which is fixed on {@link BillPaymentResponse},
     *     and {@code false} when none was, which is fixed on {@link BillPaymentPreview}
     */
    boolean paid();
}
