// AEGIS — sidecar нативного падения (SIGSEGV/SIGABRT/SIGBUS/SIGILL/SIGFPE/SIGTRAP/SIGSYS).
//
// Java UncaughtExceptionHandler не видит нативных сигналов в libjingle. Этот
// обработчик пишет отчёт async-signal-safe write(2)+fsync(2) в заранее открытый
// файл, затем отдаёт сигнал предыдущему handler'у (tombstone Android сохраняется).
//
// ПОРЯДОК ЗАПИСИ ВАЖЕН. Каждый блок отдельно сбрасывается на диск, от самого
// надёжного к самому рискованному:
//   1. signal/code/addr/tid/pid/thread   — чистые syscall'ы, не могут упасть;
//   2. regs pc/lr/sp/fp/fault            — чтение ucontext, не может упасть;
//   3. breadcrumb                        — статический буфер, не может упасть;
//   4. frames raw                        — обход FP-цепочки с проверкой границ;
//   5. frames sym                        — dladdr (берёт lock загрузчика, может
//                                          зависнуть → делаем последним).
// Даже если шаг 4/5 убьёт процесс повторно, шаги 1–3 уже на диске, а pc из
// шага 2 достаточно, чтобы через `llvm-addr2line`/`ndk-stack` найти строку.

#include <jni.h>

#include <dlfcn.h>
#include <fcntl.h>
#include <signal.h>
#include <stdint.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <time.h>
#include <ucontext.h>
#include <unistd.h>

#include <android/log.h>

#define AEGIS_DIAG_TAG "AegisDiag"

