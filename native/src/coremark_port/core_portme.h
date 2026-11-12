/* RetroMobileBench Android port. Upstream algorithm sources are unmodified. */
#ifndef CORE_PORTME_H
#define CORE_PORTME_H
#include <stdint.h>
#include <stddef.h>
#include <pthread.h>
#define HAS_FLOAT 1
#define HAS_TIME_H 1
#define HAS_STDIO 1
#define HAS_PRINTF 0
#define SEED_METHOD SEED_VOLATILE
#define MEM_METHOD MEM_STACK
#define MULTITHREAD 4
#define MAIN_HAS_NOARGC 1
#define MAIN_HAS_NORETURN 0
#define PERFORMANCE_RUN 1
#define COMPILER_VERSION __VERSION__
#define COMPILER_FLAGS RMB_CORE_FLAGS
#define MEM_LOCATION "Code in RAM, data on stack"
#define PARALLEL_METHOD "PThreads"
#define main rmb_coremark_entry
/* Microsecond ticks keep the upstream %lu log meaningful on 32-bit Android. */
typedef uint32_t CORE_TICKS;
typedef int16_t ee_s16;
typedef uint16_t ee_u16;
typedef int32_t ee_s32;
typedef uint32_t ee_u32;
typedef uint8_t ee_u8;
typedef double ee_f32;
typedef uintptr_t ee_ptr_int;
typedef size_t ee_size_t;
#define align_mem(x) (void *)(4 + (((ee_ptr_int)(x)-1) & ~(ee_ptr_int)3))
typedef struct { pthread_t thread; int started; ee_u8 portable_id; } core_portable;
extern ee_u32 default_num_contexts;
void portable_init(core_portable *p, int *argc, char *argv[]);
void portable_fini(core_portable *p);
int ee_printf(const char *format, ...);
#endif
