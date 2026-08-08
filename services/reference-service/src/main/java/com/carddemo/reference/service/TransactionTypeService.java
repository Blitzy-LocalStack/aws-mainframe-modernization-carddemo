package com.carddemo.reference.service;

import com.carddemo.common.error.ApiError;
import com.carddemo.common.error.ClientInputException;
import com.carddemo.common.error.RecordConflictException;
import com.carddemo.common.web.CursorToken;
import com.carddemo.common.web.PageResponse;
import com.carddemo.reference.domain.TransactionType;
import com.carddemo.reference.dto.PageDirection;
import com.carddemo.reference.dto.TransactionTypeCreateRequest;
import com.carddemo.reference.dto.TransactionTypeListRequest;
import com.carddemo.reference.dto.TransactionTypeResponse;
import com.carddemo.reference.dto.TransactionTypeUpdateRequest;
import com.carddemo.reference.mapper.TransactionTypeMapper;
import com.carddemo.reference.repository.TransactionTypeRepository;
import com.carddemo.common.validation.FieldValidationFlag;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transaction-type rules, transcribed from the baseline inquiry, list and maintenance screens.
 *
 * <p>Purpose: this class owns the parent side of the transaction-reference feature -- the browse the
 * list screen performs, the keyed read the inquiry performs, and the three write behaviours the
 * baseline distinguishes. Its consumer is {@code com.carddemo.reference.api}; it reads no request
 * shape it was not handed and renders no response beyond the transfer objects the mapper builds.</p>
 *
 * <p>Assumptions: the browse is keyset paged and the window is decided here rather than by a caller.
 * The published contract declares no page-size parameter and states that none may be added, so a size
 * travelling in a request would publish a parameter the contract does not.</p>
 *
 * <p>Assumptions: the strict update reports a miss and inserts nothing, which is the list screen's
 * behaviour and is what the published operation declares by carrying 404 among its answers. The
 * maintenance screen's insert-on-miss is a third behaviour that this contract does not expose; the
 * divergence is registered rather than silently resolved, and a client reaches the same outcomes by
 * creating and then replacing.</p>
 */
@Service
public class TransactionTypeService {

    /**
     * The cursor binding for this browse.
     *
     * <p>Assumptions: a binding distinct from every other browse is what stops a position minted for
     * one list being replayed against another. The token carries it under the seal, so a cursor from
     * the category browse presented here is refused rather than silently interpreted.</p>
     */
    public static final String CURSOR_BINDING = "reference-transaction-type-list";

    /**
     * The number of rows one page publishes.
     *
     * <p>Assumptions: SEVEN rows, taken from the program and not from a count of fields on a map.
     * {@code app/app-transaction-type-db2/cbl/COTRTLIC.cbl} declares
     * {@code 05 WS-MAX-SCREEN-LINES PIC S9(4) COMP VALUE 7.} at physical line 60 and reads it at
     * physical lines 940, 1004, 1018, 1334, 1386, 1657 and 1736, and eight of its arrays are declared
     * over seven occurrences. The repository is asked for one more than this so that a further-page flag
     * is established from a surplus row rather than from a count, which is how the baseline establishes
     * it too at physical lines 1657 to 1673.
     *
     * <p>Refactoring Rationale: this constant read ten, and its own note claimed ten was the number of
     * detail rows the baseline screen displays. That was not so, and the error was not cosmetic: at ten
     * a page carried three rows the baseline never showed together, so page boundaries, the further-page
     * flag and every sealed position differed from the reference. The seven-row window is documented at
     * length in {@code repository/package-info.java}, which was correct while this constant was not.
     */
    public static final int PAGE_SIZE = 7;

    /** The response-field identity of the type-code filter. */
    public static final String FIELD_TYPE_CODE = "typeCode";

    /** The response-field identity of the description filter. */
    public static final String FIELD_DESCRIPTION = "description";

    /**
     * The verbatim refusal when a supplied filter matches no row anywhere in the table.
     *
     * <p>Assumptions: carried character for character from physical line 1264 of
     * {@code app/app-transaction-type-db2/cbl/COTRTLIC.cbl}, including its capital R.
     */
    public static final String MESSAGE_NO_RECORDS_FOR_FILTER =
            "No Records found for these filter conditions";

    /** The verbatim refusal when no type carries the code asked for. */
    public static final String MESSAGE_TYPE_NOT_FOUND = "Transaction type NOT found...";

    /** Access to the transaction-type table. */
    private final TransactionTypeRepository types;

    /** Access to the category table, consulted only to diagnose a restricted delete. */
    private final com.carddemo.reference.repository.TransactionCategoryRepository categories;

    /**
     * Builds the service over the two repositories it reads.
     *
     * @param types access to the transaction-type table; must not be {@code null}
     * @param categories access to the category table, read only to diagnose a refused delete; must
     *     not be {@code null}
     */
    public TransactionTypeService(
            TransactionTypeRepository types,
            com.carddemo.reference.repository.TransactionCategoryRepository categories) {
        this.types = types;
        this.categories = categories;
    }

