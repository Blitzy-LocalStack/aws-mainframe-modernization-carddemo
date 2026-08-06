package com.carddemo.batch.dto;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Verifies that {@link DisclosureGroupKey} assembles the disclosure-group key in the order the
 * reference record declares, and that its DEFAULT fallback substitutes one component of three.
 *
 * <p>Every expectation below is pinned to an immutable reference artifact rather than to a value
 * chosen for the convenience of the test, and each citation sits beside the expectation it fixes so
 * that a reader can re-derive it without leaving this file. The artifacts are
 * {@code app/cpy/CVTRA02Y.cpy}, whose lines 5 to 8 declare the key and whose line 2 records the
 * record length; {@code app/cbl/CBACT04C.cbl}, whose line 50 names the key, lines 78 to 81 repeat
 * its layout, lines 210 to 212 assign it, line 437 substitutes the DEFAULT group and lines 443 to
 * 459 refuse a second miss; {@code app/cpy/CVTRA01Y.cpy}, whose lines 7 and 8 declare the two codes
 * that are copied into the key; and {@code app/jcl/DISCGRP.jcl}, whose line 40 defines the cluster
 * with {@code KEYS(16 0)}.</p>
 *
 * <p>Assumptions: two of the tests below defend properties that a compiler cannot, and they are the
 * reason this class exists rather than being conveniences around a three-component record. The
 * first is the component ORDER. The baseline assigns the key's last two components in the opposite
 * sequence to the one they occupy -- {@code app/cbl/CBACT04C.cbl} line 211 assigns the category and
 * line 212 the type, while {@code app/cpy/CVTRA02Y.cpy} line 7 places the type before line 8's
 * category -- and a record built in the assignment sequence compiles, satisfies every signature and
 * fails only as a lookup that matches no row. The second is the SCOPE of the DEFAULT substitution:
 * line 437 replaces the account-group component alone, so a derivation that replaced all three
 * would resolve the rate of a different transaction type and category, returning a plausible
 * number rather than an error.</p>
 *
 * <p>Alternatives Considered: asserting the key through a repository round trip against a real
 * database, which is how the lookup will ultimately be exercised. Rejected for these two properties
 * specifically, because a fixed-character column ignores trailing blanks and would therefore accept
 * a key this class is asserting the exact bytes of; the padding and the component order have to be
 * checked where they are literal, which is in the rendered key rather than in a query result. The
 * database-facing behaviour is a separate concern and belongs to the repository's own tests.</p>
 */
class DisclosureGroupKeyTest {

    /**
     * An ordinary account group identifier at its declared ten-character width, being the
     * seven-character {@code GROUP01} followed by three spaces.
     */
    private static final String ORDINARY_GROUP_ID = "GROUP01   ";

    /**
     * A transaction type code at the two-character width of {@code DIS-TRAN-TYPE-CD PIC X(02)} at
     * {@code app/cpy/CVTRA02Y.cpy} line 7.
     */
    private static final String TYPE_CODE = "01";

    /**
     * A single-digit transaction category code, chosen because its stored four-digit form
     * {@code 0005} differs from its numeric form and so exposes a missing zero-fill.
     */
    private static final int CATEGORY_CODE = 5;

    /** The four-digit stored form of {@link #CATEGORY_CODE}, per the numeric picture's zero-fill. */
    private static final String CATEGORY_FIELD = "0005";

