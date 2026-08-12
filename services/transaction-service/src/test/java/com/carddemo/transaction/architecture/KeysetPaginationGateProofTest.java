package com.carddemo.transaction.architecture;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.equivalentTo;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.carddemo.transaction.repository.DailyTransactionRepository;
import com.carddemo.transaction.repository.TransactionCategoryBalanceRepository;
import com.carddemo.transaction.repository.TransactionRejectRepository;
import com.carddemo.transaction.repository.TransactionRepository;
import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaMember;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.EvaluationResult;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.Query;

/**
 * Proves that the keyset-only repository contract of this bounded context actually rejects an
 * offset-shaped declaration, and applies the same predicate to the real repository package so the
 * proof is a live guardrail rather than a demonstration.
 *
 * <p>Purpose: a gate that cannot fail is not a gate. This class owns the predicate that decides
 * whether a declared repository member pages by key or by offset, evaluates it against synthetic
 * subjects authored to be refused, evaluates it against a synthetic subject authored to be
 * admitted, and finally evaluates it against the declared members of
 * {@code com.carddemo.transaction.repository}. The first evaluation shows the predicate bites, the
 * second shows it discriminates rather than refusing everything, and the third makes an offset
 * query added to a production repository fail this test.
 *
 * <p>This file exists because of Rule 1, the one user-specified rule of this migration. Nothing in
 * the migration requirements asks for a proof that a gate can fail; Rule 1's validation gate does,
 * because a rule whose predicate never fires satisfies the letter of the obligation -- a gate is
 * present, and it reports a pass -- while defeating what the obligation is for. Rule 1 binds this
 * file on three independent grounds, and no one of them depends on the other two. Its own scope
 * clause attaches the documentation obligation to every function, class and module entry point and
 * exempts no test anywhere in its text. The Checkstyle documentation gate sweeps
 * {@code src/test/java} because {@code services/pom.xml} sets {@code includeTestSourceDirectory},
 * and {@code config/checkstyle/suppressions.xml} suppresses only generated sources and test
 * resource fixtures, so it declines to exempt this tree on the stated ground that tests are in
 * scope. And {@code tests/README.md} section 12, whose heading is at line 516 and whose blockquote
 * runs from line 544 to line 549, makes a docstring stating purpose, parameters, returns and
 * exceptions plus a named-category why-comment a hard review gate for every new test.
 *
 * <p>Refactoring Rationale: offset pagination is refused for a behavioural reason and not as a
 * preference, and the reason is that the reference browse state is already a keyset cursor.
 * {@code app/cbl/COCRDLIC.cbl} lines 229 to 245 carry that cursor across a screen turn in the
 * communication area: line 229 opens the structure, lines 230 to 232 hold the last displayed key,
 * lines 233 to 235 hold the first displayed key, line 237 holds the screen ordinal, line 239 holds
 * the last-page-displayed flag, and line 242 holds the next-page indicator whose line 244
 * condition name is set to a literal when a further page exists. Nothing in that structure counts
 * rows already consumed. {@code app/cbl/COTRN00C.cbl} drives the four browse verbs whose bodies
 * span lines 589 to 694 -- start-browse at line 593, forward read at line 626, backward read at
 * line 660 and end-browse at line 694 -- and discovers the indicator by reading one row more than
 * the screen holds: its loop at line 297 stops when the index reaches 11 and its eleventh read at
 * line 308 sets the indicator from that read's outcome alone at lines 309 to 313. Under concurrent
 * insertion an offset skips rows it never showed and repeats rows it already showed, because the
 * count of rows preceding a position changes when a row lands before it, whereas a key already
 * returned keeps its place in the ordered set. Substituting an offset would therefore change
 * observable behaviour rather than only the implementation, which is why the migrated queries keep
 * the strict key comparison and the size-plus-one probe that is exactly how the reference answers
 * the same question.
 *
 * <p>Assumptions: the catching mechanism this proof certifies is the keyset-only repository
 * contract declared by {@code com.carddemo.transaction.repository.package-info}. That charter is
 * what states the invariant -- the cursor is the scalar {@code tranId}, availability is discovered
 * by fetching one row beyond the page size, the comparisons are strictly greater-than forward with
 * an ascending order and strictly less-than backward with a descending order, the envelope is
 * {@code com.carddemo.common.web.PageResponse} and is never re-declared, and no offset,
 * page-number or total-count vocabulary appears anywhere. This class does not restate that
 * contract as a second authority; it makes the charter's last clause decidable by a build.
 *
 * <p>Alternatives Considered: adding this predicate to {@code TransactionLayeringRulesTest} as a
 * fourth gate family. Rejected because that class is specified to own exactly the three families
 * A1, A2 and A3, and a fourth family smuggled in beside them would put two unrelated concerns
 * under one description: a continuous-integration failure would then name a layering gate without
 * saying whether the boundary that broke was the domain isolation, the ownership arrow, the money
 * path or the paging shape. Keeping the predicate here also keeps it where the module that owns the
 * boundary can see it, because not every bounded context in this migration browses and the shared
 * kernel's rules class is a closed set that admits no additional rule class.
 *
 * <p>Assumptions: the offset-shaped subjects are nested types of this test rather than production
 * types, and that placement is load-bearing rather than tidy. The third assertion below evaluates
 * the predicate over the compiled production repository package; a subject authored to be refused
 * would be picked up by that evaluation and would fail the very build this proof exists to
 * protect. No offset-shaped member is added to a production repository, not even briefly.
 *
 * <p>Assumptions: the predicate inspects DECLARED members only, and this is the least obvious
 * constraint in the file. All four production repositories extend {@code JpaRepository}, which
 * reaches {@code PagingAndSortingRepository}, and that supertype DECLARES a find-all method taking
 * {@code org.springframework.data.domain.Pageable} and returning
 * {@code org.springframework.data.domain.Page}; {@code CrudRepository} likewise declares a count
 * member. A predicate that resolved inherited members would therefore report every repository in
 * the package as an offset violator and redden the build permanently, over a surface no repository
 * in this module declares or calls. ArchUnit's declared code-unit accessor is used throughout and
 * the hierarchy-resolving accessors are not, which is what the third assertion verifies
 * empirically rather than by inspection.
 *
 * <p>Alternatives Considered: deciding the same question by searching the repository sources as
 * text, or by testing a package name with a substring match. Both were rejected on the same
 * defect: neither can tell a declared member from an inherited one, so both would reproduce the
 * false positive above, and a substring match over names additionally refuses legitimate ones --
 * the name-token test here is bounded by a camel-case word boundary for exactly that reason.
 * ArchUnit's member and annotation model is used instead, including for annotation values, and
 * ownership is decided by class identity or by ArchUnit's package matcher rather than by string
 * containment.
 *
 * <p>Trade-offs: the synthetic subjects ship with the module permanently, and one of them carries
 * a query annotation naming a clause this context forbids. Accepted, because a proof of a
 * prohibition needs an instance of the prohibited construct, and there is no way to show that a
 * predicate discriminates rather than merely being present without holding both a subject it
 * refuses and a subject it admits. The cost is bounded by placement: the compiled production graph
 * these gates evaluate excludes every test source, and {@code TransactionLayeringRulesTest}
 * additionally removes this whole package from its analysed graph, so a synthetic subject cannot
 * reach a gate it was not written for.
 */
