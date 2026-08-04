package com.carddemo.common.web;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.HexFormat;
import org.slf4j.MDC;

/**
 * Threads one correlation identity through an HTTP request: accepted on the way in, published to the
 * logging context for the life of the request, and echoed on the way out.
 *
 * <h2>The identity contract</h2>
 *
 * <p>Three obligations define this filter, and each of the three is inherited from a reference-only
 * program rather than invented here:</p>
 *
 * <ul>
 *   <li>an identity supplied by the caller is echoed back <b>exactly as it arrived</b>, so the caller
 *       can match a response to the request it sent;</li>
 *   <li>when the caller supplies none, one is minted rather than the request being refused;</li>
 *   <li>the identity occupies at most {@code CORRELATION_ID_MAX_LENGTH} characters, and a minted one
 *       occupies that width exactly.</li>
 * </ul>
 *
 * <p>The identity travels two ways out of this filter and both are part of the contract. It goes into
 * the SLF4J mapped diagnostic context under {@code CORRELATION_ID_MDC_KEY}, so that every log line
 * any downstream component emits while serving the request carries it without that component having
 * to know it exists. And it goes onto the response under {@code CORRELATION_ID_HEADER}, so that a
 * caller holding a response can name the unit of work in a support conversation. That in-and-out
 * symmetry is the whole point: this class is the HTTP analogue of the message-broker correlation
 * identifier the reference baseline already relied on.</p>
 *
 * <p>Assumptions: the identity is <b>not</b> a credential. It carries none, it is derived from none,
 * and it confers no authority whatsoever. It is written to a response header and to log output, both
 * of which are read by people and by tooling that is not the caller, so treating it as a secret would
 * be a category error. It is minted unpredictably for the separate reason argued on
 * {@link #generateCorrelationId()}, which is about not handing out a means to guess the identities of
 * other requests, not about protecting the value itself.</p>
 *
 * <h2>Lineage: {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl}</h2>
 *
 * <p>That program is 1026 lines and is reference-only. It is read, cited by path and line, and never
 * modified. It consumes a request message, decides an authorization, and replies; the discipline it
 * applies to the correlation identifier across that round trip is the specification this class
 * implements over HTTP. Four of its lines settle the behaviour above.</p>
 *
 * <p><b>Width.</b> Line 45 declares {@code 05 WS-SAVE-CORRELID PIC X(24).} That is where
 * {@code CORRELATION_ID_MAX_LENGTH} comes from. Assumptions: this width is quotable precisely because
 * it is declared in the program's <b>own</b> working storage. The eight broker copybooks the program
 * includes -- at its lines 149, 152, 155, 158, 161, 164, 167 and 170 -- are supplied by the message
 * broker and resolved when the program is compiled on its original platform; a search of this
 * repository for them returns nothing at all. Any width that lives only inside one of those
 * copybooks is therefore not verifiable from this repository, and no such width is asserted anywhere
 * in this class. The three widths that <b>are</b> verifiable are enumerated below.</p>
 *
 * <p><b>Echo, and mint when absent.</b> Line 745 moves the saved inbound identifier straight into the
 * reply descriptor, unaltered. Line 746 moves the no-identifier constant {@code MQMI-NONE} into the
 * reply's own message identifier, which is the baseline's idiom for "there is no inbound value here,
 * so start fresh". Those two lines sit next to each other and do opposite things on purpose: one
 * field is carried across, the adjacent one is originated. This filter reproduces exactly that
 * split -- carry the caller's identity, originate what the caller did not supply.</p>
 *
 * <p><b>The identity is a token, never a selector.</b> Line 395 moves {@code MQMI-NONE} and line 396
 * moves {@code MQCI-NONE} into the descriptor used to read the next request, which makes that read
 * match <b>any</b> waiting message rather than one bearing a particular identifier. Assumptions: the
 * correlation identity is therefore carried and echoed but never selected on. Nothing in this
 * package or downstream of it may route, shard or authorize by this value, and no consumer should
 * build such behaviour on top of it, because the baseline it mirrors never did.</p>
 *
 * <p><b>Nothing is hard-coded.</b> The queue the program reads is handed to it at line 238 from its
 * trigger data; the queue it replies to is taken from the inbound message itself at lines 413 to 414
 * and applied at lines 741 to 742. That per-message destination is only possible because line 758
 * calls {@code MQPUT1}, which opens, puts and closes for each message, rather than putting to a
 * queue opened once. Assumptions: configuration in this migration is injected, never written into
 * code. This class holds that line by expressing the header name, the context key and the width as
 * named constants instead of literals buried in a method body, and by containing no endpoint,
 * hostname, queue name or credential of any kind.</p>
 *
 * <h2>Refactoring Rationale: what this filter replaces</h2>
 *
 * <p>Correlation identity already exists in the reference baseline, so this class formalises an
 * established concept rather than introducing one.
 * {@code app/app-authorization-ims-db2-mq/cpy/CCPAUERY.cpy} is 40 lines. At line 19 it declares
 * {@code 01 ERROR-LOG-RECORD.}, a structured log record of eleven fields whose declared widths sum
 * to 122 bytes: a date and a time of six each at lines 20 and 21, an application and a program name
 * of eight each at lines 22 and 23, a four-character location at line 24, a one-character level at
 * line 25 with its four condition names at lines 26 to 29, a one-character subsystem at line 30 with
 * its six condition names at lines 31 to 36, two nine-character codes at lines 37 and 38, a
 * fifty-character message at line 39, and -- as the very last line of the copybook -- line 40's
 * {@code 05 ERR-EVENT-KEY PIC X(20).}</p>
 *
 * <p>That last field is the baseline's own correlation key. What was wrong with the old approach is
 * not the concept but its custody: the field is <b>application-managed</b>, so it is only populated
 * where a programmer remembered to populate it, and only in the programs that adopted the structured
 * record at all. The programs that did not adopt it log without any correlation key whatsoever, and
 * that gap is measurable rather than hypothetical. {@code app/cbl/COTRN00C.cbl} emits an unstructured
 * line and raises a flag at lines 613 and 614, and repeats the identical pair at lines 647 and 681.
 * {@code app/cbl/COBIL00C.cbl} repeats it twice more, at lines 461 and 490. Five sites, no
 * correlation identity at any of them. Moving custody from the application to this filter is what
 * closes that gap: a request cannot reach a handler without passing through the chain, so there is no
 * code path left on which the identity can be forgotten.</p>
 *
 * <h2>Refactoring Rationale: the reply-durability window, and where it is answered</h2>
 *
 * <p>A correlation identity makes a request and its reply <b>matchable</b>. It does not make a reply
 * <b>durable</b>, and the baseline shows precisely why the two must not be confused. In
 * {@code COPAUA0C.cbl} the database commit is issued at line 335, inside the
 * {@code EXEC CICS SYNCPOINT} that spans lines 334 to 336. The reply is published far later, by the
 * call at line 758. Those two points are 423 lines apart, and neither broker call is enrolled in the
 * unit of work that line 335 commits: the read composes its options from {@code MQGMO-NO-SYNCPOINT}
 * at line 389 and the publish composes its options from {@code MQPMO-NO-SYNCPOINT} at line 753. The
 * consequence is a window -- if the process ends anywhere inside it, the database records a decision
 * that was made while no reply was ever published, and the correlation identity that would let anyone
 * notice belongs to a reply that does not exist.</p>
 *
 * <p>Alternatives Considered: the syncpoint-participating forms of both calls,
 * {@code MQGMO-SYNCPOINT} and {@code MQPMO-SYNCPOINT}, were available to the baseline at those same
 * two lines and were not used. The baseline therefore chose at-least-once delivery with the reply
 * published outside the commit, which is a legitimate choice with a known cost rather than an
 * oversight. The migration implements the other trade: the reply is committed together with the data
 * it reports and published afterwards from a transactional outbox, so a reply exists for every
 * decision that committed. The baseline publishes outside the commit; the migrated code publishes
 * from an outbox inside it; the divergence is documented. That outbox belongs to the service that
 * owns pending authorization data, not to this filter and not to this module -- the shared kernel
 * carries the identity half of the problem and takes no position on message durability.</p>
 *
 * <h2>Assumptions: two units for one interval, one hundred apart</h2>
 *
 * <p>{@code COPAUA0C.cbl} expresses the same five seconds twice, in two different units, with no
 * comment at either site. Line 242 moves {@code 5000} into the wait interval, which is consumed at
 * line 393 and is denominated in <b>milliseconds</b>. Line 750 moves {@code 50} into the reply
 * expiry, which is denominated in <b>tenths of a second</b>. Both are five seconds. Read either
 * literal without carrying its unit along and the answer is wrong by a factor of ten in one
 * direction or the other, and nothing in the source warns of it. The bearing on this class is a
 * standing rule rather than a value: every quantity here is a count of characters or a count of
 * bytes, each is named as such on its declaration, and this class deliberately holds <b>no</b>
 * duration, timeout or expiry at all. A duration would be the third unit in the same neighbourhood,
 * and there is nothing about the life of a correlation identity that needs one -- it is scoped to the
 * request, which begins and ends without reference to a clock.</p>
 *
 * <h2>Assumptions: three declared widths, and only three</h2>
 *
 * <p>Three widths are verifiable from this repository, they mean different things, and conflating any
 * two of them would corrupt the contract:</p>
 *
 * <ul>
 *   <li><b>20</b> characters -- the structured log event key,
 *       {@code CCPAUERY.cpy} line 40;</li>
 *   <li><b>24</b> characters -- the correlation identity itself, {@code COPAUA0C.cbl} line 45, and
 *       the one this class implements;</li>
 *   <li><b>48</b> characters -- <b>queue names</b>, {@code COPAUA0C.cbl} lines 43 and 44. This width
 *       describes a destination and has nothing to do with an identity. It is recorded here only so
 *       that a reader who meets it elsewhere does not mistake it for a wider correlation field.</li>
 * </ul>
 *
 * <p>There is no fourth width. Any width belonging to a field declared inside one of the eight absent
 * broker copybooks is unverifiable here and is not stated.</p>
 *
 * <h2>Trade-offs: one measurement habit of the baseline that is not carried across</h2>
 *
 * <p>The reference program measures its reply with a single field that plays two roles, and the
 * arithmetic is worth following because this class deliberately declines to imitate it.
 * {@code COPAUA0C.cbl} line 46 declares {@code 05 WS-RESP-LENGTH PIC S9(4) VALUE 1.} -- a one-based
 * <b>position</b>, seeded at the first character. Line 730 hands that field to a concatenation as its
 * pointer, which leaves it standing one place past the last character transferred. Line 756 then
 * moves that same field into the length of the message to be published, so a position is spent as
 * though it were a count.</p>
 *
 * <p>The gap this opens is exactly one byte, and it is measurable rather than theoretical. The reply
 * layout at {@code app/app-authorization-ims-db2-mq/cpy/CCPAURLY.cpy} lines 19 to 24 declares six
 * fields of 16, 15, 6, 2, 4 and 14 characters, which is 57, and the concatenation places one
 * separator after each of the six, giving 63 characters of payload. A pointer seeded at 1 therefore
 * comes to rest at 64, and 64 bytes are published for a payload of 63. The baseline spends one field
 * as both a position and a length; this class keeps the two apart; the divergence is documented.</p>
 *
 * <p>Holding them apart here is a rule with a visible shape rather than a sentiment.
 * {@code CORRELATION_ID_MAX_LENGTH} is a count of characters and is only ever compared against a
 * length. The loop variable in {@link #isContractConforming(String)} is a zero-based position and is
 * only ever used to index. Neither is ever assigned to the other, and no arithmetic in this class
 * converts one into the other. Trade-offs: keeping two concepts where the baseline kept one costs a
 * little more vocabulary, and it buys the guarantee that an identity this class admits is exactly as
 * wide as it claims to be -- which is the whole of what the width contract asserts.</p>
 *
 * <h2>Assumptions: how this class reaches a running service</h2>
 *
 * <p>The SLF4J mapped diagnostic context arrives transitively. {@code org.slf4j.MDC} is on this
 * module's compile path because the logging starter sits inside the starters the module already
 * declares; SLF4J is deliberately not declared on its own and this class required no change to the
 * module descriptor. Declaring it directly would open a second independently resolved path to the
 * logging API, and when two such paths disagree the binding selected at start-up is effectively
 * arbitrary -- the symptom being log output that silently goes missing rather than a build that
 * fails.</p>
 *
 * <p>Assumptions: this filter is registered by the <b>consuming service</b>, and nothing in this
 * module registers it. The shared kernel is a library, not a deployable: it carries no application
 * entry point, no runtime configuration resource, no schema migration, no interface description and
 * no container image of its own. Shipping a configuration resource from here to self-register would
 * contradict that, and the repository-wide counts say so plainly -- nine build modules, eight
 * container definitions, ten images -- so a service wires this filter into its own chain and this
 * class supplies only the behaviour being wired. A service registering it should give it the
 * outermost position, because an identity that is established after some other component has already
 * logged is an identity missing from exactly the lines most likely to be read.</p>
 *
 * <p>Assumptions: no executable golden-master oracle exists for this class, and none is claimed. The
 * reference functional-parity suite exercises batch flows, and {@code COPAUA0C.cbl} cannot be
 * compiled by that suite's open-source compiler at all, because the eight broker copybooks it
 * includes are absent from this repository. Behaviour here is therefore established by this module's
 * own unit tests -- the width contract, the verbatim echo, minting when the header is absent,
 * presence in the logging context and on the response, removal on both the normal and the exceptional
 * path, and the absence of residue on a reused thread.</p>
 *
 * <p>This class holds no mutable state. Its two shared instances are documented on their
 * declarations as safe for concurrent use, which is what allows one filter instance to serve every
 * request thread.</p>
 */
