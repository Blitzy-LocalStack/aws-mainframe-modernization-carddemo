/**
 * Owns the Micrometer common tag set that every migrated CardDemo service attaches to every meter.
 *
 * <p><b>Purpose.</b> A common tag is a dimension the registry applies to each meter it publishes,
 * without the emitting code naming it at the call site. This package declares exactly three of them
 * -- {@code service}, {@code environment} and {@code version} -- and nothing else. Those three are
 * the whole of the contract: every question this package answers is a question about how a series is
 * labelled, and every question about what a series measures belongs to whichever bounded context
 * emits it. One production class, {@code MetricsConfig}, contributes the set to the registry through
 * a {@code MeterRegistryCustomizer}, which is why the eight service modules inherit the labelling by
 * depending on this library rather than by restating it apiece.</p>
 *
 * <p><b>Parameters, return values, exceptions or errors.</b> A package declaration accepts no
 * parameter, returns no value and raises nothing, so this descriptor carries no parameter, return or
 * exception at-clause. The inapplicability is declared rather than left silent, because the project
 * Explainability rule at its line 39 forbids a docstring that omits parameters, return values or
 * purpose, and a reader has to be able to tell a declared inapplicability from an oversight.
 * Fabricating the three at-clauses would also fail the repository ruleset, which audits at-clause
 * bodies for emptiness, so omitting them is the compliant reading of the rule and not a departure
 * from it.</p>
 *
 * <h2>The three tags, and what each one buys</h2>
 *
 * <ul>
 *   <li><b>{@code service}</b> -- partitions a series by the bounded context that owns it, so one
 *       series is attributable to exactly one of the eight services rather than to the migrated
 *       system as an undifferentiated whole. Absent this tag, two contexts publishing a same-named
 *       meter would sum into a single series and neither could be read back out of it.</li>
 *   <li><b>{@code environment}</b> -- keeps the development and production series apart inside one
 *       metrics namespace, so a spike observed in a development environment is never mistaken for a
 *       production one. This package embeds no environment name of its own: the value arrives from
 *       the deployment, which is what keeps one built artefact deployable to either environment.
 *       </li>
 *   <li><b>{@code version}</b> -- attributes a change in a series to the deployed image it came
 *       from, so a shift in a measurement is tied to the deployment that produced it instead of
 *       being correlated by hand against a release note.</li>
 * </ul>
 *
 * <p>The set is closed at three, and the closure is checkable rather than merely intended. It is
 * stated identically in three independent places: the migration plan's transformation row for this
 * package, the {@code common-lib} module descriptor's account of why {@code micrometer-core} is a
 * compile-scope dependency, and section 3.7 of {@code docs/architecture/observability.md}. A tag
 * added in any one of the three would put the three out of agreement, which is the property that
 * makes a drift visible. That document is this package's prose companion and is referenced in prose
 * only: a compilation unit does not import a Markdown file, and declaring the dependency would
 * invent a build ordering that does not exist.</p>
 *
 * <p>One consequence of the tag set being the whole contract is worth stating, because it is
 * something a reader may come here looking for and not find. No monetary amount is published as a
 * meter value anywhere in this migration. A meter value is recorded in a binary numeric type that
 * cannot represent every scale-two decimal exactly, whereas the money contract of this migration is
 * exact scale-two decimal arithmetic from the database column through to the wire; publishing a
 * balance or an interest amount as a measurement would convert the one into the other and forfeit
 * the exactness the contract exists to guarantee. Money is therefore counted and timed -- how many
 * postings, how long a posting took -- and never measured.</p>
 *
 * <h2>What the reference baseline published instead</h2>
 *
 * <p>The baseline's entire observability surface is job output, reachable through three channels
 * rather than one. Two are data definitions inside a step: {@code app/jcl/POSTTRAN.jcl} lines 26 and
 * 27 declare {@code //SYSPRINT DD SYSOUT=*} and {@code //SYSOUT   DD SYSOUT=*}, and
 * {@code app/jcl/INTCALC.jcl} lines 25 and 26 declare the same pair for the interest step. The third
 * is the job card itself: {@code app/jcl/POSTTRAN.jcl} line 1 carries {@code MSGCLASS=0}, which
 * routes the job log alongside those two streams, and line 2 carries {@code NOTIFY=&SYSUID}, which
 * is the baseline's notification mechanism and the ancestor of every alert the target raises. The
 * pairing is the standing convention of the batch tier and not a choice those two jobs happened to
 * make: {@code SYSOUT} appears on one hundred and seventeen lines across thirty-seven of the
 * thirty-eight jobs in {@code app/jcl}, and {@code SYSPRINT} on eighty lines across thirty-three of
 * them.</p>
 *
 * <p>There is no metric emission of any kind in the baseline -- no counter, no timer, no dimensional
 * label. Inspecting a run means inspecting a print file. That model was complete and functioning for
 * the platform it ran on, and it is file-shaped where the target is stream-shaped; the difference
 * the target introduces is one of mechanism, not one of quality.</p>
 *
 * <h2>The baseline's own structured vocabulary, acknowledged and not adopted</h2>
 *
 * <p>The baseline does carry one genuinely structured diagnostic record, and it matters here because
 * it is the ancestor of two things the target does.
 * {@code app/app-authorization-ims-db2-mq/cpy/CCPAUERY.cpy} declares {@code 01 ERROR-LOG-RECORD.} at
 * line 19 -- indented ten spaces, which is worth recording because a census pattern anchored at
 * seven or fewer leading spaces finds nothing in this file -- with eleven fields that sum to exactly
 * one hundred and twenty-two bytes: {@code ERR-DATE X(06)} at line 20, {@code ERR-TIME X(06)} at
 * line 21, {@code ERR-APPLICATION X(08)} at line 22, {@code ERR-PROGRAM X(08)} at line 23,
 * {@code ERR-LOCATION X(04)} at line 24, {@code ERR-LEVEL X(01)} at line 25,
 * {@code ERR-SUBSYSTEM X(01)} at line 30, {@code ERR-CODE-1 X(09)} at line 37,
 * {@code ERR-CODE-2 X(09)} at line 38, {@code ERR-MESSAGE X(50)} at line 39 and
 * {@code ERR-EVENT-KEY X(20)} at line 40.</p>
 *
 * <p>Two of those fields are load-bearing for this package. {@code ERR-APPLICATION X(08)} at line 22
 * means the baseline already carried an application dimension on every logged event, so the
 * {@code service} tag is not an invention of the target: the dimension existed, expressed once per
 * record, and what the target changes is its scope and its guarantee -- from a value a writer
 * populates per event to a label the registry applies unconditionally to every meter. That is a
 * promotion of an existing dimension, not the addition of one without precedent.
 * {@code ERR-EVENT-KEY X(20)} at line 40 is the baseline's own correlation identifier, which is why
 * the migrated {@code CorrelationIdFilter} formalises an existing concept rather than introducing
 * one; that filter belongs to {@code com.carddemo.common.web} and not to this package, because
 * correlation travels with a request whereas a common tag travels with a meter.</p>
 *
 * <p>The remaining two fields carry value domains this package deliberately does not adopt.
 * {@code ERR-LEVEL} has exactly four condition names at lines 26 to 29 -- {@code 'L'} for log,
 * {@code 'I'} for info, {@code 'W'} for warning and {@code 'C'} for critical, with no debug value
 * and no split between error and fatal. {@code ERR-SUBSYSTEM} has exactly six at lines 31 to 36 --
 * {@code 'A'} for application, {@code 'C'} for CICS, {@code 'I'} for IMS, {@code 'D'} for Db2,
 * {@code 'M'} for message queueing and {@code 'F'} for file. Both domains belong to the structured
 * log line and to the problem shape, which are owned by {@code com.carddemo.common.error} and
 * described in section 3.3 of {@code docs/architecture/observability.md}, where the subsystem
 * dimension is retained against a re-based value domain and the level mapping is recorded as a
 * decision rather than an identity. Section 3.7 of that same document describes this package's
 * surface and lists three tags. The boundary between the two sections is drawn here explicitly so
 * that it is not re-derived later into a fourth or a fifth tag.</p>
 *
 * <h2>Decision record</h2>
 *
 * <p>Alternatives Considered: a {@code com.carddemo.common.config} package was the obvious
 * alternative home for the one class this package holds, since that class is named
 * {@code MetricsConfig} and is a Spring configuration class. It was rejected, and the rejection is
 * structural rather than stylistic. This library has no application-layer subpackage of any kind --
 * no controller, service, repository, domain, transfer-object, mapper or configuration package -- so
 * filing the class by its Spring stereotype would have introduced the first one. Filing it by
 * concern instead keeps all eight subpackage names the names of contracts, so a reader looking for
 * the metric tag contract finds it under the concern it labels rather than having to know in advance
 * that the contract happens to be expressed as a configuration class. The cost is that a reader
 * hunting by stereotype has to learn the concern first. The gain is that the question of which
 * subpackage a new type belongs in keeps one defensible answer as the tree grows, because eight
 * contract names admit that question and a bucket named for a stereotype does not.</p>
 *
 * <p>Alternatives Considered: promoting the baseline's severity and subsystem domains to common tags
 * was evaluated and rejected. Four severity values and six subsystem values are sitting in
 * {@code CCPAUERY.cpy} ready to be used, and adopting either would have produced a four or five tag
 * set that looked more faithful to that record. It is the wrong faithfulness. A common tag is
 * applied to every meter unconditionally, so a severity tag would have to carry some value on the
 * many meters that have no severity at all, and a subsystem tag would partition every series by a
 * dimension the target runtime no longer has -- there is no CICS, IMS or message-queueing subsystem
 * left to name. Both domains are per-event properties of a log line, which is a different surface
 * with a different owner, and leaving them on that surface is precisely what stops the tag set
 * drifting from three to five.</p>
 *
 * <p>Refactoring Rationale: what the target replaces is not the content of the job log but its
 * addressability. A spooled job log is retrieved per job, by an operator who already knows which job
 * to go and look at, and all three baseline channels above -- {@code SYSPRINT}, {@code SYSOUT} and
 * the job card's {@code MSGCLASS} -- resolve to that same retrieval model. The target needs the same
 * content queryable across services and correlatable across one whole execution, because a business
 * operation that used to be a step inside one job is now a request crossing several independently
 * deployed services. A print file cannot answer which service, which environment and which deployed
 * image for a series it never labelled, and a per-job retrieval cannot span an execution it was
 * never scoped to. The three tags are what make a series addressable along exactly those axes.
 * Nothing about the baseline mechanism is treated here as defective: it addressed one job at a time
 * because one job at a time was the unit of work.</p>
 *
 * <p>Assumptions: this package owns none of the three values it labels with, and depends on four
 * external contracts for them. The {@code service} value is assumed to be the owning bounded
 * context's own name, stable for the life of that context, so that renaming it is a deliberate break
 * in series continuity rather than an accident. The {@code environment} value is assumed to be
 * supplied by the deployment that runs the container and never compiled in, which is the assumption
 * that lets one artefact serve every environment. The {@code version} value is assumed to identify
 * the deployed image rather than a source revision, because a deployment is what shifts a series and
 * so a deployment is what has to be identifiable from one. And all three are assumed to be of low
 * and bounded cardinality -- a small closed set of contexts, a small closed set of environments, and
 * one value per deployment -- because a common tag multiplies the series count of every meter it is
 * attached to. This package further assumes that a registry exists at runtime while declaring none:
 * no registry implementation is a dependency of this library, so which backend receives the series
 * is a deployment decision and not a compile-time one.</p>
 *
 * <p>Trade-offs: the set is confined to three tags deliberately, and the compromise runs in both
 * directions. Three tags cannot express everything an operator might want to slice by -- region,
 * availability zone, instance, tenant and endpoint are all absent, and an operator who wants one of
 * them has to add it at the meter that needs it instead of finding it already present on every
 * series. What is bought is that the cost stays bounded and predictable: because every common tag
 * multiplies the cardinality of every meter in the process, the three chosen are exactly those whose
 * value sets are small, closed and known before a series is published. A tag drawn from an open
 * value set, an account identifier or a card number for instance, would be unbounded on every meter
 * at once, and that is the one mistake in this area that cannot be undone once the series exist.
 * Confining the set here also means the decision is taken once, in one place, rather than
 * renegotiated inside each service.</p>
 *
 * <h2>Contents of this package</h2>
 *
 * <p>This package holds exactly one production class, {@code MetricsConfig}, which contributes the
 * three tags named above to the meter registry. With this descriptor beside it the directory holds
 * exactly two {@code .java} files and no subdirectory. Across {@code com.carddemo.common} as a whole
 * the production classes number seventeen, distributed as two in {@code money}, five in
 * {@code codec}, three in {@code error}, two in {@code web}, one in {@code security}, one here in
 * {@code observability}, one in {@code time} and two in {@code validation}, which is
 * 2 + 5 + 3 + 2 + 1 + 1 + 1 + 2 = 17. Adding the nine package descriptors, one for the package root
 * and one for each of its eight subpackages, gives twenty-six {@code .java} files in total.</p>
 *
 * <p>Assumptions: that canon is arithmetic, and it is restated per package on purpose so that a
 * class which is missing stays distinguishable from a class that was never planned. An earlier draft
 * of the migration plan carried a higher production-class figure; it is superseded, and seventeen,
 * nine and twenty-six are the figures to propagate. Note also that no package descriptor exists at
 * {@code com} or at {@code com/carddemo}: the canon counts nine, and the Checkstyle module that
 * requires a descriptor fires only for a directory holding an audited source file, which neither of
 * those two directories does.</p>
 *
 * <h2>Why this descriptor exists</h2>
 *
 * <p>The project Explainability rule requires a docstring on every module entry point at its line
 * 15, and a Java package declaration is that entry point; {@code package-info.java} is the only
 * compilation unit able to carry package-level Javadoc, so this file is required rather than
 * decorative. Two build gates enforce that requirement independently and neither is redundant. The
 * Checkstyle {@code JavadocPackage} module operates over the file set and requires that this file
 * exist in any directory holding an audited source file, whereas {@code MissingJavadocPackage}
 * operates on the parsed syntax tree and requires that the file carry a Javadoc block on its package
 * declaration. An empty descriptor satisfies the first and fails the second, which is why the block
 * above is the deliverable and the file's mere existence is not.</p>
 *
 * <p>Because this compilation unit contains a single statement, the decision rationale that the
 * rule's validation gate at its line 43 requires alongside the docstring has no adjacent executable
 * code to sit beside. It is therefore carried inside this block under the rule's own category
 * labels, which is the only placement the language makes available. That gate is conjunctive -- a
 * missing docstring and a missing rationale each fail it on their own -- so both halves are present
 * here by construction rather than by preference.</p>
 */
package com.carddemo.common.observability;
