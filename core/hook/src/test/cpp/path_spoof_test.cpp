// host 侧向量测试：直接编译 core/path_spoof.cpp（+ core/path_rules.cpp），不需要 Android SDK/NDK。
//
// 同一份 golden/path_spoof_vectors.tsv 同时约束：合成路径形状、五类虚拟路径改写、
// maps 行三态、/data/user/0 ↔ /data/data 等价族兜底。不依赖 gtest（CI 离线，自写判定）。
//
// 用法：path_spoof_test <path_spoof_vectors.tsv>
#include <cstdio>
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
