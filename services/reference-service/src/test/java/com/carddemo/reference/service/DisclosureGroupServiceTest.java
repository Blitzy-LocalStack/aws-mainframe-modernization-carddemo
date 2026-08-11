// =============================================================================
// services/reference-service/src/test/java/com/carddemo/reference/service/DisclosureGroupServiceTest.java
// -----------------------------------------------------------------------------
// Purpose:
//       Behavioural cases over DisclosureGroupService, the migrated form of the two rate-reading
//       paragraphs of app/cbl/CBACT04C.cbl. Three outcomes are asserted and kept distinguishable
//       from one another: a direct hit on the group asked for, a substitution onto the padded
//       default group, and a terminal miss that neither read resolves. No golden master covers
//       this contract, so these assertions are primary evidence rather than a supplement.
//
// WHY (non-obvious design decisions):
//   1.  Assumptions: this file sits beside ReferenceWriteBehaviourTest, whose OnTheRateLookup
//       group already covers the same service, and it is additive rather than a second copy. That
//       group asserts the flags and the echoed key on the substitution path but asserts NO RATE
//       there at all, so the one value the substitution exists to change was unmeasured. Every
//       case below states what the earlier group leaves open; none restates what it already holds.
//   2.  Alternatives Considered: building the substitution case on any pair other than type 07
//       category 0001. Rejected because sixteen of the seventeen pairs carry byte-identical rates
//       under both groups, so an assertion on those would pass whether or not the substitution
//       fired and would keep passing if the substitution branch were deleted outright. Pair
//       07|0001 is the only pair whose two rates differ, which is what makes the assertion able
//       to fail.
//   3.  Assumptions: the two rate literals below are the ASCII twin's, and the two baseline
//       extracts genuinely disagree on this one row. app/data/ASCII/discgrp.txt reads 15.00 at
//       row 17 under A000000000 and 0.00 at row 34 under the padded default group, while the
//       EBCDIC extract app/jcl/DISCGRP.jcl loads reads 15.00 in BOTH places. This module's own
//       fixture tree is derived from the ASCII twin, so these are its values; the EBCDIC form is
//       asserted by a different module and is neither contradicted nor restated here.
//   4.  Assumptions: the conversion to the published reply is reached statically, so it is not
//       doubled. DisclosureGroupMapper is final with a private constructor exposing one static
//       member, which leaves the repository as the service's only collaborator. What the service
//       hands the conversion is therefore asserted through the reply the conversion returns.
//   5.  Assumptions: every rate is compared by numeric comparison AND by declared scale, never by
//       value equality, because BigDecimal equality is scale-sensitive and would answer false for
//       two spellings of one amount.
//   6.  Trade-offs: no interest figure is computed anywhere below. The accrual that multiplies
//       this rate belongs to another bounded context, so what is given up is any check of the
//       arithmetic; what it buys is that a failure here can only mean the wrong rate was resolved
//       and never that the wrong sum was formed from a right one.
//   7.  Trade-offs: the byte-level census of the seed and the fixture tree is not repeated here,
//       and neither is the status code the shared advice returns. Both are already asserted
//       elsewhere, and a property asserted twice can be satisfied once while reporting as
//       satisfied in both places.
// =============================================================================
package com.carddemo.reference.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.carddemo.common.error.AbendDetail;
import com.carddemo.common.money.Money;
import com.carddemo.reference.domain.DisclosureGroup;
import com.carddemo.reference.domain.DisclosureGroup.DisclosureGroupId;
import com.carddemo.reference.dto.DisclosureGroupRateResponse;
import com.carddemo.reference.repository.DisclosureGroupRepository;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * Proves the rate substitution fires, prefers the group asked for, and is not total.
 *
 * <p>Purpose: these cases cover {@link DisclosureGroupService}, which migrates
 * {@code 1200-GET-INTEREST-RATE} at line 415 of {@code app/cbl/CBACT04C.cbl} together with the
 * separate paragraph it delegates to on a miss, {@code 1200-A-GET-DEFAULT-INT-RATE} at line 443.
 * Three outcomes have to stay distinguishable: the group asked for answers, the padded default
 * group answers instead, or neither answers and the condition is reported. A suite proving only
 * that a rate comes back would pass equally well against all three collapsed into one behaviour.</p>
 *
 * <p>Assumptions: the four rationale labels used throughout this file are the plural,
 * unparenthesised, hyphen-minus forms taken from lines 31 to 34 of the user-specified
 * Explainability rule. The singular variants that appear in the reference-only trees denote the
 * same four categories; that equivalence is declared once here and the two forms are never mixed.
 * This file is pure ASCII, which keeps the non-breaking hyphen carried by the reference-only house
 * document out of reach by construction rather than by care.</p>
 *
 * <p>Assumptions: the substitution's shape is read from the baseline rather than inferred. Line 422
 * accepts the clean status OR the not-found status, which is what makes a first miss recoverable;
 * line 436 tests for the not-found status and line 437 rewrites the group component alone; line 446
 * accepts the clean status ALONE, so a second miss is terminal by construction rather than by
 * policy. That asymmetry is why a terminal case exists below at all.</p>
 *
 * <p>Assumptions: the baseline does not survive that terminal outcome. Line 455 displays its
 * read-failure message and line 458 performs {@code 9999-ABEND-PROGRAM}, which opens at line 628,
 * moves 0 into its timing operand at line 630 and 999 into its abend code at line 631, and calls
 * the environment abend service at line 632 -- a genuine abend with immediate termination. The Java
 * reports the condition instead, carrying the same operator-facing values, and that divergence is
 * registered in {@code docs/architecture/cobol-to-service-traceability.md}, a document referenced
 * here and authored elsewhere.</p>
 *
 * <p>Assumptions: that paragraph must not be conflated with the similarly named one in
 * {@code app/app-transaction-type-db2/cbl/COBTUPDT.cbl}, whose {@code 9999-ABEND} at lines 230 to
 * 233 displays a message, moves 4 into the return code and exits WITHOUT abending. The names differ
 * by one word while the severities differ by everything, and both programs are cited by this
 * package, so the distinction is recorded where the abend values are asserted.</p>
 *
 * <p>Trade-offs: every case here drives the service through a repository stand-in rather than a
 * database. What is given up is any coverage of the query the finder generates, which the
 * container-backed cases in the repository package hold; what it buys is that the read a case did
 * NOT perform is observable, and three of the assertions below turn on exactly that.</p>
 */
