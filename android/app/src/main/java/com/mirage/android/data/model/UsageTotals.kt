package com.mirage.android.data.model

/**
 * 今日 / 本月用量快照, 单位字节。
 *
 * 账本在 :core 进程 (TrafficStatsStore), 经 `ICoreService.getUsageTotals` 取回。
 */
data class UsageTotals(
    val todayUp: Long,
    val todayDown: Long,
    val monthUp: Long,
    val monthDown: Long,
) {
    companion object {
        /** AIDL 约定的定长数组 [todayUp, todayDown, monthUp, monthDown]; 长度不符视为无效。 */
        fun fromArray(arr: LongArray?): UsageTotals? =
            if (arr != null && arr.size >= 4) UsageTotals(arr[0], arr[1], arr[2], arr[3]) else null
    }
}
