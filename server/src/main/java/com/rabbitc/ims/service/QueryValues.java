package com.rabbitc.ims.service;

import com.rabbitc.ipage.reflect.FieldDescriptor;
import com.rabbitc.ims.web.ApiException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 把请求里的字符串参数转换成字段的 Java 类型。
 * <p>
 * 查询条件里 {@code BIGINT} 列与字符串参数比较时，PostgreSQL 会直接报类型错误，
 * 所以转换必须在进 SQL 之前完成。转换失败一律 400，不让异常变成 500。
 *
 * @author 小兔糖糖OvO
 * @since 1.0
 */
final class QueryValues {

    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm[:ss]");

    private QueryValues() {
    }

    /**
     * 按字段类型转换。
     *
     * @param field 字段
     * @param raw   原始字符串
     * @return 转换后的值
     */
    static Object convert(FieldDescriptor field, String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return null;
        }
        Class<?> type = field.javaType();
        try {
            if (type == String.class) {
                return value;
            }
            if (type == Integer.class || type == int.class) {
                return Integer.valueOf(value);
            }
            if (type == Long.class || type == long.class) {
                return Long.valueOf(value);
            }
            if (type == Short.class || type == short.class) {
                return Short.valueOf(value);
            }
            if (type == BigDecimal.class) {
                return new BigDecimal(value);
            }
            if (type == Double.class || type == double.class) {
                return Double.valueOf(value);
            }
            if (type == Boolean.class || type == boolean.class) {
                // 表单/手工请求可能传「是/否」，直接 Boolean.valueOf("是") 会静默变成 false。
                if (value.equals("是") || value.equals("启用") || value.equals("1")
                        || value.equalsIgnoreCase("true") || value.equalsIgnoreCase("enabled")) {
                    return true;
                }
                if (value.equals("否") || value.equals("停用") || value.equals("0")
                        || value.equalsIgnoreCase("false") || value.equalsIgnoreCase("disabled")) {
                    return false;
                }
                throw new IllegalArgumentException("布尔值只能是 是/否、true/false 或 1/0");
            }
            if (type == LocalDate.class) {
                return LocalDate.parse(value);
            }
            if (type == LocalDateTime.class) {
                return LocalDateTime.parse(value.replace(' ', 'T'));
            }
            if (type.isEnum()) {
                // 枚举列落库为常量名（VARCHAR），直接按字符串比较即可
                return value;
            }
            return value;
        } catch (RuntimeException e) {
            throw ApiException.badRequest("字段「" + field.text() + "」的值格式不正确：" + raw);
        }
    }
}