@DisplayName("the disclosure-group rate resolution")
class DisclosureGroupServiceTest {

    /**
     * The seeded account group that prices the discriminating pair at the nonzero rate.
     *
     * <p>Assumptions: this value occupies all ten of its declared positions without padding, which
     * is why it is spelled without trailing spaces while the default group below is not.</p>
     */
    private static final String GROUP_WITH_OWN_RATE = "A000000000";

    /**
     * A ten-character account group that the seeded data prices nothing for.
     *
     * <p>Assumptions: it is spelled at exactly ten characters because
     * {@code DisclosureGroup.DisclosureGroupId} refuses any other width outright, so a shorter
     * spelling would fail at key construction rather than model an absent group.</p>
     */
    private static final String GROUP_WITH_NO_ROWS = "GROUPZZZ  ";

    /** The transaction type of the one pair whose rate differs between the two groups. */
    private static final String DISCRIMINATING_TYPE = "07";

    /** The transaction category of the one pair whose rate differs between the two groups. */
    private static final String DISCRIMINATING_CATEGORY = "0001";

    /** The transaction type of the pair no disclosure group prices at all. */
    private static final String UNPRICED_TYPE = "01";

    /** The transaction category of the pair no disclosure group prices at all. */
    private static final String UNPRICED_CATEGORY = "0005";

    /**
     * The rate the group asked for carries at the discriminating pair.
     *
     * <p>Assumptions: read from row 17 of {@code app/data/ASCII/discgrp.txt}, whose rate field
     * occupies the six bytes at offset 16 declared by line 9 of {@code app/cpy/CVTRA02Y.cpy} as
     * {@code PIC S9(04)V99}. The bytes there are five digits followed by the overpunch standing for
     * a positive zero, which decodes to this value at the column's declared scale.</p>
     */
    private static final String RATE_OF_GROUP_ASKED_FOR = "15.00";

    /**
     * The rate the substituted group carries at the same discriminating pair.
     *
     * <p>Assumptions: read from row 34 of the same extract, where the same six positions hold five
     * zero digits and the same overpunch. It differs from the value above, and that difference is
     * the whole reason this pair rather than any other drives the substitution cases.</p>
     */
    private static final String RATE_OF_SUBSTITUTED_GROUP = "0.00";

    /** The abend code the baseline moves at line 631 of {@code app/cbl/CBACT04C.cbl}. */
    private static final String EXPECTED_ABEND_CODE = "999";

    /** The program named as the culprit of a terminal rate miss. */
    private static final String EXPECTED_ABEND_CULPRIT = "CBACT04C";

    /** The reason text the baseline displays at line 455 immediately before performing the abend. */
    private static final String EXPECTED_ABEND_REASON = "ERROR READING DEFAULT DISCLOSURE GROUP";

    /** The message text the baseline displays at line 629 as the abend paragraph's first statement. */
    private static final String EXPECTED_ABEND_MESSAGE = "ABENDING PROGRAM";

    /** The disclosure-group table stand-in, the service's only collaborator. */
    private DisclosureGroupRepository groups;

    /** The service under test, rebuilt per case over a fresh stand-in. */
    private DisclosureGroupService service;

    /**
     * Prepares a fresh stand-in and a fresh service for each case.
     *
     * <p>Assumptions: both are rebuilt per case rather than shared, because several cases below
     * assert the NUMBER of reads performed and one asserts that no read happened at all. An
     * interaction left over from a previous case would satisfy those counts without the case under
     * test having performed anything.</p>
     */
    @BeforeEach
    void setUp() {
        this.groups = Mockito.mock(DisclosureGroupRepository.class);
        this.service = new DisclosureGroupService(this.groups);
    }

    /**
     * Cases over the substituted group identifier, whose trailing spaces are part of the key.
     *
     * <p>Purpose: the identifier the baseline substitutes is ten characters wide and only seven of
     * them are letters. That is not a formatting detail: it is the key the second read actually
     * searches with, so a spelling that dropped the padding would address no row.</p>
     */
    @Nested
    @DisplayName("on the substituted group identifier")
    class OnTheSubstitutedGroupIdentifier {

        /**
         * The published constant is the seven-character literal followed by three spaces.
         *
         * <p>Assumptions: the padding is proven by a PAIR of baseline lines and neither line proves
         * it alone. Line 79 of {@code app/cbl/CBACT04C.cbl} declares the receiving field
         * {@code 10 FD-DIS-ACCT-GROUP-ID           PIC X(10).}, and line 437 moves the
         * SEVEN-character literal {@code 'DEFAULT'} into it. An alphanumeric move into a wider
         * alphanumeric field is left-justified and space-filled, so the key the second read searches
         * with is that literal followed by three spaces. Line 79 alone gives a width with no value
         * moved into it; line 437 alone gives a value with no width to be padded to.</p>
         *
         * <p>Assumptions: the width is asserted against the entity's own declared constant rather
         * than a literal ten, so that a change to the column contract reaches this assertion.</p>
         */
        @Test
        @DisplayName("carry the padding as part of the constant, at the declared column width")
        void carryThePaddingAsPartOfTheConstant() {
            assertThat(DisclosureGroupService.DEFAULT_ACCT_GROUP_ID)
                    .as("line 437 moves a seven-character literal into the field line 79 declares"
                            + " ten wide, so the key on the wire carries three trailing spaces")
                    .isEqualTo("DEFAULT   ")
                    .hasSize(DisclosureGroup.ACCT_GROUP_ID_WIDTH);
            assertThat(DisclosureGroupService.DEFAULT_ACCT_GROUP_ID.substring(7))
                    .as("the three positions after the literal are spaces and not any other filler")
                    .isEqualTo("   ");
            assertThat(DisclosureGroupService.DEFAULT_ACCT_GROUP_ID.strip())
                    .as("the literal line 437 moves is seven characters before padding")
                    .isEqualTo("DEFAULT")
                    .hasSize(7);
        }