    /**
     * Confirms the rendered key places the account group first, then the TYPE code, then the
     * CATEGORY code, which is the physical order and not the order the interest program assigns.
     *
     * <p>This is the regression guard against transcribing {@code app/cbl/CBACT04C.cbl} lines 210 to
     * 212 as a field list. Those three statements assign the group, then the category, then the
     * type; the record at {@code app/cpy/CVTRA02Y.cpy} lines 6 to 8 places the group, then the type,
     * then the category. A key built in the assignment sequence would render
     * {@code GROUP01   000501} instead of {@code GROUP01   010005} -- same width, same characters,
     * different key -- so the assertion below checks each component's POSITION and not merely the
     * total length.</p>
     *
     * <p>This zero-argument test returns no value; failed expectations surface as assertion
     * errors.</p>
     */
    @Test
    @DisplayName("rendered key is group then TYPE then CATEGORY, not the program's MOVE order")
    void renderedKeyFollowsPhysicalOrderAndNotTheMoveOrderAtLines210To212() {
        DisclosureGroupKey key = new DisclosureGroupKey(ORDINARY_GROUP_ID, TYPE_CODE, CATEGORY_CODE);

        String rendered = key.fixedWidthKey();

        // WHY : Assumptions: the three positional slices are asserted individually before the whole
        //       string, because a single equality on the concatenation would fail identically for a
        //       transposed pair of codes and for a mis-padded group identifier. Slicing names which
        //       component moved, and the offsets are the record's own: 0, 10 and 12 at
        //       app/cpy/CVTRA02Y.cpy lines 6 to 8.
        assertThat(rendered).hasSize(DisclosureGroupKey.KEY_LENGTH);
        assertThat(rendered.substring(0, 10)).isEqualTo(ORDINARY_GROUP_ID);
        assertThat(rendered.substring(10, 12)).isEqualTo(TYPE_CODE);
        assertThat(rendered.substring(12, 16)).isEqualTo(CATEGORY_FIELD);
        assertThat(rendered).isEqualTo("GROUP01   010005");
    }

    /**
     * Confirms the key's total width is sixteen, being ten plus two plus four.
     *
     * <p>The sixteen is the baseline's own: {@code app/jcl/DISCGRP.jcl} line 40 defines the cluster
     * with {@code KEYS(16 0)}, and {@code app/cbl/CBACT04C.cbl} line 50 names the group at lines 78
     * to 81 as the record key.</p>
     *
     * <p>This zero-argument test returns no value; failed expectations surface as assertion
     * errors.</p>
     */
    @Test
    @DisplayName("component widths sum to the sixteen-byte key of KEYS(16 0)")
    void componentWidthsSumToTheSixteenByteRecordKey() {
        assertThat(DisclosureGroupKey.ACCOUNT_GROUP_ID_LENGTH).isEqualTo(10);
        assertThat(DisclosureGroupKey.TRANSACTION_TYPE_CODE_LENGTH).isEqualTo(2);
        assertThat(DisclosureGroupKey.TRANSACTION_CATEGORY_CODE_LENGTH).isEqualTo(4);
        assertThat(DisclosureGroupKey.KEY_LENGTH).isEqualTo(16);
    }

    /**
     * Confirms the DEFAULT fallback replaces the account group component and only that component.
     *
     * <p>{@code app/cbl/CBACT04C.cbl} line 437 moves {@code 'DEFAULT'} into
     * {@code FD-DIS-ACCT-GROUP-ID} and into no other field, so the type and category values assigned
     * at lines 211 and 212 are still in place when the retry at line 444 runs. The derivation is
     * asserted component by component for that reason: a wholesale default key would also change
     * the group and would also be sixteen characters wide, and only the two carried components
     * distinguish the two designs.</p>
     *
     * <p>This zero-argument test returns no value; failed expectations surface as assertion
     * errors.</p>
     */
    @Test
    @DisplayName("DEFAULT fallback replaces the group id alone and carries type and category through")
    void defaultFallbackReplacesOnlyTheAccountGroupComponent() {
        DisclosureGroupKey primary =
                new DisclosureGroupKey(ORDINARY_GROUP_ID, TYPE_CODE, CATEGORY_CODE);

        DisclosureGroupKey fallback = primary.withDefaultAccountGroupId();

        assertThat(fallback.accountGroupId())
                .isEqualTo(DisclosureGroupKey.DEFAULT_ACCOUNT_GROUP_ID);
        assertThat(fallback.transactionTypeCode()).isEqualTo(TYPE_CODE);
        assertThat(fallback.transactionCategoryCode()).isEqualTo(CATEGORY_CODE);
        assertThat(fallback.fixedWidthKey()).isEqualTo("DEFAULT   010005");
    }

