package com.carddemo.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.carddemo.account.service.AddressValidationService.AreaCodeClass;
import com.carddemo.account.service.RestReferenceAddressLookup.ReferenceContextUnavailableException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Verifies the reference-context adapter that resolves the three address allow-lists.
 *
 * <p>These tests exist because the port this class implements had NO implementation at all, which left
 * {@code AddressValidationService} -- an annotated component -- unsatisfiable and stopped the account
 * context from starting. They therefore assert both that the adapter exists and that it draws the
 * distinction its port requires: a 404 is the validation outcome "not in the list", and every other
 * failure is a transport failure that must NOT be reported as a validation outcome.
 */
class RestReferenceAddressLookupTest {

    /** The approved origin these tests configure the adapter with. */
    private static final String ORIGIN = "https://reference.carddemo.internal";

    /** The token value the caller is holding, which every request must carry unchanged. */
    private static final String CALLER_TOKEN = "caller-presented-token-value";

    /** Installs an authenticated caller, because the relay reads the security context per request. */
    @BeforeEach
    void establishCaller() {
        Jwt jwt = Jwt.withTokenValue(CALLER_TOKEN)
                .header("alg", "none")
                .claim("sub", "GRACEH")
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    /** Clears the context so one test's caller cannot become another's. */
    @AfterEach
    void clearCaller() {
        SecurityContextHolder.clearContext();
    }

    /**
     * A bound adapter and the transport it answers through.
     *
     * @param lookup the adapter under test
     * @param server the bound mock transport
     */
    private record Harness(RestReferenceAddressLookup lookup, MockRestServiceServer server) {
    }

    /**
     * Builds an adapter over a mock transport using the package-visible seam.
     *
     * @return the harness, never {@code null}
     */
    private static Harness harness() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        return new Harness(new RestReferenceAddressLookup(builder, ORIGIN, ORIGIN), server);
    }

    /**
     * Confirms a listed area code resolves through the published item route.
     *
     * <p>Assumptions: the ITEM route is read rather than the collection being paged and searched,
     * because one call with a fixed-size body answers the question that paging 410 rows would.
     */
    @Test
    @DisplayName("a resolved area code yields the classification its stored letter stands for")
    void aResolvedAreaCodeYieldsItsClassification() {
        Harness harness = harness();
        harness.server()
                .expect(requestTo(ORIGIN + "/api/v1/reference/us-phone-area-codes/703"))
                .andRespond(withSuccess("{\"areaCode\":\"703\",\"codeClass\":\"G\"}",
                        MediaType.APPLICATION_JSON));

        assertThat(harness.lookup().findAreaCodeClass("703"))
                .contains(AreaCodeClass.GENERAL_PURPOSE);
        harness.server().verify();
    }

    /**
     * Confirms the two stored classification letters are not collapsed into one.
     *
     * <p>Assumptions: this matters because only the general-purpose list is accepted by the account
     * update edit, so folding the easily-recognisable letter into it would admit an area code the
     * reference refuses.
     */
    @Test
    @DisplayName("the easily recognisable letter is not silently folded into general purpose")
    void theEasilyRecognisableLetterIsDistinguished() {
        Harness harness = harness();
        harness.server()
                .expect(requestTo(ORIGIN + "/api/v1/reference/us-phone-area-codes/800"))
                .andRespond(withSuccess("{\"codeClass\":\"E\"}", MediaType.APPLICATION_JSON));

        assertThat(harness.lookup().findAreaCodeClass("800"))
                .as("folding E into G would accept an area code the reference refuses")
                .contains(AreaCodeClass.EASILY_RECOGNISABLE);
    }

