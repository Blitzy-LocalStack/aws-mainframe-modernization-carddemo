// =============================================================================
// services/reference-service/src/test/java/com/carddemo/reference/repository/DisclosureGroupRepositoryIT.java
// -----------------------------------------------------------------------------
// WHAT:
//      The container-backed integration tests of DisclosureGroupRepository, and
//      the one place in this migration where the fallback disclosure group is
//      established as present in the schema at the width its key declares, with
//      its rate carried across from the seed file's zoned-decimal bytes without
//      loss.
//
// WHY (non-obvious design decisions):
//  (1) Assumptions: the four rationale labels in this file are the PLURAL forms
//      -- Alternatives Considered:, Refactoring Rationale:, Assumptions: and
//      Trade-offs: -- taken from the project rules document at its lines 31 to
//      34 and written as docs/CODE_DOCUMENTATION_STANDARD.md rules them. The
//      singular spellings mean the same thing and are deliberately not used, so
//      that a label can be found by grep before it is read by a person. This
//      equivalence is stated once here and nowhere restated.
//  (2) Refactoring Rationale: the seed assertions below are made HERE even
//      though nothing in reference-service consumes them, and the reason is a
//      consequence landing in a different service. batch-service resolves one
//      rate per category balance and, when the account's own group has no row,
//      app/cbl/CBACT04C.cbl substitutes the fallback literal at line 437 and
//      reads again at line 444. That second read does not degrade when it finds
//      nothing: line 455 reports 'ERROR READING DEFAULT DISCLOSURE GROUP' and
//      line 458 performs 9999-ABEND-PROGRAM, whose body at lines 628 to 632
//      displays 'ABENDING PROGRAM', moves 999 to the abend code and calls the
//      language environment's abend service. A fallback row absent from this
//      seed therefore presents as a termination inside a service that has no
//      defect of its own, with nothing in the failure pointing back at the seed
//      that caused it. The assertion belongs where the data is owned.
//  (3) Alternatives Considered: the real PostgreSQL engine the shared base
//      starts, rather than an in-memory one. Two properties asserted here are
//      the engine's own rather than this repository's arithmetic -- whether a
//      declared-width character column returns ten bytes for a seven-character
//      literal, and whether an exact fixed-point column returns the scale it
//      declares. An in-memory engine reached through a different migration
//      dialect answers whatever it was configured to answer, so every assertion
//      below would pass while establishing neither.
//  (4) Alternatives Considered: expressing the expected rates as decimal
//      literals, which needs no fixture and no codec. Rejected because the
//      property worth establishing is that the seed transcribed the on-disk
//      zoned-decimal representation without loss, and a literal restates that
//      conclusion instead of deriving it. Every rate compared below is decoded
//      from the fixture bytes through the production codecs, so the trailing
//      sign overpunch is genuinely exercised on the way to the comparison.
//  (5) Trade-offs: this class starts no engine of its own, because the shared
//      base starts one for the whole package; what it does add is the per-case
//      transaction described on the class below, which trades the realism of a
//      committed write for seed counts that are identical however often and in
//      whatever order the class runs.
//  (6) Assumptions: everything beneath app/ is the behavioural oracle of this
//      migration. It is read, cited by path and physical line, and never
//      modified; where the migrated behaviour departs from it deliberately the
//      departure is registered in
//      docs/architecture/cobol-to-service-traceability.md, which is maintained
//      elsewhere and referenced rather than reproduced. Every line cited here
//      is a PHYSICAL line read at that address; app/cbl/CBACT04C.cbl carries no
//      printed sequence field, so it has only the one address and the two
//      cannot disagree.
// =============================================================================
package com.carddemo.reference.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.carddemo.common.codec.CopybookLayout;
import com.carddemo.common.codec.FixedWidthCodec;
import com.carddemo.common.codec.ZonedDecimalCodec;
import com.carddemo.reference.domain.DisclosureGroup;
import com.carddemo.reference.service.DisclosureGroupService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * Verifies the disclosure-group read, the fallback group the interest accrual depends on, and the
 * exactness of the rate the seed carried across from the source file's bytes.
 *
 * <p>Assumptions: the fallback group id is ten characters wide and its trailing spaces are data rather
 * than formatting. {@code app/cbl/CBACT04C.cbl} declares the field at line 79 as
 * {@code 10 FD-DIS-ACCT-GROUP-ID           PIC X(10).} inside the key group that line 50 names as the
 * record key, and at line 437 it substitutes the seven-character literal {@code 'DEFAULT'} into that
 * ten-byte alphanumeric field. A short literal moved into a longer alphanumeric field is left-justified
 * and space-filled, so the key actually searched for is that literal followed by three spaces, and all
 * 17 fallback rows of {@code app/data/ASCII/discgrp.txt} carry exactly that form in their first ten
 * bytes.</p>
 *
 * <p>Assumptions: <b>a declared-width character column ignores trailing blanks when it is compared</b>,
 * so a predicate written against the unpadded literal matches a stored padded value -- and matches a
 * stored value that had lost its padding just as readily. A case that established the padding with an
 * equality predicate would therefore pass against a column that had silently trimmed it, and would
 * establish nothing at all. Every padding assertion in this class is made on the value READ BACK, by
 * its length and its content, and never by a predicate. The identity type closes the other half of the
 * same trap: {@code DisclosureGroup.DisclosureGroupId} checks each component for exact width in its
 * constructor, so an unpadded probe is refused before any query is issued.</p>
 *
 * <p>Assumptions: the two termination sites in that program are distinct and are not conflated here.
 * The one this class protects belongs to the fallback read, reported at line 455 after the read at line
 * 444 that carries no invalid-key clause; the other fires on the group-specific read, reported at line
 * 431 as {@code 'ERROR READING DISCLOSURE GROUP FILE'} after the read at line 416 that does carry such
 * a clause. Line 422 accepts a status of {@code '00'} or {@code '23'}, and it is the record-not-found
 * status alone, tested at line 436, that reaches the substitution at line 437 and the
 * {@code 1200-A-GET-DEFAULT-INT-RATE} paragraph opening at line 443.</p>
 *
 * <p>Assumptions: the rate is exact base-ten at every hop and is compared as such. Two decimal values
 * of equal magnitude and unequal scale are NOT equal under value equality, so every comparison below is
 * made by magnitude and the scale is then asserted separately against
 * {@code DisclosureGroup.INTEREST_RATE_SCALE}. Value equality is never applied to a rate here, because
 * it would report a correct magnitude carried at the wrong scale as a mismatch and would reject nothing
 * that a magnitude comparison accepts.</p>
 *
 * <p>Assumptions: the rate matters because it is an operand and not a value that is merely displayed.
 * {@code app/cbl/CBACT04C.cbl} guards the computation at line 214 with {@code IF DIS-INT-RATE NOT = 0}
 * before performing it at line 215, and computes at lines 464 to 465
 * {@code COMPUTE WS-MONTHLY-INT = ( TRAN-CAT-BAL * DIS-INT-RATE) / 1200} inside the paragraph opening
 * at line 462, forming the product before the quotient. That arithmetic belongs to batch-service and is
 * not implemented here; this class establishes only that the value handed to it is the value the seed
 * file holds.</p>
 *
 * <p>Assumptions: this dataset ships in TWO baseline extracts that disagree at exactly one field of one
 * record, and the fixture and the column are deliberately drawn from different ones. The divergence, the
 * settlement that resolves it and the reason each side keeps the extract it keeps are recorded beside the
 * constants naming it, so a reader meeting the one required disagreement in the loop below finds it
 * accounted for rather than surprising.</p>
 *
 * <p>Trade-offs: the class carries a per-case transaction, so the one case that writes a row leaves the
 * table exactly as it found it. What that trades away is the realism of a committed write, which no
 * assertion here needs, and what it buys is that the two seed counts are identical on every run and in
 * any order -- including when the whole package shares one engine, as it does.</p>
 */
