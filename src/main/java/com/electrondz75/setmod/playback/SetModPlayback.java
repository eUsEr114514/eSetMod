package com.electrondz75.setmod.playback;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

import com.electrondz75.setmod.state.RecordedBlock;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;

/**
 * Server side scheduler that clears a recording and then replays it block by block.
 *
 * <p>Timeline of {@code /setmod start}:</p>
 * <ol>
 *     <li>every recorded block that still holds its recorded state is removed (set to air);</li>
 *     <li>after {@value #START_DELAY_TICKS} ticks the animation begins;</li>
 *     <li>the recorded blocks are placed again, one {@code setblock} at a time, strictly in
 *         recording order, until the configured total duration has elapsed;</li>
 *     <li>the placed blocks are kept - nothing is removed after playback.</li>
 * </ol>
 */
public final class SetModPlayback {
	/** Ticks between {@code /setmod start} and the first placed block. */
	public static final int START_DELAY_TICKS = 20;

	private static final List<Job> JOBS = new ArrayList<>();

	private SetModPlayback() {
	}

	/**
	 * Starts a playback for the given player, replacing any playback that is still running for them.
	 *
	 * @param blocks        immutable snapshot of the recording, in click order
	 * @param durationTicks total length of the animation, in ticks
	 */
	public static void start(MinecraftServer server, UUID playerId, List<RecordedBlock> blocks, int durationTicks) {
		cancel(playerId);
		JOBS.add(new Job(playerId, List.copyOf(blocks), Math.max(1, durationTicks)));
	}

	/** Cancels the player's playback, if any. */
	public static boolean cancel(UUID playerId) {
		return JOBS.removeIf(job -> job.playerId.equals(playerId));
	}

	public static boolean isRunning(UUID playerId) {
		for (Job job : JOBS) {
			if (job.playerId.equals(playerId)) {
				return true;
			}
		}

		return false;
	}

	/** Called at the end of every server tick. */
	public static void tick(MinecraftServer server) {
		if (JOBS.isEmpty()) {
			return;
		}

		Iterator<Job> iterator = JOBS.iterator();

		while (iterator.hasNext()) {
			Job job = iterator.next();

			if (job.tick(server)) {
				iterator.remove();
			}
		}
	}

	/** Forgets every pending playback, used when the integrated server shuts down. */
	public static void clearAll() {
		JOBS.clear();
	}

	/**
	 * Removes every recorded block that still matches the recorded state.
	 *
	 * <p>Blocks the player changed in the meantime are left alone instead of being overwritten with
	 * air.</p>
	 *
	 * @return the number of blocks that were actually removed
	 */
	public static int clearRecordedBlocks(MinecraftServer server, List<RecordedBlock> blocks) {
		int cleared = 0;

		for (RecordedBlock block : blocks) {
			ServerWorld world = server.getWorld(block.dimensionKey());

			if (world == null || !world.getBlockState(block.pos()).equals(block.state())) {
				continue;
			}

			world.setBlockState(block.pos(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
			cleared++;
		}

		return cleared;
	}

	/**
	 * How many blocks of the recording must exist after a number of animation ticks.
	 *
	 * @param elapsedTicks  ticks since the animation started, {@code 1..durationTicks}
	 * @param blockCount    number of recorded blocks
	 * @param durationTicks total animation length
	 * @return cumulative number of blocks to place, never more than {@code blockCount}
	 */
	public static int blocksToPlace(int elapsedTicks, int blockCount, int durationTicks) {
		if (blockCount <= 0 || durationTicks <= 0 || elapsedTicks <= 0) {
			return 0;
		}

		int target = (int) Math.ceil((double) elapsedTicks * blockCount / durationTicks);
		return Math.min(target, blockCount);
	}

	private static void notifyPlayer(MinecraftServer server, UUID playerId, Text message) {
		ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);

		if (player != null) {
			player.sendMessage(message, true);
		}
	}

	private static final class Job {
		private final UUID playerId;
		private final List<RecordedBlock> blocks;
		private final int durationTicks;

		private int remainingDelay = START_DELAY_TICKS;
		private int elapsedTicks;
		private int placedBlocks;

		private Job(UUID playerId, List<RecordedBlock> blocks, int durationTicks) {
			this.playerId = playerId;
			this.blocks = blocks;
			this.durationTicks = durationTicks;
		}

		/** @return {@code true} when the job is finished and can be dropped */
		private boolean tick(MinecraftServer server) {
			if (remainingDelay > 0) {
				remainingDelay--;

				if (remainingDelay == 0) {
					notifyPlayer(server, playerId, Text.translatable("setmod.message.playback.begin", blocks.size(), durationTicks));
				}

				return false;
			}

			elapsedTicks++;

			// Spread the whole recording evenly over the requested duration: with a 200 tick
			// animation and 100 blocks one block appears every second tick, with 50 blocks every
			// fourth, and so on. A duration shorter than the block count places several per tick.
			int target = blocksToPlace(elapsedTicks, blocks.size(), durationTicks);

			while (placedBlocks < target && placedBlocks < blocks.size()) {
				place(server, blocks.get(placedBlocks));
				placedBlocks++;
			}

			if (elapsedTicks >= durationTicks) {
				notifyPlayer(server, playerId, Text.translatable("setmod.message.playback.finished", blocks.size()));
				return true;
			}

			return false;
		}

		private static void place(MinecraftServer server, RecordedBlock block) {
			ServerWorld world = server.getWorld(block.dimensionKey());

			if (world != null) {
				world.setBlockState(block.pos(), block.state(), Block.NOTIFY_ALL);
			}
		}
	}
}
