package org.crisislink.ui

import android.graphics.Bitmap
import android.graphics.Color as AColor
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import org.crisislink.core.crypto.Identity
import org.crisislink.core.model.Contact
import org.crisislink.core.model.ContactCode
import org.crisislink.core.model.DeliveryStatus
import org.crisislink.core.model.Message
import org.crisislink.core.model.Priority
import org.crisislink.core.routing.RouterKind
import org.crisislink.mesh.MeshRuntime
import java.text.DateFormat
import java.util.Date

private enum class Tab(val label: String) { MESSAGES("Messages"), CONTACTS("Contacts"), STATUS("Status") }

@Composable
fun AppRoot(runtime: MeshRuntime) {
    var tab by remember { mutableStateOf(Tab.MESSAGES) }
    var composing by remember { mutableStateOf(false) }
    val state by runtime.state.collectAsState()

    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(selected = tab == t, onClick = { tab = t }, icon = {}, label = { Text(t.label) })
                }
            }
        },
        floatingActionButton = {
            if (tab == Tab.MESSAGES) FloatingActionButton(onClick = { composing = true }) { Text("+", style = MaterialTheme.typography.headlineSmall) }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                Tab.MESSAGES -> MessagesScreen(state.messages, runtime)
                Tab.CONTACTS -> ContactsScreen(runtime, state.contacts)
                Tab.STATUS -> StatusScreen(runtime, state)
            }
        }
    }
    if (composing) ComposeDialog(runtime, state.contacts) { composing = false }
}

// ------------------------------------------------------------------ messages

