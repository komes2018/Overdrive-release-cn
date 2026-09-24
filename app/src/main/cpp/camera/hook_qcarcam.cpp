#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif

#include <errno.h>
#include <poll.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <dlfcn.h>
#include <elf.h>
#include <link.h>
#include <pthread.h>
#include <signal.h>
#include <stddef.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <sys/un.h>
#include <time.h>
#include <stdint.h>
#include <atomic>

#define FRAME_WIDTH 1920
#define FRAME_HEIGHT 1300
#define UYVY_SIZE (FRAME_WIDTH * FRAME_HEIGHT * 2)
#define MAGIC_HEADER 0x44494C35
#define AUTH_HELLO_MAGIC 0x44493548
#define AUTH_ACK_MAGIC 0x44493541
#define PROTOCOL_VERSION 1
#define AUTH_TOKEN_LENGTH 64
#define BUFFER_COUNT 5
#define IO_TIMEOUT_MS 1500
#define ACCEPT_POLL_MS 500
#define MAX_CLIENTS 8
#define MAX_CAMERAS 4
#define TEST_UTIL_WINDOW_SIZE 0xb0
#define TEST_UTIL_DESCRIPTOR_SIZE 56
#define TEST_UTIL_DESCRIPTOR_VADDR_OFFSET 0x10
#define TEST_UTIL_DESCRIPTOR_SIZE_OFFSET 0x20
#define TEST_UTIL_WINDOW_DESCRIPTORS_OFFSET 0x78
#define TEST_UTIL_WINDOW_BUFFER_COUNT_OFFSET 0x80
#define TEST_UTIL_WINDOW_WIDTH_OFFSET 0x84
#define TEST_UTIL_WINDOW_HEIGHT_OFFSET 0x88
#define TEST_UTIL_WINDOW_FORMAT_OFFSET 0x8c
#define TEST_UTIL_FORMAT_UYVY 4
#define TEST_UTIL_LIBRARY_NAME "libais_test_util.so"
#define TEST_UTIL_LIBRARY_PATH "/system/lib64/libais_test_util.so"
#define QCARCAM_FMT_UYVY_8 0x07080102u
#define MAX_COMPONENT_AGE_NS 500000000ULL
#define MAX_MOSAIC_SKEW_NS 100000000ULL

struct FrameHeader {
    uint32_t magic;
    uint32_t width;
    uint32_t height;
    uint32_t format;
    uint32_t data_size;
    uint32_t reserved;
    uint64_t sequence;
    uint64_t timestamp_ns;
};

struct AuthMessage {
    uint32_t magic;
    uint32_t version;
    int32_t pid;
    char token[AUTH_TOKEN_LENGTH];
};

static_assert(sizeof(FrameHeader) == 40, "FrameHeader wire size changed");
static_assert(sizeof(AuthMessage) == 76, "AuthMessage wire size changed");

static void* g_windows[MAX_CAMERAS] = { NULL, NULL, NULL, NULL };
static int g_num_windows = 0;
static uint64_t g_latest_sequence[MAX_CAMERAS] = {};
static uint64_t g_latest_timestamp_ns[MAX_CAMERAS] = {};
static uint64_t g_next_sequence = 0;
static bool g_layout_valid[MAX_CAMERAS] = {};
static uint8_t g_latest_frame[MAX_CAMERAS][UYVY_SIZE];

static int g_server_fd = -1;
static int g_clients[MAX_CLIENTS] = { -1, -1, -1, -1, -1, -1, -1, -1 };
static int g_client_cam[MAX_CLIENTS] = { 4, 4, 4, 4, 4, 4, 4, 4 };
static uint64_t g_client_sequence[MAX_CLIENTS] = {};
static uint64_t g_client_mosaic_sequences[MAX_CLIENTS][MAX_CAMERAS] = {};
static pthread_mutex_t g_mutex = PTHREAD_MUTEX_INITIALIZER;
static pthread_mutex_t g_frame_mutex = PTHREAD_MUTEX_INITIALIZER;
static std::atomic<bool> g_running{true};
static char g_socket_name[sizeof(((struct sockaddr_un*)0)->sun_path)] = {};
static char g_auth_token[AUTH_TOKEN_LENGTH + 1] = {};
static pid_t g_expected_client_pid = -1;
static std::atomic<bool> g_test_util_abi_verified{false};
static void* g_test_util_handle = NULL;
static pthread_once_t g_test_util_handle_once = PTHREAD_ONCE_INIT;

static uint8_t g_mosaic_buf[UYVY_SIZE];
static uint8_t g_single_buf[UYVY_SIZE];

