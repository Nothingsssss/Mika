package com.wangzan101.mika.wechat

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import com.wangzan101.mika.BuildConfig
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.core.action.ConfirmChoice
import com.wangzan101.mika.core.net.NetProxy
import com.wangzan101.mika.core.run.ChannelGateway
import com.wangzan101.mika.core.run.ChannelPeer
import com.wangzan101.mika.core.run.MemoryOwnerIdentity
import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.core.wechat.WechatAccount
import com.wangzan101.mika.core.wechat.WechatApi
import com.wangzan101.mika.core.wechat.WechatHttpException
import com.wangzan101.mika.core.wechat.WechatApiException
import com.wangzan101.mika.core.wechat.wechatServerUrl
import java.util.concurrent.atomic.AtomicReference
import java.net.SocketTimeoutException
import com.wangzan101.mika.core.wechat.WechatQrStart
import com.wangzan101.mika.settings.FeatureFlags
import com.wangzan101.mika.task.TaskService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import kotlin.math.min

enum class WechatLoginPhase { Waiting, Scanned, VerifyCode }

sealed interface WechatConnectionState {
    data object Off : WechatConnectionState
    data object NeedsBinding : WechatConnectionState
    data object Connecting : WechatConnectionState
    data object Connected : WechatConnectionState
    data class Failed(val message: String) : WechatConnectionState
}

