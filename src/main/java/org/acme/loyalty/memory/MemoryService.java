package org.acme.loyalty.memory;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.acme.loyalty.Sanitiser;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.faulttolerance.*;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

import java.util.*;
import java.util.function.Function;

@ApplicationScoped
public class MemoryService {

    private static final Logger LOG = Logger.getLogger(MemoryService.class);

    @Inject @RestClient OpenSearchMemoryClient client;
    @Inject MeterRegistry metrics;

    // The JDK common pool has the wrong context classloader, which breaks
    // config lookup inside the REST client. Use the managed executor.
    @Inject ManagedExecutor managedExecutor;

    @ConfigProperty(name = "loyalty.memory.container-id")   String containerId;
    @ConfigProperty(name = "loyalty.memory.max-turns")      int maxTurns;
    @ConfigProperty(name = "loyalty.memory.max-preferences") int maxPreferences;

    // ---- recall ---------------------------------------------------------

    @Timeout(3000)
    @Retry(maxRetries = 2, delay = 100)
    @CircuitBreaker(requestVolumeThreshold = 8, failureRatio = 0.5, delay = 10000)
    @Fallback(fallbackMethod = "noMemory")
    public RecalledMemory recall(String memberId, String sessionId) {
        var recalled = new RecalledMemory(recentTurns(memberId, sessionId), preferences(memberId));
        metrics.counter("memory.recall", "outcome", "ok").increment();
        return recalled;
    }

    RecalledMemory noMemory(String memberId, String sessionId) {
        metrics.counter("memory.recall", "outcome", "degraded").increment();
        LOG.warnf("memory unavailable; continuing without context, session=%s", sessionId);
        return RecalledMemory.empty();
    }

    private List<String> recentTurns(String memberId, String sessionId) {
        var response = client.searchWorking(containerId, Map.of(
                "size", maxTurns,
                "query", Map.of("bool", Map.of("must", List.of(
                        Map.of("term", Map.of("namespace.member_id", memberId)),
                        Map.of("term", Map.of("namespace.session_id", sessionId))))),
                "sort", List.of(Map.of("created_time", Map.of("order", "desc")))));

        var turns = extract(response, src -> {
            var data = asMap(src.get("structured_data"));
            return data == null ? null : "%s: %s".formatted(data.get("role"), data.get("text"));
        });
        Collections.reverse(turns);
        return turns;
    }

    private List<String> preferences(String memberId) {
        var response = client.searchLongTerm(containerId, Map.of(
                "size", maxPreferences,
                "query", Map.of("bool", Map.of("must", List.of(
                        Map.of("term", Map.of("namespace.member_id", memberId)),
                        Map.of("match", Map.of("strategy_type", "USER_PREFERENCE")))))));
        return extract(response, src -> (String) src.get("memory"));
    }

    // ---- write ----------------------------------------------------------

    public void recordTurnAsync(String sessionId, String memberId,
                                String userMessage, String assistantReply) {
        managedExecutor
                .runAsync(() -> recordTurn(sessionId, memberId, userMessage, assistantReply))
                .exceptionally(t -> {
                    metrics.counter("memory.write", "outcome", "failed").increment();
                    LOG.error("memory write failed", t);
                    return null;
                });
    }

    void recordTurn(String sessionId, String memberId,
                    String userMessage, String assistantReply) {

        appendWorking(memberId, sessionId, "user", userMessage);
        appendWorking(memberId, sessionId, "assistant", assistantReply);

        client.addMemory(containerId, Map.of(
                "messages", List.of(
                        Map.of("role", "user",
                               "content", List.of(Map.of("text", userMessage, "type", "text"))),
                        Map.of("role", "assistant",
                               "content", List.of(Map.of("text", assistantReply, "type", "text")))),
                "namespace", Map.of("member_id", memberId),
                "payload_type", "conversational",
                "infer", true));
    }

    private void appendWorking(String memberId, String sessionId, String role, String text) {
        client.addMemory(containerId, Map.of(
                "structured_data", Map.of("role", role, "text", text),
                "namespace", Map.of("member_id", memberId, "session_id", sessionId),
                "payload_type", "data",
                "infer", false));
    }

    // ---- response walking ------------------------------------------------

    @SuppressWarnings("unchecked")
    private List<String> extract(Map<String, Object> response,
                                 Function<Map<String, Object>, String> mapper) {
        var outer = asMap(response.get("hits"));
        if (outer == null) return new ArrayList<>();
        var hits = (List<Map<String, Object>>) outer.get("hits");
        if (hits == null) return new ArrayList<>();

        var out = new ArrayList<String>();
        for (var hit : hits) {
            var source = asMap(hit.get("_source"));
            if (source == null) continue;
            var text = mapper.apply(source);
            if (text != null && !text.isBlank()) out.add(Sanitiser.forPrompt(text));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }
}
