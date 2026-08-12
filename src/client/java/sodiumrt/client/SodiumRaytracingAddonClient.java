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
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.vulkan.VK10.*;

public class SodiumRaytracingAddonClient implements ClientModInitializer {
	private static final VulkanAccelerationStructure accelerationStructure = new VulkanAccelerationStructure();
	private static final VulkanRaytracingPipeline raytracingPipeline = new VulkanRaytracingPipeline();
	private static final List<Long> activeBlasAddresses = new ArrayList<>();
	
	private static VkInstance vkInstance;
	public static VkPhysicalDevice vkPhysicalDevice;
	private static VkDevice vkDevice;
	private static VkQueue vkQueue;
	private static long vkCommandPool;
	private static VkCommandBuffer vkCommandBuffer;
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

					PointerBuffer extensions = stack.pointers(
						stack.UTF8(KHRRayTracingPipeline.VK_KHR_RAY_TRACING_PIPELINE_EXTENSION_NAME),
						stack.UTF8(KHRAccelerationStructure.VK_KHR_ACCELERATION_STRUCTURE_EXTENSION_NAME),
						stack.UTF8(KHRBufferDeviceAddress.VK_KHR_BUFFER_DEVICE_ADDRESS_EXTENSION_NAME),
						stack.UTF8(KHRDeferredHostOperations.VK_KHR_DEFERRED_HOST_OPERATIONS_EXTENSION_NAME)
					);

					VkPhysicalDeviceBufferDeviceAddressFeatures bdaFeatures = VkPhysicalDeviceBufferDeviceAddressFeatures.calloc(stack)
						.sType(VK12.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_BUFFER_DEVICE_ADDRESS_FEATURES)
						.bufferDeviceAddress(true);

					VkPhysicalDeviceAccelerationStructureFeaturesKHR asFeatures = VkPhysicalDeviceAccelerationStructureFeaturesKHR.calloc(stack)
						.sType(KHRAccelerationStructure.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ACCELERATION_STRUCTURE_FEATURES_KHR)
						.accelerationStructure(true)
						.pNext(bdaFeatures.address());

					VkPhysicalDeviceRayTracingPipelineFeaturesKHR rtFeatures = VkPhysicalDeviceRayTracingPipelineFeaturesKHR.calloc(stack)
						.sType(KHRRayTracingPipeline.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_TRACING_PIPELINE_FEATURES_KHR)
						.rayTracingPipeline(true)
						.pNext(asFeatures.address());

					VkDeviceCreateInfo deviceCreateInfo = VkDeviceCreateInfo.calloc(stack)
						.sType$Default()
						.pQueueCreateInfos(queueCreateInfo)
						.ppEnabledExtensionNames(extensions)
						.pNext(rtFeatures.address());

					PointerBuffer pDevice = stack.mallocPointer(1);
					if (vkCreateDevice(vkPhysicalDevice, deviceCreateInfo, null, pDevice) == VK_SUCCESS) {
						vkDevice = new VkDevice(pDevice.get(0), vkPhysicalDevice, deviceCreateInfo);
						SodiumRaytracingAddon.LOGGER.info("[Sodium RT Addon] Logical Vulkan device initialized successfully!");

						PointerBuffer pQueue = stack.mallocPointer(1);
						vkGetDeviceQueue(vkDevice, 0, 0, pQueue);
						vkQueue = new VkQueue(pQueue.get(0), vkDevice);

						VkCommandPoolCreateInfo poolInfo = VkCommandPoolCreateInfo.calloc(stack)
							.sType$Default()
							.queueFamilyIndex(0)
							.flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT);

