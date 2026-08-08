package com.carddemo.reporting.domain;

import com.carddemo.common.money.Money;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Converter;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;
import org.hibernate.annotations.Immutable;

/**
 * Read-only projection of {@code reporting.v_accounts}, carrying the account values the statement
 * pipeline reads for one account.
 *
 * <h2>Purpose</h2>
 *
 * <p>The baseline statement generator reaches the account master once per cross-reference row, at
 * paragraph {@code 3000-ACCTFILE-GET.} on L392 of {@code app/cbl/CBSTM03A.CBL}, keyed by the account
 * identifier it takes from the cross-reference record at L396 with the key length computed from that
 * same field at L398. It then prints exactly two of the record's fields: the identifier at L483 and
 * the current balance at L484, the latter through the edit mask {@code PIC 9(9).99-} declared at L113.
 * Those two fields are why this projection exists. The other six members are the remainder of what the
 * physical view projects, and they are carried rather than elided because a Java projection narrower
 * than its own view would have to be widened -- together with the migration that declares the view --
 * the first time any of them was rendered.
 *
 * <h2>Assumptions: this projection serves the statement join and no other</h2>
 *
 * <p>{@code app/jcl/CREASTMT.JCL} runs the statement generator as STEP040 at L79 and supplies it four
 * data inputs: {@code TRNXFILE} at L83, {@code XREFFILE} at L84, {@code ACCTFILE} at L85 and
 * {@code CUSTFILE} at L86. Exactly four {@code COPY} statements corroborate that from the program
 * side, at L51, L53, L55 and L57 of the same generator. This projection is the {@code ACCTFILE}
 * member of that set, so its join partners are the statement transaction, card cross-reference and
 * customer projections named in this package's charter.
 *
 * <h2>Assumptions: this projection is deliberately not a report-join participant</h2>
 *
 * <p>{@code app/jcl/TRANREPT.jcl} lists its inputs across L65 through L74 as {@code TRANFILE},
 * {@code CARDXREF}, {@code TRANTYPE}, {@code TRANCATG} and {@code DATEPARM}, and no account dataset
 * appears among them. The report program agrees: at L364 of {@code app/cbl/CBTRN03C.cbl} it moves
 * {@code XREFFILE}'s own {@code XREF-ACCT-ID} into the report's account column rather than reading an
 * account record for it. The report therefore never opens the account master at all, which is why the
 * cross-reference projection and not this one supplies the account identifier a report line prints.
 * Recorded so that a reader looking for a way to widen the report query does not reach for this type.
 *
 * <h2>Assumptions: the record geometry, re-derived from two unrelated sources</h2>
 *
 * <p>{@code app/cpy/CVACT01Y.cpy} declares {@code 01 ACCOUNT-RECORD.} at L4 over a record length of
 * 300. Summing the declared {@code PICTURE} widths from L5 to L16 gives 11 plus 1 plus 12 plus 12 plus
 * 12 plus 10 plus 10 plus 10 plus 12 plus 12 plus 10 plus 10, which is 122 significant bytes, and the
 * trailing {@code FILLER PIC X(178)} at L17 occupies one-based positions 123 through 300, so 122 plus
 * 178 closes the record exactly. A source containing no copybook reaches the same total: {@code FD
 * ACCT-FILE} at L75 of {@code app/cbl/CBSTM03B.CBL} declares {@code FD-ACCT-ID PIC 9(11)} at L77 and
 * {@code FD-ACCT-DATA PIC X(289)} at L78, and 11 plus 289 is likewise 300. Two unrelated sources
 * agreeing is the reason these positions are asserted here rather than estimated.
 *
 * <h2>Assumptions: the trailing filler is dropped, and the drop is recorded here</h2>
 *
 * <p>The {@code FILLER PIC X(178)} at L17 spanning positions 123 through 300 is padding to the fixed
 * record length and carries no value, so it becomes no column and no member. The migration plan's
 * normative-copybook rule requires the drop to be recorded per record rather than inferred from the
 * absence of a member, which is what the preceding paragraph and this one do.
 *
 * <h2>Alternatives Considered: eight members, and the four declared fields the view withholds</h2>
 *
 * <p>{@code data-migration/sql/V1__reporting_views.sql} declares {@code reporting.v_accounts} as a
 * strict subset of the account relation, selecting the identifier, the status, the current balance,
 * the credit limit, the three dates and the group. It withholds four fields this record declares:
 * {@code ACCT-CASH-CREDIT-LIMIT} at L9, {@code ACCT-CURR-CYC-CREDIT} at L13 and
 * {@code ACCT-CURR-CYC-DEBIT} at L14, each of which the owning migration does declare as a base
 * column, and {@code ACCT-ADDR-ZIP} at L15. The view's own rationale is that no statement or report
 * band prints any of the four, so projecting them would widen this context's reach past what it
 * renders. Mapping all twelve declared fields regardless was the alternative and is rejected on two
 * grounds: the four columns are absent from the relation this type is mapped over, so every generated
 * select would name a column that does not exist; and widening the view to admit them would have to
 * happen in a migration this package does not own. The four are named here, with their declaring
 * lines, so the difference between this type's member list and the record's field list is a recorded
 * decision rather than an apparent omission.
 *
 * <h2>Alternatives Considered: a view is mapped, and no relation is owned</h2>
 *
 * <p>This context's ownership cell in the migration plan's per-context data model, at its section
 * 0.4.1.3, reads "(none)": it owns no schema and declares no table, and reads through views under a
 * select-only privilege instead. Owning a copy of the account relation here was the first alternative
 * and is rejected because two relations holding the same account rows would need reconciling and would
 * let a report disagree with the account service about a balance. A read replica was the second and is
 * rejected on
 * the plan's own reasoning, that a replica adds cost and replica-lag semantics for no parity benefit;
 * the baseline read one account master, and one relation read through a view reproduces that.
 *
 * <h2>Assumptions: cross-context reads travel by privilege, never by a build dependency</h2>
 *
 * <p>The relations behind these views belong to other bounded contexts, and this module reaches them
 * only through the select-only grant that
 * {@code data-migration/sql/V0__schemas_and_roles.sql} bootstraps -- the same mechanism the batch
 * context uses to reach a schema it does not own. Declaring a build dependency on the owning module
 * and importing its entity was the alternative and is not available: the layering rules held in
 * {@code services/common-lib/src/test/java/com/carddemo/common/architecture/LayeringRulesTest.java}
 * fail a build in which one context imports another's domain model. A view that has not been
 * bootstrapped is therefore a defect in the migration that declares it, to be reported against that
 * migration rather than worked around here.
 *
 * <h2>Assumptions: no association is mapped to any sibling projection</h2>
 *
 * <p>No relationship annotation appears on this type in either direction. The four-way join over the
 * inputs declared at L83 through L86 of {@code app/jcl/CREASTMT.JCL} is composed in the repository
 * layer as a query over the physical views, which keeps each projection independently loadable and
 * keeps the join shape in one place that can be read against the job control it reproduces. Mapping
 * the partners as associations was the alternative and is
 * rejected because a mapped association would make loading one account row fetch, or lazily proxy,
 * rows the caller may not need, and would encode a join order the query already states plainly.
 *
 * <h2>Trade-offs: immutable, with no optimistic-lock member and no write path</h2>
 *
 * <p>The type is annotated {@code @Immutable}, declares no mutator, no cascade and no association,
 * and every column below is declared neither insertable nor updatable. The account relation's own
 * optimistic-lock version column exists, and it belongs to the account service's writable entity,
 * which performs the before-image concurrency check {@code app/cbl/COACTUPC.cbl} implements across the
 * pseudo-conversational gap -- it declares {@code WS-DATACHANGED-FLAG PIC X(1)} at L168, the condition
 * {@code DATA-WAS-CHANGED-BEFORE-UPDATE} at L521, and issues {@code SYNCPOINT ROLLBACK} at L4100 when
 * the check fails. A version has nothing to guard on a projection that never takes part in a write, so
 * none is declared here. What this gives up is dirty checking and the convenience of
 * merging a detached instance, and a select-only reader needs neither. What it buys is that an
 * accidental write fails at the mapping layer, immediately and at the offending code, rather than at
 * the database privilege, where it would surface as an opaque failure far from its cause. Both
 * controls are kept because the annotation fails during development and the privilege fails in
 * production, and neither substitutes for the other.
 *
 * <h2>Assumptions: no business rule is encoded on this type</h2>
 *
 * <p>The rules that consume these very columns -- the inclusive comparison of a balance against a
 * credit limit, and the equal-date comparison of a transaction date against an expiration date -- are
 * transaction-posting rules owned by the batch context and asserted against {@code app/cbl/CBTRN02C.cbl}
 * by the reference suite under {@code tests}, whose section 13 records both boundaries verbatim
 * alongside the four posting reject reasons. This type exposes the projected values and
 * derives nothing: no comparison, no threshold, no available-credit figure and no expiry predicate.
 * A member that computed one would put a posting rule in two places, and it would also forfeit the
 * single-line accessor form the project's Explainability rule allows only for an accessor that
 * carries no logic.
 *
 * <h2>Assumptions: no card member and no timestamp member</h2>
 *
 * <p>{@code app/cpy/CVACT02Y.cpy} appears in neither program's {@code COPY} set and no card dataset
 * appears in either job's input list, so the card relation is not among the ones this context reads;
 * the union of schemas behind these views is the ledger, the account and the reference schema. No
 * card status and no embossed name is projected here as a result. Separately, {@code ACCOUNT-RECORD}
 * declares no {@code PIC X(26)} field anywhere between L5 and L17, so this type carries no timestamp
 * member and, in consequence, no zone-bearing time type either.
 *
 * <h2>Assumptions: every citation names its declaring copybook or file description</h2>
 *
 * <p>A field name alone does not identify a layout in this baseline. {@code FD-ACCT-DATA} is
 * {@code PIC X(318)} at L63 of {@code app/cbl/CBSTM03B.CBL}, inside {@code FD TRNX-FILE}, and
 * {@code PIC X(289)} at L78 of the same program, inside {@code FD ACCT-FILE} -- one identifier, two
 * widths, one program. The baseline itself takes the same precaution: the report program qualifies
 * two moves with {@code OF TRAN-RECORD} at L365 and L367 of {@code app/cbl/CBTRN03C.cbl}. Every
 * citation in this file therefore names the copybook or the file description that declares the field,
 * and the layout mapped here is scoped to {@code app/cpy/CVACT01Y.cpy} alone.
 *
 * <h2>Documentation contract</h2>
 *
 * <p>Every member below carries a docstring whatever its visibility, because the project's
 * Explainability rule attaches its presence clause to every class and function and names no
 * visibility to exempt. The four rationale labels are used in the plural, unparenthesised, colon-
 * terminated forms that rule declares.
 */
