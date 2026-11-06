package com.muoniumplayer.build

internal class KotlinInteropFixture(private val source: JavaInteropFixture) {
    fun describe(): String = listOf(source.decorate("-kt"), "java").joinToString(":")

    fun generatedGetter(): String = source.value

    fun utilityBridge(): String = JavaUtilityBridge.decorate(source.value)
}
