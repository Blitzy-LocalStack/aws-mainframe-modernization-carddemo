package com.carddemo.account.mapper;

import com.carddemo.account.domain.Account;
import com.carddemo.common.codec.InquiryRequestCodec;
import com.carddemo.common.codec.ZonedDecimalCodec;
import com.carddemo.common.money.Money;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Renders the account-inquiry reply exactly as the reference program lays it out.
 *
 * <h2>Purpose</h2>
 * <p>The account-inquiry flow answers with a labelled fixed-width block, not with a structured document. The
 * layout is {@code 01 WS-ACCT-RESPONSE} at {@code app/app-vsam-mq/cbl/COACCT01.cbl} physical lines 130 to 169,
 * and the program moves it into {@code REPLY-MESSAGE PIC X(1000)} at physical line 426 before putting exactly
 * one thousand characters with a string format indicator. Because the format is declared as a string, the
 * label text, the field order and every field width ARE the interface: a consumer reads the values by offset.
 * That file is REFERENCE-ONLY -- it is read as the specification for this class and is never modified.</p>
 *
 * <h2>Why this is a mapper rather than a serialiser</h2>
 * <p>Assumptions: this class is the anti-corruption layer for one direction of one flow, which is the role the
 * migration plan assigns the mapper layer at its section 0.4.3: it is the only place representation concerns
 * -- fixed widths, sign overpunch, label text, padding -- are allowed to appear. The entity it reads carries
 * ordinary Java types and the caller receives a framed string; neither of them has to know how a signed zoned
 * decimal is spelled.</p>
 *
 * <h2>The three reply forms, and why all three are here</h2>
 * <p>The reference program produces three different replies from one paragraph, and all three are transcribed
 * so that a reader sees the whole reply vocabulary in one place:
 * <ul>
 *   <li>the labelled account block, when the keyed read succeeds (physical lines 405 to 427);</li>
 *   <li>an invalid-parameters sentence naming the key, when the read finds no record (physical lines 428
 *       to 436);</li>
 *   <li>an invalid-parameters sentence naming the key AND the function code, when the function or the key
 *       fails the guard at physical line 342 (physical lines 448 to 457).</li>
 * </ul>
 * The two sentences differ, and the difference is not cosmetic: one is produced by a lookup that ran and
 * found nothing, the other by a request that was never eligible to be looked up. A single merged message
 * would make those two indistinguishable to a requester.</p>
 *
 * <p>Trade-offs: the label text is carried verbatim, including its trailing spaces and its spacing around the
 * colon, because transformation rule T8 of the migration plan requires user-visible strings to cross
 * character-for-character. Tidying {@code 'ACCOUNT ID : '} to {@code 'ACCOUNT ID: '} would shift every
 * subsequent field by one character and silently break every consumer reading by offset.</p>
 *
 * <p>This class holds no mutable state, so a single instance serves every request concurrently.</p>
 */
@Component
public class AccountInquiryReplyMapper {

    /**
     * The number of integral digits in each monetary field, from {@code PIC S9(10)V99}.
     */
    private static final int MONEY_INTEGRAL_DIGITS = 10;

    /**
     * The width of the account identifier, from {@code WS-ACCT-ID PIC 9(11)}.
     */
    private static final int ACCOUNT_ID_WIDTH = 11;

    /**
     * The width of each date field, from {@code PIC X(10)}.
     */
    private static final int DATE_WIDTH = 10;

    /**
     * The width of the group identifier, from {@code WS-ACCT-GROUP-ID PIC X(10)}.
     */
    private static final int GROUP_ID_WIDTH = 10;

    /**
     * The width of the active-status flag, from {@code WS-ACCT-ACTIVE-STATUS PIC X(01)}.
     */
    private static final int STATUS_WIDTH = 1;

    /**
     * The total length of the labelled reply block, asserted rather than assumed.
     *
     * <p>Assumptions: 252 is the sum of the twenty-two declared field widths in {@code WS-ACCT-RESPONSE}.
     * It is stated as a constant so {@link #accountFound(Account)} can assert its own output length, which is
     * the one property of this class a consumer reading by offset actually depends on.</p>
     */
    public static final int REPLY_BLOCK_LENGTH = 252;

