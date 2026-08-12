package com.carddemo.card.mapper;

import com.carddemo.card.domain.Card;
import com.carddemo.card.dto.CardDetail;
import com.carddemo.card.dto.CardSummary;
import com.carddemo.card.dto.CardUpdateRequest;
import com.carddemo.common.error.ClientInputException;
import com.carddemo.common.security.CardNumberMasker;
import com.carddemo.common.security.SealedSelector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import com.carddemo.common.web.PageResponse;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The anti-corruption layer between the stored card row and the bodies this context publishes.
 *
 * <p>Purpose: this class converts {@link Card} into the response shapes declared by
 * {@code src/main/resources/openapi/card-api.yaml}, applies a submitted update onto a stored row, and
 * opens the opaque selector by which every single-card operation addresses its card. It is the single
 * class of this package, and the package charter in {@code package-info.java} reserves to it every
 * concern arising from the shape of the baseline record, so that {@link Card} and the records under
 * {@code com.carddemo.card.dto} carry none.</p>
 *
 * <p>The record it bridges is {@code 01 CARD-RECORD} at {@code app/cpy/CVACT02Y.cpy:4}, whose banner at
 * {@code :2} declares 150 bytes. Six fields are named -- {@code CARD-NUM PIC X(16)} at {@code :5},
 * {@code CARD-ACCT-ID PIC 9(11)} at {@code :6}, {@code CARD-CVV-CD PIC 9(03)} at {@code :7},
 * {@code CARD-EMBOSSED-NAME PIC X(50)} at {@code :8}, {@code CARD-EXPIRAION-DATE PIC X(10)} at
 * {@code :9} and {@code CARD-ACTIVE-STATUS PIC X(01)} at {@code :10} -- and they occupy 91 bytes
 * between them, the balance being the padding at {@code :11}. Every baseline path named in this class
 * is reference material, cited by path and line: it is read, never modified, and still running.</p>
 *
 * <p>Assumptions: two sibling files are the contracts of record and this class mirrors them rather
 * than deriving the layout from the copybook a second time. The column list is
 * {@code src/main/resources/db/migration/V1__card.sql} and the published shapes are
 * {@code src/main/resources/openapi/card-api.yaml}. Deriving either independently is the alternative
 * and is what this avoids: two derivations disagree the first time one is edited, and because a
 * response component is a string on both sides of the boundary, nothing reports the disagreement at
 * build time.</p>
 *
 * <p>Alternatives Considered: generating this class with MapStruct, and generating the accessors it
 * reads with Lombok. Neither is on the classpath of
 * {@code services/card-service/pom.xml} and neither is added; the package charter records the
 * decision in full and it is not restated here. What matters at this scale is the consequence: every
 * conversion below is a judgement rather than a field copy, and each one carries its reasoning at the
 * line that performs it, which is the one place a generated method could not hold text.</p>
 *
 * <p>Trade-offs: this class holds no repository and issues no query, so it cannot decide what a page
 * contains or which row an update lands on. It converts what it is handed. The cost is that a caller
 * must read the row, settle the page boundaries and enforce the caller's authority before calling in;
 * what it buys is that every method here is a function of its arguments and is testable with no
 * database and no web context present.</p>
 *
 * <p>Assumptions: the mappings here are held to the baseline by transcription against the programs
 * cited at each site and by this module's own tests, and not by a comparison against recorded output.
 * {@code tests/README.md:83-85} records that the baseline's online programs cannot be exercised end to
 * end without a CICS runtime, so no such comparison exists for any card screen and none is claimed
 * for one.</p>
 *
 * <p>Assumptions: this context stores no monetary amount, so the project-wide exact-decimal rule has
 * no subject here. The record declares two numeric fields and neither is an amount: an eleven-digit
 * account identifier and a three-digit verification code. The absence is written down rather than
 * left implicit, because the cheapest way to breach that rule is for a class believed to handle no
 * amount to acquire one later.</p>
 */
// WHY : Refactoring Rationale: this mapper had no construction site at all. It declares a
//       constructor taking the deployment's selector sealer, and nothing contributed either the
//       mapper or the sealer, so no route in this context could mint a selector or open one -- the
//       type compiled and was unreachable. It is a component now, and
//       com.carddemo.card.config.CardSelectorConfig contributes the sealer it needs.
// WHY : Alternatives Considered: a @Bean method beside that sealer, which would keep both
//       contributions in one file. Declined because this class holds no configuration of its own
//       beyond the collaborator it is handed, so a factory method would add a second place to look
//       for it; component scanning states the dependency once, in the constructor that needs it.
@Component
public class CardMapper {

    /**
     * The scope under which every card row selector is sealed and opened.
     *
     * <p>Assumptions: the identical string is required to open a selector this class sealed, and a
     * different one yields an unrelated selector for the same card, so a token minted for a card row
     * cannot be presented anywhere another purpose is expected.</p>
     */
    public static final String SELECTOR_PURPOSE = "card-row";

