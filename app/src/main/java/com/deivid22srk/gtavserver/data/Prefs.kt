package com.deivid22srk.gtavserver.data

import android.content.Context

/** Persistência simples da configuração (pasta escolhida, porta, estado). */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("gtav_server", Context.MODE_PRIVATE)

    var folder: String
        get() = sp.getString(KEY_FOLDER, DEFAULT_FOLDER) ?: DEFAULT_FOLDER
        set(value) = sp.edit().putString(KEY_FOLDER, value).apply()

    var port: Int
        get() = sp.getInt(KEY_PORT, 8000)
        set(value) = sp.edit().putInt(KEY_PORT, value).apply()

    var wasRunning: Boolean
        get() = sp.getBoolean(KEY_WAS_RUNNING, false)
        set(value) = sp.edit().putBoolean(KEY_WAS_RUNNING, value).apply()

    /** Captura de logs (servidor + webview) ligada pelo usuário para diagnóstico. */
    var captureEnabled: Boolean
        get() = sp.getBoolean(KEY_CAPTURE, false)
        set(value) = sp.edit().putBoolean(KEY_CAPTURE, value).apply()

    companion object {
        private const val KEY_FOLDER = "folder"
        private const val KEY_PORT = "port"
        private const val KEY_WAS_RUNNING = "was_running"
        private const val KEY_CAPTURE = "capture_enabled"
        const val DEFAULT_FOLDER = "/storage/emulated/0"
    }
}
