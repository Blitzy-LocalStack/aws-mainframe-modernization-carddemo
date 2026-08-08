package com.carddemo.account.mapper;

import com.carddemo.account.domain.CardXref;
import com.carddemo.account.dto.CardXrefResponse;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Translates stored card cross-reference rows into the response shape this context publishes.
 *
 * <h2>Purpose</h2>
 *
 * <p>This class is the anti-corruption layer for one baseline record, the card cross-reference, and it is
 * the only place in this service where that record's representation concerns are allowed to appear. It
 * converts {@link CardXref}, the row this context stores, into {@link CardXrefResponse}, the shape this
 * context publishes, and it does nothing else: it reads no row, transcribes no business rule and decides
 * no HTTP status. Both sibling charters point here and leave no gap on either side --
 * {@code com.carddemo.account.dto} names this package at L129 of its descriptor as the sole boundary at
 * which representation concerns may appear, and {@code com.carddemo.account.domain} states at L305 to
 * L308 that it holds no mapper and no codec and assigns trailing-blank handling, dropped padding and
 * masking here by name.</p>
 *
 * <p>Three of this record's four fields are NOT carried straight through, and each of the three is a
 * judgement rather than a mechanical step: the padding field is dropped, the primary account number is
 * masked to its last four digits, and the two numeric identifiers become fixed-width digit text. Every
 * one of those carries its justification at the statement that performs it, so a reader auditing one
 * field finds the reason beside that field rather than only in this charter.</p>
 *
 * <h2>The record being translated, and why its geometry is settled</h2>
 *
 * <p>Assumptions: {@code app/cpy/CVACT03Y.cpy} is the normative contract, so a field's declared picture
 * decides its published form rather than being reconciled with it afterwards. That copybook declares
 * {@code 01 CARD-XREF-RECORD} at L4 and then exactly four fields: {@code XREF-CARD-NUM PIC X(16)} at L5,
 * {@code XREF-CUST-ID PIC 9(09)} at L6, {@code XREF-ACCT-ID PIC 9(11)} at L7 and
 * {@code FILLER PIC X(14)} at L8. The three named widths total 16 plus 9 plus 11, which is 36, and the
 * padding brings that to 36 plus 14, which is 50.</p>
 *
 * <p>Assumptions: that 50 is settled by three independent statements that agree, which is why the
 * geometry is asserted here without hedging. The copybook's own header comment states the record length
 * as 50 at L2. {@code app/cbl/CBACT03C.cbl} then describes the same physical record with a completely
 * different decomposition, as {@code FD-XREF-CARD-NUM PIC X(16)} at L39 followed by a single opaque
 * {@code FD-XREF-DATA PIC X(34)} at L40, where 16 plus 34 is again 50 and that 34 is precisely the 9 and
 * the 11 of the two identifiers plus the 14 of the padding. Two unrelated declarations of one record
 * arriving at the same total, one of which never names the inner fields at all, is the strongest
 * evidence available for the geometry, and it is what makes the padding safe to drop rather than merely
 * convenient to ignore.</p>
 *
 * <p>Assumptions: the card number is this record's key and the account identifier is not.
 * {@code app/cbl/CBACT03C.cbl} declares {@code RECORD KEY IS FD-XREF-CARD-NUM} at L32 for the file whose
 * layout it copies in at L45, opening it at L30 and L31 as an indexed file read sequentially. That is
 * why the single-row translation below is keyed on a card while the by-account translation answers with
 * a list of zero or more rows.</p>
 *
 * <h2>Why this class exists at all, rather than one copy of the layout per program</h2>
 *
 * <p>Refactoring Rationale: the baseline shares this record by textual inclusion, so every program that
 * touches it re-declares the whole layout inside itself and any handling of a field is repeated wherever
 * it is needed. Exactly three of those inclusion sites belong to this bounded context --
 * {@code app/cbl/COACTVWC.cbl} at L251, {@code app/cbl/COACTUPC.cbl} at L643 and
 * {@code app/cbl/CBACT03C.cbl} at L45 -- and the migration collapses those three into the one import of
 * {@link CardXref} at the head of this file. The record is included at further sites elsewhere in the
 * baseline, but those belong to other bounded contexts and are claimed by their own services, not by this
 * one. That collapse is the whole reason this class is worth having: with one type describing the row,
 * the representation of the row can be decided once, here, instead of three times in three programs that
 * have no way to agree with each other.</p>
 *
 * <h2>What this record deliberately does not carry</h2>
 *
 * <p>Assumptions: no amount reaches this class, so no monetary type is imported and none is needed.
 * {@code app/cpy/CVACT03Y.cpy} declares no {@code PIC S9(n)V99} field anywhere across L5 to L8. The
 * money of this bounded context sits on the account record instead, whose {@code ACCT-CURR-BAL} at
 * {@code app/cpy/CVACT01Y.cpy} L7 is the migration's normative exemplar and is handled by the account
 * mapper in this same package. The absence is recorded so that it reads as a property of the
 * cross-reference record rather than as an oversight, and so that no later edit reaches for an amount
 * type here believing one was forgotten.</p>
 *
 * <p>Assumptions: no value here is encrypted, because no field of this record is a national or
 * government-issued identifier. Those two live on the customer record, at {@code app/cpy/CVCUS01Y.cpy}
 * L17 and L18, and the customer mapper in this same package owns them. This record likewise declares no
 * card verification value across L5 to L8, so there is nothing here to suppress. Stating both absences
 * matters more than it appears: this class sits at the boundary where cryptography and suppression are
 * the expected concerns, so silence would read as a step omitted.</p>
 *
 * <p>Assumptions: no field of this record is renamed, so no correction happens here.
 * {@code app/cpy/CVACT03Y.cpy} spells all four of its fields correctly, and the single rename this
 * package owns belongs to the account record and to the account mapper. The sign conventions and packed
 * representations that the shared codecs exist for are equally absent, because every field of this
 * record is either character data or an unsigned display numeric.</p>
 *
 * <h2>Why this translation is hand-written</h2>
 *
 * <p>Alternatives Considered: generating this translation with a mapping framework, which would reduce
 * the single-row method to a declaration. Rejected, and this record shows exactly why: the translation
 * drops a padding field, masks a primary account number to its last four digits and re-establishes two
 * declared digit widths, so it is not a field-name-to-field-name copy in any of its three interesting
 * places. Each of those needs a sentence beside it naming the baseline line it answers to, and a
 * generated member has nowhere to hold one. {@code docs/CODE_DOCUMENTATION_STANDARD.md} records the same
 * conclusion for the migration as a whole at L443 to L448, and it adds a second, mechanical reason for
 * this file specifically: the documentation gate is configured with no accessor exemption at all, so a
 * generated member would fail it rather than merely go undocumented.</p>
 *
 * <p>Alternatives Considered: an annotation processor such as Lombok to remove the constructor and any
 * accessors. Rejected on the same mechanical ground, recorded at
 * {@code docs/CODE_DOCUMENTATION_STANDARD.md} L439 to L442: a generated member cannot carry a docstring,
 * and {@code config/checkstyle/checkstyle.xml} configures {@code MissingJavadocMethod} with
 * {@code allowMissingPropertyJavadoc} false at L365 and clears {@code allowedAnnotations} entirely at
 * L366, so the usual exemptions a generator relies on are not available here.</p>
 *
 * <h2>The access path this class serves, and the one it knows nothing about</h2>
 *
 * <p>Refactoring Rationale: the baseline surfaces the by-account read as a file resource in its own
 * right rather than as a query. {@code app/csd/CARDDEMO.CSD} defines the base cross-reference cluster
 * {@code CCXREF} at L37 and, separately, {@code CXACAIX} at L63, described at L64 as the alternate index
 * to that cluster by account key. The migration replaces the second with the non-unique index
 * {@code idx_card_xref_account_id}, declared at L726 of this service's schema migration, which
 * PostgreSQL maintains transactionally so that the baseline's separate index-build step retires with no
 * target. Non-uniqueness is load-bearing, because one account holds many cards and therefore many
 * cross-reference rows.</p>
 *
 * <p>Assumptions: this class is index-agnostic and contains no query logic whatsoever. It translates
 * whatever rows the repository hands it, in whatever order they arrive, so a reader looking for how the
 * by-account read is satisfied will not find it here and should read
 * {@code com.carddemo.account.repository.CardXrefRepository} instead. The paging envelope is equally
 * absent: the list method below returns a plain list, and the keyset envelope is assembled one layer
 * above by the caller, which is why this file declares no dependency on it.</p>
 *
 * <h2>What differs from the baseline storage, deliberately</h2>
 *
 * <p>Trade-offs: the rows this class translates are held with stronger isolation and durability than the
 * baseline gave them, and that is a documented correction rather than a port. Both cross-reference
 * resources are defined with an uncommitted read and no recovery -- {@code READINTEG(UNCOMMITTED)} at
 * {@code app/csd/CARDDEMO.CSD} L40 for {@code CCXREF} and L66 for {@code CXACAIX}, with
 * {@code RECOVERY(NONE)} at L46 and L72 respectively, and {@code JOURNAL(NO)} at L44 and L70. The target
 * reads at PostgreSQL's default committed isolation, which is strictly stronger, and encrypts at rest
 * with automated backups where the baseline had neither. What the correction costs is honest and is
 * accepted: a value this class publishes was read under a stricter isolation than the baseline would
 * have applied, so a comparison of the two systems under concurrent update is not a comparison of like
 * with like. Nothing in this context depends on observing an uncommitted row, so the weaker behaviour is
 * not re-imposed to preserve a similarity no caller wants.</p>
 */
