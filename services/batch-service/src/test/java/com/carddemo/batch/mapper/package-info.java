/**
 * Executable coverage for the three fixed-width record boundaries of this module.
 *
 * <h2>Purpose</h2>
 *
 * <p>This package holds one test class per mapper in the production
 * {@code com.carddemo.batch.mapper} package. It exists because all three mappers previously had
 * <b>zero</b> test references anywhere in the reactor: the shared codec's own tests prove that a
 * declared field can be sliced out of a record, but nothing proved the object-to-field maps these
 * three types declare. Those maps are where a migration loses money silently -- a money field read
 * through the wrong sign regime, a key trimmed where its width is the contract, a timestamp accepted
 * in only one of the two forms the baseline emits, or a trailing pad rebuilt as blanks where the
 * parity oracle holds low values.</p>
 *
 * <p>Assumptions: each test class transcribes its record images as literal constants assembled from
 * named field pieces rather than reading a file from the repository tree. Alternatives Considered:
 * resolving {@code app/data/ASCII/acctdata.txt} and {@code tests/golden/posting/happy_path} by
 * relative path, which would tie every assertion to real reference bytes. Rejected because a
 * relative path resolves differently from the reactor root and from the module directory, so the
 * suite would pass in one invocation and fail in the other; and because a literal assembled from
 * named pieces is a <b>second independent reading</b> of the copybook, which is the property a
 * transcription test needs. Every literal here was nonetheless captured from a real record and
 * checked against it byte for byte before being written down.</p>
 *
 * <p>Assumptions: nothing in this package is a production type, so it declares no public API, holds
 * no state and is never referenced from {@code src/main}.</p>
 */
package com.carddemo.batch.mapper;
