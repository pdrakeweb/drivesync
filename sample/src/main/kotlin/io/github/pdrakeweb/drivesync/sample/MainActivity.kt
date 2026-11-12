package io.github.pdrakeweb.drivesync.sample

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.util.Log
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.key
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import io.github.pdrakeweb.drivesync.core.SyncException
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import io.github.pdrakeweb.drivesync.android.JoinQr
import io.github.pdrakeweb.drivesync.core.Fix
import io.github.pdrakeweb.drivesync.core.SyncSetup
import io.github.pdrakeweb.drivesync.core.SyncState
import kotlinx.coroutines.launch
import java.util.UUID

class MainActivity : ComponentActivity() {
    private val app get() = application as SampleApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android 16 draws every app edge to edge; Scaffold below keeps content clear of the bars.
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                // Switching storage replaces the engine; rebuild the screen around the new one.
                val sync by app.sync.collectAsStateWithLifecycle()
                key(sync) { NotesScreen(app, onUseDrive = { connectDrive() }) }
            }
        }
        handleTestCommand(intent)
    }

    /** Receives the answer from Google's consent screen. */
    private val consent = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val granted = try {
            Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(result.data).accessToken != null
        } catch (e: ApiException) {
            false
        }
        if (granted) app.useBackend(Backend.GoogleDrive) else driveMessage.value = "Google Drive was not connected."
    }

    /**
     * Asks for permission to use the app's own folder in Google Drive. The first time, Google
     * shows a consent screen; after that the answer is remembered and nothing is shown.
     */
    private fun connectDrive() {
        lifecycleScope.launch {
            driveMessage.value = null
            try {
                val result = DriveAuth.authorize(this@MainActivity)
                val pending = result.pendingIntent
                if (result.hasResolution() && pending != null) {
                    consent.launch(IntentSenderRequest.Builder(pending.intentSender).build())
                } else {
                    app.useBackend(Backend.GoogleDrive)
                }
            } catch (e: ApiException) {
                driveMessage.value = "Could not reach Google (${e.statusCode}). Check the connection and that a Google account is signed in on this device."
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleTestCommand(intent)
    }

    /**
     * Debug builds only: lets the two-emulator journey script drive the app through intent extras
     * (`passphrase`, `add`, `rename` + `to`, `delete`, `sync`) instead of screen coordinates.
     */
    private fun handleTestCommand(intent: Intent) {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        val notes = app.database.notes()
        lifecycleScope.launch {
            // `backend drive` (with an optional `root` so each test run starts from an empty folder).
            when (intent.getStringExtra("backend")) {
                "drive" -> {
                    intent.getStringExtra("root")?.let { app.useBackend(Backend.LocalFolder, driveRoot = it) }
                    connectDrive()
                }
                "local" -> app.useBackend(Backend.LocalFolder)
            }
            intent.getStringExtra("passphrase")?.let { turnOn(app, it) }
            if (intent.getBooleanExtra("dump", false)) {
                // Names of everything in storage, for the journey's file-name check.
                val names = ArrayList<String>()
                var page: String? = null
                do {
                    val listed = app.sync.value.blobs.list("", page)
                    names += listed.items.map { it.path }
                    page = listed.nextPageToken
                } while (page != null)
                Log.i("drivesync-sample", "STORED ${names.size} " + names.sorted().joinToString(" "))
            }
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

/** The outcome of the last attempt to turn sync on, shown under the passphrase form. */
private val lastSetupResult = mutableStateOf<SyncSetup.Result?>(null)
private val setupError = mutableStateOf<String?>(null)
private val driveMessage = mutableStateOf<String?>(null)

/** Turns sync on and records the outcome for the screen; storage failures are shown, never thrown. */
private suspend fun turnOn(app: SampleApp, passphrase: String) {
    setupError.value = null
    try {
        lastSetupResult.value = app.turnOn(passphrase)
    } catch (e: SyncException) {
        lastSetupResult.value = null
        setupError.value = "Could not reach storage: ${e.error}"
    }
}

private fun describe(state: SyncState): String = when (state) {
    is SyncState.Idle -> if (state.lastSync == null) "Not synced yet" else "Synced"
    is SyncState.Syncing -> "Syncing"
    SyncState.Offline -> "Offline"
    SyncState.NeedsKey -> "Sync is off on this device"
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
private fun NotesScreen(app: SampleApp, onUseDrive: () -> Unit) {
    val backend = app.sync.collectAsStateWithLifecycle().value.backend
    val notes by app.database.notes().observe().collectAsStateWithLifecycle(emptyList())
    val state by app.engine.state.collectAsStateWithLifecycle()
    val pending by app.engine.pending.collectAsStateWithLifecycle()
    val hasKey by app.hasKey.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<Note?>(null) }
    var showJoinCode by remember { mutableStateOf(false) }
    val dao = app.database.notes()
    LaunchedEffect(Unit) { app.refreshKeyState() }

    Scaffold { insets ->
        Column(Modifier.padding(insets).padding(horizontal = 16.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("drivesync sample", style = MaterialTheme.typography.headlineSmall)
            if (hasKey) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(describe(state))
                        Text(if (pending == 0) "Nothing waiting to upload" else "$pending changes waiting to upload", style = MaterialTheme.typography.bodySmall)
                        Text("End-to-end encrypted", style = MaterialTheme.typography.bodySmall)
                    }
                    Button(onClick = { scope.launch { app.engine.sync() } }) { Text("Sync now") }
                }
                TextButton(onClick = { showJoinCode = true }) { Text("Add another device") }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (backend == Backend.GoogleDrive) "Storage: Google Drive" else "Storage: a folder on this device",
                        Modifier.weight(1f),
                    )
                    if (backend == Backend.LocalFolder) TextButton(onClick = onUseDrive) { Text("Use Google Drive") }
                }
                driveMessage.value?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                TurnOnSync(app)
                if (pending > 0) Text("$pending changes will upload once sync is on", style = MaterialTheme.typography.bodySmall)
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

    if (showJoinCode) {
        // The code is the key itself: it is drawn only while this dialog is open and never stored.
        val code = remember { app.keys.exportJoinCode() }
        val qr = remember(code) { JoinQr.bitmap(code, 720).asImageBitmap() }
        AlertDialog(
            onDismissRequest = { showJoinCode = false },
            title = { Text("Add another device") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("On the other device, enter the same passphrase, or scan this code. Anyone who sees the code can read your synced notes.")
                    Image(qr, contentDescription = "Join code as a QR code", modifier = Modifier.size(240.dp))
                }
            },
            confirmButton = { TextButton(onClick = { showJoinCode = false }) { Text("Done") } },
        )
    }
}

@Composable
private fun TurnOnSync(app: SampleApp) {
    val scope = rememberCoroutineScope()
    var passphrase by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    val result by lastSetupResult
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Sync is off on this device")
        Text(
            "Choose a passphrase to turn it on, or enter the one you chose on another device. " +
                "It encrypts your notes; if you lose it, the synced copy cannot be read.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                passphrase, { passphrase = it }, Modifier.weight(1f),
                label = { Text("Passphrase") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            Button(
                enabled = passphrase.length >= 8 && !working,
                onClick = {
                    val entered = passphrase
                    passphrase = ""
                    working = true
                    scope.launch {
                        turnOn(app, entered)
                        working = false
                    }
                },
            ) { Text(if (working) "Working" else "Turn on") }
        }
        when (result) {
            SyncSetup.Result.WrongPassphrase ->
                Text("That passphrase does not match the one used on your other device.", color = MaterialTheme.colorScheme.error)
            SyncSetup.Result.NotSetUp -> Text("Nothing to join yet.", color = MaterialTheme.colorScheme.error)
            null -> setupError.value?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            else -> if (passphrase.isNotEmpty() && passphrase.length < 8) Text("Use at least 8 characters.", style = MaterialTheme.typography.bodySmall)
        }
    }
}
