/**
 * The HTTP surface of the transaction context: the four operations its published contract declares.
 *
 * <p>Purpose: this package is the only place in this module that carries a request mapping, and it is the
 * boundary transformation rule T5 maps the reference's screen sends and receives onto. Two classes live
 * here. {@code TransactionController} serves the three operations of the transaction resource -- the paged
 * browse migrated from {@code app/cbl/COTRN00C.cbl}, the detail read migrated from
 * {@code app/cbl/COTRN01C.cbl} and the capture migrated from {@code app/cbl/COTRN02C.cbl}.
 * {@code BillPaymentController} serves the payment operation migrated from
 * {@code app/cbl/COBIL00C.cbl}, which is a separate resource because the reference gives it a separate
 * transaction and a separate screen and because its request shape shares no member with the capture's.</p>
 *
 * <p>Assumptions: no controller here holds a business rule. Each one binds and validates the request,
 * calls exactly one service method, and returns what that method returned. Every decision the reference
 * makes -- which sentence to emit, which branch a confirmation takes, whether a balance is payable -- is
 * made in the service package, so a rule can be tested without a servlet and cannot be duplicated between
 * a controller and the service beneath it.</p>
 *
 * <p>Assumptions: the sole exception to that rule is the cursor seam. The paged browse's service takes an
 * opened cursor rather than a token, because the token's key material is infrastructure the service layer
 * must not reach for, so the controller is the layer that opens and seals it. That is binding work rather
 * than a business rule, and it is the same division the reference makes when its screen carries the browse
 * key in the communication area and its browse paragraph works from the key alone.</p>
 *
 * <p>Refactoring Rationale: this charter previously described two PLANNED test classes and named no
 * production class, because at the time it was written the package held nothing but this file and the four
 * published operations were unreachable. Both controllers have since landed, and the two test classes the
 * charter named exist alongside a contract test that holds the published document and the delivered routes
 * to each other in both directions. The history is recorded rather than deleted so that a reader meeting
 * an older revision of the contract can tell which state they are looking at.</p>
 */
package com.carddemo.transaction.api;
