package de.marlon.orinpilot.term

/** Tastencodes für xterm (abhängig von DECCKM / Application Cursor Keys). */
object TermKeys {
    const val ESC = "\u001b"
    const val TAB = "\t"
    const val ENTER = "\r"
    const val BACKSPACE = "\u007f"

    fun up(app: Boolean) = if (app) "\u001bOA" else "\u001b[A"
    fun down(app: Boolean) = if (app) "\u001bOB" else "\u001b[B"
    fun right(app: Boolean) = if (app) "\u001bOC" else "\u001b[C"
    fun left(app: Boolean) = if (app) "\u001bOD" else "\u001b[D"
    fun home(app: Boolean) = if (app) "\u001bOH" else "\u001b[H"
    fun end(app: Boolean) = if (app) "\u001bOF" else "\u001b[F"

    const val PAGE_UP = "\u001b[5~"
    const val PAGE_DOWN = "\u001b[6~"
    const val INSERT = "\u001b[2~"
    const val DELETE = "\u001b[3~"

    fun f(n: Int): String = when (n) {
        1 -> "\u001bOP"; 2 -> "\u001bOQ"; 3 -> "\u001bOR"; 4 -> "\u001bOS"
        5 -> "\u001b[15~"; 6 -> "\u001b[17~"; 7 -> "\u001b[18~"; 8 -> "\u001b[19~"
        9 -> "\u001b[20~"; 10 -> "\u001b[21~"; 11 -> "\u001b[23~"; 12 -> "\u001b[24~"
        else -> ""
    }

    /** Strg+Zeichen -> Steuerzeichen (Strg+C = 0x03 usw.) */
    fun ctrl(c: Char): String? {
        val u = c.uppercaseChar()
        return when {
            u in 'A'..'Z' -> (u.code - 'A'.code + 1).toChar().toString()
            u == ' ' || u == '@' || u == '2' -> "\u0000"
            u == '[' || u == '3' -> "\u001b"
            u == '\\' || u == '4' -> "\u001c"
            u == ']' || u == '5' -> "\u001d"
            u == '^' || u == '6' -> "\u001e"
            u == '_' || u == '-' || u == '7' -> "\u001f"
            u == '?' || u == '8' -> "\u007f"
            else -> null
        }
    }

    /** Alt+Zeichen -> ESC-Präfix */
    fun alt(s: String) = "\u001b$s"
}
