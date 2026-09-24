#include "fast_cam_bridge.h"
#include "fast_cam_ipc.h"

#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <stddef.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>

struct FastCamClientCtx {
    int sock_fd;
    int received_fds[FAST_CAM_MAX_TOTAL_BUFS];
    void* mapped_ptrs[FAST_CAM_MAX_TOTAL_BUFS];
    size_t mapped_sizes[FAST_CAM_MAX_TOTAL_BUFS];
    fast_cam_handshake_multi_resp_t handshake;
    bool release_fence_supported;
    bool release_pending;
    FastCamFrame pending_frame;
};

namespace {

constexpr int kHandshakeTimeoutMs = 2000;
constexpr int kFramePayloadTimeoutMs = 1000;

int64_t monotonicMillis() {
    struct timespec now = {};
    if (clock_gettime(CLOCK_MONOTONIC, &now) != 0) return -1;
    return static_cast<int64_t>(now.tv_sec) * 1000
            + now.tv_nsec / 1000000;
}

int remainingMillis(int64_t deadline) {
    int64_t now = monotonicMillis();
    if (now < 0 || now >= deadline) return 0;
    int64_t remaining = deadline - now;
    return remaining > INT32_MAX
            ? INT32_MAX
            : static_cast<int>(remaining);
}

int waitForReadable(int socket_fd, int64_t deadline) {
    struct pollfd descriptor = {};
    descriptor.fd = socket_fd;
    descriptor.events = POLLIN;
    int ready;
    do {
        int timeout = remainingMillis(deadline);
        if (timeout <= 0) return 0;
        ready = poll(&descriptor, 1, timeout);
    } while (ready < 0 && errno == EINTR);
    if (ready <= 0) return ready;
    return descriptor.revents & POLLIN ? 1 : -1;
}

bool receiveExact(
        int socket_fd,
        void* output,
        size_t length,
        int timeout_ms) {
    size_t total = 0;
    int64_t deadline = monotonicMillis() + timeout_ms;
    while (total < length) {
        if (waitForReadable(socket_fd, deadline) != 1) return false;
        ssize_t received = recv(
                socket_fd,
                static_cast<uint8_t*>(output) + total,
                length - total,
                MSG_DONTWAIT);
        if (received < 0 && (errno == EINTR
                || errno == EAGAIN
                || errno == EWOULDBLOCK)) {
            continue;
        }
        if (received <= 0) return false;
        total += static_cast<size_t>(received);
    }
    return true;
}

void resetFileDescriptors(FastCamClientCtx* context) {
    for (int i = 0; i < FAST_CAM_MAX_TOTAL_BUFS; i++) {
        context->received_fds[i] = -1;
    }
}

void closeFileDescriptors(int* descriptors, int count) {
    for (int i = 0; i < count; i++) {
        if (descriptors[i] >= 0) {
            close(descriptors[i]);
            descriptors[i] = -1;
        }
    }
}

bool receiveHandshake(FastCamClientCtx* context) {
    memset(&context->handshake, 0, sizeof(context->handshake));
    int received_fds[FAST_CAM_MAX_TOTAL_BUFS];
    for (int& descriptor : received_fds) descriptor = -1;
    int fd_count = 0;
    bool ancillary_valid = true;
    size_t total = 0;
    int64_t deadline = monotonicMillis() + kHandshakeTimeoutMs;
    while (total < sizeof(context->handshake) && ancillary_valid) {
        if (waitForReadable(context->sock_fd, deadline) != 1) break;
        struct iovec iov = {};
        iov.iov_base =
                reinterpret_cast<uint8_t*>(&context->handshake) + total;
        iov.iov_len = sizeof(context->handshake) - total;
        char control[
                CMSG_SPACE(sizeof(int) * FAST_CAM_MAX_TOTAL_BUFS)] = {};
        struct msghdr message = {};
        message.msg_iov = &iov;
        message.msg_iovlen = 1;
        message.msg_control = control;
        message.msg_controllen = sizeof(control);
        ssize_t received = recvmsg(
                context->sock_fd,
                &message,
                MSG_DONTWAIT | MSG_CMSG_CLOEXEC);
        if (received < 0 && (errno == EINTR
                || errno == EAGAIN
                || errno == EWOULDBLOCK)) {
            continue;
        }
        if (received <= 0) break;
        for (struct cmsghdr* header = CMSG_FIRSTHDR(&message);
                header;
                header = CMSG_NXTHDR(&message, header)) {
            if (header->cmsg_level != SOL_SOCKET
                    || header->cmsg_type != SCM_RIGHTS) {
                continue;
            }
            if (header->cmsg_len < CMSG_LEN(0)) {
                ancillary_valid = false;
                break;
            }
            size_t bytes = header->cmsg_len - CMSG_LEN(0);
            if (bytes % sizeof(int) != 0) {
                ancillary_valid = false;
                break;
            }
            int count = static_cast<int>(bytes / sizeof(int));
            int* descriptors =
                    reinterpret_cast<int*>(CMSG_DATA(header));
            if (count <= 0
                    || fd_count + count > FAST_CAM_MAX_TOTAL_BUFS) {
                for (int i = 0; i < count; i++) {
                    close(descriptors[i]);
                }
                ancillary_valid = false;
                break;
            }
            memcpy(
                    received_fds + fd_count,
                    descriptors,
                    static_cast<size_t>(count) * sizeof(int));
            fd_count += count;
        }
        if (message.msg_flags & (MSG_TRUNC | MSG_CTRUNC)) break;
        total += static_cast<size_t>(received);
    }
    bool handshake_valid =
            total == sizeof(context->handshake)
            && context->handshake.magic == FAST_CAM_MAGIC
            && (context->handshake.msg_type & FAST_CAM_MSG_TYPE_MASK)
                    == FAST_CAM_MSG_HANDSHAKE_RESP
            && context->handshake.num_streams > 0
            && context->handshake.num_streams <= FAST_CAM_MAX_CAMS
            && context->handshake.total_fds > 0
            && context->handshake.total_fds <= FAST_CAM_MAX_TOTAL_BUFS
            && fd_count == static_cast<int>(context->handshake.total_fds);
    if (!ancillary_valid || !handshake_valid) {
        closeFileDescriptors(received_fds, fd_count);
        return false;
    }

    bool covered[FAST_CAM_MAX_TOTAL_BUFS] = {};
    for (uint32_t stream_index = 0;
            stream_index < context->handshake.num_streams;
            stream_index++) {
        const fast_cam_stream_info_t& stream =
                context->handshake.streams[stream_index];
        for (uint32_t previous = 0; previous < stream_index; previous++) {
            if (context->handshake.streams[previous].cam_id
                    == stream.cam_id) {
                closeFileDescriptors(received_fds, fd_count);
                return false;
            }
        }
        uint64_t minimum_stride =
                static_cast<uint64_t>(stream.width) * 2u;
        uint64_t required_bytes =
                static_cast<uint64_t>(stream.stride) * stream.height;
        uint64_t end =
                static_cast<uint64_t>(stream.fd_start_idx)
                + stream.num_buffers;
        if (stream.width == 0
                || stream.height == 0
                || stream.stride < minimum_stride
                || stream.buffer_bytes < required_bytes
                || stream.num_buffers == 0
                || stream.num_buffers > FAST_CAM_BUFS_PER_CAM
                || end > context->handshake.total_fds) {
            closeFileDescriptors(received_fds, fd_count);
            return false;
        }
        for (uint32_t buffer = 0; buffer < stream.num_buffers; buffer++) {
            uint32_t slot = stream.fd_start_idx + buffer;
            if (covered[slot] || received_fds[slot] < 0) {
                closeFileDescriptors(received_fds, fd_count);
                return false;
            }
            covered[slot] = true;
        }
    }
    for (uint32_t slot = 0; slot < context->handshake.total_fds; slot++) {
        if (!covered[slot]) {
            closeFileDescriptors(received_fds, fd_count);
            return false;
        }
    }

    memcpy(
            context->received_fds,
            received_fds,
            static_cast<size_t>(fd_count) * sizeof(int));
    for (uint32_t stream_index = 0;
            stream_index < context->handshake.num_streams;
            stream_index++) {
        const fast_cam_stream_info_t& stream =
                context->handshake.streams[stream_index];
        for (uint32_t buffer = 0; buffer < stream.num_buffers; buffer++) {
            uint32_t slot = stream.fd_start_idx + buffer;
            context->mapped_sizes[slot] = stream.buffer_bytes;
            void* mapped = mmap(
                    nullptr,
                    stream.buffer_bytes,
                    PROT_READ,
                    MAP_SHARED,
                    context->received_fds[slot],
                    0);
            if (mapped == MAP_FAILED) {
                // The caller disconnects on a failed handshake, which
                // unmaps every earlier slot and closes the complete FD set.
                // Do not publish a connected client with a null CPU mapping:
                // the safe DI5 transport is CPU-copy by default, so such a
                // session can never produce a usable frame.
                context->mapped_ptrs[slot] = nullptr;
                return false;
            }
            context->mapped_ptrs[slot] = mapped;
        }
    }

    context->release_fence_supported =
            (context->handshake.msg_type
                    & FAST_CAM_CAP_FRAME_RELEASE_FENCE) != 0;
    return true;
}

const fast_cam_stream_info_t* findStream(
        const FastCamClientCtx* context,
        uint32_t camera_id) {
    for (uint32_t index = 0;
            index < context->handshake.num_streams;
            index++) {
        if (context->handshake.streams[index].cam_id == camera_id) {
            return &context->handshake.streams[index];
        }
    }
    return nullptr;
}

}  // namespace

