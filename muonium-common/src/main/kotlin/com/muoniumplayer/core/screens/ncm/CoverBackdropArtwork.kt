// Author: dishanhai
package com.muoniumplayer.core.screens.ncm

import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.awt.image.ConvolveOp
import java.awt.image.Kernel
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.imageio.ImageIO
import kotlin.math.exp

/** A bounded cache of deliberately low-frequency cover pixels, prepared with the decoded cover. */
object CoverBackdropArtwork {
    private const val size = 32
    private const val padding = 4
    private val cache = LinkedHashMap<String, ByteArray>(32, .75f, true)
    private val weights = FloatArray(padding * 2 + 1) { index ->
        val x = (index - padding) / 2.2
        exp(-.5 * x * x).toFloat()
    }.let { values ->
        val total = values.sum()
        FloatArray(values.size) { values[it] / total }
    }

    @JvmStatic
    fun publish(trackKey: String, cover: BufferedImage) {
        if (trackKey.isBlank()) return
        val bytes = try {
            sample(cover)
        } catch (_: IOException) {
            return
        }
        synchronized(cache) {
            cache[trackKey] = bytes
            if (cache.size > 128) cache.entries.iterator().apply { next(); remove() }
        }
    }

    @JvmStatic
    fun find(trackKey: String): ByteArray? = synchronized(cache) { cache[trackKey] }

    private fun sample(cover: BufferedImage): ByteArray {
        val side = size + padding * 2
        val source = BufferedImage(side, side, BufferedImage.TYPE_INT_RGB)
        val graphics = source.createGraphics()
        try {
            graphics.color = Color(22, 21, 24)
            graphics.fillRect(0, 0, side, side)
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            graphics.drawImage(cover, 0, 0, side, side, null)
        } finally {
            graphics.dispose()
        }
        val horizontal = ConvolveOp(Kernel(weights.size, 1, weights), ConvolveOp.EDGE_NO_OP, null).filter(source, null)
        val smoothed = ConvolveOp(Kernel(1, weights.size, weights), ConvolveOp.EDGE_NO_OP, null).filter(horizontal, null)
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until size) {
            for (x in 0 until size) {
                val rgb = smoothed.getRGB(x + padding, y + padding)
                val r = (rgb shr 16) and 255
                val g = (rgb shr 8) and 255
                val b = rgb and 255
                val neutral = (r * .21 + g * .72 + b * .07) * .14 + 12
                image.setRGB(x, y, ((neutral + r * .29).toInt() shl 16) or
                    ((neutral + g * .29).toInt() shl 8) or (neutral + b * .29).toInt())
            }
        }
        return ByteArrayOutputStream().use {
            if (!ImageIO.write(image, "png", it)) throw IOException("PNG encoder unavailable")
            it.toByteArray()
        }
    }
}
