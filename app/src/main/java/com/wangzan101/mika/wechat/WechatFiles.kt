package com.wangzan101.mika.wechat

import android.content.Context
import android.net.Uri
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.cap.sandbox.Attachments
import com.wangzan101.mika.cap.sandbox.SandboxDirs
import com.wangzan101.mika.cap.sandbox.AndroidSandbox
import com.wangzan101.mika.cap.sandbox.SandboxState
import com.wangzan101.mika.core.agent.AttachmentLines
import com.wangzan101.mika.core.run.ChannelPeer
import com.wangzan101.mika.core.run.Delivery
import com.wangzan101.mika.core.wechat.*
import com.wangzan101.mika.voice.SpeechEngineSpec
import com.wangzan101.mika.voice.Transcriber
import com.wangzan101.mika.voice.Wav
import com.wangzan101.mika.settings.ProviderStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jitsi.impl.neomedia.codec.audio.silk.WechatSilk
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

class WechatMediaInputException(message: String) : Exception(message)

/** Private attachment import and result export. Uses Mika's existing workspace file gate. */
class WechatFiles(private val context: Context, private val store: WechatStore,
    private val api: WechatApi, private val cdn: WechatMediaApi) {
    private val app get() = context.applicationContext as MikaApp

    suspend fun prepare(input: PendingWechatMessage): String = withContext(Dispatchers.IO) {
        require(input.media.size <= 8)
        val owner = store.account() ?: error("WeChat is not bound")
        require(owner.ownerId == input.peer.peerId)
        val name = MessageDigest.getInstance("SHA-256").digest(input.messageId.toByteArray())
            .take(12).joinToString("") { "%02x".format(it.toInt() and 255) }
        val dir = File(context.cacheDir, "wechat-inbound/$name").apply { mkdirs() }
        val imported = mutableListOf<Pair<String, File>>()
        val transcripts = mutableListOf<String>()
        val notes = mutableListOf<String>()
        if (input.media.any { it.type == 3 && !it.voice?.text.isNullOrBlank() })
            notes += "本条任务文字已包含微信提供的语音转写，请直接根据文字执行；WAV 附件是对应原录音，不要因为没有直接听音频而声称无法理解这条语音指令。"
        try {
            input.media.forEachIndexed { index, item ->
                val bytes = cdn.download(item)
                if (store.account() != owner || !store.enabled()) error("WeChat account changed")
                if (item.type == 2) {
                    val ext = imageExtension(bytes) ?: throw WechatMediaInputException("图片格式不受支持，请发送 JPG、PNG、GIF 或 WebP 图片。")
                    imported += "微信图片-$name-$index.$ext" to File(dir, "image-$index.$ext").apply { writeBytes(bytes) }
                } else {
                    val voice = requireNotNull(item.voice)
                    val wav = when {
                        looksLikeSilk(bytes) -> Wav.encode(WechatSilk.decode(bytes, 24000), 24000)
                        voice.encodeType == 1 -> {
                            val rate = voice.sampleRate ?: 24000
                            require(rate in 8000..48000 && (voice.bitsPerSample ?: 16) == 16 && bytes.size % 2 == 0 && bytes.size <= rate * 2 * 60)
                            Wav.encode(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().let {
                                ShortArray(it.remaining()).also(it::get)
                            }, rate)
                        }
                        bytes.take(4).toByteArray().toString(Charsets.US_ASCII) == "RIFF" -> {
                            val (pcm, rate) = pcmWav(bytes)
                            Wav.encode(pcm, rate)
                        }
                        else -> null
                    }
                    val suffix = if (wav == null) "bin" else "wav"
                    imported += "微信语音-$name-$index.$suffix" to File(dir, "voice-$index.$suffix").apply { writeBytes(wav ?: bytes) }
                    if (voice.text.isNullOrBlank()) {
                        if (wav == null) {
                            notes += "原始语音附件已收到，但当前编码不能解码且没有微信转写；不能推测内容，请提示用户在微信转成文字后发送。"
                            return@forEachIndexed
                        }
                        val spec = app.voice.spec()
                        if (spec !is SpeechEngineSpec.Provider) {
                            notes += "语音附件已收到，但微信没有提供转写，Mika 未配置可读取录音文件的服务商语音转文字模型。不能推测语音内容。若无文字指令，请告知用户在 Mika 模型设置中配置语音转文字模型，或在微信转文字后发送；用户仍可要求保存或回传本录音。"
                        } else {
                            val p = spec.provider
                            val text = Transcriber(ProviderStore.modelHttp, app.providers.clientHeaders(p))
                                .transcribe(p, app.providers.providerKey(p.id), spec.model, spec.method, wav, "zh")
                            if (text.isBlank()) notes += "语音附件已收到，但没有识别到文字，不能推测内容，请提示用户重新发送或补充文字。"
                            else transcripts += "语音转写：$text"
                        }
                    }
                }
            }
            val dirs = SandboxDirs(context)
            val sandbox = AndroidSandbox(context, SandboxState.get(context))
            val size = imported.sumOf { it.second.length() }
            require(sandbox.freeBytes() - size >= Attachments.MIN_FREE &&
                sandbox.workspaceBytes(input.peer.threadId) + size <= Attachments.WORKSPACE_MAX) { "Not enough workspace space" }
            val words = (listOf(input.text) + transcripts + notes).filter { it.isNotBlank() }.joinToString("\n")
                .ifBlank { "请查看并分析我从微信发送的图片。" }
            Attachments.withTaskFiles(imported, dirs.workspace(input.peer.threadId), dirs.gate, dirs.tree,
                { sandbox.freeBytes() >= Attachments.MIN_FREE && sandbox.workspaceBytes(input.peer.threadId) <= Attachments.WORKSPACE_MAX }) { stored ->
                android.util.Log.i("MikaWechat", "Media imported: images=${input.media.count { it.type == 2 }}, voices=${input.media.count { it.type == 3 }}, provider transcripts=${transcripts.size}")
                AttachmentLines.composeBytes(words, stored)
            }
        } finally { dir.deleteRecursively() }
    }

    suspend fun send(peer: ChannelPeer, result: Delivery.Result) = withContext(Dispatchers.IO) {
        val file = requireNotNull(result.file)
        require(file.size in 1..WechatMediaApi.MAX_BYTES.toLong())
        val uri = Uri.parse(file.uri)
        require(uri.scheme == "content" && uri.authority == "media") { "Only Mika's exported MediaStore files may be sent" }
        val bytes = context.contentResolver.openInputStream(uri)?.use(WechatMediaApi::readLimited)
            ?: error("Exported attachment is unavailable")
        require(bytes.size.toLong() == file.size) { "Exported attachment size changed" }
        val owner = store.account() ?: error("WeChat is not bound")
        require(owner.ownerId == peer.peerId && store.enabled())
        val ext = imageExtension(bytes)
        // iLink accepts VOICE requests without delivering a visible voice bubble (verified on device).
        // Use the documented file route so audio actually reaches the owner; SILK becomes playable WAV.
        val silk = ext == null && file.name.lowercase().endsWith(".silk")
        val payload = if (silk) Wav.encode(WechatSilk.decode(bytes, 24000), 24000) else bytes
        val name = if (silk) file.name.dropLast(5) + ".wav" else file.name
        val mediaType = if (ext != null) 1 else 3
        val (reference, encryptedSize) = cdn.upload(owner, peer.peerId, payload, mediaType)
        val item = when {
            ext != null -> WechatItem(type = 2, image = WechatImage(reference, midSize = encryptedSize))
            else -> WechatItem(type = 4, file = WechatFile(reference, name, payload.size.toString()))
        }
        if (store.account() != owner || !store.enabled()) error("WeChat account changed")
        api.sendItem(owner, peer.peerId, result.id, item, store.contextToken(peer.peerId))
        android.util.Log.i("MikaWechat", "Media sent: type=${item.type}, bytes=${payload.size}")
    }

    companion object {
        internal fun looksLikeSilk(bytes: ByteArray): Boolean {
            val at = if (bytes.firstOrNull() == 2.toByte()) 1 else 0
            return bytes.size >= at + 9 && bytes.copyOfRange(at, at + 9).toString(Charsets.US_ASCII) == "#!SILK_V3"
        }
        internal fun imageExtension(b: ByteArray): String? = when {
            b.size >= 8 && b[0] == 0x89.toByte() && b.copyOfRange(1,4).toString(Charsets.US_ASCII) == "PNG" -> "png"
            b.size >= 3 && b[0] == 0xff.toByte() && b[1] == 0xd8.toByte() && b[2] == 0xff.toByte() -> "jpg"
            b.size >= 6 && b.copyOfRange(0,6).toString(Charsets.US_ASCII) in listOf("GIF87a", "GIF89a") -> "gif"
            b.size >= 12 && b.copyOfRange(0,4).toString(Charsets.US_ASCII) == "RIFF" && b.copyOfRange(8,12).toString(Charsets.US_ASCII) == "WEBP" -> "webp"
            else -> null
        }
        internal fun pcmWav(bytes: ByteArray): Pair<ShortArray, Int> {
            require(bytes.size >= 44 && bytes.copyOfRange(0,4).toString(Charsets.US_ASCII) == "RIFF" &&
                bytes.copyOfRange(8,12).toString(Charsets.US_ASCII) == "WAVE") { "Invalid WAV" }
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            var at = 12; var rate = 0; var channels = 0; var bits = 0; var format = 0
            while (at + 8 <= bytes.size) {
                val id = bytes.copyOfRange(at,at+4).toString(Charsets.US_ASCII)
                val size = b.getInt(at+4)
                require(size >= 0 && size <= bytes.size - at - 8)
                if (id == "fmt ") { require(size >= 16); format = b.getShort(at+8).toInt(); channels = b.getShort(at+10).toInt(); rate = b.getInt(at+12); bits = b.getShort(at+22).toInt() }
                if (id == "data") {
                    require(format == 1 && bits == 16 && channels in 1..2 && rate in 8000..48000 && size <= rate * channels * 2 * 60)
                    val frames = size / (2 * channels)
                    val source = ShortArray(frames) { i ->
                        var total = 0
                        repeat(channels) { c -> total += b.getShort(at+8+(i*channels+c)*2).toInt() }
                        (total/channels).toShort()
                    }
                    val target = if (rate in listOf(8000,12000,16000,24000)) rate else 24000
                    val resampled = if (target == rate) source else ShortArray((frames.toLong()*target/rate).toInt()) { i -> source[(i.toLong()*rate/target).toInt().coerceAtMost(source.lastIndex)] }
                    return resampled to target
                }
                at += 8 + size + (size and 1)
            }
            error("WAV contains no PCM data")
        }
    }
}
