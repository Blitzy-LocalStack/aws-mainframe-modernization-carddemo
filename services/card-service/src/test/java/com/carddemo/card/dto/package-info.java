/**
 * Tests over the transfer shapes this context publishes.
 *
 * <p>Purpose. This package holds the executable evidence for the records in
 * {@code com.carddemo.card.dto}: that each declared constraint refuses what it says it refuses, that the
 * published contract states the same rules the records enforce, and that no record's diagnostic rendering
 * carries a value the migration withholds from a log.
 *
 * <p>Refactoring Rationale: the card wire shapes documented three protections at length and enforced
 * none of them, and every one of the three was invisible to the build because each crossed the wire as
 * a string. The masked-rendering members were bounded by LENGTH alone and a raw sixteen-digit account
 * number is itself exactly sixteen characters long, so an unmasked number satisfied every one of them.
 * The generated {@code toString()} of a record prints every component, so a cardholder's name, their
 * account number and their card's expiry reached any log line that stringified an instance. And the
 * update request carried a caller-controlled expiry day, on a field the baseline renders non-display and
 * never validates. Documentation asserting a protection that nothing checks records an intention rather
 * than a fact, and part of this package exists to turn those three into facts.
 *
 * <p>What it asserts: that an unmasked number is refused at construction and that the refusal does not
 * echo the value it refused; that a properly masked rendering is still accepted, so the check is a
 * positive test of the masked form rather than a denial of one known value; that no diagnostic rendering
 * carries a name, an unmasked account number or an expiry, while the SELECTOR is still present so a
 * rendering stays useful for correlation; that the update request declares no day member and carries the
 * month and the year as separate members, matching the two independent edit flags the baseline program
 * keeps; and that the selector bound is the exact length the sealer produces, so the literal in a
 * constraint cannot drift from the arithmetic that justifies it.
 *
 * <p>Assumptions: the parity tests here read the published contract from the CLASSPATH rather than from a
 * source path, so they assert against the artifact the service actually ships. Reading the source tree
 * through the file system would pass while the packaged resource was stale or absent.
 *
 * <p>Assumptions: the remaining assertions are unit assertions against the types themselves rather than
 * against a running application, because this module has no application class yet. That is the right form
 * for these particular properties rather than a concession: two of them are properties of a CONSTRUCTOR
 * and one is a property of a component LIST, so none of them needs a request to be exercised.
 *
 * <p>Alternatives Considered: asserting the record constraints through a running web layer, with a bound
 * request per case. Rejected at this checkpoint because this module has no application class and no
 * controller, so a web test would assert the framework's binding rather than the records' constraints; the
 * validator is driven directly instead, which is the same provider the framework would have used.
 *
 * <p>Trade-offs: nothing here asserts that a mapper PRODUCES a masked rendering, because no mapper exists
 * in this module yet. What is asserted instead is that a mapper which failed to would be refused rather
 * than serialised, which is the half of the obligation that is dischargeable now and is exactly the half
 * that was missing. The properties that do need a request -- that a handler chooses the masked shape for a
 * non-administrative caller, and that the selector opens to the row it addresses -- belong with the
 * application context and are not asserted here.

 */
package com.carddemo.card.dto;
