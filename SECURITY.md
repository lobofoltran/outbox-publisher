# Security Policy

## Reporting a Vulnerability

Please report security issues privately via [GitHub Security Advisories](https://github.com/lobofoltran/outbox-publisher/security/advisories/new). Do **not** open public issues for suspected vulnerabilities.

We aim to acknowledge reports within 72 hours.

## Artifact Signing

All release artifacts published to GitHub Packages (and any future distribution channels) are signed with a **CI-only** OpenPGP key, separate from any contributor's personal key. The key is rotated annually.

### Current CI signing key

| Field       | Value                                              |
| ----------- | -------------------------------------------------- |
| User ID     | `outbox-publisher CI <ci@lobofoltran.github.io>`   |
| Fingerprint | `F4EE FA2E 3FC2 16EA 1332 7295 F395 E84E 11DF E22C` |
| Algorithm   | ed25519                                            |
| Valid until | 2027-05-16                                         |

The ASCII-armored public key lives at <ref_file file="docs/security/ci-pubkey.asc" /> and is also attached to every GitHub Release.

### Verifying an artifact

```bash
# 1. Import the public key (one-time)
curl -sSL https://raw.githubusercontent.com/lobofoltran/outbox-publisher/main/docs/security/ci-pubkey.asc \
  | gpg --import

# 2. Verify the signature alongside the JAR
gpg --verify outbox-jdbc-0.1.0.jar.asc outbox-jdbc-0.1.0.jar
```

A successful verification prints `Good signature from "outbox-publisher CI ..."` with the fingerprint shown above. Anything else (no public key, BAD signature, different fingerprint) means the artifact is **not** trustworthy — do not use it and please open a security advisory.

### Key rotation

When the CI key is rotated, the new public key replaces `docs/security/ci-pubkey.asc` on `main` in the same commit that introduces it. Previous keys are preserved under `docs/security/archive/` so older releases remain verifiable.