    /**
     * Confirms deriving the fallback leaves the original key untouched, so the key that missed
     * remains available for reporting.
     *
     * <p>Assumptions: a record is immutable, so this is a property of the language rather than of
     * the implementation -- and it is asserted anyway, because the derivation could have been
     * written to return a mutated shared instance and the two designs are indistinguishable at the
     * call site. The interest program needs both keys: the one that missed is what
     * {@code app/cbl/CBACT04C.cbl} line 418 reports as a missing group record.</p>
     *
     * <p>This zero-argument test returns no value; failed expectations surface as assertion
     * errors.</p>
     */
    @Test
    @DisplayName("deriving the fallback does not mutate the key it was derived from")
    void derivingTheFallbackLeavesTheOriginalKeyUnchanged() {
        DisclosureGroupKey primary =
                new DisclosureGroupKey(ORDINARY_GROUP_ID, TYPE_CODE, CATEGORY_CODE);

        primary.withDefaultAccountGroupId();

        assertThat(primary.accountGroupId()).isEqualTo(ORDINARY_GROUP_ID);
        assertThat(primary.transactionTypeCode()).isEqualTo(TYPE_CODE);
        assertThat(primary.transactionCategoryCode()).isEqualTo(CATEGORY_CODE);
        assertThat(primary.isDefaultAccountGroup()).isFalse();
    }

    /**
     * Confirms the DEFAULT group constant is ten characters, being the literal followed by exactly
     * three spaces.
     *
     * <p>{@code app/cbl/CBACT04C.cbl} line 437 moves the seven-character literal {@code 'DEFAULT'}
     * into a field declared {@code PIC X(10)} at {@code app/cpy/CVTRA02Y.cpy} line 6, and an
     * alphanumeric move left-justifies and space-fills, so the value the retry searches with carries
     * three trailing spaces. The assertion is written as the literal plus its padding rather than as
     * a single quoted string so that the three spaces are countable by eye.</p>
     *
     * <p>This zero-argument test returns no value; failed expectations surface as assertion
     * errors.</p>
     */
    @Test
    @DisplayName("DEFAULT group constant is the seven-character literal padded to ten")
    void defaultAccountGroupConstantIsSpacePaddedToItsDeclaredWidth() {
        assertThat(DisclosureGroupKey.DEFAULT_ACCOUNT_GROUP_ID)
                .hasSize(DisclosureGroupKey.ACCOUNT_GROUP_ID_LENGTH)
                .isEqualTo("DEFAULT" + "   ")
                .endsWith("   ");
        assertThat(DisclosureGroupKey.DEFAULT_ACCOUNT_GROUP_ID.strip()).isEqualTo("DEFAULT");
    }

    /**
     * Confirms the DEFAULT predicate answers true for a fallback key and false for an ordinary one.
     *
     * <p>The distinction is what lets a rate lookup report which of its two reads produced the rate.
     * It matters because {@code app/cbl/CBACT04C.cbl} line 422 accepts a file status of {@code '00'}
     * or {@code '23'} as equally non-fatal, so a resolved rate on its own says nothing about whether
     * the group was found.</p>
     *
     * <p>This zero-argument test returns no value; failed expectations surface as assertion
     * errors.</p>
     */
    @Test
    @DisplayName("DEFAULT predicate distinguishes a fallback key from an ordinary one")
    void defaultPredicateIsTrueOnlyForTheDefaultAccountGroup() {
        DisclosureGroupKey ordinary =
                new DisclosureGroupKey(ORDINARY_GROUP_ID, TYPE_CODE, CATEGORY_CODE);

        assertThat(ordinary.isDefaultAccountGroup()).isFalse();
        assertThat(ordinary.withDefaultAccountGroupId().isDefaultAccountGroup()).isTrue();
        assertThat(new DisclosureGroupKey(
                DisclosureGroupKey.DEFAULT_ACCOUNT_GROUP_ID, TYPE_CODE, CATEGORY_CODE)
                .isDefaultAccountGroup()).isTrue();
    }

