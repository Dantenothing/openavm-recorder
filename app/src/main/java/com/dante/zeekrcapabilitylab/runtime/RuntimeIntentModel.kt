package com.dante.zeekrcapabilitylab.runtime

/** Pure policy prototype. Production recorder/preview services do NOT execute its decisions. */
internal class RuntimeIntentModel(val processEpoch: String) {
    enum class Kind { LOCAL_RECORD, LOCAL_PREVIEW, REMOTE_VIEW, PARKING_RECORD }
    data class Permit(val id: String, val deadline: Long)
    data class Task(val id: String, val parent: String, val kind: Kind, val revision: Long,
                    val deadline: Long, val source: String, val view: String, val ended: Boolean = false)
    data class Target(val camera: Boolean, val recording: Boolean, val localDisplay: Boolean,
                      val remoteVideo: Boolean, val standby: Boolean)
    var localEpoch: String? = null; private set
    var permit: Permit? = null; private set
    private val tasks = linkedMapOf<String, Task>()
    private val usedParents = mutableSetOf<String>()
    var cleanupConfirmed = true

    fun beginLocal(id: String): Boolean {
        if (id.isBlank() || id in usedParents || usedParents.size >= 128) return false
        localEpoch?.let(::endParent); permit?.let { endParent(it.id) }; permit = null
        localEpoch = id; usedParents += id
        return true
    }
    fun endLocal(id: String) {
        if (id != localEpoch) return
        endParent(id); localEpoch = null
    }
    fun park(id: String, now: Long, duration: Long): Boolean {
        if (id.isBlank() || id in usedParents || usedParents.size >= 128 || duration !in 1..7_200_000L || now < 0 || now > Long.MAX_VALUE-duration) return false
        localEpoch?.let(::endLocal); permit?.let { endParent(it.id) }
        permit = Permit(id, now + duration); usedParents += id
        return true
    }
    fun expire(now: Long) {
        permit?.takeIf { now >= it.deadline }?.let { endParent(it.id); permit = null }
        tasks.replaceAll { _, t -> if (now >= t.deadline) t.copy(ended = true) else t }
    }
    fun revokeParking() { permit?.let { endParent(it.id) }; permit = null }
    private fun endParent(id: String) { tasks.replaceAll { _, t -> if (t.parent == id) t.copy(ended = true) else t } }
    private fun allowed(parent: String, kind: Kind) = when (kind) {
        Kind.LOCAL_RECORD, Kind.LOCAL_PREVIEW -> localEpoch == parent
        else -> permit?.id == parent
    }
    fun start(epoch: String, task: Task, now: Long): Boolean {
        expire(now)
        if (epoch != processEpoch || !cleanupConfirmed || task.id.isBlank() || task.revision < 1 || task.ended ||
            task.deadline <= now || !allowed(task.parent, task.kind)) return false
        if (task.kind in setOf(Kind.REMOTE_VIEW, Kind.PARKING_RECORD) && task.deadline > (permit?.deadline ?: 0)) return false
        val old = tasks[task.id]
        if (old != null) return old == task && !old.ended // Task identity is immutable; STOP is terminal.
        if (tasks.size >= 128) return false
        tasks[task.id] = task
        return true
    }
    fun stop(epoch: String, id: String, parent: String, kind: Kind, revision: Long, now: Long): Boolean {
        expire(now)
        if (epoch != processEpoch || revision < 1) return false
        val old = tasks[id]
        if (old != null) {
            if (old.parent != parent || old.kind != kind || revision < old.revision) return false
            tasks[id] = old.copy(revision = revision, ended = true)
            return true
        }
        if (!allowed(parent, kind) || tasks.size >= 128 || id.isBlank()) return false
        tasks[id] = Task(id, parent, kind, revision, now, "", "", ended = true)
        return true
    }
    fun target(now: Long): Target {
        expire(now)
        val active = tasks.values.filter { !it.ended && allowed(it.parent, it.kind) }
        return Target(active.isNotEmpty(), active.any { it.kind in setOf(Kind.LOCAL_RECORD, Kind.PARKING_RECORD) },
            active.any { it.kind == Kind.LOCAL_PREVIEW }, active.any { it.kind == Kind.REMOTE_VIEW }, permit != null)
    }
}
