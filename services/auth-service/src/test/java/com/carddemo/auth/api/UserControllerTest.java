package com.carddemo.auth.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.carddemo.auth.dto.CreateUserRequest;
import com.carddemo.auth.dto.UpdateUserRequest;
import com.carddemo.auth.dto.UserResponse;
import com.carddemo.auth.dto.UserSummary;
import com.carddemo.auth.service.UserService;
import com.carddemo.common.error.GlobalExceptionHandler;
import com.carddemo.common.web.CursorToken;
import com.carddemo.common.web.PageResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

/**
 * Asserts the five user-administration routes and the status and sentence each outcome answers with.
 *
 * <h2>What this class exists to catch</h2>
 *
 * <p>Purpose: the review this class answers found that none of these five routes was served -- the
 * committed contract published all five and the service answered 404 -- so everything the reference
 * reached from its administrative menu was unreachable in the target. This class asserts each route, and
 * for each the property most likely to regress without a compiler noticing: the status, the reference
 * sentence, the location header on the create, the no-body 204 on the delete, and the confirmation
 * safeguard that replaces the reference's second keystroke.
 *
 * <p>Assumptions: the adapter is exercised through a standalone setup with the shared advice registered,
 * because the mapping under test pairs an outcome with a STATUS and a status is only observable through a
 * response.
 *
 * <p>Trade-offs: no filter chain is installed, so nothing here proves these five routes require the
 * administrative authority. That property is asserted where it is declared, against the rule table the
 * chain is built from, by the security configuration cases in this module.
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception clause.</p>
 */
class UserControllerTest {

    /** A fixed instant, so a stamped problem body is reproducible from the source alone. */
    private static final Instant FIXED_INSTANT = Instant.parse("2026-08-08T09:14:27.481903Z");

    /** The serialiser the request bodies below are written with. */
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** The subject the standalone setup presents, which the paging cursors are sealed against. */
    private static final java.security.Principal CALLER = () -> "operator-1";

    /**
     * A value carrying the shape a sealed cursor token has, standing in for the first-key component.
     *
     * <p>Assumptions: the page envelope refuses a cursor component that is not a sealed token, precisely
     * so a raw keyset key cannot be published to a client, so a readable stand-in such as
     * {@code "first"} cannot be used here. The shape is a version segment, an encoded payload and a
     * fixed-width authentication code, all separated by periods, and this value reproduces it without
     * holding a real code -- this class asserts that the adapter passes the envelope through untouched,
     * not that a token verifies, which is asserted where tokens are minted and redeemed.</p>
     */
    private static final String SEALED_FIRST_KEY =
            CursorToken.VERSION + ".Zmlyc3Qta2V5LXBheWxvYWQ." + "A".repeat(43);

    /** A second value of the same sealed shape, standing in for the last-key component. */
    private static final String SEALED_LAST_KEY =
            CursorToken.VERSION + ".bGFzdC1rZXktcGF5bG9hZA." + "B".repeat(43);

    private UserService users;

    private MockMvc mockMvc;

