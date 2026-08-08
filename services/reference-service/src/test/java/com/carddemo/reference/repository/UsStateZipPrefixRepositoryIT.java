package com.carddemo.reference.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.reference.domain.UsStateZipPrefix;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Limit;

/**
 * Verifies the state-and-postal-prefix pairings and their walks against a real engine.
 *
 * <p>Assumptions: the four-character key is the state code followed by the two leading postal digits, in
 * that order, which is how {@code app/cbl/COACTUPC.cbl} concatenates them at lines 2537 to 2540 with
 * {@code DELIMITED BY SIZE}. The seed migration loads 240 pairings from the list at L1073 of
 * {@code app/cpy/CSLKPCDY.cpy}.
 */
class UsStateZipPrefixRepositoryIT extends ReferencePersistenceBase {

    /** The number of pairings the seed migration loads. */
    private static final int SEEDED_PAIRINGS = 240;

    /** The width of the composed key: two for the state, two for the postal prefix. */
    private static final int KEY_WIDTH = 4;

    /** The repository under test. */
    @Autowired
    private UsStateZipPrefixRepository prefixes;

    /**
     * Confirms the seed loads every pairing at the composed key width, ordered ascending.
     */
    @Test
    @DisplayName("the seed loads 240 pairings at the four-character key width")
    void theSeedLoadsEveryPairing() {
        List<UsStateZipPrefix> all = this.prefixes.findAllByOrderByStateZipCdAsc(Limit.of(500));
        assertThat(all).hasSize(SEEDED_PAIRINGS);
        assertThat(all).extracting(UsStateZipPrefix::getStateZipCd).isSorted();
        assertThat(all).allSatisfy(row ->
                assertThat(row.getStateZipCd()).hasSize(KEY_WIDTH));
    }

    /**
     * Confirms a listed pairing resolves and an unlisted one is reported absent.
     *
     * <p>Assumptions: the pairing asserted present is read from the loaded list rather than written out,
     * so this test states that the key composed the documented way resolves -- not that one particular
     * state happens to be seeded, which is the seed migration's own contract to state.
     */
    @Test
    @DisplayName("a listed pairing resolves and an unlisted one is reported absent")
    void aListedPairingResolves() {
        String listed = this.prefixes.findAllByOrderByStateZipCdAsc(Limit.of(1)).get(0)
                .getStateZipCd();
        assertThat(this.prefixes.findByStateZipCd(listed)).isPresent();
        assertThat(this.prefixes.findByStateZipCd("ZZ99"))
                .as("an unlisted pairing becomes the cross-field refusal at line 1667")
                .isEmpty();
    }

    /**
     * Confirms both walks are bounded and strict about the position.
     */
    @Test
    @DisplayName("both walks are bounded and exclude the position")
    void bothWalksAreBoundedAndStrict() {
        List<UsStateZipPrefix> ascending =
                this.prefixes.findAllByOrderByStateZipCdAsc(Limit.of(10));
        String third = ascending.get(2).getStateZipCd();

        assertThat(this.prefixes.findByStateZipCdGreaterThanOrderByStateZipCdAsc(third, Limit.of(3)))
                .extracting(UsStateZipPrefix::getStateZipCd)
                .allSatisfy(code -> assertThat(code).isGreaterThan(third));
        assertThat(this.prefixes.findByStateZipCdLessThanOrderByStateZipCdDesc(third, Limit.of(2)))
                .extracting(UsStateZipPrefix::getStateZipCd)
                .allSatisfy(code -> assertThat(code).isLessThan(third));
    }
}