        /**
         * A group supplied at the wrong width is refused before any read is attempted.
         *
         * <p>Assumptions: the service pads nothing and trims nothing. Widening a short value belongs
         * at the web edge and refusing a wrong width belongs to the identity type, so a caller that
         * supplies the unpadded seven-character spelling is refused at key construction rather than
         * quietly reshaped into a key that addresses no row.</p>
         *
         * <p>Trade-offs: this asserts the refusal reaches the caller, not which member raised it. The
         * identity type's own width checks are asserted where that type is covered; what is at stake
         * here is that the service does not intercept the refusal and substitute a padded guess, and
         * the absence of any read is what proves it never got as far as trying.</p>
         */
        @Test
        @DisplayName("refuse an unpadded group without reading the table")
        void refuseAnUnpaddedGroupWithoutReadingTheTable() {
            assertThatThrownBy(() -> DisclosureGroupServiceTest.this.service.findRate(
                    "DEFAULT", DISCRIMINATING_TYPE, DISCRIMINATING_CATEGORY))
                    .as("a seven-character group is not silently padded to the declared ten")
                    .isInstanceOf(IllegalArgumentException.class);

            verifyNoInteractions(DisclosureGroupServiceTest.this.groups);
        }
    }

    /**
     * Cases over a direct hit, where the group asked for prices the pair itself.
     *
     * <p>Purpose: the baseline reaches its substitution only from the not-found status at line 436,
     * so a group that prices the pair must be answered from its own row and the second read must not
     * happen at all. These cases keep the stand-in stocked with the substituted group's row as well,
     * so that preferring the wrong one would return a different rate rather than the same one.</p>
     */
    @Nested
    @DisplayName("on a direct hit")
    class OnADirectHit {

        /**
         * The group asked for supplies its own rate and no substitution is reported.
         *
         * <p>Assumptions: the substituted group's row for the SAME pair is loaded into the stand-in and
         * carries a different rate, so this case cannot pass by accident. If the service preferred
         * the substituted read, or performed both and kept the second answer, the rate asserted here
         * would come back as the other value rather than merely arriving by another route.</p>
         */
        @Test
        @DisplayName("answer from the group asked for while the substituted row is also available")
        void answerFromTheGroupAskedForWhileTheSubstitutedRowIsAlsoAvailable() {
            stockBothRowsForTheDiscriminatingPair();

            DisclosureGroupRateResponse reply = DisclosureGroupServiceTest.this.service.findRate(
                    GROUP_WITH_OWN_RATE, DISCRIMINATING_TYPE, DISCRIMINATING_CATEGORY);

            assertRateIs(reply.interestRate(), RATE_OF_GROUP_ASKED_FOR);
            assertThat(reply.appliedAcctGroupId())
                    .as("the group that answered is the one asked for")
                    .isEqualTo(GROUP_WITH_OWN_RATE);
            assertThat(reply.requestedAcctGroupId()).isEqualTo(GROUP_WITH_OWN_RATE);
        }

        /**
         * A direct hit reports the outcome the requested-group source stands for.
         *
         * <p>Assumptions: the published indicator is compared against
         * {@code DisclosureGroupService.RateSource.REQUESTED_GROUP} rather than against a bare
         * false. The two call sites into the conversion differ only by that boolean and both pass
         * the same account group, so a transposed literal would compile and would misreport every
         * outcome while still returning a correct rate. Comparing against the named outcome is what
         * makes the assertion about the mapping rather than about a coincidence of values.</p>
         *
         * <p>Assumptions: the indicator records whether a SUBSTITUTION happened and not which group
         * answered, so a caller that names the default group directly is a direct hit too -- line
         * 436 is reached only after a miss.</p>
         */
        @Test
        @DisplayName("report the requested-group outcome rather than a bare literal")
        void reportTheRequestedGroupOutcome() {
            stockBothRowsForTheDiscriminatingPair();

            DisclosureGroupRateResponse reply = DisclosureGroupServiceTest.this.service.findRate(
                    GROUP_WITH_OWN_RATE, DISCRIMINATING_TYPE, DISCRIMINATING_CATEGORY);

            assertThat(reply.defaultGroupApplied())
                    .as("a direct hit publishes the indicator the requested-group source carries")
                    .isEqualTo(DisclosureGroupService.RateSource.REQUESTED_GROUP
                            .isDefaultGroupApplied());
        }

        /**
         * A direct hit performs exactly one read and never touches the substituted group.
         *
         * <p>Assumptions: the read that did NOT happen is the assertion, and a rate-only case could
         * not make it. The substituted group's row is present in the stand-in, so a service that
         * always performed both reads would still answer with the right rate here; only the absence
         * of the second read distinguishes reading on demand from reading unconditionally.</p>
         */
        @Test
        @DisplayName("read once, leaving the substituted group untouched")
        void readOnceLeavingTheSubstitutedGroupUntouched() {
            stockBothRowsForTheDiscriminatingPair();

            DisclosureGroupServiceTest.this.service.findRate(
                    GROUP_WITH_OWN_RATE, DISCRIMINATING_TYPE, DISCRIMINATING_CATEGORY);

            verify(DisclosureGroupServiceTest.this.groups, never()).findByIdIs(
                    keyFor(DisclosureGroupService.DEFAULT_ACCT_GROUP_ID, DISCRIMINATING_TYPE,
                            DISCRIMINATING_CATEGORY));
            assertThat(DisclosureGroupServiceTest.this.keysRead(1))
                    .singleElement()
                    .extracting(DisclosureGroupId::getAcctGroupId)
                    .isEqualTo(GROUP_WITH_OWN_RATE);
        }

        /**
         * Loads both groups' rows for the discriminating pair into this case's stand-in.
         *
         * <p>Assumptions: both rows exist in the seeded data for this pair and carry different
         * rates, so stocking both is faithful rather than contrived, and it is what lets a case
         * distinguish which read answered from the value alone.</p>
         */
        private void stockBothRowsForTheDiscriminatingPair() {
            stubRowForGroupAskedFor(DisclosureGroupServiceTest.this.groups, GROUP_WITH_OWN_RATE,
                    RATE_OF_GROUP_ASKED_FOR);
            stubRowForSubstitutedGroup(DisclosureGroupServiceTest.this.groups,
                    RATE_OF_SUBSTITUTED_GROUP);
        }
    }