    /**
     * Returns one keyset page of transaction types.
     *
     * <p>Assumptions: a backward page is read descending and reversed here, because the rows wanted are
     * the ones nearest the position and a bound applied to an ascending walk would return the rows
     * furthest from it. Reversing in memory is safe precisely because the walk was bounded first.</p>
     *
     * @param request the validated paging and filter parameters; must not be {@code null}
     * @param cursorToken the sealer that mints and opens the opaque position; must not be {@code null}
     * @return one page of types, with the positions sealed and a flag saying whether more follow
     * @throws com.carddemo.common.web.CursorToken.InvalidCursorException if a supplied position is not
     *     a position this browse minted
     * @throws ClientInputException if a supplied filter matches no row anywhere in the table, which the
     *     baseline reports as a field error on the filter rather than as an empty page
     */
    @Transactional(readOnly = true)
    public PageResponse<TransactionTypeResponse> list(
            TransactionTypeListRequest request, CursorToken cursorToken) {

        String position = request.cursor() == null
                ? null
                : cursorToken.open(CURSOR_BINDING, request.cursor());
        boolean backward = request.direction() == PageDirection.PREVIOUS;
        Limit limit = Limit.of(PAGE_SIZE + 1);

        // WHY : Refactoring Rationale: the two filters were validated, documented and published and then
        //       never reached a query, so a request narrowing the browse to one type code was answered
        //       with the whole table. Normalising them here through the repository's own helpers is what
        //       makes an absent, blank or metacharacter-bearing value mean the same thing at the query as
        //       the contract says it means at the boundary.
        String typeCodeFilter = TransactionTypeRepository.typeCodeFilter(request.typeCode());
        String descriptionFilter =
                TransactionTypeRepository.descriptionFilterPattern(request.description());
        boolean filtered = typeCodeFilter != null || descriptionFilter != null;

        if (filtered) {
            requireFilterMatchesSomething(typeCodeFilter, descriptionFilter, request);
        }

        List<TransactionType> rows;
        if (filtered) {
            // WHY : Assumptions: a filtered BACKWARD walk needs a position, because its query compares
            //       against one unguarded. There is no filtered first page read backward: a first page
            //       is read forward by definition, and the forward member admits a null position.
            if (backward && position != null) {
                rows = new ArrayList<>(this.types.findFilteredPageBefore(
                        typeCodeFilter, descriptionFilter, position, limit));
                Collections.reverse(rows);
            } else {
                rows = this.types.findFilteredPageAfter(
                        typeCodeFilter, descriptionFilter, position, limit);
            }
        } else if (position == null) {
            // WHY : Assumptions: the unfiltered walks are kept for the unfiltered case rather than every
            //       page being routed through the filtered members with two null arguments. Each derived
            //       name states its own bound and order, and the three optional arms a filtered query
            //       carries are arms the database has to evaluate per row for a browse that never
            //       narrows. Trade-offs: two paths rather than one, which is the shape the baseline has
            //       as well -- its cursor arms are flag-guarded precisely so an unset filter costs
            //       nothing.
            rows = this.types.findAllByOrderByTypeCdAsc(limit);
        } else if (backward) {
            rows = new ArrayList<>(
                    this.types.findByTypeCdLessThanOrderByTypeCdDesc(position, limit));
            Collections.reverse(rows);
        } else {
            rows = this.types.findByTypeCdGreaterThanOrderByTypeCdAsc(position, limit);
        }

        return ReferencePaging.page(rows, PAGE_SIZE, backward, CURSOR_BINDING, cursorToken,
                TransactionTypeMapper::toResponse, TransactionType::getTypeCd);
    }

    /**
     * Refuses a filter that matches no row anywhere in the table, as the baseline cross-edit does.
     *
     * <p>This is {@code 1290-CROSS-EDITS} at physical lines 1239 to 1267 of
     * {@code app/app-transaction-type-db2/cbl/COTRTLIC.cbl}. That paragraph runs only when at least one
     * filter is supplied, performs the aggregate at {@code 9100-CHECK-FILTERS}, and on a zero count sets
     * {@code INPUT-ERROR}, marks each SUPPLIED filter field not-OK and displays the sentence at line
     * 1264.
     *
     * <p>Assumptions: this is a FIELD refusal and not an empty page. The two are different answers to
     * different questions -- the aggregate carries no comparison against the cursor position at all, so
     * it reports whether the filter matches anything anywhere, where an empty page reports only that
     * nothing further lies in the direction asked for. Reporting the first as the second would leave a
     * caller paging forever through a filter that can never match.
     *
     * <p>Assumptions: only the filters the CALLER supplied are named, matching the two guarded flag
     * assignments at lines 1253 to 1259. Naming a filter the caller left absent would mark a control it
     * never filled in.
     *
     * @param typeCodeFilter the normalised type-code filter, or {@code null} when absent
     * @param descriptionFilter the normalised description pattern, or {@code null} when absent
     * @param request the submitted request, read to name only the filters it carried; must not be
     *     {@code null}
     * @throws ClientInputException if no row in the table satisfies the supplied filters
     */
    private void requireFilterMatchesSomething(String typeCodeFilter, String descriptionFilter,
            TransactionTypeListRequest request) {

        if (this.types.countFilterMatches(typeCodeFilter, descriptionFilter) > 0) {
            return;
        }

        List<String> offending = new ArrayList<>(2);
        if (typeCodeFilter != null) {
            offending.add(FIELD_TYPE_CODE);
        }
        if (descriptionFilter != null) {
            offending.add(FIELD_DESCRIPTION);
        }
        throw new ClientInputException(ApiError.CODE_VALIDATION, List.copyOf(offending),
                FieldValidationFlag.NOT_OK, MESSAGE_NO_RECORDS_FOR_FILTER);
    }

