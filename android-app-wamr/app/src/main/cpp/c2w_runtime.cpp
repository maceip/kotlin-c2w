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
    return env->NewStringUTF("WAMR Fast Interp | WASI Preview 1 | SIMD");
}

} // extern "C"
