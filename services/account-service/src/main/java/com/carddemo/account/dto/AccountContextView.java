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
}
