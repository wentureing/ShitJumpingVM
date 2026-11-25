// host 侧向量测试：直接编译 core/path_rules.cpp，不需要 Android SDK/NDK。
//
// 与 Kotlin 侧 PathGoldenTest 读**同一份** golden/path_vectors.tsv —— 这是防止
// Java 与 C++ 语义再次分叉的机制。不依赖 gtest（CI 离线，自写 ASSERT）。
//
// 用法：path_rules_test <path_vectors.tsv>
#include <cstdio>
#include <fstream>
#include <iostream>
#include <sstream>
#include <string>
#include <unordered_map>
#include <vector>

#include "path_rules.h"

namespace {

const std::string kPkg = "mark.via";
const std::string kSandbox = "/sandbox/i1";

using nextvm::path::HiddenSet;
using nextvm::path::RuleTable;

struct RuleSet {
    RuleTable rules;
    HiddenSet hidden;
};

// 与 Kotlin 侧 PathGoldenTest.ruleSet() 同规格（同一份规格的两个构造器）
RuleSet makeApp() {
    RuleSet s;
    const std::string p = kPkg;
    const std::string ext = kSandbox + "/sdcard/Android/data/" + p;
    s.rules["/data/data/" + p + "/"] = kSandbox + "/";
    s.rules["/data/user/0/" + p + "/"] = kSandbox + "/";
    s.rules["/storage/emulated/0/Android/data/" + p + "/"] = ext + "/";
    s.rules["/sdcard/Android/data/" + p + "/"] = ext + "/";
    s.rules["/storage/emulated/0/Android/data/" + p] = ext;
    s.rules["/sdcard/Android/data/" + p] = ext;
    return s;
}

RuleSet makeExternal() {
    RuleSet s;
    const std::string root = kSandbox + "/sdcard";
    s.rules["/sdcard/"] = root + "/";
    s.rules["/storage/emulated/0/"] = root + "/";
    s.rules["/mnt/sdcard/"] = root + "/";
    s.rules["/storage/self/primary/"] = root + "/";
    s.rules["/storage/emulated/0"] = root;
    s.rules["/sdcard"] = root;
    return s;
}

RuleSet makeHiddenSu() {
    RuleSet s;
    s.hidden.insert("/system/bin/su");
    s.rules["/system/"] = kSandbox + "/system/";
    return s;
}

const RuleSet* selectSet(const std::string& name,
                         const RuleSet& app,
                         const RuleSet& external,
                         const RuleSet& hiddenSu) {
    if (name == "app_i1") return &app;
    if (name == "external") return &external;
    if (name == "hidden_su") return &hiddenSu;
    return nullptr;
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

std::vector<std::string> splitSpaces(const std::string& line) {
    std::vector<std::string> out;
    std::istringstream ss(line);
    std::string tok;
    while (ss >> tok) out.push_back(tok);
    return out;
}

}  // namespace

int main(int argc, char** argv) {
    if (argc < 2) {
        std::cerr << "usage: path_rules_test <path_vectors.tsv>\n";
        return 2;
    }
    std::ifstream in(argv[1]);
    if (!in) {
        std::cerr << "cannot open " << argv[1] << "\n";
        return 2;
    }

    const RuleSet app = makeApp();
    const RuleSet external = makeExternal();
    const RuleSet hiddenSu = makeHiddenSu();

    int total = 0;
    int failed = 0;
    std::string line;
    while (std::getline(in, line)) {
        if (line.empty() || line[0] == '#') continue;
        const std::vector<std::string> cols = splitTabs(line);
        if (cols.size() < 4) continue;

        const std::string& id = cols[0];
        const std::string& setName = cols[1];
        const std::string& rawPath = cols[2];
        std::string expected = cols[3];
        if (expected == "-") expected = rawPath;
        for (std::size_t i = 0; (i = expected.find("<sandbox>", i)) != std::string::npos; i += kSandbox.size()) {
            expected.replace(i, 9, kSandbox);
        }

        const RuleSet* set = selectSet(setName, app, external, hiddenSu);
        if (set == nullptr) {
            std::cerr << "[SKIP] " << id << " unknown rule_set=" << setName << "\n";
            continue;
        }

        ++total;
        std::string actual;
        if (nextvm::path::is_path_hidden(set->hidden, rawPath)) {
            actual = "/dev/null";
        } else {
            const std::string r = nextvm::path::redirect_path(set->rules, rawPath);
            actual = r.empty() ? rawPath : r;
        }
        if (actual != expected) {
            ++failed;
            std::cerr << "[FAIL] " << id << " set=" << setName << "\n"
                      << "       path=" << rawPath << "\n"
                      << "   expected=" << expected << "\n"
                      << "     actual=" << actual << "\n";
        }
    }

    std::cout << "path_rules_test: " << (total - failed) << "/" << total << " passed\n";
    return failed == 0 ? 0 : 1;
}
