package com.carddemo.batch.domain;

import com.carddemo.common.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Maps the 350-byte unposted daily-transaction record onto {@code ledger.daily_transactions} for the
 * batch bounded context, as an input stream this module reads and never writes.
 *
 * <p>A row is one transaction that has been extracted and loaded but has not yet been validated or
 * posted. It is the driving input of two migrated jobs -- the daily-transaction preflight and
 * transaction posting -- and each row leaves the feed in exactly one of two directions: it becomes a
 * posted transaction, or it becomes a rejected one. Nothing in this module updates it on the way
 * out, and the section on the read-only boundary below sets out the evidence for that rather than
 * asserting it.</p>
 *
 * <h2>The field set, and how it is derived</h2>
 *
 * <p>The contract is {@code 01 DALYTRAN-RECORD} at {@code app/cpy/CVTRA06Y.cpy:4-18}. The copybook
 * is normative under the migration plan's transformation rule T1, so a field's {@code PICTURE}
 * clause decides its column type, its Java type and its byte offset; the thirteen declarations below
 * are therefore the specification rather than a provenance note. The SQL column shown for each is
 * the column that physically exists, and the section on schema ownership explains why that
 * distinction carries weight here.</p>
 *
 * <p>The record is 350 bytes and the offset arithmetic is written out once, because every length,
 * precision and scale decision below is derived from it and an unnoticed shortfall shifts every
 * field after the point it occurs. Reading {@code app/cpy/CVTRA06Y.cpy:5-18} in order and
 * accumulating the declared widths, with a zoned {@code S9(09)V99} occupying 11 bytes in
 * {@code DISPLAY} usage: 0 + 16 = 16, + 2 = 18, + 4 = 22, + 10 = 32, + 100 = 132, + 11 = 143,
 * + 9 = 152, + 50 = 202, + 50 = 252, + 10 = 262, + 16 = 278, + 26 = 304, + 26 = 330, + 20 = 350.
 * The three offsets that matter most downstream fall out of that sum as 262 for the card number, 278
 * for the origination stamp and 304 for the processing stamp.</p>
 *
 * <table border="1">
 *   <caption>Field derivation from {@code app/cpy/CVTRA06Y.cpy}, with the physical column</caption>
 *   <tr><th>Copybook field</th><th>Line</th><th>Offset</th><th>PICTURE</th>
 *       <th>Column</th><th>SQL type</th><th>Java type</th></tr>
 *   <tr><td>{@code DALYTRAN-ID}</td><td>5</td><td>0</td><td>{@code X(16)}</td>
 *       <td>{@code transaction_id}</td><td>{@code CHAR(16)}</td><td>{@code String}</td></tr>
 *   <tr><td>{@code DALYTRAN-TYPE-CD}</td><td>6</td><td>16</td><td>{@code X(02)}</td>
 *       <td>{@code type_cd}</td><td>{@code CHAR(2)}</td><td>{@code String}</td></tr>
 *   <tr><td>{@code DALYTRAN-CAT-CD}</td><td>7</td><td>18</td><td>{@code 9(04)}</td>
 *       <td>{@code category_cd}</td><td>{@code CHAR(4)}</td><td>{@code String}</td></tr>
 *   <tr><td>{@code DALYTRAN-SOURCE}</td><td>8</td><td>22</td><td>{@code X(10)}</td>
 *       <td>{@code source}</td><td>{@code CHAR(10)}</td><td>{@code String}</td></tr>
 *   <tr><td>{@code DALYTRAN-DESC}</td><td>9</td><td>32</td><td>{@code X(100)}</td>
 *       <td>{@code description}</td><td>{@code VARCHAR(100)}</td><td>{@code String}</td></tr>
 *   <tr><td>{@code DALYTRAN-AMT}</td><td>10</td><td>132</td><td>{@code S9(09)V99}</td>
 *       <td>{@code amount}</td><td>{@code NUMERIC(11,2)}</td><td>{@code BigDecimal}</td></tr>
 *   <tr><td>{@code DALYTRAN-MERCHANT-ID}</td><td>11</td><td>143</td><td>{@code 9(09)}</td>
 *       <td>{@code merchant_id}</td><td>{@code BIGINT}</td><td>{@code Long}</td></tr>
 *   <tr><td>{@code DALYTRAN-MERCHANT-NAME}</td><td>12</td><td>152</td><td>{@code X(50)}</td>
 *       <td>{@code merchant_name}</td><td>{@code VARCHAR(50)}</td><td>{@code String}</td></tr>
 *   <tr><td>{@code DALYTRAN-MERCHANT-CITY}</td><td>13</td><td>202</td><td>{@code X(50)}</td>
 *       <td>{@code merchant_city}</td><td>{@code VARCHAR(50)}</td><td>{@code String}</td></tr>
 *   <tr><td>{@code DALYTRAN-MERCHANT-ZIP}</td><td>14</td><td>252</td><td>{@code X(10)}</td>
 *       <td>{@code merchant_zip}</td><td>{@code CHAR(10)}</td><td>{@code String}</td></tr>
 *   <tr><td>{@code DALYTRAN-CARD-NUM}</td><td>15</td><td>262</td><td>{@code X(16)}</td>
 *       <td>{@code card_num}</td><td>{@code CHAR(16)}</td><td>{@code String}</td></tr>
 *   <tr><td>{@code DALYTRAN-ORIG-TS}</td><td>16</td><td>278</td><td>{@code X(26)}</td>
 *       <td>{@code orig_ts}</td><td>{@code TIMESTAMP(6)}</td><td>{@code LocalDateTime}</td></tr>
 *   <tr><td>{@code DALYTRAN-PROC-TS}</td><td>17</td><td>304</td><td>{@code X(26)}</td>
 *       <td>{@code proc_ts}</td><td>{@code TIMESTAMP(6)}</td><td>{@code LocalDateTime}</td></tr>
 *   <tr><td>{@code FILLER}</td><td>18</td><td>330</td><td>{@code X(20)}</td>
 *       <td>dropped</td><td>none</td><td>none</td></tr>
 * </table>
 *
 * <h2>This layout is the posted record's layout, and that is load-bearing</h2>
 *
 * <p>Assumptions: {@code app/cpy/CVTRA06Y.cpy} and {@code app/cpy/CVTRA05Y.cpy} declare the same
 * thirteen fields, with the same {@code PICTURE} clauses, in the same order, <b>at the same line
 * numbers 4 through 18</b>. The only textual difference between the two files is the field-name
 * prefix, {@code DALYTRAN-} where the other reads {@code TRAN-}, and both headers declare
 * {@code RECLN = 350} at line 2 of each. This is the single fact a later reader is most likely to
 * doubt, so it is recorded rather than left to be rediscovered, and it is the premise the whole
 * mapping rests on: <b>every column name, SQL type, length, precision, scale and Java type below is
 * identical to {@link Transaction}, field for field.</b> A disagreement between the two types is a
 * defect in one of them and not a difference to be reconciled.</p>
 *
 * <p>Assumptions: two operations in the posting program depend on that identity directly, which is
 * why it is a contract and not a coincidence. {@code app/cbl/CBTRN02C.cbl:425-435} moves the fields
 * across pairwise, one statement per field and no reformatting anywhere, because each source field
 * and its target are the same picture at the same offset. And
 * {@code app/cbl/CBTRN02C.cbl:447} copies the <b>whole record</b> into the reject area with a single
 * statement, {@code MOVE DALYTRAN-RECORD TO REJECT-TRAN-DATA}, which is only correct while this
 * record is exactly the 350 bytes that area reserves. A divergence between this type and
 * {@link Transaction} would therefore not stay local to one of them: it would reach the reject
 * stream, whose bytes are compared against committed expectations under
 * {@code tests/golden/posting}.</p>
 *
 * <p>Assumptions: the trailing {@code FILLER} at {@code app/cpy/CVTRA06Y.cpy:18} is padding to the
 * declared record length rather than data, so transformation rule T1 drops it and requires the drop
 * be recorded. It is 20 bytes beginning at offset 330, and 330 plus 20 is the declared 350, so
 * recording the width here keeps the layout reconstructible from this type alone without opening the
 * copybook.</p>
 *
 * <h2>Where the three critical offsets are corroborated</h2>
 *
 * <p>Assumptions: the offsets 262, 278 and 304 are each confirmed by more than the summation above,
 * and the corroborating artifacts describe the <b>posted</b> record rather than this one. They apply
 * here <i>because</i> the two layouts are identical in the sense established above -- that inference
 * is what makes the citation legitimate, so it is stated rather than assumed.
 * {@code app/jcl/TRANREPT.jcl:41-42} declares two of the fields to the sort utility as
 * {@code TRAN-CARD-NUM,263,16,ZD} and {@code TRAN-PROC-DT,305,10,CH}, in <b>one-based</b> positions
 * that are zero-based 262 and 304. {@code app/jcl/TRANIDX.jcl:27} defines an alternate index over the
 * same record as {@code KEYS(26 304)}, a 26-byte key beginning at zero-based offset 304, which is
 * exactly the processing stamp, and {@code app/jcl/TRANIDX.jcl:30} independently declares
 * {@code RECORDSIZE(350,350)}. The sibling {@link Transaction} reaches the same three numbers from
 * the same sources and its own declaration carries the argument in full, so it is cited here rather
 * than restated.</p>
 *
 * <h2>Schema ownership, and why this mapping declares no DDL</h2>
 *
 * <p><b>{@code ledger.daily_transactions} is owned by {@code transaction-service}, not by this
 * module.</b> The only table this module owns is {@code batch.batch_run}. This module reaches the
 * feed under the scoped cross-schema grant recorded at the migration plan's section 0.4.1.3, and its
 * access to <i>this</i> table is narrower still than that grant allows: it reads, and the read-only
 * boundary below is what closes the rest.</p>
 *
 * <p>Assumptions: this mapping is DDL-passive, which is a hard constraint rather than a preference.
 * It names no index, no unique constraint, no explicit column definition and no key generation
 * strategy, because the table and its columns already exist by the time this type is loaded. They
 * are created by the owning service's migration,
 * {@code services/transaction-service/src/main/resources/db/migration/V1__ledger.sql}, whose
 * {@code CREATE TABLE ledger.daily_transactions} declares all thirteen copybook columns plus the
 * target-side ingestion sequence, and its only constraint is the primary key over that sequence. This module's persistence provider is never permitted to emit DDL: its schema
 * setting is {@code validate}, which asserts the mapping against the shape already present and emits
 * nothing, and the rationale carried beside that key in this module's {@code application.yml}
 * explains why this module in particular cannot use anything weaker -- seven of its eight mappings
 * describe tables it does not own, and its own migrations are scoped to {@code batch}, so no
 * migration of its own could ever resolve a drift in one of them. The annotations below therefore
 * <b>describe</b> that shape rather than request it. An annotation that requested DDL would ask a
 * module holding no authority over the {@code ledger} schema to create or alter another service's
 * objects, and it would surface as a validation failure or a permission error, neither of which
 * names the actual mistake.</p>
 *
 * <p>Assumptions: the SOURCE feed has <b>no record key</b>, and the physical table therefore asserts
 * no uniqueness over any copybook column. {@code app/cbl/CBTRN02C.cbl:29-31} selects the feed as
 * {@code ORGANIZATION IS SEQUENTIAL} with {@code ACCESS MODE IS SEQUENTIAL} and declares no record key
 * at all, and {@code app/jcl/POSTTRAN.jcl:30-31} supplies it as the physical sequential dataset
 * {@code AWS.M2.CARDDEMO.DALYTRAN.PS}. The posting job reads it front to back and never keys into
 * it, so a feed repeating an identifier is a feed the baseline posts twice.</p>
 *
 * <p>Assumptions: the table nevertheless carries a primary key, {@code pk_daily_transactions} over the
 * target-side column {@code ingest_seq}, and the distinction between that and a key over a copybook
 * column is the whole point. A relational table is a heap with no inherent order, so a scan that
 * resumes must order by something; the only copybook candidate is the transaction identifier, which
 * this feed does not promise to be unique. The ingestion sequence supplies the one property the
 * sequential file had and a heap does not -- a total order over physical arrival -- so every
 * occurrence stays distinct and reachable, told apart by the provider and by any consumer paging
 * the feed in a total order. This mapping does not create that column: the declaration is
 * {@code V1__ledger.sql}'s, and the annotations below only describe it. The identifier declaration
 * below explains what each column now denotes.</p>
 *
 * <h2>Why the mapping is local rather than borrowed</h2>
 *
 * <p>Alternatives Considered: a Maven dependency on {@code transaction-service}, so that the entity
 * it already declares over this table could be reused instead of a second mapping being written.
 * Rejected on two independent grounds. A service module importing another service module's
 * {@code domain} package is forbidden outright by the migration plan's section 0.5.3.1, and the
 * prohibition belongs to the layering rules that the {@code architecture-rules} Surefire execution in
 * {@code services/pom.xml} selects by the simple name {@code LayeringRulesTest}, so the import fails
 * a build rather than drawing a review comment. Independently of that test, a compile-time dependency
 * between two independently deployable services reintroduces exactly the coupling that bounded
 * contexts exist to remove: the two would then have to be built, versioned and released together.</p>
 *
 * <p>Alternatives Considered: hoisting one shared mapping up into {@code common-lib} so a single type
 * could serve every module that touches this table. Rejected because {@code common-lib} ships no
 * persistence contract at all by design -- neither the persistence starter nor a driver appears in
 * its POM -- so {@code jakarta.persistence} is simply not on its classpath. Adding it there to serve
 * this module would place an entity mapping in the one artifact every other module depends on,
 * making a schema change in one bounded context a rebuild of all nine.</p>
 *
 * <p>Trade-offs: two mappings over one table can drift and no compiler will notice. What is accepted
 * in exchange is that the drift is loud rather than silent, because both are asserted against the
 * same physical schema when their process starts, so a mapping naming a column the schema does not
 * have fails before a row is read. Compile-time agreement between the two is genuinely given up, and
 * that is the price of independent deployability: <b>this module and {@code transaction-service}
 * agree through the physical schema and never through code.</b></p>
 *
 * <p>Assumptions: because the agreement runs through the schema, the physical column decides every
 * name and type below wherever the copybook's own spelling and the column could differ. Four are
 * worth flagging for a reader arriving from the copybook: the identifier column is
 * {@code transaction_id} and not {@code tran_id}; the category code is {@code category_cd} and is a
 * <b>character</b> column rather than a small integer, despite its {@code 9(04)} picture; the source
 * column is {@code source}, with the record prefix dropped as it is on every other field; and the
 * postal code is fixed width rather than variable. Each is argued at its own declaration below,
 * against the alternative that was available, and each matches {@link Transaction} exactly.</p>
 *
 * <h2>How this feed is consumed, and the one property a reader must preserve</h2>
 *
 * <p>Assumptions: both consumers scan this table sequentially and neither keys into it, which is
 * what constrains any repository written against this type.</p>
 *
 * <ul>
 *   <li><b>The preflight</b> at {@code app/cbl/CBTRN01C.cbl:155-197} reads the feed record by record
 *       and, per record, performs exactly two operations: the cross-reference lookup at
 *       {@code app/cbl/CBTRN01C.cbl:172} and the account read at
 *       {@code app/cbl/CBTRN01C.cbl:176}. It opens six files at
 *       {@code app/cbl/CBTRN01C.cbl:157-162} and closes six at
 *       {@code app/cbl/CBTRN01C.cbl:188-193}, yet the loop body never reads the customer, card or
 *       transaction files at all. That asymmetry between what is opened and what is read is the
 *       evidence that this package needs neither a customer nor a card mapping, and it is recorded
 *       here because the open list is the misleading half.</li>
 *   <li><b>Posting</b> at {@code app/cbl/CBTRN02C.cbl:202-219} reads the same stream, validates each
 *       record at {@code app/cbl/CBTRN02C.cbl:210}, and then either posts it at
 *       {@code app/cbl/CBTRN02C.cbl:212} or increments a reject count at
 *       {@code app/cbl/CBTRN02C.cbl:214} and writes a reject at
 *       {@code app/cbl/CBTRN02C.cbl:215}.</li>
 * </ul>
 *
 * <p>Assumptions: because both are full sequential scans rather than keyed access, a repository query
 * over this type is an ordered scan, and <b>the order has to be deterministic.</b> Two compared
 * outputs depend on it: the reject stream is written in the order rejects are encountered, and the
 * interest job derives a transaction identifier per generated row, so a scan whose order varies
 * between runs shifts the contents of files that are compared byte for byte and the comparison then
 * fails for a reason that has nothing to do with logic. Ordering on the identifier column reproduces
 * the key order the baseline's sequential read presents, and it is available without an index
 * because correctness here rests on the ordering being stated rather than on it being cheap.</p>
 *
 * @see Transaction
 */
