package com.fusionml.meta;

import java.util.Locale;

/** 一条模组依赖声明（三端信息统一成这个形状）。 */
public record Dependency(String modId, boolean mandatory, String versionRange, Ordering ordering,
                         Side side, String reason) {

    /** 加载顺序约束。 */
    public enum Ordering {
        NONE, BEFORE, AFTER;

        public static Ordering of(String s) {
            if (s == null) {
                return NONE;
            }
            return switch (s.trim().toUpperCase(Locale.ROOT)) {
                case "BEFORE" -> BEFORE;
                case "AFTER" -> AFTER;
                default -> NONE;
            };
        }
    }

    /** 生效侧。 */
    public enum Side {
        BOTH, CLIENT, SERVER;

        public static Side of(String s) {
            if (s == null) {
                return BOTH;
            }
            return switch (s.trim().toUpperCase(Locale.ROOT)) {
                case "CLIENT" -> CLIENT;
                case "SERVER" -> SERVER;
                default -> BOTH;
            };
        }

        /** 在当前运行侧下是否需要加载。 */
        public boolean appliesTo(boolean client) {
            return this == BOTH || (client ? this == CLIENT : this == SERVER);
        }
    }

    public static Dependency required(String modId, String range) {
        return new Dependency(modId, true, range, Ordering.NONE, Side.BOTH, null);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(modId);
        if (versionRange != null && !versionRange.isBlank() && !"*".equals(versionRange)) {
            sb.append(' ').append(versionRange);
        }
        if (!mandatory) {
            sb.append("（可选）");
        }
        if (ordering != Ordering.NONE) {
            sb.append(" [").append(ordering).append(']');
        }
        if (side != Side.BOTH) {
            sb.append(" {").append(side).append('}');
        }
        return sb.toString();
    }
}
