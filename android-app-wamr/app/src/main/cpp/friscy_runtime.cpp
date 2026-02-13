/**
 * friscy Android Runtime — libriscv RISC-V emulator via JNI
 *
 * Phase 2: Interactive Alpine shell with VFS + rootfs.tar + dynamic ELF loading.
 *
 * Architecture:
 *   Java UI <-> JNI <-> libriscv (RISC-V 64) <-> Linux userland (Alpine)
 *
 * Execution model:
 *   1. nativeLoadRootfs() loads a tar archive into in-memory VFS
 *   2. nativeStart() finds the entry binary (/bin/sh), loads it into
 *      a RISC-V machine (with dynamic linker if needed), installs
 *      syscall handlers, and spawns an execution thread
 *   3. The execution thread runs machine.simulate() in a loop:
 *      - When the guest reads stdin and no data is available, the
 *        syscall handler calls machine.stop() and sets waiting_for_stdin
 *      - The execution thread then blocks on a condition variable
 *      - nativeSendInput() pushes data to the stdin buffer and wakes
 *        the execution thread, which calls machine.simulate() again
 *   4. nativeStop() signals the execution thread to exit
 */

#include <jni.h>
#include <android/log.h>

#include <string>
#include <vector>
#include <mutex>
#include <thread>
#include <condition_variable>
#include <cstring>
#include <memory>

#include <libriscv/machine.hpp>

// friscy runtime modules (ported from friscy-standalone)
#include "friscy/android_io.hpp"
#include "friscy/vfs.hpp"
#include "friscy/elf_loader.hpp"
#include "friscy/syscalls.hpp"
#include "friscy/network.hpp"

#define LOG_TAG "friscy"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using Machine = riscv::Machine<riscv::RISCV64>;

// Maximum instructions per simulate() call (16 billion — effectively unlimited)
static constexpr uint64_t MAX_INSTRUCTIONS = 16'000'000'000ULL;

// Syscall bases for libriscv native heap/memory management
static constexpr uint32_t HEAP_SYSCALLS_BASE = 480;
static constexpr uint32_t MEMORY_SYSCALLS_BASE = 485;

// ============================================================================
// Global State
// ============================================================================

static JavaVM* g_jvm = nullptr;
static jobject g_callback_obj = nullptr;
static jmethodID g_on_output_method = nullptr;
static std::mutex g_callback_mutex;

// Machine and VFS (owned by the runtime, accessed from execution thread)
static std::unique_ptr<Machine> g_machine;
static std::unique_ptr<vfs::VirtualFS> g_vfs;
static std::thread g_exec_thread;

// ============================================================================
// JNI Output Callback
// ============================================================================

