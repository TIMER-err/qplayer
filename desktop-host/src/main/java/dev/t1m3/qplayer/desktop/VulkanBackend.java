package dev.t1m3.qplayer.desktop;

import io.github.humbleui.skija.BackendRenderTarget;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.ColorType;
import io.github.humbleui.skija.DirectContext;
import io.github.humbleui.skija.Surface;
import io.github.humbleui.skija.SurfaceOrigin;

import dev.t1m3.qplayer.util.Logger;

import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFWVulkan;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.IntBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Vulkan graphics backend. Builds a VkInstance / surface / device / swapchain via
 * LWJGL, hands the handles to Skija's {@code DirectContext.makeVulkan}, and wraps
 * each swapchain image as a Skija {@code Surface} so the same per-frame draw path
 * runs unchanged. Selected with {@code -Dqplayer.gfx=vulkan}.
 *
 * <p>humbleui Skija exposes no way to hand swapchain semaphores to its flush, so
 * this uses a deliberately simple, fully-serialized scheme: acquire with a fence,
 * {@code flush + submit(wait=true)}, then layout-transition + present + restore via
 * one-shot command buffers fenced with {@code vkQueueWaitIdle}. Correct and
 * portable; not the fastest. Each swapchain image is kept in
 * {@code COLOR_ATTACHMENT_OPTIMAL} between frames so Skija's tracked layout always
 * matches reality.
 */
final class VulkanBackend implements GraphicsBackend {

    private final long window;

    private VkInstance instance;
    private long surfaceKHR;
    private VkPhysicalDevice physicalDevice;
    private VkDevice device;
    private VkQueue queue;
    private int queueFamily;
    private DirectContext context;

    private long swapchain;
    private int vkFormat;
    private int colorSpaceKHR;
    private ColorType skiaColorType = ColorType.BGRA_8888;
    private int width, height;

    // Swapchain image usage matching Skija's vulkan example: Skia wraps the image as
    // both a render target and a texture, so the SAMPLED + TRANSFER bits are required
    // for wrapBackendRenderTarget to succeed (COLOR_ATTACHMENT alone is rejected).
    private static final int IMAGE_USAGE =
            VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT
            | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT;

    private long[] images = new long[0];
    private BackendRenderTarget[] targets = new BackendRenderTarget[0];
    private Surface[] surfaces = new Surface[0];

    private long commandPool;
    private long acquireFence;
    private int currentIndex = -1;

    VulkanBackend(long window) {
        this.window = window;
    }

    @Override
    public Kind kind() {
        return Kind.VULKAN;
    }

    @Override
    public void init(int w, int h) {
        this.width = w;
        this.height = h;
        createInstance();
        createSurface();
        pickPhysicalDeviceAndQueue();
        createDevice();
        createSkijaContext();
        createCommandPool();
        createSwapchain();
        Logger.info("Vulkan backend ready: {}x{}, {} swapchain images, format {}",
                w, h, images.length, vkFormat);
    }

    // --- setup ----------------------------------------------------------------

    private void createInstance() {
        try (MemoryStack s = stackPush()) {
            VkApplicationInfo app = VkApplicationInfo.calloc(s).sType$Default()
                    .pApplicationName(s.UTF8("QPlayer"))
                    .apiVersion(VK11.VK_API_VERSION_1_1); // Skia's Vulkan backend requires ≥ 1.1
            PointerBuffer required = GLFWVulkan.glfwGetRequiredInstanceExtensions();
            if (required == null) throw new IllegalStateException("glfwGetRequiredInstanceExtensions null");
            VkInstanceCreateInfo ci = VkInstanceCreateInfo.calloc(s).sType$Default()
                    .pApplicationInfo(app)
                    .ppEnabledExtensionNames(required);
            PointerBuffer pInst = s.mallocPointer(1);
            check(vkCreateInstance(ci, null, pInst), "vkCreateInstance");
            instance = new VkInstance(pInst.get(0), ci);
        }
    }

    private void createSurface() {
        try (MemoryStack s = stackPush()) {
            LongBuffer pSurface = s.mallocLong(1);
            check(GLFWVulkan.glfwCreateWindowSurface(instance, window, null, pSurface),
                    "glfwCreateWindowSurface");
            surfaceKHR = pSurface.get(0);
        }
    }

