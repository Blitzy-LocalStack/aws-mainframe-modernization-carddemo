package com.carddemo.common.codec;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;

/**
 * The positional reply body the date-and-time inquiry flow answers with.
 *
 * <h2>Purpose</h2>
 * <p>This is the target form of the {@code STRING} statement at
 * {@code app/app-vsam-mq/cbl/CODATE01.cbl} physical lines 355 to 360, whose values come from the
 * {@code EXEC CICS ASKTIME} and {@code EXEC CICS FORMATTIME} pair at physical lines 343 to 353. That
 * file is REFERENCE-ONLY: it is read here as the specification and is never modified. The reference
 * program builds a labelled date and a labelled time into one buffer, moves the result into
 * {@code REPLY-MESSAGE PIC X(1000)} at physical line 359 and puts a literal length of 1000 at
 * physical line 372, so a reply on the wire is the body below padded to the shared message
 * length.</p>
 *
 * <h2>Why this layout lives in the shared kernel</h2>
 * <p>Assumptions: this is wire GEOMETRY and not a bounded context's business rule, so it belongs
 * beside {@link InquiryRequestCodec}, which already single-sources the request half and the framing
 * of the same one-thousand-character wire for both inquiry programs. Transformation rule T2 of the
 * migration plan places a shared wire contract in the shared kernel, and the reason is the same one
 * recorded on that class: two transcriptions of one layout are two chances to disagree about an
 * offset, and an offset disagreement presents as a field silently one character short rather than as
 * an error.</p>
 *
 * <p>Refactoring Rationale: this renderer was a {@code @Component} in the reference context, where the
 * queue consumer that used it lived. Both inquiry flows now arrive on ONE shared request queue -- the
 * single {@code CARDDEMO.REQUEST.QUEUE} the baseline defines at
 * {@code app/app-vsam-mq/README.md:53} -- and one queue admits exactly one owning consumer, because
 * SQS delivers no copy to each consumer and a receive hides the message from the others. The owning
 * consumer therefore renders both answers, and the account answer is data-bound while this one is a
 * function of the clock alone. Moving the renderer here rather than into the consuming context keeps
 * the date answer out of the account domain and keeps the whole wire -- request fields, framing,
 * diagnostics and now this reply body -- described in one place.</p>
 *
 * <p>Alternatives Considered: leaving the renderer in the reference context and having the owning
 * consumer call that context over HTTP for each date reply. Rejected on cost and blast radius: it
 * would add a pairwise machine-identity signing key with its own rotation obligation, IAM grants and
 * a cross-context network hop on the message path, all to obtain a value that is the clock formatted
 * two ways. Nothing about the answer depends on reference data.</p>
 *
 * <p>This class holds no state and cannot be instantiated.</p>
 */
public final class DateInquiryReplyCodec {

    /**
     * The label preceding the date, from {@code CODATE01.cbl} physical line 355, including its
     * trailing space.
     */
    public static final String LABEL_DATE = "SYSTEM DATE : ";

    /**
     * The label preceding the time, from {@code CODATE01.cbl} physical line 356, including its
     * trailing space.
     */
    public static final String LABEL_TIME = "SYSTEM TIME : ";

    /**
     * The total length of the reply body, asserted rather than assumed.
     *
     * <p>Assumptions: forty-six is the sum of the two fourteen-character labels, the ten-character
     * date and the eight-character time. It is stated so {@link #systemDateAndTime(LocalDateTime)}
     * can assert its own output, which is the one property a consumer reading by offset depends
     * on.</p>
     */
    public static final int REPLY_BODY_LENGTH = 46;

    /**
     * The date pattern, from {@code MMDDYYYY} with {@code DATESEP('-')} at {@code CODATE01.cbl}
     * physical lines 349 and 350.
     *
     * <p>Assumptions: the ordering is {@code MM-DD-YYYY} and NOT the ISO {@code YYYY-MM-DD} this
     * migration uses everywhere else. The reference program requests {@code MMDDYYYY(WS-MMDDYYYY)}
     * with a hyphen separator, which produces exactly that ordering into a {@code PIC X(10)} field.
     * Normalising it to ISO would change ten bytes of a positional reply a requester reads by
     * offset, so the divergence from the surrounding convention is preserved deliberately.</p>
     *
     * <p>Assumptions: the formatter is built with {@link Locale#ROOT}. The default-locale overload
     * substitutes a non-Latin numbering system's digits under a differently configured JVM, which
     * would put characters a fixed-width consumer cannot parse into a money-adjacent wire; the
     * layering gate refuses the default-locale form for that reason.</p>
     */
    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("MM-dd-yyyy", Locale.ROOT);

    /**
     * The time pattern, from {@code TIME} with {@code TIMESEP} at {@code CODATE01.cbl} physical lines
     * 351 and 352.
     *
     * <p>Assumptions: {@code TIMESEP} with no argument selects the colon, which is the separator the
     * reference field's eight characters accommodate: two digits, a separator, two digits, a
     * separator, two digits.</p>
     */
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);

    /**
     * Prevents instantiation.
     *
     * @throws AssertionError always, because this class is a utility holder
     */
    private DateInquiryReplyCodec() {
        throw new AssertionError("DateInquiryReplyCodec is a utility holder and must not be instantiated");
    }

    /**
     * Renders the reply body for one date-and-time inquiry.
     *
     * <p>Assumptions: the instant is supplied by the caller rather than read from a clock here. The
     * reference program reads the CICS clock, and this method renders rather than decides -- which is
     * what lets a test assert the exact bytes for a known instant instead of having to tolerate
     * whatever the wall clock said.</p>
     *
     * @param now the instant to render; must not be {@code null}
     * @return the reply body, exactly {@link #REPLY_BODY_LENGTH} characters and not yet framed to the
     *     message length, never {@code null}
     * @throws NullPointerException if {@code now} is {@code null}
     * @throws IllegalStateException if the rendered body is not its declared length, which would mean
     *     a label or a pattern here has fallen out of step with the reference program
     */
    public static String systemDateAndTime(LocalDateTime now) {
        Objects.requireNonNull(now, "now must not be null");

        String body = LABEL_DATE + DATE_FORMAT.format(now) + LABEL_TIME + TIME_FORMAT.format(now);
        if (body.length() != REPLY_BODY_LENGTH) {
            // WHY : Assumptions: asserted rather than trusted. Each label and each pattern is
            //   transcribed from a separate reference line, and a single wrong width shifts the
            //   following field without producing an error anywhere -- the consumer simply reads the
            //   wrong characters.
            throw new IllegalStateException("the rendered date-conversion reply is " + body.length()
                    + " characters but the reference layout declares " + REPLY_BODY_LENGTH
                    + "; a label or a format pattern has fallen out of step with CODATE01");
        }
        return body;
    }

    /**
     * Renders the reply body and frames it to the exact message length.
     *
     * <p>Assumptions: the framing is delegated to {@link InquiryRequestCodec#frame(String)} rather
     * than repeated, because the padding rule belongs to the wire both inquiry flows share and the
     * account reply is framed by that same method. Two paddings would be two places for one length to
     * drift.</p>
     *
     * @param now the instant to render; must not be {@code null}
     * @return the framed reply, exactly {@link InquiryRequestCodec#MESSAGE_LENGTH} characters, never
     *     {@code null}
     * @throws NullPointerException if {@code now} is {@code null}
     * @throws IllegalStateException if the rendered body is not its declared length
     */
    public static String framedSystemDateAndTime(LocalDateTime now) {
        return InquiryRequestCodec.frame(systemDateAndTime(now));
    }
}
