// Minimal entry point for the M1 GFXBench ES2 feasibility build.
//
// Links the GFXBench ES2 tests into one shared library and exposes a function that
// resolves a test factory the same way testfw does (create_test_<name>). The point of
// this file is only to force the linker to pull everything in and to prove the
// factories exist; running a test needs a GL context and data, which comes later.

#include "testfw.h"

#include <dlfcn.h>

extern "C" {
tfw::TestFactory::Holder create_test_gl_trex();
tfw::TestFactory::Holder create_test_gl_trex_off();
tfw::TestFactory::Holder create_test_gl_egypt();
tfw::TestFactory::Holder create_test_gl_egypt_off();
tfw::TestFactory::Holder create_test_gl_alu();
tfw::TestFactory::Holder create_test_gl_alu_off();
tfw::TestFactory::Holder create_test_gl_fill();
tfw::TestFactory::Holder create_test_gl_fill_off();
tfw::TestFactory::Holder create_test_gl_driver();
tfw::TestFactory::Holder create_test_gl_driver_off();
tfw::TestFactory::Holder create_test_gl_blending();
tfw::TestFactory::Holder create_test_gl_blending_off();
}

typedef tfw::TestFactory::Holder (*FactoryFn)();

// Static table: on Android 4.1/4.2 dlopen()/dlsym() by library name is unreliable for
// app libraries (docs/04-gfxbench-port.md §4.3), so the final app will use a table like this.
extern "C" __attribute__((visibility("default"))) int rmb_gfx_factory_count() {
    static const FactoryFn table[] = {
        create_test_gl_trex,  create_test_gl_trex_off,  create_test_gl_egypt,    create_test_gl_egypt_off,
        create_test_gl_alu,   create_test_gl_alu_off,   create_test_gl_fill,     create_test_gl_fill_off,
        create_test_gl_driver, create_test_gl_driver_off, create_test_gl_blending, create_test_gl_blending_off,
    };
    int n = 0;
    for (FactoryFn f : table) {
        if (f) n++;
    }
    return n;
}
