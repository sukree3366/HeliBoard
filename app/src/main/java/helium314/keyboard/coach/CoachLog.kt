// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.coach

import android.content.Context
import helium314.keyboard.latin.utils.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The Typing coach logger.
 *
 * Touch handlers only build a short JSON line and hand it to a single background thread, so no file
 * or dictionary I/O ever runs on the input thread. Words are only stored for mistakes, and only
 * with at most two words of context.
 *
 * Everything lives in app private storage, rotates at about 2 MB and is deleted by the Clear button.
 */
object CoachLog {
    private const val TAG = "CoachLog"
    const val DIR = "coach"
    private const val LOG_PREFIX = "log-"
    private const val MAX_FILE_BYTES = 2L * 1024 * 1024
    private const val MAX_LOG_FILES = 4
    private const val AGGREGATE_FILE = "aggregates.json"

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "typing-coach").apply { isDaemon = true }
    }

    private var sessionId: Long = System.currentTimeMillis()
    private var currentLogIndex = -1
    private var currentLogSize = 0L

    /** key label -> [centerX, centerY, width, height], rebuilt for every session. */
    @Volatile private var keyPositions: Map<String, IntArray> = emptyMap()

    /** layout + orientation of the current session, used as aggregate key prefix. */
    @Volatile private var sessionKey: String = ""
    @Volatile private var keyboardWidth: Int = 0

    /** Marks the session as inactive, used when logging and key statistics are both switched off. */
    fun setInactive() {
        active = false
        loggingMistakes = false
        collectingKeyStats = false
    }

    /** taps of the word currently being composed, for the substitution events */
    private val wordTaps = ArrayDeque<TapRecord>()

    /** whether anything is currently being logged at all */
    @Volatile var active: Boolean = false
        private set
    @Volatile var loggingMistakes: Boolean = false
        private set
    @Volatile var collectingKeyStats: Boolean = false
        private set

    private class TapRecord(
        val label: String?,
        val x: Int,
        val y: Int,
        val centerX: Int,
        val centerY: Int,
        val keyWidth: Int,
        val keyHeight: Int,
        val time: Long,
    )

    // ------------------------------------------------------------------ session

    fun startSession(
        context: Context,
        layout: String,
        orientation: String,
        screenWidth: Int,
        screenHeight: Int,
        densityDpi: Int,
        keyboardX: Int,
        keyboardY: Int,
        keyboardWidth: Int,
        keyboardHeight: Int,
        configJson: String,
        keyPositions: Map<String, IntArray>,
    ) {
        loggingMistakes = CoachSettings.logMistakes
        collectingKeyStats = CoachSettings.keyStats
        active = loggingMistakes || collectingKeyStats
        if (!active) return
        this.keyPositions = keyPositions
        this.keyboardWidth = keyboardWidth
        sessionKey = "$layout|$orientation"
        sessionId = System.currentTimeMillis()
        wordTaps.clear()
        val line = buildString {
            append("{\"e\":\"session\"")
            append(",\"t\":").append(sessionId)
            append(",\"sid\":").append(sessionId)
            append(",\"layout\":\"").append(escape(layout)).append('"')
            append(",\"orient\":\"").append(escape(orientation)).append('"')
            append(",\"screen\":[").append(screenWidth).append(',').append(screenHeight).append(']')
            append(",\"dpi\":").append(densityDpi)
            append(",\"kbd\":{\"x\":").append(keyboardX).append(",\"y\":").append(keyboardY)
            append(",\"w\":").append(keyboardWidth).append(",\"h\":").append(keyboardHeight).append('}')
            append(",\"cfg\":").append(configJson)
            append('}')
        }
        val level = context
        executor.execute {
            CoachAggregates.onSession(sessionKey)
            appendLine(level, line)
        }
    }

    fun endSession(context: Context) {
        if (!active) return
        wordTaps.clear()
        val level = context
        executor.execute {
            CoachAggregates.flush(level)
            currentLogIndex = -1
        }
    }

    // ------------------------------------------------------------------ events

    /** One key press; called from the input thread, only formats and queues. */
    fun onTap(
        context: Context,
        keyLabel: String?,
        x: Int,
        y: Int,
        centerX: Int,
        centerY: Int,
        keyWidth: Int,
        keyHeight: Int,
        dwellMillis: Long,
        row: Int,
    ) {
        if (!active) return
        val now = System.currentTimeMillis()
        val previousTapTime = wordTaps.lastOrNull()?.time ?: 0L
        val gap = if (previousTapTime == 0L) 0L else now - previousTapTime
        if (wordTaps.size >= 64) wordTaps.removeFirst()
        wordTaps.addLast(TapRecord(keyLabel, x, y, centerX, centerY, keyWidth, keyHeight, now))

        val dx = x - centerX
        val dy = y - centerY
        if (collectingKeyStats) {
            CoachAggregates.onTap(sessionKey, keyboardWidth, keyLabel, row, x, y, centerX, centerY, keyWidth, keyHeight, dx, dy, dwellMillis)
        }
        if (!loggingMistakes) return
        val near = nearestKeys(keyLabel, x, y)
        val line = buildString {
            append("{\"e\":\"tap\",\"t\":").append(now)
            append(",\"sid\":").append(sessionId)
            append(",\"key\":\"").append(escape(keyLabel ?: "#")).append('"')
            append(",\"x\":").append(x).append(",\"y\":").append(y)
            append(",\"kc\":[").append(centerX).append(',').append(centerY).append(']')
            append(",\"kw\":").append(keyWidth).append(",\"kh\":").append(keyHeight)
            append(",\"row\":").append(row)
            append(",\"near\":[").append(near).append(']')
            append(",\"dwell\":").append(dwellMillis)
            append(",\"gap\":").append(gap)
            append('}')
        }
        val level = context
        executor.execute { appendLine(level, line) }
    }

    /**
     * Records a word that ended up different from what was typed.
     *
     * @param how one of `autocorrect`, `suggestion`, `manual`, `reverted`, `learned`, `twocapitals`, `space`
     */
    fun onFix(
        context: Context,
        typed: String,
        final: String,
        how: String,
        contextBefore: String?,
        contextAfter: String?,
        alignment: AlignmentResult?,
    ) {
        if (!active) return
        if (typed == final) return
        val time = System.currentTimeMillis()
        if (collectingKeyStats) {
            CoachAggregates.onMistake(sessionKey, typed, final, how, alignment)
        }
        if (!loggingMistakes) return

        val taps = wordTaps.map { it }
        val ops = StringBuilder()
        alignment?.ops?.forEach { op ->
            if (ops.isNotEmpty()) ops.append(',')
            ops.append("{\"k\":\"").append(op.kind.name.lowercase(Locale.ROOT)).append('"')
            ops.append(",\"pos\":").append(op.position)
            op.typed?.let { ops.append(",\"from\":\"").append(escape(it.toString())).append('"') }
            op.intended?.let { ops.append(",\"to\":\"").append(escape(it.toString())).append('"') }
            ops.append('}')
        }
        val tapTimes = StringBuilder()
        taps.forEachIndexed { index, tap ->
            if (index > 0) tapTimes.append(',')
            tapTimes.append(tap.time)
        }
        val line = buildString {
            append("{\"e\":\"fix\",\"t\":").append(time)
            append(",\"sid\":").append(sessionId)
            append(",\"typed\":\"").append(escape(redact(typed))).append('"')
            append(",\"final\":\"").append(escape(redact(final))).append('"')
            append(",\"how\":\"").append(escape(how)).append('"')
            append(",\"ops\":[").append(ops).append(']')
            append(",\"taps\":[").append(tapTimes).append(']')
            append(",\"ctxBefore\":\"").append(escape(redact(contextBefore.orEmpty()))).append('"')
            append(",\"ctxAfter\":\"").append(escape(redact(contextAfter.orEmpty()))).append('"')
            append('}')
        }
        val level = context
        executor.execute { appendLine(level, line) }

        // one sub event per corrected character, so the geometry analysis can use the exact tap
        alignment?.changedOps?.forEach { op ->
            val typedChar = op.typed?.toString()
            val intendedChar = op.intended?.toString()
            if (typedChar == null || intendedChar == null) return@forEach
            val tap = taps.getOrNull(op.position) ?: return@forEach
            val intendedCenter = keyPositions[intendedChar] ?: keyPositions[intendedChar.lowercase(Locale.ROOT)]
            val dx = if (intendedCenter != null) tap.x - intendedCenter[0] else 0
            val dy = if (intendedCenter != null) tap.y - intendedCenter[1] else 0
            val neighbour = intendedCenter != null && isNeighbour(tap.centerX, tap.centerY, intendedCenter)
            val subLine = buildString {
                append("{\"e\":\"sub\",\"t\":").append(time)
                append(",\"sid\":").append(sessionId)
                append(",\"typedKey\":\"").append(escape(typedChar)).append('"')
                append(",\"intendedKey\":\"").append(escape(intendedChar)).append('"')
                append(",\"kind\":\"").append(op.kind.name.lowercase(Locale.ROOT)).append('"')
                append(",\"neighbor\":").append(neighbour)
                append(",\"dx\":").append(dx).append(",\"dy\":").append(dy)
                append(",\"tap\":").append(tap.time)
                append('}')
            }
            executor.execute { appendLine(level, subLine) }
        }
    }

    /** Clears the taps of the finished word. Called after every word commit. */
    fun onWordCommitted() {
        wordTaps.clear()
    }

    // ------------------------------------------------------------------ storage

    fun clear(context: Context) {
        executor.execute {
            // only the coach's own data, never the learned corrections or the backups
            directory(context).listFiles()?.forEach { file ->
                if (file.name.startsWith(LOG_PREFIX) || file.name.startsWith("aggregates")) file.delete()
            }
            currentLogIndex = -1
            currentLogSize = 0
        }
        synchronized(CoachAggregates) { CoachAggregates.reset() }
        wordTaps.clear()
    }

    fun hasData(context: Context): Boolean = directory(context).listFiles()?.any { it.length() > 0 } == true

    /**
     * Writes the raw log, the per-key summary and the learned corrections into a zip so both the
     * raw data and a human readable file can be shared with the analyst.
     */
    fun export(context: Context, output: OutputStream): Boolean {
        // make sure everything that was queued is in the file before it is copied
        runCatching { executor.submit { }.get(2, TimeUnit.SECONDS) }
        return try {
            val files = directory(context).listFiles()
                ?.filter { it.isFile && it.name.startsWith(LOG_PREFIX) }
                ?.sortedBy { it.name }
                ?: emptyList()
            val zip = ZipOutputStream(output)
            zip.putNextEntry(ZipEntry("typing-coach.jsonl"))
            for (file in files) {
                file.inputStream().use { it.copyTo(zip, 4096) }
            }
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("summary.csv"))
            zip.write(CoachAggregates.csv().toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("config.json"))
            zip.write(CoachAggregates.configJson().toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("learned.txt"))
            zip.write(LearnedStore.exportText().toByteArray())
            zip.closeEntry()
            zip.finish()
            zip.flush()
            true
        } catch (t: Throwable) {
            Log.w(TAG, "export failed", t)
            false
        }
    }

    fun summary(context: Context): String {
        CoachAggregates.load(context)
        return CoachAggregates.summaryText()
    }

    // ------------------------------------------------------------------ helpers

    private fun nearestKeys(keyLabel: String?, x: Int, y: Int): String {
        val positions = keyPositions
        if (positions.isEmpty()) return ""
        val candidates = positions.entries
            .filter { it.key != keyLabel }
            .map { entry ->
                val dx = entry.value[0] - x
                val dy = entry.value[1] - y
                entry.key to (dx * dx + dy * dy)
            }
            .sortedBy { it.second }
            .take(2)
        val builder = StringBuilder()
        candidates.forEachIndexed { index, (label, distance) ->
            if (index > 0) builder.append(',')
            builder.append("[\"").append(escape(label)).append("\",").append(kotlin.math.sqrt(distance.toDouble()).toInt()).append(']')
        }
        return builder.toString()
    }

    private fun isNeighbour(ax: Int, ay: Int, other: IntArray): Boolean {
        val dx = (ax - other[0]).toDouble()
        val dy = (ay - other[1]).toDouble()
        val maxDistance = 1.5 * maxOf(other[2], other[3])
        return dx * dx + dy * dy <= maxDistance * maxDistance
    }

    /** Replaces anything that looks like a secret: tokens with digits, @, /, \ or : */
    fun redact(text: String): String {
        if (text.isEmpty()) return text
        return text.split(' ').joinToString(" ") { token ->
            if (token.any { it.isDigit() || it == '@' || it == '/' || it == '\\' || it == ':' }) "#" else token
        }
    }

    private fun escape(value: String): String {
        val builder = StringBuilder(value.length + 8)
        for (c in value) {
            when (c) {
                '"' -> builder.append("\\\"")
                '\\' -> builder.append("\\\\")
                '\n' -> builder.append("\\n")
                '\r' -> builder.append("\\r")
                '\t' -> builder.append("\\t")
                else -> if (c < ' ') builder.append(' ') else builder.append(c)
            }
        }
        return builder.toString()
    }

    private fun directory(context: Context): File = File(context.filesDir, DIR).apply { mkdirs() }

    /** Must only be called on the logger thread. */
    private fun appendLine(context: Context, line: String) {
        try {
            val dir = directory(context)
            if (currentLogIndex < 0) {
                currentLogIndex = (dir.listFiles()?.count { it.name.startsWith(LOG_PREFIX) } ?: 0)
                rotateIfNeeded(dir)
            }
            val file = File(dir, "$LOG_PREFIX$currentLogIndex.jsonl")
            if (file.exists() && file.length() > MAX_FILE_BYTES) {
                currentLogIndex++
                deleteOldFiles(dir)
            }
            val target = File(dir, "$LOG_PREFIX$currentLogIndex.jsonl")
            target.appendText(line)
            target.appendText("\n")
        } catch (t: Throwable) {
            Log.w(TAG, "could not write log line", t)
        }
    }

    private fun rotateIfNeeded(dir: File) {
        val files = dir.listFiles { file -> file.name.startsWith(LOG_PREFIX) }?.sortedBy { it.name }.orEmpty()
        if (files.size > MAX_LOG_FILES) {
            files.take(files.size - MAX_LOG_FILES).forEach { it.delete() }
        }
        deleteOldFiles(dir)
    }

    private fun deleteOldFiles(dir: File) {
        val files = dir.listFiles { file -> file.name.startsWith(LOG_PREFIX) }?.sortedBy { it.name }.orEmpty()
        if (files.size > MAX_LOG_FILES) {
            files.take(files.size - MAX_LOG_FILES).forEach { it.delete() }
        }
    }


}

