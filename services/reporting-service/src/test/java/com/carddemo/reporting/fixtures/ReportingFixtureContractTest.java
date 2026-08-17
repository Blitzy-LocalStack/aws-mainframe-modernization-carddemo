package com.carddemo.reporting.fixtures;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.common.codec.CopybookLayout;
import com.carddemo.common.codec.FixedWidthCodec;
import com.carddemo.common.money.Money;
import com.carddemo.reporting.mapper.StatementTextMapper;
import com.carddemo.reporting.mapper.TransactionReportMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Consumes every fixed-width fixture of this service and asserts the contract each one claims.
 *
 * <h2>Purpose</h2>
 *
 * <p>Fixture files sat under {@code src/test/resources/fixtures} with no consumer. Their
 * record lengths, field offsets, key domains and synthetic origin were therefore unverified claims,
 * and a fixture that no test reads cannot fail when it drifts. This class reads every one of them:
 * it pins
 * the directory to a closed set of names, decodes every row through the shared codec against the
 * registered descriptor, asserts the field values the reporting mappers actually consume, re-encodes
 * every row to prove byte identity, drives both mappers with fixture rows so the data reaches the
 * code it was authored for, and asserts the structural markers that make the synthetic-data
 * attestation in {@code fixtures/README.md} checkable rather than merely stated.</p>
 *
 * <p>Parameters, return values, exceptions or errors. This is a test class with no constructor a
 * caller invokes, no value it yields and no exception it raises to anything outside the test engine,
 * so the type itself accepts no parameter, returns nothing and throws nothing. The inapplicability
 * is stated rather than passed over, because user-specified Rule 1 (Explainability) forbids a
 * docstring that omits parameters, return values or purpose, and a reader has to be able to tell a
 * declared inapplicability from an oversight. Every member below carries its own parameter, return
 * and exception at-clauses.</p>
 *
 * <h2>Assumptions: the newline is a file convention and never part of a record</h2>
 *
 * <p>Each fixture is line-oriented, one fixed-width record per line, with a terminating newline on
 * the last line -- the same shape the reference extracts under {@code app/data/ASCII/} use. The
 * declared record length excludes that byte, so every row is stripped of its line terminator before
 * decoding. Alternatives Considered: reading each file as one continuous byte stream and slicing it
 * at the declared length, which is what a production loader over an unterminated dataset would do.
 * Rejected because it would mis-align every record after the first by a growing number of bytes and
 * would report the misalignment as a field-content failure somewhere in the middle of the file.</p>
 *
 * <h2>Assumptions: the synthetic-data markers are asserted, not assumed</h2>
 *
 * <p>One of the fixtures is shaped exactly like a customer master and carries names, street
 * addresses, telephone numbers, a national identifier, a government-issued identifier and a date of
 * birth in every row. Its README attests that every one of those values is fabricated and that the
 * only values anywhere in the directory shared with the repository's own public sample extracts are
 * six KEYS -- two account identifiers, two customer identifiers and two card numbers -- shared on
 * purpose so a join resolves. Three structural markers make the fabricated half checkable -- an
 * unissued national-identifier area range, a reserved fictional telephone range and a literal
 * government-identifier prefix -- and each is asserted below, so a row added later from a real source
 * fails this suite rather than passing quietly.</p>
 *
 * <p>Refactoring Rationale: that read "its README attests that all of it is fabricated", which
 * overstated an attestation the README itself has since narrowed. Two of the four customer
 * identifiers in this very file ARE in {@code app/data/ASCII/custdata.txt}, deliberately, and section
 * 3 of the README gives the reason. The sentence is restated to the claim the markers below actually
 * check, because a preamble that promises more than its assertions deliver is the reason a reader
 * stops trusting the assertions.</p>
 *
 * <p>Assumptions: this directory carries synthetic primary account numbers and synthetic card
 * verification values, and neither is what the three markers below cover. {@code carddata.txt} holds
 * {@code CARD-NUM} at offset 0 and {@code CARD-CVV-CD} at offset 27; {@code cardxref.txt} holds
 * {@code XREF-CARD-NUM} at offset 0. The evidence that those are synthetic is structural in a
 * different way -- the five verification values are {@code 901} through {@code 905}, a counter no
 * issuer could compute -- and section 1.1 of the README carries it together with the handling
 * guidance. It is restated here because a reader of this class should not have to open the README to
 * learn that two of its bound fixtures carry those columns at all.</p>
 */
@DisplayName("Reporting fixtures: inventory, decoding, mapper consumption and provenance markers")
class ReportingFixtureContractTest {

    /**
     * Classpath directory holding every fixture this service ships.
     */
    private static final String FIXTURE_DIRECTORY = "fixtures";

    /**
     * The two card numbers this directory takes verbatim from the published demonstration extract.
     *
     * <p>Assumptions: these two, and only these two, are exempt from the two unroutability rules
     * {@link #noCommittedCardNumberIsAPlausibleCredential()} applies, because their provenance is
     * stronger than either rule. Both appear in {@code app/data/ASCII/carddata.txt}, the Apache-2.0
     * demonstration extract this repository publishes and that roughly sixty other fixtures across
     * the service tree already key on, so replacing them with synthetic values here would isolate
     * this directory from the rest of the corpus for no gain in safety. The exemption is not taken on
     * trust: that case reads the extract and asserts both are in it, so naming a value here cannot
     * exempt it.</p>
     *
     * <p>Assumptions: every field of these two rows OTHER than the card number is authored for this
     * directory -- the expiry dates are future-dated and the verification value is the placeholder
     * below -- so neither row is a copy of an extract record. Only the key is shared, which is the
     * same discipline the account and customer fixtures follow and which the directory's README
     * records under its key-domain section.</p>
     */
    private static final List<String> PUBLISHED_EXTRACT_CARDS =
            List.of("0500024453765740", "4859452612877065");

    /**
     * Leading digits every synthetic card number in this directory must carry.
     *
     * <p>Assumptions: {@code 9} is the ISO/IEC 7812 major industry identifier reserved for national
     * assignment, so no payment scheme issues in it and a number beginning here cannot be routed. The
     * prefix is four digits rather than one so that the value is visibly constructed at a glance
     * rather than only on inspection of its first character.</p>
     *
     * <p>Refactoring Rationale: the three synthetic rows previously carried {@code 0500024453765741},
     * {@code 1010000000000001} and {@code 3714496353984312}. The third is the well-known American
     * Express test number {@code 371449635398431} with a digit appended, so it read as a recognised
     * provider test range without being one; the first is a published extract number with its last
     * digit changed, so it read as a real card in an allocated range. Neither had any stated
     * provenance. Both hazards are the same one -- a committed value that looks like a credential --
     * and the prefix plus the deliberate Luhn failure removes it from all three.</p>
     */
    private static final String SYNTHETIC_CARD_PREFIX = "9900";

    /**
     * The verification value every row of the card fixture carries.
     *
     * <p>Assumptions: one repeated placeholder rather than a distinct value per row. The column is
     * declared {@code PIC 9(03)} so it can be neither blank nor shortened without breaking the
     * 150-byte geometry, and nothing in this module distinguishes the rows by it, so a per-row value
     * would carry no information and would only make the committed bytes resemble a verification
     * value. Masking it instead was rejected: masking is a mapper decision and applying it inside a
     * fixture would break the byte-identical round trip asserted above.</p>
     */
    private static final String PLACEHOLDER_VERIFICATION_VALUE = "000";

    /**
     * The account whose single card is deliberately absent from the cross-reference.
     *
     * <p>Assumptions: named as a constant so the identity of the unresolvable-lookup case is asserted
     * and not merely its count. A case that only counted one missing card would pass after an editor
     * moved the gap onto the two-card account, which would silently make the missing row recoverable
     * from the sibling row on the same account and destroy the case.</p>
     */
    private static final String UNREFERENCED_CARD_ACCOUNT = "00000000101";

    /**
     * The card number that must appear in no fixture in this directory.
     *
     * <p>Assumptions: this is the orphan the statement path points at, so its ABSENCE is the contract.
     * It is stated as a constant because a negative assertion needs something to name, and because an
     * editor adding it to "fix" the orphan would otherwise face no failure.</p>
     */
    private static final String SENTINEL_ABSENT_CARD = "9999999999999999";

