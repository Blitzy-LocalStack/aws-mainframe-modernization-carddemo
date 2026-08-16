package com.carddemo.transaction.architecture;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.equivalentTo;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.carddemo.transaction.dto.TransactionDetailResponse;
import com.carddemo.transaction.dto.TransactionListItemResponse;
import com.carddemo.transaction.dto.TransactionListRequest;
import com.carddemo.transaction.repository.DailyTransactionRepository;
import com.carddemo.transaction.repository.TransactionCategoryBalanceRepository;
import com.carddemo.transaction.repository.TransactionRejectRepository;
import com.carddemo.transaction.repository.TransactionRepository;
import com.carddemo.transaction.service.BillPaymentService;
import com.carddemo.transaction.service.TransactionAddService;
import com.carddemo.transaction.service.TransactionListService;
import com.carddemo.transaction.service.TransactionViewService;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaField;
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
import org.springframework.data.domain.KeysetScrollPosition;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.OffsetScrollPosition;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;
import org.springframework.data.jpa.repository.Query;

/**
 * Proves that the keyset-only list contract of this bounded context actually rejects an
 * offset-shaped declaration, and applies the same predicate to the real repository, service and
 * request-response packages so the proof is a live guardrail rather than a demonstration.
 *
 * <p>Purpose: a gate that cannot fail is not a gate, and a gate narrower than the claim it is cited
 * for is worse than none, because the claim then reads as verified. This class owns the predicate
 * that decides whether a declared member of the list layer pages by key or by offset, evaluates it
 * against synthetic subjects authored to be refused, evaluates it against a synthetic subject
 * authored to be admitted, and finally evaluates it against the declared members and outgoing
 * dependencies of {@code com.carddemo.transaction.repository},
 * {@code com.carddemo.transaction.service} and {@code com.carddemo.transaction.dto}. The first
 * evaluation shows the predicate bites, the second shows it discriminates rather than refusing
 * everything, and the third makes an offset construct added to any of the three packages fail this
 * test.
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
 * <p>Assumptions: the catching mechanism this proof certifies is the keyset-only list contract, and
 * that contract is declared by THREE package charters rather than one. The repository charter at
 * {@code com.carddemo.transaction.repository.package-info} states the invariant -- the cursor is the
 * scalar {@code tranId}, availability is discovered by fetching one row beyond the page size, the
 * comparisons are strictly greater-than forward with an ascending order and strictly less-than
 * backward with a descending order, and the envelope is
 * {@code com.carddemo.common.web.PageResponse} and is never re-declared -- and rejects offset paging
 * outright in the paragraph beginning at its line 223. The service charter at
 * {@code com.carddemo.transaction.service.package-info} carries the heading "Pagination compares
 * keys and never counts rows" at line 112 and states at lines 137 to 141 that no ordinal-position
 * vocabulary appears anywhere in that package, naming four positions it covers: parameter, field,
 * response component and method name. The request-response charter at
 * {@code com.carddemo.transaction.dto.package-info} states at lines 478 to 481 that no ordinal
 * paging component of any kind appears on any type there. This class does not restate any of the
 * three as a second authority; it makes their shared closing clause decidable by a build.
 *
 * <p>Refactoring Rationale: the predicate's subject selection covers all three of those packages,
 * and it was widened from the repository package alone. A repository-only selection was defensible
 * while the claim being certified was the repository charter's, and it stopped being defensible once
 * the same predicate was cited as the mechanism behind the service and request-response charters
 * too: two of the four positions the service charter names -- the field and the response component
 * -- do not exist in a repository interface at all, so a repository-only gate reported a pass over
 * clauses it had never read. Widening the selection rather than authoring a second predicate keeps
 * one invariant in one place; the alternative of three predicates was rejected because the pair that
 * drifts apart is always the pair that keeps passing.
 *
 * <p>Refactoring Rationale: the predicate also gained a field walk and an outgoing-dependency test
 * in the same change, and neither is decoration. A response component compiles to a field and an
 * accessor, so a code-unit walk decides the accessor and leaves the component's declared type
 * unread; and a service can construct the concrete paging request inside a method body, where no
 * signature, field, name or annotation records it. Widening the scope without adding those two tests
 * would have moved the gate over two packages whose charters name exactly the positions it could not
 * see.
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
 * types, and that placement is load-bearing rather than tidy. The guardrail assertion below
 * evaluates the predicate over the compiled production repository, service and request-response
 * packages; a subject authored to be refused would be picked up by that evaluation and would fail
 * the very build this proof exists to protect. No offset-shaped member is added to a production
 * class, not even briefly.
 *
 * <p>Assumptions: the predicate inspects DECLARED members only, and this is the least obvious
 * constraint in the file. All four production repositories extend {@code JpaRepository}, which
 * reaches {@code PagingAndSortingRepository}, and that supertype DECLARES a find-all method taking
 * {@code org.springframework.data.domain.Pageable} and returning
 * {@code org.springframework.data.domain.Page}; {@code CrudRepository} likewise declares a count
 * member. A predicate that resolved inherited members would therefore report every repository in
 * the package as an offset violator and redden the build permanently, over a surface no repository
 * in this module declares or calls. ArchUnit's declared code-unit accessor is used throughout and
 * the hierarchy-resolving accessors are not, which is what the guardrail assertion verifies
 * empirically rather than by inspection. The field walk uses the declared-field accessor for the
 * same reason, since its all-fields sibling resolves a supertype's fields too.
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
     * The production repository package, which owns the keyset queries themselves.
     */
    private static final String REPOSITORY_PACKAGE = MODULE_ROOT + ".repository";

    /**
     * The production service package, which owns the cursor-to-direction pairing above the queries.
     *
     * <p>Assumptions: this package is in scope because its own charter puts it there, not because
     * widening a gate is generally desirable. The heading at
     * {@code com.carddemo.transaction.service.package-info} line 112 reads "Pagination compares keys
     * and never counts rows", and the paragraph at lines 137 to 141 states that no ordinal-position
     * vocabulary appears anywhere in the package and names four positions it covers -- parameter,
     * field, response component and method name. A gate that read the repository package alone would
     * leave three of those four positions undecided in the package that declares them.</p>
     */
    private static final String SERVICE_PACKAGE = MODULE_ROOT + ".service";

    /**
     * The production request and response package, which owns the shapes crossing the wire.
     *
     * <p>Assumptions: this package is in scope on the same ground. The paragraph at
     * {@code com.carddemo.transaction.dto.package-info} lines 478 to 481 states that no ordinal
     * paging component of any kind -- no page number, no counted starting position and no page index
     * -- appears on any type there. A response component is a record component, which compiles to a
     * field and an accessor rather than to a query, so it is reachable only by inspecting fields and
     * declared members of this package.</p>
     */
    private static final String DTO_PACKAGE = MODULE_ROOT + ".dto";

    /**
     * The ArchUnit package identifiers of the three packages the list layer spans.
     *
     * <p>Assumptions: each identifier carries the trailing wildcard, which matches the named package
     * and anything beneath it while matching nothing above it. Every entry is derived from
     * {@code MODULE_ROOT} rather than typed as a literal, so a package rename that left this array
     * stale would leave the array pointing at a package the importer then finds empty, which the
     * guardrail's own non-empty and reach assertions report rather than pass over.</p>
     *
     * <p>Refactoring Rationale: the array replaces a single repository identifier. The predicate was
     * unchanged by that widening and only its subject selection moved, which is deliberate: one
     * predicate evaluated over three packages cannot drift between them, whereas three predicates
     * would be three places for the same invariant to be edited unevenly.</p>
     */
    private static final String[] LIST_LAYER_PACKAGE_IDENTIFIERS = {
            REPOSITORY_PACKAGE + "..", SERVICE_PACKAGE + "..", DTO_PACKAGE + ".."};

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
     * Every offset-paging type refused in a declared field and in a declared dependency.
     *
     * <p>Assumptions: this set is wider than the two signature sets above, and the widening is
     * load-bearing rather than defensive. A signature names the ABSTRACTION -- a member takes
     * {@code Pageable} and returns {@code Page} -- whereas a body names the IMPLEMENTATION, because
     * the only way to obtain a paging request is to construct one, and the constructed type is
     * {@code PageRequest} rather than the interface it satisfies. A set naming only the interfaces
     * would therefore read every declared signature correctly and still admit a service that built a
     * {@code PageRequest} in a method body and handed it to a callee.</p>
     *
     * <p>Assumptions: the two concrete result types are named for the same reason in the opposite
     * direction. A member may return an ordinary list while assembling a {@code PageImpl} or a
     * {@code SliceImpl} internally, and assembling either one issues the request shape this context
     * refuses whatever the declared return type says.</p>
     *
     * <p>Assumptions: {@code OffsetScrollPosition} is named and its keyset sibling is deliberately
     * not. The scrolling API of this persistence framework publishes both positions from one package,
     * and they are opposites: the offset position counts rows consumed from the start of the result,
     * which is precisely the construct the reference browse state does not hold, while the keyset
     * position carries the boundary key, which is the construct it does hold. Refusing the package
     * rather than the type would refuse the correct one along with the wrong one; the guard assertion
     * below asserts the keyset position's absence from this set for that reason.</p>
     *
     * <p>Alternatives Considered: refusing every type residing in the framework's domain package.
     * Rejected on two independent counts. It would refuse {@code Limit}, which all four production
     * repositories use to read page size plus one rows, and it would refuse the sort abstraction and
     * the keyset scroll position, none of which skips a row. Naming types keeps the set decidable by
     * a reader and keeps a rename a compilation failure, because every entry is derived from a class
     * literal rather than written as a string.</p>
     */
    private static final List<String> REFUSED_OFFSET_PAGING_TYPE_NAMES = List.of(
            Pageable.class.getName(),
            PageRequest.class.getName(),
            Page.class.getName(),
            PageImpl.class.getName(),
            Slice.class.getName(),
            SliceImpl.class.getName(),
            OffsetScrollPosition.class.getName());

    /**
     * The keyset scroll position, which carries a boundary key and is therefore never refused.
     *
     * <p>Assumptions: this constant exists to be asserted absent from the refused set above rather
     * than to be matched against a member, exactly as the row cap constant does. No production
     * member names it today; the assertion protects the day one does.</p>
     */
    private static final String PERMITTED_KEYSET_SCROLL_TYPE_NAME = KeysetScrollPosition.class
            .getName();

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
     * The production types the guardrail assertion must be seen to reach, one group per package.
     *
     * <p>Assumptions: these are named by class literal rather than by string, so a rename that
     * left this list stale would not compile instead of silently narrowing the guardrail. The
     * guardrail evaluates every class residing in the three list-layer packages and additionally
     * asserts that these eleven are among the subjects, because an evaluation that quietly stopped
     * reaching them would report the same pass as one that examined them.</p>
     *
     * <p>Assumptions: the list names types from all three packages rather than repositories alone,
     * which is what makes the reach assertion capable of catching a half-applied widening. An import
     * that reached the repository package and silently missed the other two would otherwise satisfy
     * a repository-only reach assertion and report a pass over the two packages whose charters name
     * the field and response-component positions.</p>
     *
     * <p>Assumptions: the four repositories are the interfaces the keyset contract is written for;
     * the four services are every class in the service package that reads a list or a single record;
     * and the three request and response types are the shapes a page crosses the wire in -- the list
     * request that carries the cursor and its direction, the element type the shared envelope
     * carries, and the single-record response that carries no paging state at all.</p>
     */
    private static final List<Class<?>> COVERED_PRODUCTION_LIST_LAYER_TYPES = List.of(
            TransactionRepository.class,
            DailyTransactionRepository.class,
            TransactionCategoryBalanceRepository.class,
            TransactionRejectRepository.class,
            TransactionListService.class,
            TransactionViewService.class,
            TransactionAddService.class,
            BillPaymentService.class,
            TransactionListRequest.class,
            TransactionListItemResponse.class,
            TransactionDetailResponse.class);

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
     * The same predicate paired with a selection naming all three production list-layer packages.
     *
     * <p>Assumptions: the selection is expressed with ArchUnit's package matcher, which tests a
     * package identifier against a class's own package rather than testing one string for
     * containment in another. A containment test would additionally select a package whose name
     * merely begins with the same characters, which is how an unrelated tree gets drawn into a
     * gate that reads as though it were scoped.</p>
     *
     * <p>Assumptions: the any-package form is used rather than three rules, so a single evaluation
     * reports every offending position across the list layer at once. Three rules would stop at the
     * first that failed and hide whatever the other two would have said, which on the day a widened
     * type or token leaks is exactly the information a maintainer needs.</p>
     */
    private static final ArchRule PRODUCTION_LIST_LAYER_PAGES_BY_KEY = classes()
            .that(resideInAnyPackage(LIST_LAYER_PACKAGE_IDENTIFIERS))
            .should(KEYSET_ONLY_DECLARED_MEMBERS)
            .as("every class declared in " + Arrays.toString(LIST_LAYER_PACKAGE_IDENTIFIERS)
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
        //     predicate which had lost one test of the five still fails here. A single subject
        //     carrying every refused shape at once would report a refusal from whichever test
        //     survived and conceal the four that had gone.
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
     * Proves the predicate refuses a declared FIELD typed as the paged result abstraction.
     *
     * <p>Assumptions: the field position is proved separately because it is the position a record
     * component occupies. A response component compiles to a private final field and an accessor,
     * and the request and response package's charter prohibits an ordinal paging component, so a
     * predicate that read code units alone would decide the accessor and never read the component's
     * own declared type.
     *
     * <p>Assumptions: the class-level dependency test also fires on this subject, because a field's
     * type is an outgoing dependency of the declaring class. The assertion therefore requires the
     * report to name the FIELD, which only the field test does, so it stays evidence about the field
     * position rather than about the dependency test firing again.
     */
    @Test
    @DisplayName("proof: a declared field typed as the paged result type is refused")
    void refusesADeclaredFieldOfThePagedResultType() {
        AssertionError refusal = assertThrows(
                AssertionError.class,
                () -> checkAgainst(PagedResultFieldSubject.class),
                "The predicate admitted a declared field typed as " + Page.class.getName()
                        + ", so an ordinal paging component can still be declared on a request or"
                        + " response type, which is the position a record component occupies.");

        assertThat(refusal.getMessage())
                .withFailMessage(
                        "The predicate refused the subject but its report did not name the offending"
                                + " field, so the refusal is attributable to the class-level"
                                + " dependency test rather than to the field position and the field"
                                + " walk may be absent.")
                .contains("lastPage", Page.class.getName());
    }

    /**
     * Proves the predicate refuses a declared FIELD whose own name carries offset vocabulary.
     *
     * <p>Assumptions: the subject's field is a primitive whole number, so it contributes no class
     * dependency at all and nothing about it is refusable except the name. That isolation is what
     * makes this assertion evidence about the field-name test specifically, and it is the one
     * position the service package's charter names that no type test can reach: a field holding a
     * row's ordinal position is a plain number.
     */
    @Test
    @DisplayName("proof: a declared field name carrying offset vocabulary is refused")
    void refusesADeclaredFieldNameCarryingOffsetVocabulary() {
        AssertionError refusal = assertThrows(
                AssertionError.class,
                () -> checkAgainst(OffsetNamedFieldSubject.class),
                "The predicate admitted a declared field whose name carries offset vocabulary, so a"
                        + " stored row ordinal that names no framework type passes the gate.");

        assertThat(refusal.getMessage())
                .withFailMessage(
                        "The predicate refused the subject but its report named neither the"
                                + " offending field nor the token that matched, so a reader cannot"
                                + " tell which part of the declaration has to change.")
                .contains("rowOffset", "offset");
    }

    /**
     * Proves the predicate refuses a paging request constructed inside a method BODY.
     *
     * <p>Assumptions: this is the one shape all four declaration tests admit, and it is the shape a
     * service layer produces. A method may be named compliantly, take no framework parameter, return
     * an ordinary value, hold no field and carry no annotation, and still construct the concrete
     * paging request and hand it to a callee. Nothing in any declaration records that; the outgoing
     * dependency does.
     *
     * <p>Assumptions: the subject constructs the CONCRETE request type rather than mentioning the
     * interface, because that is what a body can actually do -- the interface has no constructor. A
     * refused set naming only the interface would therefore read every signature correctly and still
     * admit this subject, which is exactly the leak this assertion closes.
     */
    @Test
    @DisplayName("proof: a paging request constructed inside a method body is refused")
    void refusesAPagingRequestConstructedInsideAMethodBody() {
        AssertionError refusal = assertThrows(
                AssertionError.class,
                () -> checkAgainst(OffsetRequestDependencySubject.class),
                "The predicate admitted a class that constructs " + PageRequest.class.getName()
                        + " inside a method body, so the row skip can be assembled where no"
                        + " declaration mentions it and handed to a callee.");

        assertThat(refusal.getMessage())
                .withFailMessage(
                        "The predicate refused the subject but its report did not name %s, so the"
                                + " refusal cannot be attributed to the body dependency and no"
                                + " declaration on this subject is refusable.",
                        PageRequest.class.getName())
                .contains(PageRequest.class.getName());
    }

    /**
     * Proves the predicate admits a keyset-shaped subject, so its refusals discriminate.
     *
     * <p>Assumptions: without this half a predicate that had degenerated into one refusing
     * everything would satisfy all eight refusal assertions above while gating nothing, and an
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
        List<String> refusedDeclarations = evaluation.getFailureReport().getDetails();

        assertThat(refusedDeclarations)
                .withFailMessage(
                        "The predicate refused a subject that seeks strictly past the last key seen"
                                + " ascending, seeks strictly before the first key seen descending"
                                + " and caps both with %s. It is over-matching, so every refusal"
                                + " asserted in this class passes for a reason other than the"
                                + " offset shape it names. Refused declarations: %s",
                        PERMITTED_ROW_CAP_TYPE_NAME, refusedDeclarations)
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
     *
     * <p>Assumptions: the keyset scroll position is guarded here alongside the row cap, and for a
     * sharper reason. It resides in the same framework package as every type the offset-paging set
     * names, and its own name contains the word this context pages by, so a future edit that
     * refused a package rather than a list of types would refuse the one scrolling abstraction that
     * carries a boundary key instead of a row count. No production member names it today, which is
     * precisely why nothing else in this file would notice.
     */
    @Test
    @DisplayName("guard: the row cap and the keyset scroll position are absent from every refused"
            + " set")
    void rowCapIsNeverRefused() {
        String rowCapWord = Limit.class.getSimpleName().toLowerCase(Locale.ROOT);

        assertThat(REFUSED_OFFSET_PAGING_TYPE_NAMES)
                .withFailMessage(
                        "The offset-paging type set now includes %s, so every keyset query capped at"
                                + " page size plus one, and every class depending on that cap, would"
                                + " be reported as an offset violation.",
                        PERMITTED_ROW_CAP_TYPE_NAME)
                .doesNotContain(PERMITTED_ROW_CAP_TYPE_NAME);
        assertThat(REFUSED_OFFSET_PAGING_TYPE_NAMES)
                .withFailMessage(
                        "The offset-paging type set now includes %s, which carries the boundary KEY"
                                + " rather than a count of rows consumed. Refusing it would refuse"
                                + " the scrolling position this context's paging is shaped like,"
                                + " while its offset-counting sibling is the one to refuse.",
                        PERMITTED_KEYSET_SCROLL_TYPE_NAME)
                .doesNotContain(PERMITTED_KEYSET_SCROLL_TYPE_NAME);

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
     * Applies the same predicate to all three production list-layer packages and requires no
     * violation.
     *
     * <p>Assumptions: this is what turns the eight refusals above from a demonstration into a
     * guardrail. An offset query added to any class declared in
     * {@code com.carddemo.transaction.repository}, an offset-shaped field or method added to
     * {@code com.carddemo.transaction.service}, and an ordinal paging component added to
     * {@code com.carddemo.transaction.dto} all fail this assertion, so the closing prohibition of
     * all three charters is decided by the build rather than by a reader.
     *
     * <p>Assumptions: a green result here is also the empirical verification that the predicate
     * inspects declared members only. All four covered interfaces extend {@code JpaRepository},
     * whose {@code PagingAndSortingRepository} supertype declares a find-all member taking the
     * refused request type and returning the refused result type; a predicate that resolved
     * inherited members would report all four as violators. If this assertion ever fires against
     * an unchanged repository package, the predicate has started walking the type hierarchy and
     * must be narrowed rather than the assertion relaxed.
     *
     * <p>Assumptions: the subject set is asserted non-empty and asserted to reach every covered
     * type before the predicate runs, because an evaluation whose selection matched nothing
     * and an evaluation that examined every class and found nothing report the same pass. The three
     * packages hold more than those eleven -- three package descriptors, a custom write fragment,
     * its implementation, one cross-context reader, a cross-context client and its implementation,
     * and eight further request, response and outcome types -- and every one of them is evaluated,
     * so a class added to any of the three is covered without an edit here.
     */
    @Test
    @DisplayName("guardrail: no class declared in the list layer pages by offset")
    void productionListLayerDeclaresNoOffsetPagingMember() {
        JavaClasses importedClasses = importProductionListLayerPackages();
        JavaClasses subjects =
                importedClasses.that(resideInAnyPackage(LIST_LAYER_PACKAGE_IDENTIFIERS));

        assertThat(subjects)
                .withFailMessage(
                        "No production class was imported from %s, so this guardrail would report a"
                                + " pass without evaluating anything.",
                        Arrays.toString(LIST_LAYER_PACKAGE_IDENTIFIERS))
                .isNotEmpty();

        List<String> uncoveredTypes = COVERED_PRODUCTION_LIST_LAYER_TYPES.stream()
                .filter(coveredType -> !subjects.contain(coveredType))
                .map(Class::getName)
                .toList();
        assertThat(uncoveredTypes)
                .withFailMessage(
                        "The imported subject set does not reach %s, so the guardrail no longer"
                                + " covers the types the keyset contract is written for.",
                        uncoveredTypes)
                .isEmpty();

        EvaluationResult evaluation = PRODUCTION_LIST_LAYER_PAGES_BY_KEY.evaluate(importedClasses);
        List<String> offendingDeclarations = evaluation.getFailureReport().getDetails();

        // Assumptions: the offending declarations are interpolated into the message explicitly, and
        //     that is not redundant with reading the failure report. A supplied fail message
        //     REPLACES this assertion library's own description rather than preceding it, so
        //     asserting emptiness with a message and without the list prints the diagnosis and
        //     discards the evidence. Measured: a probe field named for a row offset, planted in the
        //     service package, failed this assertion with a message that never named the class or
        //     the field. The list is what tells a maintainer which of three packages to open.
        assertThat(offendingDeclarations)
                .withFailMessage(
                        "A class declared in %s pages by offset, or the predicate has begun"
                                + " resolving members inherited from the persistence supertypes."
                                + " Narrow the predicate to declared members before relaxing this"
                                + " assertion. Offending declarations: %s",
                        Arrays.toString(LIST_LAYER_PACKAGE_IDENTIFIERS), offendingDeclarations)
                .isEmpty();
    }

    /**
     * Checks the predicate against one synthetic subject, throwing when it reports a violation.
     *
     * @param syntheticSubject the nested subject to evaluate, whose declared members either carry a
     *     refused shape or deliberately do not
     * @throws AssertionError when the predicate reports a violation for that subject, which is the
     *     outcome the eight refusal assertions expect and assert on
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
     * Imports the compiled production classes of the three list-layer packages, excluding tests.
     *
     * <p>Assumptions: the test exclusion is what keeps the synthetic subjects out of the guardrail.
     * A subject authored to be refused is still a class on this module's test classpath, so without
     * the exclusion the guardrail would fail by construction over a type written to be refused.
     *
     * <p>Assumptions: the three packages are imported in one call rather than three, so the returned
     * graph is one graph. The predicate's dependency test reads a class's outgoing dependencies, and
     * a dependency whose target is absent from the graph is still reported with its fully qualified
     * name, so the test decides identically whichever package the target sits in.
     *
     * @return the production classes of the repository, service and request-response packages,
     *     described by the scope they came from
     */
    private static JavaClasses importProductionListLayerPackages() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(REPOSITORY_PACKAGE, SERVICE_PACKAGE, DTO_PACKAGE)
                .as("production classes of " + REPOSITORY_PACKAGE + ", " + SERVICE_PACKAGE + " and "
                        + DTO_PACKAGE + " visible to the executing module");
    }

    /**
     * Reports every declared member and outgoing dependency that pages by offset instead of by key.
     *
     * <p>Assumptions: the five tests this condition applies are the five ways an offset reaches a
     * query, and each one is invisible to the other four. A framework request type arrives in a
     * parameter, a paged or sliced result leaves through a return type, a hand-rolled row skip
     * shows only in a member's own name, a written statement shows only in an annotation value, and
     * a request constructed inside a body shows only as an outgoing dependency. A field carries the
     * first and third of those in a position no code-unit walk reaches at all, which is why fields
     * are walked beside code units rather than instead of them. A condition covering four of the
     * five reports a pass over the fifth.
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
                    + ", no field typed as one of " + REFUSED_OFFSET_PAGING_TYPE_NAMES
                    + ", no name carrying one of " + REFUSED_MEMBER_NAME_TOKENS + " or opening the '"
                    + REFUSED_COUNT_PROJECTION_KEYWORD + "' projection keyword, no annotation"
                    + " statement carrying one of " + REFUSED_QUERY_SYNTAX_TOKENS
                    + ", and no dependency on one of " + REFUSED_OFFSET_PAGING_TYPE_NAMES);
        }

        /**
         * Adds one event for each offending position on each DECLARED member of the candidate.
         *
         * <p>Assumptions: the walk is over ArchUnit's declared code units and declared fields and
         * never over its hierarchy-resolving accessors, and that choice is the difference between a
         * working gate and a permanently red build. The persistence supertypes DECLARE a find-all
         * member taking the refused request type and returning the refused result type, and a count
         * member besides, so resolving inherited members would report every repository extending
         * them as a violator of a surface none of them declares or calls. The field accessor used
         * here is the declared one for the same reason; its all-fields sibling resolves a supertype's
         * fields too.
         *
         * <p>Assumptions: the dependency test is applied once per class rather than once per member,
         * because ArchUnit models an outgoing dependency as a property of the origin class. It is
         * applied AFTER the two member walks so that a violation report reads declaration first and
         * body second, which is the order a maintainer repairs them in.
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
                reportRefusedAnnotationStatement(declaredCodeUnit, events);
            }
            for (JavaField declaredField : candidate.getFields()) {
                reportRefusedFieldType(declaredField, events);
                reportRefusedName(declaredField, events);
                reportRefusedAnnotationStatement(declaredField, events);
            }
            reportRefusedDependency(candidate, events);
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
         * Reports a declared field whose own type is one of the offset-paging types.
         *
         * <p>Assumptions: the field position is inspected because a record component compiles to
         * one. The request and response package's charter prohibits an ordinal paging COMPONENT, and
         * a component of a record is a private final field plus an accessor, so a predicate reading
         * code units alone would decide the accessor and leave the component's declared type
         * unread. The service package's charter names the field position explicitly for the
         * different reason that a class there can hold a paging request between two calls.
         *
         * <p>Assumptions: the RAW type is read, so a field declared as a paged result of some
         * element type is refused on the paged result rather than admitted because its element type
         * is innocuous.
         *
         * @param declaredField the declared field whose type is inspected
         * @param events the collector to add one event to when the field type is refused
         */
        private static void reportRefusedFieldType(JavaField declaredField, ConditionEvents events) {
            String fieldTypeName = declaredField.getRawType().getName();
            if (REFUSED_OFFSET_PAGING_TYPE_NAMES.contains(fieldTypeName)) {
                events.add(SimpleConditionEvent.violated(
                        declaredField,
                        declaredField.getFullName() + " declares a field of type " + fieldTypeName));
            }
        }

        /**
         * Reports each outgoing dependency of the candidate on an offset-paging type.
         *
         * <p>Assumptions: this is the only one of the five tests that reads a member's BODY rather
         * than its declaration, and it is the test that closes the gap the other four leave. A
         * service method may declare a compliant name, take a cursor, return a plain list and hold
         * no field, and still construct a paging request inside its body and hand it to a callee;
         * nothing in a signature, a field type, a name or an annotation records that. An outgoing
         * dependency does, because constructing a type, calling a static factory on it, casting to
         * it or declaring a local of it all leave that type in the constant pool of the class.
         *
         * <p>Assumptions: the dependency is reported against the CLASS rather than against a member,
         * because ArchUnit's dependency model names the origin class and describes the originating
         * location in its own text. The description is included verbatim for that reason -- it
         * carries the source line, which is what a maintainer needs when the offending construct is
         * inside a body and therefore invisible in any declaration.
         *
         * <p>Alternatives Considered: reporting only dependencies not already reported by the four
         * declaration tests, so that each event named exactly one cause. Rejected because the
         * de-duplication would have to model which declaration produced which dependency, and
         * getting that model subtly wrong would silently drop a real violation; a duplicate event
         * costs a reader one line, whereas a dropped event costs the gate its purpose.
         *
         * @param candidate the class whose outgoing dependencies are inspected
         * @param events the collector to add one event per refused dependency to
         */
        private static void reportRefusedDependency(JavaClass candidate, ConditionEvents events) {
            for (Dependency dependency : candidate.getDirectDependenciesFromSelf()) {
                String targetTypeName = dependency.getTargetClass().getName();
                if (REFUSED_OFFSET_PAGING_TYPE_NAMES.contains(targetTypeName)) {
                    events.add(SimpleConditionEvent.violated(
                            candidate,
                            candidate.getName() + " depends on " + targetTypeName + " -- "
                                    + dependency.getDescription()));
                }
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
         * <p>Assumptions: the parameter is the general member type rather than the code-unit type,
         * so one implementation decides the name of a method, a constructor and a field alike. The
         * three charters this predicate serves name the same vocabulary in all three positions --
         * the service charter's paragraph at lines 177 to 180 lists parameter, field, response
         * component and method name together -- so deciding them with two implementations would
         * create two places for one prohibition to be edited unevenly.
         *
         * @param declaredMember the declared method, constructor or field whose name is inspected
         * @param events the collector to add one event per matching token to
         */
        private static void reportRefusedName(JavaMember declaredMember, ConditionEvents events) {
            String declaredName = declaredMember.getName();
            String normalisedName = declaredName.toLowerCase(Locale.ROOT);
            for (String token : REFUSED_MEMBER_NAME_TOKENS) {
                if (normalisedName.contains(token)) {
                    events.add(SimpleConditionEvent.violated(
                            declaredMember,
                            declaredMember.getFullName()
                                    + " declares a name carrying the refused token '" + token
                                    + "'"));
                }
            }
            if (opensACountProjection(declaredName)) {
                events.add(SimpleConditionEvent.violated(
                        declaredMember,
                        declaredMember.getFullName() + " declares a name opening the '"
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
         * <p>Assumptions: the parameter is the general member type so that a field's annotations are
         * read as well as a method's. No annotation in this framework carries a query on a field, so
         * the field arm finds nothing today; it is present because the cost is one widened parameter
         * type and the alternative is a predicate whose coverage depends on a framework detail
         * remaining true.
         *
         * @param declaredMember the declared method, constructor or field whose annotations are read
         * @param events the collector to add one event per matching fragment to
         */
        private static void reportRefusedAnnotationStatement(
                JavaMember declaredMember, ConditionEvents events) {
            for (JavaAnnotation<? extends JavaMember> annotation : declaredMember.getAnnotations()) {
                for (Map.Entry<String, Object> property : annotation.getProperties().entrySet()) {
                    for (String statement : statementCandidatesOf(property.getValue())) {
                        reportRefusedQuerySyntax(
                                declaredMember, annotation, property.getKey(), statement, events);
                    }
                }
            }
        }

        /**
         * Reports each refused fragment found in one annotation property's statement.
         *
         * @param declaredMember the declared method, constructor or field carrying the annotation
         * @param annotation the annotation whose property holds the statement, named in the report
         *     so a reader knows which annotation to open
         * @param propertyName the annotation property the statement was read from
         * @param statement the statement text to inspect
         * @param events the collector to add one event per matching fragment to
         */
        private static void reportRefusedQuerySyntax(
                JavaMember declaredMember,
                JavaAnnotation<? extends JavaMember> annotation,
                String propertyName,
                String statement,
                ConditionEvents events) {
            String normalisedStatement = statement.toLowerCase(Locale.ROOT);
            for (String token : REFUSED_QUERY_SYNTAX_TOKENS) {
                if (normalisedStatement.contains(token)) {
                    events.add(SimpleConditionEvent.violated(
                            declaredMember,
                            declaredMember.getFullName() + " carries "
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
     * A synthetic subject declaring a FIELD typed as the paged result abstraction.
     *
     * <p>Assumptions: this is a class rather than an interface, because only a class can declare an
     * instance field, and the field position is what this subject exists to place under proof. It is
     * never instantiated and never referenced from production code.
     */
    private static final class PagedResultFieldSubject {

        /**
         * Declares a field typed as the paged result, which is the position under proof.
         *
         * <p>Assumptions: the field is neither read nor written anywhere, so no method body of this
         * subject mentions the type. That keeps the field declaration itself the only place the
         * refused type appears in a member, which is what the assertion on this subject checks by
         * requiring the report to name this field.</p>
         */
        private Page<String> lastPage;

        /**
         * Prevents instantiation of a subject that exists only to be inspected.
         */
        private PagedResultFieldSubject() {
        }
    }

    /**
     * A synthetic subject declaring a FIELD whose own name carries offset vocabulary.
     *
     * <p>Assumptions: the field's type is a primitive whole number, so this subject contributes no
     * outgoing dependency on any refused type and declares no refusable code unit. Its name is the
     * only refusable thing about it, which is what isolates the field-name test.
     */
    private static final class OffsetNamedFieldSubject {

        /**
         * Declares a stored row ordinal, saying so only in its own name.
         */
        private long rowOffset;

        /**
         * Prevents instantiation of a subject that exists only to be inspected.
         */
        private OffsetNamedFieldSubject() {
        }
    }

    /**
     * A synthetic subject constructing the concrete paging request inside a method BODY.
     *
     * <p>Assumptions: every declaration on this subject is compliant -- the method's name carries no
     * refused token, it takes no parameter, it returns an untyped value, it carries no annotation and
     * the class declares no field. The refused type appears only in the body, which is the shape the
     * four declaration tests admit and the dependency test exists for.
     */
    private static final class OffsetRequestDependencySubject {

        /**
         * Prevents instantiation of a subject that exists only to be inspected.
         */
        private OffsetRequestDependencySubject() {
        }

        /**
         * Assembles a paging request and returns it untyped, mentioning the type only in the body.
         *
         * <p>Assumptions: the concrete request type is constructed rather than the interface named,
         * because an interface has no constructor and a body therefore cannot mention it without
         * naming an implementation. The returned value is deliberately untyped so the declared return
         * type reveals nothing.</p>
         *
         * @return the assembled paging request, typed as an ordinary object by the declaration
         */
        private static Object buildRequestForACallee() {
            return PageRequest.of(1, 10);
        }
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