@Composable
private fun MessagesScreen(messages: List<Message>, runtime: MeshRuntime) {
    val mine = runtime.nodeId
    if (messages.isEmpty()) {
        Text("No messages yet. Messages you send, receive, or carry for others appear here.", Modifier.padding(24.dp))
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(messages, key = { it.id }) { m ->
            val carriedForOthers = m.text == null && m.sourceId != mine && m.destinationId != mine
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        when {
                            carriedForOthers -> "Carrying a sealed message for someone else"
                            m.text != null -> m.text!!
                            else -> "Message"
                        },
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(Modifier.height(4.dp))
                    val dir = when {
                        m.sourceId == mine -> "to ${if (m.isBroadcast) "everyone" else m.destinationId!!.take(8)}"
                        m.destinationId == mine -> "to you"
                        m.isBroadcast -> "broadcast"
                        else -> "in transit"
                    }
                    Text(
                        "${if (m.isUrgent) "URGENT · " else ""}$dir · ${m.status.name.lowercase()}" +
                            (if (m.hopCount > 0) " · ${m.hopCount} hop(s)" else "") +
                            " · ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(m.createdAtMs))}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (m.sourceId == mine || m.destinationId == mine) {
                        TextButton(onClick = { runtime.deleteMessage(m.id) }) { Text("Delete") }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ComposeDialog(runtime: MeshRuntime, contacts: List<Contact>, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var urgent by remember { mutableStateOf(false) }
    var target by remember { mutableStateOf<Contact?>(null) }
    var menu by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New message") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ExposedDropdownMenuBox(expanded = menu, onExpandedChange = { menu = it }) {
                    OutlinedTextField(
                        value = target?.name ?: "Everyone nearby (broadcast, not private)",
                        onValueChange = {}, readOnly = true, label = { Text("To") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(menu) },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                    )
                    ExposedDropdownMenuDefaults.let {
                        androidx.compose.material3.DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Everyone nearby (broadcast)") }, onClick = { target = null; menu = false })
                            contacts.forEach { c -> DropdownMenuItem(text = { Text(c.name) }, onClick = { target = c; menu = false }) }
                        }
                    }
                }
                if (contacts.isEmpty()) Text("Add contacts by scanning their QR code to send private messages.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = text, onValueChange = { text = it.take(500) }, label = { Text("Message") }, minLines = 3, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !urgent, onClick = { urgent = false }, label = { Text("Normal") })
                    FilterChip(selected = urgent, onClick = { urgent = true }, label = { Text("Urgent") })
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(enabled = text.isNotBlank(), onClick = {
                val p = if (urgent) Priority.URGENT else Priority.NORMAL
                val fail: (String) -> Unit = { error = it }
                target?.let { runtime.sendDirect(it, text.trim(), p, fail) } ?: runtime.sendBroadcast(text.trim(), p, fail)
                onDismiss()
            }) { Text("Send") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ------------------------------------------------------------------ contacts

@Composable
private fun ContactsScreen(runtime: MeshRuntime, contacts: List<Contact>) {
    val context = LocalContext.current
    var name by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    val code = remember(name) { ContactCode.encode(name.ifBlank { "Me" }, runtime.identity.keys) }
    val scan = rememberLauncherForActivityResult(ScanContract()) { result ->
        val contents = result.contents ?: return@rememberLauncherForActivityResult
        val c = ContactCode.decode(contents)
        message = if (c == null) "That is not a CrisisLink code." else if (c.nodeId == runtime.nodeId) "That is your own code." else {
            runtime.addContact(c); "Added ${c.name}. Compare fingerprints in person, then mark verified."
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("My contact code", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(value = name, onValueChange = { name = it.take(40) }, label = { Text("Your name") }, modifier = Modifier.fillMaxWidth())
                    Image(qrBitmap(code).asImageBitmap(), "Contact QR code", Modifier.size(220.dp))
                    Text("Fingerprint: ${Identity.fingerprint(runtime.identity.keys)}", style = MaterialTheme.typography.bodySmall)
                    Text("Show this to someone in person. They scan it to be able to message you privately.", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { scan.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setBeepEnabled(false).setPrompt("Scan a CrisisLink contact code")) }) {
                        Text("Scan a contact's code")
                    }
                    message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        items(contacts, key = { it.nodeId }) { c ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(c.name, style = MaterialTheme.typography.titleMedium)
                    Text("Fingerprint: ${Identity.fingerprint(c.keys)}", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = c.verified, onClick = { runtime.setVerified(c.nodeId, !c.verified) }, label = { Text(if (c.verified) "Verified" else "Mark verified") })
                        TextButton(onClick = { runtime.removeContact(c.nodeId) }) { Text("Remove") }
                    }
                }
            }
        }
    }
}

private fun qrBitmap(text: String, size: Int = 600): Bitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
    return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { bmp ->
        for (x in 0 until size) for (y in 0 until size) bmp.setPixel(x, y, if (matrix[x, y]) AColor.BLACK else AColor.WHITE)
    }
}

// -------------------------------------------------------------------- status

@Composable
private fun StatusScreen(runtime: MeshRuntime, state: org.crisislink.mesh.MeshState) {
    val activity = LocalContext.current as androidx.activity.ComponentActivity
    var wipeConfirm by remember { mutableStateOf(false) }
    var background by remember { mutableStateOf(runtime.backgroundEnabled) }
    var router by remember { mutableStateOf(runtime.routerKind) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("This phone", style = MaterialTheme.typography.titleMedium)
                    Text("Node ID: ${runtime.nodeId}")
                    Text("Mesh: ${state.status}")
                    Text("Power mode: ${state.power?.mode?.name?.lowercase()?.replace('_', ' ') ?: "-"}")
                    Text("Nearby: ${state.peers.size} · rejected packets: ${state.rejected}")
                    state.peers.forEach { Text("  ${it.nodeId?.take(8) ?: "connecting"} · ${it.rssi} dBm", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Routing", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RouterKind.entries.forEach { k ->
                            FilterChip(selected = router == k, onClick = { router = k; runtime.setRouter(k) }, label = {
                                Text(when (k) { RouterKind.DQN -> "Learned"; RouterKind.SPRAY_AND_WAIT -> "Spray"; RouterKind.PROPHET -> "PRoPHET" })
                            })
                        }
                    }
                    Text("Learned = the Deep Q-Learning policy. Spray-and-Wait delivered more in simulation; use it if unsure.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Background", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Text("Keep running when the app is closed")
                        Switch(checked = background, onCheckedChange = {
                            background = it; runtime.backgroundEnabled = it
                            if (it) org.crisislink.service.MeshForegroundService.start(activity)
                        })
                    }
                    OutlinedButton(onClick = { MainActivity.requestBatteryExemption(activity) }) { Text("Allow unrestricted battery use") }
                    Text("Some phone makers (Xiaomi, Huawei, Samsung) also need CrisisLink set to \"unrestricted\" or \"autostart\" in system settings.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            OutlinedButton(onClick = { wipeConfirm = true }, modifier = Modifier.fillMaxWidth()) { Text("Panic wipe: delete keys and all data") }
        }
    }
    if (wipeConfirm) AlertDialog(
        onDismissRequest = { wipeConfirm = false },
        title = { Text("Delete everything?") },
        text = { Text("This destroys your identity, contacts and messages. Others will no longer be able to reach you at this ID. It cannot be undone.") },
        confirmButton = {
            Button(onClick = { runtime.panicWipe { activity.finishAffinity(); Runtime.getRuntime().exit(0) } }) { Text("Delete") }
        },
        dismissButton = { TextButton(onClick = { wipeConfirm = false }) { Text("Cancel") } },
    )
}
