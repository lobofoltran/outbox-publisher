# Contributing to outbox-publisher

Thanks for your interest. This file is intentionally short — operational details and the full rule set live in [`AGENTS.md`](./AGENTS.md), and the execution plan lives in [`ROADMAP.md`](./ROADMAP.md).

## Before you start

1. Read [`AGENTS.md`](./AGENTS.md) end-to-end. It defines coding standards, test rules, coverage gates, commit and PR conventions.
2. Check [`ROADMAP.md`](./ROADMAP.md) to see which phase your change belongs to.
3. Find or create the ADR you are implementing under [`docs/adr/`](./docs/adr/). Every code-producing PR links to one.

## Workflow

1. Fork or branch from `main`.
2. Make your change. Keep one logical change per PR. Split cross-cutting refactors.
3. Use [Conventional Commits](https://www.conventionalcommits.org/) for every commit. Allowed types: `feat`, `fix`, `docs`, `chore`, `refactor`, `test`, `build`, `ci`, `perf`. Scopes are module names (`outbox-core`, `outbox-jdbc`, `outbox-spring`, `outbox-micrometer`, `outbox-schema`, `outbox-bom`) or cross-cutting (`build`, `ci`, `docs`, `repo`).
4. **GPG-sign every commit and tag.** Configure your signing key via `git config --local` only — the global git config must not be modified by this project.
5. Run `./mvnw -B -ntp -Pquality verify` locally before opening the PR. Coverage gates are enforced by JaCoCo and must pass.
6. Open the PR. The template prompts you for the roadmap stage, the ADR link, risk, rollback, and observability impact. Fill it in.

## Reporting issues

Open a GitHub issue. Include:

- What you expected.
- What happened.
- A minimal reproducer if you have one.
- Java version, PostgreSQL version, Spring Boot version (if applicable).

## License

By contributing, you agree that your contributions are licensed under the [MIT License](./LICENSE).