FastCamClientCtx* fast_cam_client_create(void) {
    FastCamClientCtx* context =
            static_cast<FastCamClientCtx*>(calloc(1, sizeof(FastCamClientCtx)));
    if (!context) return nullptr;
    context->sock_fd = -1;
    resetFileDescriptors(context);
    return context;
}

void fast_cam_client_disconnect(FastCamClientCtx* context) {
    if (!context) return;
    if (context->sock_fd >= 0) {
        shutdown(context->sock_fd, SHUT_RDWR);
        close(context->sock_fd);
        context->sock_fd = -1;
    }
    for (int i = 0; i < FAST_CAM_MAX_TOTAL_BUFS; i++) {
        if (context->mapped_ptrs[i]) {
            munmap(context->mapped_ptrs[i], context->mapped_sizes[i]);
            context->mapped_ptrs[i] = nullptr;
        }
        context->mapped_sizes[i] = 0;
        if (context->received_fds[i] >= 0) {
            close(context->received_fds[i]);
            context->received_fds[i] = -1;
        }
    }
    memset(&context->handshake, 0, sizeof(context->handshake));
    memset(&context->pending_frame, 0, sizeof(context->pending_frame));
    context->release_fence_supported = false;
    context->release_pending = false;
}

void fast_cam_client_destroy(FastCamClientCtx* context) {
    if (!context) return;
    fast_cam_client_disconnect(context);
    free(context);
}

