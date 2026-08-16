package com.carddemo.reporting.mapper;

import com.carddemo.common.money.Money;
import com.carddemo.common.time.TimestampFormatter;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Objects;

/**
 * Projects the reporting context's read-only rows into the JSON payloads its API returns, and
 * narrows every value whose exposure has to be narrowed.
 *
 * <h2>How this class differs from the three byte-exact mappers beside it</h2>
 *
 * <p>Three sibling types in this package assemble artifacts: {@code TransactionReportMapper}
 * builds the 133-column daily transaction report, {@code StatementTextMapper} the 80-byte
 * plain-text statement and {@code StatementHtmlMapper} the 100-byte hypertext statement. Each of
 * those places bytes at declared positions and renders every amount through a COBOL display and
 * edit regime. <b>This class does neither.</b> It emits JSON, it places no byte and it applies no
 * edit mask of any kind, so nothing in this file reaches for the edit-mask regimes declared
 * elsewhere in this package, for either band-descriptor holder, or for the shared fixed-width
 * record codec. Both representations are exact and neither is derived from the other; they are
 * simply different, and a reader who arrives here expecting a mask will not find one because none
 * belongs.
 *
 * <p>Assumptions: this class performs no rounding and no arithmetic, so AAP Rule T4 (arithmetic
 * order is preserved) raises no product-before-quotient
 * question here. It carries an amount at the scale it already holds. A caller must therefore not
 * pre-round on this class's behalf: rounding is a decision about a monetary result and belongs
 * where the result is computed, and a mapper that also rounded would change a value while claiming
 * to project it, invisibly. The mapped values are the scale-two members declared at L10 of
 * {@code app/cpy/CVTRA05Y.cpy} and L7 of {@code app/cpy/CVACT01Y.cpy}.
 *
 * <h2>Data exposure, which happens here and nowhere else in this module</h2>
 *
 * <p>Assumptions: narrowing a primary account number to its last four characters is this class's
 * obligation alone, and it applies only to the JSON surfaces. The reason is counter-intuitive
 * enough to state outright: no card number is emitted by any byte-exact artifact this package
 * produces. The report detail band at lines 15 to 31 of {@code app/cpy/CVTRA07Y.cpy} carries a
 * transaction identifier at L16, an account identifier at L18, a type code at L20 and its
 * description at L22, a category code at L24 and its description at L26, a source at L28 and an
 * amount at L30 -- eight facts, and no card number among them. In {@code app/cbl/CBSTM03A.CBL}
 * the card number appears only in roles that are not output: the table key declared at L227, the
 * read-key restore at L421 and the table populate at L827, alongside the control-break comparand
 * {@code WS-SAVE-CARD PIC X(16)} at L69. Masking inside one of the byte-exact mappers would
 * therefore protect nothing and would rewrite bytes the parity comparison expects untouched, so
 * it would break byte parity while looking prudent. That is the single most important scoping
 * fact about this file.
 *
 * <p>Assumptions: the card verification value is <b>suppressed</b>, which means absent. No payload
 * declared in this file has a component for it, so no response carries the key at all -- not
 * blanked, not masked, not present and empty. "Mask it" is the wrong answer for this one value,
 * because a masked field still tells a reader the value exists and still gives a serialiser
 * something to write; an absent component gives it nothing. The reporting context reads through
 * read-only cross-schema views under a role holding {@code SELECT} and nothing else, and no
 * projection it reads exposes that value either, so the absence is doubled up rather than
 * relied on singly. The complete transaction record rosters at L5-L18 of
 * {@code app/cpy/CVTRA05Y.cpy} and L20-L36 of {@code app/cpy/COSTM01.CPY} contain no such member.
 *
 * <p>Assumptions: the national identifier {@code CUST-SSN PIC 9(09)} at L17 of
 * {@code app/cpy/CVCUS01Y.cpy} and the government-issued identifier
 * {@code CUST-GOVT-ISSUED-ID PIC X(20)} at L18 of the same copybook are held encrypted at rest
 * and are returned masked. This class neither encrypts nor decrypts anything, and the boundary is
 * worth stating because the two concerns are easy to conflate: encryption is a storage property
 * owned by the context that owns the table, while this class decides only how much of a decrypted
 * value is allowed to reach a serialiser. It narrows; it does not protect at rest.
 *
 * <p>Assumptions: no sensitive value may appear in a log line, in a metric tag key, in a metric
 * tag value, in a meter name or in a meter description. Nothing in this file logs, meters or
 * traces, and that is deliberate rather than incidental -- a debug line or a tag added later is
 * exactly how an unmasked primary account number escapes a mapper that is otherwise careful,
 * because a tag looks like metadata rather than like data. Every refusal raised below names the
 * component that failed and never reproduces its value, for the same reason. The seven imports at
 * L3-L9 are limited to money, time, decimal, date and object utilities; none is a logging or
 * metering API.
 *
 * <h2>Money: the failure mode that does not announce itself</h2>
 *
 * <p>Assumptions: every money-bearing component and every money parameter in this file is typed
 * {@link Money}, and never the platform's arbitrary-precision decimal type. This is not a style
 * preference. The shared Jackson module binds its serialiser to the {@code Money} type itself, so
 * a component typed as the arbitrary-precision decimal instead <b>compiles, runs, and silently
 * emits a JSON number</b> where the contract requires a JSON string. Nothing fails, nothing warns,
 * and the payload looks correct to a reader. That is the exact failure this rule exists to
 * prevent: {@code MoneyModule} registers its serialiser and deserialiser specifically against
 * {@code Money.class} at L271-L272 of
 * {@code services/common-lib/src/main/java/com/carddemo/common/money/MoneyModule.java}.
 *
 * <p>Assumptions: money crosses the wire as a JSON <b>string</b>, which carries AAP Rule T3
 * (money never leaves fixed point) to its last hop. The views
 * hold {@code NUMERIC(p,2)}, this file holds a scale-two decimal rounded half-up, and the wire
 * holds text. A JSON number is parsed into IEEE-754 binary floating point by most clients, which
 * destroys exactness at the boundary the user actually sees, and the loss is silent because the
 * value still looks like money.
 *
 * <p>Assumptions: the two money magnitudes this context reads are one integer digit apart and must
 * never be unified into a single money scale. A transaction amount is {@code NUMERIC(11,2)},
 * from {@code TRAN-AMT PIC S9(09)V99} at L10 of {@code app/cpy/CVTRA05Y.cpy} and
 * {@code TRNX-AMT PIC S9(09)V99} at L29 of {@code app/cpy/COSTM01.CPY} -- nine integer digits. An
 * account balance is {@code NUMERIC(12,2)}, from {@code ACCT-CURR-BAL PIC S9(10)V99} at L7 of
 * {@code app/cpy/CVACT01Y.cpy} -- ten integer digits. Widening the amount to the balance's
 * magnitude would accept a value the baseline field cannot hold, and narrowing the balance to the
 * amount's would refuse one it does hold, so a single shared magnitude is wrong in one direction
 * whichever of the two is chosen. Each payload below therefore bounds its own amount against its
 * own picture. The same one-digit distinction explains a narrowing on the statement side, where
 * the baseline moves the ten-digit balance into a nine-digit edited field at L484 of
 * {@code app/cbl/CBSTM03A.CBL}; that narrowing is {@code StatementTextMapper}'s concern and not
 * this class's, but it is the same distinction.
 *
 * <h2>Timestamps: opaque, and always supplied by the caller</h2>
 *
 * <p>Assumptions: the 26-character timestamps are carried as opaque text. The baseline declares
 * them as {@code PIC X(26)} -- {@code TRAN-ORIG-TS} and {@code TRAN-PROC-TS} at L16 and L17 of
 * {@code app/cpy/CVTRA05Y.cpy}, {@code TRNX-ORIG-TS} and {@code TRNX-PROC-TS} at L34 and L35 of
 * {@code app/cpy/COSTM01.CPY} -- and a 26-character alphanumeric member carries no zone, so
 * attaching one would invent information the source record never held.
 *
 * <p>Assumptions: every timestamp this class emits is supplied by its caller, and no method here
 * reads a clock. {@link TimestampFormatter} exposes {@code TIMESTAMP_LENGTH},
 * {@code format(LocalDateTime)}, {@code formatNow(Clock)}, {@code normalize(LocalDateTime)},
 * {@code normalizeNow(Clock)}, {@code parse(String)}, {@code datePrefix(String)} and
 * {@code toLocalDate(String)} -- and deliberately no method taking no argument at all, so a
 * reader reaching for one gets a compilation failure rather than a value that cannot be pinned by
 * a test. Its pattern renders year, month and day, then a <b>space</b> rather than a letter
 * separator, then the time to six fractional digits, zone-free, in exactly 26 characters.
 *
 * <p>Assumptions: a timestamp whose final two characters are blank is a legitimate value in this
 * pipeline and is carried through unaltered. Lines 53 and 54 of {@code app/jcl/CREASTMT.JCL} sort
 * and reshape the transaction record with {@code SORT FIELDS=(263,16,CH,A,1,16,CH,A)} and
 * {@code OUTREC FIELDS=(1:263,16,17:1,262,279:279,50)}, and the 50-byte tail taken from input
 * position 279 covers the whole 26-character origination timestamp plus only the first 24
 * characters of the processing timestamp, leaving positions 329 to 350 of the 350-byte record
 * unpopulated. So {@code TRNX-PROC-TS} arrives with its last two characters blank by design. This
 * class preserves such a value verbatim and never normalises it into a rendered instant, because
 * substituting an instant would report a precision the record does not carry.
 *
 * <h2>Field-level discipline</h2>
 *
 * <p>Assumptions: {@code FILLER} is dropped from every payload here, and each omission is recorded
 * so that its removal is auditable rather than invisible, which is what AAP Rule T1 (Copybook is
 * normative) asks for. The dropped members are the
 * trailing padding of the stored records: {@code X(20)} at L18 of
 * {@code app/cpy/CVTRA05Y.cpy}, {@code X(20)} at L36 of {@code app/cpy/COSTM01.CPY},
 * {@code X(178)} at L17 of {@code app/cpy/CVACT01Y.cpy}, {@code X(14)} at L8 of
 * {@code app/cpy/CVACT03Y.cpy}, {@code X(08)} at L7 of {@code app/cpy/CVTRA03Y.cpy},
 * {@code X(04)} at L9 of {@code app/cpy/CVTRA04Y.cpy} and {@code X(168)} at L23 of
 * {@code app/cpy/CUSTREC.cpy}. The contrast that matters is with
 * {@code app/cpy/CVTRA07Y.cpy}, where the {@code FILLER} census is 22 and every one of the 22
 * carries a {@code VALUE}: there they are <b>content</b> -- headings, hyphen joiners at L21 and
 * L25, dot leaders -- and {@code TransactionReportMapper} has to emit them. Carrying the
 * stored-record rule across to the report band would delete text the report displays, and
 * carrying the band's rule back here would add components whose only content is padding.
 *
 * <p>Assumptions: the trim decision is made per field rather than by a blanket rule, and the
 * baseline settles each one. A field is carried at its declared width when a byte-exact band
 * emits it at exactly that width or when it is part of a key: the type code is declared
 * {@code PIC X(02)} at L6 of {@code app/cpy/CVTRA05Y.cpy} and emitted at exactly two characters
 * at L20 of {@code app/cpy/CVTRA07Y.cpy}, so its width is the contract. A field is trimmed when
 * the band re-declares it at some other width, which shows the stored width to be padding: the
 * descriptions are declared {@code PIC X(50)} at L6 of {@code app/cpy/CVTRA03Y.cpy} and L8 of
 * {@code app/cpy/CVTRA04Y.cpy}, yet the band re-declares them as {@code PIC X(15)} at L22 and
 * {@code PIC X(29)} at L26, so 50 is storage padding and not a wire contract. The shared record
 * codec's text branch takes the same position from the other side, exposing both a raw and a
 * trimmed reading of one field with the explicit instruction not to guess per call site; the same
 * discipline is what makes these decisions explicit here even though this class never calls that
 * codec.
 *
 * <p>Assumptions: trailing padding is removed from the end only and never from the start. A
 * character field of declared width is left-justified and padded on the right, so a blank at the
 * start of such a field is content rather than padding, and removing it would change a
 * user-visible string, which AAP Rule T8 (user-visible strings are verbatim) forbids. Any
 * user-visible text that reaches a payload here is carried
 * character-for-character: no space is normalised, no label is trimmed at its front and nothing
 * is re-cased. The evidence is the fifty-character description at
 * {@code app/cpy/CVTRA03Y.cpy} L6 being re-declared at fifteen characters in
 * {@code app/cpy/CVTRA07Y.cpy} L22.
 *
 * <p>Assumptions: a data name in this corpus does not identify a field on its own, so every
 * citation in this file names the copybook that declares it. The collisions are real.
 * {@code TRAN-TYPE-CD} and {@code TRAN-CAT-CD} are declared at L6 and L7 of
 * {@code app/cpy/CVTRA05Y.cpy} and again at L6 and L7 of {@code app/cpy/CVTRA04Y.cpy}.
 * {@code TRAN-CAT-KEY} is six characters at L5 of {@code app/cpy/CVTRA04Y.cpy} and seventeen at
 * L5 of {@code app/cpy/CVTRA01Y.cpy} -- one name, two widths, and a silent field shift for anyone
 * resolving it by name alone. The same two-character type code is called {@code TRAN-TYPE},
 * without the {@code -CD}, at L5 of {@code app/cpy/CVTRA03Y.cpy} but {@code TRAN-TYPE-CD} at L6 of
 * {@code app/cpy/CVTRA04Y.cpy}. The baseline itself disambiguates rather than trusting a name:
 * {@code app/cbl/CBTRN03C.cbl} qualifies with {@code OF TRAN-RECORD} at L365 and L367. A single
 * global field-name map is therefore impossible to build correctly, and this file does not
 * attempt one.
 *
 * <p>Assumptions: {@code app/cpy/CVTRA05Y.cpy} and {@code app/cpy/COSTM01.CPY} declare the same
 * fourteen named fields and both sum to 350 characters, but the key is reordered and the geometry
 * is provably different. {@code TRAN-RECORD} leads with {@code TRAN-ID PIC X(16)} at L5 and places
 * {@code TRAN-CARD-NUM PIC X(16)} at L15; {@code TRNX-RECORD} leads with a 32-character
 * {@code TRNX-KEY} at L21 composed of the card number at L22 followed by the identifier at L23.
 * The two are never treated as one another, which is why the report payload and the statement
 * payload below are separate types with their parameters in their own record's declaration order.
 *
 * <p>Assumptions: {@code app/cpy/CUSTREC.cpy} and {@code app/cpy/CVCUS01Y.cpy} are 26-line twins
 * with identical geometry that diverge in exactly two ways: the date of birth is
 * {@code CUST-DOB-YYYYMMDD} at L19 of the first and {@code CUST-DOB-YYYY-MM-DD} at L19 of the
 * second, and L6 to L22 of the first are indented with tab characters where the second uses
 * spaces. {@code app/cbl/CBSTM03A.CBL} copies {@code CUSTREC}, so that is the statement path's
 * provenance. Every citation to either one therefore names a field and its {@code PICTURE} clause
 * and never a column offset, because an offset read off a tab-indented source is a count of
 * rendered columns rather than of characters and would be wrong by however wide a tab was
 * displayed.
 *
 * <p>Assumptions: the customer identifier is declared twice over the same nine characters and the
 * two declarations disagree. {@code app/cbl/CBSTM03B.CBL} declares {@code FD-CUST-ID PIC X(09)}
 * while L5 of {@code app/cpy/CUSTREC.cpy} declares {@code CUST-ID PIC 9(09)}. Under
 * AAP Rule T1 (Copybook is normative), the numeric
 * declaration governs the domain: the value is nine digits and nothing else. It is still carried
 * as text at its declared width, because a numeric component would discard a leading zero the
 * declared width preserves.
 *
 * <p>Assumptions: none of the three baseline misspelling corrections that AAP Rule T1 (Copybook
 * is normative) names reaches this package. {@code ACCT-EXPIRAION-DATE} is declared at L11
 * of {@code app/cpy/CVACT01Y.cpy} and no payload here surfaces it;
 * {@code CARD-EXPIRAION-DATE} and {@code PA-MERCHANT-CATAGORY-CODE} belong to records this context
 * does not read at all. No component in this file is a renamed baseline field, and stating that
 * explicitly is what stops a downstream reader inventing a fourth rename by analogy.
 *
 * <h2>The report request surface</h2>
 *
 * <p>Assumptions: the report request contract is read off {@code app/cbl/CORPT00C.cbl}. The report
 * name is ten characters, {@code WS-REPORT-NAME PIC X(10) VALUE SPACES} at L58, and the three
 * values the program moves into it are {@code 'Monthly'} at L214, {@code 'Yearly'} at L240 and
 * {@code 'Custom'} at L433. The date form is ten characters too, and it is declared literally:
 * {@code WS-DATE-FORMAT PIC X(10) VALUE 'YYYY-MM-DD'} at L72. The program composes each of its two
 * dates from a group of the same ten characters -- a four-character year, a hyphen, a
 * two-character month, a hyphen and a two-character day at L60 to L65 for the start date and at
 * L66 to L71 for the end date -- which is why the ten-character form and not a group of parts is
 * what this file validates. Both strings are carried verbatim under AAP Rule T8 (user-visible
 * strings are verbatim).
 *
 * <p>Assumptions: {@code WS-TRAN-AMT PIC +99999999.99} at L77 of {@code app/cbl/CORPT00C.cbl} is a
 * 12-character regime with eight integer digits whose sign always prints and whose leading zeros
 * are preserved, because the picture uses the digit symbol rather than the zero-suppression
 * symbol. It is narrower than the report band's nine integer digits, and <b>this class does not
 * apply it</b> -- it emits an amount as a JSON string and applies no edit mask at all. The regime
 * is recorded here so that nobody wires it into a payload component on the strength of its being
 * declared in the very program the request shape comes from.
 *
 * <p>Assumptions: {@code JCL-RECORD PIC X(80) VALUE ' '} at L79 of {@code app/cbl/CORPT00C.cbl} is
 * the buffer the program writes a job stream into so a transient-data queue can submit it. In the
 * target that submission becomes the start of a state-machine execution, which is an action taken
 * on a request rather than a value carried in one, so it is not a payload component here and no
 * component of this file names an endpoint, a resource identifier or a managed service.
 *
 * <p>Trade-offs: the two halves of this file rest on unequal evidence, and both halves are stated
 * rather than the weaker one glossed. {@code app/cbl/CBTRN03C.cbl}, {@code app/cbl/CBSTM03A.CBL}
 * and {@code app/cbl/CBSTM03B.CBL} are batch programs among the ten that lines 40 to 46 of
 * {@code tests/README.md} establish as running standalone and fully automatable, so the shapes
 * derived from them can be checked against captured output. {@code app/cbl/CORPT00C.cbl} is an
 * online program with no such coverage: the same lines, and lines 83 to 85 of the same file,
 * record that the online programs cannot run end to end without a terminal-monitor runtime and
 * that only their extractable field-validation logic is unit-tested. The request shape derived
 * from it is therefore transcribed from declarations rather than confirmed against an artifact,
 * and it carries more risk than the emission shapes do. The compensating measure taken here is
 * that every one of its contracts -- the ten-character name, the ten-character date form, the two
 * hyphen positions -- is asserted at construction, so a transcription error surfaces as a refusal
 * instead of as a silently accepted value.
 *
 * <p>Assumptions: the graded return-code rubric the COBOL oracle uses, and the aggregate
 * warn-level result it treats as its passing state, belong to that suite alone and do not cross
 * into this module. This module's gate is binary: the build either has zero violations or it
 * fails. The Java documentation gate is the private-scope
 * {@code MissingJavadocMethod} configuration at L363-L366 of
 * {@code config/checkstyle/checkstyle.xml}.
 *
 * <h2>Why this class is written by hand, and over values</h2>
 *
 * <p>Alternatives Considered: MapStruct was evaluated and rejected, on two independent grounds
 * either of which would settle it. Its most recent published release is a beta, and this mapping
 * is not mechanical: it drops {@code FILLER} on seven records, narrows a primary account number
 * to four characters, suppresses a card verification value entirely, narrows two encrypted
 * identifiers and trims selectively field by field. Every one of those is a decision a reader
 * cannot recover from the code, so every one needs a justification at the mapping site -- and a
 * generated member has nowhere to hold one. The mapper-specific never-suppress rationale states
 * the same five non-mechanical behaviours at L271-L282 of
 * {@code config/checkstyle/suppressions.xml}.
 *
 * <p>Alternatives Considered: Lombok was evaluated and rejected, because its generated accessors
 * cannot carry the Javadoc user-specified Rule 1 (Explainability) requires, and the ruleset's
 * allowed-annotation list is explicitly empty at L366 of
 * {@code config/checkstyle/checkstyle.xml}, so no annotation excuses an undocumented member. A
 * Java 21 {@code record} with an explicit constructor gives the same brevity with members that
 * can be documented.
 *
 * <p>Alternatives Considered: the mapping methods were going to take the projection types from
 * {@code com.carddemo.reporting.domain} and return the API types from
 * {@code com.carddemo.reporting.dto}, and both were rejected in favour of values plus the payload
 * records declared below. Two concrete reasons. The card-ordered statement projection declares a
 * single constructor, taking no arguments and at protected visibility at L441 of
 * {@code services/reporting-service/src/main/java/com/carddemo/reporting/domain/StatementTransactionView.java},
 * so a mapper bound to that type cannot be exercised at all without the persistence provider or
 * reflection -- and the masking guarantee this class carries is precisely the thing that has to
 * be assertable in a plain unit test. And expressing the mapper over values leaves it a leaf with
 * no compile-time bond to either neighbouring package, so the arrow runs one way and neither
 * neighbour's shape can reach back into the narrowing rules. The payload records live here rather
 * than in the API package for the same reason, and they are named apart from that package's
 * request and response types so the two are never mistaken for one another.
 *
 * <p>Trade-offs: hand-written mapping is verbose, and the compromise accepted is exactly that
 * verbosity in exchange for a justification carried at every mapping site. Two costs come with
 * it. The parameter lists are long and several parameters share the character type, so a
 * transposed call would compile; the mitigation is that every parameter list is ordered exactly
 * as its owning copybook declares its fields, so a call site can be read against the copybook
 * line by line, and every declared width is asserted, so most transpositions raise a refusal
 * naming the component rather than populating the wrong one. The second cost is that a layout
 * change has to be carried through by hand, which is the price of having each decision auditable
 * where it takes effect. The widest call has thirteen arguments following L22-L35 of
 * {@code app/cpy/COSTM01.CPY}; the report-line call has eight following L16-L30 of
 * {@code app/cpy/CVTRA07Y.cpy}.
 *
 * <p>Assumptions: no page envelope, no cursor component and no ordinal position of any kind is
 * declared in this file. A keyset envelope is a shared-kernel contract and declaring a second one
 * here would give one contract two definitions; an ordinal cursor is refused outright, because it
 * loses and repeats rows once a concurrent insert shifts positions between two reads. The words
 * page, report page, page total, account total and grand total are domain vocabulary in this
 * corpus and name nothing of that kind: L50 to L54 of {@code app/cpy/CVTRA07Y.cpy} declares a
 * band headed with the literal {@code 'Page Total'} and gives it its own edit-masked figure at
 * L54.
 *
 * <p>Assumptions: everything under {@code app/} is reference material and remains byte-identical.
 * No statement in this file describes an edit to it. Where this module's behaviour differs from
 * the baseline's, the baseline does one thing and this module does another and the difference is
 * registered in {@code docs/architecture/cobol-to-service-traceability.md}, which owns that
 * register; this file cites an entry and never defines one. The documentation convention this
 * file follows is stated in {@code docs/CODE_DOCUMENTATION_STANDARD.md}, and where two sources
 * appear to disagree the order of precedence is user-specified Rule 1 (Explainability) first,
 * then {@code config/checkstyle/checkstyle.xml}, then that standard, and prose last.
 * The single-sourcing precedent is stated at L533-L535 of {@code tests/README.md}.
 */
