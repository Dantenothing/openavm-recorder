package com.dante.zeekrcapabilitylab.ui.product

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dante.zeekrcapabilitylab.diagnostic.RecorderSupportReport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A document write outlives the dialog's composition, including Activity recreation. */
internal class RecorderSupportSaveModel(
    private val writeDocument: suspend (Context, String, Uri) -> Unit = { context, snapshot, uri ->
        requireNotNull(context.contentResolver.openOutputStream(uri, "wt"))
            .use { RecorderSupportReport.write(snapshot, it) }
    },
) : ViewModel() {
    enum class Outcome { NONE, SAVED, FAILED }
    var saving by mutableStateOf(false)
        private set
    var outcome by mutableStateOf(Outcome.NONE)
        private set

    fun clearOutcome() { outcome = Outcome.NONE }

    fun save(context: Context, snapshot: String, uri: Uri) {
        if (saving) return
        saving = true
        outcome = Outcome.NONE
        val application = context.applicationContext
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { writeDocument(application, snapshot, uri) }
                outcome = Outcome.SAVED
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { outcome = Outcome.FAILED }
            finally { saving = false }
        }
    }
}