bool fast_cam_client_is_connected(const FastCamClientCtx* context) {
    return context && context->sock_fd >= 0;
}

bool fast_cam_client_connect(
        FastCamClientCtx* context,
        const char* socket_path,
        int expected_server_pid) {
    if (!context
            || !socket_path
            || socket_path[0] != '@'
            || expected_server_pid <= 0) {
        return false;
    }
    fast_cam_client_disconnect(context);

    context->sock_fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (context->sock_fd < 0) return false;

    struct sockaddr_un address = {};
    address.sun_family = AF_UNIX;
    size_t name_length = strlen(socket_path + 1);
    if (name_length == 0 || name_length >= sizeof(address.sun_path) - 1) {
        fast_cam_client_disconnect(context);
        return false;
    }
    memcpy(address.sun_path + 1, socket_path + 1, name_length);
    socklen_t address_length = static_cast<socklen_t>(
            offsetof(struct sockaddr_un, sun_path) + 1 + name_length);
    if (connect(
                context->sock_fd,
                reinterpret_cast<struct sockaddr*>(&address),
                address_length) != 0) {
        fast_cam_client_disconnect(context);
        return false;
    }
    struct ucred credentials = {};
    socklen_t credentials_length = sizeof(credentials);
    if (getsockopt(
                context->sock_fd,
                SOL_SOCKET,
                SO_PEERCRED,
                &credentials,
                &credentials_length) != 0
            || credentials_length != sizeof(credentials)
            || credentials.pid != expected_server_pid
            || !receiveHandshake(context)) {
        fast_cam_client_disconnect(context);
        return false;
    }
    return true;
}

