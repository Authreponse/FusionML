package com.fusionml.mixin;

import com.fusionml.util.Json;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 把各端的 Mixin 配置注册进同一个 Mixin 服务。
 *
 * <p>这是"一个进程里三端同时生效"的落点，也是整件事的分水岭。
 * 关键事实（用真实 jar 反查得到，不是推测）：26.3 的 Fabric 与 NeoForge 用的是
 * <b>同一份 Mixin 实现</b>（{@code net.fabricmc:sponge-mixin:0.17.4+mixin.0.8.7}），
 * 所以不存在"两套 Mixin 互相冲突"；而 Mixin 0.8.7 原生提供
 * {@code Mixins.addConfiguration(String, IMixinConfigSource)}，能标注配置来源，
 * 正是多平台共存需要的接口。
 *
 * <p><b>为什么必须有"校验"而不是"调用没抛异常就算成功"</b>：
 * {@code Mixins.addConfiguration} 内部是
 * <pre>
 *   Config c = Config.create(name, env, source);   // 失败时抛异常，被内部 catch 掉，c = null
 *   registerConfiguration(c);                      // 参数为 null 直接 return
 * </pre>
 * 也就是说配置加载失败<b>不会抛给调用者</b>，只在 Mixin 自己的日志里留一行英文。
 * 我们第一次实测就是这样：日志写着"11/11 条已提交"，而服务里实际是 0 条。
 * 所以注册必须以"服务内的配置集合是否真的多出这一条"为准，并用我们自己的解析器
 * （{@link #validate}）给出人能看懂的原因。
 *
 * <p>实现走反射而不是编译期依赖 sponge-mixin：我们的加载器要能在没有 Mixin 的环境里
 * 独立运行（安装器、基准测试、自检都靠它），一旦 import 了 {@code org.spongepowered.*}
 * 就绑死了。
 */
public final class FusionMixinRegistry {

    private static final String MIXINS = "org.spongepowered.asm.mixin.Mixins";
    private static final String ENVIRONMENT = "org.spongepowered.asm.mixin.MixinEnvironment";
    /** Mixin 配置里会列混入类的三个键（plugin/refmap 等不影响类是否可解析）。 */
    private static final String[] MIXIN_KEYS = {"mixins", "client", "server"};

    private FusionMixinRegistry() {
    }

    /** 一次注册的结果：每个计数都来自"服务内实际状态"，不是"调用没抛异常"。 */
    public record Result(int requested, int verified, int alreadyPresent, int failed,
                         int knownConfigs, List<String> notes) {
        public String summary() {
            return "请求注册 " + requested + " 条，生效 " + verified + " 条（已在服务内 "
                    + alreadyPresent + "、失败 " + failed + "），服务内当前共 " + knownConfigs + " 条";
        }
    }

    /** 探查到的一个 Mixin 服务候选（同一个类被不同类加载器各加载一份时会有多个）。 */
    private record Candidate(ClassLoader loader, Class<?> mixins, int known, String tag) {
    }

    public static boolean available(ClassLoader cl) {
        return resolve(cl) != null;
    }

    /** 服务内当前已注册的配置数量（用于验证"两边确实在同一个服务里"）。 */
    public static int knownConfigs(ClassLoader cl) {
        Class<?> mixins = resolve(cl);
        return mixins == null ? 0 : names(mixins).size();
    }

    /** 列出服务内已注册配置的名字，便于肉眼核对两边是否都在。 */
    public static List<String> listConfigs(ClassLoader cl) {
        Class<?> mixins = resolve(cl);
        return mixins == null ? List.of() : new ArrayList<>(names(mixins));
    }

    /**
     * 在若干候选类加载器里挑出"真正在跑的那个 Mixin 服务"，再往里注册。
     *
     * <p>为什么不能写死一个类加载器：宿主平台代码、我们的代码、平台类加载器三者对
     * {@code org.spongepowered.asm.mixin.Mixins} 的解析结果可能不是同一个类对象。
     * 类对象不同 → 静态字段（配置集合）不同 → 往错的那份里注册就是
     * "提交成功但毫无效果"。判据很干脆：宿主平台自己注册的配置一定在它用的那份服务里，
     * 所以<b>谁的配置集合非空，谁就是活的</b>。
     */
    public static Result registerBest(Collection<ClassLoader> candidates, Collection<String> configs,
                                     Consumer<String> log) {
        List<Candidate> found = new ArrayList<>(4);
        Set<Integer> seenClasses = new LinkedHashSet<>();
        for (ClassLoader cl : candidates) {
            if (cl == null) {
                continue;
            }
            Class<?> mixins = resolve(cl);
            if (mixins == null) {
                continue;
            }
            // 同一个类对象只算一次：不同类加载器可能都解析到同一份（父加载器优先的情形）。
            if (!seenClasses.add(System.identityHashCode(mixins))) {
                continue;
            }
            found.add(new Candidate(cl, mixins, names(mixins).size(), describe(cl)));
        }
        if (found.isEmpty()) {
            log.accept("Mixin 服务候选：一个都没有（当前进程里没有可用的 Mixin 实现）");
            return new Result(configs.size(), 0, 0, configs.size(), 0,
                    List.of("没有找到可用的 Mixin 服务"));
        }
        found.sort((a, b) -> Integer.compare(b.known(), a.known()));
        Candidate best = found.get(0);
        for (Candidate c : found) {
            log.accept("Mixin 服务候选：" + c.tag() + " 已注册 " + c.known() + " 条"
                    + (c == best && c.known() > 0 ? "  ← 采用" : ""));
        }
        return register(best.loader(), configs, log);
    }

    /** 把一批配置注册进指定类加载器里的 Mixin 服务，并逐条校验是否真的进去了。 */
    public static Result register(ClassLoader cl, Collection<String> configs, Consumer<String> log) {
        List<String> notes = new ArrayList<>(4);
        if (configs.isEmpty()) {
            return new Result(0, 0, 0, 0, knownConfigs(cl), notes);
        }
        Class<?> mixins = resolve(cl);
        if (mixins == null) {
            notes.add("类加载器 " + describe(cl) + " 里没有 Mixin 服务");
            return new Result(configs.size(), 0, 0, configs.size(), 0, notes);
        }
        Method add;
        try {
            add = mixins.getMethod("addConfiguration", String.class);
        } catch (Throwable t) {
            notes.add("Mixin 实现里没有 addConfiguration(String)：" + t.getClass().getSimpleName());
            return new Result(configs.size(), 0, 0, configs.size(), names(mixins).size(), notes);
        }

        Set<String> known = names(mixins);
        int verified = 0;
        int already = 0;
        int failed = 0;
        for (String cfg : configs) {
            if (known.contains(cfg)) {
                already++;
                continue;
            }
            String error = null;
            try {
                add.invoke(null, cfg);
            } catch (Throwable t) {
                error = rootMessage(t);
                // Mixin 在这里抛的异常信息很"表面"（例如"资源无效或读不了"），
                // 我们自己的解析器能把原因说清楚，所以两条一起给出来。
                for (String reason : validate(cl, cfg)) {
                    notes.add(cfg + "：" + reason);
                }
            }
            if (error == null) {
                Set<String> after = names(mixins);
                if (after.contains(cfg)) {
                    known = after;
                    verified++;
                    continue;
                }
                // 走到这里说明 Mixin 内部把失败吞掉了。用我们自己的解析器给出真实原因。
                error = "Mixin 已接收但配置未进入服务（内部加载失败被静默吞掉）";
                for (String reason : validate(cl, cfg)) {
                    notes.add(cfg + "：" + reason);
                }
            }
            failed++;
            notes.add("注册失败 " + cfg + "：" + error);
        }
        int total = names(mixins).size();
        log.accept("Mixin 配置注册：" + verified + " 条生效、" + already + " 条已存在、"
                + failed + " 条失败，服务内当前共 " + total + " 条");
        return new Result(configs.size(), verified, already, failed, total, notes);
    }

    /**
     * 用我们自己的解析器判断一条 Mixin 配置能不能被这份类路径满足。
     *
     * <p>这是 {@link #register} 的"人话版错误说明"来源：Mixin 只在它自己的日志里
     * 丢一行英文，而这里直接指出是"模组包没进类路径"还是"配置里列的混入类找不到"。
     */
    public static List<String> validate(ClassLoader cl, String config) {
        List<String> problems = new ArrayList<>(2);
        Map<String, Object> json;
        try (InputStream in = cl.getResourceAsStream(config)) {
            if (in == null) {
                problems.add("配置资源在平台类路径里找不到（该模组包没有进平台类路径）");
                return problems;
            }
            json = Json.parseObject(in.readAllBytes());
        } catch (Throwable t) {
            problems.add("配置无法读取：" + rootMessage(t));
            return problems;
        }
        String pkg = Json.str(json, "package", "").replace('.', '/');
        int missing = 0;
        String firstMissing = null;
        int checked = 0;
        for (String key : MIXIN_KEYS) {
            for (Object o : Json.arr(json.get(key))) {
                String simple = String.valueOf(o).replace('.', '/');
                if (simple.isBlank()) {
                    continue;
                }
                checked++;
                String path = pkg.isEmpty() ? simple + ".class" : pkg + "/" + simple + ".class";
                if (cl.getResource(path) == null) {
                    missing++;
                    if (firstMissing == null) {
                        firstMissing = simple;
                    }
                }
            }
        }
        if (missing > 0) {
            problems.add("配置里 " + checked + " 个混入类中有 " + missing
                    + " 个在类路径下找不到（例如 " + firstMissing + "）");
        }
        return problems;
    }

    /**
     * 诊断信息：不注册任何东西，只把"服务在哪、有多少条、处于哪个阶段"打出来。
     *
     * <p>排查"提交 11 条、服务里 0 条"时最缺的就是这些信息：
     * 分不清是注册错了类加载器，还是配置本身加载失败。
     */
    public static List<String> diagnose(ClassLoader cl) {
        List<String> out = new ArrayList<>(6);
        Class<?> mixins = resolve(cl);
        if (mixins == null) {
            out.add("类加载器 " + describe(cl) + "：无 Mixin 服务");
            return out;
        }
        Set<String> known = names(mixins);
        out.add("类加载器 " + describe(cl) + " → Mixins 类 "
                + Integer.toHexString(System.identityHashCode(mixins))
                + "，阶段 " + phase(cl) + "，已注册 " + known.size() + " 条");
        int shown = 0;
        for (String n : known) {
            if (shown++ >= 4) {
                out.add("  …其余 " + (known.size() - 4) + " 条");
                break;
            }
            out.add("  " + n);
        }
        return out;
    }

    /** 当前 Mixin 环境所处阶段：在 INIT 之前注册的配置才能作用到全部类上。 */
    public static String phase(ClassLoader cl) {
        try {
            Class<?> env = Class.forName(ENVIRONMENT, false, cl);
            Object cur = env.getMethod("getDefaultEnvironment").invoke(null);
            Object phase = env.getMethod("getPhase").invoke(cur);
            return String.valueOf(phase);
        } catch (Throwable t) {
            return "未知";
        }
    }

    // ------------------------------------------------------------------ 内部工具

    private static Class<?> resolve(ClassLoader cl) {
        try {
            // initialize=false：只确认这个类在不在，不触发它的静态初始化。
            // 静态初始化会去拉 Mixin 服务，在错误时机调用会污染宿主平台的引导流程。
            return Class.forName(MIXINS, false, cl);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Set<String> names(Class<?> mixins) {
        Set<String> out = new LinkedHashSet<>();
        try {
            Object set = mixins.getMethod("getConfigs").invoke(null);
            if (set instanceof Set<?> s) {
                for (Object config : s) {
                    out.add(configName(config));
                }
            }
        } catch (Throwable ignored) {
            // 拿不到就返回空集合：调用方会把"0 条"当成异常信号处理
        }
        return out;
    }

    /** 取 {@code org.spongepowered.asm.mixin.transformer.Config#getName()}（配置文件名）。 */
    private static String configName(Object config) {
        try {
            return String.valueOf(config.getClass().getMethod("getName").invoke(config));
        } catch (Throwable t) {
            return String.valueOf(config);
        }
    }

    private static String describe(ClassLoader cl) {
        if (cl == null) {
            return "bootstrap";
        }
        return cl.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(cl));
    }

    private static String rootMessage(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String msg = root.getMessage();
        return root.getClass().getSimpleName() + (msg == null || msg.isBlank() ? "" : "：" + msg);
    }
}