class KeysetPaginationGateProofTest {

    /**
     * The package root of the bounded context this proof belongs to.
     */
    private static final String MODULE_ROOT = "com.carddemo.transaction";

    /**
     * The ArchUnit package identifier of the production repository package under proof.
     */
    private static final String REPOSITORY_PACKAGE = MODULE_ROOT + ".repository";

    /**
     * The ArchUnit package identifier matching that package and nothing above it.
     */
    private static final String REPOSITORY_PACKAGE_IDENTIFIER = REPOSITORY_PACKAGE + "..";

    /**
     * The cursor-request abstraction refused in any declared parameter position.
     *
     * <p>Assumptions: this type is named rather than described because the row skip is emitted by
     * the abstraction and not by the naming. A method that accepts it yields a statement carrying
     * an offset clause whatever the method is called, so the type is the decidable thing.</p>
     */
    private static final String REFUSED_CURSOR_REQUEST_TYPE_NAME = Pageable.class.getName();

    /**
     * The paged result abstractions refused as a declared return type.
     *
     * <p>Assumptions: both spellings are named. The paged form additionally issues a counting
     * query for a total this context has no source for, and the sliced form is the same request
     * shape with that count omitted, so admitting the sliced form alone would readmit the offset
     * behind a narrower result type.</p>
     */
    private static final List<String> REFUSED_PAGED_RESULT_TYPE_NAMES =
            List.of(Page.class.getName(), Slice.class.getName());

    /**
     * The row cap this context uses legitimately, which no set in this class refuses.
     *
     * <p>Assumptions: this constant exists to be asserted absent from every refused set rather
     * than to be matched against a member. The four production repositories cap a keyset page at
     * page size plus one with this type, so a predicate that refused it would refuse the correct
     * implementation and the surplus row that answers forward availability with it.</p>
     */
    private static final String PERMITTED_ROW_CAP_TYPE_NAME = Limit.class.getName();

    /**
     * The offset, page-number and total-count tokens refused inside a declared member name.
     *
     * <p>Assumptions: every entry is a compound or a whole word that cannot occur in a legitimate
     * derived query name over this record, which is checkable rather than asserted. The
     * transaction record is declared at {@code app/cpy/CVTRA05Y.cpy} lines 5 to 18, and none of
     * its thirteen fields -- identifier, type code, category code, source, description, amount,
     * merchant identifier, merchant name, merchant city, merchant postal code, card number,
     * originating timestamp and processing timestamp -- maps to a property whose name contains any
     * of these. A bare page or count fragment is deliberately absent from this list: a page
     * fragment occurs inside the word paging, which a compliant member may legitimately use, and a
     * count fragment opens the word country.</p>
     */
    private static final List<String> REFUSED_MEMBER_NAME_TOKENS = List.of(
            "offset", "skip", "firstresult", "pagenumber", "pagenum", "pageno", "pageindex",
            "totalcount", "totalelements", "totalpages");

    /**
     * The subject keyword that opens a count projection in a derived query name.
     *
     * <p>Assumptions: this is matched at a camel-case word boundary and not as a substring,
     * because the framework reads a leading count as the projection subject only when the next
     * character opens a new word. Matching it as a substring would refuse a member named for a
     * country, and matching it after lowercasing would destroy the boundary that separates the
     * two.</p>
     */
    private static final String REFUSED_COUNT_PROJECTION_KEYWORD = "count";

    /**
     * The offset and first-result fragments refused inside a written query statement.
     *
     * <p>Assumptions: a row cap is not on this list and a row skip is. A statement may bound how
     * many rows it returns, which is the size-plus-one probe expressed in a written query; what it
     * may not do is discard a counted prefix of the ordered set, which is what these fragments
     * spell in the query language and in its provider interface respectively.</p>
     */
    private static final List<String> REFUSED_QUERY_SYNTAX_TOKENS =
            List.of("offset", "firstresult", "first_result");