public final class ReportingDtoMapper {

    /**
     * The number of characters a primary account number is declared with, which is sixteen.
     *
     * <p>Assumptions: sixteen is read off three independent declarations that agree, so the width
     * is a contract rather than a reading -- {@code XREF-CARD-NUM PIC X(16)} at L5 of
     * {@code app/cpy/CVACT03Y.cpy}, {@code TRAN-CARD-NUM PIC X(16)} at L15 of
     * {@code app/cpy/CVTRA05Y.cpy} and {@code TRNX-CARD-NUM PIC X(16)} at L22 of
     * {@code app/cpy/COSTM01.CPY}. The masked rendering keeps the same width, so a consumer
     * reading a masked value into a declared-width field is not handed something narrower than
     * the field.
     */
    public static final int CARD_NUMBER_WIDTH = 16;

    /**
     * The number of trailing characters a masked value keeps visible, which is four.
     *
     * <p>Trade-offs: four is the concession the migration plan states, and it is a compromise in
     * both directions at once. Fewer would leave an operator unable to match a payload to a
     * support call, which is the one job a masked identifier still has to do; more would weaken
     * the masking for no gain. It is declared once so that every masked value in this file agrees
     * and no call site can quietly choose its own number. The canonical sixteen-character
     * projection is twelve mask characters plus four retained characters at L167 and L218 of
     * {@code data-migration/sql/V1__reporting_views.sql}.
     */
    public static final int VISIBLE_TRAILING_CHARACTERS = 4;