@Transactional
class DisclosureGroupRepositoryIT extends ReferencePersistenceBase {

    /** The classpath prefix the disclosure-group fixtures sit beneath. */
    private static final String FIXTURE_ROOT = "fixtures/disclosure_group/";

    /** The registry name of the layout transcribed from {@code app/cpy/CVTRA02Y.cpy}. */
    private static final String LAYOUT = "DISGROUP";

    /** The fixture holding the 17 account-group rows followed by the 17 fallback rows. */
    private static final String FALLBACK_FIXTURE = "default_fallback/discgrp.txt";

    /** The fixture holding the single first row of the source seed file. */
    private static final String FIRST_ROW_FIXTURE = "happy_path/discgrp.txt";

    /** Every row the seed migration loads into {@code reference.disclosure_groups}. */
    private static final int SEEDED_ROWS = 51;

    /** The rows of that seed carrying the fallback group id, one per type and category pair. */
    private static final int SEEDED_FALLBACK_ROWS = 17;

    /** The records the fallback fixture holds: the 17 fallback rows and the 17 they shadow. */
    private static final int FALLBACK_FIXTURE_RECORDS = 34;

    /** The account group the seed loads alongside the fallback group and the zero-rate group. */
    private static final String SEEDED_GROUP = "A000000000";

    /** The type component of the pair the fallback group carries a non-zero rate for. */
    private static final String RATED_TYPE_CD = "01";

    /** The category component of the pair the fallback group carries a non-zero rate for. */
    private static final String RATED_CAT_CD = "0001";

    /** The type component of a pair the seed loads at an explicit zero rate. */
    private static final String ZERO_RATED_TYPE_CD = "02";

