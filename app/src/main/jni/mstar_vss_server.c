#include <dlfcn.h>
#include <errno.h>
#include <poll.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <time.h>
#include <unistd.h>

typedef uint8_t u8;
typedef uint32_t u32;
typedef int (*vss_init_fn)(void);
typedef int (*vss_capture_fn)(u32, void *, void *);
typedef int (*vss_disconnect_fn)(u32);

static volatile sig_atomic_t stop_requested = 0;
static void on_stop_signal(int signal_number) {
    (void)signal_number;
    stop_requested = 1;
}

static int should_stop(void) {
    if (stop_requested) return 1;
    struct pollfd input = { .fd = STDIN_FILENO, .events = POLLIN };
    if (poll(&input, 1, 0) > 0 && (input.revents & POLLIN)) {
        u8 command;
        if (read(STDIN_FILENO, &command, 1) > 0) return 1;
    }
    return stop_requested;
}

static int write_all(int fd, const void *data, size_t length) {
    const u8 *bytes = data;
    while (length > 0) {
        ssize_t written = write(fd, bytes, length);
        if (written < 0 && errno == EINTR) continue;
        if (written <= 0) return 0;
        bytes += written;
        length -= (size_t)written;
    }
    return 1;
}

static void put32(u8 *data, u32 value) {
    for (int i = 0; i < 4; i++) data[i] = (u8)(value >> (i * 8));
}

/* The vendor ABI uses a 64-bit pointer slot even on ARM32. */
static void put_pointer(u8 *data, void *pointer) {
    put32(data, (u32)(uintptr_t)pointer);
    put32(data + 4, 0);
}

static int parse_u32(const char *text, u32 *value) {
    if (!text || !*text) return 0;
    u32 result = 0;
    while (*text) {
        if (*text < '0' || *text > '9') return 0;
        u32 digit = (u32)(*text++ - '0');
        if (result > (UINT32_MAX - digit) / 10) return 0;
        result = result * 10 + digit;
    }
    *value = result;
    return 1;
}

static void sleep_ms(unsigned milliseconds) {
    struct timespec remaining = {
        .tv_sec = milliseconds / 1000,
        .tv_nsec = (long)(milliseconds % 1000) * 1000000L
    };
    while (nanosleep(&remaining, &remaining) < 0 && errno == EINTR && !stop_requested) {
    }
}

static int64_t monotonic_ms(void) {
    struct timespec now;
    if (clock_gettime(CLOCK_MONOTONIC, &now) != 0) return 0;
    return (int64_t)now.tv_sec * 1000 + now.tv_nsec / 1000000;
}

enum {
    PACKET_SIZE = 72,
    RESULT_SIZE = 64,
    GUARD_SIZE = 256,
    SOURCE_X = 0,
    SOURCE_Y = 4,
    SOURCE_WIDTH = 8,
    SOURCE_HEIGHT = 12,
    PIXEL_BUFFER_SIZE = 0x10,
    OUTPUT_WIDTH = 0x14,
    CAPTURE_FLAGS = 0x20,
    PIXEL_BUFFER_ADDRESS = 0x28,
    OUTPUT_HEIGHT = 0x44
};

static int finish_vss(vss_disconnect_fn disconnect, int code) {
    int result = disconnect(0);
    if (result != 0) fprintf(stderr, "VSS disconnect ret=%d\n", result);
    return code;
}