    /**
     * The character a masked position is rendered with.
     *
     * <p>Assumptions: the asterisk is chosen because it is what the read-only projections this
     * context reads already emit, so a value that arrives masked and a value this file masks are
     * byte-identical and masking is idempotent. Choosing a different character would make the two
     * forms distinguishable and would let a consumer infer which side had done the narrowing,
     * which is information no consumer needs and a difference every consumer would have to
     * tolerate. The asterisk appears in the twelve-character prefix at L167 and L218 of
     * {@code data-migration/sql/V1__reporting_views.sql}.
     */
    public static final char MASK_CHARACTER = '*';

    /**
     * The number of characters a transaction identifier is declared with, which is sixteen.
     *
     * <p>Assumptions: sixteen is {@code TRAN-ID PIC X(16)} at L5 of
     * {@code app/cpy/CVTRA05Y.cpy}, corroborated by {@code TRNX-ID PIC X(16)} at L23 of
     * {@code app/cpy/COSTM01.CPY} and by the report band emitting it at
     * {@code TRAN-REPORT-TRANS-ID PIC X(16)} at L16 of {@code app/cpy/CVTRA07Y.cpy}. The band
     * emits it at exactly its declared width, which is what makes the width part of the contract
     * rather than storage padding.
     */
    public static final int TRANSACTION_ID_WIDTH = 16;

    /**
     * The number of characters an account identifier is declared with, which is eleven.
     *
     * <p>Assumptions: eleven is {@code ACCT-ID PIC 9(11)} at L5 of
     * {@code app/cpy/CVACT01Y.cpy} and {@code XREF-ACCT-ID PIC 9(11)} at L7 of
     * {@code app/cpy/CVACT03Y.cpy}. The report band declares the same value as characters --
     * {@code TRAN-REPORT-ACCOUNT-ID PIC X(11)} at L18 of {@code app/cpy/CVTRA07Y.cpy}, filled
     * from the cross-reference by {@code MOVE XREF-ACCT-ID TO TRAN-REPORT-ACCOUNT-ID} at L364 of
     * {@code app/cbl/CBTRN03C.cbl} -- so the character form is what an artifact carries and the
     * numeric form governs only the domain of digits.
     */
    public static final int ACCOUNT_ID_WIDTH = 11;

    /**
     * The number of characters a customer identifier is declared with, which is nine.
     *
     * <p>Assumptions: nine is {@code CUST-ID PIC 9(09)} at L5 of {@code app/cpy/CUSTREC.cpy} and
     * at L5 of {@code app/cpy/CVCUS01Y.cpy}. {@code app/cbl/CBSTM03B.CBL} declares the same nine
     * characters as {@code FD-CUST-ID PIC X(09)}, and AAP Rule T1 (Copybook is normative)
     * resolves the disagreement in the copybook's favour, so the domain is nine digits.
     */
    public static final int CUSTOMER_ID_WIDTH = 9;

    /**
     * The number of characters a transaction type code is declared with, which is two.
     *
     * <p>Assumptions: two is {@code TRAN-TYPE-CD PIC X(02)} at L6 of
     * {@code app/cpy/CVTRA05Y.cpy}, at L25 of {@code app/cpy/COSTM01.CPY} as
     * {@code TRNX-TYPE-CD} and at L6 of {@code app/cpy/CVTRA04Y.cpy}; the same two characters are
     * named {@code TRAN-TYPE}, with no {@code -CD}, at L5 of {@code app/cpy/CVTRA03Y.cpy}. The
     * band emits it at exactly two characters at L20 of {@code app/cpy/CVTRA07Y.cpy}.
     */
    public static final int TYPE_CODE_WIDTH = 2;

    /**
     * The number of characters a transaction category code is declared with, which is four.
     *
     * <p>Assumptions: four is {@code TRAN-CAT-CD PIC 9(04)} at L7 of
     * {@code app/cpy/CVTRA05Y.cpy}, at L26 of {@code app/cpy/COSTM01.CPY} as
     * {@code TRNX-CAT-CD} and at L7 of {@code app/cpy/CVTRA04Y.cpy}. It is carried as text rather
     * than as a number because the band emits it through the unsigned four-digit form at L24 of
     * {@code app/cpy/CVTRA07Y.cpy}, whose picture uses the digit symbol and therefore preserves a
     * leading zero -- in contrast with the zero-suppressing picture at L30 of the same copybook.
     * A numeric component would render the code {@code 0001} as {@code 1} and break every lookup
     * on the value.
     */
    public static final int CATEGORY_CODE_WIDTH = 4;

    /**
     * The number of characters a transaction source is declared with, which is ten.
     *
     * <p>Assumptions: ten is {@code TRAN-SOURCE PIC X(10)} at L8 of
     * {@code app/cpy/CVTRA05Y.cpy} and {@code TRNX-SOURCE PIC X(10)} at L27 of
     * {@code app/cpy/COSTM01.CPY}, and the band emits it at exactly ten characters at L28 of
     * {@code app/cpy/CVTRA07Y.cpy}. Because the band re-declares no narrower width for it, the
     * declared width is the contract and the value is not trimmed.
     */
    public static final int SOURCE_WIDTH = 10;

    /**
     * The number of digit positions a merchant identifier is declared with, which is nine.
     *
     * <p>Assumptions: nine is {@code TRAN-MERCHANT-ID PIC 9(09)} at L11 of
     * {@code app/cpy/CVTRA05Y.cpy} and {@code TRNX-MERCHANT-ID PIC 9(09)} at L30 of
     * {@code app/cpy/COSTM01.CPY}. The value is a magnitude rather than a code, so the payload
     * carries it as a whole-number value and validates the unsigned nine-digit domain.
     */
    public static final int MERCHANT_IDENTIFIER_DIGITS = 9;

    /**
     * The maximum width of a merchant name or city, which is fifty characters.
     *
     * <p>Assumptions: fifty is {@code TRAN-MERCHANT-NAME PIC X(50)} at L12 and
     * {@code TRAN-MERCHANT-CITY PIC X(50)} at L13 of {@code app/cpy/CVTRA05Y.cpy}, corroborated
     * by the statement-path declarations at L31 and L32 of {@code app/cpy/COSTM01.CPY}. Both
     * values are descriptive text, so the width bounds a trailing-space-trimmed value.
     */
    public static final int MERCHANT_TEXT_WIDTH = 50;

    /**
     * The declared width of a merchant postal code, which is ten characters.
     *
     * <p>Assumptions: ten is {@code TRAN-MERCHANT-ZIP PIC X(10)} at L14 of
     * {@code app/cpy/CVTRA05Y.cpy} and {@code TRNX-MERCHANT-ZIP PIC X(10)} at L33 of
     * {@code app/cpy/COSTM01.CPY}. The postal code is a code rather than prose, so its complete
     * declared width is retained, including a significant leading zero.
     */
    public static final int MERCHANT_POSTAL_CODE_WIDTH = 10;

    /**
     * The number of characters a transaction description is declared with, which is one hundred.
     *
     * <p>Assumptions: one hundred is {@code TRAN-DESC PIC X(100)} at L9 of
     * {@code app/cpy/CVTRA05Y.cpy} and {@code TRNX-DESC PIC X(100)} at L28 of
     * {@code app/cpy/COSTM01.CPY}. It bounds a trimmed value rather than fixing its length,
     * because a description is padded to its declared width in storage and the padding is not
     * content.
     */
    public static final int DESCRIPTION_WIDTH = 100;

    /**
     * The number of characters a reference-data description is declared with, which is fifty.
     *
     * <p>Assumptions: fifty is {@code TRAN-TYPE-DESC PIC X(50)} at L6 of
     * {@code app/cpy/CVTRA03Y.cpy} and {@code TRAN-CAT-TYPE-DESC PIC X(50)} at L8 of
     * {@code app/cpy/CVTRA04Y.cpy}. The report band re-declares the two at {@code PIC X(15)} at
     * L22 and {@code PIC X(29)} at L26 of {@code app/cpy/CVTRA07Y.cpy}, which is the evidence that
     * fifty is storage padding rather than a wire contract, so a value is trimmed here and this
     * width only bounds it. The narrowing to fifteen and to twenty-nine characters belongs to
     * {@code TransactionReportMapper}, which assembles the band; this class hands it the trimmed
     * value and truncates nothing.
     */
    public static final int REFERENCE_DESCRIPTION_WIDTH = 50;

    /**
     * The number of characters a customer name part is declared with, which is twenty-five.
     *
     * <p>Assumptions: twenty-five is the width of {@code CUST-FIRST-NAME},
     * {@code CUST-MIDDLE-NAME} and {@code CUST-LAST-NAME}, each {@code PIC X(25)} at L6, L7 and L8
     * of {@code app/cpy/CUSTREC.cpy} -- the copybook {@code app/cbl/CBSTM03A.CBL} copies, and
     * therefore the statement path's provenance. A name is padded to that width in storage, so
     * the width bounds a trimmed value.
     */
    public static final int NAME_PART_WIDTH = 25;

    /**
     * The number of characters the national identifier is declared with, which is nine.
     *
     * <p>Assumptions: nine is {@code CUST-SSN PIC 9(09)} at L17 of
     * {@code app/cpy/CVCUS01Y.cpy}. The value is held encrypted at rest by the context that owns
     * the record and is returned masked from here, so this width is the width of the <b>masked</b>
     * rendering as well as of the source field.
     */
    public static final int NATIONAL_IDENTIFIER_WIDTH = 9;