    /**
     * The contract field name a refused selector is attributed to.
     *
     * <p>Assumptions: the value is the path parameter name the contract declares for the four
     * single-card operations, so a client told to correct {@code cardKey} is told the name of
     * something it actually sent.</p>
     */
    public static final String SELECTOR_FIELD = "cardKey";

    /**
     * The stable code carried by every refusal of an unopenable card selector.
     *
     * <p>Assumptions: one code covers every way a selector can fail, because they share one remedy --
     * list the cards again and take a fresh selector from the row -- so separating them would divide
     * occurrences that a first responder handles identically.</p>
     */
    public static final String SELECTOR_REFUSAL_CODE = "CARD_SELECTOR_REFUSED";

    /**
     * The number of digit characters an account identifier is published as.
     *
     * <p>Assumptions: eleven is the width of {@code CARD-ACCT-ID PIC 9(11)} at
     * {@code app/cpy/CVACT02Y.cpy:6}, and it is also the alternate-index key width declared by
     * {@code KEYS(11 16)} at {@code app/jcl/CARDFILE.jcl:85}, so the two agree independently.</p>
     */
    private static final int ACCOUNT_ID_WIDTH = 11;

    /**
     * The number of characters an expiration date is published as.
     *
     * <p>Assumptions: ten is the width of {@code CARD-EXPIRAION-DATE PIC X(10)} at
     * {@code app/cpy/CVACT02Y.cpy:9}, holding a hyphen-separated year, month and day.</p>
     */
    private static final int EXPIRATION_DATE_WIDTH = 10;

    /**
     * The domain a stored card number belongs to for this class to be able to render it.
     *
     * <p>Assumptions: sixteen digit characters, being the width of {@code CARD-NUM PIC X(16)} at
     * {@code app/cpy/CVACT02Y.cpy:5} and the pattern the contract declares for every property and path
     * parameter carrying a full number. Three independent statements of the same rule agree with this
     * one and none of them is derived from it: {@code card-api.yaml} declares {@code ^[0-9]{16}$} on the
     * lookup property and on the administrative detail member, {@code CardLookupRequest} constrains its
     * one component to it, and {@code ck_cards_card_num_digits} in
     * {@code src/main/resources/db/migration/V2__card_num_digit_domain.sql} closes it at the column.</p>
     *
     * <p>Alternatives Considered: reading the rule from {@code CardLookupRequest} instead of stating it
     * here, so one constant would serve both. Declined because the two guard opposite directions and a
     * shared constant would tie them together: that record constrains what a CALLER may submit and is
     * free to narrow further, while this constant describes what STORAGE may hold and must not narrow
     * without a migration behind it. The sibling {@code TransactionMapper} publishes its own width
     * constant for the same reason. What is given up is that a future change to the stored domain has to
     * be made in both places, and the check installed by the migration is what would catch a
     * disagreement.</p>
     */
    public static final String CARD_NUMBER_DOMAIN = "^[0-9]{16}$";

    /**
     * The compiled form of {@link #CARD_NUMBER_DOMAIN}.
     *
     * <p>Assumptions: compiled once into a static field because the listing conversion applies it per
     * row, and a page carries one row per card.</p>
     */
    private static final Pattern CARD_NUMBER_DOMAIN_PATTERN = Pattern.compile(CARD_NUMBER_DOMAIN);

    /** Records that a stored row could not be rendered, and never any part of the value. */
    private static final Logger LOG = LoggerFactory.getLogger(CardMapper.class);

    /**
     * Seals and opens the opaque row selector, and is the only collaborator this class holds.
     */
    private final SealedSelector cardSelectorSealer;

    /**
     * Builds a mapper over the deployment's row-selector sealer.
     *
     * <p>Refactoring Rationale: the sealer is injected rather than constructed here, because it holds
     * key material and that is configuration this class has no business reading. Injecting it also
     * makes the instance that mints a selector the same instance that opens one, which is what
     * guarantees a token this class issues is a token it will later accept.</p>
     *
     * @param cardSelectorSealer the deployment's selector sealer, which must not be {@code null}
     * @throws NullPointerException if {@code cardSelectorSealer} is {@code null}, because a mapper with
     *     no sealer could publish neither a row selector nor a route to the row it names
     */
    // WHY : Alternatives Considered: declaring this class a Spring stereotype so the container builds
    //       it. Declined, because no bean of the sealer type is declared anywhere in this module's
    //       configuration, so a stereotype here would make every context load fail on an unsatisfied
    //       dependency rather than only the paths that actually seal something. The sibling
    //       com.carddemo.authorization.mapper.PendingAuthViewMapper is a plain class constructed by
    //       whichever component owns its sealer, and this follows that established shape; the cost is
    //       that the component owning the sealer must construct this class explicitly.
    public CardMapper(SealedSelector cardSelectorSealer) {
        this.cardSelectorSealer =
                Objects.requireNonNull(cardSelectorSealer, "cardSelectorSealer must not be null");
    }

