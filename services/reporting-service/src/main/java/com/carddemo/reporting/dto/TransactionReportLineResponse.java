package com.carddemo.reporting.dto;

import com.carddemo.common.money.Money;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Carries one detail line of the 133-column daily transaction report as an API response with exactly
 * eight components.
 *
 * <p>The account identifier, transaction-type description and transaction-category description are
 * join results rather than fields copied from the transaction record. The declared line width comes
 * from {@code TRANSACTION-HEADER-2 PIC X(133)} at {@code app/cpy/CVTRA07Y.cpy} L48, corroborated by
 * {@code WS-BLANK-LINE PIC X(133)} at {@code app/cbl/CBTRN03C.cbl} L133 and
 * {@code DCB=(LRECL=133,...)} at {@code app/jcl/TRANREPT.jcl} L78; it is not calculated by adding
 * component widths.</p>
 *
 * <p>Assumptions: the component count is established twice. The
 * {@code TRANSACTION-DETAIL-REPORT} group at {@code app/cpy/CVTRA07Y.cpy} L15 declares its eight
 * data fields at L16, L18, L20, L22, L24, L26, L28 and L30. Independently,
 * {@code 1120-WRITE-DETAIL} at {@code app/cbl/CBTRN03C.cbl} L361 to L374 initialises the layout at
 * L362 and performs exactly eight field moves at L363 to L370. The six padding fields at
 * {@code CVTRA07Y.cpy} L17, L19, L23, L27, L29 and L31 are omitted. The one-character fields at
 * L21 and L25 are different: each contains a literal hyphen that renders {@code NN-Description}, so
 * they are data owned by {@code com.carddemo.reporting.mapper}, not padding and not separate
 * response components.</p>
 *
 * <p>Assumptions: no card number, transaction description, merchant fields or timestamps belong to
 * this response even though the source transaction declares them. {@code app/cpy/CVTRA05Y.cpy}
 * carries {@code TRAN-DESC} at L9, four merchant fields at L11 to L14, {@code TRAN-CARD-NUM} at
 * L15, {@code TRAN-ORIG-TS} at L16 and {@code TRAN-PROC-TS} at L17. None appears among the report
 * fields at {@code CVTRA07Y.cpy} L15 to L31 or the eight moves at {@code CBTRN03C.cbl} L363 to
 * L370. Rule T1 therefore keeps them absent rather than adding a ninth component that the
 * byte-level report does not carry.</p>
 *
 * <p>Assumptions: the transaction record has no account identifier at all, so three components are
 * supplied by the report's joins. {@code accountId} comes from
 * {@code MOVE XREF-ACCT-ID TO TRAN-REPORT-ACCOUNT-ID} at {@code CBTRN03C.cbl} L364 and the
 * {@code CARDXREF} input at {@code TRANREPT.jcl} L67 to L68.
 * {@code typeDescription} comes from the move at L366 and {@code TRANTYPE} at L69 to L70.
 * {@code categoryDescription} comes from the move at L368 and {@code TRANCATG} at L71 to L72.
 * The complete input roster at L65 to L74 is {@code TRANFILE}, {@code CARDXREF},
 * {@code TRANTYPE}, {@code TRANCATG} and {@code DATEPARM}: the transaction source, three lookup
 * channels and the injected bounds.</p>
 *
 * <p>Assumptions: {@code CBTRN03C.cbl} explicitly writes
 * {@code TRAN-TYPE-CD OF TRAN-RECORD} at L365 and
 * {@code TRAN-CAT-CD OF TRAN-RECORD} at L367 because the reference layouts repeat those field
 * names. The unambiguous names {@code typeCode} and {@code categoryCode} preserve which values the
 * detail writer selected without exposing a copybook qualification to API consumers.</p>
 *
 * <p>Assumptions: identifier-shaped numerics remain strings of digits. The report category is
 * {@code PIC 9(04)} at {@code CVTRA07Y.cpy} L24, so {@code 0001} is four transmitted characters
 * rather than the value {@code 1}. The account identifier follows the same wire discipline,
 * corroborated by the character-over-numeric pairs in {@code app/cpy/CVCRD01Y.cpy}: account at
 * L34 and L36, card at L37 and L39, and customer at L40 and L42. This applies the string-identifier
 * decision registered at {@code com.carddemo.reporting.dto}; the two digit patterns on this record
 * enforce that representation without converting it.</p>
 *
 * <p>Assumptions: four numeric regimes coexist and are not interchangeable. The transaction report
 * uses {@code Z}-suppressed masks; unsigned {@code 9(nn)} fields preserve leading zeros; the
 * statement uses trailing-sign, non-comma masks {@code 9(9).99-} at
 * {@code app/cbl/CBSTM03A.CBL} L113 and {@code Z(9).99-} at L137; and the API carries
 * {@link Money} at scale two under {@code RoundingMode.HALF_UP} as quoted plain decimal text. One
 * Java numeric representation cannot reproduce all four display contracts.</p>
 *
 * <p>Alternatives Considered: emitting {@code amount} as a JSON number was rejected in favour of
 * the quoted string written by {@code com.carddemo.common.money.MoneyModule}. Most clients parse a
 * JSON number into an IEEE-754 binary floating point value at the boundary, where exact decimal
 * cents can be approximated. The source amount is {@code PIC S9(09)V99} at
 * {@code app/cpy/CVTRA05Y.cpy} L10, eleven significant digits, and the report ceiling is
 * {@code 999,999,999.99}; the string preserves the value and its two-place scale through the final
 * hop. The shared-money decision is registered at {@code com.carddemo.reporting.dto}, so this
 * record carries {@link Money} and adds no serializer annotation.</p>
 *
 * <p>Assumptions: the display mask remains a mapper concern under the boundary decision registered
 * at {@code com.carddemo.reporting.dto}. {@code TRAN-REPORT-AMT PIC -ZZZ,ZZZ,ZZZ.ZZ} at
 * {@code CVTRA07Y.cpy} L30 is 15 characters. Each {@code Z} suppresses a leading zero to a blank,
 * and there is no {@code 9} position, including in the cents, so zero renders as 15 BLANKS. A
 * comma to the left of the first significant digit is blanked as well. The fixed leading minus
 * prints for a negative value and becomes a blank for a positive value. The greatest magnitude the
 * mask can render is {@code 999,999,999.99}.</p>
 *
 * <p>Trade-offs: the detail and totals masks remain separate even though both occupy 15 characters.
 * This record carries the minus-leading mask at {@code CVTRA07Y.cpy} L30, while the page, account
 * and grand totals use the plus-leading {@code +ZZZ,ZZZ,ZZZ.ZZ} masks at L54, L60 and L66, whose
 * fixed plus position always prints a sign. {@code ReportTotalsResponse} is the sibling response
 * that carries the totals form; merging the two would erase a byte-visible distinction.</p>
 *
 * <p>Assumptions: this record is only the element type in the shared
 * {@code PageResponse<TransactionReportLineResponse>}; a controller composes that envelope, so
 * this type neither imports it nor carries one as a component. The shared type at
 * {@code services/common-lib/src/main/java/com/carddemo/common/web/PageResponse.java} L210 to L217
 * has one element type; {@code firstKey} and {@code lastKey} are opaque boundary tokens that may be
 * absent for an empty result, and {@code hasNext} is established by reading one row beyond those
 * returned. This cites the shared-envelope decision registered at
 * {@code com.carddemo.reporting.dto} without duplicating the envelope here.</p>
 *
 * <p>Assumptions: report pagination and API cursor paging serve different consumers.
 * {@code REPORT-PAGE-TOTALS} at {@code CVTRA07Y.cpy} L50 to L54 is an output band,
 * {@code WS-PAGE-SIZE PIC 9(03) COMP-3 VALUE 20} at {@code CBTRN03C.cbl} L131 to L132 sets its
 * twenty-detail depth, and the modulo test at L282 emits the band. API paging is positioned by an
 * opaque cursor instead. This response therefore carries neither the report-band depth nor an
 * ordinal, and it does not restore the baseline {@code WS-CA-SCREEN-NUM} or
 * {@code WS-CA-LAST-PAGE-DISPLAYED} fields, the latter of which uses shown {@code 0} and
 * not-shown {@code 9}.</p>
 *
 * <p>Assumptions: report ordering has an explicit determinism requirement. The sort symbols at
 * {@code TRANREPT.jcl} L41 to L42 place the card number at one-based position 263 and the process
 * date at one-based position 305, corroborating positions 262 and 304 when counting from zero,
 * derived from {@code CVTRA05Y.cpy} L15 and L17. The baseline sort at
 * {@code TRANREPT.jcl} L46 uses only the
 * card number and has no {@code EQUALS}, so rows sharing a card have no stable baseline tie order.
 * Java ordering implements the transaction identifier as a stable secondary key so golden
 * comparison is reproducible. The baseline does the single-key sort; Java implements the stable
 * tie key; this paragraph documents the divergence under the baseline-framing decision registered
 * at {@code com.carddemo.reporting.dto}. The ownership and divergence register is
 * {@code docs/architecture/cobol-to-service-traceability.md}; this file assigns no identifier to
 * its entries.</p>
 *
 * <p>Assumptions: {@code INCLUDE COND=(...)} at {@code TRANREPT.jcl} L47 to L48 is an inclusive
 * record-selection predicate over the date portion of the processing timestamp. The corresponding
 * service query expresses it as a SQL {@code WHERE} condition; it is not a step gate or a
 * state-machine branch merely because the JCL uses the word {@code COND}.</p>
 *
 * <p>Assumptions: the DD evidence exercises the {@code ledger}, {@code account} and
 * {@code reference} schemas, while {@code card} appears in neither report nor statement input
 * rosters. The runtime connection resolves only the {@code reporting} view schema, as
 * {@code services/reporting-service/src/main/resources/application.yml} L626 declares. This
 * record performs no query and imports no data-access type, applying the no-persistence decision
 * registered at {@code com.carddemo.reporting.dto}.</p>
 *
 * <p>Documentation label spelling follows {@code docs/CODE_DOCUMENTATION_STANDARD.md}. A type
 * declaration returns no value and raises nothing; the eight component parameters are documented
 * below.</p>
 *
 * @param transactionId the transaction identifier, at most 16 characters as
 *     {@code TRAN-REPORT-TRANS-ID PIC X(16)} declares at {@code CVTRA07Y.cpy} L16; it is also the
 *     stable secondary ordering key required when card values tie
 * @param accountId the joined account identifier, at most 11 digits as
 *     {@code TRAN-REPORT-ACCOUNT-ID PIC X(11)} declares at L18, supplied by the L364 cross-reference
 *     move and the {@code CARDXREF} input at {@code TRANREPT.jcl} L67 to L68
 * @param typeCode the transaction-type code, at most two characters as
 *     {@code TRAN-REPORT-TYPE-CD PIC X(02)} declares at {@code CVTRA07Y.cpy} L20, selected from the
 *     explicitly qualified transaction record field at {@code CBTRN03C.cbl} L365
 * @param typeDescription the joined transaction-type description, at most 15 characters as
 *     {@code TRAN-REPORT-TYPE-DESC PIC X(15)} declares at {@code CVTRA07Y.cpy} L22, supplied by the
 *     L366 move and the {@code TRANTYPE} input at {@code TRANREPT.jcl} L69 to L70
 * @param categoryCode the transaction-category join key as at most four digits, preserving leading
 *     zeros from {@code TRAN-REPORT-CAT-CD PIC 9(04)} at {@code CVTRA07Y.cpy} L24 and selected from
 *     the explicitly qualified transaction record field at {@code CBTRN03C.cbl} L367
 * @param categoryDescription the joined transaction-category description, at most 29 characters as
 *     {@code TRAN-REPORT-CAT-DESC PIC X(29)} declares at {@code CVTRA07Y.cpy} L26, supplied by the
 *     L368 move and the {@code TRANCATG} input at {@code TRANREPT.jcl} L71 to L72
 * @param source the transaction source, at most ten characters as
 *     {@code TRAN-REPORT-SOURCE PIC X(10)} declares at {@code CVTRA07Y.cpy} L28 and as the L369
 *     move carries without a lookup
 * @param amount the exact transaction amount moved at {@code CBTRN03C.cbl} L370 into the
 *     minus-leading mask declared at {@code CVTRA07Y.cpy} L30; {@link Money} carries scale two and
 *     its shared module writes quoted plain decimal text
 */
