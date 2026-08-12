/**
 * Tests that hold this context's published contract and its transport types to each other.
 *
 * <p>Assumptions: the assertions here are about AGREEMENT between two artifacts rather than about the
 * outcome of a request. The contract at {@code src/main/resources/openapi/transaction-api.yaml} and the
 * records in {@code com.carddemo.transaction.dto} each state a path, a member name, a field width, a
 * value domain and a paging vocabulary, and nothing in a compiler compares them: one side is a document
 * and the other is an annotation argument.</p>
 *
 * <p>Refactoring Rationale: this package exists because a review found six such disagreements in this
 * context alone - unversioned paths that no deployed route answered, a confirmation member named for a
 * noun invented during the migration, a response member absent from the created-body schema, five
 * identifier fields bounded by one any-length digit run instead of their declared widths, a paging
 * direction whose serialised form matched neither side, and a correlation identity bounded at a width
 * the shared filter does not enforce. Each would have surfaced first as a refused request or a rejected
 * insert in a deployed environment rather than as a build failure.</p>
 *
 * <p>Alternatives Considered: generating these records from the contract, which would make the
 * disagreement impossible rather than detectable. Rejected because the mapping is not mechanical - it
 * truncates padding, masks the primary account number, withholds a verification value and renames
 * misspelled baseline fields, each needing a justification at the point of decision that a generator
 * cannot carry - and because the migration plan states the mapping layer is hand-written for that
 * reason. Asserting the agreement keeps the annotated records and their rationale while making drift
 * fail a build.</p>
 *
 * <p>Refactoring Rationale: that list named "narrows one edit mask" until a later review measured the
 * narrowing. The service tested the eight-digit SCREEN picture while this contract and the request record
 * both admitted the record's nine, so nothing was narrowed at the boundary and a nine-digit amount was
 * instead refused after binding. The narrowing is gone rather than relocated -- divergence
 * D-AMOUNT-RECORD-WIDTH settles the field at the record's width on all three sides -- and a case in this
 * package now reads the service's own constant so a third authority cannot dissent again unnoticed.</p>
 *
 * <p>Trade-offs: the contract is parsed as untyped nested maps rather than through an OpenAPI object
 * model, and the constraints are read by reflection rather than by running a validator. A model plus a
 * validator would exercise the behaviour; it would also see only what the model exposes and only the
 * values a test happens to submit, whereas the defects being guarded against are in the DECLARATIONS.
 * Reading both declarations directly compares exactly what drifted.</p>
 */
package com.carddemo.transaction.dto;
