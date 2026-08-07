/**
 * The hand-written anti-corruption layer of the reference context, and the only place a copybook
 * representation concern may appear.
 *
 * <h2>Purpose</h2>
 *
 * <p>Every type here converts between {@code com.carddemo.reference.domain} and
 * {@code com.carddemo.reference.dto}. Its consumers are {@code com.carddemo.reference.service} and,
 * for the outbound direction only, {@code com.carddemo.reference.api}. Nothing here reads a
 * datastore, decides a business rule or holds state between requests.</p>
 *
 * <p>Assumptions: this package exists so that the two shapes on either side of it can each be clean.
 * Trailing blanks are padding to a declared width rather than data; a rate is a scaled decimal on one
 * side and a string on the wire; a code carries leading zeros that a numeric type would discard. Each
 * of those is a property of the source record rather than of the target domain, so each is resolved
 * here and nowhere else.</p>
 *
 * <p>Assumptions: all FIVE mappers are landed as compilation units beside this descriptor --
 * {@code DateInquiryReplyMapper}, {@code DisclosureGroupMapper}, {@code LookupMapper},
 * {@code TransactionCategoryMapper} and {@code TransactionTypeMapper}. Nothing is outstanding.
 * Refactoring Rationale: the count is stated as a measurement against the directory and the five are
 * named, because two earlier revisions of this paragraph each stated it as a plan and each was wrong
 * in a different direction -- one named a single mapper while four more sat beside it, the other
 * counted four while a fifth was already present. A roster consulted to learn which mapper owns a
 * conversion sends a reader to write one that exists whenever it under-reports, so the number has to
 * be re-measured whenever a file is added to this directory.</p>
 *
 * <h2>Why the conversion is written rather than generated</h2>
 *
 * <p>Alternatives Considered: MapStruct was evaluated and rejected for the whole project, and this
 * package is where the reason is most visible. The conversion is not mechanical: it trims padding on
 * some members and not others, it renames nothing here but renames elsewhere, and it moves a rate
 * between a scaled decimal and a string form. Each of those decisions needs a justification at the
 * point it is made, and a generated mapper has nowhere to carry one. Lombok is rejected on the
 * neighbouring ground that a generated accessor cannot carry the documentation the Explainability
 * rule requires.</p>
 *
 * <p>Assumptions: every mapper here is stateless and its members are static, so none is a Spring
 * bean and none is injected. A conversion that depends on nothing has no reason to be a component,
 * and making it one would invite a dependency to be added to it later.</p>
 *
 * <h2>Trailing blanks, and the one rule about them</h2>
 *
 * <p>Assumptions: a fixed-character column returns its value padded to the declared width, and the
 * padding is dropped on the way out and never on the way in. Dropping it outbound is what keeps a
 * description from being published with thirty spaces after it; keeping it inbound is what keeps a
 * composite key comparable, because the baseline's own fallback key carries trailing spaces
 * deliberately. A single rule applied in both directions would break one of the two.</p>
 *
 * <h2>Why this file exists</h2>
 *
 * <p>Assumptions: user-specified Rule 1 (Explainability) requires a docstring on every module entry
 * point, and in Java a package's entry point is its package declaration.
 * {@code config/checkstyle/checkstyle.xml} enforces that with {@code JavadocPackage} over the file
 * set and {@code MissingJavadocPackage} over the parsed tree, both bound to the {@code validate}
 * phase with violations failing the build. The written convention it follows is
 * {@code docs/CODE_DOCUMENTATION_STANDARD.md}.</p>
 */
package com.carddemo.reference.mapper;
