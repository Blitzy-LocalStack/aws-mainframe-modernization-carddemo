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
}