    /**
     * Cases over the substitution onto the padded default group.
     *
     * <p>Purpose: this is the behaviour the whole class exists for and the one the sibling write
     * cases leave unmeasured on its most important axis. Those cases assert the flags and the echoed
     * key on this path but assert no rate, so the value the substitution changes was untested. Every
     * case here drives the discriminating pair, whose two groups carry different rates, so the rate
     * itself distinguishes which read answered.</p>
     */
    @Nested
    @DisplayName("on the substitution onto the default group")
    class OnTheSubstitutionOntoTheDefaultGroup {

        /**
         * A miss on the group asked for is answered with the substituted group's own rate.
         *
         * <p>Assumptions: the value asserted is the substituted group's 0.00 and not the 15.00 the
         * other group carries at this pair, and the second assertion states that difference
         * explicitly. On any of the other sixteen pairs both groups carry identical rates, so an
         * assertion there would hold whether the substitution fired or not; here it can only hold if
         * the second read supplied the answer.</p>
         *
         * <p>Assumptions: the rates are the ASCII twin's. The EBCDIC extract the load job reads
         * carries 15.00 under both groups at this pair, so a case built on that form could not
         * discriminate at all; this module's fixture tree is derived from the ASCII twin and these
         * are its values.</p>
         */
        @Test
        @DisplayName("answer with the substituted group's rate and not the other group's")
        void answerWithTheSubstitutedGroupsRate() {
            stockOnlyTheSubstitutedRow();

            DisclosureGroupRateResponse reply = DisclosureGroupServiceTest.this.service.findRate(
                    GROUP_WITH_NO_ROWS, DISCRIMINATING_TYPE, DISCRIMINATING_CATEGORY);

            assertRateIs(reply.interestRate(), RATE_OF_SUBSTITUTED_GROUP);

            // WHY : Assumptions: the negative assertion is not redundant beside the positive one, and
            //       it is what the pair was chosen for. The positive assertion pins the value; this
            //       one pins the CONTRAST, so a reader can see from the case itself that the two
            //       routes cannot both satisfy it. If the constants were ever moved onto a pair the
            //       two groups price alike, this line is the one that fails.
            assertThat(reply.interestRate().amount())
                    .as("the two groups differ at this pair, so answering with the other group's"
                            + " rate would mean the substitution did not supply this reply")
                    .isNotEqualByComparingTo(RATE_OF_GROUP_ASKED_FOR);
        }

        /**
         * The reply names the padded group that answered while echoing the group asked for.
         *
         * <p>Assumptions: both identifiers are published because they differ on this path and a
         * caller needs each for a different reason -- the one it asked about, and the one whose rate
         * it received. The applied identifier is asserted to be the padded ten-character form, which
         * is what line 437 produces from a seven-character literal.</p>
         */
        @Test
        @DisplayName("name the padded group that answered and echo the group asked for")
        void nameThePaddedGroupThatAnsweredAndEchoTheGroupAskedFor() {
            stockOnlyTheSubstitutedRow();

            DisclosureGroupRateResponse reply = DisclosureGroupServiceTest.this.service.findRate(
                    GROUP_WITH_NO_ROWS, DISCRIMINATING_TYPE, DISCRIMINATING_CATEGORY);

            assertThat(reply.appliedAcctGroupId())
                    .isEqualTo(DisclosureGroupService.DEFAULT_ACCT_GROUP_ID)
                    .hasSize(DisclosureGroup.ACCT_GROUP_ID_WIDTH);
            assertThat(reply.requestedAcctGroupId())
                    .as("the group asked for is echoed untrimmed, exactly as it was searched for")
                    .isEqualTo(GROUP_WITH_NO_ROWS)
                    .hasSize(DisclosureGroup.ACCT_GROUP_ID_WIDTH);
            assertThat(reply.defaultGroupApplied())
                    .as("a substitution publishes the indicator the default-group source carries")
                    .isEqualTo(DisclosureGroupService.RateSource.DEFAULT_GROUP
                            .isDefaultGroupApplied());
        }

        /**
         * The two reads happen in order, and only the group component is rewritten between them.
         *
         * <p>Assumptions: line 437 rewrites exactly one of the three key components. All three are
         * assembled by the caller -- line 210 moves the account group, line 211 the category and line
         * 212 the type -- and the type and category are still holding those values when the second
         * read is performed at line 444. A substitution that also generalised the type or the
         * category would always find a row, which is why the carried-through components are asserted
         * rather than assumed: the rate returned would belong to a different product and, because the
         * lookup would succeed, nothing would report an error.</p>
         *
         * <p>Assumptions: the captured second key is asserted to be the padded ten-character form,
         * which shows the service passes the already-padded constant rather than a shorter spelling
         * that some downstream comparison would have to widen.</p>
         */
        @Test
        @DisplayName("read the group asked for, then the padded group, rewriting the group alone")
        void readTheGroupAskedForThenThePaddedGroupRewritingTheGroupAlone() {
            stockOnlyTheSubstitutedRow();

            DisclosureGroupServiceTest.this.service.findRate(
                    GROUP_WITH_NO_ROWS, DISCRIMINATING_TYPE, DISCRIMINATING_CATEGORY);

            List<DisclosureGroupId> read = DisclosureGroupServiceTest.this.keysRead(2);
            assertThat(read.get(0).getAcctGroupId())
                    .as("the group asked for is searched first and is not trimmed on the way in")
                    .isEqualTo(GROUP_WITH_NO_ROWS);
            assertThat(read.get(1).getAcctGroupId())
                    .as("the second search uses the padded constant, not a shorter spelling")
                    .isEqualTo(DisclosureGroupService.DEFAULT_ACCT_GROUP_ID)
                    .hasSize(DisclosureGroup.ACCT_GROUP_ID_WIDTH);
            assertThat(read).allSatisfy(key -> {
                assertThat(key.getTranTypeCd()).isEqualTo(DISCRIMINATING_TYPE);
                assertThat(key.getTranCatCd()).isEqualTo(DISCRIMINATING_CATEGORY);
            });
        }

