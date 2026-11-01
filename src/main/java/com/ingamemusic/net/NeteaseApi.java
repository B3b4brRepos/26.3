package com.ingamemusic.net;

import com.google.gson.*;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.common.BitMatrix;
import com.ingamemusic.IngameMusic;
import com.ingamemusic.model.Track;
import java.io.*;
import java.math.BigInteger;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * NetEase Cloud Music client, written after the way DangoMusic does it (https://github.com/Sorihiko/DangoMusic, GPL-3.0):
 * plain web endpoints + the MUSIC_U login cookie. Search, play links and QR login. Audio is MP3 only (decoded by jlayer).
 *
 * The login cookie comes from, in this order: config/ingamemusic/netease_cookie.txt (written by QR login or pasted in Opts),
 * then the account that was baked into the jar at build time (assets/ingamemusic/account.txt).
 */
public final class NeteaseApi {
	private NeteaseApi() {}

	private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";
	private static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
		.connectTimeout(Duration.ofSeconds(10)).executor(IngameMusic.IO).build();

	// ------------------------------------------------------------------------------------------------ cookie
	private static volatile String bundled;

	private static Path cookieFile() { return com.ingamemusic.Dirs.root().resolve("netease_cookie.txt"); }

	private static String normalize(String c) {
		if (c == null) return "";
		c = c.trim();
		if (c.isEmpty()) return "";
		if (!c.contains("=")) c = "MUSIC_U=" + c;
		return c;
	}
	/** Cookie that is sent with requests: the user's file wins, otherwise the account baked into the jar. */
	public static String cookie() {
		try { if (Files.isRegularFile(cookieFile())) { String c = normalize(Files.readString(cookieFile())); if (!c.isEmpty()) return c; } }
		catch (IOException ignored) { }
		String b = bundled;
		if (b == null) {
			try (InputStream in = NeteaseApi.class.getResourceAsStream("/assets/ingamemusic/account.txt")) {
				b = in == null ? "" : normalize(new String(in.readAllBytes(), StandardCharsets.UTF_8));
			} catch (IOException e) { b = ""; }
			bundled = b;
		}
		return b;
	}
	public static boolean loggedIn() { return cookie().contains("MUSIC_U="); }
	/** True when the cookie in use is the one baked into the jar. */
	public static boolean usingBuiltIn() {
		try { if (Files.isRegularFile(cookieFile()) && !normalize(Files.readString(cookieFile())).isEmpty()) return false; } catch (IOException ignored) { }
		return !normalize(bundled == null ? readBundled() : bundled).isEmpty();
	}
	private static String readBundled() { cookie(); return bundled == null ? "" : bundled; }
	public static void saveCookie(String c) {
		try { Files.createDirectories(cookieFile().getParent()); Files.writeString(cookieFile(), normalize(c)); nick = ""; }
		catch (IOException e) { IngameMusic.LOG.warn("Could not save NetEase cookie", e); }
	}

	private static volatile String nick = "";
	/** Account name for the UI (looked up once in the background). */
	public static String nickname() {
		if (!loggedIn()) return "";
		if (nick.isEmpty()) {
			nick = "…";
			IngameMusic.IO.execute(() -> {
				try {
					JsonObject o = getJson("https://music.163.com/api/nuser/account/get");
					JsonObject p = o != null && o.get("profile") instanceof JsonObject po ? po : null;
					nick = p != null && p.has("nickname") ? p.get("nickname").getAsString() : "?";
				} catch (Exception e) { nick = "?"; }
			});
		}
		return nick;
	}

	// ------------------------------------------------------------------------------------------------ search / urls
	public static CompletableFuture<List<Track>> search(String keyword) {
		return CompletableFuture.supplyAsync(() -> {
			String q = keyword.trim();
			List<String> errors = new ArrayList<>();
			try {   // 1. cloudsearch (has cover + duration)
				JsonObject o = getJson("https://music.163.com/api/cloudsearch/pc?s=" + enc(q) + "&type=1&offset=0&limit=30");
				List<Track> t = songsOf(o);
				if (t != null && !t.isEmpty()) return t;
				if (t == null) errors.add("cloudsearch " + code(o));
			} catch (Exception e) { errors.add("cloudsearch " + e.getClass().getSimpleName()); }
			try {   // 2. encrypted weapi version (what the website uses; rarely blocked)
				JsonObject body = new JsonObject();
				body.addProperty("s", q); body.addProperty("type", "1"); body.addProperty("limit", "30");
				body.addProperty("offset", "0"); body.addProperty("total", "true"); body.addProperty("csrf_token", "");
				Resp r = weapi("https://music.163.com/weapi/cloudsearch/get/web", body.toString(), cookieHeader());
				JsonObject o = r == null ? null : JsonParser.parseString(r.body).getAsJsonObject();
				List<Track> t = songsOf(o);
				if (t != null && !t.isEmpty()) return t;
				if (t == null) errors.add("weapi " + code(o));
			} catch (Exception e) { errors.add("weapi " + e.getClass().getSimpleName()); }
			if (!errors.isEmpty()) throw new IllegalStateException("NetEase search failed (" + String.join(", ", errors) + ")");
			return new ArrayList<>();
		}, IngameMusic.IO);
	}

	public record SongUrl(String url, boolean trialOnly) {}

	/** Play link: the account's own full-quality link when logged in, else NetEase's public outer link (free songs only). */
	public static SongUrl songUrl(long id) {
		SongUrl s = playerUrl(id);
		if (s != null) return s;
		Long alt = alternative(id);
		if (alt != null) { SongUrl a = playerUrl(alt); if (a != null) return a; }
		return new SongUrl("https://music.163.com/song/media/outer/url?id=" + id + ".mp3", false);
	}
	private static SongUrl playerUrl(long id) {
		try {
			JsonObject o = getJson("https://music.163.com/api/song/enhance/player/url?ids=" + enc("[" + id + "]") + "&br=192000");
			if (o == null || !o.has("data") || !o.get("data").isJsonArray() || o.getAsJsonArray("data").isEmpty()) return null;
			JsonObject d = o.getAsJsonArray("data").get(0).getAsJsonObject();
			String url = str(d, "url", "");
			String type = str(d, "type", "").toLowerCase(Locale.ROOT);
			boolean trial = d.has("freeTrialInfo") && !d.get("freeTrialInfo").isJsonNull();
			if (!url.isEmpty() && (type.isEmpty() || type.equals("mp3"))) return new SongUrl(url, trial);
		} catch (Exception e) { IngameMusic.LOG.warn("NetEase song url failed: {}", e.toString()); }
		return null;
	}
	private static Long alternative(long id) {
		try {
			JsonObject o = getJson("https://music.163.com/api/v3/song/detail?c=" + enc("[{\"id\":" + id + "}]"));
			if (o == null) return null;
			int st = 0;
			if (o.has("privileges") && o.getAsJsonArray("privileges").size() > 0) { JsonElement e = o.getAsJsonArray("privileges").get(0).getAsJsonObject().get("st"); if (e != null && e.isJsonPrimitive()) st = e.getAsInt(); }
			if (st >= 0 || !o.has("songs") || o.getAsJsonArray("songs").isEmpty()) return null;
			JsonElement r = o.getAsJsonArray("songs").get(0).getAsJsonObject().get("noCopyrightRcmd");
			if (r == null || !r.isJsonObject()) return null;
			long alt = Long.parseLong(r.getAsJsonObject().get("songId").getAsString());
			return alt > 0 && alt != id ? alt : null;
		} catch (Exception e) { return null; }
	}

	private static List<Track> songsOf(JsonObject o) {
		if (o == null || !(o.get("result") instanceof JsonObject res)) return null;
		if (!res.has("songs") || !res.get("songs").isJsonArray()) return new ArrayList<>();
		List<Track> out = new ArrayList<>();
		for (JsonElement e : res.getAsJsonArray("songs")) { if (e.isJsonObject()) { Track t = parse(e.getAsJsonObject()); if (t != null) out.add(t); } }
		return out;
	}
	private static Track parse(JsonObject s) {
		if (!s.has("id") || s.get("id").isJsonNull()) return null;
		String id = s.get("id").getAsString();
		JsonElement ar = s.has("ar") ? s.get("ar") : s.get("artists");
		List<String> names = new ArrayList<>();
		if (ar != null && ar.isJsonArray()) for (JsonElement a : ar.getAsJsonArray()) if (a.isJsonObject()) { String n = str(a.getAsJsonObject(), "name", ""); if (!n.isEmpty()) names.add(n); }
		JsonElement dur = s.has("dt") ? s.get("dt") : s.get("duration");
		long ms = 0; try { if (dur != null && !dur.isJsonNull()) ms = dur.getAsLong(); } catch (Exception ignored) { }
		JsonElement al = s.has("al") ? s.get("al") : s.get("album");
		String album = "", cover = "";
		if (al != null && al.isJsonObject()) { album = str(al.getAsJsonObject(), "name", ""); cover = str(al.getAsJsonObject(), "picUrl", ""); }
		if (!cover.isEmpty()) cover += (cover.contains("?") ? "&" : "?") + "param=96y96";
		return new Track("ne:" + id, str(s, "name", "Unknown"), names.isEmpty() ? "Unknown" : String.join(", ", names), album, ms, cover, "", null);
	}

	/** Synced LRC text of a NetEase song, or null (instrumental / none / network problem). */
	public static String lyric(long id) {
		try {
			JsonObject o = getJson("https://music.163.com/api/song/lyric?id=" + id + "&lv=-1&kv=-1&tv=-1");
			if (o == null) return null;
			if (o.has("nolyric") && o.get("nolyric").isJsonPrimitive() && o.get("nolyric").getAsBoolean()) return null;
			if (o.has("uncollected") && o.get("uncollected").isJsonPrimitive() && o.get("uncollected").getAsBoolean()) return null;
			JsonElement lrc = o.get("lrc");
			if (lrc != null && lrc.isJsonObject()) { String s = str(lrc.getAsJsonObject(), "lyric", ""); return s.isBlank() ? null : s; }
		} catch (Exception e) { IngameMusic.LOG.warn("NetEase lyrics failed: {}", e.toString()); }
		return null;
	}

	// ------------------------------------------------------------------------------------------------ QR login
	public enum LoginState { IDLE, LOADING, WAITING, SCANNED, SUCCESS, EXPIRED, ERROR }
	private static volatile LoginState login = LoginState.IDLE;
	private static volatile boolean[][] qr;
	private static volatile int session, qrVersion;
	private static volatile String qrFile = "";

	public static LoginState loginState() { return login; }
	public static boolean[][] qrMatrix() { return qr; }
	/** Path of the PNG that was saved so it can also be scanned from the gallery (or "" if saving failed). */
	public static String qrFilePath() { return qrFile; }

	public static void startLogin() {
		int my = ++session; login = LoginState.LOADING; qr = null; qrFile = "";
		Thread t = new Thread(() -> runLogin(my), "ingamemusic-netease-login"); t.setDaemon(true); t.start();
	}
	public static void cancelLogin() { session++; if (login != LoginState.SUCCESS) login = LoginState.IDLE; qr = null; }

	private static void runLogin(int my) {
		try {
			Resp kr = weapi("https://music.163.com/weapi/login/qrcode/unikey", "{\"type\":1}", "os=pc; appver=2.10.13");
			String key = kr == null ? null : JsonParser.parseString(kr.body).getAsJsonObject().get("unikey").getAsString();
			if (key == null || key.isEmpty()) { if (my == session) login = LoginState.ERROR; return; }
			if (my != session) return;
			String url = "https://music.163.com/login?codekey=" + key;
			BitMatrix m = new QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, 0, 0, Map.of(EncodeHintType.MARGIN, 1, EncodeHintType.CHARACTER_SET, "UTF-8"));
			boolean[][] grid = new boolean[m.getHeight()][m.getWidth()];
			for (int y = 0; y < m.getHeight(); y++) for (int x = 0; x < m.getWidth(); x++) grid[y][x] = m.get(x, y);
			qr = grid; qrVersion++; login = LoginState.WAITING;
			qrFile = savePng(grid);
			while (my == session) {
				Thread.sleep(2000);
				if (my != session) return;
				Resp r = weapi("https://music.163.com/weapi/login/qrcode/client/login", "{\"key\":\"" + key + "\",\"type\":1}", "os=pc; appver=2.10.13");
				if (r == null) continue;
				int code = JsonParser.parseString(r.body).getAsJsonObject().get("code").getAsInt();
				switch (code) {
					case 801 -> login = LoginState.WAITING;
					case 802 -> login = LoginState.SCANNED;
					case 800 -> { login = LoginState.EXPIRED; return; }
					case 803 -> {
						String c = pickCookies(r.setCookies);
						if (c.contains("MUSIC_U=")) { saveCookie(c); login = LoginState.SUCCESS; } else login = LoginState.ERROR;
						return;
					}
					default -> { }
				}
			}
		} catch (InterruptedException e) { Thread.currentThread().interrupt(); }
		catch (Exception e) { IngameMusic.LOG.warn("NetEase QR login failed: {}", e.toString()); if (my == session) login = LoginState.ERROR; }
	}
	private static String pickCookies(List<String> setCookies) {
		Set<String> wanted = Set.of("MUSIC_U", "__csrf", "MUSIC_A_T", "MUSIC_R_T", "NMTID");
		List<String> parts = new ArrayList<>();
		for (String sc : setCookies) {
			String kv = sc.split(";", 2)[0].trim();
			int eq = kv.indexOf('=');
			if (eq > 0 && wanted.contains(kv.substring(0, eq)) && kv.length() > eq + 1) parts.add(kv);
		}
		return String.join("; ", parts);
	}
	/** Writes the QR code as a PNG next to the config so it can be opened in the gallery and scanned from there. */
	private static String savePng(boolean[][] g) {
		try {
			int scale = 10, n = g.length, size = n * scale;
			java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_INT_RGB);
			for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) img.setRGB(x, y, g[y / scale][x / scale] ? 0x000000 : 0xFFFFFF);
			Path p = com.ingamemusic.Dirs.root().resolve("netease_login_qr.png");
			Files.createDirectories(p.getParent());
			if (!javax.imageio.ImageIO.write(img, "png", p.toFile())) return "";
			return p.toAbsolutePath().toString();
		} catch (Throwable t) { return ""; }
	}

	// ------------------------------------------------------------------------------------------------ HTTP + weapi crypto
	private record Resp(String body, List<String> setCookies) {}
	private static String cookieHeader() { String c = cookie(); return c.isEmpty() ? "os=pc; appver=2.10.13" : c + "; os=pc; appver=2.10.13"; }
	private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
	private static String str(JsonObject o, String k, String d) { return o.has(k) && !o.get(k).isJsonNull() && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : d; }
	private static String code(JsonObject o) { return o == null || !o.has("code") ? "no response" : o.get("code").getAsString(); }

	private static JsonObject getJson(String url) throws IOException, InterruptedException {
		HttpRequest rq = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).header("User-Agent", UA)
			.header("Referer", "https://music.163.com/").header("Accept", "application/json").header("Cookie", cookieHeader()).GET().build();
		HttpResponse<String> r = HTTP.send(rq, HttpResponse.BodyHandlers.ofString());
		if (r.statusCode() != 200) return null;
		JsonElement e = JsonParser.parseString(r.body());
		return e.isJsonObject() ? e.getAsJsonObject() : null;
	}
	private static Resp weapi(String url, String json, String cookie) throws IOException, InterruptedException {
		String secKey = randomKey();
		String params = aes(aes(json, PRESET_KEY), secKey);
		String body = "params=" + enc(params) + "&encSecKey=" + enc(rsa(secKey));
		HttpRequest rq = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(12)).header("User-Agent", UA)
			.header("Referer", "https://music.163.com/").header("Content-Type", "application/x-www-form-urlencoded").header("Cookie", cookie)
			.POST(HttpRequest.BodyPublishers.ofString(body)).build();
		HttpResponse<String> r = HTTP.send(rq, HttpResponse.BodyHandlers.ofString());
		if (r.statusCode() < 200 || r.statusCode() > 299) return null;
		return new Resp(r.body(), r.headers().allValues("Set-Cookie"));
	}

	private static final String PRESET_KEY = "0CoJUm6Qyw8W8jud", IV = "0102030405060708", PUB_EXP = "010001";
	private static final String MODULUS = "00e0b509f6259df8642dbc35662901477df22677ec152b5ff68ace615bb7b725152b3ab17a876aea8a5aa76d2e41762"
		+ "9ec4ee341f56135fccf695280104e0312ecbda92557c93870114af6c9d05c4f7f0c3685b7a46bee255932575cce10b42"
		+ "4d813cfe4875d3e82047b97ddef52741d546b8e289dc6935b3ece0462db0a22b8e7";
	private static final String CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
	private static final SecureRandom RNG = new SecureRandom();
	private static String randomKey() { StringBuilder b = new StringBuilder(); for (int i = 0; i < 16; i++) b.append(CHARS.charAt(RNG.nextInt(CHARS.length()))); return b.toString(); }
	private static String aes(String text, String key) {
		try {
			Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
			c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES"), new IvParameterSpec(IV.getBytes(StandardCharsets.UTF_8)));
			return Base64.getEncoder().encodeToString(c.doFinal(text.getBytes(StandardCharsets.UTF_8)));
		} catch (Exception e) { throw new IllegalStateException(e); }
	}
	private static String rsa(String secKey) {
		byte[] rev = new StringBuilder(secKey).reverse().toString().getBytes(StandardCharsets.UTF_8);
		String hex = new BigInteger(1, rev).modPow(new BigInteger(PUB_EXP, 16), new BigInteger(MODULUS, 16)).toString(16);
		return "0".repeat(Math.max(0, 256 - hex.length())) + hex;
	}
}
