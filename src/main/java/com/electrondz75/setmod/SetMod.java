package com.electrondz75.setmod;

import com.electrondz75.setmod.command.SetModCommand;
import com.electrondz75.setmod.item.RecordStickItem;
import com.electrondz75.setmod.playback.SetModPlayback;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.item.v1.FabricItemSettings;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.registry.FuelRegistry;

import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * eSetMod entrypoint.
 *
 * <p>The mod adds a single item, the "Record Stick". Right-clicking a block with it records that
 * block (position + block state + dimension) in the order the blocks were clicked. The records are
 * stored per player in the world's {@link com.electrondz75.setmod.state.SetModState} saved data,
 * and are replayed by the {@code /setmod} command.</p>
 */
public class SetMod implements ModInitializer {
	public static final String MOD_ID = "esetmod";

	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	/** The record stick. Uses the vanilla stick texture, model shape and stack size. */
	public static final Item RECORD_STICK = new RecordStickItem(new FabricItemSettings());

	public static Identifier id(String path) {
		return new Identifier(MOD_ID, path);
	}

	@Override
	public void onInitialize() {
		Registry.register(Registries.ITEM, id("record_stick"), RECORD_STICK);

		// A vanilla stick burns for 100 ticks, so does this one.
		FuelRegistry.INSTANCE.add(RECORD_STICK, 100);

		// Vanilla sticks live in the "Ingredients" tab, mirror that.
		ItemGroupEvents.modifyEntriesEvent(ItemGroups.INGREDIENTS).register(entries -> entries.add(RECORD_STICK));

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> SetModCommand.register(dispatcher));

		ServerTickEvents.END_SERVER_TICK.register(SetModPlayback::tick);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> SetModPlayback.clearAll());

		LOGGER.info("[{}] initialized", MOD_ID);
	}
}
