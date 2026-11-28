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

    // 2) 图形栈：patch 后 shader 编译 abort / 渲染 SIGABRT。
    //    2026-11-28 全系统摸底：实际有 40 个图形/渲染库（/system 24 + /system_ext 1 + /vendor 15），
    //    原先只覆盖 6 类关键词，漏掉 34 个（hwui/minikin/harfbuzz/gui/ui/drm/gralloc/adreno 全家/angle/
    //    vivo 私有渲染库）。这些库**不参与文件路径翻译**，patch 它们只有风险、没有收益。
    //    清单见 docs/现状/现状-系统库分类与版本敏感性.md
    if (has(lib_name, "libGLES") || has(lib_name, "libEGL") ||
        has(lib_name, "eglSubDriver") || has(lib_name, "libvulkan") ||
        has(lib_name, "vulkan.") || has(lib_name, "libllvm-glnext") ||
        has(lib_name, "libhwui") || has(lib_name, "libminikin") ||
        has(lib_name, "libharfbuzz") || has(lib_name, "libskia") ||
        has(lib_name, "libgui") || has(lib_name, "libui.so") ||
        has(lib_name, "libdrm") || has(lib_name, "libgralloc") ||
        has(lib_name, "libgsl") || has(lib_name, "libq3dtools") ||
        has(lib_name, "libOpenCL") || has(lib_name, "libadreno") ||
        has(lib_name, "librenderengine") || has(lib_name, "libSurfaceFlinger") ||
        has(lib_name, "libpanglearmor") || has(lib_name, "libgpurenderengine") ||
        has(lib_name, "libgps_lightgbm")) {
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
