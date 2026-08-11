package sodiumrt.client;

import net.fabricmc.fabric.api.datagen.v1.DataGeneratorEntrypoint;
import net.fabricmc.fabric.api.datagen.v1.FabricDataGenerator;
import sodiumrt.SodiumRaytracingAddon;

public class SodiumRaytracingAddonDataGenerator implements DataGeneratorEntrypoint {
	@Override
	public void onInitializeDataGenerator(FabricDataGenerator fabricDataGenerator) {
		SodiumRaytracingAddon.LOGGER.info("[Sodium RT Addon] Initializing Data Generator pack...");
		FabricDataGenerator.Pack pack = fabricDataGenerator.createPack();
		// Register custom model/shader/texture data providers here
	}
}
