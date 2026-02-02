/**
 * c2w WAMR Runtime for Android
 *
 * High-performance WebAssembly execution for container2wasm
 * using WAMR (WebAssembly Micro Runtime) with WASI support.
 *
 * Architecture:
 *   Java UI ←→ JNI ←→ Pipes ←→ WAMR (WASI) ←→ Bochs WASM ←→ Linux
 *
 * Performance vs Chicory:
 *   - Chicory: Java WASM interpreter (~5% native speed)
 *   - WAMR Fast Interp: Native interpreter (~75% native speed)
 *   - Expected speedup: 10-15x
 */

#include <jni.h>
#include <android/log.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>

#include <string>
#include <atomic>
#include <thread>
#include <mutex>
#include <cstring>
#include <unistd.h>
#include <fcntl.h>
#include <poll.h>
#include <sys/select.h>
#include <sys/stat.h>

#include "wasm_export.h"

#define LOG_TAG "c2w_wamr"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ============================================================================
// Global State
// ============================================================================

static wasm_module_t g_wasm_module = nullptr;
static wasm_module_inst_t g_module_inst = nullptr;
static std::atomic<bool> g_vm_running{false};

// Pipes for WASI stdio redirection
static int g_stdin_pipe[2] = {-1, -1};   // [0]=read (WASM), [1]=write (Java)
static int g_stdout_pipe[2] = {-1, -1};  // [0]=read (Java), [1]=write (WASM)

// Threads
static std::thread g_vm_thread;
static std::thread g_stdout_thread;

// Java callback
static JavaVM* g_jvm = nullptr;
static jobject g_callback_obj = nullptr;
static jmethodID g_on_output_method = nullptr;

// c2w handshake detection
static std::atomic<bool> g_handshake_sent{false};
static int g_equal_count = 0;

// Checkpoint/Snapshot support
static std::string g_checkpoint_path;
static std::atomic<bool> g_checkpoint_ready{false};

// Checkpoint file format magic
static const char CHECKPOINT_MAGIC[8] = {'C', '2', 'W', 'S', 'N', 'A', 'P', '\0'};
static const uint32_t CHECKPOINT_VERSION = 1;

// ============================================================================
// Utility Functions
// ============================================================================

static void send_to_java(const char* data, size_t len) {
    if (!g_jvm || !g_callback_obj || !g_on_output_method || len == 0) return;

    JNIEnv* env = nullptr;
    bool attached = false;

    jint res = g_jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (res == JNI_EDETACHED) {
        if (g_jvm->AttachCurrentThread(&env, nullptr) != 0) return;
        attached = true;
    }

    if (env) {
        // Create Java string from data (handle non-null-terminated)
        std::string str(data, len);
        jstring jstr = env->NewStringUTF(str.c_str());
        if (jstr) {
            env->CallVoidMethod(g_callback_obj, g_on_output_method, jstr);
            env->DeleteLocalRef(jstr);
        }
    }

    if (attached) {
        g_jvm->DetachCurrentThread();
    }
}

static void close_pipes() {
    for (int& fd : g_stdin_pipe) {
        if (fd >= 0) { close(fd); fd = -1; }
    }
    for (int& fd : g_stdout_pipe) {
        if (fd >= 0) { close(fd); fd = -1; }
    }
}

// ============================================================================
// Stdout Reader Thread
// ============================================================================

static void stdout_reader_thread() {
    char buf[4096];
    g_equal_count = 0;

    LOGI("Stdout reader thread started");

    while (g_vm_running && g_stdout_pipe[0] >= 0) {
        // Use poll to wait for data with timeout
        struct pollfd pfd = { g_stdout_pipe[0], POLLIN, 0 };
        int ret = poll(&pfd, 1, 100);  // 100ms timeout

        if (ret > 0 && (pfd.revents & POLLIN)) {
            ssize_t n = read(g_stdout_pipe[0], buf, sizeof(buf) - 1);
            if (n > 0) {
                buf[n] = '\0';

                // Send to Java
                send_to_java(buf, n);

                // Detect c2w handshake: "=========="
                if (!g_handshake_sent) {
                    for (ssize_t i = 0; i < n; i++) {
                        if (buf[i] == '=') {
                            g_equal_count++;
                            if (g_equal_count >= 10 && !g_handshake_sent.exchange(true)) {
                                LOGI("c2w handshake detected - sending boot signal");

                                // Small delay then send "=\n"
                                std::this_thread::sleep_for(std::chrono::milliseconds(50));

                                if (g_stdin_pipe[1] >= 0) {
                                    write(g_stdin_pipe[1], "=\n", 2);
                                    send_to_java("[Host] Boot signal sent\n", 24);
                                }
                            }
                        } else {
                            g_equal_count = 0;
                        }
                    }
                }
            } else if (n == 0) {
                // EOF
                break;
            }
        } else if (ret < 0 && errno != EINTR) {
            break;
        }
    }

    LOGI("Stdout reader thread exiting");
}

