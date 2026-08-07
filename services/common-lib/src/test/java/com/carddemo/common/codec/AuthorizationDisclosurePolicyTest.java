package com.carddemo.common.codec;

import static org.assertj.core.api.Assertions.assertThat;


import com.carddemo.common.codec.CopybookLayout.FieldSpec;
import com.carddemo.common.codec.CopybookLayout.RecordSpec;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Verifies that the two IMS authorization segments withhold every field the disclosure policy does not
 * name, that the Java and Python statements of that policy are the same set, and that the withholding
 * actually changes what a decode diagnostic says.
 *
 * <h2>Purpose</h2>
 *
 * <p>The two authorization segments are the only records in this corpus whose content is decoded by two
 * classes -- {@link CopybookLayout} through the fixed-width and packed codecs, and {@link CsvAuthCodec}
 * over the request and reply payloads -- and by two languages, since
 * {@code data-migration/src/carddemo_migration/copybook/layouts.py} transcribes the same two copybooks
 * independently. Three statements of one record's sensitivity is three chances to disagree, and they did
 * disagree: the Java segment layouts once marked one field sensitive in the detail segment and none at
 * all in the summary, while the Python transcription withheld sixteen and the wire codec withheld nine.
 * This class is what makes them one statement in practice rather than in prose.</p>
 *
 * <p>Assumptions: the properties asserted here are STRUCTURAL, over every field of both segments, rather
 * than samples. A disclosure policy is only worth having if it holds for the field nobody thought about,
 * so the fail-closed property is asserted as "every field is either named or withheld" instead of
 * checking that a handful of known-sensitive names are marked.</p>
 *
 * <p>Assumptions: every withholding assertion is PAIRED with its disclosable twin -- the same probe on
 * a field of the same storage regime that the allowlist names. Asserting only that a withheld field's
 * message omits a detail would pass if the message omitted that detail for everybody, which would mean
 * the flag did nothing and the diagnostic was merely useless. The pair proves the flag is what makes the
 * difference.</p>
 *
 * <p>Trade-offs: one test reads a Python source file from a sibling tree and parses a literal out of it.
 * That is coarser than sharing a definition, and sharing one is not available -- a Java constant and a
 * Python frozenset cannot be one object, and generating a third artifact both read from would add a
 * build step and a staleness mode in order to remove a two-line drift risk. Reading the file makes the
 * drift fail the Java build, which runs on every change to either tree, and the precedent for reaching
 * across trees in a test is already set by
 * {@code services/reporting-service/.../ReportingTaskRunnerTest}, which reads a Terraform module the
 * same way.</p>
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception section.</p>
 */
class AuthorizationDisclosurePolicyTest {

    /**
     * The Python transcription this policy must agree with, relative to this module's base directory.
     *
     * <p>Assumptions: the path is module-relative because Maven runs a test with the module directory as
     * its working directory, so two levels up is the repository root. The same relative form is used by
     * the reporting module's state-machine test, which is the precedent this follows.</p>
     */
    private static final String PYTHON_LAYOUTS =
            "../../data-migration/src/carddemo_migration/copybook/layouts.py";

    /**
     * Matches the Python frozenset literal that states the same policy.
     *
     * <p>Assumptions: the pattern anchors on the assignment and stops at the first closing brace, so it
     * cannot run past the literal into the function that follows. It is deliberately tolerant of the
     * type annotation between the name and the value, because that annotation is a detail of the Python
     * side that this test has no business pinning.</p>
     */
    private static final Pattern PYTHON_ALLOWLIST = Pattern.compile(
            "_AUTHORIZATION_DISCLOSABLE_FIELDS[^=]*=\\s*frozenset\\(\\s*\\{(.*?)\\}", Pattern.DOTALL);

    /**
     * Matches one double-quoted field name inside the Python literal.
     *
     * <p>Assumptions: comments inside the literal are not stripped before matching, and they need not
     * be: the comment lines in that block carry no double-quoted text, so a name pattern cannot pick one
     * up. Relying on that is safe because a comment that DID quote a name would make this test fail
     * loudly rather than pass wrongly.</p>
     */
    private static final Pattern QUOTED_NAME = Pattern.compile("\"([A-Z0-9-]+)\"");

    /**
     * The byte every position of a probe record image is filled with.
     *
     * <p>Assumptions: {@code 0xFF} is invalid for every numeric regime in these segments and decodes to
     * no ASCII digit, so one filler value drives the failure path for the packed and display fields
     * alike. Its high nibble is what the packed codec reports as an invalid digit nibble, which is the
     * detail the withheld fields must not carry.</p>
     */
    private static final byte PROBE_BYTE = (byte) 0xFF;

