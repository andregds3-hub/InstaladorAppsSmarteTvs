package br.com.instaladortv.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.instaladortv.discovery.DevModeStatus
import br.com.instaladortv.discovery.Platform
import br.com.instaladortv.discovery.TvInfo
import br.com.instaladortv.ui.InstallerViewModel
import br.com.instaladortv.ui.Screen
import br.com.instaladortv.ui.SectionCard
import br.com.instaladortv.ui.StatusChip

@Composable
fun TvScreen(vm: InstallerViewModel, onBack: () -> Unit, onWizard: () -> Unit, onApps: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    val sel = s.selected ?: return Screen("TV", onBack = onBack) { Text("Escolha uma TV.", it.padding(16.dp)) }
    var editing by remember { mutableStateOf(false) }
    var yearText by remember(sel.year) { mutableStateOf(sel.year?.toString().orEmpty()) }

    LaunchedEffect(sel.tv.ip) { vm.refreshDevStatus() }

    Screen(sel.title, onBack = onBack) { modifier ->
        Column(modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionCard {
                Text(sel.tv.name.ifBlank { sel.title }, fontWeight = FontWeight.Bold)
                Text("${sel.platform.label} · ano ${sel.year ?: "?"} · ${sel.tv.ip}")
                if (sel.platform == Platform.TIZEN) {
                    val tizen = sel.osVersion.ifBlank { TvInfo.TIZEN_BY_YEAR[sel.year] ?: "?" }
                    Text("Tizen $tizen${if (sel.osVersion.isBlank()) " (de fábrica)" else ""}", style = MaterialTheme.typography.bodyMedium)
                }
                if (sel.duid.isNotBlank()) Text("DUID ${sel.duid}", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { editing = !editing }) { Text(if (editing) "Fechar" else "O modelo está errado? Corrigir") }
                if (editing) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(Platform.TIZEN, Platform.ANDROID_TV).forEach { p ->
                            FilterChip(sel.platform == p, { vm.correctModel(p, sel.year) }, label = { Text(p.label) })
                        }
                    }
                    OutlinedTextField(
                        yearText,
                        { v -> yearText = v.filter(Char::isDigit).take(4); vm.correctModel(sel.platform, yearText.toIntOrNull()) },
                        label = { Text("Ano do modelo (ex.: 2018)") },
                        singleLine = true,
                    )
                }
            }

            SectionCard {
                Text("Modo Desenvolvedor", style = MaterialTheme.typography.titleMedium)
                StatusChip(s.devStatus)
                if (s.devStatus != DevModeStatus.READY) Text(vm.devModeMessage(s.devStatus), style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = onWizard, modifier = Modifier.fillMaxWidth()) {
                    Text(if (s.devStatus == DevModeStatus.READY) "Ver o passo a passo" else "Abrir o passo a passo")
                }
            }

            s.connectError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                onClick = { vm.connect(onConnected = onApps) },
                enabled = !s.connecting && s.devStatus != DevModeStatus.OFF && s.devStatus != DevModeStatus.OTHER_DEVICE,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (s.connecting) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("Conectando…")
                    }
                } else {
                    Text(if (sel.connected) "Ver apps para esta TV" else "Conectar e ver apps")
                }
            }
        }
    }
}
