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
#include <unistd.h>  // usleep
#include "android_io.hpp"

namespace syscalls {

using Machine = riscv::Machine<riscv::RISCV64>;


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
    constexpr int prctl         = 167;
    constexpr int mremap        = 216;
    constexpr int madvise       = 233;
    constexpr int getrandom     = 278;
    constexpr int sigaltstack   = 132;
    constexpr int getresuid     = 148;
    constexpr int getresgid     = 150;
    constexpr int getpgid       = 155;
    constexpr int umask         = 166;
    constexpr int clock_getres  = 114;
    constexpr int recvmsg       = 212;
    constexpr int membarrier    = 283;
    constexpr int statx         = 291;
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

// O_* flags
constexpr int O_RDONLY = 0;
constexpr int O_WRONLY = 1;
constexpr int O_RDWR = 2;
constexpr int O_CREAT = 0100;
constexpr int O_EXCL = 0200;
constexpr int O_TRUNC = 01000;
constexpr int O_APPEND = 02000;
constexpr int O_DIRECTORY = 0200000;
constexpr int O_CLOEXEC = 02000000;

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

// Syscall handlers (static functions, no captures)
namespace handlers {

static void sys_exit(Machine& m) {
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
    m.stop();
    m.set_result(m.template sysarg<int>(0));
}

// clone — cooperative vfork emulation for single-process emulator.
// Saves parent state, returns 0 (child context). When child calls
// exit/exit_group, parent state is restored with child PID as return.
static void sys_clone(Machine& m) {
    if (g_fork.in_child) {
        // Nested fork not supported
        m.set_result(-11);  // -EAGAIN
        return;
    }

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

            // Reset writable data of old main binary
            if (g_exec_ctx.exec_rw_start < g_exec_ctx.exec_rw_end) {
                m.memory.memset(g_exec_ctx.exec_rw_start, 0,
                    g_exec_ctx.exec_rw_end - g_exec_ctx.exec_rw_start);
            }

            // Load new main binary segments at the same PIE base
            uint64_t exec_base = g_exec_ctx.exec_base;
            if (exec_info.type == elf::ET_DYN) {
                auto [lo, hi] = elf::get_load_range(new_binary);
                exec_base = 0x40000;  // standard PIE base
                dynlink::load_elf_segments(m, new_binary, exec_base);
                uint64_t actual_base = exec_base - lo + lo;  // = exec_base
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

            // If the new binary needs a dynamic linker, reload interpreter too
            uint64_t interp_base = g_exec_ctx.interp_base;
            uint64_t interp_entry = g_exec_ctx.interp_entry;

            if (exec_info.is_dynamic && !exec_info.interpreter.empty()) {
                // Load interpreter from VFS
                std::string interp_resolved = resolve_path(fs, exec_info.interpreter);
                auto interp_binary = read_vfs_file(fs, interp_resolved);
                if (interp_binary.empty()) {
                    std::cerr << "[friscy] execve: interpreter not found: "
                              << exec_info.interpreter << "\n";
                    m.set_result(-2);
                    return;
                }

                // Reset old interpreter writable data
                if (g_exec_ctx.interp_rw_start < g_exec_ctx.interp_rw_end) {
                    m.memory.memset(g_exec_ctx.interp_rw_start, 0,
                        g_exec_ctx.interp_rw_end - g_exec_ctx.interp_rw_start);
                }

                // Reload interpreter at same base
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

            // Update exec context
            g_exec_ctx.exec_binary = std::move(new_binary);
            g_exec_ctx.exec_info = exec_info;

            // Set up fresh stack
            uint64_t sp = dynlink::setup_dynamic_stack(
                m, exec_info, interp_base, args,
                g_exec_ctx.env, g_exec_ctx.original_stack_top);

            // Clear registers and jump
            for (int i = 1; i < 32; i++) m.cpu.reg(i) = 0;
            m.cpu.reg(riscv::REG_SP) = sp;
            m.cpu.jump(exec_info.is_dynamic ? interp_entry : exec_info.entry_point);

            std::cout << "[friscy] execve: jumping to 0x" << std::hex
                      << (exec_info.is_dynamic ? interp_entry : exec_info.entry_point)
                      << std::dec << "\n";
            return;  // don't set_result — execve doesn't return on success
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

    int fd = (flags & O_DIRECTORY) ? fs.opendir(path) : fs.open(path, flags);
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
static void sys_gettid(Machine& m) { m.set_result(1); }
static void sys_getuid(Machine& m) { m.set_result(0); }
static void sys_geteuid(Machine& m) { m.set_result(0); }
static void sys_getgid(Machine& m) { m.set_result(0); }
static void sys_getegid(Machine& m) { m.set_result(0); }
static void sys_set_tid_address(Machine& m) { m.set_result(1); }
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

// NOTE: brk, mmap, munmap are handled by libriscv's
// setup_linux_syscalls() + add_mman_syscalls(). Do NOT override them here.

// mprotect — no-op during child execution to prevent RELRO from
// poisoning page permissions and the decoder cache. The child's
// interpreter applies RELRO (read-only relocations) which changes
// page attributes AND decoder cache entries. After parent restore,
// these stale entries cause protection faults we can't easily fix.
// By making mprotect a no-op for the child, pages stay in their
// pre-fork state and the parent can resume cleanly.
static void sys_mprotect(Machine& m) {
    if (g_fork.in_child) {
        m.set_result(0);  // skip RELRO during child
        return;
    }
    // Main process: apply the real page attribute change.
    auto addr = m.sysarg(0);
    auto len  = m.sysarg(1);
    auto prot = m.template sysarg<int>(2);
    riscv::PageAttributes attr;
    attr.read  = (prot & 1) != 0;  // PROT_READ
    attr.write = (prot & 2) != 0;  // PROT_WRITE
    attr.exec  = (prot & 4) != 0;  // PROT_EXEC
    m.memory.set_page_attr(addr, len, attr);
    m.set_result(0);
}

static void sys_sigaction(Machine& m) { m.set_result(0); }
static void sys_sigprocmask(Machine& m) { m.set_result(0); }
static void sys_prlimit64(Machine& m) { m.set_result(0); }
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
            uint8_t termios_buf[60] = {};
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

    // TCSETS/TCSETSW/TCSETSF - set terminal attributes
    // Accept silently for fd 0, 1, 2 so programs can set raw mode etc.
    if (request == 0x5402 || request == 0x5403 || request == 0x5404) {
        if (fd >= 0 && fd <= 2) {
            m.set_result(0);
            return;
        }
    }

    // TCSETS, TCSETSW, TCSETSF - set terminal attributes (accept silently)
    if ((request == 0x5402 || request == 0x5403 || request == 0x5404)) {
        if (fd == 0) {
            m.set_result(-25);  // -ENOTTY (stdin is not a terminal)
            return;
        }
        if (fd == 1 || fd == 2) {
            m.set_result(0);
            return;
        }
    }

    m.set_result(err::NOTSUP);
}

static void sys_fcntl(Machine& m) {
    int cmd = m.template sysarg<int>(1);
    switch (cmd) {
        case 1: case 3:  // F_GETFD, F_GETFL
        case 2: case 4:  // F_SETFD, F_SETFL
            m.set_result(0);
            break;
        default:
            m.set_result(err::INVAL);
    }
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
    // args 2,3: timeout (ignored), sigmask (ignored)

    if (nfds == 0) {
        m.set_result(0);
        return;
    }
    if (nfds > 64) nfds = 64;

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
            // VFS file descriptors are always ready
            revents |= (events & 0x0001); // POLLIN if requested
            if (revents) ready++;
        }

        m.memory.template write<int16_t>(entry_addr + 6, revents);
    }

    if (ready > 0) {
        m.set_result(ready);
    } else if (needs_stdin) {
        // No data on stdin — stop and let JS resume when data arrives
        android_io::waiting_for_stdin.store(true);
        m.cpu.increment_pc(-4);
        m.stop();
    } else {
        // Nothing ready and no stdin to wait for.
        // This happens when the shell polls for signals (SIGCHLD)
        // after a fork+wait cycle. Without stopping, this creates
        // a spin loop consuming billions of instructions.
        // Treat as a stdin-wait so the JS event loop can process.
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
inline int g_next_epoll_fd = 1000;  // Start high to avoid collisions with VFS fds

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
            // VFS fds: pipes may have data, regular files always ready
            auto entry = fs.get_entry(fd);
            if (entry && entry->type == vfs::FileType::Fifo) {
                // Pipe: check if data available
                if ((interest.events & 0x01) && entry->content.size() > 0)
                    revents |= 0x01;
                if (interest.events & 0x04)
                    revents |= 0x04;
            } else {
                // Regular file: always ready
                if (interest.events & 0x01) revents |= 0x01;
                if (interest.events & 0x04) revents |= 0x04;
            }
        }

        if (revents) {
            // struct epoll_event { uint32_t events; [4 pad]; uint64_t data; } = 16 bytes
            uint64_t offset = events_addr + ready * 16;
            m.memory.template write<uint32_t>(offset, revents);
            m.memory.template write<uint32_t>(offset + 4, 0);  // padding
            m.memory.template write<uint64_t>(offset + 8, interest.data);  // caller's data
            ready++;
        }
    }

