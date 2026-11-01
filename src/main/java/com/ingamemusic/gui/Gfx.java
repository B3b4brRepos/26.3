package com.ingamemusic.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;

public final class Gfx {
	public static final int PANEL = 0xF0101412, BORDER = 0xFF2B3A31, GREEN = 0xFF4ADE80, GREEN_DK = 0xFF1F7A46,
		TEXT = 0xFFE6E9E7, DIM = 0xFF8A948E, TRACK = 0xFF2A302D, BTN = 0xFF1B2220, BTN_HOV = 0xFF2A3430, RED = 0xFFFF6B6B;

	/** Global alpha multiplier used for fade animations. Always reset to 1 after drawing. */
	public static float A = 1f;

	public static int a(int color) { return A >= 0.999f ? color : UiFx.withAlpha(color, A); }

	public static void fill(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, int color) {
		int c = a(color);
		if ((c >>> 24) == 0) return;
		g.fill(x1, y1, x2, y2, c);
	}
	public static void gradient(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, int c1, int c2) {
		g.fillGradient(x1, y1, x2, y2, a(c1), a(c2));
	}

	/** Filled rounded rectangle without allocations (row based corner cut). */
	public static void round(GuiGraphicsExtractor g, int x, int y, int w, int h, int r, int color) {
		r = Math.min(r, Math.min(w, h) / 2);
		for (int i = 0; i < r; i++) {
			double dy = r - i - 0.5;
			int inset = r - (int) Math.round(Math.sqrt(r * r - dy * dy));
			fill(g, x + inset, y + i, x + w - inset, y + i + 1, color);
			fill(g, x + inset, y + h - 1 - i, x + w - inset, y + h - i, color);
		}
		fill(g, x, y + r, x + w, y + h - r, color);
	}
	public static void circle(GuiGraphicsExtractor g, int cx, int cy, int r, int color) {
		double rr = (r + 0.5) * (r + 0.5);
		for (int dy = -r; dy <= r; dy++) {
			int dx = (int) Math.round(Math.sqrt(Math.max(0, rr - dy * dy)));
			fill(g, cx - dx, cy + dy, cx + dx + 1, cy + dy + 1, color);
		}
	}
	public static void panel(GuiGraphicsExtractor g, int x, int y, int w, int h, int r, int fill) {
		round(g, x, y, w, h, r, BORDER);
		round(g, x + 1, y + 1, w - 2, h - 2, Math.max(1, r - 1), fill);
	}
	/** Rounded only at the bottom: for panels docked flush to the top edge of the screen. */
	public static void roundBottom(GuiGraphicsExtractor g, int x, int y, int w, int h, int r, int color) {
		r = Math.min(r, Math.min(w, h) / 2);
		fill(g, x, y, x + w, y + h - r, color);
		for (int i = 0; i < r; i++) {
			double dy = r - i - 0.5;
			int inset = r - (int) Math.round(Math.sqrt(r * r - dy * dy));
			fill(g, x + inset, y + h - 1 - i, x + w - inset, y + h - i, color);
		}
	}
	public static void panelTop(GuiGraphicsExtractor g, int x, int y, int w, int h, int r, int fill) {
		roundBottom(g, x, y, w, h, r, BORDER);
		roundBottom(g, x + 1, y, w - 2, h - 1, Math.max(1, r - 1), fill);
	}


