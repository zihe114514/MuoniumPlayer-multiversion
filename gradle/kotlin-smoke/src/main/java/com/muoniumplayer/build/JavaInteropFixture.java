package com.muoniumplayer.build;

import lombok.Getter;

final class JavaInteropFixture {

    @Getter
    private final String value;

    JavaInteropFixture(String value) {
        this.value = value;
    }

    String decorate(String suffix) {
        return value + suffix;
    }
}