/**
 * Always-on aggregates: no text, only counters and offsets. They are what the touch offset model
 * and the summary screen are built from.
 */
object CoachAggregates {
    private const val TAG = "CoachAggregates"
    private const val FILE_NAME = "aggregates.json"
    private const val FLUSH_EVERY = 100

    /** key: layout|orientation|key -> [n, sx, sxx, sy, syy, dwell, kwidth, kheight] */
    private val keyStats = HashMap<String, LongArray>()
    /** key: layout|orientation|r<row> -> [n, sdy, sdyy] */
    private val rowStats = HashMap<String, LongArray>()
    /** key: layout|orientation|left / right -> [n, sdx, sdy] */
    private val halfStats = HashMap<String, LongArray>()
    /** key: layout|orientation -> [taps, dwellSum, mistakes, learningApplied] */
    private val totals = HashMap<String, LongArray>()
    /** key: typed|intended -> count */
    private val confusions = HashMap<String, Long>()
    private var pendingWrites = 0
    private var currentSessionKey: String = ""

    fun reset() {
        keyStats.clear()
        rowStats.clear()
        halfStats.clear()
        totals.clear()
        confusions.clear()
        pendingWrites = 0
    }

    fun onSession(sessionKey: String) {
        currentSessionKey = sessionKey
        loadIfNeeded()
    }

