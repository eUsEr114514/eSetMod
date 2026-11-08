package com.electrondz75.setmod.state;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.PersistentState;

/**
 * Per-player recording cache, saved together with the world.
 *
 * <p>For every player we keep the ordered list of recorded blocks plus an optional animation
 * duration (set with {@code /setmod speed}). Records are only ever cleared by
 * {@code /setmod clear}, so a recording can be replayed more than once.</p>
 */
public class SetModState extends PersistentState {
	public static final String DATA_NAME = "esetmod";

	/** Hard cap so a single stack of records can never grow without bounds. */
	public static final int MAX_RECORDS = 4096;

	/** Shortest automatic animation length: one second. */
	public static final int DEFAULT_MIN_DURATION_TICKS = 20;

	/** Longest animation that can be requested: one hour. */
	public static final int MAX_DURATION_TICKS = 72000;

	private static final String PLAYERS_KEY = "players";
	private static final String UUID_KEY = "uuid";
	private static final String BLOCKS_KEY = "blocks";
	private static final String DURATION_KEY = "duration";

	private final Map<UUID, List<RecordedBlock>> records = new HashMap<>();
	private final Map<UUID, Integer> durations = new HashMap<>();

	public SetModState() {
	}

	public SetModState(NbtCompound nbt) {
		NbtList players = nbt.getList(PLAYERS_KEY, NbtElement.COMPOUND_TYPE);

		for (int i = 0; i < players.size(); i++) {
			NbtCompound playerTag = players.getCompound(i);

			if (!playerTag.contains(UUID_KEY, NbtElement.STRING_TYPE)) {
				continue;
			}

			UUID uuid;

			try {
				uuid = UUID.fromString(playerTag.getString(UUID_KEY));
			} catch (IllegalArgumentException exception) {
				continue;
			}

			List<RecordedBlock> blocks = new ArrayList<>();
			NbtList blockList = playerTag.getList(BLOCKS_KEY, NbtElement.COMPOUND_TYPE);

			for (int j = 0; j < blockList.size() && blocks.size() < MAX_RECORDS; j++) {
				blocks.add(RecordedBlock.fromNbt(blockList.getCompound(j)));
			}

			records.put(uuid, blocks);

			if (playerTag.contains(DURATION_KEY, NbtElement.INT_TYPE)) {
				durations.put(uuid, playerTag.getInt(DURATION_KEY));
			}
		}
	}

	@Override
	public NbtCompound writeNbt(NbtCompound nbt) {
		NbtList players = new NbtList();
		Set<UUID> knownPlayers = new LinkedHashSet<>(records.keySet());
		knownPlayers.addAll(durations.keySet());

		for (UUID uuid : knownPlayers) {
			NbtCompound playerTag = new NbtCompound();
			playerTag.putString(UUID_KEY, uuid.toString());

			NbtList blockList = new NbtList();

			for (RecordedBlock block : records.getOrDefault(uuid, List.of())) {
				blockList.add(block.toNbt());
			}

			playerTag.put(BLOCKS_KEY, blockList);

			Integer duration = durations.get(uuid);

			if (duration != null) {
				playerTag.putInt(DURATION_KEY, duration);
			}

			players.add(playerTag);
		}

		nbt.put(PLAYERS_KEY, players);
		return nbt;
	}

	public static SetModState get(MinecraftServer server) {
		return server.getOverworld()
				.getPersistentStateManager()
				.getOrCreate(SetModState::new, () -> new SetModState(), DATA_NAME);
	}

	/** Immutable snapshot of the player's recording, in click order. */
	public List<RecordedBlock> getRecords(UUID playerId) {
		return List.copyOf(records.getOrDefault(playerId, List.of()));
	}

	public int getRecordCount(UUID playerId) {
		return records.getOrDefault(playerId, List.of()).size();
	}

	/**
	 * Appends a block to the player's recording.
	 *
	 * <p>Clicking the same block twice does not create a duplicate: the existing entry keeps its
	 * position in the order and only its block state is refreshed.</p>
	 */
	public RecordResult addRecord(UUID playerId, RecordedBlock block) {
		List<RecordedBlock> blocks = records.computeIfAbsent(playerId, key -> new ArrayList<>());

		for (int i = 0; i < blocks.size(); i++) {
			RecordedBlock existing = blocks.get(i);

			if (existing.dimension().equals(block.dimension()) && existing.pos().equals(block.pos())) {
				if (!existing.state().equals(block.state())) {
					blocks.set(i, block);
					markDirty();
				}

				return RecordResult.UPDATED;
			}
		}

		if (blocks.size() >= MAX_RECORDS) {
			return RecordResult.LIMIT;
		}

		blocks.add(block);
		markDirty();
		return RecordResult.ADDED;
	}

	/** Drops every record of the player and returns how many were removed. */
	public int clearRecords(UUID playerId) {
		List<RecordedBlock> removed = records.remove(playerId);
		markDirty();
		return removed == null ? 0 : removed.size();
	}

	public boolean hasCustomDuration(UUID playerId) {
		return durations.containsKey(playerId);
	}

	public int getCustomDuration(UUID playerId) {
		return durations.getOrDefault(playerId, -1);
	}

	/**
	 * The animation length in ticks: the value set by {@code /setmod speed} when present, otherwise
	 * one tick per recorded block (with a one second minimum).
	 */
	public int resolveDuration(UUID playerId, int blockCount) {
		Integer configured = durations.get(playerId);

		if (configured != null) {
			return Math.max(1, configured);
		}

		return Math.max(DEFAULT_MIN_DURATION_TICKS, blockCount);
	}

	public void setDuration(UUID playerId, int ticks) {
		durations.put(playerId, Math.max(1, Math.min(ticks, MAX_DURATION_TICKS)));
		markDirty();
	}

	/** Outcome of {@link #addRecord(UUID, RecordedBlock)}. */
	public enum RecordResult {
		ADDED,
		UPDATED,
		LIMIT
	}
}
