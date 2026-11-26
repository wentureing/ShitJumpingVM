// 由 tools/gen/gen-diag-channels.mjs 从
// core/common/src/main/resources/diag_channels.tsv 生成。**不要手改**。
//
// 消费者：native-hook.cpp 的 is_container_diag_path()（shared + kotlin + cpp）
#pragma once

static const char* const kDiagMarkersShared[] = {
    "debug-trace.log",
    "checks.log",
    "seccomp-diag.log",
    "breadcrumb-",
    "stackdump-",
    "hijacktrace-",
    "startactivity-trace-",
    "webviewwarm-",
    "webviewwatch-",
};

static const char* const kDiagMarkersKotlinOnly[] = {
    "stateprobe-",
    "dexmem-",
    "shortcut-probe-",
    "memdex-",
    "v139-probe.log",
    "v144-intent-probe.log",
    "v145-intent-probe.log",
    "v148-connect-probe.log",
    "-filtered.log",
    "-1.trace",
};

static const char* const kDiagMarkersCppOnly[] = {
    "-hooklog-",
};
