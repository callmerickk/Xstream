package com.example.streamnest

/** Something else needs the microphone (voice search): the wake word stops while n > 0. */
object MicHold { val n = kotlinx.coroutines.flow.MutableStateFlow(0) }
