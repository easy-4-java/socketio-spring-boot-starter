package com.corundumstudio.socketio.spring.boot;

import com.corundumstudio.socketio.HandshakeData;
import com.corundumstudio.socketio.spring.boot.listener.JWTAuthorizationListener;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JWTAuthorizationListenerTest {
    @Test
    void newAuthorizationApiPreservesHeaderAndQueryTokenChecks() {
        JWTAuthorizationListener listener = new JWTAuthorizationListener();
        HandshakeData empty = data("");
        assertFalse(listener.getAuthorizationResult(empty).isAuthorized());
        assertFalse(listener.getAuthorizationResult(data("  ")).isAuthorized());
        assertTrue(listener.getAuthorizationResult(data("query-token")).isAuthorized());
        empty.getHttpHeaders().set("X-Authorization", "header-token");
        assertTrue(listener.getAuthorizationResult(empty).isAuthorized());
    }

    private HandshakeData data(String token) {
        return new HandshakeData(new DefaultHttpHeaders(),
                Collections.singletonMap("token", Collections.singletonList(token)),
                new InetSocketAddress("127.0.0.1", 12345), "/socket.io/", false);
    }
}