        /**
         * The two routes into this pair resolve different money, so the pair discriminates.
         *
         * <p>Alternatives Considered: leaving this property to the rate assertions above. Rejected
         * because those assertions rest on it: if a subsequent edit moved these cases onto a pair whose
         * two groups price alike, each of them would keep passing while proving nothing, and the
         * failure would be silent in exactly the direction that reads as coverage. Stating the
         * property as its own case means the pair's suitability fails loudly instead.</p>
         *
         * <p>Assumptions: this asserts the two ROUTES answer differently through the service, which
         * is a different claim from the byte-level census of the seed and the fixture tree. That
         * census is asserted where those bytes are owned and is deliberately not repeated here.</p>
         */
        @Test
        @DisplayName("resolve different money by each route, which is what makes the pair discriminate")
        void resolveDifferentMoneyByEachRoute() {
            Money onADirectHit = rateThroughAnIndependentService(GROUP_WITH_OWN_RATE, true);
            Money afterSubstituting = rateThroughAnIndependentService(GROUP_WITH_NO_ROWS, false);

            assertThat(onADirectHit.amount())
                    .as("were these equal, every substitution assertion in this group would pass"
                            + " without the substitution having fired")
                    .isNotEqualByComparingTo(afterSubstituting.amount());
            assertRateIs(onADirectHit, RATE_OF_GROUP_ASKED_FOR);
            assertRateIs(afterSubstituting, RATE_OF_SUBSTITUTED_GROUP);
        }

        /**
         * Loads only the substituted group's row, leaving the group asked for absent.
         *
         * <p>Assumptions: the group asked for is stubbed to no row rather than left unstubbed. The
         * stand-in would answer an unstubbed call with an empty optional anyway, so stating the miss
         * explicitly is what makes the arrangement legible as a modelled absence rather than as an
         * omission a reader has to infer.</p>
         */
        private void stockOnlyTheSubstitutedRow() {
            stubMissForGroupAskedFor(DisclosureGroupServiceTest.this.groups, GROUP_WITH_NO_ROWS,
                    DISCRIMINATING_TYPE, DISCRIMINATING_CATEGORY);
            stubRowForSubstitutedGroup(DisclosureGroupServiceTest.this.groups,
                    RATE_OF_SUBSTITUTED_GROUP);
        }
    }

    /**
     * Cases over the terminal miss, which the substitution cannot resolve.
     *
     * <p>Purpose: the substitution resolves most misses and not all of them. The reference data
     * carries one transaction type and category pair that no disclosure group prices, so the terminal
     * path is reachable and has to be handled rather than assumed away. Treating it as unreachable
     * would leave an absent rate flowing onward and accrue nothing while reporting success.</p>
     */
    @Nested
    @DisplayName("on a terminal miss")
    class OnATerminalMiss {

        /**
         * The unpriced pair is reported as a distinct condition rather than as an absent value.
         *
         * <p>Assumptions: the pair used here is the one the reference data leaves unpriced under
         * EVERY group, the substituted group included, so the terminal path is exercised by a real
         * property of the data rather than by an arrangement invented for the case. The byte-level
         * census establishing that property is asserted where the seed and fixture bytes are owned
         * and is not repeated here.</p>
         *
         * <p>Assumptions: the condition is a distinct subclass rather than the bare supertype,
         * because it carries the key that missed and the operator record alongside the sentence.</p>
         */
        @Test
        @DisplayName("report the unpriced pair as its own condition")
        void reportTheUnpricedPairAsItsOwnCondition() {
            stockNoRowAtAll();

            assertThatThrownBy(() -> DisclosureGroupServiceTest.this.service.findRate(
                    GROUP_WITH_OWN_RATE, UNPRICED_TYPE, UNPRICED_CATEGORY))
                    .isInstanceOf(DisclosureGroupService.DisclosureGroupNotFoundException.class)
                    .hasMessage(DisclosureGroupService.MESSAGE_RATE_NOT_FOUND);
        }

        /**
         * The condition is a no-such-element type, which is what routes it to a 404.
         *
         * <p>Assumptions: {@code com.carddemo.common.error.GlobalExceptionHandler} is the only
         * exception advice declared in this reactor, and the member that answers with 404 is declared
         * against the standard no-such-element type. This condition therefore reaches that status by
         * being a subtype of it, and re-parenting it to any other unchecked type would silently turn
         * the published 404 into a 500 while every other assertion here kept passing.</p>
         *
         * <p>Trade-offs: what is asserted is the subtype link this file owns, not the status code
         * itself. The advice's own cases assert the code, and restating it here would let one of the
         * two satisfy the property while both reported it as satisfied. The message is asserted to be
         * the catalogue sentence for the same reason it matters: the advice renders a not-found
         * message verbatim only when it comes from the catalogue, and substitutes a generic sentence
         * otherwise.</p>
         */
        @Test
        @DisplayName("raise a no-such-element type, which is the route to the published 404")
        void raiseANoSuchElementTypeWhichIsTheRouteToThePublished404() {
            stockNoRowAtAll();

            assertThatThrownBy(() -> DisclosureGroupServiceTest.this.service.findRate(
                    GROUP_WITH_OWN_RATE, UNPRICED_TYPE, UNPRICED_CATEGORY))
                    .as("the shared advice answers this supertype with 404, so the link is the"
                            + " property that must not regress")
                    .isInstanceOf(NoSuchElementException.class);
        }

        /**
         * The condition carries the key that missed, untrimmed and with leading zeros intact.
         *
         * <p>Assumptions: the key travels on the condition rather than in the published sentence. An
         * error body that echoed the account group back would widen what an unauthenticated probe
         * could learn about which groups exist, while an operational record needs the key exactly as
         * it was searched for in order to be actionable.</p>
         */
        @Test
        @DisplayName("carry the key that missed, untrimmed and with leading zeros intact")
        void carryTheKeyThatMissedUntrimmedAndWithLeadingZerosIntact() {
            stockNoRowAtAll();

            DisclosureGroupService.DisclosureGroupNotFoundException thrown = terminalMissFor(
                    GROUP_WITH_OWN_RATE, UNPRICED_TYPE, UNPRICED_CATEGORY);

            assertThat(thrown.getRequestedAcctGroupId())
                    .isEqualTo(GROUP_WITH_OWN_RATE)
                    .hasSize(DisclosureGroup.ACCT_GROUP_ID_WIDTH);
            assertThat(thrown.getTranTypeCd()).isEqualTo(UNPRICED_TYPE);
            assertThat(thrown.getTranCatCd())
                    .as("the category keeps its leading zeros, its source picture being zero-filled")
                    .isEqualTo(UNPRICED_CATEGORY);
        }

