package br.com.instaladortv.ui

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import br.com.instaladortv.ui.screens.AppsScreen
import br.com.instaladortv.ui.screens.HomeScreen
import br.com.instaladortv.ui.screens.InstallScreen
import br.com.instaladortv.ui.screens.LimitsScreen
import br.com.instaladortv.ui.screens.SettingsScreen
import br.com.instaladortv.ui.screens.TvScreen
import br.com.instaladortv.ui.screens.WizardScreen
import br.com.instaladortv.ui.theme.InstaladorTheme

class MainActivity : ComponentActivity() {
    private val vm: InstallerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            InstaladorTheme {
                // Login da Samsung: abre no navegador (Custom Tab); o retorno chega em localhost:4794.
                LaunchedEffect(Unit) {
                    vm.openUrl.collect { url -> CustomTabsIntent.Builder().build().launchUrl(this@MainActivity, Uri.parse(url)) }
                }
                val nav = rememberNavController()
                val back: () -> Unit = { nav.popBackStack() }
                NavHost(nav, startDestination = "home") {
                    composable("home") {
                        HomeScreen(vm, onTv = { nav.navigate("tv") }, onSettings = { nav.navigate("settings") })
                    }
                    composable("tv") {
                        TvScreen(vm, onBack = back, onWizard = { nav.navigate("wizard") }, onApps = { nav.navigate("apps") })
                    }
                    composable("wizard") {
                        WizardScreen(vm, onBack = back, onDone = { nav.popBackStack() })
                    }
                    composable("apps") {
                        AppsScreen(
                            vm,
                            onBack = back,
                            onInstall = { app -> vm.install(app); nav.navigate("install") },
                            onSettings = { nav.navigate("settings") },
                        )
                    }
                    composable("install") { InstallScreen(vm, onBack = back) }
                    composable("settings") {
                        SettingsScreen(vm, onBack = back, onLimits = { nav.navigate("limits") }, onDiagnostics = { nav.navigate("diagnostics") })
                    }
                    composable("limits") { LimitsScreen(onBack = back) }
                    composable("diagnostics") {
                        Screen("Diagnóstico", onBack = back) { modifier ->
                            Box(modifier) { PocScreen(viewModel()) }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.refreshPhoneIp()
    }
}
