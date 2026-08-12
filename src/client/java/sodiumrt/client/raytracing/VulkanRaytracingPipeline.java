package sodiumrt.client.raytracing;

import org.lwjgl.PointerBuffer;
import org.lwjgl.vulkan.*;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.*;
import static org.lwjgl.vulkan.KHRAccelerationStructure.*;
import static org.lwjgl.vulkan.KHRBufferDeviceAddress.*;
import static org.lwjgl.vulkan.VK11.*;
import static org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;
import static org.lwjgl.vulkan.VK12.VK_MEMORY_ALLOCATE_DEVICE_ADDRESS_BIT;

public class VulkanRaytracingPipeline {
    private long pipelineLayout = VK_NULL_HANDLE;
    private long pipeline = VK_NULL_HANDLE;
    private long descriptorSetLayout = VK_NULL_HANDLE;
    private long descriptorPool = VK_NULL_HANDLE;
    private long descriptorSet = VK_NULL_HANDLE;

    private long sbtBuffer = VK_NULL_HANDLE;
    private long sbtMemory = VK_NULL_HANDLE;

    private final VkStridedDeviceAddressRegionKHR raygenSbtRegion = VkStridedDeviceAddressRegionKHR.create();
    private final VkStridedDeviceAddressRegionKHR missSbtRegion = VkStridedDeviceAddressRegionKHR.create();
    private final VkStridedDeviceAddressRegionKHR hitSbtRegion = VkStridedDeviceAddressRegionKHR.create();
    private final VkStridedDeviceAddressRegionKHR callableSbtRegion = VkStridedDeviceAddressRegionKHR.create();

    public void initPipeline(VkDevice device, VkPhysicalDevice physicalDevice) {
        long rgenModule = createShaderModule(device, loadShaderBytes("/assets/sodium-vulkan-rt/shaders/rt_pipeline.rgen.spv"));
        long rchitModule = createShaderModule(device, loadShaderBytes("/assets/sodium-vulkan-rt/shaders/rt_pipeline.rchit.spv"));
        long rmissModule = createShaderModule(device, loadShaderBytes("/assets/sodium-vulkan-rt/shaders/rt_pipeline.rmiss.spv"));

        if (rgenModule == 0 || rchitModule == 0 || rmissModule == 0) {
            sodiumrt.SodiumRaytracingAddon.LOGGER.error("[Sodium RT Addon] Shader modules could not be created from resources. Aborting pipeline creation.");
            return;
        }

        initPipeline(physicalDevice, device, rgenModule, rchitModule, rmissModule);
    }

    public static long createShaderModule(VkDevice device, byte[] code) {
        if (code == null || code.length == 0 || device == null) return 0L;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer buffer = stack.malloc(code.length);
            buffer.put(code);
            buffer.flip();

            VkShaderModuleCreateInfo createInfo = VkShaderModuleCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO)
                .pCode(buffer);

