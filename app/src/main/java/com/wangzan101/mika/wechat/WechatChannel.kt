package com.wangzan101.mika.wechat

import android.util.Log
import com.wangzan101.mika.core.run.Channel
import com.wangzan101.mika.core.run.ChannelInput
import com.wangzan101.mika.core.run.ChannelInputSink
import com.wangzan101.mika.core.run.ChannelPeer
import com.wangzan101.mika.core.run.Delivery
import com.wangzan101.mika.core.run.SendOutcome
import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.core.wechat.WechatApi
import com.wangzan101.mika.core.wechat.WechatApiException
import com.wangzan101.mika.core.wechat.WechatUpdates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel as WorkQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

/** 微信只适配收发；主人校验、提问路由和任务执行都在通用渠道层。 */
class WechatChannel(
    private val store: WechatStore,
    private val api: WechatApi,
    private val activeAsk: (String) -> Delivery.Ask? = { null },
    private val activeRun: (String) -> RunId? = { null },
    private val prepareMedia: suspend (PendingWechatMessage) -> String = { it.text },
    private val workScope: CoroutineScope? = null,
    private val sendFile: suspend (ChannelPeer, Delivery.Result) -> Unit = { _, _ -> error("Media sender unavailable") },
    private val log: (String) -> Unit = { Log.i(TAG, it) },
) : Channel {
    override val id = ID
    override val confirms = false
    @Volatile private var sink: ChannelInputSink? = null
    private val pollLock = Mutex()
    private var worker: Job? = null
    private var queue: WorkQueue<PendingWechatMessage>? = null
    private val scheduled = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    override fun isAlive(peer: ChannelPeer): Boolean {
        val account = store.account()
        return peer.channelId == id && store.enabled() && account != null && peer.peerId == account.ownerId
    }

    override fun start(sink: ChannelInputSink): AutoCloseable {
        this.sink = sink
        if (workScope != null) {
            val work = WorkQueue<PendingWechatMessage>(WorkQueue.UNLIMITED)
            queue = work
            worker = workScope.launch {
                for (pending in work) try {
                    if (this@WechatChannel.sink === sink && isAlive(pending.peer)) dispatch(sink, pending)
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { log("Incoming message registration failed; it remains pending") }
                finally { scheduled.remove(pending.messageId) }
            }
        }
        return AutoCloseable { if (this.sink === sink) {
            this.sink = null
            worker?.cancel(); worker = null
            queue?.close(); queue = null
            scheduled.clear()
        } }
    }

    override fun acknowledge(messageId: String) = store.acknowledge(messageId)

    override suspend fun send(peer: ChannelPeer, delivery: Delivery): SendOutcome {
        if (peer.channelId != id || !isAlive(peer)) return SendOutcome.Failed
        val account = store.account() ?: return SendOutcome.Failed
        val text = when (delivery) {
            is Delivery.Ask -> delivery.text
            is Delivery.Result -> delivery.text
            is Delivery.Notice -> delivery.text
            is Delivery.Progress -> delivery.text
        }
        return withContext(Dispatchers.IO) {
            val started = System.nanoTime()
            val outcome = try {
                if (delivery is Delivery.Result && delivery.file != null) sendFile(peer, delivery)
                else api.sendText(account, peer.peerId, delivery.id, text, store.contextToken(peer.peerId))
                SendOutcome.Delivered
            } catch (e: CancellationException) {
                throw e
            } catch (e: WechatApiException) {
                log("Send rejected: API code ${e.code}")
                SendOutcome.Failed
            } catch (_: IOException) {
                SendOutcome.Uncertain
            } catch (_: Exception) {
                SendOutcome.Failed
            }
            log("${delivery::class.simpleName} send $outcome in ${(System.nanoTime() - started) / 1_000_000} ms")
            outcome
        }
    }

    /** 先补交上次未登记的消息，再取新消息；任务登记处做最终幂等。 */
    suspend fun pollOnce(): WechatUpdates? = pollLock.withLock {
        val input = sink ?: return@withLock null
        val account = store.account()?.takeIf { store.enabled() } ?: return@withLock null
        store.pending().forEach { pending ->
            if (store.account() != account || pending.peer.peerId != account.ownerId || sink !== input) return@withLock null
            schedule(input, pending)
        }
        val updates = api.updatesCancellable(account, store.cursor())
        // 换绑或停止后，旧请求的结果不能再写入新会话，也不能推进新账号的游标。
        if (store.account() != account || sink !== input || !store.enabled()) return@withLock null
        updates.msgs.forEach { message ->
            val owner = account.ownerId
            if (message.fromUserId != owner) return@forEach
            store.rememberContext(owner, message.contextToken)
            if (message.messageType != 1) return@forEach
            val messageId = message.messageId?.takeIf { it.isNotBlank() } ?: return@forEach
            val media = message.items.filter { (it.type == 2 && it.image != null) || (it.type == 3 && it.voice?.media != null) }
            val text = message.userText().orEmpty()
            if (text.isBlank() && media.isEmpty()) return@forEach
            val peer = store.peer(owner)
            if (store.account() != account || sink !== input || !store.enabled()) return@withLock null
            val askId = activeAsk(peer.threadId)
                ?.takeIf { it.kind == Delivery.Ask.Kind.Input }
                ?.askId
            val stop = text.trim().lowercase() in setOf("停止", "停止任务", "/stop")
            val target = if (stop) activeRun(peer.threadId)?.value else null
            if (store.journal(messageId, peer, text, askId, stop, target, media)) {
                schedule(input, PendingWechatMessage(messageId, peer, text, askId, stop, target, media))
            }
        }
        if (updates.cursor.isNotEmpty() && store.account() == account && store.enabled()) store.saveCursor(updates.cursor)
        updates
    }

    private suspend fun schedule(input: ChannelInputSink, pending: PendingWechatMessage) {
        // Cancellation must not wait behind a slow CDN download or transcription.
        if (pending.stopRequested) { dispatch(input, pending); return }
        val work = queue
        if (work == null) { dispatch(input, pending); return }
        if (scheduled.add(pending.messageId) && !work.trySend(pending).isSuccess) scheduled.remove(pending.messageId)
    }

    private suspend fun dispatch(input: ChannelInputSink, pending: PendingWechatMessage) {
        if (!pending.stopRequested) {
            val text = if (pending.media.isEmpty()) pending.text else try {
                store.prepared(pending.messageId) ?: prepareMedia(pending).also { store.prepare(pending.messageId, it) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                log("Media preparation failed: ${e::class.simpleName}")
                send(pending.peer, Delivery.Notice("media-error:${pending.messageId}", pending.peer.threadId,
                    if (e is WechatMediaInputException) e.message.orEmpty() else
                        "媒体处理失败，请重发图片或语音。单条消息最多 8 个附件，每个不超过 20 MB。"))
                acknowledge(pending.messageId)
                return
            }
            if (!isAlive(pending.peer)) return
            input.receive(ChannelInput.Text(pending.peer, pending.messageId, text, pending.replyToAskId))
            return
        }
        pending.stopRunId?.let { input.receive(ChannelInput.Stop(pending.peer, RunId(it))) }
        send(pending.peer, Delivery.Notice("stopped:${pending.messageId}", pending.peer.threadId,
            if (pending.stopRunId == null) "当前没有正在运行的任务。" else "已请求停止当前任务。"))
        acknowledge(pending.messageId)
    }

    companion object {
        const val ID = "wechat"
        private const val TAG = "MikaWechat"
    }
}
