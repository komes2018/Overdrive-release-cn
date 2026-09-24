#pragma once

#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define FAST_CAM_API __attribute__((visibility("default")))

typedef struct {
    uint32_t cam_id;
    uint32_t width;
    uint32_t height;
    uint32_t stride;
    uint32_t buffer_bytes;
    uint32_t buffer_index;
    uint32_t buffer_slot;
    uint32_t sequence_no;
    uint64_t timestamp_ns;
    int dma_buf_fd;
    const uint8_t* pixels;
} FastCamFrame;

typedef struct FastCamClientCtx FastCamClientCtx;

FAST_CAM_API FastCamClientCtx* fast_cam_client_create(void);
FAST_CAM_API void fast_cam_client_destroy(FastCamClientCtx* ctx);
FAST_CAM_API bool fast_cam_client_connect(
        FastCamClientCtx* ctx,
        const char* socket_path,
        int expected_server_pid);
FAST_CAM_API void fast_cam_client_disconnect(FastCamClientCtx* ctx);
FAST_CAM_API bool fast_cam_client_is_connected(
        const FastCamClientCtx* ctx);
FAST_CAM_API bool fast_cam_client_wait_frame(
        FastCamClientCtx* ctx,
        FastCamFrame* out_frame,
        int timeout_ms);
FAST_CAM_API bool fast_cam_client_supports_release_fence(
        const FastCamClientCtx* ctx);
FAST_CAM_API bool fast_cam_client_release_frame(
        FastCamClientCtx* ctx,
        const FastCamFrame* frame,
        int fence_fd);

#ifdef __cplusplus
}

class FastCamClient {
public:
    FastCamClient() : context_(fast_cam_client_create()) {}
    ~FastCamClient() { fast_cam_client_destroy(context_); }

    bool connect(const char* socket_path, int expected_server_pid) {
        return fast_cam_client_connect(
                context_, socket_path, expected_server_pid);
    }

    void disconnect() {
        fast_cam_client_disconnect(context_);
    }

    bool isConnected() const {
        return fast_cam_client_is_connected(context_);
    }

    bool waitForFrame(FastCamFrame* frame, int timeout_ms = 100) {
        return fast_cam_client_wait_frame(context_, frame, timeout_ms);
    }

    bool supportsReleaseFence() const {
        return fast_cam_client_supports_release_fence(context_);
    }

    bool releaseFrame(const FastCamFrame* frame, int fence_fd = -1) {
        return fast_cam_client_release_frame(context_, frame, fence_fd);
    }

private:
    FastCamClientCtx* context_;
};
#endif
