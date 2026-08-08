package com.carddemo.authorization.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Asserts that a recorded authorization carries the match status its DECISION produced, and that no
 * other status can be originated.
 *
 * <p><b>Purpose.</b> The reference consumer selects between two match statuses on the outcome of the
 * authorization, at {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl} L902 to L906: an approval
 * takes the pending value at L903 and a decline takes the declined value at L905, with no third branch.
 * An earlier revision of {@link PendingAuthDetail} fixed the value to pending inside its constructor, so
 * every declined authorization was persisted as one still awaiting a match. These tests make the two
 * branches executable so the collapse cannot recur.</p>
 *
 * <p>Assumptions: this is a unit test over the entity's own constructor with no persistence context.
 * The property under test is a construction invariant rather than a mapping one -- the column's four-value
 * check constraint is asserted by the migration, and what has to be asserted here is the narrower set the
 * INSERT PATH may originate. A repository test would exercise the column and would pass just as happily
 * with the wrong one of the two values chosen.</p>
 *
 * <p>Alternatives Considered: asserting the branch through
 * {@code com.carddemo.authorization.service.AuthorizationRequestListener} instead, which is where the
 * decision is mapped. Rejected as the ONLY assertion, because the listener test needs a decision service,
 * an account-context stub, three repositories and a clock, so a failure there reports as a listener defect
 * whatever its cause. Both levels are worth having and this is the level that isolates the invariant; the
 * listener's own suite covers the mapping from a decision to these two characters.</p>
 *
 * @see PendingAuthDetail#ORIGINATED_MATCH_STATUSES
 */
class MatchStatusOriginationTest {

    /** The account the fixture rows hang beneath. */
    private static final long ACCOUNT_ID = 11L;

    /** The five-digit ordinal date component of the fixture key. */
    private static final int AUTH_DATE = 26218;

    /** The nine-digit time-of-day component of the fixture key. */
    private static final int AUTH_TIME = 91644123;

    /** The sixteen digits the fixture rows present. */
    private static final String CARD_NUMBER = "4111111111111111";

    /**
     * Builds one detail row with the match status supplied and every other component fixed.
     *
     * <p>Assumptions: every parameter other than the match status is a constant, so a failure names the
     * status rather than leaving a reader to work out which of twenty-four arguments moved.</p>
     *
     * @param matchStatus the status to originate; passed through unaltered
     * @return the constructed row
     */
    private static PendingAuthDetail rowWith(String matchStatus) {
        return new PendingAuthDetail(
                new PendingAuthDetailKey(ACCOUNT_ID, AUTH_DATE, AUTH_TIME),
                "260806", "091644", CARD_NUMBER, "0100", "2712", "0100", "0000",
                "091644", "00", "0000", "003000",
                new BigDecimal("250.00"), new BigDecimal("250.00"),
                "5411", "840", (short) 5, "MERCHANT000001", "ACME HARDWARE",
                "SPRINGFIELD", "IL", "627040000", "TX0000000000001", matchStatus);
    }

    /**
     * Rehydrates one detail row with the match status supplied and every other component fixed.
     *
     * <p>Assumptions: the argument list is IDENTICAL to {@link #rowWith(String)}'s, which is what makes
     * the pair of tests below a comparison of provenance alone. If the two paths took different arguments
     * a reader could not tell whether a difference in outcome came from the provenance or from the
     * data.</p>
     *
     * @param matchStatus the persisted status to rehydrate; passed through unaltered
     * @return the rehydrated row
     */
    private static PendingAuthDetail persistedRowWith(String matchStatus) {
        return PendingAuthDetail.rehydrated(
                new PendingAuthDetailKey(ACCOUNT_ID, AUTH_DATE, AUTH_TIME),
                "260806", "091644", CARD_NUMBER, "0100", "2712", "0100", "0000",
                "091644", "00", "0000", "003000",
                new BigDecimal("250.00"), new BigDecimal("250.00"),
                "5411", "840", (short) 5, "MERCHANT000001", "ACME HARDWARE",
                "SPRINGFIELD", "IL", "627040000", "TX0000000000001", matchStatus);
    }

