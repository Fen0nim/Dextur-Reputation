package org.dextur.reputation.storage;

import com.google.gson.*;
import java.time.Instant;

/** Internal JSON payloads; record constructors enforce bounds and immutable canonical ordering. */
final class ProtectionCodec {
    private ProtectionCodec() { }
    private static final Gson GSON=new GsonBuilder().disableHtmlEscaping().registerTypeAdapter(Instant.class,new TypeAdapter<Instant>() {
        @Override public void write(com.google.gson.stream.JsonWriter writer,Instant value) throws java.io.IOException { writer.value(value.toString()); }
        @Override public Instant read(com.google.gson.stream.JsonReader reader) throws java.io.IOException { return Instant.parse(reader.nextString()); }
    }.nullSafe()).registerTypeAdapter(long.class,new ExactLong()).registerTypeAdapter(Long.class,new ExactLong())
      .registerTypeAdapter(int.class,new ExactInt()).registerTypeAdapter(Integer.class,new ExactInt()).create();
    private static String integer(com.google.gson.stream.JsonReader reader) throws java.io.IOException {
        if(reader.peek()!=com.google.gson.stream.JsonToken.NUMBER) throw new JsonParseException("Expected integer");
        String value=reader.nextString();if(!value.matches("-?(0|[1-9][0-9]*)")) throw new JsonParseException("Expected exact integer");return value;
    }
    private static final class ExactLong extends TypeAdapter<Long> {
        public void write(com.google.gson.stream.JsonWriter out,Long value) throws java.io.IOException { out.value(value); }
        public Long read(com.google.gson.stream.JsonReader in) throws java.io.IOException { try { return Long.parseLong(integer(in)); }catch(NumberFormatException error) { throw new JsonParseException("Integer overflow",error); } }
    }
    private static final class ExactInt extends TypeAdapter<Integer> {
        public void write(com.google.gson.stream.JsonWriter out,Integer value) throws java.io.IOException { out.value(value); }
        public Integer read(com.google.gson.stream.JsonReader in) throws java.io.IOException { try { return Integer.parseInt(integer(in)); }catch(NumberFormatException error) { throw new JsonParseException("Integer overflow",error); } }
    }
    static JsonElement tree(Object value) { return GSON.toJsonTree(value); }
    static String encode(Object value) { return GSON.toJson(value); }
    static <T> T decode(String text,Class<T> type) { return GSON.fromJson(text,type); }
    static <T> T decode(JsonElement tree,Class<T> type) { return GSON.fromJson(tree,type); }
}
