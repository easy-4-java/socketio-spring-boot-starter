package com.corundumstudio.socketio.spring.boot;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SocketioProcessRestartTest {
    @Test
    void gracefulTerminationAllowsImmediateRestart() throws Exception {
        restartAfterTermination(false);
    }

    @Test
    void forcedTerminationAllowsImmediateRestart() throws Exception {
        restartAfterTermination(true);
    }

    private void restartAfterTermination(boolean forced) throws Exception {
        int port = SocketioTestSupport.freePort();
        Process first = launch(port);
        try {
            awaitReady(first, port);
            // Establish a live transport before termination, so this exercises bound connections.
            new SocketioTestSupport.PollingClient(port);
            if (forced) {
                first.destroyForcibly();
            } else {
                first.destroy();
            }
            assertTrue(first.waitFor(15, TimeUnit.SECONDS), "server failed to terminate");
        } finally {
            first.destroyForcibly();
            first.waitFor(5, TimeUnit.SECONDS);
        }
        Process second = launch(port);
        try {
            awaitReady(second, port);
            new SocketioTestSupport.PollingClient(port);
        } finally {
            second.destroy();
            if (!second.waitFor(15, TimeUnit.SECONDS)) {
                second.destroyForcibly();
                second.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    private Process launch(int port) throws Exception {
        Path directory = Paths.get("target", "test-process-logs");
        Files.createDirectories(directory);
        Path log = Files.createTempFile(directory, "server-", ".log");
        return new ProcessBuilder(Paths.get(System.getProperty("java.home"), "bin", "java").toString(),
                "-XX:ActiveProcessorCount=2", "-cp", System.getProperty("java.class.path"),
                SocketioProcessApplication.class.getName(), Integer.toString(port))
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
    }

    private void awaitReady(Process process, int port) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline && process.isAlive()) {
            try {
                if (SocketioTestSupport.request(port, "", null).startsWith("0{")) {
                    return;
                }
            } catch (java.io.IOException notReady) {
                Thread.sleep(100);
            }
        }
        throw new AssertionError("Socket.IO process did not start; see target/test-process-logs");
    }
}
