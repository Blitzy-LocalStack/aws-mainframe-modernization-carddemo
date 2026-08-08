/**
 * Tests for the account bounded context's persistent entities and, with them, its published projections.
 *
 * <p>Assumptions: this charter exists because Checkstyle's {@code JavadocPackage} rule audits a DIRECTORY
 * rather than a compilation unit, and this module's documentation gate includes test sources. A package
 * that holds a test class and no charter fails the build, so the charter has to govern the directory as a
 * whole.</p>
 *
 * <p>What belongs here: assertions about the three entities of the corresponding main-source package.
 * {@code DiagnosticRenderingTest} additionally covers five projection records from the sibling
 * {@code dto} package, and the placement is deliberate rather than convenient: the three entities are the
 * types whose confident and wrong disclosure rationales made that class necessary, so a reader arriving
 * from one of them should find the assertions beside it, and one class covering one rule across every
 * type it governs cannot fall out of step with itself the way two classes could.</p>
 *
 * <p>Trade-offs: these tests construct their subjects directly rather than loading a Spring context or a
 * database. That buys assertions naming the exact value that leaked, at the cost of proving nothing about
 * persistence mapping -- which the repository integration tests of this module cover instead.</p>
 */
package com.carddemo.account.domain;