    /**
     * The number of characters the government-issued identifier is declared with, which is twenty.
     *
     * <p>Assumptions: twenty is {@code CUST-GOVT-ISSUED-ID PIC X(20)} at L18 of
     * {@code app/cpy/CVCUS01Y.cpy}. As with the national identifier, the value is held encrypted
     * at rest elsewhere and is returned masked from here at the same declared width.
     */
    public static final int GOVERNMENT_IDENTIFIER_WIDTH = 20;

    /**
     * The greatest number of characters a report name may carry, which is ten.
     *
     * <p>Assumptions: ten is {@code WS-REPORT-NAME PIC X(10) VALUE SPACES} at L58 of
     * {@code app/cbl/CORPT00C.cbl}. It bounds rather than fixes the length, because the three
     * values that program moves into the field are all shorter than the field --
     * {@code 'Monthly'} at L214, {@code 'Yearly'} at L240 and {@code 'Custom'} at L433 -- so
     * demanding exactly ten characters would refuse every name the baseline actually produces.
     */
    public static final int REPORT_NAME_WIDTH = 10;

    /**
     * The number of characters a reporting date carries, which is ten.
     *
     * <p>Assumptions: ten is both the declared width of {@code WS-DATE-FORMAT PIC X(10)} at L72 of
     * {@code app/cbl/CORPT00C.cbl} and the sum of the group that program composes a date from at
     * L60 to L65 -- a four-character year, a hyphen, a two-character month, a hyphen and a
     * two-character day. Two independent readings of the same program give ten, so the width is
     * exact rather than a maximum.
     */
    public static final int DATE_WIDTH = 10;

    /**
     * The date form a reporting date is carried in, reproduced character-for-character.
     *
     * <p>Assumptions: this is the literal {@code WS-DATE-FORMAT PIC X(10) VALUE 'YYYY-MM-DD'}
     * declares at L72 of {@code app/cbl/CORPT00C.cbl}, carried verbatim under AAP Rule T8
     * (user-visible strings are verbatim). It is exposed as a constant
     * rather than only enforced, so a client can be told the form it must send in the same
     * characters the baseline states it in.
     */
    public static final String DATE_FORM = "YYYY-MM-DD";

    /**
     * The number of integer digit positions a transaction amount is declared with, which is nine.
     *
     * <p>Assumptions: nine comes from {@code TRAN-AMT PIC S9(09)V99} at L10 of
     * {@code app/cpy/CVTRA05Y.cpy} and {@code TRNX-AMT PIC S9(09)V99} at L29 of
     * {@code app/cpy/COSTM01.CPY}, which the views hold as {@code NUMERIC(11,2)}. It is one digit
     * narrower than an account balance and the two are never unified, so this bound is stated
     * separately from that one rather than derived from a shared money width.
     */
    public static final int TRANSACTION_AMOUNT_INTEGER_DIGITS = 9;

    /**
     * The number of integer digit positions an account balance is declared with, which is ten.
     *
     * <p>Assumptions: ten comes from {@code ACCT-CURR-BAL PIC S9(10)V99} at L7 of
     * {@code app/cpy/CVACT01Y.cpy}, which the views hold as {@code NUMERIC(12,2)}. The shared
     * money type's own domain bound happens to be the same ten-digit magnitude, which corroborates
     * the reading; the bound is nonetheless computed from this picture here, so that the two
     * magnitudes this file enforces each trace to their own declaration.
     */
    public static final int ACCOUNT_BALANCE_INTEGER_DIGITS = 10;

    // WHY : Assumptions: the two magnitude limits are computed from the integer-digit counts above
    //       rather than written as decimal literals, so each traces to exactly one declaration --
    //       nine digits to TRAN-AMT PIC S9(09)V99 at app/cpy/CVTRA05Y.cpy L10 and ten to
    //       ACCT-CURR-BAL PIC S9(10)V99 at app/cpy/CVACT01Y.cpy L7. Two literals would be a second
    //       executable statement of a width the constants above already state, and the failure mode
    //       of two positions for one width is that a correction reaches one of them.
    private static final BigDecimal MAX_TRANSACTION_AMOUNT =
            magnitudeLimit(TRANSACTION_AMOUNT_INTEGER_DIGITS);

    // WHY : Assumptions: this limit is deliberately one integer digit wider than the one above and
    //       must stay so. Collapsing the pair into a single constant is the specific mistake the
    //       one-digit gap between app/cpy/CVACT01Y.cpy L7 and app/cpy/CVTRA05Y.cpy L10 makes
    //       possible, and it is wrong in one direction whichever of the two is kept.
    private static final BigDecimal MAX_ACCOUNT_BALANCE =
            magnitudeLimit(ACCOUNT_BALANCE_INTEGER_DIGITS);

    // WHY : Assumptions: nine digit positions is the unsigned domain declared for the merchant
    //       identifier at app/cpy/CVTRA05Y.cpy L11 and app/cpy/COSTM01.CPY L30. The bound is kept
    //       separate from the monetary limits because it carries no fractional positions.
    private static final long MAX_MERCHANT_IDENTIFIER = 999_999_999L;

    // WHY : Assumptions: the two positions a reporting date carries a hyphen in, taken from the
    //       group app/cbl/CORPT00C.cbl composes a date from at L60 to L65: a four-character year
    //       ends at index 3, so index 4 is the first hyphen, and a two-character month ends at
    //       index 6, so index 7 is the second. They are named rather than inlined because a
    //       validator reading 4 and 7 as bare numbers states no provenance for them.
    private static final int FIRST_HYPHEN_INDEX = 4;

    // WHY : Assumptions: the second hyphen position, derived from the same group at
    //       app/cbl/CORPT00C.cbl L60 to L65 as the first one above.
    private static final int SECOND_HYPHEN_INDEX = 7;

    // WHY : Assumptions: the separator character the date form at app/cbl/CORPT00C.cbl L72 places
    //       between its parts, declared there as FILLER PIC X(01) VALUE '-' at L62 and L64. It is
    //       the plain ASCII hyphen-minus and not any typographic dash, because the baseline literal
    //       is a single character in a fixed-width field.
    private static final char DATE_SEPARATOR = '-';

    /**
     * Refuses instantiation, because this class holds no state and offers only static operations.
     *
     * <p>Alternatives Considered: making this an injectable bean with instance methods was
     * evaluated and rejected. Every operation here is a pure function of its arguments, so an
     * instance would carry nothing, and injecting one would add a construction and a lifetime to
     * manage in exchange for no seam worth having -- there is nothing to substitute in a test,
     * because there is no collaborator to stand in for. A private constructor is declared rather
     * than left implicit so that the intent is stated where a reader looks for a constructor. The
     * public API consists of 8 operations, all static and each receiving every varying value as
     * an argument.
     *
     * <p>Assumptions: the constructor is documented in full even though it is private and empty,
     * because user-specified Rule 1 (Explainability) requires a docstring on every function
     * without qualifying by visibility, and the ruleset that mechanises it audits constructors at
     * private scope through {@code MissingJavadocMethod} at L363-L366 of
     * {@code config/checkstyle/checkstyle.xml}.
     *
     * @throws AssertionError always, so that reflection cannot produce an instance of a type whose
     *     whole contract is that instances are meaningless
     */
    private ReportingDtoMapper() {
        throw new AssertionError("ReportingDtoMapper is a static holder and must not be instantiated");
    }

    /**
     * Carries the eight semantic values of one transaction-report detail line as JSON data.
     *
     * <p>Assumptions: component order follows the detail band at L15 to L31 of
     * {@code app/cpy/CVTRA07Y.cpy}: transaction identifier at L16, account identifier at L18,
     * type code and description at L20 and L22, category code and description at L24 and L26,
     * source at L28 and amount at L30. The two hyphen joiners and the amount edit mask remain
     * byte-rendering concerns, so this payload carries plain values and no line image.
     *
     * @param transactionId the declared-width sixteen-character transaction identifier from
     *     {@code TRAN-REPORT-TRANS-ID PIC X(16)} at L16
     * @param accountId the declared-width eleven-character account identifier from
     *     {@code TRAN-REPORT-ACCOUNT-ID PIC X(11)} at L18
     * @param typeCode the declared-width two-character transaction type code from
     *     {@code TRAN-REPORT-TYPE-CD PIC X(02)} at L20
     * @param typeDescription the transaction type description, trailing storage padding removed,
     *     bounded by the fifty characters declared at L6 of {@code app/cpy/CVTRA03Y.cpy}
     * @param categoryCode the declared-width four-character category code from
     *     {@code TRAN-REPORT-CAT-CD PIC 9(04)} at L24
     * @param categoryDescription the category description, trailing storage padding removed,
     *     bounded by the fifty characters declared at L8 of {@code app/cpy/CVTRA04Y.cpy}
     * @param source the declared-width ten-character source from
     *     {@code TRAN-REPORT-SOURCE PIC X(10)} at L28
     * @param amount the exact transaction amount whose nine integer positions come from
     *     {@code TRAN-AMT PIC S9(09)V99} at L10 of {@code app/cpy/CVTRA05Y.cpy}
     */
    public record ReportTransactionPayload(
            String transactionId,
            String accountId,
            String typeCode,
            String typeDescription,
            String categoryCode,
            String categoryDescription,
            String source,
            Money amount) {

        /**
         * Validates and narrows every report-line component at construction.
         *
         * @param transactionId the transaction identifier, which must contain exactly sixteen
         *     characters
         * @param accountId the account identifier, which must contain exactly eleven characters
         * @param typeCode the transaction type code, which must contain exactly two characters
         * @param typeDescription the type description, which may carry trailing storage padding
         *     but must not exceed fifty characters
         * @param categoryCode the category code, which must contain exactly four characters
         * @param categoryDescription the category description, which may carry trailing storage
         *     padding but must not exceed fifty characters
         * @param source the transaction source, which must contain exactly ten characters
         * @param amount the transaction amount, which must fit nine integer positions and two
         *     fractional positions
         * @throws NullPointerException if any component is {@code null}
         * @throws IllegalArgumentException if a declared-width component has the wrong width, descriptive
         *     text exceeds its declared width or the amount exceeds its declared magnitude
         */
        public ReportTransactionPayload {
            // WHY : Assumptions: no primary account number is accepted here because the complete
            //       detail roster at app/cpy/CVTRA07Y.cpy L15-L31 has no such member. Adding one
            //       merely to mask it would widen the JSON contract and would invent data the
            //       report line itself never emits.
            transactionId =
                    declaredWidthCode(transactionId, TRANSACTION_ID_WIDTH, "transactionId");
            accountId = declaredWidthDigits(accountId, ACCOUNT_ID_WIDTH, "accountId");
            typeCode = declaredWidthCode(typeCode, TYPE_CODE_WIDTH, "typeCode");

            // WHY : Assumptions: the stored type description is PIC X(50) at
            //       app/cpy/CVTRA03Y.cpy L6 but the report band re-declares it as PIC X(15) at
            //       app/cpy/CVTRA07Y.cpy L22, proving that trailing positions in the stored field
            //       are padding rather than user-visible content.
            typeDescription =
                    descriptiveText(
                            typeDescription, REFERENCE_DESCRIPTION_WIDTH, "typeDescription");
            categoryCode =
                    declaredWidthDigits(categoryCode, CATEGORY_CODE_WIDTH, "categoryCode");

            // WHY : Assumptions: the stored category description is PIC X(50) at
            //       app/cpy/CVTRA04Y.cpy L8 but the report band re-declares it as PIC X(29) at
            //       app/cpy/CVTRA07Y.cpy L26, so trailing spaces are storage padding and leading
            //       spaces remain content under AAP Rule T8 (user-visible strings are verbatim).
            categoryDescription =
                    descriptiveText(
                            categoryDescription,
                            REFERENCE_DESCRIPTION_WIDTH,
                            "categoryDescription");
            source = declaredWidthCode(source, SOURCE_WIDTH, "source");
            amount =
                    requireMagnitude(
                            amount, MAX_TRANSACTION_AMOUNT, "amount", "nine integer digits");

            // WHY : Assumptions: the 22 FILLER declarations in app/cpy/CVTRA07Y.cpy all carry a
            //       VALUE and are therefore byte-record content, unlike the X(20) storage padding
            //       at app/cpy/CVTRA05Y.cpy L18. Neither becomes a JSON component: the former is
            //       emitted by the byte-exact report mapper and the latter is discarded here.
        }
    
        /**
         * Renders the line's identity and classification, WITHOUT the account or the amount.
         *
         * <p>Purpose. The account identifier and the amount are withheld by
         * {@code docs/architecture/observability.md} L1093 to L1112. This is the mapper's input shape for a
         * report line, so it is stringified on the mapping path -- which runs once per line of every report
         * -- and the published line response it becomes makes the same two omissions for the same
         * reasons.</p>
         *
         * <p>Assumptions: the two reference descriptions are kept beside their codes. They are seeded
         * reference text rather than anything a submitter authored, so they classify a line without
         * describing a transaction.</p>
         *
         * @return a rendering naming the transaction identifier, the type and category codes with their
         *     descriptions and the source, with the account identifier and the amount omitted; never
         *     {@code null}
         */
        @Override
        public String toString() {
            return "ReportTransactionPayload[transactionId=" + this.transactionId
                    + ", typeCode=" + this.typeCode
                    + ", typeDescription=" + this.typeDescription
                    + ", categoryCode=" + this.categoryCode
                    + ", categoryDescription=" + this.categoryDescription
                    + ", source=" + this.source + ']';
        }
}

