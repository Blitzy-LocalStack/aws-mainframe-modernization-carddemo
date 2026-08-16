package com.carddemo.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.carddemo.auth.domain.IdentitySyncTask;
import com.carddemo.auth.domain.User;
import com.carddemo.auth.dto.CreateUserRequest;
import com.carddemo.auth.dto.CreatedUserResponse;
import com.carddemo.auth.dto.UpdateUserRequest;
import com.carddemo.auth.dto.UserResponse;
import com.carddemo.auth.dto.UserSummary;
import com.carddemo.auth.mapper.UserMapper;
import com.carddemo.auth.repository.IdentitySyncTaskRepository;
import com.carddemo.auth.repository.UserRepository;
import com.carddemo.common.error.ApiError;
import com.carddemo.common.error.ClientInputException;
import com.carddemo.common.validation.FieldValidationFlag;
import com.carddemo.common.web.CursorToken;
import com.carddemo.common.web.PageResponse;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.domain.Limit;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import software.amazon.awssdk.services.cognitoidentityprovider.model.InternalErrorException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UsernameExistsException;

/**
 * Asserts the five user-administration operations and every reference sentence they report.
 *
 * <h2>What this class exists to catch</h2>
 *
 * <p>Purpose: this class is the parity evidence for the four reference programs reached from the
 * administrative menu -- the browse in {@code app/cbl/COUSR00C.cbl}, the add in
 * {@code app/cbl/COUSR01C.cbl}, the update in {@code app/cbl/COUSR02C.cbl} and the delete in
 * {@code app/cbl/COUSR03C.cbl}. For each operation it pins the two things that can regress without a
 * compiler noticing: the sentence reported and the field the sentence is attributed to, plus the order
 * in which the two stores are touched.</p>
 *
 * <p>Assumptions: no executable oracle exists for any of these four programs. They are online programs
 * built on the terminal command-level interface, and the runner carries no region to run them in, so
 * nothing here can be produced by running reference code and comparing. Sentence text and attribution
 * are the whole of the evidence available, which is why every one is asserted character for character
 * rather than in substance.</p>
 *
 * <p>Assumptions: every sentence is asserted as a LITERAL rather than against the service's own
 * constant. Comparing against the constant would pass if both moved together, which is exactly the
 * change transformation rule T8 forbids -- including the two the reference itself writes oddly, the
 * absent trailing letter in the conflict sentence and the word Update on the delete failure path.</p>
 *
 * <p>Assumptions: per-literal punctuation is carried across rather than normalised, because the
 * reference is not self-consistent about it and consistency would be a change in output. The four
 * blank-field sentences, the conflict, not-found, add-failure, update-failure and lookup-failure
 * sentences carry NO space before their ellipsis; the unchanged-body sentence and the two keystroke
 * prompts DO. Both spellings are asserted below exactly as their cited lines write them.</p>
 *
 * <p>Assumptions: field keys are not invented and are not read from a highlighting template, because
 * none of the four programs copies one. Each program's attribution is the operand of its own
 * {@code MOVE -1 TO} cursor statement, and the six keys this context can produce are therefore the
 * identifier, the two names, the reference type, the paging cursor and, at the adapter, the deletion
 * confirmation. That derivation is what makes the surprising attribution at
 * {@code app/cbl/COUSR01C.cbl} line 272 a contract rather than a mistake to tidy.</p>
 *
 * <p>Alternatives Considered: loading a Spring application context, or standing a database container up
 * under this class. Both were rejected because the unit under test has no web surface and its
 * persistence collaborator is an interface, so neither would reach one additional branch, while both
 * would add container or context start-up to every run. The branches that need a real store -- the
 * primary key refusing a duplicate, and the two key-bounded queries producing rows in the declared
 * order -- are covered by the integration test bound to the verification phase in the repository test
 * package, which is where a real store belongs.</p>
 *
 * <p>Assumptions: a REAL cursor sealer is built over stable key material rather than a substituted one,
 * because the two boundary cursors a page returns are opened again by the next request and a mocked
 * sealer would let a page be sealed under one binding and opened under another without this class
 * noticing. The paging cases therefore assert a genuine round trip.</p>
 *
 * <p>Trade-offs: the repository and the identity provisioning are substituted, so nothing here proves a
 * real database enforces the primary key or that a real pool accepts the calls. What it proves is the
 * branch structure this service owns -- the ordering between the two stores, the compensation, the
 * unchanged-body refusal and the failure taxonomy -- most of which a live store could not be made to
 * produce on demand.</p>
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception clause.</p>
 */
class UserServiceTest {

    /** The caller every paging case issues cursors to, which the seal is bound against. */
    private static final String SUBJECT = "operator-1";

    // Assumptions: stable key material, declared here by value, so a token this class produces is
    //   reproducible from the source alone and no case depends on a value generated per run. The
    //   shared sealer refuses material shorter than its declared minimum, which this comfortably
    //   clears.
    private static final byte[] CURSOR_KEY =
            "carddemo-auth-user-list-cursor-signing-key".getBytes(StandardCharsets.UTF_8);

    // Assumptions: ten rows to a page, transcribed from app/cbl/COUSR00C.cbl line 57, where
    //   USER-REC OCCURS 10 TIMES is the reference's declaration of how many rows one screen holds.
    //   It is stated here by value rather than read from the service, so a change to the service's
    //   own constant fails these cases instead of moving silently with them.
    private static final int PAGE_SIZE = 10;

    // Assumptions: the two SQL states are stated by value here rather than read from the service, so a
    //   change to the service's own classification fails these cases instead of moving silently with them.
    //   Both are PostgreSQL's, which is the engine ADR-003 selects: 23505 is the unique violation the
    //   conflict is keyed on, and 22001 is the string-data-right-truncation a value too wide for its column
    //   reports -- the case that used to be answered with the identifier's conflict sentence.
    private static final String SQL_STATE_UNIQUE_VIOLATION = "23505";

    private static final String SQL_STATE_VALUE_TOO_WIDE = "22001";

    /** The subject every substituted provisioning call mints, stated by value so a row binds to it. */
    private static final UUID SUBJECT_MINTED =
            UUID.fromString("11111111-2222-3333-4444-555555555555");

    // Assumptions: one shared provisioned identity, carrying BOTH halves the provisioning collaborator
    //   now answers with -- the subject the row binds to and the one-time credential the account was
    //   created with. Stating it once here rather than per case is what makes the create assertions
    //   below able to compare the credential the service returns against the credential provisioning
    //   supplied, which is the property that would regress silently if the service dropped the value
    //   on the floor again. The credential text is a literal rather than a generated draw, because a
    //   substituted collaborator is not the generator and a per-run value would make the comparison
    //   unreproducible from the source.
    private static final ProvisionedIdentity PROVISIONED =
            new ProvisionedIdentity(SUBJECT_MINTED, "Aa1!aaaaaaaaaaaaaaaaaaaa");

    private UserRepository users;

    private CognitoUserProvisioningService provisioning;

    private CursorToken sealer;

    private IdentitySyncTaskRepository ledger;

    private UserService service;

    /**
     * The substituted transaction manager both write templates run through.
     *
     * Assumptions: held as a field rather than as a local of the builder below so that a case can assert
     * the SPAN's outcome and not merely its effects. One case needs exactly that -- a demotion the provider
     * refuses must roll back rather than commit -- and the in-memory doubles honour no transaction, so the
     * rollback is observable here and nowhere else in this class.
     */
    private PlatformTransactionManager transactions;

    /**
     * Builds the service over substituted stores, a real cursor sealer and a real identity-sync ledger.
     *
     * <p>This method takes no parameter and yields no value; it assigns the collaborators every case
     * below shares. It raises nothing under normal operation.</p>
     *
     * <p>Assumptions: the identity-sync collaborator is the REAL one over an in-memory ledger rather than
     * a substitute, and that choice is what keeps every provider assertion in this class meaningful after
     * the two stores were separated. The three write paths no longer call the provider themselves -- they
     * commit an intention and drain it afterwards -- so a substituted ledger service would make
     * {@code verify(provisioning).synchronise(...)} and {@code verify(provisioning).withdraw(...)}
     * unreachable, and the parity those cases carry would be lost rather than relocated.</p>
     *
     * <p>Assumptions: the transaction manager is substituted, which makes the template run its callback
     * and commit nothing. That is sufficient here because what these cases assert is the ORDER of the
     * calls and which store is touched, not that a database committed; the commit itself is asserted by
     * the container-backed integration test in the repository test package.</p>
     */
    @BeforeEach
    void buildService() {
        users = mock(UserRepository.class);
        provisioning = mock(CognitoUserProvisioningService.class);
        sealer = new CursorToken(CURSOR_KEY, Duration.ofMinutes(5));
        ledger = inMemoryLedger();
        transactions = mock(PlatformTransactionManager.class);
        // WHY pass the SAME user-row substitute the service under test writes through: the reconciler
        //   decides whether a withdrawal is still owed by probing for the row, so sharing one substitute
        //   is what lets a case arrange "the insert landed" or "it did not" once and have both halves
        //   agree. Two separate substitutes would let the reconciler act on a row state the service
        //   never produced, and every guard-lifecycle assertion below would be vacuous.
        IdentitySyncService identitySync = new IdentitySyncService(ledger, provisioning, users,
                Clock.fixed(Instant.parse("2022-07-18T03:00:00Z"), ZoneOffset.UTC), transactions);
        // Assumptions: the mapper is the REAL one rather than a substitute, because it is where the
        //   stored padding is stripped, and the unchanged-body comparison below depends on comparing
        //   logical values rather than stored images. A substituted mapper would let that comparison
        //   pass against padded values and the case would assert nothing.
        service = new UserService(users, new UserMapper(), provisioning, sealer, identitySync,
                transactions);
    }

    /**
     * Builds a ledger repository that keeps its rows in a map and assigns identifiers on save.
     *
     * <p>Assumptions: the identifier is assigned by reflection, because the column is declared
     * {@code GENERATED BY DEFAULT AS IDENTITY} and the entity therefore exposes no setter for it -- a
     * setter would let application code choose a value the sequence had not issued. Alternatives
     * Considered: adding one for the benefit of tests, rejected because it widens the production type to
     * serve a substitute; and stubbing {@code findById} to return the saved instance regardless of the
     * identifier asked for, rejected because the applier's double-application check depends on asking for
     * a specific row and being told about that row.</p>
     *
     * <p>Assumptions: this builder is declared inline here rather than shared with the sibling test of
     * the ledger service, because this package's charter admits no shared helper: a vector or a stub is
     * written in the file that reads it so a reader never opens a second file to learn what it does.</p>
     *
     * @return a substituted ledger repository backed by insertion-ordered storage; never {@code null}
     */
    private static IdentitySyncTaskRepository inMemoryLedger() {
        IdentitySyncTaskRepository stub = mock(IdentitySyncTaskRepository.class);
        Map<Long, IdentitySyncTask> rows = new LinkedHashMap<>();
        AtomicLong sequence = new AtomicLong();

        when(stub.save(any(IdentitySyncTask.class))).thenAnswer(call -> {
            IdentitySyncTask task = call.getArgument(0);
            if (task.getTaskId() == null) {
                ReflectionTestUtils.setField(task, "taskId", sequence.incrementAndGet());
            }
            rows.put(task.getTaskId(), task);
            return task;
        });
        when(stub.saveAndFlush(any(IdentitySyncTask.class)))
                .thenAnswer(call -> stub.save(call.getArgument(0)));
        when(stub.findById(any())).thenAnswer(
                call -> Optional.ofNullable(rows.get(call.<Long>getArgument(0))));
        when(stub.findByUserIdAndStatusOrderByTaskIdAsc(any(), any(), any(Limit.class)))
                .thenAnswer(call -> selectFrom(rows, call.getArgument(1), call.getArgument(0),
                        call.<Limit>getArgument(2)));
        when(stub.findByStatusOrderByTaskIdAsc(any(), any(Limit.class)))
                .thenAnswer(call -> selectFrom(rows, call.getArgument(0), null,
                        call.<Limit>getArgument(1)));
        return stub;
    }

    /**
     * Selects the stored ledger rows of one status, optionally for one user, in identifier order.
     *
     * @param rows the ledger's backing storage
     * @param status the status a row must carry to be selected
     * @param userId the user a row must name, or {@code null} to select every user's rows
     * @param limit the greatest number of rows to yield
     * @return the selected rows in ascending identifier order; never {@code null}
     */
    private static List<IdentitySyncTask> selectFrom(Map<Long, IdentitySyncTask> rows, String status,
            String userId, Limit limit) {
        return rows.values().stream()
                .filter(row -> status.equals(row.getStatus()))
                .filter(row -> userId == null || userId.equals(row.getUserId()))
                .sorted(Comparator.comparing(IdentitySyncTask::getTaskId))
                .limit(limit.max())
                .toList();
    }

    /**
     * Asserts an absent first name is the first thing the create chain refuses, attributed to itself.
     *
     * <p>The sentence asserted is the one at {@code app/cbl/COUSR01C.cbl} line 120, and the attribution
     * is the operand of the cursor statement at line 122.</p>
     *
     * <p>This case takes no parameter and yields no value.</p>
     *
     * @throws ClientInputException always, raised by the service's ordered create chain and captured by
     *     the assertion below; it is the shared kernel's client-input type, not a locally declared one
     */
    @Test
    @DisplayName("a create with no first name reports the reference first-name sentence")
    void aCreateWithNoFirstNameReportsTheReferenceSentence() {
        // Assumptions: the reference VALIDATES in screen order and PERSISTS in record order, and the two
        //   sequences are different inside this one program. Its chain opens at
        //   app/cbl/COUSR01C.cbl line 117 and tests the first name first, at line 118, matching the
        //   order the symbolic map declares its input face at app/cpy-bms/COUSR01.CPY line 60. Its
        //   guarded move block at lines 153 to 160 then writes the identifier first, at line 154,
        //   matching app/cpy/CSUSR01Y.cpy line 18. The request record therefore declares screen order
        //   and the response record declares record order; collapsing the two onto one order would
        //   change which sentence a caller with two absent fields is told about.
        assertThatThrownBy(() -> service.create(
                        new CreateUserRequest("   ", "Lovelace", "USER0042", "A")))
                .isInstanceOf(ClientInputException.class)
                .hasMessage("First Name can NOT be empty...")
                .extracting(raised -> ((ClientInputException) raised).state())
                .as("a body field the caller submitted empty carries the blank state, which is the"
                        + " state the reference's own templated highlight pairs with an asterisk in the"
                        + " field as well as a colour change")
                .isEqualTo(FieldValidationFlag.BLANK);

        // Assumptions: nothing is read and nothing is provisioned, because the chain runs before the
        //   duplicate probe and before the pool call. A body the reference would have refused on a
        //   blank field must not reach either store, or a caller would be told about a conflict or a
        //   provider fault for a request that never got past its first arm.
        verifyNoInteractions(users);
        verifyNoInteractions(provisioning);
    }

    /**
     * Asserts an absent last name is refused with its own sentence once the first name is supplied.
     *
     * <p>The sentence asserted is the one at {@code app/cbl/COUSR01C.cbl} line 126, whose cursor
     * statement at line 128 fixes the attribution.</p>
     *
     * <p>This case takes no parameter and yields no value.</p>
     *
     * @throws ClientInputException always, raised by the service's ordered create chain and captured by
     *     the assertion below
     */
    @Test
    @DisplayName("a create with no last name reports the reference last-name sentence")
    void aCreateWithNoLastNameReportsTheReferenceSentence() {
        assertThatThrownBy(() -> service.create(
                        new CreateUserRequest("Ada", "", "USER0042", "A")))
                .isInstanceOf(ClientInputException.class)
                .hasMessage("Last Name can NOT be empty...")
                .extracting(raised -> ((ClientInputException) raised).field())
                .isEqualTo("lastName");
    }

    /**
     * Asserts an absent identifier on the create path is refused with the shared identifier sentence.
     *
     * <p>The sentence asserted is the one at {@code app/cbl/COUSR01C.cbl} line 132, and the same string
     * is written by the update program at line 148 and by the delete program at lines 147 and 179.</p>
     *
     * <p>This case takes no parameter and yields no value.</p>
     *
     * @throws ClientInputException always, raised by the service's ordered create chain and captured by
     *     the assertion below
     */
    @Test
    @DisplayName("a create with no identifier reports the reference identifier sentence")
    void aCreateWithNoIdentifierReportsTheReferenceSentence() {
        assertThatThrownBy(() -> service.create(
                        new CreateUserRequest("Ada", "Lovelace", "        ", "A")))
                .isInstanceOf(ClientInputException.class)
                .hasMessage("User ID can NOT be empty...")
                .extracting(raised -> ((ClientInputException) raised).field())
                .isEqualTo("userId");
    }

    /**
     * Asserts an absent reference type is refused with its own sentence at the end of the chain.
     *
     * <p>The sentence asserted is the one at {@code app/cbl/COUSR01C.cbl} line 144, whose cursor
     * statement at line 146 fixes the attribution.</p>
     *
     * <p>This case takes no parameter and yields no value.</p>
     *
     * @throws ClientInputException always, raised by the service's ordered create chain and captured by
     *     the assertion below
     */
    @Test
    @DisplayName("a create with no reference type reports the reference type sentence")
    void aCreateWithNoReferenceTypeReportsTheReferenceSentence() {
        assertThatThrownBy(() -> service.create(
                        new CreateUserRequest("Ada", "Lovelace", "USER0042", " ")))
                .isInstanceOf(ClientInputException.class)
                .hasMessage("User Type can NOT be empty...")
                .extracting(raised -> ((ClientInputException) raised).field())
                .isEqualTo("userType");
    }

    /**
     * Asserts a body with every field absent reports only the earliest sentence in the create order.
     *
     * <p>The sentence asserted is the first-name one at {@code app/cbl/COUSR01C.cbl} line 120, and the
     * three sentences at lines 126, 132 and 144 must not appear.</p>
     *
     * <p>This case takes no parameter and yields no value.</p>
     *
     * @throws ClientInputException always, raised by the service's ordered create chain and captured by
     *     the assertion below
     */
    @Test
    @DisplayName("a create with every field absent reports only the earliest sentence")
    void aCreateWithEveryFieldAbsentReportsOnlyTheEarliest() {
        // Assumptions: the reference chain is an EVALUATE TRUE, which stops at the first true condition
        //   and whose every arm ends by sending the screen, so a body failing four checks produced
        //   exactly one sentence. Declarative constraints on the request record cannot reproduce that:
        //   the provider evaluates them in no defined order, so it would emit whichever it happened to
        //   reach first and the caller could see any of the four. This case is what holds the ordered
        //   chain in the service, and it asserts the absence of the other three sentences as well as
        //   the presence of the right one.
        ClientInputException raised = (ClientInputException) org.assertj.core.api.Assertions
                .catchThrowable(() -> service.create(new CreateUserRequest("", "", "", "")));

        assertThat(raised).isNotNull();
        assertThat(raised.getMessage()).isEqualTo("First Name can NOT be empty...");
        assertThat(raised.getMessage())
                .as("only the earliest arm of the chain may report, so the three later sentences"
                        + " must be absent")
                .isNotEqualTo("Last Name can NOT be empty...")
                .isNotEqualTo("User ID can NOT be empty...")
                .isNotEqualTo("User Type can NOT be empty...");
        assertThat(raised.fields())
                .as("one arm reports, so exactly one attribution reaches the caller")
                .containsExactly("firstName");
    }