struct QCarCamPlanePrefix {
    uint32_t width;
    uint32_t height;
    uint32_t stride;
    uint32_t size;
};

struct QCarCamBuffersPrefix {
    uint32_t color_format;
    uint32_t reserved;
    const uint8_t* buffers;
    uint32_t buffer_count;
};

static_assert(
        offsetof(QCarCamBuffersPrefix, buffers) == 8,
        "qcarcam buffer pointer offset changed");
static_assert(
        offsetof(QCarCamBuffersPrefix, buffer_count) == 16,
        "qcarcam buffer count offset changed");

static const uint8_t EXPECTED_TEST_UTIL_BUILD_ID[] = {
    0x4a, 0xa0, 0x18, 0x42, 0x81, 0x7a, 0x06, 0x42,
    0x1f, 0xd9, 0x64, 0xd0, 0x90, 0xdf, 0x2e, 0x02
};

struct BuildIdLookup {
    void* base;
    bool matched;
};

static int verify_loaded_build_id(
        struct dl_phdr_info* info, size_t, void* opaque) {
    BuildIdLookup* lookup = (BuildIdLookup*)opaque;
    if ((void*)info->dlpi_addr != lookup->base) return 0;
    for (ElfW(Half) i = 0; i < info->dlpi_phnum; i++) {
        const ElfW(Phdr)& phdr = info->dlpi_phdr[i];
        if (phdr.p_type != PT_NOTE) continue;
        const uint8_t* cursor =
                (const uint8_t*)(info->dlpi_addr + phdr.p_vaddr);
        const uint8_t* end = cursor + phdr.p_memsz;
        while (cursor + sizeof(ElfW(Nhdr)) <= end) {
            const ElfW(Nhdr)* note = (const ElfW(Nhdr)*)cursor;
            cursor += sizeof(ElfW(Nhdr));
            size_t name_size = (note->n_namesz + 3u) & ~3u;
            size_t desc_size = (note->n_descsz + 3u) & ~3u;
            if (cursor + name_size + desc_size > end) break;
            const uint8_t* name = cursor;
            const uint8_t* desc = cursor + name_size;
            if (note->n_type == NT_GNU_BUILD_ID
                    && note->n_namesz == 4
                    && memcmp(name, "GNU", 4) == 0
                    && note->n_descsz == sizeof(EXPECTED_TEST_UTIL_BUILD_ID)
                    && memcmp(
                            desc,
                            EXPECTED_TEST_UTIL_BUILD_ID,
                            sizeof(EXPECTED_TEST_UTIL_BUILD_ID)) == 0) {
                lookup->matched = true;
                return 1;
            }
            cursor += name_size + desc_size;
        }
    }
    return 1;
}

static bool verify_test_util_abi(void* symbol) {
    Dl_info info = {};
    if (!symbol || dladdr(symbol, &info) == 0 || !info.dli_fbase) {
        return false;
    }
    BuildIdLookup lookup = { info.dli_fbase, false };
    dl_iterate_phdr(verify_loaded_build_id, &lookup);
    return lookup.matched;
}

static void open_test_util_handle() {
    g_test_util_handle = dlopen(
            TEST_UTIL_LIBRARY_NAME, RTLD_NOW | RTLD_LOCAL);
    if (!g_test_util_handle) {
        g_test_util_handle = dlopen(
                TEST_UTIL_LIBRARY_PATH, RTLD_NOW | RTLD_LOCAL);
    }
    if (!g_test_util_handle) {
        const char* error = dlerror();
        fprintf(
                stderr,
                "[Hook] Unable to open camera utility library: %s\n",
                error ? error : "unknown error");
    }
}

static void* resolve_test_util_symbol(const char* name) {
    pthread_once(&g_test_util_handle_once, open_test_util_handle);
    if (!g_test_util_handle) return NULL;
    dlerror();
    void* symbol = dlsym(g_test_util_handle, name);
    const char* error = dlerror();
    if (!symbol || error) {
        fprintf(
                stderr,
                "[Hook] Unable to resolve camera utility symbol %s: %s\n",
                name,
                error ? error : "not found");
        return NULL;
    }
    return symbol;
}

static inline uint64_t get_monotonic_time_ns() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000000000ULL + (uint64_t)ts.tv_nsec;
}

static uint64_t get_monotonic_time_ms() {
    return get_monotonic_time_ns() / 1000000ULL;
}

