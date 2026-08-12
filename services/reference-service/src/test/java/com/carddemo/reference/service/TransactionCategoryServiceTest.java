// =============================================================================
// services/reference-service/src/test/java/com/carddemo/reference/service/TransactionCategoryServiceTest.java
// -----------------------------------------------------------------------------
// Purpose:
//      Unit cases over TransactionCategoryService, the CHILD half of the
//      transaction-reference feature. They pin what is genuinely specific to a
//      two-part key: the composite position codec that folds (type_cd, cat_cd)
//      into the one opaque cursor the shared envelope carries, the ascending
//      pair ordering the baseline unique index declares, the four-character
//      digit key whose leading zeros an integral type would destroy, the
//      advisory child-existence reading that is not the referential authority,
//      the two directions of one foreign key told apart by SQLSTATE, and the
//      absence of any character-class rule on a category description. Every
//      collaborator is a double; nothing here starts a container or a Spring
//      context.
//
// WHY (non-obvious design decisions):
//  (1) Assumptions: NO GOLDEN MASTER COVERS ANY PATH ASSERTED HERE. The
//      reference-only oracle states at lines 83 to 85 of tests/README.md that
//      the online CO* programs cannot run end to end without a CICS runtime and
//      that only their extractable field-validation logic is unit-tested, and a
//      case-insensitive search of that tree for a cotrt, trantype or trancatg
//      artefact returns no filename at all. These assertions are therefore
//      primary evidence rather than a second opinion, so each one cites the
//      physical baseline line it was read from and asserts what did NOT happen
//      as well as what did.
//  (2) Assumptions: every baseline citation below is a PHYSICAL line number,
//      readable with sed -n '1914p'. It is never the six-digit sequence printed
//      in columns 1 to 6. In COTRTLIC.cbl the two agree only as far as physical
//      line 1807, after which the printed sequence stands four higher for the
//      rest of the file -- physical line 1914 prints 191800 -- so a citation
//      taken from the printed field names the wrong line in exactly the region
//      these cases quote.
//  (3) Assumptions: the four rationale labels are typed here and never copied
//      out of tests/README.md. That file carries the non-breaking hyphen 106
//      times across 77 of its lines, and its own list of the four categories at
//      line 548 renders the compromise label with that character together with a
//      typographic dash, both indistinguishable on screen from the ASCII forms
//      while behaving differently in a search. This file is pure ASCII, which
//      puts the hazard out of reach rather than relying on care.
//  (4) Assumptions: the singular spellings that appear in the reference-only
//      suite's configuration headers denote these same four categories. That
//      equivalence is declared once, here, so no subsequent edit corrects one form
//      into the other; it governs those reference-only artifacts alone and does
//      not propagate into this file, which uses the plural form exclusively.
//  (5) Refactoring Rationale: the surface exercised here was READ from the
//      authored class rather than taken from the transformation plan, and the
//      two differ in four ways that a plan-derived case would have compiled
//      against and failed on. PageResponse carries five components and does
//      publish a backward flag; the entity does carry a mapped revision
//      counter, though the refusal still rests on the service's own comparison
//      of it before the write; the keyed walks seek strictly past the boundary
//      rather than at it; and the service takes ONE collaborator, because the
//      mapper is a final class exposing static methods and there is no parent
//      repository to inject. Each is asserted in the form the code actually has.
//  (6) Trade-offs: this class asserts the paging ENVELOPE only in composite-key
//      terms -- that a boundary names a row the caller received, and that the
//      probe row leaves from the correct end of a two-part window. The scalar
//      form of those same properties is already held by TransactionTypeBrowseTest
//      over the parent table, and the mechanism is one shared helper, so
//      restating it here would duplicate a sibling rather than add evidence. The
//      compromise accepted is that a regression in that shared helper is caught
//      by the sibling first and by this class only through the composite paths.
//  (7) Trade-offs: this class is the sixth compilation unit in its directory, and
//      the volatile inventory paragraph of the package charter beside it counts
//      four. That paragraph is deliberately kept apart from the charter's
//      durable rules precisely because it lags, it is bound by no build gate,
//      and it is not this file's to rewrite; the count is recorded here instead
//      so a reader who checks it finds the discrepancy explained rather than
//      unremarked. The charter's DURABLE half, at its line 39, is what rules
//      this file into existence: the transaction-category rule is a rule in its
//      own right and not a mode of the transaction-type rule, because
//      TransactionCategoryService is a separate type from TransactionTypeService.
// =============================================================================

package com.carddemo.reference.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.carddemo.common.error.ApiError;
import com.carddemo.common.error.ClientInputException;
import com.carddemo.common.error.RecordConflictException;
import com.carddemo.common.validation.FieldValidationFlag;
import com.carddemo.common.web.CursorToken;
import com.carddemo.common.web.PageResponse;
import com.carddemo.reference.domain.TransactionCategory;
import com.carddemo.reference.domain.TransactionCategory.TransactionCategoryId;
import com.carddemo.reference.dto.PageDirection;
import com.carddemo.reference.dto.TransactionCategoryCreateRequest;
import com.carddemo.reference.dto.TransactionCategoryListRequest;
import com.carddemo.reference.dto.TransactionCategoryResponse;
import com.carddemo.reference.dto.TransactionCategoryUpdateRequest;
import com.carddemo.reference.repository.TransactionCategoryRepository;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.Query;

/**
 * Pins the transaction-category behaviours a two-part key makes possible to get wrong.
 *
 * <p>Purpose: the cases below assert eight families of property that a behavioural change could cross
 * without any compiler or schema noticing. First, that a browse position folds BOTH halves of the
 * composite key into one sealed token and reconstructs both exactly, for every key the baseline seeds.
 * Second, that the order a page publishes is the ascending pair order the baseline index declares,
 * whichever direction the read travelled. Third, that the child-existence reading answers in both
 * directions while remaining advisory rather than authoritative. Fourth, that the two directions of
 * one foreign key are reported apart, and that an unrelated constraint refusal is reported as neither.
 * Fifth, that no character-class rule is applied to a category description. Sixth, that the field
 * state a refusal carries is chosen from three and not from two. Seventh, that the earliest refusal on
 * a path is the one delivered. Eighth, that a category is the child of the referential rule and never
 * its parent.
 *
 * <p>Assumptions: the surface exercised is the one actually authored. {@link TransactionCategoryService}
 * publishes {@code list}, {@code listByType}, {@code find}, {@code read}, {@code existsForType},
 * {@code create}, {@code update}, {@code replace} and {@code delete}; its refusals are the four types
 * nested inside it, which is the opposite arrangement from the sibling parent service, where the
 * refusals are the shared kernel's own types. It takes ONE collaborator, the category repository,
 * because the mapper is a final class exposing static methods with no instance to inject and there is
 * no parent repository at all -- an absence this class asserts, since it is what makes the declared
 * constraint the only possible authority over the parent relationship.
 *
 * <p>Assumptions: the key is {@code (type_cd CHAR(2), cat_cd CHAR(4))} and the category half is a
 * four-character string of DIGITS, never an integer. {@code TRC_TYPE_CATEGORY CHAR(4) NOT NULL} is
 * declared at line 3 of {@code app/app-transaction-type-db2/ddl/TRNTYCAT.ddl}, the Db2 host structure
 * declares {@code PIC X(4)} at lines 42 and 43 of
 * {@code app/app-transaction-type-db2/dcl/DCLTRCAT.dcl}, and {@code app/cpy/CVTRA04Y.cpy} places
 * {@code TRAN-CAT-CD PIC 9(04)} at line 7, at zero-based offset two inside the sixty-byte
 * {@code TRAN-CAT-RECORD} its line 2 declares. The seeded keys run {@code '0001'} to {@code '0005'},
 * so an integral half would publish {@code 5} for {@code '0005'} and every position naming it would
 * miss. The repository package charter carries this same ruling at package scope; it is cited rather
 * than restated.
 *
 * <p>Assumptions: the ordering is {@code (type_cd ASC, cat_cd ASC)} because
 * {@code app/app-transaction-type-db2/ddl/XTRNTYCAT.ddl} declares
 * {@code CREATE UNIQUE INDEX CARDDEMO.X_TRAN_TYPE_CATG} over {@code TRC_TYPE_CODE ASC} and then
 * {@code TRC_TYPE_CATEGORY ASC} at its lines 1 to 3. That is a schema fact and not a preference, which
 * is why one case below reads the declared queries themselves rather than only their results.
 *
 * <p>Assumptions: the entity DOES carry a mapped revision counter, and the refusal for a stale one is
 * nevertheless raised by the service's own comparison of it before any write rather than by the
 * provider at commit. That placement is what lets the refusal carry the stored revision back to the
 * caller, and it is the migrated form of the pre-edit comparison the baseline performs across a screen
 * turn -- {@code 1205-COMPARE-OLD-NEW} at line 783 of
 * {@code app/app-transaction-type-db2/cbl/COTRTUPC.cbl}, whose exit is at line 814, which applies
 * {@code FUNCTION UPPER-CASE} and {@code FUNCTION TRIM} to both sides at lines 786 to 793 and compares
 * trimmed lengths at lines 794 to 797. No case here asserts a provider-raised optimistic-lock failure,
 * because that is not the mechanism the class uses.
 *
 * <p>Assumptions: the seeded membership is the eighteen rows of {@code app/data/ASCII/trancatg.txt},
 * whose keys are {@code 01|0001} to {@code 01|0005}, {@code 02|0001} to {@code 02|0003},
 * {@code 03|0001} to {@code 03|0003}, {@code 04|0001} to {@code 04|0003}, {@code 05|0001},
 * {@code 06|0001}, {@code 06|0002} and {@code 07|0001}, against the seven type codes {@code 01} to
 * {@code 07} of {@code app/data/ASCII/trantype.txt}. Those two files terminate their rows with a
 * carriage return and a line feed -- sixty data bytes and a carriage return, so sixty-one before the
 * feed, with the last row of the type file carrying none -- while {@code app/data/ASCII/discgrp.txt}
 * is line-feed only at exactly fifty bytes. A reader that does not discard the carriage return pulls a
 * sixty-first byte into the record and mis-decodes, and the failure presents as a field-offset fault
 * rather than as a line-ending one. The fixtures beside this class are normalised to line feeds at
 * exactly the declared sixty bytes, a ruling their own README states and this class honours without
 * restating; no case here reads a file, so the asymmetry is recorded rather than relied upon.
 *
 * <p>Trade-offs: the window fixtures that need eight rows use one synthetic type run rather than a
 * seeded one, because no seeded type carries eight categories -- the largest is type {@code 01} with
 * five. The compromise is that those fixtures are not evidence about seeded membership; they are
 * evidence about window mechanics, and the cases that speak about membership use the seeded keys.
 *
 * <p>Trade-offs: three cases reach for a declared annotation or a declared constructor by reflection.
 * That couples them to a member's name, so a rename fails them rather than silently passing; the
 * coupling is accepted because the properties involved -- which ordering a query declares, and how many
 * collaborators the class can possibly consult -- are not observable through any behaviour a double can
 * present.
 *
 * @see TransactionTypeServiceTest for the parent half of this feature, whose findings about the shared
 *     outcome classifier, the shared before-image comparison and the live character class are cited
 *     here rather than re-derived
 * @see TransactionTypeBrowseTest for the scalar-key form of the shared paging mechanism
 */
class TransactionCategoryServiceTest {

    /** The first type code the seed file carries, and the one with the most categories. */
    private static final String TYPE_CD = "01";

    /** The highest category the seeded type {@link #TYPE_CD} carries, whose leading zeros are the point. */
    private static final String CAT_CD = "0005";

    /** The lowest category the seeded type {@link #TYPE_CD} carries. */
    private static final String FIRST_CAT_CD = "0001";

    /** The type code the second seeded run begins, used to cross a type boundary. */
    private static final String NEXT_TYPE_CD = "02";

