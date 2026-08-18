package org.acme.loyalty;

import java.util.List;

public record MemberContext(String memberId, int points, String tier,
                            List<String> preferences, List<String> recentTurns,
                            String catalogue) {}
