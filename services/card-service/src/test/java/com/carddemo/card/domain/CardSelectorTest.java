package com.carddemo.card.domain;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.Column;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Fixes the properties the card row selector must hold for a published route to name a card without
 * carrying that card's number.
 *
 * <p>Assumptions: the selector is the one member of this entity with no copybook counterpart, so
 * nothing in the baseline constrains it and every property it needs has to be asserted here rather
 * than inherited from a record layout. Four are load-bearing and each has its own case below: it is
 * present on a constructed card, it is unique across cards, it is stable for a card's lifetime, and it
 * is unrelated to the card number.</p>
 *
 * <p>Alternatives Considered: asserting these against a live database with Testcontainers, which
 * would additionally exercise the {@code gen_random_uuid()} default and the unique constraint.
 * Declined for this class because the properties under test are properties of the JAVA generation
 * path, and a container would make a regression in that path indistinguishable from a schema or
 * driver problem. The migration text is read and asserted directly instead, which is what keeps the
 * column and the member from drifting apart without needing a server.</p>
 */
class CardSelectorTest {

    /** Where the owning service's initial migration is published on the test classpath. */
    private static final String MIGRATION = "db/migration/V1__card.sql";

    /**
     * A number in the reserved test range, used only to show the selector is unrelated to it.
     *
     * <p>Assumptions: the major industry identifier 9 is reserved for national assignment, so this
     * cannot collide with a live card. It is a literal here because the point of the case that uses it
     * is that two cards differing ONLY in their number still receive unrelated selectors.</p>
     */
    private static final String SAMPLE_CARD_NUMBER = "9111111111110011";

    /**
     * Builds a card with the six values the baseline record carries.
     *
     * @param cardNum the sixteen-character card number to give the card
     * @return a card whose selector was assigned by the constructor under test
     */
    private static Card cardNumbered(String cardNum) {
        return new Card(cardNum, 11L, null, "PAUL BUCK", LocalDate.of(2023, 1, 20), "Y");
    }