@Entity
// WHY : Assumptions: the read-only boundary is enforced at the type rather than left to the absence
//       of mutators below, and the evidence that it IS read-only is documentary rather than
//       stylistic: no baseline program writes this dataset. app/cbl/CBTRN01C.cbl:99 copies
//       app/cpy/CVTRA06Y.cpy and reads the file sequentially; app/cbl/CBTRN02C.cbl copies the same
//       book and reads the same file as its driving input; app/cbl/CBTRN02C.cbl:29-31 declares it
//       sequential with no record key, and both programs open it for input only. The dataset is
//       produced upstream instead -- in the target by the extract-and-load package, which decodes
//       app/data/ASCII/dailytran.txt against the layout it declares as DALYTRAN_LAYOUT at
//       data-migration/src/carddemo_migration/copybook/layouts.py:2145-2163, or by a generation of
//       the daily-transaction generation group.
//       Declaring the type immutable makes that contract structural: the provider excludes it from
//       dirty checking altogether, so a member mutated inside a managed context produces no update
//       statement at all rather than quietly altering the input stream part-way through a run.
// WHY : Alternatives Considered: relying solely on the absence of mutators, or marking each column
//       insertable and updatable false. The first prevents mutation only through the public API and
//       leaves reflective and provider-driven paths open, and it states the intent nowhere a reader
//       can see it. The second has to be repeated on every column, so a column added later would be
//       writable by default and the guarantee would decay by omission. The type-level form is one
//       statement that cannot be partially applied.
// WHY : Trade-offs: this is a provider-specific annotation rather than a portable one, accepted
//       because every module under services/ runs on the same provider through Spring Data JPA and
//       no second provider is in scope. The cost is that a provider change would have to revisit
//       this line; the compensation is that the guarantee covers the reflective and framework paths
//       the portable alternatives above do not reach.
@Immutable
// WHY : Alternatives Considered: leaving the table name unqualified and letting the pinned
//       connection search path resolve it. Declined because this module spans four schemas at three
//       different grant levels, so one search path cannot express which level applies to which read,
//       and the schema this mapping names is one the module does not own. Naming it on the annotation
//       puts the grant boundary where a reader of the entity finds it, instead of requiring a trip to
//       the connection configuration to discover that this read crosses a context.
@Table(name = "daily_transactions", schema = "ledger")
public class DailyTransaction {

