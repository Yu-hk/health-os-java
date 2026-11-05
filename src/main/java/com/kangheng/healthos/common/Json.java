package com.kangheng.healthos.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * 统一的 JSON 编解码入口。
 *
 * <p>把 {@code detail} / {@code payload} 这类"半结构化列"按字符串落库，
 * 避免在 JDBC 层引入 jsonb 类型处理器；读写都走同一份 {@link ObjectMapper} 配置，
 * 防止某处写入的时间格式另一处读不出来。
 */
public final class Json {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private Json() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static String write(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("JSON 序列化失败: " + value.getClass().getName(), ex);
        }
    }

    public static <T> T read(String json, Class<T> type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, type);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("JSON 反序列化失败: " + type.getName(), ex);
        }
    }

    public static <T> T readOrDefault(String json, Class<T> type, T fallback) {
        T value = read(json, type);
        return value == null ? fallback : value;
    }

    /** 读取半结构化 detail/payload 列为 Map；解析失败返回空 Map 而不是抛异常。 */
    @SuppressWarnings("unchecked")
    public static java.util.Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) {
            return java.util.Map.of();
        }
        try {
            return MAPPER.readValue(json, java.util.Map.class);
        } catch (JsonProcessingException ex) {
            return java.util.Map.of();
        }
    }

    /** 读取 JSON 数组为指定元素类型的列表；空值或格式不符时返回空列表。 */
    public static <T> java.util.List<T> readList(String json, Class<T> elementType) {
        if (json == null || json.isBlank()) {
            return java.util.List.of();
        }
        try {
            return MAPPER.readValue(json,
                    MAPPER.getTypeFactory().constructCollectionType(java.util.List.class, elementType));
        } catch (JsonProcessingException ex) {
            return java.util.List.of();
        }
    }
}