static bool wait_for_io(int fd, short events, uint64_t deadline_ms) {
    while (g_running.load()) {
        uint64_t now = get_monotonic_time_ms();
        if (now >= deadline_ms) return false;
        int timeout = (int)(deadline_ms - now);
        struct pollfd pfd = { fd, events, 0 };
        int result = poll(&pfd, 1, timeout);
        if (result > 0) {
            if (pfd.revents & events) return true;
            if (pfd.revents & (POLLERR | POLLHUP | POLLNVAL)) return false;
        } else if (result == 0) {
            return false;
        } else if (errno != EINTR) {
            return false;
        }
    }
    return false;
}

static bool read_all(int fd, void* buf, size_t count, int timeout_ms) {
    size_t total = 0;
    uint8_t* ptr = (uint8_t*)buf;
    uint64_t deadline = get_monotonic_time_ms() + (uint64_t)timeout_ms;
    while (total < count && wait_for_io(fd, POLLIN, deadline)) {
        ssize_t r = recv(fd, ptr + total, count - total, 0);
        if (r > 0) {
            total += (size_t)r;
        } else if (r == 0) {
            return false;
        } else if (errno != EINTR && errno != EAGAIN && errno != EWOULDBLOCK) {
            return false;
        }
    }
    return total == count;
}

static bool write_all(int fd, const void* buf, size_t count, int timeout_ms) {
    size_t total = 0;
    const uint8_t* ptr = (const uint8_t*)buf;
    uint64_t deadline = get_monotonic_time_ms() + (uint64_t)timeout_ms;
    while (total < count && wait_for_io(fd, POLLOUT, deadline)) {
        ssize_t w = send(fd, ptr + total, count - total, MSG_NOSIGNAL);
        if (w > 0) {
            total += (size_t)w;
        } else if (w == 0) {
            return false;
        } else if (errno != EINTR && errno != EAGAIN && errno != EWOULDBLOCK) {
            return false;
        }
    }
    return total == count;
}

static bool set_socket_timeouts(int fd) {
    struct timeval timeout = {
        IO_TIMEOUT_MS / 1000,
        (IO_TIMEOUT_MS % 1000) * 1000
    };
    return setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout)) == 0
            && setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout)) == 0;
}

static bool constant_time_token_equal(const char* left, const char* right) {
    uint8_t different = 0;
    for (size_t i = 0; i < AUTH_TOKEN_LENGTH; i++) {
        different |= (uint8_t)(left[i] ^ right[i]);
    }
    return different == 0;
}

static bool load_channel_config() {
    const char* token = getenv("OVERDRIVE_QCARCAM_TOKEN");
    const char* socket_name = getenv("OVERDRIVE_QCARCAM_SOCKET");
    const char* client_pid = getenv("OVERDRIVE_QCARCAM_CLIENT_PID");
    if (!token || strlen(token) != AUTH_TOKEN_LENGTH
            || !socket_name || !client_pid) {
        return false;
    }
    for (size_t i = 0; i < AUTH_TOKEN_LENGTH; i++) {
        char c = token[i];
        if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
            return false;
        }
    }
    size_t socket_length = strlen(socket_name);
    if (socket_length == 0 || socket_length >= sizeof(g_socket_name)) {
        return false;
    }
    for (size_t i = 0; i < socket_length; i++) {
        char c = socket_name[i];
        if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'z')
                || c == '_' || c == '-')) {
            return false;
        }
    }
    char* end = NULL;
    errno = 0;
    long pid = strtol(client_pid, &end, 10);
    if (errno != 0 || !end || *end != '\0' || pid <= 0 || pid > INT32_MAX) {
        return false;
    }
    memcpy(g_auth_token, token, AUTH_TOKEN_LENGTH);
    memcpy(g_socket_name, socket_name, socket_length + 1);
    g_expected_client_pid = (pid_t)pid;
    if (getppid() != g_expected_client_pid) {
        return false;
    }
    unsetenv("OVERDRIVE_QCARCAM_TOKEN");
    unsetenv("OVERDRIVE_QCARCAM_SOCKET");
    unsetenv("OVERDRIVE_QCARCAM_CLIENT_PID");
    return true;
}

static void* parent_watchdog_thread(void*) {
    while (g_running.load()) {
        if (getppid() != g_expected_client_pid
                || (kill(g_expected_client_pid, 0) != 0 && errno == ESRCH)) {
            _exit(0);
        }
        usleep(500000);
    }
    return NULL;
}

