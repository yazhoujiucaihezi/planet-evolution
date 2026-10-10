package com.planet.evolution.dto;

/** 单个区域的对外视图。ownerCivId 为 0 表示无主。 */
public record RegionView(int id, String name, int ownerCivId, String ownerCivName) {
}
