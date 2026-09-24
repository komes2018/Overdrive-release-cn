#pragma once

#include <stdint.h>

#define FAST_CAM_MAX_CAMS 8
#define FAST_CAM_BUFS_PER_CAM 5
#define FAST_CAM_MAX_TOTAL_BUFS (FAST_CAM_MAX_CAMS * FAST_CAM_BUFS_PER_CAM)
#define FAST_CAM_MAGIC 0x4643414D
#define FAST_CAM_CAP_FRAME_RELEASE_FENCE 0x80000000u
#define FAST_CAM_MSG_TYPE_MASK 0x0000ffffu

typedef enum {
    FAST_CAM_MSG_HANDSHAKE_REQ = 1,
    FAST_CAM_MSG_HANDSHAKE_RESP,
    FAST_CAM_MSG_FRAME_READY,
    FAST_CAM_MSG_FRAME_RELEASE,
} fast_cam_msg_type_t;

typedef struct {
    uint32_t cam_id;
    uint32_t width;
    uint32_t height;
    uint32_t stride;
    uint32_t buffer_bytes;
    uint32_t num_buffers;
    uint32_t fd_start_idx;
} __attribute__((packed)) fast_cam_stream_info_t;

typedef struct {
    uint32_t magic;
    uint32_t msg_type;
    uint32_t num_streams;
    uint32_t total_fds;
    fast_cam_stream_info_t streams[FAST_CAM_MAX_CAMS];
} __attribute__((packed)) fast_cam_handshake_multi_resp_t;

typedef struct {
    uint32_t magic;
    uint32_t msg_type;
    uint32_t cam_id;
    uint32_t buf_index;
    uint32_t sequence_no;
    uint32_t width;
    uint32_t height;
    uint64_t timestamp_ns;
} __attribute__((packed)) fast_cam_frame_msg_t;

typedef struct {
    uint32_t magic;
    uint32_t msg_type;
    uint32_t cam_id;
    uint32_t buf_index;
    uint32_t sequence_no;
} __attribute__((packed)) fast_cam_frame_release_msg_t;

#ifdef __cplusplus
static_assert(sizeof(fast_cam_stream_info_t) == 28);
static_assert(sizeof(fast_cam_handshake_multi_resp_t) == 240);
static_assert(sizeof(fast_cam_frame_msg_t) == 36);
static_assert(sizeof(fast_cam_frame_release_msg_t) == 20);
#else
_Static_assert(sizeof(fast_cam_stream_info_t) == 28, "FastCam stream ABI");
_Static_assert(
        sizeof(fast_cam_handshake_multi_resp_t) == 240,
        "FastCam handshake ABI");
_Static_assert(sizeof(fast_cam_frame_msg_t) == 36, "FastCam frame ABI");
_Static_assert(
        sizeof(fast_cam_frame_release_msg_t) == 20,
        "FastCam release ABI");
#endif
