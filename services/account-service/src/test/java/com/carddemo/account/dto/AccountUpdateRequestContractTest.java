package com.carddemo.account.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the submitted account-update contract member by member, against the baseline screen it came from.
 *
 * <p>Purpose: {@code AccountUpdateRequest} is the ONE request body this bounded context accepts, and it is
 * consumed by a caller this repository does not contain. Its component names are therefore wire names: a
 * rename, a reordering or a change of declared type is a breaking change to an external contract, and none
 * of those three is visible to any test that asserts only how many components exist. This class asserts the
 * whole contract -- the exact ordered list of all forty-three component names, the declared type of every
 * one of them, and the character width each carries -- so that any of the three failures is a failing
 * assertion here rather than a caller's failure in production.</p>
 *
 * <p>Refactoring Rationale: the only assertion over this record's shape formerly lived inside a controller
 * case in {@code com.carddemo.account.api.AccountControllerTest}, and it claimed
 * {@code hasSize(43)} plus three sampled names. That assertion is satisfied by any forty-three components
 * whatsoever: renaming {@code lastName} to {@code surname}, swapping the order of
 * {@code phone1Prefix} and {@code phone1LineNumber}, or retyping {@code creditLimit} from {@code String}
 * to {@code java.math.BigDecimal} all leave it green while breaking every caller. The count also sat in a
 * case about caller-supplied identity, where a reader looking for the contract would not find it. The
 * contract moves here, where the package charter already scopes the subject as "the declared shape of a
 * transfer type -- its components, their arity", and it is stated exhaustively rather than sampled.</p>
 *
 * <p>Alternatives Considered: asserting the same contract by serialising a populated record and comparing
 * the resulting JSON against a committed document. Rejected for two independent reasons. It cannot prove a
 * component is ABSENT, because an absent component and a null component render identically once null
 * suppression is in play -- which is exactly the failure the identity assertion has to catch. And it makes
 * the production record both the subject under test and the source of the expected text, so a rename
 * changes both sides together and the comparison stays green through the very edit it exists to catch. The
 * expected names here are written out as literals, and the widths are read from the immutable baseline, so
 * neither side of any comparison is produced by the code under test.</p>
 *
 * <p>Trade-offs: the forty-three-row table below is long, and a component added to the record must be added
 * to it by hand or this class fails. That cost is the mechanism, not a side effect: an exhaustive table that
 * must be edited deliberately is what converts a silent wire-contract change into a build failure. A
 * generated table would have to be generated FROM the record, which is the alternative already rejected.</p>
 *
 * <p>Assumptions: nothing here starts a Spring context, a database or a container, per this package's
 * charter. One file is read from the working tree -- the baseline symbolic map -- which needs no runtime
 * beyond the file system and follows the precedent set by
 * {@code com.carddemo.batch.dto.DisclosureGroupSeedParityTest}, which holds seeded reference rates against
 * the immutable extract the running system loads.</p>
 */
@DisplayName("AccountUpdateRequest publishes exactly the baseline screen's forty-three submitted fields")
class AccountUpdateRequestContractTest {

    /**
     * The repository-relative path of the BMS symbolic map the submitted contract is derived from.
     *
     * <p>Assumptions: the symbolic map is the authority for the width of each submitted field, rather than
     * the mapset source at {@code app/bms/COACTUP.bms} or the receiving program at
     * {@code app/cbl/COACTUPC.cbl}. The map is what the program actually receives the screen through, so its
     * {@code PIC} clauses are the widths a caller's value has to fit; the mapset declares the same widths a
     * second time as {@code LENGTH=} attributes and the program declares none of them.</p>
     */
    private static final String SYMBOLIC_MAP = "app/cpy-bms/COACTUP.CPY";

    /**
     * The line at which the symbolic map's INPUT group opens, one-based and inclusive.
     *
     * <p>Assumptions: the input group is bounded explicitly rather than the whole file being scanned,
     * because the file redefines the same storage as an OUTPUT group immediately afterwards. Every field
     * name appears a second time in that redefinition with an {@code O} suffix in place of the {@code I},
     * and several appear with a different width, so scanning the whole file would silently match output
     * declarations and would report widths a caller never sends.</p>
     */
    private static final int INPUT_GROUP_FIRST_LINE = 17;

