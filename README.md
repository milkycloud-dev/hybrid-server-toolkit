<p align="center"><img src="assets/icon.png" width="128" height="128" alt="Hybrid Server Toolkit icon"></p>

<h1 align="center">Hybrid Server Toolkit</h1>

<p align="center">Server-only NeoForge 1.21.1 mod with fixes for large modded servers: a Chunky pregeneration queue with a real on-disk completion check, recipe book recovery, a watchdog for hung shutdowns and faster Aether generation.</p>

<p align="center">
  <a href="https://github.com/milkycloud-dev/hybrid-server-toolkit/actions/workflows/build.yml"><img src="https://github.com/milkycloud-dev/hybrid-server-toolkit/actions/workflows/build.yml/badge.svg" alt="Build"></a>
  <a href="https://github.com/milkycloud-dev/hybrid-server-toolkit/releases/latest"><img src="https://img.shields.io/github/v/release/milkycloud-dev/hybrid-server-toolkit" alt="Release"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-proprietary-lightgrey" alt="License: proprietary"></a>
</p>

<p align="center"><a href="#english">English</a> | <a href="#русский">Русский</a></p>

<a id="english"></a>

## English

### Overview

The mod collects fixes that came out of running a heavily modded 1.21.1 server on a NeoForge + Bukkit hybrid core. Each part is independent and does nothing unless its trigger exists.

| Part | Problem | What the mod does |
|---|---|---|
| Pregeneration queue | Chunky starts every task at once and has no queue. Many worlds in parallel exhaust memory. | Runs the worlds from a list in order, at most `concurrent` at a time, and survives restarts. |
| Disk check | Chunky reports 100 % even when chunks failed or were lost in a crash during world saving. | After Chunky finishes, the next start reads the region files. A world is done only when every chunk of the square is stored with status `full`; otherwise it goes back into the queue. |
| Recipe book | On Youer the recipe book is empty after every death: its `restoreFrom` lacks vanilla's recipe book copy. | Copies the book on respawn, and on login restores recipes from completed recipe advancements. |
| Exit watchdog | An out-of-memory error during shutdown can leave background threads alive, so the process never exits and the panel thinks the server is running. | 90 seconds after the server thread has died, the process exits with code 1 so the panel restarts it. |
| Aether generation | Aether's dungeon blacklist filter asks the world-wide structure manager, which loads neighbour chunks synchronously during decoration. Profiling showed Aether generating about 40 times slower than the overworld. | The filter gets the structure manager bound to the generation region, as vanilla does at that step. |

### Pregeneration queue

The queue is configured in `config/notebuns_pregen.json`. Without the file the queue stays off.

```json
{
  "enabled": true,
  "concurrent": 1,
  "radius": 11000.0,
  "shape": "square",
  "pattern": "concentric",
  "worlds": ["minecraft:overworld", "minecraft:the_nether"],
  "done": [],
  "check": []
}
```

| Key | Default | Meaning |
|---|---|---|
| `enabled` | `true` in a new file | master switch |
| `concurrent` | `4` | how many worlds Chunky generates at the same time |
| `radius` | `5000` | radius around 0, 0 in blocks |
| `shape`, `pattern` | `square`, `concentric` | passed to Chunky; the disk check supports `square` only |
| `worlds` | empty | worlds in the order they should be generated |
| `done` | written by the mod | worlds that passed the disk check |
| `check` | written by the mod | worlds Chunky finished; checked at the next start |

The file is re-read every 30 seconds when it changes, so the list and `concurrent` can be edited on a running server. Tasks above `concurrent` are paused from the end of the list and continued later. A manual `chunky pause` holds the queue until `chunky continue` or a restart. Completion events that arrive while the server is already stopping are ignored, and a world listed as done whose Chunky task is not really finished goes back into the queue.

### Requirements

- Minecraft 1.21.1, NeoForge 21.1 or a NeoForge-based hybrid core
- Dedicated server only; the mod does not load on a client
- Chunky for the queue and the disk check (optional)
- The Aether for the generation fix (optional)
- Java 21

### Installation