@Component
// WHY : Alternatives Considered: declaring this class final, which is the natural reading of a type with
//       no subclass and no intended extension. Not done, for two specific reasons rather than a
//       preference. The container creates a runtime subclass of a bean whenever an aspect is applied to
//       it, and a final class cannot be subclassed, so sealing this type would convert any later
//       cross-cutting concern -- a timing metric, an audit record -- from a configuration change into a
//       source change here. The second reason is consistency that can be checked: AccountContextMapper
//       and AccountInquiryReplyMapper in this same package are both declared exactly this way, and a
//       third mapper differing in its declaration would invite a reader to look for a reason that does
//       not exist. The accepted cost is that extension is possible in principle; it is bounded by the
//       class holding no mutable state for a subclass to disturb.
public class CardXrefMapper {

    /**
     * Holds the number of trailing digits of a primary account number a published response may disclose.
     */
    // WHY : Trade-offs: four is the disclosure the security design allows outside the administrative
    //       card-detail endpoint, and it is the same count the entity applies to its own diagnostic
    //       rendering, so one card number is never disclosed to two different depths by two paths in one
    //       service. Fewer digits would leave a caller unable to recognise a row it already holds, and
    //       more would begin reconstructing a value that app/cpy/CVACT03Y.cpy L5 declares as a whole
    //       sixteen-character primary account number.
    private static final int DISCLOSED_SUFFIX_LENGTH = 4;

