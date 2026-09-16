/*
 * Copyright (c) 2018 Jitse Boonstra
 */

package cn.mythicland.npclib.api.skin;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MojangSkinService {
    private static final Pattern UUID_PATTERN = Pattern.compile("\"id\"\\s*:\\s*\"([a-fA-F0-9]{32})\"");
    private static final Pattern VALUE_PATTERN = Pattern.compile("\"value\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern SIGNATURE_PATTERN = Pattern.compile("\"signature\"\\s*:\\s*\"([^\"]+)\"");
    private static final long TTL_MILLIS = 10L * 60L * 1000L;

    private final Map<String, CacheEntry> cache;
    private final Map<String, CompletableFuture<Optional<Skin>>> inFlightRequests;

    public MojangSkinService() {
        this.cache = new ConcurrentHashMap<>();
        this.inFlightRequests = new ConcurrentHashMap<>();
    }

    public Optional<Skin> fetchSkinByPlayerName(String playerName) {
        String key = playerName.toLowerCase();
        CacheEntry cached = this.cache.get(key);
        long now = System.currentTimeMillis();
        if (cached != null && cached.expiresAt() > now) return Optional.of(cached.skin());
        CompletableFuture<Optional<Skin>> request = new CompletableFuture<>();
        CompletableFuture<Optional<Skin>> existingRequest = this.inFlightRequests.putIfAbsent(key, request);
        if (existingRequest != null) return existingRequest.join();

        Optional<Skin> resolvedSkin = Optional.empty();
        try {
            Optional<String> uuid = this.fetchUuid(playerName);
            if (uuid.isPresent()) resolvedSkin = this.fetchTexture(uuid.get());
            if (resolvedSkin.isPresent()) {
                this.cache.put(
                        key,
                        new CacheEntry(resolvedSkin.get(), System.currentTimeMillis() + TTL_MILLIS)
                );
            }
            return resolvedSkin;
        } finally {
            request.complete(resolvedSkin);
            this.inFlightRequests.remove(key, request);
        }
    }

    private Optional<String> fetchUuid(String playerName) {
        try {
            String encodedName = URLEncoder.encode(playerName, StandardCharsets.UTF_8);
            String response = this.readText("https://api.mojang.com/users/profiles/minecraft/" + encodedName);
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
                    "https://sessionserver.mojang.com/session/minecraft/profile/"
                            + uuidWithoutDash + "?unsigned=false"
            );
            Matcher valueMatcher = VALUE_PATTERN.matcher(response);
            Matcher signatureMatcher = SIGNATURE_PATTERN.matcher(response);
            if (!valueMatcher.find() || !signatureMatcher.find()) return Optional.empty();
            return Optional.of(new Skin(valueMatcher.group(1), signatureMatcher.group(1)));
        } catch (Exception exception) {
            return Optional.empty();
        }
    }

    private String readText(String address) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) URI.create(address)
                .toURL()
                .openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        connection.setUseCaches(false);

        BufferedReader reader = new BufferedReader(
                new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8)
        );
        StringBuilder builder = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) builder.append(line);
        reader.close();
        connection.disconnect();
        return builder.toString();
    }

    public record CacheEntry(Skin skin, long expiresAt) {
    }
}
