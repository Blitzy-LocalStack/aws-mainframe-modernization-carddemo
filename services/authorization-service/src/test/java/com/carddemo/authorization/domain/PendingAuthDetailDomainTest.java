package com.carddemo.authorization.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Asserts the closed domains and the fraud transition {@link PendingAuthDetail} enforces.
 *
 * <p>Purpose: the entity carries three invariants that no other test in this module reaches -- the
 * four-value match-status domain, the two-digit bound on the point-of-sale entry mode, and the
 * two-state fraud transition with its paired report date. Each is enforced in the entity rather than
 * left to a check constraint, because a constraint fires only at flush time and reports a column
 * instead of the call site that chose the value; this class is what proves the earlier refusal
 * happens.
 *
 * <p>Assumptions: every case constructs the entity directly rather than going through the request
 * consumer, because the invariants belong to the type and hold for every instance including the ones
 * an extract loader and a test fixture build. A test that could only reach them through the consumer
 * would leave the loader path unasserted.
 *
 * <p>Assumptions: the copybook, the migration and the reference programs cited below live under
 * {@code app/app-authorization-ims-db2-mq}, which is read as this type's specification and is never
 * modified. Every citation is a path and a line number for that reason.
 */
class PendingAuthDetailDomainTest {

    /**
     * The account the built rows belong to.
     *
     * <p>Assumptions: an eleven-digit identifier, the width {@code PA-ACCT-ID PIC 9(11)} declares, so the
     * value is representative of a real key rather than a small ordinal.</p>
     */
    private static final long ACCOUNT_ID = 10000000011L;

    /**
     * The five-digit ordinal date component of the built rows' key.
     */
    private static final int AUTH_DATE = 24095;

    /**
     * The nine-digit time-of-day component of the built rows' key.
     */
    private static final int AUTH_TIME = 91644123;

    /**
     * An eight-character report date in the month-first form the reference marking flow writes.
     *
     * <p>Assumptions: the separators are solidi because {@code cbl/COPAUS2C.cbl} L95 to L100 asks
     * {@code FORMATTIME} for a month, day and two-digit year with a bare {@code DATESEP}, which defaults
     * to a solidus. The value is eight characters, which is what {@code PIC X(08)} at
     * {@code cpy/CIPAUDTY.cpy} L53 and the {@code CHAR(8)} column both declare.</p>
     */
    private static final String REPORT_DATE = "08/06/26";

    /**
     * A second report date, one day later, used to show a repeated mark restamps rather than preserves.
     */
    private static final String LATER_REPORT_DATE = "08/07/26";

    /**
     * Every match status an insert path originates is accepted and stored as given.
     *
     * <p>Assumptions: the four values the condition names admit are {@code PA-MATCH-PENDING},
     * {@code PA-MATCH-AUTH-DECLINED}, {@code PA-MATCH-PENDING-EXPIRED} and
     * {@code PA-MATCHED-WITH-TRAN} at {@code cpy/CIPAUDTY.cpy} L46 to L49, and the migration's check
     * constraint at its L582 to L583 admits the same four. Only TWO of them are exercised here, and
     * the narrowing is the constructor's rather than this test's: an insert reaches pending or
     * declined and nothing else, so a row CONSTRUCTED in the expired or the matched state would
     * assert an outcome only the purge job or the posting match can have produced. The other two are
     * still named as constants and still admitted by the column, because the extract loads rows
     * already carrying them and the provider materialises a loaded row through the no-argument
     * constructor and field assignment -- which this validation never sees. The refusal of those two
     * at construction is asserted separately by {@code MatchStatusOriginationTest}.</p>
     *
     * @param status the one-character match status under test
     */
    @ParameterizedTest
    @ValueSource(strings = {"P", "D"})
    @DisplayName("every match status an insert originates is accepted and stored as given")
    void everyOriginatedMatchStatusIsStored(String status) {
        assertThat(detailWith(status, (short) 5).getMatchStatus()).isEqualTo(status);
    }

    /**
     * The four constants spell the four admitted values, so a caller never needs a bare literal.
     *
     * <p>Assumptions: this asserts the CONSTANTS and not the domain, which is a different claim from the
     * case above. A constant whose value drifted from the condition name it cites would still leave that
     * case green, because the case would then be exercising the drifted value on both sides.</p>
     */
    @Test
    @DisplayName("the four match-status constants spell the copybook's four condition-name values")
    void theMatchStatusConstantsSpellTheCopybookValues() {
        assertThat(PendingAuthDetail.MATCH_STATUS_PENDING).isEqualTo("P");
        assertThat(PendingAuthDetail.MATCH_STATUS_DECLINED).isEqualTo("D");
        assertThat(PendingAuthDetail.MATCH_STATUS_PENDING_EXPIRED).isEqualTo("E");
        assertThat(PendingAuthDetail.MATCH_STATUS_MATCHED_WITH_TRAN).isEqualTo("M");
        assertThat(PendingAuthDetail.FRAUD_REPORTED).isEqualTo("F");
        assertThat(PendingAuthDetail.FRAUD_REMOVED).isEqualTo("R");
    }

