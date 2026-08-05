package com.carddemo.authorization.dto;

import com.carddemo.common.money.Money;

/**
 * Projects the pending-authorization summary screen onto one flat response payload of 62 components, one
 * for each data field that screen's symbolic map declares.
 *
 * <p><strong>Purpose.</strong> This record is the outbound half of the pending-authorization summary
 * endpoint. It carries what one screenful of that screen carries and nothing besides: six header positions,
 * fifteen account-and-customer context positions, five rows of eight positions each, and one message line.
 * It reaches no datastore, holds no business rule and decides no authorization; its values arrive from
 * {@code com.carddemo.authorization.mapper}, which the charter at {@code com.carddemo.authorization.dto}
 * names as the one place representation concerns may appear.
 *
 * <h2>Where the 62 components come from</h2>
 *
 * <p>Assumptions: the component count, the declaration order and every width quoted below were derived from
 * {@code app/app-authorization-ims-db2-mq/cpy-bms/COPAU00.cpy} as read, and from no secondary description
 * of it. Two independent derivations agree on 62. Counting the data field declarations of the
 * {@code COPAU0AI} structure yields 62; and that structure opens at L17 with a twelve-position filler at
 * L18 and then advances by exactly six lines per logical field, so the last of them, {@code ERRMSGI} at
 * L390, is field number (390 - 18) / 6 = 62. The six-line stride is the map's own geometry: every field
 * contributes a length halfword, a flag position, an attribute redefinition of that position, a
 * four-position filler, and then the data position this record projects. The pattern used to count them
 * tolerates runs of whitespace, which is load-bearing rather than fastidious: these maps separate a level
 * number from its picture clause with two spaces or more, so a single-space pattern matches none of the 62
 * and reports absence instead of failing.
 *
 * <p>Assumptions: one record covers both halves of the map rather than two.
 * {@code 01 COPAU0AO REDEFINES COPAU0AI.} at L391 lays the output half over the same storage as the input
 * half, so the two are one shape and not two, and {@code ERRMSGO PIC X(78)} at L764 is the same 78
 * positions as {@code ERRMSGI} at L390. The program corroborates it by writing outbound data into the input
 * names -- {@code MOVE PA-TRANSACTION-ID TO TRNID01I OF COPAU0AI} at {@code cbl/COPAUS0C.cbl} L547, and the
 * whole first-row block at L547 to L554 with it -- while the header population writes the output names at
 * L731 to L746. Only the redefinition makes both coherent, and a reader who took the two halves for two
 * shapes would author this payload twice.
 *
 * <p>Assumptions: the declaration order below is the map's order, and it is not tidy. The six header
 * components arrive as transaction name, first title, date, program name, second title, time, so the two
 * titles are not adjacent and neither are the date and the time. In the context group the account status
 * sits between the two address lines, and the four limit-and-balance components alternate rather than
 * grouping the limits together. Within the list, rows one to four lead with their selection marker while
 * row five ends with it: {@code SEL0005I} is declared at L384, after {@code PAMT005I} at L378, and the raw
 * six-line groups spanning L373 to L384 confirm it. All of that is screen-position ordering, which is what
 * a 3270 map records. Re-ordering any of it would leave the count right and the contract wrong, and no
 * assertion on the count alone detects that.
 *
 * <p>Assumptions: this payload is outbound, and two of its component groups are read inbound by the
 * baseline as well. Within the fifteen-component context group {@code ACCTIDI} is the only user-input
 * field: it is read at {@code cbl/COPAUS0C.cbl} L264 for blank or low values and at L273 for a non-numeric
 * value, then moved at L283. The other fourteen are output only. The five row selection markers are also
 * inbound, read at L286, L290, L294, L298 and L302 and moved into the selection flag at L287, L291, L295,
 * L299 and L303, with their attribute positions left protected at L614, L623, L632, L641 and L650 until a
 * row is populated at L554, L566, L578, L590 and L602. The precise statement is therefore that the account
 * identifier is the only input in the context group, not that it is the only input on the screen.
 *
 * <h2>Widths, values and types</h2>
 *
 * <p>Assumptions: the message component is bounded at 78 positions, and that is the only width this record
 * enforces. Four declarations agree on it: {@code ERRMSGI PIC X(78)} at {@code cpy-bms/COPAU00.cpy} L390
 * and {@code ERRMSGO PIC X(78)} at L764 for this screen, and {@code ERRMSGI PIC X(78)} at
 * {@code cpy-bms/COPAU01.cpy} L180 with {@code ERRMSGO PIC X(78)} at L344 for the detail screen beside it.
 * The feeder is wider than the field: {@code WS-MESSAGE PIC X(80)} at {@code cbl/COPAUS0C.cbl} L37 is moved
 * into the 78-position field at L692, so the baseline drops the final two positions of a full-width message
 * and the target carries the 78 the field declares. The house 75-position contract that
 * {@code CCARD-ERROR-MSG} and {@code CCARD-RETURN-MSG} declare at {@code app/cpy/CVCRD01Y.cpy} L28 and L29
 * lives in a different copybook, one this module never includes, and it does not apply here: four
 * message-width regimes exist across the reference tree and these two screens are the 78-position outlier.
 * Sizing this component at 75 would refuse the last three positions of a message the baseline renders
 * whole.
 *
 * <p>Assumptions: a copybook width is normative and a map width is presentation, so no component below is
 * checked against the number its own map field declares. The strongest evidence is one datum declared at
 * two widths on two screens: the transaction identifier is {@code TRNID0nI PIC X(16)} here, at
 * {@code cpy-bms/COPAU00.cpy} L156 through L342, and {@code TRNIDI PIC X(15)} on the detail screen at
 * {@code cpy-bms/COPAU01.cpy} L132, while the datum itself is {@code PA-TRANSACTION-ID PIC X(15)} at
 * {@code cpy/CIPAUDTY.cpy} L44. Two rendered amounts on this screen make the point from the other
 * direction: {@code APPRAMTI} at L126 and {@code DECLAMTI} at L144 each declare ten positions and each
 * receives the nine-position edit mask {@code WS-DISPLAY-AMT9}, at {@code cbl/COPAUS0C.cbl} L797 and L799.
 * The consequence is specific rather than tidy-minded: a validator sized from the map would accept a
 * sixteenth character the stored field cannot hold, so this record would publish a contract the datastore
 * refuses. This is transformation rule T1 of the migration plan, which makes the copybook normative. That
 * plan numbers its transformation rules T1 to T10, and those identifiers are a different namespace from the
 * user-specified rules -- T1 here is not Rule 1 -- so a citation that blurs the two sends a reader to the
 * wrong document.
 *
 * <p>Assumptions: {@code accountStatus} is declared by the map and left unset by the baseline. A search of
 * {@code cbl/COPAUS0C.cbl} for {@code ACCSTAT} returns zero references of any kind, and
 * {@code PA-ACCOUNT-STATUS PIC X(02) OCCURS 5 TIMES} at {@code cpy/CIPAUSMY.cpy} L22 has zero references in
 * that program either, so nothing in the summary flow moves a value into the field or reads one from it.
 * The component is kept because the map declares it and because the 62 both derivations agree on includes
 * it; dropping it would make this record 61 components and no longer the shape the map describes. The
 * baseline leaves the position unset and the target carries it as an absent value. No source is invented
 * for it: this record does not guess that the two-position five-slot segment array at L22 is what the
 * one-position map field was meant to show, because nothing in the program connects them.
 *
 * <p>Assumptions: three context components are composed rather than copied, so no single stored datum
 * stands behind them and each is carried at the map width for want of a normative one. {@code CNAMEI} is
 * assembled by {@code STRING} from the first name, one middle initial and the last name into {@code CNAMEO}
 * at {@code cbl/COPAUS0C.cbl} L758 to L763; {@code ADDR001I} from address lines one and two at L766 to
 * L769; {@code ADDR002I} from address line three, the state code and the first five ZIP positions at L771
 * to L776. For those three the declared 25 positions are a composition budget, and a longer input is
 * truncated by the move rather than refused. Their two neighbours are ordinary copies and are documented as
 * such: {@code CUSTIDI} from {@code CUST-ID} at L757 and {@code PHONE1I} from {@code CUST-PHONE-NUM-1} at
 * L779. The distinction carries weight because a check can be sized from a copied field's source and cannot
 * be sized from a composition.
 *
 * <p>Assumptions: two monetary scales coexist in this bounded context and this record carries both without
 * flattening them. The context amounts are {@code S9(09)V99 COMP-3} at {@code cpy/CIPAUSMY.cpy} L23 to L26
 * and L29 to L30, eleven digits of which two are decimal; the row amounts are {@code S9(10)V99 COMP-3} at
 * {@code cpy/CIPAUDTY.cpy} L34 and L35, twelve digits of which two are decimal.
 * {@code com.carddemo.common.money.Money} fixes the scale at two, which is the property both share, and the
 * differing integer widths are a schema concern the migration's data model carries rather than a payload
 * concern. Separately, the two counts are stored wider than they are shown: {@code PA-APPROVED-AUTH-CNT}
 * and {@code PA-DECLINED-AUTH-CNT} are {@code S9(04) COMP} at {@code cpy/CIPAUSMY.cpy} L27 and L28, a
 * binary halfword, and they reach the screen through {@code WS-DISPLAY-COUNT PIC 9(03)} at
 * {@code cbl/COPAUS0C.cbl} L58 by the moves at L788 to L791, so a count of a thousand or more shows without
 * its leading digit. These components carry the stored value rather than that render. The baseline renders
 * three positions, the target carries the halfword, the divergence is documented here, and a client that
 * wants three positions formats them at its own edge.
 *
 * <p>Assumptions: the eleven monetary components are {@code com.carddemo.common.money.Money} and never a
 * primitive or boxed floating-point type. A decimal cent has no exact binary floating-point value, so a
 * payload carrying money as a JSON number invites a client to parse it into a double and lose exactness at
 * the boundary a user actually reads; {@code com.carddemo.common.money.MoneyModule} fixes the wire form as
 * a string and is registered once for the service rather than once per payload. The two count components
 * are {@code Integer} because a binary halfword is not money, so a monetary type would assert a scale the
 * datum does not have. Every remaining component is a {@code String}, on the overlay discipline the
 * migration plan records in its section 0.7.2: the baseline declares its identifiers and codes as
 * characters and reaches the same bytes numerically only for arithmetic, so a string is the representation
 * that survives the move, and a numeric component would discard a leading zero the declared width
 * preserves.
 *
 * <p>Assumptions: this record imports exactly one type, {@code com.carddemo.common.money.Money}, and
 * re-declares nothing the shared kernel owns. That is transformation rule T2 of the migration plan, under
 * which one former copybook inclusion becomes one import from the single package that owns the contract. It
 * carries no persistence annotation and no import from {@code com.carddemo.authorization.domain}, which is
 * the boundary the charter at {@code com.carddemo.authorization.dto} asserts and the reason a value cannot
 * reach the wire without passing through the mapper that masks and suppresses.
 *
 * <p>Assumptions: an unpopulated row carries absent values rather than a placeholder, which is why the two
 * closed-domain checks below admit an absent value. The baseline blanks all seven data fields of an unused
 * row and leaves its selection attribute protected -- {@code INITIALIZE-AUTH-DATA} from
 * {@code cbl/COPAUS0C.cbl} L608, with the blanking for the first row at L615 to L621 and its protected
 * attribute at L614 -- and a page can hold fewer than five rows because the loop at L424 stops at end of
 * data. A check that demanded one of the four match-status values on every row would therefore refuse the
 * baseline's own short final page.
 *
 * <h2>What this record deliberately does not carry</h2>
 *
 * <p>Trade-offs: there is no paging component and no row-addressing key, and both absences are decisions
 * with a stated cost. Paging in the baseline is carried entirely in the communication area and not at all
 * in the symbolic map: {@code CDEMO-CPVS-INFO} is declared at {@code cbl/COPAUS0C.cbl} L117 to L126,
 * immediately after {@code COPY COCOM01Y.} at L116, so it extends the shared session structure the
 * migration plan retires in its section 0.7.1, and {@code COPY COPAU00.} follows only at L129. Its members
 * map onto {@code com.carddemo.common.web.PageResponse} member for member, which is why that envelope is
 * imported where a page of these rows is returned and why none of its members is restated here:
 * <ul>
 *   <li>{@code lastKey} is {@code CDEMO-CPVS-PAUKEY-LAST}, declared at L121 and set by
 *       {@code MOVE PA-AUTHORIZATION-KEY TO CDEMO-CPVS-PAUKEY-LAST} at L434 to L435.
 *   <li>{@code firstKey} and {@code prevCursor} are the {@code CDEMO-CPVS-PAUKEY-PREV-PG} stack, declared
 *       at L120, pushed at L439 to L440 and popped by the backward path at L365 to L368.
 *   <li>{@code hasNext} is {@code CDEMO-CPVS-NEXT-PAGE-FLG}, declared at L123 with its two condition names
 *       at L124 and L125, set at L374 and tested at L404; the baseline establishes it by reading one row
 *       beyond the five displayed at L445 to L452, which is the same probe the envelope documents.
 *   <li>{@code hasPrev} is the backward guard {@code IF CDEMO-CPVS-PAGE-NUM > 1} at L365, carried as a
 *       stated value rather than inferred from the presence of a first row.
 *   <li>The nullable cursor tokens are the baseline's sentinel discipline:
 *       {@code IF CDEMO-CPVS-PAUKEY-LAST = SPACES OR LOW-VALUES} at L391 and {@code MOVE LOW-VALUES} at
 *       L422.
 *   <li>Nothing at all corresponds to {@code CDEMO-CPVS-PAGE-NUM} at L122, deliberately.
 * </ul>
 *
 * <p>Trade-offs: that last entry is the one member which must not cross over, and the reason is concrete
 * rather than stylistic. Every reference to {@code CDEMO-CPVS-PAGE-NUM} is either an index into the
 * twenty-slot remembered-key table declared at L120, at L368 and L440, or the arithmetic maintaining that
 * index, at L347, L366, L437 and L438, or the guard at L365 that reads it as backward availability. Because
 * the table holds twenty entries, the baseline pages backward twenty pages and no further. An opaque cursor
 * needs no such table and therefore carries no such bound, so a page number would import the bound while
 * importing nothing that uses it.
 *
 * <p>Trade-offs: the row-addressing key is absent because it is not on the screen either.
 * {@code MOVE PA-AUTHORIZATION-KEY TO CDEMO-CPVS-AUTH-KEYS(n)} at L545, L557, L569, L581 and L593 stores
 * one eight-position key per displayed row in the communication area, and a mark on a row moves that stored
 * key into {@code CDEMO-CPVS-PAU-SELECTED} across L288 to L305. That key is
 * {@code PA-AUTH-DATE-9C PIC S9(05) COMP-3} at {@code cpy/CIPAUDTY.cpy} L20, three bytes, followed by
 * {@code PA-AUTH-TIME-9C PIC S9(09) COMP-3} at L21, five bytes, which is exactly the eight positions the
 * slot declares. It is a different item from the displayed {@code PDATE0nI} and {@code PTIME0nI}, which
 * render {@code PA-AUTH-ORIG-DATE} and {@code PA-AUTH-ORIG-TIME}, both {@code PIC X(06)} at L22 and L23, so
 * neither can be reconstructed from the other and the two are not interchangeable. No sixty-third component
 * is added for it. The cost is real and is stated rather than left implicit: resolving a marked row to the
 * address of its detail view is work for the api and service packages, which answer it through the path
 * parameter of the detail endpoint, and this record does not carry the value that resolution consumes.
 *
 * <p>Alternatives Considered: Lombok, for the accessors. Rejected because generated accessors cannot carry
 * the Javadoc this file is obliged to write, and a Java 21 record with 62 declared components gives the
 * same brevity while leaving every member documentable.
 *
 * <p>Alternatives Considered: MapStruct, for the projection that fills this record. Rejected because the
 * projection is not mechanical. It drops {@code FILLER} -- 34 positions of it at {@code cpy/CIPAUSMY.cpy}
 * L31 and 17 at {@code cpy/CIPAUDTY.cpy} L54 -- it masks a primary account number to its last four digits,
 * it suppresses a card verification value outright, it composes three context components from several
 * sources at once, and it derives two of every row's eight from other fields. Each of those needs a
 * justification written at the mapping site, and a generated mapper has nowhere to put one.
 *
 * <p>Alternatives Considered: five nested eight-component row records instead of 40 flat components. That
 * was genuinely available and groups the list more legibly. Rejected because it would make the top level 27
 * components, which is neither the shape the map describes nor the count both derivations agree on: the
 * arity would change silently while every count written in this file still said 62. The row grouping is
 * carried by the component names instead, each prefixed with its row index, so the grouping is legible
 * without altering the arity.
 *
 * <p>Alternatives Considered: declaring the two enforced constraints as bean-validation annotations on the
 * components, which is what the sibling request record {@code com.carddemo.reporting.dto.StatementRequest}
 * does and which lets a refusal name the field it concerns. Rejected here on a difference of direction
 * rather than a preference. That record is bound from an inbound request body, where a validator runs as
 * part of binding and a raised exception would reach the client as an unreadable body instead of a
 * per-field entry. This record is outbound and is assembled in process, so nothing on the serialisation
 * path asks a validator to evaluate an annotation: a constraint written here would read as enforcement
 * while enforcing nothing, and the value it appeared to guard would reach the client unchecked. A malformed
 * value here is also a defect in this service rather than something a caller sent, so there is no
 * caller-supplied field for a per-field entry to name. The canonical constructor therefore refuses, and it
 * refuses at construction so that no instance can exist in a state this file documents as impossible.
 *
 * <p>Alternatives Considered: overriding {@code toString} to withhold component values from a log, as that
 * same sibling record does for its card number. Rejected because the two values the migration masks or
 * suppresses are not components of this record at all: no primary account number is declared anywhere on
 * this map, the card number that exists in this context is {@code PA-CARD-NUM PIC X(16)} at
 * {@code cpy/CIPAUDTY.cpy} L24, a segment field the summary screen never renders, and no card verification
 * value exists in either segment. The components that are personal -- the composed name, the two composed
 * address lines and the telephone number -- are shown whole on the screen this record projects, at
 * {@code cbl/COPAUS0C.cbl} L763, L769, L776 and L779, so masking them here would put the payload at odds
 * with the screen while withholding nothing the screen withholds.
 *
 * <h2>Documentation contract</h2>
 *
 * <p><b>Parameters, return values, exceptions or errors at the type level.</b> The 62 record components are
 * this type's parameters and each carries its own at-clause below. A type declaration returns no value and
 * raises nothing, so no return or exception at-clause appears on this block; the canonical constructor
 * declared in the body carries its own, including the exception it raises. The inapplicability is stated
 * rather than left silent, because a docstring that omits parameters or return values is among the
 * forbidden patterns the user-specified Explainability rule lists at L39, and a reader has to be able to
 * tell a declared inapplicability from an oversight.
 *
 * <p>Assumptions: this block exists because that rule requires a docstring on every new class at L15 and
 * requires the language's own format at L22, which for Java names Javadoc; the 62 at-clauses exist because
 * L19 requires a name, a type and a description for each parameter and L22 makes the Javadoc tag the form
 * that requirement takes. The rule states the consequence of an omission in its validation gate at L43. The
 * migration plan separately mechanises the presence half of that gate by binding a documentation check to
 * the Maven {@code validate} phase, which runs before compilation, so one missing at-clause on 62
 * components both breaches the rule and stops the build. The two authorities are independent, and the
 * mechanised check being silent about something is not permission under the rule.
 *
 * <p>Assumptions: the test channel for this context is not authored yet, so the assertions it owes this
 * record are stated here rather than deferred to it. They are: that the component count is exactly 62; that
 * the declaration order places {@code row5Selection} last within row five, after
 * {@code row5ApprovedAmount}; that the message component accepts 78 positions and refuses 79; that every
 * monetary component serialises as a JSON string, which the shared kernel's money module fixes rather than
 * this record; that a row match status accepts only {@code 'P'}, {@code 'D'}, {@code 'E'} and {@code 'M'};
 * that a row approval status accepts only {@code 'A'} and {@code 'D'}; and that no serialised property name
 * contains {@code page}, {@code offset}, {@code skip}, {@code total} or {@code size}. The last of those is
 * why every row component is named for its row index rather than for a position within a page.
 *
 * @param transactionName the transaction identifier this screen runs under, map field {@code TRNNAMEI}
 *     declared at {@code cpy-bms/COPAU00.cpy} L24; fed from {@code WS-CICS-TRANID}, whose declared value is
 *     {@code 'CPVS'} at {@code cbl/COPAUS0C.cbl} L36, by the move at L733
 * @param title01 the first title-band line, map field {@code TITLE01I} declared at
 *     {@code cpy-bms/COPAU00.cpy} L30; carried position for position from {@code CCDA-TITLE01 PIC X(40)} at
 *     {@code app/cpy/COTTL01Y.cpy} L18 by the move at {@code cbl/COPAUS0C.cbl} L731
 * @param currentDate the screen date as eight rendered positions in month, day and two-digit-year order,
 *     map field {@code CURDATEI} declared at {@code cpy-bms/COPAU00.cpy} L36; assembled from the
 *     current-date function at {@code cbl/COPAUS0C.cbl} L736 to L740
 * @param programName the originating program name, map field {@code PGMNAMEI} declared at
 *     {@code cpy-bms/COPAU00.cpy} L42; fed from {@code WS-PGM-AUTH-SMRY}, whose declared value is
 *     {@code 'COPAUS0C'} at {@code cbl/COPAUS0C.cbl} L33, by the move at L734
 * @param title02 the second title-band line, map field {@code TITLE02I} declared at
 *     {@code cpy-bms/COPAU00.cpy} L48; carried position for position from {@code CCDA-TITLE02 PIC X(40)} at
 *     {@code app/cpy/COTTL01Y.cpy} L20 by the move at {@code cbl/COPAUS0C.cbl} L732
 * @param currentTime the screen time as eight rendered positions in hour, minute and second order, map
 *     field {@code CURTIMEI} declared at {@code cpy-bms/COPAU00.cpy} L54; assembled at
 *     {@code cbl/COPAUS0C.cbl} L742 to L746
 * @param accountId the account identifier the summary was requested for, map field {@code ACCTIDI} declared
 *     at {@code cpy-bms/COPAU00.cpy} L60, and the only inbound component of the context group; the baseline
 *     refuses it blank at {@code cbl/COPAUS0C.cbl} L264 and refuses it non-numeric at L273 before moving it
 *     at L283, so it travels as digits only and never as a number
 * @param customerName the customer name composed into 25 positions, map field {@code CNAMEI} declared at
 *     {@code cpy-bms/COPAU00.cpy} L66; assembled by {@code STRING} from the first name, one middle initial
 *     and the last name into {@code CNAMEO} at {@code cbl/COPAUS0C.cbl} L758 to L763, so no single stored
 *     field stands behind it
 * @param customerId the customer identifier, map field {@code CUSTIDI} declared at
 *     {@code cpy-bms/COPAU00.cpy} L72; copied from {@code CUST-ID} at {@code cbl/COPAUS0C.cbl} L757, and
 *     held in this context as {@code PA-CUST-ID PIC 9(09)} at {@code cpy/CIPAUSMY.cpy} L20
 * @param addressLine1 the first composed address line, map field {@code ADDR001I} declared at
 *     {@code cpy-bms/COPAU00.cpy} L78; assembled by {@code STRING} from customer address lines one and two
 *     into {@code ADDR001O} at {@code cbl/COPAUS0C.cbl} L766 to L769
 * @param accountStatus the single-position account status, map field {@code ACCSTATI} declared at
 *     {@code cpy-bms/COPAU00.cpy} L84; the baseline moves nothing into it, as the assumption on it above
 *     records, so this component is absent on every response projected from the baseline's own inputs
 * @param addressLine2 the second composed address line, map field {@code ADDR002I} declared at
 *     {@code cpy-bms/COPAU00.cpy} L90; assembled by {@code STRING} from customer address line three, the
 *     state code and the first five ZIP positions into {@code ADDR002O} at {@code cbl/COPAUS0C.cbl} L771 to
 *     L776
 * @param phoneNumber1 the customer's first telephone number, map field {@code PHONE1I} declared at
 *     {@code cpy-bms/COPAU00.cpy} L96; copied from {@code CUST-PHONE-NUM-1} at {@code cbl/COPAUS0C.cbl}
 *     L779, punctuation included, so it is not a digit string
 * @param approvedCount the number of approved authorizations, map field {@code APPRCNTI} declared at
 *     {@code cpy-bms/COPAU00.cpy} L102; the stored datum is {@code PA-APPROVED-AUTH-CNT PIC S9(04) COMP} at
 *     {@code cpy/CIPAUSMY.cpy} L27, a binary halfword and not money, and this component carries that value
 *     rather than the three-position render at {@code cbl/COPAUS0C.cbl} L788 to L789
 * @param declinedCount the number of declined authorizations, map field {@code DECLCNTI} declared at
 *     {@code cpy-bms/COPAU00.cpy} L108; the stored datum is {@code PA-DECLINED-AUTH-CNT PIC S9(04) COMP} at
 *     {@code cpy/CIPAUSMY.cpy} L28, rendered at {@code cbl/COPAUS0C.cbl} L790 to L791 through the same
 *     three-position field as the approved count
 * @param creditLimit the account credit limit, map field {@code CREDLIMI} declared at
 *     {@code cpy-bms/COPAU00.cpy} L114; taken from {@code ACCT-CREDIT-LIMIT} on the account record rather
 *     than from the summary segment, through the twelve-position mask at {@code cbl/COPAUS0C.cbl} L780 to
 *     L781
 * @param cashLimit the account cash credit limit, map field {@code CASHLIMI} declared at
 *     {@code cpy-bms/COPAU00.cpy} L120; taken from {@code ACCT-CASH-CREDIT-LIMIT} on the account record,
 *     through the nine-position mask at {@code cbl/COPAUS0C.cbl} L782 to L783
 * @param approvedAuthAmount the total approved authorization amount, map field {@code APPRAMTI} declared at
 *     {@code cpy-bms/COPAU00.cpy} L126; from {@code PA-APPROVED-AUTH-AMT PIC S9(09)V99 COMP-3} at
 *     {@code cpy/CIPAUSMY.cpy} L29 through the nine-position mask at {@code cbl/COPAUS0C.cbl} L796 to L797,
 *     so the ten positions this map declares exceed what that mask can fill
 * @param creditBalance the credit balance, map field {@code CREDBALI} declared at
 *     {@code cpy-bms/COPAU00.cpy} L132; from {@code PA-CREDIT-BALANCE PIC S9(09)V99 COMP-3} at
 *     {@code cpy/CIPAUSMY.cpy} L25 through the twelve-position mask at {@code cbl/COPAUS0C.cbl} L792 to
 *     L793
 * @param cashBalance the cash balance, map field {@code CASHBALI} declared at {@code cpy-bms/COPAU00.cpy}
 *     L138; from {@code PA-CASH-BALANCE PIC S9(09)V99 COMP-3} at {@code cpy/CIPAUSMY.cpy} L26 through the
 *     nine-position mask at {@code cbl/COPAUS0C.cbl} L794 to L795
 * @param declinedAuthAmount the total declined authorization amount, map field {@code DECLAMTI} declared at
 *     {@code cpy-bms/COPAU00.cpy} L144; from {@code PA-DECLINED-AUTH-AMT PIC S9(09)V99 COMP-3} at
 *     {@code cpy/CIPAUSMY.cpy} L30 through the nine-position mask at {@code cbl/COPAUS0C.cbl} L798 to L799,
 *     which the ten declared positions likewise exceed
 * @param row1Selection the row 1 selection marker, map field {@code SEL0001I} declared at
 *     {@code cpy-bms/COPAU00.cpy} L150; inbound as well as outbound, since the baseline reads it at
 *     {@code cbl/COPAUS0C.cbl} L286 and moves it into the selection flag at L287, and its attribute byte is
 *     left protected at L614 until the row is populated at L554
 * @param row1TransactionId the row 1 transaction identifier, map field {@code TRNID01I} declared at
 *     {@code cpy-bms/COPAU00.cpy} L156; the normative datum is {@code PA-TRANSACTION-ID PIC X(15)} at
 *     {@code cpy/CIPAUDTY.cpy} L44, one position narrower than this map declares, and it is moved at
 *     {@code cbl/COPAUS0C.cbl} L547
 * @param row1AuthDate the row 1 authorization date rendered with month, day and two-digit-year separators,
 *     map field {@code PDATE01I} declared at {@code cpy-bms/COPAU00.cpy} L162; the stored datum is
 *     {@code PA-AUTH-ORIG-DATE PIC X(06)} at {@code cpy/CIPAUDTY.cpy} L22, held year first and re-ordered
 *     at {@code cbl/COPAUS0C.cbl} L531 to L534
 * @param row1AuthTime the row 1 authorization time rendered with hour, minute and second separators, map
 *     field {@code PTIME01I} declared at {@code cpy-bms/COPAU00.cpy} L168; the stored datum is
 *     {@code PA-AUTH-ORIG-TIME PIC X(06)} at {@code cpy/CIPAUDTY.cpy} L23, with the separators inserted at
 *     {@code cbl/COPAUS0C.cbl} L527 to L529
 * @param row1AuthType the row 1 authorization type, map field {@code PTYPE01I} declared at
 *     {@code cpy-bms/COPAU00.cpy} L174; copied unchanged from {@code PA-AUTH-TYPE PIC X(04)} at
 *     {@code cpy/CIPAUDTY.cpy} L25, so the declared width and the stored width agree here and no narrowing
 *     applies
 * @param row1ApprovalStatus the row 1 approval status, map field {@code PAPRV01I} declared at
 *     {@code cpy-bms/COPAU00.cpy} L180; derived rather than stored, taking {@code 'A'} when
 *     {@code PA-AUTH-RESP-CODE} equals {@code '00'} and {@code 'D'} otherwise at {@code cbl/COPAUS0C.cbl}
 *     L536 to L539, so those two values are its whole domain
 * @param row1MatchStatus the row 1 match status, map field {@code PSTAT01I} declared at
 *     {@code cpy-bms/COPAU00.cpy} L186; copied from {@code PA-MATCH-STATUS PIC X(01)} at
 *     {@code cpy/CIPAUDTY.cpy} L45, whose closed domain is {@code 'P'}, {@code 'D'}, {@code 'E'} and
 *     {@code 'M'} at L46 to L49
 * @param row1ApprovedAmount the row 1 approved amount, map field {@code PAMT001I} declared at
 *     {@code cpy-bms/COPAU00.cpy} L192; sourced from {@code PA-APPROVED-AMT} at {@code cpy/CIPAUDTY.cpy}
 *     L35 and never from {@code PA-TRANSACTION-AMT} at L34, which carries the identical picture, per the
 *     move at {@code cbl/COPAUS0C.cbl} L525 that reaches this row at L553
 * @param row2Selection the row 2 selection marker, map field {@code SEL0002I} declared at
 *     {@code cpy-bms/COPAU00.cpy} L198; inbound as well as outbound, since the baseline reads it at
 *     {@code cbl/COPAUS0C.cbl} L290 and moves it into the selection flag at L291, and its attribute byte is
 *     left protected at L623 until the row is populated at L566
 * @param row2TransactionId the row 2 transaction identifier, map field {@code TRNID02I} declared at
 *     {@code cpy-bms/COPAU00.cpy} L204; the normative datum is {@code PA-TRANSACTION-ID PIC X(15)} at
 *     {@code cpy/CIPAUDTY.cpy} L44, one position narrower than this map declares, and it is moved at
 *     {@code cbl/COPAUS0C.cbl} L559
 * @param row2AuthDate the row 2 authorization date rendered with month, day and two-digit-year separators,
 *     map field {@code PDATE02I} declared at {@code cpy-bms/COPAU00.cpy} L210; the stored datum is
 *     {@code PA-AUTH-ORIG-DATE PIC X(06)} at {@code cpy/CIPAUDTY.cpy} L22, held year first and re-ordered
 *     at {@code cbl/COPAUS0C.cbl} L531 to L534
 * @param row2AuthTime the row 2 authorization time rendered with hour, minute and second separators, map
 *     field {@code PTIME02I} declared at {@code cpy-bms/COPAU00.cpy} L216; the stored datum is
 *     {@code PA-AUTH-ORIG-TIME PIC X(06)} at {@code cpy/CIPAUDTY.cpy} L23, with the separators inserted at
 *     {@code cbl/COPAUS0C.cbl} L527 to L529
 * @param row2AuthType the row 2 authorization type, map field {@code PTYPE02I} declared at
 *     {@code cpy-bms/COPAU00.cpy} L222; copied unchanged from {@code PA-AUTH-TYPE PIC X(04)} at
 *     {@code cpy/CIPAUDTY.cpy} L25, so the declared width and the stored width agree here and no narrowing
 *     applies
 * @param row2ApprovalStatus the row 2 approval status, map field {@code PAPRV02I} declared at
 *     {@code cpy-bms/COPAU00.cpy} L228; derived rather than stored, taking {@code 'A'} when
 *     {@code PA-AUTH-RESP-CODE} equals {@code '00'} and {@code 'D'} otherwise at {@code cbl/COPAUS0C.cbl}
 *     L536 to L539, so those two values are its whole domain
 * @param row2MatchStatus the row 2 match status, map field {@code PSTAT02I} declared at
 *     {@code cpy-bms/COPAU00.cpy} L234; copied from {@code PA-MATCH-STATUS PIC X(01)} at
 *     {@code cpy/CIPAUDTY.cpy} L45, whose closed domain is {@code 'P'}, {@code 'D'}, {@code 'E'} and
 *     {@code 'M'} at L46 to L49
 * @param row2ApprovedAmount the row 2 approved amount, map field {@code PAMT002I} declared at
 *     {@code cpy-bms/COPAU00.cpy} L240; sourced from {@code PA-APPROVED-AMT} at {@code cpy/CIPAUDTY.cpy}
 *     L35 and never from {@code PA-TRANSACTION-AMT} at L34, which carries the identical picture, per the
 *     move at {@code cbl/COPAUS0C.cbl} L525 that reaches this row at L565
 * @param row3Selection the row 3 selection marker, map field {@code SEL0003I} declared at
 *     {@code cpy-bms/COPAU00.cpy} L246; inbound as well as outbound, since the baseline reads it at
 *     {@code cbl/COPAUS0C.cbl} L294 and moves it into the selection flag at L295, and its attribute byte is
 *     left protected at L632 until the row is populated at L578
 * @param row3TransactionId the row 3 transaction identifier, map field {@code TRNID03I} declared at
 *     {@code cpy-bms/COPAU00.cpy} L252; the normative datum is {@code PA-TRANSACTION-ID PIC X(15)} at
 *     {@code cpy/CIPAUDTY.cpy} L44, one position narrower than this map declares, and it is moved at
 *     {@code cbl/COPAUS0C.cbl} L571
 * @param row3AuthDate the row 3 authorization date rendered with month, day and two-digit-year separators,
 *     map field {@code PDATE03I} declared at {@code cpy-bms/COPAU00.cpy} L258; the stored datum is
 *     {@code PA-AUTH-ORIG-DATE PIC X(06)} at {@code cpy/CIPAUDTY.cpy} L22, held year first and re-ordered
 *     at {@code cbl/COPAUS0C.cbl} L531 to L534
 * @param row3AuthTime the row 3 authorization time rendered with hour, minute and second separators, map
 *     field {@code PTIME03I} declared at {@code cpy-bms/COPAU00.cpy} L264; the stored datum is
 *     {@code PA-AUTH-ORIG-TIME PIC X(06)} at {@code cpy/CIPAUDTY.cpy} L23, with the separators inserted at
 *     {@code cbl/COPAUS0C.cbl} L527 to L529
 * @param row3AuthType the row 3 authorization type, map field {@code PTYPE03I} declared at
 *     {@code cpy-bms/COPAU00.cpy} L270; copied unchanged from {@code PA-AUTH-TYPE PIC X(04)} at
 *     {@code cpy/CIPAUDTY.cpy} L25, so the declared width and the stored width agree here and no narrowing
 *     applies
 * @param row3ApprovalStatus the row 3 approval status, map field {@code PAPRV03I} declared at
 *     {@code cpy-bms/COPAU00.cpy} L276; derived rather than stored, taking {@code 'A'} when
 *     {@code PA-AUTH-RESP-CODE} equals {@code '00'} and {@code 'D'} otherwise at {@code cbl/COPAUS0C.cbl}
 *     L536 to L539, so those two values are its whole domain
 * @param row3MatchStatus the row 3 match status, map field {@code PSTAT03I} declared at
 *     {@code cpy-bms/COPAU00.cpy} L282; copied from {@code PA-MATCH-STATUS PIC X(01)} at
 *     {@code cpy/CIPAUDTY.cpy} L45, whose closed domain is {@code 'P'}, {@code 'D'}, {@code 'E'} and
 *     {@code 'M'} at L46 to L49
 * @param row3ApprovedAmount the row 3 approved amount, map field {@code PAMT003I} declared at
 *     {@code cpy-bms/COPAU00.cpy} L288; sourced from {@code PA-APPROVED-AMT} at {@code cpy/CIPAUDTY.cpy}
 *     L35 and never from {@code PA-TRANSACTION-AMT} at L34, which carries the identical picture, per the
 *     move at {@code cbl/COPAUS0C.cbl} L525 that reaches this row at L577
 * @param row4Selection the row 4 selection marker, map field {@code SEL0004I} declared at
 *     {@code cpy-bms/COPAU00.cpy} L294; inbound as well as outbound, since the baseline reads it at
 *     {@code cbl/COPAUS0C.cbl} L298 and moves it into the selection flag at L299, and its attribute byte is
 *     left protected at L641 until the row is populated at L590
 * @param row4TransactionId the row 4 transaction identifier, map field {@code TRNID04I} declared at
 *     {@code cpy-bms/COPAU00.cpy} L300; the normative datum is {@code PA-TRANSACTION-ID PIC X(15)} at
 *     {@code cpy/CIPAUDTY.cpy} L44, one position narrower than this map declares, and it is moved at
 *     {@code cbl/COPAUS0C.cbl} L583
 * @param row4AuthDate the row 4 authorization date rendered with month, day and two-digit-year separators,
 *     map field {@code PDATE04I} declared at {@code cpy-bms/COPAU00.cpy} L306; the stored datum is
 *     {@code PA-AUTH-ORIG-DATE PIC X(06)} at {@code cpy/CIPAUDTY.cpy} L22, held year first and re-ordered
 *     at {@code cbl/COPAUS0C.cbl} L531 to L534
 * @param row4AuthTime the row 4 authorization time rendered with hour, minute and second separators, map
 *     field {@code PTIME04I} declared at {@code cpy-bms/COPAU00.cpy} L312; the stored datum is
 *     {@code PA-AUTH-ORIG-TIME PIC X(06)} at {@code cpy/CIPAUDTY.cpy} L23, with the separators inserted at
 *     {@code cbl/COPAUS0C.cbl} L527 to L529
 * @param row4AuthType the row 4 authorization type, map field {@code PTYPE04I} declared at
 *     {@code cpy-bms/COPAU00.cpy} L318; copied unchanged from {@code PA-AUTH-TYPE PIC X(04)} at
 *     {@code cpy/CIPAUDTY.cpy} L25, so the declared width and the stored width agree here and no narrowing
 *     applies
 * @param row4ApprovalStatus the row 4 approval status, map field {@code PAPRV04I} declared at
 *     {@code cpy-bms/COPAU00.cpy} L324; derived rather than stored, taking {@code 'A'} when
 *     {@code PA-AUTH-RESP-CODE} equals {@code '00'} and {@code 'D'} otherwise at {@code cbl/COPAUS0C.cbl}
 *     L536 to L539, so those two values are its whole domain
 * @param row4MatchStatus the row 4 match status, map field {@code PSTAT04I} declared at
 *     {@code cpy-bms/COPAU00.cpy} L330; copied from {@code PA-MATCH-STATUS PIC X(01)} at
 *     {@code cpy/CIPAUDTY.cpy} L45, whose closed domain is {@code 'P'}, {@code 'D'}, {@code 'E'} and
 *     {@code 'M'} at L46 to L49
 * @param row4ApprovedAmount the row 4 approved amount, map field {@code PAMT004I} declared at
 *     {@code cpy-bms/COPAU00.cpy} L336; sourced from {@code PA-APPROVED-AMT} at {@code cpy/CIPAUDTY.cpy}
 *     L35 and never from {@code PA-TRANSACTION-AMT} at L34, which carries the identical picture, per the
 *     move at {@code cbl/COPAUS0C.cbl} L525 that reaches this row at L589
 * @param row5TransactionId the row 5 transaction identifier, map field {@code TRNID05I} declared at
 *     {@code cpy-bms/COPAU00.cpy} L342; the normative datum is {@code PA-TRANSACTION-ID PIC X(15)} at
 *     {@code cpy/CIPAUDTY.cpy} L44, one position narrower than this map declares, and it is moved at
 *     {@code cbl/COPAUS0C.cbl} L595
 * @param row5AuthDate the row 5 authorization date rendered with month, day and two-digit-year separators,
 *     map field {@code PDATE05I} declared at {@code cpy-bms/COPAU00.cpy} L348; the stored datum is
 *     {@code PA-AUTH-ORIG-DATE PIC X(06)} at {@code cpy/CIPAUDTY.cpy} L22, held year first and re-ordered
 *     at {@code cbl/COPAUS0C.cbl} L531 to L534
 * @param row5AuthTime the row 5 authorization time rendered with hour, minute and second separators, map
 *     field {@code PTIME05I} declared at {@code cpy-bms/COPAU00.cpy} L354; the stored datum is
 *     {@code PA-AUTH-ORIG-TIME PIC X(06)} at {@code cpy/CIPAUDTY.cpy} L23, with the separators inserted at
 *     {@code cbl/COPAUS0C.cbl} L527 to L529
 * @param row5AuthType the row 5 authorization type, map field {@code PTYPE05I} declared at
 *     {@code cpy-bms/COPAU00.cpy} L360; copied unchanged from {@code PA-AUTH-TYPE PIC X(04)} at
 *     {@code cpy/CIPAUDTY.cpy} L25, so the declared width and the stored width agree here and no narrowing
 *     applies
 * @param row5ApprovalStatus the row 5 approval status, map field {@code PAPRV05I} declared at
 *     {@code cpy-bms/COPAU00.cpy} L366; derived rather than stored, taking {@code 'A'} when
 *     {@code PA-AUTH-RESP-CODE} equals {@code '00'} and {@code 'D'} otherwise at {@code cbl/COPAUS0C.cbl}
 *     L536 to L539, so those two values are its whole domain
 * @param row5MatchStatus the row 5 match status, map field {@code PSTAT05I} declared at
 *     {@code cpy-bms/COPAU00.cpy} L372; copied from {@code PA-MATCH-STATUS PIC X(01)} at
 *     {@code cpy/CIPAUDTY.cpy} L45, whose closed domain is {@code 'P'}, {@code 'D'}, {@code 'E'} and
 *     {@code 'M'} at L46 to L49
 * @param row5ApprovedAmount the row 5 approved amount, map field {@code PAMT005I} declared at
 *     {@code cpy-bms/COPAU00.cpy} L378; sourced from {@code PA-APPROVED-AMT} at {@code cpy/CIPAUDTY.cpy}
 *     L35 and never from {@code PA-TRANSACTION-AMT} at L34, which carries the identical picture, per the
 *     move at {@code cbl/COPAUS0C.cbl} L525 that reaches this row at L601
 * @param row5Selection the row 5 selection marker, map field {@code SEL0005I} declared at
 *     {@code cpy-bms/COPAU00.cpy} L384; inbound as well as outbound, since the baseline reads it at
 *     {@code cbl/COPAUS0C.cbl} L302 and moves it into the selection flag at L303, and its attribute byte is
 *     left protected at L650 until the row is populated at L602
 * @param message the message line, map field {@code ERRMSGI} declared at {@code cpy-bms/COPAU00.cpy} L390,
 *     bounded at 78 positions as the assumption on it above sets out; the baseline clears it at
 *     {@code cbl/COPAUS0C.cbl} L186 and fills it from {@code WS-MESSAGE PIC X(80)} at L692
 */
