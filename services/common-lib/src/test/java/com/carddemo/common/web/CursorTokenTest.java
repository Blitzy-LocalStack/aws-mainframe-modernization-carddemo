package com.carddemo.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.common.web.CursorToken.InvalidCursorException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that a keyset cursor leaves this system only as a sealed, authenticated, bound and bounded
 * token, and that every refusal withholds what it refused.
 *
 * <p>Assumptions: the raw cursor used throughout is the 27-character composite the card list produces,
 * a 16-digit card number followed by an 11-digit account identifier, per lines 230 to 232 of
 * {@code app/cbl/COCRDLIC.cbl}. Using the real shape rather than a short placeholder is what makes the
 * length assertions meaningful, and it is also the exact value whose exposure these tests exist to
 * prevent.</p>
 */
class CursorTokenTest {

    /** Key material of the required width, fixed so that a sealed token is reproducible. */
    private static final byte[] KEY =
            "carddemo-cursor-key-for-tests-32".getBytes(StandardCharsets.UTF_8);

    /** The binding a token is issued under: one query name and one authenticated subject. */
    private static final String BINDING = "card-list|subject-6f1c2d";

    /** The raw composite cursor the card list query yields, which must never reach a client. */
    private static final String RAW_CURSOR = "411111111111111100000000011";

    /** A lifetime long enough that no test is timing-dependent. */
    private static final Duration LIFETIME = Duration.ofMinutes(30);

    /**
     * Confirms the round trip: a token sealed from a raw composite cursor opens back to that exact
     * cursor, so the repository predicate can be rebuilt from what the client returned.
     */
    @Test
    @DisplayName("a sealed token opens back to the exact key it was sealed from")
    void sealedTokenOpensToItsKey() {
        CursorToken sealer = new CursorToken(KEY, LIFETIME);

        String token = sealer.seal(BINDING, RAW_CURSOR);

        assertThat(sealer.open(BINDING, token)).isEqualTo(RAW_CURSOR);
    }

    /**
     * Confirms the token does not spell out the key it carries, which is the property that keeps a
     * primary account number out of a page response body.
     */
    @Test
    @DisplayName("a sealed token carries neither the card number nor the account identifier verbatim")
    void sealedTokenDoesNotCarryTheKeyVerbatim() {
        CursorToken sealer = new CursorToken(KEY, LIFETIME);

        String token = sealer.seal(BINDING, RAW_CURSOR);

        // WHY : Assumptions: this asserts the property a client can observe -- that the characters of
        //       the key are not present in the token -- rather than asserting the encoding used. The
        //       encoding may change; the requirement that a page response not spell out a primary
        //       account number may not.
        assertThat(token)
                .doesNotContain(RAW_CURSOR)
                .doesNotContain(RAW_CURSOR.substring(0, 16))
                .hasSizeLessThanOrEqualTo(CursorToken.MAX_TOKEN_LENGTH);
    }

    /**
     * Confirms a token whose payload is edited by one character fails to open, so a client cannot turn
     * a page cursor into a key predicate of its own choosing.
     */
    @Test
    @DisplayName("a token altered in its payload fails authentication")
    void alteredPayloadFailsAuthentication() {
        CursorToken sealer = new CursorToken(KEY, LIFETIME);
        String token = sealer.seal(BINDING, RAW_CURSOR);

        int firstDot = token.indexOf('.');
        char original = token.charAt(firstDot + 1);
        char replacement = original == 'A' ? 'B' : 'A';
        String tampered = token.substring(0, firstDot + 1) + replacement
                + token.substring(firstDot + 2);

        assertThatThrownBy(() -> sealer.open(BINDING, tampered))
                .isInstanceOf(InvalidCursorException.class)
                .hasMessageContaining("failed authentication");
    }

    /**
     * Confirms a token is bound to the query and subject it was issued for, so one user's cursor cannot
     * be replayed as another's.
     */
    @Test
    @DisplayName("a token issued for one binding cannot be opened under another")
    void tokenIsBoundToItsQueryAndSubject() {
        CursorToken sealer = new CursorToken(KEY, LIFETIME);
        String token = sealer.seal(BINDING, RAW_CURSOR);

        assertThatThrownBy(() -> sealer.open("card-list|subject-other", token))
                .isInstanceOf(InvalidCursorException.class)
                .hasMessageContaining("failed authentication");
    }

