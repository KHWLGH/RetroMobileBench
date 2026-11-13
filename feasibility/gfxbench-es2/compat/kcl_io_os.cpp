// GPU packs are verified and extracted to ordinary files by GpuDataPack.java.
#include "kcl_io.h"
#include <sys/stat.h>
void KCL::File::OS_PreOpen() {}
int KCL::File::MkDir(const char *path) { return mkdir(path, 0755); }
int KCL::File::ListDir(std::vector<std::string> &, int, const char *, const char *) { return 0; }
