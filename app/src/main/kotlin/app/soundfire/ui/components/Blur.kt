package app.soundfire.ui.components

import android.graphics.Bitmap
import androidx.core.graphics.scale

/**
 * CPU blur for Fire OS 8 (API 30), which predates RenderEffect. Downscaling first keeps it cheap:
 * a 1080p frame at 1/10 scale is ~20k pixels, and three box-blur passes approximate a Gaussian.
 */
object Blur {
    fun blur(source: Bitmap, downscale: Int, radius: Int, passes: Int = 3): Bitmap {
        val software = if (source.config == Bitmap.Config.ARGB_8888) source else source.copy(Bitmap.Config.ARGB_8888, false)
        val w = (software.width / downscale).coerceAtLeast(1)
        val h = (software.height / downscale).coerceAtLeast(1)
        val small = software.scale(w, h, filter = true)
        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)
        val scratch = IntArray(w * h)
        repeat(passes) {
            boxBlurHorizontal(pixels, scratch, w, h, radius)
            boxBlurVertical(scratch, pixels, w, h, radius)
        }
        val out = if (small.isMutable) small else small.copy(Bitmap.Config.ARGB_8888, true)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    private fun boxBlurHorizontal(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val div = 2 * r + 1
        for (y in 0 until h) {
            val row = y * w
            var a = 0; var red = 0; var g = 0; var b = 0
            for (i in -r..r) {
                val p = src[row + i.coerceIn(0, w - 1)]
                a += p ushr 24; red += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF
            }
            for (x in 0 until w) {
                dst[row + x] = ((a / div) shl 24) or ((red / div) shl 16) or ((g / div) shl 8) or (b / div)
                val add = src[row + (x + r + 1).coerceAtMost(w - 1)]
                val rem = src[row + (x - r).coerceAtLeast(0)]
                a += (add ushr 24) - (rem ushr 24)
                red += ((add shr 16) and 0xFF) - ((rem shr 16) and 0xFF)
                g += ((add shr 8) and 0xFF) - ((rem shr 8) and 0xFF)
                b += (add and 0xFF) - (rem and 0xFF)
            }
        }
    }

    private fun boxBlurVertical(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val div = 2 * r + 1
        for (x in 0 until w) {
            var a = 0; var red = 0; var g = 0; var b = 0
            for (i in -r..r) {
                val p = src[i.coerceIn(0, h - 1) * w + x]
                a += p ushr 24; red += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF
            }
            for (y in 0 until h) {
                dst[y * w + x] = ((a / div) shl 24) or ((red / div) shl 16) or ((g / div) shl 8) or (b / div)
                val add = src[(y + r + 1).coerceAtMost(h - 1) * w + x]
                val rem = src[(y - r).coerceAtLeast(0) * w + x]
                a += (add ushr 24) - (rem ushr 24)
                red += ((add shr 16) and 0xFF) - ((rem shr 16) and 0xFF)
                g += ((add shr 8) and 0xFF) - ((rem shr 8) and 0xFF)
                b += (add and 0xFF) - (rem and 0xFF)
            }
        }
    }
}