public record TransactionReportLineResponse(
        @Size(max = 16) String transactionId,
        @Size(max = 11) @Pattern(regexp = "[0-9]+") String accountId,
        @Size(max = 2) String typeCode,
        @Size(max = 15) String typeDescription,
        @Size(max = 4) @Pattern(regexp = "[0-9]+") String categoryCode,
        @Size(max = 29) String categoryDescription,
        @Size(max = 10) String source,
        Money amount) {

    /**
     * Creates a response line from the eight values established by the report projection.
     *
     * <p>Assumptions: every value is stored unchanged. The joins are established by the three moves
     * at {@code app/cbl/CBTRN03C.cbl} L364, L366 and L368, declared-width padding and edit masks
     * belong to {@code com.carddemo.reporting.mapper}, and absence handling belongs to the composing
     * controller. Repeating any of those transformations here would give the package decision
     * register at {@code com.carddemo.reporting.dto} a second implementation site.</p>
     *
     * @param transactionId the transaction identifier to store, corresponding to the L363 move
     * @param accountId the joined account identifier to store, corresponding to the L364 move
     * @param typeCode the transaction-type code to store, corresponding to the L365 move
     * @param typeDescription the joined transaction-type description to store, corresponding to the
     *     L366 move
     * @param categoryCode the digit-preserving transaction-category code to store, corresponding to
     *     the L367 move
     * @param categoryDescription the joined transaction-category description to store, corresponding
     *     to the L368 move
     * @param source the transaction source to store, corresponding to the L369 move
     * @param amount the exact monetary amount to store, corresponding to the L370 move
     */
    public TransactionReportLineResponse(
            String transactionId,
            String accountId,
            String typeCode,
            String typeDescription,
            String categoryCode,
            String categoryDescription,
            String source,
            Money amount) {
        this.transactionId = transactionId;
        this.accountId = accountId;
        this.typeCode = typeCode;
        this.typeDescription = typeDescription;
        this.categoryCode = categoryCode;
        this.categoryDescription = categoryDescription;
        this.source = source;
        this.amount = amount;
    }

    /**
     * Returns the transaction identifier carried by this detail line.
     *
     * @return the transaction identifier, with a declared maximum width of 16 characters
     */
    public String transactionId() {
        return transactionId;
    }

    /**
     * Returns the joined account identifier carried by this detail line.
     *
     * @return the account identifier as a digit string with a declared maximum width of 11
     */
    public String accountId() {
        return accountId;
    }

    /**
     * Returns the transaction-type code carried by this detail line.
     *
     * @return the transaction-type code, with a declared maximum width of two characters
     */
    public String typeCode() {
        return typeCode;
    }

    /**
     * Returns the joined transaction-type description carried by this detail line.
     *
     * @return the transaction-type description, with a declared maximum width of 15 characters
     */
    public String typeDescription() {
        return typeDescription;
    }

    /**
     * Returns the transaction-category join key carried by this detail line.
     *
     * @return the transaction-category code as a digit string of at most four characters, including
     *     any leading zeros
     */
    public String categoryCode() {
        return categoryCode;
    }

    /**
     * Returns the joined transaction-category description carried by this detail line.
     *
     * @return the transaction-category description, with a declared maximum width of 29 characters
     */
    public String categoryDescription() {
        return categoryDescription;
    }

    /**
     * Returns the transaction source carried by this detail line.
     *
     * @return the transaction source, with a declared maximum width of ten characters
     */
    public String source() {
        return source;
    }

    /**
     * Returns the exact transaction amount carried by this detail line.
     *
     * @return the scale-two monetary amount whose JSON wire form is quoted plain decimal text
     */
    public Money amount() {
        return amount;
    }
}
