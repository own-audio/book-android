package audio.own.bookplayer.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import audio.own.core.designsystem.theme.AccentChoice
import audio.own.core.designsystem.theme.OwnAudioTheme
import audio.own.bookplayer.AppContainer
import audio.own.bookplayer.account.DevicesContent
import audio.own.bookplayer.account.DevicesViewModel
import audio.own.bookplayer.account.HouseholdContent
import audio.own.bookplayer.account.HouseholdViewModel
import audio.own.bookplayer.account.ListeningContent
import audio.own.bookplayer.account.ListeningViewModel
import audio.own.bookplayer.google.GoogleResult
import audio.own.bookplayer.google.GoogleSignIn
import audio.own.bookplayer.onboarding.AccountMode
import audio.own.bookplayer.onboarding.AddServerContent
import audio.own.bookplayer.onboarding.OnboardingContent
import audio.own.bookplayer.onboarding.OwnAudioAccountContent
import audio.own.bookplayer.onboarding.OwnAudioAccountViewModel
import audio.own.bookplayer.onboarding.ServerFormViewModel
import audio.own.bookplayer.onboarding.TwoFactorContent
import audio.own.bookplayer.onboarding.TwoFactorViewModel
import audio.own.bookplayer.servers.ServerDetailContent
import audio.own.bookplayer.servers.ServerDetailViewModel
import audio.own.bookplayer.servers.ServerDetector
import audio.own.bookplayer.servers.LocalServerIcons
import audio.own.bookplayer.servers.ServerPageUi
import audio.own.bookplayer.servers.SignInAgainContent
import audio.own.bookplayer.servers.SignInAgainViewModel
import audio.own.bookplayer.settings.AppLanguage
import audio.own.bookplayer.settings.LicencesContent
import audio.own.bookplayer.settings.LogsContent
import audio.own.bookplayer.settings.SettingsContent
import audio.own.bookplayer.settings.SettingsUi
import audio.own.bookplayer.settings.shareLog
import kotlinx.coroutines.launch

private object Routes {
    const val MAIN = "main"
    const val ACCOUNT = "account/{mode}"
    const val SETTINGS = "settings"
    const val ADD_SERVER = "add-server"
    const val SERVER = "server/{id}"
    const val SIGN_IN_AGAIN = "sign-in-again/{id}"
    const val FAMILY = "family/{id}"
    const val DEVICES = "devices/{id}"
    const val LISTENING = "listening/{id}"
    const val LOGS = "logs"
    const val LICENCES = "licences"
}

/** The whole app: the theme with the chosen accent, the pages, and the two-factor step over them. */
@Composable
fun AppRoot(container: AppContainer) {
    val accent by container.preferences.accent.collectAsState(initial = AccentChoice.APP)
    OwnAudioTheme(accent = accent) {
        val icons by container.serverIcons.icons.collectAsStateWithLifecycle()
        CompositionLocalProvider(LocalServerIcons provides icons) {
            Surface(color = MaterialTheme.colorScheme.background) {
                val nav = rememberNavController()
                AppNavHost(container, nav)
                TwoFactorOverlay(container)
            }
        }
    }
}

