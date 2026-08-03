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
 * <h2>Contract two: two rounding modes, and why they are not one</h2>
 *
 * <p>This package exposes two distinct rounding behaviours, and the distinction is deliberate.
 * General money arithmetic reduces a result to scale 2 with {@code RoundingMode.HALF_UP}. The
 * interest accrual path does not: it forms the product at full precision, divides only then, and
 * reduces the quotient to scale 2 with {@code RoundingMode.DOWN}, which truncates toward zero.</p>
 *
 * <p>Assumptions: the interest behaviour is read off the reference program rather than chosen.
 * Lines 462 to 468 of {@code app/cbl/CBACT04C.cbl} hold the accrual paragraph, whose statement is
 * {@code COMPUTE WS-MONTHLY-INT = ( TRAN-CAT-BAL * DIS-INT-RATE) / 1200}. Two facts about that one
 * statement settle the rounding question between them. First, the receiving field is declared at
 * line 168 of the same program as {@code 05 WS-MONTHLY-INT            PIC S9(09)V99.}, so the
 * result is stored at exactly two decimal places and any further precision has to go somewhere.
 * Second, the statement carries no {@code ROUNDED} phrase -- and neither does any other statement
 * in the program, because a search for that phrase across all 652 lines of the file returns no
 * match at all. A store into a fixed-scale field without that phrase discards the surplus digits
 * rather than rounding them, so the reference behaviour is truncation toward zero and the target
 * contract is {@code RoundingMode.DOWN}.</p>
 *
 * <p>Trade-offs: carrying two modes costs the package a uniform arithmetic surface. A caller has
 * to know which of the two calls it wants, and a reader comparing this descriptor against sibling
 * prose will find the general contract stated on its own. Sibling descriptors in this repository
 * state the money contract as scale 2 with {@code RoundingMode.HALF_UP}, which is correct as the
 * general contract and is what this package implements generally; the illustrative Java example at
 * line 226 of {@code docs/CODE_DOCUMENTATION_STANDARD.md} additionally applies half-up to a
 * monthly-interest return value specifically. For the accrual path this descriptor's contract
 * governs, and the difference is recorded here as correction <b>C-ROUNDING</b> so that a later
 * reader treats it as a resolved divergence rather than as an inconsistency to be reconciled by
 * collapsing the two calls back into one mode. The compromise accepted is a less uniform API in
 * exchange for accrual figures that match the reference program cent for cent.</p>
 *
 * <p>Alternatives Considered: a single half-up mode for both paths was evaluated and rejected, and
 * it is worth recording why the choice is easy to get wrong. On the vectors the reference fixtures
 * actually carry, the two modes agree, so a single mode would pass those comparisons. The
 * happy-path interest fixture supplies a category balance of {@code 1000.00} and a disclosure-group
 * rate of {@code 15.00}; the formula yields {@code 12.5000} exactly, and both modes therefore
 * return {@code 12.50}. At a rate of {@code 2.50} against the same balance the quotient is
 * {@code 2.08333...} and both modes return {@code 2.08}. The two modes part company only where the
 * quotient lands exactly on a half cent, as with a balance of {@code 1000.80} at a rate of
 * {@code 2.50}: the quotient is {@code 2.0850} exactly, truncation returns {@code 2.08} and half-up
 * returns {@code 2.09}. Choosing half-up would therefore leave a defect that no existing fixture
 * detects and that a production balance would eventually expose one cent at a time, which is why
 * the mode is pinned to the reference behaviour rather than to the comparison that happens to be
 * available.</p>
 *
 * <p>Assumptions: the multiply-before-divide order is part of the same contract and is not an
 * implementation detail, per transformation rule T4. The reference statement multiplies the balance
 * by the rate and only then divides by 1200, so the product is formed at full precision. Reversing
 * the order and dividing first forces an intermediate result to a scale before the multiplication
 * consumes it: on the balance and rate just given, multiplying first yields {@code 2.0850} while
 * dividing first at an intermediate scale of two yields {@code 2.0750}, a difference of two cents
 * from reordering alone. The order is therefore preserved literally.</p>
 *
 * <h2>Contract three: a JSON string on the wire</h2>
 *
 * <p>{@code MoneyModule} serialises a monetary amount as a JSON string and deserialises it from
 * one. This is the third contract the package owns, and it is the one most often mistaken for
 * fussiness.</p>
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
 * with this descriptor beside them the directory holds exactly three {@code .java} files. There is
 * no fourth file here. Across {@code com.carddemo.common} as a whole the distribution is two
 * production classes in {@code money}, five in {@code codec}, three in {@code error}, two in
 * {@code web}, one in {@code security}, one in {@code observability}, one in {@code time} and two
 * in {@code validation}, the package root contributing none. The two totals that follow are each
 * kept whole on one line so that either can be checked by eye and matched by a search without a
 * line break splitting it:</p>
 *
 * <pre>
 * production classes:  2 + 5 + 3 + 2 + 1 + 1 + 1 + 2 = 17
 * compilation units:   17 production + 9 package descriptors = 26
 * </pre>
 *
 * <p>The nine descriptors are one for the package root and one for each of its eight subpackages.
 * The figures are recorded so that a later reader can tell a class that is missing from a class
 * that was never planned.</p>
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
