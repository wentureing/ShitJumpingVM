package com.nextvm.core.model

/**
 * 路径族的分类。每个族对应一种"真机上的目录形态"。
 *
 * 只做**分类**，不承载模板（模板在 PathKinds 数据表里，见 B 阶段）。
 * 这里先落地枚举，供 PathSpoofConfig 的按族开关与 PathPolicy 的判断使用。
 */
enum class PathKind {
    /** /data/user/0/<pkg> */
    DATA_ROOT,

    /** <dataDir>/files */
    FILES,

    /** <dataDir>/cache */
    CACHE,

    /** <dataDir>/code_cache */
    CODE_CACHE,

    /** <dataDir>/no_backup */
    NO_BACKUP,

    /** <dataDir>/databases/<name> */
    DATABASES,

    /** <dataDir>/app_<name> */
    APP_DIR,

    /** /storage/emulated/0/Android/data/<pkg> */
    EXTERNAL_DATA,

    /** .../Android/data/<pkg>/files */
    EXTERNAL_FILES,

    /** .../Android/data/<pkg>/cache */
    EXTERNAL_CACHE,

    /** /storage/emulated/0/Android/obb/<pkg> */
    OBB,

    /** /storage/emulated/0/Android/media/<pkg> */
    MEDIA,

    /** /data/app/~~<token>==/<pkg>-<token>==/base.apk */
    APK,

    /** /data/app/~~<token>==/<pkg>-<token>==/lib/arm64 */
    NATIVE_LIB,
    ;

    companion object {
        fun fromName(name: String): PathKind? = entries.firstOrNull { it.name == name }
    }
}
