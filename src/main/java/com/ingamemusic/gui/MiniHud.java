package com.ingamemusic.gui;

import com.ingamemusic.IngameMusic;
import com.ingamemusic.MusicConfig;
import com.ingamemusic.audio.AudioEngine;
import com.ingamemusic.lyrics.Lyrics;
import com.ingamemusic.model.Track;
import com.ingamemusic.player.PlayerController;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/** Compact always-visible HUD. Allocation-free per frame except when the track / lyric line changes. */
public final class MiniHud implements HudElement {
	private static final int W = 160, H = 26;
	private final float[] vis = new float[AudioEngine.BANDS];
	private Track lastTrack; private String titleLine = "";
	private int lastLyric = -2; private Track lastLyricTrack; private String lyricLine = "";
	private String lastErr; private String errLine = "";

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, DeltaTracker tick) {
		MusicConfig cfg = IngameMusic.config; PlayerController pc = IngameMusic.player;
		Minecraft mc = Minecraft.getInstance();
		if (!cfg.hudVisible || mc.player == null) return;
		if (mc.gui.screen() instanceof MusicScreen || mc.gui.hud.getDebugOverlay().showDebugScreen()) return;
		Font tr = mc.font;
		float s = cfg.hudScale;
		int sw = mc.getWindow().getGuiScaledWidth(), sh = mc.getWindow().getGuiScaledHeight();
		float x, y;
		switch (cfg.hudAnchor) {
			case TOP_LEFT -> { x = 2 + cfg.hudOffsetX; y = 3 + cfg.hudMarginY; }
			case TOP_RIGHT -> { x = sw - W * s - 2 - cfg.hudOffsetX; y = 3 + cfg.hudMarginY; }
			case BOTTOM_RIGHT -> { x = sw - W * s - 2 - cfg.hudOffsetX; y = sh - H * s - 2 - cfg.hudMarginY - 28; } // clear of hotbar row
			default -> { x = (sw - W * s) / 2f + cfg.hudOffsetX; y = 3 + cfg.hudMarginY; }
		}
		var m = g.pose(); m.pushMatrix(); m.translate(x, y); m.scale(s, s);

		Track t = pc.current;
		if (t != null && t != lastTrack) { lastTrack = t; titleLine = tr.plainSubstrByWidth("\u266B " + t.title() + " \u00B7 " + t.artist(), W - 54); }
		Gfx.glassPanel(g, 0, 0, W, H, 9);
		if (t == null) {   // idle pill: always visible at the top so the player is easy to find
			Gfx.round(g, 4, 3, 20, 20, 5, 0x22FFFFFF);
			g.text(tr, "\u266A", 12, 8, Gfx.DIM, false);
			g.text(tr, "Nothing playing", 28, 4, Gfx.TEXT, false);
			g.text(tr, "Press M or type /music", 28, 14, Gfx.DIM, false);
			m.popMatrix();
			return;
		}
		// artwork
		Identifier art = IngameMusic.art.get(t.artUrl());
		if (art != null) g.blit(RenderPipelines.GUI_TEXTURED, art, 4, 3, 0f, 0f, 20, 20, 64, 64, 64, 64);
		else { g.fill(4, 3, 24, 23, Gfx.TRACK); g.text(tr, "\u266A", 12, 8, Gfx.DIM, false); }
		g.text(tr, titleLine, 28, 4, Gfx.TEXT, false);
		g.text(tr, statusLine(pc, tr), 28, 14, Gfx.GREEN, false);

		// visualizer
		if (cfg.hudVisualizer) {
			int bx = W - 29;
			for (int i = 0; i < vis.length; i++) {
				vis[i] += (pc.engine.band(i) - vis[i]) * 0.35f;
				int bh = 1 + (int) (vis[i] * 12);
				g.fill(bx + i * 3, 19 - bh, bx + i * 3 + 2, 19, Gfx.GREEN);
			}
		}
		// progress
		long d = pc.engine.duration();
		if (d > 0) {
			int pw = (int) ((W - 12) * Math.min(1.0, pc.engine.position() / (double) d));
			g.fill(10, H - 4, W - 10, H - 3, 0x30FFFFFF);
			g.fill(10, H - 4, 10 + (int) (pw * (W - 20) / (float) (W - 12)), H - 3, Gfx.GREEN);
		}
		m.popMatrix();
	}

	private String statusLine(PlayerController pc, Font tr) {
		switch (pc.engine.state()) {
			case LOADING: return "Loading\u2026";
			case ERROR: {
				String e = pc.engine.error();
				if (!e.equals(lastErr)) { lastErr = e; errLine = tr.plainSubstrByWidth(e, W - 34); }
				return errLine;
			}
			case PAUSED: return "Paused";
			default: break;
		}
		Lyrics l = pc.lyrics;
		if (IngameMusic.config.lyricsOn && l != null && l.synced) {
			int i = l.indexAt(pc.lyricPosition());
			if (i != lastLyric || lastLyricTrack != pc.current) {
				lastLyric = i; lastLyricTrack = pc.current;
				lyricLine = i < 0 ? "\u266A" : tr.plainSubstrByWidth(l.lines[i].isEmpty() ? "\u266A" : l.lines[i], W - 54);
			}
			return lyricLine;
		}
		return pc.engine.state() == AudioEngine.State.IDLE ? "Stopped" : "Playing";
	}
}
