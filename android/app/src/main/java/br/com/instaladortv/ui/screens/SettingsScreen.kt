package br.com.instaladortv.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.instaladortv.BuildConfig
import br.com.instaladortv.ui.InstallerViewModel
import br.com.instaladortv.ui.Screen
import br.com.instaladortv.ui.SectionCard

@Composable
fun SettingsScreen(vm: InstallerViewModel, onBack: () -> Unit, onLimits: () -> Unit, onDiagnostics: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    var url by remember(s.panelUrl) { mutableStateOf(s.panelUrl) }
    var key by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }

    Screen("Configurações", onBack = onBack) { modifier ->
        Column(modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionCard {
                Text("Painel", style = MaterialTheme.typography.titleMedium)
                if (s.panelBuiltIn) {
                    Text(s.panelUrl)
                } else {
                    OutlinedTextField(url, { url = it.trim(); saved = false }, label = { Text("Endereço (https://…)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(
                        key, { key = it.trim(); saved = false },
                        label = { Text(if (s.panelConfigured) "Chave do app (deixe vazio para manter)" else "Chave do app") },
                        singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
                    )
                    Button(onClick = { vm.savePanel(url, key); key = ""; saved = true }, enabled = url.isNotBlank()) { Text("Salvar") }
                    if (saved) Text(if (s.panelConfigured) "Salvo." else "Falta a chave do app.")
                }
            }
            SectionCard {
                Text("Conta Samsung", style = MaterialTheme.typography.titleMedium)
                if (s.samsungEmail == null) {
                    Text("Nenhuma conta conectada. O login é pedido na primeira instalação.")
                } else {
                    Text(s.samsungEmail.orEmpty())
                    Text("TVs autorizadas: ${s.samsungDuids.joinToString().ifBlank { "nenhuma" }}", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = vm::logoutSamsung) { Text("Sair da conta Samsung") }
                    Text(
                        "Sair apaga os certificados deste celular. Apps já instalados só recebem atualização com a mesma conta; " +
                            "com outra conta é preciso desinstalar o app da TV antes.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            OutlinedButton(onClick = onLimits, modifier = Modifier.fillMaxWidth()) { Text("Limitações conhecidas") }
            OutlinedButton(onClick = onDiagnostics, modifier = Modifier.fillMaxWidth()) { Text("Diagnóstico (avançado)") }
            Text("Versão ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall)
        }
    }
}
