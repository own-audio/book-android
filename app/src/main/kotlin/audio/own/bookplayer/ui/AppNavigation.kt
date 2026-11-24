package audio.own.bookplayer.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import audio.own.kit.ownaudio.familyBilling
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import android.content.ActivityNotFoundException
import androidx.core.net.toUri
import androidx.browser.customtabs.CustomTabsIntent
import audio.own.bookplayer.apple.AppleSignIn
import audio.own.bookplayer.apple.AppleStatus
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
import audio.own.bookplayer.player.PlayerActions
import audio.own.bookplayer.player.PlayerContent
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
    const val PLAYER = "player"
    const val DOWNLOADS = "downloads"
    const val IMPORT = "import/{id}"
    const val NARRATE = "narrate/{id}"
}

/** The whole app: the theme with the chosen accent, the pages, and the two-factor step over them. */
@Composable
fun AppRoot(container: AppContainer) {
    val accent by container.preferences.accent.collectAsState(initial = AccentChoice.APP)
    OwnAudioTheme(accent = accent) {
        val icons by container.serverIcons.icons.collectAsStateWithLifecycle()
        CompositionLocalProvider(LocalServerIcons provides icons, audio.own.bookplayer.library.LocalCoverRefresher provides container.covers) {
            Surface(color = MaterialTheme.colorScheme.background) {
                val nav = rememberNavController()
                AppNavHost(container, nav)
                TwoFactorOverlay(container)
                AppleBrowser(container.appleSignIn)
                ResumeQuestionOverlay(container)
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
    OpenPlayerFromNotification(container) { nav.navigate(Routes.PLAYER) { launchSingleTop = true } }

    NavHost(nav, startDestination = Routes.MAIN) {
        composable(Routes.MAIN) {
            when {
                !servers.loaded -> LoadingScreen()
                servers.servers.isEmpty() -> WelcomeRoute(container, onAccount = { nav.navigate("account/${it.name}") })
                else -> LibraryTabs(
                    container,
                    onOpenPlayer = { nav.navigate(Routes.PLAYER) },
                    onSettings = { nav.navigate(Routes.SETTINGS) },
                    onAddBook = { id, narrate -> nav.navigate(if (narrate) "narrate/$id" else "import/$id") },
                )
            }
        }
        composable(Routes.PLAYER) { PlayerRoute(container, onClose = back) }
        composable(Routes.ACCOUNT) { entry ->
            val mode = AccountMode.valueOf(entry.arguments?.getString("mode") ?: AccountMode.SIGN_IN.name)
            val vm = viewModel { OwnAudioAccountViewModel(mode, container.servers, container.log, GoogleSignIn.isAvailable, container.appleSignIn) }
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
                onApple = vm::signInWithApple,
            )
        }
        composable(Routes.SETTINGS) {
            val context = LocalContext.current
            val playbackPrefs by container.playbackSettings.prefs.collectAsStateWithLifecycle()
            val downloads by container.downloads.state.collectAsStateWithLifecycle()
            val offlineMode by container.preferences.offlineMode.collectAsStateWithLifecycle(initialValue = false)
            SettingsContent(
                ui = SettingsUi(
                    servers = servers.servers,
                    activeId = servers.activeId,
                    accent = accent(container),
                    language = AppLanguage.current(),
                    version = "${container.versionName} (${container.versionCode})",
                    refused = servers.servers.map { it.id }.filter(servers::isRefused).toSet(),
                    playback = playbackPrefs,
                    downloadedBytes = downloads.totalBytes,
                    offlineMode = offlineMode,
                    hasOwnAudio = servers.servers.any { it.kind == audio.own.bookplayer.servers.ServerKinds.OWN_AUDIO },
                ),
                onPlayback = { change -> scope.launch { container.playbackSettings.update(change) } },
                onDownloads = { nav.navigate(Routes.DOWNLOADS) },
                onOfflineMode = { on -> scope.launch { container.preferences.setOfflineMode(on) } },
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
            val vm = viewModel { ServerFormViewModel(container.servers, container.log, GoogleSignIn.isAvailable, apple = container.appleSignIn) }
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
                onApple = vm::signInWithApple,
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
            val vm = viewModel { SignInAgainViewModel(id, container.servers, container.log, GoogleSignIn.isAvailable, container.appleSignIn) }
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
                onApple = vm::signInWithApple,
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
        composable(Routes.IMPORT) { entry -> ImportRoute(container, arg(entry), onClose = back) }
        composable(Routes.NARRATE) { entry -> NarrateRoute(container, arg(entry), onClose = back) }
        composable(Routes.DOWNLOADS) {
            val snapshot by container.downloads.state.collectAsStateWithLifecycle()
            CompositionLocalProvider(audio.own.bookplayer.downloads.LocalBookDownloads provides snapshot) {
                audio.own.bookplayer.downloads.DownloadsContent(
                    snapshot = snapshot,
                    servers = servers.servers,
                    // Settings has no book pages under it: a tap plays the book.
                    onOpen = { entry -> container.playback.play(entry.serverId, entry.book); nav.navigate(Routes.PLAYER) },
                    onRemove = { entry -> container.scope.launch { container.downloads.remove(entry.compositeId) } },
                    onRetryFailed = { container.scope.launch { container.downloads.retryFailed() } },
                    onRemoveAll = { container.scope.launch { container.downloads.removeAll() } },
                    onBack = back,
                )
            }
        }
    }
}

@Composable
private fun ImportRoute(container: AppContainer, serverId: String, onClose: () -> Unit) {
    val vm = viewModel {
        audio.own.bookplayer.imports.ImportBookViewModel(serverId, container.importReader, container.imports) {
            container.network.state.value.let { it.connected && it.metered }
        }
    }
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.done) { if (state.done) onClose() }
    val pickers = audio.own.bookplayer.imports.rememberImportPickers(onDocuments = vm::addDocuments, onFolder = vm::addFolder, onCover = vm::setCover)
    audio.own.bookplayer.imports.ImportBookContent(
        state,
        audio.own.bookplayer.imports.ImportFormActions(
            onCancel = onClose,
            onPickFiles = pickers.audioFiles,
            onPickFolder = pickers.folder,
            onPickCover = pickers.cover,
            onMove = vm::move,
            onRemove = vm::remove,
            onTitle = vm::setTitle,
            onAuthor = vm::setAuthor,
            onNarrator = vm::setNarrator,
            onVisibility = vm::setVisibility,
            onAdd = vm::addToLibrary,
            onConfirmMobile = vm::start,
            onDismissMobile = vm::dismissMobile,
        ),
    )
}

@Composable
private fun NarrateRoute(container: AppContainer, serverId: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val vm = viewModel {
        audio.own.bookplayer.narration.NarrateBookViewModel(
            serverId = serverId,
            narrator = container.sources.source(serverId) as? audio.own.book.library.BookNarrator,
            credit = {
                container.servers.client(serverId)?.let { client ->
                    runCatching { client.familyBilling() }.getOrNull()?.let {
                        audio.own.bookplayer.narration.NarrationCredit(java.math.BigDecimal(it.availableMicro).movePointLeft(6), it.pricing.currency, it.depleted)
                    }
                }
            },
            copyIn = { uri -> audio.own.bookplayer.narration.copyEbook(context.applicationContext, uri) },
            narrations = container.narrations,
        )
    }
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.done) { if (state.done) onClose() }
    val pick = audio.own.bookplayer.narration.rememberEbookPicker(vm::take)
    audio.own.bookplayer.narration.NarrateBookContent(
        state,
        audio.own.bookplayer.narration.NarrateActions(
            onCancel = onClose,
            onPick = pick,
            onTitle = vm::setTitle,
            onSourceLanguage = vm::setSourceLanguage,
            onTargetLanguage = vm::setTargetLanguage,
            onVoice = vm::setVoice,
            onOutput = vm::setOutput,
            onPrice = vm::price,
            onStart = vm::start,
        ),
    )
}

/** A tap on the media notification opens the player. */
@Composable
private fun OpenPlayerFromNotification(container: AppContainer, open: () -> Unit) {
    val context = LocalContext.current
    androidx.compose.runtime.DisposableEffect(Unit) {
        val activity = context.findActivity() as? androidx.activity.ComponentActivity
        fun handle(intent: android.content.Intent?) {
            if (intent?.getBooleanExtra(audio.own.bookplayer.MainActivity.EXTRA_OPEN_PLAYER, false) != true) return
            intent.removeExtra(audio.own.bookplayer.MainActivity.EXTRA_OPEN_PLAYER)
            if (container.playback.state.value.hasBook) open()
        }
        handle(activity?.intent)
        val listener = androidx.core.util.Consumer<android.content.Intent> { handle(it) }
        activity?.addOnNewIntentListener(listener)
        onDispose { activity?.removeOnNewIntentListener(listener) }
    }
}

@Composable
private fun accent(container: AppContainer): AccentChoice {
    val value by container.preferences.accent.collectAsState(initial = AccentChoice.APP)
    return value
}

@Composable
private fun WelcomeRoute(container: AppContainer, onAccount: (AccountMode) -> Unit) {
    val vm = viewModel { ServerFormViewModel(container.servers, container.log, GoogleSignIn.isAvailable, apple = container.appleSignIn) }
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
        onApple = vm::signInWithApple,
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

/** The full player over everything, from the mini player. */
@Composable
private fun PlayerRoute(container: AppContainer, onClose: () -> Unit) {
    val state by container.playback.state.collectAsStateWithLifecycle()
    val playback = container.playback
    val serverId = state.serverId
    val bookId = state.bookId?.let { audio.own.book.library.BookId(it).bookId }
    val bookmarksVm = viewModel(key = "bookmarks|${state.bookId}") {
        audio.own.bookplayer.player.BookmarksViewModel(serverId?.let { container.sources.source(it) }, bookId, onChanged = container::noteBookmarksChanged) { container.offline.isOffline }
    }
    val bookmarks by bookmarksVm.state.collectAsStateWithLifecycle()
    PlayerContent(
        state = state,
        actions = PlayerActions(
            onClose = onClose,
            onStop = { playback.stop(); onClose() },
            onToggle = playback::toggle,
            onSeek = playback::seek,
            onSkipBack = playback::skipBack,
            onSkipForward = playback::skipForward,
            onPreviousChapter = playback::previousChapter,
            onNextChapter = playback::nextChapter,
            onSpeed = playback::setSpeed,
            onSleep = playback::setSleep,
            onSleepEnd = playback::setSleepEnd,
            onExtendSleep = playback::extendSleep,
            onCancelSleep = playback::cancelSleep,
            onPlayFrom = { at ->
                playback.seek(at)
                if (!playback.state.value.isPlaying) playback.toggle()
            },
        ),
        bookmarks = bookmarks,
        bookmarkActions = audio.own.bookplayer.player.BookmarkSheetActions(
            onAdd = bookmarksVm::add,
            onRename = bookmarksVm::rename,
            onDelete = bookmarksVm::delete,
            onDismissError = bookmarksVm::dismissError,
        ),
        onLoadBookmarks = bookmarksVm::load,
    )
}

/** "Continue from ‹device›?", over whatever screen started the book. */
@Composable
private fun ResumeQuestionOverlay(container: AppContainer) {
    val question by container.playbackModel.resumeQuestion.collectAsStateWithLifecycle()
    question?.let {
        audio.own.bookplayer.player.ResumeDialog(
            it,
            onOther = { container.playbackModel.answerResume(useOther = true) },
            onLocal = { container.playbackModel.answerResume(useOther = false) },
            onDismiss = container.playbackModel::dismissResume,
        )
    }
}

/** Opens Sign in with Apple's page in a Custom Tab once the server named it; the redirect brings the answer back. */
@Composable
private fun AppleBrowser(apple: AppleSignIn) {
    val status by apple.status.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val open = status as? AppleStatus.Browser
    LaunchedEffect(open) {
        if (open == null || open.opened || open.url.isEmpty()) return@LaunchedEffect
        // Marked first: the app stops as the tab opens, and coming back unanswered is a cancel.
        apple.opened()
        try {
            CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context.findActivity() ?: context, open.url.toUri())
        } catch (e: ActivityNotFoundException) {
            apple.noBrowser()
        }
    }
}