    /**
     * Carries one statement-ordered transaction with exposure narrowed for JSON.
     *
     * <p>Assumptions: component order follows {@code TRNX-RECORD} at L20 to L36 of
     * {@code app/cpy/COSTM01.CPY}, including its 32-character key with card number first and
     * transaction identifier second. The database-only card grouping fingerprint is deliberately
     * absent because it orders rows inside the service and is not client data.
     *
     * @param cardNumber the sixteen-character primary account number narrowed to twelve mask
     *     characters and its last four significant characters
     * @param transactionId the declared-width sixteen-character transaction identifier
     * @param typeCode the declared-width two-character transaction type code
     * @param categoryCode the declared-width four-character transaction category code
     * @param source the declared-width ten-character transaction source
     * @param description the transaction description with trailing storage padding removed
     * @param amount the exact transaction amount, limited to nine integer positions
     * @param merchantId the unsigned merchant identifier, limited to nine digits
     * @param merchantName the merchant name with trailing storage padding removed
     * @param merchantCity the merchant city with trailing storage padding removed
     * @param merchantPostalCode the declared-width ten-character merchant postal code
     * @param originatingTimestamp the caller-supplied opaque 26-character origination timestamp,
     *     or {@code null} when the source row has no calendar value
     * @param processingTimestamp the caller-supplied opaque 26-character processing timestamp,
     *     or {@code null} when the source row has no calendar value
     */
    public record StatementTransactionPayload(
            String cardNumber,
            String transactionId,
            String typeCode,
            String categoryCode,
            String source,
            String description,
            Money amount,
            Long merchantId,
            String merchantName,
            String merchantCity,
            String merchantPostalCode,
            String originatingTimestamp,
            String processingTimestamp) {

        /**
         * Validates, trims and narrows a statement transaction at construction.
         *
         * @param cardNumber the primary account number, already narrowed or in its full declared
         *     sixteen-character form
         * @param transactionId the transaction identifier, exactly sixteen characters
         * @param typeCode the transaction type code, exactly two characters
         * @param categoryCode the transaction category code, exactly four characters
         * @param source the transaction source, exactly ten characters
         * @param description the description, at most one hundred characters before trailing
         *     storage padding is removed
         * @param amount the transaction amount, within nine integer positions at scale two
         * @param merchantId the non-negative merchant identifier, no greater than nine digits
         * @param merchantName the merchant name, at most fifty characters before trailing storage
         *     padding is removed
         * @param merchantCity the merchant city, at most fifty characters before trailing storage
         *     padding is removed
         * @param merchantPostalCode the merchant postal code, exactly ten characters
         * @param originatingTimestamp the opaque origination timestamp, exactly 26 characters or
         *     {@code null}
         * @param processingTimestamp the opaque processing timestamp, exactly 26 characters or
         *     {@code null}
         * @throws NullPointerException if a required component is {@code null}
         * @throws IllegalArgumentException if a width or magnitude is outside its declared
         *     contract
         */
        public StatementTransactionPayload {
            // WHY : Assumptions: exposure is narrowed here because TRNX-CARD-NUM is PIC X(16) at
            //       app/cpy/COSTM01.CPY L22 while no byte-exact statement band emits it;
            //       app/cbl/CBSTM03A.CBL uses the value only as the L227 table key, the L421
            //       read-key restore, the L827 table population and the L69 control-break
            //       comparand. Applying the same narrowing to a byte record would change parity.
            cardNumber = maskTrailing(cardNumber, CARD_NUMBER_WIDTH, "cardNumber");
            transactionId =
                    declaredWidthCode(transactionId, TRANSACTION_ID_WIDTH, "transactionId");
            typeCode = declaredWidthCode(typeCode, TYPE_CODE_WIDTH, "typeCode");
            categoryCode =
                    declaredWidthDigits(categoryCode, CATEGORY_CODE_WIDTH, "categoryCode");
            source = declaredWidthCode(source, SOURCE_WIDTH, "source");

            // WHY : Assumptions: TRNX-DESC is PIC X(100) at app/cpy/COSTM01.CPY L28 and the view
            //       presents it as descriptive variable-length text, so right-side spaces are
            //       padding while a leading space remains user-visible content.
            description = descriptiveText(description, DESCRIPTION_WIDTH, "description");
            amount =
                    requireMagnitude(
                            amount, MAX_TRANSACTION_AMOUNT, "amount", "nine integer digits");
            merchantId = requireMerchantIdentifier(merchantId);

            // WHY : Assumptions: TRNX-MERCHANT-NAME is PIC X(50) at
            //       app/cpy/COSTM01.CPY L31 and is descriptive text, so only trailing storage
            //       spaces are removed and the declared width remains an upper bound.
            merchantName =
                    descriptiveText(merchantName, MERCHANT_TEXT_WIDTH, "merchantName");

            // WHY : Assumptions: TRNX-MERCHANT-CITY is PIC X(50) at
            //       app/cpy/COSTM01.CPY L32 and follows the same descriptive-text policy as the
            //       merchant name, independently at this mapping site.
            merchantCity =
                    descriptiveText(merchantCity, MERCHANT_TEXT_WIDTH, "merchantCity");
            merchantPostalCode =
                    declaredWidthCode(
                            merchantPostalCode,
                            MERCHANT_POSTAL_CODE_WIDTH,
                            "merchantPostalCode");
            originatingTimestamp =
                    opaqueTimestamp(originatingTimestamp, "originatingTimestamp");

            // WHY : Assumptions: app/jcl/CREASTMT.JCL L53-L54 copies only 50 bytes beginning at
            //       the origination timestamp, so the 26-character processing member receives
            //       only its first 24 characters and two trailing blanks. Width validation
            //       preserves that 24-of-26 artifact and never substitutes a derived instant.
            processingTimestamp =
                    opaqueTimestamp(processingTimestamp, "processingTimestamp");

            // WHY : Assumptions: the X(20) FILLER at app/cpy/COSTM01.CPY L36 is record padding
            //       and has no component. This is intentionally unlike app/cpy/CVTRA07Y.cpy,
            //       where all 22 FILLER declarations carry a VALUE and belong to byte rendering.
            // WHY : Assumptions: the card verification value is suppressed, meaning absent rather
            //       than blanked or masked. COSTM01.CPY declares no such member in its 350 bytes,
            //       so neither this constructor nor this record offers a parameter for one.
        }
    
        /**
         * Renders the identity, the codes and the two timestamps, and nothing else.
         *
         * <p>Purpose. Seven of the thirteen components are withheld by
         * {@code docs/architecture/observability.md} L1093 to L1112 or by the reasoning recorded for the
         * published shapes: the card number, the amount, the description as submitter-authored free text,
         * and the four merchant components as one unit. The generated rendering printed a card number
         * beside a sum and a merchant, once per transaction on a statement.</p>
         *
         * <p>Assumptions: the card number is omitted rather than masked because this is a mapper input
         * carrying the STORED sixteen digits, not the masked form the published statement carries. Masking
         * here would put a second masking rule outside the mapper method that owns it in this context,
         * which is what that rule's first clause forbids.</p>
         *
         * <p>Trade-offs: both timestamps are kept, which is more than the published shape keeps, and
         * deliberately so: a statement's transactions are selected by processing date and ordered by
         * origination, so a selection or ordering fault in this mapping is diagnosed from exactly those two
         * values.</p>
         *
         * @return a rendering naming the transaction identifier, the type and category codes, the source and
         *     the two timestamps, with the card number, the amount, the description and all four merchant
         *     components omitted; never {@code null}
         */
        @Override
        public String toString() {
            return "StatementTransactionPayload[transactionId=" + this.transactionId
                    + ", typeCode=" + this.typeCode
                    + ", categoryCode=" + this.categoryCode
                    + ", source=" + this.source
                    + ", originatingTimestamp=" + this.originatingTimestamp
                    + ", processingTimestamp=" + this.processingTimestamp + ']';
        }
}

