package com.ingamemusic.gui;

import com.ingamemusic.IngameMusic;
import com.ingamemusic.MusicConfig;
import com.ingamemusic.Dirs;
import com.ingamemusic.audio.AudioEngine;
import com.ingamemusic.lyrics.Lyrics;
import com.ingamemusic.model.Track;
import com.ingamemusic.net.HttpUtil;
import com.ingamemusic.net.NeteaseApi;
import com.ingamemusic.player.PlayerController;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.function.IntConsumer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import com.mojang.blaze3d.platform.InputConstants;

/**
 * Full player. Immediate-mode UI: every frame draws widgets and registers click regions, so layout and hit-testing
 * can never get out of sync. Everything is laid out in a fixed 320x212 space that is scaled to fit any window.
 * v1.1: animated open/close, sliding tab pill, smooth hover + list scrolling, drawn transport icons, volume slider,
 * seek tooltip, live visualizer and soft UI sounds.
 */
public final class MusicScreen extends Screen {
	private enum Tab {
		LYRICS("Lyrics"), SEARCH("Search"), QUEUE("Queue"), LISTS("Lists"), RECENT("Recent"), LOCAL("Local"), OPTS("Opts");
		final String label; Tab(String l) { label = l; }
	}
	/** snd: 0 = silent, 1 = click, 2 = tab sound. */
	private record Hit(int x, int y, int w, int h, int snd, Runnable run) {
		boolean in(double mx, double my) { return mx >= x && mx < x + w && my >= y && my < y + h; }
		boolean near(double mx, double my) { return mx >= x - 5 && mx < x + w + 5 && my >= y - 5 && my < y + h + 5; }
	}
	private static final int PW = 320, PH = 212, CX = 8, CY = 71, CW = 304, ROW_H = 20, ROWS = 4;
	private static final int BX0 = 38, BX1 = PW - 38, BY = 176;
	private static final int VX0 = 28, VW = 44, VY = 196;

	// state that survives closing the screen
	private static Tab tab = Tab.SEARCH;
	private static List<Track> results = new ArrayList<>();
	private static String searchMsg = "Type a title or artist and press Enter";
	private static boolean searching;
	private static int searchToken;
	private static String searchText = "";
	private static List<Track> localFiles = new ArrayList<>();
	private static String selectedList = "Favorites";

	private final PlayerController pc = IngameMusic.player;
	private final MusicConfig cfg = IngameMusic.config;
	private final List<Hit> hits = new ArrayList<>();
	private final Map<Integer, Float> anims = new HashMap<>();
	private final Set<Integer> hovered = new HashSet<>();
	private final float[] vis = new float[AudioEngine.BANDS];
	private EditBox searchField, idField;
	private float fit = 1, left, top;
	private float sx, sy, ts = 1;           // animated screen transform (for scissor)
	private int lmx, lmy, listScroll;
	private float scrollF, dt, age, open, tabPos = -1, tabFade = 1, dispFrac;
	private boolean closing, dragBar, dragVol; private double dragFrac;
	private Hit pressHit; private double pressLy, scrollStart; private boolean touchScroll; private int listMax;
	private float lyricScroll; private double manualLine; private long lastManual; private long lastFrame = System.nanoTime();
	private Lyrics wrappedFor; private int wrappedW; private List<FormattedCharSequence> wrapped = List.of();

	public MusicScreen() { super(Component.literal("Music")); UiFx.open(); }

	@Override protected void init() {
		fit = Math.max(0.3f, Math.min(Math.max(0.4f, Math.min(1.5f, cfg.uiScale)), Math.min((width - 6f) / PW, (height - 8f) / PH)));
		left = Math.max(2f, Math.min(width - PW * fit - 2f, (width - PW * fit) / 2f + cfg.uiOffsetX)); top = 3f;   // floats at the very top, centred
		searchField = new EditBox(font, CX + 6, CY + 3, CW - 44 - 12, 9, Component.empty());
		searchField.setBordered(false);
		searchField.setMaxLength(80); searchField.setHint(Component.literal("Search NetEase: title or artist")); searchField.setValue(searchText);
		idField = new EditBox(font, CX + 6, CY + 66, 240 - 12, 9, Component.empty());
		idField.setBordered(false);
		idField.setMaxLength(4000); idField.setHint(Component.literal("Paste MUSIC_U cookie"));
		if (tab == Tab.SEARCH) searchField.setFocused(true);
		if (tab == Tab.LOCAL) scanLocal();
	}
	@Override public boolean isPauseScreen() { return false; }

	@Override public void onClose() {
		if (closing) return;
		closing = true; UiFx.close();
	}
	@Override public void tick() {
		super.tick();
		if (closing && open <= 0f) super.onClose();
	}

	private EditBox activeField() { return tab == Tab.SEARCH ? searchField : tab == Tab.OPTS ? idField : null; }
	/** Background rectangle {x, y, w, h} of the active text field, in panel coordinates. */
	private int[] fieldBox() { return tab == Tab.SEARCH ? new int[] {CX, CY, CW - 44, 13} : new int[] {CX, CY + 63, 240, 13}; }

	private void setTab(Tab t) {
		if (t == tab) return;
		tab = t; listScroll = 0; scrollF = 0; tabFade = 0f; hovered.clear();
		searchField.setFocused(t == Tab.SEARCH); idField.setFocused(false);
		if (t == Tab.LOCAL) scanLocal();
	}

	// ---- animation helpers ----------------------------------------------------------------------------------
	private int key(int a, int b, int c) { return (a * 73856093) ^ (b * 19349663) ^ (c * 83492791) ^ (tab.ordinal() * 1337); }
	private float anim(int k, float target, float speed) {
		float v = anims.getOrDefault(k, 0f);
		v += (target - v) * Math.min(1f, dt * speed);
		if (Math.abs(target - v) < 0.002f) v = target;
		anims.put(k, v);
		return v;
	}
	private float hover(int k, boolean hov) {
		if (hov) { if (hovered.add(k) && age > 0.5f && open >= 0.99f && !dragBar && !dragVol) UiFx.tick(); }
		else hovered.remove(k);
		return anim(k, hov ? 1f : 0f, 16f);
	}
	/** Clip to a panel-space rectangle (the pose stack already carries the animated scale and position). */
	private void scissor(GuiGraphicsExtractor g, int x, int y, int w, int h) { g.enableScissor(x, y, x + w, y + h); }