    /**
     * The line at which the symbolic map's INPUT group closes, one-based and inclusive.
     *
     * <p>Assumptions: the bound is the line before {@code 01 CACTUPAO REDEFINES CACTUPAI.}, which the
     * accompanying assertion verifies rather than trusts -- a bound that silently drifted would narrow the
     * scan and make a missing field look like a contract error.</p>
     */
    private static final int INPUT_GROUP_LAST_LINE = 342;

    /**
     * The declaration that opens the output redefinition, used to prove the input bound above is right.
     */
    private static final String OUTPUT_REDEFINITION = "01  CACTUPAO REDEFINES CACTUPAI.";

    /**
     * Matches one fixed-width input field declaration inside the symbolic map's input group.
     *
     * <p>Assumptions: only {@code 02}-level {@code PIC X(n)} declarations whose name ends in {@code I} are
     * matched. That excludes the three companions the map generator emits for every field -- the
     * {@code COMP PIC S9(4)} length cell, the one-character flag cell and its attribute redefinition -- and
     * it excludes the {@code 02 FILLER} entries, none of which is a field a caller supplies.</p>
     */
    private static final Pattern INPUT_FIELD =
            Pattern.compile("\\s+02\\s+([A-Z0-9]+I)\\s+PIC X\\((\\d+)\\)\\.\\s*");

    /**
     * The number of fixed-width input fields the symbolic map declares, submitted and non-submitted alike.
     */
    private static final int DECLARED_INPUT_FIELDS = 54;

    /**
     * The map's input fields that carry screen chrome the server writes and no caller submits.
     *
     * <p>Assumptions: these six are named explicitly rather than filtered by a heuristic, because the only
     * thing that distinguishes them from a submitted field is what the reference DOES with them: the
     * transaction name, the two title lines, the program name, and the current date and time are all moved
     * outward by the program and never read inward. A heuristic over names would be a guess; an enumeration
     * is checkable, and the sum below proves the enumeration is complete.</p>
     */
    private static final List<String> CHROME_FIELDS =
            List.of("TRNNAMEI", "TITLE01I", "CURDATEI", "PGMNAMEI", "TITLE02I", "CURTIMEI");

    /**
     * The map's input fields that carry server-composed message lines rather than submitted values.
     *
     * <p>Assumptions: both are outbound only. The informational line is {@code PIC X(45)} and the error line
     * is {@code PIC X(78)}; the target carries the second as the width bound on its own rendered sentence,
     * which is asserted where that rendering happens rather than here.</p>
     */
    private static final List<String> MESSAGE_FIELDS = List.of("INFOMSGI", "ERRMSGI");

    /**
     * The map's input fields that carry function-key legends rather than submitted values.
     *
     * <p>Assumptions: the legend text is presentation the target re-derives on the client, so none of the
     * three is part of any request body. They are enumerated for the same reason as the chrome fields: to
     * make the arity derivation add up exactly.</p>
     */
    private static final List<String> LEGEND_FIELDS = List.of("FKEYSI", "FKEY05I", "FKEY12I");

    /**
     * One submitted field, tying a wire member name to the baseline screen field and width behind it.
     *
     * <p>Assumptions: the three facts are held together in one row because they must change together. A
     * component whose screen provenance is unknown cannot have its width justified, and a width with no
     * component name cannot be held against the record. Splitting them into three collections would let two
     * of the three drift apart between edits.</p>
     *
     * @param component the record component name, which is also the JSON member name on the wire
     * @param screenField the {@code 02}-level name of the field in {@code app/cpy-bms/COACTUP.CPY}
     * @param width the character width that field is declared with, as {@code PIC X(width)}
     */
    private record SubmittedField(String component, String screenField, int width) { }

