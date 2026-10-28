// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.coach

import android.content.Context
import helium314.keyboard.latin.utils.ExecutorUtils
import helium314.keyboard.latin.utils.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * Storage of everything the learning autocorrect layer knows: learned (typo, fix) pairs, learned
 * misplaced-space phrases, blocked pairs and the "keep as typed" words.
 *
 * The data is kept in memory (a few dozen entries at most) so lookups on the input thread are just
 * a hash map access. All file access happens on the background executor.
 *
 * A single JSON file in the app private storage holds all languages. It is included in HeliBoard's
 * backup by the `coach/.*` pattern in `BackupRestorePreference`.
 */
object LearnedStore {
    private const val TAG = "LearnedStore"
    private const val DIR = "coach"
    private const val FILE_NAME = "learned.json"
    private const val VERSION = 1

    const val STATE_CANDIDATE = 0
    const val STATE_LEARNED = 1
    const val STATE_BLOCKED = 2

    /** One entry as shown in the settings screen. */
    data class Entry(
        val typo: String,
        val fix: String,
        val count: Int,
        val state: Int,
        val reverts: Int,
        val language: String,
    )

    private class Record(
        var fix: String,
        var count: Int,
        var state: Int,
        var lastSeen: Long,
        var reverts: Int,
    )

    private class LanguageData {
        /** key: lower case typo */
        val pairs = HashMap<String, Record>()
        /** key: lower case "first second", value: fix of the same length */
        val phrases = HashMap<String, Record>()
        /** lower case words that must never be changed */
        val keep = HashSet<String>()
    }

    private val lock = Any()
    private val languages = HashMap<String, LanguageData>()
    @Volatile private var loaded = false
    @Volatile private var loading = false
    @Volatile private var dirty = false
    private var lastSaveTime = 0L

    fun isLoaded() = loaded

    /** Loads the learned data synchronously. Only for the settings screen, never for typing. */
    fun ensureLoaded(context: Context) {
        if (loaded) return
        try {
            loadSync(context.applicationContext)
        } catch (t: Throwable) {
            Log.w(TAG, "could not load learned corrections", t)
            loaded = true
        }
    }

    /** Loads the learned data in the background. Safe to call repeatedly. */
    fun loadAsync(context: Context) {
        if (loaded || loading) return
        loading = true
        val appContext = context.applicationContext
        ExecutorUtils.getBackgroundExecutor(ExecutorUtils.KEYBOARD).execute {
            try {
                loadSync(appContext)
            } catch (t: Throwable) {
                Log.w(TAG, "could not load learned corrections", t)
            } finally {
                loading = false
            }
        }
    }

    private fun loadSync(context: Context) {
        val file = file(context)
        if (!file.exists()) {
            loaded = true
            return
        }
        val json = JSONObject(file.readText())
        synchronized(lock) {
            languages.clear()
            val langs = json.optJSONObject("languages") ?: JSONObject()
            for (language in langs.keys()) {
                val langJson = langs.optJSONObject(language) ?: continue
                val data = LanguageData()
                readRecords(langJson.optJSONObject("pairs"), data.pairs)
                readRecords(langJson.optJSONObject("phrases"), data.phrases)
                langJson.optJSONArray("keep")?.let { keep ->
                    for (i in 0 until keep.length()) {
                        keep.optString(i).takeIf { it.isNotEmpty() }?.let { data.keep.add(it.lowercase(Locale.ROOT)) }
                    }
                }
                languages[language] = data
            }
        }
        loaded = true
    }

    private fun readRecords(json: JSONObject?, into: HashMap<String, Record>) {
        if (json == null) return
        for (key in json.keys()) {
            val array = json.optJSONArray(key) ?: continue
            if (array.length() < 5) continue
            into[key] = Record(
                fix = array.optString(0, ""),
                count = array.optInt(1, 0),
                state = array.optInt(2, STATE_CANDIDATE),
                lastSeen = array.optLong(3, 0L),
                reverts = array.optInt(4, 0),
            )
        }
    }

    // ------------------------------------------------------------------ reading (input thread)

