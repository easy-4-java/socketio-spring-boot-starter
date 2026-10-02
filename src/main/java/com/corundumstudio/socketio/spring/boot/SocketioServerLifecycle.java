package com.corundumstudio.socketio.spring.boot;

import com.corundumstudio.socketio.SocketIOServer;
import org.springframework.context.SmartLifecycle;

/** 由 Spring 统一管理服务器生命周期，在监听器装配后启动，并回收启动失败的资源。 */
public class SocketioServerLifecycle implements SmartLifecycle {
    private final SocketIOServer server;
    private volatile boolean running;
    private boolean startAttempted;

    public SocketioServerLifecycle(SocketIOServer server) {
        this.server = server;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        startAttempted = true;
        try {
            server.start();
            running = true;
        } catch (Throwable failure) {
            // bind 失败时 Bean 已存在，但 Spring 不会替上游关闭 Netty 线程组。
            try {
                stop();
            } catch (Throwable cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            if (failure instanceof Error) {
                throw (Error) failure;
            }
            // Netty Future.syncUninterruptibly 可能直接抛出未声明的 BindException。
            throw new IllegalStateException("Unable to start Socket.IO server at "
                    + server.getConfiguration().getHostname() + ":"
                    + server.getConfiguration().getPort(), failure);
        }
    }

    @Override
    public synchronized void stop() {
        if (!startAttempted) {
            return;
        }
        startAttempted = false;
        running = false;
        server.stop();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        // 使用高阶段；Redis 启停顺序由 Bean 依赖及工厂集中注册订阅保证。
        return Integer.MAX_VALUE;
    }
}
