package com.ingamemusic.gui;

import com.ingamemusic.IngameMusic;
import com.ingamemusic.net.HttpUtil;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/** Bounded LRU cache of 64x64 album art textures. Render-thread access only. */
public final class ArtworkCache {
	private static final int MAX = 40, SIZE = 64;
	private static final class Entry { Identifier id; boolean released; }
	private final AtomicInteger counter = new AtomicInteger();
	private final Map<String, ArtworkCache.Entry> map = new LinkedHashMap<>(64, 0.75f, true) {
		@Override protected boolean removeEldestEntry(Map.Entry<String, ArtworkCache.Entry> e) {
			if (size() > MAX) { release(e.getValue()); return true; }
			return false;
		}
	};
	private void release(Entry e) {
		e.released = true;
		if (e.id != null) Minecraft.getInstance().getTextureManager().release(e.id);
	}
	/** Returns a texture id or null while loading / on failure. Kicks off an async load on first request. */
	public Identifier get(String url) {
		if (url == null || url.isEmpty()) return null;
		Entry e = map.get(url);
		if (e == null) { e = new Entry(); map.put(url, e); load(url, e); }
		return e.id;
	}
	private void load(String url, Entry e) {
		Minecraft mc = Minecraft.getInstance();
		HttpUtil.bytes(url).thenAcceptAsync(bytes -> {
			try {
				BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
				if (img == null) return;
				int[] px = scale(img);
				mc.execute(() -> {
					if (e.released) return;
					NativeImage ni = new NativeImage(NativeImage.Format.RGBA, SIZE, SIZE, false);
					for (int y = 0; y < SIZE; y++) for (int x = 0; x < SIZE; x++) {
						ni.setPixel(x, y, px[y * SIZE + x]);   // ARGB
					}
					Identifier id = Identifier.fromNamespaceAndPath(IngameMusic.ID, "art/" + counter.incrementAndGet());
					mc.getTextureManager().register(id, new DynamicTexture(() -> "ingamemusic art", ni));
					e.id = id;
				});
			} catch (Exception ex) { IngameMusic.LOG.debug("Artwork decode failed: {}", url); }
		}, IngameMusic.IO);
	}
	/** Box-filter downscale (no AWT Graphics2D: avoids AWT toolkit init issues on macOS). */
	private static int[] scale(BufferedImage src) {
		int w = src.getWidth(), h = src.getHeight(); int[] out = new int[SIZE * SIZE];
		for (int y = 0; y < SIZE; y++) for (int x = 0; x < SIZE; x++) {
			int x0 = x * w / SIZE, x1 = Math.max(x0 + 1, (x + 1) * w / SIZE), y0 = y * h / SIZE, y1 = Math.max(y0 + 1, (y + 1) * h / SIZE);
			long r = 0, g = 0, b = 0; int n = 0;
			for (int yy = y0; yy < y1 && yy < h; yy++) for (int xx = x0; xx < x1 && xx < w; xx++) {
				int p = src.getRGB(xx, yy); r += (p >> 16) & 255; g += (p >> 8) & 255; b += p & 255; n++;
			}
			if (n == 0) n = 1;
			out[y * SIZE + x] = 0xFF000000 | ((int) (r / n) << 16) | ((int) (g / n) << 8) | (int) (b / n);
		}
		return out;
	}
	public void clear() { for (Entry e : map.values()) release(e); map.clear(); }
}
