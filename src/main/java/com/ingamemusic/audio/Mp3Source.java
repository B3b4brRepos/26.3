package com.ingamemusic.audio;

import java.io.*;
import java.nio.file.*;
import javax.sound.sampled.AudioFormat;
import javazoom.jl.decoder.*;

public final class Mp3Source implements AudioSource {
	private final Path path;
	private Bitstream bs;
	private Decoder dec;
	private InputStream in;
	private AudioFormat fmt;
	private long durMs;
	private byte[] pend = new byte[0];
	private int pendOff;

	public Mp3Source(Path p) throws IOException {
		path = p;
		try {
			open();
			Header h = bs.readFrame();
			if (h == null) throw new IOException("Empty or invalid MP3 file");
			int ch = h.mode() == Header.SINGLE_CHANNEL ? 1 : 2;
			fmt = new AudioFormat(h.frequency(), 16, ch, true, false);
			durMs = (long) h.total_ms((int) Files.size(p));
			open();
		} catch (BitstreamException e) { close(); throw new IOException("Invalid MP3: " + e.getMessage(), e); }
	}
	private void open() throws IOException {
		closeStream();
		in = new BufferedInputStream(Files.newInputStream(path), 64 * 1024);
		bs = new Bitstream(in);
		dec = new Decoder();
		pend = new byte[0]; pendOff = 0;
	}
	private void closeStream() {
		try { if (bs != null) bs.close(); } catch (BitstreamException ignored) {}
		try { if (in != null) in.close(); } catch (IOException ignored) {}
	}
	@Override public AudioFormat format() { return fmt; }
	@Override public long durationMs() { return durMs; }

	@Override public int read(byte[] b) throws IOException {
		while (pendOff >= pend.length) if (!decodeNext()) return -1;
		int n = Math.min(b.length, pend.length - pendOff);
		System.arraycopy(pend, pendOff, b, 0, n);
		pendOff += n;
		return n;
	}
	private boolean decodeNext() throws IOException {
		try {
			Header h = bs.readFrame();
			if (h == null) return false;
			try {
				SampleBuffer sb = (SampleBuffer) dec.decodeFrame(h, bs);
				short[] s = sb.getBuffer(); int len = sb.getBufferLength();
				byte[] out = new byte[len * 2];
				for (int i = 0; i < len; i++) { out[2 * i] = (byte) s[i]; out[2 * i + 1] = (byte) (s[i] >> 8); }
				pend = out; pendOff = 0;
			} catch (DecoderException e) { pend = new byte[0]; pendOff = 0; }   // skip a corrupt frame
			finally { bs.closeFrame(); }
			return true;
		} catch (BitstreamException e) {
			if (e.getErrorCode() == BitstreamErrors.STREAM_EOF) return false;
			throw new IOException("MP3 decode error: " + e.getMessage(), e);
		}
	}
	@Override public void seek(long ms) throws IOException {
		open();
		try {
			float t = 0;
			while (t < ms) { Header h = bs.readFrame(); if (h == null) break; t += h.ms_per_frame(); bs.closeFrame(); }
		} catch (BitstreamException e) { throw new IOException("MP3 seek failed: " + e.getMessage(), e); }
	}
	@Override public void close() { closeStream(); }
}
