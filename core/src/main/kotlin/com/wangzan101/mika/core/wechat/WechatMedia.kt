package com.wangzan101.mika.core.wechat

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

@OptIn(ExperimentalSerializationApi::class)
@Serializable data class WechatMedia(
    @SerialName("encrypt_query_param") val query: String? = null,
    @SerialName("aes_key") val aesKey: String? = null,
    @EncodeDefault @SerialName("encrypt_type") val encryptType: Int = 1,
    @SerialName("full_url") val fullUrl: String? = null,
)
@Serializable data class WechatImage(val media: WechatMedia? = null, val aeskey: String? = null,
    @SerialName("mid_size") val midSize: Int? = null)
@Serializable data class WechatVoice(val media: WechatMedia? = null, val text: String? = null,
    @SerialName("encode_type") val encodeType: Int? = null,
    @SerialName("sample_rate") val sampleRate: Int? = null,
    @SerialName("bits_per_sample") val bitsPerSample: Int? = null, val playtime: Int? = null)
@Serializable data class WechatFile(val media: WechatMedia? = null, @SerialName("file_name") val name: String = "", val len: String = "")
@Serializable internal data class WechatUploadRequest(
    val filekey: String, @SerialName("media_type") val mediaType: Int,
    @SerialName("to_user_id") val peer: String, val rawsize: Int, val rawfilemd5: String,
    val filesize: Int, @SerialName("no_need_thumb") val noThumb: Boolean, val aeskey: String,
    @SerialName("base_info") val baseInfo: WechatBaseInfo,
)
@Serializable data class WechatUploadReply(val ret: Int = 0, val errcode: Int = 0, val errmsg: String? = null,
    @SerialName("upload_param") val uploadParam: String? = null,
    @SerialName("upload_full_url") val uploadUrl: String? = null)

/** Official iLink CDN transport. Bot authorization is never forwarded to the CDN. */
class WechatMediaApi(client: OkHttpClient, private val api: WechatApi,
    private val cdnBase: String = "https://novac2c.cdn.weixin.qq.com/c2c") {
    private val http = client.newBuilder().followRedirects(false).followSslRedirects(false).build()

    fun download(item: WechatItem): ByteArray {
        val media = (if (item.type == 2) item.image?.media else item.voice?.media)
            ?: throw IOException("WeChat media reference is missing")
        val url = media.fullUrl?.takeIf { it.isNotBlank() } ?: cdnBase.trimEnd('/') +
            "/download?encrypted_query_param=" + java.net.URLEncoder.encode(media.query ?: throw IOException("WeChat download parameter is missing"), "UTF-8")
        val encrypted = http.newCall(Request.Builder().url(checkedUrl(url)).get().build()).execute().use { r ->
            if (!r.isSuccessful) throw WechatHttpException(r.code)
            readLimited(r.body?.byteStream() ?: throw IOException("Empty media response"))
        }
        val rawKey = item.image?.aeskey?.let(::hexBytes) ?: media.aesKey?.let(::decodeKey)
        if (rawKey == null && item.type != 2) throw IOException("WeChat voice encryption key is missing")
        return if (rawKey == null) encrypted else crypt(Cipher.DECRYPT_MODE, encrypted, rawKey)
    }

    fun upload(account: WechatAccount, peer: String, bytes: ByteArray, mediaType: Int): Pair<WechatMedia, Int> {
        require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES) { "Media exceeds the 20 MB limit" }
        val key = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val hex = key.hex()
        val encrypted = crypt(Cipher.ENCRYPT_MODE, bytes, key)
        val filekey = ByteArray(16).also { SecureRandom().nextBytes(it) }.hex()
        val upload = api.uploadUrl(account, filekey, mediaType, peer, bytes.size,
            MessageDigest.getInstance("MD5").digest(bytes).hex(), encrypted.size, hex)
        val url = upload.uploadUrl?.takeIf { it.isNotBlank() } ?: cdnBase.trimEnd('/') +
            "/upload?encrypted_query_param=" + java.net.URLEncoder.encode(upload.uploadParam
                ?: throw IOException("WeChat upload parameter is missing"), "UTF-8") + "&filekey=$filekey"
        // Upload is idempotent for this filekey. A message POST is never automatically retried.
        var last: IOException? = null
        repeat(3) {
            try {
                http.newCall(Request.Builder().url(checkedUrl(url))
                    .post(encrypted.toRequestBody("application/octet-stream".toMediaType())).build()).execute().use { r ->
                    if (r.code in 400..499) throw WechatHttpException(r.code)
                    if (r.code != 200) throw IOException("WeChat CDN upload HTTP ${r.code}")
                    val query = r.header("x-encrypted-param")?.takeIf { it.isNotBlank() }
                        ?: throw IOException("WeChat CDN response is missing the download parameter")
                    return WechatMedia(query, Base64.getEncoder().encodeToString(hex.toByteArray(Charsets.US_ASCII))) to encrypted.size
                }
            } catch (e: WechatHttpException) { throw e }
            catch (e: IOException) { last = e }
        }
        throw last ?: IOException("WeChat upload failed")
    }

    private fun checkedUrl(value: String): okhttp3.HttpUrl {
        val u = value.toHttpUrl()
        val testBase = cdnBase.toHttpUrl()
        require((u.isHttps && u.port == 443 && (u.host == "weixin.qq.com" || u.host.endsWith(".weixin.qq.com"))) ||
            (testBase.host == "localhost" && u.host == testBase.host && u.port == testBase.port)) { "Unsupported WeChat CDN address" }
        require(u.username.isEmpty() && u.password.isEmpty()) { "Unexpected CDN credentials" }
        return u
    }

    companion object {
        const val MAX_BYTES = 20 * 1024 * 1024
        fun readLimited(input: java.io.InputStream): ByteArray = input.use {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val n = it.read(buffer)
                if (n < 0) break
                require(out.size() + n <= MAX_BYTES + 16) { "Media exceeds the 20 MB limit" }
                out.write(buffer, 0, n)
            }
            out.toByteArray()
        }
        fun decodeKey(value: String): ByteArray {
            val decoded = Base64.getDecoder().decode(value)
            return if (decoded.size == 16) decoded else hexBytes(decoded.toString(Charsets.US_ASCII))
        }
        fun hexBytes(value: String): ByteArray {
            require(value.matches(Regex("[0-9a-fA-F]{32}"))) { "Invalid WeChat AES key" }
            return ByteArray(16) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        }
        fun crypt(mode: Int, bytes: ByteArray, key: ByteArray): ByteArray {
            require(key.size == 16)
            return Cipher.getInstance("AES/ECB/PKCS5Padding").run { init(mode, SecretKeySpec(key, "AES")); doFinal(bytes) }
        }
        private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
