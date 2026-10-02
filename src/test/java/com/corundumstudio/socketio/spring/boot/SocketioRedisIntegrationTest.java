package com.corundumstudio.socketio.spring.boot;

import com.corundumstudio.socketio.SocketIOServer;
import com.corundumstudio.socketio.store.RedisTemplateMap;
import com.corundumstudio.socketio.store.RedisTemplateStore;
import com.corundumstudio.socketio.store.RedisTemplateStoreFactory;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SocketioRedisIntegrationTest {
    private static String redisPort;

    @BeforeAll
    static void requireRedis() {
        redisPort = System.getProperty("socketio.test.redis.port", "");
        assumeTrue(StringUtils.hasText(redisPort), "Set -Dsocketio.test.redis.port to run against a dedicated Redis");
    }

    private ConfigurableApplicationContext node(int port) {
        return SocketioTestSupport.start(port, "--socketio.redis.template.enabled=true",
                "--spring.redis.host=127.0.0.1", "--spring.redis.port=" + redisPort);
    }

    private ConfigurableApplicationContext node(int port, String backend) {
        if (backend.equals("template")) {
            return node(port);
        }
        return SocketioTestSupport.start(port, "--socketio.redis.redisson.enabled=true",
                "--socketio.redis.redisson.single.address=redis://127.0.0.1:" + redisPort,
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
