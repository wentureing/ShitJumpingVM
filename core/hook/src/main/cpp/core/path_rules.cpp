#include "path_rules.h"

#include "path_pattern.h"

namespace nextvm {
namespace path {

std::string trim_trailing_slashes(const std::string& s) {
    std::string out = s;
    while (!out.empty() && out.back() == '/') out.pop_back();
    return out;
}

bool is_path_hidden(const HiddenSet& hidden, const std::string& path) {
    if (hidden.empty()) return false;
    return hidden.count(path) > 0;
}

std::string redirect_path(const RuleTable& rules, const std::string& path) {
    if (path.empty() || rules.empty()) return "";

    std::string best_from;
    std::string best_to;
    std::size_t best_len = 0;
    bool best_is_root = false;

    for (const auto& kv : rules) {
        const PathPattern pat = parse_pattern(kv.first);
        if (!pattern_matches(pat, path)) continue;
        const bool is_root = pattern_is_root_form(pat, path);
        const std::size_t len = is_root ? path.size() : pat.text.size();
        if (!(len > best_len || (len == best_len && best_is_root && !is_root))) continue;
        best_len = len;
        // 只有"非根形态的前缀规则"才做路径拼接；Exact/Glob/根形态都是整路径替换。
        best_from = (is_root || pat.kind != PatternKind::Prefix) ? path : pat.text;
        best_to = is_root ? trim_trailing_slashes(kv.second) : kv.second;
        best_is_root = is_root;
    }

    if (best_from.empty()) return "";

    const std::string rest = path.substr(best_from.size());
    if (rest.empty()) return best_to;
    const bool to_slash = !best_to.empty() && best_to.back() == '/';
    const bool rest_slash = rest.front() == '/';
    if (to_slash && rest_slash) return best_to + rest.substr(1);
    if (to_slash) return best_to + rest;
    if (rest_slash) return best_to + rest;
    return best_to + "/" + rest;
}

}  // namespace path
}  // namespace nextvm
