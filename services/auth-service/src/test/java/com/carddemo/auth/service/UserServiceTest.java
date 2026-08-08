package com.carddemo.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.carddemo.auth.domain.User;
import com.carddemo.auth.dto.CreateUserRequest;
import com.carddemo.auth.dto.UpdateUserRequest;
import com.carddemo.auth.dto.UserResponse;
import com.carddemo.auth.dto.UserSummary;
import com.carddemo.auth.mapper.UserMapper;
import com.carddemo.auth.repository.UserRepository;
import com.carddemo.common.error.ClientInputException;
import com.carddemo.common.web.CursorToken;
import com.carddemo.common.web.PageResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.domain.Limit;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UsernameExistsException;

/**
 * Asserts the five user-administration operations and every reference sentence they report.
 *
 * <h2>What this class exists to catch</h2>
 *
 * <p>Purpose: the review this class answers found that none of these five operations existed -- the
 * committed contract published all five, the mapper and the repository query methods had no production
 * caller, and the gateway routed five operations the service answered 404. Everything the reference
 * reached from its administrative menu was unreachable. This class asserts each operation and, for each,
 * the one thing about it that could regress without a compiler noticing: the sentence it reports and the
 * order in which it touches the two stores.
 *
 * <p>Assumptions: every sentence is asserted as a LITERAL rather than against the service's own constant.
 * Comparing against the constant would pass if both moved together, which is exactly the change
 * transformation rule T8 forbids -- including the two the reference itself got wrong, the missing "s" in
 * the duplicate sentence and the word "Update" on the delete failure.
 *
 * <p>Assumptions: a REAL cursor sealer is built over fixed key material rather than a substituted one,
 * because the two boundary cursors a page returns are opened again by the next request and a mocked
 * sealer would let a page be sealed under one binding and opened under another without this class
 * noticing. The paging cases therefore assert a genuine round trip.
 *
 * <p>Trade-offs: the repository and the identity provisioning are substituted, so nothing here proves a
 * real database enforces the primary key or that a real pool accepts the calls. What it proves is the
 * branch structure this service owns -- the ordering between the two stores, the compensation, the
 * unchanged-body refusal and the failure taxonomy -- most of which a live store could not be made to
 * produce on demand.
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception clause.</p>
 */
class UserServiceTest {

    /** The caller every paging case issues cursors to, which the seal is bound against. */
    private static final String SUBJECT = "operator-1";

    /** Fixed key material, so a token asserted here is reproducible from the source alone. */
    private static final byte[] CURSOR_KEY =
            "carddemo-auth-user-list-cursor-signing-key".getBytes(StandardCharsets.UTF_8);

    private UserRepository users;

    private CognitoUserProvisioningService provisioning;

    private CursorToken sealer;

    private UserService service;

    /**
     * Builds the service over a substituted repository and provisioner and a real cursor sealer.
     */
    @BeforeEach
    void buildService() {
        users = mock(UserRepository.class);
        provisioning = mock(CognitoUserProvisioningService.class);
        sealer = new CursorToken(CURSOR_KEY, Duration.ofMinutes(5));
        // WHY : Assumptions: the mapper is the REAL one rather than a substitute, because it is where the
        //       stored fixed-width padding is stripped and the unchanged-body comparison below depends on
        //       comparing logical values rather than stored images. A substituted mapper would let that
        //       comparison pass against padded values and the case would assert nothing.
        service = new UserService(users, new UserMapper(), provisioning, sealer);
    }

    /**
     * Asserts a first page returns ten rows with both boundary cursors and a further page available.
     */
    @Test
    @DisplayName("a first page returns ten rows and reports the eleventh as a further page")
    void aFirstPageReportsAFurtherPage() {
        when(users.findByUserIdGreaterThanOrderByUserIdAsc(eq(""), any(Limit.class)))
                .thenReturn(rows(11));

        PageResponse<UserSummary> page = service.list(null, null, SUBJECT);

        assertThat(page.items()).hasSize(10);
        assertThat(page.items()).extracting(UserSummary::userId)
                .containsExactly("USER0001", "USER0002", "USER0003", "USER0004", "USER0005",
                        "USER0006", "USER0007", "USER0008", "USER0009", "USER0010");
        assertThat(page.hasNext())
                .as("the eleventh row read is the reference's own device for discovering a further page")
                .isTrue();
        assertThat(page.firstKey()).isNotNull();
        assertThat(page.lastKey()).isNotNull();
    }

