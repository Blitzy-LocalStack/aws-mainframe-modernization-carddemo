package com.carddemo.account.dto;

/**
 * Response wire contract of the card cross-reference lookups this context publishes, one instance per
 * cross-reference row.
 *
 * <h2>What this record is</h2>
 *
 * <p>Purpose. Each instance describes one row of the card cross-reference, derived field for field from
 * {@code app/cpy/CVACT03Y.cpy}, whose group item {@code 01 CARD-XREF-RECORD} is declared at L4 and whose own
 * header at L2 states the record length as 50. Two access paths answer with this shape: the lookup of a
 * single cross-reference by the card it is keyed on, and the lookup by account that stands in for the
 * alternate index the reference region surfaces as {@code CXACAIX}. The second of those yields zero or more
 * rows, which is why this record describes a row rather than a whole response body. It carries transport
 * representation and nothing else: no business rule, no row access, and no knowledge that a database
 * exists.</p>
 *
 * <p>Every component is text. Two of the three are drawn from fields declared with numeric pictures, and the
 * paragraph below records why they are still text rather than numbers.</p>
 *
 * <h2>Where each component comes from</h2>
 *
 * <p>Assumptions: all three components are declared as text and constrained to digits or characters, because
 * the reference treats these values as characters on the wire and as numbers only inside arithmetic.
 * {@code app/cbl/COACTUPC.cbl} holds each such value as a character field with a numeric {@code REDEFINES}
 * laid over it: the account identifier at L671 is {@code PIC X(11)} redefined at L672 to L673 as
 * {@code PIC 9(11)}, and the customer identifier at L710 is {@code PIC X(09)} redefined at L711 to L712 as
 * {@code PIC 9(09)}. The cross-reference key itself is handled the same way at L376 to L380, where
 * {@code WS-XREF-RID} declares {@code WS-CARD-RID-CARDNUM PIC X(16)} and
 * {@code WS-CARD-RID-CUST-ID PIC 9(09)} and then lays a character {@code REDEFINES} over the numeric one. A
 * numeric component would also discard a leading zero, and both identifiers are fixed-width keys in which a
 * leading zero is part of the value rather than decoration on it.</p>
 *
 * <p>Assumptions: {@code FILLER PIC X(14)} at {@code app/cpy/CVACT03Y.cpy} L8 has no counterpart component
 * here, because it is padding to a fixed physical record length rather than data a row carries. Arithmetic
 * settles both that the three components are the whole of the record and that nothing has been invented: 16
 * plus 9 plus 11 is 36, and 36 plus the dropped 14 is 50, exactly the record length the copybook's own header
 * states at L2. A second file agrees independently. {@code app/cbl/CBACT03C.cbl} describes the same record as
 * {@code FD-XREF-CARD-NUM PIC X(16)} at L39 followed by {@code FD-XREF-DATA PIC X(34)} at L40, where 16 plus
 * 34 is likewise 50 and that 34 is precisely the 9 and the 11 of the two identifiers plus the 14 of the
 * dropped padding.</p>
 *
 * <h2>The card number is this record's key, and it travels masked</h2>
 *
 * <p>Assumptions: the card number is this record's own key and the account identifier is not.
 * {@code app/cbl/CBACT03C.cbl} declares {@code RECORD KEY IS FD-XREF-CARD-NUM} at L32 for the file whose
 * layout it copies in at L45 with {@code COPY CVACT03Y.}, so the cross-reference is keyed on the card number
 * in the reference system. The lookup by account is the alternate access path, surfaced to the region as
 * {@code CXACAIX} and replaced in the migration by the non-unique secondary index
 * {@code idx_card_xref_account_id}. This is written down because the by-account lookup is reached from an
 * account context, and a reader arriving that way would otherwise take the account identifier for the key and
 * expect a single row where the contract yields zero or more.</p>
 *
 * <p>Trade-offs: the card number reaches a caller masked to its last four digits, and this record offers no
 * unmasked variant. {@code XREF-CARD-NUM} at {@code app/cpy/CVACT03Y.cpy} L5 is a whole
 * {@code PIC X(16)} primary account number, and a response body is a value a caller may log, cache or
 * forward onward. What the mask costs is real and is accepted: a caller cannot reconcile a returned row
 * against a whole card number it does not already hold. The administrative path that answers with an unmasked
 * primary account number sits in the card bounded context rather than in this one, so adding an unmasked
 * component here would put one disclosure decision in two contexts with no single place to read it. The mask
 * is applied where every representation concern in this context is applied, in
 * {@code com.carddemo.account.mapper}, and never inside this record. This record also carries no card
 * verification value, because {@code app/cpy/CVACT03Y.cpy} declares none across L5 to L7 and no endpoint in
 * this migration answers with one.</p>
 *
 * <h2>Paging is by key, and this record does not know the envelope</h2>
 *
 * <p>Alternatives Considered: addressing a page by its ordinal position in the result, counting rows from the
 * start of it on every request, was evaluated and rejected in favour of paging by key. Under concurrent
 * insertion the two are not equivalent: a row inserted ahead of the reader's position shifts every later row
 * by one, so the next request re-serves a row the caller has already seen and passes over one it has not.
 * {@code app/cbl/COBIL00C.cbl} shows that such an insertion is real rather than hypothetical, deriving the
 * next transaction identifier at L212 to L217 by positioning at {@code HIGH-VALUES}, reading the previous
 * record and then adding one, with no lock held across that read and the write that follows it, so a new key
 * can land between two of a reader's requests. Paging by key has no such failure mode, and the reference
 * already pages that way: {@code app/cbl/COCRDLIC.cbl} carries a last-key pair at L230 to L232, a first-key
 * pair at L233 to L235, a screen counter at L237, a last-page-displayed flag at L239 whose conditions read 0
 * for shown at L240 and 9 for not shown at L241, and a next-page indicator at L242 to L244 established by
 * discovering one record beyond the seven a screen holds, declared at L177 to L178. Note the polarity of that
 * flag, which is the reverse of the reading a newcomer expects.</p>
 *
 * <p>Alternatives Considered: declaring the keyset envelope inside this record, so that one type carried both
 * a row and its cursor, was rejected. {@code PageResponse} is parameterised over this record at the
 * controller and service signatures instead, which is where the by-account read is turned into a page;
 * {@code CardXrefRepository} answers that read with a plain list and says so in its own charter, keeping the
 * paging decision one layer above itself. Depending on the envelope from here would invert that dependency
 * and pull a web concern into a shape whose whole job is to describe a row, and it would leave the
 * single-row lookup carrying cursor components that could only ever be empty. This record therefore names
 * that envelope without depending on it, so a reader can find the paging contract from here while this file
 * declares no dependency on it whatsoever.</p>
 *
 * <h2>What this record deliberately does not carry</h2>
 *
 * <p>Assumptions: this record carries no monetary amount and so has no dependency on the shared money type.
 * {@code app/cpy/CVACT03Y.cpy} declares no {@code S9(n)V99} field in any of L5, L6 or L7. The money of this
 * bounded context sits on the account record instead, at {@code app/cpy/CVACT01Y.cpy} L7
 * {@code ACCT-CURR-BAL}, L8 {@code ACCT-CREDIT-LIMIT}, L9 {@code ACCT-CASH-CREDIT-LIMIT}, L13
 * {@code ACCT-CURR-CYC-CREDIT} and L14 {@code ACCT-CURR-CYC-DEBIT}, all five of them declared
 * {@code PIC S9(10)V99}. The absence is recorded so that it reads as a property of the cross-reference record
 * rather than as an oversight, and so that no later edit reaches for an amount type here on the assumption
 * that one was forgotten. Were an amount ever to arrive in this context it would travel as a quoted string
 * through the shared type and never as a JSON number, because most clients parse a JSON number into an
 * IEEE-754 binary floating point value, which cannot hold twelve significant digits exactly.</p>
 *
 * <p>Alternatives Considered: answering the by-account lookup with {@code CardXrefView}, the by-card
 * projection already published in this package, was evaluated and does not fit. That shape omits the card
 * number deliberately, on the ground that a caller looking a card up by its number already holds the value
 * and an echo would only add a second place it exists. The by-account lookup inverts exactly that: its caller
 * supplies an account and does not know which cards resolve to it, so a row that failed to name its own card
 * would be unusable, and because that name is a primary account number it is named in masked form. The two
 * shapes are complementary rather than redundant, and folding either into the other would leave one of the
 * two callers guessing.</p>
 *
 * <h2>Why a record, and why the widths are documented rather than enforced</h2>
 *
 * <p>Alternatives Considered: a class with generated accessors was weighed against a Java 21 {@code record},
 * and the record is used. No accessor-generation library is a dependency of this module, for a mechanical
 * reason rather than a stylistic one: {@code config/checkstyle/checkstyle.xml} configures
 * {@code MissingJavadocMethod} with {@code allowMissingPropertyJavadoc} false at L358 and clears
 * {@code allowedAnnotations} at L359, so an accessor must carry a docstring of its own, and a generated
 * member has nowhere to hold one. The Explainability rule's presence clause at its L15 grants no exemption
 * either. A record's canonical accessors are documented instead by the type-level parameter tags below, which
 * {@code JavadocType} requires in full because {@code allowMissingParamTags} is false at L413 and
 * {@code MissingJavadocType} lists {@code RECORD_DEF} among its tokens at L310 to L311. A mapping generator
 * was rejected on the same footing for a different reason: the translation into this shape is not mechanical,
 * since it drops the padding recorded above and masks a primary account number, and each of those needs its
 * justification written beside it at the point of use.</p>
 *
 * <p>Trade-offs: the declared widths recorded on the tags below are documented constraints rather than
 * rejections this record performs, and no compact constructor is written to enforce them. The reference pads
 * a short value to its fixed width instead of refusing it, since {@code XREF-CARD-NUM} at
 * {@code app/cpy/CVACT03Y.cpy} L5 is a fixed {@code PIC X(16)} field and not a maximum, so a length
 * rejection here would refuse values the reference accepts. The compromise accepted is that a caller reads a
 * width from its tag rather than having it enforced at construction. That costs little in practice, because
 * validation of an inbound value is the request contract's responsibility and the widths of a row this
 * service produces are already fixed by the column it was read from.</p>
 *
 * @param cardNumberMasked the card this cross-reference row is for, masked to its last four digits, from
 *     {@code XREF-CARD-NUM} at {@code app/cpy/CVACT03Y.cpy} L5, declared {@code PIC X(16)} and so sixteen
 *     characters wide in the reference record; this is the row's key, and the value is never the whole
 *     primary account number
 * @param customerId the customer the card is registered to, digits only, from {@code XREF-CUST-ID} at
 *     {@code app/cpy/CVACT03Y.cpy} L6, declared {@code PIC 9(09)} and so nine digits wide, with any leading
 *     zero significant because the width is fixed rather than a maximum
 * @param accountId the account the card draws on, digits only, from {@code XREF-ACCT-ID} at
 *     {@code app/cpy/CVACT03Y.cpy} L7, declared {@code PIC 9(11)} and so eleven digits wide, with any
 *     leading zero significant on the same ground
 */
