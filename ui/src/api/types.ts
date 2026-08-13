/**
 * @file The single TypeScript declaration of every wire shape the CardDemo services publish to a
 * browser, plus the vocabulary all of their contracts share.
 *
 * This module declares TYPES ONLY. It holds no constant, no function, no class and no runtime import,
 * so the compiler erases it in full and importing a type from it adds nothing to a bundle. The helper
 * that turns a declared contract operation into the target its client sends is `requestPath` in
 * `ui/src/api/client.ts`, alongside the client that sends it; `ui/src/api/contracts.test.ts` reads this
 * file's source and fails on any runtime declaration, so the property is checked rather than asserted.
 *
 * Purpose
 * -------
 * The repository publishes SEVEN service contracts. Six of them -- auth, card, transaction, reference,
 * authorization and reporting -- are browser-facing in whole. The seventh, `account-api.yaml`, is
 * browser-facing in part: seven of its operations are tagged `internal` and secured with
 * `internalServiceToken`, and this module declares none of the shapes reachable only from those. Every
 * remaining response and request shape of all seven is declared here, once, grouped by the contract
 * that publishes it.
 *
 * Refactoring Rationale: the shapes of six contracts were previously declared inside the client module
 * that calls them, and are declared here now. A wire shape has two kinds of consumer -- the client
 * module that sends and receives it, and the screen that renders it -- so a shape declared inside its
 * client is owned by one of its consumers rather than by the contract it describes. A second consumer
 * then either imports from that client module, which makes one client depend on another for a type
 * neither owns, or restates the shape, which is how two definitions of one wire contract come to drift
 * apart with each screen binding to whichever it happened to import. Declaring all of them here gives
 * every shape exactly one definition, in the one module in this folder that is erased in full.
 *
 * Trade-offs: this module is consequently large, and a reader looking for one shape reads a section
 * header to find it rather than opening the client that uses it. That is the cost paid. What it buys is
 * that there is nowhere else for a wire shape to be -- `ui/src/api/contracts.test.ts` asserts that no
 * client module declares one -- so a shape cannot be added in a second place by a well-meaning author
 * who did not know the first place existed.
 *
 * Assumptions: each client module RE-EXPORTS the shapes of its own contract, so no consumer import
 * changed meaning when the declarations moved. A screen importing `CardSummary` from `../../api/cards`
 * still resolves, and a screen may equally import it from here; both reach one declaration.
 *
 * Assumptions: the shared vocabulary -- the error and abend shapes, the page envelope, the reading
 * direction, the validation state, the severity and subsystem domains, and the money, date and timestamp
 * scalars -- is declared once at the top because every contract restates it in its own document. An
 * OpenAPI document cannot reference a schema in a file that no build step assembles, so each of the
 * seven necessarily repeats those schemas; a divergence between any two copies is a defect in whichever
 * diverged rather than a local choice. All seven were compared property by property against this module,
 * and where one narrows a member -- transaction-api bounds `status` to 400 through 599 while
 * authorization-api leaves it an unbounded int32 -- the NARROWER form is taken here, because a client
 * accepting the wider one would type a value no service sends.
 *
 * Assumptions: the shared field-error entry has exactly THREE members -- `field`, `state` and
 * `message` -- and all seven contracts agree on that. They did not agree while this module was first
 * authored: the emitted record published a derived predicate as a fourth `error` property, and one
 * contract had been amended to admit it while six declared three members and sealed themselves against a
 * fourth. The predicate is now withheld from the wire at its source, and
 * `ApiErrorWireShapeTest` in `services/common-lib` pins the emitted property set, so the three members
 * declared here are what a response carries rather than what it ought to carry.
 *
 * Why the operation manifest lives beside the types
 * ------------------------------------------------
 * Refactoring Rationale: each client module exports a manifest of the operations it implements, and
 * builds every request target from it through `requestPath` in `./client` rather than from a string
 * literal at the call site. The two arrangements are not equivalent. With literals, a module's manifest
 * and its behaviour are two independent descriptions of one thing, so a manifest can agree with the
 * contract while the code beside it calls a different address -- which is a drift a gate reading the
 * manifest cannot see. Deriving the target from the manifest removes that possibility by construction,
 * and leaves `ui/src/api/contracts.test.ts` with exactly one comparison to make: manifest against
 * contract.
 *
 * Trade-offs: the cost is a level of indirection at every call site, where a reader now follows a
 * constant instead of reading a path in place. It is accepted because the alternative failure is
 * silent and this one is merely inconvenient: a mistyped literal reaches an address the edge answers
 * with its own 404 while the service is running, healthy and correct, which is the least diagnosable
 * failure this boundary has.
 *
 * What this module holds, and what it deliberately does not
 * --------------------------------------------------------
 * Assumptions: the page envelope has exactly FOUR members -- `items`, `firstKey`, `lastKey` and
 * `hasNext` -- and carries no answer to whether an EARLIER page exists. That is not an omission: the
 * reference does not answer it from the file either, deciding it from the screen ordinal it already
 * holds, at `app/cbl/COCRDLIC.cbl` L237 to L238 and L902 to L903. The ordinal is client state, so the
 * screen that pages holds it and the envelope publishes only `firstKey`, the POSITION a backward request
 * is issued from. The reasoning is recorded in full on {@link PageResponse}.
 *
 * Assumptions: what is NOT declared here is as deliberate as what is. There is no card verification
 * value, no password outside the sign-on request, no CICS response or reason member on the error shape,
 * no queue payload, and none of the seven internal-only account shapes. Each of those omissions is
 * argued at the cross-cutting decisions block below, beside the citation that settles it.
 */

/**
 * The HTTP methods a CardDemo contract may declare.
 *
 * Assumptions: five members, matching the set `ReportingApiContractTest` filters path-item members
 * on, so the two gates agree about what counts as an operation. `HEAD`, `OPTIONS` and `TRACE` are
 * absent because no contract declares one and a client has no reason to send one.
 */
export type HttpMethod = 'GET' | 'POST' | 'PUT' | 'DELETE' | 'PATCH';

/**
 * One operation a client module implements, named exactly as its contract declares it.
 *
 * Assumptions: `path` is the ABSOLUTE contract path template including the `/api/v1` prefix, and not
 * the relative target the client sends. Holding the contract's own spelling is what lets the drift
 * gate compare this value with a parsed contract directly, with no transformation on either side
 * that could itself be wrong; `requestPath` in `./client` performs the one transformation, in one
 * place. It is named there rather than here because it is a runtime function and this module is
 * erased in full.
 */
export interface ContractOperation {
  readonly method: HttpMethod;

  /**
   * The operation's path as its CONTRACT spells it, prefix included, not the target a client sends.
   *
   * Assumptions: holding the contract's own spelling is what lets the drift gate compare this value
   * with a parsed contract directly; `requestPath` in `./client` performs the one transformation.
   */
  readonly path: string;
  readonly operationId: string;
}

/**
 * Reading direction a browse request pairs with its cursor.
 *
 * Assumptions: the two members are spelled exactly as the services accept them -- lower case -- and
 * not as any Java enum's constant names. All SIX wholly browser-facing contracts publish this pair in a
 * `PageDirection` schema -- auth, authorization, card, reference, reporting and transaction -- and the
 * edge refuses an unrecognised value with HTTP 400 before any handler runs, so a client spelling them
 * otherwise would have every paging request rejected. `account-api.yaml` publishes no such schema,
 * because the two paged operations it declares are among the seven this module treats as internal.
 */
export type PageDirection = 'next' | 'previous';

/**
 * Shared sealed-cursor page envelope returned by every browse operation.
 *
 * Assumptions: FOUR members, matching the `PageResponse` record in common-lib and the `CardPage`,
 * `PageResponse`, `TransactionPage`, `PendingAuthPage`, `TransactionTypePage` and sibling schemas the
 * contracts publish, each of which lists all four as required with `additionalProperties: false`. A
 * fifth member -- a next cursor, a previous cursor, a backward-availability flag, a row total -- would
 * describe a body no service sends, and a member that is always absent is worse than no member because
 * it reads as a value that merely happens to be missing this time.
 *
 * Assumptions: `lastKey` is both the last row's identity and the position a forward request is issued
 * from, and `firstKey` likewise for a backward one. The service seals the direction into each token,
 * so replaying `firstKey` with direction `next` is refused with HTTP 400 rather than answered with
 * the wrong page -- which is what makes one value safe to serve both purposes.
 *
 * Refactoring Rationale: whether an EARLIER page exists is deliberately NOT a member here, because the
 * reference does not answer it from the file either. `app/cbl/COCRDLIC.cbl` declares the screen ordinal
 * `WS-CA-SCREEN-NUM PIC 9(1)` at L237 with `88 CA-FIRST-PAGE VALUE 1` at L238, decrements it on PF7 at
 * L508 and increments it on PF8 at L492, and refuses the backward step with `NO PREVIOUS PAGES TO
 * DISPLAY` at L902 to L903 purely on that ordinal -- no backward probe read is ever issued to decide it.
 * The migration moves that navigation state client-side per AAP section 0.7.1, so this client holds the
 * ordinal and the envelope publishes only the POSITION a backward step is issued from, which is
 * `firstKey`. Alternatives Considered: keeping a server-computed flag. Rejected because the service
 * would have to read backward from a page it has not been asked for, and its answer would still be
 * stale by the time a caller acted on it.
 *
 * Assumptions: `hasNext` is settled by the service from a read of one row MORE than the page holds,
 * which is how the reference settles the same question -- `app/cbl/COCRDLIC.cbl` sets its
 * `WS-CA-NEXT-PAGE-IND PIC X(1)` at L242, whose `88 CA-NEXT-PAGE-EXISTS VALUE 'Y'` at L244 is turned on
 * by discovering a record beyond the seven the screen shows. A client therefore never computes
 * availability from the number of rows it received.
 * @template T The row type of one page, which is the shape the operation returning it declares -- for
 *   example {@link CardXrefResponse} for an account's cross-reference rows.
 */
export interface PageResponse<T> {
  readonly items: readonly T[];

  /**
   * Sealed cursor identifying the FIRST row returned, or nothing when the page carried none.
   *
   * Assumptions: opaque, and the client neither parses, compares nor computes on it -- it is replayed
   * verbatim. The value it seals differs by context and is composite in some of them: the
   * pending-authorization key is two packed-decimal integers at
   * `app/app-authorization-ims-db2-mq/cpy/CIPAUDTY.cpy` L19 to L21, while the card browse key is the
   * card number ALONE. The service also seals the direction into it, so replaying this one forward is
   * refused with 400 rather than answered with the wrong page. Anything a client inferred from its bytes
   * would be inference about an encoding it does not own.
   *
   * Refactoring Rationale: this described the card browse key as "a card number with an account
   * identifier", which is not the physical key. `card.cards` declares `pk_cards PRIMARY KEY (card_num)`
   * and the browse orders and cursors on that column alone -- `CardListService` seals
   * `getCardNum()` and nothing else -- with the account identifier acting as an optional NARROWING
   * filter carried separately in the sealed binding rather than as part of the position. Describing it
   * as composite invited a reader to expect two values inside the token and to treat the account
   * identifier as replayable position data, which it is not.
   */
  readonly firstKey: string | null;
  readonly lastKey: string | null;

  /**
   * Whether reading forward from `lastKey` yields a further page.
   *
   * Assumptions: this is the ONLY availability answer the envelope carries. Its backward counterpart is
   * the caller's own page ordinal, held in the screen that pages, matching the reference's
   * `CA-FIRST-PAGE` test at `app/cbl/COCRDLIC.cbl` L238.
   */
  readonly hasNext: boolean;
}

/**
 * Which of the baseline's two field-level failure conditions applies.
 *
 * Assumptions: two members and no more, because the baseline draws exactly this distinction. The
 * templated highlight copybook `app/cpy/CSSETATY.cpy` moves the error colour into a field when its
 * validation flag is not-OK OR blank, and additionally writes a literal asterisk into the field only
 * in the blank case, so `BLANK` carries a rendering obligation `NOT_OK` does not.
 */
export type FieldValidationState = 'NOT_OK' | 'BLANK';

/** How serious a failure the services classify a problem document as. */
export type Severity = 'LOG' | 'INFO' | 'WARNING' | 'CRITICAL';

/**
 * Which subsystem a failure is attributed to.
 *
 * Assumptions: the six members include three the migrated system has no runtime for -- `CICS`,
 * `IMS` and `QUEUE` in its mainframe sense -- and they are retained rather than pruned because the
 * enumeration transcribes the baseline's own attribution vocabulary and a client must accept every
 * value a service may send.
 */
export type Subsystem = 'APPLICATION' | 'CICS' | 'IMS' | 'RELATIONAL' | 'QUEUE' | 'OBJECT_STORE';

/**
 * One failing request property, as the shared advice renders it.
 *
 * Assumptions: `message` is the verbatim string the baseline raises for that field, at the
 * seventy-five-character width its carriers declare, and it is rendered to the user unchanged.
 * Transformation rule T8 requires user-visible strings to be carried across character for character,
 * so a client must never reword one.
 */
export interface FieldError {
  readonly field: string;
  readonly state: FieldValidationState;
  readonly message: string;
}

/**
 * The structured equivalent of the baseline's `ABEND-DATA` group.
 *
 * Assumptions: four members at the widths `app/cpy/CSMSG02Y.cpy` L45 to L53 declares. It is a
 * diagnostic surface and not a user-facing one, which is why every contract declares it nullable:
 * an ordinary refusal carries none.
 */
export interface AbendDetail {
  readonly abendCode: string;
  readonly abendCulprit: string;
  readonly abendReason: string;
  readonly abendMsg: string;
}

/**
 * One problem document, as the shared advice in common-lib renders it.
 *
 * Assumptions: `fieldErrors` is always present and is empty rather than absent when a failure names
 * no field. Every contract declares it required, so a client checking for its presence would be
 * checking a condition that never occurs; checking its length is the meaningful test.
 */