    /**
     * Converts one stored card into the row the paged listing publishes.
     *
     * <p>Assumptions: every column this row needs is declared {@code NOT NULL} by
     * {@code V1__card.sql}, so a {@code null} reaching the refusals below means an instance the
     * provider has not populated rather than a card whose value is absent. Refusing here is the last
     * opportunity to do so: the record's own constructor guards its selector and its masked rendering
     * but not its remaining components, and a constraint annotation on a response record is evaluated
     * when a body is validated as input and not when one is serialised as output.</p>
     *
     * @param card the stored card row to render, which must not be {@code null}
     * @return the listing row in the shape the contract publishes, never {@code null}
     * @throws NullPointerException if {@code card} is {@code null}, or if the card number, account
     *     identifier or active status the contract marks required is unset on it
     * @throws IllegalArgumentException if the sealed selector or the masked rendering this method
     *     derives is not the shape the row's own constructor accepts, which no stored card produces and
     *     which therefore indicates a sealer or masker that has come apart from the shapes its
     *     consumers declare
     */
    public CardSummary toSummary(Card card) {
        Objects.requireNonNull(card, "card must not be null");
        String cardNumber = Objects.requireNonNull(card.getCardNum(), "card number must not be null");

        // WHY : Trade-offs: the number is published masked to its last four digits on this row and on
        //       every other response of this service, the administrative read below being the single
        //       exception. What is given up is real and is accepted deliberately: a caller holding only
        //       the carddemo-user authority cannot copy a card number out of a listing, cannot
        //       reconcile one against a statement or an acquirer file, and cannot search for one, so
        //       any such workflow must go through the one route that discloses the number and the
        //       authority that guards it. That cost is accepted because a listing is the widest
        //       surface this service has -- seven rows to every caller who can reach the endpoint --
        //       and it is the surface least able to justify carrying the number in full.
        // WHY : Assumptions: the last four digits are the disclosed part, and the masker rather than
        //       this class decides that. CardNumberMasker.mask replaces the leading positions instead
        //       of removing them, so the rendering is as wide as the value it hides and the record's
        //       own masked-shape constraint is satisfied by construction.
        // WHY : Assumptions: this is an ADDITION to what the storage platform offered rather than a
        //       repair of it. The three card transactions are defined CONFDATA(NO) at
        //       app/csd/CARDDEMO.CSD:353, :363 and :374, alongside DUMP(YES) TRACE(YES) on the line
        //       above each, so the platform did not suppress confidential data in a dump or a trace and
        //       the programs written against it had no facility to ask it to. Masking at this boundary
        //       is a capability the target has and that platform did not.
        String maskedCardNumber = CardNumberMasker.mask(cardNumber);

        return new CardSummary(
                sealCardSelector(cardNumber),
                maskedCardNumber,
                accountIdDigits(card.getAccountId()),
                Objects.requireNonNull(card.getActiveStatus(), "active status must not be null"));
    }

    /**
     * Converts one stored card into the detail body every non-administrative read returns.
     *
     * <p>Assumptions: this is the body of {@code getCard} and of a successful {@code updateCard}, and
     * it carries the masked rendering only. The contract splits the two detail shapes rather than
     * varying one: {@code CardDetail} is the shared core and has no member able to hold a full number,
     * while {@code AdminCardDetail} is that same core plus one required member that can, returned by
     * the single operation declaring {@code x-required-authority: carddemo-admin}. The full number is
     * therefore obtained separately, from
     * {@link #discloseCardNumberToAdministrator(Card)}, and composed onto this core by that one
     * route.</p>
     *
     * @param card the stored card row to render, which must not be {@code null}
     * @return the detail body in the shape the contract publishes, never {@code null}
     * @throws NullPointerException if {@code card} is {@code null}, or if any column the contract marks
     *     required is unset on it
     * @throws IllegalArgumentException if the sealed selector or the masked rendering this method
     *     derives is not the shape the body's own constructor accepts, or if the stored expiration date
     *     does not render as the ten characters the contract declares
     */
    public CardDetail toDetail(Card card) {
        Objects.requireNonNull(card, "card must not be null");
        String cardNumber = Objects.requireNonNull(card.getCardNum(), "card number must not be null");

        // WHY : Refactoring Rationale: no component of this body carries the card verification value,
        //       and that PRESERVES an absence the baseline already had rather than withdrawing
        //       something it displayed. The value appears in no presentation artifact of the three card
        //       programs: neither app/cpy-bms/COCRDSL.CPY, COCRDLI.CPY nor COCRDUP.CPY names it, and
        //       neither do the mapsets app/bms/COCRDSL.bms, COCRDLI.bms or COCRDUP.bms. The list
        //       screen's display row is only app/cbl/COCRDLIC.cbl:258-260 -- an account number, a card
        //       number and a status -- with no field for it. It is also never accepted as input:
        //       CCUP-NEW-CVV-CD at app/cbl/COCRDUPC.cbl:306 is initialised at :586 and read as a MOVE
        //       source at :1464, and no statement anywhere in that program moves a value INTO it, which
        //       is why no update shape carries it either. So the stored value reaches a screen from
        //       nowhere in the baseline and reaches a body from nowhere here.
        // WHY : Trade-offs: the value is ABSENT rather than masked or truncated. Masking was the
        //       alternative and is rejected on a specific ground: a masked rendering still discloses
        //       the length and the shape of what it hides, and for a three-digit value whose whole
        //       domain is a thousand possibilities that is most of what there is to know. An absent
        //       member discloses neither. It follows that the value is also written to no log and
        //       placed in no error payload by this class, which is why no refusal below quotes a value
        //       it rejected.
        // WHY : Refactoring Rationale: the target field and column are spelled expirationDate and
        //       expiration_date, where the record field at app/cpy/CVACT02Y.cpy:9 carries a misspelling
        //       of the word. This is a TARGET-SIDE NAMING DECISION and nothing more: the baseline tree
        //       is reference material that keeps its own spelling, the snapshot group at
        //       app/cbl/COCRDUPC.cbl:297 carries the same one, and the pairing is recorded in
        //       docs/architecture/data-model-and-schema-mapping.md so the lineage reads from either
        //       side. It is the only field of this record renamed; the other five keep the names their
        //       declarations give them.
        // WHY : Assumptions: seven components are built from six stored fields and none from the
        //       59-byte FILLER at app/cpy/CVACT02Y.cpy:11. Those bytes pad CARD-RECORD out to the
        //       constant length that RECORDSIZE(150 150) at app/jcl/CARDFILE.jcl:55 requires, which
        //       app/cbl/CBACT02C.cbl:39-40 confirms from the other side as a 16-byte key ahead of 134
        //       bytes of data. The assumption is that the fixed length is a storage artefact of the
        //       indexed file and not data: the field names nothing, no program reads it, and a JSON
        //       body has no constant length for it to pad out. The seventh component is the
        //       concurrency counter, which corresponds to a column and not to a record field.
        return new CardDetail(
                sealCardSelector(cardNumber),
                CardNumberMasker.mask(cardNumber),
                accountIdDigits(card.getAccountId()),
                Objects.requireNonNull(card.getEmbossedName(), "embossed name must not be null"),
                isoExpirationDate(card.getExpirationDate()),
                Objects.requireNonNull(card.getActiveStatus(), "active status must not be null"),
                card.getVersion());
    }

