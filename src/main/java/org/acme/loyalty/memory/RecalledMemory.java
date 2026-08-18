package org.acme.loyalty.memory;

import java.util.List;

public record RecalledMemory(List<String> recentTurns, List<String> preferences) {
    public static RecalledMemory empty() { return new RecalledMemory(List.of(), List.of()); }
}
