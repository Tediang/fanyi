package com.tediang.quicktranslate

import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap

internal data class LaunchTiming(
    val entry: TranslationEntry,
    val uiVisibleMs: Long? = null,
    val requestDispatchMs: Long? = null,
    val firstTextMs: Long? = null,
    val totalMs: Long? = null,
)

/** In-memory, content-free launch timing used by diagnostics and repeatable benchmarks. */
internal object LaunchPerformance {
    private data class Record(
        val entry: TranslationEntry,
        val startedAt: Long,
        val timing: LaunchTiming,
    )

    private val records = ConcurrentHashMap<String, Record>()

    fun begin(launch: TranslationLaunch) {
        if (records.size >= MAX_RECORDS) {
            records.entries.minByOrNull { it.value.startedAt }?.let { records.remove(it.key) }
        }
        records[launch.id] = Record(
            entry = launch.entry,
            startedAt = SystemClock.elapsedRealtime(),
            timing = LaunchTiming(entry = launch.entry),
        )
    }

    fun markUiVisible(launchId: String) {
        records.computeIfPresent(launchId) { _, record ->
            record.copy(timing = record.timing.copy(uiVisibleMs = SystemClock.elapsedRealtime() - record.startedAt))
        }
    }

    fun markRequestDispatched(launchId: String) {
        records.computeIfPresent(launchId) { _, record ->
            record.copy(timing = record.timing.copy(requestDispatchMs = SystemClock.elapsedRealtime() - record.startedAt))
        }
    }

    fun markTranslationFinished(launchId: String, diagnostics: TranslationDiagnostics) {
        records.computeIfPresent(launchId) { _, record ->
            record.copy(
                timing = record.timing.copy(
                    firstTextMs = diagnostics.firstTextMs,
                    totalMs = diagnostics.totalMs,
                ),
            )
        }
    }

    fun continueAs(previousLaunchId: String, resolvedLaunch: TranslationLaunch) {
        val previous = records[previousLaunchId] ?: return
        if (previous.timing.uiVisibleMs != null) return
        records.remove(previousLaunchId)
        records[resolvedLaunch.id] = previous.copy(
            entry = resolvedLaunch.entry,
            timing = previous.timing.copy(entry = resolvedLaunch.entry),
        )
    }

    fun timing(launchId: String): LaunchTiming? = records[launchId]?.timing

    fun uiVisibleSamples(entry: TranslationEntry): List<Long> = records.values
        .filter { it.entry == entry }
        .mapNotNull { it.timing.uiVisibleMs }

    fun requestDispatchSamples(entry: TranslationEntry): List<Long> = records.values
        .filter { it.entry == entry }
        .mapNotNull { it.timing.requestDispatchMs }

    fun firstTextSamples(entry: TranslationEntry): List<Long> = records.values
        .filter { it.entry == entry }
        .mapNotNull { it.timing.firstTextMs }

    fun totalSamples(entry: TranslationEntry): List<Long> = records.values
        .filter { it.entry == entry }
        .mapNotNull { it.timing.totalMs }

    fun clear() {
        records.clear()
    }

    private const val MAX_RECORDS = 100
}
