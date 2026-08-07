/**
 * Tests over the reference business rules, and in particular over the three write behaviours the
 * baseline distinguishes.
 *
 * <p>Purpose: the rules in {@code com.carddemo.reference.service} are transcriptions, so what has to be
 * asserted is not that they run but that they still differ from each other where the baseline differs. A
 * strict update that quietly inserted, or a batch run that abandoned itself at the first reject, would
 * both pass a test that only checked the happy path.</p>
 *
 * <p>Assumptions: the collaborators are doubles rather than a database. That is deliberate for these
 * cases: what is under test is a control-flow distinction -- whether a write happened at all, and in what
 * order -- and a double is the only way to assert that nothing was written, which a database cannot
 * distinguish from a write that was rolled back.</p>
 *
 * <p>Assumptions: user-specified Rule 1 (Explainability) requires a docstring on every module entry
 * point, and Checkstyle audits test sources as well as main sources. The written convention it follows is
 * {@code docs/CODE_DOCUMENTATION_STANDARD.md}.</p>
 */
package com.carddemo.reference.service;
