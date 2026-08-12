package com.carddemo.reporting.dto;

import java.util.List;
import java.util.Objects;

/**
 * The transactions one statement was rendered from, with the true count beside them.
 *
 * <p>Purpose: the published contract declares the statement-transactions operation's body as an object
 * carrying the transaction array, and this record is that object. It additionally carries the count the
 * statement covers and whether the array is short of it, so a caller of THIS operation can tell a whole
 * statement from a bounded one without issuing a second request.
 *
 * <p>Refactoring Rationale: the record carried {@code items} alone, and the omission made a real bound
 * invisible. {@code com.carddemo.reporting.service.StatementService} bounds the composed window at
 * {@code MAX_RESPONSE_TRANSACTIONS} rows -- registered as divergence
 * {@code D-STMT-RESPONSE-BOUNDED} -- and the argument recorded for that bound is that truncation stays
 * measurable because the HEADING carries the card's true count. That argument holds for the statement
 * operation, which returns the heading; it does not hold for this one, which returns this object and
 * nothing else. A caller of the transactions operation therefore received a silently shortened array with
 * no member to compare it against, which is the one shape of truncation a caller cannot detect.
 *
 * <p>Assumptions: the count is the card's TRUE total and not the length of the array. The two agree on
 * every statement short of the bound and disagree on exactly the statements where the difference matters,
 * so reporting the array's own length would be a member that could never reveal anything. It is taken
 * from the heading of the same composed document the rows come from -- a database aggregate over the
 * whole card -- so it costs no extra query and cannot disagree with the rows it accompanies.
 *
 * <p>Assumptions: {@code truncated} is DERIVED at construction and is not an argument, so it cannot
 * disagree with the two members it summarises. A boolean a caller passed could report a whole statement
 * as bounded or the reverse, and this is a record two different call sites construct.
 *
 * <p>Assumptions: this is still deliberately NOT a page. The set is delimited by the statement's own
 * period and is read to be rendered as a document, so there is no open-ended sequence for a cursor to
 * walk and no boundary key accompanies the rows. What is published is the fact of a bound, not a way to
 * step past it -- a caller needing every row of an exceptionally long history reads the rendered
 * artifact, which carries no bound at all.
 *
 * <p>Alternatives Considered: reusing the shared page envelope for uniformity with the report's line
 * operation. Rejected for the reason above: a page envelope whose {@code hasNext} is always false and
 * whose keys are always null describes a paging capability the operation does not have, and a client
 * written against it would carry a paging loop that never runs. The two members added here say what is
 * true -- the array may be short and here is what it is short of -- without implying a forward step.
 *
 * <p>Alternatives Considered: declaring a {@code maxItems} on the array in the contract instead. Rejected
 * as insufficient rather than wrong: it would tell a caller the ceiling exists but not whether THIS body
 * hit it, which is the question a caller actually has. Both are now published -- the schema states the
 * bound and these members report the outcome.
 *
 * @param items the statement's transactions, each with its card number already masked, at most the
 *     service's response bound in number; never {@code null} and copied at construction
 * @param transactionCount the number of transactions the statement covers in total, which is at least
 *     {@code items.size()} and exceeds it exactly when the window was bounded; never negative
 * @param truncated whether {@code items} is short of {@code transactionCount}; derived at construction
 *     and never accepted from a caller
 */
public record StatementTransactionCollection(
    List<StatementTransactionResponse> items,
    int transactionCount,
    boolean truncated) {

    /**
     * Copies and freezes the transaction list, and derives the truncation flag from the two counts.
     *
     * @throws NullPointerException if {@code items} is {@code null} or holds a {@code null} element
     * @throws IllegalArgumentException if {@code transactionCount} is negative, or is fewer than the
     *     rows supplied, which would describe a statement covering less than it returned
     */
    public StatementTransactionCollection {
        Objects.requireNonNull(items, "items must not be null; a statement covering no transaction"
                + " carries an empty list rather than an absent one");
        items = List.copyOf(items);

        if (transactionCount < 0) {
            throw new IllegalArgumentException("transactionCount must not be negative: a statement"
                    + " covers zero or more transactions and the value is a database aggregate over"
                    + " the whole card, so a negative one is a defect in the caller rather than a"
                    + " statement with no rows");
        }
        if (transactionCount < items.size()) {
            throw new IllegalArgumentException("transactionCount " + transactionCount + " is fewer"
                    + " than the " + items.size() + " rows supplied: the count is the card's total and"
                    + " the rows are a window onto it, so the count can equal or exceed the window and"
                    + " never fall below it. A caller reaching this has passed the window's own length"
                    + " as the total, or has paired rows with another card's count");
        }

        // WHY : Assumptions: the flag is computed here rather than accepted, so the three members
        //       cannot disagree. Two call sites construct this record and a boolean argument at each
        //       would be two chances to report a bounded statement as whole -- which is the direction
        //       that matters, because a caller reading truncated=false stops looking.
        truncated = transactionCount > items.size();
    }

    /**
     * Builds the collection from a window of rows and the card's true total.
     *
     * <p>Assumptions: this factory exists so that a call site names the two numbers it is supplying and
     * cannot pass the derived flag at all. The canonical constructor stays available because
     * deserialising the shape in a test or a generated client needs it, and it derives the same flag,
     * so a body that arrives claiming the wrong one is corrected on the way in rather than trusted.
     *
     * @param items the window of rows to publish; must not be {@code null}
     * @param transactionCount the card's total transaction count, from the statement heading; must not
     *     be negative and must not be fewer than the rows supplied
     * @return the collection with its truncation flag derived; never {@code null}
     */
    public static StatementTransactionCollection of(List<StatementTransactionResponse> items,
            int transactionCount) {
        return new StatementTransactionCollection(items, transactionCount, false);
    }
}
