package com.hub.api;

/**
 * **框架契约版本**（R4 契约一致）。
 *
 * <p>为什么需要：各游戏模块都是 `provided` 依赖 MiniGameLib，**运行时却不保证版本一致** ——
 * 线上只换一个游戏 jar（或只换 MiniGameLib）时，会出现"编译期有这个方法、运行期没有"的
 * `NoClassDefFoundError`，表现为插件启用失败、甚至把这个游戏静默踢出注册表。
 *
 * <p>现在每个游戏在自己的 `plugin.yml` 里声明它按哪个契约写的：
 * <pre>
 *   minigame:
 *     key: snakeparty
 *     contract: 1
 * </pre>
 * 启动时框架（HubCore）会比对；不一致就**明确报错**（而不是让它半死不活）。
 *
 * <p>规则：**改变了游戏的 API 语义才升级 {@link #VERSION}**（新增可选方法不算）。
 */
public final class Contract {

    /** 当前框架契约版本。改这里 = 要求所有游戏同步更新 `minigame.contract`。 */
    public static final int VERSION = 1;

    /** plugin.yml 里声明契约的键。 */
    public static final String KEY = "minigame.contract";

    private Contract() {
    }

    /**
     * 把声明值翻译成"给人看的状态"。
     *
     * <p>读取是**各插件自己解析 plugin.yml**（HubCore 的 `GameRegistry` 本来就在扫 jar 读
     * `minigame.key`，顺手读 `contract` 最省事）—— Paper 的 `PluginDescriptionFile` 没有
     * 通用自定义字段访问器，从这里硬读反而更绕。
     */
    public static String describe(int declared) {
        if (declared < 0) {
            return "未声明";
        }
        return declared == VERSION ? "一致" : "不一致（声明 " + declared + "，框架 " + VERSION + "）";
    }

    /** 声明值是否与框架一致。 */
    public static boolean matches(int declared) {
        return declared == VERSION;
    }
}
