package com.ingamemusic.gui;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;

/** Small helpers for the music UI: easing, colour maths and soft UI sounds. Never throws into the game. */
public final class UiFx {
	private UiFx() {}

	// ---- easing / colour ------------------------------------------------------------------------------------
	public static float clamp01(float v) { return v < 0f ? 0f : Math.min(v, 1f); }
	public static float easeOut(float t) { float u = 1f - clamp01(t); return 1f - u * u * u; }

	public static int withAlpha(int color, float mul) {
		int a = (int) (((color >>> 24) & 255) * clamp01(mul));
		return (a << 24) | (color & 0xFFFFFF);
	}
	public static int mix(int a, int b, float t) {
		t = clamp01(t);
		int aa = (a >>> 24) & 255, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
		int ba = (b >>> 24) & 255, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
		return ((int) (aa + (ba - aa) * t) << 24) | ((int) (ar + (br - ar) * t) << 16) | ((int) (ag + (bg - ag) * t) << 8) | (int) (ab + (bb - ab) * t);
	}

	// ---- sounds ---------------------------------------------------------------------------------------------
	private static final Map<String, SoundEvent> CACHE = new HashMap<>();
	private static long lastKey, lastTick;

	private static void play(String id, float pitch, float volume) {
		try {
			SoundEvent ev = CACHE.get(id);
			if (ev == null) { ev = SoundEvent.createVariableRangeEvent(Identifier.tryParse(id)); CACHE.put(id, ev); }
			Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(ev, pitch, volume));
		} catch (Exception ignored) { }
	}

	public static void open() { play("minecraft:block.note_block.chime", 1.5f, 0.35f); play("minecraft:ui.button.click", 1.25f, 0.25f); }
	public static void close() { play("minecraft:ui.button.click", 0.75f, 0.35f); }
	public static void click() { play("minecraft:ui.button.click", 1.0f, 0.55f); }
	public static void tab() { play("minecraft:block.note_block.hat", 1.35f, 0.5f); play("minecraft:ui.button.click", 1.25f, 0.3f); }
	public static void focus() { play("minecraft:block.note_block.bit", 1.7f, 0.25f); }
	public static void seek() { play("minecraft:block.note_block.hat", 1.1f, 0.4f); }
	public static void done() { play("minecraft:block.note_block.pling", 1.6f, 0.3f); }
	public static void tick() {
		long now = System.nanoTime();
		if (now - lastTick < 40_000_000L) return;
		lastTick = now;
		play("minecraft:block.note_block.hat", 1.9f, 0.12f);
	}
	public static void key() {
		long now = System.nanoTime();
		if (now - lastKey < 25_000_000L) return;
		lastKey = now;
		play("minecraft:block.note_block.hat", 1.6f + (float) Math.random() * 0.35f, 0.22f);
	}
}