export interface ApiError {
  readonly code: string;
  readonly secondaryCode: string;
  readonly message: string | null;
  readonly severity: Severity;
  readonly subsystem: Subsystem;
  readonly status: number;
  readonly correlationId: string;
  readonly path: string;
  readonly timestamp: string;
  readonly fieldErrors: readonly FieldError[];
  readonly abend: AbendDetail | null;
}

// WHY : Assumptions: the two scalars every browser-facing contract carries in the same shape are
//       named here so that a reader of a member below sees the constraint instead of an unqualified
//       `string`. Both are transparent aliases of `string`, which is what makes them safe to
//       introduce beside modules that already spell these members `string` -- an alias is the same
//       type, so no existing declaration changes meaning and no existing call site has to move. What
//       they add is the citation: the reason a monetary amount is not a `number` belongs where the
//       amount is declared, not in a reviewer's memory.

/**
 * An exact monetary amount, carried as text with two fractional digits.
 *
 * Assumptions: the wire form is the `Money` schema of `account-api.yaml`, `type: string` with pattern
 * `^-?[0-9]{1,10}\.[0-9]{2}$`, and the sibling contracts inline the same patterned string. The ten
 * integral digits are the width the reference layouts declare: `05 ACCT-CURR-BAL PIC S9(10)V99.` at
 * `app/cpy/CVACT01Y.cpy` L7, a zoned-decimal field whose sign is overpunched onto its last byte. The
 * server holds the same value as `NUMERIC(p,2)` in SQL and as a `BigDecimal` at scale 2 with
 * `HALF_UP`, and `com.carddemo.common.money.MoneyModule` is what puts it on the wire as a string.
 *
 * Alternatives Considered: `number`, which is what a JSON number would deserialise to. Rejected
 * because JavaScript has one numeric type and it is an IEEE-754 double, so `0.1 + 0.2` is not `0.3`
 * and a cent is lost at the boundary the user actually reads. The failure is the worst kind available
 * here: it produces a plausible figure rather than an error, so a statement balance that is a cent
 * wrong looks exactly like one that is right.
 *
 * Assumptions: the baseline itself transports money as characters, so this is transcription and not
 * invention. The account-update program snapshots the balance as `ACUP-OLD-CURR-BAL PIC X(12)` and
 * only then redefines it as `PIC S9(10)V99` for arithmetic, at `app/cbl/COACTUPC.cbl` L675 to L677,
 * with the same X-over-9 pair for the credit limit at L678 to L680 and the cash credit limit at L681
 * to L683. The bill-pay screen declares its balance field `CURBALI PIC X(14)` at
 * `app/cpy-bms/COBIL00.CPY` L66.
 *
 * Trade-offs: arithmetic on this type is not available without an explicit decimal conversion, and
 * that is the point rather than a cost incurred by accident. A screen that needs a total asks the
 * service for one; the reference computes its own page, account and grand totals in the batch that
 * renders the report rather than on the terminal.
 */
export type Money = string;

/**
 * A calendar date in `YYYY-MM-DD` form, ten characters wide.
 *
 * Assumptions: the stored value is already this text at this width -- `ACCT-OPEN-DATE PIC X(10)` at
 * `app/cpy/CVACT01Y.cpy` L10 and `CUST-DOB-YYYY-MM-DD PIC X(10)` at `app/cpy/CVCUS01Y.cpy` L19 -- and
 * the components are already in most-significant-first order, so a lexical comparison of two of these
 * orders them the same way a date comparison would. Nothing is gained by parsing one to compare it.
 *
 * Alternatives Considered: a `Date`. Rejected on two independent grounds. A `Date` is an instant and
 * therefore carries a time zone, so an account expiry date read in one zone and rendered in another
 * can move by a day -- and the expiry boundary is inclusive in the reference, where a transaction
 * dated equal to the expiration date posts and one day later is refused, so a day of drift changes an
 * outcome rather than a display. And a round trip through `Date` cannot preserve what the sibling
 * timestamp members carry: `TRAN-ORIG-TS` and `TRAN-PROC-TS` are `PIC X(26)` at
 * `app/cpy/CVTRA05Y.cpy` L16 and L17, the form `YYYY-MM-DD HH:MM:SS.mmmmmm`, whose microsecond
 * precision a millisecond-resolution `Date` truncates. Those members stay text in their own modules
 * for this reason, and dates stay text here for consistency with them.
 */
export type IsoDate = string;

/**
 * A twenty-six-character local timestamp, carried as opaque text.
 *
 * Assumptions: the form is `YYYY-MM-DD HH:MM:SS.mmmmmm` -- space-separated, microsecond precision, no
 * zone designator -- which is what `TimestampFormatter` in
 * `services/common-lib/src/main/java/com/carddemo/common/time/TimestampFormatter.java` emits and what
 * `TRAN-ORIG-TS` and `TRAN-PROC-TS` declare as `PIC X(26)` at `app/cpy/CVTRA05Y.cpy` L16 and L17. Four
 * of the seven contracts publish it as a named `Timestamp26` scalar with `minLength` and `maxLength`
 * both 26 and a pattern; this alias is its single TypeScript expression.
 *
 * Refactoring Rationale: an alias of `string` and never `Date`, for a stronger reason than the one that
 * keeps {@link IsoDate} text. A `Date` holds milliseconds, so a round trip through one TRUNCATES the
 * last three digits of the fraction -- the value would still render plausibly and would no longer be the
 * value the service sent. The separator is also a space rather than a `T`, so a strict date-time parse
 * rejects a perfectly well-formed value, and there is no zone to interpret: reading one as UTC or as
 * local are both assertions the stored value does not make.
 *
 * Trade-offs: an alias is a transparent name, so nothing prevents a plain `string` being assigned where
 * this type is declared. What it buys is the citation and the truncation warning at every member that
 * carries a timestamp, in place of an unqualified `string` a reader has to ask about. The compile-time
 * guarantee lives on the service side, where the shared formatter is the only producer.
 *
 * Assumptions: one member in the statement projection legitimately carries twenty-four significant
 * characters followed by two blanks, because the reference reformatting step at
 * `app/jcl/CREASTMT.JCL` L54 copies only 24 of the field's 26 characters. The width is still 26, so this
 * alias still describes it; `reporting-api.yaml` names that member's own scalar so the two admissible
 * contents are stated where they apply rather than relaxed for every timestamp.
 */
export type Timestamp26 = string;

// WHY : Assumptions: the notes below are the cross-cutting decisions this module is answerable for,
//       each recorded once beside the declarations that carry it out, and several of them govern a
//       TYPE declared in the service module that owns the contract rather than here. Every note is
//       followed to a real declaration in this file or to a cited source line, so a reader can check
//       it rather than take it. They are gathered here rather than repeated per member because each
//       governs several shapes at once, and a decision found in only one of the places it applies
//       tends to be re-decided in the others.
//
//       Assumptions: no card verification value appears in any shape in this module, under this or any
//       other name. The reference record does carry one -- `CARD-CVV-CD PIC 9(03)` at
//       `app/cpy/CVACT02Y.cpy` L7 -- and no operation of any of the seven contracts returns it, so no
//       shape may declare it. The omission is deliberate and is recorded because the record layout is
//       the source these shapes are read from, and someone comparing the two would otherwise find a
//       field missing and complete the mapping in good faith.
//
//       Trade-offs: a primary account number is rendered masked everywhere except one administrative
//       shape. `CardSummary.displayCardNumber` carries the masked rendering and `AdminCardDetail`
//       carries the sixteen digits, at an address the service restricts to the administrative group.
//       The compromise accepted is that the disclosure exists at all; what bounds it is that it is
//       visible in the type system, on one named shape, rather than being an undeclared property of a
//       general one.
//
//       Refactoring Rationale: every closed literal domain is a UNION here and never `string`. There
//       are nine: {@link UserType}, {@link FieldValidationState}, {@link Severity}, {@link Subsystem},
//       {@link PageDirection}, {@link ApprovalStatus}, {@link MatchStatus}, {@link AuthFraudFlag} with
//       {@link FraudAction}, and the reference contract's {@link PhoneAreaCodeClass},
//       {@link DateMask}, {@link DateFeedbackCode}, {@link MaintenanceActionType},
//       {@link MaintenanceActionOutcomeState} and {@link ReportBand}. Each is closed by a source the
//       migration does not get to choose -- a copybook condition name, a check constraint, or an enum
//       the contract publishes -- so a union makes a value outside the domain a compile error, where
//       `string` would defer it to a field error the service answers at run time. The cost is that
//       widening a domain touches this file; that is the intended cost, because widening one is a
//       contract change.
//
//       Assumptions: the two-value user-type domain is `'A'` for administrator and `'U'` for user. The
//       authority for the VALUE SET is `app/cpy/COCOM01Y.cpy` L26 to L28, where
//       `CDEMO-USER-TYPE PIC X(01)` is followed by `88 CDEMO-USRTYP-ADMIN VALUE 'A'` and
//       `88 CDEMO-USRTYP-USER VALUE 'U'`. The width alone comes from `SEC-USR-TYPE PIC X(01)` at
//       `app/cpy/CSUSR01Y.cpy` L22, which is why that line is not the citation: it constrains how wide
//       the field is and says nothing about which values it admits.
//
//       Assumptions: the authorization match domain is `'P'`, `'D'`, `'E'` and `'M'` and the fraud
//       domain is `'F'` and `'R'`. Both are read from the condition names of the detail segment:
//       `app/app-authorization-ims-db2-mq/cpy/CIPAUDTY.cpy` L46 to L49 for pending, declined,
//       pending-expired and matched, and L51 to L52 for confirmed and removed. The fraud member is used
//       nullably because an authorization nobody has marked carries no tag, which is a state and not a
//       missing datum -- and the untagged state has exactly ONE spelling on the wire, because the
//       service normalises a stored blank to null before serialising.
//
//       Assumptions: the five account-status slots of the summary segment are five discretely named
//       members and not an array, declared as `accountStatus1` through `accountStatus5` on
//       {@link PendingAuthSummary}. The arity is fixed at five by
//       `app/app-authorization-ims-db2-mq/cpy/CIPAUSMY.cpy` L22, `05 PA-ACCOUNT-STATUS PIC X(02)
//       OCCURS 5 TIMES.`, and the schema holds it as five columns. An array would type a length the
//       contract does not have, so a screen would have to handle a sixth element that cannot arrive and
//       a fourth that cannot be missing.
//
//       Refactoring Rationale: no shape here carries a password except {@link SignOnRequest}, which
//       exchanges one for a token and stores nothing. The reference holds the credential in the
//       clear -- `05 SEC-USR-PWD PIC X(08).` at `app/cpy/CSUSR01Y.cpy` L21 -- compares it directly at
//       `app/cbl/COSGN00C.cbl` L223, and writes it back to the screen at `app/cbl/COUSR02C.cbl` L169.
//       The target keeps no such column and no such member: identity moves to a managed user pool, so
//       there is nothing for a user shape to carry. This is the divergence the traceability register
//       records as D-4, and it is the one place where parity with the baseline is declined rather than
//       preserved.
//
//       Refactoring Rationale: {@link ApiError} declares no CICS response or reason member, and the
//       omission is a decision rather than an oversight. The reference composes its file-error sentence
//       from the internal file name plus the CICS response and reason codes -- `app/cbl/COCRDLIC.cbl`
//       L153 to L172 builds exactly that and L1254 moves it to the message field -- and every one of
//       those three values names something inside the service: a dataset, a platform condition, a
//       platform sub-condition. None is actionable by a browser and all three are disclosure. The
//       migrated shape carries `code`, `secondaryCode` and `correlationId` instead, so a caller has a
//       stable value to branch on, a value to distinguish conditions that share a status, and an
//       identifier that leads an operator to the log line where the platform detail legitimately lives.
//       Adding a response or reason member here would put the detail back on the wire, which is why
//       neither exists to be populated.
//
//       Refactoring Rationale: three reference field names carry a misspelling and the target names all
//       three as spelled: `ACCT-EXPIRAION-DATE` at `app/cpy/CVACT01Y.cpy` L11 and
//       `CARD-EXPIRAION-DATE` at `app/cpy/CVACT02Y.cpy` L9 both become `expirationDate`, and
//       `PA-MERCHANT-CATAGORY-CODE` at `app/app-authorization-ims-db2-mq/cpy/CIPAUDTY.cpy` L36 becomes
//       `merchantCategoryCode`. All three baseline spellings are named here so the lineage between a
//       member and the field it is read from is never ambiguous, and the copybooks themselves are
//       untouched: they are the behavioural oracle for this migration and stay byte-identical, so the
//       divergence is documented rather than removed at its source.
//
//       Assumptions: the authorization request and reply payloads are NOT declared in this module. They
//       are queue contracts in comma-separated form, eighteen fields outbound and six inbound, decoded
//       by `com.carddemo.common.codec.CsvAuthCodec` on the server. A browser never receives one, so
//       declaring the shape here would create a second definition of a contract that has an owner --
//       and a definition no test in this tree could hold to account, since nothing in the browser
//       exercises that path.
//
//       Assumptions: the seven INTERNAL shapes of `account-api.yaml` are not declared here either, for
//       the same reason and with a stronger one behind it. `AccountContextView`, `AccountLookupRequest`,
//       `CardXrefLookupRequest`, `CardXrefView`, `CustomerLookupRequest`, `CustomerPage` and
//       `CustomerResponse` are each reachable only from an operation that document tags `internal` and
//       secures with `internalServiceToken`, so a browser cannot call one at all. Two of them are worse
//       than merely unreachable: the cross-reference lookup body carries a FULL primary account number,
//       and the two identifier lookups type their identifiers as numbers rather than as the text every
//       screen-facing shape uses. Declaring them on the browser surface offered a shape no browser may
//       send and made a full card number reachable from a screen's own type imports.

