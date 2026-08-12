package com.carddemo.transaction.dto;

/**
 * The two shapes a transaction-capture submission can be answered with, as one closed type.
 *
 * <p><b>Purpose.</b> {@code app/cbl/COTRN02C.cbl} answers a submission in one of two materially
 * different ways, and {@code openapi/transaction-api.yaml} publishes both as CLOSED objects with
 * different member sets. A turn that wrote answers with {@code TransactionCreated} -- the assigned
 * identifier, the normalised amount and the baseline's success sentence. A turn that did not write
 * answers with {@code TransactionAddPreview} -- the normalised amount, the discriminator fixed false,
 * and the prompt asking for a confirmation. This interface is what lets the service decide between
 * them, since the decision is the reference program's own evaluation at lines 169 to 188 and belongs in
 * the service layer.</p>
 *
 * <p>⚠️ Refactoring Rationale: this type exists because one record was being used for both shapes and
 * the preview body a caller received was invalid against the contract that describes it. The service
 * returned {@link TransactionAddResponse} on the unconfirmed turn with its identifier left null, and
 * the published preview schema declares no {@code transactionId} at all while setting
 * {@code additionalProperties: false}, so a strict client rejected it; it also omitted the
 * {@code written} member that schema marks required, which is the one member a caller reading the body
 * alone uses to tell a prompt from a capture. Making the two shapes two types is what makes each closed
 * object satisfiable. Widening the preview schema to admit a null identifier was rejected because it
 * would publish a body that carries the capture shape while naming no capture, which is exactly the
 * ambiguity the {@code written} discriminator exists to remove.
 *
 * <p>Alternatives Considered: two service methods, one per shape, with the controller choosing between
 * them. Rejected because the choice IS a business rule: {@code app/cbl/COTRN02C.cbl} evaluates the
 * confirmation at line 169 with three arms, and which arm a submission takes is transcribed from the
 * program rather than derivable from the request by a controller. Moving that decision into the API
 * layer would move a transcribed rule out of the layer this module's architecture rules place it in.
 *
 * <p>Alternatives Considered: an unsealed interface, or no common type with the service returning
 * {@code Object}. Rejected because sealing makes the set of shapes checkable: a third answer cannot be
 * added without naming it here, and a caller switching over the two gets an exhaustiveness guarantee
 * from the compiler rather than a default arm that can only guess.
 *
 * <p>Assumptions: the interface declares exactly one member, the discriminator, and the two shapes
 * spell it differently from the bill-payment pair on purpose. This operation's contract names it
 * {@code written} because a transaction is written, where a payment is {@code paid}; both words come
 * from their own published schema and neither is normalised to the other.
 *
 * <p>Assumptions: no serialisation annotation appears on this interface. Jackson serialises the runtime
 * type of the value a controller returns, so a preview serialises as a preview and a capture as a
 * capture with no type information written into either body -- which is what keeps both closed objects
 * satisfiable. A type-info annotation would inject a discriminator property neither schema admits.
 *
 * @see TransactionAddPreview the shape a turn that wrote nothing is answered with
 * @see TransactionAddResponse the shape a turn that wrote is answered with
 */
public sealed interface TransactionAddOutcome
        permits TransactionAddPreview, TransactionAddResponse {

    /**
     * Reports whether a transaction was written on the turn this outcome answers.
     *
     * <p>Assumptions: this is read to choose the HTTP status and, on a capture, to address the written
     * row. It is deliberately the only member in common: a caller that needs the identifier has to
     * establish which shape it holds first, which the sealed hierarchy lets it do exhaustively.</p>
     *
     * @return {@code true} when a transaction was written, which is fixed on
     *     {@link TransactionAddResponse}, and {@code false} when none was, which is fixed on
     *     {@link TransactionAddPreview}
     */
    boolean written();
}
