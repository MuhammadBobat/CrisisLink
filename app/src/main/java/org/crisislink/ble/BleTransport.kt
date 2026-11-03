package org.crisislink.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import org.crisislink.core.crypto.hexToBytes
import org.crisislink.core.crypto.toHex
import org.crisislink.core.mesh.Link
import org.crisislink.core.protocol.Fragmenter
import org.crisislink.core.protocol.Reassembler
import java.util.UUID

interface TransportListener {
    fun onLinkUp(link: Link)
    fun onLinkDown(linkId: String)
    fun onPacket(linkId: String, packet: ByteArray)
    fun onStatus(status: String) {}
}

/**
 * Every phone is a GATT server (advertises, accepts writes, sends notifications) and a GATT client
 * (scans, connects) at once. Both sides see each other, so only the phone with the lower node ID
 * dials; the other waits to be dialled. That leaves exactly one link per pair.
 *
 * Packets are fragmented to the link's ATT payload and sent one frame at a time per link.
 */
@SuppressLint("MissingPermission")
class BleTransport(
    private val context: Context,
    private val nodeId: String,
    private val listener: TransportListener,
) {
    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("9fa480e0-4967-4542-9390-d343dc5d04ae")
        val CHAR_UUID: UUID = UUID.fromString("af0badb1-5b99-43cd-917a-a77bc549e3cc")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val TAG = "BleTransport"
        private const val REQUEST_MTU = 517
        private const val MIN_SCAN_RESTART_MS = 5_000L
        private const val CONNECT_TIMEOUT_MS = 15_000L
    }

    private val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? = manager.adapter
    private val main = Handler(Looper.getMainLooper())
    private val nodeIdBytes = nodeId.hexToBytes()

    private var scanner: BluetoothLeScanner? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var server: BluetoothGattServer? = null
    private var characteristic: BluetoothGattCharacteristic? = null
    private var profile: PowerProfile = PowerProfiles.of(PowerMode.BALANCED)
    private var running = false
    private var scanning = false
    private var lastScanStart = 0L

    private val links = HashMap<String, GattLink>()               // by link id
    private val pendingConnect = HashMap<String, Long>()           // address -> started at
    private val backoff = HashMap<String, Pair<Long, Int>>()       // address -> (not before, failures)
    private val reassemblers = HashMap<String, Reassembler>()
    private val serverDevices = HashMap<String, BluetoothDevice>()
    private val serverMtu = HashMap<String, Int>()

    val linkCount: Int @Synchronized get() = links.size

    // ------------------------------------------------------------- lifecycle

    @Synchronized
    fun start(initial: PowerProfile) {
        if (running) return
        val a = adapter
        if (a == null || !a.isEnabled) { listener.onStatus("Bluetooth is off"); return }
        running = true
        profile = initial
        startServer()
        startAdvertising()
        startScanCycle()
        listener.onStatus("Mesh running")
    }

    @Synchronized
    fun stop() {
        running = false
        main.removeCallbacksAndMessages(null)
        stopScan()
        runCatching { advertiser?.stopAdvertising(advertiseCallback) }
        links.values.toList().forEach { it.close() }
        links.clear()
        pendingConnect.clear()
        runCatching { server?.close() }
        server = null
        listener.onStatus("Stopped")
    }

    @Synchronized
    fun applyProfile(p: PowerProfile) {
        val advertisingChanged = p.advertiseMode != profile.advertiseMode || p.txPower != profile.txPower
        profile = p
        if (!running) return
        if (advertisingChanged) {
            runCatching { advertiser?.stopAdvertising(advertiseCallback) }
            main.postDelayed({ startAdvertising() }, 300)
        }
        main.removeCallbacks(scanOn); main.removeCallbacks(scanOff)
        stopScan()
        startScanCycle()
    }

    // ----------------------------------------------------------- GATT server

    private fun startServer() {
        server = manager.openGattServer(context, serverCallback) ?: return
        val ch = BluetoothGattCharacteristic(
            CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        ch.addDescriptor(BluetoothGattDescriptor(CCCD_UUID, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
        characteristic = ch
        server?.addService(BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply { addCharacteristic(ch) })
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            val id = "s:" + device.address
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                synchronized(this@BleTransport) { serverDevices[id] = device }
            } else {
                synchronized(this@BleTransport) { serverDevices.remove(id); serverMtu.remove(id) }
                dropLink(id)
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            synchronized(this@BleTransport) { serverMtu["s:" + device.address] = mtu; links["s:" + device.address]?.frameSize = mtu - 3 }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor, preparedWrite: Boolean,
            responseNeeded: Boolean, offset: Int, value: ByteArray,
        ) {
            if (responseNeeded) server?.sendResponse(device, requestId, android.bluetooth.BluetoothGatt.GATT_SUCCESS, 0, null)
            if (descriptor.uuid != CCCD_UUID || !value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) return
            val id = "s:" + device.address
            val link = ServerLink(id, device, serverMtu[id]?.minus(3) ?: 20)
            registerLink(link)
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, c: BluetoothGattCharacteristic, preparedWrite: Boolean,
            responseNeeded: Boolean, offset: Int, value: ByteArray,
        ) {
            if (responseNeeded) server?.sendResponse(device, requestId, android.bluetooth.BluetoothGatt.GATT_SUCCESS, 0, null)
            if (c.uuid == CHAR_UUID) deliver("s:" + device.address, value)
        }

        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, c: BluetoothGattCharacteristic) {
            server?.sendResponse(device, requestId, android.bluetooth.BluetoothGatt.GATT_SUCCESS, 0, ByteArray(0))
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            synchronized(this@BleTransport) { links["s:" + device.address] }?.onSent(status == android.bluetooth.BluetoothGatt.GATT_SUCCESS)
        }
    }

    private inner class ServerLink(id: String, val device: BluetoothDevice, frame: Int) : GattLink(id, frame) {
        override fun transmit(frame: ByteArray): Boolean {
            val ch = characteristic ?: return false
            val s = server ?: return false
            return if (Build.VERSION.SDK_INT >= 33) {
                s.notifyCharacteristicChanged(device, ch, false, frame) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION") run { ch.value = frame; s.notifyCharacteristicChanged(device, ch, false) }
            }
        }
        override fun close() { runCatching { server?.cancelConnection(device) } }
    }

    // ----------------------------------------------------------- advertising

    private fun startAdvertising() {
        advertiser = adapter?.bluetoothLeAdvertiser
        val adv = advertiser ?: return listener.onStatus("Advertising not supported")
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(profile.advertiseMode).setTxPowerLevel(profile.txPower)
            .setConnectable(true).setTimeout(0).build()
        val data = AdvertiseData.Builder().setIncludeDeviceName(false).setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(SERVICE_UUID)).build()
        // Node id in the scan response lets scanners pick who dials despite rotating MAC addresses.
        val response = AdvertiseData.Builder().setIncludeDeviceName(false)
            .addServiceData(ParcelUuid(SERVICE_UUID), nodeIdBytes).build()
        adv.startAdvertising(settings, data, response, advertiseCallback)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) {
            Log.w(TAG, "advertise failed: $errorCode")
            if (running && errorCode != ADVERTISE_FAILED_ALREADY_STARTED && errorCode != ADVERTISE_FAILED_FEATURE_UNSUPPORTED &&
                errorCode != ADVERTISE_FAILED_DATA_TOO_LARGE
            ) main.postDelayed({ if (running) startAdvertising() }, 3_000)
        }
    }

    // --------------------------------------------------------------- scanning

    private val scanOn = Runnable { beginScan() }
    private val scanOff = Runnable { stopScan(); if (running) main.postDelayed(scanOn, profile.scanOffMs) }

    private fun startScanCycle() {
        main.removeCallbacks(scanOn); main.removeCallbacks(scanOff)
        main.post(scanOn)
    }

    private fun beginScan() {
        if (!running) return
        val wait = lastScanStart + MIN_SCAN_RESTART_MS - System.currentTimeMillis()   // Android throttles scan starts
        if (wait > 0) { main.postDelayed(scanOn, wait); return }
        scanner = adapter?.bluetoothLeScanner
        val s = scanner ?: return
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
        val settings = ScanSettings.Builder().setScanMode(profile.scanMode).setReportDelay(0).build()
        runCatching { s.startScan(listOf(filter), settings, scanCallback); scanning = true; lastScanStart = System.currentTimeMillis() }
            .onFailure { Log.w(TAG, "scan start failed", it); main.postDelayed(scanOn, 10_000); return }
        if (!profile.continuous) main.postDelayed(scanOff, profile.scanOnMs)
    }

    private fun stopScan() {
        if (scanning) runCatching { scanner?.stopScan(scanCallback) }
        scanning = false
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = onScanned(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::onScanned)
        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "scan failed: $errorCode")
            scanning = false
            if (running) main.postDelayed(scanOn, if (errorCode == SCAN_FAILED_SCANNING_TOO_FREQUENTLY) 10_000 else 3_000)
        }
    }

    @Synchronized
    private fun onScanned(result: ScanResult) {
        if (!running) return
        val peerBytes = result.scanRecord?.getServiceData(ParcelUuid(SERVICE_UUID)) ?: return
        if (peerBytes.size < 8) return
        val peerId = peerBytes.copyOf(8).toHex()
        if (peerId == nodeId || nodeId > peerId) return                  // higher id waits to be dialled
        if (result.rssi < profile.minRssi) return
        val address = result.device.address
        val linkId = "c:$address"
        if (links.containsKey(linkId) || links.size + pendingConnect.size >= profile.maxLinks) return
        val now = System.currentTimeMillis()
        pendingConnect[address]?.let { if (now - it < CONNECT_TIMEOUT_MS) return }
        if ((backoff[address]?.first ?: 0L) > now) return
        pendingConnect[address] = now
        main.post { connect(result.device, result.rssi) }
    }

    // ------------------------------------------------------------ GATT client

    private fun connect(device: BluetoothDevice, rssi: Int) {
        val gatt = device.connectGatt(context, false, ClientCallback(rssi), BluetoothDevice.TRANSPORT_LE)
        main.postDelayed({
            synchronized(this) {
                if (pendingConnect.containsKey(device.address) && !links.containsKey("c:" + device.address)) {
                    failConnect(device.address); runCatching { gatt.disconnect(); gatt.close() }
                }
            }
        }, CONNECT_TIMEOUT_MS)
    }

    private inner class ClientCallback(private val rssi: Int) : BluetoothGattCallback() {
        private var frame = 20

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val address = gatt.device.address
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                main.postDelayed({ if (!gatt.requestMtu(REQUEST_MTU)) gatt.discoverServices() }, 200)
            } else {
                dropLink("c:$address")
                synchronized(this@BleTransport) { if (links["c:$address"] == null) failConnect(address) }
                main.postDelayed({ runCatching { gatt.close() } }, 500)
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) frame = mtu - 3
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val ch = gatt.getService(SERVICE_UUID)?.getCharacteristic(CHAR_UUID)
            val cccd = ch?.getDescriptor(CCCD_UUID)
            if (status != BluetoothGatt.GATT_SUCCESS || ch == null || cccd == null) { gatt.disconnect(); return }
            gatt.setCharacteristicNotification(ch, true)
            if (Build.VERSION.SDK_INT >= 33) gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            else @Suppress("DEPRECATION") run { cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; gatt.writeDescriptor(cccd) }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) { gatt.disconnect(); return }
            val ch = gatt.getService(SERVICE_UUID)?.getCharacteristic(CHAR_UUID) ?: return gatt.disconnect()
            val address = gatt.device.address
            synchronized(this@BleTransport) { pendingConnect.remove(address); backoff.remove(address) }
            registerLink(ClientLink("c:$address", gatt, ch, frame, rssi))
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            synchronized(this@BleTransport) { links["c:" + gatt.device.address] }?.onSent(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            deliver("c:" + gatt.device.address, value)
        }

        @Deprecated("API < 33")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION") deliver("c:" + gatt.device.address, c.value ?: return)
        }
    }

    private inner class ClientLink(
        id: String, val gatt: BluetoothGatt, val ch: BluetoothGattCharacteristic, frame: Int, rssiAtConnect: Int,
    ) : GattLink(id, frame) {
        init { rssi = rssiAtConnect }
        override fun transmit(frame: ByteArray): Boolean =
            if (Build.VERSION.SDK_INT >= 33) {
                gatt.writeCharacteristic(ch, frame, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION") run {
                    ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT; ch.value = frame; gatt.writeCharacteristic(ch)
                }
            }
        override fun close() { runCatching { gatt.disconnect() } }
    }

    // ------------------------------------------------------------ link plumbing

    private fun registerLink(link: GattLink) {
        synchronized(this) {
            links.put(link.id, link)?.close()
            reassemblers[link.id] = Reassembler()
        }
        listener.onLinkUp(link)
    }

    private fun dropLink(id: String) {
        val had = synchronized(this) { reassemblers.remove(id); links.remove(id) != null }
        if (had) listener.onLinkDown(id)
    }

    private fun failConnect(address: String) {
        pendingConnect.remove(address)
        val failures = (backoff[address]?.second ?: 0) + 1
        backoff[address] = (System.currentTimeMillis() + minOf(30_000L, 3_000L * failures)) to failures
    }

    private fun deliver(linkId: String, frame: ByteArray) {
        val packet = synchronized(this) { reassemblers[linkId] }?.add(frame, System.currentTimeMillis()) ?: return
        listener.onPacket(linkId, packet)
    }
}

/** One neighbour, over either GATT role. Packets are fragmented and sent one frame at a time. */
abstract class GattLink(override val id: String, frame: Int) : Link {
    @Volatile var frameSize: Int = frame.coerceIn(20, 512)
    @Volatile override var rssi: Int = -70
    override val connectedAtMs: Long = System.currentTimeMillis()

    private val queue = ArrayDeque<ByteArray>()
    private var busy = false

    protected abstract fun transmit(frame: ByteArray): Boolean
    abstract fun close()

    override fun send(packet: ByteArray) {
        val frames = Fragmenter.split(packet, frameSize)
        synchronized(this) { queue.addAll(frames) }
        pump()
    }

    fun onSent(ok: Boolean) {
        synchronized(this) { busy = false; if (!ok) queue.clear() }
        pump()
    }

    private fun pump() {
        val frame = synchronized(this) {
            if (busy || queue.isEmpty()) return
            busy = true
            queue.removeFirst()
        }
        if (!transmit(frame)) synchronized(this) { busy = false; queue.clear() }
    }
}
