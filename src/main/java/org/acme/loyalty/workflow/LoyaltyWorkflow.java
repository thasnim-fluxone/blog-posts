package org.acme.loyalty.workflow;

import dev.langchain4j.agentic.declarative.Output;
import dev.langchain4j.agentic.declarative.SequenceAgent;
import org.acme.loyalty.MemberContext;
import org.acme.loyalty.agents.LoyaltySupervisorAgent;
import org.acme.loyalty.agents.ResponseComposerAgent;

public interface LoyaltyWorkflow {

    @SequenceAgent(
            outputKey = "loyaltyResult",
            subAgents = { LoyaltySupervisorAgent.class, ResponseComposerAgent.class })
    String handle(MemberContext context, String request);

    @Output
    static String output(String composedResponse) {
        return composedResponse;
    }
}