    /** A type code no seeded row uses, for the paths that must report a miss. */
    private static final String ABSENT_TYPE_CD = "77";

    /** A category code no seeded row uses, for the paths that must report a miss. */
    private static final String ABSENT_CAT_CD = "0099";

    /** The description the seed file stores against {@code 01|0005}. */
    private static final String STORED_DESCRIPTION = "Interest Amount";

    /**
     * The seventeenth seeded description, which carries a character the type editor refuses.
     *
     * <p>Assumptions: row seventeen of {@code app/data/ASCII/trancatg.txt} is literally
     * {@code 060002Non-fraud reversal} followed by blanks to the fifty-byte field and a four-character
     * filler of zeros. The hyphen is what makes it evidence: the baseline's alphanumeric editor admits
     * letters, digits and the blank alone, so this value could not survive that edit -- and the
     * baseline nevertheless ships it, because that editor is never applied to a category description.
     */
    private static final String HYPHENATED_DESCRIPTION = "Non-fraud reversal";

    /** The authenticated caller every browse in this class is issued as. */
    private static final String SUBJECT = "REFUSER2";

    /**
     * The key browse positions are sealed under, fabricated here and used nowhere else.
     *
     * <p>Assumptions: the VALUE is immaterial and only the length is a contract -- the sealer refuses a
     * key shorter than its keyed cipher requires, and this phrase is forty-five bytes, comfortably past
     * that floor. It is a visibly non-production phrase held in a unit test, it never reaches a deployed
     * profile, and no case here asserts anything about the confidentiality of a deployed key.
     */
    private static final byte[] CURSOR_KEY =
            "transaction-category-service-test-sealing-key".getBytes(StandardCharsets.UTF_8);

    /** How long a sealed position stays openable, long enough that no case can expire mid-run. */
    private static final Duration CURSOR_LIFETIME = Duration.ofHours(1);

    /** The vendor text a driver exception carries, chosen to be searchable and to occur nowhere else. */
    private static final String DRIVER_REASON = "carddemo-category-driver-reason-marker";

    /**
     * A constraint state that is neither of the two this service classifies.
     *
     * <p>Assumptions: this is the check-violation state, chosen because the migrated table really does
     * declare check constraints, so it is a refusal this service could genuinely receive rather than an
     * impossible value. What matters is only that it is not the unique state and not the foreign-key
     * state, which is the discrimination the case using it exists to prove.
     */
    private static final String UNRELATED_SQLSTATE = "23514";

    /**
     * The eighteen keys {@code app/data/ASCII/trancatg.txt} seeds, in the file's own order.
     *
     * <p>Assumptions: the order here is the file's order and is already the ascending pair order, which
     * is itself worth noting -- the seed file is sorted by the same composite the baseline index
     * declares, so a case may use this list both as the membership it asserts over and as the ordering
     * it asserts against.
     */
    private static final List<SeededKey> SEEDED_KEYS = List.of(
            new SeededKey("01", "0001"), new SeededKey("01", "0002"), new SeededKey("01", "0003"),
            new SeededKey("01", "0004"), new SeededKey("01", "0005"), new SeededKey("02", "0001"),
            new SeededKey("02", "0002"), new SeededKey("02", "0003"), new SeededKey("03", "0001"),
            new SeededKey("03", "0002"), new SeededKey("03", "0003"), new SeededKey("04", "0001"),
            new SeededKey("04", "0002"), new SeededKey("04", "0003"), new SeededKey("05", "0001"),
            new SeededKey("06", "0001"), new SeededKey("06", "0002"), new SeededKey("07", "0001"));

    /** The category table double. */
    private TransactionCategoryRepository categories;

    /** The service under test, rebuilt per case over a fresh double. */
    private TransactionCategoryService service;

    /** The sealer every browse case mints and opens positions through, rebuilt per case. */
    private CursorToken sealer;

    /**
     * Prepares a fresh double, a fresh service and a fresh sealer for each case.
     *
     * <p>Assumptions: the double is rebuilt rather than reset because many cases below assert that an
     * interaction did NOT happen, and that assertion is only meaningful against a double no earlier case
     * has touched.
     *
     * <p>It takes no parameter and returns no value.
     */
    @BeforeEach
    void setUp() {
        resetDoubles();
    }

    /**
     * Replaces the double, the service and the sealer with untouched instances.
     *
     * <p>Alternatives Considered: letting a case that needs a second, differently-stubbed refusal simply
     * stub the same member twice. That does not work here and the reason is specific: stubbing a member to
     * THROW and then stubbing it again invokes it during the second stubbing, which raises the exception
     * configured by the first and fails the case before it has asserted anything. Rebuilding the double is
     * the supported way to present a second refusal from one case.
     *
     * <p>It takes no parameter and returns no value.
     */
    private void resetDoubles() {
        this.categories = mock(TransactionCategoryRepository.class);
        this.service = new TransactionCategoryService(this.categories);
        this.sealer = new CursorToken(CURSOR_KEY, CURSOR_LIFETIME);
    }

    /**
     * One key of the seeded category table, held as its two declared halves.
     *
     * <p>Assumptions: both halves are held as strings of their declared width, never as numbers. A
     * record is used rather than a pair of parallel lists so that a case cannot drift the two apart.
     *
     * @param typeCd the two-character type half, exactly as the seed file carries it
     * @param catCd the four-character category half, leading zeros intact
     */
    private record SeededKey(String typeCd, String catCd) {
    }

    /**
     * Builds the composite identity of one category without normalising either half.
     *
     * @param typeCd the two-character type half; passed through unaltered
     * @param catCd the four-digit category half; passed through unaltered so leading zeros survive
     * @return the identity the repository is keyed by, never {@code null}
     */
    private static TransactionCategoryId id(String typeCd, String catCd) {
        return new TransactionCategoryId(typeCd, catCd);
    }

    /**
     * Builds a stored row standing for what a keyed read would return.
     *
     * @param typeCd the two-character type half of the row's key
     * @param catCd the four-digit category half of the row's key
     * @param description the description the row holds, taken as literal text
     * @return a category whose revision is the column's declared default, never {@code null}
     */
    private static TransactionCategory storedCategory(
            String typeCd, String catCd, String description) {

        return new TransactionCategory(id(typeCd, catCd), description);
    }

    /**
     * Builds a stored row at the canonical seeded key and description.
     *
     * @return the row standing for {@code 01|0005}, never {@code null}
     */
    private static TransactionCategory storedCategory() {
        return storedCategory(TYPE_CD, CAT_CD, STORED_DESCRIPTION);
    }

    /**
     * Builds a browse request from the four parts the contract publishes.
     *
     * @param cursor the sealed position to resume from, or {@code null} for an opening page
     * @param direction the direction of travel, or {@code null} when no position is being moved from
     * @param typeCode the type-code narrowing, or {@code null} for every type
     * @param description the description narrowing, or {@code null} for no description narrowing
     * @return the browse request, never {@code null}
     */
    private static TransactionCategoryListRequest browse(
            String cursor, PageDirection direction, String typeCode, String description) {

        return new TransactionCategoryListRequest(cursor, direction, typeCode, description);
    }

    /**
     * Builds an opening browse request over every type, with no narrowing and no position.
     *
     * @return the opening browse request, never {@code null}
     */
    private static TransactionCategoryListRequest openingBrowse() {
        return browse(null, null, null, null);
    }

    /**
     * Composes the binding a position of the COLLECTION browse is sealed under.
     *
     * <p>Assumptions: the binding is composed through the shared helper the service itself uses rather
     * than assembled from its parts here. Re-deriving the composition in a test would let the two drift
     * and the case would then be asserting its own arithmetic rather than the service's.
     *
     * @param backward whether the leading boundary is wanted rather than the trailing one
     * @param typeFilter the type-code narrowing sealed into the position, or {@code null} for none
     * @param searchText the stripped description narrowing sealed in, or {@code null} for none
     * @return the binding that position is sealed under, never {@code null}
     */
    private static String collectionBinding(
            boolean backward, String typeFilter, String searchText) {

        return ReferencePaging.binding(TransactionCategoryService.CURSOR_BINDING, SUBJECT, backward,
                typeFilter, searchText);
    }

    /**
     * Composes the binding a position of the BY-PARENT browse is sealed under.
     *
     * @param backward whether the leading boundary is wanted rather than the trailing one
     * @param typeFilter the parent type code the route was addressed by, sealed into the position
     * @param searchText the stripped description narrowing sealed in, or {@code null} for none
     * @return the binding that position is sealed under, never {@code null}
     */
    private static String byTypeBinding(boolean backward, String typeFilter, String searchText) {
        return ReferencePaging.binding(TransactionCategoryService.CURSOR_BINDING_BY_TYPE, SUBJECT,
                backward, typeFilter, searchText);
    }

    /**
     * Seals an arbitrary plain position under a binding, so a case can present a chosen payload.
     *
     * <p>Assumptions: this exists so that a case can present a position which OPENS correctly and then
     * fails to read as a two-part key. Presenting a hand-built token instead would be refused by the
     * sealer, which is a different refusal from a different component, and the malformed-payload cases
     * would then be proving nothing about this service's decoder.
     *
     * @param binding the binding to seal under, which must be the one the route will open with
     * @param plainPosition the plain text to seal, which may deliberately be unreadable as a key
     * @return the sealed token, never {@code null}
     */
    private String sealedPosition(String binding, String plainPosition) {
        return this.sealer.seal(binding, plainPosition);
    }

    /**
     * Wraps a SQLSTATE in the integrity violation a provider would raise carrying it.
     *
     * <p>Assumptions: the state is carried on a {@link SQLException} in the cause chain rather than in
     * the wrapper's text, because that is where the service reads it from -- it walks the chain for the
     * one interface that is part of the platform and reports the state directly. A wrapper whose message
     * merely quoted the digits would not be classified at all, which one case below proves.
     *
     * @param sqlState the five-character state the database reported
     * @return an integrity violation whose cause reports that state, never {@code null}
     */
    private static DataIntegrityViolationException integrityViolation(String sqlState) {
        return new DataIntegrityViolationException(
                "constraint refused the statement", new SQLException(DRIVER_REASON, sqlState));
    }

    /**
     * Reads the query one keyed walk declares, so an ordering can be asserted rather than inferred.
     *
     * <p>Alternatives Considered: asserting the ordering only through results returned by a double.
     * That form cannot see the ordering at all -- a double returns whatever the case handed it, so a
     * query whose {@code ORDER BY} had been reversed or dropped would satisfy every result-shaped
     * assertion in this class. Reading the declared query is the only assertion that notices, which is
     * why the coupling to a member name is accepted here.
     *
     * @param method the repository member whose declared query is wanted
     * @param parameterTypes the member's parameter types, in declaration order
     * @return the declared query text, never {@code null}
     * @throws ReflectiveOperationException if the member is renamed or its signature changes without
     *     these cases being updated, which fails them rather than letting them pass by omission
     * @throws AssertionError if the member no longer declares a query of its own, in which case its
     *     ordering is no longer readable here and the case must not report success
     */
    private static String declaredQuery(String method, Class<?>... parameterTypes)
            throws ReflectiveOperationException {

        Query declared = TransactionCategoryRepository.class
                .getDeclaredMethod(method, parameterTypes)
                .getAnnotation(Query.class);
        if (declared == null) {
            throw new AssertionError(method
                    + " no longer declares a query of its own, so the ordering this case asserts is no"
                    + " longer readable from it");
        }
        return declared.value();
    }

    /**
     * Builds a run of categories under one type, ascending by category code.
     *
     * @param typeCd the type half every row in the run carries
     * @param count how many rows the run holds, numbered from one
     * @return the run in ascending pair order, never {@code null}
     */
    private static List<TransactionCategory> ascendingRun(String typeCd, int count) {
        List<TransactionCategory> run = new ArrayList<>(count);
        for (int ordinal = 1; ordinal <= count; ordinal++) {
            run.add(storedCategory(typeCd, String.format("%04d", ordinal), "Row " + ordinal));
        }
        return run;
    }