static bool authenticate_client(int client) {
    struct ucred credentials = {};
    socklen_t credentials_length = sizeof(credentials);
    if (getsockopt(client, SOL_SOCKET, SO_PEERCRED,
                   &credentials, &credentials_length) != 0
            || credentials_length != sizeof(credentials)
            || credentials.pid != g_expected_client_pid
            || credentials.uid != getuid()
            || !set_socket_timeouts(client)) {
        return false;
    }

    AuthMessage hello = {};
    if (!read_all(client, &hello, sizeof(hello), IO_TIMEOUT_MS)
            || hello.magic != AUTH_HELLO_MAGIC
            || hello.version != PROTOCOL_VERSION
            || hello.pid != credentials.pid
            || !constant_time_token_equal(hello.token, g_auth_token)) {
        return false;
    }

    AuthMessage ack = {};
    ack.magic = AUTH_ACK_MAGIC;
    ack.version = PROTOCOL_VERSION;
    ack.pid = getpid();
    memcpy(ack.token, g_auth_token, AUTH_TOKEN_LENGTH);
    return write_all(client, &ack, sizeof(ack), IO_TIMEOUT_MS);
}

static void close_client_locked(int slot) {
    int fd = g_clients[slot];
    if (fd >= 0) {
        shutdown(fd, SHUT_RDWR);
        close(fd);
    }
    g_clients[slot] = -1;
    g_client_cam[slot] = 4;
    g_client_sequence[slot] = 0;
    memset(g_client_mosaic_sequences[slot], 0,
           sizeof(g_client_mosaic_sequences[slot]));
}

static int camera_index_for_window_locked(void* window) {
    for (int camera = 0; camera < g_num_windows; camera++) {
        if (g_windows[camera] == window) return camera;
    }
    return -1;
}

static const uint8_t* get_cam_vaddr(int cam_idx, int buf_idx) {
    if (cam_idx < 0 || cam_idx >= g_num_windows
            || buf_idx < 0 || buf_idx >= BUFFER_COUNT
            || !g_test_util_abi_verified.load() || !g_layout_valid[cam_idx]) {
        return NULL;
    }
    void* win = g_windows[cam_idx];
    if (!win) return NULL;
    uint8_t* p_win = (uint8_t*)win;
    uint32_t buffer_count = 0;
    uint32_t width = 0;
    uint32_t height = 0;
    uint8_t* p_desc_base = NULL;
    memcpy(
            &p_desc_base,
            p_win + TEST_UTIL_WINDOW_DESCRIPTORS_OFFSET,
            sizeof(p_desc_base));
    memcpy(
            &buffer_count,
            p_win + TEST_UTIL_WINDOW_BUFFER_COUNT_OFFSET,
            sizeof(buffer_count));
    memcpy(
            &width,
            p_win + TEST_UTIL_WINDOW_WIDTH_OFFSET,
            sizeof(width));
    memcpy(
            &height,
            p_win + TEST_UTIL_WINDOW_HEIGHT_OFFSET,
            sizeof(height));
    if (!p_desc_base) return NULL;
    if (buffer_count != BUFFER_COUNT
            || width != FRAME_WIDTH || height != FRAME_HEIGHT) {
        return NULL;
    }
    uint8_t* desc =
            p_desc_base + (buf_idx * TEST_UTIL_DESCRIPTOR_SIZE);
    const uint8_t* vaddr = NULL;
    uint32_t mapped_size = 0;
    memcpy(
            &vaddr,
            desc + TEST_UTIL_DESCRIPTOR_VADDR_OFFSET,
            sizeof(vaddr));
    memcpy(
            &mapped_size,
            desc + TEST_UTIL_DESCRIPTOR_SIZE_OFFSET,
            sizeof(mapped_size));
    return vaddr && mapped_size >= UYVY_SIZE ? vaddr : NULL;
}