    /**
     * Asserts a last page reports no further page and still carries both boundary cursors.
     */
    @Test
    @DisplayName("a last page reports no further page")
    void aLastPageReportsNoFurtherPage() {
        when(users.findByUserIdGreaterThanOrderByUserIdAsc(eq(""), any(Limit.class)))
                .thenReturn(rows(4));

        PageResponse<UserSummary> page = service.list(null, null, SUBJECT);

        assertThat(page.items()).hasSize(4);
        assertThat(page.hasNext()).isFalse();
    }

    /**
     * Asserts an exhausted page is a success carrying an empty array rather than a failure.
     */
    @Test
    @DisplayName("an exhausted page is a success with an empty array")
    void anExhaustedPageIsASuccess() {
        when(users.findByUserIdGreaterThanOrderByUserIdAsc(eq(""), any(Limit.class)))
                .thenReturn(List.of());

        PageResponse<UserSummary> page = service.list(null, null, SUBJECT);

        assertThat(page.items()).isEmpty();
        assertThat(page.hasNext()).isFalse();
        assertThat(page.firstKey()).isNull();
        assertThat(page.lastKey()).isNull();
    }

    /**
     * Asserts the forward cursor of one page opens the next, and that rows come back ascending.
     *
     * <p>Assumptions: this is the round trip a mocked sealer could not verify. The cursor is sealed under
     * the forward direction scope and presented with the forward direction, which is the only combination
     * that opens.</p>
     */
    @Test
    @DisplayName("the forward cursor of one page positions the next page after its last row")
    void theForwardCursorPositionsTheNextPage() {
        when(users.findByUserIdGreaterThanOrderByUserIdAsc(eq(""), any(Limit.class)))
                .thenReturn(rows(11));

        PageResponse<UserSummary> first = service.list(null, null, SUBJECT);

        when(users.findByUserIdGreaterThanOrderByUserIdAsc(eq("USER0010"), any(Limit.class)))
                .thenReturn(rows(11, 3));

        PageResponse<UserSummary> second = service.list(first.lastKey(), "next", SUBJECT);

        assertThat(second.items()).extracting(UserSummary::userId)
                .containsExactly("USER0011", "USER0012", "USER0013");
        verify(users).findByUserIdGreaterThanOrderByUserIdAsc(eq("USER0010"), any(Limit.class));
    }

    /**
     * Asserts a backward page is read descending and presented ascending, with a further page available.
     */
    @Test
    @DisplayName("a backward page is read descending and presented ascending")
    void aBackwardPageIsPresentedAscending() {
        when(users.findByUserIdGreaterThanOrderByUserIdAsc(eq(""), any(Limit.class)))
                .thenReturn(rows(11));

        PageResponse<UserSummary> first = service.list(null, null, SUBJECT);

        List<User> descending = new ArrayList<>(rows(1, 3));
        java.util.Collections.reverse(descending);
        when(users.findByUserIdLessThanOrderByUserIdDesc(eq("USER0001"), any(Limit.class)))
                .thenReturn(descending);

        PageResponse<UserSummary> back = service.list(first.firstKey(), "previous", SUBJECT);

        assertThat(back.items()).extracting(UserSummary::userId)
                .containsExactly("USER0001", "USER0002", "USER0003");
        assertThat(back.hasNext())
                .as("a caller that has just retreated came from a page that exists, so a further page"
                        + " forwards demonstrably follows")
                .isTrue();
    }

    /**
     * Asserts a cursor sealed for one direction cannot be presented with the other.
     *
     * <p>Assumptions: this is the property the committed contract states of its single cursor parameter --
     * that replaying the previous-page position with direction next cannot silently answer with the wrong
     * page -- and it is what makes one parameter as safe as two named ones.</p>
     */
    @Test
    @DisplayName("a cursor sealed for one direction is refused when presented with the other")
    void aCursorIsBoundToItsDirection() {
        when(users.findByUserIdGreaterThanOrderByUserIdAsc(eq(""), any(Limit.class)))
                .thenReturn(rows(11));

        PageResponse<UserSummary> first = service.list(null, null, SUBJECT);

        assertThatThrownBy(() -> service.list(first.lastKey(), "previous", SUBJECT))
                .isInstanceOf(ClientInputException.class);
        assertThatThrownBy(() -> service.list(first.firstKey(), "next", SUBJECT))
                .isInstanceOf(ClientInputException.class);
    }