    /**
     * Reverses a run, standing for what a backward keyed walk returns.
     *
     * <p>Assumptions: the backward queries declare a DESCENDING order, so a double standing in for one
     * must hand back a descending run. Handing back an ascending run would make the case pass whether
     * the service reversed the result or not, which is precisely the property under test.
     *
     * @param rows the run to reverse; must not be {@code null}
     * @return a descending copy, never {@code null}
     */
    private static List<TransactionCategory> descending(List<TransactionCategory> rows) {
        List<TransactionCategory> reversed = new ArrayList<>(rows);
        Collections.reverse(reversed);
        return reversed;
    }

    /**
     * Renders the published rows as their two key halves joined for comparison.
     *
     * <p>Assumptions: the joining character here is a comparison convenience local to this class and is
     * not the service's own separator. No case asserts anything about how the service joins the halves
     * internally, because the position it produces is sealed and opaque.
     *
     * @param page the page whose published order is wanted; must not be {@code null}
     * @return the keys of the published rows, in the order the page published them, never {@code null}
     */
    private static List<String> publishedKeys(PageResponse<TransactionCategoryResponse> page) {
        return page.items().stream().map(row -> row.typeCd() + "/" + row.catCd()).toList();
    }

    /**
     * Cases pinning the codec that folds a two-part key into one opaque position.
     *
     * <p>Alternatives Considered: giving the shared envelope a second type parameter for its key, so
     * that a composite-keyed list could carry the pair as a typed value instead of encoding it. That was
     * declined where the envelope is declared, and this class asserts the consequence rather than the
     * decision: the envelope is shared by every list in every service, most of which key on a single
     * column, so a key type parameter would oblige all of them to name a key type in order to serve the
     * few whose key is a pair. Two separate boundary components, one per half, was declined for the same
     * reason -- every scalar-keyed list would then publish a component permanently absent. Encoding the
     * pair into the one existing position leaves that shared contract untouched, and makes the encoding this
     * service's own obligation, which is why it is asserted here and not in a sibling.
     *
     * <p>Assumptions: every case below drives the codec through the PUBLIC surface -- it mints a page,
     * takes the boundary the page published, presents it back, and observes which halves reached the
     * repository. No case reaches the private encoder or decoder, and no case parses a position. That is
     * deliberate: the position is sealed and opaque, so an assertion about its internal arrangement would
     * pin an implementation detail the service is free to change, whereas an assertion that it
     * reconstructs both halves pins the contract that actually matters.
     */
    @Nested
    @DisplayName("on the composite position codec")
    class OnTheCompositePositionCodec {

        /**
         * A trailing boundary reopens as BOTH halves of the key, leading zeros intact.
         *
         * <p>Assumptions: the halves are observed where they are consumed, as the two arguments the
         * forward keyed walk receives. Observing them anywhere earlier would not distinguish a codec that
         * reconstructed the pair from one that reconstructed only the type half and defaulted the other.
         *
         * <p>It takes no parameter and returns no value.
         */
        @Test
        @DisplayName("a trailing boundary reopens as both halves with the leading zeros intact")
        void aTrailingBoundaryReopensAsBothHalves() {
            when(categories.findFirstPage(any(Limit.class)))
                    .thenReturn(List.of(storedCategory()));

            PageResponse<TransactionCategoryResponse> opening =
                    service.list(openingBrowse(), sealer, SUBJECT);
            service.list(browse(opening.lastKey(), PageDirection.NEXT, null, null), sealer, SUBJECT);

            ArgumentCaptor<String> typeHalf = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> categoryHalf = ArgumentCaptor.forClass(String.class);
            verify(categories)
                    .findPageAfter(typeHalf.capture(), categoryHalf.capture(), any(Limit.class));

            assertThat(typeHalf.getValue())
                    .as("the two-character type half declared CHAR(2) at TRNTYCAT.ddl line 2")
                    .isEqualTo(TYPE_CD);
            assertThat(categoryHalf.getValue())
                    .as("the four-character digit half declared CHAR(4) at TRNTYCAT.ddl line 3; an"
                            + " integral half would deliver 5 here")
                    .isEqualTo(CAT_CD)
                    .hasSize(TransactionCategory.CAT_CD_WIDTH)
                    .startsWith("000");
        }

        /**
         * Every one of the eighteen seeded keys survives a mint and a reopen unchanged.
         *
         * <p>Assumptions: a fresh double and a fresh service are built per key so that the single
         * interaction verified belongs to that key alone. Re-stubbing one shared double eighteen times
         * would leave every earlier interaction on it and the verification would no longer identify
         * which key produced which call.
         *
         * <p>It takes no parameter and returns no value.
         */
        @Test
        @DisplayName("all eighteen seeded keys round-trip through a minted position")
        void allSeededKeysRoundTrip() {
            for (SeededKey key : SEEDED_KEYS) {
                // WHY : Assumptions: a double per key, because the verification below asserts a SINGLE
                //       interaction. Carried across iterations, the eighteenth verification would see
                //       eighteen calls and could no longer say which key produced which.
                TransactionCategoryRepository fresh = mock(TransactionCategoryRepository.class);
                when(fresh.findFirstPage(any(Limit.class))).thenReturn(
                        List.of(storedCategory(key.typeCd(), key.catCd(), STORED_DESCRIPTION)));
                TransactionCategoryService local = new TransactionCategoryService(fresh);

                PageResponse<TransactionCategoryResponse> opening =
                        local.list(openingBrowse(), sealer, SUBJECT);
                local.list(browse(opening.lastKey(), PageDirection.NEXT, null, null), sealer,
                        SUBJECT);

                ArgumentCaptor<String> typeHalf = ArgumentCaptor.forClass(String.class);
                ArgumentCaptor<String> categoryHalf = ArgumentCaptor.forClass(String.class);
                verify(fresh)
                        .findPageAfter(typeHalf.capture(), categoryHalf.capture(), any(Limit.class));

                assertThat(typeHalf.getValue() + "|" + categoryHalf.getValue())
                        .as("seeded key %s|%s must reopen as itself", key.typeCd(), key.catCd())
                        .isEqualTo(key.typeCd() + "|" + key.catCd());
            }
        }

        /**
         * The by-parent route reopens the CATEGORY half only, the parent arriving separately.
         *
         * <p>Assumptions: this is the one shape a single-column browse cannot have, and it is where a
         * composite codec is most easily got wrong. The type-narrowed walk pins the type half with an
         * equality, so only the category half can vary and only it is bound from the boundary; the
         * narrowing type comes from the request path instead. A codec that handed the boundary's type
         * half to this walk would silently produce the right answer whenever the two agreed -- which is
         * always, on this route -- and would only fail once a position minted elsewhere was presented.
         *
         * <p>It takes no parameter and returns no value.
         */
        @Test
        @DisplayName("the by-parent route reopens the category half and takes the parent from the path")
        void theByParentRouteReopensTheCategoryHalfOnly() {
            when(categories.findFirstPageOfType(anyString(), any(Limit.class)))
                    .thenReturn(List.of(storedCategory()));

            PageResponse<TransactionCategoryResponse> opening =
                    service.listByType(TYPE_CD, openingBrowse(), sealer, SUBJECT);
            service.listByType(TYPE_CD, browse(opening.lastKey(), PageDirection.NEXT, null, null),
                    sealer, SUBJECT);

            ArgumentCaptor<String> narrowingType = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> categoryHalf = ArgumentCaptor.forClass(String.class);
            verify(categories).findPageOfTypeAfter(
                    narrowingType.capture(), categoryHalf.capture(), any(Limit.class));

            assertThat(narrowingType.getValue())
                    .as("the parent reaches the walk from the request path")
                    .isEqualTo(TYPE_CD);
            assertThat(categoryHalf.getValue())
                    .as("only the category half is bound from the boundary, because the equality pins"
                            + " the type half")
                    .isEqualTo(CAT_CD);
        }

        /**
         * A leading boundary reopens backward as both halves, and by parent as the category half.
         *
         * <p>Assumptions: both backward walks are asserted in one case because the property is one
         * property -- that the leading boundary is sealed for backward travel and decodes the same way
         * the trailing one does. Asserting only the forward direction would leave the direction that
         * carries the leading boundary entirely unproven.
         *
         * <p>It takes no parameter and returns no value.
         */
        @Test
        @DisplayName("a leading boundary reopens backward on both routes")
        void aLeadingBoundaryReopensBackward() {
            when(categories.findFirstPage(any(Limit.class)))
                    .thenReturn(List.of(storedCategory(TYPE_CD, FIRST_CAT_CD, STORED_DESCRIPTION)));
            when(categories.findFirstPageOfType(anyString(), any(Limit.class)))
                    .thenReturn(List.of(storedCategory(TYPE_CD, FIRST_CAT_CD, STORED_DESCRIPTION)));

            PageResponse<TransactionCategoryResponse> collection =
                    service.list(openingBrowse(), sealer, SUBJECT);
            service.list(browse(collection.firstKey(), PageDirection.PREVIOUS, null, null), sealer,
                    SUBJECT);
            verify(categories).findPageBefore(TYPE_CD, FIRST_CAT_CD, Limit.of(
                    TransactionCategoryService.PAGE_SIZE + 1));

            PageResponse<TransactionCategoryResponse> byType =
                    service.listByType(TYPE_CD, openingBrowse(), sealer, SUBJECT);
            service.listByType(TYPE_CD,
                    browse(byType.firstKey(), PageDirection.PREVIOUS, null, null), sealer, SUBJECT);
            verify(categories).findPageOfTypeBefore(TYPE_CD, FIRST_CAT_CD, Limit.of(
                    TransactionCategoryService.PAGE_SIZE + 1));
        }

        /**
         * A published position is opaque: it is sealed, carries no plain half, and is never repeatable.
         *
         * <p>Assumptions: opacity is asserted three ways because no one of them is sufficient. That the
         * value has the sealed shape says it went through the sealer; that it carries neither half's
         * plain text nor a separator says a caller cannot lift the key out of it; and that minting the
         * same boundary twice yields two DIFFERENT values says the encoding is not a reversible one a
         * caller could learn to construct, while both still reopen to the same pair. The third is the
         * one that would catch a position degraded to a plain or merely encoded pair.
         *
         * <p>It takes no parameter and returns no value.
         */
        @Test
        @DisplayName("a published position is sealed, carries no plain half, and never repeats")
        void aPublishedPositionIsOpaque() {
            when(categories.findFirstPage(any(Limit.class)))
                    .thenReturn(List.of(storedCategory()));

            String first = service.list(openingBrowse(), sealer, SUBJECT).lastKey();
            String second = service.list(openingBrowse(), sealer, SUBJECT).lastKey();

            assertThat(CursorToken.hasSealedShape(first))
                    .as("a position leaves this service sealed, never as a bare key")
                    .isTrue();
            assertThat(first)
                    .as("neither half nor any separator between them is legible in the token")
                    .doesNotContain(TYPE_CD + "|" + CAT_CD)
                    .doesNotContain("|");
            assertThat(second)
                    .as("two mintings of one boundary differ, so the token cannot be reconstructed by a"
                            + " caller that has seen one")
                    .isNotEqualTo(first);

            service.list(browse(first, PageDirection.NEXT, null, null), sealer, SUBJECT);
            service.list(browse(second, PageDirection.NEXT, null, null), sealer, SUBJECT);
            verify(categories, times(2))
                    .findPageAfter(TYPE_CD, CAT_CD, Limit.of(
                            TransactionCategoryService.PAGE_SIZE + 1));
        }

