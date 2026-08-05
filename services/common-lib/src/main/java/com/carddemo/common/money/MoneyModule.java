package com.carddemo.common.money;

import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.Version;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.module.SimpleModule;

/**
 * Carries a monetary amount across a JSON boundary as a quoted plain decimal string, in both
 * directions.
 *
 * <p>{@link Money} guarantees that an amount is exact while it is held in memory. This module
 * guarantees that the exactness survives the last hop, where the amount is written into a response
 * body or read out of a request body. Neither guarantee is sufficient on its own: an exact
 * {@code Money} rendered as a bare JSON number is approximated by the client that receives it, and
 * an inexact value read into a {@code Money} is already wrong before any arithmetic touches it. The
 * two classes in this package are therefore one contract expressed in two places, and this is the
 * half of it that faces outward.</p>
 *
 * <p>The module registers one serialiser and one deserialiser, both bound to {@link Money} and to no
 * other type. It implements nothing else: it applies no rounding, holds no state and has no
 * configurable variant, because the wire form it renders is fixed by the contract described below
 * rather than chosen at runtime.</p>
 *
 * <h2>Why the string form continues the reference system rather than departing from it</h2>
 *
 * <p>Assumptions: the reference application already transports money as signed decimal text across a
 * message boundary while holding the same logical amounts in packed decimal, and this module
 * preserves exactly that division of labour. Three declarations establish it, and all three are read
 * and cited only, never edited:</p>
 *
 * <ul>
 *   <li><b>Storage is packed decimal.</b> Line 34 of
 *       {@code app/app-authorization-ims-db2-mq/cpy/CIPAUDTY.cpy} declares
 *       {@code 05  PA-TRANSACTION-AMT           PIC S9(10)V99 COMP-3.} and line 35 declares
 *       {@code 05  PA-APPROVED-AMT              PIC S9(10)V99 COMP-3.} By the packed length rule
 *       {@code floor(digits / 2) + 1}, twelve digits occupy <b>7 bytes</b> each. Those bytes are not
 *       characters and cannot be read as any.</li>
 *   <li><b>The wire is edited display text.</b> Line 27 of
 *       {@code app/app-authorization-ims-db2-mq/cpy/CCPAURQY.cpy} declares
 *       {@code 05  PA-RQ-TRANSACTION-AMT        PIC +9(10).99.} for the <em>same logical amount</em>
 *       -- <b>14 characters</b>, being one sign position, ten integer digits, a literal decimal point
 *       and two decimal digits. That copybook is the eighteen-field authorization request, whose
 *       fields occupy lines 19 through 36.</li>
 *   <li><b>So is the reply.</b> Line 24 of
 *       {@code app/app-authorization-ims-db2-mq/cpy/CCPAURLY.cpy} declares
 *       {@code 05  PA-RL-APPROVED-AMT           PIC +9(10).99.} in the six-field reply at lines 19
 *       through 24, likewise 14 characters of text.</li>
 * </ul>
 *
 * <p>The quoted JSON string is therefore the faithful analogue of {@code PIC +9(10).99}, and it is the
 * JSON number that would be the novel construction. The continuity is literal rather than merely
 * thematic: that edited field renders an amount of 2065.00 as {@code +0000002065.00}, and a leading
 * plus sign together with leading zeros is text the {@link java.math.BigDecimal} string constructor
 * accepts unchanged, so a producer copying such a field verbatim into a JSON string is understood by
 * this module without a translation step.</p>
 *
 * <h2>The JSON number, and why it is not used</h2>
 *
 * <p>Alternatives Considered: emitting money as a JSON number was evaluated and rejected, and the
 * reason is a measurable loss rather than a preference. A JSON number carries no scale, and the
 * majority of clients parse one into an IEEE-754 binary64 value on receipt. That representation
 * cannot hold most two-place decimal fractions at all -- {@code 0.01} has no finite binary expansion
 * -- so such a client must approximate the value it was handed. The normative field fixes the size of
 * the exposure: line 7 of {@code app/cpy/CVACT01Y.cpy} declares
 * {@code 05  ACCT-CURR-BAL                     PIC S9(10)V99.}, which is twelve significant decimal
 * digits, and binary64 carries roughly fifteen to seventeen significant decimal digits in total, so
 * there is no headroom to absorb an approximation before it reaches the cents. Every guarantee the
 * database column, the in-memory type and the extract-transform-load code establish would be
 * discarded at the boundary the user actually sees, and discarded silently, because the amount would
 * still look like money.</p>
 *
 * <p>Trade-offs: quoting the amount moves the parsing decision into the client's own code, where a
 * decimal type can be chosen deliberately. The compromise accepted is one pair of quote characters
 * per amount and a client that must convert before it can perform arithmetic. That cost is accepted
 * because the alternative trades an invariant enforced in three layers for the convenience of one.</p>
 *
 * <h2>The four properties of the rendered form</h2>
 *
 * <p>Assumptions: the rendering is plain decimal and never exponent notation. The reference forms
 * admit no exponent at all -- {@code PIC +9(10).99} is 14 fixed character positions -- so a value
 * rendered in exponent notation would be unreadable to any consumer that mirrors the baseline layout.
 * This module therefore renders through {@link Money#toPlainString()} rather than through the general
 * text conversion of the underlying decimal type, which is permitted to emit exponent notation at
 * certain scales.</p>
 *
 * <p>Assumptions: the scale on the wire is exactly two, so a zero amount is rendered {@code 0.00} and
 * never {@code 0}, and an amount of one and a half is rendered {@code 1.50}. The scale is a property
 * of the declaration rather than a display choice: {@code V99} in the picture above is an implied
 * decimal point followed by exactly two digit positions. This module does not enforce the scale
 * itself, because {@link Money} already does so in its constructor, which rejects any value whose
 * scale is not {@link Money#SCALE}. Re-asserting the invariant here would give one rule two owners,
 * and the usual outcome of that is two rules.</p>
 *
 * <p>Assumptions: the sign is carried in the rendered text, because every money picture in the
 * reference records is signed -- the {@code S} of {@code PIC S9(10)V99} at line 7 of
 * {@code app/cpy/CVACT01Y.cpy} -- so a negative amount is rendered with a leading minus and reads
 * back identically.</p>
 *
 * <p>Assumptions: no value on either path passes through an IEEE-754 binary representation.
 * Serialisation renders text from the exact decimal, and deserialisation parses text into an exact
 * decimal through {@link Money#of(String)}. Migration rule T3 states that prohibition for the whole
 * money path, and it is architecture-tested by {@code LayeringRulesTest} in this module's test tree,
 * so a breach fails a build rather than a review. This class is the enforcement point for the JSON
 * half of it: the two preceding layers cannot defend a boundary they do not reach.</p>
 *
 * <h2>The Jackson generation this module is written against</h2>
 *
 * <p>Assumptions: this module is written against the Jackson 3 API under the {@code tools.jackson}
 * group, and the generation is not interchangeable with the older one. Spring Boot 4.1 version
 * manages both Jackson lines, but it builds its default HTTP message conversion from the 3.x mapper,
 * and the two lines share no module type at all. Written against the older generation this class
 * would compile, package and pass every unit test, and then fail to be registered on the mapper that
 * actually serialises a response. {@code services/common-lib/pom.xml} therefore declares the
 * {@code tools.jackson.core} coordinate, and it records that same failure as having already happened
 * once.</p>
 *
 * <p>Assumptions: the consequence of this module not being registered was measured rather than
 * assumed, and it is worse than a loss of precision. With no handler registered for it, the value
 * type is treated as an ordinary bean, and because its accessors are not named in the bean convention
 * the only members discovered are its three sign predicates: an amount of
 * {@code 9999999999.99} is written as {@code {"negative":false,"positive":true,"zero":false}} and the
 * amount is absent from the payload altogether. A bare JSON number is what an unhandled decimal
 * renders as -- the same amount held directly as a decimal is written {@code 9999999999.99} -- which
 * is the loss the money path avoids by carrying the value type in the first place. Both outcomes are
 * silent: nothing in a build, a startup log or a response status reports either one.</p>
 *
 * <h2>How a consumer registers this module</h2>
 *
 * <p>Refactoring Rationale: no consumer registers this module explicitly, and none has to. This
 * module publishes a provider-configuration file for the platform service-provider mechanism, named
 * for the Jackson module interface and holding this class's binary name; the file lives under this
 * module's own resource tree at {@code src/main/resources} and carries its own commentary. Jackson
 * resolves modules through the platform service loader, so a mapper built with its
 * find-and-add-modules step discovers this one and registers both handlers with no wiring at the call
 * site, and the framework's own Jackson configuration performs that step by default. The earlier
 * shape of this module relied on one line of explicit wiring per consumer and, measurably, got none:
 * the failure described immediately above -- three sign predicates rendered and the amount absent
 * altogether -- is what a forgotten registration produces, and it is silent in every build and
 * startup log. Discovery removes the per-consumer step that was the single point of failure.</p>
 *
 * <p>Trade-offs: the compromise accepted is that registration is no longer visible in the Java source
 * of a consumer, so a reader of a controller cannot see why an amount is quoted. What is bought is
 * that no consumer can forget it. Two properties bound the cost. The registration identifier this
 * class supplies makes registration idempotent, so a consumer that additionally registers the module
 * by hand -- which remains supported, since the public no-argument constructor is unchanged --
 * replaces one map entry rather than adding a second. And the handlers are bound to {@link Money}
 * alone, so discovery cannot alter the rendering of any other type in any consumer that merely has
 * this library on its classpath.</p>
 *
 * <p>Alternatives Considered: a framework auto-configuration class declaring a bean of this module
 * type was evaluated and rejected. It would take effect only inside an application context, so a
 * batch step or a test that builds a bare mapper would still render the broken form, and it would
 * make this library depend on the framework's auto-configuration artifact for a concern that is not
 * framework-specific. The platform mechanism is neutral and is the one Jackson documents.</p>
 *
 * <p>Trade-offs: the class therefore offers a public no-argument constructor and requires no
 * framework at all, so it works against a bare mapper. The web starter that would supply framework
 * annotations is declared optional by this module's descriptor and is not inherited by consumers, so
 * a class here that depended on one would compile in this module and fail to load in a consumer that
 * had not declared the same starter. No annotation is applied for that reason.</p>
 *
 * <h2>What deliberately does not live here</h2>
 *
 * <p>Alternatives Considered: encoding the comma-delimited message payloads was considered for this
 * class and rejected. The eighteen-field authorization request and the six-field reply cited above
 * are character-delimited records whose field order and delimiter are themselves the contract, and
 * they are owned by {@code com.carddemo.common.codec.CsvAuthCodec}. Migration rule T2 keeps one
 * shared concern in one place, so encoding them here as well would give that contract a second
 * declaration to drift from the first. The JSON envelope this module renders is offered additively,
 * for consumers that are new to the migrated system, and it never replaces the delimited form.</p>
 *
 * <p>Alternatives Considered: registering a key handler as well, so that a monetary amount could also
 * serve as a JSON property name, was considered and not done. Nothing in the migrated system keys a
 * structure by an amount -- the keys are account identifiers, card numbers and transaction
 * identifiers -- so the registration would be speculative, and a handler that no payload exercises is
 * a handler no test covers. The omission is benign in the one direction it could matter:
 * {@link Money} renders its own text form as the wire form, so an amount used as a key would still be
 * written as plain decimal text.</p>
 *
 * <p>Trade-offs: one identifier is described in this file rather than spelled, and the omission is
 * deliberate so that a later reader does not supply it: the pair of IEEE-754 binary primitive type
 * names and their wrapper types, excluded from the money path. Those tokens are audited by a search of
 * this tree, so writing either one produces a hit that has to be explained away on every audit. The
 * description above is unambiguous, the cost is paid in reading effort, and the convention is not
 * invented here: {@link Money} and the package descriptor beside it describe the numeric type names
 * the same way. The service-provider path is the deliberate exception -- it is spelled out in the
 * provider-configuration file itself, because there the interface name is not prose but the lookup key
 * the platform matches on.</p>
 *
 * <h2>Documentation of the overriding members</h2>
 *
 * <p>Assumptions: a Jackson value handler is built almost entirely out of overriding methods, and
 * every one of them carries a full Javadoc block here rather than an inherited-documentation
 * reference. Two independent obligations require it. The project Explainability rule attaches its
 * docstring requirement to every function, class and module entry point and names no exemption, and
 * an overriding method still has to state what <em>this</em> implementation does with the contract it
 * inherits, which the supertype's own documentation cannot say. The Checkstyle configuration at
 * {@code config/checkstyle/checkstyle.xml} is then aligned with that rule deliberately rather than
 * left at its defaults: it clears the allowed-annotations list on its presence check, so the
 * exemption an overriding method would ordinarily receive does not apply, and it sets that check and
 * its coverage companion to the widest visibility, so a private nested handler is audited exactly as
 * a public class is. A member here left undocumented therefore fails the build as well as the
 * review, and the two agree by construction rather than by coincidence.</p>
 */
