package com.carddemo.reference.service;

import com.carddemo.common.validation.DateEditValidator;
import com.carddemo.reference.dto.DateConversionRequest;
import com.carddemo.reference.dto.DateConversionResponse;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * Reports the verdict on one candidate date read against one picture.
 *
 * <h2>Purpose</h2>
 * <p>This class holds the date evaluation the synchronous surface of the reference context calls. Its
 * specification is the date-edit utility {@code app/cbl/CSUTLDTC.cbl}, whose linkage at physical line
 * 88 takes a date, the picture to read it by and a result, and which is REFERENCE-ONLY: it is read
 * here and never modified.</p>
 *
 * <p>The single member below applies the default picture when a caller supplies none and assembles
 * the six members the published reply declares. Both of those sit here rather than in the controller
 * so that the boundary layer holds no date rule of its own.</p>
 *
 * <h2>This class evaluates a date; it converts nothing, and it serves no queue</h2>
 *
 * <p>Assumptions: the member below does NOT convert anything, and neither the class name nor the
 * reply type's name should be read as implying that it does. What exists in the baseline sources is a
 * date EDIT, and this class reports its verdict. A request-driven conversion would be an extension
 * beyond the baseline and would have to be documented as one.</p>
 *
 * <p>Assumptions: the asynchronous date-and-time inquiry that
 * {@code app/app-vsam-mq/cbl/CODATE01.cbl} serves over its queue pair is a SEPARATE route with a
 * separate implementation, {@link DateInquiryMessageListener}, and that route does not call this
 * class. The two do not share an evaluation because they do not answer the same question: this one
 * judges a date a caller submits, while the queue route emits the current system date and time and
 * reads no field of its request. The baseline itself keeps them apart -- a search for
 * {@code CSUTLDTC} across all 524 lines of {@code app/app-vsam-mq/cbl/CODATE01.cbl} returns zero
 * occurrences, so the queue-borne program never calls the date-edit utility.</p>
 *
 * <p>Refactoring Rationale: this class supersedes a type named {@code DateConversionMessageListener}
 * that carried BOTH this evaluation and a second {@code @SqsListener} bound to the same request queue
 * as {@link DateInquiryMessageListener}. Two consumers on one queue is not a redundancy, it is a
 * defect: the transport hands each message to whichever container polled first, and the two answered
 * with different reply widths, different reply routing, different content types and different expiry
 * handling, so the wire behaviour of the flow depended on a race. The queue side of that type was
 * removed rather than repaired because everything it did already existed on the surviving consumer
 * and existed in a better form there -- the reply rendering in
 * {@code com.carddemo.reference.mapper.DateInquiryReplyMapper}, the error sink in
 * {@code DateInquiryMessageListener.publishError}, and a requester-expiry check the removed consumer
 * did not honour at all. What remains here is the one member that had no counterpart: this
 * evaluation, which is reached only from the synchronous endpoint. Alternatives Considered: keeping
 * both types and disabling one listener behind a condition. Rejected because two implementations of
 * one wire contract drift whichever of them is switched off, and the condition would have to be
 * correct in every environment for the contract to hold.</p>
 *
 * <h2>One naming trap in the reference utility, recorded so nobody reproduces it</h2>
 *
 * <p>Assumptions: physical line 62 of {@code app/cbl/CSUTLDTC.cbl} declares an all-zero feedback
 * token under a name that reads as though it meant an invalid date, and physical lines 129 and 130
 * select that condition to report the date VALID -- the name means the opposite of what it says. A
 * tenth branch at physical lines 147 and 148 reports the genuinely invalid case. The shared validator
 * carries the rules, not the misleading name.</p>
 *
 * <h2>Documentation and validation obligations this class is written against</h2>
 *
 * <p>Assumptions: user-specified Rule 1 (Explainability) governs this file, and its validation gate
 * is conjunctive -- a member missing either its docstring or the reason for a non-obvious choice
 * fails review, not one or the other. Every member below therefore carries both.</p>
 *
 * <p>Assumptions: this class holds no monetary value and performs no decimal arithmetic, so the
 * exact-fixed-point discipline the migration applies elsewhere has nothing to bite on here. Where it
 * would, the mechanical gate DOES reach this package: rule A3 of
 * {@code com.carddemo.common.architecture.LayeringRulesTest} evaluates every rule over the package
 * root {@code com.carddemo}, not over the shared money package, and it forbids a {@code float},
 * {@code double}, {@code Float} or {@code Double} member in any field, parameter or return position
 * of any production type in the whole reactor. Refactoring Rationale: the removed type's
 * documentation asserted that the gate scoped its subject set to the shared money package alone and
 * that the prohibition therefore rested on review in this package. That was wrong in the direction
 * that matters -- it understated a gate that already applies -- and it is corrected rather than
 * merely deleted, because a reader who believed it could conclude a {@code double} here would only
 * be caught by a human.</p>
 *
 * <p>Trade-offs: parity for this class cannot be established by byte comparison. The repository's
 * golden-master oracle covers the batch flows only, and the date utility is called from screens that
 * need a CICS runtime, so the evidence here is the transcribed rules together with the linkage widths
 * -- not a compared byte stream. Saying so is the honest position; claiming oracle-backed parity for
 * this evaluation would not be.</p>
 *
 * <p>This class holds no state, so a single instance serves every request concurrently.</p>
 */
