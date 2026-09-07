package com.faultory.core.save

/**
 * A [SaveRepository] backed by a plain map — no disk I/O, so a headless run can never touch the
 * player's real save slots (the same invariant [com.faultory.core.capture] keeps for capture mode).
 * Seeds from [initial] so a driver can hand it an upstream level's completed run before opening a
 * level that supplies from it — [com.faultory.core.systems.BeltSupplyFeederFactory] reads through a
 * [SaveRepository] the same way whether the save came from disk or was seeded here.
 */
class InMemorySaveRepository(
    initial: Map<String, GameSave> = emptyMap()
) : SaveRepository {
    private val saves: MutableMap<String, GameSave> = initial.toMutableMap()

    override fun hasSlot(slotId: String): Boolean = slotId in saves

    override fun load(slotId: String): GameSave? = saves[slotId]

    override fun save(save: GameSave) {
        saves[save.slotId] = save
    }
}

/** An [EncounterProgressRepository] backed by a plain field — see [InMemorySaveRepository]. */
class InMemoryEncounterProgressRepository(
    initial: EncounterProgress = EncounterProgress()
) : EncounterProgressRepository {
    private var progress: EncounterProgress = initial

    override fun load(): EncounterProgress = progress

    override fun save(progress: EncounterProgress) {
        this.progress = progress
    }
}
