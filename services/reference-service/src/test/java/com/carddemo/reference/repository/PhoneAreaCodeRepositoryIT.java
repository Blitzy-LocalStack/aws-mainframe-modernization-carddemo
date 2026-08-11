// =============================================================================
// services/reference-service/src/test/java/com/carddemo/reference/repository/PhoneAreaCodeRepositoryIT.java
// -----------------------------------------------------------------------------
// Purpose:
//      The container-backed integration tests of PhoneAreaCodeRepository, the
//      classification-scoped membership predicate over
//      reference.us_phone_area_codes. This class owns the fourth of the five
//      assertions package-info.java states at its lines 401 to 463: that the
//      phone-code classification is a DISJOINT and TOTAL two-value partition,
//      ruled at its lines 438 to 448.
//
// WHY (non-obvious design decisions):
//  (1) Assumptions: the four rationale labels used throughout this file are
//      written in the PLURAL form -- Alternatives Considered:, Refactoring
//      Rationale:, Assumptions: and Trade-offs: -- which is the form
//      docs/CODE_DOCUMENTATION_STANDARD.md rules at its lines 236 to 251. The
//      singular spellings carry the same meaning and are deliberately unused,
//      so that every rationale in this tree is reachable by one literal
//      search. The equivalence is stated once here and is not repeated below.
//  (2) Assumptions: the figures asserted below are measured from the copybook
//      rather than quoted from prose. app/cpy/CSLKPCDY.cpy declares
//      VALID-PHONE-AREA-CODE at L30 over 490 literals terminating at L520,
//      VALID-GENERAL-PURP-CODE at L521 over 410 terminating at L930, and
//      VALID-EASY-RECOG-AREA-CODE at L931 over 80 terminating at L1010. All
//      three read the one field declared at L24 as PIC XXX. The two sublists
//      share no member, 410 plus 80 is exactly 490, and their union equals the
//      full list in both directions; V2__seed_reference.sql carries those same
//      three sets, member for member.
//  (3) Assumptions: an extraction trap sits INSIDE the first clause and yields
//      exactly the wrong number. app/cpy/CSLKPCDY.cpy L440 is a comment line,
//      carrying an asterisk in column 7 and reading "Easily recognizable codes
//      begin here.", and it ends with a period. A reader or an extractor that
//      takes that period for the clause terminator stops there and counts 410
//      where the clause holds 490 and terminates at L520. That same comment is
//      the copybook's own boundary marker: L30 to L439 is precisely the
//      410-member general-purpose block and L441 to L520 precisely the
//      80-member easily-recognisable one, which is why ONE classification
//      column reproduces the copybook's own ordering rather than losing it.
//  (4) Alternatives Considered: asserting only that the table holds 490 rows,
//      which is the shorter case and the one a reader reaches for first.
//      Rejected with a named consequence: a seed that collapsed every row onto
//      a single classification, or that left the classification absent, still
//      holds 490 rows and would pass a bare count unchanged. The 410-and-80
//      counts, the empty intersection and the union checked in both directions
//      are what make the cardinality mean the copybook was TRANSCRIBED rather
//      than merely that the table was populated.
//  (5) Alternatives Considered: reading the three condition names as
//      overlapping lists and asserting a subset relation in place of a
//      partition. Refuted by the arithmetic above and by package-info.java
//      lines 445 to 448, which rule that the arithmetic is the authority and
//      that this assertion may not be weakened to a subset relation or an
//      approximate count. Two overlapping sets cannot sum to the size of their
//      union, so the two readings are not both available. The copybook itself
//      is unambiguous and nothing in app/cpy/CSLKPCDY.cpy is at fault: the
//      baseline declares three condition names over one field and the
//      arithmetic between them is exact. It is a READING that can be wrong,
//      and the assertions below encode the measured one, so a reader arriving
//      from the overlapping description finds the discrepancy named here
//      rather than silently resolved.
// =============================================================================

