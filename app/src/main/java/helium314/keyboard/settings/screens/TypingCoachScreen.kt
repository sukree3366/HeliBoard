// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import helium314.keyboard.coach.Coach
import helium314.keyboard.coach.CoachLog
import helium314.keyboard.coach.CoachSettings
import helium314.keyboard.coach.LearnedStore
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.ExecutorUtils
import helium314.keyboard.latin.utils.NextScreenIcon
import helium314.keyboard.latin.utils.getActivity
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.SearchScreen
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.SettingsDestination
import helium314.keyboard.settings.SettingsWithoutKey
import helium314.keyboard.settings.dialogs.ConfirmationDialog
import helium314.keyboard.settings.dialogs.InfoDialog
import helium314.keyboard.settings.dialogs.TextInputDialog
import helium314.keyboard.settings.filePicker
import helium314.keyboard.settings.preferences.ListPreference
import helium314.keyboard.settings.preferences.Preference
import helium314.keyboard.settings.preferences.SwitchPreference
import helium314.keyboard.settings.preferences.TextInputPreference

@Composable
fun TypingCoachScreen(onClickBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = context.prefs()
    val learnedEnabled = prefs.getBoolean(CoachSettings.PREF_LEARNED_CORRECTIONS, CoachSettings.DEFAULT_LEARNED_CORRECTIONS)
    val spaceEnabled = prefs.getBoolean(CoachSettings.PREF_MISPLACED_SPACE, CoachSettings.DEFAULT_MISPLACED_SPACE)
    val twoCapitalsEnabled = prefs.getBoolean(CoachSettings.PREF_TWO_CAPITALS, CoachSettings.DEFAULT_TWO_CAPITALS)
    val loggingEnabled = prefs.getBoolean(CoachSettings.PREF_LOG_MISTAKES, CoachSettings.DEFAULT_LOG_MISTAKES)
    val keyStatsEnabled = prefs.getBoolean(CoachSettings.PREF_KEY_STATS, CoachSettings.DEFAULT_KEY_STATS)
    val offsetEnabled = prefs.getBoolean(CoachSettings.PREF_TOUCH_OFFSET, CoachSettings.DEFAULT_TOUCH_OFFSET)
    val items = listOf(
        R.string.coach_category_learning,
        SettingsWithoutKey.COACH_LEARNED_LIST,
        CoachSettings.PREF_LEARNED_CORRECTIONS,
        if (learnedEnabled) CoachSettings.PREF_LEARN_AFTER else null,
        CoachSettings.PREF_OVERRIDE_NATIVE,
        if (learnedEnabled) CoachSettings.PREF_REMEMBER_REVERTS else null,
        if (learnedEnabled) CoachSettings.PREF_BLOCK_AFTER_REVERTS else null,
        R.string.coach_category_rules,
        CoachSettings.PREF_MISPLACED_SPACE,
        if (spaceEnabled) CoachSettings.PREF_MISPLACED_SPACE_LEARN_AFTER else null,
        CoachSettings.PREF_TWO_CAPITALS,
        if (twoCapitalsEnabled) CoachSettings.PREF_TWO_CAPITAL_EXCEPTIONS else null,
        R.string.coach_category_logging,
        SettingsWithoutKey.COACH_SUMMARY,
        CoachSettings.PREF_LOG_MISTAKES,
        CoachSettings.PREF_KEY_STATS,
        if (loggingEnabled || keyStatsEnabled) SettingsWithoutKey.COACH_EXPORT else null,
        if (loggingEnabled || keyStatsEnabled) SettingsWithoutKey.COACH_CLEAR else null,
        R.string.coach_category_touch,
        CoachSettings.PREF_TOUCH_OFFSET,
        if (offsetEnabled) SettingsWithoutKey.COACH_TOUCH_RESET else null,
    )
    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.coach_title),
        settings = items,
    )
}

