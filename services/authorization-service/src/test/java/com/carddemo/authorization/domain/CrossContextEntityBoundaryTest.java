package com.carddemo.authorization.domain;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Asserts that this context maps the two authorization segments and nothing belonging to another context.
 *
 * <p>Purpose: the summary screen this context serves composes data from more places than this context
 * owns, so the boundary has to be asserted as an INVENTORY rather than only as an import restriction. The
 * reference program {@code app/app-authorization-ims-db2-mq/cbl/COPAUS0C.cbl} performs three reads against
 * records this context does not own -- the card cross-reference through its account path at L818, the
 * account master at L869 and the customer master at L920 -- and each of those is an interface dependency on
 * {@code account-service} or {@code card-service} rather than a table to reproduce here. This class pins
 * that: it enumerates every mapped type the module compiles and compares the set against the two
 * authorization segments plus the two tables this context adds of its own.
 *
 * <p>Alternatives Considered: relying on the shared kernel's layering rule alone, which already forbids one
 * bounded context from importing another's {@code domain} package. Rejected because that rule constrains
 * REFERENCES and this class constrains DECLARATIONS, and the failure mode of concern here passes the
 * reference rule cleanly: an account or customer table mapped by a class declared inside
 * {@code com.carddemo.authorization.domain} imports nothing from another context, so nothing about it is a
 * cross-package reference. It would simply give this schema a second copy of a record another service owns,
 * with two writers, no shared constraint between them and no gate objecting.
 *
 * <p>Alternatives Considered: enumerating the types with the architecture library this module already has on
 * its test path, which is how the shared kernel expresses its layering rules. Measured and rejected here:
 * that library resolves each annotation it is asked about against the whole test class path, and this
 * module's path carries the application framework and the cloud SDK, so the same two assertions took over
 * thirty seconds. Loading the classes the module itself compiled and reading their annotations directly
 * needs no resolution step and answers the same question. The layering rules stay where they are, expressed
 * in that library, because they are about references between packages rather than about one module's own
 * declarations.
 *
 * <p>Assumptions: the check is by mapped TABLE NAME and not by class name, because the name that decides
 * whether two services own one table is the one in the annotation. A class named for an authorization
 * concept and mapped onto an account table would satisfy a name-based check and be exactly the duplication
 * this class exists to refuse.
 *
 * <p>Trade-offs: the expected set is written out here, so adding a legitimate table to this context fails
 * this test until the set is extended. That is accepted deliberately: the edit that extends it is where a
 * reviewer is asked whether the new table belongs to this context, which is the question the test exists to
 * force.
 */
class CrossContextEntityBoundaryTest {

    /**
     * The root package whose compiled types are examined.
     */
    private static final String CONTEXT_ROOT = "com.carddemo.authorization";

    /**
     * The only tables this context may map.
     *
     * <p>Assumptions: two of the four carry across hierarchical segments -- {@code pending_auth_summary}
     * from the root declared at {@code ims/DBPAUTP0.dbd} L28 and {@code pending_auth_detail} from the child
     * at L36 -- and two have no segment behind them: the fraud table the reference marking writes to its
     * relational store, and the outbox row that makes a reply durable with the decision that produced
     * it.</p>
     */
    private static final List<String> PERMITTED_TABLES =
            List.of("auth_fraud", "auth_reply_outbox", "pending_auth_detail", "pending_auth_summary");

    /**
     * Table names that would indicate a record owned by the account or card context had been mapped here.
     *
     * <p>Assumptions: these are the migrated names of the records the summary program and its siblings reach
     * across a context boundary to read -- the account master, the customer master, the card master and the
     * card cross-reference. They are listed explicitly so that a failure names the boundary that was crossed
     * rather than only reporting an unexpected table.</p>
     */
    private static final List<String> FOREIGN_TABLES =
            List.of("accounts", "customers", "cards", "card_xref");

    /**
     * The mapped table names, read once for the whole class.
     *
     * <p>Assumptions: the inventory is taken ONCE and reused, because it cannot change between two
     * assertions in one run. An earlier arrangement read it from inside an assertion loop, which repeated
     * the whole enumeration once per candidate for identical outcomes.</p>
     */
    private static final List<String> MAPPED_TABLES = readMappedTables();

    /**
     * Every mapped table in the module is one this context owns.
     */
    @Test
    @DisplayName("this context maps only the authorization tables it owns")
    void contextMapsOnlyItsOwnTables() {
        assertThat(MAPPED_TABLES)
                .containsExactlyInAnyOrderElementsOf(PERMITTED_TABLES)
                .doesNotContainAnyElementsOf(FOREIGN_TABLES);
    }

