package com.abhiram.photoq

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

class MainActivity : ComponentActivity() {
    private val app get() = application as PhotoQApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { PhotoQRoot() } }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun PhotoQRoot() {
        val prefs = remember { getSharedPreferences("photoq", Context.MODE_PRIVATE) }
        val keyProvider = remember { CmdKeyProvider(this) }
        var tab by remember { mutableIntStateOf(0) }
        var info by remember { mutableStateOf<String?>(null) }
        var monitoring by remember { mutableStateOf(prefs.getBoolean("monitoring", false)) }
        var paused by remember { mutableStateOf(prefs.getBoolean("paused", false)) }

        val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                try {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    keyProvider.setCameraTree(uri)
                    info = try { keyProvider.readKey(); "Camera folder selected and cmd.txt is readable." } catch (t: Throwable) { "Folder selected, but ${t.message}" }
                } catch (t: Throwable) { info = "Folder permission failed: ${t.message}" }
            }
        }
        var startAfterPermission by remember { mutableStateOf(false) }
        val mediaPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted && startAfterPermission) {
                startAfterPermission = false
                startMonitor(); monitoring = true; paused = false
            } else if (!granted) info = "Photo permission is required to watch Camera images."
        }
        val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

        fun selectCameraFolder() {
            val initial = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:DCIM/Camera")
            folderPicker.launch(initial)
        }
        fun requestStart() {
            try { keyProvider.readKey() } catch (t: Throwable) { info = "Select DCIM/Camera and ensure cmd.txt exists first: ${t.message}"; return }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {
                startAfterPermission = true; mediaPermission.launch(Manifest.permission.READ_MEDIA_IMAGES); return
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            startMonitor(); monitoring = true; paused = false
        }

        Scaffold(
            topBar = { TopAppBar(title = { Text("PhotoQ") }) },
            bottomBar = {
                NavigationBar {
                    listOf("Answers", "Queue", "Group", "Settings").forEachIndexed { i, label ->
                        NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Text((i + 1).toString()) }, label = { Text(label) })
                    }
                }
            }
        ) { pad ->
            Column(Modifier.padding(pad).fillMaxSize()) {
                info?.let { AssistChip(onClick = { info = null }, label = { Text(it) }, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) }
                when (tab) {
                    0 -> AnswersScreen(monitoring, paused,
                        onStart = ::requestStart,
                        onStop = { stopMonitor(); monitoring = false; paused = false },
                        onPause = {
                            paused = !paused; prefs.edit().putBoolean("paused", paused).apply()
                            if (!paused && monitoring) app.processor.scanSince(prefs.getLong("session_start", System.currentTimeMillis()))
                        },
                        onScan = { app.processor.scanSince(it); info = "Scan queued." },
                        onInfo = { info = it })
                    1 -> QueueScreen(onRetry = app.processor::retry)
                    2 -> GroupScreen(onInfo = { info = it })
                    3 -> SettingsScreen(keyProvider, onSelect = ::selectCameraFolder, onInfo = { info = it })
                }
            }
        }
    }

    private fun startMonitor() = ContextCompat.startForegroundService(this, Intent(this, MonitorService::class.java).setAction(MonitorService.ACTION_START))
    private fun stopMonitor() = startService(Intent(this, MonitorService::class.java).setAction(MonitorService.ACTION_STOP))

    @Composable
    private fun AnswersScreen(
        monitoring: Boolean, paused: Boolean,
        onStart: () -> Unit, onStop: () -> Unit, onPause: () -> Unit,
        onScan: (Long) -> Unit, onInfo: (String) -> Unit
    ) {
        val change by app.store.changes.collectAsState()
        val results = remember(change) { app.store.results() }
        var exactLabel by remember { mutableStateOf("Exact time") }
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onStart, enabled = !monitoring) { Text("START") }
                OutlinedButton(onClick = onStop, enabled = monitoring) { Text("STOP") }
                if (monitoring) OutlinedButton(onClick = onPause) { Text(if (paused) "RESUME" else "PAUSE AUTO") }
            }
            Text(if (monitoring) if (paused) "Monitoring paused" else "Monitoring active" else "Monitoring stopped", Modifier.padding(horizontal = 12.dp), fontWeight = FontWeight.SemiBold)
            Text("Scan Since Time", Modifier.padding(start = 12.dp, top = 10.dp), fontWeight = FontWeight.Bold)
            Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(5,10,30,60).forEach { m -> TextButton(onClick = { onScan(System.currentTimeMillis() - m * 60_000L) }) { Text("${m}m") } }
                TextButton(onClick = {
                    pickExactTime { ts -> exactLabel = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(ts)); onScan(ts) }
                }) { Text(exactLabel) }
            }
            HorizontalDivider()
            Text("Answers", Modifier.padding(12.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (results.isEmpty()) Text("No results yet.", Modifier.padding(16.dp))
            LazyColumn(Modifier.fillMaxSize()) { items(results, key = { it.id }) { ResultCard(it, onInfo) } }
        }
    }

    @Composable
    private fun ResultCard(r: ResultRow, onInfo: (String) -> Unit) {
        var details by remember { mutableStateOf(false) }
        Card(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp)) {
            Column(Modifier.padding(12.dp)) {
                Text(r.questionNumber?.let { "Q$it" } ?: "Question number unknown", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                val answer = if (r.answers.isNotEmpty()) r.answers.joinToString(", ") { optionLabel(it) } else r.answerText ?: "Could not normalize answer"
                Text("Answer: $answer", style = MaterialTheme.typography.titleMedium)
                r.answerText?.takeIf { r.answers.isNotEmpty() }?.let { Text(it) }
                Text("${r.imageUris.size} image(s) • ${DateFormat.getDateTimeInstance().format(Date(r.completedAt))} • ${r.status}", style = MaterialTheme.typography.bodySmall)
                r.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = { details = !details }) { Text(if (details) "Hide details" else "Details") }
                if (details) {
                    Text("Raw Gemini response:", fontWeight = FontWeight.SemiBold)
                    Text(r.raw.ifBlank { "(no raw response)" }, style = MaterialTheme.typography.bodySmall)
                    Row { TextButton(onClick = { app.store.deleteResult(r.id); onInfo("Result deleted") }) { Text("Delete") } }
                }
            }
        }
    }

    @Composable
    private fun QueueScreen(onRetry: (Long) -> Unit) {
        val change by app.store.changes.collectAsState()
        val queue = remember(change) { app.store.queue() }
        Column(Modifier.fillMaxSize()) {
            Text("Processing Queue", Modifier.padding(12.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            LazyColumn { items(queue, key = { it.mediaId }) { q ->
                ListItem(
                    headlineContent = { Text("Image ${q.mediaId}") },
                    supportingContent = { Text("${q.state}${q.bundleId?.let { " • $it" } ?: ""}") },
                    trailingContent = { if (q.state == "FAILED") TextButton(onClick = { onRetry(q.mediaId) }) { Text("Retry") } }
                ); HorizontalDivider()
            } }
        }
    }

    @Composable
    private fun GroupScreen(onInfo: (String) -> Unit) {
        var photos by remember { mutableStateOf<List<CameraPhoto>>(emptyList()) }
        val selected = remember { mutableStateListOf<Long>() }
        LaunchedEffect(Unit) { photos = withContext(Dispatchers.IO) { app.processor.queryCameraPhotos(System.currentTimeMillis() - 24 * 60 * 60 * 1000L, 100).reversed() } }
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Select images for ONE question", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                TextButton(onClick = { selected.clear() }) { Text("Clear") }
                TextButton(onClick = { selected.clear(); selected.addAll(photos.map { it.id }) }) { Text("All") }
            }
            Text("${selected.size} selected. Selected images are sent together in chronological order.", Modifier.padding(horizontal = 12.dp))
            Button(
                onClick = {
                    val chosen = photos.filter { it.id in selected }
                    if (chosen.isEmpty()) onInfo("Select at least one image.")
                    else if (app.processor.enqueueManual(chosen)) { selected.clear(); onInfo("${chosen.size} image(s) grouped and queued as ONE question.") }
                    else onInfo("One or more selected images are already queued/processed. Choose unclaimed images or use Retry.")
                },
                enabled = selected.isNotEmpty(),
                modifier = Modifier.padding(12.dp).fillMaxWidth()
            ) { Text("GROUP AND SEND") }
            LazyColumn { items(photos, key = { it.id }) { p ->
                Row(Modifier.fillMaxWidth().clickable { if (p.id in selected) selected.remove(p.id) else selected.add(p.id) }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = p.id in selected, onCheckedChange = { if (it) selected.add(p.id) else selected.remove(p.id) })
                    Thumbnail(p.uri)
                    Spacer(Modifier.width(8.dp))
                    Column { Text("Image ${p.id}"); Text(DateFormat.getDateTimeInstance().format(Date(p.capturedAt)), style = MaterialTheme.typography.bodySmall) }
                }
                HorizontalDivider()
            } }
        }
    }

    @Composable
    private fun Thumbnail(uri: String) {
        val image by produceState<ImageBitmap?>(null, uri) {
            value = withContext(Dispatchers.IO) {
                try { contentResolver.loadThumbnail(Uri.parse(uri), Size(160, 160), null).asImageBitmap() } catch (_: Throwable) { null }
            }
        }
        Box(Modifier.size(72.dp)) {
            if (image != null) Image(image!!, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Surface(Modifier.fillMaxSize(), tonalElevation = 2.dp) { Box(contentAlignment = Alignment.Center) { Text("IMG") } }
        }
    }

    @Composable
    private fun SettingsScreen(keyProvider: CmdKeyProvider, onSelect: () -> Unit, onInfo: (String) -> Unit) {
        val tree = keyProvider.cameraTree()
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Settings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("API key source: cmd.txt inside your selected DCIM/Camera folder. The key is read locally when a request is sent; it is never committed or stored in the PhotoQ database.")
            Text(if (tree == null) "Camera folder: not selected" else "Camera folder access configured")
            Button(onClick = onSelect) { Text("SELECT DCIM/CAMERA") }
            OutlinedButton(onClick = {
                onInfo(try { keyProvider.readKey(); "cmd.txt found and contains a key." } catch (t: Throwable) { t.message ?: "Unable to read cmd.txt" })
            }) { Text("CHECK cmd.txt") }
            Text("Provider: Command Code\nModel: google/gemini-3.7-flash\nConcurrency: 4\nNo ads or analytics.")
        }
    }

    private fun optionLabel(n: Int): String = if (n in 1..26) ('A'.code + n - 1).toChar().toString() else n.toString()

    private fun pickExactTime(onPicked: (Long) -> Unit) {
        val cal = Calendar.getInstance()
        DatePickerDialog(this, { _, y, m, d ->
            TimePickerDialog(this, { _, h, min ->
                val c = Calendar.getInstance().apply { set(y, m, d, h, min, 0); set(Calendar.MILLISECOND, 0) }
                onPicked(c.timeInMillis)
            }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), false).show()
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
    }
}
