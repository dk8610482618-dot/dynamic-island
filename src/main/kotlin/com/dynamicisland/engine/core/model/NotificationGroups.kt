package com.dynamicisland.engine.core.model

/** Ranking comes from Android's RankingMap, newest-first within equal importance. */
object NotificationGroups {
    data class Group(val key:String,val label:String,val notifications:List<IslandNotification>)
    fun group(entries:List<IslandNotification>):List<Group> = entries.distinctBy {it.key}
        .groupBy {it.packageName+":"+(it.groupKey ?: "ungrouped")}
        .map { (key,rows) -> Group(key,rows.first().appLabel ?: rows.first().packageName,
            rows.sortedWith(compareByDescending<IslandNotification>{it.importance}.thenByDescending{it.postedAtMillis}.thenBy{it.key})) }
        .sortedWith(compareByDescending<Group>{it.notifications.maxOf{row->row.importance}}.thenByDescending{it.notifications.maxOf{row->row.postedAtMillis}}.thenBy{it.key})
}
