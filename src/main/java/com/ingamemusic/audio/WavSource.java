package com.ingamemusic.audio;

import java.io.*;
import java.nio.file.*;
import javax.sound.sampled.*;

public final class WavSource implements AudioSource {
	private final Path path;
	private AudioInputStream in;
	private final AudioFormat fmt;
	private final long durMs;

	public WavSource(Path p) throws IOException {
		path = p;
		try {
			AudioInputStream raw = AudioSystem.getAudioInputStream(p.toFile());
			AudioFormat src = raw.getFormat();
			fmt = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, src.getSampleRate(), 16, src.getChannels(), src.getChannels() * 2, src.getSampleRate(), false);
			durMs = raw.getFrameLength() > 0 && src.getFrameRate() > 0 ? (long) (raw.getFrameLength() * 1000.0 / src.getFrameRate()) : -1;
			raw.close();
			if (!AudioSystem.isConversionSupported(fmt, src)) throw new IOException("Unsupported WAV encoding: " + src);
			open();
		} catch (UnsupportedAudioFileException e) { throw new IOException("Unsupported audio file: " + e.getMessage(), e); }
	}
	private void open() throws IOException {
		close();
		try { in = AudioSystem.getAudioInputStream(fmt, AudioSystem.getAudioInputStream(path.toFile())); }
		catch (UnsupportedAudioFileException e) { throw new IOException(e); }
	}
	@Override public AudioFormat format() { return fmt; }
	@Override public long durationMs() { return durMs; }
	@Override public int read(byte[] b) throws IOException {
		int fs = fmt.getFrameSize(), total = 0;
		while (total < b.length) { int n = in.read(b, total, b.length - total); if (n < 0) break; total += n; }
		total -= total % fs;
		return total == 0 ? -1 : total;
	}
	@Override public void seek(long ms) throws IOException {
		open();
		in.skipNBytes((long) (ms * fmt.getFrameRate() / 1000.0) * fmt.getFrameSize());
	}
	@Override public void close() { try { if (in != null) in.close(); } catch (IOException ignored) {} }
}