    /**
     * The complete submitted contract, in the record's declaration order.
     *
     * <p>Assumptions: the order is the RECORD's declaration order and deliberately not the screen's layout
     * order, and the two genuinely differ -- the screen interleaves the account dates between the credit
     * limit and the cash limit, while the record groups the five monetary members together and the three
     * date triples after them. Declaration order is what matters to a caller because it is the order of the
     * canonical constructor, so a reordering breaks every positional construction; the screen's order binds
     * nothing, since every member arrives by name in a JSON object.</p>
     *
     * <p>Assumptions: the widths carried here are the baseline screen widths and NOT storage precisions, and
     * three families make the distinction load-bearing. Each of the five monetary members is fifteen
     * characters because that is the edit-mask width the screen renders, while the column behind it is
     * {@code PIC S9(10)V99}; each date arrives as three separate members of four, two and two characters
     * against a single {@code PIC X(10)} stored column; and the national identifier arrives as three members
     * of three, two and four characters. A width table taken from the storage layout would disagree with the
     * wire on all eleven of those members.</p>
     */
    private static final List<SubmittedField> SUBMITTED_CONTRACT = List.of(
            new SubmittedField("accountId", "ACCTSIDI", 11),
            new SubmittedField("activeStatus", "ACSTTUSI", 1),
            new SubmittedField("creditLimit", "ACRDLIMI", 15),
            new SubmittedField("cashCreditLimit", "ACSHLIMI", 15),
            new SubmittedField("currentBalance", "ACURBALI", 15),
            new SubmittedField("currentCycleCredit", "ACRCYCRI", 15),
            new SubmittedField("currentCycleDebit", "ACRCYDBI", 15),
            new SubmittedField("openDateYear", "OPNYEARI", 4),
            new SubmittedField("openDateMonth", "OPNMONI", 2),
            new SubmittedField("openDateDay", "OPNDAYI", 2),
            new SubmittedField("expirationDateYear", "EXPYEARI", 4),
            new SubmittedField("expirationDateMonth", "EXPMONI", 2),
            new SubmittedField("expirationDateDay", "EXPDAYI", 2),
            new SubmittedField("reissueDateYear", "RISYEARI", 4),
            new SubmittedField("reissueDateMonth", "RISMONI", 2),
            new SubmittedField("reissueDateDay", "RISDAYI", 2),
            new SubmittedField("groupId", "AADDGRPI", 10),
            new SubmittedField("customerId", "ACSTNUMI", 9),
            new SubmittedField("ssnPart1", "ACTSSN1I", 3),
            new SubmittedField("ssnPart2", "ACTSSN2I", 2),
            new SubmittedField("ssnPart3", "ACTSSN3I", 4),
            new SubmittedField("dateOfBirthYear", "DOBYEARI", 4),
            new SubmittedField("dateOfBirthMonth", "DOBMONI", 2),
            new SubmittedField("dateOfBirthDay", "DOBDAYI", 2),
            new SubmittedField("ficoCreditScore", "ACSTFCOI", 3),
            new SubmittedField("firstName", "ACSFNAMI", 25),
            new SubmittedField("middleName", "ACSMNAMI", 25),
            new SubmittedField("lastName", "ACSLNAMI", 25),
            new SubmittedField("addressLine1", "ACSADL1I", 50),
            new SubmittedField("addressLine2", "ACSADL2I", 50),
            new SubmittedField("city", "ACSCITYI", 50),
            new SubmittedField("stateCode", "ACSSTTEI", 2),
            new SubmittedField("countryCode", "ACSCTRYI", 3),
            new SubmittedField("zipCode", "ACSZIPCI", 5),
            new SubmittedField("phone1AreaCode", "ACSPH1AI", 3),
            new SubmittedField("phone1Prefix", "ACSPH1BI", 3),
            new SubmittedField("phone1LineNumber", "ACSPH1CI", 4),
            new SubmittedField("phone2AreaCode", "ACSPH2AI", 3),
            new SubmittedField("phone2Prefix", "ACSPH2BI", 3),
            new SubmittedField("phone2LineNumber", "ACSPH2CI", 4),
            new SubmittedField("governmentIssuedId", "ACSGOVTI", 20),
            new SubmittedField("eftAccountId", "ACSEFTCI", 10),
            new SubmittedField("primaryCardHolderIndicator", "ACSPFLGI", 1));

    /**
     * Name fragments no submitted member may carry, because the token carries what they would.
     *
     * <p>Assumptions: identity reaches this service in signed claims, so a member able to carry a user
     * identifier, a user type, a role, an authority or a scope would be a member a caller could use to
     * assert its own privilege. Fragments are tested rather than exact names because the failure to catch is
     * a member ADDED later under a spelling nobody predicted -- {@code callerRole} and {@code roleName} both
     * contain {@code role}, and an exact-name list would admit both.</p>
     */
    private static final List<String> FORBIDDEN_NAME_FRAGMENTS =
            List.of("userid", "usertype", "role", "authority", "scope", "token", "password", "credential");