// 2x2 grid compositor in UYVY (4 cameras combined into 1920x1300 at 30 FPS)
static void compose_2x2_mosaic(const uint8_t* cam0, const uint8_t* cam1, const uint8_t* cam2, const uint8_t* cam3) {
    const int W = FRAME_WIDTH;  // 1920
    const int H = FRAME_HEIGHT; // 1300
    const int HALF_W = W / 2;   // 960
    const int HALF_H = H / 2;   // 650

    // Top half: Cam 0 (Front) on Left, Cam 1 (Right) on Right
    for (int y = 0; y < HALF_H; y++) {
        const uint8_t* src0 = cam0 ? (cam0 + (y * 2) * W * 2) : NULL;
        const uint8_t* src1 = cam1 ? (cam1 + (y * 2) * W * 2) : NULL;
        uint8_t* dst_row = g_mosaic_buf + y * W * 2;

        // Top-Left: Cam 0
        for (int x = 0; x < HALF_W; x += 2) {
            if (src0) {
                int s = (x * 2) * 2;
                dst_row[x * 2]     = src0[s];
                dst_row[x * 2 + 1] = src0[s + 1];
                dst_row[x * 2 + 2] = src0[s + 2];
                dst_row[x * 2 + 3] = src0[s + 5];
            } else {
                memset(dst_row + x * 2, 0, 4);
            }
        }
        // Top-Right: Cam 1
        uint8_t* dst_right = dst_row + HALF_W * 2;
        for (int x = 0; x < HALF_W; x += 2) {
            if (src1) {
                int s = (x * 2) * 2;
                dst_right[x * 2]     = src1[s];
                dst_right[x * 2 + 1] = src1[s + 1];
                dst_right[x * 2 + 2] = src1[s + 2];
                dst_right[x * 2 + 3] = src1[s + 5];
            } else {
                memset(dst_right + x * 2, 0, 4);
            }
        }
    }

    // Bottom half: Cam 2 (Rear) on Left, Cam 3 (Left) on Right.
    // This preserves OverDrive's Q0=Front, Q1=Right, Q2=Rear, Q3=Left contract.
    for (int y = 0; y < HALF_H; y++) {
        const uint8_t* src2 = cam2 ? (cam2 + (y * 2) * W * 2) : NULL;
        const uint8_t* src3 = cam3 ? (cam3 + (y * 2) * W * 2) : NULL;
        uint8_t* dst_row = g_mosaic_buf + (y + HALF_H) * W * 2;

        // Bottom-Left: Cam 2
        for (int x = 0; x < HALF_W; x += 2) {
            if (src2) {
                int s = (x * 2) * 2;
                dst_row[x * 2]     = src2[s];
                dst_row[x * 2 + 1] = src2[s + 1];
                dst_row[x * 2 + 2] = src2[s + 2];
                dst_row[x * 2 + 3] = src2[s + 5];
            } else {
                memset(dst_row + x * 2, 0, 4);
            }
        }
        // Bottom-Right: Cam 3
        uint8_t* dst_right = dst_row + HALF_W * 2;
        for (int x = 0; x < HALF_W; x += 2) {
            if (src3) {
                int s = (x * 2) * 2;
                dst_right[x * 2]     = src3[s];
                dst_right[x * 2 + 1] = src3[s + 1];
                dst_right[x * 2 + 2] = src3[s + 2];
                dst_right[x * 2 + 3] = src3[s + 5];
            } else {
                memset(dst_right + x * 2, 0, 4);
            }
        }
    }
}

