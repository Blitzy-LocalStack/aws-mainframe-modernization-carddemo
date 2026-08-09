/**
 * Owns exact fixed-point money and its JSON wire form for every migrated CardDemo service.
 *
 * <p><b>Purpose.</b> Two production classes live here and nothing else. {@code Money} is the value
 * type that carries a monetary amount in memory and performs arithmetic on it; {@code MoneyModule}
 * is the Jackson module that decides how such an amount crosses an API boundary. Every monetary
 * amount anywhere in the migrated system -- a balance, a credit limit, a cycle credit or debit, a
 * transaction amount, an accrued interest figure -- is created, combined, persisted and serialised
 * through those two types. The package therefore owns three contracts rather than merely two
 * classes: the representation money is held in, the rounding applied when a result must be reduced
 * to cents, and the form money takes on the wire. Each is stated below with the reference artefact
 * it derives from, because the migration plan calls fixed-point and character-set fidelity the
 * highest-risk area of the whole effort, on the ground that an error here is silent and yields
 * numbers that look plausible and are wrong in the cents.</p>
 *
 * <p><b>Parameters, return values, exceptions or errors.</b> A package declaration accepts no
 * parameters, returns no value and raises nothing, so this descriptor deliberately carries no
 * parameter, return or exception at-clause. The vacancy is declared rather than left silent because
 * the Explainability rule's line 39 forbids a docstring that omits parameters, return values or
 * purpose, and a reader has to be able to tell a declared vacancy from an oversight. Stating the
 * elements as empty at-clauses instead would not help: the {@code NonEmptyAtclauseDescription}
 * module rejects an at-clause that carries no description, so a fabricated tag would fail the same
 * gate it was written to satisfy.</p>
 *
 * <h2>The normative money field</h2>
 *
 * <p>One reference declaration fixes the shape of money for the whole migration. Line 7 of
 * {@code app/cpy/CVACT01Y.cpy}, the 300-byte account record, declares
 * {@code 05  ACCT-CURR-BAL                     PIC S9(10)V99.} -- ten integer digits, two decimal
 * digits, and a sign. Three properties of that declaration propagate into every decision this
 * package makes:</p>
 *
 * <ul>
 *   <li>It is <em>zoned decimal with a sign overpunch</em>, not packed decimal. The sign is carried
 *       by overpunching the final digit position rather than by a separate nibble, which is why the
 *       reference build convention recorded at line 268 of {@code tests/README.md} passes an EBCDIC
 *       sign option: read under the other convention the same bytes yield the wrong sign on a
 *       negative balance, silently.</li>
 *   <li>It is <em>decimal, not binary</em>. Two places after an implied decimal point is an exact
 *       property of the declaration, not a display preference, so the target representation has to
 *       be exact at two places as well.</li>
 *   <li>It is <em>the same shape across the base masters</em>. The eleven base-master copybooks
 *       carry no {@code COMP} usage and no {@code OCCURS} clause at all, so no base-master money
 *       field is packed and none is an array. Packed decimal does occur in the migration, but only
 *       outside those records: in the export record layout and in the two authorization segment
 *       layouts. That split is the reason decoding lives in the sibling {@code codec} package,
 *       which carries two distinct numeric codecs, while this package carries none.</li>
 * </ul>
 *
 * <h2>Contract one: money never leaves fixed point</h2>
 *
 * <p>Transformation rule T3 pins one representation per layer and admits no exception:
 * {@code NUMERIC(p,2)} in the database, {@code BigDecimal} carried at scale 2 in Java,
 * {@code Decimal} in the extract-transform-load code, and a JSON <em>string</em> on the wire.
 * IEEE-754 binary arithmetic is excluded from the money path entirely -- neither of the language's
 * two binary primitive types, neither of their wrapper types, and never a bare JSON number either.
 * The exclusion is architecture-tested by {@code LayeringRulesTest} in this module's test tree, so
 * it fails a build rather than a review.</p>
 *
 * <p>Trade-offs: the excluded numeric type names are described in the previous paragraph rather
 * than spelled. Spelling them would make this descriptor match a search for the very tokens the
 * money path must not contain, and that search is one of the checks this tree is audited with, so
 * a literal mention would produce a hit that has to be explained away on every audit. The
 * description is unambiguous, since the language has exactly two IEEE-754 binary primitive types
 * and one wrapper type for each, so the cost of the circumlocution is paid in reading effort and
 * buys an audit that stays clean. The convention is not invented here: the package root descriptor
 * at {@code com/carddemo/common/package-info.java} states the same prohibition the same way, and a
 * search of either file for those type names returns nothing, so the two are consistent by
 * construction rather than by coincidence.</p>
 *
 * <h2>Contract two: two rounding modes, one per operation, and why it is not one</h2>
 *
 * <p>Every reduction of a monetary result to cents in this package uses scale 2, and which mode
 * performs it is a property of the operation rather than of the caller. Reducing a supplied amount,
 * general multiplication and general division use {@code RoundingMode.HALF_UP}, exposed as
 * {@code Money.GENERAL_ROUNDING}. Interest accrual uses {@code RoundingMode.DOWN}, exposed as
 * {@code Money.BASELINE_INTEREST_ROUNDING}. Neither is reachable from any signature, so a call site
 * cannot select between them and cannot be asked to. The accrual path differs from the others in
 * both the <em>order</em> of its operations, described below, and its rounding.</p>
 *
 * <p>Assumptions: the split follows the reference source, and it is the asymmetry rather than the
 * modes that carries the meaning. The baseline performs exactly one monetary computation, and the
 * accrual quotient is it. Lines 462 to 468 of {@code app/cbl/CBACT04C.cbl} hold the accrual
 * paragraph, whose statement is
 * {@code COMPUTE WS-MONTHLY-INT = ( TRAN-CAT-BAL * DIS-INT-RATE) / 1200}. The receiving field is
 * declared at line 168 of the same program as {@code 05 WS-MONTHLY-INT            PIC S9(09)V99.},
 * so the result is stored at exactly two decimal places and surplus precision has to go somewhere;
 * and the statement carries no {@code ROUNDED} phrase, nor does any other statement in the program,
 * because a search for that phrase across all 652 lines returns no match. A store into a
 * fixed-scale field without that phrase discards the surplus digits rather than rounding them, so
 * the baseline behaviour is truncation toward zero, and that is what the accrual mode reproduces.
 * The three general operations have no reference statement at all -- they are target arithmetic with
 * no baseline counterpart -- so nothing constrains their mode and transformation rule T3's half-up
 * applies to them unopposed.</p>
 *
 * <p>Refactoring Rationale: this package applied half-up to the accrual as well, and registered the
 * resulting cent as a documented divergence identified {@code C-ROUNDING}. That disposition is
 * withdrawn and the divergence is closed; the identifier is retained only as a withdrawal record in
 * {@code docs/architecture/cobol-to-service-traceability.md} so that it still resolves for a reader
 * who meets it in an older comment. Reading rule T3's "half up for the whole money path" as covering
 * the accrual put the letter of a transformation rule above the requirement it exists to serve: the
 * plan requires observable behaviour to be unchanged, names the exact interest formula among the
 * rules that must be preserved, and admits a behavioural change only as an explicitly authorised
 * divergence. What T3 actually forbids -- binary floating point in the money path, and a
 * caller-selectable mode -- is still forbidden here and is still asserted mechanically: the mode is
 * fixed at the type, unreachable from every signature, and applied to an exact decimal.</p>
 *
 * <p>Refactoring Rationale: the cent was not the whole of the cost, and understating it is what made
 * the divergence look acceptable. The accrual is one of the business rules the reference test suite
 * asserts verbatim, so a cent of drift in it is a functional-parity failure in the most heavily
 * asserted computation in the system. It also compounds rather than staying local: line 467 of
 * {@code app/cbl/CBACT04C.cbl} adds each row's reduced result into the account total and line 352
 * adds that total to the account balance once per account, so a cent gained per transaction category
 * reaches the balance the next over-limit comparison is made against, and that comparison is
 * inclusive.</p>
 *
 * <p>Trade-offs: two modes cost a reader having to know which operation is governed by which, where
 * one mode cost nothing to explain and a cent in the one computation that matters most. The cost is
 * paid down three ways: exactly one operation sits on the truncating side, each constant is named
 * for the operation it governs rather than for a general policy, and the vector that discriminates
 * the two is asserted rather than described. On the vectors the reference fixtures actually carry
 * the two modes agree, which is why a discriminating test has to be constructed deliberately: the
 * happy-path interest fixture supplies a category balance of {@code 1000.00} and a disclosure-group
 * rate of {@code 15.00}, the formula yields {@code 12.5000} exactly and both modes return
 * {@code 12.50}; at a rate of {@code 2.50} against the same balance the quotient is
 * {@code 2.08333...} and both return {@code 2.08}. They part company on an exact half cent, as with
 * a balance of {@code 1000.80} at a rate of {@code 2.50}: the quotient is {@code 2.0850} exactly,
 * truncation returns {@code 2.08} and half-up returns {@code 2.09}.</p>
 *
 * <p>Alternatives Considered: exposing the mode as a parameter on the accrual and letting the caller
 * select it. Rejected because the choice would then live at the call site, where the next
 * accrual-adjacent caller would face a decision with no basis for making it, and because two call
 * sites computing the same accrual could disagree by a cent with nothing in either one signalling
 * that they had chosen differently. Also considered: amending the migration plan to admit
 * truncation. Rejected outright -- the plan is frozen and is the agreed contract. Neither is
 * necessary: fixing the mode per operation satisfies the plan's parity requirement and its
 * prohibitions at the same time, without relocating the decision to a caller or to the plan.</p>
 *
 * <p>Assumptions: the multiply-before-divide order is part of the same contract and is not an
 * implementation detail, per transformation rule T4. The reference statement multiplies the balance
 * by the rate and only then divides by 1200, so the product is formed at full precision. Reversing
 * the order and dividing first forces an intermediate result to a scale before the multiplication
 * consumes it: on the balance and rate just given, multiplying first yields {@code 2.0850} while
 * dividing first at an intermediate scale of two yields {@code 2.0750}, a difference of two cents
 * from reordering alone, and no rounding mode recovers it. The order is therefore preserved
 * literally, and the one reduction happens after the division and nowhere else.</p>
 *
 * <h2>Contract three: a JSON string on the wire</h2>
 *
 * <p>{@code MoneyModule} serialises a monetary amount as a JSON string and deserialises it from
 * one. This is the third contract the package owns, and it is the one most often mistaken for
 * fussiness.</p>
 *
 * <p>Assumptions: the module is written against the Jackson 3 API, under the {@code tools.jackson}
 * group, and that generation choice is load-bearing rather than incidental. Spring Boot 4.1 version
 * manages both Jackson lines but builds its default HTTP message converter from the 3.x mapper, and
 * a module implementing the 2.x module type is not a module that mapper recognises. Written against
 * the older generation this class would compile, package and pass every unit test, then fail to be
 * registered on the converter that actually serialises a response -- and the amount would fall back
 * to a plain {@code BigDecimal} serialisation, which is a bare JSON number: exactly the outcome the
 * paragraph below rejects, arrived at silently. {@code services/common-lib/pom.xml} therefore
 * declares the {@code tools.jackson.core} coordinate, and no module in this tree declares the 2.x
 * one, so a single mapper generation serves the whole migration.</p>
 *
 * <p>Alternatives Considered: emitting money as a JSON number was evaluated and rejected. A JSON
 * number carries no scale and no exactness guarantee, and the majority of clients parse one into an
 * IEEE-754 binary value on receipt. That is not a theoretical loss, and the normative field makes
 * the size of it concrete: {@code PIC S9(10)V99} at line 7 of {@code app/cpy/CVACT01Y.cpy} is
 * twelve significant decimal digits with a scale of exactly two, and the two-place decimal
 * fractions such a field is built from -- {@code 0.01} among them -- have no finite binary
 * expansion, so a binary parse cannot represent the value it was handed and must approximate it.
 * Every guarantee the two preceding contracts establish would then be discarded at the last hop, at
 * the boundary the user actually sees, and discarded silently -- the amount would still look like
 * money. Quoting the amount moves the parse decision to the client's application code, where a
 * decimal type can be chosen deliberately, at the cost of one pair of quote characters per amount
 * and of a client that must convert before arithmetic. That cost is accepted because the
 * alternative trades an invariant enforced in three layers for the convenience of one.</p>
 *
 * <p>Assumptions: the string form is continuous with the reference system rather than a departure
 * from it. The baseline already transports money as text on a message wire. Line 27 of
 * {@code app/app-authorization-ims-db2-mq/cpy/CCPAURQY.cpy} declares
 * {@code 05  PA-RQ-TRANSACTION-AMT        PIC +9(10).99.} -- an edited display field of exactly
 * fourteen characters, being one sign position, ten integer digits, a literal decimal point and two
 * decimal digits. That copybook is the eighteen-field authorization request, whose fields occupy
 * lines 19 through 36 and whose amount field is thus character data, not a numeric type, while the
 * same logical amounts are held in packed decimal in the authorization segment layouts. The
 * migrated system preserves exactly that division of labour: exact fixed point in storage and in
 * arithmetic, signed decimal text on the wire.</p>
 *
 * <h2>Where the baseline exercises these contracts</h2>
 *
 * <p>Two reference programs are the reason these contracts are stated as contracts. Their line
 * ranges are cited so that a maintainer can read the behaviour rather than infer it. Both files are
 * reference material: they are read and cited, never edited.</p>
 *
 * <ul>
 *   <li>{@code app/cbl/CBACT04C.cbl} lines 462 to 468 hold the interest accrual described above,
 *       and lines 436 to 438 hold the disclosure-group fallback that supplies the rate it consumes:
 *       when the group lookup reports status {@code '23'} the program moves the literal
 *       {@code 'DEFAULT'} into the group key and retrieves the default rate. The fallback matters
 *       here because it decides <em>which</em> rate reaches the formula, and the accrued figure is
 *       only reproducible if the rate is.</li>
 *   <li>{@code app/cbl/CBTRN02C.cbl} lines 403 to 420 hold the two posting boundary comparisons. A
 *       working balance is computed as the cycle credit less the cycle debit plus the transaction
 *       amount, and that balance is then compared against the credit limit with {@code >=}, so a
 *       balance landing exactly on the limit passes and only an excess is rejected. The expiration
 *       test on the following lines is inclusive in the same way. Both comparisons are decisions
 *       taken on money and on a date at their exact declared scale, which is what makes an
 *       inexact representation upstream a correctness problem rather than a display one: a value
 *       that is wrong in the last cent flips an inclusive comparison at the boundary.</li>
 * </ul>
 *
 * <h2>Contents of this package</h2>
 *
 * <p>This package holds exactly two production classes, {@code Money} and {@code MoneyModule}, and
 * with this descriptor beside them the directory holds exactly three {@code .java} files:</p>
 *
 * <pre>
 * this directory: 3 java files = 2 classes + 1 charter
 * </pre>
 *
 * <p>There is
 * no fourth file here and none is to be added: the arithmetic contract and its wire-format
 * companion are the whole of the concern, and a third type would be either a second money
 * representation or a concern belonging to another package. Across {@code com.carddemo.common} as a
 * whole the module holds six production classes in {@code codec}, four in {@code control}, seven in
 * {@code error}, three in {@code messaging}, two in {@code money}, three in {@code observability},
 * eight in {@code security}, one in {@code time}, two in {@code validation} and three in
 * {@code web}, the package root contributing one -- the auto-configuration class that registers this package's codec
 * module, and the {@code Clock}, the cursor-token signer, the correlation filter, the meter filter
 * and the error advice, in every service. The two totals that follow are each kept whole on one line
 * so that either can be checked by eye, matched by a search without a line break splitting it, and
 * read back by the drift test named below:</p>
 *
 * <pre>
 * production classes:  1 + 6 + 4 + 7 + 3 + 2 + 3 + 8 + 1 + 2 + 3 = 40
 * compilation units:   40 production + 11 package descriptors = 51
 * </pre>
 *
 * <p>The eleven descriptors are one for the package root and one for each of its ten subpackages.
 * The figures are recorded so that a class absent from the module stays distinguishable from one
 * the module never held.</p>
 *
 * <p>Refactoring Rationale: every figure above was previously a TARGET rather than a measurement,
 * and the paragraph that said so argued that a target "keeps this paragraph true at every point in
 * that sequence". That reasoning held only while the tree was a subset of the target. It is not: the
 * module now holds forty production classes against a target of twenty-one, and one whole
 * subpackage -- {@code messaging}, with {@code MessageExpiry}, {@code MessagingCorrelationId} and
 * {@code QueueClientBudget} -- that the target never named at all. A target that the delivery
 * has overshot is not a forgiving description of the delivery; it is a false one, and it fails in
 * the direction that matters, because a reader consults this block to learn whether a type they
 * cannot find is missing or was never admitted, and an under-stated inventory answers "never
 * admitted" about a class that is right there.</p>
 *
 * <p>Refactoring Rationale: the inventory is now a MEASUREMENT, and
 * {@code com.carddemo.common.architecture.SharedKernelInventoryTest} re-derives it from the
 * directory on every build and fails if this block disagrees. Alternatives Considered: deleting the
 * inventory instead, on the argument that a directory listing already reports the present state and
 * cannot fall out of date. Rejected because the listing answers a different question: it says what
 * is there, not what belongs there, so it cannot distinguish a type that was added deliberately from
 * one that drifted in from another concern -- which is the judgement this block exists to record.
 * Mechanising it keeps the judgement and removes the staleness, where deleting it would keep the
 * staleness problem solved and lose the judgement.</p>
 *
 * <h2>Why this descriptor exists</h2>
 *
 * <p>Assumptions: this file is required by two independent external contracts, and neither is
 * redundant. The first is the project Explainability rule, whose line 15 requires a docstring on
 * every module entry point; a Java package declaration is that entry point, and
 * {@code package-info.java} is the only compilation unit in which package-level Javadoc can be
 * carried, so the file is required rather than decorative. The second is the Checkstyle
 * configuration at {@code config/checkstyle/checkstyle.xml}, which enforces the same requirement in
 * two halves that a reader will otherwise mistake for a duplicate. The {@code JavadocPackage}
 * module sits at Checker level, inspects the file system, and requires that this file <em>exist</em>
 * in any directory holding an audited source file; the {@code MissingJavadocPackage} module sits
 * inside {@code TreeWalker}, inspects the parsed Javadoc tree, and requires that the file
 * <em>carry</em> a Javadoc block on its package declaration. An empty descriptor satisfies the
 * first and fails the second, so both are needed and neither can be removed as surplus.</p>
 *
 * <p>Assumptions: the consequence of deleting this file is immediate and local, which is the whole
 * reason it is worth stating. The Checkstyle plugin runs under the execution
 * {@code checkstyle-documentation-gate}, bound to the Maven {@code validate} phase with violations
 * failing the build and the threshold set at warning severity. Binding to {@code validate} places
 * the gate before compilation, and {@code common-lib} is declared first among the nine reactor
 * modules, so removing or emptying this descriptor stops every local build of every module before a
 * single source file is compiled. There is no in-code bypass available either: the configuration
 * enables no suppression filter, so neither a suppression comment nor a suppression annotation has
 * any effect, and the companion {@code suppressions.xml} is chartered for generated sources and
 * test fixtures alone.</p>
 *
 * <p>Assumptions: no comparable descriptor sits at {@code com/} or at {@code com/carddemo/}, and
 * the absence is intended rather than an omission. {@code JavadocPackage} reports only against a
 * directory that contains an audited source file. Those two directories are pure namespace
 * segments that hold no {@code .java} file of their own, so no descriptor is required in either and
 * none is created. The descriptor at {@code com/carddemo/common/} does exist, because that
 * directory does come to hold source files.</p>
 *
 * <p>Because this compilation unit contains a single statement, the decision rationale that the
 * rule's validation gate at its line 43 requires alongside the docstring has no adjacent executable
 * code to sit beside. It is therefore carried inside this block under the rule's own category
 * labels, which is the only placement the language makes available for a package declaration.</p>
 */
package com.carddemo.common.money;
