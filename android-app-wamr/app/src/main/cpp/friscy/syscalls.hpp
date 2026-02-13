// syscalls.hpp - Linux syscall emulation for RISC-V 64-bit
// Implements the minimum viable syscall set for container workloads
//
// Uses libriscv's userdata mechanism to pass VFS to syscall handlers.
// Adapted from friscy-standalone for Android (Emscripten I/O replaced with android_io).
#pragma once

#include <libriscv/machine.hpp>
#include "vfs.hpp"
#include "elf_loader.hpp"
#include <ctime>
#include <cstring>
#include <random>
#include <iostream>
#include <set>
#include <unordered_map>
#include <unistd.h>  // usleep
#include <sys/socket.h>
#include <poll.h>
#include "android_io.hpp"

namespace syscalls {

using Machine = riscv::Machine<riscv::RISCV64>;

// Network bridge function pointers (set by friscy_runtime.cpp after network.hpp is included).
// Avoids including network.hpp here (which would cause macro clashes with fcntl.h).
inline bool (*net_is_socket_fd)(int fd) = nullptr;
inline int  (*net_get_native_fd)(int fd) = nullptr;  // returns native fd or -1

// Cooperative fork state — single-process vfork emulation.
// On clone(): save parent registers, return 0 (child runs).
// On exit_group() in child: restore parent registers, return child PID.
// On wait4(): return saved exit status.
struct ForkState {
    uint64_t regs[32];  // Saved parent registers (x0-x31)
    uint64_t pc;        // Saved parent PC (the ecall instruction)
    int exit_status;    // Child's exit code
    pid_t child_pid;    // PID assigned to child
    bool in_child;      // True while "child" is running
    bool child_reaped;  // True after wait4 has reaped the child
    // Memory snapshots: saved at clone, restored when child exits.
    // With FLAT_RW_ARENA, all arena memory is contiguous so we can
    // save large ranges without worrying about unmapped pages.
    //   1. Data+BRK: exec_rw_start to heap_start (data/BSS + brk region)
    //   2. Interpreter data/BSS (ld-musl state)
    //   3. Stack (return addresses, locals)
    //   4. mmap'd pages: heap_start+heap_size to mmap pointer
    //      (TLS, malloc'd data — musl uses mmap not brk for malloc)
    struct MemRegion {
        std::vector<uint8_t> data;
        uint64_t addr;
        uint64_t size;
    };
    MemRegion exec_data;     // data/BSS + BRK region
    MemRegion interp_data;
    MemRegion stack_data;
    MemRegion mmap_data;     // guest mmap allocations (TLS, malloc)
    // VFS fd snapshot: fds open before fork. On child exit, close any
    // fds not in this set to undo child's dup2/pipe/open changes.
    std::set<int> parent_open_fds;
};
inline ForkState g_fork = {};
inline pid_t g_next_pid = 100;

// Cooperative thread scheduler for CLONE_THREAD.
struct VThread {
    uint64_t regs[32];
    uint64_t pc;
    int tid;
    bool active;
    bool waiting;
    uint64_t futex_addr;
    int32_t futex_val;
    uint64_t clear_child_tid;
    uint64_t syscall_budget;
};
constexpr int MAX_VTHREADS = 8;
constexpr uint64_t THREAD_QUANTUM = 50000;
struct ThreadScheduler {
    VThread threads[MAX_VTHREADS];
    int current = 0;
    int count = 0;

    void init(int main_tid) {
        threads[0].tid = main_tid;
        threads[0].active = true;
        threads[0].waiting = false;
        current = 0;
        count = 1;
    }

    int add_thread(int tid) {
        for (int i = 0; i < MAX_VTHREADS; i++) {
            if (!threads[i].active) {
                threads[i].tid = tid;
                threads[i].active = true;
                threads[i].waiting = false;
                threads[i].clear_child_tid = 0;
                threads[i].syscall_budget = THREAD_QUANTUM;
                count++;
                return i;
            }
        }
        return -1;
    }

    int next_runnable(int skip = -1) {
        for (int i = 0; i < MAX_VTHREADS; i++) {
            if (i != skip && threads[i].active && !threads[i].waiting) {
                return i;
            }
        }
        return -1;
    }

    int wake(uint64_t addr, int max_wake) {
        int woken = 0;
        for (int i = 0; i < MAX_VTHREADS && woken < max_wake; i++) {
            if (threads[i].active && threads[i].waiting && threads[i].futex_addr == addr) {
                threads[i].waiting = false;
                woken++;
            }
        }
        return woken;
    }

    void remove_thread(int tid) {
        for (int i = 0; i < MAX_VTHREADS; i++) {
            if (threads[i].active && threads[i].tid == tid) {
                threads[i].active = false;
                threads[i].waiting = false;
                count--;
                return;
            }
        }
    }
};
inline ThreadScheduler g_sched;

inline void save_thread(Machine& m, VThread& t) {
    for (int i = 0; i < 32; i++) t.regs[i] = m.cpu.reg(i);
    t.pc = m.cpu.pc();
}

inline void restore_thread(Machine& m, VThread& t) {
    for (int i = 0; i < 32; i++) m.cpu.reg(i) = t.regs[i];
    m.cpu.jump(t.pc);
}

inline bool switch_to_thread(Machine& m, int target_idx) {
    if (target_idx < 0 || target_idx == g_sched.current) return false;
    auto& cur = g_sched.threads[g_sched.current];
    auto& tgt = g_sched.threads[target_idx];
    save_thread(m, cur);
    restore_thread(m, tgt);
    g_sched.current = target_idx;
    tgt.syscall_budget = THREAD_QUANTUM;
    return true;
}

inline void maybe_preempt(Machine& m) {
    if (g_sched.count <= 1) return;
    auto& cur = g_sched.threads[g_sched.current];
    if (cur.syscall_budget > 0) {
        cur.syscall_budget--;
        return;
    }
    int next = g_sched.next_runnable(g_sched.current);
    if (next >= 0) {
        static int preempt_count = 0;
        if (++preempt_count <= 20)
            fprintf(stderr, "[preempt] t%d -> t%d (quantum exhausted)\n",
                    g_sched.current, next);
        switch_to_thread(m, next);
    } else {
        cur.syscall_budget = THREAD_QUANTUM;
    }
}

// Execution context saved from initial load — used by execve to
// reload binary segments and set up a fresh stack.
struct ExecContext {
    std::vector<uint8_t> exec_binary;    // Original main executable
    std::vector<uint8_t> interp_binary;  // Original interpreter (ld-musl)
    elf::ElfInfo exec_info;              // Adjusted ELF info (with PIE base)
    uint64_t exec_base = 0;             // PIE base for main executable
    uint64_t exec_rw_start = 0;         // First writable segment of main binary
    uint64_t exec_rw_end = 0;           // End of writable segments of main binary
    uint64_t interp_base = 0;           // Where interpreter was loaded
    uint64_t interp_rw_start = 0;       // First writable segment of interpreter
    uint64_t interp_rw_end = 0;         // End of writable segments of interpreter
    uint64_t interp_entry = 0;          // Interpreter entry point
    uint64_t original_stack_top = 0;    // Stack top from initial setup
    uint64_t heap_start = 0;            // Start of brk heap area
    uint64_t heap_size = 0;             // Size of brk heap area
    uint64_t brk_base = 0;             // Current binary's break base (end of BSS, page-aligned)
    uint64_t brk_current = 0;          // Current break pointer
    bool brk_overridden = false;       // True after execve sets up new brk
    std::vector<std::string> env;        // Environment variables
    bool dynamic = false;                // Using dynamic linker?
};
inline ExecContext g_exec_ctx;

// RISC-V 64-bit syscall numbers (from Linux kernel)
namespace nr {
    constexpr int getcwd        = 17;
    constexpr int dup           = 23;
    constexpr int dup3          = 24;
    constexpr int fcntl         = 25;
    constexpr int ioctl         = 29;
    constexpr int mkdirat       = 34;
    constexpr int unlinkat      = 35;
    constexpr int symlinkat     = 36;
    constexpr int linkat        = 37;
    constexpr int renameat      = 38;
    constexpr int ftruncate     = 46;
    constexpr int faccessat     = 48;
    constexpr int chdir         = 49;
    constexpr int openat        = 56;
    constexpr int close         = 57;
    constexpr int pipe2         = 59;
    constexpr int getdents64    = 61;
    constexpr int lseek         = 62;
    constexpr int read          = 63;
    constexpr int write         = 64;
    constexpr int readv         = 65;
    constexpr int writev        = 66;
    constexpr int pread64       = 67;
    constexpr int pwrite64      = 68;
    constexpr int sendfile      = 71;
    constexpr int ppoll         = 73;
    constexpr int readlinkat    = 78;
    constexpr int newfstatat    = 79;
    constexpr int fstat         = 80;
    constexpr int exit          = 93;
    constexpr int exit_group    = 94;
    constexpr int set_tid_address = 96;
    constexpr int set_robust_list = 99;
    constexpr int clock_gettime = 113;
    constexpr int sigaction     = 134;
    constexpr int sigprocmask   = 135;
    constexpr int getpid        = 172;
    constexpr int getppid       = 173;
    constexpr int getuid        = 174;
    constexpr int geteuid       = 175;
    constexpr int getgid        = 176;
    constexpr int getegid       = 177;
    constexpr int gettid        = 178;
    constexpr int sysinfo       = 179;
    constexpr int brk           = 214;
    constexpr int munmap        = 215;
    constexpr int clone         = 220;
    constexpr int execve        = 221;
    constexpr int mmap          = 222;
    constexpr int mprotect      = 226;
    constexpr int wait4         = 260;
    constexpr int prlimit64     = 261;
    constexpr int eventfd2      = 19;
    constexpr int epoll_create1 = 20;
    constexpr int epoll_ctl     = 21;
    constexpr int epoll_pwait   = 22;
    constexpr int capget        = 90;
    constexpr int futex         = 98;
    constexpr int nanosleep     = 101;
    constexpr int sched_getscheduler = 120;
    constexpr int sched_getparam     = 121;
    constexpr int sched_getaffinity  = 123;
    constexpr int uname         = 160;
    constexpr int getrlimit     = 163;
    constexpr int prctl         = 167;
    constexpr int mremap        = 216;
    constexpr int madvise       = 233;
    constexpr int getrandom     = 278;
    constexpr int flock         = 32;
    constexpr int fchmod        = 52;
    constexpr int fchmodat      = 53;
    constexpr int fchownat      = 54;
    constexpr int pwritev       = 70;
    constexpr int fsync         = 82;
    constexpr int sched_yield   = 124;
    constexpr int kill          = 129;
    constexpr int tkill         = 130;
    constexpr int tgkill        = 131;
    constexpr int sigaltstack   = 132;
    constexpr int rt_sigreturn  = 139;
    constexpr int getresuid     = 148;
    constexpr int getresgid     = 150;
    constexpr int getpgid       = 155;
    constexpr int getgroups     = 158;
    constexpr int umask         = 166;
    constexpr int socketpair    = 199;
    constexpr int sendmsg       = 211;
    constexpr int clock_getres  = 114;
    constexpr int recvmsg       = 212;
    constexpr int membarrier    = 283;
    constexpr int statx         = 291;
    constexpr int close_range   = 436;
    constexpr int rseq          = 293;
    constexpr int io_uring_setup = 425;
    constexpr int faccessat2    = 439;
}

// Linux stat64 structure for RISC-V 64
struct linux_stat64 {
    uint64_t st_dev;
    uint64_t st_ino;
    uint32_t st_mode;
    uint32_t st_nlink;
    uint32_t st_uid;
    uint32_t st_gid;
    uint64_t st_rdev;
    uint64_t __pad1;
    int64_t  st_size;
    int32_t  st_blksize;
    int32_t  __pad2;
    int64_t  st_blocks;
    int64_t  st_atime_sec;
    int64_t  st_atime_nsec;
    int64_t  st_mtime_sec;
    int64_t  st_mtime_nsec;
    int64_t  st_ctime_sec;
    int64_t  st_ctime_nsec;
    int32_t  __reserved_pad[2];
};

// Linux timespec
struct linux_timespec {
    int64_t tv_sec;
    int64_t tv_nsec;
};

// AT_* constants
constexpr int AT_FDCWD = -100;
constexpr int AT_EMPTY_PATH = 0x1000;
constexpr int AT_SYMLINK_NOFOLLOW = 0x100;

// O_* flags (prefixed to avoid macro clashes with Android NDK <fcntl.h>)
namespace oflags {
constexpr int RDONLY = 0;
constexpr int WRONLY = 1;
constexpr int RDWR = 2;
constexpr int CREAT = 0100;
constexpr int EXCL = 0200;
constexpr int TRUNC = 01000;
constexpr int APPEND = 02000;
constexpr int DIRECTORY = 0200000;
constexpr int CLOEXEC = 02000000;
}

// Error codes (negated for syscall return values)
namespace err {
    constexpr int64_t NOENT = -2;
    constexpr int64_t BADF = -9;
    constexpr int64_t ACCES = -13;
    constexpr int64_t EXIST = -17;
    constexpr int64_t NOTDIR = -20;
    constexpr int64_t ISDIR = -21;
    constexpr int64_t INVAL = -22;
    constexpr int64_t NOSYS = -38;
    constexpr int64_t NOTSUP = -95;
}

// Context passed via machine userdata
struct SyscallContext {
    vfs::VirtualFS* fs;
    std::mt19937 rng;

