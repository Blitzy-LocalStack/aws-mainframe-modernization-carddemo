package com.carddemo.transaction.architecture;

import static com.tngtech.archunit.base.DescribedPredicate.describe;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.conditions.ArchConditions.dependOnClassesThat;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;

/**
 * Gates this context's list screens against offset pagination and proves, in the same run, that the
 * gate rejects an offset-paging subject.
 *
 * <p>Purpose: the list screens of this context page by key and never by offset, and that is what the
 * baseline does rather than an improvement on it. {@code app/cbl/COTRN00C.cbl} fills ten screen rows
 * under a loop that runs until its index reaches 11 at line 297 and then performs an eleventh read at
 * line 308 whose outcome alone sets the next-page indicator, and {@code app/cbl/COCRDLIC.cbl} carries
 * the browse cursor across a screen turn as a last-key and first-key pair at lines 229 to 244. Under
 * offset paging, concurrent inserts silently skip and repeat rows, because the offset is counted
 * against a result set that has changed, so substituting one would change observable behaviour and
 * not merely the implementation.
 *
 * <p>Assumptions: this boundary is owned by this module rather than shared, which is why it is gated
 * here and not in the shared architecture artifact. Not every context in the migration browses, and
 * the shared artifact is a closed set that admits no second rule class, so a module-scoped invariant
 * has nowhere there to live.
 *
 * <p>Assumptions: the forbidden constructs are named as types rather than inferred from a method
 * name, because the offset is emitted by the abstraction and not by the naming. A paging request
 * carries a page number and a page size and its query emits a SQL {@code OFFSET} clause, and a
 * sliced result is the same request shape with the count query omitted, so both are the construct
 * this context declines. The transfer object this context returns instead is the keyset envelope
 * published by the shared kernel, which carries the items together with a first key, a last key and a
 * next-page flag discovered by fetching one row more than the page size -- which is exactly how the
 * reference discovers it.
 *
 * <p>Trade-offs: this class both enforces and proves, where the money family is enforced in the
 * sibling rules class and proved here in a class of its own. Accepted, and the asymmetry is
 * deliberate: the money family is one of the three the shared charter names, so it belongs with its
 * two siblings, whereas this boundary has no shared counterpart and splitting it across two files
 * would put a rule in one place and the only evidence it bites in another.
 *
 * <p>Alternatives Considered: removing the paging abstraction from the module's dependency set
 * instead of gating it, so the construct could not be written at all. Rejected because the same
 * artifact supplies the repository abstractions this module's repositories extend, so removing it
 * would remove the persistence layer with it. A gate is the only form of this prohibition that leaves
 * the module buildable.
 */
class KeysetPaginationGateProofTest {

    /**
     * The package root of every class this module owns.
     */
    private static final String MODULE_ROOT = "com.carddemo.transaction";

    /**
     * The packages in which a list is requested, executed or published in this context.
     *
     * <p>Assumptions: a paging construct could only enter through one of these three. A repository
     * declares the query, a service composes the page, and a transfer object publishes it. Naming the
     * three rather than the whole module keeps the gate off the configuration package, where a Spring
     * Data type may legitimately appear in a repository-scanning declaration.</p>
     */
    private static final List<String> LIST_HANDLING_PACKAGE_IDENTIFIERS = List.of(
            MODULE_ROOT + ".repository..", MODULE_ROOT + ".service..", MODULE_ROOT + ".dto..");

    /**
     * The fully qualified names of the offset-paging constructs this context declines.
     *
     * <p>Assumptions: the request abstraction and both result abstractions are named. A page result
     * carries a total count and therefore an offset-addressable position; a slice omits the count but
     * is produced from the same request, so admitting it would readmit the offset by the back door.</p>
     */
    private static final List<String> FORBIDDEN_OFFSET_PAGING_TYPE_NAMES = List.of(
            Pageable.class.getName(), Page.class.getName(), Slice.class.getName());

    /**
     * The package identifier the fixtures below reside in, which the proof rule selects on.
     *
     * <p>Assumptions: this is the package of this test class itself. A fixture cannot be placed in one
     * of the list-handling production packages the enforcement rule selects, because the enforcement
     * would then evaluate it and fail by construction, so the proof pairs the same condition with a
     * selection naming this package instead.</p>
     */
    private static final String FIXTURE_PACKAGE_IDENTIFIER = MODULE_ROOT + ".architecture..";

