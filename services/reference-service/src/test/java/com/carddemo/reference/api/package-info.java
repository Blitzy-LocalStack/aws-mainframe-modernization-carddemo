/**
 * Tests over the reference REST surface, and in particular over its agreement with the published
 * contract.
 *
 * <p>Purpose: the contract document and the controllers are two descriptions of one interface, and
 * nothing but a test compares them. This package holds that comparison, in both directions: an operation
 * the document declares with no handler, and a handler with no declared operation, each fail the
 * build.</p>
 *
 * <p>Assumptions: the comparison is made against the annotations rather than against a running context,
 * so it needs no database, no queue and no token. That is deliberate -- a contract check that required
 * the whole application to start would be skipped on the runs where it matters most.</p>
 *
 * <p>Assumptions: user-specified Rule 1 (Explainability) requires a docstring on every module entry
 * point, and Checkstyle audits test sources as well as main sources, so this descriptor is required
 * rather than decorative. The written convention it follows is
 * {@code docs/CODE_DOCUMENTATION_STANDARD.md}.</p>
 */
package com.carddemo.reference.api;
