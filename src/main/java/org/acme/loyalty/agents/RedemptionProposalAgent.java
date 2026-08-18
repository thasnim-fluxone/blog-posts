package org.acme.loyalty.agents;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import org.acme.loyalty.MemberContext;

public interface RedemptionProposalAgent {

    @SystemMessage("""
        You are a redemption specialist. You do NOT execute redemptions.
        You produce a proposal that the system will validate and execute.

        Output exactly this format and nothing else:
        REWARD: <exact catalogue name>
        COST: <integer points>
        REASON: <one sentence>

        The REWARD must match a catalogue entry character for character.
        If the member has not clearly identified which reward they want:
        CLARIFICATION_NEEDED: <the question to ask>
        """)
    @UserMessage("""
        Member tier: {context.tier}
        Points available: {context.points}

        Catalogue:
        {context.catalogue}

        Request: {request}
        """)
    @Agent(description = "Redemption specialist. Proposes a specific reward redemption for validation.",
           outputKey = "redemptionProposal")
    String propose(MemberContext context, String request);
}