        /**
         * A position minted on one route is refused on the other, in both directions.
         *
         * <p>Assumptions: the narrowing is held IDENTICAL across the two presentations so that the only
         * difference is which route minted the position. Presenting an unnarrowed collection request
         * instead would also be refused, but for two reasons at once, and the case would not show that
         * the route itself is part of the seal.
         *
         * <p>It takes no parameter and returns no value.
         */
        @Test
        @DisplayName("neither route opens a position the other route minted")
        void neitherRouteOpensTheOthersPosition() {
            String mintedByParent = sealedPosition(byTypeBinding(false, TYPE_CD, null),
                    TYPE_CD + "|" + CAT_CD);
            String mintedByCollection = sealedPosition(collectionBinding(false, TYPE_CD, null),
                    TYPE_CD + "|" + CAT_CD);

            assertThatThrownBy(() -> service.list(
                    browse(mintedByParent, PageDirection.NEXT, TYPE_CD, null), sealer, SUBJECT))
                    .as("a by-parent position must not open the collection route")
                    .isInstanceOf(CursorToken.InvalidCursorException.class);

            assertThatThrownBy(() -> service.listByType(TYPE_CD,
                    browse(mintedByCollection, PageDirection.NEXT, null, null), sealer, SUBJECT))
                    .as("a collection position must not open the by-parent route")
                    .isInstanceOf(CursorToken.InvalidCursorException.class);

            verifyNoInteractions(categories);
        }

        /**
         * Neither boundary can be replayed in the opposite direction.
         *
         * <p>Assumptions: the two boundaries are sealed under different bindings, so a trailing boundary
         * presented as a backward position is refused by the seal rather than answered with a window on
         * the wrong side of the caller's place. That refusal is what stops a page from being paged into
         * from the wrong end, which reads to a caller as rows arriving twice.
         *
         * <p>It takes no parameter and returns no value.
         */
        @Test
        @DisplayName("a trailing boundary is not a backward position and a leading one is not a forward")
        void neitherBoundaryReplaysInTheOtherDirection() {
            when(categories.findFirstPage(any(Limit.class)))
                    .thenReturn(List.of(storedCategory()));
            PageResponse<TransactionCategoryResponse> opening =
                    service.list(openingBrowse(), sealer, SUBJECT);

            assertThatThrownBy(() -> service.list(
                    browse(opening.lastKey(), PageDirection.PREVIOUS, null, null), sealer, SUBJECT))
                    .isInstanceOf(CursorToken.InvalidCursorException.class);
            assertThatThrownBy(() -> service.list(
                    browse(opening.firstKey(), PageDirection.NEXT, null, null), sealer, SUBJECT))
                    .isInstanceOf(CursorToken.InvalidCursorException.class);
        }
    }

    /**
     * Cases refusing a position that opens but cannot be read as a two-part key.
     *
     * <p>Alternatives Considered: treating an unreadable position as an absent one and answering with the
     * opening page. Declined because it is wrong in the one direction a caller cannot detect -- it
     * believes it is continuing a walk, receives the opening window instead, and ends with the first rows
     * repeated in the middle of its results while whatever followed the real boundary is never delivered.
     * A refusal is the only answer a caller can act on, by restarting deliberately.
     *
     * <p>Assumptions: every payload below is SEALED under the binding the route will open with, so the
     * sealer accepts it and the service's own decoder is what refuses it. Presenting a hand-built token
     * would be refused earlier and by a different component, and the case would then prove nothing about
     * this service. The two refusals are told apart by type: the sealer's refusal is a subtype of the
     * caller-input refusal, so each case asserts that the failure is the base type and NOT that subtype.
     */
    @Nested
    @DisplayName("on an unreadable position")
    class OnAnUnreadablePosition {

        /**
         * Presents one sealed but unreadable payload on the collection route and returns the refusal.
         *
         * @param plainPosition the plain text sealed into the position, chosen to be unreadable as a key
         * @return the throwable the browse raised, or {@code null} if it unexpectedly raised none
         */
        private Throwable refusalFor(String plainPosition) {
            String position = sealedPosition(collectionBinding(false, null, null), plainPosition);
            return catchThrowable(() ->
                    service.list(browse(position, PageDirection.NEXT, null, null), sealer, SUBJECT));
        }

        /**
         * Five distinct unreadable payloads are each refused as caller input by this service's decoder.
         *
         * <p>Assumptions: the five are chosen to cover the five ways the pair can fail to reconstruct,
         * and the third and fourth are the ones that matter most for a digit key of declared width -- a
         * category half of three characters is exactly what a caller that dropped a leading zero would
         * supply, and a half that is not all digits is what a caller that treated the key as a number and
         * re-rendered it would supply. Both must be refused rather than silently seeking to a key that
         * does not exist.
         *
         * <p>It takes no parameter and returns no value.
         */
        @Test
        @DisplayName("no separator, two separators, a short half, a short type and a non-digit half")
        void everyUnreadablePayloadIsRefusedAsCallerInput() {
            List<String> unreadable = List.of(
                    "010005", "01|00|05", "01|005", "1|0005", "01|00A5");

            for (String payload : unreadable) {
                Throwable refusal = refusalFor(payload);

                assertThat(refusal)
                        .as("payload %s must be refused, not answered with a window", payload)
                        .isInstanceOf(ClientInputException.class)
                        .isNotInstanceOf(CursorToken.InvalidCursorException.class);
            }
            verify(categories, never()).findFirstPage(any(Limit.class));
            verify(categories, never()).findPageAfter(anyString(), anyString(), any(Limit.class));
        }

        /**
         * The refusal names the position component, carries the validation code, and echoes no token.
         *
         * <p>Assumptions: the offending value is never rendered back. A position is sealed, so a value
         * that failed to read is either corrupted or forged, and returning it would reflect unvalidated
         * caller input straight back to whoever supplied it. The case asserts the absence rather than
         * trusting it, because a refusal that quoted the value would still satisfy every other assertion
         * here.
         *
         * <p>It takes no parameter and returns no value.
         */
        @Test
        @DisplayName("the refusal names the position component and never echoes the token")
        void theRefusalNamesThePositionAndEchoesNothing() {
            String position = sealedPosition(collectionBinding(false, null, null), "010005");
            ClientInputException refusal = (ClientInputException) catchThrowable(() ->
                    service.list(browse(position, PageDirection.NEXT, null, null), sealer, SUBJECT));

            assertThat(refusal.code()).isEqualTo(ApiError.CODE_VALIDATION);
            assertThat(refusal.field()).isEqualTo(TransactionCategoryService.FIELD_CURSOR);
            assertThat(refusal.getMessage())
                    .isEqualTo(TransactionCategoryService.MESSAGE_CURSOR_MALFORMED)
                    .doesNotContain(position);
        }
    }

    /**
     * Cases pinning the order a page publishes to the order the baseline index declares.
     *
     * <p>Assumptions: the ordering has a schema origin and is not a preference.
     * {@code app/app-transaction-type-db2/ddl/XTRNTYCAT.ddl} declares
     * {@code CREATE UNIQUE INDEX CARDDEMO.X_TRAN_TYPE_CATG} on
     * {@code CARDDEMO.TRANSACTION_TYPE_CATEGORY} over {@code (TRC_TYPE_CODE ASC, TRC_TYPE_CATEGORY ASC)}
     * across its lines 1 to 3, and {@code TRNTYCAT.ddl} declares the same pair as the primary key at its
     * line 5. Both halves ascending, type first, is therefore the order the collection has.
     *
     * <p>Trade-offs: the ordering is asserted twice, in two different ways, because neither way alone is
     * enough. A results-shaped assertion cannot see a query's own {@code ORDER BY} at all, since a double
     * returns whatever the case handed it; and an annotation-shaped assertion cannot see whether the
     * service preserves what the query returned. The cost is one reflective case coupled to six member
     * names, accepted because the alternative leaves half the property unasserted.
     */
    @Nested
    @DisplayName("on the published ordering")
    class OnThePublishedOrdering {

        /**
         * Each of the six keyed walks declares the pair ordering the baseline index declares.
         *
         * <p>Assumptions: four walks order ascending and two descending, and that asymmetry is correct
         * rather than an inconsistency -- a backward walk must read away from the boundary to find the
         * rows nearest it, so it orders descending and the caller reverses the run for display. Asserting
         * that all six ordered ascending would be asserting a walk that cannot work.
         *
         * @throws ReflectiveOperationException if any of the six walks is renamed or re-signed, which
         *     fails this case rather than letting it pass by omission
         * @throws AssertionError if a walk stops declaring a query of its own, so that its ordering is no
         *     longer readable here
         */
        @Test
        @DisplayName("all six keyed walks order on the pair, four ascending and two descending")
        void allSixKeyedWalksOrderOnThePair() throws ReflectiveOperationException {
            String ascending = "order by c.id.typeCd asc, c.id.catCd asc";
            String descending = "order by c.id.typeCd desc, c.id.catCd desc";

            assertThat(declaredQuery("findFirstPage", Limit.class)).contains(ascending);
            assertThat(declaredQuery("findPageAfter", String.class, String.class, Limit.class))
                    .contains(ascending);
            assertThat(declaredQuery("findFirstPageOfType", String.class, Limit.class))
                    .contains(ascending);
            assertThat(declaredQuery("findPageOfTypeAfter", String.class, String.class, Limit.class))
                    .contains(ascending);

            assertThat(declaredQuery("findPageBefore", String.class, String.class, Limit.class))
                    .as("a backward walk reads away from the boundary, so it orders descending and the"
                            + " caller reverses the run")
                    .contains(descending);
            assertThat(declaredQuery("findPageOfTypeBefore", String.class, String.class, Limit.class))
                    .contains(descending);
        }

        /**
         * The unnarrowed seek compares the whole pair rather than each half independently.
         *
         * <p>Assumptions: the only correct form is the grouped one, {@code type > :type or (type = :type
         * and cat > :cat)}. The plausible wrong alternative, {@code type >= :type and cat > :cat}, drops
         * every row at each type boundary whose category is at or below the boundary row's: on the seeded
         * data, stepping past {@code 01|0005} would lose {@code 02|0001} outright, and the loss presents
         * as a short page rather than as an error.
         *
         * @throws ReflectiveOperationException if either seeking walk is renamed or re-signed
         * @throws AssertionError if either walk stops declaring a query of its own
         */
        @Test
        @DisplayName("the unnarrowed seek groups the pair instead of comparing halves independently")
        void theUnnarrowedSeekGroupsThePair() throws ReflectiveOperationException {
            assertThat(declaredQuery("findPageAfter", String.class, String.class, Limit.class))
                    .contains("c.id.typeCd > :typeCd")
                    .contains("or (c.id.typeCd = :typeCd and c.id.catCd > :catCd)");
            assertThat(declaredQuery("findPageBefore", String.class, String.class, Limit.class))
                    .contains("c.id.typeCd < :typeCd")
                    .contains("or (c.id.typeCd = :typeCd and c.id.catCd < :catCd)");
        }

        /**
         * A forward window is published in the ascending pair order it was read in, across a type change.
         *
         * <p>Assumptions: the window deliberately crosses a type boundary, because a window inside one
         * type cannot distinguish an ordering over the pair from an ordering over the category half
         * alone. The two seeded runs {@code 01|0005} and {@code 02|0001} are exactly the adjacent pair
         * that a wrongly-formed comparison loses.
         *
         * <p>It takes no parameter and returns no value.
         */
        @Test
        @DisplayName("a forward window crossing a type change publishes in ascending pair order")
        void aForwardWindowCrossingATypeChangePublishesAscending() {
            when(categories.findFirstPage(any(Limit.class))).thenReturn(List.of(
                    storedCategory(TYPE_CD, "0004", "Fourth"),
                    storedCategory(TYPE_CD, CAT_CD, STORED_DESCRIPTION),
                    storedCategory(NEXT_TYPE_CD, FIRST_CAT_CD, "Cash payment")));

            PageResponse<TransactionCategoryResponse> page =
                    service.list(openingBrowse(), sealer, SUBJECT);

            assertThat(publishedKeys(page))
                    .as("type first and then category, both ascending, per X_TRAN_TYPE_CATG")
                    .containsExactly("01/0004", "01/0005", "02/0001");
        }