    /**
     * A match status outside the four-value domain is refused at construction.
     *
     * <p>Assumptions: the lower-case spellings are included as cases because they are the failure a
     * caller is most likely to produce, and because the reference condition names compare bytes -- a
     * lower-case value would satisfy none of them and would reach the column as an untagged-looking
     * status the detail screen could not classify. The blank and the empty string are included because
     * the column is {@code NOT NULL} and a space is a value a fixed-character column accepts silently.</p>
     *
     * @param status the rejected match status under test
     */
    @ParameterizedTest
    @ValueSource(strings = {"p", "d", "X", " ", "", "PP", "0"})
    @DisplayName("a match status outside the four-value domain is refused at construction")
    void anOutOfDomainMatchStatusIsRefused(String status) {
        assertThatThrownBy(() -> detailWith(status, (short) 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("match status");
    }

    /**
     * A null match status is refused rather than treated as a permitted absence.
     *
     * <p>Assumptions: null is a separate case from the out-of-domain strings above because the two would
     * be caught by different code in a naive implementation -- a membership test alone rejects null, but
     * an equality chain would not. The column is {@code NOT NULL} at migration L492, and the reference
     * insert's two branches at {@code cbl/COPAUA0C.cbl} L902 to L906 are exhaustive, so there is no third
     * outcome that legitimately leaves the field unset.</p>
     *
     * <p>Assumptions: the expected type is {@link NullPointerException} and not the
     * {@link IllegalArgumentException} the out-of-domain cases raise, which is a distinction and not an
     * inconsistency. A null is an absent ARGUMENT and the constructor documents it as such, so it is
     * refused by {@code Objects.requireNonNull} in the JDK's own idiom; a present value outside the
     * domain is a wrong argument. What both must do is name the component, and that is what is asserted
     * here rather than merely the type.</p>
     */
    @Test
    @DisplayName("a null match status is refused, the column being declared NOT NULL")
    void aNullMatchStatusIsRefused() {
        assertThatThrownBy(() -> detailWith(null, (short) 5))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("matchStatus");
    }

    /**
     * An entry mode inside the two unsigned digits is accepted, at both ends and absent.
     *
     * <p>Assumptions: zero and 99 are both accepted, so the bound is inclusive at both ends -- an
     * exclusive bound would reject mode zero, which {@code PIC 9(02)} admits and which is a real entry
     * mode rather than an absence. Null is accepted separately because the column is nullable and an
     * extract may carry no mode; absence and zero are distinct states and both must be representable.</p>
     */
    @Test
    @DisplayName("an entry mode of 0, of 99 or absent is accepted, the bound being inclusive")
    void anInRangeOrAbsentEntryModeIsAccepted() {
        assertThat(detailWith("P", (short) 0).getPosEntryMode()).isZero();
        assertThat(detailWith("P", (short) 99).getPosEntryMode()).isEqualTo((short) 99);
        assertThat(detailWith("P", null).getPosEntryMode()).isNull();
    }

    /**
     * An entry mode that two unsigned digits cannot hold is refused at construction.
     *
     * <p>Assumptions: the bound is checked in the entity even though the column is {@code SMALLINT},
     * because {@code SMALLINT} holds five digits and would store 100 or -1 successfully. Such a row would
     * then fail only when something tried to render it at its declared two-character width or encode it
     * onto the two-character wire field at {@code cpy/CCPAURQY.cpy} L30 -- a failure arbitrarily far from
     * the call site that chose the value. The negative case matters as much as the wide one: the picture
     * is UNSIGNED, so a negative mode did not come from the segment at all.</p>
     *
     * @param mode the rejected entry mode under test
     */
    @ParameterizedTest
    @ValueSource(shorts = {-1, 100, 1000, Short.MAX_VALUE, Short.MIN_VALUE})
    @DisplayName("an entry mode outside two unsigned digits is refused at construction")
    void anOutOfRangeEntryModeIsRefused(short mode) {
        assertThatThrownBy(() -> detailWith("P", mode))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pos entry mode");
    }

    /**
     * A newly-built row carries neither fraud member, which is the never-examined state.
     *
     * <p>Assumptions: both members are left unassigned rather than defaulted, and that is asserted here
     * because it is a deliberate choice rather than an omission. {@code cbl/COPAUA0C.cbl} L908 to L909
     * moves SPACE into both fields on every insert, and those are the only writes to either field in that
     * program, so the never-examined state is blank-or-absent and inventing a fraud position at
     * construction would assert something about an authorization nobody has looked at.</p>
     */
    @Test
    @DisplayName("a newly-built row carries neither fraud member, the never-examined state")
    void aNewRowCarriesNoFraudPosition() {
        PendingAuthDetail row = detailWith("P", (short) 5);

        assertThat(row.getAuthFraud()).isNull();
        assertThat(row.getFraudReportDate()).isNull();
    }

    /**
     * Each of the two marking states can be set, and each stamps the report date.
     *
     * <p>Assumptions: the removal is asserted to stamp a date exactly as the report does, which is a
     * reading of the reference flow and not a symmetry preference: {@code cbl/COPAUS2C.cbl} L101 performs
     * {@code MOVE WS-CUR-DATE TO PA-FRAUD-RPT-DATE} unconditionally, before either its insert path at
     * L199 to L201 or its update path at L230 to L232 is chosen. So a withdrawal records when it was
     * withdrawn, and the {@code R} half of the published action domain is reachable through this type --
     * which it was not when the only mutator always wrote {@code F}.</p>
     *
     * @param state the marking state under test
     */
    @ParameterizedTest
    @ValueSource(strings = {"F", "R"})
    @DisplayName("either marking state can be set, and either stamps the report date")
    void eitherMarkingStateIsReachableAndStampsTheDate(String state) {
        PendingAuthDetail row = detailWith("P", (short) 5);

        row.applyFraudMark(state, REPORT_DATE);

        assertThat(row.getAuthFraud()).isEqualTo(state);
        assertThat(row.getFraudReportDate()).isEqualTo(REPORT_DATE);
    }

    /**
     * A report can be withdrawn and reinstated, the transition moving in both directions.
     *
     * <p>Assumptions: the two directions are asserted in sequence on ONE row rather than on two rows,
     * because the property under test is the transition and not the assignment. The reference detail
     * screen toggles between the two states at {@code cbl/COPAUS1C.cbl} L236 to L241 -- confirmed becomes
     * removed and anything else becomes confirmed -- so a row must be able to arrive at either state from
     * the other, and a per-state test on fresh rows would never exercise that.</p>
     *
     * <p>Assumptions: a withdrawal does NOT blank the report date back to the never-examined state. Only
     * the insert path blanks these fields, together, at {@code cbl/COPAUA0C.cbl} L908 to L909; treating
     * {@code R} as a return to that state would erase the evidence the authorization was examined.</p>
     */
    @Test
    @DisplayName("a report can be withdrawn and reinstated, the date following each transition")
    void aReportCanBeWithdrawnAndReinstated() {
        PendingAuthDetail row = detailWith("P", (short) 5);

        row.applyFraudMark(PendingAuthDetail.FRAUD_REPORTED, REPORT_DATE);
        row.applyFraudMark(PendingAuthDetail.FRAUD_REMOVED, LATER_REPORT_DATE);

        assertThat(row.getAuthFraud()).isEqualTo(PendingAuthDetail.FRAUD_REMOVED);
        assertThat(row.getFraudReportDate()).isEqualTo(LATER_REPORT_DATE);

        row.applyFraudMark(PendingAuthDetail.FRAUD_REPORTED, REPORT_DATE);

        assertThat(row.getAuthFraud()).isEqualTo(PendingAuthDetail.FRAUD_REPORTED);
        assertThat(row.getFraudReportDate()).isEqualTo(REPORT_DATE);
    }

    /**
     * Repeating a mark succeeds and restamps the date rather than being refused.
     *
     * <p>Assumptions: idempotence is the reference behaviour rather than a convenience.
     * {@code cbl/COPAUS2C.cbl} L203 detects the duplicate-key condition on its insert and performs the
     * update paragraph instead of failing, and its L224 to L225 sets the date to the current one on that
     * update path rather than preserving the earlier value. So a repeated report is an accepted update
     * that re-dates the row, and refusing it here would introduce a failure the reference does not have.</p>
     */
    @Test
    @DisplayName("repeating a mark succeeds and restamps the date, as the reference update path does")
    void repeatingAMarkRestampsTheDate() {
        PendingAuthDetail row = detailWith("P", (short) 5);

        row.applyFraudMark(PendingAuthDetail.FRAUD_REPORTED, REPORT_DATE);
        row.applyFraudMark(PendingAuthDetail.FRAUD_REPORTED, LATER_REPORT_DATE);

        assertThat(row.getAuthFraud()).isEqualTo(PendingAuthDetail.FRAUD_REPORTED);
        assertThat(row.getFraudReportDate()).isEqualTo(LATER_REPORT_DATE);
    }

    /**
     * A fraud state outside the two marking values is refused, blank and null included.
     *
     * <p>Assumptions: the blank and the null are refused even though the COLUMN admits both, and that
     * asymmetry is deliberate rather than an oversight. Blank and null are the state of an authorization
     * nobody has examined, reached only by an insert or an extract load; a marking transition always
     * asserts a fraud position, so it may only move to one of the two tagged values. The {@code 'S'} case
     * is included because it means success on the marking RESPONSE and is the value most likely to be
     * passed here by mistake.</p>
     *
     * @param state the rejected fraud state under test
     */
    @ParameterizedTest
    @ValueSource(strings = {"f", "r", " ", "", "S", "X", "FR"})
    @DisplayName("a fraud state outside the two marking values is refused, blank included")
    void anOutOfDomainFraudStateIsRefused(String state) {
        PendingAuthDetail row = detailWith("P", (short) 5);

        assertThatThrownBy(() -> row.applyFraudMark(state, REPORT_DATE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fraud state");
        assertThat(row.getAuthFraud()).as("a refused transition leaves the row unchanged").isNull();
        assertThat(row.getFraudReportDate()).isNull();
    }

    /**
     * A null fraud state is refused, so the transition cannot clear the position.
     */
    @Test
    @DisplayName("a null fraud state is refused, the transition never clearing the position")
    void aNullFraudStateIsRefused() {
        PendingAuthDetail row = detailWith("P", (short) 5);

        assertThatThrownBy(() -> row.applyFraudMark(null, REPORT_DATE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fraud state");
        assertThat(row.getAuthFraud()).isNull();
    }

    /**
     * A report date that is absent or not exactly eight characters is refused, for either state.
     *
     * <p>Assumptions: the width is checked rather than assumed because a fixed-character column pads a
     * short value with blanks silently, so a five-character date would store as a plausible-looking eight
     * and only be caught when something tried to read a day out of it. Both target states are exercised,
     * because the date requirement applies to a withdrawal as much as to a report and a check written
     * inside only the report branch would leave the other half open.</p>
     *
     * @param state the marking state under test
     */
    @ParameterizedTest
    @ValueSource(strings = {"F", "R"})
    @DisplayName("a report date that is absent or the wrong width is refused for either state")
    void aMalformedReportDateIsRefused(String state) {
        PendingAuthDetail row = detailWith("P", (short) 5);

        assertThatThrownBy(() -> row.applyFraudMark(state, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fraud report date");
        assertThatThrownBy(() -> row.applyFraudMark(state, ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fraud report date");
        assertThatThrownBy(() -> row.applyFraudMark(state, "8/6/26"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fraud report date");
        assertThatThrownBy(() -> row.applyFraudMark(state, "08/06/2026"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fraud report date");

        assertThat(row.getAuthFraud()).as("no refused call left a partial position").isNull();
        assertThat(row.getFraudReportDate()).isNull();
    }

    /**
     * Builds one detail row carrying the supplied match status and entry mode.
     *
     * <p>Assumptions: every other member is a fixed representative value, because no case here varies
     * one. The amounts are at scale two already, this constructor decoding nothing.</p>
     *
     * @param matchStatus the match status to construct with, which may be {@code null} for a refusal case
     * @param posEntryMode the entry mode to construct with, which may be {@code null}
     * @return the constructed row, when the arguments are accepted
     */
    private static PendingAuthDetail detailWith(String matchStatus, Short posEntryMode) {
        return new PendingAuthDetail(
                new PendingAuthDetailKey(ACCOUNT_ID, AUTH_DATE, AUTH_TIME),
                "260806", "091644", "4000123456789010", "0100", "2712", "0100", "0000",
                "AUTH01", "00", "0000", "003000", new BigDecimal("250.00"),
                new BigDecimal("250.00"), "5411", "840", posEntryMode, "MERCHANT000001",
                "ACME HARDWARE", "SPRINGFIELD", "IL", "627040000", "TX0000000000001",
                matchStatus);
    }
}
