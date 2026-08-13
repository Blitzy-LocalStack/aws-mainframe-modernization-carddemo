package com.carddemo.reporting.mapper;

import com.carddemo.reporting.domain.TransactionCategoryBalanceView;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Renders one line of the category-balance report exactly as the reference sort reformats it.
 *
 * <p>Purpose: this is the whole of {@code app/jcl/PRTCATBL.jcl}'s {@code OUTREC} at lines 53-56. That
 * job has no COBOL program, so its {@code OUTREC FIELDS} list IS the record layout and there is no
 * file description anywhere to read it from instead:</p>
 *
 * <pre>
 * OUTREC FIELDS=(TRANCAT-ACCT-ID,X,
 *     TRANCAT-TYPE-CD,X,
 *     TRANCAT-CD,X,
 *     TRAN-CAT-BAL,EDIT=(TTTTTTTTT.TT),9X)
 * </pre>
 *
 * <p>Each named field takes the width its {@code SYMNAMES} entry declares at lines 47-50, each
 * {@code X} is one blank, {@code EDIT=(TTTTTTTTT.TT)} is a twelve-character pattern, and {@code 9X}
 * is nine trailing blanks.</p>
 *
 * <p><b>The declared record length and the field list disagree by one byte, and this class resolves
 * the disagreement rather than hiding it.</b> Summing the list gives
 * 11 + 1 + 2 + 1 + 4 + 1 + 12 + 9 = 41, while {@code SORTOUT} at line 61 declares
 * {@code DCB=(LRECL=40,RECFM=FB,BLKSIZE=0)}. Unlike the comparable disagreement in
 * {@code app/jcl/CREASTMT.JCL}, there is no program file description available to break the tie --
 * that job's two declarations were settled against {@code app/cbl/CBSTM03A.CBL}'s own
 * {@code PIC X(80)} and {@code PIC X(100)} records, and here no such program exists.</p>
 *
 * <p>Assumptions: the DECLARED length wins and the line is 40 bytes, with the trailing blank filler
 * carrying the difference at EIGHT blanks rather than nine. Two reasons, and the second is the
 * decisive one. First, the record length is what a downstream reader of the catalogued dataset uses:
 * a fixed-block dataset is read in units of its {@code LRECL}, so a 41-byte record against a declared
 * 40 would misalign every line after the first for any consumer that trusted the catalogue. Second,
 * the byte the truncation removes is PADDING and not data -- positions 1 through 32 hold the key, the
 * separators and the edited balance and are identical either way, so nothing a reader interprets
 * changes. The divergence is registered as {@code D-PRTCATBL-LRECL} in
 * {@code docs/architecture/cobol-to-service-traceability.md}.</p>
 *
 * <p>Alternatives Considered: emitting 41 bytes and treating the declared length as the error. It was
 * rejected on the reasoning above -- it moves the inconsistency from a padding byte, where nothing
 * reads it, to the record framing, where everything does. Also considered and rejected: making the
 * width configurable so an operator could choose. A record layout that varies by deployment is not a
 * contract, and the one thing a fixed-width consumer cannot tolerate is a width it has to discover.
 * </p>
 *
 * <p>Assumptions: the encoding is US-ASCII. Every field this line carries is a digit, a blank, a
 * period or one of the two-character type codes the reference's own extracts spell in ASCII, so no
 * byte above 127 is representable in it and a wider charset could only differ on input this layout
 * refuses. The reference wrote EBCDIC; the target writes the same characters in the encoding every
 * other artifact this context produces uses, and that choice is stated once here.</p>
 *
 * <p>Documentation convention: {@code docs/CODE_DOCUMENTATION_STANDARD.md}.</p>
 *
 * <p>Baseline lineage: citations are provenance only. Nothing under {@code app/} is read at run time
 * and nothing under it is altered by this migration.</p>
 */
public final class CategoryBalanceLineLayout {

    /**
     * Length of one emitted line, from {@code SORTOUT}'s {@code DCB=(LRECL=40)} at
     * {@code app/jcl/PRTCATBL.jcl:61}.
     */
    public static final int LINE_LENGTH = 40;

    /**
     * Width of the account identifier, from {@code TRANCAT-ACCT-ID,1,11,ZD} at
     * {@code app/jcl/PRTCATBL.jcl:47}.
     */
    public static final int ACCOUNT_ID_WIDTH = 11;

    /**
     * Width of the transaction type code, from {@code TRANCAT-TYPE-CD,12,2,CH} at
     * {@code app/jcl/PRTCATBL.jcl:48}.
     */
    public static final int TYPE_CODE_WIDTH = 2;

    /**
     * Width of the transaction category code, from {@code TRANCAT-CD,14,4,ZD} at
     * {@code app/jcl/PRTCATBL.jcl:49}.
     */
    public static final int CATEGORY_CODE_WIDTH = 4;

    /**
     * Number of trailing blanks the emitted line carries.
     *
     * <p>Assumptions: EIGHT, where the reference's {@code 9X} names nine. This is the single byte the
     * record-length resolution in this class's own documentation absorbs, and it is absorbed here --
     * in the padding -- rather than anywhere a reader interprets.</p>
     */
    public static final int TRAILING_BLANK_WIDTH = 8;

    /** The single-blank separator each {@code X} in the reference's {@code OUTREC} contributes. */
    private static final char SEPARATOR = ' ';

    /** The character an unfilled position carries. */
    private static final char BLANK = ' ';

