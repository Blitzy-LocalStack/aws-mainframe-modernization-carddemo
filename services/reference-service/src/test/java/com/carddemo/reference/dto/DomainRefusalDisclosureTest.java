package com.carddemo.reference.dto;

import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Asserts that a rejected wire value never appears in the refusal it causes.
 *
 * <p><b>Purpose.</b> Both {@code @JsonCreator} readers in this package resolve a caller-supplied string
 * against a closed two-member domain and raise {@link IllegalArgumentException} when it matches neither.
 * Both used to append the supplied value to that message. The deserialiser wraps whatever a creator
 * throws into a message that is logged and can reach a response body, and the values arrive from a query
 * parameter and from a request body -- so the echo put arbitrary caller-controlled bytes into a log line
 * and into text a client reads back. This class is the check that they stay out.</p>
 *
 * <p>Assumptions: the inputs below are chosen to be recognisable if they leak rather than to be
 * plausible. A value that merely differs from {@code G} would be indistinguishable from the domain
 * characters the message legitimately names, so an assertion using one could pass while the echo was
 * still present. Each input here is a token no correct message could contain for any other reason.</p>
 *
 * <p>Trade-offs: this asserts the absence of the input rather than the exact message text. Pinning the
 * text would also catch a message that stopped naming the domain, and it would fail on any rewording,
 * which is the churn that makes such assertions get deleted. The domain naming is asserted separately
 * and once per type, so the two halves are covered without that cost.</p>
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception at-clause.</p>
 */
class DomainRefusalDisclosureTest {

    /** Values no correct refusal message could contain for any reason other than echoing input. */
    private static final List<String> DOMAIN_TOKENS = List.of("G", "E", "next", "previous");

    /**
     * Verifies that the code-class reader names its domain and reproduces nothing it was given.
     *
     * @param rejected a value outside the two-member domain, written to be recognisable if it leaks
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "\u0000LEAKED-CODE-CLASS",
        "g",
        "GENERAL_PURPOSE",
        "' OR 1=1 --",
        "line-one\nlevel=ERROR forged=yes",
        "\u0000\u0000\u0000",
    })
    @DisplayName("a refused codeClass names the admitted domain and never echoes what was supplied")
    void aRefusedCodeClassNeverEchoesWhatWasSupplied(String rejected) {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> PhoneAreaCodeResponse.CodeClass.fromWireValue(rejected))
                .withMessageContaining("G")
                .withMessageContaining("E")
                .extracting(Throwable::getMessage, org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .as("the refusal must name the domain without reproducing the caller's value, because"
                        + " the deserialiser logs this message and can return it")
                .doesNotContain(rejected);
    }

    /**
     * Verifies that the paging-direction reader names its domain and reproduces nothing it was given.
     *
     * @param rejected a value outside the two-member domain, written to be recognisable if it leaks
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "\u0000LEAKED-DIRECTION",
        "NEXT",
        "forward",
        "'; DROP TABLE reference.us_states; --",
        "line-one\nlevel=ERROR forged=yes",
    })
    @DisplayName("a refused direction names the admitted domain and never echoes what was supplied")
    void aRefusedDirectionNeverEchoesWhatWasSupplied(String rejected) {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> PageDirection.fromWireValue(rejected))
                .withMessageContaining("next")
                .withMessageContaining("previous")
                .extracting(Throwable::getMessage, org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .as("the refusal must name the domain without reproducing the caller's value")
                .doesNotContain(rejected);
    }

    /**
     * Verifies that neither refusal message grows with the length of what it refused.
     *
     * <p>Assumptions: length is asserted as well as containment because a truncating echo would defeat
     * a containment check while still placing caller bytes in the message. A message that is identical
     * for a short and a very long rejection cannot be carrying either.</p>
     *
     * @param padding the repeat count that makes one rejected value far longer than the other
     */
    @ParameterizedTest
    @ValueSource(ints = {1, 4096})
    @DisplayName("neither refusal message varies with the size of the value it refused")
    void neitherRefusalMessageVariesWithTheSizeOfTheValue(int padding) {
        String rejected = "X".repeat(padding);

        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> PhoneAreaCodeResponse.CodeClass.fromWireValue(rejected))
                .withMessage("codeClass must be one of G or E");
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> PageDirection.fromWireValue(rejected))
                .withMessage("direction must be one of next or previous");
    }

    /**
     * Verifies that the code-class reader refuses an absent value without a message that echoes it.
     *
     * <p>Assumptions: absence is covered separately because this reader refuses {@code null} where its
     * sibling returns {@code null}, and that asymmetry is a documented contract decision rather than an
     * oversight -- the classification is required by the schema and {@code NOT NULL} in the column.</p>
     */
    @org.junit.jupiter.api.Test
    @DisplayName("an absent codeClass is refused with the same domain-naming message")
    void anAbsentCodeClassIsRefusedWithTheSameMessage() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> PhoneAreaCodeResponse.CodeClass.fromWireValue(null))
                .withMessage("codeClass must be one of G or E");
    }

    /**
     * Verifies that the domain tokens this class relies on are the ones the readers accept.
     *
     * <p>Assumptions: this guards the two tests above from becoming vacuous. They assert that a
     * refusal NAMES the domain, which only means anything while these four tokens really are the
     * accepted vocabulary; if a token were renamed, the containment assertions would fail loudly here
     * rather than quietly stop testing anything.</p>
     */
    @org.junit.jupiter.api.Test
    @DisplayName("the domain tokens these assertions rely on are the accepted vocabulary")
    void theDomainTokensAreTheAcceptedVocabulary() {
        org.assertj.core.api.Assertions.assertThat(DOMAIN_TOKENS)
                .as("the two readers between them accept exactly these four wire values")
                .containsExactlyInAnyOrder(
                        PhoneAreaCodeResponse.CodeClass.GENERAL_PURPOSE.wireValue(),
                        PhoneAreaCodeResponse.CodeClass.EASILY_RECOGNISABLE.wireValue(),
                        PageDirection.NEXT.wireValue(),
                        PageDirection.PREVIOUS.wireValue());
    }
}
