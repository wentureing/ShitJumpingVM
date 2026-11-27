// host 侧向量测试：直接编译 core/path_spoof.cpp（+ core/path_rules.cpp），不需要 Android SDK/NDK。
//
// 同一份 golden/path_spoof_vectors.tsv 同时约束：合成路径形状、五类虚拟路径改写、
// maps 行三态、/data/user/0 ↔ /data/data 等价族兜底。不依赖 gtest（CI 离线，自写判定）。
//
// 用法：path_spoof_test <path_spoof_vectors.tsv>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <iostream>
#include <string>
#include <vector>

#include "path_rules.h"
#include "path_spoof.h"

namespace {

const std::string kSandbox = "/sandbox/i1";

// 只注册 /data/data/<pkg>/ 与 /sdcard/：故意**不**注册 /data/user/0/<pkg>/，
// 这样 redirect_alias 的等价族兜底才会被真的走到。
nextvm::path::RuleTable makeRules() {
    nextvm::path::RuleTable rules;
    rules["/data/data/mark.via/"] = kSandbox + "/";
    rules["/sdcard/"] = kSandbox + "/sdcard/";
    return rules;
}

std::vector<std::string> splitTabs(const std::string& line) {
    std::vector<std::string> out;
    std::string cur;
    for (char c : line) {
        if (c == '\t') {
            out.push_back(cur);
            cur.clear();
        } else {
            cur.push_back(c);
        }
    }
    out.push_back(cur);
    return out;
}

std::string stripTrailingNewline(std::string s) {
    while (!s.empty() && (s.back() == '\n' || s.back() == '\r')) s.pop_back();
    return s;
}

// 日志里每步都打印，超长值截断（maps 行动辄 120+ 字符）
std::string shorten(const std::string& s) {
    constexpr std::size_t kMax = 110;
    if (s.size() <= kMax) return s;
    return s.substr(0, kMax) + "…";
}

}  // namespace