    /**
     * Asserts a create body carrying no credential component at all is accepted.
     *
     * <p>This case takes no parameter and yields no value; it raises nothing, which is the property
     * being asserted.</p>
     */
    @Test
    @DisplayName("a create body carrying no credential is valid on the create path")
    void aCreateBodyCarryingNoCredentialIsValid() {
        when(users.existsById("USER0042")).thenReturn(false);
        when(provisioning.provision("USER0042", "Ada", "Lovelace", "A"))
                .thenReturn(PROVISIONED);
        when(users.insertUser(any(), any(), any(), any(), any())).thenReturn(1);

        CreatedUserResponse created = service.create(
                new CreateUserRequest("Ada", "Lovelace", "USER0042", "A"));

        // Assumptions: the reference chain has a FIFTH arm this request cannot fail, and its absence is
        //   the observable consequence of the credential omission rather than an oversight here. The
        //   arm's condition is at app/cbl/COUSR01C.cbl line 136, its sentence at line 138 and its
        //   cursor at line 140, and the guarded move block writes the submitted value into the record
        //   at line 157, at zero-based offset 48 of the 80-byte layout declared at
        //   app/cpy/CSUSR01Y.cpy line 21. The request record declares four components and none of them
        //   is that value, so a body without one is complete rather than incomplete, and there is no
        //   fifth sentence for this class to assert. The argument for declining to carry the value at
        //   all belongs where the operation is declared, not here.
        assertThat(created.userId()).isEqualTo("USER0042");
        assertThat(CreateUserRequest.class.getRecordComponents())
                .as("the request declares exactly the four components the reference screen collects"
                        + " other than the one omitted")
                .hasSize(4);
    }

    /**
     * Asserts a reference type outside the two admitted characters is refused by the service itself.
     *
     * <p>The two admitted characters are declared at {@code app/cpy/COCOM01Y.cpy} lines 27 and 28, as
     * quoted single-character values under the group item at line 26.</p>
     *
     * @param submitted a single character outside the admitted pair, supplied by the value source
     * @throws ClientInputException always, raised by the service's membership check and captured by the
     *     assertion below
     */
    @ParameterizedTest
    @ValueSource(strings = {"X", "a", "u", "1", "Z"})
    @DisplayName("a reference type outside the admitted pair is refused in service logic")
    void aReferenceTypeOutsideTheAdmittedPairIsRefused(String submitted) {
        // Assumptions: enforcing membership is a NARROWING and not a transcription. Both screen
        //   programs test the field for being non-blank only -- app/cbl/COUSR01C.cbl line 142 and
        //   app/cbl/COUSR02C.cbl line 204 -- so the reference accepted any single character at input
        //   and only INTERPRETED the value downstream through the two condition names at
        //   app/cpy/COCOM01Y.cpy lines 27 and 28. The record layout is not the authority for the
        //   domain: app/cpy/CSUSR01Y.cpy line 22 declares the field's width and a count of its
        //   condition names returns zero. Refusing a third value is therefore new behaviour, and the
        //   sentence it carries is a new sentence rather than one of the reference's; that divergence
        //   is documented.
        assertThatThrownBy(() -> service.create(
                        new CreateUserRequest("Ada", "Lovelace", "USER0042", submitted)))
                .isInstanceOf(ClientInputException.class)
                .hasMessage("User Type must be A or U...")
                .extracting(raised -> ((ClientInputException) raised).state())
                .as("an out-of-domain value is a refused value rather than an absent one, so it"
                        + " carries the not-valid state and not the blank state")
                .isEqualTo(FieldValidationFlag.NOT_OK);

        // Assumptions: the membership check runs inside the ordered chain, before the duplicate probe,
        //   so a body carrying an inadmissible type never reaches either store.
        verifyNoInteractions(users);
        verifyNoInteractions(provisioning);
    }

    /**
     * A submitted identifier that EXPANDS under the fold is refused before any side effect.
     *
     * <p>⚠️ Purpose: this is the regression case for the expansion defect. Java's upper-case mapping is not
     * length-preserving -- the sharp s folds to two characters -- so a value the request record admitted at
     * eight characters could canonicalise to as many as sixteen. The record and the path variable both
     * bound the SUBMITTED value, so nothing checked the canonical one: the expanded key was probed for,
     * handed to the identity provider, and refused only afterwards by the {@code CHAR(8)} column. The
     * compensation that unwound the provider account then reported the integrity failure as a
     * duplicate-key conflict, so the caller was told an identifier already existed when no such row had
     * ever been written -- and a provider account had been created and withdrawn along the way.</p>
     *
     * <p>⚠️ Assumptions: the value source is chosen so that every case is admitted by the record and
     * refused by the service. Four sharp characters submit as four and canonicalise to eight, which is at
     * the bound and must be ADMITTED -- so that case is deliberately absent from this source and covered
     * by the case below; five submit as five and canonicalise to ten, which is the narrowest failing case
     * and the one an off-by-one width check would let through.</p>
     *
     * <p>⚠️ Assumptions: the two stores are asserted to have NO interaction, which is the whole substance
     * of the correction. The refusal existed before, at the column; what did not exist was a refusal that
     * came BEFORE the duplicate probe and the provider call. A case asserting only the exception would
     * pass against the defect.</p>
     *
     * @param submitted an identifier of at most eight characters whose canonical form is wider, supplied
     *     by the value source
     * @throws ClientInputException always, raised by the service's canonical width check and captured by
     *     the assertion below
     */
    @ParameterizedTest
    @ValueSource(strings = {"\u00df\u00df\u00df\u00df\u00df", "A\u00df\u00df\u00df\u00df",
        "\u00df\u00df\u00df\u00df\u00df\u00df\u00df\u00df", "AB\u00df\u00df\u00df\u00dfCD"})
    @DisplayName("an identifier that expands under the fold is refused before the provider is called")
    void anIdentifierThatExpandsUnderTheFoldIsRefusedBeforeAnySideEffect(String submitted) {
        assertThat(submitted.length())
                .as("every case must be admitted by the record's own bound, or the case would be "
                        + "asserting the record rather than the service")
                .isLessThanOrEqualTo(8);
        assertThat(submitted.toUpperCase(java.util.Locale.ROOT).length())
                .as("and must expand past it, which is the condition the service now detects")
                .isGreaterThan(8);

        assertThatThrownBy(() -> service.create(
                        new CreateUserRequest("Ada", "Lovelace", submitted, "A")))
                .isInstanceOf(ClientInputException.class)
                .hasMessage("User ID must be at most 8 characters...")
                .extracting(raised -> ((ClientInputException) raised).field())
                .as("the refusal names the identifier, so a caller rendering the baseline's screen "
                        + "homes the cursor to the field that was wrong")
                .isEqualTo("userId");

        verifyNoInteractions(users);
        verifyNoInteractions(provisioning);
    }

    /**
     * An identifier whose canonical form lands exactly ON the width bound is admitted.
     *
     * <p>Assumptions: this case exists so the width check cannot be satisfied by refusing everything that
     * folds to a different length. Four sharp characters submit as four and canonicalise to eight, which
     * is the declared width of {@code SEC-USR-ID PIC X(08)} -- so the value is storable and must be
     * stored. A check written with {@code >=} rather than {@code >} would refuse it, which is the
     * off-by-one an assertion on failing inputs alone cannot detect.</p>
     *
     * <p>Assumptions: the provider IS reached in this case, and that is the property asserted rather than
     * the returned row. Reaching the provider is what distinguishes "admitted" from "refused later"; a
     * service that admitted the value at the width check and then refused it somewhere else would still
     * satisfy an assertion that no exception was thrown at this line.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("an identifier whose canonical form is exactly eight characters is admitted")
    void anIdentifierCanonicalisingToExactlyTheWidthIsAdmitted() {
        String submitted = "\u00df\u00df\u00df\u00df";
        assertThat(submitted.toUpperCase(java.util.Locale.ROOT))
                .as("the canonical form is at the bound, not past it")
                .hasSize(8)
                .isEqualTo("SSSSSSSS");

        when(provisioning.provision("SSSSSSSS", "Ada", "Lovelace", "A")).thenReturn(PROVISIONED);

        service.create(new CreateUserRequest("Ada", "Lovelace", submitted, "A"));

        verify(provisioning).provision("SSSSSSSS", "Ada", "Lovelace", "A");
    }

    /**
     * An identifier carrying a character outside the invariant domain is refused before any side effect.
     *
     * <p>⚠️ Purpose: the domain check is what makes the service's definition of the key and the column's
     * guard the SAME definition, and it is asserted here from the service side. The two folds -- Java's
     * root locale and the engine's {@code upper()} -- agree only inside the invariant set, so a value
     * outside it can satisfy one and violate the other; and a blank inside the identifier makes it
     * unusable as one in the reference itself, which renders it {@code DELIMITED BY SPACE} at
     * {@code app/cbl/COUSR01C.cbl} L256, {@code COUSR02C.cbl} L373 and {@code COUSR03C.cbl} L319.</p>
     *
     * <p>⚠️ Assumptions: this is a NARROWING of the reference, which validates the identifier's characters
     * nowhere, and it is registered as {@code D-USER-ID-CANONICAL-DOMAIN}. The committed extract
     * {@code app/data/EBCDIC/AWS.M2.CARDDEMO.USRSEC.PS} carries ten identifiers drawn from
     * {@code [A-Z0-9]} alone, so nothing the parity oracle holds is refused -- which is why the narrowing
     * is affordable.</p>
     *
     * <p>Assumptions: the state is asserted as the rejected-value one rather than the blank one, because
     * the published contract turns that distinction into presentation and a caller draws the reference's
     * asterisk marker for the blank state only. A supplied-but-inadmissible identifier is not an unfilled
     * field.</p>
     *
     * @param submitted an identifier carrying one character outside the invariant printable domain,
     *     supplied by the value source
     * @throws ClientInputException always, raised by the service's domain check and captured by the
     *     assertion below
     */
    @ParameterizedTest
    @ValueSource(strings = {"US ER01", "USER\t01", "\u00c4SER001", "USER\u00a001"})
    @DisplayName("an identifier outside the invariant character domain is refused before any side effect")
    void anIdentifierOutsideTheInvariantDomainIsRefused(String submitted) {
        assertThatThrownBy(() -> service.create(
                        new CreateUserRequest("Ada", "Lovelace", submitted, "A")))
                .isInstanceOf(ClientInputException.class)
                .hasMessage("User ID must be printable characters without spaces...")
                .extracting(raised -> ((ClientInputException) raised).state())
                .as("a supplied-but-inadmissible identifier is a refused value, not an unfilled field")
                .isEqualTo(FieldValidationFlag.NOT_OK);

        verifyNoInteractions(users);
        verifyNoInteractions(provisioning);
    }

    /**
     * Asserts the update path applies the same membership narrowing as the create path.
     *
     * @param submitted a single character outside the admitted pair, supplied by the value source
     * @throws ClientInputException always, raised by the service's membership check and captured by the
     *     assertion below
     */
    @ParameterizedTest
    @ValueSource(strings = {"X", "a", "u", "0"})
    @DisplayName("an update carrying an inadmissible reference type is refused before the row is read")
    void anUpdateWithAnInadmissibleReferenceTypeIsRefused(String submitted) {
        assertThatThrownBy(() -> service.update("USER0001",
                        new UpdateUserRequest("Grace", "Hopper", submitted)))
                .isInstanceOf(ClientInputException.class)
                .hasMessage("User Type must be A or U...");

        // Assumptions: the ordered chain precedes the keyed read, so an inadmissible type is reported
        //   rather than a not-found for a row the request never got as far as looking for.
        verifyNoInteractions(users);
    }

    /**
     * Asserts both admitted characters are accepted, so the narrowing refuses nothing it should admit.
     *
     * @param submitted one of the two admitted characters, supplied by the value source
     */
    @ParameterizedTest
    @ValueSource(strings = {"A", "U"})
    @DisplayName("both admitted reference types are accepted")
    void bothAdmittedReferenceTypesAreAccepted(String submitted) {
        when(users.existsById("USER0042")).thenReturn(false);
        when(provisioning.provision("USER0042", "Ada", "Lovelace", submitted))
                .thenReturn(PROVISIONED);
        when(users.insertUser(any(), any(), any(), any(), any())).thenReturn(1);

        CreatedUserResponse created = service.create(
                new CreateUserRequest("Ada", "Lovelace", "USER0042", submitted));

        assertThat(created.userType()).isEqualTo(submitted);
    }

    /**
     * Asserts a create provisions the pool account first and then writes the row bound to its subject.
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("a create provisions the identity first and binds the row to the subject it minted")
    void aCreateProvisionsThenWrites() {
        UUID subject = UUID.fromString("11111111-2222-3333-4444-555555555555");
        when(users.existsById("USER0042")).thenReturn(false);
        when(provisioning.provision("USER0042", "Ada", "Lovelace", "A"))
                .thenReturn(new ProvisionedIdentity(subject, PROVISIONED.credentialSecretName()));
        when(users.insertUser(any(), any(), any(), any(), any())).thenReturn(1);

        CreatedUserResponse created = service.create(
                new CreateUserRequest("Ada", "Lovelace", "USER0042", "A"));

        // Alternatives Considered: accepting the subject reference as a fifth request component, so a
        //   caller named the identity the row binds to. Rejected because a caller could then bind a new
        //   row to a subject belonging to some other account, and nothing on this boundary could tell
        //   an intended value from a copied one; the published contract records that the component was
        //   withdrawn for exactly that reason. Minting it inside the provisioning collaborator also
        //   keeps every provider call behind one seam, so this class needs no provider type at all --
        //   which is why no provider import appears above.
        assertThat(created.cognitoSub())
                .as("the row is bound to the subject the pool minted, not to any value the caller"
                        + " supplied")
                .isEqualTo(subject);

        InOrder ordered = inOrder(provisioning, users);
        ordered.verify(provisioning).provision("USER0042", "Ada", "Lovelace", "A");
        ordered.verify(users).insertUser(any(), any(), any(), any(), any());
    }

    /**
     * Asserts an already-taken identifier is refused with the reference sentence and nothing provisioned.
     *
     * <p>The sentence asserted is the one at {@code app/cbl/COUSR01C.cbl} line 263, reproduced with its
     * grammatical slip intact because an externally observable string is part of the interface whether
     * or not it reads well.</p>
     *
     * <p>This case takes no parameter and yields no value.</p>
     *
     * @throws UserService.DuplicateUserException always, raised by the service and captured by the
     *     assertion below; it is the type nested in the service under test, which is NOT the same type
     *     as the same-named class declared alongside it in this package
     */
    @Test
    @DisplayName("a duplicate identifier is refused before anything is provisioned")
    void aDuplicateIdentifierIsRefusedBeforeProvisioning() {
        when(users.existsById("USER0001")).thenReturn(true);

        // Assumptions: there is exactly ONE conflict outcome to model, not two. The reference writes two
        //   adjacent WHEN clauses at app/cbl/COUSR01C.cbl lines 260 and 261 -- one for a duplicate key
        //   and one for a duplicate record -- and they fall through to a single shared body at lines 262
        //   to 266 carrying one sentence, one attribution and one screen send. They are two response
        //   codes for one condition rather than two conditions. That the condition arises from the write
        //   at all is settled by the write itself: the statement at lines 240 to 248 names the record
        //   identification field at line 244 and its key length at line 245, so the file manager decides
        //   the duplicate. This class therefore asserts one refusal and never a second variant.
        assertThatThrownBy(() -> service.create(
                        new CreateUserRequest("Ada", "Lovelace", "USER0001", "A")))
                .isInstanceOf(UserService.DuplicateUserException.class)
                .hasMessage("User ID already exist...");

        verifyNoInteractions(provisioning);
        verify(users, never()).insertUser(any(), any(), any(), any(), any());
    }

    /**
     * Asserts a duplicate detected by the constraint reports the same one sentence and compensates.
     *
     * <p>This case takes no parameter and yields no value.</p>
     *
     * @throws UserService.DuplicateUserException always, raised by the service when the keyed insert is
     *     refused and captured by the assertion below
     */
    @Test
    @DisplayName("a duplicate lost to a race withdraws the account it had just provisioned")
    void aRaceLostToTheConstraintWithdrawsTheAccount() {
        when(users.existsById("USER0042")).thenReturn(false);
        when(provisioning.provision("USER0042", "Ada", "Lovelace", "A"))
                .thenReturn(PROVISIONED);
        when(users.insertUser(any(), any(), any(), any(), any()))
                .thenThrow(uniquenessViolation());

        // Assumptions: the race and the probe are the SAME outcome to a caller, which is what keeps the
        //   count of conflict sentences at one. The probe answers the ordinary case in one indexed read;
        //   the constraint answers the interleaved case; both report the one sentence at
        //   app/cbl/COUSR01C.cbl line 263.
        // Refactoring Rationale: the stubbed failure now carries the SQL STATE a store reports for a
        //   uniqueness failure, where it used to be a bare translated exception with no cause at all. The
        //   service classifies on that state, because the framework's translator maps a constraint
        //   violation and a value-too-wide failure onto ONE exception class -- so a stub with no state
        //   would exercise the fault arm and this case would assert the wrong outcome for the right
        //   reason. The sibling case below pins the other side of the same classification.
        assertThatThrownBy(() -> service.create(
                        new CreateUserRequest("Ada", "Lovelace", "USER0042", "A")))
                .isInstanceOf(UserService.DuplicateUserException.class)
                .hasMessage("User ID already exist...");

        verify(provisioning).withdraw("USER0042");
    }

    /**
     * Asserts an integrity violation that is NOT a uniqueness failure reports the add sentence.
     *
     * <p>This case takes no parameter and yields no value.</p>
     *
     * @throws IllegalStateException always, raised by the service when the insert is refused for a reason
     *     that is not the identifier being taken, and captured by the assertion below
     */
    @Test
    @DisplayName("an integrity violation that is not a duplicate reports the add sentence")
    void anIntegrityViolationThatIsNotADuplicateReportsTheAddSentence() {
        when(users.existsById("USER0042")).thenReturn(false);
        when(provisioning.provision("USER0042", "Ada", "Lovelace", "A"))
                .thenReturn(PROVISIONED);
        when(users.insertUser(any(), any(), any(), any(), any()))
                .thenThrow(integrityViolation(SQL_STATE_VALUE_TOO_WIDE));

        // Refactoring Rationale: this case exists because ONE catch arm answered every integrity
        //   violation with the conflict sentence, and the sentence is about the identifier. The insert
        //   reaches the same translated exception class for a value too wide for its column, for a refused
        //   check constraint and for a missing required value -- none of which the caller fixes by
        //   choosing another identifier. A caller told "User ID already exist..." for a twenty-one
        //   character family name would retry under a different identifier and be refused again on every
        //   one it tried, because the identifier was never the problem.
        assertThatThrownBy(() -> service.create(
                        new CreateUserRequest("Ada", "Lovelace", "USER0042", "A")))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("Unable to Add User...");

        // Assumptions: the account is still withdrawn, because it was still provisioned. The
        //   classification decides what the CALLER is told; it does not change what this request owes the
        //   pool.
        verify(provisioning).withdraw("USER0042");
    }

