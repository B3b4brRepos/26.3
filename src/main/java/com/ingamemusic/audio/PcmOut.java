package com.ingamemusic.audio;

import com.ingamemusic.IngameMusic;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.function.BooleanSupplier;
import javax.sound.sampled.*;
import org.lwjgl.BufferUtils;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;
import org.lwjgl.openal.ALC10;

/**
 * Where decoded 16-bit PCM goes. OpenAL (LWJGL, the same library Minecraft's own sounds use) is tried first because
 * it works on phone launchers that have no javax.sound mixers; plain Java Sound is the fallback.
 */
interface PcmOut {
	void start();
	void stop();
	void flush();
	void write(byte[] d, int n) throws InterruptedException;
	/** Frames queued but not yet played. */
	long bufferedFrames();
	/** Waits (bounded) until everything queued has played, or {@code keepWaiting} turns false. */
	void drain(BooleanSupplier keepWaiting) throws InterruptedException;
	void close();

	static PcmOut open(AudioFormat fmt) throws IOException {
		Throwable al;
		try { return new AlOut(fmt); }
		catch (Throwable t) { al = t; IngameMusic.LOG.info("OpenAL output unavailable: {}", t.toString()); }
		try { return new JavaOut(fmt); }
		catch (Throwable t) { throw new IOException("No audio output available (OpenAL: " + al.getMessage() + ")"); }
	}
}

/** Streams buffers to one OpenAL source. Used from a single playback thread only. */
final class AlOut implements PcmOut {
	private static final int NBUF = 6;
	private final int source, fmtEnum, rate, frameSize;
	private final int[] bufs = new int[NBUF];
	private final ArrayDeque<Integer> free = new ArrayDeque<>();
	private final ArrayDeque<int[]> queued = new ArrayDeque<>();   // {bufferId, frames}
	private final ByteBuffer direct = BufferUtils.createByteBuffer(16384);
	private boolean started, paused;

	AlOut(AudioFormat f) {
		if (ALC10.alcGetCurrentContext() == 0L) throw new IllegalStateException("OpenAL context not available");
		int ch = f.getChannels();
		if (ch < 1 || ch > 2) throw new IllegalStateException("Only mono/stereo supported");
		fmtEnum = ch == 1 ? AL10.AL_FORMAT_MONO16 : AL10.AL_FORMAT_STEREO16;
		rate = (int) f.getSampleRate(); frameSize = f.getFrameSize();
		AL10.alGetError();
		source = AL10.alGenSources();
		check("alGenSources");
		AL10.alSourcei(source, AL10.AL_SOURCE_RELATIVE, AL10.AL_TRUE);   // not positional: no distance fade or panning
		AL10.alSource3f(source, AL10.AL_POSITION, 0f, 0f, 0f);
		AL10.alSourcef(source, AL10.AL_GAIN, 1f);
		for (int i = 0; i < NBUF; i++) { bufs[i] = AL10.alGenBuffers(); free.add(bufs[i]); }
		check("alGenBuffers");
	}
	private static void check(String what) {
		int e = AL10.alGetError();
		if (e != AL10.AL_NO_ERROR) throw new IllegalStateException(what + " failed: OpenAL error 0x" + Integer.toHexString(e));
	}
	private void reclaim() {
		int processed = AL10.alGetSourcei(source, AL10.AL_BUFFERS_PROCESSED);
		while (processed-- > 0) { free.add(AL10.alSourceUnqueueBuffers(source)); queued.poll(); }
	}
	private void ensurePlaying() {
		if (!started || paused || queued.isEmpty()) return;
		if (AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE) != AL10.AL_PLAYING) AL10.alSourcePlay(source);   // also restarts after an underrun
	}
	@Override public void start() { started = true; paused = false; ensurePlaying(); }
	@Override public void stop() { paused = true; AL10.alSourcePause(source); }
	@Override public void flush() {
		AL10.alSourceStop(source);
		int q = AL10.alGetSourcei(source, AL10.AL_BUFFERS_QUEUED);
		while (q-- > 0) AL10.alSourceUnqueueBuffers(source);
		queued.clear(); free.clear();
		for (int b : bufs) free.add(b);
	}
	@Override public void write(byte[] d, int n) throws InterruptedException {
		int off = 0;
		while (off < n) {
			int len = Math.min(n - off, direct.capacity());
			len -= len % frameSize;
			if (len <= 0) break;
			for (;;) {
				reclaim();
				if (!free.isEmpty()) break;
				ensurePlaying();
				Thread.sleep(4);
			}
			int id = free.poll();
			direct.clear(); direct.put(d, off, len); direct.flip();
			AL10.alBufferData(id, fmtEnum, direct, rate);
			AL10.alSourceQueueBuffers(source, id);
			queued.add(new int[]{id, len / frameSize});
			ensurePlaying();
			off += len;
		}
	}
	@Override public long bufferedFrames() {
		reclaim();
		long sum = 0; for (int[] q : queued) sum += q[1];
		return Math.max(0, sum - AL10.alGetSourcei(source, AL11.AL_SAMPLE_OFFSET));
	}
	@Override public void drain(BooleanSupplier keepWaiting) throws InterruptedException {
		for (int i = 0; i < 200 && keepWaiting.getAsBoolean(); i++) {
			reclaim();
			if (queued.isEmpty()) break;
			Thread.sleep(20);
		}
	}
	@Override public void close() {
		try {
			AL10.alSourceStop(source);
			int q = AL10.alGetSourcei(source, AL10.AL_BUFFERS_QUEUED);
			while (q-- > 0) AL10.alSourceUnqueueBuffers(source);
			AL10.alDeleteSources(source);
			AL10.alDeleteBuffers(bufs);
		} catch (Throwable ignored) {}
	}
}

/** Plain Java Sound output (desktop fallback). */
final class JavaOut implements PcmOut {
	private final SourceDataLine line;
	private final int bufBytes, fs;
	JavaOut(AudioFormat f) throws LineUnavailableException {
		fs = f.getFrameSize(); bufBytes = fs * ((int) f.getSampleRate() / 8);
		line = AudioSystem.getSourceDataLine(f);
		line.open(f, bufBytes);
	}
	@Override public void start() { line.start(); }
	@Override public void stop() { line.stop(); }
	@Override public void flush() { line.flush(); }
	@Override public void write(byte[] d, int n) { line.write(d, 0, n); }
	@Override public long bufferedFrames() { return (bufBytes - line.available()) / fs; }
	@Override public void drain(BooleanSupplier keepWaiting) throws InterruptedException {
		for (int i = 0; i < 40 && keepWaiting.getAsBoolean() && line.available() < bufBytes; i++) Thread.sleep(20);
	}
	@Override public void close() { try { line.stop(); line.flush(); line.close(); } catch (Exception ignored) {} }
}
