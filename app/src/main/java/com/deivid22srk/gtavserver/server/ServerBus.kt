package com.deivid22srk.gtavserver.server

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Estado observável do servidor compartilhado entre Service e UI. */
object ServerBus {
    val running = MutableStateFlow(false)
    val starting = MutableStateFlow(false)
    val url = MutableStateFlow<String?>(null)
    val error = MutableStateFlow<String?>(null)
    val logs = MutableStateFlow<List<String>>(emptyList())

    fun log(line: String) {
        logs.update { current -> (current + line).takeLast(300) }
    }

    fun clearLogs() {
        logs.value = emptyList()
    }

    fun reset() {
        running.value = false
        starting.value = false
        url.value = null
    }
}