int main(int argc, char **argv) {
    u32 x, y, source_width, source_height, width, height, fps;
    if (argc != 8 ||
        !parse_u32(argv[1], &x) || !parse_u32(argv[2], &y) ||
        !parse_u32(argv[3], &source_width) || !parse_u32(argv[4], &source_height) ||
        !parse_u32(argv[5], &width) || !parse_u32(argv[6], &height) ||
        !parse_u32(argv[7], &fps)) {
        fprintf(stderr, "Usage: mstar_vss_server X Y SRC_W SRC_H OUT_W OUT_H FPS\n");
        return 2;
    }
    uint64_t aspect_error = (uint64_t)source_width * height > (uint64_t)source_height * width
        ? (uint64_t)source_width * height - (uint64_t)source_height * width
        : (uint64_t)source_height * width - (uint64_t)source_width * height;
    if (!source_width || !source_height || !width || !height || !fps ||
        (uint64_t)x + source_width > UINT32_MAX ||
        (uint64_t)y + source_height > UINT32_MAX ||
        aspect_error > source_width ||
        (uint64_t)width * height > (UINT32_MAX - GUARD_SIZE) / 4) {
        fprintf(stderr, "Invalid capture geometry or fps\n");
        return 2;
    }
    fprintf(stderr, "MSTAR_PID=%ld\n", (long)getpid());
    size_t pixel_length = (size_t)width * height * 4;
    size_t rgb_length = (size_t)width * height * 3;
    u8 *pixels = mmap(NULL, pixel_length + GUARD_SIZE, PROT_READ | PROT_WRITE,
                      MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    u8 *rgb = mmap(NULL, rgb_length, PROT_READ | PROT_WRITE,
                   MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (pixels == MAP_FAILED || rgb == MAP_FAILED) {
        fprintf(stderr, "mmap failed\n");
        return 3;
    }
    void *library = dlopen("/vendor/lib/libmtal.so", RTLD_NOW);
    if (!library) {
        fprintf(stderr, "MTAL dlopen failed: %s\n", dlerror());
        return 4;
    }
    vss_init_fn mtal_init = (vss_init_fn)dlsym(library, "MTAL_Init");
    vss_init_fn vss_init = (vss_init_fn)dlsym(library, "MTVSS_Init");
    vss_capture_fn capture = (vss_capture_fn)dlsym(library, "MTVSS_CaptureOne");
    vss_disconnect_fn disconnect = (vss_disconnect_fn)dlsym(library, "MTVSS_DisconnectEx");
    if (!mtal_init || !vss_init || !capture || !disconnect) {
        fprintf(stderr, "Missing MTAL/VSS symbol\n");
        return 5;
    }
    int status = mtal_init();
    if (status != 0) {
        fprintf(stderr, "MTAL_Init error=%d\n", status);
        return 6;
    }
    status = vss_init();
    if (status != 0) {
        fprintf(stderr, "MTVSS_Init error=%d\n", status);
        return 7;
    }
    signal(SIGPIPE, SIG_IGN);
    signal(SIGTERM, on_stop_signal);
    fprintf(stderr, "MStar VSS stream started\n");

    u8 packet[PACKET_SIZE] = {0};
    u8 result[RESULT_SIZE];
    u8 header[8];
    put32(packet + SOURCE_X, x);
    put32(packet + SOURCE_Y, y);
    put32(packet + SOURCE_WIDTH, source_width);
    put32(packet + SOURCE_HEIGHT, source_height);
    put32(packet + PIXEL_BUFFER_SIZE, (u32)pixel_length);
    put32(packet + OUTPUT_WIDTH, width);
    put32(packet + CAPTURE_FLAGS, 0);
    put_pointer(packet + PIXEL_BUFFER_ADDRESS, pixels);
    put32(packet + OUTPUT_HEIGHT, height);
    put32(header, width);
    put32(header + 4, height);

    uint64_t paced_frames = 0;
    int64_t origin = monotonic_ms();
    unsigned failed_frames = 0;
    while (!should_stop()) {
        memset(pixels, 0xa5, pixel_length + GUARD_SIZE);
        memset(result, 0, sizeof(result));
        int capture_status = capture(0, packet, result);
        int guard_corrupted = 0;
        for (size_t i = pixel_length; i < pixel_length + GUARD_SIZE; i++) {
            if (pixels[i] != 0xa5) {
                guard_corrupted = 1;
                break;
            }
        }
        if (guard_corrupted) {
            fprintf(stderr, "Buffer overrun detected; fatal\n");
            return finish_vss(disconnect, 9);
        }
        int changed = 0;
        if (capture_status == 0) {
            for (size_t i = 0; i < pixel_length; i++) {
                if (pixels[i] != 0xa5) {
                    changed = 1;
                    break;
                }
            }
        }
        if (capture_status != 0 || !changed) {
            failed_frames++;
            if (failed_frames == 1 || failed_frames % 20 == 0) {
                fprintf(stderr, "VSS capture unavailable, ret=%d, failures=%u\n",
                        capture_status, failed_frames);
            }
            sleep_ms(500);
            continue;
        }
        if (failed_frames > 0) {
            /* После паузы без кадров темп считается заново, иначе кадры
               шли бы без задержки, пока не догонят прежний график. */
            origin = monotonic_ms();
            paced_frames = 0;
            failed_frames = 0;
        }
        for (size_t i = 0, j = 0; i < pixel_length; i += 4) {
            rgb[j++] = pixels[i + 2];
            rgb[j++] = pixels[i + 1];
            rgb[j++] = pixels[i];
        }
        if (!write_all(STDOUT_FILENO, header, sizeof(header)) ||
            !write_all(STDOUT_FILENO, rgb, rgb_length)) {
            return finish_vss(disconnect, 0);
        }
        paced_frames++;
        if (paced_frames >= 100000) {
            origin = monotonic_ms();
            paced_frames = 0;
        }
        int64_t target = (int64_t)paced_frames * 1000 / fps;
        int64_t wait = target - (monotonic_ms() - origin);
        if (wait > 0) sleep_ms((unsigned)wait);
    }
    return finish_vss(disconnect, 0);
}