/** 单账号私聊运行时。监听存活由独立前台服务负责，文字和媒体任务仍只进 TaskService。 */
class WechatRuntime(
    private val context: Context,
    val store: WechatStore,
    client: OkHttpClient? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = client ?: NetProxy.shared.client(OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).writeTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS))
    private val api = WechatApi(http, BuildConfig.VERSION_NAME)
    private val files = WechatFiles(context, store, api, com.wangzan101.mika.core.wechat.WechatMediaApi(http, api))
    private val owners = MemoryOwnerIdentity()
    private val app get() = context.applicationContext as MikaApp
    private val gateway = ChannelGateway(owners,
        { threadId -> app.taskState.run(threadId)?.activeAsk },
        object : ChannelGateway.Route {
            override suspend fun newTask(peer: ChannelPeer, messageId: String, text: String) =
                TaskService.startChannel(context, peer, messageId, text)

            override suspend fun answer(threadId: String, askId: String, text: String): Boolean =
                app.taskState.run(threadId)?.answerFromChannel(text, askId) == true

            override suspend fun confirm(threadId: String, askId: String, choice: ConfirmChoice) = false
            override suspend fun stop(runId: RunId) = TaskService.cancel(context, runId.value)
            override suspend fun cancelAsk(threadId: String) {
                val run = app.taskState.run(threadId) ?: return
                run.activeAsk?.askId?.let(run::cancelAsk)
            }
        })

    val channel = WechatChannel(store, api,
        activeAsk = { threadId -> app.taskState.run(threadId)?.activeAsk },
        activeRun = { threadId -> app.taskState.run(threadId)?.let { RunId(it.key) } },
        prepareMedia = files::prepare, workScope = scope, sendFile = files::send)
    private var registration: AutoCloseable? = null
    private var monitor: Job? = null
    private val network = WechatNetwork(context) { restartMonitor() }
    private val _state = MutableStateFlow<WechatConnectionState>(
        if (store.account() == null) WechatConnectionState.NeedsBinding else WechatConnectionState.Off)
    val state: StateFlow<WechatConnectionState> = _state.asStateFlow()
    private val _loginPhase = MutableStateFlow(WechatLoginPhase.Waiting)
    val loginPhase = _loginPhase.asStateFlow()
    private val verifyCode = AtomicReference<String?>(null)
    fun submitVerifyCode(value: String) {
        require(value.matches(Regex("[0-9]{4,12}"))) { "Enter the verification code shown in WeChat" }
        verifyCode.set(value)
        _loginPhase.value = WechatLoginPhase.Scanned
    }

    fun channelFor(id: String) = channel.takeIf { id == WechatChannel.ID }
    fun acknowledge(messageId: String) = store.acknowledge(messageId)

    @Synchronized fun sync(): Boolean {
        if (FeatureFlags.get(context).quitRequested()) return false
        val account = store.account()
        if (!store.enabled() || account == null) {
            _state.value = WechatConnectionState.NeedsBinding.takeIf { account == null } ?: WechatConnectionState.Off
            return false
        }
        if (registration == null) {
            owners.bind(ChannelPeer(WechatChannel.ID, account.ownerId, ""))
            registration = gateway.start(channel)
        }
        network.start()
        if (monitor?.isActive != true) {
            _state.value = WechatConnectionState.Connecting
            monitor = scope.launch { monitorLoop() }
        }
        return true
    }

    @Synchronized fun stop() {
        monitor?.cancel()
        monitor = null
        registration?.close()
        registration = null
        network.stop()
        _state.value = if (store.account() == null) WechatConnectionState.NeedsBinding else WechatConnectionState.Off
    }

    fun ensureRunning() {
        if (FeatureFlags.get(context).quitRequested() || !store.enabled() || store.account() == null) return
        _state.value = WechatConnectionState.Connecting
        runCatching { context.startForegroundService(Intent(context, WechatService::class.java)) }
            .onFailure { _state.value = WechatConnectionState.Failed(it.message ?: it::class.simpleName.orEmpty()) }
    }

    fun setEnabled(enabled: Boolean) {
        if (enabled) check(store.account() != null) { "Bind WeChat before enabling it" }
        store.setEnabled(enabled)
        if (enabled) ensureRunning() else {
            context.stopService(Intent(context, WechatService::class.java))
            stop()
        }
    }

    suspend fun startLogin(): WechatQrStart = withContext(Dispatchers.IO) {
        _loginPhase.value = WechatLoginPhase.Waiting
        verifyCode.set(null)
        api.startLogin()
    }

    /** 调用方取消时只停止本次扫码等待，不影响已绑定账号的监听。 */
    suspend fun awaitLogin(qrcode: String): WechatAccount? {
        require(qrcode.isNotBlank()) { "Missing QR code key" }
        val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(5)
        var baseUrl = "https://ilinkai.weixin.qq.com"
        while (currentCoroutineContext().isActive && System.nanoTime() < deadline) {
            if (_loginPhase.value == WechatLoginPhase.VerifyCode && verifyCode.get() == null) {
                delay(250)
                continue
            }
            val status = try { api.pollLogin(qrcode, baseUrl, verifyCode.getAndSet(null)) }
            catch (e: CancellationException) { throw e }
            catch (e: WechatApiException) { throw e }
            catch (_: java.io.IOException) { delay(2_000); continue }
            when (status.status) {
                "wait" -> Unit
                "scaned" -> _loginPhase.value = WechatLoginPhase.Scanned
                "need_verifycode" -> _loginPhase.value = WechatLoginPhase.VerifyCode
                "scaned_but_redirect" -> {
                    baseUrl = wechatServerUrl(requireNotNull(status.redirectHost))
                    _loginPhase.value = WechatLoginPhase.Scanned
                }
                "verify_code_blocked" -> error("微信验证失败次数过多，请刷新二维码。")
                "binded_redirect" -> error("微信返回已绑定状态，请先在微信解除旧绑定后重试。")
                "expired" -> return null
                "confirmed" -> Unit
                else -> error("微信返回不支持的授权状态：${status.status.take(40)}")
            }
            status.account()?.let { account ->
                wechatServerUrl(account.baseUrl)
                store.account()?.let { old -> owners.unbind(ChannelPeer(WechatChannel.ID, old.ownerId, "")) }
                store.saveAccount(account)
                store.setEnabled(true)
                owners.bind(ChannelPeer(WechatChannel.ID, account.ownerId, ""))
                // 旧账号的长轮询必须在这里取消；新请求从已清空的账号游标开始。
                restartMonitor()
                ensureRunning()
                return account
            }
            if (status.status == "expired") return null
            if (status.status == "confirmed") throw IllegalStateException("Incomplete confirmed login response")
            delay(1_000)
        }
        return null
    }

    fun unbind() {
        context.stopService(Intent(context, WechatService::class.java))
        store.account()?.let { old -> owners.unbind(ChannelPeer(WechatChannel.ID, old.ownerId, "")) }
        stop()
        store.clear()
        _state.value = WechatConnectionState.NeedsBinding
    }

    @Synchronized private fun restartMonitor() {
        if (registration == null || !store.enabled()) return
        monitor?.cancel()
        monitor = scope.launch { monitorLoop() }
    }

    private suspend fun monitorLoop() {
        var failures = 0
        while (currentCoroutineContext().isActive && store.enabled()) {
            try {
                val remaining = store.suspendedUntilMs() - System.currentTimeMillis()
                if (remaining > 0) {
                    _state.value = WechatConnectionState.Failed("微信会话暂不可用，暂停后自动重试。")
                    delay(remaining.coerceAtMost(60_000))
                    continue
                }
                channel.pollOnce()
                failures = 0
                _state.value = WechatConnectionState.Connected
                delay(250) // Do not spin if a proxy or server returns empty polls immediately.
            } catch (e: CancellationException) {
                throw e
            } catch (_: SocketTimeoutException) {
                _state.value = WechatConnectionState.Connected
                delay(250)
            } catch (e: WechatApiException) {
                if (e.code == -14) {
                    store.suspendUntil(System.currentTimeMillis() + TimeUnit.HOURS.toMillis(1))
                } else {
                    failures++
                    _state.value = WechatConnectionState.Failed("WeChat API ${e.code}")
                    delay(backoff(failures))
                }
            } catch (e: WechatHttpException) {
                if (e.httpCode == 401 || e.httpCode == 403) {
                    store.setEnabled(false)
                    _state.value = WechatConnectionState.NeedsBinding
                    context.stopService(Intent(context, WechatService::class.java))
                    return
                }
                failures++
                _state.value = WechatConnectionState.Failed(e.message.orEmpty())
                delay(backoff(failures))
            } catch (e: Exception) {
                failures++
                Log.w(TAG, "WeChat polling failed", e)
                _state.value = WechatConnectionState.Failed(e.message ?: e::class.simpleName.orEmpty())
                delay(backoff(failures))
            }
        }
    }

    private fun backoff(failures: Int) = min(30_000L, 1_000L * (1L shl (failures - 1).coerceAtMost(5)))

    companion object { private const val TAG = "MikaWechat" }
}

private class WechatNetwork(context: Context, private val changed: () -> Unit) {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private var started = false
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = changed()
        override fun onLost(network: Network) = changed()
    }

    @Synchronized fun start() {
        if (started) return
        runCatching { manager.registerDefaultNetworkCallback(callback) }.onSuccess { started = true }
    }

    @Synchronized fun stop() {
        if (!started) return
        runCatching { manager.unregisterNetworkCallback(callback) }
        started = false
    }
}
