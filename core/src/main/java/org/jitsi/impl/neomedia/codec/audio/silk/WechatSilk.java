package org.jitsi.impl.neomedia.codec.audio.silk;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;

/** Bounded WeChat SILK container adapter over libjitsi's Apache-2.0 Java codec. */
public final class WechatSilk {
    private static final byte[] HEADER = "#!SILK_V3".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    public static short[] decode(byte[] data, int rate) throws IOException {
        checkRate(rate);
        int offset = data.length > 0 && data[0] == 2 ? 1 : 0;
        if (data.length < offset + HEADER.length) throw new IOException("Incomplete SILK header");
        for (byte b : HEADER) if (data[offset++] != b) throw new IOException("Unsupported voice format");
        if (offset < data.length && data[offset] == 10) offset++;
        SKP_Silk_decoder_state state = new SKP_Silk_decoder_state();
        if (DecAPI.SKP_Silk_SDK_InitDecoder(state) != 0) throw new IOException("SILK decoder initialization failed");
        SKP_SILK_SDK_DecControlStruct control = new SKP_SILK_SDK_DecControlStruct();
        control.API_sampleRate = rate;
        short[] result = new short[rate * 60];
        short[] frame = new short[rate / 50];
        int used = 0;
        while (offset < data.length) {
            if (offset + 2 > data.length) throw new IOException("Incomplete SILK packet length");
            int length = (short)((data[offset] & 255) | ((data[offset + 1] & 255) << 8));
            offset += 2;
            if (length == -1 && offset == data.length) break;
            if (length <= 0 || length > 1250 || offset + length > data.length) throw new IOException("Invalid SILK packet");
            int frames = 0;
            do {
                short[] count = {(short)frame.length};
                int status = DecAPI.SKP_Silk_SDK_Decode(state, control, 0, data, offset, length, frame, 0, count);
                if (status != 0 || count[0] <= 0 || count[0] > frame.length || used + count[0] > result.length)
                    throw new IOException("Invalid or over-60-second SILK audio");
                System.arraycopy(frame, 0, result, used, count[0]);
                used += count[0];
                if (++frames > 5) throw new IOException("Too many SILK frames in packet");
            } while (control.moreInternalDecoderFrames != 0);
            offset += length;
        }
        return Arrays.copyOf(result, used);
    }

    public static byte[] encode(short[] samples, int rate) throws IOException {
        checkRate(rate);
        if (samples.length == 0 || samples.length > rate * 60) throw new IOException("Voice must be 0–60 seconds");
        SKP_Silk_encoder_state_FLP state = new SKP_Silk_encoder_state_FLP();
        SKP_SILK_SDK_EncControlStruct control = new SKP_SILK_SDK_EncControlStruct();
        if (EncAPI.SKP_Silk_SDK_InitEncoder(state, control) != 0) throw new IOException("SILK encoder initialization failed");
        control.API_sampleRate = rate; control.maxInternalSampleRate = rate;
        control.packetSize = rate / 50; control.bitRate = 24000;
        control.complexity = 1; control.packetLossPercentage = 0; control.useDTX = 0; control.useInBandFEC = 0;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(2); out.write(HEADER, 0, HEADER.length);
        short[] frame = new short[rate / 50];
        byte[] packet = new byte[1250];
        for (int at = 0; at < samples.length; at += frame.length) {
            Arrays.fill(frame, (short)0);
            System.arraycopy(samples, at, frame, 0, Math.min(frame.length, samples.length - at));
            short[] count = {(short)packet.length};
            if (EncAPI.SKP_Silk_SDK_Encode(state, control, frame, 0, frame.length, packet, 0, count) != 0 || count[0] <= 0)
                throw new IOException("SILK encoding failed");
            out.write(count[0] & 255); out.write((count[0] >> 8) & 255); out.write(packet, 0, count[0]);
        }
        return out.toByteArray();
    }
    private static void checkRate(int rate) {
        if (rate != 8000 && rate != 12000 && rate != 16000 && rate != 24000) throw new IllegalArgumentException("Unsupported SILK sample rate");
    }
    private WechatSilk() { }
}
