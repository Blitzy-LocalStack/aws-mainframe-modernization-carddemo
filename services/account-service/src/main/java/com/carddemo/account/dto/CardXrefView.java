package com.carddemo.account.dto;

/**
 * The response body of the card cross-reference lookup: the two identifiers a card resolves to.
 *
 * <h2>Why the card number is not echoed</h2>
 *
 * <p>Assumptions: this record carries the account and customer identifiers and NOT the card number the
 * caller supplied. Echoing it would put a primary account number into a response body that a caller may
 * log, and the caller already holds the value -- it sent it -- so the echo would carry no information at the
 * cost of a second place the number exists. The stored row does hold all three, which is why this is a
 * projection of it rather than the row itself.</p>
 *
 * <p>Assumptions: both members are declared as boxed {@link Long} rather than as primitives, and the
 * boxing is load-bearing rather than incidental. This record is the shape a consumer deserialises into, so
 * a member absent from an inbound document has to be representable -- and the consumer's contract treats an
 * incomplete body as a dependency failure precisely because it can tell the difference. Primitives would
 * silently default a missing identifier to zero, which is a valid-looking account number.</p>
 *
 * <h2>Why these identifiers are numbers here and digit strings on the screen shape</h2>
 *
 * <p>⚠️ Assumptions: this shape and {@link CardXrefByAccountView} publish both identifiers as JSON NUMBERS
 * while {@link CardXrefResponse} publishes the same two columns as digit STRINGS, and a reader meeting the
 * two side by side is entitled to know that the difference is a decision. It is. These two shapes are read
 * by MACHINES -- {@code transaction-service} and {@code authorization-service} bind them, and the columns
 * they mirror are {@code BIGINT}, so a number is the representation that costs neither side a conversion.
 * {@link CardXrefResponse} is read by a BROWSER and mirrors a fixed-width screen field, so it carries the
 * display form: {@code XREF-ACCT-ID} is {@code PIC 9(11)} at line 7 of {@code app/cpy/CVACT03Y.cpy} and
 * {@code XREF-CUST-ID} is {@code PIC 9(09)} at line 6, and an unsigned display numeric is right-justified
 * and ZERO-filled.</p>
 *
 * <p>⚠️ Trade-offs: the numeric form does not carry the zero fill, and on this data that is not a hypothetical
 * loss -- {@code app/data/ASCII/acctdata.txt} numbers its fifty accounts {@code 00000000001} through
 * {@code 00000000050}, so the numeric form of every shipped account is one or two digits where the display
 * form is eleven. THE OBLIGATION THIS PLACES ON A CONSUMER IS EXPLICIT: a consumer that renders this value
 * back as characters must zero-fill it to the declared width. Both consumers now do --
 * {@code authorization-service}'s and {@code transaction-service}'s clients each format it to eleven digits
 * -- and the second of the two did not, which is why the obligation is recorded here rather than left to be
 * inferred from the column type.</p>
 *
 * <p>Alternatives Considered: publishing digit strings on these two shapes as well, so that one
 * representation crossed every address. Rejected, and not on taste: both members would then have to be
 * declared and validated as strings on two consumers whose seams are being changed by other work in this
 * same programme, and a published wire type is the one thing a consumer cannot absorb silently -- a strict
 * deserialiser answers a changed type with a failure, so the change would take both consumers down until
 * they were redeployed together. The representation difference is bounded, it is stated here and in the
 * published contract, and the obligation it creates is one line of formatting on each consumer.</p>
 *
 * <p>Assumptions: neither identifier is masked. Neither is cardholder data on its own: an account number in
 * this system is an eleven-digit internal key and a customer identifier a nine-digit one, and the migration
 * masks the primary account NUMBER rather than every numeric identity. Masking them would additionally make
 * the response useless, since the caller's next two calls are keyed on exactly these values.</p>
 *
 * @param accountId the account the card draws on, {@code XREF-ACCT-ID PIC 9(11)}; never {@code null} in a
 *     response this service produces
 * @param customerId the customer the card belongs to, {@code XREF-CUST-ID PIC 9(09)}; never {@code null} in
 *     a response this service produces
 */
public record CardXrefView(Long accountId, Long customerId) {

    /** Rendered in place of the two identifiers, so an absent field cannot be read as an empty one. */
    private static final String WITHHELD = "[REDACTED]";

    /**
     * Renders this projection for a log line, carrying neither identifier.
     *
     * <p>Purpose. A record's compiler-generated rendering prints every component, and both components here
     * are values {@code docs/architecture/observability.md} names among the ones a durable diagnostic may
     * not hold -- an account identifier and a customer identifier. That contract is about LOGGING and is
     * separate from what a response may carry: this projection publishes both identifiers deliberately,
     * because its caller's next two reads are keyed on exactly them, and the same values are still
     * withheld from a log line.</p>
     *
     * <p>Trade-offs: both are omitted rather than abbreviated. Abbreviating a protected value is masking,
     * and masking has one owner per value -- the primary account number has a shared masker and these two
     * identifiers have none, so a shortened form here would be inventing a second rule for a value that
     * has no masked shape. What is accepted is that this rendering does not say which row was translated;
     * the correlation identifier on every request-scoped line locates the event instead.</p>
     *
     * @return a rendering naming the type and recording that its identifiers were withheld, never
     *     {@code null}
     */
    @Override
    public String toString() {
        return "CardXrefView[identifiers=" + WITHHELD + ']';
    }
}
