// host 侧向量测试：直接编译 core/lib_filter.cpp（不需要 Android SDK/NDK）。
//
// 固定的是"哪些库会被 GOT patch、因为哪一条判据"—— 这块历史上是渲染崩溃 /
// popup 闪退的来源（图形栈被误 patch、系统库范围放大），此前零测试。
//
// 用法：lib_filter_test <lib_filter_vectors.tsv>
#include <cstdio>
#include <fstream>
#include <iostream>
#include <string>
#include <vector>

#include "lib_filter.h"

namespace {

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

const char* decision_name(nextvm::libfilter::Decision d) {
    return d == nextvm::libfilter::Decision::kPatch ? "patch" : "skip";
}

}  // namespace

int main(int argc, char** argv) {
    if (argc < 2) {
        std::cerr << "usage: lib_filter_test <lib_filter_vectors.tsv>\n";
        return 2;
    }
    std::ifstream in(argv[1]);
    if (!in) {
        std::cerr << "cannot open " << argv[1] << "\n";
        return 2;
    }

    int total = 0;
    int failed = 0;
    std::string line;
    while (std::getline(in, line)) {
        if (!line.empty() && line.back() == '\r') line.pop_back();
        if (line.empty() || line[0] == '#') continue;
        const std::vector<std::string> cols = splitTabs(line);
        if (cols.size() < 4) continue;

        const std::string& libCol = cols[0];
        const bool systemLibs = cols[1] == "1";
        const std::string& wantDecision = cols[2];
        const std::string& wantReason = cols[3];

        const char* lib = (libCol == "<null>") ? nullptr : libCol.c_str();
        const nextvm::libfilter::Verdict v =
            nextvm::libfilter::classify_lib(lib, systemLibs);
        const std::string gotDecision = decision_name(v.decision);
        const std::string gotReason = nextvm::libfilter::reason_name(v.reason);

        ++total;
        if (gotDecision == wantDecision && gotReason == wantReason) {
            std::cout << "[ok] lib=" << (lib == nullptr ? "<null>" : libCol)
                      << " system_libs=" << (systemLibs ? 1 : 0) << " -> "
                      << gotDecision << "/" << gotReason << "\n";
        } else {
            ++failed;
            std::cerr << "[FAIL] lib=" << (lib == nullptr ? "<null>" : libCol)
                      << " system_libs=" << (systemLibs ? 1 : 0) << "\n"
                      << "     expected=" << wantDecision << "/" << wantReason << "\n"
                      << "       actual=" << gotDecision << "/" << gotReason << "\n";
        }
    }

    // 不变量：判据优先级不依赖向量文件
    struct Inv { const char* name; bool ok; };
    const std::vector<Inv> invariants = {
        // 无名字：原逻辑不过滤（照常 patch）
        {"nullptr_patches", nextvm::libfilter::classify_lib(nullptr, false).decision ==
                                nextvm::libfilter::Decision::kPatch},
        // 自身优先于 app_lib：宿主库在 /data/app/ 下，仍必须 skip
        {"self_beats_app_lib",
         nextvm::libfilter::classify_lib(
             "/data/app/~~q==/com.nextvm.app-q==/lib/arm64/libx.so", false).reason ==
             nextvm::libfilter::Reason::kContainerSelf},
        // 图形栈优先于"系统库开关"：打开 NS_HOOK_SYSTEM_LIBS 也不能 patch 图形栈
        {"graphics_beats_system_toggle",
         nextvm::libfilter::classify_lib("/system/lib64/libEGL_x.so", true).decision ==
             nextvm::libfilter::Decision::kSkip},
        // 空串（非 nullptr）走完整判据 → 落到系统库
        {"empty_string_is_system_lib",
         nextvm::libfilter::classify_lib("", false).reason ==
             nextvm::libfilter::Reason::kSystemLib},
    };
    for (const auto& inv : invariants) {
        ++total;
        if (inv.ok) {
            std::cout << "[ok] invariant " << inv.name << "\n";
        } else {
            ++failed;
            std::cerr << "[FAIL] invariant " << inv.name << "\n";
        }
    }

    std::cout << "lib_filter_test: " << (total - failed) << "/" << total << " passed\n";
    return failed == 0 ? 0 : 1;
}
