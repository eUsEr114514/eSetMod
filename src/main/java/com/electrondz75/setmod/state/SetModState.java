package com.electrondz75.setmod.state;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.PersistentState;

/**
 * Per-player recording data, saved together with the world.
 *
 * <p>Every player has two things:</p>
 * <ul>
 *     <li>a <b>cache</b>: the blocks recorded with the stick since the last {@code /setmod name},
 *         in click order;</li>
 *     <li>a set of <b>named recordings</b> created by {@code /setmod name <name>}, each with its own
 *         animation length assigned by {@code /setmod speed <name> <ticks>}.</li>
 * </ul>
 *
 * <p>Saving the cache clears it, so the next right-click with the stick starts a fresh
 * recording.</p>
 */
public class SetModState extends PersistentState {
	public static final String DATA_NAME = "esetmod";

	/** Hard cap for a single recording, so it can never grow without bounds. */
	public static final int MAX_RECORDS = 4096;

	/** How many named recordings a single player may keep. */
	public static final int MAX_RECORDINGS = 64;

	/** Shortest automatic animation length: one second. */
	public static final int DEFAULT_MIN_DURATION_TICKS = 20;

	/** Longest animation that can be requested: one hour. */
	public static final int MAX_DURATION_TICKS = 72000;

	private static final String PLAYERS_KEY = "players";
	private static final String UUID_KEY = "uuid";
	private static final String PENDING_KEY = "pending";
	private static final String NAMED_KEY = "named";
	private static final String NAME_KEY = "name";
	private static final String BLOCKS_KEY = "blocks";
	private static final String DURATION_KEY = "duration";

	private final Map<UUID, List<RecordedBlock>> pending = new HashMap<>();
	private final Map<UUID, Map<String, Recording>> named = new HashMap<>();

	public SetModState() {
	}

	public SetModState(NbtCompound nbt) {
		NbtList players = nbt.getList(PLAYERS_KEY, NbtElement.COMPOUND_TYPE);

		for (int i = 0; i < players.size(); i++) {
			NbtCompound playerTag = players.getCompound(i);
			UUID uuid = readUuid(playerTag);

			if (uuid == null) {
				continue;
			}

			// "blocks" is the pre-named-recordings format; keep those blocks as the cache.
			NbtList pendingList = playerTag.contains(PENDING_KEY, NbtElement.LIST_TYPE)
					? playerTag.getList(PENDING_KEY, NbtElement.COMPOUND_TYPE)
					: playerTag.getList(BLOCKS_KEY, NbtElement.COMPOUND_TYPE);
			pending.put(uuid, readBlocks(pendingList));

			NbtList namedList = playerTag.getList(NAMED_KEY, NbtElement.COMPOUND_TYPE);
			Map<String, Recording> recordings = new LinkedHashMap<>();

			for (int j = 0; j < namedList.size() && recordings.size() < MAX_RECORDINGS; j++) {
				NbtCompound recordingTag = namedList.getCompound(j);
				String name = recordingTag.getString(NAME_KEY);

				if (name.isEmpty()) {
					continue;
				}

				List<RecordedBlock> blocks = readBlocks(recordingTag.getList(BLOCKS_KEY, NbtElement.COMPOUND_TYPE));
				recordings.put(name, new Recording(blocks, recordingTag.getInt(DURATION_KEY)));
			}

			if (!recordings.isEmpty()) {
				named.put(uuid, recordings);
			}
		}
	}