    /**
     * Holds the marker a masked primary account number is prefixed with, standing for the withheld digits.
     */
    // WHY : Assumptions: the marker is a fixed four-character string and is deliberately NOT sized from
    //       the sixteen-character field width. Padding it to twelve characters would let a caller
    //       recover the original width by measuring the result, and the published contract on
    //       CardXrefResponse promises the last four digits rather than a positional rendering of the
    //       whole field, so a width-preserving mask would over-promise.
    private static final String MASK_MARKER = "****";

    /**
     * Holds the declared digit width of the customer identifier, nine.
     */
    // WHY : Assumptions: nine is read from XREF-CUST-ID PIC 9(09) at app/cpy/CVACT03Y.cpy L6 and is a
    //       fixed width rather than a maximum, which is why it is applied as padding below instead of
    //       being validated as an upper bound only.
    private static final int CUSTOMER_ID_WIDTH = 9;

    /**
     * Holds the declared digit width of the account identifier, eleven.
     */
    // WHY : Assumptions: eleven is read from XREF-ACCT-ID PIC 9(11) at app/cpy/CVACT03Y.cpy L7. It
    //       exceeds the range of a thirty-two-bit integer, which is why the entity holds this
    //       identifier in a wider integral type and why the conversion below cannot narrow it.
    private static final int ACCOUNT_ID_WIDTH = 11;

