package com.plan.ui.theme

import androidx.compose.ui.unit.dp

object Spacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
}

object Touch {
    val Min = 48.dp
}

object FieldSize {
    val Compact = 48.dp
    val Standard = 56.dp
    val WeightFieldMin = 88.dp
    val NumericFieldMin = 84.dp
}

fun formatNumber(value: Float, decimals: Int = 1): String {
    if (value == 0f) return "0"
    val asLong = value.toLong()
    return if (value == asLong.toFloat()) {
        formatThousands(asLong)
    } else {
        val factor = Math.pow(10.0, decimals.toDouble()).toFloat()
        val rounded = (value * factor).toInt() / factor
        val whole = rounded.toLong()
        val frac = ((rounded - whole) * factor).toInt()
        val absFrac = if (frac < 0) -frac else frac
        if (absFrac == 0) formatThousands(whole)
        else "${formatThousands(whole)}.${absFrac.toString().padStart(decimals, '0').trimEnd('0').ifEmpty { "0" }}"
    }
}

private fun formatThousands(value: Long): String {
    val s = value.toString()
    val negative = s.startsWith("-")
    val digits = if (negative) s.substring(1) else s
    if (digits.length <= 3) return s
    val sb = StringBuilder()
    val firstGroup = digits.length % 3
    if (firstGroup > 0) sb.append(digits, 0, firstGroup)
    var i = firstGroup
    while (i < digits.length) {
        if (sb.isNotEmpty()) sb.append(',')
        sb.append(digits, i, i + 3)
        i += 3
    }
    return if (negative) "-$sb" else sb.toString()
}
