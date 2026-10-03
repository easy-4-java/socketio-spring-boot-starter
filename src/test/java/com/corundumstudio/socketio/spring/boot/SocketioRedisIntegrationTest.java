package com.corundumstudio.socketio.spring.boot;

import com.corundumstudio.socketio.SocketIOServer;
import com.corundumstudio.socketio.store.RedisTemplateMap;
import com.corundumstudio.socketio.store.RedisTemplateStore;
import com.corundumstudio.socketio.store.RedisTemplateStoreFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import com.corundumstudio.socketio.store.RedissonStoreFactory;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.Objects;
import org.springframework.util.StringUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SocketioRedisIntegrationTest {
    private static String redisHost;
    private static String redisPort;
    private static GenericContainer<?> redisContainer;

    @BeforeAll
    static void requireRedis() {
        redisPort = System.getProperty("socketio.test.redis.port", "");
        if (StringUtils.hasText(redisPort)) {
            // 外部 Redis 由调用方管理；测试只清理自己创建的数据。
            redisHost = System.getProperty("socketio.test.redis.host", "127.0.0.1");
        } else {
            // 未提供端口时必须执行容器测试；Docker 不可用应报错，不能静默跳过。
            redisContainer = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);
            redisContainer.start();
            redisHost = redisContainer.getHost();
            redisPort = String.valueOf(redisContainer.getMappedPort(6379));
        }
    }

    @AfterAll
    static void stopRedis() {
        if (Objects.nonNull(redisContainer)) {
            redisContainer.stop();
        }
    }

    private ConfigurableApplicationContext node(int port) {
        return SocketioTestSupport.start(port, "--socketio.redis.template.enabled=true",
                "--spring.redis.host=" + redisHost, "--spring.redis.port=" + redisPort);
    }

    private ConfigurableApplicationContext node(int port, String backend) {
        if (backend.equals("template")) {
            return node(port);
        }
        return SocketioTestSupport.start(port, "--socketio.redis.redisson.enabled=true",
                "--socketio.redis.redisson.single.address=redis://"
                        + (redisHost.contains(":") ? "[" + redisHost + "]" : redisHost) + ":" + redisPort,
                "--socketio.redis.redisson.threads=2", "--socketio.redis.redisson.netty-threads=2");
    }

    @ParameterizedTest
    @ValueSource(strings = {"template", "redisson"})
    void roomUnicastReachesOnlyTargetOnAnotherNodeAndListenersStop(String backend) throws Exception {
        int portA = SocketioTestSupport.freePort();
        int portB = SocketioTestSupport.freePort();
        RedisMessageListenerContainer container = null;
        try (ConfigurableApplicationContext nodeA = node(portA, backend);
                ConfigurableApplicationContext nodeB = node(portB, backend)) {
            if (backend.equals("template")) {
                assertTrue(nodeA.getBean(SocketIOServer.class).getConfiguration().getStoreFactory()
                        instanceof RedisTemplateStoreFactory);
                container = nodeB.getBean(RedisMessageListenerContainer.class);
                assertTrue(container.isRunning());
            } else {
                assertTrue(nodeA.getBean(SocketIOServer.class).getConfiguration().getStoreFactory()
                        instanceof RedissonStoreFactory);
            }
            SocketIOServer serverB = nodeB.getBean(SocketIOServer.class);
            String targetRoom = "user:" + UUID.randomUUID();
            CountDownLatch targetJoined = new CountDownLatch(1);
            serverB.addConnectListener(client -> {
                // A production app assigns this from its authenticated user identity.
                if (serverB.getAllClients().size() == 1) {
                    client.joinRoom(targetRoom);
                    targetJoined.countDown();
                }
            });
            SocketioTestSupport.PollingClient target = new SocketioTestSupport.PollingClient(portB);
            assertTrue(targetJoined.await(2, TimeUnit.SECONDS));
            SocketioTestSupport.PollingClient bystander = new SocketioTestSupport.PollingClient(portB);
            SocketIOServer serverA = nodeA.getBean(SocketIOServer.class);
            assertTrue(serverA.getAllClients().isEmpty());
            // No local client is needed: DISPATCH carries the room and namespace to node B.
            serverA.getRoomOperations(targetRoom).sendEvent("notice", Collections.singletonMap("text", "hello"));
            String delivered = target.event("notice");
            assertTrue(delivered.contains("\"text\":\"hello\""), delivered);
            assertFalse(bystander.poll().contains("notice"));
            serverA.getRoomOperations(targetRoom).sendEvent("notice", "second");
            String second = target.event("notice");
            assertTrue(second.contains("second"), second);
            assertFalse(second.contains("hello"), "same-node echo must not redeliver the first message");
        }
        if (Objects.nonNull(container)) {
            assertFalse(container.isRunning());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"template", "redisson"})
    void roomDeliveryResumesAfterReceiverNodeRestarts(String backend) throws Exception {
        int receiverPort = SocketioTestSupport.freePort();
        String room = "restart:" + UUID.randomUUID();
        try (ConfigurableApplicationContext sender = node(SocketioTestSupport.freePort(), backend)) {
            SocketIOServer senderServer = sender.getBean(SocketIOServer.class);
            for (int round = 0; round < 2; round++) {
                // 同端口创建新接收节点，验证重启后恢复跨节点投递。
                try (ConfigurableApplicationContext receiver = node(receiverPort, backend)) {
                    CountDownLatch joined = new CountDownLatch(1);
                    receiver.getBean(SocketIOServer.class).addConnectListener(client -> {
                        client.joinRoom(room);
                        joined.countDown();
                    });
                    SocketioTestSupport.PollingClient client =
                            new SocketioTestSupport.PollingClient(receiverPort);
                    assertTrue(joined.await(5, TimeUnit.SECONDS));
                    String message = "round-" + round;
                    senderServer.getRoomOperations(room).sendEvent("restart-notice", message);
                    assertTrue(client.event("restart-notice").contains(message));
                }
            }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void redisMapsAndSessionStorePreserveTypesAndMapContracts() throws Exception {
        try (ConfigurableApplicationContext node = node(SocketioTestSupport.freePort())) {
            RedisTemplate<Object, Object> template = (RedisTemplate<Object, Object>) node.getBean("socketIoRedisTemplate");
            String name = "socketio-test:" + UUID.randomUUID();
            Map<UUID, String> map = new RedisTemplateMap<>(template, name);
            UUID key = UUID.randomUUID();
            UUID sessionId = UUID.randomUUID();
            try {
                assertTrue(map.isEmpty());
                assertNull(map.put(key, "one"));
                assertFalse(map.isEmpty());
                assertEquals("one", map.put(key, "two"));
                assertEquals(Collections.singleton(key), map.keySet());
                assertEquals("two", map.remove(key));
                assertFalse(map.containsKey(key));
                map.put(key, "three");
                map.clear();
                assertTrue(map.isEmpty());
                assertTrue(map.entrySet().isEmpty());
                RedisTemplateStore store = new RedisTemplateStore(sessionId, template);
                store.set("user", key);
                assertEquals(key, store.get("user"));
                assertTrue(store.has("user"));
                store.del("user");
                assertFalse(store.has("user"));
            } finally {
                template.delete(name);
                template.delete(sessionId.toString());
            }
        }
    }
}
