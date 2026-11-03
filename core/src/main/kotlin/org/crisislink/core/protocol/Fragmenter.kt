package org.crisislink.core.protocol

import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

/**
 * Splits packets into BLE-sized frames and reassembles them, tolerating reordering.
 * Frame: fragment id (4 B), index (2 B), total (2 B), chunk.
 */
object Fragmenter {
    const val HEADER = 8
    private val nextId = AtomicInteger((Math.random() * Int.MAX_VALUE).toInt())

    fun split(packet: ByteArray, maxFrame: Int): List<ByteArray> {
        val chunk = maxFrame - HEADER
        require(chunk > 0) { "Frame size too small" }
        val total = maxOf(1, (packet.size + chunk - 1) / chunk)
        require(total <= 0xffff) { "Packet too large" }
        val id = nextId.incrementAndGet()
        return (0 until total).map { i ->
            val start = i * chunk
            val end = minOf(packet.size, start + chunk)
            ByteBuffer.allocate(HEADER + end - start)
                .putInt(id).putShort(i.toShort()).putShort(total.toShort())
                .put(packet, start, end - start)
                .array()
        }
    }
}

/** Reassembles frames from one link. Incomplete packets expire; memory is capped. */
class Reassembler(
    private val timeoutMs: Long = 30_000,
    private val maxBytes: Int = 1 shl 20,
    private val maxConcurrent: Int = 128,
) {
    private class Partial(val total: Int, val startedMs: Long) {
        val chunks = arrayOfNulls<ByteArray>(total)
        var received = 0
        var bytes = 0
    }

    private val partials = LinkedHashMap<Int, Partial>()

    /** Returns a complete packet, or null if more frames are needed or the frame was rejected. */
    fun add(frame: ByteArray, nowMs: Long): ByteArray? {
        if (frame.size < Fragmenter.HEADER) return null
        partials.values.removeIf { nowMs - it.startedMs > timeoutMs }
        val b = ByteBuffer.wrap(frame)
        val id = b.int
        val index = b.short.toInt() and 0xffff
        val total = b.short.toInt() and 0xffff
        if (total == 0 || index >= total) return null
        val chunk = frame.copyOfRange(Fragmenter.HEADER, frame.size)
        if (total == 1) return chunk

        val p = partials.getOrPut(id) {
            if (partials.size >= maxConcurrent) partials.remove(partials.keys.first())
            Partial(total, nowMs)
        }
        if (p.total != total || p.chunks[index] != null) return null
        p.bytes += chunk.size
        if (p.bytes > maxBytes) { partials.remove(id); return null }
        p.chunks[index] = chunk
        p.received++
        if (p.received < total) return null
        partials.remove(id)
        val out = java.io.ByteArrayOutputStream(p.bytes)
        p.chunks.forEach { out.write(it!!) }
        return out.toByteArray()
    }

    val pending: Int get() = partials.size
}
