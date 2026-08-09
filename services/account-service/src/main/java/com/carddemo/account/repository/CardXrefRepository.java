package com.carddemo.account.repository;

import com.carddemo.account.domain.CardXref;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Reads the card-to-account-and-customer cross-reference rows this context owns.
 *
 * <p><b>Purpose.</b> This is the migrated form of every path by which the reference system reaches
 * the cross-reference record: the keyed read entered on a card number, the read entered on an
 * account identifier through a second access path the region surfaced as a file of its own, and the
 * whole-file ordered scan a batch program drives. All three survive here as real access paths, each
 * expressed as a typed method rather than as a file verb, and none of them degrades into an
 * unordered read of the entire table.</p>
 *
 * <h2>The record, and what settles its key</h2>
 *
 * <p>Assumptions: the contract is {@code app/cpy/CVACT03Y.cpy}, whose header comment at L2 announces
 * a fifty-byte record and which declares {@code 01 CARD-XREF-RECORD.} at L4. Its three named fields
 * are a sixteen-character card number at L5, a nine-digit customer identifier at L6 and an
 * eleven-digit account identifier at L7, with fourteen bytes of padding at L8 that map to nothing;
 * sixteen plus nine plus eleven is thirty-six, and thirty-six plus fourteen is the declared fifty.
 * The entity beside this package settles which column each of those fields becomes, and this
 * interface never restates a width or a type of its own.</p>
 *
 * <p>Assumptions: the key is the card number, and that is settled from two places that agree rather
 * than assumed from the field order alone. {@code app/cbl/CBACT03C.cbl} names it outright at L32 as
 * {@code RECORD KEY IS FD-XREF-CARD-NUM}, and its own file-section record corroborates the position
 * from a second direction: L38 opens the record, L39 declares the sixteen-character key field and
 * L40 the thirty-four-byte remainder, which together are again the declared fifty. L45 of that
 * program confirms the copybook above is the contract it reads through.</p>
 *
 * <h2>The keyed read, and why a named method stands beside the inherited one</h2>
 *
 * <p>Assumptions: the by-key access path is the {@code findById} inherited from the Spring Data
 * interface, keyed on the card-number column, and it is not redeclared or overridden here. Its
 * argument type is the reason this interface's key type parameter is textual rather than numeric,
 * which is the one detail about it a reader is most likely to expect wrongly: the two sibling
 * repositories in this package key on whole numbers because their records do, and this one keys on
 * sixteen characters because L5 of the copybook declares characters.</p>
 *
 * <p>Alternatives Considered: leaving the inherited method as the only keyed read, so that this
 * interface declared nothing at all in the way both siblings declare nothing. Rejected on one
 * concrete ground rather than on style. On the two sibling records the key is a surrogate whose name
 * carries no obligation, whereas here the key IS a primary account number, and a call site reading
 * {@code findById(value)} gives a reviewer no indication that the value being passed is one and must
 * be handled as one. The named method below says which value is being looked up, and it is the
 * method the read path in {@code com.carddemo.account.service} already calls. Both remain available
 * and they resolve to the same statement, so the choice costs nothing at the store.</p>
 *
 * <h2>Ruling: the second access path is a real index, not decoration</h2>
 *
 * <p>Assumptions: the by-account reads below are backed by the non-unique secondary index
 * {@code idx_card_xref_account_id} over the account-identifier column, and what is assumed is that
 * the reference genuinely reached this record two ways rather than filtering a scan. Five
 * independent witnesses say so, and they are listed rather than summarised because the whole
 * by-account surface of this interface rests on them:</p>
 *
 * <ol>
 *   <li>{@code app/csd/CARDDEMO.CSD} defines a file resource named {@code CXACAIX} at L63 and
 *       describes it in words at L64 as the alternate index to {@code CCXREF} by account key.</li>
 *   <li>The data set names prove the relationship. That resource points at a name ending
 *       {@code .AIX.PATH} at L65, where the base cluster -- defined at L37, described at L38 as the
 *       card-to-account cross-reference -- points at a name ending {@code .KSDS} at L39. The same
 *       base name is reached two ways, distinguished only by those two suffixes. The resource name
 *       of the base cluster is {@code CCXREF}; the longer spelling appears only inside the data set
 *       names and never as a resource.</li>
 *   <li>A program declares the second path as a file literal.
 *       {@code app/cbl/COACTVWC.cbl} declares {@code 'CXACAIX '} across L192 and L193 and consumes
 *       it in the read at L727 through L732, entering that read on a record identification field
 *       holding an account identifier at L729 with its length at L730 and receiving the record at
 *       L731 with its length at L732.</li>
 *   <li>The same program says so in a comment at L725, naming the access as being by way of an
 *       alternate index on the account identifier.</li>
 *   <li>And the decisive one: the base cluster is keyed by CARD NUMBER and not by account.
 *       {@code app/cbl/CBACT03C.cbl} declares {@code RECORD KEY IS FD-XREF-CARD-NUM} at L32 beside
 *       {@code ACCESS MODE IS SEQUENTIAL} at L31, which is precisely why a separate index has to
 *       exist for the by-account read to be a path at all rather than a search.</li>
 * </ol>
 *
 * <p>Assumptions: the paragraph housing that read is {@code 9200-GETCARDXREF-BYACCT.} at L723 of
 * {@code app/cbl/COACTVWC.cbl}, reaching its exit at L771, and {@code app/cbl/COACTUPC.cbl} declares
 * the same paragraph at L3650. This package owns {@code idx_card_xref_account_id} and no other index
 * in that family: the resource at L13 of the same resource definition, and the account-keyed path
 * over the card master named by the literal at L190 and L191 of {@code app/cbl/COACTVWC.cbl}, belong
 * to the card context and are named nowhere in this interface.</p>
 *
 * <p>Assumptions: that index is NON-UNIQUE, and the non-uniqueness is load-bearing rather than
 * incidental. One account holds many cards and therefore many cross-reference rows, so a unique
 * index would refuse rows the reference seed data contains. Every consequence recorded below for the
 * by-account methods follows from it.</p>
 *
 * <h2>What the reference does on that path, and what this interface offers</h2>
 *
 * <p>Trade-offs: the by-account surface here is a documented superset of the reference's, and the
 * divergence is stated rather than absorbed. The reference does one thing: the read at L727 through
 * L732 of {@code app/cbl/COACTVWC.cbl} carries no generic-key option and is not a browse start, so
 * it is a single keyed read, and its result arm at L738 consumes exactly one row -- moving the
 * customer identifier at L739 and the card number at L740 -- with the not-found arm at L741. This
 * interface implements three things instead: a single-row finder that mirrors that read, an ordered
 * whole-set read for one account, and a bounded ordered read that resumes from a cursor. The reason
 * the superset exists is that the index is non-unique, so an account with several cards genuinely
 * has several rows to offer, and the reference's own resource definition grants
 * {@code BROWSE(YES) DELETE(YES) READ(YES) UPDATE(YES) JOURNAL(NO)} on that path at L70 -- browse
 * being a first-class operation on it, whether or not this particular program uses one. What is
 * accepted in exchange is that a reader comparing the two must consult this paragraph to see which
 * of the three methods corresponds to the read at L727, and the answer is recorded on that method
 * rather than left to be inferred.</p>
 *
 * <p>Assumptions: because the index is non-unique, the single-row finder needs a stated tie-break or
 * its result is whichever row the plan happened to reach first. It orders by card number ascending
 * and takes the first, so the same data yields the same row on every run. Without that the migrated
 * behaviour would vary between two executions over identical rows, which is an unstated behaviour
 * change rather than a carried-over one.</p>
 *
 * <h2>The ordered scan this interface's cursor pair replaces</h2>
 *
 * <p>Assumptions: the bounded ordered reads replace a whole-file sequential read and not a random
 * one, because {@code app/cbl/CBACT03C.cbl} contains no keyed random read anywhere in its
 * hundred-and-seventy-eight lines. It declares its file at L29, {@code ORGANIZATION IS INDEXED} at
 * L30, {@code ACCESS MODE IS SEQUENTIAL} at L31, its record key at L32 and its status field at L33,
 * then drives an open, get-next, close triad: {@code 0000-XREFFILE-OPEN.} at L118 issuing its open
 * at L120, {@code 1000-XREFFILE-GET-NEXT.} at L92 whose body at L93 reads the next record into the
 * copybook structure, and {@code 9000-XREFFILE-CLOSE.} at L136 issuing its close at L138. Its driver
 * loop runs from L74 to L81 and its two status conditions are declared at L62 and L63, the second of
 * which is the end-of-file condition that stops the loop. That same triad recurs in all three batch
 * programs of this context, which is why one bounded ordered read serves them all.</p>
 *
 * <p>Assumptions: no structured failure is raised from here on any of those paths. The reference
 * ends its own diagnostic route in {@code 9999-ABEND-PROGRAM.} at L154 of that program, which calls
 * the language-environment abend service at L158 after reporting through
 * {@code 9910-DISPLAY-IO-STATUS.} at L161 -- named exactly so in this program, where a sibling batch
 * program of the same shape uses different paragraph labels at the same physical lines. The migrated
 * counterpart of that abend payload lives in the shared kernel as
 * {@code com.carddemo.common.error.AbendDetail}, and it is deliberately neither imported nor
 * re-declared here: a repository raises nothing structured, it lets a store failure propagate to the
 * shared advice that renders it.</p>
 *
 * <h2>Position is a key, and never a count of rows</h2>
 *
 * <p>Alternatives Considered: positioning a bounded read by counting rows from the start of the
 * ordered set. Rejected on observable behaviour rather than on taste: when rows are inserted or
 * removed between two requests, the number of rows preceding the resume point changes underneath the
 * reader, so a read positioned that way omits rows it never returned and returns rows it already
 * returned. A read that resumes from the key of the last row it actually returned can do neither,
 * because that key names a row rather than a distance and is unaffected by an insertion elsewhere in
 * the table.</p>
 *
 * <p>Alternatives Considered: the reference already resumes by key, which is what makes the choice
 * above a one-to-one carry-over rather than an approximation of something looser.
 * {@code app/cbl/COCRDLIC.cbl} declares {@code 01 WS-THIS-PROGCOMMAREA.} at L229 and persists across
 * the terminal turn, at L230 through L244, a trailing key pair at L230 through L232, a leading key
 * pair at L233 through L235, a screen ordinal at L237 with its first-screen condition at L238, a
 * last-screen-displayed flag at L239 with its two conditions at L240 and L241, and a
 * further-rows-exist indicator at L242 with its two conditions at L243 and L244, alongside the row
 * counter at L145. Worth noting because it strengthens the citation rather than merely decorating
 * it: the key pair the reference carries is a card number followed by an account identifier, which
 * is exactly the pair of columns this table is accessed by. Nothing in that structure counts rows
 * already consumed, so nothing had to be invented here and nothing had to be discarded.</p>
 *
 * <h2>One row beyond the page, and who trims it</h2>
 *
 * <p>Assumptions: a caller establishes whether further rows follow by asking for one row more than
 * it intends to keep, which is the reference's own technique rather than an addition. That is why
 * every bounded method here takes its bound from the caller as {@code Limit.of(size + 1)} and never
 * bounds itself: the reference sets its further-rows indicator by discovering one record beyond
 * those a screen holds, the indicator declared at L242 through L244 of
 * {@code app/cbl/COCRDLIC.cbl}. The surplus row is an artifact of that bound, so the caller that
 * chose the bound is the one that must drop it, and its key must never be published as a boundary --
 * publishing it would advance a cursor one row too far and drop a row from the following read.</p>
 *
 * <p>Refactoring Rationale: the four browse verbs of the reference collapse into the one query pair
 * below -- ascending when reading forward, descending when reading backward -- and no handle is
 * opened or closed. What was wrong with the arrangement being replaced is not the verbs but where
 * the position lived: it lived in a structure the terminal echoed back, declared at L229 of
 * {@code app/cbl/COCRDLIC.cbl}, so continuity across a turn depended on that structure returning
 * intact. A cursor value carried in a request and verified on arrival ends that dependency, and
 * because there is no server-side file position to hold, there is nothing for a start verb to open
 * or an end verb to release.</p>
 *
 * <h2>Where the paging envelope is assembled, and why not here</h2>
 *
 * <p>Alternatives Considered: assembling the shared envelope
 * {@code com.carddemo.common.web.PageResponse} in default methods on this interface, so that the
 * surplus-row trim, the ordering normalisation and the boundary capture would have one owner.
 * Evaluated and rejected, and the reason is a hard constraint rather than a preference. That
 * envelope's canonical constructor requires each of its two cursor components to be a token sealed
 * by {@code com.carddemo.common.web.CursorToken} and refuses anything else: the two calls that
 * enforce it stand at
 * {@code services/common-lib/src/main/java/com/carddemo/common/web/PageResponse.java} L342 and L343,
 * the check they reach is declared at L357 through L364 of that file, and
 * {@code services/common-lib/src/test/java/com/carddemo/common/web/PageResponseTest.java} asserts
 * the refusal against a raw key at L263 through L271. Sealing needs key material and the subject the
 * token is issued for -- {@code seal} is an instance method at L235 of that token class, on a type
 * constructed with a signing key at L197 -- and an interface holds neither, so a default method here
 * could only ever offer a raw key, and every read that returned a row would be refused at
 * construction. Declaring such a method would ship one that cannot work.</p>
 *
 * <p>Assumptions: the envelope is therefore assembled one layer up, in
 * {@code com.carddemo.account.service}, which holds the sealer and the caller's identity and can
 * mint the boundary tokens this interface cannot. That is the division the package descriptor beside
 * this file already records, and the transport record for these rows records the same division from
 * its own side. Every method here consequently returns an entity or an ordered list of entities,
 * which is also the shape the equivalent browse repository in the card context returns for the same
 * reason. Reading rows here and sealing them there keeps this interface free of configuration
 * entirely: it needs no key, no lifetime and no request context in order to be exercised.</p>
 *
 * <p>Assumptions: the ordering normalisation belongs with that trim for the same reason. The
 * backward read below returns its rows in descending key order, because a bound has to apply to the
 * rows nearest the cursor and only the descending ordering selects those; the caller reverses them
 * into ascending order once it has dropped the surplus row. Reversing inside the query is not
 * available, and reversing before the trim would drop the wrong row, since on a descending result
 * the surplus row is the last one read and therefore the smallest key.</p>
 *
 * <h2>The cursor here needs no numeric rendering</h2>
 *
 * <p>Assumptions: the cursor parameters below are textual because the key column is, so no numeric
 * rendering or parsing step stands between a returned row and the next request -- unlike the two
 * sibling repositories in this package, whose keys are whole numbers. The settled form of that cursor
 * is the sixteen-character width declared at L5 of {@code app/cpy/CVACT03Y.cpy}, and it is compared
 * as characters rather than as a number because a leading zero is significant in it and because
 * sixteen significant digits exceed what a binary floating-point value represents exactly. The
 * account identifier that narrows a by-account read is a different thing entirely: it is a whole
 * number and a query predicate, never a cursor, and the two are kept apart in the descriptions
 * below so a reader does not conflate them.</p>
 *
 * <h2>Page size is the caller's, and no row count is named here</h2>
 *
 * <p>Trade-offs: no method here names how many rows a read returns. The bound arrives as a
 * parameter, and the reason is concrete rather than a matter of taste: this context has no browse
 * screen at all -- the two programs that read this record online, at L727 and L3650 of the two
 * programs cited above, each display a single record -- and the ordered read here stands in for the
 * batch triad of {@code app/cbl/CBACT03C.cbl}, which has no screen and therefore no row capacity to
 * inherit. The cursor-shape citations from {@code app/cbl/COCRDLIC.cbl} above are lineage for the
 * cursor and for nothing else; that program's own row capacity is not imported. What is accepted is
 * that this interface cannot be inspected to learn what a full read is; what is bought is that it
 * never carries a number it could contradict.</p>
 *
 * <h2>No version column, and no lock of any kind</h2>
 *
 * <p>Assumptions: this record has no update path, so the entity carries no optimistic-concurrency
 * version member and this interface carries no concurrency concern at all. The asymmetry against the
 * two siblings is deliberate: their version members exist for the account-update flow of
 * {@code app/cbl/COACTUPC.cbl}, and that four-thousand-two-hundred-and-thirty-six-line program
 * rewrites the account and customer masters without touching the cross-reference.
 * {@code app/cbl/CBACT03C.cbl} only reads it, as L31 and L32 and the read at L93 show. No explicit
 * locking hint of any kind appears below either, and that is a separate point from the missing
 * version: a lock held here would hold a row across a request boundary the reference never held one
 * across, which is a stronger claim than the source makes.</p>
 *
 * <h2>Isolation, as a cross-reference and not a fresh claim</h2>
 *
 * <p>Trade-offs: both cross-reference resources are defined to read without regard to uncommitted
 * change -- the operand appears at L40 for the base cluster and at L66 for the second access path of
 * {@code app/csd/CARDDEMO.CSD}, with the locking update model at L43 and L69 -- and the store this
 * interface reads through runs at a strictly stronger default. The compromise accepted is the
 * direction nobody minds: a read here may decline to see something the reference would have shown,
 * and never the reverse. This is a cross-reference rather than a ruling, because
 * {@code com.carddemo.account.config.DataSourceConfig} is the single owner of the connection's
 * settings, and it is also why no table name below is qualified and no schema name is restated: that
 * class pins the search path on every pooled connection, so naming one here would give one setting
 * two definitions that a later change could move apart.</p>
 *
 * <h2>No executable parity oracle exists for these paths</h2>
 *
 * <p>Assumptions: this is stated rather than glossed, because a reader may reasonably assume the
 * reference suite covers it. L83 through L85 of {@code tests/README.md} record that the online
 * programs cannot be run end to end without a runtime the runner does not have, so only their
 * extractable field-validation logic is unit-tested; and the business rules that suite asserts
 * verbatim, from L553 onward, govern the posting, interest and category-balance programs of other
 * contexts and name none of the programs cited here. So no golden-master comparison is claimed for
 * this interface, and its behaviour is established by tests written against the copybook and program
 * lines cited above. The graded condition-code rubric under {@code tests/**}, in which a soft code
 * still reads as success, belongs to that suite alone; the gate over this file is pass or fail.</p>
 *
 * <p>Every reference program named above is read and cited only. None is modified, and where the
 * migrated behaviour differs the divergence is recorded above rather than introduced silently.</p>
 *
 * <h2>Why this type carries no parameter, return or exception section</h2>
 *
 * <p>Assumptions: an interface declaration accepts no argument, yields no value and raises nothing,
 * so the parameter, return and exception elements the project Explainability rule enumerates at its
 * L18 through L21 have nothing to describe at this level; each member below carries its own. The
 * inapplicability is stated rather than left silent, because that rule lists a docstring omitting
 * parameters or return values among its forbidden patterns at L39, and a reader must be able to tell
 * a declared inapplicability from an oversight. Nor does any member below carry an exception
 * section: a row that does not exist is an empty result rather than a raised condition on every path
 * here, which is the "where applicable" qualification of L21, and a store failure propagates to the
 * shared advice untouched. The convention these blocks follow is
 * {@code docs/CODE_DOCUMENTATION_STANDARD.md}, and where it and the rule appear to differ the rule
 * governs first, the Checkstyle configuration second and the prose standard third.</p>
 *
 * <p>Assumptions: every member below carries a docstring even though several are one-line
 * declarations with no body to explain, because the obligation at L15 of that rule attaches to the
 * declaration rather than to the amount of code behind it, and its gate at L43 is conjunctive -- a
 * docstring alone does not discharge it and a rationale alone does not either. Two consequences are
 * worth naming so a later reader does not mistake them for excess. A method whose predicate is
 * derived from its own name still needs a paragraph saying WHY that access path exists, which the
 * name cannot say and which L28 asks for; and the exemption at L23 for an accessor carrying no logic
 * does not reach a query method, so no member here takes the single-line form. Mechanically the same
 * conclusion is reached from a different direction: {@code MissingJavadocMethod} is configured in
 * {@code config/checkstyle/checkstyle.xml} with its scope at the narrowest setting, its
 * property-accessor exemption switched off and its minimum-line-count property left unset, so no
 * short-method escape exists. A clean run of that gate is nevertheless not evidence of compliance
 * with the rule, only of compliance with what a parser can see.</p>
 */
public interface CardXrefRepository extends JpaRepository<CardXref, String> {

    /**
     * Returns the cross-reference row entered on one card number.
     *
     * <p>Assumptions: the argument is the whole key, so this resolves through the primary-key
     * constraint and reads at most one row. What makes that safe to assert is the pair of
     * independent statements recorded on the interface above: the record key named at L32 of
     * {@code app/cbl/CBACT03C.cbl}, and the sixteen-character leading field declared at L5 of
     * {@code app/cpy/CVACT03Y.cpy} and restated at L39 of that program.</p>
     *
     * <p>Assumptions: a card that is not cross-referenced yields an empty result rather than a
     * raised condition, which is the shape the reference's own not-found arm has -- at L741 of
     * {@code app/cbl/COACTVWC.cbl} it sets an input-error flag and composes a screen message rather
     * than abending. Choosing which sentence a caller eventually reads is the business of
     * {@code com.carddemo.account.service}, so no message text is reproduced here.</p>
     *
     * @param cardNum the primary account number to resolve, of the declared width the copybook
     *     states at L5; must not be {@code null}
     * @return the row for that card, or an empty result when the card is not cross-referenced; never
     *     {@code null}
     */
    Optional<CardXref> findByCardNum(String cardNum);

    /**
     * Returns one cross-reference row for an account, taking the lowest card number when the account
     * holds several.
     *
     * <p>Assumptions: this exists because the base cluster cannot answer the question. Its record key
     * is the card number, declared at L32 of {@code app/cbl/CBACT03C.cbl} beside the sequential
     * access mode at L31, so an account identifier is not a key of it at all; the reference reaches
     * this record by account only through the second access path, defined at L63 of
     * {@code app/csd/CARDDEMO.CSD} and described at L64 as the alternate index by account key. The
     * method name states which column is being matched; this paragraph states why matching that
     * column is possible, which the name cannot.</p>
     *
     * <p>Assumptions: this is the migrated counterpart of the single keyed read at L727 through L732
     * of {@code app/cbl/COACTVWC.cbl} -- one read, carrying no generic-key option and no browse
     * start, whose result arm at L738 consumes exactly one row at L739 and L740. The return shape is
     * therefore a single optional row rather than a list, so a call site cannot silently begin
     * treating a one-row answer as a set.</p>
     *
     * <p>Assumptions: the tie-break is the ordering, and it is required rather than cosmetic. The
     * index behind this read is non-unique because one account holds many cards, so without a stated
     * ordering the row returned would be whichever the plan reached first and could differ between
     * two executions over identical rows. Ordering by card number ascending and taking the first row
     * makes the answer reproducible, and it is the same ordering the whole-set read below uses, so
     * the two never disagree about which row is first.</p>
     *
     * <p>Trade-offs: this narrows a read the context already performs. The customer-resolution path
     * in {@code com.carddemo.account.service} reaches the same row today by reading every row for the
     * account and discarding all but the first, which is safe -- every row for one account names the
     * same customer, because the record carries the account and the customer together -- but it
     * materialises an account's whole card set to use one row of it. This method lets the store
     * return one row instead. What is accepted is a second by-account method on the interface; the
     * whole-set read below is retained unchanged because callers that genuinely need every row exist,
     * and collapsing the two would force one of them into the wrong shape.</p>
     *
     * @param accountId the account whose cross-reference row is wanted, the eleven-digit identifier
     *     the copybook declares at L7; must not be {@code null}
     * @return the row with the lowest card number for that account, or an empty result when the
     *     account has no cards; never {@code null}
     */
    Optional<CardXref> findFirstByAccountIdOrderByCardNumAsc(Long accountId);

    /**
     * Reads one account's screen composition -- cross-reference, account and customer -- in one statement.
     *
     * <p>Purpose: this is the whole of {@code 9000-READ-ACCT} at L687 of {@code app/cbl/COACTVWC.cbl} as a
     * single statement. That paragraph drives three keyed reads in a fixed order, each gated on the one
     * before, and the ordering is load bearing because only the cross-reference yields the customer key the
     * third read needs.</p>
     *
     * <p>Refactoring Rationale: the three reads were three statements. One statement replaces them because
     * this datasource runs at read-committed isolation, where each statement takes its own snapshot, so
     * three statements could compose an account from before a concurrent update with a customer from after
     * it -- a pairing that never existed. The rationale on the composing method asserted the opposite, and
     * this query is what makes that assertion true.</p>
     *
     * <p>Trade-offs: the gates no longer PREVENT a read. In the reference a miss on the first read meant the
     * second was never issued, and here all three sides are evaluated together. Nothing observable changes,
     * because a read has no side effect and the outcome is still decided in the reference's order from which
     * sides came back empty; what is given up is the ability to say that a missing account cost one read
     * rather than one join.</p>
     *
     * <p>Assumptions: both joins are OUTER, which is what preserves the three distinct reference outcomes.
     * An inner join would collapse the account-master miss and the customer-master miss into a single empty
     * result, and each of those arms carries its own verbatim sentence and its own rule about which half of
     * the screen is published.</p>
     *
     * <p>Assumptions: the joins are composed with an explicit predicate rather than by navigating an
     * association, because the three entities declare none and the schema declares no foreign key between
     * their tables. Declaring an association to shorten this query would assert a referential guarantee the
     * database does not enforce.</p>
     *
     * <p>Assumptions: the ordering and the caller's limit together settle WHICH cross-reference row answers
     * when an account holds several. Ascending card number is the base cluster's own order, which
     * {@code app/cbl/CBACT03C.cbl} states with {@code ACCESS MODE IS SEQUENTIAL} at L31 beside
     * {@code RECORD KEY IS FD-XREF-CARD-NUM} at L32, and it is the same tie-break the keyed by-account read
     * on this interface applies -- so both routes resolve to the same row.</p>
     *
     * @param accountId the account whose screen composition is wanted, the eleven-digit identifier the
     *     copybook declares at L7; must not be {@code null}
     * @param limit the greatest number of rows to return, which a caller wanting the reference's
     *     single-record shape sets to one
     * @return the composition rows in ascending card-number order, empty when the account has no
     *     cross-reference row at all; never {@code null}
     */
    @Query("""
            select new com.carddemo.account.repository.AccountScreenRow(x, a, c)
            from CardXref x
              left join Account a on a.accountId = x.accountId
              left join Customer c on c.customerId = x.customerId
            where x.accountId = :accountId
            order by x.cardNum asc
            """)
    List<AccountScreenRow> findAccountScreenRows(
            @Param("accountId") Long accountId,
            Limit limit);

    /**
     * Reads forward from a cursor in ascending card-number order, optionally narrowed to one account.
     *
     * <p>Refactoring Rationale: this method and its backward counterpart below are together the
     * migrated form of the reference's four browse verbs and of the batch triad that reads the same
     * file end to end. What was replaced is not the verbs but the place the position lived. In the
     * batch program it lived in an open file handle -- {@code 0000-XREFFILE-OPEN.} at L118 of
     * {@code app/cbl/CBACT03C.cbl} opening at L120, {@code 1000-XREFFILE-GET-NEXT.} at L92 advancing
     * at L93, {@code 9000-XREFFILE-CLOSE.} at L136 releasing at L138 -- and online it lived in a
     * structure the terminal echoed back, declared at L229 of {@code app/cbl/COCRDLIC.cbl}. A cursor
     * value carried in the request needs neither: there is no handle for a start verb to open, none
     * for an end verb to release, and no echoed structure whose loss breaks continuity.</p>
     *
     * <p>Assumptions: the ordering is ascending and the comparison against the cursor is STRICT, so a
     * read resumes after the last row the caller actually received. The reference positions inclusively
     * on the first row it has not yet shown and then reads, which produces the same boundaries: with a
     * given number of rows to a read, resuming strictly after the key of the last returned row yields
     * the same next set. The consequence a caller must honour is the one recorded on the interface
     * above -- the surplus row's key is never published as a boundary, because publishing it would
     * advance the cursor one row too far and drop a row from the following read.</p>
     *
     * <p>Assumptions: an absent cursor means the first read of the set, which is why the parameter is
     * nullable here and mandatory on the backward counterpart. There is no corresponding backward
     * state: a backward step is only expressible from a set that was already returned, so an absent
     * cursor there would describe a request that cannot arise.</p>
     *
     * <p>Alternatives Considered: naming both predicates in a derived method name instead of writing
     * one statement, which is how the two by-account methods above are expressed. Not available here.
     * A predicate derived from a method name is always applied, and no derived form means restrict on
     * this argument only when the caller supplied one, so each predicate would have to be either always
     * on or always off -- four methods per direction for the four combinations, every one of them
     * restating the cursor predicate. That predicate is the single part a divergence in would silently
     * move a boundary, and one statement per direction states it once.</p>
     *
     * <p>Trade-offs: the statement is written in the persistence query language rather than in the
     * store's own dialect. Native text would let the index be named outright, and is declined: it would
     * pin this read to one product's syntax where the query language resolves the same predicate
     * through whichever plan the store chooses, and it would restate the column names the entity
     * already maps -- giving the mapping a second definition that a later column rename would move out
     * of step with silently. The two arguments are matched against mapped property names here, so a
     * renamed member fails at startup rather than at the first statement that touches it.</p>
     *
     * @param afterCardNum the card number the previous read ended on, exclusive, or {@code null} to
     *     read from the start of the set
     * @param accountId the account to narrow the read to, resolving through
     *     {@code idx_card_xref_account_id}, or {@code null} to read the whole set unnarrowed
     * @param limit the bound on how many rows come back, which a caller supplies as one more than it
     *     intends to keep so that the surplus row answers whether further rows follow; must not be
     *     {@code null}
     * @return the matching rows in ascending card-number order, at most as many as the bound allows,
     *     empty when the cursor is already past the last matching row; never {@code null}
     */
    // WHY : Assumptions: each argument is tested for absence in the same statement that compares it,
    //       so its type is inferable from that comparison and no cast is written. The account
    //       predicate is expressed as an equality against the mapped identifier rather than as a join
    //       to the account master, because this row's whole purpose is to be read WITHOUT loading
    //       either record it points at, and because the entities of this context declare no
    //       association to navigate and the schema declares no foreign key between their tables.
    @Query("""
            select x from CardXref x
            where (:afterCardNum is null or x.cardNum > :afterCardNum)
              and (:accountId is null or x.accountId = :accountId)
            order by x.cardNum asc
            """)
    List<CardXref> findForwardFromCursor(
            @Param("afterCardNum") String afterCardNum,
            @Param("accountId") Long accountId,
            Limit limit);

    /**
     * Reads backward from a cursor in descending card-number order, optionally narrowed to one account.
     *
     * <p>Refactoring Rationale: this is the counterpart of the reference's read-previous verb, and the
     * descending ordering is what makes it that rather than a fresh read from the start of the set. The
     * position it seeks from is the LEADING boundary of the set the caller already holds, which is the
     * key pair the reference keeps for exactly that purpose at L233 through L235 of
     * {@code app/cbl/COCRDLIC.cbl} beside the trailing pair at L230 through L232. What is replaced is
     * the place that position lived, not the verb: in the batch program it lived in an open file handle
     * -- opened at L118 of {@code app/cbl/CBACT03C.cbl} and released at L136 -- so walking a set
     * backwards meant holding one. A cursor carried in the request holds nothing, which is why the pair
     * of statements on this interface covers all four browse verbs between them with none to open or
     * release.</p>
     *
     * <p>Trade-offs: the rows come back in the order the statement read them, descending, and the
     * caller reverses them before presenting a set. Reversing inside the statement is not available:
     * the bound has to apply to the rows NEAREST the cursor, and only the descending ordering selects
     * those, so an ascending ordering here would bound the wrong end of the table. The reference makes
     * the same accommodation for the same reason -- it fills its display positions from the last one
     * upward, so the row it read first lands in the position shown last. What is accepted is that this
     * one method's result is not in presentation order; what is bought is that the rows adjacent to the
     * cursor are the rows returned.</p>
     *
     * <p>Assumptions: the surplus row is the LAST row of this descending result and therefore the
     * smallest key, so a caller must drop it before reversing rather than after. That surplus row is
     * the one the caller asked for beyond its own bound, the technique recorded on the interface above
     * and taken from the further-rows indicator at L242 through L244 of {@code app/cbl/COCRDLIC.cbl}.
     * Reversing first would drop the row nearest the cursor and leave a hole at the boundary -- a set
     * that looks right and is quietly missing a row, which is exactly the class of defect the single
     * shared envelope in the kernel exists to prevent.</p>
     *
     * <p>Assumptions: the cursor is mandatory here and the comparison against it is strict, for the
     * reasons recorded on the forward read above. The column it is compared against is the record key
     * named at L32 of {@code app/cbl/CBACT03C.cbl}, whose sixteen-character width the copybook declares
     * at L5 of {@code app/cpy/CVACT03Y.cpy}, so this ordering walks the same sequence the reference
     * walks. A backward step from an unread set is not a request this interface admits.</p>
     *
     * @param beforeCardNum the card number the current read begins on, exclusive, which is the leading
     *     boundary of the set the caller already holds; must not be {@code null}
     * @param accountId the account to narrow the read to, resolving through
     *     {@code idx_card_xref_account_id}, or {@code null} to read the whole set unnarrowed
     * @param limit the bound on how many rows come back, which a caller supplies as one more than it
     *     intends to keep so that the surplus row answers whether further rows precede the set; must
     *     not be {@code null}
     * @return the matching rows in DESCENDING card-number order, at most as many as the bound allows,
     *     empty when the cursor is already at the first matching row; never {@code null}
     */
    // WHY : Assumptions: the cursor predicate is unconditional here whereas the forward statement
    //       guards its own with an absence test, and the asymmetry is the one recorded above rather
    //       than an inconsistency between the two statements. The account predicate stays guarded in
    //       both, because narrowing is optional in both directions.
    @Query("""
            select x from CardXref x
            where x.cardNum < :beforeCardNum
              and (:accountId is null or x.accountId = :accountId)
            order by x.cardNum desc
            """)
    List<CardXref> findBackwardFromCursor(
            @Param("beforeCardNum") String beforeCardNum,
            @Param("accountId") Long accountId,
            Limit limit);
}
