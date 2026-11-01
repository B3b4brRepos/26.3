package com.ingamemusic;

import net.fabricmc.loader.api.FabricLoader;
import java.io.IOException;
import java.nio.file.*;

public final class Dirs {
	private static Path root, music, lyrics, cache;
	public static void init() {
		root = FabricLoader.getInstance().getGameDir().resolve("ingamemusic");
		music = root.resolve("music"); lyrics = root.resolve("lyrics"); cache = root.resolve("cache");
		try { Files.createDirectories(music); Files.createDirectories(lyrics); Files.createDirectories(cache); }
		catch (IOException e) { IngameMusic.LOG.error("Cannot create dirs", e); }
	}
	public static Path root() { return root; }
	public static Path music() { return music; }
	public static Path lyrics() { return lyrics; }
	public static Path cache() { return cache; }
	public static String safe(String s) { return s.replaceAll("[^A-Za-z0-9._-]", "_"); }
}
