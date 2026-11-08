package com.wangzan101.mika.core.wechat

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.jitsi.impl.neomedia.codec.audio.silk.WechatSilk
import java.util.Base64
import javax.crypto.Cipher
import kotlinx.serialization.json.*

class WechatMediaTest {
    @Test fun rawAndHexBase64KeysDecryptTheSamePayload() {
        val key = ByteArray(16) { it.toByte() }
        val raw = Base64.getEncoder().encodeToString(key)
        val hex = key.joinToString("") { "%02x".format(it) }
        val encodedHex = Base64.getEncoder().encodeToString(hex.toByteArray())
        assertArrayEquals(key, WechatMediaApi.decodeKey(raw))
        assertArrayEquals(key, WechatMediaApi.decodeKey(encodedHex))
        val plain = "native image bytes".toByteArray()
        val encrypted = WechatMediaApi.crypt(Cipher.ENCRYPT_MODE, plain, key)
        assertEquals(32, encrypted.size)
        assertArrayEquals(plain, WechatMediaApi.crypt(Cipher.DECRYPT_MODE, encrypted, key))
    }

    @Test fun downloadsEncryptedImageWithoutLeakingBotHeaders() {
        MockWebServer().use { server ->
            server.start()
            val base = "http://localhost:${server.port}"
            val api = WechatApi(OkHttpClient(), "0.1.38", base)
            val cdn = WechatMediaApi(OkHttpClient(), api, base)
            val key = ByteArray(16) { 3 }
            val bytes = byteArrayOf(1,2,3,4,5)
            server.enqueue(MockResponse().setBody(okio.Buffer().write(WechatMediaApi.crypt(Cipher.ENCRYPT_MODE, bytes, key))))
            val item = WechatItem(2, image = WechatImage(WechatMedia(query = "a+b/c=", aesKey = Base64.getEncoder().encodeToString(key))))
            assertArrayEquals(bytes, cdn.download(item))
            val request = server.takeRequest()
            assertEquals("a+b/c=", request.requestUrl!!.queryParameter("encrypted_query_param"))
            assertNull(request.getHeader("Authorization"))
        }
    }

    @Test fun uploadUsesOfficialEncryptedMediaContractAndStructuredImageMessage() {
        MockWebServer().use { server ->
            server.start()
            val base = "http://localhost:${server.port}"
            val api = WechatApi(OkHttpClient(), "0.1.38", base)
            val cdn = WechatMediaApi(OkHttpClient(), api, base)
            val account = WechatAccount("bot-secret", base, "bot", "owner")
            server.enqueue(MockResponse().setBody("""{"upload_full_url":"$base/upload"}"""))
            server.enqueue(MockResponse().setHeader("x-encrypted-param", "download-param").setBody(""))
            server.enqueue(MockResponse().setBody("""{"ret":0}"""))
            val bytes = "PNG fixture bytes".toByteArray()
            val (ref, size) = cdn.upload(account, "owner", bytes, 1)
            api.sendItem(account, "owner", "file:run:0", WechatItem(2, image = WechatImage(ref, midSize = size)), "ctx")
            val prepare = server.takeRequest()
            assertEquals("/ilink/bot/getuploadurl", prepare.path)
            val body = Json.parseToJsonElement(prepare.body.readUtf8()).jsonObject
            assertEquals("1", body.getValue("media_type").jsonPrimitive.content)
            assertEquals("true", body.getValue("no_need_thumb").jsonPrimitive.content)
            val upload = server.takeRequest()
            assertNull(upload.getHeader("Authorization"))
            assertEquals("application/octet-stream", upload.getHeader("Content-Type"))
            assertArrayEquals(bytes, WechatMediaApi.crypt(Cipher.DECRYPT_MODE, upload.body.readByteArray(),
                WechatMediaApi.hexBytes(body.getValue("aeskey").jsonPrimitive.content)))
            val send = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            val message = send.getValue("msg").jsonObject
            assertEquals("ctx", message.getValue("context_token").jsonPrimitive.content)
            assertEquals("2", message.getValue("item_list").jsonArray.single().jsonObject.getValue("type").jsonPrimitive.content)
            val media = message.getValue("item_list").jsonArray.single().jsonObject.getValue("image_item").jsonObject.getValue("media").jsonObject
            assertEquals("1", media.getValue("encrypt_type").jsonPrimitive.content)
        }
    }

    @Test fun invalidKeyAndUntrustedDownloadAddressAreRejected() {
        try { WechatMediaApi.decodeKey("YWJj"); fail() } catch (_: IllegalArgumentException) { }
        val api = WechatApi(OkHttpClient(), "0.1.38")
        val cdn = WechatMediaApi(OkHttpClient(), api)
        try { cdn.download(WechatItem(2, image = WechatImage(WechatMedia(fullUrl = "http://127.0.0.1/secret")))); fail() }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun silkContainerRoundtripDecodesBoundedAudioWithoutNativeLibraries() {
        val rate = 16000
        val samples = ShortArray(rate / 5) { (kotlin.math.sin(it * 2.0 * Math.PI * 440 / rate) * 5000).toInt().toShort() }
        val silk = WechatSilk.encode(samples, rate)
        assertEquals(2, silk[0].toInt())
        val decoded = WechatSilk.decode(silk, rate)
        assertEquals(samples.size, decoded.size)
        assertTrue(decoded.any { it.toInt() != 0 })
        try { WechatSilk.decode(silk.copyOf(4), rate); fail() } catch (_: java.io.IOException) { }
        try { WechatSilk.encode(ShortArray(rate * 61), rate); fail() } catch (_: java.io.IOException) { }
    }
}
