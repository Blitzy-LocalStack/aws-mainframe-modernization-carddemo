/**
 * Owns the structured error contract of the migrated CardDemo services: the
 * problem shape returned to every API client, the per-field error array that
 * carries validation failures inside it, and the structured equivalent of the
 * baseline abend data block.
 *
 * <p><b>Purpose.</b> Three questions have exactly one answer each across all
 * eight bounded contexts, and this package is where those three answers live.
 * What does a failed request look like on the wire? What does a field-level
 * validation failure look like inside that response? And what does an
 * unrecoverable condition look like once there is no terminal screen left to
 * write it onto? The reference baseline answers all three, but it answers them
 * in four separate copybooks, at four different declared widths, and through a
 * centralised emission paragraph that every caller has to remember to invoke.
 * This charter records those answers, records the widths, and records each
 * place where the migrated form departs from them and why.
 *
 * <p><b>Parameters, return values, exceptions or errors.</b> A package
 * declaration accepts no parameter, yields no value and raises nothing, so this
 * charter carries no parameter, return or exception at-clause. The
 * inapplicability is declared rather than passed over in silence so that a
 * reader can tell it from an oversight.
 *
 * <h2>The roles this package owns</h2>
 *
 * <pre>
 * file                            holds
 * package-info.java               this charter
 * ApiError.java                   the problem shape, with the per-field array
 * GlobalExceptionHandler.java     the one advice that renders every failure
 * AbendDetail.java                the structured abend data equivalent
 * ApiErrorSecurityHandlers.java   the two refusals the filter chain answers itself
 * ClientInputException.java       the caller-fault marker the advice keys 400 on
 * RecordConflictException.java    the contention a service names for itself
 * FieldOrdering.java              the per-body field order the advice latches on
 * </pre>
 *
 * <p>Seven production classes and one charter, eight compilation units, and the
 * set is closed at eight rather than at some larger number a reader has to
 * guess at. Four of the seven are the original trio plus the advice; the other
 * three exist because the advice cannot infer what only the raising code knows.
 * {@code ApiErrorSecurityHandlers} renders the 401 and the 403 that the security
 * filter chain answers before any advice runs, so those two statuses carry the
 * same problem shape as every other failure. {@code ClientInputException} marks
 * a refusal as the caller's to fix, which is the distinction the standard
 * exception hierarchy cannot draw. {@code RecordConflictException} lets a
 * service that has already detected contention say which kind, rather than
 * leaving the advice to infer it from a provider exception name.
 * {@code FieldOrdering} declares the order one request body's fields are checked
 * in, because Bean Validation evaluates constraints in no defined order while
 * several migrated screens report the first failure and only the first.
 *
 * <p>Refactoring Rationale: this table and the count beneath it are corrected
 * rather than merely extended, and the correction is recorded because the
 * earlier text was not vague but definite and wrong. It named exactly three
 * production classes, four compilation units, and stated that no fifth
 * compilation unit was to be added -- that anything which would have been a
 * fourth top-level type was a nested type inside one of the three instead. Four
 * top-level types had since been added beside them, so the sentence forbidding
 * them was being read as a description of a directory it no longer described.
 * The closed-set discipline itself is kept, because it is what makes a proposed
 * addition arguable: a ninth compilation unit here has to be argued against the
 * seven roles above rather than simply added beside them. What is withdrawn is
 * only the claim that every further type must nest -- the three additions above
 * are top-level precisely because each is referenced by code outside this
 * package, and a nested type reached from eight service modules would name its
 * enclosing class at every use site for no benefit. Two of the original
 * nestings do survive and are unaffected: the per-field entry still nests inside
 * {@code ApiError}, beside the array that holds it, and each concrete conflict
 * condition still nests inside {@code RecordConflictException}.
 *
 * <p>Alternatives Considered: this charter could have been a stub -- a bare
 * {@code package} statement under an ordinary block comment -- and it is prose
 * instead because a stub passes half the gate and fails the other half. Two
 * checks interlock, deliberately split across a boundary that looks arbitrary
 * until the reason is stated. The presence check is a file-set check and sits
 * at the audit's outermost level: it inspects the file system and requires a
 * charter file to exist in any directory holding an audited compilation unit,
 * and it never reads what is inside. The content check is a tree check and sits
 * inside the tree walker: it inspects the parsed Javadoc of that file and
 * requires the file to carry Javadoc at all. A charter reduced to a bare
 * package statement therefore satisfies the first and fails the second, which
 * is exactly the outcome the pair exists to prevent -- a directory that is
 * documented on paper and undocumented in fact. The audit configuration at
 * {@code config/checkstyle/checkstyle.xml} explains the same split in its own
 * comments at lines 215 to 222 and 325 to 331, and it is restated here because
 * a reader of this file may never open that one.
 *
 * <p>Assumptions: the gate is local rather than a pipeline-only step. The rule
 * set is bound to the build's validate phase, ahead of compilation, so deleting
 * this file or emptying it breaks the build on a developer's own machine and
 * not merely in continuous integration. There is also no in-code escape: none
 * of the three comment-driven or annotation-driven suppression filters is
 * enabled, and the companion suppressions file reaches generated sources and
 * test fixtures only, so nothing beneath this module's main source tree can be
 * exempted.
 *
 * <h2>Four message widths, and not one of them is the others</h2>
 *
 * <p>The single most consequential thing this package owns is a set of declared
 * widths. Each was read from the file rather than taken on trust:
 *
 * <pre>
 * width  field                        declared at
 * 50     CCDA-MSG-THANK-YOU           app/cpy/CSMSG01Y.cpy lines 18 to 19
 * 50     CCDA-MSG-INVALID-KEY         app/cpy/CSMSG01Y.cpy lines 20 to 21
 * 50     ABEND-REASON                 app/cpy/CSMSG02Y.cpy lines 26 to 27
 * 72     ABEND-MSG                    app/cpy/CSMSG02Y.cpy lines 28 to 29
 * 75     CCARD-ERROR-MSG              app/cpy/CVCRD01Y.cpy line 28
 * 75     CCARD-RETURN-MSG             app/cpy/CVCRD01Y.cpy line 29
 * 50     ERR-MESSAGE                  CCPAUERY.cpy line 39
 * 80     LS-RESULT                    app/cbl/CSUTLDTC.cbl line 86
 * </pre>
 *
 * <p>Assumptions: there are four distinct regimes above -- 50, 72, 75 and 80 --
 * and this package models them as four, because each belongs to a different
 * destination. Fifty is the common message and reason width; seventy-two is the
 * widest thing the abend block carries; seventy-five is the terminal message
 * line and the only one of the four that survives into the migrated user
 * interface as a rendering constraint; eighty is the diagnostic out-parameter of
 * the date edit utility, declared as {@code 01 LS-RESULT PIC X(80).} at line 86
 * of {@code app/cbl/CSUTLDTC.cbl} and so a call contract rather than a screen
 * contract at all. Truncating an eighty-character diagnostic into a
 * fifty-character field would discard the end of the text that explains the
 * failure, and widening a fifty-character reason to eighty would change the
 * trailing blanks a fixed-width consumer receives; both are observable.
 *
 * <p>Assumptions: fifty recurs at three unrelated sites -- the two common
 * messages of {@code app/cpy/CSMSG01Y.cpy}, the abend reason of
 * {@code app/cpy/CSMSG02Y.cpy}, and the error-log message of the authorization
 * extension's {@code CCPAUERY.cpy} -- which share no lineage and serve three
 * subsystems, so the agreement on fifty is a house convention the baseline made
 * more than once rather than a coincidence, and the migrated constants are
 * grouped accordingly.
 *
 * <p>Assumptions: the two common message literals are 49 characters long
 * against a declared width of 50, measured from the text at lines 19 and 21 of
 * {@code app/cpy/CSMSG01Y.cpy}, so the baseline right-pads one further blank
 * when the field is loaded. Transformation rule T8 requires user-visible strings
 * to be carried across character for character, so the migrated constant holds
 * the 49-character literal exactly and the declared width of 50 is recorded
 * separately as the padding contract; a constant that absorbed the extra blank
 * would be neither the source literal nor the loaded field.
 *
 * <p>Assumptions: the baseline contains two different thank-you strings and both
 * are carried forward as distinct constants, because they differ in three ways
 * at once. {@code 05 CCDA-MSG-THANK-YOU PIC X(50)} at lines 18 and 19 of
 * {@code app/cpy/CSMSG01Y.cpy} names the CardDemo application and is 49
 * characters against its declared 50; {@code 05 CCDA-THANK-YOU PIC X(40)} at
 * lines 23 and 24 of {@code app/cpy/COTTL01Y.cpy} names the CCDA application and
 * is exactly 40, filling its width with no padding. Merging them would be a
 * visible change to what a user reads. The forty-character width belongs to the
 * title band rather than to a message line -- the same copybook declares two
 * further forty-character title constants at lines 18 and 20 -- so it is not a
 * fifth message-width regime.
 *
 * <p>Assumptions: the abend fields of {@code app/cpy/CSMSG02Y.cpy} occupy lines
 * 21 to 29 of a 35-line file: {@code 01 ABEND-DATA.} at line 21, then
 * {@code ABEND-CODE PIC X(4)}, {@code ABEND-CULPRIT PIC X(8)},
 * {@code ABEND-REASON PIC X(50)} and {@code ABEND-MSG PIC X(72)}, each occupying
 * two lines because each carries its {@code VALUE SPACES} on the line after its
 * picture. Four components summing to 134 bytes, all four blank-initialised. The
 * extent is stated with its arithmetic because a citation placing this block
 * beyond line 35 names a range the file refutes.
 *
 * <p>Assumptions: that same copybook announces itself as a different file -- its
 * line 2 reads {@code 000800* CABENDD.CPY}, which is neither the name it is
 * stored under nor the name any program includes it by. The internal title is a
 * survival from an earlier name, recorded here so that a reader tracing the
 * lineage of {@code AbendDetail} searches for the storage name and nobody
 * concludes a second copybook is missing.
 *
 * <p>Refactoring Rationale: the 75-character screen message line belongs to
 * {@code app/cpy/CVCRD01Y.cpy}, not to {@code app/cpy/CSMSG01Y.cpy}. The two
 * are easy to transpose because one is named for messages and the other is not,
 * and the misattribution would move the width onto a copybook that declares no
 * field of that width at all. The correct declarations are
 * {@code 10 CCARD-ERROR-MSG PIC X(75).} at line 28 and
 * {@code 10 CCARD-RETURN-MSG PIC X(75).} at line 29 of a 46-line file.
 *
 * <p>Assumptions: those two fields are not symmetric, and the asymmetry is
 * load-bearing. Line 30 declares
 * {@code 88 CCARD-RETURN-MSG-OFF VALUE LOW-VALUES.} and it attaches to the
 * return message alone, so the baseline can ask whether a return message has
 * been set and cannot ask the same of an error message. {@code ApiError}
 * therefore models one nullable aggregate message whose absence is meaningful,
 * and does not invent a second sentinel for a field that never had one.
 *
 * <p>Trade-offs: of the four widths only 75 is carried forward as a rendering
 * constraint, and the other three become documentation of provenance rather than
 * limits enforced at run time. A client rendering a message in a reflowing
 * browser layout has no 24-row terminal to overflow, so enforcing 50 or 72 on
 * the wire would reject text no consumer would have struggled with, while
 * enforcing 80 would apply a call contract to a response body. What is given up
 * is the ability to prove by construction that a migrated message would still
 * have fitted the original screen; what is gained is that the one width a client
 * depends on is stated once and not lost among three no client can observe. Log
 * field shapes belong to the migration's observability notes and are
 * deliberately not restated here.
 *
 * <h2>Two different kinds of empty</h2>
 *
 * <p>Assumptions: the baseline distinguishes a message that was never set from a
 * message set to blanks, and this package preserves the distinction. The abend
 * block blank-initialises all four components, at lines 23, 25, 27 and 29 of
 * {@code app/cpy/CSMSG02Y.cpy}; the screen return message uses a low-values
 * sentinel instead, at line 30 of {@code app/cpy/CVCRD01Y.cpy}. Blanks means a
 * message that exists and is empty, low values means a message that is off.
 * Trimming both to an empty string would erase a difference the baseline can
 * still see and would leave no way to express message-off at all.
 *
 * <p>Assumptions: the same condition-name idiom carries both sentinel values, so
 * anyone mapping one of these fields has to read the declaration governing the
 * field in front of them rather than generalising from a similarly named
 * condition elsewhere. Every message-off condition in the reference programs is
 * a blanks sentinel -- line 250 of {@code COTRTLIC.cbl}, line 168 of
 * {@code COTRTUPC.cbl}, line 174 of {@code app/cbl/COCRDUPC.cbl}, line 118 of
 * {@code app/cbl/COACTVWC.cbl}, line 480 of {@code app/cbl/COACTUPC.cbl} and
 * line 135 of {@code app/cbl/COCRDSLC.cbl} -- while the shared copybook's
 * condition at line 30 of {@code app/cpy/CVCRD01Y.cpy} is low values. Same
 * role, two encodings, not interchangeable.
 *
 * <p>Assumptions: the sentinel is control flow and not decoration. In
 * {@code app/cpy/CSUTLDPY.cpy} the aggregate message is written only under a
 * message-off test, at lines 218, 233, 263 and 305, so the first failure to
 * occur wins and later failures leave the text alone. Folding the two empties
 * into one would let a message set to blanks read as never set, and the second
 * failure would then overwrite the first.
 *
 * <h2>The per-field error array: transformation rule T7</h2>
 *
 * <p>Transformation rule T7 governs the shape this package publishes. The
 * baseline's per-field failure marking is a pair of conditions on a flag,
 * spelled {@code FLG-*-NOT-OK} and {@code FLG-*-BLANK}, read by a templated
 * highlight copybook at lines 17 to 27 of {@code app/cpy/CSSETATY.cpy}. In the
 * migrated form that becomes a structured per-field error array inside the
 * response body, which a client renders as a form field carrying an error
 * validation status and help text. The flag half of the contract belongs to the
 * sibling {@code validation} package; the array that carries it to a client
 * belongs here.
 *
 * <p>Assumptions: blank is a kind of error and not a third peer state. Lines 18
 * and 19 of {@code app/cpy/CSSETATY.cpy} are a single disjunctive test whose two
 * branches lead to the same highlight, and the blank path then sets a second
 * marker on top of the first rather than a different one. The published array
 * therefore carries every offending field with the same status and distinguishes
 * never-supplied from unacceptable-value as a property of the entry; modelling
 * blank as a peer would oblige every client to handle three cases where the
 * baseline handles two.
 *
 * <p>Assumptions: the asterisk marker is presentational and never a domain
 * value, and the template proves it by writing the two things to two different
 * places -- line 21 moves the colour attribute into the field's colour subfield,
 * line 24 moves the literal {@code '*'} into its output subfield. The migrated
 * entry therefore records that a field was never supplied and leaves the marker
 * to rendering, because an asterisk in a value is indistinguishable from data
 * the user typed.
 *
 * <p>Assumptions: one failure does not map to one field and the fan-out varies,
 * so the array is genuinely variable length. In {@code app/cpy/CSUTLDPY.cpy} a
 * thirty-first day in a thirty-day month at lines 213 to 217 and a thirtieth of
 * February at lines 228 to 232 each mark two fields, while a twenty-ninth of
 * February in a non-leap year at lines 259 to 262 and a rejection on the
 * runtime-services path at lines 300 to 304 each mark three. A fixed fan-out
 * would either drop the year on the two-field cases or invent one on the
 * three-field cases, and the highlighted fields would stop matching.
 *
 * <p>Refactoring Rationale: the aggregate message and the per-field array are
 * two channels with two different arities, and {@code ApiError} carries them as
 * such. The baseline already works this way in the same copybook: the aggregate
 * text is written under the message-off latch and so records the first failure
 * only, while the field markers are set unconditionally and accumulate.
 * Collapsing them loses information either way -- keeping only the aggregate
 * highlights one field out of three, keeping only the array leaves no single
 * sentence to display where the baseline displays one -- at the cost of a
 * response a client has to read in two places.
 *
 * <h2>The re-entry gate becomes nothing at all</h2>
 *
 * <p>Refactoring Rationale: line 20 of {@code app/cpy/CSSETATY.cpy} adds a
 * further condition to the disjunctive test above it, requiring a later
 * conversational turn before any field is highlighted; the marker it tests is
 * declared at lines 29 to 31 of {@code app/cpy/COCOM01Y.cpy}. That gate has
 * nothing to test in the migrated form, because the services are stateless and a
 * handler answering with a field-error array has no first-entry and later-entry
 * distinction available to it and no place to keep one. Error presentation is
 * therefore driven purely by the response body.
 *
 * <p>Assumptions: that produces one deliberate behavioural difference, recorded
 * rather than absorbed. On a first submission that fails validation the baseline
 * reports the aggregate message without highlighting the offending fields
 * because its gate has not opened; the migrated services highlight them
 * immediately, and the divergence is documented in the migration's traceability
 * matrix. It also constrains the shape of this package: no type here holds a
 * turn count, a submission counter or any other trace of the conversational
 * marker, and none is to be introduced under any name.
 *
 * <h2>Severity and subsystem are adopted, not invented</h2>
 *
 * <p>The baseline already emits structured error records. The authorization
 * extension's {@code CCPAUERY.cpy} is a 40-line copybook whose group item at
 * line 19 carries eleven fields totalling 122 bytes, two of which have closed
 * value domains:
 *
 * <pre>
 * severity  (line 25)  4 values, at lines 26 to 29
 *   L log       I info       W warning       C critical
 * subsystem (line 30)  6 values, at lines 31 to 36
 *   A application   C CICS   I IMS   D Db2   M MQ   F file
 * </pre>
 *
 * <p>Alternatives Considered: the five-level ladder most logging frameworks
 * default to, which would add a debug level below the baseline's lowest and
 * split its top level in two. Rejected because the four-value domain is the one
 * the existing operational material already speaks -- a warning-level record
 * from a baseline program and one from a migrated service should be comparable
 * without a translation table, whereas an added level is a rung that is always
 * empty and a split top rung produces two nobody can tell apart after the fact.
 *
 * <p>Assumptions: the subsystem domain maps unevenly and the gaps are named.
 * Application maps to application, Db2 to the relational store, message-queue to
 * the queue transport and file to the object store; CICS and IMS have no target
 * analogue, being the two platform components the migration replaces outright,
 * and are never emitted by a migrated service. Both values are kept in the
 * documented domain all the same, because a baseline record carrying either one
 * still has to be readable and dropping a value silently makes an old record
 * undecodable rather than merely unusual.
 *
 * <p>Assumptions: the correlation identifier is inherited, not invented. The
 * twenty-character event key at line 40 of that copybook is the baseline's own
 * means of tying the records of one unit of work together, so the sibling
 * {@code web} package's correlation filter formalises an existing concept. This
 * package consumes the result: the identifier that arrives on a request is the
 * identifier on the problem shape returned for it, so a client-reported failure
 * can be found in the operational record without the client quoting a timestamp.
 *
 * <p>Refactoring Rationale: machine codes stay out of user-visible prose, and
 * the baseline is inconsistent on this point. The structured record gets it
 * right by construction -- its two nine-character code fields at lines 37 and 38
 * are separate from the fifty-character message at line 39, so a consumer can
 * match on a code without parsing a sentence. Two other sites do the opposite:
 * lines 544 to 549 of the authorization extension's {@code COPAUS1C.cbl}
 * assemble a database return code into text a user reads, and lines 306 to 313
 * of {@code app/cpy/CSUTLDPY.cpy} assemble a severity and a message number the
 * same way. That pattern makes one string serve two audiences needing opposite
 * things -- an operator needs a stable token to search for, a user needs a
 * sentence that can be reworded -- so {@code ApiError} keeps the code and the
 * message in separate members.
 *
 * <h2>Centralised error emission has an ancestor in the baseline</h2>
 *
 * <p>Refactoring Rationale: the advice in this package is the baseline's own
 * pattern with one failure mode removed. The authorization consumer
 * {@code COPAUA0C.cbl} factors its error emission into one paragraph at line
 * 983, invoked from fourteen separate places (lines 282, 316, 429, 500, 512,
 * 547, 560, 595, 608, 639, 778, 846, 931, 975), and the same program's siblings
 * factor commit and rollback the same way -- {@code COPAUS1C.cbl} at lines 557
 * to 560 and 565 to 569. Centralising is settled house practice; the migrated
 * form differs only in being reached by an exception propagating out of the
 * handler rather than by an invocation written at each site. That is the whole
 * benefit: fourteen sites is fourteen opportunities to add a fifteenth error
 * path and forget the invocation, and a forgotten invocation is invisible
 * because the program still works and the record simply is not written.
 *
 * <h2>Two conflicts, and both are HTTP 409</h2>
 *
 * <p>The advice owns two mandatory conflict mappings, both because the
 * alternative leaks something a client must not receive.
 *
 * <p>Refactoring Rationale: the first is an optimistic-lock conflict, which the
 * baseline already implements by hand -- so the migrated version is a change of
 * expression, not of behaviour. {@code app/cbl/COACTUPC.cbl} snapshots the whole
 * pre-edit record into a before-image group beginning at line 669, holding each
 * numeric twice (a twelve-character balance field redefined as a signed value
 * with two decimal places at lines 675 to 677), carries a change marker declared
 * at line 168 as {@code 05 WS-DATACHANGED-FLAG PIC X(1).} with conditions at
 * lines 169 and 170, commits on the success path at lines 945 to 958, and on a
 * failed rewrite sets a locked-but-failed state and rolls back at lines 4097 to
 * 4103. The migrated services express the same intent through a version column
 * the persistence layer maintains, mapped here to HTTP 409. Nothing is lost,
 * because the baseline's read-for-update lock was never held across the user's
 * thinking time -- which is precisely why the before-image had to exist.
 *
 * <p>Assumptions: two citations on that program are routinely conflated and are
 * different constructs. Line 168 is the one-character change marker with its
 * conditions at lines 169 and 170; lines 521 and 522 are a message-valued
 * condition on a message field, one member of an eleven-member family occupying
 * lines 507 to 528, whose value is the text a user sees. Only the second becomes
 * a message constant. Its verbatim text at line 522 is
 * {@code Record changed by some one else. Please review}: 46 characters, with no
 * terminating period -- the period after the closing quote is the statement
 * terminator -- and with the two-word spelling of the third-party pronoun
 * preserved exactly, the same spelling appearing again at line 286 of
 * {@code app/cpy/CSUTLDPY.cpy}. Transformation rule T8 requires character-for-
 * character carriage, so regularising the spelling would be a silent
 * behavioural change of exactly the kind that rule exists to prevent.
 *
 * <p>Assumptions: several other commit boundaries bear on the same mapping, so
 * the 409 is not read as belonging to one program. {@code app/cbl/COCRDUPC.cbl}
 * commits at line 470; the authorization consumer commits once per message at
 * line 335 of {@code COPAUA0C.cbl}; the pending-authorization screens commit at
 * line 686 of {@code COPAUS0C.cbl}, where the commit is guarded and also
 * releases a hierarchical-database resource, and at lines 557 and 558 of
 * {@code COPAUS1C.cbl}, whose rollback path and verbatim message are at lines
 * 545 and 563. Each is a place a concurrent change can be discovered, so each is
 * a place the same status has to be produced.
 *
 * <p>Refactoring Rationale: the second mapping is a restrict-on-delete
 * violation, answered with a 409 and a message rather than the database's own
 * complaint. The reference-data foreign key that preserves the baseline's
 * restrict semantic makes deleting a transaction type impossible while
 * categories still point at it, and a driver's constraint-violation text names
 * the schema, the table and the constraint -- so the raw form both fails to tell
 * the caller what to do and tells an untrusted caller the internal shape of the
 * store.
 *
 * <p>Assumptions: nothing this package emits ever carries a credential, a
 * connection string, a stack trace or a vendor error code out to a client, and
 * the two mappings above are part of how that is achieved rather than
 * incidental to it. A problem shape is assembled from members this package
 * defines, never from the text of a caught exception, so there is no path by
 * which an internal detail reaches a response body by default. Diagnostic
 * detail goes to the operational record, keyed by the inherited correlation
 * identifier, which is what makes it possible to answer a client with a short
 * sentence and still investigate the failure.
 *
 * <h2>The closed inventory of this directory</h2>
 *
 * <p>Assumptions: this package's contract is exactly seven production classes --
 * {@code ApiError}, {@code AbendDetail}, {@code GlobalExceptionHandler},
 * {@code ApiErrorSecurityHandlers}, {@code ClientInputException},
 * {@code RecordConflictException} and {@code FieldOrdering} -- plus this charter, so
 * the directory holds 7 + 1 = 8 compilation units. That is the same seven the file
 * table at the head of this charter lists, and the two statements are kept in
 * agreement deliberately: a reader who checks only one of them must reach the same
 * figure. The set is recorded as closed so that a type the contract never admitted
 * stays distinguishable from one that belongs here: an eighth error type proposed for
 * this package has to be argued against the seven roles above rather than simply
 * added beside them.
 *
 * <p>Refactoring Rationale: this paragraph said three production classes and four
 * compilation units, and it is corrected to seven and eight. The figure it carried
 * was accurate when written and was then left behind by four additions to this
 * directory, which is exactly the failure a closed inventory exists to make visible
 * -- so the inventory is re-derived from the directory rather than adjusted by
 * arithmetic on the old number. The count this charter owns is also the count the
 * root charter at {@code com.carddemo.common} carries for this row of its
 * module-wide table, and both were corrected together, because a subpackage figure
 * that agreed with the directory while the module table did not would simply move
 * the disagreement one level up.
 *
 * <p>Alternatives Considered: restating the whole module's per-subpackage totals
 * here as a cross-check. Rejected -- the module-wide figures belong to the root
 * charter at {@code com.carddemo.common}, and a second copy of them in a
 * subpackage is a figure that can disagree with its own source. This charter states
 * only the count it owns, and {@code com.carddemo.common} states the module's.
 *
 * <h2>The dependency arrow, and why the shared kernel exists</h2>
 *
 * <p>Trade-offs: this package depends on the sibling {@code validation} package
 * and that package never depends on this one, and the one-way arrow is a
 * constraint accepted rather than a fact observed. The per-field flag is set by a
 * validator while it is deciding whether a field is acceptable; the array is
 * assembled here only once a response is being built. Pointing the arrow the
 * other way would gather the whole error model into one package, which is
 * tidier, but a validator could then not run without the web-facing response
 * model on its path and the date edit rules would stop being testable as pure
 * logic. The cost is that two halves of one contract live in two packages. This
 * package may also use the {@code money} and {@code time} siblings, and must not
 * be depended upon by {@code validation}, {@code codec}, {@code money} or
 * {@code time}.
 *
 * <p>Assumptions: layering has one configured enforcement site, the
 * {@code architecture-rules} Surefire execution declared in
 * {@code services/pom.xml}, which scans the shared kernel's test artifact into
 * every module and selects the layering rules by the simple name
 * {@code LayeringRulesTest}. The audit configuration's import-restriction module
 * is deliberately not enabled and must not be added, because two owners would
 * mean two files to change whenever a boundary moves and no way to tell which is
 * authoritative from either.
 *
 * <p>Trade-offs: nine package roots exist across the migrated code base -- this
 * shared kernel and the eight bounded contexts -- and the arrow between them
 * runs one way only, with all eight depending on this module and this module on
 * none of them. The eight are referred to collectively throughout this file and
 * never spelled out, because no compilation unit here may reference one in any
 * form: not an import, not a fully qualified name, and not a string literal
 * resolved at run time. The last of those bites hardest here, because the advice
 * discriminates some exception types by class name in order to avoid a
 * compile-time dependency on a framework it does not require, so only
 * third-party, platform and standard-library names may appear in such a string.
 * Describing the roots costs a sentence that reads less directly than a dotted
 * path; it buys a charter that does not itself match the audit search for the
 * very dependency this tree is forbidden to have.
 *
 * <p>Alternatives Considered: each service declaring the error shape it needs
 * for itself. Rejected for the reason the baseline settles the same question --
 * the reference programs resolve every layout through one compiler include path,
 * recorded at line 268 of {@code tests/README.md}, and transformation rule T2
 * carries that discipline to this side: one {@code COPY} becomes one import, and
 * shared concerns -- codecs, money, errors, validation flags -- live only in
 * {@code common-lib}. Eight service-local problem shapes would drift, and the
 * day two of them disagree the symptom is a client that parses seven services'
 * failures and mishandles the eighth.
 *
 * <h2>Message text is Java constants</h2>
 *
 * <p>Trade-offs: every message this package carries is a Java constant and none
 * is externalised to a properties file or a resource bundle, which follows from
 * what this module is. {@code common-lib} is a library rather than a deployable:
 * it has no application configuration of its own, no container definition, no
 * interface contract directory and no schema migration directory, and its
 * resources directory holds only the auto-configuration registration file the
 * framework requires by name and the shared configuration defaults a service
 * imports deliberately. A message in a bundle would turn a library into
 * something that has to be configured before it can be used and would make the
 * text overridable from a consumer's classpath. What is given up is changing a
 * message without recompiling, and the loss is small because transformation rule
 * T8 requires these strings to match the baseline character for character, so a
 * deployment that could reword one is a deployment that could break parity.
 *
 * <h2>Money is exact fixed point, in the error path too</h2>
 *
 * <p>Assumptions: an error payload can carry money -- an over-limit failure is
 * about an amount -- so transformation rule T3 binds this package exactly as it
 * binds the arithmetic. One representation per layer, no exceptions: an exact
 * decimal with two places in the store, a scaled decimal type in Java, and a
 * JSON string on the wire. IEEE-754 binary arithmetic is forbidden anywhere in
 * the money path, in either of the language's two binary primitive types, in
 * either of their wrapper types, and as a bare JSON number. The normative field
 * is {@code 05 ACCT-CURR-BAL PIC S9(10)V99.} at line 7 of
 * {@code app/cpy/CVACT01Y.cpy}, the 300-byte account record; it is zoned decimal
 * with a sign overpunch, and lines 273 and 274 of {@code tests/README.md} record
 * that compiling with the default sign convention instead of the EBCDIC one
 * "misreads the zoned-decimal sign overpunch and silently corrupts negative
 * balances".
 *
 * <p>Trade-offs: the string form on the wire is the part that looks like
 * fussiness and is not, because a JSON number is parsed into IEEE-754 binary by
 * most clients and an amount that was exact through the store and the service
 * becomes approximate in the one place a person reads it. The cost accepted is a
 * client that must convert before arithmetic. The two forbidden numeric type
 * names are described rather than spelled anywhere in this file, so that this
 * charter does not match the audit search for the very tokens the money path
 * must not contain; the enforcement is the architecture test named above,
 * because prose cannot fail a build and a test can.
 *
 * <h2>How this package is verified</h2>
 *
 * <p>Assumptions: the repository's COBOL oracle suite covers batch flows, so it
 * cannot verify most of this package -- the problem shape and the advice are
 * request-facing, and the baseline's online programs cannot be driven end to end
 * without a transaction monitor the runner does not have. Both are therefore
 * verified by unit tests under {@code services/common-lib/src/test} and no
 * golden-master backing is claimed for them. The message text is the one
 * exception: it is verifiable directly against the copybooks without running
 * anything, and is asserted character for character against the lines cited
 * here. Recording the boundary matters because claiming oracle backing for the
 * whole package would overstate the evidence behind the part that has none.
 */
package com.carddemo.common.error;
