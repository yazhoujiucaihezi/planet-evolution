package com.planet.evolution.simulation;

import java.util.Map;

/**
 * 科技树里的一个节点（不可变定义）。
 *
 * <p>成本按资源类型给出（键为 {@link com.planet.evolution.model.Civilization#POINT_TYPES} 里的类型）；
 * {@code cost} 是基础成本之和，便于排序（如开局「按最低成本从古典往高依次点」）。
 * {@code exclusiveGroup} 是互斥组：同组里已选过别的节点时，本节点的实际成本每种资源 ×2。
 *
 * @param id             节点 id（如 {@code mil_nuke}）
 * @param name           中文名（如「核弹」）
 * @param branch         所属分支（military / economy / society / space，根节点为 basic）
 * @param prerequisite   前置节点 id；null 表示树根
 * @param costs          基础成本（按资源类型）；null 或空表示免费
 * @param cost           基础成本之和
 * @param techBonus      解锁时奖励的科技值
 * @param exclusiveGroup 互斥组名；null 表示不参与互斥惩罚
 */
public record TechNode(String id, String name, String branch, String prerequisite,
        Map<String, Integer> costs, int cost, int techBonus, String exclusiveGroup) {
}
