package com.electrondz75.setmod.command;

import java.util.Collection;
import java.util.Set;
import java.util.TreeSet;

import com.electrondz75.setmod.playback.SetModPlayback;
import com.electrondz75.setmod.state.Recording;
import com.electrondz75.setmod.state.SetModState;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;

import net.minecraft.command.CommandSource;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

/**
 * {@code /setmod name <targets> <name> | start <targets> <name> | speed <targets> <name> [ticks] |
 * clear <targets> [name]}.
 *
 * <p>Every sub command takes an explicit execution target, so the commands can be used from command
 * blocks and from {@code /execute as ... run setmod ...}: the target selector decides whose
 * recordings are read and written. The source itself does not need to be a player.</p>
 */
public final class SetModCommand {
	/** Longest accepted recording name. */
	public static final int MAX_NAME_LENGTH = 32;

	private static final String TARGETS = "targets";
	private static final String NAME = "name";
	private static final String TICKS = "ticks";

	private static final SuggestionProvider<ServerCommandSource> RECORDING_NAMES = (context, builder) -> {
		try {
			Collection<ServerPlayerEntity> targets = EntityArgumentType.getPlayers(context, TARGETS);
			Set<String> names = new TreeSet<>();
			SetModState state = SetModState.get(context.getSource().getServer());

			for (ServerPlayerEntity target : targets) {
				names.addAll(state.getRecordingNames(target.getUuid()));
			}

			return CommandSource.suggestMatching(names, builder);
		} catch (Exception exception) {
			// The target selector is not resolvable yet (or not a player selector): no suggestions.
			return builder.buildFuture();
		}
	};

	private SetModCommand() {
	}

