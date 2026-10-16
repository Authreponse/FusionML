package com.fusionml.launch;

import com.fusionml.mc.McRules;
import com.fusionml.util.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 版本 JSON 里 {@code arguments} 的求值。
 *
 * <p>Minecraft 从 1.13 起把启动参数也写进了版本定义，并且两种形式混用：
 * 纯字符串（{@code "--username"}），以及带规则的对象
 * （{@code {"rules":[...], "value":"-Dfoo=bar"}}，value 还可以是数组一次给多个参数）。
 * 占位符形如 {@code ${auth_player_name}}，由启动方填。
 *
 * <p>为什么坚持按版本定义求值而不是硬编码一套参数：不同版本需要的参数并不一样
 * （有的要 {@code --assetIndex}，有的还要 {@code --userProperties}、{@code --demo}），
 * 硬编码出来的命令换个版本就会缺参数，而缺参数的表现往往是"能启动但进不去世界"，
 * 排查起来非常费劲。
 */
public final class GameArguments {

    private GameArguments() {
    }

    /**
     * 求值一组参数。
     *
     * @param argsNode 版本 JSON 里的 {@code arguments.jvm} 或 {@code arguments.game}
     * @param params   占位符取值（键不带 ${}）
     * @param features 启动器特性开关
     */
    public static List<String> resolve(Object argsNode, Map<String, String> params,
                                       Map<String, Boolean> features) {
        List<String> out = new ArrayList<>(32);
        if (argsNode == null) {
            return out;
        }
        // 兼容老版本的 "minecraftArguments": "--username ${auth_player_name} ..."
        if (argsNode instanceof String s) {
            for (String part : s.split("\\s+")) {
                if (!part.isEmpty()) {
                    out.add(substitute(part, params));
                }
            }
            return out;
        }
        // 也允许直接传入已经求值过一遍的字符串数组（合并继承链时会用到）。
        if (argsNode instanceof String[] arr) {
            for (String s : arr) {
                if (s != null && !s.isEmpty()) {
                    out.add(substitute(s, params));
                }
            }
            return out;
        }
        for (Object item : Json.arr(argsNode)) {
            if (item instanceof String s) {
                out.add(substitute(s, params));
                continue;
            }
            Map<String, Object> obj = Json.obj(item);
            if (!McRules.allows(obj.get("rules"), features)) {
                continue;
            }
            Object value = obj.get("value");
            if (value instanceof List<?> list) {
                for (Object v : list) {
                    out.add(substitute(String.valueOf(v), params));
                }
            } else if (value != null) {
                out.add(substitute(String.valueOf(value), params));
            }
        }
        return out;
    }

    // 替换 ${key} 占位符。
    // 未知键保留原样而不是替换成空串：一个原样保留的占位符在日志里一眼可见，
    // 而空串只会表现为"某个参数莫名消失"，后者难查得多。
    public static String substitute(String text, Map<String, String> params) {
        if (text == null || text.indexOf("${") < 0) {
            return text;
        }
        StringBuilder sb = new StringBuilder(text.length() + 16);
        int i = 0;
        while (i < text.length()) {
            int start = text.indexOf("${", i);
            if (start < 0) {
                sb.append(text, i, text.length());
                break;
            }
            sb.append(text, i, start);
            int end = text.indexOf('}', start);
            if (end < 0) {
                sb.append(text, start, text.length());
                break;
            }
            String key = text.substring(start + 2, end);
            String value = params.get(key);
            sb.append(value != null ? value : text.substring(start, end + 1));
            i = end + 1;
        }
        return sb.toString();
    }
}