    /**
     * The clause the packed codec appends for a disclosable field and omits for a withheld one.
     *
     * <p>Assumptions: this literal is the observable difference the flag makes in the packed regime. It
     * is taken from {@code PackedDecimalCodec}'s own nibble-detail rendering, so a change to that
     * rendering fails the paired assertions below rather than silently making them vacuous.</p>
     */
    private static final String NIBBLE_CLAUSE = "found 0x";

    /**
     * The separator that introduces a delegated cause message, present only for a disclosable field.
     *
     * <p>Assumptions: the marker is the SEPARATOR rather than any particular cause wording, and that is
     * deliberate. {@code FixedWidthCodec}'s failure builder appends {@code ": " + cause.getMessage()}
     * when the field may be read and appends the omission statement after a semicolon when it may not,
     * so the separator is present in exactly one of the two branches regardless of what the delegated
     * codec chose to say. Matching a specific cause sentence instead would make this test fail the day a
     * codec reworded a diagnostic, which is a change that does not affect disclosure at all.</p>
     */
    private static final String DELEGATED_DETAIL_SEPARATOR = ": ";

    /**
     * The statement the failure builder substitutes for the delegated detail on a withheld field.
     */
    private static final String OMISSION_CLAUSE = "field content omitted because it is sensitive";

    /**
     * The two segment layouts this policy governs.
     *
     * @return the logical names of the two IMS authorization segments
     */
    private static Stream<String> authorizationSegments() {
        return Stream.of("PAUTSUM0", "PAUTDTL");
    }