@Entity
@Immutable
@Table(name = "v_accounts", schema = "reporting")
public class AccountView {

    /**
     * Declared width of the account status flag, in characters.
     *
     * <p>Assumptions: 1 is the width {@code ACCT-ACTIVE-STATUS PIC X(01)} declares at L6 of
     * {@code app/cpy/CVACT01Y.cpy}, and the owning migration carries the column at that same fixed
     * width.</p>
     */
    public static final int ACTIVE_STATUS_WIDTH = 1;

    /**
     * Declared width of the disclosure group identifier, in characters.
     *
     * <p>Assumptions: 10 is the width {@code ACCT-GROUP-ID PIC X(10)} declares at L16 of
     * {@code app/cpy/CVACT01Y.cpy}. The width is part of the contract rather than incidental, because
     * this value is the lookup key into the seeded disclosure groups and a differently padded key
     * would not match.</p>
     */
    public static final int GROUP_ID_WIDTH = 10;

    // Assumptions: the identifier is a magnitude and not eleven characters, because L5 of
    //              app/cpy/CVACT01Y.cpy declares ACCT-ID PIC 9(11), an unsigned numeric picture whose
    //              leading zeros are padding rather than data. It is the single-column key of the
    //              indexed file the baseline reads: app/cbl/CBSTM03B.CBL declares ACCESS MODE IS
    //              RANDOM at L51 and RECORD KEY IS FD-ACCT-ID at L52, one field and no composite, and
    //              FD-ACCT-ID is itself PIC 9(11) at L77. The join partner agrees on the type as well
    //              as the width: XREF-ACCT-ID is PIC 9(11) at L7 of app/cpy/CVACT03Y.cpy, and the
    //              statement generator keys this very read with it at L396 of app/cbl/CBSTM03A.CBL,
    //              taking the key length from that field at L398. Mapping it as a fixed-width string
    //              to mirror the file bytes was the alternative and is rejected: it would make the
    //              two sides of that keyed read disagree on type while agreeing on width.
    @Id
    @Column(name = "account_id", nullable = false, insertable = false, updatable = false)
    private Long accountId;