    /**
     * Carries the identity and balance values that head a statement JSON response.
     *
     * <p>Assumptions: this shape joins the three identifiers from {@code app/cpy/CVACT03Y.cpy},
     * the three name parts and two protected identifiers from {@code app/cpy/CUSTREC.cpy}, and
     * the current balance from {@code app/cpy/CVACT01Y.cpy}. Each source keeps its own geometry;
     * this record is a projection of named values and never a concatenated record image. The
     * cited members are at L5-L7, L6-L8 and L7 respectively.
     *
     * @param cardNumber the sixteen-character primary account number narrowed to its last four
     *     significant characters
     * @param customerId the declared-width nine-character customer identifier
     * @param accountId the declared-width eleven-character account identifier
     * @param firstName the first name with trailing storage padding removed
     * @param middleName the middle name with trailing storage padding removed
     * @param lastName the last name with trailing storage padding removed
     * @param nationalIdentifier the nine-character national identifier narrowed to its last four
     *     significant characters
     * @param governmentIssuedIdentifier the twenty-character government-issued identifier
     *     narrowed to its last four significant characters
     * @param currentBalance the exact current balance, limited to ten integer positions
     */
    public record StatementSummaryPayload(
            String cardNumber,
            String customerId,
            String accountId,
            String firstName,
            String middleName,
            String lastName,
            String nationalIdentifier,
            String governmentIssuedIdentifier,
            Money currentBalance) {

        /**
         * Validates, trims and narrows a statement summary at construction.
         *
         * @param cardNumber the primary account number, already narrowed or in its full declared
         *     sixteen-character form
         * @param customerId the customer identifier, exactly nine characters
         * @param accountId the account identifier, exactly eleven characters
         * @param firstName the first name, at most twenty-five characters before trailing storage
         *     padding is removed
         * @param middleName the middle name, at most twenty-five characters before trailing
         *     storage padding is removed
         * @param lastName the last name, at most twenty-five characters before trailing storage
         *     padding is removed
         * @param nationalIdentifier the national identifier, in its full or already narrowed
         *     nine-character form
         * @param governmentIssuedIdentifier the government-issued identifier, in its full or
         *     already narrowed twenty-character form
         * @param currentBalance the current account balance, within ten integer positions at
         *     scale two
         * @throws NullPointerException if any component is {@code null}
         * @throws IllegalArgumentException if a width or magnitude is outside its declared
         *     contract
         */
        public StatementSummaryPayload {
            // WHY : Assumptions: XREF-CARD-NUM is PIC X(16) at app/cpy/CVACT03Y.cpy L5, so the
            //       full value exists upstream and must be narrowed before it becomes JSON. The
            //       same last-four operation is idempotent for the projection's already-masked
            //       sixteen-character form, which prevents a later read from widening exposure.
            cardNumber = maskTrailing(cardNumber, CARD_NUMBER_WIDTH, "cardNumber");
            customerId = declaredWidthDigits(customerId, CUSTOMER_ID_WIDTH, "customerId");
            accountId = declaredWidthDigits(accountId, ACCOUNT_ID_WIDTH, "accountId");

            // WHY : Assumptions: CUST-FIRST-NAME is PIC X(25) at app/cpy/CUSTREC.cpy L6 and is
            //       descriptive text, so its right-side spaces are padding while any leading
            //       space is retained under AAP Rule T8 (user-visible strings are verbatim).
            firstName = descriptiveText(firstName, NAME_PART_WIDTH, "firstName");

            // WHY : Assumptions: CUST-MIDDLE-NAME is PIC X(25) at app/cpy/CUSTREC.cpy L7 and is
            //       evaluated independently because a blank middle name is valid content after
            //       its trailing storage padding has been removed.
            middleName = descriptiveText(middleName, NAME_PART_WIDTH, "middleName");

            // WHY : Assumptions: CUST-LAST-NAME is PIC X(25) at app/cpy/CUSTREC.cpy L8 and uses
            //       the same trailing-only policy, stated here so the choice is local to the
            //       field rather than inferred from the two name parts above.
            lastName = descriptiveText(lastName, NAME_PART_WIDTH, "lastName");

            // WHY : Assumptions: CUST-SSN is PIC 9(09) at app/cpy/CVCUS01Y.cpy L17. The owning
            //       context holds it encrypted, while this class only narrows the value admitted
            //       to serialisation; encryption and decryption do not occur in this mapper.
            nationalIdentifier =
                    maskTrailing(
                            nationalIdentifier,
                            NATIONAL_IDENTIFIER_WIDTH,
                            "nationalIdentifier");

            // WHY : Assumptions: CUST-GOVT-ISSUED-ID is PIC X(20) at
            //       app/cpy/CVCUS01Y.cpy L18 and crosses the same exposure boundary as the
            //       national identifier, with sixteen masked positions and four retained.
            governmentIssuedIdentifier =
                    maskTrailing(
                            governmentIssuedIdentifier,
                            GOVERNMENT_IDENTIFIER_WIDTH,
                            "governmentIssuedIdentifier");
            currentBalance =
                    requireMagnitude(
                            currentBalance,
                            MAX_ACCOUNT_BALANCE,
                            "currentBalance",
                            "ten integer digits");

            // WHY : Assumptions: record padding is absent per owning copybook: X(14) at
            //       app/cpy/CVACT03Y.cpy L8, X(168) at app/cpy/CUSTREC.cpy L23 and X(178) at
            //       app/cpy/CVACT01Y.cpy L17. None carries a VALUE, so none is JSON data.
            // WHY : Assumptions: the card verification value is suppressed, meaning no component,
            //       no key and no substitute member. A blank or masked member would still cause a
            //       serialiser to expose that the field exists, which absence avoids. The complete
            //       joined identity rosters at app/cpy/CVACT03Y.cpy L5-L8 and
            //       app/cpy/CVCUS01Y.cpy L5-L23 declare no such member.
        }
    
        /**
         * Renders NOTHING but the presence of its one optional member: all nine components are protected.
         *
         * <p>Purpose. This shape carries a primary account number, two identifiers, a name in three parts, a
         * NATIONAL identifier, a government-issued identifier and a balance.
         * {@code docs/architecture/observability.md} L1093 to L1112 withholds identifiers and amounts by
         * name, and the two credential-grade identifiers are the values this migration encrypts at rest and
         * masks in every response -- so a rendering that printed them would defeat, in one line, controls
         * applied at the column and at the API boundary.</p>
         *
         * <p>Assumptions: the two credential identifiers get no presence flag either, unlike the middle
         * name. Whether a customer has a stored national identifier is itself a fact about that customer,
         * and the mapping does not branch on it, so a flag would disclose something while diagnosing
         * nothing.</p>
         *
         * <p>Trade-offs: this rendering is close to contentless, and that is the correct outcome for a shape
         * whose every component is protected. What locates a statement in a log is the correlation
         * identifier on the same line and the run identifier the job carries, neither of which names a
         * cardholder.</p>
         *
         * @return a rendering reporting whether the optional middle name is present, with all nine values
         *     withheld; never {@code null}
         */
        @Override
        public String toString() {
            return "StatementSummaryPayload[middleNamePresent=" + (this.middleName != null)
                    + ", personalData=[REDACTED]]";
        }
}

    /**
     * Carries one transaction-type reference value as JSON.
     *
     * @param typeCode the declared-width two-character type code declared as
     *     {@code TRAN-TYPE PIC X(02)} at L5 of {@code app/cpy/CVTRA03Y.cpy}
     * @param description the type description with trailing storage padding removed, bounded by
     *     {@code TRAN-TYPE-DESC PIC X(50)} at L6 of the same copybook
     */
    public record TransactionTypePayload(String typeCode, String description) {

        /**
         * Validates and trims a transaction-type value at construction.
         *
         * @param typeCode the type code, which must contain exactly two characters
         * @param description the description, which must not exceed fifty characters before
         *     trailing storage padding is removed
         * @throws NullPointerException if either component is {@code null}
         * @throws IllegalArgumentException if the code has the wrong width or the description
         *     exceeds its declared width
         */
        public TransactionTypePayload {
            typeCode = declaredWidthCode(typeCode, TYPE_CODE_WIDTH, "typeCode");

            // WHY : Assumptions: TRAN-TYPE-DESC is PIC X(50) at
            //       app/cpy/CVTRA03Y.cpy L6 but the report band allocates only PIC X(15) at
            //       app/cpy/CVTRA07Y.cpy L22, so right-side spaces are storage padding and no
            //       leading character may be removed.
            description =
                    descriptiveText(description, REFERENCE_DESCRIPTION_WIDTH, "description");

            // WHY : Assumptions: the X(08) FILLER at app/cpy/CVTRA03Y.cpy L7 is padding without
            //       a VALUE and therefore has no JSON component. This local omission must not be
            //       generalized to app/cpy/CVTRA07Y.cpy, whose 22 FILLER declarations are
            //       byte-record content because every one carries a VALUE.
        }
    }

    /**
     * Carries one transaction-category reference value as JSON.
     *
     * @param typeCode the declared-width two-character leading key component declared at L6 of
     *     {@code app/cpy/CVTRA04Y.cpy}
     * @param categoryCode the declared-width four-character trailing key component declared at L7 of
     *     {@code app/cpy/CVTRA04Y.cpy}
     * @param description the category description with trailing storage padding removed, bounded
     *     by the fifty characters declared at L8 of {@code app/cpy/CVTRA04Y.cpy}
     */
    public record TransactionCategoryPayload(
            String typeCode, String categoryCode, String description) {

        /**
         * Validates and trims a transaction-category value at construction.
         *
         * @param typeCode the type code, which must contain exactly two characters
         * @param categoryCode the category code, which must contain exactly four characters
         * @param description the description, which must not exceed fifty characters before
         *     trailing storage padding is removed
         * @throws NullPointerException if any component is {@code null}
         * @throws IllegalArgumentException if a code has the wrong width or the description
         *     exceeds its declared width
         */
        public TransactionCategoryPayload {
            typeCode = declaredWidthCode(typeCode, TYPE_CODE_WIDTH, "typeCode");
            categoryCode =
                    declaredWidthDigits(categoryCode, CATEGORY_CODE_WIDTH, "categoryCode");

            // WHY : Assumptions: TRAN-CAT-TYPE-DESC is PIC X(50) at
            //       app/cpy/CVTRA04Y.cpy L8 while the report band allocates PIC X(29) at
            //       app/cpy/CVTRA07Y.cpy L26, so trailing spaces are padding and the retained
            //       prefix is carried character-for-character.
            description =
                    descriptiveText(description, REFERENCE_DESCRIPTION_WIDTH, "description");

            // WHY : Assumptions: the X(04) FILLER at app/cpy/CVTRA04Y.cpy L9 is padding and has
            //       no JSON component. The owning copybook is named because TRAN-CAT-KEY is six
            //       characters at app/cpy/CVTRA04Y.cpy L5 but seventeen at
            //       app/cpy/CVTRA01Y.cpy L5, so the data name alone cannot identify geometry.
        }
    }

    /**
     * Carries the resolved report name and inclusive reporting range as JSON.
     *
     * <p>Assumptions: the request contains the values that {@code app/cbl/CORPT00C.cbl} passes
     * onward after resolving a preset. It contains neither the twelve-character amount display
     * member at L77 nor the 80-character submission buffer at L79, because the first is an output
     * rendering concern and the second starts execution rather than describing the request.
     *
     * @param reportName the report name, with trailing storage padding removed and no more than
     *     ten characters as declared at L58 of {@code app/cbl/CORPT00C.cbl}
     * @param startDate the inclusive start date, exactly ten characters in the
     *     {@value ReportingDtoMapper#DATE_FORM} form assembled at L60 to L65
     * @param endDate the inclusive end date, exactly ten characters in the
     *     {@value ReportingDtoMapper#DATE_FORM} form assembled at L66 to L71
     */
    public record ReportRequestPayload(String reportName, String startDate, String endDate) {

        /**
         * Validates a report request against the declared name width and date form.
         *
         * @param reportName the report name, which may carry right-side storage padding but must
         *     retain at least one character after that padding is removed
         * @param startDate the inclusive start date in {@value ReportingDtoMapper#DATE_FORM} form
         * @param endDate the inclusive end date in {@value ReportingDtoMapper#DATE_FORM} form
         * @throws NullPointerException if any component is {@code null}
         * @throws IllegalArgumentException if the name is blank or over width, or if either date
         *     has the wrong width, separators, digit positions or calendar value
         */
        public ReportRequestPayload {
            // WHY : Assumptions: WS-REPORT-NAME is PIC X(10) at
            //       app/cbl/CORPT00C.cbl L58, while the values moved into it are Monthly at L214,
            //       Yearly at L240 and Custom at L433. The ten positions are therefore a maximum
            //       with right-side padding rather than a requirement that JSON carry ten bytes.
            reportName = requireReportName(reportName);

            // WHY : Assumptions: the start-date group at app/cbl/CORPT00C.cbl L60-L65 is four
            //       digits, a hyphen, two digits, a hyphen and two digits. Validation returns the
            //       caller's exact characters after checking that contract, so AAP Rule T8
            //       (user-visible strings are verbatim) remains intact.
            startDate = requireDateForm(startDate, "startDate");

            // WHY : Assumptions: the end-date group at app/cbl/CORPT00C.cbl L66-L71 has the same
            //       ten-character geometry as the start date and is validated independently so a
            //       refusal names the component whose form is outside the contract.
            endDate = requireDateForm(endDate, "endDate");

            // WHY : Assumptions: WS-TRAN-AMT PIC +99999999.99 at
            //       app/cbl/CORPT00C.cbl L77 is a twelve-character, eight-integer-digit display
            //       regime. It is not a request component and no edit mask is applied here;
            //       money-bearing payloads retain Money so their wire form remains a JSON string.
            // WHY : Assumptions: JCL-RECORD PIC X(80) at app/cbl/CORPT00C.cbl L79 is an
            //       execution-start buffer, not data a caller submits, so this record has no
            //       corresponding component and emits no byte record.
            // WHY : Trade-offs: tests/README.md L40-L46 and L83-L85 establish that this
            //       CORPT00C-derived request has extractable validation tests but no end-to-end
            //       golden-master oracle, unlike the batch-derived emission paths. Constructor
            //       checks make each declared width and separator executable, accepting that they
            //       cannot prove the terminal-monitor interaction the unavailable oracle covered.
        }
    }

