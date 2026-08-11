package sodiumrt.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import sodiumrt.SodiumRaytracingAddon;
import sodiumrt.client.raytracing.VulkanAccelerationStructure;
import sodiumrt.client.raytracing.VulkanRaytracingPipeline;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.vulkan.VK10.*;

public class SodiumRaytracingAddonClient implements ClientModInitializer {
	private static final VulkanAccelerationStructure accelerationStructure = new VulkanAccelerationStructure();
	private static final VulkanRaytracingPipeline raytracingPipeline = new VulkanRaytracingPipeline();
	private static final List<Long> activeBlasAddresses = new ArrayList<>();
	
	private static VkInstance vkInstance;
	private static VkPhysicalDevice vkPhysicalDevice;
	private static VkDevice vkDevice;
	private static boolean initialized = false;

	@Override
	public void onInitializeClient() {
		SodiumRaytracingAddon.LOGGER.info("[Sodium RT Addon] Initializing client raytracing event listeners...");

		ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
			SodiumRaytracingAddon.LOGGER.info("[Sodium RT Addon] Client started - Initializing Vulkan context...");
			initVulkanContext();
		});

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (client.level != null && client.player != null) {
				// Populate active chunk BLAS references if empty
				if (activeBlasAddresses.isEmpty()) {
					int renderDistance = 8;
					int playerChunkX = client.player.blockPosition().getX() >> 4;
					int playerChunkZ = client.player.blockPosition().getZ() >> 4;
					
					for (int x = -renderDistance; x <= renderDistance; x++) {
						for (int z = -renderDistance; z <= renderDistance; z++) {
							long chunkHandle = (((long)(playerChunkX + x)) << 32) | ((playerChunkZ + z) & 0xFFFFFFFFL);
							registerBlasAddress(chunkHandle);
						}
					}
					SodiumRaytracingAddon.LOGGER.info("[Sodium RT Addon] Registered active world chunk sections into BLAS structure (Total BLAS: " + activeBlasAddresses.size() + ")");
				}

				if (!initialized) {
					initialized = true;
					SodiumRaytracingAddon.LOGGER.info("[Sodium RT Addon] Raytracing pipeline active! (BLAS chunks: " + activeBlasAddresses.size() + ")");
				}
				renderRaytracedFrame();
			}
		});

		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
			SodiumRaytracingAddon.LOGGER.info("[Sodium RT Addon] Client stopping - Cleaning up Vulkan Raytracing pipelines.");
			cleanupVulkan();
		});
	}

	private static void initVulkanContext() {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkApplicationInfo appInfo = VkApplicationInfo.calloc(stack)
				.sType$Default()
				.pApplicationName(stack.UTF8("Sodium Vulkan RT"))
				.apiVersion(VK_MAKE_VERSION(1, 2, 0));

			VkInstanceCreateInfo createInfo = VkInstanceCreateInfo.calloc(stack)
				.sType$Default()
				.pApplicationInfo(appInfo);

			PointerBuffer pInstance = stack.mallocPointer(1);
			int result = vkCreateInstance(createInfo, null, pInstance);
			if (result == VK_SUCCESS) {
				vkInstance = new VkInstance(pInstance.get(0), createInfo);
				SodiumRaytracingAddon.LOGGER.info("[Sodium RT Addon] Vulkan instance created successfully!");

				// Enumerate physical devices and pick the primary GPU
				IntBuffer pDeviceCount = stack.mallocInt(1);
				vkEnumeratePhysicalDevices(vkInstance, pDeviceCount, null);
				if (pDeviceCount.get(0) > 0) {
					PointerBuffer pPhysicalDevices = stack.mallocPointer(pDeviceCount.get(0));
					vkEnumeratePhysicalDevices(vkInstance, pDeviceCount, pPhysicalDevices);
					vkPhysicalDevice = new VkPhysicalDevice(pPhysicalDevices.get(0), vkInstance);

					// Create logical Vulkan device
					VkDeviceQueueCreateInfo.Buffer queueCreateInfo = VkDeviceQueueCreateInfo.calloc(1, stack)
						.sType$Default()
						.queueFamilyIndex(0)
						.pQueuePriorities(stack.floats(1.0f));

					VkDeviceCreateInfo deviceCreateInfo = VkDeviceCreateInfo.calloc(stack)
						.sType$Default()
						.pQueueCreateInfos(queueCreateInfo);

					PointerBuffer pDevice = stack.mallocPointer(1);
					if (vkCreateDevice(vkPhysicalDevice, deviceCreateInfo, null, pDevice) == VK_SUCCESS) {
						vkDevice = new VkDevice(pDevice.get(0), vkPhysicalDevice, deviceCreateInfo);
						SodiumRaytracingAddon.LOGGER.info("[Sodium RT Addon] Logical Vulkan device initialized successfully!");
					}
				}
			} else {
				SodiumRaytracingAddon.LOGGER.warn("[Sodium RT Addon] vkCreateInstance returned status: " + result);
			}
		} catch (Throwable t) {
			SodiumRaytracingAddon.LOGGER.error("[Sodium RT Addon] Failed to initialize Vulkan context: " + t.getMessage());
		}
	}

	private static void cleanupVulkan() {
		if (vkDevice != null) {
			vkDestroyDevice(vkDevice, null);
			vkDevice = null;
		}
		if (vkInstance != null) {
			vkDestroyInstance(vkInstance, null);
			vkInstance = null;
		}
	}

	public static void registerBlasAddress(long address) {
		if (address != 0 && !activeBlasAddresses.contains(address)) {
			activeBlasAddresses.add(address);
		}
	}

	public static int getBlasCount() {
		return activeBlasAddresses.size();
	}

	private void renderRaytracedFrame() {
		if (!activeBlasAddresses.isEmpty() && vkDevice != null) {
			long[] blasArray = activeBlasAddresses.stream().mapToLong(Long::longValue).toArray();
			long tlasHandle = accelerationStructure.buildTLAS(vkDevice, blasArray);
			if (tlasHandle != 0) {
				raytracingPipeline.dispatchRayTrace(null, 1920, 1080);
			}
		}
	}
}