    // Trade-offs: the status stays one character wide and is neither narrowed to a two-valued flag nor
    //             raised to an enumeration. L6 declares PIC X(01), a domain of one character, and this
    //             type projects what the relation stores; a two-valued flag would have to decide what
    //             to do with any character outside the pair a reader happens to expect, and would
    //             silently answer for a value the baseline would have printed. The cost accepted is
    //             that a caller comparing this value writes the comparison itself and gets no
    //             compile-time check of the character it compares against.
    @Column(name = "active_status", length = ACTIVE_STATUS_WIDTH, nullable = false,
            insertable = false, updatable = false)
    private String activeStatus;

    // Assumptions: this is the one value of this row a statement prints, moved at L484 of
    //              app/cbl/CBSTM03A.CBL through the edit mask declared at L113. Its declared precision
    //              is the defining property of this projection: L7 declares PIC S9(10)V99, which is
    //              twelve significant digits at a scale of two, and L8, L9, L13 and L14 declare the
    //              same picture for the credit limit, the cash credit limit and the two cycle
    //              accumulators -- five fields at twelve digits. That is one digit WIDER than the
    //              money the sibling transaction projections carry, which is PIC S9(09)V99 at L29 of
    //              app/cpy/COSTM01.CPY and at L10 of app/cpy/CVTRA05Y.cpy, eleven digits; the report
    //              program corroborates the narrower width independently by declaring its page,
    //              account and grand totals all PIC S9(09)V99 at L134, L135 and L136 of
    //              app/cbl/CBTRN03C.cbl. The two precisions are never unified in either direction, so
    //              the precision and scale below are written as literals on this column rather than
    //              taken from a constant, and the converter nested at the foot of this file is
    //              declared on this type rather than shared: a constant or a converter reachable from
    //              an eleven-digit attribute is the exact mechanism by which one declared contract
    //              silently becomes the other.
    @Column(name = "curr_bal", precision = 12, scale = 2, nullable = false,
            insertable = false, updatable = false)
    @Convert(converter = AccountMoneyConverter.class)
    private Money currentBalance;