    /** The learned fix for exactly this typo, with the casing of [typedWord] applied. */
    fun replacement(typedWord: String, language: String): String? {
        if (!loaded) return null
        val key = typedWord.lowercase(Locale.ROOT)
        val record = synchronized(lock) { languages[language]?.pairs?.get(key) } ?: return null
        if (record.state != STATE_LEARNED) return null
        return LearningRules.conformCase(typedWord, record.fix)
    }

    /** Whether this typo is a learned (or blocked) pair. Used to tell the coach's own fixes apart. */
    fun isLearnedPair(typedWord: String, committedWord: String, language: String): Boolean {
        if (!loaded) return false
        val record = synchronized(lock) {
            languages[language]?.pairs?.get(typedWord.lowercase(Locale.ROOT))
        } ?: return false
        return record.state == STATE_LEARNED && record.fix.equals(committedWord, ignoreCase = true)
    }

    /** A learned misplaced-space fix for the two given words, e.g. "oka yi" -> "okay i". */
    fun phraseFix(first: String, second: String, language: String): String? {
        if (!loaded) return null
        if (first.isEmpty() || second.isEmpty()) return null
        val key = (first + " " + second).lowercase(Locale.ROOT)
        val record = synchronized(lock) { languages[language]?.phrases?.get(key) } ?: return null
        if (record.state != STATE_LEARNED) return null
        // keep the casing of the first word, it is usually at the start of a sentence
        val fix = record.fix
        return if (first[0].isUpperCase() && fix.isNotEmpty() && fix[0].isLowerCase()) {
            fix.replaceFirstChar { it.uppercase(Locale.ROOT) }
        } else fix
    }

    /** True when the user asked to never change this word. */
    fun isKeptAsTyped(word: String, language: String): Boolean {
        if (!loaded) return false
        return synchronized(lock) { languages[language]?.keep?.contains(word.lowercase(Locale.ROOT)) } == true
    }

    fun entryFor(typedWord: String, language: String): Entry? {
        val record = synchronized(lock) {
            languages[language]?.pairs?.get(typedWord.lowercase(Locale.ROOT))
        } ?: return null
        return Entry(typedWord, record.fix, record.count, record.state, record.reverts, language)
    }

    fun entries(language: String? = null): List<Entry> = synchronized(lock) {
        val result = ArrayList<Entry>()
        for ((lang, data) in languages) {
            if (language != null && lang != language) continue
            for ((typo, record) in data.pairs) {
                result.add(Entry(typo, record.fix, record.count, record.state, record.reverts, lang))
            }
        }
        result.sortedWith(compareBy({ it.state }, { -it.count }, { it.typo }))
    }

    // ------------------------------------------------------------------ learning (background)

    /**
     * Counts one sighting of a (typo, fix) pair.
     * @return true when the pair reached the threshold and is now applied automatically
     */
    fun recordPair(
        context: Context,
        language: String,
        typo: String,
        fix: String,
        learnAfter: Int,
    ): Boolean {
        if (!loaded) return false
        val key = typo.lowercase(Locale.ROOT)
        var becameLearned = false
        synchronized(lock) {
            val data = languages.getOrPut(language) { LanguageData() }
            val record = data.pairs[key]
            if (record != null) {
                if (record.state == STATE_BLOCKED) return false
                if (!record.fix.equals(fix, ignoreCase = true)) {
                    // the user changed their mind, start over with the new fix
                    record.fix = fix.lowercase(Locale.ROOT)
                    record.count = 0
                    record.state = STATE_CANDIDATE
                }
                record.count++
                record.lastSeen = System.currentTimeMillis()
                if (record.state == STATE_CANDIDATE && record.count >= learnAfter) {
                    record.state = STATE_LEARNED
                    becameLearned = true
                }
            } else {
                val newRecord = Record(
                    fix = fix.lowercase(Locale.ROOT),
                    count = 1,
                    state = if (learnAfter <= 1) STATE_LEARNED else STATE_CANDIDATE,
                    lastSeen = System.currentTimeMillis(),
                    reverts = 0,
                )
                data.pairs[key] = newRecord
                becameLearned = newRecord.state == STATE_LEARNED
            }
        }
        markDirty(context)
        return becameLearned
    }

