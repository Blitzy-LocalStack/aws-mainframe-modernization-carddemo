package com.carddemo.reference.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.reference.domain.UsState;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Limit;

/**
 * Verifies the state allow-list's keyset walks and keyed finder against a real engine.
 *
 * <p>Assumptions: the count is the baseline's own. {@code app/cpy/CSLKPCDY.cpy} holds the state list at
 * L1013, and the seed migration loads 56 entries -- the fifty states plus the district and the five
 * territory codes the baseline list carries. The account context accepts exactly these.
 *
 * <p>Refactoring Rationale: this class was named {@code UsStateRepositoryIT} and covers what is now
 * {@link StateRepository}, so it is renamed with the interface rather than left naming a type that no
 * longer exists. The qualifier in the name is deliberate: {@code StateRepositoryIT} already covers the
 * same interface from a different angle -- the closed 56-code domain, the six non-state codes by name,
 * the two-character key round trip and the primary key's refusal of a duplicate -- and this class covers
 * the QUERY SURFACE, the three keyset walks and the keyed finder. Alternatives Considered: folding these
 * three cases into that class, which would leave one test per interface and match the package's naming
 * rule exactly. Rejected here because that class states in its own body that the walks "are covered by
 * the sibling class" and reasons about its cardinality read on that basis, so the division of subject is
 * load-bearing documentation rather than an accident of authorship; both names still name the interface
 * under test, which is the property the naming rule buys.
 */
class StateRepositoryWalkIT extends ReferencePersistenceBase {

    /** The number of state codes the seed migration loads. */
    private static final int SEEDED_STATES = 56;

    /** The repository under test. */
    @Autowired
    private StateRepository states;

    /**
     * Confirms the seed loads the whole list, ordered ascending by the engine.
     */
    @Test
    @DisplayName("the seed loads 56 state codes in ascending order")
    void theSeedLoadsEveryState() {
        List<UsState> all = this.states.findAllByOrderByStateCodeAsc(Limit.of(200));
        assertThat(all).hasSize(SEEDED_STATES);
        assertThat(all).extracting(UsState::getStateCode).isSorted();
        assertThat(all).extracting(UsState::getStateCode).startsWith("AK").endsWith("WY");
    }

    /**
     * Confirms the keyed finder resolves a seeded code and reports an unseeded one absent.
     *
     * <p>Assumptions: the negative half is the one the account update path depends on -- an unlisted
     * state has to be reported absent rather than resolving to a neighbour, because that report is what
     * becomes the field refusal the reference raises at line 1600 of {@code app/cbl/COACTUPC.cbl}.
     */
    @Test
    @DisplayName("the keyed finder resolves a seeded state and reports an unlisted one absent")
    void theKeyedFinderResolvesASeededState() {
        assertThat(this.states.findByStateCode("VA")).isPresent();
        assertThat(this.states.findByStateCode("ZZ")).isEmpty();
    }

    /**
     * Confirms both walks are bounded and strict about the position.
     */
    @Test
    @DisplayName("both walks are bounded and exclude the position")
    void bothWalksAreBoundedAndStrict() {
        List<UsState> forward =
                this.states.findByStateCodeGreaterThanOrderByStateCodeAsc("AK", Limit.of(3));
        assertThat(forward).hasSize(3);
        assertThat(forward).extracting(UsState::getStateCode)
                .allSatisfy(code -> assertThat(code).isGreaterThan("AK"));

        List<UsState> backward =
                this.states.findByStateCodeLessThanOrderByStateCodeDesc("WY", Limit.of(3));
        assertThat(backward).hasSize(3);
        assertThat(backward).extracting(UsState::getStateCode)
                .allSatisfy(code -> assertThat(code).isLessThan("WY"));
    }
}
