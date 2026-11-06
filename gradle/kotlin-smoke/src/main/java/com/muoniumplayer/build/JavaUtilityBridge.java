package com.muoniumplayer.build;

final class JavaUtilityBridge {

    private JavaUtilityBridge() {
    }

    static String decorate(String value) {
        return LombokUtilityFixture.decorate(value);
    }
}