public final class MoneyModule extends SimpleModule {

    // WHY : Assumptions: the platform requires a serial version identifier on every serialisable
    //       type, and this type inherits serialisability from the Jackson module base class rather
    //       than declaring it. Pinning the value keeps it stable across builds instead of letting
    //       the compiler derive one that changes whenever a member is added.
    private static final long serialVersionUID = 1L;

    // WHY : Trade-offs: the module name is a fixed literal rather than the class's own simple name.
    //       The name is what appears in mapper diagnostics and in any registration listing, so
    //       deriving it from the class would let a rename silently change a value that other output
    //       is read against. The cost is that a rename must update this line too, which is the
    //       cheaper of the two failures. The constant stays private because getModuleName(), which
    //       the base class implements from this value, is already the public way to read it.
    private static final String MODULE_NAME = "carddemo-money";

    // WHY : Assumptions: the registration identifier MUST be non-null, and supplying it is the
    //       reason the three-argument constructor is used below. The Jackson module base class
    //       returns the concrete class name as its identifier, but the simple-module subclass
    //       overrides that to return whichever value its constructor received, and the one- and
    //       two-argument constructors both pass null. The mapper builder uses the identifier as a
    //       map key and removes any existing entry before inserting, so every module carrying a
    //       null identifier collides on the single null key and the last one registered evicts the
    //       others. Passing the class name restores the base behaviour and makes registration
    //       idempotent: registering this module twice replaces one entry rather than adding two.
    private static final Object REGISTRATION_ID = MoneyModule.class.getName();

