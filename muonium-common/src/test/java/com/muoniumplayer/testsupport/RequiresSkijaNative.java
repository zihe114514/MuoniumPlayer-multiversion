package com.muoniumplayer.testsupport;

import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 需要真实 Skija 原生库的测试类打这个标：CI（ubuntu）上 Skija 的 windows/linux
 * 原生加载链路走不通（SkijaNatives 的伴生包目录与 library.path 口子在无头 CI
 * 都不可用），这些用例只在开发者本机跑 —— fabric build.gradle 在非 Windows
 * 的 test JVM 上设 muonium.ci.skipSkijaNative=true，JUnit 据此整类禁用。
 *
 * <p>本机 Windows 不设该属性，测试照常全跑。</p>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@EnabledIfSystemProperty(named = "muonium.ci.skipSkijaNative", matches = "false")
public @interface RequiresSkijaNative {
}