    /**
     * Builds the JSON payload for one transaction-report detail line.
     *
     * @param transactionId the transaction identifier, exactly sixteen characters
     * @param accountId the account identifier, exactly eleven characters
     * @param typeCode the transaction type code, exactly two characters
     * @param typeDescription the type description, at most fifty characters before trailing
     *     storage padding is removed
     * @param categoryCode the category code, exactly four characters
     * @param categoryDescription the category description, at most fifty characters before
     *     trailing storage padding is removed
     * @param source the transaction source, exactly ten characters
     * @param amount the exact transaction amount, bounded by nine integer positions
     * @return a validated report transaction payload whose descriptive values have only trailing
     *     storage padding removed
     * @throws NullPointerException if any argument is {@code null}
     * @throws IllegalArgumentException if a declared width or the transaction magnitude is
     *     outside its contract
     */
    public static ReportTransactionPayload toReportTransaction(
            String transactionId,
            String accountId,
            String typeCode,
            String typeDescription,
            String categoryCode,
            String categoryDescription,
            String source,
            Money amount) {
        return new ReportTransactionPayload(
                transactionId,
                accountId,
                typeCode,
                typeDescription,
                categoryCode,
                categoryDescription,
                source,
                amount);
    }

    /**
     * Builds the JSON payload for one statement-ordered transaction.
     *
     * @param cardNumber the primary account number, already narrowed or in its full
     *     sixteen-character form
     * @param transactionId the transaction identifier, exactly sixteen characters
     * @param typeCode the transaction type code, exactly two characters
     * @param categoryCode the transaction category code, exactly four characters
     * @param source the transaction source, exactly ten characters
     * @param description the transaction description, at most one hundred characters before
     *     trailing storage padding is removed
     * @param amount the exact transaction amount, bounded by nine integer positions
     * @param merchantId the non-negative merchant identifier, bounded by nine digits
     * @param merchantName the merchant name, at most fifty characters before trailing storage
     *     padding is removed
     * @param merchantCity the merchant city, at most fifty characters before trailing storage
     *     padding is removed
     * @param merchantPostalCode the merchant postal code, exactly ten characters
     * @param originatingTimestamp the opaque 26-character origination timestamp, or {@code null}
     * @param processingTimestamp the opaque 26-character processing timestamp, or {@code null}
     * @return a validated statement transaction payload with its primary account number narrowed
     *     and its timestamps otherwise unchanged
     * @throws NullPointerException if a required argument is {@code null}
     * @throws IllegalArgumentException if a declared width or magnitude is outside its contract
     */
    public static StatementTransactionPayload toStatementTransaction(
            String cardNumber,
            String transactionId,
            String typeCode,
            String categoryCode,
            String source,
            String description,
            Money amount,
            Long merchantId,
            String merchantName,
            String merchantCity,
            String merchantPostalCode,
            String originatingTimestamp,
            String processingTimestamp) {
        return new StatementTransactionPayload(
                cardNumber,
                transactionId,
                typeCode,
                categoryCode,
                source,
                description,
                amount,
                merchantId,
                merchantName,
                merchantCity,
                merchantPostalCode,
                originatingTimestamp,
                processingTimestamp);
    }

    /**
     * Builds the JSON payload that heads one statement.
     *
     * @param cardNumber the primary account number, already narrowed or in its full
     *     sixteen-character form
     * @param customerId the customer identifier, exactly nine characters
     * @param accountId the account identifier, exactly eleven characters
     * @param firstName the first name, at most twenty-five characters before trailing storage
     *     padding is removed
     * @param middleName the middle name, at most twenty-five characters before trailing storage
     *     padding is removed
     * @param lastName the last name, at most twenty-five characters before trailing storage
     *     padding is removed
     * @param nationalIdentifier the national identifier, already narrowed or in its full
     *     nine-character form
     * @param governmentIssuedIdentifier the government-issued identifier, already narrowed or in
     *     its full twenty-character form
     * @param currentBalance the exact account balance, bounded by ten integer positions
     * @return a validated statement summary with all three protected identifiers narrowed before
     *     they can be serialised
     * @throws NullPointerException if any argument is {@code null}
     * @throws IllegalArgumentException if a declared width or the balance magnitude is outside
     *     its contract
     */
    public static StatementSummaryPayload toStatementSummary(
            String cardNumber,
            String customerId,
            String accountId,
            String firstName,
            String middleName,
            String lastName,
            String nationalIdentifier,
            String governmentIssuedIdentifier,
            Money currentBalance) {
        return new StatementSummaryPayload(
                cardNumber,
                customerId,
                accountId,
                firstName,
                middleName,
                lastName,
                nationalIdentifier,
                governmentIssuedIdentifier,
                currentBalance);
    }

    /**
     * Builds one transaction-type reference payload.
     *
     * @param typeCode the transaction type code, exactly two characters
     * @param description the type description, at most fifty characters before trailing storage
     *     padding is removed
     * @return a validated type payload retaining the code's declared width
     * @throws NullPointerException if either argument is {@code null}
     * @throws IllegalArgumentException if the code has the wrong width or the description exceeds
     *     its declared width
     */
    public static TransactionTypePayload toTransactionType(
            String typeCode, String description) {
        return new TransactionTypePayload(typeCode, description);
    }

    /**
     * Builds one transaction-category reference payload.
     *
     * @param typeCode the leading transaction type code, exactly two characters
     * @param categoryCode the trailing category code, exactly four characters
     * @param description the category description, at most fifty characters before trailing
     *     storage padding is removed
     * @return a validated category payload retaining both key components at declared width
     * @throws NullPointerException if any argument is {@code null}
     * @throws IllegalArgumentException if either code has the wrong width or the description
     *     exceeds its declared width
     */
    public static TransactionCategoryPayload toTransactionCategory(
            String typeCode, String categoryCode, String description) {
        return new TransactionCategoryPayload(typeCode, categoryCode, description);
    }

    /**
     * Builds a resolved report request payload.
     *
     * @param reportName the report name, no more than ten characters after trailing storage
     *     padding is removed
     * @param startDate the inclusive start date in {@value #DATE_FORM} form
     * @param endDate the inclusive end date in {@value #DATE_FORM} form
     * @return a validated request containing no execution buffer and no rendered amount field
     * @throws NullPointerException if any argument is {@code null}
     * @throws IllegalArgumentException if the report name is blank or over width, or either date
     *     is outside the declared form
     */
    public static ReportRequestPayload toReportRequest(
            String reportName, String startDate, String endDate) {
        return new ReportRequestPayload(reportName, startDate, endDate);
    }

    /**
     * Renders a caller-supplied local timestamp in the shared 26-character contract form.
     *
     * <p>Assumptions: the caller supplies the value and this method never reads a clock. Delegating
     * to the shared formatter keeps the required space between date and time and the six
     * fractional positions in one implementation rather than declaring another format here.
     * {@code TimestampFormatter.format(LocalDateTime)} is the explicit-argument entry point at
     * L368 of the shared formatter.
     *
     * @param timestamp the zone-free local timestamp to render; must not be {@code null}
     * @return the timestamp as exactly 26 characters in the form
     *     {@code YYYY-MM-DD HH:MM:SS.mmmmmm}
     * @throws NullPointerException if {@code timestamp} is {@code null}
     * @throws IllegalArgumentException if the timestamp year cannot be represented by the
     *     26-character contract
     */
    public static String renderTimestamp(LocalDateTime timestamp) {
        return TimestampFormatter.format(timestamp);
    }

    /**
     * Narrows a primary account number to a same-width mask and its last four characters.
     *
     * <p>Assumptions: callers may supply either the full value carried by
     * {@code TRAN-CARD-NUM PIC X(16)} at L15 of {@code app/cpy/CVTRA05Y.cpy} or the canonical
     * twelve-mask-character projection form. Applying the operation to either yields the same
     * sixteen-character result, so repeated mapping cannot widen what an earlier boundary
     * narrowed.
     *
     * @param cardNumber the full or already narrowed primary account number, exactly sixteen
     *     significant characters; must not be {@code null}
     * @return twelve mask characters followed by the input's last four significant characters
     * @throws NullPointerException if {@code cardNumber} is {@code null}
     * @throws IllegalArgumentException if {@code cardNumber} does not contain exactly sixteen
     *     significant characters
     */
    public static String maskPrimaryAccountNumber(String cardNumber) {
        return maskTrailing(cardNumber, CARD_NUMBER_WIDTH, "cardNumber");
    }

    /**
     * Masks every significant character except the last four while retaining declared width.
     *
     * <p>Assumptions: trailing U+0020 spaces are storage padding and are excluded before width is
     * asserted, matching the right-trim applied by the read-only projections. No other whitespace
     * character is removed, because AAP Rule T8 (user-visible strings are verbatim) permits no
     * broader normalisation. The primary account number's declared width is sixteen at
     * {@code app/cpy/CVACT03Y.cpy} L5.
     *
     * @param value the full or already narrowed identifier; must not be {@code null}
     * @param declaredWidth the number of significant characters the owning copybook declares,
     *     which must be at least four
     * @param component the non-sensitive component name used in refusal messages; must not be
     *     {@code null}
     * @return a string of {@code declaredWidth} characters with only the final four copied from
     *     {@code value}
     * @throws NullPointerException if {@code value} or {@code component} is {@code null}
     * @throws IllegalArgumentException if {@code declaredWidth} is less than four or the value
     *     does not contain exactly that many significant characters
     */
    private static String maskTrailing(String value, int declaredWidth, String component) {
        Objects.requireNonNull(component, "component must not be null");
        Objects.requireNonNull(value, component + " must not be null");

        if (declaredWidth < VISIBLE_TRAILING_CHARACTERS) {
            throw new IllegalArgumentException(
                    "declaredWidth must be at least " + VISIBLE_TRAILING_CHARACTERS);
        }

        int significantEnd = value.length();
        while (significantEnd > 0 && value.charAt(significantEnd - 1) == ' ') {
            significantEnd--;
        }

        if (significantEnd != declaredWidth) {
            throw new IllegalArgumentException(
                    component + " must contain exactly " + declaredWidth + " significant characters");
        }

        int visibleStart = declaredWidth - VISIBLE_TRAILING_CHARACTERS;

        // WHY : Assumptions: every prefix position is generated rather than copied, which makes
        //       the operation safe for both full and already narrowed input. For the primary
        //       account number the twelve-position prefix follows X(16) at
        //       app/cpy/CVACT03Y.cpy L5 and the four-character disclosure limit.
        return String.valueOf(MASK_CHARACTER).repeat(visibleStart)
                + value.substring(visibleStart, significantEnd);
    }