public record CardXrefResponse(String cardNumberMasked, String customerId, String accountId) {

    /**
     * Renders this projection for a log line, carrying only the value already masked.
     *
     * <p>Refactoring Rationale: a record's generated rendering prints every component, so this type
     * emitted the customer identifier and the account identifier in full wherever an instance reached a
     * diagnostic -- and one reaches a diagnostic on every serialisation or validation failure the
     * framework reports. The sensitive-data logging contract in
     * {@code docs/architecture/observability.md} names both identifiers among its prohibited values and
     * states that a prohibited value is OMITTED rather than abbreviated, so the generated form was a
     * disclosure and neither identifier is shortened here -- both are gone.</p>
     *
     * <p>Trade-offs: what survives is the card-number component, and it survives because the value it
     * holds is already masked to its last four digits by the mapping layer before it ever reaches this
     * record. That is the one sanctioned abbreviation the same contract allows, and allowing it here
     * applies no new rule: this record neither masks nor unmasks anything. The cost is that a log line
     * can no longer say which customer or which account a cross-reference row links, which is what the
     * correlation identifier on every request-scoped line is for.</p>
     *
     * <p>Assumptions: the marker text is not restated here and the component is emitted as it stands. A
     * literal repeated beside the value it describes is a second statement of the masking rule, and two
     * statements of one rule are how the two come to disagree.</p>
     *
     * @return a rendering naming the type and the already-masked card number, and neither identifier,
     *     never {@code null}
     */
    @Override
    public String toString() {
        return "CardXrefResponse[cardNumberMasked=" + this.cardNumberMasked + ']';
    }
}
