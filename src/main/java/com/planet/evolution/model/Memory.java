package com.planet.evolution.model;

/**
 * 一条文明记忆：某年、某事、针对某文明、带正负倾向。
 *
 * <p>由 {@link Civilization#remember(Memory)} 写入，并按 {@code sentiment × 10} 折算成
 * 对目标文明的关系值变化（-100~100）。{@code type} 是事件类型标签（如「战争」「援助」），
 * {@code targetCivId} 为 0 表示没有特定目标（世界事件）。
 *
 * @param year        发生年份
 * @param type        事件类型标签
 * @param targetCivId 目标文明 id；0 表示无特定目标
 * @param description 事件描述
 * @param sentiment   情感倾向 -1.0（极负面）~ 1.0（极正面）
 */
public record Memory(int year, String type, int targetCivId, String description, double sentiment) {
}
