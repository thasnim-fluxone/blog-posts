# Memory for a supervised agent system

**Build a loyalty assistant locally with Quarkus, LangChain4j and OpenSearch**

We are going to build a loyalty assistant for a coffee chain — the kind of thing a member opens in an app to ask about their points and redeem rewards. Alice is a member with 2,450 points. Here is a conversation with it that looks trivial and is not.

> **Alice:** What can I get with my points?  
> **Assistant:** You have 2,450 — enough for the barista masterclass, the branded travel mug, or a specialty drink.  
> **Alice:** How about the second one?

The second message has no subject. Answering it needs the first exchange. Fine — that is chat history, every chatbot has it.

Now add a supervisor. That one message from Alice now triggers three separate LLM calls: the supervisor decides which specialist to invoke, the specialist answers, a composer writes the reply. The supervisor's decision has to reach the composer somehow — and that is state which has nothing to do with conversation history, did not exist in the single-agent version, and must *not* survive into the next turn.

And a month later, when Alice comes back in a fresh session, the system should still know she only ever redeems for experiences. That is a third kind of state again.

These are not three names for the same thing. Each is written at a different moment, read back in a different way, and needed for a different reason.

> Once an agentic system has more than one agent, "memory" stops being one thing. It becomes three, with three different lifetimes, and conflating them is where these designs go wrong.

|  | Answers | Lives for | In this guide |
|---|---|---|---|
| **Agentic scope** | "What did the other agents in *this request* just produce?" | one invocation | `AgenticScope`, managed by LangChain4j |
| **Working memory** | "What were we just talking about?" | one conversation | OpenSearch, stored raw |
| **Long-term memory** | "Who is this person?" | until erased | OpenSearch, LLM-extracted |

The failure modes are specific, and each is a pair being confused for each other. Store the supervisor's routing decision in working memory and every future prompt replays your own internal chatter — self-inflicted context poisoning. Never expire working memory and a throwaway remark from March comes back looking like a durable fact. Replay every long-term preference verbatim in every prompt and the model drowns in retrieved-but-irrelevant text.

