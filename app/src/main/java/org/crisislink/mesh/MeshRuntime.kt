package org.crisislink.mesh

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.room.Room
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.crisislink.R
import org.crisislink.ble.BleTransport
import org.crisislink.ble.PowerController
import org.crisislink.ble.PowerProfile
import org.crisislink.ble.TransportListener
import org.crisislink.core.crypto.Identity
import org.crisislink.core.mesh.Link
import org.crisislink.core.mesh.MeshEngine
import org.crisislink.core.mesh.MeshListener
import org.crisislink.core.mesh.PeerInfo
import org.crisislink.core.model.Contact
import org.crisislink.core.model.DeliveryStatus
import org.crisislink.core.model.Message
import org.crisislink.core.model.Priority
import org.crisislink.core.routing.DqnPolicy
import org.crisislink.core.routing.DqnRouter
import org.crisislink.core.routing.ProphetRouter
import org.crisislink.core.routing.Router
import org.crisislink.core.routing.RouterKind
import org.crisislink.core.routing.SprayAndWaitRouter
import org.crisislink.core.storage.BufferPolicy
import org.crisislink.identity.KeystoreIdentityStore
import org.crisislink.storage.CrisisLinkDb
import org.crisislink.storage.RoomMeshStore
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

data class MeshState(
    val messages: List<Message> = emptyList(),
    val contacts: List<Contact> = emptyList(),
    val peers: List<PeerInfo> = emptyList(),
    val running: Boolean = false,
    val status: String = "Stopped",
    val power: PowerProfile? = null,
    val rejected: Int = 0,
)

/**
 * Owns the identity, database, mesh engine and BLE transport for the whole process. The foreground
 * service starts and stops it; the UI observes [state] and sends through it. Everything touching the
 * engine runs on one worker thread (BLE callbacks arrive on binder threads and the engine is synchronised).
 */
class MeshRuntime(private val context: Context) {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences("crisislink", Context.MODE_PRIVATE)
    private val identityStore = KeystoreIdentityStore(context)
    private val db = Room.databaseBuilder(context, CrisisLinkDb::class.java, "crisislink.db").build()
    private val store = RoomMeshStore(db.dao())

    val identity: Identity = identityStore.loadOrCreate()
    private val _state = MutableStateFlow(MeshState())
    val state: StateFlow<MeshState> = _state.asStateFlow()

    private val power: PowerController = PowerController(context) { profile ->
        transport.applyProfile(profile)
        _state.value = _state.value.copy(power = profile)
    }

    private val engine: MeshEngine = MeshEngine(
        identity, store, loadRouter(RouterKind.valueOf(prefs.getString("router", RouterKind.DQN.name)!!)),
        battery = { power.batteryFraction },
        listener = object : MeshListener {
            override fun onMessageReceived(message: Message) { notifyReceived(message); refresh() }
            override fun onMessageDelivered(messageId: String) = refresh()
            override fun onPeersChanged(peers: List<PeerInfo>) {
                power.hasPeers = peers.isNotEmpty()
                _state.value = _state.value.copy(peers = peers)
                refresh()
            }
            override fun onRejected(linkId: String, reason: String) {
                _state.value = _state.value.copy(rejected = _state.value.rejected + 1)
            }
        },
    )

    private val transport: BleTransport = BleTransport(context, identity.nodeId, object : TransportListener {
        override fun onLinkUp(link: Link) = worker.execute { engine.onLinkUp(link) }
        override fun onLinkDown(linkId: String) = worker.execute { engine.onLinkDown(linkId) }
        override fun onPacket(linkId: String, packet: ByteArray) = worker.execute { engine.onPacket(linkId, packet) }
        override fun onStatus(status: String) { _state.value = _state.value.copy(status = status) }
    })

    private val maintenance = object : Runnable {
        override fun run() { worker.execute { engine.purge(); refresh() }; main.postDelayed(this, 60_000) }
    }

    val nodeId: String get() = identity.nodeId
    val routerKind: RouterKind get() = engine.router.kind
    val usingLearnedPolicy: Boolean get() = engine.router.kind == RouterKind.DQN

    init { refresh() }

    // ---------------------------------------------------------------- control

    @Synchronized
    fun start() {
        if (_state.value.running) return
        createChannels()
        power.start()
        transport.start(power.current)
        main.postDelayed(maintenance, 60_000)
        _state.value = _state.value.copy(running = true, power = power.current)
    }