    /**
     * The four production interfaces the third assertion must be seen to cover.
     *
     * <p>Assumptions: these are named by class literal rather than by string, so a rename that
     * left this list stale would not compile instead of silently narrowing the guardrail. The
     * third assertion evaluates every class residing in the repository package and additionally
     * asserts that these four are among the subjects, because an evaluation that quietly stopped
     * reaching them would report the same pass as one that examined them.</p>
     */
    private static final List<Class<?>> COVERED_PRODUCTION_REPOSITORIES = List.of(
            TransactionRepository.class,
            DailyTransactionRepository.class,
            TransactionCategoryBalanceRepository.class,
            TransactionRejectRepository.class);

    /**
     * The one predicate this class owns, paired below with three different subject selections.
     *
     * <p>Trade-offs: the predicate is declared once and reused, rather than written once for the
     * synthetic subjects and once for the production package. Two declarations of one invariant
     * can drift apart, and the pair that drifts is the pair where the proof keeps passing while
     * the guardrail has changed; the cost of sharing it is that the subject selection has to be
     * supplied per evaluation, which the helpers below do explicitly.</p>
     */
    private static final ArchCondition<JavaClass> KEYSET_ONLY_DECLARED_MEMBERS =
            new KeysetOnlyDeclaredMemberCondition();

    /**
     * The same predicate paired with a selection naming the production repository package.
     *
     * <p>Assumptions: the selection is expressed with ArchUnit's package matcher, which tests a
     * package identifier against a class's own package rather than testing one string for
     * containment in another. A containment test would additionally select a package whose name
     * merely begins with the same characters, which is how an unrelated tree gets drawn into a
     * gate that reads as though it were scoped.</p>
     */
    private static final ArchRule PRODUCTION_REPOSITORIES_PAGE_BY_KEY = classes()
            .that(resideInAPackage(REPOSITORY_PACKAGE_IDENTIFIER))
            .should(KEYSET_ONLY_DECLARED_MEMBERS)
            .as("every class declared in " + REPOSITORY_PACKAGE_IDENTIFIER
                    + " pages by key and never by offset");

    /**
     * Proves the predicate refuses a declared member that accepts the cursor-request abstraction.
     *
     * <p>Assumptions: the parameter position is where the offset arrives from outside, so a
     * predicate that inspected only return types would admit a member handed a page number by its
     * caller and hand the row skip straight to the provider.
     */
    @Test
    @DisplayName("proof: a declared parameter of the paging request type is refused")
    void refusesADeclaredPagingRequestParameter() {
        // Assumptions: an ArchUnit rule reports a violation by throwing, so the refusal is
        //     asserted as a thrown error rather than as a returned flag.
        // Alternatives Considered: asserting only that something was thrown. Rejected because the
        //     engine also throws when a selection matched no subject at all, which is the one way
        //     this assertion could pass while proving nothing; asserting that the message names
        //     the offending member and the refused type separates a genuine refusal from an empty
        //     evaluation.
        AssertionError refusal = assertThrows(
                AssertionError.class,
                () -> checkAgainst(PagingRequestParameterSubject.class),
                "The predicate admitted a declared member taking " + REFUSED_CURSOR_REQUEST_TYPE_NAME
                        + ", so it is not gating the abstraction that emits the row skip and the"
                        + " guardrail assertion in this class proves nothing.");

        assertThat(refusal.getMessage())
                .withFailMessage(
                        "The predicate refused the subject but its report named neither the"
                                + " offending member nor %s, so the refusal cannot be attributed to"
                                + " the parameter position and may be an empty evaluation instead.",
                        REFUSED_CURSOR_REQUEST_TYPE_NAME)
                .contains("findEveryRow", REFUSED_CURSOR_REQUEST_TYPE_NAME);
    }

    /**
     * Proves the predicate refuses a declared member that returns the paged result abstraction.
     *
     * <p>Assumptions: the paged result is refused on its own and not only alongside the request
     * type, because a member can return it while taking a key. Such a member still issues the
     * counting query the paged result carries, and this context has no source for that total.
     */
    @Test
    @DisplayName("proof: a declared return type of the paged result type is refused")
    void refusesADeclaredPagedResultReturnType() {
        // Assumptions: this shape is asserted separately from the parameter shape above so that a
        //     predicate which had lost one test of the four still fails here. A single subject
        //     carrying every refused shape at once would report a refusal from whichever test
        //     survived and conceal the three that had gone.
        AssertionError refusal = assertThrows(
                AssertionError.class,
                () -> checkAgainst(PagedResultReturnTypeSubject.class),
                "The predicate admitted a declared member returning " + Page.class.getName()
                        + ", so a paged result can still leave this context's repository layer.");

        assertThat(refusal.getMessage())
                .withFailMessage(
                        "The predicate refused the subject but its report named neither the"
                                + " offending member nor %s, so the refusal cannot be attributed to"
                                + " the return type.",
                        Page.class.getName())
                .contains("findWholeSet", Page.class.getName());
    }

    /**
     * Proves the predicate refuses a declared member that returns the sliced result abstraction.
     *
     * <p>Assumptions: the sliced result is the paged request shape with the total omitted, so a
     * predicate naming only the paged form would readmit the offset behind the narrower result
     * type while reporting a pass on the wider one.
     */
    @Test
    @DisplayName("proof: a declared return type of the sliced result type is refused")
    void refusesADeclaredSlicedResultReturnType() {
        // Assumptions: the sliced form is proved separately from the paged form because the two
        //     are distinct types rather than one type under two names, so a set naming only the
        //     first would leave the second entirely ungated.
        AssertionError refusal = assertThrows(
                AssertionError.class,
                () -> checkAgainst(SlicedResultReturnTypeSubject.class),
                "The predicate admitted a declared member returning " + Slice.class.getName()
                        + ", so the offset can re-enter through the result type that omits the"
                        + " total rather than the one that carries it.");

        assertThat(refusal.getMessage())
                .withFailMessage(
                        "The predicate refused the subject but its report named neither the"
                                + " offending member nor %s.",
                        Slice.class.getName())
                .contains("findPartialSet", Slice.class.getName());
    }