    // Assumptions: PIC S9(10)V99 at L8, so the same twelve digits at scale two as the balance above,
    //              and declared here as literals for the reason recorded there. It is held as the
    //              shared money type and never as a bare arbitrary-precision decimal and never as an
    //              IEEE-754 binary floating point quantity, because a binary floating point
    //              representation cannot hold an exact cent and has no margin whatsoever at twelve
    //              digits. That prohibition is not this file's preference: the layering rules fail a
    //              build over a binary floating point member anywhere beneath the analysed root.
    @Column(name = "credit_limit", precision = 12, scale = 2, nullable = false,
            insertable = false, updatable = false)
    @Convert(converter = AccountMoneyConverter.class)
    private Money creditLimit;

    // Assumptions: L10, L11 and L12 each declare PIC X(10), ten characters holding a year-month-day
    //              ordered date, and all three are mapped as calendar dates rather than as the ten
    //              characters the file stores. The change of type preserves behaviour because that
    //              ordering makes a lexical comparison over the characters agree with a chronological
    //              comparison over the dates, and the baseline relies on exactly that equivalence:
    //              app/jcl/TRANREPT.jcl declares TRAN-PROC-DT,305,10,CH at L42 -- ten CHARACTER bytes
    //              -- and then range-compares it against the character literals C'2022-01-01' at L43
    //              and C'2022-07-06' at L44, a character comparison used as a date range. Keeping the
    //              character form was the alternative and is rejected: a date member cannot be
    //              accidentally sliced, concatenated, or compared against a value formatted another
    //              way, and those are the failures the equivalence above quietly permits.
    @Column(name = "open_date", nullable = false, insertable = false, updatable = false)
    private LocalDate openDate;

