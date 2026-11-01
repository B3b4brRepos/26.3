package com.ingamemusic.model;

/** A playable track: NetEase (id "ne:<songId>", link resolved when played), a plain audioUrl, or a local file. */
public record Track(String id, String title, String artist, String album, long durationMs,
                    String artUrl, String audioUrl, String localPath) {
	public boolean isLocal() { return localPath != null; }
	public boolean isNetease() { return id != null && id.startsWith("ne:"); }
	public long neteaseId() { try { return Long.parseLong(id.substring(3)); } catch (Exception e) { return 0L; } }
}
