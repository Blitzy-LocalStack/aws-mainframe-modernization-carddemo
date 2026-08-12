package com.carddemo.account.dto;

/**
 * The response body of the ACCOUNT-keyed card cross-reference lookup: the row the account resolves to.
 *
 * <h2>Why this shape differs from the card-keyed one</h2>
 *
 * <p>Refactoring Rationale: the account-keyed operation used to answer with {@link CardXrefView}, the same
 * two-identifier projection the CARD-keyed operation answers with, and that was the wrong shape for it. The
 * two operations are keyed from opposite ends, so what is redundant in one is the whole point of the other:
 * a card-keyed caller supplied the card number and needs the account and customer it resolves to, while an
 * account-keyed caller supplied the account and needs the CARD. Publishing the narrower shape on both left
 * the account-keyed consumer with no way to learn the one value it asked for, and its own seam record
 * declared a {@code cardNumber} member that no response could ever populate.</p>
 *
 * <p>Purpose: the value that member has to carry is the reference's own. {@code READ-CXACAIX-FILE} at lines
 * 576 to 604 of {@code app/cbl/COTRN02C.cbl} reads the alternate index by account identifier at line 582 and
 * takes {@code XREF-CARD-NUM} from the record it receives, which becomes the card number the posted
 * transaction is written under; {@code app/cbl/COBIL00C.cbl} performs the same read at line 414 for the bill
 * payment it then writes. Both are transcribed in {@code transaction-service}, and neither transcription can
 * produce a transaction at all without this field.</p>
 *
 * <p>Trade-offs: the card number is carried IN FULL and is not masked to its last four digits, which is the
 * opposite of what {@link CardXrefResponse} does with the same column. The two are different contracts for
 * different audiences and the difference is deliberate. {@link CardXrefResponse} is the end-user page a
 * browser renders, where a whole primary account number has no reader and every reason not to travel; this
 * record answers a machine operation whose consumer writes the value into a ledger row as a key, so a masked
 * value would not merely be less useful -- it would be a different key, and the transaction would be written
 * against a card that does not exist. What limits the exposure instead is reachability: this operation lives
 * beneath the prefix {@code SecurityConfig.CARD_XREF_PATH_PATTERN} gates on a machine token, the public
 * gateway publishes no route to it, and the load balancer forwards it only from inside the network.</p>
 *
 * <p>Assumptions: the card number is a {@code String} of digit characters and not a numeric type, matching
 * {@code XREF-CARD-NUM PIC X(16)} at L5 of {@code app/cpy/CVACT03Y.cpy} -- the reference declares it as
 * CHARACTERS, a leading zero is significant in it, and sixteen digits exceed what a signed 32-bit integer
 * can hold. The two identifiers stay boxed {@link Long} for the reason recorded on {@link CardXrefView}: a
 * member absent from an inbound document has to be representable, because a consumer that cannot tell an
 * absent identifier from a zero one cannot report an incomplete body as the dependency failure it is.</p>
 *
 * <p>Alternatives Considered: adding the card number to {@link CardXrefView} and letting both operations
 * share one widened shape. Rejected because it would put a primary account number into the card-keyed
 * response, whose caller already holds the value and gains nothing from the echo -- so the change would have
 * created a second document carrying a PAN in exchange for no information, which is the exact trade
 * {@link CardXrefView} was written to avoid. Alternatives Considered: having the account-keyed consumer call
 * the paged walk beside it and take the first row. Rejected because that walk is an end-user contract whose
 * projection masks the card number, so the consumer would receive a value it cannot key on, and because the
 * reference's access here is a single deterministic keyed read rather than a browse.</p>
 *
 * @param accountId the account the row is keyed by, {@code XREF-ACCT-ID PIC 9(11)}; never {@code null} in a
 *     response this service produces
 * @param customerId the customer the card belongs to, {@code XREF-CUST-ID PIC 9(09)}; never {@code null} in
 *     a response this service produces
 * @param cardNumber the selected card number in full, {@code XREF-CARD-NUM PIC X(16)}; never {@code null} in
 *     a response this service produces
 */
public record CardXrefByAccountView(Long accountId, Long customerId, String cardNumber) {
}