bool fast_cam_client_wait_frame(
        FastCamClientCtx* context,
        FastCamFrame* output,
        int timeout_ms) {
    if (!context
            || context->sock_fd < 0
            || !output
            || context->release_pending) {
        return false;
    }

    int ready = waitForReadable(
            context->sock_fd, monotonicMillis() + timeout_ms);
    if (ready == 0) return false;
    if (ready < 0) {
        fast_cam_client_disconnect(context);
        return false;
    }

    fast_cam_frame_msg_t message = {};
    if (!receiveExact(
                context->sock_fd,
                &message,
                sizeof(message),
                kFramePayloadTimeoutMs)
            || message.magic != FAST_CAM_MAGIC
            || message.msg_type != FAST_CAM_MSG_FRAME_READY) {
        fast_cam_client_disconnect(context);
        return false;
    }

    const fast_cam_stream_info_t* stream =
            findStream(context, message.cam_id);
    if (!stream
            || message.buf_index >= stream->num_buffers
            || message.width != stream->width
            || message.height != stream->height) {
        fast_cam_client_disconnect(context);
        return false;
    }
    uint32_t slot = stream->fd_start_idx + message.buf_index;
    if (slot >= context->handshake.total_fds
            || context->received_fds[slot] < 0) {
        fast_cam_client_disconnect(context);
        return false;
    }

    FastCamFrame frame = {};
    frame.cam_id = message.cam_id;
    frame.width = stream->width;
    frame.height = stream->height;
    frame.stride = stream->stride;
    frame.buffer_bytes = stream->buffer_bytes;
    frame.buffer_index = message.buf_index;
    frame.buffer_slot = slot;
    frame.sequence_no = message.sequence_no;
    frame.timestamp_ns = message.timestamp_ns;
    frame.dma_buf_fd = context->received_fds[slot];
    frame.pixels =
            static_cast<const uint8_t*>(context->mapped_ptrs[slot]);
    context->pending_frame = frame;
    context->release_pending = context->release_fence_supported;
    *output = frame;
    return true;
}

bool fast_cam_client_supports_release_fence(
        const FastCamClientCtx* context) {
    return context && context->release_fence_supported;
}

bool fast_cam_client_release_frame(
        FastCamClientCtx* context,
        const FastCamFrame* frame,
        int fence_fd) {
    if (!context || !frame) return false;
    if (!context->release_fence_supported) return true;
    if (context->sock_fd < 0
            || !context->release_pending
            || frame->cam_id != context->pending_frame.cam_id
            || frame->buffer_index != context->pending_frame.buffer_index
            || frame->sequence_no != context->pending_frame.sequence_no) {
        return false;
    }

    fast_cam_frame_release_msg_t release = {};
    release.magic = FAST_CAM_MAGIC;
    release.msg_type = FAST_CAM_MSG_FRAME_RELEASE;
    release.cam_id = frame->cam_id;
    release.buf_index = frame->buffer_index;
    release.sequence_no = frame->sequence_no;
    struct iovec iov = {};
    iov.iov_base = &release;
    iov.iov_len = sizeof(release);
    char control[CMSG_SPACE(sizeof(int))] = {};
    struct msghdr message = {};
    message.msg_iov = &iov;
    message.msg_iovlen = 1;
    if (fence_fd >= 0) {
        message.msg_control = control;
        message.msg_controllen = sizeof(control);
        struct cmsghdr* header = CMSG_FIRSTHDR(&message);
        header->cmsg_level = SOL_SOCKET;
        header->cmsg_type = SCM_RIGHTS;
        header->cmsg_len = CMSG_LEN(sizeof(int));
        memcpy(CMSG_DATA(header), &fence_fd, sizeof(fence_fd));
    }

    ssize_t sent = sendmsg(context->sock_fd, &message, MSG_NOSIGNAL);
    if (sent != static_cast<ssize_t>(sizeof(release))) {
        fast_cam_client_disconnect(context);
        return false;
    }
    context->release_pending = false;
    memset(&context->pending_frame, 0, sizeof(context->pending_frame));
    return true;
}
