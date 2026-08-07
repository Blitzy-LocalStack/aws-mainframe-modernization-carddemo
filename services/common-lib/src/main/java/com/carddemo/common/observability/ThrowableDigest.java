package com.carddemo.common.observability;

/**
 * Renders a failure for a log line as the chain of types that produced it, carrying no message text.
 *
 * <h2>The problem this exists for</h2>
 *
 * <p>Handing a throwable to a logging facade is the ordinary way to record a failure, and it is the one
 * thing the generic five-hundred handler must not do. The facade treats a trailing throwable argument as
 * the exception to render, and the default rendering prints that exception's message, then every cause's
 * message, then the frames. Those messages are not written by this repository: they come from a JDBC
 * driver reporting the statement it could not run, from a JSON parser quoting the token it could not
 * read, from a validation library naming the value it rejected. Any of those can carry a primary account
 * number, a national identifier or a whole request record, and the generic handler is by definition the
 * one that fires for failures nobody anticipated -- so the content is unbounded by construction.</p>
 *
 * <p>Refactoring Rationale: the site this class exists for used to pass the throwable and justified it by
 * saying that doing so "leaves redaction and layout to the appender configuration, which one place owns".
 * There is no such place. This repository ships no {@code logback.xml}, no {@code logback-spring.xml} and
 * no {@code log4j2.xml} anywhere, and that is deliberate rather than an omission -- three of the
 * environment profiles record that adding one "would take over the appender chain wholesale", so the
 * chain is Spring Boot's default and no repository-owned filter sits in it. The claim named an owner that
 * did not exist, which is the most expensive kind of documentation defect: it reads as a control and
 * removes the pressure to build one.</p>
 *
 * <h2>What is kept and what is dropped</h2>
 *
 * <p>Assumptions: the type names and the stack frames are kept, and only the messages are dropped. That
 * split is the whole design, and it is chosen because the two halves have different provenance. A type
 * name and a frame are facts about CODE -- a class, a method, a source line -- and no request value can
 * reach either. A message is a sentence some library composed at the moment of failure, and it is the
 * only part of a throwable into which a value can be interpolated. Dropping the messages therefore
 * removes the entire disclosure channel while leaving an operator the two questions they actually ask
 * first: what failed, and where.</p>
 *
 * <p>Trade-offs: what is given up is real and worth naming. A driver's own explanation of a constraint
 * violation, a parser's position within a document and a validation library's account of which rule
 * failed are all messages, so none of them survives. An operator who needs one of those must reproduce
 * the failure with debug logging raised for the specific package, which is a deliberate, scoped and
 * auditable act rather than the default posture. Alternatives Considered: registering a Logback
 * {@code ThrowableHandlingConverter} and a {@code logback-spring.xml} to install it, which would let the
 * facade keep receiving the throwable while the appender stripped the messages. Rejected on two grounds:
 * it introduces the very configuration file the profiles deliberately avoid, taking ownership of the
 * whole appender chain in order to change one converter; and the redaction would then exist only where
 * that file is on the classpath, so a service packaged without it would log in full while every document
 * in the repository said otherwise. A reduced representation composed at the call site cannot be
 * configured away.</p>
 *
 * <p>Assumptions: this class does NOT sanitise and does NOT mask. A type name cannot carry a control
 * character in practice, but a dynamically generated proxy or lambda name is still a string this class
 * did not author, so callers writing the result into a structured line pass it through
 * {@link LogSafeText#sanitize(String)} exactly as they would any other value of external provenance. The
 * two concerns are kept separate for the same reason that class states: one decides whether a value can
 * forge a record, and this one decides which parts of a failure may be read at all.</p>
 */
public final class ThrowableDigest {

    /**
     * The furthest a cause chain is walked.
     *
     * <p>Assumptions: eight is generous for a real failure -- a driver wrapped by a provider wrapped by a
     * template wrapped by a proxy is four -- and it is bounded so that a pathological chain cannot make
     * one log line unbounded. The truncation is reported rather than silent, so a reader can tell a
     * chain that ended from a chain that was cut.</p>
     */
    public static final int MAX_CAUSE_DEPTH = 8;

    /**
     * The number of stack frames rendered for the outermost throwable.
     *
     * <p>Assumptions: three frames is where the diagnostic value of a trace is concentrated. The
     * outermost frame names the statement that failed and its two callers place it, which is what
     * identifies the code path; the remaining frames are almost entirely framework dispatch on a request
     * path and cost line length without adding a decision. Every cause below the first contributes one
     * frame, because for a cause the question is only where it was raised.</p>
     */
    public static final int MAX_HEAD_FRAMES = 3;

    /**
     * The number of stack frames rendered for each cause below the outermost throwable.
     */
    public static final int MAX_CAUSE_FRAMES = 1;

    /**
     * The separator between one link of the cause chain and the next.
     *
     * <p>Assumptions: an arrow rather than a comma, and it carries no space, so the whole digest is one
     * token with no internal whitespace. A structured log line is parsed on whitespace by several
     * collectors, so a digest containing a space would become two fields, one of which has no key.</p>
     */
    public static final String CAUSE_SEPARATOR = "<-";