    /**
     * The predicate that recognises an offset-paging construct by its fully qualified name.
     *
     * <p>Assumptions: the match is by exact name rather than by assignability, so a repository
     * interface that merely extends a Spring Data base type is not caught while a declaration that
     * actually names one of the three is. That distinction matters because this module's repositories
     * do extend a Spring Data base interface, legitimately, and only their paging shape is at issue.</p>
     */
    private static final DescribedPredicate<JavaClass> IS_AN_OFFSET_PAGING_CONSTRUCT = describe(
            "be one of " + FORBIDDEN_OFFSET_PAGING_TYPE_NAMES,
            candidate -> FORBIDDEN_OFFSET_PAGING_TYPE_NAMES.contains(candidate.getName()));

    /**
     * The condition this gate decides violations from, held separately so the proof can evaluate it.
     *
     * <p>Trade-offs: the condition is declared once and paired with two different selections, one for
     * enforcement over production packages and one for the proof over this test's own package. The
     * alternative was to write the prohibition twice; it was rejected because two declarations can drift
     * apart, and the proof would then keep passing while the enforcement had changed.</p>
     */
    private static final ArchCondition<JavaClass> DEPENDS_ON_AN_OFFSET_PAGING_CONSTRUCT =
            dependOnClassesThat(IS_AN_OFFSET_PAGING_CONSTRUCT);

    /**
     * The rule that no list-handling class of this module touches an offset-paging construct.
     */
    private static final ArchRule NO_OFFSET_PAGING_IN_LIST_HANDLING = noClasses()
            .that()
            .resideInAnyPackage(LIST_HANDLING_PACKAGE_IDENTIFIERS.toArray(String[]::new))
            .should(DEPENDS_ON_AN_OFFSET_PAGING_CONSTRUCT)
            .as("K1: no class in " + LIST_HANDLING_PACKAGE_IDENTIFIERS + " depends on "
                    + FORBIDDEN_OFFSET_PAGING_TYPE_NAMES);

    /**
     * The same condition paired with a selection naming this test's own package, for the proof.
     */
    private static final ArchRule PROOF_OF_NO_OFFSET_PAGING = noClasses()
            .that()
            .resideInAPackage(FIXTURE_PACKAGE_IDENTIFIER)
            .should(DEPENDS_ON_AN_OFFSET_PAGING_CONSTRUCT)
            .as("proof of K1 against a fixture in " + FIXTURE_PACKAGE_IDENTIFIER);

    /**
     * Enforces the boundary against this module's compiled production classes.
     *
     * <p>Assumptions: the subject set is asserted non-empty before the rule is checked, because a rule
     * whose selection matched nothing reports the same pass as a rule that examined every class and
     * found nothing. This module declares three repositories, so an empty selection means a package
     * was renamed out of the matched shape rather than that there was nothing to find.
     *
     * <p>Trade-offs: the failure text names package identifiers and type names and nothing read at run
     * time, because a gate's diagnostic is read from build logs retained more widely than the build.
     */
    @Test
    @DisplayName("K1: no repository, service or transfer object of this module pages by offset")
    void listHandlingDeclaresNoOffsetPagingConstruct() {
        JavaClasses production = importProductionClasses();

        assertThat(production.that(resideInAnyPackage(
                LIST_HANDLING_PACKAGE_IDENTIFIERS.toArray(String[]::new))))
                .withFailMessage(
                        "No production class was imported from the list-handling packages %s, so this"
                                + " gate would pass without evaluating anything.",
                        LIST_HANDLING_PACKAGE_IDENTIFIERS)
                .isNotEmpty();

        NO_OFFSET_PAGING_IN_LIST_HANDLING.check(production);
    }

    /**
     * Proves the gate rejects a repository-shaped subject that accepts a paging request.
     *
     * <p>Refactoring Rationale: the enforcement above is a negative assertion, and a negative
     * assertion over a rule that can never fire is indistinguishable from a boundary that holds. That
     * ambiguity is the reason the likeliest repair a hurried reader reaches for when a layering gate
     * fails noisily is to stop running it. Producing the evidence in the build removes the ambiguity
     * in the direction that matters: a proof that stops failing its violating subject is itself a
     * failure, so the erosion becomes visible instead of quiet.
     *
     * <p>Assumptions: the fixture is evaluated through the same condition object the enforcement above
     * checks, paired with a selection naming this test's own package. What is reused is the part that
     * decides violations; what is proof-local is a package identifier a reader can verify by eye.
     * Rebuilding the condition for the proof would demonstrate only that a copy of the gate can fail.
     */
    @Test
    @DisplayName("proof: the gate rejects a subject accepting a paging request")
    void gateRejectsAPagingRequestParameter() {
        EvaluationResult result = evaluateAgainst(OffendingOffsetPagedFinder.class);

        assertThat(result.hasViolation())
                .withFailMessage(
                        "The gate accepted a class that accepts a paging request and returns a paged"
                                + " result. It is therefore not preventing offset pagination, and the"
                                + " enforcement assertion beside this one proves nothing.")
                .isTrue();
    }