            LongBuffer pShaderModule = stack.mallocLong(1);
            if (vkCreateShaderModule(device, createInfo, null, pShaderModule) == VK_SUCCESS) {
                return pShaderModule.get(0);
            }
            return 0L;
        }
    }

    private static byte[] loadShaderBytes(String path) {
        try (java.io.InputStream in = VulkanRaytracingPipeline.class.getResourceAsStream(path)) {
            if (in != null) return in.readAllBytes();
        } catch (Exception ignored) {}
        return new byte[0];
    }

    public void initPipeline(VkPhysicalDevice physicalDevice, VkDevice device, long rgenShaderModule, long rchitShaderModule, long rmissShaderModule) {
        if (device == null || physicalDevice == null || rgenShaderModule == 0 || rchitShaderModule == 0 || rmissShaderModule == 0) {
            sodiumrt.SodiumRaytracingAddon.LOGGER.error("[Sodium RT Addon] Invalid handles passed to initPipeline. Skipping pipeline creation.");
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // 1. Descriptor set layout: Binding 0 (TLAS), Binding 1 (Storage Image), Binding 2 (Camera Uniform)
            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(3, stack);

            bindings.get(0)
                .binding(0)
                .descriptorType(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR)
                .descriptorCount(1)
                .stageFlags(VK_SHADER_STAGE_RAYGEN_BIT_KHR | VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR);

            bindings.get(1)
                .binding(1)
                .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                .descriptorCount(1)
                .stageFlags(VK_SHADER_STAGE_RAYGEN_BIT_KHR);

            bindings.get(2)
                .binding(2)
                .descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                .descriptorCount(1)
                .stageFlags(VK_SHADER_STAGE_RAYGEN_BIT_KHR | VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR);

            VkDescriptorSetLayoutCreateInfo layoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO)
                .pBindings(bindings);

            LongBuffer pSetLayout = stack.mallocLong(1);
            if (vkCreateDescriptorSetLayout(device, layoutInfo, null, pSetLayout) != VK_SUCCESS) {
                throw new RuntimeException("Failed to create descriptor set layout");
            }
            descriptorSetLayout = pSetLayout.get(0);

            VkPipelineLayoutCreateInfo pipelineLayoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
                .pSetLayouts(stack.longs(descriptorSetLayout));

            LongBuffer pPipelineLayout = stack.mallocLong(1);
            if (vkCreatePipelineLayout(device, pipelineLayoutInfo, null, pPipelineLayout) != VK_SUCCESS) {
                throw new RuntimeException("Failed to create pipeline layout");
            }
            pipelineLayout = pPipelineLayout.get(0);

            // 2. Shader stages configuration
            VkPipelineShaderStageCreateInfo.Buffer shaderStages = VkPipelineShaderStageCreateInfo.calloc(3, stack);

            // RayGen
            shaderStages.get(0)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                .stage(VK_SHADER_STAGE_RAYGEN_BIT_KHR)
                .module(rgenShaderModule)
                .pName(stack.UTF8("main"));

            // Miss
            shaderStages.get(1)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                .stage(VK_SHADER_STAGE_MISS_BIT_KHR)
                .module(rmissShaderModule)
                .pName(stack.UTF8("main"));

            // Closest Hit
            shaderStages.get(2)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                .stage(VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR)
                .module(rchitShaderModule)
                .pName(stack.UTF8("main"));

            // 3. Shader groups
            VkRayTracingShaderGroupCreateInfoKHR.Buffer groups = VkRayTracingShaderGroupCreateInfoKHR.calloc(3, stack);

            // RayGen group (Index 0)
            groups.get(0)
                .sType(VK_STRUCTURE_TYPE_RAY_TRACING_SHADER_GROUP_CREATE_INFO_KHR)
                .type(VK_RAY_TRACING_SHADER_GROUP_TYPE_GENERAL_KHR)
                .generalShader(0)
                .closestHitShader(VK_SHADER_UNUSED_KHR)
                .anyHitShader(VK_SHADER_UNUSED_KHR)
                .intersectionShader(VK_SHADER_UNUSED_KHR);

            // Miss group (Index 1)
            groups.get(1)
                .sType(VK_STRUCTURE_TYPE_RAY_TRACING_SHADER_GROUP_CREATE_INFO_KHR)
                .type(VK_RAY_TRACING_SHADER_GROUP_TYPE_GENERAL_KHR)
                .generalShader(1)
                .closestHitShader(VK_SHADER_UNUSED_KHR)
                .anyHitShader(VK_SHADER_UNUSED_KHR)
                .intersectionShader(VK_SHADER_UNUSED_KHR);

            // Closest Hit group (Index 2)
            groups.get(2)
                .sType(VK_STRUCTURE_TYPE_RAY_TRACING_SHADER_GROUP_CREATE_INFO_KHR)
                .type(VK_RAY_TRACING_SHADER_GROUP_TYPE_TRIANGLES_HIT_GROUP_KHR)
                .generalShader(VK_SHADER_UNUSED_KHR)
                .closestHitShader(2)
                .anyHitShader(VK_SHADER_UNUSED_KHR)
                .intersectionShader(VK_SHADER_UNUSED_KHR);

            // 4. Create Ray Tracing Pipeline
            VkRayTracingPipelineCreateInfoKHR.Buffer pipelineInfo = VkRayTracingPipelineCreateInfoKHR.calloc(1, stack)
                .sType(VK_STRUCTURE_TYPE_RAY_TRACING_PIPELINE_CREATE_INFO_KHR)
                .pStages(shaderStages)
                .pGroups(groups)
                .maxPipelineRayRecursionDepth(2)
                .layout(pipelineLayout);

            LongBuffer pPipeline = stack.mallocLong(1);
            if (vkCreateRayTracingPipelinesKHR(device, VK_NULL_HANDLE, VK_NULL_HANDLE, pipelineInfo, null, pPipeline) != VK_SUCCESS) {
                throw new RuntimeException("Failed to create Ray Tracing Pipeline");
            }
            pipeline = pPipeline.get(0);

            // 5. Build Shader Binding Table (SBT)
            buildShaderBindingTable(physicalDevice, device, stack);
        }
    }

    private void buildShaderBindingTable(VkPhysicalDevice physicalDevice, VkDevice device, MemoryStack stack) {
        VkPhysicalDeviceRayTracingPipelinePropertiesKHR rtProps = VkPhysicalDeviceRayTracingPipelinePropertiesKHR.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_TRACING_PIPELINE_PROPERTIES_KHR);

        VkPhysicalDeviceProperties2 props = VkPhysicalDeviceProperties2.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2)
            .pNext(rtProps.address());

        vkGetPhysicalDeviceProperties2(physicalDevice, props);

        int handleSize = rtProps.shaderGroupHandleSize();
        int handleAlignment = rtProps.shaderGroupBaseAlignment();
        int handleSizeAligned = alignUp(handleSize, handleAlignment);

        int groupCount = 3; // RayGen, Miss, Hit
        int sbtSize = groupCount * handleSizeAligned;

        ByteBuffer handles = stack.malloc(groupCount * handleSize);
        if (vkGetRayTracingShaderGroupHandlesKHR(device, pipeline, 0, groupCount, handles) != VK_SUCCESS) {
            throw new RuntimeException("Failed to get Ray Tracing Shader Group Handles");
        }

        // Create SBT Buffer
        VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
            .size(sbtSize)
            .usage(VK_BUFFER_USAGE_SHADER_BINDING_TABLE_BIT_KHR | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT)
            .sharingMode(VK_SHARING_MODE_EXCLUSIVE);

        LongBuffer pBuffer = stack.mallocLong(1);
        if (vkCreateBuffer(device, bufferInfo, null, pBuffer) != VK_SUCCESS) {
            throw new RuntimeException("Failed to create SBT buffer");
        }
        sbtBuffer = pBuffer.get(0);

        VkMemoryRequirements memReqs = VkMemoryRequirements.calloc(stack);
        vkGetBufferMemoryRequirements(device, sbtBuffer, memReqs);

        VkMemoryAllocateInfo allocInfo = VkMemoryAllocateInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
            .allocationSize(memReqs.size())
            .memoryTypeIndex(findMemoryType(physicalDevice, memReqs.memoryTypeBits(), VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT));

        VkMemoryAllocateFlagsInfo flagsInfo = VkMemoryAllocateFlagsInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_FLAGS_INFO)
            .flags(VK_MEMORY_ALLOCATE_DEVICE_ADDRESS_BIT);
        allocInfo.pNext(flagsInfo.address());

        LongBuffer pMemory = stack.mallocLong(1);
        if (vkAllocateMemory(device, allocInfo, null, pMemory) != VK_SUCCESS) {
            throw new RuntimeException("Failed to allocate SBT memory");
        }
        sbtMemory = pMemory.get(0);
        vkBindBufferMemory(device, sbtBuffer, sbtMemory, 0);

        // Map and copy handle data
        PointerBuffer pData = stack.mallocPointer(1);
        vkMapMemory(device, sbtMemory, 0, sbtSize, 0, pData);
        long mappedAddress = pData.get(0);

        for (int i = 0; i < groupCount; i++) {
            long dest = mappedAddress + (long) i * handleSizeAligned;
            long src = org.lwjgl.system.MemoryUtil.memAddress(handles) + (long) i * handleSize;
            org.lwjgl.system.MemoryUtil.memCopy(src, dest, handleSize);
        }
        vkUnmapMemory(device, sbtMemory);

        // Get SBT Device Address
        VkBufferDeviceAddressInfo addressInfo = VkBufferDeviceAddressInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_BUFFER_DEVICE_ADDRESS_INFO_KHR)
            .buffer(sbtBuffer);
        long sbtAddress = vkGetBufferDeviceAddressKHR(device, addressInfo);

        // Set SBT Regions
        raygenSbtRegion.deviceAddress(sbtAddress)
            .stride(handleSizeAligned)
            .size(handleSizeAligned);

        missSbtRegion.deviceAddress(sbtAddress + handleSizeAligned)
            .stride(handleSizeAligned)
            .size(handleSizeAligned);

        hitSbtRegion.deviceAddress(sbtAddress + 2L * handleSizeAligned)
            .stride(handleSizeAligned)
            .size(handleSizeAligned);
    }

    public void updateDescriptors(VkDevice device, long tlasHandle, long storageImageView, long cameraUniformBuffer, long cameraUniformSize) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (descriptorPool == VK_NULL_HANDLE) {
                VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(3, stack);
                poolSizes.get(0).type(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR).descriptorCount(1);
                poolSizes.get(1).type(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1);
                poolSizes.get(2).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1);

                VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO)
                    .pPoolSizes(poolSizes)
                    .maxSets(1);

                LongBuffer pPool = stack.mallocLong(1);
                if (vkCreateDescriptorPool(device, poolInfo, null, pPool) != VK_SUCCESS) {
                    throw new RuntimeException("Failed to create descriptor pool");
                }
                descriptorPool = pPool.get(0);

                VkDescriptorSetAllocateInfo allocInfo = VkDescriptorSetAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
                    .descriptorPool(descriptorPool)
                    .pSetLayouts(stack.longs(descriptorSetLayout));

                LongBuffer pSet = stack.mallocLong(1);
                if (vkAllocateDescriptorSets(device, allocInfo, pSet) != VK_SUCCESS) {
                    throw new RuntimeException("Failed to allocate descriptor set");
                }
                descriptorSet = pSet.get(0);
            }

            // Write 0: TLAS
            VkWriteDescriptorSetAccelerationStructureKHR asWrite = VkWriteDescriptorSetAccelerationStructureKHR.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET_ACCELERATION_STRUCTURE_KHR)
                .pAccelerationStructures(stack.longs(tlasHandle));

            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(3, stack);

            writes.get(0)
                .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                .pNext(asWrite.address())
                .dstSet(descriptorSet)
                .dstBinding(0)
                .descriptorType(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR)
                .descriptorCount(1);

            // Write 1: Storage Image
            VkDescriptorImageInfo.Buffer imageInfo = VkDescriptorImageInfo.calloc(1, stack)
                .imageView(storageImageView)
                .imageLayout(VK_IMAGE_LAYOUT_GENERAL);

            writes.get(1)
                .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                .dstSet(descriptorSet)
                .dstBinding(1)
                .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                .descriptorCount(1)
                .pImageInfo(imageInfo);

            // Write 2: Camera Uniform
            VkDescriptorBufferInfo.Buffer bufferInfo = VkDescriptorBufferInfo.calloc(1, stack)
                .buffer(cameraUniformBuffer)
                .offset(0)
                .range(cameraUniformSize);

            writes.get(2)
                .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                .dstSet(descriptorSet)
                .dstBinding(2)
                .descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                .descriptorCount(1)
                .pBufferInfo(bufferInfo);

            vkUpdateDescriptorSets(device, writes, null);
        }
    }

    public void dispatchRayTrace(VkCommandBuffer commandBuffer, int width, int height) {
        if (pipeline == VK_NULL_HANDLE || descriptorSet == VK_NULL_HANDLE) return;

        vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR, pipeline);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR, pipelineLayout, 0, stack.longs(descriptorSet), null);
            vkCmdTraceRaysKHR(commandBuffer, raygenSbtRegion, missSbtRegion, hitSbtRegion, callableSbtRegion, width, height, 1);
        }
    }

    public static int findMemoryType(VkPhysicalDevice physicalDevice, int typeFilter, int properties) {
        if (physicalDevice == null) return 0;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceMemoryProperties memProperties = VkPhysicalDeviceMemoryProperties.calloc(stack);
            vkGetPhysicalDeviceMemoryProperties(physicalDevice, memProperties);

            for (int i = 0; i < memProperties.memoryTypeCount(); i++) {
                if ((typeFilter & (1 << i)) != 0 && (memProperties.memoryTypes(i).propertyFlags() & properties) == properties) {
                    return i;
                }
            }
            return 0;
        }
    }

    private static int alignUp(int size, int alignment) {
        return (size + alignment - 1) & ~(alignment - 1);
    }

    public void cleanup(VkDevice device) {
        if (descriptorPool != VK_NULL_HANDLE) {
            vkDestroyDescriptorPool(device, descriptorPool, null);
        }
        if (sbtBuffer != VK_NULL_HANDLE) {
            vkDestroyBuffer(device, sbtBuffer, null);
            vkFreeMemory(device, sbtMemory, null);
        }
        if (pipeline != VK_NULL_HANDLE) {
            vkDestroyPipeline(device, pipeline, null);
        }
        if (pipelineLayout != VK_NULL_HANDLE) {
            vkDestroyPipelineLayout(device, pipelineLayout, null);
        }
        if (descriptorSetLayout != VK_NULL_HANDLE) {
            vkDestroyDescriptorSetLayout(device, descriptorSetLayout, null);
        }
    }
}
