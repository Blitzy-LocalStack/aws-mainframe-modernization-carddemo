// =============================================================================
// services/reference-service/src/test/java/com/carddemo/reference/repository/StateZipPrefixRepositoryIT.java
// -----------------------------------------------------------------------------
// Purpose:
//      The container-backed audit of the state-and-postal-prefix allow-list held
//      in reference.us_state_zip_prefixes. Five properties are established: that
//      the seed transcribed the baseline's closed 240-member domain completely,
//      that the domain is terminated at the two tokens named below, that the four
//      characters are compared as ONE token rather than as two independent
//      halves, that the mapped type carries one attribute and so the trailing
//      copybook field gained no column, and that the primary key refuses a second
//      row for a token already loaded.
//
// WHY (non-obvious design decisions):
//  - Refactoring Rationale: this class is named for the seeded DOMAIN of
//      reference.us_state_zip_prefixes rather than for a repository interface,
//      which departs from the naming ruling in package-info.java that a class
//      here takes the name of the interface it covers with IT appended. That
//      ruling is about classes named after interfaces, and this is a unit that is
//      not one: every read below goes through UsStateZipPrefixRepository, whose
//      walk contracts -- cardinality, ordering and the strictness of both bounds
//      -- are covered by UsStateZipPrefixRepositoryIT, and the two classes share
//      no assertion. Naming both here restores what the naming rule buys, that a
//      reader can derive the subject and its neighbours without opening the file.
//      The departure is recorded at the file it is about rather than in that
//      charter, which is the convention the charter itself sets.
//  - Alternatives Considered: a real PostgreSQL engine rather than an in-memory
//      substitute. Two properties below are the engine's own and no substitute
//      can answer them -- the collation a declared-width character column is
//      ordered under, which is the whole reason a lexical minimum and maximum
//      mean anything, and the state the engine reports when the primary key
//      refuses a duplicate. A substitute answers whatever it is configured to
//      answer, so every assertion here would pass while establishing nothing
//      about the schema that is actually deployed.
//  - Alternatives Considered: declaring an engine of this class's own rather
//      than extending the shared base. Rejected: ReferencePersistenceBase is a
//      second package-private top-level type declared inside the file
//      TransactionTypeRepositoryIT.java, reached from here by same-package
//      resolution with no import, and it already starts ONE engine for the whole
//      package in a static initialiser and already carries the context and
//      profile selection this class inherits. A second declaration would start a
//      second engine for the same package, run the same two migrations over
//      again, and leave two answers to the question of which engine a failure
//      came from.
//  - Trade-offs: starting a database engine costs this class more than any
//      assertion it makes would cost against a stand-in, and that cost is
//      accepted rather than reduced. What it buys is that the seed audit here is
//      a real reading of a migrated table: the migrations run, the rows are the
//      rows the seed statement produced, and the cardinality is counted by the
//      engine that stores them. Asserted against a stand-in the same case would
//      report the fixture it was handed, so a seed that had transcribed the
//      copybook incompletely would still be reported as complete -- which is the
//      one outcome this class exists to rule out.
//  - Trade-offs: the one case here that writes is transactional, so it rolls
//      back, and what that gives up is commit-visibility realism -- no case here
//      observes a row as a separate connection would see it after a commit, so a
//      defect appearing only once a change is durable is not caught at this
//      level. What it buys is that the cardinality asserted below is exact in any
//      order, on a re-run, and under the parallel execution this module's runners
//      allow. The package depends on that: no case in it commits an inserted or
//      deleted row, so a row left behind here would fail the cross-table seed
//      audit that TransactionTypeRepositoryIT owns, for a reason belonging to
//      this file.
//  - Assumptions: every citation beneath app/ is a physical line number read at
//      that address, and everything beneath app/ is the behavioural oracle of
//      this migration -- read and cited, never modified. Where a migrated form
//      departs from it deliberately, the departure is registered in
//      docs/architecture/cobol-to-service-traceability.md, a document this file
//      references and does not author.
// =============================================================================
package com.carddemo.reference.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.carddemo.reference.domain.UsStateZipPrefix;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.metamodel.EntityType;
import java.sql.SQLException;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Audits the seeded state-and-postal-prefix domain against a real PostgreSQL engine.
 *
 * <p>Purpose: the baseline holds this domain as a condition name over a single alphanumeric field.
 * {@code app/cpy/CSLKPCDY.cpy} declares the field at L1072 as
 * {@code 02 US-STATE-AND-FIRST-ZIP2 PIC X(4)} and opens the condition name at L1073, whose literals
 * run to L1313 and number 240, all distinct and each exactly four characters. This class states that
 * the migrated table carries that same closed set, that it carries it whole at both ends, and that
 * the four characters remain one value.</p>
 *
 * <p>Assumptions: {@code V1__reference.sql} is the sole source of this table's column names, because
 * no baseline table exists for it at all -- a condition name declares values and no columns, so a
 * column name inferred from anywhere else would be invented. Its L455 to L464 declare exactly one
 * column, {@code state_zip_cd CHAR(4) NOT NULL}, under the primary key
 * {@code pk_us_state_zip_prefixes}. {@code package-info.java} rules the same at its L328 to L331.</p>
 *
 * <p>Assumptions: {@code 02 LAST-3-OF-ZIP PIC X(3)} at {@code app/cpy/CSLKPCDY.cpy} L1314 sits
 * immediately beneath the condition name above and is NOT part of this domain. It is scaffolding the
 * baseline uses to park the remaining postal digits while it edits an address, it is named by no
 * condition and constrained by no allow-list, and it has no column here by design. Its position makes
 * it easy to read as a second field of the same structure, which is why its absence is stated rather
 * than left to be inferred from a schema that simply does not mention it. The case named
 * {@code theMappedTypeCarriesOneAttributeOnly} is what turns that absence into an assertion.</p>
 *
 * <p>Assumptions: an extraction trap sits in the same copybook and is recorded for anyone
 * re-deriving these counts. A COBOL comment line sits INSIDE a {@code VALUES} clause at L440,
 * {@code *Easily recognizable codes begin here.}, and it ends with a period; an extractor that reads
 * literals without first discarding comment lines treats that period as the clause terminator and
 * stops early. The clause it truncates is the phone-area-code list opened at L30 rather than this
 * one, but the same extraction step is what produces both figures, so a re-derivation that skips
 * comment lines agrees with the 240 below and one that does not will disagree with the 490 asserted
 * elsewhere in this package.</p>
 */
