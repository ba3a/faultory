package com.faultory.core.i18n

enum class UiMessageKey(override val path: String) : MessageKey {
    GAME_TITLE("game.title"),

    LEVEL_SELECT_TITLE("level_select.title"),
    LEVEL_SELECT_HINT("level_select.hint"),
    LEVEL_SELECT_OPEN("level_select.open"),
    LEVEL_SELECT_LOCKED_REQUIRES("level_select.locked.requires"),
    LEVEL_SELECT_LOCKED_HINT("level_select.locked.hint"),

    BANK_WORKERS("bank.workers"),
    BANK_MACHINES("bank.machines"),
    BANK_WORKER("bank.worker"),
    BANK_MACHINE("bank.machine"),

    HUD_STATUS("hud.status"),
    HUD_PROGRESS("hud.progress"),
    HUD_BACK_TO_LEVELS("hud.back_to_levels"),
    HUD_SHIFT_COMPLETE("hud.shift_complete"),
    HUD_WORKER_FALLBACK("hud.worker_fallback"),
    HUD_ASSIGNING("hud.assigning"),
    HUD_HELP_DEFAULT("hud.help.default"),
    HUD_HELP_WORKER("hud.help.worker"),
    HUD_HELP_QA("hud.help.qa"),
    HUD_HELP_PRODUCER("hud.help.producer"),

    COMPLETION_REPLAY("completion.replay"),
    COMPLETION_NEXT("completion.next"),
    COMPLETION_BACK("completion.back"),
    COMPLETION_PASSED("completion.passed"),
    COMPLETION_FAILED("completion.failed"),
    COMPLETION_DELIVERY("completion.delivery"),
    COMPLETION_THRESHOLDS("completion.thresholds"),
    COMPLETION_STARS("completion.stars"),
    COMPLETION_MIX("completion.mix"),
    COMPLETION_PRODUCT_LINE("completion.product_line"),

    UPGRADE_TITLE("upgrade.title"),
    UPGRADE_WORKER("upgrade.worker"),
    UPGRADE_MACHINE("upgrade.machine"),
    UPGRADE_COST("upgrade.cost"),

    CONTEXT_ASSIGN_MACHINE("context.assign_machine"),
    CONTEXT_ASSIGN_QA("context.assign_qa"),
    CONTEXT_UPGRADE("context.upgrade"),
}