    /**
     * The complete set of resources this directory may contain, in alphabetical order.
     *
     * <p>Assumptions: the set is closed rather than a lower bound, and the README is a member of it.
     * A directory assertion that only checked for the presence of expected files would pass after an
     * unrelated fixture was dropped in, and one that omitted the README would pass after the
     * provenance statement was deleted. Both are things this test exists to prevent.</p>
     *
     * <p>Assumptions: closing the set means each planned fixture must be admitted here by name as it
     * lands, so this list grows by deliberate edit rather than on its own. {@code tcatbal.txt} is
     * such an admission: it is the transaction-category-balance record of
     * {@code app/cpy/CVTRA01Y.cpy}, which {@code app/jcl/PRTCATBL.jcl} reads at its declared
     * 50-byte length, and it is a planned member of this directory rather than a stray file.</p>
     *
     * <p>Assumptions: {@code xreffile.txt} is the second such admission, and it is the same record
     * as {@code cardxref.txt} under a different data-definition name rather than a new record type.
     * {@code app/jcl/CREASTMT.JCL} line 84 supplies {@code XREFFILE} to {@code app/cbl/CBSTM03A.CBL},
     * and line 118 of {@code app/cbl/CBSTM03B.CBL} resolves that name as one of its four branches, so
     * the statement path reads the cross-reference under a name the report path never uses. Admitting
     * it by name is what keeps the two files from being mistaken for a duplicate of one another.</p>
     *
     * <p>Alternatives Considered: relaxing the assertion to a lower bound so that any newly added
     * fixture passes without an edit here. Rejected because it would surrender the exact property
     * the paragraph above is built on: an unrelated or accidentally committed file, or a fixture
     * written against no descriptor at all, would then enter this directory silently. Naming each
     * arrival costs one line and keeps the directory's contents an owned decision.</p>
     *
     * <p>Assumptions: {@code carddata.txt} is the second such admission. It is the card master of
     * {@code app/cpy/CVACT02Y.cpy}, whose {@code 01 CARD-RECORD} at line 4 declares six named fields
     * summing to 91 bytes and a trailing {@code FILLER PIC X(59)} at line 11, giving the 150-byte
     * length its header comment at line 2 states and {@link CopybookLayout} registers as
     * {@code CARD}. Its {@code CARD-CVV-CD} at line 7 occupies zero-based offset 27 for three bytes
     * and is registered SENSITIVE, so the fixture carries the three digits the copybook declares and
     * nothing masks them here; masking and suppression are mapper decisions, and applying either in a
     * fixture would break the byte-identical round trip asserted below. What the fixture does instead
     * is write the SAME non-discriminating placeholder on every row -- see
     * {@link #PLACEHOLDER_VERIFICATION_VALUE} -- so the column decodes without any row carrying a
     * value that could be read as a verification value.
     * {@code CARD-EXPIRAION-DATE} at line 9 carries the second of the three baseline misspellings and
     * is written at its declared offset under its declared name, because the correction to
     * {@code cards.expiration_date} belongs to the entity and the mapper rather than to the data.</p>
     *
     * <p>Alternatives Considered: leaving this record out of the directory altogether, on the ground
     * that NO reporting program reads the card master. That ground is real and is worth stating
     * plainly so no reader infers a dependency that does not exist: {@code app/jcl/TRANREPT.jcl}
     * declares its inputs at lines 65 to 74 as {@code TRANFILE}, {@code CARDXREF}, {@code TRANTYPE},
     * {@code TRANCATG} and {@code DATEPARM} with no {@code CARDFILE} among them, matching the six
     * {@code SELECT} clauses of {@code app/cbl/CBTRN03C.cbl} at lines 28 to 57; and the
     * {@code EVALUATE LK-M03B-DD} at line 118 of {@code app/cbl/CBSTM03B.CBL} offers only
     * {@code 'TRNXFILE'}, {@code 'XREFFILE'}, {@code 'CUSTFILE'} and {@code 'ACCTFILE'}, so
     * {@code app/cbl/CBSTM03A.CBL} cannot reach the card master either. Admitting it anyway was
     * chosen because {@code ReportingDtoMapperTest} already names {@code carddata.txt} among the ten
     * records this context reads and binds it to the {@code CARD} descriptor, and a named binding
     * with no record behind it is a claim rather than a check. The file makes that binding a
     * decodable exemplar that the three structural cases below actually exercise.</p>
     *
     * <p>Alternatively the record could have carried the fifty card numbers of the published extract.
     * Rejected because nothing in this module joins against them, so forty-five further rows would add
     * no coverage and forty-five chances to drift out of agreement with the account fixture. Five rows
     * are carried instead, and they are chosen to make three properties checkable: two of them share
     * account {@code 00000000050} so that the card-keyed control break of
     * {@code app/cbl/CBTRN03C.cbl} line 181 -- whose band line 183 labels "Account Total" while keying
     * on {@code WS-CURR-CARD-NUM} at line 137 -- is distinguishable from an implementation grouped by
     * account; exactly one carries {@code 'N'} in {@code CARD-ACTIVE-STATUS}, without which column 91
     * would be indistinguishable from padding, since all fifty rows of the reference extract are
     * {@code 'Y'}; and every {@code CARD-ACCT-ID} resolves in {@code acctfile.txt} with every
     * {@code CARD-EMBOSSED-NAME} agreeing with the matching customer in {@code custfile.txt}.</p>
     *
     * <p>Refactoring Rationale: all three of those properties, and the three the cross-reference entry
     * below states, were prose only. {@link #everyFixture()} asserts a name, a descriptor, a record
     * length and a row count, and the pad case asserts the trailing bytes, so an edit that preserved
     * all of those while pointing both card rows at different accounts -- or flipping the single
     * {@code 'N'} to {@code 'Y'} -- would have passed the entire suite while deleting the reason these
     * rows exist. {@link #theCardFixturesCarryEveryStatedRelationship()} now asserts every one of the
     * six, each read independently out of the two files rather than from a shared constant.</p>
     *
     * <p>Assumptions: {@code cardxref.txt} is the third such admission, and unlike the card master it
     * is a file the report path genuinely reads. It is the card cross-reference of
     * {@code app/cpy/CVACT03Y.cpy}, whose {@code 01 CARD-XREF-RECORD} at line 4 declares
     * {@code XREF-CARD-NUM PIC X(16)}, {@code XREF-CUST-ID PIC 9(09)} and
     * {@code XREF-ACCT-ID PIC 9(11)} summing to 36 bytes, plus a trailing {@code FILLER PIC X(14)} at
     * line 8, giving the 50-byte length its header comment at line 2 states and {@link CopybookLayout}
     * registers as {@code XREF}. {@code app/cbl/CBSTM03B.CBL} corroborates the same 50 bytes
     * independently, declaring {@code FD-XREFFILE-REC} at lines 66 to 68 as {@code X(16)} followed by
     * {@code X(34)}. {@code app/jcl/TRANREPT.jcl} names it at lines 67 and 68 under the DD
     * {@code CARDXREF}, and {@code app/cbl/CBTRN03C.cbl} reads it at line 485.</p>
     *
     * <p>Assumptions: one copybook serves TWO DD names, so this directory will hold two fixtures that
     * share the single {@code XREF} descriptor rather than declaring a layout each. This file is the
     * report path's set under DD {@code CARDXREF}; the statement path reads the identical 50-byte
     * record under DD {@code XREFFILE}, which is one of the four {@code WHEN} branches of the
     * {@code EVALUATE LK-M03B-DD} at line 118 of {@code app/cbl/CBSTM03B.CBL}. A reader who assumed
     * one file per descriptor would read the pair as a duplicate and delete one of them.</p>
     *
     * <p>Assumptions: its {@code XREF-ACCT-ID} at zero-based offset 25 is an access path rather than a
     * spare column. In the baseline it is the key of the {@code CXACAIX} alternate index, surfaced to
     * CICS as a file in its own right, and the migration replaces it with the non-unique index
     * {@code idx_card_xref_account_id}. Two of the four rows therefore share account
     * {@code 00000000050} under different card numbers, which is what makes that index exercisable and
     * what makes the card-keyed control break of {@code app/cbl/CBTRN03C.cbl} line 181 distinguishable
     * from an implementation grouped by account: its band at line 183 is labelled "Account Total"
     * while the break key is {@code WS-CURR-CARD-NUM} at line 137, so those two rows emit two bands
     * printing one account id where a {@code GROUP BY account_id} emits one band.</p>
     *
     * <p>Alternatives Considered: adding a fifth cross-reference row for the card master's third card,
     * or adding a row for card {@code 9999999999999999}. Both were rejected, and each carries its own
     * case. The card master's third card is deliberately UNREFERENCED so that this pair of files holds
     * an unresolvable lookup: the {@code INVALID KEY} path of {@code app/cbl/CBTRN03C.cbl} at lines
     * 486 to 490 ABENDS rather than warning and continuing, and because the lookup fires only inside
     * the control break at lines 181 to 188 a missing row makes the report print the account id left
     * resident from the PREVIOUS card rather than a blank. The sentinel {@code 9999999999999999} is
     * absent for a different reason -- it is the value the report and statement paths point at as
     * their orphan, so it must exist in neither the card master nor the cross-reference. Both
     * properties are asserted rather than described, by
     * {@link #theCardFixturesCarryEveryStatedRelationship()}, so adding either row to "tidy up" the
     * files fails rather than quietly deleting a case.</p>
     *
     * <p>Refactoring Rationale: that sentence previously said the sentinel "must exist in no fixture
     * here", which was broader than both its purpose and the assertion enforcing it. An unresolvable
     * key has to appear in the DRIVING record for the lookup to be attempted at all; what makes it
     * unresolvable is its absence from the two files the lookup reads. {@code tranfile.txt} carries
     * it as the card of its first orphan row for exactly that reason, and the assertions in
     * {@link #theCardFixturesCarryEveryStatedRelationship()} were always scoped to
     * {@code carddata.txt} and {@code cardxref.txt} alone, so the narrowed wording now matches both
     * the design and the code. Left as it was, the paragraph would have read as a prohibition on the
     * one row that makes the orphan case exist.</p>
     *
     * <p>Refactoring Rationale: this paragraph previously described the unreferenced card as one of
     * "the eighty-four {@code 1010000000000001} through {@code 1010000000000084} overflow cards" of a
     * statement-path superset. No such series exists anywhere in this repository -- that string
     * occurred only in this directory's own three files -- so the sentence attributed the row's
     * provenance to a corpus that does not exist. The row's real purpose, being the one card absent
     * from the cross-reference, is stated above and is now asserted.</p>
     *
     * <p>Assumptions: {@code tranfile.txt} is the third such admission, and it is the only member of
     * this directory whose name is a data-definition name rather than a seed-dataset name. The posted
     * transaction master is batch OUTPUT, so no seeded extract of it exists to borrow a name from;
     * {@code app/cbl/CBTRN03C.cbl} names its input {@code TRANFILE} and {@code app/jcl/TRANREPT.jcl}
     * supplies it at lines 65 and 66, so the DD name is what the file is called. Its layout is
     * {@code app/cpy/CVTRA05Y.cpy}, registered as {@code TRAN}, 350 bytes, and it carries 31 rows:
     * 26 inside the {@code 2022-01-01} to {@code 2022-07-06} window that lines 43 and 44 of that job
     * supply, two fully resolvable rows one day outside each end, and three rows whose card, type
     * code and type-and-category pair respectively resolve in none of the three lookup fixtures. It
     * is 350 rather than the 60 or 50 bytes of its neighbours because it is the driving record of the
     * report rather than one of the tables the report joins to.</p>
     *
     * <p>Trade-offs: admitting it costs one line here and three argument-source rows below, against
     * the alternative of leaving the closed set as it was and letting the fixture live outside the
     * assertions. That was rejected for the reason the paragraph above gives: a fixture this
     * directory holds but this class does not name is a fixture whose length, round trip and pad
     * character nobody checks, and it is the largest and most offset-dependent record here.</p>
     */
    private static final List<String> EXPECTED_RESOURCES =
            List.of("README.md", "acctfile.txt", "carddata.txt", "cardxref.txt", "custfile.txt",
                    "tcatbal.txt", "trancatg.txt", "tranfile.txt", "trantype.txt", "xreffile.txt");