    if (ready > 0) {
        m.set_result(ready);
    } else if (timeout == 0) {
        // Non-blocking poll, nothing ready
        m.set_result(0);
    } else {
        // Nothing ready, timeout > 0 or -1 (infinite).
        // Yield to JS event loop so stdin data / timers can arrive.
        android_io::waiting_for_stdin.store(true);
        m.cpu.increment_pc(-4);
        m.stop();
    }
}

// ============================================================================
// futex — thread synchronization (single-threaded: mostly no-ops)
// ============================================================================

static void sys_futex(Machine& m) {
    auto uaddr = m.sysarg(0);
    int op = m.template sysarg<int>(1);

    // Mask off FUTEX_PRIVATE_FLAG (128) and FUTEX_CLOCK_REALTIME (256)
    int cmd = op & 0x7f;

    constexpr int FUTEX_WAIT = 0;
    constexpr int FUTEX_WAKE = 1;
    constexpr int FUTEX_WAIT_BITSET = 9;
    constexpr int FUTEX_WAKE_BITSET = 10;

    if (cmd == FUTEX_WAIT || cmd == FUTEX_WAIT_BITSET) {
        // Check if futex word matches expected value
        int32_t expected = m.template sysarg<int>(2);
        int32_t actual = m.memory.template read<int32_t>(uaddr);
        if (actual != expected) {
            m.set_result(-11);  // -EAGAIN
        } else {
            // Single-threaded: no one will wake us. Return -ETIMEDOUT
            // to avoid infinite wait. Caller retries the operation.
            m.set_result(-110);  // -ETIMEDOUT
        }
    } else if (cmd == FUTEX_WAKE || cmd == FUTEX_WAKE_BITSET) {
        // Single-threaded: no waiters to wake
        m.set_result(0);
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

    usleep(static_cast<useconds_t>(ms) * 1000);
    m.set_result(0);
}

// ============================================================================
// Stubs — safe no-ops or ENOSYS returns
// ============================================================================

static void sys_madvise(Machine& m) { m.set_result(0); }
static void sys_prctl(Machine& m) { m.set_result(0); }
static void sys_mremap(Machine& m) { m.set_result(err::NOSYS); }
static void sys_eventfd2(Machine& m) { m.set_result(err::NOSYS); }
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

}  // namespace handlers

// Install all syscall handlers
inline void install_syscalls(Machine& machine, vfs::VirtualFS& fs) {
    // Create and store context
    static SyscallContext ctx(&fs);
    machine.set_userdata(&ctx);

    // Install handlers
    using namespace handlers;
    machine.install_syscall_handler(nr::exit, sys_exit);
    machine.install_syscall_handler(nr::exit_group, sys_exit);
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
    // brk, mmap, munmap: handled by libriscv (do not override)
    // mprotect: override to no-op during child execution (prevent RELRO
    // from poisoning decoder cache / page attrs during fork cycle)
    machine.install_syscall_handler(nr::mprotect, sys_mprotect);
    machine.install_syscall_handler(nr::sigaction, sys_sigaction);
    machine.install_syscall_handler(nr::sigprocmask, sys_sigprocmask);
    machine.install_syscall_handler(nr::prlimit64, sys_prlimit64);
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
}

}  // namespace syscalls