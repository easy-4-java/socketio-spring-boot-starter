package com.corundumstudio.socketio.spring.boot.listener;

import com.corundumstudio.socketio.AuthorizationResult;
import com.corundumstudio.socketio.HandshakeData;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

class JWTAuthorizationListenerTest {
    private final JWTAuthorizationListener listener = new JWTAuthorizationListener();

    private HandshakeData handshake(String header, String parameter) {
        DefaultHttpHeaders headers = new DefaultHttpHeaders();
        if (header != null) {
            headers.set(JWTAuthorizationListener.AUTHORIZATION_HEADER, header);
        }
        return new HandshakeData(headers,
                parameter == null ? Collections.emptyMap()
                        : Collections.singletonMap("token", Collections.singletonList(parameter)),
                null, "/", false);
    }

    @Test
    void bridgePreservesExistingTokenDecisions() {
        assertSame(AuthorizationResult.SUCCESSFUL_AUTHORIZATION,
                listener.getAuthorizationResult(handshake(" token ", null)));
        assertSame(AuthorizationResult.SUCCESSFUL_AUTHORIZATION,
                listener.getAuthorizationResult(handshake(null, "query-token")));
        assertSame(AuthorizationResult.SUCCESSFUL_AUTHORIZATION,
                listener.getAuthorizationResult(handshake("", "query-token")));
        assertSame(AuthorizationResult.FAILED_AUTHORIZATION,
                listener.getAuthorizationResult(handshake(null, null)));
        assertSame(AuthorizationResult.FAILED_AUTHORIZATION,
                listener.getAuthorizationResult(handshake(null, "  ")));
        // A whitespace-only header takes precedence, as in the original boolean contract.
        assertSame(AuthorizationResult.FAILED_AUTHORIZATION,
                listener.getAuthorizationResult(handshake("  ", "query-token")));
    }

    @Test
    void bridgeStillDispatchesToPublicBooleanOverride() {
        JWTAuthorizationListener denying = new JWTAuthorizationListener() {
            @Override
            public boolean isAuthorized(HandshakeData data) {
                return false;
            }
        };
        assertSame(AuthorizationResult.FAILED_AUTHORIZATION,
                denying.getAuthorizationResult(handshake("token", null)));
    }

    @Test
    void bridgeDoesNotTurnExceptionsIntoSuccessfulAuthorization() {
        IllegalStateException failure = new IllegalStateException("authorization failed");
        JWTAuthorizationListener throwing = new JWTAuthorizationListener() {
            @Override
            public boolean isAuthorized(HandshakeData data) {
                throw failure;
            }
        };
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> throwing.getAuthorizationResult(handshake("token", null))));
    }
}