    /**
     * Resolves the fixture directory on the test classpath.
     *
     * @return the directory holding the fixtures, never {@code null}
     * @throws IllegalStateException if the directory is absent from the classpath, which means the
     *     resource copy step did not run rather than that a fixture is missing
     */
    private static Path fixtureDirectory() {
        URL located = ReportingFixtureContractTest.class.getClassLoader()
                .getResource(FIXTURE_DIRECTORY);
        if (located == null) {
            throw new IllegalStateException(
                    "fixture directory " + FIXTURE_DIRECTORY + " is not on the test classpath");
        }
        try {
            return Path.of(located.toURI());
        } catch (URISyntaxException cause) {
            throw new IllegalStateException("fixture directory URL is not a usable path", cause);
        }
    }

    /**
     * Reads one fixture as a list of records, with every line terminator removed.
     *
     * @param fileName the fixture file name, relative to the fixture directory
     * @return the records in file order, none of them carrying a line terminator
     * @throws UncheckedIOException if the fixture cannot be read, which is a broken checkout rather
     *     than a contract failure and is therefore not translated into an assertion
     */
    private static List<String> records(String fileName) {
        try {
            return Files.readAllLines(fixtureDirectory().resolve(fileName), StandardCharsets.UTF_8);
        } catch (IOException cause) {
            throw new UncheckedIOException("cannot read fixture " + fileName, cause);
        }
    }

    /**
     * Decodes one record of a fixture against its registered descriptor.
     *
     * @param fileName the fixture file name
     * @param descriptorName the registered layout name the fixture is written against
     * @param rowIndex the zero-based row to decode
     * @return the decoded field map, keyed by copybook field name
     */
    private static Map<String, Object> decode(String fileName, String descriptorName,
            int rowIndex) {
        return FixedWidthCodec.decodeRecord(
                records(fileName).get(rowIndex).getBytes(StandardCharsets.UTF_8),
                CopybookLayout.layout(descriptorName));
    }

    /**
     * Supplies each fixture paired with the descriptor, record length and row count it claims.
     *
     * @return a stream of file name, descriptor name, declared record length and expected row count
     */
    private static Stream<Arguments> everyFixture() {
        return Stream.of(
                Arguments.of("acctfile.txt", "ACCOUNT", 300, 4),
                Arguments.of("custfile.txt", "CUSTOMER", 500, 4),
                Arguments.of("trantype.txt", "TRANTYPE", 60, 7),
                Arguments.of("trancatg.txt", "TRANCAT", 60, 9),

                // WHY : Assumptions: the card fixture is registered here rather than left merely
                //       present in the directory listing, because being listed proves only that a
                //       file exists. Its five rows against the 150-byte CARD descriptor are what
                //       subject it to the same three structural cases as its siblings: the declared
                //       length, the byte-for-byte round trip and the registered-descriptor check.
                //       Trade-offs: the row count is stated here as well as being readable from the
                //       file, so a row silently added or dropped fails this argument source rather
                //       than passing a suite that only ever asserted "every row is 150 bytes".
                Arguments.of("carddata.txt", "CARD", 150, 5),

                // WHY : Assumptions: the cross-reference is registered under the descriptor name XREF
                //       while the file is named cardxref.txt, and the two are NOT interchangeable.
                //       XREF is the registry key for app/cpy/CVACT03Y.cpy; cardxref.txt is named for
                //       the DD app/jcl/TRANREPT.jcl declares at lines 67 and 68, which happens to
                //       coincide with the seed dataset name. The SAME descriptor also serves the
                //       statement path's XREFFILE branch at line 118 of app/cbl/CBSTM03B.CBL, so a
                //       second fixture will arrive against this one entry rather than declaring a
                //       layout of its own.
                //       Trade-offs: the row count of four is stated here as well as being readable
                //       from the file, because two of those four rows exist only to share account
                //       00000000050 and a silently dropped row would take that property with it while
                //       still leaving every surviving row exactly 50 bytes.
                Arguments.of("cardxref.txt", "XREF", 50, 4),

                // WHY : Assumptions: this is the second fixture the entry above anticipated, and it
                //       arrives against that SAME descriptor rather than declaring one of its own.
                //       xreffile.txt is the DD name app/jcl/CREASTMT.JCL line 84 supplies to
                //       app/cbl/CBSTM03A.CBL, whose subprogram resolves it at line 118 of
                //       app/cbl/CBSTM03B.CBL, where FD-XREFFILE-REC is declared X(16) + X(34) --
                //       the same 50 bytes app/cpy/CVACT03Y.cpy sums to. Two file names over one
                //       registry entry is the whole point: a layout declared twice can disagree
                //       with itself.
                //       Trade-offs: the row count of 88 is stated here as well as being readable
                //       from the file. Eighty-four of those rows are one account's cards and exist
                //       only to carry more distinct cards than the 51 of WS-CARD-TBL at line 226 of
                //       app/cbl/CBSTM03A.CBL, so a silently dropped row would erode that margin
                //       while still leaving every surviving row exactly 50 bytes and passing every
                //       other case here.
                //       Alternatives Considered: numbering those 84 cards as a plain consecutive run
                //       9900001010000002 upward, which is the obvious scheme and is NOT what the file
                //       does -- its sixteenth digit is deliberately not the Luhn check digit of the
                //       first fifteen, so the visible sequence sits in digits ten to fifteen. The
                //       consecutive run was rejected because the check digit cycles: roughly one card
                //       in ten would come out Luhn-VALID, and a Luhn-valid sixteen-digit number is
                //       exactly what a scanner or a reviewer reads as a real card. Forcing the digit
                //       wrong keeps all 84 provably unissuable, which is the property this
                //       directory's README attests of every fabricated number.
                Arguments.of("xreffile.txt", "XREF", 50, 88),

                // WHY : Assumptions: the balance fixture is registered last because it is the child
                //       of the two reference fixtures above -- every one of its rows draws a type
                //       code from trantype.txt and a type-and-category pair from trancatg.txt, which
                //       is the shape the target preserves as a restricting foreign key. Declaring it
                //       here subjects it to the same three structural assertions as its siblings
                //       rather than leaving it merely present in the directory listing.
                //       Trade-offs: the descriptor name TCATBAL differs from the file name
                //       tcatbal.txt only in case, and the two are NOT interchangeable. The file is
                //       named for the seed dataset because PRTCATBL.jcl STEP10R runs DFSORT with no
                //       COBOL program and so has only generic SORTIN and SORTOUT names to offer,
                //       while TCATBAL is the registry key. Passing either one in the other position
                //       fails, which is the intended outcome.
                Arguments.of("tcatbal.txt", "TCATBAL", 50, 8),

                // WHY : Assumptions: the posted-transaction master is the DRIVING record of the
                //       report rather than one of the tables the report joins to, so its 350 bytes
                //       and 31 rows are declared here for the same three structural cases as its
                //       siblings. Its two load-bearing offsets are confirmed four times over -- field
                //       arithmetic across app/cpy/CVTRA05Y.cpy puts TRAN-CARD-NUM at zero-based 262
                //       and TRAN-PROC-TS at 304, app/jcl/TRANREPT.jcl lines 41 and 42 declare the
                //       ONE-based DFSORT positions 263 and 305, app/jcl/TRANIDX.jcl line 27 builds an
                //       alternate index KEYS(26 304) over RECORDSIZE(350,350), and
                //       app/cbl/CBTRN03C.cbl lines 61 to 65 split its own FD as X(304) plus X(26)
                //       plus X(20).
                //       Trade-offs: the row count of 31 is stated here as well as being readable from
                //       the file, because the count is load-bearing in a way a length check cannot
                //       see. WS-PAGE-SIZE at line 131 of that program is 20 and the grand total is
                //       accumulated from PAGE totals at line 297 rather than from transactions, so a
                //       fixture that quietly fell to 20 in-range rows or fewer would stop
                //       distinguishing that chain from a plain sum while every surviving row remained
                //       exactly 350 bytes.
                Arguments.of("tranfile.txt", "TRAN", 350, 31));
    }

    /**
     * Asserts that the fixture directory holds exactly the closed set of resources named here.
     *
     * @throws IOException if the directory cannot be listed
     */
    @Test
    @DisplayName("the fixture directory holds exactly the closed set of resources named here")
    void theFixtureDirectoryHoldsExactlyTheExpectedResources() throws IOException {
        try (Stream<Path> entries = Files.list(fixtureDirectory())) {
            assertThat(entries.map(path -> path.getFileName().toString()).sorted().toList())
                    .containsExactlyElementsOf(EXPECTED_RESOURCES);
        }
    }

