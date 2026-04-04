/*
 * mtk_thal_capture_server — standalone root binary for MTK screen capture
 * via libthal_capture.so (HIDL client).
 *
 * Uses do_capture_window() which talks to vendor.mediatek.hardware.capture@1.0
 * HIDL service. Captures from the display pipeline (video + OSD blended)
 * with hardware DIP — minimal CPU overhead.
 *
 * Supports two modes with automatic fallback:
 *   1. Android mode: normal capture (CapPoint from render HAL)
 *   2. HDMI mode:    patched capture (forced CapPoint=9, security bypass)
 *
 * Requirements:
 *   - Root access (su)
 *   - /dev/dma_heap/mtk_dip_capture_uncached must be writable (chmod 666)
 *   - LD_LIBRARY_PATH=/vendor/lib:/system/lib
 *   - 32-bit ARM binary (armeabi-v7a) — all vendor libs are 32-bit
 *
 * Protocol (stdout, binary):
 *   Per frame:
 *     4 bytes LE: width
 *     4 bytes LE: height
 *     width * height * 3 bytes: RGB data
 *
 * Usage: mtk_thal_capture_server <width> <height> <fps>
 */

#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <string.h>
#include <unistd.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <time.h>
#include <signal.h>
#include <poll.h>
#include <errno.h>
#include <sys/ioctl.h>
#include <sys/mman.h>

#define LOG_TAG "MtkThalCapSrv"
#ifdef __ANDROID__
#include <android/log.h>
#define LOGI(...) do { __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__); } while(0)
#define LOGE(...) do { __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__); fprintf(stderr, __VA_ARGS__); fprintf(stderr, "\n"); } while(0)
#else
#define LOGI(...) do { fprintf(stderr, __VA_ARGS__); fprintf(stderr, "\n"); } while(0)
#define LOGE(...) do { fprintf(stderr, __VA_ARGS__); fprintf(stderr, "\n"); } while(0)
#endif

/* DMA heap allocation */
struct dma_heap_allocation_data {
    __u64 len;
    __u32 fd;
    __u32 fd_flags;
    __u64 heap_flags;
};
#define DMA_HEAP_IOCTL_ALLOC _IOWR('H', 0x0, struct dma_heap_allocation_data)

#define DMA_HEAP_PATH "/dev/dma_heap/mtk_dip_capture_uncached"

/*
 * do_capture_window signature (reverse-engineered from libthal_capture.so):
 *   int do_capture_window(
 *       int window_id,    // 0
 *       int capture_type, // 0
 *       int crop_x,       // 0
 *       int crop_y,       // 0
 *       int crop_w,       // must equal output_w
 *       int crop_h,       // must equal output_h
 *       int output_w,
 *       int output_h,
 *       int buffer_fd,    // DMA-buf fd from mtk_dip_capture_uncached
 *       int buffer_size   // output_w * output_h * 4 (RGBA)
 *   );
 *   Returns: 0 = success
 */
typedef int (*fn_do_capture_window)(int, int, int, int, int, int, int, int, int, int);

static volatile int g_running = 1;

static void signal_handler(int sig) {
    (void)sig;
    g_running = 0;
}

static int write_all(int fd, const void *buf, size_t len) {
    const uint8_t *p = (const uint8_t *)buf;
    while (len > 0) {
        ssize_t n = write(fd, p, len);
        if (n <= 0) return -1;
        p += n;
        len -= n;
    }
    return 0;
}

static int alloc_dma_buf(int size) {
    int heap_fd = open(DMA_HEAP_PATH, O_RDWR);
    if (heap_fd < 0) {
        LOGE("Cannot open %s: %s", DMA_HEAP_PATH, strerror(errno));
        return -1;
    }

    struct dma_heap_allocation_data data;
    memset(&data, 0, sizeof(data));
    data.len = size;
    data.fd_flags = O_RDWR | O_CLOEXEC;

    if (ioctl(heap_fd, DMA_HEAP_IOCTL_ALLOC, &data) < 0) {
        LOGE("DMA alloc failed (%d bytes): %s", size, strerror(errno));
        close(heap_fd);
        return -1;
    }
    close(heap_fd);
    return data.fd;
}

