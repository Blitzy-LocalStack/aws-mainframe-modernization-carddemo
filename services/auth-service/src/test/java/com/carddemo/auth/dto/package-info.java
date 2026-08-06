/**
 * Tests for the request and response records of the auth bounded context.
 *
 * <h2>What belongs here</h2>
 *
 * <p>Assumptions: this package holds assertions about the records themselves -- their declared
 * constraints, the messages those constraints report, the order the reference checks them in, and the
 * renderings the records produce for a log. It deliberately does NOT hold web-layer slice tests, which
 * belong to {@code com.carddemo.auth.api}, nor contract-versus-configuration comparisons, which belong
 * to {@code com.carddemo.auth.config} where the published document is already loaded from the classpath.
 *
 * <p>Two subjects therefore live here, and they are named separately because they fail for unrelated
 * reasons: what a refused request body is TOLD, and what a response projection's string form REVEALS.
 *
 * <h2>Why a record needs its own tests at all</h2>
 *
 * <p>Refactoring Rationale: a declared constraint looks self-evidently correct and is not. The review
 * that prompted this package found that {@code SignOnRequest} carried the right constraints with the
 * wrong messages -- the validation provider's defaults rather than the two literals the reference
 * displays -- and that nothing ordered them, so a caller submitting an empty screen could be told about
 * the credential where the reference tells it about the identifier. Both defects are invisible to a
 * compiler: a message is a string attribute and an order is an emergent property of a provider's
 * iteration. Only an executed assertion distinguishes them.
 *
 * <p>Assumptions: the assertions here drive a REAL validation provider rather than reading the
 * annotations reflectively. Reading the attribute would confirm the literal was written down; running
 * the provider confirms it is the sentence a caller actually receives, which is the property
 * transformation rule T8 constrains.
 *
 * <h2>Why a generated string form is a disclosure and not a formatting detail</h2>
 *
 * <p>The two user projections in {@code com.carddemo.auth.dto} carry personal data: a given name, a
 * family name and, on the single-row response, the identity provider's subject for the person the row
 * describes. A Java record generates a string form that prints every component beside its value, so
 * leaving that form generated publishes those values into every log line, assertion message and
 * exception detail that stringifies an instance. Both records therefore override the string form and
 * substitute a placeholder, and this package is where that substitution is held to its claim.</p>
 *
 * <p>Assumptions: an override of this kind cannot be verified by a positive assertion alone. A test
 * that confirmed the rendering contains the row identifier would pass equally against a rendering that
 * also printed both names, so the obligation here is stated as an absence -- the withheld values do not
 * appear -- and an absence is only evidence when the check that looked for them is known to be capable
 * of finding them. That is why the leak detector in this package is applied first to a control string
 * built to contain the values, and only then to the real rendering.</p>
 *
 * <p>Trade-offs: those tests pin the exact text of two string forms, so any deliberate change to either
 * rendering breaks them and has to be made in two places. That is accepted, and it is the point rather
 * than a cost: the renderings exist to withhold specific values, and a change to one that nobody had to
 * acknowledge in a test is precisely the change that would quietly reintroduce the exposure. Pinning
 * the whole line rather than only the placeholder count is what makes a reordering or a newly added
 * component visible as well.</p>
 *
 * <h2>Alternatives considered for the location of these assertions</h2>
 *
 * <p>Alternatives Considered: folding them into {@code com.carddemo.auth.config.AuthApiContractTest},
 * which already loads the published contract and would let one class hold both halves of the
 * schema-versus-Java comparison, and would leave the module carrying one test package fewer. Rejected
 * because that class asserts a DOCUMENT against a CONFIGURATION and needs no application types to do
 * it, whereas these assertions instantiate records, run a provider and stringify instances; merging
 * them would make one class fail for several unrelated reasons and would hide which side of the
 * comparison broke. A reader looking for what this context does with personal data in a diagnostic
 * would also have no reason to open a file about path matching. The schema half of that comparison
 * stays there, where the document is already parsed, and the Java half is here.
 *
 * <h2>What this package deliberately does not assert</h2>
 *
 * <p>Assumptions: the sibling {@code SignOnResponse} also narrows its string form, replacing three
 * token values with a placeholder, and it is not covered here. Its narrowing is about credentials
 * rather than personal data, it was already in place and reviewed as adequate, and pulling it into this
 * package would mix the two subjects the sections above separate. Nothing about it is asserted or
 * assumed by the tests in this package beyond the placeholder literal the three types share, which is
 * noted where it is used rather than depended upon.</p>
 *
 * <h2>Why this descriptor exists</h2>
 *
 * <p>The project Explainability rule requires a docstring on every module entry point, and a Java
 * package declaration is that entry point. {@code package-info.java} is the only compilation unit that
 * can carry package-level Javadoc, so this file is required rather than decorative, and the
 * documentation gate audits test sources exactly as it audits main sources. Two Checkstyle modules
 * enforce the requirement independently: one requires the file to exist in a directory holding an
 * audited source, the other requires the file to carry a Javadoc block, so a descriptor holding a bare
 * package statement would satisfy the first and fail the second.</p>
 *
 * <p>Assumptions: the parameter, return and exception elements of the rule's docstring specification
 * describe callable code, so they are omitted from this block deliberately rather than written out
 * empty -- an at-clause carrying no description is itself a violation of the completeness module that
 * audits this build.</p>
 */
package com.carddemo.auth.dto;
