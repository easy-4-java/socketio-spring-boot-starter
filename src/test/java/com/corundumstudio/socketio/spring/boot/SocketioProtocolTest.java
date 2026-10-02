package com.corundumstudio.socketio.spring.boot;

import com.corundumstudio.socketio.SocketIOServer;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SocketioProtocolTest {
    @Test
    void boundHeartbeatSettingsKeepPongClientAliveAndDisconnectSilentClient() throws Exception {
        int port = SocketioTestSupport.freePort();
        try (ConfigurableApplicationContext context = SocketioTestSupport.start(port,
                "--socketio.server.ping-interval=150", "--socketio.server.ping-timeout=450")) {
            SocketIOServer server = context.getBean(SocketIOServer.class);
            CountDownLatch disconnected = new CountDownLatch(1);
            server.addDisconnectListener(client -> disconnected.countDown());
            SocketioTestSupport.PollingClient client = new SocketioTestSupport.PollingClient(port);
            assertEquals(150, client.handshake.get("pingInterval").asInt());
            assertEquals(450, client.handshake.get("pingTimeout").asInt());
            for (int i = 0; i < 6; i++) {
                assertTrue(client.poll().contains("2"));
            }
            assertFalse(disconnected.await(50, TimeUnit.MILLISECONDS));
            // Stop polling/pong: the server must remove the expired session.
            assertTrue(disconnected.await(3, TimeUnit.SECONDS));
            assertTrue(server.getAllClients().isEmpty());
        }
    }
}