/*
 * HDMI capture patch state.
 *
 * libthal_capture.so blocks HDMI input capture via a bIsSecurity check
 * (returns ret=3) and the HIDL service rejects HDMI-specific CapPoints.
 * We patch do_capture_window() in-memory: replace the security check with
 * forced CapPoint=9 (STREAM_ALL_VIDEO) + unconditional branch.
 *
 * The patch is applied once at startup but capture mode switches dynamically:
 *   - Android mode: use do_capture_window with window=0 (normal)
 *   - HDMI mode:    use do_capture_window with window=0 (patch handles the rest)
 *
 * Fallback logic: if Android capture fails N times in a row, switch to HDMI
 * mode. Periodically probe Android mode to switch back when available.
 */

#define HDMI_PATCH_SIZE 6

static const uint8_t hdmi_patch_pattern[HDMI_PATCH_SIZE] =
    { 0x9d, 0xf8, 0xb0, 0x00, 0x80, 0xb1 };
static const uint8_t hdmi_patch_bytes[HDMI_PATCH_SIZE] =
    { 0x09, 0x20, 0x24, 0x90, 0x10, 0xe0 };

static uint8_t hdmi_patch_original[HDMI_PATCH_SIZE];
static uint8_t *hdmi_patch_addr = NULL;

/* Find the patch site in do_capture_window, save original bytes */
static void hdmi_patch_init(fn_do_capture_window do_capture) {
    uint8_t *func = (uint8_t *)((uintptr_t)do_capture & ~1u);

    for (int i = 0; i < 2048 - HDMI_PATCH_SIZE; i++) {
        if (memcmp(func + i, hdmi_patch_pattern, HDMI_PATCH_SIZE) == 0) {
            hdmi_patch_addr = func + i;
            memcpy(hdmi_patch_original, hdmi_patch_addr, HDMI_PATCH_SIZE);
            LOGI("HDMI patch site found at offset +%d", i);
            return;
        }
    }
    LOGI("HDMI patch pattern not found (may not be needed on this firmware)");
}

static int hdmi_patch_write(const uint8_t *bytes) {
    if (!hdmi_patch_addr) return -1;
    uintptr_t page = (uintptr_t)hdmi_patch_addr & ~0xFFFu;
    if (mprotect((void *)page, 0x2000, PROT_READ | PROT_WRITE | PROT_EXEC) != 0)
        return -1;
    memcpy(hdmi_patch_addr, bytes, HDMI_PATCH_SIZE);
    mprotect((void *)page, 0x2000, PROT_READ | PROT_EXEC);
    return 0;
}

static int hdmi_patch_apply(void) {
    return hdmi_patch_write(hdmi_patch_bytes);
}

static int hdmi_patch_revert(void) {
    return hdmi_patch_write(hdmi_patch_original);
}

static void enable_dip_debug(void) {
    static const char *paths[] = {
        "/sys/devices/platform/1d497e00.dip0/dip_debug",
        "/sys/devices/platform/1d498400.dip1/dip_debug",
        "/sys/devices/platform/1d498a00.dip2/dip_debug",
        NULL
    };
    for (int i = 0; paths[i]; i++) {
        int fd = open(paths[i], O_WRONLY);
        if (fd >= 0) { write(fd, "1", 1); close(fd); }
    }
}

/* How many consecutive failures before switching to HDMI mode */
#define FALLBACK_THRESHOLD 5
/* How often (in ms) to probe Android mode while in HDMI mode */
#define PROBE_INTERVAL_MS 500

