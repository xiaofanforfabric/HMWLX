# 发版流程

## 一句话

在提交信息首行写 `rel:`、`RC:`、`beta:` 或 `dev:`，剩下的全自动。

```bash
git commit -m "rel: 首个正式版"
git push
```

---

## 版本阶段

```
dev  <  beta  <  RC  <  rel
```

| 阶段 | 前缀 | 版本示例 | 含义 |
|---|---|---|---|
| 开发 | `dev:` | `0.1-dev2` | 不可分发 |
| 公测 | `beta:` | `0.1-beta1` | 公开测试 |
| 候选 | `RC:` | `0.1-RC1` | 功能冻结，只修 bug |
| 正式 | `rel:` | `0.1` | 正式发布 |

后缀无数字等价于 1：`0.1-dev` 就是 `0.1-dev1`。

---

## 推进规则

从当前版本出发，打某个前缀会得到什么：

### 当前 `0.1-dev`

| 前缀 | 结果 |
|---|---|
| `rel:` | `0.1` |
| `RC:` | `0.1-RC1` |
| `beta:` | `0.1-beta1` |
| `dev:` | `0.1-dev2` |

### 当前 `0.1-beta1`

| 前缀 | 结果 |
|---|---|
| `rel:` | `0.1` |
| `RC:` | `0.1-RC1` |
| `beta:` | `0.1-beta2` |
| `dev:` | **失败**（阶段倒退） |

### 当前 `0.1-RC1`

| 前缀 | 结果 |
|---|---|
| `rel:` | `0.1` |
| `RC:` | `0.1-RC2` |
| `beta:` | **失败** |
| `dev:` | **失败** |

### 当前 `0.1`（正式版）

| 前缀 | 结果 |
|---|---|
| `rel:` | `0.2` |
| `RC:` | `0.2-RC1` |
| `beta:` | `0.2-beta1` |
| `dev:` | `0.2-dev1` |

**要点**：正式版发布后，任何前缀都会先递增小版本，因为「0.1 已经发完了，接下来是 0.2 的事」。

---

## 自动化链路

```
┌─────────────────────────────────────────────────────────────┐
│  git push  "rel: xxx"                                       │
└────────────────────┬────────────────────────────────────────┘
                     │
                     ▼
        ┌────────────────────────────┐
        │ release-prepare (GHA)      │
        │  1. 算下一个版本号          │
        │  2. 改 gradle.properties    │
        │  3. 开分支 release/v0.1     │
        │  4. 开 PR                  │
        └────────────┬───────────────┘
                     │
              人工 review & merge
                     │
                     ▼
        ┌────────────────────────────┐
        │ release-tag / job:tag      │
        │  1. 校验版本号              │
        │  2. 打 tag v0.1            │
        │  3. 推送 tag               │
        └────────────┬───────────────┘
                     │ tag 推送事件
                     ▼
        ┌────────────────────────────┐
        │ release-tag / job:build    │
        │  构建全部产物               │
        │  .exe .run .deb .rpm       │
        │  .AppImage .tar.gz         │
        └────────────┬───────────────┘
                     │
                     ▼
        ┌────────────────────────────┐
        │ release-tag / job:publish  │
        │  创建 GitHub Release        │
        │  上传全部附件               │
        └────────────────────────────┘

并行：CNB 收到同样的提交时，release-build stage 也跑一遍，
      产物进 CNB 制品库（双平台互相验证，也避免单点故障）
```

**为什么不一步到位打 tag**：版本号变更需要 review。如果直接打 tag，万一版本算错或合并冲突，tag 已经在远端了，只能删掉重来 —— 对于已经有人下载的发布很麻烦。

---

## 手动操作

```bash
# 只看会推进到什么版本，不改文件
./scripts/release.sh --detect "rel: 测试"
python3 scripts/bump-version.py --current 0.1-dev --stage rel

# 本地推进版本（CI 用的就是这条）
./scripts/release.sh --prepare "rel: 首个正式版"

# 校验并打 tag（CI 用）
./scripts/release.sh --verify
./scripts/release.sh --tag
```

GitHub 上也可以手动触发：**Actions → Release Prepare → Run workflow**，直接选阶段，不用写提交信息。

---

## 本地产物

```bash
./scripts/build.sh          # 编译 + 测试 + fat jar
./scripts/pack-windows.sh   # Windows .exe
./scripts/pack-linux.sh     # Linux 目录 + .run + tar.gz
./scripts/pack-linux-formats.sh  # DEB / RPM / AppImage
```

产物在 `dist/`。

---

## 改版本号

只改一处：

```properties
# gradle.properties
appVersion=0.1-dev
```

以下都自动跟随：

| 用途 | 派生方式 |
|---|---|
| jar 文件名 | `hmwlx-0.1-dev-all.jar` |
| Windows | `HMWLX-0.1-dev-win-x64.exe` |
| Linux 单文件 | `HMWLX-0.1-dev-linux-x64.run` |
| AppImage | `HMWLX-0.1-dev-x86_64.AppImage` |
| DEB | `hmwlx_0.1~dev_amd64.deb`（`~` 表示预发布，排序低于正式版） |
| RPM | `hmwlx-0.1.dev-1.x86_64.rpm`（RPM 不允许连字符） |
| git tag | `v0.1-dev` |

---

## 校验

版本逻辑有 27 个单元测试：

```bash
python3 scripts/test-bump-version.py
# 或随 test.sh 一起跑
./scripts/test.sh
```

测试覆盖了上面所有推进规则的组合，包括失败场景（阶段倒退必须报错）。

---

## 常见问题

**Q: 打 `dev:` 提示版本不能倒退**

当前版本是 beta/RC/rel 阶段。阶段只能往前走。要从 beta 回到 dev，得先发正式版（`rel:`）推进到下一个 0.2，然后 `dev:` 才能给出 `0.2-dev1`。

**Q: PR 合并后没打 tag**

`release-tag` 的 `tag` job 会检查版本号对应的 tag 是否已存在。如果 `gradle.properties` 没变更（比如推了个普通提交），会跳过打 tag，这是正常的。

**Q: tag 推送后没构建**

检查 `.github/workflows/release-tag.yml` 的 `on.push.tags` 是否包含 `v*`。只监听 `branches` 时 tag 事件不会触发。

**Q: 想重发某个版本**

```bash
git tag -d v0.1              # 删本地
git push origin :refs/tags/v0.1   # 删远端
# 然后在 GitHub Actions 手动触发 release-tag，指定 tag 名
```
