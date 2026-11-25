package com.adstrosoftware.diceware

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.SupervisorJob
import okio.FileSystem
import okio.Path.Companion.toPath

// DataStore allows one instance per file per process, but apps and tests may create several Generators.
// shortcut: not thread-safe, fine while Generators are only created on the main thread.
private val settingsByPath = mutableMapOf<String, DataStore<Preferences>>()

// Like settings, one library per directory per process, so two never write the same index.
// shortcut: not thread-safe, fine while Generators are only created on the main thread.
private val librariesByPath = mutableMapOf<String, WordListLibrary>()

/**
 * The production Generator: the bundled EFF Word Lists, whose Diceware-format text [readBundled] returns
 * (called off the main thread), plus Custom Word Lists in [wordListsPath], rolled by the OS secure random
 * generator, with settings saved in the DataStore Preferences file at [settingsPath] (ending `.preferences_pb`).
 */
fun createGenerator(
    readBundled: (EffWordList) -> String,
    settingsPath: String,
    wordListsPath: String,
    scope: CoroutineScope,
): Generator = Generator(
    librariesByPath.getOrPut(wordListsPath) {
        // The library outlives any one screen's scope: it is shared by every Generator in the process.
        WordListLibrary(
            { EffWordList.entries.associateWith { WordList.parse(readBundled(it)) } },
            FileSystem.SYSTEM,
            wordListsPath.toPath(),
            CoroutineScope(SupervisorJob()),
        )
    },
    secureRandomBytes(),
    settingsByPath.getOrPut(settingsPath) { PreferenceDataStoreFactory.createWithPath { settingsPath.toPath() } },
    scope,
)

/** For Swift, which can't make a [CoroutineScope]: the Generator lives on the main thread for the app's lifetime. */
fun createGenerator(readBundled: (EffWordList) -> String, settingsPath: String, wordListsPath: String): Generator =
    createGenerator(readBundled, settingsPath, wordListsPath, MainScope())
