package com.carddemo.reference.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.carddemo.reference.domain.DisclosureGroup;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Verifies the disclosure-group keyed read and the fallback group the interest job depends on.
 *
 * <p>Assumptions: this repository offers one keyed finder and no walk at all, because that is the whole
 * of its baseline surface -- {@code app/cbl/CBACT04C.cbl} reads this data by key and, on a miss, reads the
 * fallback group by key a second time at lines 415 to 441. It never browses it.
 */
class DisclosureGroupRepositoryIT extends ReferencePersistenceBase {

    /** The account group the seed loads rates for. */
    private static final String SEEDED_GROUP = "A000000000";

    // WHY : Assumptions: the fallback group is written PADDED to the ten characters the key type
    //       requires. The column is a declared-width character column and DisclosureGroupId refuses any
    //       other width outright, which was established by running this test rather than by reading the
    //       type: the unpadded seven-character form raised at construction and never reached a query. The
    //       padding is therefore part of the key's contract and not an artefact of storage, and the seed
    //       migration writes the same padded form.
    /** The fallback group the interest job falls back to on a miss, at the key's declared width. */
    private static final String DEFAULT_GROUP = "DEFAULT   ";

    /** The repository under test. */
    @Autowired
    private DisclosureGroupRepository groups;

    /**
     * Confirms a seeded rate resolves by its three-part key.
     */
    @Test
    @DisplayName("a seeded rate resolves by its three-part key")
    void aSeededRateResolvesByKey() {
        assertThat(this.groups.findByIdIs(
                new DisclosureGroup.DisclosureGroupId(SEEDED_GROUP, "01", "0001")))
                .isPresent();
    }

    /**
     * Confirms the fallback group is seeded and reachable, which the interest job requires.
     *
     * <p>Assumptions: this is a parity requirement rather than a convenience. When an account's own
     * disclosure key is absent the baseline reads the fallback group instead, so a schema seeded without
     * it would compute no interest at all for every such account rather than failing visibly.
     */
    @Test
    @DisplayName("the fallback disclosure group is seeded and reachable by key")
    void theFallbackGroupIsSeeded() {
        assertThat(this.groups.findByIdIs(
                new DisclosureGroup.DisclosureGroupId(DEFAULT_GROUP, "01", "0001")))
                .as("app/cbl/CBACT04C.cbl lines 415 to 441 fall back to this group on a keyed miss")
                .isPresent();
    }

    /**
     * Confirms the key type refuses a group name that is not at the column's declared width.
     *
     * <p>Refactoring Rationale: this case replaces one that asserted the opposite -- that an UNPADDED
     * name would resolve against the padded column, on the reasoning that padding should be transparent
     * to a caller. Running it showed the key type refuses the unpadded form at construction, before any
     * query is issued. That is the stronger design and is kept: a composite key over blank-padded columns
     * that accepted two widths would let one logical key exist in two forms, and only one of them would
     * match a stored row -- a miss that reads as absent data rather than as a malformed key.
     */
    @Test
    @DisplayName("the key type refuses a group name that is not at the column's declared width")
    void theKeyTypeRefusesAnUnpaddedGroupName() {
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() ->
                new DisclosureGroup.DisclosureGroupId("DEFAULT", "02", "0001"));
    }

    /**
     * Confirms an unseeded key is reported absent rather than resolving to a neighbouring rate.
     */
    @Test
    @DisplayName("an unseeded key is reported absent")
    void anUnseededKeyIsAbsent() {
        assertThat(this.groups.findByIdIs(
                new DisclosureGroup.DisclosureGroupId("NOSUCHGRP ", "99", "9999")))
                .isEmpty();
    }
}
