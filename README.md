# socketio-spring-boot-starter

Spring Boot 2.x starter for netty-socketio. [简体中文](README.zh-CN.md)

The `main` source version is `io.github.easy4j:socketio-spring-boot-starter:2.0.1-SNAPSHOT`.
Baseline: Spring Boot 2.6.0, Java 8+, netty-socketio 2.0.14, Netty 4.1.130.Final.
This documentation and the issue fixes apply to `main`; other branches have separate baselines.
A source commit does not publish a Maven Central release. Build these fixes with `mvn clean install` before consuming them.

## Quick start

```xml
<!-- Set in the consuming Spring Boot parent-based application. -->
<properties>
    <netty.version>4.1.130.Final</netty.version>
</properties>
<dependency>
    <groupId>io.github.easy4j</groupId>
    <artifactId>socketio-spring-boot-starter</artifactId>
    <version>2.0.1-SNAPSHOT</version>
</dependency>
```

```yaml
socketio:
  server:
    enabled: true
    hostname: 0.0.0.0
    port: 9092
    boss-threads: 1
    worker-threads: 4
    ping-interval: 25000
    ping-timeout: 60000
    socket-config:
      reuse-address: true
```

Enable with **`socketio.server.enabled=true`**; it is disabled by default.
The old `socketio.enabled` example was incorrect. Inject `SocketIOServer` and register listeners or annotated handlers.
Spring `SmartLifecycle` starts the server after handler registration and stops it on context close.
There is one shutdown owner and failed starts release initialized Netty resources while retaining the original cause.

## Cluster and remote unicast (#6)

The default memory store is local. Choose one Redis backend, using the same Redis deployment on all nodes:

* RedisTemplate: add `org.springframework.boot:spring-boot-starter-data-redis` and enable `socketio.redis.template.enabled=true`.
* Redisson: add `org.redisson:redisson:3.17.7`, enable `socketio.redis.redisson.enabled=true`, and configure its `single.address` (or the corresponding server mode).

The prefixes are `socketio.redis.template` and `socketio.redis.redisson`, not `socket-io.cache.*` from the previous issue reply.
`main` has no Hazelcast auto-configuration. Do not enable both Redis backends.

```yaml
spring:
  redis:
    host: 127.0.0.1
    port: 6379
socketio:
  redis:
    template:
      enabled: true
    redisson:
      enabled: false
```

Or use Redisson:

```yaml
socketio:
  redis:
    template:
      enabled: false
    redisson:
      enabled: true
      server: single
      single:
        address: redis://127.0.0.1:6379
```

**`getClient(sessionId)` only looks up local connections.** Sharing a store does not create a local proxy for remote clients.
Assign a private room from the authenticated identity on the owning node, then publish to that room from any node:

```java
// authenticatedUserId must come from verified application authentication.
client.joinRoom("user:" + authenticatedUserId);
server.getRoomOperations("user:" + authenticatedUserId).sendEvent("notice", message);
```

A user room targets all that user's connections. For one connection, assign a `session:<server sessionId>` room.
Do not trust client-supplied user IDs or room names. The default authorization listener allows connections;
provide an application `AuthorizationListener`. The historical `JWTAuthorizationListener` only checks for a nonblank token, not its signature.

The RedisTemplate listener is now a Spring-managed bean, and all subscriptions are registered before accepting connections.
Polling requires sticky sessions at the load balancer. WebSocket-only removes the polling affinity requirement but still needs pub/sub.
Redis pub/sub provides online delivery, not persistence, offline delivery, or guaranteed cross-node ACK callbacks.

RedisTemplate hash encoding changed to preserve UUID types. Stop old nodes, remove only application-owned temporary Socket.IO
session/hash data, and upgrade all nodes together. Do not mix old and new nodes or clear a shared Redis database.
Redis must be trusted: JSON type metadata restores stored objects.

## Heartbeat and browser clients (#3)

`ping-interval` and `ping-timeout` are milliseconds and appear in the Engine.IO handshake.
Compatible `socket.io-client` implementations handle protocol ping/pong automatically; no application timer is required.
Engine.IO 4 (Socket.IO 3/4) uses server ping/client pong; Engine.IO 3 reverses that direction.
Custom business liveness events are separate from protocol heartbeat.

```javascript
import { io } from "socket.io-client";
const socket = io("http://localhost:9092", { transports: ["websocket"] });
socket.on("connect", () => console.log("connected", socket.id));
socket.on("disconnect", reason => console.log("disconnected", reason));
socket.on("connect_error", error => console.error(error.message));
socket.on("notice", message => console.log(message));
```

Set proxy timeouts above `ping-interval + ping-timeout`. A plain WebSocket client is not a Socket.IO client.
See [protocol and heartbeat](https://socket.io/docs/v4/how-it-works/) and [multiple nodes](https://socket.io/docs/v4/using-multiple-nodes/).

## Restart troubleshooting (#5)

Spring context close and SIGTERM use Spring's shutdown hook. SIGKILL cannot run JVM hooks;
wait for the process to exit and configure `socket-config.reuse-address=true`.
Bind failures now clean up resources. Inspect the complete cause chain, bind address, IPv4/IPv6, containers and the old process.
The original report lacks a complete stack trace, so its unique cause remains unknown;
regressions now cover the observed lifecycle defects and real process restarts.

## Build and compatibility tests (#4)

```bash
# Docker must be running: Testcontainers starts/stops an isolated Redis on a random port.
mvn clean verify
# Optional: use a dedicated external Redis without starting a container or flushing it.
mvn clean verify -Dsocketio.test.redis.host=127.0.0.1 -Dsocketio.test.redis.port=6379
# Test another published upstream version with Testcontainers.
mvn clean verify -Dnetty-socketio.version=2.0.14
```

Redis integration tests use Testcontainers 1.21.4 (`redis:7-alpine`) by default: the container is started once
for the test class, its actual host/mapped port is injected into both backends, and it is stopped after the tests.
Docker/image/startup failures fail the build instead of skipping integration tests. An external Redis is used only
when `socketio.test.redis.port` is supplied; its host defaults to `127.0.0.1`, and its lifecycle stays with the caller.
The CI matrix uses the same automatic-container path, with no fixed Redis service port.
Tests are enabled by default, and no-test builds fail. CI covers Boot 2.3.5.RELEASE/JDK 8, Boot 2.6.0/JDK 8,
and Boot 2.7.18/JDK 8, 17, 21. Manual CI accepts `netty_socketio_version` for upstream compatibility checks.
CI runs the same acceptance suite through the installed starter in `src/it/consumer`, so the matrix verifies a consuming application.
A consumer must also pin `netty.version` as shown above: its own Boot BOM overrides transitive dependency versions.
For apps without the Boot parent, import `io.netty:netty-bom:4.1.130.Final` in dependency management.
Keep the Netty BOM aligned when updating netty-socketio; the current Netty pin follows upstream 2.0.14's POM.

The tests exercise authorization API compatibility, heartbeat binding/pong/timeout, failed-start cleanup, one shutdown,
port reuse, first restart after SIGTERM/SIGKILL, two-node unicast for RedisTemplate/Redisson,
non-target isolation, delivery after receiver-node restart for both Redis backends, and Redis map/session contracts.

[Apache License 2.0](LICENSE)