    /**
     * Creates the module with its serialiser and deserialiser already registered.
     *
     * <p>A consumer needs no further configuration: constructing the module and handing it to a
     * mapper is the whole of the wiring. The two handlers are bound to {@link Money} alone, so no
     * other type's rendering is affected by registering this module.</p>
     *
     * <p>Assumptions: the version reported to Jackson is the unknown version, and the base class
     * requires only that it be non-null. The idiomatic alternative is a generated version class
     * built from a template resource, which needs both an additional build plugin and the resource
     * directory this library does not have. Restating the project version as a literal here was the
     * other option and was rejected: this module's descriptor deliberately keeps exactly one version
     * element in the whole build, and a second copy in Java source is a second place to edit at
     * release time and the usual source of a value that no longer matches.</p>
     *
     * <p>Trade-offs: the class is final, and one reason is visible right here. This constructor calls
     * the inherited registration methods, which are themselves overridable; in a subclassable class
     * those calls could be intercepted before the object was fully constructed, and a subclass could
     * substitute a handler that rendered a number. Sealing the class removes that path, at the cost
     * of a consumer being unable to extend the module to add an unrelated handler -- which is better
     * served by registering a second module anyway.</p>
     */
    public MoneyModule() {
        super(MODULE_NAME, Version.unknownVersion(), REGISTRATION_ID);

        // WHY : Assumptions: both handlers are registered against the concrete Money type rather
        //       than against the decimal type it wraps. Registering the decimal type would capture
        //       every unrelated decimal field in every payload of all eight consuming services --
        //       an interest rate, a credit score threshold, a page size -- and quote those too,
        //       changing contracts this module has no mandate over. Money is the type the money
        //       path is expressed in, so binding to it makes the quoting exactly as wide as the
        //       contract it enforces.
        addSerializer(Money.class, new MoneySerializer());
        addDeserializer(Money.class, new MoneyDeserializer());
    }