namespace {

/** Максимум кадров стека в отчёте. Больше не нужно: верхние 24 всегда хватает. */
constexpr int kMaxFrames = 24;

/** Размер одной «хлебной крошки» (последняя операция перед падением). */
constexpr size_t kBreadcrumbSize = 192;

/** Сколько последних крошек держим (кольцевой буфер). */
constexpr int kBreadcrumbSlots = 8;

int g_fd = -1;

struct sigaction g_old_segv {};
struct sigaction g_old_abrt {};
struct sigaction g_old_bus {};
struct sigaction g_old_ill {};
struct sigaction g_old_fpe {};
struct sigaction g_old_trap {};
struct sigaction g_old_sys {};

/**
 * Защита от повторного входа: если обработчик сам упадёт (например, на обходе
 * битой FP-цепочки), второй вход сразу уходит в цепочку к debuggerd, а не
 * зацикливается и не затирает уже записанный отчёт.
 */
volatile sig_atomic_t g_in_handler = 0;

/** Кольцо крошек. Пишется из Java, читается из обработчика сигнала. */
char g_breadcrumbs[kBreadcrumbSlots][kBreadcrumbSize];
volatile sig_atomic_t g_breadcrumb_next = 0;
volatile sig_atomic_t g_breadcrumb_count = 0;

/** Монотонное время установки хука — чтобы видеть аптайм процесса в отчёте. */
struct timespec g_installed_at {};

// --- Примитивы записи (async-signal-safe) -----------------------------------

void write_all(int fd, const char* data, size_t n) {
    while (n > 0) {
        const ssize_t w = write(fd, data, n);
        if (w <= 0) return;
        data += static_cast<size_t>(w);
        n -= static_cast<size_t>(w);
    }
}

void write_cstr(int fd, const char* s) {
    if (s == nullptr) {
        write_all(fd, "null", 4);
        return;
    }
    write_all(fd, s, strlen(s));
}

void write_hex(int fd, unsigned long long value) {
    char buf[18];
    buf[0] = '0';
    buf[1] = 'x';
    const char* digits = "0123456789abcdef";
    for (int i = 0; i < 16; ++i) {
        buf[17 - i] = digits[value & 0xfu];
        value >>= 4;
    }
    write_all(fd, buf, 18);
}

void write_dec(int fd, long long value) {
    if (value == 0) {
        write_all(fd, "0", 1);
        return;
    }
    if (value < 0) {
        write_all(fd, "-", 1);
        value = -value;
    }
    char buf[24];
    int i = 24;
    while (value > 0 && i > 0) {
        buf[--i] = static_cast<char>('0' + (value % 10));
        value /= 10;
    }
    write_all(fd, buf + i, static_cast<size_t>(24 - i));
}

/** Сбрасывает блок на диск: процесс может умереть в следующей инструкции. */
void flush_block(int fd) {
    write_all(fd, "\n", 1);
    fsync(fd);
}

// --- Расшифровка сигналов ---------------------------------------------------

const char* signal_name(int sig) {
    switch (sig) {
        case SIGSEGV: return "SIGSEGV";
        case SIGABRT: return "SIGABRT";
        case SIGBUS: return "SIGBUS";
        case SIGILL: return "SIGILL";
        case SIGFPE: return "SIGFPE";
        case SIGTRAP: return "SIGTRAP";
        case SIGSYS: return "SIGSYS";
        default: return "SIG?";
    }
}

/**
 * `si_code` отвечает на главный вопрос отладки: «это разыменование нуля,
 * нарушение прав, невыровненный доступ или нас кто-то убил извне».
 */
const char* signal_code_name(int sig, int code) {
    if (code == SI_USER) return "SI_USER(kill извне)";
    if (code == SI_QUEUE) return "SI_QUEUE";
    if (code == SI_TKILL) return "SI_TKILL(tgkill извне)";
    switch (sig) {
        case SIGSEGV:
            switch (code) {
                case SEGV_MAPERR: return "SEGV_MAPERR(адрес не отображён)";
                case SEGV_ACCERR: return "SEGV_ACCERR(нет прав на страницу)";
                default: return "SEGV_?";
            }
        case SIGBUS:
            switch (code) {
                case BUS_ADRALN: return "BUS_ADRALN(невыровненный адрес)";
                case BUS_ADRERR: return "BUS_ADRERR(нет физического адреса)";
                case BUS_OBJERR: return "BUS_OBJERR(ошибка устройства)";
                default: return "BUS_?";
            }
        case SIGILL:
            switch (code) {
                case ILL_ILLOPC: return "ILL_ILLOPC(код операции)";
                case ILL_ILLOPN: return "ILL_ILLOPN(операнд)";
                case ILL_ILLADR: return "ILL_ILLADR(режим адресации)";
                case ILL_ILLTRP: return "ILL_ILLTRP(ловушка)";
                case ILL_PRVOPC: return "ILL_PRVOPC(привилегированная операция)";
                default: return "ILL_?";
            }
        case SIGFPE:
            switch (code) {
                case FPE_INTDIV: return "FPE_INTDIV(деление на 0)";
                case FPE_INTOVF: return "FPE_INTOVF(переполнение)";
                default: return "FPE_?";
            }
        case SIGTRAP:
            switch (code) {
                case TRAP_BRKPT: return "TRAP_BRKPT(brk — RTC_CHECK/__builtin_trap)";
                case TRAP_TRACE: return "TRAP_TRACE";
                default: return "TRAP_?";
            }
        case SIGABRT:
            return "ABRT(abort/RTC_FATAL/ART Runtime::Abort)";
        case SIGSYS:
            return "SYS_SECCOMP(запрещённый syscall)";
        default:
            return "?";
    }
}

// --- Регистры из ucontext ---------------------------------------------------

struct CpuState {
    uintptr_t pc = 0;
    uintptr_t lr = 0;
    uintptr_t sp = 0;
    uintptr_t fp = 0;
    bool valid = false;
};

CpuState read_cpu_state(void* ctx) {
    CpuState st;
    if (ctx == nullptr) return st;
    auto* uc = static_cast<ucontext_t*>(ctx);
#if defined(__aarch64__)
    st.pc = static_cast<uintptr_t>(uc->uc_mcontext.pc);
    st.lr = static_cast<uintptr_t>(uc->uc_mcontext.regs[30]);
    st.sp = static_cast<uintptr_t>(uc->uc_mcontext.sp);
    st.fp = static_cast<uintptr_t>(uc->uc_mcontext.regs[29]);
    st.valid = true;
#elif defined(__arm__)
    st.pc = static_cast<uintptr_t>(uc->uc_mcontext.arm_pc);
    st.lr = static_cast<uintptr_t>(uc->uc_mcontext.arm_lr);
    st.sp = static_cast<uintptr_t>(uc->uc_mcontext.arm_sp);
    st.fp = static_cast<uintptr_t>(uc->uc_mcontext.arm_fp);
    st.valid = true;
#elif defined(__x86_64__)
    st.pc = static_cast<uintptr_t>(uc->uc_mcontext.gregs[REG_RIP]);
    st.sp = static_cast<uintptr_t>(uc->uc_mcontext.gregs[REG_RSP]);
    st.fp = static_cast<uintptr_t>(uc->uc_mcontext.gregs[REG_RBP]);
    st.valid = true;
#elif defined(__i386__)
    st.pc = static_cast<uintptr_t>(uc->uc_mcontext.gregs[REG_EIP]);
    st.sp = static_cast<uintptr_t>(uc->uc_mcontext.gregs[REG_ESP]);
    st.fp = static_cast<uintptr_t>(uc->uc_mcontext.gregs[REG_EBP]);
    st.valid = true;
#else
    (void)uc;
#endif
    return st;
}

/**
 * Обход цепочки кадровых указателей. `_Unwind_Backtrace` из обработчика сигнала
 * на arm64 упирается в кадр самого сигнала и отдаёт только наши функции, поэтому
 * идём по FP вручную от регистров упавшего потока.
 *
 * Границы проверяем строго (адрес растёт, выровнен, лежит в пределах 8 МБ от
 * SP) — этого достаточно, чтобы не улететь в SIGSEGV на битом стеке.
 */
int collect_frames(const CpuState& st, uintptr_t* out, int max_frames) {
    int n = 0;
    if (!st.valid) return 0;
    if (st.pc != 0) out[n++] = st.pc;
    if (st.lr != 0 && st.lr != st.pc && n < max_frames) out[n++] = st.lr;

    uintptr_t fp = st.fp;
    const uintptr_t stack_lo = st.sp;
    const uintptr_t stack_hi = st.sp + (8u << 20);
    const uintptr_t word = sizeof(uintptr_t);

    for (int depth = 0; depth < max_frames && n < max_frames; ++depth) {
        if (fp == 0) break;
        if (fp < stack_lo || fp >= stack_hi) break;
        if ((fp & (word - 1)) != 0) break;
        const uintptr_t next_fp = *reinterpret_cast<uintptr_t*>(fp);
        const uintptr_t ret = *reinterpret_cast<uintptr_t*>(fp + word);
        if (ret == 0) break;
        out[n++] = ret;
        // Кадры обязаны расти вверх, иначе цепочка испорчена и дальше идти опасно.
        if (next_fp <= fp) break;
        fp = next_fp;
    }
    return n;
}

// --- Хлебные крошки ---------------------------------------------------------

void write_breadcrumbs(int fd) {
    const int count = static_cast<int>(g_breadcrumb_count);
    if (count <= 0) {
        write_cstr(fd, "breadcrumbs=нет");
        return;
    }
    const int slots = count < kBreadcrumbSlots ? count : kBreadcrumbSlots;
    const int next = static_cast<int>(g_breadcrumb_next);
    write_cstr(fd, "breadcrumbs=");
    write_dec(fd, slots);
    // От самой свежей к самой старой: первая строка — то, что делали в момент смерти.
    for (int i = 1; i <= slots; ++i) {
        int idx = next - i;
        while (idx < 0) idx += kBreadcrumbSlots;
        write_cstr(fd, "\n  -");
        write_dec(fd, i - 1);
        write_cstr(fd, " ");
        g_breadcrumbs[idx][kBreadcrumbSize - 1] = '\0';
        write_cstr(fd, g_breadcrumbs[idx]);
    }
}

// --- Цепочка к предыдущему обработчику --------------------------------------

void chain(int sig, siginfo_t* info, void* ctx, const struct sigaction* old) {
    if (old != nullptr) {
        if (old->sa_flags & SA_SIGINFO) {
            if (old->sa_sigaction != nullptr) {
                old->sa_sigaction(sig, info, ctx);
                return;
            }
        } else if (old->sa_handler != nullptr && old->sa_handler != SIG_IGN) {
            old->sa_handler(sig);
            return;
        }
    }
    signal(sig, SIG_DFL);
    raise(sig);
}

const struct sigaction* old_for(int sig) {
    switch (sig) {
        case SIGSEGV: return &g_old_segv;
        case SIGABRT: return &g_old_abrt;
        case SIGBUS: return &g_old_bus;
        case SIGILL: return &g_old_ill;
        case SIGFPE: return &g_old_fpe;
        case SIGTRAP: return &g_old_trap;
        case SIGSYS: return &g_old_sys;
        default: return nullptr;
    }
}

// --- Сам обработчик ---------------------------------------------------------

void handle(int sig, siginfo_t* info, void* ctx) {
    const int fd = g_fd;
    // Повторный вход (упали внутри обработчика) — отчёт уже на диске, уходим.
    if (fd >= 0 && g_in_handler == 0) {
        g_in_handler = 1;

        // --- Блок 1: что за сигнал и кого убило -----------------------------
        char thread_name[24];
        memset(thread_name, 0, sizeof(thread_name));
        prctl(PR_GET_NAME, reinterpret_cast<unsigned long>(thread_name), 0, 0, 0);
        thread_name[sizeof(thread_name) - 1] = '\0';

        struct timespec now {};
        clock_gettime(CLOCK_MONOTONIC, &now);
        const long long uptime_ms =
            static_cast<long long>(now.tv_sec - g_installed_at.tv_sec) * 1000LL +
            (now.tv_nsec - g_installed_at.tv_nsec) / 1000000LL;

        const int si_code = info != nullptr ? info->si_code : 0;
        write_cstr(fd, "signal=");
        write_cstr(fd, signal_name(sig));
        write_cstr(fd, " signo=");
        write_dec(fd, sig);
        write_cstr(fd, " code=");
        write_dec(fd, si_code);
        write_cstr(fd, " codeName=");
        write_cstr(fd, signal_code_name(sig, si_code));
        write_cstr(fd, " addr=");
        write_hex(fd, info != nullptr ? reinterpret_cast<unsigned long long>(info->si_addr) : 0);
        write_cstr(fd, " tid=");
        write_dec(fd, static_cast<long long>(syscall(SYS_gettid)));
        write_cstr(fd, " pid=");
        write_dec(fd, static_cast<long long>(getpid()));
        write_cstr(fd, " thread='");
        write_cstr(fd, thread_name);
        write_cstr(fd, "' послеУстановкиМс=");
        write_dec(fd, uptime_ms);
        if (si_code == SI_USER || si_code == SI_TKILL) {
            write_cstr(fd, " отправительPid=");
            write_dec(fd, info != nullptr ? static_cast<long long>(info->si_pid) : -1);
            write_cstr(fd, " отправительUid=");
            write_dec(fd, info != nullptr ? static_cast<long long>(info->si_uid) : -1);
        }
        flush_block(fd);

        // --- Блок 2: регистры упавшего потока -------------------------------
        const CpuState st = read_cpu_state(ctx);
        write_cstr(fd, "regs pc=");
        write_hex(fd, st.pc);
        write_cstr(fd, " lr=");
        write_hex(fd, st.lr);
        write_cstr(fd, " sp=");
        write_hex(fd, st.sp);
        write_cstr(fd, " fp=");
        write_hex(fd, st.fp);
        write_cstr(fd, " ctx=");
        write_cstr(fd, st.valid ? "есть" : "нет");
        flush_block(fd);

        // --- Блок 3: что приложение делало прямо перед смертью ---------------
        write_breadcrumbs(fd);
        flush_block(fd);

        // --- Блок 4: сырые адреса кадров ------------------------------------
        uintptr_t frames[kMaxFrames];
        const int frame_count = collect_frames(st, frames, kMaxFrames);
        write_cstr(fd, "framesRaw=");
        write_dec(fd, frame_count);
        for (int i = 0; i < frame_count; ++i) {
            write_cstr(fd, " ");
            write_hex(fd, frames[i]);
        }
        flush_block(fd);

        // --- Блок 5: символизация (dladdr берёт lock загрузчика — последним) --
        for (int i = 0; i < frame_count; ++i) {
            Dl_info dl;
            memset(&dl, 0, sizeof(dl));
            write_cstr(fd, "#");
            write_dec(fd, i);
            write_cstr(fd, " ");
            write_hex(fd, frames[i]);
            if (dladdr(reinterpret_cast<void*>(frames[i]), &dl) != 0 && dl.dli_fname != nullptr) {
                const char* slash = strrchr(dl.dli_fname, '/');
                write_cstr(fd, " ");
                write_cstr(fd, slash != nullptr ? slash + 1 : dl.dli_fname);
                write_cstr(fd, "+");
                write_hex(fd, static_cast<unsigned long long>(
                                  frames[i] - reinterpret_cast<uintptr_t>(dl.dli_fbase)));
                if (dl.dli_sname != nullptr) {
                    write_cstr(fd, " ");
                    write_cstr(fd, dl.dli_sname);
                }
            } else {
                write_cstr(fd, " <нет карты>");
            }
            write_all(fd, "\n", 1);
        }
        fsync(fd);

        __android_log_print(ANDROID_LOG_FATAL, AEGIS_DIAG_TAG,
                            "нативный сигнал %s tid=%ld — отчёт в sidecar",
                            signal_name(sig), static_cast<long>(syscall(SYS_gettid)));
    }
    chain(sig, info, ctx, old_for(sig));
}

// --- Установка --------------------------------------------------------------

bool is_ours(const struct sigaction* sa) {
    return sa != nullptr && (sa->sa_flags & SA_SIGINFO) && sa->sa_sigaction == handle;
}

void install_one(int sig, struct sigaction* old_store) {
    struct sigaction current {};
    sigaction(sig, nullptr, &current);
    struct sigaction sa {};
    sigemptyset(&sa.sa_mask);
    sa.sa_sigaction = handle;
    // SA_ONSTACK: bionic выделяет альтернативный стек каждому потоку, поэтому
    // переполнение стека мы всё ещё поймаем.
    sa.sa_flags = SA_SIGINFO | SA_ONSTACK;
    if (is_ours(&current)) {
        // Уже наш — не затираем сохранённый системный (иначе цепочка замкнётся
        // сама на себя и мы потеряем tombstone).
        sigaction(sig, &sa, nullptr);
        return;
    }
    sigaction(sig, &sa, old_store);
}

void bind_all() {
    install_one(SIGSEGV, &g_old_segv);
    install_one(SIGABRT, &g_old_abrt);
    install_one(SIGBUS, &g_old_bus);
    install_one(SIGILL, &g_old_ill);
    install_one(SIGFPE, &g_old_fpe);
    // RTC_CHECK/__builtin_trap на arm64 — это brk #0 → SIGTRAP, а не SIGABRT.
    install_one(SIGTRAP, &g_old_trap);
    // seccomp-фильтр Android убивает SIGSYS: без хука выглядит как «тихая смерть».
    install_one(SIGSYS, &g_old_sys);
}

}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_aegis_messenger_diag_DiagnosticNative_nativeInstall(JNIEnv* env, jobject, jstring path) {
    if (path == nullptr) return JNI_FALSE;
    const char* utf = env->GetStringUTFChars(path, nullptr);
    if (utf == nullptr) return JNI_FALSE;
    const int fd = open(utf, O_CREAT | O_WRONLY | O_TRUNC | O_CLOEXEC, 0600);
    env->ReleaseStringUTFChars(path, utf);
    if (fd < 0) {
        __android_log_print(ANDROID_LOG_WARN, AEGIS_DIAG_TAG, "не удалось открыть sidecar native-crash");
        return JNI_FALSE;
    }
    if (g_fd >= 0) close(g_fd);
    g_fd = fd;
    memset(g_breadcrumbs, 0, sizeof(g_breadcrumbs));
    g_breadcrumb_next = 0;
    g_breadcrumb_count = 0;
    g_in_handler = 0;
    clock_gettime(CLOCK_MONOTONIC, &g_installed_at);
    bind_all();
    __android_log_print(ANDROID_LOG_INFO, AEGIS_DIAG_TAG, "хук native-сигналов установлен");
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_aegis_messenger_diag_DiagnosticNative_nativeRebind(JNIEnv*, jobject) {
    if (g_fd < 0) return JNI_FALSE;
    bind_all();
    return JNI_TRUE;
}

/**
 * Кладёт «хлебную крошку» в кольцо. Вызывается из Kotlin перед каждым опасным
 * нативным шагом (createPeerConnection / setLocalDescription / TurnPort …).
 * Именно она отвечает на вопрос «на какой операции умерли», когда Java-журнал
 * оборвался на предыдущей строке.
 */
JNIEXPORT void JNICALL
Java_com_aegis_messenger_diag_DiagnosticNative_nativeBreadcrumb(JNIEnv* env, jobject, jstring text) {
    if (text == nullptr) return;
    const char* utf = env->GetStringUTFChars(text, nullptr);
    if (utf == nullptr) return;
    const int slot = static_cast<int>(g_breadcrumb_next) % kBreadcrumbSlots;
    size_t n = strlen(utf);
    if (n >= kBreadcrumbSize) n = kBreadcrumbSize - 1;
    memcpy(g_breadcrumbs[slot], utf, n);
    g_breadcrumbs[slot][n] = '\0';
    env->ReleaseStringUTFChars(text, utf);
    g_breadcrumb_next = static_cast<sig_atomic_t>((slot + 1) % kBreadcrumbSlots);
    if (g_breadcrumb_count < kBreadcrumbSlots) {
        g_breadcrumb_count = static_cast<sig_atomic_t>(g_breadcrumb_count + 1);
    }
}

/** Диагностика самой диагностики: установлен ли хук и открыт ли sidecar. */
JNIEXPORT jint JNICALL
Java_com_aegis_messenger_diag_DiagnosticNative_nativeFd(JNIEnv*, jobject) {
    return static_cast<jint>(g_fd);
}

}  // extern "C"
