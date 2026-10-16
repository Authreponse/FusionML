package com.fusionml.bytecode;

import java.util.ArrayList;
import java.util.List;

/**
 * JVM 方法/字段描述符解析与生成。纯字符扫描，无正则，无对象中间态。
 *
 * <p>加载器在两条热路径上要用它：把模组元数据里的类名换算成描述符（注册阶段），
 * 以及给注入的钩子算参数槽位（字节码阶段）。
 */
public final class Descriptors {

    private Descriptors() {
    }

    public static List<String> paramTypes(String desc) {
        List<String> out = new ArrayList<>(4);
        int i = 1;
        while (i < desc.length() && desc.charAt(i) != ')') {
            int start = i;
            while (desc.charAt(i) == '[') {
                i++;
            }
            char c = desc.charAt(i);
            if (c == 'L') {
                i = desc.indexOf(';', i) + 1;
            } else {
                i++;
            }
            out.add(desc.substring(start, i));
        }
        return out;
    }

    public static String returnType(String desc) {
        int i = desc.lastIndexOf(')');
        return desc.substring(i + 1);
    }

    public static int slots(String type) {
        char c = type.charAt(type.length() - 1);
        return (c == 'J' || c == 'D') ? 2 : 1;
    }

    public static int paramSlots(String desc) {
        int n = 0;
        for (String t : paramTypes(desc)) {
            n += slots(t);
        }
        return n;
    }

    /**
     * 把类型写法统一成描述符形式。
     *
     * <p>接受三种输入并<b>幂等</b>处理：基本类型（{@code I}）、内部名（{@code a/b/C}）、
     * 已经是描述符的形式（{@code La/b/C;} 或 {@code [I}）。
     *
     * <p>幂等这一点很关键：{@link #paramTypes} 返回的是描述符，而调用方常常拿它和
     * "内部名形式的类名"混在一起再走一遍本方法。若不判断"已经是描述符"，
     * 对象类型会被包成 {@code LLa/b/C;;} —— 分号翻倍。这种错误不会编译报错，
     * 只会在签名比对时表现为"看起来一模一样却对不上"（我们就是这么被自己的安全检查
     * 拦下了一次注入）。
     */
    public static String toDescriptor(String internalOrPrimitive) {
        if (internalOrPrimitive == null || internalOrPrimitive.isEmpty()) {
            return "";
        }
        char first = internalOrPrimitive.charAt(0);
        if (first == '[') {
            return internalOrPrimitive;                 
        }
        if (internalOrPrimitive.length() == 1) {
            return internalOrPrimitive;                 
        }
        if (first == 'L' && internalOrPrimitive.endsWith(";")) {
            return internalOrPrimitive;                 
        }
        return "L" + internalOrPrimitive + ";";
    }

    public static String methodDesc(List<String> params, String ret) {
        StringBuilder sb = new StringBuilder(32);
        sb.append('(');
        for (String p : params) {
            sb.append(toDescriptor(p));
        }
        sb.append(')').append(toDescriptor(ret));
        return sb.toString();
    }

    public static String fieldDesc(String type) {
        return toDescriptor(type);
    }

    /**
     * 判断"实际压栈的类型序列"能否安全地传给"钩子声明的形参"。
     *
     * <p>为什么不要求精确相等：钩子往往用 {@code Object} 形参来接收游戏类型，
     * 这样才能避免把加载器 jar 绑死在某个游戏版本上（编译期不引用游戏类）。
     * 把 {@code Gui} 传给 {@code Object} 形参在字节码层面完全合法，
     * 要求描述符逐字符相等会把这类正确的注入误判为"签名不匹配"而跳过。
     *
     * <p>规则保守：形参个数必须一致，返回类型必须一致，每个形参要么完全相同，
     * 要么钩子侧声明为 {@code Ljava/lang/Object;}。不做继承关系推断 ——
     * 我们手上没有类型层次信息，宁可少接受一些，也不能放过真正不兼容的签名。
     */
    public static boolean acceptsParams(String expectedDesc, String hookDesc) {
        List<String> expected = paramTypes(expectedDesc);
        List<String> actual = paramTypes(hookDesc);
        if (expected.size() != actual.size()) {
            return false;
        }
        if (!returnType(expectedDesc).equals(returnType(hookDesc))) {
            return false;
        }
        for (int i = 0; i < expected.size(); i++) {
            String want = expected.get(i);
            String have = actual.get(i);
            if (want.equals(have) || "Ljava/lang/Object;".equals(have)) {
                continue;
            }
            return false;
        }
        return true;
    }
}
