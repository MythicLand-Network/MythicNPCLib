package cn.mythicland.npclib.api.skin;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.*;

public class MojangSkinServiceTest {
    private static final String UUID = "069a79f444e94726a5befca90e38aaf5";

    @Test
    public void writesOnlyFetchedSkinToTheStoreAndReadsItAfterRestart() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/users/profiles/minecraft/TestPlayer", exchange ->
                respond(exchange, 200, "{\"id\":\"" + UUID + "\"}"));
        server.createContext("/session/minecraft/profile/" + UUID, exchange ->
                respond(exchange, 200, "{\"properties\":[{\"value\":\"value\",\"signature\":\"signature\"}]}"));
        server.start();
        var store = mock(SkinCacheStore.class);
        var skin = new Skin("value", "signature");
        when(store.load("testplayer")).thenReturn(Optional.empty(), Optional.of(skin));
        String endpoint = "http://localhost:" + server.getAddress().getPort();
        try {
            MojangSkinService service = new MojangSkinService(null, endpoint, endpoint, endpoint);
            service.setPersistentCache(store);
            assertEquals(Optional.of(skin), service.fetchSkinByPlayerName("TestPlayer"));
            assertEquals(Optional.of(skin), service.fetchSkinByPlayerName("testplayer"));
            server.stop(0);
            MojangSkinService restarted = new MojangSkinService(null,
                    "http://127.0.0.1:1", "http://127.0.0.1:1", "http://127.0.0.1:1");
            restarted.setPersistentCache(store);
            assertEquals(Optional.of(skin), restarted.fetchSkinByPlayerName("TestPlayer"));
            verify(store, times(2)).load("testplayer");
            verify(store).save("testplayer", skin);
            verifyNoMoreInteractions(store);
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void fallsBackToMineToolsWhenMojangFailsAndPersistsSkin() throws Exception {
        List<String> requests = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/users/profiles/minecraft/TestPlayer", exchange -> {
            requests.add(exchange.getRequestURI().getPath());
            respond(exchange, 503, "");
        });
        server.createContext("/session/minecraft/profile/" + UUID, exchange -> {
            requests.add(exchange.getRequestURI().getPath());
            respond(exchange, 503, "");
        });
        server.createContext("/uuid/TestPlayer", exchange -> {
            requests.add(exchange.getRequestURI().getPath());
            respond(exchange, 200, "{\"id\":\"" + UUID + "\",\"name\":\"TestPlayer\"}");
        });
        server.createContext("/profile/" + UUID, exchange -> {
            requests.add(exchange.getRequestURI().getPath());
            respond(
                    exchange,
                    200,
                    "{\"raw\":{\"properties\":[{\"name\":\"textures\","
                            + "\"value\":\"value\",\"signature\":\"signature\"}]}}"
            );
        });
        server.start();

        Path cacheFile = Files.createTempFile("mythic-npc-skins", ".properties");
        Files.deleteIfExists(cacheFile);
        String endpoint = "http://localhost:" + server.getAddress().getPort();
        try {
            MojangSkinService service = new MojangSkinService(
                    cacheFile,
                    endpoint,
                    endpoint,
                    endpoint
            );
            assertEquals(Optional.of(new Skin("value", "signature")), service.fetchSkinByPlayerName("TestPlayer"));
            assertTrue(requests.contains("/users/profiles/minecraft/TestPlayer"));
            assertTrue(requests.contains("/uuid/TestPlayer"));
            assertTrue(requests.contains("/profile/" + UUID));

            server.stop(0);
            MojangSkinService cachedService = new MojangSkinService(
                    cacheFile,
                    "http://127.0.0.1:1",
                    "http://127.0.0.1:1",
                    "http://127.0.0.1:1"
            );
            assertEquals(Optional.of(new Skin("value", "signature")),
                    cachedService.fetchSkinByPlayerName("testplayer"));
        } finally {
            server.stop(0);
            Files.deleteIfExists(cacheFile);
        }
    }

    @Test
    public void missingOfficialProfileStillUsesMineTools() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/users/profiles/minecraft/TestPlayer", exchange -> respond(exchange, 404, ""));
        server.createContext("/uuid/TestPlayer", exchange -> respond(exchange, 200, "{\"id\":\"" + UUID + "\"}"));
        server.createContext("/profile/" + UUID, exchange -> respond(exchange, 200,
                "{\"raw\":{\"properties\":[{\"value\":\"value\",\"signature\":\"signature\"}]}}"));
        server.start();
        String endpoint = "http://localhost:" + server.getAddress().getPort();
        try {
            MojangSkinService service = new MojangSkinService(null, endpoint, endpoint, endpoint);
            assertEquals(Optional.of(new Skin("value", "signature")), service.fetchSkinByPlayerName("TestPlayer"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void chineseNamesUseStoredSkinWithoutNetworkRequests() throws Exception {
        List<String> requests = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(exchange.getRequestURI().getPath());
            respond(exchange, 500, "");
        });
        server.start();
        try {
            String endpoint = "http://localhost:" + server.getAddress().getPort();
            MojangSkinService service = new MojangSkinService(null, endpoint, endpoint, endpoint);
            SkinCacheStore store = mock(SkinCacheStore.class);
            Skin skin = new Skin("value", "signature");
            when(store.load("中文玩家")).thenReturn(Optional.of(skin));
            when(store.load("混合player")).thenReturn(Optional.empty());
            service.setPersistentCache(store);
            assertEquals(Optional.of(skin), service.fetchSkinByPlayerName("中文玩家"));
            assertEquals(Optional.empty(), service.fetchSkinByPlayerName("混合Player"));
            assertTrue(requests.isEmpty());
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void failedLookupIsNotRepeatedUntilServiceRestarts() throws Exception {
        List<String> requests = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext(
                "/", exchange -> {
                    requests.add(exchange.getRequestURI().getPath());
                    respond(exchange, 503, "");
                }
        );
        server.start();
        try {
            String endpoint = "http://localhost:" + server.getAddress().getPort();
            MojangSkinService service = new MojangSkinService(null, endpoint, endpoint, endpoint);
            assertEquals(Optional.empty(), service.fetchSkinByPlayerName("TestPlayer"));
            assertEquals(Optional.empty(), service.fetchSkinByPlayerName("testplayer"));
            assertEquals(2, requests.size());
            MojangSkinService restarted = new MojangSkinService(null, endpoint, endpoint, endpoint);
            assertEquals(Optional.empty(), restarted.fetchSkinByPlayerName("TestPlayer"));
            assertEquals(4, requests.size());
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void timeoutUsesFallbackWithoutWarningAndReportsFailureWhenBothSourcesFail() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/users/profiles/minecraft/TestPlayer", exchange -> {
            try {
                Thread.sleep(5500);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        server.createContext("/uuid/TestPlayer", exchange -> respond(exchange, 200, "{\"id\":\"" + UUID + "\"}"));
        server.createContext("/profile/" + UUID, exchange -> respond(exchange, 200,
                "{\"raw\":{\"properties\":[{\"value\":\"value\",\"signature\":\"signature\"}]}}"));
        server.start();
        List<LogRecord> records = new CopyOnWriteArrayList<>();
        Logger logger = Logger.getLogger(MojangSkinService.class.getName());
        Handler handler = new Handler() {
            @Override public void publish(LogRecord record) { records.add(record); }
            @Override public void flush() { }
            @Override public void close() { }
        };
        logger.addHandler(handler);
        try {
            String endpoint = "http://localhost:" + server.getAddress().getPort();
            MojangSkinService service = new MojangSkinService(null, endpoint, endpoint, endpoint);
            assertEquals(Optional.of(new Skin("value", "signature")), service.fetchSkinByPlayerName("TestPlayer"));
            assertTrue(records.isEmpty());
            server.stop(0);
            MojangSkinService unavailable = new MojangSkinService(null,
                    "http://127.0.0.1:1", "http://127.0.0.1:1", "http://127.0.0.1:1");
            assertEquals(Optional.empty(), unavailable.fetchSkinByPlayerName("TestPlayer"));
            assertEquals(1, records.size());
            assertTrue(records.get(0).getMessage().contains("Mojang profile"));
            assertTrue(records.get(0).getMessage().contains("MineTools"));
        } finally {
            logger.removeHandler(handler);
            server.stop(0);
        }
    }

    private static void respond(HttpExchange exchange, int statusCode, String body) throws IOException {
        byte[] response = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(statusCode, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }
}
