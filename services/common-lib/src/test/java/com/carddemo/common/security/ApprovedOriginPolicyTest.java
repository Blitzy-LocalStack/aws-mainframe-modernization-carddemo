package com.carddemo.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Verifies that a configured service-to-service base address is refused unless it is the approved
 * absolute HTTPS origin.
 *
 * <h2>What this class asserts</h2>
 *
 * <p>Three internal clients in this migration attach a credential to every request they send, and one of
 * them carries a primary account number in the first request it makes. The address they send it to is
 * configuration, so it can be changed after review by whoever edits a parameter file. These cases assert
 * that each of the seven ways a base address can be unsafe is refused BEFORE any client is built, that
 * the refusal names the offending property, and that no refusal quotes the value -- a misconfigured
 * address has on occasion been a credential-bearing one, and these messages reach a retained startup
 * log.</p>
 *
 * <p>Assumptions: the property prefix is supplied by the caller and every refusal is asserted to name the
 * property derived from it, rather than asserted against a literal. That is what lets three callers with
 * three different prefixes share one check while each keeps the message its own operators already know.
 * </p>
 */
class ApprovedOriginPolicyTest {

    /** A prefix standing for a caller's configuration namespace, distinct from any real one. */
    private static final String PREFIX = "carddemo.example-context";

    /** The property every shape refusal is expected to name. */
    private static final String BASE_PROPERTY = PREFIX + ".base-url";

    /** An address that satisfies every condition, used as the baseline every case varies from. */
    private static final String APPROVED = "https://internal.example.invalid";

    /** The clauses a caller supplies, chosen so a refusal can be seen to carry them. */
    private static final ApprovedOriginPolicy.Sensitivity SENSITIVITY =
            new ApprovedOriginPolicy.Sensitivity(
                    "this seam carries a credential on every request",
                    "the request carries a credential",
                    "an unapproved destination receives that credential");

    /**
     * Verifies an address equal to the approved origin is admitted, so the refusals mean something.
     *
     * <p>Assumptions: this case exists first because every other case in this class is a refusal, and a
     * policy that refused everything would satisfy all of them while making every deployment fail to
     * start. A trailing separator is included on one of the two values to pin the normalisation as well.
     * </p>
     */
    @Test
    @DisplayName("the approved origin is admitted, with or without a trailing separator")
    void theApprovedOriginIsAdmitted() {
        assertThatCode(() ->
                ApprovedOriginPolicy.require(PREFIX, APPROVED, APPROVED, SENSITIVITY))
                .doesNotThrowAnyException();
        assertThatCode(() ->
                ApprovedOriginPolicy.require(PREFIX, APPROVED + "/", APPROVED, SENSITIVITY))
                .doesNotThrowAnyException();
        assertThatCode(() ->
                ApprovedOriginPolicy.require(PREFIX, " " + APPROVED + " ", APPROVED, SENSITIVITY))
                .doesNotThrowAnyException();
    }

