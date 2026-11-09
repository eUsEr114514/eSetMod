package com.electrondz75.setmod.item;

import java.util.List;

import com.electrondz75.setmod.state.RecordedBlock;
import com.electrondz75.setmod.state.SetModState;

import net.minecraft.block.BlockState;
import net.minecraft.client.item.TooltipContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * The record stick: a stick that records every block you right-click with it.
 *
 * <p>The recorded block is the block that was clicked (not the neighbour that would receive a
 * placement), together with its full block state, so stairs, slabs, rotated logs and so on are
 * replayed exactly as they were.</p>
 */
public class RecordStickItem extends Item {
	public RecordStickItem(Settings settings) {
		super(settings);
	}

	@Override
	public ActionResult useOnBlock(ItemUsageContext context) {
		World world = context.getWorld();
		PlayerEntity player = context.getPlayer();

		if (player == null) {
			return ActionResult.PASS;
		}

		if (world.isClient()) {
			return ActionResult.SUCCESS;
		}

		if (!(world instanceof ServerWorld serverWorld) || !(player instanceof ServerPlayerEntity serverPlayer)) {
			return ActionResult.PASS;
		}

		BlockPos pos = context.getBlockPos().toImmutable();
		BlockState state = world.getBlockState(pos);
		RecordedBlock recorded = new RecordedBlock(world.getRegistryKey().getValue(), pos, state);

		SetModState modState = SetModState.get(serverWorld.getServer());
		SetModState.RecordResult result = modState.addRecord(serverPlayer.getUuid(), recorded);
		int total = modState.getPendingCount(serverPlayer.getUuid());

		switch (result) {
			case ADDED -> serverPlayer.sendMessage(Text.translatable("setmod.message.recorded", total), true);
			case UPDATED -> serverPlayer.sendMessage(Text.translatable("setmod.message.updated", total), true);
			case LIMIT -> serverPlayer.sendMessage(Text.translatable("setmod.message.limit", SetModState.MAX_RECORDS), true);
		}

		return ActionResult.SUCCESS;
	}

	@Override
	public void appendTooltip(ItemStack stack, World world, List<Text> tooltip, TooltipContext context) {
		tooltip.add(Text.translatable("item.esetmod.record_stick.tooltip.1").formatted(Formatting.GRAY));
		tooltip.add(Text.translatable("item.esetmod.record_stick.tooltip.2").formatted(Formatting.GRAY));
	}
}