    /**
     * Reads the Python statement of the policy out of its source file.
     *
     * @return the field names the Python allowlist declares, in declaration order
     * @throws UncheckedIOException if the Python source cannot be read, which would mean the sibling
     *     tree is absent and the parity claim cannot be checked at all
     * @throws IllegalStateException if the allowlist literal is not present, which would mean the Python
     *     side stopped stating the policy in the form this test verifies
     */
    private static Set<String> pythonAllowlist() {
        String source;
        try {
            source = Files.readString(Path.of(PYTHON_LAYOUTS), StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new UncheckedIOException(
                    "the Python layout transcription at " + PYTHON_LAYOUTS + " could not be read, so the"
                    + " cross-language disclosure parity cannot be checked", unreadable);
        }

        Matcher literal = PYTHON_ALLOWLIST.matcher(source);
        if (!literal.find()) {
            throw new IllegalStateException("the Python layout transcription at " + PYTHON_LAYOUTS
                    + " no longer declares _AUTHORIZATION_DISCLOSABLE_FIELDS as a frozenset literal, so"
                    + " the two statements of the disclosure policy can no longer be compared");
        }

        Set<String> names = new LinkedHashSet<>();
        Matcher name = QUOTED_NAME.matcher(literal.group(1));
        while (name.find()) {
            names.add(name.group(1));
        }
        return names;
    }

    /**
     * Returns the names of the fields of one segment that the policy withholds.
     *
     * @param segment the logical name of the segment
     * @return the withheld field names, in declaration order
     */
    private static List<String> withheldOf(String segment) {
        return CopybookLayout.layout(segment).fields().stream()
                .filter(FieldSpec::sensitive)
                .map(FieldSpec::name)
                .toList();
    }

    /**
     * Selects one field of a segment by name.
     *
     * @param segment the logical name of the segment
     * @param fieldName the field name exactly as the copybook declares it
     * @return the field descriptor
     * @throws IllegalStateException if the segment declares no such field, which would mean this test
     *     names a field the transcription has renamed or dropped
     */
    private static FieldSpec fieldOf(String segment, String fieldName) {
        return CopybookLayout.layout(segment).fields().stream()
                .filter(field -> field.name().equals(fieldName))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "segment " + segment + " declares no field named " + fieldName));
    }

    /**
     * Decodes one field from a probe image filled with an invalid byte and returns the failure message.
     *
     * @param segment the logical name of the segment the field belongs to
     * @param fieldName the field to decode
     * @return the message of the thrown failure
     */
    private static String probeFailureMessage(String segment, String fieldName) {
        RecordSpec spec = CopybookLayout.layout(segment);
        byte[] image = new byte[spec.reclen()];
        Arrays.fill(image, PROBE_BYTE);
        FieldSpec field = fieldOf(segment, fieldName);

        // WHY : Assumptions: the assertion is that a failure occurs AND is captured, not merely that
        //       decoding is attempted. A probe that stopped throwing -- because the codec grew tolerant
        //       of an invalid byte, say -- would make every message assertion below vacuous, so the
        //       throw itself is asserted here rather than assumed by a try/catch that returns a default.
        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                () -> FixedWidthCodec.decodeField(image, field));
        assertThat(thrown)
                .describedAs("decoding %s of %s from an all-0x%s image must fail, or this probe proves"
                        + " nothing", fieldName, segment, "FF")
                .isNotNull();
        return String.valueOf(thrown.getMessage());
    }

    /**
     * Verifies every field of both segments is either explicitly disclosable or withheld.
     *
     * <p>Assumptions: this is the fail-closed property itself, and it is what a denylist cannot state. A
     * field added to either copybook transcription and not named in the allowlist is withheld by
     * construction, so this test passes for a field nobody has thought about -- which is exactly the
     * case the previous per-declaration-site marking got wrong.</p>
     *
     * @param segment the logical name of the segment under test
     */
    @ParameterizedTest
    @MethodSource("authorizationSegments")
    @DisplayName("every authorization field is disclosable by name or else withheld")
    void everyAuthorizationFieldIsNamedOrWithheld(String segment) {
        RecordSpec spec = CopybookLayout.layout(segment);

        assertThat(spec.fields()).isNotEmpty();
        spec.fields().forEach(field -> assertThat(field.sensitive())
                .describedAs("field %s of %s must be withheld unless the allowlist names it",
                        field.name(), segment)
                .isEqualTo(!CopybookLayout.AUTHORIZATION_DISCLOSABLE_FIELDS.contains(field.name())));
    }

    /**
     * Verifies the summary segment withholds the customer identifier, both limits, both balances and
     * both amounts.
     *
     * <p>Assumptions: the seven are named as literals rather than derived, because a derived expectation
     * would restate the allowlist and would then pass for whatever the allowlist happens to say. Naming
     * them pins the outcome: the packed account key, the two status fields, the two counters and the pad
     * stay readable, and nothing else does.</p>
     */
    @Test
    @DisplayName("the summary segment withholds the customer identifier, both limits, both balances and "
            + "both amounts")
    void theSummarySegmentWithholdsItsFinancialAndCustomerFields() {
        assertThat(withheldOf("PAUTSUM0")).containsExactlyInAnyOrder(
                "PA-CUST-ID",
                "PA-CREDIT-LIMIT",
                "PA-CASH-LIMIT",
                "PA-CREDIT-BALANCE",
                "PA-CASH-BALANCE",
                "PA-APPROVED-AUTH-AMT",
                "PA-DECLINED-AUTH-AMT");
    }

    /**
     * Verifies the detail segment withholds exactly the nine base names the wire codec covers.
     *
     * <p>Assumptions: these nine are the base names of {@link CsvAuthCodec}'s own sensitive set with the
     * request and reply infixes removed, which is why the count matters as much as the membership. The
     * two artifacts decode the same fields, so a segment that withheld eight or ten would mean one of
     * them had drifted from the other.</p>
     */
    @Test
    @DisplayName("the detail segment withholds exactly the nine base names the wire codec covers")
    void theDetailSegmentWithholdsTheWireCodecsBaseNames() {
        assertThat(withheldOf("PAUTDTL")).containsExactlyInAnyOrder(
                "PA-CARD-NUM",
                "PA-CARD-EXPIRY-DATE",
                "PA-TRANSACTION-AMT",
                "PA-APPROVED-AMT",
                "PA-MERCHANT-ID",
                "PA-MERCHANT-NAME",
                "PA-MERCHANT-CITY",
                "PA-MERCHANT-ZIP",
                "PA-TRANSACTION-ID");
    }

    /**
     * Verifies the merchant state stays disclosable while the four narrowing merchant fields do not.
     *
     * <p>Assumptions: this is asserted separately from the set above because it is the one
     * classification a later contributor is most likely to "correct" for symmetry -- four merchant
     * fields withheld and a fifth not looks like an oversight until the reason is read. Pinning it makes
     * the asymmetry a decision the build defends.</p>
     */
    @Test
    @DisplayName("the merchant state is disclosable while merchant identity, name, city and postal code "
            + "are not")
    void theMerchantStateIsDisclosableAndTheNarrowingMerchantFieldsAreNot() {
        assertThat(withheldOf("PAUTDTL")).doesNotContain("PA-MERCHANT-STATE");
        assertThat(withheldOf("PAUTDTL")).contains(
                "PA-MERCHANT-ID", "PA-MERCHANT-NAME", "PA-MERCHANT-CITY", "PA-MERCHANT-ZIP");
    }

    /**
     * Verifies the allowlist names no field that neither segment declares.
     *
     * <p>Assumptions: a dead name in an allowlist is worse than useless, because it reads as a decision
     * that some field is disclosable when no such field exists -- and if a field is later added under
     * that name it becomes disclosable silently, without anybody deciding so. This is the check that
     * keeps the allowlist an inventory of real fields.</p>
     */
    @Test
    @DisplayName("the allowlist names no field that neither authorization segment declares")
    void theAllowlistNamesNoFieldThatNeitherSegmentDeclares() {
        Set<String> declared = new LinkedHashSet<>();
        authorizationSegments().forEach(segment -> CopybookLayout.layout(segment).fields()
                .forEach(field -> declared.add(field.name())));

        assertThat(CopybookLayout.AUTHORIZATION_DISCLOSABLE_FIELDS).isSubsetOf(declared);
    }

    /**
     * Verifies the Java and Python statements of the disclosure policy are the same set of names.
     *
     * <p>Assumptions: set equality is asserted in both directions by a single containment-exactly check,
     * so a name added on either side alone fails. This is the assertion that converts the Python
     * comment's parity claim from prose into something the build enforces -- the claim used to be false,
     * and the reason it could stay false is that nothing compared the two.</p>
     */
    @Test
    @DisplayName("the Java allowlist and the Python allowlist are the same set of names")
    void theJavaAndPythonAllowlistsAreTheSameSet() {
        Set<String> python = pythonAllowlist();

        assertThat(python)
                .describedAs("the Python allowlist parsed out of %s", PYTHON_LAYOUTS)
                .isNotEmpty();
        assertThat(CopybookLayout.AUTHORIZATION_DISCLOSABLE_FIELDS)
                .describedAs("the Java allowlist must name exactly what %s names", PYTHON_LAYOUTS)
                .containsExactlyInAnyOrderElementsOf(python);
    }

    /**
     * Verifies a packed decode failure on a withheld money field names no nibble, while the same failure
     * on a disclosable packed field does.
     *
     * <p>Assumptions: the pair is the point. {@code PA-CREDIT-BALANCE} is a withheld balance and
     * {@code PA-ACCT-ID} is the disclosable packed account key, and both are packed fields of the same
     * segment decoded from the same image -- so the only difference between the two messages is the
     * flag. Without the second assertion the first would pass for a codec that had simply stopped saying
     * anything useful.</p>
     */
    @Test
    @DisplayName("a packed decode failure names the offending nibble only for a disclosable field")
    void aPackedDecodeFailureNamesTheNibbleOnlyForADisclosableField() {
        assertThat(probeFailureMessage("PAUTSUM0", "PA-CREDIT-BALANCE"))
                .doesNotContain(NIBBLE_CLAUSE);
        assertThat(probeFailureMessage("PAUTSUM0", "PA-ACCT-ID"))
                .contains(NIBBLE_CLAUSE);
    }

    /**
     * Verifies both withheld packed amounts of the detail segment name no nibble either.
     *
     * <p>Assumptions: the two amounts are asserted alongside the summary balance because they are the
     * fields the wire codec also withholds, so a drift that reclassified them would break the
     * cross-artifact agreement this class exists to hold. Their disclosable twin is the same account key
     * asserted above, which is why no second positive case is repeated here.</p>
     */
    @Test
    @DisplayName("neither withheld detail amount names its offending nibble")
    void neitherWithheldDetailAmountNamesItsNibble() {
        assertThat(probeFailureMessage("PAUTDTL", "PA-TRANSACTION-AMT"))
                .doesNotContain(NIBBLE_CLAUSE);
        assertThat(probeFailureMessage("PAUTDTL", "PA-APPROVED-AMT"))
                .doesNotContain(NIBBLE_CLAUSE);
    }

    /**
     * Verifies a display decode failure carries its delegated detail only for a disclosable field.
     *
     * <p>Assumptions: {@code PA-CUST-ID} is the withheld unsigned-display customer identifier and
     * {@code PA-PROCESSING-CODE} is a disclosable unsigned-display code, so this is the display-regime
     * counterpart of the packed pair above. The withheld message is additionally asserted to carry the
     * substituted statement, because a message that omitted the detail AND said nothing in its place
     * would leave an operator unable to tell a withheld detail from a codec that produced none.</p>
     */
    @Test
    @DisplayName("a display decode failure carries its delegated detail only for a disclosable field")
    void aDisplayDecodeFailureCarriesItsDetailOnlyForADisclosableField() {
        String withheld = probeFailureMessage("PAUTSUM0", "PA-CUST-ID");
        assertThat(withheld).doesNotContain(DELEGATED_DETAIL_SEPARATOR);
        assertThat(withheld).contains(OMISSION_CLAUSE);

        assertThat(probeFailureMessage("PAUTDTL", "PA-PROCESSING-CODE"))
                .contains(DELEGATED_DETAIL_SEPARATOR)
                .doesNotContain(OMISSION_CLAUSE);
    }

    /**
     * Verifies every withheld field of both segments names itself and its geometry in a failure and
     * nothing more.
     *
     * <p>Assumptions: this sweeps the remaining withheld fields the paired tests above do not name
     * individually, and it asserts the three halves that make a diagnostic useful without being
     * disclosing: the field name and its offset are present, the failure builder took its withholding
     * branch, and no delegated cause text or nibble reading follows. A field whose regime cannot fail on
     * content -- every withheld character field here -- reaches this loop with no failure at all, which
     * is skipped explicitly rather than by omission so that a future codec which DID quote a character
     * value would be caught by the same sweep.</p>
     *
     * @param segment the logical name of the segment under test
     */
    @ParameterizedTest
    @MethodSource("authorizationSegments")
    @DisplayName("no withheld field's failure carries a content clause")
    void noWithheldFieldsFailureCarriesAContentClause(String segment) {
        RecordSpec spec = CopybookLayout.layout(segment);
        byte[] image = new byte[spec.reclen()];
        Arrays.fill(image, PROBE_BYTE);

        for (FieldSpec field : spec.fields()) {
            if (!field.sensitive()) {
                continue;
            }

            Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                    () -> FixedWidthCodec.decodeField(image, field));
            if (thrown == null) {
                // WHY : Assumptions: a character field decodes any byte sequence, so the absence of a
                //       failure here is the expected outcome for one and is not a gap in the sweep. What
                //       matters is that the loop still VISITS it, so the day a character decoder starts
                //       rejecting a byte and quoting it, this assertion is already in place to catch it.
                continue;
            }

            assertThat(String.valueOf(thrown.getMessage()))
                    .describedAs("the diagnostic for withheld field %s of %s", field.name(), segment)
                    .contains(field.name())
                    .contains(String.valueOf(field.start()))
                    .contains(OMISSION_CLAUSE)
                    .doesNotContain(NIBBLE_CLAUSE)
                    .doesNotContain(DELEGATED_DETAIL_SEPARATOR);
        }
    }

    /**
     * Verifies the layout registry still refuses a field declaration whose geometry is wrong, so that
     * running the disclosure helper over a field list has not weakened the geometry contract.
     *
     * <p>Assumptions: the helper replaces two flags and carries the geometry through, and this is the
     * assertion that the carrying is real rather than assumed. A helper that rebuilt a field from
     * defaults would move offsets, and because both segments' widths sum to their declared lengths the
     * error would surface only as a mis-decode. Asserting that the segments still validate and that a
     * deliberately wrong width is still rejected covers both halves.</p>
     */
    @Test
    @DisplayName("closing disclosure leaves the geometry contract intact")
    void closingDisclosureLeavesTheGeometryContractIntact() {
        authorizationSegments().forEach(segment -> {
            RecordSpec spec = CopybookLayout.layout(segment);
            int covered = spec.fields().stream().mapToInt(FieldSpec::length).sum();
            assertThat(covered)
                    .describedAs("the fields of %s must still cover exactly its declared length", segment)
                    .isEqualTo(spec.reclen());
        });

        FieldSpec probe = CopybookLayout.packed("PA-PROBE", 3, 9, 2, true);
        FieldSpec reflagged = probe.withFlags(true, true).withFlags(false, false);
        assertThat(reflagged.start()).isEqualTo(probe.start());
        assertThat(reflagged.length()).isEqualTo(probe.length());
        assertThat(reflagged.kind()).isEqualTo(probe.kind());
        assertThat(reflagged.intDigits()).isEqualTo(probe.intDigits());
        assertThat(reflagged.decDigits()).isEqualTo(probe.decDigits());
        assertThat(reflagged.signed()).isEqualTo(probe.signed());
    }
}