    /**
     * Creates the mapper, which holds no state and collaborates with nothing.
     *
     * <p>Assumptions: this constructor is declared explicitly and left empty on purpose, because the
     * absence of any collaborator is a design fact about this class rather than an accident of it not
     * needing one yet. Translating this record requires no cryptography, no monetary conversion and no
     * lookup, so there is nothing to inject; every field it publishes is derived from the row handed to
     * it. Holding no state is also what makes a single shared instance safe to call from many request
     * threads at once.</p>
     *
     * <p>Alternatives Considered: exposing this behaviour as static methods on a utility class and
     * declaring no bean at all, which for a stateless translation is a reasonable shape. Rejected so
     * that a caller receives this mapper by constructor injection and can be exercised against a
     * substitute without starting a container, which is the same reason every other collaborator in this
     * migration is injected rather than reached statically. That choice is also what the baseline's
     * static linkage between programs is being replaced with, so keeping it a bean is the consistent
     * reading of the migration rather than a local taste.</p>
     */
    public CardXrefMapper() {
        // WHY : Assumptions: the body is empty because there is no state to establish. A no-argument
        //       constructor that assigned nothing would be indistinguishable from the implicit default
        //       one, and it is written out anyway so that the two paragraphs above have somewhere to
        //       live at the member they describe rather than in the class charter.
    }

    /**
     * Converts one stored cross-reference row into the response this context publishes for it.
     *
     * <p>Assumptions: the row handed in has been read from the database, so its three columns are
     * populated. All three are declared not-null in this service's schema migration, which is why a
     * missing value below is reported as a broken guarantee rather than rendered as a blank.</p>
     *
     * @param row the stored cross-reference row to translate, of type
     *     {@link com.carddemo.account.domain.CardXref}; must not be {@code null}
     * @return the published response of type {@link com.carddemo.account.dto.CardXrefResponse}, carrying
     *     the masked card number and the two fixed-width identifiers, never {@code null}
     * @throws NullPointerException if {@code row} is {@code null}, because an absent row is a not-found
     *     answer for the caller to decide on and not a value that can be translated
     * @throws IllegalStateException if any of the row's three columns is {@code null}, or if the card
     *     number is too short to take a four-digit suffix from, or if either identifier needs more
     *     digits than its baseline field declares
     */
    public CardXrefResponse toCardXrefResponse(CardXref row) {
        Objects.requireNonNull(row, "row must not be null");

        // WHY : Assumptions: the fourth field, FILLER PIC X(14) at app/cpy/CVACT03Y.cpy L8, is never
        //       read here and has no component to be read into. It is padding to a fixed physical record
        //       length rather than data the row carries, and the arithmetic proves it accounts for
        //       nothing else: the three named widths are 16 plus 9 plus 11, which is 36, and 36 plus the
        //       14 of the padding is the 50 that L2 of the same copybook declares as the record length.
        //       app/cbl/CBACT03C.cbl corroborates from a second direction, describing the same record as
        //       a 16-byte key at L39 and one opaque PIC X(34) remainder at L40, so the 14 is inside a
        //       span that program never decomposes at all. The drop is recorded here, at the statement
        //       that declines to read it, because a silently absent field is indistinguishable from a
        //       forgotten one.
        return new CardXrefResponse(
                // WHY : Trade-offs: the primary account number is published masked, and this class
                //       offers no unmasked route to it. The baseline does the opposite:
                //       app/cbl/COACTVWC.cbl L740 moves XREF-CARD-NUM whole into CDEMO-CARD-NUM,
                //       which is declared PIC 9(16) in the passed communication area at
                //       app/cpy/COCOM01Y.cpy L40 to L41 -- storage the terminal echoes back, so the
                //       complete sixteen-character value travels to the client and returns from it on
                //       every screen turn. Narrowing that costs something real and it is accepted: a
                //       caller cannot reconcile a returned row against a whole card number it does not
                //       already hold. The administrative path that does answer with a whole primary
                //       account number lives in the card bounded context, not in this one, so adding an
                //       unmasked variant here would put one disclosure decision in two services with no
                //       single place to read it. Responses from this context are therefore masked
                //       unconditionally, with no flag and no caller-supplied override to get it wrong.
                maskToLastFourDigits(row.getCardNum()),
                // WHY : Assumptions: both identifiers are published as digit text of exactly the width
                //       their baseline fields declare, leading zeros included, rather than as numbers.
                //       app/cpy/CVACT03Y.cpy declares XREF-CUST-ID PIC 9(09) at L6 and XREF-ACCT-ID
                //       PIC 9(11) at L7, which are display numerics: characters in storage and numbers
                //       only inside arithmetic. The baseline says so itself by laying character
                //       REDEFINES pairs over exactly these values in the account-update before-image --
                //       app/cbl/COACTUPC.cbl holds the account identifier at L671 as PIC X(11)
                //       redefined at L672 to L673 as PIC 9(11), and the customer identifier at L710 as
                //       PIC X(09) redefined at L711 to L712 as PIC 9(09). The entity holds these as
                //       integral values because the columns are integral, and this class is the single
                //       point at which they regain their declared widths, so a stored identifier
                //       shorter than its field is padded back rather than published narrowed. Losing a
                //       leading zero would change a fixed-width key into a different string, and these
                //       keys are compared as text by every consumer that reads them by offset.
                unsignedDigits(row.getCustomerId(), CUSTOMER_ID_WIDTH, "customerId"),
                unsignedDigits(row.getAccountId(), ACCOUNT_ID_WIDTH, "accountId"));
    }

