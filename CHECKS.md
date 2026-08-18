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

## Verified by full end-to-end run — Quarkus 3.33.2, OpenSearch 3.7.0, gpt-4o-mini

`mvn test -Dtest=RedemptionServiceTest` — 4/4 passing, including pessimistic lock
concurrency test (8 concurrent threads, exactly 1 CONFIRMED).

Live model run confirmed all scenarios:

| Scenario | Result | Notes |
|---|---|---|
| Recommendation (Alice, 2450 pts) | ✅ | `recommend$0$0` only; no other specialists |
| Working memory follow-up ("how about the second one?") | ✅ | Resolves with history; fails gracefully if async write hasn't flushed yet |
| Supervisor guard (Bob, 180 pts, travel voucher) | ✅ | Guard allows (180 > 150 floor); specialist invoked; `RedemptionService` returns `INSUFFICIENT_POINTS` |
| Redemption (branded travel mug) | ✅ | `REWARD: Branded travel mug / COST: 900` parsed; catalogue price 900 charged; confirmation appended |
| Small talk ("thanks!") | ✅ | Supervisor went `done` immediately — zero specialists invoked |
| Long-term extraction | ✅ | `USER_PREFERENCE` records written; `strategy_type` and `memory` fields confirmed |
| Long-term loop (preference shapes later session) | ✅ | Preference stated in s-9 appeared in `Known preferences` of s-10; travel mug absent from recommendation |
| Qute resolves `{context.tier}` on a record | ✅ | Confirmed in every request log — `Member tier: GOLD` reached the specialist |
| `@Output` parameter name matches `outputKey` | ✅ | `composedResponse` wired correctly; composer output reached the HTTP response |

## Bugs found and fixed during the run

**Infrastructure / compose:**
- `OPENSEARCH_JAVA_OPTS: -Xms1g -Xmx1g` — memory circuit breaker tripped when deploying
  the embedding model. Fixed: bumped to `-Xms2g -Xmx2g` in `compose-devservices.yml`.
- Init container race condition — the init service fires immediately after the OpenSearch
  health check passes, but the security plugin initialises a few seconds later. The curl
  command returns `OpenSearch Security not initialized` and the cluster settings are never
  applied. Workaround documented in Step 14: apply settings manually if model registration
  returns `No eligible node found`.

**Step 14 connector body (three separate issues found and fixed):**
1. `"messages": ${parameters.messages}` — the agentic memory pipeline does not pass a
   `messages` parameter. It passes `system_prompt` and `user_prompt`. Any other parameter
   name produces `parameter placeholder not filled`.
2. `embedding_dimension` is required for `TEXT_EMBEDDING` containers — omitting it returns
   `Dimension is required for TEXT_EMBEDDING`. all-MiniLM-L6-v2 → 384.
3. `post_process_function` is required — OpenAI returns `$.choices[0].message.content` but
   the pipeline reads `$.output.message.content[0].text`. Without the Painless reshape script
   the extraction fails with `Missing property in path $['output']`.

All three fixes are in the blog post HTML (Step 14) and in `compose-devservices.yml`.

## Environment

- Keycloak Dev Services provides logins `alice` and `bob` ✅
- Token endpoint path and `quarkus-app` / `secret` credentials ✅