	// ---------------------------------------------------------------------------------------------------------
	@Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
		long now = System.nanoTime(); dt = Math.min(0.1f, (now - lastFrame) / 1e9f); lastFrame = now; age += dt;
		open = UiFx.clamp01(open + (closing ? -dt : dt) / 0.30f);
		float e = UiFx.easeOut(open);
		hits.clear();
		lmx = (int) ((mx - left) / fit); lmy = (int) ((my - top) / fit);

		if (cfg.glassBlur) extractBlurredBackground(g);
		int dim = (int) (0x30 * e);
		if (dim > 0) g.fill(0, 0, width, height, dim << 24);

		float s = 0.94f + 0.06f * e;
		ts = fit * s; sx = left + PW * fit / 2f; sy = top - (1f - e) * 16f;
		var m = g.pose(); m.pushMatrix(); m.translate(sx, sy); m.scale(ts, ts); m.translate(-PW / 2f, 0);
		try {
			Gfx.A = e;
			Gfx.glassPanel(g, 0, 0, PW, PH, 10);
			Gfx.gradient(g, 12, 2, PW - 12, 54, 0x1C4ADE80, 0x004ADE80);
			header(g);
			tabs(g);
			tabFade = UiFx.clamp01(tabFade + dt / 0.2f);
			Gfx.A = e * UiFx.easeOut(tabFade);
			switch (tab) {
				case LYRICS -> lyricsTab(g, dt);
				case SEARCH -> searchTab(g);
				case QUEUE -> queueTab(g);
				case LISTS -> listsTab(g);
				case RECENT -> recentTab(g);
				case LOCAL -> localTab(g);
				case OPTS -> optsTab(g);
			}
			Gfx.A = e;
			progress(g);
			controls(g);
		} finally {
			Gfx.A = 1f;
			m.popMatrix();
		}
	}

	private boolean inside(int x, int y, int w, int h) { return !closing && lmx >= x && lmx < x + w && lmy >= y && lmy < y + h; }

	private void btn(GuiGraphicsExtractor g, int x, int y, int w, int h, String label, boolean active, Runnable r) { btnX(g, x, y, w, h, label, active, 1, true, r); }
	private void btnX(GuiGraphicsExtractor g, int x, int y, int w, int h, String label, boolean active, int snd, boolean hittable, Runnable r) {
		boolean hov = hittable && inside(x, y, w, h);
		float hv = hover(key(x, y, w), hov);
		Gfx.glassBtn(g, x, y, w, h, active, hv);
		int tc = active ? 0xFFFFFFFF : UiFx.mix(0xFFC8D2CC, 0xFFFFFFFF, hv);
		text(g, label, x + (w - font.width(label)) / 2, y + (h - 8) / 2 + 1, tc);
		if (hittable) hits.add(new Hit(x, y, w, h, snd, r));
	}
	private void text(GuiGraphicsExtractor g, String s, int x, int y, int color) {
		int c = Gfx.a(color); if ((c >>> 24) < 8) return;
		g.text(font, s, x, y, c, false);
	}
	private void center(GuiGraphicsExtractor g, String s, int cx, int y, int color) { text(g, s, cx - font.width(s) / 2, y, color); }
	private String trim(String s, int w) { return font.plainSubstrByWidth(s, w); }

	/** Text that scrolls horizontally when it is wider than w. */
	private void marquee(GuiGraphicsExtractor g, String s, int x, int y, int w, int color) {
		int tw = font.width(s);
		if (tw <= w) { text(g, s, x, y, color); return; }
		int gap = 28; float off = (age * 22f) % (tw + gap);
		scissor(g, x, y - 1, w, 10);
		text(g, s, x - (int) off, y, color);
		text(g, s, x - (int) off + tw + gap, y, color);
		g.disableScissor();
	}

	private void header(GuiGraphicsExtractor g) {
		Track t = pc.current;
		boolean playing = pc.engine.state() == AudioEngine.State.PLAYING;
		float glow = anim(9001, playing ? 1f : 0f, 5f);
		if (glow > 0.01f) {
			float pulse = 0.55f + 0.45f * (float) Math.sin(age * 3.2f);
			Gfx.round(g, 6, 6, 44, 44, 7, UiFx.withAlpha(Gfx.GREEN, 0.22f * glow * pulse));
		}
		Identifier art = t == null ? null : IngameMusic.art.get(t.artUrl());
		if (art != null) g.blit(RenderPipelines.GUI_TEXTURED, art, 8, 8, 0f, 0f, 40, 40, 64, 64, 64, 64);
		else { Gfx.round(g, 8, 8, 40, 40, 5, Gfx.TRACK); center(g, "\u266A", 28, 24, Gfx.DIM); }
		if (t == null) text(g, "Nothing playing", 54, 10, Gfx.TEXT); else marquee(g, t.title(), 54, 10, 200, Gfx.TEXT);
		text(g, t == null ? "Pick a song from Search or Local" : trim(t.artist() + (t.album().isBlank() ? "" : " \u2014 " + t.album()), 200), 54, 21, Gfx.DIM);
		String st; int col = Gfx.GREEN;
		switch (pc.engine.state()) {
			case LOADING -> st = "Loading" + ".".repeat(1 + (int) (age * 2.5f) % 3);
			case PLAYING -> st = "Playing";
			case PAUSED -> st = "Paused";
			case ERROR -> { st = trim("Error: " + pc.engine.error(), 200); col = Gfx.RED; }
			default -> { st = t == null ? "" : "Stopped"; col = Gfx.DIM; }
		}
		text(g, st, 54, 34, col);

		// live visualizer
		for (int i = 0; i < vis.length; i++) {
			vis[i] += (pc.engine.band(i) - vis[i]) * Math.min(1f, dt * 12f);
			int bh = 2 + (int) (Math.min(1f, vis[i]) * 24);
			int bx = 262 + i * 5;
			Gfx.fill(g, bx, 46 - bh, bx + 3, 46, UiFx.withAlpha(UiFx.mix(Gfx.GREEN_DK, Gfx.GREEN, vis[i]), 0.15f + 0.85f * glow));
		}
		btnX(g, PW - 20, 6, 14, 12, "x", false, 1, true, this::onClose);
	}

	private void tabs(GuiGraphicsExtractor g) {
		Tab[] all = Tab.values(); int tw = CW / all.length, idx = tab.ordinal();
		Gfx.round(g, CX, 54, tw * all.length, 14, 6, UiFx.withAlpha(0xFFFFFFFF, 0.07f));
		if (tabPos < 0) tabPos = idx;
		tabPos += (idx - tabPos) * Math.min(1f, dt * 14f);
		Gfx.glassBtn(g, CX + Math.round(tabPos * tw) + 1, 55, tw - 2, 12, true, 0.3f);
		for (int i = 0; i < all.length; i++) {
			Tab t = all[i]; int x = CX + i * tw;
			float hv = hover(key(x, 54, 9), inside(x, 54, tw, 14));
			int col = t == tab ? 0xFFFFFFFF : UiFx.mix(0xFF9AA59F, 0xFFFFFFFF, hv);
			center(g, t.label, x + tw / 2, 58, col);
			hits.add(new Hit(x, 54, tw, 14, 2, () -> setTab(t)));
		}
	}

	/** Rounded input background with an animated focus border. */
	private void fieldBox(GuiGraphicsExtractor g, int[] b, boolean focused) {
		float f = anim(key(b[0], b[1], 77), focused ? 1f : 0f, 12f);
		Gfx.glassInset(g, b[0], b[1], b[2], b[3], 5, f);
	}

	// ---- lyrics tab -----------------------------------------------------------------------------------------
	private void lyricsTab(GuiGraphicsExtractor g, float dt) {
		int ay = CY + 14, ah = 82;
		Lyrics l = pc.lyrics;
		if (!cfg.lyricsOn) { center(g, "Lyrics are turned off", PW / 2, CY + 40, Gfx.DIM); return; }
		if (pc.current == null) { center(g, "Nothing playing", PW / 2, CY + 40, Gfx.DIM); return; }
		switch (pc.lyricsState) {
			case LOADING -> { center(g, "Searching lyrics\u2026", PW / 2, CY + 40, Gfx.DIM); return; }
			case ERROR -> { center(g, "Lyrics error:", PW / 2, CY + 32, Gfx.RED); center(g, trim(pc.lyricsError, 290), PW / 2, CY + 44, Gfx.DIM); return; }
			case NOT_FOUND -> {
				center(g, "No lyrics found for this track", PW / 2, CY + 30, Gfx.DIM);
				center(g, trim("Put a .lrc file in ingamemusic/lyrics/" + Dirs.safe(pc.current.id()) + ".lrc", 296), PW / 2, CY + 44, 0xFF66706A);
				return;
			}
			default -> { if (l == null) return; }
		}
		// toolbar: sync state + manual offset
		text(g, l.synced ? "Synced" : "Unsynced (no timestamps)", CX, CY + 2, l.synced ? Gfx.GREEN : Gfx.DIM);
		btn(g, PW - 8 - 112, CY, 14, 11, "-", false, () -> { cfg.lyricsOffsetMs -= 250; cfg.save(); });
		center(g, String.format("Offset %+.2fs", cfg.lyricsOffsetMs / 1000f), PW - 8 - 56, CY + 2, Gfx.TEXT);
		btn(g, PW - 8 - 14, CY, 14, 11, "+", false, () -> { cfg.lyricsOffsetMs += 250; cfg.save(); });
		if (!l.synced) { plainLyrics(g, l, ay, ah); return; }

		int idx = l.indexAt(pc.lyricPosition());
		boolean manual = System.currentTimeMillis() - lastManual < 3000;
		float target = manual ? (float) manualLine : Math.max(0, idx);
		lyricScroll += (target - lyricScroll) * Math.min(1f, dt * 9f);
		int lh = 12, cy = ay + (ah - lh) / 2;
		scissor(g, CX, ay, CW, ah);
		int first = Math.max(0, (int) Math.floor(lyricScroll - 4)), last = Math.min(l.lines.length - 1, (int) Math.ceil(lyricScroll + 4));
		for (int i = first; i <= last; i++) {
			int y = cy + Math.round((i - lyricScroll) * lh);
			if (y < ay - lh || y > ay + ah) continue;
			String s = l.lines[i].isEmpty() ? "\u266A" : trim(l.lines[i], CW - 12);
			boolean cur = i == idx;
			float c = anim(key(i, 5, 5), cur ? 1f : 0f, 10f);
			if (c > 0.02f) Gfx.round(g, CX, y - 1, CW, lh, 3, UiFx.withAlpha(0xFF14301F, c));
			int dist = Math.abs(i - Math.round(lyricScroll)); int a = Math.max(0x50, 0xE0 - dist * 0x38);
			center(g, s, PW / 2, y + 1, UiFx.mix((a << 24) | 0x9AA39E, Gfx.GREEN, c));
			final int line = i; hits.add(new Hit(CX, Math.max(ay, y - 1), CW, lh, 1, () -> pc.seek(Math.max(0, l.times[line] - cfg.lyricsOffsetMs))));
		}
		g.disableScissor();
	}
	private void plainLyrics(GuiGraphicsExtractor g, Lyrics l, int ay, int ah) {
		if (wrappedFor != l || wrappedW != CW - 8) {
			wrappedFor = l; wrappedW = CW - 8; List<FormattedCharSequence> w = new ArrayList<>();
			for (String s : l.lines) { if (s.isEmpty()) w.add(FormattedCharSequence.EMPTY); else w.addAll(font.split(Component.literal(s), CW - 8)); }
			wrapped = w;
		}
		int lh = 10, vis = ah / lh, max = Math.max(0, wrapped.size() - vis);
		listScroll = Math.max(0, Math.min(max, listScroll));
		scissor(g, CX, ay, CW, ah);
		int col = Gfx.a(0xFFC4CCC8);
		if ((col >>> 24) >= 8) for (int i = 0; i < vis && listScroll + i < wrapped.size(); i++) g.text(font, wrapped.get(listScroll + i), CX + 4, ay + i * lh, col, false);
		g.disableScissor();
	}

	// ---- search tab -----------------------------------------------------------------------------------------
	private void searchTab(GuiGraphicsExtractor g) {
		fieldBox(g, fieldBox(), searchField.isFocused());
		searchField.extractRenderState(g, lmx, lmy, 0f);
		btn(g, CX + CW - 40, CY, 40, 13, "Search", false, this::runSearch);
		int y0 = CY + 16;
		if (results.isEmpty() || searching) {
			if (searching) {
				center(g, "Searching", PW / 2, y0 + 26, Gfx.DIM);
				for (int k = 0; k < 3; k++) {
					float ph = (float) Math.sin(age * 6f - k * 0.9f) * 0.5f + 0.5f;
					Gfx.circle(g, PW / 2 - 8 + k * 8, y0 + 46, 2, UiFx.withAlpha(Gfx.GREEN, 0.3f + 0.7f * ph));
				}
				return;
			}
			center(g, trim(searchMsg, 296), PW / 2, y0 + 34, searchMsg.startsWith("Type") || searchMsg.startsWith("No results") ? Gfx.DIM : Gfx.RED);
			if (results.isEmpty() && searchMsg.startsWith("Type")) {
				center(g, "Source: NetEase Cloud Music", PW / 2, y0 + 48, 0xFF66706A);
				center(g, NeteaseApi.loggedIn() ? (NeteaseApi.usingBuiltIn() ? "Built-in account active" : "Logged in") : "Not logged in (Opts tab): VIP songs play as 30 s previews", PW / 2, y0 + 60, NeteaseApi.loggedIn() ? Gfx.GREEN : Gfx.DIM);
			}
			return;
		}
		rows(g, results, y0, "+", i -> pc.enqueue(results.get(i)), "*", i -> pc.addToPlaylist(selectedList, results.get(i)),
			i -> pc.playList(results, i), -1);
	}
	private void runSearch() {
		String q = searchField.getValue().trim(); searchText = searchField.getValue();
		if (q.isEmpty()) return;
		int token = ++searchToken; searching = true; results = new ArrayList<>(); listScroll = 0; scrollF = 0;
		NeteaseApi.search(q).whenComplete((r, ex) -> minecraft.execute(() -> {
			if (token != searchToken) return;
			searching = false;
			if (ex != null) searchMsg = HttpUtil.friendly(ex);
			else { results = r; if (r.isEmpty()) searchMsg = "No results for \"" + q + "\""; else UiFx.done(); }
		}));
	}

	// ---- queue / recent / lists / local ---------------------------------------------------------------------
	private void queueTab(GuiGraphicsExtractor g) {
		text(g, pc.queue.size() + " in queue", CX, CY + 3, Gfx.DIM);
		btn(g, PW - 8 - 40, CY, 40, 13, "Clear", false, pc::clearQueue);
		if (pc.queue.isEmpty()) { center(g, "Queue is empty. Use + on a song.", PW / 2, CY + 50, Gfx.DIM); return; }
		rows(g, pc.queue, CY + 16, "x", pc::removeFromQueue, null, null, pc::playIndex, pc.index);
	}
	private void recentTab(GuiGraphicsExtractor g) {
		text(g, "Recently played", CX, CY + 3, Gfx.DIM);
		btn(g, PW - 8 - 40, CY, 40, 13, "Clear", false, () -> { pc.library.recents.clear(); pc.library.saveAsync(); });
		List<Track> r = pc.library.recents;
		if (r.isEmpty()) { center(g, "Nothing played yet", PW / 2, CY + 50, Gfx.DIM); return; }
		rows(g, r, CY + 16, "+", i -> pc.enqueue(r.get(i)), "*", i -> pc.addToPlaylist(selectedList, r.get(i)), i -> pc.playList(r, i), -1);
	}
	private void listsTab(GuiGraphicsExtractor g) {
		List<String> names = new ArrayList<>(pc.library.playlists.keySet());
		if (!pc.library.playlists.containsKey(selectedList)) selectedList = names.get(0);
		int si = names.indexOf(selectedList);
		btn(g, CX, CY, 12, 13, "<", false, () -> { selectedList = names.get((si + names.size() - 1) % names.size()); listScroll = 0; scrollF = 0; });
		text(g, trim(selectedList, 110), CX + 16, CY + 3, Gfx.GREEN);
		btn(g, CX + 132, CY, 12, 13, ">", false, () -> { selectedList = names.get((si + 1) % names.size()); listScroll = 0; scrollF = 0; });
		btn(g, CX + 150, CY, 34, 13, "New", false, () -> { selectedList = pc.createPlaylist(); listScroll = 0; scrollF = 0; });
		btn(g, CX + 188, CY, 30, 13, "Del", false, () -> pc.deletePlaylist(selectedList));
		btn(g, CX + 222, CY, 40, 13, "Play", false, () -> pc.playList(pc.library.playlists.get(selectedList), 0));
		List<Track> l = pc.library.playlists.get(selectedList);
		if (l.isEmpty()) { center(g, "Empty. Use * on a song to add it to this list.", PW / 2, CY + 50, Gfx.DIM); return; }
		String name = selectedList;
		rows(g, l, CY + 16, "x", i -> pc.removeFromPlaylist(name, i), "+", i -> pc.enqueue(l.get(i)), i -> pc.playList(l, i), -1);
	}
	private void localTab(GuiGraphicsExtractor g) {
		btn(g, CX, CY, 70, 13, "Open folder", false, this::openFolder);
		btn(g, CX + 74, CY, 44, 13, "Refresh", false, this::scanLocal);
		text(g, "MP3 / OGG / WAV", CX + 126, CY + 3, Gfx.DIM);
		if (localFiles.isEmpty()) {
			if (!localMsg.isEmpty()) center(g, trim(localMsg, 296), PW / 2, CY + 28, Gfx.GREEN);
			center(g, "No files yet. Drop music into the folder,", PW / 2, CY + 40, Gfx.DIM);
			center(g, "then press Refresh. (.lrc next to a song = lyrics)", PW / 2, CY + 52, Gfx.DIM);
			return;
		}
		rows(g, localFiles, CY + 16, "+", i -> pc.enqueue(localFiles.get(i)), "*", i -> pc.addToPlaylist(selectedList, localFiles.get(i)), i -> pc.playList(localFiles, i), -1);
	}
	private static String localMsg = "";
	/** Opens the music folder where a desktop exists; otherwise shows its path (phones have no file manager hook). */
	private void openFolder() {
		Path dir = Dirs.music();
		localMsg = "Folder: " + dir.toAbsolutePath();
		try {
			if (!java.awt.GraphicsEnvironment.isHeadless() && java.awt.Desktop.isDesktopSupported()) java.awt.Desktop.getDesktop().open(dir.toFile());
		} catch (Throwable ignored) { }
	}
	private void scanLocal() {
		IngameMusic.IO.execute(() -> {
			List<Track> out = new ArrayList<>();
			try (var s = Files.walk(Dirs.music(), 3)) {
				s.filter(Files::isRegularFile).sorted().forEach(p -> {
					String n = p.getFileName().toString(), low = n.toLowerCase(Locale.ROOT);
					if (!(low.endsWith(".mp3") || low.endsWith(".ogg") || low.endsWith(".wav"))) return;
					String base = n.substring(0, n.lastIndexOf('.')), title = base, artist = "Unknown";
					int d = base.indexOf(" - ");
					if (d > 0) { artist = base.substring(0, d).trim(); title = base.substring(d + 3).trim(); }
					out.add(new Track("local:" + Dirs.music().relativize(p), title, artist, "", 0, null, null, p.toAbsolutePath().toString()));
				});
			} catch (IOException e) { IngameMusic.LOG.warn("Local scan failed", e); }
			minecraft.execute(() -> localFiles = out);
		});
	}

	// ---- options tab ----------------------------------------------------------------------------------------
	private void saveCookie() { String c = idField.getValue().trim(); if (!c.isEmpty()) NeteaseApi.saveCookie(c); idField.setValue(""); idField.setFocused(false); }

	/** Shows the QR code while a NetEase login is in progress. Scan it with the NetEase Cloud Music app. */
	private void loginPanel(GuiGraphicsExtractor g) {
		NeteaseApi.LoginState st = NeteaseApi.loginState();
		boolean[][] q = NeteaseApi.qrMatrix();
		int px = 2, qx = CX + 4, qy = CY + 2;
		if (q != null) {
			int n = q.length; px = Math.max(1, Math.min(88 / (n + 2), 3));
			Gfx.fill(g, qx - 2, qy - 2, qx + n * px + 2, qy + n * px + 2, 0xFFFFFFFF);
			for (int y = 0; y < n; y++) {
				int x = 0;
				while (x < n) {
					if (!q[y][x]) { x++; continue; }
					int x2 = x; while (x2 < n && q[y][x2]) x2++;
					Gfx.fill(g, qx + x * px, qy + y * px, qx + x2 * px, qy + (y + 1) * px, 0xFF000000);
					x = x2;
				}
			}
		} else { text(g, "Getting a code…", qx, qy + 10, Gfx.DIM); }
		int rx = qx + (q == null ? 90 : q.length * px) + 12, ry = CY + 4;
		String l1, l2 = "";
		int col = Gfx.TEXT;
		switch (st) {
			case LOADING -> l1 = "Requesting a login code…";
			case WAITING -> { l1 = "Scan with the NetEase Cloud Music app"; l2 = "Me tab > scan icon (top right)"; }
			case SCANNED -> { l1 = "Scanned! Confirm login on your phone"; col = Gfx.GREEN; }
			case SUCCESS -> { l1 = "Logged in. You can search and play now."; col = Gfx.GREEN; }
			case EXPIRED -> { l1 = "This code expired."; col = Gfx.RED; }
			case ERROR -> { l1 = "Login failed (network or NetEase busy)."; col = Gfx.RED; }
			default -> l1 = "";
		}
		text(g, trim(l1, CW - (rx - CX) - 4), rx, ry, col);
		if (!l2.isEmpty()) text(g, trim(l2, CW - (rx - CX) - 4), rx, ry + 11, Gfx.DIM);
		String file = NeteaseApi.qrFilePath();
		if (st == NeteaseApi.LoginState.WAITING && !file.isEmpty()) {
			text(g, "Same phone? Open this saved image from", rx, ry + 26, Gfx.DIM);
			text(g, "the scan screen (gallery button):", rx, ry + 37, Gfx.DIM);
			text(g, trim(file, CW - (rx - CX) - 4), rx, ry + 48, 0xFF66706A);
		}
		boolean again = st == NeteaseApi.LoginState.EXPIRED || st == NeteaseApi.LoginState.ERROR;
		boolean done = st == NeteaseApi.LoginState.SUCCESS;
		btn(g, rx, CY + 70, 70, 13, again ? "New code" : done ? "Done" : "Cancel", false, again ? NeteaseApi::startLogin : NeteaseApi::cancelLogin);
	}

	private void optsTab(GuiGraphicsExtractor g) {
		if (NeteaseApi.loginState() != NeteaseApi.LoginState.IDLE) { loginPanel(g); return; }
		int y = CY + 2;
		btn(g, CX, y, 74, 13, "HUD: " + (cfg.hudVisible ? "ON" : "OFF"), cfg.hudVisible, () -> { cfg.hudVisible = !cfg.hudVisible; cfg.save(); });
		btn(g, CX + 78, y, 130, 13, "Position: " + cfg.hudAnchor.label, false, () -> { cfg.hudAnchor = cfg.hudAnchor.next(); cfg.save(); });
		btn(g, CX + 212, y, 92, 13, "Visualizer " + (cfg.hudVisualizer ? "ON" : "OFF"), cfg.hudVisualizer, () -> { cfg.hudVisualizer = !cfg.hudVisualizer; cfg.save(); });
		y += 17;
		text(g, String.format("HUD scale %.1fx", cfg.hudScale), CX, y + 3, Gfx.TEXT);
		btn(g, CX + 80, y, 14, 13, "-", false, () -> { cfg.hudScale = Math.max(0.5f, Math.round((cfg.hudScale - 0.1f) * 10) / 10f); cfg.save(); });
		btn(g, CX + 98, y, 14, 13, "+", false, () -> { cfg.hudScale = Math.min(3f, Math.round((cfg.hudScale + 0.1f) * 10) / 10f); cfg.save(); });
		btn(g, CX + 124, y, 90, 13, "Reset lyric sync", false, () -> { cfg.lyricsOffsetMs = 0; cfg.save(); });
		y += 17;
		text(g, String.format("Panel size %.2fx", cfg.uiScale), CX, y + 3, Gfx.TEXT);
		btn(g, CX + 80, y, 14, 13, "-", false, () -> { cfg.uiScale = Math.max(0.4f, Math.round((cfg.uiScale - 0.05f) * 100) / 100f); cfg.save(); resizeNow(); });
		btn(g, CX + 98, y, 14, 13, "+", false, () -> { cfg.uiScale = Math.min(1.5f, Math.round((cfg.uiScale + 0.05f) * 100) / 100f); cfg.save(); resizeNow(); });
		btn(g, CX + 124, y, 78, 13, "Blur: " + (cfg.glassBlur ? "ON" : "OFF"), cfg.glassBlur, () -> { cfg.glassBlur = !cfg.glassBlur; cfg.save(); });
		btn(g, CX + 208, y, 14, 13, "<", false, () -> { cfg.uiOffsetX = Math.max(-300, cfg.uiOffsetX - 10); cfg.save(); resizeNow(); });
		btn(g, CX + 226, y, 14, 13, ">", false, () -> { cfg.uiOffsetX = Math.min(300, cfg.uiOffsetX + 10); cfg.save(); resizeNow(); });
		text(g, "X " + (cfg.uiOffsetX > 0 ? "+" : "") + cfg.uiOffsetX, CX + 246, y + 3, Gfx.DIM);
		y += 17;
		String who = !NeteaseApi.loggedIn() ? "NetEase: not logged in" : NeteaseApi.usingBuiltIn() ? "NetEase: built-in account" : "NetEase: " + NeteaseApi.nickname();
		text(g, trim(who, 190), CX, y + 1, NeteaseApi.loggedIn() ? Gfx.GREEN : Gfx.DIM);
		btn(g, CX + 226, CY + 51, 78, 11, "QR login", false, NeteaseApi::startLogin);
		fieldBox(g, fieldBox(), idField.isFocused());
		idField.extractRenderState(g, lmx, lmy, 0f);
		btn(g, CX + 246, CY + 63, 50, 13, "Save", false, this::saveCookie);
		btn(g, CX, CY + 80, 118, 12, "On-screen lyrics: " + (cfg.lyricsOverlay ? "ON" : "OFF"), cfg.lyricsOverlay, () -> { cfg.lyricsOverlay = !cfg.lyricsOverlay; cfg.save(); });
		btn(g, CX + 122, CY + 80, 14, 12, "-", false, () -> { cfg.lyricsOverlayScale = Math.max(0.8f, Math.round((cfg.lyricsOverlayScale - 0.2f) * 10) / 10f); cfg.save(); });
		btn(g, CX + 140, CY + 80, 14, 12, "+", false, () -> { cfg.lyricsOverlayScale = Math.min(3f, Math.round((cfg.lyricsOverlayScale + 0.2f) * 10) / 10f); cfg.save(); });
		text(g, String.format("size x%.1f", cfg.lyricsOverlayScale), CX + 160, CY + 82, Gfx.DIM);
	}
	/** Re-run layout after the size option changed (keeps typed text). */
	private void resizeNow() { searchText = searchField.getValue(); String id = idField.getValue(); init(); idField.setValue(id); }

	// ---- shared row list (smooth scrolling) -----------------------------------------------------------------
	private void rows(GuiGraphicsExtractor g, List<Track> list, int y0, String b1, IntConsumer a1, String b2, IntConsumer a2, IntConsumer click, int highlight) {
		int max = Math.max(0, list.size() - ROWS);
		listScroll = Math.max(0, Math.min(max, listScroll));
		listMax = max;
		if (!touchScroll) {
			scrollF += (listScroll - scrollF) * Math.min(1f, dt * 14f);
			if (Math.abs(listScroll - scrollF) < 0.003f) scrollF = listScroll;
		}
		int w = CW - 6, bandEnd = y0 + ROWS * ROW_H - 1;
		int first = (int) Math.floor(scrollF); float frac = scrollF - first;
		scissor(g, CX, y0, CW, ROWS * ROW_H - 1);
		for (int r = 0; r <= ROWS; r++) {
			int i = first + r; if (i >= list.size()) break;
			Track t = list.get(i); int y = y0 + Math.round((r - frac) * ROW_H); final int idx = i;
			int vy0 = Math.max(y, y0), vy1 = Math.min(y + ROW_H - 1, bandEnd);
			boolean visible = vy1 > vy0;
			boolean hov = visible && inside(CX, vy0, w, vy1 - vy0), cur = i == highlight;
			float hv = hover(key(i, 31, 3), hov);
			Gfx.round(g, CX, y, w, ROW_H - 1, 5, cur ? UiFx.withAlpha(Gfx.GREEN, 0.14f + 0.08f * hv) : UiFx.withAlpha(0xFFFFFFFF, 0.06f + 0.07f * hv));
			Gfx.fill(g, CX + 5, y, CX + w - 5, y + 1, UiFx.withAlpha(0xFFFFFFFF, 0.10f + 0.10f * hv));
			if (cur) Gfx.fill(g, CX + 1, y + 4, CX + 3, y + ROW_H - 5, Gfx.GREEN);
			if (visible) hits.add(new Hit(CX, vy0, w, vy1 - vy0, 1, () -> click.accept(idx)));
			Identifier art = IngameMusic.art.get(t.artUrl());
			if (art != null) g.blit(RenderPipelines.GUI_TEXTURED, art, CX + 3, y + 2, 0f, 0f, 15, 15, 64, 64, 64, 64); else Gfx.fill(g, CX + 3, y + 2, CX + 18, y + 17, Gfx.TRACK);
			int reserve = (b1 != null ? 14 : 0) + (b2 != null ? 14 : 0);
			text(g, trim(t.title(), w - 22 - reserve - 36), CX + 22, y + 2, cur ? Gfx.GREEN : Gfx.TEXT);
			text(g, trim(t.artist(), w - 22 - reserve - 36), CX + 22, y + 10, Gfx.DIM);
			String d = Gfx.time(t.durationMs() > 0 ? t.durationMs() : -1);
			text(g, d, CX + w - reserve - 4 - font.width(d), y + 6, Gfx.DIM);
			boolean btnOk = y + 4 >= y0 && y + 15 <= bandEnd;
			if (b1 != null) btnX(g, CX + w - 14, y + 4, 12, 11, b1, false, 1, btnOk, () -> a1.accept(idx));
			if (b2 != null) btnX(g, CX + w - 28, y + 4, 12, 11, b2, false, 1, btnOk, () -> a2.accept(idx));
		}
		g.disableScissor();
		if (list.size() > ROWS) {
			int th = ROWS * ROW_H - 1, bh = Math.max(8, th * ROWS / list.size()), by = y0 + Math.round((th - bh) * scrollF / Math.max(1, max));
			Gfx.round(g, CX + CW - 3, y0, 2, th, 1, Gfx.TRACK); Gfx.round(g, CX + CW - 3, by, 2, bh, 1, Gfx.GREEN_DK);
		}
	}

	// ---- progress + controls --------------------------------------------------------------------------------
	private void progress(GuiGraphicsExtractor g) {
		long dur = pc.engine.duration(), pos = pc.engine.position();
		double real = dur > 0 ? Math.min(1.0, pos / (double) dur) : 0;
		dispFrac += ((dragBar ? dragFrac : real) - dispFrac) * Math.min(1f, dt * 18f);
		if (Math.abs((dragBar ? dragFrac : real) - dispFrac) < 0.0005) dispFrac = (float) (dragBar ? dragFrac : real);
		text(g, Gfx.time(dragBar && dur > 0 ? (long) (dragFrac * dur) : pos), 8, BY - 2, Gfx.DIM);
		String d = Gfx.time(dur > 0 ? dur : -1); text(g, d, PW - 8 - font.width(d), BY - 2, Gfx.DIM);
		boolean hov = dur > 0 && (dragBar || inside(BX0 - 3, BY - 6, BX1 - BX0 + 6, 14));
		float hv = anim(9100, hov ? 1f : 0f, 14f);
		int bh = 3 + Math.round(hv * 2), by = BY + 2 - bh / 2, fw = (int) ((BX1 - BX0) * dispFrac);
		Gfx.round(g, BX0, by, BX1 - BX0, bh, bh / 2, UiFx.withAlpha(0xFFFFFFFF, 0.16f));
		if (fw > 0) Gfx.round(g, BX0, by, Math.max(bh, fw), bh, bh / 2, UiFx.mix(Gfx.GREEN_DK, Gfx.GREEN, 0.6f + 0.4f * hv));
		Gfx.circle(g, BX0 + fw, BY + 2, 2 + Math.round(hv * 2), 0xFFFFFFFF);
		if (hv > 0.3f) {
			double f = dragBar ? dragFrac : Math.max(0, Math.min(1, (lmx - BX0) / (double) (BX1 - BX0)));
			String tip = Gfx.time((long) (f * dur)); int tw = font.width(tip) + 8, tx = (int) (BX0 + (BX1 - BX0) * f) - tw / 2;
			tx = Math.max(2, Math.min(PW - 2 - tw, tx));
			Gfx.round(g, tx, BY - 17, tw, 11, 4, 0xFF0B0F0D); text(g, tip, tx + 4, BY - 15, Gfx.TEXT);
		}
	}

	private void iconButton(GuiGraphicsExtractor g, int x, int y, int size, boolean next, Runnable r) {
		float hv = hover(key(x, y, size), inside(x - 3, y - 3, size + 6, size + 6));
		if (hv > 0.02f) Gfx.circle(g, x + size / 2, y + size / 2, size / 2 + 3, UiFx.withAlpha(0xFFFFFFFF, 0.08f * hv));
		int col = UiFx.mix(0xFFC8D2CC, 0xFFFFFFFF, hv);
		if (next) Gfx.iconNext(g, x, y, size, col); else Gfx.iconPrev(g, x, y, size, col);
		hits.add(new Hit(x - 3, y - 3, size + 6, size + 6, 1, r));
	}

	private void controls(GuiGraphicsExtractor g) {
		// volume slider
		float vol = pc.engine.volume();
		text(g, "Vol", 8, VY - 4, Gfx.DIM);
		boolean vh = dragVol || inside(VX0 - 3, VY - 7, VW + 6, 14);
		float vhv = anim(9200, vh ? 1f : 0f, 14f);
		int vfw = Math.round(VW * vol), vb = 3 + Math.round(vhv * 2);
		Gfx.round(g, VX0, VY - vb / 2, VW, vb, vb / 2, UiFx.withAlpha(0xFFFFFFFF, 0.16f));
		if (vfw > 0) Gfx.round(g, VX0, VY - vb / 2, Math.max(vb, vfw), vb, vb / 2, Gfx.GREEN);
		Gfx.circle(g, VX0 + vfw, VY, 2 + Math.round(vhv * 2), 0xFFFFFFFF);
		text(g, Math.round(vol * 100) + "%", VX0 + VW + 6, VY - 4, Gfx.DIM);

		// transport
		btn(g, 112, 188, 28, 16, "Shuf", pc.shuffle, pc::toggleShuffle);
		iconButton(g, 150, 191, 10, false, pc::previous);
		boolean playing = pc.engine.state() == AudioEngine.State.PLAYING || (pc.engine.state() == AudioEngine.State.LOADING && !pc.engine.isPaused());
		int pcx = 176, pcy = 196;
		boolean ph = inside(pcx - 11, pcy - 11, 22, 22);
		float phv = hover(key(pcx, pcy, 99), ph);
		int r = 10 + Math.round(phv * 1.5f);
		Gfx.circle(g, pcx, pcy, r + 1, UiFx.withAlpha(Gfx.GREEN, 0.18f + 0.22f * phv));
		Gfx.circle(g, pcx, pcy, r, UiFx.mix(Gfx.GREEN, 0xFF86F0B0, phv));
		if (playing) Gfx.iconPause(g, pcx - 4, pcy - 5, 9, 0xFF0B1410); else Gfx.iconPlay(g, pcx - 3, pcy - 5, 10, 0xFF0B1410);
		hits.add(new Hit(pcx - 11, pcy - 11, 22, 22, 1, pc::togglePause));
		iconButton(g, 192, 191, 10, true, () -> pc.next(false));
		btn(g, 212, 188, 40, 16, "Rep:" + switch (pc.repeat) { case OFF -> "Off"; case ALL -> "All"; case ONE -> "One"; }, pc.repeat != PlayerController.Repeat.OFF, pc::cycleRepeat);
		btn(g, 258, 188, 54, 16, "Lyrics", cfg.lyricsOn, () -> { cfg.lyricsOn = !cfg.lyricsOn; cfg.save(); });
	}

	// ---- input ----------------------------------------------------------------------------------------------
	private double volFrac(double lx) { return Math.max(0, Math.min(1, (lx - VX0) / VW)); }

	@Override public boolean mouseClicked(MouseButtonEvent e, boolean dbl) {
		if (closing) return true;
		double lx = (e.x() - left) / fit, ly = (e.y() - top) / fit;
		if (e.button() != 0) return false;
		EditBox f = activeField();
		if (f != null && !(tab == Tab.OPTS && NeteaseApi.loginState() != NeteaseApi.LoginState.IDLE)) {
			int[] b = fieldBox();
			boolean inF = lx >= b[0] && lx < b[0] + b[2] && ly >= b[1] && ly < b[1] + b[3];
			if (inF) { f.mouseClicked(new MouseButtonEvent(lx, ly, e.buttonInfo()), dbl); if (!f.isFocused()) UiFx.focus(); f.setFocused(true); return true; }
			f.setFocused(false);
		}
		if (lx >= BX0 - 3 && lx <= BX1 + 3 && ly >= BY - 5 && ly <= BY + 9 && pc.engine.duration() > 0) {
			dragBar = true; dragFrac = Math.max(0, Math.min(1, (lx - BX0) / (BX1 - BX0))); return true;
		}
		if (lx >= VX0 - 3 && lx <= VX0 + VW + 3 && ly >= VY - 7 && ly <= VY + 7) {
			dragVol = true; pc.engine.setVolume((float) volFrac(lx)); return true;
		}
		for (int i = hits.size() - 1; i >= 0; i--) {
			Hit h = hits.get(i);
			if (h.in(lx, ly)) { pressHit = h; pressLy = ly; scrollStart = scrollBase(); touchScroll = false; return true; }
		}
		pressHit = null; pressLy = ly; scrollStart = scrollBase(); touchScroll = false;
		return super.mouseClicked(e, dbl);
	}
	@Override public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
		double lx = (e.x() - left) / fit;
		if (dragBar) { dragFrac = Math.max(0, Math.min(1, (lx - BX0) / (BX1 - BX0))); return true; }
		if (dragVol) { pc.engine.setVolume((float) volFrac(lx)); return true; }
		double ly = (e.y() - top) / fit;
		if (!touchScroll && Math.abs(ly - pressLy) > 4 && pressLy >= CY + 14 && pressLy <= 168 && (pressHit != null || tab != Tab.OPTS)) { touchScroll = true; pressHit = null; }
		if (touchScroll) { applyScroll(pressLy - ly); return true; }
		if (pressHit != null) return true;
		return super.mouseDragged(e, dx, dy);
	}
	@Override public boolean mouseReleased(MouseButtonEvent e) {
		if (dragBar) { dragBar = false; pc.seek((long) (dragFrac * pc.engine.duration())); UiFx.seek(); return true; }
		if (dragVol) { dragVol = false; pc.setVolume(pc.engine.volume()); UiFx.seek(); return true; }
		if (touchScroll) { touchScroll = false; listScroll = (int) Math.round(scrollF); return true; }
		if (pressHit != null) {
			Hit h = pressHit; pressHit = null;
			double lx = (e.x() - left) / fit, ly = (e.y() - top) / fit;
			if (h.near(lx, ly)) { if (h.snd() == 1) UiFx.click(); else if (h.snd() == 2) UiFx.tab(); h.run().run(); }
			return true;
		}
		return super.mouseReleased(e);
	}
	/** Starting value for a touch drag-scroll of the current tab's content. */
	private double scrollBase() {
		Lyrics l = pc.lyrics;
		if (tab == Tab.LYRICS) {
			if (l != null && l.synced) return System.currentTimeMillis() - lastManual < 3000 ? manualLine : Math.max(0, l.indexAt(pc.lyricPosition()));
			return listScroll;
		}
		return scrollF;
	}
	/** dy = how far the finger moved up, in panel units. */
	private void applyScroll(double dy) {
		Lyrics l = pc.lyrics;
		if (tab == Tab.LYRICS) {
			if (l != null && l.synced) { manualLine = Math.max(0, Math.min(l.lines.length - 1, scrollStart + dy / 12.0)); lastManual = System.currentTimeMillis(); }
			else listScroll = (int) Math.round(Math.max(0, scrollStart + dy / 10.0));
		} else {
			scrollF = (float) Math.max(0, Math.min(listMax, scrollStart + dy / ROW_H));
			listScroll = (int) Math.round(scrollF);
		}
	}
	@Override public boolean mouseScrolled(double mx, double my, double h, double v) {
		int d = v > 0 ? -1 : 1;
		Lyrics l = pc.lyrics;
		if (tab == Tab.LYRICS && l != null && l.synced) {
			double base = System.currentTimeMillis() - lastManual < 3000 ? manualLine : Math.max(0, l.indexAt(pc.lyricPosition()));
			manualLine = Math.max(0, Math.min(l.lines.length - 1, base + d * 2)); lastManual = System.currentTimeMillis();
		} else listScroll += d * (tab == Tab.LYRICS ? 3 : 1);
		return true;
	}
	@Override public boolean keyPressed(KeyEvent e) {
		int key = e.key();
		EditBox f = activeField();
		if (f != null && f.isFocused()) {
			if (key == InputConstants.KEY_ENTER || key == InputConstants.KEY_KP_ENTER) {
				if (tab == Tab.SEARCH) runSearch(); else saveCookie();
				return true;
			}
			if (key == InputConstants.KEY_ESCAPE) { f.setFocused(false); return true; }
			return f.keyPressed(e);
		}
		if (key == InputConstants.KEY_ESCAPE || IngameMusic.KEY_OPEN.matches(e)) { onClose(); return true; }
		if (key == InputConstants.KEY_SPACE) { pc.togglePause(); return true; }
		if (key == InputConstants.KEY_LEFT) { pc.seek(pc.engine.position() - 5000); return true; }
		if (key == InputConstants.KEY_RIGHT) { pc.seek(pc.engine.position() + 5000); return true; }
		return super.keyPressed(e);
	}
	@Override public boolean charTyped(CharacterEvent e) {
		EditBox f = activeField();
		if (f != null && f.isFocused()) { UiFx.key(); return f.charTyped(e); }
		return super.charTyped(e);
	}
	@Override public void removed() { searchText = searchField.getValue(); }
}
