package de.marlon.orinpilot.term

import androidx.compose.ui.graphics.Color

/** xterm-256-Farbpalette (Int-ARGB für android.graphics.Paint) */
object TermColors {
    const val BG: Int = 0xFF0A0C0B.toInt()
    const val FG: Int = 0xFFD8E0DA.toInt()
    const val CURSOR: Int = 0xFF76B900.toInt()
    val BG_COLOR = Color(BG)

    private val BASE16 = intArrayOf(
        0xFF1B1F1D.toInt(), 0xFFE0565B.toInt(), 0xFF76B900.toInt(), 0xFFE5B84A.toInt(),
        0xFF4F8FE6.toInt(), 0xFFB57EDC.toInt(), 0xFF3EC1C9.toInt(), 0xFFC8CFCA.toInt(),
        0xFF5C6660.toInt(), 0xFFFF7A7F.toInt(), 0xFF9BE22D.toInt(), 0xFFFFD866.toInt(),
        0xFF7AB0FF.toInt(), 0xFFD49CFF.toInt(), 0xFF6EE6EE.toInt(), 0xFFFFFFFF.toInt(),
    )

    private val PALETTE: IntArray = IntArray(256).also { p ->
        for (i in 0 until 16) p[i] = BASE16[i]
        val steps = intArrayOf(0, 95, 135, 175, 215, 255)
        for (i in 0 until 216) {
            val r = steps[i / 36]; val g = steps[(i / 6) % 6]; val b = steps[i % 6]
            p[16 + i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        for (i in 0 until 24) {
            val v = 8 + i * 10
            p[232 + i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
    }

    fun resolve(color: Int, foreground: Boolean, bold: Boolean): Int = when {
        color == TerminalEmulator.COLOR_DEFAULT -> if (foreground) FG else BG
        color >= TerminalEmulator.TRUECOLOR_FLAG -> (0xFF shl 24) or (color and 0xFFFFFF)
        // fette Standardfarben als helle Variante (wie xterm)
        bold && foreground && color < 8 -> PALETTE[color + 8]
        color in 0..255 -> PALETTE[color]
        else -> if (foreground) FG else BG
    }
}