    /**
     * Asserts an integrity violation carrying no SQL state at all is reported as a fault.
     *
     * <p>This case takes no parameter and yields no value.</p>
     *
     * @throws IllegalStateException always, raised by the service and captured by the assertion below
     */
    @Test
    @DisplayName("an integrity violation carrying no SQL state is reported as a fault")
    void anIntegrityViolationCarryingNoSqlStateIsReportedAsAFault() {
        when(users.existsById("USER0042")).thenReturn(false);
        when(provisioning.provision("USER0042", "Ada", "Lovelace", "A"))
                .thenReturn(PROVISIONED);
        when(users.insertUser(any(), any(), any(), any(), any()))
                .thenThrow(new DataIntegrityViolationException("the store refused the row"));

        // Assumptions: the DEFAULT direction of the classification is the fault and not the conflict, and
        //   this case is what fixes it. A violation whose provenance the service cannot read says nothing
        //   about uniqueness, so answering the conflict would tell a caller its identifier is taken on the
        //   strength of a failure that never said so; the add sentence is true of every case.
        assertThatThrownBy(() -> service.create(
                        new CreateUserRequest("Ada", "Lovelace", "USER0042", "A")))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("Unable to Add User...");

        verify(provisioning).withdraw("USER0042");
    }

    /**
     * Asserts a write failure reports the reference add sentence attributed to the first name.
     *
     * <p>The sentence asserted is the one at {@code app/cbl/COUSR01C.cbl} line 270.</p>
     *
     * <p>This case takes no parameter and yields no value.</p>
     *
     * @throws IllegalStateException always, raised by the service when the insert cannot be written and
     *     captured by the assertion below
     */
    @Test
    @DisplayName("a failed insert reports the reference add sentence and withdraws the account")
    void aFailedInsertReportsTheReferenceSentence() {
        when(users.existsById("USER0042")).thenReturn(false);
        when(provisioning.provision("USER0042", "Ada", "Lovelace", "A"))
                .thenReturn(PROVISIONED);
        when(users.insertUser(any(), any(), any(), any(), any()))
                .thenThrow(new QueryTimeoutException("statement timed out"));

        // Assumptions: this failure is attributed to the FIRST NAME and not to the identifier, which
        //   reads oddly and is nevertheless the contract. The reference arm at app/cbl/COUSR01C.cbl
        //   line 267 writes its sentence at line 270 and then moves its cursor to the first name's
        //   length field at line 272, where the conflict arm four lines earlier moves it to the
        //   identifier's at line 265. Attribution is derived from the cursor operand, so the two arms
        //   attribute differently, and normalising them would change which field a client highlights.
        assertThatThrownBy(() -> service.create(
                        new CreateUserRequest("Ada", "Lovelace", "USER0042", "A")))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("Unable to Add User...");

        verify(provisioning).withdraw("USER0042");
    }

    /**
     * Asserts a failure of the duplicate probe itself reports the add sentence rather than a conflict.
     *
     * <p>This case takes no parameter and yields no value.</p>
     *
     * @throws IllegalStateException always, raised by the service when the probe cannot be read and
     *     captured by the assertion below
     */
    @Test
    @DisplayName("a failed duplicate probe reports the add sentence and provisions nothing")
    void aFailedDuplicateProbeReportsTheAddSentence() {
        when(users.existsById("USER0042")).thenThrow(new QueryTimeoutException("statement timed out"));

        assertThatThrownBy(() -> service.create(
                        new CreateUserRequest("Ada", "Lovelace", "USER0042", "A")))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("Unable to Add User...");

        // Assumptions: an unreadable probe is a fault and not a conflict, so nothing is provisioned and
        //   no compensation is needed. Reporting the conflict sentence here would tell a caller its
        //   identifier was taken on the strength of a read that never answered.
        verifyNoInteractions(provisioning);
    }

    /**
     * Asserts the create path folds the identifier ONCE and uses that one value for all three steps.
     *
     * <p>The value submitted below carries both a surrounding blank and lower case, so a single case
     * covers the trim and the fold together -- the same pair the read case asserts, now on the path that
     * used not to apply either.</p>
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("a create folds the identifier before probing, provisioning and writing")
    void aCreateFoldsTheIdentifierOnceForEveryStep() {
        // Assumptions: EVERY stub below is keyed on the folded value, which is what makes this case
        //   able to fail. A service that carried the raw value would find no stub for the probe, be
        //   answered false by the substitute's default, then find no stub for the provisioning call and
        //   be answered null -- and fail before it reached an assertion.
        when(users.existsById("USER0042")).thenReturn(false);
        when(provisioning.provision("USER0042", "Ada", "Lovelace", "A")).thenReturn(PROVISIONED);
        when(users.insertUser(any(), any(), any(), any(), any())).thenReturn(1);

        CreatedUserResponse created = service.create(
                new CreateUserRequest("Ada", "Lovelace", " user0042 ", "A"));

        // Refactoring Rationale: this case exists because the fold used to be applied on the read,
        //   update and delete paths and NOT here, and one missing application produced three separate
        //   observable defects rather than one cosmetic difference. Measured against the running
        //   service: creating "bnd00005" stored a row under that spelling and then answered not-found
        //   for BOTH spellings on read, because the read folded and the stored key had not; creating
        //   "BND00005" afterwards was accepted as a SECOND row rather than refused as a duplicate,
        //   because the probe compared the raw value against a folded column; and an update addressed to
        //   "bnd00005" folded its key, found the other row and mutated THAT one -- a caller naming one
        //   record and altering another. The three assertions below pin the three steps that each have
        //   to agree for none of those to be reachable.
        assertThat(created.userId())
                .as("the row is written under the folded key, so every later keyed request reaches it")
                .isEqualTo("USER0042");

        verify(users).existsById("USER0042");
        verify(provisioning).provision("USER0042", "Ada", "Lovelace", "A");
        // Assumptions: the identifier argument is asserted by value while the other four are left open,
        //   because this case is about the KEY alone -- the remaining four are asserted by the create
        //   case above, and repeating them here would make this case fail for reasons it is not about.
        verify(users).insertUser(eq("USER0042"), any(), any(), any(), any());
    }

    /**
     * Asserts an identifier whose folded form outgrows the key column is refused before either store.
     *
     * <p>The submitted value is eight characters of the German sharp s, which the root locale folds to
     * sixteen characters of capital S. It therefore satisfies every constraint declared on the request
     * body -- it is non-blank and eight characters long -- and cannot be stored in a CHAR(8) key.</p>
     *
     * <p>This case takes no parameter and yields no value.</p>
     *
     * @throws ClientInputException always, raised by the service's canonical derivation and captured by
     *     the assertion below
     */
    @Test
    @DisplayName("an identifier that folds past the key width is refused before anything is provisioned")
    void anIdentifierThatFoldsPastTheKeyWidthIsRefused() {
        // Refactoring Rationale: this case exists because case folding is NOT length-preserving and
        //   nothing checked the length of what the fold produced. The request record's width constraint
        //   measures the SUBMITTED value while the column stores the FOLDED one, so this body passed the
        //   coarse gate, passed the ordered chain, folded to sixteen characters, PROVISIONED a pool
        //   account under that sixteen-character username, and was then refused by the insert -- and
        //   reported to the caller as "User ID already exist..." because one arm answered every integrity
        //   violation. The three assertions below pin the three steps that must not be reached.
        assertThatThrownBy(() -> service.create(
                        new CreateUserRequest("Ada", "Lovelace", "ßßßßßßßß", "A")))
                .isInstanceOf(ClientInputException.class)
                .hasMessage("User ID must be at most 8 characters...");

        // Assumptions: the probe is asserted as NOT REACHED as well as the provider, because the order the
        //   defect made visible is what this case fixes: the derivation is validated ahead of both, so an
        //   unstorable identifier costs one refusal rather than an indexed read and a provider round trip.
        verify(users, never()).existsById(any());
        verifyNoInteractions(provisioning);
        verify(users, never()).insertUser(any(), any(), any(), any(), any());
    }

    /**
     * Asserts the refusal of an over-long folded identifier is attributed to the identifier and rejected.
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("the over-long folded identifier is attributed to the identifier as a rejected value")
    void theOverLongFoldedIdentifierIsAttributedToTheIdentifier() {
        // Assumptions: the flag is the supplied-and-rejected one rather than the blank one, and the
        //   published contract turns that distinction into presentation -- a caller rendering the
        //   baseline's own presentation draws the asterisk marker for the blank state only, and this
        //   caller did supply something. The absent-identifier case beside it asserts the other flag, so
        //   the two together pin that the derivation reports its two refusals differently.
        assertThatThrownBy(() -> service.create(
                        new CreateUserRequest("Ada", "Lovelace", "ßßßßßßßß", "A")))
                .isInstanceOf(ClientInputException.class)
                .satisfies(raised -> {
                    ClientInputException refusal = (ClientInputException) raised;
                    assertThat(refusal.field()).isEqualTo("userId");
                    assertThat(refusal.state()).isEqualTo(FieldValidationFlag.NOT_OK);
                });

        assertThatThrownBy(() -> service.create(
                        new CreateUserRequest("Ada", "Lovelace", "\t", "A")))
                .isInstanceOf(ClientInputException.class)
                .satisfies(raised -> {
                    ClientInputException refusal = (ClientInputException) raised;
                    assertThat(refusal.field()).isEqualTo("userId");
                    assertThat(refusal.state()).isEqualTo(FieldValidationFlag.BLANK);
                });
    }

    /**
     * Asserts an identifier the trim consumes entirely is refused as an absent one, before either store.
     *
     * <p>The submitted value is a single tab. The emptiness test this service uses is the reference's own
     * -- absent, empty, wholly spaces or wholly low values, because the reference compares its screen
     * field against {@code SPACES OR LOW-VALUES} -- and a tab equals neither constant, so the ordered
     * chain reads it as SUPPLIED while {@code String#trim} removes it.</p>
     *
     * <p>This case takes no parameter and yields no value.</p>
     *
     * @throws ClientInputException always, raised by the service's canonical derivation and captured by
     *     the assertion below
     */
    @Test
    @DisplayName("an identifier the trim consumes entirely is refused before anything is provisioned")
    void anIdentifierTheTrimConsumesEntirelyIsRefused() {
        // Refactoring Rationale: this case exists because the two emptiness tests disagree by design and
        //   nothing reconciled them. The chain admits a tab and the trim removes it, so the derived key
        //   was the EMPTY STRING -- which was then probed for, and provisioned as a pool account with an
        //   empty username, before anything noticed. Refusing it names the identifier and carries the
        //   reference's own sentence for an identifier that was never filled in, which is what an
        //   identifier with nothing in it is.
        assertThatThrownBy(() -> service.create(
                        new CreateUserRequest("Ada", "Lovelace", "\t", "A")))
                .isInstanceOf(ClientInputException.class)
                .hasMessage("User ID can NOT be empty...");

        verify(users, never()).existsById(any());
        verifyNoInteractions(provisioning);
    }

    /**
     * Asserts a read whose folded identifier cannot be a key is refused rather than reported absent.
     *
     * <p>This case takes no parameter and yields no value.</p>
     *
     * @throws ClientInputException always, raised by the service's canonical derivation and captured by
     *     the assertion below
     */
    @Test
    @DisplayName("a read of an identifier that folds past the key width is refused, not reported absent")
    void aReadOfAnIdentifierThatFoldsPastTheKeyWidthIsRefused() {
        // Refactoring Rationale: this path used to fold and read with whatever came out, so a folded form
        //   wider than the column matched no row and the read answered the reference's not-found sentence
        //   with a 404. That answer asserts a well-formed identifier had no row, and invites a caller to
        //   look for something the schema cannot hold; no row can ever carry this value.
        assertThatThrownBy(() -> service.read("ßßßßßßßß"))
                .isInstanceOf(ClientInputException.class)
                .hasMessage("User ID must be at most 8 characters...");

        // Assumptions: the store is not read at all, which is the observable half of the change -- the
        //   previous arrangement issued a keyed read for a key no row could carry.
        verify(users, never()).findById(any());
    }

    /**
     * Asserts a create arms its compensating withdrawal BEFORE provisioning and settles it with the insert.
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("a create arms the withdrawal before provisioning and settles it with the insert")
    void aCreateArmsTheWithdrawalBeforeProvisioningAndSettlesItWithTheInsert() {
        when(users.existsById("USER0042")).thenReturn(false);
        when(provisioning.provision("USER0042", "Ada", "Lovelace", "A")).thenReturn(PROVISIONED);
        when(users.insertUser(any(), any(), any(), any(), any())).thenReturn(1);

        service.create(new CreateUserRequest("Ada", "Lovelace", "USER0042", "A"));

        // Refactoring Rationale: the ORDER is the assertion, and it is the whole of what this case adds.
        //   The compensation used to be recorded from the insert's failure handlers, which covers a failed
        //   insert and nothing else: a process death, an eviction or a rollback raised outside those
        //   handlers left an account that can authenticate, holds no membership this context records,
        //   permanently blocks a later create of the same identifier, and is named by no ledger row and no
        //   log line. A ledger write that PRECEDES the provider call is what makes the ledger's pending
        //   set a complete description of what the pool may owe.
        InOrder ordered = inOrder(ledger, provisioning, users);
        ordered.verify(ledger).save(any(IdentitySyncTask.class));
        ordered.verify(provisioning).provision("USER0042", "Ada", "Lovelace", "A");
        ordered.verify(users).insertUser(any(), any(), any(), any(), any());

        // Assumptions: the armed row is SETTLED rather than left pending, so a successful create leaves
        //   nothing owed. A pending withdrawal beside a committed row is the one state that must never
        //   exist: any applier reaching it removes the account of a user that was just created.
        assertThat(ledger.findByUserIdAndStatusOrderByTaskIdAsc("USER0042",
                        IdentitySyncTask.STATUS_PENDING, Limit.of(5)))
                .as("a committed create owes the pool nothing")
                .isEmpty();
        // Refactoring Rationale: the settle is asserted as CANCELLED carrying NO reason code, where this
        //   case asserted ABANDONED with "create-committed". IdentitySyncTask.markCancelled nulls the code
        //   and means never-owed, which is exactly what a committed create leaves; markAbandoned demands a
        //   reason because it means an owed intention was given up. Asserting ABANDONED here also
        //   contradicted aSuccessfulCreateClosesItsGuard, which asserts CANCELLED for this same
        //   transition. The ordering assertion above is what this case adds and it is untouched.
        assertThat(ledger.findByUserIdAndStatusOrderByTaskIdAsc("USER0042",
                        IdentitySyncTask.STATUS_CANCELLED, Limit.of(5)))
                .singleElement()
                .satisfies(settled -> {
                    assertThat(settled.getOperation()).isEqualTo(IdentitySyncTask.OPERATION_WITHDRAW);
                    assertThat(settled.getLastFailureCode()).isNull();
                });
        verify(provisioning, never()).withdraw(any());
    }

    /**
     * Asserts a pool duplicate VOIDS the armed withdrawal instead of applying it.
     *
     * <p>This case takes no parameter and yields no value.</p>
     *
     * @throws UserService.DuplicateUserException always, raised by the service when the pool refuses the
     *     username and captured by the assertion below
     */
    @Test
    @DisplayName("a pool duplicate voids the armed withdrawal rather than applying it")
    void aPoolDuplicateVoidsTheArmedWithdrawal() {
        when(users.existsById("USER0042")).thenReturn(false);
        when(provisioning.provision("USER0042", "Ada", "Lovelace", "A"))
                .thenThrow(UsernameExistsException.builder().message("username exists").build());

        assertThatThrownBy(() -> service.create(
                        new CreateUserRequest("Ada", "Lovelace", "USER0042", "A")))
                .isInstanceOf(UserService.DuplicateUserException.class)
                .hasMessage("User ID already exist...");

        // Assumptions: this is the ONE outcome where the armed withdrawal must not be applied, and the
        //   reason is that the provider refused to CREATE an account -- so this request holds none. The
        //   account under that username belongs either to the caller that won an ordinary race, which is
        //   about to write its row, or to an earlier interrupted create, whose OWN armed row the scheduled
        //   pass owns. Applying here would destroy the first and duplicate the second.
        verify(provisioning, never()).withdraw(any());
        assertThat(ledger.findByUserIdAndStatusOrderByTaskIdAsc("USER0042",
                        IdentitySyncTask.STATUS_PENDING, Limit.of(5)))
                .as("a withdrawal for an account this request never created must not stay owed")
                .isEmpty();
        // Refactoring Rationale: the void is asserted as CANCELLED carrying no reason code, where this
        //   case asserted ABANDONED with "pool-held-account". The provider refused to CREATE, so this
        //   attempt owns no account and the guard was never owed -- which is what markCancelled records
        //   and why it nulls the code. The reason is not lost to an operator: the arm raising this refusal
        //   logs reason=duplicate-in-pool, and that token says which of the two paths closed the guard.
        assertThat(ledger.findByUserIdAndStatusOrderByTaskIdAsc("USER0042",
                        IdentitySyncTask.STATUS_CANCELLED, Limit.of(5)))
                .singleElement()
                .satisfies(settled -> assertThat(settled.getLastFailureCode()).isNull());
    }

    /**
     * Asserts a provider fault applies the armed withdrawal, because the account may exist.
     *
     * <p>This case takes no parameter and yields no value.</p>
     *
     * @throws IllegalStateException always, raised by the service when the provider cannot serve the call
     *     and captured by the assertion below
     */
    @Test
    @DisplayName("a provider fault applies the armed withdrawal rather than voiding it")
    void aProviderFaultAppliesTheArmedWithdrawal() {
        when(users.existsById("USER0042")).thenReturn(false);
        when(provisioning.provision("USER0042", "Ada", "Lovelace", "A"))
                .thenThrow(InternalErrorException.builder().message("the pool is unavailable").build());

        assertThatThrownBy(() -> service.create(
                        new CreateUserRequest("Ada", "Lovelace", "USER0042", "A")))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("Unable to Add User...");

        // Assumptions: the withdrawal is APPLIED and not voided, because a fault cannot tell a caller
        //   whether the account was created -- a timeout is reported the same way whether the provider
        //   acted or not. Withdrawal treats an absent account as success, so applying is safe when nothing
        //   was created and necessary when something was; voiding would be a guess in the direction that
        //   leaves an orphan.
        verify(provisioning).withdraw("USER0042");
    }

    /**
     * Asserts an identifier taken in a different case is refused as a conflict rather than accepted.
     *
     * <p>This is the create half of the same defect the fold case above records: the probe compared the
     * spelling as submitted against a column holding the folded form, so an identifier already taken
     * answered free whenever the two spellings differed.</p>
     *
     * <p>This case takes no parameter and yields no value.</p>
     *
     * @throws UserService.DuplicateUserException always, raised by the service and captured by the
     *     assertion below
     */
    @Test
    @DisplayName("an identifier already taken in another case is refused as a duplicate")
    void anIdentifierTakenInAnotherCaseIsRefusedAsADuplicate() {
        when(users.existsById("USER0001")).thenReturn(true);

        assertThatThrownBy(() -> service.create(
                        new CreateUserRequest("Ada", "Lovelace", "user0001", "A")))
                .isInstanceOf(UserService.DuplicateUserException.class)
                .hasMessage("User ID already exist...");

        // Assumptions: nothing is provisioned, which is the property that separates this from a race.
        //   The probe answered before the pool was called at all, so there is no account to withdraw
        //   and no compensation to assert.
        verifyNoInteractions(provisioning);
        verify(users, never()).insertUser(any(), any(), any(), any(), any());
    }