    /**
     * Builds the adapter over a substituted service with the shared advice registered.
     */
    @BeforeEach
    void buildMockMvc() {
        users = mock(UserService.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new UserController(users,
                        Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC)))
                .setControllerAdvice(new GlobalExceptionHandler(
                        Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC)))
                .build();
    }

    /**
     * Asserts the list route answers 200 with the page envelope and passes the caller's name through.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("the list route answers 200 with the page envelope")
    void theListRouteAnswersWithThePageEnvelope() throws Exception {
        when(users.list(isNull(), isNull(), eq("operator-1"))).thenReturn(PageResponse.ofRows(
                List.of(new UserSummary("USER0001", "Ada", "Lovelace", "U")),
                SEALED_FIRST_KEY, SEALED_LAST_KEY, true));

        mockMvc.perform(get(UserController.COLLECTION_PATH).principal(CALLER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].userId").value("USER0001"))
                .andExpect(jsonPath("$.items[0].userType").value("U"))
                .andExpect(jsonPath("$.hasNext").value(true))
                .andExpect(jsonPath("$.firstKey").value(SEALED_FIRST_KEY))
                .andExpect(jsonPath("$.lastKey").value(SEALED_LAST_KEY));
    }

    /**
     * Asserts the cursor and the direction reach the service exactly as the caller sent them.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("the cursor and direction reach the service unaltered")
    void theCursorAndDirectionReachTheService() throws Exception {
        when(users.list(any(), any(), eq("operator-1"))).thenReturn(PageResponse.empty());

        mockMvc.perform(get(UserController.COLLECTION_PATH)
                        .param("cursor", "v1.payload.code")
                        .param("direction", "previous")
                        .principal(CALLER))
                .andExpect(status().isOk());

        verify(users).list("v1.payload.code", "previous", "operator-1");
    }

    /**
     * Asserts a direction outside the contract's enumeration is refused before the service runs.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("a direction outside the contract's enumeration answers 400")
    void anInvalidDirectionAnswers400() throws Exception {
        mockMvc.perform(get(UserController.COLLECTION_PATH)
                        .param("cursor", "v1.payload.code")
                        .param("direction", "backwards")
                        .principal(CALLER))
                .andExpect(status().isBadRequest());

        verify(users, never()).list(any(), any(), any());
    }

    /**
     * Asserts the create route answers 201 with the stored row and a location header addressing it.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("the create route answers 201 with a location header addressing the row")
    void theCreateRouteAnswers201WithALocationHeader() throws Exception {
        UUID subject = UUID.fromString("11111111-2222-3333-4444-555555555555");
        when(users.create(any(CreateUserRequest.class)))
                .thenReturn(new UserResponse("USER0042", "Ada", "Lovelace", "A", subject));

        mockMvc.perform(post(UserController.COLLECTION_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(
                                new CreateUserRequest("Ada", "Lovelace", "USER0042", "A"))))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/auth/users/USER0042"))
                .andExpect(jsonPath("$.userId").value("USER0042"))
                .andExpect(jsonPath("$.cognitoSub").value(subject.toString()));
    }

    /**
     * Asserts a duplicate identifier answers 409 with the reference sentence, missing "s" and all.
     *
     * <p>Assumptions: the shared advice's own conflict renderer cannot produce this sentence -- it carries
     * a fixed sentence chosen from three contention kinds, none of which is a duplicate key -- which is why
     * this adapter declares a handler for the refusal type. This case is what proves the handler is reached
     * rather than the advice.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("a duplicate identifier answers 409 with the reference sentence")
    void aDuplicateIdentifierAnswers409() throws Exception {
        when(users.create(any(CreateUserRequest.class))).thenAnswer(invocation -> {
            throw duplicateUser();
        });

        mockMvc.perform(post(UserController.COLLECTION_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(
                                new CreateUserRequest("Ada", "Lovelace", "USER0001", "A"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("User ID already exist..."));
    }

    /**
     * Asserts a blank submitted name answers 400 with the reference sentence for that field.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("a blank submitted name answers 400 with the reference sentence")
    void aBlankNameAnswers400() throws Exception {
        mockMvc.perform(post(UserController.COLLECTION_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(
                                new CreateUserRequest("   ", "Lovelace", "USER0042", "A"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'firstName')].message")
                        .value(org.hamcrest.Matchers.hasItem("First Name can NOT be empty...")));

        verify(users, never()).create(any(CreateUserRequest.class));
    }

    /**
     * Asserts the read route answers 200 with the whole row and 404 with the reference sentence.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("the read route answers 200 with the row and 404 with the reference sentence")
    void theReadRouteAnswers200And404() throws Exception {
        when(users.read("USER0001")).thenReturn(new UserResponse("USER0001", "Ada", "Lovelace", "U",
                UUID.fromString("99999999-8888-7777-6666-555555555555")));

        mockMvc.perform(get(UserController.COLLECTION_PATH + "/USER0001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("USER0001"));

        when(users.read("NOSUCH01"))
                .thenThrow(new NoSuchElementException("User ID NOT found..."));

        mockMvc.perform(get(UserController.COLLECTION_PATH + "/NOSUCH01"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("User ID NOT found..."));
    }

    /**
     * Asserts a path identifier wider than the reference field answers 400 rather than 404.
     *
     * <p>Assumptions: the bound is declared on the path parameter as well as in the request records
     * because a path segment is covered by none of them. Without it a nine-character segment would reach
     * the store as a key that cannot exist and be answered 404 -- reporting a row as missing when the
     * request was malformed.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("an over-long path identifier answers 400 rather than 404")
    void anOverLongPathIdentifierAnswers400() throws Exception {
        mockMvc.perform(get(UserController.COLLECTION_PATH + "/USER00001"))
                .andExpect(status().isBadRequest());

        verify(users, never()).read(any());
    }

    /**
     * Asserts the update route answers 200 with the row as stored after the change.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("the update route answers 200 with the row as stored")
    void theUpdateRouteAnswers200() throws Exception {
        when(users.update(eq("USER0001"), any(UpdateUserRequest.class)))
                .thenReturn(new UserResponse("USER0001", "Grace", "Hopper", "A",
                        UUID.fromString("99999999-8888-7777-6666-555555555555")));

        mockMvc.perform(put(UserController.COLLECTION_PATH + "/USER0001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(
                                new UpdateUserRequest("Grace", "Hopper", "A"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstName").value("Grace"))
                .andExpect(jsonPath("$.userType").value("A"));
    }

    /**
     * Asserts a confirmed delete answers 204 with no body at all.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("a confirmed delete answers 204 with no body")
    void aConfirmedDeleteAnswers204() throws Exception {
        mockMvc.perform(delete(UserController.COLLECTION_PATH + "/USER0001")
                        .param("confirmed", "true"))
                .andExpect(status().isNoContent())
                .andExpect(result -> org.assertj.core.api.Assertions
                        .assertThat(result.getResponse().getContentAsString()).isEmpty());

        verify(users).delete("USER0001");
    }

    /**
     * Asserts an unconfirmed delete answers 400 and deletes nothing.
     *
     * <p>Refactoring Rationale: this safeguard replaces the reference's second keystroke, which cannot
     * survive as a keystroke over HTTP. Without it a prefetch, a retried request or a crawler following a
     * link could destroy a row.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("an unconfirmed delete answers 400 and deletes nothing")
    void anUnconfirmedDeleteDeletesNothing() throws Exception {
        mockMvc.perform(delete(UserController.COLLECTION_PATH + "/USER0001")
                        .param("confirmed", "false"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("confirmed"));

        mockMvc.perform(delete(UserController.COLLECTION_PATH + "/USER0001"))
                .andExpect(status().isBadRequest());

        verify(users, never()).delete(any());
    }

    /**
     * Builds the duplicate refusal the service raises, whose constructor is package-private to it.
     *
     * <p>Assumptions: it is built reflectively because the constructor is package-private in
     * {@code com.carddemo.auth.service}, which is deliberate there -- the conflict is decided in one place
     * -- and this class sits in a different package. Widening it to let a test build one would weaken the
     * property the visibility exists to hold.</p>
     *
     * @return the refusal to throw from the substituted service; never {@code null}
     * @throws ReflectiveOperationException if the constructor cannot be resolved, which a rename would
     *     cause and which should surface here rather than as a silent skip
     */
    private static Throwable duplicateUser() throws ReflectiveOperationException {
        var constructor = UserService.DuplicateUserException.class
                .getDeclaredConstructor(String.class);
        constructor.setAccessible(true);
        return constructor.newInstance(UserService.MESSAGE_USER_ID_EXISTS);
    }
}
