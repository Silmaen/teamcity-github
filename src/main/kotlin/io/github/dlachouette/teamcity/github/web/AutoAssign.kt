package io.github.dlachouette.teamcity.github.web

// Who to assign a pull request to under `autoAssignAuthor`, or null for nobody.
//
// Only on `opened`: that is the one moment "nobody is assigned" means nobody
// chose yet. Later on it may mean somebody removed the assignee on purpose, and
// putting them back would fight a human. A bot author is skipped — a
// dependency-update PR assigned to a bot is assigned to nobody.
object AutoAssign {

    fun assignee(
        action: PrAction,
        enabled: Boolean,
        author: String,
        authorIsBot: Boolean,
        assignees: List<String>,
    ): String? = when {
        !enabled || action != PrAction.OPENED -> null
        author.isBlank() || authorIsBot -> null
        assignees.isNotEmpty() -> null
        else -> author
    }
}
