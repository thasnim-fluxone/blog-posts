package org.acme.loyalty.memory;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.generic.Bodies;
import org.opensearch.client.opensearch.generic.ClientOptions;
import org.opensearch.client.opensearch.generic.Requests;
import org.opensearch.client.opensearch.generic.Response;

import java.util.Map;

/**
 * Alternative to the MicroProfile REST Client, using the Quarkiverse
 * OpenSearch extension.
 *
 *   <dependency>
 *     <groupId>io.quarkiverse.opensearch</groupId>
 *     <artifactId>quarkus-opensearch-java-client</artifactId>
 *   </dependency>
 *
 * The typed Java API covers indexing and search but not plugin endpoints,
 * so agentic memory goes through generic(), which the client provides for
 * exactly this case. The trade-off is raw JSON in and out — the same as the
 * REST Client version — in exchange for Dev Services and a typed API on hand
 * when the application starts indexing its own data.
 */
@ApplicationScoped
public class OpenSearchMemoryGenericClient {

    @Inject OpenSearchClient client;

    public Map<String, Object> addMemory(String containerId, String json) {
        return post("/_plugins/_ml/memory_containers/" + containerId + "/memories", json);
    }

    public Map<String, Object> searchWorking(String containerId, String json) {
        return post("/_plugins/_ml/memory_containers/" + containerId
                    + "/memories/working/_search", json);
    }

    public Map<String, Object> searchLongTerm(String containerId, String json) {
        return post("/_plugins/_ml/memory_containers/" + containerId
                    + "/memories/long-term/_search", json);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> post(String endpoint, String json) {
        try (Response response = client.generic()
                .withClientOptions(ClientOptions.throwOnHttpErrors())
                .execute(Requests.builder()
                        .endpoint(endpoint)
                        .method("POST")
                        .json(json)
                        .build())) {

            return response.getBody()
                    .map(b -> Bodies.json(b, Map.class, client._transport().jsonpMapper()))
                    .map(m -> (Map<String, Object>) m)
                    .orElse(Map.of());

        } catch (Exception e) {
            throw new RuntimeException("OpenSearch request failed: " + endpoint, e);
        }
    }
}
