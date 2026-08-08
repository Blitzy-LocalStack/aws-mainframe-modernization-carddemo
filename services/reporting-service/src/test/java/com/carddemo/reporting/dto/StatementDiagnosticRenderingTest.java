package com.carddemo.reporting.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.common.money.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Pins what a statement rendering may and may not carry into a log line.
 *
 * <p>Refactoring Rationale: these two records were flagged because neither overrode
 * {@code toString}, so the record-generated form rendered every component. Between them that is a
 * primary account number twice, an account identifier, a customer name, a statement total, a
 * transaction amount and four merchant fields including a free-text name. The row record is the
 * sharper of the two: it is the element type of the shared page envelope, so ONE interpolation of a
 * page into a diagnostic renders every row the page holds.</p>
 *
 * <p>Assumptions: the assertions name the values that must be ABSENT rather than checking the shape of
 * what is present. That direction is deliberate, and it is the direction the sibling card entity's own
 * rendering test settled on: a rendering can only regress by GAINING a member, and an assertion on
 * presence cannot detect a gain. A small number of positive assertions accompany them solely so that a
 * rendering reduced to the empty string could not pass by carrying nothing at all.</p>
 *
 * <p>Alternatives Considered: asserting on a captured log record through a logging test appender rather
 * than on {@code toString} directly. Rejected because the disclosure decision lives in the override and
 * not in any one call site; an appender test would prove one caller behaves and would say nothing about
 * the framework-internal and exception-message paths that invoke {@code toString} implicitly -- and
 * those are the paths that make this a hazard rather than a style question.</p>
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception at-clause.</p>
 */
@DisplayName("statement diagnostic renderings")
class StatementDiagnosticRenderingTest {

    /**
     * A card number from the published CardDemo demonstration seed.
     *
     * <p>Assumptions: a seed value is used rather than a minted one so the constant is verifiable
     * against a committed file, and it is a demonstration value identifying no real person.</p>
     */
    private static final String CARD_NUMBER = "4111111111111111";

    /**
     * The last four digits of that number, asserted absent separately from the whole.
     *
     * <p>Assumptions: asserted on its own because a masked rendering would satisfy an assertion against
     * the full number while still disclosing these four digits -- and beside a customer name on the same
     * line, four digits identify the card outright. Masking is right in a response body and wrong in a
     * log.</p>
     */
    private static final String CARD_LAST_FOUR = "1111";

    /**
     * The eleven-digit account identifier, which is the join key that attributes everything else.
     */
    private static final String ACCOUNT_ID = "00000000011";

    /**
     * A customer name distinctive enough that a substring assertion cannot match by accident.
     */
    private static final String CUSTOMER_NAME = "QUINTILIAN HOLLOWFIELD";

    /**
     * A merchant name distinctive enough that a substring assertion cannot match by accident.
     */
    private static final String MERCHANT_NAME = "ZANZIBAR CURIOSITIES LIMITED";

    /**
     * The summary record's rendering.
     */
    @Nested
    @DisplayName("the statement summary")
    class Summary {

        /**
         * Builds a summary carrying every value under test.
         *
         * @return the record under test, never {@code null}
         */
        private StatementResponse subject() {
            return new StatementResponse(CARD_NUMBER, ACCOUNT_ID, CUSTOMER_NAME,
                    Money.of("1234.56"), 7, "s3://bucket/plain.txt", "s3://bucket/page.html",
                    "2026-08-08 06:00:00.000000");
        }

        /**
         * No part of the primary account number appears, whole or masked.
         */
        @Test
        @DisplayName("discloses neither the whole card number nor its last four digits")
        void cardNumberIsAbsentEntirely() {
            assertThat(subject().toString())
                    .doesNotContain(CARD_NUMBER)
                    .doesNotContain(CARD_LAST_FOUR);
        }

        /**
         * The account identifier and the customer name are both absent.
         */
        @Test
        @DisplayName("discloses neither the account identifier nor the customer name")
        void attributingValuesAreAbsent() {
            assertThat(subject().toString())
                    .doesNotContain(ACCOUNT_ID)
                    .doesNotContain(CUSTOMER_NAME);
        }

        /**
         * The statement total is absent.
         *
         * <p>Assumptions: a monetary total is withheld even though it names no person by itself,
         * because the other three components of this record would have named one on the same line.</p>
         */
        @Test
        @DisplayName("discloses no monetary total")
        void totalIsAbsent() {
            assertThat(subject().toString()).doesNotContain("1234.56");
        }

