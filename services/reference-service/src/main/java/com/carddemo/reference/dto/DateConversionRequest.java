package com.carddemo.reference.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The query parameters of the synchronous date evaluation.
 *
 * <p>Purpose: the inbound shape of the date-edit read published at
 * {@code GET /api/v1/reference/date-evaluations}, which is the migrated form of the baseline date
 * utility {@code app/cbl/CSUTLDTC.cbl}. That program declares its two inbound arguments
 * {@code 01 LS-DATE PIC X(10)} and {@code 01 LS-DATE-FORMAT PIC X(10)} at its lines 84 and 85,
 * returns {@code 01 LS-RESULT PIC X(80)} declared at its line 86, and takes all three in through the
 * {@code PROCEDURE DIVISION USING} at its line 88. The first two arguments are the two members here;
 * the third is the reply and travels back on {@code DateConversionResponse}. Nothing on this shape
 * reads a datastore, decides a rule or holds state between requests: the rules live in
 * {@code com.carddemo.common.validation.DateEditValidator}, and this type only states what a caller
 * may send.</p>
 *
 * <p>Assumptions: the date member is TEXT and is deliberately not a temporal type. This is the most
 * consequential decision on the shape, because the operation exists to answer whether a candidate
 * value is a usable date at all. A member typed as a date would be parsed by the binder before any
 * handler ran, so exactly the malformed inputs a caller asks about would be turned away with a
 * generic binding failure, and the distinct verdicts the baseline utility reports at its lines 130 to
 * 148 -- among them {@code 'Nonnumeric data'}, {@code 'Datevalue error'} and {@code 'Invalid month  '}
 * -- would collapse into one transport error that names none of them.</p>
 *
 * <p>Alternatives Considered: constraining the date member with a date-format pattern was evaluated
 * and rejected on that same ground. A pattern would duplicate the check the operation performs and
 * would then apply it in the wrong place, refusing the input instead of reporting on it, so the only
 * constraints here are presence and width. Whether a width agrees with the mask it was sent with is a
 * cross-member rule, and it belongs to the validator that reads the components by offset rather than
 * to a shape that sees each member on its own.</p>
 *
 * <p>Assumptions: the date width bounds are eight and ten, and both are the baseline's own. Ten is the
 * declared width of {@code LS-DATE PIC X(10)} and of the caller views that fill it --
 * {@code 05 CSUTLDTC-DATE PIC X(10)} at line 63 of {@code app/cbl/COTRN02C.cbl} and at line 130 of
 * {@code app/cbl/CORPT00C.cbl}. Eight is the unseparated form, which the baseline itself hands to that
 * same ten-character argument, and the shared validator publishes both widths as
 * {@code PACKED_DATE_LENGTH} and {@code MASKED_DATE_LENGTH}. Turning the eight-character form away
 * here would turn away a value the published contract lists as an example of this very parameter.</p>
 *
 * <p>Assumptions: two mask literals reach this operation, they are declared at different widths, and
 * both are carried across character for character. {@code app/cbl/COTRN02C.cbl} declares
 * {@code 05 WS-DATE-FORMAT PIC X(10) VALUE 'YYYY-MM-DD'} at its line 60, while
 * {@code app/cpy/CSUTLDWY.cpy} declares {@code 10 WS-DATE-FORMAT PIC X(08) VALUE 'YYYYMMDD'} at its
 * lines 58 and 59. Because the utility's argument is ten characters wide, the eight-character literal
 * arrives space-padded: {@code app/cpy/CSUTLDPY.cpy} moves it at its line 291 and calls the utility at
 * its line 293 with no adjustment of any kind in between. The padding is a property of the argument
 * rather than of the literal, which is why the bound below is AT MOST ten characters and not exactly
 * ten.</p>
 *
 * <p>Refactoring Rationale: the two literals are not reduced to one canonical mask. They are separate
 * call sites at separate declared widths, and a shape admitting only the wider of them would refuse
 * every caller written against the narrower one while appearing to tighten nothing. Both are
 * admissible input to the utility, so both stay admissible here, and the shared validator publishes
 * them side by side as {@code DATE_FORMAT_MASK} and {@code BASELINE_DATE_FORMAT_MASK}.</p>
 *
 * <p>Alternatives Considered: enumerating the mask as those two values was evaluated and rejected. The
 * utility takes the mask as data and reports {@code 'Bad Pic String '} at its line 142, under the
 * condition it declares at its line 68, when it cannot use the pattern it was handed -- which is a
 * verdict on an evaluation that ran, not a transport error. An enumeration on this shape would be
 * applied by the deserialiser before any handler ran, so that one verdict would become unreachable
 * and a caller would receive a refusal where the baseline gives a feedback code. The published
 * contract reaches the same conclusion and types this parameter as its wider bounded string rather
 * than as its enumeration of recognised masks, so the bound below is a width alone.</p>
 *
 * <p>Assumptions: the mask carries no presence constraint, because the published contract declares the
 * parameter as not required and names the separated form as its default. The member this shape feeds
 * tells an omitted mask from a supplied one by testing the component for absence, and it defaults
 * there rather than here precisely so that the reply can echo the picture actually applied. A presence
 * constraint on this member would leave that branch unreachable and would refuse every caller relying
 * on the documented default.</p>
 *
 * <p>Assumptions: no member selects an output format, because the operation this shape serves is an
 * edit that returns a verdict and publishes no conversion, so there is no output to select and an
 * absent selection is the only state there is. The reason is worth stating at length even so, because
 * the baseline holds a code domain that invites such a member, and a later reader adding one has to
 * find the domain recorded rather than infer it. {@code app/cpy/CODATECN.cpy} assigns the SAME two
 * code values to opposite formats depending on direction. On input it declares
 * {@code 88 YYYYMMDD-IN VALUE "1"} and {@code 88 YYYY-MM-DD-IN VALUE "2"} at its lines 20 and 21, so
 * there {@code "1"} selects the unseparated form and {@code "2"} the separated one. On output it
 * declares {@code 88 YYYY-MM-DD-OP VALUE "1"} and {@code 88 YYYYMMDD-OP VALUE "2"} at its lines 37
 * and 38, so there {@code "1"} selects the SEPARATED form and {@code "2"} the unseparated one. The two
 * directions read as though they agreed, and they do not. Live use settles it:
 * {@code app/cbl/CBACT01C.cbl} moves {@code '2'} into the input code at its line 225 and {@code '2'}
 * into the output code at its line 226, in adjacent statements, meaning separated in and unseparated
 * out from one repeated value. A reader who takes the codes to be symmetric inverts every conversion,
 * so the domain is recorded here in full even though no member carries it. The copybook writes those
 * clauses with quotation marks where the rest of the baseline uses apostrophes, which is noted only so
 * that the citation is faithful.</p>
 *
 * <p>Alternatives Considered: carrying an optional member that selected one of those output codes was
 * evaluated and rejected on three grounds. The published contract
 * {@code services/reference-service/src/main/resources/openapi/reference-api.yaml} declares this
 * operation with the date and the mask alone and declares no output selection anywhere in it; the
 * reply shape {@code DateConversionResponse} has no member able to carry a converted value, so a
 * selection would ask for an output nothing could answer; and the package charter beside this file
 * makes that contract authoritative for every member of every shape here and asks specifically that
 * members not be re-derived from a copybook. An absent selection is therefore not a defaulted one:
 * this operation reports a verdict and performs no conversion, which is what the contract states of
 * the migrated system.</p>
 *
 * <p>Assumptions: {@code app/cpy/CODATECN.cpy} is cited above as the authority for that output code
 * domain and NOT as a call path being ported. The one program that copies it is
 * {@code app/cbl/CBACT01C.cbl}, which hands the whole record to the assembler routine
 * {@code 'COBDATFT'} at its line 231, and the migration retires the assembler modules and the macro
 * library with no cloud analogue. Nothing on this endpoint is to be wired to an assembler-shaped
 * conversion; where the migrated behaviour departs from the baseline path, the departure is registered
 * in {@code docs/architecture/cobol-to-service-traceability.md}.</p>
 *
 * <p>Assumptions: the member names here are ordinary Java names and are not transcriptions of the
 * copybook's own. That copybook spells its output group {@code CODATECN-0UT-DATE} with a digit at its
 * line 39, ends two filler names with a lower-case letter in {@code CODATECN-1OFIl} and
 * {@code CODATECN-2OFIl} at its lines 46 and 51, declares {@code CODATECN-1MM} at both its line 25 and
 * its line 31, declares {@code CODATECN-1O-YYYY} inside an input redefinition at its line 29 and an
 * output redefinition at its line 41, and places {@code CODATECN-2YY} in the day position of the
 * redefinition at its line 33. Those are properties of the baseline, cited so a reader can see they
 * were read rather than missed, and left exactly as they stand.</p>
 *
 * <p>Refactoring Rationale: none of the transport frame around the baseline's asynchronous date flow
 * travels on this shape. {@code app/app-vsam-mq/cbl/CODATE01.cbl} is queue plumbing from end to end --
 * it references {@code CODATECN} nowhere and calls {@code CSUTLDTC} nowhere -- and its buffers are
 * {@code 01 MQ-BUFFER PIC X(1000)} at its line 50 together with {@code QUEUE-MESSAGE},
 * {@code REQUEST-MESSAGE}, {@code REPLY-MESSAGE} and {@code ERROR-MESSAGE}, each declared
 * {@code PIC X(1000)}, at its lines 105 to 108. Its request frame {@code 01 REQUEST-MSG-COPY} is
 * {@code 10 WS-FUNC PIC X(04)} plus {@code 10 WS-KEY PIC 9(11)} plus
 * {@code 10 WS-FILLER PIC X(985)}, so those thousand bytes are padding around a four-character
 * function and a key rather than payload. A shape reproducing that frame would carry padding as
 * data.</p>
 *
 * <p>Assumptions: the queue names that program hard-codes are absent for the same reason. It moves
 * {@code 'CARD.DEMO.REPLY.DATE'} at its line 147 and {@code 'CARD.DEMO.ERROR'} at its line 243, and a
 * destination is a deployment concern that a request never names. The reply-queue literal is worth
 * recording for the messaging documentation as well, because the migration's queue table carries an
 * error sink but no reply destination of that name; noting it belongs to that document, and is not
 * something a shape can settle.</p>
 *
 * <p>Refactoring Rationale: there is no correlation member either. That program carries
 * {@code 01 MQ-CORRELID PIC X(24)} and {@code 01 MQ-MSG-ID PIC X(24)} at its lines 52 and 53 because a
 * queue reply has to be matched to its request, and the migrated system matches on a header handled by
 * {@code com.carddemo.common.web.CorrelationIdFilter} instead. Identity of a unit of work placed in a
 * payload becomes something a caller can contradict and something every shape has to restate.</p>
 *
 * <p>Refactoring Rationale: the twenty-character date member is dropped as well.
 * {@code app/cpy/CODATECN.cpy} declares {@code 10 CODATECN-INP-DATE PIC X(20)} at its line 22 and
 * slices it two ways through the redefinitions at its lines 23 and 28, whose trailing
 * {@code 15 CODATECN-1FIL PIC X(12)} at its line 27 and {@code 15 CODATECN-2FIL PIC X(10)} at its line
 * 34 exist so that either layout fits one record of a set length. This shape carries the date at the
 * width the utility takes and drops that padding, which is the treatment the migration prescribes for
 * filler generally.</p>
 *
 * @param date the candidate date as text, required and deliberately unconstrained in format; between
 *     eight and ten characters, the widths of the unseparated and separated forms the baseline
 *     recognises, and reported on rather than refused when it is not a usable date
 * @param mask the picture the candidate is read against, at most the ten characters the baseline
 *     argument declares and deliberately not narrowed to a set of values; absent means the separated
 *     form, which the service applies so the reply can echo the picture that was used
 */
public record DateConversionRequest(
        @NotBlank @Size(min = 8, max = 10) String date,
        @Size(min = 1, max = 10) String mask) {
}
