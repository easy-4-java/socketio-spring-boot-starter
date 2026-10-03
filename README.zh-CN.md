# socketio-spring-boot-starter

Spring Boot 2.x Starter，集成 netty-socketio。 [English](README.md)

`main` 当前源码版本：`io.github.easy4j:socketio-spring-boot-starter:2.0.1-SNAPSHOT`。
默认 Spring Boot 2.6.0，Java 8+，netty-socketio 2.0.14，Netty 4.1.130.Final。
其他版本分支有自己的依赖基线；本文和本次 Issue 修复针对 `main`。
源码提交不代表 Maven Central 已发布新版本；使用本次修复请先从源码执行 `mvn clean install`。

## 快速开始

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

启用开关是 **`socketio.server.enabled=true`**，默认不开启。原来的 README 中 `socketio.enabled` 示例不正确。
注入 `SocketIOServer`，注册业务监听器或使用 `@OnConnect`、`@OnEvent`。
服务器由 Spring `SmartLifecycle` 在监听器装配后启动，在 context 关闭时停止；无需自己注册 JVM shutdown hook。
若启动失败，会保留原始异常并清理已初始化的 Netty 资源。

## 集群与跨节点单推（#6）

默认 `MemoryStoreFactory` 仅支持本节点。多节点使用以下后端之一，各节点连接同一 Redis。
配置前缀是 `socketio.redis.template` / `socketio.redis.redisson`，不是旧回复中的 `socket-io.cache.*`。
当前 `main` 没有 Hazelcast 自动配置，不能仅靠 `socketio.hazelcast.enabled` 启用集群。

RedisTemplate 需要应用额外引入 `org.springframework.boot:spring-boot-starter-data-redis`：

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

Redisson 需要应用额外引入 `org.redisson:redisson:3.17.7`：

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

不要同时启用两个后端。RedisTemplate 的模板与监听容器由 Spring 初始化、启动和回收；订阅在监听端口开放前完成。

**`server.getClient(sessionId)` 只查询本节点连接。** 共享存储不会把远端客户端变成本地对象。
跨节点单推使用服务端分配的专属房间：

```java
// 在连接所属节点完成认证后，以可信用户身份分配房间。
// authenticatedUserId 必须来自业务认证结果，不能直接信任客户端提交的房间或用户 ID。
client.joinRoom("user:" + authenticatedUserId);

// 在任意节点发送，底层房间 DISPATCH 经 Redis 传递到连接所属节点。
server.getRoomOperations("user:" + authenticatedUserId)
      .sendEvent("notice", message);
```

用户房间会发送给该用户的所有在线连接；只针对一个连接时，使用 `session:<服务端 sessionId>` 专属房间。
一个房间内的客户端应属于预期接收者。默认授权监听器允许连接，生产应用必须提供自己的 `AuthorizationListener`。
历史 `JWTAuthorizationListener` 只检查 token 非空，不执行 JWT 签名验证。

HTTP polling 多节点部署需要负载均衡粘性会话；WebSocket-only 不需要 polling 粘性，但仍需共享 pub/sub。
Redis pub/sub 是在线投递，不提供持久化、离线消息或跨节点 ACK 回调保证。

RedisTemplate 本次修复会保留 UUID 类型并修正 hash key 编码。已有 RedisTemplate 集群应停止全部旧节点，
清理应用专属的 Socket.IO 临时会话/hash 数据后一起升级；不要混用新旧节点或清空共用 Redis 数据库。
Redis 必须处于可信访问边界，JSON 类型元数据用于恢复会话对象。

## 心跳与前端（#3）

`ping-interval` / `ping-timeout` 的单位是毫秒，通过 Engine.IO 握手传给客户端。
使用兼容的 Socket.IO 客户端时，协议层自动处理 ping/pong，**不需要自己写 `setInterval` 心跳消息**。
Engine.IO 4（Socket.IO 3/4）由服务器发送 ping、客户端回复 pong；Engine.IO 3 的方向相反。
业务存活检测事件与协议心跳是两件事，可以根据业务需要另行定义。

