package com.ingamemusic.lyrics;

import com.google.gson.*;
import com.ingamemusic.Dirs;
import com.ingamemusic.IngameMusic;
import com.ingamemusic.model.Track;
import com.ingamemusic.net.HttpUtil;
import com.ingamemusic.net.NeteaseApi;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.CompletableFuture;

/** Lyrics from: user files (.lrc/.txt next to a local track, or lyrics/<id>.lrc), then NetEase (for NetEase songs), then LRCLIB (https://lrclib.net). */
public final class LyricsService {
	/** Completes with null when nothing was found; completes exceptionally on network errors. */
	public CompletableFuture<Lyrics> fetch(Track t) {
		return CompletableFuture.supplyAsync(() -> readUserFile(t), IngameMusic.IO).thenCompose(l -> {
			if (l != null) return CompletableFuture.completedFuture(l);
			return remote(t);
		});
	}
	private Lyrics readUserFile(Track t) {
		try {
			if (t.isLocal()) {
				Path p = Path.of(t.localPath()); String n = p.getFileName().toString();
				String base = n.contains(".") ? n.substring(0, n.lastIndexOf('.')) : n;
				for (String ext : new String[]{".lrc", ".txt"}) {
					Path f = p.resolveSibling(base + ext);
					if (Files.exists(f)) { Lyrics l = LrcParser.parse(Files.readString(f)); if (l != null) return l; }
				}
			}
			Path f = Dirs.lyrics().resolve(Dirs.safe(t.id()) + ".lrc");
			if (Files.exists(f)) return LrcParser.parse(Files.readString(f));
		} catch (Exception e) { IngameMusic.LOG.warn("Reading lyric file failed", e); }
		return null;
	}
	private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

	private CompletableFuture<Lyrics> remote(Track t) {
		if (!t.isNetease()) return lrclib(t);
		return CompletableFuture.supplyAsync(() -> LrcParser.parse(NeteaseApi.lyric(t.neteaseId())), IngameMusic.IO)
			.thenCompose(l -> l != null ? CompletableFuture.completedFuture(l) : lrclib(t));
	}

	private CompletableFuture<Lyrics> lrclib(Track t) {
		boolean knownArtist = t.artist() != null && !t.artist().isBlank() && !t.artist().equals("Unknown");
		long secs = t.durationMs() / 1000;
		CompletableFuture<Lyrics> exact = !knownArtist ? CompletableFuture.completedFuture(null) :
			HttpUtil.get("https://lrclib.net/api/get?track_name=" + enc(t.title()) + "&artist_name=" + enc(t.artist())
				+ (secs > 0 ? "&duration=" + secs : "")).thenApply(r -> r.status() == 200 ? fromObject(JsonParser.parseString(r.body()).getAsJsonObject()) : (Lyrics) null)
				.exceptionally(e -> null);
		return exact.thenCompose(l -> {
			if (l != null) return CompletableFuture.completedFuture(l);
			String q = knownArtist ? "track_name=" + enc(t.title()) + "&artist_name=" + enc(t.artist()) : "q=" + enc(t.title());
			return HttpUtil.get("https://lrclib.net/api/search?" + q).thenApply(r -> {
				if (r.status() != 200) throw new IllegalStateException("Lyrics service HTTP " + r.status());
				return pick(JsonParser.parseString(r.body()).getAsJsonArray(), secs);
			});
		});
	}
	private static Lyrics pick(JsonArray arr, long secs) {
		JsonObject best = null; double bestScore = -1;
		for (JsonElement e : arr) {
			JsonObject o = e.getAsJsonObject();
			double sc = 0;
			if (has(o, "syncedLyrics")) sc += 10; else if (has(o, "plainLyrics")) sc += 5; else continue;
			if (secs > 0 && o.has("duration") && !o.get("duration").isJsonNull()) {
				double d = Math.abs(o.get("duration").getAsDouble() - secs);
				if (d > 8) continue;           // different version of the song
				sc += 3 - Math.min(3, d);
			}
			if (sc > bestScore) { bestScore = sc; best = o; }
		}
		return best == null ? null : fromObject(best);
	}
	private static boolean has(JsonObject o, String k) { return o.has(k) && !o.get(k).isJsonNull() && !o.get(k).getAsString().isBlank(); }
	private static Lyrics fromObject(JsonObject o) {
		if (has(o, "syncedLyrics")) { Lyrics l = LrcParser.parse(o.get("syncedLyrics").getAsString()); if (l != null) return l; }
		if (has(o, "plainLyrics")) return LrcParser.parse(o.get("plainLyrics").getAsString());
		return null;
	}
}
