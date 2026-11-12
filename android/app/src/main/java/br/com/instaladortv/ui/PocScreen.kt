package br.com.instaladortv.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.instaladortv.discovery.TvInfo
import br.com.instaladortv.tizen.InstallId

@Composable
fun PocScreen(vm: PocViewModel) {
    val s by vm.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), vm::pickPackage)
    val permitPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), vm::pickPermit)

    LazyColumn(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Section("1. Rede") {
                Text("IP deste celular: ${s.phoneIp ?: "sem Wi-Fi"}", fontWeight = FontWeight.Bold)
                Text("Digite este IP no Modo Desenvolvedor da TV (Apps → 12345) e reinicie a TV.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = vm::scan, enabled = !s.scanning) { Text("Procurar TVs") }
                    OutlinedButton(onClick = vm::refreshPhoneIp) { Text("Atualizar IP") }
                }
                if (s.scanning) LinearProgressIndicator(progress = { s.scanProgress }, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(s.manualIp, vm::setManualIp, label = { Text("IP da TV") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = vm::useManualIp) { Text("Usar") }
                }
            }
        }
        items(s.tvs, key = { it.ip }) { tv -> TvCard(tv, tv.ip == s.selected?.ip) { vm.select(tv) } }

        s.selected?.let { tv ->
            item {
                Section("2. TV escolhida: ${tv.ip}") {
                    Text("${tv.modelName.ifEmpty { tv.model }}  ·  ano ${tv.year ?: "?"}  ·  Tizen de fábrica ${tv.factoryTizen.ifEmpty { "?" }}")
                    if (tv.developerMode == false) Text("Modo Desenvolvedor DESLIGADO na TV.", color = MaterialTheme.colorScheme.error)
                    if (tv.developerIp.isNotEmpty() && tv.developerIp != s.phoneIp) {
                        Text("A TV está liberada para ${tv.developerIp}, mas este celular é ${s.phoneIp}.", color = MaterialTheme.colorScheme.error)
                    }
                    OutlinedButton(onClick = vm::testSdb, enabled = !s.busy) { Text("Testar conexão SDB") }
                    s.sdbReport?.let { Mono(it) }
                }
            }
        }

        item {
            Section("3. Pacote (.wgt já assinado)") {
                OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text("Escolher arquivo") }
                s.packageError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                s.packageInfo?.let { info ->
                    Text("${info.name}  ${info.packageId} / ${info.appId}  v${info.version}")
                    Text("Tizen exigido: ${info.requiredVersion.ifEmpty { "-" }}  ·  assinado: ${if (info.isSigned) "sim" else "NÃO"}")
                    Mono("SHA-256 ${s.packageSha256}")
                }
                OutlinedTextField(
                    s.expectedSha256, vm::setExpectedSha256,
                    label = { Text("SHA-256 esperado (opcional)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(onClick = { permitPicker.launch(arrayOf("*/*")) }) { Text("Escolher device-profile.xml (Permit)") }
                s.permitError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (s.permitFile != null) {
                    val duid = s.selected?.duid
                    Text("Permit para: ${s.permitDevices.joinToString().ifEmpty { "?" }}")
                    if (duid != null && duid.isNotEmpty() && !duid.startsWith("uuid:") && duid !in s.permitDevices) {
                        Text("Este perfil não lista o DUID da TV escolhida.", color = MaterialTheme.colorScheme.error)
                    }
                } else {
                    Text("Sem o device-profile.xml a TV pode recusar com install failed[118].")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("vd_appinstall usa:")
                    FilterChip(s.installId == InstallId.PACKAGE, { vm.setInstallId(InstallId.PACKAGE) }, label = { Text("package ID") })
                    FilterChip(s.installId == InstallId.APP, { vm.setInstallId(InstallId.APP) }, label = { Text("app ID") })
                }
            }
        }

        item {
            Section("4. Instalar") {
                Button(
                    onClick = vm::install,
                    enabled = !s.busy && s.selected != null && s.packageInfo?.isSigned == true,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Instalar e abrir") }
                if (s.busy) {
                    Text(s.stage?.label ?: "Aguarde…")
                    val percent = s.percent
                    if (percent != null) {
                        LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
                s.result?.let { r ->
                    Text(
                        (if (r.ok) "Instalado: " else "Não instalado: ") + r.message,
                        color = if (r.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold,
                    )
                }
                OutlinedButton(onClick = { clipboard.setText(AnnotatedString(vm.report())) }) { Text("Copiar relatório") }
            }
        }

        item { Section("Log") { Mono(s.log.takeLast(80).joinToString("\n")) } }
    }
}

@Composable
private fun TvCard(tv: TvInfo, selected: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = if (selected) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else CardDefaults.cardColors(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(tv.name.ifEmpty { tv.platform.label }, fontWeight = FontWeight.Bold)
            Text("${tv.ip}  ·  ${tv.modelName.ifEmpty { tv.model.ifEmpty { "modelo ?" } }}  ·  ${tv.year ?: ""}")
            Text("SDB ${if (tv.sdbOpen) "aberta" else "fechada"}  ·  Modo Dev ${when (tv.developerMode) { true -> "ligado"; false -> "desligado"; null -> "?" }}")
            if (tv.duid.isNotEmpty()) Text("DUID ${tv.duid}", fontSize = 12.sp)
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun Mono(text: String) = Text(text, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