static void* dma_streamer_thread(void* arg) {
    printf("[Hook] Dedicated 30.0 FPS DMA Streaming engine started.\n");

    const uint64_t target_frame_period_ns = 33333333ULL; // 30.00 FPS (~33.33 ms)

    while (g_running.load()) {
        uint64_t frame_start_ns = get_monotonic_time_ns();

        pthread_mutex_lock(&g_mutex);

        // Check if any client is active
        int active_clients = 0;
        for (int i = 0; i < MAX_CLIENTS; i++) {
            if (g_clients[i] >= 0) active_clients++;
        }

        if (active_clients > 0) {
            bool mosaic_built = false;
            bool mosaic_checked = false;
            uint64_t mosaic_sequence = 0;
            uint64_t mosaic_timestamp_ns = 0;
            uint64_t mosaic_sequences[MAX_CAMERAS] = {};

            for (int i = 0; i < MAX_CLIENTS; i++) {
                int cfd = g_clients[i];
                if (cfd < 0) continue;

                // Check for client commands (non-blocking)
                uint8_t cmd = 0xFF;
                ssize_t cr = recv(cfd, &cmd, 1, MSG_DONTWAIT);
                if (cr == 1 && cmd <= 4) {
                    g_client_cam[i] = cmd;
                    g_client_sequence[i] = 0;
                    memset(g_client_mosaic_sequences[i], 0,
                           sizeof(g_client_mosaic_sequences[i]));
                    printf("[Hook] Client slot [%d] switched to Mode [%u]\n", i, cmd);
                } else if (cr == 0 || (cr < 0 && errno != EAGAIN
                        && errno != EWOULDBLOCK && errno != EINTR)) {
                    close_client_locked(i);
                    continue;
                }

                int target_mode = g_client_cam[i];
                const void* send_payload = NULL;
                uint64_t producer_sequence = 0;
                uint64_t producer_timestamp_ns = 0;
                bool producer_advanced = false;

                if (target_mode == 4) {
                    // 2x2 Mosaic
                    if (!mosaic_checked) {
                        mosaic_checked = true;
                        pthread_mutex_lock(&g_frame_mutex);
                        bool ready = true;
                        uint64_t now_ns = get_monotonic_time_ns();
                        uint64_t newest_timestamp_ns = 0;
                        mosaic_sequence = UINT64_MAX;
                        mosaic_timestamp_ns = UINT64_MAX;
                        for (int camera = 0; camera < MAX_CAMERAS; camera++) {
                            uint64_t sequence = g_latest_sequence[camera];
                            uint64_t timestamp = g_latest_timestamp_ns[camera];
                            mosaic_sequences[camera] = sequence;
                            if (sequence == 0 || timestamp == 0
                                    || timestamp > now_ns
                                    || now_ns - timestamp > MAX_COMPONENT_AGE_NS) {
                                ready = false;
                                break;
                            }
                            if (sequence < mosaic_sequence) {
                                mosaic_sequence = sequence;
                            }
                            if (timestamp < mosaic_timestamp_ns) {
                                mosaic_timestamp_ns = timestamp;
                            }
                            if (timestamp > newest_timestamp_ns) {
                                newest_timestamp_ns = timestamp;
                            }
                        }
                        if (ready
                                && newest_timestamp_ns - mosaic_timestamp_ns
                                        > MAX_MOSAIC_SKEW_NS) {
                            ready = false;
                        }
                        if (ready) {
                            compose_2x2_mosaic(
                                    g_latest_frame[0],
                                    g_latest_frame[1],
                                    g_latest_frame[2],
                                    g_latest_frame[3]);
                            mosaic_built = true;
                        }
                        pthread_mutex_unlock(&g_frame_mutex);
                    }
                    if (mosaic_built) {
                        producer_advanced = true;
                        for (int camera = 0; camera < MAX_CAMERAS; camera++) {
                            if (mosaic_sequences[camera]
                                    <= g_client_mosaic_sequences[i][camera]) {
                                producer_advanced = false;
                                break;
                            }
                        }
                        if (producer_advanced) {
                            send_payload = g_mosaic_buf;
                            producer_sequence = mosaic_sequence;
                            producer_timestamp_ns = mosaic_timestamp_ns;
                        }
                    }
                } else {
                    // Single camera
                    pthread_mutex_lock(&g_frame_mutex);
                    producer_sequence = g_latest_sequence[target_mode];
                    producer_timestamp_ns =
                            g_latest_timestamp_ns[target_mode];
                    producer_advanced =
                            producer_sequence > g_client_sequence[i];
                    if (producer_advanced) {
                        memcpy(
                                g_single_buf,
                                g_latest_frame[target_mode],
                                UYVY_SIZE);
                        send_payload = g_single_buf;
                    }
                    pthread_mutex_unlock(&g_frame_mutex);
                }

                if (send_payload && producer_advanced) {
                    FrameHeader header{};
                    header.magic = MAGIC_HEADER;
                    header.width = FRAME_WIDTH;
                    header.height = FRAME_HEIGHT;
                    header.format = 1; // UYVY
                    header.data_size = UYVY_SIZE;
                    header.sequence = producer_sequence;
                    header.timestamp_ns = producer_timestamp_ns;

                    if (!write_all(cfd, &header, sizeof(header), IO_TIMEOUT_MS)
                            || !write_all(cfd, send_payload,
                                          header.data_size, IO_TIMEOUT_MS)) {
                        close_client_locked(i);
                    } else {
                        g_client_sequence[i] = producer_sequence;
                        if (target_mode == 4) {
                            memcpy(
                                    g_client_mosaic_sequences[i],
                                    mosaic_sequences,
                                    sizeof(g_client_mosaic_sequences[i]));
                        }
                    }
                }
            }
        }

        pthread_mutex_unlock(&g_mutex);

        // Precise sleep to maintain stable 30.0 FPS
        uint64_t elapsed_ns = get_monotonic_time_ns() - frame_start_ns;
        if (elapsed_ns < target_frame_period_ns) {
            uint64_t sleep_ns = target_frame_period_ns - elapsed_ns;
            struct timespec req = { 0, (long)sleep_ns };
            nanosleep(&req, NULL);
        }
    }

    return NULL;
}

