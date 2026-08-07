/**
 * The anti-corruption layer of the account bounded context: the one place a stored row becomes a published
 * value.
 *
 * <h2>What belongs here</h2>
 *
 * <p>Every projection from an entity to a transfer object, and nothing else. This is where the decisions
 * that are properties of the CONTRACT rather than of the row are made and justified in place: which fields
 * a caller may see at all, which are masked, which are withheld entirely, and how a fixed-point amount
 * crosses an interface. Concentrating them here is what keeps every other package free of them -- a
 * controller that assembled its own response would be a second place those decisions were made, and the
 * two would differ the first time one was changed.</p>
 *
 * <p>Assumptions: the projections are HAND-WRITTEN and no mapping generator is used. That is a deliberate
 * choice recorded in the migration plan's dependency inventory, and the reason is visible in this package:
 * the mapping is not mechanical. It masks a primary account number to its last four digits, withholds the
 * card verification value entirely, never returns a national or government-issued identifier, drops the
 * filler that padded a fixed-length record, and carries three corrected field spellings. Every one of those
 * needs a justification at the site where it happens, and a generated mapper cannot hold one.</p>
 *
 * <p>Assumptions: money crosses as a JSON STRING and never as a JSON number. A JSON number is parsed into
 * a binary floating-point value by most clients, which cannot retain every two-decimal value exactly, and
 * the reference transports these amounts as characters already. The shared kernel's money module fixes the
 * representation once, so nothing in this package restates it.</p>
 *
 * <h2>What does not belong here</h2>
 *
 * <p>No row access and no business rule. A mapper here receives an entity and returns a record; it does not
 * decide whether the entity should have been read, and it does not decide what happens when it was absent.
 * </p>
 *
 * <h2>Why this descriptor exists</h2>
 *
 * <p>Assumptions: the project Explainability rule requires a docstring on every module entry point, and in
 * Java a package declaration is that entry point. Separately, {@code JavadocPackage} is declared at Checker
 * level in {@code config/checkstyle/checkstyle.xml}, outside the tree walker, so a directory holding a
 * processed compilation unit without a {@code package-info.java} fails the build.</p>
 *
 * <p>A package charter accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception section.</p>
 */
package com.carddemo.account.mapper;
