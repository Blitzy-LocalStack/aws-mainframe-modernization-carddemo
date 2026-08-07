package com.carddemo.reference.mapper;

import com.carddemo.common.codec.InquiryRequestCodec;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Renders the date-conversion reply exactly as the reference program lays it out.
 *
 * <h2>Purpose</h2>
 * <p>The date-conversion flow answers with a single labelled line carrying the system date and the system time.
 * The layout is the {@code STRING} statement at {@code app/app-vsam-mq/cbl/CODATE01.cbl} physical lines 355 to
 * 360, and the values come from the {@code EXEC CICS ASKTIME} and {@code EXEC CICS FORMATTIME} pair at physical
 * lines 343 to 353. That file is REFERENCE-ONLY: it is read as the specification for this class and is never
 * modified.</p>
 *
 * <p>Because the message is put with a string format indicator, the label text, the separators and the two
 * field widths ARE the interface -- a consumer reads the date and the time by offset. Transformation rule T8 of
 * the migration plan therefore requires them to cross character-for-character, which is why the labels below
 * keep their spacing around the colon and why the date keeps its unusual ordering.</p>
 *
 * <h2>The date ordering is month-first, and that is not a mistake</h2>
 * <p>Assumptions: the date is rendered {@code MM-DD-YYYY}, not the ISO {@code YYYY-MM-DD} used everywhere else
 * in this migration. The reference program requests {@code MMDDYYYY(WS-MMDDYYYY)} with {@code DATESEP('-')} at
 * physical lines 349 and 350, which produces exactly that ordering into a {@code PIC X(10)} field. Normalising
 * it to ISO here would be an improvement nobody asked for and would silently change a value a consumer parses
 * positionally -- and worse, the two forms are indistinguishable for the first twelve days of any month, so the
 * change would appear to work in most tests.</p>
 *
 * <p>This class holds no mutable state, so a single instance serves every request concurrently.</p>
 */
@Component
public class DateInquiryReplyMapper {

    /**
     * The label preceding the date, from physical line 355, including its trailing space.
     */
    private static final String LABEL_DATE = "SYSTEM DATE : ";

    /**
     * The label preceding the time, from physical line 356, including its trailing space.
     */
    private static final String LABEL_TIME = "SYSTEM TIME : ";

    /**
     * The date pattern, from {@code MMDDYYYY} with {@code DATESEP('-')} at physical lines 349 and 350.
     */
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("MM-dd-yyyy");

    /**
     * The time pattern, from {@code TIME} with {@code TIMESEP} at physical lines 351 and 352.
     *
     * <p>Assumptions: {@code TIMESEP} with no argument selects the colon, which is the separator the reference
     * field's eight characters accommodate: two digits, a separator, two digits, a separator, two digits.</p>
     */
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    /**
     * The total length of the reply body, asserted rather than assumed.
     *
     * <p>Assumptions: forty-six is the sum of the two fourteen-character labels, the ten-character date and the
     * eight-character time. It is stated so {@link #systemDateAndTime(LocalDateTime)} can assert its own
     * output, which is the one property a consumer reading by offset depends on.</p>
     */
    public static final int REPLY_BODY_LENGTH = 46;

    /**
     * Renders the reply for one date-conversion request.
     *
     * <p>Assumptions: the instant is supplied by the caller rather than read from a clock here. The reference
     * program reads the CICS clock, and this class renders rather than decides -- which is what lets a test
     * assert the exact bytes for a known instant instead of having to tolerate whatever the wall clock said.</p>
     *
     * @param now the instant to render; must not be {@code null}
     * @return the reply body, exactly {@link #REPLY_BODY_LENGTH} characters and not yet framed to the message
     *     length, never {@code null}
     * @throws NullPointerException if {@code now} is {@code null}
     * @throws IllegalStateException if the rendered body is not its declared length, which would mean a label
     *     or a pattern here has fallen out of step with the reference program
     */
    public String systemDateAndTime(LocalDateTime now) {
        Objects.requireNonNull(now, "now must not be null");

        String body = LABEL_DATE + DATE_FORMAT.format(now) + LABEL_TIME + TIME_FORMAT.format(now);
        if (body.length() != REPLY_BODY_LENGTH) {
            // WHY : Assumptions: asserted rather than trusted. Each label and each pattern is transcribed from
            //   a separate reference line, and a single wrong width shifts the following field without
            //   producing an error anywhere -- the consumer simply reads the wrong characters.
            throw new IllegalStateException("the rendered date-conversion reply is " + body.length()
                    + " characters but the reference layout declares " + REPLY_BODY_LENGTH
                    + "; a label or a format pattern has fallen out of step with CODATE01");
        }
        return body;
    }

    /**
     * Frames a reply body to the exact message length.
     *
     * <p>Assumptions: the reference program moves its reply into {@code REPLY-MESSAGE PIC X(1000)} at physical
     * line 359 and then puts a literal length of 1000 at physical line 372, so every reply on the wire is one
     * thousand characters regardless of how much of it carries data.</p>
     *
     * @param body the reply body; must not be {@code null}
     * @return the body padded to the message length, never {@code null}
     * @throws IllegalArgumentException if the body exceeds the message length
     */
    public String frame(String body) {
        return InquiryRequestCodec.frame(Objects.requireNonNull(body, "body must not be null"));
    }
}