    /**
     * An approved authorization is recorded as pending a match.
     */
    @Test
    @DisplayName("an approval originates the pending status, per COPAUA0C L903")
    void approvalOriginatesPending() {
        assertThat(rowWith(PendingAuthDetail.MATCH_STATUS_PENDING).getMatchStatus())
                .as("cbl/COPAUA0C.cbl L903 sets PA-MATCH-PENDING on the approval branch")
                .isEqualTo("P");
    }

    /**
     * A declined authorization is recorded as declined and NOT as pending.
     *
     * <p>Assumptions: the second assertion is the one that would have failed before the remediation, and
     * it is written as an explicit inequality rather than left implied by the first. A row persisted as
     * pending reserves capacity against the account and is aged out by the purge job as though a match
     * were still possible, so the two values are not interchangeable labels.</p>
     */
    @Test
    @DisplayName("a decline originates the declined status and never the pending one, per COPAUA0C L905")
    void declineOriginatesDeclined() {
        PendingAuthDetail declined = rowWith(PendingAuthDetail.MATCH_STATUS_DECLINED);

        assertThat(declined.getMatchStatus())
                .as("cbl/COPAUA0C.cbl L905 sets PA-MATCH-AUTH-DECLINED on the else branch")
                .isEqualTo("D");
        assertThat(declined.getMatchStatus())
                .as("a declined authorization must not be persisted as pending a match")
                .isNotEqualTo(PendingAuthDetail.MATCH_STATUS_PENDING);
    }