int main(int argc, char *argv[]) {
    if (argc < 4) {
        fprintf(stderr, "Usage: %s <width> <height> <fps>\n", argv[0]);
        return 1;
    }

    int req_width = atoi(argv[1]);
    int req_height = atoi(argv[2]);
    int fps = atoi(argv[3]);

    if (req_width <= 0 || req_height <= 0 || fps <= 0 ||
        req_width > 1920 || req_height > 1920) {
        LOGE("Invalid args: %dx%d @ %d fps (max 1920)", req_width, req_height, fps);
        return 1;
    }

    /*
     * do_capture_window crops a pixel region from the SurfaceFlinger compose
     * buffer (1920x1080 on this TV). Requesting smaller output doesn't
     * downscale — it just crops the top-left corner.
     *
     * Strategy: always capture the full screen at 1920x1080, then downscale
     * to the requested size in software before sending through the pipe.
     * This keeps pipe bandwidth low for LED-grid sizes (e.g., 30x18).
     */
    #define CAP_W 1920
    #define CAP_H 1080

    /*
     * Output size — what we send through the pipe.
     * Enforce minimum 240p (426x240) to keep the aspect ratio and ensure
     * enough pixel data for good LED color sampling. The app's LED mapper
     * handles the final reduction to the LED grid.
     */
    int out_w = req_width;
    int out_h = req_height;
    if (out_w < 426 || out_h < 240) {
        /* Scale up preserving aspect ratio to at least 240p */
        float scale = 1.0f;
        if (out_w > 0 && out_h > 0) {
            float sw = 426.0f / out_w;
            float sh = 240.0f / out_h;
            scale = sw > sh ? sw : sh;
        }
        out_w = (int)(out_w * scale);
        out_h = (int)(out_h * scale);
        if (out_w < 426) out_w = 426;
        if (out_h < 240) out_h = 240;
    }
    /* Ensure even */
    out_w = (out_w + 1) & ~1;
    out_h = (out_h + 1) & ~1;

    long frame_interval_us = 1000000L / fps;

    signal(SIGPIPE, signal_handler);
    signal(SIGTERM, signal_handler);
    signal(SIGINT, signal_handler);

    /* Load libthal_capture.so */
    void *lib = dlopen("libthal_capture.so", RTLD_NOW);
    if (!lib) lib = dlopen("/vendor/lib/libthal_capture.so", RTLD_NOW);
    if (!lib) {
        LOGE("Cannot load libthal_capture.so: %s", dlerror());
        return 2;
    }

    fn_do_capture_window do_capture =
        (fn_do_capture_window)dlsym(lib, "do_capture_window");
    if (!do_capture) {
        LOGE("Symbol do_capture_window not found");
        dlclose(lib);
        return 3;
    }

    /* Prepare HDMI patch (find site + save original bytes, don't apply yet) */
    hdmi_patch_init(do_capture);
    enable_dip_debug();

    /* Allocate DMA buffer for full-screen RGBA capture */
    int rgba_size = CAP_W * CAP_H * 4;
    int buf_fd = alloc_dma_buf(rgba_size);
    if (buf_fd < 0) {
        dlclose(lib);
        return 4;
    }

    void *dma_buf = mmap(NULL, rgba_size, PROT_READ | PROT_WRITE, MAP_SHARED, buf_fd, 0);
    if (dma_buf == MAP_FAILED) {
        LOGE("mmap failed: %s", strerror(errno));
        close(buf_fd);
        dlclose(lib);
        return 5;
    }

    /*
     * Local copy of the captured frame. The DIP writes to the DMA buffer
     * which may be uncached/volatile — copying to a regular heap buffer
     * ensures we read a consistent snapshot.
     */
    uint8_t *rgba_copy = (uint8_t *)malloc(rgba_size);
    if (!rgba_copy) {
        LOGE("malloc rgba_copy failed");
        munmap(dma_buf, rgba_size);
        close(buf_fd);
        dlclose(lib);
        return 6;
    }

    /* Pre-allocate RGB output buffer (at requested size) */
    int rgb_size = out_w * out_h * 3;
    uint8_t *rgb_buf = (uint8_t *)malloc(rgb_size);
    if (!rgb_buf) {
        LOGE("malloc rgb_buf failed");
        free(rgba_copy);
        munmap(dma_buf, rgba_size);
        close(buf_fd);
        dlclose(lib);
        return 6;
    }

    /* Warm-up capture */
    do_capture(0, 0, 0, 0, CAP_W, CAP_H, CAP_W, CAP_H, buf_fd, rgba_size);

    LOGI("Capture started: capture %dx%d, output %dx%d @ %d fps",
         CAP_W, CAP_H, out_w, out_h, fps);

    /*
     * Status header (sent once before frames):
     *   4 bytes LE: magic 0x4D544B53 ("MTKS")
     *   4 bytes LE: flags (bit 0 = HDMI patch available)
     * The app reads this to detect HDMI capture capability.
     */
    {
        uint32_t status_header[2];
        status_header[0] = 0x4D544B53;
        status_header[1] = hdmi_patch_addr ? 1 : 0;
        if (write_all(STDOUT_FILENO, status_header, 8) < 0) {
            LOGE("Failed to write status header");
            free(rgb_buf); free(rgba_copy);
            munmap(dma_buf, rgba_size); close(buf_fd); dlclose(lib);
            return 7;
        }
    }

    struct timespec ts_start, ts_end, ts_last_probe;
    clock_gettime(CLOCK_MONOTONIC, &ts_last_probe);

    int consecutive_errors = 0;
    int hdmi_mode = 0;

    while (g_running) {
        clock_gettime(CLOCK_MONOTONIC, &ts_start);

        /* Check if parent is still alive */
        struct pollfd pfd = { .fd = STDIN_FILENO, .events = POLLIN | POLLHUP };
        if (poll(&pfd, 1, 0) > 0 && (pfd.revents & (POLLHUP | POLLERR))) {
            LOGI("Parent disconnected");
            break;
        }

        /*
         * In HDMI mode, periodically probe Android capture:
         * revert the patch, try one capture, and if it works — stay in
         * Android mode. If it fails — re-apply patch and continue HDMI.
         */
        if (hdmi_mode) {
            long probe_elapsed_ms = (ts_start.tv_sec - ts_last_probe.tv_sec) * 1000L +
                (ts_start.tv_nsec - ts_last_probe.tv_nsec) / 1000000L;
            if (probe_elapsed_ms >= PROBE_INTERVAL_MS) {
                ts_last_probe = ts_start;
                hdmi_patch_revert();
                int probe_ret = do_capture(0, 0, 0, 0, CAP_W, CAP_H,
                                           CAP_W, CAP_H, buf_fd, rgba_size);
                if (probe_ret == 0) {
                    hdmi_mode = 0;
                    consecutive_errors = 0;
                    LOGI("Android capture restored, leaving HDMI mode");
                    /* Use this frame — fall through to processing */
                    goto process_frame;
                }
                /* Probe failed, re-apply patch */
                hdmi_patch_apply();
            }
        }

        int ret = do_capture(0, 0, 0, 0, CAP_W, CAP_H, CAP_W, CAP_H, buf_fd, rgba_size);

        if (ret != 0) {
            consecutive_errors++;

            if (!hdmi_mode && consecutive_errors >= FALLBACK_THRESHOLD) {
                /* Switch to HDMI mode */
                if (hdmi_patch_apply() == 0) {
                    hdmi_mode = 1;
                    clock_gettime(CLOCK_MONOTONIC, &ts_last_probe);
                    LOGI("Switching to HDMI capture mode after %d errors", consecutive_errors);
                    /* Retry immediately with patch applied */
                    continue;
                }
            }

            int backoff_ms = consecutive_errors < 20 ? 100 : 1000;
            usleep(backoff_ms * 1000);
            continue;
        }

        if (consecutive_errors > 0 && !hdmi_mode) {
            LOGI("Capture recovered after %d errors", consecutive_errors);
        }
        consecutive_errors = 0;

process_frame:
        /* Snapshot: copy DMA buffer to local memory immediately */
        memcpy(rgba_copy, dma_buf, rgba_size);

        /* Downscale RGBA 1920x1080 → RGB out_w x out_h using nearest-neighbor */
        const uint8_t *src = rgba_copy;
        for (int y = 0; y < out_h; y++) {
            int sy = y * CAP_H / out_h;
            for (int x = 0; x < out_w; x++) {
                int sx = x * CAP_W / out_w;
                int si = (sy * CAP_W + sx) * 4;
                int di = (y * out_w + x) * 3;
                rgb_buf[di]     = src[si];
                rgb_buf[di + 1] = src[si + 1];
                rgb_buf[di + 2] = src[si + 2];
            }
        }

        /* Write frame: header + RGB data */
        uint32_t header[2] = { (uint32_t)out_w, (uint32_t)out_h };
        if (write_all(STDOUT_FILENO, header, 8) < 0) break;
        if (write_all(STDOUT_FILENO, rgb_buf, rgb_size) < 0) break;

        /* Pace to target FPS */
        clock_gettime(CLOCK_MONOTONIC, &ts_end);
        long elapsed_us = (ts_end.tv_sec - ts_start.tv_sec) * 1000000L +
                          (ts_end.tv_nsec - ts_start.tv_nsec) / 1000L;
        long sleep_us = frame_interval_us - elapsed_us;
        if (sleep_us > 0) usleep(sleep_us);
    }

    free(rgb_buf);
    free(rgba_copy);
    munmap(dma_buf, rgba_size);
    close(buf_fd);
    dlclose(lib);
    LOGI("Exiting");
    return 0;
}