    // WHY : Assumptions: the dataset ships in TWO baseline extracts and they are byte-identical except
    //       at one field of one record. app/data/EBCDIC/AWS.M2.CARDDEMO.DISCGRP.PS record 34 -- the
    //       fallback group's ('07','0001') pair -- holds the rate span 00150{ while row 34 of
    //       app/data/ASCII/discgrp.txt holds 00000{, so the same pair reads 15.00 from one extract and
    //       0.00 from the other. Measured across all fifty-one records the rate multiplicities differ
    //       accordingly, 29/16/6 in the EBCDIC extract against 30/15/6 in the ASCII one for
    //       0.00/15.00/25.00, and every other byte of every record agrees.
    // WHY : Assumptions: the two sides of this class are deliberately drawn from DIFFERENT extracts, and
    //       neither choice is incidental. V2__seed_reference.sql seeds this table from the EBCDIC extract
    //       and records why at its lines 236 to 262, registering the divergence as
    //       D-SEED-ENCODING-AUTHORITY in docs/architecture/cobol-to-service-traceability.md; the fixture
    //       is derived from the ASCII extract and its own README records at section 2.4 that the EBCDIC
    //       form would leave the fallback scenario with no discriminating pair at all. Asserting the
    //       divergence rather than smoothing it is therefore what pins BOTH decisions: a fixture
    //       re-derived from the EBCDIC extract would make the scenario vacuous, and a table re-seeded
    //       from the ASCII extract would accrue nothing where the authoritative extract accrues 15.00%.
    // WHY : Trade-offs: this is the one row of the thirty-four where the fixture and the column are
    //       required to DISAGREE, which costs the loop a branch and costs a reader this note. Comparing
    //       the two extracts as though they agreed would have failed on a correct table, and adjusting
    //       either extract to remove the question is refused outright because app/ is read-only.
    /** The type component of the one pair where the two baseline extracts disagree. */
    private static final String DIVERGENT_TYPE_CD = "07";

    /** The rate the ASCII-derived fixture holds at that pair for the fallback group. */
    private static final String ASCII_EXTRACT_RATE = "0.00";

    /** The rate the authoritative EBCDIC extract holds at that pair, and the seeded value. */
    private static final String EBCDIC_EXTRACT_RATE = "15.00";

    /** The field name of the account group, exactly as {@code CVTRA02Y.cpy} line 6 declares it. */
    private static final String FIELD_GROUP_ID = "DIS-ACCT-GROUP-ID";

    /** The field name of the transaction type, exactly as {@code CVTRA02Y.cpy} line 7 declares it. */
    private static final String FIELD_TYPE_CD = "DIS-TRAN-TYPE-CD";

    /** The field name of the transaction category, exactly as line 8 declares it. */
    private static final String FIELD_CAT_CD = "DIS-TRAN-CAT-CD";

    /** The field name of the interest rate, exactly as {@code CVTRA02Y.cpy} line 9 declares it. */
    private static final String FIELD_RATE = "DIS-INT-RATE";

    /** The integer digits of {@code DIS-INT-RATE PIC S9(04)V99}. */
    private static final int RATE_INT_DIGITS = 4;

    /** The fractional digits of {@code DIS-INT-RATE PIC S9(04)V99}. */
    private static final int RATE_DEC_DIGITS = 2;

    // WHY : Assumptions: this one character is the whole reason the fixture is decoded rather than
    //       read as text. It is the trailing-sign overpunch standing for a positive low-order zero,
    //       so the six bytes 00150{ are the value 15.00 and not a string ending in a brace. All 51
    //       rows of app/data/ASCII/discgrp.txt end their rate span in this character, because every
    //       seeded rate is positive with a zero in its hundredths place. The overpunch belongs to the
    //       on-disk representation alone and must never reach a column, which is what
    //       assertNoOverpunchReachedTheColumns establishes for every row this class reads back.
    /** The positive-zero trailing-sign overpunch every seeded rate span ends in. */
    private static final String POSITIVE_ZERO_OVERPUNCH = "{";

    // WHY : Assumptions: this group id is deliberately absent from the seed, and it is padded with
    //       spaces to the ten characters the key declares so that the write path is exercised on a
    //       value whose padding is significant. Reusing a seeded group id would have made the one
    //       writing case an update of a row another case reads, so a regression in either would
    //       surface in both and neither would be attributable.
    /** An unseeded, space-padded group id used by the single case that writes a row. */
    private static final String PROBE_GROUP_ID = "PROBE     ";

    // WHY : Assumptions: this key is absent from the seed in all three components, so the empty
    //       result it produces cannot be explained by a partial match on any one of them. Its group
    //       component is padded to ten characters because the identity type refuses any other width,
    //       which is the same check the fallback probe passes through.
    /** A key absent from the seed, used to establish that a miss is reported rather than substituted. */
    private static final String UNSEEDED_GROUP_ID = "NOSUCHGRP ";

