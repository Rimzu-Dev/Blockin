package com.insanestudios.blockin.Sound;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;

import java.io.InputStream;
import java.util.Arrays;

/**
 * Procedural sound engine for Blockin — no audio files required.
 *
 * <p>Every effect (step, break, place, jump) is synthesized in real time over
 * the system mixer as short PCM bursts. The sound "category" passed by callers
 * (e.g. "Grass", "Stone") is resolved to a small set of material flavours so
 * different blocks can feel slightly different without shipping assets.
 *
 * <p>Volume and mute state are plain public fields so the settings UI and the
 * engine share them directly.
 */
public final class SoundEngine {

    /** Master per-effect levels (0.0 - 1.0) and enable flags. */
    public static float stepVolume = 0.6F;
    public static float breakVolume = 0.8F;
    public static float placeVolume = 0.7F;
    public static boolean stepEnabled = true;
    public static boolean breakEnabled = true;
    public static boolean placeEnabled = true;

    private static final float SAMPLE_RATE = 44100.0F;

    private static SourceDataLine line;
    private static boolean audioUnavailable = false;

    private static byte[] stepClip;

    private SoundEngine() {
    }

    // ----------------------------------------------------------- playback

    public synchronized static void playStep(String category) {
        if (!stepEnabled || stepVolume <= 0.0F) return;
        writeRaw(scalePcm(stepClipOr(strike(0.07F)), stepVolume));
    }

    public synchronized static void playJump(String category) {
        if (!stepEnabled || stepVolume <= 0.0F) return;
        write(whoosh(0.22F), stepVolume * 0.8F);
    }

    public synchronized static void playBreak(String category) {
        if (!breakEnabled || breakVolume <= 0.0F) return;
        write(crack(0.16F, materialHardness(category)), breakVolume);
    }

    public synchronized static void playPlace(String category) {
        if (!placeEnabled || placeVolume <= 0.0F) return;
        float[] thud = thump(0.05F, 0.9F, 0.6F, 0.35F);
        float[] blip = placeBlip(0.06F, materialDensity(category));
        float[] mix = new float[Math.max(thud.length, blip.length)];
        System.arraycopy(thud, 0, mix, 0, thud.length);
        for (int i = 0; i < blip.length; i++) {
            mix[i] += blip[i];
        }
        write(mix, placeVolume);
    }

    // -------------------------------------------------------- synthesis

    /** Short low-frequency thud for footsteps and block placement. */
    private static float[] thump(float seconds, float freq, float density, float noiseAmt) {
        int n = (int) (SAMPLE_RATE * seconds);
        float[] out = new float[n];
        for (int i = 0; i < n; i++) {
            float t = i / SAMPLE_RATE;
            float decay = (float) Math.exp(-t * 30.0F);
            float tone = (float) Math.sin(2.0 * Math.PI * freq * t);
            float noise = (float) (Math.random() * 2.0 - 1.0) * noiseAmt * (density > 0.5F ? 0.6F : 1.0F);
            out[i] = (tone * 0.8F + noise) * decay;
        }
        return out;
    }

    /** Fallback synthetic step used only when no recorded WAV was staged. */
    private static byte[] strike(float seconds) {
        return floatsToBytes(thump(seconds, 0.55F, 0.3F, 0.25F), 1.0F);
    }

    private static byte[] stepClipOr(byte[] fallback) {
        if (stepClip != null) return stepClip;
        try (InputStream in = SoundEngine.class.getResourceAsStream(
                "/com/insanestudios/blockin/Sound/Sounds/Walking/Walking_Grass.wav")) {
            byte[] clip = readWavMono16(in, 52800);
            stepClip = (clip != null && clip.length > 0) ? clip : fallback;
        } catch (Throwable t) {
            stepClip = fallback;
        }
        return stepClip;
    }

    /** The little pitched "blip" layered on top of a placement thud. */
    private static float[] placeBlip(float seconds, float baseFreq) {
        int n = (int) (SAMPLE_RATE * seconds);
        float[] out = new float[n];
        float f0 = 200.0F + baseFreq * 80.0F;
        float f1 = f0 * 0.65F;
        for (int i = 0; i < n; i++) {
            float t = i / (float) n;
            float freq = f0 + (f1 - f0) * t;
            float phase = (float) (2.0 * Math.PI * freq * i / SAMPLE_RATE);
            float env = (float) Math.sin(Math.PI * t); // soft attack + release
            out[i] = (float) Math.sin(phase) * env * 0.35F;
        }
        return out;
    }

    /** Noise burst with a descending pitch for block breaking. */
    private static float[] crack(float seconds, float hardness) {
        int n = (int) (SAMPLE_RATE * seconds);
        float[] out = new float[n];
        for (int i = 0; i < n; i++) {
            float t = i / (float) n;
            float decay = (float) Math.exp(-t * 16.0F);
            float freq = 300.0F - (300.0F - 80.0F) * t; // pitch falls away
            float wobble = (float) Math.sin(2.0 * Math.PI * freq * i / SAMPLE_RATE);
            float noise = (float) (Math.random() * 2.0 - 1.0);
            out[i] = (noise * 0.6F + wobble * 0.4F) * decay * (0.6F + hardness * 0.6F);
        }
        return out;
    }

