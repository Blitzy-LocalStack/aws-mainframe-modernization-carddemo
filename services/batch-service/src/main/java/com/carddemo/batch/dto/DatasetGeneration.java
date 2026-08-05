package com.carddemo.batch.dto;

/**
 * The object-storage coordinate of one dataset generation: which family, which business date, and
 * which generation number.
 *
 * <h2>Purpose: the replacement for a relative generation reference</h2>
 *
 * <p><b>Purpose.</b> This type names the place a batch step reads a dataset generation from or
 * writes one to. It is the target-side stand-in for the reference baseline's relative generation
 * reference, the {@code (0)} and {@code (+1)} notation a JCL {@code DSN=} operand appends to a
 * generation-data-group base. The migration plan's transformation rule T6 maps JCL by category and
 * assigns that notation to a new or current object-storage generation prefix; this record is where
 * that assignment becomes a Java value.</p>
 *
 * <p>It renders one string, the key prefix
 * {@code <domain>/<dataset>/dt=YYYY-MM-DD/gen=NNNN/}, and it renders the two variable segments of
 * that prefix separately so a caller can log or assert either one on its own.</p>
 *
 * <h2>Ten generation families, and the count is the part that gets miscopied</h2>
 *
 * <p><b>The reference baseline defines TEN generation-dataset bases, spread across THREE files:
 * {@code app/jcl/DEFGDGB.jcl}, {@code app/jcl/DEFGDGD.jcl} and {@code app/jcl/DALYREJS.jcl}.</b>
 * {@link DatasetFamily} declares one constant for each, and the per-constant documentation there
 * cites the defining line of every one.</p>
 *
 * <p>Assumptions: six is the wrong number and it is the number a careful reader arrives at, which
 * is why the correct one is stated in bold above rather than left to be counted. Six of the ten are
 * defined in a single {@code IDCAMS} step in {@code app/jcl/DEFGDGB.jcl}, at lines 25, 31, 37, 43,
 * 49 and 55 -- a file headed {@code DEFINE GDG BASES NEEDED BY CARDDEMO PROJECT} at line 19, which
 * reads as though it were the complete inventory. It is not. Three more are defined in
 * {@code app/jcl/DEFGDGD.jcl}, at lines 28, 51 and 74, and the tenth in
 * {@code app/jcl/DALYREJS.jcl} at line 25, a job named for the reject dataset itself and giving no
 * hint in its name that it defines a generation base. Modelling six would silently drop four
 * families: the four affected steps would still write their objects, into a prefix with no
 * retention rule attached, so nothing would fail and generations would accumulate without bound.
 * The reject stream is one of the four, and it is the audit trail of every transaction the posting
 * run declined.</p>
 *
 * <p>Assumptions: the sibling package charter at {@code com.carddemo.batch.dto} describes this type
 * by citing {@code app/jcl/DEFGDGB.jcl} and its six bases. That statement is accurate about that
 * one file and is not a count of the families; this type is the authority on the family set, and it
 * declares ten. The two are read together, not as a disagreement.</p>
 *
 * <p>Assumptions: an exhaustive search for the defining verb finds ELEVEN statements, not ten, and
 * the discrepancy is recorded rather than smoothed over.
 * {@code grep -rn "DEFINE GENERATIONDATAGROUP" app/jcl} matches four files: the three above plus
 * {@code app/jcl/REPTFILE.jcl}, whose line 26 re-declares the base already declared at
 * {@code app/jcl/DEFGDGB.jcl:37} -- {@code AWS.M2.CARDDEMO.TRANREPT} -- but with {@code LIMIT(10)}
 * at line 27 and no {@code SCRATCH}. Eleven statements therefore cover ten DISTINCT base names,
 * because that one base carries two competing definitions. The distinct names are what a prefix
 * family corresponds to, so the family count is ten; the authored
 * {@code infra/modules/s3-datasets} module records the same conflict against its own inventory and
 * likewise provisions ten.</p>
 *
 * <h2>What this type deliberately does not carry</h2>
 *
 * <p>Trade-offs: the rendering carries no bucket name, no environment name and no URI scheme. Those
 * are supplied by configuration, and the module that owns them is
 * {@code infra/modules/s3-datasets}, which composes the bucket from its own inputs and publishes
 * the family prefixes as outputs. Leaving them out means one coordinate value is valid unchanged in
 * every environment, and that a test can assert a rendered prefix without standing up any
 * configuration at all. The cost is that a caller needing a fully-qualified location has to join
 * this prefix to a bucket it obtained elsewhere, so the join exists somewhere in every writer
 * rather than once here.</p>
 *
 * <p>Assumptions: no type from a cloud provider software development kit appears in this file, and
 * the omission is the point rather than an oversight. This record names a location; it does not open
 * one, list one or write to one. The charter of this package fixes that import discipline, and the
 * consequence worth stating is testability: every member below can be exercised by constructing a
 * value and comparing a string, with no client, no credentials and no emulator.</p>
 *
 * <p>Assumptions: this file declares no import. Everything it needs is a string literal, an
 * {@code int}, the enumeration facilities of the language and {@link BusinessDate}, which is
 * declared in this same package and therefore needs none.</p>
 *
 * <h2>Value equality is the contract</h2>
 *
 * <p>Assumptions: the generated {@code equals}, {@code hashCode} and {@code toString} are left
 * exactly as the record produces them. Two coordinates naming the same family, the same business
 * date and the same generation number ARE the same coordinate, so component-wise equality is
 * already the equality this type wants and there is nothing to add.
 * {@link BusinessDate} makes the same choice for the same reason, so equality composes: two
 * coordinates are equal when their tokens are byte-identical, and not when two differently-spelled
 * tokens happen to derive the same partition date. That asymmetry is deliberate and is the subject
 * of the section below.</p>
 *
 * <h2>Baseline lineage: provenance only</h2>
 *
 * <p>Every citation in this file is provenance. Nothing under {@code app/**} is read at run time,
 * and nothing under it is altered by this migration -- the reference implementation is the
 * behavioural oracle and stays byte-identical. Where migrated behaviour differs from the reference,
 * the reference does one thing, the Java does another, and the divergence is registered in
 * {@code docs/architecture/cobol-to-service-traceability.md}. No claim is made anywhere in this file
 * that the reference itself was altered, because it was not. Line numbers refer to the source as
 * committed, and in a COBOL or JCL line columns 73 to 80 carry a sequence field that is not part of
 * the statement.</p>
 *
 * @param family the generation-dataset family this coordinate addresses, one of the ten the
 *     reference baseline defines; it supplies the {@code <domain>/<dataset>} portion of the
 *     rendered prefix and must not be {@code null}
 * @param businessDate the opaque ten-character business-date token this generation belongs to,
 *     carried as the package-local {@link BusinessDate} rather than as a calendar type so that its
 *     bytes stay untouched; it is READ to derive the {@code dt=} segment and is never altered, and
 *     it must not be {@code null}
 * @param generationNumber the generation number within that family and that date, from
 *     {@value #MINIMUM_GENERATION_NUMBER} to {@value #MAXIMUM_GENERATION_NUMBER} inclusive; it is
 *     rendered zero-padded to {@value #GENERATION_DIGITS} digits in the {@code gen=} segment, and
 *     the upper bound is that width rather than an arbitrary ceiling
 */