    /** The transaction type of the unseeded key. */
    private static final String UNSEEDED_TYPE_CD = "99";

    /** The transaction category of the unseeded key. */
    private static final String UNSEEDED_CAT_CD = "9999";

    /** The repository under test. */
    @Autowired
    private DisclosureGroupRepository groups;

    // WHY : Alternatives Considered: counting the fallback rows by reading the whole table and
    //       filtering in Java. Rejected because this interface deliberately declares no walk over this
    //       table -- the cited program resolves one rate per balance row and never enumerates rates --
    //       so a walk here would be a query no baseline program performs and would sit against the
    //       ruling the package charter records. An aggregate over one group is neither a walk nor a
    //       window: it returns a single number and cannot express a starting position, so it does not
    //       reach for the distance-positioned mechanism the charter excludes from this package.
    // WHY : Assumptions: the aggregate's own comparison IS a predicate over a declared-width character
    //       column, and that is sound here precisely because it is counting rather than establishing a
    //       width. The padding is proven separately, on a value read back, by
    //       theFallbackGroupIsSeededAtTheWidthItsKeyDeclares.
    // WHY : Assumptions: the same handle is what lets the writing case clear the persistence context
    //       between its write and its read. Without that clear, the read returns the instance the
    //       write left behind and the column is never consulted, so the case would assert against its
    //       own argument rather than against storage.
    /** The persistence handle used for the fallback-row aggregate and for the one context clear. */
    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Confirms the fallback disclosure group is seeded at the ten-character width its key declares.
     *
     * <p>Assumptions: this is the assertion the package charter marks as never omitted, and its form is
     * load-bearing. The group id is read back and then examined by LENGTH and by CONTENT; the presence
     * of the row alone would not establish the padding, because the column's comparison ignores
     * trailing blanks and would have matched a trimmed value equally well.</p>
     *
     * <p>Assumptions: the expected literal is taken from
     * {@code DisclosureGroupService.DEFAULT_ACCT_GROUP_ID} as well as being written out, so the
     * published constant and the stored value are held to the same ten characters. Were the two to
     * diverge, the service would probe with one form while the seed held the other.</p>
     *
     * <p>It takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the fallback disclosure group is seeded at the ten-character width its key declares")
    void theFallbackGroupIsSeededAtTheWidthItsKeyDeclares() {
        Optional<DisclosureGroup> found = this.groups.findByIdIs(new DisclosureGroup.DisclosureGroupId(
                DisclosureGroupService.DEFAULT_ACCT_GROUP_ID, RATED_TYPE_CD, RATED_CAT_CD));

        assertThat(found)
                .as("app/cbl/CBACT04C.cbl line 458 terminates when this row cannot be read")
                .isPresent();

        String storedGroupId = found.get().getAcctGroupId();
        assertThat(storedGroupId)
                .as("app/cbl/CBACT04C.cbl line 79 declares the field ten characters wide")
                .hasSize(DisclosureGroup.ACCT_GROUP_ID_WIDTH);
        assertThat(storedGroupId)
                .as("line 437 moves a seven-character literal into it, so three spaces follow")
                .isEqualTo("DEFAULT   ")
                .isEqualTo(DisclosureGroupService.DEFAULT_ACCT_GROUP_ID)
                .startsWith("DEFAULT")
                .endsWith("   ");
    }

