package com.fusionml.mixin;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 本加载器自研的注入格式（原生，不依赖 Fabric Mixin 的任何代码）。
 *
 * <p>用法：
 * <pre>{@code
 * @FusionMixin(target = "com.example.SomeClass")
 * public class MyMixin {
 *     @FusionHead(method = "doThing", desc = "(I)V")
 *     public static void before(SomeClass self, int arg) { ... }
 * }
 * }</pre>
 *
 * <p>钩子方法的签名规则：目标方法若是实例方法，第一个参数就是目标类本身；
 * 之后逐个对应目标方法的参数。返回类型必须是 void（见 HookInjector 的栈中立约束）。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface FusionMixin {

    String target();

    int priority() default 1000;
}
