package com.carddemo.common.error;

import java.util.List;

/**
 * Declares the order in which a request body's fields are checked, so that the FIRST failure can be named.
 *
 * <h2>The problem this exists for</h2>
 *
 * <p>Refactoring Rationale: several migrated screens report the first failure and only the first, and one
 * of them -- sign-on -- makes that observable in the exact sentence a user sees.
 * {@code app/cbl/COSGN00C.cbl} tests the identifier at line 118 and reports
 * {@code 'Please enter User ID ...'} at line 120, then tests the credential at line 123 and reports
 * {@code 'Please enter Password ...'} at line 125, in a chain that STOPS at the first failure. A caller
 * submitting both fields empty is therefore told about the identifier and never about the credential.
 * Bean Validation cannot reproduce that on its own: it evaluates every constraint on the body, in no
 * defined order, and the shared advice then reported a generic aggregate sentence with both field entries
 * accumulated. The aggregate sentence was therefore never the baseline's, and which field appeared first
 * in the array was whatever order the provider happened to produce -- so a promise to carry the sentence
 * across verbatim under transformation rule T8 could not be kept.</p>
 *
 * <p>A request record implements this interface to state the order its own screen checks its fields in.
 * The shared advice then sorts the field entries into that order and latches the FIRST entry's own message
 * as the aggregate sentence, which is exactly the baseline's two-part behaviour: one sentence, latched to
 * the first failure, and per-field markers that accumulate.</p>
 *
 * <h2>Why an opt-in interface rather than a change to the advice</h2>
 *
 * <p>Alternatives Considered: making the advice always latch the first field entry's message, with no
 * interface at all. Rejected because most migrated screens accumulate rather than latch -- the divergence
 * register carries an entry for exactly that, {@code D-ERROR-ACCUMULATION}, recording that every violation
 * is reported where the baseline reported only the first -- so a global change would alter the observable
 * behaviour of every one of them to fix one. An opt-in leaves those responses byte-identical and changes
 * only the bodies whose own screen latched.</p>
 *
 * <p>Alternatives Considered: an annotation carrying the order, read reflectively. Rejected because an
 * annotation cannot be checked against the record's actual components by the compiler, so a renamed
 * component would leave a stale name in the annotation and the ordering would silently fall back to
 * encounter order -- the failure this interface exists to remove. A method returning names is no better
 * checked, but it sits next to the components and is covered by the record's own test.</p>
 *
 * <p>Trade-offs: the order is stated as field NAMES rather than as an ordered list of accessors, which
 * means a typo produces an unordered entry rather than a compile error. The accepted mitigation is that
 * the advice places an unrecognised name LAST rather than dropping it, so a typo degrades to encounter
 * order for that one field and never loses an entry; and the implementing record asserts its own list
 * against its record components in a test.</p>
 */
public interface FieldOrdering {

    /**
     * Returns the field names in the order the originating screen checks them.
     *
     * <p>Assumptions: the names are the ones a client keys a field entry by -- the request body's own
     * property names -- because that is what the advice compares against. They are NOT the copybook or
     * map field names, which differ; the mapping between the two lives in the record's documentation.</p>
     *
     * @return the ordered field names, never {@code null} and never containing {@code null}; a name the
     *     body does not declare is harmless and simply matches nothing
     */
    List<String> fieldOrder();
}