    /**
     * Verifies an absent base address is refused and the refusal carries the caller's own clause.
     *
     * <p>Assumptions: blank is refused as well as null, because a configuration mechanism that supplies an
     * empty string for an unset key is common and an empty base address would otherwise be resolved
     * against nothing.</p>
     *
     * @param absent the unusable value under test, of type {@code String}, being empty or whitespace
     */
    @ParameterizedTest(name = "an absent base address [{0}] is refused")
    @ValueSource(strings = {"", "   "})
    void anAbsentBaseAddressIsRefused(String absent) {
        assertThatThrownBy(() ->
                ApprovedOriginPolicy.require(PREFIX, absent, APPROVED, SENSITIVITY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(BASE_PROPERTY + " must be supplied")
                .hasMessageContaining(SENSITIVITY.whenAbsent());

        assertThatThrownBy(() ->
                ApprovedOriginPolicy.require(PREFIX, null, APPROVED, SENSITIVITY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(BASE_PROPERTY + " must be supplied");
    }

    /**
     * Verifies an absent approved origin is refused, so the comparison cannot be skipped by omission.
     *
     * <p>Assumptions: this is the one refusal that names the OTHER property, and it matters because the
     * approved origin defaults to the base address in every caller. A deployment that set the default
     * expression to an empty value would otherwise pass the comparison trivially, which is the failure
     * this refusal exists to prevent.</p>
     */
    @Test
    @DisplayName("an absent approved origin is refused, naming the approved-origin property")
    void anAbsentApprovedOriginIsRefused() {
        assertThatThrownBy(() -> ApprovedOriginPolicy.require(PREFIX, APPROVED, "", SENSITIVITY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(PREFIX + ".approved-origin must be supplied when it is set at all");
    }

    /**
     * Verifies each unsafe shape is refused, naming the condition that failed.
     *
     * <p>Assumptions: the shapes are exercised as one parameterised case because they share one subject --
     * the address is not a bare absolute HTTPS origin -- while each carries its own message. Asserting the
     * message fragment per shape is what stops a policy that refused everything with one sentence from
     * passing: an operator reading "must carry no path" acts differently from one reading "must use the
     * https scheme".</p>
     *
     * @param unsafe the address under test, of type {@code String}
     * @param expectedFragment the fragment the refusal must carry, of type {@code String}
     */
    @ParameterizedTest(name = "[{0}] is refused with [{1}]")
    @org.junit.jupiter.params.provider.CsvSource({
        "http://internal.example.invalid,             must use the https scheme",
        "ftp://internal.example.invalid,              must use the https scheme",
        "/relative/only,                              must use the https scheme",
        "https://user:secret@internal.example.invalid,must carry no user information",
        "https://internal.example.invalid/api,        must carry no path",
        "https://internal.example.invalid?probe=1,    must carry no query and no fragment",
        "https://internal.example.invalid#fragment,   must carry no query and no fragment",
    })
    void everyUnsafeShapeIsRefused(String unsafe, String expectedFragment) {
        assertThatThrownBy(() ->
                ApprovedOriginPolicy.require(PREFIX, unsafe.trim(), unsafe.trim(), SENSITIVITY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(BASE_PROPERTY)
                .hasMessageContaining(expectedFragment.trim());
    }

    /**
     * Verifies an absolute HTTPS address that is not the approved origin is refused.
     *
     * <p>Assumptions: the address under test is well shaped in every other respect, which is the point --
     * it would pass every check above, so this case is the only one that can fail if the comparison is
     * dropped. The refusal carries the caller's own clause about what an unapproved destination receives.
     * </p>
     */
    @Test
    @DisplayName("a well-shaped address that is not the approved origin is refused")
    void aWellShapedUnapprovedAddressIsRefused() {
        assertThatThrownBy(() -> ApprovedOriginPolicy.require(PREFIX,
                "https://attacker.example.invalid", APPROVED, SENSITIVITY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(BASE_PROPERTY + " is not the approved origin")
                .hasMessageContaining(SENSITIVITY.whenUnapproved());
    }

    /**
     * Verifies an address that is not parseable is refused without quoting it.
     *
     * <p>Assumptions: the value is asserted ABSENT from the message rather than the message merely being
     * asserted present. Quoting a malformed address would be defensible for configuration and is withheld
     * anyway, because a misconfigured value has on occasion been a credential-bearing address and this
     * message reaches a retained startup log.</p>
     */
    @Test
    @DisplayName("an unparseable address is refused and never quoted back")
    void anUnparseableAddressIsRefusedWithoutQuotingIt() {
        String malformed = "https://host name with spaces/secret-token";

        assertThatThrownBy(() ->
                ApprovedOriginPolicy.require(PREFIX, malformed, malformed, SENSITIVITY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(BASE_PROPERTY + " is not a valid address")
                .satisfies(refusal -> assertThat(refusal.getMessage())
                        .as("a malformed address may itself carry a credential")
                        .doesNotContain("secret-token"));
    }

    /**
     * Verifies a partially supplied clause set is refused at construction.
     *
     * <p>Assumptions: this is asserted because the three clauses are the only part of the check a caller
     * supplies, so a null one would otherwise reach a composed message as the text "null" and tell an
     * operator nothing. Failing at construction locates the fault in the calling client.</p>
     */
    @Test
    @DisplayName("a clause set missing any clause is refused at construction")
    void aPartiallySuppliedClauseSetIsRefused() {
        assertThatThrownBy(() -> new ApprovedOriginPolicy.Sensitivity(null, "b", "c"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("whenAbsent");
        assertThatThrownBy(() -> new ApprovedOriginPolicy.Sensitivity("a", null, "c"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("whenNotHttps");
        assertThatThrownBy(() -> new ApprovedOriginPolicy.Sensitivity("a", "b", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("whenUnapproved");
    }

    /**
     * Verifies the required scheme is published as a constant both halves of a message can read.
     *
     * <p>Assumptions: the constant is asserted rather than assumed because the scheme appears in a refusal
     * message that an operator matches against their own configuration. A constant lets the message and the
     * comparison come from one value; asserting it keeps a future change visible here.</p>
     */
    @Test
    @DisplayName("the required scheme is https and is published")
    void theRequiredSchemeIsPublished() {
        assertThat(ApprovedOriginPolicy.REQUIRED_SCHEME).isEqualTo("https");
    }
}
