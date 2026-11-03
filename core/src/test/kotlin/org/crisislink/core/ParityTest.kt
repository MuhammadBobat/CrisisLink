package org.crisislink.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.crisislink.core.routing.DecisionInputs
import org.crisislink.core.routing.DqnPolicy
import org.crisislink.core.routing.FEATURE_NAMES
import org.crisislink.core.routing.buildFeatures
import org.crisislink.core.routing.linkQualityFromRssi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Kotlin must reproduce the simulator's features and the Python network's Q-values exactly. */
class ParityTest {
    private val root = File(System.getProperty("crisislink.root") ?: "..")
    private val policyText = File(root, "app/src/main/assets/policy/dqn_policy.json").readText()

    @Test
    fun featureBuilderMatchesSimulator() {
        val cases = Json.parseToJsonElement(File(root, "simulation/fixtures/feature_cases.json").readText()).jsonArray
        assertTrue(cases.size > 20)
        for (c in cases) {
            val i = c.jsonObject.getValue("inputs").jsonObject
            fun d(k: String) = i.getValue(k).jsonPrimitive.double
            val got = buildFeatures(
                DecisionInputs(
                    urgent = i.getValue("urgent").jsonPrimitive.boolean,
                    ttlLeft = d("ttlLeft"),
                    hops = i.getValue("hops").jsonPrimitive.int,
                    pSelfToDst = d("pSelfToDst"),
                    pPeerToDst = d("pPeerToDst"),
                    peerFree = d("peerFree"),
                    selfFree = d("selfFree"),
                    peerBattery = d("peerBattery"),
                    selfBattery = d("selfBattery"),
                    linkQuality = d("linkQuality"),
                    contactAgeSeconds = d("contactAgeSeconds"),
                    neighbours = i.getValue("neighbours").jsonPrimitive.int,
                    isSource = i.getValue("isSource").jsonPrimitive.boolean,
                    peerIsDestination = i.getValue("peerIsDestination").jsonPrimitive.boolean,
                    copiesMade = i.getValue("copiesMade").jsonPrimitive.int,
                ),
            )
            val want = c.jsonObject.getValue("features").jsonArray.map { it.jsonPrimitive.double }
            assertEquals(want.size, got.size)
            want.forEachIndexed { k, w -> assertEquals("${FEATURE_NAMES[k]} in $i", w, got[k], 1e-5) }
        }
    }

    @Test
    fun policyMatchesPythonNetwork() {
        val policy = DqnPolicy.fromJson(policyText)
        val parity = Json.parseToJsonElement(policyText).jsonObject.getValue("parity").jsonObject
        val inputs = parity.getValue("inputs").jsonArray
        val outputs = parity.getValue("outputs").jsonArray
        assertTrue(inputs.size > 10)
        inputs.forEachIndexed { n, row ->
            val q = policy.qValues(row.jsonArray.map { it.jsonPrimitive.double }.toDoubleArray())
            val want = outputs[n].jsonArray.map { it.jsonPrimitive.double }
            assertEquals(want[0], q[0], 1e-4)
            assertEquals(want[1], q[1], 1e-4)
        }
    }

    @Test
    fun mismatchedFeatureSetIsRejected() {
        val bad = policyText.replace("\"copies_made\"", "\"something_else\"")
        assertThrows(IllegalArgumentException::class.java) { DqnPolicy.fromJson(bad) }
    }

    @Test
    fun rssiMapsIntoUnitRange() {
        assertEquals(0.0, linkQualityFromRssi(-120), 0.0)
        assertEquals(1.0, linkQualityFromRssi(-30), 0.0)
        assertEquals(0.5, linkQualityFromRssi(-70), 1e-9)
    }
}
