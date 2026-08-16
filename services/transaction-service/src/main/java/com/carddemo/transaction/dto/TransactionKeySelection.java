package com.carddemo.transaction.dto;

/**
 * The two key alternatives a capture submission may address an account by.
 *
 * <p><b>Purpose.</b> {@code VALIDATE-INPUT-KEY-FIELDS} at {@code app/cbl/COTRN02C.cbl} lines 193 to 230
 * is one paragraph reached from two arms of the screen's dispatch -- the Enter arm at lines 133 and 134
 * and the copy arm at line 473 -- and it reads exactly two fields: the account identifier at line 196 and
 * the card number at line 210, refusing at lines 224 to 229 when neither was filled in. Two request
 * shapes in this package therefore carry the same pair of members and the same rule about them, and this
 * interface is what lets ONE constraint validator decide it for both.
 *
 * <p>Refactoring Rationale: the alternative was a second class-level constraint annotation and a second
 * validator, one per record. Rejected because the rule is the reference's single paragraph: two copies of
 * it could disagree, and the copy they would disagree about is the one that decides whether a submission
 * carrying neither key is refused -- so a drift would let one operation write against a key it never
 * resolved. Bean Validation resolves a validator by assignability, so a validator declared over this
 * interface serves every record that implements it without either record naming it.
 *
 * <p>Assumptions: the interface declares the two key members and NOTHING else -- no confirmation, no data
 * member and no behaviour. Its whole subject is the pair the reference's key paragraph reads, and widening
 * it would make it a description of one of the two records rather than of the rule they share.
 *
 * <p>Assumptions: both members are nullable, and that is the rule rather than an oversight. Either may be
 * absent because they are ALTERNATIVES: the account arm resolves first and line 209 overwrites whatever
 * card number arrived beside it, so a submission carrying only an account is complete. What may not happen
 * is both being absent, which is the one condition the shared constraint refuses.
 */
public interface TransactionKeySelection {

    /**
     * The account identifier the submission was keyed on, or {@code null} when it was keyed on a card.
     *
     * @return the eleven-digit account identifier as text, or {@code null} or blank when not supplied
     */
    String accountId();

    /**
     * The card number the submission was keyed on, or {@code null} when it was keyed on an account.
     *
     * @return the sixteen-digit card number as text, or {@code null} or blank when not supplied
     */
    String cardNumber();
}
