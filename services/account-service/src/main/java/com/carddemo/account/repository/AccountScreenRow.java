package com.carddemo.account.repository;

import com.carddemo.account.domain.Account;
import com.carddemo.account.domain.CardXref;
import com.carddemo.account.domain.Customer;

/**
 * One cross-reference row with the account and customer it names, read in a single statement.
 *
 * <p>Purpose: this carrier exists so that the three reads the account screen composes -- the
 * cross-reference, the account master and the customer master -- can be issued as ONE statement and
 * therefore observed under ONE snapshot. Each component is the entity that statement's corresponding side
 * yielded, or {@code null} when that side held no row.</p>
 *
 * <p>Refactoring Rationale: the three reads were three separate statements inside one read-only
 * transaction, and the rationale on the composing method claimed that made the account and the customer on
 * one screen mutually consistent. Under this datasource's default read-committed isolation it does not: a
 * statement takes its own snapshot, so a concurrent update committing between the second and third
 * statement yields an account from before it and a customer from after it -- a pairing that never existed
 * in the database, shown as though it had. One statement cannot exhibit that, because there is one
 * snapshot to exhibit.</p>
 *
 * <p>Alternatives Considered: raising the transaction to repeatable read instead, which is the other remedy
 * and would have kept three statements. Rejected because it buys the same guarantee at the cost of a wider
 * one -- every read in the transaction becomes snapshot-stable whether it needs to be or not -- and it
 * introduces a serialisation-failure outcome this operation would then have to answer for. A single
 * statement needs no isolation change at all, so no other read in this service is affected.</p>
 *
 * <p>Alternatives Considered: declaring JPA associations between the three entities so the join could be
 * navigated. Rejected because the schema declares no foreign key between the three tables -- the
 * cross-reference is deliberately readable without loading either record it points at -- so an association
 * would assert a referential guarantee the database does not enforce. The query composes the join with an
 * explicit predicate instead, which asserts nothing.</p>
 *
 * <p>Assumptions: the two nullable components are how the composing method still tells the three reference
 * outcomes apart. No row at all is the cross-reference miss; a row with no account is the account-master
 * miss; a row with an account and no customer is the customer-master miss, which is the one arm that still
 * publishes the account half.</p>
 *
 * @param crossReference the cross-reference row the account's lowest-ordering card names; never
 *     {@code null}, because a query yielding no such row yields no row at all
 * @param account the account master row that cross-reference names, or {@code null} when the master holds
 *     none
 * @param customer the customer master row that cross-reference names, or {@code null} when the master
 *     holds none or when the cross-reference names no customer
 */
public record AccountScreenRow(CardXref crossReference, Account account, Customer customer) {
}
