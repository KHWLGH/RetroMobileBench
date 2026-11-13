#include "platform/platform_android.h"
namespace kishonti { namespace android {
namespace detail { Battery batteryInfo(); }
Platform *Platform::instance() { static Platform platform; return &platform; }
float Platform::batteryTemperature() { return detail::batteryInfo().temperature; }
float Platform::batteryLevel() { return detail::batteryInfo().level; }
bool Platform::batteryCharging() { int status = detail::batteryInfo().status; return status == 2 || status == 5; }
}}
