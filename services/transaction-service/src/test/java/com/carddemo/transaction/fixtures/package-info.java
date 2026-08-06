/**
 * The executable consumer for this module's nine posting-scenario fixture directories.
 *
 * <h2>Why this package exists</h2>
 *
 * <p>Twenty-seven record files across nine scenario directories described a contract that nothing
 * executed. The module's other tests cover the DTOs, the entity mapping, the repository against a real
 * database and the published contract, but none of them opens a fixture -- so a boundary amount could
 * move by one cent, a boundary date by one day, or an intentionally empty file could gain a trailing
 * newline, and every test would stay green. Those are exactly the changes the fixtures exist to make
 * detectable, which is why an unconsumed fixture is a documented intention rather than an assertion.</p>
 *
 * <p>Assumptions: files are read from the test classpath and decoded through the registered
 * {@code DALYTRAN}, {@code TRAN} and {@code TCATBAL} layouts, so the offsets under test are the
 * production offsets rather than a second set restated here.</p>
 *
 * <h2>What it asserts, and why the pairs matter more than the values</h2>
 *
 * <p>Alternatives Considered: asserting each fixture's values independently. Rejected because the two
 * boundaries in this tree are *pairs*, and a pair is what carries the meaning. The over-limit boundary
 * is the claim that an amount exactly at the credit limit posts and one cent more rejects; asserting
 * the two amounts separately would stay green if both moved together, which destroys the boundary while
 * leaving every individual assertion true. This package therefore asserts the difference -- one cent,
 * one day -- alongside the values, and it asserts that the time-of-day tail is identical in the date
 * pair so the single day is provably the only variable.</p>
 *
 * <p>Trade-offs: it does not run the posting program or its migrated equivalent, so it does not assert
 * that the at-limit record posts or that the over-limit record produces reason code 102. Those need the
 * batch job, which lives in another module, and asserting them here against a stand-in would produce a
 * test passing against a fiction. What is asserted is that the inputs those runs depend on hold the
 * values their READMEs claim.</p>
 *
 * <h2>Why this descriptor exists</h2>
 *
 * <p>The project Explainability rule requires a docstring on every module entry point, and a Java
 * package declaration is that entry point; {@code package-info.java} is the only compilation unit that
 * can carry package-level Javadoc. Two Checkstyle modules enforce it independently, and the
 * documentation gate audits test sources exactly as it audits main sources.</p>
 *
 * <p>Assumptions: the parameter, return and exception elements of the rule's docstring specification
 * describe callable code and are omitted here deliberately rather than written out empty, because an
 * at-clause carrying no description is itself a violation of the completeness module that audits this
 * build.</p>
 */
package com.carddemo.transaction.fixtures;