@Composable
private fun AppNavHost(container: AppContainer, nav: NavHostController) {
    val servers by container.servers.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val back: () -> Unit = { nav.popBackStack() }
    fun arg(entry: androidx.navigation.NavBackStackEntry) = entry.arguments?.getString("id").orEmpty()

    NavHost(nav, startDestination = Routes.MAIN) {
        composable(Routes.MAIN) {
            when {
                !servers.loaded -> LoadingScreen()
                servers.servers.isEmpty() -> WelcomeRoute(container, onAccount = { nav.navigate("account/${it.name}") })
                else -> {
                    val offlineMode by container.preferences.offlineMode.collectAsState(initial = false)
                    TabsScreen(
                        servers = servers.servers,
                        activeId = servers.activeId,
                        offlineMode = offlineMode,
                        onSelectServer = { id, library -> scope.launch { container.servers.select(id, library) } },
                        onOfflineMode = { on -> scope.launch { container.preferences.setOfflineMode(on) } },
                        onSettings = { nav.navigate(Routes.SETTINGS) },
                    )
                }
            }
        }
        composable(Routes.ACCOUNT) { entry ->
            val mode = AccountMode.valueOf(entry.arguments?.getString("mode") ?: AccountMode.SIGN_IN.name)
            val vm = viewModel { OwnAudioAccountViewModel(mode, container.servers, container.log, GoogleSignIn.isAvailable) }
            val state by vm.state.collectAsStateWithLifecycle()
            LaunchedEffect(state.done) { if (state.done) nav.popBackStack(Routes.MAIN, inclusive = false) }
            val google = rememberGoogle(onToken = vm::signInWithGoogle, onFailed = vm::googleFailed)
            OwnAudioAccountContent(
                state = state,
                showGoogle = GoogleSignIn.isAvailable,
                onCancel = back,
                onName = vm::setName,
                onEmail = vm::setEmail,
                onPassword = vm::setPassword,
                onSubmit = vm::submit,
                onGoogle = { state.googleClientId?.let(google) },
            )
        }
        composable(Routes.SETTINGS) {
            val context = LocalContext.current
            SettingsContent(
                ui = SettingsUi(
                    servers = servers.servers,
                    activeId = servers.activeId,
                    accent = accent(container),
                    language = AppLanguage.current(),
                    version = "${container.versionName} (${container.versionCode})",
                    refused = servers.servers.map { it.id }.filter(servers::isRefused).toSet(),
                ),
                onBack = back,
                onOpenServer = { nav.navigate("server/$it") },
                onRemoveServer = { id -> container.servers.scopeLaunch { container.servers.remove(id) } },
                onAddServer = { nav.navigate(Routes.ADD_SERVER) },
                onAccent = { choice -> scope.launch { container.preferences.setAccent(choice) } },
                onLanguage = { AppLanguage.apply(it); context.findActivity()?.recreate() },
                onLicences = { nav.navigate(Routes.LICENCES) },
                onLogs = { nav.navigate(Routes.LOGS) },
            )
        }
        composable(Routes.ADD_SERVER) {
            val vm = viewModel { ServerFormViewModel(container.servers, container.log, GoogleSignIn.isAvailable) }
            val state by vm.state.collectAsStateWithLifecycle()
            LaunchedEffect(state.done) { if (state.done) nav.popBackStack() }
            val google = rememberGoogle(onToken = vm::signInWithGoogle, onFailed = vm::googleFailed)
            AddServerContent(
                state = state,
                showGoogle = GoogleSignIn.isAvailable,
                onCancel = back,
                onAddress = vm::setAddress,
                onUsername = vm::setUsername,
                onPassword = vm::setPassword,
                onName = vm::setName,
                onConnect = vm::connect,
                onGoogle = { state.googleClientId?.let(google) },
            )
        }
        composable(Routes.SERVER) { entry ->
            val id = arg(entry)
            val vm = viewModel { ServerDetailViewModel(id, container.servers, ServerDetector(container.http, container.userAgent, container.log), container.log) }
            val detail by vm.state.collectAsStateWithLifecycle()
            LaunchedEffect(detail.gone) { if (detail.gone) nav.popBackStack() }
            val record = servers.server(id)
            if (record == null) {
                LaunchedEffect(Unit) { nav.popBackStack() }
                return@composable
            }
            ServerDetailContent(
                ui = ServerPageUi(
                    server = record,
                    isActive = record.id == servers.activeId,
                    connection = servers.connections[id],
                    check = servers.checks[id],
                    checking = id in servers.checking,
                    refused = servers.isRefused(id),
                    detail = detail,
                    offline = container.offline.isOffline,
                ),
                onBack = back,
                onUse = { vm.use() },
                onCheckNow = { vm.checkNow() },
                onSignInAgain = { nav.navigate("sign-in-again/$id") },
                onDeleteAccount = vm::deleteAccount,
                onFamily = { nav.navigate("family/$id") },
                onDevices = { nav.navigate("devices/$id") },
                onListening = { nav.navigate("listening/$id") },
                onRename = { vm.rename(it) },
                onLibrary = { vm.setLibrary(it) },
                onConnection = { vm.setConnection(it) },
                onAddAddress = { vm.addAddress(it) },
                onRemoveAddress = { vm.removeAddress(it) },
                onRemove = vm::remove,
                onDismissError = vm::dismissErrors,
            )
        }
        composable(Routes.SIGN_IN_AGAIN) { entry ->
            val id = arg(entry)
            val vm = viewModel { SignInAgainViewModel(id, container.servers, container.log, GoogleSignIn.isAvailable) }
            val state by vm.state.collectAsStateWithLifecycle()
            LaunchedEffect(state.done) { if (state.done) nav.popBackStack() }
            val google = rememberGoogle(onToken = vm::signInWithGoogle, onFailed = vm::googleFailed)
            SignInAgainContent(
                state = state,
                showGoogle = GoogleSignIn.isAvailable,
                onCancel = back,
                onUsername = vm::setUsername,
                onPassword = vm::setPassword,
                onSubmit = vm::submit,
                onGoogle = { state.googleClientId?.let(google) },
            )
        }
        composable(Routes.FAMILY) { entry ->
            val id = arg(entry)
            val vm = viewModel { HouseholdViewModel({ container.servers.client(id) }) }
            val state by vm.state.collectAsStateWithLifecycle()
            HouseholdContent(
                state = state,
                offline = container.offline.isOffline,
                onBack = back,
                onRetry = vm::load,
                onInvite = vm::createInvite,
                onShowInvite = vm::show,
                onWithdraw = vm::withdraw,
                onJoin = vm::join,
                onDismissError = vm::dismissError,
            )
        }
        composable(Routes.DEVICES) { entry ->
            val id = arg(entry)
            val vm = viewModel { DevicesViewModel({ container.servers.client(id) }, { container.servers.signOutEverywhere(id) }) }
            val state by vm.state.collectAsStateWithLifecycle()
            LaunchedEffect(state.signedOutEverywhere) { if (state.signedOutEverywhere) nav.popBackStack() }
            DevicesContent(
                state = state,
                onBack = back,
                onRetry = vm::load,
                onSignOut = vm::signOut,
                onSignOutEverywhere = vm::signOutEverywhere,
                onDismissError = vm::dismissError,
            )
        }
        composable(Routes.LISTENING) { entry ->
            val id = arg(entry)
            val vm = viewModel { ListeningViewModel({ container.servers.client(id) }) }
            val state by vm.state.collectAsStateWithLifecycle()
            ListeningContent(state, onBack = back, onRange = vm::setRange, onRetry = vm::load, onShared = vm::setShared, onDismissError = vm::dismissError)
        }
        composable(Routes.LOGS) {
            val context = LocalContext.current
            val lines by container.log.lines.collectAsStateWithLifecycle()
            LogsContent(lines, onBack = back, onShare = { shareLog(context, container.log, container.logHeader) })
        }
        composable(Routes.LICENCES) { LicencesContent(onBack = back) }
    }
}