    private void pickPhysicalDeviceAndQueue() {
        try (MemoryStack s = stackPush()) {
            IntBuffer count = s.mallocInt(1);
            check(vkEnumeratePhysicalDevices(instance, count, null), "enumerate devices");
            if (count.get(0) == 0) throw new IllegalStateException("no Vulkan devices");
            PointerBuffer devs = s.mallocPointer(count.get(0));
            vkEnumeratePhysicalDevices(instance, count, devs);
            for (int i = 0; i < count.get(0); i++) {
                VkPhysicalDevice pd = new VkPhysicalDevice(devs.get(i), instance);
                int qf = findGraphicsPresentQueue(pd, s);
                if (qf >= 0) {
                    physicalDevice = pd;
                    queueFamily = qf;
                    return;
                }
            }
            throw new IllegalStateException("no device with a graphics+present queue");
        }
    }

    private int findGraphicsPresentQueue(VkPhysicalDevice pd, MemoryStack s) {
        IntBuffer count = s.mallocInt(1);
        vkGetPhysicalDeviceQueueFamilyProperties(pd, count, null);
        VkQueueFamilyProperties.Buffer props = VkQueueFamilyProperties.malloc(count.get(0), s);
        vkGetPhysicalDeviceQueueFamilyProperties(pd, count, props);
        IntBuffer supports = s.mallocInt(1);
        for (int i = 0; i < count.get(0); i++) {
            boolean graphics = (props.get(i).queueFlags() & VK_QUEUE_GRAPHICS_BIT) != 0;
            vkGetPhysicalDeviceSurfaceSupportKHR(pd, i, surfaceKHR, supports);
            if (graphics && supports.get(0) == VK_TRUE) return i;
        }
        return -1;
    }

    private void createDevice() {
        try (MemoryStack s = stackPush()) {
            VkDeviceQueueCreateInfo.Buffer queues = VkDeviceQueueCreateInfo.calloc(1, s)
                    .sType$Default()
                    .queueFamilyIndex(queueFamily)
                    .pQueuePriorities(s.floats(1.0f));
            PointerBuffer ext = s.mallocPointer(1);
            ext.put(0, s.UTF8(VK_KHR_SWAPCHAIN_EXTENSION_NAME));
            VkDeviceCreateInfo ci = VkDeviceCreateInfo.calloc(s).sType$Default()
                    .pQueueCreateInfos(queues)
                    .ppEnabledExtensionNames(ext);
            PointerBuffer pDev = s.mallocPointer(1);
            check(vkCreateDevice(physicalDevice, ci, null, pDev), "vkCreateDevice");
            device = new VkDevice(pDev.get(0), physicalDevice, ci);
            PointerBuffer pQueue = s.mallocPointer(1);
            vkGetDeviceQueue(device, queueFamily, 0, pQueue);
            queue = new VkQueue(pQueue.get(0), device);
        }
    }

    private void createSkijaContext() {
        long instanceProcAddr = VK.getFunctionProvider().getFunctionAddress("vkGetInstanceProcAddr");
        long deviceProcAddr;
        try (MemoryStack s = stackPush()) {
            deviceProcAddr = vkGetInstanceProcAddr(instance, s.UTF8("vkGetDeviceProcAddr"));
        }
        context = DirectContext.makeVulkan(
                instance.address(), physicalDevice.address(), device.address(),
                queue.address(), queueFamily, instanceProcAddr, deviceProcAddr,
                VK11.VK_API_VERSION_1_1);
    }

    private void createCommandPool() {
        try (MemoryStack s = stackPush()) {
            VkCommandPoolCreateInfo ci = VkCommandPoolCreateInfo.calloc(s).sType$Default()
                    .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
                    .queueFamilyIndex(queueFamily);
            LongBuffer pPool = s.mallocLong(1);
            check(vkCreateCommandPool(device, ci, null, pPool), "vkCreateCommandPool");
            commandPool = pPool.get(0);
            VkFenceCreateInfo fci = VkFenceCreateInfo.calloc(s).sType$Default();
            LongBuffer pFence = s.mallocLong(1);
            check(vkCreateFence(device, fci, null, pFence), "vkCreateFence");
            acquireFence = pFence.get(0);
        }
    }