    /**
     * Converts a list of stored cross-reference rows into the published responses, preserving their order.
     *
     * <p>Assumptions: this serves the by-account read, which the repository answers with a plain ordered
     * list. An empty list is a legitimate answer rather than an error, because an account may have no
     * cards cross-referenced to it, and this method returns an empty list for that case rather than
     * refusing it.</p>
     *
     * <p>Assumptions: the keyset paging envelope is assembled by the caller and is deliberately neither
     * imported nor constructed here. {@code com.carddemo.common.web.PageResponse} is parameterised over
     * the response type at the service and controller signatures, which is the layer that knows how many
     * rows a page holds and which cursor positions bound it; this class only knows how to translate a
     * row. Depending on that envelope from here would pull a web concern into the translation layer and
     * would leave the single-row method above with cursor fields that could only ever be empty.</p>
     *
     * <p>Trade-offs: the element contract is enforced by delegating each row to
     * {@link #toCardXrefResponse(CardXref)} rather than by checking the elements in a loop here. That
     * keeps exactly one place where a row is validated, so the batch path cannot drift from the
     * single-row path as either changes. The cost accepted is that a refusal names the offending value
     * rather than its position in the list, which is a weaker diagnostic; it is accepted because a list
     * containing a null element indicates a defect in the caller assembling it rather than a data
     * condition a reader needs to locate by index.</p>
     *
     * @param rows the stored cross-reference rows to translate, a {@link java.util.List} of
     *     {@link com.carddemo.account.domain.CardXref}; must not be {@code null}, may be empty, and must
     *     contain no {@code null} element
     * @return an unmodifiable {@link java.util.List} of
     *     {@link com.carddemo.account.dto.CardXrefResponse} in the same order as {@code rows}, empty when
     *     {@code rows} is empty, never {@code null}
     * @throws NullPointerException if {@code rows} is {@code null}, or if any element of it is
     *     {@code null}
     * @throws IllegalStateException if any row fails the column guarantees described on
     *     {@link #toCardXrefResponse(CardXref)}
     */
    public List<CardXrefResponse> toCardXrefResponses(List<CardXref> rows) {
        Objects.requireNonNull(rows, "rows must not be null");

        // WHY : Trade-offs: the result is unmodifiable, which is what this pipeline produces and is kept
        //       rather than copied into a mutable list. A caller assembling a page has no reason to
        //       mutate the translated rows, and handing back a mutable list would invite it to, which
        //       would then be a second place the contents of a page could be decided. The accepted cost
        //       is that a caller wanting to add to the result must copy it first.
        return rows.stream().map(this::toCardXrefResponse).toList();
    }

