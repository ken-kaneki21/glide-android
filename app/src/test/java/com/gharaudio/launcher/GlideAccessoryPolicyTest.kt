package com.gharaudio.launcher

import org.junit.Assert.*
import org.junit.Test

class GlideAccessoryPolicyTest {
    @Test fun knownBluetoothNamesKeepTheirProfileKeys() {
        val names = mapOf("M4 AEBT" to "Sennheiser MOMENTUM 4", "Galaxy Buds 2 Pro" to "Galaxy Buds2 Pro",
            "WF-1000XM4" to "Sony WF-1000XM4", "WH-1000XM4" to "Sony WH-1000XM4", "JBL Flip5" to "JBL Flip 5")
        names.forEach { (input, expected) -> assertEquals(expected, accessoryDescriptor(input, AccessoryKind.BLUETOOTH, true)?.label) }
    }
    @Test fun unknownBluetoothGetsAUsableSanitizedName() {
        assertEquals("My headset", accessoryDescriptor(" My\n headset ", AccessoryKind.BLUETOOTH, true)?.label)
        assertEquals("Bluetooth audio", accessoryDescriptor(null, AccessoryKind.BLUETOOTH, true)?.label)
        assertEquals("Bluetooth audio", accessoryDescriptor("A2DP", AccessoryKind.BLUETOOTH, true)?.label)
        assertEquals(128, accessoryDescriptor("x".repeat(300), AccessoryKind.BLUETOOTH, true)?.label?.length)
    }
    @Test fun analogModelsAreNotGuessedAndNonMusicOutputsAreIgnored() {
        assertEquals("Wired headphones", accessoryDescriptor("IE200", AccessoryKind.WIRED, true)?.label)
        assertNull(accessoryDescriptor("M4", AccessoryKind.BLUETOOTH, false))
        assertNull(accessoryDescriptor("M4", AccessoryKind.OTHER, true))
    }
    @Test fun openingWithConnectedOutputsDoesNotNotify() {
        val tracker = AccessoryConnections(mapOf(1 to "Headset"))
        assertFalse(tracker.added(1,"Headset"))
        assertFalse(tracker.added(2,"Headset"))
    }
    @Test fun duplicatesAreSuppressedAndGenuineReconnectionsNotify() {
        val tracker = AccessoryConnections(emptyMap())
        assertTrue(tracker.added(1,"Headset"))
        assertFalse(tracker.added(1,"Headset"))
        assertFalse(tracker.added(2,"Headset"))
        tracker.removed(1)
        assertTrue(tracker.hasLabel("Headset"))
        tracker.removed(2)
        assertFalse(tracker.hasLabel("Headset"))
        assertTrue(tracker.added(3,"Headset"))
        assertFalse(tracker.added(4,null))
    }
}
