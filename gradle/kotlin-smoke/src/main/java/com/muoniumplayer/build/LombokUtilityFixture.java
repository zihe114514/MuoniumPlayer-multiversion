package com.muoniumplayer.build;

import lombok.experimental.UtilityClass;

@UtilityClass
class LombokUtilityFixture {

    String decorate(String value) {
        return value + "-utility";
    }
}
