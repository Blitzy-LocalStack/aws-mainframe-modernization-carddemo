/**
 * Tests for the shared error model and the one advice that renders every failed request.
 *
 * <p>Assumptions: this package exists as a test package because the audit configured by
 * {@code config/checkstyle/checkstyle.xml} runs its {@code JavadocPackage} check at checker level over
 * every source root the build hands it, and {@code services/pom.xml} sets
 * {@code includeTestSourceDirectory} to true. A test package with no {@code package-info.java} is
 * therefore a build failure rather than an omission a reviewer might miss, which is the intended
 * effect: Rule 1's docstring obligation reaches a module entry point whether or not the module is
 * production code.</p>
 *
 * <p>Assumptions: what these tests are for is narrower than "the error package works". They exist to
 * hold two properties that cannot be read off the source by inspection. The first is that a primary
 * account number appearing in a request path reaches neither the operational log nor the emitted
 * problem shape, which is a property of one shared helper feeding eighteen call sites and is asserted
 * against the log record itself rather than against the helper. The second is that the widths this
 * package records from the baseline agree with the widths the transport actually mints, so a recorded
 * provenance value can never be mistaken for a live contract.</p>
 *
 * <p>Trade-offs: the masking assertions capture real log events through a list appender attached to
 * the advice's own logger, which couples the test to the logging back end the starter chain ships.
 * The alternative -- asserting only on the returned problem shape -- was rejected because the finding
 * these tests close was specifically about log storage, and a test that never reads a log line would
 * pass just as happily if the masking were applied to the response body alone.</p>
 */
package com.carddemo.common.error;