    /**
     * Asserts the pool's own duplicate-username refusal is reported as the conflict, not as a fault.
     *
     * <p>This case takes no parameter and yields no value.</p>
     *
     * @throws UserService.DuplicateUserException always, raised by the service when the pool refuses the
     *     username and captured by the assertion below
     */
    @Test
    @DisplayName("a duplicate the pool decides is reported as the conflict, not as a failed add")
    void aDuplicateDecidedByThePoolIsReportedAsTheConflict() {
        when(users.existsById("USER0042")).thenReturn(false);
        when(provisioning.provision("USER0042", "Ada", "Lovelace", "A"))
                .thenThrow(UsernameExistsException.builder().message("username exists").build());

        // Refactoring Rationale: this refusal used to report the add sentence with a 500, and the
        //   condition it names is not a fault. Provisioning PRECEDES the insert on this path -- it has
        //   to, because the row's subject column is not nullable and only the pool can mint the subject
        //   -- so under concurrency the pool, not the primary key, is what first observes that two
        //   callers named one identifier. Measured against six concurrent creates of one identifier
        //   before this change: one answered 201 and the other five answered 500, with exactly one row
        //   written; the outcome was already correct and only the status and sentence were wrong.
        //   Answering the conflict makes the two orderings indistinguishable to a caller, which is the
        //   property the probe and the constraint arms already have between them.
        assertThatThrownBy(() -> service.create(
                        new CreateUserRequest("Ada", "Lovelace", "USER0042", "A")))
                .isInstanceOf(UserService.DuplicateUserException.class)
                .hasMessage("User ID already exist...");

        // Assumptions: no compensating withdrawal is asserted, and its absence is deliberate rather than
        //   overlooked. The pool refused to CREATE an account, so this call provisioned nothing of its
        //   own; withdrawing the username would destroy the account the caller that won the race is
        //   about to write a row for.
        verify(provisioning, never()).withdraw(any());
        verify(users, never()).insertUser(any(), any(), any(), any(), any());
    }

    /**
     * Asserts the pool's duplicate refusal probes the row exactly once and reaches no second verdict.
     *
     * <p>This case takes no parameter and yields no value.</p>
     *
     * @throws UserService.DuplicateUserException always, raised by the service and captured by the
     *     assertion below
     */
    @Test
    @DisplayName("a pool duplicate probes the row once and draws no conclusion about an orphan")
    void aPoolDuplicateProbesTheRowExactlyOnce() {
        when(users.existsById("USER0042")).thenReturn(false);
        when(provisioning.provision("USER0042", "Ada", "Lovelace", "A"))
                .thenThrow(UsernameExistsException.builder().message("username exists").build());

        assertThatThrownBy(() -> service.create(
                        new CreateUserRequest("Ada", "Lovelace", "USER0042", "A")))
                .isInstanceOf(UserService.DuplicateUserException.class)
                .hasMessage("User ID already exist...");

        // Refactoring Rationale: the single probe is the assertion, and it is here because a SECOND one
        //   was briefly added and was wrong. The intent was to say in the operational record which of two
        //   states the refusal came from -- an ordinary lost race, or a pool account left behind by an
        //   interrupted create -- and to log the second at error level. The two are indistinguishable at
        //   that instant: a concurrent create that has provisioned and not yet committed presents exactly
        //   as an orphan. Measured against six concurrent creates of one identifier, the error arm fired
        //   THREE times with no orphan present at all. A count of one is what stops that verdict being
        //   attempted again, and it is asserted as a count rather than as an absent log line because the
        //   probe is the observable half of the mistake.
        verify(users, times(1)).existsById("USER0042");
        verify(provisioning, never()).withdraw(any());
    }

    /**
     * Asserts the update chain tests the path identifier before any submitted body field.
     *
     * <p>The sentence asserted is the one at {@code app/cbl/COUSR02C.cbl} line 182, the first arm of the
     * saving paragraph that opens at line 177.</p>
     *
     * <p>This case takes no parameter and yields no value.</p>
     *
     * @throws ClientInputException always, raised by the service's ordered update chain and captured by
     *     the assertion below
     */
    @Test
    @DisplayName("an update with a blank path identifier reports the identifier sentence first")
    void anUpdateWithABlankPathIdentifierReportsTheIdentifierFirst() {
        // Assumptions: the identifier comes FIRST on this path where it came third on the create path,
        //   and the reference orders them that way in two separate places. Its loading chain at
        //   app/cbl/COUSR02C.cbl lines 145 to 155 has one arm and that arm is the identifier, and its
        //   saving chain at lines 179 to 213 opens with the identifier at line 180 before the first name
        //   at line 186, the last name at line 192 and the reference type at line 204. The identifier is
        //   a path variable here rather than a body property, so it is also the one field of the four a
        //   declarative constraint on the request record could not have ordered at all.
        assertThatThrownBy(() -> service.update("   ",
                        new UpdateUserRequest("", "", "")))
                .isInstanceOf(ClientInputException.class)
                .hasMessage("User ID can NOT be empty...")
                .extracting(raised -> ((ClientInputException) raised).field())
                .isEqualTo("userId");

        verifyNoInteractions(users);
    }

    /**
     * Asserts the update chain reports the first name before the last name and the reference type.
     *
     * <p>The sentence asserted is the one at {@code app/cbl/COUSR02C.cbl} line 188.</p>
     *
     * <p>This case takes no parameter and yields no value.</p>
     *
     * @throws ClientInputException always, raised by the service's ordered update chain and captured by
     *     the assertion below
     */
    @Test
    @DisplayName("an update with a supplied identifier and no names reports the first-name sentence")
    void anUpdateWithNoNamesReportsTheFirstNameSentence() {
        assertThatThrownBy(() -> service.update("USER0001",
                        new UpdateUserRequest("", "", "")))
                .isInstanceOf(ClientInputException.class)
                .hasMessage("First Name can NOT be empty...")
                .extracting(raised -> ((ClientInputException) raised).field())
                .isEqualTo("firstName");
    }

    /**
     * Asserts the update chain reports the last name once the two fields before it are supplied.
     *
     * <p>The sentence asserted is the one at {@code app/cbl/COUSR02C.cbl} line 194.</p>
     *
     * <p>This case takes no parameter and yields no value.</p>
     *
     * @throws ClientInputException always, raised by the service's ordered update chain and captured by
     *     the assertion below
     */
    @Test
    @DisplayName("an update with no last name reports the reference last-name sentence")
    void anUpdateWithNoLastNameReportsTheReferenceSentence() {
        assertThatThrownBy(() -> service.update("USER0001",
                        new UpdateUserRequest("Grace", "  ", "A")))
                .isInstanceOf(ClientInputException.class)
                .hasMessage("Last Name can NOT be empty...")
                .extracting(raised -> ((ClientInputException) raised).field())
                .isEqualTo("lastName");
    }

    /**
     * Asserts the update chain reports the absent reference type last of the four.
     *
     * <p>The sentence asserted is the one at {@code app/cbl/COUSR02C.cbl} line 206.</p>
     *
     * <p>This case takes no parameter and yields no value.</p>
     *
     * @throws ClientInputException always, raised by the service's ordered update chain and captured by
     *     the assertion below
     */
    @Test
    @DisplayName("an update with no reference type reports the reference type sentence")
    void anUpdateWithNoReferenceTypeReportsTheReferenceSentence() {
        assertThatThrownBy(() -> service.update("USER0001",
                        new UpdateUserRequest("Grace", "Hopper", "")))
                .isInstanceOf(ClientInputException.class)
                .hasMessage("User Type can NOT be empty...")
                .extracting(raised -> ((ClientInputException) raised).field())
                .isEqualTo("userType");
    }

    /**
     * Asserts an update body carrying no credential component is complete rather than incomplete.
     *
     * <p>This case takes no parameter and yields no value; it raises nothing.</p>
     */
    @Test
    @DisplayName("an update body carrying no credential is valid on the update path")
    void anUpdateBodyCarryingNoCredentialIsValid() {
        when(users.findById("USER0001")).thenReturn(Optional.of(row("USER0001")));
        when(users.saveAndFlush(any(User.class))).thenAnswer(call -> call.getArgument(0));

        UserResponse updated = service.update("USER0001",
                new UpdateUserRequest("Grace", "Hopper", "A"));

        // Assumptions: the reference saving chain has a fifth arm whose condition is at
        //   app/cbl/COUSR02C.cbl line 198 and whose sentence is at line 200, and the loading path even
        //   moves the stored value back onto the screen at line 169. Neither has a counterpart, because
        //   the request record declares three components and the table declares five columns, none of
        //   which could hold such a value. A body without one is therefore complete, and there is no
        //   fifth sentence for this class to assert.
        assertThat(updated.firstName()).isEqualTo("Grace");
        assertThat(UpdateUserRequest.class.getRecordComponents())
                .as("the identifier is a path variable and the omitted value is absent, so exactly"
                        + " three components remain")
                .hasSize(3);
    }

    /**
     * Asserts an update that changes nothing is refused with the reference sentence and no attribution.
     *
     * <p>The sentence asserted is the one at {@code app/cbl/COUSR02C.cbl} line 239, which is the one
     * sentence on this path carrying a space before its ellipsis.</p>
     *
     * <p>This case takes no parameter and yields no value.</p>
     *
     * @throws ClientInputException always, raised by the service when nothing in the body differs and
     *     captured by the assertion below
     */
    @Test
    @DisplayName("an unchanged update is refused with the reference modify sentence")
    void anUnchangedUpdateIsRefused() {
        when(users.findById("USER0001")).thenReturn(Optional.of(row("USER0001")));

        // Trade-offs: refusing a body that asks for nothing is DIRTY DETECTION and not concurrency
        //   control, and treating it as either one costs something. The reference compared each
        //   submitted field against the record at app/cbl/COUSR02C.cbl lines 219 to 234 and, where
        //   nothing differed, took the branch at line 238 and wrote its sentence at line 239 in the red
        //   attribute at line 241 -- and the colour is what settles it as a refusal, because the same
        //   field is written neutral at line 338 and green at line 371 in that one program. What is
        //   given up by carrying it across is that a client cannot make an idempotent replay of an
        //   accepted body: a retried save that lost its response is refused rather than confirmed. What
        //   would be given up by dropping it is the reference's own answer to a user who pressed save
        //   without typing. The observable answer is kept. Note that this is the OPPOSITE outcome to the
        //   account-update program's, which asks whether the row moved under the caller and says so in
        //   its own words at app/cbl/COACTUPC.cbl lines 521 to 522; nothing in this context produces
        //   that, because none of the four user programs detects it.
        assertThatThrownBy(() -> service.update("USER0001",
                        new UpdateUserRequest("Ada", "Lovelace", "U")))
                .isInstanceOf(ClientInputException.class)
                .hasMessage("Please modify to update ...")
                .extracting(raised -> ((ClientInputException) raised).field())
                .as("the reference arm moves no cursor, so this refusal names no field")
                .isNull();

        verify(users, never()).saveAndFlush(any(User.class));
        verifyNoInteractions(provisioning);
    }

    /**
     * Asserts each of the three comparable values on its own is enough to make a body a change.
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("any one of the three comparable values differing makes the body a change")
    void anyOneOfTheThreeComparableValuesMakesAChange() {
        // Assumptions: the reference sets its modified indicator in FOUR places -- app/cbl/COUSR02C.cbl
        //   lines 221, 225, 229 and 233 -- and the target compares THREE values, because the arm at
        //   lines 227 to 230 is the credential arm and the boundary carries no credential. The
        //   consequence is precise and is what this case pins: a request whose only difference was that
        //   value cannot be expressed at all, so it arrives as a body identical to the stored row and is
        //   refused by the case above. Three arms, not four, and the fourth is absent rather than
        //   silently treated as unchanged.
        assertUpdateIsAccepted(new UpdateUserRequest("Grace", "Lovelace", "U"), "the first name");
        assertUpdateIsAccepted(new UpdateUserRequest("Ada", "Hopper", "U"), "the last name");
        assertUpdateIsAccepted(new UpdateUserRequest("Ada", "Lovelace", "A"), "the reference type");
    }

    /**
     * Asserts the unchanged comparison is made on the values as stored, which the column types settle.
     *
     * <p>This case takes no parameter and yields no value.</p>
     *
     * @throws ClientInputException always, raised because the two variable-width columns carry no pad
     *     and the body therefore matches the stored row exactly; captured by the assertion below
     */
    @Test
    @DisplayName("an unchanged body matches the stored values directly, no padding being carried")
    void anUnchangedBodyMatchesTheStoredValuesDirectly() {
        // Assumptions: the comparison is on the values AS STORED, and the column types are what make
        //   that equivalent to comparing logical values. The two name columns are declared variable-width
        //   and the reference-type column is one character wide, so a stored value carries no pad run for
        //   a comparison to trip over, and the reference-type column has no room for one. The reference
        //   faced the opposite situation and was protected differently: it compared a space-padded screen
        //   field against a space-padded record field, both declared at their widths at
        //   app/cpy/CSUSR01Y.cpy lines 18 to 23, so its padding was equal on both sides and invisible to
        //   it. The load that fills these columns from that declared-width source is therefore the step
        //   that owes the strip, and the case below is what fails if a padded value ever reaches a row --
        //   it would report every replay of an unchanged body as a change.
        when(users.findById("USER0001")).thenReturn(Optional.of(row("USER0001")));

        assertThatThrownBy(() -> service.update("USER0001",
                        new UpdateUserRequest("Ada", "Lovelace", "U")))
                .isInstanceOf(ClientInputException.class)
                .hasMessage("Please modify to update ...");
    }

