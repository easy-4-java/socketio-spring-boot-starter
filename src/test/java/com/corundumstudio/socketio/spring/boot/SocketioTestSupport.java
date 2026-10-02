package com.corundumstudio.socketio.spring.boot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SocketioTestSupport {
    private SocketioTestSupport() {
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class TestApplication {
    }

    static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    static ConfigurableApplicationContext start(int port, String... extra) {
        List<String> args = new ArrayList<>(Arrays.asList(
                "--spring.main.banner-mode=off", "--socketio.server.enabled=true",
                "--socketio.server.hostname=127.0.0.1", "--socketio.server.port=" + port,
                "--socketio.server.boss-threads=1", "--socketio.server.worker-threads=2",
                "--socketio.server.socket-config.reuse-address=true",
                "--socketio.server.ping-interval=1000", "--socketio.server.ping-timeout=3000",
                "--socketio.redis.template.enabled=false", "--socketio.redis.redisson.enabled=false"));
        args.addAll(Arrays.asList(extra));
        Map<String, String> options = new LinkedHashMap<>();
        for (String arg : args) {
            options.put(arg.substring(0, arg.indexOf('=')), arg);
        }
        return new SpringApplicationBuilder(TestApplication.class)
                .web(WebApplicationType.NONE).run(options.values().toArray(new String[0]));
    }

    static String request(int port, String query, String body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(
                "http://127.0.0.1:" + port + "/socket.io/?EIO=4&transport=polling" + query).openConnection();
        connection.setConnectTimeout(2000);
        connection.setReadTimeout(6000);
        try {
            if (Objects.nonNull(body)) {
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "text/plain;charset=UTF-8");
                try (java.io.OutputStream output = connection.getOutputStream()) {
                    output.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }
            assertEquals(200, connection.getResponseCode());
            try (InputStream input = connection.getInputStream();
                    ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                }
                return new String(output.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            connection.disconnect();
        }
    }

    static final class PollingClient {
        private final int port;
        private final String query;
        final JsonNode handshake;

        PollingClient(int port) throws Exception {
            this.port = port;
            String open = request(port, "", null);
            assertTrue(open.startsWith("0{"), open);
            handshake = new ObjectMapper().readTree(open.substring(1));
            query = "&sid=" + handshake.get("sid").asText();
            send("40");
            String connected = poll();
            assertTrue(connected.contains("40"), connected);
        }

        void send(String packet) throws Exception {
            request(port, query, packet);
        }

        String poll() throws Exception {
            String packets = request(port, query, null);
            for (String packet : packets.split("\u001e")) {
                if (packet.equals("2")) {
                    send("3");
                }
            }
            return packets;
        }

        String event(String name) throws Exception {
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(6);
            while (System.nanoTime() < deadline) {
                String packets = poll();
                if (packets.contains("42[\"" + name + "\"")) {
                    return packets;
                }
            }
            throw new AssertionError("No event received: " + name);
        }
    }
}