    SyscallContext(vfs::VirtualFS* vfs) : fs(vfs) {
        std::random_device rd;
        rng.seed(rd());
    }
};

// Helper to get context from machine
inline SyscallContext* get_ctx(Machine& m) {
    return m.template get_userdata<SyscallContext>();
}

// Helper to get VFS from machine
inline vfs::VirtualFS& get_fs(Machine& m) {
    return *get_ctx(m)->fs;
}

// Saved references to libriscv's built-in handlers (for forwarding).
inline Machine::syscall_t libriscv_mmap_handler = nullptr;
inline Machine::syscall_t libriscv_brk_handler = nullptr;

// Mmap bump allocator pointer (reset on each machine init).
inline uint64_t g_mmap_bump = 0;

// Syscall handlers (static functions, no captures)
namespace handlers {

// Forward declaration — sys_exit has the fork parent restore logic
static void sys_exit(Machine& m);

// exit_group — terminate all threads and stop the machine
static void sys_exit_group(Machine& m) {
    int exit_code = m.template sysarg<int>(0);
    fprintf(stderr, "[exit_group] code=%d from thread t%d (tid=%d)\n",
            exit_code, g_sched.current,
            g_sched.count > 0 ? g_sched.threads[g_sched.current].tid : -1);

    if (g_fork.in_child) {
        sys_exit(m);
        return;
    }

    for (int i = 0; i < MAX_VTHREADS; i++) {
        g_sched.threads[i].active = false;
        g_sched.threads[i].waiting = false;
    }
    g_sched.count = 0;

    m.stop();
    m.set_result(exit_code);
}

static void sys_exit(Machine& m) {
    // If a cooperative thread is exiting (not the main thread or a fork child),
    // remove it from the scheduler and switch to another thread.
    if (g_sched.count > 1 && g_sched.current != 0) {
        int exiting = g_sched.current;
        auto& t = g_sched.threads[exiting];
        int exit_code = m.template sysarg<int>(0);
        fprintf(stderr, "[exit] thread tid=%d exit_code=%d, switching\n", t.tid, exit_code);

        if (t.clear_child_tid != 0) {
            m.memory.template write<int32_t>(t.clear_child_tid, 0);
            g_sched.wake(t.clear_child_tid, 1);
            fprintf(stderr, "[exit] cleared child_tid at 0x%lx\n", (long)t.clear_child_tid);
        }

        t.active = false;
        t.waiting = false;
        g_sched.count--;

        int next = g_sched.next_runnable(exiting);
        if (next >= 0) {
            restore_thread(m, g_sched.threads[next]);
            g_sched.current = next;
            return;
        }
    }

    if (g_fork.in_child) {
        // "Child" is exiting — restore parent state
        g_fork.exit_status = m.template sysarg<int>(0);
        g_fork.in_child = false;

        // CRITICAL: Fix page permissions BEFORE restoring memory.
        // The parent's initial RELRO made data pages read-only. If we
        // try to memcpy to those pages first, the write triggers a
        // protection fault that propagates out of resume(), leaving
        // the state half-restored and causing the parent to crash.
        auto fix_perms = [&](uint64_t addr, uint64_t size) {
            if (addr > 0 && size > 0) {
                riscv::PageAttributes attr;
                attr.read = true;
                attr.write = true;
                attr.exec = true;
                m.memory.set_page_attr(addr, size, attr);
            }
        };
        // Fix data/BSS + BRK region (includes RELRO pages)
        {
            uint64_t save_end = (g_exec_ctx.heap_start > g_exec_ctx.exec_rw_end)
                              ? g_exec_ctx.heap_start : g_exec_ctx.exec_rw_end;
            fix_perms(g_exec_ctx.exec_rw_start,
                      save_end - g_exec_ctx.exec_rw_start);
        }
        // Fix interpreter data
        fix_perms(g_exec_ctx.interp_rw_start,
                  g_exec_ctx.interp_rw_end - g_exec_ctx.interp_rw_start);
        // Fix mmap region
        if (g_fork.mmap_data.size > 0) {
            fix_perms(g_fork.mmap_data.addr, g_fork.mmap_data.size);
        }
        // Fix stack
        {
            uint64_t sp = g_fork.regs[2];  // Use saved SP, not current
            fix_perms(sp, g_exec_ctx.original_stack_top - sp);
        }

        // Now restore parent memory (data/BSS, interpreter, stack, mmap)
        auto restore = [&](ForkState::MemRegion& r) {
            if (!r.data.empty()) {
                m.memory.memcpy(r.addr, r.data.data(), r.size);
                r.data.clear();
                r.data.shrink_to_fit();
            }
        };
        restore(g_fork.exec_data);
        restore(g_fork.interp_data);
        restore(g_fork.stack_data);
        restore(g_fork.mmap_data);

        // Restore VFS fd state: close any fds the child opened/dup2'd
        // that the parent didn't have. This undoes pipe redirections
        // (e.g. dup2(pipe_fd, 1)) so parent's stdout goes to terminal.
        {
            auto& fs = get_fs(m);
            auto current_fds = fs.get_open_fds();
            for (int fd : current_fds) {
                if (g_fork.parent_open_fds.count(fd) == 0) {
                    fs.close(fd);
                }
            }
            g_fork.parent_open_fds.clear();
        }

        // Restore parent registers (x0-x31)
        for (int i = 1; i < 32; i++) {  // Skip x0 (hardwired zero)
            m.cpu.reg(i) = g_fork.regs[i];
        }
        // Resume parent at instruction after the clone ecall
        m.cpu.jump(g_fork.pc);
        // Parent sees child PID as clone() return value
        m.set_result(g_fork.child_pid);
        return;
    }
    int exit_code = m.template sysarg<int>(0);
    fprintf(stderr, "[exit] main thread exit code=%d\n", exit_code);
    m.stop();
    m.set_result(exit_code);
}

// clone — cooperative vfork emulation + thread creation.
static void sys_clone(Machine& m) {
    uint64_t flags = m.sysarg(0);

    constexpr uint64_t F_CLONE_VM     = 0x00000100;
    constexpr uint64_t F_CLONE_THREAD = 0x00010000;
    constexpr uint64_t F_CLONE_VFORK  = 0x00004000;

    if ((flags & F_CLONE_THREAD) || ((flags & F_CLONE_VM) && !(flags & F_CLONE_VFORK))) {
        // Thread creation with cooperative scheduling.
        constexpr uint64_t F_CLONE_PARENT_SETTID  = 0x00100000;
        constexpr uint64_t F_CLONE_CHILD_CLEARTID = 0x00200000;
        constexpr uint64_t F_CLONE_SETTLS         = 0x00080000;

        int tid = g_next_pid++;
        auto child_stack = m.sysarg(1);

        if (flags & F_CLONE_PARENT_SETTID) {
            auto parent_tidptr = m.sysarg(2);
            if (parent_tidptr != 0) {
                m.memory.template write<int32_t>(parent_tidptr, tid);
            }
        }

        if (g_sched.count == 0) {
            g_sched.init(g_next_pid - 2);
        }

        int child_idx = g_sched.add_thread(tid);
        if (child_idx < 0) {
            fprintf(stderr, "[clone] thread slots full, faking tid=%d\n", tid);
            m.set_result(tid);
            return;
        }

        int parent_idx = g_sched.current;
        save_thread(m, g_sched.threads[parent_idx]);
        g_sched.threads[parent_idx].regs[10] = (uint64_t)tid;

        m.cpu.reg(riscv::REG_SP) = child_stack;
        m.set_result(0);

        if (flags & F_CLONE_SETTLS) {
            auto tls = m.sysarg(3);
            m.cpu.reg(4) = tls;  // tp register = x4
        }

        if (flags & F_CLONE_CHILD_CLEARTID) {
            auto child_tidptr = m.sysarg(4);
            g_sched.threads[child_idx].clear_child_tid = child_tidptr;
        }

        g_sched.current = child_idx;
        g_sched.threads[child_idx].pc = m.cpu.pc();

        static int thread_count = 0;
        if (++thread_count <= 10)
            fprintf(stderr, "[clone] thread #%d cooperative, tid=%d stack=0x%lx\n",
                    thread_count, tid, (long)child_stack);
        return;
    }

    if (g_fork.in_child) {
        m.set_result(-11);  // -EAGAIN
        return;
    }

    fprintf(stderr, "[clone] fork flags=0x%lx\n", (long)flags);

    // Save parent registers
    for (int i = 0; i < 32; i++) {
        g_fork.regs[i] = m.cpu.reg(i);
    }
    g_fork.pc = m.cpu.pc();  // Already past the ecall
    g_fork.child_pid = g_next_pid++;
    g_fork.exit_status = 0;

    // Save parent memory BEFORE setting in_child.
    // If memcpy_out throws (e.g. protection fault on RELRO pages),
    // the exception propagates to the retry loop. On retry, the ecall
    // re-enters this handler. With in_child still false, we retry the
    // save (now with the faulting page made RWX by the retry handler).
    //
    // Memory layout (for PIE at 0x40000):
    //   exec_rw_start..exec_rw_end : data/BSS (globals, GOT, .bss)
    //   exec_rw_end..heap_start   : BRK region (musl small allocs)
    //   heap_start..+heap_size    : native heap (from mmap_allocate)
    //   heap_start+heap_size..mmap: guest mmap (TLS, libc malloc pages)
    //
    // Region 1: main binary writable segments + BRK heap.
    // Covers data/BSS/GOT (exec_rw_start..exec_rw_end) and the BRK
    // region (exec_rw_end..heap_start) where musl puts small allocs
    // (shell variables like $PWD live here).
    {
        uint64_t save_start = g_exec_ctx.exec_rw_start;
        uint64_t save_end = (g_exec_ctx.heap_start > g_exec_ctx.exec_rw_end)
                          ? g_exec_ctx.heap_start : g_exec_ctx.exec_rw_end;
        if (save_start > 0 && save_end > save_start) {
            // BRK pages may not have read attrs yet — make them readable.
            riscv::PageAttributes attr;
            attr.read = true; attr.write = true; attr.exec = true;
            m.memory.set_page_attr(save_start, save_end - save_start, attr);

            auto& r = g_fork.exec_data;
            r.addr = save_start;
            r.size = save_end - save_start;
            r.data.resize(r.size);
            m.memory.memcpy_out(r.data.data(), r.addr, r.size);
        }
    }

    // Region 2: interpreter writable segments
    if (g_exec_ctx.interp_rw_start > 0 && g_exec_ctx.interp_rw_end > g_exec_ctx.interp_rw_start) {
        auto& r = g_fork.interp_data;
        r.addr = g_exec_ctx.interp_rw_start;
        r.size = g_exec_ctx.interp_rw_end - g_exec_ctx.interp_rw_start;
        r.data.resize(r.size);
        m.memory.memcpy_out(r.data.data(), r.addr, r.size);
    }

    // Region 3: stack (SP to stack top)
    {
        uint64_t sp = m.cpu.reg(riscv::REG_SP);
        uint64_t stack_top = g_exec_ctx.original_stack_top;
        auto& r = g_fork.stack_data;
        r.addr = sp;
        r.size = stack_top - sp;
        r.data.resize(r.size);
        m.memory.memcpy_out(r.data.data(), r.addr, r.size);
    }

    // Region 4: guest mmap allocations (TLS, libc malloc pages)
    // musl uses mmap (not brk) for malloc. Guest mmaps are placed
    // after our native heap area. Probe mmap_allocate(0) to find
    // the current allocation frontier.
    if (g_exec_ctx.heap_start > 0 && g_exec_ctx.heap_size > 0) {
        uint64_t mmap_region_start = g_exec_ctx.heap_start + g_exec_ctx.heap_size;
        uint64_t mmap_frontier = m.memory.mmap_allocate(0);
        if (mmap_frontier > mmap_region_start) {
            auto& r = g_fork.mmap_data;
            r.addr = mmap_region_start;
            r.size = mmap_frontier - mmap_region_start;
            r.data.resize(r.size);
            m.memory.memcpy_out(r.data.data(), r.addr, r.size);
        }
    }

    // Save VFS open fd set so child's dup2/pipe/open can be undone
    g_fork.parent_open_fds = get_fs(m).get_open_fds();

    // Only set in_child AFTER all saves succeed.
    // This way if memcpy_out throws, the retry will re-enter clone
    // with in_child still false, allowing the save to be retried.
    g_fork.in_child = true;
    g_fork.child_reaped = false;

    // Return 0 = "you are the child"
    m.set_result(0);
}

// wait4 — return status of the cooperatively-forked child.
// In our model the child has always already exited by the time
// the parent resumes, so this never blocks.
static void sys_wait4(Machine& m) {
    // After the first reap, return ECHILD (no more children).
    // This prevents infinite loops in shells that call waitpid
    // until all children are reaped.
    if (g_fork.child_reaped || g_fork.child_pid == 0) {
        m.set_result(-10);  // -ECHILD
        return;
    }

    auto wstatus_addr = m.sysarg(1);
    if (wstatus_addr != 0) {
        // Encode in wait status format: WEXITSTATUS = (status & 0xff) << 8
        int32_t wstatus = (g_fork.exit_status & 0xff) << 8;
        m.memory.template write<int32_t>(wstatus_addr, wstatus);
    }
    g_fork.child_reaped = true;
    m.set_result(g_fork.child_pid);
}

// Helper: resolve a VFS path through symlinks (up to 10 levels).
static std::string resolve_path(vfs::VirtualFS& fs, const std::string& path) {
    std::string resolved = path;
    for (int i = 0; i < 10; i++) {
        vfs::Entry entry;
        if (!fs.stat(resolved, entry)) return "";  // not found
        if (entry.type != vfs::FileType::Symlink) break;
        char target[256];
        ssize_t n = fs.readlink(resolved, target, sizeof(target));
        if (n <= 0) break;
        std::string link(target, n);
        if (link[0] != '/') {
            auto slash = resolved.rfind('/');
            if (slash != std::string::npos)
                link = resolved.substr(0, slash + 1) + link;
        }
        resolved = link;
    }
    return resolved;
}

// Helper: read a file from VFS into a byte vector.
static std::vector<uint8_t> read_vfs_file(vfs::VirtualFS& fs, const std::string& path) {
    int fd = fs.open(path, 0 /*O_RDONLY*/);
    if (fd < 0) return {};
    std::vector<uint8_t> data;
    char buf[4096];
    ssize_t n;
    while ((n = fs.read(fd, buf, sizeof(buf))) > 0) {
        data.insert(data.end(), buf, buf + n);
    }
    fs.close(fd);
    return data;
}

// Helper: search PATH for a command name, return full path or empty.
static std::string search_path(vfs::VirtualFS& fs, const std::string& cmd) {
    if (cmd.empty() || cmd[0] == '/') return cmd;
    std::string path_val = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin";
    for (auto& e : g_exec_ctx.env) {
        if (e.substr(0, 5) == "PATH=") { path_val = e.substr(5); break; }
    }
    size_t pos = 0;
    while (pos < path_val.size()) {
        size_t colon = path_val.find(':', pos);
        std::string dir = (colon == std::string::npos)
            ? path_val.substr(pos) : path_val.substr(pos, colon - pos);
        std::string candidate = dir + "/" + cmd;
        std::string resolved = resolve_path(fs, candidate);
        if (!resolved.empty()) {
            vfs::Entry e2;
            if (fs.stat(resolved, e2) && e2.type == vfs::FileType::Regular)
                return candidate;  // return unresolved (let caller resolve)
        }
        pos = (colon == std::string::npos) ? path_val.size() : colon + 1;
    }
    return "";
}

// execve — replace current "process" with a new program.
// Supports:
//   - Busybox applets (same binary, just new argv)
//   - Arbitrary ELF binaries (loads new code + interpreter)
//   - Shebang scripts (#!/path/to/interpreter)
static void sys_execve(Machine& m) {
    auto path_addr = m.sysarg(0);
    auto argv_addr = m.sysarg(1);

    if (!g_exec_ctx.dynamic || g_exec_ctx.exec_binary.empty()) {
        m.set_result(-38);  // -ENOSYS
        return;
    }

    // Read target path
    std::string path;
    try {
        path = m.memory.memstring(path_addr);
    } catch (...) {
        m.set_result(-14);  // -EFAULT
        return;
    }

    auto& fs = get_fs(m);

    // Resolve symlinks
    std::string resolved = resolve_path(fs, path);
    if (resolved.empty()) {
        m.set_result(-2);  // -ENOENT
        return;
    }

    // Read argv from guest memory
    std::vector<std::string> args;
    try {
        for (int i = 0; i < 256; i++) {
            uint64_t ptr = m.memory.template read<uint64_t>(argv_addr + i * 8);
            if (ptr == 0) break;
            args.push_back(m.memory.memstring(ptr));
        }
    } catch (...) {
        m.set_result(-14);  // -EFAULT
        return;
    }

    if (args.empty()) {
        args.push_back(path);
    }

    // Shebang handling: if the target file starts with "#!", parse the
    // interpreter line and rewrite args as: interpreter [arg] script argv[1..]
    {
        int fd = fs.open(resolved, 0 /*O_RDONLY*/);
        if (fd >= 0) {
            char hdr[256];
            ssize_t n = fs.read(fd, hdr, sizeof(hdr) - 1);
            fs.close(fd);
            if (n >= 4 && hdr[0] == '#' && hdr[1] == '!') {
                hdr[n] = '\0';
                char* eol = std::strchr(hdr + 2, '\n');
                if (eol) *eol = '\0';
                char* interp = hdr + 2;
                while (*interp == ' ' || *interp == '\t') interp++;
                std::string interp_path;
                std::string interp_arg;
                char* space = std::strchr(interp, ' ');
                if (!space) space = std::strchr(interp, '\t');
                if (space) {
                    interp_path = std::string(interp, space);
                    char* a = space + 1;
                    while (*a == ' ' || *a == '\t') a++;
                    char* end = a + std::strlen(a) - 1;
                    while (end > a && (*end == ' ' || *end == '\t' || *end == '\r')) *end-- = '\0';
                    if (*a) interp_arg = a;
                } else {
                    char* end = interp + std::strlen(interp) - 1;
                    while (end > interp && (*end == ' ' || *end == '\t' || *end == '\r')) *end-- = '\0';
                    interp_path = interp;
                }
                std::vector<std::string> new_args;
                new_args.push_back(interp_path);
                if (!interp_arg.empty()) new_args.push_back(interp_arg);
                new_args.push_back(resolved);
                for (size_t i = 1; i < args.size(); i++)
                    new_args.push_back(args[i]);
                args = std::move(new_args);

                // Handle /usr/bin/env: resolve command via PATH
                if (interp_path == "/usr/bin/env" && args.size() >= 2) {
                    std::string cmd = args[1];
                    std::string found = search_path(fs, cmd);
                    if (!found.empty()) {
                        args[0] = found;
                        args.erase(args.begin() + 1);
                        resolved = resolve_path(fs, found);
                    }
                } else {
                    resolved = resolve_path(fs, interp_path);
                }
                if (resolved.empty()) {
                    m.set_result(-2);  // -ENOENT
                    return;
                }
            }
        }
    }

    // Read the target binary from VFS to check if it's a different ELF
    auto new_binary = read_vfs_file(fs, resolved);
    bool is_new_elf = false;

    if (new_binary.size() >= sizeof(elf::Elf64_Ehdr)) {
        const auto* ehdr = reinterpret_cast<const elf::Elf64_Ehdr*>(new_binary.data());
        if (ehdr->e_ident[0] == 0x7f && ehdr->e_ident[1] == 'E' &&
            ehdr->e_ident[2] == 'L' && ehdr->e_ident[3] == 'F' &&
            ehdr->e_machine == elf::EM_RISCV) {
            is_new_elf = true;
        }
    }

    if (is_new_elf && new_binary != g_exec_ctx.exec_binary) {
        // ---- Loading a NEW binary (e.g. /usr/bin/node) ----
        try {
            auto exec_info = elf::parse_elf(new_binary);
            std::cout << "[friscy] execve: loading new binary " << resolved
                      << " (" << new_binary.size() << " bytes)\n";

            // Check if new binary fits in arena
            constexpr uint64_t ARENA_SIZE = 1ULL << riscv::encompassing_Nbit_arena;
            auto [new_lo, new_hi] = elf::get_load_range(new_binary);
            uint64_t exec_base = 0x40000;
            uint64_t load_end = exec_base + new_hi - new_lo;
            std::cerr << "[execve] ELF load range: lo=0x" << std::hex << new_lo
                      << " hi=0x" << new_hi << " load_end=0x" << load_end
                      << " arena=0x" << ARENA_SIZE << std::dec << "\n";

            if (load_end >= ARENA_SIZE) {
                std::cerr << "[execve] ERROR: binary too large for arena!\n";
                m.set_result(-12);  // -ENOMEM
                return;
            }

            // Make arena writable for loading
            {
                riscv::PageAttributes rw;
                rw.read = true; rw.write = true;
                m.memory.set_page_attr(exec_base, load_end - exec_base, rw);
            }
            // Also make old binary range writable
            {
                auto [old_lo, old_hi] = elf::get_load_range(g_exec_ctx.exec_binary);
                uint64_t old_start = g_exec_ctx.exec_base;
                uint64_t old_end = old_start + old_hi;
                riscv::PageAttributes rw;
                rw.read = true; rw.write = true;
                m.memory.set_page_attr(old_start, old_end - old_start, rw);
            }

            // Load new main binary segments at PIE base
            if (exec_info.type == elf::ET_DYN) {
                auto [lo, hi] = elf::get_load_range(new_binary);
                exec_base = 0x40000;
                dynlink::load_elf_segments(m, new_binary, exec_base);
                exec_info.phdr_addr += (exec_base - lo);
                exec_info.entry_point += (exec_base - lo);
                g_exec_ctx.exec_base = exec_base;
                auto [rw_lo, rw_hi] = elf::get_writable_range(new_binary);
                g_exec_ctx.exec_rw_start = (exec_base - lo) + rw_lo;
                g_exec_ctx.exec_rw_end = (exec_base - lo) + rw_hi;
            } else {
                dynlink::load_elf_segments(m, new_binary, 0);
                auto [rw_lo, rw_hi] = elf::get_writable_range(new_binary);
                g_exec_ctx.exec_rw_start = rw_lo;
                g_exec_ctx.exec_rw_end = rw_hi;
            }

            uint64_t interp_base = g_exec_ctx.interp_base;
            uint64_t interp_entry = g_exec_ctx.interp_entry;

            if (exec_info.is_dynamic && !exec_info.interpreter.empty()) {
                std::string interp_resolved = resolve_path(fs, exec_info.interpreter);
                auto interp_binary = read_vfs_file(fs, interp_resolved);
                if (interp_binary.empty()) {
                    std::cerr << "[friscy] execve: interpreter not found: "
                              << exec_info.interpreter << "\n";
                    m.set_result(-2);
                    return;
                }

                // Make old interpreter pages writable before overwriting
                {
                    auto [ilo, ihi] = elf::get_load_range(g_exec_ctx.interp_binary);
                    riscv::PageAttributes rw;
                    rw.read = true; rw.write = true;
                    m.memory.set_page_attr(interp_base, ihi - ilo, rw);
                }

                dynlink::load_elf_segments(m, interp_binary, interp_base);

                auto interp_info = elf::parse_elf(interp_binary);
                if (interp_info.type == elf::ET_DYN) {
                    auto [lo, hi] = elf::get_load_range(interp_binary);
                    interp_entry = interp_info.entry_point - lo + interp_base;
                } else {
                    interp_entry = interp_info.entry_point;
                }

                auto [irw_lo, irw_hi] = elf::get_writable_range(interp_binary);
                g_exec_ctx.interp_rw_start = interp_base + irw_lo;
                g_exec_ctx.interp_rw_end = interp_base + irw_hi;
                g_exec_ctx.interp_binary = std::move(interp_binary);
                g_exec_ctx.interp_entry = interp_entry;
            }

            g_exec_ctx.exec_binary = std::move(new_binary);
            g_exec_ctx.exec_info = exec_info;

            // Reset memory layout after loading new binary
            {
                uint64_t max_end = load_end;
                if (exec_info.is_dynamic) {
                    auto [ilo, ihi] = elf::get_load_range(g_exec_ctx.interp_binary);
                    uint64_t interp_end2 = interp_base + (ihi - ilo);
                    if (interp_end2 > max_end) max_end = interp_end2;
                }

                uint64_t new_brk_base = (max_end + 4095) & ~4095ULL;
                g_exec_ctx.brk_base = new_brk_base;
                g_exec_ctx.brk_current = new_brk_base;
                g_exec_ctx.brk_overridden = true;

                constexpr uint64_t BRK_MAX = 16ULL << 20;
                riscv::PageAttributes rw;
                rw.read = true; rw.write = true;
                m.memory.set_page_attr(new_brk_base, BRK_MAX, rw);

                uint64_t new_mmap_start = new_brk_base + BRK_MAX;
                if (m.memory.mmap_address() < new_mmap_start) {
                    m.memory.mmap_address() = new_mmap_start;
                }

                std::cerr << "[execve] memory layout reset: brk=0x" << std::hex
                          << new_brk_base << " mmap=0x"
                          << m.memory.mmap_address() << std::dec << "\n";
            }

            // Relocate stack if new binary overlaps the old stack
            uint64_t new_stack_top = g_exec_ctx.original_stack_top;
            if (new_stack_top < load_end + 0x10000) {
                new_stack_top = interp_base - 0x1000;
                std::cerr << "[execve] relocating stack: 0x" << std::hex
                          << g_exec_ctx.original_stack_top << " -> 0x"
                          << new_stack_top << std::dec << "\n";
                riscv::PageAttributes rw;
                rw.read = true; rw.write = true;
                m.memory.set_page_attr(new_stack_top - 0x10000, 0x10000, rw);
                g_exec_ctx.original_stack_top = new_stack_top;
            }

            uint64_t sp = dynlink::setup_dynamic_stack(
                m, exec_info, interp_base, args,
                g_exec_ctx.env, new_stack_top);

            for (int i = 1; i < 32; i++) m.cpu.reg(i) = 0;
            m.cpu.reg(riscv::REG_SP) = sp;
            m.cpu.jump(exec_info.is_dynamic ? interp_entry : exec_info.entry_point);

            std::cout << "[friscy] execve: jumping to 0x" << std::hex
                      << (exec_info.is_dynamic ? interp_entry : exec_info.entry_point)
                      << std::dec << "\n";
            return;
        } catch (const riscv::MachineException& e) {
            std::cerr << "[friscy] execve: MachineException loading " << resolved
                      << ": " << e.what()
                      << " (data=0x" << std::hex << e.data() << std::dec
                      << ", type=" << e.type() << ")\n";
            m.set_result(-8);  // -ENOEXEC
            return;
        } catch (const std::exception& e) {
            std::cerr << "[friscy] execve: failed to load " << resolved
                      << ": " << e.what() << "\n";
            m.set_result(-8);  // -ENOEXEC
            return;
        }
    }

    // ---- Same binary (busybox applet) or non-ELF ----
    // Just set up fresh stack with new argv and re-enter the dynamic linker.

    uint64_t sp = dynlink::setup_dynamic_stack(
        m, g_exec_ctx.exec_info, g_exec_ctx.interp_base,
        args, g_exec_ctx.env, g_exec_ctx.original_stack_top);

    for (int i = 1; i < 32; i++) m.cpu.reg(i) = 0;
    m.cpu.reg(riscv::REG_SP) = sp;
    m.cpu.jump(g_exec_ctx.interp_entry);
}

static void sys_openat(Machine& m) {
    auto& fs = get_fs(m);
    int dirfd = m.template sysarg<int>(0);
    auto path_addr = m.sysarg(1);
    int flags = m.template sysarg<int>(2);
    if (dirfd != AT_FDCWD) {
        m.set_result(err::NOTSUP);
        return;
    }

    std::string path;
    try {
        path = m.memory.memstring(path_addr);
    } catch (...) {
        m.set_result(err::INVAL);
        return;
    }

    int fd = (flags & oflags::DIRECTORY) ? fs.opendir(path) : fs.open(path, flags);
    fprintf(stderr, "[open] %s => fd=%d flags=0x%x\n", path.c_str(), fd, flags);
    m.set_result(fd);
}

static void sys_close(Machine& m) {
    get_fs(m).close(m.template sysarg<int>(0));
    m.set_result(0);
}

static void sys_read(Machine& m) {
    auto& fs = get_fs(m);
    int fd = m.template sysarg<int>(0);
    auto buf_addr = m.sysarg(1);
    size_t count = m.sysarg(2);

    // If fd has been redirected (e.g. dup2'd to a pipe), use VFS
    if (fd == 0 && fs.is_open(fd)) {
        std::vector<uint8_t> buf(count);
        ssize_t n = fs.read(fd, buf.data(), count);
        if (n > 0) {
            m.memory.memcpy(buf_addr, buf.data(), n);
        }
        m.set_result(n);
        return;
    }

    if (fd == 0) {
        // Try non-blocking read from Android stdin buffer
        std::vector<uint8_t> tmp(count);
        int bytes_read = android_io::try_read_stdin(tmp.data(), count);
        if (bytes_read >= 0) {
            if (bytes_read > 0) {
                m.memory.memcpy(buf_addr, tmp.data(), bytes_read);
            }
            m.set_result(bytes_read);
        } else {
            // No data available — rewind PC to the ecall instruction
            // and stop the machine. When resumed, the ecall will
            // re-execute this syscall handler, retrying the read.
            android_io::waiting_for_stdin.store(true);
            m.cpu.increment_pc(-4);  // Rewind past ecall (4 bytes)
            m.stop();
        }
        return;
    }

    // Socket FDs: delegate to recv
    if (net_is_socket_fd && net_is_socket_fd(fd)) {
        int native_fd = net_get_native_fd ? net_get_native_fd(fd) : -1;
        if (native_fd >= 0) {
            std::vector<uint8_t> buf(count);
            ssize_t n = ::recv(native_fd, buf.data(), count, 0);
            if (n > 0) {
                m.memory.memcpy(buf_addr, buf.data(), n);
            }
            m.set_result(n >= 0 ? n : -errno);
            return;
        }
    }

    std::vector<uint8_t> buf(count);
    ssize_t n = fs.read(fd, buf.data(), count);
    if (n > 0) {
        m.memory.memcpy(buf_addr, buf.data(), n);
    }
    m.set_result(n);
}

static void sys_write(Machine& m) {
    auto& fs = get_fs(m);
    int fd = m.template sysarg<int>(0);
    auto buf_addr = m.sysarg(1);
    size_t count = m.sysarg(2);

    // Check VFS first — fd 1/2 may have been dup2'd to a pipe/file
    if (fs.is_open(fd)) {
        std::vector<uint8_t> buf(count);
        m.memory.memcpy_out(buf.data(), buf_addr, count);
        ssize_t n = fs.write(fd, buf.data(), count);
        m.set_result(n);
        return;
    }

    // Default stdout/stderr go to host terminal
    if (fd == 1 || fd == 2) {
        try {
            auto view = m.memory.memview(buf_addr, count);
            m.print(reinterpret_cast<const char*>(view.data()), count);
            m.set_result(count);
        } catch (...) {
            m.set_result(err::INVAL);
        }
        return;
    }

    // Socket FDs: delegate to send
    if (net_is_socket_fd && net_is_socket_fd(fd)) {
        int native_fd = net_get_native_fd ? net_get_native_fd(fd) : -1;
        if (native_fd >= 0) {
            std::vector<uint8_t> buf(count);
            m.memory.memcpy_out(buf.data(), buf_addr, count);
            ssize_t n = ::send(native_fd, buf.data(), count, 0);
            m.set_result(n >= 0 ? n : -errno);
            return;
        }
    }

    m.set_result(err::BADF);
}

static void sys_writev(Machine& m) {
    auto& fs = get_fs(m);
    int fd = m.template sysarg<int>(0);
    auto iov_addr = m.sysarg(1);
    int iovcnt = m.template sysarg<int>(2);

    // Check VFS first — fd 1/2 may have been dup2'd to a pipe/file
    if (fs.is_open(fd)) {
        size_t total = 0;
        for (int i = 0; i < iovcnt; i++) {
            uint64_t base = m.memory.template read<uint64_t>(iov_addr + i * 16);
            uint64_t len = m.memory.template read<uint64_t>(iov_addr + i * 16 + 8);
            if (len > 0) {
                std::vector<uint8_t> buf(len);
                m.memory.memcpy_out(buf.data(), base, len);
                ssize_t n = fs.write(fd, buf.data(), len);
                if (n < 0) {
                    m.set_result(total > 0 ? (int64_t)total : n);
                    return;
                }
                total += n;
            }
        }
        m.set_result(total);
        return;
    }

    // Default stdout/stderr go to host terminal
    if (fd == 1 || fd == 2) {
        size_t total = 0;
        for (int i = 0; i < iovcnt; i++) {
            uint64_t base = m.memory.template read<uint64_t>(iov_addr + i * 16);
            uint64_t len = m.memory.template read<uint64_t>(iov_addr + i * 16 + 8);
            if (len > 0) {
                auto view = m.memory.memview(base, len);
                m.print(reinterpret_cast<const char*>(view.data()), len);
                total += len;
            }
        }
        m.set_result(total);
        return;
    }

    // Socket FDs: gather iov and send
    if (net_is_socket_fd && net_is_socket_fd(fd)) {
        int native_fd = net_get_native_fd ? net_get_native_fd(fd) : -1;
        if (native_fd >= 0) {
            size_t total = 0;
            for (int i = 0; i < iovcnt; i++) {
                uint64_t base = m.memory.template read<uint64_t>(iov_addr + i * 16);
                uint64_t len = m.memory.template read<uint64_t>(iov_addr + i * 16 + 8);
                if (len > 0) {
                    std::vector<uint8_t> buf(len);
                    m.memory.memcpy_out(buf.data(), base, len);
                    ssize_t n = ::send(native_fd, buf.data(), len, 0);
                    if (n < 0) {
                        m.set_result(total > 0 ? (int64_t)total : -errno);
                        return;
                    }
                    total += n;
                    if (static_cast<size_t>(n) < len) break;
                }
            }
            m.set_result(total);
            return;
        }
    }

    m.set_result(err::BADF);
}

static void sys_lseek(Machine& m) {
    auto& fs = get_fs(m);
    m.set_result(fs.lseek(
        m.template sysarg<int>(0),
        m.template sysarg<int64_t>(1),
        m.template sysarg<int>(2)
    ));
}

static void sys_getdents64(Machine& m) {
    auto& fs = get_fs(m);
    int fd = m.template sysarg<int>(0);
    auto buf_addr = m.sysarg(1);
    size_t count = m.sysarg(2);

    std::vector<uint8_t> buf(count);
    ssize_t n = fs.getdents64(fd, buf.data(), count);
    if (n > 0) {
        m.memory.memcpy(buf_addr, buf.data(), n);
    }
    m.set_result(n);
}

static void sys_newfstatat(Machine& m) {
    auto& fs = get_fs(m);
    int dirfd = m.template sysarg<int>(0);
    auto path_addr = m.sysarg(1);
    auto statbuf_addr = m.sysarg(2);
    int flags = m.template sysarg<int>(3);

    if (flags & AT_EMPTY_PATH) {
        m.set_result(err::NOTSUP);
        return;
    }

    if (dirfd != AT_FDCWD) {
        m.set_result(err::NOTSUP);
        return;
    }

    std::string path;
    try {
        path = m.memory.memstring(path_addr);
    } catch (...) {
        m.set_result(err::INVAL);
        return;
    }

    vfs::Entry entry;
    bool ok = (flags & AT_SYMLINK_NOFOLLOW) ? fs.lstat(path, entry) : fs.stat(path, entry);
    if (!ok) {
        m.set_result(err::NOENT);
        return;
    }

    linux_stat64 st = {};
    st.st_dev = 1;
    st.st_ino = std::hash<std::string>{}(path);
    st.st_mode = static_cast<uint32_t>(entry.type) | entry.mode;
    st.st_nlink = entry.is_dir() ? 2 : 1;
    st.st_uid = entry.uid;
    st.st_gid = entry.gid;
    st.st_size = entry.size;
    st.st_blksize = 4096;
    st.st_blocks = (entry.size + 511) / 512;
    st.st_mtime_sec = entry.mtime;
    st.st_atime_sec = entry.mtime;
    st.st_ctime_sec = entry.mtime;

    m.memory.memcpy(statbuf_addr, &st, sizeof(st));
    m.set_result(0);
}

static void sys_fstat(Machine& m) {
    auto& fs = get_fs(m);
    int fd = m.template sysarg<int>(0);
    auto statbuf_addr = m.sysarg(1);

    // stdin/stdout/stderr are character devices
    if (fd == 0 || fd == 1 || fd == 2) {
        linux_stat64 st = {};
        st.st_dev = 1;
        st.st_mode = 020666;  // Character device
        st.st_nlink = 1;
        st.st_blksize = 4096;
        m.memory.memcpy(statbuf_addr, &st, sizeof(st));
        m.set_result(0);
        return;
    }

    // VFS file descriptors
    auto entry = fs.get_entry(fd);
    if (entry) {
        std::string path = fs.get_path(fd);
        linux_stat64 st = {};
        st.st_dev = 1;
        st.st_ino = std::hash<std::string>{}(path);
        st.st_mode = static_cast<uint32_t>(entry->type) | entry->mode;
        st.st_nlink = entry->is_dir() ? 2 : 1;
        st.st_uid = entry->uid;
        st.st_gid = entry->gid;
        st.st_size = entry->size;
        st.st_blksize = 4096;
        st.st_blocks = (entry->size + 511) / 512;
        st.st_mtime_sec = entry->mtime;
        st.st_atime_sec = entry->mtime;
        st.st_ctime_sec = entry->mtime;
        m.memory.memcpy(statbuf_addr, &st, sizeof(st));
        m.set_result(0);
        return;
    }

    m.set_result(err::BADF);
}

static void sys_readlinkat(Machine& m) {
    auto& fs = get_fs(m);
    int dirfd = m.template sysarg<int>(0);
    auto path_addr = m.sysarg(1);
    auto buf_addr = m.sysarg(2);
    size_t bufsiz = m.sysarg(3);

    if (dirfd != AT_FDCWD) {
        m.set_result(err::NOTSUP);
        return;
    }

    std::string path;
    try {
        path = m.memory.memstring(path_addr);
    } catch (...) {
        m.set_result(err::INVAL);
        return;
    }

    std::vector<char> buf(bufsiz);
    ssize_t n = fs.readlink(path, buf.data(), bufsiz);
    if (n > 0) {
        m.memory.memcpy(buf_addr, buf.data(), n);
    }
    m.set_result(n);
}

static void sys_getcwd(Machine& m) {
    auto& fs = get_fs(m);
    auto buf_addr = m.sysarg(0);
    size_t size = m.sysarg(1);

    std::string cwd = fs.getcwd();
    if (cwd.size() + 1 > size) {
        m.set_result(-34);  // ERANGE
        return;
    }
    m.memory.memcpy(buf_addr, cwd.c_str(), cwd.size() + 1);
    m.set_result(buf_addr);
}

static void sys_chdir(Machine& m) {
    auto& fs = get_fs(m);
    std::string path;
    try {
        path = m.memory.memstring(m.sysarg(0));
    } catch (...) {
        m.set_result(err::INVAL);
        return;
    }
    m.set_result(fs.chdir(path) ? 0 : err::NOENT);
}

static void sys_faccessat(Machine& m) {
    auto& fs = get_fs(m);
    int dirfd = m.template sysarg<int>(0);

    if (dirfd != AT_FDCWD) {
        m.set_result(err::NOTSUP);
        return;
    }

    std::string path;
    try {
        path = m.memory.memstring(m.sysarg(1));
    } catch (...) {
        m.set_result(err::INVAL);
        return;
    }

    vfs::Entry entry;
    m.set_result(fs.stat(path, entry) ? 0 : err::NOENT);
}

static void sys_getpid(Machine& m) { m.set_result(1); }
static void sys_getppid(Machine& m) { m.set_result(0); }
static void sys_gettid(Machine& m) {
    if (g_sched.count > 0) {
        m.set_result(g_sched.threads[g_sched.current].tid);
    } else {
        m.set_result(1);
    }
}
static void sys_getuid(Machine& m) { m.set_result(0); }
static void sys_geteuid(Machine& m) { m.set_result(0); }
static void sys_getgid(Machine& m) { m.set_result(0); }
static void sys_getegid(Machine& m) { m.set_result(0); }
static void sys_set_tid_address(Machine& m) {
    auto tidptr = m.sysarg(0);
    if (g_sched.count > 0) {
        g_sched.threads[g_sched.current].clear_child_tid = tidptr;
        m.set_result(g_sched.threads[g_sched.current].tid);
    } else {
        m.set_result(1);
    }
}
static void sys_set_robust_list(Machine& m) { m.set_result(0); }

static void sys_clock_gettime(Machine& m) {
    auto tp_addr = m.sysarg(1);
    struct timespec ts;
    clock_gettime(CLOCK_REALTIME, &ts);

    linux_timespec lts;
    lts.tv_sec = ts.tv_sec;
    lts.tv_nsec = ts.tv_nsec;
    m.memory.memcpy(tp_addr, &lts, sizeof(lts));
    m.set_result(0);

    // Preemptive scheduling: yield to other threads periodically
    maybe_preempt(m);
}

static void sys_getrandom(Machine& m) {
    auto* ctx = get_ctx(m);
    auto buf_addr = m.sysarg(0);
    size_t count = m.sysarg(1);

    std::vector<uint8_t> buf(count);
    for (size_t i = 0; i < count; i++) {
        buf[i] = ctx->rng() & 0xFF;
    }
    m.memory.memcpy(buf_addr, buf.data(), count);
    m.set_result(count);
}

// mmap — intercept file-backed mappings, custom bump allocator for anonymous
static void sys_mmap(Machine& m) {
    auto* ctx = get_ctx(m);
    int vfd = m.template sysarg<int>(4);

    if (vfd == -1) {
        // Anonymous mapping: forward to libriscv's built-in handler.
        // Our custom bump allocator only works with encompassing arena mode,
        // but we use RISCV_FLAT_RW_ARENA without RISCV_ENCOMPASSING_ARENA,
        // so libriscv's built-in mmap_allocate is the correct path.
        if (libriscv_mmap_handler) {
            libriscv_mmap_handler(m);
        } else {
            m.set_result(uint64_t(-12));  // -ENOMEM
        }
        maybe_preempt(m);
        return;
    }

    // File-backed mapping: use our VFS
    auto addr_g = m.sysarg(0);
    auto length = m.sysarg(1);
    auto prot   = m.template sysarg<int>(2);
    auto flags  = m.template sysarg<int>(3);
    auto offset = m.sysarg(5);
    std::string fd_path = ctx->fs->get_path(vfd);
    std::cerr << "[mmap] fd=" << vfd << " path=" << fd_path
              << " addr=0x" << std::hex << addr_g
              << " len=0x" << length
              << " prot=" << std::dec << prot
              << " flags=0x" << std::hex << flags
              << " off=0x" << offset << std::dec << "\n";

    constexpr int MAP_FIXED = 0x10;
    constexpr uint64_t PAGE_ALIGN_MASK = 4095;

    if (addr_g % 4096 != 0) {
        m.set_result(uint64_t(-22));  // -EINVAL
        return;
    }
    length = (length + PAGE_ALIGN_MASK) & ~PAGE_ALIGN_MASK;

    auto entry = ctx->fs->get_entry(vfd);
    if (!entry || !entry->is_file()) {
        m.set_result(uint64_t(-9));  // -EBADF
        return;
    }

    auto& nextfree = m.memory.mmap_address();
    uint64_t dst;

    if (addr_g == 0) {
        if constexpr (riscv::encompassing_Nbit_arena > 0) {
            if (nextfree + length > riscv::encompassing_arena_mask) {
                m.set_result(uint64_t(-12));
                return;
            }
        }
        dst = nextfree;
        nextfree += length;
    } else if ((flags & MAP_FIXED) && addr_g < m.memory.mmap_start()) {
        dst = addr_g;
    } else if ((flags & MAP_FIXED) && addr_g >= m.memory.mmap_start() && addr_g + length <= nextfree) {
        dst = addr_g;
    } else if ((flags & MAP_FIXED) && addr_g >= m.memory.mmap_start()) {
        if constexpr (riscv::encompassing_Nbit_arena > 0) {
            uint64_t needed_end = addr_g + length;
            if (needed_end > riscv::encompassing_arena_mask) {
                m.set_result(uint64_t(-12));
                return;
            }
        }
        if (addr_g + length > nextfree)
            nextfree = addr_g + length;
        dst = addr_g;
    } else {
        dst = addr_g;
    }

    riscv::PageAttributes rw_attr;
    rw_attr.read = true;
    rw_attr.write = true;
    m.memory.set_page_attr(dst, length, rw_attr);

    m.memory.memdiscard(dst, length, true);

    const auto& content = entry->content;
    if (offset < content.size()) {
        size_t avail = content.size() - offset;
        size_t to_copy = std::min((size_t)length, avail);
        m.memory.memcpy(dst, content.data() + offset, to_copy);
    }

    riscv::PageAttributes attr;
    attr.read  = (prot & 1) != 0;
    attr.write = (prot & 2) != 0;
    attr.exec  = (prot & 4) != 0;
    m.memory.set_page_attr(dst, length, attr);

    m.set_result(dst);
    std::cerr << "[mmap] => 0x" << std::hex << dst << std::dec
              << " (nextfree=0x" << std::hex << nextfree << std::dec << ")\n";
}

// mprotect — apply page attrs for mmap region (thread stacks etc.)
// No-op for pages below mmap_start to avoid RELRO decoder cache issues.
static void sys_mprotect(Machine& m) {
    auto addr = m.sysarg(0);
    auto len  = m.sysarg(1);
    auto prot = m.template sysarg<int>(2);

    static int mprot_count = 0;
    if (++mprot_count <= 50)
        fprintf(stderr, "[mprotect] addr=0x%lx len=0x%lx prot=%d pc=0x%lx\n",
                (long)addr, (long)len, prot, (long)m.cpu.pc());

    if (addr >= m.memory.mmap_start()) {
        riscv::PageAttributes attr;
        attr.read = (prot & 1) != 0;
        attr.write = (prot & 2) != 0;
        attr.exec = (prot & 4) != 0;
        m.memory.set_page_attr(addr, len, attr);
    }
    m.set_result(0);
}

static void sys_sigaction(Machine& m) { m.set_result(0); }
static void sys_sigprocmask(Machine& m) { m.set_result(0); }
static void sys_prlimit64(Machine& m) {
    unsigned int resource = m.template sysarg<unsigned int>(1);
    auto new_rlim_addr = m.sysarg(2);
    auto old_rlim_addr = m.sysarg(3);

    constexpr unsigned RLIMIT_NOFILE = 7;
    constexpr unsigned RLIMIT_STACK  = 3;
    constexpr unsigned RLIMIT_AS     = 9;

    uint64_t cur = 1024, max = 1024;
    switch (resource) {
        case RLIMIT_NOFILE: cur = 1024; max = 1024; break;
        case RLIMIT_STACK:  cur = 8*1024*1024; max = UINT64_MAX; break;
        case RLIMIT_AS:     cur = UINT64_MAX; max = UINT64_MAX; break;
        default:            cur = UINT64_MAX; max = UINT64_MAX; break;
    }

    if (old_rlim_addr != 0) {
        m.memory.template write<uint64_t>(old_rlim_addr, cur);
        m.memory.template write<uint64_t>(old_rlim_addr + 8, max);
    }
    (void)new_rlim_addr;
    m.set_result(0);
}
static void sys_getrlimit(Machine& m) {
    unsigned int resource = m.template sysarg<unsigned int>(0);
    auto rlim_addr = m.sysarg(1);
    constexpr unsigned RLIMIT_NOFILE = 7;
    constexpr unsigned RLIMIT_STACK  = 3;
    constexpr unsigned RLIMIT_AS     = 9;
    uint64_t cur = UINT64_MAX, max = UINT64_MAX;
    switch (resource) {
        case RLIMIT_NOFILE: cur = 1024; max = 1024; break;
        case RLIMIT_STACK:  cur = 8*1024*1024; max = UINT64_MAX; break;
        case RLIMIT_AS:     cur = UINT64_MAX; max = UINT64_MAX; break;
    }
    if (rlim_addr != 0) {
        m.memory.template write<uint64_t>(rlim_addr, cur);
        m.memory.template write<uint64_t>(rlim_addr + 8, max);
    }
    fprintf(stderr, "[getrlimit] resource=%u => cur=%lu max=%lu\n", resource, cur, max);
    m.set_result(0);
}
static void sys_rseq(Machine& m) { m.set_result(err::NOSYS); }

// sendfile(out_fd, in_fd, offset, count) - copy data between fds via VFS
static void sys_sendfile(Machine& m) {
    auto* ctx = get_ctx(m);
    int out_fd = m.template sysarg<int>(0);
    int in_fd = m.template sysarg<int>(1);
    auto offset_ptr = m.sysarg(2);
    size_t count = m.sysarg(3);

    // Read from in_fd
    if (count > 65536) count = 65536;  // cap single transfer
    std::vector<uint8_t> buf(count);

    // Handle offset if provided
    if (offset_ptr != 0) {
        int64_t off = m.memory.template read<int64_t>(offset_ptr);
        ssize_t n = ctx->fs->pread(in_fd, buf.data(), count, off);
        if (n < 0) { m.set_result(n); return; }
        // Update the offset
        m.memory.template write<int64_t>(offset_ptr, off + n);
        count = n;
    } else {
        ssize_t n = ctx->fs->read(in_fd, buf.data(), count);
        if (n < 0) { m.set_result(n); return; }
        count = n;
    }

    if (count == 0) { m.set_result(0); return; }

    // Write to out_fd
    if (out_fd == 1 || out_fd == 2) {
        // stdout/stderr - use printer
        m.print(reinterpret_cast<const char*>(buf.data()), count);
        m.set_result(count);
    } else {
        ssize_t n = ctx->fs->write(out_fd, buf.data(), count);
        m.set_result(n);
    }
}

static void sys_ioctl(Machine& m) {
    int fd = m.template sysarg<int>(0);
    unsigned long request = m.sysarg(1);

    // TIOCGWINSZ - get window size
    // Return terminal dimensions for stdin/stdout/stderr so that programs
    // can query the terminal size (e.g. for ncurses, ls column formatting).
    if (request == 0x5413) {
        if (fd >= 0 && fd <= 2) {
            auto ws_addr = m.sysarg(2);
            struct { uint16_t rows, cols, xpixel, ypixel; } ws = { 24, 80, 0, 0 };
            ws.rows = static_cast<uint16_t>(android_io::term_rows.load());
            ws.cols = static_cast<uint16_t>(android_io::term_cols.load());
            m.memory.memcpy(ws_addr, &ws, sizeof(ws));
            m.set_result(0);
            return;
        }
    }

    // TCGETS - get terminal attributes
    // Succeed for fd 0, 1, 2 so isatty() returns true for all stdio.
    // This enables interactive shell features: line editing, colored
    // prompts, tab completion, job control.
    if (request == 0x5401) {
        if (fd >= 0 && fd <= 2) {
            auto termios_addr = m.sysarg(2);
            // struct termios on RISC-V Linux is 44 bytes:
            //   c_iflag(4) + c_oflag(4) + c_cflag(4) + c_lflag(4)
            //   + c_line(1) + c_cc[19] + c_ispeed(4) + c_ospeed(4)
            uint8_t termios_buf[44] = {};
            uint32_t c_iflag = 0;
            uint32_t c_oflag = 0x0005;  // OPOST | ONLCR
            uint32_t c_cflag = 0x00bf;  // CS8 | CREAD | CLOCAL
            uint32_t c_lflag = 0x8a3b;  // ECHO|ICANON|ISIG|IEXTEN|ECHOCTL|ECHOKE|ECHOE
            std::memcpy(termios_buf + 0, &c_iflag, 4);
            std::memcpy(termios_buf + 4, &c_oflag, 4);
            std::memcpy(termios_buf + 8, &c_cflag, 4);
            std::memcpy(termios_buf + 12, &c_lflag, 4);
            m.memory.memcpy(termios_addr, termios_buf, sizeof(termios_buf));
            m.set_result(0);
            return;
        }
    }

    // TCSETS, TCSETSW, TCSETSF - set terminal attributes (accept silently)
    if (request == 0x5402 || request == 0x5403 || request == 0x5404) {
        if (fd >= 0 && fd <= 2) {
            m.set_result(0);
            return;
        }
    }

    // FIONBIO - set non-blocking mode (libuv uses this on pipes/sockets)
    if (request == 0x5421) {
        m.set_result(0);
        return;
    }

    fprintf(stderr, "[ioctl] fd=%d request=0x%lx => -ENOTSUP\n", fd, (long)request);
    m.set_result(err::NOTSUP);
}

static void sys_fcntl(Machine& m) {
    auto& fs = get_fs(m);
    int fd = m.template sysarg<int>(0);
    int cmd = m.template sysarg<int>(1);

    bool valid = (fd >= 0 && fd <= 2) || fs.is_open(fd);
    if (!valid) {
        m.set_result(err::BADF);
        return;
    }

    constexpr int FCNTL_DUPFD = 0;
    constexpr int FCNTL_GETFD = 1;
    constexpr int FCNTL_SETFD = 2;
    constexpr int FCNTL_GETFL = 3;
    constexpr int FCNTL_SETFL = 4;
    constexpr int FCNTL_DUPFD_CLOEXEC = 1030;

    switch (cmd) {
        case FCNTL_DUPFD:
        case FCNTL_DUPFD_CLOEXEC: {
            int newfd = fs.dup(fd);
            m.set_result(newfd);
            return;
        }
        case FCNTL_GETFD:
            m.set_result(0);
            return;
        case FCNTL_SETFD:
            m.set_result(0);
            return;
        case FCNTL_GETFL:
            m.set_result((fd == 1 || fd == 2) ? 1 : 0);
            return;
        case FCNTL_SETFL:
            m.set_result(0);
            return;
        default:
            m.set_result(0);
            return;
    }
}

// close_range — bulk close/cloexec file descriptors
static void sys_close_range(Machine& m) {
    m.set_result(0);
}

static void sys_dup(Machine& m) {
    auto& fs = get_fs(m);
    int oldfd = m.template sysarg<int>(0);
    m.set_result(fs.dup(oldfd));
}

static void sys_dup3(Machine& m) {
    auto& fs = get_fs(m);
    int oldfd = m.template sysarg<int>(0);
    int newfd = m.template sysarg<int>(1);
    if (oldfd == newfd) {
        m.set_result(err::INVAL);
        return;
    }
    m.set_result(fs.dup2(oldfd, newfd));
}

static void sys_pipe2(Machine& m) {
    auto& fs = get_fs(m);
    auto pipefd_addr = m.sysarg(0);

    // Create a pipe using two connected in-memory file handles
    // Write end writes to a shared buffer, read end reads from it
    auto pipe_entry = std::make_shared<vfs::Entry>();
    pipe_entry->type = vfs::FileType::Fifo;
    pipe_entry->mode = 0600;
    pipe_entry->size = 0;

    // Allocate two fds - read end and write end
    int read_fd = fs.open_pipe(pipe_entry, 0);
    int write_fd = fs.open_pipe(pipe_entry, 1);

    int32_t fds[2] = { read_fd, write_fd };
    m.memory.memcpy(pipefd_addr, fds, sizeof(fds));
    fprintf(stderr, "[pipe2] => read=%d write=%d\n", read_fd, write_fd);
    m.set_result(0);
}

static void sys_readv(Machine& m) {
    auto& fs = get_fs(m);
    int fd = m.template sysarg<int>(0);
    auto iov_addr = m.sysarg(1);
    int iovcnt = m.template sysarg<int>(2);

    // If fd 0 has been redirected (e.g. dup2'd to a pipe), use VFS
    if (fd == 0 && fs.is_open(fd)) {
        size_t total = 0;
        for (int i = 0; i < iovcnt; i++) {
            uint64_t base = m.memory.template read<uint64_t>(iov_addr + i * 16);
            uint64_t len = m.memory.template read<uint64_t>(iov_addr + i * 16 + 8);
            if (len > 0) {
                std::vector<uint8_t> buf(len);
                ssize_t n = fs.read(fd, buf.data(), len);
                if (n < 0) {
                    m.set_result(total > 0 ? (int64_t)total : n);
                    return;
                }
                if (n > 0) {
                    m.memory.memcpy(base, buf.data(), n);
                    total += n;
                }
                if (static_cast<size_t>(n) < len) break;
            }
        }
        m.set_result(total);
        return;
    }

    if (fd == 0) {
        // Try non-blocking read from Android stdin buffer into iovec
        int has_data = android_io::has_stdin_data() ? 1 :
                       (android_io::is_eof() ? -1 : 0);
        if (has_data == -1) {
            // EOF
            m.set_result(0);
            return;
        }
        if (has_data == 0) {
            // No data — rewind PC and stop machine so main loop can yield
            android_io::waiting_for_stdin.store(true);
            m.cpu.increment_pc(-4);  // Rewind past ecall (4 bytes)
            m.stop();
            return;
        }
        size_t total = 0;
        for (int i = 0; i < iovcnt; i++) {
            uint64_t base = m.memory.template read<uint64_t>(iov_addr + i * 16);
            uint64_t len = m.memory.template read<uint64_t>(iov_addr + i * 16 + 8);
            if (len > 0) {
                std::vector<uint8_t> tmp(len);
                int bytes_read = android_io::try_read_stdin(tmp.data(), len);
                if (bytes_read > 0) {
                    m.memory.memcpy(base, tmp.data(), bytes_read);
                    total += bytes_read;
                }
                if (bytes_read <= 0 || static_cast<size_t>(bytes_read) < len) break;
            }
        }
        m.set_result(total);
        return;
    }

    size_t total = 0;
    for (int i = 0; i < iovcnt; i++) {
        uint64_t base = m.memory.template read<uint64_t>(iov_addr + i * 16);
        uint64_t len = m.memory.template read<uint64_t>(iov_addr + i * 16 + 8);
        if (len > 0) {
            std::vector<uint8_t> buf(len);
            ssize_t n = fs.read(fd, buf.data(), len);
            if (n < 0) {
                m.set_result(total > 0 ? (int64_t)total : n);
                return;
            }
            if (n > 0) {
                m.memory.memcpy(base, buf.data(), n);
                total += n;
            }
            if (static_cast<size_t>(n) < len) break;  // Short read
        }
    }
    m.set_result(total);
}

static void sys_pread64(Machine& m) {
    auto& fs = get_fs(m);
    int fd = m.template sysarg<int>(0);
    auto buf_addr = m.sysarg(1);
    size_t count = m.sysarg(2);
    uint64_t offset = m.sysarg(3);

    std::vector<uint8_t> buf(count);
    ssize_t n = fs.pread(fd, buf.data(), count, offset);
    if (n > 0) {
        m.memory.memcpy(buf_addr, buf.data(), n);
    }
    m.set_result(n);
}

static void sys_pwrite64(Machine& m) {
    auto& fs = get_fs(m);
    int fd = m.template sysarg<int>(0);
    auto buf_addr = m.sysarg(1);
    size_t count = m.sysarg(2);
    uint64_t offset = m.sysarg(3);

    std::vector<uint8_t> buf(count);
    m.memory.memcpy_out(buf.data(), buf_addr, count);
    ssize_t n = fs.pwrite(fd, buf.data(), count, offset);
    m.set_result(n);
}

static void sys_ftruncate(Machine& m) {
    auto& fs = get_fs(m);
    int fd = m.template sysarg<int>(0);
    uint64_t length = m.sysarg(1);
    m.set_result(fs.ftruncate(fd, length));
}

static void sys_mkdirat(Machine& m) {
    auto& fs = get_fs(m);
    int dirfd = m.template sysarg<int>(0);
    auto path_addr = m.sysarg(1);
    uint32_t mode = m.template sysarg<uint32_t>(2);

    if (dirfd != AT_FDCWD) {
        m.set_result(err::NOTSUP);
        return;
    }

    std::string path;
    try { path = m.memory.memstring(path_addr); }
    catch (...) { m.set_result(err::INVAL); return; }

    m.set_result(fs.mkdir(path, mode));
}

static void sys_unlinkat(Machine& m) {
    auto& fs = get_fs(m);
    int dirfd = m.template sysarg<int>(0);
    auto path_addr = m.sysarg(1);
    int flags = m.template sysarg<int>(2);

    if (dirfd != AT_FDCWD) {
        m.set_result(err::NOTSUP);
        return;
    }

    std::string path;
    try { path = m.memory.memstring(path_addr); }
    catch (...) { m.set_result(err::INVAL); return; }

    m.set_result(fs.unlink(path, flags));
}

static void sys_symlinkat(Machine& m) {
    auto& fs = get_fs(m);
    auto target_addr = m.sysarg(0);
    int newdirfd = m.template sysarg<int>(1);
    auto linkpath_addr = m.sysarg(2);

    if (newdirfd != AT_FDCWD) {
        m.set_result(err::NOTSUP);
        return;
    }

    std::string target, linkpath;
    try {
        target = m.memory.memstring(target_addr);
        linkpath = m.memory.memstring(linkpath_addr);
    } catch (...) { m.set_result(err::INVAL); return; }

    m.set_result(fs.symlink(target, linkpath));
}

static void sys_linkat(Machine& m) {
    auto& fs = get_fs(m);
    int olddirfd = m.template sysarg<int>(0);
    auto oldpath_addr = m.sysarg(1);
    int newdirfd = m.template sysarg<int>(2);
    auto newpath_addr = m.sysarg(3);

    if (olddirfd != AT_FDCWD || newdirfd != AT_FDCWD) {
        m.set_result(err::NOTSUP);
        return;
    }

    std::string oldpath, newpath;
    try {
        oldpath = m.memory.memstring(oldpath_addr);
        newpath = m.memory.memstring(newpath_addr);
    } catch (...) { m.set_result(err::INVAL); return; }

    m.set_result(fs.link(oldpath, newpath));
}

static void sys_renameat(Machine& m) {
    auto& fs = get_fs(m);
    int olddirfd = m.template sysarg<int>(0);
    auto oldpath_addr = m.sysarg(1);
    int newdirfd = m.template sysarg<int>(2);
    auto newpath_addr = m.sysarg(3);

    if (olddirfd != AT_FDCWD || newdirfd != AT_FDCWD) {
        m.set_result(err::NOTSUP);
        return;
    }

    std::string oldpath, newpath;
    try {
        oldpath = m.memory.memstring(oldpath_addr);
        newpath = m.memory.memstring(newpath_addr);
    } catch (...) { m.set_result(err::INVAL); return; }

    m.set_result(fs.rename(oldpath, newpath));
}

static void sys_sysinfo(Machine& m) {
    auto info_addr = m.sysarg(0);

    // Linux sysinfo structure (64-bit)
    struct linux_sysinfo {
        int64_t  uptime;
        uint64_t loads[3];
        uint64_t totalram;
        uint64_t freeram;
        uint64_t bufferram;
        uint64_t totalswap;
        uint64_t freeswap;
        uint16_t procs;
        uint16_t pad;
        uint32_t pad2;
        uint64_t totalhigh;
        uint64_t freehigh;
        uint32_t mem_unit;
    };

    linux_sysinfo si = {};
    si.uptime = 100;
    si.totalram = 256ULL * 1024 * 1024;  // 256MB
    si.freeram = 128ULL * 1024 * 1024;   // 128MB
    si.procs = 1;
    si.mem_unit = 1;

    m.memory.memcpy(info_addr, &si, sizeof(si));
    m.set_result(0);
}

// ppoll - poll file descriptors for events
// Ash uses this to check if stdin has data before reading.
static void sys_ppoll(Machine& m) {
    auto fds_addr = m.sysarg(0);
    uint64_t nfds = m.sysarg(1);
    auto timeout_addr = m.sysarg(2);

    if (nfds == 0) {
        m.set_result(0);
        return;
    }
    if (nfds > 64) nfds = 64;

    bool has_timeout = (timeout_addr != 0);
    bool zero_timeout = false;
    if (has_timeout) {
        int64_t tv_sec = m.memory.template read<int64_t>(timeout_addr);
        int64_t tv_nsec = m.memory.template read<int64_t>(timeout_addr + 8);
        zero_timeout = (tv_sec == 0 && tv_nsec == 0);
    }
    int ready = 0;
    bool needs_stdin = false;

    for (uint64_t i = 0; i < nfds; i++) {
        uint64_t entry_addr = fds_addr + i * 8;
        int32_t fd = m.memory.template read<int32_t>(entry_addr);
        int16_t events = m.memory.template read<int16_t>(entry_addr + 4);
        int16_t revents = 0;

        if (fd == 0 && (events & 0x0001 /*POLLIN*/)) {
            int has_data = android_io::has_stdin_data() ? 1 :
                           (android_io::is_eof() ? -1 : 0);
            if (has_data == 1) {
                revents |= 0x0001; // POLLIN
                ready++;
            } else if (has_data == -1) {
                revents |= 0x0010; // POLLHUP (EOF)
                ready++;
            } else {
                needs_stdin = true;
            }
        } else if (fd == 1 || fd == 2) {
            if (events & 0x0004 /*POLLOUT*/) {
                revents |= 0x0004;
                ready++;
            }
        } else if (fd >= 0) {
            revents |= (events & 0x0001); // POLLIN if requested
            if (revents) ready++;
        }

        m.memory.template write<int16_t>(entry_addr + 6, revents);
    }

    if (ready > 0) {
        m.set_result(ready);
    } else if (zero_timeout) {
        m.set_result(0);
    } else if (needs_stdin) {
        android_io::waiting_for_stdin.store(true);
        m.cpu.increment_pc(-4);
        m.stop();
    } else {
        android_io::waiting_for_stdin.store(true);
        m.cpu.increment_pc(-4);
        m.stop();
    }
}

// ============================================================================
// epoll — I/O event notification for libuv (Node.js event loop)
// ============================================================================

// Epoll instance keyed by VFS fd
struct EpollInterest {
    uint32_t events;  // EPOLLIN=1, EPOLLOUT=4, etc.
    uint64_t data;    // Caller's epoll_data (returned as-is in epoll_pwait)
};
struct EpollInstance {
    std::unordered_map<int, EpollInterest> interests;  // fd → {events, data}
};

// Global epoll instances (keyed by epoll fd)
inline std::unordered_map<int, EpollInstance> g_epoll_instances;
inline int g_next_epoll_fd = 2000;  // Start at 2000 to avoid collision with socket FDs (base 1000)

static void sys_epoll_create1(Machine& m) {
    int fd = g_next_epoll_fd++;
    g_epoll_instances[fd] = EpollInstance{};
    m.set_result(fd);
}

static void sys_epoll_ctl(Machine& m) {
    int epfd = m.template sysarg<int>(0);
    int op   = m.template sysarg<int>(1);
    int fd   = m.template sysarg<int>(2);
    auto event_addr = m.sysarg(3);

    auto it = g_epoll_instances.find(epfd);
    if (it == g_epoll_instances.end()) {
        m.set_result(-9);  // -EBADF
        return;
    }

    constexpr int EPOLL_CTL_ADD = 1;
    constexpr int EPOLL_CTL_DEL = 2;
    constexpr int EPOLL_CTL_MOD = 3;

    if (op == EPOLL_CTL_ADD || op == EPOLL_CTL_MOD) {
        // struct epoll_event { uint32_t events; [pad]; uint64_t data; } = 16 bytes
        uint32_t events = m.memory.template read<uint32_t>(event_addr);
        uint64_t data   = m.memory.template read<uint64_t>(event_addr + 8);
        it->second.interests[fd] = EpollInterest{events, data};
        m.set_result(0);
    } else if (op == EPOLL_CTL_DEL) {
        it->second.interests.erase(fd);
        m.set_result(0);
    } else {
        m.set_result(err::INVAL);
    }
}

static void sys_epoll_pwait(Machine& m) {
    int epfd = m.template sysarg<int>(0);
    auto events_addr = m.sysarg(1);
    int maxevents = m.template sysarg<int>(2);
    int timeout = m.template sysarg<int>(3);

    auto it = g_epoll_instances.find(epfd);
    if (it == g_epoll_instances.end()) {
        m.set_result(-9);  // -EBADF
        return;
    }

    auto& fs = get_fs(m);
    int ready = 0;

    // Check each interest for readiness
    for (auto& [fd, interest] : it->second.interests) {
        if (ready >= maxevents) break;

        uint32_t revents = 0;

        if (fd == 0) {
            // stdin — check Android buffer
            if (android_io::has_stdin_data() && (interest.events & 0x01 /*EPOLLIN*/))
                revents |= 0x01;
        } else if (fd == 1 || fd == 2) {
            // stdout/stderr always writable
            if (interest.events & 0x04 /*EPOLLOUT*/)
                revents |= 0x04;
        } else if (fs.is_open(fd)) {
            auto entry = fs.get_entry(fd);
            if (entry && entry->type == vfs::FileType::Fifo) {
                if ((interest.events & 0x01) && entry->content.size() > 0)
                    revents |= 0x01;
                if (interest.events & 0x04)
                    revents |= 0x04;
            } else {
                if (interest.events & 0x01) revents |= 0x01;
                if (interest.events & 0x04) revents |= 0x04;
            }
        }
        else if (net_is_socket_fd && net_is_socket_fd(fd)) {
            int native_fd = net_get_native_fd ? net_get_native_fd(fd) : -1;
            if (native_fd >= 0) {
                struct pollfd pfd;
                pfd.fd = native_fd;
                pfd.events = 0;
                if (interest.events & 0x01) pfd.events |= POLLIN;
                if (interest.events & 0x04) pfd.events |= POLLOUT;
                pfd.revents = 0;
                if (::poll(&pfd, 1, 0) > 0) {
                    if (pfd.revents & POLLIN)  revents |= 0x01;
                    if (pfd.revents & POLLOUT) revents |= 0x04;
                    if (pfd.revents & (POLLERR | POLLHUP)) revents |= 0x08;
                }
            }
        }

        if (revents) {
            uint64_t offset = events_addr + ready * 16;
            m.memory.template write<uint32_t>(offset, revents);
            m.memory.template write<uint32_t>(offset + 4, 0);
            m.memory.template write<uint64_t>(offset + 8, interest.data);
            ready++;
        }
    }

    if (ready > 0) {
        m.set_result(ready);
    } else if (timeout == 0) {
        m.set_result(0);
    } else {
        // Native mode: collect socket fds and do a blocking poll
        std::vector<struct pollfd> pfds;
        std::vector<std::pair<int, EpollInterest*>> pfd_map;
        for (auto& [fd2, interest2] : it->second.interests) {
            if (net_is_socket_fd && net_is_socket_fd(fd2)) {
                int native_fd = net_get_native_fd ? net_get_native_fd(fd2) : -1;
                if (native_fd >= 0) {
                    struct pollfd pfd;
                    pfd.fd = native_fd;
                    pfd.events = 0;
                    if (interest2.events & 0x01) pfd.events |= POLLIN;
                    if (interest2.events & 0x04) pfd.events |= POLLOUT;
                    pfd.revents = 0;
                    pfds.push_back(pfd);
                    pfd_map.push_back({fd2, &interest2});
                }
            }
        }
        if (!pfds.empty()) {
            int poll_timeout = timeout;
            int ret = ::poll(pfds.data(), pfds.size(), poll_timeout);
            if (ret > 0) {
                for (size_t i = 0; i < pfds.size() && ready < maxevents; i++) {
                    uint32_t revents2 = 0;
                    if (pfds[i].revents & POLLIN)  revents2 |= 0x01;
                    if (pfds[i].revents & POLLOUT) revents2 |= 0x04;
                    if (pfds[i].revents & (POLLERR | POLLHUP)) revents2 |= 0x08;
                    if (revents2) {
                        uint64_t offset = events_addr + ready * 16;
                        m.memory.template write<uint32_t>(offset, revents2);
                        m.memory.template write<uint32_t>(offset + 4, 0);
                        m.memory.template write<uint64_t>(offset + 8, pfd_map[i].second->data);
                        ready++;
                    }
                }
            }
            m.set_result(ready);
            return;
        }
        // Nothing ready, no sockets to poll — yield
        android_io::waiting_for_stdin.store(true);
        m.cpu.increment_pc(-4);
        m.stop();
    }
}

// ============================================================================
// futex — thread synchronization with cooperative scheduling
// ============================================================================

static void sys_futex(Machine& m) {
    auto uaddr = m.sysarg(0);
    int op = m.template sysarg<int>(1);

    int cmd = op & 0x7f;

    constexpr int FUTEX_WAIT = 0;
    constexpr int FUTEX_WAKE = 1;
    constexpr int FUTEX_WAIT_BITSET = 9;
    constexpr int FUTEX_WAKE_BITSET = 10;

    if (cmd == FUTEX_WAIT || cmd == FUTEX_WAIT_BITSET) {
        int32_t expected = m.template sysarg<int>(2);
        int32_t actual = m.memory.template read<int32_t>(uaddr);
        if (actual != expected) {
            m.set_result(-11);  // -EAGAIN
            return;
        }

        // Cooperative scheduling: if another thread is runnable, switch to it.
        if (g_sched.count > 1) {
            auto& cur = g_sched.threads[g_sched.current];
            cur.waiting = true;
            cur.futex_addr = uaddr;
            cur.futex_val = expected;
            m.set_result(0);

            int next = g_sched.next_runnable(g_sched.current);
            if (next >= 0) {
                static int switch_count = 0;
                if (++switch_count <= 20)
                    fprintf(stderr, "[futex] WAIT switch t%d->t%d addr=0x%lx exp=0x%x\n",
                            g_sched.current, next, (long)uaddr, (unsigned)expected);
                switch_to_thread(m, next);
                return;
            }
            cur.waiting = false;
        }

        // Fallback: no cooperative threads. Write 0 to break spin loops.
        m.memory.template write<int32_t>(uaddr, 0);
        static int futex_wait_count = 0;
        if (++futex_wait_count <= 20) {
            fprintf(stderr, "[futex] WAIT fallback addr=0x%lx exp=0x%x\n",
                    (long)uaddr, (unsigned)expected);
        }
        m.set_result(0);

    } else if (cmd == FUTEX_WAKE || cmd == FUTEX_WAKE_BITSET) {
        int max_wake = m.template sysarg<int>(2);
        int woken = g_sched.wake(uaddr, max_wake);

        if (woken > 0) {
            static int wake_count = 0;
            if (++wake_count <= 20)
                fprintf(stderr, "[futex] WAKE addr=0x%lx woke=%d\n",
                        (long)uaddr, woken);
        }
        m.set_result(woken);
    } else {
        m.set_result(-38);  // -ENOSYS for other futex ops
    }
}

// ============================================================================
// statx — extended file stat
// ============================================================================

static void sys_statx(Machine& m) {
    auto& fs = get_fs(m);
    int dirfd = m.template sysarg<int>(0);
    auto path_addr = m.sysarg(1);
    int flags = m.template sysarg<int>(2);
    // uint32_t mask = m.template sysarg<uint32_t>(3);  // unused — we fill all
    auto buf_addr = m.sysarg(4);

    if (dirfd != AT_FDCWD) {
        m.set_result(err::NOTSUP);
        return;
    }

    std::string path;
    try {
        path = m.memory.memstring(path_addr);
    } catch (...) {
        m.set_result(err::INVAL);
        return;
    }

    // AT_EMPTY_PATH with empty string means fstat on dirfd — not supported
    if (path.empty()) {
        m.set_result(-2);  // -ENOENT
        return;
    }

    auto entry = fs.resolve(path);
    if (!entry) {
        m.set_result(-2);  // -ENOENT
        return;
    }

    // struct statx (256 bytes on rv64)
    uint8_t buf[256] = {};

    // stx_mask (offset 0): what fields are filled
    uint32_t stx_mask = 0x07ff;  // STATX_BASIC_STATS
    std::memcpy(buf + 0, &stx_mask, 4);

    // stx_blksize (offset 4)
    uint32_t blksize = 4096;
    std::memcpy(buf + 4, &blksize, 4);

    // stx_attributes (offset 8) — 0
    // stx_nlink (offset 16)
    uint32_t nlink = entry->is_dir() ? 2 : 1;
    std::memcpy(buf + 16, &nlink, 4);

    // stx_uid (offset 20), stx_gid (offset 24)
    uint32_t zero32 = 0;
    std::memcpy(buf + 20, &zero32, 4);
    std::memcpy(buf + 24, &zero32, 4);

    // stx_mode (offset 28)
    uint16_t mode = entry->mode;
    if (entry->is_dir())       mode |= 0040000;  // S_IFDIR
    else if (entry->type == vfs::FileType::Symlink) mode |= 0120000;  // S_IFLNK
    else                       mode |= 0100000;  // S_IFREG
    std::memcpy(buf + 28, &mode, 2);

    // stx_ino (offset 32) — use pointer as fake inode
    uint64_t ino = reinterpret_cast<uintptr_t>(entry.get()) & 0xFFFFFFFF;
    std::memcpy(buf + 32, &ino, 8);

    // stx_size (offset 40)
    uint64_t size = entry->is_dir() ? 4096 : entry->content.size();
    std::memcpy(buf + 40, &size, 8);

    // stx_blocks (offset 48)
    uint64_t blocks = (size + 511) / 512;
    std::memcpy(buf + 48, &blocks, 8);

    // stx_attributes_mask (offset 56) — 0

    // Timestamps: stx_atime (64), stx_btime (80), stx_ctime (96), stx_mtime (112)
    // Each is { int64_t tv_sec; uint32_t tv_nsec; int32_t __reserved; } = 16 bytes
    // Use current time
    struct timespec now;
    clock_gettime(CLOCK_REALTIME, &now);
    for (int i = 0; i < 4; i++) {
        size_t off = 64 + i * 16;
        std::memcpy(buf + off, &now.tv_sec, 8);
        uint32_t nsec = now.tv_nsec;
        std::memcpy(buf + off + 8, &nsec, 4);
    }

    m.memory.memcpy(buf_addr, buf, sizeof(buf));
    m.set_result(0);
}

// ============================================================================
// uname — system identification
// ============================================================================

static void sys_uname(Machine& m) {
    auto buf_addr = m.sysarg(0);

    // struct utsname: 5 fields of 65 bytes each = 325 bytes (some add domainname=65 → 390)
    // RISC-V Linux uses 65-byte fields
    constexpr int FIELD_LEN = 65;
    uint8_t buf[FIELD_LEN * 6] = {};  // 6 fields to be safe

    auto write_field = [&](int idx, const char* val) {
        size_t len = std::strlen(val);
        if (len >= FIELD_LEN) len = FIELD_LEN - 1;
        std::memcpy(buf + idx * FIELD_LEN, val, len);
    };

    write_field(0, "Linux");                  // sysname
    write_field(1, "friscy");                 // nodename
    write_field(2, "6.1.0-friscy");           // release
    write_field(3, "#1 SMP PREEMPT_DYNAMIC"); // version
    write_field(4, "riscv64");                // machine
    write_field(5, "(none)");                 // domainname

    m.memory.memcpy(buf_addr, buf, sizeof(buf));
    m.set_result(0);
}

// ============================================================================
// nanosleep — sleep for specified duration
// ============================================================================

static void sys_nanosleep(Machine& m) {
    auto req_addr = m.sysarg(0);

    int64_t tv_sec = m.memory.template read<int64_t>(req_addr);
    int64_t tv_nsec = m.memory.template read<int64_t>(req_addr + 8);
    int ms = static_cast<int>(tv_sec * 1000 + tv_nsec / 1000000);
    if (ms < 1) ms = 1;

    // Cooperative scheduling: nanosleep is a natural yield point
    if (g_sched.count > 1) {
        int next = g_sched.next_runnable(g_sched.current);
        if (next >= 0) {
            switch_to_thread(m, next);
            return;
        }
    }

    usleep(static_cast<useconds_t>(ms) * 1000);
    m.set_result(0);
}

// ============================================================================
// Stubs — safe no-ops or ENOSYS returns
// ============================================================================

static void sys_madvise(Machine& m) {
    auto addr = m.sysarg(0);
    auto len = m.sysarg(1);
    auto advice = m.template sysarg<int>(2);
    static int madvise_count = 0;
    if (++madvise_count <= 200)
        fprintf(stderr, "[madvise] addr=0x%lx len=0x%lx advice=%d pc=0x%lx\n",
                (long)addr, (long)len, advice, (long)m.cpu.pc());
    m.set_result(0);
}
static void sys_prctl(Machine& m) { m.set_result(0); }
static void sys_mremap(Machine& m) { m.set_result(err::NOSYS); }
static void sys_eventfd2(Machine& m) {
    auto& fs = get_fs(m);
    auto entry = std::make_shared<vfs::Entry>();
    entry->type = vfs::FileType::Regular;
    entry->mode = 0600;
    entry->size = 0;
    entry->content.resize(8, 0);
    int fd = fs.open_pipe(entry, 0);
    fprintf(stderr, "[eventfd2] => fd=%d\n", fd);
    m.set_result(fd);
}
static void sys_io_uring_setup(Machine& m) { m.set_result(err::NOSYS); }
static void sys_capget(Machine& m) { m.set_result(-1); }  // -EPERM

static void sys_sched_getscheduler(Machine& m) {
    m.set_result(0);  // SCHED_OTHER
}

static void sys_sched_getparam(Machine& m) {
    auto param_addr = m.sysarg(1);
    // struct sched_param { int sched_priority; }
    m.memory.template write<int32_t>(param_addr, 0);
    m.set_result(0);
}

static void sys_sched_getaffinity(Machine& m) {
    auto mask_addr = m.sysarg(2);
    // Write 1-bit CPU mask (1 core)
    uint64_t mask = 1;
    m.memory.template write<uint64_t>(mask_addr, mask);
    m.set_result(8);  // Return size of mask in bytes
}

// ============================================================================
// Additional syscalls discovered from strace of curl/git/python/vim/bash/ssh
// ============================================================================

static void sys_umask(Machine& m) {
    // Return previous umask, accept new one (we don't enforce permissions)
    static uint32_t current_umask = 0022;
    uint32_t new_mask = m.template sysarg<uint32_t>(0);
    uint32_t old = current_umask;
    current_umask = new_mask & 0777;
    m.set_result(old);
}

static void sys_getpgid(Machine& m) {
    // Return same as getpid — single process group
    m.set_result(1);
}

static void sys_getresuid(Machine& m) {
    // Write real, effective, saved UIDs (all 0 = root)
    auto ruid_addr = m.sysarg(0);
    auto euid_addr = m.sysarg(1);
    auto suid_addr = m.sysarg(2);
    m.memory.template write<uint32_t>(ruid_addr, 0);
    m.memory.template write<uint32_t>(euid_addr, 0);
    m.memory.template write<uint32_t>(suid_addr, 0);
    m.set_result(0);
}

static void sys_getresgid(Machine& m) {
    auto rgid_addr = m.sysarg(0);
    auto egid_addr = m.sysarg(1);
    auto sgid_addr = m.sysarg(2);
    m.memory.template write<uint32_t>(rgid_addr, 0);
    m.memory.template write<uint32_t>(egid_addr, 0);
    m.memory.template write<uint32_t>(sgid_addr, 0);
    m.set_result(0);
}

static void sys_sigaltstack(Machine& m) {
    // Accept silently — we don't deliver signals, so alternate stack is unused
    m.set_result(0);
}

static void sys_clock_getres(Machine& m) {
    // int clock_getres(clockid_t clk, struct timespec *res)
    auto res_addr = m.sysarg(1);
    if (res_addr != 0) {
        // Report 1ms resolution
        m.memory.template write<int64_t>(res_addr, 0);       // tv_sec
        m.memory.template write<int64_t>(res_addr + 8, 1000000);  // tv_nsec = 1ms
    }
    m.set_result(0);
}

static void sys_membarrier(Machine& m) {
    // Single-core: no memory ordering issues. Return -ENOSYS for registration,
    // which tells callers to fall back to compiler barriers.
    int cmd = m.template sysarg<int>(0);
    if (cmd == 0) {
        // MEMBARRIER_CMD_QUERY — report no supported commands
        m.set_result(0);
    } else {
        m.set_result(err::NOSYS);
    }
}

static void sys_faccessat2(Machine& m) {
    // Same as faccessat but with extra flags arg (which we ignore)
    auto& fs = get_fs(m);
    int dirfd = m.template sysarg<int>(0);
    auto path_addr = m.sysarg(1);
    // int mode = m.template sysarg<int>(2);
    // int flags = m.template sysarg<int>(3);  // AT_SYMLINK_NOFOLLOW etc.

    if (dirfd != AT_FDCWD) {
        m.set_result(err::NOTSUP);
        return;
    }

    std::string path;
    try {
        path = m.memory.memstring(path_addr);
    } catch (...) {
        m.set_result(err::INVAL);
        return;
    }

    auto entry = fs.resolve(path);
    m.set_result(entry ? 0 : err::NOENT);
}

// recvmsg — scatter-gather socket receive (needed by node HTTP)
static void sys_recvmsg(Machine& m) {
    int fd = m.template sysarg<int>(0);
    auto msghdr_addr = m.sysarg(1);
    // int flags = m.template sysarg<int>(2);

    auto& fs = get_fs(m);

    // struct msghdr {
    //   void *msg_name;          // 0:  8 bytes
    //   socklen_t msg_namelen;   // 8:  4 bytes (+4 pad)
    //   struct iovec *msg_iov;   // 16: 8 bytes
    //   size_t msg_iovlen;       // 24: 8 bytes
    //   void *msg_control;       // 32: 8 bytes
    //   size_t msg_controllen;   // 40: 8 bytes
    //   int msg_flags;           // 48: 4 bytes
    // }
    auto iov_addr = m.memory.template read<uint64_t>(msghdr_addr + 16);
    auto iovlen   = m.memory.template read<uint64_t>(msghdr_addr + 24);

    // Read into iovec buffers, similar to readv
    size_t total = 0;
    for (uint64_t i = 0; i < iovlen && i < 16; i++) {
        uint64_t base = m.memory.template read<uint64_t>(iov_addr + i * 16);
        uint64_t len  = m.memory.template read<uint64_t>(iov_addr + i * 16 + 8);
        if (len > 0) {
            std::vector<uint8_t> buf(len);
            ssize_t n = fs.read(fd, buf.data(), len);
            if (n < 0) {
                m.set_result(total > 0 ? (int64_t)total : n);
                return;
            }
            if (n > 0) {
                m.memory.memcpy(base, buf.data(), n);
                total += n;
            }
            if (static_cast<size_t>(n) < len) break;
        }
    }

    // Zero out msg_controllen (no ancillary data)
    m.memory.template write<uint64_t>(msghdr_addr + 40, 0);
    // Clear msg_flags
    m.memory.template write<int32_t>(msghdr_addr + 48, 0);

    m.set_result(total);
}

// ============================================================================
// Round 3: Go echo + Next.js build gaps
// ============================================================================

static void sys_flock(Machine& m) { m.set_result(0); }
static void sys_fsync(Machine& m) { m.set_result(0); }

static void sys_fchmod(Machine& m) {
    auto& fs = get_fs(m);
    int fd = m.template sysarg<int>(0);
    uint32_t mode = m.template sysarg<uint32_t>(1);
    auto entry = fs.get_entry(fd);
    if (!entry) { m.set_result(err::BADF); return; }
    entry->mode = mode & 07777;
    m.set_result(0);
}

static void sys_fchmodat(Machine& m) {
    auto& fs = get_fs(m);
    int dirfd = m.template sysarg<int>(0);
    auto path_addr = m.sysarg(1);
    uint32_t mode = m.template sysarg<uint32_t>(2);
    if (dirfd != AT_FDCWD) { m.set_result(err::NOTSUP); return; }
    std::string path;
    try { path = m.memory.memstring(path_addr); }
    catch (...) { m.set_result(err::INVAL); return; }
    auto entry = fs.resolve(path);
    if (!entry) { m.set_result(err::NOENT); return; }
    entry->mode = mode & 07777;
    m.set_result(0);
}

static void sys_fchownat(Machine& m) { m.set_result(0); }
static void sys_getgroups(Machine& m) { m.set_result(0); }

static void sys_kill(Machine& m) {
    int pid = m.template sysarg<int>(0);
    int sig = m.template sysarg<int>(1);
    if (pid <= 1 || pid == 100) {
        m.set_result(0);
    } else {
        m.set_result(-3);  // -ESRCH
    }
}

static void sys_tkill(Machine& m) {
    int sig = m.template sysarg<int>(1);
    if (sig == 6) { // SIGABRT
        fprintf(stderr, "[ABORT] tkill(SIGABRT)! PC=0x%lx RA=0x%lx SP=0x%lx\n",
                (long)m.cpu.pc(), (long)m.cpu.reg(1), (long)m.cpu.reg(2));
    }
    m.set_result(0);
}

static void sys_sched_yield(Machine& m) {
    m.set_result(0);
    if (g_sched.count > 1) {
        int next = g_sched.next_runnable(g_sched.current);
        if (next >= 0) {
            switch_to_thread(m, next);
        }
    }
}

static void sys_rt_sigreturn(Machine& m) { m.set_result(0); }

static void sys_pwritev(Machine& m) {
    auto& fs = get_fs(m);
    int fd = m.template sysarg<int>(0);
    auto iov_addr = m.sysarg(1);
    int iovcnt = m.template sysarg<int>(2);
    int64_t offset = m.template sysarg<int64_t>(3);

    std::vector<uint8_t> combined;
    for (int i = 0; i < iovcnt && i < 16; i++) {
        uint64_t base = m.memory.template read<uint64_t>(iov_addr + i * 16);
        uint64_t len  = m.memory.template read<uint64_t>(iov_addr + i * 16 + 8);
        if (len > 0) {
            size_t prev = combined.size();
            combined.resize(prev + len);
            m.memory.memcpy_out(combined.data() + prev, base, len);
        }
    }

    if (combined.empty()) { m.set_result(0); return; }
    ssize_t n = fs.pwrite(fd, combined.data(), combined.size(), offset);
    m.set_result(n);
}

static void sys_socketpair(Machine& m) {
    auto& fs = get_fs(m);
    auto sv_addr = m.sysarg(3);

    auto pipe_a = std::make_shared<vfs::Entry>();
    pipe_a->type = vfs::FileType::Fifo;
    pipe_a->mode = 0600;
    pipe_a->size = 0;

    auto pipe_b = std::make_shared<vfs::Entry>();
    pipe_b->type = vfs::FileType::Fifo;
    pipe_b->mode = 0600;
    pipe_b->size = 0;

    int fd0_read = fs.open_pipe(pipe_a, 0);
    int fd0_write = fs.open_pipe(pipe_b, 1);
    int fd1_read = fs.open_pipe(pipe_b, 0);
    int fd1_write = fs.open_pipe(pipe_a, 1);

    fs.close(fd0_write);
    fs.close(fd1_read);

    int32_t sv[2] = { fd1_write, fd0_read };
    m.memory.memcpy(sv_addr, sv, sizeof(sv));
    m.set_result(0);
}

static void sys_sendmsg(Machine& m) {
    int fd = m.template sysarg<int>(0);
    auto msghdr_addr = m.sysarg(1);
    auto& fs = get_fs(m);

    auto iov_addr = m.memory.template read<uint64_t>(msghdr_addr + 16);
    auto iovlen   = m.memory.template read<uint64_t>(msghdr_addr + 24);

    size_t total = 0;
    for (uint64_t i = 0; i < iovlen && i < 16; i++) {
        uint64_t base = m.memory.template read<uint64_t>(iov_addr + i * 16);
        uint64_t len  = m.memory.template read<uint64_t>(iov_addr + i * 16 + 8);
        if (len > 0) {
            std::vector<uint8_t> buf(len);
            m.memory.memcpy_out(buf.data(), base, len);
            ssize_t n = fs.write(fd, buf.data(), len);
            if (n < 0) {
                m.set_result(total > 0 ? (int64_t)total : n);
                return;
            }
            total += n;
            if (static_cast<size_t>(n) < len) break;
        }
    }
    m.set_result(total);
}

}  // namespace handlers

// Custom brk handler: after execve, libriscv's m_heap_address is stale.
static void sys_brk(Machine& m) {
    auto new_end = m.sysarg(0);

    if (!g_exec_ctx.brk_overridden) {
        // Forward to libriscv's built-in brk handler which properly manages
        // heap memory (page allocation, heap pointer tracking, etc.)
        if (libriscv_brk_handler) {
            libriscv_brk_handler(m);
            return;
        }
        // Fallback if no built-in handler saved
        uint64_t heap_addr = m.memory.heap_address();
        constexpr uint64_t BRK_MAX = 16ULL << 20;
        if (new_end > heap_addr + BRK_MAX) {
            new_end = heap_addr + BRK_MAX;
        } else if (new_end < heap_addr) {
            new_end = heap_addr;
        }
        m.set_result(new_end);
        return;
    }

    constexpr uint64_t BRK_MAX = 16ULL << 20;
    if (new_end == 0 || new_end < g_exec_ctx.brk_base) {
        new_end = g_exec_ctx.brk_current;
    } else if (new_end > g_exec_ctx.brk_base + BRK_MAX) {
        new_end = g_exec_ctx.brk_base + BRK_MAX;
    }

    if (new_end > g_exec_ctx.brk_current) {
        uint64_t start = g_exec_ctx.brk_current;
        uint64_t len = new_end - start;
        riscv::PageAttributes rw;
        rw.read = true; rw.write = true;
        m.memory.set_page_attr(start, len, rw);
    }

    g_exec_ctx.brk_current = new_end;
    m.set_result(new_end);
}

// Round 4: Node.js startup syscalls
namespace nr {
    constexpr int getsockopt     = 209;
    constexpr int riscv_hwprobe  = 258;
}

static void sys_getsockopt(Machine& m) {
    m.set_result(-88);  // -ENOTSOCK
}

static void sys_riscv_hwprobe(Machine& m) {
    m.set_result(-38);  // -ENOSYS
}

// Install all syscall handlers
inline void install_syscalls(Machine& machine, vfs::VirtualFS& fs) {
    // Create and store context
    static SyscallContext ctx(&fs);
    machine.set_userdata(&ctx);

    // Install handlers
    using namespace handlers;
    machine.install_syscall_handler(nr::exit, sys_exit);
    machine.install_syscall_handler(nr::exit_group, sys_exit_group);
    machine.install_syscall_handler(nr::openat, sys_openat);
    machine.install_syscall_handler(nr::close, sys_close);
    machine.install_syscall_handler(nr::read, sys_read);
    machine.install_syscall_handler(nr::write, sys_write);
    machine.install_syscall_handler(nr::writev, sys_writev);
    machine.install_syscall_handler(nr::lseek, sys_lseek);
    machine.install_syscall_handler(nr::getdents64, sys_getdents64);
    machine.install_syscall_handler(nr::newfstatat, sys_newfstatat);
    machine.install_syscall_handler(nr::fstat, sys_fstat);
    machine.install_syscall_handler(nr::readlinkat, sys_readlinkat);
    machine.install_syscall_handler(nr::getcwd, sys_getcwd);
    machine.install_syscall_handler(nr::chdir, sys_chdir);
    machine.install_syscall_handler(nr::faccessat, sys_faccessat);
    machine.install_syscall_handler(nr::getpid, sys_getpid);
    machine.install_syscall_handler(nr::getppid, sys_getppid);
    machine.install_syscall_handler(nr::gettid, sys_gettid);
    machine.install_syscall_handler(nr::getuid, sys_getuid);
    machine.install_syscall_handler(nr::geteuid, sys_geteuid);
    machine.install_syscall_handler(nr::getgid, sys_getgid);
    machine.install_syscall_handler(nr::getegid, sys_getegid);
    machine.install_syscall_handler(nr::set_tid_address, sys_set_tid_address);
    machine.install_syscall_handler(nr::set_robust_list, sys_set_robust_list);
    machine.install_syscall_handler(nr::clock_gettime, sys_clock_gettime);
    machine.install_syscall_handler(nr::getrandom, sys_getrandom);
    machine.install_syscall_handler(nr::clone, sys_clone);
    machine.install_syscall_handler(nr::execve, sys_execve);
    machine.install_syscall_handler(nr::wait4, sys_wait4);
    // brk: save built-in handler, then override for post-execve memory layout
    libriscv_brk_handler = Machine::syscall_handlers[nr::brk];
    machine.install_syscall_handler(nr::brk, sys_brk);
    // mmap: save built-in handler, then override for file-backed mappings + custom bump allocator
    libriscv_mmap_handler = Machine::syscall_handlers[nr::mmap];
    machine.install_syscall_handler(nr::mmap, sys_mmap);
    // mprotect: override for thread stacks + RELRO safety
    machine.install_syscall_handler(nr::mprotect, sys_mprotect);
    machine.install_syscall_handler(nr::sigaction, sys_sigaction);
    machine.install_syscall_handler(nr::sigprocmask, sys_sigprocmask);
    machine.install_syscall_handler(nr::prlimit64, sys_prlimit64);
    machine.install_syscall_handler(nr::getrlimit, sys_getrlimit);
    machine.install_syscall_handler(nr::rseq, sys_rseq);
    machine.install_syscall_handler(nr::ioctl, sys_ioctl);
    machine.install_syscall_handler(nr::fcntl, sys_fcntl);
    machine.install_syscall_handler(nr::dup, sys_dup);
    machine.install_syscall_handler(nr::dup3, sys_dup3);
    machine.install_syscall_handler(nr::pipe2, sys_pipe2);
    machine.install_syscall_handler(nr::readv, sys_readv);
    machine.install_syscall_handler(nr::ppoll, sys_ppoll);
    machine.install_syscall_handler(nr::sendfile, sys_sendfile);
    machine.install_syscall_handler(nr::pread64, sys_pread64);
    machine.install_syscall_handler(nr::pwrite64, sys_pwrite64);
    machine.install_syscall_handler(nr::ftruncate, sys_ftruncate);
    machine.install_syscall_handler(nr::mkdirat, sys_mkdirat);
    machine.install_syscall_handler(nr::unlinkat, sys_unlinkat);
    machine.install_syscall_handler(nr::symlinkat, sys_symlinkat);
    machine.install_syscall_handler(nr::linkat, sys_linkat);
    machine.install_syscall_handler(nr::renameat, sys_renameat);
    machine.install_syscall_handler(nr::sysinfo, sys_sysinfo);

    // epoll — libuv event loop
    machine.install_syscall_handler(nr::epoll_create1, sys_epoll_create1);
    machine.install_syscall_handler(nr::epoll_ctl, sys_epoll_ctl);
    machine.install_syscall_handler(nr::epoll_pwait, sys_epoll_pwait);

    // futex — thread synchronization
    machine.install_syscall_handler(nr::futex, sys_futex);

    // statx — extended stat
    machine.install_syscall_handler(nr::statx, sys_statx);

    // uname — system identification
    machine.install_syscall_handler(nr::uname, sys_uname);

    // nanosleep
    machine.install_syscall_handler(nr::nanosleep, sys_nanosleep);

    // Stubs
    machine.install_syscall_handler(nr::madvise, sys_madvise);
    machine.install_syscall_handler(nr::prctl, sys_prctl);
    machine.install_syscall_handler(nr::mremap, sys_mremap);
    machine.install_syscall_handler(nr::eventfd2, sys_eventfd2);
    machine.install_syscall_handler(nr::io_uring_setup, sys_io_uring_setup);
    machine.install_syscall_handler(nr::capget, sys_capget);
    machine.install_syscall_handler(nr::sched_getscheduler, sys_sched_getscheduler);
    machine.install_syscall_handler(nr::sched_getparam, sys_sched_getparam);
    machine.install_syscall_handler(nr::sched_getaffinity, sys_sched_getaffinity);

    // Round 2: discovered from strace of curl/git/python/vim/bash/ssh
    machine.install_syscall_handler(nr::umask, sys_umask);
    machine.install_syscall_handler(nr::getpgid, sys_getpgid);
    machine.install_syscall_handler(nr::getresuid, sys_getresuid);
    machine.install_syscall_handler(nr::getresgid, sys_getresgid);
    machine.install_syscall_handler(nr::sigaltstack, sys_sigaltstack);
    machine.install_syscall_handler(nr::clock_getres, sys_clock_getres);
    machine.install_syscall_handler(nr::membarrier, sys_membarrier);
    machine.install_syscall_handler(nr::faccessat2, sys_faccessat2);
    machine.install_syscall_handler(nr::recvmsg, sys_recvmsg);

    // Round 3: Go echo + Next.js build
    machine.install_syscall_handler(nr::flock, sys_flock);
    machine.install_syscall_handler(nr::fsync, sys_fsync);
    machine.install_syscall_handler(nr::fchmod, sys_fchmod);
    machine.install_syscall_handler(nr::fchmodat, sys_fchmodat);
    machine.install_syscall_handler(nr::fchownat, sys_fchownat);
    machine.install_syscall_handler(nr::getgroups, sys_getgroups);
    machine.install_syscall_handler(nr::kill, sys_kill);
    machine.install_syscall_handler(nr::tkill, sys_tkill);
    machine.install_syscall_handler(nr::tgkill, sys_tkill);  // same as tkill
    machine.install_syscall_handler(nr::sched_yield, sys_sched_yield);
    machine.install_syscall_handler(nr::close_range, sys_close_range);
    machine.install_syscall_handler(nr::rt_sigreturn, sys_rt_sigreturn);
    machine.install_syscall_handler(nr::pwritev, sys_pwritev);
    machine.install_syscall_handler(nr::socketpair, sys_socketpair);
    machine.install_syscall_handler(nr::sendmsg, sys_sendmsg);

    // Round 4: Node.js startup
    machine.install_syscall_handler(nr::getsockopt, sys_getsockopt);
    machine.install_syscall_handler(nr::riscv_hwprobe, sys_riscv_hwprobe);
}

}  // namespace syscalls