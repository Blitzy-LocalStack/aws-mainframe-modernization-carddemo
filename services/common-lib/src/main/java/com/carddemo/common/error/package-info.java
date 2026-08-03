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
 * inapplicability is declared rather than passed over in silence, because the
 * Explainability rule's line 39 forbids a docstring that omits parameters,
 * return values or purpose, and a reader has to be able to tell a declared
 * inapplicability from an oversight. Of the four docstring elements that rule
 * enumerates at its lines 18 to 21 -- Purpose, Parameters, Return values, and
 * Exceptions or errors -- exactly one applies to this compilation unit, and the
 * paragraph above discharges it.
 *
 * <h2>What this package holds</h2>
 *
 * <pre>
 * file                          holds
 * package-info.java             this charter
 * ApiError.java                 the problem shape, with the per-field array
 * GlobalExceptionHandler.java   the one advice that renders every failure
 * AbendDetail.java              the structured abend data equivalent
 * </pre>
 *
 * <p>Three production classes and one charter, four compilation units, and the
 * set is closed. There is no fourth production class here and none is to be
 * added: anything that would have been a fourth top-level type is a nested type
 * inside one of the three instead. The per-field entry nests inside
 * {@code ApiError}, beside the array that holds it, and any exception type this
 * module needs in order to recognise a conflict without taking a dependency on
 * a persistence framework nests inside the class that recognises it.
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
 * and this package models them as four. They are not collapsed into a single
 * width, and no one of them is treated as the canonical one from which the
 * others are truncated or padded. The reason is that each width belongs to a
 * different destination. Fifty is the common message and reason width. The
 * seventy-two-character abend message is the widest thing the abend block
 * carries. Seventy-five is the terminal message line, and it is the only one of
 * the four that survives into the migrated user interface as a rendering
 * constraint. Eighty is the diagnostic out-parameter of the date edit utility,
 * declared in its linkage section at {@code app/cbl/CSUTLDTC.cbl} line 86 as
 * {@code 01 LS-RESULT PIC X(80).} and passed by the procedure division header
 * on the line immediately after, so it is a call contract rather than a screen
 * contract at all. Truncating an eighty-character diagnostic into a
 * fifty-character message field would silently discard the end of the text that
 * explains the failure, and widening a fifty-character reason to eighty would
 * change the number of trailing blanks a fixed-width consumer receives. Both
 * are observable, so both are avoided by keeping the four separate.
 *
 * <p>Assumptions: fifty recurs at three unrelated sites, which makes it a house
 * convention rather than a coincidence, and the migrated constants are grouped
 * accordingly. The three sites are the two common messages of
 * {@code app/cpy/CSMSG01Y.cpy}, the abend reason of
 * {@code app/cpy/CSMSG02Y.cpy}, and the error-log message of the authorization
 * extension's {@code CCPAUERY.cpy}. Those three copybooks share no lineage,
 * serve three different subsystems, and were plainly not derived from one
 * another, so the agreement on fifty is a decision the baseline made more than
 * once and is carried forward as such.
 *
 * <p>Assumptions: the two common message literals are 49 characters long
 * against a declared width of 50, and both halves of that sentence matter. Each
 * literal was measured directly from {@code app/cpy/CSMSG01Y.cpy}: the
 * thank-you text at line 19 and the invalid-key text at line 21 are 49
 * characters each, so the baseline right-pads one further blank when the field
 * is loaded. Transformation rule T8 requires user-visible strings to be carried
 * across character for character, so the migrated constant holds the
 * 49-character literal exactly as written, and the declared width of 50 is
 * recorded separately as the padding contract rather than folded into the
 * literal. A constant that silently absorbed the extra blank would be neither
 * the source literal nor the loaded field, and would compare equal to neither.
 *
 * <p>Assumptions: transcribing those literals is a hand operation, not a
 * pattern match. In both cases the picture clause and the value clause sit on
 * separate lines -- lines 18 and 19 for the first, lines 20 and 21 for the
 * second -- so a reader who scans for a picture and a value on one line finds
 * the width and misses the text. The abend block has the same shape for all
 * four of its components, and that shape is the whole reason the block occupies
 * the exact line range recorded below.
 *
 * <p>Assumptions: the baseline contains two different thank-you strings, they
 * differ in three separate ways, and both are carried forward as distinct
 * constants. Neither is merged into the other and neither is treated as a
 * variant spelling of the other. The first is
 * {@code 05 CCDA-MSG-THANK-YOU PIC X(50)} at lines 18 and 19 of
 * {@code app/cpy/CSMSG01Y.cpy}, whose literal names the CardDemo application
 * and is 49 characters against its declared 50. The second is
 * {@code 05 CCDA-THANK-YOU PIC X(40)} at lines 23 and 24 of the 27-line
 * {@code app/cpy/COTTL01Y.cpy}, whose literal names the CCDA application and is
 * exactly 40 characters, filling its declared width with no padding at all. So
 * the two differ in declared width, in the application name inside the text,
 * and in whether the literal reaches its own declared width. Any one of those
 * three would be enough to make them separate strings; together they make
 * merging them a visible change to what a user reads. The forty-character width
 * belongs to the title band rather than to a message line -- the same copybook
 * declares two further forty-character title constants at lines 18 and 20 -- so
 * it is not a fifth message-width regime and is recorded here only because the
 * string that carries it is so easily mistaken for the fifty-character one.
 *
 * <p>Refactoring Rationale: the abend fields of {@code app/cpy/CSMSG02Y.cpy}
 * occupy lines 21 to 29. An earlier citation in circulation places them at
 * lines 45 to 53, and that citation cannot be right: the file is 35 lines long,
 * so the range it names does not exist. The extent recorded here was read from
 * the file. Line 21 opens {@code 01 ABEND-DATA.}; then
 * {@code ABEND-CODE PIC X(4)} at line 22 with its {@code VALUE SPACES} at line
 * 23, {@code ABEND-CULPRIT PIC X(8)} at lines 24 and 25,
 * {@code ABEND-REASON PIC X(50)} at lines 26 and 27, and
 * {@code ABEND-MSG PIC X(72)} at lines 28 and 29. Four components, two lines
 * each, which is precisely why eight declarations occupy nine lines from the
 * group item. The four sum to 134 bytes, and all four are initialised to
 * blanks. The stale range is recorded rather than quietly replaced because the
 * arithmetic is what settles it, and a reader who meets the old citation
 * elsewhere needs to know it was checked and found impossible rather than
 * merely disagreed with.
 *
 * <p>Assumptions: that same copybook announces itself as a different file. Its
 * line 2 reads {@code 000800* CABENDD.CPY}, which is not the name the file is
 * stored under and not the name any program uses to include it. The internal
 * title is a survival from an earlier name, and the file carries other evidence
 * of the same history: lines 1 to 4 still hold legacy sequence numbers 000700
 * through 001000 while lines 5 to 20 hold none, and lines 30 to 32 are bare
 * blank lines. The mismatch is recorded here so that a reader tracing the
 * lineage of {@code AbendDetail} searches for the storage name and is not
 * derailed by the title, and so that nobody looks for a second copybook under
 * the announced name and concludes it is missing.
 *
 * <p>Refactoring Rationale: the 75-character screen message line belongs to
 * {@code app/cpy/CVCRD01Y.cpy}, not to {@code app/cpy/CSMSG01Y.cpy}. The two
 * are easy to transpose because one is named for messages and the other is not,
 * and the misattribution matters because it would move the width onto a
 * copybook that declares no field of that width at all. The correct
 * declarations are {@code 10 CCARD-ERROR-MSG PIC X(75).} at line 28 and
 * {@code 10 CCARD-RETURN-MSG PIC X(75).} at line 29 of a 46-line file.
 *
 * <p>Assumptions: those two 75-character fields are not symmetric, and the
 * asymmetry is load-bearing. Line 30 declares
 * {@code 88 CCARD-RETURN-MSG-OFF VALUE LOW-VALUES.}, and that condition
 * attaches to the return message on line 29 alone. The error message on line 28
 * has no such condition and no sentinel of any kind. So the baseline can ask
 * whether a return message has been set, and cannot ask the same question of an
 * error message. {@code ApiError} therefore models one nullable aggregate
 * message whose absence is meaningful, and does not invent a second sentinel
 * for a field that never had one.
 *
 * <p>Trade-offs: of the four widths, only 75 is carried forward as a rendering
 * constraint, and the other three become documentation of provenance rather
 * than limits enforced at run time. A migrated message that a client renders in
 * a reflowing browser layout has no 24-row terminal to overflow, so enforcing
 * 50 or 72 on the wire would reject text no consumer would have struggled with,
 * while enforcing 80 would apply a call contract to a response body. What is
 * given up is the ability to prove by construction that a migrated message
 * would still have fitted the original screen. What is gained is that the one
 * width a client genuinely depends on is stated once and is not lost among
 * three that no client can observe. The logging side of this question is
 * deliberately not settled here: log field shapes belong to the migration's
 * observability notes, and duplicating them into this charter would create a
 * second source of truth for something this package does not own.
 *
 * <h2>Two different kinds of empty</h2>
 *
 * <p>Assumptions: the baseline distinguishes a message that was never set from
 * a message that was set to blanks, and this package preserves the distinction.
 * The two encodings sit side by side in the evidence. The abend block
 * initialises all four of its components to blanks, at lines 23, 25, 27 and 29
 * of {@code app/cpy/CSMSG02Y.cpy}. The screen return message instead uses a
 * low-values sentinel, at line 30 of {@code app/cpy/CVCRD01Y.cpy}. A field
 * filled with blanks and a field filled with low values are distinguishable in
 * the baseline and they mean different things: the first is a message that
 * exists and is empty, the second is a message that is off. Trimming both to an
 * empty string in the migrated model would erase a difference the baseline can
 * still see, and would leave no way to express message-off at all.
 *
 * <p>Assumptions: the same condition-name idiom carries both sentinel values in
 * the baseline, which is what makes the distinction worth stating rather than
 * assuming. A message-off condition is declared six times across the reference
 * programs, and every one of the six is a blanks sentinel: at line 250 of
 * {@code COTRTLIC.cbl} and line 168 of {@code COTRTUPC.cbl} in the
 * transaction-type extension, and at line 174 of {@code app/cbl/COCRDUPC.cbl},
 * line 118 of {@code app/cbl/COACTVWC.cbl}, line 480 of
 * {@code app/cbl/COACTUPC.cbl} and line 135 of {@code app/cbl/COCRDSLC.cbl}.
 * The shared copybook's condition, at line 30 of {@code app/cpy/CVCRD01Y.cpy},
 * is a low-values sentinel instead. Same role, two encodings:
 * the two sentinels are not interchangeable. Anyone mapping one of these fields
 * has to read the declaration that governs the field in front of them rather
 * than generalising from a similarly named condition elsewhere.
 *
 * <p>Assumptions: the sentinel is control flow and not decoration. In the date
 * edit copybook {@code app/cpy/CSUTLDPY.cpy}, the aggregate message is written
 * only under a message-off test, so the first failure to occur wins and later
 * failures leave the aggregate text alone. The four multi-field cases discussed
 * below each latch that way, at lines 218, 233, 263 and 305, and each latch
 * wraps a string assembly into the return message. If both empties were folded
 * into one, a message set to blanks would read as never set and the second
 * failure would overwrite the first, which changes which message a user sees.
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
 * and 19 of {@code app/cpy/CSSETATY.cpy} are a single disjunctive test -- the
 * not-acceptable condition or the never-supplied condition -- and both branches
 * lead to the same highlight. The blank path then does something extra rather
 * than something different: it sets a second marker on top of the first. So the
 * array this package publishes carries every offending field with the same
 * status, and distinguishes never-supplied from unacceptable-value as a
 * property of the entry rather than as a separate collection. Modelling blank
 * as a peer of error would oblige every client to handle three cases where the
 * baseline handles two.
 *
 * <p>Assumptions: the asterisk marker is presentational and never a domain
 * value, and the template proves it by writing the two things to two different
 * places. Line 21 moves the colour attribute into the field's colour subfield,
 * named with a trailing C at line 22. Line 24 moves the literal {@code '*'}
 * into the field's output subfield, named with a trailing O at line 25. One
 * targets how the field looks, the other targets what the field contains on the
 * next transmission. The migrated entry therefore records that a field was
 * never supplied, and leaves the marker to rendering; putting an asterisk into
 * a value would make a presentation artifact indistinguishable from data the
 * user typed.
 *
 * <p>Assumptions: one failure does not map to one field, and the fan-out varies
 * by case, so the array is genuinely variable length rather than uniformly
 * three-way. Two cases in {@code app/cpy/CSUTLDPY.cpy} mark two fields: a
 * thirty-first day in a thirty-day month at lines 213 to 217, and a thirtieth
 * of February at lines 228 to 232, each marking the day and the month. Two more
 * mark three fields: a twenty-ninth of February in a non-leap year at lines 259
 * to 262, and a rejection on the runtime-services path at lines 300 to 304,
 * each adding the year. A model that assumed a fixed fan-out would either drop
 * the year on the three-field cases or invent one on the two-field cases, and
 * either way the highlighted fields on the rendered form would stop matching
 * the baseline's.
 *
 * <p>Refactoring Rationale: the aggregate message and the per-field array are
 * two channels with two different arities, and {@code ApiError} carries them as
 * such -- one aggregate message, many field entries. The baseline already works
 * this way, and the mechanism is visible in the same copybook: the aggregate
 * text is written under the message-off latch and so records the first failure
 * only, while the field markers are set unconditionally and so accumulate
 * across every failure the chain finds. Collapsing the two into one channel is
 * the tempting simplification and it loses information in whichever direction
 * it is taken. Keeping only the aggregate discards every failure after the
 * first, so a form that has three bad fields highlights one. Keeping only the
 * array leaves the response with no single sentence to display where the
 * baseline displays one. Two channels reproduce what the baseline does, at the
 * cost of a response shape a client has to read in two places.
 *
 * <h2>The re-entry gate becomes nothing at all</h2>
 *
 * <p>Refactoring Rationale: the baseline gates its field highlight on a
 * conversational turn marker, and the migrated form has no such gate. Line 20
 * of {@code app/cpy/CSSETATY.cpy} adds a further condition to the disjunctive
 * test above it, requiring that the program be on a later turn before any field
 * is highlighted at all. The marker it tests is declared at lines 29 to 31 of
 * {@code app/cpy/COCOM01Y.cpy}: a one-digit field with two conditions, one for
 * a first entry and one for a later entry. What was wrong with carrying that
 * forward is that it has nothing to test. The migrated services are stateless,
 * so a handler that answers a validation failure with a response carrying a
 * field-error array has no first-entry and later-entry distinction available to
 * it and no place to keep one. Error presentation is therefore driven purely by
 * the response body: if the array names a field, that field is shown as being
 * in error.
 *
 * <p>Assumptions: that produces one deliberate behavioural difference, and it
 * is recorded rather than absorbed. On a first submission that fails
 * validation, the baseline reports the aggregate message without highlighting
 * the offending fields, because its gate has not opened yet; the migrated
 * services highlight them immediately. The baseline behaves one way, the
 * migrated code implements the other, and the divergence is documented in the
 * migration's traceability matrix. It also has a consequence for the shape of
 * this package that is worth stating positively: no type here holds a turn
 * count, a submission counter, a repeat-submission indicator or any other trace
 * of the conversational marker, and none is to be introduced under any name.
 * The array is a function of the request that produced it and of nothing that
 * came before.
 *
 * <h2>Severity and subsystem are adopted, not invented</h2>
 *
 * <p>The baseline already emits structured error records. The authorization
 * extension's {@code CCPAUERY.cpy} is a 40-line copybook whose group item is
 * declared at line 19, indented ten spaces, and it carries eleven fields
 * totalling 122 bytes: a date and a time of six each, an application name and a
 * program name of eight each, a four-character location, a one-character
 * severity, a one-character subsystem, two nine-character machine codes, a
 * fifty-character message and a twenty-character event key. Two of those fields
 * carry closed value domains:
 *
 * <pre>
 * severity  (line 25)  4 values, at lines 26 to 29
 *   L log       I info       W warning       C critical
 * subsystem (line 30)  6 values, at lines 31 to 36
 *   A application   C CICS   I IMS   D Db2   M MQ   F file
 * </pre>
 *
 * <p>Alternatives Considered: a fresh severity ladder was available and was
 * rejected in favour of the four values above. The obvious alternative is the
 * five-level ladder most logging frameworks default to, which would add a debug
 * level below the baseline's lowest and split its top level into an error level
 * and a fatal one. It was rejected because the four-value domain is the one the
 * existing operational material already speaks: a record written at warning
 * level by a baseline program and a record written at warning level by a
 * migrated service should be comparable without a translation table, and adding
 * a level nothing emits creates a rung that is always empty while splitting the
 * top rung creates two that nobody can tell apart after the fact. Four values,
 * unchanged, keeps the two systems legible side by side during the period when
 * both are running.
 *
 * <p>Assumptions: the subsystem domain maps unevenly and the gaps are named
 * rather than papered over. Application maps to application. Db2 becomes the
 * relational store, message-queue becomes the queue transport, and file becomes
 * the object store. Two of the six have no target analogue at all: CICS and
 * IMS, because the transaction monitor and the hierarchical database are
 * exactly the two platform components the migration replaces outright rather
 * than re-implements. Those two values are therefore never emitted by a
 * migrated service. They are kept in the documented domain all the same,
 * because a baseline record carrying either one still has to be readable by
 * whatever reads both, and silently dropping a value from a domain makes an old
 * record undecodable rather than merely unusual.
 *
 * <p>Assumptions: the correlation identifier is inherited, not invented. The
 * twenty-character event key at line 40 of that copybook is the baseline's own
 * means of tying the records emitted by one unit of work together, which is why
 * the sibling {@code web} package's correlation filter formalises an existing
 * concept rather than introducing a new one. This package consumes the result:
 * the identifier that arrives on a request is the identifier that appears on
 * the problem shape returned for it, so a client-reported failure can be found
 * in the operational record without the client having to quote a timestamp.
 *
 * <p>Refactoring Rationale: machine codes stay out of user-visible prose, and
 * the baseline is inconsistent on this point in a way worth being explicit
 * about. The structured record gets it right by construction: its two
 * nine-character code fields are declared at lines 37 and 38, separate from the
 * fifty-character message at line 39, so a consumer can match on a code without
 * parsing a sentence and can change the sentence without breaking the match.
 * Two other sites do the opposite. Lines 544 to 549 of the authorization
 * extension's {@code COPAUS1C.cbl} assemble a database return code directly
 * into text a user reads, and lines 306 to 313 of {@code app/cpy/CSUTLDPY.cpy}
 * assemble a severity and a message number into the aggregate message the same
 * way. What is wrong with the second pattern is that it makes one string serve
 * two audiences that need opposite things: an operator needs a stable token to
 * search for, and a user needs a sentence that can be reworded without anything
 * breaking. {@code ApiError} keeps a machine-readable code and a human-readable
 * message in separate members for that reason, and the structured record's own
 * layout is the precedent.
 *
 * <h2>Centralised error emission has an ancestor in the baseline</h2>
 *
 * <p>Refactoring Rationale: the single advice class in this package is not a
 * new idea imported from a framework; it is the baseline's own pattern with one
 * failure mode removed. The authorization consumer {@code COPAUA0C.cbl} is 1026
 * lines long and factors its error emission into one paragraph, defined at line
 * 983, which the rest of the program invokes from fourteen separate places:
 *
 * <pre>
 * lines 282, 316, 429, 500, 512, 547, 560, 595, 608, 639, 778, 846, 931, 975
 * </pre>
 *
 * <p>That list is kept whole on one line so that the count can be checked
 * against it by eye. Centralising the emission is therefore already settled
 * house practice, and the migrated form differs from it in exactly one respect:
 * the advice is reached by an exception propagating out of the handler rather
 * than by an explicit invocation written at each site. That single difference
 * is the whole benefit. Fourteen sites is fourteen opportunities to add a
 * fifteenth error path and forget the invocation, and a forgotten invocation is
 * invisible -- the program still works, and the record simply is not written.
 * An advice reached by propagation cannot be forgotten, because there is
 * nothing at the call site to omit.
 *
 * <p>Assumptions: factoring a cross-cutting concern into one named unit is a
 * baseline habit rather than a migration invention, and two further instances
 * in the same tree make that clear. The pending-authorization screen program
 * {@code COPAUS1C.cbl} factors its commit into a named paragraph at lines 557
 * to 560 and its rollback into another at lines 565 to 569, each wrapping a
 * single transaction verb. The templated highlight copybook discussed above is
 * a third instance, factored so far that it is a text substitution shared
 * across programs. The migrated structure follows the baseline's grain here,
 * which is why this package holds one advice rather than an error branch per
 * controller.
 *
 * <h2>Two conflicts, and both are HTTP 409</h2>
 *
 * <p>The advice in this package owns two mandatory conflict mappings. Both
 * exist because the alternative leaks something a client must not receive.
 *
 * <p>The first is an optimistic-lock conflict. The baseline already implements
 * this by hand, and understanding that is what makes the migrated version a
 * change of expression rather than a change of behaviour.
 * {@code app/cbl/COACTUPC.cbl} snapshots the whole pre-edit record into a
 * before-image group beginning at line 669, holding each numeric twice -- once
 * as a display field and once through a numeric redefinition of the same bytes,
 * as at lines 675 to 677 where a twelve-character balance field is redefined as
 * a signed value with two decimal places. It carries a change marker declared
 * at line 168 as {@code 05 WS-DATACHANGED-FLAG PIC X(1).}, whose two conditions
 * at lines 169 and 170 distinguish no-change from change-occurred. It commits
 * on the success path at lines 945 to 958. And when the rewrite fails it sets a
 * locked-but-failed state and issues a transaction rollback at lines 4097 to
 * 4103 before branching to the write exit. The migrated services express the
 * same intent natively, through a version column the persistence layer
 * maintains, and this package maps the resulting conflict to HTTP 409. Nothing
 * is lost in the translation, because the baseline's read-for-update lock was
 * never held across the user's thinking time in the first place -- which is
 * precisely why the before-image had to exist.
 *
 * <p>Assumptions: two citations on that program are routinely conflated and
 * they are different constructs. Line 168 is the one-character change marker,
 * and its conditions are at lines 169 and 170. Lines 521 and 522 are something
 * else entirely: a message-valued condition on a message field, one member of
 * an eleven-member family occupying lines 507 to 528, whose value is the text a
 * user sees. Any reading that treats the second as a condition on the first is
 * wrong, and the difference matters to this package because only one of the two
 * becomes a message constant. The verbatim text at line 522 is
 * {@code Record changed by some one else. Please review}. Three things about it
 * are carried across unchanged: it is 46 characters, measured from the literal
 * itself; it has no terminating period, the period visible after the closing
 * quote in the source being the statement terminator rather than part of the
 * text; and the two-word spelling of the third-party pronoun is preserved
 * exactly as written, the same spelling appearing again at line 286 of
 * {@code app/cpy/CSUTLDPY.cpy}. Transformation rule T8 requires user-visible
 * strings to be carried character for character, and a spelling regularised on
 * the way through would be a silent behavioural change of exactly the kind that
 * rule exists to prevent.
 *
 * <p>Assumptions: several other commit boundaries in the baseline bear on the
 * same mapping and are recorded so that the 409 is not read as belonging to one
 * program. {@code app/cbl/COCRDUPC.cbl} commits at line 470. The authorization
 * consumer commits once per message at line 335 of {@code COPAUA0C.cbl}. The
 * pending-authorization screens commit at line 686 of {@code COPAUS0C.cbl},
 * where the commit is guarded and also releases a hierarchical-database
 * resource, and at lines 557 and 558 of {@code COPAUS1C.cbl}, whose rollback
 * path and its own verbatim message are at lines 545 and 563. Each of those is
 * a place where a concurrent change can be discovered, so each is a place the
 * same conflict status has to be produced.
 *
 * <p>Refactoring Rationale: the second mapping is a restrict-on-delete
 * violation, and it becomes a 409 with a message rather than the database's own
 * complaint. The reference-data foreign key that preserves the baseline's
 * existing restrict semantic makes deleting a transaction type impossible while
 * categories still point at it. What is wrong with letting that surface as it
 * arrives is that a driver's constraint-violation text names the schema, the
 * table and the constraint, so the raw form both fails to tell the caller what
 * to do about it and tells an untrusted caller the internal shape of the store.
 * The advice recognises the violation and answers with the same status the
 * optimistic-lock conflict uses and a message describing the business rule.
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
 * <h2>The count canon</h2>
 *
 * <p>Assumptions: the shared kernel holds 17 production classes and 9 package
 * charter files, for 26 compilation units in total. This package contributes 3
 * of those production classes and 1 of those charters, so the directory holds
 * 3 + 1 = 4 compilation units when complete. The arithmetic is recorded so that
 * a later reader can tell a class that is missing from one that was never
 * planned, and so that a stale total cannot survive next to a breakdown that
 * re-derives it:
 *
 * <pre>
 * money 2 + codec 5 + error 3 + web 2 + security 1 + observability 1 + time 1 + validation 2 = 17
 * </pre>
 *
 * <p>Cross-check by compilation unit, counting one charter per package plus
 * that package's production classes: 1 + 3 + 6 + 4 + 3 + 2 + 2 + 2 + 3 = 26,
 * the root package contributing its charter alone. And 17 production classes
 * plus 9 charters is 26. All three paths agree, this file is one of the nine
 * charters, and the 4 in the fourth position of that second sum is this
 * directory.
 *
 * <h2>The dependency arrow, and why the shared kernel exists</h2>
 *
 * <p>Trade-offs: this package depends on the sibling {@code validation} package
 * and that package never depends on this one, and the one-way arrow is a
 * constraint accepted rather than a fact observed. The per-field flag is set by
 * a validator while it is deciding whether a field is acceptable; the array is
 * assembled here only once a response is being built. Pointing the arrow the
 * other way would gather the whole error model into one package, which is
 * tidier, and it would mean a validator could not run without the web-facing
 * response model on its path -- so the date edit rules would stop being
 * testable as pure logic. What this direction costs is that the two halves of
 * one contract live in two packages, and a reader following the contract has to
 * follow it across a boundary. What it buys is that the validator has no
 * dependency of its own at all. This package may also use the {@code money} and
 * {@code time} siblings, and must not be depended upon by {@code validation},
 * {@code codec}, {@code money} or {@code time}.
 *
 * <p>Assumptions: that layering has exactly one owner, and it is a test:
 * {@code services/common-lib/src/test/java/com/carddemo/common/architecture/LayeringRulesTest.java}.
 * It is a test precisely so that it cannot rot into a comment nobody runs. The
 * audit configuration's import-restriction module is deliberately not enabled
 * and must not be added, because two owners would mean two files to change
 * whenever a boundary moves and no way to tell from either which is
 * authoritative. A test also names the offending class together with the
 * offending dependency, which is the information needed to act on it.
 *
 * <p>Trade-offs: nine package roots exist across the migrated code base -- this
 * shared kernel and the eight bounded contexts, one root each for the sign-on
 * and user context, the account context, the card context, the transaction
 * context, the reference-data context, the batch context, the pending
 * authorization context and the reporting context. The arrow between them runs
 * one way only: all eight depend on this module and this module depends on none
 * of them, and the shared kernel is the only intra-reactor dependency a service
 * is permitted to declare. Those eight roots are referred to collectively
 * throughout this file and are never spelled out, because no compilation unit
 * here may reference one in any form -- not an import, not a fully qualified
 * name, and not a string literal resolved at run time. The last of those three
 * is the reason for the naming restraint, and it bites hardest on this package:
 * the advice discriminates some exception types by class name in order to avoid
 * a compile-time dependency on a framework it does not require, so only
 * third-party, platform and standard-library names may appear in such a string.
 * Describing the roots rather than listing them costs a sentence that reads
 * less directly than a dotted path would; it buys a charter that does not
 * itself match the audit search for the very dependency this tree is forbidden
 * to have, which would otherwise need explaining away on every audit.
 *
 * <p>Alternatives Considered: each service could have declared the error shape
 * it needs for itself, and that was rejected for the reason the baseline
 * settles the same question. The reference programs resolve every layout
 * through one compiler include path, recorded at line 268 of
 * {@code tests/README.md} as
 * {@code cobc -fixed -fsign=EBCDIC --std=ibm-strict -I app/cpy}, and lines 540
 * to 542 of that file state the discipline it buys: "COBOL unit tests resolve
 * record layouts through the compiler copybook path ({@code cobc -I app/cpy})
 * via {@code COPY CVTRA06Y.} / {@code COPY CVACT01Y.} -- never duplicate a
 * layout; keep it single-sourced from {@code app/cpy/}." Transformation rule T2
 * says the same for this side, verbatim: "One {@code COPY} becomes one import
 * ... Shared concerns -- codecs, money, errors, validation flags -- live only
 * in {@code common-lib}." And the migration plan's cross-file dependency
 * analysis, verbatim: "Shared concerns are never re-declared per service ...
 * This is the Java analogue of compiling every COBOL program with a single
 * copybook include path, and it is the reason {@code common-lib} exists at
 * all." Eight service-local problem shapes would drift, and the day two of them
 * disagree the symptom is a client that parses seven services' failures and
 * mishandles the eighth.
 *
 * <p>Assumptions: both quotations above are reproduced with an ASCII
 * hyphen-minus where the source carries a non-breaking hyphen and with a pair
 * of ASCII hyphens where it carries a long dash. The wording is unchanged and
 * only those punctuation code points are normalised. The substitution is
 * declared so that it reads as deliberate rather than as a transcription slip;
 * the authoring notes at the foot of this charter give the reason the whole
 * file is ASCII.
 *
 * <h2>Message text is Java constants</h2>
 *
 * <p>Trade-offs: every message this package carries is a Java constant, and
 * none is externalised to a properties file or a resource bundle. That is not a
 * preference; it follows from what this module is. {@code common-lib} is a
 * library rather than a deployable, so it has no resources directory beside its
 * sources, no application configuration of any kind, no container definition,
 * no interface contract directory and no schema migration directory. A message
 * moved into a bundle would need one of those, which would turn a library into
 * something that has to be configured before it can be used. What is given up
 * is the ability to change a message without recompiling, and the loss is small
 * here because these strings are not meant to change: transformation rule T8
 * requires them to match the baseline character for character, so a deployment
 * that could reword one is a deployment that could break parity. What is gained
 * is that a constant is compiled, greppable, and assertable by a unit test
 * against the copybook line it came from.
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
 * {@code app/cpy/CVACT01Y.cpy}, the 300-byte account record; it is zoned
 * decimal with a sign overpunch rather than packed decimal, and lines 273 and
 * 274 of {@code tests/README.md} record that compiling with the default sign
 * convention instead of the EBCDIC one "misreads the zoned-decimal sign
 * overpunch and silently corrupts negative balances".
 *
 * <p>Trade-offs: the string form on the wire is the part that looks like
 * fussiness and is not. A JSON number is parsed into IEEE-754 binary by most
 * clients, which destroys exactness at the boundary the user actually sees, so
 * an amount that was exact through the store and the service becomes
 * approximate in the one place a person reads it. The cost accepted is a client
 * that must convert before arithmetic. The two forbidden numeric type names are
 * described rather than spelled anywhere in this file, because spelling them
 * would make this charter match a search for the very tokens the money path
 * must not contain, and that search is one of the checks this tree is audited
 * with. The enforcement is the architecture test named above; prose cannot fail
 * a build and a test can.
 *
 * <h2>How this package is verified</h2>
 *
 * <p>Assumptions: the repository's COBOL oracle suite covers batch flows, so it
 * cannot verify most of this package. The problem shape and the advice are
 * request-facing concerns, and the baseline's online programs cannot be driven
 * end to end without a transaction monitor, which the runner does not have.
 * Both are therefore verified by unit tests under this module's own test tree
 * at {@code services/common-lib/src/test}, and no golden-master backing is
 * claimed for them. One part is an exception: the message text is verifiable
 * directly against the copybooks, without running anything, and is asserted
 * character for character against the lines cited in this charter. Recording
 * the boundary matters because claiming oracle backing for the whole package
 * would overstate the evidence behind the part that has none.
 *
 * <p>Assumptions: two directories are called tests and they are not the same
 * thing. The repository root's {@code tests} directory is the COBOL three-layer
 * functional-parity oracle suite, with its own 590-line guide and its own
 * fixtures, goldens, helpers and mocks. This module's test tree is
 * {@code services/common-lib/src/test}. Neither substitutes for the other, and
 * work on one does not modify the other. The oracle suite also grades itself on
 * a mainframe condition-code rubric in which a warning-level result is its
 * green state, and that rubric belongs to it alone: this module's build is
 * binary, and the compiler, the documentation gate and the test runner each
 * pass or fail.
 *
 * <h2>Authoring notes for this file</h2>
 *
 * <p>Trade-offs: this file is pure ASCII with no byte-order mark. The
 * alternative was to reproduce the typographic dashes the migration plan's own
 * prose uses, which reads closer to that prose. ASCII was chosen because this
 * charter quotes text carrying a non-breaking hyphen, and a non-breaking hyphen
 * is indistinguishable from an ordinary one on screen while behaving
 * differently in a search -- it is what would quietly turn a label into a token
 * that a search for that label fails to find. Restricting the whole file to
 * ASCII makes that failure mode unreachable and keeps the bytes stable under
 * any default charset, at the cost of plainer punctuation. The build declares
 * UTF-8 for both the source encoding and the audit charset, so ASCII is a
 * strict subset of what is configured and nothing is lost mechanically.
 *
 * <p>Trade-offs: this file contains one Javadoc block and one package
 * declaration and nothing else -- no type, no annotation, no import and no line
 * comment. It is not a module descriptor: no module descriptor exists anywhere
 * in this repository and none is introduced. It carries no authorship, release
 * or revision at-clauses either, because the audit omits the entire
 * Javadoc-formatting family that would ask for them and the version control
 * history answers those questions more reliably than a comment maintained by
 * hand. It likewise carries no parameter, return or exception at-clause, for
 * the reason given in the second paragraph of this charter.
 *
 * <p>Trade-offs: prose is wrapped at 80 columns to match the sibling charters
 * in this tree, even though the audit enables no line-length check and so does
 * not require it. Two lines exceed that width deliberately and should be left
 * alone. One is the production-class cross-check in the count canon above, kept
 * whole so that a reader can check the sum by eye and a search can match it
 * entire. The other is the path to the architecture test, which cannot be
 * broken because a line break inside an inline code span would insert this
 * comment's own margin into the rendered path. Three shorter items are held
 * whole for the same reason and do fit: the compilation-unit cross-check, the
 * fourteen call sites, and the sentence stating that the two sentinels differ.
 * In each case rewrapping would trade something a reader or a search uses for a
 * rule the build does not apply.
 *
 * <p>Assumptions: the labelled sentences throughout this file use the four
 * category names in the plural, unparenthesised form the Explainability rule
 * itself uses at its lines 31 to 34, with the trailing colon retained and no
 * emphasis markers. A reader who searches the wider repository meets the
 * singular form more often, and inside comments meets a parenthesised singular
 * most often of all, so the form here could be mistaken for a departure from
 * house style. It is not. The rule's line 43 makes its own wording the sentence
 * this tree is audited against, and forms are never mixed inside one file: this
 * Java tree uses the plural labels exclusively, while the shell, infrastructure
 * and COBOL-adjacent artifacts keep the singular in-file forms they were
 * authored with. The rule's inline twin-comment idiom has no application here,
 * because a charter has one declaration and no statements to annotate; the
 * labelled sentence is its in-Javadoc equivalent.
 *
 * <p>Assumptions: two identifier namespaces collide by number and are kept
 * textually distinct throughout. The single user-specified rule is
 * Explainability and is cited only by its line numbers. The migration plan's
 * transformation rules are numbered T1 to T10 and are always written with the
 * T. The distinction is load-bearing rather than pedantic here: this package's
 * published shape is governed by T7, its message text by T8, its imports by T2
 * and its money by T3, while its documentation is governed by the rule's line
 * 43. T1 is an unrelated rule holding that the copybook layouts are normative,
 * and T9 is the licence for the reshaping described above and the limit on it
 * -- structure changes, behaviour does not.
 *
 * <p>Assumptions: every citation in this charter names a file and a line, a
 * declared width or a measured byte count, because the rule's line 41 forbids a
 * vague rationale and a labelled sentence with nothing to open would be one.
 * Where a figure here disagrees with a figure in circulation upstream -- the
 * extent of the abend block, and the length of the conflict message -- the
 * figure recorded is the one read or measured from the file, and the
 * disagreement is stated in the paragraph concerned so that a later reader can
 * re-derive it rather than choose between two unsourced numbers.
 *
 * <p>Assumptions: nothing beneath {@code app} is altered by this migration,
 * this package included. The copybooks and programs cited throughout are
 * reference only and stay byte-identical, because the baseline is the
 * behavioural oracle and has to remain usable as one. Where the migrated code
 * behaves differently from a baseline program -- the dropped conversational
 * gate above being this package's one instance -- the framing is always that
 * the baseline does one thing, the Java implements another, and the divergence
 * is documented in the migration's traceability matrix.
 */
package com.carddemo.common.error;
