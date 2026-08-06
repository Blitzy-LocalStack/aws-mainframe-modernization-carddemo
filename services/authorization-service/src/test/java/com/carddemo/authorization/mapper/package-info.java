/**
 * Contract tests for the one crossing between the wire records and this context's queue payloads.
 *
 * <p><b>Purpose.</b> One class executes here. {@code AuthorizationMessageMapperTest} asserts that the two
 * representations of the authorization request and reply agree: that the crossing carries every component
 * to the component of the same meaning, that no payload width is narrower than the width its copybook
 * declares, that a round trip in either direction is identity, and that the payload's own value domains
 * are applied on every crossing rather than declared and forgotten.</p>
 *
 * <p>Assumptions: this package exists because the duplication it tests is deliberate and cannot be
 * removed. {@code com.carddemo.common.codec.CsvAuthCodec} owns the delimited wire form -- field order, the
 * comma delimiter, the declared width table and the edited money rendering -- because with a
 * string-format payload those things are the interface for the requester already waiting on the queue. The
 * two payload records in {@code com.carddemo.authorization.dto} are this context's structured
 * representation of the same contract, carrying the copybook documentation and the Jakarta constraints.
 * Two representations of one contract can only be kept in agreement mechanically, which is what this
 * package does.</p>
 *
 * <h2>Why these tests exist</h2>
 *
 * <p>Assumptions: before the remediation these tests accompany, nothing converted between the two
 * representations and nothing validated a payload, so the constraints on both payload records were
 * unreachable. They had already drifted: three character components were restricted to digits although
 * their copybook fields are {@code PIC X}, and three widths were pinned as exact although the codec pads a
 * short value. Neither drift could be detected, because no test and no production path exercised the
 * declarations at all. The width assertion here is deliberately driven from the codec's own published
 * width table rather than from literals, so a copybook width that moves is caught in one place instead of
 * being restated in a second one.</p>
 *
 * <h2>Documentation contract</h2>
 *
 * <p>This charter exists because {@code JavadocPackage} in {@code config/checkstyle/checkstyle.xml}
 * audits any directory holding a source file the gate processes, and the gate includes test sources.
 * Parameters, return values and exceptions are inapplicable to a package declaration rather than omitted
 * from it, since there is no callable member here; the inapplicability is stated because a docstring that
 * silently omits them is one of the patterns user-specified Rule 1 forbids.</p>
 */
package com.carddemo.authorization.mapper;
