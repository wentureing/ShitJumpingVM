// 库级 GOT patch 作用域判定（纯逻辑，无 Android / JNI 依赖）。
//
// 判据与顺序逐字搬自 native-hook.cpp 的 patch_library_got（四级过滤）。
// 说明见 docs/代码镜像/core/hook/cpp/core/lib_filter.md
#pragma once

namespace nextvm {
namespace libfilter {

enum class Decision {
    kPatch,
    kSkip,
};

enum class Reason {
    kNone,          // lib_name == nullptr：原逻辑不做过滤
    kContainerSelf, // 宿主包名下的库 / 容器 native 引擎
    kGraphicsStack, // patch 后 shader 编译 abort
    kAppLib,        // /data/app、/data/user、/virtual
    kSystemLib,     // 其余系统库，由 NS_HOOK_SYSTEM_LIBS 决定
};

struct Verdict {
    Decision decision;
    Reason reason;
};

/** 判定一个库要不要 GOT patch。system_libs_enabled 对应 NS_HOOK_SYSTEM_LIBS。 */
Verdict classify_lib(const char* lib_name, bool system_libs_enabled);

/** 逗号分隔列表里是否有"某一项出现在 subject 中"（用于短库名 vs 长路径）。 */
bool list_matches(const char* csv, const char* subject);

/** 逗号分隔列表里是否有与 needle 完全相等的一项（符号名有前缀包含：open ⊂ openat）。 */
bool list_exact(const char* csv, const char* needle);

const char* reason_name(Reason reason);

}  // namespace libfilter
}  // namespace nextvm