class StateZipPrefixRepositoryIT extends ReferencePersistenceBase {

    /** The number of combinations the seed loads, being the literal count of the baseline list. */
    private static final long SEEDED_COMBINATIONS = 240L;

    /** The declared width of the token, in characters. */
    private static final int TOKEN_WIDTH = 4;

    /** The lexically first token of the baseline list, its first literal at L1074. */
    private static final String LEXICAL_FIRST_TOKEN = "AA34";

    /** The lexically last token of the baseline list, its final literal at L1313. */
    private static final String LEXICAL_LAST_TOKEN = "WY83";

    // WHY : Alternatives Considered: the two probes below are assembled from the halves of the two
    //       tokens named above rather than invented, and that is what makes them evidence. AA83 takes
    //       the state half of AA34 and the postal half of WY83; WY34 takes the state half of WY83 and
    //       the postal half of AA34. Each half demonstrably occurs in the seeded data -- AA in AA34,
    //       83 in ID83, NJ83, VI83 and WY83, WY in WY82 and WY83, 34 in AA34 and FL34 -- while
    //       neither recombination appears anywhere in the baseline list. A probe built from halves
    //       that did not both occur would prove nothing, because its absence would be explained by
    //       the missing half rather than by the combination.
    /** A recombination of the two named tokens' halves that the baseline list does not contain. */
    private static final String UNLISTED_RECOMBINATION_LOW = "AA83";

    /** The opposite recombination of the same two halves, likewise absent from the list. */
    private static final String UNLISTED_RECOMBINATION_HIGH = "WY34";

    /** The state PostgreSQL reports when a unique constraint refuses a duplicate key. */
    private static final String SQLSTATE_UNIQUE_VIOLATION = "23505";

