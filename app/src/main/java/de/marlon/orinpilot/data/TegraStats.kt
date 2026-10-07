package de.marlon.orinpilot.data

/** Eine geparste Zeile von `tegrastats`. */
data class TegraStats(
    val ramUsedMb: Int = 0,
    val ramTotalMb: Int = 0,
    val swapUsedMb: Int = 0,
    val swapTotalMb: Int = 0,
    /** null = Kern offline */
    val cpuLoads: List<Int?> = emptyList(),
    val cpuFreqs: List<Int?> = emptyList(),
    val emcLoad: Int? = null,
    val emcFreq: Int? = null,
    val gpuLoad: Int? = null,
    val gpuFreq: Int? = null,
    val temps: Map<String, Float> = emptyMap(),
    val rails: List<PowerRail> = emptyList(),
) {
    val cpuAvg: Int
        get() {
            val online = cpuLoads.filterNotNull()
            return if (online.isEmpty()) 0 else online.sum() / online.size
        }

    /** höchste sinnvolle Temperatur (tj bevorzugt) */
    val hotTemp: Float?
        get() = temps["tj"] ?: temps.values.maxOrNull()

    val totalPowerMw: Int?
        get() = rails.firstOrNull { it.name == "VDD_IN" }?.currentMw
            ?: rails.firstOrNull { it.name.contains("IN") }?.currentMw

    companion object {
        private val RAM = Regex("""RAM (\d+)/(\d+)MB""")
        private val SWAP = Regex("""SWAP (\d+)/(\d+)MB""")
        private val CPU = Regex("""CPU \[([^\]]*)]""")
        private val EMC = Regex("""EMC_FREQ (\d+)%(?:@(\d+))?""")
        private val GPU = Regex("""GR3D_FREQ (\d+)%(?:@\[?(\d+))?""")
        private val TEMP = Regex("""\b([A-Za-z][\w-]*)@(-?\d+(?:\.\d+)?)C\b""")
        private val RAIL = Regex("""\b((?:VDD|VIN|POM)_[A-Z0-9_]+) (\d+)(?:mW)?/(\d+)(?:mW)?""")

        fun parse(line: String): TegraStats? {
            if (!line.contains("RAM ")) return null
            val ram = RAM.find(line)
            val swap = SWAP.find(line)
            val loads = mutableListOf<Int?>()
            val freqs = mutableListOf<Int?>()
            CPU.find(line)?.groupValues?.get(1)?.split(',')?.forEach { part ->
                val p = part.trim()
                if (p == "off" || p.isEmpty()) {
                    loads.add(null); freqs.add(null)
                } else {
                    val pct = p.substringBefore('%').toIntOrNull()
                    val f = p.substringAfter('@', "").toIntOrNull()
                    loads.add(pct); freqs.add(f)
                }
            }
            val emc = EMC.find(line)
            val gpu = GPU.find(line)
            val temps = linkedMapOf<String, Float>()
            TEMP.findAll(line).forEach { m ->
                val v = m.groupValues[2].toFloatOrNull()
                if (v != null && v > -100f) temps[m.groupValues[1]] = v
            }
            val rails = RAIL.findAll(line).map {
                PowerRail(it.groupValues[1], it.groupValues[2].toInt(), it.groupValues[3].toInt())
            }.toList()
            return TegraStats(
                ramUsedMb = ram?.groupValues?.get(1)?.toIntOrNull() ?: 0,
                ramTotalMb = ram?.groupValues?.get(2)?.toIntOrNull() ?: 0,
                swapUsedMb = swap?.groupValues?.get(1)?.toIntOrNull() ?: 0,
                swapTotalMb = swap?.groupValues?.get(2)?.toIntOrNull() ?: 0,
                cpuLoads = loads, cpuFreqs = freqs,
                emcLoad = emc?.groupValues?.get(1)?.toIntOrNull(),
                emcFreq = emc?.groupValues?.get(2)?.toIntOrNull(),
                gpuLoad = gpu?.groupValues?.get(1)?.toIntOrNull(),
                gpuFreq = gpu?.groupValues?.get(2)?.toIntOrNull(),
                temps = temps,
                rails = rails,
            )
        }
    }
}

data class PowerRail(val name: String, val currentMw: Int, val averageMw: Int)