        /**
         * A backward window read descending is published ascending, so both directions agree.
         *
         * <p>Assumptions: the double returns a DESCENDING run because that is what the backward query
         * declares. The property under test is that the service reverses it, so a double returning an
         * ascending run would make this case pass whether the reversal existed or not.
         *
         * <p>It takes no parameter and returns no value.
         */
        @Test
        @DisplayName("a backward window read descending is published ascending")
        void aBackwardWindowIsPublishedAscending() {
            List<TransactionCategory> ascending = List.of(
                    storedCategory(TYPE_CD, "0002", "Second"),
                    storedCategory(TYPE_CD, "0003", "Third"),
                    storedCategory(TYPE_CD, "0004", "Fourth"));
            // WHY : Assumptions: the double hands back a DESCENDING run because that is the order the
            //       backward query declares. Handing back an ascending one would let this case pass
            //       whether the service reversed the result or not, which is the property under test.
            when(categories.findPageBefore(anyString(), anyString(), any(Limit.class)))
                    .thenReturn(descending(ascending));
            String position = sealedPosition(collectionBinding(true, null, null),
                    TYPE_CD + "|" + CAT_CD);

            PageResponse<TransactionCategoryResponse> page = service.list(
                    browse(position, PageDirection.PREVIOUS, null, null), sealer, SUBJECT);

            assertThat(publishedKeys(page))
                    .as("the read order is descending and the published order is the index order")
                    .containsExactly("01/0002", "01/0003", "01/0004");
        }

        /**
         * The window is seven rows, the eighth is a probe, and it leaves from the forward end.
         *
         * <p>Assumptions: seven is the baseline's own window, declared as
         * {@code 05 WS-MAX-SCREEN-LINES PIC S9(4) COMP VALUE 7.} at line 60 of
         * {@code app/app-transaction-type-db2/cbl/COTRTLIC.cbl}. The eighth row is requested so that
         * forward availability can be established by observing whether it came back, which is not the
         * same thing as counting the collection -- a count answers a different question, costs another
         * statement, and is stale the instant it is taken.
         *
         * <p>It takes no parameter and returns no value.
         */
        @Test
        @DisplayName("eight rows are requested, seven are published, and the surplus is the last")
        void theSurplusRowIsRequestedAndDroppedFromTheForwardEnd() {
            when(categories.findFirstPage(any(Limit.class)))
                    .thenReturn(ascendingRun(TYPE_CD, TransactionCategoryService.PAGE_SIZE + 1));

            PageResponse<TransactionCategoryResponse> page =
                    service.list(openingBrowse(), sealer, SUBJECT);

            ArgumentCaptor<Limit> requested = ArgumentCaptor.forClass(Limit.class);
            verify(categories).findFirstPage(requested.capture());
            assertThat(requested.getValue().max())
                    .as("one more row than the window, which is how the further-page flag is set")
                    .isEqualTo(TransactionCategoryService.PAGE_SIZE + 1);

            assertThat(page.items()).hasSize(TransactionCategoryService.PAGE_SIZE);
            assertThat(publishedKeys(page))
                    .as("the probe leaves from the forward end, so the highest key is withheld")
                    .containsExactly("01/0001", "01/0002", "01/0003", "01/0004", "01/0005",
                            "01/0006", "01/0007");
            assertThat(page.hasNext()).isTrue();
        }

        /**
         * On a backward walk the probe leaves from the leading end, keeping the rows nearest the caller.
         *
         * <p>Assumptions: this is the mirror of the case above and is the half most easily got wrong. A
         * backward page must publish the seven rows immediately BEFORE the caller's position, so the
         * probe is the furthest away and must leave from the ascending start. Dropping it from the end
         * instead would publish a window seven rows adrift of where the caller actually is.
         *
         * <p>Trade-offs: the eight-row run under one type is synthetic, because no seeded type carries
         * eight categories -- type {@code 01} carries five. It is evidence about window mechanics only.
         *
         * <p>It takes no parameter and returns no value.
         */
        @Test
        @DisplayName("on a backward walk the probe leaves from the leading end")
        void theSurplusRowLeavesFromTheLeadingEndBackward() {
            when(categories.findPageBefore(anyString(), anyString(), any(Limit.class)))
                    .thenReturn(descending(
                            ascendingRun(TYPE_CD, TransactionCategoryService.PAGE_SIZE + 1)));
            String position = sealedPosition(collectionBinding(true, null, null),
                    TYPE_CD + "|0009");

            PageResponse<TransactionCategoryResponse> page = service.list(
                    browse(position, PageDirection.PREVIOUS, null, null), sealer, SUBJECT);

            assertThat(publishedKeys(page))
                    .as("the seven rows nearest the position, with the furthest one withheld")
                    .containsExactly("01/0002", "01/0003", "01/0004", "01/0005", "01/0006",
                            "01/0007", "01/0008");
            assertThat(page.items()).hasSize(TransactionCategoryService.PAGE_SIZE);
        }

        /**
         * Exactly a windowful with no probe reports no further page, and no count is ever taken.
         *
         * <p>Assumptions: the flag is derived from the probe alone. A service that fell back on a count
         * would answer the same way on this fixture, so the case also asserts that the count member was
         * never called -- which is the only observation that tells the two mechanisms apart.
         *
         * <p>It takes no parameter and returns no value.
         */
        @Test
        @DisplayName("exactly seven rows reports no further page and takes no count")
        void exactlySevenRowsReportsNoFurtherPage() {
            when(categories.findFirstPage(any(Limit.class)))
                    .thenReturn(ascendingRun(TYPE_CD, TransactionCategoryService.PAGE_SIZE));

            PageResponse<TransactionCategoryResponse> page =
                    service.list(openingBrowse(), sealer, SUBJECT);

            assertThat(page.items()).hasSize(TransactionCategoryService.PAGE_SIZE);
            assertThat(page.hasNext()).isFalse();
            verify(categories, never()).countByTypeCd(anyString());
        }

        /**
         * Every page publishes the position a backward step is issued from, and no count is taken for it.
         *
         * <p>Assumptions: the envelope carries four members, so whether a page precedes this one is not a
         * question it answers -- the reference answers that from the page ordinal its terminal holds
         * between turns, and the migrated answer belongs to the client. What both pages here must supply
         * is the leading boundary, and the substance of the case is that supplying it costs no second
         * query.
         *
         * <p>It takes no parameter and returns no value.
         */
        @Test
        @DisplayName("every page publishes its leading boundary and no count is taken for it")
        void everyPagePublishesItsLeadingBoundaryWithoutCounting() {
            when(categories.findFirstPage(any(Limit.class)))
                    .thenReturn(List.of(storedCategory()));
            when(categories.findPageAfter(anyString(), anyString(), any(Limit.class)))
                    .thenReturn(List.of(storedCategory(NEXT_TYPE_CD, FIRST_CAT_CD, "Cash payment")));

            PageResponse<TransactionCategoryResponse> opening =
                    service.list(openingBrowse(), sealer, SUBJECT);
            assertThat(opening.firstKey())
                    .as("the opening page names the position a backward step would be issued from")
                    .isNotNull();

            PageResponse<TransactionCategoryResponse> resumed = service.list(
                    browse(opening.lastKey(), PageDirection.NEXT, null, null), sealer, SUBJECT);
            assertThat(resumed.firstKey())
                    .as("and so does a page reached from a position")
                    .isNotNull();

            verify(categories, never()).countByTypeCd(anyString());
        }

        /**
         * The two published boundaries name rows the caller actually received.
         *
         * <p>Assumptions: this is asserted by REPLAYING both boundaries and observing which keys reach
         * the repository, rather than by parsing them, since they are sealed. The trailing boundary must
         * name the last published row and the leading one the first; a boundary naming the withheld probe
         * would hand the caller a position it never saw, and the next page would then begin one row past
         * where it should.
         *
         * <p>It takes no parameter and returns no value.
         */
        @Test
        @DisplayName("both boundaries name published rows and never the withheld probe")
        void bothBoundariesNamePublishedRows() {
            when(categories.findFirstPage(any(Limit.class)))
                    .thenReturn(ascendingRun(TYPE_CD, TransactionCategoryService.PAGE_SIZE + 1));

            PageResponse<TransactionCategoryResponse> page =
                    service.list(openingBrowse(), sealer, SUBJECT);

            service.list(browse(page.lastKey(), PageDirection.NEXT, null, null), sealer, SUBJECT);
            verify(categories).findPageAfter(TYPE_CD, "0007",
                    Limit.of(TransactionCategoryService.PAGE_SIZE + 1));
            verify(categories, never()).findPageAfter(TYPE_CD, "0008",
                    Limit.of(TransactionCategoryService.PAGE_SIZE + 1));

            service.list(browse(page.firstKey(), PageDirection.PREVIOUS, null, null), sealer,
                    SUBJECT);
            verify(categories).findPageBefore(TYPE_CD, "0001",
                    Limit.of(TransactionCategoryService.PAGE_SIZE + 1));
        }

        /**
         * An exhausted collection is the empty envelope, with both boundaries absent.
         *
         * <p>Assumptions: both boundaries are absent rather than blank, because a page with no rows has
         * no ends to name. The envelope's own construction refuses a non-empty page missing either
         * boundary, so the absence here is the only shape an empty page can legally take.
         *
         * <p>It takes no parameter and returns no value.
         */
        @Test
        @DisplayName("an exhausted collection publishes no rows, no boundaries and no availability")
        void anExhaustedCollectionIsTheEmptyEnvelope() {
            when(categories.findFirstPage(any(Limit.class))).thenReturn(List.of());

            PageResponse<TransactionCategoryResponse> page =
                    service.list(openingBrowse(), sealer, SUBJECT);

            assertThat(page.items()).isEmpty();
            assertThat(page.firstKey()).isNull();
            assertThat(page.lastKey()).isNull();
            assertThat(page.hasNext()).isFalse();
        }
    }

    /**
     * Cases pinning the child-existence reading as advisory rather than as the referential authority.
     *
     * <p>Assumptions: the authority is the declared constraint. Lines 6 and 7 of
     * {@code app/app-transaction-type-db2/ddl/TRNTYCAT.ddl} declare
     * {@code FOREIGN KEY TRC_TYPE_CODE (TRC_TYPE_CODE) REFERENCES CARDDEMO.TRANSACTION_TYPE (TR_TYPE) ON
     * DELETE RESTRICT}, and that clause refuses a parent's delete whether this reading was consulted or
     * not. The reading exists so the parent can produce the clearer sentence on the common case, and it is
     * stale the instant it returns -- a category inserted immediately afterwards falsifies a negative
     * answer and one deleted immediately afterwards falsifies a positive one.
     *
     * <p>Trade-offs: the parent's own refusal is asserted by {@link TransactionTypeServiceTest}, which
     * owns that path, and is cited here rather than repeated. What this class owes the contract is the
     * reading itself, in both directions, plus the structural fact that it cannot be the authority.
     */
    @Nested
    @DisplayName("on the child-existence reading")
    class OnTheChildExistenceReading {

        /**
         * A type with categories reads true and a type without them reads false.
         *
         * <p>Assumptions: both directions are asserted in one case because a reading that answered one
         * value unconditionally would satisfy either half alone. The boundary that matters is one child
         * against none, so the positive fixture uses a single row rather than several.
         *
         * <p>It takes no parameter and returns no value.
         */
        @Test
        @DisplayName("a referenced type reads true and an unreferenced one reads false")
        void theReadingAnswersInBothDirections() {
            when(categories.countByTypeCd(TYPE_CD)).thenReturn(1L);
            when(categories.countByTypeCd(ABSENT_TYPE_CD)).thenReturn(0L);

            assertThat(service.existsForType(TYPE_CD))
                    .as("one surviving child is enough for ON DELETE RESTRICT to bite")
                    .isTrue();
            assertThat(service.existsForType(ABSENT_TYPE_CD))
                    .as("no child means the constraint has nothing to refuse")
                    .isFalse();
        }