public final class CorrelationIdFilter implements Filter {

    /**
     * The request and response header that carries the correlation identity.
     *
     * <p>Assumptions: the name carries the {@code X-} convention for a header that is not registered
     * with a standards body, and it is spelled identically on the way in and on the way out so that
     * one constant governs both directions. A caller that supplies this header has its value echoed;
     * a caller that omits it receives a minted one under the same name.</p>
     *
     * <p>Alternatives Considered: reading the name from configuration at start-up. Rejected, because
     * the name is half of a wire contract shared with every caller and every other service in the
     * migration, so a value that can differ between two deployments of the same system is a way for
     * the contract to break silently -- one side reading a header the other never wrote, with no
     * error anywhere. It is exposed as a constant instead, which lets a consuming service and a test
     * name the header without repeating the literal, and keeps the reference in this class from being
     * a magic value buried in a method body.</p>
     */
    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

    /**
     * The key under which the identity is published to the SLF4J mapped diagnostic context.
     *
     * <p>Assumptions: a logging configuration references this key by name to place the identity in
     * each rendered line, so the value is a contract with that configuration and not an internal
     * detail. It is exposed for the same reason the header name is: so a service's logging
     * configuration and this module's tests can name one thing rather than repeat a literal.</p>
     *
     * <p>Alternatives Considered: reusing the header name as the context key. Rejected, because the
     * two are read by different consumers -- the header by a caller across the network, the key by a
     * log pattern in the same process -- and tying them together would mean a change demanded by
     * either consumer silently rewriting the other's contract.</p>
     */
    public static final String CORRELATION_ID_MDC_KEY = "correlationId";