// WHY : Assumptions: the shapes below are those of `account-api.yaml`, the one browser-facing
//       contract with no service module of its own, covering the account view, the account update
//       and the card cross-reference reads. Every member is declared exactly as that document
//       declares it -- same
//       name, same nullability, same optionality -- and where the document and the record layout
//       disagree the DOCUMENT is followed. The two disagree deliberately in four places, because the
//       account-view screen shows a narrower value than the record stores and the reference itself
//       performs that narrowing with a direct move. Reading the record instead would type four
//       members wider than any response can carry.
//
//       Assumptions: the contract declares `additionalProperties: false` on every one of these, so a
//       member absent below is a member no response carries, and adding one here would type a value
//       the service is forbidden from sending.

/**
 * The request body both account-keyed reads take: the account to resolve, and nothing else.
 *
 * Assumptions: this mirrors `AccountLookupRequest` in `account-api.yaml`, whose single property is
 * required and is declared `type: string` with `pattern: '^[0-9]{1,11}$'` and `maxLength: 11` -- the
 * eleven-digit width `XREF-ACCT-ID PIC 9(11)` declares at L7 of `app/cpy/CVACT03Y.cpy`.
 *
 * Refactoring Rationale: the shape is declared HERE and no longer written inline where the body is
 * composed. `ui/src/api/accounts.ts` used to declare its own `{ accountId: number }` return type at the
 * point of use, which made a second definition of one contract shape -- free to drift from the document
 * and outside the reach of the gate in `ui/src/api/contracts.test.ts` that keeps every wire shape to one
 * definition. Naming it here puts it beside the response shapes of the same contract.
 *
 * Assumptions: the member is TEXT and is never a number, which is the same reason every identifier in
 * this module is text. The reference holds it as characters and reinterprets it as a number only for
 * arithmetic -- `10 CC-ACCT-ID PIC X(11)` at `app/cpy/CVCRD01Y.cpy` L34 with
 * `10 CC-ACCT-ID-N REDEFINES CC-ACCT-ID PIC 9(11).` at L36 -- and the eleven-character width carries
 * leading zeroes that no numeric type can hold. A screen sends what its operator typed; the service
 * converts once, when it addresses the stored row.
 */
export interface AccountLookupRequest {
  readonly accountId: string;
}

/**
 * One account and its customer as the account-view operation returns them.
 *
 * Assumptions: this mirrors `AccountViewResponse` in `account-api.yaml`, whose five members are all
 * required. The composition -- an identifier, an account part, a customer part and two message
 * channels -- is the shape of the screen rather than the shape of either record, because the reference
 * builds this view from two files at once: `app/cbl/COACTVWC.cbl` reads the account and then the
 * customer the cross-reference names, and renders both on one map.
 */
export interface AccountViewResponse {
  /**
   * The account that was read, echoed as digits so the caller can confirm what it received.
   *
   * Assumptions: eleven digits at `^[0-9]{1,11}$`, from `ACCT-ID PIC 9(11)` at
   * `app/cpy/CVACT01Y.cpy` L5. It is text in this body while the path parameter addressing the same
   * account is an integer, and the contract states that difference is deliberate: every value on this
   * screen is character data, so a body member typed as a number would be the only one a client had to
   * convert back before rendering it.
   */
  readonly accountId: string;

  /** The account's own stored values, as {@link AccountDetail} describes them. */
  readonly account: AccountDetail;

  /** The customer the account belongs to, at screen widths, as {@link CustomerDetail} describes. */
  readonly customer: CustomerDetail;

  /**
   * The screen's information line, or nothing when there is none to show.
   *
   * Assumptions: forty characters, the width `05 WS-INFO-MSG PIC X(40)` declares at
   * `app/cbl/COACTVWC.cbl` L110 -- not the forty-five its map container holds. The container is the
   * box and the program's field is what the program moves into it, so forty is the binding figure and
   * a caller sizing for forty-five would be allowing for a value this system never emits. This
   * operation always leaves it empty; the member exists because the update response shares the shape
   * and does fill it.
   */
  readonly informationMessage: string | null;

  /**
   * The screen's message line, or nothing when there is none to show.
   *
   * Assumptions: seventy-five characters, matching `WS-RETURN-MSG` at `app/cbl/COACTVWC.cbl` L117 and
   * the two communication-area carriers `CCARD-ERROR-MSG` and `CCARD-RETURN-MSG`, both
   * `PIC X(75)` at `app/cpy/CVCRD01Y.cpy` L28 and L29. It is a rendering constraint carried across
   * rather than a suggestion, and it is NOT the seventy-eight of the map's `ERRMSGI` field.
   */
  readonly returnMessage: string | null;
}

/**
 * The stored values of one account.
 *
 * Assumptions: this mirrors `AccountDetail` in `account-api.yaml`, whose ten members are all required,
 * and the member ORDER is the contract's rather than the record's -- the document interleaves the
 * money and date members, and following it keeps a reader comparing the two files line for line. The
 * source record is `ACCOUNT-RECORD` at `app/cpy/CVACT01Y.cpy` L4 to L17, three hundred bytes.
 *
 * Assumptions: the record's `ACCT-ID` and `ACCT-ADDR-ZIP` are absent here because the contract does
 * not declare them on this shape. The identifier is carried once by the enclosing response instead of
 * twice, and the postal code the account-view screen shows is the customer's. `FILLER PIC X(178)` at
 * L17 is padding to the fixed record length and is dropped, as every fixed-width pad is.
 */
export interface AccountDetail {
  /**
   * Whether the account is active, as one character.
   *
   * Trade-offs: typed as `string` and NOT as a two-value union, even though the example the contract
   * gives is `Y`. `account-api.yaml` declares this member as `type: string` with `maxLength: 1` and no
   * enumeration, while `card-api.yaml` DOES enumerate its own card status as `Y` or `N` -- which is
   * why `ui/src/api/cards.ts` narrows there and this does not. Narrowing here would reject a value
   * this contract permits its service to send, and a response the client refuses to type is worse than
   * a character it renders as it arrives.
   */
  readonly activeStatus: string;

  /** When the account was opened. `ACCT-OPEN-DATE PIC X(10)` at `app/cpy/CVACT01Y.cpy` L10. */
  readonly openDate: IsoDate;

  /** The account's credit limit. `ACCT-CREDIT-LIMIT PIC S9(10)V99` at `app/cpy/CVACT01Y.cpy` L8. */
  readonly creditLimit: Money;

  /**
   * When the account expires.
   *
   * Refactoring Rationale: the baseline declares this field `ACCT-EXPIRAION-DATE` at
   * `app/cpy/CVACT01Y.cpy` L11, with `EXPIRAION` for `EXPIRATION`; the target names it
   * `expirationDate`. The baseline spelling is named here so the lineage between the two is never in
   * doubt, and the copybook itself is untouched -- it is the behavioural oracle for this migration and
   * stays byte-identical. The divergence is documented rather than left for a reader to infer from a
   * name that does not match its source.
   *
   * Assumptions: the expiry boundary this value participates in is INCLUSIVE -- a transaction dated
   * equal to it posts and one day later is refused -- which is why the member is the same ten-
   * character text the reference compares rather than a parsed instant.
   */
  readonly expirationDate: IsoDate;

  /** The cash advance limit. `ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99` at `app/cpy/CVACT01Y.cpy` L9. */
  readonly cashCreditLimit: Money;

  /** When the account was last reissued. `ACCT-REISSUE-DATE PIC X(10)` at L12 of that copybook. */
  readonly reissueDate: IsoDate;

  /** The balance owed. `ACCT-CURR-BAL PIC S9(10)V99` at `app/cpy/CVACT01Y.cpy` L7. */
  readonly currentBalance: Money;

  /** Credits posted this cycle. `ACCT-CURR-CYC-CREDIT PIC S9(10)V99` at L13 of that copybook. */
  readonly currentCycleCredit: Money;

  /**
   * The disclosure group whose interest rates apply to this account.
   *
   * Assumptions: ten characters, `ACCT-GROUP-ID PIC X(10)` at `app/cpy/CVACT01Y.cpy` L16. It is the
   * first component of the disclosure-group key, and a value with no matching group row falls back to
   * the group named `DEFAULT` rather than failing, which is why an unfamiliar value here is not
   * necessarily an error.
   */
  readonly groupId: string;

  /** Debits posted this cycle. `ACCT-CURR-CYC-DEBIT PIC S9(10)V99` at L14 of that copybook. */
  readonly currentCycleDebit: Money;
}

/**
 * One customer at the widths the account-view MAP declares.
 *
 * Assumptions: this mirrors `CustomerDetail` in `account-api.yaml`, eighteen required members, and it
 * is NOT interchangeable with the `CustomerResponse` shape that contract declares for its
 * service-to-service reads. Four members are narrower here than the record stores them and one is named
 * differently, because this shape is what the account-view screen shows and the reference narrows those
 * values itself with a direct move. Carrying the wider internal shape where this is expected would put a
 * ten-character postal code into a five-character field. That internal shape is deliberately absent from
 * this module -- see the account section header for why the browser surface declares none of them.
 *
 * Assumptions: the national identifier and the government-issued identifier appear ONLY in masked
 * form. Both are held encrypted by the service -- `CUST-SSN PIC 9(09)` at `app/cpy/CVCUS01Y.cpy` L17
 * and `CUST-GOVT-ISSUED-ID PIC X(20)` at L18 -- and no read operation returns either in the clear, so
 * no unmasked member is declared for a screen to reach for.
 */
export interface CustomerDetail {
  /**
   * The customer read, as digits. `CUST-ID PIC 9(09)` at `app/cpy/CVCUS01Y.cpy` L5.
   *
   * Assumptions: text rather than a number, on the same terms as every identifier in these shapes. A
   * nine-digit value would survive as a number, but a sixteen-digit card number would not -- ten to
   * the sixteenth exceeds the largest exactly representable integer, about 9.007 times ten to the
   * fifteenth -- and leading zeros are part of a fixed-width contract that a numeric type discards.
   * The reference draws the same distinction itself, holding each identifier as characters and
   * redefining it as digits only where it computes: `CC-CUST-ID PIC X(09)` with
   * `CC-CUST-ID-N REDEFINES CC-CUST-ID PIC 9(9)` at `app/cpy/CVCRD01Y.cpy` L40 and L42, and the same
   * pair for the account at L34 and L36 and for the card number at L37 and L39.
   */
  readonly customerId: string;

  /**
   * The national identifier, rendered masked.
   *
   * Assumptions: up to twelve characters, which is WIDER than the nine digits stored, because a mask
   * carries separators the stored value does not. Derived from `CUST-SSN PIC 9(09)` at
   * `app/cpy/CVCUS01Y.cpy` L17. The member name says `Masked` so that a screen cannot bind it to a
   * field expecting the whole value and quietly render a partial one as if it were complete.
   */
  readonly ssnMasked: string;

  /** The customer's date of birth. `CUST-DOB-YYYY-MM-DD PIC X(10)` at `app/cpy/CVCUS01Y.cpy` L19. */
  readonly dateOfBirth: IsoDate;

  /**
   * The customer's credit score, as up to three digits of text.
   *
   * Trade-offs: this is a bounded small integer and is still typed as `string`, which looks like the
   * money rule applied where it does not belong. It is the CONTRACT that decides: `account-api.yaml`
   * declares this member `type: string`, `maxLength: 3`, pattern `^[0-9]{1,3}$`, and cites the map
   * field `ACSTFCOI PIC X(3)` over the stored `CUST-FICO-CREDIT-SCORE PIC 9(03)` at
   * `app/cpy/CVCUS01Y.cpy` L22. The screen carries it as characters, so the body does too. The reason
   * money is text is exactness; the reason this is text is agreement with the document -- two
   * different reasons reaching the same type, and worth distinguishing so neither is applied by reflex
   * to a member the contract types as a number.
   */
  readonly ficoCreditScore: string;

  /** Given name. `ACSFNAMI PIC X(25)`, from `CUST-FIRST-NAME PIC X(25)` at L6. */
  readonly firstName: string;

  /**
   * Middle name, or nothing.
   *
   * Assumptions: required-but-nullable, because a customer legitimately has none and the load records
   * an all-blank fixed-width field as absent rather than as a string of spaces. From
   * `CUST-MIDDLE-NAME PIC X(25)` at `app/cpy/CVCUS01Y.cpy` L7. The key is present in the body either
   * way, carrying an explicit empty value, so a screen tests the value and never the key.
   */
  readonly middleName: string | null;

  /** Family name. `ACSLNAMI PIC X(25)`, from `CUST-LAST-NAME PIC X(25)` at L8. */
  readonly lastName: string;

  /** First address line. `ACSADL1I PIC X(50)`, from `CUST-ADDR-LINE-1 PIC X(50)` at L9. */
  readonly addressLine1: string;

  /**
   * Two-character state code. `ACSSTTEI PIC X(2)`, from `CUST-ADDR-STATE-CD PIC X(02)` at L12.
   *
   * Assumptions: the service validates a submitted code against seeded reference data, so a value
   * here is one that passed that check rather than arbitrary text.
   */
  readonly stateCode: string;

  /** Second address line, or nothing. From `CUST-ADDR-LINE-2 PIC X(50)` at L10; nullable as stored. */
  readonly addressLine2: string | null;

  /**
   * Postal code at the screen's five characters.
   *
   * Assumptions: this is the first of the four places this shape is narrower than the record.
   * `ACSZIPCI PIC X(5)` against the stored `CUST-ADDR-ZIP PIC X(10)` at `app/cpy/CVCUS01Y.cpy` L14,
   * and the reference performs the narrowing itself with a direct move onto the shorter field. The
   * narrowing is reproduced rather than widened back, because widening it would let this shape carry
   * ten characters the screen it describes cannot show.
   */
  readonly zipCode: string;