    /**
     * Asserts a cursor issued to one caller cannot be redeemed by another.
     */
    @Test
    @DisplayName("a cursor issued to one caller is refused when replayed by another")
    void aCursorIsBoundToItsSubject() {
        when(users.findByUserIdGreaterThanOrderByUserIdAsc(eq(""), any(Limit.class)))
                .thenReturn(rows(11));

        PageResponse<UserSummary> first = service.list(null, null, SUBJECT);

        assertThatThrownBy(() -> service.list(first.lastKey(), "next", "operator-2"))
                .isInstanceOf(ClientInputException.class);
    }

    /**
     * Asserts a store failure on the list reports the reference lookup sentence.
     */
    @Test
    @DisplayName("a store failure on the list reports the reference lookup sentence")
    void aStoreFailureOnTheListReportsTheReferenceSentence() {
        when(users.findByUserIdGreaterThanOrderByUserIdAsc(eq(""), any(Limit.class)))
                .thenThrow(new QueryTimeoutException("statement timed out"));

        assertThatThrownBy(() -> service.list(null, null, SUBJECT))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("Unable to lookup User...");
    }

    /**
     * Asserts a read of an absent row reports the reference not-found sentence as a no-such-element.
     *
     * <p>Assumptions: the TYPE is asserted as well as the sentence, because the shared advice maps exactly
     * that type onto 404 and carries the sentence onto the body; a different type would answer 500 with a
     * generic sentence and the operation's published 404 names this literal specifically.</p>
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
     */
    @Test
    @DisplayName("a blank identifier is refused with the reference empty-identifier sentence")
    void aBlankIdentifierIsRefused() {
        assertThatThrownBy(() -> service.read("   "))
                .isInstanceOf(ClientInputException.class)
                .hasMessage("User ID can NOT be empty...");

        verifyNoInteractions(users);
    }

    /**
     * Asserts a read folds the identifier to upper case before it looks for the row.
     */
    @Test
    @DisplayName("a read folds the identifier before looking for the row")
    void aReadFoldsTheIdentifier() {
        when(users.findById("USER0001")).thenReturn(Optional.of(row("USER0001")));

        UserResponse read = service.read(" user0001 ");

        assertThat(read.userId()).isEqualTo("USER0001");
        verify(users).findById("USER0001");
    }

