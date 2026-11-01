package com.ingamemusic.audio;

import com.ingamemusic.Dirs;
import com.ingamemusic.IngameMusic;
import com.ingamemusic.model.Track;
import com.ingamemusic.net.HttpUtil;
import com.ingamemusic.net.NeteaseApi;
import java.io.IOException;
import java.nio.file.*;
import javax.sound.sampled.*;

/**
 * Own playback thread per track writing PCM to its own OpenAL source (works on phone launchers) or, failing that,
 * a javax.sound line. Separate from Minecraft's sound events, so game sounds are never affected.
 */
public final class AudioEngine {
	public enum State { IDLE, LOADING, PLAYING, PAUSED, ERROR }
	public interface Listener { void onEnded(Track t); void onError(Track t, String msg); }
	public static final int BANDS = 8;
	private static final float[] FREQS = {60, 150, 300, 600, 1200, 2400, 4800, 9600};

	private volatile State state = State.IDLE;
	private volatile long positionMs, durationMs;
	private volatile String error = "";
	private volatile float volume = 0.6f;
	private final float[] bands = new float[BANDS];
	private volatile Session current;
	private Listener listener;

	public void setListener(Listener l) { listener = l; }
	public State state() { return state; }
	public long position() { return positionMs; }
	public long duration() { return durationMs; }
	public String error() { return error; }
	public float volume() { return volume; }
	public void setVolume(float v) { volume = Math.max(0f, Math.min(1f, v)); }
	public float band(int i) { return state == State.PLAYING ? bands[i] : 0f; }
	public boolean isPaused() { Session s = current; return s != null && s.paused; }

	public synchronized void play(Track t, long startMs) {
		stopInternal();
		Session s = new Session(t, startMs);
		current = s; state = State.LOADING; positionMs = startMs; durationMs = t.durationMs(); error = "";
		s.thread.start();
	}
	public synchronized void stop() { stopInternal(); state = State.IDLE; positionMs = 0; }
	private void stopInternal() {
		Session s = current; current = null;
		if (s != null) s.cancel();
		java.util.Arrays.fill(bands, 0f);
	}
	public void pause() { Session s = current; if (s == null) return; s.paused = true; if (state == State.PLAYING) state = State.PAUSED; }
	public void resume() {
		Session s = current; if (s == null) return;
		s.paused = false; synchronized (s.lock) { s.lock.notifyAll(); }
		if (state == State.PAUSED) state = State.PLAYING;
	}
	public void seek(long ms) {
		Session s = current; if (s == null) return;
		ms = Math.max(0, durationMs > 0 ? Math.min(ms, durationMs - 100) : ms);
		s.seekTo = ms; positionMs = ms;
		synchronized (s.lock) { s.lock.notifyAll(); }
	}
	public void shutdown() { stop(); }

	private final class Session implements Runnable {
		final Track track; final long startMs; final Thread thread; final Object lock = new Object();
		volatile boolean stop, paused; volatile long seekTo = -1;
		Session(Track t, long s) { track = t; startMs = s; thread = new Thread(this, "ingamemusic-audio"); thread.setDaemon(true); }
		void cancel() { stop = true; synchronized (lock) { lock.notifyAll(); } thread.interrupt(); }
		boolean live() { return current == this && !stop; }