    /**
     * Confirms the identity type refuses the unpadded fallback literal before any query is issued.
     *
     * <p>Assumptions: this is what makes the vacuous form of the padding assertion unreachable rather
     * than merely discouraged. A composite key over blank-padded columns that accepted two widths would
     * let one logical key exist in two forms, only one of which matches a stored row, and the mismatch
     * would surface as absent data rather than as a malformed key -- which for this table means an
     * interest figure accrued at another group's rate instead of an error at the lookup.</p>
     *
     * <p>It takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the identity type refuses the unpadded fallback literal")
    void theIdentityTypeRefusesTheUnpaddedFallbackLiteral() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .as("a seven-character group id can never reach a query through this identity")
                .isThrownBy(() -> new DisclosureGroup.DisclosureGroupId(
                        "DEFAULT", RATED_TYPE_CD, RATED_CAT_CD));
    }

    /**
     * Confirms the seed loads 51 rows of which 17 carry the fallback group.
     *
     * <p>Assumptions: both numbers come from {@code app/data/ASCII/discgrp.txt} rather than from the
     * migration that loads it. That file holds 51 records of 50 bytes, and its first ten bytes take
     * exactly three distinct values at 17 records each, so 51 is three groups of 17 and the fallback
     * group is one of the three.</p>
     *
     * <p>Assumptions: the cross-table audit that sums this table with the other five belongs to
     * {@code TransactionTypeRepositoryIT}, which owns the grand total; only the two numbers for THIS
     * table are asserted here, so neither statement can be satisfied in one place and reported as
     * satisfied in both.</p>
     *
     * <p>It takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the seed loads 51 disclosure-group rows of which 17 carry the fallback group")
    void theSeedLoadsFiftyOneRowsOfWhichSeventeenCarryTheFallbackGroup() {
        assertThat(this.groups.count())
                .as("app/data/ASCII/discgrp.txt holds 51 records of 50 bytes")
                .isEqualTo(SEEDED_ROWS);
        assertThat(rowsCarryingGroup(DisclosureGroupService.DEFAULT_ACCT_GROUP_ID))
                .as("17 of those records begin with the padded fallback group id")
                .isEqualTo(SEEDED_FALLBACK_ROWS);
        assertThat(rowsCarryingGroup(SEEDED_GROUP))
                .as("the fallback group shadows one rate per pair the account group carries")
                .isEqualTo(SEEDED_FALLBACK_ROWS);
    }

    /**
     * Confirms every seeded rate matches the fixture bytes it was derived from, at the declared scale.
     *
     * <p>Assumptions: the fixture is decoded through the production codecs rather than parsed here, so
     * the offsets under test are the registered ones and not a second transcription of the copybook.
     * {@code CopybookLayout} places the rate at zero-based offset 16 over six bytes, which is columns 17
     * to 22 counted from one; the geometry is asserted before the first record is read, because a
     * shifted offset otherwise surfaces as a value mismatch on every row and reads as a seeding fault
     * rather than as a layout fault.</p>
     *
     * <p>Assumptions: each record's rate is decoded twice, once as part of the whole record and once
     * directly from its span, and the two are required to agree. The record-level decode is the one a
     * consumer would use; the span-level decode is what pins it to offset 16, since the two can only
     * agree if the record-level decode read the same six bytes.</p>
     *
     * <p>Assumptions: the two seeded groups this fixture carries are asserted by KEYED READ, one row at
     * a time, which is the only access path this interface offers and the one the cited program uses.
     * The counted result is then held to 17 fallback rows out of 34 records, so a fixture that had lost
     * or gained a row could not satisfy the case by coincidence.</p>
     *
     * <p>Assumptions: exactly ONE of the thirty-four records is required to disagree with the column,
     * because the fixture and the seed are drawn from the two baseline extracts of this dataset and those
     * two disagree at exactly one field of one record. The divergence, the authority that settles it and
     * the reason each side keeps the extract it keeps are recorded beside the three constants this case
     * reads. Both values are asserted, so neither side can be quietly re-derived from the other's
     * extract.</p>
     *
     * <p>It takes no parameter.</p>
     *
     * @throws IOException if the fixture cannot be read from the test classpath
     */
    @Test
    @DisplayName("every seeded rate matches its fixture bytes except the one encoding divergence")
    void everySeededRateMatchesTheFixtureBytesItWasDerivedFrom() throws IOException {
        CopybookLayout.RecordSpec layout = CopybookLayout.layout(LAYOUT);
        assertThat(layout.reclen())
                .as("app/cpy/CVTRA02Y.cpy line 2 declares RECLN = 50")
                .isEqualTo(50);
        assertThat(ZonedDecimalCodec.widthOf(RATE_INT_DIGITS, RATE_DEC_DIGITS))
                .as("PIC S9(04)V99 occupies six bytes, the sign folded into the last of them")
                .isEqualTo(6);

        List<byte[]> records = recordsOf(bytesOf(FALLBACK_FIXTURE), layout.reclen());
        assertThat(records)
                .as("17 account-group rows followed by the 17 fallback rows they are shadowed by")
                .hasSize(FALLBACK_FIXTURE_RECORDS);

        List<String> groupIds = new ArrayList<>();
        TreeSet<String> distinctRates = new TreeSet<>();
        int divergences = 0;
        for (byte[] record : records) {
            Map<String, Object> fields = decode(record, layout);
            String rateSpan = rateSpanOf(record);
            assertThat(rateSpan)
                    .as("every seeded rate is positive with a zero in its hundredths place")
                    .endsWith(POSITIVE_ZERO_OVERPUNCH);

            BigDecimal decodedRate = (BigDecimal) fields.get(FIELD_RATE);
            assertThat(ZonedDecimalCodec.decode(rateSpan, RATE_INT_DIGITS, RATE_DEC_DIGITS, true))
                    .as("the record-level decode read the span at zero-based offset 16")
                    .isEqualByComparingTo(decodedRate);

            DisclosureGroup.DisclosureGroupId key = keyOf(fields);
            Optional<DisclosureGroup> stored = this.groups.findByIdIs(key);
            assertThat(stored).as("the seed loads every record of the source dataset").isPresent();

            DisclosureGroup row = stored.get();
            if (isTheEncodingDivergence(row)) {
                divergences++;
                assertThat(decodedRate)
                        .as("row 34 of app/data/ASCII/discgrp.txt holds the rate span 00000{")
                        .isEqualByComparingTo(ASCII_EXTRACT_RATE);
                assertThat(row.getInterestRate())
                        .as("record 34 of the authoritative EBCDIC extract holds 00150{ instead")
                        .isEqualByComparingTo(EBCDIC_EXTRACT_RATE);
            } else {
                assertThat(row.getInterestRate())
                        .as("the stored rate is the fixture's own value, compared by magnitude")
                        .isEqualByComparingTo(decodedRate);
            }
            assertThat(row.getInterestRate().scale())
                    .as("V1__reference.sql line 337 declares interest_rate NUMERIC(6,2)")
                    .isEqualTo(DisclosureGroup.INTEREST_RATE_SCALE);
            assertNoOverpunchReachedTheColumns(row);

            groupIds.add(row.getAcctGroupId());
            distinctRates.add(row.getInterestRate().toPlainString());
        }

        assertThat(divergences)
                .as("the two baseline extracts disagree at exactly one field of one record")
                .isEqualTo(1);
        assertThat(Collections.frequency(groupIds, DisclosureGroupService.DEFAULT_ACCT_GROUP_ID))
                .as("half of the fixture is the fallback group, at its padded ten characters")
                .isEqualTo(SEEDED_FALLBACK_ROWS);
        assertThat(distinctRates)
                .as("the dataset holds three distinct rate spans and no others")
                .containsExactly(ASCII_EXTRACT_RATE, EBCDIC_EXTRACT_RATE, "25.00");
    }