    // Alternatives Considered: the baseline declares this field as ACCT-EXPIRAION-DATE at L11 of
    //              app/cpy/CVACT01Y.cpy, without the letter T in the middle of the word; the target
    //              names the column expiration_date and this member expirationDate, and the divergence
    //              is recorded in docs/architecture/data-model-and-schema-mapping.md. Carrying the
    //              baseline spelling through verbatim was the alternative and is rejected, because the
    //              migration plan settles the target column name and a projection that disagreed with
    //              the relation it is mapped over would not resolve. The divergence is recorded here as
    //              well as in that document because a reader searching the baseline for the target
    //              name will not find it, and a reader searching the target for the baseline name will
    //              not find that either. The copybook is unchanged and stays exactly as it is.
    @Column(name = "expiration_date", nullable = false, insertable = false, updatable = false)
    private LocalDate expirationDate;

    // Assumptions: PIC X(10) at L12, and the column is declared with no absent state to represent. The
    //              owning migration reaches that conclusion on evidence rather than on symmetry with
    //              the two dates above: the baseline account-update program edits this field through
    //              the same date-edit routine it applies to the expiration date, with no optional path
    //              and no branch that permits a blank, and every record of the seeded account dataset
    //              carries a real date here. A hydrated row therefore never presents this member as
    //              absent, and admitting an absent state would invent one the baseline has no encoding
    //              for. The member is still a reference type, so an instance read before the provider
    //              has populated it presents null, and each accessor below says which of the two
    //              situations its null belongs to rather than leaving that to inference.
    @Column(name = "reissue_date", nullable = false, insertable = false, updatable = false)
    private LocalDate reissueDate;

    // Assumptions: PIC X(10) at L16, mapped at its declared width for the reason recorded on
    //              GROUP_ID_WIDTH above. This is the value the interest calculation resolves a rate
    //              by, and the same identifier whose absence from the disclosure groups falls back to
    //              the DEFAULT group; that fallback is the reference context's rule and is not
    //              reproduced here, which is why this member is the raw projected key and not a
    //              resolved rate.
    @Column(name = "group_id", length = GROUP_ID_WIDTH, nullable = false,
            insertable = false, updatable = false)
    private String groupId;

    /**
     * Creates an empty instance for the persistence provider to populate.
     *
     * <p>Assumptions: the persistence specification requires an entity to declare a constructor
     * taking no arguments, and the provider calls this one before assigning the mapped members
     * directly. Visibility is protected rather than public because no caller outside this type's own
     * hierarchy has a use for a row that has not been populated yet.</p>
     */
    protected AccountView() {
        // Assumptions: the body is empty by design and not unfinished. Assigning defaults here would
        //              be overwritten immediately by the provider, and a default for the balance
        //              declared at L7 in particular would afterwards be indistinguishable from a
        //              balance that was actually read from the view.
    }

    /**
     * Creates an instance from values already projected by the view.
     *
     * <p>Alternatives Considered: validating the arguments here was the alternative and is rejected.
     * Every value originates in a relation the account context owns and has already passed that
     * context's own validation on the way in, so a guard here would make this reader an arbiter of
     * data it does not own, and its only possible effect would be to fail a read of a row the owning
     * context wrote deliberately. A reader that cannot present what the relation holds cannot
     * reproduce the statement the baseline printed from it.</p>
     *
     * @param accountId the account identifier declared {@code PIC 9(11)} at L5 of
     *     {@code app/cpy/CVACT01Y.cpy}, and the single-column key of the row
     * @param activeStatus the one-character account status declared {@code PIC X(01)} at L6, carried
     *     as the stored character rather than as an interpreted flag
     * @param currentBalance the current balance declared {@code PIC S9(10)V99} at L7, which is the one
     *     value of this row a statement prints
     * @param creditLimit the credit limit declared {@code PIC S9(10)V99} at L8, at the same twelve
     *     digits and scale of two as the balance
     * @param openDate the date the account was opened, declared {@code PIC X(10)} at L10
     * @param expirationDate the date the account expires, declared at L11 under the baseline spelling
     *     this file's member-level note records
     * @param reissueDate the date the account is reissued, declared {@code PIC X(10)} at L12
     * @param groupId the ten-character disclosure group identifier declared at L16, which is the
     *     lookup key a rate is resolved by
     */
    public AccountView(Long accountId, String activeStatus, Money currentBalance, Money creditLimit,
            LocalDate openDate, LocalDate expirationDate, LocalDate reissueDate, String groupId) {
        this.accountId = accountId;
        this.activeStatus = activeStatus;
        this.currentBalance = currentBalance;
        this.creditLimit = creditLimit;
        this.openDate = openDate;
        this.expirationDate = expirationDate;
        this.reissueDate = reissueDate;
        this.groupId = groupId;
    }