    /** Loads stored aggregates. Used by the settings screen, which has no typing session. */
    fun load(context: Context) {
        if (keyStats.isNotEmpty() || totals.isNotEmpty()) return
        loadFrom(context)
    }

    fun onTap(
        sessionKey: String,
        keyboardWidth: Int,
        keyLabel: String?,
        row: Int,
        x: Int,
        y: Int,
        centerX: Int,
        centerY: Int,
        keyWidth: Int,
        keyHeight: Int,
        dx: Int,
        dy: Int,
        dwellMillis: Long,
    ) {
        val label = keyLabel ?: return
        if (label.length > 3) return
        if (!label.first().isLetter()) return
        synchronized(this) {
            val key = "$sessionKey|${label.lowercase(Locale.ROOT)}"
            val entry = keyStats.getOrPut(key) { LongArray(8) }
            entry[0]++
            entry[1] += dx
            entry[2] += dx.toLong() * dx
            entry[3] += dy
            entry[4] += dy.toLong() * dy
            entry[5] += dwellMillis
            entry[6] += keyWidth
            entry[7] += keyHeight
            val rowKey = "${sessionKey}|r$row"
            val rowEntry = rowStats.getOrPut(rowKey) { LongArray(3) }
            rowEntry[0]++
            rowEntry[1] += dy
            rowEntry[2] += dy.toLong() * dy
            val half = if (keyboardWidth > 0 && x < keyboardWidth / 2) "left" else "right"
            val halfEntry = halfStats.getOrPut("$sessionKey|$half") { LongArray(3) }
            halfEntry[0]++
            halfEntry[1] += dx
            halfEntry[2] += dy
            val total = totals.getOrPut(sessionKey) { LongArray(4) }
            total[0]++
            total[1] += dwellMillis
            pendingWrites++
        }
    }

