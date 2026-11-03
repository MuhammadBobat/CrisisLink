package org.crisislink.core.routing

import kotlin.math.pow

/**
 * Encounter predictability P(self, x): how likely this node is to meet x again soon.
 * PRoPHET-style update with aging and transitivity; constants match SimConfig.
 */
class EncounterTable(
    initial: Map<String, Double> = emptyMap(),
    private var updatedAtMs: Long,
) {
    private val p = initial.toMutableMap()

    private fun age(nowMs: Long) {
        val elapsed = (nowMs - updatedAtMs) / 1000.0
        if (elapsed <= 0) return
        val factor = GAMMA.pow(elapsed / UNIT_SECONDS)
        p.replaceAll { _, v -> v * factor }
        p.values.removeIf { it < 0.001 }
        updatedAtMs = nowMs
    }

    fun predictability(nodeId: String, nowMs: Long): Double {
        age(nowMs)
        return p[nodeId] ?: 0.0
    }

    /** This node's row, strongest entries first, for the handshake. */
    fun export(nowMs: Long): Map<String, Double> {
        age(nowMs)
        return p.entries.sortedByDescending { it.value }.take(MAX_EXPORTED).associate { it.key to it.value }
    }

    /** Update after meeting [peerId], who reported its own row [peerRow]. */
    fun onEncounter(peerId: String, peerRow: Map<String, Double>, selfId: String, nowMs: Long) {
        age(nowMs)
        val direct = p[peerId] ?: 0.0
        val pPeer = direct + (1 - direct) * P_INIT
        p[peerId] = pPeer
        for ((node, pPeerToNode) in peerRow) {
            if (node == selfId || node == peerId) continue
            val cur = p[node] ?: 0.0
            p[node] = cur + (1 - cur) * pPeer * pPeerToNode.coerceIn(0.0, 1.0) * BETA
        }
    }

    fun snapshot(): Pair<Map<String, Double>, Long> = p.toMap() to updatedAtMs

    companion object {
        const val P_INIT = 0.75
        const val BETA = 0.25
        const val GAMMA = 0.98
        const val UNIT_SECONDS = 600.0
        const val MAX_EXPORTED = 64
    }
}
