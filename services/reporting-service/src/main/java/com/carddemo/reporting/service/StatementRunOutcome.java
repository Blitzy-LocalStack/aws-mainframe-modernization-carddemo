package com.carddemo.reporting.service;

import java.util.List;
import java.util.Objects;

/**
 * What one statement run produced: how many statements, how many it omitted, and where each one sits
 * in the artifact.
 *
 * <h2>Purpose</h2>
 *
 * <p>The generator used to return a count alone, which was everything the task needed for its journal
 * line and nothing a later reader needed in order to find one card's statement inside a run-wide
 * document. This carries both, so the task can write the index in the same run that produced the
 * artifacts it indexes -- the only moment at which the record boundaries are known without reading the
 * artifact back.
 *
 * <h2>Assumptions: the index is ordered by fingerprint, and that is not the artifact's order</h2>
 *
 * <p>Entries arrive ordered by ascending card FINGERPRINT and are written to the index artifact in that
 * order. The read path depends on it: it locates one card by binary search over the index's fixed-width
 * records, and a binary search over an unordered array reports entries that are present as absent.
 *
 * <p>That is deliberately NOT the order of the statements in the document the entries point into. The
 * generator walks the cross-reference in ascending card NUMBER, because the record order of the
 * statement artifact is observable output held to the reference, which reads that file in key order. A
 * fingerprint is a digest, so the two orders bear no relation to each other and one walk cannot produce
 * both -- the generator therefore sorts the entries for lookup before returning them, and each entry
 * carries the position it names, so reordering the entries moves nothing in the artifact.
 *
 * <p>Trade-offs: an earlier version of this charter claimed the walk itself was fingerprint-ordered and
 * that nothing re-sorted, on the reasoning that a sort would conceal a walk that had stopped being
 * ordered. The premise was wrong -- the walk is card-number ordered by contract -- so the effect was a
 * binary search over an unordered index, which answered "your statement is not in this artifact" for
 * statements that were. The ordering is now established where the index is built rather than assumed of
 * its source.
 *
 * <h2>Assumptions: a run reports what it omitted, not only what it wrote</h2>
 *
 * <p>A statement whose own content cannot be rendered is written nowhere, journalled, and stepped over,
 * so the run completes rather than losing every other statement with it. The count of those omissions
 * travels here, beside the produced count, because the alternative is that it travels only in a log
 * line: the task's own summary event would then report the produced figure alone and a run that dropped
 * a cardholder's statement would read to an operator exactly like a run that dropped nothing.
 *
 * <p>Trade-offs: the omission count is deliberately NOT cross-checked against the index, where the
 * produced count is. There is nothing to check it against -- an omitted statement contributes no entry
 * by design, which is the property that keeps every later record position answerable -- so the only
 * invariant available is that it cannot be negative.
 *
 * @param statementsProduced how many statements the run wrote, being the number of cross-reference rows
 *     it read and rendered successfully
 * @param statementsOmitted how many cross-reference rows the run read but could not render, and
 *     therefore wrote nowhere
 * @param index one entry per produced statement, in ascending card-fingerprint order
 */
public record StatementRunOutcome(int statementsProduced, int statementsOmitted,
        List<StatementIndexEntry> index) {

    /**
     * Validates the count against the index and refuses a pairing that cannot both be true.
     *
     * <p>Assumptions: the two components are cross-checked rather than each checked alone. They are two
     * views of one run, so a count that disagrees with the number of entries means one of the two was
     * built wrongly -- and the consequence of not noticing is an index that omits a card, which the read
     * path would report as "your statement is not in this artifact" for a statement that is.</p>
     *
     * @param statementsProduced how many statements the run wrote; must not be negative
     * @param statementsOmitted how many statements the run could not render; must not be negative
     * @param index one entry per produced statement; must not be {@code null}
     * @throws NullPointerException if {@code index} is {@code null}
     * @throws IllegalArgumentException if either count is negative, or if the produced count disagrees
     *     with the number of index entries
     */
    public StatementRunOutcome {
        Objects.requireNonNull(index, "index must not be null");
        if (statementsProduced < 0) {
            throw new IllegalArgumentException("statementsProduced counts statements written and "
                    + "cannot be negative");
        }
        if (statementsOmitted < 0) {
            throw new IllegalArgumentException("statementsOmitted counts statements the run could not "
                    + "render and cannot be negative");
        }
        if (statementsProduced != index.size()) {
            throw new IllegalArgumentException("the run produced " + statementsProduced
                    + " statements but the index holds " + index.size()
                    + " entries; one card would be unreachable in the artifact");
        }
    }

    /**
     * Renders the outcome for a diagnostic.
     *
     * <p>Assumptions: both counts are printed and the entries are reduced to their number. Every entry
     * carries a card fingerprint, which names exactly one card, so rendering the list would put a
     * portfolio's worth of card identifiers into one log line.</p>
     *
     * @return the rendering, never {@code null}
     */
    @Override
    public String toString() {
        return "StatementRunOutcome[statementsProduced=" + statementsProduced
                + ", statementsOmitted=" + statementsOmitted
                + ", index=" + index.size() + " entries]";
    }
}
