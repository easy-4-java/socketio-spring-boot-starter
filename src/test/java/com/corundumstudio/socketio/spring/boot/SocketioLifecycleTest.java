package com.corundumstudio.socketio.spring.boot;

import com.corundumstudio.socketio.SocketIOServer;
import com.corundumstudio.socketio.store.MemoryStoreFactory;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.net.ServerSocket;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class SocketioLifecycleTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SocketioServerAutoConfiguration.class))
            .withPropertyValues("socketio.server.enabled=true", "socketio.server.hostname=127.0.0.1",
                    "socketio.server.boss-threads=1", "socketio.server.worker-threads=2");

    @Test
    void contextCloseStopsStoreExactlyOnceAndPortCanBeReused() throws Exception {
        int port = SocketioTestSupport.freePort();
        CountingStore store = new CountingStore();
        runner.withBean("clientStoreFactory", CountingStore.class, () -> store,
                        definition -> definition.setDestroyMethodName(""))
                .withPropertyValues("socketio.server.port=" + port).run(context -> {
                    assertNull(context.getStartupFailure());
                    assertNotNull(context.getBean(SocketIOServer.class));
                    assertEquals(0, store.stops.get());
                });
        assertEquals(1, store.stops.get(), "server must have one shutdown owner");
        try (ServerSocket rebound = new ServerSocket(port)) {
            assertEquals(port, rebound.getLocalPort());
        }
    }

    @Test
    void bindFailureReleasesInitializedResourcesAndNextStartSucceeds() throws Exception {
        CountingStore store = new CountingStore();
        int port;
        try (ServerSocket occupied = new ServerSocket(0)) {
            port = occupied.getLocalPort();
            runner.withBean("clientStoreFactory", CountingStore.class, () -> store,
                            definition -> definition.setDestroyMethodName(""))
                    .withPropertyValues("socketio.server.port=" + port).run(context -> {
                        assertNotNull(context.getStartupFailure());
                        assertEquals(1, store.stops.get(), "failed start must release the store");
                    });
        }
        runner.withPropertyValues("socketio.server.port=" + port).run(context ->
                assertNull(context.getStartupFailure()));
    }

    static class CountingStore extends MemoryStoreFactory {
        final AtomicInteger stops = new AtomicInteger();

        @Override
        public void shutdown() {
            stops.incrementAndGet();
            super.shutdown();
        }
    }
}