    /**
     * The greatest number of characters a correlation identity may occupy, twenty-four.
     *
     * <p>Assumptions: this is the declared width of {@code 05 WS-SAVE-CORRELID PIC X(24).} at
     * {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl} line 45. It is a maximum rather than
     * an exact requirement for inbound values, and exact for minted ones. The reason it is a maximum
     * is that the baseline field is of declared width and pads what it holds with spaces, and this
     * migration treats trailing spaces in such a field as padding rather than as data; a caller
     * sending a shorter identity has therefore sent a complete identity, not a truncated one, and
     * padding it out here would be re-formatting a value this filter has undertaken to echo
     * unaltered.</p>
     *
     * <p>Alternatives Considered: no bound at all, accepting whatever a caller sends. Rejected for a
     * specific reason rather than a stylistic one: the value has to survive a round trip through a
     * representation of this declared width without changing, and a value wider than the width
     * cannot. Accepting one would mean either that the identity a caller correlates on is not the
     * identity that reaches the other side of that representation, or that this class has quietly
     * abandoned the contract it exists to honour.</p>
     */
    public static final int CORRELATION_ID_MAX_LENGTH = 24;

    /**
     * The count of random bytes rendered into a minted identity, twelve.
     *
     * <p>Assumptions: hexadecimal rendering yields two characters per byte, so twelve bytes yield
     * exactly {@code CORRELATION_ID_MAX_LENGTH} characters and carry ninety-six bits of entropy. The
     * relationship between the two constants is arithmetic and is asserted by this module's tests
     * rather than left as a coincidence for a maintainer to discover after changing one of
     * them.</p>
     */
    private static final int GENERATED_ID_RANDOM_BYTES = CORRELATION_ID_MAX_LENGTH / 2;

