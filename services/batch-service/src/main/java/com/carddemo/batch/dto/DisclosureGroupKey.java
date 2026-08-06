package com.carddemo.batch.dto;

/**
 * The three-component key that selects exactly one disclosure-group interest rate.
 *
 * <p>This is the migrated form of the group item {@code DIS-GROUP-KEY} declared at
 * {@code app/cpy/CVTRA02Y.cpy} line 5, which spans the first 16 bytes of the 50-byte
 * disclosure-group record whose length that copybook's own line 2 records as {@code RECLN = 50}.
 * One baseline program reads that record: {@code app/cbl/CBACT04C.cbl} accrues interest, and it
 * reaches the rate it multiplies by through this key and through nothing else. Under the migration
 * plan's transformation rule T1 the copybook, and not the file description that repeats it, is the
 * normative source for every width and every position below.</p>
 *
 * <h2>The layout, with the arithmetic that checks it</h2>
 *
 * <p>Offsets are zero-based. They are recorded because they are the audit trail: every component of
 * this key traces back to a byte range of the record's 16-byte key.</p>
 *
 * <pre>
 * offset  bytes  copybook field (line)     PICTURE  component of this record  stored form
 *      0     10  DIS-ACCT-GROUP-ID (L6)    X(10)    accountGroupId            held at width
 *     10      2  DIS-TRAN-TYPE-CD  (L7)    X(02)    transactionTypeCode       held at width
 *     12      4  DIS-TRAN-CAT-CD   (L8)    9(04)    transactionCategoryCode   zero-padded
 * </pre>
 *
 * <p>Ten plus two plus four is sixteen, and that sixteen is confirmed from three independent places
 * rather than derived once. {@code app/cpy/CVTRA02Y.cpy} lines 6 to 8 declare the three components
 * under the group item at line 5. The file description at {@code app/cbl/CBACT04C.cbl} lines 78 to
 * 81 repeats all three at the same widths, and line 50 of that program names the whole group as
 * {@code RECORD KEY IS FD-DISCGRP-KEY}, so the reference system addresses the record by one 16-byte
 * value and not by three independent lookups. Independently of both,
 * {@code app/jcl/DISCGRP.jcl} line 40 defines the cluster with {@code KEYS(16 0)} -- a 16-byte key
 * at offset zero, which balances only if the three components are 10, 2 and 4 bytes wide -- and its
 * line 41 declares {@code RECORDSIZE(50 50)}.</p>
 *
 * <h2>The component order is the copybook's, and the program assigns in a different one</h2>
 *
 * <p>Assumptions: the physical order of this key is account group, then transaction type, then
 * transaction category, and the declaration order of the three components below is load-bearing
 * rather than cosmetic. It is the order of {@code app/cpy/CVTRA02Y.cpy} lines 6 to 8, therefore the
 * order of the group item at line 5 and of the 16-byte record key that
 * {@code app/cbl/CBACT04C.cbl} line 50 names; the file description at lines 78 to 81 of that same
 * program repeats it, and {@code app/jcl/DISCGRP.jcl} line 40 depends on it arithmetically. The
 * sibling mapping {@code com.carddemo.batch.domain.DisclosureGroup.DisclosureGroupId} declares its
 * three members in that same order, and the owning service's primary key is declared on
 * {@code (acct_group_id, tran_type_cd, tran_cat_cd)} to match, so this record, that mapping and the
 * physical index all agree.</p>
 *
 * <p>Alternatives Considered: transcribing the order in which the interest program assigns the
 * three components, which is what reading it top to bottom produces, and which is
 * <b>not</b> the physical order. Three consecutive statements populate the key immediately before
 * the lookup: {@code app/cbl/CBACT04C.cbl} line 210 moves the account group identifier into the
 * group component, line 211 moves the <b>category</b> code, and line 212 moves the <b>type</b>
 * code. The last two are assigned in the opposite sequence to the one they occupy. Assignment
 * order is immaterial to the baseline, because each statement names its destination field
 * explicitly and the record layout alone decides where that field sits, so every one of those
 * moves lands correctly; the sequence is presentational there. It becomes material here, because a
 * record's components are positional. The rejected order would have produced a positional
 * constructor whose second argument is the category and whose third is the type, and a composite
 * index declared in that same wrong order. Both would compile, both would satisfy every signature
 * -- the two codes are a short string and a small number in either arrangement -- and the defect
 * would be invisible in the source. It would surface only as a lookup that matches no row, which
 * the baseline reports as a missing disclosure group rather than as a malformed key, and as an
 * index whose leading column is not the one queries filter on. That is why the order is stated here
 * and again beside the components: it is the one thing about this key a caller cannot get wrong
 * safely.</p>
 *
 * <h2>The DEFAULT fallback replaces one component of three</h2>
 *
 * <p>Assumptions: the fallback substitutes the account-group component <b>alone</b> and carries the
 * other two through unchanged. {@code app/cbl/CBACT04C.cbl} lines 415 to 440 are
 * {@code 1200-GET-INTEREST-RATE}: the read at line 416 carries an {@code INVALID KEY} branch that
 * displays {@code 'DISCLOSURE GROUP RECORD MISSING'} and then
 * {@code 'TRY WITH DEFAULT GROUP CODE'} at lines 418 and 419; line 436 tests the file status for
 * {@code '23'} specifically; and line 437 moves the literal {@code 'DEFAULT'} into
 * {@code FD-DIS-ACCT-GROUP-ID} and into no other field, before line 438 retries the read. The type
 * and category values assigned at lines 211 and 212 are still in place when that retry runs, so the
 * retry asks for <i>the same transaction type and category under the group named DEFAULT</i>. A
 * wholesale default key, with all three components replaced, would resolve a different rate
 * entirely, which is why {@link #withDefaultAccountGroupId()} is a derivation from an existing key
 * and why this type publishes no all-defaulted constant.</p>
 *
 * <p>Assumptions: the substituted value is ten characters and not the seven the program's literal
 * is written with. {@code 'DEFAULT'} at {@code app/cbl/CBACT04C.cbl} line 437 is moved into a field
 * declared {@code PIC X(10)} at {@code app/cpy/CVTRA02Y.cpy} line 6 and at
 * {@code app/cbl/CBACT04C.cbl} line 79, and an alphanumeric move left-justifies and space-fills, so
 * the key the retry searches for is {@code DEFAULT} followed by three spaces.
 * {@link #DEFAULT_ACCOUNT_GROUP_ID} therefore carries that padding, and the padding is part of the
 * value rather than decoration on it.</p>
 *
 * <h2>What a missing group, and a missing DEFAULT group, each mean</h2>
 *
 * <p>Two facts about the outcome of a lookup are decided by this key and are recorded here for the
 * benefit of whoever implements the lookup. Neither behaviour lives in this type: the rate-lookup
 * result is {@code InterestRateLookup} and the read itself belongs to a repository.</p>
 *
 * <ul>
 *   <li>A missing group record is an <b>ordinary, expected</b> outcome and not a failure.
 *       {@code app/cbl/CBACT04C.cbl} line 422 accepts a file status of {@code '00'} or
 *       {@code '23'} -- found, and not found -- as equally non-fatal, and only some other status
 *       drives the failure arm to the display at line 431 and the abend that follows. The
 *       not-found case is the fallback path.</li>
 *   <li>A missing DEFAULT record <b>is</b> a hard failure. Lines 443 to 459 are
 *       {@code 1200-A-GET-DEFAULT-INT-RATE}: the read at line 444 carries no {@code INVALID KEY}
 *       clause at all, and the status test at line 446 accepts only {@code '00'}, so a key still
 *       absent after the substitution reaches the display of
 *       {@code 'ERROR READING DEFAULT DISCLOSURE GROUP'} at line 455 and abends. There is no third
 *       fallback and no rate compiled into the program, so the fallback does not resolve to zero
 *       interest -- it stops the run.</li>
 * </ul>
 *
 * <p>Assumptions: because the substitution replaces one component of three, the seed requirement
 * that follows from the second point is not one row. A distinct DEFAULT-group row is needed for
 * every transaction type and category pair an account can present, and those rows are reference
 * data seeded by {@code reference-service}. This module neither creates nor seeds them.</p>
 *
 * <h2>Why the three components are typed as they are</h2>
 *
 * <p>Assumptions: the account group identifier and the transaction type code are fixed-width text
 * because both are declared {@code PIC X} -- alphanumeric at {@code app/cpy/CVTRA02Y.cpy} lines 6
 * and 7 -- so each is a code rather than a quantity, and its declared width is part of the key
 * contract that {@code app/jcl/DISCGRP.jcl} line 40 counts in bytes. Trailing blanks in either are
 * therefore significant characters occupying declared positions, not padding this type may
 * discard.</p>
 *
 * <p>Assumptions: the transaction category code is a number, because
 * {@code DIS-TRAN-CAT-CD PIC 9(04)} at {@code app/cpy/CVTRA02Y.cpy} line 8 is numeric-display: a
 * numeric picture right-justifies and zero-fills, so the value 5 occupies the record as
 * {@code 0005} and never as {@code 5} followed by blanks. An {@code int} carries every value the
 * four digits admit, 0 through 9999, with room to spare, and {@link #transactionCategoryCodeField()}
 * supplies the width. Splitting the concern that way -- the number holds the value, the rendering
 * holds the width -- is what keeps {@code 5} and {@code 0005} from being two keys, which is exactly
 * the hazard a text type carries here: under text, a caller who omitted a leading zero would build
 * a different key and the difference would surface only as a lookup that matched nothing. A
 * {@code short} would also fit and is not used, because {@code int} is the width Java arithmetic
 * and every parsing entry point produce anyway, so a narrower type would add a conversion at each
 * boundary and save nothing that this key's size makes worth saving.</p>
 *
 * <p>Assumptions: the numeric type here differs deliberately from the text member of the sibling
 * mapping {@code com.carddemo.batch.domain.DisclosureGroup.DisclosureGroupId}, whose category
 * component is a fixed-character string because the owning service declares the column
 * {@code CHAR(4)}. The two are not in conflict: that member has to match a column, whereas this
 * component has to be constructible from a job argument and from the category of a transaction
 * category balance, both of which are numbers. {@link #transactionCategoryCodeField()} is the
 * bridge between the two representations, and it is the only place the conversion is expressed.</p>
 *
 * <h2>Why the rate is not one of the components</h2>
 *
 * <p>Trade-offs: this type is the key and holds no rate, no amount and no money. The same key is
 * read twice against the same record in one lookup -- the primary read at
 * {@code app/cbl/CBACT04C.cbl} line 416 and, after the substitution, the retry at line 444 -- so a
 * key that also carried a resolved rate would carry the first read's value into the second, where it
 * is stale by construction. Keeping the resolved rate in a separate result type,
 * {@code InterestRateLookup}, costs one more type than a combined row would, and buys a key that is
 * reusable for both reads and for the derivation between them.</p>
 *
 * <h2>Equality is literal, and the padding is part of the value</h2>
 *
 * <p>Assumptions: value equality is this type's contract and is left exactly as the record produces
 * it -- all three components compared, none of them trimmed, normalised or case-folded. Trimming
 * the account group identifier was the alternative and is declined for a specific reason. The
 * column behind this key is {@code CHAR(10)}, and a fixed-character column treats trailing blanks
 * as insignificant in both directions: it blank-fills a short value on the way in and strips the
 * blanks again on the way out. Trimming here would import that leniency into Java, where it does
 * not hold, so a padded key and a bare one would become interchangeable inside a hash-based
 * collection while continuing to render as two different 16-byte keys. A key that behaves one way
 * in a map and another in a fixed-width record is worse than one that is simply literal. Equality
 * is therefore literal, and the compact constructor is what guarantees there is only ever one
 * literal form of a given key to compare.</p>
 *
 * <p>Assumptions: the generated {@code equals}, {@code hashCode} and {@code toString} are all left
 * in place rather than written out. Component equality is precisely the equality this key wants, so
 * there is nothing to add; and the generated rendering wraps the components in the type name and
 * the component names, which makes it unmistakable for the 16-byte wire form that
 * {@link #fixedWidthKey()} produces. A hand-written {@code toString} returning the bare composite
 * would look interchangeable with that method and would eventually be used as one. Note that value
 * equality is independent of component <i>order</i> in a way the composite rendering is not: two
 * keys are equal when each component equals its counterpart, whereas {@link #fixedWidthKey()}
 * concatenates by position and a transposition changes its result.</p>
 *
 * <h2>How strict the compact constructor is, and what that costs</h2>
 *
 * <p>Trade-offs: the account group identifier is required to be <b>exactly</b> ten characters and
 * the transaction type code exactly two, rather than being accepted short and padded on the way in.
 * The consequence is real and is paid deliberately: every construction site has to reach the
 * declared width first, and a value read back out of a fixed-character column arrives short,
 * because {@code com.carddemo.batch.domain.DisclosureGroup} exposes a group identifier whose
 * trailing blanks the database has already stripped.
 * {@link #ofBlankPaddedAccountGroupId(String, String, int)} exists as the one named place that
 * conversion happens, so the padding is explicit at the call site instead of implicit in every
 * constructor call. What strictness buys is that a key has exactly one canonical form, which makes
 * {@link #isDefaultAccountGroup()} answerable correctly. Under the accept-and-pad alternative the
 * short and padded forms would both be constructible and would be unequal, so a key built from a
 * blank-stripped {@code DEFAULT} would report that the fallback had not fired when it had -- a
 * wrong answer about the one branch this key exists to express. Strictness makes that answer
 * unrepresentable rather than merely documented.</p>
 *
 * @param accountGroupId the account group identifier, {@code DIS-ACCT-GROUP-ID PIC X(10)} at
 *     {@code app/cpy/CVTRA02Y.cpy} line 6, occupying the key's first ten bytes; exactly ten
 *     characters, trailing blanks included, and the one component the DEFAULT fallback replaces
 * @param transactionTypeCode the transaction type code, {@code DIS-TRAN-TYPE-CD PIC X(02)} at
 *     {@code app/cpy/CVTRA02Y.cpy} line 7, occupying zero-based offsets 10 and 11; exactly two
 *     characters, and carried through the DEFAULT fallback unchanged
 * @param transactionCategoryCode the transaction category code, {@code DIS-TRAN-CAT-CD PIC 9(04)}
 *     at {@code app/cpy/CVTRA02Y.cpy} line 8, occupying zero-based offsets 12 to 15; a value from
 *     0 through 9999 whose four-digit stored form is supplied by
 *     {@link #transactionCategoryCodeField()}, and carried through the DEFAULT fallback unchanged
 */
