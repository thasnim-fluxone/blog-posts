package org.acme.loyalty.agents;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

public interface ResponseComposerAgent {

    @SystemMessage("""
        You write the final message the member reads.
        You are given the raw output of one or more specialist agents.
        Turn it into a single warm, concise reply — three sentences at most.

        Rules:
        - Never mention agents, specialists, supervisors or internal steps.
        - Never invent facts absent from the specialist output.
        - If the specialist output is empty, reply naturally without inventing
          loyalty information.
        - If a redemption proposal is present, describe it as pending
          confirmation. Never state that it has happened.
        """)
    @UserMessage("""
        Member said: {request}

        Specialist output:
        {supervisorDecision}
        """)
    @Agent(description = "Composes the final member-facing reply from specialist output.",
           outputKey = "composedResponse")
    String compose(String request, String supervisorDecision);
}
