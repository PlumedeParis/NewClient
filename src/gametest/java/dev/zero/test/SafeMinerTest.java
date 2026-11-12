package dev.zero.test;

import dev.zero.module.ModuleManager;
import dev.zero.modules.SafeMiner;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

import java.util.List;

/** SafeMiner: route found, side approach, lava avoided, follows as you dig, next ore after mining. */
public class SafeMinerTest implements FabricClientGameTest {
	private static void check(boolean ok, String what) {
		System.out.println("[ZERO-SAFEMINER-TEST] " + (ok ? "OK   " : "FAIL ") + what);
	}

	@Override
	public void runTest(ClientGameTestContext ctx) {
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			sp.getClientWorld().waitForChunksRender();
			var server = sp.getServer();
			server.runCommand("gamemode creative @a");
			for (int x = -24; x <= 24; x += 8) server.runCommand("fill " + x + " -63 -24 " + Math.min(24, x + 7) + " -50 24 stone");
			server.runCommand("fill 0 -60 0 0 -59 0 air");
			BlockPos ore = new BlockPos(10, -60, 7);
			server.runCommand("setblock 10 -60 7 diamond_ore");
			// lava pocket right on the direct line between player and ore
			server.runCommand("fill 4 -60 2 6 -59 4 lava");
			server.runCommand("tp @a 0.5 -60 0.5 -55 20");
			ctx.waitTicks(40);
			ctx.runOnClient(mc -> {
				for (var m : ModuleManager.all()) m.setEnabled(false);
				ModuleManager.safeMiner.setEnabled(true);
				ModuleManager.safeMiner.setTestTargets(List.of(ore));
			});
			waitRoute(ctx);
			List<SafeMiner.Step> route = ctx.computeOnClient(mc -> ModuleManager.safeMiner.route());
			check(!route.isEmpty(), "route found (" + route.size() + " steps, " + route.stream().mapToInt(x -> x.mine().size()).sum() + " to dig)");
			if (route.isEmpty()) return;
			check(ore.equals(ctx.computeOnClient(mc -> ModuleManager.safeMiner.goalPos())), "route leads to the diamond");
			SafeMiner.Step last = route.get(route.size() - 1);
			int dx = ore.getX() - last.feet().getX(), dz = ore.getZ() - last.feet().getZ(), dy = ore.getY() - last.feet().getY();
			boolean adjacent = Math.abs(dx) + Math.abs(dz) == 1 && (dy == 0 || dy == 1);
			check(adjacent, "route ends next to the ore (offset " + dx + "," + dy + "," + dz + ")");
			int[] DX = {0, 1, 0, -1}, DZ = {-1, 0, 1, 0};
			check(!(DX[last.dir()] == dx && DZ[last.dir()] == dz), "ore reached from the side, not by digging straight at it");
			boolean nearLava = false;
			for (SafeMiner.Step s : route) for (BlockPos b : s.mine()) for (var d : net.minecraft.core.Direction.values()) {
				BlockPos n = b.relative(d);
				if (n.getX() >= 4 && n.getX() <= 6 && n.getZ() >= 2 && n.getZ() <= 4 && n.getY() >= -60 && n.getY() <= -59) nearLava = true;
			}
			check(!nearLava, "no dug block touches the lava");
			long turns = 0;
			for (int i = 1; i < route.size(); i++) if (route.get(i).dir() != route.get(i - 1).dir()) turns++;
			check(turns <= 4, "straight tunnels (" + turns + " turns)");
			check(route.stream().allMatch(s -> s.feet().getY() == -60), "stays on one level");
			ctx.takeScreenshot("safe_miner");

			// dig the route the way a player would and walk along it
			int mid = route.size() / 2;
			for (int i = 0; i <= mid; i++) {
				for (BlockPos b : route.get(i).mine()) server.runCommand("setblock " + b.getX() + " " + b.getY() + " " + b.getZ() + " air");
				BlockPos f = route.get(i).feet();
				server.runCommand("tp @a " + (f.getX() + 0.5) + " " + f.getY() + " " + (f.getZ() + 0.5));
				ctx.waitTicks(3);
			}
			ctx.waitTicks(10);
			int prog = ctx.computeOnClient(mc -> ModuleManager.safeMiner.progressIndex());
			check(prog >= mid - 1, "progress follows you (" + prog + "/" + route.size() + ")");
			String instr = ctx.computeOnClient(mc -> ModuleManager.safeMiner.instruction());
			check(instr.startsWith("Dig"), "instruction: " + instr);
			ctx.takeScreenshot("safe_miner_halfway");

			// mine the ore -> routes to the next one
			BlockPos ore2 = new BlockPos(-9, -60, -8);
			server.runCommand("setblock -9 -60 -8 deepslate_diamond_ore");
			ctx.waitTicks(5);
			ctx.runOnClient(mc -> ModuleManager.safeMiner.setTestTargets(List.of(ore, ore2)));
			waitRoute(ctx);
			check(ore.equals(ctx.computeOnClient(mc -> ModuleManager.safeMiner.goalPos())), "nearest ore first");
			server.runCommand("setblock 10 -60 7 air");
			ctx.waitTicks(10);
			waitRoute(ctx);
			check(ore2.equals(ctx.computeOnClient(mc -> ModuleManager.safeMiner.goalPos())), "mined ore -> routes to the next ore (" + ctx.computeOnClient(mc -> String.valueOf(ModuleManager.safeMiner.goalPos())) + ")");

			// real visible-ore scanning (no test targets): an exposed diamond in a small cave
			ctx.runOnClient(mc -> ModuleManager.safeMiner.setTestTargets(null));
			server.runCommand("setblock -9 -60 -8 stone");
			server.runCommand("fill 6 -60 -12 7 -59 -12 air");
			server.runCommand("setblock 8 -60 -12 diamond_ore");
			ctx.waitTicks(60);
			waitRoute(ctx);
			check(new BlockPos(8, -60, -12).equals(ctx.computeOnClient(mc -> ModuleManager.safeMiner.goalPos())), "finds exposed real ores by itself");
			ctx.runOnClient(mc -> ModuleManager.safeMiner.setEnabled(false));
		}
	}

	private static void waitRoute(ClientGameTestContext ctx) {
		for (int i = 0; i < 100; i++) {
			if (ctx.computeOnClient(mc -> !ModuleManager.safeMiner.searching() && !ModuleManager.safeMiner.route().isEmpty())) return;
			ctx.waitTicks(2);
		}
	}
}
