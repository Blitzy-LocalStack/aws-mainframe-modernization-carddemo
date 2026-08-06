/**
 * Anti-corruption-layer tests of the transaction bounded context. This package holds the
 * mapper tests of transaction-service: each one asserts that a copybook-shaped record and
 * a domain object convert to one another exactly, and that the representation concerns the
 * mappers exist to absorb never leak past them.
 *
 * <h2>What belongs here</h2>
 *
 * <p>Assumptions: this package holds assertions about the CONVERSIONS -- which stored column becomes
 * which published member, what is masked on the way out, what is padded, which side of a computation a
 * value is read from, and which absences collapse onto null. Assertions about a transfer object's own
 * declared constraints belong to {@code com.carddemo.transaction.dto}, and assertions about the
 * fixed-width record geometry belong to {@code com.carddemo.transaction.domain}.
 *
 * <h2>Target contract, and the tree state at the checkpoint that authored it</h2>
 *
 * <p>Assumptions: every inventory, file name, class name and count in this charter describes the
 * package's <b>target contract</b> as the migration plan assigns it, not the set of files present
 * beside this one today. The migration lands its artifacts in plan order, so a type or test named
 * below that has no file yet is <b>planned</b>, not missing, and a count below is a target total
 * rather than a measurement of the directory.</p>
 *
 * <p>Alternatives Considered: withholding this charter until every test it governs
 * exists. Rejected, because the charter is what the authors of those tests work
 * from -- which test belongs here, which may not, what the closed set is -- so
 * writing it last would leave the package with no stated contract during exactly
 * the interval in which one is needed. The cost of authoring it first is that its
 * inventory reads as present tense unless the distinction is declared, which is
 * what this section is for; the sentence above is the single place a reader has to
 * look to tell a target from a measurement.</p>
 *
 * <h2>Test classes</h2>
 *
 * <dl>
 *   <dt>{@code TransactionMapperTest}</dt>
 *   <dd>Covers the mapper over the 350-byte transaction
 *       record. What must be asserted is the set of decisions the mapper makes that a
 *       reader cannot infer from a field name: that {@code FILLER} is dropped rather than
 *       carried, that the primary account number is masked to its last four digits on
 *       every response except the administrative one, and that money survives the round
 *       trip as an exact scale-2 value rather than acquiring a rounding error.</dd>
 *
 *   <dt>{@code BillPaymentMappingTest}</dt>
 *   <dd>Covers the bill-payment request and response mapping,
 *       including that the payment amount reaches the domain as {@code BigDecimal} and
 *       leaves as a JSON string, that the published balance is read from the side of
 *       the subtraction the reference program reads it from, that the figure the far side
 *       leaves behind is invariably zero, and that the identifiers are padded to their
 *       declared widths.</dd>
 * </dl>
 *
 * <p>Refactoring Rationale: a second class named {@code BillPaymentMapperTest} was authored beside that
 * one and is withdrawn rather than kept, because the two were written against different response
 * shapes. It asserted a seven-member body carrying a masked card number and a separate amount, and the
 * shape this context publishes carries five and reports the balance once -- so the two could not both
 * pass, and keeping both would have left the module with one test asserting a member the record does
 * not declare. Its five subjects are each covered by the surviving class, so nothing it asserted is
 * lost: two money sources, a zero remainder, the fixed flag, an absent message and a refused absent
 * balance. What its charter contributed instead is the paragraph below, which states WHY this package
 * exists at all better than the enumeration does.
 *
 * <p>Assumptions: the property these tests exist for is that two members of one response body can carry
 * the same type, the same scale and the same magnitude while denoting different figures. The
 * bill-payment result is drawn from exactly such a pair: the amount paid and the balance the account is
 * left with are the same number on opposite sides of one subtraction in the reference program. A schema
 * cannot distinguish them, a compiler cannot distinguish them, and a test that built both from one value
 * would pass while the response reported the wrong one. The only mechanism that turns that confusion
 * into a build failure is a test that supplies the figures from two visibly different places and then
 * asserts which one the published member carries, which is what the surviving class does.
 *
 * <p>Trade-offs: asserting a fixed flag and a padded identifier couples these tests to decisions the
 * mapper makes rather than to values a caller supplies, so a change of mind about either fails a test
 * rather than passing silently. That coupling is deliberate: both are contract decisions, and a
 * regression in either is invisible in a body that still deserialises.
 *
 * <h2>Why a mapper needs its own tests</h2>
 *
 * <p>Refactoring Rationale: the migration plan makes this layer the ONE place copybook representation
 * concerns are allowed to appear -- dropped padding, a masked primary account number, a suppressed
 * verification value, a corrected misspelling -- so every decision it encodes is a decision that exists
 * nowhere else to be cross-checked against. The review that prompted this package found a response
 * shape that no mapper constructed at all, which is how its two descriptions came to disagree about
 * which side of a subtraction a balance was read from: with nothing executing the conversion, neither
 * description was ever contradicted.
 *
 * <h2>Why a round trip is the assertion of choice here</h2>
 *
 * <p>Alternatives Considered: asserting only the decode direction, on the grounds that
 * the service reads records far more often than it writes them. Rejected because a
 * decode-only test passes happily against a mapper that silently discards a field: the
 * field is simply absent from the object nobody compared it against. A round trip fails
 * on exactly that mistake, which is the commonest defect this layer can have.</p>
 *
 * <p>Assumptions: the fixture records are derived from the copybook layouts rather than
 * captured from a run, so a fixture cannot encode the very defect a test is meant to
 * catch. Where a fixture and a copybook disagree, the copybook is right -- it is the
 * normative source under Transformation Rule T1, and it is read and never modified. The
 * assertions here name the reference program line the conversion reproduces, in the same
 * form the production code does, because a conversion that merely compiles proves nothing.</p>
 *
 * <h2>Why money assertions belong here at all</h2>
 *
 * <p>Trade-offs: the ArchUnit money-path assertion in {@code common-lib} already fails the
 * build on a binary floating-point type anywhere in the money path, so a test here cannot
 * be the primary guard against one. These assertions are the second half of that
 * defence: the architecture rule proves no {@code double} is present, and a round-trip
 * assertion proves the {@code BigDecimal} that is present keeps its scale and its sign
 * through a conversion. A type rule cannot see a lost cent, and a value assertion cannot
 * see a wrong type, so both are needed.</p>
 *
 * <p>The outcome of this build is binary; the graded return-code rubric belongs to the
 * COBOL suite under {@code tests} and has no meaning for this module.</p>
 */
package com.carddemo.transaction.mapper;