    /**
     * Confirms the documented behaviour for an unpadded {@code DEFAULT}: the canonical constructor
     * refuses it, and the blank-padding entry point accepts it and reaches the fallback key.
     *
     * <p>Assumptions: this pins the consequence of the constructor's strictness, which is the one
     * open design decision this type had to settle. Because a seven-character group identifier
     * cannot be constructed at all, no key exists that names the DEFAULT group and yet answers
     * {@code false} to the predicate -- the wrong answer is unrepresentable rather than merely
     * documented. The lenient path is not removed, only named: a group identifier read back from a
     * {@code CHAR(10)} column arrives with its trailing blanks stripped, so
     * {@code ofBlankPaddedAccountGroupId} is the sanctioned way back to the canonical width and its
     * result is asserted equal to the derived fallback.</p>
     *
     * <p>This zero-argument test returns no value; failed expectations surface as assertion
     * errors.</p>
     */
    @Test
    @DisplayName("unpadded DEFAULT is refused by the constructor and padded by the named factory")
    void unpaddedDefaultGroupIsRefusedCanonicallyAndAcceptedThroughThePaddingFactory() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new DisclosureGroupKey("DEFAULT", TYPE_CODE, CATEGORY_CODE))
                .withMessageContaining("not exactly 10");

        DisclosureGroupKey padded = DisclosureGroupKey.ofBlankPaddedAccountGroupId(
                "DEFAULT", TYPE_CODE, CATEGORY_CODE);

        assertThat(padded.accountGroupId()).isEqualTo(DisclosureGroupKey.DEFAULT_ACCOUNT_GROUP_ID);
        assertThat(padded.isDefaultAccountGroup()).isTrue();
        assertThat(padded).isEqualTo(
                new DisclosureGroupKey(ORDINARY_GROUP_ID, TYPE_CODE, CATEGORY_CODE)
                        .withDefaultAccountGroupId());
    }

    /**
     * Confirms the blank-padding entry point pads on the right, passes a value already at width
     * through untouched, and refuses one that is over width.
     *
     * <p>Assumptions: an over-wide value is refused rather than truncated. Truncating would produce a
     * well-formed sixteen-character key addressing a different row, which is the failure mode this
     * type is shaped to avoid, whereas a refusal names the offending value and its length.</p>
     *
     * <p>This zero-argument test returns no value; failed expectations surface as assertion
     * errors.</p>
     */
    @Test
    @DisplayName("blank padding fills on the right, is idempotent at width, and refuses over-width")
    void blankPaddingFillsOnTheRightAndRefusesAnOverWideValue() {
        assertThat(DisclosureGroupKey
                .ofBlankPaddedAccountGroupId("GROUP01", TYPE_CODE, CATEGORY_CODE).accountGroupId())
                .isEqualTo(ORDINARY_GROUP_ID);
        assertThat(DisclosureGroupKey
                .ofBlankPaddedAccountGroupId(ORDINARY_GROUP_ID, TYPE_CODE, CATEGORY_CODE)
                .accountGroupId()).isEqualTo(ORDINARY_GROUP_ID);

        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> DisclosureGroupKey
                        .ofBlankPaddedAccountGroupId("ELEVENCHARS", TYPE_CODE, CATEGORY_CODE))
                .withMessageContaining("exceeds its declared width");
    }

    /**
     * Confirms the category code renders as four digits at every value the picture admits.
     *
     * <p>{@code DIS-TRAN-CAT-CD PIC 9(04)} at {@code app/cpy/CVTRA02Y.cpy} line 8 is numeric-display,
     * so a numeric picture right-justifies and zero-fills: the record holds {@code 0005} and never
     * {@code 5} followed by blanks. The three values below are the two bounds and one interior value
     * whose numeric and stored forms differ.</p>
     *
     * <p>This zero-argument test returns no value; failed expectations surface as assertion
     * errors.</p>
     */
    @Test
    @DisplayName("category renders zero-padded to four digits at 0, 5 and 9999")
    void categoryCodeRendersZeroPaddedToFourDigits() {
        assertThat(renderedCategory(DisclosureGroupKey.MIN_TRANSACTION_CATEGORY_CODE))
                .isEqualTo("0000")
                .hasSize(DisclosureGroupKey.TRANSACTION_CATEGORY_CODE_LENGTH);
        assertThat(renderedCategory(CATEGORY_CODE))
                .isEqualTo(CATEGORY_FIELD)
                .hasSize(DisclosureGroupKey.TRANSACTION_CATEGORY_CODE_LENGTH);
        assertThat(renderedCategory(DisclosureGroupKey.MAX_TRANSACTION_CATEGORY_CODE))
                .isEqualTo("9999")
                .hasSize(DisclosureGroupKey.TRANSACTION_CATEGORY_CODE_LENGTH);

        // WHY : Assumptions: a two-digit interior value is included because it is the shape the
        //       interest program itself writes -- app/cbl/CBACT04C.cbl moves a two-character literal
        //       into a PIC 9(04) field for the category of the interest transaction it generates --
        //       so the two-zero prefix is the case a real run exercises most.
        assertThat(renderedCategory(50)).isEqualTo("0050");
    }

    /**
     * Confirms every malformed component is refused, and refused with the one documented exception
     * type.
     *
     * <p>Assumptions: a single exception type covers an absent component, a mis-sized one and an
     * out-of-range one, matching the sibling record {@code BusinessDate} in this same package, so a
     * caller cannot come to depend on the type to tell the three apart. The seven cases below are
     * both bounds of each rule: a null and a wrong width for each string component, and one value on
     * each side of the category range.</p>
     *
     * <p>This zero-argument test returns no value; failed expectations surface as assertion
     * errors.</p>
     */
    @Test
    @DisplayName("null, mis-sized and out-of-range components are all refused")
    void malformedComponentsAreRefused() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new DisclosureGroupKey(null, TYPE_CODE, CATEGORY_CODE));
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new DisclosureGroupKey("SHORT", TYPE_CODE, CATEGORY_CODE));
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(
                        () -> new DisclosureGroupKey("ELEVENCHARS", TYPE_CODE, CATEGORY_CODE));
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new DisclosureGroupKey(ORDINARY_GROUP_ID, null, CATEGORY_CODE));
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new DisclosureGroupKey(ORDINARY_GROUP_ID, "0", CATEGORY_CODE));
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new DisclosureGroupKey(ORDINARY_GROUP_ID, "012", CATEGORY_CODE));
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new DisclosureGroupKey(ORDINARY_GROUP_ID, TYPE_CODE, -1))
                .withMessageContaining("PIC 9(04)");
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new DisclosureGroupKey(ORDINARY_GROUP_ID, TYPE_CODE, 10000))
                .withMessageContaining("PIC 9(04)");
    }

    /**
     * Confirms value equality compares all three components and hashes consistently with that.
     *
     * <p>This zero-argument test returns no value; failed expectations surface as assertion
     * errors.</p>
     */
    @Test
    @DisplayName("equal components are equal and hash alike; a differing category is unequal")
    void valueEqualityComparesEveryComponent() {
        DisclosureGroupKey one =
                new DisclosureGroupKey(ORDINARY_GROUP_ID, TYPE_CODE, CATEGORY_CODE);
        DisclosureGroupKey same =
                new DisclosureGroupKey(ORDINARY_GROUP_ID, TYPE_CODE, CATEGORY_CODE);

        assertThat(same).isEqualTo(one).hasSameHashCodeAs(one);
        assertThat(new DisclosureGroupKey(ORDINARY_GROUP_ID, TYPE_CODE, CATEGORY_CODE + 1))
                .isNotEqualTo(one);
        assertThat(new DisclosureGroupKey(ORDINARY_GROUP_ID, "02", CATEGORY_CODE))
                .isNotEqualTo(one);
        assertThat(new DisclosureGroupKey("GROUP02   ", TYPE_CODE, CATEGORY_CODE))
                .isNotEqualTo(one);
    }

    /**
     * Confirms blanks inside the account group component are significant characters and are never
     * trimmed away by equality or by the rendered key.
     *
     * <p>Assumptions: the literal pairing this would ideally assert -- a padded group identifier
     * against the same value unpadded -- is UNREPRESENTABLE here, and that is the stronger property
     * rather than a gap in the test. Two values of the required ten characters cannot differ only in
     * trailing blanks, and the short form is refused outright by the constructor, so the padded and
     * bare forms can never both exist to be compared. What can still differ is where the blanks sit,
     * and that is asserted below: a fixed-character column would treat neither difference as
     * significant, whereas this key treats both as significant because the rendered sixteen
     * characters are compared byte for byte against a record.</p>
     *
     * <p>This zero-argument test returns no value; failed expectations surface as assertion
     * errors.</p>
     */
    @Test
    @DisplayName("blanks in the group id are significant in equality and in the rendered key")
    void blanksInTheAccountGroupComponentAreSignificant() {
        DisclosureGroupKey trailingBlanks =
                new DisclosureGroupKey("A         ", TYPE_CODE, CATEGORY_CODE);
        DisclosureGroupKey leadingBlanks =
                new DisclosureGroupKey("         A", TYPE_CODE, CATEGORY_CODE);

        assertThat(trailingBlanks).isNotEqualTo(leadingBlanks);
        assertThat(trailingBlanks.fixedWidthKey()).isNotEqualTo(leadingBlanks.fixedWidthKey());

        // WHY : Assumptions: the accessor and the rendering are both checked for the padding,
        //       because trimming in only one of the two is the realistic mistake: an accessor that
        //       stripped blanks would still render a correct key, and a rendering that stripped them
        //       would shorten the DEFAULT fallback key from sixteen characters to thirteen and shift
        //       the two codes three positions left.
        assertThat(trailingBlanks.accountGroupId()).isEqualTo("A         ");
        assertThat(DisclosureGroupKey.DEFAULT_ACCOUNT_GROUP_ID).isNotEqualTo("DEFAULT");
        assertThat(trailingBlanks.withDefaultAccountGroupId().fixedWidthKey())
                .hasSize(DisclosureGroupKey.KEY_LENGTH)
                .startsWith("DEFAULT   ");
    }

    /**
     * Confirms the key declares exactly three components and that none of them is a rate or an
     * amount.
     *
     * <p>Assumptions: the same key is read twice against the same record in one lookup -- the primary
     * read at {@code app/cbl/CBACT04C.cbl} line 416 and the retry at line 444 -- so a component
     * holding a resolved rate would carry the first read's value into the second, where it is stale
     * by construction. The resolved rate belongs to a separate result type. This is asserted
     * structurally rather than by reading the source, so that adding a fourth component or an
     * approximate numeric type fails the build.</p>
     *
     * <p>This zero-argument test returns no value; failed expectations surface as assertion
     * errors.</p>
     */
    @Test
    @DisplayName("key declares exactly three components and carries no rate, amount or binary float")
    void keyDeclaresExactlyThreeComponentsAndNoRate() {
        RecordComponent[] components = DisclosureGroupKey.class.getRecordComponents();
        List<String> names = Arrays.stream(components).map(RecordComponent::getName).toList();
        List<String> typeNames =
                Arrays.stream(components).map(component -> component.getType().getName()).toList();

        assertThat(components).hasSize(3);
        assertThat(names)
                .containsExactly("accountGroupId", "transactionTypeCode", "transactionCategoryCode");
        assertThat(typeNames).containsExactly("java.lang.String", "java.lang.String", "int");

        // WHY : Assumptions: the names are screened as well as the types, because a rate could be
        //       smuggled in as an exact decimal that no type check would object to. The migration
        //       forbids an approximate numeric anywhere in the money path outright, so the binary
        //       floating-point types and the exact decimal type are screened as well rather than
        //       relying on the exact component list above to catch a substitution.
        // WHY : Assumptions: the types are screened by NAME rather than by class literal, because
        //       the component types form a list of wildcard-parameterised classes and a class-literal
        //       comparison against it does not type-check. Names are also what a reader can match
        //       against the migration's own prohibition without resolving a type.
        assertThat(names).noneMatch(name -> name.toLowerCase().contains("rate")
                || name.toLowerCase().contains("amount")
                || name.toLowerCase().contains("money")
                || name.toLowerCase().contains("balance"));
        assertThat(typeNames).doesNotContain(
                "float", "double", "java.lang.Float", "java.lang.Double", "java.math.BigDecimal");
    }

    /**
     * Renders the four-digit stored form of a category code through a key built around it.
     *
     * <p>Assumptions: the helper builds a whole key rather than calling the rendering in isolation,
     * because the rendering is a member of the key and the category code cannot be varied without
     * one. The two other components are held at the values this class uses everywhere else so that a
     * failure names the category and nothing else.</p>
     *
     * @param categoryCode the category code to render, which must lie within the range the record's
     *     four-digit picture admits
     * @return the four-digit stored form of that code; never {@code null}
     */
    private static String renderedCategory(int categoryCode) {
        return new DisclosureGroupKey(ORDINARY_GROUP_ID, TYPE_CODE, categoryCode)
                .transactionCategoryCodeField();
    }

}
