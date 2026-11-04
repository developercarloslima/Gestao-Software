package br.com.nh.cotacao.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class AsaasWalletIsolationTest {
    private static final String EXPECTED = "64d6a15a-15f1-4d03-884f-727889dbd518";

    @Test void validWalletEnablesOnlyMatchingTenant() throws Exception {
        try (TestWalletServer server = new TestWalletServer("{\"data\":[{\"id\":\"" + EXPECTED + "\"}]}")) {
            assertTrue(client(server.url(), EXPECTED).enabled());
            assertFalse(client(server.url(), "outra-carteira").enabled());
        }
    }

    @Test void missingWalletOrInvalidCredentialsNeverEnablesAsaas() throws Exception {
        try (TestWalletServer server = new TestWalletServer("{\"data\":[{\"id\":\"" + EXPECTED + "\"}]}")) {
            assertFalse(client("https://api-sandbox.asaas.com/v3", "").enabled());
            assertFalse(new AsaasClient(new ObjectMapper(), true, server.url(), "", "webhook-token", "test", EXPECTED).enabled());
        }
    }

    @Test void apiErrorFailsClosed() throws Exception {
        try (TestWalletServer server = new TestWalletServer("{}", 401)) {
            assertFalse(client(server.url(), EXPECTED).enabled());
        }
    }

    private AsaasClient client(String url, String expected) {
        return new AsaasClient(new ObjectMapper(), true, url, "test-subaccount-key", "test-webhook-token", "NH-Test", expected);
    }

    private static final class TestWalletServer implements AutoCloseable {
        private final HttpServer server;

        TestWalletServer(String body) throws Exception { this(body, 200); }

        TestWalletServer(String body, int status) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/wallets", exchange -> {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                try (var out = exchange.getResponseBody()) { out.write(bytes); }
            });
            server.start();
        }

        String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
        @Override public void close() { server.stop(0); }
    }
}