    // WHY : Alternatives Considered: a persistence association from this row to the account, the
    //       cross-reference or the category balance it relates to, so that a caller could traverse to
    //       them instead of the job resolving each one itself. Rejected because it would misdescribe
    //       what the baseline does: every one of those reachings is an explicit keyed read, moving a
    //       value into a key and issuing one read against one file --
    //       app/cbl/CBTRN02C.cbl:380-392 for the cross-reference, :393-401 for the account and
    //       :469-471 for the category balance -- rather than navigation across a graph. Modelling
    //       them as associations would also let a lazy traversal cross a schema boundary from inside
    //       an unrelated read, on a schema this module reaches only under a scoped grant, so the
    //       breadth of a query would stop being visible at the call site that issued it.
    // WHY : Trade-offs: the cost is that a caller wanting the related row has to ask for it, which is
    //       more code at the call site than a field dereference. That is accepted because it keeps
    //       every read that crosses a context countable, and because the three lookups above do not
    //       share a cardinality -- two resolve a single row and the third may find none and then
    //       create one -- so a single association style could not have expressed all three anyway.

    // WHY : Assumptions: the offset index below is repeated here as a lookup a maintainer editing one
    //       declaration can read without leaving the field block, even though the class
    //       documentation above already carries the running-sum derivation and the argument for each
    //       type. The duplication is deliberate: a maintainer changing one width has to reconcile
    //       the whole table to 350, and sending them to the class Javadoc for the numbers is how a
    //       width gets changed against a remembered layout instead of a read one.
    //       transaction_id 0:16 | type_cd 16:2 | category_cd 18:4 | source 22:10 |
    //       description 32:100 | amount 132:11 | merchant_id 143:9 | merchant_name 152:50 |
    //       merchant_city 202:50 | merchant_zip 252:10 | card_num 262:16 | orig_ts 278:26 |
    //       proc_ts 304:26 | dropped FILLER 330:20. Last offset plus its width is 350, the declared
    //       record length, so a change that does not still reconcile to 350 is an error.
    // WHY : Assumptions: the three offsets a downstream artifact names directly are 262, 278 and 304,
    //       and each is corroborated outside this file rather than taken from the copybook alone: app/jcl/TRANREPT.jcl:41-42 declares two of them to the
    //       sort utility in one-based form, and app/jcl/TRANIDX.jcl:27 keys an alternate index at
    //       zero-based 304. Both artifacts name the POSTED record rather than this one, and they are
    //       admissible here only because the two copybook layouts are identical in the sense the
    //       class documentation establishes -- that inference is the whole basis of the citation, so
    //       it is stated rather than left implicit. Transaction records the same corroboration
    //       against the same sources and is cited rather than restated.
    // WHY : Assumptions: a fourth source agrees on the WHOLE table rather than on three offsets, and
    //       it was derived independently of this file, which is what makes it corroboration: the extract-and-load package declares this record as
    //       DALYTRAN_LAYOUT at data-migration/src/carddemo_migration/copybook/layouts.py:2145-2163,
    //       giving the same thirteen offsets and widths in the same order. Two of its entries also
    //       confirm decisions taken below rather than merely the geometry: it types the amount as a
    //       signed zoned value of nine integer digits and two decimals, and it marks the card number
    //       sensitive. That package additionally derives the reject layout by extending this one, at
    //       data-migration/src/carddemo_migration/copybook/layouts.py:2349, which is the same
    //       relationship app/cbl/CBTRN02C.cbl:447 expresses by copying the whole record.

    /**
     * The transaction identifier the feed supplies, 16 characters with significant leading zeros.
     */
    // WHY : Assumptions: alphanumeric and not numeric. The picture is X(16) at
    //       app/cpy/CVTRA06Y.cpy:5, and the values justify the declaration rather than merely
    //       following it: every identifier in app/data/ASCII/dailytran.txt is zero-filled to its full
    //       width, so a numeric mapping would render the same identifier with a different number of
    //       digits than the record carries and the value would no longer match the bytes it came
    //       from. It is also the value posting copies verbatim into the posted record at
    //       app/cbl/CBTRN02C.cbl:425, and the value the preflight names when it reports a record it
    //       could not resolve at app/cbl/CBTRN01C.cbl:181-183, so the two consumers that mention it
    //       both treat it as an opaque label.
    /**
     * The database-assigned ingestion sequence distinguishing one physical occurrence from another.
     *
     * <p>Assumptions: this is the mapped identity and it corresponds to
     * {@code ledger.daily_transactions.ingest_seq}, which the owning migration declares as
     * {@code pk_daily_transactions}. It reflects arrival order, so ordering by it reproduces the order
     * the baseline's sequential read covers the dataset in. It is non-null on every row read back.</p>
     *
     * <p>Assumptions: this mapping does not create the column. The declaration is the owning
     * migration's -- {@code ingest_seq BIGINT GENERATED BY DEFAULT AS IDENTITY} -- so this type
     * describes a column that exists rather than asking another service's schema to grow one.</p>
     *
     * <p>Refactoring Rationale: an earlier revision mapped {@code transactionId} as the identity and
     * recorded the consequence honestly -- that two rows repeating an identifier would share one entity
     * identity, so a consumer "must treat a scan of this feed as a stream and must not rely on entity
     * identity to tell two rows apart". The caveat was accurate but it was not sufficient, because the
     * chunked reader that scans this feed did exactly what the caveat forbade: it ordered and resumed on
     * that same non-unique column, so a duplicate identifier straddling a chunk boundary was skipped
     * outright. A caveat cannot correct a cursor. The earlier note also rejected a surrogate on the
     * ground that the column did not exist in the physical table; the owning migration now declares it,
     * so that objection is spent.</p>
     */
    // WHY : Assumptions: the ordinal is the row's position in the source stream, so ordering by it
    //       reproduces the order `READ ... NEXT RECORD` visited at app/cbl/CBTRN02C.cbl:202-219.
    //       That is the order this module's own posting loop depends on, because the reject stream's
    //       sequence is the feed's sequence restricted to the rejected records.
    // WHY : Assumptions: no generation strategy is declared on this mapping even though the column is
    //       an identity column, because this type is @Immutable and read-only here -- this module
    //       scans the feed and never inserts into it. Declaring IDENTITY generation would describe a
    //       write path that does not exist in this module. The owning service's mapping, which is the
    //       one a loader uses, declares the strategy.
    // WHY : Alternatives Considered: declaring @GeneratedValue(IDENTITY) here as well, on the ground
    //       that it costs nothing on a read path and states where the value comes from. Rejected,
    //       and the sibling test asserts the absence so the decision cannot be undone silently: an
    //       annotation on this mapping would describe an insert this module cannot perform, and a
    //       reader auditing which module writes the feed would find two mappings claiming the write
    //       path and no way to tell which one has it. What the alternative was right about is
    //       carried instead by the sentence above -- the value is database-assigned and never
    //       application-supplied here -- which states the fact without asserting the capability.
    // WHY : Assumptions: mapping the ordinal as the identity does NOT displace the identifier the
    //       feed supplies. That value is still mapped, still compared by the committed parity
    //       expectations, and still the value posting copies verbatim at app/cbl/CBTRN02C.cbl:425.
    @Id
    @Column(name = "ingest_seq", updatable = false)
    private Long ingestSeq;