	// ---- liquid glass (soft, translucent, specular rim) ----------------------------------------------------
	/** Vertical colour ramp that follows rounded corners. */
	public static void sheen(GuiGraphicsExtractor g, int x, int y, int w, int h, int r, int top, int bottom) {
		r = Math.min(r, Math.min(w, h) / 2);
		for (int i = 0; i < h; i++) {
			int inset = 0;
			if (i < r) { double dy = r - i - 0.5; inset = r - (int) Math.round(Math.sqrt(r * r - dy * dy)); }
			else if (i >= h - r) { double dy = i - (h - r) + 0.5; inset = r - (int) Math.round(Math.sqrt(r * r - dy * dy)); }
			fill(g, x + inset, y + i, x + w - inset, y + i + 1, UiFx.mix(top, bottom, i / (float) Math.max(1, h - 1)));
		}
	}
	/** Big floating glass pane: soft shadow, bright rim, dark frosted tint, sheen, specular top edge, faint green base glow. */
	public static void glassPanel(GuiGraphicsExtractor g, int x, int y, int w, int h, int r) {
		for (int i = 4; i >= 1; i--) round(g, x - i, y - i + 3, w + 2 * i, h + 2 * i, r + i, 0x08000000);
		round(g, x, y, w, h, r, 0x70FFFFFF);
		round(g, x + 1, y + 1, w - 2, h - 2, r - 1, 0xB30E1412);
		sheen(g, x + 1, y + 1, w - 2, h - 2, r - 1, 0x30FFFFFF, 0x07FFFFFF);
		fill(g, x + r, y + 1, x + w - r, y + 2, 0x60FFFFFF);
		fill(g, x + r + 10, y + 2, x + w - r - 10, y + 3, 0x20FFFFFF);
		fill(g, x + 1, y + r, x + 2, y + h - r, 0x1CFFFFFF);
		fill(g, x + r, y + h - 2, x + w - r, y + h - 1, 0x304ADE80);
	}
	/** Small glass control. hv = hover amount 0..1. */
	public static void glassBtn(GuiGraphicsExtractor g, int x, int y, int w, int h, boolean active, float hv) {
		int r = Math.min(h / 2, 6);
		int rim = active ? UiFx.withAlpha(GREEN, 0.55f + 0.3f * hv) : UiFx.withAlpha(0xFFFFFFFF, 0.16f + 0.16f * hv);
		int body = active ? UiFx.mix(0xC81F7A46, 0xDC2A9A5C, hv) : UiFx.withAlpha(0xFFFFFFFF, 0.08f + 0.10f * hv);
		round(g, x, y, w, h, r, rim);
		round(g, x + 1, y + 1, w - 2, h - 2, Math.max(1, r - 1), body);
		fill(g, x + r, y + 1, x + w - r, y + 2, UiFx.withAlpha(0xFFFFFFFF, 0.16f + 0.18f * hv));
	}
	/** Glass inset (text boxes, tracks). focus = 0..1 glow. */
	public static void glassInset(GuiGraphicsExtractor g, int x, int y, int w, int h, int r, float focus) {
		round(g, x, y, w, h, r, UiFx.mix(0x40FFFFFF, GREEN, focus));
		round(g, x + 1, y + 1, w - 2, h - 2, Math.max(1, r - 1), 0xA0070B09);
	}

	// ---- transport icons (drawn from rectangles, so no font glyphs needed) -------------------------------
	public static void iconPlay(GuiGraphicsExtractor g, int x, int y, int s, int color) {
		int w = Math.max(2, (int) (s * 0.8f));
		for (int i = 0; i < w; i++) {
			int hh = Math.max(1, Math.round(s * (1f - i / (float) w)));
			int yy = y + (s - hh) / 2;
			fill(g, x + i, yy, x + i + 1, yy + hh, color);
		}
	}
	public static void iconPause(GuiGraphicsExtractor g, int x, int y, int s, int color) {
		int bw = Math.max(2, s / 3);
		fill(g, x, y, x + bw, y + s, color);
		fill(g, x + s - bw, y, x + s, y + s, color);
	}
	public static void iconNext(GuiGraphicsExtractor g, int x, int y, int s, int color) {
		iconPlay(g, x, y, s, color);
		fill(g, x + s - 1, y, x + s + 1, y + s, color);
	}
	public static void iconPrev(GuiGraphicsExtractor g, int x, int y, int s, int color) {
		int w = Math.max(2, (int) (s * 0.8f));
		fill(g, x, y, x + 2, y + s, color);
		for (int i = 0; i < w; i++) {
			int hh = Math.max(1, Math.round(s * (i / (float) w)));
			int yy = y + (s - hh) / 2;
			fill(g, x + 2 + i, yy, x + 3 + i, yy + hh, color);
		}
	}

	public static String time(long ms) {
		if (ms < 0) return "--:--";
		long s = ms / 1000; return String.format("%02d:%02d", s / 60, s % 60);
	}
}
