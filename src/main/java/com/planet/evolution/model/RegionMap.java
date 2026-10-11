package com.planet.evolution.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 世界区域地图：固定的简化地理区划与邻接关系。
 * id 从 1 开始，0 保留给"无主"。
 */
public final class RegionMap {

    private final List<Region> regions = new ArrayList<>();
    private final Map<Integer, Region> byId = new LinkedHashMap<>();

    public RegionMap() {
        // 亚洲
        add(1, "东亚", "forest", 2, 6, 7, 8);
        add(2, "东南亚", "jungle", 1, 3, 4, 19);
        add(3, "南亚", "jungle", 2, 4, 6, 19);
        add(4, "中亚", "mountain", 2, 3, 5, 6);
        add(5, "西亚", "desert", 4, 6, 11, 13, 17);
        add(6, "北亚", "tundra", 1, 3, 4, 5, 7);
        // 欧洲
        add(7, "西欧", "plains", 1, 6, 8, 10, 11, 17);
        add(8, "南欧", "mountain", 1, 7, 10, 13);
        add(9, "东欧", "plains", 6, 7, 10, 12);
        add(10, "中欧", "forest", 7, 8, 9, 11, 12);
        add(11, "北欧", "tundra", 5, 7, 10, 12);
        // 非洲
        add(12, "北非", "desert", 9, 10, 11, 13, 14);
        add(13, "西非", "jungle", 5, 8, 12, 14, 15);
        add(14, "中非", "jungle", 12, 13, 15, 16);
        add(15, "东非", "plains", 13, 14, 16, 17);
        add(16, "南非", "plains", 14, 15);
        // 美洲
        add(17, "北美东部", "forest", 5, 7, 12, 18, 20);
        add(18, "北美西部", "mountain", 17, 19, 20);
        add(19, "中美", "jungle", 2, 3, 18, 21);
        add(20, "加勒比", "jungle", 17, 18, 21);
        add(21, "南美北部", "jungle", 19, 20, 22, 23);
        add(22, "南美西部", "mountain", 21, 23, 24);
        add(23, "南美东部", "forest", 21, 22, 24);
        add(24, "南美南部", "plains", 22, 23);
        // 大洋洲与极地
        add(25, "澳大利亚", "desert", 2, 26, 30);
        add(26, "新西兰", "forest", 25, 30);
        add(27, "太平洋岛屿", "jungle", 2, 25, 30);
        add(28, "南极洲", "tundra", 24, 30);
        add(29, "北极", "tundra", 6, 11, 17);
        add(30, "大西洋岛屿", "plains", 25, 26, 27, 28);
    }

    private void add(int id, String name, String terrain, Integer... neighbors) {
        Region region = new Region(id, name, List.of(neighbors), terrain);
        regions.add(region);
        byId.put(id, region);
    }

    /**
     * 动态新增一个区域（玩家自由行动用，例如「飞出地球」开辟新地盘）。
     * id 取当前最大 id + 1；直接把归属设为给定文明；neighbors 里不存在的区域 id 会被过滤掉。
     *
     * @param name        区域名
     * @param ownerCivId  归属文明 id（0 表示无主）
     * @param neighbors   邻接区域 id 列表，可为 null / 空
     * @return 新建的区域
     */
    public Region addDynamicRegion(String name, int ownerCivId, List<Integer> neighbors) {
        int nextId = 1;
        for (Region region : regions) {
            if (region.getId() >= nextId) {
                nextId = region.getId() + 1;
            }
        }
        List<Integer> valid = new ArrayList<>();
        if (neighbors != null) {
            for (Integer id : neighbors) {
                if (id != null && byId.containsKey(id)) {
                    valid.add(id);
                }
            }
        }
        Region region = new Region(nextId, name, valid);
        region.setOwnerCivId(ownerCivId);
        regions.add(region);
        byId.put(nextId, region);
        return region;
    }

    /** 全部区域（只读）。 */
    public List<Region> all() {
        return Collections.unmodifiableList(regions);
    }

    /** 按 id 取区域，不存在返回 null。 */
    public Region get(int id) {
        return byId.get(id);
    }

    public int size() {
        return regions.size();
    }

    /** 随机挑一个无主区域；没有无主区域时返回 null。 */
    public Region randomUnowned(Random random) {
        List<Region> free = new ArrayList<>();
        for (Region region : regions) {
            if (region.isUnowned()) {
                free.add(region);
            }
        }
        return free.isEmpty() ? null : free.get(random.nextInt(free.size()));
    }
}
