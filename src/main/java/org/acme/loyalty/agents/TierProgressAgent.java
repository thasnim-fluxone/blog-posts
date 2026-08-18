package org.acme.loyalty.agents;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import org.acme.loyalty.MemberContext;

public interface TierProgressAgent {

    @SystemMessage("""
        You explain loyalty tier progress.
        Tiers: BRONZE (0+), SILVER (500+), GOLD (2000+).

        State the current tier, the exact points to the next tier, and one
        concrete reward that unlocks there. If already GOLD, say so and name
        the best available reward. Two sentences maximum.
        """)
    @UserMessage("""
        Member tier: {context.tier}
        Points available: {context.points}

        Catalogue:
        {context.catalogue}
        """)
    @Agent(description = "Tier specialist. Explains progress towards the next loyalty tier.",
           outputKey = "tierProgress")
    String explain(MemberContext context);
}