    /** Soft filtered whoosh for jumping. */
    private static float[] whoosh(float seconds) {
        int n = (int) (SAMPLE_RATE * seconds);
        float[] out = new float[n];
        float smooth = 0.0F;
        for (int i = 0; i < n; i++) {
            float t = i / (float) n;
            float env = (float) Math.sin(Math.PI * t); // rise then fall
            float noise = (float) (Math.random() * 2.0 - 1.0);
            smooth = smooth * 0.85F + noise * 0.15F; // crude low-pass
            out[i] = smooth * env;
        }
        return out;
    }

    // ------------------------------------------------------ material mix

    /** 0 (soft, e.g. grass/dirt) .. 1 (hard, e.g. stone). */
    private static float materialHardness(String category) {
        if (category == null || category.isEmpty()) return 0.5F;
        String c = category.toLowerCase();
        if (c.contains("stone") || c.contains("rock") || c.contains("glass")) return 0.9F;
        if (c.contains("wood") || c.contains("plank")) return 0.45F;
        return 0.25F; // grass, dirt, sand, ...
    }

    /** 0 (light) .. 1 (dense) for placement pitch/thud colour. */
    private static float materialDensity(String category) {
        if (category == null || category.isEmpty()) return 0.5F;
        String c = category.toLowerCase();
        if (c.contains("stone") || c.contains("rock")) return 0.9F;
        if (c.contains("dirt") || c.contains("sand")) return 0.6F;
        if (c.contains("wood") || c.contains("plank")) return 0.55F;
        return 0.3F; // grass, snow, cloth ...
    }

    // ------------------------------------------------------------- device

    /** Decodes a WAV stream into 16-bit mono big-endian PCM at {@link #SAMPLE_RATE},
     * trimmed of trailing silence and capped at {@code maxBytes} samples. */
    private static byte[] readWavMono16(InputStream in, int maxSampleBytes) {
        if (in == null) return null;
        try {
            AudioInputStream ais = AudioSystem.getAudioInputStream(in);
            AudioFormat mono16 = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, SAMPLE_RATE, 16, 1, 2, SAMPLE_RATE, true);
            AudioInputStream conv = AudioSystem.getAudioInputStream(mono16, ais);

            byte[] raw = new byte[maxSampleBytes * 2];
            int off = 0;
            int r;
            while (off < raw.length && (r = conv.read(raw, off, raw.length - off)) > 0) {
                off += r;
            }
            raw = Arrays.copyOf(raw, off);

            float[] mono = bytesToFloats(raw);
            return floatsToBytes(trimTail(mono, 0.003F), 1.0F);
        } catch (Throwable t) {
            System.err.println("[SoundEngine] step decode failed: " + t);
            return null;
        }
    }

    private static float[] bytesToFloats(byte[] pcm) {
        float[] out = new float[pcm.length / 2];
        for (int i = 0; i < out.length; i++) {
            int s = (pcm[i * 2] & 0xFF) | (pcm[i * 2 + 1] << 8);
            out[i] = ((float) s) / 32767.0F;
        }
        return out;
    }

    private static byte[] floatsToBytes(float[] f, float volume) {
        return floatsToBytes(f, f.length, volume);
    }

    private static byte[] floatsToBytes(float[] f, int count, float volume) {
        byte[] out = new byte[count * 2];
        for (int i = 0; i < count; i++) {
            int s = (int) (Math.max(-1.0, Math.min(1.0, f[i] * volume)) * 32767.0);
            out[i * 2] = (byte) (s >> 8);
            out[i * 2 + 1] = (byte) (s & 0xFF);
        }
        return out;
    }

    /** Removes leading/trailing samples below the noise floor after a lead-in. */
    private static float[] trimTail(float[] f, float floor) {
        int end = f.length;
        while (end > 0 && Math.abs(f[end - 1]) < floor) end--;
        return Arrays.copyOf(f, end);
    }

    private static void ensureLine() {
        if (line != null || audioUnavailable) return;
        try {
            AudioFormat format = new AudioFormat(SAMPLE_RATE, 16, 1, true, true);
            SourceDataLine dl = AudioSystem.getSourceDataLine(format);
            dl.open(format, 65536);
            dl.start();
            line = dl;
        } catch (Throwable t) {
            audioUnavailable = true;
            System.err.println("[SoundEngine] audio output unavailable: " + t);
        }
    }

    private static void write(float[] samples, float volume) {
        writeRaw(floatsToBytes(samples, volume));
    }

    private static void writeRaw(byte[] out) {
        ensureLine();
        if (line == null || out.length == 0) return;

        if (out.length > line.available()) return;
        line.write(out, 0, out.length);
    }

    /** Scales 16-bit big-endian PCM in place on a fresh copy. */
    private static byte[] scalePcm(byte[] pcm, float volume) {
        if (volume <= 0.0001F) return new byte[0];
        byte[] out = new byte[pcm.length];
        for (int i = 0; i + 1 < pcm.length; i += 2) {
            int s = (pcm[i] & 0xFF) | (pcm[i + 1] << 8);
            s = (int) (s * volume);
            out[i] = (byte) (s >> 8);
            out[i + 1] = (byte) (s & 0xFF);
        }
        return out;
    }

    /** No-op keep-alive that forces the mixer to initialise at startup. */
    public static void warmUp() {
        ensureLine();
    }

    /** Stops the audio thread and frees the output line. */
    public static void shutdown() {
        if (line != null) {
            line.drain();
            line.stop();
            line.close();
            line = null;
        }
    }
}