    /**
     * Proves the predicate refuses a declared member whose own name carries offset vocabulary.
     *
     * <p>Assumptions: a member can express an offset without naming any framework type at all, by
     * taking a plain whole number and skipping that many rows. The type tests above are blind to
     * that member, which is why the charter's prohibition covers vocabulary and not only types.
     */
    @Test
    @DisplayName("proof: a declared member name carrying offset vocabulary is refused")
    void refusesADeclaredMemberNameCarryingOffsetVocabulary() {
        // Assumptions: the subject's parameter is a primitive whole number and its return type is
        //     an ordinary list, so nothing about it is refusable except the name. That isolation
        //     is what makes this assertion evidence about the name test rather than about the two
        //     type tests firing again.
        AssertionError refusal = assertThrows(
                AssertionError.class,
                () -> checkAgainst(OffsetNamedMemberSubject.class),
                "The predicate admitted a declared member whose name carries offset vocabulary, so"
                        + " a hand-rolled row skip that names no framework type passes the gate.");

        assertThat(refusal.getMessage())
                .withFailMessage(
                        "The predicate refused the subject but its report named neither the"
                                + " offending member nor the token that matched, so a reader cannot"
                                + " tell which part of the name has to change.")
                .contains("findRowsAtOffset", "offset");
    }

    /**
     * Proves the predicate refuses a declared member whose query annotation carries an offset.
     *
     * <p>Assumptions: a written query is the remaining way in, and it is the way a type test and a
     * name test both miss. A member with a compliant name, a plain list return type and no
     * framework parameter can still carry a statement that discards a counted prefix of the
     * ordered set, and the statement is only visible in the annotation value.
     */
    @Test
    @DisplayName("proof: a declared query annotation carrying an offset clause is refused")
    void refusesADeclaredQueryAnnotationCarryingAnOffsetClause() {
        // Assumptions: the annotation value is read through ArchUnit's annotation model rather
        //     than by reading the source as text, so the statement is decided from the compiled
        //     member that will actually run.
        // Alternatives Considered: matching the annotation by its type before reading its value.
        //     Rejected because more than one annotation in this framework carries a statement, and
        //     naming one type would leave a statement carried by the other unread; every
        //     string-valued property of every annotation on a declared member is scanned instead.
        AssertionError refusal = assertThrows(
                AssertionError.class,
                () -> checkAgainst(OffsetBearingQueryAnnotationSubject.class),
                "The predicate admitted a declared member whose written query discards a counted"
                        + " prefix of the ordered set, so the clause the charter forbids can be"
                        + " carried in an annotation the gate never reads.");

        assertThat(refusal.getMessage())
                .withFailMessage(
                        "The predicate refused the subject but its report named neither the"
                                + " offending member nor the fragment that matched.")
                .contains("findFollowingRows", "offset");
    }

    /**
     * Proves the predicate admits a keyset-shaped subject, so its refusals discriminate.
     *
     * <p>Assumptions: without this half a predicate that had degenerated into one refusing
     * everything would satisfy all five refusal assertions above while gating nothing, and an
     * over-matching predicate is the likelier of the two accidents because a widened type or token
     * test fires on more subjects rather than fewer.
     *
     * <p>Assumptions: the admitted subject is shaped the way the charter states -- it seeks
     * strictly past the last key seen and orders ascending, seeks strictly before the first key
     * seen and orders descending, caps both with the row cap type at page size plus one, and
     * carries a written query that orders without discarding a prefix. Admitting it is what shows
     * the row cap is permitted rather than merely unmentioned.
     */
    @Test
    @DisplayName("proof: a keyset-shaped declaration capped by the row cap type is admitted")
    void admitsAKeysetShapedDeclaration() {
        EvaluationResult evaluation = evaluateAgainst(KeysetShapedSubject.class);

        assertThat(evaluation.getFailureReport().getDetails())
                .withFailMessage(
                        "The predicate refused a subject that seeks strictly past the last key seen"
                                + " ascending, seeks strictly before the first key seen descending"
                                + " and caps both with %s. It is over-matching, so every refusal"
                                + " asserted in this class passes for a reason other than the"
                                + " offset shape it names.",
                        PERMITTED_ROW_CAP_TYPE_NAME)
                .isEmpty();
    }