public record DisclosureGroupKey(
        String accountGroupId,
        String transactionTypeCode,
        int transactionCategoryCode) {

    // WHY : Assumptions: the three components above are declared in the PHYSICAL order of the
    //       record key -- group, then type, then category -- taken from app/cpy/CVTRA02Y.cpy
    //       lines 6 to 8, confirmed by the file description at app/cbl/CBACT04C.cbl lines 78 to 82
    //       whose group app/cbl/CBACT04C.cbl line 50 names as RECORD KEY IS FD-DISCGRP-KEY, and
    //       confirmed again arithmetically by the KEYS(16 0) operand of app/jcl/DISCGRP.jcl line 40.
    // WHY : Alternatives Considered: declaring them in the order app/cbl/CBACT04C.cbl assigns them
    //       at lines 210 to 212, which is group, then CATEGORY, then TYPE. Rejected. That sequence
    //       is presentational in the baseline, where each MOVE names its destination field and so
    //       lands correctly whatever order the statements appear in, but a record's components are
    //       positional: the transposition would reach the positional constructor and the composite
    //       index without any signature objecting, and it would surface only as a lookup matching
    //       no row -- reported by the baseline as a missing disclosure group -- and as an index
    //       whose leading column is not the one queries filter on. This comment sits here, next to
    //       the declaration, so that the order is not rewritten to match the program's source
    //       sequence by a later reader who has only lines 210 to 212 in view.

    /**
     * The declared width of the account group component, ten characters.
     *
     * <p>Assumptions: the number is {@code DIS-ACCT-GROUP-ID PIC X(10)} at
     * {@code app/cpy/CVTRA02Y.cpy} line 6, repeated as {@code FD-DIS-ACCT-GROUP-ID PIC X(10)} at
     * {@code app/cbl/CBACT04C.cbl} line 79. It is published rather than kept private because a
     * caller that has to pad a value to this width, or assert that it already is, needs the same
     * number this type validates against; two copies of it would be two numbers that can
     * disagree.</p>
     */
    public static final int ACCOUNT_GROUP_ID_LENGTH = 10;

    /**
     * The declared width of the transaction type component, two characters.
     *
     * <p>Assumptions: the number is {@code DIS-TRAN-TYPE-CD PIC X(02)} at
     * {@code app/cpy/CVTRA02Y.cpy} line 7, repeated at {@code app/cbl/CBACT04C.cbl} line 80. The
     * same two-character code is declared {@code TRANCAT-TYPE-CD PIC X(02)} at
     * {@code app/cpy/CVTRA01Y.cpy} line 7, which is the field
     * {@code app/cbl/CBACT04C.cbl} line 212 copies into the key, so the widths agree on both sides
     * of the lookup.</p>
     */
    public static final int TRANSACTION_TYPE_CODE_LENGTH = 2;

    /**
     * The declared width of the transaction category component, four digits.
     *
     * <p>Assumptions: the number is {@code DIS-TRAN-CAT-CD PIC 9(04)} at
     * {@code app/cpy/CVTRA02Y.cpy} line 8, repeated at {@code app/cbl/CBACT04C.cbl} line 81, and it
     * is the width {@link #transactionCategoryCodeField()} renders to rather than a bound on the
     * component itself. The corresponding source field is {@code TRANCAT-CD PIC 9(04)} at
     * {@code app/cpy/CVTRA01Y.cpy} line 8, copied into the key at
     * {@code app/cbl/CBACT04C.cbl} line 211.</p>
     */
    public static final int TRANSACTION_CATEGORY_CODE_LENGTH = 4;

    /**
     * The total width of the composite key, sixteen bytes.
     *
     * <p>Assumptions: sixteen is ten plus two plus four, and it is asserted by the baseline rather
     * than inferred from this type. {@code app/cbl/CBACT04C.cbl} line 50 names the whole group item
     * declared at lines 78 to 81 as the record key, and {@code app/jcl/DISCGRP.jcl} line 40 defines
     * the cluster with {@code KEYS(16 0)}. The constant is published so that a caller comparing a
     * rendered key against a record does not restate the total, which is the number that would
     * quietly stop matching if any component width were ever misread.</p>
     */
    public static final int KEY_LENGTH =
            ACCOUNT_GROUP_ID_LENGTH + TRANSACTION_TYPE_CODE_LENGTH + TRANSACTION_CATEGORY_CODE_LENGTH;

    /**
     * The smallest transaction category code the four-digit picture admits, zero.
     *
     * <p>Assumptions: {@code DIS-TRAN-CAT-CD PIC 9(04)} at {@code app/cpy/CVTRA02Y.cpy} line 8 is
     * unsigned, so the picture admits no negative value at all, and zero renders as
     * {@code 0000}.</p>
     */
    public static final int MIN_TRANSACTION_CATEGORY_CODE = 0;

    /**
     * The largest transaction category code the four-digit picture admits, nine thousand nine
     * hundred and ninety-nine.
     *
     * <p>Assumptions: four unsigned digits reach 9999 and no further, so a larger value has no
     * representation in the four bytes the record reserves at zero-based offsets 12 to 15.</p>
     */
    public static final int MAX_TRANSACTION_CATEGORY_CODE = 9999;

    // WHY : Assumptions: the constant below is TEN characters and not the seven its baseline
    //       literal is written with, and the three trailing spaces are part of the value rather
    //       than incidental. app/cbl/CBACT04C.cbl line 437 moves the literal 'DEFAULT' into
    //       FD-DIS-ACCT-GROUP-ID, a field declared PIC X(10) at app/cpy/CVTRA02Y.cpy line 6 and at
    //       app/cbl/CBACT04C.cbl line 79, and an alphanumeric move left-justifies and space-fills,
    //       so the key the retry at line 444 searches for is DEFAULT followed by three spaces.
    // WHY : Trade-offs: the padded form is what this type publishes even though the column behind
    //       the key is CHAR(10) and would accept either form, because the two consumers of this
    //       constant disagree about which form is correct. A query against a fixed-character column
    //       matches whichever form is supplied, since trailing blanks are insignificant there; the
    //       16-byte rendering that fixedWidthKey() produces does not, and neither does the literal
    //       String comparison in isDefaultAccountGroup(). Publishing the bare seven-character
    //       literal instead would satisfy the lenient consumer and silently break both strict ones.
    /**
     * The account group identifier the interest program substitutes when a group lookup misses,
     * space-padded to its declared ten-character width.
     *
     * <p>The value is the seven-character literal {@code DEFAULT} followed by exactly three spaces.
     * Its provenance is {@code app/cbl/CBACT04C.cbl} line 437, where {@code 'DEFAULT'} is moved
     * into a {@code PIC X(10)} field, and the padding is a consequence of that width rather than a
     * choice made here.</p>
     *
     * <p>Assumptions: a comparison of a stored {@code CHAR(10)} value against an unpadded
     * {@code DEFAULT} is not reliably equivalent to a comparison against this constant. A
     * fixed-character column ignores trailing blanks, so it treats the two as one value, while Java
     * {@code String} equality and any byte-for-byte comparison of a rendered key treat them as two.
     * Using this constant everywhere is what keeps those views from diverging.</p>
     */
    public static final String DEFAULT_ACCOUNT_GROUP_ID = "DEFAULT   ";

    /**
     * A run of blanks exactly as wide as the account group component, used to pad a short value.
     *
     * <p>Assumptions: padding is done by taking a suffix of this literal rather than by a formatter,
     * so the characters appended are always the ASCII space {@code U+0020} that the baseline's
     * space-fill produces. A locale-sensitive formatter is deliberately not used anywhere in this
     * type, because its padding and digit characters depend on the default locale and a fixed-width
     * record has no room for a character that occupies more than one byte.</p>
     */
    private static final String ACCOUNT_GROUP_ID_BLANK_PAD = "          ";

    // WHY : Assumptions: the literal below is exactly as wide as the category component, so a
    //       suffix of it supplies precisely the leading zeros a value of one, two or three digits
    //       is short by. The alternative was a formatter with a zero-padded numeric conversion,
    //       rejected on a concrete ground rather than a stylistic one: that conversion localises
    //       its digits, so under a default locale whose decimal digits are not ASCII the rendering
    //       would emit characters the 16-byte key has no room for. Integer.toString is specified to
    //       emit ASCII digits at radix ten regardless of locale, which is why it is paired with
    //       this literal instead.
    /**
     * A run of zeros exactly as wide as the transaction category component, used to pad a value.
     *
     * <p>Assumptions: the record's numeric picture right-justifies and zero-fills, so a short value
     * is short on the LEFT and the padding is a prefix. That is the opposite of the account group
     * component, whose alphanumeric picture pads on the right, and the two are deliberately held as
     * two separate literals so neither is used for the other.</p>
     */
    private static final String TRANSACTION_CATEGORY_CODE_ZERO_PAD = "0000";


    /**
     * Asserts that each component is present and occupies its declared width, and stores all three
     * exactly as received.
     *
     * <p>Nothing is trimmed, padded, case-folded or otherwise normalised here. Only presence, width
     * and numeric range are checked, for the reasons recorded on this type: a key has exactly one
     * canonical form, and the caller supplies it.</p>
     *
     * <p>Successful construction yields this record instance and no separate return value.</p>
     *
     * @param accountGroupId the candidate account group identifier; must be non-null and exactly
     *     {@link #ACCOUNT_GROUP_ID_LENGTH} characters, trailing blanks included
     * @param transactionTypeCode the candidate transaction type code; must be non-null and exactly
     *     {@link #TRANSACTION_TYPE_CODE_LENGTH} characters
     * @param transactionCategoryCode the candidate transaction category code; must lie between
     *     {@link #MIN_TRANSACTION_CATEGORY_CODE} and {@link #MAX_TRANSACTION_CATEGORY_CODE}
     *     inclusive
     * @throws IllegalArgumentException if either string component is {@code null}, if either is not
     *     exactly its declared width, or if the category code falls outside the range the record's
     *     four-digit picture admits
     */
    public DisclosureGroupKey {
        // WHY : Assumptions: one exception type covers an absent component, a mis-sized one and an
        //       out-of-range one, because a caller can do nothing different about the three. The
        //       type matches the one the sibling record BusinessDate in this same package raises
        //       for its own absent and mis-sized token, so a rejection reads the same across the
        //       package rather than depending on which contract was violated.
        if (accountGroupId == null) {
            throw new IllegalArgumentException(
                    "disclosure-group account group id is required and was null");
        }

        // WHY : Trade-offs: the width is required EXACTLY rather than accepted short and padded
        //       here. The consequence is that a value read back from a fixed-character column,
        //       which arrives with its trailing blanks already stripped, is rejected by this
        //       constructor and has to reach the declared width through
        //       ofBlankPaddedAccountGroupId first. That is the cost, and it is paid so that a key
        //       has exactly one canonical form: padding silently here would make the short and
        //       padded forms indistinguishable afterwards, which reads as a kindness until the
        //       fallback branch has to be reported, at which point a key built from a
        //       blank-stripped DEFAULT would have to answer whether the fallback fired and there
        //       would be no way to tell. Rejecting is loud; padding silently is not.
        // WHY : Assumptions: the width is measured on the value as supplied, with no whitespace
        //       removed first, so an eleven-character value that would measure ten after trimming
        //       is refused rather than quietly accepted. Accepting it would place a character in a
        //       declared key position that the baseline never puts there.
        if (accountGroupId.length() != ACCOUNT_GROUP_ID_LENGTH) {
            throw new IllegalArgumentException("disclosure-group account group id '"
                    + accountGroupId + "' is " + accountGroupId.length()
                    + " characters, not exactly " + ACCOUNT_GROUP_ID_LENGTH
                    + "; a value read from a fixed-character column arrives blank-stripped and must"
                    + " reach its declared width through ofBlankPaddedAccountGroupId");
        }

        if (transactionTypeCode == null) {
            throw new IllegalArgumentException(
                    "disclosure-group transaction type code is required and was null");
        }

        // WHY : Assumptions: the type code is required at exactly two characters and is not padded
        //       either, and unlike the group component it has no lenient entry point. No baseline
        //       path supplies it short: app/cbl/CBACT04C.cbl line 212 copies it from
        //       TRANCAT-TYPE-CD PIC X(02) at app/cpy/CVTRA01Y.cpy line 7, which is already two
        //       characters wide, and the DEFAULT substitution at line 437 does not touch it. A
        //       padding entry point for a component nothing supplies short would only widen the
        //       surface through which a malformed code could reach a key.
        if (transactionTypeCode.length() != TRANSACTION_TYPE_CODE_LENGTH) {
            throw new IllegalArgumentException("disclosure-group transaction type code '"
                    + transactionTypeCode + "' is " + transactionTypeCode.length()
                    + " characters, not exactly " + TRANSACTION_TYPE_CODE_LENGTH);
        }

        // WHY : Assumptions: the range is what the four-digit unsigned picture at
        //       app/cpy/CVTRA02Y.cpy line 8 admits, and it is checked here rather than left to the
        //       rendering because a value outside it has no representation in the four bytes the
        //       record reserves. A negative value would render with a sign character and a value
        //       above 9999 with five digits, and either would shift every following byte of the
        //       16-byte key by one position -- a corruption of the key rather than a wrong value in
        //       it. Failing at construction localises that to the call site that produced the
        //       number.
        if (transactionCategoryCode < MIN_TRANSACTION_CATEGORY_CODE
                || transactionCategoryCode > MAX_TRANSACTION_CATEGORY_CODE) {
            throw new IllegalArgumentException("disclosure-group transaction category code "
                    + transactionCategoryCode + " is outside "
                    + MIN_TRANSACTION_CATEGORY_CODE + " through "
                    + MAX_TRANSACTION_CATEGORY_CODE
                    + ", which is what PIC 9(04) admits");
        }
    }

    /**
     * Builds a key from an account group identifier that is short of its declared width, blank-padding
     * it on the right first.
     *
     * <p>This is the one sanctioned way to reach the canonical ten-character form from a value that
     * arrives short, and the case that arrives short is specific: a group identifier read back out
     * of the {@code CHAR(10)} column has had its trailing blanks stripped by the database, so the
     * padded {@code DEFAULT} key is returned as the seven-character literal. Passing that value to
     * the canonical constructor is rejected; passing it here yields the key the baseline would have
     * searched with.</p>
     *
     * <p>Assumptions: only the account group component is padded. The transaction type code must
     * already be exactly two characters, because no baseline path supplies it short, and the
     * category code is a number that carries no padding at all until it is rendered.</p>
     *
     * @param accountGroupId the account group identifier at or below its declared width, which is
     *     blank-padded on the right to {@link #ACCOUNT_GROUP_ID_LENGTH} characters before the key is
     *     built; must not be {@code null}
     * @param transactionTypeCode the transaction type code, which is passed through unpadded and so
     *     must already be exactly {@link #TRANSACTION_TYPE_CODE_LENGTH} characters
     * @param transactionCategoryCode the transaction category code, passed through unchanged
     * @return a key whose account group component is exactly {@link #ACCOUNT_GROUP_ID_LENGTH}
     *     characters; never {@code null}
     * @throws IllegalArgumentException if {@code accountGroupId} is {@code null} or longer than its
     *     declared width, or if either of the remaining components fails the canonical
     *     constructor's own checks
     */
    public static DisclosureGroupKey ofBlankPaddedAccountGroupId(
            String accountGroupId, String transactionTypeCode, int transactionCategoryCode) {
        if (accountGroupId == null) {
            throw new IllegalArgumentException(
                    "disclosure-group account group id is required and was null");
        }

        // WHY : Assumptions: a value ALREADY at the declared width is passed through untouched, and
        //       an OVER-wide one is rejected here rather than truncated. Truncating would produce a
        //       key that looks well formed and addresses a different row, which is the failure this
        //       whole type is shaped to avoid; the length is reported in the message so the caller
        //       can see how far over it was.
        if (accountGroupId.length() > ACCOUNT_GROUP_ID_LENGTH) {
            throw new IllegalArgumentException("disclosure-group account group id '"
                    + accountGroupId + "' is " + accountGroupId.length()
                    + " characters, which exceeds its declared width of "
                    + ACCOUNT_GROUP_ID_LENGTH + " and cannot be padded to it");
        }

        // WHY : Assumptions: the padding is a SUFFIX of a blank literal of the declared width, so
        //       the characters appended are the ASCII space the baseline's alphanumeric space-fill
        //       produces, and the result is the declared width by construction rather than by a
        //       loop whose bound could be miscounted.
        String padded = accountGroupId
                + ACCOUNT_GROUP_ID_BLANK_PAD.substring(accountGroupId.length());
        return new DisclosureGroupKey(padded, transactionTypeCode, transactionCategoryCode);
    }


    /**
     * Returns the account group identifier exactly as it was supplied, at its declared width.
     *
     * <p>This accessor accepts no parameters.</p>
     *
     * <p>Alternatives Considered: offering a second, trimmed accessor beside this one, for a log line
     * or an assertion message. Declined, and the omission is recorded here because it is where a
     * reader would look for one: the sibling mapping
     * {@code com.carddemo.batch.domain.DisclosureGroup} does expose a blank-stripped group
     * identifier, since a {@code CHAR(10)} column strips the padding on the way out, so the absence
     * of a matching member here is a decision rather than an oversight. Two accessors returning two
     * different strings for one component is how the wrong one reaches a query parameter or a
     * rendered key, and the padded form is the only one of the two that is sixteen characters wide
     * when concatenated. The diagnostic need the trimmed form would have served is met by the
     * generated {@code toString}, which names each component and so makes its padding visible rather
     * than discarding it.</p>
     *
     * @return the account group identifier, exactly {@link #ACCOUNT_GROUP_ID_LENGTH} characters with
     *     any trailing blanks intact; never {@code null}
     */
    public String accountGroupId() {
        // WHY : Assumptions: this accessor is written out rather than left implicit purely so that
        //       its contract has somewhere to live. An implicit accessor carries no Javadoc block
        //       and therefore no @return, and the guarantee that matters here is exactly the one an
        //       implicit accessor cannot state: that the trailing blanks come back out.
        return accountGroupId;
    }

    /**
     * Returns the transaction type code exactly as it was supplied.
     *
     * <p>This accessor accepts no parameters.</p>
     *
     * @return the two-character transaction type code, unaltered; never {@code null}
     */
    public String transactionTypeCode() {
        // WHY : Assumptions: written out for the same reason as the accessor above -- the contract
        //       that this code is returned unaltered needs a Javadoc block to sit in, and an
        //       implicit accessor has none.
        return transactionTypeCode;
    }

    /**
     * Returns the transaction category code as a number, without its stored zero padding.
     *
     * <p>This accessor accepts no parameters. The four-digit form the record stores is produced by
     * {@link #transactionCategoryCodeField()} instead.</p>
     *
     * @return the category code, between {@link #MIN_TRANSACTION_CATEGORY_CODE} and
     *     {@link #MAX_TRANSACTION_CATEGORY_CODE} inclusive
     */
    public int transactionCategoryCode() {
        // WHY : Assumptions: written out so that the split between this method and the field
        //       rendering is documented at both ends. A caller reaching for the stored four-digit
        //       form and finding only a number here would otherwise be left to pad it itself, which
        //       is the duplication the paired method exists to prevent.
        return transactionCategoryCode;
    }

    /**
     * Derives the fallback key by replacing the account group component alone with
     * {@link #DEFAULT_ACCOUNT_GROUP_ID}.
     *
     * <p>The transaction type code and the transaction category code are carried through unchanged,
     * which is what makes this the fallback the baseline performs rather than a different lookup.
     * {@code app/cbl/CBACT04C.cbl} line 437 moves {@code 'DEFAULT'} into
     * {@code FD-DIS-ACCT-GROUP-ID} and into no other field, so when the retry at line 444 runs, the
     * type and category values assigned at lines 211 and 212 are still in place. The retry therefore
     * asks for the same transaction type and category under the group named {@code DEFAULT}.</p>
     *
     * <p>This operation accepts no parameters, and the receiving key is not modified: a record is
     * immutable, so the fallback key is a new instance and the original remains available for
     * reporting which key missed.</p>
     *
     * <p>Trade-offs: applying this derivation to a key that is already the DEFAULT group is
     * permitted and simply yields an equal key, rather than being rejected. Rejecting it was the
     * alternative and is declined because the baseline has no third read -- lines 443 to 459 carry
     * no {@code INVALID KEY} clause and abend on any status other than {@code '00'} -- so a caller
     * that derives twice has a defect in its own control flow, and this type cannot see enough of
     * that flow to tell a loop apart from a legitimate re-derivation of a key it was handed.
     * {@link #isDefaultAccountGroup()} is what lets a caller make that check itself before
     * deriving.</p>
     *
     * @return a new key with the DEFAULT account group and this key's own transaction type and
     *     category codes; never {@code null}
     */
    public DisclosureGroupKey withDefaultAccountGroupId() {
        // WHY : Assumptions: ONE component of three is replaced. Replacing all three -- which a
        //       constant named for a "default key" would invite -- would resolve the rate of a
        //       different transaction type and category, so it would return a number rather than
        //       fail, and the wrong number would post as interest. The other two components are
        //       therefore forwarded explicitly here so that the single substitution is visible at
        //       the point it happens.
        return new DisclosureGroupKey(
                DEFAULT_ACCOUNT_GROUP_ID, transactionTypeCode, transactionCategoryCode);
    }

    /**
     * Reports whether this key names the DEFAULT account group, and so whether it is a fallback key.
     *
     * <p>This predicate accepts no parameters. It is the check a rate lookup uses to report that the
     * fallback fired, which matters because {@code app/cbl/CBACT04C.cbl} line 422 treats a missing
     * group record as an ordinary outcome rather than an error: without this distinction a resolved
     * rate gives no indication of which of the two reads produced it.</p>
     *
     * <p>Assumptions: the comparison is against the padded ten-character constant and is literal, so
     * it answers {@code true} only for a key holding that exact value. A key holding the bare
     * seven-character literal cannot exist, because the compact constructor refuses that width, so
     * there is no short form of the DEFAULT group for this predicate to miss. That is the whole
     * benefit of the constructor's strictness: were the short form constructible it would answer
     * {@code false} here while addressing the very same {@code CHAR(10)} row, and the fallback would
     * be reported as not having fired when it had.</p>
     *
     * @return {@code true} when the account group component equals
     *     {@link #DEFAULT_ACCOUNT_GROUP_ID}, and {@code false} for every ordinary group
     */
    public boolean isDefaultAccountGroup() {
        // WHY : Assumptions: the constant is the receiver of the comparison rather than the
        //       argument. The component is guaranteed non-null by the compact constructor, so this
        //       is not a null guard; it is written this way so that the direction of the comparison
        //       cannot come to depend on that guarantee if a future entry point ever relaxes it.
        return DEFAULT_ACCOUNT_GROUP_ID.equals(accountGroupId);
    }

    /**
     * Renders the transaction category code at the four-digit width the record stores it in.
     *
     * <p>This rendering accepts no parameters. It is the inverse of the numeric accessor: 5 renders
     * as {@code 0005}, 0 as {@code 0000} and 9999 as {@code 9999}, always four characters.</p>
     *
     * <p>Assumptions: the leading zeros are not cosmetic. {@code DIS-TRAN-CAT-CD PIC 9(04)} at
     * {@code app/cpy/CVTRA02Y.cpy} line 8 is numeric-display, and a numeric picture right-justifies
     * and zero-fills, so the record holds {@code 0005} rather than {@code 5} followed by blanks. The
     * interest program relies on that fill for the value it writes as well: it sets the category of
     * the interest transaction it generates by moving a two-character literal into a
     * {@code PIC 9(04)} field, which stores four digits. A caller that dropped a leading zero would
     * not produce a shorter key -- it would produce a different one.</p>
     *
     * @return the category code as exactly {@link #TRANSACTION_CATEGORY_CODE_LENGTH} ASCII digits;
     *     never {@code null}
     */
    public String transactionCategoryCodeField() {
        // WHY : Assumptions: Integer.toString is specified to emit ASCII digits at radix ten
        //       whatever the default locale is, and the padding is a PREFIX taken from a
        //       four-character zero literal, so the result is four ASCII characters by construction.
        //       A locale-sensitive formatter with a zero-padded numeric conversion was the
        //       alternative and is rejected on a concrete ground: that conversion localises its
        //       digits, so under a default locale whose decimal digits are not ASCII it would emit
        //       characters that occupy more than one byte and overflow the four the record reserves.
        // WHY : Assumptions: the substring index is safe without a guard because the compact
        //       constructor has already confined the value to 0 through 9999, so its decimal form is
        //       between one and four characters and the index is between zero and four.
        String digits = Integer.toString(transactionCategoryCode);
        return TRANSACTION_CATEGORY_CODE_ZERO_PAD.substring(digits.length()) + digits;
    }

    /**
     * Renders this key as the single sixteen-character value the reference system addresses the
     * record by.
     *
     * <p>This rendering accepts no parameters. The components are concatenated in the key's physical
     * order -- the account group identifier at its full ten characters, then the two-character
     * transaction type code, then the four-digit category code -- and ten plus two plus four is the
     * sixteen that {@code app/jcl/DISCGRP.jcl} line 40 declares as {@code KEYS(16 0)} and that
     * {@code app/cbl/CBACT04C.cbl} line 50 names as {@code RECORD KEY IS FD-DISCGRP-KEY} over the
     * group at lines 78 to 81.</p>
     *
     * <p>Assumptions: only one of the three components needs a rendering step, and the asymmetry is
     * a consequence of the compact constructor rather than an omission here. The account group
     * identifier and the transaction type code are already held at their declared widths, because
     * the constructor requires exactly that and rejects anything else, so there is no padding left
     * for this method to apply to either; the category code is a number and is the one component
     * whose stored form differs from the form the record carries in memory.</p>
     *
     * <p>Trade-offs: this rendering is position-sensitive in a way value equality is not. Two keys
     * are equal when each component equals its counterpart, whereas this method concatenates by
     * position, so a transposed pair of codes yields a different sixteen characters. That is the
     * intended property and is why this method is the regression guard for the component order: a
     * key assembled in the order {@code app/cbl/CBACT04C.cbl} lines 210 to 212 assign the components
     * renders its category where the record expects its type.</p>
     *
     * @return exactly {@link #KEY_LENGTH} characters, being the account group identifier, the
     *     transaction type code and the zero-padded category code in that order; never {@code null}
     */
    public String fixedWidthKey() {
        // WHY : Assumptions: the two text components are concatenated as held, with nothing trimmed
        //       and nothing re-padded. Trimming the account group identifier here would shorten the
        //       DEFAULT fallback key from sixteen characters to thirteen and shift the type and
        //       category codes three positions left, so a comparison against a record would miss on
        //       every field rather than on one -- which is why no normalisation happens at this
        //       boundary either.
        return accountGroupId + transactionTypeCode + transactionCategoryCodeField();
    }
}
