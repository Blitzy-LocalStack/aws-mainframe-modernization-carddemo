package com.carddemo.batch.service;

import com.carddemo.batch.dto.BusinessDate;
import com.carddemo.batch.dto.DatasetGeneration;
import com.carddemo.batch.dto.DatasetGeneration.DatasetFamily;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * Applies the generation-dataset discipline the ten baseline generation bases express.
 *
 * <p>Purpose: every one of the ten bases is defined with a five-generation scratch limit -- six in
 * {@code app/jcl/DEFGDGB.jcl} at lines 25 to 57, three in {@code app/jcl/DEFGDGD.jcl} at lines 28 to 76,
 * and one in {@code app/jcl/DALYREJS.jcl} at lines 24 to 26. This service answers the two questions a
 * step asks of that discipline: which prefix the next generation is written under, and which existing
 * generations the retention rule leaves behind.</p>
 *
 * <p>Assumptions: retention counts NONCURRENT generations, so the newest is always kept and the rule
 * bites on the sixth-oldest and older. That is what the scratch limit means -- the base holds five
 * generations and a new one displaces the oldest -- and it is also how the object store's own lifecycle
 * rule is configured, so a step and the bucket agree rather than each pruning on its own count.</p>
 *
 * <p>Assumptions: relative generation notation is resolved here and never written into a prefix. The
 * reference writes {@code (+1)} for a new generation and {@code (0)} for the current one, which are
 * positions rather than names; a prefix has to carry an absolute number so that two runs on the same
 * business date cannot address one another's output.</p>
 */
@Service
public class DatasetGenerationService {

    /**
     * Returns the generation a step should write, one past the highest that already exists.
     *
     * <p>Assumptions: an empty family yields the minimum generation number rather than one past it, so
     * the first run of a family writes the same number a freshly defined base would carry.</p>
     *
     * @param family the dataset family being written; must not be {@code null}
     * @param businessDate the injected business date the generation is partitioned under; must not be
     *     {@code null}
     * @param existing the generations already present for that family and date, in any order; must not be
     *     {@code null}
     * @return the generation to write, never {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    public DatasetGeneration nextGeneration(DatasetFamily family, BusinessDate businessDate,
            List<DatasetGeneration> existing) {

        Objects.requireNonNull(family, "family must not be null");
        Objects.requireNonNull(businessDate, "businessDate must not be null");
        Objects.requireNonNull(existing, "existing must not be null");

        int highest = existing.stream()
                .mapToInt(DatasetGeneration::generationNumber)
                .max()
                .orElse(DatasetGeneration.MINIMUM_GENERATION_NUMBER - 1);

        return new DatasetGeneration(family, businessDate, highest + 1);
    }

    /**
     * Returns the generations the retention rule removes, newest-first order preserved among the rest.
     *
     * <p>Assumptions: the returned list is what a caller deletes, rather than what it keeps. A method
     * returning the survivors would leave the caller to compute the complement, and a caller computing a
     * complement of a retention rule is where an off-by-one deletes a generation that should have
     * stood.</p>
     *
     * @param existing every generation currently present for one family and date, in any order; must not
     *     be {@code null}
     * @return the generations beyond the retained count, oldest first, never {@code null} and empty when
     *     the family holds no more than the retained count
     * @throws NullPointerException if {@code existing} is {@code null}
     */
    public List<DatasetGeneration> generationsToScratch(List<DatasetGeneration> existing) {
        Objects.requireNonNull(existing, "existing must not be null");

        List<DatasetGeneration> ordered = new ArrayList<>(existing);
        ordered.sort(Comparator.comparingInt(DatasetGeneration::generationNumber).reversed());

        // WHY : Assumptions: the newest generation is index zero after the reverse sort, and the retained
        //       window is the first RETAINED_GENERATION_COUNT entries counted from it. Everything after
        //       that window is scratched, which is the sixth-newest and older.
        if (ordered.size() <= DatasetGeneration.RETAINED_GENERATION_COUNT) {
            return List.of();
        }

        List<DatasetGeneration> scratched = new ArrayList<>(
                ordered.subList(DatasetGeneration.RETAINED_GENERATION_COUNT, ordered.size()));
        scratched.sort(Comparator.comparingInt(DatasetGeneration::generationNumber));
        return List.copyOf(scratched);
    }
}