    /**
     * No mapped type stands for account, customer or card-cross-reference data.
     *
     * <p>Assumptions: this is asserted separately from the inventory above even though the inventory
     * subsumes it, because the two fail with different diagnostics. The inventory reports that the mapped
     * set differs from the permitted set; this one reports that a specific foreign record was reproduced,
     * which is the sentence a reader needs in order to know that the answer is an interface call to the
     * owning context rather than an addition to the permitted list.</p>
     */
    @Test
    @DisplayName("no entity here reproduces account, customer or card data")
    void noEntityReproducesAnotherContextsRecords() {
        for (String foreign : FOREIGN_TABLES) {
            assertThat(MAPPED_TABLES)
                    .as("%s belongs to another bounded context and is read through its interface",
                            foreign)
                    .doesNotContain(foreign);
        }
    }

    /**
     * Reads the mapped table name of every persistent type this module compiles.
     *
     * <p>Assumptions: the table name is taken from the {@link Table} annotation when one is present and
     * falls back to the type's simple name lowercased when it is not, which is the provider's own default
     * naming. Without the fallback an entity that omitted the annotation would contribute no name at all and
     * would pass both assertions by being invisible to them.</p>
     *
     * @return the mapped table names, in no particular order
     * @throws IllegalStateException if the enumeration finds no mapped type at all, which would leave both
     *     assertions vacuous rather than satisfied
     */
    private static List<String> readMappedTables() {
        List<String> tables = new ArrayList<>();
        for (Class<?> candidate : compiledTypes()) {
            if (!candidate.isAnnotationPresent(Entity.class)) {
                continue;
            }
            Table mapping = candidate.getAnnotation(Table.class);
            tables.add(mapping == null || mapping.name().isEmpty()
                    ? candidate.getSimpleName().toLowerCase(Locale.ROOT)
                    : mapping.name());
        }
        if (tables.isEmpty()) {
            throw new IllegalStateException(
                    "found no mapped type under " + CONTEXT_ROOT + ", so this inventory proves nothing");
        }
        return tables;
    }

    /**
     * Loads the types THIS MODULE compiles, and not the types it merely depends on.
     *
     * <p>Assumptions: the walk is anchored on the output directory this module's own classes were loaded
     * from, obtained from a known module type, so the question asked is what this module DECLARES rather than
     * what exists anywhere under a package name. Nested and synthetic types are skipped because a mapped
     * type is never one.</p>
     *
     * <p>Assumptions: each type is loaded WITHOUT initialisation. Reading an annotation does not need a
     * class initialised, and requesting initialisation would run the static setup of every type in the
     * module as a side effect of taking an inventory.</p>
     *
     * @return the module's own compiled types whose package lies under {@link #CONTEXT_ROOT}
     * @throws IllegalStateException if the module's compiled output location cannot be resolved
     * @throws UncheckedIOException if the compiled output directory cannot be walked
     */
    private static List<Class<?>> compiledTypes() {
        Path output = compiledOutput();
        List<Class<?>> types = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(output)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                String relative = output.relativize(file).toString();
                if (!relative.endsWith(".class") || relative.contains("$")) {
                    continue;
                }
                String name = relative.substring(0, relative.length() - ".class".length())
                        .replace(java.io.File.separatorChar, '.');
                if (!name.startsWith(CONTEXT_ROOT)) {
                    continue;
                }
                types.add(load(name));
            }
        } catch (IOException unreadable) {
            throw new UncheckedIOException(
                    "cannot walk the compiled output at " + output, unreadable);
        }
        return types;
    }

    /**
     * Resolves the directory this module's compiled classes were loaded from.
     *
     * @return the compiled output directory
     * @throws IllegalStateException if the location is absent or is not a usable directory path, either of
     *     which would silently empty the inventory
     */
    private static Path compiledOutput() {
        CodeSource source = PendingAuthSummary.class.getProtectionDomain().getCodeSource();
        if (source == null || source.getLocation() == null) {
            throw new IllegalStateException(
                    "cannot resolve the compiled output location of " + CONTEXT_ROOT);
        }
        try {
            Path location = Path.of(source.getLocation().toURI());
            if (!Files.isDirectory(location)) {
                throw new IllegalStateException(
                        "the compiled output of " + CONTEXT_ROOT + " is not a directory: " + location);
            }
            return location;
        } catch (URISyntaxException malformed) {
            throw new IllegalStateException(
                    "the compiled output location of " + CONTEXT_ROOT + " is not a usable path",
                    malformed);
        }
    }

    /**
     * Loads one compiled type by name without initialising it.
     *
     * @param name the binary name of the type to load
     * @return the loaded type
     * @throws IllegalStateException if a type the module compiled cannot be loaded, which would mean the
     *     inventory silently omitted it
     */
    private static Class<?> load(String name) {
        try {
            return Class.forName(name, false, CrossContextEntityBoundaryTest.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError unloadable) {
            throw new IllegalStateException("cannot load the compiled type " + name, unloadable);
        }
    }
}
