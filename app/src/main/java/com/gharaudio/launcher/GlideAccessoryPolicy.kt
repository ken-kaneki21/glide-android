package com.gharaudio.launcher

import java.util.Locale

internal enum class AccessoryKind { WIRED, BLUETOOTH, OTHER }
internal data class AccessoryDescriptor(val label: String, val kind: AccessoryKind)

/** Audio-output names describe availability, not a verified playback route. */
internal fun accessoryDescriptor(name: String?, kind: AccessoryKind, isSink: Boolean): AccessoryDescriptor? {
    if (!isSink || kind == AccessoryKind.OTHER) return null
    // An analog output cannot identify the headphone model attached to it.
    if (kind == AccessoryKind.WIRED) return AccessoryDescriptor("Wired headphones", kind)
    val clean = name.orEmpty().filterNot(Char::isISOControl).trim().take(128)
    val lower = clean.lowercase(Locale.ROOT)
    val label = when {
        "momentum 4" in lower || lower == "m4" || lower == "m4 aebt" -> "Sennheiser MOMENTUM 4"
        "buds2 pro" in lower || "buds 2 pro" in lower -> "Galaxy Buds2 Pro"
        "wf-1000xm4" in lower -> "Sony WF-1000XM4"
        "wh-1000xm4" in lower -> "Sony WH-1000XM4"
        "flip 5" in lower || "flip5" in lower -> "JBL Flip 5"
        "ie 200" in lower || "ie200" in lower -> "Sennheiser IE 200"
        clean.isBlank() || lower in setOf("bluetooth", "bluetooth a2dp", "a2dp", "unknown") -> "Bluetooth audio"
        else -> clean
    }
    return AccessoryDescriptor(label, kind)
}

/** Seed from the initial snapshot so opening Glide is not a new connection event.
 * Multiple simultaneous IDs with the same label are grouped for presentation only;
 * this is not hardware identity or active-route tracking. */
internal class AccessoryConnections(initial: Map<Int, String?>) {
    private val outputs = initial.toMutableMap()
    fun added(id: Int, label: String?): Boolean {
        if (outputs.containsKey(id)) return false
        val alreadyPresented = label != null && outputs.values.any { it == label }
        outputs[id] = label
        return label != null && !alreadyPresented
    }
    fun removed(id: Int) { outputs.remove(id) }
    fun hasLabel(label: String): Boolean = outputs.values.any { it == label }
}
