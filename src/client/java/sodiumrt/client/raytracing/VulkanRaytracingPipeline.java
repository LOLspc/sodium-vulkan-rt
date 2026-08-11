package sodiumrt.client.raytracing;

import org.lwjgl.vulkan.*;
import org.lwjgl.system.MemoryStack;

import java.nio.LongBuffer;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.*;

public class VulkanRaytracingPipeline {
    private long pipelineLayout = VK_NULL_HANDLE;
    private long pipeline = VK_NULL_HANDLE;
    private long descriptorSetLayout = VK_NULL_HANDLE;

    public void initPipeline(VkDevice device, long rgenShaderModule, long rchitShaderModule, long rmissShaderModule) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Descriptor set layout: Binding 0 (TLAS), Binding 1 (Storage Image), Binding 2 (Camera Uniform)
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
        }
    }

    public void dispatchRayTrace(VkCommandBuffer commandBuffer, int width, int height) {
        if (pipeline == VK_NULL_HANDLE) return;
        
        // Dispatch ray generation shader across screen dimensions
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkStridedDeviceAddressRegionKHR raygenSbt = VkStridedDeviceAddressRegionKHR.calloc(stack);
            VkStridedDeviceAddressRegionKHR missSbt = VkStridedDeviceAddressRegionKHR.calloc(stack);
            VkStridedDeviceAddressRegionKHR hitSbt = VkStridedDeviceAddressRegionKHR.calloc(stack);
            VkStridedDeviceAddressRegionKHR callableSbt = VkStridedDeviceAddressRegionKHR.calloc(stack);

            vkCmdTraceRaysKHR(commandBuffer, raygenSbt, missSbt, hitSbt, callableSbt, width, height, 1);
        }
    }

    public void cleanup(VkDevice device) {
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
