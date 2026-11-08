package com.wangzan101.mika.wechat

import org.junit.Test
import org.junit.Assert.*
import com.wangzan101.mika.voice.Wav
import org.jitsi.impl.neomedia.codec.audio.silk.WechatSilk

class WechatFilesTest {
    @Test fun wavRoundtripKeepsSamplesAndRejectsMalformedLengths() {
        val pcm = ShortArray(3200) { (it % 1000).toShort() }
        val bytes = Wav.encode(pcm)
        val (samples, rate) = WechatFiles.pcmWav(bytes)
        assertEquals(16000, rate)
        assertArrayEquals(pcm, samples)
        assertEquals(pcm.size, WechatSilk.decode(WechatSilk.encode(samples, rate), rate).size)
        try { WechatFiles.pcmWav(bytes.copyOf(42)); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun binaryImageSniffingDoesNotTreatArbitraryHtmlAsImage() {
        assertEquals("png", WechatFiles.imageExtension(byteArrayOf(0x89.toByte(),80,78,71,13,10,26,10)))
        assertEquals("jpg", WechatFiles.imageExtension(byteArrayOf(0xff.toByte(),0xd8.toByte(),0xff.toByte())))
        assertNull(WechatFiles.imageExtension("<svg onload='bad'/>".toByteArray()))
    }
}
