# Using the Quarkiverse OpenSearch extension instead

The main project talks to agentic memory through a MicroProfile REST Client
interface. This directory holds the alternative, using the Quarkiverse
OpenSearch extension.

## When this is the better choice

Take this route if the application will use OpenSearch for more than memory —
indexing the reward catalogue for search, storing redemption history for
analytics, anything where a typed `SearchResponse<Reward>` beats hand-parsed
JSON. You also get Dev Services, which starts a container automatically in dev
and test.

Stay with the REST Client if memory is the only thing OpenSearch does here.
Fewer dependencies, and the interface documents exactly which four endpoints
the application touches.

## What changes

**pom.xml** — add:

```xml
<dependency>
  <groupId>io.quarkiverse.opensearch</groupId>
  <artifactId>quarkus-opensearch-java-client</artifactId>
  <version>3.4.3</version>
</dependency>
```

Note this is versioned independently of the Quarkus platform. Extension 3.x
requires JDK 21 and supports only the Java client — the low-level and
high-level REST clients were removed. Use 2.x if you need those.

**application.properties** — replace the `quarkus.rest-client.opensearch-memory.*`
and `quarkus.tls.opensearch.*` entries with the extension's own configuration.
Verify the exact property names against the extension documentation for your
version.

**Code** — swap `OpenSearchMemoryClient` for
`MemoryClient-opensearch-extension.java`, and have `MemoryService` build JSON
strings rather than `Map` literals.

## The one thing to know

The typed Java API has no methods for `_plugins/_ml/*`, because plugin
endpoints are not part of the core API surface. `client.generic()` exists for
this: it executes an arbitrary method and path, and returns the response
without interpreting the status code. Added in client 2.10.0, and motivated by
exactly this problem — reaching plugin endpoints the typed API does not cover.

Two consequences worth planning for. The `Response` holds resources and must
be closed, so use try-with-resources. And the generic client does not throw on
HTTP errors unless you ask it to, hence
`withClientOptions(ClientOptions.throwOnHttpErrors())`.

## Verification status

The generic client API shown here is taken from the OpenSearch Java client
documentation and has **not** been executed in this project. The extension's
configuration property names in particular should be confirmed against the
version you resolve.
