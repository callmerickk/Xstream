package com.example.streamnest

/**
 * Named on/off switches for features that are new or Xstream-only. In the beta app (Xstream) every switch is on;
 * in the stable app (StreamX) a switch is on only when its name is in [STABLE]. Add a switch here, check it where the
 * feature starts, and add its name to [STABLE] once it is approved for StreamX.
 */
object Feature {
    /** The switches approved for the stable app. The only place to turn a feature on in StreamX. */
    private val STABLE: Set<String> = setOf()

    private fun on(name: String): Boolean = Brand.IS_BETA || name in STABLE

    /** Example: the cloud link finder (Xstream CloudFind.kt), not in the shared code yet. */
    val cloudFind: Boolean = on("cloudFind")
    /** Example: invite-only access (Xstream Access.kt), not in the shared code yet. */
    val accessGate: Boolean = on("accessGate")
}
