package com.deivid22srk.gtavserver.server

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Estado observável do servidor compartilhado entre Service e UI.
 *
 * Os LOGS agora vivem no [LogBus] (captura SERVIDOR/WEBVIEW/APP com exportação);
 * [log] mantém-se aqui como delegação para os pontos de chamada do serviço.
 */
object ServerBus {
    val running = MutableStateFlow(false)
    val starting = MutableStateFlow(false)
    val url = MutableStateFlow<String?>(null)
    val error = MutableStateFlow<String?>(null)

    fun log(line: String) {
        LogBus.log(LogBus.SERVER, line)
    }

    fun reset() {
        running.value = false
        starting.value = false
        url.value = null
    }
}
