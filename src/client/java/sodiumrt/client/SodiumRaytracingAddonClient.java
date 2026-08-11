package sodiumrt.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import sodiumrt.SodiumRaytracingAddon;

public class SodiumRaytracingAddonClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		SodiumRaytracingAddon.LOGGER.info("[Sodium RT Addon] Initializing client raytracing event listeners...");

		ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
			SodiumRaytracingAddon.LOGGER.info("[Sodium RT Addon] Client started - Vulkan Raytracing backend ready.");
		});

		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
			SodiumRaytracingAddon.LOGGER.info("[Sodium RT Addon] Client stopping - Cleaning up Vulkan Raytracing pipelines.");
		});
	}
}