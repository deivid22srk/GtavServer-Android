package com.deivid22srk.gtavserver.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File

/**
 * Navegador de arquivos PRÓPRIO (sem SAF/DocumentFile): lista pastas via
 * java.io.File, com subir nível, caminho atual, chips de volumes e botão
 * "Selecionar esta pasta". Depende do MANAGE_EXTERNAL_STORAGE (Android 11+)
 * ou READ_EXTERNAL_STORAGE (Android <= 10).
 */
@Composable
fun FolderPickerScreen(
    initialPath: String,
    onPick: (String) -> Unit,
    onBack: () -> Unit,
) {
    var current by remember { mutableStateOf(normalizeRoot(initialPath)) }
    var dirs by remember { mutableStateOf<List<File>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var volumes by remember { mutableStateOf<List<File>>(emptyList()) }

    LaunchedEffect(current) {
        error = null
        val dir = File(current)
        val children = dir.listFiles()
        if (children == null) {
            dirs = emptyList()
            error = "Não foi possível ler esta pasta (sem permissão ou inacessível)."
        } else {
            dirs = children
                .filter { it.isDirectory }
                .sortedWith(compareBy({ it.name.lowercase() }))
        }
    }

    LaunchedEffect(Unit) {
        volumes = listVolumes()
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar")
            }
            Column(Modifier.weight(1f)) {
                Text("Escolher pasta do espelho", style = MaterialTheme.typography.titleMedium)
                Text(
                    current,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // Volumes: armazenamento interno e cartões SD (/storage/XXXX-XXXX)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            volumes.take(3).forEach { vol ->
                FilledTonalButton(onClick = { current = normalizeRoot(vol.absolutePath) }) {
                    Icon(Icons.Filled.Storage, contentDescription = null, Modifier.size(16.dp))
                    Spacer(Modifier.size(4.dp))
                    Text(volumeLabel(vol), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        Spacer(Modifier.height(8.dp))

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
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(8.dp))
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(
                onClick = {
                    val parent = File(current).parentFile
                    if (parent != null && parent.canRead() || parent?.absolutePath == "/storage") {
                        current = normalizeRoot(parent.absolutePath)
                    } else if (parent != null) {
                        current = normalizeRoot(parent.absolutePath)
                    }
                },
                enabled = File(current).parent != null,
            ) {
                Icon(Icons.Filled.ArrowUpward, contentDescription = null, Modifier.size(16.dp))
                Spacer(Modifier.size(4.dp))
                Text("Subir um nível")
            }
            FilledTonalButton(onClick = { onPick(current) }) {
                Icon(Icons.Filled.Check, contentDescription = null, Modifier.size(16.dp))
                Spacer(Modifier.size(4.dp))
                Text("Selecionar esta pasta")
            }
        }

        Spacer(Modifier.height(8.dp))

        LazyColumn(Modifier.fillMaxSize()) {
            items(dirs, key = { it.absolutePath }) { dir ->
                Card(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                        .clickable {
                            current = normalizeRoot(dir.absolutePath)
                        },
                ) {
                    Row(
                        Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.Folder,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.size(12.dp))
                        Text(dir.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** Normaliza caminhos: remove barra final (exceto raiz). */
fun normalizeRoot(path: String): String {
    var p = path.trimEnd('/')
    if (p.isEmpty()) p = "/"
    return p
}

/** Volumes navegáveis: interno + externos removíveis (/storage/XXXX-XXXX). */
fun listVolumes(): List<File> {
    val result = mutableListOf<File>()
    val storage = File("/storage")
    val children = storage.listFiles()
    if (children != null) {
        children.filter { it.isDirectory }.forEach { result.add(it) }
    } else {
        result.add(File("/storage/emulated/0"))
    }
    // Garante o interno mesmo se /storage não listar
    val emulated = File("/storage/emulated/0")
    if (result.none { it.absolutePath == emulated.absolutePath }) {
        result.add(0, emulated)
    }
    return result.distinctBy { it.absolutePath }
}

fun volumeLabel(vol: File): String = when (vol.absolutePath) {
    "/storage/emulated/0", "/sdcard" -> "Interno"
    else -> vol.name.ifEmpty { vol.absolutePath }
}