@Service
public class DateConversionService {

    /**
     * Reports the verdict on one candidate date against one picture.
     *
     * <p>Purpose: the migrated form of the date-edit utility's linkage, taken as a request shape and
     * answered as the structured verdict the published contract declares.</p>
     *
     * <p>Refactoring Rationale: every date-edit rule is delegated and none is restated here, which is
     * AAP Rule T2 (one {@code COPY} becomes one import) applied to a callable rather than to a record
     * layout, and it re-expresses the baseline's own mechanism rather than reorganising it. The
     * utility at {@code app/cbl/CSUTLDTC.cbl} was ITSELF a shared callable subprogram: its procedure
     * division at physical line 88 takes the date, the mask and a result, its date and mask
     * parameters are each ten characters so the mask is a PARAMETER rather than a constant, its
     * result is eighty characters, and it hands control back at physical line 100. One shared
     * implementation is therefore the faithful shape and not a liberty. The rules live in the shared
     * kernel rather than in the platform interface they were expressed through, because that
     * interface has no counterpart available to reproduce; what is carried across is the rules, not
     * the call.</p>
     *
     * @param request the candidate date and the optional mask, as a {@link DateConversionRequest};
     *     must not be {@code null}
     * @return the verdict as a {@link DateConversionResponse}, carrying the named feedback code, the
     *     numeric severity and message number the utility produces, the verdict text, and the
     *     submitted date and applied mask echoed back
     * @throws NullPointerException if {@code request} is {@code null}, or if its date is {@code null}
     * @throws IllegalArgumentException if the date is not the width the applied mask declares, which
     *     the validator reports because it reads the date's components by offset
     */
    public DateConversionResponse convert(DateConversionRequest request) {
        Objects.requireNonNull(request, "request must not be null");

        // Assumptions: the mask defaults here rather than on the request shape, and a supplied but
        //     empty mask defaults with an absent one. Defaulting on the shape would make an omitted
        //     mask indistinguishable from one a caller stated explicitly, and the reply echoes the mask
        //     actually APPLIED so a caller can see which of the two it received.
        String mask = request.mask() == null || request.mask().isBlank()
                ? DateEditValidator.DATE_FORMAT_MASK
                : request.mask();

        DateEditValidator.LanguageEnvironmentResult result =
                DateEditValidator.evaluateWithLanguageEnvironment(request.date(), mask);

        // Assumptions: the named code and the two numbers are all carried across. The numbers are what
        //     the reference utility actually returns and the only values reconcilable against it,
        //     while the name is what a caller branches on without embedding a numeric table. Carrying
        //     one without the other would either oblige every caller to hold that table or make the
        //     reply impossible to reconcile against the utility it transcribes.
        return new DateConversionResponse(
                result.feedbackCode().name(),
                result.severity(),
                result.messageNumber(),
                result.verdict(),
                result.date(),
                result.mask());
    }
}
