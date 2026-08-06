/**
 * Tests for the shared validation contracts transcribed from the reference date-edit routines.
 *
 * <p>Assumptions: these tests assert against the PUBLISHED constants of the class under test rather
 * than against literals repeated here, except where the point of the assertion is the literal itself.
 * The exception is deliberate and narrow: the three verbatim baseline messages are asserted BOTH
 * against the constant and against the exact character sequence, because the whole value of carrying a
 * message verbatim is lost if the only record of what verbatim means is the constant that might have
 * been edited.</p>
 *
 * <p>Assumptions: every business date is injected as an argument and no test reads the wall clock, so a
 * run today and a run in a year produce identical results. The date-of-birth rule is asserted against
 * two different injected dates precisely to demonstrate that the comparison uses the argument -- a
 * reintroduced clock read would fail here rather than in a nightly run months later.</p>
 */
package com.carddemo.common.validation;