1. Download `hybrid-server-toolkit-<version>.jar` from [Releases](https://github.com/milkycloud-dev/hybrid-server-toolkit/releases) and put it into `mods/` on the server.
2. For the queue, create `config/notebuns_pregen.json` as shown above.
3. Restart. The recipe book fix, the watchdog and the Aether fix work without configuration.

The mod id `notebuns_server` and the config file name are kept from the first deployment, so existing installs keep their state.

### Releases

Every tag `v*` is built by GitHub Actions from this source and published in [Releases](../../releases). The build log of each release is public, so every jar can be traced to its commit.

### Known limitations

- The queue always centres tasks on 0, 0.
- The disk check understands the `square` shape only and reads one world at a time in a background thread at startup.
- The recipe book recovery brings back recipes granted by advancements. Recipes unlocked only by crafting come back on the next craft. On the server it was written for this restored about 90 % of a book.
- The watchdog uses `Runtime.halt`, so shutdown hooks do not run in that case. It only fires after the server thread is already gone.
- Log lines are in Russian.

### License

Proprietary, all rights reserved. Official release binaries may be run unmodified on servers you operate. Copying, modifying or redistributing the code or the binaries requires written permission. Full terms: [LICENSE](LICENSE).

<a id="русский"></a>

## Русский

### Обзор

Мод собирает правки, которые понадобились для большого модового сервера 1.21.1 на гибридном ядре NeoForge + Bukkit. Части независимы и ничего не делают, если их повода нет.

| Часть | Проблема | Что делает мод |
|---|---|---|
| Очередь прегенерации | Chunky запускает все задачи сразу и не умеет очередь. Много миров параллельно съедают память. | Идёт по списку миров по порядку, не больше `concurrent` одновременно, и переживает перезапуски. |
| Проверка диска | Chunky показывает 100 %, даже если чанки упали с ошибкой или потерялись при падении во время сохранения миров. | Когда Chunky закончил, при следующем старте читаются файлы регионов. Мир готов, только если каждый чанк квадрата записан со статусом `full`; иначе он возвращается в очередь. |
| Книга рецептов | На Youer после каждой смерти книга рецептов пустая: в его `restoreFrom` нет ванильного копирования книги. | Копирует книгу при возрождении, а при входе возвращает рецепты из выполненных достижений. |
| Сторож выхода | Нехватка памяти во время остановки может оставить фоновые потоки живыми: процесс не выходит, а панель считает сервер работающим. | Через 90 секунд после смерти основного потока сервера процесс завершается с кодом 1, и панель его перезапускает. |
| Генерация Aether | Фильтр подземелий Aether спрашивает менеджер структур всего мира, а тот на шаге декораций синхронно грузит соседние чанки. По профилю Aether генерировался примерно в 40 раз медленнее верхнего мира. | Фильтр получает менеджер структур, привязанный к области генерации, как делает ваниль на этом шаге. |

### Очередь прегенерации

Очередь настраивается в `config/notebuns_pregen.json`. Без файла она выключена.

```json
{
  "enabled": true,
  "concurrent": 1,
  "radius": 11000.0,
  "shape": "square",
  "pattern": "concentric",
  "worlds": ["minecraft:overworld", "minecraft:the_nether"],
  "done": [],
  "check": []
}
```

| Ключ | По умолчанию | Назначение |
|---|---|---|
| `enabled` | `true` в новом файле | главный выключатель |
| `concurrent` | `4` | сколько миров Chunky генерирует одновременно |
| `radius` | `5000` | радиус вокруг 0, 0 в блоках |
| `shape`, `pattern` | `square`, `concentric` | передаются в Chunky; проверка диска умеет только `square` |
| `worlds` | пусто | миры в порядке генерации |
| `done` | пишет мод | миры, прошедшие проверку диска |
| `check` | пишет мод | миры, которые Chunky закончил; проверяются при следующем старте |

Файл перечитывается раз в 30 секунд, если он изменился, так что список и `concurrent` можно править на работающем сервере. Задачи сверх `concurrent` ставятся на паузу с конца списка и продолжаются позже. Ручной `chunky pause` держит очередь до `chunky continue` или перезапуска. События завершения, пришедшие, когда сервер уже останавливается, не засчитываются, а мир из `done`, чья задача у Chunky на самом деле не закончена, возвращается в очередь.

### Требования

- Minecraft 1.21.1, NeoForge 21.1 или гибридное ядро на NeoForge
- Только выделенный сервер, на клиенте мод не загружается
- Chunky для очереди и проверки диска (необязательно)
- The Aether для правки генерации (необязательно)
- Java 21

### Установка

1. Скачать `hybrid-server-toolkit-<версия>.jar` из [Releases](https://github.com/milkycloud-dev/hybrid-server-toolkit/releases) и положить в `mods/` на сервере.
2. Для очереди создать `config/notebuns_pregen.json`, как показано выше.
3. Перезапустить сервер. Книга рецептов, сторож и правка Aether работают без настройки.

Mod id `notebuns_server` и имя файла настроек остались от первой установки, чтобы существующие установки сохранили состояние.

### Релизы

Каждый тег `v*` собирается из этих исходников в GitHub Actions и публикуется в [Releases](../../releases). Лог сборки каждого релиза открыт, так что любой jar можно сверить с его коммитом.

### Известные ограничения

- Очередь всегда ставит центр задач в 0, 0.
- Проверка диска понимает только форму `square` и при старте читает миры по одному в фоновом потоке.
- Книга рецептов восстанавливается по достижениям. Рецепты, открытые только крафтом, вернутся при следующем крафте. На сервере, под который писался мод, так вернулось около 90 % книги.
- Сторож использует `Runtime.halt`, поэтому хуки завершения в этом случае не выполняются. Срабатывает он, только когда основной поток сервера уже завершился.
- Строки лога на русском.

### Лицензия

Проприетарная, все права защищены. Официальные сборки из релизов можно запускать без изменений на своих серверах. Копировать, изменять и распространять код или сборки можно только с письменного разрешения. Полный текст: [LICENSE](LICENSE).
