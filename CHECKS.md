# What is verified, and what is not

## Verified by execution — pure Java

Compiled and run on JDK 21, no framework required. 22 assertions, all passing:

- `Catalogue.cheapestCost()` returns 150
- `Catalogue.byName` is case- and whitespace-tolerant, returns null for unknown rewards
- Supervisor guard blocks redemption below 150 points, allows at exactly 150
- Supervisor guard blocks at 149 (boundary)
- Member balance reaches the supervisor prompt
- `Sanitiser` redacts instruction overrides, pseudo-tags, "you are now"
- `Sanitiser` leaves ordinary text unchanged and truncates at 2000 chars
- Proposal regex parses well-formed proposals and captures the reward name
- Parsed reward name resolves against the catalogue
- Catalogue price overrides a fabricated `COST:` in the proposal
- `CLARIFICATION_NEEDED` does not parse as a proposal
- Proposal regex handles CRLF line endings

## Verified against a live cluster — OpenSearch 3.7.0

Every silent-failure mode has been ruled out:

| Claim | Result |
|---|---|
| `POST /memories/working/_search` exists | **200** |
| `POST /memories/long-term/_search` exists | path valid; 500 `index must not be null` until a container has an embedding model and strategies — expected |
| Write body shape for working memory | accepted, returns `session_id` and `working_memory_id` |
| `term` on `namespace.session_id` matches | **1 hit** — field is `keyword`-mapped, not `text` |
| `created_time` is sortable | sort accepted |
| `_source` shape read by `MemoryService` | confirmed: `structured_data.role`, `structured_data.text`, `namespace.member_id`, `namespace.session_id` |

Real index names, for anyone writing retention policies later — note the
`default` segment comes from the container's `index_prefix`:

```
.plugins-ml-am-default-memory-working
.plugins-ml-am-default-memory-sessions
.plugins-ml-am-memory-container
```

## Not yet verified

**Runtime bugs found by scenario testing** (both fixed):
- ~~async memory writes~~ — `CompletableFuture.runAsync()` with no executor uses the
  JDK common pool, whose threads carry the wrong context classloader. `ServiceLoader`
  then resolves the wrong config factory and the REST client fails with
  `SmallRyeConfigFactory: QuarkusConfigFactory not a subtype`. **Every memory write
  silently failed**, so recall always returned empty. Fixed by injecting
  `ManagedExecutor`.
- ~~catalogue reached the specialists~~ — the supervisor planner fills agent arguments
  itself, and for a `String catalogue` parameter it emitted the literal `"String"`
  from the agent description. Specialists received `Catalogue:\nString` and invented
  rewards. Fixed by moving the catalogue into `MemberContext`, which resolves from
  the agentic scope rather than from planner output.
  **Lesson: never pass data to an agent as a String parameter — the planner will
  invent a value for it.**

**Framework wiring** (fails at build or startup):
- ~~Flyway migration matches the entities~~ — **FIXED.** `@GeneratedValue` with no
  strategy resolves to `SEQUENCE` under Hibernate 6 and expects `redemption_SEQ`,
  but `BIGSERIAL` creates `redemption_id_seq`. Startup failed with
  `missing sequence [redemption_SEQ]`. The entity now specifies
  `GenerationType.IDENTITY`, which is what `BIGSERIAL` actually provides.
- ~~truststore path~~ — **FIXED.** `quarkus.tls.<name>.trust-store.pem.certs`
  resolves against the working directory, not the classpath. The CA must sit at
  the project root; putting it in `src/main/resources` gives
  `NoSuchFileException: opensearch-ca.pem` at startup.
- ~~pom builds~~ — **FIXED.** The hand-written pom omitted
  `<maven.compiler.parameters>true</maven.compiler.parameters>`. Without it,
  parameter names are absent from the bytecode, every Qute placeholder name
  resolves to `null`, and the build fails with
  `Duplicate key null (attempted merging values 0 and 1)` from
  `AiServicesProcessor`. `quarkus create app` sets this automatically.
- ~~`MonitoredAgent` resolves~~ — **FALSE.** No such type. The package exists but
  contains `AgentListener` (interface), `AgentMonitor` (concrete class implementing
  it), `MonitoredExecution`, `AgentRequest`/`AgentResponse`. `HtmlReportGenerator`
  does not exist either. Observability is registered, not inherited. The `extends
  MonitoredAgent` clause has been removed from `LoyaltyWorkflow`.
- Note: `langchain4j-agentic` resolves as **1.11.7-beta19** — the agentic module
  is beta, so APIs move between releases
- `quarkus-langchain4j-agentic` resolves on the pinned platform version
- `io.quarkus.rest.client.reactive.ClientBasicAuth` resolves
- `quarkus.rest-client.<key>.tls-configuration-name` is accepted
- Flyway migration matches the entities under `schema-management=validate`

**Behaviour** (needs a model):
- Qute resolves `{context.tier}` on a record inside `@UserMessage`
- `@Output` parameter name matches the composer's `outputKey`
- Supervisor routes correctly and invokes nothing for small talk
- Pessimistic lock prevents double-spend under concurrency
- Long-term extraction produces `USER_PREFERENCE` records with a `memory` field
- Extraction changes a later recommendation across sessions

**Environment:**
- Keycloak Dev Services provides logins `alice` and `bob`
- Token endpoint path and `quarkus-app` / `secret` credentials
