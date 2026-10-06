# data/ —— 游戏数据（**不随本仓库分发**）

这个目录用于存放**从游戏导出**的配置 JSON，是重新生成索引
（`app/src/main/assets/verses.json`）的输入。

> ⚠️ **本目录的 `*.json` 已被 `.gitignore` 忽略**：它们属于游戏素材，
> 不在本仓库分发。仓库只提供**生成索引的代码**，数据请自备。

## 为什么 App 不需要你准备数据

`app/src/main/assets/verses.json`（约 20 KB）**已随仓库提交**，它是上述游戏数据经
`tools/gen_verse_index.py` 抽取后的**最小投影**：

* 词句**首句**（已去标点）
* 对应**词牌**名
* **词人**姓名

App 运行时只读这个文件，**离线可用、无需你做任何准备**。
只有当你想要**重新生成**索引（例如游戏更新了词句库）时，才需要自己导出下面的原始数据。

## 如何自备数据

从你自己设备的游戏安装目录中取出这两个文件，按原名放进本目录：

| 文件 | 说明 |
|---|---|
| `SongCiVerseConfig.json` | 词句库（根对象含 `dataList` 数组） |
| `SongCiPoetConfig.json` | 词人表（用于把 `PoetIds` 换成姓名，可选） |

格式见 [`songci-verse-config.example.json`](songci-verse-config.example.json)
与 [`songci-poet-config.example.json`](songci-poet-config.example.json) —— 它们只保留
**结构骨架与字段说明**，不含真实词句内容。

## 生成索引

```bash
python tools/gen_verse_index.py          # 生成 + 校验 → app/src/main/assets/verses.json
python tools/gen_verse_index.py --check  # 只校验已生成的文件（不需要 data/）
```

`gen_verse_index.py` 需要用到 `SongCiVerseConfig.json` 里的这些字段：

| 字段 | 用途 |
|---|---|
| `ID` | 词句 id（索引里的 `id`） |
| `Name` | 词句名（索引里的 `name`，取自首句） |
| `Verse` | 词句正文 → 取**首句**（第一个 `。，、；：` 之前）作为匹配键 |
| `PaiName`（或等价字段） | 词牌名 |
| `PoetIds` | 词人 id，用于关联 `SongCiPoetConfig.json` 的 `Name` |
| `UnlockNeedMinister` | 解锁条件；含 `999`（需特殊名臣）的条目会被剔除 |

> 字段名随游戏版本可能变化。若生成脚本对不上，按实际 JSON 调整
> `tools/gen_verse_index.py` 里的字段读取逻辑即可。
