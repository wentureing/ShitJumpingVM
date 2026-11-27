// 路径伪装纯逻辑实现（从 native-hook.cpp 逐字搬出，包名参数化）。
// 说明见 docs/代码镜像/core/hook/cpp/core/path_spoof.md
#include "path_spoof.h"

#include <cstring>

namespace nextvm {
namespace spoof {

namespace {

// 合成路径里固定的安装 token：只要求"看起来像正常安装"，不要求唯一。
constexpr const char* kInstallToken = "VmK8x2==";

}  // namespace

std::string synthetic_apk_path(const std::string& guest_pkg) {
    return "/data/app/~~" + std::string(kInstallToken) + "/" + guest_pkg + "-" +
           kInstallToken + "/base.apk";
}

std::string synthetic_lib_path(const std::string& guest_pkg,
                               const std::string& file_name) {
    return "/data/app/~~" + std::string(kInstallToken) + "/" + guest_pkg + "-" +
           kInstallToken + "/lib/arm64/" + file_name;
}

bool rewrite_virtual_path(const std::string& guest_pkg, const std::string& path,
                          std::string& out, bool* host_fallback) {
    if (host_fallback != nullptr) *host_fallback = false;
    std::string core = path;
    std::string suffix;
    if (core.size() >= kDeletedSuffixLen &&
        core.compare(core.size() - kDeletedSuffixLen, kDeletedSuffixLen,
                     kDeletedSuffix) == 0) {
        core.erase(core.size() - kDeletedSuffixLen);
        suffix = kDeletedSuffix;
    }

    // Guest APK 文件：base + split（虚拟存储里 split 名为
    // "<instanceId>_split_config.<...>.apk"，stock 形态是 base.apk 旁边的纯 split 名）。
    if (core.find("/files/virtual/apks/") != std::string::npos &&
        core.size() >= 4 && core.compare(core.size() - 4, 4, ".apk") == 0) {
        std::string fname = core.substr(core.find_last_of('/') + 1);
        size_t split_pos = fname.find("split_");
        if (fname.find("_split_") != std::string::npos &&
            split_pos != std::string::npos) {
            out = "/data/app/~~" + std::string(kInstallToken) + "/" + guest_pkg +
                  "-" + kInstallToken + "/" + fname.substr(split_pos) + suffix;
        } else {
            out = synthetic_apk_path(guest_pkg) + suffix;
        }
        return true;
    }

    // Guest 数据树：抽出的 lib 走合成 lib 目录，其余镜像到 /data/data/<pkg>/。
    const std::string data_marker = "/files/virtual/data/";
    size_t data_pos = core.find(data_marker);
    if (data_pos != std::string::npos) {
        size_t inst_end = core.find('/', data_pos + data_marker.size());
        if (inst_end != std::string::npos) {
            std::string rest = core.substr(inst_end + 1);
            if (rest.compare(0, 4, "lib/") == 0) {
                out = synthetic_lib_path(
                          guest_pkg, rest.substr(rest.find_last_of('/') + 1)) +
                      suffix;
            } else {
                out = "/data/data/" + guest_pkg + "/" + rest + suffix;
            }
            return true;
        }
    }

    // 其余提到容器/hook 引擎自身的路径：没有 guest 等价物。
    if (core.find("nextvm") != std::string::npos) {
        if (host_fallback != nullptr) *host_fallback = true;
        out = synthetic_apk_path(guest_pkg) + suffix;
        return true;
    }
    return false;
}

bool maps_line_visible(const char* line) {
    static const char* kHiddenKeywords[] = {
        "nextvm", "lsplant", "dobby", "bhook", "xhook", "substrate", "xposed"
    };
    for (const char* kw : kHiddenKeywords) {
        if (strstr(line, kw) != nullptr) return false;
    }
    return true;
}

bool transform_maps_line(const std::string& guest_pkg, const char* line,
                         std::string& out) {
    const char* slash = strchr(line, '/');
    if (slash != nullptr) {
        std::string prefix(line, static_cast<size_t>(slash - line));
        std::string core(slash);
        while (!core.empty() && (core.back() == '\n' || core.back() == '\r')) {
            core.pop_back();
        }

        std::string rewritten;
        bool host_fallback = false;
        if (rewrite_virtual_path(guest_pkg, core, rewritten, &host_fallback)) {
            // 容器/引擎自己的映射在 stock 进程里不存在 —— 丢弃。
            if (host_fallback) return false;
            out = prefix + rewritten + "\n";
            return true;
        }
    }

    // hook 框架关键字（含 [anon:...] 映射名）—— 丢弃。
    if (!maps_line_visible(line)) return false;

    out = line;
    return true;
}

std::string redirect_path_with_alias(const path::RuleTable& rules,
                                     const std::string& path) {
    std::string redirected = path::redirect_path(rules, path);
    if (!redirected.empty()) return redirected;

    // 等价族兜底：/data/user/0/ 与 /data/data/ 指向同一目录。
    if (path.compare(0, 13, "/data/user/0/") == 0) {
        return path::redirect_path(rules, "/data/data/" + path.substr(13));
    }
    return "";
}

}  // namespace spoof
}  // namespace nextvm