    /**
     * Returns the account identifier this row is keyed by, or null before the row is populated.
     *
     * @return the value of column {@code account_id}, never absent on a populated row because the
     *     column is the key of the relation the view projects
     */
    public Long getAccountId() {
        return accountId;
    }

    /**
     * Returns the one-character account status, or null before the row is populated.
     *
     * @return the value of column {@code active_status} as the single stored character, never absent
     *     on a populated row
     */
    public String getActiveStatus() {
        return activeStatus;
    }

    /**
     * Returns the current balance, which is the value a statement heading prints.
     *
     * @return the value of column {@code curr_bal} as an exact amount at a scale of two, never absent
     *     on a populated row, and null on an instance the provider has not populated
     */
    public Money getCurrentBalance() {
        return currentBalance;
    }

    /**
     * Returns the credit limit at the same twelve declared digits as the balance.
     *
     * @return the value of column {@code credit_limit} as an exact amount at a scale of two, never
     *     absent on a populated row, and null on an instance the provider has not populated
     */
    public Money getCreditLimit() {
        return creditLimit;
    }

    /**
     * Returns the date the account was opened.
     *
     * @return the value of column {@code open_date}, never absent on a populated row because the
     *     column admits no absent state, and null on an instance the provider has not populated
     */
    public LocalDate getOpenDate() {
        return openDate;
    }

    /**
     * Returns the date the account expires.
     *
     * @return the value of column {@code expiration_date}, whose name diverges from the baseline
     *     spelling as this file's member-level note records; never absent on a populated row, and null
     *     on an instance the provider has not populated
     */
    public LocalDate getExpirationDate() {
        return expirationDate;
    }

    /**
     * Returns the date the account is reissued.
     *
     * @return the value of column {@code reissue_date}, never absent on a populated row on the
     *     evidence recorded against that column, and null on an instance the provider has not
     *     populated
     */
    public LocalDate getReissueDate() {
        return reissueDate;
    }

    /**
     * Returns the disclosure group identifier a rate is resolved by.
     *
     * @return the value of column {@code group_id} at its declared width of ten characters, never
     *     absent on a populated row
     */
    public String getGroupId() {
        return groupId;
    }

    /**
     * Reports whether another object denotes the same account row as this one.
     *
     * <p>Assumptions: equality rests on the identifier alone, which is sound here because the
     * identifier is the key of the relation the view projects and therefore settles row identity by
     * itself. Comparing the projected values as well was the alternative and is rejected: the balance
     * at L7 changes by design across a posting run, as do the two cycle accumulators declared at L13
     * and L14 that feed it, so two loads of one row taken either side of a run would compare unequal
     * and make row identity depend on a figure that is meant to move.</p>
     *
     * @param other the object to compare against, which may be of any type and may be null
     * @return true when the argument is an account projection carrying an equal identifier, and false
     *     otherwise
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof AccountView that)) {
            return false;
        }
        return Objects.equals(this.accountId, that.accountId);
    }

    /**
     * Returns a hash consistent with the identifier-only equality declared above.
     *
     * @return the hash of the account identifier, and zero on an instance whose identifier has not
     *     been populated yet
     */
    @Override
    public int hashCode() {
        return Objects.hashCode(this.accountId);
    }

    /**
     * Returns a diagnostic rendering naming the identifier and the status, and no monetary figure.
     *
     * <p>Trade-offs: both monetary members are withheld from this rendering, which is the same
     * decision the sibling transaction projection records for its own amount. The account identifier
     * is included because the baseline prints it in full on every statement it produces, at L483 of
     * {@code app/cbl/CBSTM03A.CBL}, so it is not a value this system conceals. A balance and a credit
     * limit are different: this type carries five declared twelve-digit money fields' worth of
     * sensitivity in the two it projects, and a rendering is reached from a log statement, an
     * assertion message and a debugger alike, none of which is a place a customer's balance should
     * arrive by default. The cost accepted is that a reader cannot tell a row's balance from a log
     * line and has to ask for it deliberately through the accessor; the compensation is that no log
     * line written from this type carries a monetary figure at all.</p>
     *
     * @return a single-line rendering naming the type, the account identifier and the status
     */
    @Override
    public String toString() {
        return "AccountView[accountId=" + accountId + ", activeStatus=" + activeStatus + ']';
    }

