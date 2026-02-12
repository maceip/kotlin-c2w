// android_io.hpp - Android I/O bridge for friscy syscalls
//
// Replaces Emscripten's EM_ASM stdin buffer and terminal size queries
// with thread-safe C++ equivalents used from JNI.
//
// The JNI layer (friscy_runtime.cpp) calls push_stdin() when the user
// types, and the syscall handlers call try_read_stdin() / has_stdin_data()
// to serve guest read()/ppoll() on fd 0.

#pragma once

#include <mutex>
#include <condition_variable>
#include <vector>
#include <atomic>
#include <cstdint>
#include <cstddef>
#include <algorithm>

namespace android_io {

// --- Stdin buffer (protected by mutex) ---

inline std::mutex stdin_mutex;
inline std::condition_variable stdin_cv;
inline std::vector<uint8_t> stdin_buffer;
inline std::atomic<bool> stdin_eof{false};

// --- Terminal dimensions ---

inline std::atomic<int> term_rows{24};
inline std::atomic<int> term_cols{80};

// --- Execution state ---

// True when the RISC-V machine stopped because stdin had no data.
// The execution loop checks this after simulate() returns to decide
// whether to wait for input or treat it as program exit.
inline std::atomic<bool> waiting_for_stdin{false};

// True while the execution thread is running.
inline std::atomic<bool> running{false};

// --- Functions ---

// Try to read from stdin buffer.
// Returns bytes read (>0), 0 if EOF, -1 if no data available yet.
inline int try_read_stdin(uint8_t* buf, size_t count) {
    std::lock_guard<std::mutex> lock(stdin_mutex);
    if (!stdin_buffer.empty()) {
        size_t to_read = std::min(count, stdin_buffer.size());
        std::copy(stdin_buffer.begin(), stdin_buffer.begin() + to_read, buf);
        stdin_buffer.erase(stdin_buffer.begin(), stdin_buffer.begin() + to_read);
        return static_cast<int>(to_read);
    }
    if (stdin_eof.load(std::memory_order_relaxed)) return 0;  // EOF
    return -1;  // No data yet
}

// Check if stdin has data available (non-blocking).
inline bool has_stdin_data() {
    std::lock_guard<std::mutex> lock(stdin_mutex);
    return !stdin_buffer.empty();
}

// Check if stdin is at EOF.
inline bool is_eof() {
    return stdin_eof.load(std::memory_order_relaxed);
}

// Push data to stdin buffer (called from JNI nativeSendInput).
inline void push_stdin(const uint8_t* data, size_t len) {
    {
        std::lock_guard<std::mutex> lock(stdin_mutex);
        stdin_buffer.insert(stdin_buffer.end(), data, data + len);
    }
    stdin_cv.notify_one();
}

// Reset all state for a new session.
inline void reset() {
    {
        std::lock_guard<std::mutex> lock(stdin_mutex);
        stdin_buffer.clear();
    }
    stdin_eof.store(false, std::memory_order_relaxed);
    waiting_for_stdin.store(false, std::memory_order_relaxed);
    running.store(false, std::memory_order_relaxed);
}

}  // namespace android_io
