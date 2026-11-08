package com.wangzan101.mika.wechat

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.BuildConfig
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.core.run.ChannelFile
import com.wangzan101.mika.core.run.Delivery
import com.wangzan101.mika.core.wechat.WechatApi
import com.wangzan101.mika.core.wechat.WechatMediaApi
import com.wangzan101.mika.voice.Wav
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/** Explicit opt-in only: sends two labelled test attachments to the already linked owner. */
class WechatMediaLiveTest {
    @Test fun boundOwnerReceivesImageAndAudioFile() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("wechatMediaLive") == "true")
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MikaApp
        assumeTrue(app.packageName.endsWith(".dev"))
        val store = app.wechat.store
        val account = requireNotNull(store.account()) { "Bind the development app first" }
        check(store.enabled())
        val peer = store.peer(account.ownerId)
        val http = OkHttpClient.Builder().callTimeout(60, TimeUnit.SECONDS).build()
        val api = WechatApi(http, BuildConfig.VERSION_NAME)
        val cdn = WechatMediaApi(http, api)
        val files = WechatFiles(app, store, api, cdn)
        val id = "media-probe:${System.currentTimeMillis()}"
        api.sendText(account, peer.peerId, "$id:label", "媒体测试：接下来是一张 Mika 测试图和一个可播放的 WAV 音频文件（1 秒提示音）。", store.contextToken(peer.peerId))
        val bitmap = Bitmap.createBitmap(320, 160, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.rgb(30, 80, 130))
            drawText("Mika media test", 24f, 90f, Paint().apply { color = Color.WHITE; textSize = 32f })
        }
        val png = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        val rate = 24000
        val tone = ShortArray(rate) { (kotlin.math.sin(it * 2.0 * Math.PI * 440 / rate) * 3000).toInt().toShort() }
        listOf("mika-media-test.png" to png, "mika-media-test.wav" to Wav.encode(tone, rate)).forEachIndexed { index, (name, bytes) ->
            val uri = requireNotNull(app.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, if (index == 0) "image/png" else "audio/wav")
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/Mika")
            }))
            try {
                requireNotNull(app.contentResolver.openOutputStream(uri)).use { it.write(bytes) }
                files.send(peer, Delivery.Result("$id:$index", peer.threadId, "",
                    file = ChannelFile(name, uri.toString(), bytes.size.toLong())))
            } finally { app.contentResolver.delete(uri, null, null) }
        }
    }
}