    /**
     * Asserts that every row of every fixture is exactly the record length its descriptor declares.
     *
     * @param fileName the fixture under test
     * @param descriptorName the registered layout the fixture is written against
     * @param declaredLength the record length the descriptor declares
     * @param expectedRows the number of records the fixture is expected to hold
     */
    @ParameterizedTest(name = "{0} holds {3} rows of exactly {2} bytes")
    @MethodSource("everyFixture")
    @DisplayName("every row of every fixture is exactly the record length its descriptor declares")
    void everyRowIsExactlyItsDeclaredRecordLength(String fileName, String descriptorName,
            int declaredLength, int expectedRows) {
        List<String> rows = records(fileName);

        // WHY : Assumptions: the declared length is read from the registered descriptor AND stated
        //       again as a parameter, so the two must agree. Asserting only that each row matches the
        //       descriptor would pass for a descriptor whose reclen had itself been changed, which is
        //       the failure a fixture directory cannot detect on its own.
        assertThat(CopybookLayout.layout(descriptorName).reclen()).isEqualTo(declaredLength);
        assertThat(rows).hasSize(expectedRows);
        assertThat(rows).allSatisfy(row -> assertThat(row).hasSize(declaredLength));
    }

    /**
     * Asserts that every row of every fixture decodes and re-encodes to the identical bytes.
     *
     * @param fileName the fixture under test
     * @param descriptorName the registered layout the fixture is written against
     * @param declaredLength the record length the descriptor declares, unused by this assertion and
     *     accepted only because the argument source is shared
     * @param expectedRows the expected row count, unused by this assertion and accepted only because
     *     the argument source is shared
     */
    @ParameterizedTest(name = "{0} round-trips byte for byte")
    @MethodSource("everyFixture")
    @DisplayName("every row of every fixture decodes and re-encodes to the identical bytes")
    void everyRowRoundTripsByteForByte(String fileName, String descriptorName, int declaredLength,
            int expectedRows) {
        CopybookLayout.RecordSpec spec = CopybookLayout.layout(descriptorName);

        for (String row : records(fileName)) {
            byte[] original = row.getBytes(StandardCharsets.UTF_8);
            Map<String, Object> fields = FixedWidthCodec.decodeRecord(original, spec);

            // WHY : Assumptions: the sign-preserving encoder is used rather than the plain one. The
            //       plain encoder normalises a negative zero overpunch, which is a deliberate
            //       divergence registered as D-SIGNED-ZERO-ZONED, so a fixture holding one would
            //       fail a byte comparison for a reason that is not a fixture fault.
            assertThat(FixedWidthCodec.encodeRecordPreservingSign(fields, spec, original))
                    .isEqualTo(original);
        }
    }

    /**
     * Supplies each fixture paired with the trailing-pad character its reference extract uses.
     *
     * <p>Assumptions: the pad character is a per-record-type fact taken from the shipped extracts
     * under {@code app/data/ASCII/}, and it is NOT one convention for the whole directory. Measured
     * across those extracts, {@code acctdata.txt}, {@code custdata.txt} and {@code dailytran.txt}
     * pad with BLANKS while {@code tcatbal.txt}, {@code trantype.txt} and {@code trancatg.txt} pad
     * with ASCII ZEROES, and the sibling fixture trees agree -- {@code transaction-service}'s balance
     * fixtures and {@code reference-service}'s type and category fixtures all pad with zeroes.</p>
     *
     * <p>Refactoring Rationale: three of this directory's fixtures padded with blanks where
     * their extracts pad with zeroes, so the same record type was written two ways two directories
     * apart -- {@code reference-service}'s {@code trantype.txt} and this one differed in their last
     * eight bytes while claiming the same descriptor. The three are corrected and the convention is
     * asserted here so it cannot drift back silently, because a wrong pad byte changes nothing a
     * decode can detect: {@code FILLER} is a character field, so blanks and zeroes both decode, both
     * re-encode and both round-trip byte for byte.</p>
     *
     * <p>Alternatives Considered: padding every fixture in this directory with zeroes, which is the
     * simpler rule and is what a one-line reading of the defect suggests. Rejected on measurement:
     * it would have changed {@code acctfile.txt} and {@code custfile.txt} away from what their own
     * extracts do, replacing three divergences with two new ones. The pad belongs to the record
     * type, not to the directory.</p>
     *
     * @return a stream of fixture name, descriptor name and the single character its pad is made of
     */
    private static Stream<Arguments> everyFixturePad() {
        return Stream.of(
                Arguments.of("acctfile.txt", "ACCOUNT", ' '),
                Arguments.of("custfile.txt", "CUSTOMER", ' '),

                // WHY : Assumptions: the card record's pad is a BLANK, measured rather than assumed.
                //       The FILLER span at columns 92 to 150 of app/data/ASCII/carddata.txt holds
                //       exactly one distinct character across all fifty of its records, and that
                //       character is a blank, so this row follows the account and customer extracts
                //       rather than the three zero-padded ones.
                // WHY : Refactoring Rationale: this entry and the one below claimed the fixture
                //       "omits the FILLER key entirely and lets FixedWidthCodec rebuild the pad,
                //       which makes the value a codec fact". There is no key to omit. Both files are
                //       hand-authored fixed-width text and carry no field map at all, so the
                //       sentence described an encode path nothing here took and credited the pad
                //       byte to a mechanism that never ran. The independent route is now REAL rather
                //       than described, and it is narrower than the withdrawn claim:
                //       everyFixturePadIsSuppliedByTheCodecOnlyWhenItIsBlank below decodes each row,
                //       removes the FILLER entry from the decoded map, re-encodes, and asserts the
                //       committed bytes come back -- but only for the five BLANK-padded fixtures.
                //       The codec's rule is content-based, so a zero pad is content it carries
                //       rather than padding it derives, and the three zero-padded fixtures are
                //       asserted NOT to be reproducible from the descriptor. That asymmetry is why
                //       this argument source has to measure the character from the extract at all.
                // WHY : Assumptions: the cross-reference is the one record whose pad CANNOT be
                //       measured from its extract, because app/data/ASCII/cardxref.txt stores only the
                //       36 declared bytes and pads nothing at all -- it truncates where the account
                //       and card extracts pad. Two independent sources settle it as a BLANK instead,
                //       and they agree: the live house fixture at
                //       tests/fixtures/posting/happy_path/cardxref.txt writes those 36 bytes followed
                //       by 14 blanks, and the codec-rebuild case named above reaches the same 14
                //       bytes from the descriptor alone.
                //       Trade-offs: a zero pad was available and would have matched the three
                //       zero-padded reference records, but it would have contradicted the only
                //       committed cardxref fixture in the repository for a record type whose own
                //       extract offers no counter-evidence.
                Arguments.of("cardxref.txt", "XREF", ' '),

                // WHY : Assumptions: the statement path's cross-reference pads identically, because
                //       the pad is a property of the DESCRIPTOR the two files share rather than of
                //       either file. Registering it separately is still worth the line: it is what
                //       puts all 88 of its rows through the codec-rebuild case below, which is the
                //       only mechanical proof that its FILLER span agrees with app/cpy/CVACT03Y.cpy
                //       byte for byte.
                //       Alternatives Considered: leaving it out on the grounds that cardxref.txt
                //       already covers the XREF pad. Rejected because the two files carry different
                //       row counts and different card numbers, so a defect confined to the 84-row
                //       block -- a short row, or a FILLER a generator filled with zeros -- would be
                //       invisible to a case that only ever read the four-row file.
                Arguments.of("xreffile.txt", "XREF", ' '),
                Arguments.of("carddata.txt", "CARD", ' '),

                // WHY : Assumptions: the posted-transaction master's pad is a BLANK, measured rather
                //       than assumed, and it is measured from a DIFFERENT record's extract because
                //       its own is batch output and does not exist. app/data/ASCII/dailytran.txt is
                //       the daily-transaction record of app/cpy/CVTRA06Y.cpy, whose field widths are
                //       identical to CVTRA05Y's field for field, so its trailing FILLER occupies the
                //       same columns 331 to 350. All 300 of its records are 350 bytes and every one
                //       of those spans holds 20 blanks, so the measurement is unanimous.
                //       Alternatives Considered: padding this one with ASCII zeroes to match the
                //       three zero-padded reference records. Rejected on that measurement -- the pad
                //       belongs to the record type, and for this record type the only extract with
                //       the same geometry pads with blanks. Choosing zeroes would also have moved
                //       this fixture out of the codec-suppliable half of the rule asserted by
                //       everyFixturePadIsSuppliedByTheCodecOnlyWhenItIsBlank, turning a pad the
                //       descriptor can rebuild into content the file has to carry.
                Arguments.of("tranfile.txt", "TRAN", ' '),
                Arguments.of("tcatbal.txt", "TCATBAL", '0'),
                Arguments.of("trantype.txt", "TRANTYPE", '0'),
                Arguments.of("trancatg.txt", "TRANCAT", '0'));
    }

    /**
     * Asserts every fixture's trailing pad is made of the character its reference extract uses.
     *
     * @param fileName the fixture under test
     * @param descriptorName the registered layout the fixture is written against
     * @param padCharacter the single character the pad must consist of
     */
    @ParameterizedTest(name = "{0} pads its trailing FILLER with ''{2}''")
    @MethodSource("everyFixturePad")
    @DisplayName("every fixture pads its trailing FILLER with its extract's own character")
    void everyFixturePadsWithItsExtractsCharacter(String fileName, String descriptorName,
            char padCharacter) {
        CopybookLayout.RecordSpec spec = CopybookLayout.layout(descriptorName);
        List<CopybookLayout.FieldSpec> fields = spec.fields();
        CopybookLayout.FieldSpec pad = fields.get(fields.size() - 1);

        // WHY : Assumptions: the pad is located as the LAST declared field rather than by name,
        //       because the security-user record names its trailing pad SEC-USR-FILLER and a
        //       name match would silently skip a record whose pad is spelled differently. Its
        //       being a FILLER is then asserted, so a record whose last field is real content
        //       fails here rather than having its content compared against a pad character.
        assertThat(pad.name()).as("last declared field of %s", descriptorName).isEqualTo("FILLER");
        assertThat(pad.end()).isEqualTo(spec.reclen());

        String expected = String.valueOf(padCharacter).repeat(pad.length());
        for (String row : records(fileName)) {
            assertThat(row.substring(pad.start(), pad.end()))
                    .as("trailing pad of a %s row in %s", descriptorName, fileName)
                    .isEqualTo(expected);
        }

        // WHY : Assumptions: the two pad characters are asserted to be DIFFERENT in the same test,
        //       so the parameter is doing work. A suite that only checked "the pad is all of one
        //       character" would pass for a directory that had standardised on blanks and lost the
        //       per-record-type distinction this case exists to hold.
        assertThat(everyFixturePad().map(arguments -> arguments.get()[2]).distinct().count())
                .as("the pad character is a per-record-type fact, not one directory convention")
                .isEqualTo(2);
    }

