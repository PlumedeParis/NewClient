package dev.zero.update;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.zero.ZeroClient;
import dev.zero.config.ClientSettings;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Update checks and installs. The only part of ZERØ that uses the internet: it talks to
 * github.com (this project's releases), never to the Minecraft server you play on.
 * <ol>
 *   <li>At start, reads {@code version.json} from the latest release (version, jar, SHA-256, changelog).</li>
 *   <li>If newer, the title screen asks: update now / next launch / later / skip this version.</li>
 *   <li>The jar is downloaded to {@code config/zero/update}, and its SHA-256 must match.</li>
 *   <li>When Minecraft closes, a small helper process swaps the jars (a running jar is locked on Windows),
 *       and with "update now" starts Minecraft again.</li>
 * </ol>
 */
public final class Updater {
	private Updater() {}

	public static final String REPO = "PlumedeParis/NewClient";
	private static final String INFO_URL = "https://github.com/" + REPO + "/releases/latest/download/version.json";
	private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9._+-]{1,80}");

	public enum Mode { ASK, OFF }

	public enum State { IDLE, CHECKING, UP_TO_DATE, AVAILABLE, DOWNLOADING, READY, FAILED }

	public record Info(String version, String tag, String jar, String sha256, String changelog) {}

	private static volatile State state = State.IDLE;
	private static volatile Info info;
	private static volatile float progress;
	private static volatile String error = "";
	private static volatile boolean restartAfterDownload;
	private static boolean prompted, armed, whatsNewShown;
	private static String currentOverride; // in-game test only

	private static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(10)).build();

	// ---------------------------------------------------------------- basics

	public static State state() { return state; }

	public static Info info() { return info; }

	public static float progress() { return progress; }

	public static String error() { return error; }

	public static String current() {
		if (currentOverride != null) return currentOverride;
		return FabricLoader.getInstance().getModContainer("zero").map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("0");
	}

	public static void setCurrentForTest(String v) { currentOverride = v; }

	/** The ZERØ jar in the mods folder (null when running from an IDE / dev folder: nothing to update). */
	public static Path ownJar() {
		try {
			for (Path p : FabricLoader.getInstance().getModContainer("zero").orElseThrow().getOrigin().getPaths())
				if (Files.isRegularFile(p) && p.getFileName().toString().endsWith(".jar")) return p;
		} catch (Exception ignored) {
		}
		return null;
	}

	static Path dir() {
		Path d = FabricLoader.getInstance().getConfigDir().resolve("zero").resolve("update");
		try {
			Files.createDirectories(d);
		} catch (Exception ignored) {
		}
		return d;
	}

	/** 1 if a is newer than b, -1 if older, 0 if equal ("2.10.0" > "2.9.3"). */
	public static int compare(String a, String b) {
		String[] x = a.split("[.+-]"), y = b.split("[.+-]");
		for (int i = 0; i < Math.max(x.length, y.length); i++) {
			int p = i < x.length ? num(x[i]) : 0, q = i < y.length ? num(y[i]) : 0;
			if (p != q) return p > q ? 1 : -1;
		}
		return 0;
	}

	private static int num(String s) {
		try {
			return Integer.parseInt(s.replaceAll("\\D", ""));
		} catch (Exception e) {
			return 0;
		}
	}

	// ---------------------------------------------------------------- saved state (skip, last seen version)

	private static JsonObject loadState() {
		try {
			Path f = dir().resolve("state.json");
			if (Files.exists(f)) return JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
		} catch (Exception ignored) {
		}
		return new JsonObject();
	}

	private static void saveState(JsonObject o) {
		try {
			Files.writeString(dir().resolve("state.json"), o.toString(), StandardCharsets.UTF_8);
		} catch (Exception ignored) {
		}
	}

	public static void skip(String version) {
		JsonObject o = loadState();
		o.addProperty("skip", version);
		saveState(o);
	}

	// ---------------------------------------------------------------- startup

	/** Called once at game start. */
	public static void init() {
		// an update downloaded earlier but not installed yet (game crashed, or "next launch")
		try {
			Path pending = dir().resolve("pending.json");
			if (Files.exists(pending)) {
				JsonObject p = JsonParser.parseString(Files.readString(pending, StandardCharsets.UTF_8)).getAsJsonObject();
				String v = p.get("version").getAsString();
				Path jar = dir().resolve(p.get("file").getAsString());
				if (compare(v, current()) > 0 && Files.isRegularFile(jar) && sha256(jar).equalsIgnoreCase(p.get("sha256").getAsString())) {
					info = new Info(v, p.has("tag") ? p.get("tag").getAsString() : "", p.get("file").getAsString(), p.get("sha256").getAsString(), p.has("changelog") ? p.get("changelog").getAsString() : "");
					state = State.READY;
					arm(false);
					ZeroClient.LOGGER.info("ZERØ {} is ready and will be installed when Minecraft closes", v);
					return;
				}
				Files.deleteIfExists(pending);
				Files.deleteIfExists(jar);
			}
		} catch (Exception e) {
			ZeroClient.LOGGER.warn("Could not read the pending update", e);
		}
		if (ClientSettings.UPDATES.get() == Mode.ASK && ownJar() != null) check();
	}

	/** Reads version.json from the latest GitHub release, in the background. */
	public static void check() {
		if (state == State.CHECKING || state == State.DOWNLOADING || state == State.READY) return;
		state = State.CHECKING;
		Thread t = new Thread(() -> {
			try {
				HttpRequest req = HttpRequest.newBuilder(URI.create(INFO_URL)).timeout(Duration.ofSeconds(15)).header("User-Agent", "ZERO-updater").GET().build();
				HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
				if (res.statusCode() != 200) throw new IllegalStateException("GitHub answered " + res.statusCode());
				JsonObject o = JsonParser.parseString(res.body()).getAsJsonObject();
				Info i = new Info(o.get("version").getAsString(), o.get("tag").getAsString(), o.get("jar").getAsString(),
					o.get("sha256").getAsString(), o.has("changelog") ? o.get("changelog").getAsString() : "");
				if (!SAFE_NAME.matcher(i.tag()).matches() || !SAFE_NAME.matcher(i.jar()).matches() || !i.jar().endsWith(".jar") || !i.sha256().matches("[0-9a-fA-F]{64}"))
					throw new IllegalStateException("unexpected release info");
				info = i;
				state = compare(i.version(), current()) > 0 ? State.AVAILABLE : State.UP_TO_DATE;
			} catch (Exception e) {
				error = e.getMessage() == null ? e.toString() : e.getMessage();
				state = State.FAILED;
			}
		}, "ZERO update check");
		t.setDaemon(true);
		t.start();
	}

	// ---------------------------------------------------------------- title screen prompts

	/** Called while the title screen is shown: "What's new" after an update, else the update prompt once per launch. */
	public static void onTitleScreen() {
		Minecraft mc = Minecraft.getInstance();
		if (!whatsNewShown) {
			whatsNewShown = true;
			JsonObject s = loadState();
			String last = s.has("lastSeen") ? s.get("lastSeen").getAsString() : null;
			String cur = current();
			s.addProperty("lastSeen", cur);
			saveState(s);
			if (last != null && compare(cur, last) > 0) {
				mc.execute(() -> mc.setScreen(new dev.zero.gui.WhatsNewScreen(mc.screen, last, cur)));
				return;
			}
		}
		if (prompted || state != State.AVAILABLE || info == null) return;
		JsonObject s = loadState();
		if (s.has("skip") && s.get("skip").getAsString().equals(info.version())) return;
		prompted = true;
		mc.execute(() -> mc.setScreen(new dev.zero.gui.UpdateScreen(mc.screen)));
	}

	// ---------------------------------------------------------------- download

	/** Downloads and verifies the new jar. restart = close Minecraft and install right after. */
	public static void download(boolean restart) {
		Info i = info;
		if (i == null || state == State.DOWNLOADING) return;
		restartAfterDownload = restart;
		if (state == State.READY) {
			if (restart) restartNow();
			return;
		}
		state = State.DOWNLOADING;
		progress = 0;
		Thread t = new Thread(() -> {
			Path part = dir().resolve(i.jar() + ".part"), jar = dir().resolve(i.jar());
			try {
				URI uri = URI.create("https://github.com/" + REPO + "/releases/download/" + i.tag() + "/" + i.jar());
				HttpRequest req = HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(3)).header("User-Agent", "ZERO-updater").GET().build();
				HttpResponse<InputStream> res = HTTP.send(req, HttpResponse.BodyHandlers.ofInputStream());
				if (res.statusCode() != 200) throw new IllegalStateException("GitHub answered " + res.statusCode());
				long total = res.headers().firstValueAsLong("Content-Length").orElse(-1);
				MessageDigest sha = MessageDigest.getInstance("SHA-256");
				try (InputStream in = res.body(); OutputStream out = Files.newOutputStream(part)) {
					byte[] buf = new byte[16384];
					long done = 0;
					for (int n; (n = in.read(buf)) > 0; ) {
						out.write(buf, 0, n);
						sha.update(buf, 0, n);
						done += n;
						if (total > 0) progress = Math.min(1f, done / (float) total);
						if (done > 64L << 20) throw new IllegalStateException("download too big");
					}
				}
				String got = HexFormat.of().formatHex(sha.digest());
				if (!got.equalsIgnoreCase(i.sha256())) {
					Files.deleteIfExists(part);
					throw new IllegalStateException("the download is damaged (fingerprint does not match), nothing was installed");
				}
				Files.move(part, jar, StandardCopyOption.REPLACE_EXISTING);
				JsonObject p = new JsonObject();
				p.addProperty("version", i.version());
				p.addProperty("tag", i.tag());
				p.addProperty("file", i.jar());
				p.addProperty("sha256", i.sha256());
				p.addProperty("changelog", i.changelog());
				Files.writeString(dir().resolve("pending.json"), p.toString(), StandardCharsets.UTF_8);
				progress = 1;
				state = State.READY;
				arm(false);
				if (restartAfterDownload) Minecraft.getInstance().execute(Updater::restartNow);
			} catch (Exception e) {
				try {
					Files.deleteIfExists(part);
				} catch (Exception ignored) {
				}
				error = e.getMessage() == null ? e.toString() : e.getMessage();
				state = State.FAILED;
			}
		}, "ZERO update download");
		t.setDaemon(true);
		t.start();
	}

	// ---------------------------------------------------------------- install on exit

	private static volatile boolean relaunch;

	/** Installs the downloaded jar when Minecraft closes (each launch arms it once). */
	private static synchronized void arm(boolean restart) {
		if (restart) relaunch = true;
		if (armed) return;
		armed = true;
		Runtime.getRuntime().addShutdownHook(new Thread(Updater::launchHelper, "ZERO update install"));
	}

	/** "Update now": close Minecraft; the helper installs and starts it again. */
	public static void restartNow() {
		arm(true);
		Minecraft.getInstance().stop();
	}

	private static void launchHelper() {
		Info i = info;
		Path old = ownJar();
		if (i == null || old == null) return;
		try {
			Path dir = dir();
			Path newJar = dir.resolve(i.jar());
			if (!Files.isRegularFile(newJar)) return;
			// the helper runs from a copy: the real jar is about to be deleted
			Path helper = dir.resolve("helper.jar");
			Files.copy(old, helper, StandardCopyOption.REPLACE_EXISTING);
			List<String> cmd = List.of(javaExe(), "-cp", helper.toString(), SwapHelper.class.getName(),
				String.valueOf(ProcessHandle.current().pid()), old.toString(), newJar.toString(), old.resolveSibling(i.jar()).toString(),
				dir.resolve("update.log").toString(), relaunch ? "1" : "0", FabricLoader.getInstance().getGameDir().toString());
			Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(dir.resolve("helper.log").toFile())).start();
			// how to start Minecraft again goes through the pipe, never to a file (it contains your session)
			try (Writer w = new OutputStreamWriter(p.getOutputStream(), StandardCharsets.UTF_8)) {
				if (relaunch) for (String a : relaunchCommand()) w.write(Base64.getEncoder().encodeToString(a.getBytes(StandardCharsets.UTF_8)) + "\n");
			}
		} catch (Exception e) {
			ZeroClient.LOGGER.warn("Could not start the ZERØ updater", e);
		}
	}

	private static String javaExe() {
		return ProcessHandle.current().info().command().orElseGet(() -> {
			boolean win = System.getProperty("os.name", "").toLowerCase().contains("win");
			return Path.of(System.getProperty("java.home"), "bin", win ? "java.exe" : "java").toString();
		});
	}

	/** The same Java, JVM options, class path and game arguments as this launch. */
	static List<String> relaunchCommand() {
		List<String> cmd = new ArrayList<>();
		cmd.add(javaExe());
		for (String a : ManagementFactory.getRuntimeMXBean().getInputArguments()) if (!a.startsWith("-agentlib:jdwp")) cmd.add(a);
		cmd.add("-cp");
		cmd.add(System.getProperty("java.class.path"));
		cmd.add("net.fabricmc.loader.impl.launch.knot.KnotClient");
		cmd.addAll(List.of(FabricLoader.getInstance().getLaunchArguments(false)));
		return cmd;
	}

	static String sha256(Path file) throws Exception {
		MessageDigest sha = MessageDigest.getInstance("SHA-256");
		try (InputStream in = Files.newInputStream(file)) {
			byte[] buf = new byte[16384];
			for (int n; (n = in.read(buf)) > 0; ) sha.update(buf, 0, n);
		}
		return HexFormat.of().formatHex(sha.digest());
	}

	// ---------------------------------------------------------------- changelog (bundled in the jar)

	/** Changelog sections newer than {@code after} up to and including {@code upTo} (markdown). */
	public static String changelog(String after, String upTo) {
		String text;
		try (InputStream in = Updater.class.getResourceAsStream("/assets/zero/changelog.md")) {
			text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (Exception e) {
			return "";
		}
		StringBuilder out = new StringBuilder();
		boolean keep = false;
		for (String line : text.split("\n")) {
			if (line.startsWith("## ")) {
				String v = line.substring(3).trim();
				keep = (after == null || compare(v, after) > 0) && compare(v, upTo) <= 0;
			}
			if (keep) out.append(line).append('\n');
		}
		return out.toString().trim();
	}
}
