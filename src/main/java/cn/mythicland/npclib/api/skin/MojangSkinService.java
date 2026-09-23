/*
 * Copyright (c) 2018 Jitse Boonstra
 */

package cn.mythicland.npclib.api.skin;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MojangSkinService {
    private static final String DEFAULT_MOJANG_API_ROOT = "https://api.mojang.com";
    private static final String DEFAULT_SESSION_SERVER_ROOT = "https://sessionserver.mojang.com";
    private static final String DEFAULT_MINE_TOOLS_API_ROOT = "https://api.minetools.eu";
    private static final Pattern UUID_PATTERN = Pattern.compile("\"id\"\\s*:\\s*\"([a-fA-F0-9]{32})\"");
    private static final Pattern VALUE_PATTERN = Pattern.compile("\"value\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern SIGNATURE_PATTERN = Pattern.compile("\"signature\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern MINE_TOOLS_VALUE_PATTERN = Pattern.compile(
            "\"raw\"\\s*:\\s*\\{.*?\"value\"\\s*:\\s*\"([^\"]+)\"",
            Pattern.DOTALL
    );
    private static final Pattern MINE_TOOLS_SIGNATURE_PATTERN = Pattern.compile(
            "\"raw\"\\s*:\\s*\\{.*?\"signature\"\\s*:\\s*\"([^\"]+)\"",
            Pattern.DOTALL
    );
    private static final long TTL_MILLIS = 10L * 60L * 1000L;

    private final Map<String, CacheEntry> cache;
    private final Map<String, Skin> lastKnownSkins;
    private final Map<String, CompletableFuture<Optional<Skin>>> inFlightRequests;
    private final Path cacheFile;
    private final String mojangApiRoot;
    private final String sessionServerRoot;
    private final String mineToolsApiRoot;

    public MojangSkinService() {
        this(null);
    }

    public MojangSkinService(Path cacheFile) {
        this(
                cacheFile,
                DEFAULT_MOJANG_API_ROOT,
                DEFAULT_SESSION_SERVER_ROOT,
                DEFAULT_MINE_TOOLS_API_ROOT
        );
    }

    MojangSkinService(
            Path cacheFile,
            String mojangApiRoot,
            String sessionServerRoot,
            String mineToolsApiRoot
    ) {
        this.cache = new ConcurrentHashMap<>();
        this.lastKnownSkins = new ConcurrentHashMap<>();
        this.inFlightRequests = new ConcurrentHashMap<>();
        this.cacheFile = cacheFile;
        this.mojangApiRoot = trimTrailingSlash(mojangApiRoot);
        this.sessionServerRoot = trimTrailingSlash(sessionServerRoot);
        this.mineToolsApiRoot = trimTrailingSlash(mineToolsApiRoot);
        this.loadPersistentCache();
    }

    public Optional<Skin> fetchSkinByPlayerName(String playerName) {
        if (playerName == null || playerName.isBlank()) return Optional.empty();
        String normalizedPlayerName = playerName.trim();
        String key = normalizedPlayerName.toLowerCase(Locale.ROOT);
        CacheEntry cached = this.cache.get(key);
        long now = System.currentTimeMillis();
        if (cached != null && cached.expiresAt() > now) return Optional.of(cached.skin());
        CompletableFuture<Optional<Skin>> request = new CompletableFuture<>();
        CompletableFuture<Optional<Skin>> existingRequest = this.inFlightRequests.putIfAbsent(key, request);
        if (existingRequest != null) return existingRequest.join();

        Optional<Skin> resolvedSkin = Optional.empty();
        try {
            resolvedSkin = this.fetchOfficialSkin(normalizedPlayerName)
                    .or(() -> this.fetchMineToolsSkin(normalizedPlayerName));
            if (resolvedSkin.isPresent()) {
                Skin skin = resolvedSkin.get();
                this.lastKnownSkins.put(key, skin);
                this.cache.put(
                        key,
                        new CacheEntry(skin, System.currentTimeMillis() + TTL_MILLIS)
                );
                this.persistCache();
            } else {
                Skin lastKnownSkin = this.lastKnownSkins.get(key);
                if (lastKnownSkin != null) resolvedSkin = Optional.of(lastKnownSkin);
            }
            return resolvedSkin;
        } finally {
            request.complete(resolvedSkin);
            this.inFlightRequests.remove(key, request);
        }
    }

    private Optional<Skin> fetchOfficialSkin(String playerName) {
        Optional<String> uuid = this.fetchUuid(playerName);
        return uuid.flatMap(this::fetchTexture);
    }

    private Optional<String> fetchUuid(String playerName) {
        try {
            String encodedName = URLEncoder.encode(playerName, StandardCharsets.UTF_8);
            String response = this.readText(
                    this.mojangApiRoot + "/users/profiles/minecraft/" + encodedName
            );
            Matcher matcher = UUID_PATTERN.matcher(response);
            if (!matcher.find()) return Optional.empty();
            return Optional.of(matcher.group(1));
        } catch (Exception exception) {
            return Optional.empty();
        }
    }

    private Optional<Skin> fetchTexture(String uuidWithoutDash) {
        try {
            String response = this.readText(
                    this.sessionServerRoot + "/session/minecraft/profile/"
                            + uuidWithoutDash + "?unsigned=false"
            );
            return this.extractSkin(response, VALUE_PATTERN, SIGNATURE_PATTERN);
        } catch (Exception exception) {
            return Optional.empty();
        }
    }

    private Optional<Skin> fetchMineToolsSkin(String playerName) {
        try {
            String encodedName = URLEncoder.encode(playerName, StandardCharsets.UTF_8);
            String uuidResponse = this.readText(this.mineToolsApiRoot + "/uuid/" + encodedName);
            Matcher uuidMatcher = UUID_PATTERN.matcher(uuidResponse);
            if (!uuidMatcher.find()) return Optional.empty();

            String profileResponse = this.readText(
                    this.mineToolsApiRoot + "/profile/" + uuidMatcher.group(1)
            );
            return this.extractSkin(
                    profileResponse,
                    MINE_TOOLS_VALUE_PATTERN,
                    MINE_TOOLS_SIGNATURE_PATTERN
            );
        } catch (Exception exception) {
            return Optional.empty();
        }
    }

    private Optional<Skin> extractSkin(
            String response,
            Pattern valuePattern,
            Pattern signaturePattern
    ) {
        Matcher valueMatcher = valuePattern.matcher(response);
        Matcher signatureMatcher = signaturePattern.matcher(response);
        if (!valueMatcher.find() || !signatureMatcher.find()) return Optional.empty();
        return Optional.of(new Skin(valueMatcher.group(1), signatureMatcher.group(1)));
    }

    private String readText(String address) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) URI.create(address)
                .toURL()
                .openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        connection.setUseCaches(false);
        int responseCode = connection.getResponseCode();
        if (responseCode < 200 || responseCode >= 300) {
            connection.disconnect();
            throw new IOException("HTTP " + responseCode);
        }

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8)
        )) {
            StringBuilder builder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) builder.append(line);
            return builder.toString();
        } finally {
            connection.disconnect();
        }
    }

    private void loadPersistentCache() {
        if (this.cacheFile == null || !Files.isRegularFile(this.cacheFile)) return;
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(this.cacheFile, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException exception) {
            return;
        }

        long expiresAt = System.currentTimeMillis() + TTL_MILLIS;
        for (String key : properties.stringPropertyNames()) {
            String encodedSkin = properties.getProperty(key);
            int separator = encodedSkin.indexOf('|');
            if (separator <= 0 || separator == encodedSkin.length() - 1) continue;
            Skin skin = new Skin(
                    encodedSkin.substring(0, separator),
                    encodedSkin.substring(separator + 1)
            );
            this.lastKnownSkins.put(key, skin);
            this.cache.put(key, new CacheEntry(skin, expiresAt));
        }
    }

    private synchronized void persistCache() {
        if (this.cacheFile == null) return;
        Properties properties = new Properties();
        this.lastKnownSkins.forEach((key, skin) ->
                properties.setProperty(key, skin.value() + "|" + skin.signature())
        );

        Path parent = this.cacheFile.getParent();
        Path temporaryFile = this.cacheFile.resolveSibling(this.cacheFile.getFileName() + ".tmp");
        try {
            if (parent != null) Files.createDirectories(parent);
            try (Writer writer = Files.newBufferedWriter(temporaryFile, StandardCharsets.UTF_8)) {
                properties.store(writer, null);
            }
            try {
                Files.move(
                        temporaryFile,
                        this.cacheFile,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE
                );
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporaryFile, this.cacheFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            try {
                Files.deleteIfExists(temporaryFile);
            } catch (IOException ignored) {
            }
        }
    }

    private static String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    public record CacheEntry(Skin skin, long expiresAt) {
    }
}