    /**
     * The lowest character code an inbound identity may contain, {@code 0x21}.
     *
     * <p>Assumptions: this bound and its upper counterpart together admit only the printable
     * single-byte range and exclude the space at {@code 0x20}. Excluding the space is deliberate and
     * is argued on {@link #isContractConforming(String)}: a field of declared width pads with
     * spaces, so a space inside an identity cannot be told apart from padding once the value crosses
     * into that representation.</p>
     */
    private static final char LOWEST_ACCEPTED_CHARACTER = 0x21;

    /**
     * The highest character code an inbound identity may contain, {@code 0x7E}.
     *
     * <p>Assumptions: every code from the lower bound through this one occupies exactly one byte when
     * encoded as single-byte ASCII, which is what makes a count of characters and a count of bytes
     * the same measurement and therefore what makes the twenty-four width contract mean one
     * unambiguous thing. Anything above this bound is either a control code or requires more than one
     * byte to encode, and both would break that equivalence.</p>
     */
    private static final char HIGHEST_ACCEPTED_CHARACTER = 0x7E;

    /**
     * The request attribute that marks this filter as already applied to the current request.
     *
     * <p>Assumptions: a servlet container runs the filter chain again for an internal dispatch, so a
     * request that is forwarded, included, resumed asynchronously or routed to an error handler
     * passes this filter more than once. The reason that matters is argued at the guard in
     * {@link #doFilter(ServletRequest, ServletResponse, FilterChain)}. The name is derived from the
     * class rather than written out, so it cannot collide with an attribute another component
     * chose.</p>
     */
    private static final String FILTER_APPLIED_ATTRIBUTE =
            CorrelationIdFilter.class.getName() + ".APPLIED";