    /**
     * Updates the mistake counters and the confusion table.
     * @return true when something was recorded
     */
    fun onMistake(
        sessionKey: String,
        typed: String,
        intended: String,
        how: String,
        alignment: AlignmentResult?,
    ): Boolean {
        synchronized(this) {
            val total = totals.getOrPut(sessionKey) { LongArray(4) }
            total[2]++
            if (how == "learned") total[3]++
            alignment?.changedOps?.forEach { op ->
                val from = op.typed?.toString() ?: return@forEach
                val to = op.intended?.toString() ?: return@forEach
                val key = "${from.lowercase(Locale.ROOT)}|${to.lowercase(Locale.ROOT)}"
                confusions[key] = (confusions[key] ?: 0L) + 1
            }
            pendingWrites++
        }
        return true
    }

    fun maybeFlush(context: Context) {
        synchronized(this) {
            if (pendingWrites < FLUSH_EVERY) return
        }
        flush(context)
    }

    /** Serialises the aggregates and writes them in the background. */
    fun flush(context: Context) {
        val appContext = context.applicationContext
        writer.execute {
            val json = synchronized(this) {
                pendingWrites = 0
                toJson()
            }
            try {
                val file = File(File(appContext.filesDir, CoachLog.DIR), FILE_NAME)
                file.parentFile?.mkdirs()
                file.writeText(json)
            } catch (t: Throwable) {
                Log.w(TAG, "could not save aggregates", t)
            }
            TouchOffsetModel.invalidate()
        }
    }

    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "typing-coach-stats").apply { isDaemon = true }
    }

    private fun loadIfNeeded() {
        if (keyStats.isNotEmpty() || totals.isNotEmpty()) return
        val context = helium314.keyboard.latin.settings.Settings.getCurrentContext() ?: return
        loadFrom(context)
    }

    private fun loadFrom(context: Context) {
        try {
            val file = File(File(context.filesDir, CoachLog.DIR), FILE_NAME)
            if (!file.exists()) return
            val json = JSONObject(file.readText())
            synchronized(this) {
                readLongArrays(json.optJSONObject("keys"), keyStats)
                readLongArrays(json.optJSONObject("rows"), rowStats)
                readLongArrays(json.optJSONObject("halves"), halfStats)
                readLongArrays(json.optJSONObject("totals"), totals)
                json.optJSONObject("confusions")?.let { object_ ->
                    for (key in object_.keys()) confusions[key] = object_.optLong(key, 0)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "could not load aggregates", t)
        }
    }

    private fun readLongArrays(json: JSONObject?, target: HashMap<String, LongArray>) {
        if (json == null) return
        for (key in json.keys()) {
            val array = json.optJSONArray(key) ?: continue
            val values = LongArray(array.length())
            for (i in 0 until array.length()) values[i] = array.optLong(i, 0)
            target[key] = values
        }
    }

    private fun toJson(): String {
        val json = JSONObject()
        json.put("version", 1)
        json.put("keys", writeLongArrays(keyStats))
        json.put("rows", writeLongArrays(rowStats))
        json.put("halves", writeLongArrays(halfStats))
        json.put("totals", writeLongArrays(totals))
        val conf = JSONObject()
        for ((key, value) in confusions) conf.put(key, value)
        json.put("confusions", conf)
        return json.toString()
    }

    private fun writeLongArrays(source: Map<String, LongArray>): JSONObject {
        val json = JSONObject()
        for ((key, values) in source) {
            val array = JSONArray()
            values.forEach { array.put(it) }
            json.put(key, array)
        }
        return json
    }

    // ------------------------------------------------------------------ read side

    fun snapshotKeys(): Map<String, LongArray> = synchronized(this) { HashMap(keyStats) }

    fun snapshotRows(sessionKey: String): Map<Int, LongArray> = synchronized(this) {
        val result = HashMap<Int, LongArray>()
        for ((key, value) in rowStats) {
            if (!key.startsWith("$sessionKey|r")) continue
            val row = key.substringAfterLast('r').toIntOrNull() ?: continue
            result[row] = value
        }
        result
    }

    fun snapshotHalves(sessionKey: String): Map<String, LongArray> = synchronized(this) {
        val result = HashMap<String, LongArray>()
        for ((key, value) in halfStats) {
            if (!key.startsWith("$sessionKey|")) continue
            result[key.substringAfterLast('|')] = value
        }
        result
    }

    fun totalsFor(sessionKey: String): LongArray = synchronized(this) {
        (totals[sessionKey] ?: LongArray(4)).copyOf()
    }

    fun allTotals(): Map<String, LongArray> = synchronized(this) { HashMap(totals) }

    fun topConfusions(limit: Int): List<Triple<String, String, Long>> = synchronized(this) {
        confusions.entries
            .sortedByDescending { it.value }
            .take(limit)
            .mapNotNull { entry ->
                val parts = entry.key.split('|')
                if (parts.size != 2) null else Triple(parts[0], parts[1], entry.value)
            }
    }

    fun csv(): String {
        val builder = StringBuilder()
        builder.append("layout;orientation;key;taps;mean_dx;sd_dx;mean_dy;sd_dy;mean_dwell_ms;errors\n")
        val keys = synchronized(this) { HashMap(keyStats) }
        val total = synchronized(this) { HashMap(totals) }
        for ((key, values) in keys.entries.sortedBy { it.key }) {
            val parts = key.split('|')
            if (parts.size < 3) continue
            val n = values[0].coerceAtLeast(1)
            val meanDx = values[1].toDouble() / n
            val meanDy = values[3].toDouble() / n
            val varDx = values[2].toDouble() / n - meanDx * meanDx
            val varDy = values[4].toDouble() / n - meanDy * meanDy
            builder.append(parts[0]).append(';').append(parts[1]).append(';')
            builder.append(parts[2]).append(';').append(values[0]).append(';')
            builder.append(String.format(Locale.ROOT, "%.1f;%.1f;%.1f;%.1f;%.0f", meanDx, kotlin.math.sqrt(varDx.coerceAtLeast(0.0)), meanDy, kotlin.math.sqrt(varDy.coerceAtLeast(0.0)), values[5].toDouble() / n))
            val sessionTotal = total["${parts[0]}|${parts[1]}"]
            builder.append(';').append(sessionTotal?.get(2) ?: 0)
            builder.append('\n')
        }
        builder.append('\n')
        builder.append("confusion;typed;intended;count\n")
        topConfusions(50).forEach { (typed, intended, count) ->
            builder.append("confusion;").append(typed).append(';').append(intended).append(';').append(count).append('\n')
        }
        return builder.toString()
    }

    fun configJson(): String {
        val json = JSONObject()
        json.put("heightScale", "-")
        json.put("note", "geometry settings are in the session events of typing-coach.jsonl")
        json.put("learnedCorrections", CoachSettings.learnedCorrections)
        json.put("learnAfter", CoachSettings.learnAfter)
        json.put("misplacedSpace", CoachSettings.misplacedSpace)
        json.put("misplacedSpaceLearnAfter", CoachSettings.misplacedSpaceLearnAfter)
        json.put("twoCapitals", CoachSettings.twoCapitals)
        json.put("overrideNativeAutocorrect", CoachSettings.overrideNative)
        json.put("rememberReverts", CoachSettings.rememberReverts)
        json.put("blockAfterReverts", CoachSettings.blockAfterReverts)
        json.put("touchOffsetModel", CoachSettings.touchOffset)
        return json.toString(2)
    }

    fun summaryText(): String {
        val totals = allTotals()
        var taps = 0L
        var mistakes = 0L
        var learned = 0L
        for (value in totals.values) {
            taps += value[0]
            mistakes += value[2]
            learned += value[3]
        }
        val perThousand = if (taps > 0) mistakes * 1000.0 / taps else 0.0
        val builder = StringBuilder()
        builder.append("Keys recorded: ").append(taps).append('\n')
        builder.append("Mistakes: ").append(mistakes).append('\n')
        builder.append("Mistakes per 1000 keys: ").append(String.format(Locale.ROOT, "%.1f", perThousand)).append('\n')
        builder.append("Fixes applied by learned rules: ").append(learned).append('\n')
        val top = topConfusions(10)
        if (top.isNotEmpty()) {
            builder.append('\n').append("Most frequent confusions (typed -> intended):").append('\n')
            for ((typed, intended, count) in top) {
                builder.append("  ").append(typed).append(" -> ").append(intended).append(": ").append(count).append('\n')
            }
        }
        return builder.toString()
    }
}
