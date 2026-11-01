package com.ingamemusic;

import com.ingamemusic.gui.*;
import com.ingamemusic.player.PlayerController;
import java.util.concurrent.*;
import net.fabricmc.api.ClientModInitializer;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.*;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import com.mojang.blaze3d.platform.InputConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class IngameMusic implements ClientModInitializer {
	public static final String ID = "ingamemusic";
	public static final Logger LOG = LoggerFactory.getLogger(ID);
	/** Network / decode / file work. */
	public static final ExecutorService IO = Executors.newFixedThreadPool(3, r -> { Thread t = new Thread(r, "ingamemusic-io"); t.setDaemon(true); return t; });
	/** Ordered config/library writes. */
	public static final ExecutorService SAVE = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "ingamemusic-save"); t.setDaemon(true); return t; });

	public static MusicConfig config;
	public static PlayerController player;
	public static ArtworkCache art;
	/** Set by the /music command (touch devices have no M key); handled on the next tick, after chat has closed. */
	private static volatile boolean openRequested;
	public static KeyMapping KEY_OPEN, KEY_PLAY, KEY_NEXT, KEY_PREV, KEY_HUD;
	private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(ID, "main"));

	private static KeyMapping key(String n, int code) {
		return KeyMappingHelper.registerKeyMapping(new KeyMapping("key.ingamemusic." + n, InputConstants.Type.KEYBOARD, code, CATEGORY));
	}

	@Override public void onInitializeClient() {
		Dirs.init();
		config = MusicConfig.load();
		player = new PlayerController(config);
		art = new ArtworkCache();

		KEY_OPEN = key("open", InputConstants.KEY_M);
		KEY_PLAY = key("play", -1);
		KEY_NEXT = key("next", -1);
		KEY_PREV = key("prev", -1);
		KEY_HUD = key("hud", -1);

		HudElementRegistry.attachElementAfter(VanillaHudElements.MISC_OVERLAYS, Identifier.fromNamespaceAndPath(ID, "hud"), new MiniHud());
		HudElementRegistry.attachElementAfter(VanillaHudElements.MISC_OVERLAYS, Identifier.fromNamespaceAndPath(ID, "lyrics"), new LyricsHud());
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
			dispatcher.register(ClientCommands.literal("music").executes(ctx -> { openRequested = true; return 1; })));
		ClientTickEvents.END_CLIENT_TICK.register(c -> {
			while (KEY_OPEN.consumeClick()) if (c.gui.screen() == null) c.gui.setScreen(new MusicScreen());
			if (openRequested && c.gui.screen() == null) { openRequested = false; c.gui.setScreen(new MusicScreen()); }
			while (KEY_PLAY.consumeClick()) player.togglePause();
			while (KEY_NEXT.consumeClick()) player.next(false);
			while (KEY_PREV.consumeClick()) player.previous();
			while (KEY_HUD.consumeClick()) { config.hudVisible = !config.hudVisible; config.save(); }
		});
		ClientPlayConnectionEvents.DISCONNECT.register((h, c) -> player.onDisconnect());
		ClientLifecycleEvents.CLIENT_STOPPING.register(c -> { player.shutdown(); art.clear(); IO.shutdown(); SAVE.shutdown();
			try { SAVE.awaitTermination(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) {} });
	}
}
