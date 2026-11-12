package br.com.instaladortv.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.instaladortv.discovery.DevModeStatus
import br.com.instaladortv.ui.InstallerViewModel
import br.com.instaladortv.ui.Screen
import br.com.instaladortv.ui.SectionCard
import br.com.instaladortv.ui.StatusChip

@Composable
fun HomeScreen(vm: InstallerViewModel, onTv: () -> Unit, onSettings: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    var manual by remember { mutableStateOf(false) }
    var manualIp by remember { mutableStateOf("") }

    Screen("Instalador TV", actions = { IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Configurações") } }) { modifier ->
        LazyColumn(modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                SectionCard {
                    Text("Este celular", style = MaterialTheme.typography.labelLarge)
                    Text(s.phoneIp ?: "Sem Wi-Fi", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("O celular e a TV precisam estar no mesmo Wi-Fi.", style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = vm::scan, enabled = !s.scanning, modifier = Modifier.fillMaxWidth()) {
                        Text(if (s.scanning) "Procurando TVs…" else "Procurar TVs")
                    }
                    if (s.scanning) LinearProgressIndicator(progress = { s.scanProgress }, modifier = Modifier.fillMaxWidth())
                }
            }
            s.message?.let { msg -> item { Text(msg, color = MaterialTheme.colorScheme.error) } }
            items(s.tvs, key = { it.ip }) { tv ->
                val status = DevModeStatus.of(tv, s.phoneIp)
                SectionCard(Modifier.clickable { vm.select(tv); onTv() }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(tv.modelName.ifBlank { tv.name.ifBlank { tv.platform.label } }, fontWeight = FontWeight.Bold)
                            Text("${tv.platform.label} · ${tv.year ?: "ano ?"} · ${tv.ip}", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    StatusChip(status)
                }
            }
            item {
                if (!manual) {
                    TextButton(onClick = { manual = true }) { Text("Não encontrou a TV? Digitar o IP da TV") }
                } else {
                    SectionCard {
                        Text("IP da TV (na TV: Configurações → Geral → Rede → Status da rede)", style = MaterialTheme.typography.bodyMedium)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(manualIp, { manualIp = it.trim() }, label = { Text("IP da TV") }, singleLine = true, modifier = Modifier.weight(1f))
                            OutlinedButton(onClick = { vm.addManualIp(manualIp) }) { Text("Usar") }
                        }
                    }
                }
            }
        }
    }
}