    /**
     * The label preceding the account identifier, from physical lines 132 to 133.
     */
    private static final String LABEL_ACCOUNT_ID = "ACCOUNT ID : ";

    /**
     * The label preceding the active status, from physical lines 135 to 136.
     */
    private static final String LABEL_STATUS = "ACCOUNT STATUS : ";

    /**
     * The label preceding the current balance, from physical lines 138 to 139.
     */
    private static final String LABEL_BALANCE = "BALANCE : ";

    /**
     * The label preceding the credit limit, from physical lines 142 to 143.
     */
    private static final String LABEL_CREDIT_LIMIT = "CREDIT LIMIT : ";

    /**
     * The label preceding the cash credit limit, from physical lines 146 to 147.
     */
    private static final String LABEL_CASH_LIMIT = "CASH LIMIT : ";

    /**
     * The label preceding the open date, from physical lines 150 to 151.
     */
    private static final String LABEL_OPEN_DATE = "OPEN DATE : ";

    /**
     * The label preceding the expiration date, from physical lines 153 to 154.
     */
    private static final String LABEL_EXPIRY_DATE = "EXPR DATE : ";

    /**
     * The label preceding the reissue date, from physical lines 156 to 157.
     */
    private static final String LABEL_REISSUE_DATE = "REIS DATE : ";

    /**
     * The label preceding the current-cycle credit, from physical lines 159 to 160.
     */
    private static final String LABEL_CYCLE_CREDIT = "CREDIT BAL : ";

    /**
     * The label preceding the current-cycle debit, from physical lines 163 to 164.
     */
    private static final String LABEL_CYCLE_DEBIT = "DEBIT BAL : ";

    /**
     * The label preceding the group identifier, from physical lines 167 to 168.
     */
    private static final String LABEL_GROUP_ID = "GROUP ID : ";

    /**
     * The opening of both invalid-parameters sentences, from physical line 429.
     */
    private static final String INVALID_PARAMETERS = "INVALID REQUEST PARAMETERS ";

    /**
     * The key label in both invalid-parameters sentences, from physical line 430.
     */
    private static final String INVALID_KEY_LABEL = "ACCT ID : ";

    /**
     * The function label in the second invalid-parameters sentence, from physical line 452.
     */
    private static final String INVALID_FUNCTION_LABEL = "FUNCTION : ";

    /**
     * Renders the labelled reply for an account that was found.
     *
     * <p>Assumptions: every monetary field is rendered by the shared zoned-decimal codec rather than by a
     * decimal format. The reference fields are declared {@code PIC S9(10)V99} DISPLAY, so on the wire they are
     * twelve characters with the sign overpunched into the last digit and no decimal point present. A
     * conventional {@code -1234.56} rendering would be both the wrong width and the wrong shape, and a
     * consumer decoding by the copybook would read the following label as part of the number.</p>
     *
     * <p>Assumptions: the account's active status, dates and group identifier are published here even though
     * the internal read API deliberately withholds them. That is not an inconsistency: this reply IS the
     * baseline's account-inquiry answer and its content is fixed by the reference layout, whereas the internal
     * read API is a new contract whose consumer needs three values. Widening either to match the other would
     * change one of them without cause.</p>
     *
     * @param account the account master row; must not be {@code null}
     * @return the reply body, exactly {@link #REPLY_BLOCK_LENGTH} characters and not yet framed to the
     *     message length, never {@code null}
     * @throws NullPointerException if {@code account} is {@code null}
     * @throws IllegalStateException if the rendered block is not its declared length, which would mean a field
     *     width here has fallen out of step with the reference layout
     */
    public String accountFound(Account account) {
        Objects.requireNonNull(account, "account must not be null");

        StringBuilder reply = new StringBuilder(REPLY_BLOCK_LENGTH)
                .append(LABEL_ACCOUNT_ID)
                .append(unsignedDigits(account.getAccountId(), ACCOUNT_ID_WIDTH))
                .append(LABEL_STATUS)
                .append(fixed(account.getActiveStatus(), STATUS_WIDTH, "active_status"))
                .append(LABEL_BALANCE)
                .append(money(account.getCurrentBalance(), "curr_bal"))
                .append(LABEL_CREDIT_LIMIT)
                .append(money(account.getCreditLimit(), "credit_limit"))
                .append(LABEL_CASH_LIMIT)
                .append(money(account.getCashCreditLimit(), "cash_credit_limit"))
                .append(LABEL_OPEN_DATE)
                .append(fixed(date(account.getOpenDate(), "open_date"), DATE_WIDTH, "open_date"))
                .append(LABEL_EXPIRY_DATE)
                .append(fixed(date(account.getExpirationDate(), "expiration_date"),
                        DATE_WIDTH, "expiration_date"))
                .append(LABEL_REISSUE_DATE)
                .append(fixed(date(account.getReissueDate(), "reissue_date"),
                        DATE_WIDTH, "reissue_date"))
                .append(LABEL_CYCLE_CREDIT)
                .append(money(account.getCurrentCycleCredit(), "curr_cyc_credit"))
                .append(LABEL_CYCLE_DEBIT)
                .append(money(account.getCurrentCycleDebit(), "curr_cyc_debit"))
                .append(LABEL_GROUP_ID)
                .append(fixed(account.getGroupId(), GROUP_ID_WIDTH, "group_id"));

        if (reply.length() != REPLY_BLOCK_LENGTH) {
            // WHY : Assumptions: this is asserted rather than trusted. Every field width above is transcribed
            //   from a separate copybook line, and a single wrong width shifts every following field without
            //   producing an error anywhere -- the consumer simply reads the wrong bytes. Checking the sum is
            //   the cheapest way to make that class of mistake fail immediately and locally.
            throw new IllegalStateException("the rendered account-inquiry reply is " + reply.length()
                    + " characters but the reference layout WS-ACCT-RESPONSE declares "
                    + REPLY_BLOCK_LENGTH + "; a field width has fallen out of step with the copybook");
        }
        return reply.toString();
    }

