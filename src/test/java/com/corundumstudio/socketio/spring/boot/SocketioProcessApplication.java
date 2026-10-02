package com.corundumstudio.socketio.spring.boot;

import java.util.concurrent.CountDownLatch;

/** 子进程夹具：使用真实 Spring shutdown hook 验证 SIGTERM / SIGKILL 后重启。 */
public final class SocketioProcessApplication {
    private SocketioProcessApplication() {
    }

    public static void main(String[] args) throws Exception {
        SocketioTestSupport.start(Integer.parseInt(args[0]));
        new CountDownLatch(1).await();
    }
}
