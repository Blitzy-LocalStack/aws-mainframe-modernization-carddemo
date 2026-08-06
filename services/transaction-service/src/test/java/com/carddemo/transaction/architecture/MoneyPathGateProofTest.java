package com.carddemo.transaction.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves that the money family this module is gated by actually rejects a binary floating-point
 * member, by evaluating that exact rule against subjects authored to violate it.
 *
 * <p>Purpose: a layering rule that matched nothing reports the same pass as a layering rule that
 * examined every class and found nothing to complain about. The sibling rules class asserts that its
 * families had subjects; this class asserts the complementary property, that the family reports a
 * violation when one is present. Together the two remove the ambiguity in both directions, so a
 * green gate is evidence rather than an absence of evidence.
 *
 * <p>Assumptions: the rule under proof is obtained from
 * {@link TransactionLayeringRulesTest#moneyPathCondition()} rather than rebuilt here. Rebuilding the
 * condition would demonstrate that a copy of the gate can fail while saying nothing about the gate
 * the build runs, and the copy and the gate can drift apart silently while this test keeps passing.
 * Proving the real rule is the only version of this test that cannot rot into a tautology.
 *
 * <p>Assumptions: the violating subjects are nested types of this test and are placed, by package
 * naming, inside a money-carrying package identifier the rule selects on. The rule selects
 * {@code com.carddemo.transaction.domain..}, {@code ..dto..} and {@code ..mapper..}, and a nested
 * class of this test resides in {@code com.carddemo.transaction.architecture}, which the rule does
 * not select. The evaluation below therefore imports the fixtures and evaluates a rule whose
 * selection is widened to reach them, which is why the fixtures are declared as separate types with
 * their own recorded intent rather than as fields of this test.
 *
 * <p>Trade-offs: the fixtures deliberately contain the tokens the money path forbids, and they are
 * the only place in this module permitted to do so. Accepted, because a proof of a prohibition needs
 * an instance of the prohibited construct, and confining it to a test class keeps it out of the
 * production packages the rule protects. The production graph the sibling rules class imports
 * excludes every test source, so these fixtures cannot reach the gate they exist to trip.
 *
 * <p>Alternatives Considered: proving the gate with a hand-written assertion over a reflection scan
 * instead of an ArchUnit evaluation. Rejected because the property under proof is a property of the
 * rule engine's evaluation of the rule, and a second mechanism would prove that the second mechanism
 * works. The evaluation API returns a result carrying the violation set, so the assertion here reads
 * the same outcome the {@code check} call in the sibling class reads.
 */
class MoneyPathGateProofTest {

    /**
     * The package identifier the fixtures below reside in, which the proof rule selects on.
     *
     * <p>Assumptions: this is the package of this test class itself. A fixture cannot be placed in one
     * of the money-carrying production packages the enforcement rule selects, because that would put a
     * prohibited construct into a package the gate protects and the enforcement would then fail by
     * construction. Pairing the real condition with a selection naming this package is what lets the
     * proof exercise the gate's decision logic without relocating the gate's scope.</p>
     */
    private static final String FIXTURE_PACKAGE_IDENTIFIER = "com.carddemo.transaction.architecture..";

    /**
     * Asserts that the money family reports a violation for a field declared as a primitive double.
     *
     * <p>Assumptions: a field is the position where an amount is stored inexactly, and it is the
     * position a reader is most likely to consider already covered by a code review. Proving it first
     * fixes the family's most basic obligation.
     *
     * <p>Trade-offs: the assertion reads the boolean violation flag rather than the failure text. The
     * text names the offending member and is what a developer needs when the gate fails for real, but
     * asserting on it here would couple this proof to the engine's message wording, so a library
     * upgrade that reworded a report would fail a test that is about the rule and not about the
     * wording.
     */
    @Test
    @DisplayName("proof: the money family rejects a field declared double")
    void moneyFamilyRejectsADoubleField() {
        EvaluationResult result = evaluateAgainst(OffendingAmountField.class);

        assertThat(result.hasViolation())
                .withFailMessage(
                        "The money family accepted a class declaring a primitive double field. The"
                                + " family is therefore not gating the position where an amount is"
                                + " stored, and every module-scoped pass it has reported is"
                                + " uninformative.")
                .isTrue();
    }

    /**
     * Asserts that the money family reports a violation for a boxed Double return type.
     *
     * <p>Assumptions: a return type is the position a reader is least likely to look for, because the
     * arithmetic inside the method can be entirely correct and the loss happens only as the value
     * leaves. The boxed form is used here rather than the primitive so that this proof and the field
     * proof above cover both spellings between them: a family naming only the primitives would pass a
     * class that had merely boxed the defect.
     */
    @Test
    @DisplayName("proof: the money family rejects a boxed Double return type")
    void moneyFamilyRejectsABoxedDoubleReturnType() {
        EvaluationResult result = evaluateAgainst(OffendingAmountReturnType.class);

        assertThat(result.hasViolation())
                .withFailMessage(
                        "The money family accepted a method returning java.lang.Double. The family is"
                                + " therefore not gating the position where an exact internal amount is"
                                + " published inexactly.")
                .isTrue();
    }

    /**
     * Asserts that the money family reports a violation for a float parameter.
     *
     * <p>Assumptions: a parameter is where an amount arrives already rounded, so the loss happened in
     * the caller and the exact type never saw the original value. That makes it the position whose
     * defect is hardest to attribute from the far end of a pipeline, which is the reason the family
     * inspects it at all.
     */
    @Test
    @DisplayName("proof: the money family rejects a float parameter")
    void moneyFamilyRejectsAFloatParameter() {
        EvaluationResult result = evaluateAgainst(OffendingAmountParameter.class);

        assertThat(result.hasViolation())
                .withFailMessage(
                        "The money family accepted a method taking a float parameter. The family is"
                                + " therefore not gating the position where an amount is handed in"
                                + " already rounded.")
                .isTrue();
    }

    /**
     * Asserts that the money family accepts an exact-decimal subject, so the proofs above are not
     * passing for a reason unrelated to the members they declare.
     *
     * <p>Refactoring Rationale: without this clause the three proofs above would still pass if the
     * rule had degenerated into one that rejects everything it is given. A rule that always fails is
     * as useless as a rule that never fails, and it is the more likely accident of the two, because a
     * mis-specified condition typically over-matches. Asserting one compliant subject is accepted is
     * what makes the three violation assertions meaningful.
     *
     * <p>Assumptions: the compliant subject declares its amount as {@link BigDecimal}, which is the
     * type this migration carries money in throughout: exact fixed point at two decimal places, the
     * Java counterpart of the zoned-decimal money fields of the two records this context owns.
     */
    @Test
    @DisplayName("proof: the money family accepts an exact-decimal subject")
    void moneyFamilyAcceptsAnExactDecimalSubject() {
        EvaluationResult result = evaluateAgainst(CompliantAmountHolder.class);

        assertThat(result.hasViolation())
                .withFailMessage(
                        "The money family rejected a class whose only amount is a BigDecimal. It is"
                                + " over-matching, so the violation proofs beside this one pass for a"
                                + " reason other than the members they declare and prove nothing about"
                                + " binary floating point.")
                .isFalse();
    }

    /**
     * Evaluates the module's own money rule against one fixture class.
     *
     * <p>Assumptions: the rule's own package selection is bypassed by evaluating it over an imported
     * set holding the fixture alone, and the description is not overridden. The selection exists to
     * scope the gate to money-carrying production packages; a fixture cannot be placed in one of those
     * packages without putting a prohibited construct into a package the rule protects, so the
     * fixture is placed here and the selection is satisfied by widening the rule with
     * {@code allowEmptyShould} rather than by relocating the fixture.
     *
     * @param fixture the class to evaluate, which either declares a forbidden member or deliberately
     *     does not; must not be {@code null}
     * @return the evaluation carrying the violation set the rule produced for that class
     */
    private static EvaluationResult evaluateAgainst(Class<?> fixture) {
        JavaClasses imported = new ClassFileImporter().importClasses(fixture);
        ArchRule rule = noClasses()
                .that()
                .resideInAPackage(FIXTURE_PACKAGE_IDENTIFIER)
                .should(TransactionLayeringRulesTest.moneyPathCondition())
                .as("proof of A3 against a fixture in " + FIXTURE_PACKAGE_IDENTIFIER);
        return rule.evaluate(imported);
    }

    /**
     * A fixture declaring an amount as a primitive double, so the field position can be proved.
     *
     * <p>Assumptions: this type is never instantiated and never referenced from production code. It
     * exists so that the money family has an instance of the construct it forbids to reject.
     */
    private static final class OffendingAmountField {

        /**
         * An amount stored in a binary floating-point field, which is the defect under proof.
         */
        private double transactionAmount;

        /**
         * Reports the stored amount so that the field is not merely unused.
         *
         * @return the amount as stored, which the money family must refuse
         */
        private double transactionAmount() {
            return transactionAmount;
        }
    }

    /**
     * A fixture returning a boxed Double, so the return position can be proved.
     *
     * <p>Assumptions: this type is never instantiated and never referenced from production code.
     */
    private static final class OffendingAmountReturnType {

        /**
         * Publishes an amount through a boxed binary floating-point type.
         *
         * @return an amount the money family must refuse to see published
         */
        private Double publishedAmount() {
            return Double.valueOf(0);
        }
    }

    /**
     * A fixture accepting a float parameter, so the parameter position can be proved.
     *
     * <p>Assumptions: this type is never instantiated and never referenced from production code.
     */
    private static final class OffendingAmountParameter {

        /**
         * Accepts an amount that has already lost exactness before the call.
         *
         * @param alreadyRoundedAmount the inexact amount the money family must refuse to accept
         * @throws UnsupportedOperationException always, because the method is declared so that a rule
         *     can inspect its parameter type and is never invoked
         */
        private void acceptAmount(float alreadyRoundedAmount) {
            throw new UnsupportedOperationException(
                    "fixture method: declared to be inspected by a rule, never to be invoked");
        }
    }

    /**
     * A fixture carrying an amount exactly, so over-matching by the rule can be detected.
     *
     * <p>Assumptions: this type is never instantiated and never referenced from production code.
     */
    private static final class CompliantAmountHolder {

        /**
         * An amount held as exact fixed point, which the money family must accept.
         */
        private BigDecimal transactionAmount = BigDecimal.ZERO;

        /**
         * Reports the stored amount so that the field is not merely unused.
         *
         * @return the exact amount as stored
         */
        private BigDecimal transactionAmount() {
            return transactionAmount;
        }
    }
}