    /**
     * Reads the owning service's migration from the test classpath.
     *
     * @return the migration text
     * @throws IOException if the resource cannot be read
     * @throws IllegalStateException if the migration is absent from the classpath, which would mean
     *     the service publishes no schema for the column this class asserts on
     */
    private static String migration() throws IOException {
        try (InputStream in =
                CardSelectorTest.class.getClassLoader().getResourceAsStream(MIGRATION)) {
            if (in == null) {
                throw new IllegalStateException("migration absent from the classpath: " + MIGRATION);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Asserts a constructed card carries a selector without one being supplied.
     *
     * <p>Assumptions: the selector is deliberately NOT a constructor parameter, so this also fixes
     * that no caller chooses it. A caller that could choose one could pick the address a card answers
     * on, and two callers choosing the same value would collide on a database constraint rather than
     * being refused as bad input.</p>
     */
    @Test
    @DisplayName("a constructed card carries a selector that no caller supplied")
    void aConstructedCardCarriesAGeneratedSelector() {
        Card card = cardNumbered(SAMPLE_CARD_NUMBER);

        assertThat(card.getCardSelector())
                .as("a card with no selector could not be addressed by any published route")
                .isNotNull();
        assertThat(card.getCardSelector().version())
                .as("version 4 is the random variant; a time- or name-based value would carry"
                        + " information about when or from what it was made")
                .isEqualTo(4);
    }

    /**
     * Asserts selectors do not repeat across cards.
     *
     * <p>Assumptions: a thousand cards rather than two. Two would pass under a generator that
     * alternated between a pair of values, and the failure this guards against is a generator that is
     * effectively constant -- for instance one seeded identically in every task -- which shows up as
     * repetition at volume and not in a single comparison.</p>
     */
    @Test
    @DisplayName("selectors do not repeat across cards")
    void selectorsDoNotRepeat() {
        Set<UUID> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            seen.add(cardNumbered(SAMPLE_CARD_NUMBER).getCardSelector());
        }

        assertThat(seen)
                .as("a repeated selector would make one route resolve to two cards, which has no"
                        + " defined answer, and would break uq_cards_selector on insert")
                .hasSize(1000);
    }

    /**
     * Asserts a selector carries nothing derived from the card number.
     *
     * <p>Assumptions: this is the property that makes the selector safe to place in a request target
     * at all, and it is asserted rather than described. Two cards constructed with the SAME number
     * receive different selectors, which is only possible if the number is not an input to the
     * generation; and the canonical rendering shares no four-character run with the number, which is
     * the shape a naive derivation -- embedding the last four digits, say -- would leave behind.</p>
     */
    @Test
    @DisplayName("a selector is unrelated to the card number it addresses")
    void aSelectorIsUnrelatedToTheCardNumber() {
        Card first = cardNumbered(SAMPLE_CARD_NUMBER);
        Card second = cardNumbered(SAMPLE_CARD_NUMBER);

        assertThat(first.getCardSelector())
                .as("two cards carrying the same number receive different selectors, so the number"
                        + " is not an input to the generation")
                .isNotEqualTo(second.getCardSelector());

        String rendered = first.getCardSelector().toString();
        assertThat(rendered).doesNotContain(SAMPLE_CARD_NUMBER);
        for (int i = 0; i + 4 <= SAMPLE_CARD_NUMBER.length(); i++) {
            String run = SAMPLE_CARD_NUMBER.substring(i, i + 4);
            assertThat(rendered)
                    .as("no four-digit run of the card number may appear in its selector, which is"
                            + " the residue a partial derivation would leave")
                    .doesNotContain(run);
        }
    }

    /**
     * Asserts the selector renders in exactly the canonical form the published route accepts.
     *
     * <p>Assumptions: the contract's path parameter admits only the lower-case hyphenated form, so a
     * value this entity emits in any other spelling would be a selector no route could carry. The
     * pattern here restates the contract's rather than referencing it, because the two are owned by
     * different files and a shared constant would let both change together unnoticed.</p>
     */
    @Test
    @DisplayName("a selector renders in the canonical form the route accepts")
    void aSelectorRendersCanonically() {
        Pattern canonical = Pattern.compile(
                "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

        for (int i = 0; i < 100; i++) {
            String rendered = cardNumbered(SAMPLE_CARD_NUMBER).getCardSelector().toString();
            assertThat(canonical.matcher(rendered).matches())
                    .as("a selector rendering the route refuses is one the row cannot be navigated"
                            + " from: %s",
                            rendered)
                    .isTrue();
        }
    }

    /**
     * Asserts the mapping refuses to rewrite a selector once a row holds one.
     *
     * <p>Assumptions: a selector is a row's stable address, and the one thing an address must not do
     * is change while something still holds it -- a re-issued selector would silently 404 a list row a
     * caller was still looking at. There is no operation that rotates one, so this asserts the
     * mapping forbids the write rather than trusting that none is attempted.</p>
     *
     * @throws NoSuchFieldException if the member is renamed without this assertion being updated,
     *     which is itself the signal that the guarantee needs re-establishing
     */
    @Test
    @DisplayName("the selector column is declared not updatable")
    void theSelectorIsNotUpdatable() throws NoSuchFieldException {
        Field field = Card.class.getDeclaredField("cardSelector");
        Column column = field.getAnnotation(Column.class);

        assertThat(column).isNotNull();
        assertThat(column.name()).isEqualTo("card_selector");
        assertThat(column.nullable())
                .as("a nullable selector would let a row exist that no route can address")
                .isFalse();
        assertThat(column.updatable())
                .as("a rewritable selector would break every held reference to the row")
                .isFalse();
    }

    /**
     * Asserts the migration declares the column the entity maps, with the guarantees it relies on.
     *
     * <p>Refactoring Rationale: this reads the migration text rather than a live schema because the
     * entity and the DDL are the two places the column is declared and nothing else compares them.
     * The entity generates a value and the DDL defaults one, which is deliberate -- rows also arrive
     * through the bulk load, which never instantiates the entity -- and that redundancy is exactly why
     * a drift between the two would be silent: a column the entity mapped and the schema lacked would
     * fail only at the first flush, and one the schema had but the entity did not would simply never
     * be read.</p>
     *
     * @throws IOException if the migration cannot be read from the classpath
     */
    @Test
    @DisplayName("the migration declares the selector column, its default and its uniqueness")
    void theMigrationDeclaresTheColumn() throws IOException {
        String sql = migration();

        Matcher declaration = Pattern.compile(
                        "card_selector\\s+UUID\\s+NOT NULL\\s+DEFAULT\\s+gen_random_uuid\\(\\)")
                .matcher(sql);
        assertThat(declaration.find())
                .as("the column must be UUID, NOT NULL and defaulted: the bulk load supplies no"
                        + " value for it because the seed extract has no field for one, so without"
                        + " the default every loaded row would be refused")
                .isTrue();

        assertThat(sql)
                .as("uniqueness is what makes the selector usable as a row address, and the"
                        + " constraint is named because the service quotes names back")
                .contains("CONSTRAINT uq_cards_selector UNIQUE (card_selector)");
    }
}