    /** The number of persistent attributes the mapped type is expected to declare. */
    private static final int MAPPED_ATTRIBUTE_COUNT = 1;

    /** The name of the single mapped attribute, as {@code UsStateZipPrefix} declares it at its L183. */
    private static final String MAPPED_ATTRIBUTE_NAME = "stateZipCd";

    /** The repository under test. */
    @Autowired
    private UsStateZipPrefixRepository prefixes;

    // WHY : Alternatives Considered: the provider is reached directly for two narrow purposes -- to
    //       read the mapping the provider actually built, and to issue the one INSERT this class
    //       needs -- and no repository member can serve either. The mapping is not visible through a
    //       repository at all, and the reason the INSERT cannot be a save is recorded at the case
    //       that performs it. This is the provider's own unit-of-work insert rather than a bulk or
    //       derived modifying statement, so package-info.java's ruling at its L332 to L335 that a
    //       write is carried out by the provider and not bypassed is honoured rather than sidestepped.
    /** The persistence context, through which the built mapping is read and one insert issued. */
    @PersistenceContext
    private EntityManager entityManager;

    // WHY : Alternatives Considered: annotating the writing case so the framework rolls it back,
    //       which is what two sibling classes here do. Rejected for the reason TransactionTypeRepositoryIT
    //       records at its L192 to L197: a unique constraint is evaluated when its statement executes,
    //       and an ambient transaction spanning the whole case makes the point at which a refusal
    //       arrives depend on when the context happens to flush. Running the write in its own
    //       transaction, with the flush taken explicitly inside it, makes the refusal attributable to
    //       the statement that caused it and leaves nothing behind either way, since a refused
    //       statement rolls its own transaction back.
    /** Supplies the transaction the one write in this class runs inside. */
    @Autowired
    private TransactionTemplate commit;