```javascript
import { io } from "socket.io-client";

const socket = io("http://localhost:9092", {
  transports: ["websocket"]
});
socket.on("connect", () => console.log("connected", socket.id));
socket.on("disconnect", reason => console.log("disconnected", reason));
socket.on("connect_error", error => console.error(error.message));
socket.on("notice", message => console.log(message));
// ping/pong 由 socket.io-client 自动维护。
```

代理超时应大于 `ping-interval + ping-timeout`。普通 WebSocket 客户端不等于 Socket.IO 客户端。
参考：[协议与心跳](https://socket.io/docs/v4/how-it-works/)、[多节点部署](https://socket.io/docs/v4/using-multiple-nodes/)。

## 重启与排障（#5）

| 现象 | 处理 |
|---|---|
| Spring context 关闭或 SIGTERM | Spring 统一停止服务器，端口和线程组随之释放 |
| SIGKILL / `kill -9` | JVM 无法执行退出钩子；等待进程真正退出后再启动，使用 `reuse-address: true` |
| 端口占用、绑定失败 | 启动失败会回收已初始化资源；从异常链检查实际 `BindException` |
| 端口看似空闲但绑定失败 | 检查完整异常、监听地址、容器网络、IPv4/IPv6 与上一个进程是否已退出 |

历史 Issue #5 没有完整异常栈，无法确认其唯一原因；本次覆盖已发现的生命周期缺陷并加入真实进程重启回归。

## 构建与兼容性验证（#4）

```bash
# Docker 需已启动；Testcontainers 自动创建和销毁独立 Redis，使用随机映射端口：
mvn clean verify
# 可选：使用专供测试的外部 Redis，不启动容器、不清空数据库：
mvn clean verify -Dsocketio.test.redis.host=127.0.0.1 -Dsocketio.test.redis.port=6379
# 使用 Testcontainers 验证另一个已发布的上游版本：
mvn clean verify -Dnetty-socketio.version=2.0.14
```

Redis 集成测试默认使用 Testcontainers 1.21.4 的 `redis:7-alpine` 容器，按测试类启动一次，
将实际 host 和动态映射端口传给两种后端，并在测试结束后销毁。Docker 不可用、镜像拉取或启动失败会使测试失败，不会静默跳过。
指定 `socketio.test.redis.port` 时使用外部 Redis，host 默认 `127.0.0.1`，可通过 `socketio.test.redis.host` 修改；外部实例由调用方管理。
CI 也使用同一自动容器路径，不再依赖固定端口的 Redis service；Surefire 默认执行测试。
CI 覆盖 Boot 2.3.5.RELEASE / JDK 8、Boot 2.6.0 / JDK 8、Boot 2.7.18 / JDK 8、17、21。
手动运行 CI 可通过 `netty_socketio_version` 指定待适配的上游版本。
CI 通过 `src/it/consumer` 引入已安装的 Starter jar 后运行同一验收测试，验证实际消费方。
消费方也必须设置上面的 `netty.version`，因为其自己的 Spring Boot BOM 会覆盖传递依赖版本。
不继承 Spring Boot parent 的应用应在 dependencyManagement 中导入 `io.netty:netty-bom:4.1.130.Final`。
版本升级需验证 Netty BOM 的一致性，不能只改一个 jar。当前 Netty 版本与上游 2.0.14 的 POM 对齐。

测试覆盖：授权 API 适配、配置绑定与 ping/pong 超时、启动失败回收、单次关闭、端口复用、
SIGTERM/SIGKILL 后首次重启、RedisTemplate/Redisson 两节点房间单推、非目标连接隔离、两种后端接收节点重启后恢复投递、Redis Map/session 语义。

[Apache License 2.0](LICENSE)