    /**
     * Renders a monetary amount into a JSON document as a quoted plain decimal string.
     *
     * <p>Assumptions: this handler is nested and private because the module is the only supported way
     * to obtain it. Exposing it would let a consumer attach it to a mapper without the matching
     * reader, and a mapper that quotes an amount on the way out while accepting a number on the way
     * in enforces half a contract, which is harder to diagnose than enforcing none. The module
     * registers the pair together for that reason.</p>
     *
     * <p>Assumptions: the inherited emptiness test is deliberately not overridden, and the omission
     * matters. That test reports a value empty only when it is absent, so a zero amount is not empty
     * -- which is the required answer, because a zero balance is a fact about an account rather than
     * a missing field. Overriding it to treat a zero amount as empty would drop {@code "0.00"} from
     * any payload configured to omit empty values, and a reader could not distinguish the resulting
     * absence from an amount that was never computed.</p>
     */
    private static final class MoneySerializer extends ValueSerializer<Money> {

        /**
         * Writes the amount into the generator as a JSON string of plain decimal text.
         *
         * @param value the amount to write. Jackson does not pass {@code null} here: a null property
         *     is routed to the mapper's own null handling before a value handler is consulted, so no
         *     null branch is written below rather than one being written and left unreachable
         * @param gen the generator to write into, already positioned where this value belongs in the
         *     document
         * @param ctxt the serialisation context, which this implementation does not consult because
         *     the wire form is fixed by the contract on the enclosing class and offers no
         *     configurable variant to resolve against
         * @throws JacksonException if the generator cannot accept the value, which in practice means
         *     the underlying stream has failed. Every Jackson failure is unchecked in this
         *     generation, so this declaration mirrors the inherited signature and adds no obligation
         *     for a caller
         */
        @Override
        public void serialize(Money value, JsonGenerator gen, SerializationContext ctxt)
                throws JacksonException {
            // WHY : Assumptions: the string writer is used where a numeric writer would otherwise be
            //       the obvious call, and this single line is what the whole class exists for. A
            //       numeric write emits a bare JSON number, which the majority of clients parse into
            //       an IEEE-754 binary64 value; the reference amount is twelve significant decimal
            //       digits at a scale of two, and that representation cannot hold most two-place
            //       decimal fractions exactly, so the loss lands in the cents.
            //       Alternatives Considered: rendering from the wrapped decimal's general text
            //       conversion was rejected, because that conversion is permitted to emit exponent
            //       notation at certain scales and the reference wire form is 14 fixed character
            //       positions with no exponent. Money.toPlainString() is the accessor the value type
            //       itself designates as this wire form, so the rendering has exactly one owner and
            //       the logged form of an amount cannot drift from its serialised form.
            gen.writeString(value.toPlainString());
        }

