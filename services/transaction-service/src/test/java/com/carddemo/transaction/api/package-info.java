/**
 * Controller-level tests of the transaction bounded context. This package holds the web
 * layer tests of transaction-service: each one drives a controller through the HTTP
 * surface and asserts status codes, response shapes and error bodies, without reaching a
 * database.
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
 *   <dt>{@code TransactionControllerTest}</dt>
 *   <dd>PLANNED, not yet authored. Slices the web layer around the transaction list,
 *       detail and create endpoints with the service layer mocked. The list assertions
 *       carry the keyset contract: a page is requested by cursor rather than by offset,
 *       and the envelope's next-page availability is asserted rather than inferred from
 *       the item count.</dd>
 *
 *   <dt>{@code BillPaymentControllerTest}</dt>
 *   <dd>PLANNED, not yet authored. Slices the web layer around the bill-payment
 *       endpoint. Assumptions: the confirmation step the baseline performs on the screen
 *       is a client concern, so what is asserted here is the endpoint's own behaviour --
 *       that a payment request is validated, that a rejection carries its per-field
 *       error array, and that money is transported as a JSON string.</dd>
 * </dl>
 *
 * <h2>Why these tests mock the service layer rather than wiring the whole context</h2>
 *
 * <p>Alternatives Considered: booting the full application context for every controller
 * assertion. Rejected because it makes a web-layer failure indistinguishable from a
 * persistence failure: a broken query would redden a test whose name claims a status
 * code is wrong, sending a reader to the controller instead of to the repository. The
 * repository behaviour is asserted separately in the sibling {@code repository} package
 * against a real database, so nothing is left unverified by the split.</p>
 *
 * <p>Trade-offs: a mocked collaborator cannot catch a mismatch between what a controller
 * expects and what the service actually returns. That gap is closed by the contract
 * assertions over the published OpenAPI document, which check the controller surface
 * against the frozen contract rather than against a mock's expectations.</p>
 *
 * <h2>What a green run of this package does and does not prove</h2>
 *
 * <p>Trade-offs: these tests prove the HTTP surface behaves as the contract states. They
 * do not prove parity with the baseline's screen behaviour, because the online CICS
 * programs cannot run end to end without a CICS runtime that the test runner does not
 * provide. Parity for the transcribed rules rests on the sibling {@code service} tests
 * and on the COBOL suite under {@code tests}, and no stronger claim should be read into a
 * green run here. The outcome of this build is binary; the graded return-code rubric
 * belongs to the COBOL suite and has no meaning for this module.</p>
 */
package com.carddemo.transaction.api;
