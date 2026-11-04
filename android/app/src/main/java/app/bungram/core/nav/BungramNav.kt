package app.bungram.core.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.bungram.AppGraph
import app.bungram.SessionState
import app.bungram.feature.auth.AuthViewModel
import app.bungram.feature.auth.LoginScreen
import app.bungram.feature.auth.SplashScreen
import app.bungram.feature.chat.ChatScreen
import app.bungram.feature.chat.ChatViewModel
import app.bungram.feature.createchat.CreateChatRoute
import app.bungram.feature.createchat.CreateMode
import app.bungram.feature.groupinfo.GroupInfoScreen
import app.bungram.feature.groupinfo.GroupInfoViewModel
import app.bungram.feature.home.HomeScreen
import app.bungram.feature.newchat.NewChatScreen
import app.bungram.feature.newchat.NewChatViewModel

object Routes {
    const val HOME = "home"
    const val CHAT = "chat/{chatId}"
    const val NEW_CHAT = "newchat"
    const val CREATE_GROUP = "create/group"
    const val CREATE_CHANNEL = "create/channel"
    const val GROUP_INFO = "info/{chatId}"
    fun chat(id: String) = "chat/$id"
    fun info(id: String) = "info/$id"
}

/**
 * Auth gate. Three states, no flicker: the stored token is checked before the first frame, so a
 * returning user never sees the login screen flash.
 */
@Composable
fun BungramNavHost() {
    val session by AppGraph.session.collectAsStateWithLifecycle()

    when (session) {
        SessionState.Loading -> SplashScreen()

        SessionState.LoggedOut -> {
            val vm: AuthViewModel = viewModel(
                factory = viewModelFactory {
                    initializer {
                        AuthViewModel(
                            api = AppGraph.api,
                            settings = AppGraph.settings,
                            tokens = AppGraph.tokens,
                            onAuthenticated = AppGraph::onLoggedIn,
                        )
                    }
                }
            )
            val state by vm.state.collectAsStateWithLifecycle()
            LoginScreen(
                state = state,
                onDetectCountry = vm::detectCountry,
                onSelectCountry = vm::selectCountry,
                onNationalChange = vm::onNationalChange,
                onCodeChange = vm::onCodeChange,
                onServerUrlChange = vm::onServerUrlChange,
                onToggleServerField = vm::toggleServerField,
                onRequestOtp = vm::requestOtp,
                onVerify = vm::verify,
                onResend = vm::resend,
                onBack = vm::back,
                onUseDevCode = vm::useDevCode,
            )
        }

        is SessionState.LoggedIn -> MainNavHost()
    }
}

@Composable
private fun MainNavHost() {
    val nav = rememberNavController()

    // Coming back from background: drain the outbox and catch up immediately.
    LaunchedEffect(Unit) { AppGraph.sync.poke() }

    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onOpenChat = { nav.navigate(Routes.chat(it)) },
                onOpenCall = { /* CallActivity / ConnectionService in phase 3 */ },
                onNewChat = { nav.navigate(Routes.NEW_CHAT) },
                onLogout = { AppGraph.logout() },
                onCreateGroup = { nav.navigate(Routes.CREATE_GROUP) },
                onCreateChannel = { nav.navigate(Routes.CREATE_CHANNEL) },
            )
        }

        composable(Routes.NEW_CHAT) {
            val vm: NewChatViewModel = viewModel(
                factory = viewModelFactory {
                    initializer { NewChatViewModel(AppGraph.api, AppGraph.repository) }
                }
            )
            val state by vm.state.collectAsStateWithLifecycle()
            NewChatScreen(
                state = state,
                onBack = { nav.popBackStack() },
                onPhoneChange = vm::onPhoneChange,
                onSearch = vm::search,
                onStart = {
                    vm.start { chatId ->
                        nav.navigate(Routes.chat(chatId)) {
                            popUpTo(Routes.NEW_CHAT) { inclusive = true }
                        }
                    }
                },
            )
        }

        composable(Routes.CREATE_GROUP) { CreateChatRoute(initialMode = CreateMode.GROUP, nav = nav) }
        composable(Routes.CREATE_CHANNEL) { CreateChatRoute(initialMode = CreateMode.CHANNEL, nav = nav) }

        composable(
            route = Routes.GROUP_INFO,
            arguments = listOf(navArgument("chatId") { type = NavType.StringType }),
        ) { entry ->
            val chatId = entry.arguments?.getString("chatId") ?: return@composable
            val vm: GroupInfoViewModel = viewModel(
                factory = viewModelFactory {
                    initializer { GroupInfoViewModel(AppGraph.api, AppGraph.repository, chatId) }
                }
            )
            val infoState by vm.state.collectAsStateWithLifecycle()
            val context = androidx.compose.ui.platform.LocalContext.current

            LaunchedEffect(infoState.closed) {
                if (infoState.closed) {
                    nav.popBackStack(Routes.HOME, inclusive = false)
                }
            }

            GroupInfoScreen(
                state = infoState,
                meId = AppGraph.tokens.userId.orEmpty(),
                onBack = { nav.popBackStack() },
                onMintInvite = vm::mintInvite,
                onRevokeInvite = vm::revokeInvite,
                onAddMember = vm::addByPhone,
                onRemoveMember = vm::remove,
                onToggleAdmin = vm::toggleAdmin,
                onRename = vm::rename,
                onLeave = vm::leave,
                onDelete = vm::delete,
                onCopyLink = { link ->
                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Bungram invite", link))
                },
            )
        }

        composable(
            route = Routes.CHAT,
            arguments = listOf(navArgument("chatId") { type = NavType.StringType }),
        ) { entry ->
            val chatId = entry.arguments?.getString("chatId") ?: return@composable
            val vm: ChatViewModel = viewModel(
                factory = viewModelFactory {
                    initializer { ChatViewModel(AppGraph.repository, AppGraph.recorder, chatId) }
                }
            )
            val state by vm.state.collectAsStateWithLifecycle()

            LaunchedEffect(chatId) { vm.markRead() }
            DisposableEffect(chatId) {
                onDispose {
                    AppGraph.repository.leaveChat()
                    AppGraph.audio.stop()
                }
            }

            ChatScreen(
                state = state,
                onBack = { nav.popBackStack() },
                onDraftChange = vm::onDraftChange,
                onSend = vm::send,
                onToggleAttachment = vm::toggleAttachmentMenu,
                onSendMedia = { uri, kind, durationMs -> vm.sendMedia(uri, kind, durationMs) },
                onStartRecording = { vm.startRecording() },
                onStopRecording = vm::stopRecording,
                onCancelRecording = vm::cancelRecording,
                onVideoCall = {},
                onVoiceCall = {},
                onClearReply = { vm.setReply(null) },
                onDismissError = vm::dismissError,
                onOpenInfo = { nav.navigate(Routes.info(chatId)) },
                onSendSticker = vm::sendSticker,
                onReply = vm::setReply,
                onDelete = vm::delete,
                stickerPacks = state.stickerPacks,
            )
        }
    }
}