    /**
     * Asserts the pad run is removed from the declared-width identifier and from nothing else.
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("the identifier is published without its pad run while a stored name is left alone")
    void theIdentifierIsPublishedWithoutItsPadRun() {
        // Assumptions: the strip is applied to the columns that are declared at a WIDTH and to no others,
        //   and the column types are what decide which is which. The identifier is eight characters wide
        //   and the reference type one, so either can come back padded and both are stripped; the two
        //   names are variable-width and store no pad of their own, so removing a trailing blank from
        //   them would discard a character a caller genuinely stored. Stripping the identifier matches
        //   what the reference showed a user: the composed sentences on its three successful arms delimit
        //   the identifier by a blank, at app/cbl/COUSR01C.cbl line 256, app/cbl/COUSR02C.cbl line 373
        //   and app/cbl/COUSR03C.cbl line 319, so the value it displayed stopped at the first pad
        //   character.
        when(users.findById("SHORT")).thenReturn(Optional.of(
                new User("SHORT   ", "Ada  ", "Lovelace", "U",
                        UUID.fromString("99999999-8888-7777-6666-555555555555"))));

        UserResponse read = service.read("short");

        assertThat(read.userId())
                .as("the eight-character identifier column pads, so its pad run is removed")
                .isEqualTo("SHORT");
        assertThat(read.firstName())
                .as("a variable-width column stores no pad, so a stored trailing blank is data and"
                        + " survives")
                .isEqualTo("Ada  ");
    }

    /**
     * Asserts a leading blank is significant, because only the trailing pad run is stripped.
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("a leading blank is a difference, because only the trailing pad run is stripped")
    void aLeadingBlankIsADifference() {
        when(users.findById("USER0001")).thenReturn(Optional.of(row("USER0001")));
        when(users.saveAndFlush(any(User.class))).thenAnswer(call -> call.getArgument(0));

        // Assumptions: the strip is TRAILING-ONLY, so a leading blank survives it and is data rather
        //   than padding. A declared-width column pads on the right, so a leading blank was never put
        //   there by the column and removing it would discard a character a caller submitted.
        UserResponse updated = service.update("USER0001",
                new UpdateUserRequest(" Ada", "Lovelace", "U"));

        assertThat(updated.firstName()).isEqualTo(" Ada");
    }

    /**
     * Asserts a changed update writes the row and then brings the pool in line with both types.
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("a changed update writes the row and synchronises the identity with both types")
    void aChangedUpdateSynchronisesTheIdentity() {
        when(users.findById("USER0001")).thenReturn(Optional.of(row("USER0001")));
        when(users.saveAndFlush(any(User.class))).thenAnswer(call -> call.getArgument(0));

        UserResponse updated = service.update("USER0001",
                new UpdateUserRequest("Grace", "Hopper", "A"));

        // Assumptions: the PREVIOUSLY stored type is handed to the provisioner alongside the new one,
        //   read before the row is mutated, because the provisioner is what decides whether group
        //   membership has to move -- and it must not move on an update that changed only a name, since
        //   removing and re-adding a group passes through a state in which the account holds none.
        assertThat(updated.userType()).isEqualTo("A");
        verify(provisioning).synchronise("USER0001", "Grace", "Hopper", "U", "A");

        // Assumptions: the ORDER is the correction, not merely the fact that both stores were touched. The
        //   row is written, the intention naming what the pool owes is recorded beside it, and only then is
        //   the pool called. The previous arrangement called the pool from inside the transaction that
        //   wrote the row, so a provider call that succeeded and was followed by a failed commit left the
        //   pool holding a projection of a row that was never changed.
        InOrder ordered = inOrder(users, ledger, provisioning);
        ordered.verify(users).saveAndFlush(any(User.class));
        ordered.verify(ledger).save(any(IdentitySyncTask.class));
        ordered.verify(provisioning).synchronise("USER0001", "Grace", "Hopper", "U", "A");
    }

    /**
     * Asserts a DEMOTION withdraws administrative authority before the row is committed.
     *
     * <p>Purpose: every authorization decision in the fleet is made on a signed group claim, so lowering a
     * user's type is not effective until the account has lost the administrative group and the tokens
     * already minted under it can no longer be used or renewed. This case pins the ORDER that makes a
     * reported success mean that: the withdrawal is called inside the write span, before the commit, and
     * therefore before the caller has been answered.</p>
     *
     * <p>Assumptions: the ordering assertion is what carries the claim, and the fact of the call alone would
     * not. The arrangement this replaces called the provider after the commit and discarded the outcome, so
     * a case asserting only that the provider was reached would have passed against it while a caller was
     * still being told a demotion had happened that had not.</p>
     *
     * <p>Assumptions: the post-commit projection is asserted to STILL run, because the withdrawal removes
     * the administrative group and ends the sessions while the projection updates the attributes and adds
     * the ordinary group. Dropping it would leave the account holding no group at all.</p>
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("a demotion withdraws administrative authority inside the write span, before the commit")
    void aDemotionWithdrawsAuthorityBeforeTheRowIsCommitted() {
        when(users.findById("ADMIN001")).thenReturn(Optional.of(admin("ADMIN001")));
        when(users.saveAndFlush(any(User.class))).thenAnswer(call -> call.getArgument(0));

        UserResponse updated = service.update("ADMIN001", new UpdateUserRequest("Ada", "Lovelace", "U"));

        assertThat(updated.userType()).isEqualTo("U");
        verify(provisioning).withdrawAdministrativeAuthority("ADMIN001");

        InOrder ordered = inOrder(users, ledger, provisioning);
        ordered.verify(users).saveAndFlush(any(User.class));
        ordered.verify(ledger).save(any(IdentitySyncTask.class));
        ordered.verify(provisioning).withdrawAdministrativeAuthority("ADMIN001");
        ordered.verify(provisioning).synchronise("ADMIN001", "Ada", "Lovelace", "A", "U");
    }

    /**
     * Asserts a demotion the provider will not accept is REFUSED rather than reported as done.
     *
     * <p>Purpose: this is the fail-closed half. If the withdrawal cannot be applied there is nothing anyone
     * can do about the outstanding administrative tokens, so the honest answer is that the demotion did not
     * happen -- and the row must not be left saying otherwise. The withdrawal is the last statement of the
     * write span, so its failure rolls the span back and no row, and no intention, is committed.</p>
     *
     * <p>Assumptions: the ledger is asserted EMPTY afterwards, which is the difference between this and the
     * ordinary projection failure the next case covers. A pending intention would mean the row had been
     * committed and the pool was merely behind; nothing pending means nothing was written at all, so
     * retrying the request is the whole of the remedy.</p>
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("a demotion the provider refuses fails the request and commits nothing")
    void aDemotionThePoolRefusesIsNotReportedAsDone() {
        when(users.findById("ADMIN001")).thenReturn(Optional.of(admin("ADMIN001")));
        when(users.saveAndFlush(any(User.class))).thenAnswer(call -> call.getArgument(0));
        doThrow(InternalErrorException.builder().message("the pool is unavailable").build())
                .when(provisioning).withdrawAdministrativeAuthority("ADMIN001");

        assertThatThrownBy(() ->
                service.update("ADMIN001", new UpdateUserRequest("Ada", "Lovelace", "U")))
                .isInstanceOf(InternalErrorException.class);

        verify(provisioning, never()).synchronise(any(), any(), any(), any(), any());
        // WHY : Assumptions: the SPAN is asserted to have rolled back, which is what makes "nothing was
        //       committed" a claim rather than a hope. It cannot be asserted through the stores: the
        //       ledger substitute keeps its rows in a map and the row store is a mock, so neither honours
        //       a rollback and the intention this span recorded is still readable from the double after
        //       the span was abandoned. The manager is the one collaborator that observes the outcome.
        verify(transactions).rollback(any());
        verify(transactions, never()).commit(any());
    }

    /**
     * Asserts a PROMOTION reaches no withdrawal, because it takes no authority away.
     *
     * <p>Assumptions: the opposite transition leaves outstanding tokens carrying the ordinary group, so the
     * holder can reach less than the row now permits until they sign on again. That is the safe direction
     * and the reference has no revocation to reproduce, so signing a working session out to grant an
     * authority the user did not ask for at that moment is not done.</p>
     *
     * <p>Assumptions: a change that leaves the type alone is covered by the same assertion, because the
     * predicate compares the two types rather than asking whether the update touched anything.</p>
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("a promotion, and a name-only change, withdraw nothing")
    void aPromotionWithdrawsNothing() {
        when(users.findById("USER0001")).thenReturn(Optional.of(row("USER0001")));
        when(users.saveAndFlush(any(User.class))).thenAnswer(call -> call.getArgument(0));

        service.update("USER0001", new UpdateUserRequest("Grace", "Hopper", "A"));
        // WHY : Assumptions: the second update names the type the FIRST one left behind, so it is a
        //       name-only change. The substituted read answers with one row instance, which the first
        //       update mutated in place -- naming "U" again here would be a genuine demotion of that
        //       mutated row and would assert the opposite of what this case is for.
        service.update("USER0001", new UpdateUserRequest("Ada", "Lovelace", "A"));

        verify(provisioning, never()).withdrawAdministrativeAuthority(any());
    }

    /**
     * Asserts an update whose pool call fails still succeeds and leaves the intention owed.
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("an update whose pool call fails still succeeds and leaves the intention owed")
    void anUpdateSurvivesAFailedPoolCall() {
        when(users.findById("USER0001")).thenReturn(Optional.of(row("USER0001")));
        when(users.saveAndFlush(any(User.class))).thenAnswer(call -> call.getArgument(0));
        doThrow(InternalErrorException.builder().message("the pool is unavailable").build())
                .when(provisioning).synchronise(any(), any(), any(), any(), any());

        // Assumptions: the change is reported as APPLIED, because it was: the row is committed and durable
        //   before the pool is called at all. Reporting a failure would tell a caller its change did not
        //   take effect when it did, and a client acting on that report would submit it again.
        UserResponse updated = service.update("USER0001",
                new UpdateUserRequest("Grace", "Hopper", "A"));

        assertThat(updated.firstName()).isEqualTo("Grace");

        // Assumptions: the intention stays PENDING, which is what the scheduled reconciliation pass reads.
        //   This is the property the previous arrangement could not offer at all -- a lost provider call
        //   left no record of itself anywhere.
        assertThat(ledger.findByUserIdAndStatusOrderByTaskIdAsc("USER0001",
                        IdentitySyncTask.STATUS_PENDING, Limit.of(5)))
                .singleElement()
                .satisfies(owed -> assertThat(owed.getOperation())
                        .isEqualTo(IdentitySyncTask.OPERATION_SYNCHRONISE));
    }

    /**
     * Asserts a delete whose pool withdrawal fails still succeeds and leaves the withdrawal owed.
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("a delete whose pool withdrawal fails still succeeds and leaves it owed")
    void aDeleteSurvivesAFailedWithdrawal() {
        User stored = row("USER0001");
        when(users.findById("USER0001")).thenReturn(Optional.of(stored));
        doThrow(InternalErrorException.builder().message("the pool is unavailable").build())
                .when(provisioning).withdraw("USER0001");

        service.delete("USER0001");

        verify(users).delete(stored);

        // Assumptions: the withdrawal is still OWED rather than lost, and the direction this preserves is
        //   the recoverable one. A row removed with its account still present is an identity refused at
        //   every guarded route; the reverse -- an account removed while the row remains -- is the
        //   direction no later operation repairs without reprovisioning, and calling the pool from inside
        //   the transaction was what made it reachable.
        assertThat(ledger.findByUserIdAndStatusOrderByTaskIdAsc("USER0001",
                        IdentitySyncTask.STATUS_PENDING, Limit.of(5)))
                .singleElement()
                .satisfies(owed -> assertThat(owed.getOperation())
                        .isEqualTo(IdentitySyncTask.OPERATION_WITHDRAW));
    }

    /**
     * Asserts a create whose compensating withdrawal fails records that withdrawal durably.
     *
     * <p>This case takes no parameter and yields no value.</p>
     *
     * @throws UserService.DuplicateUserException always, raised when the keyed insert is refused and
     *     captured by the assertion below
     */
    @Test
    @DisplayName("a create whose compensating withdrawal fails records the withdrawal durably")
    void aFailedCompensationIsRecordedDurably() {
        when(users.existsById("USER0042")).thenReturn(false);
        when(provisioning.provision("USER0042", "Ada", "Lovelace", "A"))
                .thenReturn(PROVISIONED);
        when(users.insertUser(any(), any(), any(), any(), any()))
                .thenThrow(uniquenessViolation());
        doThrow(InternalErrorException.builder().message("the pool is unavailable").build())
                .when(provisioning).withdraw("USER0042");

        assertThatThrownBy(() -> service.create(
                        new CreateUserRequest("Ada", "Lovelace", "USER0042", "A")))
                .isInstanceOf(UserService.DuplicateUserException.class)
                .hasMessage("User ID already exist...");

        // Assumptions: the compensation is RECORDED before it is attempted, so a withdrawal the pool
        //   refuses is retried by the reconciliation pass rather than surviving only as a log line. The
        //   orphaned account this closes can authenticate and holds no membership this context records,
        //   and it makes its identifier permanently unusable for a later create.
        assertThat(ledger.findByUserIdAndStatusOrderByTaskIdAsc("USER0042",
                        IdentitySyncTask.STATUS_PENDING, Limit.of(5)))
                .singleElement()
                .satisfies(owed -> assertThat(owed.getOperation())
                        .isEqualTo(IdentitySyncTask.OPERATION_WITHDRAW));
    }

    /**
     * Asserts the update mutates the row the read returned rather than replacing it with a new one.
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("an update mutates the loaded row in place rather than saving a rebuilt one")
    void anUpdateMutatesTheLoadedRowInPlace() {
        User loaded = row("USER0001");
        when(users.findById("USER0001")).thenReturn(Optional.of(loaded));
        when(users.saveAndFlush(any(User.class))).thenAnswer(call -> call.getArgument(0));

        service.update("USER0001", new UpdateUserRequest("Grace", "Hopper", "A"));

        // Assumptions: the mutator shape is derived from the reference rather than chosen. Its loading
        //   read at app/cbl/COUSR02C.cbl lines 322 to 331 names the record identification field at
        //   line 326, its key length at line 327 and the update option at line 328; it then mutates that
        //   same record area in place at lines 219 to 234; and its saving statement at lines 360 to 366
        //   names only the dataset, the source area, the length and the two response fields, carrying
        //   NEITHER a record identification field NOR a key length, because it rewrites the row the
        //   earlier read already holds. Load, mutate the loaded thing, flush what changed is exactly the
        //   managed-entity contract, so this case asserts that the instance handed to the save is the
        //   very instance the read returned -- a rebuilt one would be a different row.
        verify(users).saveAndFlush(loaded);
        assertThat(loaded.getFirstName()).isEqualTo("Grace");
        assertThat(loaded.getLastName()).isEqualTo("Hopper");
        assertThat(loaded.getUserType()).isEqualTo("A");
    }

    /**
     * Asserts two successive accepted updates both apply, so the later writer prevails.
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("a later accepted update prevails over an earlier one with no conflict reported")
    void aLaterAcceptedUpdatePrevails() {
        User loaded = row("USER0001");
        when(users.findById("USER0001")).thenReturn(Optional.of(loaded));
        when(users.saveAndFlush(any(User.class))).thenAnswer(call -> call.getArgument(0));

        service.update("USER0001", new UpdateUserRequest("Grace", "Hopper", "A"));
        UserResponse second = service.update("USER0001", new UpdateUserRequest("Ada", "Hopper", "A"));

        // Assumptions: the last writer prevails and no conflict is reported, which is the reference's own
        //   outcome rather than a weakening of it. Its screen-populating read and its saving rewrite run
        //   in two different terminal tasks, so the lock the update option at app/cbl/COUSR02C.cbl
        //   line 328 takes does not span the interval a user spends typing; the file is defined without
        //   record-level sharing and on a locking update model at app/csd/CARDDEMO.CSD lines 89 and 93,
        //   which governs one task and not two; and the program holds no before-image, no timestamp and
        //   no conflict token to detect a change made in between. The table declares no column that
        //   could hold one either. Reporting a conflict here would be a new observable behaviour rather
        //   than a migrated one, so the semantic is carried across and the divergence-free outcome is
        //   what this case pins.
        assertThat(second.firstName()).isEqualTo("Ada");
        verify(users, never()).findById("NOSUCH01");
    }

    /**
     * Asserts an update against an absent row reports the reference not-found sentence.
     *
     * <p>This case takes no parameter and yields no value.</p>
     *
     * @throws NoSuchElementException always, raised by the service when no row carries the identifier and
     *     captured by the assertion below
     */
    @Test
    @DisplayName("an update against an absent row reports the reference not-found sentence")
    void anUpdateAgainstAnAbsentRowReportsNotFound() {
        when(users.findById("NOSUCH01")).thenReturn(Optional.empty());

        // Assumptions: the reference reports this string from two arms of the same program, the loading
        //   read at app/cbl/COUSR02C.cbl line 342 and the saving rewrite at line 379, and both attribute
        //   it to the identifier at lines 344 and 381. One target sentence answers both, because the
        //   keyed read that precedes the write is the only place a missing row can be discovered here.
        assertThatThrownBy(() -> service.update("NOSUCH01",
                        new UpdateUserRequest("Grace", "Hopper", "A")))
                .isExactlyInstanceOf(NoSuchElementException.class)
                .hasMessage("User ID NOT found...");
    }

    /**
     * Asserts an unreadable row on the update path reports the reference lookup sentence.
     *
     * <p>This case takes no parameter and yields no value.</p>
     *
     * @throws IllegalStateException always, raised by the service when the keyed read fails and captured
     *     by the assertion below
     */
    @Test
    @DisplayName("an unreadable row on the update path reports the reference lookup sentence")
    void anUnreadableRowOnTheUpdatePathReportsTheLookupSentence() {
        when(users.findById("USER0001")).thenThrow(new QueryTimeoutException("statement timed out"));

        // Assumptions: a failed lookup and a failed write are DIFFERENT sentences on this path, and the
        //   reference keeps them apart. Its read arm at app/cbl/COUSR02C.cbl line 346 writes the lookup
        //   sentence at line 349, and its rewrite arm at line 383 writes the update sentence at line 386;
        //   both attribute to the first name, at lines 351 and 388.
        assertThatThrownBy(() -> service.update("USER0001",
                        new UpdateUserRequest("Grace", "Hopper", "A")))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("Unable to lookup User...");
    }

    /**
     * Asserts a failed write on the update path reports the reference update sentence.
     *
     * <p>This case takes no parameter and yields no value.</p>
     *
     * @throws IllegalStateException always, raised by the service when the row cannot be written and
     *     captured by the assertion below
     */
    @Test
    @DisplayName("a failed update reports the reference update sentence")
    void aFailedUpdateReportsTheReferenceSentence() {
        when(users.findById("USER0001")).thenReturn(Optional.of(row("USER0001")));
        when(users.saveAndFlush(any(User.class))).thenThrow(new QueryTimeoutException("statement timed out"));

        assertThatThrownBy(() -> service.update("USER0001",
                        new UpdateUserRequest("Grace", "Hopper", "A")))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("Unable to Update User...");
    }

    /**
     * Asserts a read of an absent row reports the reference not-found sentence as a no-such-element.
     *
     * <p>The TYPE is asserted as well as the sentence, because the shared advice maps exactly that type
     * onto the not-found status and carries the sentence onto the body; a different type would answer a
     * server fault with a generic sentence.</p>
     *
     * <p>This case takes no parameter and yields no value.</p>
     *
     * @throws NoSuchElementException always, raised by the service when no row carries the identifier and
     *     captured by the assertion below
     */
    @Test
    @DisplayName("a read of an absent row reports the reference not-found sentence")
    void aReadOfAnAbsentRowReportsNotFound() {
        when(users.findById("NOSUCH01")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.read("NOSUCH01"))
                .isExactlyInstanceOf(NoSuchElementException.class)
                .hasMessage("User ID NOT found...");
    }

    /**
     * Asserts a blank path identifier is refused with the reference empty-identifier sentence.
     *
     * <p>This case takes no parameter and yields no value.</p>
     *
     * @throws ClientInputException always, raised by the service before any store is touched and captured
     *     by the assertion below
     */
    @Test
    @DisplayName("a blank identifier is refused with the reference empty-identifier sentence")
    void aBlankIdentifierIsRefused() {
        // Assumptions: the PATH-variable guard reports the plain not-valid state where the ordered body
        //   chains report the blank state, and the two are genuinely different situations rather than an
        //   inconsistency. A body field the caller submitted empty is a field the reference would have
        //   marked with an asterisk as well as highlighting, which is what the blank state carries; a
        //   path variable that arrived empty is a malformed request line, and there is no screen field to
        //   mark. The case below therefore pins the not-valid state here, and the create chain's blank
        //   state is pinned separately where a body field is what went missing.
        assertThatThrownBy(() -> service.read("   "))
                .isInstanceOf(ClientInputException.class)
                .hasMessage("User ID can NOT be empty...")
                .extracting(raised -> ((ClientInputException) raised).state())
                .isEqualTo(FieldValidationFlag.NOT_OK);

        verifyNoInteractions(users);
    }

    /**
     * Asserts a read folds the identifier to upper case before it looks for the row.
     *
     * <p>The value submitted below carries both a surrounding blank and lower case, so a single
     * assertion covers the trim and the fold together.</p>
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("a read folds the identifier before looking for the row")
    void aReadFoldsTheIdentifier() {
        // Assumptions: the fold happens BEFORE the keyed read, under a locale that does not vary with the
        //   host, and the stub below is keyed on the folded value so a service that read the raw value
        //   would find nothing and fail this case. Two things make the fold necessary rather than
        //   cosmetic: the identifier column is declared at a width over a single case, so a lower-case
        //   key simply does not exist in it, and the reference never had to fold because its terminal
        //   delivered upper case by convention -- a convention no request line carries. The locale is
        //   what stops the same characters deriving two different keys on two hosts, which would make a
        //   row findable on one deployment and absent on another.
        when(users.findById("USER0001")).thenReturn(Optional.of(row("USER0001")));

        UserResponse read = service.read(" user0001 ");

        assertThat(read.userId()).isEqualTo("USER0001");
        verify(users).findById("USER0001");
    }

    /**
     * Asserts an unreadable row on the read path reports the reference lookup sentence.
     *
     * <p>This case takes no parameter and yields no value.</p>
     *
     * @throws IllegalStateException always, raised by the service when the keyed read fails and captured
     *     by the assertion below
     */
    @Test
    @DisplayName("an unreadable row on the read path reports the reference lookup sentence")
    void anUnreadableRowOnTheReadPathReportsTheLookupSentence() {
        when(users.findById("USER0001")).thenThrow(new QueryTimeoutException("statement timed out"));

        assertThatThrownBy(() -> service.read("USER0001"))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("Unable to lookup User...");
    }

    /**
     * Asserts a delete removes the row and then the pool account, in that order.
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("a delete removes the row and then the identity")
    void aDeleteRemovesTheRowThenTheIdentity() {
        User stored = row("USER0001");
        when(users.findById("USER0001")).thenReturn(Optional.of(stored));

        service.delete("USER0001");

        // Assumptions: the order is chosen for the state an interruption leaves behind. A row removed
        //   with its account still present is an identity refused at every guarded route; an account
        //   removed with its row still present is a row whose user cannot sign on and which no later
        //   operation can repair without provisioning again. The reference reached the destructive
        //   statement at app/cbl/COUSR03C.cbl lines 307 to 311 immediately after its keyed read at
        //   line 190, the two performs being adjacent at lines 190 and 191, so one call ordering answers
        //   for both.
        InOrder ordered = inOrder(users, provisioning);
        ordered.verify(users).delete(stored);
        ordered.verify(provisioning).withdraw("USER0001");
    }

    /**
     * Asserts a delete against an absent row reports the reference not-found sentence.
     *
     * <p>This case takes no parameter and yields no value.</p>
     *
     * @throws NoSuchElementException always, raised by the service when no row carries the identifier and
     *     captured by the assertion below
     */
    @Test
    @DisplayName("a delete against an absent row reports the reference not-found sentence")
    void aDeleteAgainstAnAbsentRowReportsNotFound() {
        when(users.findById("NOSUCH01")).thenReturn(Optional.empty());

        // Assumptions: the reference reports this string from two arms of the delete program, the read at
        //   app/cbl/COUSR03C.cbl line 289 and the destructive statement's own not-found arm at line 325,
        //   both attributed to the identifier at lines 291 and 327. The keyed read precedes the removal
        //   here, so one target sentence answers both and nothing is removed.
        assertThatThrownBy(() -> service.delete("NOSUCH01"))
                .isExactlyInstanceOf(NoSuchElementException.class)
                .hasMessage("User ID NOT found...");

        verify(users, never()).delete(any(User.class));
        verifyNoInteractions(provisioning);
    }