    /**
     * The record declares exactly the forty-three submitted members, in exactly this order.
     *
     * <p>Purpose: this is the assertion a caller depends on. The names are the JSON member names, and the
     * order is the canonical constructor's parameter order, so both a rename and a reordering are breaking
     * changes. Both are asserted at once by comparing the whole declared list against the whole expected
     * list, which no count and no sample can do.</p>
     */
    @Test
    @DisplayName("the record declares the forty-three submitted members in exactly the expected order")
    void theRecordDeclaresEveryMemberInTheExpectedOrder() {
        assertThat(SUBMITTED_CONTRACT)
                .as("the expected contract itself must carry forty-three rows and no duplicate member")
                .hasSize(43)
                .extracting(SubmittedField::component)
                .doesNotHaveDuplicates();

        assertThat(componentNamesOf(AccountUpdateRequest.class))
                .as("a rename or a reordering of any member is a breaking change to an external caller")
                .containsExactlyElementsOf(
                        SUBMITTED_CONTRACT.stream().map(SubmittedField::component).toList());
    }

    /**
     * Every submitted member is declared as character transport and nothing narrower.
     *
     * <p>Purpose: the reference receives every one of these fields as characters and validates them itself,
     * including the five monetary members and the nine date parts. Declaring any of them as a number would
     * move the parse into the deserialiser, which answers a malformed value with a body-level binding
     * failure naming a Java type -- where the contract requires a per-field refusal carrying the reference's
     * own sentence. A monetary member declared as a floating-point type would additionally violate
     * transformation rule T3. The accessor's return type is asserted beside the component's type because
     * they are separately declared in bytecode and a hand-written accessor could disagree.</p>
     */
    @Test
    @DisplayName("every submitted member is declared as a character sequence, component and accessor alike")
    void everySubmittedMemberIsCharacterTransport() {
        for (RecordComponent component : AccountUpdateRequest.class.getRecordComponents()) {
            assertThat(component.getType())
                    .as("member %s must arrive as characters so the target validates it, not the parser",
                            component.getName())
                    .isEqualTo(String.class);
            assertThat(component.getAccessor().getReturnType())
                    .as("the accessor for %s must agree with the component it reads", component.getName())
                    .isEqualTo(String.class);
        }
    }

    /**
     * Every submitted member's width is the width the baseline screen declares for the field behind it.
     *
     * <p>Purpose: the widths are the second half of the contract. A caller's value has to fit the field the
     * reference receives it in, so a width recorded wrongly is a value accepted here and truncated there.
     * This case reads the widths from the immutable symbolic map rather than from any target artifact, so
     * the expected side of the comparison is not produced by the code under test.</p>
     *
     * <p>Assumptions: the arity is proved as well as the widths. The map declares fifty-four fixed-width
     * input fields; six carry screen chrome, two carry server-composed messages and three carry function-key
     * legends, leaving forty-three that a caller supplies. Asserting that sum makes the record's arity a
     * derived fact rather than a remembered one -- if a later reading of the map found a fifty-fifth field,
     * this case would fail and name it instead of silently accepting a contract short by one.</p>
     */
    @Test
    @DisplayName("every submitted member carries the baseline screen field's declared character width")
    void everySubmittedWidthIsTheBaselineScreenWidth() {
        Map<String, Integer> declared = declaredInputFieldWidths();

        assertThat(declared)
                .as("the symbolic map declares fifty-four fixed-width input fields")
                .hasSize(DECLARED_INPUT_FIELDS);

        List<String> notSubmitted = new ArrayList<>(CHROME_FIELDS);
        notSubmitted.addAll(MESSAGE_FIELDS);
        notSubmitted.addAll(LEGEND_FIELDS);
        assertThat(declared.keySet())
                .as("every field the map declares is either submitted by a caller or one of the eleven not")
                .containsExactlyInAnyOrderElementsOf(namesOfSubmittedFields(notSubmitted));

        for (SubmittedField expected : SUBMITTED_CONTRACT) {
            assertThat(declared)
                    .as("member %s maps to screen field %s, which the map must declare",
                            expected.component(), expected.screenField())
                    .containsKey(expected.screenField());
            assertThat(declared.get(expected.screenField()))
                    .as("member %s carries screen field %s, declared PIC X(%d)",
                            expected.component(), expected.screenField(), expected.width())
                    .isEqualTo(expected.width());
        }
    }