    /**
     * Confirms the seed transcribed the baseline's closed domain completely, endpoints included.
     *
     * <p>Purpose: the cardinality and the two named tokens are asserted together because neither
     * half establishes the property on its own. The count states that as many combinations were
     * loaded as the baseline declares; the two tokens state that the ones loaded include the ends of
     * the range rather than merely numbering 240.</p>
     *
     * <p>Assumptions: the figure comes from {@code app/cpy/CSLKPCDY.cpy}, whose condition name opens
     * at L1073 over the {@code PIC X(4)} field declared at L1072 and whose literals end at L1313.
     * Extracting those literals with comment lines discarded yields 240, all distinct, and
     * {@code V2__seed_reference.sql} carries the identical set at its L641 to L675 under its own
     * declaration of 240 at L17. A count asserted here is therefore a statement that the seed
     * transcribed the copybook completely and not merely that the migration ran, which is the reading
     * {@code package-info.java} gives it at its L431 to L437.</p>
     *
     * <p>Refactoring Rationale: the count is pinned at this level because a DIFFERENT bounded context
     * consumes this table and owns no part of it. {@code account-service}'s
     * {@code AddressValidationService} queries the lookup tables during account maintenance, so a
     * combination missing from this seed does not surface here at all -- it surfaces there as an
     * address refused, in a service with no defect of its own and with nothing in the refusal to name
     * the seed that caused it. Asserting the figure where the data is owned is what makes that
     * failure attributable to its cause.</p>
     *
     * <p>It takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the seed transcribes all 240 combinations including both range endpoints")
    void theSeedTranscribesTheClosedDomainCompletely() {
        // WHY : Assumptions: this counts rows to audit a seed and is NOT a total taken to size a
        //       window. package-info.java rules at its L456 to L462 that a further-page answer comes
        //       from reading one row beyond the window and never from a count, and nothing here reads
        //       a window at all -- the subject is how many combinations exist, which is a property of
        //       the seed rather than of any browse.
        assertThat(this.prefixes.count())
                .as("the literal count of app/cpy/CSLKPCDY.cpy L1073, taken over L1073 to L1313")
                .isEqualTo(SEEDED_COMBINATIONS);

        // WHY : Alternatives Considered: asserting the count alone and leaving membership to the
        //       cardinality. Rejected on arithmetic: a count constrains HOW MANY combinations were
        //       loaded and says nothing about WHICH. A transcription that substituted one literal --
        //       AA43 for AA34, two adjacent characters transposed -- loads 240 rows, all distinct, and
        //       passes a count assertion while describing a domain the copybook does not hold.
        //       The seed statement's ON CONFLICT DO NOTHING at V2__seed_reference.sql L676 does not
        //       help here either: it disposes of a REPEATED literal by landing no row for it, which a
        //       count would catch as a shortfall, and a substituted literal is not repeated. Naming the
        //       two endpoints is what closes the gap, and they are the two most worth naming because
        //       they are the only combinations whose loss also moves the range that every keyed walk
        //       over this table is bounded by, which is the property the case below states.
        assertThat(this.prefixes.findByStateZipCd(LEXICAL_FIRST_TOKEN))
                .as("the first literal of the baseline list, at app/cpy/CSLKPCDY.cpy L1074")
                .isPresent();
        assertThat(this.prefixes.findByStateZipCd(LEXICAL_LAST_TOKEN))
                .as("the final literal of the baseline list, at app/cpy/CSLKPCDY.cpy L1313")
                .isPresent();
    }

    /**
     * Confirms the two named tokens terminate the domain rather than merely belonging to it.
     *
     * <p>Purpose: presence establishes that a token was loaded; this establishes that nothing sorts
     * outside it. The engine is asked for the leading row, then for anything strictly below the first
     * token and anything strictly above the last, and both of those must come back with no rows.</p>
     *
     * <p>Assumptions: this is the engine's ordering of a declared-width character column and not a
     * property of a sorted Java collection. The lexical extremes of the baseline list were read as
     * {@code AA34} and {@code WY83}; whether the column agrees is what the reads below settle, and it
     * is the premise every keyed walk over this table rests on.</p>
     *
     * <p>Assumptions: the bound on each read is the smallest that can answer the question, and the
     * two boundary reads are strict on their position by construction, which is a property the
     * sibling class covering this interface's walks establishes in general. What is new here is the
     * subject: those reads are taken AT the domain's ends, so an empty answer is a statement about
     * the domain rather than about the walk.</p>
     *
     * <p>It takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("nothing in the table sorts before AA34 or after WY83")
    void theDomainIsTerminatedAtBothNamedTokens() {
        assertThat(this.prefixes.findAllByOrderByStateZipCdAsc(Limit.of(1)))
                .extracting(UsStateZipPrefix::getStateZipCd)
                .as("the engine's leading row under the column's own collation")
                .containsExactly(LEXICAL_FIRST_TOKEN);

        assertThat(this.prefixes
                .findByStateZipCdLessThanOrderByStateZipCdDesc(LEXICAL_FIRST_TOKEN, Limit.of(1)))
                .as("no combination sorts below the first literal of the baseline list")
                .isEmpty();
        assertThat(this.prefixes
                .findByStateZipCdGreaterThanOrderByStateZipCdAsc(LEXICAL_LAST_TOKEN, Limit.of(1)))
                .as("no combination sorts above the final literal of the baseline list")
                .isEmpty();
    }

    /**
     * Confirms the four characters are one token compared as a unit, not two independent halves.
     *
     * <p>Purpose: the four characters read as a two-letter state code followed by the two leading
     * digits of a postal code, and that reading invites a schema of two columns. The baseline does not
     * offer that reading: {@code app/cpy/CSLKPCDY.cpy} L1072 declares ONE
     * {@code PIC X(4)} field and its condition name holds one four-character literal per permitted
     * combination, so membership is a test of the whole token. This case states that the migrated form
     * behaves the same way.</p>
     *
     * <p>Alternatives Considered: splitting the token into a state column and a postal-prefix column,
     * which is the shape the four characters suggest and which was rejected on a consequence that can
     * be counted. Two independent columns admit every pairing of their values, so the permitted domain
     * would become the cross product of the 56 state codes the same copybook lists at L1013 with the
     * 100 values two digits can take -- 5600 admissible pairings in place of a closed set of 240. The
     * two probes below are what makes that concrete rather than argued: each is built from halves that
     * both occur in the seeded data, and neither recombination is permitted, so a split schema would
     * accept an address the baseline refuses and would refuse nothing in exchange.</p>
     *
     * <p>Assumptions: the width is claimed in Java and never through a comparison the engine
     * evaluates. PostgreSQL implements {@code CHAR(n)} as {@code bpchar}, whose equality ignores
     * trailing blanks, so a padded value and an unpadded one compare equal there and an equality
     * predicate cannot witness a width at all. Reading the value back and measuring the returned
     * string is what states the width; the equality beside it states the content. Every one of the 240
     * literals is exactly four characters, so no padding arises in the stored data and the two claims
     * do not compete.</p>
     *
     * <p>It takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the token round-trips as one four-character value and its halves do not recombine")
    void theTokenIsComparedAsOneUnitAndNotAsTwoHalves() {
        Optional<UsStateZipPrefix> loaded = this.prefixes.findByStateZipCd(LEXICAL_FIRST_TOKEN);
        assertThat(loaded).as("the seed must have loaded the subject combination").isPresent();

        assertThat(loaded.get().getStateZipCd())
                .as("the stored value, measured in Java because bpchar equality cannot witness width")
                .isEqualTo(LEXICAL_FIRST_TOKEN)
                .hasSize(TOKEN_WIDTH);

        assertThat(this.prefixes.findByStateZipCd(UNLISTED_RECOMBINATION_LOW))
                .as("the state half of AA34 with the postal half of WY83 is not a permitted pairing")
                .isEmpty();
        assertThat(this.prefixes.findByStateZipCd(UNLISTED_RECOMBINATION_HIGH))
                .as("the state half of WY83 with the postal half of AA34 is not a permitted pairing")
                .isEmpty();
    }

    /**
     * Confirms the mapped type carries one persistent attribute, so the trailing field gained no column.
     *
     * <p>Purpose: this is the assertion form of the absence recorded on this class.
     * {@code 02 LAST-3-OF-ZIP PIC X(3)} at {@code app/cpy/CSLKPCDY.cpy} L1314 is the field
     * immediately beneath the condition name this table comes from, and it is parsing scaffolding
     * rather than part of the domain. The mapping the provider actually built is read here and
     * required to declare exactly one attribute, so a second column added for that field -- or for
     * anything else -- fails at this level instead of surfacing later as a table that disagrees with
     * the migration.</p>
     *
     * <p>Assumptions: the mapping is read from the provider's own model rather than from the
     * annotations on the source, so what is asserted is what the running context resolved.
     * {@code V1__reference.sql} L455 to L464 declares the one column and the profile in
     * {@code src/test/resources/application-test.yml} validates the mapping against it at startup, so
     * an attribute mapped to a column the migration does not declare would already have aborted the
     * context; this case covers the opposite direction, a column the migration does declare being
     * joined by a second one.</p>
     *
     * <p>Alternatives Considered: asserting the absent column by name, which is the obvious form and
     * is unusable. A name that is absent cannot be read back, so the assertion would have to be
     * written against a literal that exists nowhere in the schema, the mapping or the entity -- and it
     * would then keep passing if a column were added under any other spelling. Counting the
     * attributes states the closed shape instead, which is the property the absence is an instance
     * of.</p>
     *
     * <p>It takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the mapped type declares one attribute, so the trailing copybook field has no column")
    void theMappedTypeCarriesOneAttributeOnly() {
        EntityType<UsStateZipPrefix> mapped =
                this.entityManager.getMetamodel().entity(UsStateZipPrefix.class);

        assertThat(mapped.getAttributes())
                .as("one column in V1__reference.sql, therefore one attribute and no second field")
                .hasSize(MAPPED_ATTRIBUTE_COUNT);
        assertThat(mapped.getId(String.class).getName())
                .as("the whole row is its key, so the one attribute is the identifier")
                .isEqualTo(MAPPED_ATTRIBUTE_NAME);
    }

    /**
     * Confirms the primary key refuses a second row for a combination the seed already loaded.
     *
     * <p>Purpose: the closed domain is only closed if the key holds, and the key is
     * {@code pk_us_state_zip_prefixes} at {@code V1__reference.sql} L463. What is asserted is that the
     * ENGINE refuses the duplicate and that it reports the state a caller would branch on, so the
     * guarantee belongs to every writer of the schema rather than only to callers arriving through
     * this application.</p>
     *
     * <p>Assumptions: the insert is issued through the provider's own unit of work and the flush is
     * taken explicitly, because a constraint assertion without an explicit flush is vacuous -- the
     * provider registers the row and defers the statement to whenever the context is next flushed,
     * which for a transactional method is at commit, after the assertion has already returned.
     * Flushing on the repository is deliberate: that proxy is where the provider's exception is
     * translated into the type the service layer classifies, so the refusal arrives here in the form
     * production code would see.</p>
     *
     * <p>Alternatives Considered: saving the duplicate through the repository, which reads as the
     * natural route and cannot establish this property. The identifier is assigned by the caller and
     * this type declares no optimistic-lock counter, so the framework's newness test finds a non-null
     * identifier, concludes the instance is not new, and routes the save through
     * {@code EntityManager.merge} -- which loads the row that identifier names and writes an UPDATE
     * against it. No INSERT is ever issued, so no constraint is ever reached and the assertion would
     * pass for the wrong reason. {@code TransactionTypeRepositoryIT} records the same measurement for
     * its own type at its L355 to L387.</p>
     *
     * <p>Assumptions: the combination collided with is a SEEDED one, so the row the constraint
     * collides with was committed by the migration and lies outside this transaction. The refused
     * statement rolls that transaction back, so the cardinality asserted above is unaffected however
     * the cases in this package are ordered.</p>
     *
     * <p>Assumptions: that last sentence holds only while the combination really is seeded, and the
     * dependency was measured rather than reasoned about. Running this class against a seed in which
     * that combination had been altered, the insert met no existing row, SUCCEEDED, and was committed
     * by the transaction around it -- and a later case in this same class then found the row this one
     * had just written. The premise is therefore not incidental, and the case named
     * {@code theSeedTranscribesTheClosedDomainCompletely} is what states it: it asserts that this
     * combination is present, so a seed that stopped supplying it fails there rather than quietly
     * turning this case into a committing write.</p>
     *
     * <p>It takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the primary key refuses a duplicate combination and reports SQLSTATE 23505")
    void theKeyRefusesASecondRowForASeededToken() {
        DataIntegrityViolationException refusal = catchThrowableOfType(
                DataIntegrityViolationException.class,
                () -> this.commit.executeWithoutResult(status -> {
                    this.entityManager.persist(new UsStateZipPrefix(LEXICAL_FIRST_TOKEN));
                    this.prefixes.flush();
                }));

        assertThat(refusal).as("the key must refuse a second row for a loaded combination").isNotNull();
        assertThat(sqlStateOf(refusal))
                .as("a duplicate key, which is a different state from an absent value")
                .isEqualTo(SQLSTATE_UNIQUE_VIOLATION);
    }

    /**
     * Reads the SQLSTATE out of a refusal by walking its cause chain.
     *
     * <p>Assumptions: the state is reached by walking for a {@link java.sql.SQLException} rather than
     * by matching a provider-specific exception subclass, which is how application code that branches
     * on the state reads it. Asserting it the same way makes this evidence for that mechanism and not
     * for a different one. It raises nothing of its own: a chain carrying no state is answered with
     * {@code null}, because a walk that found nothing is a fact the caller asserts on rather than an
     * error, and throwing would replace a legible assertion failure with a second exception raised
     * while the first was being examined.</p>
     *
     * <p>Alternatives Considered: putting this on the shared base so the sibling class that needs the
     * same walk and this one could share it. Rejected because that base lives inside another test
     * class's file and exists to own the engine, so widening it into a general assertion toolkit would
     * couple every class in the package to a change made for one of them; the walk is a single loop
     * and is stated where it is used.</p>
     *
     * @param failure the refusal the provider raised, whose cause chain is walked; may be
     *     {@code null}, which is answered as no state found rather than as an error
     * @return the first non-blank SQLSTATE found on the chain, or {@code null} when the chain reports
     *     none
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
