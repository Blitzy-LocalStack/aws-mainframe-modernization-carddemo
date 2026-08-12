package com.carddemo.account.dto;

/**
 * The nine customer fields a neighbouring context renders on a screen, and nothing else.
 *
 * <h2>Why this projection exists</h2>
 *
 * <p>Refactoring Rationale: the pending-authorization detail screen needs a cardholder's name, a postal
 * address and a telephone number, and it had no way to obtain them. Its client read them from the response of
 * {@code POST /api/v1/customers/lookup}, which is the EXISTENCE check -- an operation that answers 204 or 404
 * and carries no body at all by contract, for a documented reason: a problem document would return a customer
 * identifier to a caller and into a log. The client's own seam record additionally declared a
 * {@code customerName} member that no operation on this contract publishes and no column exists for. So every
 * display field on that screen rendered as absent, and nothing failed while it did.</p>
 *
 * <p>Alternatives Considered: having the consumer read the body-bearing {@code POST /api/v1/customers/record}
 * instead, which needs no new type here. Rejected on least privilege. That operation answers with the WHOLE
 * customer record, so it is gated on
 * {@code com.carddemo.common.security.InternalServiceToken#SCOPE_CUSTOMER_MASTER_READ} -- an authority
 * deliberately granted to no context, because the difference between confirming one row and reading a
 * national identifier, a government-issued identifier and a credit score for any customer is not a difference
 * of degree. Pointing the screen at that operation would have required minting that scope for a context that
 * renders nine fields, which is exactly the escalation the scope split was introduced to prevent. This
 * projection is served under the narrower decision-read scope the consumer already holds.</p>
 *
 * <p>Alternatives Considered: leaving the field list in the consumer, as its own rationale argued -- that a
 * dedicated endpoint "would put a screen's field list into another service's public contract, so a change to
 * this screen would require a change there". Rejected because the alternative it defended does not work: the
 * fields have to cross the boundary somehow, and the only shapes available were a bodiless response, which
 * carries nothing, and the whole record, which carries too much. A narrow published projection makes the
 * exposure a decision this context takes and can refuse, which is what a bounded context owning the customer
 * master is for. The cost is real and is accepted: widening this screen later needs a change here.</p>
 *
 * <h2>Why the address arrives as five components and not as two rendered lines</h2>
 *
 * <p>Refactoring Rationale: this projection carried only the first two address lines when it was introduced,
 * and that was too narrow to render the screen it exists for. The consumer's second address line is not
 * {@code CUST-ADDR-LINE-2}: {@code app/app-authorization-ims-db2-mq/cbl/COPAUS0C.cbl} composes its first line
 * at L766 through L770 from {@code CUST-ADDR-LINE-1} and {@code CUST-ADDR-LINE-2}, and its SECOND line at L771
 * through L777 from {@code CUST-ADDR-LINE-3}, {@code CUST-ADDR-STATE-CD} and the first five characters of
 * {@code CUST-ADDR-ZIP}. Three of those five columns were absent here, so the screen's second address line
 * could not be composed with parity from anything this contract published. They are published now.</p>
 *
 * <p>Assumptions: the address arrives as its stored COMPONENTS rather than as the two lines the screen shows,
 * for the same reason the name does. Which separator joins a city to a state, and whether a ten-character
 * postal code narrows to five, are decisions of the screen that reads them; taking them here would fix one
 * consumer's presentation inside the context that owns the column, on behalf of a screen this context cannot
 * see. The consumer composes, which is what the reference program does with the same five fields.</p>
 *
 * <p>Assumptions: the postal code is published at its STORED width of ten characters, from
 * {@code CUST-ADDR-ZIP PIC X(10)} at L14 of {@code app/cpy/CVCUS01Y.cpy}, and is NOT narrowed to the five the
 * screen renders. The reference narrows at the point of display -- {@code CUST-ADDR-ZIP(1:5)} at L775 of
 * {@code COPAUS0C} -- so narrowing here would publish the screen's truncation as though it were the layout,
 * and would silently discard the four-digit extension for any later reader.</p>
 *
 * <p>Assumptions: the country code at L13 is deliberately ABSENT even though it sits between the two published
 * address components in the record. No line of the screen renders it -- neither composition at L766 nor at
 * L771 reads it -- and this projection publishes the field list one screen needs rather than the contiguous
 * span of the record those fields happen to occupy.</p>
 *
 * <p>Assumptions: the NAME arrives as its three stored components rather than as one composed string. The
 * customer record declares {@code CUST-FIRST-NAME PIC X(25)} at L6 of {@code app/cpy/CVCUS01Y.cpy},
 * {@code CUST-MIDDLE-NAME PIC X(25)} at L7 and {@code CUST-LAST-NAME PIC X(25)} at L8, and declares no
 * composed name anywhere; composing one here would publish a value the record does not hold and would fix the
 * join -- which separator, and whether an absent middle name collapses the spacing -- inside this context on
 * behalf of a screen this context cannot see. The consumer composes it, which is what the reference programs
 * do with the same three fields.</p>
 *
 * <p>Assumptions: no protected value appears here at all, so nothing in this record needs masking. The
 * national identifier at L17 and the government-issued identifier at L18 are absent rather than masked, and
 * the credit score is absent as well -- the screen renders none of the three, and a masked member is still a
 * member a future consumer would start reading.</p>
 *
 * <p>Assumptions: the second address line and the middle name are the two members that may legitimately be
 * absent, because the reference declares both as ordinary fixed-width fields a screen may leave blank, and
 * {@code app/cbl/COACTUPC.cbl} edits the FIRST address line for presence at L1824 while requiring nothing of
 * the second. They are therefore published as nullable, and the seven remaining members are not -- the third
 * address line, the state code and the postal code are all declared {@code NOT NULL} by the schema this
 * context owns, so publishing them as nullable would describe a row this service cannot store.</p>
 *
 * @param firstName the cardholder's given name, {@code CUST-FIRST-NAME PIC X(25)}; never {@code null} in a
 *     response this service produces
 * @param middleName the cardholder's middle name, {@code CUST-MIDDLE-NAME PIC X(25)}, or {@code null} when
 *     the stored field is blank
 * @param lastName the cardholder's family name, {@code CUST-LAST-NAME PIC X(25)}; never {@code null} in a
 *     response this service produces
 * @param addressLine1 the first address line, {@code CUST-ADDR-LINE-1 PIC X(50)}; never {@code null} in a
 *     response this service produces
 * @param addressLine2 the second address line, {@code CUST-ADDR-LINE-2 PIC X(50)}, or {@code null} when the
 *     stored field is blank
 * @param addressLine3 the third address line, which carries the city, {@code CUST-ADDR-LINE-3 PIC X(50)};
 *     never {@code null} in a response this service produces
 * @param stateCode the two-character state code, {@code CUST-ADDR-STATE-CD PIC X(02)}; never {@code null} in
 *     a response this service produces
 * @param zipCode the postal code at its STORED width of ten characters, {@code CUST-ADDR-ZIP PIC X(10)},
 *     unnarrowed; never {@code null} in a response this service produces
 * @param phoneNumber1 the primary telephone number at its stored width of fifteen characters,
 *     {@code CUST-PHONE-NUM-1}; never {@code null} in a response this service produces
 */
public record CustomerDisplayView(String firstName, String middleName, String lastName,
        String addressLine1, String addressLine2, String addressLine3, String stateCode,
        String zipCode, String phoneNumber1) {
}