        /**
         * The reading takes a count and touches nothing else.
         *
         * <p>Assumptions: it must not read the parent row, lock anything, or load the children. Any of
         * those would make the reading look authoritative to a caller reasoning about what it costs, and
         * loading the children would turn a bounded count into an unbounded read on a type with many.
         *
         * <p>It takes no parameter and returns no value.
         */
        @Test
        @DisplayName("the reading takes one count and touches nothing else")
        void theReadingTakesOneCountOnly() {
            when(categories.countByTypeCd(TYPE_CD)).thenReturn(4L);

            service.existsForType(TYPE_CD);

            verify(categories).countByTypeCd(TYPE_CD);
            verifyNoMoreInteractions(categories);
        }

        /**
         * The service holds no parent collaborator at all, so it cannot be the referential authority.
         *
         * <p>Assumptions: this is a structural assertion and it is the strongest form the property has.
         * A behavioural case can only show that a parent was not consulted on the paths it exercises,
         * whereas a single declared constructor taking the category repository alone shows that no path
         * could consult one. That is what leaves the declared constraint as the only possible authority
         * over the parent relationship.
         *
         * <p>It takes no parameter and returns no value.
         */
        @Test
        @DisplayName("the service declares one collaborator, so no parent table is reachable from it")
        void theServiceHoldsNoParentCollaborator() {
            Constructor<?>[] declared = TransactionCategoryService.class.getDeclaredConstructors();

            assertThat(declared).hasSize(1);
            assertThat(declared[0].getParameterTypes())
                    .as("only the category table is reachable, so the parent relationship is enforced"
                            + " by the declared foreign key and by nothing in this class")
                    .containsExactly(TransactionCategoryRepository.class);
        }

        /**
         * A negative reading does not disarm the constraint on the very next write.
         *
         * <p>Assumptions: this is the race the reading cannot cover, and asserting it is what keeps the
         * reading honest. The parent existed for nobody, the insert was attempted anyway, and the
         * constraint refused it -- so the outcome a caller receives is decided by the constraint and is
         * indistinguishable from the outcome it would receive had the reading never run.
         *
         * <p>It takes no parameter and returns no value.
         */
        @Test
        @DisplayName("a negative reading still leaves the constraint to refuse the insert")
        void aNegativeReadingLeavesTheConstraintToRefuse() {
            when(categories.countByTypeCd(ABSENT_TYPE_CD)).thenReturn(0L);
            when(categories.findByIdIs(id(ABSENT_TYPE_CD, FIRST_CAT_CD)))
                    .thenReturn(Optional.empty());
            when(categories.saveAndFlush(any(TransactionCategory.class))).thenThrow(
                    integrityViolation(TransactionTypeService.SQLSTATE_FOREIGN_KEY_VIOLATION));

            assertThat(service.existsForType(ABSENT_TYPE_CD)).isFalse();
            assertThatThrownBy(() -> service.create(new TransactionCategoryCreateRequest(
                    ABSENT_TYPE_CD, FIRST_CAT_CD, STORED_DESCRIPTION)))
                    .as("the ON DELETE RESTRICT relationship is never weakened to a server fault")
                    .isInstanceOf(
                            TransactionCategoryService.UnknownParentTransactionTypeException.class);
        }
    }

    /**
     * Cases telling the two directions of one foreign key apart, and telling both from anything else.
     *
     * <p>Refactoring Rationale: the baseline discriminates referential refusal UNEVENLY across the three
     * programs of this extension, so no single one of them can be generalised from.
     * {@code app/app-transaction-type-db2/cbl/COTRTUPC.cbl} carries an arm for SQLCODE -532 at its line
     * 1638, which sets its delete-failed condition and composes
     * {@code 'Please delete associated child records first:'} at its line 1641;
     * {@code app/app-transaction-type-db2/cbl/COTRTLIC.cbl} carries the same arm at its physical line
     * 1914; and {@code app/app-transaction-type-db2/cbl/COBTUPDT.cbl} carries NONE -- a search of that
     * file for the code returns nothing. Its insert path is blunter still: {@code 9700-INSERT-RECORD} at
     * lines 1596 to 1623 of {@code COTRTUPC.cbl} has a zero arm and a catch-all and no duplicate-key arm
     * at all, so a duplicate and any other insert failure are one answer there. The migration reports
     * them apart so that the api package and an operator can, and the divergence is documented in
     * {@code docs/architecture/cobol-to-service-traceability.md}, which this class references and does not
     * author.
     *
     * <p>Assumptions: one declared constraint bites in two directions that mean opposite things to a
     * caller. Deleting a type that categories still reference is the parent's refusal; inserting a
     * category under a type that does not exist is this one. Both arrive as the same SQLSTATE, so a merged
     * outcome would tell a caller who mistyped a parent code to go and delete dependent rows that do not
     * exist.
     */
    @Nested
    @DisplayName("on the typed integrity classification")
    class OnTheTypedIntegrityClassification {

        /**
         * Presents a create whose insert is refused with a given state and returns the outcome.
         *
         * @param failure the integrity violation the flush will raise; must not be {@code null}
         * @return the throwable the create raised, or {@code null} if it unexpectedly raised none
         */
        private Throwable createRefusedWith(DataIntegrityViolationException failure) {
            when(categories.findByIdIs(id(TYPE_CD, CAT_CD))).thenReturn(Optional.empty());
            when(categories.saveAndFlush(any(TransactionCategory.class))).thenThrow(failure);
            return catchThrowable(() -> service.create(
                    new TransactionCategoryCreateRequest(TYPE_CD, CAT_CD, STORED_DESCRIPTION)));
        }

        /**
         * A unique violation is the duplicate outcome and a foreign-key violation is the orphan outcome.
         *
         * <p>Assumptions: the two states are read from the sibling parent service rather than re-declared
         * here, because both classes classify the same two conditions raised by the same constraint pair
         * and a second pair of literals could be edited on one side only.
         *
         * <p>It takes no parameter and returns no value.
         */
        @Test
        @DisplayName("a unique violation is the duplicate and a foreign-key violation is the orphan")
        void eachStateSelectsItsOwnOutcome() {
            assertThat(createRefusedWith(
                    integrityViolation(TransactionTypeService.SQLSTATE_UNIQUE_VIOLATION)))
                    .isInstanceOf(
                            TransactionCategoryService.DuplicateTransactionCategoryException.class);

            // WHY : Trade-offs: the doubles are rebuilt mid-case so that ONE case can present two
            //       refusals and assert the contrast between them. Two separate cases would each see
            //       one state and neither could assert that the two select different outcomes.
            resetDoubles();
            assertThat(createRefusedWith(
                    integrityViolation(TransactionTypeService.SQLSTATE_FOREIGN_KEY_VIOLATION)))
                    .isInstanceOf(
                            TransactionCategoryService.UnknownParentTransactionTypeException.class);
        }

        /**
         * The two outcomes are distinct types although they deliberately carry the same contention kind.
         *
         * <p>Assumptions: separating the TYPES must not separate the WORDING. Both conditions reach the
         * caller through the same integrity branch with the same referential sentence, which is the
         * published behaviour; the distinct types exist so that the api package can choose without
         * re-reading the SQLSTATE itself. A case asserting only that the types differ would permit a
         * change that also split the sentence, and a case asserting only the shared kind would permit the
         * merge this classification exists to prevent.
         *
         * <p>It takes no parameter and returns no value.
         */
        @Test
        @DisplayName("the two outcomes are different types carrying the same contention kind")
        void theTwoOutcomesAreDistinctTypesSharingOneKind() {
            RecordConflictException duplicate = (RecordConflictException) createRefusedWith(
                    integrityViolation(TransactionTypeService.SQLSTATE_UNIQUE_VIOLATION));
            resetDoubles();
            RecordConflictException orphan = (RecordConflictException) createRefusedWith(
                    integrityViolation(TransactionTypeService.SQLSTATE_FOREIGN_KEY_VIOLATION));

            assertThat(duplicate)
                    .isNotInstanceOf(
                            TransactionCategoryService.UnknownParentTransactionTypeException.class);
            assertThat(orphan)
                    .isNotInstanceOf(
                            TransactionCategoryService.DuplicateTransactionCategoryException.class);
            assertThat(duplicate.kind())
                    .as("one referential sentence, selected from the kind, serves both")
                    .isEqualTo(RecordConflictException.Kind.REFERENCED_ROW)
                    .isEqualTo(orphan.kind());
        }

        /**
         * A state that is neither of the two is re-raised exactly as it arrived.
         *
         * <p>Assumptions: this is the second half of the discrimination and the half a partial test
         * omits. A classifier that answered every integrity refusal with the referential outcome would
         * satisfy both cases above and would tell a caller hitting a check violation to delete dependent
         * rows. The assertion is on IDENTITY rather than on type, because handing back the same instance
         * is what preserves the answer this path gave before any classification existed.
         *
         * <p>It takes no parameter and returns no value.
         */
        @Test
        @DisplayName("an unrelated state is re-raised as the very same instance")
        void anUnrelatedStateIsReRaisedUnchanged() {
            DataIntegrityViolationException unrelated = integrityViolation(UNRELATED_SQLSTATE);

            assertThat(createRefusedWith(unrelated))
                    .isSameAs(unrelated)
                    .isNotInstanceOf(RecordConflictException.class);
        }

        /**
         * The state is read from the cause chain and never from the wrapper's own text.
         *
         * <p>Assumptions: a wrapper whose message merely quotes the digits carries no state at all, and
         * must therefore classify as unrecognised. This is asserted because reading the text would appear
         * to work on every fixture above -- each of those wrappers has a cause that agrees with it -- and
         * would then misclassify a refusal whose vendor text happened to mention another constraint's
         * state.
         *
         * <p>It takes no parameter and returns no value.
         */
        @Test
        @DisplayName("a violation whose text quotes a state but whose chain reports none is unchanged")
        void theStateIsReadFromTheChainAndNotTheText() {
            DataIntegrityViolationException textOnly = new DataIntegrityViolationException(
                    "SQLSTATE " + TransactionTypeService.SQLSTATE_FOREIGN_KEY_VIOLATION
                            + " appears in this text and in no SQLException");

            assertThat(createRefusedWith(textOnly))
                    .isSameAs(textOnly)
                    .isNotInstanceOf(RecordConflictException.class);
        }

        /**
         * The insert is flushed inside the classifying block rather than deferred to commit.
         *
         * <p>Assumptions: a plain save only registers the row with the persistence context, and the
         * statement would then reach the database at commit -- after the classifying block had already
         * returned. Both conditions the block exists to classify are raised by the statement itself, so
         * without the flush a duplicate key and an absent parent would escape unclassified and arrive as
         * the shared handler's generic integrity answer.
         *
         * <p>It takes no parameter and returns no value.
         */
        @Test
        @DisplayName("a create flushes the insert and never merely registers it")
        void aCreateFlushesTheInsert() {
            when(categories.findByIdIs(id(TYPE_CD, CAT_CD))).thenReturn(Optional.empty());
            when(categories.saveAndFlush(any(TransactionCategory.class)))
                    .thenAnswer(call -> call.getArgument(0));

            service.create(new TransactionCategoryCreateRequest(TYPE_CD, CAT_CD,
                    STORED_DESCRIPTION));

            verify(categories).saveAndFlush(any(TransactionCategory.class));
            verify(categories, never()).save(any(TransactionCategory.class));
        }

