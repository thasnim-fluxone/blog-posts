package org.acme.loyalty.memory;

import io.quarkus.rest.client.reactive.ClientBasicAuth;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
import java.util.Map;

@Path("/_plugins/_ml/memory_containers")
@RegisterRestClient(configKey = "opensearch-memory")
@ClientBasicAuth(username = "${opensearch.username}", password = "${opensearch.password}")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public interface OpenSearchMemoryClient {

    @POST @Path("/{containerId}/memories")
    Map<String, Object> addMemory(@PathParam("containerId") String containerId,
                                  Map<String, Object> body);

    @POST @Path("/{containerId}/memories/working/_search")
    Map<String, Object> searchWorking(@PathParam("containerId") String containerId,
                                      Map<String, Object> query);

    @POST @Path("/{containerId}/memories/long-term/_search")
    Map<String, Object> searchLongTerm(@PathParam("containerId") String containerId,
                                       Map<String, Object> query);
}
