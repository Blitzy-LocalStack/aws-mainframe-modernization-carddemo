package com.carddemo.reference.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The query parameters of the date evaluation.
 *
 * <p>Purpose: the inbound shape of the synchronous date-evaluation read, which is the migrated form of
 * the baseline's date-edit utility. The date is required; the mask is optional and defaults to the
 * ISO-ordered form.</p>
 *
 * <p>Assumptions: the date member carries NO format constraint, and its absence is the point of the
 * operation rather than an omission. This endpoint exists to report a verdict on a candidate value,
 * so a shape that refused a malformed date would refuse exactly the inputs the caller is asking about
 * and would answer a generic validation failure in place of the specific feedback the baseline
 * produces. The only constraints are presence and a width, because a value that is absent or absurdly
 * long is not a date the utility has a verdict for.</p>
 *
 * <p>Alternatives Considered: typing the member as a date was evaluated and rejected for the same
 * reason. A binder that parsed it would reject the invalid case before the service saw it, and the
 * feedback code the reply carries would then be unreachable for every input that most needs it.</p>
 *
 * @param date the candidate date to evaluate, taken as text and deliberately unconstrained in format
 * @param mask the picture the candidate is to be read against; absent means the ISO-ordered form,
 *     which is the default the contract publishes
 */
public record DateConversionRequest(
        @NotBlank @Size(min = 1, max = 32) String date,
        @Size(min = 1, max = 10) String mask) {
}
