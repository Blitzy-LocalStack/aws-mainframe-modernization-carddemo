/**
 * Executable consumer for the fixed-width fixtures under {@code src/test/resources/fixtures}.
 *
 * <h2>Purpose</h2>
 *
 * <p>This package holds one class, {@code BatchFixtureContractTest}, and it exists because most of
 * the corpus it consumes previously had no consumer at all. Sixteen scenario directories across four
 * domains hold 51 record files; before this class, five of those files were opened as job input and
 * the rest were only cited in prose. A fixture nobody loads proves nothing: its record
 * length, its field offsets, the one-cent and one-day boundary pairs it encodes, the relationships
 * that make a reject scenario reject, and the emptiness that drives a create path are all claims,
 * and a claim no test reads cannot fail. This class turns each of them into an assertion, so a
 * boundary value can no longer move by one cent, one day or one byte with every test staying
 * green.</p>
 *
 * <p>Assumptions: the class does <em>not</em> re-run the batch jobs over these bytes. Two of the
 * four domains are driven end to end elsewhere -- the pre-posting pass reads three scenarios from
 * this module's classpath, and the interest job reads the reference tree under {@code tests/} -- and
 * duplicating those runs here would assert the jobs twice while still leaving the unread files
 * unread. What this class asserts instead is the corpus itself: its census, its geometry, its
 * governance rules and the discriminating values its scenario READMEs state. Section 1.1 of
 * {@code src/test/resources/fixtures/README.md} records which scenarios are driven and which are
 * mirrors.</p>
 *
 * <p>Assumptions: the tests live in their own package rather than beside the job tests, because
 * their subject is the <em>resource directory</em> rather than any one production type. The sibling
 * services reached the same arrangement independently -- {@code com.carddemo.transaction.fixtures}
 * and {@code com.carddemo.reporting.fixtures} -- so the location is a house convention rather than a
 * local choice. Alternatives Considered: folding the assertions into the job test classes, which
 * would avoid a package. Rejected because the census assertion is closed-set, naming every scenario
 * that may exist, and a closed set stated in several classes is a set that drifts.</p>
 *
 * <p>Assumptions: nothing in this package is a production type, so it declares no public API, holds
 * no state and is never referenced from {@code src/main}. It is compiled only by the test
 * compilation unit.</p>
 */
package com.carddemo.batch.fixtures;
