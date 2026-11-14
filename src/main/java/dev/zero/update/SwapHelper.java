package dev.zero.update;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Tiny program run in its own Java process (from a copy of the ZERØ jar) once Minecraft closes:
 * waits for the game to exit, puts the new jar in the mods folder, removes the old one, and
 * optionally starts Minecraft again. Uses plain Java only (Minecraft is not loaded here).
 * <p>
 * args: gamePid oldJar newJar targetJar logFile relaunch(0/1) gameDir
 * stdin (relaunch only): the command to start the game, one Base64 argument per line.
 */
public final class SwapHelper {
	private SwapHelper() {}

	public static void main(String[] args) throws Exception {
		long pid = Long.parseLong(args[0]);
		Path oldJar = Path.of(args[1]), newJar = Path.of(args[2]), target = Path.of(args[3]), log = Path.of(args[4]);
		boolean relaunch = "1".equals(args[5]);
		Path gameDir = Path.of(args[6]);
		List<String> command = new ArrayList<>();
		if (relaunch) {
			// read everything now: the game writes it before it exits
			BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
			for (String line; (line = in.readLine()) != null; ) if (!line.isEmpty()) command.add(new String(Base64.getDecoder().decode(line), StandardCharsets.UTF_8));
		}
		log(log, "waiting for Minecraft (pid " + pid + ") to close");
		Optional<ProcessHandle> game = ProcessHandle.of(pid);
		if (game.isPresent()) {
			try {
				game.get().onExit().get(3, TimeUnit.MINUTES);
			} catch (Exception e) {
				log(log, "Minecraft did not close in time, update cancelled");
				return;
			}
		}
		Thread.sleep(800); // let the OS release file locks
		boolean ok = swap(oldJar, newJar, target, log);
		if (ok && relaunch && !command.isEmpty()) {
			try {
				new ProcessBuilder(command).directory(gameDir.toFile()).redirectErrorStream(true)
					.redirectOutput(ProcessBuilder.Redirect.appendTo(log.resolveSibling("relaunch.log").toFile())).start();
				log(log, "Minecraft restarted");
			} catch (Exception e) {
				log(log, "could not restart Minecraft (start it again from your launcher): " + e);
			}
		}
	}

	/** New jar in, old jar out. If the old jar cannot be removed, the new one is taken back out (never two ZERØ jars). */
	static boolean swap(Path oldJar, Path newJar, Path target, Path log) throws Exception {
		if (!Files.isRegularFile(newJar)) {
			log(log, "nothing to install: " + newJar);
			return false;
		}
		Files.copy(newJar, target, StandardCopyOption.REPLACE_EXISTING);
		if (!target.equals(oldJar)) {
			boolean deleted = false;
			for (int i = 0; i < 40 && !deleted; i++) {
				try {
					Files.deleteIfExists(oldJar);
					deleted = true;
				} catch (Exception e) {
					Thread.sleep(250); // still locked (Windows)
				}
			}
			if (!deleted) {
				Files.deleteIfExists(target);
				log(log, "the old jar is still in use, update cancelled: " + oldJar);
				return false;
			}
		}
		Files.deleteIfExists(newJar);
		Files.deleteIfExists(newJar.resolveSibling("pending.json"));
		log(log, "installed " + target.getFileName());
		return true;
	}

	private static void log(Path log, String msg) {
		try {
			Files.writeString(log, LocalDateTime.now() + " " + msg + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (Exception ignored) {
		}
	}
}