  /**
   * The city line of the address.
   *
   * Assumptions: fifty characters, stored in `CUST-ADDR-LINE-3 PIC X(50)` at `app/cpy/CVCUS01Y.cpy`
   * L11. The map names this field for what the screen asks of it while the record names it by position
   * in the address block; the contract follows the map, and the internal `CustomerResponse` shape in
   * `account-api.yaml` follows the record. The two names describe one stored value.
   */
  readonly city: string;

  /** Three-character country code. `ACSCTRYI PIC X(3)`, from `CUST-ADDR-COUNTRY-CD PIC X(03)` at L13. */
  readonly countryCode: string;

  /**
   * Primary telephone number at the screen's thirteen characters.
   *
   * Assumptions: `ACSPHN1I PIC X(13)`, narrowed from `CUST-PHONE-NUM-1 PIC X(15)` at
   * `app/cpy/CVCUS01Y.cpy` L15 by the reference's own direct move, which drops exactly the two-
   * character trailing pad the wider field declares. Non-null on this shape, unlike its counterpart on
   * the internal `CustomerResponse` shape, which mirrors the nullable record field.
   */
  readonly phoneNumber1: string;

  /**
   * The government-issued identifier, rendered masked.
   *
   * Assumptions: twenty characters, the stored width of `CUST-GOVT-ISSUED-ID PIC X(20)` at
   * `app/cpy/CVCUS01Y.cpy` L18. Held encrypted by the service and never returned in the clear by a
   * read; the update request is the only shape carrying the whole value, and only inbound.
   */
  readonly governmentIssuedIdMasked: string;

  /** Secondary telephone number, or nothing. From `CUST-PHONE-NUM-2 PIC X(15)` at L16, narrowed to 13. */
  readonly phoneNumber2: string | null;

  /** Electronic funds transfer account. `ACSEFTCI PIC X(10)`, from `CUST-EFT-ACCOUNT-ID PIC X(10)` at L20. */
  readonly eftAccountId: string;

  /**
   * Whether this customer is the primary card holder, as one character.
   *
   * Assumptions: `ACSPFLGI PIC X(1)`, from `CUST-PRI-CARD-HOLDER-IND PIC X(01)` at
   * `app/cpy/CVCUS01Y.cpy` L21. Typed as `string` because the contract enumerates no values for it,
   * on the same reasoning given for the account's active status.
   */
  readonly primaryCardHolderIndicator: string;
}

/**
 * One row of an account's card cross-reference.
 *
 * Assumptions: this mirrors `CardXrefResponse` in `account-api.yaml`, three required members, and the
 * source record is `CARD-XREF-RECORD` at `app/cpy/CVACT03Y.cpy` L4 to L8, fifty bytes, with
 * `FILLER PIC X(14)` at L8 dropped as padding.
 *
 * Assumptions: it differs from the internal `CardXrefView` shape in `account-api.yaml` in exactly one
 * respect and deliberately -- it carries the card the row is FOR. A list of an account's cards cannot be
 * read without something to tell the rows apart, whereas that view's caller supplied the card and needs
 * no echo of it. That single difference is why the contract declares both shapes rather than one; only
 * this half is browser-facing, so only this half is declared here.
 */
export interface CardXrefResponse {
  /**
   * The card this row is for, masked to its last four digits.
   *
   * Trade-offs: a masked rendering is unavoidably ambiguous -- two cards on one account sharing their
   * last four digits render identically -- and the ambiguity is accepted because the alternative is a
   * list response that discloses a full primary account number per row. Sixteen characters are allowed
   * rather than four because a mask carries its own filler. The stored field is
   * `XREF-CARD-NUM PIC X(16)` at `app/cpy/CVACT03Y.cpy` L5, and the one shape in this folder carrying
   * the unmasked sixteen digits is the administrative card detail in `ui/src/api/cards.ts`, at an
   * address the service restricts to the administrative group.
   */
  readonly cardNumberMasked: string;

  /** The customer that card belongs to, as digits. `XREF-CUST-ID PIC 9(09)` at L6, `^[0-9]{1,9}$`. */
  readonly customerId: string;

  /** The account that card belongs to, as digits. `XREF-ACCT-ID PIC 9(11)` at L7 of that copybook. */
  readonly accountId: string;
}

/**
 * One page of an account's card cross-reference rows.
 *
 * Assumptions: the rows arrive in ascending card-number order and a full page holds at most seven of
 * them, the row count the card-list program fixes for its own map; the page is empty when the account
 * holds no card, which is a representable state and not an error.
 */
export type CardXrefPage = PageResponse<CardXrefResponse>;

/**
 * One submission of the account-update screen.
 *
 * Assumptions: this mirrors `AccountUpdateRequest` in `account-api.yaml`, and EVERY member is optional
 * because that schema declares no `required` list at all. That is not an oversight to tighten here: the
 * reference validates the presence of each field itself, field by field, and reports the first failure
 * it reaches on the screen -- so a submission with a field left empty is a case the service answers with
 * a field error, not a body a client should be unable to construct. Making a member mandatory here would
 * move a validation the service owns into the browser, where it would produce a different first message
 * than the reference does.
 *
 * Assumptions: every member is text at the width `app/cpy-bms/COACTUP.CPY` declares, including the five
 * money members, which are fifteen-character screen fields rather than the {@link Money} pattern. The
 * reference validates each as characters before it converts any, so an inbound amount is whatever the
 * user typed and not yet a well-formed decimal. This is the one place in this module where a monetary
 * value is deliberately NOT {@link Money}: applying that type would assert a pattern the caller has not
 * yet been told to satisfy, and would type away the malformed input the service exists to reject.
 *
 * Assumptions: the composite values arrive in PARTS -- three dates as year, month and day, the national
 * identifier as three groups, each telephone number as three groups -- because the map declares a
 * separate input field for each part and the reference validates them separately. Recombining them here
 * would discard which part a field error refers to.
 *
 * Refactoring Rationale: every optional member here -- and in every other shape in this module -- is
 * spelled `?: string` and never `?: string | undefined`. `ui/tsconfig.json` enables
 * `exactOptionalPropertyTypes`, whose entire purpose is to distinguish a member that is ABSENT from one
 * that is present holding `undefined`; adding `| undefined` re-admits the second and so turns that
 * setting off one member at a time. The distinction is not academic. A member present as `undefined` is
 * serialised by `JSON.stringify` as no member at all, so the two forms reach the service identically --
 * but only the absent form is what these shapes mean, and a client that assigned `undefined` from a
 * cleared control would compile while expressing a state no contract has.
 *
 * Trade-offs: a caller assembling a body or a query conditionally must SPREAD a member in rather than
 * assign `undefined` to it, which is more to write. Exactly one call site in the tree needed that --
 * the card browse in `ui/src/screens/cardList`, which omits the cursor on its opening read -- and the
 * friction there is the whole point of the setting: the compiler now enforces that the opening read
 * carries no cursor rather than a cursor whose value is nothing.
 *
 * Assumptions: this shape is INBOUND ONLY and is never a component of a response. The read shapes carry
 * only masked forms, which is why {@link CustomerDetail} has no counterpart to the identifier members;
 * the members that carry an identifier in the clear are declared separately on
 * {@link SensitiveAccountUpdateFields} rather than here, for the reasons recorded there.
 *
 * Refactoring Rationale: there is NO version or revision member. The concurrency token travels as a weak
 * entity tag in an `If-Match` header, which the contract declares required on the update operation, and
 * the value is the one the view operation returned in its `ETag`. This replaces the reference's own
 * optimistic check rather than inventing one: `app/cbl/COACTUPC.cbl` snapshots a complete pre-edit
 * before-image from L669 onward -- with the X-over-9 pairs at L671 to L683 that hold each number as
 * characters and redefine it for arithmetic -- and carries `WS-DATACHANGED-FLAG PIC X(1)` at L168 to
 * record whether the stored row moved while the user was typing. A stale tag is answered with HTTP 409,
 * carrying the message that condition names at L521 to L522, verbatim and including its two-word
 * spelling: `Record changed by some one else. Please review`.
 */
export interface AccountUpdateRequest {
  /** The account being edited. `ACCTSIDI PIC X(11)`, for `ACCT-ID PIC 9(11)`. */
  readonly accountId?: string;

  /** Active status as typed. `ACSTTUSI PIC X(1)`, for `ACCT-ACTIVE-STATUS PIC X(01)`. */
  readonly activeStatus?: string;

  // WHY : Assumptions: the five money members of this submission are fifteen characters each, the map's own field width, which is wider than any
  //       well-formed amount needs precisely so that a malformed entry can be received and reported
  //       rather than truncated into a different number.

  /** Credit limit as typed. `ACRDLIMI PIC X(15)`, for `ACCT-CREDIT-LIMIT`. */
  readonly creditLimit?: string;

  /** Cash advance limit as typed. `ACSHLIMI PIC X(15)`, for `ACCT-CASH-CREDIT-LIMIT`. */
  readonly cashCreditLimit?: string;

  /** Balance as typed. `ACURBALI PIC X(15)`, for `ACCT-CURR-BAL`. */
  readonly currentBalance?: string;

  /** Cycle credits as typed. `ACRCYCRI PIC X(15)`, for `ACCT-CURR-CYC-CREDIT`. */
  readonly currentCycleCredit?: string;

  /** Cycle debits as typed. `ACRCYDBI PIC X(15)`, for `ACCT-CURR-CYC-DEBIT`. */
  readonly currentCycleDebit?: string;

  // WHY : Assumptions: the three account dates each arrive in three parts, because the map declares
  //       a year, month and day field per date, so a client sends what
  //       the user typed into each. The whole-date forms these compose are `ACCT-OPEN-DATE`,
  //       `ACCT-EXPIRAION-DATE` and `ACCT-REISSUE-DATE` at `app/cpy/CVACT01Y.cpy` L10, L11 and L12 --
  //       the middle one carrying the baseline's `EXPIRAION` spelling that the target names
  //       `expirationDate`.

  /** Opening year as typed. `OPNYEARI PIC X(4)`. */
  readonly openDateYear?: string;

  /** Opening month as typed. `OPNMONI PIC X(2)`. */
  readonly openDateMonth?: string;

  /** Opening day as typed. `OPNDAYI PIC X(2)`. */
  readonly openDateDay?: string;

  /** Expiry year as typed. `EXPYEARI PIC X(4)`. */
  readonly expirationDateYear?: string;

  /** Expiry month as typed. `EXPMONI PIC X(2)`. */
  readonly expirationDateMonth?: string;

  /** Expiry day as typed. `EXPDAYI PIC X(2)`. */
  readonly expirationDateDay?: string;

  /** Reissue year as typed. `RISYEARI PIC X(4)`. */
  readonly reissueDateYear?: string;

  /** Reissue month as typed. `RISMONI PIC X(2)`. */
  readonly reissueDateMonth?: string;

  /** Reissue day as typed. `RISDAYI PIC X(2)`. */
  readonly reissueDateDay?: string;

  /** Disclosure group as typed. `AADDGRPI PIC X(10)`, for `ACCT-GROUP-ID PIC X(10)`. */
  readonly groupId?: string;

  /** The customer the account refers to. `ACSTNUMI PIC X(9)`, for `CUST-ID PIC 9(09)`. */
  readonly customerId?: string;

  /** Birth year as typed. `DOBYEARI PIC X(4)`, one part of `CUST-DOB-YYYY-MM-DD`. */
  readonly dateOfBirthYear?: string;

  /** Birth month as typed. `DOBMONI PIC X(2)`. */
  readonly dateOfBirthMonth?: string;

  /** Birth day as typed. `DOBDAYI PIC X(2)`. */
  readonly dateOfBirthDay?: string;

  /** Credit score as typed. `ACSTFCOI PIC X(3)`, for `CUST-FICO-CREDIT-SCORE PIC 9(03)`. */
  readonly ficoCreditScore?: string;

  /** Given name as typed. `ACSFNAMI PIC X(25)`. */
  readonly firstName?: string;

  /** Middle name as typed; matches the nullable stored column. `ACSMNAMI PIC X(25)`. */
  readonly middleName?: string;

  /** Family name as typed. `ACSLNAMI PIC X(25)`. */
  readonly lastName?: string;

  /** First address line as typed. `ACSADL1I PIC X(50)`. */
  readonly addressLine1?: string;

  /** Second address line as typed; matches the nullable stored column. `ACSADL2I PIC X(50)`. */
  readonly addressLine2?: string;

  /** City as typed, stored as `CUST-ADDR-LINE-3 PIC X(50)`. `ACSCITYI PIC X(50)`. */
  readonly city?: string;

  /**
   * State code as typed. `ACSSTTEI PIC X(2)`.
   *
   * Assumptions: a code outside the seeded reference data is refused by the service as a field error,
   * so this member carries what the user typed rather than a value already known to be valid.
   */
  readonly stateCode?: string;

  /** Country code as typed. `ACSCTRYI PIC X(3)`. */
  readonly countryCode?: string;

  /** Postal code as typed, at the SCREEN width of five against a stored ten. `ACSZIPCI PIC X(5)`. */
  readonly zipCode?: string;

  // WHY : Assumptions: the two telephone numbers each arrive in the three groups the map asks for,
  //       and the parts compose `CUST-PHONE-NUM-1` and `CUST-PHONE-NUM-2`, both
  //       `PIC X(15)` at `app/cpy/CVCUS01Y.cpy` L15 and L16. The reference validates an area code
  //       against seeded reference data, which is why the area code is its own field and not a slice
  //       of a longer one.

  /** First number's area code as typed. `ACTPHA1I PIC X(3)`. */
  readonly phone1AreaCode?: string;

  /** First number's exchange prefix as typed. `ACTPHB1I PIC X(3)`. */
  readonly phone1Prefix?: string;

  /** First number's line digits as typed. `ACTPHC1I PIC X(4)`. */
  readonly phone1LineNumber?: string;

  /** Second number's area code as typed. `ACTPHA2I PIC X(3)`. */
  readonly phone2AreaCode?: string;

