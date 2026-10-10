package com.planet.evolution.dto;

/**
 * 单个区域的对外视图。ownerCivId 为 0 表示无主。
 * explored 为 false 表示玩家还没探索到（前端盖黑雾遮罩）。
 */
public record RegionView(int id, String name, int ownerCivId, String ownerCivName, boolean explored) {
}