    /**
     * The two statuses reached by later transitions cannot be originated by an insert.
     *
     * <p>Assumptions: {@code 'E'} is set when a pending row ages out and {@code 'M'} when posting matches
     * one, so both are transitions on an existing row. The column admits all four, which is correct
     * because a row must be able to HOLD every state it can reach; the constructor admits two, which is
     * what stops an insert asserting an outcome no insert can have produced.</p>
     */
    @Test
    @DisplayName("the expired and matched statuses are refused at construction")
    void laterTransitionsCannotBeOriginated() {
        for (String later : new String[] {"E", "M"}) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("%s is reached by a later transition, not by an insert", later)
                    .isThrownBy(() -> rowWith(later))
                    .withMessageContaining("matchStatus must be one of");
        }
    }

    /**
     * A value outside the copybook's four-value domain is refused.
     */
    @Test
    @DisplayName("an out-of-domain status is refused at construction")
    void outOfDomainStatusIsRefused() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> rowWith("X"))
                .withMessageContaining("COPAUA0C");
    }

    /**
     * An absent status is refused rather than defaulted.
     *
     * <p>Assumptions: refusing is the point. Defaulting to pending is precisely the behaviour that was
     * removed, so a null must fail loudly rather than reinstating it by another route.</p>
     */
    @Test
    @DisplayName("an absent status is refused rather than defaulted to pending")
    void absentStatusIsRefused() {
        assertThatNullPointerException()
                .isThrownBy(() -> rowWith(null))
                .withMessageContaining("matchStatus");
    }

    /**
     * The originated set is exactly the two values the insert path reaches, in branch order.
     */
    @Test
    @DisplayName("exactly two statuses may be originated, in the order the reference branches select them")
    void originatedSetIsExactlyTheTwoInsertValues() {
        assertThat(PendingAuthDetail.ORIGINATED_MATCH_STATUSES)
                .containsExactly(PendingAuthDetail.MATCH_STATUS_PENDING,
                        PendingAuthDetail.MATCH_STATUS_DECLINED);
    }

    /**
     * Rehydration admits every one of the four statuses a stored row can hold.
     *
     * <p>Assumptions: this is the property whose absence made valid data unloadable. A row that aged out
     * carries {@code 'E'} and a row that posting matched carries {@code 'M'}; the column's own check
     * constraint {@code ck_pending_auth_detail_match_status} admits both, the copybook declares condition
     * names for both at {@code cpy/CIPAUDTY.cpy} L46 to L49, and the committed fixture
     * {@code pautdtl1-match-status-domain.bin} holds one record per status precisely so a load can be
     * asserted over all four. Routing the load through the insert-only constructor made two of those four
     * records unloadable, so an extract of a real database would have failed on the first expired
     * authorization it reached.</p>
     *
     * <p>Assumptions: all four are asserted rather than only the two the insert path refuses, because the
     * two the insert path admits must keep working through the rehydration route as well -- a fix that
     * admitted {@code 'E'} and {@code 'M'} while breaking {@code 'P'} and {@code 'D'} would exchange one
     * unloadable half for the other.</p>
     */
    @Test
    @DisplayName("rehydration admits all four persisted statuses P, D, E and M")
    void rehydrationAdmitsTheWholePersistedDomain() {
        for (String persisted : new String[] {
                PendingAuthDetail.MATCH_STATUS_PENDING,
                PendingAuthDetail.MATCH_STATUS_DECLINED,
                PendingAuthDetail.MATCH_STATUS_PENDING_EXPIRED,
                PendingAuthDetail.MATCH_STATUS_MATCHED_WITH_TRAN}) {
            assertThat(persistedRowWith(persisted).getMatchStatus())
                    .as("%s is a state a stored row can hold, so a load must accept it", persisted)
                    .isEqualTo(persisted);
        }
    }

    /**
     * Rehydration is WIDER than origination and not a bypass of every rule.
     *
     * <p>Assumptions: the two paths differ in exactly one respect -- which set of statuses they admit --
     * and a value outside the copybook's four is refused on BOTH. Admitting an arbitrary character on the
     * load path would let an extract introduce a value the column's check constraint then refuses at
     * flush time, reporting a database error with no field named instead of a refusal naming the
     * status.</p>
     *
     * <p>Assumptions: the message asserted is the FIRST stage of the staged refusal, the one that says the
     * value is not a match status at all. The second stage -- the one that says a value is a match status
     * an insert may not originate -- is unreachable from this path by construction, since rehydration
     * admits the whole domain, and asserting that sentence here would pass only if the widening had
     * failed.</p>
     */
    @Test
    @DisplayName("rehydration still refuses a status outside the copybook's four-value domain")
    void rehydrationRefusesAnOutOfDomainStatus() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> persistedRowWith("X"))
                .withMessageContaining("matchStatus is not a match status value")
                .withMessageContaining("X");
        assertThatNullPointerException()
                .isThrownBy(() -> persistedRowWith(null))
                .withMessageContaining("matchStatus");
    }

    /**
     * The insert path stays narrow after the load path was widened.
     *
     * <p>Assumptions: this is the regression guard on the fix itself. The straightforward way to make
     * {@code 'E'} and {@code 'M'} loadable is to widen the constructor, which would silently permit a
     * listener defect to record an authorization as already matched or already expired -- an outcome no
     * decision branch in {@code cbl/COPAUA0C.cbl} can produce. Asserting the same two values through the
     * two routes in one test is what proves the widening landed on the load path only.</p>
     */
    @Test
    @DisplayName("widening the load path left the insert path refusing E and M")
    void wideningRehydrationDidNotWidenOrigination() {
        for (String later : new String[] {
                PendingAuthDetail.MATCH_STATUS_PENDING_EXPIRED,
                PendingAuthDetail.MATCH_STATUS_MATCHED_WITH_TRAN}) {
            assertThat(persistedRowWith(later).getMatchStatus()).isEqualTo(later);
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("%s must remain unreachable from an insert", later)
                    .isThrownBy(() -> rowWith(later));
        }
    }
}
