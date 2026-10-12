package com.planet.evolution.simulation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.planet.evolution.model.Civilization;

/**
 * 科技树定义与操作（纯静态，无状态）。四条分支（军事 / 经济 / 社会 / 科技）+ 一个基础根节点。
 *
 * <p>规则：
 * <ul>
 *   <li>可研究 = 自己没解锁、且前置已解锁（根节点无前置）；</li>
 *   <li>成本按资源类型扣五种资源点，{@link #pay} / {@link #canAfford} 走**实际成本**；</li>
 *   <li>互斥组：同组已选过别的节点时，实际成本每种资源 ×2（{@link #isPenalized}）；</li>
 *   <li>解锁顺手把「主要消耗类型」的产出系数 ×1.2（{@link com.planet.evolution.model.Civilization#bumpMultiplier}）。</li>
 * </ul>
 */
public final class TechTree {

    public static final String BRANCH_BASIC = "basic";
    public static final String BRANCH_MILITARY = "military";
    public static final String BRANCH_ECONOMY = "economy";
    public static final String BRANCH_SOCIETY = "society";
    public static final String BRANCH_SPACE = "space";

    /** 对外的四大分支（按展示顺序）。 */
    private static final List<String> BRANCHES =
            List.of(BRANCH_MILITARY, BRANCH_ECONOMY, BRANCH_SOCIETY, BRANCH_SPACE);

    private static final Map<String, String> BRANCH_LABELS;

    static {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put(BRANCH_MILITARY, "军事");
        labels.put(BRANCH_ECONOMY, "经济");
        labels.put(BRANCH_SOCIETY, "社会");
        labels.put(BRANCH_SPACE, "科技");
        labels.put(BRANCH_BASIC, "基础");
        BRANCH_LABELS = Collections.unmodifiableMap(labels);
    }

    private static final List<TechNode> NODES = buildNodes();

    private static final Map<String, TechNode> BY_ID;

    static {
        Map<String, TechNode> byId = new LinkedHashMap<>();
        for (TechNode node : NODES) {
            byId.put(node.id(), node);
        }
        BY_ID = Collections.unmodifiableMap(byId);
    }

    private TechTree() {
    }

    /** 全部节点（按定义顺序）。 */
    public static List<TechNode> all() {
        return NODES;
    }

    /** 四大分支的 key（按展示顺序）。 */
    public static List<String> branches() {
        return BRANCHES;
    }

    /** 分支 key → 中文名；认不出时原样返回。 */
    public static String branchLabel(String branch) {
        return branch == null ? "" : BRANCH_LABELS.getOrDefault(branch, branch);
    }

    /** 按 id 找节点；找不到返回 null。 */
    public static TechNode getById(String id) {
        return id == null ? null : BY_ID.get(id.trim());
    }

    /** 按中文名找节点；认不出中文名时退回按 id 找。 */
    public static TechNode getByName(String target) {
        if (target == null) {
            return null;
        }
        String name = target.trim();
        if (name.isEmpty()) {
            return null;
        }
        for (TechNode node : NODES) {
            if (node.name().equals(name)) {
                return node;
            }
        }
        return BY_ID.get(name);
    }

    /** 当前可研究的节点：未解锁、且前置已满足（根节点无前置）。 */
    public static List<TechNode> getAvailable(Civilization civilization) {
        List<TechNode> available = new ArrayList<>();
        if (civilization == null) {
            return available;
        }
        for (TechNode node : NODES) {
            if (civilization.hasUnlockedTech(node.id())) {
                continue;
            }
            if (node.prerequisite() == null || civilization.hasUnlockedTech(node.prerequisite())) {
                available.add(node);
            }
        }
        return available;
    }

    /** 是否正被同组惩罚：节点的互斥组里，玩家已解锁了别的节点。 */
    public static boolean isPenalized(TechNode node, Civilization civilization) {
        if (node == null || civilization == null || node.exclusiveGroup() == null) {
            return false;
        }
        for (TechNode other : NODES) {
            if (other.id().equals(node.id())) {
                continue;
            }
            if (node.exclusiveGroup().equals(other.exclusiveGroup())
                    && civilization.hasUnlockedTech(other.id())) {
                return true;
            }
        }
        return false;
    }

