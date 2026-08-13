package com.carddemo.account.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.account.domain.CardXref;
import com.carddemo.account.dto.CardXrefResponse;
import com.carddemo.common.security.MaskedCardNumber;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the published form of a cross-reference row: the masked primary account number, the two zero-padded
 * identifiers, and the refusal of a stored key that is not the declared width.
 *
 * <p>⚠️ Refactoring Rationale: this class did not exist, and the gap it closes was not a matter of degree.
 * Every other test that touches {@link CardXrefMapper} SUBSTITUTES it -- the service tests, the contract test
 * and the route tests all pass a mock -- so the one place in this module that decides how much of a primary
 * account number reaches a caller had no assertion of any kind against it. The consequence was live: the
 * mapper composed a masked value as a four-character marker followed by the last four digits, an
 * eight-character string, while the published contract declares exactly twelve mask characters and four
 * digits and {@code requireMaskedCardNumber} in {@code ui/src/api/accounts.ts} throws a RangeError on
 * anything else. Every populated response of the end-user cross-reference page was therefore rejected by its
 * own client, and no test in the module could observe it because the class producing the value was never the
 * class under test.
 *
 * <p>Assumptions: the expected masked form is DERIVED from {@code MaskedCardNumber}, not written out. That
 * type composes its pattern from the two constants the shared renderer masks with, so an assertion built from
 * it fails when the rule changes rather than when this file is edited -- which is the whole reason the rule
 * has one owner. A literal here would have reproduced exactly the defect described above, one layer up.
 *
 * <p>Measured: reverting the mapper to the four-character marker and re-running this class fails exactly one
 * case, {@code theCardNumberIsPublishedInTheSharedMaskedForm}, on the shared-domain assertion -- and
 * {@code CardXrefControllerTest} still passes all twenty, because its fixtures are composed from the same
 * constants the mapper uses. That pairing is the point: the slice tests cannot detect this defect at all,
 * which is why it survived, and this class can.
 *
 * <p>Assumptions: the identifiers are asserted at their declared widths rather than merely non-blank. The
 * baseline declares {@code XREF-CUST-ID PIC 9(09)} and {@code XREF-ACCT-ID PIC 9(11)} as fixed-width fields,
 * so a leading zero is part of the value and a rendering that dropped it would produce a different key for
 * the same row.
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception at-clause.
 */
@DisplayName("Cross-reference mapping: the masked card number and the two fixed-width identifiers")
class CardXrefMapperTest {

    /**
     * A card number from the published CardDemo demonstration seed, row 7 of
     * {@code app/data/ASCII/cardxref.txt}.
     *
     * <p>Assumptions: a seed value is used rather than a minted one so the constant is verifiable against
     * the repository, and it identifies no real person and no real account.</p>
     */
    private static final String SEED_CARD_NUMBER = "4859452612877065";

    /** The last four digits of {@link #SEED_CARD_NUMBER}, the one fragment a response may carry. */
    private static final String PERMITTED_SUFFIX = "7065";

    /** The leading twelve digits of {@link #SEED_CARD_NUMBER}, which no response may carry. */
    private static final String FORBIDDEN_PREFIX = "485945261287";

    /** A customer identifier narrower than its declared nine digits, so padding is observable. */
    private static final Long NARROW_CUSTOMER_ID = 42L;

    /** An account identifier narrower than its declared eleven digits, so padding is observable. */
    private static final Long NARROW_ACCOUNT_ID = 7L;

    /** The subject under test, which holds no state and needs no collaborator. */
    private final CardXrefMapper mapper = new CardXrefMapper();

