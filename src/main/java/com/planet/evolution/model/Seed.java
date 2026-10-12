package com.planet.evolution.model;

/**
 * 一颗因果种子：关键事件留下的长期后果，种下后若千年才发芽。
 *
 * <p>由 {@code EvolutionEngine} 在关键事件后请 AI 判定是否种下；稀有度低于重大阈值的一律丢弃。
 * 发芽年限不再听 AI，由引擎按 {@code rarity} 随机决定（60~79 约 100~200 年，80~100 约 200~400 年）。
 *
 * @param id                 种子唯一 id
 * @param type               种子类型（灭国之仇 / 联姻纽带 / 背叛记忆 / 援助之恩）
 * @param plantedBy          种下它的文明 id（0 表示世界事件，没有具体种植者）
 * @param targetCivId        目标文明 id（0 表示无特定目标）
 * @param plantedYear        种下年份
 * @param rarity             稀有度 0~100（决定后果规模与发芽年限）
 * @param triggerAfterYears  种下后多少年发芽
 * @param note               备注（AI 写的简短说明）
 */
public record Seed(String id, String type, int plantedBy, int targetCivId, int plantedYear,
        int rarity, int triggerAfterYears, String note) {
}
