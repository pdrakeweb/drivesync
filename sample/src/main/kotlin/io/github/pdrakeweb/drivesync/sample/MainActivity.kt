package io.github.pdrakeweb.drivesync.sample

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import io.github.pdrakeweb.drivesync.core.Fix
import io.github.pdrakeweb.drivesync.core.SyncState
import kotlinx.coroutines.launch
import java.util.UUID

class MainActivity : ComponentActivity() {
    private val app get() = application as SampleApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android 16 draws every app edge to edge; Scaffold below keeps content clear of the bars.
        enableEdgeToEdge()
        setContent { MaterialTheme { NotesScreen(app) } }
        handleTestCommand(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleTestCommand(intent)
    }

    /**
     * Debug builds only: lets the two-emulator journey script drive the app through intent extras
     * (`add`, `rename` + `to`, `delete`, `sync`) instead of screen coordinates.
     */
    private fun handleTestCommand(intent: Intent) {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        val notes = app.database.notes()
        lifecycleScope.launch {
            intent.getStringExtra("add")?.let { notes.insert(Note(UUID.randomUUID().toString(), it, null)) }
            intent.getStringExtra("rename")?.let { from ->
                notes.byTitle(from)?.let { notes.rename(it.id, intent.getStringExtra("to") ?: from) }
            }
            intent.getStringExtra("delete")?.let { title -> notes.byTitle(title)?.let { notes.delete(it.id) } }
            if (intent.extras?.keySet()?.any { it in setOf("add", "rename", "delete") } == true) app.engine.refreshPending()
            if (intent.getBooleanExtra("sync", false)) app.engine.sync()
        }
    }
}

private fun describe(state: SyncState): String = when (state) {
    is SyncState.Idle -> if (state.lastSync == null) "Not synced yet" else "Synced"
    is SyncState.Syncing -> "Syncing"
    SyncState.Offline -> "Offline"
    SyncState.NeedsKey -> "Needs passphrase"
    is SyncState.Error -> "Sync problem: ${state.kind}" + when (state.fix) {
        Fix.RetryLater -> " (will retry)"
        Fix.UpdateApp -> " (update the app)"
        Fix.SignIn -> " (sign in again)"
        Fix.FreeUpSpace -> " (storage is full)"
        Fix.EnterPassphrase -> " (enter the passphrase)"
        Fix.ResetThisDevice -> " (this device must be set up again)"
        null -> ""
    }
}

@Composable
private fun NotesScreen(app: SampleApp) {
    val notes by app.database.notes().observe().collectAsStateWithLifecycle(emptyList())
    val state by app.engine.state.collectAsStateWithLifecycle()
    val pending by app.engine.pending.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<Note?>(null) }
    val dao = app.database.notes()

    Scaffold { insets ->
        Column(Modifier.padding(insets).padding(horizontal = 16.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("drivesync sample", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Milestone S2 build: the sync folder is NOT encrypted yet.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(describe(state))
                    Text(if (pending == 0) "Nothing waiting to upload" else "$pending changes waiting to upload", style = MaterialTheme.typography.bodySmall)
                }
                Button(onClick = { scope.launch { app.engine.sync() } }) { Text("Sync now") }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(draft, { draft = it }, Modifier.weight(1f), label = { Text("New note") }, singleLine = true)
                Button(
                    enabled = draft.isNotBlank(),
                    onClick = {
                        val title = draft.trim()
                        draft = ""
                        scope.launch {
                            dao.insert(Note(UUID.randomUUID().toString(), title, null))
                            app.changed()
                        }
                    },
                ) { Text("Add") }
            }
            HorizontalDivider()
            if (notes.isEmpty()) Text("No notes yet. Add one above.")
            LazyColumn(Modifier.fillMaxWidth()) {
                items(notes, key = { it.id }) { note ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(note.title, Modifier.weight(1f))
                        TextButton(onClick = { editing = note }) { Text("Rename") }
                        TextButton(onClick = { scope.launch { dao.delete(note.id); app.changed() } }) { Text("Delete") }
                    }
                }
            }
        }
    }

    editing?.let { note ->
        var title by remember(note.id) { mutableStateOf(note.title) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Rename note") },
            text = { OutlinedTextField(title, { title = it }, singleLine = true) },
            confirmButton = {
                TextButton(
                    enabled = title.isNotBlank(),
                    onClick = {
                        editing = null
                        scope.launch { dao.rename(note.id, title.trim()); app.changed() }
                    },
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }
}
