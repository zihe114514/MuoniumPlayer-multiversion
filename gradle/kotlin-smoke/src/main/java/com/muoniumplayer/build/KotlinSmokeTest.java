package com.muoniumplayer.build;

public final class KotlinSmokeTest {

    private KotlinSmokeTest() {
    }

    public static void main(String[] args) {
        JavaInteropFixture java = new JavaInteropFixture("muonium");
        KotlinInteropFixture kotlin = new KotlinInteropFixture(java);
        String actual = kotlin.describe();
        if (!"muonium-kt:java".equals(actual) || !"muonium".equals(java.getValue())
                || !"muonium".equals(kotlin.generatedGetter())
                || !"muonium-utility".equals(kotlin.utilityBridge())) {
            throw new AssertionError("Java/Kotlin 混编结果不符：" + actual);
        }
        System.out.println("Kotlin/Java mixed compilation OK");
    }
}
