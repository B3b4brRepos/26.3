package com.ingamemusic.lyrics;

public final class Lyrics {
	public final boolean synced;
	public final long[] times;   // null when !synced
	public final String[] lines;
	public Lyrics(boolean synced, long[] times, String[] lines) { this.synced = synced; this.times = times; this.lines = lines; }
	/** Index of the last line whose time <= ms, or -1. Only meaningful if synced. */
	public int indexAt(long ms) {
		if (!synced) return -1;
		int lo = 0, hi = times.length - 1, ans = -1;
		while (lo <= hi) { int mid = (lo + hi) >>> 1; if (times[mid] <= ms) { ans = mid; lo = mid + 1; } else hi = mid - 1; }
		return ans;
	}
}
