package com.carddemo.account.service;

import com.carddemo.common.security.ApprovedOriginPolicy;
import com.carddemo.account.service.AddressValidationService.AreaCodeClass;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Reads the three address allow-lists from the reference context over its published HTTP surface.
 *
 * <p>This is the adapter for {@link AddressValidationService.ReferenceAddressLookup}. The lists it
 * reads originate in {@code app/cpy/CSLKPCDY.cpy} -- the 410-member general-purpose area-code list at
 * L521, the easily-recognisable list at L931, the state list at L1013 and the state-and-postal-prefix
 * list at L1073 -- which the migration seeds into the {@code reference} schema's
 * {@code us_phone_area_codes}, {@code us_states} and {@code us_state_zip_prefixes} tables.
 *
 * <p>Alternatives Considered: reading those tables directly with a repository in this service.
 * Rejected because the reference context owns that schema, and the Agent Action Plan grants
 * cross-schema access to exactly one role -- the posting role, for the one unit of work that is
 * genuinely multi-schema. Reaching into {@code reference} from here would make a second exception to
 * schema-per-service for a read that the owning service already publishes.
 *
 * <p>Alternatives Considered: presenting a minted machine token, as
 * {@code RestAccountContextClient} does when the transaction context reads account records. Rejected
 * for this hop on two grounds. It would require a new audience and scope in {@code common-lib}, a new
 * earlier-ordered internal chain in the reference service and a new signing key with its
 * infrastructure wiring here -- all to express an authority the caller ALREADY holds. And it would
 * grant MORE than the caller has: a machine token would let a request read reference rows that the
 * end user's own token could not, which is the opposite of what the baseline does, where
 * {@code app/cbl/COACTUPC.cbl} reads the lookup data inside the signed-on user's own CICS task. The
 * machine-token pattern exists for the transaction context because posting also runs from a batch and
 * listener path where there is no end user; address validation runs only inside a user-driven account
 * update, so relaying the caller's own credential is both faithful and smaller.
 *
 * <p>Assumptions: the reference service grants every GET on its surface to either group, so the
 * credential an account-update caller already carries satisfies these three reads with no rule change
 * on the far side.
 */
@Component
public class RestReferenceAddressLookup implements AddressValidationService.ReferenceAddressLookup {

    /** The published route that resolves one phone area code, carrying its baseline class. */
    public static final String PATH_AREA_CODE = "/api/v1/reference/us-phone-area-codes/{areaCode}";

    /** The published route that resolves one state code. */
    public static final String PATH_STATE = "/api/v1/reference/us-states/{stateCode}";

    /** The published route that resolves one state-and-postal-prefix pairing. */
    public static final String PATH_STATE_ZIP_PREFIX =
            "/api/v1/reference/us-state-zip-prefixes/{stateZipPrefix}";

    /** The only scheme a relayed credential may travel over. */
    /**
     * The configuration prefix this seam's two address properties sit under.
     *
     * <p>Assumptions: the prefix is named once and both the base address and the approved origin are
     * read from it, so the two cannot come to sit under different prefixes. Every refusal the shared
     * policy raises names its property from this value, which is why the messages are unchanged.</p>
     */
    private static final String PROPERTY_PREFIX = "carddemo.reference-context";

    /** The configured client, already carrying the base address, both timeouts and the relay. */
    private final RestClient client;

