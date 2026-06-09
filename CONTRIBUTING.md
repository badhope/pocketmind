# Contributing

Thanks for your interest in contributing! This document covers the
basics so we can review and merge changes quickly.

## Ground Rules

- Be respectful. We follow the project's
  [Code of Conduct](./CODE_OF_CONDUCT.md).
- Keep PRs focused. One feature / fix per PR.
- Discuss breaking changes in an issue **before** opening the PR.
- Don't commit secrets, large binaries, or generated build output.

## Development

1. **Fork & branch**: branch off `main`, name it
   `feat/<short-topic>` or `fix/<short-topic>`.
2. **Install** dependencies using the lockfile shipped in the repo
   (pnpm / npm / uv / pip / cargo — see `README.md` for the exact
   command).
3. **Run checks locally** before pushing:
   - linter (`lint`)
   - formatter (`format:check` or equivalent)
   - unit tests
4. **Write tests** for any non-trivial logic. Bug fixes should ship a
   regression test.

## Commit Messages

We loosely follow Conventional Commits:

```
<type>(<scope>): <short summary>

<body — explain the *why*, link the issue>
```

Common types: `feat`, `fix`, `docs`, `refactor`, `test`, `chore`, `ci`.

## Pull Requests

- Fill in the PR template. Screenshots help for UI work.
- Keep the diff small. Squash commits before merging unless the history
  is itself useful.
- Make sure CI is green; reviewers may wait for the first green run
  before looking.

## Reporting Bugs

Use the **Bug report** issue template. Include:

- exact version / commit SHA
- minimal reproduction (command, snippet, or screenshot)
- expected vs actual behavior
- environment (OS, runtime version, container, etc.)

## Suggesting Features

Open a **Feature request** issue. Describe the problem first, then the
proposed solution. We're more likely to act on clear user pain.

## License

By contributing, you agree that your contributions will be licensed
under the same license as the project (see [`LICENSE`](./LICENSE)).