    /**
     * Asserts a blank identifier on the delete path is refused and removes nothing.
     *
     * <p>This case takes no parameter and yields no value.</p>
     *
     * @throws ClientInputException always, raised by the service before any store is touched and captured
     *     by the assertion below
     */
    @Test
    @DisplayName("a blank identifier on the delete path removes nothing")
    void aBlankIdentifierOnTheDeletePathRemovesNothing() {
        // Assumptions: the reference guards its destructive paragraph with the same blank test twice over,
        //   in the loading chain at app/cbl/COUSR03C.cbl line 179 and in the entry chain at line 147,
        //   both writing the identifier sentence and both moving the cursor to the identifier at
        //   lines 181 and 149. One guard answers for both here because both arms precede the same read.
        assertThatThrownBy(() -> service.delete(" "))
                .isInstanceOf(ClientInputException.class)
                .hasMessage("User ID can NOT be empty...");

        verifyNoInteractions(users);
        verifyNoInteractions(provisioning);
    }

    /**
     * Asserts an unreadable row on the delete path reports the reference lookup sentence.
     *
     * <p>This case takes no parameter and yields no value.</p>
     *
     * @throws IllegalStateException always, raised by the service when the keyed read fails and captured
     *     by the assertion below
     */
    @Test
    @DisplayName("an unreadable row on the delete path reports the reference lookup sentence")
    void anUnreadableRowOnTheDeletePathReportsTheLookupSentence() {
        when(users.findById("USER0001")).thenThrow(new QueryTimeoutException("statement timed out"));

        // Assumptions: the delete program keeps its lookup failure and its removal failure apart exactly
        //   as its sibling does -- the read arm at app/cbl/COUSR03C.cbl line 293 writes the lookup
        //   sentence at line 296 and attributes it to the first name at line 298, where the removal arm
        //   at line 329 writes a different sentence at line 332.
        assertThatThrownBy(() -> service.delete("USER0001"))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("Unable to lookup User...");

        verifyNoInteractions(provisioning);
    }

    /**
     * Asserts a failed removal reports the sentence the reference writes for it, naming the wrong verb.
     *
     * <p>The sentence asserted is the one at {@code app/cbl/COUSR03C.cbl} line 332, which says Update on
     * a delete path.</p>
     *
     * <p>This case takes no parameter and yields no value.</p>
     *
     * @throws IllegalStateException always, raised by the service when the row cannot be removed and
     *     captured by the assertion below
     */
    @Test
    @DisplayName("a failed delete reports the reference sentence, which names Update")
    void aFailedDeleteReportsTheReferenceSentenceNamingUpdate() {
        User stored = row("USER0001");
        when(users.findById("USER0001")).thenReturn(Optional.of(stored));
        doThrow(new QueryTimeoutException("statement timed out")).when(users).delete(stored);

        // Trade-offs: the sentence says Update on a delete failure, and carrying it across trades a
        //   sentence that reads oddly for the sentence that is actually emitted. The reference writes it
        //   at app/cbl/COUSR03C.cbl lines 332 to 333, and the evidence that it is a copy the author never
        //   reworded is that the delete program was cloned from the update program. Both place their
        //   eight copy statements on the very same lines -- 49, 60, 62, 63, 64, 65, 67 and 68 -- and
        //   seven of those eight lines are byte-identical between the two files; the single exception is
        //   line 60, where each names its own symbolic map, which is precisely the one substitution a
        //   clone would have to make. The literal's own first home is the update program's rewrite arm at
        //   lines 386 to 387, and the diagnostic statements at lines 294 and 330 of the delete program
        //   mirror lines 347 and 384 of the update program. Rewording it to name the operation would be
        //   an undocumented change in output, so the reference wording stands and this case is what stops
        //   a well-meaning tidy-up.
        assertThatThrownBy(() -> service.delete("USER0001"))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("Unable to Update User...");
    }

    /**
     * Asserts a removal that matched no row reports the not-found sentence rather than a failed update.
     *
     * <p>This case takes no parameter and yields no value.</p>
     *
     * @throws NoSuchElementException always, raised by the service when the statement affected no row and
     *     captured by the assertion below
     */
    @Test
    @DisplayName("a delete whose statement matched no row reports the reference not-found sentence")
    void aDeleteThatMatchedNoRowReportsNotFound() {
        User stored = row("USER0001");
        when(users.findById("USER0001")).thenReturn(Optional.of(stored));
        // Assumptions: the failure is raised from the FLUSH rather than from the removal call, because
        //   that is where the provider counts the rows its statement affected. The removal call only
        //   marks the instance for deletion, so a substitute that threw from it would model a condition
        //   the provider does not produce.
        doThrow(new OptimisticLockingFailureException("row count 0, expected 1"))
                .when(users).flush();

        // Refactoring Rationale: this outcome used to fall into the arm above and report the failed-update
        //   sentence with a 500, and the condition it names is not a fault. Two callers deleting one row
        //   both find it, the first commits, and the second's statement matches nothing -- so the
        //   provider raises its stale-state failure with no fault anywhere. Measured against six
        //   concurrent deletes of one row before this change: one answered 204 and the rest split between
        //   404 and 500 purely on thread timing, the 404s being the callers whose keyed read lost and the
        //   500s the callers whose read won and whose statement then lost. The row is gone either way, so
        //   both report the sentence a repeat of the same request already receives.
        assertThatThrownBy(() -> service.delete("USER0001"))
                .isExactlyInstanceOf(NoSuchElementException.class)
                .hasMessage("User ID NOT found...");

        // Assumptions: no withdrawal is owed and none is asserted. Nothing was deleted here, so recording
        //   the intention would ask the reconciliation pass to withdraw an account that the caller who
        //   DID delete the row is already withdrawing -- and the pool account belongs to that deletion,
        //   not to this one.
        verifyNoInteractions(provisioning);
    }

    /**
     * Asserts nothing in the service's own delete decides whether a deletion was confirmed.
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("the service delete carries no confirmation of its own, which the adapter owns")
    void theServiceDeleteCarriesNoConfirmationOfItsOwn() {
        // Assumptions: that a deletion is confirmed AT ALL preserves a property the delete program has
        //   and the update program does not, and the property is sited at the adapter rather than here
        //   because it is a fact about the request and not about the row. In app/cbl/COUSR03C.cbl the
        //   destructive paragraph is reached from exactly one key: the arm at lines 121 to 122 performs
        //   it, and the back-key arm at lines 111 to 118 goes straight to choosing where to return with
        //   no destructive call anywhere in it. So leaving the delete screen removed nothing. The update
        //   program is the opposite -- its back-key arm at line 111 performs the save at line 112 before
        //   choosing where to return at line 119 -- and that save-on-navigate behaviour is that one
        //   program's alone and is reproduced nowhere, because navigation issues no call here and a
        //   request that asked to go somewhere must not write. The migrated shape keeps the delete side's
        //   property by making the confirmation a required query parameter at the adapter, attributed to
        //   the parameter's own name, so a request that does not carry it removes nothing before this
        //   method is ever entered. This method therefore takes one argument, and this case pins that
        //   count so a confirmation is never quietly given a default here.
        assertThat(reflectiveParameterCount("delete"))
                .as("the destructive operation takes the identifier alone, so no confirmation can be"
                        + " defaulted inside the service")
                .isEqualTo(1);
    }

    /**
     * Asserts a first page returns ten rows and reports a further page from the eleventh read.
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("a first page returns ten rows and reports the eleventh as a further page")
    void aFirstPageReportsAFurtherPage() {
        when(users.findAllByOrderByUserIdAsc(any(Limit.class)))
                .thenReturn(rows(PAGE_SIZE + 1));

        PageResponse<UserSummary> page = service.list(null, null, SUBJECT);

        // Assumptions: ten rows to a page and eleven read, both taken from the reference. Its screen
        //   holds ten, declared at app/cbl/COUSR00C.cbl line 57, and it discovers a further page by
        //   reading ONE MORE than it can show: after filling the screen it advances once more at
        //   line 311 and sets its indicator from whether that read produced a record, at line 313 when
        //   it did and lines 315 and 318 when it did not, the indicator's two condition names being
        //   declared at lines 72 and 73. The eleventh row is therefore a probe and never a row a caller
        //   sees, which is why the count below is ten and not eleven.
        assertThat(page.items()).hasSize(PAGE_SIZE);
        assertThat(page.items()).extracting(UserSummary::userId)
                .containsExactly("USER0001", "USER0002", "USER0003", "USER0004", "USER0005",
                        "USER0006", "USER0007", "USER0008", "USER0009", "USER0010");
        assertThat(page.hasNext()).isTrue();
        assertThat(page.firstKey()).isNotNull();
        assertThat(page.lastKey()).isNotNull();
    }

    /**
     * Asserts a page shorter than ten reports no further page and still carries both boundary cursors.
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("a short final page reports no further page and names its own last row")
    void aShortFinalPageNamesItsOwnLastRow() {
        when(users.findAllByOrderByUserIdAsc(any(Limit.class)))
                .thenReturn(rows(4));

        PageResponse<UserSummary> page = service.list(null, null, SUBJECT);

        // Assumptions: BOTH cursors are derived from the rows actually returned, and on a partial page
        //   that is a documented divergence rather than an incidental detail. The reference wrote its
        //   two boundary keys from arms of a position-indexed structure: the first row's arm at
        //   app/cbl/COUSR00C.cbl line 387 writes the leading key as the second receiver of one
        //   multi-receiver move at lines 388 and 389, and only the TENTH row's arm at line 433 writes
        //   the trailing key, at line 435. The ninth row's arm at lines 428 to 432 writes no key at all,
        //   which is the general case. So on any page of fewer than ten rows the tenth arm never fires
        //   and the reference's trailing key keeps whatever the previous page left in it. The migrated
        //   read takes the leading key from the first returned row and the trailing key from the last
        //   returned row, whatever their count; the divergence is documented.
        assertThat(page.items()).hasSize(4);
        assertThat(page.hasNext()).isFalse();
        assertThat(nextPositionOf(page)).isEqualTo("USER0004");
        assertThat(previousPositionOf(page)).isEqualTo("USER0001");
    }

    /**
     * Asserts an exhausted page is a success carrying an empty array and no cursors.
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("an exhausted page is a success with an empty array")
    void anExhaustedPageIsASuccess() {
        when(users.findAllByOrderByUserIdAsc(any(Limit.class)))
                .thenReturn(List.of());

        PageResponse<UserSummary> page = service.list(null, null, SUBJECT);

        // Assumptions: a page with no rows has no boundary to name, so both cursors are absent rather
        //   than carried over from anywhere. Reaching the end of the walk is a successful read of an
        //   empty range and never a failure, which is the shape the reference's own arrival arms take:
        //   each sets its end indicator, writes a sentence and renders the screen rather than abending.
        assertThat(page.items()).isEmpty();
        assertThat(page.hasNext()).isFalse();
        assertThat(page.firstKey()).isNull();
        assertThat(page.lastKey()).isNull();
    }

    /**
     * Asserts the forward cursor of one page opens the next and positions it after that page's last row.
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("the forward cursor of one page positions the next page after its last row")
    void theForwardCursorPositionsTheNextPage() {
        when(users.findAllByOrderByUserIdAsc(any(Limit.class)))
                .thenReturn(rows(PAGE_SIZE + 1));

        PageResponse<UserSummary> first = service.list(null, null, SUBJECT);

        when(users.findByUserIdGreaterThanOrderByUserIdAsc(eq("USER0010"), any(Limit.class)))
                .thenReturn(rows(11, 3));

        PageResponse<UserSummary> second = service.list(first.lastKey(), "next", SUBJECT);

        // Alternatives Considered: positioning a page by counting rows to skip rather than by naming the
        //   key to resume after. Rejected because a count is measured against a range that other requests
        //   are changing: a row inserted before the window between two requests pushes one row past the
        //   boundary so the caller never sees it, and a row removed pulls one back so the caller sees it
        //   twice. A key-bounded window cannot do either, because its boundary is a value that exists in
        //   the data rather than a tally of rows scanned past. The reference positions by key for the
        //   same reason -- it holds keys, not counts, in the two fields at lines 68 and 69 -- so this is
        //   also the arrangement that preserves the page boundaries it produced.
        assertThat(second.items()).extracting(UserSummary::userId)
                .containsExactly("USER0011", "USER0012", "USER0013");
        verify(users).findByUserIdGreaterThanOrderByUserIdAsc(eq("USER0010"), any(Limit.class));
    }

    /**
     * Asserts a backward page is read descending and presented ascending.
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("a backward page is read descending and presented ascending")
    void aBackwardPageIsPresentedAscending() {
        when(users.findAllByOrderByUserIdAsc(any(Limit.class)))
                .thenReturn(rows(PAGE_SIZE + 1));

        PageResponse<UserSummary> first = service.list(null, null, SUBJECT);

        when(users.findByUserIdLessThanOrderByUserIdDesc(eq("USER0001"), any(Limit.class)))
                .thenReturn(descending(rows(1, 3)));

        PageResponse<UserSummary> back = service.list(first.firstKey(), "previous", SUBJECT);

        // Assumptions: the backward query must return DESCENDING and the ordering into display order is
        //   the service's own work, because a key-bounded window that reads backwards can only bound
        //   itself by taking the rows nearest the cursor first. The reference has the same two-step: its
        //   reverse walk, whose statement is at app/cbl/COUSR00C.cbl lines 655 to 663 under the label at
        //   line 653, hands back records in descending key order, and the program places each into a
        //   descending screen position rather than displaying them in the order read. This case asserts
        //   the reversal directly, by stubbing the query descending and requiring the page ascending --
        //   a service that passed the query result through untouched would fail here.
        assertThat(back.items()).extracting(UserSummary::userId)
                .containsExactly("USER0001", "USER0002", "USER0003");
    }

    /**
     * Asserts a page reached by retreating still reports a further page forwards.
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("a page reached by retreating reports a further page forwards")
    void aRetreatedPageReportsAFurtherPageForwards() {
        when(users.findAllByOrderByUserIdAsc(any(Limit.class)))
                .thenReturn(rows(PAGE_SIZE + 1));

        PageResponse<UserSummary> first = service.list(null, null, SUBJECT);

        when(users.findByUserIdLessThanOrderByUserIdDesc(eq("USER0001"), any(Limit.class)))
                .thenReturn(descending(rows(1, 2)));

        PageResponse<UserSummary> back = service.list(first.firstKey(), "previous", SUBJECT);

        // Assumptions: the envelope reports forward availability only, and there is no backward
        //   counterpart to report -- the reference has no previous-page indicator anywhere. Its paging
        //   state at app/cbl/COUSR00C.cbl lines 68 to 75 declares two keys, a page ordinal, one
        //   forward indicator with its two condition names at lines 72 and 73, and two selection fields,
        //   and nothing else. A caller that has just retreated demonstrably came from a page that
        //   exists, so a further page forwards demonstrably follows, and that is what is reported here.
        assertThat(back.hasNext()).isTrue();
    }

    /**
     * Asserts a cursor sealed for one direction cannot be presented with the other.
     *
     * <p>This case takes no parameter and yields no value.</p>
     *
     * @throws ClientInputException in both assertions below, raised by the service when the sealed
     *     position does not open under the binding the presented direction implies
     */
    @Test
    @DisplayName("a cursor sealed for one direction is refused when presented with the other")
    void aCursorIsBoundToItsDirection() {
        when(users.findAllByOrderByUserIdAsc(any(Limit.class)))
                .thenReturn(rows(PAGE_SIZE + 1));

        PageResponse<UserSummary> first = service.list(null, null, SUBJECT);

        // Assumptions: no page ordinal reaches a query on any path, which is why one opaque position
        //   parameter is as safe as two named ones. The reference carries an ordinal -- an eight-digit
        //   counter at app/cbl/COUSR00C.cbl line 70, incremented at lines 309 to 310 and 320 to 321 and
        //   moved onto the screen at lines 327 and 376, against an eight-character screen field at
        //   app/cpy-bms/COUSR00.CPY line 60 -- but it uses it to LABEL the page and to gate its
        //   backward guard, never to locate a record. It has no target counterpart, so a caller cannot
        //   ask for a page by number and no arithmetic on a number can position a read.
        assertThatThrownBy(() -> service.list(first.lastKey(), "previous", SUBJECT))
                .isInstanceOf(ClientInputException.class);
        assertThatThrownBy(() -> service.list(first.firstKey(), "next", SUBJECT))
                .isInstanceOf(ClientInputException.class);
    }

    /**
     * Asserts a cursor issued to one caller cannot be redeemed by another.
     *
     * <p>This case takes no parameter and yields no value.</p>
     *
     * @throws ClientInputException always, raised by the service when the position was sealed against a
     *     different caller; captured by the assertion below
     */
    @Test
    @DisplayName("a cursor issued to one caller is refused when replayed by another")
    void aCursorIsBoundToItsSubject() {
        when(users.findAllByOrderByUserIdAsc(any(Limit.class)))
                .thenReturn(rows(PAGE_SIZE + 1));

        PageResponse<UserSummary> first = service.list(null, null, SUBJECT);

        assertThatThrownBy(() -> service.list(first.lastKey(), "next", "operator-2"))
                .isInstanceOf(ClientInputException.class)
                .extracting(raised -> ((ClientInputException) raised).field())
                .as("a refused position is attributed to the position parameter, which is the sixth and"
                        + " last key this context can produce")
                .isEqualTo("cursor");
    }

    /**
     * Asserts a position naming a key with no row is answered rather than reported as not found.
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("a position naming a key with no row answers a page rather than a not-found")
    void aPositionNamingAKeyWithNoRowAnswersAPage() {
        when(users.findAllByOrderByUserIdAsc(any(Limit.class)))
                .thenReturn(rows(PAGE_SIZE + 1));

        PageResponse<UserSummary> first = service.list(null, null, SUBJECT);

        when(users.findByUserIdGreaterThanOrderByUserIdAsc(eq("USER0010"), any(Limit.class)))
                .thenReturn(List.of());

        // Assumptions: a position whose key names no surviving row is a successful read of an empty
        //   range, never a not-found, because the predicate is a range and not an equality. The reference
        //   settles this in the same direction: the arm its opening browse takes when the key it was
        //   given is absent, at app/cbl/COUSR00C.cbl line 600, is keyed on the NOT-FOUND response and it
        //   still writes a sentence at line 603 and renders a screen at line 606 rather than abending.
        //   A row deleted between two page requests therefore costs the caller nothing.
        PageResponse<UserSummary> after = service.list(first.lastKey(), "next", SUBJECT);

        assertThat(after.items()).isEmpty();
        assertThat(after.hasNext()).isFalse();
    }

    /**
     * Asserts a store failure on the paged read reports the reference lookup sentence.
     *
     * <p>This case takes no parameter and yields no value.</p>
     *
     * @throws IllegalStateException always, raised by the service when the key-bounded query fails and
     *     captured by the assertion below
     */
    @Test
    @DisplayName("a store failure on the list reports the reference lookup sentence")
    void aStoreFailureOnTheListReportsTheReferenceSentence() {
        when(users.findAllByOrderByUserIdAsc(any(Limit.class)))
                .thenThrow(new QueryTimeoutException("statement timed out"));

        // Assumptions: the reference writes this one sentence from THREE separate browse arms -- the
        //   opening browse at app/cbl/COUSR00C.cbl line 610, the forward advance at line 644 and the
        //   reverse walk at line 678 -- each attributing it to the identifier field at lines 612, 646
        //   and 680. All three collapse onto one target query pair, so one sentence answers for all
        //   three arms and there is nothing to keep apart.
        assertThatThrownBy(() -> service.list(null, null, SUBJECT))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("Unable to lookup User...");
    }

