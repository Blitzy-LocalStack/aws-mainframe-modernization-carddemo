/**
 * Verifies the shared kernel money type against the exact fixed-point contracts the reference
 * baseline states, and against the textual form an amount takes when it leaves the process.
 *
 * <p>Five contracts are exercised here, and they are deliberately five rather than one. Money is
 * exact fixed point carried at scale 2, and the kernel re-establishes that scale half-up wherever a
 * scale reduction is needed. One computation, the monthly interest accrual, truncates instead, and
 * that truncation is a contract in its own right rather than an inconsistency to be smoothed over.
 * Posting weighs an amount against a credit limit on an inclusive boundary. A disclosure-group key
 * that is not found selects a named default group's rate rather than failing the run. And an amount
 * crosses an interface as characters, never as a bare number. Each is asserted separately, because
 * each fails independently of the other four and a single aggregate expectation would report only
 * that something differed without saying which of the five it was.</p>
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
 * <h2>The monthly accrual truncates, and that is its own contract</h2>
 *
 * <p>{@code app/cbl/CBACT04C.cbl} line 168 declares {@code 05 WS-MONTHLY-INT PIC S9(09)V99.}, a
 * receiving field of scale 2, and lines 464 and 465 compute
 * {@code ( TRAN-CAT-BAL * DIS-INT-RATE) / 1200} into it carrying no {@code ROUNDED} phrase. Without
 * that phrase the excess fraction digits are discarded rather than rounded, so this one accrual
 * reduces scale by truncation while the kernel's general money arithmetic reduces it half-up. Both
 * behaviours are asserted, and they are asserted on quotients whose third fraction digit is five or
 * greater, because that is the only input class on which the two differ at all: a quotient that
 * already fits two fraction digits passes under either rule and so distinguishes nothing.</p>
 *
 * <p>The operand order is part of the same contract and is asserted with it. The product is formed
 * at full precision and only then divided, which is the order lines 464 and 465 use. Dividing
 * first and multiplying second is arithmetically equivalent over the rationals and is not
 * equivalent here, because the intermediate would be reduced to scale 2 before the multiplication
 * and the final cent then moves on many balances. The divisor 1200 is twelve months times one
 * hundred, converting the annual percentage held in {@code DIS-INT-RATE PIC S9(04)V99} at
 * {@code app/cpy/CVTRA02Y.cpy} line 9 into a monthly fraction; it is asserted as the literal the
 * reference uses rather than decomposed into two divisions, which would introduce a second scale
 * reduction that the reference does not perform.</p>
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
 * <p>Two {@code .java} files and no others: this descriptor, and {@code MoneyTest}, which exercises
 * the money types held in the {@code com.carddemo.common.money} package of the main source tree.
 * Fixture bytes are quoted inside the expectations rather than copied into a test resource
 * directory, so this package owns no resources and introduces no nested directory.</p>
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
