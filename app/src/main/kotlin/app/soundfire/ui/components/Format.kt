package app.soundfire.ui.components

import android.icu.text.CompactDecimalFormat
import java.text.NumberFormat
import java.util.Locale

private val compactFormat = CompactDecimalFormat.getInstance(Locale.US, CompactDecimalFormat.CompactStyle.SHORT)
    .apply { maximumFractionDigits = 1 }

fun compact(n: Long): String = compactFormat.format(n)

fun formatDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private val countFormat = NumberFormat.getIntegerInstance(Locale.US)

fun formatCount(n: Int): String = countFormat.format(n)