        /**
         * The operator record reproduces the four values the baseline abend carries.
         *
         * <p>Assumptions: the four values are drawn from the baseline rather than composed afresh, so
         * that an operator can line a migrated diagnostic up against a reference job log. The code is
         * the 999 moved at line 631, the culprit is the program the rule lives in, the reason is the
         * message displayed at line 455 immediately before the abend is performed at line 458, and
         * the message is the one displayed at line 629 as the abend paragraph's first statement.</p>
         *
         * <p>Assumptions: the reason names the DEFAULT read's failure and not the other one. The
         * program has two distinct abend sites on this path: line 434 for a first read that failed
         * for some reason other than a miss, displaying its own message at line 431, and line 458 for
         * the substituted read. Only the second is reachable from a terminal miss, and reproducing
         * the first message here would misreport which read gave up.</p>
         *
         * <p>Assumptions: the values are asserted unpadded. The record's constructor truncates a
         * component that exceeds its declared width and substitutes blank for absent, but it does not
         * pad a shorter one, so the three-character code stays three characters.</p>
         */
        @Test
        @DisplayName("reproduce the baseline abend code, culprit, reason and message")
        void reproduceTheBaselineAbendCodeCulpritReasonAndMessage() {
            stockNoRowAtAll();

            AbendDetail detail = terminalMissFor(GROUP_WITH_OWN_RATE, UNPRICED_TYPE,
                    UNPRICED_CATEGORY).getAbendDetail();

            assertThat(detail).isNotNull();
            assertThat(detail.abendCode()).isEqualTo(EXPECTED_ABEND_CODE);
            assertThat(detail.abendCulprit()).isEqualTo(EXPECTED_ABEND_CULPRIT);
            assertThat(detail.abendReason())
                    .as("line 455 names the substituted read, which is the only one a terminal miss"
                            + " can have given up on")
                    .isEqualTo(EXPECTED_ABEND_REASON);
            assertThat(detail.abendMsg()).isEqualTo(EXPECTED_ABEND_MESSAGE);
        }

        /**
         * Both reads are attempted before the condition is raised, and no third read follows.
         *
         * <p>Assumptions: the substituted read must actually be attempted, which a condition-only
         * assertion could not establish. A service that raised straight off the first miss would
         * satisfy every other case in this group while never substituting at all, and on a pair the
         * substituted group DOES price that shortcut would turn a resolvable lookup into a failure.
         * The absence of a third read is asserted in the same breath, because the substituted
         * paragraph at line 443 has no substitution of its own and therefore cannot recurse.</p>
         */
        @Test
        @DisplayName("attempt both reads before raising, and no more than both")
        void attemptBothReadsBeforeRaisingAndNoMoreThanBoth() {
            stockNoRowAtAll();

            terminalMissFor(GROUP_WITH_OWN_RATE, UNPRICED_TYPE, UNPRICED_CATEGORY);

            List<DisclosureGroupId> read = DisclosureGroupServiceTest.this.keysRead(2);
            assertThat(read.get(0).getAcctGroupId()).isEqualTo(GROUP_WITH_OWN_RATE);
            assertThat(read.get(1).getAcctGroupId())
                    .isEqualTo(DisclosureGroupService.DEFAULT_ACCT_GROUP_ID);
        }

        /**
         * Stubs both reads of the unpriced pair to no row, which is the terminal arrangement.
         *
         * <p>Assumptions: both keys are stubbed explicitly rather than relying on the stand-in's
         * default, so the arrangement reads as two modelled absences and a subsequent reader cannot
         * mistake it for a case that simply forgot to stub the substituted group.</p>
         */
        private void stockNoRowAtAll() {
            stubMissForGroupAskedFor(DisclosureGroupServiceTest.this.groups, GROUP_WITH_OWN_RATE,
                    UNPRICED_TYPE, UNPRICED_CATEGORY);
            stubMissForSubstitutedGroup(DisclosureGroupServiceTest.this.groups, UNPRICED_TYPE,
                    UNPRICED_CATEGORY);
        }
    }

    /**
     * Cases over the shape of the reply and of the outcome discriminator.
     *
     * <p>Purpose: every other lookup in this bounded context answers with a window over many rows,
     * and this one answers with a single reply. That difference is asserted explicitly rather than
     * left implicit, because the reference program resolves one rate per balance row and never
     * enumerates rates, so there is no walk here to page over.</p>
     */
    @Nested
    @DisplayName("on the reply shape")
    class OnTheReplyShape {

        /**
         * Both entry points answer with one reply object and never with a sequence of them.
         *
         * <p>Assumptions: the absence of a window is asserted by testing the declared return type
         * against the standard sequence interface rather than by naming a particular envelope type.
         * Every window type this stack could return implements that interface, so the check catches
         * any of them, and it keeps holding if a different one is introduced afterwards. Naming one
         * envelope would only exclude the one named.</p>
         *
         * @throws NoSuchMethodException if either entry point is renamed without this case being
         *     updated, which fails the case rather than skipping it
         */
        @Test
        @DisplayName("answer with a single reply and never with a window over rows")
        void answerWithASingleReplyAndNeverWithAWindowOverRows() throws NoSuchMethodException {
            // WHY : Assumptions: BOTH names are checked rather than only the one the route calls.
            //       They are two spellings of one lookup, and a reply shape asserted on one of them
            //       would leave the other free to answer differently while every behavioural case
            //       here kept passing, because those cases bind to one name apiece.
            for (String name : List.of("findRate", "resolveRate")) {
                Method entryPoint = DisclosureGroupService.class.getDeclaredMethod(
                        name, String.class, String.class, String.class);

                assertThat(entryPoint.getReturnType())
                        .as("%s answers with the single rate reply", name)
                        .isEqualTo(DisclosureGroupRateResponse.class);

                // WHY : Alternatives Considered: naming a particular window envelope type and
                //       asserting the return type is not it. Rejected because it would exclude only
                //       the one type named, and a different envelope introduced afterwards would
                //       pass. Every window envelope in this stack is iterable, so testing against
                //       that one standard-library supertype excludes the whole family at once and
                //       keeps no envelope type in this file's imports.
                assertThat(Iterable.class.isAssignableFrom(entryPoint.getReturnType()))
                        .as("%s must not answer with anything iterable, which is what every window"
                                + " envelope is", name)
                        .isFalse();
                assertThat(entryPoint.getParameterTypes())
                        .as("%s takes the three key components and nothing that positions a window",
                                name)
                        .containsExactly(String.class, String.class, String.class);
            }
        }

