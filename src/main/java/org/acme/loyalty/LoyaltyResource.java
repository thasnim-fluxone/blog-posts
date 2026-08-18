package org.acme.loyalty;

import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.util.UUID;

@Path("/chat")
@Authenticated
public class LoyaltyResource {

    @Inject SecurityIdentity identity;
    @Inject LoyaltyService service;

    public record ChatRequest(@NotBlank @Size(max = 2000) String message,
                              @Size(max = 128) String sessionId) {}

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.TEXT_PLAIN)
    public String chat(@Valid ChatRequest request,
                       @HeaderParam("Idempotency-Key") String idempotencyKey) {

        // In production the OIDC principal and your member ID are unlikely to
        // be the same value; resolve one to the other here.
        String memberId = identity.getPrincipal().getName();

        String sessionId = memberId + ":" +
                (request.sessionId() == null ? "default" : request.sessionId());

        String key = idempotencyKey == null ? UUID.randomUUID().toString() : idempotencyKey;

        return service.chat(memberId, sessionId, request.message(), key);
    }
}
