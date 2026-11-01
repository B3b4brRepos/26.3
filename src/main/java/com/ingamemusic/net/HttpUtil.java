package com.ingamemusic.net;

import com.ingamemusic.Dirs;
import com.ingamemusic.IngameMusic;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.*;

public final class HttpUtil {
	private static final HttpClient CLIENT = HttpClient.newBuilder()
		.followRedirects(HttpClient.Redirect.ALWAYS).connectTimeout(Duration.ofSeconds(10)).executor(IngameMusic.IO).build();
	public record Resp(int status, String body) {}
	private static final String UA = "IngameMusic/1.0 (Minecraft Fabric mod)";

	private static HttpRequest.Builder req(String url) {
		return HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).header("User-Agent", UA);
	}
	public static CompletableFuture<Resp> get(String url) { return get(url, 0); }
	/** GET with up to 2 automatic retries on timeouts, HTTP 429 and 5xx (a busy server often works a second later). */
	private static CompletableFuture<Resp> get(String url, int attempt) {
		return CLIENT.sendAsync(req(url).GET().build(), HttpResponse.BodyHandlers.ofString())
			.<CompletableFuture<Resp>>handle((r, ex) -> {
				boolean retry = attempt < 2 && (ex != null || r.statusCode() == 429 || r.statusCode() >= 500);
				if (!retry) return ex != null ? CompletableFuture.<Resp>failedFuture(ex) : CompletableFuture.completedFuture(new Resp(r.statusCode(), r.body()));
				Executor later = CompletableFuture.delayedExecutor(600L * (attempt + 1), TimeUnit.MILLISECONDS, IngameMusic.IO);
				return CompletableFuture.supplyAsync(() -> (Void) null, later).thenCompose(v -> get(url, attempt + 1));
			}).thenCompose(f -> f);
	}
	public static CompletableFuture<byte[]> bytes(String url) {
		return CLIENT.sendAsync(req(url).GET().build(), HttpResponse.BodyHandlers.ofByteArray()).thenApply(r -> {
			if (r.statusCode() != 200) throw new CompletionException(new IOException("HTTP " + r.statusCode()));
			return r.body();
		});
	}
	/** Blocking download (called on the audio thread; interruptible). */
	public static void download(String url, Path target) throws IOException, InterruptedException {
		HttpRequest rq = req(url).timeout(Duration.ofMinutes(5)).GET().build();
		HttpResponse<InputStream> r = CLIENT.send(rq, HttpResponse.BodyHandlers.ofInputStream());
		if (r.statusCode() != 200) { r.body().close(); throw new IOException("Audio server returned HTTP " + r.statusCode()); }
		Path tmp = target.resolveSibling(target.getFileName() + ".part");
		try (InputStream in = r.body()) { Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING); Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING); }
		finally { try { Files.deleteIfExists(tmp); } catch (IOException ignored) {} }
		trimCache(target);
	}
	private static final long MAX_CACHE = 256L * 1024 * 1024;
	private static void trimCache(Path keep) {
		try (var s = Files.list(Dirs.cache())) {
			var files = s.filter(p -> p.toString().endsWith(".mp3")).sorted(java.util.Comparator.comparingLong(p -> p.toFile().lastModified())).toList();
			long total = 0; for (Path p : files) total += p.toFile().length();
			for (Path p : files) { if (total <= MAX_CACHE) break; if (p.equals(keep)) continue; total -= p.toFile().length(); Files.deleteIfExists(p); }
		} catch (IOException ignored) {}
	}
	public static String friendly(Throwable t) {
		while ((t instanceof CompletionException || t instanceof ExecutionException) && t.getCause() != null) t = t.getCause();
		if (t instanceof HttpConnectTimeoutException || t instanceof HttpTimeoutException) return "Connection timed out";
		if (t instanceof UnknownHostException || t instanceof ConnectException || (t.getCause() instanceof UnknownHostException)) return "No internet connection / server unreachable";
		String m = t.getMessage();
		return m == null ? t.getClass().getSimpleName() : m;
	}
}