  /** Second number's exchange prefix as typed. `ACTPHB2I PIC X(3)`. */
  readonly phone2Prefix?: string;

  /** Second number's line digits as typed. `ACTPHC2I PIC X(4)`. */
  readonly phone2LineNumber?: string;

  /** Electronic funds transfer account as typed. `ACSEFTCI PIC X(10)`. */
  readonly eftAccountId?: string;

  /** Primary card-holder indicator as typed. `ACSPFLGI PIC X(1)`. */
  readonly primaryCardHolderIndicator?: string;
}

/**
 * The four members of an account-update submission that carry a personal identifier IN THE CLEAR.
 *
 * Refactoring Rationale: these four are declared as their own shape rather than as members of
 * {@link AccountUpdateRequest}, and the split is a containment boundary rather than a tidiness
 * preference. `AccountUpdateRequest` is the general, reusable submission shape: a screen builds one, a
 * test fixture builds one, a retry buffer or a draft-restore feature would hold one, and anything a
 * caller holds it is free to log, serialise into local storage or attach to an error report. Three
 * digits, two digits, four digits and a twenty-character identifier are exactly the values that must
 * never take any of those paths, so they are not members of the shape a caller passes around. A caller
 * that needs them names {@link SensitiveAccountUpdateRequest} explicitly, which is a decision visible at
 * its declaration site and greppable across the tree.
 *
 * Alternatives Considered: leaving all four on the general shape, which is what the wire body looks like
 * and therefore the shorter mirror of the contract. Rejected because the contract's own body being flat
 * says nothing about how a client should HOLD the values -- the flat body is reproduced exactly by the
 * intersection below, so nothing about the request changes -- and a type that carries a national
 * identifier without saying so is the shape most likely to end up in a log line, since nothing in its
 * name or its use warns the author. Also considered: omitting the four entirely and having the browser
 * send only masked values. Rejected outright as a parity break: `app/cpy-bms/COACTUP.CPY` declares
 * `ACTSSN1I PIC X(3)`, `ACTSSN2I PIC X(2)`, `ACTSSN3I PIC X(4)` and `ACSGOVTI PIC X(20)` as INPUT
 * fields, so the reference screen genuinely sets these values and a migration that could not set them
 * would drop a function of the screen.
 *
 * Assumptions: WRITE-ONLY, in both directions of that phrase. No read operation in `account-api.yaml`
 * returns any of these members -- {@link CustomerDetail} carries `ssnMasked` and
 * `governmentIssuedIdMasked` and no clear counterpart -- and the service stores both encrypted, so a
 * value assigned here is the only place in the browser it exists. It follows that a screen must clear
 * these fields after a submission resolves rather than retaining them to prefill a retry.
 *
 * Assumptions: the national identifier arrives in three PARTS because the map declares three input
 * fields and the reference validates them separately, composing `CUST-SSN PIC 9(09)` at
 * `app/cpy/CVCUS01Y.cpy` L17. Recombining them here would discard which part a field error refers to,
 * which is the same reason the dates and telephone numbers on the general shape arrive in parts.
 *
 * Trade-offs: nothing in TypeScript enforces the non-logging obligation these members carry -- a type
 * cannot prevent a `console.log`. What the split buys is that the obligation attaches to a NAMED shape
 * a reviewer can search for, instead of being one of forty-odd indistinguishable members of the shape
 * every account screen already holds.
 */
export interface SensitiveAccountUpdateFields {
  /**
   * First three digits of the national identifier, as typed. `ACTSSN1I PIC X(3)`.
   *
   * Assumptions: in the clear, because an edit has to be able to set the value. It must not be logged,
   * persisted in the browser, or included in an error report.
   */
  readonly ssnPart1?: string;

  /** Middle two digits of the national identifier, as typed. `ACTSSN2I PIC X(2)`. Never logged. */
  readonly ssnPart2?: string;

  /** Last four digits of the national identifier, as typed. `ACTSSN3I PIC X(4)`. Never logged. */
  readonly ssnPart3?: string;

  /**
   * The government-issued identifier as typed, in the clear.
   *
   * Assumptions: `ACSGOVTI PIC X(20)`, for `CUST-GOVT-ISSUED-ID PIC X(20)` at `app/cpy/CVCUS01Y.cpy`
   * L18. Write-only on the same terms as the three parts above: it is returned only as
   * `governmentIssuedIdMasked`, so a value here exists nowhere else in the browser and must not be
   * logged, cached or retained after the submission resolves.
   */
  readonly governmentIssuedId?: string;
}

/**
 * The submission body of the account-update operation, personal identifiers included.
 *
 * Assumptions: an intersection and not a third declaration, so the WIRE body is byte-for-byte the flat
 * shape `AccountUpdateRequest` in `account-api.yaml` declares -- the split above is about what a client
 * holds and names, never about what it sends. This is the type the one call that submits an update
 * takes; every other holder of update state uses {@link AccountUpdateRequest} and so cannot carry a
 * clear identifier at all.
 */
export type SensitiveAccountUpdateRequest = AccountUpdateRequest & SensitiveAccountUpdateFields;

/**
 * What the account-update operation returns.
 *
 * Assumptions: this mirrors `AccountUpdateResponse` in `account-api.yaml`, whose six members are all
 * required. It returns the stored state ALONGSIDE any field errors rather than one or the other, which
 * is what lets the screen redisplay the row it failed to write without a second read -- the behaviour
 * the reference produces by redisplaying its own map.
 *
 * Assumptions: `fieldErrors` is the whole mechanism by which a failing field is marked, and it is
 * load-bearing rather than decorative. The reference marks a field by moving a colour attribute into it,
 * and additionally an asterisk when the field is blank, through the templated block at
 * `app/cpy/CSSETATY.cpy` L17 to L27 -- but that block is gated on `AND CDEMO-PGM-REENTER` at L20, the
 * re-entry flag of `CDEMO-PGM-CONTEXT` at `app/cpy/COCOM01Y.cpy` L29 to L31. A stateless handler has no
 * first-entry-versus-re-entry distinction to gate on, so the marking cannot be inferred from a remembered
 * turn and must be carried in the body. That is why the array is present on a success as well, empty.
 */
export interface AccountUpdateResponse {
  /** The account that was written, echoed as digits. `ACCT-ID PIC 9(11)` at `app/cpy/CVACT01Y.cpy` L5. */
  readonly accountId: string;

  /**
   * The screen's information line, or nothing.
   *
   * Assumptions: forty characters, the width `WS-INFO-MSG` declares at `app/cbl/COACTUPC.cbl` L463.
   * Unlike the view operation, this one does fill it -- it is where a successful update reports itself.
   */
  readonly informationMessage: string | null;

  /**
   * The screen's message line, or nothing.
   *
   * Assumptions: seventy-five characters, the width `WS-RETURN-MSG` declares at `app/cbl/COACTUPC.cbl`
   * L479, matching the two communication-area carriers. This is the channel that carries the conflict
   * sentence when a stale entity tag is refused.
   */
  readonly returnMessage: string | null;

  /**
   * One entry per refused field, empty when nothing was refused.
   *
   * Assumptions: always present, so a screen tests the length rather than the key. Each entry pairs the
   * logical field name with the verbatim sentence the reference raises for it, at the seventy-five
   * characters that sentence's carrier declares.
   */
  readonly fieldErrors: readonly FieldError[];

  /** The account's stored state after the attempt, whether or not it changed. */
  readonly account: AccountDetail;

  /** The customer's stored state after the attempt, at screen widths. */
  readonly customer: CustomerDetail;
}

// ---------------------------------------------------------------------------
// card-api.yaml -- the card browse, detail, update and administrative shapes
// ---------------------------------------------------------------------------
// WHY : Refactoring Rationale: these were declared in ui/src/api/cards.ts and are declared here now.
//       A screen binds to a wire shape and its client module binds to the same one, so a shape declared
//       inside the module that calls it is owned by its first caller rather than by the contract it
//       describes -- and a second caller then either imports from that module, which makes one client
//       depend on another, or restates the shape, which is how two definitions of one contract come to
//       drift apart. Declaring every contract's browser-facing shapes in this one module gives each
//       shape exactly one definition, in a file the compiler erases in full, and each client module
//       re-exports what it always exported so no consumer import had to move.

/**
 * Summary row returned by the card browse endpoint.
 *
 * Refactoring Rationale: this carries the row's masked rendering, its account and its status, and
 * NOT the embossed name or the expiration date. An earlier revision of this interface declared those
 * two as well, and the browse it types has no such columns to fill them from: the baseline list row
 * is twenty-eight characters — an eleven-character account number, a sixteen-character card number
 * and a one-character status, declared at `app/cbl/COCRDLIC.cbl:258-260` — and `card-api.yaml`
 * publishes exactly those three. Two fields typed as present and never sent would have rendered as
 * blank columns, and a list disclosing more of each row than the screen it replaces disclosed is a
 * widening no requirement asks for. Both fields remain on the detail shape, which is where the update
 * screen reads them.
 *
 * Refactoring Rationale: a FOURTH member, `key`, carries the opaque selector that addresses this
 * row's card. It was withdrawn once, on the ground that "publishing an addressable value per row would
 * put back exactly what masking the rendering exists to prevent, since a caller could then lift an
 * addressable identity for every row of a page at once". That objection is answered rather than
 * overruled, and on two independent grounds. Masking exists so that a list response discloses no card
 * NUMBER; the selector is sealed by the service and carries no digit of one, so lifting every selector
 * on a page yields every row's address and not one digit of any card. And the withdrawal removed no
 * exposure, it relocated one: with no address on the row, every single-card route had to carry the
 * number itself, into browser history and into access-log objects the load balancer and the
 * distribution both write verbatim before any application code runs. What further bounds the selector
 * is that it is bound by the service to the resource, to the authenticated subject and to the query
 * scope, and that it expires — so what can be lifted from a page addresses, for this caller and for a
 * bounded time, exactly the rows this caller was already shown.
 */
export interface CardSummary {
  readonly key: string;
  readonly displayCardNumber: string;
  readonly accountId: string;
  readonly activeStatus: 'Y' | 'N';
}

/**
 * Card-detail representation returned for one card.
 *
 * Assumptions: this extends the summary rather than restating it, so the selector, the masked
 * rendering, the account and the status are described once. The three members declared here are the
 * ones the detail carries and the list does not — two editable attributes and the concurrency token —
 * which together with the four inherited ones are exactly the seven `CardDetailCore` declares in
 * `card-api.yaml`.
 *
 * Assumptions: the selector IS inherited rather than omitted, because the contract's `CardDetailCore`
 * declares `key` as one of its seven required properties and the Java record carries it as its first
 * component. A detail read is addressed BY a selector, so it would be defensible for the response to
 * leave the caller holding the one it already had; the contract does not take that option, and it is
 * right not to, because an update MINTS A FRESH selector — the value the caller sent is spent once the
 * version moves, and omitting the new one would leave the update screen unable to re-read the row it
 * had just written.
 */
export interface CardDetail extends CardSummary {
  readonly embossedName: string;
  readonly expirationDate: string;
  readonly version: number;
}

/**
 * Card-detail representation returned for one card by the administrative operation.
 *
 * Assumptions: this extends the ordinary detail with the one member that distinguishes it, the
 * UNMASKED card number, matching `AdminCardDetail` in `card-api.yaml` -- which is `CardDetailCore`
 * plus a sixteen-digit `cardNumber`. The two operations differ in what they are permitted to render
 * and never in what they may change, which is why no update counterpart exists.
 *
 * Trade-offs: this is the one shape in the SPA that carries a full primary account number, and it is
 * typed here rather than left as an untyped response so that the disclosure is visible in the type
 * system instead of only in a runtime body. The offsetting risk is that a screen could render it
 * where the ordinary detail belonged; what bounds that is the address -- the value is reachable only
 * through `/api/v1/admin/cards`, which `card-service`'s authority table restricts to the
 * administrative group, so an ordinary caller receives 403 rather than a number.
 */
export interface AdminCardDetail extends CardDetail {
  readonly cardNumber: string;
}

/**
 * Fields the card update operation permits a browser to change.
 *
 * Refactoring Rationale: the expiry travels as a month and a year and NOT as a whole date, matching
 * `CardUpdateRequest` in `card-api.yaml`. The baseline's update screen edits only those two parts: its
 * day input is rendered non-display at `app/cbl/COCRDUPC.cbl:1285`, is redisplayed from the pre-edit
 * snapshot at `:1123` rather than from anything entered, and has no validation paragraph at all. An
 * earlier revision of this interface sent one ISO date, which let a browser set an expiry day the
 * baseline does not expose — and the day the stored date keeps is now supplied by the service from the
 * row's own current value, so no browser can influence it.
 *
 * Trade-offs: a caller therefore cannot send a detail response straight back as an update, because the
 * detail carries the whole stored date. That asymmetry matches the screens, where the detail map
 * declares `EXPMONI` and `EXPYEARI` and no day field while the stored record holds a whole date.
 */
export interface CardUpdateRequest {
  readonly embossedName: string;
  readonly expirationMonth: string;
  readonly expirationYear: string;
  readonly activeStatus: 'Y' | 'N';
  readonly version: number;
}

/**
 * Criteria a card browse request may narrow by.
 *
 * Assumptions: every member is optional and the object itself may be omitted, because a browse with
 * no criteria is the first page of the unfiltered set, which is the screen's initial state. They are
 * grouped into one object rather than passed positionally so that a caller adding a filter cannot
 * silently supply it in the cursor's position.
 *
 * Refactoring Rationale: a `cardNumber` member is withdrawn, and with it the only card filter this
 * browse had. The contract no longer declares a card-number query parameter, because a query string is
 * persisted verbatim by the load balancer's mandatory access log on exactly the same terms as a path
 * segment. Reaching one card by its number is `lookupCard`, which sends the number in a body.
 */
export interface CardListQuery {
  readonly accountId?: string;
  readonly cursor?: string;
  readonly direction?: PageDirection;
}

