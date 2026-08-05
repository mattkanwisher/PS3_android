// Enumerates which arm64 system/extension instructions the Android
// emulator's binary translator (ndk_translation) accepts. Every candidate
// runs in a fork()ed child so a SIGILL kills only that child; the parent
// prints one PROBE line per item. Build as a static executable:
//
//   aarch64-linux-android29-clang -static -O1 -o translator-probe ci/translator-probe.c
//
// and run via `adb shell /data/local/tmp/translator-probe`.

#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/wait.h>
#include <unistd.h>

static uint8_t g_buf[256] __attribute__((aligned(64)));

#define PROBE(name, ...)                                                     \
	do {                                                                     \
		fflush(stdout);                                                      \
		pid_t pid = fork();                                                  \
		if (pid == 0) {                                                      \
			__VA_ARGS__;                                                     \
			_exit(0);                                                        \
		}                                                                    \
		int st = 0;                                                          \
		waitpid(pid, &st, 0);                                                \
		if (WIFEXITED(st) && WEXITSTATUS(st) == 0)                           \
			printf("PROBE %-24s ok\n", name);                                \
		else if (WIFSIGNALED(st))                                            \
			printf("PROBE %-24s DIED sig=%d\n", name, WTERMSIG(st));         \
		else                                                                 \
			printf("PROBE %-24s exit=%d\n", name, WEXITSTATUS(st));          \
	} while (0)

int main(void)
{
	uint64_t v = 0;
	(void)v;

	// System registers rpcs3 + its deps read (util/tsc.hpp, util/sysinfo.cpp,
	// util/simd.hpp, sse2neon.h, bionic/compiler-rt cache maintenance).
	PROBE("mrs cntvct_el0", asm volatile("mrs %0, cntvct_el0" : "=r"(v)));
	PROBE("mrs cntfrq_el0", asm volatile("mrs %0, cntfrq_el0" : "=r"(v)));
	PROBE("mrs fpcr", asm volatile("mrs %0, fpcr" : "=r"(v)));
	PROBE("msr fpcr", asm volatile("msr fpcr, %0" ::"r"(v)));
	PROBE("mrs fpsr", asm volatile("mrs %0, fpsr" : "=r"(v)));
	PROBE("msr fpsr", asm volatile("msr fpsr, %0" ::"r"(v)));
	PROBE("mrs nzcv", asm volatile("mrs %0, nzcv" : "=r"(v)));
	PROBE("mrs tpidr_el0", asm volatile("mrs %0, tpidr_el0" : "=r"(v)));
	PROBE("mrs ctr_el0", asm volatile("mrs %0, ctr_el0" : "=r"(v)));
	PROBE("mrs dczid_el0", asm volatile("mrs %0, dczid_el0" : "=r"(v)));
	PROBE("mrs midr_el1", asm volatile("mrs %0, midr_el1" : "=r"(v)));
	PROBE("mrs id_aa64pfr0_el1", asm volatile("mrs %0, id_aa64pfr0_el1" : "=r"(v)));
	PROBE("mrs id_aa64isar0_el1", asm volatile("mrs %0, id_aa64isar0_el1" : "=r"(v)));

	// Barriers / hints (utils::busy_wait uses sevl/wfe-style waiting).
	PROBE("isb", asm volatile("isb" ::: "memory"));
	PROBE("dmb ish", asm volatile("dmb ish" ::: "memory"));
	PROBE("dsb ish", asm volatile("dsb ish" ::: "memory"));
	PROBE("sevl+wfe", asm volatile("sevl\nwfe" ::: "memory"));
	PROBE("yield", asm volatile("yield" ::: "memory"));

	// Cache maintenance (__builtin___clear_cache sequence used by every JIT).
	PROBE("dc cvau/ic ivau", asm volatile("dc cvau, %0\ndsb ish\nic ivau, %0\ndsb ish\nisb" ::"r"(g_buf) : "memory"));
	PROBE("dc zva", asm volatile("dc zva, %0" ::"r"(g_buf) : "memory"));

	// Exclusives (armv8.0 LL/SC — the compat build's atomic path).
	PROBE("ldaxr/stlxr", asm volatile("1: ldaxr %w0, [%1]\nstlxr w9, %w0, [%1]\ncbnz w9, 1b" : "=&r"(v) : "r"(g_buf) : "w9", "memory"));

	// Known v8.1 extensions — expected to die on this translator; present to
	// confirm the probe methodology itself.
	PROBE("ldset (LSE)", asm volatile(".arch armv8.1-a\nldset %w0, %w0, [%1]" : "+r"(v) : "r"(g_buf) : "memory"));
	PROBE("sqrdmlah (QRDMX)", asm volatile(".arch armv8.1-a\nsqrdmlah v0.8h, v1.8h, v2.8h" ::: "v0"));

	printf("PROBE-DONE\n");
	return 0;
}