    /**
     * Discloses the card number in full, for the one operation authorised to receive it.
     *
     * <p>Assumptions: the caller has already established, server-side, that the requester holds the
     * {@code carddemo-admin} authority. This method performs no authorisation of its own and cannot:
     * it is handed a stored row and has no access to the request, the token or the security context.
     * The authority is decided from the verified claims of the bearer token by the filter chain and
     * never from a field a client supplied, which is the whole reason a single named method exists for
     * this disclosure instead of a flag on the conversion above -- a flag would be one boolean away
     * from disclosing the number on the unauthorised route, and the two calls here cannot be confused
     * for one another at a call site.</p>
     *
     * @param card the stored card row whose number is to be disclosed, which must not be {@code null}
     * @return the sixteen digit characters of the stored card number, unmasked and with leading zeros
     *     significant, never {@code null}
     * @throws NullPointerException if {@code card} is {@code null}, or if the card number is unset on
     *     it
     */
    // WHY : Trade-offs: this returns the number by itself rather than a fully composed administrative
    //       body. The composed shape the contract declares is that of the core above plus this one
    //       value, and building it here would require a type this package does not own and must not
    //       invent. Handing back the single disclosed value keeps the decision -- which route may see
    //       a full number -- in this class, where the masking decision also lives, while leaving the
    //       composition to the route that holds the authority. The cost is that the administrative
    //       route makes two calls where the others make one.
    public String discloseCardNumberToAdministrator(Card card) {
        Objects.requireNonNull(card, "card must not be null");
        return Objects.requireNonNull(card.getCardNum(), "card number must not be null");
    }

    /**
     * Applies a submitted update onto a stored card, in place.
     *
     * <p>Assumptions: three of the request's five components are written onto the row here. The
     * concurrency counter is deliberately not one of them -- it is owned by the persistence provider,
     * and the value the request carries is what the service layer compares in order to decide whether
     * this update may proceed at all, so writing it would overwrite the very evidence the comparison
     * rests on. The card number and the account identifier are not written either, and cannot be: the
     * entity exposes no mutator for either, the number being this table's primary key.</p>
     *
     * @param request the validated update to apply, which must not be {@code null}
     * @param card the stored card row to apply it onto, which must not be {@code null}
     * @return the same instance that was passed in, now carrying the submitted values; it is not a
     *     copy, so a caller holding a managed entity has already changed what will be written
     * @throws NullPointerException if either argument is {@code null}, or if the row carries no stored
     *     expiration date for the day to be preserved from
     * @throws NumberFormatException if the request's expiration month or year is not numeric, which its
     *     own constraints admit no way of reaching
     * @throws java.time.DateTimeException if the request's expiration month or year falls outside the
     *     range a calendar month can occupy, which its own constraints likewise admit no way of
     *     reaching
     */
    public Card applyUpdate(CardUpdateRequest request, Card card) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(card, "card must not be null");

