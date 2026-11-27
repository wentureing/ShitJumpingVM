// 路径伪装纯逻辑（无 Android / JNI 依赖）—— 供 native-hook.cpp 与 host 单测共用。
//
// 红线：不得 include <jni.h>、<android/*>、<link.h>、<sys/*>。
// 说明见 docs/代码镜像/core/hook/cpp/core/path_spoof.md
#pragma once

#include <cstddef>
#include <string>

#include "path_rules.h"

namespace nextvm {
namespace spoof {

/** maps 行/路径里的 " (deleted)" 后缀（含前导空格）。
 *  长度一律用 kDeletedSuffixLen，**不要手数**（历史上写过 11，导致剥离永不生效）。 */
inline constexpr char kDeletedSuffix[] = " (deleted)";
inline constexpr std::size_t kDeletedSuffixLen = sizeof(kDeletedSuffix) - 1;

/** 合成"正常安装"的 base.apk 路径。 */
std::string synthetic_apk_path(const std::string& guest_pkg);

/** 合成"正常安装"的 lib/arm64/<file_name> 路径。 */
std::string synthetic_lib_path(const std::string& guest_pkg,
                               const std::string& file_name);

/** 把 betray 虚拟布局的路径改写成 stock 等价物；false = 无需改写。
 *  host_fallback=true 表示这是容器/引擎自己的路径，没有 guest 等价物。 */
bool rewrite_virtual_path(const std::string& guest_pkg, const std::string& path,
                          std::string& out, bool* host_fallback = nullptr);

/** maps 行是否可见（命中 hook 框架关键字则丢弃）。 */
bool maps_line_visible(const char* line);

/** 一行 maps 的三态：改写 / 原样 / 丢弃（返回 false）。 */
bool transform_maps_line(const std::string& guest_pkg, const char* line,
                         std::string& out);

/** 规则重定向 + /data/user/0 ↔ /data/data 等价族兜底；空串 = 未命中。 */
std::string redirect_path_with_alias(const path::RuleTable& rules,
                                     const std::string& path);

}  // namespace spoof
}  // namespace nextvm
