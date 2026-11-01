package com.ingamemusic.audio;

import java.io.IOException;
import java.nio.*;
import java.nio.file.*;
import javax.sound.sampled.AudioFormat;
import org.lwjgl.stb.STBVorbis;
import org.lwjgl.system.*;

/** OGG Vorbis via LWJGL's bundled stb_vorbis (already shipped with Minecraft). Decodes the whole file up front. */
public final class OggSource implements AudioSource {
	private ShortBuffer pcm;
	private final int channels, rate;
	private int pos;

	public OggSource(Path p) throws IOException {
		byte[] raw = Files.readAllBytes(p);
		ByteBuffer bb = MemoryUtil.memAlloc(raw.length);
		try (MemoryStack st = MemoryStack.stackPush()) {
			bb.put(raw).flip();
			IntBuffer ch = st.mallocInt(1), sr = st.mallocInt(1);
			pcm = STBVorbis.stb_vorbis_decode_memory(bb, ch, sr);
			if (pcm == null) throw new IOException("Could not decode OGG (only Vorbis is supported)");
			channels = ch.get(0); rate = sr.get(0);
		} finally { MemoryUtil.memFree(bb); }
		if (channels < 1 || channels > 2) { close(); throw new IOException("Only mono/stereo OGG is supported"); }
	}
	@Override public AudioFormat format() { return new AudioFormat(rate, 16, channels, true, false); }
	@Override public long durationMs() { return pcm == null ? -1 : (long) (pcm.limit() / channels * 1000L / rate); }
	@Override public int read(byte[] b) {
		if (pcm == null) return -1;
		int frames = Math.min(b.length / (2 * channels), (pcm.limit() - pos) / channels);
		if (frames <= 0) return -1;
		int n = frames * channels;
		for (int i = 0; i < n; i++) { short s = pcm.get(pos + i); b[2 * i] = (byte) s; b[2 * i + 1] = (byte) (s >> 8); }
		pos += n;
		return n * 2;
	}
	@Override public void seek(long ms) {
		long f = ms * rate / 1000L;
		pos = (int) Math.min(pcm.limit() - (pcm.limit() % channels), f * channels);
	}
	@Override public void close() { if (pcm != null) { MemoryUtil.memFree(pcm); pcm = null; } }
}
