package com.carddemo.reporting.service;

import java.util.List;
import java.util.Objects;

/**
 * What one statement run produced: how many statements, and where each one sits in the artifact.
 *
 * <h2>Purpose</h2>
 *
 * <p>The generator used to return a count alone, which was everything the task needed for its journal
 * line and nothing a later reader needed in order to find one card's statement inside a run-wide
 * document. This carries both, so the task can write the index in the same run that produced the
 * artifacts it indexes -- the only moment at which the record boundaries are known without reading the
 * artifact back.
 *
 * <h2>Assumptions: the index is ordered and that order is part of the contract</h2>
 *
 * <p>Entries arrive in the order the cross-reference walk produced them, which is ascending card
 * fingerprint, and they are written to the index artifact in that order. The read path depends on it:
 * it locates one card by binary search over the artifact's fixed-width records, which is only correct
 * while the records are sorted. Nothing here re-sorts, because a sort would hide a walk that had stopped
 * being ordered rather than reveal it -- and an unordered walk is a defect in the cursor, which is
 * where it should be found.
 *
 * @param statementsProduced how many statements the run wrote, being the number of cross-reference rows
 *     it read
 * @param index one entry per statement, in ascending card-fingerprint order
 */
public record StatementRunOutcome(int statementsProduced, List<StatementIndexEntry> index) {

    /**
     * Validates the count against the index and refuses a pairing that cannot both be true.
     *
     * <p>Assumptions: the two components are cross-checked rather than each checked alone. They are two
     * views of one run, so a count that disagrees with the number of entries means one of the two was
     * built wrongly -- and the consequence of not noticing is an index that omits a card, which the read
     * path would report as "your statement is not in this artifact" for a statement that is.</p>
     *
     * @param statementsProduced how many statements the run wrote; must not be negative
     * @param index one entry per statement; must not be {@code null}
     * @throws NullPointerException if {@code index} is {@code null}
     * @throws IllegalArgumentException if the count is negative, or if it disagrees with the number of
     *     index entries
     */
    public StatementRunOutcome {
        Objects.requireNonNull(index, "index must not be null");
        if (statementsProduced < 0) {
            throw new IllegalArgumentException("statementsProduced counts statements written and "
                    + "cannot be negative");
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
     * <p>Assumptions: the count is printed and the entries are reduced to their number. Every entry
     * carries a card fingerprint, which names exactly one card, so rendering the list would put a
     * portfolio's worth of card identifiers into one log line.</p>
     *
     * @return the rendering, never {@code null}
     */
    @Override
    public String toString() {
        return "StatementRunOutcome[statementsProduced=" + statementsProduced
                + ", index=" + index.size() + " entries]";
    }
}
