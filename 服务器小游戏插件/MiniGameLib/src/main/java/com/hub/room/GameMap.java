package com.hub.room;

import java.util.List;

/**
 * 一张**地图**：某个游戏要用哪些世界实例、每个世界里的区域与规则。
 *
 * <p>为什么需要这一层（用户拍板的两条）：
 * <ol>
 *   <li>**一个模板世界会被多个游戏引用**，每个游戏对它的初始化不同 —— 所以区域/规则属于
 *       「(游戏, 地图)」而不是属于模板世界；</li>
 *   <li>**一张地图可能散落在多个世界里**（派对就是：子游戏分别在 3 个世界），
 *       所以一张地图对应**一组**世界实例。</li>
 * </ol>
 *
 * @param game   游戏 key
 * @param name   地图名（{@code /hub create <game> <map>} 里的 map）
 * @param worlds 这张地图需要的世界实例（按顺序创建）
 * @param entry  入口点（玩家进房间后先出现在这；**不是出生点**，游戏可再按玩家调整）；可为 null
 */
public record GameMap(String game, String name, List<WorldPlan> worlds, Entry entry) {

    /**
     * 一个世界实例的计划：从哪个模板来、里面有哪些区域、默认规则是什么。
     *
     * @param templateKey 模板键（{@link WorldTemplate#key()}）
     * @param rules       世界级规则（区域级没写时用它）
     * @param regions     该世界里属于这张地图的区域
     */
    public record WorldPlan(String templateKey, WorldTemplate.Rules rules, List<RegionPlan> regions) {
    }

    /**
     * 一块区域的计划。
     *
     * <p>{@code rules} 可以为 null —— 表示"跟随世界级规则"。用户已拍板：世界级缺省 + 区域级覆盖，
     * 因为同一个世界里"高台之王"要 pvp、隔壁"蛇行"不要 pvp。
     */
    public record RegionPlan(String name, double x1, double y1, double z1,
                             double x2, double y2, double z2, WorldTemplate.Rules rules) {

        /** 实际生效的规则：自己有就用自己，否则用世界的。 */
        public WorldTemplate.Rules effectiveRules(WorldTemplate.Rules worldRules) {
            return rules != null ? rules : worldRules;
        }

        /** 区域中心点（用于推导入口点等）。 */
        public double centerX() {
            return (x1 + x2) / 2.0;
        }

        public double centerZ() {
            return (z1 + z2) / 2.0;
        }
    }

    /** 入口点：玩家进房间后先站在这里（属于哪个模板世界 + 坐标）。 */
    public record Entry(String templateKey, double x, double y, double z) {
    }
}