    /**
     * Confirms the published card number is exactly the form the contract and the browser client require.
     */
    @Test
    @DisplayName("the card number is published as twelve mask characters and its last four digits")
    void theCardNumberIsPublishedInTheSharedMaskedForm() {
        CardXrefResponse published = this.mapper.toCardXrefResponse(
                new CardXref(SEED_CARD_NUMBER, NARROW_CUSTOMER_ID, NARROW_ACCOUNT_ID));

        assertThat(MaskedCardNumber.isMasked(published.cardNumberMasked()))
                .as("the value must satisfy the shared masked-card-number domain, which is the pattern"
                        + " openapi/account-api.yaml publishes and ui/src/api/accounts.ts enforces")
                .isTrue();
        assertThat(published.cardNumberMasked())
                .hasSize(MaskedCardNumber.MASKED_LENGTH)
                .endsWith(PERMITTED_SUFFIX)
                // WHY : Assumptions: the leading span is asserted absent as well as the whole value, because
                //   a partial disclosure is still a disclosure -- a rendering that published the issuer
                //   digits and masked the middle would satisfy a suffix assertion alone.
                .doesNotContain(FORBIDDEN_PREFIX)
                .doesNotContain(SEED_CARD_NUMBER);
    }

    /**
     * Confirms both identifiers are published at their declared widths, zero-padded.
     */
    @Test
    @DisplayName("both identifiers are published as digit text at their declared widths")
    void bothIdentifiersArePublishedAtTheirDeclaredWidths() {
        CardXrefResponse published = this.mapper.toCardXrefResponse(
                new CardXref(SEED_CARD_NUMBER, NARROW_CUSTOMER_ID, NARROW_ACCOUNT_ID));

        assertThat(published.customerId()).isEqualTo("000000042");
        assertThat(published.accountId()).isEqualTo("00000000007");
    }

    /**
     * Confirms every row of a list is translated and the order is preserved.
     *
     * <p>Assumptions: order is asserted because the page this list becomes is a keyset page whose contract
     * states ascending card-number order; a mapper that reordered would break paging rather than
     * presentation.</p>
     */
    @Test
    @DisplayName("a list is translated row for row, in order")
    void aListIsTranslatedRowForRowInOrder() {
        List<CardXrefResponse> published = this.mapper.toCardXrefResponses(List.of(
                new CardXref("4000123456789010", 1L, 2L),
                new CardXref("4000123456789028", 3L, 4L)));

        assertThat(published).hasSize(2);
        assertThat(published.get(0).cardNumberMasked()).endsWith("9010");
        assertThat(published.get(1).cardNumberMasked()).endsWith("9028");
    }

    /**
     * Confirms a stored key that is not the declared width is REFUSED rather than published in a form no
     * consumer accepts.
     *
     * <p>⚠️ Refactoring Rationale: the guard admitted anything of at least four characters until the mask
     * became width-preserving. Under the published pattern a fifteen-character row would render as eleven
     * mask characters and four digits -- a value the contract refuses and the browser client rejects with a
     * message naming nothing useful -- so the refusal moved to the row that holds the broken column, where
     * it can name the width it expected. Trade-offs: a shorter row now fails the read instead of being
     * published unusably, which is a visibly wrong answer traded for a silently wrong one.</p>
     *
     * <p>Assumptions: the padded case is the reachable one and is the one asserted. The column is
     * {@code CHAR(16)}, which blank-pads on read, so a fifteen-digit value arrives as fifteen digits and a
     * trailing space -- which strips to fifteen characters rather than to sixteen.</p>
     */
    @Test
    @DisplayName("a stored key that is not the declared width is refused, naming the width")
    void aStoredKeyOfTheWrongWidthIsRefused() {
        CardXref padded = new CardXref("485945261287706 ", NARROW_CUSTOMER_ID, NARROW_ACCOUNT_ID);

        assertThatThrownBy(() -> this.mapper.toCardXrefResponse(padded))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(String.valueOf(MaskedCardNumber.MASKED_LENGTH))
                .hasMessageContaining("XREF-CARD-NUM");
    }

    /**
     * Confirms an absent row is refused as a caller defect rather than translated into blanks.
     */
    @Test
    @DisplayName("an absent row is refused rather than translated")
    void anAbsentRowIsRefused() {
        assertThatThrownBy(() -> this.mapper.toCardXrefResponse(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("row");
    }
}
