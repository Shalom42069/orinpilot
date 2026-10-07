package de.marlon.orinpilot.term

/**
 * Kompakter xterm-kompatibler Terminal-Emulator (Teilmenge von VT100/VT220/xterm),
 * ausreichend für bash, nano, vim, htop, top, less, apt usw.
 *
 * Reine Kotlin-Logik ohne Android-Abhängigkeit -> unit-testbar.
 * Thread-Sicherheit: feed()/resize()/snapshot() sind synchronized.
 */
class TerminalEmulator(
    cols: Int,
    rows: Int,
    private val maxScrollback: Int = 3000,
    /** Antworten an den Host (z. B. Cursor-Position-Report). */
    var onResponse: (String) -> Unit = {},
) {
    companion object {
        const val COLOR_DEFAULT = 256          // Standard-Vorder-/Hintergrund
        const val TRUECOLOR_FLAG = 0x01000000  // >= -> 24-bit RGB in den unteren 24 Bit

        const val ATTR_BOLD = 1
        const val ATTR_UNDERLINE = 2
        const val ATTR_INVERSE = 4
        const val ATTR_ITALIC = 8
        const val ATTR_DIM = 16
        const val ATTR_STRIKE = 32

        private const val S_GROUND = 0
        private const val S_ESC = 1
        private const val S_CSI = 2
        private const val S_OSC = 3
        private const val S_OSC_ESC = 4
        private const val S_CHARSET = 5
        private const val S_ESC_HASH = 6
        private const val S_DCS = 7
        private const val S_DCS_ESC = 8

        /** DEC Special Graphics (Linienzeichen) für ESC ( 0 */
        private val LINE_DRAWING: Map<Char, Char> = mapOf(
            '`' to '◆', 'a' to '▒', 'f' to '°', 'g' to '±', 'j' to '┘', 'k' to '┐',
            'l' to '┌', 'm' to '└', 'n' to '┼', 'o' to '⎺', 'p' to '⎻', 'q' to '─',
            'r' to '⎼', 's' to '⎽', 't' to '├', 'u' to '┤', 'v' to '┴', 'w' to '┬',
            'x' to '│', 'y' to '≤', 'z' to '≥', '{' to 'π', '|' to '≠', '}' to '£', '~' to '·'
        )
    }

    class Cell(
        @JvmField var ch: Char = ' ',
        @JvmField var fg: Int = COLOR_DEFAULT,
        @JvmField var bg: Int = COLOR_DEFAULT,
        @JvmField var attr: Int = 0,
    ) {
        fun copy() = Cell(ch, fg, bg, attr)
    }

    class Line(cols: Int) {
        var cells: Array<Cell> = Array(cols) { Cell() }
        fun text(): String = String(CharArray(cells.size) { cells[it].ch }).trimEnd()
        fun copy(): Line {
            val l = Line(0)
            l.cells = Array(cells.size) { cells[it].copy() }
            return l
        }
    }

    var cols: Int = cols.coerceAtLeast(2); private set
    var rows: Int = rows.coerceAtLeast(2); private set

    private var mainScreen: Array<Line> = Array(this.rows) { Line(this.cols) }
    private var altScreen: Array<Line> = Array(this.rows) { Line(this.cols) }
    private var useAlt = false
    private val screen: Array<Line> get() = if (useAlt) altScreen else mainScreen
    private val scrollback = ArrayDeque<Line>()

    var cursorX = 0; private set
    var cursorY = 0; private set
    var cursorVisible = true; private set
    var applicationCursorKeys = false; private set
    var bracketedPaste = false; private set
    var title: String = ""; private set

    /** wird bei jeder Änderung erhöht, damit die UI neu zeichnen kann */
    @Volatile var version: Long = 0; private set

    private var scrollTop = 0
    private var scrollBottom = this.rows - 1
    private var autoWrap = true
    private var wrapPending = false
    private var originMode = false
    private var insertMode = false

    private var curFg = COLOR_DEFAULT
    private var curBg = COLOR_DEFAULT
    private var curAttr = 0

    private var g0LineDrawing = false
    private var g1LineDrawing = false
    private var useG1 = false
    private var charsetTarget = 0

    private var tabStops = BooleanArray(this.cols) { it % 8 == 0 }

    // gespeicherter Cursor (DECSC)
    private var savedX = 0
    private var savedY = 0
    private var savedFg = COLOR_DEFAULT
    private var savedBg = COLOR_DEFAULT
    private var savedAttr = 0
    private var savedWrapPending = false

    // Parser
    private var state = S_GROUND
    private val params = IntArray(32)
    private var paramCount = 0
    private var paramHasDigit = false
    private var privatePrefix: Char = 0.toChar()
    private var intermediate: Char = 0.toChar()
    private val oscBuf = StringBuilder()
    private var lastPrinted: Char = ' '

    // UTF-8
    private var utfNeeded = 0
    private var utfCodePoint = 0

    val scrollbackSize: Int @Synchronized get() = scrollback.size

    // ------------------------------------------------------------------ Eingang

    @Synchronized
    fun feed(data: ByteArray, len: Int = data.size) {
        for (i in 0 until len) {
            val b = data[i].toInt() and 0xFF
            if (utfNeeded > 0) {
                if (b and 0xC0 == 0x80) {
                    utfCodePoint = (utfCodePoint shl 6) or (b and 0x3F)
                    utfNeeded--
                    if (utfNeeded == 0) processCodePoint(utfCodePoint)
                    continue
                } else {
                    utfNeeded = 0
                    processCodePoint(0xFFFD)
                }
            }
            when {
                b < 0x80 -> processCodePoint(b)
                b and 0xE0 == 0xC0 -> { utfCodePoint = b and 0x1F; utfNeeded = 1 }
                b and 0xF0 == 0xE0 -> { utfCodePoint = b and 0x0F; utfNeeded = 2 }
                b and 0xF8 == 0xF0 -> { utfCodePoint = b and 0x07; utfNeeded = 3 }
                else -> processCodePoint(0xFFFD)
            }
        }
        version++
    }

    fun feed(text: String) = feed(text.toByteArray(Charsets.UTF_8))

    private fun processCodePoint(cp: Int) {
        when (state) {
            S_GROUND -> ground(cp)
            S_ESC -> escape(cp)
            S_CSI -> csi(cp)
            S_OSC -> when (cp) {
                0x07 -> { finishOsc(); state = S_GROUND }
                0x1B -> state = S_OSC_ESC
                else -> if (oscBuf.length < 4096) oscBuf.appendCodePoint(cp)
            }
            S_OSC_ESC -> { finishOsc(); state = S_GROUND; if (cp != '\\'.code) processCodePoint(cp) }
            S_CHARSET -> {
                val ld = cp == '0'.code
                if (charsetTarget == 0) g0LineDrawing = ld else g1LineDrawing = ld
                state = S_GROUND
            }
            S_ESC_HASH -> {
                if (cp == '8'.code) { // DECALN: Bildschirm mit 'E' füllen
                    for (l in screen) for (c in l.cells) { c.ch = 'E'; c.fg = COLOR_DEFAULT; c.bg = COLOR_DEFAULT; c.attr = 0 }
                }
                state = S_GROUND
            }
            S_DCS -> if (cp == 0x1B) state = S_DCS_ESC else if (cp == 0x07) state = S_GROUND
            S_DCS_ESC -> state = if (cp == '\\'.code) S_GROUND else S_DCS
        }
    }

    private fun ground(cp: Int) {
        when (cp) {
            0x07 -> {} // BEL
            0x08 -> { if (cursorX > 0) cursorX--; wrapPending = false }
            0x09 -> tab()
            0x0A, 0x0B, 0x0C -> { lineFeed(); wrapPending = false }
            0x0D -> { cursorX = 0; wrapPending = false }
            0x0E -> useG1 = true
            0x0F -> useG1 = false
            0x1B -> state = S_ESC
            in 0x00..0x1F -> {}
            0x7F -> {}
            else -> printChar(cp)
        }
    }

    private fun escape(cp: Int) {
        state = S_GROUND
        when (cp.toChar()) {
            '[' -> { resetParams(); state = S_CSI }
            ']' -> { oscBuf.setLength(0); state = S_OSC }
            'P' -> state = S_DCS
            '(' -> { charsetTarget = 0; state = S_CHARSET }
            ')' -> { charsetTarget = 1; state = S_CHARSET }
            '*', '+' -> { charsetTarget = 2; state = S_CHARSET }
            '#' -> state = S_ESC_HASH
            '7' -> saveCursor()
            '8' -> restoreCursor()
            'D' -> { lineFeed(); wrapPending = false }
            'E' -> { cursorX = 0; lineFeed(); wrapPending = false }
            'M' -> reverseIndex()
            'H' -> if (cursorX < cols) tabStops[cursorX] = true
            'c' -> fullReset()
            '=', '>' -> {} // Keypad-Modi
            '\\' -> {}
            else -> {}
        }
    }

    private fun resetParams() {
        paramCount = 0
        paramHasDigit = false
        params.fill(0)
        privatePrefix = 0.toChar()
        intermediate = 0.toChar()
    }

    private fun csi(cp: Int) {
        val c = cp.toChar()
        when {
            c in '0'..'9' -> {
                if (paramCount == 0) paramCount = 1
                val idx = paramCount - 1
                if (idx < params.size) params[idx] = (params[idx] * 10 + (c - '0')).coerceAtMost(100000)
                paramHasDigit = true
            }
            c == ';' || c == ':' -> {
                if (paramCount == 0) paramCount = 1
                if (paramCount < params.size) paramCount++
            }
            c == '?' || c == '>' || c == '<' || c == '=' -> privatePrefix = c
            c == ' ' || c == '!' || c == '"' || c == '$' || c == '\'' -> intermediate = c
            cp == 0x1B -> state = S_ESC // abgebrochene Sequenz
            cp in 0x40..0x7E -> { state = S_GROUND; dispatchCsi(c) }
            cp < 0x20 -> ground(cp) // C0 innerhalb CSI ausführen
            else -> {}
        }
    }

    private fun p(i: Int, def: Int): Int {
        if (i >= paramCount) return def
        val v = params[i]
        return if (v == 0) def else v
    }

    private fun dispatchCsi(c: Char) {
        if (intermediate != 0.toChar()) {
            // DECSCUSR (Cursor-Form), DECSTR (soft reset) usw.
            if (intermediate == '!' && c == 'p') softReset()
            return
        }
        if (privatePrefix == '?') {
            when (c) {
                'h' -> for (i in 0 until maxOf(1, paramCount)) setPrivateMode(params[i], true)
                'l' -> for (i in 0 until maxOf(1, paramCount)) setPrivateMode(params[i], false)
            }
            return
        }
        if (privatePrefix == '>') {
            if (c == 'c') onResponse("\u001b[>0;276;0c")
            return
        }
        when (c) {
            'A' -> { cursorY = maxOf(if (cursorY >= scrollTop) scrollTop else 0, cursorY - p(0, 1)); wrapPending = false }
            'B', 'e' -> { cursorY = minOf(if (cursorY <= scrollBottom) scrollBottom else rows - 1, cursorY + p(0, 1)); wrapPending = false }
            'C', 'a' -> { cursorX = minOf(cols - 1, cursorX + p(0, 1)); wrapPending = false }
            'D' -> { cursorX = maxOf(0, cursorX - p(0, 1)); wrapPending = false }
            'E' -> { cursorY = minOf(rows - 1, cursorY + p(0, 1)); cursorX = 0; wrapPending = false }
            'F' -> { cursorY = maxOf(0, cursorY - p(0, 1)); cursorX = 0; wrapPending = false }
            'G', '`' -> { cursorX = (p(0, 1) - 1).coerceIn(0, cols - 1); wrapPending = false }
            'H', 'f' -> setCursor(p(1, 1) - 1, p(0, 1) - 1)
            'd' -> setCursor(cursorX, p(0, 1) - 1)
            'I' -> repeat(p(0, 1)) { tab() }
            'Z' -> repeat(p(0, 1)) { backTab() }
            'J' -> eraseDisplay(if (paramCount == 0) 0 else params[0])
            'K' -> eraseLine(if (paramCount == 0) 0 else params[0])
            'L' -> insertLines(p(0, 1))
            'M' -> deleteLines(p(0, 1))
            'P' -> deleteChars(p(0, 1))
            '@' -> insertChars(p(0, 1))
            'X' -> eraseChars(p(0, 1))
            'S' -> scrollUp(p(0, 1))
            'T' -> scrollDown(p(0, 1))
            'b' -> repeat(p(0, 1).coerceAtMost(cols * rows)) { printChar(lastPrinted.code) }
            'g' -> when (if (paramCount == 0) 0 else params[0]) {
                0 -> if (cursorX < cols) tabStops[cursorX] = false
                3 -> tabStops.fill(false)
            }
            'm' -> sgr()
            'r' -> {
                val top = p(0, 1) - 1
                val bottom = p(1, rows) - 1
                if (top < bottom && bottom < rows) {
                    scrollTop = top; scrollBottom = bottom
                } else {
                    scrollTop = 0; scrollBottom = rows - 1
                }
                setCursor(0, 0)
            }
            's' -> saveCursor()
            'u' -> restoreCursor()
            'h' -> for (i in 0 until maxOf(1, paramCount)) if (params[i] == 4) insertMode = true
            'l' -> for (i in 0 until maxOf(1, paramCount)) if (params[i] == 4) insertMode = false
            'n' -> when (p(0, 0)) {
                5 -> onResponse("\u001b[0n")
                6 -> {
                    val y = if (originMode) cursorY - scrollTop else cursorY
                    onResponse("\u001b[${y + 1};${cursorX + 1}R")
                }
            }
            'c' -> onResponse("\u001b[?62;22c")
            't' -> if (p(0, 0) == 18) onResponse("\u001b[8;${rows};${cols}t")
            else -> {}
        }
    }

    private fun setPrivateMode(mode: Int, on: Boolean) {
        when (mode) {
            1 -> applicationCursorKeys = on
            6 -> { originMode = on; setCursor(0, 0) }
            7 -> autoWrap = on
            25 -> cursorVisible = on
            47, 1047 -> switchScreen(on, clear = mode == 1047 && on)
            1048 -> if (on) saveCursor() else restoreCursor()
            1049 -> {
                if (on) { saveCursor(); switchScreen(true, clear = true) }
                else { switchScreen(false, clear = false); restoreCursor() }
            }
            2004 -> bracketedPaste = on
            else -> {} // Maus-Modi u. ä. werden ignoriert
        }
    }

    private fun switchScreen(alt: Boolean, clear: Boolean) {
        if (alt == useAlt) {
            if (alt && clear) clearScreen(altScreen)
            return
        }
        useAlt = alt
        if (alt && clear) clearScreen(altScreen)
        scrollTop = 0; scrollBottom = rows - 1
        wrapPending = false
    }

    private fun clearScreen(s: Array<Line>) {
        for (l in s) for (c in l.cells) { c.ch = ' '; c.fg = COLOR_DEFAULT; c.bg = curBg; c.attr = 0 }
    }

    // ------------------------------------------------------------------ SGR

    private fun sgr() {
        if (paramCount == 0) { curFg = COLOR_DEFAULT; curBg = COLOR_DEFAULT; curAttr = 0; return }
        var i = 0
        while (i < paramCount) {
            when (val v = params[i]) {
                0 -> { curFg = COLOR_DEFAULT; curBg = COLOR_DEFAULT; curAttr = 0 }
                1 -> curAttr = curAttr or ATTR_BOLD
                2 -> curAttr = curAttr or ATTR_DIM
                3 -> curAttr = curAttr or ATTR_ITALIC
                4 -> curAttr = curAttr or ATTR_UNDERLINE
                7 -> curAttr = curAttr or ATTR_INVERSE
                9 -> curAttr = curAttr or ATTR_STRIKE
                21, 22 -> curAttr = curAttr and (ATTR_BOLD or ATTR_DIM).inv()
                23 -> curAttr = curAttr and ATTR_ITALIC.inv()
                24 -> curAttr = curAttr and ATTR_UNDERLINE.inv()
                27 -> curAttr = curAttr and ATTR_INVERSE.inv()
                29 -> curAttr = curAttr and ATTR_STRIKE.inv()
                in 30..37 -> curFg = v - 30
                39 -> curFg = COLOR_DEFAULT
                in 40..47 -> curBg = v - 40
                49 -> curBg = COLOR_DEFAULT
                in 90..97 -> curFg = v - 90 + 8
                in 100..107 -> curBg = v - 100 + 8
                38, 48 -> {
                    var color = -1
                    if (i + 1 < paramCount && params[i + 1] == 5 && i + 2 < paramCount) {
                        color = params[i + 2].coerceIn(0, 255); i += 2
                    } else if (i + 1 < paramCount && params[i + 1] == 2 && i + 4 < paramCount) {
                        val r = params[i + 2].coerceIn(0, 255)
                        val g = params[i + 3].coerceIn(0, 255)
                        val b = params[i + 4].coerceIn(0, 255)
                        color = TRUECOLOR_FLAG or (r shl 16) or (g shl 8) or b; i += 4
                    }
                    if (color >= 0) { if (v == 38) curFg = color else curBg = color }
                }
                else -> {}
            }
            i++
        }
    }

    // ------------------------------------------------------------------ Schreiben

    private fun printChar(cp: Int) {
        var ch = if (cp < 0x10000) cp.toChar() else '�'
        val lineDrawing = if (useG1) g1LineDrawing else g0LineDrawing
        if (lineDrawing) ch = LINE_DRAWING[ch] ?: ch
        if (wrapPending && autoWrap) {
            cursorX = 0
            lineFeed()
            wrapPending = false
        }
        val line = screen[cursorY].cells
        if (insertMode) {
            for (x in cols - 1 downTo cursorX + 1) line[x] = line[x - 1]
            line[cursorX] = Cell()
        }
        val cell = line[cursorX]
        cell.ch = ch; cell.fg = curFg; cell.bg = curBg; cell.attr = curAttr
        lastPrinted = ch
        if (cursorX >= cols - 1) {
            wrapPending = autoWrap
        } else {
            cursorX++
        }
    }

    private fun tab() {
        var x = cursorX + 1
        while (x < cols - 1 && !tabStops[x]) x++
        cursorX = x.coerceAtMost(cols - 1)
        wrapPending = false
    }

    private fun backTab() {
        var x = cursorX - 1
        while (x > 0 && !tabStops[x]) x--
        cursorX = x.coerceAtLeast(0)
    }

    private fun setCursor(x: Int, y: Int) {
        cursorX = x.coerceIn(0, cols - 1)
        cursorY = if (originMode) (y + scrollTop).coerceIn(scrollTop, scrollBottom) else y.coerceIn(0, rows - 1)
        wrapPending = false
    }

    private fun lineFeed() {
        if (cursorY == scrollBottom) scrollUp(1)
        else if (cursorY < rows - 1) cursorY++
    }

    private fun reverseIndex() {
        if (cursorY == scrollTop) scrollDown(1)
        else if (cursorY > 0) cursorY--
        wrapPending = false
    }

    private fun blankLine(): Line {
        val l = Line(cols)
        if (curBg != COLOR_DEFAULT) for (c in l.cells) c.bg = curBg
        return l
    }

    private fun scrollUp(n: Int) {
        val s = screen
        val count = n.coerceIn(1, scrollBottom - scrollTop + 1)
        repeat(count) {
            val removed = s[scrollTop]
            if (!useAlt && scrollTop == 0) {
                scrollback.addLast(removed)
                while (scrollback.size > maxScrollback) scrollback.removeFirst()
            }
            for (y in scrollTop until scrollBottom) s[y] = s[y + 1]
            s[scrollBottom] = blankLine()
        }
    }

    private fun scrollDown(n: Int) {
        val s = screen
        val count = n.coerceIn(1, scrollBottom - scrollTop + 1)
        repeat(count) {
            for (y in scrollBottom downTo scrollTop + 1) s[y] = s[y - 1]
            s[scrollTop] = blankLine()
        }
    }

    private fun insertLines(n: Int) {
        if (cursorY < scrollTop || cursorY > scrollBottom) return
        val s = screen
        repeat(n.coerceAtMost(scrollBottom - cursorY + 1)) {
            for (y in scrollBottom downTo cursorY + 1) s[y] = s[y - 1]
            s[cursorY] = blankLine()
        }
        cursorX = 0; wrapPending = false
    }

    private fun deleteLines(n: Int) {
        if (cursorY < scrollTop || cursorY > scrollBottom) return
        val s = screen
        repeat(n.coerceAtMost(scrollBottom - cursorY + 1)) {
            for (y in cursorY until scrollBottom) s[y] = s[y + 1]
            s[scrollBottom] = blankLine()
        }
        cursorX = 0; wrapPending = false
    }

    private fun clearCell(c: Cell) {
        c.ch = ' '; c.fg = COLOR_DEFAULT; c.bg = curBg; c.attr = 0
    }

    private fun deleteChars(n: Int) {
        val line = screen[cursorY].cells
        val count = n.coerceAtMost(cols - cursorX)
        for (x in cursorX until cols) {
            if (x + count < cols) line[x] = line[x + count] else line[x] = Cell().also { clearCell(it) }
        }
        wrapPending = false
    }

    private fun insertChars(n: Int) {
        val line = screen[cursorY].cells
        val count = n.coerceAtMost(cols - cursorX)
        for (x in cols - 1 downTo cursorX) {
            if (x - count >= cursorX) line[x] = line[x - count] else line[x] = Cell().also { clearCell(it) }
        }
        wrapPending = false
    }

    private fun eraseChars(n: Int) {
        val line = screen[cursorY].cells
        for (x in cursorX until minOf(cols, cursorX + n)) clearCell(line[x])
        wrapPending = false
    }

    private fun eraseLine(mode: Int) {
        val line = screen[cursorY].cells
        val range = when (mode) {
            0 -> cursorX until cols
            1 -> 0..minOf(cursorX, cols - 1)
            else -> 0 until cols
        }
        for (x in range) clearCell(line[x])
        wrapPending = false
    }

    private fun eraseDisplay(mode: Int) {
        val s = screen
        when (mode) {
            0 -> {
                eraseLine(0)
                for (y in cursorY + 1 until rows) for (c in s[y].cells) clearCell(c)
            }
            1 -> {
                for (y in 0 until cursorY) for (c in s[y].cells) clearCell(c)
                eraseLine(1)
            }
            2 -> for (l in s) for (c in l.cells) clearCell(c)
            3 -> scrollback.clear()
        }
        wrapPending = false
    }

    private fun saveCursor() {
        savedX = cursorX; savedY = cursorY
        savedFg = curFg; savedBg = curBg; savedAttr = curAttr
        savedWrapPending = wrapPending
    }

    private fun restoreCursor() {
        cursorX = savedX.coerceIn(0, cols - 1); cursorY = savedY.coerceIn(0, rows - 1)
        curFg = savedFg; curBg = savedBg; curAttr = savedAttr
        wrapPending = savedWrapPending
    }

    private fun softReset() {
        cursorVisible = true; originMode = false; autoWrap = true; insertMode = false
        applicationCursorKeys = false
        scrollTop = 0; scrollBottom = rows - 1
        curFg = COLOR_DEFAULT; curBg = COLOR_DEFAULT; curAttr = 0
        g0LineDrawing = false; g1LineDrawing = false; useG1 = false
    }

    private fun fullReset() {
        softReset()
        useAlt = false
        clearScreen(mainScreen); clearScreen(altScreen)
        scrollback.clear()
        cursorX = 0; cursorY = 0; wrapPending = false
        bracketedPaste = false
        tabStops = BooleanArray(cols) { it % 8 == 0 }
    }

    private fun finishOsc() {
        val s = oscBuf.toString()
        val sep = s.indexOf(';')
        if (sep > 0) {
            val code = s.substring(0, sep)
            if (code == "0" || code == "2") title = s.substring(sep + 1)
        }
        oscBuf.setLength(0)
    }

    // ------------------------------------------------------------------ Größe

    @Synchronized
    fun resize(newCols: Int, newRows: Int) {
        val nc = newCols.coerceAtLeast(2)
        val nr = newRows.coerceAtLeast(2)
        if (nc == cols && nr == rows) return

        fun adjust(old: Array<Line>, active: Boolean, toScrollback: Boolean): Array<Line> {
            val lines = old.toMutableList()
            while (lines.size > nr) {
                if (active && cursorY >= nr) {
                    // Cursor würde herausfallen -> oben abschneiden
                    val removed = lines.removeAt(0)
                    if (toScrollback) {
                        scrollback.addLast(removed)
                        while (scrollback.size > maxScrollback) scrollback.removeFirst()
                    }
                    cursorY--
                } else {
                    lines.removeAt(lines.size - 1)
                }
            }
            while (lines.size < nr) lines.add(Line(nc))
            for (l in lines) {
                if (l.cells.size != nc) {
                    val oc = l.cells
                    l.cells = Array(nc) { if (it < oc.size) oc[it] else Cell() }
                }
            }
            return lines.toTypedArray()
        }

        mainScreen = adjust(mainScreen, active = !useAlt, toScrollback = true)
        altScreen = adjust(altScreen, active = useAlt, toScrollback = false)
        cols = nc
        rows = nr
        cursorX = cursorX.coerceIn(0, cols - 1)
        cursorY = cursorY.coerceIn(0, rows - 1)
        savedX = savedX.coerceIn(0, cols - 1)
        savedY = savedY.coerceIn(0, rows - 1)
        scrollTop = 0
        scrollBottom = rows - 1
        wrapPending = false
        tabStops = BooleanArray(cols) { it % 8 == 0 }
        version++
    }

    // ------------------------------------------------------------------ Ausgabe für UI

    class Snapshot(
        val lines: List<Line>,
        val cols: Int,
        val rows: Int,
        /** Cursor-Zeile innerhalb von [lines] oder -1, wenn nicht sichtbar */
        val cursorRow: Int,
        val cursorCol: Int,
        val cursorVisible: Boolean,
        val scrollbackSize: Int,
    )

    /**
     * @param scrollOffset 0 = aktueller Bildschirm, >0 = so viele Zeilen in den Verlauf zurück
     */
    @Synchronized
    fun snapshot(scrollOffset: Int = 0): Snapshot {
        val sbSize = if (useAlt) 0 else scrollback.size
        val off = scrollOffset.coerceIn(0, sbSize)
        val out = ArrayList<Line>(rows)
        for (i in 0 until rows) {
            val virtualIndex = i - off // <0 -> Verlauf
            val line = if (virtualIndex < 0) scrollback[sbSize + virtualIndex] else screen[virtualIndex]
            out.add(line.copy())
        }
        val cRow = cursorY + off
        return Snapshot(
            lines = out, cols = cols, rows = rows,
            cursorRow = if (cRow < rows) cRow else -1,
            cursorCol = cursorX,
            cursorVisible = cursorVisible && off == 0,
            scrollbackSize = sbSize,
        )
    }

    /** gesamter Text (Verlauf + Bildschirm) – für "Alles kopieren" */
    @Synchronized
    fun allText(): String {
        val sb = StringBuilder()
        if (!useAlt) for (l in scrollback) sb.append(l.text()).append('\n')
        for (l in screen) sb.append(l.text()).append('\n')
        return sb.toString().trimEnd() + "\n"
    }

    /** sichtbarer Bildschirminhalt als Text (Tests / Debug) */
    @Synchronized
    fun screenText(): String = screen.joinToString("\n") { it.text() }
}
