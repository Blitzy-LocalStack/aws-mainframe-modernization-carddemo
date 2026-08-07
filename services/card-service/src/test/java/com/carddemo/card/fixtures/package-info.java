/**
 * The executable consumer for this module's fixed-width card fixtures.
 *
 * <h2>Why this package exists</h2>
 *
 * <p>Twelve record files described a contract that nothing executed. This module's other tests read
 * the published OpenAPI document, so a fixture byte could change -- a sign, a pad, a boundary year, a
 * deliberately zero-byte file gaining a newline -- and every test would still pass. A fixture with no
 * consumer records an intention rather than asserting a fact, and this package is what makes the
 * fixtures README's claim that the bytes are the contract enforceable rather than aspirational.</p>
 *
 * <p>Assumptions: the fixtures are read from the test classpath and decoded through the registered
 * {@code CARD} layout in {@code com.carddemo.common.codec}, so the offsets under test are the
 * production offsets rather than a second set restated here. The house prohibition on duplicating a
 * record layout applies to test code exactly as it applies to production code, and it is the reason
 * this package declares no field positions of its own.</p>
 *
 * <h2>What it asserts, and what it deliberately does not</h2>
 *
 * <p>It asserts what a fixture can be wrong about independently of any service: the record width, the
 * record count, the absence of carriage returns, the decoded value of every field, and the paired
 * boundary values that only mean something together -- the accepted expiry years against the years one
 * step outside them, the three distinct days that would collapse under a normalisation, and the two
 * unacceptable-name forms that a check written for only one of them would let through.</p>
 *
 * <p>It also asserts the paging arithmetic the eighteen-row page corpus exists for, which is the one
 * property of these fixtures that is not a property of a single record: the page size read from this
 * module's published contract, the three pages of seven, seven and four, both interior boundaries
 * traversed forward and backward, the read of one row beyond each full page whose surplus row is
 * reported and discarded, and the trailing token naming the last row the caller received rather than
 * that surplus row. Refactoring Rationale: the corpus previously received only an ordering and a size
 * assertion, and the rationale beside them named a page of ten -- the transaction list's size, from a
 * different screen in a different module. Both authorities for this screen say seven, at
 * {@code app/cbl/COCRDLIC.cbl} lines 177 and 178 and at {@code maxItems: 7} on {@code CardPage.items}
 * in {@code openapi/card-api.yaml}, and the size is now read from the contract so that no number in
 * this package can disagree with the one the service publishes.</p>
 *
 * <p>Trade-offs: the envelope and its cursors are the production
 * {@code com.carddemo.common.web.PageResponse} and {@code CursorToken}, but the store they page over is
 * the ordered corpus rather than a query. Under {@code src/main/java/com/carddemo/card} the {@code api},
 * {@code service}, {@code repository} and {@code mapper} packages hold a {@code package-info.java} and
 * nothing else, so there is no list repository to query and no list service to call. Waiting for them
 * would leave the corpus ungated in the meantime; substituting a repository double this package also
 * wrote would assert a page shape against its own author. Paging the shipped envelope types over the
 * shipped page size is the part of the contract that can be gated now, and it is the part a wrong page
 * size actually breaks.</p>
 *
 * <p>Trade-offs: it does not assert that the service rejects what these fixtures are named for. That
 * needs the validation and mapping classes, and asserting it here against a hand-rolled stand-in would
 * produce a test passing against a fiction. What is asserted instead is that the bytes those eventual
 * assertions depend on are the bytes the README says they are, which is the half of the obligation
 * that is dischargeable now and is exactly the half that was missing.</p>
 *
 * <h2>Why this descriptor exists</h2>
 *
 * <p>The project Explainability rule requires a docstring on every module entry point, and a Java
 * package declaration is that entry point; {@code package-info.java} is the only compilation unit that
 * can carry package-level Javadoc. Two Checkstyle modules enforce it independently -- one requires the
 * file to exist in a directory holding an audited source, the other requires it to carry a Javadoc
 * block -- and the documentation gate audits test sources exactly as it audits main sources.</p>
 *
 * <p>Assumptions: the parameter, return and exception elements of the rule's docstring specification
 * describe callable code and are omitted here deliberately rather than written out empty, because an
 * at-clause carrying no description is itself a violation of the completeness module that audits this
 * build.</p>
 */
package com.carddemo.card.fixtures;