    // WHY : Assumptions: nullability is left unstated while the sibling Transaction declares the same
    //       column NOT NULL, and the difference is real: on the posted master the column IS the
    //       primary key, whereas here the owning migration deliberately declines to constrain it
    //       because the sequential feed has no record key and a legitimate extract may repeat an
    //       identifier. A DDL-passive mapping must not assert a constraint the schema does not carry.
    // WHY : Assumptions: this column is NO LONGER the entity identity, which is what makes a repeated
    //       identifier representable rather than merely documented. Two rows sharing this value are
    //       two distinct entities, told apart by the ingestion sequence above, so a scan reaches both
    //       -- and the identifier the feed supplies is still carried verbatim, which is what the
    //       committed parity expectations compare.
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "transaction_id", length = 16, updatable = false)
    private String transactionId;

    /**
     * The transaction type code the feed supplies, two characters, one half of the category-balance
     * key.
     */
    // WHY : Assumptions: a fixed-width code compared blank-padded, from PIC X(02) at
    //       app/cpy/CVTRA06Y.cpy:6, so the column is CHAR rather than variable width and the
    //       provider is told so explicitly. The current extract carries only the values 01 and 03,
    //       which is a property of one extract and not a domain this mapping may narrow to.
    // WHY : Assumptions: this field and the category code that follows are declared differently in
    //       the copybook -- X(02) here against 9(04) there -- yet BOTH map to character columns, and
    //       that harmonisation is deliberate rather than an oversight. The argument for treating a
    //       numeric picture as a code is made at that field; what matters here is that the two are
    //       always used together, as the two non-account components of the category-balance key at
    //       app/cbl/CBTRN02C.cbl:470-471, so a reader comparing them should expect the same Java and
    //       SQL treatment on both and find the copybook's asymmetry resolved rather than propagated.
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "type_cd", length = 2)
    private String typeCd;

    /**
     * The transaction category code the feed supplies, four characters, the other half of the
     * category-balance key.
     */
    // WHY : Alternatives Considered: mapping this to a small integer, which its PIC 9(04) at
    //       app/cpy/CVTRA06Y.cpy:7 invites. Rejected because the picture describes the characters the
    //       field admits and not the field's role: this is a code, never an operand, and it is never
    //       summed, compared for magnitude or incremented anywhere. The values settle it -- every
    //       category code in app/data/ASCII/dailytran.txt is written zero-filled to four characters,
    //       and app/cbl/CBACT04C.cbl:483 moves the two-character literal '05' into a field of this
    //       picture, which arrives zero-filled as well. A numeric mapping would discard that filling
    //       as insignificant, so a code the record carries in four characters would come back in one
    //       or two and would no longer match the bytes it was read from.
    // WHY : Assumptions: the type has to agree with the category component of the category-balance
    //       mapping in this package and with the category component of DisclosureGroup's key, because
    //       app/cbl/CBTRN02C.cbl:469-471 composes the balance key from the account identifier, the
    //       type code and this value, and a lookup whose key component is typed differently on the
    //       two sides misses rather than failing loudly. DisclosureGroup already declares its
    //       category component as a four-character column, so this declaration matches an existing
    //       decision in the same package rather than introducing a second convention.
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "category_cd", length = 4)
    private String categoryCd;

    /**
     * The originating channel the feed names for the transaction, ten characters, blank-padded.
     */
    // WHY : Alternatives Considered: retaining the record prefix and naming the column tran_source,
    //       to keep the Java member and the copybook field visibly related. Rejected because the
    //       physical column is source, and a mapping names what exists -- the owning migration writes
    //       the identifier unquoted, which PostgreSQL accepts because it classifies that word as
    //       unreserved, a keyword only inside the MERGE statement's own grammar. The prefix is
    //       therefore dropped here exactly as it is on every other field, which is also the decision
    //       the sibling Transaction records for the same column; the two must stay identical because
    //       app/cbl/CBTRN02C.cbl:428 copies this field straight across into the posted record with no
    //       transformation at all.
    // WHY : Assumptions: fixed width rather than variable, from PIC X(10) at
    //       app/cpy/CVTRA06Y.cpy:8, and the padding is part of how the value is compared: the
    //       extract writes a ten-character field with the label left-justified and the remainder
    //       blank, so a variable-width column would make two spellings of one label -- padded and
    //       trimmed -- compare unequal.
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "source", length = 10)
    private String source;

    /**
     * The free-text description the feed carries for the transaction, up to 100 characters.
     */
    // WHY : Assumptions: descriptive rather than a code, from PIC X(100) at
    //       app/cpy/CVTRA06Y.cpy:9, so its trailing blanks are padding to the declared width rather
    //       than data and the column is variable width. That is the one substantive difference from
    //       the codes above: nothing keys on this value, so nothing compares it blank-padded, and
    //       storing the padding would preserve bytes no consumer reads. Posting moves it across
    //       unaltered at app/cbl/CBTRN02C.cbl:429.
    @Column(name = "description", length = 100)
    private String description;

    /**
     * The signed transaction amount the feed supplies, exact to two decimal places.
     */
    // WHY : Assumptions: the precision is derived and not chosen. PIC S9(09)V99 at
    //       app/cpy/CVTRA06Y.cpy:10 is nine integer digits plus two decimal places, so eleven
    //       significant digits, and the column is NUMERIC(11,2) with the member held at scale 2.
    //       The sign occupies no byte of its own: the eleventh byte carries both the last digit and
    //       the sign as an overpunch, which is why the width is 11 and not 12.
    // WHY : Assumptions: this is the NARROWER of the two money precisions that meet in this package,
    //       and the distinction matters because the wider one's widest legal value does not fit here.
    //       Account-side money is PIC S9(10)V99 -- twelve display bytes -- at
    //       app/cpy/CVACT01Y.cpy:7-9 and :13-14, mapping to NUMERIC(12,2). The two cross at two
    //       places, and both run in one direction: app/cbl/CBTRN02C.cbl:547 adds THIS field into the
    //       wider running balance, and app/cbl/CBTRN02C.cbl:403-405 computes the over-limit
    //       projection as the cycle credit minus the cycle debit plus this amount into a working
    //       field that app/cbl/CBTRN02C.cbl:187 declares PIC S9(09)V99 -- the narrower picture. So
    //       the projection is evaluated at the transaction side's precision even though two of its
    //       three operands come from the account side, and widening this column would change the
    //       domain that projection is computed in. It is left at eleven digits for that reason.
    // WHY : Assumptions: the picture is signed and the sign is load-bearing rather than incidental.
    //       app/cbl/CBTRN02C.cbl:548-552 branches on the sign of this very field: a value at or above
    //       zero accumulates into the cycle CREDIT total and a value below zero accumulates into the
    //       cycle DEBIT total, added as the negative it already is rather than negated first. A
    //       negative amount is therefore the debit convention and not an error condition, so it must
    //       never be normalised, made absolute or rejected. This is not a theoretical case: decoding
    //       the sign overpunch of the eleventh byte across app/data/ASCII/dailytran.txt gives 250
    //       positive and 50 negative amounts in 300 records, so roughly one record in six depends on
    //       the sign surviving. tests/README.md section 5.2 records that the baseline harness must
    //       compile with EBCDIC sign handling because the default silently corrupts exactly these
    //       negatives, which is the same reason the decode boundary in the target is a single one:
    //       com.carddemo.common.codec.ZonedDecimalCodec owns it, and transformation rule T2 is why it
    //       is not re-declared here.
    // WHY : Assumptions: exact decimal at every hop, never an approximate binary representation. An
    //       IEEE-754 type cannot hold a value such as one tenth exactly, so a sum of amounts would
    //       drift from the committed expectations under tests/golden/posting by amounts too small to
    //       notice per record and large enough to notice per file. The scale is stated on the column
    //       so a re-read compares equal to what was written: BigDecimal equality is scale-sensitive,
    //       so an amount stored at a different scale would not equal a round-trip of itself.
    // WHY : Refactoring Rationale: NOT NULL, where an earlier revision of this mapping left the
    //       column nullable. `DALYTRAN-AMT PIC S9(09)V99` declares no absent state, and neither
    //       normative zoned codec produces one -- ZonedDecimalCodec and the ETL's
    //       carddemo_migration.copybook.zoned both refuse a blank or non-digit numeric body -- so a
    //       blank amount field fails the load rather than loading as null. The amount is also the one
    //       field of the feed record the posting validation cannot proceed without: app/cbl/CBTRN02C.cbl
    //       L403-L405 forms its trial balance from it before any other test runs.
    @Column(name = "amount", precision = 11, scale = 2, nullable = false)
    private BigDecimal amount;

    /**
     * Integer digit positions {@code DALYTRAN-AMT PIC S9(09)V99} declares, at
     * {@code app/cpy/CVTRA06Y.cpy:10}.
     *
     * <p>Assumptions: nine, which is what makes the persisted column {@code NUMERIC(11,2)} rather than
     * the {@code NUMERIC(12,2)} the widest reference money picture would need. It is named here because
     * the constructor bounds against it, and a literal at that one site would be a width with no
     * citation beside it.</p>
     */
    private static final int AMOUNT_INTEGER_DIGITS = 9;

    /**
     * The merchant identifier the feed supplies for the transaction, nine digits.
     */
    // WHY : Assumptions: a numeric identifier, from PIC 9(09) at app/cpy/CVTRA06Y.cpy:11, mapped to
    //       BIGINT as the migration plan's section 0.4.1.3 maps numeric identifiers generally. It is
    //       numeric rather than character here, unlike the transaction and card identifiers, because
    //       no leading zero is significant: every value in app/data/ASCII/dailytran.txt is a
    //       nine-digit number written without leading zeros.
    // WHY : Assumptions: no lower bound is asserted on this value and none may be added. Zero is a
    //       legitimate merchant identifier in this system -- app/cbl/CBACT04C.cbl:491 writes exactly
    //       zero into the merchant identifier of every transaction the interest job generates -- so a
    //       positivity or non-zero constraint would reject records the baseline produces. A
    //       constraint that narrows the source domain is a behavioural change, and this mapping makes
    //       none.
    @Column(name = "merchant_id")
    private Long merchantId;

    /**
     * The merchant name the feed carries, up to 50 characters.
     */
    // WHY : Assumptions: these four members -- the merchant name, city and postal code together with
    //       the description above -- are descriptive text rather than keys, from PIC X(50), X(50) and
    //       X(10) at app/cpy/CVTRA06Y.cpy:12-14. Two consequences follow and are stated once here for
    //       all of them. Trailing blanks are padding to the declared width rather than data, which is
    //       why the two name fields are variable width. And an entirely blank value is legitimate
    //       rather than exceptional: app/cbl/CBACT04C.cbl:492-494 writes blanks into all three
    //       merchant fields of every generated interest transaction, so blankness is a value this
    //       system produces on purpose. It must therefore not be normalised to an absent value, and
    //       no emptiness constraint may be placed on any of them. That every one of the 300 records
    //       in the current extract populates all four is a property of that extract and not a
    //       contract this mapping may tighten into one.
    @Column(name = "merchant_name", length = 50)
    private String merchantName;

    /**
     * The merchant city the feed carries, up to 50 characters.
     */
    @Column(name = "merchant_city", length = 50)
    private String merchantCity;

    /**
     * The merchant postal code the feed carries, ten characters, blank-padded.
     */
    // WHY : Alternatives Considered: a variable-width column, as the two descriptive fields above
    //       use, or a numeric mapping as the merchant identifier uses. Both rejected. A postal code
    //       can begin with a zero, so a numeric mapping would drop a leading digit and change the
    //       code; and the physical column is fixed width, which is what the extract writes -- the
    //       values are left-justified in ten characters with the remainder blank, five significant
    //       digits being typical. This is the one member of the descriptive group above that is
    //       therefore CHAR rather than VARCHAR, and the provider is told so explicitly.
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "merchant_zip", length = 10)
    private String merchantZip;

    /**
     * The card number the transaction was presented on, 16 characters with significant leading
     * zeros.
     */
    // WHY : Assumptions: character and never numeric, from PIC X(16) at app/cpy/CVTRA06Y.cpy:15. No
    //       arithmetic is ever performed on it, and a leading zero is significant: 30 of the 300
    //       values in app/data/ASCII/dailytran.txt begin with a zero, so a numeric mapping would
    //       silently shorten one card number in ten. The owning migration measured the same
    //       proportion independently.
    // WHY : Assumptions: this is the lookup key into the card cross-reference, moved into the key and
    //       read at app/cbl/CBTRN02C.cbl:380-392, and the failure of that read is what produces
    //       reject reason 100 with the message 'INVALID CARD NUMBER FOUND'. The preflight performs
    //       the same lookup at app/cbl/CBTRN01C.cbl:172. A value shortened or reformatted anywhere
    //       between the extract and that read turns a resolvable record into a rejected one, which is
    //       a parity failure that looks like a data problem.
    // WHY : Trade-offs: THE VALUE IS STORED AND RETURNED IN FULL, UNMASKED. That is a deliberate
    //       exception to the migration plan's masking discipline at section 0.4.1.9, and it is
    //       confined to this layer for a specific reason: app/cbl/CBTRN02C.cbl:447 copies the WHOLE
    //       350-byte record into the reject payload, and the committed expectations under
    //       tests/golden/posting compare that payload byte for byte, so a masked value here would not
    //       merely obscure a number -- it would change compared bytes and break parity outright.
    //       Masking belongs where a value leaves the system to a caller, in the transfer-object and
    //       mapper layers of the services that expose one, and this module exposes no such surface
    //       for this record. What is accepted in exchange is that the full number is in scope for
    //       this module's data-at-rest and log controls; the rendering method at the end of this type
    //       is where the log half of that is closed.
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "card_num", length = 16)
    private String cardNum;

    /**
     * The stamp the acquiring system placed on the transaction, to microsecond precision.
     */
    // WHY : Assumptions: PIC X(26) at app/cpy/CVTRA06Y.cpy:16 renders a date, a time and six
    //       fractional digits, so microsecond precision matches the 26-character form exactly and
    //       neither loses nor invents a digit. com.carddemo.common.time.TimestampFormatter is the one
    //       place that form is produced and parsed, and it fixes the same length.
    // WHY : Alternatives Considered: a type carrying a zone or an offset instead of a local value.
    //       Rejected because there is no zone in the source to preserve: the baseline's timestamp
    //       structure keeps a GMT-offset element separately and never moves it into the rendered
    //       26-character value, so attaching a zone here would fabricate information the record does
    //       not carry and would make two runs in different regions disagree about what the same bytes
    //       mean. common-lib's formatter makes the same choice for the same reason.
    // WHY : Assumptions: on THIS record the origination stamp has two distinct roles, and the second
    //       is the one that constrains callers. First, it passes straight through: posting moves it
    //       unaltered into the posted record at app/cbl/CBTRN02C.cbl:436, so it is the same instant
    //       on both sides of the posting boundary. Second, it is read as a DATE --
    //       app/cbl/CBTRN02C.cbl:414 compares the account expiration date against the FIRST TEN
    //       CHARACTERS of this value, and passes validation when the expiration date is greater than
    //       or EQUAL to it, so an expiration date equal to the transaction date posts and only a
    //       later transaction date rejects with reason 103. That reference-modification is why the
    //       ten-character date prefix must stay recoverable from the stored value, and why recovering
    //       it is not this type's job: com.carddemo.common.time.TimestampFormatter exposes both a
    //       datePrefix and a toLocalDate for exactly this comparison, and the validation service uses
    //       one of them rather than re-slicing a rendered value here. The extract bears out that the
    //       comparison always has an operand -- all 300 records in app/data/ASCII/dailytran.txt
    //       populate this field, one reading 2022-06-10 19:27:53.000000.
    @Column(name = "orig_ts")
    private LocalDateTime origTs;

    /**
     * The processing stamp the feed leaves unset, absent on every record of the seed extract.
     */
    // WHY : Assumptions: NULLABLE HERE WHILE THE SIBLING Transaction DECLARES THE SAME COLUMN NOT
    //       NULL. That asymmetry is the point of this declaration, it is semantic rather than
    //       structural -- app/cpy/CVTRA06Y.cpy:17 and app/cpy/CVTRA05Y.cpy:17 declare the field at
    //       the identical PIC X(26) width -- and it rests on two independent findings. The feed
    //       leaves the field blank: reading the 26 bytes at zero-based offset 304 across
    //       app/data/ASCII/dailytran.txt finds them blank on 300 of 300 records, while the
    //       origination stamp above is populated on 300 of 300, so the blankness is specific to this
    //       field rather than a gap in the extract, and a blank field decodes to an absent value.
    //       And the stamp is minted downstream rather than supplied upstream:
    //       app/cbl/CBTRN02C.cbl:437 obtains a fresh value and :438 moves THAT into the posted
    //       record, whereas :436 copies the origination stamp across from the feed. Asserting a
    //       non-null constraint here would reject the seed extract in its entirety.
    // WHY : Assumptions: it follows that this member is NOT the posting time and no consumer may read
    //       it as one. The instant a record was posted exists only on the posted row, produced at
    //       app/cbl/CBTRN02C.cbl:437-438 after this record has been read; treating the feed's
    //       processing stamp as that instant would report an absent value on every record the current
    //       extract contains.
    @Column(name = "proc_ts")
    private LocalDateTime procTs;

    /**
     * Creates an empty instance for the persistence provider to populate from a result row.
     *
     * <p>Assumptions: the persistence specification requires a mapped class to declare a constructor
     * taking no arguments, which the provider invokes reflectively before writing the thirteen mapped
     * columns into the members above. Visibility is protected rather than public because no caller
     * outside this type's own hierarchy has a use for a feed record carrying no values at all: a
     * provider-generated subclass reaches a protected constructor, while application code cannot
     * reach it by accident and so cannot produce an instance whose absent identifier the equality
     * below would have to treat as an identity. Protected is the widest visibility the
     * specification's requirement actually needs. </p>
     */
    protected DailyTransaction() {
        // WHY : Assumptions: the body is empty by design rather than unfinished. The provider assigns
        //       every mapped member directly after construction on each load, so initialising any of
        //       them here would write a value that is overwritten before a caller could observe it.
        //       Nothing is defaulted either: a member this constructor set would be indistinguishable
        //       afterwards from a column the feed genuinely left blank, and the processing stamp is
        //       exactly such a column on every record of the seed extract.
    }

    /**
     * Creates a feed record carrying all thirteen values the 350-byte input record supplies.
     *
     * <p>Alternatives Considered: this type is read-only, so the construction strategies the sibling
     * {@link Transaction} could choose between are not all open here. That type takes its key and
     * records the remaining members through mutators, and its own documentation rejects a
     * thirteen-argument constructor on the ground that thirteen positional parameters invite a silent
     * transposition. The third option that reasoning depends on -- mutators -- is closed here by the
     * read-only boundary this type enforces, so the choice narrows to an all-argument constructor or a
     * hand-written builder. The constructor is chosen: a builder would add a member-per-value method
     * surface shaped exactly like the mutators this type deliberately does not have, on a construction
     * path that only fixtures use, since the provider populates a loaded row reflectively through the
     * constructor above instead. A generated builder was never available -- the annotation processor
     * that would produce one is prohibited across this project because its generated members cannot
     * carry the documentation the project's explainability rule requires. </p>
     *
     * <p>Trade-offs: the transposition risk the sibling names is real and is accepted rather than
     * dismissed. Ten of the thirteen parameters are strings and four of them are adjacent -- the
     * merchant name, city and postal code, then the card number -- so swapping a pair compiles
     * cleanly and yields a plausible record. Two things contain it. The parameter order is exactly
     * the copybook's declaration order at {@code app/cpy/CVTRA06Y.cpy:5-17}, so the offset table on
     * this class is the checklist a caller reads down rather than a separate convention to remember.
     * And the round-trip coverage for this type asserts every member individually after a reload
     * rather than comparing whole instances, which is what makes a transposition fail a test: two
     * transposed strings survive a whole-object comparison unchanged, because the object being
     * compared is the transposed one. </p>
     *
     * @param transactionId the String identifier the feed supplies, from {@code DALYTRAN-ID PIC
     *     X(16)} at {@code app/cpy/CVTRA06Y.cpy:5}, retaining any leading zeros
     * @param typeCd the String transaction type code, from {@code DALYTRAN-TYPE-CD PIC X(02)} at
     *     {@code app/cpy/CVTRA06Y.cpy:6}
     * @param categoryCd the String transaction category code in its zero-padded four-character form,
     *     from {@code DALYTRAN-CAT-CD PIC 9(04)} at {@code app/cpy/CVTRA06Y.cpy:7}
     * @param source the String originating channel, from {@code DALYTRAN-SOURCE PIC X(10)} at
     *     {@code app/cpy/CVTRA06Y.cpy:8}, blank-padded to its declared width
     * @param description the String free-text description, from {@code DALYTRAN-DESC PIC X(100)} at
     *     {@code app/cpy/CVTRA06Y.cpy:9}
     * @param amount the BigDecimal signed amount, from {@code DALYTRAN-AMT PIC S9(09)V99}, reduced to
     *     scale 2 and bounded to nine integer digits through {@code Money}; must not be {@code null}
     *     at {@code app/cpy/CVTRA06Y.cpy:10}; a negative value is the debit convention and must be
     *     passed as the negative it is
     * @param merchantId the Long merchant identifier, from {@code DALYTRAN-MERCHANT-ID PIC 9(09)} at
     *     {@code app/cpy/CVTRA06Y.cpy:11}; zero is a legitimate value
     * @param merchantName the String merchant name, from {@code DALYTRAN-MERCHANT-NAME PIC X(50)} at
     *     {@code app/cpy/CVTRA06Y.cpy:12}; an entirely blank value is legitimate
     * @param merchantCity the String merchant city, from {@code DALYTRAN-MERCHANT-CITY PIC X(50)} at
     *     {@code app/cpy/CVTRA06Y.cpy:13}; an entirely blank value is legitimate
     * @param merchantZip the String merchant postal code, from {@code DALYTRAN-MERCHANT-ZIP PIC
     *     X(10)} at {@code app/cpy/CVTRA06Y.cpy:14}, retaining any leading zero
     * @param cardNum the String card number the transaction was presented on, from
     *     {@code DALYTRAN-CARD-NUM PIC X(16)} at {@code app/cpy/CVTRA06Y.cpy:15}, unmasked and
     *     retaining any leading zeros because it is the cross-reference lookup key
     * @param origTs the LocalDateTime stamp the acquiring system placed on the transaction, from
     *     {@code DALYTRAN-ORIG-TS PIC X(26)} at {@code app/cpy/CVTRA06Y.cpy:16}, to microsecond
     *     precision
     * @param procTs the LocalDateTime processing stamp, from {@code DALYTRAN-PROC-TS PIC X(26)} at
     *     {@code app/cpy/CVTRA06Y.cpy:17}, which is null on every record of the seed extract and must
     *     be passed as null rather than substituted
     */
    public DailyTransaction(String transactionId, String typeCd, String categoryCd, String source,
            String description, BigDecimal amount, Long merchantId, String merchantName,
            String merchantCity, String merchantZip, String cardNum, LocalDateTime origTs,
            LocalDateTime procTs) {
        this.transactionId = transactionId;
        this.typeCd = typeCd;
        this.categoryCd = categoryCd;
        this.source = source;
        this.description = description;
        // WHY : Refactoring Rationale: the amount is canonicalised through Money rather than assigned
        //       verbatim, which an earlier revision did. Verbatim assignment admitted a null into a
        //       column now declared NOT NULL, a scale other than two that the driver would coerce, and
        //       a ten-integer-digit magnitude that Money.of admits for the widest reference picture and
        //       that NUMERIC(11,2) then rejects at the database with no field name in the failure.
        //       Bounding at the picture rejects all three where the value entered the row.
        this.amount = Money.ofPicture(amount, AMOUNT_INTEGER_DIGITS).amount();
        this.merchantId = merchantId;
        this.merchantName = merchantName;
        this.merchantCity = merchantCity;
        this.merchantZip = merchantZip;
        this.cardNum = cardNum;
        this.origTs = origTs;
        this.procTs = procTs;
        // WHY : Assumptions: every value is stored exactly as supplied, with no defaulting, trimming,
        //       sign normalisation, rounding or masking applied on the way in. Each of those would be
        //       a change of meaning rather than a convenience: trimming would make a padded code
        //       compare unequal to the blank-padded form the key lookups use, normalising the sign
        //       would move an amount from the debit bucket to the credit bucket at
        //       app/cbl/CBTRN02C.cbl:548-552, and substituting a value for the absent processing
        //       stamp would make it indistinguishable from a stamp the feed had actually carried.
        //       Validation belongs to the posting job's validation service, which is where the
        //       baseline puts it at app/cbl/CBTRN02C.cbl:370-422, and rejecting here would replace a
        //       counted reject carrying a reason code with an exception carrying none.
    }

    /**
     * Returns this row's ingestion ordinal, which is this type's mapped identity and the value a
     * chunked scan resumes from.
     *
     * <p>Assumptions: it is the only column on this feed that is both unique and monotonic in arrival
     * order. A caller resuming a scan passes the ordinal of the last row it holds; a caller comparing
     * rows for business content compares the transaction identifier instead, which the feed does not
     * promise to be unique.</p>
     *
     * <p>Assumptions: the value is assigned by the database, and this mapping is {@code @Immutable}, so
     * every instance this module publishes came from a query and carries a non-null ordinal. An instance
     * built by the public constructor for a test carries none, which is the one case
     * {@link #equals(Object)} answers by reference.</p>
     *
     * <p>Assumptions: the ordinal carries no business meaning and is not a transaction identifier, a run
     * identifier or a reason code. Nothing this module writes to a queue, a log line or a report renders
     * it.</p>
     *
     * @return the ingestion ordinal of the {@code ingest_seq} column, or {@code null} on an instance
     *     that did not come from a query
     */
    public Long getIngestSeq() {
        return this.ingestSeq;
    }

    /**
     * Returns the sixteen-character identifier the feed supplies for this unposted transaction.
     *
     * @return the String value of the {@code transaction_id} column, which posting copies verbatim
     *     into the posted record at {@code app/cbl/CBTRN02C.cbl:425} and which the preflight names
     *     when it reports a record it could not resolve at {@code app/cbl/CBTRN01C.cbl:181-183}
     */
    public String getTransactionId() {
        return transactionId;
    }

    /**
     * Returns the two-character type code classifying this unposted transaction.
     *
     * @return the String value of the {@code type_cd} column, blank-padded to its declared width,
     *     which is one of the two non-account components of the category-balance key composed at
     *     {@code app/cbl/CBTRN02C.cbl:470}
     */
    public String getTypeCd() {
        return typeCd;
    }

    /**
     * Returns the four-character category code sub-classifying this unposted transaction.
     *
     * @return the String value of the {@code category_cd} column in its zero-padded four-character
     *     form, which is the form the category-balance key composed at
     *     {@code app/cbl/CBTRN02C.cbl:471} and the disclosure-group key both compare against
     */
    public String getCategoryCd() {
        return categoryCd;
    }

    /**
     * Returns the originating channel the feed names for this unposted transaction.
     *
     * @return the String value of the {@code source} column, blank-padded to ten characters, which
     *     posting copies straight across into the posted record at
     *     {@code app/cbl/CBTRN02C.cbl:428}
     */
    public String getSource() {
        return source;
    }

    /**
     * Returns the free-text description the feed carries for this unposted transaction.
     *
     * @return the String value of the {@code description} column with its padding removed, which
     *     posting copies unaltered into the posted record at {@code app/cbl/CBTRN02C.cbl:429}
     */
    public String getDescription() {
        return description;
    }

    /**
     * Returns the signed amount this unposted transaction would move, exact to two decimal places.
     *
     * @return the BigDecimal value of the {@code amount} column at scale 2, signed as the feed signs
     *     it, where a negative value is the debit convention that
     *     {@code app/cbl/CBTRN02C.cbl:548-552} accumulates into the cycle debit total rather than an
     *     error to be normalised
     */
    public BigDecimal getAmount() {
        return amount;
    }

    /**
     * Returns the merchant identifier the feed supplies for this unposted transaction.
     *
     * @return the Long value of the {@code merchant_id} column, where zero is a legitimate
     *     identifier rather than an absent one, as {@code app/cbl/CBACT04C.cbl:491} writes on every
     *     generated interest transaction
     */
    public Long getMerchantId() {
        return merchantId;
    }

    /**
     * Returns the merchant name the feed carries for this unposted transaction.
     *
     * @return the String value of the {@code merchant_name} column with its padding removed, which
     *     may be entirely blank because {@code app/cbl/CBACT04C.cbl:492} writes blanks into this
     *     field on every generated interest transaction
     */
    public String getMerchantName() {
        return merchantName;
    }

    /**
     * Returns the merchant city the feed carries for this unposted transaction.
     *
     * @return the String value of the {@code merchant_city} column with its padding removed, which
     *     may be entirely blank for the same reason the merchant name may be
     */
    public String getMerchantCity() {
        return merchantCity;
    }

    /**
     * Returns the merchant postal code the feed carries for this unposted transaction.
     *
     * @return the String value of the {@code merchant_zip} column, blank-padded to ten characters and
     *     retaining any leading zero, because a postal code beginning with a zero is a different code
     *     from the same digits without it
     */
    public String getMerchantZip() {
        return merchantZip;
    }

    /**
     * Returns the unmasked card number this unposted transaction was presented on.
     *
     * @return the String value of the {@code card_num} column in full, sixteen characters retaining
     *     any leading zeros, because this is the value moved into the cross-reference key at
     *     {@code app/cbl/CBTRN02C.cbl:382} and a shortened form would turn a resolvable record into
     *     reject reason 100
     */
    public String getCardNum() {
        return cardNum;
    }

    /**
     * Returns the stamp the acquiring system placed on this transaction.
     *
     * @return the LocalDateTime value of the {@code orig_ts} column to microsecond precision, which
     *     posting passes straight through at {@code app/cbl/CBTRN02C.cbl:436} and whose date part is
     *     the transaction date the expiration comparison at {@code app/cbl/CBTRN02C.cbl:414} reads
     */
    public LocalDateTime getOrigTs() {
        return origTs;
    }

    /**
     * Returns the processing stamp the feed supplies, which the seed extract leaves unset.
     *
     * @return the LocalDateTime value of the {@code proc_ts} column, or null when the feed left the
     *     field blank as it does on every record of {@code app/data/ASCII/dailytran.txt}; this is not
     *     the instant the transaction posts, which is minted downstream at
     *     {@code app/cbl/CBTRN02C.cbl:437-438}
     */
    public LocalDateTime getProcTs() {
        return procTs;
    }

    /**
     * Compares this feed record with another object for equality on the ingestion sequence alone,
     * which is this type's mapped identity.
     *
     * <p>Refactoring Rationale: an earlier revision compared the transaction identifier, because that
     * identifier was then mapped as the identity. That is why this method changed. The earlier version
     * recorded the resulting hazard as a caveat -- that the table asserts no uniqueness over the
     * identifier, so two rows of a feed that legitimately repeated one would compare equal -- and a
     * caveat does not prevent the loss it describes. The sequence the owning migration now declares is
     * unique per row, so the hazard is removed rather than documented.
     *
     * <p>Alternatives Considered: comparing every member instead. Rejected even though this type is
     * read-only and so cannot suffer the usual objection that a member may change after the instance
     * has been placed in a hash-based collection, because comparing thirteen members would report two
     * representations of one row unequal whenever a projection had populated a subset of them. </p>
     *
     * <p>Refactoring Rationale: the comparison is on the INGESTION ORDINAL and not on the transaction
     * identifier the feed supplies. The physical table asserts no uniqueness over that identifier, so
     * comparing it reported two rows of a feed that legitimately repeated one as equal, and a consumer
     * collecting a scan into a set silently kept one of them. The ordinal is unique by construction, so
     * a scan can now be treated as a set as well as a stream. </p>
     *
     * <p>Assumptions: the ordinal is assigned by the database rather than by this module, which maps the
     * column read-only and declares no generation strategy, so every instance the provider publishes
     * arrives with it populated. The null branch below therefore covers only an instance built by the
     * no-argument constructor, and two such instances are equal only when they are the same object --
     * reporting them equal would let a hash-based collection discard one of two distinct arrivals, which
     * is the loss this identity exists to prevent. </p>
     *
     * <p>Assumptions: the comparison is a pattern match rather than an exact-class test, for the
     * provider-subclass reason the sibling {@link Transaction} sets out for its own equality, which is
     * cited here rather than restated. The consequence specific to this type is that its two operands
     * frequently come from different scans of the same feed -- a redriven run reads the feed again
     * from the start -- so two instances of two different generated subclasses can legitimately
     * denote one record, and an exact-class test would call them unequal. </p>
     *
     * @param other the Object to compare against, which may be of any type and may be null
     * @return the boolean value true when the argument is this same object, or is a daily-transaction
     *     record carrying an equal, assigned ingestion ordinal, and false otherwise
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof DailyTransaction that)) {
            return false;
        }
        // WHY : Assumptions: the identity is the ingestion sequence, so two rows repeating a
        //       transaction identifier are UNEQUAL here -- which is the point. An unhydrated
        //       instance carrying no sequence is equal only to itself, because treating two null
        //       sequences as equal would let a hash-based collection discard one of two distinct
        //       occurrences and reintroduce the loss this identity removes.
        if (this.ingestSeq == null || that.ingestSeq == null) {
            return this == other;
        }
        return this.ingestSeq.equals(that.ingestSeq);
    }

    /**
     * Returns a hash consistent with the identity-based equality above.
     *
     * <p>Assumptions: the hash is a CONSTANT rather than a function of the ingestion sequence. The pair
     * must agree -- hashing a value the equality no longer consults would let two instances the
     * equality calls equal land in different buckets, which breaks the collection contract outright --
     * and a constant satisfies that agreement for every instance, including one built by the
     * no-argument constructor whose sequence is still absent. </p>
     *
     * <p>Refactoring Rationale: an earlier revision hashed the transaction identifier, to match an
     * equality that then compared it, and argued the pairing was unusually safe here because this
     * read-only type exposes no mutator for the identifier. That argument was sound about STABILITY and
     * silent about UNIQUENESS, which is the property a hash key actually needs: two distinct arrivals
     * sharing an identifier hashed alike and compared equal, so a set discarded one. Moving both halves
     * onto the sequence fixes the pair rather than the symptom. A hash derived from the sequence would
     * have carried the opposite hazard instead: an instance hashed before the database assigned its
     * value would sit in a bucket keyed by the old hash and become unfindable in a collection that
     * still held it. </p>
     *
     * <p>Trade-offs: every instance therefore lands in one bucket, so a large hash set of feed rows
     * degrades to linear scanning within it. Accepted because a silently unfindable entity is a
     * correctness fault where a slower lookup is only a performance one, and because this module reads
     * the feed as an ordered chunked stream rather than assembling it into a set. </p>
     *
     * <p>Trade-offs: every instance therefore lands in one bucket, so a hash-based collection of these
     * degrades to a linear scan. That is accepted because the feed is read in bounded chunks and is
     * never accumulated into a large set. </p>
     *
     * @return a constant int hash, equal for every instance of this type
     */
    @Override
    public int hashCode() {
        // WHY : Assumptions: a constant rather than a function of the sequence, so the value cannot
        //       change if an instance is hashed before the provider hydrates it. Every instance
        //       therefore shares one bucket, which is accepted because this feed is read in bounded
        //       chunks and is never accumulated into a large hash-based collection.
        return DailyTransaction.class.hashCode();
    }

    /**
     * Returns a diagnostic rendering naming this feed record's identity and the three members that
     * characterise it.
     *
     * <p>Refactoring Rationale: the ingestion sequence leads the rendering, and an earlier revision
     * omitted it. The sequence is this type's mapped identity, so a log line without it names a
     * transaction identifier that two physical rows can share -- which is precisely the ambiguity the
     * identity was moved onto the sequence to remove. A diagnostic that cannot say WHICH occurrence it
     * concerns is the one case where the reader most needs it to. </p>
     *
     * <p>Trade-offs: THE CARD NUMBER IS DELIBERATELY OMITTED, even though the accessor above returns
     * it in full and the column stores it unmasked. The two are not in tension: the column has to
     * carry the full value because {@code app/cbl/CBTRN02C.cbl:447} copies the whole record into the
     * reject payload that committed expectations compare byte for byte, whereas this string reaches
     * logs, where nothing compares it and nothing needs the number. The migration plan's masking
     * discipline at section 0.4.1.9 is what closes it here. The exposure this avoids is specific to
     * the type: a job renders one such line per record while scanning THIS feed end to end, so the
     * aggregate would be a retained log holding every card number in the feed. Note that the baseline
     * itself renders the card number in a diagnostic at {@code app/cbl/CBTRN01C.cbl:181-183}; the
     * target does not reproduce that in a log line, and the divergence is registered in
     * {@code docs/architecture/cobol-to-service-traceability.md} rather than treated as a defect in
     * the COBOL, which is untouched and still runs. What is given up is that a failure diagnosed from
     * this string alone does not name the card; the identifier resolves it against the stored row. </p>
     *
     * <p>Trade-offs: THE AMOUNT IS OMITTED ON THE SAME REASONING, which is the decision the sibling
     * {@link Transaction} reached for its own rendering and recorded there. It applies with more force
     * here rather than less, because the aggregate that argument describes -- a log holding every
     * amount in an entire daily feed -- is precisely what a scan of THIS type produces, this being
     * the feed itself. The four members that remain are an ordinal, an identifier and two codes: none
     * is monetary, none is personal, and together they say which record an entry concerns without
     * saying what it was worth. The accessor above returns the amount to a caller that needs it. </p>
     *
     * <p>Alternatives Considered: rendering the processing stamp, which is the stamp the sibling
     * renders. Declined for this type because the feed leaves that field blank on every record of the
     * seed extract, so it would contribute a constant absent value and tell a reader nothing, while
     * the origination stamp is populated on every record and is the value the expiration comparison
     * at {@code app/cbl/CBTRN02C.cbl:414} reads. A stamp is worth rendering only if it varies. </p>
     *
     * <p>Trade-offs: this string is a diagnostic aid and is expressly NOT an output contract, a point
     * the sibling {@link Transaction} also records for its own rendering. It needs saying more loudly
     * here, because THIS is the record that {@code app/cbl/CBTRN02C.cbl:447} copies whole into the
     * reject payload, so a reader hunting for where those 350 bytes are produced is likelier to land
     * on this method first and conclude it is the emitter. It is not: the payload is assembled by the
     * job through {@code com.carddemo.common.codec.FixedWidthCodec} from the members above, and the
     * committed expectations under {@code tests/golden/posting} compare THAT. Widening, reordering or
     * reformatting this string therefore cannot disturb any compared output, whereas using it for the
     * payload would break parity silently -- the two agree on content and differ at every byte
     * position. </p>
     *
     * @return a String containing a single-line rendering naming the type, the ingestion sequence,
     *     the transaction identifier, the type and category codes and the origination stamp, and
     *     carrying neither the card number nor the amount
     */
    @Override
    public String toString() {
        return "DailyTransaction[ingestSeq=" + ingestSeq
                + ", transactionId=" + transactionId
                + ", typeCd=" + typeCd
                + ", categoryCd=" + categoryCd
                + ", origTs=" + origTs + "]";
    }
}
