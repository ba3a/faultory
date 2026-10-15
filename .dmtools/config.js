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
