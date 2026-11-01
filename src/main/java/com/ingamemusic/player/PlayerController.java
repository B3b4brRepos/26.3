package com.ingamemusic.player;

import com.ingamemusic.IngameMusic;
import com.ingamemusic.MusicConfig;
import com.ingamemusic.audio.AudioEngine;
import com.ingamemusic.lyrics.*;
import com.ingamemusic.model.Track;
import com.ingamemusic.net.HttpUtil;
import java.util.*;
import net.minecraft.client.Minecraft;

/** Queue / playlists / shuffle / repeat / lyrics state. All mutating methods are called from the client thread. */
public final class PlayerController implements AudioEngine.Listener {
	public enum Repeat { OFF, ALL, ONE }
	public enum LyricsState { IDLE, LOADING, READY, NOT_FOUND, ERROR }

	public final AudioEngine engine = new AudioEngine();
	public final Library library = Library.load();
	public final List<Track> queue = new ArrayList<>();
	public int index = -1;
	public Repeat repeat;
	public boolean shuffle;
	public Track current;
	public volatile Lyrics lyrics;
	public volatile LyricsState lyricsState = LyricsState.IDLE;
	public volatile String lyricsError = "";

	private final MusicConfig cfg;
	private final LyricsService lyricsSvc = new LyricsService();
	private final Deque<Integer> history = new ArrayDeque<>();
	private final Random rnd = new Random();
	private int lyricToken;

	public PlayerController(MusicConfig cfg) {
		this.cfg = cfg;
		engine.setVolume(cfg.volume);
		engine.setListener(this);
		shuffle = cfg.shuffle;
		try { repeat = Repeat.valueOf(cfg.repeat); } catch (Exception e) { repeat = Repeat.OFF; }
	}

	// ---- playback -------------------------------------------------------------------------------------------
	public void playIndex(int i) {
		if (i < 0 || i >= queue.size()) return;
		index = i; current = queue.get(i);
		engine.play(current, 0);
		library.recents.removeIf(t -> t.id().equals(current.id()));
		library.recents.add(0, current);
		while (library.recents.size() > 30) library.recents.remove(library.recents.size() - 1);
		library.saveAsync();
		loadLyrics(current);
	}
	public void playNow(Track t) {
		for (int i = 0; i < queue.size(); i++) if (queue.get(i).id().equals(t.id())) { playIndex(i); return; }
		queue.add(t); playIndex(queue.size() - 1);
	}
	public void playList(List<Track> list, int start) {
		if (list.isEmpty()) return;
		queue.clear(); queue.addAll(list); history.clear(); playIndex(Math.max(0, Math.min(start, list.size() - 1)));
	}
	public void enqueue(Track t) { boolean idle = current == null; queue.add(t); if (idle) playIndex(queue.size() - 1); }
	public void togglePause() {
		AudioEngine.State s = engine.state();
		if ((s == AudioEngine.State.IDLE || s == AudioEngine.State.ERROR) && current != null) engine.play(current, 0);
		else if (current == null && !queue.isEmpty()) playIndex(Math.max(0, index));
		else if (engine.isPaused()) engine.resume(); else engine.pause();
	}
	public void seek(long ms) { engine.seek(ms); }
	public void next(boolean auto) {
		if (queue.isEmpty()) return;
		if (auto && repeat == Repeat.ONE) { playIndex(index); return; }
		if (shuffle && queue.size() > 1) {
			int n; do { n = rnd.nextInt(queue.size()); } while (n == index);
			history.push(index); playIndex(n); return;
		}
		int n = index + 1;
		if (n >= queue.size()) { if (repeat == Repeat.ALL) n = 0; else { if (auto) engine.stop(); return; } }
		playIndex(n);
	}
	public void previous() {
		if (queue.isEmpty()) return;
		if (engine.position() > 3000) { engine.seek(0); return; }
		if (shuffle && !history.isEmpty()) { playIndex(history.pop()); return; }
		playIndex(Math.max(0, index - 1));
	}
	public void removeFromQueue(int i) {
		if (i < 0 || i >= queue.size()) return;
		boolean wasCurrent = i == index;
		queue.remove(i); history.clear();
		if (wasCurrent) { if (i < queue.size()) playIndex(i); else { engine.stop(); current = null; index = -1; clearLyrics(); } }
		else if (i < index) index--;
	}
	public void clearQueue() { queue.clear(); history.clear(); index = -1; current = null; engine.stop(); clearLyrics(); }
	public void setVolume(float v) { engine.setVolume(v); cfg.volume = engine.volume(); cfg.save(); }
	public void toggleShuffle() { shuffle = !shuffle; cfg.shuffle = shuffle; cfg.save(); }
	public void cycleRepeat() { repeat = Repeat.values()[(repeat.ordinal() + 1) % 3]; cfg.repeat = repeat.name(); cfg.save(); }

	// ---- playlists ------------------------------------------------------------------------------------------
	public String createPlaylist() {
		int n = library.playlists.size(); String name;
		do { name = "Playlist " + (++n); } while (library.playlists.containsKey(name));
		library.playlists.put(name, new ArrayList<>()); library.saveAsync(); return name;
	}
	public void deletePlaylist(String name) { if (!"Favorites".equals(name)) { library.playlists.remove(name); library.saveAsync(); } }
	public void addToPlaylist(String name, Track t) {
		List<Track> l = library.playlists.get(name);
		if (l != null && l.stream().noneMatch(x -> x.id().equals(t.id()))) { l.add(t); library.saveAsync(); }
	}
	public void removeFromPlaylist(String name, int i) {
		List<Track> l = library.playlists.get(name);
		if (l != null && i >= 0 && i < l.size()) { l.remove(i); library.saveAsync(); }
	}

	// ---- lyrics ---------------------------------------------------------------------------------------------
	private void clearLyrics() { lyricToken++; lyrics = null; lyricsState = LyricsState.IDLE; }
	private void loadLyrics(Track t) {
		int token = ++lyricToken; lyrics = null; lyricsState = LyricsState.LOADING; lyricsError = "";
		Minecraft mc = Minecraft.getInstance();
		lyricsSvc.fetch(t).whenComplete((l, ex) -> mc.execute(() -> {
			if (token != lyricToken) return;           // track changed meanwhile
			if (ex != null) { lyricsState = LyricsState.ERROR; lyricsError = HttpUtil.friendly(ex); }
			else if (l == null) lyricsState = LyricsState.NOT_FOUND;
			else { lyrics = l; lyricsState = LyricsState.READY; }
		}));
	}
	/** Playback position used to pick the current lyric line (includes the manual sync offset). */
	public long lyricPosition() { return engine.position() + cfg.lyricsOffsetMs; }

	// ---- engine callbacks (audio thread) --------------------------------------------------------------------
	@Override public void onEnded(Track t) {
		Minecraft.getInstance().execute(() -> { if (current != null && current.id().equals(t.id())) next(true); });
	}
	@Override public void onError(Track t, String msg) { /* surfaced through engine.state()/error() in the UI */ }

	public void onDisconnect() { if (engine.state() == AudioEngine.State.PLAYING) engine.pause(); }
	public void shutdown() { engine.shutdown(); library.saveAsync(); }
}