    /**
     * The entropy source for minted identities.
     *
     * <p>Assumptions: {@link SecureRandom} is documented as safe for concurrent use, which is what
     * allows one shared instance to serve every request thread. Constructing one per request would
     * repeat seeding work that yields no additional unpredictability, and under concurrent load
     * several instances seeded from the same platform state is a worse position than one instance
     * that has already been seeded once.</p>
     */
    private static final SecureRandom ENTROPY_SOURCE = new SecureRandom();

    /**
     * The renderer that turns random bytes into upper-case hexadecimal characters.
     *
     * <p>Assumptions: {@link HexFormat} instances are immutable and safe for concurrent use, so this
     * one is shared for the same reason the entropy source is. Upper case is chosen because the
     * declared-width character field this identity round-trips through conventionally holds upper
     * case, so a minted value already reads as something that belongs in it; the choice has no
     * bearing on an inbound value, which is echoed in whatever case it arrived in.</p>
     */
    private static final HexFormat IDENTITY_RENDERER = HexFormat.of().withUpperCase();

    /**
     * Creates a filter instance for a consuming service to register in its own chain.
     *
     * <p>Assumptions: a consuming service constructs this filter and places it in its chain; nothing
     * in this module does so, because the shared kernel ships no configuration resource. The
     * constructor takes no argument and the instance holds no state, so one instance serves every
     * request thread and a service is free to hold it as a singleton.</p>
     *
     * <p>Alternatives Considered: leaving the constructor implicit. Rejected, because the fact that
     * this class is meant to be instantiated -- and instantiated by the consumer rather than by
     * anything here -- is exactly the sort of thing a reader arrives wanting to know, and an implicit
     * constructor is the one member of a class that cannot be documented. Declaring it costs one
     * member and puts the registration contract where it is looked for.</p>
     */
    public CorrelationIdFilter() {
        // WHY : Alternatives Considered: overriding init and destroy was evaluated and rejected. Both
        //       are default methods on the servlet filter interface with empty bodies, and this class
        //       has nothing to build up when a container starts it and nothing to release when the
        //       container discards it -- its two shared instances are static, immutable and created
        //       when the class loads. Overriding them would add two members asserting a lifecycle
        //       this class does not have, and the documentation gate would then require both to be
        //       described, so the cost would be paid in prose about behaviour that does not exist.
    }