    /**
     * Asserts a create provisions the pool account first and then writes the row bound to its subject.
     */
    @Test
    @DisplayName("a create provisions the identity first and binds the row to the subject it minted")
    void aCreateProvisionsThenWrites() {
        UUID subject = UUID.fromString("11111111-2222-3333-4444-555555555555");
        when(users.existsById("USER0042")).thenReturn(false);
        when(provisioning.provision("USER0042", "Ada", "Lovelace", "A")).thenReturn(subject);
        when(users.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserResponse created = service.create(
                new CreateUserRequest("Ada", "Lovelace", "USER0042", "A"));

        assertThat(created.userId()).isEqualTo("USER0042");
        assertThat(created.cognitoSub()).isEqualTo(subject);
        verify(provisioning).provision("USER0042", "Ada", "Lovelace", "A");
        verify(users).save(any(User.class));
    }

    /**
     * Asserts an already-taken identifier is refused with the reference sentence and nothing provisioned.
     *
     * <p>Assumptions: the reference sentence is reproduced with its grammatical error intact -- "exist"
     * rather than "exists" -- because an externally observable string is part of the interface whether or
     * not it reads well.</p>
     */
    @Test
    @DisplayName("a duplicate identifier is refused before anything is provisioned")
    void aDuplicateIdentifierIsRefusedBeforeProvisioning() {
        when(users.existsById("USER0001")).thenReturn(true);

        assertThatThrownBy(() -> service.create(
                        new CreateUserRequest("Ada", "Lovelace", "USER0001", "A")))
                .isInstanceOf(UserService.DuplicateUserException.class)
                .hasMessage("User ID already exist...");

        verifyNoInteractions(provisioning);
        verify(users, never()).save(any(User.class));
    }

    /**
     * Asserts a duplicate detected by the constraint compensates the provisioned account.
     *
     * <p>Refactoring Rationale: the compensation is what makes provisioning-before-writing safe. Without
     * it a failed insert would leave a pool account that can authenticate and has no row, and a later
     * create for the same identifier would fail on the pool's duplicate-username condition -- which this
     * service deliberately does not translate into a client-facing conflict -- so the identifier would
     * become permanently unusable through a path no operator could see.</p>
     */
    @Test
    @DisplayName("a duplicate lost to a race withdraws the account it had just provisioned")
    void aRaceLostToTheConstraintWithdrawsTheAccount() {
        when(users.existsById("USER0042")).thenReturn(false);
        when(provisioning.provision("USER0042", "Ada", "Lovelace", "A"))
                .thenReturn(UUID.randomUUID());
        when(users.save(any(User.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

        assertThatThrownBy(() -> service.create(
                        new CreateUserRequest("Ada", "Lovelace", "USER0042", "A")))
                .isInstanceOf(UserService.DuplicateUserException.class)
                .hasMessage("User ID already exist...");

        verify(provisioning).withdraw("USER0042");
    }

    /**
     * Asserts a write failure reports the reference add sentence and compensates the account.
     */
    @Test
    @DisplayName("a failed insert reports the reference add sentence and withdraws the account")
    void aFailedInsertReportsTheReferenceSentence() {
        when(users.existsById("USER0042")).thenReturn(false);
        when(provisioning.provision("USER0042", "Ada", "Lovelace", "A"))
                .thenReturn(UUID.randomUUID());
        when(users.save(any(User.class))).thenThrow(new QueryTimeoutException("timed out"));

        assertThatThrownBy(() -> service.create(
                        new CreateUserRequest("Ada", "Lovelace", "USER0042", "A")))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("Unable to Add User...");

        verify(provisioning).withdraw("USER0042");
    }

    /**
     * Asserts an orphaned pool account is a fault rather than a caller-facing conflict.
     *
     * <p>Assumptions: the authority on whether a user identifier is taken is the primary key on the row
     * table, and the probe found no row. Reaching this condition therefore means an account exists whose
     * row was never written -- an inconsistency an operator has to resolve, not something a caller can
     * correct by choosing another identifier.</p>
     */
    @Test
    @DisplayName("an orphaned pool account is a fault, not a caller-facing conflict")
    void anOrphanedPoolAccountIsAFault() {
        when(users.existsById("USER0042")).thenReturn(false);
        when(provisioning.provision("USER0042", "Ada", "Lovelace", "A"))
                .thenThrow(UsernameExistsException.builder().message("User account already exists")
                        .build());

        assertThatThrownBy(() -> service.create(
                        new CreateUserRequest("Ada", "Lovelace", "USER0042", "A")))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("Unable to Add User...");

        verify(users, never()).save(any(User.class));
    }

    /**
     * Asserts an update that changes nothing is refused with the reference sentence and no attribution.
     *
     * <p>Assumptions: this is the reference's own behaviour, not an addition -- its else branch writes
     * {@code 'Please modify to update ...'} in RED, and the colour marks it a rejection rather than
     * advice. Note the space before the ellipsis, which this literal carries and the six others do
     * not.</p>
     */
    @Test
    @DisplayName("an unchanged update is refused with the reference modify sentence")
    void anUnchangedUpdateIsRefused() {
        when(users.findById("USER0001")).thenReturn(Optional.of(row("USER0001")));

        assertThatThrownBy(() -> service.update("USER0001",
                        new UpdateUserRequest("Ada", "Lovelace", "U")))
                .isInstanceOf(ClientInputException.class)
                .hasMessage("Please modify to update ...");

        verify(users, never()).save(any(User.class));
        verifyNoInteractions(provisioning);
    }

    /**
     * Asserts a changed update writes the row and then brings the pool in line with the previous type.
     *
     * <p>Assumptions: the PREVIOUS type is passed to the provisioner as well as the new one, because the
     * provisioner needs it to decide whether group membership has to move at all -- and it must not move
     * on an update that changed only a name, since removing and re-adding a group passes through a state
     * in which the account holds none.</p>
     */
    @Test
    @DisplayName("a changed update writes the row and synchronises the identity with both types")
    void aChangedUpdateSynchronisesTheIdentity() {
        when(users.findById("USER0001")).thenReturn(Optional.of(row("USER0001")));
        when(users.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserResponse updated = service.update("USER0001",
                new UpdateUserRequest("Grace", "Hopper", "A"));

        assertThat(updated.firstName()).isEqualTo("Grace");
        assertThat(updated.userType()).isEqualTo("A");
        verify(provisioning).synchronise("USER0001", "Grace", "Hopper", "U", "A");
    }

    /**
     * Asserts an update against an absent row reports the reference not-found sentence.
     */
    @Test
    @DisplayName("an update against an absent row reports the reference not-found sentence")
    void anUpdateAgainstAnAbsentRowReportsNotFound() {
        when(users.findById("NOSUCH01")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update("NOSUCH01",
                        new UpdateUserRequest("Grace", "Hopper", "A")))
                .isExactlyInstanceOf(NoSuchElementException.class)
                .hasMessage("User ID NOT found...");
    }

    /**
     * Asserts a failed update reports the reference update sentence.
     */
    @Test
    @DisplayName("a failed update reports the reference update sentence")
    void aFailedUpdateReportsTheReferenceSentence() {
        when(users.findById("USER0001")).thenReturn(Optional.of(row("USER0001")));
        when(users.save(any(User.class))).thenThrow(new QueryTimeoutException("timed out"));

        assertThatThrownBy(() -> service.update("USER0001",
                        new UpdateUserRequest("Grace", "Hopper", "A")))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("Unable to Update User...");
    }

    /**
     * Asserts a delete removes the row and then the pool account, in that order.
     *
     * <p>Assumptions: the order is chosen for the state an interruption leaves. A row removed with its
     * account still present is an identity refused at every guarded route; an account removed with its row
     * still present is a row whose user cannot sign on and which no later operation can repair without
     * reprovisioning.</p>
     */
    @Test
    @DisplayName("a delete removes the row and then the identity")
    void aDeleteRemovesTheRowThenTheIdentity() {
        User stored = row("USER0001");
        when(users.findById("USER0001")).thenReturn(Optional.of(stored));

        service.delete("USER0001");

        org.mockito.InOrder ordered = org.mockito.Mockito.inOrder(users, provisioning);
        ordered.verify(users).delete(stored);
        ordered.verify(provisioning).withdraw("USER0001");
    }

    /**
     * Asserts a delete against an absent row reports the reference not-found sentence.
     */
    @Test
    @DisplayName("a delete against an absent row reports the reference not-found sentence")
    void aDeleteAgainstAnAbsentRowReportsNotFound() {
        when(users.findById("NOSUCH01")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete("NOSUCH01"))
                .isExactlyInstanceOf(NoSuchElementException.class)
                .hasMessage("User ID NOT found...");

        verifyNoInteractions(provisioning);
    }

    /**
     * Asserts a failed delete reports the sentence the reference writes for it, naming the wrong verb.
     *
     * <p>Assumptions: the sentence says "Update" on a delete failure. That is what
     * {@code app/cbl/COUSR03C.cbl} line 332 writes -- the same string its sibling writes at
     * {@code app/cbl/COUSR02C.cbl} line 386 -- and it reads as a copy of the update program's handler that
     * was never reworded. It is carried across as it stands because the string is externally observable;
     * the wrong verb is documented rather than fixed, and this case is what stops a well-meaning
     * correction.</p>
     */
    @Test
    @DisplayName("a failed delete reports the reference sentence, which names Update")
    void aFailedDeleteReportsTheReferenceSentenceNamingUpdate() {
        User stored = row("USER0001");
        when(users.findById("USER0001")).thenReturn(Optional.of(stored));
        org.mockito.Mockito.doThrow(new QueryTimeoutException("timed out"))
                .when(users).delete(stored);

        assertThatThrownBy(() -> service.delete("USER0001"))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("Unable to Update User...");
    }

    /**
     * Builds a stored row with the ordinary user type and the two names the update cases start from.
     *
     * @param userId the identifier to key the row by
     * @return a row that is not managed by any provider, which is all these cases need
     */
    private static User row(String userId) {
        return new User(userId, "Ada", "Lovelace", "U",
                UUID.fromString("99999999-8888-7777-6666-555555555555"));
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
}
