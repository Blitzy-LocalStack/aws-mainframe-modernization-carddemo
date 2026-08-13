package com.carddemo.account.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.account.api.AccountController;
import com.carddemo.account.api.CardXrefController;
import com.carddemo.account.api.CustomerController;
import com.carddemo.common.security.InternalServiceToken;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

/**
 * Asserts what the account context accepts as proof that a caller is the authorization service.
 *
 * <h2>Purpose</h2>
 * <p>The internal read paths carry no human credential, so the only thing standing between them and an
 * unauthenticated caller is this configuration. This class asserts the four independent refusals -- wrong
 * signature, wrong issuer, wrong audience, expired -- plus the authority mapping that decides whether a
 * correctly signed token actually satisfies the rule, plus the matcher that decides which paths this chain
 * governs at all.</p>
 *
 * <p>Alternatives Considered: driving a loaded Spring context with a servlet container and asserting on status
 * codes was rejected. Every refusal above renders as the same 401, so a context test could report that the
 * chain refuses a token without establishing WHICH check refused it -- and a configuration that had lost its
 * audience validator entirely would still pass such a test as long as the signature check remained. Exercising
 * the real decoder this class builds, and the real converter it installs, makes each check separately
 * falsifiable.</p>
 *
 * <p>Assumptions: the decoder under test is the one the bean method returns, not a hand-built equivalent, so
 * the validator chain, the algorithm pin and the key decoding are all the deployed ones.</p>
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no parameter,
 * return or exception section.</p>
 */
class InternalApiSecurityConfigTest {

    /**
     * The key both sides share. Fabricated, exactly at the minimum admissible length.
     */
    private static final String KEY = "0123456789abcdef0123456789abcdef";

    /**
     * A different key of the same length, for the wrong-key refusal.
     */
    private static final String OTHER_KEY = "fedcba9876543210fedcba9876543210";

    /**
     * A fixed instant, so the expiry assertions are not clock-dependent.
     */
    private static final Instant NOW = Instant.parse("2026-08-07T12:00:00Z");

    /**
     * The subject the calling service mints under.
     *
     * <p>Assumptions: read from the shared constant rather than written as a literal, because the verifier
     * now admits a CLOSED set of subjects and this fixture has to be a member of it. A literal here would
     * keep passing after a rename on one side and would then be asserting that a refused subject is
     * accepted, which is the inverse of the property.</p>
     */
    private static final String SUBJECT = InternalServiceToken.SUBJECT_AUTHORIZATION_SERVICE;

    /**
     * A subject naming no service the verifier admits.
     *
     * <p>Assumptions: shaped like a plausible service name rather than as obvious nonsense, because the
     * property under test is that membership of the admitted set is what decides -- not that the value
     * looks wrong.</p>
     */
    private static final String UNADMITTED_SUBJECT = "carddemo-reporting-service";

    /**
     * The subject the transaction context mints under, and the identifier of its key.
     *
     * <p>Assumptions: this test needs BOTH callers because the property under assertion is that they are
     * distinguishable. A test holding one subject could not tell a verifier that checks the subject from one
     * that ignores it.</p>
     */
    private static final String OTHER_SUBJECT = InternalServiceToken.SUBJECT_TRANSACTION_SERVICE;

    /**
     * The address a container re-dispatches a failed request to.
     *
     * <p>Assumptions: this is the framework's conventional error path and it is stated as a literal here on
     * purpose, because the matcher under assertion deliberately does NOT depend on it -- the rule matches
     * the dispatcher TYPE and the ORIGINAL target, so a deployment that renamed its error page would keep
     * working. A constant read from the configuration would make this test look like a test of the path.</p>
     */
    private static final String ERROR_PATH = "/error";

    /**
     * Builds the deployed decoder over both callers' keys.
     *
     * <p>Assumptions: the two keys DIFFER, which is what makes every impersonation case below meaningful.
     * Identical keys would let a token minted under one subject verify under the other's key, so the
     * key-identifier and subject assertions would pass vacuously.</p>
     *
     * @return the decoder the bean method produces, never {@code null}
     */
    private static JwtDecoder decoder() {
        return new InternalApiSecurityConfig().internalTokenDecoder(KEY, OTHER_KEY);
    }

    /**
     * Builds a minter for the authorization caller.
     *
     * @param key the signing key
     * @param lifetime how long the token is valid for
     * @param issuedAt the instant the token claims it was issued at
     * @return the minter, never {@code null}
     */
    private static InternalServiceToken minter(String key, Duration lifetime, Instant issuedAt) {
        return minter(key, lifetime, issuedAt, SUBJECT);
    }

    /**
     * Builds a minter under a nominated subject.
     *
     * @param key the signing key
     * @param lifetime how long the token is valid for
     * @param issuedAt the instant the token claims it was issued at
     * @param subject the subject the minted token names itself by
     * @return the minter, never {@code null}
     */
    private static InternalServiceToken minter(String key, Duration lifetime, Instant issuedAt,
            String subject) {
        return new InternalServiceToken(key.getBytes(StandardCharsets.UTF_8), subject,
                Clock.fixed(issuedAt, ZoneOffset.UTC), lifetime);
    }

    /**
     * Mints the token a correct caller presents.
     *
     * @return the serialised token, never {@code null}
     */
    private static String correctToken() {
        return minter(KEY, Duration.ofMinutes(1), Instant.now()).mint(
                InternalServiceToken.AUDIENCE_ACCOUNT_CONTEXT,
                InternalServiceToken.SCOPE_CARD_XREF_READ);
    }