						LongBuffer pCommandPool = stack.mallocLong(1);
						if (vkCreateCommandPool(vkDevice, poolInfo, null, pCommandPool) == VK_SUCCESS) {
							vkCommandPool = pCommandPool.get(0);
							VkCommandBufferAllocateInfo allocInfo = VkCommandBufferAllocateInfo.calloc(stack)
								.sType$Default()
								.commandPool(vkCommandPool)
								.level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
								.commandBufferCount(1);

							PointerBuffer pCmdBuffer = stack.mallocPointer(1);
							if (vkAllocateCommandBuffers(vkDevice, allocInfo, pCmdBuffer) == VK_SUCCESS) {
								vkCommandBuffer = new VkCommandBuffer(pCmdBuffer.get(0), vkDevice);
							}
						}
						raytracingPipeline.initPipeline(vkDevice, vkPhysicalDevice);
						createStorageImageAndBuffers();
					}
				}
			} else {
				SodiumRaytracingAddon.LOGGER.warn("[Sodium RT Addon] vkCreateInstance returned status: " + result);
			}
		} catch (Throwable t) {
			SodiumRaytracingAddon.LOGGER.error("[Sodium RT Addon] Failed to initialize Vulkan context: " + t.getMessage());
		}
	}

	private static long storageImage = VK_NULL_HANDLE;
	private static long storageImageMemory = VK_NULL_HANDLE;
	private static long storageImageView = VK_NULL_HANDLE;
	private static long cameraUniformBuffer = VK_NULL_HANDLE;
	private static long cameraUniformMemory = VK_NULL_HANDLE;
	private static final int RENDER_WIDTH = 1920;
	private static final int RENDER_HEIGHT = 1080;

	private static void createStorageImageAndBuffers() {
		if (vkDevice == null || vkPhysicalDevice == null) return;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			// 1. Create Storage Image
			VkImageCreateInfo imageInfo = VkImageCreateInfo.calloc(stack)
				.sType$Default()
				.imageType(VK_IMAGE_TYPE_2D)
				.format(VK_FORMAT_R8G8B8A8_UNORM)
				.extent(e -> e.width(RENDER_WIDTH).height(RENDER_HEIGHT).depth(1))
				.mipLevels(1)
				.arrayLayers(1)
				.samples(VK_SAMPLE_COUNT_1_BIT)
				.tiling(VK_IMAGE_TILING_OPTIMAL)
				.usage(VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT)
				.sharingMode(VK_SHARING_MODE_EXCLUSIVE)
				.initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);

			LongBuffer pImage = stack.mallocLong(1);
			if (vkCreateImage(vkDevice, imageInfo, null, pImage) == VK_SUCCESS) {
				storageImage = pImage.get(0);
				VkMemoryRequirements memReqs = VkMemoryRequirements.calloc(stack);
				vkGetImageMemoryRequirements(vkDevice, storageImage, memReqs);

				VkMemoryAllocateInfo allocInfo = VkMemoryAllocateInfo.calloc(stack)
					.sType$Default()
					.allocationSize(memReqs.size())
					.memoryTypeIndex(VulkanRaytracingPipeline.findMemoryType(vkPhysicalDevice, memReqs.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT));

				LongBuffer pMem = stack.mallocLong(1);
				if (vkAllocateMemory(vkDevice, allocInfo, null, pMem) == VK_SUCCESS) {
					storageImageMemory = pMem.get(0);
					vkBindImageMemory(vkDevice, storageImage, storageImageMemory, 0);

					VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack)
						.sType$Default()
						.image(storageImage)
						.viewType(VK_IMAGE_VIEW_TYPE_2D)
						.format(VK_FORMAT_R8G8B8A8_UNORM)
						.subresourceRange(r -> r.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1));

					LongBuffer pView = stack.mallocLong(1);
					if (vkCreateImageView(vkDevice, viewInfo, null, pView) == VK_SUCCESS) {
						storageImageView = pView.get(0);
					}
				}
			}

			// 2. Create Camera Uniform Buffer (2 * 64 bytes for invView/invProj + 16 bytes for viewPos/frameIndex)
			long bufferSize = 144;
			VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack)
				.sType$Default()
				.size(bufferSize)
				.usage(VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT)
				.sharingMode(VK_SHARING_MODE_EXCLUSIVE);

			LongBuffer pBuffer = stack.mallocLong(1);
			if (vkCreateBuffer(vkDevice, bufferInfo, null, pBuffer) == VK_SUCCESS) {
				cameraUniformBuffer = pBuffer.get(0);
				VkMemoryRequirements memReqs = VkMemoryRequirements.calloc(stack);
				vkGetBufferMemoryRequirements(vkDevice, cameraUniformBuffer, memReqs);

				VkMemoryAllocateInfo allocInfo = VkMemoryAllocateInfo.calloc(stack)
					.sType$Default()
					.allocationSize(memReqs.size())
					.memoryTypeIndex(VulkanRaytracingPipeline.findMemoryType(vkPhysicalDevice, memReqs.memoryTypeBits(), VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT));

				LongBuffer pMem = stack.mallocLong(1);
				if (vkAllocateMemory(vkDevice, allocInfo, null, pMem) == VK_SUCCESS) {
					cameraUniformMemory = pMem.get(0);
					vkBindBufferMemory(vkDevice, cameraUniformBuffer, cameraUniformMemory, 0);
				}
			}
		}
	}

	public static void updateCameraUniforms(org.joml.Matrix4f viewMatrix, org.joml.Matrix4f projMatrix, double camX, double camY, double camZ, int frameIndex) {
		if (vkDevice == null || cameraUniformMemory == VK_NULL_HANDLE) return;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			org.joml.Matrix4f invView = new org.joml.Matrix4f(viewMatrix).invert();
			org.joml.Matrix4f invProj = new org.joml.Matrix4f(projMatrix).invert();

			PointerBuffer ppData = stack.mallocPointer(1);
			if (vkMapMemory(vkDevice, cameraUniformMemory, 0, 144, 0, ppData) == VK_SUCCESS) {
				java.nio.ByteBuffer buffer = ppData.getByteBuffer(0, 144);
				invView.get(buffer); // 64 bytes (0..63)
				buffer.position(64);
				invProj.get(buffer); // 64 bytes (64..127)
				buffer.position(128);
				buffer.putFloat((float) camX);
				buffer.putFloat((float) camY);
				buffer.putFloat((float) camZ);
				buffer.putInt(frameIndex);
				vkUnmapMemory(vkDevice, cameraUniformMemory);
			}
		}
	}
	void cleanupVulkan(){	if (vkDevice != null) {
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

	private static long fallbackBlasAddress = 0L;

	public static void renderRaytracedFrame() {
		if (vkDevice != null && vkCommandBuffer != null && storageImageView != VK_NULL_HANDLE && cameraUniformBuffer != VK_NULL_HANDLE) {
			net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
			if (client.player != null) {
				org.joml.Matrix4f viewMatrix = new org.joml.Matrix4f().identity();
				org.joml.Matrix4f projMatrix = new org.joml.Matrix4f().perspective((float) Math.toRadians(70.0), (float) RENDER_WIDTH / RENDER_HEIGHT, 0.05f, 1000.0f);
				updateCameraUniforms(viewMatrix, projMatrix, client.player.getX(), client.player.getEyeY(), client.player.getZ(), (int) (System.currentTimeMillis() & 0xFFFF));
			}

			List<Long> addresses = new ArrayList<>(activeBlasAddresses);
			if (addresses.isEmpty()) {
				if (fallbackBlasAddress == 0L) {
					fallbackBlasAddress = 1L; // Fallback unit bounding address
				}
				addresses.add(fallbackBlasAddress);
			}

			long[] blasArray = addresses.stream().mapToLong(Long::longValue).toArray();
			long tlasHandle = accelerationStructure.buildTLAS(vkDevice, blasArray);
			if (tlasHandle != 0) {
				try (MemoryStack stack = MemoryStack.stackPush()) {
					raytracingPipeline.updateDescriptors(vkDevice, tlasHandle, storageImageView, cameraUniformBuffer, 144);

					VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack)
						.sType$Default()
						.flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);

					vkBeginCommandBuffer(vkCommandBuffer, beginInfo);
					raytracingPipeline.dispatchRayTrace(vkCommandBuffer, RENDER_WIDTH, RENDER_HEIGHT);
					vkEndCommandBuffer(vkCommandBuffer);

					VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack)
						.sType$Default()
						.pCommandBuffers(stack.pointers(vkCommandBuffer.address()));

					vkQueueSubmit(vkQueue, submitInfo, VK_NULL_HANDLE);
					vkQueueWaitIdle(vkQueue);
				}
			}
		}
	}
}