    /**
     * The separator between frames of one link.
     */
    public static final String FRAME_SEPARATOR = ";";

    /**
     * The marker appended when the chain was longer than {@link #MAX_CAUSE_DEPTH}.
     */
    public static final String TRUNCATION_MARKER = "<-...";

    /**
     * The text rendered for a {@code null} failure.
     *
     * <p>Assumptions: a placeholder rather than an empty string, because an empty value in a structured
     * line is indistinguishable from a field the emitter forgot to populate, and the two want different
     * responses from whoever reads the line.</p>
     */
    public static final String NONE = "(none)";

    /**
     * The text rendered in place of a frame for a throwable that carries no stack trace.
     *
     * <p>Assumptions: a throwable constructed with writable-stack-trace suppression, or one deserialised
     * from a remote call, can have an empty trace. Saying so is more useful than rendering the type alone,
     * which would look like a formatting failure.</p>
     */
    public static final String NO_FRAME = "(no-frame)";

    /**
     * Prevents instantiation of this utility holder.
     *
     * <p>Assumptions: the single operation is a stateless function of its argument, so an instance would
     * carry nothing. A private constructor states that, where an implicit public one would invite a caller
     * to inject this class as a collaborator and then to mock it -- and a mocked digest is one that can be
     * made to return the message text this class exists to withhold.</p>
     *
     * @throws AssertionError always, so that reflective instantiation fails as loudly as direct
     *     instantiation is prevented
     */
    private ThrowableDigest() {
        throw new AssertionError("ThrowableDigest is a utility holder and is never instantiated");
    }

    /**
     * Renders a failure as its cause chain of type names and originating frames, with no message text.
     *
     * <p>The result has the shape
     * {@code com.example.OuterFailure@com.carddemo.a.B.c(B.java:12);com.carddemo.a.B.d(B.java:20)<-org.example.Cause@org.example.X.y(X.java:99)},
     * one token with no internal whitespace, ending in {@link #TRUNCATION_MARKER} when the chain was
     * deeper than {@link #MAX_CAUSE_DEPTH}.</p>
     *
     * <p>Assumptions: a self-referential or cyclic cause chain terminates rather than spinning. The walk
     * stops when a throwable reports itself as its own cause and is additionally bounded by the depth
     * cap, so a cycle of any length ends at the cap. Both guards are present because they fail
     * differently: the identity test catches the common single-element cycle with the chain intact, and
     * the cap catches every longer one.</p>
     *
     * @param failure the throwable to describe, which may be {@code null}
     * @return a single whitespace-free token naming each type in the chain and the frames it was raised
     *     at, or {@link #NONE} when {@code failure} is {@code null}; never {@code null}
     */
    public static String of(Throwable failure) {
        if (failure == null) {
            return NONE;
        }

        StringBuilder digest = new StringBuilder();
        Throwable walk = failure;
        int depth = 0;
        while (walk != null && depth < MAX_CAUSE_DEPTH) {
            if (depth > 0) {
                digest.append(CAUSE_SEPARATOR);
            }
            appendLink(digest, walk, depth == 0 ? MAX_HEAD_FRAMES : MAX_CAUSE_FRAMES);

            // WHY : Assumptions: the identity test is written against the RETRIEVED cause rather than
            //       against a set of seen throwables. A set would catch longer cycles too, but it would
            //       also allocate on every failure for a condition the depth cap already bounds, and this
            //       runs on a request path. The self-cause form is the one the platform's own
            //       initialisation guard permits to be constructed, so it is the one worth naming.
            Throwable cause = walk.getCause();
            walk = cause == walk ? null : cause;
            depth++;
        }

        if (walk != null) {
            digest.append(TRUNCATION_MARKER);
        }
        return digest.toString();
    }

    /**
     * Appends one link of the chain: its type name and up to the given number of frames.
     *
     * @param digest the buffer being composed
     * @param link the throwable whose type and frames are rendered
     * @param frameBudget the maximum number of frames to render for this link
     */
    private static void appendLink(StringBuilder digest, Throwable link, int frameBudget) {
        digest.append(link.getClass().getName());

        StackTraceElement[] frames = link.getStackTrace();
        if (frames.length == 0) {
            digest.append('@').append(NO_FRAME);
            return;
        }

        int rendered = Math.min(frameBudget, frames.length);
        for (int index = 0; index < rendered; index++) {
            digest.append(index == 0 ? '@' : FRAME_SEPARATOR);
            // WHY : Assumptions: the frame is composed from its four accessors rather than from the
            //       element's own rendering. The platform's rendering prefixes a module and class-loader
            //       name for a frame outside the unnamed module, which is variable across packagings and
            //       lengthens the token without answering any question an operator asked. Composing the
            //       four parts keeps one shape whatever the frame came from.
            digest.append(frames[index].getClassName())
                    .append('.')
                    .append(frames[index].getMethodName())
                    .append('(')
                    .append(frames[index].getFileName() == null
                            ? NO_FRAME : frames[index].getFileName())
                    .append(':')
                    .append(frames[index].getLineNumber())
                    .append(')');
        }
    }
}
