package com.carddemo.batch.dto;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * The opaque ten-character business-date token that a batch job receives as its
 * {@code --business-date=} argument.
 *
 * <p>This type carries the token and asserts its width. It does not interpret it. The baseline
 * program that consumes the equivalent parameter declares the field
 * {@code PARM-DATE PIC X(10)} at {@code app/cbl/CBACT04C.cbl:178} -- alphanumeric, sitting inside
 * the linkage group whose halfword length prefix at line 177 is
 * {@code PARM-LENGTH PIC S9(04) COMP} -- and receives it from outside the program through
 * {@code PROCEDURE DIVISION USING EXTERNAL-PARMS} at line 180. Nothing in that program parses the
 * field, compares it to a calendar or re-renders it, and neither does this type.</p>
 *
 * <h2>Why the token is carried through unchanged</h2>
 *
 * <p>Assumptions: the token is the leading segment of a primary key, not a date.
 * {@code app/cbl/CBACT04C.cbl:473-480} builds the identifier of every generated interest
 * transaction by concatenation and by nothing else -- line 474 reads
 * {@code ADD 1 TO WS-TRANID-SUFFIX} and lines 476 to 480 read
 * {@code STRING PARM-DATE, WS-TRANID-SUFFIX DELIMITED BY SIZE INTO TRAN-ID END-STRING}. The
 * suffix is {@code WS-TRANID-SUFFIX PIC 9(06) VALUE 0} at line 173 and the destination is
 * {@code TRAN-ID PIC X(16)} at {@code app/cpy/CVTRA05Y.cpy:5}, so ten characters followed by six
 * digits occupy sixteen exactly, with no slack at either end. The token's bytes are therefore
 * observable output, and every one of them survives into the identifier.</p>
 *
 * <p>Alternatives Considered: parsing the token into a {@link java.time.LocalDate} in the
 * constructor and re-rendering it in one canonical layout, which is what a reader expects of a
 * type named after a date. Rejected, and the consequence is measurable rather than speculative:
 * the parity oracle commits expectation files in BOTH accepted layouts.
 * {@code tests/golden/interest/happy_path/transact.expected} opens {@code 2024-01-15000001}, the
 * separated layout, while {@code tests/golden/interest/e2e_interest_cycle_transactions.expected}
 * opens {@code 2022071800000001}, the compact layout that {@code app/jcl/INTCALC.jcl:22} injects as
 * {@code PARM='2022071800'}. Canonicalising to either layout changes the identifier bytes of the
 * other scenario, and the golden comparison then reports a parity failure over records that look
 * entirely plausible. So: no whitespace is discarded, no separator is inserted or removed, no
 * character case is altered and no character is substituted anywhere in this type.</p>
 *
 * <p>Assumptions: the width is exactly ten, and it is ten because both layouts actually seen are
 * ten characters -- {@code '2022071800'} at {@code app/jcl/INTCALC.jcl:22} and the separated
 * {@code 2022-07-18} that the orchestration state supplies as a container override. The
 * <em>width</em> is the contract and the <em>layout</em> is not, which is what makes ten plus six
 * equal sixteen for either one. Admitting a shorter form such as a bare eight-character
 * {@code YYYYMMDD} would leave a fourteen-character identifier in a sixteen-character field, and
 * the baseline's own concatenation offers nowhere to pad it.</p>
 *
 * <h2>Why this type validates width and not date validity</h2>
 *
 * <p>Assumptions: {@code PARM-DATE} is alphanumeric at {@code app/cbl/CBACT04C.cbl:178} and the
 * program never treats it as a calendar value, so there is no date validity to preserve. The
 * driver at {@code app/jcl/INTCALC.jcl:22} supplies {@code '2022071800'}, which is not a valid
 * ISO local date at all -- it is eight date digits followed by two further characters. A
 * constructor that parsed strictly would reject the baseline's own production parameter, so
 * strict parsing here is not a stronger contract, it is the wrong one.</p>
 *
 * <p>Assumptions: the character class of the token is asserted at the process boundary rather than
 * here, and the split is deliberate rather than an omission. The module entry point
 * {@code BatchApplication} rejects a {@code --business-date=} value that is not exactly ten
 * characters of ASCII digits and ASCII hyphen-minus, and it has to hold that gate itself: it runs
 * before an application context exists, and its own recorded reasoning is that a malformed command
 * line must be refused without one. This type is the same contract modelled for code that runs
 * after start-up, and it asserts the one invariant the derived identifier depends on. That entry
 * point already accepted, in writing, the cost of stating its contract twice; adding a second
 * character-class gate here would add a second authority for something the identifier arithmetic
 * does not depend on at all.</p>
 *
 * <h2>Why there is no clock and no default</h2>
 *
 * <p>Assumptions: the date is injected, always. {@code PROCEDURE DIVISION USING EXTERNAL-PARMS} at
 * {@code app/cbl/CBACT04C.cbl:180} is how the baseline takes it, and the module entry point
 * {@code BatchApplication} declares no default for its {@code --business-date=} option for the
 * same reason, recording that a default would give one task two sources of truth that can
 * disagree. This type consequently exposes no
 * clock-reading factory, no constant standing for the current day and no way to obtain an instance
 * without naming a token. A factory reading the host clock would make a rerun of a given business
 * day produce different identifiers from its predecessor, which is precisely the reproducibility
 * the injected parameter exists to guarantee -- and it would pass every unit test written on the
 * day it was authored, so nothing would surface until the first rerun.</p>
 *
 * <p>Assumptions: the token performs no arithmetic of its own either. There is no method here that
 * advances or rewinds the token by a day, because doing so on the compact layout would have to
 * invent an interpretation of its trailing two characters. A caller that genuinely needs date
 * arithmetic goes through {@link #parseIsoDateForRangeComparison()} and owns what that implies.</p>
 *
 * <h2>Why a record rather than a bare string parameter</h2>
 *
 * <p>Trade-offs: a bare {@code String} passed from method to method cannot assert the
 * ten-character invariant at any boundary, and that invariant is the only thing standing between a
 * mistyped argument and a sixteen-character key silently becoming fifteen or seventeen. Wrapping
 * it moves the assertion to the one place every caller must pass through. Two costs are accepted
 * in exchange, and both are real: one extra type for a single string, and the same width expressed
 * in this module twice, once at the process boundary and once here.</p>
 *
 * <p>Assumptions: the accessor {@link #token()}, and not {@link #toString()}, is the source of the
 * value that reaches a record or an identifier. The generated {@code toString} is deliberately
 * left as the record produces it, which wraps the token in the type name and the component name
 * and is therefore never mistakable for the wire token; a hand-written {@code toString} returning
 * the bare token would look interchangeable with the accessor and would eventually be used as one.
 * The generated {@code equals} and {@code hashCode} are left in place for the same reason -- token
 * equality is exactly the equality this type wants, so there is nothing to add.</p>
 *
 * @param token the business-date token exactly as the caller supplied it, ten characters, neither
 *     interpreted as a calendar value nor altered in any way; it is concatenated verbatim into
 *     generated transaction identifiers, so its bytes are part of this module's observable output
 */
public record BusinessDate(String token) {

    /**
     * The one accepted character width of a business-date token, ten.
     *
     * <p>Assumptions: the number comes from {@code PARM-DATE PIC X(10)} at
     * {@code app/cbl/CBACT04C.cbl:178}, and it is held privately here because the published
     * authority for it in this module is the module entry point {@code BatchApplication}, which
     * reports the same number in the usage diagnostic accompanying a rejected command line.
     * Exposing a second public constant for one number would let a caller compile against
     * whichever of the two it happened to find first.</p>
     */
    private static final int TOKEN_LENGTH = 10;

    /**
     * Asserts the token's width and stores it exactly as received.
     *
     * <p>The token is neither parsed nor altered. Only its presence and its width are checked,
     * for the reasons recorded on this type.</p>
     *
     * <p>Successful construction yields this record instance and no separate return value.</p>
     *
     * @param token the candidate business-date token; must be non-null and exactly ten characters
     * @throws IllegalArgumentException if {@code token} is {@code null}, or if its length is
     *     anything other than exactly ten characters
     */
    public BusinessDate {
        // WHY : Assumptions: a single exception type covers the absent token and the mis-sized one
        //       because a caller can do nothing different about the two. The type matches the one
        //       the module entry point already raises for a malformed --business-date= argument,
        //       so a rejection reads the same whether it happened at the process boundary or
        //       later inside the module.
        if (token == null) {
            throw new IllegalArgumentException("business-date token is required and was null");
        }

        // WHY : Assumptions: the check is on length alone, and it is deliberately blind to what
        //       the characters are. app/cbl/CBACT04C.cbl:178 declares PARM-DATE alphanumeric and
        //       app/jcl/INTCALC.jcl:22 supplies '2022071800', which no strict ISO parse accepts,
        //       so a validity check here would reject the baseline's own production parameter.
        //       Length is what the derived identifier depends on: ten characters plus the
        //       six-digit suffix of app/cbl/CBACT04C.cbl:173 fill TRAN-ID PIC X(16) exactly.
        // WHY : Assumptions: the length is read off the token as supplied, with no leading or
        //       trailing whitespace removed first. An eleven-character value that would measure
        //       ten after whitespace removal is rejected rather than quietly accepted, because
        //       accepting it would concatenate a space into a primary key.
        if (token.length() != TOKEN_LENGTH) {
            throw new IllegalArgumentException("business-date token '" + token + "' is "
                    + token.length() + " characters, not exactly " + TOKEN_LENGTH);
        }
    }

    /**
     * Returns the business-date token exactly as it was supplied.
     *
     * <p>This is the value that a caller concatenates into a generated transaction identifier, and
     * it is byte-for-byte what the constructor received.</p>
     *
     * <p>This accessor accepts no parameters.</p>
     *
     * @return the ten-character token, unaltered; never {@code null}
     */
    public String token() {
        // WHY : Assumptions: the accessor is written out rather than left implicit purely so that
        //       this contract has somewhere to live. An implicit accessor carries no Javadoc block
        //       and so no @return, and the guarantee that matters about this method is exactly the
        //       thing an implicit accessor cannot state: that it returns the token unaltered.
        return token;
    }

    /**
     * Parses the token as an ISO local date, for date-range comparison only.
     *
     * <p>Three properties of this method are load-bearing and a caller must know all three.</p>
     *
     * <p>This operation accepts no parameters.</p>
     *
     * <ul>
     *   <li>It exists <b>only</b> so that a job can compare a business date against a range. It
     *       has no other sanctioned use.</li>
     *   <li>Its result is <b>never</b> the value used to build a generated transaction identifier.
     *       Identifier construction reads {@link #token()} and nothing else, because
     *       {@code app/cbl/CBACT04C.cbl:476-480} concatenates the parameter as supplied and any
     *       re-rendering here would change the identifier bytes.</li>
     *   <li>It <b>throws</b> when the token is not in the separated ISO layout, and a caller must
     *       expect that for a compact {@code YYYYMMDDnn} token. The baseline's own driver supplies
     *       exactly such a token: {@code app/jcl/INTCALC.jcl:22} injects
     *       {@code PARM='2022071800'}, for which this method throws.</li>
     * </ul>
     *
     * <p>Trade-offs: providing this method at all was weighed against omitting it and letting each
     * caller that needs a range predicate parse the raw token itself. Concentrating the single
     * legitimate interpretation in one named, auditable method was chosen because the dispersed
     * alternative gives every call site its own opportunity to re-render the token on the way
     * back out, and a re-rendered token reaching an identifier is the parity failure this whole
     * type exists to prevent. The cost accepted is a method that is unusable for a token in the
     * compact layout, which is why the layout constraint is in the method's own name.</p>
     *
     * @return the token interpreted as an ISO local date, suitable for range comparison and for
     *     nothing that reaches a stored identifier; never {@code null}
     * @throws DateTimeParseException if the token is not in the separated ISO layout
     *     {@code YYYY-MM-DD}, which includes every token in the compact layout
     */
    public LocalDate parseIsoDateForRangeComparison() {
        // WHY : Assumptions: the token is handed to the parser as held, with nothing inserted to
        //       coax a compact layout into an acceptable one. Rewriting it here would give this
        //       method a second behaviour -- silent reinterpretation -- that no caller could tell
        //       apart from a successful parse of a token that was already separated.
        return LocalDate.parse(token);
    }
}
