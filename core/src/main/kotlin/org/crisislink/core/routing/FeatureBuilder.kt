package org.crisislink.core.routing

/**
 * State features for one forwarding decision: a message at this node, a candidate peer.
 *
 * Order and definitions mirror simulation/crisislink/features.py. Every feature is in [0, 1].
 * FeatureParityTest checks this against vectors produced by the simulator.
 */
val FEATURE_NAMES = listOf(
    "urgent", "ttl_left", "hops", "p_a_dst", "p_b_dst", "p_delta", "b_free", "a_free",
    "b_battery", "a_battery", "link_quality", "contact_age", "neighbours", "is_source", "b_is_dst",
    "copies_made",
)

const val CONTACT_AGE_CAP_SECONDS = 300.0
const val NEIGHBOUR_CAP = 5.0
const val COPIES_CAP = 8.0
const val MAX_HOPS = 10

/** Raw, observable inputs for a decision (local state or the peer's handshake). */
data class DecisionInputs(
    val urgent: Boolean,
    val ttlLeft: Double,
    val hops: Int,
    val pSelfToDst: Double,
    val pPeerToDst: Double,
    val peerFree: Double,
    val selfFree: Double,
    val peerBattery: Double,
    val selfBattery: Double,
    val linkQuality: Double,
    val contactAgeSeconds: Double,
    val neighbours: Int,
    val isSource: Boolean,
    val peerIsDestination: Boolean,
    val copiesMade: Int,
)

private fun unit(v: Double) = v.coerceIn(0.0, 1.0)
private fun flag(b: Boolean) = if (b) 1.0 else 0.0

fun buildFeatures(x: DecisionInputs): DoubleArray = doubleArrayOf(
    flag(x.urgent),
    unit(x.ttlLeft),
    unit(x.hops.toDouble() / MAX_HOPS),
    unit(x.pSelfToDst),
    unit(x.pPeerToDst),
    unit((x.pPeerToDst - x.pSelfToDst + 1) / 2),
    unit(x.peerFree),
    unit(x.selfFree),
    unit(x.peerBattery),
    unit(x.selfBattery),
    unit(x.linkQuality),
    unit(x.contactAgeSeconds / CONTACT_AGE_CAP_SECONDS),
    unit(x.neighbours / NEIGHBOUR_CAP),
    flag(x.isSource),
    flag(x.peerIsDestination),
    unit(x.copiesMade / COPIES_CAP),
)

/** BLE RSSI (dBm) to the simulator's link-quality scale: -100 dBm = edge of range, -40 = adjacent. */
fun linkQualityFromRssi(rssi: Int): Double = unit((rssi + 100) / 60.0)