    /**
     * Confirms a decoded fixture row written to the column keeps its padding and leaves its overpunch.
     *
     * <p>Assumptions: this is the write direction of the same contract the seed assertions cover in the
     * read direction. The seed rows establish that a padded group id and an exact rate can be READ; this
     * case establishes that they survive being WRITTEN, which the seed cannot show because the migration
     * inserted those rows before any of this code ran.</p>
     *
     * <p>Assumptions: the persistence context is cleared between the write and the read. Without that,
     * the read is answered from the instance the write left behind and the column is never consulted, so
     * the case would compare its own argument with itself. The write is flushed first, because writes
     * are otherwise deferred and a cleared context would discard a statement that had not been
     * issued.</p>
     *
     * <p>Assumptions: the row is written under an unseeded group id, so it neither collides with a
     * seeded key nor alters a row another case reads, and the per-case transaction on this class
     * discards it either way.</p>
     *
     * <p>It takes no parameter.</p>
     *
     * @throws IOException if the fixture cannot be read from the test classpath
     */
    @Test
    @DisplayName("a decoded fixture row written to the column keeps its padding and drops its overpunch")
    void aDecodedFixtureRowWrittenToTheColumnKeepsItsPadding() throws IOException {
        CopybookLayout.RecordSpec layout = CopybookLayout.layout(LAYOUT);
        List<byte[]> records = recordsOf(bytesOf(FIRST_ROW_FIXTURE), layout.reclen());
        assertThat(records).as("the fixture holds the single first row of the source file").hasSize(1);

        byte[] record = records.get(0);
        assertThat(rateSpanOf(record))
                .as("the first row of app/data/ASCII/discgrp.txt carries a rate of 15.00")
                .isEqualTo("00150" + POSITIVE_ZERO_OVERPUNCH);

        Map<String, Object> fields = decode(record, layout);
        DisclosureGroup.DisclosureGroupId probeKey = new DisclosureGroup.DisclosureGroupId(
                PROBE_GROUP_ID, (String) fields.get(FIELD_TYPE_CD), categoryOf(fields));
        this.groups.saveAndFlush(new DisclosureGroup(probeKey, (BigDecimal) fields.get(FIELD_RATE)));
        this.entityManager.clear();

        Optional<DisclosureGroup> written = this.groups.findByIdIs(probeKey);
        assertThat(written).as("the row just written is readable back out of the column").isPresent();

        DisclosureGroup row = written.get();
        assertThat(row.getAcctGroupId())
                .as("the write path neither trims the trailing spaces nor adds any")
                .hasSize(DisclosureGroup.ACCT_GROUP_ID_WIDTH);
        assertThat(row.getAcctGroupId()).isEqualTo(PROBE_GROUP_ID);
        assertThat(row.getInterestRate())
                .as("the zoned span 00150 followed by its overpunch is the value 15.00")
                .isEqualByComparingTo("15.00");
        assertThat(row.getInterestRate().scale()).isEqualTo(DisclosureGroup.INTEREST_RATE_SCALE);
        assertNoOverpunchReachedTheColumns(row);
    }