		@Override public void run() {
			AudioSource src = null; PcmOut line = null;
			try {
				Path file;
				if (track.isLocal()) file = Path.of(track.localPath());
				else {
					file = Dirs.cache().resolve(Dirs.safe(track.id()) + ".mp3");
					if (!Files.exists(file) || Files.size(file) < 1024) {
						String url = track.audioUrl();
						if (track.isNetease()) {                          // the play link is only valid for a while, so ask for it now
							NeteaseApi.SongUrl su = NeteaseApi.songUrl(track.neteaseId());
							url = su.url();
							if (su.trialOnly()) IngameMusic.LOG.info("NetEase only offers a preview of {} for this account (VIP song?)", track.title());
						}
						HttpUtil.download(url, file);
					}
					else file.toFile().setLastModified(System.currentTimeMillis());
				}
				if (!live()) return;
				src = AudioSource.open(file);
				if (src.durationMs() > 0) durationMs = src.durationMs();
				AudioFormat fmt = src.format();
				int fs = fmt.getFrameSize(), rate = (int) fmt.getSampleRate();
				line = PcmOut.open(fmt);
				long base = startMs, written = 0;
				if (startMs > 0) src.seek(startMs);
				line.start();
				if (live()) state = paused ? State.PAUSED : State.PLAYING;
				byte[] data = new byte[(8192 / fs) * fs];
				float[] mono = new float[512];
				while (live()) {
					long sk = seekTo;
					if (sk >= 0) { seekTo = -1; src.seek(sk); line.flush(); written = 0; base = sk; positionMs = sk; continue; }
					if (paused) {
						line.stop(); if (live()) state = State.PAUSED;
						synchronized (lock) { while (paused && !stop && seekTo < 0) lock.wait(250); }
						if (!live()) break;
						if (!paused) { line.start(); state = State.PLAYING; }
						continue;
					}
					int n = src.read(data);
					if (n < 0) break;
					analyse(data, n, fmt.getChannels(), rate, mono);
					applyVolume(data, n);
					line.write(data, n);
					written += n / fs;
					long buffered = line.bufferedFrames();
					positionMs = base + Math.max(0, written - buffered) * 1000L / rate;
				}
				if (live()) {            // natural end: wait for buffer to drain
					line.drain(this::live);
				}
				if (live()) { state = State.IDLE; positionMs = durationMs > 0 ? durationMs : positionMs; Listener l = listener; if (l != null) l.onEnded(track); }
			} catch (InterruptedException ignored) {
			} catch (Throwable e) {
				if (live()) {
					String msg = e instanceof LineUnavailableException ? "Audio device unavailable" : HttpUtil.friendly(e);
					error = msg; state = State.ERROR;
					IngameMusic.LOG.warn("Playback failed for {}", track.title(), e);
					Listener l = listener; if (l != null) l.onError(track, msg);
				}
			} finally {
				if (line != null) line.close();
				if (src != null) src.close();
			}
		}
		void applyVolume(byte[] d, int n) {
			float g = volume * volume;
			for (int i = 0; i + 1 < n; i += 2) {
				int s = (short) ((d[i] & 0xFF) | (d[i + 1] << 8));
				s = Math.max(-32768, Math.min(32767, Math.round(s * g)));
				d[i] = (byte) s; d[i + 1] = (byte) (s >> 8);
			}
		}
		/** Small Goertzel filter bank (8 bands) on up to 512 samples: cheap and allocation-free. */
		void analyse(byte[] d, int n, int ch, int rate, float[] mono) {
			int frames = Math.min(mono.length, n / (2 * ch));
			if (frames < 64) return;
			for (int i = 0; i < frames; i++) { int o = i * 2 * ch; mono[i] = (short) ((d[o] & 0xFF) | (d[o + 1] << 8)) / 32768f; }
			for (int b = 0; b < BANDS; b++) {
				if (FREQS[b] > rate / 2f) { bands[b] *= 0.8f; continue; }
				double coeff = 2 * Math.cos(2 * Math.PI * FREQS[b] / rate), s1 = 0, s2 = 0;
				for (int i = 0; i < frames; i++) { double s0 = mono[i] + coeff * s1 - s2; s2 = s1; s1 = s0; }
				double power = Math.max(0, s1 * s1 + s2 * s2 - coeff * s1 * s2);
				float mag = (float) (Math.sqrt(power) / (frames * 0.5));
				float lvl = Math.min(1f, (float) Math.sqrt(mag) * 1.8f);
				bands[b] = Math.max(lvl, bands[b] * 0.85f);
			}
		}
	}
}
