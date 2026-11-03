package org.crisislink.core.routing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Q-network trained in simulation (simulation/train.py), evaluated on-device.
 * Actions: index 0 = hold, 1 = forward.
 */
class DqnPolicy private constructor(private val layers: List<Layer>, var margin: Double) {

    private class Layer(val w: Array<DoubleArray>, val b: DoubleArray, val relu: Boolean) // w is [in][out]

    /** Returns [Q(hold), Q(forward)]. */
    fun qValues(features: DoubleArray): DoubleArray {
        var h = features
        for (layer in layers) {
            val out = layer.b.copyOf()
            for (i in h.indices) {
                val xi = h[i]
                if (xi == 0.0) continue
                val row = layer.w[i]
                for (j in out.indices) out[j] += xi * row[j]
            }
            if (layer.relu) for (j in out.indices) if (out[j] < 0) out[j] = 0.0
            h = out
        }
        return h
    }

    /** Q-advantage of forwarding over holding; also used to rank candidates. */
    fun advantage(features: DoubleArray): Double = qValues(features).let { it[1] - it[0] }

    companion object {
        const val ASSET_PATH = "policy/dqn_policy.json"

        fun fromJson(text: String, margin: Double = 0.0): DqnPolicy = fromJson(Json.parseToJsonElement(text).jsonObject, margin)

        fun fromJson(json: JsonObject, margin: Double = 0.0): DqnPolicy {
            val names = json.getValue("feature_names").jsonArray.map { it.jsonPrimitive.content }
            require(names == FEATURE_NAMES) { "Policy feature set does not match the app: $names" }
            val layers = json.getValue("layers").jsonArray.map { el ->
                val l = el.jsonObject
                Layer(
                    w = l.getValue("w").jsonArray.map { row -> row.jsonArray.map { it.jsonPrimitive.double }.toDoubleArray() }.toTypedArray(),
                    b = l.getValue("b").jsonArray.map { it.jsonPrimitive.double }.toDoubleArray(),
                    relu = l.getValue("act").jsonPrimitive.content == "relu",
                )
            }
            return DqnPolicy(layers, margin)
        }
    }
}