int main(int argc, char** argv) {
    if (argc < 2) {
        std::cerr << "usage: path_spoof_test <path_spoof_vectors.tsv>\n";
        return 2;
    }
    std::ifstream in(argv[1]);
    if (!in) {
        std::cerr << "cannot open " << argv[1] << "\n";
        return 2;
    }

    const nextvm::path::RuleTable rules = makeRules();
    int total = 0;
    int failed = 0;

    // 不变量：与向量文件无关的**结构性约束**，防止"向量只覆盖已想到的输入"。
    auto checkInv = [&](const char* name, bool ok, const std::string& detail) {
        ++total;
        if (ok) {
            std::cout << "[ok] invariant " << name << " " << shorten(detail) << "\n";
        } else {
            ++failed;
            std::cerr << "[FAIL] invariant " << name << " " << detail << "\n";
        }
    };

    {
        // 合成 apk 路径：必须带 guest 包名、不得泄漏容器特征（nextvm）
        const std::string apk = nextvm::spoof::synthetic_apk_path("mark.via");
        checkInv("synthetic_apk_uses_pkg",
                 apk.find("mark.via") != std::string::npos,
                 apk);
        checkInv("synthetic_apk_no_host_leak",
                 apk.find("nextvm") == std::string::npos,
                 apk);

        // 合成 lib 路径：包名 + lib/arm64/<file> 两段都要在
        // 注意：长度不手数 —— 直接用期待后缀的 size()，避免同类"数错常数"的坑
        const std::string lib = nextvm::spoof::synthetic_lib_path("com.sandbox.probe", "x.so");
        const std::string lib_tail = "/lib/arm64/x.so";
        checkInv("synthetic_lib_shape",
                 lib.find("com.sandbox.probe") != std::string::npos &&
                     lib.size() >= lib_tail.size() &&
                     lib.compare(lib.size() - lib_tail.size(), lib_tail.size(), lib_tail) == 0,
                 lib);

        // 空串 maps 行不得被丢弃（否则 maps 尾部/异常行会被吞）
        checkInv("maps_empty_line_visible", nextvm::spoof::maps_line_visible(""), "(empty)");

        // 空路径不改写，且不写 out（避免调用方拿到半成品）
        std::string out = "sentinel";
        const bool rewritten = nextvm::spoof::rewrite_virtual_path("mark.via", "", out);
        checkInv("rewrite_empty_path_unchanged",
                 !rewritten && out == "sentinel", out);

        // 空规则表 → 即使命中 /data/user/0 前缀也不得凭空造出结果
        const nextvm::path::RuleTable empty;
        checkInv("alias_empty_rules_miss",
                 nextvm::spoof::redirect_path_with_alias(empty, "/data/user/0/mark.via/x").empty(),
                 "(empty table)");

        // ---- 针对 (deleted) 缺陷的回归（历史上长度手写成 11，剥离永不生效）----
        // 1) 常量自洽：长度必须等于后缀字符串长度（防止"改字符串不改长度"的第二形态）
        checkInv("deleted_suffix_len_matches_text",
                 nextvm::spoof::kDeletedSuffixLen ==
                     std::strlen(nextvm::spoof::kDeletedSuffix),
                 std::to_string(nextvm::spoof::kDeletedSuffixLen));
        // 2) 行为：剥离必须生效、后缀必须原样出现在输出里，且**不得**被判 host_fallback
        {
            const std::string in =
                "/data/user/0/com.nextvm.app/files/virtual/apks/mark.via_1.apk" +
                std::string(nextvm::spoof::kDeletedSuffix);
            std::string out2;
            bool hf = true;
            const bool rew = nextvm::spoof::rewrite_virtual_path("mark.via", in, out2, &hf);
            checkInv("deleted_suffix_stripped",
                     rew && !hf &&
                         out2 == nextvm::spoof::synthetic_apk_path("mark.via") +
                                     nextvm::spoof::kDeletedSuffix,
                     out2);
        }
        // 3) maps 层：同一行必须改写（保留后缀），不得被丢弃
        {
            const std::string in =
                "7f0000000000-7f0000001000 r--p 00000000 fd:00 123 "
                "/data/user/0/com.nextvm.app/files/virtual/apks/mark.via_1.apk" +
                std::string(nextvm::spoof::kDeletedSuffix);
            std::string out3;
            const bool kept = nextvm::spoof::transform_maps_line("mark.via", in.c_str(), out3);
            checkInv("deleted_suffix_maps_kept",
                     kept &&
                         stripTrailingNewline(out3) ==
                             "7f0000000000-7f0000001000 r--p 00000000 fd:00 123 " +
                                 nextvm::spoof::synthetic_apk_path("mark.via") +
                                 nextvm::spoof::kDeletedSuffix,
                     stripTrailingNewline(out3));
        }
    }

    std::string line;
    while (std::getline(in, line)) {
        if (line.empty() || line[0] == '#') continue;
        const std::vector<std::string> cols = splitTabs(line);
        if (cols.size() < 4) continue;

        const std::string& op = cols[0];
        const std::string& pkg = cols[1];
        const std::string& input = cols[2];
        const std::string& expected = cols[3];
        const std::string flag = cols.size() > 4 ? cols[4] : "";

        ++total;
        std::string actual;
        std::string why;

        if (op == "synthetic_apk") {
            actual = nextvm::spoof::synthetic_apk_path(pkg);
        } else if (op == "synthetic_lib") {
            actual = nextvm::spoof::synthetic_lib_path(pkg, input);
        } else if (op == "rewrite") {
            std::string out;
            bool host_fallback = false;
            const bool rewritten =
                nextvm::spoof::rewrite_virtual_path(pkg, input, out, &host_fallback);
            if (expected == "unchanged") {
                actual = rewritten ? out : "unchanged";
                if (rewritten) why = "期望不改写，实际改写了";
            } else if (!rewritten) {
                actual = "unchanged";
                why = "期望改写，实际未命中";
            } else {
                actual = out;
                if (flag == "host_fallback" && !host_fallback) {
                    why = "期望 host_fallback=true，实际 false";
                }
            }
        } else if (op == "hidden") {
            actual = nextvm::spoof::maps_line_visible(input.c_str()) ? "keep" : "drop";
        } else if (op == "transform") {
            std::string out;
            const bool kept = nextvm::spoof::transform_maps_line(pkg, input.c_str(), out);
            if (!kept) {
                actual = "drop";
            } else if (expected == "keep") {
                actual = stripTrailingNewline(out) == input ? "keep" : stripTrailingNewline(out);
            } else {
                actual = stripTrailingNewline(out);
            }
        } else if (op == "redirect_alias") {
            const std::string r = nextvm::spoof::redirect_path_with_alias(rules, input);
            actual = r.empty() ? "unchanged" : r;
        } else {
            --total;
            std::cerr << "[SKIP] unknown op=" << op << "\n";
            continue;
        }

        if (actual != expected) {
            ++failed;
            std::cerr << "[FAIL] " << op << " pkg=" << pkg << "\n"
                      << "        input=" << input << "\n"
                      << "     expected=" << expected << "\n"
                      << "       actual=" << actual;
            if (!why.empty()) std::cerr << "\n        (" << why << ")";
            std::cerr << "\n";
        } else {
            // 每一步都留痕：CI 日志能直接看出哪条用例固定了哪个行为
            std::cout << "[ok] " << op;
            if (pkg != "-") std::cout << " pkg=" << pkg;
            std::cout << " input=" << shorten(input) << " -> " << shorten(actual);
            if (!flag.empty()) std::cout << " [" << flag << "]";
            std::cout << "\n";
        }
    }

    std::cout << "path_spoof_test: " << (total - failed) << "/" << total << " passed\n";
    return failed == 0 ? 0 : 1;
}
