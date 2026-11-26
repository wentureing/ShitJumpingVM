// path_pattern 的 host 向量测试（g++，无 Android 依赖）。
//
// 与 Kotlin 侧 PathPatternTest 覆盖同一组语义；两边用例名一致，便于对照。
#include <cstdio>
#include <string>

#include "path_pattern.h"

using namespace nextvm::path;

static int g_failures = 0;
static int g_checks = 0;

static void expect(bool cond, const char* what) {
    ++g_checks;
    if (!cond) {
        std::printf("FAIL  %s\n", what);
        ++g_failures;
    }
}

static void expect_eq(const std::string& got, const std::string& want, const char* what) {
    ++g_checks;
    if (got != want) {
        std::printf("FAIL  %s  got=%s want=%s\n", what, got.c_str(), want.c_str());
        ++g_failures;
    }
}

static void glob_cases() {
    expect(glob_match("*.log", "a.log"), "G1 单星匹配后缀");
    expect(!glob_match("*.log", "a.logx"), "G2 后缀不同不匹配");
    expect(glob_match("/data/*/files", "/data/x/files"), "G3 中间星");
    expect(glob_match("/a/*", "/a/b/c"), "G4 星跨 '/'（简化语义，文档已写明）");
    expect(glob_match("*", ""), "G5 星匹配空串");
    expect(!glob_match("/a/*", "/b/c"), "G6 前缀不符不匹配");
    expect(glob_match("/a/**/c", "/a/b/c"), "G7 连续星等价单星");
    expect(!glob_match("abc", "abcd"), "G8 无星时退化为全等");
}

static void prefix_cases() {
    const PathPattern p = parse_pattern("/data/user/0/com.a/");
    expect(p.kind == PatternKind::Prefix, "P1 无星 -> Prefix");
    expect(pattern_matches(p, "/data/user/0/com.a/files"), "P2 前缀命中子路径");
    expect(!pattern_matches(p, "/data/user/0/com.ab/files"), "P3 前缀不得误伤 com.ab");
    expect(pattern_is_root_form(p, "/data/user/0/com.a"), "P4 根形态命中");
    expect(!pattern_is_root_form(p, "/data/user/0/com.a/files"), "P5 非根形态");

    const PathPattern bare = parse_pattern("/storage/emulated/0/Android/data/com.a");
    expect(pattern_matches(bare, "/storage/emulated/0/Android/data/com.a"), "P6 无尾斜杠匹配自身");
    expect(pattern_matches(bare, "/storage/emulated/0/Android/data/com.a/files"), "P7 无尾斜杠仍是前缀（保持既有语义）");
}

static void exact_cases() {
    PathPattern e;
    e.kind = PatternKind::Exact;
    e.text = "/system/bin/su";
    expect(pattern_matches(e, "/system/bin/su"), "E1 精确命中");
    expect(!pattern_matches(e, "/system/bin/sudo"), "E2 精确不误伤 sudo");
    expect(!pattern_matches(e, "/system/bin/su/x"), "E3 精确不匹配子路径");
}

static void glob_kind_cases() {
    const PathPattern g = parse_pattern("/sdcard/*.txt");
    expect(g.kind == PatternKind::Glob, "K1 含星 -> Glob");
    expect(pattern_matches(g, "/sdcard/note.txt"), "K2 Glob 命中");
    expect(pattern_matches(g, "/sdcard/sub/note.txt"), "K3 星跨 '/' 也命中（简化语义）");
}

int main() {
    glob_cases();
    prefix_cases();
    exact_cases();
    glob_kind_cases();
    std::printf("path_pattern: %d checks, %d failures\n", g_checks, g_failures);
    return g_failures == 0 ? 0 : 1;
}
