/**
 * Tests for the shared observability helpers.
 *
 * <p>Assumptions: these tests assert on returned strings rather than on captured log output. What is
 * under test is the neutralisation rule itself -- which characters are replaced, what the replacement
 * is, and whether the clean case is copied at all -- and asserting that through an appender would add a
 * logging backend, its configuration and its formatting to the set of things a failure could be
 * attributed to.</p>
 */
package com.carddemo.common.observability;