        /**
         * The discriminator names both outcomes and maps each to the indicator the reply publishes.
         *
         * <p>Assumptions: the discriminator exists so the published boolean is derived from a named
         * outcome rather than written as a bare literal at each conversion call site. Those two call
         * sites differ only by that boolean and both pass the same account group, so a transposed
         * literal would compile and would misreport every substitution while returning a rate that is
         * otherwise correct.</p>
         *
         * <p>Assumptions: the count is asserted as well as the mapping. A third outcome added without
         * a corresponding decision about what it publishes would be the way this mapping silently
         * acquires a case nothing has reasoned about.</p>
         */
        @Test
        @DisplayName("name exactly two outcomes and map each to its published indicator")
        void nameExactlyTwoOutcomesAndMapEachToItsPublishedIndicator() {
            assertThat(DisclosureGroupService.RateSource.values())
                    .as("a direct read and a substitution are the only two outcomes the baseline has")
                    .hasSize(2);
            assertThat(DisclosureGroupService.RateSource.REQUESTED_GROUP.isDefaultGroupApplied())
                    .isFalse();
            assertThat(DisclosureGroupService.RateSource.DEFAULT_GROUP.isDefaultGroupApplied())
                    .isTrue();
        }
    }

    /**
     * Builds a disclosure row at the declared scale for one three-part key.
     *
     * <p>Assumptions: the rate is supplied as text and converted through {@code BigDecimal}'s text
     * constructor rather than from a primitive literal, because the primitive forms cannot represent
     * most two-place decimal fractions exactly and would seed a case with an amount that is already
     * approximate before the service ever reads it.</p>
     *
     * @param acctGroupId the ten-character account group the row belongs to, passed unaltered
     * @param tranTypeCd the two-character transaction type of the row
     * @param tranCatCd the four-character transaction category of the row, leading zeros intact
     * @param rate the rate as plain decimal text, for example {@code "15.00"}
     * @return a {@code DisclosureGroup} carrying that key and that rate; never {@code null}
     */
    private static DisclosureGroup rowFor(String acctGroupId, String tranTypeCd, String tranCatCd,
            String rate) {

        return new DisclosureGroup(keyFor(acctGroupId, tranTypeCd, tranCatCd), new BigDecimal(rate));
    }

    /**
     * Builds the composite identity the finder is keyed on.
     *
     * <p>Assumptions: the components are handed to the identity type exactly as received. That type
     * checks each one for its declared width and refuses any other, so this helper deliberately
     * neither pads nor trims -- doing either would hide the very property several cases assert.</p>
     *
     * @param acctGroupId the ten-character account group component
     * @param tranTypeCd the two-character transaction type component
     * @param tranCatCd the four-character transaction category component
     * @return the {@code DisclosureGroupId} for those three components; never {@code null}
     */
    private static DisclosureGroupId keyFor(String acctGroupId, String tranTypeCd,
            String tranCatCd) {

        return new DisclosureGroupId(acctGroupId, tranTypeCd, tranCatCd);
    }

    /**
     * Asserts a published rate equals the expected amount in value and in declared scale.
     *
     * <p>Alternatives Considered: comparing with value equality, which is the ordinary way to
     * compare two amounts and is wrong here. {@code BigDecimal} equality compares scale as well as
     * unscaled value, so an amount spelled with no decimal places answers false against the same
     * amount spelled with two even though the two are numerically identical. Comparing numerically
     * and asserting the scale separately states both properties and cannot be satisfied by either
     * one alone.</p>
     *
     * <p>Assumptions: the scale asserted is the one the owning column declares, taken from the
     * entity's own constant rather than written as a literal, so a change to the column contract
     * reaches this assertion instead of leaving it agreeing with a number nothing else uses.</p>
     *
     * @param published the rate carried on the reply, as the shared money type
     * @param expected the expected amount as plain decimal text, for example {@code "0.00"}
     */
    private static void assertRateIs(Money published, String expected) {
        assertThat(published.amount())
                .as("the rate must match numerically, whatever scale each side is spelled at")
                .isEqualByComparingTo(expected);

        // WHY : Assumptions: the scale needs its own assertion because the comparison above cannot
        //       carry it. Numeric comparison is deliberately scale-insensitive, so a rate that
        //       arrived at scale 0 or 4 would satisfy it, and the consumer that multiplies this
        //       value before dividing depends on the operand's declared scale rather than only on
        //       its magnitude. Stating both is what makes the pair equivalent to the column
        //       contract instead of to half of it.
        assertThat(published.amount().scale())
                .as("the rate must be carried at the scale the owning column declares")
                .isEqualTo(DisclosureGroup.INTEREST_RATE_SCALE);
    }

    /**
     * Stubs the read of the group asked for so it resolves to a row at the given rate.
     *
     * <p>Assumptions: the key is stubbed by equality on the composite identity, which the identity
     * type supports over all three of its components. That is what lets a case stub the two reads
     * independently and therefore observe which of them the service performed.</p>
     *
     * @param table the stand-in to stub
     * @param acctGroupId the ten-character account group whose own row is being supplied
     * @param rate the rate that row carries, as plain decimal text
     */
    private static void stubRowForGroupAskedFor(DisclosureGroupRepository table, String acctGroupId,
            String rate) {

        when(table.findByIdIs(keyFor(acctGroupId, DISCRIMINATING_TYPE, DISCRIMINATING_CATEGORY)))
                .thenReturn(Optional.of(rowFor(acctGroupId, DISCRIMINATING_TYPE,
                        DISCRIMINATING_CATEGORY, rate)));
    }

    /**
     * Stubs the read of the substituted group at the discriminating pair.
     *
     * <p>Assumptions: the key is built from {@code DisclosureGroupService.DEFAULT_ACCT_GROUP_ID}
     * rather than from a literal spelled here, so a case cannot pass against a padding this file
     * invented while the service searches with a different one.</p>
     *
     * @param table the stand-in to stub
     * @param rate the rate the substituted group's row carries, as plain decimal text
     */
    private static void stubRowForSubstitutedGroup(DisclosureGroupRepository table, String rate) {
        when(table.findByIdIs(keyFor(DisclosureGroupService.DEFAULT_ACCT_GROUP_ID,
                DISCRIMINATING_TYPE, DISCRIMINATING_CATEGORY)))
                .thenReturn(Optional.of(rowFor(DisclosureGroupService.DEFAULT_ACCT_GROUP_ID,
                        DISCRIMINATING_TYPE, DISCRIMINATING_CATEGORY, rate)));
    }