	public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
		dispatcher.register(CommandManager.literal("setmod")
				.requires(source -> source.hasPermissionLevel(2))
				.executes(context -> {
					context.getSource().sendFeedback(() -> Text.translatable("setmod.command.usage"), false);
					return 1;
				})
				.then(CommandManager.literal("name")
						.then(CommandManager.argument(TARGETS, EntityArgumentType.players())
								.then(CommandManager.argument(NAME, StringArgumentType.word())
										.executes(SetModCommand::name))))
				.then(CommandManager.literal("start")
						.then(CommandManager.argument(TARGETS, EntityArgumentType.players())
								.then(CommandManager.argument(NAME, StringArgumentType.word())
										.suggests(RECORDING_NAMES)
										.executes(SetModCommand::start))))
				.then(CommandManager.literal("speed")
						.then(CommandManager.argument(TARGETS, EntityArgumentType.players())
								.then(CommandManager.argument(NAME, StringArgumentType.word())
										.suggests(RECORDING_NAMES)
										.executes(SetModCommand::speedQuery)
										.then(CommandManager.argument(TICKS, IntegerArgumentType.integer(1, SetModState.MAX_DURATION_TICKS))
												.executes(SetModCommand::speedSet)))))
				.then(CommandManager.literal("clear")
						.then(CommandManager.argument(TARGETS, EntityArgumentType.players())
								.executes(SetModCommand::clearCache)
								.then(CommandManager.argument(NAME, StringArgumentType.word())
										.suggests(RECORDING_NAMES)
										.executes(SetModCommand::clearRecording)))));
	}

	/** {@code /setmod name <targets> <name>}: store each target's cache under a name and empty it. */
	private static int name(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
		ServerCommandSource source = context.getSource();
		Collection<ServerPlayerEntity> targets = EntityArgumentType.getPlayers(context, TARGETS);
		String name = StringArgumentType.getString(context, NAME);

		if (!validateName(source, name)) {
			return 0;
		}

		SetModState state = SetModState.get(source.getServer());
		int successes = 0;

		for (ServerPlayerEntity target : targets) {
			int blockCount = state.getPendingCount(target.getUuid());

			switch (state.savePending(target.getUuid(), name)) {
				case EMPTY -> feedback(source, targets, target, Text.translatable("setmod.command.name.empty"), true);
				case LIMIT -> feedback(source, targets, target,
						Text.translatable("setmod.command.name.limit", SetModState.MAX_RECORDINGS), true);
				case SAVED -> {
					feedback(source, targets, target, Text.translatable("setmod.command.name.saved", blockCount, name), false);
					successes++;
				}
				case REPLACED -> {
					feedback(source, targets, target, Text.translatable("setmod.command.name.replaced", blockCount, name), false);
					successes++;
				}
			}
		}

		return successes > 0 ? 1 : 0;
	}

	/** {@code /setmod start <targets> <name>}: clear that recording, then replay it after the delay. */
	private static int start(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
		ServerCommandSource source = context.getSource();
		Collection<ServerPlayerEntity> targets = EntityArgumentType.getPlayers(context, TARGETS);
		String name = StringArgumentType.getString(context, NAME);

		if (!validateName(source, name)) {
			return 0;
		}

		SetModState state = SetModState.get(source.getServer());
		int successes = 0;

		for (ServerPlayerEntity target : targets) {
			Recording recording = state.getRecording(target.getUuid(), name);

			if (recording == null) {
				feedback(source, targets, target, Text.translatable("setmod.command.start.unknown", name), true);
				continue;
			}

			if (recording.blocks().isEmpty()) {
				feedback(source, targets, target, Text.translatable("setmod.command.start.emptyRecording", name), true);
				continue;
			}

			int duration = SetModState.resolveDuration(recording);
			int cleared = SetModPlayback.clearRecordedBlocks(source.getServer(), recording.blocks());
			SetModPlayback.start(source.getServer(), target.getUuid(), name, recording.blocks(), duration);

			feedback(source, targets, target, Text.translatable("setmod.command.start.started",
					name, recording.blocks().size(), cleared, SetModPlayback.START_DELAY_TICKS, duration), false);
			successes++;
		}

		return successes > 0 ? 1 : 0;
	}

	/** {@code /setmod speed <targets> <name>}: report the duration that is currently in effect. */
	private static int speedQuery(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
		ServerCommandSource source = context.getSource();
		Collection<ServerPlayerEntity> targets = EntityArgumentType.getPlayers(context, TARGETS);
		String name = StringArgumentType.getString(context, NAME);

		if (!validateName(source, name)) {
			return 0;
		}

		SetModState state = SetModState.get(source.getServer());
		int successes = 0;

		for (ServerPlayerEntity target : targets) {
			Recording recording = state.getRecording(target.getUuid(), name);

			if (recording == null) {
				feedback(source, targets, target, Text.translatable("setmod.command.speed.unknown", name), true);
				continue;
			}

			int duration = SetModState.resolveDuration(recording);
			int blockCount = recording.blocks().size();

			if (recording.hasCustomDuration()) {
				feedback(source, targets, target, Text.translatable("setmod.command.speed.get", name, duration, blockCount), false);
			} else {
				feedback(source, targets, target, Text.translatable("setmod.command.speed.default", name, duration, blockCount), false);
			}

			successes++;
		}

		return successes > 0 ? 1 : 0;
	}

	/** {@code /setmod speed <targets> <name> <ticks>}: set that recording's animation length. */
	private static int speedSet(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
		ServerCommandSource source = context.getSource();
		Collection<ServerPlayerEntity> targets = EntityArgumentType.getPlayers(context, TARGETS);
		String name = StringArgumentType.getString(context, NAME);

		if (!validateName(source, name)) {
			return 0;
		}

		SetModState state = SetModState.get(source.getServer());
		int ticks = IntegerArgumentType.getInteger(context, TICKS);
		int successes = 0;

		for (ServerPlayerEntity target : targets) {
			Recording recording = state.getRecording(target.getUuid(), name);

			if (recording == null) {
				feedback(source, targets, target, Text.translatable("setmod.command.speed.unknown", name), true);
				continue;
			}

			state.setRecordingDuration(target.getUuid(), name, ticks);
			int blockCount = recording.blocks().size();

			feedback(source, targets, target, Text.translatable("setmod.command.speed.set", name, ticks, blockCount), false);

			if (SetModPlayback.isRunning(target.getUuid(), name)) {
				feedback(source, targets, target, Text.translatable("setmod.command.speed.running", name), false);
			}

			successes++;
		}

		return successes > 0 ? 1 : 0;
	}

	/** {@code /setmod clear <targets>}: drop the unsaved cache of every target. */
	private static int clearCache(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
		ServerCommandSource source = context.getSource();
		Collection<ServerPlayerEntity> targets = EntityArgumentType.getPlayers(context, TARGETS);
		SetModState state = SetModState.get(source.getServer());

		for (ServerPlayerEntity target : targets) {
			int removed = state.clearPending(target.getUuid());
			feedback(source, targets, target, Text.translatable("setmod.command.clear.cached", removed), false);
		}

		return 1;
	}

	/** {@code /setmod clear <targets> <name>}: delete one named recording, stopping its playback. */
	private static int clearRecording(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
		ServerCommandSource source = context.getSource();
		Collection<ServerPlayerEntity> targets = EntityArgumentType.getPlayers(context, TARGETS);
		String name = StringArgumentType.getString(context, NAME);

		if (!validateName(source, name)) {
			return 0;
		}

		SetModState state = SetModState.get(source.getServer());
		int successes = 0;

		for (ServerPlayerEntity target : targets) {
			int removed = state.removeRecording(target.getUuid(), name);

			if (removed < 0) {
				feedback(source, targets, target, Text.translatable("setmod.command.clear.named.unknown", name), true);
				continue;
			}

			boolean cancelled = SetModPlayback.cancel(target.getUuid(), name);
			feedback(source, targets, target, Text.translatable("setmod.command.clear.named", name, removed), false);

			if (cancelled) {
				feedback(source, targets, target, Text.translatable("setmod.command.clear.cancelled", name), false);
			}

			successes++;
		}

		return successes > 0 ? 1 : 0;
	}

	/**
	 * Sends one result message. With several targets the message is prefixed with the target's name,
	 * and when the command does not come from that player (command block, {@code /execute as},
	 * another player) the target gets the message in chat as well.
	 */
	private static void feedback(ServerCommandSource source, Collection<ServerPlayerEntity> targets, ServerPlayerEntity target, Text message, boolean error) {
		Text text = targets.size() > 1
				? Text.translatable("setmod.feedback.prefix", target.getName(), message)
				: message;

		if (error) {
			source.sendError(text);
		} else {
			source.sendFeedback(() -> text, false);
		}

		if (source.getEntity() != target) {
			target.sendMessage(text, false);
		}
	}

	private static boolean validateName(ServerCommandSource source, String name) {
		if (name.isEmpty() || name.length() > MAX_NAME_LENGTH) {
			source.sendError(Text.translatable("setmod.command.name.tooLong", MAX_NAME_LENGTH));
			return false;
		}

		return true;
	}
}