    /**
     * Builds the client this adapter reads through.
     *
     * @param builder the shared builder the framework supplies; must not be {@code null}
     * @param baseUrl the reference context's base address, scheme and authority only; must not be
     *     {@code null}
     * @param approvedOrigin the exact origin the base address is required to equal; must not be
     *     {@code null} or blank
     * @param connectTimeoutMillis how long to wait for the connection, in milliseconds
     * @param readTimeoutMillis how long to wait for the response, in milliseconds
     * @throws IllegalStateException if the base address is absent, is not an absolute HTTPS address,
     *     carries user information, a path, a query or a fragment, or is not the approved origin
     */
    // WHY : Assumptions: the annotation is REQUIRED here and its absence stopped the context from
    //       starting. Spring's implicit constructor injection applies only to a class with exactly
    //       ONE constructor; this class has two, because the package-private one below is a test
    //       seam. With two candidates and no marked one, the container stops looking for an
    //       injectable constructor and falls back to a no-argument constructor, which this class
    //       does not declare, so bean creation failed with "No default constructor found" and the
    //       whole account context failed to start rather than degrading.
    // WHY : Alternatives Considered: removing the test seam so a single constructor would again be
    //       implicit. Rejected because that seam exists for a measured reason recorded on it: the
    //       public constructor installs its own request factory to apply the two timeouts, which
    //       REPLACES any transport a test had bound, so tests would reach the real network. Marking
    //       the injection point keeps both the seam and the timeouts.
    @Autowired
    public RestReferenceAddressLookup(RestClient.Builder builder,
            @Value("${carddemo.reference-context.base-url}") String baseUrl,
            @Value("${carddemo.reference-context.approved-origin:"
                    + "${carddemo.reference-context.base-url}}") String approvedOrigin,
            @Value("${carddemo.reference-context.connect-timeout-ms:2000}") long connectTimeoutMillis,
            @Value("${carddemo.reference-context.read-timeout-ms:3000}") long readTimeoutMillis) {

        // WHY : Refactoring Rationale: the address is examined before it reaches the builder because
        //       every request this client makes RELAYS THE CALLER'S OWN BEARER TOKEN. A plain-HTTP
        //       address would put that credential on the wire in clear text, a different host would hand
        //       it to whoever answers there, and an address carrying user information would additionally
        //       set a second credential in a header this client never declares. None of the three fails
        //       visibly: each produces a service that starts and then leaks on the first account update.
        requireApprovedOrigin(baseUrl, approvedOrigin);

        // WHY : Assumptions: the connect timeout is applied by the HTTP client and the read timeout by
        //       the request factory, because each owns one of the two waits. Setting only one leaves
        //       the other unbounded, and it is the read that hangs -- an address validation that never
        //       returns holds the account-update request open rather than failing it.
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMillis))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMillis));

        this.client = builder.baseUrl(baseUrl)
                .requestFactory(factory)
                .requestInterceptor(callerTokenInterceptor())
                .build();
    }

    /**
     * Builds the adapter over a builder whose transport the caller has already configured.
     *
     * <p>Assumptions: this seam exists so a test can bind a mock transport to the builder. The public
     * constructor installs its own request factory in order to apply the two timeouts, and doing so
     * REPLACES any transport already bound -- so a test using the public constructor would exercise the
     * real network instead of its own stub.
     *
     * <p>Assumptions: the seam applies the SAME address validation, so it cannot be used to bypass it.
     * It does install the credential relay, because only the request factory conflicts with a bound
     * mock transport; leaving the relay out would make every test exercise a client that presents no
     * credential, which is precisely the state the reference context refuses.
     *
     * @param builder the builder, with its transport already configured by the caller; must not be
     *     {@code null}
     * @param baseUrl the reference context's base address; must not be {@code null}
     * @param approvedOrigin the exact origin the base address is required to equal; must not be
     *     {@code null} or blank
     * @throws IllegalStateException if the base address fails any check the public constructor applies
     */
    RestReferenceAddressLookup(RestClient.Builder builder, String baseUrl, String approvedOrigin) {
        requireApprovedOrigin(baseUrl, approvedOrigin);
        this.client = builder
                .baseUrl(baseUrl)
                .requestInterceptor(callerTokenInterceptor())
                .build();
    }

    /**
     * Refuses the configured address unless it is the approved absolute HTTPS origin.
     *
     * <p>Refactoring Rationale: the seven refusals this used to perform inline now come from the shared
     * kernel's {@link ApprovedOriginPolicy}, and the messages are unchanged character for character because
     * the prefix and the three risk clauses below are the ones this copy carried. Two modules held a
     * structurally identical copy of the check and a third was about to add one; transformation rule T2 puts
     * a shared concern in the kernel exactly once, and the failure mode of three copies is that one of them
     * gets strengthened.</p>
     *
     * <p>Assumptions: the clauses stay HERE rather than moving into the kernel with the check, because they
     * state what is at risk on THIS seam and no other seam shares it. A kernel-side default would have to be
     * vague enough to fit every caller, and a vague reason is what the Explainability rule forbids.</p>
     *
     * @param baseUrl the configured base address; may be {@code null}
     * @param approvedOrigin the origin the base address must equal; may be {@code null}
     * @throws IllegalStateException if either value is absent, or the base address is not an absolute
     *     HTTPS origin, or it is not the approved origin
     */
    private static void requireApprovedOrigin(String baseUrl, String approvedOrigin) {
        ApprovedOriginPolicy.require(PROPERTY_PREFIX, baseUrl, approvedOrigin,
                new ApprovedOriginPolicy.Sensitivity(
                        "every request this client makes relays the calling user's bearer token, so there"
                                + " is no safe default address to fall back to",
                        "every request relays the caller's bearer token",
                        "every request relays the caller's bearer token, so an unapproved destination"
                                + " receives a live credential on the first account update"));
    }

    /**
     * Builds the interceptor that relays the calling user's own bearer token on every request.
     *
     * <p>Assumptions: the credential is read from the security context per request rather than held on
     * this bean, because the bean is a singleton shared by every concurrent request and each request
     * carries a different caller. Capturing one token at construction would present one user's
     * credential for every other user's validation.
     *
     * <p>Trade-offs: a call arriving with no authenticated JWT is refused here rather than sent
     * onward without a credential. Sending it would draw a 401 from the reference service, which this
     * adapter can only report as a transport failure -- indistinguishable, to whoever reads the log,
     * from the reference service being down. Refusing locally names the real cause.
     *
     * @return the interceptor, never {@code null}
     */
    private static ClientHttpRequestInterceptor callerTokenInterceptor() {
        return (request, body, execution) -> {
            request.getHeaders().setBearerAuth(callerCredential());
            return execution.execute(request, body);
        };
    }

    /**
     * Reads the calling user's bearer token out of the current security context.
     *
     * @return the encoded token the caller presented, never {@code null} or blank
     * @throws ReferenceContextUnavailableException if the current request carries no authenticated
     *     JWT, so there is no credential to relay
     */
    private static String callerCredential() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken bearer) {
            return bearer.getToken().getTokenValue();
        }
        throw new ReferenceContextUnavailableException(
                "address validation ran with no authenticated caller, so no credential could be "
                        + "relayed to the reference context", null);
    }

    /**
     * {@inheritDoc}
     *
     * @param areaCode {@inheritDoc}
     * @return {@inheritDoc}; the classification is absent when the reference context refuses the
     *     submitted code as one no seeded row can hold, on the terms
     *     {@link #isAboutTheSubmittedValue} sets out
     * @throws ReferenceContextUnavailableException if the reference context could not be reached,
     *     refused the relayed credential or answered unusably -- each a transport or authorisation
     *     failure and not a validation outcome
     */
    @Override
    public Optional<AreaCodeClass> findAreaCodeClass(String areaCode) {
        try {
            PhoneAreaCodeView view = this.client.get()
                    .uri(PATH_AREA_CODE, areaCode)
                    .retrieve()
                    .body(PhoneAreaCodeView.class);
            return Optional.ofNullable(view).map(PhoneAreaCodeView::toAreaCodeClass);
        } catch (HttpClientErrorException refused) {
            if (isAboutTheSubmittedValue(refused)) {
                return Optional.empty();
            }
            throw new ReferenceContextUnavailableException(
                    "the phone area code lookup did not answer", refused);
        } catch (IllegalArgumentException unusable) {
            throw new ReferenceContextUnavailableException(
                    "the reference context answered with an area code classification this service "
                            + "does not recognise", unusable);
        } catch (RestClientException failure) {
            throw new ReferenceContextUnavailableException(
                    "the phone area code lookup did not answer", failure);
        }
    }

    /**
     * {@inheritDoc}
     *
     * @param stateCode {@inheritDoc}
     * @return {@inheritDoc}; the answer is {@code false} when the reference context refuses the
     *     submitted code as one no seeded row can hold, which is what it answers for a code that is
     *     not upper-case as well as for one that is simply unlisted
     * @throws ReferenceContextUnavailableException if the reference context could not be reached,
     *     refused the relayed credential or answered unusably -- each a transport or authorisation
     *     failure and not a validation outcome
     */
    @Override
    public boolean stateCodeExists(String stateCode) {
        return resolves(PATH_STATE, stateCode, "the state code lookup did not answer");
    }

    /**
     * {@inheritDoc}
     *
     * @param stateZipPrefix {@inheritDoc}
     * @return {@inheritDoc}; the answer is {@code false} when the reference context refuses the
     *     submitted pairing as one no seeded row can hold, which is what it answers for a pairing whose
     *     halves are the wrong shape as well as for one that is simply unlisted
     * @throws ReferenceContextUnavailableException if the reference context could not be reached,
     *     refused the relayed credential or answered unusably -- each a transport or authorisation
     *     failure and not a validation outcome
     */
    @Override
    public boolean stateZipPrefixExists(String stateZipPrefix) {
        return resolves(PATH_STATE_ZIP_PREFIX, stateZipPrefix,
                "the state and postal prefix lookup did not answer");
    }

    /**
     * Reports whether one published item route resolves the supplied value.
     *
     * <p>Assumptions: both existence checks read the ITEM route rather than paging the collection and
     * searching it. The item route answers the question in one call with a fixed-size body, where
     * paging 410 area codes or 51 states would cost several calls and would additionally have to
     * reproduce the far side's keyset paging to be correct.
     *
     * @param path the templated item route to read; must not be {@code null}
     * @param value the single path value to substitute; must not be {@code null}
     * @param failureDetail the sentence to report if the read fails; must not be {@code null}
     * @return {@code true} when the route resolved a row, {@code false} when it refused the value as
     *     one no seeded row can hold, on the terms {@link #isAboutTheSubmittedValue} sets out
     * @throws ReferenceContextUnavailableException if the read failed for any reason that says nothing
     *     about the submitted value
     */
    private boolean resolves(String path, String value, String failureDetail) {
        try {
            this.client.get().uri(path, value).retrieve().toBodilessEntity();
            return true;
        } catch (HttpClientErrorException refused) {
            if (isAboutTheSubmittedValue(refused)) {
                return false;
            }
            throw new ReferenceContextUnavailableException(failureDetail, refused);
        } catch (RestClientException failure) {
            throw new ReferenceContextUnavailableException(failureDetail, failure);
        }
    }

    /**
     * Reports whether a client-error answer is ABOUT THE VALUE that was submitted.
     *
     * <p>Purpose: to draw the one distinction the port's contract turns on. A refusal about the value
     * is the validation outcome "this value is in none of the seeded lists", which the port models as
     * an absent classification or a false existence answer. Anything else is a transport or
     * authorisation failure, which must NOT be reported as a validation outcome, because doing so
     * refuses a VALID address for every caller whenever the owning service or the credential relay is
     * broken.</p>
     *
     * <p>Refactoring Rationale: only 404 used to qualify, and every other status -- 400 included --
     * became a reported failure. The reference context answers 400, not 404, whenever the submitted
     * value cannot be a key at all: each item route constrains its path value to the shape every
     * seeded key has, {@code ^[0-9]{3}$} for an area code, {@code ^[A-Z]{2}$} for a state code and
     * {@code ^[A-Z]{2}[0-9]{2}$} for a state-and-postal pairing, and a value outside that shape is
     * refused by the constraint before any row is read. So a caller submitting the lower-case
     * {@code nc} as a state, or a two-digit area code, drew a 500 naming the reference context as
     * unavailable in place of the field error the baseline produces. That is the wrong answer twice
     * over: it hides a correctable input behind an infrastructure fault, and it loses the verbatim
     * wording {@code app/cbl/COACTUPC.cbl} composes at its lines 2502 and 2503.</p>
     *
     * <p>Assumptions: a shape refusal and an unseeded key are the same answer to the question this
     * adapter asks, and the baseline is what settles that. {@code app/cbl/COACTUPC.cbl} decides state
     * validity by evaluating the condition name {@code VALID-US-STATE-CODE} over the literals listed
     * at line 1013 of {@code app/cpy/CSLKPCDY.cpy}; that comparison has exactly one negative outcome
     * however the submitted value fails it, because {@code nc} is no more one of those literals than
     * {@code ZZ} is. Splitting the two here would invent a distinction the specification does not
     * draw.</p>
     *
     * <p>Alternatives Considered: treating EVERY 4xx as a refusal about the value, which is the
     * broader reading. Rejected deliberately, because 401 and 403 are the statuses the reference
     * context answers when the relayed credential is missing, expired or lacks the scope -- facts
     * about the CALLER and about this seam's configuration, and not about the address. Folding them in
     * would silently turn a broken relay into "your state code is invalid" for every user at once,
     * which is precisely the failure the port's contract forbids and the hardest kind to diagnose from
     * a field error. The same reasoning excludes 405, 406, 409, 415 and 429: each reports something
     * about the request or the service, and none of them reports anything about the value.</p>
     *
     * <p>Trade-offs: the two admitted statuses are named explicitly rather than derived from a range,
     * so adding a third is a deliberate edit with a reason rather than a side effect of widening a
     * comparison. The accepted cost is that a future reference-context route answering, say, 422 for a
     * malformed value would be reported here as a transport failure until this method is told about
     * it -- which fails safe, because it refuses nothing that was valid.</p>
     *
     * @param refused the client-error answer the reference context returned; must not be {@code null}
     * @return {@code true} when the status says the submitted value is not, and cannot be, a seeded
     *     key; {@code false} when the status says nothing about the value
     */
    private static boolean isAboutTheSubmittedValue(HttpClientErrorException refused) {
        int status = refused.getStatusCode().value();
        return status == HttpStatus.NOT_FOUND.value() || status == HttpStatus.BAD_REQUEST.value();
    }

    /**
     * The reference context's area-code answer, declared in full.
     *
     * <p>Refactoring Rationale: EVERY published member is declared, including {@code areaCd}, which
     * this adapter does not consult. An earlier revision declared the classification alone and
     * recorded that "the response carries more, and binding fields this adapter does not consult
     * would make an unrelated addition to that contract a compile concern here" -- which described a
     * deserialiser this service does not run. {@code application.yml} sets
     * {@code spring.jackson.deserialization.fail-on-unknown-properties} to true, deliberately, so a
     * member the producer publishes and this shape omits is not skipped: it fails the conversion. The
     * area-code route publishes {@code areaCd} on every answer, so EVERY successful classification
     * became a binding fault, was caught below as an unusable answer and was reported to the caller as
     * the reference context being unavailable -- a 500 on every account update that carried a
     * telephone number, whatever the number was. Declaring the closed shape is what makes the strict
     * setting safe, and the setting is what makes a later shape change visible.</p>
     *
     * <p>Alternatives Considered: annotating this shape {@code @JsonIgnoreProperties(ignoreUnknown =
     * true)}, which would have made the omission harmless without declaring the member. Rejected on
     * two grounds. The contract this shape reads declares {@code additionalProperties: false} on
     * {@code UsPhoneAreaCode} at line 3195 of
     * {@code services/reference-service/src/main/resources/openapi/reference-api.yaml} with both
     * members required, so an unknown member is a contract violation rather than a permitted addition
     * -- tolerating it would mean accepting an answer the producer is not allowed to send. And the
     * account context is not the first to meet this exact failure: the transaction and authorization
     * contexts each read the account context's cross-reference through the same strict setting and
     * each resolved it by declaring the closed shape, recording the same reasoning on
     * {@code RestAccountContextClient}. Two sibling adapters resolving one problem two ways is how one
     * of them later gets relaxed, which is the argument that settled it.</p>
     *
     * @param areaCd the accepted area code the answer is about, declared so the strict deserialiser
     *     admits it and not consulted here -- the item route is keyed on the value this adapter
     *     supplied, so the answer cannot be about a different code
     * @param codeClass the one-character baseline class, {@code G} or {@code E}
     */
    private record PhoneAreaCodeView(String areaCd, String codeClass) {

        /**
         * Translates the transported class letter into the port's classification.
         *
         * @return the classification that letter stands for, never {@code null}
         * @throws IllegalArgumentException if the letter is neither of the two the baseline defines,
         *     or if no class was transported at all
         */
        private AreaCodeClass toAreaCodeClass() {
            if (codeClass == null || codeClass.length() != 1) {
                throw new IllegalArgumentException(
                        "expected a one-character area code classification");
            }
            return AreaCodeClass.fromCode(codeClass.charAt(0));
        }
    }

    /**
     * Reports that the reference context could not be reached, or answered in a way this service
     * cannot use.
     *
     * <p>Assumptions: this is deliberately NOT a validation failure. The port's contract states that a
     * transport failure must not be reported as a validation outcome, because doing so would refuse a
     * valid address whenever the owning service was unavailable.
     */
    public static class ReferenceContextUnavailableException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        /**
         * Builds the report.
         *
         * @param detail what was being read when the failure happened; must not be {@code null}
         * @param cause the underlying failure, or {@code null} when there is no lower-level cause
         */
        public ReferenceContextUnavailableException(String detail, Throwable cause) {
            super(detail, cause);
        }
    }
}