        /**
         * Reports the value type this serialiser handles.
         *
         * @return the {@link Money} class, never {@code null}
         */
        @Override
        public Class<?> handledType() {
            // WHY : Trade-offs: this restates the type the enclosing constructor already names when
            //       it registers the handler, and it is declared anyway because the inherited
            //       implementation reports the root object type rather than the type actually
            //       handled. A handler that leaves it inherited therefore describes itself
            //       incorrectly to Jackson's own diagnostics and to the single-argument registration
            //       form, which reads the handled type from the handler instead of from the call.
            //       The cost is one duplicated mention of the type; what it buys is that the two
            //       registration forms are interchangeable and no diagnostic reports this handler as
            //       handling everything.
            return Money.class;
        }
    }

    /**
     * Reads a monetary amount from a JSON document, accepting a string of plain decimal text and
     * nothing else.
     *
     * <p>Alternatives Considered: accepting a JSON number by reading it as an exact decimal was
     * evaluated and rejected, and the rejection is the deliberate half of this contract. Reading a
     * number token as a decimal would be exact on this side, so the argument for rejecting it is not
     * a local loss of precision. It is that a number token means the producer did not honour the
     * string form, and at this end a number that survived exactly is indistinguishable from one its
     * producer had already approximated. Accepting it would therefore restore nothing while making
     * the contract unenforceable, since a producer that is never told would keep emitting numbers.
     * There is also no legacy producer to accommodate: the JSON envelope is offered additively to
     * consumers that are new to the migrated system, and each service's published interface
     * description declares a monetary field as a string, so rejection reports a contract violation
     * at the first boundary that can still name its origin.</p>
     *
     * <p>Alternatives Considered: reporting each rejection through the context's weird-value handler
     * rather than through its input-mismatch report was evaluated and rejected. That handler consults
     * a configured problem handler and returns a substitute value when one is installed, which would
     * add a branch reachable only through a mapper configuration this module cannot exercise, and the
     * substitute it returns would be a silently different monetary amount. The input-mismatch report
     * always raises, so every rejection below has one outcome and one test.</p>
     *
     * <p>Assumptions: no value on this path passes through an IEEE-754 binary representation at any
     * point. The text is handed to {@link Money#of(String)}, which parses it with the exact decimal
     * type's own string constructor; there is no numeric accessor on the parser in the path at all,
     * which is what makes the string form worth requiring in the first place.</p>
     */
    private static final class MoneyDeserializer extends ValueDeserializer<Money> {