    /**
     * Confirms absence is reported as an empty outcome rather than as a failure.
     */
    @Test
    @DisplayName("an unlisted area code is an empty outcome and not a failure")
    void anUnlistedAreaCodeIsAnEmptyOutcome() {
        Harness harness = harness();
        harness.server()
                .expect(requestTo(ORIGIN + "/api/v1/reference/us-phone-area-codes/000"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(harness.lookup().findAreaCodeClass("000")).isEmpty();
    }

    /**
     * Confirms a transport failure is never reported as the code being absent.
     *
     * <p>Assumptions: this is the distinction the port's contract turns on. Collapsing the two would
     * refuse a valid address for every caller whenever the owning service was unavailable.
     */
    @Test
    @DisplayName("an unreachable reference context is a transport failure, never an empty outcome")
    void anUnreachableContextIsATransportFailure() {
        Harness harness = harness();
        harness.server()
                .expect(requestTo(ORIGIN + "/api/v1/reference/us-phone-area-codes/703"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> harness.lookup().findAreaCodeClass("703"))
                .as("reporting this as absent would refuse a valid address whenever the owner was down")
                .isInstanceOf(ReferenceContextUnavailableException.class);
    }

    /**
     * Confirms a classification letter outside the stored domain is refused rather than defaulted.
     */
    @Test
    @DisplayName("a classification letter this service does not recognise is a failure, not a class")
    void anUnrecognisedClassificationIsAFailure() {
        Harness harness = harness();
        harness.server()
                .expect(requestTo(ORIGIN + "/api/v1/reference/us-phone-area-codes/703"))
                .andRespond(withSuccess("{\"codeClass\":\"Z\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> harness.lookup().findAreaCodeClass("703"))
                .isInstanceOf(ReferenceContextUnavailableException.class);
    }

    /**
     * Confirms state-code presence is decided by the answered status and nothing else.
     */
    @Test
    @DisplayName("a resolved state code is present and an unresolved one is absent")
    void stateCodePresenceFollowsTheStatus() {
        Harness present = harness();
        present.server().expect(requestTo(ORIGIN + "/api/v1/reference/us-states/VA"))
                .andRespond(withSuccess("{\"stateCode\":\"VA\"}", MediaType.APPLICATION_JSON));
        assertThat(present.lookup().stateCodeExists("VA")).isTrue();

        Harness absent = harness();
        absent.server().expect(requestTo(ORIGIN + "/api/v1/reference/us-states/ZZ"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        assertThat(absent.lookup().stateCodeExists("ZZ")).isFalse();
    }

    /**
     * Confirms pairing presence is decided by the answered status and nothing else.
     */
    @Test
    @DisplayName("a resolved state and postal prefix pairing is present and an unresolved one is absent")
    void stateZipPrefixPresenceFollowsTheStatus() {
        Harness present = harness();
        present.server().expect(requestTo(ORIGIN + "/api/v1/reference/us-state-zip-prefixes/VA22"))
                .andRespond(withSuccess("{\"stateZipCode\":\"VA22\"}", MediaType.APPLICATION_JSON));
        assertThat(present.lookup().stateZipPrefixExists("VA22")).isTrue();

        Harness absent = harness();
        absent.server().expect(requestTo(ORIGIN + "/api/v1/reference/us-state-zip-prefixes/VA99"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        assertThat(absent.lookup().stateZipPrefixExists("VA99")).isFalse();
    }

    /**
     * Confirms a failed state read is a transport failure rather than a negative answer.
     *
     * <p>Assumptions: the boolean-returning methods are the easier of the two shapes to get wrong,
     * because false is a valid value they could return on any failure and nothing would look broken.
     */
    @Test
    @DisplayName("a state lookup that fails for any reason other than absence is a transport failure")
    void aFailingStateLookupIsATransportFailure() {
        Harness harness = harness();
        harness.server().expect(requestTo(ORIGIN + "/api/v1/reference/us-states/VA"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> harness.lookup().stateCodeExists("VA"))
                .as("answering false here would report a valid state as invalid while the owner was down")
                .isInstanceOf(ReferenceContextUnavailableException.class);
    }

    /**
     * Confirms the calling user's own token is what reaches the reference context.
     *
     * <p>Assumptions: relaying rather than minting is what keeps the read within the authority the
     * caller already holds; a minted machine token would grant more than the user has.
     */
    @Test
    @DisplayName("the caller's own credential is relayed unchanged")
    void theCallersCredentialIsRelayed() {
        Harness harness = harness();
        harness.server()
                .expect(requestTo(ORIGIN + "/api/v1/reference/us-states/VA"))
                .andExpect(header("Authorization", "Bearer " + CALLER_TOKEN))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThat(harness.lookup().stateCodeExists("VA")).isTrue();
        harness.server().verify();
    }

    /**
     * Confirms an uncredentialed call is refused locally rather than sent and rejected remotely.
     *
     * <p>Assumptions: a remote refusal would arrive as a 401 this adapter can only report as the owner
     * being unavailable, which names the wrong cause for whoever reads the log.
     */
    @Test
    @DisplayName("a call with no authenticated caller is refused here rather than sent uncredentialed")
    void aCallWithNoAuthenticatedCallerIsRefused() {
        Harness harness = harness();
        SecurityContextHolder.clearContext();

        assertThatThrownBy(() -> harness.lookup().stateCodeExists("VA"))
                .as("sending it would draw a 401 this adapter could only report as the owner being down")
                .isInstanceOf(ReferenceContextUnavailableException.class)
                .hasMessageContaining("no authenticated caller");
    }

    /**
     * Confirms an unset address stops startup and names the property to set.
     *
     * <p>Assumptions: failing at startup is preferable to defaulting, because a default would produce a
     * service that starts and then reports every address as unverifiable.
     */
    @Test
    @DisplayName("an absent base address stops startup and names the property")
    void anAbsentBaseAddressStopsStartup() {
        for (String absent : new String[] {null, "", "   "}) {
            assertThatThrownBy(
                    () -> new RestReferenceAddressLookup(RestClient.builder(), absent, ORIGIN))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("carddemo.reference-context.base-url");
        }
    }

    /**
     * Confirms an address that would carry the relayed credential in clear text is refused.
     */
    @Test
    @DisplayName("a base address that would carry the relayed credential in clear text is refused")
    void aPlainTextBaseAddressIsRefused() {
        String insecure = "http://reference.carddemo.internal";
        assertThatThrownBy(
                () -> new RestReferenceAddressLookup(RestClient.builder(), insecure, insecure))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("https");
    }

    /**
     * Confirms an address pointing somewhere other than the approved origin is refused.
     *
     * <p>Assumptions: this check exists because every request carries a live credential, so an
     * unapproved destination receives one on the first account update.
     */
    @Test
    @DisplayName("a base address that is not the approved origin is refused")
    void anUnapprovedOriginIsRefused() {
        assertThatThrownBy(() -> new RestReferenceAddressLookup(
                RestClient.builder(), "https://elsewhere.example", ORIGIN))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("approved origin");
    }

    /**
     * Confirms anything beyond a scheme and authority is refused.
     *
     * <p>Assumptions: a base path would silently re-root every call, and user information would set a
     * second credential in a header this client never declares.
     */
    @Test
    @DisplayName("a base address carrying user information, a path, a query or a fragment is refused")
    void aBaseAddressCarryingMoreThanAnOriginIsRefused() {
        for (String malformed : new String[] {
            "https://user:secret@reference.carddemo.internal",
            ORIGIN + "/api",
            ORIGIN + "?tenant=1",
            ORIGIN + "#fragment"}) {
            assertThatThrownBy(() -> new RestReferenceAddressLookup(
                    RestClient.builder(), malformed, malformed))
                    .as("value %s must not reach the builder", malformed)
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    /**
     * Confirms two spellings of one origin compare equal, so a separator is not a startup failure.
     */
    @Test
    @DisplayName("a trailing separator is the same origin and must not stop startup")
    void aTrailingSeparatorIsTheSameOrigin() {
        assertThatCode(() -> new RestReferenceAddressLookup(
                RestClient.builder(), ORIGIN + "/", ORIGIN))
                .doesNotThrowAnyException();
    }

    /**
     * Confirms the port now has an implementation, which is the finding this class answers.
     *
     * <p>Assumptions: the validator is additionally constructed here, because the defect was not that
     * the interface lacked an implementor in the abstract but that the annotated component depending on
     * it could not be built, which stopped the whole context from starting.
     */
    @Test
    @DisplayName("the adapter satisfies the port that left the validator unsatisfiable")
    void theAdapterSatisfiesThePort() {
        assertThat(AddressValidationService.ReferenceAddressLookup.class)
                .as("the finding was that this port had no implementation at all")
                .isAssignableFrom(RestReferenceAddressLookup.class);
        assertThatCode(() -> new AddressValidationService(harness().lookup()))
                .doesNotThrowAnyException();
    }


    /**
     * Confirms the CONTAINER can now build the validator, which is what the defect prevented.
     *
     * <p>Refactoring Rationale: asserting that the adapter implements the interface is not enough. The
     * defect was that {@code AddressValidationService} is an annotated component whose only constructor
     * dependency had no bean, so the account context failed to start outright -- the same shape as the
     * customer-identifier-protection defect. A reflective assertion would have passed throughout. This
     * test asks a real container for the bean instead, so a future change that withdraws the adapter or
     * renames the property fails here rather than at deployment.
     */
    @Test
    @DisplayName("a container holding the adapter builds the address validator")
    void aContainerBuildsTheAddressValidator() {
        new ApplicationContextRunner()
                .withUserConfiguration(WireAdapterAndValidator.class)
                .withPropertyValues("carddemo.reference-context.base-url=" + ORIGIN)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(AddressValidationService.class);
                    assertThat(context)
                            .hasSingleBean(AddressValidationService.ReferenceAddressLookup.class);
                });
    }

    /**
     * Confirms a container WITHOUT the adapter still fails, and fails naming the port.
     *
     * <p>Refactoring Rationale: this is the defect itself, kept as an executable statement of it. The
     * account context held an annotated validator and no adapter for its port, and the whole context
     * therefore failed to start. Asserting only the positive case would let a future change withdraw the
     * adapter and leave this class green while every task crash-looped; asserting the negative case as
     * well pins WHY the positive one matters and names the type a reader has to supply.
     */
    @Test
    @DisplayName("a container missing the adapter fails, naming the port that has no bean")
    void aContainerMissingTheAdapterFails() {
        new ApplicationContextRunner()
                .withUserConfiguration(WireValidatorOnly.class)
                .run(context -> assertThat(context)
                        .getFailure()
                        .hasMessageContaining("ReferenceAddressLookup"));
    }

    /**
     * Registers the validator with no adapter, reproducing the state the finding describes.
     */
    @Configuration(proxyBeanMethods = false)
    static class WireValidatorOnly {

        /**
         * Publishes the validator whose port this configuration deliberately leaves unsatisfied.
         *
         * @param lookup the port the container cannot resolve
         * @return never returns, because the container cannot supply the argument
         */
        @Bean
        AddressValidationService addressValidationService(
                AddressValidationService.ReferenceAddressLookup lookup) {
            return new AddressValidationService(lookup);
        }
    }

    /**
     * Registers the adapter and the validator the way the component scan does.
     *
     * <p>Assumptions: the two are declared explicitly rather than scanned, because scanning this package
     * would also pull in the account read and write services and the inquiry listener, each of which
     * needs repositories this test has no reason to supply. The dependency under test is unaffected: the
     * validator still receives its port from the container rather than from the test.
     */
    @Configuration(proxyBeanMethods = false)
    static class WireAdapterAndValidator {

        /**
         * Publishes the builder the framework's web starter contributes in a running service.
         *
         * <p>Assumptions: the builder is declared here rather than pulled in from an autoconfiguration,
         * because the property under test is whether the container can resolve the PORT -- not where the
         * framework happens to publish its HTTP client builder in this release. Naming an
         * autoconfiguration class would couple this test to a package that has moved between releases and
         * would fail for a reason unrelated to the finding.
         *
         * @return the builder, never {@code null}
         */
        @Bean
        RestClient.Builder restClientBuilder() {
            return RestClient.builder();
        }

        /**
         * Publishes the adapter under its port type, exactly as the stereotype annotation does.
         *
         * @param builder the builder the autoconfiguration supplies; must not be {@code null}
         * @param baseUrl the configured base address; must not be {@code null}
         * @return the adapter, never {@code null}
         */
        @Bean
        RestReferenceAddressLookup referenceAddressLookup(RestClient.Builder builder,
                @org.springframework.beans.factory.annotation.Value(
                        "${carddemo.reference-context.base-url}") String baseUrl) {
            return new RestReferenceAddressLookup(builder, baseUrl, baseUrl, 2000, 3000);
        }

        /**
         * Publishes the validator, whose construction is the property under test.
         *
         * @param lookup the port the container resolves; must not be {@code null}
         * @return the validator, never {@code null}
         */
        @Bean
        AddressValidationService addressValidationService(
                AddressValidationService.ReferenceAddressLookup lookup) {
            return new AddressValidationService(lookup);
        }
    }

}
