/**
 * Tests for the online-write window: the gate's decision and the interceptor that applies it.
 *
 * <p>Purpose. Two properties of this package carry the whole of its value and neither is visible from
 * a signature, so both are asserted rather than reviewed. The gate FAILS CLOSED -- an unreadable flag
 * refuses the write instead of admitting it -- and its value comparison is POSITIVE, so an empty or
 * mistyped flag reads as closed. Both are the kind of behaviour that is correct by construction only
 * until someone simplifies it.
 *
 * <p>Assumptions: hand-written stubs rather than a mocking framework, matching every other test in
 * this module. The stub for the Systems Manager client also records the calls it received, which is
 * what lets the caching cases assert the NUMBER of reads rather than only their answers -- the
 * property under test there is that a decision is or is not read again, and an answer alone cannot
 * distinguish the two.
 *
 * <p>Assumptions: the interceptor's tests hold the window CLOSED and observe which requests are
 * refused, rather than substituting a stand-in for the gate and counting calls to it. An open window
 * lets everything through, so every case would pass whether or not the interceptor consulted the gate
 * at all; a closed window makes the classification observable.
 *
 * <p>Trade-offs: the cache's expiry is exercised by advancing a controlled clock reading rather than
 * by sleeping. Sleeping would make the suite slower and would make the boundary case flaky on a
 * loaded runner, and the property being asserted -- that a decision stops being reused at a
 * particular instant -- is stated more directly by stepping past that instant than by waiting near it.
 * The cost is one constructor parameter on the gate, which its own documentation records.
 *
 * <p>Assumptions: the four rationale labels used here -- {@code Alternatives Considered:},
 * {@code Refactoring Rationale:}, {@code Assumptions:} and {@code Trade-offs:} -- are written in the
 * plural, colon-terminated, without parentheses and with the ASCII hyphen-minus, which is the only
 * accepted spelling across the migration trees.
 */
package com.carddemo.common.control;