package com.carddemo.reference.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.carddemo.reference.domain.UsPhoneAreaCode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * Verifies that the classification-scoped predicate partitions the allow-list exactly as the
 * baseline's condition names do.
 *
 * <p><b>Purpose.</b> {@code app/cbl/COACTUPC.cbl} tests {@code IF VALID-GENERAL-PURP-CODE}, the
 * 410-literal condition name declared at {@code app/cpy/CSLKPCDY.cpy} L521, and it tests neither
 * {@code VALID-PHONE-AREA-CODE}, the 490-literal union at L30, nor
 * {@code VALID-EASY-RECOG-AREA-CODE}, the 80-literal list at L931. This class asserts that
 * {@link PhoneAreaCodeRepository} draws the same line, and that the seeded table underneath it is a
 * total two-value partition rather than a list with a decorative flag: 410 general-purpose plus 80
 * easily-recognisable is exactly 490, no code carries both classifications, no code carries neither,
 * and the union of the two is set-equal to the whole table in both directions.</p>
 *
 * <p>Assumptions: the classification values are taken from
 * {@link UsPhoneAreaCode#CODE_CLASS_GENERAL_PURPOSE} and
 * {@link UsPhoneAreaCode#CODE_CLASS_EASILY_RECOGNISABLE} and never spelled inline. The letters are a
 * schema fact -- {@code V1__reference.sql} constrains the column to two of them at its L407 -- so a
 * test that hard-coded them would keep passing if the entity's published constants and the stored
 * values ever diverged, which is the one failure a caller of this predicate would actually see.</p>
 *
 * <p>Assumptions: {@code V1__reference.sql} is the SOLE source of this table's column names, and
 * every column named in this file was read there rather than inferred. No baseline table exists to
 * derive them from -- the copybook declares condition names over a working-storage field, not a
 * record layout with a stored key -- so {@code area_cd} and {@code code_class} have no second
 * authority to reconcile against, and {@code package-info.java} rules the same at its L328. A column
 * name guessed from the copybook's own hyphenated field name would compile, because the mapping
 * lives in the entity rather than here, and would then fail at a schema validation whose message
 * names the entity and not this file.</p>
 *
 * <p>Assumptions: the two sample codes are read from the copybook rather than chosen for
 * convenience. {@code '201'} is the first literal of the general-purpose list at
 * {@code app/cpy/CSLKPCDY.cpy} L521 and appears nowhere under L931; {@code '200'} is the first
 * literal of the easily-recognisable list at L931 and appears nowhere under L521. Each was confirmed
 * absent from the other list before being used here, so neither assertion below can pass merely
 * because a code happened to carry both classifications.</p>
 *
 * <p>Alternatives Considered: one table with a classification column rather than two tables, one per
 * sublist. All three condition names hang off the SINGLE field declared at
 * {@code app/cpy/CSLKPCDY.cpy} L24 as {@code PIC XXX}, so a code and its classification are one
 * fact in the baseline. Two tables would make a code representable in both or in neither, and the
 * copybook can express neither state; one column constrained to two values makes both
 * unrepresentable. The consequence for this class is that the partition has to be asserted as a
 * property of a column rather than read off two row counts, which is what the cases below do.</p>
 *
 * <p>Alternatives Considered: an in-memory engine in place of the PostgreSQL container the shared
 * base starts. Rejected because three of the properties asserted here are the engine's own and not
 * the mapping's: that {@code CHAR(3)} returns a value of exactly three characters, that the primary
 * key refuses a duplicate at the statement rather than at commit, and that the check constraint at
 * {@code V1__reference.sql} L407 refuses a third classification. An engine that accepted a wider
 * domain, or deferred either refusal, would leave every case here green while the deployed schema
 * behaved differently.</p>
 *
 * <p>Refactoring Rationale: the cardinality is pinned at this level because a DIFFERENT bounded
 * context consumes this table without owning it. {@code account-service}'s
 * {@code AddressValidationService} queries the three lookup tables and seeds none of them, so an
 * area code missing from this seed does not fail anything here -- it surfaces there as an address
 * refused during account maintenance, with nothing in the refusal naming the seed that caused it.
 * Pinning the figures where they are owned is what makes that failure attributable. The direction of
 * the dependency is one-way and stays that way: no type from that context is imported here.</p>
 *
 * <p>Trade-offs: the shared container is started once for the package and its start-up cost is
 * accepted so that the seed figures are measured against the real migrations rather than described.
 * A doubled repository would let every case below pass without either migration having run, which
 * would leave the one property they exist to establish -- that {@code V1__reference.sql} and
 * {@code V2__seed_reference.sql} together produce the copybook's own sets -- asserted by nothing.</p>
 *
 * <p>Trade-offs: the three cases that write are annotated individually so each rolls back, and what
 * that gives up is commit-visibility realism: no case here observes a refused row as a separate
 * connection would after a commit. What it buys is that the counted figures stay exact in any
 * execution order and under the parallel running this module's runners allow, which matters because
 * the property under audit is completeness -- a tolerance wide enough to absorb another case's
 * leftover row is also wide enough to absorb a missing one.</p>
 */
class PhoneAreaCodeRepositoryIT extends ReferencePersistenceBase {

    // WHY : Assumptions: these are declared int rather than long even though the repository count
    //       returns a long, because each is compared BOTH against a long count and against a
    //       collection size. An int widens to long at the first comparison and is accepted directly
    //       by the second, whereas a long constant cannot be handed to a size assertion at all, so
    //       one declared type serves both readings and no cast appears at any assertion site.
    /** Every code the seed loads, across both classifications, from {@code CSLKPCDY.cpy} L30. */
    private static final int SEEDED_CODES = 490;

    /** The general-purpose sublist the baseline's account-maintenance path accepts, from L521. */
    private static final int GENERAL_PURPOSE_CODES = 410;

    /** The easily-recognisable sublist the baseline's account-maintenance path declines, from L931. */
    private static final int EASILY_RECOGNISABLE_CODES = 80;

    /** The two-character state codes the seed loads, from {@code CSLKPCDY.cpy} L1013. */
    private static final int SEEDED_STATES = 56;

    /** The state-and-postal-prefix combinations the seed loads, from {@code CSLKPCDY.cpy} L1073. */
    private static final int SEEDED_STATE_ZIP_PREFIXES = 240;

    /** The lookup subtotal the three tables another context reads must sum to. */
    private static final int LOOKUP_SUBTOTAL = 786;

    /** The declared width of {@code area_cd}, from {@code PIC XXX} at {@code CSLKPCDY.cpy} L24. */
    private static final int AREA_CODE_WIDTH = 3;

    /** The first literal of the general-purpose list at {@code app/cpy/CSLKPCDY.cpy} L521. */
    private static final String GENERAL_PURPOSE_CODE = "201";

    /** The first literal of the easily-recognisable list at {@code app/cpy/CSLKPCDY.cpy} L931. */
    private static final String EASILY_RECOGNISABLE_CODE = "200";

    /** A well-formed three-character value on neither copybook list and in neither seeded class. */
    private static final String ABSENT_CODE = "000";

    /** A classification letter the check constraint at {@code V1__reference.sql} L407 excludes. */
    private static final String REJECTED_CLASS = "Z";

    /** The state PostgreSQL reports for a unique or primary-key violation. */
    private static final String SQLSTATE_UNIQUE_VIOLATION = "23505";

    /** The state PostgreSQL reports for a check-constraint violation. */
    private static final String SQLSTATE_CHECK_VIOLATION = "23514";

    /** The interface under test: the classification-scoped membership predicate. */
    @Autowired
    private PhoneAreaCodeRepository classifiedCodes;

    /** Read only for the lookup subtotal, which spans the three tables another context consumes. */
    @Autowired
    private UsStateRepository states;

    /** Read only for the lookup subtotal, for the same reason as the state repository above. */
    @Autowired
    private UsStateZipPrefixRepository stateZipPrefixes;

    // WHY : Alternatives Considered: the entity manager is injected because the repository cannot
    //       issue the INSERT that the primary-key case has to provoke. TransactionTypeRepositoryIT
    //       measures the reason at its lines 355 to 387: this entity carries an ASSIGNED String
    //       identifier, so the framework's newness test sees a non-null key, routes save through
    //       merge, and a duplicate arrives as an UPDATE of the existing row instead of as a key
    //       violation. A save-based case would therefore assert nothing at all about the primary
    //       key while appearing to.
    // WHY : Trade-offs: reaching past the repository is accepted for exactly the three write cases
    //       below and for nothing else. The cost is that those cases exercise the provider directly
    //       rather than the interface under test; what it buys is that each constraint is proven by
    //       a statement the engine actually refused, which no route through save can produce here.
    /** The provider, used only where a genuine {@code INSERT} is required. */
    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Confirms the predicate accepts a general-purpose code and refuses an easily-recognisable one.
     *
     * <p>Assumptions: both halves are asserted in ONE case rather than in two, because what is under
     * test is a distinction and not two independent facts. A predicate written against the
     * 490-literal union at {@code app/cpy/CSLKPCDY.cpy} L30 would satisfy the first half and fail the
     * second; splitting them would let a report show one green result for a predicate that had lost
     * the distinction, and the distinction is 80 codes the baseline declines.</p>
     *
     * <p>It takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the predicate accepts a general-purpose code and refuses an easily-recognisable one")
    void thePredicateIsScopedToTheNamedClassification() {
        assertThat(this.classifiedCodes.existsByAreaCodeAndCodeClass(
                GENERAL_PURPOSE_CODE, UsPhoneAreaCode.CODE_CLASS_GENERAL_PURPOSE))
                .as("%s is the first literal of the general-purpose list at CSLKPCDY.cpy L521",
                        GENERAL_PURPOSE_CODE)
                .isTrue();

        assertThat(this.classifiedCodes.existsByAreaCodeAndCodeClass(
                EASILY_RECOGNISABLE_CODE, UsPhoneAreaCode.CODE_CLASS_GENERAL_PURPOSE))
                .as("%s is on the easily-recognisable list at CSLKPCDY.cpy L931, which the "
                        + "account-maintenance path does not test", EASILY_RECOGNISABLE_CODE)
                .isFalse();
    }

    /**
     * Confirms each sample code answers only for the classification it actually carries.
     *
     * <p>Assumptions: this is the mirror of the case above, and it is present because the two
     * together rule out a defect neither catches alone. A predicate that ignored its second argument
     * entirely would satisfy the general-purpose half of both cases; only asserting the
     * easily-recognisable direction as well shows the argument is read.</p>
     *
     * <p>It takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("neither sample code answers for the classification it does not carry")
    void eachCodeAnswersOnlyForItsOwnClassification() {
        assertThat(this.classifiedCodes.existsByAreaCodeAndCodeClass(
                EASILY_RECOGNISABLE_CODE, UsPhoneAreaCode.CODE_CLASS_EASILY_RECOGNISABLE))
                .as("%s is the first literal at CSLKPCDY.cpy L931", EASILY_RECOGNISABLE_CODE)
                .isTrue();

        assertThat(this.classifiedCodes.existsByAreaCodeAndCodeClass(
                GENERAL_PURPOSE_CODE, UsPhoneAreaCode.CODE_CLASS_EASILY_RECOGNISABLE))
                .as("%s is on the L521 list only", GENERAL_PURPOSE_CODE)
                .isFalse();
    }

    /**
     * Confirms the 490 seeded codes form a disjoint and total two-value partition of 410 and 80.
     *
     * <p><b>Purpose.</b> This is the assertion {@code package-info.java} states at its lines 438 to
     * 448 and the one this class exists for. Four properties are established together: that the two
     * classifications hold exactly 410 and 80 codes, that those figures sum to the whole table, that
     * no code carries a classification outside the two the schema admits and none carries an absent
     * one, and that the union of the two subsets is set-equal to the whole table checked from BOTH
     * sides.</p>
     *
     * <p>Assumptions: the two subsets are built from the STORED classification while the full key set
     * is built from ROW IDENTITY, and that separation is what makes the second union direction mean
     * something. Partitioning a set by filtering the same set would make one direction true by
     * construction; deriving the subsets from the column instead leaves a row whose classification is
     * absent, or is a third value, in the full key set but in NEITHER subset -- so it is the whole
     * table minus the union that catches it, and that difference is asserted explicitly rather than
     * folded into a size comparison.</p>
     *
     * <p>Assumptions: the copybook evidence behind each figure was measured, not quoted.
     * {@code app/cpy/CSLKPCDY.cpy} L30 declares 490 literals and terminates at L520; L521 declares
     * 410 and terminates at L930; L931 declares 80 and terminates at L1010. The intersection of the
     * last two is empty and their union is set-equal to the first in both directions. The comment at
     * L440 is the trap that yields 410 in place of 490 and is described in this file's header.</p>
     *
     * <p>Assumptions: the classification's totality is a schema property rather than a seeding
     * accident. {@code V1__reference.sql} declares {@code code_class CHAR(1) NOT NULL} at its L393
     * and constrains it to two values at its L407, so the absent case cannot arise once the migration
     * has run and the third-value case cannot be stored. Both are still asserted here, because what
     * this case establishes is that the SEED honoured the constraint's intent across every row and
     * not merely that the constraint exists.</p>
     *
     * <p>Trade-offs: every row is read rather than a sample. The property claimed is completeness of
     * a transcription, so a sample would leave most of it untested, and 490 rows of three characters
     * is a cost worth paying to keep the claim exact rather than probable.</p>
     *
     * <p>It takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the 490 codes are a disjoint and total two-value partition of 410 and 80")
    void theClassificationIsADisjointAndTotalTwoValuePartition() {
        List<UsPhoneAreaCode> everyRow = this.classifiedCodes.findAll();

        Set<String> everyKey = new TreeSet<>();
        Set<String> generalPurpose = new TreeSet<>();
        Set<String> easilyRecognisable = new TreeSet<>();
        Set<String> unrecognisedClass = new TreeSet<>();
        Set<String> absentClass = new TreeSet<>();
        for (UsPhoneAreaCode row : everyRow) {
            everyKey.add(row.getAreaCode());
            String codeClass = row.getCodeClass();
            if (codeClass == null) {
                absentClass.add(row.getAreaCode());
            } else if (UsPhoneAreaCode.CODE_CLASS_GENERAL_PURPOSE.equals(codeClass)) {
                generalPurpose.add(row.getAreaCode());
            } else if (UsPhoneAreaCode.CODE_CLASS_EASILY_RECOGNISABLE.equals(codeClass)) {
                easilyRecognisable.add(row.getAreaCode());
            } else {
                unrecognisedClass.add(row.getAreaCode());
            }
        }

        // WHY : Assumptions: the key set is compared to the ROW count as well as to the expected
        //       figure, so a duplicated key cannot be absorbed silently. The primary key at
        //       V1__reference.sql L395 makes that impossible, and asserting it here means a schema
        //       that lost the constraint would fail on this line rather than on an arithmetic
        //       identity three lines further down whose cause would be far less obvious.
        assertThat(everyKey)
                .as("app/cpy/CSLKPCDY.cpy L30 declares 490 literals, terminating at L520")
                .hasSize(SEEDED_CODES)
                .hasSize(everyRow.size());

        assertThat(absentClass)
                .as("code_class is NOT NULL at V1__reference.sql L393, so no code may lack a class")
                .isEmpty();
        assertThat(unrecognisedClass)
                .as("the check at V1__reference.sql L407 admits two values and no third")
                .isEmpty();

        assertThat(generalPurpose)
                .as("app/cpy/CSLKPCDY.cpy L521 declares 410 literals, terminating at L930")
                .hasSize(GENERAL_PURPOSE_CODES);
        assertThat(easilyRecognisable)
                .as("app/cpy/CSLKPCDY.cpy L931 declares 80 literals, terminating at L1010")
                .hasSize(EASILY_RECOGNISABLE_CODES);
        // WHY : Assumptions: the sum is taken over the MEASURED subset sizes and compared against the
        //       measured row count as well as against the expected figure. Adding the two declared
        //       constants instead would assert 410 plus 80 is 490 -- arithmetic on two literals that
        //       the compiler can fold and that no seeded row participates in -- so it would hold
        //       against an empty table.
        assertThat(generalPurpose.size() + easilyRecognisable.size())
                .as("410 plus 80 is exactly 490, which two overlapping lists could not be")
                .isEqualTo(everyKey.size())
                .isEqualTo(SEEDED_CODES);

        // WHY : Assumptions: disjointness is asserted over the MEMBERS and not inferred from the two
        //       sizes summing correctly. Two subsets of a 490-row table can each be the right size
        //       and still share a member, provided they also both miss one, and the sum would not
        //       move. Only comparing the members rules that out.
        assertThat(generalPurpose)
                .as("no code may carry both classifications")
                .doesNotContainAnyElementsOf(easilyRecognisable);

        Set<String> union = new TreeSet<>(generalPurpose);
        union.addAll(easilyRecognisable);

        // WHY : Assumptions: both directions of the set equality are asserted separately, which is
        //       what package-info.java lines 441 to 442 mean by set-equal in both directions. The
        //       first difference catches a classified code that is not in the table; the second
        //       catches a code in the table that reached neither classification, which is the shape
        //       an absent or third-value class actually takes. A single size comparison would report
        //       neither, because a table that lost one row from each side keeps its total.
        assertThat(differenceOf(union, everyKey))
                .as("every classified code must be a row of the table")
                .isEmpty();
        assertThat(differenceOf(everyKey, union))
                .as("every row of the table must reach exactly one classification")
                .isEmpty();
    }

    /**
     * Confirms the predicate's verdict agrees with the stored classification for every seeded code.
     *
     * <p><b>Purpose.</b> The partition case establishes what the COLUMN holds. This one establishes
     * that the interface under test reports the same thing, over the same 490 codes, in both
     * classifications. The two are separate properties: the column could partition correctly while a
     * predicate that dropped its classification argument accepted every code under both letters, and
     * only comparing the two answers detects that.</p>
     *
     * <p>Assumptions: the comparison is set-for-set in both directions rather than by size, so a
     * predicate that accepted one extra code and refused one it should have accepted is caught. That
     * pairing keeps both sizes correct, which is precisely the defect a count could not see.</p>
     *
     * <p>Trade-offs: the predicate is exercised once per code per classification, which is 980
     * statements against the shared engine, rather than being sampled. The property claimed is that
     * the SQL predicate partitions the whole allow-list the way the baseline's condition names do, and
     * a sample would establish it for the sampled codes only. The cost is bounded and local: the
     * queries are primary-key probes on a 490-row table in the container this package already
     * starts.</p>
     *
     * <p>It takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the predicate agrees with the stored classification for all 490 codes")
    void thePredicateAgreesWithTheStoredClassification() {
        List<UsPhoneAreaCode> everyRow = this.classifiedCodes.findAll();
        assertThat(everyRow)
                .as("the seed must have loaded rows for this comparison to mean anything")
                .hasSize(SEEDED_CODES);

        String general = UsPhoneAreaCode.CODE_CLASS_GENERAL_PURPOSE;
        String recognisable = UsPhoneAreaCode.CODE_CLASS_EASILY_RECOGNISABLE;

        assertThat(keysAcceptedAs(everyRow, general))
                .as("the predicate must accept exactly the codes stored as general purpose")
                .containsExactlyInAnyOrderElementsOf(keysClassifiedAs(everyRow, general));
        assertThat(keysAcceptedAs(everyRow, recognisable))
                .as("the predicate must accept exactly the codes stored as easily recognisable")
                .containsExactlyInAnyOrderElementsOf(keysClassifiedAs(everyRow, recognisable));
    }

    /**
     * Confirms the inherited identity check answers the union question and refuses a non-member.
     *
     * <p><b>Purpose.</b> {@link PhoneAreaCodeRepository} documents {@code existsById} as the
     * union-scoped predicate it deliberately does not re-declare, so the union question is answered by
     * an inherited method and has to be verified as part of this interface's surface rather than
     * assumed from the superinterface.</p>
     *
     * <p>Assumptions: it is asserted over EVERY seeded row and not a sample, because the claim is that
     * the union check holds for all 490. The row count is asserted first so that the walk cannot pass
     * by iterating an empty result, which is the way this shape of case fails silently.</p>
     *
     * <p>Assumptions: the non-member value is {@code '000'}, chosen by checking both the copybook and
     * the seed rather than by looking plausible. {@code '999'} is the value that looks natural and is
     * wrong: it is seeded, carrying the easily-recognisable classification, and it appears at
     * {@code app/cpy/CSLKPCDY.cpy} L520 as the last literal of the union clause. {@code '000'} appears
     * in neither, and the sibling {@code UsPhoneAreaCodeRepositoryIT} uses the same value for its own
     * empty-finder case, so the two classes agree on which value lies outside the allow-list.</p>
     *
     * <p>It takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the inherited identity check holds for all 490 codes and refuses a non-member")
    void theInheritedIdentityCheckAnswersTheUnionQuestion() {
        List<UsPhoneAreaCode> everyRow = this.classifiedCodes.findAll();
        assertThat(everyRow).hasSize(SEEDED_CODES);
        assertThat(everyRow).allSatisfy(row ->
                assertThat(this.classifiedCodes.existsById(row.getAreaCode())).isTrue());

        assertThat(this.classifiedCodes.existsById(ABSENT_CODE))
                .as("%s is on neither copybook list and is not seeded", ABSENT_CODE)
                .isFalse();
    }

    /**
     * Confirms the three lookup tables another context reads sum to the declared subtotal of 786.
     *
     * <p><b>Purpose.</b> {@code V2__seed_reference.sql} declares a lookup subtotal of 786 in its own
     * header, and {@code package-info.java} restates it at its lines 431 to 437. This case turns that
     * figure from a description into a contract: 490 phone area codes from
     * {@code app/cpy/CSLKPCDY.cpy} L30, 56 state codes from its L1013 and 240 state-and-postal-prefix
     * combinations from its L1073.</p>
     *
     * <p>Refactoring Rationale: the subtotal is asserted here, beside the partition, rather than left
     * to the per-table cases. These three tables are exactly the set that {@code account-service}'s
     * {@code AddressValidationService} queries and does not own, so the subtotal is the figure that
     * bounds one context's exposure to another context's seed. A per-table figure cannot state a sum,
     * and three per-table cases all passing still would not, because a table nobody counted would not
     * be missed.</p>
     *
     * <p>Assumptions: the grand total of 862 is NOT re-asserted here. It is owned by
     * {@code TransactionTypeRepositoryIT}, which counts all six tables in one case; repeating it would
     * put one figure under two owners, and a later change to the seed would then have to be reconciled
     * in two places or leave the two disagreeing.</p>
     *
     * <p>Assumptions: the baseline validates a candidate area code against the 410-member
     * general-purpose sublist alone, so the domain this seed defines is not decorative -- narrowing it
     * refuses phone numbers the baseline accepts, and widening it accepts numbers the baseline
     * refuses. That is why the figure is pinned rather than sanity-checked against a lower bound.</p>
     *
     * <p>Trade-offs: three counting queries are issued rather than one aggregate over a join. Separate
     * counts name the table that is wrong in the failure message, whereas a single figure would report
     * only that some table had moved and leave the reader to find which.</p>
     *
     * <p>It takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the three lookup tables sum to the declared subtotal of 786")
    void theLookupTablesSumToTheDeclaredSubtotal() {
        // WHY : Assumptions: counting seeded rows to audit a transcription is NOT the total-count
        //       that this package prohibits. package-info.java rules at its lines 456 to 462 that a
        //       further-page answer must come from reading one row beyond the window and never from a
        //       count; that ruling governs PAGINATION, and nothing below positions, sizes or bounds a
        //       page. The distinction is recorded so a later reader does not mistake a cardinality
        //       audit for a prohibited page count and delete it.
        long areaCodeRows = this.classifiedCodes.count();
        long stateRows = this.states.count();
        long zipPrefixRows = this.stateZipPrefixes.count();

        assertThat(areaCodeRows).as("app/cpy/CSLKPCDY.cpy L30").isEqualTo(SEEDED_CODES);
        assertThat(stateRows).as("app/cpy/CSLKPCDY.cpy L1013").isEqualTo(SEEDED_STATES);
        assertThat(zipPrefixRows).as("app/cpy/CSLKPCDY.cpy L1073")
                .isEqualTo(SEEDED_STATE_ZIP_PREFIXES);

        assertThat(areaCodeRows + stateRows + zipPrefixRows)
                .as("the lookup subtotal V2__seed_reference.sql declares in its header")
                .isEqualTo(LOOKUP_SUBTOTAL);
    }

    /**
     * Confirms a stored key is read back as exactly three characters carrying exactly its digits.
     *
     * <p><b>Purpose.</b> {@code app/cpy/CSLKPCDY.cpy} L24 declares the field {@code PIC XXX} and
     * {@code V1__reference.sql} carries that through as {@code CHAR(3)}, so a code such as
     * {@code '012'} has to keep its leading zero and a code must never come back widened or
     * truncated. This case reads a row and inspects the value it received.</p>
     *
     * <p>Assumptions: the property is proven by READING THE VALUE BACK and asserting its length and
     * its Java-string content, never by comparing values through the column. PostgreSQL implements
     * {@code CHAR(n)} as {@code bpchar}, whose comparison operators disregard trailing blanks, so
     * {@code '201'} and {@code '201 '} are one value at that type and an equality predicate cannot
     * distinguish a width difference from a match. An equality-based case would therefore report the
     * engine's blank-insensitivity as though it were the mapping's width, which is a different claim
     * and a false one.</p>
     *
     * <p>Assumptions: no padding arises in this table in the first place, because every one of the 490
     * literals is exactly three characters wide. The length is asserted anyway, since that is the
     * property a widened or trimmed mapping would break and it costs one assertion to pin.</p>
     *
     * <p>Assumptions: the classification is checked the same way for the same reason. It is
     * {@code CHAR(1)} at {@code V1__reference.sql} L393, and a value read back as anything other than
     * one character would make the entity's two published constants unmatchable by equality even
     * though a database comparison against them would still succeed.</p>
     *
     * <p>It takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a stored key is read back as exactly three characters with no padding")
    void theStoredKeyIsReadBackAtItsDeclaredWidth() {
        UsPhoneAreaCode row = this.classifiedCodes.findById(GENERAL_PURPOSE_CODE).orElse(null);
        assertThat(row).as("%s is seeded from CSLKPCDY.cpy L521", GENERAL_PURPOSE_CODE).isNotNull();

        assertThat(row.getAreaCode())
                .as("PIC XXX at CSLKPCDY.cpy L24 is three characters, so bpchar adds no padding")
                .hasSize(AREA_CODE_WIDTH)
                .isEqualTo(GENERAL_PURPOSE_CODE);
        assertThat(row.getCodeClass())
                .as("code_class is CHAR(1) at V1__reference.sql L393")
                .hasSize(1)
                .isEqualTo(UsPhoneAreaCode.CODE_CLASS_GENERAL_PURPOSE);
    }

    /**
     * Confirms a classification letter the schema does not admit matches no code.
     *
     * <p>Assumptions: the predicate returns {@code false} for an unrecognised letter rather than
     * raising. A caller passing a letter outside the two published constants is asking about an empty
     * set, and an empty set is a legitimate answer; a method that threw instead would make the
     * argument's domain a run-time surprise rather than the documented pair of constants it is.</p>
     *
     * <p>Assumptions: this is the READ side of the closed domain and the write side is asserted
     * separately below. Both are needed: a predicate could answer {@code false} for a third letter
     * simply because no row carried one, which is what the write case independently establishes.</p>
     *
     * <p>It takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("an unrecognised classification letter matches no code")
    void anUnrecognisedClassificationMatchesNoCode() {
        assertThat(this.classifiedCodes.existsByAreaCodeAndCodeClass(
                GENERAL_PURPOSE_CODE, REJECTED_CLASS)).isFalse();
        assertThat(this.classifiedCodes.existsByAreaCodeAndCodeClass(
                EASILY_RECOGNISABLE_CODE, REJECTED_CLASS)).isFalse();
    }

    /**
     * Confirms the primary key refuses a duplicate area code at the flush.
     *
     * <p><b>Purpose.</b> {@code V1__reference.sql} declares {@code pk_us_phone_area_codes} on
     * {@code area_cd} at its L395, and that constraint is what makes the key set counted by the
     * partition case a set at all. Without it a seeded code could appear twice under two
     * classifications, both subset sizes would still be reachable, and the union would still cover the
     * table -- so the partition case depends on this refusal and does not establish it.</p>
     *
     * <p>Assumptions: the insert is issued through the provider and NOT through the repository,
     * because a repository save cannot produce this violation. This entity carries an assigned
     * {@code String} identifier and no version counter, so the framework's newness test sees a
     * non-null key, routes the save through {@code merge}, and a duplicate arrives as an UPDATE of the
     * existing row. {@code TransactionTypeRepositoryIT} measures exactly that at its lines 355 to 387
     * for the sibling entity, which has the same identifier shape. A save-based case here would pass
     * while asserting nothing about the primary key.</p>
     *
     * <p>Assumptions: the flush is EXPLICIT and that is what makes the case non-vacuous. The provider
     * defers the statement until the persistence context is flushed, which for a transactional method
     * is at commit -- after the assertion has already returned. Without the flush the refusal would
     * arrive outside the assertion and the case would report success having provoked nothing.</p>
     *
     * <p>Assumptions: the colliding row is a SEEDED code rather than one this case inserted first, so
     * the row the constraint collides with was committed by the migration and lies outside this
     * method's unit of work.</p>
     *
     * <p>Trade-offs: the state is read out of the cause chain instead of the thrown type being
     * asserted. Reaching the provider directly bypasses the repository proxy that maps a provider
     * failure onto the framework's data-access hierarchy, so the surfacing type is not this case's
     * subject; the state is, and it distinguishes this refusal from the check-constraint refusal below
     * that would otherwise be indistinguishable from it.</p>
     *
     * <p>It takes no parameter and returns no value.</p>
     */
    @Test
    @Transactional
    @DisplayName("the primary key refuses a duplicate area code at the flush")
    void thePrimaryKeyRefusesADuplicateAreaCode() {
        assertThat(this.classifiedCodes.existsById(GENERAL_PURPOSE_CODE))
                .as("the seed must already hold %s for the key to collide with it",
                        GENERAL_PURPOSE_CODE)
                .isTrue();

        Throwable refusal = refusalOf(new UsPhoneAreaCode(
                GENERAL_PURPOSE_CODE, UsPhoneAreaCode.CODE_CLASS_GENERAL_PURPOSE));

        assertThat(refusal).as("the primary key must refuse a second row for one code").isNotNull();
        assertThat(sqlStateOf(refusal))
                .as("a unique violation, which is a different state from a check violation")
                .isEqualTo(SQLSTATE_UNIQUE_VIOLATION);
    }

    /**
     * Confirms the check constraint refuses a third classification on a new row.
     *
     * <p><b>Purpose.</b> {@code V1__reference.sql} constrains {@code code_class} to two values at its
     * L407, and that constraint is the mechanism that makes the partition TWO-valued. The partition
     * case asserts that no seeded row carries a third value; this case asserts that no row could,
     * which is the stronger property and the one that keeps the seed honest as it changes.</p>
     *
     * <p>Assumptions: the row inserted carries a code that is NOT seeded, so the statement is a
     * genuine insert refused by the check rather than an update of an existing row refused by it. The
     * sibling {@code UsPhoneAreaCodeRepositoryIT} asserts the same constraint from the other
     * direction, over a seeded code through its own interface; the two paths are different statements
     * against the same constraint, and this one is the insert path the seed migration itself takes.</p>
     *
     * <p>Assumptions: the classification value is a letter outside the two the entity publishes, and
     * the entity's constructor deliberately validates neither argument -- its own documentation records
     * that a hand-assembled instance may carry a classification the constraint declines and is refused
     * on insert. This case is that refusal.</p>
     *
     * <p>It takes no parameter and returns no value.</p>
     */
    @Test
    @Transactional
    @DisplayName("the check constraint refuses a third classification on a new row")
    void theCheckConstraintRefusesAThirdClassification() {
        assertThat(this.classifiedCodes.existsById(ABSENT_CODE))
                .as("%s must be absent so the refusal comes from the check, not from the key",
                        ABSENT_CODE)
                .isFalse();

        Throwable refusal = refusalOf(new UsPhoneAreaCode(ABSENT_CODE, REJECTED_CLASS));

        assertThat(refusal).as("the check at V1__reference.sql L407 must refuse a third value")
                .isNotNull();
        assertThat(sqlStateOf(refusal))
                .as("a check violation, which is a different state from a unique violation")
                .isEqualTo(SQLSTATE_CHECK_VIOLATION);
    }

    /**
     * Confirms an absent classification is refused, which is what makes the partition total.
     *
     * <p><b>Purpose.</b> {@code V1__reference.sql} declares {@code code_class CHAR(1) NOT NULL} at its
     * L393. The partition case asserts that no seeded row has an absent classification; this case
     * asserts that none can acquire one, so the totality of the partition survives a later change to
     * the seed rather than holding only for the rows loaded today.</p>
     *
     * <p>Alternatives Considered: asserting the state code for this refusal as the two cases above do.
     * Rejected on a measured ground rather than a stylistic one: the provider checks a mapping declared
     * not-null before it issues the statement, so the failure can legitimately arrive with no database
     * exception in its cause chain and therefore no state to read. Asserting a state would make this
     * case depend on WHICH layer refuses first, which is not the property under test -- that the value
     * is refused is.</p>
     *
     * <p>It takes no parameter and returns no value.</p>
     */
    @Test
    @Transactional
    @DisplayName("an absent classification is refused, so the partition cannot become partial")
    void anAbsentClassificationIsRefused() {
        Throwable refusal = refusalOf(new UsPhoneAreaCode(ABSENT_CODE, null));

        assertThat(refusal)
                .as("code_class is NOT NULL at V1__reference.sql L393, so an absent class is refused")
                .isNotNull();
    }

    /**
     * Collects the keys of the rows whose STORED classification equals the one named.
     *
     * <p>Assumptions: the comparison is made in Java against the value the provider returned, not
     * pushed into a query. That is what makes the result independent of the predicate under test, so
     * the two can be compared against each other rather than one being derived from the other.</p>
     *
     * @param rows the rows to classify, as read from the table; must not be {@code null}
     * @param codeClass the classification to select, one of the two constants
     *     {@link UsPhoneAreaCode} publishes
     * @return the keys of the rows carrying that classification, in ascending key order
     */
    private static Set<String> keysClassifiedAs(List<UsPhoneAreaCode> rows, String codeClass) {
        Set<String> keys = new TreeSet<>();
        for (UsPhoneAreaCode row : rows) {
            if (codeClass.equals(row.getCodeClass())) {
                keys.add(row.getAreaCode());
            }
        }
        return keys;
    }

    /**
     * Collects the keys the PREDICATE under test accepts under the classification named.
     *
     * <p>Assumptions: this asks the interface under test once per row, so its result reflects what the
     * SQL predicate decided rather than what the row carried. A predicate that ignored its
     * classification argument would return every key here under both letters, which is the defect the
     * comparison against the stored classification exists to catch.</p>
     *
     * @param rows the rows whose keys are offered to the predicate; must not be {@code null}
     * @param codeClass the classification to test membership of, one of the two constants
     *     {@link UsPhoneAreaCode} publishes
     * @return the keys the predicate accepted under that classification, in ascending key order
     */
    private Set<String> keysAcceptedAs(List<UsPhoneAreaCode> rows, String codeClass) {
        Set<String> keys = new TreeSet<>();
        for (UsPhoneAreaCode row : rows) {
            if (this.classifiedCodes.existsByAreaCodeAndCodeClass(row.getAreaCode(), codeClass)) {
                keys.add(row.getAreaCode());
            }
        }
        return keys;
    }

    /**
     * Computes the members of one set that are absent from another.
     *
     * <p>Assumptions: this exists so that BOTH directions of a set equality can be asserted as their
     * own line with their own failure message, which is what {@code package-info.java} lines 441 to 442
     * mean by set-equal in both directions. A single containment assertion would name only the
     * direction that failed; two differences name which side gained or lost a member, and those two
     * defects have different causes.</p>
     *
     * @param left the set whose surplus members are wanted; must not be {@code null}
     * @param right the set to subtract; must not be {@code null}
     * @return the members of {@code left} absent from {@code right}, in ascending order, empty when
     *     {@code left} is contained in {@code right}
     */
    private static Set<String> differenceOf(Set<String> left, Set<String> right) {
        Set<String> surplus = new TreeSet<>(left);
        surplus.removeAll(right);
        return surplus;
    }

    /**
     * Issues a genuine insert for one row and returns whatever refused it.
     *
     * <p>Assumptions: the persist and the flush are paired here, in that order, because either alone
     * proves nothing. The persist registers the row without contacting the engine and the flush is
     * what issues the statement, so a case that omitted the flush would leave the refusal to arrive at
     * commit, after its assertion had passed.</p>
     *
     * <p>Assumptions: the throwable is returned rather than asserted on here, so each calling case
     * states its own expectation. The three constraints this file exercises fail in three different
     * ways, and a helper that asserted a single shape would force all three into it.</p>
     *
     * @param candidate the row to attempt; may carry a duplicate key, an inadmissible classification
     *     or an absent one
     * @return the throwable the provider or the engine raised, or {@code null} when the row was
     *     accepted
     */
    private Throwable refusalOf(UsPhoneAreaCode candidate) {
        return catchThrowable(() -> {
            this.entityManager.persist(candidate);
            this.entityManager.flush();
        });
    }

    /**
     * Reads the state out of a refusal by walking its cause chain.
     *
     * <p>Assumptions: the chain is walked for a database exception rather than a provider-specific
     * subclass being matched, because the state is the portable part of the answer and the wrapping
     * type is not. This is also how {@code com.carddemo.common.error.GlobalExceptionHandler}'s
     * upstream classification reads it, so a state asserted this way is evidence about the mechanism
     * production code uses rather than about a different one.</p>
     *
     * @param failure the throwable a refused statement produced; must not be {@code null}
     * @return the first non-blank state found in the chain, or {@code null} when the chain reports
     *     none, which is the case when the refusal never reached the engine
     */
    private static String sqlStateOf(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof SQLException reported
                    && reported.getSQLState() != null
                    && !reported.getSQLState().isBlank()) {
                return reported.getSQLState();
            }
            current = current.getCause();
        }
        return null;
    }
}