    /**
     * Renders a primary account number as the withholding marker followed by its last four digits.
     *
     * <p>Assumptions: trailing blanks are stripped before the suffix is taken, and this is the reason the
     * method cannot simply take the final four characters. The column behind this value is a fixed-width
     * character column, which blank-pads on read, so a value shorter than its declared width comes back
     * padded and its final four characters would be spaces rather than digits. Handling that padding is
     * this package's stated responsibility: {@code com.carddemo.account.domain} assigns trailing-blank
     * handling here by name at L305 to L308 of its descriptor.</p>
     *
     * <p>Alternatives Considered: reusing the entity's own masking helper by widening its visibility,
     * since {@link com.carddemo.account.domain.CardXref} already renders a masked card number for its
     * diagnostic string. Rejected because the two have deliberately different failure contracts and
     * merging them would have to weaken one. The entity's renderer is reached from a logging path, so it
     * must never throw -- a failure there would destroy the very log entry being written -- and it
     * therefore answers with the marker alone when the value is absent or too short. A published
     * response cannot do that: answering with a bare marker would present a broken column as a
     * successfully masked card. This renderer refuses instead. Keeping them separate also keeps the
     * entity free of any transport concern, which its own charter and both sibling descriptors require.</p>
     *
     * @param cardNumber the stored primary account number of type {@link java.lang.String}, expected to
     *     be the sixteen characters that {@code XREF-CARD-NUM} declares; must not be {@code null}
     * @return the masked rendering of type {@link java.lang.String}, the marker followed by the last four
     *     digits, never {@code null}
     * @throws IllegalStateException if {@code cardNumber} is {@code null}, or if it holds fewer than four
     *     characters once trailing blanks are removed, because publishing a shorter value would disclose
     *     digits without identifying the row and publishing the marker alone would hide a broken column
     */
    private static String maskToLastFourDigits(String cardNumber) {
        if (cardNumber == null) {
            throw new IllegalStateException("cardNum is null, but it is this row's primary key and is"
                    + " declared NOT NULL in the schema and nullable = false on the entity");
        }
        String digits = cardNumber.stripTrailing();
        if (digits.length() < DISCLOSED_SUFFIX_LENGTH) {
            throw new IllegalStateException("cardNum holds " + digits.length()
                    + " characters once padding is removed, which cannot yield the "
                    + DISCLOSED_SUFFIX_LENGTH + " disclosed digits the published contract declares;"
                    + " the baseline field is a fixed sixteen characters wide");
        }
        return MASK_MARKER + digits.substring(digits.length() - DISCLOSED_SUFFIX_LENGTH);
    }

    /**
     * Renders an unsigned identifier as digit text padded with leading zeros to its declared width.
     *
     * <p>Assumptions: a value needing more digits than the field declares is refused rather than
     * truncated. Truncation would publish a syntactically valid identifier belonging to a different
     * customer or account, which is a silently wrong answer, whereas a refusal is a visible one.</p>
     *
     * @param value the stored identifier of type {@link java.lang.Long}; must not be {@code null}
     * @param width the declared digit width of the baseline field, an {@code int} count of digits, nine
     *     for the customer identifier and eleven for the account identifier
     * @param field the component name of type {@link java.lang.String}, so that a refusal names which of
     *     the two values was at fault
     * @return the zero-padded rendering of type {@link java.lang.String}, exactly {@code width}
     *     characters long, never {@code null}
     * @throws IllegalStateException if {@code value} is {@code null}, because the column behind it is
     *     declared not-null, or if it needs more than {@code width} digits
     */
    private static String unsignedDigits(Long value, int width, String field) {
        if (value == null) {
            throw new IllegalStateException(field + " is null, but it is declared NOT NULL in the schema"
                    + " and nullable = false on the entity");
        }
        String digits = String.valueOf(value.longValue());
        if (digits.length() > width) {
            throw new IllegalStateException(field + " occupies " + digits.length()
                    + " digits but the baseline field declares " + width
                    + "; truncating it would publish a different identifier");
        }
        // WHY : Assumptions: padding is applied on the left because the baseline field is an unsigned
        //       display numeric, in which the value is right-justified within its declared width and the
        //       unused leading positions hold zeros rather than spaces. Padding on the right would
        //       multiply the identifier by a power of ten as far as any consumer reading it as a number
        //       is concerned.
        return "0".repeat(width - digits.length()) + digits;
    }
}