    /**
     * Guards the row cap type and its name against being drawn into any refused set.
     *
     * <p>Assumptions: the admission proof above covers the row cap as a TYPE but not as a name,
     * because no production member names it. A future edit adding that word to the name tokens
     * would therefore pass every other assertion here while refusing the one abstraction the four
     * production repositories use to read page size plus one rows, so the absence is asserted
     * directly and the word is derived from the type rather than typed as a bare literal.
     */
    @Test
    @DisplayName("guard: the row cap type and its name are absent from every refused set")
    void rowCapIsNeverRefused() {
        String rowCapWord = Limit.class.getSimpleName().toLowerCase(Locale.ROOT);

        assertThat(REFUSED_CURSOR_REQUEST_TYPE_NAME)
                .withFailMessage(
                        "The refused cursor-request type has become %s, which is the row cap the"
                                + " keyset queries use rather than a paging request.",
                        PERMITTED_ROW_CAP_TYPE_NAME)
                .isNotEqualTo(PERMITTED_ROW_CAP_TYPE_NAME);
        assertThat(REFUSED_PAGED_RESULT_TYPE_NAMES)
                .withFailMessage(
                        "The refused return types now include %s, so every keyset query capped at"
                                + " page size plus one would be reported as an offset violation.",
                        PERMITTED_ROW_CAP_TYPE_NAME)
                .doesNotContain(PERMITTED_ROW_CAP_TYPE_NAME);
        assertThat(REFUSED_MEMBER_NAME_TOKENS)
                .withFailMessage(
                        "The refused name tokens now include '%s', which would refuse a member"
                                + " named for the row cap the charter requires.",
                        rowCapWord)
                .doesNotContain(rowCapWord);
        assertThat(REFUSED_QUERY_SYNTAX_TOKENS)
                .withFailMessage(
                        "The refused query fragments now include '%s', which would refuse a written"
                                + " query that bounds how many rows it returns. Bounding a result is"
                                + " the size-plus-one probe; discarding a counted prefix is the"
                                + " offset, and only the second is forbidden.",
                        rowCapWord)
                .doesNotContain(rowCapWord);
    }

    /**
     * Applies the same predicate to the production repository package and requires no violation.
     *
     * <p>Assumptions: this is what turns the five refusals above from a demonstration into a
     * guardrail. An offset query added to any class declared in
     * {@code com.carddemo.transaction.repository} fails this assertion, so the charter's closing
     * prohibition is decided by the build rather than by a reader.
     *
     * <p>Assumptions: a green result here is also the empirical verification that the predicate
     * inspects declared members only. All four covered interfaces extend {@code JpaRepository},
     * whose {@code PagingAndSortingRepository} supertype declares a find-all member taking the
     * refused request type and returning the refused result type; a predicate that resolved
     * inherited members would report all four as violators. If this assertion ever fires against
     * an unchanged repository package, the predicate has started walking the type hierarchy and
     * must be narrowed rather than the assertion relaxed.
     *
     * <p>Assumptions: the subject set is asserted non-empty and asserted to reach the four covered
     * interfaces before the predicate runs, because an evaluation whose selection matched nothing
     * and an evaluation that examined every class and found nothing report the same pass. The
     * package holds more than those four -- a package descriptor, a custom write fragment, its
     * implementation and one cross-context reader -- and every one of them is evaluated, so a
     * ninth class added to the package is covered without an edit here.
     */
    @Test
    @DisplayName("guardrail: no class declared in the repository package pages by offset")
    void productionRepositoryPackageDeclaresNoOffsetPagingMember() {
        JavaClasses importedClasses = importProductionRepositoryPackage();
        JavaClasses subjects = importedClasses.that(resideInAPackage(REPOSITORY_PACKAGE_IDENTIFIER));

        assertThat(subjects)
                .withFailMessage(
                        "No production class was imported from %s, so this guardrail would report a"
                                + " pass without evaluating anything.",
                        REPOSITORY_PACKAGE_IDENTIFIER)
                .isNotEmpty();

        List<String> uncoveredRepositories = COVERED_PRODUCTION_REPOSITORIES.stream()
                .filter(repository -> !subjects.contain(repository))
                .map(Class::getName)
                .toList();
        assertThat(uncoveredRepositories)
                .withFailMessage(
                        "The imported subject set does not reach %s, so the guardrail no longer"
                                + " covers the interfaces the keyset contract is written for.",
                        uncoveredRepositories)
                .isEmpty();

        EvaluationResult evaluation = PRODUCTION_REPOSITORIES_PAGE_BY_KEY.evaluate(importedClasses);

        assertThat(evaluation.getFailureReport().getDetails())
                .withFailMessage(
                        "A class declared in %s pages by offset, or the predicate has begun"
                                + " resolving members inherited from the persistence supertypes."
                                + " Narrow the predicate to declared members before relaxing this"
                                + " assertion.",
                        REPOSITORY_PACKAGE_IDENTIFIER)
                .isEmpty();
    }

    /**
     * Checks the predicate against one synthetic subject, throwing when it reports a violation.
     *
     * @param syntheticSubject the nested subject to evaluate, whose declared members either carry a
     *     refused shape or deliberately do not
     * @throws AssertionError when the predicate reports a violation for that subject, which is the
     *     outcome the five refusal assertions expect and assert on
     */
    private static void checkAgainst(Class<?> syntheticSubject) {
        ruleFor(syntheticSubject).check(importOf(syntheticSubject));
    }

    /**
     * Evaluates the predicate against one synthetic subject and returns the outcome.
     *
     * <p>Alternatives Considered: driving the two assertions that expect NO violation through the
     * checking form above and asserting that nothing was thrown. Rejected on the diagnostic, not on
     * the verdict: both forms read the same violation set, so they decide identically, but an
     * assertion phrased as "nothing was thrown" reports only that something was when it fails and
     * discards the violation list, whereas asserting that the failure report holds no detail prints
     * the offending declarations themselves. That list is what a maintainer needs on the day an
     * admission assertion starts failing, and it is the difference between a report that names the
     * member to repair and one that names only the test.
     *
     * @param syntheticSubject the nested subject to evaluate
     * @return the evaluation carrying whatever violations the predicate produced for that subject
     */
    private static EvaluationResult evaluateAgainst(Class<?> syntheticSubject) {
        return ruleFor(syntheticSubject).evaluate(importOf(syntheticSubject));
    }