    /** Counts one sighting of a misplaced-space phrase; the fix must have the same length. */
    fun recordPhrase(
        context: Context,
        language: String,
        typedPhrase: String,
        fix: String,
        learnAfter: Int,
    ): Boolean {
        if (!loaded) return false
        val key = typedPhrase.lowercase(Locale.ROOT)
        var becameLearned = false
        synchronized(lock) {
            val data = languages.getOrPut(language) { LanguageData() }
            val record = data.phrases[key]
            if (record != null) {
                if (record.state == STATE_BLOCKED) return false
                if (!record.fix.equals(fix, ignoreCase = true)) {
                    record.fix = fix
                    record.count = 0
                    record.state = STATE_CANDIDATE
                }
                record.count++
                record.lastSeen = System.currentTimeMillis()
                if (record.state == STATE_CANDIDATE && record.count >= learnAfter) {
                    record.state = STATE_LEARNED
                    becameLearned = true
                }
            } else {
                val newRecord = Record(
                    fix = fix,
                    count = 1,
                    state = if (learnAfter <= 1) STATE_LEARNED else STATE_CANDIDATE,
                    lastSeen = System.currentTimeMillis(),
                    reverts = 0,
                )
                data.phrases[key] = newRecord
                becameLearned = newRecord.state == STATE_LEARNED
            }
        }
        markDirty(context)
        return becameLearned
    }

    /**
     * The user reverted a correction. Reverts of learned pairs are counted and, after
     * [blockAfter] reverts, the pair is blocked for good.
     * @return true when the pair was blocked by this revert
     */
    fun recordRevert(context: Context, language: String, typo: String, blockAfter: Int): Boolean {
        if (!loaded) return false
        var blocked = false
        synchronized(lock) {
            val record = languages[language]?.pairs?.get(typo.lowercase(Locale.ROOT)) ?: return false
            record.reverts++
            record.lastSeen = System.currentTimeMillis()
            if (record.reverts >= blockAfter) {
                record.state = STATE_BLOCKED
                blocked = true
            }
        }
        markDirty(context)
        return blocked
    }

    /** Adds a word to the "keep as typed" list. */
    fun addKeepAsTyped(context: Context, language: String, word: String) {
        if (!loaded || word.isBlank()) return
        synchronized(lock) {
            languages.getOrPut(language) { LanguageData() }.keep.add(word.lowercase(Locale.ROOT))
        }
        markDirty(context)
    }

    fun removeKeepAsTyped(context: Context, language: String, word: String) {
        synchronized(lock) {
            languages[language]?.keep?.remove(word.lowercase(Locale.ROOT))
        }
        markDirty(context)
    }

    fun keepAsTypedWords(language: String): List<String> = synchronized(lock) {
        languages[language]?.keep?.sorted().orEmpty()
    }

    // ------------------------------------------------------------------ editing (settings screen)

    fun removeEntry(context: Context, language: String, typo: String) {
        synchronized(lock) {
            languages[language]?.pairs?.remove(typo.lowercase(Locale.ROOT))
        }
        markDirty(context)
    }

    fun unblock(context: Context, language: String, typo: String) {
        synchronized(lock) {
            val record = languages[language]?.pairs?.get(typo.lowercase(Locale.ROOT)) ?: return
            record.state = STATE_LEARNED
            record.reverts = 0
            record.count = maxOf(record.count, 2)
        }
        markDirty(context)
    }

    fun addManual(context: Context, language: String, typo: String, fix: String) {
        if (typo.isBlank() || fix.isBlank()) return
        synchronized(lock) {
            val data = languages.getOrPut(language) { LanguageData() }
            data.pairs[typo.trim().lowercase(Locale.ROOT)] = Record(
                fix = fix.trim().lowercase(Locale.ROOT),
                count = 2,
                state = STATE_LEARNED,
                lastSeen = System.currentTimeMillis(),
                reverts = 0,
            )
            data.keep.remove(typo.trim().lowercase(Locale.ROOT))
        }
        markDirty(context)
    }

