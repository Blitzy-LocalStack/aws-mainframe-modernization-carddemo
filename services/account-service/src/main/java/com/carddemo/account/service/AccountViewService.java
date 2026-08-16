package com.carddemo.account.service;

import com.carddemo.account.domain.Account;
import com.carddemo.account.domain.CardXref;
import com.carddemo.account.domain.Customer;
import com.carddemo.account.dto.AccountContextView;
import com.carddemo.account.dto.AccountViewResponse;
import com.carddemo.account.dto.CardXrefByAccountView;
import com.carddemo.account.dto.CardXrefResponse;
import com.carddemo.account.dto.CardXrefView;
import com.carddemo.account.dto.CustomerDisplayView;
import com.carddemo.account.dto.CustomerResponse;
import com.carddemo.account.mapper.AccountContextMapper;
import com.carddemo.account.mapper.AccountMapper;
import com.carddemo.account.mapper.CardXrefMapper;
import com.carddemo.account.mapper.CustomerMapper;
import com.carddemo.account.repository.AccountRepository;
import com.carddemo.account.repository.AccountScreenRow;
import com.carddemo.account.repository.CardXrefRepository;
import com.carddemo.account.repository.CustomerRepository;
import com.carddemo.common.error.AbendDetail;
import com.carddemo.common.error.ApiError;
import com.carddemo.common.validation.FieldValidationFlag;
import com.carddemo.common.web.CursorToken;
import com.carddemo.common.web.PageResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The account read path, transcribed from the online view program and the batch account reader.
 *
 * <h2>Purpose</h2>
 *
 * <p>This class carries the reads that the account view screen and two neighbouring bounded contexts
 * resolve against. Its two reference sources are {@code app/cbl/COACTVWC.cbl}, 941 lines, the CICS
 * transaction {@code CAVW} whose resource definition names it at L317 and L318 of
 * {@code app/csd/CARDDEMO.CSD}, and {@code app/cbl/CBACT01C.cbl}, 430 lines, the sequential reader of
 * the account master. Both lengths are the physical lengths of files this migration reads as its
 * specification and never modifies.
 *
 * <p>Each significant paragraph of the reference becomes one named method here, so the traceability
 * matrix at {@code docs/architecture/cobol-to-service-traceability.md} can cite a paragraph-to-method
 * pair rather than naming a class and leaving a reader to search it. The pairs are
 * {@code 2200-EDIT-MAP-INPUTS} at L622 and {@code 2210-EDIT-ACCOUNT} at L649 for the input edit,
 * {@code 9000-READ-ACCT} at L687 for the composition, and {@code 9200-GETCARDXREF-BYACCT} at L723,
 * {@code 9300-GETACCTDATA-BYACCT} at L774 and {@code 9400-GETCUSTDATA-BYCUST} at L825 for the three
 * keyed reads it drives.
 *
 * <h2>The three-hop composition, and why each hop is a repository call</h2>
 *
 * <p>Refactoring Rationale: the reference resolves one account view through three ordered keyed reads,
 * short-circuiting at each miss. {@code 9000-READ-ACCT} at L687 performs the cross-reference read at
 * L723, then the account master read at L774, then the customer master read at L825, and abandons the
 * sequence at L697 and L698, at L704 and L705, and at L713 and L714 respectively. Each read is an
 * {@code EXEC CICS READ} against a file name held as a literal -- {@code 'CXACAIX '} at L192 and L193,
 * {@code 'ACCTDAT '} at L184 and L185, {@code 'CUSTDAT '} at L188 and L189 -- and each becomes a
 * repository method here, which is the mapping transformation rule T5 assigns to that verb. The
 * ordering is preserved because it is load-bearing rather than incidental: the cross-reference read is
 * first because it is the only thing that yields the customer key the third read needs, which the
 * reference states at L737 through L740 by moving {@code XREF-CUST-ID} and {@code XREF-CARD-NUM} into
 * the selection context on the normal-response arm.
 *
 * <p>Refactoring Rationale: the first hop reaches the cross-reference BY ACCOUNT, and the base cluster
 * cannot answer that question. The reference reads it through a second access path, the alternate index
 * whose name is the literal at L192 and L193, supplying the account identifier as the record
 * identification field at L729 with its key length at L730. The target replaces that path with the
 * secondary index {@code idx_card_xref_account_id} and reaches it through
 * {@code CardXrefRepository.findFirstByAccountIdOrderByCardNumAsc}, so the access path survives as an
 * index rather than as a separately named file.
 *
 * <h2>Absence is an outcome, and the three gates are not the same shape</h2>
 *
 * <p>Assumptions: the first gate tests the VALIDATION FLAG while the second and third test a MESSAGE
 * CONDITION, and the asymmetry is the reference's own. At L696 the message-condition test for the
 * cross-reference miss is commented out and at L697 the flag test {@code IF FLG-ACCTFILTER-NOT-OK} is
 * the live one, whereas L704 and L713 test the message conditions
 * {@code DID-NOT-FIND-ACCT-IN-ACCTDAT} and {@code DID-NOT-FIND-CUST-IN-CUSTDAT} directly. Both shapes
 * are transcribed as they stand. The commented-out line at L696 is recorded as observed and is neither
 * reinstated nor characterised.
 *
 * <p>Assumptions: the two message conditions are declared at L131 with L132 and at L133 with L134, and
 * the statements that would set them are themselves commented out, at L792 and at L842. In the
 * reference the miss therefore surfaces through the {@code INPUT-ERROR} branch at L387 through L392
 * instead, after the not-found arms compose a message carrying the CICS response and reason codes at
 * L796 through L806 and at L846 through L856. Those codes have no target analogue, because there is no
 * CICS response code to report; the migrated hops therefore latch the DECLARED condition text and the
 * gates at L704 and L713 govern, which is what makes the short-circuit hold so that a miss never
 * attempts the following read. The caller-visible outcome is the same not-found either way, and the
 * divergence is registered in the traceability matrix rather than left implicit.
 *
 * <h2>Message text is carried byte for byte</h2>
 *
 * <p>Assumptions: the aggregate message channel is {@code WS-RETURN-MSG PIC X(75)} at L117, whose unset
 * state is {@code 88 WS-RETURN-MSG-OFF VALUE SPACES} at L118. Seventy-five is this program's own
 * declaration and is not inherited from {@code app/cpy/CSMSG01Y.cpy}, which declares a different,
 * fifty-character two-message regime at L17 through L21. The informational channel beside it is
 * {@code WS-INFO-MSG PIC X(40)} at L110.
 *
 * <p>Assumptions: the text the reference EMITS for a rejected filter is the inline literal at L672 and
 * not the condition names declared at L125 with L126 and at L127 with L128, and the two differ in
 * three ways: the emitted literal carries two spaces between {@code must} and {@code be}, a hyphen in
 * {@code non-zero}, and the word {@code Filter} where the declarations carry {@code number}. The
 * emitted literal is what a user reads, so it is what this class reproduces, character for character
 * and with its whitespace never normalised. The reason the two diverge at all is visible in the
 * reference: nothing anywhere in the program sets {@code SEARCHED-ACCT-NOT-NUMERIC} or
 * {@code SEARCHED-ACCT-ZEROES}, so L671 through L673 moves a literal instead, and the declarations are
 * unreached.
 *
 * <p>Assumptions: the same reading settles the informational channel. Nothing sets
 * {@code WS-INFORM-OUTPUT}, declared at L115 with L116, so {@code 'Displaying details of given
 * Account'} is never emitted. What reaches the screen is the prompt declared at L113 with L114,
 * because {@code 9000-READ-ACCT} clears the channel at L689 and the screen setup restores that prompt
 * at L528 and L529 before moving it out at L534. This class therefore publishes the prompt on every
 * response, including a successful one.
 *
 * <p>Assumptions: the exit text at L119 with L120 carries fourteen trailing spaces INSIDE its literal
 * and is deliberately absent from this class. It belongs to the function-key branch whose body is the
 * {@code EXEC CICS XCTL} at L349 through L352, and program transfer becomes a client-side route change
 * in the target rather than a server-side redirect, so no server response of this service may carry
 * it.
 *
 * <h2>Two abend surfaces, kept apart</h2>
 *
 * <p>Assumptions: the reference has two distinct failure surfaces and conflating them would lose one.
 * The first is the {@code WHEN OTHER} arm at L375, which stamps the culprit at L376, the code
 * {@code '0001'} at L377 and a blank reason at L378, and then routes the text
 * {@code 'UNEXPECTED DATA SCENARIO'} at L379 and L380 into the seventy-five-character channel and NOT
 * into the abend message field. The second is {@code ABEND-ROUTINE} at L916, registered for the whole
 * program by the {@code EXEC CICS HANDLE ABEND} at L264 through L266, which defaults its message at
 * L918 through L920, stamps the same culprit at L922 and abends with the code {@code '9999'} at L934
 * through L936. Both are rendered through {@code AbendDetail}, whose four components are the group
 * item declared at L21 through L29 of {@code app/cpy/CSMSG02Y.cpy}.
 *
 * <h2>No session state, and no arithmetic</h2>
 *
 * <p>Refactoring Rationale: this class holds no session state because the mechanism that made session
 * state necessary does not survive the migration. {@code app/cpy/COCOM01Y.cpy} declares a 160-byte
 * {@code CARDDEMO-COMMAREA} across L19 to L44 in five groups, and it decomposes into four separate
 * target mechanisms: the navigation fields become client-side router history, the identity fields at
 * L25 through L28 become validated token claims, the selection fields become request path parameters,
 * and the re-entry discriminator at L29 with L30 and L31 is removed entirely, because a stateless
 * handler answering with a field-error array has no first-entry against re-entry distinction left to
 * draw. With {@code TWASIZE(0)} on {@code CAVW} at L318 of {@code app/csd/CARDDEMO.CSD} there was no
 * transaction work area to fall back on, so decomposing that communication area accounts for all of
 * the continuity the reference kept and leaves nothing for a server-side session store to hold.
 *
 * <p>Refactoring Rationale: date values are handled by the platform library and not by the module the
 * batch reader calls. {@code app/cbl/CBACT01C.cbl} sets a conversion type at L225 and L226 and then
 * calls an assembler module at L231 to format a date; this migration retires the assembler tree with
 * no target at all, so the entities expose calendar types and the mappers render them. Nothing here
 * reaches an external formatter.
 *
 * <p>Alternatives Considered: recomputing or deriving any amount on this path, rather than publishing
 * the stored value. Rejected because a second place where cents are formed is a second place they can
 * differ. Money in this bounded context is exact scaled decimal at every hop, of which
 * {@code ACCT-CURR-BAL PIC S9(10)V99} at L7 of {@code app/cpy/CVACT01Y.cpy} is the exemplar and which
 * that copybook declares five times, at L7, L8, L9, L13 and L14; it is {@code NUMERIC(12,2)} in the
 * database and a JSON string on the wire. Any amount this migration does derive forms its product at
 * full precision before dividing, because dividing first changes the intermediate precision and
 * therefore the final cent, and a read that recomputed a stored amount would owe that same ordering
 * without having any reason to. Exactness matters here for a documented reason: {@code tests/README.md}
 * records at L273 and L274 that the reference must be compiled with the EBCDIC sign convention because
 * the default silently corrupts negative balances. IEEE 754 binary floating point appears nowhere in
 * this class.
 *
 * <p>Trade-offs: the substitution the batch reader performs on a zero current-cycle debit is NOT
 * reproduced. {@code app/cbl/CBACT01C.cbl} moves a hard-coded amount into its OUTPUT record at L236
 * through L238 when the stored value is zero, which is an artifact of that extract file and not a rule
 * of the account master -- the master itself is read into the record structure unchanged at L166.
 * Reproducing it would put a value no row holds into the money path of a read endpoint. The substituted
 * amount is deliberately not quoted anywhere in this class, not even as documentation, so that a search
 * of the migrated tree for it returns nothing at all rather than returning the one file that explains
 * why it is absent; the three cited lines are where a reader sees the value itself. What is given up is
 * literal correspondence with those lines; what is kept is that a zero current-cycle debit is published
 * as zero. The divergence is registered in the traceability matrix.
 *
 * <p>Alternatives Considered: this class pages nothing, and the one method that returns a list returns
 * every row for one account. Were a bound ever needed, it would resume from the key of the last row
 * returned rather than from a count of rows skipped, because rows inserted or removed between two
 * requests change how many rows precede a resume point, so a read positioned by count omits rows it
 * never returned and repeats rows it already returned. The reference itself resumes by key, which is
 * why that choice would be a carry-over rather than an approximation: the browse cursor the card-list
 * program carries between screen turns at L230 through L244 of {@code app/cbl/COCRDLIC.cbl} is a last
 * key, a first key and a next-page indicator, and never a row count.
 *
 * <p>Assumptions: there is no executable parity oracle for either reference program.
 * {@code tests/README.md} records at L83 through L85 that the online programs cannot run end to end
 * without a CICS runtime and that only their extractable field-validation logic is unit-tested, and its
 * business-rule section beginning at L553 governs the posting, interest and category-balance programs
 * rather than these two. Parity here therefore rests on transcribed validation logic and on the
 * copybook contracts, and no oracle is claimed.
 *
 * <p>Assumptions: this class holds no mutable state, so one instance serves every request
 * concurrently. That follows from the decomposition above rather than being a separate choice: with the
 * communication area of {@code app/cpy/COCOM01Y.cpy} L19 to L44 decomposed and no transaction work area
 * behind it, per {@code TWASIZE(0)} at L318 of {@code app/csd/CARDDEMO.CSD}, nothing is left for an
 * instance to carry between two requests. Parameters, return values and raised exceptions are
 * documented per method below.
 */
@Service
public class AccountViewService {

    /**
     * The program name the reference stamps as the culprit of either abend surface.
     *
     * <p>Assumptions: read from {@code LIT-THISPGM PIC X(8) VALUE 'COACTVWC'} at L143 and L144 of
     * {@code app/cbl/COACTVWC.cbl}, which both L376 and L922 move into the culprit component. It is
     * eight characters, exactly the width {@code ABEND-CULPRIT} declares at L24 of
     * {@code app/cpy/CSMSG02Y.cpy}, so it is carried whole and is never shortened.</p>
     */
    private static final String THIS_PROGRAM = "COACTVWC";

    /**
     * The response key under which a rejected account filter is reported.
     *
     * <p>Assumptions: the same key the update path already publishes for this field, so a client
     * binding one account error message binds them both. The screen field it names is the eleven-digit
     * filter the reference holds at L78 through L80 of {@code app/cbl/COACTVWC.cbl}.</p>
     */
    private static final String ACCOUNT_FILTER_FIELD = "accountId";

    /**
     * The declared character width of the account filter, eleven.
     *
     * <p>Assumptions: read from {@code WS-CARD-RID-ACCT-ID PIC 9(11)} at L78 of
     * {@code app/cbl/COACTVWC.cbl} together with its character redefinition at L79 and L80. That
     * text-over-number pairing is why the filter travels as digits-only text rather than as a number:
     * the reference supplies the character view as the record identification field of the read at
     * L729, so an eleven-character value with leading zeros is the shape both sides agree on.</p>
     */
    private static final int ACCOUNT_FILTER_WIDTH = 11;

    /**
     * The informational-channel text every response carries.
     *
     * <p>Assumptions: read verbatim from the condition declared at L113 with L114 of
     * {@code app/cbl/COACTVWC.cbl}. It is the only informational text the program ever emits: the
     * channel is cleared at L689 and this prompt is restored at L528 and L529 before being moved to
     * the screen at L534, while the alternative text declared at L115 with L116 is set nowhere in the
     * program.</p>
     */
    private static final String INFO_PROMPT_FOR_INPUT = "Enter or update id of account to display";

    /**
     * The text the reference emits for an account filter that is not eleven non-zero digits.
     *
     * <p>Assumptions: this is the inline literal at L672 of {@code app/cbl/COACTVWC.cbl}, reproduced
     * character for character. It carries TWO spaces between {@code must} and {@code be}, a hyphen in
     * {@code non-zero}, and the word {@code Filter}; the condition names at L125 with L126 and at L127
     * with L128 read differently and are set nowhere, so they are not the emitted text. The whitespace
     * is data here and is never normalised.</p>
     */
    private static final String RETURN_ACCOUNT_FILTER_REJECTED =
            "Account Filter must  be a non-zero 11 digit number";

    /**
     * The text the field edit latches when no account filter was supplied.
     *
     * <p>Assumptions: read verbatim from the condition declared at L121 with L122 of
     * {@code app/cbl/COACTVWC.cbl}, which L657 through L659 latches under the message-off guard. It is
     * an intermediate value on that path rather than the text a user finally reads, because the
     * cross-field edit at L640 through L642 replaces it; both are transcribed so that the replacement
     * is visible rather than assumed.</p>
     */
    private static final String RETURN_PROMPT_FOR_ACCOUNT = "Account number not provided";

    /**
     * The text a user finally reads when no account filter was supplied.
     *
     * <p>Assumptions: read verbatim from the condition declared at L123 with L124 of
     * {@code app/cbl/COACTVWC.cbl}. The cross-field edit at L640 through L642 sets it with NO
     * message-off guard, so unlike every other assignment to this channel it overwrites whatever the
     * field edit had already latched.</p>
     */
    private static final String RETURN_NO_SEARCH_CRITERIA = "No input received";

    /**
     * The text reported when the cross-reference holds no row for the account.
     *
     * <p>Assumptions: read verbatim from the condition declared at L129 with L130 of
     * {@code app/cbl/COACTVWC.cbl}. The reference declares it and never sets it, composing a message
     * carrying CICS response codes at L744 through L757 instead; those codes have no target analogue,
     * so the declared text is what this class latches.</p>
     */
    private static final String RETURN_NOT_FOUND_IN_CARD_XREF =
            "Did not find this account in account card xref file";

    /**
     * The text reported when the account master holds no row for the account.
     *
     * <p>Assumptions: read verbatim from the condition declared at L131 with L132 of
     * {@code app/cbl/COACTVWC.cbl}, which is the condition the gate at L704 tests. Latching it is what
     * makes that gate govern, since the statement that would set it is commented out at L792.</p>
     */
    private static final String RETURN_NOT_FOUND_IN_ACCOUNT_MASTER =
            "Did not find this account in account master file";

    /**
     * The text reported when the customer master holds no row for the resolved customer.
     *
     * <p>Assumptions: read verbatim from the condition declared at L133 with L134 of
     * {@code app/cbl/COACTVWC.cbl}, which is the condition the gate at L713 tests. As with the account
     * master, latching it is what makes that gate govern, since its setter is commented out at
     * L842.</p>
     */
    private static final String RETURN_NOT_FOUND_IN_CUSTOMER_MASTER =
            "Did not find associated customer in master file";

    /**
     * The text the first abend surface routes into the seventy-five-character channel.
     *
     * <p>Assumptions: read verbatim from L379 and L380 of {@code app/cbl/COACTVWC.cbl}. Its
     * destination is {@code WS-RETURN-MSG} and NOT the abend message component, which is why this
     * constant is a return-channel text and the abend detail built beside it carries a blank
     * message.</p>
     */
    private static final String RETURN_UNEXPECTED_DATA_SCENARIO = "UNEXPECTED DATA SCENARIO";

    /**
     * The condition code of the first abend surface, the unclassifiable data scenario.
     *
     * <p>Assumptions: read from L377 of {@code app/cbl/COACTVWC.cbl}. Four characters, matching the
     * width {@code ABEND-CODE} declares at L22 of {@code app/cpy/CSMSG02Y.cpy}.</p>
     */
    private static final String ABEND_CODE_DATA_SCENARIO = "0001";

    /**
     * The condition code the program-wide abend handler terminates with.
     *
     * <p>Assumptions: read from the {@code ABCODE} operand at L935 of
     * {@code app/cbl/COACTVWC.cbl}, inside the abend at L934 through L936.</p>
     */
    private static final String ABEND_CODE_UNHANDLED = "9999";

    /**
     * The default message the program-wide abend handler substitutes when none was supplied.
     *
     * <p>Assumptions: read verbatim from L919 of {@code app/cbl/COACTVWC.cbl}, including its closing
     * full stop, which the guard at L918 through L920 applies only when the message component is
     * still unset.</p>
     */
    private static final String ABEND_MSG_UNEXPECTED = "UNEXPECTED ABEND OCCURRED.";

    /**
     * The blank reason component both abend surfaces carry.
     *
     * <p>Assumptions: L378 of {@code app/cbl/COACTVWC.cbl} moves spaces into the reason component and
     * nothing later fills it. The empty string is used rather than a run of fifty spaces because this
     * migration treats the trailing spaces of a fixed-width field as padding rather than as data, and
     * the shared abend type documents that same reading of its own components.</p>
     */
    private static final String ABEND_REASON_BLANK = "";

    /**
     * The query identity every cursor of the customer scan is sealed against.
     *
     * <p>Assumptions: the binding names THIS scan and nothing else, so a cursor minted by another
     * listing is refused rather than silently resumed against the wrong key column. It is composed as a
     * literal rather than through {@code CursorToken.binding(String, String, String)} because that
     * composition requires a non-blank authenticated subject, and the customer scan carries no subject
     * into this layer: it stands in for the whole-file walk of a batch reader, which
     * {@code app/cbl/CBCUS01C.cbl} drives from {@code PROCEDURE DIVISION} at L70 with no terminal and
     * therefore no signed-on user at all.</p>
     */
    private static final String CUSTOMER_SCAN_BINDING = "account.customers.scan";

    /**
     * The greatest number of customer rows one page of the scan may carry.
     *
     * <p>Trade-offs: the reference sweep is UNBOUNDED -- {@code app/cbl/CBCUS01C.cbl} runs
     * {@code PERFORM UNTIL END-OF-FILE = 'Y'} at L74 to L81 over the whole master and writes each
     * record to a print stream at L78 -- and this ceiling is the compromise that makes the same access
     * path answerable over a request-response protocol. What is preserved exactly is the ORDER and the
     * ACCESS PATH: ascending {@code CUST-ID}, which is the primary key the file declares at L32 beside
     * {@code ACCESS MODE IS SEQUENTIAL} at L31. What is given up is that a caller wanting the whole
     * master now issues several requests instead of one job step. The ceiling is enforced here as well
     * as on the request parameter, because a bound only declared at the edge is a bound a second caller
     * of this method would not inherit.</p>
     */
    public static final int CUSTOMER_SCAN_MAX_PAGE_SIZE = 100;

    /**
     * The number of customer rows a page carries when the caller expresses no preference.
     *
     * <p>Assumptions: a default exists because the reference asks for no page size and has none to
     * migrate, so a caller reproducing its whole-file walk has nothing to supply. The value is well
     * inside the ceiling above rather than equal to it, so the commonest request is not also the
     * heaviest one this operation admits.</p>
     */
    public static final int CUSTOMER_SCAN_DEFAULT_PAGE_SIZE = 20;

    /** The account master rows this bounded context owns. */
    private final AccountRepository accounts;

    /** The customer master rows this bounded context owns. */
    private final CustomerRepository customers;

    /** The card cross-reference rows this bounded context owns. */
    private final CardXrefRepository crossReferences;

    /** Projects stored rows onto the three machine-read contracts a neighbouring context consumes. */
    private final AccountContextMapper contextMapper;

    /** Projects a stored account row onto the human view, message channels included. */
    private final AccountMapper accountMapper;

    /** Projects a stored customer row onto the human view's customer half, identifiers masked. */
    private final CustomerMapper customerMapper;

    /** Projects cross-reference rows onto the by-account response list. */
    private final CardXrefMapper crossReferenceMapper;

    /**
     * Seals and opens the page positions of every paged read this class publishes.
     *
     * <p>Assumptions: this collaborator is what makes the paged envelope buildable at this layer and
     * nowhere below it. The envelope refuses a cursor component that is not a sealed token, and sealing
     * needs key material, so the repository interface -- which holds none -- states that the envelope is
     * assembled one layer up. This field is that layer, and it serves both paged reads here: the
     * customer scan and the by-account cross-reference walk. Each seals against its own query name, so a
     * token issued for one cannot be presented to the other.</p>
     */
    private final CursorToken cursorToken;

    /**
     * The rows one page of a cross-reference walk carries.
     *
     * <p>Assumptions: seven is read from the reference rather than chosen here.
     * {@code app/cbl/COCRDLIC.cbl} declares {@code WS-MAX-SCREEN-LINES PIC S9(4) COMP} at L177 with
     * {@code VALUE 7.} at L178, and that is the count its browse fills before it probes for one more
     * record to settle whether a further page exists.</p>
     */
    private static final int CARD_XREF_PAGE_SIZE = 7;

    /**
     * The query name every cross-reference cursor is sealed against.
     *
     * <p>Assumptions: the name is part of the seal, so a token issued for this walk cannot be presented
     * to any other paged read in the migration even by a caller holding both. Naming the query rather
     * than sealing on the subject alone is what makes that substitution fail.</p>
     */
    private static final String CARD_XREF_CURSOR_QUERY = "account.card-xrefs.by-account";

    /**
     * The scope a forward cross-reference cursor is sealed against.
     *
     * <p>Assumptions: the direction is sealed in rather than trusted from the request, so the trailing
     * boundary of a page cannot be replayed as a leading one. The reference keeps the two boundaries in
     * separate fields for the same reason, at L230 through L232 and L233 through L235 of
     * {@code app/cbl/COCRDLIC.cbl}.</p>
     */
    private static final String CARD_XREF_CURSOR_SCOPE_FORWARD = "direction:next";

    /** The scope a backward cross-reference cursor is sealed against. */
    private static final String CARD_XREF_CURSOR_SCOPE_BACKWARD = "direction:previous";

    /**
     * The prefix naming the account a cross-reference cursor was issued while walking.
     *
     * <p>Assumptions: the account is part of the seal because the cursor names a CARD NUMBER, and a card
     * number is a valid position within some other account's rows too. Without the account in the seal a
     * token from one account repositioned another account's walk silently.</p>
     */
    private static final String CARD_XREF_CURSOR_SCOPE_ACCOUNT_PREFIX = "account:";

    /**
     * The request word that asks a cross-reference walk to step backward.
     *
     * <p>Assumptions: any other word, the empty string included, reads forward. That is the reference's
     * own default: {@code app/cbl/COCRDLIC.cbl} advances with its forward read and takes its backward
     * path only when the backward key is asked for explicitly.</p>
     */
    private static final String CARD_XREF_DIRECTION_PREVIOUS = "previous";

    /**
     * Creates the read path over its repositories and projections.
     *
     * <p>Assumptions: every collaborator arrives through the constructor rather than through field
     * injection, so an instance is fully formed once constructed and can be exercised with substitutes
     * under a plain unit test with no container and no database attached. That property is the reason
     * the package charter assigns constructor injection: the reference reaches its subroutines by
     * static {@code CALL} linkage and holds their state in shared {@code WORKING-STORAGE}, carried
     * between programs in the structure at L19 to L44 of {@code app/cpy/COCOM01Y.cpy}, so no rule there
     * can be exercised on its own.</p>
     *
     * <p>Assumptions: two account projections are injected rather than one, and they are not
     * interchangeable. The context projection owns the three-amount machine contract a neighbouring
     * bounded context reads; the account projection owns the human view screen, whose ten account moves
     * run at L473 through L490 of {@code app/cbl/COACTVWC.cbl}, including the assembly of the two
     * message channels declared at L110 and L117, and it is the only one that can build a response
     * carrying them. Collapsing them would put one screen's contract and one service-to-service
     * contract behind a single type whose callers could not then be told apart.</p>
     *
     * @param accounts the account master repository, an {@link AccountRepository}; must not be
     *     {@code null}
     * @param customers the customer master repository, a {@link CustomerRepository}; must not be
     *     {@code null}
     * @param crossReferences the card cross-reference repository, a {@link CardXrefRepository}; must
     *     not be {@code null}
     * @param contextMapper the projection to the machine-read contracts, an
     *     {@link AccountContextMapper}; must not be {@code null}
     * @param accountMapper the projection to the human account view, an {@link AccountMapper}; must
     *     not be {@code null}
     * @param customerMapper the projection to the human view's customer half, a {@link CustomerMapper};
     *     must not be {@code null}
     * @param crossReferenceMapper the projection to the by-account cross-reference list, a
     *     {@link CardXrefMapper}; must not be {@code null}
     * @param cursorToken the sealer the customer scan and the paged cross-reference walk position
     *     their page boundaries with, a {@link CursorToken}; must not be {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    public AccountViewService(AccountRepository accounts,
            CustomerRepository customers,
            CardXrefRepository crossReferences,
            AccountContextMapper contextMapper,
            AccountMapper accountMapper,
            CustomerMapper customerMapper,
            CardXrefMapper crossReferenceMapper,
            CursorToken cursorToken) {
        this.accounts = Objects.requireNonNull(accounts, "accounts must not be null");
        this.customers = Objects.requireNonNull(customers, "customers must not be null");
        this.crossReferences = Objects.requireNonNull(crossReferences, "crossReferences must not be null");
        this.contextMapper = Objects.requireNonNull(contextMapper, "contextMapper must not be null");
        this.accountMapper = Objects.requireNonNull(accountMapper, "accountMapper must not be null");
        this.customerMapper = Objects.requireNonNull(customerMapper, "customerMapper must not be null");
        this.crossReferenceMapper =
                Objects.requireNonNull(crossReferenceMapper, "crossReferenceMapper must not be null");
        this.cursorToken = Objects.requireNonNull(cursorToken, "cursorToken must not be null");
    }

    /**
     * Resolves a primary account number to the account and customer it belongs to.
     *
     * <p>Assumptions: the transaction is declared read-only, which is a statement of intent the
     * provider enforces rather than a micro-optimisation -- a write attempted inside it fails instead
     * of succeeding quietly. Every method on this class is a read and none has any business holding a
     * write path.</p>
     *
     * @param cardNumber the sixteen-digit primary account number to resolve, a {@code String} of the
     *     width {@code XREF-CARD-NUM} declares at L5 of {@code app/cpy/CVACT03Y.cpy}; must not be
     *     {@code null}
     * @return the account and customer the card belongs to, a {@link CardXrefView}, never {@code null}
     * @throws NoSuchElementException if the card is not cross-referenced, which the shared advice
     *     renders as HTTP 404 and which a consuming context reads as its card-not-found decision input
     */
    @Transactional(readOnly = true)
    public CardXrefView resolveCardCrossReference(String cardNumber) {
        return this.crossReferences.findByCardNum(cardNumber)
                .map(this.contextMapper::toCardXrefView)
                // WHY : Assumptions: the raised message names NEITHER the card number nor any part of
                //       it. It reaches the shared advice, which writes it to a log and returns it in a
                //       response body, and the value is a primary account number. The caller knows
                //       which card it asked about, so the message has nothing to add by repeating it
                //       into two more places.
                .orElseThrow(() -> new NoSuchElementException(
                        "no cross-reference row exists for the requested card"));
    }

    /**
     * Reads the limits and posted balance of one account.
     *
     * @param accountId the eleven-digit account identifier, the {@code long} rendering of the key
     *     {@code ACCT-ID} declares at L5 of {@code app/cpy/CVACT01Y.cpy}
     * @return the account's credit limit, cash credit limit and posted balance, an
     *     {@link AccountContextView}, never {@code null}
     * @throws NoSuchElementException if the account master holds no such row, which the shared advice
     *     renders as HTTP 404 and which a consuming context reads as its account-not-found decision
     *     input
     */
    @Transactional(readOnly = true)
    public AccountContextView readAccountContext(long accountId) {
        return this.accounts.findById(accountId)
                .map(this.contextMapper::toAccountContextView)
                // WHY : Refactoring Rationale: the identifier used to be named in this message, on the
                //       argument that an eleven-digit ACCT-ID already travelled in the request path and
                //       in the access log of every hop, so withholding it here protected nothing. Both
                //       halves of that argument were wrong. The sensitive-data logging contract in
                //       docs/architecture/observability.md names ACCOUNT AND CUSTOMER IDENTIFIERS
                //       alongside the primary account number as values a durable diagnostic may not
                //       carry, and it states that a prohibited value is OMITTED and not abbreviated --
                //       so the contract never granted the exemption the old comment claimed. And the
                //       premise has since been removed outright: this operation is now reached by a
                //       POST carrying its key in a body, so the identifier appears in no request line
                //       and in no access log, and repeating it here would be the ONLY place it landed.
                // WHY : Trade-offs: the message loses the one value an operator would want when reading
                //       it in isolation. It is not lost to the operator, because the correlation
                //       identifier the shared filter stamps on the request joins this record to the
                //       caller's own -- which is where the identifier legitimately lives, in the
                //       calling context's own bounded diagnostics.
                .orElseThrow(() -> new NoSuchElementException(
                        "no account master row exists for the requested account"));
    }

    /**
     * Reports whether the customer master holds one customer.
     *
     * <p>Assumptions: an existence check is issued rather than a read, and the difference is visible in
     * the generated statement -- an existence check selects no column, while a read materialises the
     * eighteen the copybook declares at L5 through L22 of {@code app/cpy/CVCUS01Y.cpy}, two of them
     * encrypted identifiers. Since the answer is a presence either way, reading the row would decrypt
     * nothing and disclose nothing, but it would still carry cardholder data out of the database into
     * this process's heap for no purpose.</p>
     *
     * @param customerId the nine-digit customer identifier, the {@code long} rendering of the key
     *     {@code CUST-ID} declares at L5 of {@code app/cpy/CVCUS01Y.cpy}
     * @return {@code true} when the customer master holds the row, {@code false} when it does not
     */
    @Transactional(readOnly = true)
    public boolean customerExists(long customerId) {
        return this.customers.existsById(customerId);
    }

    /**
     * Reads one customer of the customer master by its key.
     *
     * <p>Purpose: this is the keyed arm of the access surface {@code app/cbl/CBCUS01C.cbl} declares.
     * That program names {@code RECORD KEY IS FD-CUST-ID} at L32 over a file whose record it carves
     * into a nine-digit key at L39 and four hundred and ninety-one opaque bytes at L40, and it copies
     * the meaningful layout in at L45 with {@code COPY CVCUS01Y.} -- so the key is the customer
     * identifier and the published fields are the copybook's, not the file description's.</p>
     *
     * <p>Assumptions: the projection is applied here rather than by the caller, because the mapper is
     * what masks the two identifiers the record holds -- the national identifier at L17 of
     * {@code app/cpy/CVCUS01Y.cpy} and the government-issued identifier at L18 -- and a caller handed a
     * stored row could publish either one whole. Returning the projection is what makes the masking an
     * invariant of this method rather than an obligation on whoever calls it.</p>
     *
     * <p>Trade-offs: an absent row is RAISED rather than returned as an empty optional, which differs
     * from the presence check beside this method and does so deliberately. That one answers a
     * yes-or-no question a caller acts on; this one promises a representation, and there is no
     * representation of a row that is not there. The sentence carried is the reference's own, declared
     * at L133 with L134 of {@code app/cbl/COACTVWC.cbl} and reached at its gate at L713, which is the
     * same text the update program declares at L501 with L502 of {@code app/cbl/COACTUPC.cbl}.</p>
     *
     * @param customerId the nine-digit customer identifier, the {@code long} rendering of the key
     *     {@code CUST-ID} declares at L5 of {@code app/cpy/CVCUS01Y.cpy}
     * @return the customer with both stored identifiers masked, a {@link CustomerResponse}, never
     *     {@code null}
     * @throws NoSuchElementException if the customer master holds no such row, carrying the reference
     *     sentence, which the shared advice renders as HTTP 404; the advice substitutes its own fixed
     *     absence sentence in the body, because it passes a carried sentence through only when it ends in
     *     an ellipsis and this one does not, so the reference wording is observable in this exception and
     *     in the advice's log line rather than in the response
     */
    @Transactional(readOnly = true)
    public CustomerResponse readCustomer(long customerId) {
        return this.customers.findById(customerId)
                .map(this.customerMapper::toCustomerResponse)
                .orElseThrow(() -> new NoSuchElementException(RETURN_NOT_FOUND_IN_CUSTOMER_MASTER));
    }

    /**
     * Reads one ascending page of the customer master, resuming from a sealed position.
     *
     * <p>Purpose: this is the bounded form of the whole-file sweep {@code app/cbl/CBCUS01C.cbl} drives
     * at L74 through L81 -- a {@code PERFORM UNTIL END-OF-FILE = 'Y'} whose body reads the next record
     * at L76 and writes it at L78, entered after the open at L72 and left at the close at L83. The
     * reference filters nothing and pages nothing, so the ORDER is the whole of what has to be
     * preserved, and it is: ascending {@code CUST-ID}, which follows from
     * {@code ACCESS MODE IS SEQUENTIAL} at L31 over {@code RECORD KEY IS FD-CUST-ID} at L32.</p>
     *
     * <p>Alternatives Considered: positioning the page by offset, rejected on correctness rather than
     * on cost. The reference's own browse state is already a keyset cursor: at L230 through L244 of
     * {@code app/cbl/COCRDLIC.cbl} it carries a trailing key pair at L230 through L232, a leading key
     * pair at L233 through L235, a screen ordinal at L237, a last-screen flag at L239 through L241 and a
     * further-rows indicator at L242 through L244, with a row counter at L145 -- and not one offset, row
     * number or page-size field anywhere in it. An offset is evaluated against the table as it stands
     * when each page is fetched, so rows inserted or removed between two fetches shift the window and
     * the caller silently skips rows or receives one twice; a position naming the last key seen is not
     * moved by a concurrent write.</p>
     *
     * <p>Assumptions: the scan is FORWARD only, and the descending query the repository also declares
     * is deliberately not reached from here. The reference walk is one-directional -- its loop has a
     * single get-next paragraph at L92 and no backward counterpart at all -- so a backward step would be
     * a capability this program never had. The two boundary keys are still published, because the
     * envelope carries both and the leading one is what tells a caller it is holding the opening
     * page.</p>
     *
     * <p>Assumptions: one row MORE than the page carries is requested, and the arrival of that surplus
     * row is the whole of how the further-rows answer is reached. It is never reached by counting the
     * master. This is the reference's own method: at L242 through L244 of
     * {@code app/cbl/COCRDLIC.cbl} the further-rows state is a single flag, raised by discovering one
     * record beyond what the screen holds. The surplus row is trimmed here and published nowhere, so it
     * never reaches a caller as data.</p>
     *
     * @param cursor the sealed position to resume strictly after, or {@code null} to read the opening
     *     page of the scan
     * @param pageSize the number of rows the page may carry, clamped into the range this class declares
     *     between one and {@link #CUSTOMER_SCAN_MAX_PAGE_SIZE}
     * @return one ascending page with both boundary positions sealed and the further-rows indicator set,
     *     a {@link PageResponse} of {@link CustomerResponse}, exhausted when no row follows the
     *     position; never {@code null}
     * @throws CursorToken.InvalidCursorException if the supplied position is not one this scan sealed,
     *     which the shared advice renders as HTTP 400 keyed to the cursor
     */
    @Transactional(readOnly = true)
    public PageResponse<CustomerResponse> listCustomers(String cursor, int pageSize) {
        int size = Math.clamp(pageSize, 1, CUSTOMER_SCAN_MAX_PAGE_SIZE);

        // WHY : Assumptions: the surplus row is requested through the limit and NOT through a second
        //       query, because the repository states that whether further rows follow is settled by
        //       asking for one more row than will be published and observing whether it arrives. The
        //       limit therefore carries size plus one, and the extra row is removed below.
        Limit window = Limit.of(size + 1);

        // WHY : Assumptions: an absent position enters the scan through the repository's opening query
        //       rather than through the resuming one with a sentinel key. The reference enters the same
        //       way -- the open at L72 of app/cbl/CBCUS01C.cbl falls straight into the get-next
        //       paragraph at L92 and seeks no lowest identifier first -- and the resuming query declares
        //       its bound as required, so there is no absent value to pass it.
        // WHY : Assumptions: the position is OPENED before it reaches a predicate rather than trusted as
        //       supplied. This replaces the reference's trust in an echoed communication area, which it
        //       reads back from the terminal at each turn per the block at L229 onward of
        //       app/cbl/COCRDLIC.cbl; a position this scan did not seal is refused instead of used.
        // WHY : Assumptions: the opened value is parsed as a number with NO defensive branch, and the
        //       parse is total rather than merely likely to succeed. Two facts make it so, and both are
        //       contracts rather than observations: open() either returns the exact key material that was
        //       sealed under this binding or raises instead of returning, and the only place this binding
        //       is ever sealed is pageOfCustomers below, which seals the stored identifier of a row.
        //       That identifier is numeric in the reference itself -- CUST-ID is PIC 9(09) at L5 of
        //       app/cpy/CVCUS01Y.cpy and is the RECORD KEY at L32 of app/cbl/CBCUS01C.cbl -- so a value
        //       that opens successfully cannot be non-numeric, and no NumberFormatException is reachable
        //       or declared.
        // WHY : Alternatives Considered: catching the parse failure and re-raising it as a refused
        //       cursor, which would be defensible if the payload could be attacker-chosen. It cannot:
        //       an attacker-chosen payload fails the seal check and never reaches the parse, so the catch
        //       would be unreachable code that reads as though the parse were untrusted -- and it would
        //       additionally convert any FUTURE non-numeric binding sealed by mistake into a client error
        //       rather than surfacing it as the programming error it would be.
        List<Customer> rows = cursor == null
                ? this.customers.findAllByOrderByCustomerIdAsc(window)
                : this.customers.findByCustomerIdGreaterThanOrderByCustomerIdAsc(
                        Long.parseLong(this.cursorToken.open(CUSTOMER_SCAN_BINDING, cursor)), window);

        return pageOfCustomers(rows, size);
    }

    /**
     * Trims a read window to its page and seals both boundary positions.
     *
     * <p>Assumptions: the surplus row is removed from the END of the window, and the end is the correct
     * one because this scan travels one way only. A read that also travelled backward would have to trim
     * the other end for that direction, since its surplus row is the one furthest from the position;
     * having no backward arm, this method has one case rather than two.</p>
     *
     * <p>Assumptions: both boundaries are sealed from the TRIMMED page, so each names a row the caller
     * actually received. The reference does otherwise for its trailing boundary and the divergence is
     * recorded rather than inherited: {@code app/cbl/COCRDLIC.cbl} captures the last displayed key at
     * L1194 and L1195 and then overwrites it with the probe row's key at L1212 through L1214, so the
     * value it keeps names a record the terminal never showed. Publishing that would advance a caller's
     * position one row too far and drop a row from the following page.</p>
     *
     * @param rows the window the store returned, at most the page size plus one, in ascending
     *     identifier order; must not be {@code null}
     * @param size the number of rows the page may carry
     * @return the page with both boundaries sealed, or the exhausted page when the window holds no row;
     *     never {@code null}
     * @throws IllegalArgumentException if a sealed boundary is refused by the envelope's own cursor
     *     check, which no value produced here can provoke
     */
    private PageResponse<CustomerResponse> pageOfCustomers(List<Customer> rows, int size) {
        boolean hasSurplus = rows.size() > size;
        List<Customer> shown = hasSurplus ? rows.subList(0, size) : rows;

        // WHY : Assumptions: an exhausted read answers with the envelope's own exhausted page rather
        //       than with boundaries sealed over nothing, because the envelope refuses a row-bearing
        //       page that names no boundary and because this is the state the reference reaches when its
        //       read reports end-of-file at L98 and L99 of app/cbl/CBCUS01C.cbl and raises the
        //       end-of-file flag at L108.
        if (shown.isEmpty()) {
            return PageResponse.empty();
        }

        List<CustomerResponse> items = new ArrayList<>(shown.size());
        for (Customer row : shown) {
            items.add(this.customerMapper.toCustomerResponse(row));
        }

        // WHY : Assumptions: the boundaries are sealed from the STORED key rather than from the
        //       published projection, because the projection renders the identifier as text for the
        //       reason recorded on the response record while the resuming query binds a numeric key.
        //       Sealing the rendered form would make the next page's bound depend on a display decision.
        // WHY : Refactoring Rationale: no backward availability answer is published, and the leading
        //       boundary below is what this page owes a caller stepping back. The reference settles that
        //       question from a page ordinal the terminal carried between turns rather than from a read,
        //       so its migrated home is the client's navigation state and a fifth envelope component
        //       would answer from the service what the reference answers from the client.
        return PageResponse.ofRows(items,
                this.cursorToken.seal(CUSTOMER_SCAN_BINDING,
                        String.valueOf(shown.getFirst().getCustomerId())),
                this.cursorToken.seal(CUSTOMER_SCAN_BINDING,
                        String.valueOf(shown.getLast().getCustomerId())),
                hasSurplus);
    }

    /**
     * Reads the human account view for an account already known to the caller.
     *
     * <p>Purpose: this is the migrated form of {@code 9000-READ-ACCT} at L687 of
     * {@code app/cbl/COACTVWC.cbl} entered with a validated key, which is the state the reference
     * reaches at L369 after its input edit has passed. It is the route the view screen and the update
     * screen's pre-read both take once an account identifier is in the request path.</p>
     *
     * <p>Assumptions: both masters and the cross-reference are read by ONE STATEMENT, so the account and
     * the customer on one screen are consistent with each other. That mirrors the reference, where all
     * three reads are driven from the single paragraph at L687 through L720 of
     * {@code app/cbl/COACTVWC.cbl} inside one task.</p>
     *
     * <p>Refactoring Rationale: this paragraph previously credited the surrounding read-only TRANSACTION
     * with that consistency while the composition issued three separate statements, and the credit was
     * misplaced. This datasource runs at read-committed isolation, where a transaction takes no snapshot of
     * its own and each statement takes one, so a concurrent update committing between two of the three
     * statements produced an account from before it beside a customer from after it -- a pairing that never
     * existed in the database, published as though it had. Alternatives Considered: raising this
     * transaction to repeatable read, which is the other available remedy; rejected because it makes every
     * read in the transaction snapshot-stable whether it needs to be or not and adds a
     * serialisation-failure outcome this operation would then have to answer for, where one statement needs
     * no isolation change at all.</p>
     *
     * <p>Trade-offs: a miss on the cross-reference or the account master is raised here rather than
     * returned as a message-bearing response. The reference itself returns a re-rendered screen at L365
     * and L366, so raising is a rendering decision taken at this boundary and not a rule: this method is
     * the route a machine caller and a path-parameter request take, and both express absence as
     * HTTP 404. What is accepted is that one composition is rendered two ways; what is bought is that
     * neither caller is given the other's shape. The verbatim reference sentence travels on the raised
     * type, and whether it reaches the response body is decided by the shared advice rather than
     * restated here.</p>
     *
     * <p>⚠️ Refactoring Rationale: a miss on the CUSTOMER master is NOT raised, and it used to be. That
     * arm is the reference's own partial state -- the account region is painted under the disjunctive
     * guard at L471 and L472 while the customer region is suppressed at L493 -- so it answers HTTP 200
     * with the customer member null, the reference's sentence in {@code returnMessage} and no entity tag.
     * Raising for it reported an account that exists as absent and withheld the ten fields the terminal
     * displays.</p>
     *
     * @param accountId the account to read, the eleven-digit identifier the reference supplies as the
     *     record identification field of the read at L778
     * @return the composed view carrying the account, its customer where one was located and both
     *     message channels, together with the revision both rows stand at when both were located, a
     *     {@link RevisionedAccountView}, never {@code null}
     * @throws NoSuchElementException if the cross-reference or the account master holds no matching row,
     *     which the shared advice renders as HTTP 404; a missing CUSTOMER row is answered rather than
     *     raised, as the rationale above records
     * @throws IllegalStateException if the composition reaches a data condition none of the three
     *     reads classifies, which is the first abend surface of L375 through L380, or if a read fails
     *     for a reason the program-wide handler of L916 would have caught
     */
    @Transactional(readOnly = true)
    public RevisionedAccountView readAccountView(long accountId) {
        RevisionedAccountView composed = readAccountUnderAbendHandler(accountId);
        AccountViewResponse view = composed.view();

        // WHY : ⚠️ Refactoring Rationale: only the ACCOUNT half is required for this route to answer, and
        //       the customer half used to be required too. That disjunction made the reference's own
        //       partial state unreachable: app/cbl/COACTVWC.cbl guards its two screen regions
        //       DIFFERENTLY -- the account region on FOUND-ACCT-IN-MASTER OR FOUND-CUST-IN-MASTER at
        //       L471 and L472, the customer region on FOUND-CUST-IN-MASTER alone at L493 -- so an
        //       account located with no customer row is painted with its ten account fields and the
        //       verbatim sentence naming the miss, and the internal chain below already composes exactly
        //       that at the arm returning the account with a null customer. Raising here discarded that
        //       composition and reported an account that exists as HTTP 404, which loses ten fields the
        //       terminal shows and tells a caller the account is absent.
        // WHY : Assumptions: a machine caller can now tell the two apart, which is what the earlier note
        //       was protecting and what the CONTRACT now carries instead of this guard:
        //       account-api.yaml publishes the customer member as nullable, the returnMessage carries the
        //       reference's own sentence on that arm, and no entity tag is issued because no precondition
        //       can be formed from rows that were not both located.
        // WHY : Assumptions: the ACCOUNT half still raises, and the asymmetry is the reference's rather
        //       than a convenience. With no account row located both screen guards above are false and
        //       the reference paints NEITHER region, so there is no partial state to publish and absence
        //       of the account is absence of the view.
        if (view.account() == null) {
            throw notFound(view.returnMessage());
        }

        return composed;
    }

    /**
     * An account view together with the revision the two rows behind it stand at.
     *
     * <p>Purpose: the two members are produced by ONE statement inside ONE read-only transaction and
     * travel together for that reason. The view is what the caller renders and the revision is the
     * precondition it must return on its next edit of this account, and pairing them is what stops a
     * caller being handed one that describes a different state from the other.</p>
     *
     * <p>⚠️ Refactoring Rationale: the adapter used to obtain the revision by calling a second, read-only
     * operation on the WRITE service after this one had already answered. At this datasource's
     * read-committed isolation that is two snapshots, so a concurrent edit committing between them
     * published a body from before it beside a revision naming the state after it -- and a caller echoing
     * that revision on an {@code If-Match} was then told its precondition was current while holding a
     * body that was not, which is exactly the silent overwrite the precondition exists to prevent. The
     * revision now comes off the same composition row as the body.</p>
     *
     * <p>Assumptions: this is a service-layer carrier and NOT a published wire shape. Nothing serialises
     * it -- the adapter unpacks it, putting the view in the body and the revision in an {@code ETag}
     * header -- so it is declared here rather than added to the closed transfer-object inventory, which
     * would describe it as something a client receives.</p>
     *
     * @param view the composed view carrying the account, its customer and both message channels; never
     *     {@code null}
     * @param revision the token both rows stand at, which the caller returns on its next edit of this
     *     account; never {@code null} once the composition has both halves, and {@code null} when only
     *     the account half was located -- an arm {@link #readAccountView} now publishes, because no
     *     precondition can be formed from rows that were not both read
     */
    public record RevisionedAccountView(AccountViewResponse view, String revision) {
    }

    /**
     * Reads the nine customer fields a neighbouring context renders on a screen.
     *
     * <p>Refactoring Rationale: this read exists because the pending-authorization detail screen had no
     * operation that answers with those fields. Its client read them from the response of
     * {@link #customerExists(long)}, whose contract carries no body at all, so the screen's name, address
     * and telephone fields rendered as absent on every request and nothing failed while they did.</p>
     *
     * <p>Alternatives Considered: letting that consumer call {@link #readCustomer(long)} instead, which
     * needs nothing new here. Rejected on least privilege: that read answers with the whole record and is
     * gated on the customer-master authority, which is granted to no context precisely because it would
     * hand a caller the national identifier, the government-issued identifier and the credit score of any
     * customer. Serving nine fields under the narrower decision-read authority the consumer already holds
     * is what keeps the escalation from happening.</p>
     *
     * <p>Assumptions: this method reads the SAME row {@link #readCustomer(long)} reads and applies a
     * narrower projection to it, rather than issuing a narrower statement. The saving a projection-level
     * query would make is one round trip's worth of columns from a row already located by primary key,
     * and what it would cost is a second statement whose column list has to be kept in step with this
     * projection by hand -- so the two could come to disagree about which fields the screen is entitled
     * to, which is the disagreement this whole operation exists to remove.</p>
     *
     * <p>Trade-offs: an absent row is RAISED rather than returned empty, matching
     * {@link #readCustomer(long)} rather than {@link #customerExists(long)}. This method promises a
     * representation and there is no representation of a row that is not there; the consumer reads the 404
     * as its own absent-customer outcome and renders the screen with the fields unpopulated, which is what
     * it does today for a customer the master does not hold.</p>
     *
     * @param customerId the nine-digit customer identifier, the {@code long} rendering of the key
     *     {@code CUST-ID} declares at L5 of {@code app/cpy/CVCUS01Y.cpy}
     * @return the nine-field display projection, never {@code null}
     * @throws NoSuchElementException if the customer master holds no such row, carrying the reference
     *     sentence, which the shared advice renders as HTTP 404
     */
    @Transactional(readOnly = true)
    public CustomerDisplayView readCustomerDisplay(long customerId) {
        return this.customers.findById(customerId)
                .map(this.customerMapper::toCustomerDisplayView)
                .orElseThrow(() -> new NoSuchElementException(RETURN_NOT_FOUND_IN_CUSTOMER_MASTER));
    }

    /**
     * Resolves an account to the account and customer its lowest-ordering cross-referenced card names.
     *
     * <p>Purpose: this is the migrated form of {@code 9200-GETCARDXREF-BYACCT.} at L723 of
     * {@code app/cbl/COACTVWC.cbl}, whose body at L727 through L732 issues ONE {@code EXEC CICS READ}
     * against the alternate-index literal declared at L192 and L193 and receives one record. The target
     * reaches the same access path through the secondary index {@code idx_card_xref_account_id}.</p>
     *
     * <p>Assumptions: exactly one row answers, and the tie-break is stated rather than left to the
     * store. The index is not unique, so an account may hold several rows; the row taken is the one whose
     * card number orders lowest, which is the base cluster's own order per
     * {@code ACCESS MODE  IS SEQUENTIAL} at L31 beside {@code RECORD KEY   IS FD-XREF-CARD-NUM} at L32 of
     * {@code app/cbl/CBACT03C.cbl}. Without the ordering the answer would be whichever row the plan
     * happened to reach first and two identical requests could differ.</p>
     *
     * <p>Trade-offs: the read is bounded to one row in the STATEMENT rather than by reading the account's
     * rows and keeping the first. An account with many cards would otherwise materialise every one of
     * them to answer a question about a single row, and every row carries a primary account number, so
     * the wider read would carry cardholder data into this process's heap for no purpose.</p>
     *
     * @param accountId the account to resolve, the eleven-digit identifier {@code XREF-ACCT-ID} declares
     *     at L7 of {@code app/cpy/CVACT03Y.cpy}
     * @return the account, the customer and the card number the lowest-ordering cross-referenced card
     *     names, a {@link CardXrefByAccountView}, never {@code null}
     * @throws NoSuchElementException if the account has no cross-referenced card, which the shared advice
     *     renders as HTTP 404
     */
    @Transactional(readOnly = true)
    public CardXrefByAccountView resolveCardCrossReferenceByAccount(long accountId) {
        // WHY : Refactoring Rationale: the account-keyed operation answers with the WIDER projection,
        //       which carries the card number the row resolved to. The narrower shape published the
        //       account and the customer and withheld the one column the caller asked the question to
        //       learn, so a consuming context had to issue a second, card-keyed call to discover the
        //       value this row already held.
        return this.crossReferences.findFirstByAccountIdOrderByCardNumAsc(accountId)
                .map(this.contextMapper::toCardXrefByAccountView)
                // WHY : Refactoring Rationale: the raised message names NO identifier at all, where it
                //       previously named the account and argued that an account identifier was
                //       admissible because it "already travelled in the request". That argument is
                //       refuted by the migration's own logging contract, which covers account and
                //       customer identifiers by name alongside the primary account number and requires
                //       a prohibited value to be OMITTED rather than abbreviated. A message travels
                //       further than the request that provoked it: it reaches the operational record
                //       through whatever advice or handler catches it, and a record holding the
                //       identifier plus a timestamp locates the customer, the cards and the
                //       transactions without holding any of them.
                // WHY : Assumptions: what remains identifies the CONDITION rather than the row, and
                //       that is sufficient here because the caller supplied the key and the correlation
                //       identifier on the response ties the refusal to the request in the operational
                //       record. A message left with nothing safe to say says only what happened.
                .orElseThrow(() -> new NoSuchElementException(
                        "the account has no cross-referenced card"));
    }

    /**
     * Walks one account's cross-reference rows a page at a time, positioned by key rather than by count.
     *
     * <p>Refactoring Rationale: this is the SUPERSET of the single account-keyed read above, and it
     * exists because that read cannot describe an account holding several cards. The reference answers
     * with one record because {@code app/cbl/COACTVWC.cbl} L727 issues a keyed {@code READ} rather than a
     * browse, whereas {@code idx_card_xref_account_id} is not unique. The divergence is recorded rather
     * than introduced quietly, and the single-record shape stays available beside this one.</p>
     *
     * <p>Refactoring Rationale: the browse state that used to live outside the request lives in the
     * request now. The reference kept it in a structure the terminal echoed back, declared from L229 of
     * {@code app/cbl/COCRDLIC.cbl}, so continuity depended on the client returning that buffer intact;
     * the batch reader kept it in an open file handle instead, opened at L118 of
     * {@code app/cbl/CBACT03C.cbl} and released at L136. A sealed token in the request needs neither, and
     * it is verified on the way back in rather than trusted.</p>
     *
     * <p>Assumptions: the further-page indicator is settled by reading ONE row beyond the page and
     * observing whether it arrived, never by counting how many rows exist. That is the reference's own
     * device -- {@code app/cbl/COCRDLIC.cbl} sets the indicator at L242 through L244 after discovering a
     * record beyond the seven a screen holds -- and the surplus row's key is dropped rather than
     * published, because publishing it would advance the cursor past a row the caller never received.</p>
     *
     * <p>Assumptions: a backward step with NO cursor reads the first page forward rather than failing. A
     * backward walk is only expressible from a set already returned, so there is no earlier position to
     * seek from, and the reference reaches the same answer when the backward key it holds is still its
     * low-value sentinel at L243 of {@code app/cbl/COCRDLIC.cbl}.</p>
     *
     * @param accountId the account whose rows are walked, the eleven-digit identifier
     *     {@code XREF-ACCT-ID} declares at L7 of {@code app/cpy/CVACT03Y.cpy}
     * @param cursor the sealed boundary token the previous page ended on, or {@code null} or blank to
     *     read the first page
     * @param direction the step to take, {@code previous} to walk backward and anything else to walk
     *     forward
     * @param subject the validated caller the cursor is sealed for, taken from the token rather than
     *     from the request body; must not be {@code null}
     * @return one page of rows in ascending card-number order with both boundary tokens sealed and the
     *     further-page indicator settled, a {@link PageResponse} of {@link CardXrefResponse}, never
     *     {@code null}
     * @throws NullPointerException if {@code subject} is {@code null}
     * @throws CursorToken.InvalidCursorException if the cursor cannot be opened, or was sealed for
     *     another query, another subject or the other direction, which the shared advice renders as HTTP
     *     400
     */
    @Transactional(readOnly = true)
    public PageResponse<CardXrefResponse> listCardCrossReferences(
            long accountId, String cursor, String direction, String subject) {

        Objects.requireNonNull(subject, "subject must not be null");

        boolean backward = CARD_XREF_DIRECTION_PREVIOUS.equals(direction);
        String position = cursor == null || cursor.isBlank()
                ? null
                : this.cursorToken.open(
                        cardXrefCursorBinding(accountId, subject, backward), cursor);

        // WHY : Assumptions: a backward step needs a position and the repository's backward statement
        //       declares its cursor mandatory, so an absent one cannot be passed to it. Reading forward
        //       from the start of the set is the answer rather than a refusal, for the reason recorded
        //       in this method's contract.
        List<CardXref> window = position == null
                ? this.crossReferences.findForwardFromCursor(
                        null, accountId, Limit.of(CARD_XREF_PAGE_SIZE + 1))
                : cardXrefWindow(accountId, position, backward);

        return cardXrefPage(window, backward && position != null, accountId, subject);
    }

    /**
     * Reads one row beyond a page in the direction asked for, from a known position.
     *
     * @param accountId the account the read is narrowed to, resolving through
     *     {@code idx_card_xref_account_id}
     * @param position the card number the previous page bounded on, exclusive
     * @param backward {@code true} to read the rows preceding the position in descending order,
     *     {@code false} to read those following it in ascending order
     * @return the rows the statement returned, at most one more than a page holds, never {@code null}
     */
    private List<CardXref> cardXrefWindow(long accountId, String position, boolean backward) {
        Limit limit = Limit.of(CARD_XREF_PAGE_SIZE + 1);

        // WHY : Trade-offs: the bound is one row wider than the page in both directions, which is the
        //       cost of settling the further-page indicator without a count. A count over the account's
        //       rows would answer the same question and would scan every row the index holds for that
        //       account, where this reads one.
        return backward
                ? this.crossReferences.findBackwardFromCursor(position, accountId, limit)
                : this.crossReferences.findForwardFromCursor(position, accountId, limit);
    }

    /**
     * Assembles a read window into the published page envelope, boundaries sealed.
     *
     * <p>Assumptions: the surplus row is dropped from the END of the window in BOTH directions, and the
     * backward window is reversed only afterwards. The repository's backward statement returns descending
     * rows, so its surplus row is the smallest key and therefore the last element, while the row adjacent
     * to the cursor is the first; reversing before dropping would discard that adjacent row and leave a
     * gap at the boundary that no caller could detect.</p>
     *
     * @param window the rows the statement returned, at most one more than a page holds
     * @param backward {@code true} when the window was read in descending order and must be reversed
     *     into presentation order
     * @param accountId the account being walked, sealed into both boundary tokens so neither can
     *     reposition a walk of a different account
     * @param subject the validated caller the boundary tokens are sealed for
     * @return the page envelope carrying the rows, both sealed boundaries and the further-page
     *     indicator, never {@code null}
     */
    private PageResponse<CardXrefResponse> cardXrefPage(
            List<CardXref> window, boolean backward, long accountId, String subject) {

        List<CardXref> rows = new ArrayList<>(window);
        boolean more = rows.size() > CARD_XREF_PAGE_SIZE;
        if (more) {
            rows.remove(rows.size() - 1);
        }

        // WHY : Assumptions: an exhausted walk yields the shared empty envelope rather than a page
        //       naming boundaries it has no rows to take them from. An account legitimately holds no
        //       card, and a browse over an absent alternate-index key ends the same way rather than
        //       failing.
        if (rows.isEmpty()) {
            return PageResponse.empty();
        }
        if (backward) {
            Collections.reverse(rows);
        }

        List<CardXrefResponse> items = this.crossReferenceMapper.toCardXrefResponses(rows);
        String leading = rows.get(0).getCardNum();
        String trailing = rows.get(rows.size() - 1).getCardNum();

        // WHY : Assumptions: a backward page always reports a further page forward, because the set the
        //       caller stepped back from is itself ahead of this one. The reference makes the same
        //       unconditional claim on its backward path rather than probing for it.
        // WHY : Refactoring Rationale: no mirror-image claim is made in the backward direction, because
        //       this envelope carries the backward POSITION and not a backward answer. The reference asks
        //       that question of the terminal's own page ordinal rather than of the file, so the answer
        //       belongs to the client that holds the ordinal, and the shared envelope stays at the four
        //       members every consumer of it declares.
        return PageResponse.ofRows(items,
                this.cursorToken.seal(cardXrefCursorBinding(accountId, subject, true), leading),
                this.cursorToken.seal(cardXrefCursorBinding(accountId, subject, false), trailing),
                backward || more);
    }

    /**
     * Composes the binding a cross-reference cursor is sealed against.
     *
     * <p>Assumptions: the binding names the query, the subject, the ACCOUNT being walked and the
     * direction together, so a token is usable only for the walk, the caller, the parent and the step it
     * was issued for. Sealing on the subject alone would let one caller present the leading boundary of a
     * page as a trailing one and step over rows.</p>
     *
     * <p>Refactoring Rationale: the account was ABSENT from this binding and is added here, because
     * without it a cursor issued while walking account A opened cleanly while walking account B. The
     * consequence was not a leak -- the row filter is the account in the path, so no other account's rows
     * were returned -- but a silent misposition: the cursor names a card number, the predicate is keyed on
     * it, and the same card number under a different account is a valid position that the caller never
     * saw. A page could therefore begin part way through account B's rows, or be empty, with nothing in
     * the response saying so.</p>
     *
     * <p>Assumptions: the account is rendered zero-padded to its declared eleven digits, because the value
     * is a {@code long} in Java and {@code XREF-ACCT-ID PIC 9(11)} in the record at L7 of
     * {@code app/cpy/CVACT03Y.cpy}. Padding keeps one account's rendering stable whatever formats it, so a
     * token cannot fail to open merely because two code paths rendered the same account differently.</p>
     *
     * <p>Alternatives Considered: concatenating the account and the direction into one string here.
     * Rejected because a hand-joined scope is not injective unless it also escapes, and the shared
     * composer already length-prefixes each predicate for exactly that reason -- see
     * {@link CursorToken#scope}.</p>
     *
     * @param accountId the account whose rows the token positions within
     * @param subject the validated caller the token is issued for
     * @param backward {@code true} for the backward scope, {@code false} for the forward scope
     * @return the binding string the seal and the matching open are performed with, never {@code null}
     */
    private static String cardXrefCursorBinding(long accountId, String subject, boolean backward) {
        return CursorToken.binding(CARD_XREF_CURSOR_QUERY, subject,
                CursorToken.scope(
                        backward ? CARD_XREF_CURSOR_SCOPE_BACKWARD : CARD_XREF_CURSOR_SCOPE_FORWARD,
                        CARD_XREF_CURSOR_SCOPE_ACCOUNT_PREFIX
                                + String.format(Locale.ROOT, "%011d", accountId)));
    }

    /**
     * Edits a screen-supplied account filter and reports its tri-state validation outcome.
     *
     * <p>Purpose: this is {@code 2210-EDIT-ACCOUNT} at L649 of {@code app/cbl/COACTVWC.cbl}, exit at
     * L683. The reference holds the outcome in {@code WS-EDIT-ACCT-FLAG PIC X(1)} at L58 with the three
     * conditions at L59, L60 and L61, and this method returns that flag rather than mutating shared
     * storage. The three states line up exactly with the shared type's own codes: the reference spells
     * unacceptable as the digit zero at L59, acceptable as the digit one at L60 and never-supplied as a
     * SPACE at L61, which is the alternate blank code rather than the letter the shared type takes as
     * canonical.</p>
     *
     * <p>Assumptions: the flag starts unacceptable at L650 and only the final branch at L677 through
     * L679 makes it acceptable, so a value reaching neither guard is still rejected. That default is
     * transcribed as the ordering of the tests below: each guard returns, and acceptance is what is
     * left.</p>
     *
     * <p>Assumptions: the never-supplied test at L653 and L654 admits both the low-value regime and
     * spaces, and the screen edit above it at L628 through L633 folds the literal asterisk marker into
     * the same outcome. The shared type's never-supplied predicate answers for exactly that set,
     * including the marker it publishes for the blank case, so the predicate is consumed rather than
     * restated here.</p>
     *
     * <p>Trade-offs: the reference's own numeric test is {@code IF CC-ACCT-ID IS NOT NUMERIC} at L666
     * over the eleven-character field declared at L34 with L35 of {@code app/cpy/CVCRD01Y.cpy}, so on a
     * terminal a value shorter than eleven arrives space-padded and fails that test. This method
     * instead strips the fixed-width padding and then requires digits only, no more than the declared
     * width, and not all zeroes. The compromise is deliberate and its consequence is stated: a caller
     * supplying fewer than eleven digits is accepted here where a terminal user typing the same value
     * would have been rejected. The padding that caused that rejection is the terminal field's and not
     * the identifier's -- the identifier is declared {@code PIC 9(11)} in the redefinition at L36 of
     * that same copybook, and the reference itself supplies it left-zero-padded through the character
     * redefinition at L79 and L80 of {@code app/cbl/COACTVWC.cbl}. Rejecting a short value over HTTP
     * would therefore enforce a screen geometry rather than a rule about accounts. Both remaining tests
     * are carried unchanged, so an all-zero filter is still rejected exactly as L667 rejects it, and
     * the divergence is registered in the traceability matrix.</p>
     *
     * @param screenAccountFilter the account filter as supplied, a {@code String} that may be
     *     {@code null}, may be blank, and may carry the blank-case screen marker
     * @return {@link FieldValidationFlag#BLANK} when no filter was supplied,
     *     {@link FieldValidationFlag#NOT_OK} when one was supplied but is not a non-zero identifier of
     *     at most the declared width, and {@link FieldValidationFlag#VALID} otherwise; never
     *     {@code null}
     */
    public FieldValidationFlag editAccountFilter(String screenAccountFilter) {
        String supplied = withoutScreenMarker(screenAccountFilter);

        // WHY : Assumptions: the blank arm is tested FIRST because the reference tests it first, at
        //       L653 and L654, and returns at L661 before the numeric test at L666 is reached. The
        //       order is observable rather than cosmetic: an unsupplied field is not all digits
        //       either, so testing the numeric guard first would report every unsupplied field as
        //       unacceptable and lose the never-supplied state the screen marker depends on.
        if (FieldValidationFlag.isNeverSupplied(supplied)) {
            return FieldValidationFlag.BLANK;
        }

        String candidate = supplied.trim();

        // WHY : Assumptions: the two rejections are one guard here because the reference joins them
        //       with OR at L666 and L667 and gives both arms the same body, the single literal at
        //       L672. Splitting them would imply two messages where the reference emits one.
        if (!isEntirelyDigits(candidate)
                || candidate.length() > ACCOUNT_FILTER_WIDTH
                || isEntirelyZeros(candidate)) {
            return FieldValidationFlag.NOT_OK;
        }

        return FieldValidationFlag.VALID;
    }

    /**
     * Reports the per-field error array a rejected account filter produces.
     *
     * <p>Purpose: this is the migrated form of the reference's field-highlight contract. The reference
     * records a rejected field as a flag plus an aggregate sentence and, on the screen, as a colour
     * change with a literal marker for the never-supplied case; transformation rule T7 turns that pair
     * into a structured per-field entry in the response body. The shared validation type owns both the
     * conversion and the marker, so neither is decided here.</p>
     *
     * <p>Assumptions: an acceptable filter yields an EMPTY array rather than an entry saying so,
     * because the shared type refuses to build an entry for an acceptable state at all. That refusal
     * is what makes the presence of an entry sufficient evidence of a rejection, and the reference
     * agrees: the acceptable arm at L677 through L679 of {@code app/cbl/COACTVWC.cbl} sets the flag and
     * touches the message channel not at all, so there is nothing for an entry to carry.</p>
     *
     * @param screenAccountFilter the account filter as supplied, a {@code String} that may be
     *     {@code null} or blank
     * <p>Refactoring Rationale: this method and {@link #readAccountView} are TOGETHER the surviving form
     * of the reference's re-entry dispatch arm, {@code WHEN CDEMO-PGM-REENTER} at L361 of
     * {@code app/cbl/COACTVWC.cbl}, which edits the filter at L362 and then either re-renders with the
     * message at L364 through L367 or performs the three-hop read at L369. A single method that did both
     * also existed here and has been withdrawn: it was reached by nothing, and it answered a rejected
     * filter with a 200 carrying an unpopulated view, where the published operation answers 400 with the
     * refusal keyed to the field. Two implementations of one dispatch arm disagreeing about the status
     * code is worse than one, and the adapter owns the status.</p>
     *
     * <p>Assumptions: the other two reference dispatch arms have no server call at all -- the
     * function-key arm at L324 is client-side navigation and the first-entry arm at L353 renders an empty
     * form -- so this pair is the whole of the surviving dispatch.</p>
     *
     * @return the entries for this one field as a {@code List} of {@link ApiError.FieldError}, empty
     *     when the filter is acceptable and never larger than one entry; never {@code null}
     */
    public List<ApiError.FieldError> accountFilterFieldErrors(String screenAccountFilter) {
        AccountFilterEdit edit = editMapInputs(screenAccountFilter);

        // WHY : Assumptions: the entry carries the SAME sentence the aggregate channel carries, not a
        //       second wording of it. The reference has one message per rejected filter -- the literal
        //       at L672 for an unacceptable value, the cross-field text at L641 for an unsupplied one
        //       -- so a per-field entry with wording of its own would be text no reference line
        //       produces, and the screen and the field array could then disagree about why the filter
        //       was refused.
        return edit.state().toFieldError(ACCOUNT_FILTER_FIELD, edit.returnMessage())
                .map(ApiError.FieldError::from)
                .map(List::of)
                .orElseGet(List::of);
    }

    /**
     * Edits the screen inputs of the view map and carries both halves of the outcome.
     *
     * <p>Purpose: this is {@code 2200-EDIT-MAP-INPUTS} at L622 of {@code app/cbl/COACTVWC.cbl}, exit at
     * L645. It performs the field edit and then the one cross-field edit at L640 through L642.</p>
     *
     * <p>Assumptions: the cross-field edit REPLACES the message the field edit latched, and it is the
     * only assignment to that channel in the whole program that carries no message-off guard. L657
     * through L659 latches the not-provided text under the guard, and then L641 sets the
     * no-input-received text unconditionally, so the second is what a user reads. Transcribing only
     * the second would hide that the first is reached; transcribing only the first would publish text
     * the screen never shows.</p>
     *
     * @param screenAccountFilter the account filter as supplied, a {@code String} that may be
     *     {@code null}, may be blank, and may carry the blank-case screen marker
     * @return the validation state paired with the aggregate message the reference would have latched,
     *     an {@link AccountFilterEdit}, never {@code null}
     */
    private AccountFilterEdit editMapInputs(String screenAccountFilter) {
        FieldValidationFlag state = editAccountFilter(screenAccountFilter);

        // WHY : Assumptions: the channel starts UNSET rather than empty, because its off-state is
        //       declared as spaces at L118 and the response record documents an unset channel as
        //       absent rather than blank. Starting it empty would publish a set-but-empty channel, a
        //       state neither the reference nor the contract has an encoding for.
        String returnMessage = null;

        if (state == FieldValidationFlag.BLANK) {
            returnMessage = latchedReturnMessage(returnMessage, RETURN_PROMPT_FOR_ACCOUNT);
        }

        if (state == FieldValidationFlag.NOT_OK) {
            returnMessage = latchedReturnMessage(returnMessage, RETURN_ACCOUNT_FILTER_REJECTED);
        }

        // WHY : Refactoring Rationale: this third assignment is a plain overwrite and NOT a latch,
        //       and the difference is the reference's own. The cross-field edit at L640 through L642
        //       carries no message-off guard, unlike every other assignment to this channel, so it
        //       supersedes the not-provided text the field edit latched two statements ago. Routing
        //       it through the latch would preserve the first message and invert that behaviour;
        //       omitting the latched assignment entirely would hide that the reference reaches it.
        if (state == FieldValidationFlag.BLANK) {
            returnMessage = RETURN_NO_SEARCH_CRITERIA;
        }

        return new AccountFilterEdit(state, returnMessage);
    }

    /**
     * The outcome of editing the account filter: its validation state and the message it latched.
     *
     * <p>Refactoring Rationale: the reference keeps these two in separate {@code WORKING-STORAGE}
     * items, the flag at L58 and the aggregate channel at L117 of {@code app/cbl/COACTVWC.cbl}, and
     * every paragraph that touches either reaches it as shared state. A method returning both together
     * is what lets the edit be exercised on its own, which is the property the package charter cites as
     * the reason the service layer exists at all; a field pair on this class would reintroduce exactly
     * the shared mutable storage the migration removes and would make one instance unable to serve two
     * requests at once.</p>
     *
     * @param state the tri-state validation outcome, a {@link FieldValidationFlag}; never {@code null}
     * @param returnMessage the aggregate-channel sentence, a {@code String} of at most the
     *     seventy-five characters L117 declares, or {@code null} when the channel is unset
     */
    private record AccountFilterEdit(FieldValidationFlag state, String returnMessage) {
    }

    /**
     * Runs the three-hop composition under the program-wide abend handler.
     *
     * <p>Purpose: this is the registration the reference performs before anything else, the
     * {@code EXEC CICS HANDLE ABEND LABEL(ABEND-ROUTINE)} at L264 through L266 of
     * {@code app/cbl/COACTVWC.cbl}. It is a separate method from the composition it guards so that the
     * registration is visible as its own step, exactly as it is in the reference, rather than being
     * folded into the paragraph it protects.</p>
     *
     * <p>Trade-offs: a failure this class has already diagnosed is re-raised unchanged and only an
     * undiagnosed one is wrapped, which is what keeps the two abend surfaces apart. The alternative --
     * letting every failure reach the shared advice untouched -- was rejected because the reference
     * stamps a culprit and a condition code onto every abend it handles, at L922 and at L935, and that
     * stamping is the whole content of the surface; losing it would leave a five-hundred response whose
     * log line cannot say which migrated program raised it. What is accepted is one extra frame on an
     * already-failing path and a broad catch, and the cause is chained so the shared advice still
     * renders and logs the original.</p>
     *
     * @param accountKey the account to compose the view for, the eleven-digit identifier
     * @return the composed view with its revision, or the view the reference would have re-rendered when
     *     a read missed carrying no revision; a {@link RevisionedAccountView}, never {@code null}
     * @throws NoSuchElementException never raised from here, and propagated unchanged when the
     *     composition raises it
     * @throws IllegalStateException if the composition reaches the unclassifiable data scenario of
     *     L375 through L380, or if any other runtime failure escapes it, in which case the message
     *     carries the abend code and culprit of L935 and L922 and the original failure is the cause
     */
    private RevisionedAccountView readAccountUnderAbendHandler(long accountKey) {
        try {
            return readAccount(accountKey);
        } catch (NoSuchElementException | IllegalStateException diagnosed) {
            // WHY : Assumptions: these two types are the ones this class raises deliberately -- the
            //       absence outcome and the first abend surface -- so each already carries the
            //       reference's own diagnosis. Re-raising them unchanged is what stops the second
            //       surface from overwriting the first, which would report every miss as an abend.
            throw diagnosed;
        } catch (RuntimeException undiagnosed) {
            throw new IllegalStateException(
                    renderedAbend(abendRoutineDetail(null), null), undiagnosed);
        }
    }

    /**
     * Composes one account view from three ordered keyed reads, abandoning the sequence at the first
     * miss.
     *
     * <p>Purpose: this is {@code 9000-READ-ACCT} at L687 of {@code app/cbl/COACTVWC.cbl}, exit at L720.
     * It drives {@code 9200-GETCARDXREF-BYACCT} at L723, then {@code 9300-GETACCTDATA-BYACCT} at L774,
     * then {@code 9400-GETCUSTDATA-BYCUST} at L825, with a gate after each.</p>
     *
     * <p>Assumptions: the ORDER is load-bearing and is not an arbitrary sequencing of three
     * independent reads. The cross-reference is read first because it is the only thing that yields the
     * customer key the third read needs, which the reference states at L737 through L740 by moving the
     * cross-reference row's customer and card identifiers into the selection context; the reference
     * then moves that customer identifier into the customer read key at L708. Reordering the reads
     * would leave the third with no key at all.</p>
     *
     * <p>Assumptions: each gate returns before the following read is issued, so a miss never causes a
     * read that the reference would not have performed. That is the observable content of the three
     * {@code GO TO} statements at L698, L705 and L714.</p>
     *
     * <p>Assumptions: the first gate tests the validation FLAG and the second and third test a MESSAGE
     * CONDITION, transcribed as they stand. At L696 the message-condition test for the cross-reference
     * is commented out and the flag test at L697 is the live one; L704 and L713 test message conditions
     * directly. The commented-out line is recorded as observed and is not reinstated.</p>
     *
     * <p>Assumptions: which halves a miss leaves populated follows the reference's two screen guards
     * rather than a uniform rule. The account region is filled under the disjunction at L471 with L472
     * and the customer region under the single condition at L493, so a customer miss on an account that
     * WAS located yields the account half with no customer half, while a miss on either of the first
     * two reads yields neither. The two read flags at L83 and L85 therefore survive as the presence of
     * the two nested groups in the response rather than as fields on this class.</p>
     *
     * @param accountKey the account to compose the view for, the eleven-digit identifier the reference
     *     moves into the read key at L691
     * @return the composed view with the revision its two rows stand at when all three reads resolve,
     *     otherwise the view the reference would have re-rendered carrying the latched message and no
     *     revision; a {@link RevisionedAccountView}, never {@code null}
     * @throws IllegalStateException if the located cross-reference row names no customer, which is a
     *     state the record layout has no encoding for and which the reference would reach as the
     *     unclassifiable data scenario of L375 through L380
     */
    private RevisionedAccountView readAccount(long accountKey) {
        // WHY : Assumptions: the channel starts unset because the reference clears it once, at L278,
        //       before any branch runs, and the informational channel is cleared at L689 at the head
        //       of this very paragraph. Neither is a field on this class, so both are locals and one
        //       request cannot see another's message.
        String returnMessage = null;
        FieldValidationFlag accountFilterState = FieldValidationFlag.VALID;

        // WHY : Refactoring Rationale: all three sides are read by ONE statement, so the account and the
        //       customer this screen publishes are observed under one snapshot. Three statements inside one
        //       read-only transaction did NOT give that, because this datasource runs at read-committed
        //       isolation where each statement takes its own snapshot -- so a concurrent update committing
        //       between the second and third statement produced an account from before it beside a customer
        //       from after it, a pairing that never existed, published as though it had. The rationale on
        //       the public entry point asserted the guarantee; this statement is what makes the assertion
        //       true.
        // WHY : Trade-offs: the three gates below no longer PREVENT a read the way the reference's GO TO
        //       statements at L698, L705 and L714 did -- the sides are evaluated together. Nothing
        //       observable changes, because a read has no side effect and the outcome is still decided in
        //       the reference's own order from which sides came back empty.
        Optional<AccountScreenRow> composed = readAccountScreenRow(accountKey);

        Optional<CardXref> crossReference = composed.map(AccountScreenRow::crossReference);
        if (crossReference.isEmpty()) {
            accountFilterState = FieldValidationFlag.NOT_OK;
            returnMessage = latchedReturnMessage(returnMessage, RETURN_NOT_FOUND_IN_CARD_XREF);
        }

        if (accountFilterState.isError()) {
            return withoutRevision(unpopulatedView(accountKey, returnMessage));
        }

        // WHY : Assumptions: the value is taken directly rather than defensively, and the call is safe
        //       because the only assignment that puts the flag into an error state is the empty arm
        //       above, whose gate at app/cbl/COACTVWC.cbl L697 and L698 has already returned; reaching
        //       this statement therefore means the row is present. The identifier the row carries may
        //       still be absent, which the guard further down answers for.
        Long customerKey = crossReference.get().getCustomerId();

        // WHY : Assumptions: the account side is absent exactly when the outer join found no master row,
        //       which is the migrated form of the second read missing. The join is OUTER for this reason:
        //       an inner one would have collapsed this miss and the customer miss into one empty result,
        //       and each arm carries its own verbatim sentence and its own rule about which half of the
        //       screen is published.
        Optional<Account> account = composed.map(AccountScreenRow::account)
                .filter(Objects::nonNull);
        if (account.isEmpty()) {
            returnMessage = latchedReturnMessage(returnMessage, RETURN_NOT_FOUND_IN_ACCOUNT_MASTER);
        }

        // WHY : Assumptions: this gate compares the CHANNEL against a declared condition text rather
        //       than testing a flag, because that is the shape of the reference's own test at L704 --
        //       the condition it names is an eighty-eight level over the seventy-five-character field,
        //       declared at L131 with L132. The comparison is what makes the gate govern, since the
        //       statement that would set that condition is commented out at L792.
        if (RETURN_NOT_FOUND_IN_ACCOUNT_MASTER.equals(returnMessage)) {
            return withoutRevision(unpopulatedView(accountKey, returnMessage));
        }

        if (customerKey == null) {
            // WHY : Assumptions: an absent customer identifier on a located cross-reference row is
            //       neither a miss nor a rejected input, so neither of the other two outcomes fits.
            //       The record layout declares that field as nine unsigned digits at L6 of
            //       app/cpy/CVACT03Y.cpy and has no encoding for its absence, which is exactly the
            //       unclassifiable data scenario the reference falls through to at L375.
            throw new IllegalStateException(renderedAbend(
                    unexpectedDataScenarioDetail(), RETURN_UNEXPECTED_DATA_SCENARIO));
        }

        // WHY : Assumptions: the customer side is joined on the identifier the CROSS-REFERENCE carries and
        //       never on one taken from the account row, because the account record declares no customer
        //       identifier at all -- the twelve named fields at L5 through L16 of app/cpy/CVACT01Y.cpy
        //       contain no such field. The reference obtains it at L739 and moves it into the third read's
        //       key at L708, which is the same derivation the join predicate expresses.
        Optional<Customer> customer = composed.map(AccountScreenRow::customer)
                .filter(Objects::nonNull);
        if (customer.isEmpty()) {
            returnMessage = latchedReturnMessage(returnMessage, RETURN_NOT_FOUND_IN_CUSTOMER_MASTER);
        }

        if (RETURN_NOT_FOUND_IN_CUSTOMER_MASTER.equals(returnMessage)) {
            // WHY : Assumptions: the account half IS published on this arm, unlike the two above,
            //       because the reference's account region is guarded by a disjunction at L471 and
            //       L472 that the located account already satisfies through the read flag it sets at
            //       L788. Suppressing it here would withhold ten fields the screen shows.
            return withoutRevision(this.accountMapper.toAccountViewResponse(
                    account.get(), null, INFO_PROMPT_FOR_INPUT, returnMessage));
        }

        // WHY : Assumptions: the revision is derived from the SAME two rows the body is mapped from, so
        //       the pair the caller receives cannot describe two different states. Both rows arrived on
        //       one composition row from one statement, which is what makes the derivation meaningful --
        //       deriving it from a second read would reintroduce the two-snapshot defect this carrier
        //       exists to close.
        // WHY : Assumptions: the derivation goes through AccountRevision rather than being written out
        //       here, so this token and the one the write path compares cannot differ in format.
        return new RevisionedAccountView(
                this.accountMapper.toAccountViewResponse(
                        account.get(),
                        this.customerMapper.toCustomerDetail(customer.get()),
                        INFO_PROMPT_FOR_INPUT,
                        returnMessage),
                AccountRevision.of(account.get(), customer.get()));
    }

    /**
     * Pairs an incomplete composition with no revision.
     *
     * <p>Assumptions: a composition missing either master row has NO revision, and the absence is
     * expressed as {@code null} rather than as an empty or sentinel token, because a caller cannot form
     * a precondition for rows that were not both located.</p>
     *
     * <p>⚠️ Refactoring Rationale: one of these compositions IS published now. This note read that the
     * class never publishes them because {@link #readAccountView} raised on every incomplete one, and
     * that stopped being true when the customer-miss arm became a 200: the adapter answers it with no
     * {@code ETag} header at all rather than with a tag naming nothing.</p>
     *
     * @param view the re-rendered view the reference would have sent; must not be {@code null}
     * @return the view carried with no revision, a {@link RevisionedAccountView}, never {@code null}
     */
    private static RevisionedAccountView withoutRevision(AccountViewResponse view) {
        return new RevisionedAccountView(view, null);
    }

    /**
     * Reads one account's whole screen composition in a single statement.
     *
     * <p>Purpose: this is the whole of {@code 9000-READ-ACCT} at L687 of {@code app/cbl/COACTVWC.cbl},
     * whose three keyed reads are {@code 9200-GETCARDXREF-BYACCT} at L723, {@code 9300-GETACCTDATA-BYACCT}
     * at L774 and {@code 9400-GETCUSTDATA-BYCUST} at L825.</p>
     *
     * <p>Assumptions: each of the three reference reads survives as one side of the statement, and the
     * access path of each is preserved rather than merely its result. The cross-reference is reached BY
     * ACCOUNT, which the base cluster cannot answer -- the reference reads through the alternate index
     * named at L192 and L193, supplying the account as the record identification field at L729 -- and the
     * target reaches the same path through the secondary index {@code idx_card_xref_account_id}. The
     * account master is reached by its PRIMARY key, which {@code app/cbl/CBACT01C.cbl} states with
     * {@code RECORD KEY IS FD-ACCT-ID} at L32 for the same file, which is why that side is an equality on
     * the identifier and the first is not. The customer master is likewise reached by its primary key,
     * with the key coming from the cross-reference.</p>
     *
     * <p>Assumptions: ONE row is asked for, because the reference issues a keyed read carrying no browse
     * start and consumes exactly one row on its normal arm at L739 and L740. The by-account index is
     * non-unique, since one account holds many cards, so the statement states an ordering and this bound
     * takes the lowest card number; without the ordering the row returned would be whichever the plan
     * reached first and two identical requests could differ.</p>
     *
     * <p>Assumptions: the account master row is returned AS STORED. The batch reader substitutes a literal
     * amount for a zero current-cycle debit at L236 through L238 of {@code app/cbl/CBACT01C.cbl}, but it
     * does so while filling its OUTPUT record rather than while reading the master, which it reads
     * unchanged at L166. That substitution is an artefact of the extract file and is not reproduced on a
     * read path, so a zero current-cycle debit is published as zero.</p>
     *
     * @param accountKey the account whose composition is wanted, the eleven-digit identifier the reference
     *     moves into the read key at L691
     * @return the composition row, or an empty result when the cross-reference holds no row for the
     *     account; an {@code Optional} of {@link AccountScreenRow}, never {@code null}
     */
    private Optional<AccountScreenRow> readAccountScreenRow(long accountKey) {
        return this.crossReferences.findAccountScreenRows(accountKey, Limit.of(1))
                .stream()
                .findFirst();
    }

    /**
     * Builds the view the reference re-renders when a read missed, with neither nested half populated.
     *
     * <p>Purpose: this is the screen the reference sends at L365 and L366 of
     * {@code app/cbl/COACTVWC.cbl} after the input-error branch at L387 through L392 moves the
     * seventy-five-character channel into the screen's error field. Neither nested half is populated
     * because both screen guards, the disjunction at L471 with L472 and the condition at L493, are
     * false when no master row was located.</p>
     *
     * <p>Assumptions: the identifier is echoed as supplied and is NOT zeroed on this path, which is the
     * one place it differs from a rejected filter. Only the field edit zeroes it, at L660 and L675;
     * a read that missed leaves the selection identifier alone, and the screen setup echoes it at
     * L468.</p>
     *
     * @param accountKey the identifier to echo, the eleven-digit key as supplied, or zero on the
     *     rejected-filter path where the field edit has already zeroed it
     * @param returnMessage the latched aggregate message, a {@code String} of at most the
     *     seventy-five characters L117 declares; may be {@code null} when the channel is unset
     * @return the re-rendered view carrying both message channels and no nested half, an
     *     {@link AccountViewResponse}, never {@code null}
     */
    private AccountViewResponse unpopulatedView(long accountKey, String returnMessage) {
        return new AccountViewResponse(
                AccountMapper.accountIdentifierDigits(accountKey),
                null,
                null,
                INFO_PROMPT_FOR_INPUT,
                returnMessage);
    }

    /**
     * Builds the absence outcome for the routes that express a miss as a raised failure.
     *
     * <p>Assumptions: the sentence handed in is the reference's own text, already latched by whichever
     * hop missed, so this method composes nothing of its own. Whether that sentence reaches a response
     * body is decided by the shared advice, which admits a migrated sentence only on shape grounds, and
     * that decision is deliberately not restated here.</p>
     *
     * @param returnMessage the latched aggregate message naming which read missed, a {@code String};
     *     may be {@code null} when the channel is unset, which the shared advice answers for by
     *     rendering its own not-found sentence
     * @return the failure to raise, a {@link NoSuchElementException}, never {@code null}
     */
    private NoSuchElementException notFound(String returnMessage) {
        return new NoSuchElementException(returnMessage);
    }

    /**
     * Builds the abend detail of the first surface, the unclassifiable data scenario.
     *
     * <p>Purpose: this is the {@code WHEN OTHER} arm at L375 of {@code app/cbl/COACTVWC.cbl}, which
     * stamps the culprit at L376, the condition code at L377 and a blank reason at L378.</p>
     *
     * <p>Assumptions: the abend MESSAGE component is left blank on purpose. L379 and L380 route the
     * text of this surface into the seventy-five-character channel and NOT into the abend message
     * field, so filling the message here would put the same sentence in two components and let the two
     * disagree. The sentence is carried beside this detail by the caller.</p>
     *
     * @return the detail for the first abend surface, an {@link AbendDetail} carrying the code of L377,
     *     the culprit of L376 and blank reason and message components, never {@code null}
     */
    private AbendDetail unexpectedDataScenarioDetail() {
        return new AbendDetail(
                ABEND_CODE_DATA_SCENARIO, THIS_PROGRAM, ABEND_REASON_BLANK, ABEND_REASON_BLANK);
    }

    /**
     * Builds the abend detail of the second surface, the program-wide handler.
     *
     * <p>Purpose: this is {@code ABEND-ROUTINE} at L916 of {@code app/cbl/COACTVWC.cbl}. It defaults
     * the message at L918 through L920, stamps the culprit at L922 and carries the condition code the
     * abend at L934 through L936 terminates with.</p>
     *
     * <p>Assumptions: the off-state tested here is the LOW-VALUE regime and not the blank regime,
     * because L918 tests the message component against low values whereas the aggregate channel's
     * off-state is declared as spaces at L118. The shared validation predicate consumed here answers
     * for the wider set -- absent, empty, all blanks and all low values -- which is the correct reading
     * for this component and is wider than the reading that channel admits, so the two are tested by
     * different predicates rather than by one shared with the aggregate.</p>
     *
     * @param abendMsg the message to carry, a {@code String}; may be {@code null}, empty or blank, any
     *     of which selects the default text of L919
     * @return the detail for the second abend surface, an {@link AbendDetail} carrying the code of
     *     L935, the culprit of L922, a blank reason and either the supplied message or the default of
     *     L919, never {@code null}
     */
    private AbendDetail abendRoutineDetail(String abendMsg) {
        String message = FieldValidationFlag.isNeverSupplied(abendMsg) ? ABEND_MSG_UNEXPECTED : abendMsg;
        return new AbendDetail(ABEND_CODE_UNHANDLED, THIS_PROGRAM, ABEND_REASON_BLANK, message);
    }

    /**
     * Renders an abend detail and its accompanying text as one diagnostic sentence.
     *
     * <p>Assumptions: the text arrives as a parameter because the two surfaces carry it in different
     * components -- the first in the seventy-five-character channel per L379 and L380 of
     * {@code app/cbl/COACTVWC.cbl}, the second in the abend message component per L919 -- so a renderer
     * that read one component would silently produce a codeless sentence for the other surface.</p>
     *
     * <p>Assumptions: no account identifier, customer identifier or monetary amount reaches this
     * sentence. It is built from a four-character code, an eight-character program name and text taken
     * from a fixed-width operator field, all three of which are migrated constants rather than values
     * read from a row, so the sentence is safe to log and to chain onto a raised failure.</p>
     *
     * @param detail the abend detail to render, an {@link AbendDetail}; must not be {@code null}
     * @param text the accompanying sentence when the surface carries it outside the detail, a
     *     {@code String}; may be {@code null}, in which case the detail's own message component is used
     * @return the diagnostic sentence naming the condition code, the culprit and whichever text was
     *     available, a {@code String}, never {@code null}
     */
    private String renderedAbend(AbendDetail detail, String text) {
        String sentence = isReturnMessageOff(text) ? detail.abendMsg() : text;
        return "abend " + detail.abendCode() + " raised by " + detail.abendCulprit()
                + (sentence.isEmpty() ? "" : ": " + sentence);
    }

    /**
     * Returns the aggregate channel with a candidate applied only when the channel is still unset.
     *
     * <p>Purpose: this is the {@code IF WS-RETURN-MSG-OFF} guard the reference wraps around every
     * assignment to that channel but one, at L657, L670, L744, L793 and L845 of
     * {@code app/cbl/COACTVWC.cbl}. Its effect is first-message-wins.</p>
     *
     * <p>Assumptions: the single exception is the cross-field edit at L640 through L642, which carries
     * no guard and therefore overwrites. That one assignment is written as a plain overwrite at its
     * call site rather than routed through here, so the difference between the two remains visible in
     * the code rather than only in this paragraph.</p>
     *
     * @param current the channel as it stands, a {@code String}; may be {@code null} when unset
     * @param candidate the sentence to apply if the channel is unset, a {@code String}; must not be
     *     {@code null}
     * @return the existing sentence when one is already set, otherwise the candidate, a {@code String},
     *     never {@code null} once a candidate has been applied
     */
    private String latchedReturnMessage(String current, String candidate) {
        return isReturnMessageOff(current) ? candidate : current;
    }

    /**
     * Reports whether the aggregate message channel is in its unset state.
     *
     * <p>Assumptions: the unset state is absent or entirely blank and NOTHING WIDER, because this
     * channel declares its off-state exactly once, as spaces, at L118 of
     * {@code app/cbl/COACTVWC.cbl}. The shared validation predicate is deliberately not reused here:
     * it additionally folds a run of low-value bytes into the same answer, which is correct for a
     * screen input field that genuinely arrives as either pad byte, and admitting that fourth state
     * here would mean silently overwriting a channel the reference treats as set -- the one thing
     * first-message-wins exists to prevent.</p>
     *
     * @param value the channel value to test, a {@code String}; may be {@code null}
     * @return {@code true} when the channel is absent or entirely blank, {@code false} otherwise
     */
    private boolean isReturnMessageOff(String value) {
        return value == null || value.isBlank();
    }

    /**
     * Returns a screen value with the blank-case marker folded into the never-supplied form.
     *
     * <p>Purpose: this is the normalisation at L628 through L633 of {@code app/cbl/COACTVWC.cbl}, which
     * moves low values into the working field when the screen field holds the literal marker or
     * spaces, so that the field edit below it sees one never-supplied state rather than two.</p>
     *
     * <p>Assumptions: the marker is taken from the shared validation type rather than written as a
     * literal here, because that type also WRITES it back for the blank case and a marker read in one
     * place and written in another must be one character, not two that happen to match today. The
     * character being matched is the one the reference compares the screen field against at L628 of
     * {@code app/cbl/COACTVWC.cbl}.</p>
     *
     * @param screenValue the screen field as supplied, a {@code String}; may be {@code null}
     * @return {@code null} when the value is the marker alone, otherwise the value unchanged, a
     *     {@code String} that may be {@code null}
     */
    private String withoutScreenMarker(String screenValue) {
        if (screenValue != null
                && FieldValidationFlag.BLANK_SCREEN_MARKER.equals(screenValue.trim())) {
            return null;
        }
        return screenValue;
    }

    /**
     * Reports whether every character of a value is a decimal digit.
     *
     * <p>Assumptions: the test is written against the digits zero through nine only, and not against a
     * general character-category test, because the reference's own {@code IS NOT NUMERIC} at L666 of
     * {@code app/cbl/COACTVWC.cbl} accepts exactly that set. A category test would additionally accept
     * the decimal digits of other scripts, which the field this migrates could never have held and
     * which would then reach an identifier parse.</p>
     *
     * @param value the value to test, a {@code String}; must not be {@code null} and must not be empty
     * @return {@code true} when every character is one of the ten decimal digits, {@code false}
     *     otherwise
     */
    private static boolean isEntirelyDigits(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < '0' || character > '9') {
                return false;
            }
        }
        return true;
    }

    /**
     * Reports whether a value is entirely the digit zero.
     *
     * <p>Assumptions: this is the second half of the reference's rejection at L667 of
     * {@code app/cbl/COACTVWC.cbl}, which compares the field against the figurative zero constant. That
     * comparison is true for any run of zero digits, so a single zero and eleven zeros are rejected
     * alike; testing a parsed numeric value against zero would reach the same verdict here but would
     * require the parse to have succeeded first, and this test runs before any parse.</p>
     *
     * @param value the value to test, a {@code String}; must not be {@code null} and must not be empty
     * @return {@code true} when every character is the digit zero, {@code false} otherwise
     */
    private static boolean isEntirelyZeros(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) != '0') {
                return false;
            }
        }
        return true;
    }
}