	@Override
	public NbtCompound writeNbt(NbtCompound nbt) {
		NbtList players = new NbtList();
		Set<UUID> knownPlayers = new TreeSet<>(pending.keySet());
		knownPlayers.addAll(named.keySet());

		for (UUID uuid : knownPlayers) {
			NbtCompound playerTag = new NbtCompound();
			playerTag.putString(UUID_KEY, uuid.toString());

			NbtList pendingList = new NbtList();

			for (RecordedBlock block : pending.getOrDefault(uuid, List.of())) {
				pendingList.add(block.toNbt());
			}

			playerTag.put(PENDING_KEY, pendingList);

			NbtList namedList = new NbtList();

			for (Map.Entry<String, Recording> entry : named.getOrDefault(uuid, Map.of()).entrySet()) {
				Recording recording = entry.getValue();
				NbtCompound recordingTag = new NbtCompound();
				recordingTag.putString(NAME_KEY, entry.getKey());
				recordingTag.putInt(DURATION_KEY, recording.durationTicks());

				NbtList blockList = new NbtList();

				for (RecordedBlock block : recording.blocks()) {
					blockList.add(block.toNbt());
				}

				recordingTag.put(BLOCKS_KEY, blockList);
				namedList.add(recordingTag);
			}

			playerTag.put(NAMED_KEY, namedList);
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

	/* ---------------------------------------------------------------- cache */

	/** Immutable snapshot of the unsaved cache, in click order. */
	public List<RecordedBlock> getPending(UUID playerId) {
		return List.copyOf(pending.getOrDefault(playerId, List.of()));
	}

	public int getPendingCount(UUID playerId) {
		return pending.getOrDefault(playerId, List.of()).size();
	}

	/**
	 * Appends a block to the player's cache.
	 *
	 * <p>Clicking the same block twice does not create a duplicate: the existing entry keeps its
	 * position in the order and only its block state is refreshed.</p>
	 */
	public RecordResult addRecord(UUID playerId, RecordedBlock block) {
		List<RecordedBlock> blocks = pending.computeIfAbsent(playerId, key -> new ArrayList<>());

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

	/** Drops the unsaved cache of the player and returns how many blocks were removed. */
	public int clearPending(UUID playerId) {
		List<RecordedBlock> removed = pending.remove(playerId);
		markDirty();
		return removed == null ? 0 : removed.size();
	}

	/* -------------------------------------------------------------- named */

	/**
	 * Moves the unsaved cache into a named recording and clears the cache.
	 *
	 * <p>Saving over an existing name replaces its blocks but keeps the animation length that was
	 * set for it.</p>
	 */
	public SaveResult savePending(UUID playerId, String name) {
		List<RecordedBlock> cached = pending.get(playerId);

		if (cached == null || cached.isEmpty()) {
			return SaveResult.EMPTY;
		}

		Map<String, Recording> recordings = named.computeIfAbsent(playerId, key -> new LinkedHashMap<>());
		Recording existing = recordings.get(name);

		if (existing == null && recordings.size() >= MAX_RECORDINGS) {
			return SaveResult.LIMIT;
		}

		int duration = existing == null ? Recording.AUTOMATIC_DURATION : existing.durationTicks();
		recordings.put(name, new Recording(cached, duration));
		pending.remove(playerId);
		markDirty();

		return existing == null ? SaveResult.SAVED : SaveResult.REPLACED;
	}

	/** The named recording of the player, or {@code null} when there is none with that name. */
	public Recording getRecording(UUID playerId, String name) {
		Map<String, Recording> recordings = named.get(playerId);

		return recordings == null ? null : recordings.get(name);
	}

	/** Sorted names, used for tab completion and for "which recording?" hints. */
	public List<String> getRecordingNames(UUID playerId) {
		Map<String, Recording> recordings = named.get(playerId);

		if (recordings == null || recordings.isEmpty()) {
			return List.of();
		}

		return List.copyOf(new TreeSet<>(recordings.keySet()));
	}

	/** @return the number of blocks that were dropped, or {@code -1} when the name is unknown */
	public int removeRecording(UUID playerId, String name) {
		Map<String, Recording> recordings = named.get(playerId);

		if (recordings == null) {
			return -1;
		}

		Recording removed = recordings.remove(name);

		if (removed == null) {
			return -1;
		}

		if (recordings.isEmpty()) {
			named.remove(playerId);
		}

		markDirty();
		return removed.blocks().size();
	}

	public void setRecordingDuration(UUID playerId, String name, int ticks) {
		Map<String, Recording> recordings = named.get(playerId);

		if (recordings == null) {
			return;
		}

		Recording recording = recordings.get(name);

		if (recording == null) {
			return;
		}

		recordings.put(name, recording.withDuration(Math.max(1, Math.min(ticks, MAX_DURATION_TICKS))));
		markDirty();
	}

	/**
	 * The animation length in ticks: the value set by {@code /setmod speed} when present, otherwise
	 * one tick per recorded block (with a one second minimum).
	 */
	public static int resolveDuration(Recording recording) {
		if (recording.hasCustomDuration()) {
			return Math.max(1, recording.durationTicks());
		}

		return Math.max(DEFAULT_MIN_DURATION_TICKS, recording.blocks().size());
	}

	private static UUID readUuid(NbtCompound playerTag) {
		if (!playerTag.contains(UUID_KEY, NbtElement.STRING_TYPE)) {
			return null;
		}

		try {
			return UUID.fromString(playerTag.getString(UUID_KEY));
		} catch (IllegalArgumentException exception) {
			return null;
		}
	}

	private static List<RecordedBlock> readBlocks(NbtList blockList) {
		List<RecordedBlock> blocks = new ArrayList<>();

		for (int i = 0; i < blockList.size() && blocks.size() < MAX_RECORDS; i++) {
			blocks.add(RecordedBlock.fromNbt(blockList.getCompound(i)));
		}

		return blocks;
	}

	/** Outcome of {@link #addRecord(UUID, RecordedBlock)}. */
	public enum RecordResult {
		ADDED,
		UPDATED,
		LIMIT
	}

	/** Outcome of {@link #savePending(UUID, String)}. */
	public enum SaveResult {
		SAVED,
		REPLACED,
		EMPTY,
		LIMIT
	}
}
