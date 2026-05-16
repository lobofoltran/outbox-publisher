# Roadmap prompts

Cada arquivo abaixo contém prompts auto-contidos para um agente (Devin, Claude
Code, etc.) executar **um item por PR**. A ordem dentro de cada arquivo é a
ordem sugerida de execução para minimizar conflitos de merge.

Regras gerais para qualquer agente executando estes prompts:

- Trabalhe em branch a partir de `main` atualizado (`git fetch origin main && git checkout -b <branch>`).
- Use o prefixo Conventional Commit indicado em cada prompt — release-please depende disso.
- Rode `./mvnw -B -ntp -Pquality verify` antes de abrir PR.
- **Não** edite mais de um item por PR. Se algum prompt parecer exigir, pare e peça revisão humana.
- Toda mudança breaking (`feat!` / `refactor!` / `chore!`) precisa de seção `BREAKING CHANGE:` no corpo do commit explicando migração.
- Não toque em `outbox-core` sem ADR — se um prompt parecer exigir, crie a ADR primeiro num PR separado.

Releases:

- [prompts/v0.4.0.md](prompts/v0.4.0.md) — limpar SPIs antes do 1.0
- [prompts/v0.5.0.md](prompts/v0.5.0.md) — production hardening + boundaries
- [prompts/v0.6.0.md](prompts/v0.6.0.md) — validar SPI com segundo dialeto
- [prompts/v1.0.0.md](prompts/v1.0.0.md) — congelar API
- [prompts/post-1.x.md](prompts/post-1.x.md) — evoluções permitidas + non-goals
