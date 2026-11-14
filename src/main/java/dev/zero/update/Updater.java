package dev.zero.update;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.zero.config.ClientSettings;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.regex.Pattern;

/**
 * Update notice. The only part of ZERØ that uses the internet: it reads one small file from
 * this project's GitHub releases (never from the Minecraft server you play on).
 * <p>
 * It never downloads or installs anything. If a newer version exists it shows a pop-up and
 * can open the release page in your browser; you install the jar yourself.
 */
public final class Updater {
	private Updater() {}

	public static final String REPO = "PlumedeParis/NewClient";
	/** Always this fixed address: the link is never taken from the downloaded file. */
	public static final String PAGE = "https://github.com/" + REPO + "/releases/latest";
	private static final String INFO_URL = PAGE + "/download/version.json";
	private static final Pattern SAFE_VERSION = Pattern.compile("[0-9A-Za-z.+-]{1,30}");

	public enum Mode { ASK, OFF }

	public enum State { IDLE, CHECKING, UP_TO_DATE, AVAILABLE, FAILED }

	public record Info(String version, String changelog) {}

	private static volatile State state = State.IDLE;
	private static volatile Info info;
	private static volatile String error = "";
	private static boolean prompted, whatsNewShown;
	private static String currentOverride; // in-game test only

	private static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(10)).build();

	public static State state() { return state; }

	public static Info info() { return info; }

	public static String error() { return error; }

	public static String current() {
		if (currentOverride != null) return currentOverride;
		return FabricLoader.getInstance().getModContainer("zero").map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("0");
	}

	public static void setCurrentForTest(String v) { currentOverride = v; }

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

	// ---------------------------------------------------------------- saved state

	private static Path stateFile() {
		Path d = FabricLoader.getInstance().getConfigDir().resolve("zero");
		try {
			Files.createDirectories(d);
		} catch (Exception ignored) {
		}
		return d.resolve("update.json");
	}

	private static JsonObject loadState() {
		try {
			Path f = stateFile();
			if (Files.exists(f)) return JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
		} catch (Exception ignored) {
		}
		return new JsonObject();
	}

	private static void saveState(JsonObject o) {
		try {
			Files.writeString(stateFile(), o.toString(), StandardCharsets.UTF_8);
		} catch (Exception ignored) {
		}
	}

	/** "Don't show again" for this version only: the next version asks again. */
	public static void hide(String version) {
		JsonObject o = loadState();
		o.addProperty("hidden", version);
		saveState(o);
	}

	// ---------------------------------------------------------------- check

	/** Called once at game start. */
	public static void init() {
		if (ClientSettings.UPDATES.get() == Mode.ASK) check();
	}

	/** Reads version.json from the latest GitHub release, in the background. */
	public static void check() {
		if (state == State.CHECKING) return;
		state = State.CHECKING;
		Thread t = new Thread(() -> {
			try {
				HttpRequest req = HttpRequest.newBuilder(URI.create(INFO_URL)).timeout(Duration.ofSeconds(15)).header("User-Agent", "ZERO-update-check").GET().build();
				HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
				if (res.statusCode() != 200) throw new IllegalStateException("GitHub answered " + res.statusCode());
				JsonObject o = JsonParser.parseString(res.body()).getAsJsonObject();
				String version = o.get("version").getAsString();
				if (!SAFE_VERSION.matcher(version).matches()) throw new IllegalStateException("unexpected release info");
				String log = o.has("changelog") ? o.get("changelog").getAsString() : "";
				info = new Info(version, log.length() > 4000 ? log.substring(0, 4000) : log);
				state = compare(version, current()) > 0 ? State.AVAILABLE : State.UP_TO_DATE;
			} catch (Exception e) {
				error = e.getMessage() == null ? e.toString() : e.getMessage();
				state = State.FAILED;
			}
		}, "ZERO update check");
		t.setDaemon(true);
		t.start();
	}

	/** Opens the release page in the browser. */
	public static void openPage() {
		net.minecraft.util.Util.getPlatform().openUri(URI.create(PAGE));
	}

	// ---------------------------------------------------------------- title screen prompts

	/** Called while the title screen is shown: "What's new" after you updated, else the notice once per launch. */
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
		if (s.has("hidden") && s.get("hidden").getAsString().equals(info.version())) return;
		prompted = true;
		mc.execute(() -> mc.setScreen(new dev.zero.gui.UpdateScreen(mc.screen)));
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