@Composable
private fun accent(container: AppContainer): AccentChoice {
    val value by container.preferences.accent.collectAsState(initial = AccentChoice.APP)
    return value
}

@Composable
private fun WelcomeRoute(container: AppContainer, onAccount: (AccountMode) -> Unit) {
    val vm = viewModel { ServerFormViewModel(container.servers, container.log, GoogleSignIn.isAvailable) }
    val state by vm.state.collectAsStateWithLifecycle()
    val google = rememberGoogle(onToken = vm::signInWithGoogle, onFailed = vm::googleFailed)
    OnboardingContent(
        state = state,
        showGoogle = GoogleSignIn.isAvailable,
        onCreateAccount = { onAccount(AccountMode.SIGN_UP) },
        onSignIn = { onAccount(AccountMode.SIGN_IN) },
        onTryDemo = vm::tryDemo,
        onAddress = vm::setAddress,
        onUsername = vm::setUsername,
        onPassword = vm::setPassword,
        onName = vm::setName,
        onConnect = vm::connect,
        onGoogle = { state.googleClientId?.let(google) },
    )
}

/** Asks Google for an id token made for [String] (the server's web client id). */
@Composable
private fun rememberGoogle(onToken: (String) -> Unit, onFailed: (String) -> Unit): (String) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return { clientId ->
        scope.launch {
            when (val result = GoogleSignIn.idToken(context.findActivity() ?: context, clientId)) {
                is GoogleResult.Token -> onToken(result.idToken)
                is GoogleResult.Failed -> onFailed(result.reason)
                GoogleResult.Cancelled -> Unit
            }
        }
    }
}

/** The second step of a sign-in, over whichever page started it. */
@Composable
private fun TwoFactorOverlay(container: AppContainer) {
    val servers by container.servers.state.collectAsStateWithLifecycle()
    val pending = servers.pendingTwoFactor ?: return
    Dialog(onDismissRequest = { container.servers.cancelTwoFactor() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier, color = MaterialTheme.colorScheme.background) {
            val vm = viewModel(key = pending.mfaToken) { TwoFactorViewModel(container.servers) }
            val state by vm.state.collectAsStateWithLifecycle()
            TwoFactorContent(state, onCode = vm::setCode, onCheck = vm::check, onCancel = vm::cancel)
        }
    }
}
