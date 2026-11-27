# 推送与发布流程

> 这份文档是给 AI agent 和自己看的操作手册。**别再试本机直推。**

## 为什么本机推不了

本机 git 凭据管理器里的 GitHub 账号是 `daysneco`，对本仓库只有读权限：

```
remote: Permission to hijoywell/flitr.git denied to daysneco.
fatal: ... The requested URL returned error: 403
```

写权限在构建服务器 `instabox-tjing` 上（那里配了 `hijoywell` 身份的
`~/.git-credentials`）。所以**所有推送都必须经由服务器**。

本机也不配置 git remote —— 故意保持这样，避免误推出错账号。

---

## 推送流程（照抄即可）

### 参数

| 项 | 值 |
|---|---|
| SSH | `ssh -p 2222 user@instabox-tjing` |
| 服务器仓库 | `/data/home/flitr`（`origin` 已指向 `https://github.com/hijoywell/flitr.git`） |
| 服务器身份 | `hijoywell` / 有写权限 |
| 本机仓库 | 当前工作目录 |

### 步骤

**1. 本机打 bundle（比 scp 整个目录快，且只传对象）**

```powershell
git bundle create "$env:TEMP\flitr.bundle" --all
```

**2. 上传**

```powershell
scp -P 2222 -o StrictHostKeyChecking=no "$env:TEMP\flitr.bundle" user@instabox-tjing:/data/home/flitr.bundle
```

**3. 服务器侧对齐并推送**

```bash
ssh -p 2222 -o StrictHostKeyChecking=no user@instabox-tjing '
  cd ~/flitr &&
  git fetch ~/flitr.bundle "refs/heads/main:refs/remotes/bundle/main" --force &&
  git checkout -B main refs/remotes/bundle/main &&
  git push origin main &&
  git push origin --tags
'
```

> 服务器上若有独有提交（构建环境相关），`checkout -B` 会丢掉分支引用但对象仍在，
> 先用 `git branch server-backup-$(date +%Y%m%d%H%M) <旧HEAD>` 留个备份再对齐。

**4. 等 CI 建 Release**

push tag（`v*`）会触发 `.github/workflows/android.yml`：跑单测 → 构建 release APK →
校验签名 → `gh release create` 自动上传 APK。

查状态：

```powershell
ssh -p 2222 -o StrictHostKeyChecking=no user@instabox-tjing "bash ~/flitr_check.sh"
```

（服务器上已有 `~/flitr_check.sh`，读 `~/.git-credentials` 拿 token 查 runs 与 releases。）

---

## 发版清单

1. 改 `app/build.gradle.kts`：`appVersionName` / `appVersionCode`（code 必须单调 +1）
2. 补 `CHANGELOG.md` 顶部新版本条目，并把开头那句「当前为 x.y.z」一起改
3. 单测：`gradle :app:testDebugUnitTest --tests "com.aios.flitr.engine.*"`
4. 构建并装机验证：`gradle :app:assembleRelease` → `adb install -r`
5. 提交：`git commit`
6. 打 tag：`git tag -a vX.Y.Z -m "..."`
7. **按上面的流程推送**（本机推不了）
8. 确认 Release 页面出现 APK

---

## 已知坑

| 坑 | 说明 |
|---|---|
| `ssh -p 2222` 偶尔 "Connection refused" | 瞬时抖动，重试即可，不是配置问题 |
| release 包里 R8 会剥掉反射加载的类 | ML Kit 曾因此完全失效，见 `proguard-rules.pro` |
| ML Kit 模型文件必须 noCompress | 见 `app/build.gradle.kts` 的 `androidResources` |
| 服务器 git 输出尾部常有 `close - IO is still pending` | 无害，SSH 通道关闭时的正常提示 |