The complete project is at [thasnim-fluxone/blog-posts](https://github.com/thasnim-fluxone/blog-posts). The snippets below are the parts worth explaining; everything else is in the repo.

Two rules shape every design decision:

> **Retrieval, validation and persistence are not agents.** Deterministic work stays in Java.  
> **Store the conversation, never the reasoning.** Internal agent chatter goes to traces.

---

## Prerequisites

- **JDK 21 or newer**
- **Quarkus 3.33 LTS or newer** — install the CLI, or use the generated wrapper
- **Docker or a compatible container runtime** with at least 4 GB available to containers — OpenSearch with ML models fails silently on less
- **An OpenAI-compatible endpoint and key**
- `curl` and `jq`

Allow roughly two hours end to end. Steps 1–12 move quickly; Step 14, registering the extraction models, is the slowest part.

---

## Step 1: OpenSearch, with security on

Define the cluster in `compose-devservices.yml` at the project root. Quarkus starts it when dev mode starts and cleans it up when dev mode ends:

```yaml
services:
  opensearch:
    image: opensearchproject/opensearch:3.7.0
    container_name: loyalty-opensearch
    environment:
      discovery.type: single-node
      OPENSEARCH_INITIAL_ADMIN_PASSWORD: <your-admin-password>
      OPENSEARCH_JAVA_OPTS: -Xms2g -Xmx2g
    ports:
      - '9200:9200'
    healthcheck:
      test: >
        curl -sk -u admin:<your-admin-password>
        https://localhost:9200/_cluster/health || exit 1
      interval: 10s
      retries: 30
      start_period: 30s

  opensearch-init:
    image: curlimages/curl:8.11.1
    depends_on:
      opensearch:
        condition: service_healthy
    command: >
      -sk -u admin:<your-admin-password>
      -X PUT https://opensearch:9200/_cluster/settings
      -H "Content-Type: application/json"
      -d '{"persistent":{
             "plugins.ml_commons.only_run_on_ml_node":false,
             "plugins.ml_commons.model_access_control_enabled":false,
             "plugins.ml_commons.native_memory_threshold":99}}'
```

The init service is doing real work: without those cluster settings, model registration later fails with a node allocation error. The heap must be 2g — the embedding model does not fit in less.

One step stays manual, because the application validates its truststore before Quarkus finishes booting:

```bash
docker compose -f compose-devservices.yml up -d opensearch
docker cp loyalty-opensearch:/usr/share/opensearch/config/root-ca.pem ./opensearch-ca.pem
```

Add a shell function so the rest of the guide is readable:

```bash
os() {
  curl -s --cacert ./opensearch-ca.pem \
    -u admin:<your-admin-password> -H "Content-Type: application/json" "$@"
}
```

## Step 2: The project

```bash
quarkus create app org.acme:loyalty-agents \
  --extension='quarkus-langchain4j-openai,quarkus-langchain4j-agentic,rest-jackson,rest-client-jackson,oidc,hibernate-orm-panache,hibernate-validator,jdbc-postgresql,flyway,smallrye-fault-tolerance,smallrye-health,micrometer-registry-prometheus'
cd loyalty-agents
cp ../opensearch-ca.pem .
```

Import both BOMs — the LangChain4j extensions are on their own release cadence and the platform BOM does not manage them alone. With both imported, declare `quarkus-langchain4j-openai` and `quarkus-langchain4j-agentic` with no `<version>`. One property that is easy to miss when assembling a build by hand:

```xml
<maven.compiler.parameters>true</maven.compiler.parameters>
```

Placeholders like `{context}` are resolved from method parameter names, which are absent from the bytecode without it. The failure is a build-time `Duplicate key null` from `AiServicesProcessor`.

`application.properties` — full file in the repo ([`src/main/resources/application.properties`](https://github.com/thasnim-fluxone/blog-posts/blob/main/src/main/resources/application.properties)). The two properties worth calling out: `opensearch-ca.pem` is resolved against the working directory, not the classpath — it belongs at the project root beside `pom.xml`. And `schema-management.strategy=validate` means Hibernate never alters the schema; Flyway owns it.

The Flyway migration creates `loyalty_member` and `redemption` tables and seeds alice (2,450 pts), bob (180 pts), and carol (620 pts). Carol exists only for the concurrency test.

## Step 3: The domain

Ordinary Java. The entities, catalogue and REST client are mechanical — see [`src/main/java/org/acme/loyalty/`](https://github.com/thasnim-fluxone/blog-posts/tree/main/src/main/java/org/acme/loyalty) in the repo. One class earns real attention.

`RedemptionService` is where the money moves. Two lines carry it:

```java
Member member = Member.findById(memberId, LockModeType.PESSIMISTIC_WRITE);
...
record.cost = reward.cost();   // catalogue price, never the model's
```

The balance in `MemberContext` is a snapshot taken before three model calls happened. `PESSIMISTIC_WRITE` re-reads inside the transaction that spends points, so a member with 620 points cannot redeem the same 450-point reward twice if eight requests arrive at once. `record.cost = reward.cost()` means the agent's proposed `COST:` is parsed only to confirm the format looked right, then thrown away — a model proposing a 2,200-point voucher at 50 points gets charged 2,200.

The deterministic rules are the best-tested code in the system. Run these before anything else — they need no OpenSearch, no API key, and no model:

```bash
mvn test -Dtest=SupervisorRequestTest   # guard logic, routing rules
mvn test -Dtest=SanitiserTest           # injection redaction
```

## Step 4: Recall

The agents need the member's balance, tier, preferences, and what was said earlier. All of it is fetched before the workflow starts and handed in as a single `MemberContext` record.

Retrieval is I/O with no judgement in it, so it belongs in Java rather than behind a tool call. `MemoryService` — full listing in the repo — carries three properties worth noting.

**`memberId` comes from the caller, never from an agent.** Expose recall as a tool and the model chooses whose preferences to fetch. Taking tenant identity from the request closes that by construction.

**The catalogue lives inside `MemberContext` deliberately.** The supervisor's planner decides what arguments each specialist receives, and for a plain `String catalogue` parameter it supplies a value of its own — in practice the literal text `String`, lifted from the agent description. The specialist then reads `Catalogue:\nString` and invents rewards that sound plausible and do not exist. Anything an agent must receive intact belongs in the context object, resolved from the agentic scope rather than from planner output.

**`ManagedExecutor`, not `CompletableFuture.runAsync()`.** The JDK common pool carries the wrong context classloader, which breaks config lookup inside the REST client. The symptom is silent: writes are asynchronous, so nothing throws, every reply looks fine, and nothing is ever stored. The fix is one injected field:

```java
@Inject ManagedExecutor managedExecutor;
```

The async write path then becomes:

```java
managedExecutor
    .runAsync(() -> recordTurn(sessionId, memberId, userMessage, assistantReply))
    .exceptionally(t -> { LOG.error("memory write failed", t); return null; });
```

**Degrading beats failing.** `@Fallback(fallbackMethod = "noMemory")` means if OpenSearch is unreachable the circuit opens and the assistant answers without history rather than returning a 500.

**Recall is bounded:** ten turns, five preferences. Unbounded recall produces context distraction.

## Step 5: The supervisor

`@SupervisorRequest` runs before the supervisor and builds its instructions from real data:

```java
@SupervisorRequest
static String request(MemberContext context, String request) {

    boolean canAffordAnything = context.points() >= Catalogue.cheapestCost();

    String redemptionRule = canAffordAnything
        ? """
          The member can afford at least one reward.
          If they are asking to redeem something, invoke RedemptionProposalAgent.
          """
        : """
          The member cannot afford ANY reward in the catalogue.
          DO NOT invoke RedemptionProposalAgent under any circumstances.
          DO NOT invoke RewardRecommendationAgent — there is nothing to recommend.
          If they ask to redeem, invoke TierProgressAgent instead.
          """;

    return """
        You are the supervisor for a coffee chain loyalty assistant.
        ...
        %s
        Member: %s (tier %s, %d points)
        ...
        """.formatted(redemptionRule, context.memberId(), context.tier(),
                      context.points(), ...);
}
```

`canAffordAnything` is an ordinary Java comparison, and the branch it selects is an instruction the supervisor receives, not a question it is asked.

> Compute the hard constraints in Java and hand the model a request that already accounts for them.

This is the honest answer to "how do you stop the supervisor doing something stupid." Not better prompting — fewer decisions. And because the method is `static` and pure, that rule is unit-testable with no model, no network and no API key.

The three specialists (`RewardRecommendationAgent`, `TierProgressAgent`, `RedemptionProposalAgent`) and `ResponseComposerAgent` are interfaces; the extension generates the implementations. Full listings in the repo. One line from `RedemptionProposalAgent` is worth quoting:

```
You are a redemption specialist. You do NOT execute redemptions.
You produce a proposal that the system will validate and execute.
```

It proposes; it does not execute. The `LoyaltyWorkflow` sequences the supervisor and composer:

```java
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
```

The `@Output` parameter name must match `ResponseComposerAgent`'s `outputKey` exactly — a mismatch fails at runtime, not compile time. The agentic module is currently in beta; this guide was written against `quarkus.platform.version=3.33.2`.

## Step 6: Orchestration

`LoyaltyService.chat` — full listing in the repo — follows the architecture diagram: recall, sanitise, run, validate, execute, persist. The only lines that carry design weight:

```java
String memberId = identity.getPrincipal().getName();
```

There is no `memberId` path parameter. It comes from the verified token, and the session ID is prefixed with it so one client cannot read another's session by guessing an ID. Every layer below can assume the tenant is settled.

```java
String settlement = settle(memberId, reply, requestId);
if (settlement != null) reply = reply + "\n\n" + settlement;
memory.recordTurnAsync(sessionId, memberId, message, reply);
```

The model proposes; `settle()` decides. The reply appends the settlement line *after* the model has already composed the response, which is why the composer is told to describe redemptions as "pending confirmation" — it runs before validation.

## Step 7: Writing memory

A run produces a supervisor decision, up to three specialist outputs and a composed reply. Persist only the exchange the member saw. Working memory is replayed into future prompts, so storing internal traces is context poisoning you built yourself.

Two writes, two purposes:

```java
// raw, keyed by session, read back verbatim
appendWorking(memberId, sessionId, "user", userMessage);
appendWorking(memberId, sessionId, "assistant", assistantReply);

// handed to OpenSearch's extraction pipeline
client.addMemory(containerId, Map.of(
        "messages", List.of(...),
        "namespace", Map.of("member_id", memberId),
        "payload_type", "conversational",
        "infer", true));
```

Both are off the critical path — the member already has the reply. A failed write costs one turn of history, not one failed request.

## Step 8: Long-term memory — registering the models

Extraction needs two models registered in `ml-commons`. Budget twenty minutes.

If model registration returns `No eligible node found`, the init container raced the security plugin. Apply the settings by hand:

```bash
os -X PUT "https://localhost:9200/_cluster/settings" -d '{
  "persistent": {
    "plugins.ml_commons.only_run_on_ml_node": false,
    "plugins.ml_commons.model_access_control_enabled": false,
    "plugins.ml_commons.native_memory_threshold": 99
  }
}' | jq '{acknowledged}'
```

Then register the embedding model (~90 MB download), poll until `COMPLETED`, and deploy it. The LLM connector requires three things that are not obvious from the documentation. The agentic memory pipeline calls the LLM with `system_prompt` and `user_prompt` — any other parameter names produce `parameter placeholder not filled`. OpenAI returns `$.choices[0].message.content` but the pipeline reads `$.output.message.content[0].text`, so a `post_process_function` Painless script is required to reshape the response. And `$OPENAI_API_KEY` must be set in the terminal running these commands — the key is embedded at connector creation time:

```bash
CONNECTOR=$(os -X POST "https://localhost:9200/_plugins/_ml/connectors/_create" -d '{
  "name": "OpenAI chat",
  ...
  "actions": [{
    "action_type": "predict",
    "method": "POST",
    "url": "https://${parameters.endpoint}/v1/chat/completions",
    "headers": { "Authorization": "Bearer ${credential.openAI_key}" },
    "request_body": "{\"model\":\"${parameters.model}\",\"messages\":[{\"role\":\"system\",\"content\":\"${parameters.system_prompt}\"},{\"role\":\"user\",\"content\":\"${parameters.user_prompt}\"}]}",
    "post_process_function": "String c = params.get(\"choices\").get(0).get(\"message\").get(\"content\"); return \"{\\\"name\\\":\\\"response\\\",\\\"dataAsMap\\\":{\\\"output\\\":{\\\"message\\\":{\\\"content\\\":[{\\\"text\\\":\\\"\" + c.replace(\"\\\"',\"\\\\\\\"\") + \"\\\"}]}}}}\""
  }]
}' | jq -r '.connector_id')
```

Full model registration and container creation commands are in the README. Create the container with `embedding_dimension: 384` (required — omitting it returns `Dimension is required for TEXT_EMBEDDING`) and `USER_PREFERENCE` and `SEMANTIC` strategies, then restart the app with the new `MEMORY_CONTAINER_ID`.

**Working memory** needs durability and ordering — the last N messages for this session, in sequence. **Long-term memory** needs two different capabilities: *extraction* (recognising that "I cannot drink dairy" is a durable fact and "the flat white was cold" is a passing complaint) and *retrieval by relevance* (surfacing the dairy constraint when Alice asks about drink rewards, despite her question sharing no word with the stored fact). OpenSearch's agentic memory API provides both. Other arrangements get you to the same place, but it is worth knowing which capabilities you are getting and which you are still on the hook for.

## What to verify

Run `mvn quarkus:dev` and work through these:

1. **Recommendation.** As Alice: *"What can I get with my points?"* → recommendation agent only. Watch the agent report, not just the reply — the correct answer produced by invoking all three specialists is still a bug.
2. **Working memory.** Same session: *"How about the second one?"* → resolves the reference. Restart the JVM, ask again — still works.
3. **The guard.** As Bob (180 points): *"I want to redeem the travel voucher"* → `RedemptionProposalAgent` runs (180 pts is above the 150-point floor), `RedemptionService` returns `INSUFFICIENT_POINTS`. At 10 points the guard fires in `@SupervisorRequest` and the specialist is absent from the trace entirely.
4. **Redemption.** As Alice: *"Redeem the branded travel mug"* → proposal, confirmation line, balance 1,550. Same request, same `Idempotency-Key` → `DUPLICATE`, balance unchanged.
5. **Small talk.** *"thanks!"* → no specialists invoked at all.
6. **Degradation.** Stop OpenSearch, send a request → 200 with no history, not a 500. Check `localhost:8080/q/metrics` for `memory_recall{outcome="degraded"}`.
7. **The long-term loop.** As Alice, fresh session: *"I only care about experiences, never merchandise."* Wait fifteen seconds. Start another new session, ask for a recommendation — it leads with experiences rather than the travel mug. A preference stated in a closed session changed what a different agent recommended in a session that knows nothing about it.

Also run the infrastructure test:

```bash
mvn test -Dtest=RedemptionServiceTest
```

The concurrency test (`onlyOneOfEightConcurrentRedemptionsSucceeds`) is the one worth running twenty times — concurrency bugs pass intermittently. Then comment out `LockModeType.PESSIMISTIC_WRITE` and confirm it starts failing. A safety test that still passes with the safety removed is not testing anything.

---

## What would change in production

- **Credentials and certificates.** The application gets its own OpenSearch role scoped to the memory indices, real certificates from a mounted secret, and credentials from a secret manager. The client code does not change.
- **Tenant isolation at the data layer.** OpenSearch's document-level security makes isolation structural — a query that forgets the `memberId` filter still cannot return another member's documents.
- **Retention and erasure.** Transcripts are personal data with no natural expiry. Production needs an ISM policy and an explicit erasure path for data-subject requests.

---

## Where this gets harder

**Superseded facts are attempted, not solved.** "Prefers travel rewards" was true in March. By August the member redeems for free coffee every morning. Both records score well on semantic similarity, and vector search has no opinion about which is current. OpenSearch does address this — the agentic memory design includes updating or deleting memories when new information contradicts what is stored. It is worth knowing how that decision gets made, though: the contradiction check is an LLM judgement at ingestion time, and published evaluation of exactly this task is unflattering across the whole field. Treat automatic contradiction resolution as a useful default that will sometimes be wrong. Where a fact is expensive to get wrong, carry your own `valid-from` and `superseded-by` fields.

**Genuinely conflicting preferences.** Two memories, both current, both true, pulling in opposite directions. Retrieve both and you have handed the contradiction to the supervisor and let it choose.

**Bounded recall is a guess.** Five preferences and ten turns are numbers chosen without evidence. The right answer probably depends on what the member is asking about.

---

## What comes next

Everything here reads one member's memory. But the extracted preferences are also ordinary documents in a Lucene index, which means the same store can answer a question no chat-memory abstraction can express: *how many members have told us they cannot drink dairy?*

The follow-up, [Your agent's memory is a database](https://medium.com/@thasnimhm/your-agents-memory-is-a-database), covers that — aggregations across every member, semantic cohorts for cold start, and the coverage metric that is the only way to notice extraction has silently stopped working.

## Three things worth keeping

Strip away the framework and the architecture comes down to three boundaries. They are the parts I would carry into any agentic system, not just this one.

**Deterministic work stays in Java.** Retrieval, validation and persistence are not agents. That single rule is what stops a model choosing whose data to read, and what stops it deciding that a 2,200-point voucher costs fifty. It also means the logic worth trusting is the logic you can unit-test in milliseconds, with no API key and no network.

**Store the conversation, not the reasoning.** Working memory gets replayed into every future prompt, so it is the one place where writing more is actively worse. The member said something; the system said something back. Routing decisions and half-formed proposals go to the agent report, where they belong.

**Memory is executable input.** Everything else in your stack assumes stored data sits still. A poisoned memory is a prompt injection that persists — and in a supervised system it does not just skew one answer, it reaches the routing decision that picks which agent answers at all.

Supervisor and specialists first, with no memory whatsoever. Get the routing right while you can still watch every hop in the agent report and the only variable is your prompts.

Then working memory, and the moment it earns its place: ask a follow-up question with no subject in it, restart the JVM, and ask again.

Then long-term extraction — and stop when a preference stated in one session changes a recommendation in the next. That is the loop closing, and it is worth more as a demo than any single answer the assistant produces.

By the time you reach that third layer you will have opinions about what deserves to persist, formed from evidence rather than from a diagram. Which is exactly when that decision should be made.