    /**
     * Establishes the correlation identity for one request, publishes it, and releases it afterwards.
     *
     * <p>The identity is resolved from the inbound header or minted, placed in the SLF4J mapped
     * diagnostic context, written to the response, and removed from the context once the rest of the
     * chain has run. The response is written to <b>before</b> the chain proceeds; the reason is
     * argued at {@link #publishToResponse(ServletResponse, String)}.</p>
     *
     * <p>A request that is not an HTTP request still passes through untouched apart from the logging
     * context, which is argued at {@link #resolveCorrelationId(ServletRequest)}. A request already
     * carrying this filter's mark passes straight down the chain, which is argued at the guard
     * below.</p>
     *
     * @param request the request being served, from which an inbound identity is read when the
     *     request is an HTTP request and which carries the applied-once mark for the life of the
     *     request; supplied non-null by the container
     * @param response the response being built, onto which the identity is written when the response
     *     is an HTTP response; supplied non-null by the container
     * @param chain the remainder of the filter chain, invoked exactly once on every path through
     *     this method so that no request can be silently dropped here; supplied non-null by the
     *     container
     * @throws IOException if writing or reading the request or response fails anywhere further down
     *     the chain; it is propagated rather than handled, because this filter establishes logging
     *     context and has no basis on which to decide what an input or output failure in a business
     *     handler means
     * @throws ServletException if a component further down the chain reports a servlet-level failure;
     *     it is likewise propagated, and the {@code finally} block below guarantees the logging
     *     context is released on this path exactly as it is on the normal one
     */
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        // WHY : Assumptions: a servlet container runs the chain again for an internal dispatch, so a
        //       forwarded, included, asynchronously resumed or error-routed request reaches this
        //       filter more than once. Without this guard the second pass would mint a SECOND
        //       identity for one unit of work, which defeats the purpose of having one; worse, the
        //       inner pass's finally block would remove the context key while the outer pass was
        //       still running, so every line logged after the inner dispatch returned would carry no
        //       identity at all. Trade-offs: the same guarantee is available by extending the
        //       framework's once-per-request filter base class, which was rejected because it would
        //       add a dependency to this module to obtain a check that is one attribute read.
        if (request.getAttribute(FILTER_APPLIED_ATTRIBUTE) != null) {
            chain.doFilter(request, response);
            return;
        }
        request.setAttribute(FILTER_APPLIED_ATTRIBUTE, Boolean.TRUE);

        String correlationId = resolveCorrelationId(request);

