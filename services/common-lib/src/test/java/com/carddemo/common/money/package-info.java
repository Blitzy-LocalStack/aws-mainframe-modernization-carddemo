/**
 * Verifies the shared kernel money type against the exact fixed-point contracts the reference
 * baseline states, and against the textual form an amount takes when it leaves the process.
 *
 * <h2>Target contract, and the tree state at the checkpoint that authored it</h2>
 *
 * <p>Assumptions: every inventory, file name, class name and count in this charter describes the
 * package's <b>target contract</b> as the migration plan assigns it, not the set of files present
 * beside this one today. The migration lands its artifacts in plan order and this charter is
 * authored first, so at the checkpoint that authored it this directory holds this charter and
 * nothing else. A type or test named below that has no file yet is therefore <b>planned</b>, not
 * missing, and a count below is a target total rather than a measurement of the directory.</p>
 *
 * <p>Alternatives Considered: withholding this charter until every class it governs
 * exists. Rejected, because the charter is what the authors of those classes work
 * from -- which type belongs here, which may not, what the closed set is -- so
 * writing it last would leave the package with no stated contract during exactly
 * the interval in which one is needed. The cost of authoring it first is that its
 * inventory reads as present tense unless the distinction is declared, which is
 * what this section is for; the sentence above is the single place a reader has to
 * look to tell a target from a measurement.</p>
 *
 * <p>Five contracts are exercised here, and they are deliberately five rather than one. Money is
 * exact fixed point carried at scale 2, and the kernel re-establishes that scale half-up wherever a
 * scale reduction is needed. The monthly interest accrual is held to that same single mode, applied
 * once, after a full-precision product has been divided, and it needs its own expectations because
 * it is the one computation where the mode is observable at all. Posting weighs an amount against a
 * credit limit on an inclusive boundary. A disclosure-group key that is not found selects a named
 * default group's rate rather than failing the run. And an amount crosses an interface as
 * characters, never as a bare number. Each is asserted separately, because each fails independently
 * of the other four and a single aggregate expectation would report only that something differed
 * without saying which of the five it was.</p>
 *
 * <p>Nothing here defines behaviour. Every expectation in this package is read out of an immutable
 * reference artifact and restated as a Java assertion: the money and rate pictures in
 * {@code app/cpy/CVACT01Y.cpy} and {@code app/cpy/CVTRA02Y.cpy}, the accrual and fallback paragraphs
 * in {@code app/cbl/CBACT04C.cbl}, the validation chain in {@code app/cbl/CBTRN02C.cbl}, the
 * authorization request money field in {@code app/app-authorization-ims-db2-mq/cpy/CCPAURQY.cpy},
 * and the fixture and documentation conventions recorded in {@code tests/README.md} section 12,
 * which requires a fixture record to match its copybook layout exactly, sign overpunch included.
 * Those artifacts are read as evidence and are never modified.</p>
 *
 * <h2>The monthly accrual reduces scale half-up, once, and the vector that proves it</h2>
 *
 * <p>{@code app/cbl/CBACT04C.cbl} line 168 declares {@code 05 WS-MONTHLY-INT PIC S9(09)V99.}, a
 * receiving field of scale 2, and lines 464 and 465 compute
 * {@code ( TRAN-CAT-BAL * DIS-INT-RATE) / 1200} into it carrying no {@code ROUNDED} phrase, so the
 * reference discards the excess fraction digits rather than rounding them. The target does not
 * inherit that from the statement. Transformation rule T3 pins Java money to scale 2 with
 * {@code RoundingMode.HALF_UP} and admits no exception; the money-invariant table and the
 * arithmetic-order section of {@code docs/architecture/data-model-and-schema-mapping.md} state the
 * same rule for this accrual specifically, that the target multiplies at full precision, divides
 * once, and applies scale 2 with half-up at that single point; and the illustrative example at line
 * 226 of {@code docs/CODE_DOCUMENTATION_STANDARD.md} documents a monthly interest return value as
 * scaled to two decimal places, half-up. Expectations here assert that single mode. The gap between
 * it and the reference statement is a behavioural divergence, and it is registered as one in
 * {@code docs/architecture/cobol-to-service-traceability.md} rather than absorbed into a second
 * rounding mode; that registration is why a test here may assert half-up without contradicting the
 * parity oracle.</p>
 *
 * <p>Assumptions: the mode is only observable on a quotient whose third fraction digit is five or
 * greater, so an expectation that does not reach that input class asserts nothing about rounding at
 * all. This is not a theoretical caution: the reference fixtures do not reach it. The happy-path
 * interest fixture supplies a category balance of {@code 1000.00} against a disclosure-group rate of
 * {@code 15.00}, where the formula yields {@code 12.5000} exactly, and at a rate of {@code 2.50}
 * against the same balance the quotient is {@code 2.08333...}; a truncating implementation returns
 * the required figure on both. Expectations here therefore carry a discriminating vector alongside
 * the fixture vectors: a category balance of {@code 1000.80} at a rate of {@code 2.50}, whose
 * quotient is {@code 2.0850} exactly and whose required result is {@code 2.09}. A truncating
 * implementation returns {@code 2.08} on that input and fails, which is the whole point of writing
 * it down. Without that vector the suite would report a passing rounding contract while never having
 * exercised the rounding.</p>
 *
 * <p>The operand order is part of the same contract and is asserted with it. The product is formed
 * at full precision and only then divided, which is the order lines 464 and 465 use. Dividing
 * first and multiplying second is arithmetically equivalent over the rationals and is not
 * equivalent here, because the intermediate would be reduced to scale 2 before the multiplication
 * and the final cent then moves on many balances. The divisor 1200 is twelve months times one
 * hundred, converting the annual percentage held in {@code DIS-INT-RATE PIC S9(04)V99} at
 * {@code app/cpy/CVTRA02Y.cpy} line 9 into a monthly fraction; it is asserted as the literal the
 * reference uses rather than decomposed into two divisions, which would introduce a second scale
 * reduction that neither the reference statement nor this contract performs.</p>
 *
 * <h2>Posting weighs money on an inclusive boundary</h2>
 *
 * <p>{@code app/cbl/CBTRN02C.cbl} lines 403 to 405 form a working balance as the current cycle
 * credit less the current cycle debit plus the transaction amount, into
 * {@code 05 WS-TEMP-BAL PIC S9(09)V99.} declared at line 187. The three account operands are
 * {@code ACCT-CREDIT-LIMIT}, {@code ACCT-CURR-CYC-CREDIT} and {@code ACCT-CURR-CYC-DEBIT}, all
 * {@code PIC S9(10)V99} at {@code app/cpy/CVACT01Y.cpy} lines 8, 13 and 14, so the comparison is
 * between two exact scale-2 values and never between approximations of them. Line 407 then reads
 * {@code IF ACCT-CREDIT-LIMIT >= WS-TEMP-BAL}: an amount landing exactly on the limit satisfies the
 * test and posts, and only an amount beyond it reaches reason 102 at lines 410 and 411.
 * {@code tests/README.md} lines 570 and 571 state the same boundary from the opposite side, that a
 * balance exactly at the limit must post while one cent over must reject. Expectations here pin the
 * limit itself and the smallest representable step past it, since the boundary is where an
 * inclusive comparison and an exclusive one give different answers and everywhere else they agree.
 * The companion test at line 414 leans the same way on a date rather than on an amount, and is noted
 * here only so that the shared inclusive direction is not mistaken for a coincidence; the date rule
 * itself is verified where date handling lives, not in this package.</p>
 *
 * <h2>A disclosure group that is not found selects a default rate</h2>
 *
 * <p>{@code app/cbl/CBACT04C.cbl} line 422 admits the not-found file status alongside the success
 * status, and line 437 then moves the literal {@code 'DEFAULT'} into the group portion of the key
 * before line 438 reads again. The key is the leading sixteen characters of the 50-byte disclosure
 * group record, an account group id, a transaction type code and a transaction category code at
 * {@code app/cpy/CVTRA02Y.cpy} lines 6 to 8, and what the second read yields is the rate at line 9.
 * The fallback therefore changes which rate enters the accrual and changes nothing about how the
 * accrual is computed. Expectations here assert that money consequence, the amount produced from
 * the substituted rate, and stop there: resolving a key against the disclosure group table is owned
 * by the service that owns that table, and asserting the lookup here would duplicate a contract
 * this package does not hold.</p>
 *
 * <p>No expectation in this package asserts a fee. {@code app/cbl/CBACT04C.cbl} lines 518 to 520
 * hold the fee paragraph, and its entire body is one comment and an exit, so it computes nothing;
 * line 216 performs it regardless. There is no fee behaviour in the reference from which to derive
 * an expected amount, and inventing one would make this package the origin of a business rule
 * rather than a reading of one.</p>
 *
 * <h2>An amount crosses an interface as characters</h2>
 *
 * <p>Transformation rule T3 pins one representation per layer: an exact decimal column in the
 * database, a scale-2 decimal in Java, and a JSON string on the wire. The last of those is the part
 * that looks like an eccentricity and is not.
 * {@code app/app-authorization-ims-db2-mq/cpy/CCPAURQY.cpy} line 27 declares
 * {@code 05 PA-RQ-TRANSACTION-AMT PIC +9(10).99.}, and that is an edited picture rather than a
 * computational one: a sign character, ten integer digits, a literal period and two fraction
 * digits, fourteen characters of text among the eighteen text fields the request carries at lines
 * 19 to 36. The baseline already moved an amount across its own external boundary as characters, so
 * the string wire form preserves that shape rather than imposing a new one. Expectations here
 * assert that a serialised amount is a quoted string and that reading it back yields the same
 * scale-2 value, because a bare JSON number is parsed by most clients into an IEEE-754 binary value
 * and the cents are then lost at the one boundary a user actually reads.</p>
 *
 * <p>Assumptions: the prohibition on IEEE-754 binary arithmetic in the money path is enforced by
 * {@code LayeringRulesTest} and by no expectation in this package, so nothing here should be read as
 * carrying it. The language's two IEEE-754 binary primitive types and their two wrapper types are
 * described in this file and never spelled, and that is deliberate: this tree is audited by
 * searching the money path for those exact tokens, and a descriptor that spelled them would produce
 * a hit on every audit that a reader then has to dismiss by hand. Prose cannot fail a build in any
 * case; the architecture test can, which is why the constraint is named here and asserted there.</p>
 *
 * <h2>Decision record</h2>
 *
 * <p>Assumptions: this descriptor exists because the Java documentation gate audits test sources on
 * the same terms as main sources. {@code services/pom.xml} sets {@code includeTestSourceDirectory}
 * on the Checkstyle execution it binds to the Maven {@code validate} phase, and
 * {@code mvn -f services/common-lib/pom.xml help:effective-pom} resolves that setting to
 * {@code true}, so {@code config/checkstyle/checkstyle.xml} governs this directory in full. Two of
 * its checks act on this file as a pair and neither is redundant. {@code JavadocPackage} is a
 * file-set check: it processes this directory once the directory directly contains an audited Java
 * file, {@code MoneyTest} beside this descriptor, and it reports a missing {@code package-info.java}
 * without reading a byte of whatever one is present. {@code MissingJavadocPackage} then reads this
 * file and requires the package declaration to carry Javadoc, so a descriptor holding an ordinary
 * block comment and nothing else would satisfy the first check and fail the second. A package
 * declaration is also the module entry point the project Explainability rule names, and
 * {@code package-info.java} is the only compilation unit able to carry package-level Javadoc, so
 * this file is load bearing rather than decorative. That rule's parameter, return value and
 * exception elements describe callable code; a package declaration takes no argument, yields no
 * value and raises nothing, so those elements are omitted here deliberately rather than written out
 * empty. An at-clause carrying no description would itself be reported, by
 * {@code NonEmptyAtclauseDescription}.</p>
 *
 * <p>Assumptions: a descriptor belongs at a directory that directly holds an audited Java file and
 * at no other. The intermediate segments of this package name are pure namespace: in this test tree
 * they hold further directories and no Java file of their own, so the file-set check never processes
 * them and none of them needs a descriptor. Adding one would document a namespace segment that has
 * nothing in it to describe, and would then have to be maintained against a package whose contents
 * are all one level further down. This file therefore covers {@code com.carddemo.common.money} in
 * the test tree and claims nothing above it; the charter for the {@code com.carddemo.common}
 * namespace already exists in the main tree and is not restated here.</p>
 *
 * <p>Alternatives Considered: folding these expectations into an interest-specific or a
 * posting-specific test package was evaluated and rejected. The scale-2 money type is one type in
 * {@code common-lib} consumed by every service, and the migration keeps a shared concern single
 * sourced there for the same reason the reference suite keeps a record layout single sourced from
 * one directory instead of copying it per program ({@code tests/README.md} lines 540 to 542). Two
 * suites asserting one rounding contract can drift apart on it while both stay green, and a
 * one-cent rounding divergence is precisely the class of defect that survives a smoke test because
 * the numbers still look plausible. Verification is therefore single sourced alongside the type it
 * verifies.</p>
 *
 * <p>Trade-offs: this descriptor states the contracts and cites where each one is written down, and
 * deliberately holds no test vector, no copybook layout and no fixture record. The cost is that a
 * reader who wants the numbers must open {@code MoneyTest}. Restating the vectors here was rejected
 * because a vector written in two places can disagree with itself, and the copy in prose is the one
 * nothing executes and therefore the one that goes stale unnoticed. Widths, offsets and amounts stay
 * single sourced from the copybooks and fixtures named above.</p>
 *
 * <p>Trade-offs: this file is pure ASCII with no byte-order mark, matching the sibling descriptors
 * in this module rather than the typographic punctuation the migration prose uses. Two of the lines
 * quoted from {@code tests/README.md} section 12 carry a non-breaking hyphen, a character
 * indistinguishable from an ordinary hyphen on screen that behaves differently in a search, so a
 * rationale label spelled with one is a label that a search for the label fails to find.
 * Restricting this file to ASCII makes that failure mode unreachable, at the cost of plainer
 * punctuation, and the build declares UTF-8 for both the source encoding and the documentation
 * gate's charset, of which ASCII is a strict subset.</p>
 *
 * <h2>Contents of this package</h2>
 *
 * <p>This package holds three {@code .java} files and no others: this descriptor, {@code MoneyTest},
 * which exercises money arithmetic, the posting boundaries and the wire behaviour of the
 * {@code com.carddemo.common.money} types, and {@code MoneyModuleTest}, which pins the JSON wire
 * form of an amount against the Jackson release this build resolves. Fixture bytes are quoted
 * inside the expectations rather than copied into a test resource directory, so this package owns
 * no resources and introduces no nested directory.</p>
 *
 * <p>Refactoring Rationale: this paragraph declared a two-file target contract and recorded that at
 * its authoring checkpoint the directory held this descriptor alone. Both statements are
 * superseded: {@code MoneyTest} landed, the two production classes it covers landed with it, and
 * {@code MoneyModuleTest} arrived beside it as a third file the earlier contract did not admit.
 * The roster is restated at three rather than kept at two with an exception noted underneath,
 * because a contract that excludes a file which exists reads as governance while the governed
 * thing sits outside it -- and this package's whole purpose is to be the one place the money rule
 * is checked, so a reader must be able to see every file that checks it.</p>
 *
 * <h2>Relationship to the reference test suite</h2>
 *
 * <p>The tests in this tree are additive Java unit tests and are not the reference COBOL parity
 * oracle. That oracle lives under {@code tests/} at the repository root with its own runners and its
 * own pinned toolchain; it is read here as evidence and never modified, and nothing in this package
 * stands in for it. The two are also gated differently, which is worth stating so that neither
 * rubric is read into the other: that suite aggregates a graded condition code in which a
 * warning-level result is a documented pass, whereas this package is gated by Maven, Checkstyle and
 * JUnit, each of which either passes or fails outright. The relationship runs one way only, and it
 * runs from there to here: the pictures, paragraphs and fixture columns cited above are read as
 * observations of the contract, and the expectations derived from them live in this package.</p>
 */
package com.carddemo.common.money;