    /**
     * Verifies a correctly minted token is accepted and carries the scope the rule requires.
     */
    @Test
    @DisplayName("a correctly minted token is accepted and grants the required authority")
    void aCorrectlyMintedTokenIsAccepted() {
        Jwt verified = decoder().decode(correctToken());

        assertThat(verified.getSubject()).isEqualTo(SUBJECT);
        // WHY : Assumptions: the issuer is read as a claim string rather than through Jwt.getIssuer(). That
        //   accessor is URL-typed and raises on this issuer, which is an opaque name by design -- so asserting
        //   through it would fail on a token that is entirely correct.
        assertThat(verified.getClaimAsString(JwtClaimNames.ISS)).isEqualTo(InternalServiceToken.ISSUER);
        assertThat(verified.getAudience()).containsExactly(InternalServiceToken.AUDIENCE_ACCOUNT_CONTEXT);

        var authentication = InternalApiSecurityConfig.internalAuthenticationConverter().convert(verified);
        assertThat(authentication).isNotNull();
        assertThat(authentication.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .contains(InternalApiSecurityConfig.CARD_XREF_READ_AUTHORITY);
    }

    /**
     * Verifies a token naming one caller but signed with the other caller's key is refused.
     *
     * <p>Refactoring Rationale: this case used to sign with a key the verifier did not hold at all, which was
     * sufficient while there was one key and every other key was unknown. With one key per caller both keys are
     * held, so the meaningful forgery is the IMPERSONATION -- a token naming the authorization context signed
     * with the transaction context's key -- and that is what is asserted now. The refusal comes from the
     * signature check rather than from a claim validator, because key selection reads the header's identifier
     * and then verifies against that caller's key alone.</p>
     *
     * <p>Assumptions: both keys are the SAME length, so the refusal is attributable to the signature rather
     * than to a length check the decoder might apply first.</p>
     */
    @Test
    @DisplayName("a token naming one caller but signed with the other caller's key is refused")
    void aTokenSignedWithAnotherCallersKeyIsRefused() {
        String impersonation = minter(OTHER_KEY, Duration.ofMinutes(1), Instant.now(), SUBJECT).mint(
                InternalServiceToken.AUDIENCE_ACCOUNT_CONTEXT,
                InternalServiceToken.SCOPE_CARD_XREF_READ);

        assertThatThrownBy(() -> decoder().decode(impersonation)).isInstanceOf(JwtException.class);
    }

    /**
     * Verifies a token whose key identifier names no known caller is refused.
     *
     * <p>Assumptions: key selection finds no candidate at all for this token, which is the case a misconfigured
     * third service would produce. It is asserted separately from the impersonation above because the two fail
     * at different points -- selection here, signature there -- and a verifier could plausibly get one right
     * and the other wrong.</p>
     *
     * @throws Exception if the hand-assembled token cannot be signed, which would itself be the defect
     */
    @Test
    @DisplayName("a token whose key identifier names no known caller is refused")
    void aTokenFromAnUnknownCallerIsRefused() throws Exception {
        assertThatThrownBy(() -> decoder().decode(handSigned("carddemo-reporting-service",
                "carddemo-reporting-service", InternalServiceToken.SCOPE_CARD_XREF_READ, KEY)))
                .isInstanceOf(JwtException.class);
    }

    /**
     * Verifies a token whose key identifier and subject name different callers is refused.
     *
     * <p>Assumptions: this token is CORRECTLY SIGNED with the key its identifier names, so the signature check
     * cannot refuse it and only the agreement validator can. Without that validator the account context would
     * attribute the call, in its refusal bodies and in whatever an operator reads afterwards, to a caller whose
     * key did not sign it.</p>
     *
     * @throws Exception if the hand-assembled token cannot be signed, which would itself be the defect
     */
    @Test
    @DisplayName("a token whose key identifier and subject disagree is refused")
    void aTokenWhoseKeyIdentifierAndSubjectDisagreeIsRefused() throws Exception {
        assertThatThrownBy(() -> decoder().decode(handSigned(SUBJECT, OTHER_SUBJECT,
                InternalServiceToken.SCOPE_CARD_XREF_READ, KEY)))
                .isInstanceOf(JwtException.class);
    }

    /**
     * Verifies a caller presenting a scope it is not permitted to hold is refused at the decoder.
     *
     * <p>Assumptions: the token is assembled by hand rather than through the shared minter, because the minter
     * refuses this scope for this subject -- which is the point of asserting it here too. The two refusals are
     * complementary: the minter's stops an honest caller from asking, and this one stops a caller that obtained
     * the token some other way from being admitted.</p>
     *
     * @throws Exception if the hand-assembled token cannot be signed, which would itself be the defect
     */
    @Test
    @DisplayName("a caller presenting a scope outside its permitted set is refused")
    void aCallerPresentingAnUnpermittedScopeIsRefused() throws Exception {
        assertThat(InternalServiceToken.permits(OTHER_SUBJECT,
                InternalServiceToken.SCOPE_CUSTOMER_READ))
                .as("the closed table must withhold this scope from this caller, or the case is vacuous")
                .isFalse();

        assertThatThrownBy(() -> decoder().decode(handSigned(OTHER_SUBJECT, OTHER_SUBJECT,
                InternalServiceToken.SCOPE_CUSTOMER_READ, OTHER_KEY)))
                .isInstanceOf(JwtException.class);
    }

    /**
     * Assembles and signs a token directly, so a shape the shared minter refuses to produce can be presented.
     *
     * <p>Assumptions: this bypasses the minter deliberately and only for shapes the minter refuses -- a
     * mismatched key identifier, an unknown caller, an unpermitted scope. Every shape the minter WILL produce
     * is exercised through it instead, so this helper never becomes a second implementation of minting.</p>
     *
     * @param keyId the value to place in the key-identifier header
     * @param subject the value to place in the subject claim
     * @param scope the value to place in the scope claim
     * @param key the key to sign with
     * @return the serialised token, never {@code null}
     * @throws Exception if signing fails, which would itself be the defect
     */
    private static String handSigned(String keyId, String subject, String scope, String key)
            throws Exception {
        Instant issuedAt = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(InternalServiceToken.ISSUER)
                .subject(subject)
                .audience(InternalServiceToken.AUDIENCE_ACCOUNT_CONTEXT)
                .issueTime(Date.from(issuedAt))
                .expirationTime(Date.from(issuedAt.plus(Duration.ofMinutes(1))))
                .claim(InternalServiceToken.SCOPE_CLAIM, scope)
                .build();
        SignedJWT token = new SignedJWT(new JWSHeader.Builder(InternalServiceToken.SIGNING_ALGORITHM)
                .keyID(keyId)
                .build(), claims);
        token.sign(new MACSigner(key.getBytes(StandardCharsets.UTF_8)));
        return token.serialize();
    }

    /**
     * Verifies a token minted for a different audience is refused.
     *
     * <p>Assumptions: this is the check that keeps a token minted for one callee from being replayed at
     * another. It is asserted separately from the signature check because such a token IS correctly signed --
     * it is the same shared key -- so only the audience validator can refuse it, and a configuration missing
     * that validator would accept it.</p>
     */
    @Test
    @DisplayName("a correctly signed token minted for another audience is refused")
    void aTokenForAnotherAudienceIsRefused() {
        String misdirected = minter(KEY, Duration.ofMinutes(1), Instant.now())
                .mint("carddemo-some-other-service", InternalServiceToken.SCOPE_CARD_XREF_READ);

        assertThatThrownBy(() -> decoder().decode(misdirected))
                .isInstanceOf(JwtException.class)
                .hasMessageContaining("aud");
    }

    /**
     * Verifies a correctly signed token whose lifetime has elapsed is refused.
     *
     * <p>Assumptions: the token is minted as issued and expired well before now, past any default clock skew
     * the framework allows, so the refusal cannot be attributed to a tolerance window.</p>
     */
    @Test
    @DisplayName("a correctly signed but expired token is refused")
    void anExpiredTokenIsRefused() {
        String stale = minter(KEY, Duration.ofMinutes(1), NOW.minus(Duration.ofDays(1))).mint(
                InternalServiceToken.AUDIENCE_ACCOUNT_CONTEXT,
                InternalServiceToken.SCOPE_CARD_XREF_READ);

        assertThatThrownBy(() -> decoder().decode(stale))
                .isInstanceOf(JwtException.class)
                .hasMessageContaining("exp");
    }

    /**
     * Verifies a correctly signed token minted by a workload the verifier does not admit is refused.
     *
     * <p>Refactoring Rationale: this case did not exist, because nothing read the subject. Signature, issuer
     * and audience together establish only that SOME holder of the shared key minted the token for this
     * service, so before this check a workload that obtained the key -- or a future service given it for one
     * purpose -- presented an indistinguishable credential. The refusal is asserted at the DECODER rather
     * than at the authority mapping, which is where the scope refusal below is asserted, because the two are
     * enforced in different places and a case that could not tell them apart would pass on either.</p>
     * @throws Exception if the hand-assembled token cannot be signed, which would itself be the defect
     */
    @Test
    @DisplayName("a correctly signed token from an unadmitted subject is refused")
    void aTokenFromAnUnadmittedSubjectIsRefused() throws Exception {
        // Assumptions: the token is HAND-SIGNED rather than minted, because the minter refuses an
        //   unadmitted subject at construction -- so a minted one cannot exist to be presented. The
        //   refusal being asserted here is the VERIFIER's, and it has to be reachable independently of
        //   the minter's: a caller that obtained the shared key does not go through our minter at all.
        String foreign = handSigned(UNADMITTED_SUBJECT, UNADMITTED_SUBJECT,
                InternalServiceToken.SCOPE_ACCOUNT_READ, KEY);

        assertThat(InternalServiceToken.ADMITTED_SUBJECTS)
                .as("the fixture only tests anything while this subject stays outside the admitted set")
                .doesNotContain(UNADMITTED_SUBJECT);
        assertThatThrownBy(() -> decoder().decode(foreign))
                .isInstanceOf(JwtException.class);
    }

    /**
     * Verifies both minting services are admitted, so neither is refused by the subject check.
     *
     * <p>Assumptions: asserted as a positive case beside the refusal above, because a subject check that
     * refused everything would satisfy the refusal case alone while taking both production callers offline
     * -- the same class of defect as the one this whole phase remediates.</p>
     *
     * <p>Refactoring Rationale: each subject mints with a scope IT is permitted to carry, drawn from the
     * shared token type's own permission table rather than from one scope named here for both. An earlier
     * revision minted the account read for both, which stopped being mintable when transaction-service's
     * permitted set was narrowed to the cross-reference read alone -- bill payment no longer reads an
     * account over HTTP, because it settles the balance on its own connection. The narrowing is the
     * correct state and this fixture was asserting the old one; deriving the scope per subject is what
     * keeps the case about the SUBJECT check, which is what it exists to assert, rather than about which
     * scope a caller happens to hold today.</p>
     */
    @Test
    @DisplayName("both production minting subjects are admitted")
    void bothProductionSubjectsAreAdmitted() {
        for (String subject : InternalServiceToken.ADMITTED_SUBJECTS) {
            // Assumptions: each subject mints with ITS OWN key, because the verifier holds one key per
            //   admitted caller and selects it by the key identifier the token carries. Minting both with
            //   one key would fail the signature check for whichever caller that key does not belong to,
            //   which is a property of the fixture rather than of the subject check under test.
            String signingKey = subject.equals(SUBJECT) ? KEY : OTHER_KEY;
            // Assumptions: the scope is READ OFF the permission table rather than named here, so this
            //   case cannot be broken again by a legitimate narrowing of what a caller may carry. The
            //   cross-reference read is the one scope both admitted subjects hold, and taking the first
            //   permitted scope would work today and stop being deterministic the moment a set grows.
            String token = minter(signingKey, Duration.ofMinutes(1), Instant.now(), subject).mint(
                    InternalServiceToken.AUDIENCE_ACCOUNT_CONTEXT,
                    InternalServiceToken.SCOPE_CARD_XREF_READ);

            assertThat(InternalServiceToken.permittedScopes(subject))
                    .as("the cross-reference read is the scope both admitted subjects carry")
                    .contains(InternalServiceToken.SCOPE_CARD_XREF_READ);
            assertThat(decoder().decode(token).getSubject())
                    .as("subject %s is minted in production and must decode", subject)
                    .isEqualTo(subject);
        }
        assertThat(InternalServiceToken.ADMITTED_SUBJECTS)
                .as("the admitted set is exactly the two services that mint against this context")
                .containsExactlyInAnyOrder(InternalServiceToken.SUBJECT_AUTHORIZATION_SERVICE,
                        InternalServiceToken.SUBJECT_TRANSACTION_SERVICE);
    }

    /**
     * Verifies a correctly signed token carrying a different scope does not grant the required authority.
     *
     * <p>Refactoring Rationale: this case used to mint an INVENTED scope and assert that it granted nothing.
     * The minter now refuses a scope outside the caller's own set, so that token can no longer be produced by
     * the honest path -- and the shape it was standing in for is covered from the decoder side by
     * {@link #aCallerPresentingAnUnpermittedScopeIsRefused()}. What is worth asserting here instead is the
     * property the per-family split introduced: a token carrying a scope the caller IS permitted to hold still
     * confers only that family's authority, so the account read cannot reach a cross-reference address. Under
     * the single-authority rule it replaces, there was no such thing to assert.</p>
     *
     * <p>Assumptions: such a token DECODES successfully -- it is correctly signed, correctly addressed,
     * unexpired and permitted -- so the decoder is the wrong place to look for the refusal. What must fail is
     * the authority mapping, and that is what is asserted, because it is what turns the request into a 403
     * rather than letting it through.</p>
     */
    @Test
    @DisplayName("a token scoped to one family grants that family's authority and no other")
    void aTokenScopedToOneFamilyGrantsOnlyThatFamily() {
        String accountScoped = minter(KEY, Duration.ofMinutes(1), Instant.now())
                .mint(InternalServiceToken.AUDIENCE_ACCOUNT_CONTEXT,
                        InternalServiceToken.SCOPE_ACCOUNT_READ);

        Jwt verified = decoder().decode(accountScoped);
        var authentication = InternalApiSecurityConfig.internalAuthenticationConverter().convert(verified);

        assertThat(authentication).isNotNull();
        // WHY : Assumptions: the assertion is containment plus exclusion rather than an exact set,
        //   because the framework contributes an authority of its own describing the presentation
        //   factor. Pinning the whole set would make this case fail on a framework upgrade that
        //   renamed that authority, which is not the property under assertion; naming the three
        //   authorities this class owns covers both directions of the one that is.
        assertThat(authentication.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .contains(InternalApiSecurityConfig.ACCOUNT_READ_AUTHORITY)
                .doesNotContain(InternalApiSecurityConfig.CARD_XREF_READ_AUTHORITY,
                        InternalApiSecurityConfig.CUSTOMER_READ_AUTHORITY);
    }

    /**
     * Verifies a decision-path token does not grant the customer-record authority, and vice versa.
     *
     * <p>Refactoring Rationale: this is the case the finding turned on. One scope governed every internal
     * route, so a token minted to decide an authorization also admitted enumerating the customer master and
     * reading a whole record out of it -- names, addresses, phone numbers, dates of birth, transfer account
     * identifiers, credit scores. Both directions are asserted, because a separation that held in only one
     * direction would still let one group of callers reach the other's routes.</p>
     * @throws Exception if the hand-assembled token cannot be signed, which would itself be the defect
     */
    @Test
    @DisplayName("neither internal scope grants the other group's authority")
    void neitherScopeGrantsTheOtherGroupsAuthority() throws Exception {
        var converter = InternalApiSecurityConfig.internalAuthenticationConverter();

        String decisionToken = minter(KEY, Duration.ofMinutes(1), Instant.now()).mint(
                InternalServiceToken.AUDIENCE_ACCOUNT_CONTEXT,
                InternalServiceToken.SCOPE_ACCOUNT_READ);
        var decisionAuthorities = converter.convert(decoder().decode(decisionToken));
        assertThat(decisionAuthorities).isNotNull();
        assertThat(decisionAuthorities.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .contains(InternalApiSecurityConfig.ACCOUNT_READ_AUTHORITY)
                .doesNotContain(InternalApiSecurityConfig.INTERNAL_CUSTOMER_MASTER_AUTHORITY);

        // Assumptions: the customer-master token is asserted to be REFUSED rather than mapped, and both
        //   controls that refuse it are named here because either alone would be weaker. The minter
        //   permits a subject only the scopes its own row names and this scope is in NO row, so no service
        //   can issue it; and the verifier applies the same table, so a token a key-holder assembled by
        //   hand is refused too. The two whole-customer-record addresses are therefore published and
        //   presently unreachable, which is narrower than the state this replaces -- where a key-holder
        //   could still reach them -- and is preferred for exactly that reason.
        String recordsToken = handSigned(SUBJECT, SUBJECT,
                InternalServiceToken.SCOPE_CUSTOMER_MASTER_READ, KEY);
        assertThatThrownBy(() -> decoder().decode(recordsToken))
                .as("no admitted caller may carry the customer-master scope")
                .isInstanceOf(JwtException.class);

        // Assumptions: the authority MAPPING for that scope is still asserted, on a token built directly
        //   rather than decoded, because the mapping and the admission are two different mechanisms and
        //   the chain's own rule is written against the mapped authority. Asserting only the refusal would
        //   leave the rule referring to an authority nothing in this class shows can exist.
        Jwt records = Jwt.withTokenValue("assembled-for-the-converter-alone")
                .header(InternalServiceToken.KEY_ID_HEADER, SUBJECT)
                .subject(SUBJECT)
                .audience(List.of(InternalServiceToken.AUDIENCE_ACCOUNT_CONTEXT))
                .claim(InternalServiceToken.SCOPE_CLAIM,
                        InternalServiceToken.SCOPE_CUSTOMER_MASTER_READ)
                .build();
        var recordsAuthorities = converter.convert(records);
        assertThat(recordsAuthorities).isNotNull();
        assertThat(recordsAuthorities.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .contains(InternalApiSecurityConfig.INTERNAL_CUSTOMER_MASTER_AUTHORITY)
                .doesNotContain(InternalApiSecurityConfig.CARD_XREF_READ_AUTHORITY,
                        InternalApiSecurityConfig.ACCOUNT_READ_AUTHORITY,
                        InternalApiSecurityConfig.CUSTOMER_READ_AUTHORITY);
    }

    /**
     * Verifies an absent credential produces no verified token at all.
     *
     * <p>Assumptions: an unauthenticated request is asserted at the decoder rather than at the chain, because
     * the chain answers a missing bearer header without ever consulting the decoder. What this pins is the
     * complementary property -- that nothing resembling a credential is admitted -- for the empty string and
     * for a bare unsigned value.</p>
     */
    @Test
    @DisplayName("nothing that is not a signed token is admitted")
    void nothingUnsignedIsAdmitted() {
        JwtDecoder decoder = decoder();

        assertThatThrownBy(() -> decoder.decode("")).isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> decoder.decode("not-a-token")).isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> decoder.decode(
                Base64.getUrlEncoder().withoutPadding().encodeToString(
                        "{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8)) + ".e30."))
                .isInstanceOf(JwtException.class);
    }

    /**
     * Verifies a blank configured key is refused while the context is being built.
     *
     * <p>Assumptions: failing at construction is what prevents a deployment from starting with a chain that
     * cannot verify anything. A decoder built over an empty key would either refuse every token -- taking the
     * account context offline for that caller -- or, worse, be constructible over a key an
     * attacker could guess.</p>
     *
     * <p>Refactoring Rationale: BOTH properties are asserted in both directions, where one used to be. A
     * deployment supplying one key and not the other is the misconfiguration this split makes possible, and its
     * symptom is worse than a total failure: one caller's traffic keeps working while the other's is refused
     * with a 401 that names no property, so an operator looks at the failing caller rather than at the
     * verifier's configuration.</p>
     */
    @Test
    @DisplayName("a blank or short configured key is refused at construction, on either side")
    void aBlankOrShortKeyIsRefusedAtConstruction() {
        InternalApiSecurityConfig config = new InternalApiSecurityConfig();

        assertThatThrownBy(() -> config.internalTokenDecoder("   ", OTHER_KEY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("carddemo.internal-identity.authorization-signing-key");
        assertThatThrownBy(() -> config.internalTokenDecoder(KEY, "   "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("carddemo.internal-identity.transaction-signing-key");
        assertThatThrownBy(() -> config.internalTokenDecoder("tooshort", OTHER_KEY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(String.valueOf(InternalServiceToken.MIN_KEY_LENGTH));
        assertThatThrownBy(() -> config.internalTokenDecoder(KEY, "tooshort"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(String.valueOf(InternalServiceToken.MIN_KEY_LENGTH));
    }

    /**
     * Verifies the chain governs exactly the internal addresses of this context and nothing adjacent.
     *
     * <p>Assumptions: the negative half carries the weight. A matcher that was too wide would place a human
     * business path under a chain that accepts a machine token and demands a scope no human token carries,
     * which would refuse legitimate traffic; one that was too narrow would leave an internal path under the
     * human chain, which would accept an identity-provider token in its place. The cross-reference
     * collection path and both bases are probed for that reason.</p>
     *
     * <p>Refactoring Rationale: the CUSTOMER collection path is now asserted CLAIMED where an earlier
     * revision asserted it left to the application chain. That earlier reading was correct only while the
     * customer subtree published a single address: the application chain denies that whole subtree, so the
     * customer scan mounted at the collection path would have been refused to every caller rather than
     * merely to human ones. Its reference is {@code app/cbl/CBCUS01C.cbl}, which carries no
     * {@code EXEC CICS} verb and appears in no resource definition, so a workload credential is the only
     * one that fits it.</p>
     */
    @Test
    @DisplayName("the chain matches exactly the internal addresses and nothing adjacent")
    void theChainMatchesExactlyTheInternalPaths() {
        var matcher = InternalApiSecurityConfig.internalPaths();

        assertThat(matcher.matches(request(HttpMethod.POST,
                CardXrefController.BASE_PATH + CardXrefController.LOOKUP_PATH))).isTrue();

        // WHY : Assumptions: both account-keyed cross-reference operations are probed as well as the
        //   card-keyed one, because the application chain denies the whole cross-reference subtree. A
        //   path this chain fails to claim therefore reaches no handler at all rather than falling
        //   through to a laxer rule, so an omission here presents as a 403 on a correctly mounted route.
        assertThat(matcher.matches(request(HttpMethod.POST,
                CardXrefController.BASE_PATH + CardXrefController.LOOKUP_BY_ACCOUNT_PATH))).isTrue();
        assertThat(matcher.matches(request(HttpMethod.POST,
                CardXrefController.BASE_PATH + CardXrefController.SEARCH_BY_ACCOUNT_PATH))).isTrue();

        // WHY : Assumptions: the GET form of each account-keyed operation is refused, which is the half
        //   that keeps the disclosure property. Each keys on a value the logging contract withholds from
        //   a durable diagnostic, and claiming the GET here would authorise the shape that puts that key
        //   back into a request line before the dispatcher rejected the method.
        assertThat(matcher.matches(request(HttpMethod.GET,
                CardXrefController.BASE_PATH + CardXrefController.LOOKUP_BY_ACCOUNT_PATH))).isFalse();
        assertThat(matcher.matches(request(HttpMethod.GET,
                CardXrefController.BASE_PATH + CardXrefController.SEARCH_BY_ACCOUNT_PATH))).isFalse();
        // WHY : Refactoring Rationale: the three keyed addresses these probes used to assert -- the
        //   account read, the customer presence check and the keyed customer record read -- are gone. All
        //   three moved their identifier into a request body, so the probes now address the body-based
        //   forms and the keyed forms are asserted UNCLAIMED below, which is the half that keeps the
        //   disclosure closed: an address this chain does not claim reaches the application chain, which
        //   denies the customer subtree outright and admits the account subtree only to a business group.
        assertThat(matcher.matches(request(HttpMethod.POST,
                AccountController.BASE_PATH + AccountController.LOOKUP_PATH))).isTrue();
        assertThat(matcher.matches(request(HttpMethod.POST,
                CustomerController.BASE_PATH + CustomerController.LOOKUP_PATH))).isTrue();
        assertThat(matcher.matches(request(HttpMethod.POST,
                CustomerController.BASE_PATH + CustomerController.RECORD_PATH)))
                .as("the keyed customer read is an internal address")
                .isTrue();

        // WHY : Refactoring Rationale: the six-field display read is probed because it is a NEW internal
        //   address, and an address this chain fails to claim reaches the application chain -- which denies
        //   the whole customer subtree outright. An omission here would therefore present as a 403 on a
        //   correctly mounted route serving a screen that renders six fields, which is the shape of failure
        //   this whole operation was authored to remove.
        assertThat(matcher.matches(request(HttpMethod.POST,
                CustomerController.BASE_PATH + CustomerController.DISPLAY_PATH)))
                .as("the six-field customer display read is an internal address")
                .isTrue();
        assertThat(matcher.matches(request(HttpMethod.GET,
                AccountController.BASE_PATH + "/12345678901")))
                .as("the retired keyed account read must NOT be claimed: claiming it would authorise the"
                        + " shape that puts an account identifier back into a request line")
                .isFalse();
        assertThat(matcher.matches(request(HttpMethod.GET,
                CustomerController.BASE_PATH + "/987654321")))
                .as("the retired keyed customer probe must NOT be claimed")
                .isFalse();
        assertThat(matcher.matches(request(HttpMethod.HEAD,
                CustomerController.BASE_PATH + "/987654321")))
                .as("nor its HEAD form, which the collapsed operation no longer serves")
                .isFalse();
        assertThat(matcher.matches(request(HttpMethod.GET,
                CustomerController.BASE_PATH + "/987654321/record")))
                .as("nor the retired keyed customer record read")
                .isFalse();
        assertThat(matcher.matches(request(HttpMethod.GET, CustomerController.BASE_PATH)))
                .as("the customer scan is an internal address: the application chain denies this whole"
                        + " subtree, so leaving it there would refuse every caller and not merely the"
                        + " human ones")
                .isTrue();

        assertThat(matcher.matches(request(HttpMethod.GET, CardXrefController.BASE_PATH)))
                .as("the cross-reference collection path is not an internal read")
                .isFalse();
        assertThat(matcher.matches(request(HttpMethod.GET, AccountController.BASE_PATH)))
                .as("the account collection path stays on the human chain")
                .isFalse();
        assertThat(matcher.matches(request(HttpMethod.GET,
                AccountController.BASE_PATH + "/12345678901/statements")))
                .as("a deeper path under the account base is not an internal read")
                .isFalse();
        assertThat(matcher.matches(request(HttpMethod.GET,
                CustomerController.BASE_PATH + "/987654321/statements")))
                .as("a deeper path under the customer base that no operation serves is not claimed")
                .isFalse();
        assertThat(matcher.matches(request(HttpMethod.GET, "/actuator/health")))
                .as("the health probe stays unauthenticated on the other chain")
                .isFalse();
    }

    /**
     * Verifies the chain claims only the METHOD each internal operation serves, so that the end-user
     * operations sharing an address are left to the application chain.
     *
     * <p>Purpose: this is the property whose absence made the end-user account update unreachable. The
     * matcher was composed from paths alone, and a path-only matcher claims every method at that address --
     * so the PUT that applies an edited account was governed by this chain and demanded the internal read
     * scope, which no identity-provider token carries. The symptom was a 403 on a route that was correctly
     * mounted, correctly authorised by the application chain's own rule, and never reached it.</p>
     *
     * <p>Refactoring Rationale: the account read and the end-user edit no longer share an address, so the
     * property this case guards is asserted across TWO addresses rather than two methods of one. The read
     * moved to its own lookup address as a POST when its identifier left the request line, and the edit
     * keeps the keyed address. Both halves are still asserted, because the failure remains invisible from
     * either alone: the lookup POST must be claimed -- narrowing too far would put the machine read on the
     * human chain, which requires a group claim the calling service does not have -- and the keyed PUT must
     * not be.</p>
     *
     * <p>Refactoring Rationale: the customer presence check is asserted for POST alone, where it was
     * previously asserted for GET and HEAD together because one handler served both. The two collapsed into
     * one operation when the identifier moved into a body, so there is no second form left to keep
     * consistent -- and the HEAD form is now asserted UNCLAIMED, so a caller still issuing it is refused by
     * the application chain rather than authorised here.</p>
     *
     * <p>Assumptions: each customer read is asserted for the one method it publishes and refused for a
     * write method neither publishes. No end-user operation shares either address, so the narrowing here
     * does not separate two surfaces -- it keeps the chain from authorising a method the dispatcher would
     * refuse afterwards.</p>
     */
    @Test
    @DisplayName("the chain claims only the method each internal operation serves")
    void theChainClaimsOnlyTheMethodEachOperationServes() {
        var matcher = InternalApiSecurityConfig.internalPaths();
        String account = AccountController.BASE_PATH + "/12345678901";
        String accountLookup = AccountController.BASE_PATH + AccountController.LOOKUP_PATH;
        String customerLookup = CustomerController.BASE_PATH + CustomerController.LOOKUP_PATH;
        String customerRecord = CustomerController.BASE_PATH + CustomerController.RECORD_PATH;
        String lookup = CardXrefController.BASE_PATH + CardXrefController.LOOKUP_PATH;

        assertThat(matcher.matches(request(HttpMethod.POST, accountLookup)))
                .as("the machine read is a POST at its own address and must stay on this chain")
                .isTrue();
        assertThat(matcher.matches(request(HttpMethod.GET, accountLookup)))
                .as("a GET to the lookup address is left to the application chain rather than authorised"
                        + " here and answered as an unsupported method afterwards")
                .isFalse();
        assertThat(matcher.matches(request(HttpMethod.PUT, account)))
                .as("the end-user edit is a PUT on the keyed address and must fall through to the"
                        + " application chain, which grants it to either business group")
                .isFalse();
        assertThat(matcher.matches(request(HttpMethod.DELETE, account)))
                .as("no internal operation deletes an account, so the method is not claimed here")
                .isFalse();

        assertThat(matcher.matches(request(HttpMethod.POST, customerLookup))).isTrue();
        assertThat(matcher.matches(request(HttpMethod.HEAD, customerLookup)))
                .as("the collapsed operation serves POST alone, so no HEAD form is claimed")
                .isFalse();
        assertThat(matcher.matches(request(HttpMethod.PUT, customerLookup))).isFalse();

        assertThat(matcher.matches(request(HttpMethod.POST, customerRecord)))
                .as("the keyed customer read is a POST and must stay on this chain")
                .isTrue();
        assertThat(matcher.matches(request(HttpMethod.PUT, customerRecord)))
                .as("no operation writes a customer record, so the method is not claimed here")
                .isFalse();
        assertThat(matcher.matches(request(HttpMethod.GET, CustomerController.BASE_PATH))).isTrue();
        assertThat(matcher.matches(request(HttpMethod.POST, CustomerController.BASE_PATH)))
                .as("the scan publishes GET alone at the collection address, so claiming a POST would"
                        + " authorise a method the dispatcher would then refuse")
                .isFalse();

        assertThat(matcher.matches(request(HttpMethod.POST, lookup))).isTrue();
        assertThat(matcher.matches(request(HttpMethod.GET, lookup)))
                .as("the lookup is a POST; a GET to the same address is left to the application chain,"
                        + " which denies the whole cross-reference subtree rather than authorising it"
                        + " here and answering with an unsupported method afterwards")
                .isFalse();
    }

    /**
     * Verifies each route group is claimed by its own matcher and by neither the other's.
     *
     * <p>Refactoring Rationale: this case did not exist, because there was only one group. It is what pins
     * the finding's fix to the routes rather than to the vocabulary: the authorization rules are written one
     * per group, so an address that drifted into the wrong group would be governed by the wrong authority
     * with nothing failing. Both directions are asserted for both groups.</p>
     *
     * <p>Assumptions: the union is asserted to equal the chain's own security matcher for every address
     * either group claims, so a group added later that the chain does not match -- or matched but placed in
     * no group, where the terminal rule denies it -- is caught here rather than in production.</p>
     */
    @Test
    @DisplayName("each internal route group is claimed by its own matcher alone")
    void eachRouteGroupIsClaimedByItsOwnMatcherAlone() {
        var decisions = InternalApiSecurityConfig.decisionReadPaths();
        var records = InternalApiSecurityConfig.customerMasterPaths();
        var chain = InternalApiSecurityConfig.internalPaths();

        List<MockHttpServletRequest> decisionRequests = List.of(
                request(HttpMethod.POST,
                        CardXrefController.BASE_PATH + CardXrefController.LOOKUP_PATH),
                request(HttpMethod.POST,
                        CardXrefController.BASE_PATH + CardXrefController.LOOKUP_BY_ACCOUNT_PATH),
                request(HttpMethod.POST,
                        CardXrefController.BASE_PATH + CardXrefController.SEARCH_BY_ACCOUNT_PATH),
                request(HttpMethod.POST, AccountController.BASE_PATH + AccountController.LOOKUP_PATH),
                request(HttpMethod.POST,
                        CustomerController.BASE_PATH + CustomerController.LOOKUP_PATH),
                // WHY : Assumptions: the display read belongs in the DECISION group and its absence from
                //   the record group below is the assertion that matters. It carries no encrypted
                //   identifier and no credit score, so gating it on the customer-master authority -- which
                //   this system mints for no context -- would make it unreachable; gating the WHOLE record
                //   on the decision authority instead would hand every caller that resolves a card number
                //   a national identifier. The loop asserts both directions for every entry.
                request(HttpMethod.POST,
                        CustomerController.BASE_PATH + CustomerController.DISPLAY_PATH));
        List<MockHttpServletRequest> recordRequests = List.of(
                request(HttpMethod.GET, CustomerController.BASE_PATH),
                request(HttpMethod.POST,
                        CustomerController.BASE_PATH + CustomerController.RECORD_PATH));

        for (MockHttpServletRequest decision : decisionRequests) {
            assertThat(decisions.matches(decision))
                    .as("%s %s is a decision-path read", decision.getMethod(),
                            decision.getRequestURI())
                    .isTrue();
            assertThat(records.matches(decision))
                    .as("%s %s must not be gated on the customer-record scope, which nothing mints",
                            decision.getMethod(), decision.getRequestURI())
                    .isFalse();
            assertThat(chain.matches(decision)).isTrue();
        }
        for (MockHttpServletRequest record : recordRequests) {
            assertThat(records.matches(record))
                    .as("%s %s discloses a whole customer record", record.getMethod(),
                            record.getRequestURI())
                    .isTrue();
            assertThat(decisions.matches(record))
                    .as("%s %s must NOT be reachable with a decision-path credential -- this is the"
                            + " capability the finding removed", record.getMethod(),
                            record.getRequestURI())
                    .isFalse();
            assertThat(chain.matches(record)).isTrue();
        }
    }

    /**
     * Verifies the three end-user operations that share the account prefix are not claimed by this chain.
     *
     * <p>Assumptions: they are asserted explicitly rather than left to the deeper-path case above, because
     * these three are REAL mounted routes rather than hypothetical ones. A matcher widened to a subtree
     * pattern would claim all three, and the symptom -- an account view that answers 403 to the very user
     * whose screen it is -- would be attributed to the token or the group long before the chain.</p>
     *
     * <p>Refactoring Rationale: all three are now POSTs on fixed sub-paths of the account prefix, where two
     * were keyed GETs and the third a keyed PUT. They moved because a path segment is written verbatim into
     * the load balancer's access record, and this chain's own address is a POST too -- so the separation
     * that used to fall out of the METHOD differing now rests entirely on the SUB-PATH differing, which is
     * exactly why each of the three is named here rather than one standing for the family.</p>
     */
    @Test
    @DisplayName("the end-user routes on the account prefix stay on the application chain")
    void theEndUserRoutesBelowTheAccountAddressAreNotClaimed() {
        var matcher = InternalApiSecurityConfig.internalPaths();

        assertThat(matcher.matches(request(HttpMethod.POST,
                AccountController.BASE_PATH + AccountController.VIEW_PATH)))
                .as("the human account view is an end-user route")
                .isFalse();
        assertThat(matcher.matches(request(HttpMethod.POST,
                AccountController.BASE_PATH + AccountController.UPDATE_PATH)))
                .as("the account edit is an end-user route")
                .isFalse();
        assertThat(matcher.matches(request(HttpMethod.POST,
                AccountController.BASE_PATH + AccountController.CARD_XREF_SEARCH_PATH)))
                .as("the by-account cross-reference walk is an end-user route")
                .isFalse();
    }

    /**
     * Verifies the chain is ordered ahead of the application chain.
     *
     * <p>Assumptions: this is asserted from the annotation rather than from behaviour because the consequence
     * of getting it wrong is invisible in a unit test and severe in deployment: the application chain's
     * catch-all rule matches every path, so if it were consulted first every internal read would be refused
     * for want of a human group claim, and no test that exercised the chains individually would notice.</p>
     *
     * @throws Exception if the bean method cannot be reflected, which would mean its signature moved
     */
    @Test
    @DisplayName("the internal chain is ordered ahead of the application chain")
    void theInternalChainIsOrderedFirst() throws Exception {
        Order internal = AnnotationUtils.findAnnotation(
                InternalApiSecurityConfig.class.getMethod("internalApiFilterChain",
                        org.springframework.security.config.annotation.web.builders.HttpSecurity.class,
                        org.springframework.security.oauth2.jwt.JwtDecoder.class,
                        java.time.Clock.class),
                Order.class);

        assertThat(internal).isNotNull();
        assertThat(internal.value()).isEqualTo(InternalApiSecurityConfig.CHAIN_ORDER);
        assertThat(InternalApiSecurityConfig.CHAIN_ORDER)
                .as("a lower order is consulted first, and the application chain leaves its order at the "
                        + "framework default so it must be the higher number")
                .isLessThan(org.springframework.core.Ordered.LOWEST_PRECEDENCE);
    }

    /**
     * Verifies both required authorities are composed from the framework's own scope prefix.
     *
     * <p>Assumptions: both are asserted in one case because the property is about the composition rule and
     * it is the same rule for both. The property that they are DIFFERENT values is asserted here as well,
     * because two authorities spelled the same would publish a separation that granted nothing.</p>
     */
    @Test
    @DisplayName("both required authorities are the scopes under the framework prefix")
    void theRequiredAuthoritiesAreTheScopesUnderTheFrameworkPrefix() {
        // Assumptions: the decision authorities are read as the published LIST rather than as one value,
        //   because the single read authority this case once pinned was split into one per operation
        //   family. The composition rule is the same for all of them, which is why they are still one case.
        // WHY : ⚠️ Refactoring Rationale: the list carries FOUR entries where it carried three, and the
        //   order is the order the chain states its rules in -- the narrowest first. The
        //   card-number-disclosing address was split out of the cross-reference family, so an assertion
        //   naming three would pass against a chain that had lost the split and gone back to authorising
        //   an unmasked primary account number with the same credential as a masked page.
        assertThat(InternalApiSecurityConfig.requiredAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly(
                        "SCOPE_" + InternalServiceToken.SCOPE_CARD_XREF_RESOLVE_CARD_NUMBER,
                        "SCOPE_" + InternalServiceToken.SCOPE_CARD_XREF_READ,
                        "SCOPE_" + InternalServiceToken.SCOPE_ACCOUNT_READ,
                        "SCOPE_" + InternalServiceToken.SCOPE_CUSTOMER_READ);
        assertThat(InternalApiSecurityConfig.requiredCustomerRecordsAuthority().getAuthority())
                .isEqualTo(InternalApiSecurityConfig.INTERNAL_CUSTOMER_MASTER_AUTHORITY)
                .isEqualTo("SCOPE_" + InternalServiceToken.SCOPE_CUSTOMER_MASTER_READ);
        assertThat(InternalApiSecurityConfig.INTERNAL_CUSTOMER_MASTER_AUTHORITY)
                .as("one authority under two names would separate nothing")
                .isNotIn(InternalApiSecurityConfig.CARD_XREF_READ_AUTHORITY,
                        InternalApiSecurityConfig.CARD_XREF_RESOLVE_AUTHORITY,
                        InternalApiSecurityConfig.ACCOUNT_READ_AUTHORITY,
                        InternalApiSecurityConfig.CUSTOMER_READ_AUTHORITY);
        // WHY : Assumptions: the two cross-reference authorities are asserted DIFFERENT, because they are
        //   composed from two constants that differ by one path segment of their scope name. Two
        //   authorities that happened to spell the same string would satisfy every matcher assertion in
        //   this class while leaving the disclosure reachable with the credential the split withheld it
        //   from -- the same failure mode the customer-master assertion above exists for.
        assertThat(InternalApiSecurityConfig.CARD_XREF_RESOLVE_AUTHORITY)
                .isNotEqualTo(InternalApiSecurityConfig.CARD_XREF_READ_AUTHORITY);
    }

    /**
     * Verifies the two authority groups are DISJOINT and together exhaust the chain's own matcher.
     *
     * <p>Purpose: this is the property that closes the escalation. One authority governed every internal
     * address, so a token the authorization and transaction contexts mint in order to resolve ONE card
     * number also reached the operation that returns every field of a customer record and the scan that
     * pages the whole customer master. Splitting the authority is only effective if the two groups do not
     * overlap -- an address in both would still answer to the decision authority -- and only complete if
     * their union is the chain's whole matcher, because an address the chain claims and neither group names
     * falls to the catch-all rule and silently regains the decision authority.</p>
     *
     * <p>Assumptions: the two authorities are asserted DIFFERENT as well as correctly composed. Two
     * constants that happened to hold the same string would satisfy every other assertion in this class
     * while leaving one credential reaching everything, which is precisely the state being corrected.</p>
     */
    @Test
    @DisplayName("the two internal authority groups are disjoint and exhaust the chain")
    void theTwoInternalAuthorityGroupsAreDisjointAndExhaustTheChain() {
        assertThat(InternalApiSecurityConfig.INTERNAL_CUSTOMER_MASTER_AUTHORITY)
                .isEqualTo("SCOPE_" + InternalServiceToken.SCOPE_CUSTOMER_MASTER_READ)
                .as("a shared value would leave one credential reaching every internal address")
                .isNotIn(InternalApiSecurityConfig.CARD_XREF_READ_AUTHORITY,
                        InternalApiSecurityConfig.CARD_XREF_RESOLVE_AUTHORITY,
                        InternalApiSecurityConfig.ACCOUNT_READ_AUTHORITY,
                        InternalApiSecurityConfig.CUSTOMER_READ_AUTHORITY);

        var decision = InternalApiSecurityConfig.decisionReadPaths();
        var customerMaster = InternalApiSecurityConfig.customerMasterPaths();
        var whole = InternalApiSecurityConfig.internalPaths();

        List<MockHttpServletRequest> decisionAddresses = List.of(
                request(HttpMethod.POST,
                        CardXrefController.BASE_PATH + CardXrefController.LOOKUP_PATH),
                request(HttpMethod.POST,
                        CardXrefController.BASE_PATH + CardXrefController.LOOKUP_BY_ACCOUNT_PATH),
                request(HttpMethod.POST,
                        CardXrefController.BASE_PATH + CardXrefController.SEARCH_BY_ACCOUNT_PATH),
                request(HttpMethod.POST,
                        AccountController.BASE_PATH + AccountController.LOOKUP_PATH),
                request(HttpMethod.POST,
                        CustomerController.BASE_PATH + CustomerController.LOOKUP_PATH),
                request(HttpMethod.POST,
                        CustomerController.BASE_PATH + CustomerController.DISPLAY_PATH));
        List<MockHttpServletRequest> customerMasterAddresses = List.of(
                request(HttpMethod.POST,
                        CustomerController.BASE_PATH + CustomerController.RECORD_PATH),
                request(HttpMethod.GET, CustomerController.BASE_PATH));

        for (MockHttpServletRequest address : decisionAddresses) {
            assertThat(decision.matches(address))
                    .as("%s %s is a decision read", address.getMethod(), address.getServletPath())
                    .isTrue();
            assertThat(customerMaster.matches(address))
                    .as("%s %s must NOT also demand the customer-master authority",
                            address.getMethod(), address.getServletPath())
                    .isFalse();
        }
        for (MockHttpServletRequest address : customerMasterAddresses) {
            assertThat(customerMaster.matches(address))
                    .as("%s %s reads whole customer records", address.getMethod(),
                            address.getServletPath())
                    .isTrue();
            // WHY : Assumptions: this is the assertion that makes the split real rather than nominal. If
            //   a customer-master address also matched the decision group, the rule order in the chain
            //   would be irrelevant -- the address would still be reachable with a token minted for a
            //   card lookup, which is exactly the escalation being closed.
            assertThat(decision.matches(address))
                    .as("%s %s must NOT be reachable with a decision-read token",
                            address.getMethod(), address.getServletPath())
                    .isFalse();
        }
        for (MockHttpServletRequest address : decisionAddresses) {
            assertThat(whole.matches(address)).isTrue();
        }
        for (MockHttpServletRequest address : customerMasterAddresses) {
            assertThat(whole.matches(address)).isTrue();
        }
    }

    /**
     * Verifies the ONE cross-reference address that discloses an unmasked card number is governed by its
     * own authority group, and that the credential for it is minted for one caller only.
     *
     * <p>Purpose: this is the assertion that makes the purpose-bound split real rather than nominal. The
     * account-keyed lookup answers with a whole primary account number, because its consumer writes that
     * value into its ledger row as the row's key; the two addresses beside it answer with none. While all
     * three shared one authority, any holder of the cross-reference scope could provoke that disclosure --
     * so the property worth pinning is that the disclosing address is matched by the narrow group and by
     * NEITHER of the wider ones, and that the other two are matched by the wider group and not by the
     * narrow one.</p>
     *
     * <p>Assumptions: the minter's own table is asserted in the same case, because a chain rule demanding a
     * scope that both callers may carry would be a separation that separates nothing. The two halves are
     * enforced in different modules -- the rule here, the table in the shared kernel -- and neither alone
     * is the control.</p>
     *
     * <p>Assumptions: both groups are asserted to sit inside {@code decisionReadPaths()} as well, because
     * that is the matcher the chain composes its own boundary from; an address in a group the chain does
     * not claim reaches no handler at all.</p>
     */
    @Test
    @DisplayName("the card-number-disclosing address is governed by its own authority group alone")
    void theDisclosingAddressIsGovernedByItsOwnGroup() {
        var resolve = InternalApiSecurityConfig.cardXrefResolvePaths(
                PathPatternRequestMatcher.withDefaults());
        var read = InternalApiSecurityConfig.cardXrefPaths(
                PathPatternRequestMatcher.withDefaults());
        var decisions = InternalApiSecurityConfig.decisionReadPaths();
        var chain = InternalApiSecurityConfig.internalPaths();

        MockHttpServletRequest disclosing = request(HttpMethod.POST,
                CardXrefController.BASE_PATH + CardXrefController.LOOKUP_BY_ACCOUNT_PATH);
        List<MockHttpServletRequest> nonDisclosing = List.of(
                request(HttpMethod.POST,
                        CardXrefController.BASE_PATH + CardXrefController.LOOKUP_PATH),
                request(HttpMethod.POST,
                        CardXrefController.BASE_PATH + CardXrefController.SEARCH_BY_ACCOUNT_PATH));

        assertThat(resolve.matches(disclosing))
                .as("the account-keyed lookup is the address that answers with a whole card number")
                .isTrue();
        assertThat(read.matches(disclosing))
                .as("if the wider cross-reference group still claimed it, the rule order in the chain"
                        + " would be irrelevant and the split would grant nothing")
                .isFalse();
        assertThat(decisions.matches(disclosing)).isTrue();
        assertThat(chain.matches(disclosing)).isTrue();

        for (MockHttpServletRequest address : nonDisclosing) {
            assertThat(read.matches(address))
                    .as("%s %s discloses no card number", address.getMethod(), address.getRequestURI())
                    .isTrue();
            assertThat(resolve.matches(address))
                    .as("%s %s must not demand the disclosure scope, which is minted for one caller",
                            address.getMethod(), address.getRequestURI())
                    .isFalse();
            assertThat(decisions.matches(address)).isTrue();
            assertThat(chain.matches(address)).isTrue();
        }

        assertThat(InternalServiceToken.permits(InternalServiceToken.SUBJECT_TRANSACTION_SERVICE,
                InternalServiceToken.SCOPE_CARD_XREF_RESOLVE_CARD_NUMBER))
                .as("the transaction context keys its ledger row on this value, so it must reach it")
                .isTrue();
        assertThat(InternalServiceToken.permits(InternalServiceToken.SUBJECT_AUTHORIZATION_SERVICE,
                InternalServiceToken.SCOPE_CARD_XREF_RESOLVE_CARD_NUMBER))
                .as("the authorization context calls the card-keyed form only, so it must NOT reach it")
                .isFalse();
    }

    /**
     * Verifies the enumerated address census agrees with the matcher the chain is built from.
     *
     * <p>Purpose: {@code internalAddresses()} is a second derivation of the same addresses, existing so
     * that the error-dispatch matcher can decide against the ORIGINAL request target -- which survives an
     * error dispatch only as an attribute. Two derivations can drift, and the symptom of drift is silent:
     * an address added to a path group but not to the census would have its error page authenticated by
     * the identity provider's decoder, and the only visible effect would be an occasional 401 replacing an
     * occasional 406.</p>
     *
     * <p>Assumptions: each address is offered under both methods and accepted if EITHER matches, because
     * the census is deliberately method-free while the path groups are method-bound -- the collection
     * address publishes GET and every other address publishes POST.</p>
     */
    @Test
    @DisplayName("every enumerated internal address is claimed by the chain's own matcher")
    void theAddressCensusAgreesWithTheChainMatcher() {
        var chain = InternalApiSecurityConfig.internalPaths();

        assertThat(InternalApiSecurityConfig.internalAddresses())
                .as("eight addresses are claimed: three cross-reference, one account and four customer")
                .hasSize(8)
                .doesNotHaveDuplicates();

        for (String address : InternalApiSecurityConfig.internalAddresses()) {
            boolean claimed = chain.matches(request(HttpMethod.POST, address))
                    || chain.matches(request(HttpMethod.GET, address));
            assertThat(claimed)
                    .as("%s is enumerated for the error-dispatch matcher but claimed by no path group,"
                            + " so its error page would fall to the identity-provider chain", address)
                    .isTrue();
        }
    }

    /**
     * Verifies the container's ERROR dispatch of an internal request stays on THIS chain, and that no other
     * dispatch is drawn onto it.
     *
     * <p>Purpose: an error dispatch reaches the filters as a request to the deployment's error page, so
     * without this matcher it belongs to the identity-provider chain -- which would hand the caller's
     * still-present machine token to a decoder pinned to the provider's issuer and keys, and refuse it.
     * The caller's real failure would be replaced by a 401 attributing it to authentication.</p>
     *
     * <p>Assumptions: four cases are asserted together because the property is a conjunction, and each
     * clause fails a different way if dropped. An error dispatch of an internal address must be claimed;
     * an error dispatch of an END-USER address must not be, or the machine decoder would authenticate a
     * browser caller's error page; an ordinary request to the error path must not be, or a caller could
     * reach the container's error body under a machine credential; and the context path must be tolerated,
     * because the attribute carries the full target while the census carries within-application paths.</p>
     */
    @Test
    @DisplayName("an error dispatch of an internal address is claimed and no other dispatch is")
    void theErrorDispatchOfAnInternalAddressIsClaimed() {
        var chain = InternalApiSecurityConfig.internalPaths();
        String internal = CardXrefController.BASE_PATH + CardXrefController.LOOKUP_BY_ACCOUNT_PATH;
        String endUser = AccountController.BASE_PATH + AccountController.VIEW_PATH;

        assertThat(chain.matches(errorDispatch(internal, "")))
                .as("the error page of an internal request must keep the machine-token decoder")
                .isTrue();
        assertThat(chain.matches(errorDispatch(endUser, "")))
                .as("the error page of an end-user request must stay on the identity-provider chain")
                .isFalse();
        assertThat(chain.matches(request(HttpMethod.GET, ERROR_PATH)))
                .as("an ordinary request to the error path is not an error dispatch and is not claimed")
                .isFalse();
        assertThat(chain.matches(errorDispatch(internal, "/account")))
                .as("the attribute carries the context path, so a deployment mounted under a prefix must"
                        + " still match")
                .isTrue();
    }

    /**
     * Builds a request for the matcher assertions.
     *
     * @param method the request method
     * @param path the request path
     * @return the request, never {@code null}
     */
    private static MockHttpServletRequest request(HttpMethod method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method.name(), path);
        request.setServletPath(path);
        return request;
    }

    /**
     * Builds the container's ERROR dispatch of a request that was addressed elsewhere.
     *
     * <p>Assumptions: the dispatch is addressed to the error path and carries the original target in the
     * standard attribute, which is exactly what a container does -- the request object is the same instance
     * with a new dispatcher type and the error attributes added. A test that merely set the dispatcher type
     * without the attribute would assert against a shape no container produces.</p>
     *
     * @param originalTarget the within-application path the failing request was addressed to; must not be
     *     {@code null}
     * @param contextPath the deployment's context path, empty for a root deployment; must not be
     *     {@code null}
     * @return the error-dispatched request, never {@code null}
     */
    private static MockHttpServletRequest errorDispatch(String originalTarget, String contextPath) {
        // WHY : Assumptions: the request target is built WITH the context path, because a container reports
        //   a request URI that includes it and the mock refuses a context path that is not a prefix of the
        //   URI -- which is the same invariant. Setting the servlet path to the error page alone keeps the
        //   two halves consistent for a matcher that reads either.
        MockHttpServletRequest request =
                new MockHttpServletRequest(HttpMethod.POST.name(), contextPath + ERROR_PATH);
        request.setContextPath(contextPath);
        request.setServletPath(ERROR_PATH);
        request.setDispatcherType(DispatcherType.ERROR);
        request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, contextPath + originalTarget);
        return request;
    }
}