fun createCoachSettings(context: Context) = listOf(
    Setting(
        context, SettingsWithoutKey.COACH_LEARNED_LIST,
        R.string.coach_learned_list, R.string.coach_learned_list_summary
    ) {
        Preference(
            name = stringResource(R.string.coach_learned_list),
            description = stringResource(R.string.coach_learned_list_summary),
            onClick = { SettingsDestination.navigateTo(SettingsDestination.LearnedCorrections) },
        ) { NextScreenIcon() }
    },
    Setting(
        context, CoachSettings.PREF_LEARNED_CORRECTIONS,
        R.string.coach_learned_corrections, R.string.coach_learned_corrections_summary
    ) { setting ->
        SwitchPreference(setting, CoachSettings.DEFAULT_LEARNED_CORRECTIONS, onCheckedChange = { CoachSettings.refresh(context) })
    },
    Setting(context, CoachSettings.PREF_LEARN_AFTER, R.string.coach_learn_after, R.string.coach_learn_after_summary) { setting ->
        ListPreference(
            setting,
            listOf("1" to 1, "2" to 2, "3" to 3),
            CoachSettings.DEFAULT_LEARN_AFTER,
        ) { CoachSettings.refresh(context) }
    },
    Setting(
        context, CoachSettings.PREF_OVERRIDE_NATIVE,
        R.string.coach_override_native, R.string.coach_override_native_summary
    ) { setting ->
        SwitchPreference(setting, CoachSettings.DEFAULT_OVERRIDE_NATIVE, onCheckedChange = { CoachSettings.refresh(context) })
    },
    Setting(
        context, CoachSettings.PREF_REMEMBER_REVERTS,
        R.string.coach_remember_reverts, R.string.coach_remember_reverts_summary
    ) { setting ->
        SwitchPreference(setting, CoachSettings.DEFAULT_REMEMBER_REVERTS, onCheckedChange = { CoachSettings.refresh(context) })
    },
    Setting(context, CoachSettings.PREF_BLOCK_AFTER_REVERTS, R.string.coach_block_after_reverts) { setting ->
        ListPreference(
            setting,
            listOf("1" to 1, "2" to 2, "3" to 3, "5" to 5),
            CoachSettings.DEFAULT_BLOCK_AFTER_REVERTS,
        ) { CoachSettings.refresh(context) }
    },
    Setting(
        context, CoachSettings.PREF_MISPLACED_SPACE,
        R.string.coach_misplaced_space, R.string.coach_misplaced_space_summary
    ) { setting ->
        SwitchPreference(setting, CoachSettings.DEFAULT_MISPLACED_SPACE, onCheckedChange = { CoachSettings.refresh(context) })
    },
    Setting(
        context, CoachSettings.PREF_MISPLACED_SPACE_LEARN_AFTER,
        R.string.coach_learn_after, R.string.coach_misplaced_space_learn_after_summary
    ) { setting ->
        ListPreference(
            setting,
            listOf("1" to 1, "2" to 2, "3" to 3),
            CoachSettings.DEFAULT_MISPLACED_SPACE_LEARN_AFTER,
        ) { CoachSettings.refresh(context) }
    },
    Setting(
        context, CoachSettings.PREF_TWO_CAPITALS,
        R.string.coach_two_capitals, R.string.coach_two_capitals_summary
    ) { setting ->
        SwitchPreference(setting, CoachSettings.DEFAULT_TWO_CAPITALS, onCheckedChange = { CoachSettings.refresh(context) })
    },
    Setting(
        context, CoachSettings.PREF_TWO_CAPITAL_EXCEPTIONS,
        R.string.coach_two_capital_exceptions, R.string.coach_two_capital_exceptions_summary
    ) { setting ->
        TextInputPreference(setting, CoachSettings.DEFAULT_TWO_CAPITAL_EXCEPTIONS)
    },
    Setting(context, SettingsWithoutKey.COACH_SUMMARY, R.string.coach_summary) { setting ->
        var showDialog by rememberSaveable { mutableStateOf(false) }
        var summary by remember { mutableStateOf("") }
        Preference(
            name = setting.title,
            description = stringResource(R.string.coach_summary_summary),
            onClick = {
                LearnedStore.ensureLoaded(context)
                summary = CoachLog.summary(context)
                showDialog = true
            },
        )
        if (showDialog) {
            InfoDialog(summary.ifBlank { context.getString(R.string.coach_no_entries) }) { showDialog = false }
        }
    },
    Setting(
        context, CoachSettings.PREF_LOG_MISTAKES,
        R.string.coach_log_mistakes, R.string.coach_log_mistakes_summary
    ) { setting ->
        SwitchPreference(setting, CoachSettings.DEFAULT_LOG_MISTAKES, onCheckedChange = { CoachSettings.refresh(context) })
    },
    Setting(
        context, CoachSettings.PREF_KEY_STATS,
        R.string.coach_key_stats, R.string.coach_key_stats_summary
    ) { setting ->
        SwitchPreference(setting, CoachSettings.DEFAULT_KEY_STATS, onCheckedChange = { CoachSettings.refresh(context) })
    },
    Setting(context, SettingsWithoutKey.COACH_EXPORT, R.string.coach_export) { setting ->
        var message by rememberSaveable { mutableStateOf<String?>(null) }
        val handler = remember { Handler(Looper.getMainLooper()) }
        val launcher = filePicker { uri ->
            ExecutorUtils.getBackgroundExecutor(ExecutorUtils.KEYBOARD).execute {
                val success = try {
                    context.getActivity()?.contentResolver?.openOutputStream(uri)?.use { stream ->
                        CoachLog.export(context, stream)
                    } ?: false
                } catch (t: Throwable) {
                    false
                }
                handler.post {
                    message = context.getString(if (success) R.string.coach_export_done else R.string.coach_export_failed)
                }
            }
        }
        Preference(
            name = setting.title,
            description = stringResource(R.string.coach_export_summary),
            onClick = {
                launcher.launch(
                    Intent(Intent.ACTION_CREATE_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("application/zip")
                        .putExtra(Intent.EXTRA_TITLE, "typing-coach.zip")
                )
            },
        )
        message?.let { InfoDialog(it) { message = null } }
    },
    Setting(context, SettingsWithoutKey.COACH_CLEAR, R.string.coach_clear) { setting ->
        var showDialog by rememberSaveable { mutableStateOf(false) }
        Preference(
            name = setting.title,
            description = stringResource(R.string.coach_clear_summary),
            onClick = { showDialog = true },
        )
        if (showDialog) {
            ConfirmationDialog(
                onDismissRequest = { showDialog = false },
                onConfirmed = {
                    showDialog = false
                    CoachLog.clear(context)
                },
                content = { Text(stringResource(R.string.coach_clear_confirm)) },
            )
        }
    },
    Setting(
        context, CoachSettings.PREF_TOUCH_OFFSET,
        R.string.coach_touch_offset, R.string.coach_touch_offset_summary
    ) { setting ->
        SwitchPreference(
            setting, CoachSettings.DEFAULT_TOUCH_OFFSET,
            onCheckedChange = {
                CoachSettings.refresh(context)
                Coach.resetTouchOffsetModel(context)
            }
        )
    },
    Setting(context, SettingsWithoutKey.COACH_TOUCH_RESET, R.string.coach_touch_reset) { setting ->
        var showDialog by rememberSaveable { mutableStateOf(false) }
        Preference(
            name = setting.title,
            description = Coach.touchOffsetReadout(context),
            onClick = { showDialog = true },
        )
        if (showDialog) {
            ConfirmationDialog(
                onDismissRequest = { showDialog = false },
                onConfirmed = {
                    showDialog = false
                    Coach.resetTouchOffsetModel(context)
                },
                content = { Text(stringResource(R.string.coach_touch_reset_confirm)) },
            )
        }
    },
)

@Composable
fun LearnedCorrectionsScreen(onClickBack: () -> Unit) {
    val context = LocalContext.current
    LearnedStore.ensureLoaded(context)
    var entries by remember { mutableStateOf(LearnedStore.entries()) }
    var keepWords by remember { mutableStateOf(LearnedStore.keepAsTypedWords(currentLanguage())) }
    var showAdd by rememberSaveable { mutableStateOf(false) }

    fun reload() {
        entries = LearnedStore.entries()
        keepWords = LearnedStore.keepAsTypedWords(currentLanguage())
    }

    val importLauncher = filePicker { uri ->
        ExecutorUtils.getBackgroundExecutor(ExecutorUtils.KEYBOARD).execute {
            val text = try {
                context.getActivity()?.contentResolver?.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            } catch (t: Throwable) {
                null
            }
            if (text != null) {
                LearnedStore.importPairs(context, currentLanguage(), text)
            }
        }
    }
    val exportLauncher = filePicker { uri ->
        ExecutorUtils.getBackgroundExecutor(ExecutorUtils.KEYBOARD).execute {
            try {
                context.getActivity()?.contentResolver?.openOutputStream(uri)?.use {
                    it.write(LearnedStore.exportText().toByteArray())
                }
            } catch (t: Throwable) {
                // the user can simply export again
            }
        }
    }

    SearchScreen(
        onClickBack = onClickBack,
        title = { Text(stringResource(R.string.coach_learned_list)) },
        filteredItems = { term ->
            if (term.isBlank()) emptyList()
            else entries.filter { it.typo.contains(term, true) || it.fix.contains(term, true) }
        },
        itemContent = { entry ->
            val stateText = when (entry.state) {
                LearnedStore.STATE_LEARNED -> stringResource(R.string.coach_state_learned)
                LearnedStore.STATE_BLOCKED -> stringResource(R.string.coach_state_blocked)
                else -> stringResource(R.string.coach_state_candidate)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("${entry.typo} -> ${entry.fix}", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "$stateText, ${entry.count}x, ${entry.reverts} " + stringResource(R.string.coach_reverts),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (entry.state == LearnedStore.STATE_BLOCKED) {
                    TextButton(onClick = {
                        LearnedStore.unblock(context, entry.language, entry.typo)
                        reload()
                    }) { Text(stringResource(R.string.coach_unblock)) }
                }
                TextButton(onClick = {
                    LearnedStore.removeEntry(context, entry.language, entry.typo)
                    reload()
                }) { Text(stringResource(R.string.coach_delete)) }
            }
        },
        menu = listOf(
            stringResource(R.string.coach_add) to { showAdd = true },
            stringResource(R.string.coach_import) to {
                importLauncher.launch(
                    Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("text/plain")
                )
            },
            stringResource(R.string.coach_export_list) to {
                exportLauncher.launch(
                    Intent(Intent.ACTION_CREATE_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_TITLE, "learned-corrections.txt")
                )
            },
        ),
        content = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.coach_learned_list_summary),
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
                entries.forEach { entry ->
                    val stateText = when (entry.state) {
                        LearnedStore.STATE_LEARNED -> stringResource(R.string.coach_state_learned)
                        LearnedStore.STATE_BLOCKED -> stringResource(R.string.coach_state_blocked)
                        else -> stringResource(R.string.coach_state_candidate)
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("${entry.typo} -> ${entry.fix}", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "$stateText, ${entry.count}x, ${entry.reverts} " + stringResource(R.string.coach_reverts),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (entry.state == LearnedStore.STATE_BLOCKED) {
                            TextButton(onClick = {
                                LearnedStore.unblock(context, entry.language, entry.typo)
                                reload()
                            }) { Text(stringResource(R.string.coach_unblock)) }
                        }
                        TextButton(onClick = {
                            LearnedStore.removeEntry(context, entry.language, entry.typo)
                            reload()
                        }) { Text(stringResource(R.string.coach_delete)) }
                    }
                }
                if (keepWords.isNotEmpty()) {
                    Text(
                        stringResource(R.string.coach_keep_as_typed),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                    keepWords.forEach { word ->
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(word, style = MaterialTheme.typography.bodyLarge)
                            TextButton(onClick = {
                                LearnedStore.removeKeepAsTyped(context, currentLanguage(), word)
                                reload()
                            }) { Text(stringResource(R.string.coach_delete)) }
                        }
                    }
                }
                if (entries.isEmpty() && keepWords.isEmpty()) {
                    Text(
                        stringResource(R.string.coach_no_entries),
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
    )
    if (showAdd) {
        TextInputDialog(
            onDismissRequest = { showAdd = false },
            onConfirmed = { value ->
                val parts = value.split("=", "->")
                if (parts.size == 2) {
                    LearnedStore.addManual(context, currentLanguage(), parts[0], parts[1])
                } else if (value.isNotBlank()) {
                    // a single word is never changed again, not even by auto-correction
                    LearnedStore.addKeepAsTyped(context, currentLanguage(), value.trim())
                }
                reload()
                showAdd = false
            },
            initialText = "",
            title = { Text(stringResource(R.string.coach_add)) },
            description = { Text(stringResource(R.string.coach_add_summary)) },
            checkTextValid = { it.isNotBlank() },
        )
    }
}

private fun currentLanguage(): String = try {
    Settings.getValues()?.mLocale?.toString() ?: "en"
} catch (t: Throwable) {
    "en"
}