    /**
     * Pairs the shared predicate with a selection naming exactly one synthetic subject.
     *
     * <p>Alternatives Considered: selecting by the package identifier of this test, which is what
     * the sibling money proof does. Rejected HERE on a hazard that does not arise there: every
     * synthetic subject in this file resides in this one package alongside the proof harness
     * itself, and this harness deliberately declares member names carrying the very vocabulary the
     * predicate refuses, so a package-wide selection would evaluate the harness and the other
     * subjects together and report refusals that belong to neither the subject under proof nor the
     * production code. Selecting by class identity keeps each assertion evidence about one shape.
     *
     * <p>Assumptions: identity is decided by ArchUnit's own class-equivalence predicate rather
     * than by comparing name strings, and the selection therefore matches exactly one subject,
     * which is what keeps the engine's empty-selection error out of the refusal assertions.
     *
     * @param syntheticSubject the nested subject the returned rule selects
     * @return the rule pairing the shared predicate with that single subject
     */
    private static ArchRule ruleFor(Class<?> syntheticSubject) {
        return classes()
                .that(equivalentTo(syntheticSubject))
                .should(KEYSET_ONLY_DECLARED_MEMBERS)
                .as("declared members of " + syntheticSubject.getName()
                        + " page by key and never by offset");
    }

    /**
     * Imports one synthetic subject so the predicate can be evaluated against it.
     *
     * @param syntheticSubject the nested subject to import
     * @return the imported classes, which the selection above narrows to that subject
     */
    private static JavaClasses importOf(Class<?> syntheticSubject) {
        return new ClassFileImporter().importClasses(syntheticSubject);
    }

