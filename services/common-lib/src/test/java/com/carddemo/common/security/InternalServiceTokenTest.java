package com.carddemo.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Asserts the shape and the bounds of the credential one bounded context presents to another.
 *
 * <h2>Purpose</h2>
 * <p>This class pins the four properties the receiving side verifies -- signature, issuer, audience and scope
 * -- plus the two the minter enforces on itself: a key long enough to key the digest, and a lifetime short
 * enough that a captured token is not a durable credential. Each is asserted separately because each fails
 * independently, and because a token missing any one of them would still verify against a receiver that checked
 * only the others.</p>
 *
 * <p>Assumptions: the minted tokens are parsed and verified with the underlying library rather than with the
 * framework's decoder. What is under test is what this class PRODUCES, and using the framework's consumer would
 * make the assertion contingent on that consumer's own defaults -- so a token missing a claim could pass
 * because the consumer supplied one.</p>
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no parameter,
 * return or exception section.</p>
 */
class InternalServiceTokenTest {

    /**
     * A key of exactly the minimum admissible length.
     */
    private static final byte[] KEY =
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);

    /**
     * A fixed instant so the issue and expiry claims are reproducible.
     */
    private static final Instant NOW = Instant.parse("2026-08-07T12:00:00Z");

    /**
     * The subject a minted token carries.
     */
    private static final String SUBJECT = InternalServiceToken.SUBJECT_AUTHORIZATION_SERVICE;

    /**
     * Builds a minter over the fixed key and clock.
     *
     * @param lifetime how long each minted token should be valid for
     * @return the minter, never {@code null}
     */
    private static InternalServiceToken minter(Duration lifetime) {
        return new InternalServiceToken(KEY, SUBJECT, Clock.fixed(NOW, ZoneOffset.UTC), lifetime);
    }

    /**
     * Parses a serialised token.
     *
     * @param serialised the token text
     * @return the parsed token, never {@code null}
     * @throws ParseException if the text is not a signed token, which would mean the minter produced
     *     something unusable
     */
    private static SignedJWT parse(String serialised) throws ParseException {
        return SignedJWT.parse(serialised);
    }

    /**
     * Verifies a minted token carries every claim the receiving side checks.
     *
     * @throws Exception if the minted token cannot be parsed, which would itself be the defect
     */
    @Test
    @DisplayName("a minted token carries the issuer, subject, audience, scope and both instants")
    void aMintedTokenCarriesEveryVerifiedClaim() throws Exception {
        SignedJWT token = parse(minter(Duration.ofMinutes(1)).mint(
                InternalServiceToken.AUDIENCE_ACCOUNT_CONTEXT,
                InternalServiceToken.SCOPE_CARD_XREF_READ));

        assertThat(token.getJWTClaimsSet().getIssuer()).isEqualTo(InternalServiceToken.ISSUER);
        assertThat(token.getJWTClaimsSet().getSubject()).isEqualTo(SUBJECT);
        assertThat(token.getJWTClaimsSet().getAudience())
                .containsExactly(InternalServiceToken.AUDIENCE_ACCOUNT_CONTEXT);
        assertThat(token.getJWTClaimsSet().getStringClaim(InternalServiceToken.SCOPE_CLAIM))
                .isEqualTo(InternalServiceToken.SCOPE_CARD_XREF_READ);
        assertThat(token.getJWTClaimsSet().getIssueTime().toInstant()).isEqualTo(NOW);
        assertThat(token.getJWTClaimsSet().getExpirationTime().toInstant())
                .isEqualTo(NOW.plus(Duration.ofMinutes(1)));
    }

    /**
     * Verifies the signature is over the shared key and that a different key does not verify it.
     *
     * <p>Assumptions: the negative half is asserted as well as the positive. A signature that verified under
     * any key would be no signature at all, and a test that only checked the correct key could not tell the
     * difference.</p>
     *
     * @throws Exception if the minted token cannot be parsed, which would itself be the defect
     */
    @Test
    @DisplayName("the signature verifies under the shared key and not under another")
    void theSignatureIsOverTheSharedKey() throws Exception {
        JWSObject token = parse(minter(Duration.ofMinutes(1)).mint(
                InternalServiceToken.AUDIENCE_ACCOUNT_CONTEXT,
                InternalServiceToken.SCOPE_CARD_XREF_READ));

        assertThat(token.verify(new MACVerifier(KEY))).isTrue();
        assertThat(token.verify(new MACVerifier(
                "fedcba9876543210fedcba9876543210".getBytes(StandardCharsets.UTF_8)))).isFalse();
    }

    /**
     * Verifies the header names exactly one algorithm and the type.
     *
     * <p>Assumptions: the algorithm is asserted on the emitted header because a receiver pinned to one
     * algorithm refuses anything else -- so a minter that emitted a different one would produce tokens the
     * receiver rejects, with no indication of which side was wrong.</p>
     *
     * @throws Exception if the minted token cannot be parsed, which would itself be the defect
     */
    @Test
    @DisplayName("the header names the pinned algorithm and the token type")
    void theHeaderNamesThePinnedAlgorithm() throws Exception {
        SignedJWT token = parse(minter(Duration.ofMinutes(1)).mint(
                InternalServiceToken.AUDIENCE_ACCOUNT_CONTEXT,
                InternalServiceToken.SCOPE_CARD_XREF_READ));

        assertThat(token.getHeader().getAlgorithm())
                .isEqualTo(InternalServiceToken.SIGNING_ALGORITHM);
        assertThat(token.getHeader().getType().toString()).isEqualTo("JWT");
    }

    /**
     * Verifies a key shorter than the digest width is refused at construction.
     */
    @Test
    @DisplayName("a key shorter than the digest width is refused")
    void aShortKeyIsRefused() {
        byte[] tooShort = new byte[InternalServiceToken.MIN_KEY_LENGTH - 1];

        assertThatThrownBy(() -> new InternalServiceToken(tooShort, SUBJECT,
                Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(String.valueOf(InternalServiceToken.MIN_KEY_LENGTH));
    }

    /**
     * Verifies a lifetime beyond the shared bound is refused, and one at the bound is accepted.
     *
     * <p>Assumptions: the bound exists so a configured lifetime cannot quietly become a durable credential, so
     * both sides of it are asserted -- a bound that refused the value at it would be off by one and would make
     * the documented maximum unreachable.</p>
     */
    @Test
    @DisplayName("a lifetime beyond the bound is refused and one at the bound is accepted")
    void theLifetimeIsBounded() {
        assertThatCode(() -> minter(InternalServiceToken.MAX_LIFETIME)).doesNotThrowAnyException();
        assertThatThrownBy(() -> minter(InternalServiceToken.MAX_LIFETIME.plusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("durable credential");
        assertThatThrownBy(() -> minter(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> minter(Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Verifies a subject outside the closed set is refused, blank included.
     *
     * <p>Refactoring Rationale: this case asserted only that a BLANK subject was refused, which was the whole
     * of the old rule. The subject is now load-bearing in two further ways -- it identifies the signing key in
     * the token header and it keys the table of scopes the caller may carry -- so any unrecognised value names
     * a caller the verifying context holds no key for, and every token minted under it would be refused there.
     * Both the blank form and a plausible-looking unknown form are asserted, because the second is the one a
     * misconfiguration actually produces.</p>
     */
    @Test
    @DisplayName("a subject outside the closed set is refused, blank or otherwise")
    void aSubjectOutsideTheClosedSetIsRefused() {
        assertThatThrownBy(() -> new InternalServiceToken(KEY, "   ",
                Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("subject");
        assertThatThrownBy(() -> new InternalServiceToken(KEY, "carddemo-reporting-service",
                Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("carddemo-reporting-service");
    }

    /**
     * Verifies the header names the signing key by the subject it belongs to.
     *
     * <p>Assumptions: this is the claim that makes per-caller keys work. Without a key identifier the verifier
     * would have to try every key it holds, which admits a token signed by one caller under another caller's
     * name -- so the header value is asserted to EQUAL the subject rather than merely to be present.</p>
     *
     * @throws Exception if the minted token cannot be parsed, which would itself be the defect
     */
    @Test
    @DisplayName("the header identifies the signing key by the caller's own subject")
    void theHeaderIdentifiesTheKeyBySubject() throws Exception {
        SignedJWT token = parse(minter(Duration.ofMinutes(1)).mint(
                InternalServiceToken.AUDIENCE_ACCOUNT_CONTEXT,
                InternalServiceToken.SCOPE_CARD_XREF_READ));

        assertThat(token.getHeader().getKeyID()).isEqualTo(SUBJECT);
        assertThat(token.getJWTClaimsSet().getSubject()).isEqualTo(token.getHeader().getKeyID());
    }

    /**
     * Verifies a caller may not mint a scope its own subject is not permitted to carry.
     *
     * <p>Assumptions: the transaction context is the subject asserted against, because it is the one with a
     * genuinely narrower entitlement: it calls ONE operation family on the account context, the card
     * cross-reference lookup, so both the account and the customer scopes are withheld from it. Asserting the
     * permitted scopes as well as the two refused ones is what stops the case passing on a table that
     * permitted nothing at all.</p>
     *
     * <p>Refactoring Rationale: the permitted half of this case has been rewritten twice, each time because
     * the table changed rather than because the assertion was wrong. It named two scopes until the
     * transaction context stopped reading the account master over HTTP -- its bill payment reads and reduces
     * the balance locally now, under a named cross-schema grant, so nothing in it mints the account scope,
     * and the refused half gained that entry, which is the direction that matters. It names two again now
     * for an unrelated reason: the cross-reference family itself was split, because ONE of its three
     * addresses answers with an unmasked primary account number, and the scope covering that address is
     * granted to this caller and withheld from the other. The two changes must not be read as one -- the
     * first removed a reach nothing used, the second bounded a disclosure that is required for parity.</p>
     */
    @Test
    @DisplayName("a caller cannot mint a scope outside its own permitted set")
    void aCallerCannotMintAScopeItIsNotPermittedToCarry() {
        InternalServiceToken transactionMinter = new InternalServiceToken(KEY,
                InternalServiceToken.SUBJECT_TRANSACTION_SERVICE,
                Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(1));

        // WHY : Refactoring Rationale: the transaction row carries TWO cross-reference scopes where it
        //       carried one, and the second is asserted here rather than only where it is used. The
        //       account-keyed lookup answers with an unmasked primary account number and the other two
        //       cross-reference addresses answer with none, so the disclosure was split onto its own
        //       scope and granted to this caller alone -- it keys its ledger row on that value. Both are
        //       named because the ordering of the returned set is alphabetical and asserting one would
        //       pass against a table that had lost the other.
        assertThat(InternalServiceToken.permittedScopes(
                InternalServiceToken.SUBJECT_TRANSACTION_SERVICE))
                .containsExactly(InternalServiceToken.SCOPE_CARD_XREF_READ,
                        InternalServiceToken.SCOPE_CARD_XREF_RESOLVE_CARD_NUMBER);
        // WHY : Assumptions: the OTHER caller's refusal of that same scope is asserted here, in the case
        //       that owns the table, because the narrowing is a property of the table rather than of
        //       either service. The authorization context calls the card-keyed lookup only, so a
        //       credential it mints must not resolve an account to a clear card number; without this
        //       assertion a table that granted the scope to both callers would leave every other case in
        //       this class passing.
        assertThat(InternalServiceToken.permits(InternalServiceToken.SUBJECT_AUTHORIZATION_SERVICE,
                InternalServiceToken.SCOPE_CARD_XREF_RESOLVE_CARD_NUMBER)).isFalse();
        assertThatThrownBy(() -> transactionMinter.mint(
                InternalServiceToken.AUDIENCE_ACCOUNT_CONTEXT,
                InternalServiceToken.SCOPE_CUSTOMER_READ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(InternalServiceToken.SCOPE_CUSTOMER_READ);
        assertThatThrownBy(() -> transactionMinter.mint(
                InternalServiceToken.AUDIENCE_ACCOUNT_CONTEXT,
                InternalServiceToken.SCOPE_ACCOUNT_READ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(InternalServiceToken.SCOPE_ACCOUNT_READ);
        assertThat(transactionMinter.mint(InternalServiceToken.AUDIENCE_ACCOUNT_CONTEXT,
                InternalServiceToken.SCOPE_CARD_XREF_READ)).isNotBlank();
    }

    /**
     * Verifies the shared table answers both questions a verifier asks and answers them consistently.
     *
     * <p>Assumptions: the unknown-subject case is asserted to permit NOTHING rather than to be absent from the
     * table, because a verifier calls the predicate rather than inspecting the table -- so a predicate that
     * defaulted an unknown subject to a permissive answer would admit a caller nobody declared.</p>
     */
    @Test
    @DisplayName("the shared table names exactly two callers and permits nothing to any other")
    void theSharedTableNamesExactlyTheKnownCallers() {
        assertThat(InternalServiceToken.knownSubjects())
                .containsExactly(InternalServiceToken.SUBJECT_AUTHORIZATION_SERVICE,
                        InternalServiceToken.SUBJECT_TRANSACTION_SERVICE);
        assertThat(InternalServiceToken.isKnownSubject("carddemo-reporting-service")).isFalse();
        assertThat(InternalServiceToken.isKnownSubject(null)).isFalse();
        assertThat(InternalServiceToken.permittedScopes("carddemo-reporting-service")).isEmpty();
        assertThat(InternalServiceToken.permits("carddemo-reporting-service",
                InternalServiceToken.SCOPE_CARD_XREF_READ)).isFalse();
        assertThat(InternalServiceToken.permits(
                InternalServiceToken.SUBJECT_AUTHORIZATION_SERVICE, null)).isFalse();
        assertThat(InternalServiceToken.permits(
                InternalServiceToken.SUBJECT_AUTHORIZATION_SERVICE,
                InternalServiceToken.SCOPE_CUSTOMER_READ)).isTrue();
    }

    /**
     * Verifies the key is copied on the way in and on the way out.
     *
     * <p>Assumptions: both directions are asserted. Copying only on the way in would still hand a caller a
     * live reference through the accessor; copying only on the way out would let a caller that cleared its own
     * array empty this instance's key, so the minter would start producing signatures over zeroes.</p>
     *
     * @throws Exception if the minted token cannot be parsed, which would itself be the defect
     */
    @Test
    @DisplayName("the key is copied on the way in and on the way out")
    void theKeyIsCopiedInBothDirections() throws Exception {
        byte[] caller = KEY.clone();
        InternalServiceToken minter =
                new InternalServiceToken(caller, SUBJECT, Clock.fixed(NOW, ZoneOffset.UTC),
                        Duration.ofMinutes(1));
        java.util.Arrays.fill(caller, (byte) 0);

        JWSObject token = parse(minter.mint(InternalServiceToken.AUDIENCE_ACCOUNT_CONTEXT,
                InternalServiceToken.SCOPE_CARD_XREF_READ));
        assertThat(token.verify(new MACVerifier(KEY)))
                .as("clearing the caller's array must not have emptied the minter's key")
                .isTrue();

        byte[] handedOut = minter.keyMaterial();
        java.util.Arrays.fill(handedOut, (byte) 0);
        assertThat(minter.keyMaterial())
                .as("clearing a handed-out copy must not have emptied the minter's key")
                .isEqualTo(KEY);
    }

    /**
     * Verifies the diagnostic rendering discloses no key material.
     */
    @Test
    @DisplayName("the diagnostic rendering discloses no key material")
    void theRenderingDisclosesNoKey() {
        String rendered = minter(Duration.ofMinutes(1)).toString();

        assertThat(rendered)
                .contains(SUBJECT)
                .contains("keyLengthBytes=" + InternalServiceToken.MIN_KEY_LENGTH)
                .doesNotContain(new String(KEY, StandardCharsets.UTF_8));
    }

    /**
     * Verifies two mints under one minter produce byte-identical tokens under a fixed clock.
     *
     * <p>Assumptions: determinism under a FIXED clock is asserted so that the per-call minting the client
     * performs is known not to introduce a random element -- which would make a captured token
     * unattributable and would defeat any future deduplication. Under a real clock the issue instant advances,
     * which is what makes two tokens differ in practice.</p>
     */
    @Test
    @DisplayName("minting is deterministic under a fixed clock")
    void mintingIsDeterministicUnderAFixedClock() {
        InternalServiceToken minter = minter(Duration.ofMinutes(1));

        assertThat(minter.mint(InternalServiceToken.AUDIENCE_ACCOUNT_CONTEXT,
                InternalServiceToken.SCOPE_CARD_XREF_READ))
                .isEqualTo(minter.mint(InternalServiceToken.AUDIENCE_ACCOUNT_CONTEXT,
                        InternalServiceToken.SCOPE_CARD_XREF_READ));
    }

    /**
     * Verifies the shared lifetime rule admits exactly what the minting constructor would issue.
     *
     * <p>⚠️ Purpose: this rule is the VERIFYING half of a bound that used to exist only on the minting side.
     * A bound applied solely by the party that chooses to honour it is not a bound: a token declaring thirty
     * minutes, signed with a real key and naming a real caller, was accepted for as long as it declared. The
     * agreement asserted here is what makes the two halves one rule -- a lifetime the constructor accepts is
     * a lifetime this rule admits, and a lifetime the constructor refuses is one this rule refuses.</p>
     *
     * <p>Assumptions: the boundary is asserted from BOTH sides at exactly the bound, because an off-by-one in
     * either direction is the failure mode a single-sided assertion cannot see: admitting one second past the
     * bound weakens it silently, and refusing a token exactly at it would refuse every token the minter
     * issues at its own maximum.</p>
     */
    @Test
    @DisplayName("the lifetime rule admits at the bound and refuses one second past it")
    void theLifetimeRuleHoldsTheSameBoundAsTheConstructor() {
        assertThat(InternalServiceToken.isWithinMaximumLifetime(
                NOW, NOW.plus(InternalServiceToken.MAX_LIFETIME)))
                .as("a token declaring exactly the bound is one the minter itself would issue")
                .isTrue();
        assertThat(InternalServiceToken.isWithinMaximumLifetime(
                NOW, NOW.plus(InternalServiceToken.MAX_LIFETIME).plusSeconds(1)))
                .as("one second past the bound is refused, as the constructor refuses it")
                .isFalse();
        assertThat(InternalServiceToken.isWithinMaximumLifetime(NOW, NOW.plus(Duration.ofMinutes(30))))
                .as("the thirty-minute credential the verifier used to accept")
                .isFalse();
        assertThat(InternalServiceToken.isWithinMaximumLifetime(NOW, NOW.plus(Duration.ofMinutes(1))))
                .as("an ordinary one-minute token")
                .isTrue();
    }

    /**
     * Verifies an absent claim, and a self-contradictory pair, are refused rather than passed over.
     *
     * <p>⚠️ Assumptions: each of these three is a BYPASS if it is admitted, which is why they are asserted
     * separately from the bound itself. A token with no issue time declares no lifetime to bound, so a minter
     * wanting an unbounded credential would need only to omit the claim. A token with no expiry is refused by
     * nothing else at all -- the framework's timestamp validator checks an expiry only when one is present.
     * And an expiry BEFORE the issue time yields a negative duration, which is at most the bound by any
     * comparison, so without an explicit clause it would satisfy the rule while being internally
     * contradictory.</p>
     */
    @Test
    @DisplayName("an absent issue time, an absent expiry and a backwards pair are all refused")
    void theLifetimeRuleRefusesAnythingItCannotBound() {
        assertThat(InternalServiceToken.isWithinMaximumLifetime(null, NOW.plusSeconds(60)))
                .as("no issue time means no declared lifetime to bound")
                .isFalse();
        assertThat(InternalServiceToken.isWithinMaximumLifetime(NOW, null))
                .as("no expiry is otherwise refused by nothing")
                .isFalse();
        assertThat(InternalServiceToken.isWithinMaximumLifetime(null, null))
                .as("neither claim present")
                .isFalse();
        assertThat(InternalServiceToken.isWithinMaximumLifetime(NOW, NOW.minusSeconds(1)))
                .as("an expiry before the issue time is contradictory, not merely late")
                .isFalse();
    }
}