static void send_to_java(const char* data, size_t len) {
    std::lock_guard<std::mutex> lock(g_callback_mutex);
    if (!g_jvm || !g_callback_obj || !g_on_output_method || len == 0) return;

    JNIEnv* env = nullptr;
    bool attached = false;

    jint res = g_jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (res == JNI_EDETACHED) {
        if (g_jvm->AttachCurrentThread(&env, nullptr) != 0) return;
        attached = true;
    }

    if (env) {
        // Handle potential non-UTF8 data by replacing invalid bytes
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

// libriscv printer callback (raw function pointer — no captures)
static void friscy_printer(const Machine&, const char* data, size_t size) {
    send_to_java(data, size);
}

// ============================================================================
// Execution Thread
// ============================================================================

static void execution_loop() {
    LOGI("Execution thread started");

    while (android_io::running.load()) {
        try {
            // Run until the machine stops (stdin wait, exit, or exception)
            // Retry on page faults by making the faulting page writable
            for (int retries = 0; retries < 8; retries++) {
                try {
                    g_machine->simulate(MAX_INSTRUCTIONS);
                    break;
                } catch (const riscv::MachineException& e) {
                    uint64_t fault_addr = e.data();
                    if (fault_addr != 0 && retries < 7) {
                        uint64_t page = fault_addr & ~0xFFFULL;
                        riscv::PageAttributes attr;
                        attr.read = true;
                        attr.write = true;
                        attr.exec = true;
                        g_machine->memory.set_page_attr(page, 4096, attr);
                        LOGI("Fixed page fault at 0x%llx, retrying",
                             (unsigned long long)fault_addr);
                        continue;
                    }
                    throw;  // Re-throw if we can't fix it
                }
            }

            if (android_io::waiting_for_stdin.load()) {
                // Machine stopped because stdin has no data.
                // Wait for input from the Java side.
                android_io::waiting_for_stdin.store(false);

                std::unique_lock<std::mutex> lock(android_io::stdin_mutex);
                android_io::stdin_cv.wait(lock, [] {
                    return !android_io::stdin_buffer.empty() ||
                           android_io::stdin_eof.load() ||
                           !android_io::running.load();
                });

                if (!android_io::running.load()) {
                    LOGI("Execution thread: stop signal received");
                    break;
                }
                // Data arrived — resume machine (the ecall will re-execute)
            } else {
                // Machine exited normally (sys_exit or completed)
                auto exit_code = g_machine->return_value<int>();
                LOGI("Program exited with code: %d", exit_code);
                std::string msg = "\r\n[friscy] Program exited with code: " +
                                  std::to_string(exit_code) + "\r\n";
                send_to_java(msg.c_str(), msg.size());
                break;
            }
        } catch (const riscv::MachineException& e) {
            LOGE("RISC-V machine exception: %s (data: 0x%lX, pc: 0x%lX)",
                 e.what(), (unsigned long)e.data(),
                 (unsigned long)g_machine->cpu.pc());
            std::string err = "\r\n\033[31m[friscy error] " +
                              std::string(e.what()) + "\033[0m\r\n";
            send_to_java(err.c_str(), err.size());
            break;
        } catch (const std::exception& e) {
            LOGE("Exception: %s", e.what());
            std::string err = "\r\n\033[31m[friscy error] " +
                              std::string(e.what()) + "\033[0m\r\n";
            send_to_java(err.c_str(), err.size());
            break;
        }
    }

    android_io::running.store(false);
    LOGI("Execution thread finished");
}

// ============================================================================
// Helper: resolve a path through VFS symlinks
// ============================================================================

static std::string resolve_vfs_path(vfs::VirtualFS& fs, const std::string& path) {
    // Try the path directly
    auto entry = fs.resolve(path);
    if (entry) return path;

    // Not found
    return "";
}

// Helper: read a file from VFS into a byte vector
static std::vector<uint8_t> read_vfs_file(vfs::VirtualFS& fs, const std::string& path) {
    int fd = fs.open(path, 0);
    if (fd < 0) return {};

    auto entry = fs.get_entry(fd);
    if (!entry) {
        fs.close(fd);
        return {};
    }

    std::vector<uint8_t> data(entry->content.begin(), entry->content.end());
    fs.close(fd);
    return data;
}

// ============================================================================
// JNI Functions
// ============================================================================

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    g_jvm = vm;
    LOGI("friscy runtime loaded (libriscv RISC-V 64)");
    return JNI_VERSION_1_6;
}

JNIEXPORT jboolean JNICALL
Java_com_example_c2wdemo_FriscyRuntime_nativeInit(JNIEnv* env, jclass clazz) {
    LOGI("friscy runtime initialized");
    return JNI_TRUE;
}

/**
 * Load a rootfs tar archive into the in-memory VFS, find the entry binary,
 * create a RISC-V machine with dynamic linking support, and install syscalls.
 *
 * @param tarBytes The rootfs tar archive bytes
 * @param entryPath The entry binary path (e.g., "/bin/sh")
 * @param callback Output callback for terminal output
 * @return true on success
 */
JNIEXPORT jboolean JNICALL
Java_com_example_c2wdemo_FriscyRuntime_nativeLoadRootfs(
    JNIEnv* env, jclass clazz,
    jbyteArray tarBytes, jstring entryPath, jobject callback) {

    // Store callback
    {
        std::lock_guard<std::mutex> lock(g_callback_mutex);
        if (g_callback_obj) {
            env->DeleteGlobalRef(g_callback_obj);
        }
        g_callback_obj = env->NewGlobalRef(callback);
        jclass cls = env->GetObjectClass(callback);
        g_on_output_method = env->GetMethodID(cls, "onOutput", "(Ljava/lang/String;)V");
    }

    // Get tar bytes
    jsize tar_len = env->GetArrayLength(tarBytes);
    LOGI("Loading rootfs tar: %d bytes", tar_len);

    jbyte* tar_data = env->GetByteArrayElements(tarBytes, nullptr);
    if (!tar_data) {
        LOGE("Failed to get tar byte array");
        return JNI_FALSE;
    }

    // Get entry path string
    const char* entry_cstr = env->GetStringUTFChars(entryPath, nullptr);
    std::string entry_path(entry_cstr);
    env->ReleaseStringUTFChars(entryPath, entry_cstr);

    try {
        // Reset state
        android_io::reset();

        // Load tar into VFS
        g_vfs = std::make_unique<vfs::VirtualFS>();
        g_vfs->load_tar(reinterpret_cast<const uint8_t*>(tar_data), tar_len);
        env->ReleaseByteArrayElements(tarBytes, tar_data, JNI_ABORT);

        LOGI("VFS loaded, resolving entry: %s", entry_path.c_str());

        // Resolve the entry path (may be a symlink, e.g. /bin/sh -> /bin/busybox)
        std::string resolved_entry = resolve_vfs_path(*g_vfs, entry_path);
        if (resolved_entry.empty()) {
            LOGE("Entry not found in VFS: %s", entry_path.c_str());
            std::string msg = "[friscy] Entry not found: " + entry_path + "\n";
            send_to_java(msg.c_str(), msg.size());
            return JNI_FALSE;
        }

        // Read the entry binary from VFS
        auto binary = read_vfs_file(*g_vfs, resolved_entry);
        if (binary.empty()) {
            LOGE("Failed to read entry binary: %s", resolved_entry.c_str());
            return JNI_FALSE;
        }
        LOGI("Entry binary: %s (%zu bytes)", resolved_entry.c_str(), binary.size());

        // Parse ELF to check for dynamic linking
        auto exec_info = elf::parse_elf(binary);
        bool use_dynamic_linker = exec_info.is_dynamic &&
                                  !exec_info.interpreter.empty();

        std::vector<uint8_t> interp_binary;
        elf::ElfInfo interp_info{};
        uint64_t interp_base = 0;

        if (use_dynamic_linker) {
            LOGI("Dynamic binary, interpreter: %s", exec_info.interpreter.c_str());

            // Read interpreter from VFS
            std::string interp_resolved = resolve_vfs_path(*g_vfs, exec_info.interpreter);
            if (interp_resolved.empty()) {
                LOGE("Interpreter not found: %s", exec_info.interpreter.c_str());
                return JNI_FALSE;
            }
            interp_binary = read_vfs_file(*g_vfs, interp_resolved);
            if (interp_binary.empty()) {
                LOGE("Failed to read interpreter: %s", interp_resolved.c_str());
                return JNI_FALSE;
            }
            interp_info = elf::parse_elf(interp_binary);
            LOGI("Interpreter: %zu bytes", interp_binary.size());
        } else {
            LOGI("Static binary, no dynamic linker needed");
        }

        // Create the RISC-V machine (512MB arena for container workloads)
        riscv::MachineOptions<riscv::RISCV64> options{
            .memory_max = 512ull << 20,  // 512MB
        };
        g_machine = std::make_unique<Machine>(binary, options);

        // If dynamic, load interpreter and set up auxiliary vector
        if (use_dynamic_linker) {
            // Load interpreter within the 512MB arena (at 384MB mark)
            interp_base = 0x18000000;
            LOGI("Loading interpreter at 0x%lx", (unsigned long)interp_base);

            dynlink::load_elf_segments(*g_machine, interp_binary, interp_base);

            // Calculate interpreter entry point
            uint64_t interp_entry = interp_info.entry_point;
            if (interp_info.type == elf::ET_DYN) {
                auto [lo, hi] = elf::get_load_range(interp_binary);
                interp_entry = interp_info.entry_point - lo + interp_base;
            }

            // Adjust exec_info for PIE main binary
            if (exec_info.type == elf::ET_DYN) {
                uint64_t actual_entry = g_machine->memory.start_address();
                uint64_t exec_base = actual_entry - exec_info.entry_point;
                exec_info.phdr_addr += exec_base;
                exec_info.entry_point = actual_entry;
                LOGI("PIE base: 0x%lx", (unsigned long)exec_base);

                auto [lo, hi] = elf::get_load_range(binary);
                syscalls::g_exec_ctx.exec_base = exec_base + lo;
                auto [rw_lo, rw_hi] = elf::get_writable_range(binary);
                syscalls::g_exec_ctx.exec_rw_start = exec_base + rw_lo;
                syscalls::g_exec_ctx.exec_rw_end = exec_base + rw_hi;
            }

            // Jump to interpreter instead of main binary
            g_machine->cpu.jump(interp_entry);
            LOGI("Interpreter entry: 0x%lx", (unsigned long)interp_entry);
        }

        // Save execution context for execve support
        syscalls::g_exec_ctx.exec_binary = binary;
        syscalls::g_exec_ctx.exec_info = exec_info;
        if (use_dynamic_linker) {
            syscalls::g_exec_ctx.interp_binary = interp_binary;
            syscalls::g_exec_ctx.interp_base = interp_base;
            syscalls::g_exec_ctx.interp_entry = g_machine->cpu.pc();
            syscalls::g_exec_ctx.dynamic = true;
            auto [irw_lo, irw_hi] = elf::get_writable_range(interp_binary);
            syscalls::g_exec_ctx.interp_rw_start = interp_base + irw_lo;
            syscalls::g_exec_ctx.interp_rw_end = interp_base + irw_hi;
        }

        // Install Linux syscall emulation (defaults from libriscv)
        g_machine->setup_linux_syscalls();

        // Set up heap and mmap area (64MB)
        const auto heap_area = g_machine->memory.mmap_allocate(64ULL << 20);
        g_machine->setup_native_heap(HEAP_SYSCALLS_BASE, heap_area, 64ULL << 20);
        syscalls::g_exec_ctx.heap_start = heap_area;
        syscalls::g_exec_ctx.heap_size = 64ULL << 20;
        LOGI("Heap area: 0x%lx (64MB)", (unsigned long)heap_area);

        Machine::setup_native_memory(MEMORY_SYSCALLS_BASE);

        // Install our custom VFS-backed syscall handlers (overrides libriscv defaults)
        syscalls::install_syscalls(*g_machine, *g_vfs);

        // Install network syscall handlers (real POSIX sockets via Android)
        net::install_network_syscalls(*g_machine);

        // Wire up network bridge function pointers for syscalls.hpp
        // (syscalls.hpp uses these to delegate socket I/O without including network.hpp)
        syscalls::net_is_socket_fd = [](int fd) -> bool {
            return net::get_network_ctx().is_socket_fd(fd);
        };
        syscalls::net_get_native_fd = [](int fd) -> int {
            return net::get_network_ctx().get_native_fd(fd);
        };

        // Initialize cooperative thread scheduler (for CLONE_THREAD support)
        syscalls::g_sched = {};
        syscalls::g_fork = {};
        syscalls::g_next_pid = 100;

        // Environment variables
        std::vector<std::string> guest_env = {
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "HOME=/root",
            "USER=root",
            "TERM=xterm-256color",
            "LANG=C.UTF-8",
            "HOSTNAME=friscy",
        };
        syscalls::g_exec_ctx.env = guest_env;

        // Arguments
        std::vector<std::string> guest_args = {entry_path};

        // Set up program stack
        if (use_dynamic_linker) {
            uint64_t stack_top = g_machine->cpu.reg(riscv::REG_SP);
            syscalls::g_exec_ctx.original_stack_top = stack_top;

            uint64_t sp = dynlink::setup_dynamic_stack(
                *g_machine, exec_info, interp_base,
                guest_args, guest_env, stack_top);
            g_machine->cpu.reg(riscv::REG_SP) = sp;
            LOGI("Dynamic stack: SP=0x%lx", (unsigned long)sp);
        } else {
            g_machine->setup_argv(guest_args, guest_env);
        }

        // Route stdout/stderr to Java callback
        g_machine->set_printer(friscy_printer);

        // Log unhandled syscalls (don't crash)
        Machine::on_unhandled_syscall =
            [](Machine& m, size_t syscall_number) {
                LOGI("Unhandled syscall: %zu", syscall_number);
                m.set_result(-38);  // ENOSYS
            };

        LOGI("Machine ready, entry: %s", entry_path.c_str());
        std::string msg = "[friscy] Loaded " + entry_path + " (" +
                          std::to_string(binary.size()) + " bytes)\r\n";
        send_to_java(msg.c_str(), msg.size());

        return JNI_TRUE;

    } catch (const std::exception& e) {
        env->ReleaseByteArrayElements(tarBytes, tar_data, JNI_ABORT);
        LOGE("Failed to load rootfs: %s", e.what());
        std::string err = "[friscy error] " + std::string(e.what()) + "\n";
        send_to_java(err.c_str(), err.size());
        return JNI_FALSE;
    }
}

/**
 * Start the RISC-V execution thread.
 */
JNIEXPORT jboolean JNICALL
Java_com_example_c2wdemo_FriscyRuntime_nativeStart(JNIEnv* env, jclass clazz) {
    if (!g_machine) {
        LOGE("Cannot start: no machine loaded");
        return JNI_FALSE;
    }

    if (android_io::running.load()) {
        LOGI("Already running");
        return JNI_TRUE;
    }

    android_io::running.store(true);
    android_io::waiting_for_stdin.store(false);

    // Join any previous execution thread
    if (g_exec_thread.joinable()) {
        g_exec_thread.join();
    }

    g_exec_thread = std::thread(execution_loop);
    LOGI("Execution thread spawned");

    return JNI_TRUE;
}

/**
 * Send input text to the guest's stdin.
 */
JNIEXPORT void JNICALL
Java_com_example_c2wdemo_FriscyRuntime_nativeSendInput(
    JNIEnv* env, jclass clazz, jstring text) {

    const char* chars = env->GetStringUTFChars(text, nullptr);
    if (!chars) return;

    size_t len = strlen(chars);
    if (len > 0) {
        android_io::push_stdin(
            reinterpret_cast<const uint8_t*>(chars), len);
    }
    env->ReleaseStringUTFChars(text, chars);
}

/**
 * Stop the execution thread.
 */
JNIEXPORT void JNICALL
Java_com_example_c2wdemo_FriscyRuntime_nativeStop(JNIEnv* env, jclass clazz) {
    if (!android_io::running.load()) return;

    LOGI("Stopping execution...");
    android_io::running.store(false);

    // Stop the machine (if it's running simulate())
    if (g_machine) {
        g_machine->stop();
    }

    // Wake up the execution thread if it's waiting for stdin
    android_io::stdin_cv.notify_one();

    // Wait for the execution thread to finish
    if (g_exec_thread.joinable()) {
        g_exec_thread.join();
    }

    LOGI("Execution stopped");
}

/**
 * Destroy the machine and free resources.
 */
JNIEXPORT void JNICALL
Java_com_example_c2wdemo_FriscyRuntime_nativeDestroy(JNIEnv* env, jclass clazz) {
    // Stop first
    Java_com_example_c2wdemo_FriscyRuntime_nativeStop(env, clazz);

    g_machine.reset();
    g_vfs.reset();

    // Clear exec context and thread state
    syscalls::g_exec_ctx = {};
    syscalls::g_sched = {};
    syscalls::g_fork = {};
    syscalls::g_next_pid = 100;
    syscalls::g_mmap_bump = 0;
    syscalls::libriscv_mmap_handler = nullptr;
    syscalls::libriscv_brk_handler = nullptr;
    syscalls::net_is_socket_fd = nullptr;
    syscalls::net_get_native_fd = nullptr;

    // Clear callback
    {
        std::lock_guard<std::mutex> lock(g_callback_mutex);
        if (g_callback_obj) {
            env->DeleteGlobalRef(g_callback_obj);
            g_callback_obj = nullptr;
        }
        g_on_output_method = nullptr;
    }

    LOGI("Runtime destroyed");
}

/**
 * Check if the execution thread is running.
 */
JNIEXPORT jboolean JNICALL
Java_com_example_c2wdemo_FriscyRuntime_nativeIsRunning(JNIEnv* env, jclass clazz) {
    return android_io::running.load() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_example_c2wdemo_FriscyRuntime_nativeGetVersion(JNIEnv* env, jclass clazz) {
    return env->NewStringUTF("friscy | libriscv RISC-V 64 | Phase 7");
}

// --- Snapshot save/restore ---
// Custom format for flat arena mode (libriscv's built-in serialize
// doesn't work with RISCV_FLAT_RW_ARENA).
//
// Format:
//   [magic: 8]  [version: 4]  [regs_size: 4]
//   [arena_size: 8]  [instruction_counter: 8]
//   [registers: regs_size bytes]
//   [arena: arena_size bytes]

static constexpr uint64_t SNAPSHOT_MAGIC = 0x4653524953435946ULL;  // "FYSCRISF"
static constexpr uint32_t SNAPSHOT_VERSION = 1;

JNIEXPORT jboolean JNICALL
Java_com_example_c2wdemo_FriscyRuntime_nativeSaveSnapshot(
    JNIEnv* env, jclass clazz, jstring jpath) {
    if (!g_machine) {
        LOGE("Cannot save snapshot: no machine");
        return JNI_FALSE;
    }

    const char* path = env->GetStringUTFChars(jpath, nullptr);
    LOGI("Saving snapshot to: %s", path);

    FILE* fp = fopen(path, "wb");
    env->ReleaseStringUTFChars(jpath, path);
    if (!fp) {
        LOGE("Cannot open snapshot file for writing");
        return JNI_FALSE;
    }

    auto& cpu = g_machine->cpu;
    auto& mem = g_machine->memory;

    // Header
    uint64_t magic = SNAPSHOT_MAGIC;
    uint32_t version = SNAPSHOT_VERSION;
    uint32_t regs_size = static_cast<uint32_t>(sizeof(cpu.registers()));
    uint64_t arena_size = mem.memory_arena_size();
    uint64_t counter = g_machine->instruction_counter();

    fwrite(&magic, 8, 1, fp);
    fwrite(&version, 4, 1, fp);
    fwrite(&regs_size, 4, 1, fp);
    fwrite(&arena_size, 8, 1, fp);
    fwrite(&counter, 8, 1, fp);

    // CPU registers (POD struct)
    fwrite(&cpu.registers(), regs_size, 1, fp);

    // Flat arena memory
    const void* arena = mem.memory_arena_ptr();
    if (arena && arena_size > 0) {
        size_t written = fwrite(arena, 1, arena_size, fp);
        if (written != arena_size) {
            LOGE("Short write: %zu / %zu", written, (size_t)arena_size);
            fclose(fp);
            return JNI_FALSE;
        }
    }

    fclose(fp);
    LOGI("Snapshot saved: regs=%u arena=%zu", regs_size, (size_t)arena_size);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_example_c2wdemo_FriscyRuntime_nativeRestoreSnapshot(
    JNIEnv* env, jclass clazz, jstring jpath) {
    if (!g_machine) {
        LOGE("Cannot restore snapshot: no machine (call loadRootfs first)");
        return JNI_FALSE;
    }

    const char* path = env->GetStringUTFChars(jpath, nullptr);
    LOGI("Restoring snapshot from: %s", path);

    FILE* fp = fopen(path, "rb");
    env->ReleaseStringUTFChars(jpath, path);
    if (!fp) {
        LOGE("Cannot open snapshot file for reading");
        return JNI_FALSE;
    }

    // Read and validate header
    uint64_t magic = 0;
    uint32_t version = 0, regs_size = 0;
    uint64_t arena_size = 0, counter = 0;

    fread(&magic, 8, 1, fp);
    fread(&version, 4, 1, fp);
    fread(&regs_size, 4, 1, fp);
    fread(&arena_size, 8, 1, fp);
    fread(&counter, 8, 1, fp);

    if (magic != SNAPSHOT_MAGIC) {
        LOGE("Invalid snapshot magic");
        fclose(fp);
        return JNI_FALSE;
    }
    if (version != SNAPSHOT_VERSION) {
        LOGE("Unsupported snapshot version: %u", version);
        fclose(fp);
        return JNI_FALSE;
    }

    auto& cpu = g_machine->cpu;
    auto& mem = g_machine->memory;

    uint32_t expected_regs = static_cast<uint32_t>(sizeof(cpu.registers()));
    uint64_t expected_arena = mem.memory_arena_size();

    if (regs_size != expected_regs) {
        LOGE("Register size mismatch: file=%u expected=%u", regs_size, expected_regs);
        fclose(fp);
        return JNI_FALSE;
    }
    if (arena_size != expected_arena) {
        LOGE("Arena size mismatch: file=%zu expected=%zu",
             (size_t)arena_size, (size_t)expected_arena);
        fclose(fp);
        return JNI_FALSE;
    }

    // Restore CPU registers
    fread(&cpu.registers(), regs_size, 1, fp);

    // Restore arena memory
    void* arena = mem.memory_arena_ptr();
    if (arena && arena_size > 0) {
        size_t read_bytes = fread(arena, 1, arena_size, fp);
        if (read_bytes != arena_size) {
            LOGE("Short read: %zu / %zu", read_bytes, (size_t)arena_size);
            fclose(fp);
            return JNI_FALSE;
        }
    }

    // Restore instruction counter
    g_machine->reset_instruction_counter();
    // Note: we don't restore the exact counter, just reset it

    fclose(fp);
    LOGI("Snapshot restored: regs=%u arena=%zu", regs_size, (size_t)arena_size);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_example_c2wdemo_FriscyRuntime_nativeSetTerminalSize(
    JNIEnv* env, jclass clazz, jint cols, jint rows) {
    android_io::term_cols.store(cols, std::memory_order_relaxed);
    android_io::term_rows.store(rows, std::memory_order_relaxed);
    LOGI("Terminal size: %dx%d", cols, rows);
}

} // extern "C"
