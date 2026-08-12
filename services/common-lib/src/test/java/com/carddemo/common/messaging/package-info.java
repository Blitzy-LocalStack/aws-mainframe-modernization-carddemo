/**
 * Verifies the queue-metadata rules of {@code com.carddemo.common.messaging}, and in particular that
 * they are DIFFERENT from the servlet header rules they were once mistaken for.
 *
 * <h2>What is exercised here</h2>
 *
 * <p>Four production types live in the package under test and each has a test class here:
 * {@code MessagingCorrelationIdTest} and {@code MessageExpiryTest} cover the two value rules described
 * below, {@code QueueClientBudgetTest} covers the arithmetic relating a queue client's time bounds to
 * the visibility period of the message its handler holds, and {@code RethrowingDigestErrorHandlerTest}
 * covers what a FAILED delivery records and what it rethrows. The first two are about what a single value
 * may contain; the third is about how three values must be ordered against one another, which is why it is
 * stated as its own class rather than as further cases on either of the others.</p>
 *
 * <p>A fifth class here, {@code MessageSinkSuppressionTest}, has no production type of its own in this
 * package because its subject is a CONFIGURATION line: the shared defaults switch off one framework logger
 * by its fully qualified name, and the handler above is the replacement record that line assumes exists.
 * The two are asserted together in this directory because neither is sufficient alone -- suppressing the
 * framework record with no replacement makes failures invisible, and adding a replacement without the
 * suppression leaves the original exposure in place beside it.</p>
 *
 * <p>Refactoring Rationale: the handler's rethrow is asserted at least as heavily as its log rendering, and
 * that balance is deliberate. The pinned starter installs its error-handler stage as a RECOVERY step, so a
 * handler that returned normally would leave the pipeline result successful and the acknowledgement stage
 * that runs after it would DELETE the message. A handler edited into swallowing would therefore not lose a
 * log line, it would lose the message -- no visibility-timeout redelivery, no dead-letter at the fifth
 * receive -- and no assertion about logging would catch it, which is why the swallow case has cases of its
 * own.</p>
 *
 * <p>Refactoring Rationale for the third: the budget rule was originally going to be written once per
 * service, inside each queue client's configuration class. Stating it here instead means the comparison
 * that prevents two consumers acting on one message -- a whole-call bound that must expire before the
 * queue can redeliver -- is asserted in one place at the boundary values, rather than three times at
 * whatever value each service happened to configure. The rule deliberately holds no client type, so it is
 * testable without a broker or a software development kit on the classpath.</p>
 *
 * <p>The remaining contract under test has two halves that pull against each other. A correlation
 * identity arriving on a queue attribute must be echoed back to the requester verbatim, because that
 * value is the only thing pairing an answer with its question -- so the rule admitting it has to be
 * WIDE enough for every rendering a requester can produce from the reference baseline's twenty-four
 * byte field: hexadecimal at 48 characters, base64 with its own punctuation, an opaque token, a
 * dashed UUID. The same value reaching a log record must not be able to terminate a field or forge a
 * line -- so the rule governing that rendering has to be NARROW, admitting letters, digits and four
 * separators and neutralising everything else at the same length.</p>
 *
 * <p>Alternatives Considered: exercising the messaging rule only through the listener that consumes
 * it. Rejected, because a listener test that supplies one well-formed value and one malformed one
 * cannot state the boundary; it demonstrates two points on it. Testing the rule directly is what
 * makes the admitted set and the log-safe set enumerable, and the enumeration is the contract a
 * requester depends on.</p>
 *
 * <p>Trade-offs: these tests reference {@code com.carddemo.common.web.CorrelationIdFilter} in order
 * to assert that a legitimate messaging identity FAILS the servlet rule. Reaching across packages in
 * a test is normally worth avoiding, and it is accepted here for one reason: the divergence between
 * the two rules is the whole justification for this package existing, and a divergence that is only
 * described in prose can be silently removed. Asserting it executably means a later change that
 * narrows the messaging rule back to the servlet rule fails in this directory rather than on the
 * queue.</p>
 *
 * <p>Assumptions: nothing here asserts a particular identity TEXT. The values used are chosen to
 * exercise a class of character -- printable punctuation, a control character, a space, a non-ASCII
 * code point, an over-long run -- so the assertions describe the rule rather than a sample.</p>
 *
 * <p>A package charter accepts no parameter, yields no value and raises nothing, so this block
 * carries no parameter, return or exception section.</p>
 */
package com.carddemo.common.messaging;
