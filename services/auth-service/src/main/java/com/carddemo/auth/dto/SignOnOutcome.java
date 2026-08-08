package com.carddemo.auth.dto;

/**
 * The closed set of shapes the sign-on operation can answer with on its success status.
 *
 * <p>Purpose: give the two success bodies the published contract declares one Java type, so a handler
 * can return either without the return type widening to {@code Object} and without a caller having to
 * inspect which members happen to be present.
 *
 * <p>Refactoring Rationale: this interface exists because the contract's success status is a choice of
 * two shapes and the service could express only one. {@code auth-api.yaml} declares
 * {@code POST /api/v1/auth/signon} answering 200 with a {@code oneOf} discriminated on
 * {@code outcome}: {@code AUTHENTICATED} selects {@link SignOnResponse} and {@code CHALLENGE} selects
 * {@link SignOnChallenge}. {@link SignOnResponse} constrains its own discriminator to the single
 * value {@code AUTHENTICATED}, so the challenge shape was unrepresentable in any signature returning
 * it -- and the consequence was not cosmetic. Every seeded identity is created with a temporary
 * password, a temporary password always yields {@code NEW_PASSWORD_REQUIRED} on first use, and with
 * no shape to carry that outcome the service reported it as an integration failure. The first
 * sign-on of every provisioned user answered 500 and no path existed to set a permanent password, so
 * no seeded user could obtain a token at all.
 *
 * <p>Assumptions: the type is SEALED and permits exactly the two records the contract names. A
 * sealed hierarchy is what makes the set closed in the compiler rather than by convention: adding a
 * third success shape to the document without adding it here is a compile error at the permit
 * clause, and a {@code switch} over an outcome is exhaustive without a default arm that could
 * silently absorb a shape nobody handled. An open interface, or a common supertype with no permit
 * clause, would let a third implementation exist that the published document does not describe.
 *
 * <p>Alternatives Considered: returning {@code Object} from the handler, or declaring two handler
 * methods on two paths. The first was rejected because it removes every compile-time guarantee about
 * what a caller receives, and because springdoc would then describe the operation as returning
 * nothing in particular. The second was rejected because the contract publishes ONE operation with
 * two outcomes -- a caller posts a credential once and learns from the discriminator whether it has
 * tokens or a challenge -- so splitting it would publish an operation the document does not declare
 * and would require the client to guess which one to call before it knew the answer.
 *
 * <p>Alternatives Considered: declaring the two shared members ({@code outcome} and {@code userId})
 * on this interface as abstract methods, which is what makes them reachable without a cast. Both
 * records already declare accessors of those names, so the interface adds no code to either, and
 * naming them here is what lets a caller log the outcome and echo the identifier from the common
 * type. The members deliberately stop there: the token components exist on one shape and the session
 * on the other, so lifting either would put a member on this type that one implementation could
 * only answer with {@code null}.
 *
 * <p>Trade-offs: this interface carries no Jackson type information -- no
 * {@code @JsonTypeInfo}, no {@code @JsonSubTypes} -- and serialisation therefore relies on the
 * runtime type of the value returned. That is correct here and is stated because the annotation is
 * the reflex: each record already declares its own {@code outcome} component with the constant the
 * document requires, so the discriminator is ordinary data rather than metadata a serialiser has to
 * synthesise. Adding the annotations would emit a second discriminating property beside the one the
 * contract declares.
 */
public sealed interface SignOnOutcome permits SignOnResponse, SignOnChallenge {

    /**
     * Returns the value that names which of the two success shapes this body is.
     *
     * @return {@link SignOnResponse#OUTCOME_AUTHENTICATED} or
     *     {@link SignOnChallenge#OUTCOME_CHALLENGE}; never {@code null}
     */
    String outcome();

    /**
     * Returns the identifier the exchange was performed for, echoed so a client can render it.
     *
     * <p>Assumptions: this value carries no authority on either shape. Authority comes from the group
     * claim on a validated token, which a client cannot alter without invalidating the signature.</p>
     *
     * @return the folded user identifier, at most eight characters; never {@code null}
     */
    String userId();
}