// ---------------------------------------------------------------------------
// auth-api.yaml -- the sign-on exchange and the four user-maintenance shapes
// ---------------------------------------------------------------------------
// WHY : Assumptions: this section carries the one password member in the whole module, on
//       SignOnRequest, and it is the only shape in this file that may. A credential belongs to the
//       exchange that sends it and to nothing else, so no other declaration here names one -- the
//       token refresh and the challenge answer carry a token and a session handle instead.

/**
 * The two user types the baseline admits, and the two Cognito groups they map to.
 *
 * Assumptions: exactly two members, 'A' and 'U', transcribed from `SEC-USR-TYPE` at
 * `app/cpy/CSUSR01Y.cpy` L22. The `auth.users` check constraint admits the same two, so a third value
 * is refused by the database as well as by the contract.
 */
export type UserType = 'A' | 'U';

export interface SignOnRequest {
  readonly userId: string;
  readonly password: string;
}

/**
 * A completed sign-on, carrying the token set the SPA presents on every later request.
 *
 * Assumptions: `refreshToken` is nullable because the identity provider omits it on a renewal, which
 * is the flow that consumed the previous one. A caller that stored null over a held refresh token
 * would sign the user out at the next expiry, so a null must be treated as "keep what you have"
 * rather than as "the token was revoked".
 */
export interface SignOnTokens {
  readonly outcome: 'AUTHENTICATED';
  readonly userId: string;
  readonly accessToken: string;
  readonly idToken: string;
  readonly refreshToken: string | null;
  readonly tokenType: string;
  readonly expiresIn: number;
}

/**
 * A sign-on the provider will not complete until the credential is changed.
 *
 * Assumptions: `session` is an opaque continuation value and is passed back unread. It is a
 * credential-equivalent for the length of the exchange, which is why it travels in a body on the way
 * back as well as on the way out.
 */
export interface SignOnChallenge {
  readonly outcome: 'CHALLENGE';
  readonly challengeName: 'NEW_PASSWORD_REQUIRED';
  readonly session: string;
  readonly userId: string;
}

/**
 * Which of the two sign-on outcomes occurred.
 *
 * Assumptions: the union is discriminated on `outcome`, which the contract declares as a constant on
 * each member and names as the discriminator property. Deriving the branch from the presence of
 * `accessToken` instead would work today and would break silently the moment a third outcome carried
 * one, whereas an unhandled constant is a compile error.
 */
export type SignOnResult = SignOnTokens | SignOnChallenge;

export interface TokenRefreshRequest {
  readonly userId: string;
  readonly refreshToken: string;
}

export interface SignOnChallengeRequest {
  readonly userId: string;
  readonly session: string;
  readonly newPassword: string;
}

/**
 * One row of the user browse.
 *
 * Assumptions: four members and no credential among them. The baseline record carries an
 * eight-character plaintext password at `app/cpy/CSUSR01Y.cpy` L21; the migrated system does not carry
 * that field at all, so there is nothing here to omit rather than a member deliberately withheld.
 */
export interface UserSummary {
  readonly userId: string;
  readonly firstName: string;
  readonly lastName: string;
  readonly userType: UserType;
}

/**
 * One user as the administration screens render it.
 *
 * Assumptions: `cognitoSub` is an OUTPUT and never an input. It is minted by the identity provider
 * when the account is provisioned, so a creation request that supplied one would be asserting an
 * identity it cannot have created -- which is why {@link CreateUserRequest} below declares four members
 * and this shape declares five.
 */
export interface UserResponse extends UserSummary {
  readonly cognitoSub: string;
}

/**
 * The fields a user creation accepts.
 *
 * Assumptions: FOUR members. No credential is among them, because this service creates none: the
 * identity provider is asked to provision the account and it mints the initial credential itself. No
 * subject is among them either, for the reason recorded on {@link UserResponse}.
 */
export interface CreateUserRequest {
  readonly firstName: string;
  readonly lastName: string;
  readonly userId: string;
  readonly userType: UserType;
}

/**
 * The fields a user update accepts.
 *
 * Assumptions: three members, and the identifier is not one of them. It addresses the row from the
 * target, so admitting it in the body as well would create a request whose two halves could disagree
 * about which user is being changed.
 */
export interface UpdateUserRequest {
  readonly firstName: string;
  readonly lastName: string;
  readonly userType: UserType;
}

export interface UserListQuery {
  readonly cursor?: string;
  readonly direction?: PageDirection;
}

// ---------------------------------------------------------------------------
// transaction-api.yaml -- the ledger browse, detail, add and bill-payment shapes
// ---------------------------------------------------------------------------
// WHY : Assumptions: two of these are UNION outcomes rather than single shapes, and the unions are the
//       contract's own -- an add and a bill payment each answer either a preview to confirm or the
//       committed record, which is the reference's two-turn confirmation expressed in one response
//       type. A client discriminates on the member the union names rather than on the status code.

/**
 * One row of the transaction browse.
 *
 * Assumptions: FOUR members, matching `TransactionSummary` in the contract, and no card number among
 * them. The baseline list row shows the identifier, the originating timestamp, the description and the
 * amount; adding the card would disclose more per row than the screen it replaces disclosed, on a
 * response that returns a whole page at once.
 */
export interface TransactionSummary {
  readonly transactionId: string;
  readonly originTimestamp: Timestamp26;
  readonly description: string;
  readonly amount: string;
}

/**
 * One transaction in full, as the detail screen renders it.
 *
 * Assumptions: `cardNumber` is the MASKED rendering and `processTimestamp` is nullable, both exactly
 * as the contract declares. A transaction that has been accepted but not yet posted carries no
 * processing timestamp, so null here is a real state and not a missing value.
 */
export interface TransactionDetail {
  readonly transactionId: string;
  readonly typeCode: string;
  readonly categoryCode: string;
  readonly source: string;
  readonly description: string;
  readonly amount: string;
  readonly merchantId: string;
  readonly merchantName: string;
  readonly merchantCity: string;
  readonly merchantZip: string;
  readonly cardNumber: string;
  readonly originTimestamp: Timestamp26;
  readonly processTimestamp: string | null;
  readonly returnMessage: string | null;
}

/**
 * The fields the create operation accepts.
 *
 * Assumptions: `accountId` and `cardNumber` are both optional and exactly one identifies the card to
 * post against, which is why neither is required by the contract. `confirmation` is optional too: its
 * absence is the unconfirmed first pass that answers 200 with nothing written.
 */
export interface TransactionCreateRequest {
  readonly accountId?: string;
  readonly cardNumber?: string;
  readonly typeCode: string;
  readonly categoryCode: string;
  readonly source: string;
  readonly description: string;
  readonly amount: string;
  readonly originDate: string;
  readonly processDate: string;
  readonly merchantId: string;
  readonly merchantName: string;
  readonly merchantCity: string;
  readonly merchantZip: string;
  readonly confirmation?: string;
}

/**
 * The body a user creation answers with, naming where the account's first credential was published.
 *
 * Refactoring Rationale: the member is the managed-secret entry's NAME and not the credential. A
 * credential in a response body is retained by every proxy log and browser history on the path, and the
 * migration forbids a credential reaching a place with no audit trail; a resource name is not a secret,
 * so it may travel here. Trade-offs: collecting the value needs a grant on the secret store, which a
 * browser session does not hold, so onboarding ends with one privileged read.
 */
export interface CreatedUserResponse extends UserResponse {
  readonly credentialSecretName: string;
}

export interface TransactionAddPreview {
  readonly amount: string;
  readonly written: boolean;
  readonly returnMessage: string | null;
}

export interface TransactionCreated {
  readonly transactionId: string;
  readonly amount: string;
  readonly returnMessage: string;
}

/**
 * Which of the two create outcomes occurred, discriminated so neither can be read as the other.
 *
 * Assumptions: the discriminant is derived from the HTTP status and not from the `written` member of
 * the preview shape. Both are present in the 200 body, and the status is the one the contract makes
 * normative -- 201 for a written record -- so deriving from it keeps this client agreeing with the
 * contract rather than with one property of one body.
 */
export type TransactionAddOutcome =
  | { readonly outcome: 'PREVIEWED'; readonly preview: TransactionAddPreview }
  | { readonly outcome: 'CREATED'; readonly created: TransactionCreated };

export interface BillPaymentRequest {
  readonly accountId: string;
  readonly confirmation?: string;
}

export interface BillPaymentPreview {
  readonly accountId: string;
  /**
   * The balance a confirmed request would pay, NULL on the declined turn.
   *
   * Refactoring Rationale: required and nullable rather than optional. `app/cbl/COBIL00C.cbl` L178 to L181
   * answers a declined confirmation by clearing the screen and reaching no account read, so there is no
   * figure to report -- but `BillPaymentPreview.cleared` constructs the component as `null` and the
   * services pin `default-property-inclusion: always`, so the member is WRITTEN carrying null. It was
   * previously optional AND non-nullable, which described an omission the service cannot produce while
   * promising a decimal string on the one turn that answers null.
   */
  readonly payableBalance: string | null;
  readonly paid: boolean;
  readonly returnMessage: string | null;
}

export interface BillPaymentResponse {
  readonly transactionId: string;
  readonly accountId: string;
  readonly currentBalance: string;
  readonly paid: boolean;
  readonly returnMessage: string | null;
}

export type BillPaymentOutcome =
  | { readonly outcome: 'PREVIEWED'; readonly preview: BillPaymentPreview }
  | { readonly outcome: 'PAID'; readonly payment: BillPaymentResponse };

/**
 * Criteria a transaction browse may narrow by.
 *
 * Assumptions: the contract's only filter is an exact transaction identifier, so no free-text or
 * range criterion is offered here. The baseline browse screen accepts a starting identifier and
 * nothing else, and offering a criterion the service does not implement would fail at the edge with
 * 400 against a field the user was invited to fill.
 */
export interface TransactionListQuery {
  readonly transactionIdFilter?: string;
  readonly cursor?: string;
  readonly direction?: PageDirection;
}

// ---------------------------------------------------------------------------
// reference-api.yaml -- transaction types and categories, disclosure rates,
// the three seeded lookup tables, date evaluation and batch maintenance
// ---------------------------------------------------------------------------
// WHY : Assumptions: this is the largest section because the reference contract owns the most schemas,
//       and its five closed literal domains -- the phone-area-code class, the two date masks, the date
//       feedback codes, the maintenance action types and their outcome states -- are declared here as
//       unions rather than restated as `string` at each use. A union is what makes a value outside the
//       domain a compile error instead of a request the service answers with a field error.

/**
 * Which class of North American area code a row records.
 *
 * Assumptions: two members, 'G' for a geographic code and 'E' for a non-geographic one, transcribed
 * from the two allow-lists in `app/cpy/CSLKPCDY.cpy`. Address validation admits both but treats them
 * differently, which is why the class is published rather than filtered out at the source.
 */
export type PhoneAreaCodeClass = 'G' | 'E';

export interface TransactionType {
  readonly typeCd: string;
  readonly description: string;
  readonly version: number;
}

export interface TransactionTypeCreateRequest {
  readonly typeCd: string;
  readonly description: string;
}

/**
 * The fields a transaction-type replace accepts.
 *
 * Assumptions: the code is not among them. It addresses the row from the target, so admitting it in
 * the body as well would create a request whose two halves could disagree about which row changes.
 */
export interface TransactionTypeReplaceRequest {
  readonly description: string;
  readonly version: number;
}

export interface TransactionCategory {
  readonly typeCd: string;
  readonly catCd: string;
  readonly description: string;
  readonly version: number;
}

export interface TransactionCategoryCreateRequest {
  readonly typeCd: string;
  readonly catCd: string;
  readonly description: string;
}

export interface TransactionCategoryReplaceRequest {
  readonly description: string;
  readonly version: number;
}

/**
 * One disclosure-group interest rate, and which group actually supplied it.
 *
 * Assumptions: three members describe the fallback rather than one, and that is the point of the
 * shape. `app/cbl/CBACT04C.cbl` L415 to L441 falls back to the group literally named `DEFAULT` when the
 * account's own group has no row, and a response reporting only the rate would leave a caller unable to
 * tell a configured rate from a defaulted one. `requestedAcctGroupId`, `appliedAcctGroupId` and
 * `defaultGroupApplied` make that distinction explicit.
 */
export interface DisclosureGroupRate {
  readonly requestedAcctGroupId: string;
  readonly appliedAcctGroupId: string;
  readonly tranTypeCd: string;
  readonly tranCatCd: string;
  readonly interestRate: string;
  readonly defaultGroupApplied: boolean;
}

export interface UsPhoneAreaCode {
  readonly areaCd: string;
  readonly codeClass: PhoneAreaCodeClass;
}

export interface UsState {
  readonly stateCd: string;
}

export interface UsStateZipPrefix {
  readonly stateZipCd: string;
}

/**
 * Which mask a date is being evaluated against.
 *
 * Assumptions: the two published forms are the hyphenated and the compact one, and the contract
 * accepts any string of up to ten characters for the parameter while defaulting it to the hyphenated
 * form. The union here names the two the service supports, so a screen cannot offer a third by
 * accident, and the parameter type below widens to string for exactly the case where a caller is
 * echoing a mask it received.
 */
export type DateMask = 'YYYY-MM-DD' | 'YYYYMMDD';

/**
 * Which specific defect a date evaluation found, or that it found none.
 *
 * Assumptions: ten members, transcribed from the feedback codes `app/cbl/CSUTLDTC.cbl` and its two
 * companion copybooks distinguish. They are carried across individually rather than collapsed into a
 * boolean because the baseline's reply is a structured triple -- a severity, a message number and
 * message text -- and a per-field error must be able to name which part of a date was wrong.
 */