public record DatasetGeneration(
        DatasetFamily family,
        BusinessDate businessDate,
        int generationNumber) {

    /**
     * The number of generations retained per family, five.
     *
     * <p>Assumptions: five is the reference baseline's own retention limit, declared as
     * {@code LIMIT(5)} on every one of the ten defining statements -- {@code app/jcl/DEFGDGB.jcl:26}
     * for the first of them, and lines 32, 38, 44, 50 and 56 of that same file, then
     * {@code app/jcl/DEFGDGD.jcl:29}, {@code :52} and {@code :75}, then
     * {@code app/jcl/DALYREJS.jcl:26}. Each of the ten pairs that limit with {@code SCRATCH} on the
     * following line, which is what makes an aged-out generation deleted rather than merely
     * uncatalogued. The target analogue is bucket versioning with a lifecycle rule retaining five
     * noncurrent object versions, which {@code infra/modules/s3-datasets} configures per family.</p>
     *
     * <p>Assumptions: the one exception in the baseline is recorded on this type rather than folded
     * in silently. {@code app/jcl/REPTFILE.jcl:27} sets {@code LIMIT(10)} on its duplicate
     * declaration of the report base and omits {@code SCRATCH}. The three files treated as
     * authoritative declare all ten at {@code LIMIT(5)}, so five is applied uniformly here, and the
     * infrastructure module keeps a per-family override available for the day that conflict is
     * resolved the other way.</p>
     */
    // WHY : Assumptions: the number is published here, from Java, even though the mechanism that
    //       enforces it is a lifecycle rule in Terraform. It is one fact with two homes, and a
    //       reader auditing retention should not have to open HCL to learn what this module
    //       believes the limit is. Naming it in both places means a change to one of them leaves a
    //       visible disagreement instead of a silent drift, which is the failure a number that
    //       lived only in the infrastructure could not surface.
    public static final int RETAINED_GENERATION_COUNT = 5;

    /**
     * The fixed width of the rendered generation number, four digits.
     *
     * <p>Assumptions: the width comes from the target prefix convention
     * {@code <domain>/<dataset>/dt=YYYY-MM-DD/gen=NNNN/}, in which {@code NNNN} is four digits.
     * The sibling renderer that stages generations, {@code generation_prefix} at
     * {@code data-migration/src/carddemo_migration/config.py:1296-1385}, formats to the same four
     * from its own {@code _GENERATION_DIGITS} at line 428, and the loader that reads prefixes back
     * matches them with the pattern {@code dt=(\d{4}-\d{2}-\d{2})/gen=(\d{4})/} at
     * {@code data-migration/src/carddemo_migration/loaders/s3_stage.py:27}. The width is therefore
     * asserted on the read side as well as chosen on the write side, so it is a contract between
     * two authored components rather than a formatting preference of this one.</p>
     */
    public static final int GENERATION_DIGITS = 4;

    /**
     * The lowest accepted generation number, zero.
     *
     * <p>Assumptions: zero is admitted because the sibling renderer admits it.
     * {@code data-migration/src/carddemo_migration/config.py:1333} documents the accepted range as
     * "between 0 and 9999 inclusive" and line 1371 enforces exactly that, so a
     * {@code gen=0000} prefix is a prefix that component can create and that
     * {@code loaders/s3_stage.py:27} can parse back.</p>
     *
     * <p>Trade-offs: a minimum of one was the alternative, and it is the more intuitive reading --
     * the reference baseline's absolute generation names begin at the first generation, and the
     * first generation of a family renders here as {@code gen=0001}. It was rejected because the
     * two components write into one bucket: a Java bound of one would make a prefix the Python
     * stager can legitimately produce unrepresentable in Java, so a Java verification pass could
     * not name a generation that already existed. The cost accepted is that this type admits a
     * generation number the mainframe notion of a generation has no counterpart for.</p>
     */
    public static final int MINIMUM_GENERATION_NUMBER = 0;

    /**
     * The highest accepted generation number, nine thousand nine hundred and ninety-nine.
     *
     * <p>Assumptions: the ceiling is derived from {@value #GENERATION_DIGITS} rather than chosen
     * independently, so the two cannot disagree: four digits hold up to 9999 and no more. The bound
     * has to be enforced rather than trusted because formatting does not truncate -- a value of
     * 10000 would render as {@code gen=10000}, five digits, and would then sort ahead of
     * {@code gen=9999} in the lexicographic ordering an object listing offers, which is precisely
     * the ordering the padding exists to protect. The sibling renderer records the same reasoning
     * at {@code data-migration/src/carddemo_migration/config.py:422-429}.</p>
     */
    public static final int MAXIMUM_GENERATION_NUMBER = 9999;

    /**
     * The literal that opens the business-date partition segment, {@code dt=}.
     *
     * <p>Assumptions: the spelling is fixed by the target prefix convention and is matched
     * literally by {@code loaders/s3_stage.py:27}, so it is a shared constant in effect even though
     * each component declares its own.</p>
     */
    private static final String DATE_PARTITION_MARKER = "dt=";

    /**
     * The literal that opens the generation segment, {@code gen=}.
     *
     * <p>Assumptions: as with the date marker, the spelling is fixed by the prefix convention and
     * is matched literally on the read side.</p>
     */
    private static final String GENERATION_MARKER = "gen=";

    /**
     * The single character that separates one key segment from the next, a forward slash.
     *
     * <p>Assumptions: the separator is what gives the staged layout its shape, so it is named once
     * here and concatenated rather than written as a literal at each of the four places a segment
     * boundary occurs.</p>
     */
    private static final String SEGMENT_SEPARATOR = "/";

    /**
     * The length of a date rendered in the separated layout {@code YYYY-MM-DD}, ten characters.
     */
    private static final int SEPARATED_DATE_LENGTH = 10;

    /**
     * The length of a date rendered in the compact layout {@code YYYYMMDD}, eight characters.
     */
    private static final int COMPACT_DATE_LENGTH = 8;

    /**
     * The index at which a separated date carries the hyphen between year and month, four.
     */
    private static final int YEAR_MONTH_HYPHEN_INDEX = 4;

    /**
     * The index at which a separated date carries the hyphen between month and day, seven.
     */
    private static final int MONTH_DAY_HYPHEN_INDEX = 7;

    /**
     * The index one past the last digit of the year in either layout, four.
     */
    private static final int YEAR_END_INDEX = 4;

    /**
     * The index one past the last digit of the month in the compact layout, six.
     */
    private static final int COMPACT_MONTH_END_INDEX = 6;

    /**
     * Rejects a coordinate that cannot address a generation, and stores the rest as received.
     *
     * <p>Three things are checked and nothing else is: that a family was named, that a business date
     * was named, and that the generation number fits the rendered width. The business-date token
     * itself is neither inspected nor adjusted here -- {@link BusinessDate} has already asserted its
     * width, and the shape of its contents is a question only {@link #partitionDate()} asks, and
     * asks without changing anything.</p>
     *
     * <p>Successful construction yields this record instance and no separate return value.</p>
     *
     * @param family the generation-dataset family; must not be {@code null}
     * @param businessDate the business-date token this generation belongs to; must not be
     *     {@code null}
     * @param generationNumber the generation number; must fall from
     *     {@value #MINIMUM_GENERATION_NUMBER} to {@value #MAXIMUM_GENERATION_NUMBER} inclusive
     * @throws IllegalArgumentException if {@code family} is {@code null}, if {@code businessDate} is
     *     {@code null}, or if {@code generationNumber} falls outside
     *     {@value #MINIMUM_GENERATION_NUMBER} to {@value #MAXIMUM_GENERATION_NUMBER} inclusive
     */
    public DatasetGeneration {
        // WHY : Assumptions: one exception type covers all three rejections because a caller can do
        //       nothing different about them, and it is the type BusinessDate already raises for a
        //       mis-sized token. A coordinate rejected here therefore reads the same in a container
        //       log whether the fault was an absent family or an out-of-range generation.
        if (family == null) {
            throw new IllegalArgumentException("dataset generation requires a family and none was given");
        }

        if (businessDate == null) {
            throw new IllegalArgumentException("dataset generation requires a business date and none was given");
        }

        // WHY : Assumptions: the range is enforced rather than left to the formatter, because the
        //       formatter does not truncate. A number wider than four digits still renders, just
        //       wider, and a wider gen= segment sorts ahead of every padded one in the
        //       lexicographic ordering an object listing gives -- so an unchecked value produces a
        //       prefix that looks valid and lists in the wrong place, which is the failure that
        //       shows up as a wrong generation being read rather than as an error.
        if (generationNumber < MINIMUM_GENERATION_NUMBER || generationNumber > MAXIMUM_GENERATION_NUMBER) {
            throw new IllegalArgumentException("dataset generation number " + generationNumber
                    + " is outside the accepted range " + MINIMUM_GENERATION_NUMBER + " to "
                    + MAXIMUM_GENERATION_NUMBER + " inclusive, which is the range "
                    + GENERATION_DIGITS + " rendered digits can hold");
        }
    }

    /**
     * Returns the generation-dataset family this coordinate addresses.
     *
     * <p>This accessor accepts no parameters.</p>
     *
     * @return the family, one of the ten {@link DatasetFamily} declares; never {@code null}
     */
    public DatasetFamily family() {
        // WHY : Assumptions: the accessor is written out rather than left implicit only so that this
        //       contract has somewhere to live. An implicit record accessor carries no Javadoc block
        //       and therefore no return description, which is the one thing a reader needs from it.
        //       BusinessDate in this package writes its own accessor out for the same reason.
        return family;
    }

    /**
     * Returns the business-date token this generation belongs to, unaltered.
     *
     * <p>The value returned is the same {@link BusinessDate} the constructor received, holding the
     * same token bytes. Deriving the {@code dt=} segment does not replace it and does not modify
     * it.</p>
     *
     * <p>This accessor accepts no parameters.</p>
     *
     * @return the business date; never {@code null}, and carrying the token exactly as supplied
     */
    public BusinessDate businessDate() {
        return businessDate;
    }

    /**
     * Returns the generation number within this family and this business date.
     *
     * <p>This accessor accepts no parameters.</p>
     *
     * @return the generation number, from {@value #MINIMUM_GENERATION_NUMBER} to
     *     {@value #MAXIMUM_GENERATION_NUMBER} inclusive
     */
    public int generationNumber() {
        return generationNumber;
    }

    /**
     * Derives the partition date the {@code dt=} segment carries, in the separated layout
     * {@code YYYY-MM-DD}.
     *
     * <p>The derivation READS {@link BusinessDate#token()} and builds a new string from it. It does
     * not alter, replace, re-store, trim or re-render the token, and {@link #businessDate()} returns
     * the same bytes after this method has been called as before.</p>
     *
     * <p>Two token layouts occur in practice, both ten characters, and each takes its own branch:</p>
     *
     * <ul>
     *   <li>the separated layout, hyphens at index {@value #YEAR_MONTH_HYPHEN_INDEX} and
     *       {@value #MONTH_DAY_HYPHEN_INDEX} and digits elsewhere. Its first
     *       {@value #SEPARATED_DATE_LENGTH} characters are already the required shape, so they are
     *       taken as they stand. Token {@code 2022-07-18} yields {@code 2022-07-18}.</li>
     *   <li>the compact layout, {@value #COMPACT_DATE_LENGTH} leading digits followed by two further
     *       characters. Its leading {@value #COMPACT_DATE_LENGTH} digits are read as
     *       {@code YYYYMMDD} and re-rendered with hyphens inserted. Token {@code 2022071800}, which
     *       {@code app/jcl/INTCALC.jcl:22} injects as {@code PARM='2022071800'}, yields
     *       {@code 2022-07-18}.</li>
     * </ul>
     *
     * <p>Both observed layouts therefore produce one partition value for one business day, which is
     * the whole reason the derivation exists.</p>
     *
     * <p><b>Why the partition value may be derived when the token may not be touched.</b></p>
     *
     * <p>Assumptions: the token is parity-bearing and the partition value is not, and these are two
     * different obligations resting on one input. {@code app/cbl/CBACT04C.cbl:473-480} builds the
     * identifier of every generated interest transaction by concatenation and by nothing else: line
     * 474 adds one to {@code WS-TRANID-SUFFIX PIC 9(06)}, declared at line 173, and lines 476 to 480
     * string {@code PARM-DATE} and that suffix into {@code TRAN-ID PIC X(16)}, declared at
     * {@code app/cpy/CVTRA05Y.cpy:5}. Ten token characters plus six suffix digits fill sixteen
     * exactly, with no slack, so every byte of the token is observable output and is compared by the
     * parity oracle. A storage prefix is compared by nothing: the reference baseline addressed
     * generations by absolute dataset name, never by a date partition, so no golden master holds an
     * expectation about a {@code dt=} value at all.</p>
     *
     * <p>Trade-offs: the compromise accepted is that one input yields two differently-shaped
     * outputs -- the token as supplied, and a normalised date derived from it -- and a reader
     * meeting both could reasonably assume one had been converted into the other. That is exactly
     * why this is a named method carrying this explanation rather than an inline expression inside a
     * writer. The alternative of normalising once, at construction, and holding both forms was
     * rejected: it puts a normalised date in a field beside the token, and the next author to need
     * "the date" picks whichever field autocompletes first, at which point a normalised value can
     * reach an identifier and the parity failure is a plausible-looking record rather than an
     * error.</p>
     *
     * <p>Alternatives Considered: parsing the token as a calendar date and asking the result to
     * render itself, which is the obvious implementation and is already available on the token type
     * as {@link BusinessDate#parseIsoDateForRangeComparison()}. Rejected, and the rejection is
     * concrete rather than cautious: that method is documented to throw for a compact token, and the
     * compact token is the reference baseline's own production parameter at
     * {@code app/jcl/INTCALC.jcl:22}. An implementation built on it would fail on the one input the
     * baseline actually supplies, and would fail only when a real business date reached it. Reading
     * the layout and rendering from the parts accepts both.</p>
     *
     * <p>This operation accepts no parameters.</p>
     *
     * @return the partition date in the separated layout {@code YYYY-MM-DD}, exactly
     *     {@value #SEPARATED_DATE_LENGTH} characters; never {@code null}
     * @throws IllegalStateException if the token matches neither layout -- that is, if it is neither
     *     hyphen-separated at the two expected indices with digits elsewhere, nor led by
     *     {@value #COMPACT_DATE_LENGTH} digits. The message quotes the token so a container log
     *     identifies the offending value without access to this source
     */
    public String partitionDate() {
        String token = businessDate.token();

        // WHY : Assumptions: the value built below is used for the dt= prefix and for nothing else,
        //       and the token is read rather than rewritten. The token's own bytes are concatenated
        //       into TRAN-ID PIC X(16) at app/cbl/CBACT04C.cbl:476-480, so altering them would
        //       change a stored identifier; a prefix is compared by no golden master, so
        //       normalising the prefix changes nothing that is checked.
        if (isSeparatedLayout(token)) {
            return token.substring(0, SEPARATED_DATE_LENGTH);
        }

        if (isCompactLayout(token)) {
            // WHY : Assumptions: the hyphens are inserted by building a new string from three
            //       slices rather than by editing the token, so no method that mutates or replaces
            //       the token is reachable from here. The slices are fixed positions because the
            //       compact layout is positional: four year digits, two month, two day.
            return token.substring(0, YEAR_END_INDEX)
                    + "-" + token.substring(YEAR_END_INDEX, COMPACT_MONTH_END_INDEX)
                    + "-" + token.substring(COMPACT_MONTH_END_INDEX, COMPACT_DATE_LENGTH);
        }

        // WHY : Alternatives Considered: returning the token unchanged, or substituting a placeholder
        //       date, were both evaluated and both rejected. Either would stage a generation under a
        //       prefix that the loader's own dt=(\d{4}-\d{2}-\d{2})/gen=(\d{4})/ pattern at
        //       data-migration/src/carddemo_migration/loaders/s3_stage.py:27 does not match, so the
        //       objects would be written and would then be invisible to every reader that walks
        //       generations by convention. Raising stops the step at the coordinate that cannot be
        //       rendered instead of producing an unreachable one.
        throw new IllegalStateException("business-date token '" + token
                + "' matches neither the separated layout YYYY-MM-DD nor the compact layout"
                + " YYYYMMDD followed by two characters, so no dt= partition value can be derived"
                + " from it");
    }

    /**
     * Renders the business-date partition segment of the key prefix, {@code dt=} followed by the
     * derived date.
     *
     * <p>The segment carries no separator of its own; {@link #keyPrefix()} joins it to its
     * neighbours.</p>
     *
     * <p>This operation accepts no parameters.</p>
     *
     * @return the segment, for example {@code dt=2022-07-18}; never {@code null}
     * @throws IllegalStateException if the business-date token matches neither layout
     *     {@link #partitionDate()} accepts
     */
    public String datePartitionSegment() {
        return DATE_PARTITION_MARKER + partitionDate();
    }

    /**
     * Renders the generation segment of the key prefix, {@code gen=} followed by the generation
     * number zero-padded to {@value #GENERATION_DIGITS} digits.
     *
     * <p>Generation 1 renders {@code gen=0001}, generation 42 renders {@code gen=0042} and
     * generation 9999 renders {@code gen=9999}.</p>
     *
     * <p>This operation accepts no parameters.</p>
     *
     * @return the segment, for example {@code gen=0001}; never {@code null}, and always
     *     {@value #GENERATION_DIGITS} digits wide after the marker
     */
    public String generationSegment() {
        // WHY : Assumptions: the padding is functional, not cosmetic. Object keys are ordered
        //       lexicographically, which is the only ordering a listing offers, so an unpadded
        //       gen=10 sorts BEFORE gen=2 and a caller taking the last key as the current
        //       generation reads the wrong one. Four fixed digits make string order agree with
        //       numeric order across the whole accepted range, and the constructor's upper bound is
        //       what keeps a fifth digit from ever appearing and breaking that agreement again.
        String digits = Integer.toString(generationNumber);

        // WHY : Alternatives Considered: formatting with a width specifier, which is the shorter
        //       expression and was rejected. The single-argument formatting call resolves its digit
        //       characters from the default formatting locale, so under a locale whose numbering
        //       system is not latin it emits that system's digits -- a key that is no longer the
        //       ASCII form every other reader of this bucket compares, sorts and pattern-matches
        //       against, produced by a process that merely started with different locale settings.
        //       Integer.toString is specified to emit ASCII digits regardless of locale, so the
        //       padding is assembled around it instead.
        StringBuilder padded = new StringBuilder(GENERATION_DIGITS);
        for (int position = digits.length(); position < GENERATION_DIGITS; position++) {
            padded.append('0');
        }
        padded.append(digits);
        return GENERATION_MARKER + padded;
    }

    /**
     * Renders the complete object-storage key prefix this coordinate addresses.
     *
     * <p>The shape is {@code <domain>/<dataset>/dt=YYYY-MM-DD/gen=NNNN/}, and the value ENDS WITH a
     * forward slash. It is a prefix that objects are placed beneath, not the key of an object.</p>
     *
     * <p>Trade-offs: the trailing separator is included, and the consequence of the other choice is
     * concrete rather than stylistic. Without it, {@code gen=0001} is a leading substring of
     * {@code gen=00010}, so a listing filtered by the shorter string would return the objects of
     * both generations and a caller would read one generation's contents as another's. Including it
     * costs the caller one step when composing an object key, since the separator is already present
     * and must not be added again. The sibling renderer at
     * {@code data-migration/src/carddemo_migration/config.py:1312-1314} states the same reasoning
     * and returns the same shape, and {@code loaders/s3_stage.py:27} anchors its pattern on that
     * trailing slash, so a Java rendering without it would not be recognised by the component that
     * reads generations back.</p>
     *
     * <p>Trade-offs: no bucket name, environment name or URI scheme appears in the returned value.
     * The same coordinate is therefore valid unchanged in every environment, and a caller needing a
     * fully-qualified location joins this prefix to a bucket obtained from configuration. The cost
     * is that the join exists in each writer rather than once here; the benefit is that a rendered
     * prefix can be asserted in a unit test with no configuration, no credentials and no
     * emulator.</p>
     *
     * <p>This operation accepts no parameters.</p>
     *
     * @return the key prefix, for example {@code ledger/dalyrejs/dt=2022-07-18/gen=0001/}; never
     *     {@code null}, and always terminated by a forward slash
     * @throws IllegalStateException if the business-date token matches neither layout
     *     {@link #partitionDate()} accepts
     */
    public String keyPrefix() {
        // WHY : Assumptions: the family portion already ends with a separator, matching the value
        //       infra/modules/s3-datasets publishes for that family, so only the two boundaries
        //       this method introduces are written here. Adding a separator after the family
        //       portion as well would produce an empty path segment, and an empty segment is a
        //       distinct key location from the intended one rather than a harmless duplicate.
        return family.pathSegment()
                + datePartitionSegment() + SEGMENT_SEPARATOR
                + generationSegment() + SEGMENT_SEPARATOR;
    }

    /**
     * Reports whether a token is in the separated layout {@code YYYY-MM-DD}.
     *
     * <p>The test is positional: a hyphen at index {@value #YEAR_MONTH_HYPHEN_INDEX}, a hyphen at
     * index {@value #MONTH_DAY_HYPHEN_INDEX}, and digits at the eight remaining positions of the
     * first {@value #SEPARATED_DATE_LENGTH} characters. Whatever follows those characters is not
     * examined.</p>
     *
     * @param candidate the token to classify; may be shorter than a date, in which case the answer
     *     is {@code false} rather than an error
     * @return {@code true} when the candidate opens with a separated date, {@code false} otherwise
     */
    private static boolean isSeparatedLayout(String candidate) {
        // WHY : Assumptions: the length test comes first and the character tests rely on it
        //       short-circuiting. BusinessDate already guarantees exactly ten characters, so this
        //       test cannot fail for a constructed coordinate; it is written anyway so that the
        //       indexed reads below are safe by construction rather than safe by a guarantee made
        //       in another file, which is the kind of dependency that survives a refactor only by
        //       luck.
        return candidate.length() >= SEPARATED_DATE_LENGTH
                && candidate.charAt(YEAR_MONTH_HYPHEN_INDEX) == '-'
                && candidate.charAt(MONTH_DAY_HYPHEN_INDEX) == '-'
                && isAllDigits(candidate, 0, YEAR_END_INDEX)
                && isAllDigits(candidate, YEAR_MONTH_HYPHEN_INDEX + 1, MONTH_DAY_HYPHEN_INDEX)
                && isAllDigits(candidate, MONTH_DAY_HYPHEN_INDEX + 1, SEPARATED_DATE_LENGTH);
    }

    /**
     * Reports whether a token opens with the compact layout {@code YYYYMMDD}.
     *
     * <p>Only the leading {@value #COMPACT_DATE_LENGTH} characters are examined, and they must all
     * be digits. The two characters the reference baseline's parameter carries after them are not
     * examined, because nothing in the baseline interprets them.</p>
     *
     * @param candidate the token to classify; may be shorter than a compact date, in which case the
     *     answer is {@code false} rather than an error
     * @return {@code true} when the candidate opens with eight digits, {@code false} otherwise
     */
    private static boolean isCompactLayout(String candidate) {
        return candidate.length() >= COMPACT_DATE_LENGTH
                && isAllDigits(candidate, 0, COMPACT_DATE_LENGTH);
    }

    /**
     * Reports whether every character in a half-open range of a string is an ASCII digit.
     *
     * @param candidate the string to inspect
     * @param fromIndex the first index to inspect, inclusive
     * @param toIndex the index to stop at, exclusive
     * @return {@code true} when every character in the range is an ASCII digit, {@code false} as
     *     soon as one is not
     */
    private static boolean isAllDigits(String candidate, int fromIndex, int toIndex) {
        for (int index = fromIndex; index < toIndex; index++) {
            // WHY : Alternatives Considered: classifying with Character.isDigit, which is the
            //       idiomatic call and was rejected because it accepts the decimal digits of every
            //       script, not only ASCII. A token carrying such a digit would be classified as a
            //       date here and would render a dt= value whose bytes are not the ASCII form the
            //       rest of this bucket's readers compare, sort and scope IAM resource patterns
            //       against. An explicit range refuses it at the coordinate instead, which is the
            //       only place the refusal is still cheap.
            char character = candidate.charAt(index);
            if (character < '0' || character > '9') {
                return false;
            }
        }
        return true;
    }

    /**
     * The ten generation-dataset families the reference baseline defines, and no eleventh.
     *
     * <h2>Ten, drawn from three files</h2>
     *
     * <p>Six constants come from {@code app/jcl/DEFGDGB.jcl}, three from
     * {@code app/jcl/DEFGDGD.jcl} and one from {@code app/jcl/DALYREJS.jcl}. Every constant cites
     * its own defining line, so the set can be audited against the baseline by reading this
     * enumeration top to bottom; the constants are declared in that same file-and-line order for
     * exactly that reason.</p>
     *
     * <p>Assumptions: each constant carries TWO strings because they answer two different
     * questions, and neither is derivable from the other. The mainframe base name is provenance --
     * it says which generation data group this family replaces, and it is reproduced character for
     * character. The path segment is the target location, and it is deliberately not a
     * transliteration of the base name: it is lower case, it uses hyphens where the base name uses
     * dots, and family eight drops the {@code .PS.} qualifier of
     * {@code AWS.M2.CARDDEMO.TRANCATG.PS.BKUP} entirely. No mechanical transformation produces all
     * ten segments from all ten base names, so both are declared.</p>
     *
     * <h2>Where the domain grouping comes from</h2>
     *
     * <p>Alternatives Considered: inventing a grouping here, or flattening the families into a
     * single prefix with no domain level at all. Both were rejected, and the first was rejected for
     * a reason stronger than taste: the grouping is ALREADY published. The authored
     * {@code infra/modules/s3-datasets} module declares it in the default of its
     * {@code dataset_families} variable, keyed exactly as the segments below are spelled, and its
     * {@code main.tf} composes each family prefix as the domain followed by the key followed by a
     * separator. A second, independently-invented grouping in Java would put the same objects in two
     * different places depending on which component wrote them, and the divergence would surface as
     * a batch step that wrote successfully into a prefix carrying no lifecycle rule. Flattening was
     * rejected because the domain level is what lets an access policy and a lifecycle rule be scoped
     * to one bounded context's datasets rather than to all of them, and because a flat namespace
     * gives {@code tranrept} and {@code trantype-bkup} no visible relationship to the contexts that
     * own them. The grouping is therefore ADOPTED here, not chosen.</p>
     *
     * <p>Assumptions: the domains follow the owning bounded context's database schema rather than
     * being coined for storage. The transaction context owns the {@code ledger} schema, the
     * reference context owns {@code reference}, and the reporting context owns no tables but
     * produces the 133-column report, which is why {@code reporting} appears once. The split is six
     * ledger, three reference and one reporting, written out so the total can be verified by
     * addition rather than trusted.</p>
     *
     * <h2>Declaration order, and why the ordinal is not a contract</h2>
     *
     * <p>Trade-offs: declaring in baseline definition order makes this file checkable against the
     * three defining JCL members line by line, and the cost of encoding an order in a declaration is
     * that the order becomes reachable as a number. <b>The ordinal of a constant here is not part of
     * any contract and must never be persisted, written into an object key or recorded in the
     * durable step ledger.</b> A family inserted to match a future baseline change would silently
     * renumber every ordinal already written down, whereas a path segment changed the same way is
     * caught by the prefix a reader cannot find. The sibling {@link BatchJobName} states the same
     * prohibition for the same reason.</p>
     *
     * <h2>Parameters, return values and exceptions at type level: declared inapplicable</h2>
     *
     * <p>An enumeration declaration accepts no parameter, yields no value and raises nothing, so
     * this block carries no parameter, return or exception at-clause, and no authorship,
     * availability or revision at-clause either. The inapplicability is stated rather than left
     * silent because the project's single user-specified rule, Explainability, names at its line 39
     * a docstring that omits parameters, return values or purpose among its forbidden patterns, and
     * a reader has to be able to tell a declared inapplicability from an oversight. The elements
     * that do apply to a type are discharged above; the remainder are discharged on each member
     * below.</p>
     */
    public enum DatasetFamily {

        /**
         * Transaction master backup generations, replacing
         * {@code AWS.M2.CARDDEMO.TRANSACT.BKUP}.
         *
         * <p>Defined at {@code app/jcl/DEFGDGB.jcl:25}, with {@code LIMIT(5)} at line 26 and
         * {@code SCRATCH} at line 27. Written as a new generation by {@code app/jcl/TRANBKP.jcl:33},
         * which unloads the transaction master at {@code LRECL=350}, and read back as the current
         * generation by {@code app/jcl/COMBTRAN.jcl:24}, where it is the first input to the merge
         * sort. It is also written as a new generation by {@code app/jcl/TRANREPT.jcl:33} and read
         * back at line 39 within that same job.</p>
         */
        TRANSACT_BKUP("AWS.M2.CARDDEMO.TRANSACT.BKUP", "ledger", "transact-bkup"),

        /**
         * Daily transaction generations staged for posting, replacing
         * {@code AWS.M2.CARDDEMO.TRANSACT.DALY}.
         *
         * <p>Defined at {@code app/jcl/DEFGDGB.jcl:31}, with {@code LIMIT(5)} at line 32 and
         * {@code SCRATCH} at line 33. Written as a new generation by
         * {@code app/jcl/TRANREPT.jcl:55}, the card-ordered extract the sort produces, and read back
         * at line 66 as the report program's input.</p>
         */
        TRANSACT_DALY("AWS.M2.CARDDEMO.TRANSACT.DALY", "ledger", "transact-daly"),

        /**
         * Transaction report generations, the 133-column fixed-width output, replacing
         * {@code AWS.M2.CARDDEMO.TRANREPT}.
         *
         * <p>Defined at {@code app/jcl/DEFGDGB.jcl:37}, with {@code LIMIT(5)} at line 38 and
         * {@code SCRATCH} at line 39. Written as a new generation by {@code app/jcl/TRANREPT.jcl:80}
         * at the {@code LRECL=133} declared at line 78.</p>
         *
         * <p>Assumptions: this is the one base the baseline declares twice.
         * {@code app/jcl/REPTFILE.jcl:26} re-declares it with {@code LIMIT(10)} at line 27 and no
         * {@code SCRATCH}, which is why an exhaustive search finds eleven declaring statements over
         * ten distinct names. The {@code LIMIT(5)} declaration is the one this migration applies,
         * matching the authored infrastructure module; the conflict is recorded rather than
         * resolved, because the baseline is not self-consistent about it and this migration does not
         * alter the baseline to make it so.</p>
         */
        TRANREPT("AWS.M2.CARDDEMO.TRANREPT", "reporting", "tranrept"),

        /**
         * Transaction-category-balance backup generations, replacing
         * {@code AWS.M2.CARDDEMO.TCATBALF.BKUP}.
         *
         * <p>Defined at {@code app/jcl/DEFGDGB.jcl:43}, with {@code LIMIT(5)} at line 44 and
         * {@code SCRATCH} at line 45. Written as a new generation by
         * {@code app/jcl/PRTCATBL.jcl:39}, which unloads the category-balance master at
         * {@code LRECL=50}, and read back at line 45 as the input to that job's sort.</p>
         */
        TCATBALF_BKUP("AWS.M2.CARDDEMO.TCATBALF.BKUP", "ledger", "tcatbalf-bkup"),

        /**
         * System-generated transaction generations, the interest transactions the accrual run emits,
         * replacing {@code AWS.M2.CARDDEMO.SYSTRAN}.
         *
         * <p>Defined at {@code app/jcl/DEFGDGB.jcl:49}, with {@code LIMIT(5)} at line 50 and
         * {@code SCRATCH} at line 51. Written as a new generation by {@code app/jcl/INTCALC.jcl:41}
         * at the {@code LRECL=350} declared at line 39, by the interest program the same job invokes
         * at line 22, and read back as the current generation by {@code app/jcl/COMBTRAN.jcl:26},
         * where it is the concatenated second input to the merge sort.</p>
         *
         * <p>Assumptions: this family is the one whose {@code dt=} partition value is most exposed to
         * the token-layout question, because the job that writes it is the job that supplies the
         * compact ten-character parameter: {@code app/jcl/INTCALC.jcl:22} reads
         * {@code PARM='2022071800'}. {@link DatasetGeneration#partitionDate()} is what reconciles
         * that with the separated partition layout.</p>
         */
        SYSTRAN("AWS.M2.CARDDEMO.SYSTRAN", "ledger", "systran"),

        /**
         * Combined transaction generations, the merge of the backup and the system transactions,
         * replacing {@code AWS.M2.CARDDEMO.TRANSACT.COMBINED}.
         *
         * <p>Defined at {@code app/jcl/DEFGDGB.jcl:55}, with {@code LIMIT(5)} at line 56 and
         * {@code SCRATCH} at line 57. Written as a new generation by
         * {@code app/jcl/COMBTRAN.jcl:37}, the output of the sort that merges the two current
         * generations read at lines 24 and 26, and read back at line 44 by the reload step in that
         * same job.</p>
         */
        TRANSACT_COMBINED("AWS.M2.CARDDEMO.TRANSACT.COMBINED", "ledger", "transact-combined"),

        /**
         * Transaction-type reference backup generations, replacing
         * {@code AWS.M2.CARDDEMO.TRANTYPE.BKUP}.
         *
         * <p>Defined at {@code app/jcl/DEFGDGD.jcl:28}, with {@code LIMIT(5)} at line 29 and
         * {@code SCRATCH} at line 30. Its first generation is written as a new generation in that
         * same job at line 40, at the {@code LRECL=60} declared at line 42.</p>
         */
        TRANTYPE_BKUP("AWS.M2.CARDDEMO.TRANTYPE.BKUP", "reference", "trantype-bkup"),

        /**
         * Transaction-category reference backup generations, replacing
         * {@code AWS.M2.CARDDEMO.TRANCATG.PS.BKUP}.
         *
         * <p>Defined at {@code app/jcl/DEFGDGD.jcl:51}, with {@code LIMIT(5)} at line 52 and
         * {@code SCRATCH} at line 53. Its first generation is written as a new generation in that
         * same job at line 63, at the {@code LRECL=60} declared at line 65.</p>
         *
         * <p>Assumptions: this base name carries a {@code .PS.} qualifier that no other family's
         * does, and the path segment drops it. The qualifier distinguishes the physical sequential
         * dataset from the indexed one on the mainframe, a distinction the target has no counterpart
         * for, so carrying it into a path segment would name a storage detail that no longer exists.
         * The base name above keeps it, because the base name is provenance.</p>
         */
        TRANCATG_BKUP("AWS.M2.CARDDEMO.TRANCATG.PS.BKUP", "reference", "trancatg-bkup"),

        /**
         * Disclosure-group reference backup generations, the interest-rate table the accrual run
         * reads, replacing {@code AWS.M2.CARDDEMO.DISCGRP.BKUP}.
         *
         * <p>Defined at {@code app/jcl/DEFGDGD.jcl:74}, with {@code LIMIT(5)} at line 75 and
         * {@code SCRATCH} at line 76. Its first generation is written as a new generation in that
         * same job at line 86, at the {@code LRECL=50} declared at line 88.</p>
         */
        DISCGRP_BKUP("AWS.M2.CARDDEMO.DISCGRP.BKUP", "reference", "discgrp-bkup"),

        /**
         * Daily transaction reject-stream generations, replacing
         * {@code AWS.M2.CARDDEMO.DALYREJS}.
         *
         * <p>Defined at {@code app/jcl/DALYREJS.jcl:25}, inside the statement opened at line 24,
         * with {@code LIMIT(5)} at line 26 and {@code SCRATCH} at line 27. Written as a new
         * generation by {@code app/jcl/POSTTRAN.jcl:38}, at the {@code LRECL=430} declared at line
         * 36, by the posting program that job invokes at line 23.</p>
         *
         * <p>Assumptions: this is the tenth family and the one most easily lost, because it is the
         * only one defined in a job that defines nothing else and whose name gives no indication
         * that it declares a generation base at all. Losing it would leave the reject stream writing
         * into a prefix with no retention rule, and the reject stream is the record of every
         * transaction the posting run declined to post.</p>
         */
        DALYREJS("AWS.M2.CARDDEMO.DALYREJS", "ledger", "dalyrejs");

        /**
         * The generation-data-group base name this family replaces, reproduced character for
         * character.
         *
         * <p>Assumptions: the field is provenance and is never composed into a target location. It
         * is held so that a traceability question -- which generation data group does this prefix
         * stand in for -- can be answered from the code rather than only from a document.</p>
         */
        private final String mainframeBaseName;

        /**
         * The bounded context that owns this family's data, forming the leading path segment.
         */
        private final String domain;

        /**
         * The family's own path segment, following the domain.
         */
        private final String datasetSegment;

        /**
         * Binds one family to its baseline provenance and its target location.
         *
         * @param mainframeBaseName the generation-data-group base name this family replaces, spelled
         *     exactly as the defining JCL statement spells it
         * @param domain the owning bounded context, which becomes the leading path segment
         * @param datasetSegment the family's own path segment, spelled exactly as the key of the
         *     corresponding entry in the authored infrastructure module's family inventory
         */
        private DatasetFamily(String mainframeBaseName, String domain, String datasetSegment) {
            // WHY : Assumptions: all three arguments are stored as received, with no trimming, case
            //       folding or separator rewriting. Any of those would let this constructor silently
            //       adjust a value mistyped in a constant's argument list above, and a silently
            //       adjusted path segment is worse than a rejected one: the module would compile and
            //       would then write objects into a prefix that no lifecycle rule and no access
            //       policy names, because the infrastructure module spells the segment the other way.
            this.mainframeBaseName = mainframeBaseName;
            this.domain = domain;
            this.datasetSegment = datasetSegment;
        }

        /**
         * Returns the generation-data-group base name this family replaces.
         *
         * @return the base name, for example {@code AWS.M2.CARDDEMO.TRANSACT.BKUP}; never
         *     {@code null}, and never part of a rendered object key
         */
        public String mainframeBaseName() {
            return mainframeBaseName;
        }

        /**
         * Returns the bounded context that owns this family's data.
         *
         * @return the domain, one of {@code ledger}, {@code reference} or {@code reporting}; never
         *     {@code null}
         */
        public String domain() {
            return domain;
        }

        /**
         * Returns this family's own path segment, without its domain and without separators.
         *
         * @return the dataset segment, for example {@code transact-bkup}; never {@code null}
         */
        public String datasetSegment() {
            return datasetSegment;
        }

        /**
         * Returns the family portion of a key prefix, the domain and the dataset segment joined and
         * terminated by a forward slash.
         *
         * <p>The value ENDS WITH a separator, so it composes directly with the segments
         * {@link DatasetGeneration#keyPrefix()} appends.</p>
         *
         * @return the family prefix, for example {@code ledger/transact-bkup/}; never {@code null},
         *     and always terminated by a forward slash
         */
        public String pathSegment() {
            // WHY : Assumptions: the trailing separator is included so that this value is
            //       byte-identical to the entry the authored infra/modules/s3-datasets module
            //       publishes for the same family, which composes its own prefixes as the domain,
            //       the family key and a separator. Matching it exactly means an access policy
            //       written against the Terraform output and a key rendered here address the same
            //       location without either side adjusting the other's spelling.
            return domain + SEGMENT_SEPARATOR + datasetSegment + SEGMENT_SEPARATOR;
        }

        /**
         * Resolves a generation-data-group base name to the family that replaces it.
         *
         * <p>The comparison is exact and case-sensitive. Base names are reproduced from the JCL
         * character for character, so a value differing in case is a value that did not come from
         * the baseline.</p>
         *
         * @param candidateBaseName the base name to resolve; {@code null}, an empty string and a
         *     blank string are all accepted as input and all rejected as values, because none of
         *     them is one of the ten
         * @return the family that replaces the named base, never {@code null}
         * @throws IllegalArgumentException if {@code candidateBaseName} is not byte-identical to one
         *     of the ten base names, including when it is {@code null} or blank; the message quotes
         *     the offending value so a log identifies it without access to this source
         */
        public static DatasetFamily resolveByMainframeBaseName(String candidateBaseName) {
            for (DatasetFamily family : values()) {
                // WHY : Assumptions: the constant's own base name is the receiver of the comparison,
                //       so a null argument answers false ten times and falls through to the
                //       rejection below rather than raising from inside the loop. A null base name
                //       is the same class of fault as a misspelled one and earns the same message.
                if (family.mainframeBaseName.equals(candidateBaseName)) {
                    return family;
                }
            }

            // WHY : Alternatives Considered: returning null or an empty optional, so that an
            //       unrecognised base name would be the caller's problem. Rejected because there is
            //       no family that is legitimately the answer when the name is unknown, and a caller
            //       that forgot to test the result would stage a generation under whichever family
            //       it defaulted to -- writing one dataset's objects under another's prefix, where
            //       they would be aged out by that family's retention rule instead of their own.
            throw new IllegalArgumentException("unrecognised generation-data-group base name: '"
                    + candidateBaseName + "'; the reference baseline defines exactly "
                    + values().length + " families, declared in app/jcl/DEFGDGB.jcl,"
                    + " app/jcl/DEFGDGD.jcl and app/jcl/DALYREJS.jcl");
        }
    }

    /**
     * The two relative generation references the reference baseline uses, and no third.
     *
     * <h2>Two forms, and the search that establishes there are only two</h2>
     *
     * <p>Assumptions: a search of all thirty-eight members of {@code app/jcl} for a parenthesised
     * relative reference finds exactly two spellings, {@code (0)} and {@code (+1)}. <b>There is no
     * {@code (-1)} anywhere in the baseline, and no other offset in either direction.</b> That is
     * why this enumeration has two constants: a "previous generation" constant would model a
     * reference the baseline never makes, and a reader would reasonably assume some step used it.
     * The two forms are counted rather than assumed because they carry different meanings that the
     * target has to keep apart, and collapsing them into a single notion of "the generation" is what
     * turns a read of the current data into the creation of an empty new one.</p>
     *
     * <h2>Expressed here, resolved elsewhere</h2>
     *
     * <p>Trade-offs: this enumeration states an INTENT and cannot carry out that intent. Turning
     * {@code (+1)} into a concrete generation number means learning what the highest existing
     * generation is, and turning {@code (0)} into one means learning the same thing -- both are
     * questions only the storage bucket can answer, so both require input and output. Keeping that
     * work out of this file is what lets every member of {@link DatasetGeneration} be exercised by
     * constructing a value and comparing a string, with no client, no credentials and no emulator.
     * The cost is real and falls on the caller: a step holding one of these constants still has to
     * list the family's prefix to obtain the number it then puts in a coordinate, and this type
     * offers it no help with that.</p>
     *
     * <h2>Parameters, return values and exceptions at type level: declared inapplicable</h2>
     *
     * <p>An enumeration declaration accepts no parameter, yields no value and raises nothing, so
     * this block carries no parameter, return or exception at-clause, and no authorship,
     * availability or revision at-clause either. The inapplicability is stated for the same reason
     * it is stated on the sibling enumeration above: the project's single user-specified rule,
     * Explainability, names at its line 39 a docstring that omits parameters, return values or
     * purpose among its forbidden patterns, so a reader has to be able to tell a declared
     * inapplicability from an oversight.</p>
     */
    public enum GenerationReference {

        /**
         * The current generation, spelled {@code (0)} in the baseline, always a read.
         *
         * <p>Both occurrences in the baseline are inputs to the same merge sort:
         * {@code app/jcl/COMBTRAN.jcl:24} reads {@code AWS.M2.CARDDEMO.TRANSACT.BKUP(0)} as the
         * first input and line 26 reads {@code AWS.M2.CARDDEMO.SYSTRAN(0)} as the concatenated
         * second input. Both carry {@code DISP=SHR}, so neither creates anything.</p>
         *
         * <p>In the target this names the highest generation already staged under a family and a
         * date, which is what the trailing key of an ordered listing of that prefix gives -- and is
         * the reason {@link DatasetGeneration#generationSegment()} pads to a fixed width, since
         * without the padding the trailing key is not the highest number.</p>
         */
        CURRENT("(0)"),

        /**
         * A new generation, spelled {@code (+1)} in the baseline, created by the step that names it.
         *
         * <p>The unambiguous creation site is {@code app/jcl/POSTTRAN.jcl:38}, which names
         * {@code AWS.M2.CARDDEMO.DALYREJS(+1)} on a data definition carrying
         * {@code DISP=(NEW,CATLG,DELETE)} and {@code LRECL=430} at line 36. Other creation sites are
         * {@code app/jcl/INTCALC.jcl:41}, {@code app/jcl/TRANBKP.jcl:33},
         * {@code app/jcl/COMBTRAN.jcl:37}, {@code app/jcl/TRANREPT.jcl:33}, {@code :55} and
         * {@code :80}, {@code app/jcl/PRTCATBL.jcl:39}, and {@code app/jcl/DEFGDGD.jcl:40},
         * {@code :63} and {@code :86}.</p>
         *
         * <p>Assumptions: the form marks the generation as new, which is not the same as saying every
         * reference to it is a write. Four jobs create a generation and then read that same
         * generation back through the identical {@code (+1)} spelling later in the same job --
         * {@code app/jcl/TRANREPT.jcl} at lines 33 then 39, and again at 55 then 66;
         * {@code app/jcl/COMBTRAN.jcl} at 37 then 44; {@code app/jcl/PRTCATBL.jcl} at 39 then 45.
         * The distinction matters to a reader of this constant: it means "the generation this run
         * creates", and a step may address it more than once.</p>
         */
        NEW("(+1)");

        /**
         * The notation the baseline appends to a dataset name to make this reference.
         *
         * <p>Assumptions: the string is held for documentation and diagnostics, never composed into
         * an object key. A target key names an absolute generation through its {@code gen=} segment,
         * so a relative notation has no place in one.</p>
         */
        private final String jclNotation;

        /**
         * Binds one reference to the notation the baseline spells it with.
         *
         * @param jclNotation the parenthesised notation, spelled exactly as a JCL dataset operand
         *     spells it
         */
        private GenerationReference(String jclNotation) {
            this.jclNotation = jclNotation;
        }

        /**
         * Returns the notation the baseline appends to a dataset name to make this reference.
         *
         * @return the notation, either {@code (0)} or {@code (+1)}; never {@code null}, and never
         *     part of a rendered object key
         */
        public String jclNotation() {
            return jclNotation;
        }
    }
}
