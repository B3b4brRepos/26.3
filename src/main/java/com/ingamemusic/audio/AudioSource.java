package com.ingamemusic.audio;

import java.io.Closeable;
import java.io.IOException;
import javax.sound.sampled.AudioFormat;

/** Decoded PCM_SIGNED 16-bit little-endian stream. */
public interface AudioSource extends Closeable {
	AudioFormat format();
	/** Fills buf (length is a multiple of frame size) with whole frames. Returns bytes read or -1 at EOF. */
	int read(byte[] buf) throws IOException;
	void seek(long ms) throws IOException;
	/** Duration in ms or -1 if unknown. */
	long durationMs();
    @Override void close();

	static AudioSource open(java.nio.file.Path p) throws IOException {
		String n = p.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
		if (n.endsWith(".mp3")) return new Mp3Source(p);
		if (n.endsWith(".ogg")) return new OggSource(p);
		if (n.endsWith(".wav") || n.endsWith(".wave") || n.endsWith(".aif") || n.endsWith(".aiff") || n.endsWith(".au")) return new WavSource(p);
		throw new IOException("Unsupported audio format (use MP3, OGG or WAV): " + p.getFileName());
	}
}