export type DateFeedbackCode =
  | 'INVALID_DATE'
  | 'INSUFFICIENT_DATA'
  | 'BAD_DATE_VALUE'
  | 'INVALID_ERA'
  | 'UNSUPP_RANGE'
  | 'INVALID_MONTH'
  | 'BAD_PIC_STRING'
  | 'NON_NUMERIC_DATA'
  | 'YEAR_IN_ERA_ZERO'
  | 'OTHER';

export interface DateEvaluationResult {
  readonly feedbackCode: DateFeedbackCode;
  readonly severity: number;
  readonly messageNumber: number;
  readonly verdict: string;
  readonly date: string;
  readonly mask: string;
}

export type MaintenanceActionType = 'INSERT' | 'UPDATE' | 'DELETE';

/**
 * One entry of a batch reference update.
 *
 * Assumptions: `description` is nullable because a delete entry carries none. Requiring it would make
 * a caller invent a value for a row it is removing, and the service would then have to ignore it.
 */
export interface MaintenanceAction {
  readonly action: MaintenanceActionType;
  readonly typeCd: string;
  readonly description?: string | null;
}

export interface MaintenanceActionBatchRequest {
  readonly actions: readonly MaintenanceAction[];
}

export type MaintenanceActionOutcomeState = 'APPLIED' | 'NO_ROWS_FOUND' | 'FAILED';

/**
 * The outcome of one batch maintenance entry, positioned so it can be matched to its request entry.
 *
 * Assumptions: `position` is zero-based and is echoed rather than inferred from array order, so a
 * caller can match an outcome to its entry even if a future service answered out of order.
 */
export interface MaintenanceActionOutcome {
  readonly position: number;
  readonly action: MaintenanceActionType;
  readonly typeCd: string;
  readonly outcome: MaintenanceActionOutcomeState;
  readonly applied: boolean;
  readonly message: string;
}

/**
 * The outcome of a whole batch, with the aggregate return code the baseline job would have set.
 *
 * Assumptions: `returnCode` is 0 or 4 and 4 is a WARNING rather than a failure, matching the
 * mainframe condition-code convention the reference batch program uses: a row that matched nothing is
 * a soft outcome, not an error. A caller treating 4 as a failure would report a successful run as
 * broken.
 */
export interface MaintenanceActionBatchResponse {
  readonly outcomes: readonly MaintenanceActionOutcome[];
  readonly returnCode: number;
}

export interface ReferenceListQuery {
  readonly typeCode?: string;
  readonly description?: string;
  readonly cursor?: string;
  readonly direction?: PageDirection;
}

export interface LookupListQuery {
  readonly cursor?: string;
  readonly direction?: PageDirection;
}

export interface PhoneAreaCodeListQuery extends LookupListQuery {
  readonly codeClass?: PhoneAreaCodeClass;
}

// ---------------------------------------------------------------------------
// authorization-api.yaml -- the pending-authorization summary, detail and the
// fraud-marking exchange
// ---------------------------------------------------------------------------
// WHY : Assumptions: three of the four literal domains here are closed by the reference's own condition
//       names rather than by a choice made in the migration -- the approval status, the four match
//       statuses at app/app-authorization-ims-db2-mq/cpy/CIPAUDTY.cpy L46 to L49, and the two fraud
//       tags at L51 and L52. Declaring them as unions is what stops a screen rendering a status the
//       reference cannot produce.

export type ApprovalStatus = 'A' | 'D';

/**
 * How an authorization matched against posted activity.
 *
 * Assumptions: four members, which are the values the `authorization.pending_auth_detail` check
 * constraint admits: pending, declined, expired and matched.
 */
export type MatchStatus = 'P' | 'D' | 'E' | 'M';

/**
 * The fraud tag a row carries, used nullably wherever a row may be untagged.
 *
 * Assumptions: TWO members, and untagged is expressed by `null` rather than by a third member. The two
 * are the reference's own condition names on `PA-AUTH-FRAUD`, which
 * `app/app-authorization-ims-db2-mq/cpy/CIPAUDTY.cpy` declares as a one-character field at L50 with
 * condition names for the confirmed and removed states at L51 and L52. There is no third condition name
 * there, so an untagged authorization satisfies neither and is an ABSENCE rather than a value.
 *
 * Refactoring Rationale: an earlier revision admitted a single space as a third member, because the
 * stored column admits one -- a blank is what the extract load lands for a segment nobody has tagged.
 * Admitting it here made the unmarked state have TWO spellings on the browser side, so every screen had
 * to test for both, and a screen that tested for only one of them rendered an untagged authorization as
 * tagged. That is a reading a fraud indicator must never get wrong. The blank is now normalised to null
 * at the service boundary, in the compact constructor of
 * `services/authorization-service/src/main/java/com/carddemo/authorization/dto/PendingAuthDetailView.java`,
 * and `authorization-api.yaml` publishes the enum as the two tags plus null -- so this union is the
 * whole domain a response can carry rather than a narrowing of it.
 *
 * Alternatives Considered: keeping the wider union and having each screen collapse the blank as it
 * rendered. Rejected because it leaves one normalisation to be repeated correctly at every render site,
 * and the site that forgets it is indistinguishable from the sites that did not until a blank row
 * appears. Normalising once, where the value leaves the service, makes the blank unreachable instead of
 * merely handled.
 */
export type AuthFraudFlag = 'F' | 'R';

/**
 * The fraud transition a reviewer's request asks for.
 *
 * Assumptions: the same two members as {@link AuthFraudFlag} and a DISTINCT type, and the distinction is
 * nullability rather than membership. A response may report that a row carries no tag, so the state is
 * read as `AuthFraudFlag | null`; a request must always say which transition it wants, so this type is
 * never used nullably. Collapsing the two into one alias would let a request be written that asks for
 * nothing, which the operation has no meaning for.
 *
 * Assumptions: the two values are the reference's own -- `app/app-authorization-ims-db2-mq/cbl/COPAUS2C.cbl`
 * marks a message as fraud and withdraws that marking, which is why a withdrawal is a transition to be
 * requested rather than the absence of a request.
 */
export type FraudAction = 'F' | 'R';

/**
 * The account-level header the list operation renders above its rows.
 *
 * Assumptions: the five account-status members are five discrete members rather than an array,
 * matching `PA-ACCOUNT-STATUS PIC X(02) OCCURS 5 TIMES` at
 * `app/app-authorization-ims-db2-mq/cpy/CIPAUSMY.cpy` L22 as the schema declares it. The fixed arity
 * of five is part of the contract, and an array would admit a sixth.
 *
 * Refactoring Rationale: ⚠️ the four CUSTOMER display members -- `customerName`, `addressLine1`,
 * `addressLine2` and `phoneNumber1` -- were absent from this shape while `PendingAuthSummaryView`
 * returned them and `authorization-api.yaml` published all four, so the header this interface exists to
 * describe could not be rendered from it at all: the reference screen shows the cardholder's name and
 * address beside the amounts (`app/app-authorization-ims-db2-mq/cpy-bms/COPAU00.cpy` declares `CNAMEI`,
 * `ADDR001I`, `ADDR002I` and `PHONE1I`, composed at `cbl/COPAUS0C.cbl` L757 to L779), and a screen
 * author reading this type would have concluded the service did not send them.
 *
 * Assumptions: every member here is PRESENT and ten of them are nullable, so nothing in this shape is
 * optional. The service serialises this header from a record, every service resolves
 * `spring.jackson.default-property-inclusion: always` -- declared once for the fleet in
 * `services/common-lib/src/main/resources/carddemo-common-defaults.yml` -- and no service narrows it, so
 * a value it has nothing for arrives as an explicit null rather than as a missing key -- including the
 * four display members, whose null is what a summary whose neighbouring customer record is gone carries.
 * An unresolved customer therefore leaves all four NULL rather than blank, which is the one value that
 * distinguishes "not resolved" from "resolved and empty". `authorization-api.yaml` now publishes all
 * twenty as required for that reason, and this shape mirrors it: a member declared `?:` here would make
 * a screen provide for an absence the wire never sends.
 */
export interface PendingAuthSummary {
  readonly accountId: string;
  readonly customerId: string;
  readonly authStatus: string | null;
  readonly accountStatus1: string | null;
  readonly accountStatus2: string | null;
  readonly accountStatus3: string | null;
  readonly accountStatus4: string | null;
  readonly accountStatus5: string | null;
  readonly creditLimit: string;
  readonly cashLimit: string;
  readonly creditBalance: string;
  readonly cashBalance: string;
  readonly approvedAuthCnt: number;
  readonly declinedAuthCnt: number;
  readonly approvedAuthAmt: string;
  readonly declinedAuthAmt: string;
  readonly customerName: string | null;
  readonly addressLine1: string | null;
  readonly addressLine2: string | null;
  readonly phoneNumber1: string | null;
}

/**
 * One row of the pending-authorization list.
 *
 * Assumptions: `key` is the opaque sealed selector the detail and fraud operations are addressed by,
 * and it is the ONLY address a row carries. The underlying row is keyed by an account identifier and
 * a packed date and time, and publishing those three as a target would put the composite key of a
 * financial record into the edge access log and the browser's history.
 */
export interface PendingAuthListItem {
  readonly key: string;
  readonly transactionId: string;
  readonly authOrigDate: string | null;
  readonly authOrigTime: string | null;
  readonly authType: string | null;
  readonly approvalStatus: ApprovalStatus;
  readonly matchStatus: MatchStatus;
  readonly amount: string;
  readonly cardNum: string;
}

/**
 * The list operation's envelope: one account summary and one bounded page of rows.
 *
 * Assumptions: `screenMessage` carries the baseline's navigation-boundary text verbatim -- the
 * already-at-the-top, already-at-the-bottom and already-at-the-last strings the contract enumerates --
 * and is null when the page needs none. Transformation rule T8 requires it to be rendered unchanged.
 */
export interface PendingAuthListResponse {
  readonly summary: PendingAuthSummary;
  readonly page: PageResponse<PendingAuthListItem>;
  readonly screenMessage: string | null;
}

/**
 * One pending authorization in full.
 *
 * Assumptions: `authDate` and `authTime` are NUMBERS while every other date and time member here is a
 * string, and the asymmetry is the contract's. Those two are the row's composite key, held in the
 * segment as packed decimal -- a Julian day number and a millisecond-of-day -- and they are echoed
 * back into the fraud request unchanged. The remaining members are the authorizer's own
 * character-format fields, which are text in the message and stay text here.
 */
export interface PendingAuthDetail {
  readonly key: string;
  readonly accountId: string;
  readonly authDate: number;
  readonly authTime: number;
  readonly authOrigDate: string | null;
  readonly authOrigTime: string | null;
  readonly cardNum: string;
  readonly authType: string | null;
  readonly cardExpiryDate: string | null;
  readonly messageType: string | null;
  readonly messageSource: string | null;
  readonly authIdCode: string | null;
  readonly authRespCode: string | null;
  readonly authRespReason: string | null;
  readonly processingCode: string | null;
  readonly transactionAmt: string;
  readonly approvedAmt: string;
  readonly merchantCategoryCode: string | null;
  readonly acqrCountryCode: string | null;
  readonly posEntryMode: string | null;
  readonly merchantId: string | null;
  readonly merchantName: string | null;
  readonly merchantCity: string | null;
  readonly merchantState: string | null;
  readonly merchantZip: string | null;
  readonly transactionId: string;
  readonly matchStatus: MatchStatus;
  readonly authFraud: AuthFraudFlag | null;
  readonly fraudRptDate: string | null;
}

/**
 * One authorization projected onto the shape the 3270 detail screen rendered.
 *
 * Assumptions: this is a DIFFERENT shape from {@link PendingAuthDetail} rather than that shape with
 * extra members, and the difference is the contract's. The record reading answers the segment's own
 * fields; this reading answers what the terminal displayed, so its amounts and codes are already
 * rendered -- `authResponse` carries the approval word rather than the two-character code, `authTime`
 * carries the rendered time rather than the packed millisecond-of-day, and the card number arrives
 * masked under the member name `cardNumber`. Reusing one interface for both would let a component read
 * a rendered value as a raw one.
 *
 * Assumptions: the six chrome members are non-nullable because the service derives every one of them
 * from its own constants and clock and accepts none from the caller. The authorizer-supplied members
 * stay nullable for the reason recorded on {@link PendingAuthDetail}: the segments behind them are
 * populated from an external message, and a field that message omitted is null here rather than blank.
 *
 * Refactoring Rationale: ⚠️ `authDate`, `authTime`, `posEntryMode` and `cardExpiry` were declared
 * non-nullable and are now nullable, because all four are RENDERED from a nullable stored value and the
 * renderer returns null when it has nothing to render -- `PendingAuthDetailMapper.renderOriginatingDate`,
 * `renderOriginatingTime`, `renderPosEntryMode` and `renderCardExpiry` each answer null for a blank or
 * absent field, and `authorization-api.yaml` publishes all four with a null branch. Their columns --
 * `auth_orig_date`, `auth_orig_time`, `pos_entry_mode` and `card_expiry_date` in
 * `V1__authorization.sql` -- carry no NOT NULL, because an authorizer's message may omit any of them.
 * Declaring them non-nullable told a screen it could read `.length` or `.slice` off each of them
 * unconditionally, which is the reading that throws on the first authorization whose message omitted an
 * originating date.
 *
 * Assumptions: `authDate` and `authTime` here are the RENDERED strings and not the packed key numbers
 * {@link PendingAuthDetail} carries under the same two names. The key members are the row's identity and
 * are never null; these two are what the terminal displayed, and the terminal displayed blanks when the
 * authorizer sent none.
 */