    /** Imports `typo=fix` lines (the format of the desktop tool's learned section). */
    fun importPairs(context: Context, language: String, text: String): Int {
        var imported = 0
        synchronized(lock) {
            val data = languages.getOrPut(language) { LanguageData() }
            for (rawLine in text.lineSequence()) {
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("[")) continue
                val separator = line.indexOf('=')
                if (separator <= 0) continue
                val typo = line.substring(0, separator).trim()
                val fix = line.substring(separator + 1).trim()
                if (typo.isEmpty() || fix.isEmpty()) continue
                if (typo.equals(fix, ignoreCase = true)) continue
                data.pairs[typo.lowercase(Locale.ROOT)] = Record(
                    fix = fix.lowercase(Locale.ROOT),
                    count = 2,
                    state = STATE_LEARNED,
                    lastSeen = System.currentTimeMillis(),
                    reverts = 0,
                )
                imported++
            }
        }
        if (imported > 0) markDirty(context)
        return imported
    }

    fun exportText(language: String? = null): String {
        val builder = StringBuilder()
        builder.append("# HeliBoard learned corrections\n")
        val all = entries(language)
        for (entry in all) {
            if (entry.state == STATE_LEARNED) {
                builder.append(entry.typo).append('=').append(entry.fix).append('\n')
            }
        }
        builder.append("# candidates and blocked pairs\n")
        for (entry in all) {
            if (entry.state != STATE_LEARNED) {
                val state = if (entry.state == STATE_BLOCKED) "blocked" else "candidate"
                builder.append("# ").append(state).append(" (").append(entry.count).append("x): ")
                builder.append(entry.typo).append('=').append(entry.fix).append('\n')
            }
        }
        return builder.toString()
    }

    fun clear(context: Context) {
        synchronized(lock) { languages.clear() }
        loaded = true
        ExecutorUtils.getBackgroundExecutor(ExecutorUtils.KEYBOARD).execute {
            file(context).delete()
        }
    }

    // ------------------------------------------------------------------ persistence

    fun saveNow(context: Context) {
        if (!dirty) return
        saveAsync(context, 0)
    }

    private fun markDirty(context: Context) {
        dirty = true
        saveAsync(context, 1500)
    }

    @Synchronized
    private fun saveAsync(context: Context, delayMs: Long) {
        val now = System.currentTimeMillis()
        // throttle: at most one write every 1.5 s
        if (delayMs == 0L && now - lastSaveTime < 1000) return
        lastSaveTime = now
        dirty = false
        val appContext = context.applicationContext
        ExecutorUtils.getBackgroundExecutor(ExecutorUtils.KEYBOARD).execute {
            try {
                // serialise on the background thread, never on the input thread
                val snapshot = synchronized(lock) { toJson() }
                val file = file(appContext)
                file.parentFile?.mkdirs()
                val temp = File(file.parentFile, "$FILE_NAME.tmp")
                temp.writeText(snapshot)
                if (!temp.renameTo(file)) {
                    file.writeText(snapshot)
                    temp.delete()
                }
            } catch (t: Throwable) {
                dirty = true
                Log.w(TAG, "could not save learned corrections", t)
            }
        }
    }

    private fun toJson(): String {
        val languagesJson = JSONObject()
        for ((language, data) in languages) {
            if (data.pairs.isEmpty() && data.phrases.isEmpty() && data.keep.isEmpty()) continue
            val langJson = JSONObject()
            langJson.put("pairs", recordsToJson(data.pairs))
            langJson.put("phrases", recordsToJson(data.phrases))
            langJson.put("keep", JSONArray(data.keep.toList()))
            languagesJson.put(language, langJson)
        }
        return JSONObject().put("version", VERSION).put("languages", languagesJson).toString()
    }

    private fun recordsToJson(records: Map<String, Record>): JSONObject {
        val json = JSONObject()
        for ((key, record) in records) {
            json.put(
                key,
                JSONArray(listOf(record.fix, record.count, record.state, record.lastSeen, record.reverts)),
            )
        }
        return json
    }

    private fun file(context: Context) = File(File(context.filesDir, DIR), FILE_NAME)
}
