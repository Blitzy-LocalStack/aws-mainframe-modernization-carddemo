/**
 * The executable consumer for this module's fixed-width fixture tree.
 *
 * <h2>Why this package exists</h2>
 *
 * <p>Fifteen record files across thirteen scenario directories described a contract that nothing
 * executed. Their bytes could change -- a padding byte, a sign overpunch, a record width, a
 * deliberately zero-byte file gaining a trailing newline -- and every test in this module would have
 * stayed green, because no test opened them. A fixture with no consumer documents an intention rather
 * than asserting a fact, and the tree README states the direction of the contract plainly: the bytes
 * are the contract, and a disagreement is settled by reading them rather than by editing them. This
 * package is what makes that statement enforceable.</p>
 *
 * <p>Assumptions: this is the first test package in {@code reference-service}. Before it the module
 * contributed only the five shared architecture rules inherited from {@code common-lib}'s test
 * artifact, so a claim that "the module's tests pass" said nothing about the module. That is stated
 * because it explains why this package asserts fixture bytes rather than service behaviour: the
 * controllers, services and repositories the tree README names as eventual consumers are not authored
 * in this checkpoint, and a test cannot exercise a class that does not exist.</p>
 *
 * <h2>What it asserts, and what it deliberately does not</h2>
 *
 * <p>It asserts the four things a fixture can be wrong about independently of any service: the record
 * width, the record count, the line-ending and trailing-newline shape, and the decoded field values
 * at their declared offsets. It decodes through {@code com.carddemo.common.codec.FixedWidthCodec}
 * against the registered {@code CopybookLayout} entries, so the offsets under test are the production
 * offsets rather than a second set restated here -- the house prohibition on duplicating a record
 * layout applies to test code exactly as it applies to production code.</p>
 *
 * <p>Alternatives Considered: asserting the files as opaque byte arrays against checksums. Rejected
 * because a checksum tells a maintainer that something changed and nothing about what, and the
 * failure it produces names no field. Decoding through the production codec means a shifted offset
 * fails on the field whose value moved, which is the diagnostic a maintainer can act on. It also
 * means a change to a registered layout that broke these files would be caught here rather than
 * discovered later by a service that had not been written yet.</p>
 *
 * <p>Trade-offs: this package does not assert the business outcomes the scenario READMEs describe --
 * that a delete is refused with a 409, that an unrecognised action byte is reported while the run
 * continues, that a fallback yields the
 * {@code DEFAULT} rate. Those need the service classes, and asserting them here against a
 * hand-rolled stand-in would produce a test that passed against a fiction. What is asserted instead
 * is that the bytes those eventual assertions depend on are the bytes the READMEs say they are, which
 * is the half of the obligation that is discharicable now and is exactly the half that was missing.</p>
 *
 *
 * <p>Assumptions: geometry is obtained by asking {@code CopybookLayout} for the registered layout,
 * never by copying an offset out of the charter's tables. The charter states the rule and the reason
 * itself: the tables are a derived index that cites a copybook line per field, not a competing
 * declaration, so a test that hard-coded an offset would create a third place for the geometry to be
 * wrong.
 *
 * <p>Trade-offs: two of the record shapes read here have no registered layout, and for those a
 * descriptor is built locally from the charter's tables. Both are PROGRAM WORKING-STORAGE records
 * rather than copybooks -- {@code WS-INPUT-REC} at {@code COBTUPDT.cbl} line 71 and
 * {@code REQUEST-MSG-COPY} at {@code CODATE01.cbl} line 109 -- so there is no copybook for
 * {@code CopybookLayout} to register and nothing to ask it for. Adding them to that registry was
 * considered and rejected: its published surface is asserted by {@code common-lib}'s own tests, which
 * pin the base-master and derived name counts, so extending it is a change to the shared kernel with
 * its own obligations rather than a step in reading a fixture. The local descriptors are routed
 * through {@code FixedWidthCodec} all the same, and {@code validateGeometry()} proves their intervals
 * tile the record exactly -- which a hand-written byte slice could not.
 * <h2>Why this descriptor exists</h2>
 *
 * <p>The project Explainability rule requires a docstring on every module entry point, and a Java
 * package declaration is that entry point; {@code package-info.java} is the only compilation unit
 * that can carry package-level Javadoc. Two Checkstyle modules enforce it independently -- one
 * requires the file to exist in a directory holding an audited source, the other requires it to carry
 * a Javadoc block -- and the documentation gate audits test sources exactly as it audits main
 * sources.</p>
 *
 * <p>Assumptions: the parameter, return and exception elements of the rule's docstring specification
 * describe callable code and are omitted from this block deliberately rather than written out empty,
 * because an at-clause carrying no description is itself a violation of the completeness module that
 * audits this build.</p>
 */
package com.carddemo.reference.fixtures;