static void* socket_server_thread(void* arg) {
    g_server_fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (g_server_fd < 0) return NULL;

    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    addr.sun_path[0] = '\0';
    size_t socket_name_length = strlen(g_socket_name);
    memcpy(addr.sun_path + 1, g_socket_name, socket_name_length);
    socklen_t len = (socklen_t)(offsetof(struct sockaddr_un, sun_path)
            + 1 + socket_name_length);

    if (bind(g_server_fd, (struct sockaddr*)&addr, len) < 0) {
        close(g_server_fd);
        g_server_fd = -1;
        return NULL;
    }

    if (listen(g_server_fd, 8) < 0) {
        close(g_server_fd);
        g_server_fd = -1;
        return NULL;
    }
    printf("[Hook] Authenticated camera server is ready.\n");

    while (g_running.load()) {
        struct pollfd pfd = { g_server_fd, POLLIN, 0 };
        int ready = poll(&pfd, 1, ACCEPT_POLL_MS);
        if (ready < 0 && errno == EINTR) continue;
        if (ready <= 0 || !(pfd.revents & POLLIN)) continue;

        int client = accept(g_server_fd, NULL, NULL);
        if (client >= 0) {
            if (!authenticate_client(client)) {
                shutdown(client, SHUT_RDWR);
                close(client);
                continue;
            }
            pthread_mutex_lock(&g_mutex);
            bool added = false;
            for (int i = 0; i < MAX_CLIENTS; i++) {
                if (g_clients[i] < 0) {
                    g_clients[i] = client;
                    g_client_cam[i] = 4; // Default: 2x2 Mosaic
                    g_client_sequence[i] = 0;
                    memset(g_client_mosaic_sequences[i], 0,
                           sizeof(g_client_mosaic_sequences[i]));
                    printf("[Hook] Authenticated client connected in slot [%d]\n", i);
                    added = true;
                    break;
                }
            }
            if (!added) {
                shutdown(client, SHUT_RDWR);
                close(client);
            }
            pthread_mutex_unlock(&g_mutex);
        }
    }
    close(g_server_fd);
    g_server_fd = -1;
    return NULL;
}

__attribute__((constructor))
void hook_init() {
    setvbuf(stdout, NULL, _IOLBF, 0);
    if (!load_channel_config()) {
        g_running.store(false);
        fprintf(stderr, "[Hook] Camera channel configuration rejected.\n");
        return;
    }
    printf("[Hook] 4-Camera driver initialized.\n");
    pthread_t th_server, th_stream, th_parent;
    int error = pthread_create(
            &th_parent, NULL, parent_watchdog_thread, NULL);
    if (error != 0) {
        fprintf(stderr, "[Hook] Parent watchdog creation failed: %s\n",
                strerror(error));
        _exit(126);
    }
    pthread_detach(th_parent);
    error = pthread_create(&th_server, NULL, socket_server_thread, NULL);
    if (error != 0) {
        fprintf(stderr, "[Hook] Camera server creation failed: %s\n",
                strerror(error));
        _exit(126);
    }
    pthread_detach(th_server);
    error = pthread_create(&th_stream, NULL, dma_streamer_thread, NULL);
    if (error != 0) {
        fprintf(stderr, "[Hook] DMA streamer creation failed: %s\n",
                strerror(error));
        _exit(126);
    }
    pthread_detach(th_stream);
}

typedef int (*init_window_fn)(void* ctxt, void** pp_window);
static init_window_fn real_init_window = NULL;
static pthread_once_t real_init_window_once = PTHREAD_ONCE_INIT;

static void resolve_init_window(void) {
    real_init_window = (init_window_fn)resolve_test_util_symbol(
            "_Z21test_util_init_windowP16test_util_ctxt_tPP18test_util_window_t");
    g_test_util_abi_verified.store(
            verify_test_util_abi((void*)real_init_window));
    if (!g_test_util_abi_verified.load()) {
        fprintf(stderr, "[Hook] Unsupported camera utility ABI; streaming disabled.\n");
    }
}

extern "C" int _Z21test_util_init_windowP16test_util_ctxt_tPP18test_util_window_t(void* ctxt, void** pp_window) {
    pthread_once(&real_init_window_once, resolve_init_window);
    int res = real_init_window ? real_init_window(ctxt, pp_window) : 4;
    if (g_test_util_abi_verified.load() && pp_window && *pp_window) {
        pthread_mutex_lock(&g_frame_mutex);
        if (g_num_windows < MAX_CAMERAS) {
            g_windows[g_num_windows] = *pp_window;
            printf("[Hook] Captured Camera [%d] test_util window pointer: %p\n", g_num_windows, *pp_window);
            g_num_windows++;
        }
        pthread_mutex_unlock(&g_frame_mutex);
    }
    return res;
}