    /**
     * Refuses instantiation of this layout holder.
     *
     * <p>Trade-offs: a private constructor rather than an interface holding constants. An interface
     * would publish the constants without a constructor to hide, but it would also let a type
     * implement it and inherit them, which invites a reader to think the layout is a behaviour a
     * class can adopt rather than one fixed record shape.</p>
     *
     * @throws AssertionError always, because the type holds only the layout. It is raised rather than
     *     left as an empty body so that a reflective instantiation -- the one route a private
     *     constructor does not close -- fails loudly instead of yielding a useless instance
     */
    private CategoryBalanceLineLayout() {
        throw new AssertionError("CategoryBalanceLineLayout holds only the layout and is not"
                + " instantiable");
    }

    /**
     * Renders one balance as a fixed-length report line.
     *
     * @param row the projected balance to render; must not be {@code null}, and its key and balance
     *     must both be present, which holds for any row the provider materialised
     * @return exactly {@value #LINE_LENGTH} bytes holding the zero-padded account identifier, a
     *     blank, the type code, a blank, the zero-padded category code, a blank, the edited balance
     *     and {@value #TRAILING_BLANK_WIDTH} trailing blanks, never {@code null}
     * @throws NullPointerException if {@code row}, its key or its balance is {@code null}
     * @throws ArithmeticException if the balance needs more than nine integer digits or does not
     *     carry exactly two decimal places, raised by the edit-mask formatter
     * @throws IllegalArgumentException if the account identifier, type code or category code is wider
     *     than the position the reference's own {@code SYMNAMES} entry declares for it
     * @throws IllegalStateException if the composed line is not {@value #LINE_LENGTH} bytes, which
     *     reports a defect in this class rather than a fault of the caller
     */
    public static byte[] render(TransactionCategoryBalanceView row) {
        Objects.requireNonNull(row, "row must not be null");
        TransactionCategoryBalanceView.TransactionCategoryBalanceKey key =
                Objects.requireNonNull(row.getKey(), "row key must not be null");

        StringBuilder line = new StringBuilder(LINE_LENGTH);
        // WHY : Assumptions: the identifier is ZERO-padded on the LEFT and not blank-padded, because
        //       the reference declares it ZD -- zoned decimal -- at app/jcl/PRTCATBL.jcl:47, and a
        //       zoned-decimal field of eleven positions carries eleven digits. Blank padding would
        //       produce a field that is not a number in the form the declaration names, and a
        //       consumer reading positions 1 through 11 as digits would fail on the blanks.
        line.append(zeroPadded(
                Objects.requireNonNull(key.accountId(), "account identifier must not be null")
                        .toString(),
                ACCOUNT_ID_WIDTH, "account identifier"));
        line.append(SEPARATOR);
        // WHY : Assumptions: the type code is padded on the RIGHT with blanks, because the reference
        //       declares it CH -- character -- at app/jcl/PRTCATBL.jcl:48, and a character field of
        //       declared width is left-justified and blank-filled. This is the one field of the three
        //       whose padding side differs from its neighbours, which is why it is stated here rather
        //       than shared with them.
        line.append(blankPadded(
                Objects.requireNonNull(key.typeCode(), "type code must not be null"),
                TYPE_CODE_WIDTH, "type code"));
        line.append(SEPARATOR);
        // WHY : Assumptions: the category code is ZERO-padded like the identifier and for the same
        //       reason -- app/jcl/PRTCATBL.jcl:49 declares it ZD -- even though this context carries
        //       it as text. Carrying it as text is what PRESERVES the leading zeros the 0001 form in
        //       app/data/ASCII/tcatbal.txt holds; padding here is what supplies them when a source
        //       row somehow holds fewer than four characters.
        line.append(zeroPadded(
                Objects.requireNonNull(key.categoryCode(), "category code must not be null"),
                CATEGORY_CODE_WIDTH, "category code"));
        line.append(SEPARATOR);
        line.append(CobolEditMask.formatSortEditedBalance(
                Objects.requireNonNull(row.getBalance(), "balance must not be null")));
        line.append(String.valueOf(BLANK).repeat(TRAILING_BLANK_WIDTH));

        String rendered = line.toString();
        if (rendered.length() != LINE_LENGTH) {
            throw new IllegalStateException("the category-balance line composed to "
                    + rendered.length() + " characters where app/jcl/PRTCATBL.jcl:61 declares "
                    + LINE_LENGTH);
        }
        return rendered.getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * Left-pads a value with zeroes to a declared width, refusing a value that is already wider.
     *
     * @param value the value to pad; must not be {@code null}
     * @param width the declared width; must be positive
     * @param field the field name used in the refusal message; must not be {@code null}
     * @return the padded value, exactly {@code width} characters, never {@code null}
     * @throws IllegalArgumentException if {@code value} is wider than {@code width}, because
     *     truncating an identifier or a code would emit a line naming a different row
     */
    private static String zeroPadded(String value, int width, String field) {
        if (value.length() > width) {
            throw new IllegalArgumentException("the " + field + " occupies " + value.length()
                    + " characters where app/jcl/PRTCATBL.jcl declares " + width);
        }
        return "0".repeat(width - value.length()) + value;
    }

    /**
     * Right-pads a value with blanks to a declared width, refusing a value that is already wider.
     *
     * @param value the value to pad; must not be {@code null}
     * @param width the declared width; must be positive
     * @param field the field name used in the refusal message; must not be {@code null}
     * @return the padded value, exactly {@code width} characters, never {@code null}
     * @throws IllegalArgumentException if {@code value} is wider than {@code width}
     */
    private static String blankPadded(String value, int width, String field) {
        if (value.length() > width) {
            throw new IllegalArgumentException("the " + field + " occupies " + value.length()
                    + " characters where app/jcl/PRTCATBL.jcl declares " + width);
        }
        return value + String.valueOf(BLANK).repeat(width - value.length());
    }
}