        /**
         * A duplicate found by the pre-read answers as the duplicate found by the key, and never inserts.
         *
         * <p>Assumptions: one condition must not have two answers, or the response a caller receives
         * would depend on timing -- whether it lost a race to another caller inserting the same pair. The
         * case therefore asserts the same outcome type as the constraint-detected route above, and
         * additionally that no insert was attempted, which is the only observation distinguishing the two
         * routes at all.
         *
         * <p>It takes no parameter and returns no value.
         */
        @Test
        @DisplayName("a duplicate found by the pre-read answers alike and attempts no insert")
        void aDuplicateFoundByThePreReadAnswersAlike() {
            when(categories.findByIdIs(id(TYPE_CD, CAT_CD)))
                    .thenReturn(Optional.of(storedCategory()));

            assertThatThrownBy(() -> service.create(new TransactionCategoryCreateRequest(
                    TYPE_CD, CAT_CD, STORED_DESCRIPTION)))
                    .isInstanceOf(
                            TransactionCategoryService.DuplicateTransactionCategoryException.class);

            verify(categories, never()).saveAndFlush(any(TransactionCategory.class));
            verify(categories, never()).save(any(TransactionCategory.class));
        }
    }

    /**
     * Cases holding that no character-class rule is applied to a CATEGORY description.
     *
     * <p>Assumptions: the baseline's alphanumeric edit belongs to the TYPE description and to nothing
     * else. {@code app/app-transaction-type-db2/cbl/COTRTUPC.cbl} labels the field
     * {@code 'Transaction Desc'} at its line 758, moves {@code TTUP-NEW-TTYP-TYPE-DESC} -- the type's own
     * description -- at its line 759, sets the length to 50 at its line 760, and performs
     * {@code 1230-EDIT-ALPHANUM-REQD} at its lines 761 and 762. That editor runs from line 849 to its exit
     * at line 903 and admits letters, digits and the blank alone: the literals it converts away are
     * declared at lines 232 to 237, {@code LIT-UPPER PIC X(26)}, {@code LIT-LOWER PIC X(26)} and
     * {@code LIT-NUMBERS PIC X(10)}, grouped as {@code LIT-ALL-ALPHANUM-FROM-X} at line 230 and totalling
     * sixty-two characters with no punctuation among them.
     *
     * <p>Assumptions: the seed proves that editor is never applied to a category. Row seventeen of
     * {@code app/data/ASCII/trancatg.txt} is {@code 060002Non-fraud reversal}, whose HYPHEN that class
     * refuses, while all seven descriptions of {@code app/data/ASCII/trantype.txt} -- Purchase, Payment,
     * Credit, Authorization, Refund, Reversal and Adjustment -- comply with it. The baseline therefore
     * ships a category description its type editor would reject, and extending the type rule to categories
     * here would make that seeded row unrepresentable. The row is cited and never altered.
     *
     * <p>Trade-offs: the scope of these cases is the SERVICE and no wider. The published request shapes
     * declare their own constraints at the edge where a request is bound, and those are asserted by the
     * shape-level classes that own them; what this class asserts is that the service itself imposes no
     * character rule of its own on a description it is handed. That is the layer at which the seeded row
     * has to survive, and it is the only layer this class is answerable for.
     */
    @Nested
    @DisplayName("on the absence of a category character class")
    class OnTheAbsenceOfACategoryCharacterClass {

        /**
         * The seeded hyphenated description is accepted on a create and stored with its hyphen.
         *
         * <p>Assumptions: the stored form is inspected on the entity the service handed the repository,
         * not only on what it published, because a rule applied on the way in and a rule applied on the
         * way out would look the same from the response alone.
         *
         * <p>It takes no parameter and returns no value.
         */
        @Test
        @DisplayName("a create accepts the seeded hyphenated description and stores the hyphen")
        void aCreateAcceptsTheSeededHyphenatedDescription() {
            when(categories.findByIdIs(id("06", "0002"))).thenReturn(Optional.empty());
            when(categories.saveAndFlush(any(TransactionCategory.class)))
                    .thenAnswer(call -> call.getArgument(0));

            TransactionCategoryResponse published = service.create(
                    new TransactionCategoryCreateRequest("06", "0002", HYPHENATED_DESCRIPTION));

            ArgumentCaptor<TransactionCategory> stored =
                    ArgumentCaptor.forClass(TransactionCategory.class);
            verify(categories).saveAndFlush(stored.capture());

            assertThat(stored.getValue().getDescription())
                    .as("row seventeen of trancatg.txt must be representable exactly as it is seeded")
                    .isEqualTo(HYPHENATED_DESCRIPTION)
                    .contains("-");
            assertThat(published.description()).isEqualTo(HYPHENATED_DESCRIPTION);
        }

        /**
         * A replace accepts the same description, so the rule is absent on both write paths.
         *
         * <p>Assumptions: a rule present on one write path and absent on the other would let a seeded row
         * be created and then become uneditable, which is a worse failure than a rule applied
         * consistently. Both paths are therefore asserted.
         *
         * <p>It takes no parameter and returns no value.
         */
        @Test
        @DisplayName("a replace accepts the seeded hyphenated description too")
        void aReplaceAcceptsTheSeededHyphenatedDescription() {
            TransactionCategory stored = storedCategory("06", "0002", "Fraud reversal");
            when(categories.findByIdIs(id("06", "0002"))).thenReturn(Optional.of(stored));
            when(categories.save(any(TransactionCategory.class)))
                    .thenAnswer(call -> call.getArgument(0));

            TransactionCategoryResponse published = service.replace("06", "0002",
                    new TransactionCategoryUpdateRequest(HYPHENATED_DESCRIPTION,
                            stored.getVersion()));

            assertThat(stored.getDescription()).isEqualTo(HYPHENATED_DESCRIPTION);
            assertThat(published.description()).isEqualTo(HYPHENATED_DESCRIPTION);
        }

        /**
         * None of the characters the type editor converts away is refused by this service.
         *
         * <p>Assumptions: four values are presented, each carrying one character outside the sixty-two
         * the type editor admits -- a hyphen, an ampersand, a solidus and a full stop. Presenting only the
         * hyphen would leave open the reading that the hyphen alone had been excepted, which is not the
         * property being asserted: the property is that no character rule exists here at all.
         *
         * <p>It takes no parameter and returns no value.
         */
        @Test
        @DisplayName("no character outside the type editor's sixty-two is refused here")
        void noCharacterOutsideTheTypeEditorsClassIsRefused() {
            List<String> outsideTheTypeClass = List.of(
                    HYPHENATED_DESCRIPTION, "Cash & carry", "Credit/debit adjust", "Interest accrued.");

            for (String description : outsideTheTypeClass) {
                // WHY : Assumptions: each value gets untouched doubles, because a create that succeeded
                //       on an earlier value leaves that row present and the next iteration would then
                //       be answered as a duplicate rather than reaching the description at all.
                resetDoubles();
                when(categories.findByIdIs(id("06", "0002"))).thenReturn(Optional.empty());
                when(categories.saveAndFlush(any(TransactionCategory.class)))
                        .thenAnswer(call -> call.getArgument(0));

                assertThatCode(() -> service.create(
                        new TransactionCategoryCreateRequest("06", "0002", description)))
                        .as("description %s must not be refused by this service", description)
                        .doesNotThrowAnyException();
            }
        }
    }

    /**
     * Cases pinning the field state a refusal carries as chosen from three and not from two.
     *
     * <p>Assumptions: the three states and their codes are the baseline's own, and the valid one is the
     * counter-intuitive member of the set.
     * {@code app/app-transaction-type-db2/cbl/COTRTUPC.cbl} declares
     * {@code 88 FLG-TRANFILTER-ISVALID VALUE LOW-VALUES.} at its line 95,
     * {@code 88 FLG-TRANFILTER-NOT-OK VALUE '0'.} at its line 96 and
     * {@code 88 FLG-TRANFILTER-BLANK VALUE 'B'.} at its line 97, and repeats the same triple for the
     * description group at its lines 101 to 103. Valid is the ABSENCE of a code and not a code of its own,
     * which is the single easiest thing in this area to invert.
     *
     * <p>Assumptions: the shared flag type already treats the blank state as a subset of error and
     * publishes the marker for it. These cases CONSUME that contract at the point where this service
     * selects a state; they do not re-derive it, and the flag type's own exhaustive cases live with it in
     * the shared kernel.
     */
    @Nested
    @DisplayName("on the three-state field validation")
    class OnTheThreeStateFieldValidation {

        /**
         * Raises this service's own field refusal and returns it for inspection.
         *
         * @return the caller-input refusal the browse raised for an unreadable position, never
         *     {@code null}
         */
        private ClientInputException fieldRefusal() {
            String position = sealedPosition(collectionBinding(false, null, null), "010005");
            return (ClientInputException) catchThrowable(() ->
                    service.list(browse(position, PageDirection.NEXT, null, null), sealer, SUBJECT));
        }

        /**
         * The state selected is the not-ok one, whose baseline code is the digit zero.
         *
         * <p>Assumptions: a supplied value that could not be read is not-ok rather than blank, because
         * something WAS supplied. The distinction matters on a screen, where the blank state is the one
         * that earns the marker, and it survives here as which of three states the refusal carries.
         *
         * <p>It takes no parameter and returns no value.
         */
        @Test
        @DisplayName("the refusal carries the not-ok state, whose code is the digit zero")
        void theRefusalCarriesTheNotOkState() {
            ClientInputException refusal = fieldRefusal();

            assertThat(refusal.state()).isEqualTo(FieldValidationFlag.NOT_OK);
            assertThat(refusal.state().code())
                    .as("COTRTUPC.cbl line 96 declares the not-ok code as the character zero")
                    .isEqualTo(FieldValidationFlag.NOT_OK_CODE)
                    .isEqualTo('0');
            assertThat(refusal.state().isError()).isTrue();
        }

        /**
         * The valid state, which was not selected, is the absence of a code rather than a code.
         *
         * <p>Assumptions: this is asserted precisely because it is counter-intuitive and because getting
         * it backwards is silent. A migration that gave the valid state a printable code would still
         * satisfy every assertion about the not-ok state; what would break is the baseline's own reading
         * of an untouched flag as valid, since an untouched field holds low values.
         *
         * <p>It takes no parameter and returns no value.
         */
        @Test
        @DisplayName("the valid state is low values and is the only state that is not an error")
        void theValidStateIsLowValues() {
            assertThat(FieldValidationFlag.VALID.code())
                    .as("COTRTUPC.cbl line 95 declares the valid condition as VALUE LOW-VALUES")
                    .isEqualTo(FieldValidationFlag.VALID_CODE)
                    .isEqualTo('\u0000');
            assertThat(FieldValidationFlag.VALID.isError()).isFalse();
            assertThat(FieldValidationFlag.NOT_OK.isError()).isTrue();
            assertThat(FieldValidationFlag.BLANK.isError())
                    .as("the blank state is a subset of error and not a third verdict")
                    .isTrue();
        }

        /**
         * The blank state is the one carrying the screen marker, and this refusal does not carry it.
         *
         * <p>Assumptions: the marker is the literal asterisk the baseline moves into a field left empty,
         * and it belongs to the blank state alone. A refusal for a value that was supplied but unreadable
         * must therefore carry no marker, or a screen would mark a populated field as though nothing had
         * been entered in it.
         *
         * <p>It takes no parameter and returns no value.
         */
        @Test
        @DisplayName("the blank state carries the asterisk marker and the not-ok state carries none")
        void theBlankStateCarriesTheMarker() {
            assertThat(FieldValidationFlag.BLANK.screenMarker())
                    .isEqualTo(FieldValidationFlag.BLANK_SCREEN_MARKER)
                    .isEqualTo("*");
            assertThat(FieldValidationFlag.BLANK.code()).isEqualTo('B');

            ClientInputException refusal = fieldRefusal();
            assertThat(refusal.state().requiresBlankMarker())
                    .as("a supplied but unreadable position is not an empty field")
                    .isFalse();
            assertThat(refusal.state().screenMarker())
                    .isEqualTo(FieldValidationFlag.NO_SCREEN_MARKER);
        }
    }

