/**
 * Data-access interfaces of the account bounded context, one per record this context owns.
 *
 * <h2>What belongs here</h2>
 *
 * <p>Three interfaces, mapping the three files the reference system's account context owns:
 * {@code ACCTDAT} through {@code AccountRepository}, {@code CUSTDAT} through
 * {@code CustomerRepository}, and {@code CCXREF} together with its {@code CXACAIX} alternate index
 * through {@code CardXrefRepository}. Each is a typed interface over one table, and each carries only
 * the access paths the reference system actually has -- which is why two of the three declare no method
 * at all beyond what they inherit.</p>
 *
 * <p>Assumptions: an access path that the reference does not have is deliberately NOT added. The account
 * master and the customer master are each defined with no alternate index, so every reference program
 * reaches them by identifier; a finder on any other column would compile, would work, and would be a
 * table scan behind an interface that looks like a lookup. The one secondary path that does exist --
 * cross-reference by account -- is declared explicitly and is backed by a real index the migration
 * creates.</p>
 *
 * <h2>What does not belong here</h2>
 *
 * <p>No business rule, no transaction boundary and no transport type. A repository here returns entities
 * or optionals of entities, and the decision about what a caller may see -- masking, withholding, the
 * choice between a projection and a full row -- is made in the mapper package, because that decision is a
 * property of the published contract rather than of the row.</p>
 *
 * <p>Alternatives Considered: hand-written statements through the entity manager, or a single generic
 * repository parameterised over the three entities. The first was rejected because it would restate the
 * column mapping the entities already declare, giving the schema a second definition to drift from; the
 * second because the three tables have genuinely different access paths and a shared interface would have
 * to admit the union of them to every caller.</p>
 *
 * <h2>Why this descriptor exists</h2>
 *
 * <p>Assumptions: two independent obligations put this file here. The project Explainability rule requires
 * a docstring on every module entry point, and in Java a package declaration is that entry point, so
 * {@code package-info.java} is the only compilation unit in which a package-level docstring can be written
 * at all. Separately and mechanically, {@code JavadocPackage} is declared at Checker level in
 * {@code config/checkstyle/checkstyle.xml}, outside the tree walker, so it is a file-set check: it demands
 * that a {@code package-info.java} exist in any directory holding a processed compilation unit, and its
 * absence fails the build rather than producing a warning.</p>
 *
 * <p>A package charter accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception section.</p>
 */
package com.carddemo.account.repository;
