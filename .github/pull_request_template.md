<!--
Thanks for the contribution. Please fill in every section.
Drafts are welcome — mark the PR as Draft and iterate.
The full rule set lives in AGENTS.md; this template only captures the per-PR signals.
-->

## Summary

<!-- One paragraph: what changes and why. Link to the issue if there is one. -->

## ADR / PRD

<!-- Every code-producing PR links to an ADR. If this PR adds one, list it here. -->

Implements: `docs/adr/XXXX-name.md`

## Risk summary

<!-- What can break? What is the blast radius? Cross-module impact? -->

## Rollback plan

<!-- How to revert if this misbehaves after merge. "Revert the commit" is acceptable
     only when the change is truly self-contained; otherwise describe the steps. -->

## Observability impact

<!-- New or changed logs / metrics / traces? Cardinality concerns?
     "None" is a valid answer for pure docs / refactor PRs. -->

## Checklist

- [ ] Conventional Commit subject (`type(scope): subject`)
- [ ] All commits GPG-signed (`git log --format='%h %G?' main..HEAD` shows `G` everywhere)
- [ ] **AI-assisted commits carry the `Co-Authored-By: Devin` trailer** (amend + force-push the feature branch if missing — never force-push `main`)
- [ ] Build runs under Java 25 (`sdk use java 25.0.2-zulu` before `./mvnw`)
- [ ] Spotless applied before commit (`./mvnw -B -ntp -Pquality spotless:apply` then review diff)
- [ ] Coverage gates honored (`./mvnw -B -ntp -Pquality verify`)
- [ ] No `var` in production code, no JUnit 4 imports, no reflection in tests
- [ ] Documentation updated (`README.md` / `AGENTS.md` as applicable)
- [ ] ADR linked or added