    /**
     * Cases holding that the earliest refusal on a path is the one delivered.
     *
     * <p>Assumptions: the baseline guards every message write with {@code IF WS-RETURN-MSG-OFF}, whose
     * condition is declared {@code VALUE SPACES} at line 168 of
     * {@code app/app-transaction-type-db2/cbl/COTRTUPC.cbl}, so a sentence already written is never
     * overwritten by a subsequent one. That program carries the guard at its line 1563 among others, and
     * {@code app/app-transaction-type-db2/cbl/COTRTLIC.cbl} carries it at exactly seventeen sites -- its
     * physical lines 1195, 1222, 1677, 1685, 1698, 1711, 1783, 1825, 1863, 1873, 1882, 1917, 1927, 1956,
     * 1984, 2011 and 2040. The migrated form of one-sentence-per-turn is one refusal per call, and its
     * observable content is which refusal a caller receives when two conditions hold at once.
     *
     * <p>Assumptions: each case below arranges TWO simultaneous conditions, because a single-condition
     * case cannot see an order at all. That is the same reasoning {@link TransactionTypeServiceTest}
     * records for the parent's classifier, cited here rather than repeated.
     */
    @Nested
    @DisplayName("on the earliest refusal winning")
    class OnTheEarliestRefusalWinning {

        /**
         * A direction with no position is refused for the direction, and the decoder is never reached.
         *
         * <p>Assumptions: the position guard runs before anything opens or decodes, so the refusal names
         * the direction component rather than the position component. Both are asserted: naming the
         * position instead would send a caller looking for a cursor fault when what it actually omitted
         * was the cursor itself.
         *
         * <p>It takes no parameter and returns no value.
         */
        @Test
        @DisplayName("a direction with no position names the direction and not the position")
        void aDirectionWithNoPositionNamesTheDirection() {
            for (PageDirection direction : PageDirection.values()) {
                resetDoubles();
                ClientInputException refusal = (ClientInputException) catchThrowable(() ->
                        service.list(browse(null, direction, null, null), sealer, SUBJECT));

                assertThat(refusal.fields())
                        .as("direction %s with no position", direction)
                        .isEqualTo(List.of(ReferencePaging.FIELD_DIRECTION));
                assertThat(refusal.getMessage())
                        .isNotEqualTo(TransactionCategoryService.MESSAGE_CURSOR_MALFORMED);
                verifyNoInteractions(categories);
            }
        }

        /**
         * A create that is both a duplicate and an orphan is answered as the duplicate.
         *
         * <p>Assumptions: the pre-read runs first, so the duplicate is detected before any statement is
         * attempted, and the foreign-key refusal the flush would have raised is never reached. That order
         * is the observable one: a caller that supplied both a pair already present and a parent that does
         * not exist is told about the pair, which is the condition it can act on without guessing.
         *
         * <p>It takes no parameter and returns no value.
         */
        @Test
        @DisplayName("a create that is both duplicate and orphan is answered as the duplicate")
        void aCreateThatIsBothDuplicateAndOrphanAnswersDuplicate() {
            when(categories.findByIdIs(id(ABSENT_TYPE_CD, CAT_CD)))
                    .thenReturn(Optional.of(storedCategory(ABSENT_TYPE_CD, CAT_CD,
                            STORED_DESCRIPTION)));

            Throwable refusal = catchThrowable(() -> service.create(
                    new TransactionCategoryCreateRequest(ABSENT_TYPE_CD, CAT_CD,
                            STORED_DESCRIPTION)));

            assertThat(refusal)
                    .isInstanceOf(
                            TransactionCategoryService.DuplicateTransactionCategoryException.class)
                    .isNotInstanceOf(
                            TransactionCategoryService.UnknownParentTransactionTypeException.class);
            verify(categories, never()).saveAndFlush(any(TransactionCategory.class));
        }

        /**
         * A stale revision is refused even when the submission would have changed nothing.
         *
         * <p>Assumptions: the revision is compared BEFORE the no-change reading, which is the order the
         * baseline classifier declares at lines 1580 to 1589 of
         * {@code app/app-transaction-type-db2/cbl/COTRTUPC.cbl}, where the arm selecting
         * {@code TTUP-SHOW-DETAILS} sits below the failure arms. A caller holding a stale revision is
         * refused even where what it submitted happens to match the store, because it read a different row
         * from the one it is now agreeing with. Reversing the two would answer that caller with a silent
         * success and leave it believing it had seen the current row.
         *
         * <p>It takes no parameter and returns no value.
         */
        @Test
        @DisplayName("a stale revision is refused ahead of an unchanged submission")
        void aStaleRevisionIsRefusedAheadOfAnUnchangedSubmission() {
            TransactionCategory stored = storedCategory();
            when(categories.findByIdIs(id(TYPE_CD, CAT_CD))).thenReturn(Optional.of(stored));

            RecordConflictException refusal = (RecordConflictException) catchThrowable(() ->
                    service.replace(TYPE_CD, CAT_CD, new TransactionCategoryUpdateRequest(
                            STORED_DESCRIPTION, stored.getVersion() + 7L)));

            assertThat(refusal)
                    .isInstanceOf(
                            TransactionCategoryService.TransactionCategoryDataChangedException.class);
            assertThat(refusal.kind()).isEqualTo(RecordConflictException.Kind.STALE_VERSION);
            assertThat(refusal.currentVersion())
                    .as("the refusal reports the revision the row now holds, so the caller need not"
                            + " read again to find it")
                    .isEqualTo(stored.getVersion());
            verify(categories, never()).save(any(TransactionCategory.class));
        }
    }

    /**
     * Cases holding that a category is the child of the referential rule and never its parent.
     *
     * <p>Assumptions: nothing in this schema references a category row, so no delete of one is ever
     * refused for referential reasons. The refusals the baseline reaches on SQLCODE -532, at physical
     * lines 1914 and 1919 of {@code app/app-transaction-type-db2/cbl/COTRTLIC.cbl}, and the unclassified
     * one at its line 1929, belong to the parent's delete path and are unreachable from here. That
     * asymmetry is the whole of the difference between this delete and the parent's, and it is asserted
     * rather than assumed because a delete that consulted a child count would look correct while doing
     * work that can never change its answer.
     */
    @Nested
    @DisplayName("on the child side of the foreign key")
    class OnTheChildSideOfTheForeignKey {

        /**
         * A category delete removes the row and consults no relationship at all.
         *
         * <p>Assumptions: the count member is the one a parent delete uses to diagnose its refusal, so
         * observing that it was never called is what shows this path treats the row as a leaf.
         *
         * <p>It takes no parameter and returns no value.
         */
        @Test
        @DisplayName("a category delete removes the row and consults no relationship")
        void aCategoryDeleteConsultsNoRelationship() {
            TransactionCategory stored = storedCategory();
            when(categories.findByIdIs(id(TYPE_CD, CAT_CD))).thenReturn(Optional.of(stored));

            service.delete(TYPE_CD, CAT_CD);

            verify(categories).delete(stored);
            verify(categories, never()).countByTypeCd(anyString());
        }

        /**
         * A delete of a row that is not there reports the miss and deletes nothing.
         *
         * <p>Assumptions: an absent row is reported rather than passed off as a successful delete, which
         * is the condition the baseline names at physical line 1864 of
         * {@code app/app-transaction-type-db2/cbl/COTRTLIC.cbl}. Treating it as success would tell a
         * caller it had removed something that was never there.
         *
         * <p>It takes no parameter and returns no value.
         */
        @Test
        @DisplayName("a delete of an absent row reports the miss and deletes nothing")
        void aDeleteOfAnAbsentRowReportsTheMiss() {
            when(categories.findByIdIs(id(ABSENT_TYPE_CD, ABSENT_CAT_CD)))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.delete(ABSENT_TYPE_CD, ABSENT_CAT_CD))
                    .isInstanceOf(
                            TransactionCategoryService.TransactionCategoryNotFoundException.class)
                    .isInstanceOf(NoSuchElementException.class)
                    .hasMessage(TransactionCategoryService.MESSAGE_CATEGORY_NOT_FOUND);

            verify(categories, never()).delete(any(TransactionCategory.class));
        }

        /**
         * The two published read names answer identically, on a hit and on a miss alike.
         *
         * <p>Assumptions: two names stand for one operation because the transformation plan names it one
         * way while the authored controller binds the other, and one implementation behind both names
         * cannot come to mean two things. The case asserts the equivalence rather than trusting the
         * delegation, since a subsequent edit could give one name its own body.
         *
         * <p>It takes no parameter and returns no value.
         */
        @Test
        @DisplayName("the two read names answer identically on a hit and on a miss")
        void theTwoReadNamesAnswerIdentically() {
            when(categories.findByIdIs(id(TYPE_CD, CAT_CD)))
                    .thenReturn(Optional.of(storedCategory()));
            when(categories.findByIdIs(id(ABSENT_TYPE_CD, ABSENT_CAT_CD)))
                    .thenReturn(Optional.empty());

            assertThat(service.read(TYPE_CD, CAT_CD)).isEqualTo(service.find(TYPE_CD, CAT_CD));

            assertThatThrownBy(() -> service.find(ABSENT_TYPE_CD, ABSENT_CAT_CD))
                    .isInstanceOf(
                            TransactionCategoryService.TransactionCategoryNotFoundException.class);
            assertThatThrownBy(() -> service.read(ABSENT_TYPE_CD, ABSENT_CAT_CD))
                    .isInstanceOf(
                            TransactionCategoryService.TransactionCategoryNotFoundException.class);
        }

        /**
         * A keyed read passes both halves through unaltered, so the leading zeros survive to the store.
         *
         * <p>Assumptions: neither half is stripped on its way into the identity, because both are
         * character columns whose declared width is part of the key. A stripped or renumbered half would
         * miss a row that is present, and the miss would be reported as a not-found rather than as the
         * key fault it actually is.
         *
         * <p>It takes no parameter and returns no value.
         */
        @Test
        @DisplayName("a keyed read passes both halves through with the leading zeros intact")
        void aKeyedReadPassesBothHalvesThrough() {
            when(categories.findByIdIs(any(TransactionCategoryId.class)))
                    .thenReturn(Optional.of(storedCategory()));

            TransactionCategoryResponse published = service.find(TYPE_CD, CAT_CD);

            ArgumentCaptor<TransactionCategoryId> looked =
                    ArgumentCaptor.forClass(TransactionCategoryId.class);
            verify(categories).findByIdIs(looked.capture());

            assertThat(looked.getValue().getTypeCd()).isEqualTo(TYPE_CD);
            assertThat(looked.getValue().getCatCd())
                    .as("CVTRA04Y.cpy line 7 declares four positions, and the seeded keys use all four")
                    .isEqualTo(CAT_CD);
            assertThat(published.catCd())
                    .as("the published half keeps its leading zeros too, since only trailing blanks are"
                            + " padding")
                    .isEqualTo(CAT_CD);
        }

        /**
         * The refusal sentence is carried across character for character, including its three full stops.
         *
         * <p>Assumptions: transformation rule T8 makes a user-visible string a contract, so the trailing
         * ellipsis is part of the value and not decoration. It is asserted separately from the paths that
         * raise it because a path can be right while the wording drifts.
         *
         * <p>It takes no parameter and returns no value.
         */
        @Test
        @DisplayName("the not-found sentence is verbatim, ellipsis included")
        void theNotFoundSentenceIsVerbatim() {
            assertThat(TransactionCategoryService.MESSAGE_CATEGORY_NOT_FOUND)
                    .isEqualTo("Transaction category NOT found...")
                    .endsWith("...");
        }
    }
}
