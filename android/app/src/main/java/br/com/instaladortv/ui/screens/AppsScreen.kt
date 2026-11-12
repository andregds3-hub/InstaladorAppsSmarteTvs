package br.com.instaladortv.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import br.com.instaladortv.api.PanelApp
import br.com.instaladortv.ui.Avatar
import br.com.instaladortv.ui.InstallerViewModel
import br.com.instaladortv.ui.Screen
import br.com.instaladortv.ui.SectionCard
import br.com.instaladortv.ui.formatSize

@Composable
fun AppsScreen(vm: InstallerViewModel, onBack: () -> Unit, onInstall: (PanelApp) -> Unit, onSettings: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf<PanelApp?>(null) }
    var code by remember { mutableStateOf("") }
    LaunchedEffect(s.selected?.tv?.ip, s.selected?.platform, s.selected?.year, s.panelConfigured) {
        if (s.panelConfigured) vm.loadApps()
    }

    Screen("Apps para ${s.selected?.title ?: "a TV"}", onBack = onBack) { modifier ->
        LazyColumn(modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!s.panelConfigured) {
                item {
                    SectionCard {
                        Text("O app ainda não sabe o endereço do painel.")
                        Button(onClick = onSettings) { Text("Abrir Configurações") }
                    }
                }
            }
            if (s.appsLoading) item { Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() } }
            if (s.needsAccessCode) {
                item {
                    SectionCard {
                        Text("Digite o código de acesso que você recebeu do fornecedor.")
                        OutlinedTextField(code, { code = it.uppercase() }, label = { Text("Código de acesso") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Button(onClick = { vm.submitAccessCode(code) }, enabled = code.length >= 4) { Text("Confirmar") }
                    }
                }
            }
            s.appsError?.takeIf { !s.needsAccessCode }?.let { err ->
                item {
                    SectionCard {
                        Text(err, color = MaterialTheme.colorScheme.error)
                        OutlinedButton(onClick = vm::loadApps) { Text("Tentar de novo") }
                    }
                }
            }
            if (!s.appsLoading && s.appsError == null && s.panelConfigured && s.apps.isEmpty()) {
                item { Text("Nenhum app compatível com esta TV no momento.") }
            }
            items(s.apps, key = { it.id }) { app ->
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Avatar(app.name)
                        Column(Modifier.weight(1f)) {
                            Text(app.name, fontWeight = FontWeight.Bold)
                            Text("Versão ${app.version} · ${formatSize(app.size)}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (app.description.isNotBlank()) Text(app.description, style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = { confirm = app }, modifier = Modifier.fillMaxWidth()) { Text("Instalar") }
                }
            }
        }
    }

    confirm?.let { app ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Instalar ${app.name}?") },
            text = {
                Text(
                    "O app será baixado, assinado para esta TV e instalado. Na primeira vez é preciso entrar na sua " +
                        "conta Samsung para gerar o certificado da TV.",
                )
            },
            confirmButton = { Button(onClick = { confirm = null; onInstall(app) }) { Text("Instalar") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancelar") } },
        )
    }
}
