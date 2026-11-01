package com.ingamemusic.gui;

import com.ingamemusic.IngameMusic;
import com.ingamemusic.MusicConfig;
import com.ingamemusic.audio.AudioEngine;
import com.ingamemusic.lyrics.Lyrics;
import com.ingamemusic.player.PlayerController;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** 2D on-screen lyrics: the current line big above the hotbar, the next line small and dim. Fades in on every new line. */
public final class LyricsHud implements HudElement {
	private int lastIdx = -2; private long lineStart;

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, DeltaTracker tick) {
		MusicConfig cfg = IngameMusic.config; PlayerController pc = IngameMusic.player;
		Minecraft mc = Minecraft.getInstance();
		if (!cfg.lyricsOverlay || !cfg.lyricsOn || !cfg.hudVisible || mc.player == null || pc.current == null) return;
		if (mc.gui.screen() instanceof MusicScreen || mc.gui.hud.getDebugOverlay().showDebugScreen()) return;
		Lyrics l = pc.lyrics;
		if (l == null || !l.synced || pc.engine.state() != AudioEngine.State.PLAYING) return;
		int i = l.indexAt(pc.lyricPosition());
		if (i < 0 || l.lines[i].isEmpty()) return;
		long now = System.nanoTime();
		if (i != lastIdx) { lastIdx = i; lineStart = now; }
		float a = UiFx.easeOut(Math.min(1f, (now - lineStart) / 220_000_000f));
		Font font = mc.font;
		int sw = mc.getWindow().getGuiScaledWidth(), sh = mc.getWindow().getGuiScaledHeight();
		float s = cfg.lyricsOverlayScale;
		int maxW = (int) (sw * 0.8f / s);
		String cur = font.plainSubstrByWidth(l.lines[i], maxW);
		int cy = sh - 78 - (int) ((1f - a) * -6f);                      // slides up a few pixels while fading in
		var m = g.pose();
		m.pushMatrix(); m.translate(sw / 2f, cy); m.scale(s, s);
		int ca = (int) (255 * a);
		if (ca >= 8) g.text(font, cur, -font.width(cur) / 2, 0, (ca << 24) | 0xFFFFFF, true);
		m.popMatrix();
		if (i + 1 < l.lines.length && !l.lines[i + 1].isEmpty()) {
			String nx = font.plainSubstrByWidth(l.lines[i + 1], (int) (sw * 0.8f / (s * 0.75f)));
			float s2 = s * 0.75f;
			m.pushMatrix(); m.translate(sw / 2f, cy + 11 * s); m.scale(s2, s2);
			int na = (int) (150 * a);
			if (na >= 8) g.text(font, nx, -font.width(nx) / 2, 0, (na << 24) | 0xC8D2CC, true);
			m.popMatrix();
		}
	}
}
