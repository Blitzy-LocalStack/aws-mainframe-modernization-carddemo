package com.carddemo.reference.api;

import com.carddemo.reference.dto.DateConversionRequest;
import com.carddemo.reference.dto.DateConversionResponse;
import com.carddemo.reference.service.DateConversionMessageListener;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The synchronous date evaluation, the migrated form of the baseline date-edit utility.
 *
 * <p>Purpose: binds the candidate date and the optional mask and delegates to the same member the
 * asynchronous route uses, so the two cannot report different verdicts for one input.</p>
 *
 * <p>Assumptions: the date parameter is bound as text and is deliberately NOT bound as a date. This
 * endpoint exists to report a verdict on a candidate value, so a binder that parsed it would refuse
 * exactly the inputs a caller is asking about and would answer a generic validation failure in place of
 * the specific feedback the reference produces.</p>
 */
@RestController
@RequestMapping(DateEvaluationController.BASE_PATH)
public class DateEvaluationController {

    /** The path the contract declares. */
    public static final String BASE_PATH = "/api/v1/reference/date-evaluations";

    /** The query parameter carrying the candidate date. */
    public static final String PARAM_DATE = "date";

    /** The query parameter carrying the picture to read it against. */
    public static final String PARAM_MASK = "mask";

    /** The evaluator this controller delegates to. */
    private final DateConversionMessageListener evaluator;

    /**
     * Builds the controller over the evaluator.
     *
     * @param evaluator the date rules, shared with the asynchronous route; must not be {@code null}
     */
    public DateEvaluationController(DateConversionMessageListener evaluator) {
        this.evaluator = evaluator;
    }

    /**
     * Reports the verdict on one candidate date.
     *
     * @param date the candidate date, required and deliberately unconstrained in format
     * @param mask the picture to read it against, absent meaning the default the contract publishes
     * @return the verdict, carrying both the named feedback code and the numeric severity and message
     *     number
     */
    @GetMapping
    public DateConversionResponse evaluateDate(
            @RequestParam(name = PARAM_DATE) String date,
            @RequestParam(name = PARAM_MASK, required = false) String mask) {

        DateConversionRequest request = new DateConversionRequest(date, mask);
        return this.evaluator.convert(validated(request));
    }

    /**
     * Returns the request unchanged, marked so the container validates its constraints.
     *
     * <p>Assumptions: the shape is built here from two query parameters rather than bound directly,
     * because the contract declares them as query parameters and not as a body. Passing the assembled
     * shape through a validated member is what makes its presence and width constraints apply, which a
     * shape merely constructed would not.</p>
     *
     * @param request the assembled shape
     * @return the same shape
     */
    private DateConversionRequest validated(@Valid DateConversionRequest request) {
        return request;
    }
}
