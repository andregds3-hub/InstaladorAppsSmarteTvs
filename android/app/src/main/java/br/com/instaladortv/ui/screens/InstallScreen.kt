package br.com.instaladortv.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.instaladortv.ui.InstallStep
import br.com.instaladortv.ui.InstallerViewModel
import br.com.instaladortv.ui.NumberedStep
import br.com.instaladortv.ui.Screen
import br.com.instaladortv.ui.SectionCard
import br.com.instaladortv.ui.theme.Success

private val VISIBLE_STEPS = listOf(
    InstallStep.DOWNLOAD, InstallStep.CERTIFICATE, InstallStep.CONNECT, InstallStep.PUSH, InstallStep.INSTALL, InstallStep.LAUNCH,
)

@Composable
fun InstallScreen(vm: InstallerViewModel, onBack: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    val install = s.install ?: return Screen("Instalação", onBack = onBack) { Text("Nenhuma instalação em andamento.", it.padding(16.dp)) }
    var showLog by remember { mutableStateOf(false) }
    val current = when (install.step) {
        InstallStep.LOGIN, InstallStep.SIGN -> InstallStep.CERTIFICATE
        else -> install.step
    }
    val currentIndex = VISIBLE_STEPS.indexOf(current).let { if (install.step == InstallStep.DONE) VISIBLE_STEPS.size else it }

    Screen(install.app.name, onBack = onBack) { modifier ->
        Column(modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("${s.selected?.title ?: "TV"} · versão ${install.app.version}", style = MaterialTheme.typography.bodyMedium)
            VISIBLE_STEPS.forEachIndexed { index, step ->
                val active = index == currentIndex && install.running
                NumberedStep(
                    number = index + 1,
                    title = step.label + if (active && install.percent != null) " (${install.percent}%)" else "",
                    body = if (active) install.detail.takeIf { install.step == InstallStep.LOGIN || install.step == InstallStep.CERTIFICATE || install.step == InstallStep.SIGN }.orEmpty() else "",
                    done = index < currentIndex || (install.ok && !install.running),
                )
                if (active) {
                    val percent = install.percent
                    if (percent != null) LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
            if (install.step == InstallStep.LOGIN && install.running) {
                SectionCard {
                    Text("Entre na sua conta Samsung no navegador que abriu.", fontWeight = FontWeight.Bold)
                    Text("Depois do login, toque em \"Voltar ao app\". É só na primeira vez (e quando a TV for nova).")
                }
            }
            if (!install.running) {
                SectionCard {
                    Text(
                        if (install.ok) "Instalado e aberto na TV!" else "Não foi possível instalar",
                        color = if (install.ok) Success else MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(install.message)
                    if (install.ok) {
                        Button(onClick = { vm.clearInstall(); onBack() }, modifier = Modifier.fillMaxWidth()) { Text("Concluir") }
                    } else {
                        Button(onClick = { vm.install(install.app) }, modifier = Modifier.fillMaxWidth()) { Text("Tentar de novo") }
                        OutlinedButton(onClick = { vm.clearInstall(); onBack() }, modifier = Modifier.fillMaxWidth()) { Text("Voltar") }
                    }
                }
            } else {
                OutlinedButton(onClick = vm::cancelInstall, modifier = Modifier.fillMaxWidth()) { Text("Cancelar") }
            }
            TextButton(onClick = { showLog = !showLog }) { Text(if (showLog) "Esconder detalhes" else "Ver detalhes técnicos") }
            if (showLog) Text(install.log.joinToString("\n"), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        }
    }
}
