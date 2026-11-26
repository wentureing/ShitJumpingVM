#include "path_pattern.h"

namespace nextvm {
namespace path {

PathPattern parse_pattern(const std::string& raw) {
    PathPattern pat;
    pat.text = raw;
    pat.kind = raw.find('*') != std::string::npos ? PatternKind::Glob : PatternKind::Prefix;
    return pat;
}

bool glob_match(const std::string& pat, const std::string& path) {
    // 双指针 + 回溯点：只处理 '*'，无递归、无正则，O(len) 均摊。
    std::size_t p = 0, s = 0;
    std::size_t star = std::string::npos, mark = 0;
    while (s < path.size()) {
        if (p < pat.size() && pat[p] == '*') {
            star = p++;
            mark = s;
        } else if (p < pat.size() && pat[p] == path[s]) {
            ++p;
            ++s;
        } else if (star != std::string::npos) {
            p = star + 1;
            s = ++mark;
        } else {
            return false;
        }
    }
    while (p < pat.size() && pat[p] == '*') ++p;
    return p == pat.size();
}

bool pattern_is_root_form(const PathPattern& pat, const std::string& path) {
    if (pat.kind != PatternKind::Prefix) return false;
    if (pat.text.empty() || pat.text.back() != '/') return false;
    if (pat.text.size() != path.size() + 1) return false;
    return pat.text.compare(0, path.size(), path) == 0;
}

bool pattern_matches(const PathPattern& pat, const std::string& path) {
    switch (pat.kind) {
        case PatternKind::Exact:
            return pat.text == path;
        case PatternKind::Glob:
            return glob_match(pat.text, path);
        case PatternKind::Prefix:
        default:
            if (path.size() >= pat.text.size() && path.compare(0, pat.text.size(), pat.text) == 0) {
                return true;
            }
            return pattern_is_root_form(pat, path);
    }
}

}  // namespace path
}  // namespace nextvm
