package com.carddemo.authorization.config;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.type.LogicalType;

/**
 * Holds the request reader to the character domains this context's published contract declares.
 *
 * <h2>Why this configuration exists</h2>
 *
 * <p>⚠️ Refactoring Rationale: every scalar member of every request body this context publishes is
 * declared {@code type: string} in {@code openapi/authorization-api.yaml}, and each one stands for a
 * fixed-width CHARACTER field on a reference map -- the eleven-character account scope, the sealed
 * selector, the paging direction, the one-character fraud action. The reader's default behaviour is to
 * accept a JSON NUMBER for such a member and convert it, so {@code {"accountId":10000000101}} was
 * answered 200 for account 20000000001 even though the document says the member is a string. The
 * conversion is what makes it undetectable: the number that arrives is renderable as exactly the digits
 * the pattern constraint expects, so validation passes and nothing anywhere reports that the caller sent
 * a shape the contract does not admit.</p>
 *
 * <p>⚠️ Assumptions: the refusal is declared for the TEXTUAL target family and for the three scalar
 * input shapes that are not text -- integer, floating point and boolean. It is deliberately NOT declared
 * as a blanket withdrawal of scalar coercion, because coercion in the OPPOSITE direction is a load-bearing
 * part of this migration: money crosses every boundary as a JSON string and is read into a
 * {@code BigDecimal}, which is a string-shaped input to a numeric target. Turning coercion off wholesale
 * would refuse exactly the money contract that section 0.1.3.1's fixed-point rule requires, and it would
 * do so on the cross-context account read, which carries three amounts. The asymmetry here is therefore
 * the point: text may be read as a number, a number may not be read as text.</p>
 *
 * <p>⚠️ Alternatives Considered: {@code spring.jackson.mapper.allow-coercion-of-scalars: false}, which is
 * a single property and needs no class. Rejected for the reason above -- it is symmetric, so it would
 * take the money path down with it. Also considered: leaving the leniency in place and relying on the
 * pattern constraint. Rejected because the constraint sees the CONVERTED value, so it cannot tell a
 * conforming string from a number that converted into one, which is the whole defect.</p>
 *
 * <p>⚠️ Trade-offs: a client that has been sending a bare number for the account scope now receives 400
 * where it previously received an answer. That is the intended effect of enforcing a published domain, and
 * it is the same trade the sibling {@code fail-on-unknown-properties} setting in this module's
 * {@code application.yml} makes for an undeclared member; the two are one decision about whether the
 * document or the reader's tolerance governs. The refusal is scoped to THIS module rather than the shared
 * kernel, because the contracts it enforces are this module's own and a shared default would change seven
 * other services that no measurement covers.</p>
 *
 * <p>⚠️ Assumptions: the customiser is a bean of the framework's own JSON mapper-builder customiser type,
 * so it is applied to the SAME mapper the web layer builds rather than to a second mapper this class
 * constructs. A separate mapper would be configured correctly and read nothing, because the request
 * converter would keep using the framework's.</p>
 *
 * <p>A configuration class accepts no parameter, yields no value and raises nothing, so this block
 * carries no at-clause; the convention is {@code docs/CODE_DOCUMENTATION_STANDARD.md}.</p>
 */
@Configuration(proxyBeanMethods = false)
public class JsonReadConfig {

    /**
     * Refuses a non-textual scalar where the contract declares a character field.
     *
     * <p>⚠️ Assumptions: the three shapes are enumerated rather than expressed as "everything but a
     * string", because the enumeration also names what is NOT refused. An array, an object and a null
     * reaching a textual member are left to the reader's own handling: the first two already fail as shape
     * mismatches, and a null is a legitimate absent value for the two optional paging members, so
     * refusing it here would turn "no cursor supplied" into a malformed body.</p>
     *
     * @return the customiser the framework applies to the mapper it builds for the web layer, never
     *     {@code null}
     */
    @Bean
    public JsonMapperBuilderCustomizer refuseNonTextualScalarsForTextTargets() {
        return builder -> builder.withCoercionConfig(LogicalType.Textual, config -> {
            config.setCoercion(CoercionInputShape.Integer, CoercionAction.Fail);
            config.setCoercion(CoercionInputShape.Float, CoercionAction.Fail);
            config.setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
        });
    }
}