        // WHY : Assumptions: this method converts and does not validate, and it depends on two external
        //       contracts to make that safe. The request record carries the whole domain of each
        //       component as bean-validation constraints -- letters and spaces within fifty characters,
        //       a status of Y or N, a month of 01 to 12 and a year of 1950 to 2099 -- and the service
        //       layer has rejected a request that failed any of them before calling here. Each of those
        //       four domains is a transcription of one baseline edit paragraph, invoked from the block
        //       at app/cbl/COCRDUPC.cbl:698-708: 1230-EDIT-NAME at :806, 1240-EDIT-CARDSTATUS at :845
        //       whose 88-level at :91 admits 'Y' and 'N' and is the provenance of the check constraint
        //       on this column, 1250-EDIT-EXPIRY-MON at :877 whose 88-level at :95 admits 1 through 12,
        //       and 1260-EDIT-EXPIRY-YEAR at :913 whose 88-level at :99 admits 1950 through 2099.
        // WHY : Alternatives Considered: re-checking those four domains here as a defensive measure.
        //       Rejected because it would make this class a SECOND place each rule is written, and two
        //       statements of one rule diverge the first time only one is edited -- with the copy here
        //       being the one no request ever reaches, since a request that failed the first copy never
        //       arrives. The refusals below are of a different kind and are kept: they guard the
        //       WIDTH and shape of what this class itself renders, which is this class's own output and
        //       nobody else's rule.
        card.setEmbossedName(request.embossedName());
        card.setActiveStatus(request.activeStatus());

        // WHY : Assumptions: the day of the month is taken from the value already stored and never from
        //       the request, and this is a documented behavioural divergence rather than a
        //       transcription. What the baseline does: it snapshots the stored date as three parts,
        //       CCUP-OLD-EXPIRAION-DATE at app/cbl/COCRDUPC.cbl:297 being a four-character year at
        //       :298, a two-character month at :299 and a two-character day at :300 -- eight characters
        //       where the stored field is ten, the two hyphens not being snapshotted, which is why the
        //       comparison at :1503-1508 reads positions (1:4), (6:2) and (9:2) and steps over
        //       positions 5 and 8. It does capture a typed day at :621, notably without the blank
        //       normalisation its month at :623-628 and its year at :630 onward each receive, and it
        //       does concatenate that day back into the record at :1467-1474. Yet it never shows the
        //       typed day back: :1285 renders the field non-display, the four attribute lines that
        //       would have shown it are commented out, its own comment at :1119-1120 says the field is
        //       one the user is not being allowed to change, the line that would redisplay the typed
        //       day at :1122 is commented out while the line redisplaying the stored day at :1123 is
        //       live, and there is no 1270-EDIT paragraph at all, so the typed day is never validated.
        //       Neither app/cpy-bms/COCRDSL.CPY nor app/cpy-bms/COCRDLI.CPY carries a day field.
        //       What the Java implements: the stored day is preserved and no day is ever accepted from
        //       a caller, which is why CardUpdateRequest carries a month and a year and no day
        //       component. The divergence is registered in
        //       docs/architecture/cobol-to-service-traceability.md.
        // WHY : Assumptions: the day is not read from the wall clock either. Doing so would make the
        //       same request produce a different stored row depending on when it was replayed, which is
        //       the property that makes an update impossible to reason about after the fact.
        card.setExpirationDate(expirationDatePreservingStoredDay(
                request.expirationMonth(), request.expirationYear(), card.getExpirationDate()));

