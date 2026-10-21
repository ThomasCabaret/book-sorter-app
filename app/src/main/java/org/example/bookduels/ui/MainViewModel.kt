package org.example.bookduels.ui

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.example.bookduels.core.BabelioCsv
import org.example.bookduels.core.Book
import org.example.bookduels.core.Duel
import org.example.bookduels.core.DuelEngine
import org.example.bookduels.core.HistoryEntry
import org.example.bookduels.core.RankingReport
import org.example.bookduels.data.BookCatalog
import org.example.bookduels.data.CatalogImportResult
import org.example.bookduels.data.EventStore
import org.example.bookduels.data.PortableArchive
import java.io.ByteArrayOutputStream

internal data class SelectionFeedback(
    val duel: Duel,
    val chosenBookId: String?,
    val label: String,
)

private data class RecentDecision(val sequence: Long, val duel: Duel)

internal data class AppUiState(
    val loading: Boolean = true,
    val busy: Boolean = false,
    val catalog: List<Book> = emptyList(),
    val report: RankingReport? = null,
    val history: List<HistoryEntry> = emptyList(),
    val revision: Long = 0,
    val restorableCount: Int = 0,
    val selectionFeedback: SelectionFeedback? = null,
    val quickUndoCount: Int = 0,
    val message: String? = null,
    val error: String? = null,
)

internal class MainViewModel(application: Application) : AndroidViewModel(application) {
    private companion object {
        const val SELECTION_FEEDBACK_MS = 250L
        const val QUICK_UNDO_LIMIT = 3
    }

    private val store = EventStore(application)
    private val state = MutableStateFlow(AppUiState())
    private val recentDecisions = ArrayDeque<RecentDecision>()
    val uiState: StateFlow<AppUiState> = state.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    store.mergeBundled(BookCatalog.loadBundled(application))
                    store.applySagaCatalogCurationV1()
                }
                refresh()
            } catch (e: Exception) {
                state.value = state.value.copy(loading = false, error = e.message ?: "Catalog error")
            }
        }
    }

    private suspend fun refresh(message: String? = null, preferredDuel: Duel? = null) {
        val undoCount = recentDecisions.size
        val snapshot = withContext(Dispatchers.IO) {
            val saved = store.snapshot()
            val catalog = saved.catalog.map { it.book }
            val replay = org.example.bookduels.core.EventReplay.reconstruct(saved.events)
            val analyzed = DuelEngine.analyze(catalog, replay.activeEvents, replay.lastSequence)
            val byId = catalog.associateBy { it.id }
            val restoredDuel = preferredDuel?.let { previous ->
                val first = byId[previous.first.id]
                val second = byId[previous.second.id]
                if (first?.enabled == true && second?.enabled == true) Duel(first, second) else null
            }
            val report = if (restoredDuel == null) analyzed else analyzed.copy(duel = restoredDuel)
            AppUiState(
                loading = false, catalog = catalog, report = report, history = replay.history,
                revision = replay.lastSequence, restorableCount = replay.restorableCount,
                quickUndoCount = undoCount, message = message,
            )
        }
        state.value = snapshot
    }

    fun record(duel: Duel, winner: String?) {
        val current = state.value
        if (current.loading || current.busy || current.report?.duel != duel) return
        if (winner != null && winner != duel.first.id && winner != duel.second.id) return
        val label = when (winner) {
            duel.first.id -> duel.first.title
            duel.second.id -> duel.second.title
            else -> "Joker"
        }
        state.value = current.copy(busy = true, selectionFeedback = SelectionFeedback(duel, winner, label), message = null)
        val feedbackStartedAt = SystemClock.elapsedRealtime()
        viewModelScope.launch {
            try {
                val sequence = withContext(Dispatchers.IO) { store.record(duel.first.id, duel.second.id, winner) }
                recentDecisions.addLast(RecentDecision(sequence, duel))
                while (recentDecisions.size > QUICK_UNDO_LIMIT) recentDecisions.removeFirst()
                val remainingFeedback = SELECTION_FEEDBACK_MS - (SystemClock.elapsedRealtime() - feedbackStartedAt)
                if (remainingFeedback > 0) delay(remainingFeedback)
                refresh()
            } catch (e: Exception) { refresh("Échec de l'enregistrement : ${e.message}") }
        }
    }

    /** Appends a compensating event for the most recent choice from this session. */
    fun undoLatestDecision() {
        val current = state.value
        val latest = recentDecisions.lastOrNull() ?: return
        if (current.loading || current.busy) return
        state.value = current.copy(busy = true, selectionFeedback = null, message = null)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { store.invalidateLatest(latest.sequence, current.revision) }
                recentDecisions.removeLast()
                refresh("Dernier choix annulé", preferredDuel = latest.duel)
            } catch (e: Exception) {
                recentDecisions.clear()
                refresh("Annulation non appliquée : ${e.message}")
            }
        }
    }

    fun invalidateFrom(sequence: Long, expectedRevision: Long) {
        recentDecisions.clear()
        performOperation {
            val count = store.invalidateFrom(sequence, expectedRevision)
            "$count votes/jokers neutralisés sans suppression"
        }
    }

    fun reinstateLatest(expectedRevision: Long) {
        recentDecisions.clear()
        performOperation {
            val count = store.reinstateLatestBatch(expectedRevision)
            "$count votes/jokers restaurés"
        }
    }

    fun importCsv(uri: Uri) = performOperation {
        val app = getApplication<Application>()
        val bytes = app.contentResolver.openInputStream(uri)?.use { stream ->
            val result = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                require(result.size() + count <= 5_000_000) { "CSV larger than 5 MB" }
                result.write(buffer, 0, count)
            }
            result.toByteArray()
        } ?: error("Cannot open CSV")
        val parsed = BabelioCsv.parse(bytes)
        val merged = store.importBabelio(parsed.readBooks, parsed.skippedUnRead)
        importSummary(merged)
    }

    fun addManually(title: String, author: String, isbn13: String?) = performOperation {
        importSummary(store.addManually(title, author, isbn13))
    }

    fun setEnabled(id: String, enabled: Boolean) {
        recentDecisions.clear()
        performOperation {
            store.setEnabled(id, enabled)
            if (enabled) "Livre réintégré au classement" else "Livre mis de côté (duels conservés)"
        }
    }

    fun exportBundle(uri: Uri) = performOperation {
        val app = getApplication<Application>()
        val snapshot = store.snapshot()
        val stream = app.contentResolver.openOutputStream(uri, "w") ?: error("Cannot create archive")
        PortableArchive.write(snapshot, stream)
        "Archive exportée : ${snapshot.catalog.size} livres, ${snapshot.events.size} événements"
    }

    private fun importSummary(result: CatalogImportResult) =
        "Import : ${result.added} nouveaux, ${result.existing} déjà présents, " +
            "${result.editionAliasesAdded} nouveaux ISBN associés, ${result.ambiguous} ambigus ignorés, " +
            "${result.skippedUnread} non lus ignorés"

    private fun performOperation(work: () -> String) {
        val current = state.value
        if (current.busy || current.loading) return
        state.value = current.copy(busy = true)
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { work() }
                refresh(result)
            } catch (e: Exception) {
                refresh("Opération non appliquée : ${e.message}")
            }
        }
    }

    fun showMessage(text: String) { state.value = state.value.copy(message = text) }
    fun dismissMessage() { state.value = state.value.copy(message = null) }

    override fun onCleared() {
        store.close()
    }
}