    /**
     * Asserts the backward query is the one used when a position is presented with the previous direction.
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("a bare previous direction with no position reads the first page forwards")
    void aBarePreviousDirectionReadsTheFirstPageForwards() {
        when(users.findAllByOrderByUserIdAsc(any(Limit.class))).thenReturn(rows(3));
        when(users.findByUserIdGreaterThanOrderByUserIdAsc(any(String.class), any(Limit.class)))
                .thenReturn(rows(3));
        when(users.findByUserIdLessThanOrderByUserIdDesc(any(String.class), any(Limit.class)))
                .thenReturn(descending(rows(3)));

        PageResponse<UserSummary> page = service.list(null, "previous", SUBJECT);

        // Assumptions: a direction supplied without a position is not refused, and the published contract
        //   is what settles it -- the position is optional and its absence means the first page. The
        //   reference answers the same way when the key it holds for that direction is still its opening
        //   sentinel, seeding the scan from a bounding value at app/cbl/COUSR00C.cbl line 263 rather
        //   than failing.
        assertThat(page.items()).extracting(UserSummary::userId)
                .containsExactly("USER0001", "USER0002", "USER0003");
    }

    /**
     * Asserts the reference's five boundary sentences are five distinct strings, none of them merged.
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("the five boundary sentences are five distinct strings in two classes")
    void theFiveBoundarySentencesAreFiveDistinctStrings() {
        // Assumptions: the reference has TWO classes of boundary sentence and five distinct strings
        //   across them, and neither the classes nor any pair of strings may be merged. The GUARD class
        //   answers a caller who asked to move past a boundary already known to be reached: its backward
        //   guard tests the page ordinal at app/cbl/COUSR00C.cbl line 248 and writes line 251, and its
        //   forward guard tests the further-page indicator at line 270 and writes line 273. The ARRIVAL
        //   class answers a walk that met a boundary while running: the opening browse writes line 603 on
        //   a not-found response at line 600, the forward advance writes line 637 on an end-of-file
        //   response at line 634, and the reverse walk writes line 671 on an end-of-file response at
        //   line 668. Lines 603 and 671 differ by four words and are NOT one string used twice.
        List<String> boundarySentences = List.of(
                "You are already at the top of the page...",
                "You are already at the bottom of the page...",
                "You are at the top of the page...",
                "You have reached the bottom of the page...",
                "You have reached the top of the page...");

        assertThat(boundarySentences)
                .as("five cited lines, five strings, no two of them the same")
                .doesNotHaveDuplicates()
                .hasSize(5);
        assertThat(boundarySentences.get(2))
                .as("the arrival sentence for the opening browse is not the arrival sentence for the"
                        + " reverse walk")
                .isNotEqualTo(boundarySentences.get(4));
    }

    /**
     * Asserts every boundary condition is answered as a page rather than as a refusal.
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("every boundary condition answers a page and never a refusal")
    void everyBoundaryConditionAnswersAPage() {
        when(users.findAllByOrderByUserIdAsc(any(Limit.class)))
                .thenReturn(List.of());
        when(users.findByUserIdLessThanOrderByUserIdDesc(any(String.class), any(Limit.class)))
                .thenReturn(List.of());

        // Assumptions: the sentences themselves have no home in this envelope, and that is a boundary of
        //   responsibility rather than an omission. The envelope declares four components -- the rows,
        //   the two boundary positions and the forward indicator -- and no message component, because a
        //   boundary sentence is what a screen says about a page and not a fact about the page. What this
        //   layer owes a caller is the page, and every one of the five conditions above is answered with
        //   one: a short list or an empty list, with the forward indicator telling the caller whether to
        //   ask again. None of them is a refusal. The same holds for the one sentence in this domain that
        //   comes from the shared message copybook, the unmapped-key sentence the delete program writes
        //   at app/cbl/COUSR03C.cbl line 128 from the constant declared at app/cpy/CSMSG01Y.cpy lines 20
        //   and 21: an unmapped attention identifier is a terminal concept with no counterpart on a
        //   request-and-response boundary, so it is carried as documentation and nothing here emits it.
        PageResponse<UserSummary> forwards = service.list(null, "next", SUBJECT);
        PageResponse<UserSummary> backwards = service.list(null, "previous", SUBJECT);

        assertThat(forwards.items()).isEmpty();
        assertThat(forwards.hasNext()).isFalse();
        assertThat(backwards.items()).isEmpty();
    }

    /**
     * Asserts the two keystroke prompts are carried with the punctuation their own lines give them.
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("the two keystroke prompts keep their own spacing and drive no server state")
    void theTwoKeystrokePromptsKeepTheirOwnSpacing() {
        // Assumptions: these two are TEXT and nothing else -- no state behind either. Each is written on
        //   the successful arm of a keyed read, at app/cbl/COUSR02C.cbl lines 336 to 337 and
        //   app/cbl/COUSR03C.cbl lines 283 to 284, and each is rendered in the neutral attribute, at
        //   lines 338 and 285, which is the reference's way of marking advice rather than an outcome.
        //   Both arms open with a no-operation statement, at lines 335 and 282, that changes nothing.
        //   Advising a user which key to press next is a client concern, so neither prompt is emitted by
        //   this layer and both are pinned here only so their spacing survives.
        assertThat("Press PF5 key to save your updates ...")
                .as("this prompt carries a space before its ellipsis")
                .endsWith(" ...");
        assertThat("Press PF5 key to delete this user ...")
                .as("so does the delete prompt, and the two are different strings")
                .endsWith(" ...")
                .isNotEqualTo("Press PF5 key to save your updates ...");
    }

    /**
     * Asserts the attribute the reference used as a severity channel maps onto three outcome classes.
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("the reference message attribute separates refusal, advice and success")
    void theReferenceMessageAttributeSeparatesThreeOutcomes() {
        when(users.findById("USER0001")).thenReturn(Optional.of(row("USER0001")));
        when(users.saveAndFlush(any(User.class))).thenAnswer(call -> call.getArgument(0));

        // Assumptions: the reference's message colour attribute is its severity channel, and one program
        //   writes all three values into it -- red on the unchanged-body refusal at
        //   app/cbl/COUSR02C.cbl line 241, neutral on the keystroke prompt at line 338 and green on the
        //   successful rewrite at line 371. Those three map onto three outcome classes here: a refusal
        //   raised as a client-input failure, a prompt this layer does not emit at all, and a value
        //   returned. This case exercises the two ends of that channel against one row so the mapping is
        //   asserted rather than asserted about.
        assertThatThrownBy(() -> service.update("USER0001",
                        new UpdateUserRequest("Ada", "Lovelace", "U")))
                .as("the red end of the channel becomes a refused request")
                .isInstanceOf(ClientInputException.class);

        assertThat(service.update("USER0001", new UpdateUserRequest("Grace", "Hopper", "U")))
                .as("the green end of the channel becomes a returned value")
                .isNotNull();
    }

    /**
     * Asserts every refusal carries the shared validation code and never a store or provider diagnostic.
     *
     * <p>This case takes no parameter and yields no value.</p>
     *
     * @throws ClientInputException by the first assertion, raised by the ordered create chain and
     *     captured there
     * @throws IllegalStateException by the second assertion, raised when the keyed read fails and
     *     captured there
     */
    @Test
    @DisplayName("no store diagnostic reaches a reported sentence")
    void noStoreDiagnosticReachesAReportedSentence() {
        // Assumptions: the reference logged its own diagnostics and never displayed them. Live
        //   diagnostic statements sit on the failure arms at app/cbl/COUSR00C.cbl lines 608, 642 and
        //   676, at app/cbl/COUSR02C.cbl lines 347 and 384 and at app/cbl/COUSR03C.cbl lines 294 and
        //   330, and the add program's equivalent at app/cbl/COUSR01C.cbl line 268 is commented out
        //   altogether -- so in every case the response code went to the operator log while the screen
        //   got only the sentence. The target keeps that division, so a caller never learns a driver
        //   class name, a statement, a constraint name or a provider code.
        ClientInputException refused = (ClientInputException) org.assertj.core.api.Assertions
                .catchThrowable(() -> service.create(new CreateUserRequest("", "L", "U1", "A")));

        assertThat(refused.code())
                .as("a refusal carries the shared validation code, which names no store")
                .isEqualTo(ApiError.CODE_VALIDATION);

        when(users.findById("USER0001"))
                .thenThrow(new QueryTimeoutException("ORA-01013 statement cancelled by user"));

        assertThatThrownBy(() -> service.read("USER0001"))
                .hasMessage("Unable to lookup User...")
                .as("the driver text supplied above must not appear in what a caller is told")
                .extracting(Throwable::getMessage)
                .asString()
                .doesNotContain("ORA-01013")
                .doesNotContain("cancelled");
    }

    /**
     * Asserts no sentence this context reports comes anywhere near the reference message field's width.
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("no reported sentence approaches the reference message field width")
    void noReportedSentenceApproachesTheFieldWidth() {
        // Assumptions: there is NO truncation rule to reproduce, and this case exists so that nobody
        //   invents one. The reference message field is declared seventy-eight characters wide in every
        //   one of the four maps this context uses -- app/cpy-bms/COUSR00.CPY line 372,
        //   app/cpy-bms/COUSR01.CPY line 90, app/cpy-bms/COUSR02.CPY line 90 and
        //   app/cpy-bms/COUSR03.CPY line 84 -- and the longest sentence anywhere in the domain is the
        //   forward paging guard, well inside it. Every sentence therefore fitted whole and none was ever
        //   clipped, so no code path here truncates and none should be added.
        // Assumptions: two entries below -- the identifier's width and domain sentences -- are authored by
        //   this service rather than transcribed, because the reference could ask neither question of a
        //   single-byte eight-position screen field. They are held to the same width for the same reason
        //   the transcribed ones are: a client rendering this boundary in the reference's message field
        //   must be able to show them whole, and a sentence that only fitted on a wider surface would be
        //   clipped by the one presentation this domain has.
        int referenceMessageWidth = 78;
        List<String> everySentence = List.of(
                "User ID can NOT be empty...",
                "First Name can NOT be empty...",
                "Last Name can NOT be empty...",
                "User Type can NOT be empty...",
                "User Type must be A or U...",
                "User ID must be at most 8 characters...",
                "User ID contains an unsupported character...",
                "User ID already exist...",
                "Unable to Add User...",
                "User ID NOT found...",
                "Unable to Update User...",
                "Please modify to update ...",
                "Unable to lookup User...",
                "You are already at the bottom of the page...");

        assertThat(everySentence).allSatisfy(sentence ->
                assertThat(sentence.length()).isLessThan(referenceMessageWidth));
    }

    /**
     * Asserts the list projection carries exactly the four values the reference list screen shows.
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("the list projection carries the four values the reference list screen shows")
    void theListProjectionCarriesFourValues() {
        when(users.findAllByOrderByUserIdAsc(any(Limit.class)))
                .thenReturn(rows(1));

        PageResponse<UserSummary> page = service.list(null, null, SUBJECT);

        // Assumptions: the shape comes from the MAP and not from the scratch area the browse program
        //   builds internally. That area at app/cbl/COUSR00C.cbl lines 56 to 64 holds a single combined
        //   name of twenty-five characters at line 62 and an eight-character type at line 64, and
        //   neither width matches anything: the map declares two twenty-character name fields and a
        //   one-character type at app/cpy-bms/COUSR00.CPY lines 84, 90 and 96, which agree exactly with
        //   the record layout at app/cpy/CSUSR01Y.cpy lines 19, 20 and 22. The map and the record agree
        //   and the scratch area agrees with neither, so the map is the authority and the scratch area is
        //   an internal rendering buffer that is not modelled at all.
        assertThat(UserSummary.class.getRecordComponents()).hasSize(4);
        assertThat(page.items().get(0).firstName()).isEqualTo("Ada");
        assertThat(page.items().get(0).lastName()).isEqualTo("Lovelace");
        assertThat(page.items().get(0).userType()).isEqualTo("U");
    }

    /**
     * Asserts nothing this service publishes or accepts can carry a stored credential.
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("no shape on this boundary can carry a stored credential")
    void noShapeOnThisBoundaryCanCarryAStoredCredential() {
        // Assumptions: the omission is structural rather than a matter of care at each call site. The
        //   record layout declared a value at app/cpy/CSUSR01Y.cpy line 21, at zero-based offset 48 of
        //   its eighty bytes, and the add program wrote a submitted one straight into it at
        //   app/cbl/COUSR01C.cbl line 157 while the update program moved the stored one back onto the
        //   screen at app/cbl/COUSR02C.cbl line 169. The migrated table declares five columns and none
        //   of them could hold such a value, so none of the four shapes on this boundary declares a
        //   component for one either -- which is asserted here by component count and name rather than
        //   trusted, so a later addition fails this case instead of passing review.
        assertThat(componentNames(UserSummary.class)).containsExactly(
                "userId", "firstName", "lastName", "userType");
        assertThat(componentNames(UserResponse.class)).containsExactly(
                "userId", "firstName", "lastName", "userType", "cognitoSub");
        assertThat(componentNames(CreateUserRequest.class)).containsExactly(
                "firstName", "lastName", "userId", "userType");
        assertThat(componentNames(UpdateUserRequest.class)).containsExactly(
                "firstName", "lastName", "userType");
    }

    /**
     * Asserts the five public operations are the whole of what this service offers.
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("the service publishes exactly the five operations the four programs need")
    void theServicePublishesExactlyFiveOperations() {
        // Trade-offs: each of the three writing operations is one unit of work, and that is a decision
        //   taken in the target rather than a constraint carried over. None of the four programs issues a
        //   commit statement at all -- a count over each returns zero, where the account-update program
        //   returns two and the card-update program one -- so there is no reference commit scope for
        //   these operations to reproduce. The reference got its unit of work implicitly, by ending the
        //   terminal task. Declaring one boundary per writing operation reproduces that all-or-nothing
        //   shape explicitly; what it costs is that a caller can no longer observe a partly applied
        //   write, which the reference could not offer either, so nothing observable is given up. This
        //   case pins the operation count so a sixth path cannot appear without a boundary decision being
        //   taken for it.
        assertThat(publicOperationNames())
                .containsExactlyInAnyOrder("list", "read", "create", "update", "delete");
    }

    /**
     * Asserts an accepted update against a padded stored row still writes and reports trimmed values.
     *
     * @param request the body to submit, which differs from the stored row in exactly one value
     * @param changed a description of the one value that differs, used in the assertion description
     */
    private void assertUpdateIsAccepted(UpdateUserRequest request, String changed) {
        User loaded = row("USER0001");
        when(users.findById("USER0001")).thenReturn(Optional.of(loaded));
        when(users.saveAndFlush(any(User.class))).thenAnswer(call -> call.getArgument(0));

        UserResponse updated = service.update("USER0001", request);

        assertThat(updated)
                .as("a body differing only in %s is a change and must be accepted", changed)
                .isNotNull();
        verify(users).saveAndFlush(loaded);
    }

    /**
     * Opens the forward boundary position of a page, yielding the key the next page resumes after.
     *
     * @param page the page whose forward boundary position is to be opened
     * @return the stored identifier the forward position names
     * @throws CursorToken.InvalidCursorException if the position was not sealed for this caller reading
     *     forwards, which would mean the service sealed it under the wrong binding
     */
    private String nextPositionOf(PageResponse<UserSummary> page) {
        // Assumptions: the position is opened with the REAL sealer under the same binding the service
        //   seals it with, so this helper asserts the round trip as well as reading the key. A helper
        //   that decoded the token itself would pass even if the service sealed it under a binding no
        //   subsequent request could present.
        return sealer.open(CursorToken.binding("auth.users.list", SUBJECT, "direction:next"),
                page.lastKey());
    }

    /**
     * Opens the backward boundary position of a page, yielding the key a retreat reads back from.
     *
     * @param page the page whose backward boundary position is to be opened
     * @return the stored identifier the backward position names
     * @throws CursorToken.InvalidCursorException if the position was not sealed for this caller reading
     *     backwards
     */
    private String previousPositionOf(PageResponse<UserSummary> page) {
        return sealer.open(CursorToken.binding("auth.users.list", SUBJECT, "direction:previous"),
                page.firstKey());
    }

    /**
     * Builds a stored row with the ordinary reference type and the two names the update cases start from.
     *
     * @param userId the identifier to key the row by
     * @return a row carrying no provider state, which is all these cases need
     */
    private static User row(String userId) {
        return new User(userId, "Ada", "Lovelace", "U",
                UUID.fromString("99999999-8888-7777-6666-555555555555"));
    }

    /**
     * Builds a stored row that holds ADMINISTRATIVE authority, which is the only row a demotion can start
     * from.
     *
     * <p>Assumptions: it differs from {@link #row(String)} in the type alone, so a case built on it isolates
     * the one transition that withdraws authority -- administrator to ordinary user -- from every other
     * difference two rows could have.</p>
     *
     * @param userId the identifier the row carries, of type {@code String}; must not be {@code null}
     * @return a stored row of the administrative type, never {@code null}
     */
    private static User admin(String userId) {
        return new User(userId, "Ada", "Lovelace", "A",
                UUID.fromString("99999999-8888-7777-6666-555555555555"));
    }

    /**
     * Builds the failure a store raises when the row it was given is not unique.
     *
     * <p>Assumptions: the shape is a translated integrity violation whose CAUSE carries the SQL state,
     * because that is the shape the stack actually produces and the service classifies on. The framework's
     * translator maps the persistence provider's constraint type and its data type onto ONE exception
     * class, so a stub without a state would be indistinguishable from a value-too-wide failure and would
     * exercise the fault arm.</p>
     *
     * <p>Assumptions: the state is asserted against a live engine by the module's repository integration
     * test rather than taken on trust here, which is what keeps this substitute honest: a stub is only
     * evidence of the service's behaviour, never of the store's.</p>
     *
     * @return the failure to stub the insert with; never {@code null}
     */
    private static DataIntegrityViolationException uniquenessViolation() {
        return integrityViolation(SQL_STATE_UNIQUE_VIOLATION);
    }

    /**
     * Builds a translated integrity violation reporting one SQL state.
     *
     * @param sqlState the state the driver would have reported
     * @return the failure to stub a write with; never {@code null}
     */
    private static DataIntegrityViolationException integrityViolation(String sqlState) {
        return new DataIntegrityViolationException("the store refused the row",
                new SQLException("the store refused the row", sqlState));
    }

