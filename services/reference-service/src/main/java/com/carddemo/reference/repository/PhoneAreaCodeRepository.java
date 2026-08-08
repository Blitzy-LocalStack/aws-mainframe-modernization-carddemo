package com.carddemo.reference.repository;

import com.carddemo.reference.domain.UsPhoneAreaCode;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Membership predicates over {@code reference.us_phone_area_codes}, each one scoped to a named
 * baseline list rather than to the table as a whole.
 *
 * <h2>Purpose</h2>
 *
 * <p>This interface is the migrated form of a condition-name test. The reference baseline holds its
 * North American area codes as {@code 88}-level literal lists over a working-storage field rather
 * than as records, so the only question it can put to a candidate value is whether that value
 * appears in one named list. Every method declared below puts exactly that question to exactly one
 * named list, and the domain each one tests is stated in its own summary sentence. Nothing here
 * interprets a result, and no rule acts on one: the rules live in
 * {@code com.carddemo.reference.service} and the transfer-object mapping in
 * {@code com.carddemo.reference.mapper}.</p>
 *
 * <p>The rulings that bind every query in this package -- what may bound a walk, why a window is
 * positioned by key and never by ordinal, which engine owns the layering boundary, and that the JDBC
 * search path is pinned so no query names a schema -- are settled once in this package's descriptor,
 * {@code com.carddemo.reference.repository}, and are cited from here rather than restated.</p>
 *
 * <p>On exceptions: no method below declares one, and none carries a {@code @throws} at-clause,
 * because a derived query raises nothing of its own. A driver, connection or mapping failure
 * surfaces as the framework's data-access exception and is rendered by the shared
 * {@code com.carddemo.common.error.GlobalExceptionHandler} that this module inherits and does not
 * duplicate. The inapplicability is written down rather than left silent, because the project
 * Explainability rule lists a docstring that omits an element among its forbidden patterns, so a
 * reader has to be able to tell a declared inapplicability from an oversight; this package's
 * descriptor states the same thing the same way.</p>
 *
 * <h2>Baseline lineage: one field, three lists, two classes</h2>
 *
 * <p>{@code app/cpy/CSLKPCDY.cpy} is the sole source and is read as reference material only. Its L24
 * declares the value being validated as {@code 01 WS-US-PHONE-AREA-CODE-TO-EDIT PIC XXX}, three
 * alphanumeric positions, and three condition names sit over that one field. The literal counts
 * below were computed from the copybook rather than estimated:</p>
 *
 * <pre>
 * CSLKPCDY.cpy  condition name              literals  span         code_class
 * L24           WS-US-PHONE-AREA-CODE-TO-EDIT PIC XXX  -- the one field all three test
 * L30           VALID-PHONE-AREA-CODE            490   L30-L520     either class
 * L521          VALID-GENERAL-PURP-CODE          410   L521-L930    general purpose
 * L931          VALID-EASY-RECOG-AREA-CODE        80   L931-L1010   easily recognisable
 *                                             ------
 *                              410 + 80 = 490, sharing no member and leaving none over
 * </pre>
 *
 * <p>Every one of the three lists holds distinct literals, the two narrower lists intersect in
 * nothing, and their union equals the broad list as a set. That arithmetic is what makes a single
 * table carrying one classification column both sufficient and exact, and it is the reason a caller
 * can treat the two classes as a partition of the whole table rather than as two overlapping
 * filters.</p>
 *
 * <h2>The domain a caller must ask for, and why it is not the broad list</h2>
 *
 * <p>The copybook is copied by exactly one program, and that program tests one of the three lists.
 * {@code app/cbl/COACTUPC.cbl} L2297 moves the trimmed candidate into the L24 field and its L2298
 * reads {@code IF VALID-GENERAL-PURP-CODE}, refusing anything else with the message
 * {@code ': Not valid North America general purpose area code'}. The broad 490-literal list and the
 * 80-literal easily-recognisable list are declared by the copybook and tested by no program at all.
 * So the domain that decides whether an address is accepted is the 410-member general-purpose class,
 * and the two checks this interface exposes are deliberately not interchangeable:</p>
 *
 * <dl>
 *   <dt>Class-scoped, the 410-member general-purpose list</dt>
 *   <dd>{@link #existsByAreaCodeAndCodeClass(String, String)}, called with
 *       {@link UsPhoneAreaCode#CODE_CLASS_GENERAL_PURPOSE}. This is the check that reproduces
 *       L2298, and it is the one account-service's address validation needs: that context owns
 *       neither this table nor its seed, and it reaches this data through this service's published
 *       contract because the shared architecture test forbids it from importing an entity owned by
 *       another root.</dd>
 *
 *   <dt>Union-scoped, the 490-member broad list</dt>
 *   <dd>{@code existsById(String)}, inherited from {@code JpaRepository} and deliberately
 *       <b>not</b> redeclared here. The identity of this entity is the area code itself, so the
 *       inherited primary-key probe already answers the broad question exactly, and a second method
 *       spelling the same query would give a caller two names for one predicate with nothing to
 *       choose between them. It is named here so that a reader looking for the broad check finds it
 *       rather than concluding it is missing.</dd>
 * </dl>
 *
 * <p>Assumptions: substituting one of those two checks for the other is a functional-parity fault
 * and not a stylistic one. Answering the broad question where the baseline asks the narrow one
 * accepts all 80 easily-recognisable codes that {@code COACTUPC.cbl} L2298 refuses, and it does so
 * without failing anything: the row exists, the probe is true, and an address the baseline declines
 * is admitted. Answering the narrow question where a caller genuinely wants the broad one refuses
 * those same 80 codes just as quietly. Neither direction reports itself, which is why the domain is
 * named in every summary sentence below and why the two methods are not variants of one.</p>
 *
 * <p>Assumptions: no method here departs from a baseline behaviour. The two questions the baseline
 * cannot put at all -- how many codes a class holds, and which codes they are -- are additions to the
 * surface rather than changes to an answer, because a condition name can be evaluated but neither
 * counted nor enumerated. Where this bounded context does depart from the baseline deliberately, the
 * departure is registered in {@code docs/architecture/cobol-to-service-traceability.md}, which is
 * maintained elsewhere and referenced from here so that the register keeps a single owner.</p>
 *
 * <h2>Why this interface sits beside {@code UsPhoneAreaCodeRepository}</h2>
 *
 * <p>Two interfaces address this one table, and they divide by question rather than overlap.
 * {@code UsPhoneAreaCodeRepository} is the listing surface behind the published lookup contract: six
 * bounded ordered walks and one keyed read, all of which return rows. This interface is the
 * membership surface: it answers whether a code belongs to a named list, and it is where the
 * class-scoped predicate lives, which no method on that interface expresses -- it declares no
 * existence predicate of any kind.</p>
 *
 * <p>Alternatives Considered: adding the class-scoped predicate to that listing interface, which
 * would leave one interface per table. It was rejected because the two surfaces have different
 * callers and different shapes. The listing methods exist to fill a screen and every one of them
 * takes a bound and returns rows; the membership methods exist to decide a single validation and
 * return a truth value or a census. Merging them would put a validation predicate among the paging
 * methods, where the {@code Limit} argument that every neighbour takes reads as an omission on the
 * one method that does not need it. The compromise accepted is that a reader meets two interfaces
 * over one entity, which is why each names the other and says what it is for; the persistence
 * provider derives one implementation per interface and the two beans are independent, so nothing
 * is ambiguous at injection.</p>
 *
 * <h2>Decisions</h2>
 *
 * <p>Refactoring Rationale: the structure being replaced is a set of condition names compiled into a
 * program, and it cannot carry across unchanged. A condition name yields a truth value and never the
 * set behind it, so the baseline can say whether one candidate is acceptable but cannot enumerate
 * what is, count what is, or let another component ask. The literals also reach their single
 * consumer through a copy directive, so the allow-list is part of that program's compiled image.
 * Expressing the same three lists as one table with a classification column answers all four
 * questions from data. What deliberately does not change is the set itself, or which class of it is
 * accepted.</p>
 *
 * <p>Alternatives Considered: two tables, one per narrower list, with broad membership answered by a
 * union across both. Rejected because the copybook keeps all three lists on the single L24 field, so
 * two tables would split one key space and turn the broad check from a primary-key probe into a
 * two-relation union, while additionally admitting a code present in both tables or in neither --
 * states the copybook cannot express. The classification column was settled in
 * {@code db/migration/V1__reference.sql} for these reasons and this interface reads that shape; it
 * does not re-decide it.</p>
 *
 * <p>Trade-offs: because the class is a parameter, a caller has to know which list it is validating
 * against, and a caller that does not think about it cannot get an answer at all. That cost is
 * accepted deliberately. The alternative -- one existence check that silently picked a class inside
 * the data layer -- would hide a parity-relevant decision in the layer furthest from the rule that
 * depends on it, and would make the choice invisible at the call site where it actually matters.</p>
 *
 * <p>Assumptions: the classification is passed as one of the two constants the entity declares,
 * {@link UsPhoneAreaCode#CODE_CLASS_GENERAL_PURPOSE} or
 * {@link UsPhoneAreaCode#CODE_CLASS_EASILY_RECOGNISABLE}. No method here spells either stored letter
 * as a literal, because a bare single character at a call site cannot say which of the copybook's
 * lists was meant, and the {@code ck_us_phone_area_codes_class} check constraint of
 * {@code db/migration/V1__reference.sql} admits exactly those two values -- so a third spelling
 * matches no row and reports nothing.</p>
 *
 * <p>Assumptions: the identity is a three-character {@code String} and never a number, because L24
 * declares an alphanumeric field of three positions and every literal on all three lists is quoted
 * and compared as characters. The stored column is a blank-padded character type, which is what lets
 * a probe arriving from a declared-width source match its row: the comparison ignores trailing
 * spaces, where a varying-width column would return no row and raise nothing either. A numeric
 * identity would additionally discard a leading zero.</p>
 *
 * <p>Assumptions: the rows these predicates read are seeded by
 * {@code db/migration/V2__seed_reference.sql}, which loads exactly the 490 codes the copybook
 * declares -- 410 of the general-purpose class and 80 of the easily-recognisable class -- and does
 * so idempotently, taking no action on a key that is already present. Nothing in this module writes
 * this table, so a code the seed omits does not fail here at all; it surfaces as an address declined
 * during account maintenance in a different service, which is the one behaviour of this table that
 * is easy to mistake for a defect somewhere else.</p>
 */
@Repository
public interface PhoneAreaCodeRepository extends JpaRepository<UsPhoneAreaCode, String> {

    // Assumptions: the classification is a parameter rather than a constant folded into the query,
    //     so the domain being validated stays visible at the call site. This is the migrated form of
    //     the single condition-name test at app/cbl/COACTUPC.cbl L2298, and it is the only method in
    //     this package that can reproduce it: the broad probe inherited from the framework tests all
    //     490 codes, which is 80 more than that line accepts.
    /**
     * Reports whether a code belongs to one named baseline list, testing that list alone and not the
     * broad 490-code union.
     *
     * <p>Called with {@link UsPhoneAreaCode#CODE_CLASS_GENERAL_PURPOSE} this reproduces
     * {@code app/cbl/COACTUPC.cbl} L2298 exactly, which is the check an address validation needs.
     * Called with {@link UsPhoneAreaCode#CODE_CLASS_EASILY_RECOGNISABLE} it answers the complementary
     * question, which the baseline declares at {@code app/cpy/CSLKPCDY.cpy} L931 and no program
     * tests. The broad question is answered by the inherited {@code existsById(String)} instead.</p>
     *
     * @param areaCode the {@code String} three-character candidate code to test, compared against
     *     the blank-padded {@code area_cd} column so that a value carrying trailing spaces still
     *     matches its row
     * @param codeClass the {@code String} one-character baseline list to confine the test to, which
     *     is {@link UsPhoneAreaCode#CODE_CLASS_GENERAL_PURPOSE} for the 410-literal general-purpose
     *     list at {@code app/cpy/CSLKPCDY.cpy} L521 or
     *     {@link UsPhoneAreaCode#CODE_CLASS_EASILY_RECOGNISABLE} for the 80-literal
     *     easily-recognisable list at L931
     * @return the {@code boolean} true when a row carries that code with that classification, and
     *     false both when no row carries the code at all and when a row carries it under the other
     *     classification
     */
    boolean existsByAreaCodeAndCodeClass(String areaCode, String codeClass);

    // Assumptions: a census per class is declared because the inherited count answers only for the
    //     whole table, and the property that makes one classification column correct is that the two
    //     classes partition it. Two class counts summing to the table count is that property stated
    //     as data, so a seed that loaded a row under the wrong classification is detectable without
    //     reading 490 rows to find it.
    /**
     * Counts the rows of one named baseline list, and of that list only.
     *
     * <p>On seeded data the two classes return 410 and 80 and the inherited {@code count()} returns
     * 490, which is the copybook's own arithmetic carried into the schema. A caller verifying a load
     * asserts all three rather than any one of them, because a code misclassified by the seed leaves
     * the table total unchanged and moves only the two class counts.</p>
     *
     * @param codeClass the {@code String} one-character baseline list to count, which is
     *     {@link UsPhoneAreaCode#CODE_CLASS_GENERAL_PURPOSE} or
     *     {@link UsPhoneAreaCode#CODE_CLASS_EASILY_RECOGNISABLE}
     * @return the {@code long} number of rows carrying that classification, and zero when the
     *     classification matches no row
     */
    long countByCodeClass(String codeClass);

    // Assumptions: the ordering is part of this method rather than an argument to it, for the reason
    //     this package's descriptor gives -- a caller-supplied ordering could be one under which the
    //     result is a different sequence, which would make the guarantee unverifiable. Ascending by
    //     the code column is chosen because that is the order the copybook lists the literals in and
    //     the order the seed inserts them, so an enumerated list reads the same as its source.
    /**
     * Enumerates one named baseline list in full, in ascending code order, and never the broad
     * union.
     *
     * <p>This is the capability the baseline has no form of: a condition name yields a truth value
     * and cannot be enumerated, so the accepted set could not previously be published, compared or
     * reviewed without reading the copybook. The result is not windowed, because a class holds at
     * most the 410 rows of {@code app/cpy/CSLKPCDY.cpy} L521 and the whole list is what a caller
     * wanting to publish or verify it needs; a caller that wants a screen-sized window uses the
     * bounded ordered walks on {@code UsPhoneAreaCodeRepository} instead.</p>
     *
     * @param codeClass the {@code String} one-character baseline list to enumerate, which is
     *     {@link UsPhoneAreaCode#CODE_CLASS_GENERAL_PURPOSE} or
     *     {@link UsPhoneAreaCode#CODE_CLASS_EASILY_RECOGNISABLE}
     * @return the {@code List} of every row carrying that classification, ordered ascending by the
     *     {@code area_cd} column, and empty when the classification matches no row
     */
    List<UsPhoneAreaCode> findByCodeClassOrderByAreaCodeAsc(String codeClass);
}
