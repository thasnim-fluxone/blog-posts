package org.acme.loyalty;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;
import org.acme.loyalty.catalogue.Catalogue;
import org.acme.loyalty.catalogue.Member;
import org.acme.loyalty.catalogue.RedemptionService;
import org.acme.loyalty.memory.MemoryService;
import org.acme.loyalty.workflow.LoyaltyWorkflow;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ApplicationScoped
public class LoyaltyService {

    private static final Pattern PROPOSAL = Pattern.compile(
            "REWARD:\\s*(.+?)\\s*[\\r\\n]+COST:\\s*(\\d+)", Pattern.CASE_INSENSITIVE);

    @Inject LoyaltyWorkflow workflow;
    @Inject MemoryService memory;
    @Inject RedemptionService redemptions;
    @Inject MeterRegistry metrics;

    public String chat(String memberId, String sessionId, String rawMessage, String requestId) {

        String message = Sanitiser.forStorage(rawMessage);
        if (Sanitiser.looksLikeInjection(rawMessage)) {
            metrics.counter("guardrail.injection_suspected").increment();
        }

        var recalled = memory.recall(memberId, sessionId);

        Member member = Member.findById(memberId);
        if (member == null) throw new NotFoundException("unknown member");

        var context = new MemberContext(memberId, member.points, member.tier(),
                                        recalled.preferences(), recalled.recentTurns(),
                                        Catalogue.render());

        String reply = metrics.timer("agent.workflow")
                .record(() -> workflow.handle(context, message));

        String settlement = settle(memberId, reply, requestId);
        if (settlement != null) reply = reply + "\n\n" + settlement;

        memory.recordTurnAsync(sessionId, memberId, message, reply);
        return reply;
    }

    /** The model proposes; this decides. Returns a confirmation line, or null. */
    private String settle(String memberId, String agentOutput, String requestId) {
        Matcher m = PROPOSAL.matcher(agentOutput);
        if (!m.find()) return null;

        var outcome = redemptions.redeem(memberId, m.group(1), requestId);
        metrics.counter("redemption", "result", outcome.result().name()).increment();

        return switch (outcome.result()) {
            case CONFIRMED -> "Confirmed. New balance: %d points.".formatted(outcome.newBalance());
            case DUPLICATE -> "That redemption was already applied. Balance: %d points."
                                .formatted(outcome.newBalance());
            case INSUFFICIENT_POINTS -> "You are %d points short of that reward."
                                .formatted(outcome.shortfall());
            case UNKNOWN_REWARD -> "I could not find that reward — could you pick one from the list?";
        };
    }
}