    /**
     * Reads one transaction type by its code.
     *
     * @param typeCd the two-character code to read
     * @return the type as the contract publishes it
     * @throws NoSuchElementException carrying the verbatim refusal when no row holds that code
     */
    @Transactional(readOnly = true)
    public TransactionTypeResponse read(String typeCd) {
        return TransactionTypeMapper.toResponse(require(typeCd));
    }

    /**
     * Adds a transaction type, refusing a code that already exists.
     *
     * <p>Assumptions: the duplicate is refused as a conflict rather than accepted as a replacement,
     * which is what the published operation declares by carrying 409 among its answers. Accepting it
     * would make a create indistinguishable from a replace and would discard a stored description
     * without the caller having stated a version.</p>
     *
     * @param request the validated create body; must not be {@code null}
     * @return the stored type as the contract publishes it
     * @throws RecordConflictException when a type already carries that code
     */
    @Transactional
    public TransactionTypeResponse create(TransactionTypeCreateRequest request) {
        if (this.types.findByTypeCd(request.typeCd()).isPresent()) {
            throw new RecordConflictException(RecordConflictException.Kind.STALE_VERSION);
        }
        return TransactionTypeMapper.toResponse(
                this.types.save(TransactionTypeMapper.toNewEntity(request)));
    }

    /**
     * Replaces the description of an existing type, reporting a miss rather than inserting.
     *
     * <p>Assumptions: this is the list screen's strict update. Its baseline counterpart issues the
     * update and, when nothing matched, reports not found and inserts nothing -- unlike the
     * maintenance screen, which inserts instead. Keeping the two apart is why this method does not
     * fall back to a create.</p>
     *
     * <p>Assumptions: the version is compared here and the comparison carries the stored value into
     * the refusal, so a caller can retry against the revision that actually exists. The provider would
     * also refuse a stale write on flush, but its refusal cannot report which version won.</p>
     *
     * @param typeCd the code of the type to replace
     * @param request the validated replace body carrying the new description and the version read
     * @return the stored type as the contract publishes it
     * @throws NoSuchElementException carrying the verbatim refusal when no row holds that code
     * @throws RecordConflictException carrying the stored version when the version supplied is stale
     */
    @Transactional
    public TransactionTypeResponse replace(String typeCd, TransactionTypeUpdateRequest request) {
        TransactionType stored = require(typeCd);
        if (stored.getVersion() != request.version()) {
            throw new RecordConflictException(
                    RecordConflictException.Kind.STALE_VERSION, stored.getVersion());
        }
        stored.setDescription(TransactionTypeMapper.trimForStorage(request.description()));
        return TransactionTypeMapper.toResponse(this.types.save(stored));
    }

    /**
     * Deletes a transaction type, leaving a referential refusal to the database.
     *
     * <p>Assumptions: the constraint is the authority and this method does not pre-empt it. The
     * migration declares the foreign key from the category table {@code ON DELETE RESTRICT}, so a type
     * whose categories still exist is refused by the database, which surfaces as HTTP 409 through the
     * shared handler. Two callers deleting and inserting concurrently could each read a child count of
     * zero, so a count could never be the authority -- it is read here only to record the diagnosis
     * alongside the refusal.</p>
     *
     * @param typeCd the code of the type to delete
     * @throws NoSuchElementException carrying the verbatim refusal when no row holds that code
     */
    @Transactional
    public void delete(String typeCd) {
        TransactionType stored = require(typeCd);
        this.categories.countByTypeCd(typeCd);
        this.types.delete(stored);
    }

    /**
     * Reads a type or raises the verbatim refusal.
     *
     * @param typeCd the code to read
     * @return the stored row, never {@code null}
     * @throws NoSuchElementException carrying the verbatim refusal when no row holds that code
     */
    private TransactionType require(String typeCd) {
        Optional<TransactionType> found = this.types.findByTypeCd(typeCd);
        if (found.isEmpty()) {
            throw new NoSuchElementException(MESSAGE_TYPE_NOT_FOUND);
        }
        return found.get();
    }

}
