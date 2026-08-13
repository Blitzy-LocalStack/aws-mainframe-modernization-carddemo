package com.carddemo.account.dto;

import com.carddemo.common.money.Money;

/**
 * The response body of the account read on the account-context contract: the three amounts a decision needs.
 *
 * <h2>Why exactly three fields, and not the account record</h2>
 *
 * <p>Assumptions: this projection publishes the credit limit, the cash credit limit and the posted balance,
 * and nothing else -- no dates, no status, no postal code, no disclosure group. The set is derived from what
 * the one consumer reads: {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl} reads the account master
 * at its paragraph {@code 5200-READ-ACCT-RECORD} and uses these three fields to weigh the requested amount
 * against the limit. Publishing more would cross a context boundary with values that have no reader on the
 * other side, which is the state a later change quietly starts depending on.</p>
 *
 * <p>Assumptions: the account's ACTIVE STATUS is deliberately absent, and the absence is a decision rather
 * than an omission. The reference declares a decline reason for a closed account and never sets it, so a
 * status carried here could only be either ignored -- in which case it should not cross -- or acted on, in
 * which case this system would decline a request the reference approves. Rule T9 of the migration plan
 * settles which of those is wrong: no behavioural change ships unless it is registered as a documented
 * divergence, and this one is not registered because it is not made.</p>
 *
 * <p>Assumptions: no cardholder field is carried. A name or an address would cross with no consumer at all,
 * and the customer read on this same contract answers a presence question rather than returning a record for
 * the same reason.</p>
 *
 * <p>Assumptions: the three amounts are {@link Money}, so they serialise as JSON STRINGS. That is the whole
 * reason the type is {@code Money} rather than {@code BigDecimal}: a plain decimal would serialise as a JSON
 * number, and most clients parse a JSON number into a binary floating-point value that cannot retain every
 * two-decimal value exactly. The consumer of this document weighs one of these amounts against another to
 * decide an authorization, so an inexact hop would change outcomes at the boundary.</p>
 *
 * @param creditLimit the account's credit limit, {@code ACCT-CREDIT-LIMIT PIC S9(10)V99}; never {@code null}
 * @param cashCreditLimit the account's cash credit limit, {@code ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99};
 *     never {@code null}
 * @param currentBalance the account's posted balance, {@code ACCT-CURR-BAL PIC S9(10)V99}; never
 *     {@code null}
 */
public record AccountContextView(Money creditLimit, Money cashCreditLimit, Money currentBalance) {

    /** Rendered in place of the three amounts, so an absent field cannot be read as an empty one. */
    private static final String WITHHELD = "[REDACTED]";

    /**
     * Renders this projection for a log line, carrying none of its three amounts.
     *
     * <p>Purpose. A record's compiler-generated rendering prints every component, and every component of
     * this record is an amount: a credit limit, a cash credit limit and a posted balance. It reaches a log
     * without anyone writing it there -- a message conversion failure names the object it could not write,
     * and the shared advice renders the value it refused -- so the generated form would put an identified
     * account's whole financial position into a diagnostic.</p>
     *
     * <p>Trade-offs: the three are withheld TOGETHER rather than one being kept as context. There is no
     * reading on which a balance is safe to print and a limit is not, and the sibling projection this
     * context already publishes reaches the same disposition for the same five-amount grouping, so the two
     * agree rather than each deciding for itself. What is given up is any amount-shaped clue in a log line;
     * what remains is the correlation identifier on every request-scoped line, which locates the event
     * without naming a protected value.</p>
     *
     * <p>Assumptions: the token is a marker rather than an empty field, because an empty structured field
     * cannot be told apart from one the emitter failed to populate.</p>
     *
     * @return a rendering naming the type and recording that its amounts were withheld, never {@code null}
     */
    @Override
    public String toString() {
        return "AccountContextView[amounts=" + WITHHELD + ']';
    }
}
