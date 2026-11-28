// 库级 GOT patch 作用域判定实现（逐字搬自 native-hook.cpp 的 patch_library_got 四级过滤）。
// 说明见 docs/代码镜像/core/hook/cpp/core/lib_filter.md
#include "lib_filter.h"

#include <cstring>
#include <string>

namespace nextvm {
namespace libfilter {

namespace {

bool has(const char* s, const char* needle) {
    return std::strstr(s, needle) != nullptr;
}

}  // namespace

Verdict classify_lib(const char* lib_name, bool system_libs_enabled) {
    // 原逻辑：lib_name == nullptr 时整段过滤被跳过（照常 patch）
    if (lib_name == nullptr) return {Decision::kPatch, Reason::kNone};

    // 1) 容器自身：宿主包名下的库 或 容器 native 引擎
    if (has(lib_name, "com.nextvm.app") || has(lib_name, "libnextvm")) {
        return {Decision::kSkip, Reason::kContainerSelf};
    }

    // 2) 图形栈：patch 后 shader 编译 abort（六级判据，顺序照搬）
    if (has(lib_name, "libGLES") || has(lib_name, "libEGL") ||
        has(lib_name, "eglSubDriver") || has(lib_name, "libvulkan") ||
        has(lib_name, "vulkan.adreno") || has(lib_name, "libllvm-glnext")) {
        return {Decision::kSkip, Reason::kGraphicsStack};
    }

    // 3) 应用/沙箱库：始终 patch
    if (has(lib_name, "/data/app/") || has(lib_name, "/data/user/") ||
        has(lib_name, "/virtual/")) {
        return {Decision::kPatch, Reason::kAppLib};
    }

    // 4) 其余系统库：由 NS_HOOK_SYSTEM_LIBS 决定
    return {system_libs_enabled ? Decision::kPatch : Decision::kSkip,
            Reason::kSystemLib};
}

bool list_exact(const char* csv, const char* needle) {
    if (csv == nullptr || needle == nullptr || needle[0] == '\0') return false;
    const std::size_t n = std::strlen(needle);
    const char* p = csv;
    while (*p != '\0') {
        const char* comma = std::strchr(p, ',');
        const std::size_t len = (comma != nullptr) ? static_cast<std::size_t>(comma - p)
                                                   : std::strlen(p);
        if (len == n && std::strncmp(p, needle, n) == 0) return true;
        if (comma == nullptr) break;
        p = comma + 1;
    }
    return false;
}

bool list_matches(const char* csv, const char* subject) {
    if (csv == nullptr || subject == nullptr || subject[0] == '\0') return false;
    const char* p = csv;
    while (*p != '\0') {
        const char* comma = std::strchr(p, ',');
        const std::size_t len = (comma != nullptr) ? static_cast<std::size_t>(comma - p)
                                                   : std::strlen(p);
        if (len > 0) {
            const std::string item(p, len);
            if (std::strstr(subject, item.c_str()) != nullptr) return true;
        }
        if (comma == nullptr) break;
        p = comma + 1;
    }
    return false;
}

const char* reason_name(Reason reason) {
    switch (reason) {
        case Reason::kNone:          return "none";
        case Reason::kContainerSelf: return "container_self";
        case Reason::kGraphicsStack: return "graphics_stack";
        case Reason::kAppLib:        return "app_lib";
        case Reason::kSystemLib:     return "system_lib";
    }
    return "unknown";
}

}  // namespace libfilter
}  // namespace nextvm
