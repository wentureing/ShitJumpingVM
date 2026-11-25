// 纯逻辑路径规则（无 Android / JNI 依赖）—— 供 native-hook.cpp 与 host 单测共用。
//
// 红线：本目录**不得** include <jni.h>、<android/*>、<link.h>、<sys/*>，
// 否则 host 侧 CI 无法编译。说明见 docs/架构/架构-C++测试夹具设计.md
#pragma once

#include <cstddef>
#include <string>
#include <unordered_map>
#include <unordered_set>

namespace nextvm {
namespace path {

using RuleTable = std::unordered_map<std::string, std::string>;
using HiddenSet = std::unordered_set<std::string>;

/** 去掉尾部所有 '/'（空串原样返回）。 */
std::string trim_trailing_slashes(const std::string& s);

/** 隐藏是**精确匹配**（/system/bin/su 不连累 sudo）。 */
bool is_path_hidden(const HiddenSet& hidden, const std::string& path);

/**
 * 规则匹配与拼接。返回空串表示未命中（调用方应原样使用输入）。
 *
 * 规则是前缀；以 '/' 结尾的规则还"拥有自己的根形态"（去掉尾斜杠的那个字符串）。
 * 有效长度最长者胜，平局时真正的前缀优先。拼接规则与 Java 侧
 * NativeHookBridge.translatePath 的四分支逐字一致。
 */
std::string redirect_path(const RuleTable& rules, const std::string& path);

}  // namespace path
}  // namespace nextvm