        try {
            MDC.put(CORRELATION_ID_MDC_KEY, correlationId);
            publishToResponse(response, correlationId);
            chain.doFilter(request, response);
        } finally {
            // WHY : Trade-offs: this removal is in a finally block because a servlet container serves
            //       requests on POOLED threads, and the mapped diagnostic context is held per thread.
            //       Removing only on the normal path would leave this request's identity attached to
            //       the thread after an exception, and the next unrelated request handed that thread
            //       would log under it -- cross-request contamination, in which two units of work
            //       become indistinguishable in exactly the evidence gathered to tell them apart. The
            //       compromise accepted is one finally block and the removal cost on every request.
            //
            //       Alternatives Considered: clearing the whole context, rejected because it would
            //       discard keys this filter never put there and does not own. Saving the previous
            //       value and restoring it, rejected for a sharper reason: on a pooled thread a value
            //       present on entry may itself be residue from an earlier request, so restoring it
            //       would reinstate precisely the contamination being guarded against. Removing the
            //       one key this filter owns is the only option that cannot make the situation worse.
            MDC.remove(CORRELATION_ID_MDC_KEY);

            // WHY : Assumptions: the container may reuse a request object across an internal
            //       dispatch, so the mark is cleared alongside the context key. Leaving it set would
            //       make the guard above permanently true for that object, and a later genuine
            //       request served through it would then get no identity established at all.
            request.removeAttribute(FILTER_APPLIED_ATTRIBUTE);
        }
    }

    /**
     * Returns the caller's correlation identity when it conforms to the contract, or a minted one.
     *
     * <p>An inbound value that conforms is returned unaltered -- not trimmed, not re-cased, not
     * padded out to the contract width -- mirroring
     * {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl} line 745, which moves the saved
     * inbound identifier into the reply exactly as it was received.</p>
     *
     * <p>Alternatives Considered: normalising a conforming inbound value on the way through --
     * trimming the whitespace around it, folding it to a single case, or widening it to the contract
     * width. All three were evaluated and rejected for one reason: the caller correlates on the exact
     * bytes it sent, so a value this filter has quietly altered is a value the caller cannot find
     * again. A caller that recorded {@code aB12} and is answered {@code AB12} has been handed
     * something it now has to guess about. Trailing spaces are worth naming separately, because a
     * declared-width field pads with them and it is tempting to treat that padding as significant on
     * the way back; this migration treats trailing spaces in such a field as padding rather than as
     * data, so a shorter value is echoed at its own width and is never widened to twenty-four.</p>
     *
     * @param request the request to read the inbound header from; a request that is not an HTTP
     *     request has no header to read and yields a minted identity
     * @return the inbound identity when it conforms to the contract, otherwise a freshly minted one;
     *     never {@code null} and never blank
     */
    private static String resolveCorrelationId(ServletRequest request) {
        // WHY : Assumptions: the filter interface types this parameter as the protocol-independent
        //       request, so an HTTP header is only reachable after narrowing. Alternatives Considered:
        //       declaring the narrowing mandatory and refusing anything else. Rejected, because this
        //       filter's job is to establish logging context, and a request it cannot read a header
        //       from is a request whose log lines need an identity just as much -- refusing it would
        //       trade a missing header for a failed request.
        String inbound = request instanceof HttpServletRequest httpRequest
                ? httpRequest.getHeader(CORRELATION_ID_HEADER)
                : null;

        // WHY : Alternatives Considered: rejecting the request when no usable identity arrived, by
        //       answering with a client-error status. Rejected on the baseline's own evidence: line
        //       396 of that program moves MQCI-NONE into the descriptor it reads with, so the read
        //       accepts a message bearing no correlation identifier at all, and line 746 then
        //       originates a value where none was supplied. Minting here reproduces that, and it is
        //       also the only behaviour that keeps a caller which has not been taught this header
        //       working. Trade-offs: an identity this filter mints cannot be matched by the caller
        //       against anything it recorded before the call, which is accepted because the caller
        //       receives it on the response and it is the caller's choice to send one instead.
        return isContractConforming(inbound) ? inbound : generateCorrelationId();
    }

    /**
     * Reports whether an inbound value may be echoed as the correlation identity unaltered.
     *
     * <p>A value conforms when it is present, occupies at least one and at most
     * {@code CORRELATION_ID_MAX_LENGTH} characters, and contains only codes from
     * {@code LOWEST_ACCEPTED_CHARACTER} through {@code HIGHEST_ACCEPTED_CHARACTER} inclusive.</p>
     *
     * @param candidate the inbound header value to judge, or {@code null} when the header was absent
     *     or the request was not an HTTP request
     * @return {@code true} when the value may be echoed unaltered, {@code false} when an identity
     *     must be minted instead
     */
    private static boolean isContractConforming(String candidate) {
        if (candidate == null || candidate.isEmpty()) {
            return false;
        }

        // WHY : Trade-offs: a value that is too wide is replaced rather than cut down to the contract
        //       width. Cutting it down was considered and rejected on two counts. A shortened value is
        //       no longer the value the caller sent, so the caller cannot match a response to its
        //       request on it -- which removes the only reason to echo anything. And two callers whose
        //       identities agree for the first twenty-four characters would collapse onto one value,
        //       making two units of work indistinguishable. Replacing it keeps the response header
        //       meaningful and leaves the caller's unusable value out of both the log context and the
        //       response.
        if (candidate.length() > CORRELATION_ID_MAX_LENGTH) {
            return false;
        }

        for (int position = 0; position < candidate.length(); position++) {
            char character = candidate.charAt(position);

            // WHY : Assumptions: the accepted range is the printable single-byte range with the space
            //       excluded, and each exclusion answers a different concern. Codes below the lower
            //       bound are control codes; admitting a carriage return or a line feed here would let
            //       an untrusted header value introduce a second header into the response, so this
            //       check is what keeps that value from ever reaching the response writer. Relying on
            //       the container to reject it was considered and rejected, because that makes the
            //       guarantee a property of whichever container a deployment happens to use rather
            //       than of this code. Codes above the upper bound need more than one byte to encode,
            //       which would break the equivalence between a count of characters and a count of
            //       bytes that the twenty-four width contract rests on. The space is excluded because
            //       the declared-width character field this value round-trips through pads with
            //       spaces, so a space inside an identity could not afterwards be told apart from
            //       padding.
            if (character < LOWEST_ACCEPTED_CHARACTER || character > HIGHEST_ACCEPTED_CHARACTER) {
                return false;
            }
        }

        return true;
    }

    /**
     * Mints a correlation identity occupying the contract width exactly.
     *
     * @return a newly minted identity of exactly {@code CORRELATION_ID_MAX_LENGTH} upper-case
     *     hexadecimal characters, each a single byte when encoded as ASCII
     */
    private static String generateCorrelationId() {
        // WHY : Alternatives Considered: the platform's universally unique identifier, whose canonical
        //       form is thirty-six characters and twenty-two more than the contract admits, or
        //       thirty-two with its separators taken out. Either would have to be cut down to
        //       twenty-four, which discards entropy by an undocumented amount and yields a value that
        //       is no longer a universally unique identifier while still looking like one. Rendering a
        //       chosen count of random bytes states the width and the entropy in one place instead.
        //
        //       Alternatives Considered: the general-purpose random generator, or the thread-local
        //       one. Rejected because this value is published on a response and into log output, and a
        //       guessable identity lets an observer holding one name the identities of neighbouring
        //       requests, which turns an aid to reading logs into an aid to enumerating them. The
        //       identity authenticates nothing and carries no credential; unpredictability is chosen
        //       to deny that enumeration, not to protect the value itself.
        byte[] entropy = new byte[GENERATED_ID_RANDOM_BYTES];
        ENTROPY_SOURCE.nextBytes(entropy);
        return IDENTITY_RENDERER.formatHex(entropy);
    }

    /**
     * Writes the correlation identity onto the response for the caller to read.
     *
     * @param response the response to write to; a response that is not an HTTP response has no header
     *     to write and is left untouched
     * @param correlationId the identity to write, already resolved and conforming to the contract
     */
    private static void publishToResponse(ServletResponse response, String correlationId) {
        // WHY : Assumptions: this is called BEFORE the rest of the chain runs, and the ordering is
        //       load-bearing rather than incidental. Once a response has been committed its headers
        //       have gone to the client, and a later write is discarded silently -- no exception, no
        //       log line, simply a header the caller never receives. A handler that streams its body
        //       or writes enough of it commits the response before returning, so writing after the
        //       chain would work in testing and then omit the header on exactly the large or streamed
        //       responses hardest to diagnose.
        if (response instanceof HttpServletResponse httpResponse) {
            // WHY : Alternatives Considered: appending the header rather than setting it. Rejected
            //       because appending emits a second value when one is already present, leaving the
            //       caller two identities for one request and no rule for choosing between them.
            //       Setting it guarantees exactly one value, which is what makes the echo a contract
            //       a caller can rely on.
            httpResponse.setHeader(CORRELATION_ID_HEADER, correlationId);
        }
    }
}
