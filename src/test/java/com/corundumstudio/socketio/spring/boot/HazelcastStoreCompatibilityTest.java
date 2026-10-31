package com.corundumstudio.socketio.spring.boot;

import com.corundumstudio.socketio.store.HazelcastStoreFactory;
import com.corundumstudio.socketio.store.HazelcastPubSubStore;
import com.corundumstudio.socketio.store.Store;
import com.corundumstudio.socketio.store.pubsub.DispatchMessage;
import com.corundumstudio.socketio.store.pubsub.PubSubType;
import com.hazelcast.config.Config;
import com.hazelcast.config.JoinConfig;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class HazelcastStoreCompatibilityTest {
    private HazelcastInstance member;

    @BeforeEach
    void startIsolatedMember() throws Exception {
        Config config = new Config();
        config.setInstanceName("socketio-abi-" + UUID.randomUUID());
        config.setProperty("hazelcast.phone.home.enabled", "false");
        config.setProperty("hazelcast.operation.thread.count", "2");
        config.setProperty("hazelcast.operation.generic.thread.count", "2");
        config.setProperty("hazelcast.io.thread.count", "2");
        config.getNetworkConfig().setPort(0);
        config.getNetworkConfig().getInterfaces().setEnabled(true).addInterface("127.0.0.1");
        JoinConfig join = config.getNetworkConfig().getJoin();
        join.getMulticastConfig().setEnabled(false);
        join.getTcpIpConfig().setEnabled(false);
        join.getAwsConfig().setEnabled(false);
        // Hazelcast 4 introduced auto-detection; disable it when reproducing the ABI regression.
        try {
            Object autoDetection = join.getClass().getMethod("getAutoDetectionConfig").invoke(join);
            autoDetection.getClass().getMethod("setEnabled", boolean.class).invoke(autoDetection, false);
        } catch (NoSuchMethodException expectedOnHazelcast3) {
            // Hazelcast 3 has no auto-detection.
        }
        member = Hazelcast.newHazelcastInstance(config);
    }

    @AfterEach
    void stopMember() {
        if (member != null) {
            member.shutdown();
        }
    }

    @Test
    void upstreamStoreCanUseIMap() {
        HazelcastStoreFactory factory = new HazelcastStoreFactory(member);
        Store store = factory.createStore(UUID.randomUUID());
        store.set("key", "value");
        assertEquals("value", store.get("key"));
        assertTrue(store.has("key"));
        store.del("key");
        assertFalse(store.has("key"));
        assertNull(store.get("key"));
        factory.<String, String>createMap("factory-map").put("key", "factory-value");
        assertEquals("factory-value", factory.<String, String>createMap("factory-map").get("key"));
    }

    @Test
    void upstreamPubSubCanUseITopic() throws InterruptedException {
        HazelcastPubSubStore publisher = new HazelcastPubSubStore(member, member, 1L);
        HazelcastPubSubStore subscriber = new HazelcastPubSubStore(member, member, 2L);
        CountDownLatch received = new CountDownLatch(1);
        AtomicReference<DispatchMessage> delivered = new AtomicReference<>();
        subscriber.subscribe(PubSubType.DISPATCH, message -> {
            delivered.set(message);
            received.countDown();
        }, DispatchMessage.class);
        try {
            publisher.publish(PubSubType.DISPATCH, new DispatchMessage("test-room", null, "/test"));
            assertTrue(received.await(10, TimeUnit.SECONDS), "Actual Hazelcast topic must deliver the message");
            assertEquals("test-room", delivered.get().getRoom());
            assertEquals("/test", delivered.get().getNamespace());
            assertEquals(Long.valueOf(1), delivered.get().getNodeId());
        } finally {
            subscriber.unsubscribe(PubSubType.DISPATCH);
        }
    }
}
