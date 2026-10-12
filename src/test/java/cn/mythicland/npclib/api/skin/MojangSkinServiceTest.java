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

    private static void respond(HttpExchange exchange, int statusCode, String body) throws IOException {
        byte[] response = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(statusCode, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }
}