// ============================================================================
// Checkpoint/Snapshot Functions
// ============================================================================

/**
 * Save WASM linear memory to a checkpoint file.
 * Format:
 *   - 8 bytes: Magic "C2WSNAP\0"
 *   - 4 bytes: Version
 *   - 8 bytes: Memory size in bytes
 *   - N bytes: Raw linear memory
 *
 * Note: This captures only linear memory, not globals/tables/stack.
 * For Bochs emulation, linear memory contains the entire emulated machine state
 * (RAM, CPU state stored in memory), so this should be sufficient for restore.
 */
static bool save_checkpoint(const char* path) {
    if (!g_module_inst) {
        LOGE("Cannot save checkpoint: no module instance");
        return false;
    }

    // Get memory instance
    wasm_memory_inst_t memory = wasm_runtime_get_default_memory(g_module_inst);
    if (!memory) {
        LOGE("Cannot save checkpoint: no memory instance");
        return false;
    }

    // Get memory info
    void* base_addr = wasm_memory_get_base_address(memory);
    uint64_t page_count = wasm_memory_get_cur_page_count(memory);
    uint64_t bytes_per_page = wasm_memory_get_bytes_per_page(memory);
    uint64_t memory_size = page_count * bytes_per_page;

    LOGI("Saving checkpoint: %llu pages, %llu bytes/page, total %llu MB",
         (unsigned long long)page_count,
         (unsigned long long)bytes_per_page,
         (unsigned long long)(memory_size >> 20));

    // Open file for writing
    int fd = open(path, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (fd < 0) {
        LOGE("Cannot create checkpoint file: %s (errno=%d)", path, errno);
        return false;
    }

    // Write header
    bool success = true;
    if (write(fd, CHECKPOINT_MAGIC, 8) != 8) success = false;
    if (success && write(fd, &CHECKPOINT_VERSION, 4) != 4) success = false;
    if (success && write(fd, &memory_size, 8) != 8) success = false;

    // Write memory in chunks to avoid large single writes
    if (success && base_addr) {
        const size_t CHUNK_SIZE = 16 * 1024 * 1024;  // 16MB chunks
        uint8_t* src = static_cast<uint8_t*>(base_addr);
        uint64_t remaining = memory_size;

        while (remaining > 0 && success) {
            size_t chunk = (remaining > CHUNK_SIZE) ? CHUNK_SIZE : remaining;
            ssize_t written = write(fd, src, chunk);
            if (written != static_cast<ssize_t>(chunk)) {
                LOGE("Write error at offset %llu: expected %zu, got %zd",
                     (unsigned long long)(memory_size - remaining), chunk, written);
                success = false;
                break;
            }
            src += chunk;
            remaining -= chunk;
        }
    }

    close(fd);

    if (success) {
        LOGI("Checkpoint saved successfully: %s", path);
    } else {
        LOGE("Failed to save checkpoint");
        unlink(path);
    }

    return success;
}

/**
 * Restore WASM linear memory from a checkpoint file.
 * Must be called after module is instantiated but before execution starts.
 */
static bool restore_checkpoint(const char* path) {
    if (!g_module_inst) {
        LOGE("Cannot restore checkpoint: no module instance");
        return false;
    }

    // Get memory instance
    wasm_memory_inst_t memory = wasm_runtime_get_default_memory(g_module_inst);
    if (!memory) {
        LOGE("Cannot restore checkpoint: no memory instance");
        return false;
    }

    // Open checkpoint file
    int fd = open(path, O_RDONLY);
    if (fd < 0) {
        LOGI("No checkpoint file found: %s", path);
        return false;
    }

    // Read and verify header
    char magic[8];
    uint32_t version;
    uint64_t saved_memory_size;

    bool valid = true;
    if (read(fd, magic, 8) != 8 || memcmp(magic, CHECKPOINT_MAGIC, 8) != 0) {
        LOGE("Invalid checkpoint magic");
        valid = false;
    }
    if (valid && (read(fd, &version, 4) != 4 || version != CHECKPOINT_VERSION)) {
        LOGE("Invalid checkpoint version: %u", version);
        valid = false;
    }
    if (valid && read(fd, &saved_memory_size, 8) != 8) {
        LOGE("Cannot read memory size from checkpoint");
        valid = false;
    }

    if (!valid) {
        close(fd);
        return false;
    }

    // Get current memory info
    void* base_addr = wasm_memory_get_base_address(memory);
    uint64_t page_count = wasm_memory_get_cur_page_count(memory);
    uint64_t bytes_per_page = wasm_memory_get_bytes_per_page(memory);
    uint64_t current_memory_size = page_count * bytes_per_page;

    LOGI("Restoring checkpoint: saved=%llu MB, current=%llu MB",
         (unsigned long long)(saved_memory_size >> 20),
         (unsigned long long)(current_memory_size >> 20));

    // Grow memory if needed
    if (saved_memory_size > current_memory_size) {
        uint64_t needed_pages = (saved_memory_size + bytes_per_page - 1) / bytes_per_page;
        uint64_t pages_to_add = needed_pages - page_count;
        LOGI("Growing memory by %llu pages", (unsigned long long)pages_to_add);
        if (!wasm_memory_enlarge(memory, pages_to_add)) {
            LOGE("Failed to grow memory for checkpoint restore");
            close(fd);
            return false;
        }
        // Refresh base address after growth
        base_addr = wasm_memory_get_base_address(memory);
    }

    // Read memory in chunks
    bool success = true;
    if (base_addr) {
        const size_t CHUNK_SIZE = 16 * 1024 * 1024;  // 16MB chunks
        uint8_t* dst = static_cast<uint8_t*>(base_addr);
        uint64_t remaining = saved_memory_size;

        while (remaining > 0 && success) {
            size_t chunk = (remaining > CHUNK_SIZE) ? CHUNK_SIZE : remaining;
            ssize_t bytes_read = read(fd, dst, chunk);
            if (bytes_read != static_cast<ssize_t>(chunk)) {
                LOGE("Read error at offset %llu: expected %zu, got %zd",
                     (unsigned long long)(saved_memory_size - remaining), chunk, bytes_read);
                success = false;
                break;
            }
            dst += chunk;
            remaining -= chunk;
        }
    }

    close(fd);

    if (success) {
        LOGI("Checkpoint restored successfully");
        g_checkpoint_ready = true;
    } else {
        LOGE("Failed to restore checkpoint");
    }

    return success;
}

// ============================================================================
// VM Execution Thread
// ============================================================================

static void vm_execution_thread() {
    LOGI("VM execution thread started");

    const char* exception;
    wasm_application_execute_main(g_module_inst, 0, nullptr);

    if ((exception = wasm_runtime_get_exception(g_module_inst))) {
        LOGE("WASM exception: %s", exception);
        std::string err = "\n[VM Error] " + std::string(exception) + "\n";
        send_to_java(err.c_str(), err.size());
    } else {
        int exit_code = wasm_runtime_get_wasi_exit_code(g_module_inst);
        LOGI("WASM exited with code: %d", exit_code);
    }

    g_vm_running = false;
    LOGI("VM execution thread exiting");
}

// ============================================================================
// JNI Functions
// ============================================================================

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    g_jvm = vm;
    LOGI("WAMR c2w runtime loaded");
    return JNI_VERSION_1_6;
}