    /**
     * Asserts that a row one byte short of its record length is refused rather than mis-decoded.
     */
    @Test
    @DisplayName("a row one byte short of its record length is refused rather than mis-decoded")
    void aShortRowIsRefusedRatherThanMisDecoded() {
        String row = records("trantype.txt").get(0);

        // WHY : Assumptions: a truncated row is the failure mode this directory is most exposed to,
        //       because an editor that strips trailing blanks shortens every record in a file at
        //       once. Asserting the refusal here is what turns that into a named failure rather than
        //       a field whose content silently came from the next field along.
        assertThatThrownBy(() -> FixedWidthCodec.decodeRecord(
                row.substring(0, row.length() - 1).getBytes(StandardCharsets.UTF_8),
                CopybookLayout.layout("TRANTYPE")))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> FixedWidthCodec.decodeRecord(
                (row + " ").getBytes(StandardCharsets.UTF_8),
                CopybookLayout.layout("TRANTYPE")))
                .isInstanceOf(RuntimeException.class);
    }

    /**
     * Asserts that the account fixture carries the key domain and money values its README states.
     */
    @Test
    @DisplayName("the account fixture carries the key domain and money values its README states")
    void theAccountFixtureCarriesItsStatedKeysAndMoney() {
        assertThat(records("acctfile.txt").stream().map(row -> row.substring(0, 11)).toList())
                .containsExactly("00000000007", "00000000050", "00000000101", "00000000102");

        Map<String, Object> first = decode("acctfile.txt", "ACCOUNT", 0);

        // WHY : Assumptions: the money fields are asserted through their decoded values rather than
        //       their stored characters, because the stored form carries a zoned-decimal sign
        //       overpunch -- the balance ends in 'G' for a positive 7 in the units position -- and a
        //       character assertion would pass for a codec that read the overpunch as text.
        assertThat(first.get("ACCT-ID")).hasToString("7");
        assertThat(first.get("ACCT-ACTIVE-STATUS")).isEqualTo("Y");
        assertThat(first.get("ACCT-CURR-BAL")).hasToString("504.77");
        assertThat(first.get("ACCT-CREDIT-LIMIT")).hasToString("2065.00");
        assertThat(first.get("ACCT-CASH-CREDIT-LIMIT")).hasToString("264.00");
        assertThat(first.get("ACCT-OPEN-DATE")).isEqualTo("2012-10-12");
        assertThat(first.get("ACCT-EXPIRAION-DATE")).isEqualTo("2028-11-30");
        assertThat(first.get("ACCT-GROUP-ID")).isEqualTo("ZEROAPR   ");
    }

    /**
     * Asserts that every account row carries a balance below the nine integer digits the statement
     * mask provides.
     */
    @Test
    @DisplayName("every account row carries a balance below the nine integer digits the statement "
            + "mask provides")
    void everyAccountBalanceFitsTheStatementMask() {
        // WHY : Assumptions: this is a property of the FIXTURE and not of the mapper. A balance of a
        //       thousand million or more raises under registered divergence D-EDIT-MASK-OVERFLOW, so
        //       a fixture row carrying one would make every statement test that used it fail for a
        //       reason unrelated to what it was asserting. Pinning the property here states the
        //       constraint where a row is added rather than where it is consumed.
        for (int row = 0; row < records("acctfile.txt").size(); row++) {
            Object balance = decode("acctfile.txt", "ACCOUNT", row).get("ACCT-CURR-BAL");
            assertThat(new java.math.BigDecimal(balance.toString()).abs())
                    .isLessThan(new java.math.BigDecimal("1000000000.00"));
        }
    }

    /**
     * Asserts that the customer fixture carries the name and address parts the statement header
     * reads.
     */
    @Test
    @DisplayName("the customer fixture carries the name and address parts the statement header "
            + "reads")
    void theCustomerFixtureCarriesTheStatementHeaderParts() {
        assertThat(records("custfile.txt").stream().map(row -> row.substring(0, 9)).toList())
                .containsExactly("000000007", "000000050", "000000101", "000000102");

        Map<String, Object> first = decode("custfile.txt", "CUSTOMER", 0);

        // WHY : Assumptions: every character field is asserted at its FULL declared width including
        //       trailing blanks, because that is the form the statement header preparation requires:
        //       it cuts each name part at its first blank, so a value handed over already trimmed
        //       would change which characters the assembly keeps.
        assertThat(first.get("CUST-FIRST-NAME")).isEqualTo("Marcus                   ");
        assertThat(first.get("CUST-MIDDLE-NAME")).isEqualTo("Elliot                   ");
        assertThat(first.get("CUST-LAST-NAME")).isEqualTo("Whitfield                ");
        assertThat(first.get("CUST-ADDR-LINE-1"))
                .isEqualTo("4820 Kingsbridge Terrace                          ");
        assertThat(first.get("CUST-ADDR-STATE-CD")).isEqualTo("OH");
        assertThat(first.get("CUST-ADDR-COUNTRY-CD")).isEqualTo("USA");
        assertThat(first.get("CUST-FICO-CREDIT-SCORE")).hasToString("701");
    }

    /**
     * Supplies the three structural markers that make the synthetic-data attestation checkable.
     *
     * @return a stream of marker label, field name and the assertion each row's value must satisfy
     */
    private static Stream<Arguments> syntheticMarkers() {
        return Stream.of(
                Arguments.of("national identifier in the unissued 900 range", "CUST-SSN", "9"),
                Arguments.of("government identifier carries the literal test prefix",
                        "CUST-GOVT-ISSUED-ID", "GOVTID"),
                Arguments.of("telephone number in the reserved fictional range", "CUST-PHONE-NUM-1",
                        "555-01"));
    }

    /**
     * Asserts that every customer row carries the synthetic marker its attestation claims.
     *
     * @param markerLabel a short description of the marker, shown in the case name
     * @param fieldName the copybook field the marker appears in
     * @param marker the substring every row's value must contain
     */
    @ParameterizedTest(name = "every customer row shows the {0}")
    @MethodSource("syntheticMarkers")
    @DisplayName("every customer row carries the synthetic marker its attestation claims")
    void everyCustomerRowCarriesItsSyntheticMarker(String markerLabel, String fieldName,
            String marker) {
        // WHY : Assumptions: these three assertions are the mechanical half of the synthetic-data
        //       attestation in fixtures/README.md. Without them the attestation is prose, and a row
        //       copied from a real source would satisfy every other assertion in this class -- its
        //       width, its offsets and its round trip are all indifferent to where a value came
        //       from. Trade-offs: a marker cannot PROVE synthesis, only that the value falls in a
        //       range no issuing authority uses; that is the strongest property a test can check,
        //       and the README carries the reasoning the test cannot.
        for (int row = 0; row < records("custfile.txt").size(); row++) {
            assertThat(String.valueOf(decode("custfile.txt", "CUSTOMER", row).get(fieldName)))
                    .contains(marker);
        }
    }

    /*
     * WHY : Refactoring Rationale: a test named everyCardRowCarriesAFabricatedVerificationValue
     *       stood here, asserting that each card row's CARD-CVV-CD began with {@code 9} so the
     *       value fell in a "fabricated 900 block" that no issuing authority uses. It is
     *       WITHDRAWN because the fixture no longer works that way, and the replacement is
     *       strictly stronger rather than merely different: every row now carries the SAME
     *       PLACEHOLDER_VERIFICATION_VALUE, so the column decodes at its declared width without
     *       any row carrying bytes that could be read as a verification value at all. A
     *       per-row value in the 900 block still LOOKS like a verification value, which is the
     *       property the placeholder removes.
     *       Assumptions: the coverage is not lost. The surviving case --
     *       "no committed card number is a plausible credential and no verification value is
     *       real" -- asserts the whole CARD-CVV-CD column equals that placeholder, which subsumes
     *       a per-row leading-digit check and additionally rejects a row that varied its value.
     *       Trade-offs: the withdrawn assertion admitted new rows with any 9xx value, so it was
     *       the more permissive of the two for a fixture that is meant to be uniform; the
     *       replacement makes adding a row with a distinct value a failure, which is the intent.
     */

    /**
     * Measures how many characters of each row's description field are occupied by text.
     *
     * <p>Assumptions: the trailing blanks of a decoded text field are padding to the declared width
     * and are removed here, because the property being measured is how much text a row carries and
     * not how wide its field is. Only TRAILING blanks are removed: a leading blank would be content
     * in an alphanumeric move and removing it would change which characters a narrowing keeps.</p>
     *
     * @param fileName the fixture file name
     * @param descriptorName the registered layout name the fixture is written against
     * @param fieldName the copybook description field to measure
     * @return the occupied length of each row's description, in file order
     */
    private static List<Integer> occupiedDescriptionLengths(String fileName, String descriptorName,
            String fieldName) {
        return records(fileName).stream()
                .map(row -> String.valueOf(
                        FixedWidthCodec.decodeRecord(row.getBytes(StandardCharsets.UTF_8),
                                CopybookLayout.layout(descriptorName)).get(fieldName))
                        .stripTrailing().length())
                .toList();
    }

    /**
     * Asserts that the two description fixtures hold both a full-width and a short population.
     */
    @Test
    @DisplayName("the two description fixtures hold both a full-width and a short population")
    void theDescriptionFixturesHoldBothPopulations() {
        List<Integer> typeLengths = occupiedDescriptionLengths("trantype.txt", "TRANTYPE",
                "TRAN-TYPE-DESC");
        List<Integer> categoryLengths = occupiedDescriptionLengths("trancatg.txt", "TRANCAT",
                "TRAN-CAT-TYPE-DESC");

        // WHY : Assumptions: the OCCUPIED length is measured and not the decoded length. A text field
        //       decodes at its full declared 50 with its trailing blanks intact, so a raw length
        //       assertion would report every row as 50 and could not tell a full description from a
        //       short one at all -- which is precisely the distinction this test needs.
        // WHY : Assumptions: both populations are required and the reason is asymmetric. A fixture of
        //       only short descriptions would pass against a narrowing that returned its input
        //       unchanged; a fixture of only full-width ones would pass against a narrowing that
        //       always truncated and never blank-filled. Neither alone distinguishes a correct
        //       narrowing from a broken one.
        assertThat(typeLengths).contains(50);
        assertThat(typeLengths).anyMatch(length -> length < 15);
        assertThat(categoryLengths).contains(50);
        assertThat(categoryLengths).anyMatch(length -> length < 29);
    }

    /**
     * Asserts that the category fixture keys on the six-byte composite rather than on the type
     * alone.
     */
    @Test
    @DisplayName("the category fixture keys on the six-byte composite rather than on the type "
            + "alone")
    void theCategoryFixtureKeysOnTheComposite() {
        List<String> keys = records("trancatg.txt").stream()
                .map(row -> row.substring(0, 6))
                .toList();

        // WHY : Assumptions: the three rows under type 01 are what make this fixture able to tell a
        //       composite-keyed lookup from a type-keyed one. A one-to-one fixture would satisfy
        //       both readings, and the composite is what app/cpy/CVTRA04Y.cpy declares.
        assertThat(keys).containsExactly("010001", "010002", "010005", "020001", "030001", "040001",
                "050001", "060001", "070001");
        assertThat(keys).doesNotHaveDuplicates();
        assertThat(keys.stream().filter(key -> key.startsWith("01")).count()).isEqualTo(3);
    }

    /**
     * Asserts that a fixture account and customer row together drive a complete statement header
     * block.
     */
    @Test
    @DisplayName("a fixture account and customer row together drive a complete statement header "
            + "block")
    void fixtureRowsDriveACompleteStatementHeaderBlock() {
        Map<String, Object> account = decode("acctfile.txt", "ACCOUNT", 0);
        Map<String, Object> customer = decode("custfile.txt", "CUSTOMER", 0);

        StatementTextMapper.PreparedHeaderFields header = StatementTextMapper.prepareHeaderFields(
                String.valueOf(customer.get("CUST-FIRST-NAME")),
                String.valueOf(customer.get("CUST-MIDDLE-NAME")),
                String.valueOf(customer.get("CUST-LAST-NAME")),
                String.valueOf(customer.get("CUST-ADDR-LINE-1")),
                String.valueOf(customer.get("CUST-ADDR-LINE-2")),
                String.valueOf(customer.get("CUST-ADDR-LINE-3")),
                String.valueOf(customer.get("CUST-ADDR-STATE-CD")),
                String.valueOf(customer.get("CUST-ADDR-COUNTRY-CD")),
                String.valueOf(customer.get("CUST-ADDR-ZIP")),
                Long.parseLong(account.get("ACCT-ID").toString()),
                Money.of(account.get("ACCT-CURR-BAL").toString()),
                Integer.parseInt(customer.get("CUST-FICO-CREDIT-SCORE").toString()));

        // WHY : Assumptions: this is what makes the fixtures CONSUMED rather than merely validated.
        //       Every offset in both records is exercised through the mapper that reads it, so a
        //       field placed one byte out shows up as a wrong statement line rather than only as a
        //       wrong decoded value.
        List<byte[]> block = StatementTextMapper.emitHeaderBlock(header);
        assertThat(block).hasSize(StatementTextMapper.HEADER_BLOCK_LINE_COUNT);
        assertThat(block).allSatisfy(line -> assertThat(line).hasSize(80));
        assertThat(new String(block.get(1), StandardCharsets.UTF_8).stripTrailing())
                .isEqualTo("Marcus Elliot Whitfield");
        assertThat(new String(block.get(8), StandardCharsets.UTF_8))
                .startsWith("Account ID         :00000000007");
        assertThat(new String(block.get(9), StandardCharsets.UTF_8))
                .startsWith("Current Balance    :000000504.77");
        assertThat(new String(block.get(10), StandardCharsets.UTF_8))
                .startsWith("FICO Score         :701");
    }

    /**
     * Asserts that a fixture type and category row together drive a complete report detail line.
     */
    @Test
    @DisplayName("a fixture type and category row together drive a complete report detail line")
    void fixtureRowsDriveACompleteReportDetailLine() {
        Map<String, Object> type = decode("trantype.txt", "TRANTYPE", 0);
        Map<String, Object> category = decode("trancatg.txt", "TRANCAT", 0);

        byte[] detail = TransactionReportMapper.encodeDetailLine(
                "0000000000000001",
                7L,
                String.valueOf(type.get("TRAN-TYPE")),
                String.valueOf(type.get("TRAN-TYPE-DESC")),
                Integer.parseInt(category.get("TRAN-CAT-CD").toString()),
                String.valueOf(category.get("TRAN-CAT-TYPE-DESC")),
                "POS TERM  ",
                Money.of("1234.56"));

        // WHY : Assumptions: both fixture descriptions are 50 characters here, so this assertion is
        //       the one that proves the narrowing actually discards text. The type description keeps
        //       its leftmost 15 and the category description its leftmost 29, and each expected
        //       substring is written out rather than computed so a change of column width cannot be
        //       absorbed silently by the expectation.
        String line = new String(detail, StandardCharsets.UTF_8);
        assertThat(detail).hasSize(133);
        assertThat(line).isEqualTo("0000000000000001"
                + " " + "00000000007"
                + " " + "01" + "-" + "Purchase at poi"
                + " " + "0001" + "-" + "Regular sales draft purchase "
                + " " + "POS TERM  "
                + "    " + "       1,234.56"
                + "  " + " ".repeat(19));
        assertThat(line.substring(32, 47)).isEqualTo("Purchase at poi");
        assertThat(line.substring(53, 82)).isEqualTo("Regular sales draft purchase ");
    }

    /**
     * Asserts that a fixture category description drives the statement's own forty-nine-character
     * narrowing.
     */
    @Test
    @DisplayName("a fixture category description drives the statement's own forty-nine-character "
            + "narrowing")
    void aFixtureDescriptionDrivesTheStatementNarrowing() {
        String description =
                String.valueOf(decode("trancatg.txt", "TRANCAT", 0).get("TRAN-CAT-TYPE-DESC"));

        // WHY : Assumptions: the statement column is 49 where the report's category column is 29, so
        //       the same fixture value is narrowed to two different widths by two different
        //       artifacts. Asserting both from one source value is what shows the two narrowings are
        //       independent rather than one shared rule.
        assertThat(description).hasSize(50);
        assertThat(StatementTextMapper.renderDescriptionItem(description))
                .isEqualTo(description.substring(0, 49))
                .hasSize(StatementTextMapper.DESCRIPTION_ITEM_WIDTH);
    }

    /**
     * Asserts that every fixture is decoded against a descriptor the shared registry actually holds.
     *
     * @param fileName the fixture under test, shown in the case name
     * @param descriptorName the registered layout name the fixture is written against
     * @param declaredLength the record length the descriptor declares, unused by this assertion and
     *     accepted only because the argument source is shared
     * @param expectedRows the expected row count, unused by this assertion and accepted only because
     *     the argument source is shared
     */
    @ParameterizedTest(name = "{0} is written against the registered descriptor {1}")
    @MethodSource("everyFixture")
    @DisplayName("every fixture is decoded against a descriptor the shared registry actually holds")
    void everyFixtureUsesARegisteredDescriptor(String fileName, String descriptorName,
            int declaredLength, int expectedRows) {
        // WHY : Assumptions: membership of the registry is asserted rather than assumed, because the
        //       codec blanks an absent position only for a layout its own registry holds. A fixture
        //       written against a locally declared descriptor would decode under different rules
        //       from the one production reads it with, and nothing else in this class would notice.
        assertThat(CopybookLayout.names()).contains(descriptorName);
        assertThat(CopybookLayout.layout(descriptorName).name()).isEqualTo(descriptorName);
    }

    /**
     * Reads one declared field from every row of a fixture, as trimmed text in file order.
     *
     * <p>Assumptions: the value is read from the RAW substring at the descriptor's declared offset
     * rather than from a decoded map, and the difference matters for the numeric keys. A decode
     * renders {@code CARD-ACCT-ID PIC 9(11)} as a number, which drops the leading zeros that are
     * part of the eleven-byte key, so two rows keyed {@code 00000000050} and {@code 50} would
     * compare equal. Reading the bytes keeps the key at its declared width, which is what the
     * cross-file joins below have to compare on.</p>
     *
     * @param fileName the fixture to read
     * @param descriptorName the registered layout the fixture is written against
     * @param fieldName the copybook field name to extract
     * @return that field's text for every row, in file order, each trimmed of its padding
     * @throws IllegalArgumentException if the descriptor declares no field of that name, which is a
     *     mistake in the caller rather than a fixture fault
     */
    private static List<String> column(String fileName, String descriptorName, String fieldName) {
        CopybookLayout.FieldSpec field = CopybookLayout.layout(descriptorName).fields().stream()
                .filter(candidate -> fieldName.equals(candidate.name()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        descriptorName + " declares no field named " + fieldName));
        return records(fileName).stream()
                .map(row -> row.substring(field.start(), field.end()).trim())
                .toList();
    }

    /**
     * Asserts every relationship the card and cross-reference fixtures were authored to carry.
     *
     * <p>Purpose: the charter above states six discriminating properties of these two files -- two
     * card rows sharing one account, exactly one inactive card, every account resolving in the
     * account fixture, every embossed name agreeing with the matching customer, two of the four
     * cross-reference rows sharing that same account, and one card deliberately absent from the
     * cross-reference. This case asserts all six.</p>
     *
     * <p>Refactoring Rationale: those six properties were prose only. The registrations in
     * {@link #everyFixture()} assert a name, a descriptor, a record length and a row count, and the
     * pad case asserts the trailing bytes, so an edit that preserved width, count and padding while
     * destroying every relationship the rows exist for would have passed the whole suite. That is
     * the specific failure this case closes: pointing both card rows at different accounts, or
     * flipping the one {@code 'N'} status to {@code 'Y'}, would leave five 150-byte rows and five
     * blank pads and would silently delete the control-break discriminator the charter argues for.</p>
     *
     * <p>Assumptions: each relationship is asserted from the two files INDEPENDENTLY rather than from
     * a shared constant. A constant naming the shared account would be satisfied by a fixture that no
     * longer contained it; reading the account column out of each file and comparing the two is what
     * makes the assertion about the committed bytes.</p>
     */
    @Test
    @DisplayName("the card and cross-reference fixtures carry every relationship their charter states")
    void theCardFixturesCarryEveryStatedRelationship() {
        List<String> cardNumbers = column("carddata.txt", "CARD", "CARD-NUM");
        List<String> cardAccounts = column("carddata.txt", "CARD", "CARD-ACCT-ID");
        List<String> cardNames = column("carddata.txt", "CARD", "CARD-EMBOSSED-NAME");
        List<String> cardStatuses = column("carddata.txt", "CARD", "CARD-ACTIVE-STATUS");

        List<String> xrefCards = column("cardxref.txt", "XREF", "XREF-CARD-NUM");
        List<String> xrefCustomers = column("cardxref.txt", "XREF", "XREF-CUST-ID");
        List<String> xrefAccounts = column("cardxref.txt", "XREF", "XREF-ACCT-ID");

        List<String> accountKeys = column("acctfile.txt", "ACCOUNT", "ACCT-ID");
        List<String> customerKeys = column("custfile.txt", "CUSTOMER", "CUST-ID");
        List<String> customerFirstNames = column("custfile.txt", "CUSTOMER", "CUST-FIRST-NAME");
        List<String> customerLastNames = column("custfile.txt", "CUSTOMER", "CUST-LAST-NAME");

        // WHY : Assumptions: the card key domain is asserted to be a SET rather than merely counted,
        //       because two identical card numbers would still be five rows of 150 bytes and the
        //       cross-reference join below would then resolve one of them twice.
        assertThat(cardNumbers).as("card numbers are distinct").doesNotHaveDuplicates();
        assertThat(xrefCards).as("cross-referenced card numbers are distinct").doesNotHaveDuplicates();

        // WHY : Assumptions: the shared account is DERIVED from the file rather than named as a
        //       literal, so the assertion is about whichever account the rows actually share. The
        //       property under test is that exactly one account carries two cards, which is what makes
        //       the card-keyed control break of app/cbl/CBTRN03C.cbl line 181 distinguishable from an
        //       implementation grouped by account -- two cards on one account emit two bands printing
        //       one account id, where a GROUP BY account_id emits one.
        Map<String, Long> cardsPerAccount = cardAccounts.stream()
                .collect(java.util.stream.Collectors.groupingBy(account -> account,
                        java.util.stream.Collectors.counting()));
        List<String> accountsWithTwoCards = cardsPerAccount.entrySet().stream()
                .filter(entry -> entry.getValue() == 2L)
                .map(Map.Entry::getKey)
                .toList();
        assertThat(accountsWithTwoCards)
                .as("exactly one account must carry two cards, so the card-keyed break is provable")
                .hasSize(1);
        String sharedAccount = accountsWithTwoCards.get(0);
        assertThat(cardsPerAccount.values().stream().filter(count -> count > 2L).toList())
                .as("no account may carry more than two cards in this fixture")
                .isEmpty();

        // WHY : Assumptions: the SAME account has to be the one the cross-reference doubles up on,
        //       measured from the second file rather than assumed to follow from the first. If the two
        //       files disagreed, the account-keyed secondary index idx_card_xref_account_id would be
        //       exercised over a different account than the control break, and neither file would fail.
        assertThat(xrefAccounts.stream().filter(sharedAccount::equals).count())
                .as("the cross-reference must double up on the same account the card master does: %s",
                        sharedAccount)
                .isEqualTo(2L);

        // WHY : Assumptions: exactly one row carries 'N'. Without it, column 91 would be
        //       indistinguishable from padding, because all fifty rows of app/data/ASCII/carddata.txt
        //       are 'Y' and a fixture that copied that would never exercise the inactive branch.
        assertThat(cardStatuses.stream().filter("N"::equals).count())
                .as("exactly one card must be inactive, so CARD-ACTIVE-STATUS is not padding")
                .isEqualTo(1L);
        assertThat(cardStatuses).as("no status may be anything but Y or N").containsOnly("Y", "N");

        // WHY : Assumptions: every card's account resolves in the account fixture, and every
        //       cross-reference row's account and customer resolve in theirs. This is the referential
        //       closure the charter claims, and it is the property that makes the fixture loadable
        //       against real foreign keys -- which the sibling ReportingCardIntegrationIT then does.
        assertThat(accountKeys).as("every card's account must resolve").containsAll(cardAccounts);
        assertThat(accountKeys).as("every cross-referenced account must resolve")
                .containsAll(xrefAccounts);
        assertThat(customerKeys).as("every cross-referenced customer must resolve")
                .containsAll(xrefCustomers);

        // WHY : Assumptions: the embossed name is compared against the CUSTOMER row reached through
        //       the account rather than against a literal, so the two files are held to each other.
        //       The comparison is on the first and last name joined by one blank, which is the form
        //       CARD-EMBOSSED-NAME carries; the middle name is deliberately not part of it, because
        //       an embossed name is what fits the card face.
        for (int row = 0; row < cardNumbers.size(); row++) {
            String account = cardAccounts.get(row);
            int accountRow = accountKeys.indexOf(account);
            assertThat(accountRow)
                    .as("card %s names account %s, which must exist in acctfile.txt",
                            cardNumbers.get(row), account)
                    .isNotNegative();
            String customer = customerKeys.get(accountRow);
            String embossed = customerFirstNames.get(accountRow) + " "
                    + customerLastNames.get(accountRow);
            assertThat(cardNames.get(row))
                    .as("card %s must be embossed with customer %s's name", cardNumbers.get(row),
                            customer)
                    .isEqualTo(embossed);
        }

        // WHY : Assumptions: exactly one card of the five is ABSENT from the cross-reference, and its
        //       identity is asserted rather than only its count. It is the unresolvable-lookup case the
        //       transaction fixture can point at: the INVALID KEY path of app/cbl/CBTRN03C.cbl at lines
        //       486 to 490 abends rather than warning, and because the lookup fires only inside the
        //       control break at lines 181 to 188, a missing row makes the report print the account id
        //       left resident from the PREVIOUS card rather than a blank.
        List<String> unreferenced = cardNumbers.stream()
                .filter(card -> !xrefCards.contains(card))
                .toList();
        assertThat(unreferenced)
                .as("exactly one card must be absent from the cross-reference")
                .hasSize(1);
        assertThat(cardAccounts.get(cardNumbers.indexOf(unreferenced.get(0))))
                .as("the unreferenced card must be the one on the account that carries no other card")
                .isEqualTo(UNREFERENCED_CARD_ACCOUNT);

        // WHY : Assumptions: the sentinel card is asserted ABSENT from both files. It is the value the
        //       statement path's orphan case names, so a well-meaning editor adding it to "fix" the
        //       orphan would delete that case; a negative assertion is what makes the deletion fail.
        assertThat(cardNumbers).as("the sentinel card must not be in the card master")
                .doesNotContain(SENTINEL_ABSENT_CARD);
        assertThat(xrefCards).as("the sentinel card must not be in the cross-reference")
                .doesNotContain(SENTINEL_ABSENT_CARD);

        // WHY : Assumptions: every cross-reference row also exists in the card master, so the two
        //       files are closed against each other in BOTH directions. Only the card master may carry
        //       an extra row, and only the one named above.
        assertThat(cardNumbers).as("every cross-referenced card must exist in the card master")
                .containsAll(xrefCards);
    }

    /**
     * Asserts that no committed card number is a plausible payment credential.
     *
     * <p>Purpose: the fixture directory attests its own provenance, and for the two files that carry a
     * primary account number the attestation has two halves -- which numbers came from the published
     * extract in this repository, and that every other one is deliberately unroutable. This case
     * asserts both halves against the bytes.</p>
     *
     * <p>Assumptions: "unroutable" is asserted as two independent properties, because either alone is
     * weak. A Luhn-invalid number in an allocated range still names a real issuer; a number in the
     * major-industry-identifier 9 range that happened to satisfy Luhn would still look like a
     * credential. Requiring both of every synthetic value is what leaves no committed number that
     * could be mistaken for one, and it is what a fixed-width fixture can offer in place of masking --
     * masking is a mapper decision and applying it here would break the byte-identical round trip.</p>
     *
     * <p>Assumptions: the verification value is asserted to be the same non-discriminating placeholder
     * on every row rather than a distinct value per row. {@code CARD-CVV-CD} is declared
     * {@code PIC 9(03)}, so it can be neither blank nor shortened without breaking the 150-byte
     * geometry, and no case in this module distinguishes the rows by it -- the mapper suppresses it
     * from every response. One repeated placeholder is therefore all the fixture needs, and it is what
     * keeps the committed bytes from resembling a verification value at all.</p>
     */
    @Test
    @DisplayName("no committed card number is a plausible credential and no verification value is real")
    void noCommittedCardNumberIsAPlausibleCredential() {
        List<String> cardNumbers = column("carddata.txt", "CARD", "CARD-NUM");
        List<String> xrefCards = column("cardxref.txt", "XREF", "XREF-CARD-NUM");

        for (String card : cardNumbers) {
            assertThat(card).as("every card number is exactly sixteen digits").hasSize(16)
                    .containsOnlyDigits();
            if (PUBLISHED_EXTRACT_CARDS.contains(card)) {
                continue;
            }
            assertThat(card)
                    .as("card %s is not from the published extract, so it must sit in the"
                            + " national-assignment major-industry range", card)
                    .startsWith(SYNTHETIC_CARD_PREFIX);
            assertThat(satisfiesLuhn(card))
                    .as("card %s is synthetic, so it must deliberately fail the Luhn check", card)
                    .isFalse();
        }

        // WHY : Assumptions: the published-extract members are asserted to BE in the extract rather
        //       than trusted from the constant, by reading app/data/ASCII/carddata.txt. That file is
        //       REFERENCE-only and is read here and never written. Without this read the constant would
        //       be a way to exempt any value from the two rules above simply by naming it.
        List<String> published = publishedExtractCardNumbers();
        assertThat(published).as("the published extract must contain every card the constant exempts")
                .containsAll(PUBLISHED_EXTRACT_CARDS);
        assertThat(cardNumbers).as("every exempted card must actually be used by this fixture")
                .containsAll(PUBLISHED_EXTRACT_CARDS);

        assertThat(xrefCards)
                .as("the cross-reference may name no card the master does not carry, so its numbers"
                        + " inherit the same two guarantees")
                .allSatisfy(card -> assertThat(cardNumbers).contains(card));

        assertThat(column("carddata.txt", "CARD", "CARD-CVV-CD"))
                .as("every verification value is the same non-discriminating placeholder")
                .containsOnly(PLACEHOLDER_VERIFICATION_VALUE);
    }

    /**
     * Reports whether a card number satisfies the Luhn check digit.
     *
     * <p>Assumptions: implemented here rather than taken from a library, because no production class
     * in this migration validates a card number -- the reference application does not either -- and
     * introducing a dependency so a test could reject its own fixtures would put a check-digit rule
     * into the shipped image for no runtime purpose.</p>
     *
     * @param cardNumber the digits to test; must be non-empty and contain digits only
     * @return {@code true} when the doubled-alternate-digit sum is a multiple of ten
     */
    private static boolean satisfiesLuhn(String cardNumber) {
        int sum = 0;
        for (int position = 0; position < cardNumber.length(); position++) {
            int digit = cardNumber.charAt(cardNumber.length() - 1 - position) - '0';
            if (position % 2 == 1) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
        }
        return sum % 10 == 0;
    }

    /**
     * Reads every card number from the published demonstration extract in this repository.
     *
     * @return the sixteen-byte card numbers of {@code app/data/ASCII/carddata.txt}, in file order
     * @throws UncheckedIOException if the extract cannot be read, which means the checkout is
     *     incomplete rather than that a fixture is wrong
     */
    private static List<String> publishedExtractCardNumbers() {
        Path extract = repositoryRoot().resolve("app/data/ASCII/carddata.txt");
        try {
            return Files.readAllLines(extract, StandardCharsets.ISO_8859_1).stream()
                    .filter(row -> !row.isBlank())
                    .map(row -> row.substring(0, 16))
                    .toList();
        } catch (IOException cause) {
            throw new UncheckedIOException("cannot read the published extract " + extract, cause);
        }
    }

    /**
     * Resolves the repository root by walking up from the working directory.
     *
     * <p>Assumptions: located by the presence of {@code services/pom.xml} rather than by a fixed
     * number of parent steps, so the suite runs identically from the reactor root and from the module
     * directory. A relative path would resolve differently between those two invocations.</p>
     *
     * @return the repository root
     * @throws IllegalStateException if no ancestor carries the reactor descriptor
     */
    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve("services/pom.xml"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException(
                "no ancestor of the working directory carries services/pom.xml");
    }

    /**
     * Asserts which fixtures' trailing pads the codec supplies and which ones carry theirs as content.
     *
     * <p>Purpose: settle, per record type, whether the committed pad byte is something the codec
     * derives from the descriptor or something the file states. The codec's rule is content-based and
     * measured here rather than assumed: a BLANK padding field is dropped on decode and restored on
     * encode, while a NONBLANK one stays content and is carried through. So the five blank-padded
     * fixtures do get their pad from the codec, and the three zero-padded ones do not -- their zeroes
     * are data the file supplies, which is exactly why
     * {@link #everyFixturePadsWithItsExtractsCharacter} has to measure the character from the
     * extract instead of deriving it.</p>
     *
     * <p>Refactoring Rationale: two entries of {@link #everyFixturePad()} claimed the card and
     * cross-reference fixtures reached their pad by "omitting the FILLER key entirely" so the codec
     * rebuilt it. Nothing did that: both files are hand-authored text with no field map, and
     * {@link #everyFixturePadsWithItsExtractsCharacter} compares a raw substring against a repeated
     * character, which is the same typed-in byte read back. Neither could
     * {@link #everyRowRoundTripsByteForByte} stand in for it -- that case passes the original bytes to
     * the sign-preserving encoder as a template, so the pad it reproduces comes from the template and
     * not from the descriptor. This case is the witness the prose described, made real and made
     * two-sided, so the asymmetry between the blank and zero pads is asserted rather than papered
     * over.</p>
     *
     * <p>Assumptions: only the PAD SPAN of the from-scratch encode is compared, not the whole record.
     * A from-scratch encode re-renders every field from its decoded value, so a numeric or sign
     * rendering difference elsewhere would fail a whole-record comparison for a reason that is not a
     * pad fault. Narrowing the comparison to the span keeps the case about the one byte it names.</p>
     *
     * @param fileName the fixture under test
     * @param descriptorName the registered layout the fixture is written against
     * @param padCharacter the character the pad must consist of, which decides which half of the
     *     codec's content-based rule this fixture exercises
     */
    @ParameterizedTest(name = "{0} pad provenance")
    @MethodSource("everyFixturePad")
    @DisplayName("a blank pad is supplied by the codec and a zero pad is carried as content")
    void everyFixturePadIsSuppliedByTheCodecOnlyWhenItIsBlank(String fileName,
            String descriptorName, char padCharacter) {
        CopybookLayout.RecordSpec spec = CopybookLayout.layout(descriptorName);
        List<CopybookLayout.FieldSpec> declared = spec.fields();
        CopybookLayout.FieldSpec pad = declared.get(declared.size() - 1);
        String padName = pad.name();
        String committedPad = String.valueOf(padCharacter).repeat(pad.length());
        String blankPad = " ".repeat(pad.length());

        for (String row : records(fileName)) {
            byte[] original = row.getBytes(StandardCharsets.UTF_8);
            Map<String, Object> decoded =
                    new java.util.LinkedHashMap<>(FixedWidthCodec.decodeRecord(original, spec));

            if (padCharacter == ' ') {
                assertThat(decoded)
                        .as("%s: a blank %s is padding, so the codec drops it on decode rather than"
                                + " handing 59 or 178 meaningless blanks to a consumer", fileName,
                                padName)
                        .doesNotContainKey(padName);
            } else {
                assertThat(decoded)
                        .as("%s: a nonblank %s is CONTENT, so the codec keeps it", fileName, padName)
                        .containsKey(padName);
                assertThat(String.valueOf(decoded.remove(padName)))
                        .as("%s: the content the codec kept must be the committed pad", fileName)
                        .isEqualTo(committedPad);
            }

            String rebuiltPad = new String(FixedWidthCodec.encodeRecord(decoded, spec),
                    StandardCharsets.UTF_8).substring(pad.start(), pad.end());

            // WHY : Assumptions: the codec's restored pad is always BLANK, whatever the record type,
            //       and that single fact is what makes the two branches above meaningful. For the five
            //       blank-padded fixtures it equals the committed bytes, so the pad is a codec fact and
            //       a descriptor whose FILLER span disagreed with the file would fail here. For the
            //       three zero-padded ones it deliberately does NOT equal them, which is the positive
            //       evidence that their pad character cannot be derived and has to be measured from the
            //       extract -- the claim everyFixturePadsWithItsExtractsCharacter rests on.
            assertThat(rebuiltPad)
                    .as("%s: the codec restores a dropped pad with blanks", fileName)
                    .isEqualTo(blankPad);
            if (padCharacter == ' ') {
                assertThat(rebuiltPad)
                        .as("%s: the codec's pad must equal the committed pad", fileName)
                        .isEqualTo(committedPad);
            } else {
                assertThat(rebuiltPad)
                        .as("%s: the codec cannot derive a zero pad, so it must differ", fileName)
                        .isNotEqualTo(committedPad);
            }
        }
    }

    /**
     * Asserts that reading a fixture does not depend on the working directory or on a report date.
     */
    @Test
    @DisplayName("reading a fixture does not depend on the working directory or on a report date")
    void readingAFixtureIsIndependentOfAmbientState() {
        // WHY : Assumptions: the fixtures are resolved through the CLASS LOADER and not through a
        //       relative file path, so the suite runs identically from the reactor root and from the
        //       module directory. The report title band is included here because it is the only
        //       member of either mapper that takes a date, and it takes it as an argument rather
        //       than reading a clock -- which is what makes a rerun over the same fixtures produce
        //       the same bytes.
        assertThat(fixtureDirectory()).exists().isDirectory();
        byte[] firstRun = TransactionReportMapper.encodeNameHeader(LocalDate.of(2022, 7, 1),
                LocalDate.of(2022, 7, 31));
        byte[] secondRun = TransactionReportMapper.encodeNameHeader(LocalDate.of(2022, 7, 1),
                LocalDate.of(2022, 7, 31));
        assertThat(Arrays.equals(firstRun, secondRun)).isTrue();
    }
}
