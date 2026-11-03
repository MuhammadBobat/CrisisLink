package org.crisislink.core

import org.crisislink.core.crypto.Identity
import org.crisislink.core.model.ContactCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContactCodeTest {
    @Test
    fun roundTripDerivesNodeIdFromKeys() {
        val id = Identity.generate()
        val contact = ContactCode.decode(ContactCode.encode("Amal: north shelter", id.keys))!!
        assertEquals(id.nodeId, contact.nodeId)
        assertEquals("Amal: north shelter", contact.name)
        assertEquals(id.keys, contact.keys)
    }

    @Test
    fun rejectsGarbage() {
        listOf("", "hello", "crisislink:1:x:zz:yy", "crisislink:1:x:00:00", "crisislink:2:x:" + "00".repeat(32) + ":" + "00".repeat(32))
            .forEach { assertNull(it, ContactCode.decode(it)) }
    }
}
