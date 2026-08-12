package com.carddemo.common.web;

import com.carddemo.common.error.ApiError;
import com.carddemo.common.security.CardNumberMasker;
import com.carddemo.common.validation.FieldValidationFlag;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import org.slf4j.MDC;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Threads one correlation identity through an HTTP request: accepted on the way in, published to the
 * logging context for the life of the request, and echoed on the way out.
 *
 * <h2>The identity contract</h2>
 *
 * <p>Three obligations define this filter. The first and the third are inherited from a
 * reference-only program; the second is a decision taken by this migration, for the reason recorded
 * beside it:</p>
 *
 * <ul>
 *   <li>an identity supplied by the caller is echoed back <b>exactly as it arrived</b>, so the caller
 *       can match a response to the request it sent;</li>
 *   <li>when the caller supplies none, one is minted rather than the request being refused;</li>
 *   <li>the identity occupies at most {@code CORRELATION_ID_MAX_LENGTH} characters, and a minted one
 *       occupies that width exactly.</li>
 * </ul>
 *
 * <p>Those three settle a fourth case that they do not name, and the resolution is stated here
 * because it is the one a reader is most likely to assume wrongly: a caller that supplies an identity
 * which cannot be carried -- too wide for the width contract, carrying a character the response
 * header must not receive, or shaped like a primary account number -- has its request <b>refused</b>
 * with a client error. It is not served
 * under a minted identity. The first obligation is what forbids the substitution: an identity the
 * caller did not send is one it cannot correlate on, so quietly replacing a value satisfies the
 * filter's mechanics while defeating its purpose. Minting belongs to the second obligation alone, and
 * that obligation is about a caller who expressed no identity at all.</p>
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
 * <p>That program is reference-only: it is read, cited by path and line, and never modified. It
 * consumes a request message, decides an authorization, and replies; the discipline it applies to the
 * correlation identifier across that round trip is the specification this class implements over HTTP.
 * Four of its lines settle the behaviour above.</p>
 *
 * <p><b>Width.</b> Line 45 declares {@code 05 WS-SAVE-CORRELID PIC X(24).} That is where
 * {@code CORRELATION_ID_MAX_LENGTH} comes from. Assumptions: this width is quotable precisely because
 * it is declared in the program's <b>own</b> working storage. The eight broker copybooks the program
 * includes are supplied by the message broker and resolved when the program is compiled on its
 * original platform; a search of this repository for them returns nothing at all. Any width that
 * lives only inside one of those copybooks is therefore not verifiable from this repository, and no
 * such width is asserted anywhere in this class.</p>
 *
 * <p><b>Echo is inherited; minting is this migration's decision.</b> Line 745 moves the saved
 * inbound identifier straight into the reply descriptor, unaltered, which is the echo obligation and
 * is inherited exactly. Line 746 moves the no-identifier constant {@code MQMI-NONE} into the reply's
 * own MESSAGE identifier -- a different field from the correlation one, originated because it has no
 * inbound value. Assumptions: the reference therefore never meets a request whose CORRELATION value
 * is absent, so it decides nothing about that case and this filter cannot inherit an answer for it.
 * Minting one is chosen here instead of refusing the request, because a request carrying no identity
 * is well-formed in HTTP terms and refusing it would invent a failure the reference does not have;
 * the adjacent {@code MQMI-NONE} line is cited as the house idiom for originating a fresh identifier,
 * not as authority for minting a correlation value.</p>
 *
 * <p><b>The identity is a token, never a selector.</b> Line 395 moves {@code MQMI-NONE} and line 396
 * moves {@code MQCI-NONE} into the descriptor used to read the next request, which makes that read
 * match <b>any</b> waiting message rather than one bearing a particular identifier. Assumptions: the
 * correlation identity is therefore carried and echoed but never selected on. Nothing in this
 * package or downstream of it may route, shard or authorize by this value, because the baseline it
 * mirrors never did.</p>
 *
 * <p><b>Nothing is hard-coded.</b> The queue the program reads is handed to it at line 238 from its
 * trigger data, and the queue it replies to is taken from the inbound message itself at lines 413 to
 * 414. Assumptions: configuration in this migration is injected, never written into code. This class
 * holds that line by expressing the header name, the context key and the width as named constants
 * instead of literals buried in a method body, and by containing no endpoint, hostname, queue name or
 * credential of any kind.</p>
 *
 * <h2>Refactoring Rationale: what this filter replaces</h2>
 *
 * <p>Correlation identity already exists in the reference baseline, so this class formalises an
 * established concept rather than introducing one. Line 40 of
 * {@code app/app-authorization-ims-db2-mq/cpy/CCPAUERY.cpy} declares
 * {@code 05 ERR-EVENT-KEY PIC X(20).} as the last field of a structured log record. What was wrong
 * with the old approach is not the concept but its custody: that field is <b>application-managed</b>,
 * so it is populated only where a programmer remembered to populate it, and only in the programs that
 * adopted the structured record at all. The programs that did not adopt it log without any correlation
 * key whatsoever, and the gap is measurable rather than hypothetical -- {@code app/cbl/COTRN00C.cbl}
 * emits an unstructured line and raises a flag at lines 613 and 614 and repeats the identical pair at
 * lines 647 and 681, and {@code app/cbl/COBIL00C.cbl} repeats it at lines 461 and 490. Five sites, no
 * correlation identity at any of them. Moving custody from the application to this filter closes that
 * gap: a request cannot reach a handler without passing through the chain, so there is no code path
 * left on which the identity can be forgotten.</p>
 *
 * <p>Assumptions: a correlation identity makes a request and its reply <b>matchable</b>; it does not
 * make a reply <b>durable</b>, and the two must not be confused. Reply durability is answered by the
 * transactional outbox owned by the service that owns pending authorization data. The shared kernel
 * carries the identity half of the problem and takes no position on message durability.</p>
 *
 * <h2>Assumptions: three declared widths, and only three</h2>
 *
 * <p>Three widths are verifiable from this repository, they mean different things, and conflating any
 * two of them would corrupt the contract:</p>
 *
 * <ul>
 *   <li><b>20</b> characters -- the structured log event key, {@code CCPAUERY.cpy} line 40;</li>
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
 * <h2>Trade-offs: a length and a position are two things here, where the baseline made them one</h2>
 *
 * <p>{@code COPAUA0C.cbl} line 46 declares {@code 05 WS-RESP-LENGTH PIC S9(4) VALUE 1.} -- a one-based
 * <b>position</b>, seeded at the first character. Line 730 hands it to a concatenation as its pointer,
 * which leaves it standing one place past the last character transferred, and line 756 then moves that
 * same field into the length of the message to be published, so a position is spent as though it were
 * a count. The gap is exactly one byte and is measurable: the reply layout at
 * {@code app/app-authorization-ims-db2-mq/cpy/CCPAURLY.cpy} lines 19 to 24 declares six fields of 16,
 * 15, 6, 2, 4 and 14 characters, which is 57, and one separator after each of the six gives 63
 * characters of payload, so a pointer seeded at 1 comes to rest at 64 and 64 bytes are published for a
 * payload of 63.</p>
 *
 * <p>Trade-offs: this class keeps the two concepts apart, and the rule has a visible shape rather than
 * being a sentiment. {@code CORRELATION_ID_MAX_LENGTH} is a count of characters and is only ever
 * compared against a length; the loop variable in {@link #isContractConforming(String)} is a
 * zero-based position and is only ever used to index. Neither is ever assigned to the other, and no
 * arithmetic in this class converts one into the other. Keeping two concepts where the baseline kept
 * one costs a little more vocabulary, and it buys the guarantee that an identity this class admits is
 * exactly as wide as it claims to be -- which is the whole of what the width contract asserts.</p>
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
 * <p>Refactoring Rationale: registration is supplied once by
 * {@link com.carddemo.common.CardDemoCommonAutoConfiguration} rather than repeated by every
 * consuming service. The auto-configuration is named in Spring Boot's
 * {@code AutoConfiguration.imports} resource and its servlet-only nested configuration contributes
 * a {@code FilterRegistrationBean} at
 * {@link com.carddemo.common.CardDemoCommonAutoConfiguration#CORRELATION_FILTER_ORDER}. That makes
 * the filter outermost enough to establish both identities before security or request logging runs,
 * while a class-level servlet condition omits the registration from the non-web batch task without
 * loading the servlet signature there. Requiring eight service-local registrations was rejected
 * because one omitted import would still let that service start and serve requests while silently
 * dropping the correlation identity from every log line.</p>
 *
 * <p>Assumptions: no executable golden-master oracle exists for this class, and none is claimed. The
 * reference functional-parity suite exercises batch flows, and {@code COPAUA0C.cbl} cannot be compiled
 * by that suite's open-source compiler at all, because the eight broker copybooks it includes are
 * absent from this repository. The behaviour this class owes a verifier is therefore stated here as
 * the contract to assert: the width contract, the verbatim echo, minting when the header is absent,
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
     * The header the edge writes its own request identity into, {@code X-Request-Id}.
     *
     * <p>Refactoring Rationale: this second identity exists because the edge and the service could not
     * otherwise be joined. The API's access log can only record context variables the platform
     * defines, and a caller-supplied header is not one of them -- an access-log format naming
     * {@code $context.request.header.x-correlation-id} does not resolve, so the edge line carried no
     * identity at all while the service line carried the caller's. The platform DOES define its own
     * per-request identity, and the edge is configured to log that value and to copy it into this
     * header on the way to the integration. Reading it here is what puts the same value on both
     * lines.</p>
     *
     * <p>Assumptions: the two identities are kept as SEPARATE log fields and are never merged. They
     * have different origins and different guarantees: the correlation identity is the caller's own,
     * echoed back so the caller can match its record to the response; this one is assigned by the edge
     * and is never minted here, because a value this service invented could not appear in an edge log
     * it did not write. Merging the two FIELDS would make the edge log unjoinable on exactly the
     * requests where a caller supplied its own correlation identity, which is the case the caller most
     * cares about.</p>
     *
     * <p>Assumptions: this header has a second, narrower role -- it is also the value the correlation
     * identity FALLS BACK to when the caller supplied none, decided in
     * {@link #fallbackCorrelationId(String)}. That is not a merge of the two fields: on such a request
     * there is no caller identity for the adopted value to displace, and adopting it is what makes the
     * edge line and the service lines carry one value instead of two unrelated ones. A request that
     * does carry a caller identity keeps both fields distinct, exactly as the paragraph above
     * requires.</p>
     *
     * <p>Trade-offs: the name is a literal shared with the edge configuration in
     * {@code infra/modules/api-gateway-http/main.tf}, which stamps {@code $context.requestId} onto this
     * header for every request it forwards. The two agree by convention because they sit in different
     * trees with no build-time bond between them, and a mismatch would present quietly -- a field that
     * is always empty and a fallback that never fires, with nothing reporting either. Naming it as a
     * constant on both sides is what makes the agreement reviewable.</p>
     */
    public static final String REQUEST_ID_HEADER = "X-Request-Id";

    /**
     * The mapped-diagnostic-context key the edge's request identity is published under,
     * {@code requestId}.
     *
     * <p>Assumptions: named for the platform concept it carries rather than for the header it arrives
     * in, and declared as a constant for the same reason as its correlation counterpart: the shared
     * console pattern in {@code carddemo-common-defaults.yml} names this exact string, so a test can
     * assert the two agree and a rename cannot leave the pattern emitting an empty field.</p>
     */
    public static final String REQUEST_ID_MDC_KEY = "requestId";

    /**
     * The widest edge request identity this filter will publish, sixty-four characters.
     *
     * <p>Assumptions: deliberately WIDER than {@link #CORRELATION_ID_MAX_LENGTH}, and the asymmetry is
     * the point. The twenty-four-character bound on the correlation identity comes from a
     * declared-width character field the value round-trips through in the reference baseline; this
     * identity round-trips through nothing of the sort, because it is assigned by the edge and is only
     * ever read. The platform's own value is a thirty-six-character universally-unique identifier, so
     * judging it against twenty-four would reject every real value and leave this field permanently
     * empty -- a defect that would present as the edge join simply never working.</p>
     *
     * <p>Trade-offs: sixty-four rather than thirty-six exactly. A bound set to today's observed width
     * would have to be revised the first time the platform lengthened its identifier, and the failure
     * mode of being one character short is silent. Sixty-four leaves headroom while still bounding what
     * an untrusted header can put into a log field, which is the only thing the bound is for.</p>
     */
    public static final int REQUEST_ID_MAX_LENGTH = 64;

    /**
     * Reports whether a value may be carried as the correlation identity of a unit of work unaltered.
     *
     * <p><b>Purpose.</b> This is the ONE conformance rule for a correlation identity anywhere in the
     * migration, exposed so that a non-servlet transport applies the identical rule rather than an
     * approximation of it. A value conforms when it is present, occupies at least one and at most
     * {@link #CORRELATION_ID_MAX_LENGTH} characters, and every one of those characters is an ASCII
     * letter, an ASCII digit or one of the separators in the accepted punctuation set.</p>
     *
     * <p>Refactoring Rationale: the rule was private, and being private is what let a second transport
     * diverge from it. The queue-driven authorization consumer read a correlation attribute straight off
     * a message into its logging context and into a persisted row, applying no width and no alphabet
     * check at all -- so a requester could put a line terminator, a quotation mark or sixty-four
     * characters of anything into the same log field this filter refuses one character of. The two paths
     * write the same field and now share the same predicate; publishing it is what makes that shareable
     * without copying the alphabet into a second class.</p>
     *
     * <p>Assumptions: this answers only whether a value is usable. It does not decide what happens when
     * it is not, and the separation is deliberate -- the two transports answer that differently and
     * correctly so. A servlet request carrying a nonconforming value is refused with a client error,
     * because a caller that can be told to correct its header should be; a queue message carrying one
     * cannot be corrected by its sender in time to matter, so the consumer drops the attribute and
     * proceeds rather than destroying an authorization request over a log field.</p>
     *
     * @param candidate the value to judge, or {@code null} when none was supplied
     * @return {@code true} when the value may be carried unaltered, {@code false} when it must not be
     */
    public static boolean isConformingCorrelationId(String candidate) {
        return conformsWithin(candidate, CORRELATION_ID_MAX_LENGTH);
    }

    /**
     * The non-numeric prefix every minted identity opens with, {@code CD}.
     *
     * <p>Refactoring Rationale: a minted identity used to be twenty-four hexadecimal characters and
     * nothing else, which put it inside the very class this filter refuses. Hexadecimal draws from ten
     * digits and six letters, so a rendering of twelve random bytes is all digits with probability
     * {@code (10/16)^24}, roughly one in twenty-one thousand -- rare, and therefore worse than common:
     * such a value is minted, published, and then refused if it is ever presented back to this filter
     * as an inbound identity, and it would be refused on one request in twenty-one thousand rather
     * than reproducibly. A constant prefix removes the class entirely. Alternatives Considered:
     * re-minting until the rendering carries a letter, which also works and was rejected because a
     * retry loop makes the width contract depend on a random outcome and gives an operator nothing to
     * recognise in a log.</p>
     *
     * <p>Assumptions: two characters, both ASCII letters, so a minted identity can never satisfy the
     * bare-numeric shape at any length; and the prefix is counted INSIDE
     * {@link #CORRELATION_ID_MAX_LENGTH} rather than added to it, so the published width is unchanged
     * and the queue attribute the same identity round-trips through still carries it.</p>
     *
     * <p>Trade-offs: the prefix costs one byte of entropy compared with the previous rendering --
     * eighty-eight bits rather than ninety-six -- because two characters of the fixed width are no
     * longer random. Both figures are far beyond any enumeration this identity needs to resist, and
     * what is bought is that a minted identity is recognisable as minted and can never collide with
     * the refused class.</p>
     */
    private static final String GENERATED_ID_PREFIX = "CD";

    /**
     * The count of random bytes rendered into a minted identity, twelve.
     *
     * <p>Assumptions: hexadecimal rendering yields two characters per byte, so the count is the width
     * remaining after {@link #GENERATED_ID_PREFIX} halved -- eleven bytes, twenty-two characters,
     * eighty-eight bits of entropy, and a total of exactly
     * {@code CORRELATION_ID_MAX_LENGTH} characters. It is DERIVED from the width and the prefix rather
     * than written as a literal, so the three quantities cannot disagree after a change to any one of
     * them, and the relationship is asserted by this module's tests rather than left as a coincidence
     * for a maintainer to discover.</p>
     */
    private static final int GENERATED_ID_RANDOM_BYTES =
            (CORRELATION_ID_MAX_LENGTH - GENERATED_ID_PREFIX.length()) / 2;

    /**
     * The punctuation an inbound identity may contain, beyond letters and digits.
     *
     * <p>Assumptions: blocking the two line terminators is necessary and is not sufficient, so the
     * alphabet is an allow-list rather than a deny-list. This value is written into a log line and into
     * a mapped diagnostic context field, and the quoting, bracketing and delimiting characters of those
     * formats can corrupt the field boundaries of the record the value lands in without forging a new
     * record at all -- which is enough to make an operational search return the wrong answer about
     * which request did what. Admitting the whole printable single-byte range would leave every one of
     * those characters available to a caller.</p>
     *
     * <p>Assumptions: the three characters admitted here are the ones real callers use as separators
     * inside an identity -- the hyphen of a universally unique identifier, the underscore and the dot
     * of a hierarchical trace name -- and none of them is a delimiter, a quote or a bracket in any of
     * the formats this value is written into. Letters and digits are admitted by
     * {@link #isTokenSafe(char)} directly rather than listed here.</p>
     *
     * <p>Trade-offs: a caller whose identity carries any other punctuation is answered with a minted
     * identity instead of its own, and cannot correlate its record to this one by that value. That is
     * the same outcome the width rule already produces for an over-long identity, and it is preferred
     * to the alternative of accepting the value and sanitising it: a sanitised value is no longer the
     * value the caller sent, so the caller cannot match on it either, and two callers whose identities
     * differ only in the stripped characters would collapse onto one.</p>
     */
    private static final String ACCEPTED_PUNCTUATION = "-_.";

    /**
     * The fewest digits a bare numeric value must carry to be treated as a primary account number.
     *
     * <p>Assumptions: thirteen is the shortest length the card family issues, so it is the point below
     * which an all-digit value cannot be one of these numbers. It is deliberately lower than the
     * sixteen characters this system's own record declares at
     * {@code CARD-NUM PIC X(16)}, line 5 of {@code app/cpy/CVACT02Y.cpy}, because the value judged
     * here arrives from a caller rather than from this system's storage and is not bound by that
     * width.</p>
     *
     * <p>Trade-offs: the identifiers this system does put in a path are all shorter -- an account is
     * {@code PIC 9(11)} and a customer {@code PIC 9(09)} -- so nothing the platform itself generates
     * falls into the refused class, and the cost lands only on a caller that chose a long bare number
     * as its own correlation identity.</p>
     */
    private static final int ACCOUNT_NUMBER_MIN_DIGITS = 13;

    /**
     * The media type the refusal body is written with.
     *
     * <p>Assumptions: this is the media type every other error in this stack is rendered as, because
     * the refusal body is the same {@link ApiError} shape the shared advice produces. Answering a
     * refusal with a different media type from every other error would make one client branch on the
     * status to know how to parse the body.</p>
     */
    private static final String PROBLEM_MEDIA_TYPE = "application/json";

    /**
     * The writer that renders the refusal body.
     *
     * <p>Alternatives Considered: composing the JSON by hand in this class, which would avoid a
     * mapper here altogether. Rejected because the body has to be the SAME shape a handler-rendered
     * error carries, component for component, and a hand-written renderer is a second statement of
     * that shape which can drift from the record without anything failing. Serialising the record
     * itself cannot drift from it.</p>
     *
     * <p>Assumptions: a default mapper suffices because {@link ApiError} carries no money component
     * and no temporal component -- its timestamp is already a formatted string -- so none of the
     * modules a service registers changes how it serialises. That is asserted by this module's own
     * tests rather than assumed, and it is the reason the filter does not need the application's
     * configured mapper, which is unavailable to it at the point a refusal is written.</p>
     */
    private static final ObjectMapper PROBLEM_WRITER = JsonMapper.builder().build();

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
     * The clock the refusal body reads its failure instant from.
     *
     * <p>Assumptions: the filter holds a clock because it RENDERS a problem shape of its own and that
     * shape carries a timestamp. Taking the clock as a constructor argument rather than reading the
     * system clock inside the method is what lets a test assert the rendered body against a fixed
     * instant -- the same arrangement {@code GlobalExceptionHandler} uses, so a service configures one
     * clock and both error paths read it.</p>
     *
     * <p>Assumptions: the field is immutable and the clock implementations used are thread-safe, so one
     * instance still serves every request thread and a service is still free to hold it as a
     * singleton.</p>
     */
    private final Clock clock;

    /**
     * Creates a filter instance reading the system clock in UTC.
     *
     * <p>Assumptions: this convenience form exists because a filter is frequently registered by hand
     * in a test or a minimal deployment where no clock bean is available, and the instant it stamps a
     * refusal with is not a business value -- it is a diagnostic. The system clock is therefore the
     * right default and is named explicitly rather than left to the platform's default zone, since a
     * zone-dependent timestamp in a shared log is unreadable across deployments.</p>
     */
    public CorrelationIdFilter() {
        this(Clock.systemUTC());
    }

    /**
     * Creates a filter instance reading a supplied clock.
     *
     * <p>Assumptions: a consuming service constructs this filter and places it in its chain; nothing
     * in this module does so beyond its own auto-configuration, which passes the context's shared clock
     * so that a refusal and a handler-rendered error stamp one request identically.</p>
     *
     * <p>Alternatives Considered: reading the clock statically, which would leave the rendered
     * timestamp unassertable. Declaring both constructor forms costs two members and puts the
     * registration contract where it is looked for.</p>
     *
     * @param clock the clock the refusal body reads its failure instant from; must not be {@code null}
     * @throws NullPointerException if {@code clock} is {@code null}
     */
    public CorrelationIdFilter(Clock clock) {
        // WHY : Alternatives Considered: overriding init and destroy was evaluated and rejected. Both
        //       are default methods on the servlet filter interface with empty bodies, and this class
        //       has nothing to build up when a container starts it and nothing to release when the
        //       container discards it -- its shared instances are static, immutable and created when
        //       the class loads. Overriding them would add two members asserting a lifecycle this class
        //       does not have, and the documentation gate would then require both to be described, so
        //       the cost would be paid in prose about behaviour that does not exist.
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Establishes the correlation identity for one request, publishes it, and releases it afterwards.
     *
     * <p>An identity supplied by the caller is used exactly as it arrived; a request whose supplied
     * identity cannot be carried is REFUSED rather than served under a substituted one; and a request
     * that supplied none is served under the edge's request identifier when that conforms, else under
     * a minted one -- the cases are argued at the guard below, at
     * {@link #rejectNonconformingIdentity(ServletRequest, ServletResponse, String)} and at
     * {@link #fallbackCorrelationId(String)}. The resolved identity is placed in the SLF4J
     * mapped diagnostic context, written to the response, and removed from the context once the rest
     * of the chain has run. The response is written to <b>before</b>
     * the chain proceeds; the reason is argued at
     * {@link #publishToResponse(ServletResponse, String)}.</p>
     *
     * <p>A request that is not an HTTP request still passes through untouched apart from the logging
     * context, which is argued at {@link #inboundCorrelationId(ServletRequest)}. A request already
     * carrying this filter's mark passes straight down the chain, which is argued at the guard
     * below.</p>
     *
     * @param request the request being served, from which an inbound identity is read when the
     *     request is an HTTP request and which carries the applied-once mark for the life of the
     *     request; supplied non-null by the container
     * @param response the response being built, onto which the identity is written when the response
     *     is an HTTP response; supplied non-null by the container
     * @param chain the remainder of the filter chain, invoked exactly once on every path that
     *     serves the request, so that no request can be silently dropped here; the one path that does
     *     not invoke it is the deliberate refusal above, which answers the caller itself; supplied
     *     non-null by the container
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

        // WHY : Assumptions: a supplied identity that does not conform is REFUSED here rather than
        //       replaced, because answering it under a minted identity would break the first of this
        //       filter's three obligations silently -- the caller correlates on the exact bytes it sent,
        //       so an identity it never sent is one it cannot find in any log. The three cases are
        //       therefore distinct and each is honest: conforming means echo, present-but-unusable means
        //       refuse, and absent means fall back to the edge's own request identifier before minting.
        //       That is what makes the echo a contract rather than a best effort.
        // WHY : Alternatives Considered: widening the contract instead, so a thirty-six-character
        //       identifier conforms. Rejected because the width is not this class's to choose: it is
        //       the twenty-four characters of WS-SAVE-CORRELID at
        //       app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl line 45, and the same identity
        //       round-trips through the queue attribute that field became, so a value this filter
        //       accepted and the asynchronous path could not carry would move the failure to where
        //       it is far harder to see. The bound is published in
        //       docs/architecture/observability.md for callers to build against.
        // WHY : Trade-offs: refusal costs a caller that sends a wrong-shaped header a failed request
        //       rather than a served one under an identity it did not choose. That cost is accepted and
        //       is the point -- a 400 naming the constraint is actionable at the one moment the caller
        //       can act on it, whereas a substituted identity is discovered during an incident, when the
        //       log it was needed for has already been written.
        String inbound = inboundCorrelationId(request);
        if (inbound != null && !isContractConforming(inbound)) {
            rejectNonconformingIdentity(request, response, inbound, this.clock);
            return;
        }

        // WHY : Assumptions: the edge's own request identifier is resolved BEFORE the correlation
        //       identity is settled, because it serves two purposes and only one of them is its own
        //       logging field. It is also the value the correlation identity falls back to when the
        //       caller supplied none, argued at fallbackCorrelationId, so resolving it once and
        //       passing it on keeps one read of one header answering both.
        String requestId = resolveRequestId(request);

        String correlationId = inbound != null ? inbound : fallbackCorrelationId(requestId);

        try {
            MDC.put(CORRELATION_ID_MDC_KEY, correlationId);

            // WHY : Assumptions: the edge identity is published only when the edge supplied one, and
            //       the key is otherwise left absent rather than set to an empty value. The shared
            //       console pattern renders an absent key as an empty field, so a direct call that
            //       bypassed the edge -- a health probe from the load balancer, a request in a test --
            //       produces a line with a blank position instead of a line asserting an edge identity
            //       of "". The distinction matters when the log is searched for requests that did NOT
            //       come through the edge.
            if (!requestId.isEmpty()) {
                MDC.put(REQUEST_ID_MDC_KEY, requestId);
            }

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

            // WHY : Assumptions: removed unconditionally, even though it is only put conditionally. A
            //       servlet container serves requests on pooled threads, so a key left behind by an
            //       earlier request that DID come through the edge would attach that request's edge
            //       identity to this one's lines; removing a key that was never put is free, whereas
            //       testing before removing would reintroduce exactly the residue being guarded
            //       against.
            MDC.remove(REQUEST_ID_MDC_KEY);

            // WHY : Assumptions: the container may reuse a request object across an internal
            //       dispatch, so the mark is cleared alongside the context key. Leaving it set would
            //       make the guard above permanently true for that object, and a later genuine
            //       request served through it would then get no identity established at all.
            request.removeAttribute(FILTER_APPLIED_ATTRIBUTE);
        }
    }

    /**
     * Returns the identity the caller supplied, exactly as it arrived, or {@code null} if it supplied
     * none.
     *
     * <p>Nothing is judged here and nothing is substituted: the value is returned unaltered -- not
     * trimmed, not re-cased, not padded out to the contract width -- mirroring
     * {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl} line 745, which moves the saved
     * inbound identifier into the reply exactly as it was received. Whether the value may be used is
     * {@link #isContractConforming(String)}'s question, and what to do when it may not is
     * {@link #doFilter(ServletRequest, ServletResponse, FilterChain)}'s.</p>
     *
     * <p>Alternatives Considered: normalising a supplied value on the way through -- trimming the
     * whitespace around it, folding it to a single case, or widening it to the contract width. All
     * three were evaluated and rejected for one reason: the caller correlates on the exact bytes it
     * sent, so a value this filter has quietly altered is a value the caller cannot find again. A
     * caller that recorded {@code aB12} and is answered {@code AB12} has been handed something it now
     * has to guess about. Trailing spaces are worth naming separately, because a declared-width field
     * pads with them and it is tempting to treat that padding as significant on the way back; this
     * migration treats trailing spaces in such a field as padding rather than as data, so a shorter
     * value is echoed at its own width and is never widened to twenty-four.</p>
     *
     * <p>Assumptions: an empty header value is reported as {@code null} -- as "supplied none" rather
     * than as "supplied something unusable" -- and the distinction decides whether a request is
     * refused. A caller that sends the header with nothing after the colon has expressed no identity
     * at all, so there is nothing for a refusal to protect and the request is served under a fallback
     * identity instead -- the edge's own request identifier, else a minted one, as
     * {@link #fallbackCorrelationId(String)} decides. Originating a value where none was
     * supplied is the behaviour the baseline shows at line 746.</p>
     *
     * @param request the request to read the inbound header from; a request that is not an HTTP
     *     request has no header to read and yields {@code null}
     * @return the value of the inbound header exactly as received, or {@code null} when the request
     *     is not an HTTP request, the header is absent, or its value is empty
     */
    private static String inboundCorrelationId(ServletRequest request) {
        // WHY : Assumptions: the filter interface types this parameter as the protocol-independent
        //       request, so an HTTP header is only reachable after narrowing. Alternatives Considered:
        //       declaring the narrowing mandatory and refusing anything else. Rejected, because this
        //       filter's job is to establish logging context, and a request it cannot read a header
        //       from is a request whose log lines need an identity just as much -- refusing it would
        //       trade a missing header for a failed request.
        String inbound = request instanceof HttpServletRequest httpRequest
                ? httpRequest.getHeader(CORRELATION_ID_HEADER)
                : null;

        return inbound == null || inbound.isEmpty() ? null : inbound;
    }

    /**
     * Returns the identity to serve a request under when the caller supplied none: the edge's request
     * identifier when it also conforms to the correlation contract, else a freshly minted identity.
     *
     * <p>Reached only from {@link #doFilter(ServletRequest, ServletResponse, FilterChain)} and only
     * for a request that carried no caller identity at all, because a caller value that was present
     * but unusable is refused rather than replaced -- argued there and on
     * {@link #rejectNonconformingIdentity(ServletRequest, ServletResponse, String)}.</p>
     *
     * <p>Assumptions: the edge stamps its own request identifier onto {@code REQUEST_ID_HEADER} for
     * every request it forwards, so adopting it here is what joins the edge access-log line to these
     * service log lines for a request whose caller sent no identity of its own -- the ordinary case
     * rather than the exception. Without this step such a request produces an edge line bearing the
     * edge's request identifier and service lines bearing a value minted here that the edge never saw,
     * so the two records of one request cannot be connected at all. The same value is also logged
     * under {@code REQUEST_ID_MDC_KEY} in its own right, which is why it is read once in
     * {@link #resolveRequestId(ServletRequest)} and passed in rather than read again here.</p>
     *
     * <p>Assumptions: adoption is gated on {@link #isContractConforming(String)} and not merely on the
     * wider bound {@link #resolveRequestId(ServletRequest)} already applied. The two bounds differ --
     * {@code REQUEST_ID_MAX_LENGTH} governs what may enter a log field, {@code CORRELATION_ID_MAX_LENGTH}
     * governs what may be echoed to a caller and carried onto the asynchronous path -- so a value wide
     * enough to log is not automatically narrow enough to become this service's correlation identity.
     * Re-checking here is what keeps the narrower contract narrow.</p>
     *
     * <p>Trade-offs: an edge identifier that does not conform falls through to minting rather than
     * refusing the request. That asymmetry with the caller-supplied case is deliberate: a caller can
     * act on a refusal naming the constraint, whereas a caller refused for a header the EDGE set has
     * been handed a failure it cannot fix, so a change to the edge's identifier format degrades the
     * join rather than taking the service out of service.</p>
     *
     * <p>Alternatives Considered: preferring the edge identifier over a conforming caller value,
     * because the edge always has one. Rejected in
     * {@link #doFilter(ServletRequest, ServletResponse, FilterChain)}'s ordering, because it would
     * answer a caller with a value the caller never sent, defeating the only purpose of echoing
     * anything.</p>
     *
     * <p>Alternatives Considered: refusing a request that arrived with no usable identity from either
     * source. Rejected on the baseline's own evidence: line 396 of
     * {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl} moves MQCI-NONE into the descriptor it
     * reads with, so the read accepts a message bearing no correlation identifier at all, and line 746
     * then originates a value where none was supplied. Minting reproduces that, and it is also the
     * only behaviour that keeps a caller which has not been taught this header working.</p>
     *
     * @param requestId the edge identifier already resolved for this request, empty when the request
     *     carried none this filter would log
     * @return the edge's request identifier when it conforms to the correlation contract, else a
     *     freshly minted identity; never {@code null} and never blank
     */
    private static String fallbackCorrelationId(String requestId) {
        // WHY : Assumptions: the empty string means "no edge identifier worth logging", which is what
        //       resolveRequestId returns for an absent, over-wide or non-token-safe header and for a
        //       request that is not an HTTP request at all. Minting for that case is the same choice
        //       made in inboundCorrelationId -- a request whose log lines need an identity is not
        //       improved by being refused one.
        if (isContractConforming(requestId)) {
            return requestId;
        }

        return generateCorrelationId();
    }

    /**
     * Refuses one request whose supplied correlation identity cannot be carried.
     *
     * <p>Answers {@link HttpServletResponse#SC_BAD_REQUEST} with the shared {@link ApiError} body and
     * the response correlation header, and does not invoke the remainder of the chain -- so no handler
     * runs under an identity the caller did not choose.</p>
     *
     * <p>Alternatives Considered: delegating the refusal to {@code sendError}. Rejected because it
     * satisfies neither half of the published contract -- that a 400 carries the problem shape and that
     * every response carries the correlation header. {@code sendError} hands the response to the
     * container's error dispatch, which does not pass through the shared advice, so the body would be
     * whatever error page a deployment happened to configure, and the header would be absent because
     * this path returns before {@code publishToResponse} is reached. A client written against the
     * contract could then neither parse nor correlate this one refusal. Writing the record here makes
     * the refusal identical in every deployment and identical in shape to every other error this stack
     * returns.</p>
     *
     * <p>Assumptions: the response carries a FRESHLY MINTED identity rather than the refused one. The
     * caller's value cannot be echoed -- that is what it is being refused for -- but the header cannot
     * simply be omitted either, because the contract makes it unconditional and an operator reading
     * the refusal in a log needs a value to search on. Minting one satisfies both: the response is
     * correlatable to this filter's own log line, and the client can tell it is not the value it sent
     * because it is not the value it sent.</p>
     *
     * <p>Assumptions: the refused value is still NOT reflected anywhere -- not into the header, not
     * into the message, not into the field entry, and not into the logging context. An untrusted value
     * echoed into a header is how a carriage return in it introduces a second header into the response,
     * which is the injection {@link #isContractConforming(String)} refuses the value for in the first
     * place. A caller is equally capable of putting content into a header that must not be copied into
     * a shared log, so the diagnostic reports the value's SHAPE -- its length, against the permitted
     * width -- which identifies the defect without carrying the value.</p>
     *
     * <p>Alternatives Considered: throwing an exception for the framework's error handling to render.
     * Rejected for the reason it always was: a filter runs OUTSIDE the dispatcher, so an exception
     * thrown here never reaches a controller advice. The difference is that the conclusion is now to
     * render the record rather than to accept whatever the container renders.</p>
     *
     * <p>Trade-offs: this is the one path through this filter that does not call the chain, and it is
     * deliberate rather than an oversight of the contract documented on
     * {@link #doFilter(ServletRequest, ServletResponse, FilterChain)}. A request refused for its
     * identity must not reach a handler, because the whole reason to refuse it is that anything the
     * handler logged would be attributable to the wrong unit of work.</p>
     *
     * @param request the request being refused, whose applied-once mark is cleared so a container
     *     that reuses the request object for an error dispatch is not left believing this filter has
     *     already run, and whose path is reported in the problem shape
     * @param response the response to write the refusal onto; a response that is not an HTTP response
     *     cannot carry a status, so the request is refused by not proceeding and nothing is written
     * @param inbound the nonconforming value, read only for its length and never reproduced
     * @param clock the clock the problem shape reads its failure instant from
     * @throws IOException if writing the refusal body fails
     */
    private static void rejectNonconformingIdentity(ServletRequest request, ServletResponse response,
            String inbound, Clock clock) throws IOException {

        // WHY : Assumptions: the mark set by the caller is cleared even though this path no longer
        //       triggers an ERROR dispatch, because a container is free to re-run the chain on the same
        //       request object for reasons of its own and a stale mark would make the guard at the top
        //       of doFilter true for that pass, leaving it with no identity in the logging context at
        //       all. Clearing it costs one call and removes a dependence on container behaviour.
        request.removeAttribute(FILTER_APPLIED_ATTRIBUTE);

        if (!(response instanceof HttpServletResponse httpResponse)) {
            return;
        }

        // WHY : Assumptions: the numeric-shape rule is NAMED in the refusal, because a value of
        //       sixteen digits satisfies every other clause of the sentence and a caller told only the
        //       width and the alphabet would read the refusal as contradicting itself. The rule is
        //       stated as a shape, so the message explains the refusal without reproducing the value
        //       that caused it -- which is the whole point of refusing it.
        String detail = "The " + CORRELATION_ID_HEADER + " header must be 1 to "
                + CORRELATION_ID_MAX_LENGTH
                + " characters, each a letter, a digit or one of "
                + ACCEPTED_PUNCTUATION
                + ", and must not carry " + ACCOUNT_NUMBER_MIN_DIGITS
                + " or more digits once separators are removed; the supplied value is "
                + inbound.length() + " characters. Omit the header to have one generated.";

        String correlationId = generateCorrelationId();
        ApiError problem = ApiError.ofFieldErrors(detail, HttpServletResponse.SC_BAD_REQUEST,
                correlationId, pathOf(httpRequestOf(request)),
                List.of(new ApiError.FieldError(CORRELATION_ID_HEADER,
                        FieldValidationFlag.NOT_OK, detail)),
                clock);

        byte[] body = PROBLEM_WRITER.writeValueAsString(problem).getBytes(StandardCharsets.UTF_8);

        // WHY : Assumptions: the header, the status, the media type and the content length are all set
        //       BEFORE the body is written, because a response commits as soon as enough of its body has
        //       been written and a header set after that point is discarded silently. Setting the length
        //       explicitly also keeps the refusal from being chunked, which matters only in that it
        //       makes the response byte-identical across containers.
        httpResponse.setHeader(CORRELATION_ID_HEADER, correlationId);
        httpResponse.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        httpResponse.setContentType(PROBLEM_MEDIA_TYPE);
        httpResponse.setCharacterEncoding(StandardCharsets.UTF_8.name());
        httpResponse.setContentLength(body.length);
        httpResponse.getOutputStream().write(body);
        httpResponse.flushBuffer();
    }

    /**
     * Narrows a servlet request to its HTTP form, or {@code null} when it has none.
     *
     * <p>Assumptions: a non-HTTP request has no path to report, and reporting {@code null} for the path
     * is what {@link ApiError} already models for an absent one, so the two absences are the same
     * absence and no substitute value has to be invented.</p>
     *
     * @param request the request to narrow
     * @return the request as an HTTP request, or {@code null} when it is not one
     */
    private static HttpServletRequest httpRequestOf(ServletRequest request) {
        return request instanceof HttpServletRequest httpRequest ? httpRequest : null;
    }

    /**
     * Reads the path a refusal is attributed to, with any embedded card number masked.
     *
     * <p>Assumptions: the path is masked before it is placed in the response body, because a caller
     * assembles its own request target and a refused request is exactly the case where that target was
     * not one this service publishes a route for. The masking is delegated to
     * {@code com.carddemo.common.security.CardNumberMasker} rather than restated, so the filter and the
     * shared advice mask by one rule.</p>
     *
     * @param request the request to read, or {@code null} when the response has no HTTP request behind
     *     it
     * @return the masked request path, or {@code null} when there is no request to read one from
     */
    private static String pathOf(HttpServletRequest request) {
        return request == null ? null
                : CardNumberMasker.maskEmbeddedCardNumbers(request.getRequestURI());
    }

    /**
     * Returns the edge's request identity when it conforms to the same contract, or the empty string.
     *
     * <p>Assumptions: judged by the SAME conformance rule as the correlation identity, because it is
     * written into the same log field and therefore carries the same corruption risk. It differs in
     * exactly one respect: a non-conforming or absent value yields the empty string rather than a
     * minted identity, since a value minted here could not appear in the edge log this field exists to
     * join to, and publishing one would assert an edge identity that does not exist.</p>
     *
     * @param request the request to read the edge header from; a request that is not an HTTP request
     *     has no header to read and yields the empty string
     * @return the edge's request identity when present and conforming, otherwise the empty string
     */
    private static String resolveRequestId(ServletRequest request) {
        String inbound = request instanceof HttpServletRequest httpRequest
                ? httpRequest.getHeader(REQUEST_ID_HEADER)
                : null;

        return conformsWithin(inbound, REQUEST_ID_MAX_LENGTH) ? inbound : "";
    }

    /**
     * Reports whether an inbound value may be echoed as the correlation identity unaltered.
     *
     * <p>A value conforms when it is present, occupies at least one and at most
     * {@code CORRELATION_ID_MAX_LENGTH} characters, every one of those characters is token-safe by
     * {@link #isTokenSafe(char)} -- an ASCII letter, an ASCII digit, or one of the separators in
     * {@link #ACCEPTED_PUNCTUATION} -- and the value as a whole is not a bare run of digits long
     * enough to be a primary account number, by {@link #isAccountNumberShaped(String)}. The rule
     * itself lives in
     * {@link #conformsWithin(String, int)} so that the edge identity, whose width contract differs, is
     * judged by the same alphabet.</p>
     *
     * <p>Assumptions: this answers only whether a value is usable. It does not decide what happens
     * when it is not, and the separation is what stops the two questions being confused again: a
     * value that fails here is refused with a client error, never replaced with a minted one, for the
     * reason argued on {@link #doFilter(ServletRequest, ServletResponse, FilterChain)}.</p>
     *
     * @param candidate the inbound header value to judge, or {@code null} when the header was absent
     *     or the request was not an HTTP request
     * @return {@code true} when the value may be echoed unaltered, {@code false} when it cannot be
     *     carried and the request must be refused
     */
    private static boolean isContractConforming(String candidate) {
        return conformsWithin(candidate, CORRELATION_ID_MAX_LENGTH);
    }

    /**
     * Reports whether a value is present, within a stated width, made only of token-safe characters,
     * and not shaped like a primary account number.
     *
     * <p>Alternatives Considered: two separate checks, one per identity. Rejected because the alphabet
     * rule is the same for both -- both are written into the same log field, so both carry the same
     * corruption risk -- and two copies of that rule would be two places for one of them to be relaxed
     * without the other. Parameterising the width states exactly what differs between the two callers
     * and shares everything that does not.</p>
     *
     * @param candidate the value to judge, or {@code null} when the header was absent or the request
     *     was not an HTTP request
     * @param maxLength the widest value the calling contract admits; a longer value is refused rather
     *     than shortened, for the reason argued on {@link #isContractConforming(String)}
     * @return {@code true} when the value carries at least one character, no more than
     *     {@code maxLength} of them, nothing that is not token-safe, and is not a bare run of
     *     {@link #ACCOUNT_NUMBER_MIN_DIGITS} or more digits
     */
    private static boolean conformsWithin(String candidate, int maxLength) {
        if (candidate == null || candidate.isEmpty()) {
            return false;
        }

        // WHY : Alternatives Considered: cutting a too-wide value down to the bound rather than
        //       declining it, which was rejected on two counts. A shortened value is no longer the
        //       value that was sent, so a caller cannot match a response to its request on it -- which
        //       removes the only reason to echo anything. And two identities that agree for the first
        //       twenty-four characters would collapse onto one value, making two units of work
        //       indistinguishable. Declining leaves the unusable value out of both the log context and
        //       the response, and each caller of this check then does what suits it: doFilter refuses a
        //       request whose CALLER-supplied identity fails, while resolveRequestId simply drops an
        //       edge identifier that fails rather than failing a request over a header the caller did
        //       not set.
        if (candidate.length() > maxLength) {
            return false;
        }

        for (int position = 0; position < candidate.length(); position++) {
            // WHY : Assumptions: the accepted alphabet is letters, digits and three separators, and
            //       every exclusion answers a distinct concern. A carriage return or a line feed would
            //       let an untrusted header value introduce a SECOND header into the response, so this
            //       check is what keeps such a value from reaching the response writer at all; relying
            //       on the container to reject it was considered and rejected, because that would make
            //       the guarantee a property of whichever container a deployment happens to use rather
            //       than of this code. A quote, a bracket, an equals sign or a delimiter would corrupt
            //       the field boundaries of the log record this value is written into. A code point
            //       above the single-byte range would break the equivalence between a count of
            //       characters and a count of bytes that the twenty-four width contract rests on. And
            //       the space is excluded because the declared-width character field this value
            //       round-trips through pads with spaces, so a space inside an identity could not
            //       afterwards be told apart from padding.
            if (!isTokenSafe(candidate.charAt(position))) {
                return false;
            }
        }

        // WHY : Assumptions: the alphabet check cannot see this exposure, because the characters of an
        //       account number are individually unobjectionable. A conforming value is published to the
        //       mapped diagnostic context and therefore onto every log line the request produces, so a
        //       caller could place cardholder data into log storage through a header. This test is what
        //       keeps that class of value out.
        //       Alternatives Considered: hashing an inbound identity instead of refusing it, so that any
        //       value at all could be accepted. Rejected because it defeats the reason the identity is
        //       echoed: a caller matches a response to its request on the value it sent, and a hashed
        //       identity is no longer that value. Refusing the narrow account-number-shaped class keeps
        //       the echo exact for every other value.
        return !isAccountNumberShaped(candidate);
    }

    /**
     * Reports whether a value is a bare run of digits long enough to be a primary account number.
     *
     * <p>Assumptions: the accepted range is {@link #ACCOUNT_NUMBER_MIN_DIGITS} through
     * {@link #CORRELATION_ID_MAX_LENGTH} digits, and both ends are derived rather than chosen. The
     * lower bound is the shortest number the card family issues, so nothing shorter can be one; the
     * upper bound is simply the widest value this contract admits at all, so no separate ceiling is
     * needed. The declared field in this system is sixteen characters --
     * {@code CARD-NUM PIC X(16)} at line 5 of {@code app/cpy/CVACT02Y.cpy} -- and the range is written
     * wider than that single width deliberately, because a caller choosing to smuggle a number is not
     * bound by the width this system stores.</p>
     *
     * <p>Assumptions: the test is applied to the value with its SEPARATORS REMOVED, because the
     * alphabet has to admit a hyphen, a dot and an underscore for legitimate callers and a rule stated
     * over the raw text would therefore be a rule about punctuation rather than about the value.
     * Normalising first is what makes it about the value, and it is the same treatment
     * {@code com.carddemo.common.security.CardNumberMasker} applies for the rendering half of the same
     * problem.</p>
     *
     * <p>Trade-offs: the refused class widens, and the cost is real and worth naming. A legitimate
     * identity of thirteen or more digits is refused whether it is written bare or with separators, so
     * a caller whose scheme is a separated timestamp such as {@code 2024-01-15-093000} -- fifteen
     * digits once normalised -- is now refused where before it was echoed. That is accepted for two
     * reasons: the same caller writing the same value WITHOUT separators was already refused, so
     * admitting the separated form was an inconsistency rather than a feature; and a separated
     * fifteen-digit value is indistinguishable from a separated card number by inspection, so no rule
     * can admit one and exclude the other. Every value carrying a letter at any position is unaffected,
     * which includes every identity this platform mints -- {@link #GENERATED_ID_PREFIX} guarantees
     * it -- and the refusal names the rule so a caller can act on it.</p>
     *
     * @param candidate the value to classify, already known to be non-empty and token-safe
     * @return {@code true} when the value carries only digits and accepted separators, and its digits
     *     alone number {@link #ACCOUNT_NUMBER_MIN_DIGITS} or more; {@code false} otherwise
     */
    private static boolean isAccountNumberShaped(String candidate) {
        int digits = 0;

        for (int position = 0; position < candidate.length(); position++) {
            char character = candidate.charAt(position);
            if (character >= '0' && character <= '9') {
                digits++;
                continue;
            }
            // WHY : Assumptions: a separator does not disqualify the value and does not count towards
            //       the digit total, while ANY other character disqualifies it outright. That
            //       asymmetry is what keeps the rule narrow: a value carrying a letter is not a
            //       written card number in any convention, so it is admitted immediately rather than
            //       having its digits counted.
            if (ACCEPTED_PUNCTUATION.indexOf(character) < 0) {
                return false;
            }
        }

        return digits >= ACCOUNT_NUMBER_MIN_DIGITS;
    }

    /**
     * Reports whether one character may appear inside an echoed correlation identity.
     *
     * <p>Assumptions: the letter and digit tests are explicit ASCII range comparisons rather than the
     * platform's character-class predicates, which accept every Unicode letter and every Unicode
     * decimal digit. Admitting those would defeat the single-byte assumption the width contract rests
     * on, and would additionally admit code points that render identically to an ASCII character while
     * comparing unequal to it -- so two identities could look the same in a log and match nothing.</p>
     *
     * @param candidate the character to classify
     * @return {@code true} when the character is an ASCII letter, an ASCII digit, or one of the
     *     separators listed in {@link #ACCEPTED_PUNCTUATION}; {@code false} for everything else,
     *     including every control code, every quote, bracket and delimiter, the space, and every code
     *     point outside the single-byte range
     */
    private static boolean isTokenSafe(char candidate) {
        return (candidate >= 'a' && candidate <= 'z')
                || (candidate >= 'A' && candidate <= 'Z')
                || (candidate >= '0' && candidate <= '9')
                || ACCEPTED_PUNCTUATION.indexOf(candidate) >= 0;
    }

    /**
     * Mints a correlation identity occupying the contract width exactly.
     *
     * @return a newly minted identity of exactly {@code CORRELATION_ID_MAX_LENGTH} characters: the
     *     two-character {@link #GENERATED_ID_PREFIX} followed by upper-case hexadecimal, each a single
     *     byte when encoded as ASCII, and never a bare run of digits
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
        //
        //       Assumptions: the prefix is prepended rather than the rendering being post-processed,
        //       so a minted identity is outside the refused bare-numeric class by CONSTRUCTION and not
        //       by a test that could pass one value in twenty-one thousand. The reasoning is on
        //       GENERATED_ID_PREFIX.
        byte[] entropy = new byte[GENERATED_ID_RANDOM_BYTES];
        ENTROPY_SOURCE.nextBytes(entropy);
        return GENERATED_ID_PREFIX + IDENTITY_RENDERER.formatHex(entropy);
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