    @Synchronized
    fun stop() {
        if (!_state.value.running) return
        main.removeCallbacks(maintenance)
        transport.stop()
        power.stop()
        _state.value = _state.value.copy(running = false, peers = emptyList(), status = "Stopped")
    }

    var backgroundEnabled: Boolean
        get() = prefs.getBoolean("background", true)
        set(value) = prefs.edit().putBoolean("background", value).apply()

    fun setRouter(kind: RouterKind) {
        prefs.edit().putString("router", kind.name).apply()
        worker.execute { engine.router = loadRouter(kind); engine.reroute(); refresh() }
    }

    // --------------------------------------------------------------- messages

    fun sendDirect(contact: Contact, text: String, priority: Priority, onError: (String) -> Unit) = worker.execute {
        runCatching { engine.sendDirect(contact, text, priority) }.onFailure { onError(it.message ?: "Could not send") }
        refresh()
    }

    fun sendBroadcast(text: String, priority: Priority, onError: (String) -> Unit) = worker.execute {
        runCatching { engine.sendBroadcast(text, priority) }.onFailure { onError(it.message ?: "Could not send") }
        refresh()
    }

    fun deleteMessage(id: String) = worker.execute { store.delete(id); refresh() }

    fun addContact(contact: Contact) = worker.execute { store.putContact(contact); refresh() }
    fun setVerified(nodeId: String, verified: Boolean) = worker.execute {
        store.contact(nodeId)?.let { store.putContact(it.copy(verified = verified)) }; refresh()
    }
    fun removeContact(nodeId: String) = worker.execute { store.deleteContact(nodeId); refresh() }

    /** Deletes keys, messages and contacts. The next launch starts a fresh identity. */
    fun panicWipe(afterWipe: () -> Unit) = worker.execute {
        stop()
        db.clearAllTables()
        identityStore.wipe()
        prefs.edit().clear().apply()
        main.post(afterWipe)
    }

    // ---------------------------------------------------------------- internals

    private fun refresh() {
        worker.execute {
            val messages = store.messages().sortedWith(compareByDescending<Message> { it.createdAtMs })
            _state.value = _state.value.copy(messages = messages, contacts = store.contacts())
            updateServiceNotification(messages)
        }
    }

    private fun loadRouter(kind: RouterKind): Router = when (kind) {
        RouterKind.SPRAY_AND_WAIT -> SprayAndWaitRouter()
        RouterKind.PROPHET -> ProphetRouter()
        RouterKind.DQN -> runCatching {
            DqnRouter(DqnPolicy.fromJson(context.assets.open(DqnPolicy.ASSET_PATH).bufferedReader().use { it.readText() }))
        }.getOrElse { SprayAndWaitRouter() }   // policy asset missing or mismatched: fall back to a simple router
    }

    // ------------------------------------------------------------ notifications

    private val notificationId = AtomicInteger(100)

    private fun createChannels() {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(MESH_CHANNEL, context.getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(MESSAGE_CHANNEL, context.getString(R.string.message_channel), NotificationManager.IMPORTANCE_HIGH))
    }

    @SuppressLint("MissingPermission")
    private fun notifyReceived(m: Message) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            android.os.Build.VERSION.SDK_INT >= 33
        ) return
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val pending = android.app.PendingIntent.getActivity(context, 0, intent, android.app.PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(context, MESSAGE_CHANNEL)
            .setSmallIcon(R.drawable.ic_mesh)
            .setContentTitle(if (m.isBroadcast) "Broadcast" else "Message for you")
            .setContentText(m.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(m.text))
            .setContentIntent(pending).setAutoCancel(true).build()
        androidx.core.app.NotificationManagerCompat.from(context).notify(notificationId.incrementAndGet(), n)
    }

    private var serviceNotifier: ((peers: Int, carried: Int) -> Unit)? = null
    fun setServiceNotifier(f: ((peers: Int, carried: Int) -> Unit)?) { serviceNotifier = f }

    private fun updateServiceNotification(messages: List<Message>) {
        val carried = messages.count { it.status != DeliveryStatus.DELIVERED }.coerceAtMost(BufferPolicy.CAPACITY)
        serviceNotifier?.invoke(_state.value.peers.size, carried)
    }

    companion object {
        const val MESH_CHANNEL = "mesh"
        const val MESSAGE_CHANNEL = "messages"
    }
}
