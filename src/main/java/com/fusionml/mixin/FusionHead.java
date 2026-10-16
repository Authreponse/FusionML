package com.fusionml.mixin;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 在目标方法的入口注入一次调用。方法描述符留空表示匹配任意重载。 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface FusionHead {
    /** 目标方法名。 */
    String method();

    /** 目标方法描述符；留空匹配同名全部重载。 */
    String desc() default "";
}
