package com.carddemo.account.dto;

import com.carddemo.common.security.CardNumberMasker;

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
 * <p>Refactoring Rationale: reachability is no longer the ONLY limit, and it was too wide on its own. Every
 * holder of the cross-reference read scope could provoke this disclosure, including the authorization
 * context, which calls the card-keyed lookup and has no use for a clear card number. This operation now
 * demands {@code InternalApiSecurityConfig.CARD_XREF_RESOLVE_AUTHORITY}, composed from
 * {@code InternalServiceToken.SCOPE_CARD_XREF_RESOLVE_CARD_NUMBER}, which the shared per-caller table grants
 * to the transaction context alone -- so the disclosure is bound to the one purpose that requires it rather
 * than to a network position shared by every internal caller. A review-suggested alternative was to answer
 * with a purpose-bound OPAQUE reference instead; it is recorded and declined under Alternatives Considered
 * below, because the value is PERSISTED as a parity-mandated key rather than merely read.</p>
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
 * <p>Alternatives Considered: answering with a purpose-bound OPAQUE reference -- a sealed handle the consumer
 * carries instead of the digits. Rejected because the consumer does not read this value, it PERSISTS it:
 * {@code transactions.card_num} is declared {@code CHAR(16)} and holds the reference's own
 * {@code TRAN-CARD-NUM PIC X(16)}, so a handle stored in its place would be a ledger key no golden-master
 * comparison could match and no reference program could produce. Exchanging the handle for the digits one
 * call later was the other form of the suggestion, and it discloses the same digits to the same caller while
 * adding a round trip and a second credential-bearing surface to protect. Alternatives Considered: moving the
 * ledger write into this context so the value never crosses a boundary at all. Rejected because the ledger
 * belongs to the transaction context under the migration plan's service boundaries, and relocating a write to
 * follow one field would put two contexts' data under one owner. The disclosure is therefore kept and bounded
 * -- by a scope granted to one caller, by a masked diagnostic rendering, and by a published contract that
 * carries an unmasked card number on this schema and on no other.</p>
 *
 * <p>⚠️ Assumptions: both identifiers are JSON NUMBERS on this shape and digit STRINGS on
 * {@link CardXrefResponse}, and the reasoning for that difference -- together with the zero-fill obligation
 * it places on a consumer that renders either value back as characters -- is recorded once on
 * {@link CardXrefView} rather than repeated here. It is recorded there because that shape is the other half
 * of the same machine-facing pair; a reader who arrives at this record first should follow the link, because
 * the obligation is real and one consumer of this very shape had not met it.</p>
 *
 * @param accountId the account the row is keyed by, {@code XREF-ACCT-ID PIC 9(11)}, published as a number so
 *     carrying no leading zero; never {@code null} in a response this service produces
 * @param customerId the customer the card belongs to, {@code XREF-CUST-ID PIC 9(09)}; never {@code null} in
 *     a response this service produces
 * @param cardNumber the selected card number in full, {@code XREF-CARD-NUM PIC X(16)}; never {@code null} in
 *     a response this service produces
 */
public record CardXrefByAccountView(Long accountId, Long customerId, String cardNumber) {

    /**
     * Renders this record for a log line or a diagnostic, disclosing neither the card number nor either
     * identifier.
     *
     * <p>Purpose. A record's compiler-generated rendering prints every component, so without this override
     * an instance of this type would emit a whole primary account number beside the account and customer it
     * belongs to -- which is the reference cross-reference row reproduced in plain text. It reaches a log on
     * paths nobody writes deliberately: a message conversion failure names the object it could not write, a
     * validation failure on an outbound body renders the value it rejected, and any framework diagnostic that
     * describes a handler's return value calls this method implicitly.</p>
     *
     * <p>Trade-offs: the card number is rendered MASKED, through the shared masker, rather than omitted
     * outright, while both identifiers are omitted. The asymmetry is deliberate on both sides. A rendering
     * with nothing identifying in it at all is of no diagnostic use, and the masked suffix is the one
     * abbreviation the migration's disclosure rule sanctions -- sections 0.4.1.9 and 0.7.8 of the plan allow
     * the last four digits of a card number everywhere. The identifiers get no such allowance: the
     * sensitive-data logging contract in {@code docs/architecture/observability.md} names account and
     * customer identifiers explicitly, and abbreviating one would be inventing a masking rule for a value
     * that has no masked form -- the mistake of applying a card-number rule to something that is not a card
     * number. What is accepted is that a suffix does not identify the row uniquely; the correlation
     * identifier on every request-scoped line locates the event instead.</p>
     *
     * <p>Assumptions: the masking is delegated to {@code CardNumberMasker} rather than written here, so this
     * rendering cannot disagree with the one the mapping layer publishes or with the ones the other contexts
     * emit. The shared class exists because a masking rule written out at each site is a rule that can
     * disagree with itself, and it had already begun to.</p>
     *
     * <p>Assumptions: an absent card number renders as the shared masker's own answer for one, which is
     * nothing rather than a marker, and no branch is written for it here. A rendering reached from a
     * diagnostic path must not fail, and this record is constructed by the mapping layer from a column the
     * migration declares not-null, so an absent value means the row itself was never populated -- a state
     * this rendering reports by carrying no digits rather than by raising inside a log call.</p>
     *
     * @return a single-line rendering naming the type and the masked card number, and neither identifier,
     *     never {@code null}
     */
    @Override
    public String toString() {
        return "CardXrefByAccountView[cardNumber=" + CardNumberMasker.mask(this.cardNumber) + ']';
    }
}