    /**
     * No submitted member can carry identity, privilege or a secret.
     *
     * <p>Purpose: the reference carried the user identifier and the one-character user type in the
     * communication area the terminal echoed back, which is storage a client supplies. The target takes both
     * from signed claims, so a member here able to carry either would hand a caller back the ability to
     * assert its own privilege -- and it would do so silently, because such a member would deserialise
     * successfully and simply be ignored until some later edit read it. The declaration is swept so the
     * member cannot appear at all.</p>
     */
    @Test
    @DisplayName("no submitted member carries a user identifier, a user type, a role or a secret")
    void noSubmittedMemberCarriesIdentityOrPrivilege() {
        for (String component : componentNamesOf(AccountUpdateRequest.class)) {
            String folded = component.toLowerCase(Locale.ROOT);
            assertThat(FORBIDDEN_NAME_FRAGMENTS)
                    .as("member %s must not be able to carry identity, privilege or a secret", component)
                    .noneMatch(folded::contains);
        }
    }

    /**
     * Lists the declared component names of a record type, in declaration order.
     *
     * <p>Assumptions: the names are read from the record's own declaration rather than from a serialised
     * document, because a case that must prove a member is ABSENT cannot do so from a document -- an absent
     * member and a member serialised as null are indistinguishable on the wire once null suppression is in
     * play.</p>
     *
     * @param recordType the record type to describe; must be a record and must not be {@code null}
     * @return the component names in declaration order, never {@code null}
     */
    private static List<String> componentNamesOf(Class<?> recordType) {
        return Arrays.stream(recordType.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
    }

    /**
     * Collects the screen field names this contract submits, together with a set of names it does not.
     *
     * <p>Assumptions: the two are combined into one list so the caller can assert set EQUALITY against the
     * map's declarations. Asserting containment in one direction only would accept a map field that this
     * contract neither submits nor accounts for, which is the case that has to fail.</p>
     *
     * @param notSubmitted the screen field names accounted for as chrome, message or legend fields; must
     *     not be {@code null}
     * @return every screen field name this class accounts for, submitted first, never {@code null}
     */
    private static List<String> namesOfSubmittedFields(List<String> notSubmitted) {
        List<String> names = new ArrayList<>(
                SUBMITTED_CONTRACT.stream().map(SubmittedField::screenField).toList());
        names.addAll(notSubmitted);
        return names;
    }

    /**
     * Reads the fixed-width input field declarations out of the baseline symbolic map.
     *
     * <p>Assumptions: the scan is bounded to the input group's line range and the bound is verified against
     * the output redefinition's own declaration, so a drifted bound fails loudly here instead of quietly
     * narrowing what is checked.</p>
     *
     * @return each input field's {@code 02}-level name mapped to its declared character width, in the order
     *     the map declares them, never {@code null}
     * @throws UncheckedIOException if the symbolic map cannot be read
     * @throws AssertionError if the input group's closing bound is not where this class expects it
     */
    private static Map<String, Integer> declaredInputFieldWidths() {
        List<String> lines;
        Path map = repositoryRoot().resolve(SYMBOLIC_MAP);
        try {
            lines = Files.readAllLines(map, StandardCharsets.US_ASCII);
        } catch (IOException cause) {
            throw new UncheckedIOException("the baseline symbolic map " + map
                    + " could not be read, so the submitted widths cannot be verified", cause);
        }

        assertThat(lines.get(INPUT_GROUP_LAST_LINE).strip())
                .as("the line after the input group must open the output redefinition")
                .isEqualTo(OUTPUT_REDEFINITION);

        Map<String, Integer> widths = new LinkedHashMap<>();
        for (String line : lines.subList(INPUT_GROUP_FIRST_LINE - 1, INPUT_GROUP_LAST_LINE)) {
            Matcher declaration = INPUT_FIELD.matcher(line);
            if (declaration.matches()) {
                widths.put(declaration.group(1), Integer.valueOf(declaration.group(2)));
            }
        }
        return widths;
    }

    /**
     * Finds the repository root by walking up from the working directory.
     *
     * <p>Assumptions: the root is located by searching for the file this class reads rather than taken from
     * a build property or a relative literal. Maven runs a module's tests with the working directory set to
     * that module, so a relative literal would encode the depth from module to root and would break the
     * moment the class moved; searching for a file that must exist for the test to mean anything makes the
     * failure self-describing. This follows the resolver in
     * {@code com.carddemo.batch.dto.DisclosureGroupSeedParityTest}.</p>
     *
     * @return the nearest ancestor of the working directory that holds the baseline symbolic map, never
     *     {@code null}
     * @throws AssertionError if no ancestor holds it
     */
    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve(SYMBOLIC_MAP))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new AssertionError("no ancestor of " + Path.of("").toAbsolutePath() + " contains "
                + SYMBOLIC_MAP + ", so the submitted contract cannot be held against the baseline");
    }
}