        /**
         * The stable reason recorded when the submitted characters are not plain decimal text.
         *
         * <p>Assumptions: a short upper-case token rather than a sentence, so a consumer matches on it
         * and an operator greps for it, and neither has to parse prose that may be reworded. The two
         * tokens below are the only reasons this deserialiser can report, because the factory it
         * delegates to has exactly two failure modes.</p>
         */
        private static final String REASON_MALFORMED = "MONEY_MALFORMED";

        /**
         * The stable reason recorded when the submitted amount exceeds the reference picture's domain.
         *
         * <p>Assumptions: kept distinct from {@link #REASON_MALFORMED} because the two mean different
         * things to whoever sent the payload -- one is a value that is not a number, the other a number
         * that is too large for the field it was sent for -- and a single token would leave a producer
         * unable to tell which correction to make.</p>
         */
        private static final String REASON_OUT_OF_DOMAIN = "MONEY_OUT_OF_DOMAIN";


        /**
         * Reads the amount from the current token, which must be a JSON string of plain decimal text.
         *
         * @param parser the parser positioned on the value to read
         * @param ctxt the deserialisation context, used to report any value the contract does not
         *     admit so that the resulting failure carries the location of the offending value within
         *     the document rather than only a message
         * @return the amount, at a scale of two and never {@code null}
         * @throws JacksonException if the current token is a JSON number, if it is any other
         *     non-string token, if the string is empty once surrounding whitespace is removed, if it
         *     is not plain decimal text, or if it lies outside the domain of the reference money
         *     picture. Each case is reported through the context, which raises a mismatched-input
         *     failure; that failure is a databind failure and therefore a Jackson failure, and every
         *     Jackson failure is unchecked in this generation
         */
        @Override
        public Money deserialize(JsonParser parser, DeserializationContext ctxt)
                throws JacksonException {
            JsonToken token = parser.currentToken();

            // WHY : Trade-offs: a numeric token is rejected in its own branch, with its own message,
            //       rather than falling through to the general one below. It is the violation a
            //       producer will actually commit, because a bare number is what an unconfigured
            //       mapper emits for a decimal, so a message that names the string form and says the
            //       number is refused on purpose is the difference between a producer that stops
            //       emitting numbers and a question that keeps reopening. The cost is one branch.
            //       Assumptions: the token's own numeric test is asked rather than the two numeric
            //       token constants being named, so this branch covers an integral and a fractional
            //       number identically and cannot be left half-updated.
            if (token != null && token.isNumeric()) {
                return ctxt.reportInputMismatch(this,
                        "Cannot read a monetary amount from a JSON number. Money is carried as a JSON"
                                + " string of plain decimal text, such as \"-2065.00\", and a number"
                                + " is refused rather than converted because it cannot be told apart"
                                + " from one whose producer had already rounded it.");
            }

            // WHY : Assumptions: every remaining non-string token is a structural mismatch rather
            //       than a value this handler could interpret, so one branch covers an object, an
            //       array and a boolean alike. The token is named in the message because the
            //       document location the context attaches identifies where, and the token
            //       identifies what, and a producer needs both.
            if (token != JsonToken.VALUE_STRING) {
                return ctxt.reportInputMismatch(this,
                        "Cannot read a monetary amount from token %s. Money is carried as a JSON"
                                + " string of plain decimal text, such as \"-2065.00\".", token);
            }

            // WHY : Trade-offs: surrounding whitespace is removed before parsing, which the exact
            //       decimal type's string constructor would otherwise reject outright. The leniency
            //       is bounded and it is earned: the reference wire form is a fixed-width character
            //       field, so a producer that copies such a field verbatim can carry padding that
            //       says nothing about the value. Only the ends are affected, so an interior space
            //       still fails and no digit is ever discarded.
            //       Alternatives Considered: the legacy trimming method was rejected on measured
            //       behaviour rather than on its age. It is defined by a numeric cutoff, removing
            //       every code point at or below U+0020, so it strips control characters that are
            //       not whitespace at all -- U+0001 among them -- while missing every space
            //       separator above the cutoff, including the ideographic space U+3000 that a
            //       producer in a wide-character locale can emit. The method used here is defined
            //       by the platform whitespace predicate instead, so it removes those wider
            //       separators and leaves the control characters to fail the parse.
            //       Assumptions: neither method removes a non-breaking space such as U+00A0, and
            //       that is the wanted behaviour rather than a shortfall. A non-breaking space is a
            //       deliberate character, not field padding, so it reaches the parse and is
            //       reported as a malformed value instead of being silently discarded from an
            //       amount.
            String text = parser.getString().strip();

            // WHY : Assumptions: an empty string is rejected rather than read as an absent amount.
            //       The reference field has no blank form at all -- the 14-character edited display
            //       picture zero-fills, so an amount of nothing renders as the digits of zero and
            //       never as spaces -- and a caller that means zero can say so exactly. Mapping
            //       blank to zero would invent a value the document did not carry, and mapping it to
            //       an absent amount would report a field that was present as one that was not.
            if (text.isEmpty()) {
                return ctxt.reportInputMismatch(this,
                        "Cannot read a monetary amount from an empty JSON string. An amount has no"
                                + " blank form; a zero amount is written \"0.00\".");
            }

            try {
                return Money.of(text);
            } catch (NumberFormatException malformed) {
                // WHY : Assumptions: the two failure modes of the factory are caught separately
                //       because they mean different things to whoever sent the payload. This one is
                //       a syntax failure: the characters are not a decimal number at all.
                // WHY : Refactoring Rationale: neither the submitted value nor the factory's own
                //       message reaches this diagnostic, where an earlier revision quoted both. The
                //       value is a monetary amount, so quoting it wrote the very content the money
                //       path exists to carry exactly into a message that travels to a mapper caller
                //       and from there into whatever that caller logs -- the one destination the
                //       masking applied at the API edge does not reach. The nested message added a
                //       second disclosure of its own: the platform's decimal parser reports the
                //       offending character sequence, so forwarding it re-quoted a fragment of the
                //       value even where the value itself had been withheld. What replaces both is a
                //       stable reason code plus the expected form. The document location the context
                //       attaches already names WHERE the value was, and the field being deserialised
                //       names WHICH value it was, so a producer has everything needed to find it in
                //       its own payload without this message carrying a copy of it.
                //       Trade-offs: a reader of the log can no longer see the offending characters,
                //       which is a real loss of immediacy when the defect is a stray currency symbol.
                //       It is accepted because the alternative is a monetary value in a log line, and
                //       because the producer holds the payload it sent.
                return ctxt.reportInputMismatch(this,
                        "Cannot read a monetary amount: the value is not plain decimal text"
                                + " [reason %s]. An amount is carried as a JSON string of plain"
                                + " decimal text, such as \"-2065.00\".", REASON_MALFORMED);
            } catch (ArithmeticException outOfDomain) {
                // WHY : Assumptions: this one is a domain failure: the characters parse, but the
                //       amount is wider than the reference money picture can hold. The bound is
                //       9999999999.99, being the ten integer digits and two decimal digits of the
                //       picture at line 7 of app/cpy/CVACT01Y.cpy, so an amount beyond it could not
                //       be stored or re-encoded even if it were accepted here. Reporting it through
                //       the context names the field and the document position, whereas letting the
                //       factory's own failure escape the mapper would name neither.
                // WHY : the value and the nested message are withheld here for the reason
                //       recorded on the syntax branch above. The BOUND is stated instead, because it
                //       is a property of the reference picture rather than of the submitted payload
                //       and it is the one fact a producer needs in order to correct the request.
                return ctxt.reportInputMismatch(this,
                        "Cannot read a monetary amount: the value lies outside the domain of the"
                                + " reference money field [reason %s]. The magnitude may not exceed"
                                + " %s.", REASON_OUT_OF_DOMAIN, Money.MAX_MAGNITUDE.toPlainString());
            }
        }

        /**
         * Reports the value type this deserialiser handles.
         *
         * @return the {@link Money} class, never {@code null}
         */
        @Override
        public Class<?> handledType() {
            // WHY : Trade-offs: declared for the same reason as its counterpart on the serialiser
            //       above -- the inherited implementation reports the root object type rather than
            //       the type actually handled, so a handler that leaves it inherited misdescribes
            //       itself to Jackson's diagnostics. The cost is one duplicated mention of the type.
            return Money.class;
        }
    }
}