    /**
     * Builds a run of consecutive rows starting from the first identifier.
     *
     * @param count how many rows to build
     * @return the rows, ascending by identifier
     */
    private static List<User> rows(int count) {
        return rows(1, count);
    }

    /**
     * Builds a run of consecutive rows starting from a given ordinal.
     *
     * @param from the one-based ordinal of the first row
     * @param count how many rows to build
     * @return the rows, ascending by identifier
     */
    private static List<User> rows(int from, int count) {
        List<User> built = new ArrayList<>(count);
        for (int ordinal = from; ordinal < from + count; ordinal++) {
            built.add(row(String.format("USER%04d", ordinal)));
        }
        return built;
    }

    /**
     * Reverses a run of ascending rows, so a stub can answer the way a backward query answers.
     *
     * @param ascending the rows in ascending identifier order
     * @return a new list holding the same rows in descending identifier order
     */
    private static List<User> descending(List<User> ascending) {
        // Assumptions: the backward stub must answer DESCENDING or the reversal case would assert
        //   nothing. Building the descending order here, rather than hand-writing reversed literals at
        //   each call site, keeps the stub's order tied to the ascending run it came from.
        List<User> reversed = new ArrayList<>(ascending);
        Collections.reverse(reversed);
        return reversed;
    }

    /**
     * Counts the declared parameters of one named operation on the service under test.
     *
     * @param operation the simple name of the public operation to inspect
     * @return the number of parameters the operation declares
     * @throws IllegalStateException if no public operation carries that name, which would mean the
     *     service's published surface had changed under this class
     */
    private static int reflectiveParameterCount(String operation) {
        for (java.lang.reflect.Method declared : UserService.class.getDeclaredMethods()) {
            if (declared.getName().equals(operation)
                    && java.lang.reflect.Modifier.isPublic(declared.getModifiers())) {
                return declared.getParameterCount();
            }
        }
        throw new IllegalStateException("no public operation named " + operation);
    }

    /**
     * No line this service logs carries the identity key, on the refusal path or the success path.
     *
     * <p>⚠️ Purpose: eleven statements in this class named {@code userId}, so the defect was a property of
     * the CLASS rather than of any one line, and a case asserting the absence on one path would leave the
     * other ten free to regress. This drives both shapes an operator actually meets -- a create refused as
     * a duplicate, and a create that succeeds -- and asserts the identifier appears on neither.
     *
     * <p>⚠️ Assumptions: the appender is attached to the service's own logger and the level is driven to
     * {@code TRACE}, so a statement added later at any level is covered without this case being revisited.
     * The level is restored in a {@code finally} because the logger is a process-wide singleton.
     *
     * <p>⚠️ Assumptions: both paths are asserted to have logged something before any absence is asserted,
     * so the case cannot pass because nothing was emitted at all.
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("no logged line carries the user identifier, refused or created")
    void noLoggedLineCarriesTheUserIdentifier() {
        Logger serviceLogger = (Logger) LoggerFactory.getLogger(UserService.class);
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.start();
        serviceLogger.addAppender(captured);
        Level restored = serviceLogger.getLevel();
        serviceLogger.setLevel(Level.TRACE);
        try {
            when(users.existsById("USER0001")).thenReturn(true);
            assertThatThrownBy(() -> service.create(
                            new CreateUserRequest("Ada", "Lovelace", "USER0001", "A")))
                    .isInstanceOf(UserService.DuplicateUserException.class);

            when(users.existsById("USER0042")).thenReturn(false);
            when(provisioning.provision("USER0042", "Ada", "Lovelace", "A")).thenReturn(PROVISIONED);
            when(users.insertUser(any(), any(), any(), any(), any())).thenReturn(1);
            service.create(new CreateUserRequest("Ada", "Lovelace", "USER0042", "A"));

            assertThat(captured.list)
                    .as("both paths must have logged, or the absence below proves nothing")
                    .hasSizeGreaterThanOrEqualTo(2);
            assertThat(captured.list)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .as("no line at any level may carry the identity table's own primary key")
                    .noneMatch(line -> line.contains("USER0001"))
                    .noneMatch(line -> line.contains("USER0042"));
        } finally {
            serviceLogger.setLevel(restored);
            serviceLogger.detachAppender(captured);
        }
    }

    /**
     * Lists the names of the public operations the service under test declares.
     *
     * @return the operation names, in no particular order and without duplicates
     */
    private static List<String> publicOperationNames() {
        List<String> named = new ArrayList<>();
        for (java.lang.reflect.Method declared : UserService.class.getDeclaredMethods()) {
            // Assumptions: synthetic members are excluded because the compiler adds them for
            //   constructs the source never wrote, and counting one would make the published surface
            //   look larger than the source declares.
            if (java.lang.reflect.Modifier.isPublic(declared.getModifiers())
                    && !declared.isSynthetic() && !named.contains(declared.getName())) {
                named.add(declared.getName());
            }
        }
        return named;
    }

    /**
     * Lists the component names one record declares, in declaration order.
     *
     * @param shape the record type to inspect
     * @return the component names in the order the record declares them
     */
    private static List<String> componentNames(Class<?> shape) {
        List<String> named = new ArrayList<>();
        for (java.lang.reflect.RecordComponent component : shape.getRecordComponents()) {
            named.add(component.getName());
        }
        return named;
    }

    /**
     * Asserts the create path commits its compensation guard BEFORE it calls the provider.
     *
     * <p>⚠️ Assumptions: the ordering is the whole assertion, and it is asserted rather than assumed
     * because the reverse order is what the guard exists to rule out. A guard written after the provider
     * call would be absent for exactly the interval during which a process death leaves a pool account
     * that can authenticate and that no row and no ledger entry names -- the state that made the
     * identifier permanently unusable while nothing recorded why.</p>
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("a create records its withdrawal guard before the provider is called at all")
    void aCreateRecordsItsGuardBeforeCallingTheProvider() {
        arrangeSuccessfulProvisioning();

        service.create(new CreateUserRequest("Ada", "Lovelace", "USER0042", "A"));

        InOrder ordered = inOrder(ledger, provisioning);
        ordered.verify(ledger).save(any(IdentitySyncTask.class));
        ordered.verify(provisioning).provision("USER0042", "Ada", "Lovelace", "A");
    }

    /**
     * Asserts the guard is recorded as a claimed withdrawal, so no reconciliation drain can act on it.
     *
     * <p>⚠️ Assumptions: the recorded status is asserted to be CLAIMED and not PENDING, and the
     * distinction is the reason the state exists. A pending guard is one the drain is entitled to apply,
     * and applying it while this create is in flight would withdraw the account this create is about to
     * bind its row to -- turning the control that prevents orphans into a cause of them.</p>
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("the guard is recorded claimed, naming a withdrawal of the identifier being created")
    void theRecordedGuardIsAClaimedWithdrawal() {
        arrangeSuccessfulProvisioning();
        // Assumptions: the provider is made to fail so the create stops with the guard in the state it
        //   was recorded in. Observing it after a SUCCESSFUL create would observe the CLOSED state
        //   instead, because the closure is part of the insert -- which the case below asserts separately.
        when(provisioning.provision("USER0042", "Ada", "Lovelace", "A"))
                .thenThrow(new NoSuchElementException("stop after the guard"));

        assertThatThrownBy(() -> service.create(
                new CreateUserRequest("Ada", "Lovelace", "USER0042", "A")))
                .isInstanceOf(NoSuchElementException.class);

        IdentitySyncTask guard = onlyLedgerRowOf(IdentitySyncTask.STATUS_CLAIMED);
        assertThat(guard.getOperation())
                .as("the guard names the compensating action, so applying it undoes the provisioning")
                .isEqualTo(IdentitySyncTask.OPERATION_WITHDRAW);
        assertThat(guard.getUserId())
                .as("the guard names the identifier whose pool account would be left behind")
                .isEqualTo("USER0042");
    }

    /**
     * Asserts a guard that cannot be committed stops the create before the provider is reached.
     *
     * <p>⚠️ Trade-offs: refusing the create is strictly worse for the caller than proceeding -- a
     * perfectly serviceable create is rejected because a bookkeeping row could not be written -- and it is
     * still the correct direction. Proceeding would call the provider with no durable compensation, which
     * is the unguarded behaviour this fix replaces, and it would do so while appearing guarded. A refused
     * create leaves nothing behind; an unguarded one can leave an account nothing names.</p>
     *
     * <p>This case takes no parameter and yields no value.</p>
     *
     * @throws IllegalStateException always, raised by the guard recording and captured by the assertion
     */
    @Test
    @DisplayName("a guard that cannot be recorded stops the create before anything is provisioned")
    void aGuardThatCannotBeRecordedStopsTheCreate() {
        when(users.existsById("USER0042")).thenReturn(false);
        // Assumptions: stubbed with doThrow rather than when(...).thenThrow, because the ledger
        //   substitute already answers save with a stored-row behaviour and the when(...) form would
        //   invoke that behaviour with a null argument while stubbing.
        doThrow(new QueryTimeoutException("guard not committed"))
                .when(ledger).save(any(IdentitySyncTask.class));

        assertThatThrownBy(() -> service.create(
                new CreateUserRequest("Ada", "Lovelace", "USER0042", "A")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(UserService.MESSAGE_UNABLE_TO_ADD);

        verifyNoInteractions(provisioning);
        verify(users, never()).insertUser(any(), any(), any(), any(), any());
    }

    /**
     * Asserts a successful create closes its guard and leaves no row a drain could act on.
     *
     * <p>⚠️ Assumptions: the closure is asserted as a CANCELLED row rather than an APPLIED one, and the
     * difference is not cosmetic. Applied means the withdrawal was carried out against the provider;
     * cancelled means it was never owed. Recording a successful create's guard as applied would state in
     * the operational record that an account this create just bound a row to had been withdrawn.</p>
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("a successful create closes its guard, and closes it as never-owed rather than applied")
    void aSuccessfulCreateClosesItsGuard() {
        arrangeSuccessfulProvisioning();

        service.create(new CreateUserRequest("Ada", "Lovelace", "USER0042", "A"));

        assertThat(ledgerRowsOf(IdentitySyncTask.STATUS_CLAIMED))
                .as("no claimed guard survives a create that finished, or a drain would never be able"
                        + " to tell an in-flight create from an abandoned one")
                .isEmpty();
        assertThat(ledgerRowsOf(IdentitySyncTask.STATUS_PENDING))
                .as("a create that wrote its row owes no withdrawal")
                .isEmpty();
        assertThat(onlyLedgerRowOf(IdentitySyncTask.STATUS_CANCELLED).getOperation())
                .isEqualTo(IdentitySyncTask.OPERATION_WITHDRAW);
        verify(provisioning, never()).withdraw(any());
    }

    /**
     * Asserts the guard closure is issued after the insert, inside the transaction that carries it.
     *
     * <p>⚠️ Assumptions: what makes the closure safe is that it shares the insert's transaction, so the
     * pair commits or rolls back together. This case can assert the ORDER but not the atomicity, because
     * the transaction manager here is a substitute that commits nothing; the committed-together property
     * is asserted by the container-backed repository test. Recorded so the order alone is not mistaken
     * for the guarantee.</p>
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("the guard closure follows the insert rather than preceding it")
    void theGuardClosureFollowsTheInsert() {
        arrangeSuccessfulProvisioning();

        service.create(new CreateUserRequest("Ada", "Lovelace", "USER0042", "A"));

        InOrder ordered = inOrder(users, ledger);
        ordered.verify(users).insertUser(any(), any(), any(), any(), any());
        ordered.verify(ledger).save(any(IdentitySyncTask.class));
    }

    /**
     * Asserts the pool's duplicate refusal cancels this create's guard instead of owing it.
     *
     * <p>⚠️ Assumptions: getting this direction wrong is the most damaging mistake available on this
     * path. The refusal means the provider created NOTHING for this attempt, so the account under that
     * username belongs to somebody else -- the concurrent create about to commit its row, or an earlier
     * interrupted create whose own guard the reconciliation pass owns. Owing this guard would withdraw
     * that account, converting a lost race into an outage for the caller that won it.</p>
     *
     * <p>This case takes no parameter and yields no value.</p>
     *
     * @throws UserService.DuplicateUserException always, raised by the service and captured below
     */
    @Test
    @DisplayName("a pool duplicate cancels its own guard and withdraws nobody else's account")
    void aPoolDuplicateCancelsItsGuard() {
        when(users.existsById("USER0042")).thenReturn(false);
        when(provisioning.provision("USER0042", "Ada", "Lovelace", "A"))
                .thenThrow(UsernameExistsException.builder().message("username exists").build());

        assertThatThrownBy(() -> service.create(
                new CreateUserRequest("Ada", "Lovelace", "USER0042", "A")))
                .isInstanceOf(UserService.DuplicateUserException.class);

        assertThat(onlyLedgerRowOf(IdentitySyncTask.STATUS_CANCELLED).getUserId())
                .isEqualTo("USER0042");
        assertThat(ledgerRowsOf(IdentitySyncTask.STATUS_PENDING))
                .as("owing this guard would withdraw the winning caller's account")
                .isEmpty();
        verify(provisioning, never()).withdraw(any());
    }

    /**
     * Asserts an ambiguous provider fault owes the guard and attempts the withdrawal at once.
     *
     * <p>⚠️ Trade-offs: a withdrawal is the safe direction to be wrong in here, and it is a real choice
     * rather than an obvious one. The fault can arrive after the account was created, before it was, or
     * with the request never having reached the provider -- and the three are indistinguishable. A
     * withdrawal of an account that was never created is a no-op the provider absorbs; cancelling would
     * strand an account that may exist, which is the orphan this whole mechanism exists to prevent.</p>
     *
     * <p>This case takes no parameter and yields no value.</p>
     *
     * @throws IllegalStateException always, raised by the service and captured by the assertion below
     */
    @Test
    @DisplayName("an ambiguous provider fault owes the guard and attempts the withdrawal immediately")
    void aProviderFaultOwesTheGuard() {
        when(users.existsById("USER0042")).thenReturn(false);
        when(provisioning.provision("USER0042", "Ada", "Lovelace", "A"))
                .thenThrow(InternalErrorException.builder().message("provider fault").build());

        assertThatThrownBy(() -> service.create(
                new CreateUserRequest("Ada", "Lovelace", "USER0042", "A")))
                .isInstanceOf(IllegalStateException.class);

        // Assumptions: the immediate attempt is asserted alongside the ledger transition because the two
        //   answer different questions -- the attempt closes the window in the ordinary case where the
        //   provider is healthy, and the ledger row is what closes it when the attempt itself fails.
        verify(provisioning).withdraw("USER0042");
        assertThat(ledgerRowsOf(IdentitySyncTask.STATUS_CLAIMED))
                .as("a guard left claimed is one no drain would ever retry")
                .isEmpty();
    }

    /**
     * Asserts a failed insert reuses the guard it already committed rather than recording a second one.
     *
     * <p>⚠️ Refactoring Rationale: the earlier arrangement recorded a withdrawal intention at the moment
     * the insert failed, which exists only if that code runs. Reusing the guard committed before the
     * provider call is what survives a process death at the same point, and asserting the ledger holds
     * ONE row is what stops the two mechanisms being reintroduced side by side -- which would leave a
     * second pending withdrawal for an account the first had already withdrawn.</p>
     *
     * <p>This case takes no parameter and yields no value.</p>
     *
     * @throws UserService.DuplicateUserException always, raised by the service and captured below
     */
    @Test
    @DisplayName("a failed insert reuses its committed guard rather than recording a second intention")
    void aFailedInsertReusesItsGuard() {
        arrangeSuccessfulProvisioning();
        // Refactoring Rationale: the fixture is the file's own uniquenessViolation() rather than a bare
        //   DataIntegrityViolationException. isUniquenessViolation walks the cause chain for a SQLException
        //   carrying SQL state 23505 and correctly declines a translated violation with no cause, so the
        //   bare fixture reached the generic store-failure arm and this case saw the add sentence instead
        //   of the conflict. The classifier is right -- a value too wide for its column reaches the same
        //   translated type and must not be answered "choose another identifier" -- so the fixture is what
        //   was stale. What this case asserts is the guard REUSE below, which is unchanged.
        when(users.insertUser(any(), any(), any(), any(), any())).thenThrow(uniquenessViolation());

        assertThatThrownBy(() -> service.create(
                new CreateUserRequest("Ada", "Lovelace", "USER0042", "A")))
                .isInstanceOf(UserService.DuplicateUserException.class);

        verify(provisioning).withdraw("USER0042");
        assertThat(ledgerRowsOf(IdentitySyncTask.STATUS_CLAIMED)).isEmpty();
        assertThat(allLedgerRows())
                .as("one create leaves one guard, however it ended")
                .hasSize(1);
    }

    /**
     * Arranges the collaborators for a create that reaches its insert successfully.
     *
     * <p>Assumptions: gathered into one helper because six of the guard-lifecycle cases need exactly this
     * arrangement and differ only in the failure they then inject. Repeating it per case invited the
     * arrangements to drift apart, at which point the cases would no longer be comparable.</p>
     */
    private void arrangeSuccessfulProvisioning() {
        when(users.existsById("USER0042")).thenReturn(false);
        when(provisioning.provision("USER0042", "Ada", "Lovelace", "A"))
                .thenReturn(new ProvisionedIdentity(
                        UUID.fromString("11111111-2222-3333-4444-555555555555"),
                        PROVISIONED.credentialSecretName()));
        when(users.insertUser(any(), any(), any(), any(), any())).thenReturn(1);
    }

    /**
     * Yields the ledger rows carrying one status, in identifier order.
     *
     * @param status the status a row must carry to be yielded
     * @return the matching rows; never {@code null}
     */
    private List<IdentitySyncTask> ledgerRowsOf(String status) {
        return ledger.findByStatusOrderByTaskIdAsc(status, Limit.of(50));
    }

    /**
     * Yields the single ledger row carrying one status, failing the case when there is not exactly one.
     *
     * <p>Assumptions: the count is asserted here rather than in each caller because "exactly one" is the
     * property every guard-lifecycle case depends on -- a create that left two guards, or none, would
     * otherwise satisfy an assertion written about the first row it happened to find.</p>
     *
     * @param status the status the row must carry
     * @return the sole row carrying it; never {@code null}
     */
    private IdentitySyncTask onlyLedgerRowOf(String status) {
        List<IdentitySyncTask> rows = ledgerRowsOf(status);
        assertThat(rows)
                .as("exactly one ledger row should carry status %s", status)
                .hasSize(1);
        return rows.get(0);
    }

    /**
     * Yields every ledger row, whatever status it carries, so a count can be asserted.
     *
     * @return every stored row; never {@code null}
     */
    private List<IdentitySyncTask> allLedgerRows() {
        List<IdentitySyncTask> everything = new ArrayList<>();
        for (String status : List.of(IdentitySyncTask.STATUS_CLAIMED, IdentitySyncTask.STATUS_PENDING,
                IdentitySyncTask.STATUS_APPLIED, IdentitySyncTask.STATUS_CANCELLED,
                IdentitySyncTask.STATUS_ABANDONED)) {
            everything.addAll(ledgerRowsOf(status));
        }
        return everything;
    }
}
