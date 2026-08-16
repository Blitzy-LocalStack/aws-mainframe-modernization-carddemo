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
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.exc.UnrecognizedPropertyException;
import tools.jackson.databind.json.JsonMapper;

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
     * Builds a reader configured exactly as the running service configures its own.
     *
     * <p>Refactoring Rationale: this exists because its absence is what let a real defect ship green.
     * {@code MockRestServiceServer.bindTo(RestClient.builder())} leaves the builder's default
     * converters in place, and those are built over a mapper that IGNORES unknown members. The running
     * service does the opposite: {@code application.yml} sets
     * {@code spring.jackson.deserialization.fail-on-unknown-properties} to true. So the adapter's
     * area-code shape could omit a member the reference context publishes, fail on every real answer,
     * and still satisfy every test here -- which is what happened. Reading through the strict setting
     * makes a payload that the service cannot bind a payload these tests cannot bind either.</p>
     *
     * <p>Assumptions: enabling the feature explicitly is required rather than merely tidy. The
     * deserialiser this release ships defaults it OFF, which is why the setting appears in
     * {@code application.yml} at all, and a builder that simply omits the line would reproduce the
     * lenient behaviour this method exists to eliminate.</p>
     *
     * @return the mapper, strict about unknown members, never {@code null}
     */
    private static JsonMapper strictReader() {
        return JsonMapper.builder()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }

    /**
     * Builds an adapter over a mock transport using the package-visible seam.
     *
     * <p>Assumptions: the strict converter REPLACES the builder's JSON converter rather than being
     * appended to the list, which is what {@code withJsonConverter} does and why it is used in place of
     * the list-mutating form -- appending would leave the lenient default ahead of it in the list and
     * change nothing. The list-mutating form is additionally deprecated in this release.</p>
     *
     * <p>Assumptions: the converter is installed BEFORE the mock transport is bound, because binding
     * replaces the request factory and not the converters. Installing it afterwards would work equally,
     * and the order is stated so that a later edit does not read it as significant.</p>
     *
     * @return the harness, never {@code null}
     */
    private static Harness harness() {
        RestClient.Builder builder = RestClient.builder()
                .configureMessageConverters(converters -> converters.withJsonConverter(
                        new JacksonJsonHttpMessageConverter(strictReader())));
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
                .andRespond(withSuccess("{\"areaCd\":\"703\",\"codeClass\":\"G\"}",
                        MediaType.APPLICATION_JSON));

        assertThat(harness.lookup().findAreaCodeClass("703"))
                .contains(AreaCodeClass.GENERAL_PURPOSE);
        harness.server().verify();
    }

    /**
     * Confirms the two payloads the published contract itself carries as examples both bind.
     *
     * <p>Refactoring Rationale: this is the test whose absence let the defect reach a running service.
     * Every case in this class fabricated its own payload, and the fabrication was wrong -- it named the
     * area-code member {@code areaCode} where the contract publishes {@code areaCd} -- so the adapter's
     * shape was reconciled against the test's invention rather than against the producer. The two
     * payloads asserted here are transcribed from the {@code examples} block of the
     * {@code UsPhoneAreaCode} schema at lines 3207 to 3211 of
     * {@code services/reference-service/src/main/resources/openapi/reference-api.yaml}, which is the
     * document that governs what the producer sends, so a member renamed on either side fails here.</p>
     *
     * <p>Assumptions: transcribing the examples is the strongest form available from this module. The
     * producer's own type lives in a bounded context this one may not import -- the layering rules
     * published by {@code common-lib} refuse a cross-context type import in both directions and the two
     * modules are separate deployables -- so the contract document, not the producing class, is the
     * shared authority. The transcription is pinned to a line range for exactly that reason.</p>
     *
     * <p>Assumptions: both examples are asserted rather than one, because they exercise the two halves
     * of the classification partition. Binding only the general-purpose example would leave the
     * easily-recognisable letter unproven against the real member name.</p>
     */
    @Test
    @DisplayName("both published contract examples bind under the strictness the service configures")
    void thePublishedContractExamplesBind() {
        Harness generalPurpose = harness();
        generalPurpose.server()
                .expect(requestTo(ORIGIN + "/api/v1/reference/us-phone-area-codes/201"))
                .andRespond(withSuccess("{\"areaCd\":\"201\",\"codeClass\":\"G\"}",
                        MediaType.APPLICATION_JSON));
        assertThat(generalPurpose.lookup().findAreaCodeClass("201"))
                .as("the contract's own general-purpose example must bind, or no real answer will")
                .contains(AreaCodeClass.GENERAL_PURPOSE);

        Harness easilyRecognisable = harness();
        easilyRecognisable.server()
                .expect(requestTo(ORIGIN + "/api/v1/reference/us-phone-area-codes/800"))
                .andRespond(withSuccess("{\"areaCd\":\"800\",\"codeClass\":\"E\"}",
                        MediaType.APPLICATION_JSON));
        assertThat(easilyRecognisable.lookup().findAreaCodeClass("800"))
                .as("the contract's own easily-recognisable example must bind too")
                .contains(AreaCodeClass.EASILY_RECOGNISABLE);
    }

    /**
     * Confirms the harness really is strict, so the case above is not vacuous.
     *
     * <p>Refactoring Rationale: the two cases that matter most in this class now assert that a payload
     * BINDS, and a lenient reader would let them pass whatever the adapter's shape declared -- which is
     * precisely how the defect survived. This case pins the property those two depend on by reading an
     * undeclared member into a shape that does not admit one, and requiring the read to fail. Without
     * it, someone restoring the builder's default converters would turn this whole class green again
     * while the service went back to answering 500.</p>
     *
     * <p>Assumptions: a probe shape declared here is used rather than the adapter's own, because the
     * adapter's shape is what the other cases exercise and a probe states the property being pinned --
     * that THIS reader refuses an unknown member -- without depending on the adapter at all.</p>
     */
    @Test
    @DisplayName("the reader these tests use refuses an unknown member, as the running service does")
    void theHarnessReaderIsStrict() {
        assertThatThrownBy(() -> strictReader()
                .readValue("{\"declared\":\"x\",\"addedByTheProducer\":\"y\"}", StrictnessProbe.class))
                .as("a lenient reader here would let every binding assertion in this class pass "
                        + "against a shape the running service cannot bind")
                .isInstanceOf(UnrecognizedPropertyException.class);
    }

    /**
     * A shape admitting exactly one member, used only to prove the harness reader's strictness.
     *
     * @param declared the one member this shape admits
     */
    private record StrictnessProbe(String declared) {
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
                .andRespond(withSuccess("{\"areaCd\":\"800\",\"codeClass\":\"E\"}",
                        MediaType.APPLICATION_JSON));

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
                .andRespond(withSuccess("{\"areaCd\":\"703\",\"codeClass\":\"Z\"}",
                        MediaType.APPLICATION_JSON));

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
                .andRespond(withSuccess("{\"stateCd\":\"VA\"}", MediaType.APPLICATION_JSON));
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
                .andRespond(withSuccess("{\"stateZipCd\":\"VA22\"}", MediaType.APPLICATION_JSON));
        assertThat(present.lookup().stateZipPrefixExists("VA22")).isTrue();

        Harness absent = harness();
        absent.server().expect(requestTo(ORIGIN + "/api/v1/reference/us-state-zip-prefixes/VA99"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        assertThat(absent.lookup().stateZipPrefixExists("VA99")).isFalse();
    }

    /**
     * Confirms a value the reference context refuses on SHAPE is reported as absent, not as a failure.
     *
     * <p>Refactoring Rationale: this is the second half of the defect this class answers. Only 404 used
     * to be read as absence, and the reference context answers 400 -- not 404 -- for a value that cannot
     * be a key at all, because each of its item routes constrains the path value to the shape every
     * seeded key has. A lower-case state code, a two-digit area code and a malformed state-and-postal
     * pairing therefore all produced a 500 naming the reference context as unavailable, in place of the
     * field error the baseline composes. All three routes are asserted, because the mapping lives in one
     * shared predicate and a change that fixed one route while missing another would otherwise pass.</p>
     *
     * <p>Assumptions: a shape refusal and an unseeded key are the same answer to the question the port
     * asks. {@code app/cbl/COACTUPC.cbl} decides state validity by testing a condition name over the
     * literals at line 1013 of {@code app/cpy/CSLKPCDY.cpy}, and that test has one negative outcome
     * however the value fails it -- {@code nc} is no more one of those literals than {@code ZZ} is.
     */
    @Test
    @DisplayName("a value the reference context refuses on shape is absent, on all three routes")
    void aShapeRefusalIsReportedAsAbsence() {
        Harness areaCode = harness();
        areaCode.server().expect(requestTo(ORIGIN + "/api/v1/reference/us-phone-area-codes/12"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST));
        assertThat(areaCode.lookup().findAreaCodeClass("12"))
                .as("reporting this as unavailable hid a correctable input behind a 500")
                .isEmpty();

        Harness stateCode = harness();
        stateCode.server().expect(requestTo(ORIGIN + "/api/v1/reference/us-states/nc"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST));
        assertThat(stateCode.lookup().stateCodeExists("nc"))
                .as("the baseline answers this with 'State: is not a valid state code'")
                .isFalse();

        Harness pairing = harness();
        pairing.server().expect(requestTo(ORIGIN + "/api/v1/reference/us-state-zip-prefixes/nc12"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST));
        assertThat(pairing.lookup().stateZipPrefixExists("nc12")).isFalse();
    }

    /**
     * Confirms a refused credential relay is NEVER reported as the value being absent.
     *
     * <p>Refactoring Rationale: this is the boundary of the change above, and it is the reason the
     * mapping admits two named statuses instead of the whole 4xx range. The reference context answers
     * 401 when the relayed token is missing or expired and 403 when it lacks the scope -- facts about
     * the caller and about this seam's configuration, and nothing about the address. Folding them into
     * absence would turn a broken relay into "your state code is invalid" for every user at once, which
     * is the one outcome the port's contract forbids and the hardest to diagnose from a field error.</p>
     *
     * <p>Assumptions: the remaining client-error statuses are asserted alongside, because each reports
     * something about the request or the service rather than about the value: a wrong method, an
     * unacceptable representation, a contended write, an unsupported media type and a throttle.</p>
     */
    @Test
    @DisplayName("a refused relay or any other client error stays a failure, never an absent value")
    void aRefusedRelayIsNeverReportedAsAbsence() {
        for (HttpStatus saysNothingAboutTheValue : new HttpStatus[] {
            HttpStatus.UNAUTHORIZED,
            HttpStatus.FORBIDDEN,
            HttpStatus.METHOD_NOT_ALLOWED,
            HttpStatus.NOT_ACCEPTABLE,
            HttpStatus.CONFLICT,
            HttpStatus.UNSUPPORTED_MEDIA_TYPE,
            HttpStatus.TOO_MANY_REQUESTS}) {

            Harness areaCode = harness();
            areaCode.server().expect(requestTo(ORIGIN + "/api/v1/reference/us-phone-area-codes/703"))
                    .andRespond(withStatus(saysNothingAboutTheValue));
            assertThatThrownBy(() -> areaCode.lookup().findAreaCodeClass("703"))
                    .as("status %s must not be read as '703 is not in the list'",
                            saysNothingAboutTheValue)
                    .isInstanceOf(ReferenceContextUnavailableException.class);

            Harness stateCode = harness();
            stateCode.server().expect(requestTo(ORIGIN + "/api/v1/reference/us-states/VA"))
                    .andRespond(withStatus(saysNothingAboutTheValue));
            assertThatThrownBy(() -> stateCode.lookup().stateCodeExists("VA"))
                    .as("status %s must not be read as 'VA is not a state'", saysNothingAboutTheValue)
                    .isInstanceOf(ReferenceContextUnavailableException.class);
        }
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
