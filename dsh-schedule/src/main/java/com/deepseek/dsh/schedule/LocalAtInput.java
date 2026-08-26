package com.deepseek.dsh.schedule;

/**
 * 结构化本地日历输入 —— 对应原 Harness 的 {@code LocalAtInput}，
 * 被 {@code schedule_create} 的 {@code at} 选择器接受。
 *
 * @param date     四位数 ISO 日期（{@code YYYY-MM-DD}）
 * @param time     本地墙钟时间（{@code HH:mm:ss}，可选 1-3 位毫秒）
 * @param timeZone 显式 UTC 或 IANA Area/Location 时区
 */
public record LocalAtInput(String date, String time, String timeZone) {
}
