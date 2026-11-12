package br.com.instaladortv.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import br.com.instaladortv.ui.NumberedStep
import br.com.instaladortv.ui.Screen

private val LIMITS = listOf(
    "Modo Desenvolvedor manual" to "O Modo Desenvolvedor precisa ser ativado manualmente na TV.",
    "IP do celular" to "Se o IP do celular mudar, é preciso atualizar o IP no Modo Desenvolvedor e reiniciar a TV.",
    "Atualização ou reset da TV" to "Atualização de firmware ou reset da TV pode desativar o Modo Desenvolvedor e exigir reinstalação.",
    "Certificados" to "Os certificados valem 1 ano e têm limite de aparelhos. Depois de expirar, o app gera novos no próximo uso.",
    "Uma conexão por vez" to "A TV aceita uma conexão de desenvolvedor por vez: feche o Tizen Studio no computador antes de usar o celular.",
    "Mesma conta Samsung" to "Atualizações de um app só instalam com a mesma conta Samsung usada na primeira instalação.",
)

@Composable
fun LimitsScreen(onBack: () -> Unit) {
    Screen("Limitações conhecidas", onBack = onBack) { modifier ->
        Column(modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            LIMITS.forEachIndexed { i, (title, body) -> NumberedStep(i + 1, title, body) }
        }
    }
}