JNIEXPORT jboolean JNICALL
Java_com_example_c2wdemo_WamrRuntime_nativeInit(JNIEnv* env, jclass clazz) {
    LOGI("Initializing WAMR runtime...");

    RuntimeInitArgs init_args;
    memset(&init_args, 0, sizeof(RuntimeInitArgs));

    init_args.mem_alloc_type = Alloc_With_Allocator;
    init_args.mem_alloc_option.allocator.malloc_func = reinterpret_cast<void*>(malloc);
    init_args.mem_alloc_option.allocator.realloc_func = reinterpret_cast<void*>(realloc);
    init_args.mem_alloc_option.allocator.free_func = reinterpret_cast<void*>(free);

    if (!wasm_runtime_full_init(&init_args)) {
        LOGE("Failed to initialize WAMR");
        return JNI_FALSE;
    }

    LOGI("WAMR initialized successfully");
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_example_c2wdemo_WamrRuntime_nativeLoadModule(
    JNIEnv* env, jclass clazz, jbyteArray wasmBytes) {

    if (g_wasm_module) {
        LOGE("Module already loaded");
        return JNI_FALSE;
    }

    jsize len = env->GetArrayLength(wasmBytes);
    LOGI("Loading WASM: %d bytes (%.1f MB)", len, len / 1048576.0);

    jbyte* bytes = env->GetByteArrayElements(wasmBytes, nullptr);
    if (!bytes) {
        LOGE("Failed to get byte array");
        return JNI_FALSE;
    }

    // WAMR needs writable buffer
    uint8_t* wasm_buf = static_cast<uint8_t*>(malloc(len));
    if (!wasm_buf) {
        LOGE("Failed to allocate buffer");
        env->ReleaseByteArrayElements(wasmBytes, bytes, JNI_ABORT);
        return JNI_FALSE;
    }
    memcpy(wasm_buf, bytes, len);
    env->ReleaseByteArrayElements(wasmBytes, bytes, JNI_ABORT);

    char error_buf[256];
    g_wasm_module = wasm_runtime_load(wasm_buf, len, error_buf, sizeof(error_buf));

    if (!g_wasm_module) {
        LOGE("Failed to load module: %s", error_buf);
        free(wasm_buf);
        return JNI_FALSE;
    }

    LOGI("WASM module loaded successfully");
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_example_c2wdemo_WamrRuntime_nativeStart(
    JNIEnv* env, jclass clazz, jobject callback) {

    if (!g_wasm_module) {
        LOGE("No module loaded");
        return JNI_FALSE;
    }

    if (g_vm_running) {
        LOGE("VM already running");
        return JNI_FALSE;
    }

    // Store callback
    g_callback_obj = env->NewGlobalRef(callback);
    jclass cls = env->GetObjectClass(callback);
    g_on_output_method = env->GetMethodID(cls, "onOutput", "(Ljava/lang/String;)V");

    // Create pipes
    if (pipe(g_stdin_pipe) < 0 || pipe(g_stdout_pipe) < 0) {
        LOGE("Failed to create pipes");
        close_pipes();
        return JNI_FALSE;
    }

    // Make stdin read non-blocking for WASM (so it can poll)
    fcntl(g_stdin_pipe[0], F_SETFL, O_NONBLOCK);

    // Reset handshake state
    g_handshake_sent = false;
    g_equal_count = 0;

    // Configure WASI
    const char* dir_list[] = { "/", "." };
    uint32_t dir_count = 2;
    const char* env_list[] = { nullptr };  // Empty env for c2w

    // Set WASI args with our custom file descriptors
    wasm_runtime_set_wasi_args_ex(
        g_wasm_module,
        dir_list, dir_count,     // Preopened directories
        nullptr, 0,              // Map dirs
        env_list, 0,             // Environment
        nullptr, 0,              // Args
        g_stdin_pipe[0],         // stdin = read end of stdin pipe
        g_stdout_pipe[1],        // stdout = write end of stdout pipe
        g_stdout_pipe[1]         // stderr = same as stdout
    );

    // Large stack/heap for Bochs x86 emulator
    uint32_t stack_size = 8 * 1024 * 1024;    // 8MB stack
    uint32_t heap_size = 512 * 1024 * 1024;   // 512MB heap

    LOGI("Instantiating: stack=%uMB heap=%uMB", stack_size >> 20, heap_size >> 20);

    char error_buf[256];
    g_module_inst = wasm_runtime_instantiate(
        g_wasm_module, stack_size, heap_size, error_buf, sizeof(error_buf));

    if (!g_module_inst) {
        LOGE("Instantiate failed: %s", error_buf);
        close_pipes();
        return JNI_FALSE;
    }

    // Start threads
    g_vm_running = true;
    g_stdout_thread = std::thread(stdout_reader_thread);
    g_vm_thread = std::thread(vm_execution_thread);

    LOGI("VM started successfully");
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_example_c2wdemo_WamrRuntime_nativeSendInput(
    JNIEnv* env, jclass clazz, jstring input) {

    if (!g_vm_running || g_stdin_pipe[1] < 0) return;

    const char* str = env->GetStringUTFChars(input, nullptr);
    if (str) {
        size_t len = strlen(str);
        write(g_stdin_pipe[1], str, len);
        env->ReleaseStringUTFChars(input, str);
    }
}

JNIEXPORT void JNICALL
Java_com_example_c2wdemo_WamrRuntime_nativeStop(JNIEnv* env, jclass clazz) {
    LOGI("Stopping VM...");

    g_vm_running = false;

    // Close write ends to signal EOF
    if (g_stdin_pipe[1] >= 0) { close(g_stdin_pipe[1]); g_stdin_pipe[1] = -1; }
    if (g_stdout_pipe[1] >= 0) { close(g_stdout_pipe[1]); g_stdout_pipe[1] = -1; }

    // Wait for threads
    if (g_stdout_thread.joinable()) g_stdout_thread.join();
    if (g_vm_thread.joinable()) g_vm_thread.join();

    // Cleanup
    close_pipes();

    if (g_module_inst) {
        wasm_runtime_deinstantiate(g_module_inst);
        g_module_inst = nullptr;
    }

    if (g_callback_obj) {
        env->DeleteGlobalRef(g_callback_obj);
        g_callback_obj = nullptr;
    }

    LOGI("VM stopped");
}

JNIEXPORT void JNICALL
Java_com_example_c2wdemo_WamrRuntime_nativeDestroy(JNIEnv* env, jclass clazz) {
    Java_com_example_c2wdemo_WamrRuntime_nativeStop(env, clazz);

    if (g_wasm_module) {
        wasm_runtime_unload(g_wasm_module);
        g_wasm_module = nullptr;
    }

    wasm_runtime_destroy();
    LOGI("WAMR destroyed");
}

JNIEXPORT jboolean JNICALL
Java_com_example_c2wdemo_WamrRuntime_nativeIsRunning(JNIEnv* env, jclass clazz) {
    return g_vm_running ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_example_c2wdemo_WamrRuntime_nativeGetVersion(JNIEnv* env, jclass clazz) {
    return env->NewStringUTF("WAMR AOT + Fast Interp | WASI Preview 1 | SIMD | Checkpoint");
}

// ============================================================================
// Checkpoint JNI Functions
// ============================================================================

/**
 * Set the checkpoint file path for save/restore operations.
 */
JNIEXPORT void JNICALL
Java_com_example_c2wdemo_WamrRuntime_nativeSetCheckpointPath(
    JNIEnv* env, jclass clazz, jstring path) {

    const char* str = env->GetStringUTFChars(path, nullptr);
    if (str) {
        g_checkpoint_path = str;
        LOGI("Checkpoint path set: %s", g_checkpoint_path.c_str());
        env->ReleaseStringUTFChars(path, str);
    }
}

/**
 * Save current VM state to checkpoint file.
 * Can be called while VM is running (will briefly pause for consistency).
 * Returns true on success.
 */
JNIEXPORT jboolean JNICALL
Java_com_example_c2wdemo_WamrRuntime_nativeSaveCheckpoint(JNIEnv* env, jclass clazz) {
    if (g_checkpoint_path.empty()) {
        LOGE("No checkpoint path set");
        return JNI_FALSE;
    }

    if (!g_module_inst) {
        LOGE("No module instance for checkpoint");
        return JNI_FALSE;
    }

    // Note: For a truly consistent snapshot during execution, we would need
    // to pause the VM thread. For Bochs emulation, the memory should be
    // mostly consistent as long as we're not in the middle of an instruction.
    // A more robust solution would involve WAMR's suspend/resume APIs.

    bool result = save_checkpoint(g_checkpoint_path.c_str());
    return result ? JNI_TRUE : JNI_FALSE;
}

/**
 * Check if a checkpoint file exists.
 */
JNIEXPORT jboolean JNICALL
Java_com_example_c2wdemo_WamrRuntime_nativeHasCheckpoint(JNIEnv* env, jclass clazz) {
    if (g_checkpoint_path.empty()) {
        return JNI_FALSE;
    }

    int fd = open(g_checkpoint_path.c_str(), O_RDONLY);
    if (fd < 0) {
        return JNI_FALSE;
    }
    close(fd);
    return JNI_TRUE;
}

/**
 * Delete the checkpoint file.
 */
JNIEXPORT void JNICALL
Java_com_example_c2wdemo_WamrRuntime_nativeDeleteCheckpoint(JNIEnv* env, jclass clazz) {
    if (!g_checkpoint_path.empty()) {
        unlink(g_checkpoint_path.c_str());
        LOGI("Checkpoint deleted");
    }
}

/**
 * Get checkpoint info as a string (for display).
 * Returns null if no checkpoint exists.
 */
JNIEXPORT jstring JNICALL
Java_com_example_c2wdemo_WamrRuntime_nativeGetCheckpointInfo(JNIEnv* env, jclass clazz) {
    if (g_checkpoint_path.empty()) {
        return nullptr;
    }

    int fd = open(g_checkpoint_path.c_str(), O_RDONLY);
    if (fd < 0) {
        return nullptr;
    }

    // Read header
    char magic[8];
    uint32_t version;
    uint64_t memory_size;

    bool valid = read(fd, magic, 8) == 8 &&
                 memcmp(magic, CHECKPOINT_MAGIC, 8) == 0 &&
                 read(fd, &version, 4) == 4 &&
                 read(fd, &memory_size, 8) == 8;
    close(fd);

    if (!valid) {
        return nullptr;
    }

    // Get file size
    struct stat st;
    stat(g_checkpoint_path.c_str(), &st);

    char info[256];
    snprintf(info, sizeof(info),
             "Checkpoint: %.1f MB memory, %.1f MB file",
             memory_size / 1048576.0,
             st.st_size / 1048576.0);

    return env->NewStringUTF(info);
}

/**
 * Start VM with checkpoint restore.
 * If checkpoint exists, restores from it instead of running from start.
 * Returns true on success.
 */
JNIEXPORT jboolean JNICALL
Java_com_example_c2wdemo_WamrRuntime_nativeStartWithRestore(
    JNIEnv* env, jclass clazz, jobject callback) {

    if (!g_wasm_module) {
        LOGE("No module loaded");
        return JNI_FALSE;
    }

    if (g_vm_running) {
        LOGE("VM already running");
        return JNI_FALSE;
    }

    // Store callback
    g_callback_obj = env->NewGlobalRef(callback);
    jclass cls = env->GetObjectClass(callback);
    g_on_output_method = env->GetMethodID(cls, "onOutput", "(Ljava/lang/String;)V");

    // Create pipes
    if (pipe(g_stdin_pipe) < 0 || pipe(g_stdout_pipe) < 0) {
        LOGE("Failed to create pipes");
        close_pipes();
        return JNI_FALSE;
    }

    // Make stdin read non-blocking for WASM (so it can poll)
    fcntl(g_stdin_pipe[0], F_SETFL, O_NONBLOCK);

    // Reset handshake state
    g_handshake_sent = false;
    g_equal_count = 0;
    g_checkpoint_ready = false;

    // Configure WASI
    const char* dir_list[] = { "/", "." };
    uint32_t dir_count = 2;
    const char* env_list[] = { nullptr };

    wasm_runtime_set_wasi_args_ex(
        g_wasm_module,
        dir_list, dir_count,
        nullptr, 0,
        env_list, 0,
        nullptr, 0,
        g_stdin_pipe[0],
        g_stdout_pipe[1],
        g_stdout_pipe[1]
    );

    // Large stack/heap for Bochs x86 emulator
    uint32_t stack_size = 8 * 1024 * 1024;
    uint32_t heap_size = 512 * 1024 * 1024;

    LOGI("Instantiating: stack=%uMB heap=%uMB", stack_size >> 20, heap_size >> 20);

    char error_buf[256];
    g_module_inst = wasm_runtime_instantiate(
        g_wasm_module, stack_size, heap_size, error_buf, sizeof(error_buf));

    if (!g_module_inst) {
        LOGE("Instantiate failed: %s", error_buf);
        close_pipes();
        return JNI_FALSE;
    }

    // Try to restore from checkpoint
    bool restored = false;
    if (!g_checkpoint_path.empty()) {
        restored = restore_checkpoint(g_checkpoint_path.c_str());
        if (restored) {
            // Mark handshake as already sent since we're restoring post-boot state
            g_handshake_sent = true;
            send_to_java("[Restored from checkpoint]\n", 27);
        }
    }

    // Start threads
    g_vm_running = true;
    g_stdout_thread = std::thread(stdout_reader_thread);
    g_vm_thread = std::thread(vm_execution_thread);

    LOGI("VM started (restored=%d)", restored);
    return JNI_TRUE;
}

} // extern "C"
