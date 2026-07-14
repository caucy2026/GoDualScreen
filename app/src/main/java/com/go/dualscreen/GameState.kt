package com.go.dualscreen

object GameState {
    lateinit var game: GoGame
    var mainActivity: MainActivity? = null
    var kataGoEngine: KataGoEngine? = null
    @Volatile var useKataGo = false  // 引擎就绪后自动切换
    @Volatile var initialSyncDone = false  // V10.2: 让子首次同步完成标志
}
