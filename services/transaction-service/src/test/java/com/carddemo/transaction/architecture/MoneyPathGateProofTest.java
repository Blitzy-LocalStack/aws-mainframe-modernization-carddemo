package com.carddemo.transaction.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves that family A3, the money-path gate of this module, refuses a binary floating-point
 * declaration in each of the three member positions it polices and accepts an exact decimal
 * declaration in all of them.
 *
 * <p>A gate that cannot fail is not a gate. Family A3 reports the same quiet pass whether it
 * inspected every class this module compiles and found nothing to object to or resolved no subject
 * at all, and the two outcomes print one indistinguishable line in a build log. This class removes
 * that ambiguity in the direction that matters, by handing the gate declarations authored to be
 * refused and asserting that each one is refused, so a green A3 becomes evidence the build produced
 * rather than an inference from the gate being present.
 *
 * <p>Assumptions: nothing in the migration requirements asks for this file. It exists because of
 * Rule 1, this project's single user-specified rule, whose Validation Gate is not met by a guardrail
 * that is merely installed. An unfailable gate satisfies the letter of a
 * documentation-and-verification obligation while defeating its intent: it certifies nothing, and
 * its report is indistinguishable from that of a gate which works. The reference suite reaches the
 * same conclusion about its own riskiest switch, describing the golden-master update gate at
 * {@code tests/README.md} lines 526 to 531 as multiply guarded so that it cannot silently bless a
 * regression.
 *
 * <p>Assumptions: Rule 1 binds this file on three independent grounds, and none of the three leans
 * on the other two. Its own scope clause reaches every function, class and module entry point and
 * carries no test exemption anywhere in its text. The Checkstyle documentation gate is turned on
 * over test sources by {@code includeTestSourceDirectory} in {@code services/pom.xml}, and
 * {@code config/checkstyle/suppressions.xml} declines to narrow it here, recording in its list of
 * rejected entries that the test tree as a whole is never suppressed because tests are in scope for
 * Rule 1 and because the house convention requires a docstring on every test, fixture builder,
 * helper, mock and runner routine. That convention states the obligation itself at
 * {@code tests/README.md} section 12, whose heading is at line 516 and whose closing blockquote at
 * lines 544 to 549 makes a docstring naming purpose, parameters, returns and exceptions, together
 * with a why-comment in one of the four named categories, a hard review gate.
 *
 * <p>Assumptions: the catching mechanism is the A3 money-path rule in
 * {@link TransactionLayeringRulesTest}, obtained through
 * {@link TransactionLayeringRulesTest#moneyPathCondition()} and evaluated here as the very object
 * that class enforces with. No part of this file decides what counts as a violation.
 *
 * <p>Alternatives Considered: re-expressing the money predicate locally, which would need no shared
 * member at all. Rejected because two copies of one invariant drift apart without anything
 * announcing it, and a proof over a copy certifies the copy rather than the gate the build runs --
 * it would go on passing while the enforced rule rotted, which is the failure this class exists to
 * make impossible. The house precedent is the reference suite's instruction that a record layout be
 * resolved through the compiler copybook path, at {@code tests/README.md} section 12 item 4, lines
 * 540 to 542, which requires a layout be kept single-sourced from {@code app/cpy/} instead of
 * duplicated, for the same reason.
 *
 * <p>Alternatives Considered: declaring the violating subjects under {@code src/main}, where they
 * would read as ordinary code. Rejected on a mechanical consequence rather than on taste. Family A3
 * imports production classes with test sources excluded, so a violator under {@code src/main} would
 * enter the enforced graph, A3 would reject it exactly as designed, and the build this proof exists
 * to protect would fail over a class authored to be rejected.
 *
 * <p>Assumptions: the violating subjects are therefore private static nested types of this class, in
 * the test tree, where the enforced graph cannot reach them and this proof can. That placement is
 * load-bearing in both directions and is the least obvious arrangement in the file: the enforced
 * graph excludes them twice over, once by the importer's test-source exclusion and once by the
 * architecture package identifier, while this proof imports them by class literal and so names its
 * subject set exactly. They are never instantiated and never referenced from production code, so the
 * only thing editing one can do is make this proof fail.
 *
 * <p>Trade-offs: the arrangement means a small amount of synthetic code declaring the very construct
 * this module forbids ships in the repository permanently. Accepted, because a proof of a
 * prohibition needs an instance of the prohibited construct, and there is no other way to show that
 * the gate discriminates rather than merely exists. The alternative on offer is not a smaller file
 * but an unverified gate.
 *
 * <p>Assumptions: exact fixed point is not a preference here, it is the shape of the data this
 * context owns. {@code TRAN-AMT PIC S9(09)V99} at {@code app/cpy/CVTRA05Y.cpy} line 10, inside the
 * 350-byte {@code TRAN-RECORD}, and {@code TRAN-CAT-BAL PIC S9(09)V99} at
 * {@code app/cpy/CVTRA01Y.cpy} line 9, inside the 50-byte {@code TRAN-CAT-BAL-RECORD}, are both
 * zoned decimal with a sign overpunch at exact scale 2, and both are carried onward as
 * {@code NUMERIC(11,2)} and held in Java as {@link BigDecimal} -- {@code tranAmt} on
 * {@link com.carddemo.transaction.domain.Transaction} and {@code balance} on
 * {@link com.carddemo.transaction.domain.TransactionCategoryBalance}. An IEEE-754 binary
 * floating-point type cannot represent most exact cent values, so an amount routed through one is
 * returned plausible and slightly wrong. The baseline accumulates those fields with exact decimal
 * arithmetic -- {@code ADD DALYTRAN-AMT TO TRAN-CAT-BAL} at {@code app/cbl/CBTRN02C.cbl} lines 508
 * and 527 and {@code ADD DALYTRAN-AMT TO ACCT-CURR-BAL} at line 547 -- so a binary floating-point
 * member anywhere on the Java money path surfaces at the far end of the pipeline as an unexplained
 * one-cent difference against a golden master rather than as an error at the conversion that caused
 * it. That specific silent failure mode is what family A3 prevents and what this class proves it
 * still catches.
 */
class MoneyPathGateProofTest {

    /**
     * The package identifier this proof's own subjects reside in, which its rule selects on.
     *
     * <p>Assumptions: this is the package of this test class, so the nested subjects below satisfy
     * it. A subject cannot be placed in a package family A3 protects without putting the prohibited
     * construct into the production tree, at which point the enforcement would fail by construction,
     * so the selection is proof-local while the condition is shared. What stays here is a literal a
     * reader verifies by eye; what comes from the enforced gate is the part that decides violations.
     * The trailing wildcard matches this package and any beneath it, and a nested type reports the
     * package of its enclosing class, which is what brings the subjects below into range.</p>
     */
    private static final String PROOF_PACKAGE_IDENTIFIER = "com.carddemo.transaction.architecture..";

    /**
     * The enforced A3 condition, paired with a selection naming this proof's own package.
     *
     * <p>Assumptions: the pairing is {@code noClasses} and it has to be, because the polarity of the
     * two halves must agree. The enforced condition reports an offending member position as
     * SATISFIED rather than as violated, since ArchUnit 1.4.2 inverts every event produced by a
     * condition a {@code noClasses} rule owns. Pairing that same condition with {@code classes}
     * here would invert this proof's meaning: it would find the offending declaration and report no
     * violation, so every assertion below would fail while the gate was working correctly. The
     * enforced rule at {@code TransactionLayeringRulesTest} lines 421 to 430 uses the same pairing,
     * which is what makes this rule differ from it in selection alone.</p>
     *
     * <p>Trade-offs: one rule instance is held for all seven assertions rather than one being built
     * per assertion. Accepted because it makes the object under proof unmistakably single: every
     * assertion below evaluates this rule, and this rule carries the condition instance the enforced
     * gate carries, so there is no arrangement in which the proof and the gate could be exercising
     * two conditions that merely agree today. An ArchUnit rule holds no state about the classes it
     * is checked against, so reuse across subject sets is sound.</p>
     */
    private static final ArchRule PROOF_OF_THE_MONEY_PATH_GATE = noClasses()
            .that()
            .resideInAPackage(PROOF_PACKAGE_IDENTIFIER)
            .should(TransactionLayeringRulesTest.moneyPathCondition())
            .as("proof of A3 against a subject in " + PROOF_PACKAGE_IDENTIFIER);

    /**
     * Asserts that family A3 refuses a field declared as a primitive double.
     *
     * <p>Assumptions: a field is the position at which an amount is stored inexactly, and it is the
     * position a reader is most likely to assume a code review already covers. Proving it separately
     * fixes the family's most basic obligation before the harder positions are examined.</p>
     */
    @Test
    @DisplayName("proof: A3 refuses a field declared double")
    void moneyPathGateRefusesAPrimitiveDoubleField() {
        JavaClasses subject = subjectSetOf(InexactPrimitiveDoubleField.class);

        // WHY : Assumptions: the wrapped call is check, which is the call the enforced rule makes at
        //   TransactionLayeringRulesTest line 681, and ArchUnit signals a violation by throwing
        //   AssertionError from it. Wrapping that exact call is what makes this a proof of the gate
        //   rather than of a second mechanism reimplemented here, and a silent return would mean the
        //   stored-amount position is ungated and every module-wide pass A3 has reported over it is
        //   uninformative.
        AssertionError refusal = assertThrows(
                AssertionError.class,
                () -> PROOF_OF_THE_MONEY_PATH_GATE.check(subject),
                "A3 accepted a class declaring a primitive double field, so it is not gating the"
                        + " position at which an amount is stored.");

        // WHY : Assumptions: the diagnostic is asserted to name the offending member and its declared
        //   type, because a gate that refuses a declaration without saying which member to change
        //   sends a reader to read the whole class. Asserting the member name rather than the whole
        //   sentence keeps this coupled to the diagnostic's usefulness and not to its wording.
        assertThat(refusal.getMessage()).contains("postedAmount", "double");
    }

    /**
     * Asserts that family A3 refuses a field declared as a boxed Float.
     *
     * <p>Assumptions: the boxed spelling is proved separately from the primitive one because a family
     * naming only the two primitives would accept a class that had merely boxed the defect, and
     * boxing is what an author reaches for when a money field has to be nullable. The declared type
     * is the whole of what changes between this subject and the one above.</p>
     */
    @Test
    @DisplayName("proof: A3 refuses a field declared java.lang.Float")
    void moneyPathGateRefusesABoxedFloatField() {
        JavaClasses subject = subjectSetOf(InexactBoxedFloatField.class);

        // WHY : Assumptions: this subject differs from the primitive-field subject in its declared
        //   type alone, so a refusal here attributes to the boxed spelling and to nothing else. A
        //   silent return would mean the family reads primitives only, and the cheapest way past it
        //   would be the one an author would find first.
        AssertionError refusal = assertThrows(
                AssertionError.class,
                () -> PROOF_OF_THE_MONEY_PATH_GATE.check(subject),
                "A3 accepted a class declaring a java.lang.Float field, so the boxed spelling is a"
                        + " way past the family.");

        assertThat(refusal.getMessage()).contains("accruedInterestAmount", "java.lang.Float");
    }

    /**
     * Asserts that family A3 refuses a primitive double in the second of three parameter positions.
     *
     * <p>Assumptions: this is the highest-value assertion in the file, and it is the reason family A3
     * is written as an explicit condition that walks the parameter list by index instead of as the
     * fluent predicate that matches a callable's raw parameter types. That predicate matches an
     * ENTIRE parameter list in ArchUnit 1.4.2, so used as a contains-any test it accepts a callable
     * whose second parameter of three is a double, and the family would then report green over
     * precisely the declaration it exists to refuse. A parameter is also the position whose defect is
     * hardest to attribute downstream, because the amount arrives already rounded and the exact type
     * never saw the original value.</p>
     *
     * <p>Assumptions: the diagnostic reports this position as parameter 1 rather than parameter 2,
     * because the enforced condition walks the list from index zero. The index is asserted as well as
     * the member, since naming which of three parameters has to change is the whole benefit of
     * inspecting the list positionally.</p>
     */
    @Test
    @DisplayName("proof: A3 refuses a double in the second of three parameter positions")
    void moneyPathGateRefusesAPrimitiveDoubleInTheSecondOfThreeParameterPositions() {
        JavaClasses subject = subjectSetOf(InexactSecondOfThreeParameters.class);

        // WHY : Assumptions: a silent return here would not mean the family is merely incomplete, it
        //   would mean the family is defeated by adding a parameter either side of the amount, which
        //   is the ordinary shape of a posting call that carries a card number and a transaction
        //   identifier alongside the value. That is the specific escape the positional walk closes.
        AssertionError refusal = assertThrows(
                AssertionError.class,
                () -> PROOF_OF_THE_MONEY_PATH_GATE.check(subject),
                "A3 accepted a callable whose second parameter of three is a primitive double, so the"
                        + " family is defeated by surrounding an inexact amount with other"
                        + " parameters.");

        assertThat(refusal.getMessage()).contains("postAmount", "parameter 1", "double");
    }

    /**
     * Asserts that family A3 refuses a primitive float in the last of three parameter positions.
     *
     * <p>Assumptions: the last position is proved alongside the middle one because an off-by-one walk
     * of the parameter list fails at exactly one end, and a single interior case cannot tell a
     * correct walk from one that stops before the final element. The diagnostic reports this position
     * as parameter 2, again counting from zero.</p>
     */
    @Test
    @DisplayName("proof: A3 refuses a float in the last of three parameter positions")
    void moneyPathGateRefusesAPrimitiveFloatInTheLastOfThreeParameterPositions() {
        JavaClasses subject = subjectSetOf(InexactLastOfThreeParameters.class);

        // WHY : Assumptions: this subject pairs with the middle-position subject to bound the walk at
        //   both ends. A silent return here with the middle case still refused would localise the
        //   defect to the final index, which is where a loop bound is wrong when it is wrong at all.
        AssertionError refusal = assertThrows(
                AssertionError.class,
                () -> PROOF_OF_THE_MONEY_PATH_GATE.check(subject),
                "A3 accepted a callable whose final parameter of three is a primitive float, so the"
                        + " parameter walk does not reach the last position.");

        assertThat(refusal.getMessage()).contains("reverseAmount", "parameter 2", "float");
    }

    /**
     * Asserts that family A3 refuses a method returning a primitive float.
     *
     * <p>Assumptions: a return type is the position a reader is least likely to look for, because the
     * arithmetic inside the method can be entirely correct and exactness is lost only as the value
     * leaves. That makes it the position at which an otherwise sound calculation is published
     * wrongly.</p>
     */
    @Test
    @DisplayName("proof: A3 refuses a method returning float")
    void moneyPathGateRefusesAPrimitiveFloatReturnType() {
        JavaClasses subject = subjectSetOf(InexactPrimitiveFloatReturn.class);

        // WHY : Assumptions: the return position is asserted independently of the field and parameter
        //   positions because the enforced condition reads it from a different property of the code
        //   unit, so one position working says nothing about another. A silent return here would leave
        //   an exact internal amount publishable inexactly with the whole method body still correct.
        AssertionError refusal = assertThrows(
                AssertionError.class,
                () -> PROOF_OF_THE_MONEY_PATH_GATE.check(subject),
                "A3 accepted a method returning a primitive float, so it is not gating the position at"
                        + " which an exact internal amount is published.");

        assertThat(refusal.getMessage()).contains("publishedAmount", "float");
    }

    /**
     * Asserts that family A3 refuses a method returning a boxed Double.
     *
     * <p>Assumptions: the boxed return completes the four declared forms across the three positions,
     * so that between the six subjects every combination of spelling and position the family names is
     * exercised at least once rather than assumed to follow from a neighbour.</p>
     */
    @Test
    @DisplayName("proof: A3 refuses a method returning java.lang.Double")
    void moneyPathGateRefusesABoxedDoubleReturnType() {
        JavaClasses subject = subjectSetOf(InexactBoxedDoubleReturn.class);

        // WHY : Assumptions: a boxed return is what an author writes when a published amount has to
        //   carry absence, so this is the boxed spelling's most plausible arrival point rather than a
        //   symmetrical extra case. A silent return would leave that arrival point ungated.
        AssertionError refusal = assertThrows(
                AssertionError.class,
                () -> PROOF_OF_THE_MONEY_PATH_GATE.check(subject),
                "A3 accepted a method returning java.lang.Double, so a nullable published amount is a"
                        + " way past the family.");

        assertThat(refusal.getMessage()).contains("publishedAmount", "java.lang.Double");
    }


    /**
     * Asserts that family A3 accepts a counterpart whose only monetary members are exact decimals.
     *
     * <p>Refactoring Rationale: without this half, all six refusals above would still pass if the
     * family had degenerated into one that refuses everything handed to it. A rule that always fails
     * is as uninformative as one that never fails, and it is the likelier accident of the two, because
     * a mis-specified condition over-matches more often than it under-matches. Asserting that one
     * compliant subject is accepted is what turns the six refusals into evidence of discrimination
     * rather than evidence of indiscriminate rejection.</p>
     *
     * <p>Assumptions: the compliant counterpart declares an amount in all three positions the family
     * polices -- a field, the middle of three parameters and a return -- so its acceptance covers the
     * same surface the refusals cover rather than a narrower one. Every one of those members is
     * {@link BigDecimal}, which is the type this migration carries money in throughout and the Java
     * counterpart of the two zoned-decimal record fields named in this class's own documentation.</p>
     */
    @Test
    @DisplayName("proof: A3 accepts a counterpart whose monetary members are all BigDecimal")
    void moneyPathGateAcceptsAnExactDecimalCounterpart() {
        JavaClasses subject = subjectSetOf(ExactDecimalCounterpart.class);

        // WHY : Assumptions: acceptance is asserted as the absence of the throw rather than by reading
        //   a violation flag, so that this half and the six refusals above exercise one and the same
        //   call. A throw here would mean the family refuses an exact decimal, which would make every
        //   refusal above pass for a reason unrelated to binary floating point and would prove
        //   nothing about the money path at all.
        assertDoesNotThrow(
                () -> PROOF_OF_THE_MONEY_PATH_GATE.check(subject),
                "A3 refused a class whose only monetary members are BigDecimal, so it is"
                        + " over-matching and the refusals beside this assertion prove nothing about"
                        + " binary floating point.");
    }

    /**
     * Imports exactly one synthetic subject, so that the set a proof evaluates over is unambiguous.
     *
     * <p>Assumptions: the subject is named by class literal rather than by scanning a package, so the
     * imported set holds that class alone and a refusal cannot have been caused by a neighbour. This
     * is also why no assertion in this file needs {@code allowEmptyShould}: a set built from a class
     * literal is never empty, and a money rule that reported green over an empty set would be worse
     * than no rule, which is exactly the ambiguity this class exists to remove.</p>
     *
     * @param subject the synthetic type to evaluate the money-path rule against
     * @return an imported set holding that type alone
     */
    private static JavaClasses subjectSetOf(Class<?> subject) {
        return new ClassFileImporter().importClasses(subject);
    }

    /**
     * A subject declaring a stored amount as a primitive double, isolating the field position.
     *
     * <p>Assumptions: the type declares no callable of its own, so the only event the enforced
     * condition can raise against it comes from the field. That isolation is deliberate: a subject
     * that also returned an inexact amount would still be refused with the field inspection broken,
     * and the assertion would then prove the wrong position.</p>
     */
    private static final class InexactPrimitiveDoubleField {

        // WHY : Assumptions: this member exists to be refused, and it is one of the very few places in
        //   this module permitted to name a binary floating-point type. It is never read, because
        //   reading it would need an accessor whose return type would raise a second event from a
        //   different position and blur what the field assertion attributes a refusal to.
        private double postedAmount;
    }

    /**
     * A subject declaring a stored amount as a boxed Float, isolating the boxed field spelling.
     *
     * <p>Assumptions: this type differs from the primitive-field subject in its declared type alone,
     * so a refusal attributes to the boxed spelling rather than to any other property of the
     * subject.</p>
     */
    private static final class InexactBoxedFloatField {

        // WHY : Assumptions: the boxed form is declared because a family naming only the primitives
        //   would accept it, and boxing is the ordinary way an author makes a money field nullable.
        //   Left unread for the same reason as the primitive subject above.
        private Float accruedInterestAmount;
    }

    /**
     * A subject declaring an inexact amount in the second of three parameter positions.
     *
     * <p>Assumptions: the surrounding parameters are the reason this subject exists. Three parameters
     * with the amount in the middle is the shape the fluent raw-parameter-types predicate accepts,
     * because it matches an entire parameter list rather than any one element, so this subject is the
     * one that separates a positional walk from a whole-list match.</p>
     */
    private static final class InexactSecondOfThreeParameters {

        /**
         * Accepts an amount that lost exactness in the caller, between two exact-typed parameters.
         *
         * @param cardNumber the card the amount would post against, present so the amount is not the
         *     first parameter
         * @param postedAmount the inexact amount the money path must refuse in this position
         * @param transactionId the transaction the amount would post under, present so the amount is
         *     not the last parameter
         * @throws UnsupportedOperationException always, because the method is declared to be inspected
         *     by a rule and is never invoked
         */
        private void postAmount(String cardNumber, double postedAmount, String transactionId) {
            throw new UnsupportedOperationException(
                    "synthetic subject: declared to be inspected by a rule, never to be invoked");
        }
    }

    /**
     * A subject declaring an inexact amount in the last of three parameter positions.
     *
     * <p>Assumptions: the amount sits at the final index because a parameter walk that stops one
     * element early is refused by no interior case, so bounding the walk needs the end position as
     * well as the middle one.</p>
     */
    private static final class InexactLastOfThreeParameters {

        /**
         * Accepts an amount that lost exactness in the caller, after two exact-typed parameters.
         *
         * @param cardNumber the card the amount would reverse against
         * @param transactionId the transaction the amount would reverse
         * @param reversedAmount the inexact amount the money path must refuse in the final position
         * @throws UnsupportedOperationException always, because the method is declared to be inspected
         *     by a rule and is never invoked
         */
        private void reverseAmount(String cardNumber, String transactionId, float reversedAmount) {
            throw new UnsupportedOperationException(
                    "synthetic subject: declared to be inspected by a rule, never to be invoked");
        }
    }

    /**
     * A subject publishing an amount through a primitive float return, isolating the return position.
     *
     * <p>Assumptions: the type declares no field and takes no parameter, so a refusal can only have
     * come from the return type. The method body throws rather than returning a value, which keeps the
     * subject a declaration to be read and never a computation to be trusted.</p>
     */
    private static final class InexactPrimitiveFloatReturn {

        /**
         * Publishes an amount through a primitive binary floating-point type.
         *
         * @return never returns, because the declared type is the whole purpose of the member
         * @throws UnsupportedOperationException always, because the method is declared to be inspected
         *     by a rule and is never invoked
         */
        private float publishedAmount() {
            throw new UnsupportedOperationException(
                    "synthetic subject: declared to be inspected by a rule, never to be invoked");
        }
    }

    /**
     * A subject publishing an amount through a boxed Double return, isolating the boxed return.
     *
     * <p>Assumptions: a boxed return is what an author writes when a published amount has to carry
     * absence, so this completes the four declared forms across the three positions the family
     * polices.</p>
     */
    private static final class InexactBoxedDoubleReturn {

        /**
         * Publishes an amount through a boxed binary floating-point type.
         *
         * @return never returns, because the declared type is the whole purpose of the member
         * @throws UnsupportedOperationException always, because the method is declared to be inspected
         *     by a rule and is never invoked
         */
        private Double publishedAmount() {
            throw new UnsupportedOperationException(
                    "synthetic subject: declared to be inspected by a rule, never to be invoked");
        }
    }

    /**
     * A counterpart carrying an amount exactly in all three positions, so over-matching is detectable.
     *
     * <p>Assumptions: the three positions are covered together in one type on purpose. Acceptance has
     * to be demonstrated over the same surface the refusals cover, because a family that over-matched
     * on one position alone would still accept a counterpart that only exercised the other two.</p>
     */
    private static final class ExactDecimalCounterpart {

        // WHY : Assumptions: the initialiser is present so the declaration is complete rather than to
        //   carry a value this proof reads. No assertion in this file inspects a data value; the
        //   declared type is the entire subject of the proof.
        private BigDecimal postedAmount = BigDecimal.ZERO;

        /**
         * Accepts an exact amount in the parameter position the inexact subjects violate.
         *
         * @param cardNumber the card the amount would post against
         * @param postedAmount the exact amount, in the same middle position the inexact subject uses
         * @param transactionId the transaction the amount would post under
         * @throws UnsupportedOperationException always, because the method is declared to be inspected
         *     by a rule and is never invoked
         */
        private void postAmount(String cardNumber, BigDecimal postedAmount, String transactionId) {
            throw new UnsupportedOperationException(
                    "synthetic counterpart: declared to be inspected by a rule, never to be invoked");
        }

        /**
         * Publishes an amount through the exact fixed-point type this migration carries money in.
         *
         * @return never returns, because the declared type is the whole purpose of the member
         * @throws UnsupportedOperationException always, because the method is declared to be inspected
         *     by a rule and is never invoked
         */
        private BigDecimal publishedAmount() {
            throw new UnsupportedOperationException(
                    "synthetic counterpart: declared to be inspected by a rule, never to be invoked");
        }
    }

}