    /**
     * Confirms a zero rate is stored as an explicit scale-two zero rather than as an absent value.
     *
     * <p>Assumptions: zero is a meaningful rate here and not a missing one.
     * {@code app/cbl/CBACT04C.cbl} guards the computation at line 214 with
     * {@code IF DIS-INT-RATE NOT = 0} before performing it at line 215, so a zero rate SUPPRESSES
     * interest generation for that balance. Reporting such a row as absent would send its caller to the
     * fallback group and accrue interest at another group's rate where the cited program accrues
     * none.</p>
     *
     * <p>Assumptions: 30 of the 51 seeded rate spans are the zero span, so this is the majority case in
     * the source file rather than an edge of it.</p>
     *
     * <p>It takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a zero rate is stored as an explicit scale-two zero rather than as an absent value")
    void aZeroRateIsStoredAsAnExplicitScaleTwoZero() {
        Optional<DisclosureGroup> zeroRated = this.groups.findByIdIs(
                new DisclosureGroup.DisclosureGroupId(
                        DisclosureGroupService.DEFAULT_ACCT_GROUP_ID, ZERO_RATED_TYPE_CD, RATED_CAT_CD));
        assertThat(zeroRated).as("the zero-rate row is seeded like any other").isPresent();

        BigDecimal rate = zeroRated.get().getInterestRate();
        assertThat(rate)
                .as("V1__reference.sql line 337 declares interest_rate NOT NULL")
                .isNotNull()
                .isEqualByComparingTo("0.00");
        assertThat(rate.scale())
                .as("the column's declared scale is carried even where the magnitude is zero")
                .isEqualTo(DisclosureGroup.INTEREST_RATE_SCALE);
        assertThat(rate.signum()).as("the seeded zero is neither negative nor unsigned").isZero();
    }

    /**
     * Confirms an unseeded key is reported absent rather than substituted inside the repository.
     *
     * <p>Assumptions: choosing to read the fallback group when the requested group has no row is a
     * business rule and belongs one layer out, in {@code DisclosureGroupService}, which reads twice and
     * can therefore report whether a substitution happened. A repository that substituted silently would
     * make a gap in the seed indistinguishable from a genuine rate, and the group whose absence
     * terminates the cited program would then have no symptom at all.</p>
     *
     * <p>It takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("an unseeded key is reported absent rather than substituted inside the repository")
    void anUnseededKeyIsReportedAbsent() {
        assertThat(this.groups.findByIdIs(new DisclosureGroup.DisclosureGroupId(
                UNSEEDED_GROUP_ID, UNSEEDED_TYPE_CD, UNSEEDED_CAT_CD)))
                .as("the substitution at app/cbl/CBACT04C.cbl line 437 is the service's decision")
                .isEmpty();
    }

    /**
     * Counts the rows carrying one account group, as a seed audit rather than as a window.
     *
     * @param acctGroupId the account group to count, at the ten characters its column declares
     * @return the number of seeded rows whose account group is that value
     */
    private long rowsCarryingGroup(String acctGroupId) {
        return this.entityManager.createQuery(
                        "select count(dg) from DisclosureGroup dg where dg.id.acctGroupId = :groupId",
                        Long.class)
                .setParameter("groupId", acctGroupId)
                .getSingleResult();
    }

    /**
     * Reads one disclosure-group fixture from the test classpath.
     *
     * @param relative the path below {@value #FIXTURE_ROOT}, for example
     *     {@code happy_path/discgrp.txt}
     * @return the file's exact bytes, transcoded by nothing on the way out
     * @throws IllegalStateException if the fixture is absent from the classpath, which is a build or
     *     rename fault rather than an assertion failure
     * @throws IOException if the stream cannot be read
     */
    private static byte[] bytesOf(String relative) throws IOException {
        try (InputStream in = DisclosureGroupRepositoryIT.class.getClassLoader()
                .getResourceAsStream(FIXTURE_ROOT + relative)) {
            if (in == null) {
                throw new IllegalStateException("fixture absent from the test classpath: "
                        + FIXTURE_ROOT + relative);
            }
            return in.readAllBytes();
        }
    }

    /**
     * Splits a fixture into fixed-width records, rejecting any residue and any carriage return.
     *
     * <p>Assumptions: the three seed files this tree derives from do NOT share one line ending.
     * {@code discgrp.txt} is line-feed only -- 51 records of 50 bytes plus 51 separators is its 2601
     * bytes exactly -- while {@code trantype.txt} and {@code trancatg.txt} ship carriage returns. A
     * reader that tolerated a carriage return would pull a 51st byte into a 50-byte record and every
     * field after the first would decode one position late, which presents as a field-offset fault
     * rather than as a line-ending one. The separator is therefore asserted byte by byte.</p>
     *
     * @param raw the fixture's exact bytes
     * @param width the record width the layout declares
     * @return one array per record, each exactly {@code width} bytes long
     */
    private static List<byte[]> recordsOf(byte[] raw, int width) {
        int stride = width + 1;
        assertThat(raw.length % stride)
                .as("file length %d is not a whole number of %d-byte records plus one separator each",
                        raw.length, width)
                .isZero();

        List<byte[]> records = new ArrayList<>();
        for (int offset = 0; offset + width <= raw.length; offset += stride) {
            assertThat(raw[offset + width])
                    .as("the record at offset %d must be followed by a line feed and nothing else",
                            offset)
                    .isEqualTo((byte) '\n');
            byte[] record = new byte[width];
            System.arraycopy(raw, offset, record, 0, width);
            records.add(record);
        }
        return records;
    }