    private void createSwapchain() {
        try (MemoryStack s = stackPush()) {
            VkSurfaceCapabilitiesKHR caps = VkSurfaceCapabilitiesKHR.malloc(s);
            vkGetPhysicalDeviceSurfaceCapabilitiesKHR(physicalDevice, surfaceKHR, caps);
            chooseFormat(s);

            int extentW = width, extentH = height;
            if (caps.currentExtent().width() != 0xFFFFFFFF) {
                extentW = caps.currentExtent().width();
                extentH = caps.currentExtent().height();
            }
            width = extentW;
            height = extentH;
            final int ew = extentW, eh = extentH;

            int min = caps.minImageCount() + 1;
            if (caps.maxImageCount() > 0 && min > caps.maxImageCount()) min = caps.maxImageCount();

            VkSwapchainCreateInfoKHR ci = VkSwapchainCreateInfoKHR.calloc(s).sType$Default()
                    .surface(surfaceKHR)
                    .minImageCount(min)
                    .imageFormat(vkFormat)
                    .imageColorSpace(colorSpaceKHR)
                    .imageExtent(e -> e.width(ew).height(eh))
                    .imageArrayLayers(1)
                    .imageUsage(IMAGE_USAGE)
                    .imageSharingMode(VK_SHARING_MODE_EXCLUSIVE)
                    .preTransform(caps.currentTransform())
                    .compositeAlpha(VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR)
                    .presentMode(VK_PRESENT_MODE_FIFO_KHR)
                    .clipped(true)
                    .oldSwapchain(VK_NULL_HANDLE);
            LongBuffer pSwap = s.mallocLong(1);
            check(vkCreateSwapchainKHR(device, ci, null, pSwap), "vkCreateSwapchainKHR");
            swapchain = pSwap.get(0);

            IntBuffer count = s.mallocInt(1);
            vkGetSwapchainImagesKHR(device, swapchain, count, null);
            LongBuffer imgs = s.mallocLong(count.get(0));
            vkGetSwapchainImagesKHR(device, swapchain, count, imgs);

            int n = count.get(0);
            images = new long[n];
            targets = new BackendRenderTarget[n];
            surfaces = new Surface[n];
            for (int i = 0; i < n; i++) {
                images[i] = imgs.get(i);
                // Prime each image to COLOR_ATTACHMENT_OPTIMAL so Skija's tracked
                // layout matches when it first renders into it.
                transitionImageLayout(images[i], VK_IMAGE_LAYOUT_UNDEFINED,
                        VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
                //noinspection resource
                targets[i] = BackendRenderTarget.makeVulkan(width, height, images[i],
                        VK_IMAGE_TILING_OPTIMAL, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                        vkFormat, IMAGE_USAGE, 1, 1);
                surfaces[i] = Surface.wrapBackendRenderTarget(context, targets[i],
                        SurfaceOrigin.TOP_LEFT, skiaColorType, null,
                        new io.github.humbleui.skija.SurfaceProps(io.github.humbleui.skija.PixelGeometry.RGB_H));
            }
        }
    }

    private void chooseFormat(MemoryStack s) {
        IntBuffer count = s.mallocInt(1);
        vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice, surfaceKHR, count, null);
        VkSurfaceFormatKHR.Buffer formats = VkSurfaceFormatKHR.malloc(count.get(0), s);
        vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice, surfaceKHR, count, formats);
        // Prefer BGRA8 UNORM (matches ColorType.BGRA_8888); fall back to first.
        vkFormat = formats.get(0).format();
        colorSpaceKHR = formats.get(0).colorSpace();
        for (int i = 0; i < count.get(0); i++) {
            if (formats.get(i).format() == VK_FORMAT_B8G8R8A8_UNORM) {
                vkFormat = VK_FORMAT_B8G8R8A8_UNORM;
                colorSpaceKHR = formats.get(i).colorSpace();
                skiaColorType = ColorType.BGRA_8888;
                return;
            }
            if (formats.get(i).format() == VK_FORMAT_R8G8B8A8_UNORM) {
                vkFormat = VK_FORMAT_R8G8B8A8_UNORM;
                colorSpaceKHR = formats.get(i).colorSpace();
                skiaColorType = ColorType.RGBA_8888;
            }
        }
    }

    // --- per-frame ------------------------------------------------------------

    @Override
    public Canvas acquireCanvas() {
        try (MemoryStack s = stackPush()) {
            IntBuffer pIndex = s.mallocInt(1);
            vkResetFences(device, acquireFence);
            int r = vkAcquireNextImageKHR(device, swapchain, Long.MAX_VALUE,
                    VK_NULL_HANDLE, acquireFence, pIndex);
            if (r == VK_ERROR_OUT_OF_DATE_KHR) {
                recreateSwapchain();
                return acquireCanvas();
            }
            vkWaitForFences(device, acquireFence, true, Long.MAX_VALUE);
            currentIndex = pIndex.get(0);
        }
        Canvas c = surfaces[currentIndex].getCanvas();
        c.clear(0xFF000000);
        return c;
    }

    @Override
    public DirectContext recordingContext() {
        return context;
    }

    @Override
    public void present() {
        if (currentIndex < 0) return;
        // Skija records + submits the draw; wait so the image is fully rendered (it
        // stays COLOR_ATTACHMENT_OPTIMAL), then transition → present → restore.
        context.flush();
        context.submit(true);
        long img = images[currentIndex];
        transitionImageLayout(img, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                VK_IMAGE_LAYOUT_PRESENT_SRC_KHR);
        try (MemoryStack s = stackPush()) {
            VkPresentInfoKHR pi = VkPresentInfoKHR.calloc(s).sType$Default()
                    .swapchainCount(1)
                    .pSwapchains(s.longs(swapchain))
                    .pImageIndices(s.ints(currentIndex));
            int r = vkQueuePresentKHR(queue, pi);
            vkQueueWaitIdle(queue);
            if (r == VK_ERROR_OUT_OF_DATE_KHR || r == VK_SUBOPTIMAL_KHR) {
                recreateSwapchain();
                return;
            }
        }
        // Restore for the next time this image is acquired.
        transitionImageLayout(img, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
                VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        currentIndex = -1;
    }

    @Override
    public void resize(int w, int h) {
        if (w == width && h == height) return;
        width = w;
        height = h;
        recreateSwapchain();
    }

    private void recreateSwapchain() {
        vkDeviceWaitIdle(device);
        destroySwapchainObjects();
        createSwapchain();
    }

    @Override
    public int width() { return width; }

    @Override
    public int height() { return height; }

    @Override
    public void dispose() {
        if (device != null) vkDeviceWaitIdle(device);
        destroySwapchainObjects();
        if (context != null) { context.close(); context = null; }
        if (acquireFence != 0) { vkDestroyFence(device, acquireFence, null); acquireFence = 0; }
        if (commandPool != 0) { vkDestroyCommandPool(device, commandPool, null); commandPool = 0; }
        if (device != null) { vkDestroyDevice(device, null); device = null; }
        if (surfaceKHR != 0) { vkDestroySurfaceKHR(instance, surfaceKHR, null); surfaceKHR = 0; }
        if (instance != null) { vkDestroyInstance(instance, null); instance = null; }
    }

    private void destroySwapchainObjects() {
        for (Surface su : surfaces) if (su != null) su.close();
        for (BackendRenderTarget t : targets) if (t != null) t.close();
        surfaces = new Surface[0];
        targets = new BackendRenderTarget[0];
        images = new long[0];
        if (swapchain != 0) { vkDestroySwapchainKHR(device, swapchain, null); swapchain = 0; }
    }

    // --- helpers --------------------------------------------------------------

    // One-shot image layout transition fenced with vkQueueWaitIdle (the serialized
    // scheme means this is never on a hot path that needs semaphores).
    private void transitionImageLayout(long image, int oldLayout, int newLayout) {
        try (MemoryStack s = stackPush()) {
            VkCommandBuffer cmd = beginOneShot(s);
            VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, s)
                    .sType$Default()
                    .oldLayout(oldLayout)
                    .newLayout(newLayout)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .image(image)
                    .srcAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_TRANSFER_READ_BIT)
                    .dstAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_TRANSFER_READ_BIT);
            barrier.subresourceRange()
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            vkCmdPipelineBarrier(cmd,
                    VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                    0, null, null, barrier);
            endOneShot(cmd);
        }
    }

    // Allocate + begin a one-time-submit primary command buffer.
    private VkCommandBuffer beginOneShot(MemoryStack s) {
        VkCommandBufferAllocateInfo ai = VkCommandBufferAllocateInfo.calloc(s).sType$Default()
                .commandPool(commandPool)
                .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                .commandBufferCount(1);
        PointerBuffer pCmd = s.mallocPointer(1);
        vkAllocateCommandBuffers(device, ai, pCmd);
        VkCommandBuffer cmd = new VkCommandBuffer(pCmd.get(0), device);
        VkCommandBufferBeginInfo bi = VkCommandBufferBeginInfo.calloc(s).sType$Default()
                .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
        vkBeginCommandBuffer(cmd, bi);
        return cmd;
    }

    // End + submit + wait + free a one-shot command buffer.
    private void endOneShot(VkCommandBuffer cmd) {
        try (MemoryStack s = stackPush()) {
            vkEndCommandBuffer(cmd);
            VkSubmitInfo si = VkSubmitInfo.calloc(s).sType$Default()
                    .pCommandBuffers(s.pointers(cmd));
            vkQueueSubmit(queue, si, VK_NULL_HANDLE);
            vkQueueWaitIdle(queue);
            vkFreeCommandBuffers(device, commandPool, cmd);
        }
    }

    private static void check(int result, String what) {
        if (result != VK_SUCCESS) throw new IllegalStateException(what + " failed: " + result);
    }
}
