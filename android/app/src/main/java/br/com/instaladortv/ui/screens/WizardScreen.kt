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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.instaladortv.discovery.DevModeStatus
import br.com.instaladortv.discovery.Platform
import br.com.instaladortv.ui.InstallerViewModel
import br.com.instaladortv.ui.NumberedStep
import br.com.instaladortv.ui.Screen
import br.com.instaladortv.ui.SectionCard
import br.com.instaladortv.ui.StatusChip
import br.com.instaladortv.ui.theme.Success

@Composable
fun WizardScreen(vm: InstallerViewModel, onBack: () -> Unit, onDone: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    DisposableEffect(Unit) {
        vm.startDevPolling()
        onDispose { vm.stopDevPolling() }
    }
    val ready = s.devStatus == DevModeStatus.READY
    val phoneIp = s.phoneIp ?: "?"
    val tizen = s.selected?.platform != Platform.ANDROID_TV

    Screen("Modo Desenvolvedor", onBack = onBack) { modifier ->
        Column(modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SectionCard {
                Text("Digite este IP na TV", style = MaterialTheme.typography.labelLarge)
                Text(phoneIp, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                StatusChip(s.devStatus)
                if (!ready) {
                    Text("Verificando a TV a cada 3 segundos…", style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
            if (tizen) {
                NumberedStep(1, "Abra a tela Apps", "No controle remoto, aperte Home e entre em Apps.")
                NumberedStep(2, "Digite 1 2 3 4 5", "Com a tela Apps aberta, digite 12345 no controle (números ou teclado na tela).")
                NumberedStep(3, "Ligue o Developer mode", "Na janela que aparece, coloque Developer mode em ON.")
                NumberedStep(4, "Digite o IP deste celular", "Em Host PC IP, digite $phoneIp e confirme em OK.")
                NumberedStep(
                    5,
                    "Reinicie a TV",
                    "Segure o botão de ligar do controle até a TV desligar e religar (ou tire da tomada por 30 segundos).",
                    done = ready,
                )
            } else {
                NumberedStep(1, "Ative as opções do desenvolvedor", "Configurações → Sistema → Sobre → toque 7 vezes em Build do Android TV OS.")
                NumberedStep(2, "Ative a depuração", "Configurações → Sistema → Opções do desenvolvedor → Depuração USB / pela rede.")
                NumberedStep(3, "Autorize este celular", "Quando a TV perguntar, permita a depuração deste aparelho.", done = ready)
            }
            if (ready) {
                Text("Pronto! A TV aceita este celular.", color = Success, fontWeight = FontWeight.Bold)
                Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Continuar") }
            } else if (s.devStatus == DevModeStatus.OTHER_DEVICE) {
                Text(vm.devModeMessage(s.devStatus), color = MaterialTheme.colorScheme.error)
            }
            Text(
                "Se o IP do celular mudar (outro Wi-Fi ou roteador reiniciado), repita os passos 4 e 5.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