    /**
     * Renders the reply for a request whose keyed read found no account.
     *
     * <p>Assumptions: the key is echoed as its eleven raw characters, exactly as the reference program does --
     * it strings {@code WS-KEY} itself, which is a {@code PIC 9(11)} display field, so leading zeros are
     * present. Rendering the number instead would drop them and change the sentence a requester compares
     * against.</p>
     *
     * @param key the eleven-character key from the request; must not be {@code null}
     * @return the reply body, not yet framed to the message length, never {@code null}
     * @throws NullPointerException if {@code key} is {@code null}
     */
    public String accountNotFound(String key) {
        Objects.requireNonNull(key, "key must not be null");
        return INVALID_PARAMETERS + INVALID_KEY_LABEL + key;
    }

    /**
     * Renders the reply for a request that failed the function-and-key guard.
     *
     * <p>Assumptions: this sentence names the function code as well as the key, and it is a DIFFERENT string
     * from {@link #accountNotFound(String)}. The reference program produces two distinct sentences from two
     * distinct causes -- a lookup that ran and found nothing, and a request that was never eligible for one --
     * and merging them would make those causes indistinguishable to the requester.</p>
     *
     * @param key the eleven-character key from the request; must not be {@code null}
     * @param function the four-character function code from the request; must not be {@code null}
     * @return the reply body, not yet framed to the message length, never {@code null}
     * @throws NullPointerException if either argument is {@code null}
     */
    public String invalidRequest(String key, String function) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(function, "function must not be null");
        return INVALID_PARAMETERS + INVALID_KEY_LABEL + key + INVALID_FUNCTION_LABEL + function;
    }

    /**
     * Frames a reply body to the exact message length.
     *
     * <p>Assumptions: framing is exposed here rather than left to the caller so that all three reply forms
     * reach the wire the same way. The reference program moves each of them into the same
     * {@code PIC X(1000)} field and puts a literal length of 1000 regardless of which it produced.</p>
     *
     * @param body one of the three reply bodies above; must not be {@code null}
     * @return the body padded to the message length, never {@code null}
     * @throws IllegalArgumentException if the body exceeds the message length
     */
    public String frame(String body) {
        return InquiryRequestCodec.frame(Objects.requireNonNull(body, "body must not be null"));
    }

    /**
     * Renders a monetary value as a signed zoned decimal of the reference width.
     *
     * <p>Refactoring Rationale: a null value is REFUSED here rather than rendered as zero, and the earlier
     * shape of this method did render it as zero. Substituting zero was unreachable defensive code and it was
     * dangerous defensive code. It is unreachable because every monetary column is declared {@code NOT NULL} in
     * {@code db/migration/V1__account.sql}, is mapped {@code nullable = false} on the entity, and is rejected by
     * the entity's own public constructor -- so no database row and no in-process construction can produce one.
     * It is dangerous because the ONE path that could still produce a null is schema drift reaching the entity
     * through field-access hydration, and in that case publishing {@code 0.00} would report an account's
     * balance as zero to a requester. A financial misstatement that looks like a valid answer is worse than a
     * failure, so this fails.</p>
     *
     * @param value the amount; must not be {@code null}
     * @param field the column name, so a refusal names which value was absent
     * @return the twelve-character zoned rendering, never {@code null}
     * @throws IllegalStateException if the value is {@code null}, which can only mean the not-null guarantee
     *     the schema, the mapping and the constructor all assert has been broken
     */
    private static String money(java.math.BigDecimal value, String field) {
        if (value == null) {
            throw new IllegalStateException(field + " is null, but it is declared NOT NULL in the schema,"
                    + " nullable = false on the entity and rejected by the entity constructor; publishing"
                    + " zero here would report this account's position as zero to the requester");
        }
        return ZonedDecimalCodec.encodeMoney(Money.of(value), MONEY_INTEGRAL_DIGITS, true);
    }

    /**
     * Renders a date in the reference program's own textual form.
     *
     * <p>Assumptions: the stored column is a date and the reference field is {@code PIC X(10)} holding the
     * baseline's {@code 'YYYY-MM-DD'} text, so the ISO rendering is exactly ten characters and needs no
     * reformatting. A null is refused for the same reason as a null amount: all three date columns are declared
     * {@code NOT NULL}, so a null means the schema guarantee has been broken, and publishing ten spaces would
     * present a missing expiration date as a blank rather than as a fault.</p>
     *
     * @param value the date; must not be {@code null}
     * @param field the column name, so a refusal names which value was absent
     * @return the ten-character rendering, never {@code null}
     * @throws IllegalStateException if the value is {@code null}
     */
    private static String date(java.time.LocalDate value, String field) {
        if (value == null) {
            throw new IllegalStateException(field + " is null, but it is declared NOT NULL in the schema"
                    + " and nullable = false on the entity");
        }
        return value.toString();
    }

    /**
     * Renders an unsigned numeric identifier with leading zeros to a fixed width.
     *
     * @param value the identifier; must not be {@code null}
     * @param width the declared width
     * @return the zero-padded rendering, exactly {@code width} characters, never {@code null}
     * @throws IllegalStateException if the value is {@code null}, or does not fit the declared width -- the
     *     latter because truncating an identifier would publish a different account's number
     */
    private static String unsignedDigits(Long value, int width) {
        if (value == null) {
            throw new IllegalStateException(
                    "the account identifier is null, but it is this entity's primary key");
        }
        String digits = String.valueOf(value.longValue());
        if (digits.length() > width) {
            throw new IllegalStateException("the account identifier occupies " + digits.length()
                    + " digits but the reference field declares " + width
                    + "; truncating it would publish a different account's identifier");
        }
        return "0".repeat(width - digits.length()) + digits;
    }

    /**
     * Renders a text value left-justified and space-padded to a fixed width.
     *
     * <p>Assumptions: a value SHORTER than the field is padded, because the reference fields are fixed width
     * and a consumer reads the next one by offset. A null is refused rather than treated as empty, for the
     * reason recorded on {@link #money(java.math.BigDecimal, String)}.</p>
     *
     * @param value the text; must not be {@code null}
     * @param width the declared width
     * @param field the column name, so a refusal names which value was at fault
     * @return the padded rendering, exactly {@code width} characters, never {@code null}
     * @throws IllegalStateException if the value is {@code null} or is longer than the declared width, the
     *     latter because truncating it here would shift every following field
     */
    private static String fixed(String value, int width, String field) {
        if (value == null) {
            throw new IllegalStateException(field + " is null, but it is declared NOT NULL in the schema"
                    + " and nullable = false on the entity");
        }
        if (value.length() > width) {
            throw new IllegalStateException("a value of " + value.length()
                    + " characters does not fit the reference field width of " + width
                    + "; truncating it would shift every following field in the reply");
        }
        return value + " ".repeat(width - value.length());
    }
}