    /**
     * Converts this projection's monetary columns between the shared money type and the exact decimal
     * form a column carries.
     *
     * <p>Purpose: the shared money type is final and its constructor is not public, so the persistence
     * provider cannot instantiate it and an attribute converter is the supported way to map it. Both
     * directions delegate to that type's own factory rather than reconstructing an amount, so the
     * scale and magnitude contract is enforced in one place.</p>
     *
     * <p>Assumptions: automatic application is deliberately switched off, so this converter binds only
     * where a column names it. Registering it to apply to every monetary attribute in the persistence
     * unit was the alternative and is rejected, because the amounts in this package are declared at two
     * different widths -- twelve significant digits at L7 and L8 of {@code app/cpy/CVACT01Y.cpy}
     * against eleven at L29 of {@code app/cpy/COSTM01.CPY} and L10 of {@code app/cpy/CVTRA05Y.cpy} --
     * and an automatic registration would reach both sets silently, which is precisely how one
     * declared contract turns into the other.</p>
     *
     * <p>Assumptions: this converter is declared on this type even though its body would read
     * identically on a sibling, because the shared money type's own magnitude ceiling is
     * {@code 9999999999.99}, which is exactly the domain {@code PIC S9(10)V99} declares and therefore
     * exactly this record's twelve digits. The factory below consequently bounds an amount at the very
     * picture this file maps, a property no eleven-digit sibling can claim of the same call. A
     * converter placed somewhere both could reach would present that coincidence as a shared
     * guarantee, when for a sibling it is merely a looser bound than its own column declares.</p>
     *
     * <p>Trade-offs: the cost of declaring it here is that a short conversion is stated once per
     * projection that needs one rather than once in the package. That duplication is accepted because
     * the two alternatives are worse: a shared file would add a ninth compilation unit to the closed
     * set of eight this package's charter declares, and an automatically applied converter would bind
     * to attributes nobody inspected when adding them.</p>
     */
    @Converter(autoApply = false)
    public static class AccountMoneyConverter implements AttributeConverter<Money, BigDecimal> {

        /**
         * Creates a converter instance for the persistence provider to use.
         *
         * <p>Assumptions: the provider instantiates a converter reflectively and requires a public
         * constructor taking no arguments. It is declared explicitly rather than left implicit so that
         * the requirement is visible to a reader of this file instead of being a property of the
         * language default.</p>
         */
        public AccountMoneyConverter() {
            // Assumptions: the body is empty because a converter holds no state, and holding state
            //              here would be unsafe: the provider shares one instance across every thread
            //              reading through this mapping.
        }

        /**
         * Converts a monetary value to the decimal form the column carries.
         *
         * <p>Assumptions: this direction stays reachable even though this projection has no write
         * path, because the provider also calls it when a monetary value is bound as a query
         * parameter. A converter that refused this direction would turn a legitimate comparison
         * against an amount into a failure at query time.</p>
         *
         * @param attribute the monetary value to convert, which may be null when the attribute is
         *     unset
         * @return the amount as a decimal at a scale of exactly two, and null when the argument is
         *     null
         */
        @Override
        public BigDecimal convertToDatabaseColumn(Money attribute) {
            return attribute == null ? null : attribute.amount();
        }

        /**
         * Converts the decimal form a column carries to a monetary value.
         *
         * <p>Assumptions: the column is declared at a scale of two, so the factory's rounding is not
         * reached on a well-formed row. The factory is still called rather than bypassed, because it
         * is the single place the scale and magnitude contract is enforced, and a view presenting an
         * unexpected scale or an over-wide amount should be refused at the read rather than carried
         * onward into a printed statement.</p>
         *
         * @param dbData the decimal value read from the column, which may be null when the column is
         *     absent from the result
         * @return the amount as a monetary value at a scale of exactly two, and null when the argument
         *     is null
         * @throws ArithmeticException if the value read is wider than the twelve declared digits the
         *     shared money type admits, which names a view definition to reconcile with
         *     {@code app/cpy/CVACT01Y.cpy} rather than an amount to round
         */
        @Override
        public Money convertToEntityAttribute(BigDecimal dbData) {
            return dbData == null ? null : Money.of(dbData);
        }
    }
}
