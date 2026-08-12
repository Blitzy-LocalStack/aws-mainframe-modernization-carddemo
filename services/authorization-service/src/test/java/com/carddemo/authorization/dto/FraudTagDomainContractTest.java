package com.carddemo.authorization.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.common.money.Money;
import com.carddemo.common.web.CursorToken;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Pins the wire domain of the fraud tag a detail body publishes to two values and absence.
 *
 * <h2>Purpose</h2>
 * <p>Assert that a blank fraud tag can never reach a response: the detail body's own constructor maps it
 * to {@code null}, so an untagged authorization has exactly ONE spelling on the wire, and that any value
 * outside the two declared tags is refused outright.
 *
 * <p>Refactoring Rationale: the stored column deliberately admits a blank as well as the two tags and
 * null, because a blank is what the extract load lands for a segment nobody has tagged — the reference
 * field {@code PA-AUTH-FRAUD} at {@code cpy/CIPAUDTY.cpy} L50 declares condition names only for the
 * confirmed and removed states at L51 and L52. The published contract used to admit the blank too, which
 * left one state with two spellings and obliged every client to test for both; a client that tested for
 * only one of them rendered an untagged authorization as tagged. Normalising at this boundary makes the
 * blank unobservable without refusing a row the load legitimately produces, and this class is what keeps
 * the normalisation in place.
 *
 * <p>Alternatives Considered: asserting the domain through the contract document alone, by reading the
 * enum out of {@code authorization-api.yaml}. Rejected as the primary check because a schema states an
 * intention about a response while this constructor decides what a response can contain; a document
 * assertion would keep passing after the normalisation was removed. The document and this class have to
 * agree, and this is the half that can fail on a real value.
 *
 * <p>Trade-offs: the class is annotated {@code PER_CLASS} so the sealer that mints the required row
 * selector is derived once rather than per test, which is the same trade the sibling masked-card-number
 * contract accepts for the same reason.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
final class FraudTagDomainContractTest {

    /** The selector binding, which any constant satisfies because nothing here reopens the token. */
    private static final String BINDING = "pending-auth-detail";

    /** The sealed row selector every detail body requires, minted once for this class. */
    private final String sealedKey = sealer().seal(BINDING, "00000000001|2026015|14300000");

    /**
     * Builds a sealer over deterministic key material of the minimum accepted length.
     *
     * <p>Assumptions: the key material is fixed rather than random so a failure is reproducible, and it
     * is sized from {@link CursorToken#MIN_KEY_LENGTH} rather than written as a literal so a change to
     * that floor cannot leave this class constructing a sealer the type refuses. Nothing here asserts a
     * token's TEXT, only that a genuinely sealed selector is supplied.
     *
     * @return a sealer whose tokens satisfy {@link CursorToken#hasSealedShape(String)}; never
     *     {@code null}
     */
    private static CursorToken sealer() {
        byte[] keyMaterial = new byte[CursorToken.MIN_KEY_LENGTH];
        for (int index = 0; index < keyMaterial.length; index++) {
            keyMaterial[index] = (byte) (index + 1);
        }
        return new CursorToken(keyMaterial, Duration.ofMinutes(5));
    }

    /**
     * Builds one detail body carrying the supplied fraud tag and nothing else unusual.
     *
     * @param authFraud the fraud tag to place on the body, which may be {@code null} or blank
     * @return the constructed detail body, never {@code null}
     */
    private PendingAuthDetailView detailTaggedWith(String authFraud) {
        return new PendingAuthDetailView(this.sealedKey, "00000000001", 2026015, 14300000,
                "2026-01-15", "14:30:00", "************0011", "01", "2812", "0100", "POS", "143000",
                "00", "0000", "000000", Money.of("125.50"), Money.of("125.50"), "5411", "840", "05",
                "000000000", "CORNER STORE", "SEATTLE", "WA", "981010000", "000000000000001",
                "P", authFraud, null);
    }

    /**
     * Asserts a stored blank is published as absence rather than as a second spelling of it.
     *
     * <p>Assumptions: every width of whitespace the stored column could hold is exercised, not only a
     * single space. The column is one character wide so a single space is the realistic value, but the
     * normalisation is written against blankness rather than against that one string, and a test that
     * pinned only the one-character case would keep passing if the guard were narrowed to it — leaving a
     * value that arrives from any other loader unhandled.
     *
     * @param stored the blank value the row is assumed to carry
     */
    @ParameterizedTest
    @ValueSource(strings = {" ", "  ", "\t"})
    @DisplayName("a blank fraud tag is published as null, so untagged has one spelling")
    void aBlankTagIsNormalisedToAbsence(String stored) {
        assertThat(detailTaggedWith(stored).authFraud())
                .as("a blank is the extract load's landing state and must not reach a caller")
                .isNull();
    }

    /** Asserts an absent tag stays absent, which is the ordinary state of an untagged authorization. */
    @Test
    @DisplayName("an absent fraud tag stays absent")
    void anAbsentTagStaysAbsent() {
        assertThat(detailTaggedWith(null).authFraud()).isNull();
    }

    /**
     * Asserts both declared tags survive unchanged, so the normalisation narrows nothing it should not.
     *
     * @param tag one of the two tags the reference declares condition names for
     */
    @ParameterizedTest
    @ValueSource(strings = {"F", "R"})
    @DisplayName("both declared fraud tags are published unchanged")
    void bothDeclaredTagsArePublishedUnchanged(String tag) {
        assertThat(detailTaggedWith(tag).authFraud()).isEqualTo(tag);
        assertThat(PendingAuthDetailView.FRAUD_TAGS).contains(tag);
    }

    /**
     * Asserts a value outside the two tags is refused rather than published.
     *
     * <p>Assumptions: lower case is included among the refused values because the reference moves an
     * upper-case literal and a case-insensitive comparison anywhere in the chain would let {@code "f"}
     * stand for a fraud report on one path and for nothing on another.
     *
     * @param candidate a value the published domain does not contain
     */
    @ParameterizedTest
    @ValueSource(strings = {"X", "f", "r", "FF", "0"})
    @DisplayName("a fraud tag outside the two declared values is refused")
    void aTagOutsideTheDomainIsRefused(String candidate) {
        assertThatThrownBy(() -> detailTaggedWith(candidate))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("authFraud");
    }

    /** Asserts the published domain is exactly the two tags the reference declares condition names for. */
    @Test
    @DisplayName("the published fraud domain is exactly F and R")
    void thePublishedDomainIsExactlyTheTwoTags() {
        assertThat(PendingAuthDetailView.FRAUD_TAGS).containsExactly("F", "R");
    }
}
