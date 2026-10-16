/**
 * Faultory project configuration for the DMTools agents.
 *
 * Agents discover this file as .dmtools/config.js in the repository root (the `agents/`
 * submodule is dmtools-agents). Only what differs from the defaults is set here.
 */
module.exports = {
    repository: {
        owner: 'ba3a',
        repo: 'faultory'
    },

    git: {
        // Faultory's default branch is master, the agents' default is main.
        baseBranch: 'master'
    },

    agentConfigsDir: 'agents',

    // Planned issues (the issue_planning agent, label dmtools:plan): each step of a plan is started
    // with the label that starts development here, the trigger of the Issue Teammate workflow.
    issuePlanning: {
        devLabel: 'dmtools:develop'
    },

    cliPrompts: {
        story_development: [
            './.dmtools/prompts/faultory_ci.md'
        ],
        bug_development: [
            './.dmtools/prompts/faultory_ci.md'
        ],
        pr_rework: [
            './.dmtools/prompts/faultory_ci.md'
        ]
    },

    globalCliPrompts: [
        './agents/instructions/common/codegraph_tools.md'
    ]
};
