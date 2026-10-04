# Contributing to Trade Collector

Thanks for your interest in contributing! Trade Collector is a Kotlin/JVM service
licensed under AGPL-3.0-or-later. Contributions are welcome in any form:
bug reports, feature requests, documentation, and pull requests.

## Contributor License Agreement (CLA)

Before your first pull request can be merged, you must agree to the
[Contributor License Agreement](CLA.md). By submitting a pull request you confirm
that you accept its terms. The CLA allows the project owner to relicense
contributions under a commercial license, keeping the dual-licensing model intact.

## Getting Started

### Requirements

- JDK 21+ (Gradle 8.14)
- PostgreSQL for local development (`make dev-up` via Docker Compose)

### Build

```bash
./gradlew build        # compile and run tests
./gradlew shadowJar    # build the fat JAR
make dev-run           # build and run locally against a dev database
```

## Project Structure

```
src/main/kotlin/
├── Main.kt                          # Entry point
├── config/                          # Configuration
├── exchange/                        # Exchange adapters (Binance, Bybit)
├── model/                           # Data models
├── service/                         # Business logic (TradeProcessor, BatchScheduler)
└── storage/postgres/                # Database access
```

## Code Style

- Kotlin official code style.
- Keep file headers intact; every source file carries the SPDX notice:

  ```text
  Copyright (C) 2026 Sergey Orlov
  SPDX-License-Identifier: AGPL-3.0-or-later
  ```

## Submitting a Pull Request

1. Fork the repository and create a feature branch.
2. Make focused, atomic commits with clear messages.
3. Add or update tests where applicable.
4. Run `./gradlew build` and make sure it passes.
5. Open a pull request with a description of the change and its motivation.

By opening a pull request you confirm you have read and accepted the
[CLA](CLA.md).