        return card;
    }

    /**
     * Converts a page of stored cards into a page of listing rows, omitting any row whose stored key is
     * outside the published card-number domain.
     *
     * <p>Assumptions: the row ORDER is preserved exactly and one listing row is produced per convertible
     * stored card, so the boundary tokens stay sound: the envelope requires a page with rows to name both
     * of its boundaries, the tokens are sealed from the stored rows before this conversion runs, and each
     * still names a row of the ordered set whether or not this method could render it. A page therefore
     * remains continuable in both directions even when a row is omitted from it.</p>
     *
     * <p>Refactoring Rationale: an unrenderable row is OMITTED here, where this method previously let the
     * refusal from {@link #toSummary(Card)} propagate and take the whole page with it. Runtime testing
     * reported the consequence: a single stored key that is not sixteen digit characters -- an alphabetic
     * sixteen, or a fifteen-digit value blank-padded into the fixed-width column -- cannot satisfy the
     * masked rendering the listing row's own constructor requires, so every caller of the browse received
     * a server failure and no rows at all, including callers narrowing to accounts the offending row has
     * nothing to do with. One row of one account denying the endpoint to everybody is a worse answer than
     * a page missing that row, so the page is served and the row is recorded.</p>
     *
     * <p>Assumptions: this path is unreachable in a conforming database and is defence in depth rather
     * than a substitute for the guard that closes it. The domain is closed at the point data enters, by
     * {@code ck_cards_card_num_digits} in
     * {@code src/main/resources/db/migration/V2__card_num_digit_domain.sql}, which the bulk load also
     * passes through -- and that ordering matters: a check the loader cannot bypass is what makes a
     * non-conforming key impossible, while this branch only decides what happens if one exists anyway,
     * from a database migrated before that constraint or altered outside the migration.</p>
     *
     * <p>Trade-offs: the single-row conversions are deliberately NOT given the same treatment. A request
     * naming exactly one card has no remaining rows to serve, so {@link #toSummary(Card)} and
     * {@link #toDetail(Card)} keep refusing, and the refusal is honest rather than an empty body a caller
     * would read as a card with no members. What is accepted here is that a page can be one row shorter
     * than the query selected, without the response saying so: the envelope has no member able to report a
     * row it could not render, and adding one would change a shape every consumer of it declares. The
     * omission is therefore reported to the operational record instead, which is where a data fault this
     * service cannot repair belongs.</p>
     *
     * @param page the page of stored cards as the caller's query settled it, which must not be
     *     {@code null}
     * @return a page carrying one listing row per convertible stored card and the same boundary tokens and
     *     further-page indication, never {@code null}
     * @throws NullPointerException if {@code page} is {@code null}, or if any column the contract marks
     *     required is unset on one of its rows
     */
    public PageResponse<CardSummary> toSummaryPage(PageResponse<Card> page) {
        Objects.requireNonNull(page, "page must not be null");

        List<Card> rows = page.items();
        List<CardSummary> items = new ArrayList<>(rows.size());
        for (Card row : rows) {

            // WHY : Assumptions: the test is on the STORED key's own characters and not on a caught
            //       refusal from the conversion. Catching would also swallow a rendering failure with a
            //       different cause -- a sealer that has come apart from the selector shape its consumers
            //       declare, say -- and turn a defect in this service into rows quietly missing from a
            //       page. Testing the one condition that is a property of the DATA keeps every other
            //       cause loud.
            if (!renderableCardNumber(row.getCardNum())) {

                // WHY : Assumptions: the record names the account and the stored width and NOTHING of the
                //       key itself. A value reaching this branch is not a card number, but it is a value
                //       from the card master and may be a mistyped or mis-offset one, so quoting it would
                //       write cardholder credential material into a durable record -- the one destination
                //       the masking everywhere else in this class exists to keep it out of. The account
                //       and the width are what an operator needs to find the row with the query the
                //       migration's own header states.
                LOG.warn("event=card.list.row.unrenderable reason=card-number-outside-domain"
                        + " accountId={} storedWidth={}", row.getAccountId(),
                        row.getCardNum() == null ? 0 : row.getCardNum().length());
                continue;
            }
            items.add(toSummary(row));
        }

        // WHY : Trade-offs: the two boundary tokens are carried across VERBATIM -- neither masked, nor
        //       re-sealed, nor inspected -- even though every row's card number in the same response is
        //       masked. Masking them uniformly was the alternative and would silently break paging,
        //       because a caller can only continue from a position the server can reconstruct and no
        //       position can be reconstructed from a masked value. The compromise this leaves is
        //       narrower than it first appears, and it is worth naming exactly: these tokens are not
        //       raw keys. The contract types them as an opaque paging position bound to the query, the
        //       caller and the direction it was minted for, and the envelope's own constructor refuses
        //       a component that is not one, so a card number cannot travel in either of them. What is
        //       accepted instead is that this class cannot verify them: it holds no binding and no
        //       cursor key material, so it trusts whichever component minted them and re-publishes
        //       them. That is why the shape check lives in the envelope, which every instance passes
        //       through, rather than here, which only this one conversion passes through.
        // WHY : Assumptions: the tokens are minted by the caller and not by this class, which is what
        //       keeps the direction out of this method. The forward position and the backward position
        //       are bound to different directions, and only the component that issued the query knows
        //       which way it read; a mapper that minted them would have to be told, and would then be
        //       able to mint the wrong one.
        return new PageResponse<>(items, page.firstKey(), page.lastKey(), page.hasNext());
    }

    /**
     * Opens an opaque card selector back into the card number it stands for.
     *
     * <p>Assumptions: this is the inverse of the sealing every response performs, and the two exist as
     * a pair because the contract addresses each single-card operation by a selector rather than by a
     * card number. The reason the contract does so is recorded there: a card number in a path or a
     * query is written into a durable access-log object by the load balancer before any application
     * code could redact it, whereas a selector discloses nothing to a party without the deployment's
     * key.</p>
     *
     * @param selector the selector exactly as the client echoed it back, which must not be
     *     {@code null}
     * @return the sixteen digit characters of the card number the selector stands for, never
     *     {@code null}
     * @throws NullPointerException if {@code selector} is {@code null}
     * @throws ClientInputException if the value is not a sealed selector, or cannot be opened under
     *     this deployment's key and this class's purpose, which is a client-correctable condition and
     *     is reported as one
     */
    public String openCardSelector(String selector) {
        Objects.requireNonNull(selector, "selector must not be null");

        try {
            return cardSelectorSealer.open(SELECTOR_PURPOSE, selector);
        } catch (IllegalArgumentException refused) {
            // WHY : Trade-offs: the refusal quotes neither the offered value nor the sealer's own
            //       account of what was wrong with it, and it carries no cause. A rejected selector is
            //       attacker-supplied text, and the one thing it most often is in practice is a raw
            //       card number a client assigned to the wrong field -- so echoing it would copy the
            //       number this class masks everywhere else into an error body and a log line, the two
            //       destinations that masking does not reach. What is given up is the detail an
            //       operator could have read directly from the message; what stands in for it is the
            //       stable code, which identifies every refusal of this kind and is what such an
            //       operator correlates on.
            // WHY : Assumptions: the condition is reported as client input rather than as a fault,
            //       because a selector legitimately stops opening -- a deployment key is rotated, or a
            //       bookmarked route is followed later -- and the remedy is the client's: list the
            //       cards again and take the selector the row carries.
            throw new ClientInputException(SELECTOR_REFUSAL_CODE, SELECTOR_FIELD,
                    "the card selector presented is not one this service issued, or can no longer be"
                            + " opened; list the cards again and use the selector carried by the row");
        }
    }

    /**
     * Seals one card number into the opaque selector a response publishes.
     *
     * @param cardNumber the stored card number to seal, which must not be {@code null} or blank
     * @return the sealed selector, being the value the contract's selector schema describes, never
     *     {@code null}
     * @throws NullPointerException if {@code cardNumber} is {@code null}
     * @throws IllegalArgumentException if {@code cardNumber} is blank or longer than the sealer admits,
     *     neither of which a stored card number can be
     * @throws IllegalStateException if the platform cannot perform the sealing, which is a deployment
     *     fault and not a caller fault, and is therefore left to propagate unchanged
     */
    // WHY : Assumptions: a selector is minted per response rather than stored, and it is STABLE for one
    //       card, which the contract relies on when it calls a route built from one bookmarkable. That
    //       stability is a property of the sealer under a fixed key and purpose, not of this class.
    private String sealCardSelector(String cardNumber) {
        return cardSelectorSealer.seal(SELECTOR_PURPOSE, cardNumber);
    }

    /**
     * Reports whether a stored card number is inside the domain this class can render.
     *
     * <p>Assumptions: the test is on the stored characters and answers one question only -- whether
     * masking this value yields the shape {@code CardSummary} accepts. It does so for a value of exactly
     * sixteen digit characters and for nothing else: the masker preserves the width of what it hides, so
     * a narrower value masks to a narrower rendering and a value carrying a non-digit in its last four
     * positions masks to a rendering with a non-digit in them, and the response record refuses both.</p>
     *
     * <p>Trade-offs: this reports rather than throws, so the caller decides what an out-of-domain row
     * means. That is the whole point of separating it: the paged conversion omits such a row and serves
     * the page, while the two single-row conversions have nothing left to serve and let the response
     * record's own refusal stand.</p>
     *
     * @param cardNumber the stored card number to test, which may be {@code null}
     * @return {@code true} when the value is exactly sixteen digit characters; {@code false} when it is
     *     {@code null} or anything else
     */
    private static boolean renderableCardNumber(String cardNumber) {
        return cardNumber != null && CARD_NUMBER_DOMAIN_PATTERN.matcher(cardNumber).matches();
    }

    /**
     * Renders a stored account identifier as the fixed-width digit string the contract publishes.
     *
     * @param accountId the stored account identifier, which must not be {@code null} and must not be
     *     negative
     * @return exactly eleven digit characters with leading zeros preserved, never {@code null}
     * @throws NullPointerException if {@code accountId} is {@code null}
     * @throws IllegalArgumentException if {@code accountId} is negative or occupies more than eleven
     *     digits, neither of which the record layout admits and neither of which could be published in
     *     the declared width
     */
    private static String accountIdDigits(Long accountId) {
        Objects.requireNonNull(accountId, "account identifier must not be null");

        // WHY : Assumptions: identifiers are published as digit characters and never as JSON numbers,
        //       which is how the baseline itself holds them. app/cpy/CVCRD01Y.cpy declares CC-ACCT-ID as
        //       PIC X(11) at :34-35 and overlays CC-ACCT-ID-N as PIC 9(11) on the same bytes by
        //       REDEFINES at :36, and does the same for the card number at :37-39 and the customer
        //       identifier at :40-42; app/cbl/COCRDLIC.cbl:99-101 repeats the pattern for this very
        //       field. Characters are therefore the transport form and the number is only what
        //       arithmetic and validation see. Two consequences follow and either alone settles it: a
        //       leading zero is data here and every numeric type discards it, and a sixteen-digit card
        //       number exceeds the largest integer an IEEE-754 double represents exactly, so a client
        //       that parsed such a value as a JSON number would read back a different card than the one
        //       it was sent.
        // WHY : Trade-offs: the width is asserted rather than assumed, so an identifier too wide to
        //       publish is refused here instead of shipping as a body that violates the contract's own
        //       pattern. This is a guard on what this class renders and not a re-statement of anyone
        //       else's validation rule: the column is BIGINT and so admits values the eleven-digit
        //       record field does not, and the response record does not check this component, so
        //       nothing else in the path would notice.
        if (accountId < 0) {
            throw new IllegalArgumentException(
                    "account identifier is negative; the record field is unsigned and an unsigned"
                            + " rendering has no position for a sign");
        }

        String digits = String.format("%0" + ACCOUNT_ID_WIDTH + "d", accountId);
        if (digits.length() != ACCOUNT_ID_WIDTH) {
            throw new IllegalArgumentException(
                    "account identifier occupies " + digits.length() + " digits where the record field"
                            + " declares " + ACCOUNT_ID_WIDTH);
        }
        return digits;
    }

    /**
     * Renders a stored expiration date as the ten characters the contract publishes.
     *
     * @param expirationDate the stored expiration date, which must not be {@code null}
     * @return exactly ten characters, being a four-digit year, a two-digit month and a two-digit day
     *     separated by hyphens, never {@code null}
     * @throws NullPointerException if {@code expirationDate} is {@code null}
     * @throws IllegalArgumentException if the date does not render in exactly ten characters, which
     *     happens only for a year outside the four-digit range and which the declared field width
     *     cannot carry
     */
    private static String isoExpirationDate(LocalDate expirationDate) {
        Objects.requireNonNull(expirationDate, "expiration date must not be null");

        // WHY : Assumptions: the stored date is rendered whole and is never reassembled from parts. The
        //       baseline's detail map shows a month and a year only -- app/cpy-bms/COCRDSL.CPY carries
        //       EXPMONI and EXPYEARI and no day field -- whereas the contract publishes all ten
        //       characters of the stored value. That is a target-side presentation decision settled by
        //       card-api.yaml, and honouring it means emitting what the column holds rather than
        //       composing a value from the two parts a screen happened to display.
        // WHY : Assumptions: the platform's own ISO rendering is used rather than a formatter declared
        //       here, because it emits the year, month and day in that order with each part zero-padded
        //       to its declared width, which is exactly the ten-character form the field carries. It
        //       widens the year beyond four digits for dates far outside this domain, which is the one
        //       case the width refusal below exists to catch.
        String rendered = expirationDate.toString();
        if (rendered.length() != EXPIRATION_DATE_WIDTH) {
            throw new IllegalArgumentException(
                    "expiration date renders in " + rendered.length() + " characters where the record"
                            + " field declares " + EXPIRATION_DATE_WIDTH);
        }
        return rendered;
    }

    /**
     * Composes a new expiration date from a submitted month and year over the stored day.
     *
     * @param expirationMonth the submitted month as two digit characters, which must not be
     *     {@code null}
     * @param expirationYear the submitted year as four digit characters, which must not be
     *     {@code null}
     * @param storedExpirationDate the date currently stored on the row, whose day is the only part of
     *     it carried forward; must not be {@code null}
     * @return the composed date, carrying the submitted month and year and the stored day, never
     *     {@code null}
     * @throws NullPointerException if any argument is {@code null}
     * @throws NumberFormatException if either submitted part is not numeric, which the request's own
     *     constraints admit no way of reaching
     * @throws java.time.DateTimeException if the submitted month or year falls outside the range a
     *     calendar month can occupy, which those constraints likewise admit no way of reaching
     */
    private static LocalDate expirationDatePreservingStoredDay(
            String expirationMonth, String expirationYear, LocalDate storedExpirationDate) {
        Objects.requireNonNull(expirationMonth, "expiration month must not be null");
        Objects.requireNonNull(expirationYear, "expiration year must not be null");
        Objects.requireNonNull(
                storedExpirationDate, "stored expiration date must not be null");

        YearMonth target =
                YearMonth.of(Integer.parseInt(expirationYear), Integer.parseInt(expirationMonth));

        // WHY : Trade-offs: where the stored day does not exist in the submitted month, the day is
        //       brought back to that month's last day rather than the update being refused. The two
        //       alternatives were both weighed. Refusing would fail an update over a value the caller
        //       was never shown and cannot edit -- the day is non-display in the baseline and absent
        //       from the request shape -- so the caller could not act on the refusal. Carrying the day
        //       through unchanged is not available at all: the target column is a true date and the
        //       impossible combination the baseline can hold, because it concatenates characters into a
        //       ten-byte field at app/cbl/COCRDUPC.cbl:1467-1474 without consulting a calendar, has no
        //       representation here. Bringing the day back preserves the two parts the caller did
        //       choose, which is the outcome closest to what was asked for. The cost is that one
        //       specific day value is not round-tripped exactly, and it is the one part of the date
        //       nothing in the baseline lets a user set.
        // WHY : Assumptions: the day is bounded against the target month explicitly rather than by
        //       adjusting the stored date one field at a time. Adjusting the year and then the month
        //       brings the day back twice for a leap day, and adjusting them in the other order brings
        //       it back once, so the two orders disagree; computing the bound against the settled
        //       target month has no order to get wrong.
        int day = Math.min(storedExpirationDate.getDayOfMonth(), target.lengthOfMonth());
        return target.atDay(day);
    }
}