export interface PendingAuthDetailScreen {
  readonly transactionName: string;
  readonly title01: string;
  readonly currentDate: string;
  readonly programName: string;
  readonly title02: string;
  readonly currentTime: string;
  readonly cardNumber: string;
  readonly authDate: string | null;
  readonly authTime: string | null;
  readonly authResponse: string;
  readonly authResponseReason: string;
  readonly processingCode: string | null;
  readonly approvedAmount: string;
  readonly posEntryMode: string | null;
  readonly messageSource: string | null;
  readonly merchantCategoryCode: string | null;
  readonly cardExpiry: string | null;
  readonly authType: string | null;
  readonly transactionId: string;
  readonly matchStatus: MatchStatus;
  readonly fraudMark: string;
  readonly merchantName: string | null;
  readonly merchantId: string | null;
  readonly merchantCity: string | null;
  readonly merchantState: string | null;
  readonly merchantZip: string | null;
  readonly message: string | null;
}

/**
 * The outcome of the detail screen's forward paging move.
 *
 * Assumptions: exactly one of `authorization` and `message` is populated, discriminated by
 * `endOfData`. That mirrors the reference program's forward step, which either sets its end-of-data
 * condition and the message the screen shows or replaces the current authorization, never both -- so a
 * caller must read `endOfData` before reading either.
 */
export interface NextPendingAuthorization {
  readonly authorization: PendingAuthDetail | null;
  readonly endOfData: boolean;
  readonly message: string | null;
}

/**
 * The fraud transition a reviewer submits.
 *
 * Assumptions: ONE member, the action, and no identity of its own. `authorization-api.yaml` declares
 * `FraudMarkRequest` with `required: [action]` and `additionalProperties: false`, and the service's
 * own
 * `services/authorization-service/src/main/java/com/carddemo/authorization/dto/FraudMarkRequest.java`
 * is a record of that single component. The row is identified entirely by the sealed selector in the
 * request target, so this body carries no account, customer or row-key member.
 *
 * Refactoring Rationale: this declared four further members -- an account identifier, a customer
 * identifier and the two components of the row key -- reasoning that echoing them would let the
 * service refuse a request whose body and target disagreed. The service refuses no such thing: it
 * reads the action alone, and `services/authorization-service/src/main/resources/application.yml`
 * sets `fail-on-unknown-properties: true`, so a body carrying those four is answered with HTTP 400
 * and the fraud write never runs at all. A shape that cannot be sent is worse than a narrow one,
 * because it type-checks at every call site and fails only against a running service -- and it is the
 * one write this context publishes.
 *
 * Assumptions: the two admitted values are the reference's own transitions rather than a
 * create-or-delete pair, which is why the member is a {@link FraudAction} and is never used nullably.
 */
export interface FraudMarkRequest {
  readonly action: FraudAction;
}

/**
 * The outcome of a fraud transition.
 *
 * Assumptions: this shape describes SUCCESS only. A write that failed is answered with a non-2xx
 * status and a problem document, so `updateStatus` never reports a failure and a caller must not read
 * it as one.
 *
 * Refactoring Rationale: ⚠️ `message` was `?: string | null` and is now a plain `string`, matching the
 * contract member it mirrors. Both published factories word their outcome -- 'ADD SUCCESS' for an
 * insert and 'UPDT SUCCESS' for an update -- and a failed write is answered with a problem document
 * rather than with a sentence-less success, so neither absence nor null is a state this body has. The
 * two spellings were a client-side widening of a contract that had itself published the member as
 * optional; both sides are corrected rather than one bent to the other.
 */
export interface FraudMarkResponse {
  readonly updateStatus: string;
  readonly message: string;
}

export interface PendingAuthListQuery {
  readonly accountId: string;
  readonly cursor?: string;
  readonly direction?: PageDirection;
}

// ---------------------------------------------------------------------------
// reporting-api.yaml -- the on-demand report submission, its lines and totals,
// and the statement pair
// ---------------------------------------------------------------------------
// WHY : Assumptions: the report submission is a UNION outcome for the same reason the ledger's add is --
//       a submission answers either a preview to confirm or the accepted run -- and the three total
//       bands are a closed domain because the reference report writes exactly three levels of total.

/**
 * The report-request screen's whole named surface, as the submission accepts it.
 *
 * Assumptions: THIRTEEN members, which is the component list of
 * `com.carddemo.reporting.dto.ReportRequest` name for name, and the count is arithmetic rather than a
 * selection: `app/bms/CORPT00.bms` declares 42 field definitions of which 17 carry a name, and six of
 * those 17 are the two three-part range composites that the contract consolidates into one value each.
 *
 * Assumptions: every member is optional because which of them must be supplied depends on the report
 * type selected, and no member is unconditionally present. Six of them are screen furniture the
 * request echoes -- the transaction name, the two title lines, the program name and the two stamps --
 * and a browser normally omits all six; they are declared because the contract declares them, so a
 * screen replaying a received request body can round-trip it unchanged.
 */
export interface ReportRequest {
  readonly transactionName?: string;
  readonly title01?: string;
  readonly currentDate?: string;
  readonly programName?: string;
  readonly title02?: string;
  readonly currentTime?: string;
  readonly monthly?: string;
  readonly yearly?: string;
  readonly custom?: string;
  readonly startDate?: string;
  readonly endDate?: string;
  readonly confirm?: string;
  readonly errorMessage?: string;
}

/**
 * A started report execution.
 *
 * Assumptions: `executionArn` is the orchestrator's identity for the run, and it has no counterpart in
 * the baseline: writing job-control text to the transient data queue returned no identity at all, so an
 * operator had to find the job by name. A caller may quote this value to support.
 */
export interface ReportSubmission {
  readonly executionArn: string;
  readonly reportName: string;
  readonly shortName: string;
  readonly longName: string;
  readonly startDate: string;
  readonly endDate: string;
  readonly submittedAt: Timestamp26;
}

/**
 * Which of the two submission outcomes occurred, discriminated so neither can be read as the other.
 *
 * Assumptions: the discriminant is derived from the HTTP status, which the contract makes normative --
 * 201 for a started execution and 200 for a declined confirmation. The baseline cannot draw this
 * distinction at all: inside `SUBMIT-JOB-TO-INTRDR` at `app/cbl/CORPT00C.cbl` L462, the branch for a
 * declined confirmation at L480 to L483 sets the same error flag as a validation failure and supplies
 * no message, so the screen redisplays cleared with no indication of which occurred.
 */
export type ReportSubmissionOutcome =
  | { readonly outcome: 'DECLINED'; readonly message: null }
  | { readonly outcome: 'UNANSWERED'; readonly message: string }
  | {
      readonly outcome: 'STARTED';
      readonly submission: ReportSubmission;
      readonly message: string;
    };

/**
 * The submission body itself, as the contract publishes it for both statuses.
 *
 * Refactoring Rationale: the declined arm above carried a `preview` member typed on a
 * `ReportSubmissionPreview` interface, and both are withdrawn. The published
 * `ReportSubmissionOutcome` schema requires exactly `submitted`, `message` and `submission` and
 * forbids additional properties, and no `ReportSubmissionPreview` schema exists in the document at
 * all -- so the withdrawn shape described a body the service has never emitted, naming a report and
 * both range bounds a caller would have read as `undefined`. The message is nullable on the declined
 * arm and not on the started one, which is the contract's own asymmetry: the reference writes no
 * sentence when a confirmation is declined, at `app/cbl/CORPT00C.cbl` L480 to L483.
 *
 * Refactoring Rationale: the discriminator is the published `outcome` member and WAS the boolean
 * `submitted`. Two of the three turns answer HTTP 200 -- a caller that declined the confirmation and
 * a caller that has not answered it yet -- so the status cannot separate them, and this client
 * separated them by status alone and labelled every non-created answer `DECLINED`. A caller who had
 * merely left the confirmation blank was therefore told it had cancelled, and the prompt naming the
 * report was dropped on the floor. The union above now carries an `UNANSWERED` arm whose message is
 * that prompt and is never null, while the declined arm carries no sentence at all -- which is the
 * asymmetry the reference itself has.
 */
export interface ReportSubmissionOutcomeBody {
  readonly outcome: 'STARTED' | 'DECLINED' | 'UNANSWERED';
  readonly message: string | null;
  readonly submission: ReportSubmission | null;
}

/**
 * What became of one submitted report run, and where its document is when there is one.
 *
 * Refactoring Rationale: this shape is new because the handle a submission returns was consumed by
 * nothing. A caller could not tell a run still going from one that had failed, and could not reach
 * the document a succeeded run produced -- so a submitted state was the last thing a screen could
 * show. Every member after the first four is null while it is unknowable: the coordinates are absent
 * for a run this surface did not start, and the result pair is absent until the run has succeeded and
 * its document is actually in the store.
 *
 * Assumptions: `resultUri` and `resultGeneratedAt` move together. Either both are present or both are
 * null, because a location with no write instant would leave a caller unable to tell a fresh document
 * from a stale one, and an instant with no location names nothing.
 */
export interface ReportExecutionStatus {
  readonly executionName: string;
  readonly status: 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'TIMED_OUT' | 'ABORTED' | 'PENDING_REDRIVE';
  readonly startedAt: Timestamp26;
  readonly stoppedAt: Timestamp26 | null;
  readonly reportType: string | null;
  readonly startDate: string | null;
  readonly endDate: string | null;
  readonly resultUri: string | null;
  readonly resultGeneratedAt: Timestamp26 | null;
}

export interface TransactionReportLine {
  readonly transactionId: string;
  readonly accountId: string;
  readonly typeCode: string;
  readonly typeDescription: string;
  readonly categoryCode: string;
  readonly categoryDescription: string;
  readonly source: string;
  readonly amount: string;
}

export type ReportBand = 'PAGE' | 'ACCOUNT' | 'GRAND';

/**
 * One subtotal band of the report.
 *
 * Assumptions: `label` carries the verbatim literal its reference group declares at
 * `app/cpy/CVTRA07Y.cpy` L51, L57 and L63, and a caller renders it unchanged rather than deriving text
 * from `band`. Two irregularities defeat derivation: 'Account Total' is two words where the band name
 * is one, and all three literals are singular where their groups are plural.
 */
export interface ReportTotalBand {
  readonly band: ReportBand;
  readonly label: string;
  readonly amount: string;
}

export interface TransactionReportTotals {
  readonly bands: readonly ReportTotalBand[];
}

/**
 * The selector a statement operation takes.
 *
 * Assumptions: both members are optional and exactly one is supplied, which is why neither is required
 * by the contract -- either may be the one omitted. The card number is the only unmasked account number
 * this module transmits, and it travels in a body for the reason recorded in this file's header.
 */
export interface StatementRequest {
  readonly cardNumber?: string;
  readonly accountId?: string;
}

/**
 * The summary of one rendered statement and where its two renderings were written.
 *
 * Assumptions: `accountId` is up to twenty characters here while a request accepts eleven digits, and
 * the asymmetry is the statement layout's rather than an inconsistency to normalise:
 * `app/cpy/COSTM01.CPY` declares a twenty-character carrier, and reporting the layout's width is what
 * keeps a value it can hold from being truncated on the way out.
 *
 * Refactoring Rationale: the two locations and the stamp beside them are NULLABLE, and were required.
 * A run publishes two artifacts for the whole run rather than one pair per card, so before that run has
 * produced them there is nothing to follow -- and a required location forced a value to be reported
 * whether or not one existed, which left a caller unable to tell a rendered statement from an
 * unrendered one. Each is null exactly when the artifact it describes is not stored, and the two
 * locations are independent of each other because a run interrupted between its two writes leaves one.
 *
 * Refactoring Rationale: `firstRecord` and `recordCount` name WHERE this card's block sits inside those
 * run-wide artifacts, which is the only way a caller can collect a document and then find the part
 * belonging to the statement it asked for. Both are null on the same terms as the locations. They are
 * ordinals into a document rather than a count of anything a caller displays, which is why they are
 * numbers here and not the rendered strings every monetary member of this file carries.
 */
export interface Statement {
  readonly cardNumber: string;
  readonly accountId: string;
  readonly customerName: string;
  readonly totalAmount: string;
  readonly transactionCount: number;
  readonly plainTextUri: string | null;
  readonly htmlUri: string | null;
  readonly generatedAt: Timestamp26 | null;
  readonly firstRecord: number | null;
  readonly recordCount: number | null;
}

export interface StatementTransaction {
  readonly cardNumber: string;
  readonly transactionId: string;
  readonly typeCode: string;
  readonly categoryCode: string;
  readonly source: string;
  readonly description: string;
  readonly amount: string;
  readonly merchantId: string;
  readonly merchantName: string;
  readonly merchantCity: string;
  readonly merchantZip: string;
  readonly originTimestamp: Timestamp26;
  readonly processingTimestamp: Timestamp26;
}

/**
 * The transactions one statement was rendered from.
 *
 * Assumptions: not a page. A statement covers one card's posted transactions for one period and the
 * reference generator bounds that set itself, so there is no open-ended sequence for a cursor to walk.
 *
 * Refactoring Rationale: `transactionCount` and `truncated` are declared here, where only `items`
 * was. The published `StatementTransactionCollection` schema requires all THREE and the service
 * always emits all three, so declaring one left the other two unreadable from a screen even though
 * they arrive on every response -- and `truncated` is the ONLY signal that the array stops short of
 * what the statement covers. Omitting it meant a cardholder whose history exceeds the response
 * ceiling would be shown a silently short list indistinguishable from a complete one.
 */
export interface StatementTransactionCollection {
  readonly items: readonly StatementTransaction[];
  /**
   * How many transactions the statement covers in total.
   *
   * Assumptions: a database aggregate over the whole card rather than the length of `items`, which is
   * why the array's own length is not published in its place: the two are equal on every statement
   * within the response ceiling and differ on exactly the statements where the difference matters.
   */
  readonly transactionCount: number;
  /**
   * Whether `items` stops short of `transactionCount`.
   *
   * Assumptions: derived by the service from the two counts and never accepted from a caller, so the
   * three members cannot disagree. A screen renders this rather than comparing the counts itself.
   */
  readonly truncated: boolean;
}

export interface ReportRangeQuery {
  readonly startDate: string;
  readonly endDate: string;
  readonly cursor?: string;
  readonly direction?: PageDirection;
}
