package org.dextur.reputation.storage;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.dextur.reputation.api.ReputationSnapshot;
import org.dextur.reputation.domain.PlayerLanguage;

/** Strict, bounded by the caller, versioned checksummed JSON. Hash is integrity, not authentication. */
public final class SnapshotCodec {
    public record Document(long generation, StorageSnapshot snapshot) { }
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    public byte[] encode(long generation, StorageSnapshot snapshot) {
        JsonObject data = new JsonObject();
        JsonArray profiles = new JsonArray(), preferences = new JsonArray();
        for (var p : snapshot.profiles()) {
            JsonObject row = new JsonObject();
            row.addProperty("playerId", p.playerId().toString()); row.addProperty("scope", p.scopeId());
            row.addProperty("community", p.communityScore()); row.addProperty("system", p.systemScore());
            row.addProperty("visible", p.visibleScore()); row.addProperty("tier", p.tier()); row.addProperty("revision", p.revision());
            row.addProperty("createdAt", p.createdAt().toString()); row.addProperty("updatedAt", p.updatedAt().toString());
            profiles.add(row);
        }
        for (var p : snapshot.preferences()) {
            JsonObject row = new JsonObject(); row.addProperty("playerId", p.playerId().toString());
            row.addProperty("language", p.language().name()); row.addProperty("updatedAt", p.updatedAt().toString()); preferences.add(row);
        }
        data.add("profiles", profiles); data.add("preferences", preferences);
        JsonObject document = new JsonObject(); document.addProperty("formatVersion", 1); document.addProperty("generation", generation);
        document.add("data", data); document.addProperty("sha256", digest("1\n" + generation + "\n" + GSON.toJson(data)));
        return GSON.toJson(document).getBytes(StandardCharsets.UTF_8);
    }
    public Document decode(byte[] bytes) throws IOException {
        try (JsonReader reader = new JsonReader(new StringReader(new String(bytes, StandardCharsets.UTF_8)))) {
            reader.setStrictness(Strictness.STRICT);
            JsonObject doc = read(reader, 0).getAsJsonObject();
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new IOException("Trailing JSON data");
            keys(doc, "formatVersion", "generation", "data", "sha256");
            if (number(doc, "formatVersion") != 1) throw new IOException("Unsupported JSON format");
            long generation = number(doc, "generation");
            if (generation < 0) throw new IOException("Invalid generation");
            JsonObject data = doc.getAsJsonObject("data"); keys(data, "profiles", "preferences");
            List<ReputationSnapshot> profiles = new ArrayList<>(); List<StorageSnapshot.Preference> preferences = new ArrayList<>();
            for (var item : data.getAsJsonArray("profiles")) {
                JsonObject p = item.getAsJsonObject();
                keys(p, "playerId", "scope", "community", "system", "visible", "tier", "revision", "createdAt", "updatedAt");
                profiles.add(new ReputationSnapshot(UUID.fromString(text(p, "playerId")), text(p, "scope"), number(p,"community"), number(p,"system"),
                        number(p,"visible"), text(p,"tier"), number(p,"revision"), Instant.parse(text(p,"createdAt")), Instant.parse(text(p,"updatedAt"))));
            }
            for (var item : data.getAsJsonArray("preferences")) {
                JsonObject p = item.getAsJsonObject(); keys(p, "playerId", "language", "updatedAt");
                preferences.add(new StorageSnapshot.Preference(UUID.fromString(text(p,"playerId")), PlayerLanguage.parse(text(p,"language")), Instant.parse(text(p,"updatedAt"))));
            }
            StorageSnapshot snapshot = new StorageSnapshot(profiles, preferences);
            // Verify canonical logical content; JSON field/row order is not significant.
            JsonObject canonical = JsonParser.parseString(new String(encode(generation, snapshot), StandardCharsets.UTF_8)).getAsJsonObject();
            if (!MessageDigest.isEqual(text(doc,"sha256").getBytes(StandardCharsets.US_ASCII), text(canonical,"sha256").getBytes(StandardCharsets.US_ASCII))) throw new IOException("JSON checksum mismatch");
            return new Document(generation, snapshot);
        } catch (RuntimeException e) { throw new IOException("Invalid storage JSON; preserve files and use explicit recovery", e); }
    }
    public String checksum(StorageSnapshot snapshot) { return digest(new String(encode(0, snapshot), StandardCharsets.UTF_8)); }
    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static void keys(JsonObject object, String... names) throws IOException {
        if (!object.keySet().equals(Set.of(names))) throw new IOException("Unexpected JSON fields");
    }
    private static String text(JsonObject object, String key) throws IOException {
        var v=object.get(key); if (!(v instanceof JsonPrimitive p) || !p.isString()) throw new IOException("Expected JSON string: "+key);
        return p.getAsString();
    }
    private static long number(JsonObject object, String key) throws IOException {
        var v=object.get(key); if (!(v instanceof JsonPrimitive p) || !p.isNumber() || !p.getAsString().matches("-?(0|[1-9][0-9]*)")) throw new IOException("Expected integer: "+key);
        try { return Long.parseLong(p.getAsString()); } catch (NumberFormatException e) { throw new IOException("Integer overflow",e); }
    }
    private static JsonElement read(JsonReader reader, int depth) throws IOException {
        if (depth>16) throw new IOException("JSON nesting limit");
        return switch(reader.peek()) {
            case BEGIN_OBJECT -> {
                reader.beginObject(); JsonObject object=new JsonObject();
                while(reader.hasNext()) { String name=reader.nextName(); if(object.has(name)) throw new IOException("Duplicate JSON field"); object.add(name,read(reader,depth+1)); }
                reader.endObject(); yield object;
            }
            case BEGIN_ARRAY -> {
                reader.beginArray(); JsonArray array=new JsonArray(); while(reader.hasNext()) array.add(read(reader,depth+1)); reader.endArray(); yield array;
            }
            case STRING -> new JsonPrimitive(reader.nextString());
            case NUMBER -> new JsonPrimitive(new java.math.BigDecimal(reader.nextString()));
            default -> throw new IOException("Unexpected JSON value");
        };
    }
}