    /**
     * Removes only trailing storage spaces from descriptive text.
     *
     * <p>Assumptions: a descriptive {@code PIC X(n)} member maps to variable-length text, while
     * its left edge remains content. The scan therefore recognises only U+0020 at the right edge;
     * using a general whitespace normaliser would change characters the copybook can carry.
     *
     * @param value the descriptive value as read from its projection; must not be {@code null}
     * @param maximumWidth the owning copybook's declared maximum width, greater than zero
     * @param component the non-sensitive component name used in refusal messages; must not be
     *     {@code null}
     * @return {@code value} without trailing U+0020 storage spaces, retaining every other
     *     character in its original order
     * @throws NullPointerException if {@code value} or {@code component} is {@code null}
     * @throws IllegalArgumentException if {@code maximumWidth} is not positive or {@code value}
     *     exceeds it before padding is removed
     */
    private static String descriptiveText(
            String value, int maximumWidth, String component) {
        Objects.requireNonNull(component, "component must not be null");
        Objects.requireNonNull(value, component + " must not be null");

        if (maximumWidth <= 0) {
            throw new IllegalArgumentException("maximumWidth must be positive");
        }
        if (value.length() > maximumWidth) {
            throw new IllegalArgumentException(
                    component + " must not exceed " + maximumWidth + " characters");
        }

        int contentEnd = value.length();
        while (contentEnd > 0 && value.charAt(contentEnd - 1) == ' ') {
            contentEnd--;
        }

        // WHY : Trade-offs: returning the original instance when no trailing U+0020 exists avoids
        //       allocating an equal string, while the branch remains semantically invisible. The
        //       observable policy is still the copybook-backed one stated at each call site, such
        //       as the fifty-character description at app/cpy/CVTRA03Y.cpy L6.
        return contentEnd == value.length() ? value : value.substring(0, contentEnd);
    }

    /**
     * Requires a code or key to retain every character of its declared width.
     *
     * <p>Assumptions: declared-width codes and keys remain full-width because leading zeros and right-side
     * spaces participate in their identity. This helper performs no trim and no case conversion;
     * the two-character type code is declared at app/cpy/CVTRA05Y.cpy L6 and emitted at the same
     * width at app/cpy/CVTRA07Y.cpy L20.
     *
     * @param value the code or key value to validate; must not be {@code null}
     * @param declaredWidth the exact number of characters its owning copybook declares, greater
     *     than zero
     * @param component the non-sensitive component name used in refusal messages; must not be
     *     {@code null}
     * @return {@code value} unchanged
     * @throws NullPointerException if {@code value} or {@code component} is {@code null}
     * @throws IllegalArgumentException if {@code declaredWidth} is not positive or the value has
     *     any other length
     */
    private static String declaredWidthCode(
            String value, int declaredWidth, String component) {
        Objects.requireNonNull(component, "component must not be null");
        Objects.requireNonNull(value, component + " must not be null");

        if (declaredWidth <= 0) {
            throw new IllegalArgumentException("declaredWidth must be positive");
        }
        if (value.length() != declaredWidth) {
            throw new IllegalArgumentException(
                    component + " must contain exactly " + declaredWidth + " characters");
        }

        return value;
    }

    /**
     * Requires a numeric code or identifier to retain its declared width and leading zeros.
     *
     * <p>Assumptions: numeric {@code PICTURE} declarations constrain the character domain even
     * though JSON carries the value as text. The representative declarations are
     * {@code CUST-ID PIC 9(09)} at app/cpy/CUSTREC.cpy L5 and
     * {@code TRAN-CAT-CD PIC 9(04)} at app/cpy/CVTRA04Y.cpy L7.
     *
     * @param value the numeric code or identifier as text; must not be {@code null}
     * @param declaredWidth the exact positive number of digit positions the owning copybook
     *     declares
     * @param component the non-sensitive component name used in refusal messages; must not be
     *     {@code null}
     * @return {@code value} unchanged, including all leading zeros
     * @throws NullPointerException if {@code value} or {@code component} is {@code null}
     * @throws IllegalArgumentException if the value has the wrong width or contains a
     *     non-ASCII-digit character
     */
    private static String declaredWidthDigits(
            String value, int declaredWidth, String component) {
        String validated = declaredWidthCode(value, declaredWidth, component);

        for (int index = 0; index < validated.length(); index++) {
            char character = validated.charAt(index);
            if (character < '0' || character > '9') {
                throw new IllegalArgumentException(
                        component + " must contain exactly " + declaredWidth + " ASCII digits");
            }
        }

        return validated;
    }

    /**
     * Validates an opaque timestamp's width without parsing or normalising its contents.
     *
     * @param value the caller-supplied timestamp, or {@code null} when the projection has no
     *     calendar value
     * @param component the non-sensitive component name used in refusal messages; must not be
     *     {@code null}
     * @return {@code value} unchanged, including a final pair of spaces, or {@code null}
     * @throws NullPointerException if {@code component} is {@code null}
     * @throws IllegalArgumentException if a non-null value is not exactly 26 characters
     */
    private static String opaqueTimestamp(String value, String component) {
        Objects.requireNonNull(component, "component must not be null");

        if (value == null) {
            return null;
        }
        if (value.length() != TimestampFormatter.TIMESTAMP_LENGTH) {
            throw new IllegalArgumentException(
                    component
                            + " must contain exactly "
                            + TimestampFormatter.TIMESTAMP_LENGTH
                            + " characters");
        }

        // WHY : Assumptions: no parser is called because app/jcl/CREASTMT.JCL L53-L54 can leave
        //       the final two positions of TRNX-PROC-TS blank. The 26-character field is the
        //       contract here, and interpreting it as a calendar value would reject an artifact
        //       the baseline intentionally carries.
        return value;
    }

    /**
     * Requires a monetary value to fit its own copybook magnitude without changing it.
     *
     * <p>Assumptions: comparison is the only operation performed on the value. No rounding,
     * scaling or arithmetic result is introduced, so the exact {@link Money} instance supplied by
     * the caller is the instance stored in the payload. The compared magnitudes come from
     * {@code PIC S9(09)V99} at app/cpy/CVTRA05Y.cpy L10 and {@code PIC S9(10)V99} at
     * app/cpy/CVACT01Y.cpy L7.
     *
     * @param value the monetary value to validate; must not be {@code null}
     * @param maximum the inclusive absolute magnitude admitted by the owning picture; must not be
     *     {@code null} or negative
     * @param component the non-sensitive component name used in refusal messages; must not be
     *     {@code null}
     * @param declaredMagnitude a non-sensitive description of the owning picture's magnitude for
     *     the refusal message; must not be {@code null}
     * @return {@code value} unchanged
     * @throws NullPointerException if any reference argument is {@code null}
     * @throws IllegalArgumentException if {@code maximum} is negative or {@code value} exceeds it
     */
    private static Money requireMagnitude(
            Money value,
            BigDecimal maximum,
            String component,
            String declaredMagnitude) {
        Objects.requireNonNull(value, "money value must not be null");
        Objects.requireNonNull(maximum, "maximum must not be null");
        Objects.requireNonNull(component, "component must not be null");
        Objects.requireNonNull(declaredMagnitude, "declaredMagnitude must not be null");

        if (maximum.signum() < 0) {
            throw new IllegalArgumentException("maximum must not be negative");
        }
        if (value.amount().abs().compareTo(maximum) > 0) {
            throw new IllegalArgumentException(
                    component + " exceeds its declared " + declaredMagnitude + " magnitude");
        }

        // WHY : Assumptions: the limit supplied by each call site is distinct: NUMERIC(11,2)
        //       follows PIC S9(09)V99 at app/cpy/CVTRA05Y.cpy L10 and
        //       app/cpy/COSTM01.CPY L29, while NUMERIC(12,2) follows PIC S9(10)V99 at
        //       app/cpy/CVACT01Y.cpy L7. Selecting the limit at the call site prevents the
        //       one-integer-digit gap from being hidden in a universal money bound.
        return value;
    }

    /**
     * Requires an unsigned merchant identifier to fit nine decimal digits.
     *
     * @param merchantId the merchant identifier to validate; must not be {@code null}
     * @return {@code merchantId} unchanged
     * @throws NullPointerException if {@code merchantId} is {@code null}
     * @throws IllegalArgumentException if the value is negative or exceeds nine digits
     */
    private static Long requireMerchantIdentifier(Long merchantId) {
        Objects.requireNonNull(merchantId, "merchantId must not be null");

        if (merchantId < 0 || merchantId > MAX_MERCHANT_IDENTIFIER) {
            throw new IllegalArgumentException(
                    "merchantId must fit the unsigned nine-digit declaration");
        }
        return merchantId;
    }

    /**
     * Constructs the inclusive scale-two magnitude represented by an all-nines picture.
     *
     * <p>Assumptions: the bound is assembled from decimal characters rather than calculated from
     * an input amount. For nine integer positions the result is {@code 999999999.99}; for ten it
     * is {@code 9999999999.99}. This records the pictures at app/cpy/CVTRA05Y.cpy L10 and
     * app/cpy/CVACT01Y.cpy L7 exactly and introduces no rounding.
     *
     * @param integerDigits the positive number of integer positions in the owning picture
     * @return the greatest non-negative scale-two decimal that the picture can hold
     * @throws IllegalArgumentException if {@code integerDigits} is not positive
     */
    private static BigDecimal magnitudeLimit(int integerDigits) {
        if (integerDigits <= 0) {
            throw new IllegalArgumentException("integerDigits must be positive");
        }

        // WHY : Assumptions: Money.SCALE supplies the two fractional positions shared by both
        //       pictures, while integerDigits stays caller-specific. Building all positions from
        //       the digit character keeps the nine- and ten-position limits visibly distinct;
        //       Money.SCALE is declared as two at Money.java L173.
        String rendered =
                "9".repeat(integerDigits) + "." + "9".repeat(Money.SCALE);
        return new BigDecimal(rendered);
    }

    /**
     * Removes report-name padding and requires at least one character within the ten-character
     * field.
     *
     * @param reportName the name as carried by {@code WS-REPORT-NAME}; must not be {@code null}
     * @return the name with trailing U+0020 storage spaces removed
     * @throws NullPointerException if {@code reportName} is {@code null}
     * @throws IllegalArgumentException if the name exceeds ten characters or contains only
     *     storage spaces
     */
    private static String requireReportName(String reportName) {
        String narrowed = descriptiveText(reportName, REPORT_NAME_WIDTH, "reportName");

        if (narrowed.isEmpty()) {
            throw new IllegalArgumentException("reportName must contain at least one character");
        }
        return narrowed;
    }

    /**
     * Requires a reporting date to match the declared ten-character calendar form.
     *
     * <p>Assumptions: validation checks both geometry and calendar validity but returns the
     * original value. The mapper therefore refuses an impossible date without normalising any
     * character in a valid one. The ten-character geometry comes from
     * app/cbl/CORPT00C.cbl L60-L72.
     *
     * @param value the date value to validate; must not be {@code null}
     * @param component the non-sensitive component name used in refusal messages; must not be
     *     {@code null}
     * @return {@code value} unchanged
     * @throws NullPointerException if {@code value} or {@code component} is {@code null}
     * @throws IllegalArgumentException if the value has the wrong width, separator positions,
     *     digit positions or calendar value
     */
    private static String requireDateForm(String value, String component) {
        Objects.requireNonNull(component, "component must not be null");
        Objects.requireNonNull(value, component + " must not be null");

        if (value.length() != DATE_WIDTH) {
            throw new IllegalArgumentException(
                    component + " must contain exactly " + DATE_WIDTH + " characters");
        }
        if (value.charAt(FIRST_HYPHEN_INDEX) != DATE_SEPARATOR
                || value.charAt(SECOND_HYPHEN_INDEX) != DATE_SEPARATOR) {
            throw new IllegalArgumentException(component + " must use the " + DATE_FORM + " form");
        }

        for (int index = 0; index < value.length(); index++) {
            if (index == FIRST_HYPHEN_INDEX || index == SECOND_HYPHEN_INDEX) {
                continue;
            }
            char character = value.charAt(index);
            if (character < '0' || character > '9') {
                throw new IllegalArgumentException(
                        component + " must use ASCII digits in the " + DATE_FORM + " form");
            }
        }

        try {
            LocalDate.parse(value);
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException(
                    component + " must be a valid calendar date in the " + DATE_FORM + " form",
                    exception);
        }

        return value;
    }
}
