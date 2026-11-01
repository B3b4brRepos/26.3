package com.ingamemusic.player;

import com.google.gson.*;
import com.ingamemusic.Dirs;
import com.ingamemusic.IngameMusic;
import com.ingamemusic.model.Track;
import java.nio.file.*;
import java.util.*;

/** Persistent playlists + recently played. */
public final class Library {
	public Map<String, List<Track>> playlists = new LinkedHashMap<>();
	public List<Track> recents = new ArrayList<>();
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static Path file() { return Dirs.root().resolve("library.json"); }

	public static Library load() {
		Library l = null;
		try { if (Files.exists(file())) l = GSON.fromJson(Files.readString(file()), Library.class); }
		catch (Exception e) { IngameMusic.LOG.warn("Bad library.json, starting fresh", e); }
		if (l == null) l = new Library();
		if (l.playlists == null) l.playlists = new LinkedHashMap<>();
		if (l.recents == null) l.recents = new ArrayList<>();
		l.playlists.putIfAbsent("Favorites", new ArrayList<>());
		return l;
	}
	public void saveAsync() {
		String json = GSON.toJson(this);
		IngameMusic.SAVE.execute(() -> { try { Files.writeString(file(), json); } catch (Exception e) { IngameMusic.LOG.warn("Library save failed", e); } });
	}
}
