# Loyalty agents — companion project

Runnable companion to *Memory for a supervised agent system*.

A coffee-chain loyalty assistant: a supervisor routing to three specialist
agents, with working memory and long-term memory in OpenSearch.

> **Status:** fully verified end-to-end — all tests passing, all scenarios
> confirmed against a live cluster (OpenSearch 3.7.0, gpt-4o-mini, Quarkus
> 3.33.2). See CHECKS.md for details.

## 0. Unpack and enter the project

Every command below runs **from the project root** — the directory containing
`pom.xml`. Check with `ls pom.xml` before continuing.

```bash
unzip loyalty-agents.zip
cd loyalty-agents
ls pom.xml            # should print: pom.xml
```

## 1. OpenSearch

The cluster is defined in `compose-devservices.yml`. Quarkus starts it when dev
mode starts and cleans it up when dev mode ends — the container lifecycle
follows the application, and the cluster settings `ml-commons` needs on a single
node are applied by an init service in the same file.

**One manual step the first time.** The application validates its truststore at
startup, so the CA has to exist before Quarkus boots. Start the cluster once by
hand and extract it:

```bash
docker compose -f compose-devservices.yml up -d opensearch
docker cp loyalty-opensearch:/usr/share/opensearch/config/root-ca.pem ./opensearch-ca.pem
```

After that, `mvn quarkus:dev` manages the container itself. The CA is a
long-lived demo certificate — extract it once and forget it.

```bash
os() {
  curl -s --cacert ./opensearch-ca.pem \
    -u admin:L0cal-Dev-P@ssw0rd -H "Content-Type: application/json" "$@"
}
os "https://localhost:9200/_cat/plugins?v" | grep ml
```

The `os` function and the variables below live only in the terminal that
defined them.

## 2. Create a memory container

```bash
export CID=$(os -X POST "https://localhost:9200/_plugins/_ml/memory_containers/_create" \
  -d '{"name":"loyalty-assistant","description":"loyalty memory"}' \
  | jq -r '.memory_container_id')
echo "$CID"
```

**Verify the working-memory endpoint** — this path shifts between `ml-commons`
versions, and a 404 degrades to silent amnesia rather than an error:

```bash
os -o /dev/null -w "working search: %{http_code}\n" \
  -X POST "https://localhost:9200/_plugins/_ml/memory_containers/$CID/memories/working/_search" \
  -d '{"query":{"match_all":{}}}'
```

Expect **200**. Do not check long-term search yet: this container has no
embedding model and no strategies, so nothing exists behind it and the endpoint
answers 500 with `index must not be null`. That is expected until section 6.

## 3. Environment

```bash
export OPENSEARCH_USERNAME=admin
export OPENSEARCH_PASSWORD='L0cal-Dev-P@ssw0rd'
export MEMORY_CONTAINER_ID=$CID
export OPENAI_API_KEY=sk-...
```

## 4. Run

```bash
mvn quarkus:dev
```

PostgreSQL and Keycloak start automatically via Dev Services.

*(This project ships without a Maven wrapper. Use your installed `mvn`, or run
`mvn wrapper:wrapper` once to generate `./mvnw`.)*

Members: `alice` (2,450 pts, GOLD) and `bob` (180 pts, BRONZE) can sign in.
`carol` (620 pts) has no Keycloak login and exists only for the concurrency test.

```bash
token() {
  curl -s -X POST "http://localhost:8543/realms/quarkus/protocol/openid-connect/token" \
    -d "client_id=quarkus-app" -d "client_secret=secret" \
    -d "username=$1" -d "password=$1" -d "grant_type=password" | jq -r .access_token
}
export ALICE=$(token alice)
export BOB=$(token bob)

curl -s -X POST localhost:8080/chat \
  -H "Authorization: Bearer $ALICE" -H "Content-Type: application/json" \
  -H "Idempotency-Key: $(uuidgen)" \
  -d '{"message":"What can I get with my points?","sessionId":"s-1"}'
```

## 5. Tests

```bash
mvn test -Dtest=SupervisorRequestTest    # no infrastructure needed
mvn test -Dtest=SanitiserTest            # no infrastructure needed
mvn test -Dtest=RedemptionServiceTest    # needs Dev Services (Docker)
```

Start with the first two. They need no OpenSearch, no API key and no network,
and they confirm the supervisor guard and the sanitiser work.

Run the concurrency test twenty times, then once with `PESSIMISTIC_WRITE`
commented out to confirm it fails. A safety test that passes with the safety
removed is testing nothing.

## 6. Long-term memory

Register an embedding model and an LLM connector in `ml-commons`, then create
a container with `USER_PREFERENCE` and `SEMANTIC` strategies. See Step 13 of
the article. Extraction is asynchronous — allow ~15 seconds.

## Cleanup

```bash
podman stop opensearch && podman rm opensearch
# or: docker stop opensearch && docker rm opensearch
```