public record PendingAuthSummaryResponse(
        String transactionName,  //  1  TRNNAMEI X(4) L24
        String title01,  //  2  TITLE01I X(40) L30
        String currentDate,  //  3  CURDATEI X(8) L36
        String programName,  //  4  PGMNAMEI X(8) L42
        String title02,  //  5  TITLE02I X(40) L48
        String currentTime,  //  6  CURTIMEI X(8) L54
        String accountId,  //  7  ACCTIDI X(11) L60
        String customerName,  //  8  CNAMEI X(25) L66
        String customerId,  //  9  CUSTIDI X(9) L72
        String addressLine1,  // 10  ADDR001I X(25) L78
        String accountStatus,  // 11  ACCSTATI X(1) L84
        String addressLine2,  // 12  ADDR002I X(25) L90
        String phoneNumber1,  // 13  PHONE1I X(13) L96
        Integer approvedCount,  // 14  APPRCNTI X(3) L102
        Integer declinedCount,  // 15  DECLCNTI X(3) L108
        Money creditLimit,  // 16  CREDLIMI X(12) L114
        Money cashLimit,  // 17  CASHLIMI X(9) L120
        Money approvedAuthAmount,  // 18  APPRAMTI X(10) L126
        Money creditBalance,  // 19  CREDBALI X(12) L132
        Money cashBalance,  // 20  CASHBALI X(9) L138
        Money declinedAuthAmount,  // 21  DECLAMTI X(10) L144
        String row1Selection,  // 22  SEL0001I X(1) L150
        String row1TransactionId,  // 23  TRNID01I X(16) L156
        String row1AuthDate,  // 24  PDATE01I X(8) L162
        String row1AuthTime,  // 25  PTIME01I X(8) L168
        String row1AuthType,  // 26  PTYPE01I X(4) L174
        String row1ApprovalStatus,  // 27  PAPRV01I X(1) L180
        String row1MatchStatus,  // 28  PSTAT01I X(1) L186
        Money row1ApprovedAmount,  // 29  PAMT001I X(12) L192
        String row2Selection,  // 30  SEL0002I X(1) L198
        String row2TransactionId,  // 31  TRNID02I X(16) L204
        String row2AuthDate,  // 32  PDATE02I X(8) L210
        String row2AuthTime,  // 33  PTIME02I X(8) L216
        String row2AuthType,  // 34  PTYPE02I X(4) L222
        String row2ApprovalStatus,  // 35  PAPRV02I X(1) L228
        String row2MatchStatus,  // 36  PSTAT02I X(1) L234
        Money row2ApprovedAmount,  // 37  PAMT002I X(12) L240
        String row3Selection,  // 38  SEL0003I X(1) L246
        String row3TransactionId,  // 39  TRNID03I X(16) L252
        String row3AuthDate,  // 40  PDATE03I X(8) L258
        String row3AuthTime,  // 41  PTIME03I X(8) L264
        String row3AuthType,  // 42  PTYPE03I X(4) L270
        String row3ApprovalStatus,  // 43  PAPRV03I X(1) L276
        String row3MatchStatus,  // 44  PSTAT03I X(1) L282
        Money row3ApprovedAmount,  // 45  PAMT003I X(12) L288
        String row4Selection,  // 46  SEL0004I X(1) L294
        String row4TransactionId,  // 47  TRNID04I X(16) L300
        String row4AuthDate,  // 48  PDATE04I X(8) L306
        String row4AuthTime,  // 49  PTIME04I X(8) L312
        String row4AuthType,  // 50  PTYPE04I X(4) L318
        String row4ApprovalStatus,  // 51  PAPRV04I X(1) L324
        String row4MatchStatus,  // 52  PSTAT04I X(1) L330
        Money row4ApprovedAmount,  // 53  PAMT004I X(12) L336
        String row5TransactionId,  // 54  TRNID05I X(16) L342
        String row5AuthDate,  // 55  PDATE05I X(8) L348
        String row5AuthTime,  // 56  PTIME05I X(8) L354
        String row5AuthType,  // 57  PTYPE05I X(4) L360
        String row5ApprovalStatus,  // 58  PAPRV05I X(1) L366
        String row5MatchStatus,  // 59  PSTAT05I X(1) L372
        Money row5ApprovedAmount,  // 60  PAMT005I X(12) L378
        String row5Selection,  // 61  SEL0005I X(1) L384
        String message) {  // 62  ERRMSGI X(78) L390

    /**
     * The number of positions the reference tree declares for the message line.
     *
     * <p>Assumptions: 78 is read from {@code ERRMSGI PIC X(78)} at
     * {@code app/app-authorization-ims-db2-mq/cpy-bms/COPAU00.cpy} L390 and corroborated three
     * times over: by {@code ERRMSGO PIC X(78)} at L764 of the same file, and by L180 and L344 of the
     * sibling {@code cpy-bms/COPAU01.cpy}. Four agreeing declarations make it a contract rather
     * than a reading.
     *
     * <p>Alternatives Considered: writing the literal into the guard below. Rejected because a named
     * constant leaves one line to compare against L390, whereas a bare 78 inside a condition is a
     * number with no stated provenance sitting where nobody looks for one.
     */
    private static final int MESSAGE_MAX_LENGTH = 78;

    /**
     * The closed set of match-status values, one character per admitted value.
     *
     * <p>Assumptions: the four values are the condition names declared on
     * {@code PA-MATCH-STATUS PIC X(01)} at {@code app/app-authorization-ims-db2-mq/cpy/CIPAUDTY.cpy}
     * L45 -- pending at L46, declined at L47, expired at L48 and matched at L49 -- so the domain is
     * closed by the reference tree and not by a choice made here.
     *
     * <p>Alternatives Considered: an enum for the four values. Rejected because this record is a
     * projection of a one-position screen field, and an enum component would refuse an unrecognised
     * character by failing deserialisation of the whole payload rather than by naming the one
     * component at fault; a character set searched here refuses the component and names it.
     */
    private static final String MATCH_STATUS_DOMAIN = "PDEM";

    /**
     * The closed set of approval-status values, one character per admitted value.
     *
     * <p>Assumptions: these two are derived rather than stored. The reference tree sets the
     * approved value when {@code PA-AUTH-RESP-CODE} equals {@code '00'} and the declined value in
     * every other case, at {@code app/app-authorization-ims-db2-mq/cbl/COPAUS0C.cbl} L536 to L539,
     * so the derivation admits exactly two outcomes and no third is reachable.
     */
    private static final String APPROVAL_STATUS_DOMAIN = "AD";

    /**
     * Refuses the three component families whose value domain the reference tree fixes, so that no instance
     * can exist in a state this type documents as impossible.
     *
     * <p>Assumptions: exactly eleven of the 62 components are inspected here, and the other 51 are stored
     * as supplied. The eleven are the message line, whose 78 positions four declarations agree on, and the
     * five match statuses and five approval statuses, whose domains the reference tree closes explicitly.
     * Nothing else is checked, because for every remaining component the only number available is the map
     * width, and the assumption recorded on this type explains why a check sized from a map width would
     * admit values the stored field cannot hold.
     *
     * <p>Trade-offs: the checks refuse rather than normalise, so this constructor never rewrites a value it
     * was handed. The alternative was to trim padding and fold a blank to an absent value, as the shared
     * kernel's page envelope does for its cursor tokens. It is declined here because the caller is this
     * service's own mapper rather than a remote client: a value that needs rewriting is a defect in the
     * projection, and rewriting it silently would hide the defect while publishing a payload that no longer
     * matches what the mapper produced. Refusing surfaces the defect at the point it was introduced. The
     * cost accepted is that the mapper must present values already in contract, which is where the
     * reference tree's own widths are read anyway.
     *
     * <p>Assumptions: a blank value counts as absent rather than as a domain violation, on the baseline's
     * own representation of an unused row recorded on this type. A response for a page of fewer than five
     * rows therefore passes, which is a real case rather than a hypothetical one.
     *
     * <p><strong>Return value.</strong> A constructor returns no separate value; successful completion
     * initialises the record instance with the supplied components.
     *
     * @param transactionName the transaction identifier as supplied; not inspected here, because this
     *     service produces it from its own configuration and no caller supplies it
     * @param title01 the first title-band line as supplied; not inspected here, because its text is a
     *     constant of the reference tree rather than a value with a domain to police
     * @param currentDate the rendered screen date as supplied; not inspected here, because it is a clock
     *     render this service produces rather than a stored date
     * @param programName the originating program name as supplied; not inspected here, for the same reason
     *     as the transaction identifier above
     * @param title02 the second title-band line as supplied; not inspected here, for the same reason as the
     *     first title-band line above
     * @param currentTime the rendered screen time as supplied; not inspected here, for the same reason as
     *     the rendered screen date above
     * @param accountId the account identifier as supplied; not inspected here, because the digits-only
     *     refusal belongs where a request is bound in the api package and this record is the outbound
     *     projection
     * @param customerName the composed customer name as supplied; not inspected here, because the
     *     composition budget behind it is a screen width and not a stored width
     * @param customerId the customer identifier as supplied; not inspected here, for the same reason as the
     *     account identifier above
     * @param addressLine1 the first composed address line as supplied; not inspected here, for the same
     *     reason as the composed customer name above
     * @param accountStatus the account status as supplied; not inspected here, because the baseline
     *     declares no value domain for it and admitting one would be this record inventing a contract
     * @param addressLine2 the second composed address line as supplied; not inspected here, for the same
     *     reason as the first composed address line above
     * @param phoneNumber1 the first telephone number as supplied; not inspected here, because the baseline
     *     carries its punctuation and a digits-only check would refuse the baseline's own value
     * @param approvedCount the approved count as supplied, or {@code null} when the summary segment was not
     *     found, which is the branch that zeroes the screen from {@code cbl/COPAUS0C.cbl} L800; not
     *     inspected here, because a halfword needs no range guard this record could add
     * @param declinedCount the declined count as supplied, or {@code null} on the segment-not-found branch;
     *     not inspected here, for the same reason as the approved count above
     * @param creditLimit the credit limit as supplied; not inspected here, because the type itself fixes
     *     the scale and the rounding mode, so there is no scale for this constructor to assert
     * @param cashLimit the cash credit limit as supplied; not inspected here, for the same reason as the
     *     credit limit above
     * @param approvedAuthAmount the total approved amount as supplied; not inspected here, for the same
     *     reason as the credit limit above
     * @param creditBalance the credit balance as supplied; not inspected here, for the same reason as the
     *     credit limit above
     * @param cashBalance the cash balance as supplied; not inspected here, for the same reason as the
     *     credit limit above
     * @param declinedAuthAmount the total declined amount as supplied; not inspected here, for the same
     *     reason as the credit limit above
     * @param row1Selection the row 1 selection marker as supplied; not inspected here, because the baseline
     *     admits any non-blank position as a mark at {@code cbl/COPAUS0C.cbl} L286
     * @param row1TransactionId the row 1 transaction identifier as supplied; not inspected here, because
     *     the declared width is a screen width and refusing against it would admit values the stored 15
     *     positions cannot hold
     * @param row1AuthDate the row 1 rendered authorization date as supplied; not inspected here, because
     *     its positions are a render and not a stored width
     * @param row1AuthTime the row 1 rendered authorization time as supplied; not inspected here, for the
     *     same reason as the rendered date above
     * @param row1AuthType the row 1 authorization type as supplied; not inspected here, because the
     *     baseline declares no value domain for it
     * @param row1ApprovalStatus the row 1 approval status, refused unless it is absent or one position
     *     drawn from the derived domain
     * @param row1MatchStatus the row 1 match status, refused unless it is absent or one position drawn from
     *     the closed domain
     * @param row1ApprovedAmount the row 1 approved amount as supplied; not inspected here, for the same
     *     reason as the credit limit above
     * @param row2Selection the row 2 selection marker as supplied; not inspected here, because the baseline
     *     admits any non-blank position as a mark at {@code cbl/COPAUS0C.cbl} L290
     * @param row2TransactionId the row 2 transaction identifier as supplied; not inspected here, because
     *     the declared width is a screen width and refusing against it would admit values the stored 15
     *     positions cannot hold
     * @param row2AuthDate the row 2 rendered authorization date as supplied; not inspected here, because
     *     its positions are a render and not a stored width
     * @param row2AuthTime the row 2 rendered authorization time as supplied; not inspected here, for the
     *     same reason as the rendered date above
     * @param row2AuthType the row 2 authorization type as supplied; not inspected here, because the
     *     baseline declares no value domain for it
     * @param row2ApprovalStatus the row 2 approval status, refused unless it is absent or one position
     *     drawn from the derived domain
     * @param row2MatchStatus the row 2 match status, refused unless it is absent or one position drawn from
     *     the closed domain
     * @param row2ApprovedAmount the row 2 approved amount as supplied; not inspected here, for the same
     *     reason as the credit limit above
     * @param row3Selection the row 3 selection marker as supplied; not inspected here, because the baseline
     *     admits any non-blank position as a mark at {@code cbl/COPAUS0C.cbl} L294
     * @param row3TransactionId the row 3 transaction identifier as supplied; not inspected here, because
     *     the declared width is a screen width and refusing against it would admit values the stored 15
     *     positions cannot hold
     * @param row3AuthDate the row 3 rendered authorization date as supplied; not inspected here, because
     *     its positions are a render and not a stored width
     * @param row3AuthTime the row 3 rendered authorization time as supplied; not inspected here, for the
     *     same reason as the rendered date above
     * @param row3AuthType the row 3 authorization type as supplied; not inspected here, because the
     *     baseline declares no value domain for it
     * @param row3ApprovalStatus the row 3 approval status, refused unless it is absent or one position
     *     drawn from the derived domain
     * @param row3MatchStatus the row 3 match status, refused unless it is absent or one position drawn from
     *     the closed domain
     * @param row3ApprovedAmount the row 3 approved amount as supplied; not inspected here, for the same
     *     reason as the credit limit above
     * @param row4Selection the row 4 selection marker as supplied; not inspected here, because the baseline
     *     admits any non-blank position as a mark at {@code cbl/COPAUS0C.cbl} L298
     * @param row4TransactionId the row 4 transaction identifier as supplied; not inspected here, because
     *     the declared width is a screen width and refusing against it would admit values the stored 15
     *     positions cannot hold
     * @param row4AuthDate the row 4 rendered authorization date as supplied; not inspected here, because
     *     its positions are a render and not a stored width
     * @param row4AuthTime the row 4 rendered authorization time as supplied; not inspected here, for the
     *     same reason as the rendered date above
     * @param row4AuthType the row 4 authorization type as supplied; not inspected here, because the
     *     baseline declares no value domain for it
     * @param row4ApprovalStatus the row 4 approval status, refused unless it is absent or one position
     *     drawn from the derived domain
     * @param row4MatchStatus the row 4 match status, refused unless it is absent or one position drawn from
     *     the closed domain
     * @param row4ApprovedAmount the row 4 approved amount as supplied; not inspected here, for the same
     *     reason as the credit limit above
     * @param row5TransactionId the row 5 transaction identifier as supplied; not inspected here, because
     *     the declared width is a screen width and refusing against it would admit values the stored 15
     *     positions cannot hold
     * @param row5AuthDate the row 5 rendered authorization date as supplied; not inspected here, because
     *     its positions are a render and not a stored width
     * @param row5AuthTime the row 5 rendered authorization time as supplied; not inspected here, for the
     *     same reason as the rendered date above
     * @param row5AuthType the row 5 authorization type as supplied; not inspected here, because the
     *     baseline declares no value domain for it
     * @param row5ApprovalStatus the row 5 approval status, refused unless it is absent or one position
     *     drawn from the derived domain
     * @param row5MatchStatus the row 5 match status, refused unless it is absent or one position drawn from
     *     the closed domain
     * @param row5ApprovedAmount the row 5 approved amount as supplied; not inspected here, for the same
     *     reason as the credit limit above
     * @param row5Selection the row 5 selection marker as supplied; not inspected here, because the baseline
     *     admits any non-blank position as a mark at {@code cbl/COPAUS0C.cbl} L302
     * @param message the message line as supplied, which may be {@code null} or blank when a response
     *     carries no message; refused when it exceeds 78 positions
     * @throws IllegalArgumentException when the message component exceeds 78 positions, or when a match
     *     status or an approval status is present and is not one position drawn from its closed domain
     */
    public PendingAuthSummaryResponse {
        // Assumptions: the message line is checked first because it is the one component
        //   whose width four declarations agree on, so it is the check least likely to be
        //   the one a reader questions when a refusal arrives.
        requireMessageWithinWidth(message);

        // Assumptions: the ten row checks are written out one per row rather than driven from
        //   an array, because a record's components are not indexable and gathering them into
        //   one would allocate on every construction of a payload that is built per request.
        //   The component name is passed as a literal so a refusal names the exact component,
        //   which is information no reflective loop over 62 components could supply here.
        requireClosedDomain(row1ApprovalStatus, APPROVAL_STATUS_DOMAIN, "row1ApprovalStatus");
        requireClosedDomain(row1MatchStatus, MATCH_STATUS_DOMAIN, "row1MatchStatus");
        requireClosedDomain(row2ApprovalStatus, APPROVAL_STATUS_DOMAIN, "row2ApprovalStatus");
        requireClosedDomain(row2MatchStatus, MATCH_STATUS_DOMAIN, "row2MatchStatus");
        requireClosedDomain(row3ApprovalStatus, APPROVAL_STATUS_DOMAIN, "row3ApprovalStatus");
        requireClosedDomain(row3MatchStatus, MATCH_STATUS_DOMAIN, "row3MatchStatus");
        requireClosedDomain(row4ApprovalStatus, APPROVAL_STATUS_DOMAIN, "row4ApprovalStatus");
        requireClosedDomain(row4MatchStatus, MATCH_STATUS_DOMAIN, "row4MatchStatus");
        requireClosedDomain(row5ApprovalStatus, APPROVAL_STATUS_DOMAIN, "row5ApprovalStatus");
        requireClosedDomain(row5MatchStatus, MATCH_STATUS_DOMAIN, "row5MatchStatus");
    }

    /**
     * Refuses a message line longer than the reference tree's declared field.
     *
     * <p>Assumptions: the comparison is on the count of characters rather than on encoded bytes,
     * because the declared field is 78 character positions of a constant-width screen line and the
     * projection carries text. An absent or blank message passes, since a response that reports
     * nothing carries no message and the baseline itself clears the field at
     * {@code app/app-authorization-ims-db2-mq/cbl/COPAUS0C.cbl} L186.
     *
     * <p><strong>Return value.</strong> This guard returns no value; normal completion means the message is
     * absent or fits the declared field.
     *
     * @param candidate the message line as supplied, which may be {@code null}
     * @throws IllegalArgumentException when {@code candidate} is longer than 78 positions; the
     *     refusal names the component and its limit and never reproduces the value, so a message
     *     that carried account data cannot escape through a diagnostic
     */
    private static void requireMessageWithinWidth(String candidate) {
        if (candidate != null && candidate.length() > MESSAGE_MAX_LENGTH) {
            // Assumptions: the refusal quotes the offending length but not the offending text. The
            //   alternative, echoing the value, would put a line that may name an account into every
            //   log that records the failure, and the length alone locates the defect in the mapper.
            throw new IllegalArgumentException(
                    "message exceeds " + MESSAGE_MAX_LENGTH + " positions: length="
                            + candidate.length());
        }
    }

    /**
     * Refuses a present single-position code that its closed domain does not admit.
     *
     * <p>Assumptions: an absent value passes, which is what lets a page of fewer than five rows
     * through; the baseline blanks the data fields of an unused row at
     * {@code app/app-authorization-ims-db2-mq/cbl/COPAUS0C.cbl} L615 to L621 and leaves its
     * selection attribute protected at L614, so a short page is the ordinary case rather than an
     * error.
     *
     * <p>Trade-offs: a present value must be exactly one position, so a value carrying trailing
     * padding is refused rather than trimmed. The cost is that the mapper has to strip padding
     * before it constructs; the compensation is that the stored state and the serialised state are
     * the same state, and no caller has to know which of the two forms it is holding.
     *
     * <p><strong>Return value.</strong> This guard returns no value; normal completion means the code is
     * absent or belongs to the supplied domain.
     *
     * @param candidate the code as supplied, which may be {@code null} or blank for an absent row
     * @param domain the admitted characters, one per admitted value, searched rather than parsed
     * @param componentName the record component being checked, reproduced in the refusal so it
     *     names the one component at fault out of 62
     * @throws IllegalArgumentException when {@code candidate} is present and is not exactly one
     *     character drawn from {@code domain}
     */
    private static void requireClosedDomain(String candidate, String domain,
            String componentName) {
        if (isAbsent(candidate)) {
            return;
        }

        // Assumptions: length and membership are checked together rather than in two steps, because
        //   a two-position value drawn entirely from the domain is as wrong as a one-position value
        //   outside it, and one refusal message covers both without ranking them.
        if (candidate.length() != 1 || domain.indexOf(candidate.charAt(0)) < 0) {
            throw new IllegalArgumentException(componentName + " must be one of [" + domain
                    + "] or absent, but was \"" + candidate + '"');
        }
    }

    /**
     * Reports whether a component value carries no content.
     *
     * <p>Assumptions: blank counts as absent because the reference tree pads a constant-width field
     * with spaces and clears an unused one to spaces, so a space-filled value means the same thing
     * there as a missing value means here. Treating the two differently would refuse the
     * baseline's own representation of an unused row.
     *
     * @param candidate the component value as supplied, which may be {@code null}
     * @return {@code true} when {@code candidate} is {@code null} or consists only of whitespace,
     *     and {@code false} when it carries at least one non-whitespace character
     */
    private static boolean isAbsent(String candidate) {
        return candidate == null || candidate.isBlank();
    }
}
