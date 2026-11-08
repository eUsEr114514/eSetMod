package com.electrondz75.setmod.command;

import java.util.List;

import com.electrondz75.setmod.playback.SetModPlayback;
import com.electrondz75.setmod.state.RecordedBlock;
import com.electrondz75.setmod.state.SetModState;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

/**
 * {@code /setmod start|speed [ticks]|clear}.
 *
 * <p>Commands work on the recording of the player who runs them, so no item has to be held while
 * typing.</p>
 */
public final class SetModCommand {
	private SetModCommand() {
	}

	public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
		dispatcher.register(CommandManager.literal("setmod")
				.requires(source -> source.hasPermissionLevel(2))
				.executes(context -> {
					context.getSource().sendFeedback(() -> Text.translatable("setmod.command.usage"), false);
					return 1;
				})
				.then(CommandManager.literal("start")
						.executes(SetModCommand::start))
				.then(CommandManager.literal("speed")
						.executes(SetModCommand::speedQuery)
						.then(CommandManager.argument("ticks", IntegerArgumentType.integer(1, SetModState.MAX_DURATION_TICKS))
								.executes(SetModCommand::speedSet)))
				.then(CommandManager.literal("clear")
						.executes(SetModCommand::clear)));
	}

	/** {@code /setmod start}: clear the recorded blocks, then replay them after the delay. */
	private static int start(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
		ServerCommandSource source = context.getSource();
		ServerPlayerEntity player = source.getPlayerOrThrow();
		SetModState state = SetModState.get(source.getServer());
		List<RecordedBlock> records = state.getRecords(player.getUuid());

		if (records.isEmpty()) {
			source.sendError(Text.translatable("setmod.command.start.empty"));
			return 0;
		}

		int duration = state.resolveDuration(player.getUuid(), records.size());
		int cleared = SetModPlayback.clearRecordedBlocks(source.getServer(), records);
		SetModPlayback.start(source.getServer(), player.getUuid(), records, duration);

		source.sendFeedback(() -> Text.translatable("setmod.command.start.started",
				records.size(), cleared, SetModPlayback.START_DELAY_TICKS, duration), false);
		return 1;
	}

	/** {@code /setmod speed}: report the duration that is currently in effect. */
	private static int speedQuery(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
		ServerCommandSource source = context.getSource();
		ServerPlayerEntity player = source.getPlayerOrThrow();
		SetModState state = SetModState.get(source.getServer());
		int blockCount = state.getRecordCount(player.getUuid());

		if (state.hasCustomDuration(player.getUuid())) {
			int duration = state.getCustomDuration(player.getUuid());
			source.sendFeedback(() -> Text.translatable("setmod.command.speed.get", duration, blockCount), false);
		} else {
			int duration = state.resolveDuration(player.getUuid(), blockCount);
			source.sendFeedback(() -> Text.translatable("setmod.command.speed.default", duration, blockCount), false);
		}

		return 1;
	}

	/** {@code /setmod speed <ticks>}: set the total length of the animation. */
	private static int speedSet(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
		ServerCommandSource source = context.getSource();
		ServerPlayerEntity player = source.getPlayerOrThrow();
		SetModState state = SetModState.get(source.getServer());
		int ticks = IntegerArgumentType.getInteger(context, "ticks");

		state.setDuration(player.getUuid(), ticks);
		int blockCount = state.getRecordCount(player.getUuid());

		source.sendFeedback(() -> Text.translatable("setmod.command.speed.set", ticks, blockCount), false);

		if (SetModPlayback.isRunning(player.getUuid())) {
			source.sendFeedback(() -> Text.translatable("setmod.command.speed.running"), false);
		}

		return 1;
	}

	/** {@code /setmod clear}: drop every record and stop a running animation. */
	private static int clear(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
		ServerCommandSource source = context.getSource();
		ServerPlayerEntity player = source.getPlayerOrThrow();
		SetModState state = SetModState.get(source.getServer());

		int removed = state.clearRecords(player.getUuid());
		boolean cancelled = SetModPlayback.cancel(player.getUuid());

		source.sendFeedback(() -> Text.translatable("setmod.command.clear.done", removed), false);

		if (cancelled) {
			source.sendFeedback(() -> Text.translatable("setmod.command.clear.cancelled"), false);
		}

		return 1;
	}
}