    /**
     * Confirms a token cannot be opened with key material other than the material it was sealed under.
     */
    @Test
    @DisplayName("a token minted under different key material fails authentication")
    void tokenIsBoundToItsKeyMaterial() {
        String token = new CursorToken(KEY, LIFETIME).seal(BINDING, RAW_CURSOR);
        CursorToken other = new CursorToken(
                "a-different-key-of-thirty-two-by".getBytes(StandardCharsets.UTF_8), LIFETIME);

        assertThatThrownBy(() -> other.open(BINDING, token))
                .isInstanceOf(InvalidCursorException.class)
                .hasMessageContaining("failed authentication");
    }

    /**
     * Confirms a token older than its configured lifetime is refused, which bounds both a stale cursor
     * and a leaked one.
     */
    @Test
    @DisplayName("a token older than the configured lifetime is refused")
    void expiredTokenIsRefused() {
        // WHY : Assumptions: expiry is exercised by opening with an instance whose lifetime is the
        //       smallest positive value rather than by holding the test still or by injecting a clock.
        //       One second is already elapsed by the time a token sealed in the previous statement is
        //       presented in a later one only intermittently, so the token is sealed by an instance
        //       carrying the same key and then opened after a brief, bounded wait. This keeps the
        //       assertion deterministic without adding a clock seam to production code that has no
        //       other use for one.
        CursorToken sealer = new CursorToken(KEY, Duration.ofSeconds(1));
        String token = sealer.seal(BINDING, RAW_CURSOR);

        await(1_100);

        assertThatThrownBy(() -> sealer.open(BINDING, token))
                .isInstanceOf(InvalidCursorException.class)
                .hasMessageContaining("was issued more than");
    }

    /**
     * Confirms a raw keyset cursor is refused as a token and that the refusal does not reproduce it.
     */
    @Test
    @DisplayName("a raw keyset cursor is not a sealed token and is refused without being quoted")
    void rawCursorIsRefusedAndNotQuoted() {
        CursorToken sealer = new CursorToken(KEY, LIFETIME);

        assertThatThrownBy(() -> sealer.open(BINDING, RAW_CURSOR))
                .isInstanceOf(InvalidCursorException.class)
                .hasMessageContaining("is not a sealed token")
                .hasMessageNotContaining(RAW_CURSOR);
    }

    /**
     * Confirms an over-long token is refused on its length, before any decoding or authentication work
     * is attempted on client-supplied bytes.
     */
    @Test
    @DisplayName("a token longer than the bound is refused before anything is decoded")
    void oversizedTokenIsRefused() {
        CursorToken sealer = new CursorToken(KEY, LIFETIME);
        String oversized = "v1." + "A".repeat(CursorToken.MAX_TOKEN_LENGTH) + ".B";

        assertThatThrownBy(() -> sealer.open(BINDING, oversized))
                .isInstanceOf(InvalidCursorException.class)
                .hasMessageContaining("exceeding the " + CursorToken.MAX_TOKEN_LENGTH);
    }

    /**
     * Confirms sealing refuses a key wider than a reference composite cursor, so an over-wide token is
     * never minted rather than being minted and refused later.
     */
    @Test
    @DisplayName("sealing refuses a key wider than a reference composite cursor")
    void oversizedKeyIsRefusedAtSealTime() {
        CursorToken sealer = new CursorToken(KEY, LIFETIME);
        String tooWide = "9".repeat(CursorToken.MAX_KEY_LENGTH + 1);

        assertThatThrownBy(() -> sealer.seal(BINDING, tooWide))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeding the " + CursorToken.MAX_KEY_LENGTH);
    }