    /**
     * Imports the compiled production classes of the repository package, excluding test sources.
     *
     * <p>Assumptions: the test exclusion is what keeps the synthetic subjects out of the guardrail.
     * A subject authored to be refused is still a class on this module's test classpath, so without
     * the exclusion the guardrail would fail by construction over a type written to be refused.
     *
     * @return the production classes of the repository package, described by the scope they came
     *     from
     */
    private static JavaClasses importProductionRepositoryPackage() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(REPOSITORY_PACKAGE)
                .as("production classes of " + REPOSITORY_PACKAGE
                        + " visible to the executing module");
    }

    /**
     * Reports every declared code unit that pages by offset instead of by key.
     *
     * <p>Assumptions: the four tests this condition applies are the four ways an offset reaches a
     * query, and each one is invisible to the other three. A framework request type arrives in a
     * parameter, a paged or sliced result leaves through a return type, a hand-rolled row skip
     * shows only in a member's own name, and a written statement shows only in an annotation value.
     * A condition covering three of the four reports a pass over the fourth.
     *
     * <p>Alternatives Considered: expressing this with the fluent predicate that matches a
     * callable's raw parameter types. Rejected on the same verified property of the ArchUnit 1.4.2
     * API that the sibling money family records: that predicate matches an ENTIRE parameter list,
     * so used as a contains-any test it admits a callable whose second parameter of three is the
     * refused type. This condition walks the parameter list by position instead, which also lets
     * the report name the position that has to change.
     */
    private static final class KeysetOnlyDeclaredMemberCondition extends ArchCondition<JavaClass> {

        /**
         * Describes the condition in the terms ArchUnit prints beside a violation.
         */
        private KeysetOnlyDeclaredMemberCondition() {
            super("declare only keyset-shaped members: no " + REFUSED_CURSOR_REQUEST_TYPE_NAME
                    + " parameter, no return type among " + REFUSED_PAGED_RESULT_TYPE_NAMES
                    + ", no name carrying one of " + REFUSED_MEMBER_NAME_TOKENS + " or opening the '"
                    + REFUSED_COUNT_PROJECTION_KEYWORD + "' projection keyword, and no annotation"
                    + " statement carrying one of " + REFUSED_QUERY_SYNTAX_TOKENS);
        }

        /**
         * Adds one event for each offending position on each DECLARED code unit of the candidate.
         *
         * <p>Assumptions: the walk is over ArchUnit's declared code units and never over its
         * hierarchy-resolving accessors, and that choice is the difference between a working gate
         * and a permanently red build. The persistence supertypes DECLARE a find-all member taking
         * the refused request type and returning the refused result type, and a count member
         * besides, so resolving inherited members would report every repository extending them as
         * a violator of a surface none of them declares or calls.
         *
         * <p>Assumptions: each offending position is reported separately, so a member declaring a
         * refused parameter and a refused return type produces two events. The position is what
         * has to change, and one collapsed event would name a member while leaving a reader to
         * find which part of it was meant.
         *
         * <p>Assumptions: a match is reported as violated rather than as satisfied, because the
         * rules that own this condition select classes positively and ArchUnit inverts events only
         * for a rule phrased as a prohibition over its subjects. Reporting a match as satisfied
         * here would leave the condition finding the offending declaration and the rule reporting
         * a clean pass.
         *
         * @param candidate the class whose declared code units are inspected
         * @param events the collector this condition adds one event per offending position to
         */
        @Override
        public void check(JavaClass candidate, ConditionEvents events) {
            for (JavaCodeUnit declaredCodeUnit : candidate.getCodeUnits()) {
                reportRefusedParameterType(declaredCodeUnit, events);
                reportRefusedReturnType(declaredCodeUnit, events);
                reportRefusedName(declaredCodeUnit, events);
                reportRefusedQueryStatement(declaredCodeUnit, events);
            }
        }

        /**
         * Reports each declared parameter position holding the cursor-request abstraction.
         *
         * @param declaredCodeUnit the declared method or constructor whose parameters are inspected
         * @param events the collector to add one event per offending position to
         */
        private static void reportRefusedParameterType(
                JavaCodeUnit declaredCodeUnit, ConditionEvents events) {
            List<JavaClass> parameterTypes = declaredCodeUnit.getRawParameterTypes();
            for (int position = 0; position < parameterTypes.size(); position++) {
                JavaClass parameterType = parameterTypes.get(position);
                if (REFUSED_CURSOR_REQUEST_TYPE_NAME.equals(parameterType.getName())) {
                    events.add(SimpleConditionEvent.violated(
                            declaredCodeUnit,
                            declaredCodeUnit.getFullName() + " declares parameter " + position
                                    + " as " + parameterType.getName()));
                }
            }
        }

        /**
         * Reports a declared return type that is one of the paged result abstractions.
         *
         * @param declaredCodeUnit the declared method or constructor whose return type is inspected
         * @param events the collector to add one event to when the return type is refused
         */
        private static void reportRefusedReturnType(
                JavaCodeUnit declaredCodeUnit, ConditionEvents events) {
            String returnTypeName = declaredCodeUnit.getRawReturnType().getName();
            if (REFUSED_PAGED_RESULT_TYPE_NAMES.contains(returnTypeName)) {
                events.add(SimpleConditionEvent.violated(
                        declaredCodeUnit,
                        declaredCodeUnit.getFullName() + " returns " + returnTypeName));
            }
        }

        /**
         * Reports a declared name carrying offset vocabulary or opening a count projection.
         *
         * <p>Assumptions: the token match runs on the lowercased name and the projection keyword
         * match runs on the name as written, because the two need opposite treatment. A token such
         * as the offset fragment can be spelled inside any camel-case word and has to be found
         * case-insensitively, whereas the projection keyword is recognised only at a word boundary
         * and lowercasing the name would destroy the capital that marks it -- which is what keeps a
         * member named for a country from being read as a count.
         *
         * @param declaredCodeUnit the declared method or constructor whose name is inspected
         * @param events the collector to add one event per matching token to
         */
        private static void reportRefusedName(
                JavaCodeUnit declaredCodeUnit, ConditionEvents events) {
            String declaredName = declaredCodeUnit.getName();
            String normalisedName = declaredName.toLowerCase(Locale.ROOT);
            for (String token : REFUSED_MEMBER_NAME_TOKENS) {
                if (normalisedName.contains(token)) {
                    events.add(SimpleConditionEvent.violated(
                            declaredCodeUnit,
                            declaredCodeUnit.getFullName()
                                    + " declares a name carrying the refused token '" + token
                                    + "'"));
                }
            }
            if (opensACountProjection(declaredName)) {
                events.add(SimpleConditionEvent.violated(
                        declaredCodeUnit,
                        declaredCodeUnit.getFullName() + " declares a name opening the '"
                                + REFUSED_COUNT_PROJECTION_KEYWORD + "' projection keyword"));
            }
        }

        /**
         * Decides whether a declared name opens with the count projection keyword as a whole word.
         *
         * @param declaredName the member name exactly as written, whose capitals carry the word
         *     boundary this test depends on
         * @return whether the name is the keyword itself or the keyword followed by a new word
         */
        private static boolean opensACountProjection(String declaredName) {
            if (!declaredName.startsWith(REFUSED_COUNT_PROJECTION_KEYWORD)) {
                return false;
            }
            String remainder = declaredName.substring(REFUSED_COUNT_PROJECTION_KEYWORD.length());
            return remainder.isEmpty() || Character.isUpperCase(remainder.charAt(0));
        }

        /**
         * Reports a written statement on a declared member that discards a counted prefix.
         *
         * <p>Alternatives Considered: reading the value of one named query annotation type.
         * Rejected because this framework publishes more than one annotation that carries a
         * statement, and more than one property of the same annotation can hold one, so naming a
         * type or a property would leave a statement carried by the other unread. Every
         * string-valued property of every annotation on the declared member is scanned instead,
         * which is also why the empty defaults such annotations carry match nothing.
         *
         * @param declaredCodeUnit the declared method or constructor whose annotations are read
         * @param events the collector to add one event per matching fragment to
         */
        private static void reportRefusedQueryStatement(
                JavaCodeUnit declaredCodeUnit, ConditionEvents events) {
            for (JavaAnnotation<? extends JavaMember> annotation
                    : declaredCodeUnit.getAnnotations()) {
                for (Map.Entry<String, Object> property : annotation.getProperties().entrySet()) {
                    for (String statement : statementCandidatesOf(property.getValue())) {
                        reportRefusedQuerySyntax(
                                declaredCodeUnit, annotation, property.getKey(), statement, events);
                    }
                }
            }
        }

        /**
         * Reports each refused fragment found in one annotation property's statement.
         *
         * @param declaredCodeUnit the declared method or constructor carrying the annotation
         * @param annotation the annotation whose property holds the statement, named in the report
         *     so a reader knows which annotation to open
         * @param propertyName the annotation property the statement was read from
         * @param statement the statement text to inspect
         * @param events the collector to add one event per matching fragment to
         */
        private static void reportRefusedQuerySyntax(
                JavaCodeUnit declaredCodeUnit,
                JavaAnnotation<? extends JavaMember> annotation,
                String propertyName,
                String statement,
                ConditionEvents events) {
            String normalisedStatement = statement.toLowerCase(Locale.ROOT);
            for (String token : REFUSED_QUERY_SYNTAX_TOKENS) {
                if (normalisedStatement.contains(token)) {
                    events.add(SimpleConditionEvent.violated(
                            declaredCodeUnit,
                            declaredCodeUnit.getFullName() + " carries "
                                    + annotation.getRawType().getName() + " whose '" + propertyName
                                    + "' statement contains the refused fragment '" + token + "'"));
                }
            }
        }

        /**
         * Selects the statement texts held by one annotation property value.
         *
         * <p>Assumptions: an annotation property can hold a statement singly or as an array, and
         * ArchUnit surfaces the array as an object array whatever its component type. Both shapes
         * are handled and every other value kind -- an enumeration constant, a class reference, a
         * number, a flag -- yields nothing, because none of them can carry a query.
         *
         * @param annotationPropertyValue the property value as ArchUnit's annotation model reports
         *     it
         * @return the statement texts to inspect, empty when the value cannot carry one
         */
        private static List<String> statementCandidatesOf(Object annotationPropertyValue) {
            if (annotationPropertyValue instanceof String text) {
                return List.of(text);
            }
            if (annotationPropertyValue instanceof Object[] elements) {
                return Arrays.stream(elements)
                        .filter(String.class::isInstance)
                        .map(String.class::cast)
                        .toList();
            }
            return List.of();
        }
    }

    /**
     * A synthetic subject declaring a member that accepts the cursor-request abstraction.
     *
     * <p>Assumptions: this type is never instantiated, never implemented and never referenced from
     * production code, and it extends nothing. Extending a persistence supertype to make it look
     * more like a repository would import that supertype's own declared paging surface into the
     * imported graph, and the refusal asserted against it could then no longer be attributed to
     * the member declared here.
     */
    private static interface PagingRequestParameterSubject {

        /**
         * Declares a read that takes the paging request, which is the position under proof.
         *
         * @param pageRequest the paging request whose provider statement discards a counted prefix
         *     of the ordered set
         * @return the rows such a read would return
         */
        List<String> findEveryRow(Pageable pageRequest);
    }

    /**
     * A synthetic subject declaring a member that returns the paged result abstraction.
     *
     * <p>Assumptions: the member takes a key rather than a paging request, so the only refusable
     * thing about it is the return type. That isolation is what makes the refusal asserted against
     * it evidence about the return position.
     */
    private static interface PagedResultReturnTypeSubject {

        /**
         * Declares a read that returns the paged result, which carries a total this context lacks.
         *
         * @param lastKey the key the previous page ended on
         * @return the paged result such a read would return
         */
        Page<String> findWholeSet(String lastKey);
    }

    /**
     * A synthetic subject declaring a member that returns the sliced result abstraction.
     *
     * <p>Assumptions: the sliced result is the paged request shape with the total omitted, so this
     * subject exists to show that omitting the total does not make the shape admissible.
     */
    private static interface SlicedResultReturnTypeSubject {

        /**
         * Declares a read that returns the sliced result, which is the same request without a total.
         *
         * @param lastKey the key the previous page ended on
         * @return the sliced result such a read would return
         */
        Slice<String> findPartialSet(String lastKey);
    }

    /**
     * A synthetic subject declaring a member whose own name carries offset vocabulary.
     *
     * <p>Assumptions: the member names no framework type at all, taking a plain whole number and
     * returning an ordinary list. It is the shape a type-only predicate admits, which is why the
     * charter's prohibition covers vocabulary as well as types.
     */
    private static interface OffsetNamedMemberSubject {

        /**
         * Declares a read that discards a counted prefix, saying so only in its own name.
         *
         * @param rowsToDiscard how many rows of the ordered set the read would step over
         * @return the rows such a read would return
         */
        List<String> findRowsAtOffset(long rowsToDiscard);
    }

    /**
     * A synthetic subject declaring a member whose written query discards a counted prefix.
     *
     * <p>Assumptions: the member's name is compliant, its parameter is a key and its return type is
     * an ordinary list, so the statement in the annotation is the only refusable thing about it.
     * That is the shape both the type tests and the name test admit.
     */
    private static interface OffsetBearingQueryAnnotationSubject {

        /**
         * Declares a read whose statement orders correctly and then steps over counted rows.
         *
         * @param lastKey the key the previous page ended on, which the statement ignores
         * @return the rows such a read would return
         */
        @Query("select r from LedgerRow r order by r.key asc offset 20")
        List<String> findFollowingRows(String lastKey);
    }

    /**
     * A synthetic subject declaring the keyset shape the charter requires, for admission.
     *
     * <p>Assumptions: all three members are shaped the way the four production repositories are
     * shaped -- a strict comparison against one scalar cursor, an explicit order in the direction
     * of travel, and the row cap that lets a caller read page size plus one rows so the surplus row
     * answers availability. Admitting this subject is what shows the predicate discriminates by
     * shape rather than refusing every member that reads more than one row.
     */
    private static interface KeysetShapedSubject {

        /**
         * Declares the forward page, seeking strictly past the last key seen.
         *
         * @param lastKey the key the previous page ended on, excluded by the strict comparison
         * @param limit the row cap, which a caller sets to page size plus one
         * @return the following rows in ascending key order
         */
        List<String> findByKeyGreaterThanOrderByKeyAsc(String lastKey, Limit limit);

        /**
         * Declares the backward page, seeking strictly before the first key seen.
         *
         * @param firstKey the key the current page begins on, excluded by the strict comparison
         * @param limit the row cap, which a caller sets to page size plus one
         * @return the preceding rows in descending key order, nearest the stated position first
         */
        List<String> findByKeyLessThanOrderByKeyDesc(String firstKey, Limit limit);

        /**
         * Declares the forward page as a written statement rather than as a derived name.
         *
         * @param lastKey the key the previous page ended on, excluded by the strict comparison
         * @param limit the row cap, which a caller sets to page size plus one
         * @return the following rows in ascending key order
         */
        @Query("select r from LedgerRow r where r.key > :lastKey order by r.key asc")
        List<String> findFollowingRowsInKeyOrder(String lastKey, Limit limit);
    }
}