typedef int (*init_window_buffers_fn)(
        void* ctxt, void* window, void* buffers);
static init_window_buffers_fn real_init_window_buffers = NULL;
static pthread_once_t real_init_window_buffers_once = PTHREAD_ONCE_INIT;

static void resolve_init_window_buffers(void) {
    real_init_window_buffers =
        (init_window_buffers_fn)resolve_test_util_symbol(
            "_Z29test_util_init_window_buffersP16test_util_ctxt_tP18test_util_window_tP17qcarcam_buffers_t");
    if (!g_test_util_abi_verified.load()) {
        g_test_util_abi_verified.store(
                verify_test_util_abi((void*)real_init_window_buffers));
    }
}

extern "C" int _Z29test_util_init_window_buffersP16test_util_ctxt_tP18test_util_window_tP17qcarcam_buffers_t(
        void* ctxt, void* window, void* buffers) {
    pthread_once(
            &real_init_window_buffers_once,
            resolve_init_window_buffers);
    int result = real_init_window_buffers
            ? real_init_window_buffers(ctxt, window, buffers) : 4;
    if (result != 0 || !g_test_util_abi_verified.load()
            || !window || !buffers) {
        return result;
    }

    const QCarCamBuffersPrefix* declared =
            (const QCarCamBuffersPrefix*)buffers;
    uint32_t window_format = 0;
    memcpy(
            &window_format,
            (const uint8_t*)window + TEST_UTIL_WINDOW_FORMAT_OFFSET,
            sizeof(window_format));
    bool valid = declared->buffers
            && declared->buffer_count == BUFFER_COUNT
            && declared->color_format == QCARCAM_FMT_UYVY_8
            && window_format == TEST_UTIL_FORMAT_UYVY;
    for (uint32_t index = 0; valid && index < declared->buffer_count; index++) {
        const QCarCamPlanePrefix* plane =
                (const QCarCamPlanePrefix*)(
                        declared->buffers + index * 0x50);
        uint64_t required =
                (uint64_t)plane->stride * (uint64_t)plane->height;
        valid = plane->width == FRAME_WIDTH
                && plane->height == FRAME_HEIGHT
                && plane->stride == FRAME_WIDTH * 2
                && plane->size >= UYVY_SIZE
                && required <= plane->size;
    }

    pthread_mutex_lock(&g_frame_mutex);
    int camera = camera_index_for_window_locked(window);
    if (camera >= 0) g_layout_valid[camera] = valid;
    pthread_mutex_unlock(&g_frame_mutex);
    if (!valid) {
        fprintf(
                stderr,
                "[Hook] Camera buffer layout rejected (source=0x%x output=%u count=%u).\n",
                declared->color_format,
                window_format,
                declared->buffer_count);
    }
    return result;
}

typedef int (*post_window_fn)(
        void* ctxt, void* window, uint32_t buffer_index,
        void* dirty_rectangles, int field);
static post_window_fn real_post_window = NULL;
static pthread_once_t real_post_window_once = PTHREAD_ONCE_INIT;

static void resolve_post_window(void) {
    real_post_window = (post_window_fn)resolve_test_util_symbol(
            "_Z28test_util_post_window_bufferP16test_util_ctxt_tP18test_util_window_tjPNSt3__14listIjNS3_9allocatorIjEEEE15qcarcam_field_t");
}

extern "C" int _Z28test_util_post_window_bufferP16test_util_ctxt_tP18test_util_window_tjPNSt3__14listIjNS3_9allocatorIjEEEE15qcarcam_field_t(
        void* ctxt, void* window, uint32_t buffer_index,
        void* dirty_rectangles, int field) {
    pthread_once(&real_post_window_once, resolve_post_window);
    if (real_post_window && window && buffer_index < BUFFER_COUNT
            && g_test_util_abi_verified.load()) {
        pthread_mutex_lock(&g_frame_mutex);
        int camera = camera_index_for_window_locked(window);
        const uint8_t* source = camera >= 0 && g_layout_valid[camera]
                ? get_cam_vaddr(camera, (int)buffer_index) : NULL;
        if (source) {
            memcpy(g_latest_frame[camera], source, UYVY_SIZE);
            g_latest_sequence[camera] = ++g_next_sequence;
            g_latest_timestamp_ns[camera] = get_monotonic_time_ns();
        }
        pthread_mutex_unlock(&g_frame_mutex);
    }
    int result = real_post_window
            ? real_post_window(
                    ctxt, window, buffer_index, dirty_rectangles, field)
            : 4;
    return result;
}