    /**
     * Confirms key material narrower than the digest width is refused at construction, since a short key
     * weakens the code while still producing tokens that verify.
     */
    @Test
    @DisplayName("key material shorter than the digest width is refused at construction")
    void shortKeyMaterialIsRefused() {
        byte[] tooShort = "short-key".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> new CursorToken(tooShort, LIFETIME))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least " + CursorToken.MIN_KEY_LENGTH);
    }

    /**
     * Confirms a blank binding is refused, because a token bound to nothing is replayable everywhere.
     */
    @Test
    @DisplayName("a blank binding is refused, because it would bind a token to nothing")
    void blankBindingIsRefused() {
        CursorToken sealer = new CursorToken(KEY, LIFETIME);

        assertThatThrownBy(() -> sealer.seal("   ", RAW_CURSOR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be blank");
    }

    /**
     * Confirms the paging envelope refuses a raw keyset cursor in either boundary component, and names
     * the component without quoting the value.
     */
    @Test
    @DisplayName("the paging envelope refuses a raw keyset cursor in either boundary component")
    void pageResponseRefusesRawCursor() {
        List<String> rows = List.of("row-one", "row-two");
        CursorToken sealer = new CursorToken(KEY, LIFETIME);
        String sealed = sealer.seal(BINDING, RAW_CURSOR);

        // Assumptions: the OTHER boundary component carries a sealed token in each case, because the
        //   envelope settles its row-count invariant -- a page carrying rows must name both of its ends
        //   -- before it inspects either token's shape. Supplying one raw component and one sealed one
        //   is what makes the refusal under test the reachable one rather than the invariant above it.
        assertThatThrownBy(() -> new PageResponse<>(rows, RAW_CURSOR, sealed, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("firstKey")
                .hasMessageNotContaining(RAW_CURSOR);

        assertThatThrownBy(() -> new PageResponse<>(rows, sealed, RAW_CURSOR, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lastKey")
                .hasMessageNotContaining(RAW_CURSOR);

        // Assumptions: the filtered-away page reaches the same two components by a different route, so
        //   it is exercised separately. Its scan positions ARE the boundary components -- the envelope
        //   carries four members and has none of its own for them -- so a raw key handed to either
        //   parameter of that factory has to be refused by the same check, naming the component it
        //   landed in rather than the parameter it arrived through.
        assertThatThrownBy(() -> PageResponse.<String>ofFilteredEmpty(RAW_CURSOR, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lastKey")
                .hasMessageNotContaining(RAW_CURSOR);

        assertThatThrownBy(() -> PageResponse.<String>ofFilteredEmpty(null, RAW_CURSOR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("firstKey")
                .hasMessageNotContaining(RAW_CURSOR);
    }

    /**
     * Confirms the paging envelope accepts sealed tokens in both boundary components, keeps the
     * filtered-away page expressible from those same two, and still admits the exhausted page that
     * carries no cursor at all.
     */
    @Test
    @DisplayName("the paging envelope accepts sealed tokens and an absent cursor")
    void pageResponseAcceptsSealedTokens() {
        CursorToken sealer = new CursorToken(KEY, LIFETIME);
        String first = sealer.seal(BINDING, RAW_CURSOR);
        String last = sealer.seal(BINDING, "411111111111111200000000012");

        PageResponse<String> page = new PageResponse<>(List.of("row"), first, last, true);

        assertThat(page.firstKey()).isEqualTo(first);
        assertThat(page.lastKey()).isEqualTo(last);
        assertThat(page.hasNext()).isTrue();

        // Assumptions: the filtered-away page is asserted here because it is the state the envelope's
        //   four components have to carry without components of their own. Its forward scan position
        //   lands in lastKey and its backward one in firstKey, and a further page is reported from the
        //   presence of the forward position alone, so a caller honouring the indicator always holds
        //   the token to send back.
        PageResponse<String> filtered = PageResponse.ofFilteredEmpty(last, first);

        assertThat(filtered.items()).isEmpty();
        assertThat(filtered.lastKey()).isEqualTo(last);
        assertThat(filtered.firstKey()).isEqualTo(first);
        assertThat(filtered.hasNext()).isTrue();

        assertThat(PageResponse.<String>empty().firstKey()).isNull();
    }

    /**
     * Waits for a bounded number of milliseconds so that a one-second token lifetime can elapse.
     *
     * <p>Assumptions: the interruption is restored rather than swallowed or wrapped, because a test
     * runner cancelling a test is entitled to have that cancellation observed by whatever runs next.</p>
     *
     * @param millis how long to wait, chosen just above the lifetime under test so the assertion does
     *     not depend on scheduling luck
     */
    private static void await(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