    /** 实际成本（按资源类型）：被同组惩罚时每种资源 ×2。civilization 为 null 时按基础成本。 */
    public static Map<String, Integer> actualCosts(TechNode node, Civilization civilization) {
        Map<String, Integer> result = new LinkedHashMap<>();
        if (node == null || node.costs() == null) {
            return result;
        }
        int factor = isPenalized(node, civilization) ? 2 : 1;
        for (Map.Entry<String, Integer> entry : node.costs().entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                result.put(entry.getKey(), entry.getValue() * factor);
            }
        }
        return result;
    }

    /** 资源点是否够付这笔成本。 */
    public static boolean canAfford(Civilization civilization, Map<String, Integer> costs) {
        if (civilization == null) {
            return false;
        }
        if (costs == null || costs.isEmpty()) {
            return true;
        }
        for (Map.Entry<String, Integer> entry : costs.entrySet()) {
            if (entry.getValue() == null || entry.getValue() <= 0) {
                continue;
            }
            if (civilization.getPoints(entry.getKey()) < entry.getValue()) {
                return false;
            }
        }
        return true;
    }

    /** 资源点是否够解锁该节点（按实际成本）。 */
    public static boolean canAfford(Civilization civilization, TechNode node) {
        return canAfford(civilization, actualCosts(node, civilization));
    }

    /** 扣除这笔资源点成本。 */
    public static void pay(Civilization civilization, Map<String, Integer> costs) {
        if (civilization == null || costs == null) {
            return;
        }
        for (Map.Entry<String, Integer> entry : costs.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null && entry.getValue() > 0) {
                civilization.addPoints(entry.getKey(), -entry.getValue());
            }
        }
    }

    /** 扣除解锁该节点的实际成本。 */
    public static void pay(Civilization civilization, TechNode node) {
        pay(civilization, actualCosts(node, civilization));
    }

    /** 解锁节点：记账 + 把「主要消耗类型」的产出系数 ×1.2（已解锁则无副作用）。 */
    public static void unlock(Civilization civilization, TechNode node) {
        if (civilization == null || node == null || civilization.hasUnlockedTech(node.id())) {
            return;
        }
        civilization.unlockTech(node.id());
        civilization.bumpMultiplier(primaryType(node));
    }

    /** 成本文本，如「农业 15、工业 10」；没有正成本时返回「无」。 */
    public static String costText(Map<String, Integer> costs) {
        if (costs == null || costs.isEmpty()) {
            return "无";
        }
        StringBuilder sb = new StringBuilder();
        for (String type : Civilization.POINT_TYPES) {
            Integer value = costs.get(type);
            if (value == null || value <= 0) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('、');
            }
            sb.append(Civilization.pointLabel(type)).append(' ').append(value);
        }
        return sb.length() == 0 ? "无" : sb.toString();
    }

    /** 节点主要消耗的资源类型（成本最高者）；没有成本时按分支给默认。 */
    private static String primaryType(TechNode node) {
        String best = null;
        int bestValue = 0;
        if (node.costs() != null) {
            for (Map.Entry<String, Integer> entry : node.costs().entrySet()) {
                if (entry.getValue() != null && entry.getValue() > bestValue) {
                    bestValue = entry.getValue();
                    best = entry.getKey();
                }
            }
        }
        if (best != null) {
            return best;
        }
        return switch (node.branch()) {
            case BRANCH_MILITARY -> Civilization.POINT_MILITARY;
            case BRANCH_ECONOMY -> Civilization.POINT_PRODUCTION;
            case BRANCH_SOCIETY -> Civilization.POINT_CULTURE;
            default -> Civilization.POINT_SCIENCE;
        };
    }

    /** 建节点：cost 自动取基础成本之和。 */
    private static TechNode node(String id, String name, String branch, String prerequisite,
            Map<String, Integer> costs, int techBonus, String exclusiveGroup) {
        int total = 0;
        if (costs != null) {
            for (Integer value : costs.values()) {
                if (value != null) {
                    total += value;
                }
            }
        }
        return new TechNode(id, name, branch, prerequisite, costs, total, techBonus, exclusiveGroup);
    }

    private static List<TechNode> buildNodes() {
        String fd = Civilization.POINT_FOOD;
        String pr = Civilization.POINT_PRODUCTION;
        String mi = Civilization.POINT_MILITARY;
        String cu = Civilization.POINT_CULTURE;
        String sc = Civilization.POINT_SCIENCE;
        List<TechNode> nodes = new ArrayList<>();
        // 根：所有分支的起点
        nodes.add(node("basic_fire", "火种", BRANCH_BASIC, null, null, 0, null));
        // 军事
        nodes.add(node("mil_bronze", "青铜兵器", BRANCH_MILITARY, "basic_fire", Map.of(pr, 10), 20, "mil_arm"));
        nodes.add(node("mil_iron", "铁器军备", BRANCH_MILITARY, "mil_bronze", Map.of(pr, 25, mi, 15), 30, null));
        nodes.add(node("mil_cavalry", "骑兵", BRANCH_MILITARY, "mil_iron", Map.of(mi, 30, fd, 20), 40, "mil_mobility"));
        nodes.add(node("mil_castle", "城防工事", BRANCH_MILITARY, "mil_iron", Map.of(pr, 40, mi, 20), 45, "mil_mobility"));
        nodes.add(node("mil_gunpowder", "火器", BRANCH_MILITARY, "mil_cavalry", Map.of(mi, 60, sc, 30), 60, "mil_firearm"));
        nodes.add(node("mil_rifle", "步枪", BRANCH_MILITARY, "mil_gunpowder", Map.of(mi, 90, pr, 60), 80, "mil_firearm"));
        nodes.add(node("mil_tank", "装甲部队", BRANCH_MILITARY, "mil_rifle", Map.of(mi, 150, pr, 120), 110, null));
        nodes.add(node("mil_nuke", "核弹", BRANCH_MILITARY, "mil_tank", Map.of(mi, 250, sc, 300), 150, "mil_strategic"));
        // 经济
        nodes.add(node("eco_agriculture", "农业改良", BRANCH_ECONOMY, "basic_fire", Map.of(fd, 15), 20, "eco_food"));
        nodes.add(node("eco_irrigation", "灌溉工程", BRANCH_ECONOMY, "eco_agriculture", Map.of(fd, 30, pr, 20), 35, "eco_food"));
        nodes.add(node("eco_factory", "工厂", BRANCH_ECONOMY, "eco_agriculture", Map.of(pr, 50, sc, 20), 50, "eco_industry"));
        nodes.add(node("eco_banking", "银行", BRANCH_ECONOMY, "eco_factory", Map.of(pr, 70, cu, 40), 65, "eco_industry"));
        nodes.add(node("eco_railway", "铁路", BRANCH_ECONOMY, "eco_factory", Map.of(pr, 110, sc, 60), 80, "eco_transport"));
        nodes.add(node("eco_steamship", "轮船", BRANCH_ECONOMY, "eco_railway", Map.of(pr, 150, sc, 90), 95, "eco_transport"));
        nodes.add(node("eco_automation", "自动化", BRANCH_ECONOMY, "eco_railway", Map.of(pr, 200, sc, 150), 120, null));
        nodes.add(node("eco_grid", "电网", BRANCH_ECONOMY, "eco_automation", Map.of(pr, 260, sc, 200), 140, null));
        // 社会
        nodes.add(node("soc_philosophy", "哲学", BRANCH_SOCIETY, "basic_fire", Map.of(cu, 15), 20, "soc_thought"));
        nodes.add(node("soc_writing", "文字", BRANCH_SOCIETY, "soc_philosophy", Map.of(cu, 30, sc, 20), 35, "soc_thought"));
        nodes.add(node("soc_law", "律法", BRANCH_SOCIETY, "soc_writing", Map.of(cu, 50, mi, 30), 45, "soc_order"));
        nodes.add(node("soc_currency", "货币", BRANCH_SOCIETY, "soc_writing", Map.of(cu, 50, pr, 30), 45, "soc_order"));
        nodes.add(node("soc_education", "教育", BRANCH_SOCIETY, "soc_law", Map.of(cu, 90, sc, 60), 70, null));
        nodes.add(node("soc_university", "大学", BRANCH_SOCIETY, "soc_education", Map.of(cu, 140, sc, 110), 90, null));
        nodes.add(node("soc_democracy", "民主制度", BRANCH_SOCIETY, "soc_university", Map.of(cu, 220, sc, 160), 120, "soc_governance"));
        nodes.add(node("soc_welfare", "社会福利", BRANCH_SOCIETY, "soc_university", Map.of(cu, 300, fd, 200), 140, "soc_governance"));
        // 科技
        nodes.add(node("spc_compass", "指南针", BRANCH_SPACE, "basic_fire", Map.of(sc, 20, cu, 10), 25, "spc_navigation"));
        nodes.add(node("spc_printing", "印刷术", BRANCH_SPACE, "spc_compass", Map.of(sc, 40, cu, 30), 40, "spc_navigation"));
        nodes.add(node("spc_astronomy", "天文学", BRANCH_SPACE, "spc_printing", Map.of(sc, 70, cu, 40), 55, "spc_science"));
        nodes.add(node("spc_physics", "物理学", BRANCH_SPACE, "spc_astronomy", Map.of(sc, 120, pr, 60), 75, "spc_science"));
        nodes.add(node("spc_electricity", "电力学", BRANCH_SPACE, "spc_physics", Map.of(sc, 180, pr, 120), 95, null));
        nodes.add(node("spc_computer", "计算机", BRANCH_SPACE, "spc_electricity", Map.of(sc, 260, pr, 180), 120, null));
        nodes.add(node("spc_satellite", "卫星", BRANCH_SPACE, "spc_computer", Map.of(sc, 350, pr, 260), 150, "spc_orbit"));
        nodes.add(node("spc_rocket", "火箭", BRANCH_SPACE, "spc_satellite", Map.of(sc, 450, pr, 350, mi, 200), 180, "spc_orbit"));
        return List.copyOf(nodes);
    }
}