    /**
     * Proves the gate accepts a keyset-shaped subject, so the proof above is not passing for an
     * unrelated reason.
     *
     * <p>Refactoring Rationale: without this clause the proof above would still pass if the rule had
     * degenerated into one that rejects everything. A rule that always fails is as useless as one that
     * never fails, and it is the more likely accident of the two, because a mis-specified dependency
     * predicate typically over-matches.
     *
     * <p>Assumptions: the compliant fixture takes a key and a limit and returns a list, which is the
     * shape a keyset query has: seek past the last key seen, take one row more than the page holds, and
     * let the extra row decide whether a next page exists.
     */
    @Test
    @DisplayName("proof: the gate accepts a keyset-shaped finder")
    void gateAcceptsAKeysetShapedFinder() {
        EvaluationResult result = evaluateAgainst(CompliantKeysetFinder.class);

        assertThat(result.hasViolation())
                .withFailMessage(
                        "The gate rejected a finder that takes a key and a limit and returns a list. It"
                                + " is over-matching, so the violation proof beside this one passes for"
                                + " a reason other than the paging construct it declares.")
                .isFalse();
    }

    /**
     * Evaluates the gate against one fixture class.
     *
     * @param fixture the class to evaluate, which either depends on an offset-paging construct or
     *     deliberately does not; must not be {@code null}
     * @return the evaluation carrying the violation set the rule produced for that class
     */
    private static EvaluationResult evaluateAgainst(Class<?> fixture) {
        JavaClasses imported = new ClassFileImporter().importClasses(fixture);
        return PROOF_OF_NO_OFFSET_PAGING.evaluate(imported);
    }

    /**
     * Imports this module's compiled production classes, excluding every test source.
     *
     * <p>Assumptions: the test exclusion is what keeps the violating fixture below out of the
     * enforcement above. Without it the gate would fail by construction, because a fixture authored to
     * break a rule is still a class on the module's test classpath.
     *
     * @return the module's production classes, never {@code null}
     */
    private static JavaClasses importProductionClasses() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(MODULE_ROOT)
                .as("production classes of " + MODULE_ROOT + " visible to the executing module");
    }

    /**
     * A fixture shaped like a repository finder that pages by offset, so the gate can reject it.
     *
     * <p>Assumptions: this type is never instantiated and never referenced from production code. It
     * exists so that the gate has an instance of the construct it forbids to reject.
     */
    private static final class OffendingOffsetPagedFinder {

        /**
         * Accepts a paging request and returns a paged result, which is the defect under proof.
         *
         * @param pageRequest the offset-addressed request the gate must refuse
         * @return never returns, because the fixture is declared to be inspected rather than invoked
         * @throws UnsupportedOperationException always, because the method is declared so that a rule
         *     can inspect its signature and is never invoked
         */
        private Page<String> findAll(Pageable pageRequest) {
            throw new UnsupportedOperationException(
                    "fixture method: declared to be inspected by a rule, never to be invoked");
        }
    }

    /**
     * A fixture shaped like a keyset finder, so over-matching by the gate can be detected.
     *
     * <p>Assumptions: this type is never instantiated and never referenced from production code.
     */
    private static final class CompliantKeysetFinder {

        /**
         * Seeks past the last key seen and takes a bounded number of rows.
         *
         * @param lastKeySeen the key the previous page ended on, or {@code null} for the first page
         * @param limit the number of rows to take, which a caller sets to one more than the page holds
         *     so the extra row decides whether a next page exists
         * @return never returns, because the fixture is declared to be inspected rather than invoked
         * @throws UnsupportedOperationException always, because the method is declared so that a rule
         *     can inspect its signature and is never invoked
         */
        private List<String> findAfter(String lastKeySeen, int limit) {
            throw new UnsupportedOperationException(
                    "fixture method: declared to be inspected by a rule, never to be invoked");
        }
    }
}
