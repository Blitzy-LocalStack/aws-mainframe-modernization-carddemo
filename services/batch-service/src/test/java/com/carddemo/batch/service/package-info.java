/**
 * Tests for the batch context's service layer: the posting reject chain and the types that own its
 * precedence.
 *
 * <p>Purpose: this package holds the tests for the rules the reference's batch programs carry, in the
 * module those rules migrate to. It exists so that a rule is asserted against the code that implements it:
 * a test that performed the rule itself would agree with itself whatever the production code did, and would
 * pass unchanged if that code were absent, inverted or never written.</p>
 */
package com.carddemo.batch.service;
