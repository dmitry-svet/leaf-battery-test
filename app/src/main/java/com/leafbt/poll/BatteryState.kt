package com.leafbt.poll

import com.leafbt.can.LeafState

/** Everything the screen renders. Immutable snapshot. */
data class BatteryState(
    val leaf: LeafState = LeafState(),
    val connected: Boolean = false,
    val connecting: Boolean = false,
    val connectMsg: String? = null,
    val error: String? = null,
    /** Poll cycle counter: increments once per fresh pack A/V + cells sample. */
    val cycle: Int = 0,
    /** Connection / decode status lines. */
    val debug: List<String> = emptyList(),
)
