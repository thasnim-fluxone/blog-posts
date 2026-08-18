package org.acme.loyalty.catalogue;

import java.util.List;
import java.util.stream.Collectors;

public final class Catalogue {

    public static final List<Reward> REWARDS = List.of(
            new Reward("Free filter coffee",   150, "drink"),
            new Reward("Free pastry",          300, "food"),
            new Reward("Free specialty drink", 450, "drink"),
            new Reward("Branded travel mug",   900, "merchandise"),
            new Reward("Barista masterclass", 1800, "experience"),
            new Reward("Travel voucher",      2200, "experience"));

    private Catalogue() {}

    public static String render() {
        return REWARDS.stream()
                .map(r -> "- %s (%d points, %s)".formatted(r.name(), r.cost(), r.category()))
                .collect(Collectors.joining("\n"));
    }

    public static int cheapestCost() {
        return REWARDS.stream().mapToInt(Reward::cost).min().orElseThrow();
    }

    public static Reward byName(String name) {
        return REWARDS.stream()
                .filter(r -> r.name().equalsIgnoreCase(name.trim()))
                .findFirst().orElse(null);
    }
}
