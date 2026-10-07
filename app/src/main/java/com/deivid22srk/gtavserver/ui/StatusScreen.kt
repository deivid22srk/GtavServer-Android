package com.deivid22srk.gtavserver.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deivid22srk.gtavserver.server.ServerBus

/** Tela principal: status do servidor, URL, pasta, porta e logs em tempo real. */
@Composable
fun StatusScreen(
    running: Boolean,
    starting: Boolean,
    url: String?,
    error: String?,
    logs: List<String>,
    folder: String,
    port: Int,
    hasStorage: Boolean,
    onRequestStorage: () -> Unit,
    onPortChange: (Int) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onPickFolder: () -> Unit,
    onIgnoreBattery: () -> Unit,
    batteryIgnored: Boolean,
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    var portText by remember { mutableStateOf(port.toString()) }

    LaunchedEffect(port) { portText = port.toString() }
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) listState.animateScrollToItem(logs.size - 1)
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("GTA Mirror Server", style = MaterialTheme.typography.headlineSmall)

        // ----- Status -----
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val (label, color) = when {
                        running -> "LIGADO" to MaterialTheme.colorScheme.primary
                        starting -> "INICIANDO…" to MaterialTheme.colorScheme.tertiary
                        else -> "DESLIGADO" to MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Text(label, style = MaterialTheme.typography.titleMedium, color = color)
                    Spacer(Modifier.width(12.dp))
                    url?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
                if (running && url != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("URL do servidor", url))
                        }) {
                            Icon(
                                Icons.Filled.ContentCopy,
                                contentDescription = null,
                                Modifier.size(16.dp),
                            )
                            Spacer(Modifier.size(4.dp))
                            Text("Copiar URL")
                        }
                        FilledTonalButton(onClick = {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }) {
                            Icon(
                                Icons.Filled.OpenInNew,
                                contentDescription = null,
                                Modifier.size(16.dp),
                            )
                            Spacer(Modifier.size(4.dp))
                            Text("Testar no navegador")
                        }
                    }
                }
            }
        }

        // ----- Erro -----
        error?.let {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    it,
                    Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }

        // ----- Permissão de armazenamento (sem SAF) -----
        if (!hasStorage) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Sem acesso aos arquivos",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        "O servidor precisa ler a pasta escolhida. Conceda o acesso " +
                            "(Android 11+: Todos os arquivos; Android 10 ou menor: " +
                            "permissão de armazenamento). Nada de SAF.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    FilledTonalButton(onClick = onRequestStorage) {
                        Text("Conceder acesso aos arquivos")
                    }
                }
            }
        }

        // ----- Pasta e porta -----
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Configuração", style = MaterialTheme.typography.titleSmall)
                FilledTonalButton(onClick = onPickFolder, enabled = !running && !starting) {
                    Icon(Icons.Filled.FolderOpen, contentDescription = null, Modifier.size(16.dp))
                    Spacer(Modifier.size(4.dp))
                    Text("Escolher pasta")
                }
                Text(
                    folder,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                OutlinedTextField(
                    value = portText,
                    onValueChange = { text ->
                        portText = text.filter { it.isDigit() }.take(5)
                        portText.toIntOrNull()?.let(onPortChange)
                    },
                    label = { Text("Porta (padrão do original: 8000)") },
                    enabled = !running && !starting,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // ----- Controle -----
        Button(
            onClick = { if (running) onStop() else onStart() },
            enabled = !starting,
            modifier = Modifier.fillMaxWidth(),
            colors = if (running) {
                ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            } else {
                ButtonDefaults.buttonColors()
            },
        ) {
            if (running) {
                Icon(Icons.Filled.Stop, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text("Parar servidor")
            } else {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text("Iniciar servidor")
            }
        }

        // ----- Bateria -----
        if (!batteryIgnored) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.BatterySaver,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(
                            "Para o servidor não morrer com a tela apagada, " +
                                "ignore a otimização de bateria.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    FilledTonalButton(onClick = onIgnoreBattery) {
                        Text("Ignorar otimização de bateria")
                    }
                }
            }
        }

        // ----- Logs -----
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Logs de requisições (reais)", style = MaterialTheme.typography.titleSmall)
                    IconButton(onClick = { ServerBus.clearLogs() }) {
                        Icon(
                            Icons.Filled.DeleteSweep,
                            contentDescription = "Limpar logs",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                if (logs.isEmpty()) {
                    Text(
                        "Sem requisições ainda. Ligue o servidor e abra " +
                            "http://127.0.0.1:8000 no navegador.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(240.dp),
                    ) {
                        items(logs) { line ->
                            Text(
                                line,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }

        Text(
            "Paridade com o original: HTTP/1.0 · porta 8000 · COOP/COEP/CORP + Accept-Ranges " +
                "em todas as respostas · byte ranges · POST /data/batch (máx. 1 MiB / 1000 runs " +
                "/ 64 MiB, ?gz=1) · index.html/htm · listagem de diretórios · 301/304/416/400.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
