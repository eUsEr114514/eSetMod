package com.electrondz75.setmod.state;

import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * One block that was right-clicked with the record stick: the dimension it lives in, its position
 * and the exact block state that was recorded.
 */
public record RecordedBlock(Identifier dimension, BlockPos pos, BlockState state) {
	private static final String DIMENSION_KEY = "dimension";
	private static final String X_KEY = "x";
	private static final String Y_KEY = "y";
	private static final String Z_KEY = "z";
	private static final String STATE_KEY = "state";

	public RegistryKey<World> dimensionKey() {
		return RegistryKey.of(RegistryKeys.WORLD, dimension);
	}

	public NbtCompound toNbt() {
		NbtCompound nbt = new NbtCompound();
		nbt.putString(DIMENSION_KEY, dimension.toString());
		nbt.putInt(X_KEY, pos.getX());
		nbt.putInt(Y_KEY, pos.getY());
		nbt.putInt(Z_KEY, pos.getZ());
		nbt.put(STATE_KEY, NbtHelper.fromBlockState(state));
		return nbt;
	}

	public static RecordedBlock fromNbt(NbtCompound nbt) {
		Identifier dimension = Identifier.tryParse(nbt.getString(DIMENSION_KEY));

		if (dimension == null) {
			dimension = World.OVERWORLD.getValue();
		}

		BlockPos pos = new BlockPos(nbt.getInt(X_KEY), nbt.getInt(Y_KEY), nbt.getInt(Z_KEY));
		BlockState state = NbtHelper.toBlockState(Registries.BLOCK.getReadOnlyWrapper(), nbt.getCompound(STATE_KEY));

		return new RecordedBlock(dimension, pos, state);
	}
}