    /**
     * Decodes one record through the production codec and the registered layout.
     *
     * <p>Assumptions: the charset is named rather than left to a default, because the fixture is
     * seven-bit text and the codec verifies that its own decoding is byte-reversible. A charset that
     * altered a byte count would be reported at the field rather than silently absorbed.</p>
     *
     * @param record the 50 bytes of one disclosure-group record
     * @param layout the registered layout to decode against
     * @return the decoded fields, keyed by the names the copybook declares, in declaration order
     */
    private static Map<String, Object> decode(byte[] record, CopybookLayout.RecordSpec layout) {
        return FixedWidthCodec.decodeRecord(record, layout, StandardCharsets.US_ASCII);
    }

    /**
     * Extracts the six raw characters of the rate span, at the offset the layout registers.
     *
     * @param record the 50 bytes of one disclosure-group record
     * @return the six characters at zero-based offset 16, the last of them the sign overpunch
     */
    private static String rateSpanOf(byte[] record) {
        return new String(record, 16, ZonedDecimalCodec.widthOf(RATE_INT_DIGITS, RATE_DEC_DIGITS),
                StandardCharsets.US_ASCII);
    }

    /**
     * Rebuilds the composite identity from a decoded record.
     *
     * @param fields the decoded fields of one record
     * @return the identity the three key components form
     */
    private static DisclosureGroup.DisclosureGroupId keyOf(Map<String, Object> fields) {
        return new DisclosureGroup.DisclosureGroupId(
                (String) fields.get(FIELD_GROUP_ID), (String) fields.get(FIELD_TYPE_CD),
                categoryOf(fields));
    }

    /**
     * Renders the decoded category back into the four zero-filled digits its column stores.
     *
     * <p>Assumptions: the codec decodes this field to an integral value because
     * {@code app/cpy/CVTRA02Y.cpy} line 8 declares it {@code PIC 9(04)}, so the leading zeros are lost
     * in the decode and have to be restored. The column is character data four wide -- the baseline's
     * own table definition declares it so and the source file stores the codes zero-padded -- and an
     * integral probe would compare 1 against a stored {@code 0001} and match nothing.</p>
     *
     * @param fields the decoded fields of one record
     * @return the category code as four digits with its leading zeros intact
     */
    private static String categoryOf(Map<String, Object> fields) {
        return String.format("%0" + DisclosureGroup.TRAN_CAT_CD_WIDTH + "d",
                (Long) fields.get(FIELD_CAT_CD));
    }

    /**
     * Reports whether a row is the one pair at which the two baseline extracts disagree.
     *
     * <p>Assumptions: the three components are compared as CHARACTER data, which is what they are: the
     * group at its padded ten characters, the type at two and the category at four with its leading
     * zeros intact. No decimal is compared this way anywhere in this class, for the reason the class
     * states -- two decimals of equal magnitude and unequal scale are not equal under value equality, so
     * a rate is only ever compared by magnitude with its scale asserted separately.</p>
     *
     * @param row the row read back out of the engine
     * @return {@code true} when the row is the fallback group at the divergent type and category,
     *     {@code false} for every other row of the dataset
     */
    private static boolean isTheEncodingDivergence(DisclosureGroup row) {
        return DisclosureGroupService.DEFAULT_ACCT_GROUP_ID.equals(row.getAcctGroupId())
                && DIVERGENT_TYPE_CD.equals(row.getTranTypeCd())
                && RATED_CAT_CD.equals(row.getTranCatCd());
    }

    /**
     * Asserts that no value read back out of a row carries the sign overpunch character.
     *
     * <p>Assumptions: the overpunch belongs to the on-disk representation alone. Its arrival in a column
     * would mean the rate span had been stored as text instead of decoded, and the symptom would be a
     * rate that read plausibly while being one hundred times its intended magnitude in the hundredths
     * place -- a value no length or nullability constraint rejects.</p>
     *
     * @param row the row read back out of the engine
     */
    private static void assertNoOverpunchReachedTheColumns(DisclosureGroup row) {
        assertThat(row.getAcctGroupId()).doesNotContain(POSITIVE_ZERO_OVERPUNCH);
        assertThat(row.getTranTypeCd()).doesNotContain(POSITIVE_ZERO_OVERPUNCH);
        assertThat(row.getTranCatCd()).doesNotContain(POSITIVE_ZERO_OVERPUNCH);
        assertThat(row.getInterestRate().toPlainString()).doesNotContain(POSITIVE_ZERO_OVERPUNCH);
    }
}