        /**
         * The operationally useful values are present, so the rendering is not merely empty.
         */
        @Test
        @DisplayName("still carries the artefact references and the cardinality")
        void operationalValuesRemain() {
            assertThat(subject().toString())
                    .contains("StatementResponse")
                    .contains("s3://bucket/plain.txt")
                    .contains("s3://bucket/page.html")
                    .contains("transactionCount=7");
        }
    }

    /**
     * The transaction-line record's rendering, which is the higher-volume disclosure.
     */
    @Nested
    @DisplayName("the statement transaction line")
    class TransactionLine {

        /**
         * Builds a transaction line carrying every value under test.
         *
         * @return the record under test, never {@code null}
         */
        private StatementTransactionResponse subject() {
            return new StatementTransactionResponse(CARD_NUMBER, "0000000000000009", "01", "05",
                    "System", "Purchase at " + MERCHANT_NAME, Money.of("99.99"), "999999999",
                    MERCHANT_NAME, "ZANZIBAR CITY", "99999", "2026-08-08 05:00:00.000000",
                    "2026-08-08 06:00:00.000000");
        }

        /**
         * No part of the primary account number appears, whole or masked.
         */
        @Test
        @DisplayName("discloses neither the whole card number nor its last four digits")
        void cardNumberIsAbsentEntirely() {
            assertThat(subject().toString())
                    .doesNotContain(CARD_NUMBER)
                    .doesNotContain(CARD_LAST_FOUR);
        }

        /**
         * The amount is absent.
         */
        @Test
        @DisplayName("discloses no monetary amount")
        void amountIsAbsent() {
            assertThat(subject().toString()).doesNotContain("99.99");
        }

        /**
         * The merchant name and city are absent while the merchant identifier remains.
         *
         * <p>Assumptions: both halves are asserted together because the division is the decision. A
         * merchant name plus a city plus an amount on one line describes where a person was and what
         * they spent; the identifier is an opaque code an operator needs in order to attribute a
         * posting problem, and it describes nothing on its own.</p>
         */
        @Test
        @DisplayName("withholds merchant free text while keeping the merchant identifier")
        void merchantFreeTextIsAbsentButIdentifierRemains() {
            assertThat(subject().toString())
                    .doesNotContain(MERCHANT_NAME)
                    .doesNotContain("ZANZIBAR CITY")
                    .contains("merchantId=999999999");
        }

        /**
         * The description is absent even though it resembles a bounded code field.
         *
         * <p>Assumptions: asserted explicitly because a reader could reasonably classify the
         * description as safe. It is not: the reference carries free text composed at run time, and it
         * is the field most likely to have had a merchant name written into it upstream -- which this
         * fixture reproduces by embedding the merchant name in it.</p>
         */
        @Test
        @DisplayName("withholds the description because it carries run-time free text")
        void descriptionIsAbsent() {
            assertThat(subject().toString()).doesNotContain("Purchase at");
        }

        /**
         * The investigative values are present, so the rendering is not merely empty.
         */
        @Test
        @DisplayName("still carries the identifier, the codes, the source and both timestamps")
        void investigativeValuesRemain() {
            assertThat(subject().toString())
                    .contains("StatementTransactionResponse")
                    .contains("transactionId=0000000000000009")
                    .contains("typeCode=01")
                    .contains("categoryCode=05")
                    .contains("source=System")
                    .contains("originTimestamp=2026-08-08 05:00:00.000000")
                    .contains("processingTimestamp=2026-08-08 06:00:00.000000");
        }

        /**
         * A whole page of rows discloses nothing, which is the volume case that made this the sharper record.
         *
         * <p>Assumptions: asserted on a list rather than on one row because a list's own rendering
         * delegates to each element's, and a page is what a handler actually holds. One interpolation of
         * a page is what would have rendered every row at once.</p>
         */
        @Test
        @DisplayName("discloses nothing when a whole page of rows is rendered at once")
        void aPageOfRowsDisclosesNothing() {
            String rendered = java.util.List.of(subject(), subject(), subject()).toString();

            assertThat(rendered)
                    .doesNotContain(CARD_NUMBER)
                    .doesNotContain(CARD_LAST_FOUR)
                    .doesNotContain(MERCHANT_NAME)
                    .doesNotContain("99.99");
        }
    }
}