    /**
     * Stubs the read of the group asked for so it resolves to no row.
     *
     * @param table the stand-in to stub
     * @param acctGroupId the ten-character account group being modelled as unpriced for this pair
     * @param tranTypeCd the two-character transaction type of the key that misses
     * @param tranCatCd the four-character transaction category of the key that misses
     */
    private static void stubMissForGroupAskedFor(DisclosureGroupRepository table, String acctGroupId,
            String tranTypeCd, String tranCatCd) {

        when(table.findByIdIs(keyFor(acctGroupId, tranTypeCd, tranCatCd)))
                .thenReturn(Optional.empty());
    }

    /**
     * Stubs the read of the substituted group so it resolves to no row.
     *
     * @param table the stand-in to stub
     * @param tranTypeCd the two-character transaction type of the key that misses
     * @param tranCatCd the four-character transaction category of the key that misses
     */
    private static void stubMissForSubstitutedGroup(DisclosureGroupRepository table,
            String tranTypeCd, String tranCatCd) {

        when(table.findByIdIs(keyFor(DisclosureGroupService.DEFAULT_ACCT_GROUP_ID, tranTypeCd,
                tranCatCd))).thenReturn(Optional.empty());
    }

    /**
     * Resolves the discriminating pair through a service built over its own independent stand-in.
     *
     * <p>Assumptions: a separate stand-in is built here rather than reusing the one the lifecycle
     * supplies, because the one case that calls this needs BOTH routes resolved within a single test
     * and the two arrangements would otherwise overwrite one another. Re-invoking the lifecycle
     * method by hand was the alternative and is avoided: a test that reaches into its own fixture
     * setup reads as though the lifecycle had not run.</p>
     *
     * @param acctGroupId the ten-character account group to ask about
     * @param groupAskedForHasItsOwnRow whether the group asked for is given a row of its own, which
     *     selects the direct-hit route when {@code true} and the substitution route when
     *     {@code false}
     * @return the rate the reply carries, as the shared money type; never {@code null}
     */
    private static Money rateThroughAnIndependentService(String acctGroupId,
            boolean groupAskedForHasItsOwnRow) {

        DisclosureGroupRepository table = Mockito.mock(DisclosureGroupRepository.class);
        if (groupAskedForHasItsOwnRow) {
            stubRowForGroupAskedFor(table, acctGroupId, RATE_OF_GROUP_ASKED_FOR);
        } else {
            stubMissForGroupAskedFor(table, acctGroupId, DISCRIMINATING_TYPE,
                    DISCRIMINATING_CATEGORY);
        }
        stubRowForSubstitutedGroup(table, RATE_OF_SUBSTITUTED_GROUP);

        return new DisclosureGroupService(table)
                .findRate(acctGroupId, DISCRIMINATING_TYPE, DISCRIMINATING_CATEGORY)
                .interestRate();
    }

    /**
     * Runs a lookup that is expected to miss under both groups and returns the condition raised.
     *
     * <p>Assumptions: the condition is captured through a typed catch rather than through an
     * assertion helper's returned handle, because three cases need to read its own accessors and its
     * operator record, which the general handle does not expose. A lookup that unexpectedly
     * SUCCEEDED is failed explicitly here, so a service that stopped raising could not leave those
     * three cases quietly examining nothing.</p>
     *
     * @param acctGroupId the ten-character account group to ask about
     * @param tranTypeCd the two-character transaction type expected to be unpriced
     * @param tranCatCd the four-character transaction category expected to be unpriced
     * @return the {@code DisclosureGroupNotFoundException} the service raised; never {@code null}
     * @throws AssertionError if the lookup resolved a rate instead of raising, which would make the
     *     cases calling this vacuous
     */
    private DisclosureGroupService.DisclosureGroupNotFoundException terminalMissFor(
            String acctGroupId, String tranTypeCd, String tranCatCd) {

        try {
            DisclosureGroupRateResponse unexpected =
                    this.service.findRate(acctGroupId, tranTypeCd, tranCatCd);

            // WHY : Assumptions: the failure is raised INSIDE the guarded block on purpose, and the
            //       catch below is narrowed to the one condition so it cannot swallow it. A broader
            //       catch, or a failure raised after the block, would turn "a rate resolved when none
            //       should have" into a silent pass -- which is the direction of mistake that leaves
            //       the three cases reading this record examining an object nothing produced.
            throw new AssertionError("expected a terminal miss for " + tranTypeCd + "/" + tranCatCd
                    + " under both the group asked for and the substituted group, but a rate"
                    + " resolved: " + unexpected);
        } catch (DisclosureGroupService.DisclosureGroupNotFoundException expected) {
            return expected;
        }
    }

    /**
     * Captures the identities the finder was called with, in the order it was called.
     *
     * <p>Assumptions: the ORDER matters and is asserted by callers, because the baseline reads the
     * group asked for first and reaches the substituted group only from the not-found status at line
     * 436. A pair of reads performed in the other order would resolve the same rate on this pair
     * while inverting which group is preferred, and only the order distinguishes the two.</p>
     *
     * @param expectedReads the exact number of reads the case expects the service to have performed
     * @return the captured identities in invocation order, one per read; never {@code null}
     */
    private List<DisclosureGroupId> keysRead(int expectedReads) {
        ArgumentCaptor<DisclosureGroupId> captor = ArgumentCaptor.forClass(DisclosureGroupId.class);
        verify(this.groups, times(expectedReads)).findByIdIs(captor.capture());

        // WHY : Trade-offs: this helper closes the interaction set as well as counting it, so every
        //       caller gets an EXHAUSTIVE count rather than a lower bound. The cost is that a caller
        //       wanting to verify something further afterwards cannot, which no case here needs; what
        //       it buys is that an extra read nobody reasoned about fails the case that counted the
        //       reads instead of passing silently underneath it.
        verifyNoMoreInteractions(this.groups);
        return captor.getAllValues();
    }
}
