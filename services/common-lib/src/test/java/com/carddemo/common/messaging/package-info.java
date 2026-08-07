/**
 * Verifies the queue-metadata rules of {@code com.carddemo.common.messaging}, and in particular that
 * they are DIFFERENT from the servlet header rules they were once mistaken for.
 *
 * <h2>What is exercised here</h2>
 *
 * <p>One contract is under test and it has two halves that pull against each other. A correlation
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
